from __future__ import annotations

import argparse
import hashlib
import json
import multiprocessing
import os
import shutil
import sqlite3
import struct
import tempfile
from collections import Counter
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait
from contextlib import nullcontext
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Iterator, Mapping

from .osm_boundary_renderer import (
    BoundaryRelationMembership,
    BoundaryRendererError,
    SelectedBoundaryWay,
    _boundary_zoom_admitted,
    _source_feature_sha256,
    admin_boundary_subtype,
    build_boundary_way_feature,
)
from .osm_global_waterway_package import (
    StrictOplNode,
    StrictOplRelation,
    StrictOplWay,
    iter_strict_waterway_opl,
)
from .reference_presentation_policy import (
    PRESENTATION_POLICY_SHA256,
    SemanticSubtype,
    outline_visibility_rule,
)
from .reference_size_policy import DESTINATION_RESERVE_BYTES, HARD_COMPONENT_PACKAGE_BYTES
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    MAX_RECORDS_PER_TILE,
    MAX_RENDERER_RECORD_BYTES,
    MAX_TILE_BYTES,
    TILE_PAYLOAD_MAGIC,
    RendererTileRecord,
    _decode_renderer_record,
    _validate_package_id,
    write_streaming_package,
)
from .semantic_model import FeatureKind, LayerGroup, renderer_order_key, renderer_record_bytes


_EXTRACTION_SCHEMA = "flightalert.osm-boundary-extraction-receipt.v1"
_EXTRACTION_POLICY = "flightalert.osm-boundary-closure.v1"
_INGEST_SCHEMA = "flightalert.experiment8.osm-boundary-ingest-receipt.v1"
_DATABASE_SCHEMA = "flightalert.experiment8.osm-boundary-store.v1"
_INGEST_DOMAIN = b"FAE8OSMBOUNDARYINGEST1\0"
_RENDER_SEMANTIC_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_FINAL_SEMANTIC_DOMAIN = b"FAE8OSMBOUNDARYFINAL1\0"
_BUILD_SCHEMA = "flightalert.experiment8.osm-boundary-build.v1"
_RENDER_RUN_SCHEMA = "flightalert.experiment8.osm-boundary-render-run.v1"
_SPOOL_MAGIC = b"FAE8BRSP1"
_MAX_RECEIPT_BYTES = 16 * 1024 * 1024
_MAX_WORKERS = 10
_BOUNDARY_RELATION_TYPES = frozenset(("boundary", "multipolygon"))
_GEOMETRY_ROLES = frozenset(("outer", "inner"))
DEFAULT_BOUNDARY_ZOOMS = tuple(range(3, 12))


class BoundaryProductionError(ValueError):
    """A production boundary stage cannot preserve bounded source authority."""


@dataclass(frozen=True, slots=True)
class BoundaryIngestLimits:
    max_database_bytes: int = 64 * 1024 * 1024 * 1024
    destination_reserve_bytes: int = DESTINATION_RESERVE_BYTES

    def __post_init__(self) -> None:
        if type(self.max_database_bytes) is not int or self.max_database_bytes <= 0:
            raise BoundaryProductionError("boundary ingest database byte quota is invalid")
        if (
            type(self.destination_reserve_bytes) is not int
            or self.destination_reserve_bytes < 0
        ):
            raise BoundaryProductionError("boundary ingest destination reserve is invalid")


@dataclass(frozen=True, slots=True)
class BoundaryProductionLimits:
    workers: int = 10
    max_in_flight_batches: int = 20
    max_features_per_batch: int = 64
    max_points_per_batch: int = 128_000
    max_input_bytes_per_batch: int = 32 * 1024 * 1024
    max_spool_bytes_per_batch: int = 64 * 1024 * 1024
    max_total_spool_bytes: int = 2 * 1024 * 1024 * 1024
    max_render_database_bytes: int = 64 * 1024 * 1024 * 1024
    max_runtime_package_bytes: int = HARD_COMPONENT_PACKAGE_BYTES
    destination_reserve_bytes: int = DESTINATION_RESERVE_BYTES

    def __post_init__(self) -> None:
        if type(self.workers) is not int or not 1 <= self.workers <= _MAX_WORKERS:
            raise BoundaryProductionError("boundary worker count is invalid")
        if (
            type(self.max_in_flight_batches) is not int
            or not self.workers
            <= self.max_in_flight_batches
            <= self.workers * 2
        ):
            raise BoundaryProductionError("boundary in-flight batch bound is invalid")
        for value, label in (
            (self.max_features_per_batch, "features per batch"),
            (self.max_points_per_batch, "points per batch"),
            (self.max_input_bytes_per_batch, "input bytes per batch"),
            (self.max_spool_bytes_per_batch, "spool bytes per batch"),
            (self.max_total_spool_bytes, "total spool bytes"),
            (self.max_render_database_bytes, "render database bytes"),
            (self.max_runtime_package_bytes, "runtime package bytes"),
        ):
            if type(value) is not int or value <= 0:
                raise BoundaryProductionError(f"boundary {label} is invalid")
        if self.max_total_spool_bytes < (
            self.max_in_flight_batches * self.max_spool_bytes_per_batch
        ):
            raise BoundaryProductionError(
                "boundary total spool bound cannot retain every in-flight batch"
            )
        if (
            type(self.destination_reserve_bytes) is not int
            or self.destination_reserve_bytes < 0
        ):
            raise BoundaryProductionError("boundary destination reserve is invalid")


@dataclass(frozen=True, slots=True)
class BoundaryIngestResult:
    database_path: Path
    ingest_receipt_path: Path
    source_closure_sha256: str
    ingest_semantic_sha256: str
    selected_features: int
    selected_ways: int
    required_nodes: int
    relation_memberships: int


@dataclass(frozen=True, slots=True)
class BoundaryRenderInput:
    ordinal: int
    selected: SelectedBoundaryWay
    nodes: tuple[StrictOplNode, ...]

    @property
    def way(self) -> StrictOplWay:
        return self.selected.way

    @property
    def subtype(self) -> SemanticSubtype:
        return self.selected.subtype

    @property
    def direct(self) -> bool:
        return self.selected.direct

    @property
    def memberships(self) -> tuple[BoundaryRelationMembership, ...]:
        return self.selected.memberships

    def node_mapping(self) -> Mapping[int, StrictOplNode]:
        return MappingProxyType({node.object_id: node for node in self.nodes})


@dataclass(frozen=True, slots=True)
class BoundaryBuildResult:
    output_directory: Path
    build_receipt_path: Path
    renderer_semantic_sha256: str
    records_sha256: str
    index_sha256: str
    present_tile_count: int


@dataclass(frozen=True, slots=True)
class _RenderBatchJob:
    batch: tuple[BoundaryRenderInput, ...]
    source_generation_sha256: str
    zooms: tuple[int, ...]
    spool_path: str
    spool_byte_quota: int


@dataclass(frozen=True, slots=True)
class _SpoolDescriptor:
    start_ordinal: int
    end_ordinal: int
    path: str
    byte_count: int
    sha256: str
    record_count: int


def _canonical_json_bytes(document: object) -> bytes:
    try:
        return (
            json.dumps(
                document,
                allow_nan=False,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as error:
        raise BoundaryProductionError("boundary document is not canonical JSON") from error


def _fixed_order_json_bytes(document: object, *, trailing_lf: bool) -> bytes:
    try:
        text = json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return (text + ("\n" if trailing_lf else "")).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as error:
        raise BoundaryProductionError("extraction receipt is not canonical JSON") from error


def _sha256_file(path: Path, label: str) -> tuple[int, str]:
    try:
        before = path.stat()
        digest = hashlib.sha256()
        total = 0
        with path.open("rb") as handle:
            opened = os.fstat(handle.fileno())
            while True:
                chunk = handle.read(4 * 1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                total += len(chunk)
            after_handle = os.fstat(handle.fileno())
        after = path.stat()
    except OSError as error:
        raise BoundaryProductionError(f"{label} is unreadable") from error
    signature = lambda value: (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
    )
    if (
        total != before.st_size
        or signature(before) != signature(opened)
        or signature(before) != signature(after_handle)
        or signature(before) != signature(after)
    ):
        raise BoundaryProductionError(f"{label} changed while hashing")
    return total, digest.hexdigest()


def _hex_sha256(value: object, label: str) -> str:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise BoundaryProductionError(f"{label} is not lowercase SHA-256")
    return value


def _nonnegative_int(value: object, label: str) -> int:
    if type(value) is not int or value < 0 or value > (1 << 63) - 1:
        raise BoundaryProductionError(f"{label} is not a bounded nonnegative integer")
    return value


def _nonnegative_u64(value: object, label: str) -> int:
    if type(value) is not int or not 0 <= value <= (1 << 64) - 1:
        raise BoundaryProductionError(f"{label} is not an unsigned 64-bit integer")
    return value


def _mapping(value: object, label: str) -> dict[str, object]:
    if type(value) is not dict:
        raise BoundaryProductionError(f"{label} is not an exact object")
    return value


def _ordered_fields(
    value: object,
    fields: tuple[str, ...],
    label: str,
) -> dict[str, object]:
    document = _mapping(value, label)
    if set(document) != set(fields):
        raise BoundaryProductionError(f"{label} fields differ")
    return {field: document[field] for field in fields}


def _receipt_path(value: object, label: str) -> str:
    if type(value) is not str or not value or "\0" in value or not Path(value).is_absolute():
        raise BoundaryProductionError(f"{label} is not an absolute path")
    return value


def _object_counts(value: object, label: str) -> dict[str, int]:
    ordered = _ordered_fields(value, ("nodes", "ways", "relations"), label)
    return {
        key: _nonnegative_int(ordered[key], f"{label} {key}")
        for key in ordered
    }


def _validated_extraction_document(
    value: object,
    *,
    closure_bytes: int,
    closure_sha256: str,
) -> tuple[dict[str, object], bytes]:
    document = _ordered_fields(
        value,
        (
            "schema",
            "state",
            "semantic",
            "semanticIdentitySha256",
            "execution",
            "failureClosedPolicy",
        ),
        "boundary extraction receipt",
    )
    if document["schema"] != _EXTRACTION_SCHEMA or document["state"] != "complete":
        raise BoundaryProductionError("boundary extraction receipt is not complete")

    semantic = _ordered_fields(
        document["semantic"],
        ("policy", "tool", "input", "output", "counts"),
        "boundary extraction semantic identity",
    )
    if semantic["policy"] != _EXTRACTION_POLICY:
        raise BoundaryProductionError("boundary extraction policy differs")
    tool = _ordered_fields(
        semantic["tool"],
        ("name", "version", "sourceSha256"),
        "boundary extraction tool",
    )
    if (
        tool["name"] != "flightalert-native-osm-boundary-extractor"
        or tool["version"] != "1"
    ):
        raise BoundaryProductionError("boundary extraction tool differs")
    tool["sourceSha256"] = _hex_sha256(
        tool["sourceSha256"], "boundary extraction tool source"
    )
    input_fact = _ordered_fields(
        semantic["input"],
        ("path", "bytes", "sha256"),
        "boundary extraction input",
    )
    input_fact["path"] = _receipt_path(
        input_fact["path"], "boundary extraction input path"
    )
    input_fact["bytes"] = _nonnegative_int(
        input_fact["bytes"], "boundary extraction input bytes"
    )
    if input_fact["bytes"] <= 0:
        raise BoundaryProductionError("boundary extraction input is empty")
    input_fact["sha256"] = _hex_sha256(
        input_fact["sha256"], "boundary extraction input"
    )
    output_fact = _ordered_fields(
        semantic["output"],
        ("bytes", "sha256"),
        "boundary extraction output",
    )
    output_fact["bytes"] = _nonnegative_int(
        output_fact["bytes"], "boundary extraction output bytes"
    )
    output_fact["sha256"] = _hex_sha256(
        output_fact["sha256"], "boundary extraction output"
    )
    if output_fact != {"bytes": closure_bytes, "sha256": closure_sha256}:
        raise BoundaryProductionError("boundary extraction output identity differs")
    counts = _ordered_fields(
        semantic["counts"],
        ("inputObjects", "outputObjects"),
        "boundary extraction counts",
    )
    counts["inputObjects"] = _object_counts(
        counts["inputObjects"], "boundary extraction input object counts"
    )
    counts["outputObjects"] = _object_counts(
        counts["outputObjects"], "boundary extraction output object counts"
    )
    semantic = {
        "policy": semantic["policy"],
        "tool": tool,
        "input": input_fact,
        "output": output_fact,
        "counts": counts,
    }
    semantic_raw = _fixed_order_json_bytes(semantic, trailing_lf=False)
    if _hex_sha256(
        document["semanticIdentitySha256"],
        "boundary extraction semantic identity",
    ) != hashlib.sha256(semantic_raw).hexdigest():
        raise BoundaryProductionError("boundary extraction semantic identity differs")

    execution = _ordered_fields(
        document["execution"],
        ("executable", "workerCount", "resourceBounds"),
        "boundary extraction execution",
    )
    executable = _ordered_fields(
        execution["executable"],
        ("path", "bytes", "sha256"),
        "boundary extraction executable",
    )
    executable["path"] = _receipt_path(
        executable["path"], "boundary extraction executable path"
    )
    executable["bytes"] = _nonnegative_int(
        executable["bytes"], "boundary extraction executable bytes"
    )
    if executable["bytes"] <= 0:
        raise BoundaryProductionError("boundary extraction executable is empty")
    executable["sha256"] = _hex_sha256(
        executable["sha256"], "boundary extraction executable"
    )
    worker_count = _nonnegative_int(
        execution["workerCount"], "boundary extraction worker count"
    )
    if not 1 <= worker_count <= 10:
        raise BoundaryProductionError("boundary extraction worker count differs")
    resource_bounds = _ordered_fields(
        execution["resourceBounds"],
        (
            "maxWorkers",
            "maxBlobHeaderBytes",
            "maxBlobBytes",
            "maxUncompressedBlobBytes",
            "sortChunkBytes",
            "bloomBytes",
            "queueDepth",
            "maxWorkBytes",
            "maxOutputBytes",
            "peakWorkBytes",
        ),
        "boundary extraction resource bounds",
    )
    expected_bounds = {
        "maxWorkers": 10,
        "maxBlobHeaderBytes": 65_536,
        "maxBlobBytes": 67_108_864,
        "maxUncompressedBlobBytes": 33_554_432,
        "sortChunkBytes": 33_554_432,
        "bloomBytes": 33_554_432,
        "queueDepth": 10,
    }
    if any(resource_bounds[key] != value for key, value in expected_bounds.items()):
        raise BoundaryProductionError("boundary extraction resource bounds differ")
    for key in ("maxWorkBytes", "maxOutputBytes", "peakWorkBytes"):
        resource_bounds[key] = _nonnegative_u64(
            resource_bounds[key], f"boundary extraction {key}"
        )
    if (
        resource_bounds["maxWorkBytes"] <= 0
        or resource_bounds["maxOutputBytes"] <= 0
        or resource_bounds["peakWorkBytes"] > resource_bounds["maxWorkBytes"]
        or closure_bytes > resource_bounds["maxOutputBytes"]
    ):
        raise BoundaryProductionError("boundary extraction disk quotas differ")
    execution = {
        "executable": executable,
        "workerCount": worker_count,
        "resourceBounds": resource_bounds,
    }
    failure_policy = _ordered_fields(
        document["failureClosedPolicy"],
        (
            "completionMarker",
            "existingTargets",
            "malformedOrIncompleteInput",
            "temporaryOutput",
        ),
        "boundary extraction failure-closed policy",
    )
    if failure_policy != {
        "completionMarker": "receipt-last",
        "existingTargets": "never-overwritten",
        "malformedOrIncompleteInput": "reject",
        "temporaryOutput": "removed-on-failure",
    }:
        raise BoundaryProductionError("boundary extraction failure-closed policy differs")
    ordered = {
        "schema": document["schema"],
        "state": document["state"],
        "semantic": semantic,
        "semanticIdentitySha256": document["semanticIdentitySha256"],
        "execution": execution,
        "failureClosedPolicy": failure_policy,
    }
    return ordered, _fixed_order_json_bytes(ordered, trailing_lf=True)


def _read_extraction_receipt(
    receipt_path: Path,
    closure_path: Path,
    *,
    closure_bytes: int,
    closure_sha256: str,
) -> tuple[dict[str, object], bytes]:
    if receipt_path != Path(str(closure_path) + ".receipt.json"):
        raise BoundaryProductionError(
            "boundary extraction receipt is not the closure completion marker"
        )
    if not receipt_path.is_file() or receipt_path.is_symlink():
        raise BoundaryProductionError("boundary extraction receipt is unavailable")
    try:
        raw = receipt_path.read_bytes()
    except OSError as error:
        raise BoundaryProductionError("boundary extraction receipt is unreadable") from error
    if not 0 < len(raw) <= _MAX_RECEIPT_BYTES:
        raise BoundaryProductionError("boundary extraction receipt byte length is invalid")
    try:
        document = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise BoundaryProductionError("boundary extraction receipt is invalid JSON") from error
    document, expected_raw = _validated_extraction_document(
        document,
        closure_bytes=closure_bytes,
        closure_sha256=closure_sha256,
    )
    if expected_raw != raw:
        raise BoundaryProductionError("boundary extraction receipt is not canonical")
    return document, raw


def _connect_new(path: Path, *, max_bytes: int) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    try:
        page_size = int(connection.execute("PRAGMA page_size").fetchone()[0])
        connection.execute(f"PRAGMA max_page_count={max(1, max_bytes // page_size)}")
        connection.execute("PRAGMA cache_size=-65536")
        connection.execute("PRAGMA temp_store=FILE")
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        connection.executescript(
            """
        CREATE TABLE meta (
            key TEXT PRIMARY KEY NOT NULL,
            value BLOB NOT NULL
        ) WITHOUT ROWID;
        CREATE TABLE source_nodes (
            node_id INTEGER PRIMARY KEY NOT NULL,
            version INTEGER NOT NULL,
            timestamp TEXT NOT NULL,
            longitude_e7 INTEGER NOT NULL,
            latitude_e7 INTEGER NOT NULL
        );
        CREATE TABLE source_ways (
            way_id INTEGER PRIMARY KEY NOT NULL,
            version INTEGER NOT NULL,
            timestamp TEXT NOT NULL
        );
        CREATE TABLE way_tags (
            way_id INTEGER NOT NULL REFERENCES source_ways(way_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (way_id, ordinal)
        ) WITHOUT ROWID;
        CREATE TABLE way_nodes (
            way_id INTEGER NOT NULL REFERENCES source_ways(way_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            node_id INTEGER NOT NULL,
            PRIMARY KEY (way_id, ordinal)
        ) WITHOUT ROWID;
        CREATE INDEX way_nodes_by_node ON way_nodes(node_id);
        CREATE TABLE selected_features (
            way_id INTEGER NOT NULL,
            subtype INTEGER NOT NULL,
            direct INTEGER NOT NULL CHECK (direct IN (0, 1)),
            PRIMARY KEY (way_id, subtype)
        ) WITHOUT ROWID;
        CREATE TABLE relation_memberships (
            way_id INTEGER NOT NULL,
            subtype INTEGER NOT NULL,
            relation_id INTEGER NOT NULL,
            role TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            PRIMARY KEY (way_id, subtype, relation_id, role, ordinal),
            FOREIGN KEY (way_id, subtype)
                REFERENCES selected_features(way_id, subtype) ON DELETE CASCADE
        ) WITHOUT ROWID;
        """
        )
    except sqlite3.Error as error:
        connection.close()
        if "full" in str(error).casefold():
            raise BoundaryProductionError(
                "boundary ingest database byte quota is too small"
            ) from error
        raise
    return connection


def _direct_subtypes(way: StrictOplWay) -> tuple[SemanticSubtype, ...]:
    tags = dict(way.tags)
    subtypes: list[SemanticSubtype] = []
    if tags.get("natural") == "coastline":
        subtypes.append(SemanticSubtype.COASTLINE)
    if tags.get("boundary") == "administrative":
        subtype = admin_boundary_subtype(tags.get("admin_level", ""))
        if subtype is not None:
            subtypes.append(subtype)
    return tuple(subtypes)


def _upsert_selected(
    connection: sqlite3.Connection,
    way_id: int,
    subtype: SemanticSubtype,
    *,
    direct: bool,
) -> None:
    connection.execute(
        """
        INSERT INTO selected_features(way_id, subtype, direct) VALUES (?, ?, ?)
        ON CONFLICT(way_id, subtype) DO UPDATE SET
            direct = MAX(selected_features.direct, excluded.direct)
        """,
        (way_id, subtype.value, int(direct)),
    )


def _ingest_records(
    connection: sqlite3.Connection,
    closure_path: Path,
) -> Counter[str]:
    counts: Counter[str] = Counter()
    try:
        with closure_path.open("rb") as stream:
            for record in iter_strict_waterway_opl(stream):
                value = record.value
                if isinstance(value, StrictOplNode):
                    counts["nodes"] += 1
                    connection.execute(
                        "INSERT INTO source_nodes VALUES (?, ?, ?, ?, ?)",
                        (
                            value.object_id,
                            value.version,
                            value.timestamp,
                            value.longitude_e7,
                            value.latitude_e7,
                        ),
                    )
                elif isinstance(value, StrictOplWay):
                    counts["ways"] += 1
                    connection.execute(
                        "INSERT INTO source_ways VALUES (?, ?, ?)",
                        (value.object_id, value.version, value.timestamp),
                    )
                    connection.executemany(
                        "INSERT INTO way_tags VALUES (?, ?, ?, ?)",
                        (
                            (value.object_id, ordinal, key, tag_value)
                            for ordinal, (key, tag_value) in enumerate(value.tags)
                        ),
                    )
                    connection.executemany(
                        "INSERT INTO way_nodes VALUES (?, ?, ?)",
                        (
                            (value.object_id, ordinal, node_id)
                            for ordinal, node_id in enumerate(value.node_refs)
                        ),
                    )
                    for subtype in _direct_subtypes(value):
                        _upsert_selected(
                            connection, value.object_id, subtype, direct=True
                        )
                else:
                    counts["relations"] += 1
                    _admit_relation(connection, value)
    except (OSError, sqlite3.Error, BoundaryRendererError, ValueError) as error:
        if isinstance(error, BoundaryProductionError):
            raise
        raise BoundaryProductionError(
            f"boundary closure ingest failed: {error}"
        ) from error
    return counts


def _admit_relation(
    connection: sqlite3.Connection,
    relation: StrictOplRelation,
) -> None:
    tags = dict(relation.tags)
    subtype = (
        admin_boundary_subtype(tags.get("admin_level", ""))
        if tags.get("boundary") == "administrative"
        else None
    )
    if subtype is None or tags.get("type") not in _BOUNDARY_RELATION_TYPES:
        return
    for member in relation.members:
        if member.role not in _GEOMETRY_ROLES:
            continue
        if member.object_type != "w":
            raise BoundaryProductionError(
                f"boundary relation {relation.object_id} has unsupported "
                f"{member.role} member type {member.object_type!r}"
            )
        _upsert_selected(connection, member.ref, subtype, direct=False)
        connection.execute(
            "INSERT OR IGNORE INTO relation_memberships VALUES (?, ?, ?, ?, ?)",
            (
                member.ref,
                subtype.value,
                relation.object_id,
                member.role,
                member.ordinal,
            ),
        )


def _prune_and_validate(connection: sqlite3.Connection) -> tuple[int, int, int, int]:
    connection.execute(
        "DELETE FROM source_ways WHERE way_id NOT IN "
        "(SELECT DISTINCT way_id FROM selected_features)"
    )
    missing_ways = int(
        connection.execute(
            """
            SELECT COUNT(*) FROM (
                SELECT DISTINCT selected_features.way_id
                FROM selected_features
                LEFT JOIN source_ways USING (way_id)
                WHERE source_ways.way_id IS NULL
            )
            """
        ).fetchone()[0]
    )
    if missing_ways:
        raise BoundaryProductionError("boundary selection references missing ways")
    missing_nodes = int(
        connection.execute(
            """
            SELECT COUNT(*) FROM (
                SELECT DISTINCT way_nodes.node_id
                FROM way_nodes
                LEFT JOIN source_nodes USING (node_id)
                WHERE source_nodes.node_id IS NULL
            )
            """
        ).fetchone()[0]
    )
    if missing_nodes:
        raise BoundaryProductionError("boundary selected ways reference missing nodes")
    connection.execute(
        "DELETE FROM source_nodes WHERE node_id NOT IN (SELECT DISTINCT node_id FROM way_nodes)"
    )
    selected_features = int(
        connection.execute("SELECT COUNT(*) FROM selected_features").fetchone()[0]
    )
    selected_ways = int(
        connection.execute("SELECT COUNT(*) FROM source_ways").fetchone()[0]
    )
    required_nodes = int(
        connection.execute("SELECT COUNT(*) FROM source_nodes").fetchone()[0]
    )
    memberships = int(
        connection.execute("SELECT COUNT(*) FROM relation_memberships").fetchone()[0]
    )
    if not selected_features or not selected_ways or not required_nodes:
        raise BoundaryProductionError("boundary closure selected no usable geometry")
    return selected_features, selected_ways, required_nodes, memberships


def _meta_put(connection: sqlite3.Connection, key: str, value: object) -> None:
    connection.execute(
        "INSERT INTO meta(key, value) VALUES (?, ?)",
        (key, _canonical_json_bytes(value)),
    )


def _meta_get(connection: sqlite3.Connection, key: str) -> object:
    row = connection.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    if row is None:
        raise BoundaryProductionError(f"boundary database lacks {key!r}")
    try:
        return json.loads(bytes(row[0]).decode("utf-8", "strict"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise BoundaryProductionError(f"boundary database {key!r} is corrupt") from error


def _load_render_input(
    connection: sqlite3.Connection,
    ordinal: int,
    way_id: int,
    subtype_value: int,
    direct: int,
) -> BoundaryRenderInput:
    way_row = connection.execute(
        "SELECT version, timestamp FROM source_ways WHERE way_id = ?", (way_id,)
    ).fetchone()
    if way_row is None:
        raise BoundaryProductionError(f"boundary database lost way {way_id}")
    tags = tuple(
        (str(row[0]), str(row[1]))
        for row in connection.execute(
            "SELECT key, value FROM way_tags WHERE way_id = ? ORDER BY ordinal",
            (way_id,),
        )
    )
    node_rows = tuple(
        connection.execute(
            """
            SELECT n.node_id, n.version, n.timestamp, n.longitude_e7, n.latitude_e7
            FROM way_nodes AS w
            JOIN source_nodes AS n ON n.node_id = w.node_id
            WHERE w.way_id = ?
            ORDER BY w.ordinal
            """,
            (way_id,),
        )
    )
    refs = tuple(int(row[0]) for row in node_rows)
    nodes_by_id: dict[int, StrictOplNode] = {}
    for row in node_rows:
        node_id = int(row[0])
        nodes_by_id.setdefault(
            node_id,
            StrictOplNode(
                node_id,
                int(row[1]),
                str(row[2]),
                (),
                int(row[3]),
                int(row[4]),
            ),
        )
    memberships = tuple(
        BoundaryRelationMembership(int(row[0]), str(row[1]), int(row[2]))
        for row in connection.execute(
            """
            SELECT relation_id, role, ordinal
            FROM relation_memberships
            WHERE way_id = ? AND subtype = ?
            ORDER BY relation_id, role, ordinal
            """,
            (way_id, subtype_value),
        )
    )
    try:
        subtype = SemanticSubtype(subtype_value)
    except ValueError as error:
        raise BoundaryProductionError("boundary database subtype is unknown") from error
    way = StrictOplWay(way_id, int(way_row[0]), str(way_row[1]), tags, refs)
    return BoundaryRenderInput(
        ordinal=ordinal,
        selected=SelectedBoundaryWay(way, subtype, bool(direct), memberships),
        nodes=tuple(nodes_by_id.values()),
    )


def _render_input_bytes(value: BoundaryRenderInput) -> int:
    return (
        128
        + len(value.way.node_refs) * 32
        + sum(
            len(key.encode("utf-8", "strict"))
            + len(tag_value.encode("utf-8", "strict"))
            + 16
            for key, tag_value in value.way.tags
        )
        + sum(len(item.role.encode("utf-8", "strict")) + 32 for item in value.memberships)
    )


def iter_boundary_render_batches(
    database_path: Path,
    *,
    limits: BoundaryProductionLimits = BoundaryProductionLimits(),
    start_ordinal: int = 0,
) -> Iterator[tuple[BoundaryRenderInput, ...]]:
    """Yield source-ordered per-way batches without materializing the closure."""

    if not isinstance(database_path, Path) or not database_path.is_file():
        raise BoundaryProductionError("boundary database is unavailable")
    if not isinstance(limits, BoundaryProductionLimits):
        raise BoundaryProductionError("boundary production limits are invalid")
    if type(start_ordinal) is not int or start_ordinal < 0:
        raise BoundaryProductionError("boundary render start ordinal is invalid")
    try:
        connection = sqlite3.connect(database_path)
        connection.execute("PRAGMA query_only=ON")
        if _meta_get(connection, "schema") != _DATABASE_SCHEMA:
            raise BoundaryProductionError("boundary database schema differs")
        rows = connection.execute(
            """
            SELECT way_id, subtype, direct
            FROM selected_features
            ORDER BY way_id, subtype
            LIMIT -1 OFFSET ?
            """,
            (start_ordinal,),
        )
        batch: list[BoundaryRenderInput] = []
        points = 0
        input_bytes = 0
        ordinal = start_ordinal
        for way_id, subtype, direct in rows:
            value = _load_render_input(
                connection,
                ordinal,
                int(way_id),
                int(subtype),
                int(direct),
            )
            value_points = len(value.way.node_refs)
            value_bytes = _render_input_bytes(value)
            if (
                value_points > limits.max_points_per_batch
                or value_bytes > limits.max_input_bytes_per_batch
            ):
                raise BoundaryProductionError(
                    f"boundary way {value.way.object_id} exceeds one render batch"
                )
            if batch and (
                len(batch) >= limits.max_features_per_batch
                or points + value_points > limits.max_points_per_batch
                or input_bytes + value_bytes > limits.max_input_bytes_per_batch
            ):
                yield tuple(batch)
                batch.clear()
                points = 0
                input_bytes = 0
            batch.append(value)
            points += value_points
            input_bytes += value_bytes
            ordinal += 1
        if batch:
            yield tuple(batch)
    except sqlite3.Error as error:
        raise BoundaryProductionError("boundary database is unreadable") from error
    finally:
        if "connection" in locals():
            connection.close()


def _ingest_semantic_sha256(
    database_path: Path,
    source_generation_sha256: str,
) -> str:
    digest = hashlib.sha256(_INGEST_DOMAIN)
    limits = BoundaryProductionLimits(
        workers=1,
        max_in_flight_batches=1,
        max_features_per_batch=64,
        max_points_per_batch=128_000,
        max_input_bytes_per_batch=32 * 1024 * 1024,
        max_spool_bytes_per_batch=256 * 1024 * 1024,
        max_total_spool_bytes=256 * 1024 * 1024,
    )
    for batch in iter_boundary_render_batches(database_path, limits=limits):
        for value in batch:
            feature_sha256 = _source_feature_sha256(
                value.selected,
                value.node_mapping(),
                source_generation_sha256,
            )
            digest.update(value.ordinal.to_bytes(8, "little"))
            digest.update(feature_sha256)
    return digest.hexdigest()


def _atomic_write(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() or path.is_symlink():
        raise BoundaryProductionError(f"boundary output already exists: {path.name}")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.partial-", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.rename(temporary, path)
    except OSError as error:
        raise BoundaryProductionError(f"boundary {path.name} publication failed") from error
    finally:
        temporary.unlink(missing_ok=True)


def ingest_boundary_closure(
    *,
    closure_opl: Path,
    extraction_receipt: Path,
    database_path: Path,
    ingest_receipt_path: Path,
    limits: BoundaryIngestLimits = BoundaryIngestLimits(),
) -> BoundaryIngestResult:
    """Ingest one native boundary closure into an immutable disk-backed store."""

    for value, label in (
        (closure_opl, "closure OPL"),
        (extraction_receipt, "extraction receipt"),
        (database_path, "database"),
        (ingest_receipt_path, "ingest receipt"),
    ):
        if not isinstance(value, Path):
            raise BoundaryProductionError(f"boundary {label} path is invalid")
    if database_path == ingest_receipt_path or database_path == closure_opl:
        raise BoundaryProductionError("boundary ingest paths overlap")
    if not isinstance(limits, BoundaryIngestLimits):
        raise BoundaryProductionError("boundary ingest limits are invalid")
    if database_path.exists() or ingest_receipt_path.exists():
        raise BoundaryProductionError("boundary ingest output already exists")
    closure_bytes, closure_sha256 = _sha256_file(closure_opl, "boundary closure OPL")
    extraction_document, extraction_raw = _read_extraction_receipt(
        extraction_receipt,
        closure_opl,
        closure_bytes=closure_bytes,
        closure_sha256=closure_sha256,
    )
    database_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        available_before = shutil.disk_usage(database_path.parent).free
    except OSError as error:
        raise BoundaryProductionError(
            "boundary ingest destination capacity is unavailable"
        ) from error
    effective_database_quota = min(
        limits.max_database_bytes,
        max(0, available_before - limits.destination_reserve_bytes),
    )
    if effective_database_quota < 4_096:
        raise BoundaryProductionError(
            "boundary ingest database byte quota has no reserved capacity"
        )
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{database_path.name}.partial-", dir=database_path.parent
    )
    os.close(descriptor)
    temporary_database = Path(temporary_name)
    temporary_database.unlink()
    published_database = False
    try:
        connection = _connect_new(
            temporary_database,
            max_bytes=effective_database_quota,
        )
        try:
            counts = _ingest_records(connection, closure_opl)
            selected_features, selected_ways, required_nodes, memberships = (
                _prune_and_validate(connection)
            )
            output_counts = _mapping(
                _mapping(
                    extraction_document.get("semantic"),
                    "boundary extraction semantic identity",
                ).get("counts"),
                "boundary extraction counts",
            ).get("outputObjects")
            output_counts = _mapping(output_counts, "boundary extraction output counts")
            actual_counts = {
                "nodes": counts["nodes"],
                "ways": counts["ways"],
                "relations": counts["relations"],
            }
            if output_counts != actual_counts:
                raise BoundaryProductionError(
                    "boundary extraction output object counts differ"
                )
            _meta_put(connection, "schema", _DATABASE_SCHEMA)
            _meta_put(connection, "sourceClosureSha256", closure_sha256)
            _meta_put(
                connection,
                "extractionReceiptSha256",
                hashlib.sha256(extraction_raw).hexdigest(),
            )
            _meta_put(
                connection,
                "counts",
                {
                    "relationMemberships": memberships,
                    "requiredNodes": required_nodes,
                    "selectedFeatures": selected_features,
                    "selectedWays": selected_ways,
                },
            )
            connection.commit()
        finally:
            connection.close()
        ingest_semantic_sha256 = _ingest_semantic_sha256(
            temporary_database, closure_sha256
        )
        connection = sqlite3.connect(temporary_database)
        try:
            _meta_put(connection, "ingestSemanticSha256", ingest_semantic_sha256)
            connection.commit()
        finally:
            connection.close()
        os.rename(temporary_database, database_path)
        published_database = True
        database_bytes, database_sha256 = _sha256_file(
            database_path, "boundary ingest database"
        )
        if database_bytes > effective_database_quota:
            raise BoundaryProductionError(
                "boundary ingest database exceeded its byte quota"
            )
        receipt_document = {
            "counts": {
                "relationMemberships": memberships,
                "requiredNodes": required_nodes,
                "selectedFeatures": selected_features,
                "selectedWays": selected_ways,
            },
            "database": {
                "bytes": database_bytes,
                "sha256": database_sha256,
            },
            "execution": {
                "databaseByteQuota": {
                    "configured": limits.max_database_bytes,
                    "destinationAvailableBefore": available_before,
                    "destinationReserve": limits.destination_reserve_bytes,
                    "effective": effective_database_quota,
                }
            },
            "extractionReceipt": {
                "bytes": len(extraction_raw),
                "document": extraction_document,
                "sha256": hashlib.sha256(extraction_raw).hexdigest(),
            },
            "ingestSemanticSha256": ingest_semantic_sha256,
            "schema": _INGEST_SCHEMA,
            "sourceClosure": {
                "bytes": closure_bytes,
                "sha256": closure_sha256,
            },
        }
        _atomic_write(ingest_receipt_path, _canonical_json_bytes(receipt_document))
        return BoundaryIngestResult(
            database_path,
            ingest_receipt_path,
            closure_sha256,
            ingest_semantic_sha256,
            selected_features,
            selected_ways,
            required_nodes,
            memberships,
        )
    except BaseException:
        temporary_database.unlink(missing_ok=True)
        if published_database and not ingest_receipt_path.exists():
            database_path.unlink(missing_ok=True)
        raise


def _render_batch_job(job: _RenderBatchJob) -> _SpoolDescriptor:
    if not job.batch:
        raise BoundaryProductionError("boundary render batch is empty")
    spool_path = Path(job.spool_path)
    temporary = spool_path.with_name(
        spool_path.name + f".tmp-{os.getpid()}"
    )
    if spool_path.exists() or temporary.exists():
        raise BoundaryProductionError("boundary render spool already exists")
    digest = hashlib.sha256()
    byte_count = 0
    record_count = 0

    def write_checked(handle, raw: bytes) -> None:
        nonlocal byte_count
        if byte_count + len(raw) > job.spool_byte_quota:
            raise BoundaryProductionError(
                "boundary render spool exceeds its per-batch quota"
            )
        handle.write(raw)
        digest.update(raw)
        byte_count += len(raw)

    try:
        with temporary.open("xb") as handle:
            write_checked(handle, _SPOOL_MAGIC)
            for value in job.batch:
                visibility = outline_visibility_rule(value.subtype)
                applicable_zooms = tuple(
                    zoom
                    for zoom in job.zooms
                    if (zoom + 1) * 100 > visibility.min_zoom_centi
                    and zoom * 100 < visibility.max_zoom_centi
                    and _boundary_zoom_admitted(value.subtype, zoom)
                )
                if not applicable_zooms:
                    continue
                feature = build_boundary_way_feature(
                    value.selected,
                    nodes=value.node_mapping(),
                    source_generation_sha256=job.source_generation_sha256,
                    zooms=applicable_zooms,
                )
                for tile in sorted(
                    feature.tiles, key=lambda item: (item.z, item.y, item.x)
                ):
                    for item in feature.tiles[tile]:
                        canonical = renderer_record_bytes(item.renderer_record)
                        if len(canonical) > MAX_RENDERER_RECORD_BYTES:
                            raise BoundaryProductionError(
                                "boundary renderer record exceeds its spool bound"
                            )
                        frame = b"".join(
                            (
                                struct.pack("<Q", tile.packed),
                                struct.pack("<Q", value.ordinal),
                                struct.pack("<I", len(canonical)),
                                canonical,
                            )
                        )
                        write_checked(handle, frame)
                        record_count += 1
            handle.flush()
            os.fsync(handle.fileno())
        os.rename(temporary, spool_path)
        return _SpoolDescriptor(
            start_ordinal=job.batch[0].ordinal,
            end_ordinal=job.batch[-1].ordinal + 1,
            path=str(spool_path),
            byte_count=byte_count,
            sha256=digest.hexdigest(),
            record_count=record_count,
        )
    except (OSError, BoundaryRendererError, ValueError) as error:
        if isinstance(error, BoundaryProductionError):
            raise
        raise BoundaryProductionError(f"boundary render spool failed: {error}") from error
    finally:
        temporary.unlink(missing_ok=True)


def _create_render_store(path: Path, *, max_bytes: int) -> sqlite3.Connection:
    connection = sqlite3.connect(path)
    try:
        page_size = int(connection.execute("PRAGMA page_size").fetchone()[0])
        maximum_pages = max(1, max_bytes // page_size)
        configured_pages = int(
            connection.execute(f"PRAGMA max_page_count={maximum_pages}").fetchone()[0]
        )
        if configured_pages < maximum_pages:
            connection.close()
            raise BoundaryProductionError(
                "boundary render database byte quota cannot be raised"
            )
        connection.execute("PRAGMA cache_size=-65536")
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA temp_store=FILE")
        connection.executescript(
            """
        CREATE TABLE tile_records (
            tile_z INTEGER NOT NULL,
            tile_x INTEGER NOT NULL,
            tile_y INTEGER NOT NULL,
            draw_order INTEGER NOT NULL,
            priority INTEGER NOT NULL,
            layer_group INTEGER NOT NULL,
            feature_kind INTEGER NOT NULL,
            variant_id BLOB NOT NULL,
            feature_id BLOB NOT NULL,
            renderer_record BLOB NOT NULL
        );
        CREATE INDEX tile_records_package_order ON tile_records(
            tile_z, tile_y, tile_x, draw_order, priority, layer_group,
            feature_kind, variant_id, feature_id, renderer_record
        );
        CREATE INDEX tile_records_semantic_order ON tile_records(
            tile_z, tile_x, tile_y, draw_order, priority, layer_group,
            feature_kind, variant_id, feature_id, renderer_record
        );
        CREATE TABLE render_progress (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
            next_ordinal INTEGER NOT NULL CHECK(next_ordinal >= 0)
        );
        INSERT INTO render_progress VALUES (1, 0);
        """
        )
    except sqlite3.Error as error:
        connection.close()
        if "full" in str(error).casefold():
            raise BoundaryProductionError(
                "boundary render database byte quota is too small"
            ) from error
        raise
    return connection


def _open_render_store(path: Path, *, max_bytes: int) -> sqlite3.Connection:
    if not path.is_file():
        raise BoundaryProductionError("boundary render database is unavailable")
    connection = sqlite3.connect(path)
    try:
        page_size = int(connection.execute("PRAGMA page_size").fetchone()[0])
        maximum_pages = max(1, max_bytes // page_size)
        configured_pages = int(
            connection.execute(f"PRAGMA max_page_count={maximum_pages}").fetchone()[0]
        )
        if configured_pages < maximum_pages:
            connection.close()
            raise BoundaryProductionError(
                "boundary render database byte quota cannot be raised"
            )
        connection.execute("PRAGMA cache_size=-65536")
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        connection.execute("PRAGMA temp_store=FILE")
        connection.execute("SELECT 1 FROM tile_records LIMIT 1").fetchone()
    except sqlite3.Error as error:
        connection.close()
        if "full" in str(error).casefold():
            raise BoundaryProductionError(
                "boundary render database byte quota is too small"
            ) from error
        raise BoundaryProductionError("boundary render database cannot be resumed") from error
    return connection


def _ingest_spool(
    connection: sqlite3.Connection,
    descriptor: _SpoolDescriptor,
) -> int:
    path = Path(descriptor.path)
    actual_bytes, actual_sha256 = _sha256_file(path, "boundary render spool")
    if (
        actual_bytes != descriptor.byte_count
        or actual_sha256 != descriptor.sha256
    ):
        raise BoundaryProductionError("boundary render spool identity differs")
    records = 0
    try:
        with path.open("rb") as handle:
            if handle.read(len(_SPOOL_MAGIC)) != _SPOOL_MAGIC:
                raise BoundaryProductionError("boundary render spool magic differs")
            while True:
                header = handle.read(20)
                if not header:
                    break
                if len(header) != 20:
                    raise BoundaryProductionError("boundary render spool frame is truncated")
                packed_tile, source_ordinal, length = struct.unpack("<QQI", header)
                if not descriptor.start_ordinal <= source_ordinal < descriptor.end_ordinal:
                    raise BoundaryProductionError(
                        "boundary render spool source ordinal differs"
                    )
                if not 0 < length <= MAX_RENDERER_RECORD_BYTES:
                    raise BoundaryProductionError(
                        "boundary render spool record length is invalid"
                    )
                canonical = handle.read(length)
                if len(canonical) != length:
                    raise BoundaryProductionError(
                        "boundary render spool record ended early"
                    )
                row = _spool_database_row(packed_tile, canonical)
                connection.execute(
                    "INSERT INTO tile_records VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    row,
                )
                records += 1
    except OSError as error:
        raise BoundaryProductionError("boundary render spool is unreadable") from error
    if records != descriptor.record_count:
        raise BoundaryProductionError("boundary render spool record count differs")
    progress = connection.execute(
        """
        UPDATE render_progress SET next_ordinal = ?
        WHERE singleton = 1 AND next_ordinal = ?
        """,
        (descriptor.end_ordinal, descriptor.start_ordinal),
    )
    if progress.rowcount != 1:
        connection.rollback()
        raise BoundaryProductionError("boundary render progress differs")
    connection.commit()
    path.unlink()
    return records


def _spool_database_row(packed_tile: int, canonical: bytes) -> tuple[object, ...]:
    try:
        from .model import TileKey

        tile = TileKey.from_packed(packed_tile)
        record = _decode_renderer_record(tile, canonical)
    except ValueError as error:
        raise BoundaryProductionError(
            "boundary render spool record is not canonical"
        ) from error
    order = renderer_order_key(record)
    return (
        tile.z,
        tile.x,
        tile.y,
        order[0],
        order[1],
        order[2],
        order[3],
        order[4].to_bytes(8, "big"),
        order[5].to_bytes(8, "big"),
        canonical,
    )


def _existing_spool_descriptor(path: Path) -> _SpoolDescriptor:
    parts = path.stem.split("-")
    if (
        path.suffix != ".spool"
        or len(parts) != 2
        or any(len(part) != 12 or not part.isdecimal() for part in parts)
    ):
        raise BoundaryProductionError("boundary resume spool name differs")
    start_ordinal, end_ordinal = (int(part, 10) for part in parts)
    if end_ordinal <= start_ordinal:
        raise BoundaryProductionError("boundary resume spool range differs")
    byte_count, sha256 = _sha256_file(path, "boundary resume spool")
    records = 0
    try:
        with path.open("rb") as handle:
            if handle.read(len(_SPOOL_MAGIC)) != _SPOOL_MAGIC:
                raise BoundaryProductionError("boundary render spool magic differs")
            while True:
                header = handle.read(20)
                if not header:
                    break
                if len(header) != 20:
                    raise BoundaryProductionError(
                        "boundary render spool frame is truncated"
                    )
                packed_tile, source_ordinal, length = struct.unpack("<QQI", header)
                if not start_ordinal <= source_ordinal < end_ordinal:
                    raise BoundaryProductionError(
                        "boundary render spool source ordinal differs"
                    )
                if not 0 < length <= MAX_RENDERER_RECORD_BYTES:
                    raise BoundaryProductionError(
                        "boundary render spool record length is invalid"
                    )
                canonical = handle.read(length)
                if len(canonical) != length:
                    raise BoundaryProductionError(
                        "boundary render spool record ended early"
                    )
                _spool_database_row(packed_tile, canonical)
                records += 1
    except OSError as error:
        raise BoundaryProductionError("boundary resume spool is unreadable") from error
    return _SpoolDescriptor(
        start_ordinal=start_ordinal,
        end_ordinal=end_ordinal,
        path=str(path),
        byte_count=byte_count,
        sha256=sha256,
        record_count=records,
    )


def _spool_database_presence(
    connection: sqlite3.Connection,
    descriptor: _SpoolDescriptor,
) -> bool:
    present = 0
    try:
        with Path(descriptor.path).open("rb") as handle:
            handle.read(len(_SPOOL_MAGIC))
            while True:
                header = handle.read(20)
                if not header:
                    break
                packed_tile, _, length = struct.unpack("<QQI", header)
                row = _spool_database_row(packed_tile, handle.read(length))
                if connection.execute(
                    """
                    SELECT 1 FROM tile_records
                    WHERE tile_z = ? AND tile_x = ? AND tile_y = ?
                      AND draw_order = ? AND priority = ? AND layer_group = ?
                      AND feature_kind = ? AND variant_id = ? AND feature_id = ?
                      AND renderer_record = ?
                    LIMIT 1
                    """,
                    row,
                ).fetchone() is not None:
                    present += 1
    except (OSError, sqlite3.Error, struct.error) as error:
        raise BoundaryProductionError(
            "boundary resume spool cannot be matched to its database"
        ) from error
    if present not in (0, descriptor.record_count):
        raise BoundaryProductionError(
            "boundary resume spool is only partly committed"
        )
    return descriptor.record_count > 0 and present == descriptor.record_count


def _recover_resume_spools(
    connection: sqlite3.Connection,
    spool_directory: Path,
    start_ordinal: int,
) -> tuple[int, int, int]:
    paths = sorted(spool_directory.iterdir())
    descriptors = [_existing_spool_descriptor(path) for path in paths]
    states = [
        _spool_database_presence(connection, descriptor)
        for descriptor in descriptors
    ]
    progress_row = connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'render_progress'"
    ).fetchone()
    if progress_row is None:
        if not descriptors or descriptors[0].start_ordinal != start_ordinal:
            raise BoundaryProductionError(
                "boundary legacy resume checkpoint cannot be verified"
            )
        progress = start_ordinal
        expected = start_ordinal
        seen_absent = False
        for descriptor, present in zip(descriptors, states, strict=True):
            if descriptor.start_ordinal != expected:
                raise BoundaryProductionError(
                    "boundary legacy resume spools are not contiguous"
                )
            expected = descriptor.end_ordinal
            if present:
                if seen_absent:
                    raise BoundaryProductionError(
                        "boundary resume spool commit order differs"
                    )
                progress = descriptor.end_ordinal
            else:
                seen_absent = True
        connection.executescript(
            """
            CREATE TABLE render_progress (
                singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                next_ordinal INTEGER NOT NULL CHECK(next_ordinal >= 0)
            );
            """
        )
        connection.execute(
            "INSERT INTO render_progress VALUES (1, ?)",
            (progress,),
        )
        connection.commit()
    else:
        row = connection.execute(
            "SELECT next_ordinal FROM render_progress WHERE singleton = 1"
        ).fetchone()
        if (
            row is None
            or type(row[0]) is not int
            or int(row[0]) < start_ordinal
        ):
            raise BoundaryProductionError("boundary render progress differs")
        progress = int(row[0])
        for descriptor, present in zip(descriptors, states, strict=True):
            if descriptor.end_ordinal <= progress:
                if not present:
                    raise BoundaryProductionError(
                        "boundary committed resume spool differs"
                    )
            elif descriptor.start_ordinal >= progress:
                if present:
                    raise BoundaryProductionError(
                        "boundary uncommitted resume spool differs"
                    )
            else:
                raise BoundaryProductionError("boundary resume spool overlaps progress")
    for path in paths:
        path.unlink()
    return progress, 0, len(descriptors)


def _render_parallel(
    source_database: Path,
    render_database: Path,
    spool_directory: Path,
    *,
    source_generation_sha256: str,
    zooms: tuple[int, ...],
    limits: BoundaryProductionLimits,
    start_ordinal: int = 0,
) -> tuple[int, dict[str, int]]:
    requested_start_ordinal = start_ordinal
    connection = (
        _create_render_store(
            render_database,
            max_bytes=limits.max_render_database_bytes,
        )
        if start_ordinal == 0
        else _open_render_store(
            render_database,
            max_bytes=limits.max_render_database_bytes,
        )
    )
    recovered_spool_records = 0
    recovered_spool_batches = 0
    if start_ordinal > 0:
        (
            start_ordinal,
            recovered_spool_records,
            recovered_spool_batches,
        ) = _recover_resume_spools(
            connection,
            spool_directory,
            start_ordinal,
        )
    iterator = iter_boundary_render_batches(
        source_database,
        limits=limits,
        start_ordinal=start_ordinal,
    )
    pending: dict[object, tuple[int, int]] = {}
    completed: dict[int, _SpoolDescriptor] = {}
    next_ordinal = start_ordinal
    records = 0
    submitted_batches = 0
    maximum_resident_spool_bytes = 0
    exhausted = False

    def submit_one(executor: ProcessPoolExecutor) -> bool:
        nonlocal exhausted, submitted_batches
        if exhausted:
            return False
        try:
            batch = next(iterator)
        except StopIteration:
            exhausted = True
            return False
        start = batch[0].ordinal
        end = batch[-1].ordinal + 1
        spool = spool_directory / f"{start:012d}-{end:012d}.spool"
        future = executor.submit(
            _render_batch_job,
            _RenderBatchJob(
                batch=batch,
                source_generation_sha256=source_generation_sha256,
                zooms=zooms,
                spool_path=str(spool),
                spool_byte_quota=limits.max_spool_bytes_per_batch,
            ),
        )
        pending[future] = (start, end)
        submitted_batches += 1
        return True

    try:
        context = multiprocessing.get_context("spawn")
        with ProcessPoolExecutor(
            max_workers=limits.workers,
            mp_context=context,
        ) as executor:
            while (
                len(pending) + len(completed) < limits.max_in_flight_batches
                and submit_one(executor)
            ):
                pass
            while pending or completed:
                if pending:
                    done, _ = wait(tuple(pending), return_when=FIRST_COMPLETED)
                    for future in done:
                        expected_start, expected_end = pending.pop(future)
                        descriptor = future.result()
                        if (
                            descriptor.start_ordinal != expected_start
                            or descriptor.end_ordinal != expected_end
                            or descriptor.start_ordinal in completed
                        ):
                            raise BoundaryProductionError(
                                "boundary render worker descriptor differs"
                            )
                        completed[descriptor.start_ordinal] = descriptor
                maximum_resident_spool_bytes = max(
                    maximum_resident_spool_bytes,
                    sum(item.byte_count for item in completed.values()),
                )
                if maximum_resident_spool_bytes > limits.max_total_spool_bytes:
                    raise BoundaryProductionError(
                        "boundary completed render spools exceed their total quota"
                    )
                while next_ordinal in completed:
                    descriptor = completed.pop(next_ordinal)
                    records += _ingest_spool(connection, descriptor)
                    next_ordinal = descriptor.end_ordinal
                while (
                    len(pending) + len(completed) < limits.max_in_flight_batches
                    and submit_one(executor)
                ):
                    pass
                if not pending and completed and next_ordinal not in completed:
                    raise BoundaryProductionError(
                        "boundary render batches are not contiguous"
                    )
        if start_ordinal == 0 and (
            records + recovered_spool_records <= 0
            or submitted_batches + recovered_spool_batches <= 0
        ):
            raise BoundaryProductionError("boundary renderer produced no records")
        render_database_bytes = render_database.stat().st_size
        if render_database_bytes > limits.max_render_database_bytes:
            raise BoundaryProductionError(
                "boundary render database exceeded its byte quota"
            )
        return records, {
            "maximumCompletedSpoolBytes": maximum_resident_spool_bytes,
            "recoveredSpoolBatches": recovered_spool_batches,
            "recoveredSpoolRecords": recovered_spool_records,
            "renderDatabaseBytes": render_database_bytes,
            "renderStartOrdinal": start_ordinal,
            "resumeOrdinal": requested_start_ordinal,
            "renderedRecordsThisRun": records + recovered_spool_records,
            "submittedBatches": submitted_batches,
        }
    except BaseException:
        for path in spool_directory.glob("*.spool*"):
            path.unlink(missing_ok=True)
        raise
    finally:
        iterator.close()
        connection.close()


def _create_publishable_render_view(connection: sqlite3.Connection) -> None:
    connection.execute(
        f"""
        CREATE TEMP VIEW publishable_tile_records AS
        SELECT * FROM tile_records
        WHERE NOT (
            tile_z < 7
            AND layer_group = {LayerGroup.WATER.value}
            AND draw_order = 10
            AND priority = 0
            AND feature_kind = {FeatureKind.LINE.value}
        )
        AND NOT (
            tile_z < 8
            AND layer_group = {LayerGroup.REGIONS.value}
            AND draw_order = 23
            AND priority = 6
            AND feature_kind = {FeatureKind.LINE.value}
        )
        """
    )


def _render_ranges(
    connection: sqlite3.Connection,
    zooms: tuple[int, ...],
) -> tuple[tuple[int, int, int, int, int], ...]:
    ranges: list[tuple[int, int, int, int, int]] = []
    for zoom in zooms:
        row = connection.execute(
            """
            SELECT MIN(tile_x), MAX(tile_x), MIN(tile_y), MAX(tile_y)
            FROM publishable_tile_records WHERE tile_z = ?
            """,
            (zoom,),
        ).fetchone()
        if row is not None and row[0] is not None:
            ranges.append((zoom, int(row[0]), int(row[1]), int(row[2]), int(row[3])))
    if not ranges:
        raise BoundaryProductionError("boundary renderer produced no zoom coverage")
    return tuple(ranges)


def _validate_render_tile_bounds(
    connection: sqlite3.Connection,
) -> dict[str, int]:
    header_bytes = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
    maximum_records = 0
    maximum_raw_bytes = 0
    rendered_records = 0
    tile_count = 0
    for z, x, y, records, raw_bytes, maximum_record_bytes in connection.execute(
        """
        SELECT tile_z, tile_x, tile_y, COUNT(*),
               ? + SUM(LENGTH(renderer_record) + 8),
               MAX(LENGTH(renderer_record))
        FROM publishable_tile_records
        GROUP BY tile_z, tile_y, tile_x
        ORDER BY tile_z, tile_y, tile_x
        """,
        (header_bytes,),
    ):
        records = int(records)
        raw_bytes = int(raw_bytes)
        maximum_record_bytes = int(maximum_record_bytes)
        if maximum_record_bytes > MAX_RENDERER_RECORD_BYTES:
            raise BoundaryProductionError(
                f"boundary tile {z}/{x}/{y} has an oversized renderer record"
            )
        if records > MAX_RECORDS_PER_TILE:
            raise BoundaryProductionError(
                f"boundary tile record count exceeds its Android ceiling at {z}/{x}/{y}"
            )
        if raw_bytes > MAX_TILE_BYTES:
            raise BoundaryProductionError(
                f"boundary raw tile exceeds its Android ceiling at {z}/{x}/{y}"
            )
        maximum_records = max(maximum_records, records)
        maximum_raw_bytes = max(maximum_raw_bytes, raw_bytes)
        rendered_records += records
        tile_count += 1
    if tile_count <= 0:
        raise BoundaryProductionError("boundary renderer produced no present tiles")
    return {
        "maximumRecordsPerTile": maximum_records,
        "maximumRawTileBytes": maximum_raw_bytes,
        "presentTilesValidated": tile_count,
        "renderedRecordsValidated": rendered_records,
    }


def _tile_groups(connection: sqlite3.Connection):
    from .model import TileKey

    rows = connection.execute(
        """
        SELECT tile_z, tile_x, tile_y, renderer_record
        FROM publishable_tile_records
        ORDER BY tile_z, tile_y, tile_x, draw_order, priority, layer_group,
                 feature_kind, variant_id, feature_id, renderer_record
        """
    )
    current_tile: TileKey | None = None
    current: list[RendererTileRecord] = []
    for z, x, y, canonical in rows:
        tile = TileKey(int(z), int(x), int(y))
        if current_tile is not None and tile != current_tile:
            yield current_tile, tuple(current)
            current.clear()
        current_tile = tile
        record = _decode_renderer_record(tile, bytes(canonical))
        current.append(RendererTileRecord(record, None))
    if current_tile is not None:
        yield current_tile, tuple(current)


def _renderer_semantic_sha256(connection: sqlite3.Connection) -> str:
    from .model import TileKey

    digest = hashlib.sha256(_RENDER_SEMANTIC_DOMAIN)
    for z, x, y, canonical in connection.execute(
        """
        SELECT tile_z, tile_x, tile_y, renderer_record
        FROM publishable_tile_records
        ORDER BY tile_z, tile_x, tile_y, draw_order, priority, layer_group,
                 feature_kind, variant_id, feature_id, renderer_record
        """
    ):
        tile = TileKey(int(z), int(x), int(y))
        body = struct.pack("<Q", tile.packed) + bytes(canonical)
        digest.update(struct.pack("<I", len(body)))
        digest.update(body)
    return digest.hexdigest()


def _read_ingest_receipt(
    database_path: Path,
    receipt_path: Path,
) -> tuple[dict[str, object], bytes]:
    if (
        not database_path.is_file()
        or database_path.is_symlink()
        or not receipt_path.is_file()
        or receipt_path.is_symlink()
    ):
        raise BoundaryProductionError("boundary ingest receipt is unavailable")
    try:
        raw = receipt_path.read_bytes()
        document = json.loads(raw.decode("utf-8", "strict"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BoundaryProductionError("boundary ingest receipt is unreadable") from error
    if not 0 < len(raw) <= _MAX_RECEIPT_BYTES:
        raise BoundaryProductionError("boundary ingest receipt byte length is invalid")
    document = _ordered_fields(
        document,
        (
            "counts",
            "database",
            "execution",
            "extractionReceipt",
            "ingestSemanticSha256",
            "schema",
            "sourceClosure",
        ),
        "boundary ingest receipt",
    )
    if raw != _canonical_json_bytes(document) or document["schema"] != _INGEST_SCHEMA:
        raise BoundaryProductionError("boundary ingest receipt differs")
    database_fact = _ordered_fields(
        document["database"], ("bytes", "sha256"), "boundary ingest database"
    )
    database_bytes, database_sha256 = _sha256_file(
        database_path, "boundary ingest database"
    )
    if database_fact != {"bytes": database_bytes, "sha256": database_sha256}:
        raise BoundaryProductionError("boundary ingest database identity differs")
    execution = _ordered_fields(
        document["execution"],
        ("databaseByteQuota",),
        "boundary ingest execution",
    )
    database_quota = _ordered_fields(
        execution["databaseByteQuota"],
        (
            "configured",
            "destinationAvailableBefore",
            "destinationReserve",
            "effective",
        ),
        "boundary ingest database byte quota",
    )
    database_quota = {
        key: _nonnegative_int(value, f"boundary ingest database quota {key}")
        for key, value in database_quota.items()
    }
    if (
        database_quota["configured"] <= 0
        or database_quota["effective"] <= 0
        or database_quota["effective"] > database_quota["configured"]
        or database_quota["effective"]
        > max(
            0,
            database_quota["destinationAvailableBefore"]
            - database_quota["destinationReserve"],
        )
        or database_bytes > database_quota["effective"]
    ):
        raise BoundaryProductionError("boundary ingest database byte quota differs")
    execution["databaseByteQuota"] = database_quota
    source = _ordered_fields(
        document["sourceClosure"],
        ("bytes", "sha256"),
        "boundary source closure",
    )
    source["bytes"] = _nonnegative_int(source["bytes"], "boundary source closure bytes")
    if source["bytes"] <= 0:
        raise BoundaryProductionError("boundary source closure is empty")
    source["sha256"] = _hex_sha256(
        source["sha256"], "boundary source closure"
    )
    extraction = _ordered_fields(
        document["extractionReceipt"],
        ("bytes", "document", "sha256"),
        "boundary extraction receipt binding",
    )
    extraction["bytes"] = _nonnegative_int(
        extraction["bytes"], "boundary extraction receipt bytes"
    )
    if not 0 < extraction["bytes"] <= _MAX_RECEIPT_BYTES:
        raise BoundaryProductionError("boundary extraction receipt byte length differs")
    extraction["sha256"] = _hex_sha256(
        extraction["sha256"], "boundary extraction receipt"
    )
    extraction_document, extraction_raw = _validated_extraction_document(
        extraction["document"],
        closure_bytes=source["bytes"],
        closure_sha256=source["sha256"],
    )
    if (
        len(extraction_raw) != extraction["bytes"]
        or hashlib.sha256(extraction_raw).hexdigest() != extraction["sha256"]
    ):
        raise BoundaryProductionError("boundary extraction receipt binding differs")
    extraction["document"] = extraction_document
    counts = _ordered_fields(
        document["counts"],
        (
            "relationMemberships",
            "requiredNodes",
            "selectedFeatures",
            "selectedWays",
        ),
        "boundary ingest counts",
    )
    counts = {
        key: _nonnegative_int(value, f"boundary ingest {key}")
        for key, value in counts.items()
    }
    if not counts["selectedFeatures"] or not counts["selectedWays"] or not counts["requiredNodes"]:
        raise BoundaryProductionError("boundary ingest counts contain no geometry")
    ingest_semantic_sha256 = _hex_sha256(
        document["ingestSemanticSha256"], "boundary ingest semantic identity"
    )
    try:
        connection = sqlite3.connect(database_path)
        connection.execute("PRAGMA query_only=ON")
        if (
            connection.execute("PRAGMA quick_check").fetchone() != ("ok",)
            or _meta_get(connection, "counts") != counts
            or _meta_get(connection, "schema") != _DATABASE_SCHEMA
            or _meta_get(connection, "sourceClosureSha256")
            != source["sha256"]
            or _meta_get(connection, "extractionReceiptSha256")
            != extraction["sha256"]
            or _meta_get(connection, "ingestSemanticSha256")
            != ingest_semantic_sha256
        ):
            raise BoundaryProductionError("boundary ingest receipt and database differ")
    finally:
        if "connection" in locals():
            connection.close()
    document["sourceClosure"] = source
    document["execution"] = execution
    document["extractionReceipt"] = extraction
    document["counts"] = counts
    document["ingestSemanticSha256"] = ingest_semantic_sha256
    return document, raw


def _code_identities() -> dict[str, dict[str, object]]:
    root = Path(__file__).resolve().parent
    identities: dict[str, dict[str, object]] = {}
    for name in (
        "osm_boundary_production.py",
        "osm_boundary_renderer.py",
        "renderer_tile_package.py",
        "reference_presentation_policy.py",
    ):
        byte_count, sha256 = _sha256_file(root / name, f"boundary code {name}")
        identities[name] = {"bytes": byte_count, "sha256": sha256}
    return identities


def _file_fact(path: Path) -> dict[str, object]:
    byte_count, sha256 = _sha256_file(path, path.name)
    return {"bytes": byte_count, "name": path.name, "sha256": sha256}


def build_boundary_package(
    *,
    database_path: Path,
    ingest_receipt_path: Path,
    output_directory: Path,
    package_id: str,
    zooms: tuple[int, ...],
    limits: BoundaryProductionLimits = BoundaryProductionLimits(),
    resume_directory: Path | None = None,
    resume_ordinal: int = 0,
) -> BoundaryBuildResult:
    """Render one receipt-bound boundary supplement with bounded process workers."""

    for path, label in (
        (database_path, "database"),
        (ingest_receipt_path, "ingest receipt"),
        (output_directory, "output directory"),
    ):
        if not isinstance(path, Path):
            raise BoundaryProductionError(f"boundary {label} path is invalid")
    if not isinstance(limits, BoundaryProductionLimits):
        raise BoundaryProductionError("boundary build limits are invalid")
    if type(resume_ordinal) is not int or resume_ordinal < 0:
        raise BoundaryProductionError("boundary build resume ordinal is invalid")
    if resume_directory is None:
        if resume_ordinal != 0:
            raise BoundaryProductionError("boundary build resume directory is required")
    elif (
        not isinstance(resume_directory, Path)
        or not resume_directory.is_dir()
        or resume_ordinal <= 0
    ):
        raise BoundaryProductionError("boundary build resume state is invalid")
    if (
        type(zooms) is not tuple
        or not zooms
        or tuple(sorted(set(zooms))) != zooms
        or any(type(zoom) is not int or not 0 <= zoom <= 29 for zoom in zooms)
    ):
        raise BoundaryProductionError("boundary build zooms are invalid")
    try:
        _validate_package_id(package_id)
    except ValueError as error:
        raise BoundaryProductionError("boundary package ID is invalid") from error
    if output_directory.exists() or output_directory.is_symlink():
        raise BoundaryProductionError("boundary package output already exists")
    ingest_document, ingest_raw = _read_ingest_receipt(
        database_path, ingest_receipt_path
    )
    source = _mapping(ingest_document.get("sourceClosure"), "boundary source closure")
    source_sha256 = _hex_sha256(source.get("sha256"), "boundary source closure")
    ingest_semantic_sha256 = _hex_sha256(
        ingest_document.get("ingestSemanticSha256"), "boundary ingest semantic identity"
    )
    extraction = _mapping(
        ingest_document.get("extractionReceipt"), "boundary extraction receipt binding"
    )
    code = _code_identities()
    run_identity = {
        "code": code,
        "ingestSemanticSha256": ingest_semantic_sha256,
        "packageId": package_id,
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "schema": _RENDER_RUN_SCHEMA,
        "sourceClosureSha256": source_sha256,
        "zooms": list(zooms),
    }
    run_identity_sha256 = hashlib.sha256(
        _canonical_json_bytes(run_identity)
    ).hexdigest()
    output_directory.parent.mkdir(parents=True, exist_ok=True)
    try:
        destination_available_before = shutil.disk_usage(
            output_directory.parent
        ).free
    except OSError as error:
        raise BoundaryProductionError(
            "boundary build destination capacity is unavailable"
        ) from error
    required_destination_bytes = (
        limits.max_render_database_bytes * 2
        + limits.max_total_spool_bytes
        + limits.max_runtime_package_bytes
        + limits.destination_reserve_bytes
    )
    if destination_available_before < required_destination_bytes:
        raise BoundaryProductionError(
            "boundary build destination capacity is below its hard temp/output quotas"
        )
    build_context = (
        tempfile.TemporaryDirectory(
            prefix=f".{output_directory.name}.boundary-build-",
            dir=output_directory.parent,
        )
        if resume_directory is None
        else nullcontext(str(resume_directory))
    )
    with build_context as temporary:
        root = Path(temporary)
        render_database = root / "render.sqlite"
        spool_directory = root / "spool"
        stage_package = root / "package"
        if resume_directory is None:
            spool_directory.mkdir()
        elif (
            not render_database.is_file()
            or not spool_directory.is_dir()
            or stage_package.exists()
        ):
            raise BoundaryProductionError("boundary build resume stage differs")
        try:
            rendered_records, peaks = _render_parallel(
                database_path,
                render_database,
                spool_directory,
                source_generation_sha256=source_sha256,
                zooms=zooms,
                limits=limits,
                start_ordinal=resume_ordinal,
            )
            connection = sqlite3.connect(render_database)
            _create_publishable_render_view(connection)
            connection.execute("PRAGMA query_only=ON")
            try:
                ranges = _render_ranges(connection, zooms)
                declared_index_entries = sum(
                    (x_max - x_min + 1) * (y_max - y_min + 1)
                    for _, x_min, x_max, y_min, y_max in ranges
                )
                declared_index_bytes = declared_index_entries * INDEX_ENTRY_BYTES
                if declared_index_bytes >= limits.max_runtime_package_bytes:
                    raise BoundaryProductionError(
                        "boundary output byte quota cannot contain its tile index"
                    )
                tile_peaks = _validate_render_tile_bounds(connection)
                if resume_ordinal == 0 and (
                    rendered_records != tile_peaks["renderedRecordsValidated"]
                ):
                    raise BoundaryProductionError(
                        "boundary rendered record count differs"
                    )
                rendered_records = tile_peaks["renderedRecordsValidated"]
                semantic_sha256 = _renderer_semantic_sha256(connection)
                streaming = write_streaming_package(
                    stage_package,
                    package_id,
                    ranges,
                    _tile_groups(connection),
                    complete_declared_scope=False,
                    complete_whole_earth_dictionary=False,
                    max_records_bytes=(
                        limits.max_runtime_package_bytes - declared_index_bytes
                    ),
                )
            finally:
                connection.close()
            if streaming.present_tile_count != tile_peaks["presentTilesValidated"]:
                raise BoundaryProductionError(
                    "boundary streamed tile count differs from its validated store"
                )
            records_fact = _file_fact(stage_package / "records.fadictpack")
            index_fact = _file_fact(stage_package / "tile-index.bin")
            if (
                records_fact["sha256"] != streaming.records_sha256
                or index_fact["sha256"] != streaming.index_sha256
            ):
                raise BoundaryProductionError(
                    "boundary runtime identities changed after streaming"
                )
            manifest_path = stage_package / "manifest.json"
            manifest = json.loads(manifest_path.read_text("utf-8"))
            attribution = {
                "buildMethod": (
                    "Native deterministic OSM PBF boundary closure, disk-backed "
                    "selection, and bounded parallel V3 adaptive-path cook"
                ),
                "copyrightUrl": "https://www.openstreetmap.org/copyright",
                "credit": "OpenStreetMap contributors",
                "databaseLicense": "ODbL-1.0",
                "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
            }
            manifest["rendererSemanticStreamSha256"] = semantic_sha256
            manifest["globalBoundarySupplement"] = {
                "attribution": attribution,
                "buildSchema": _BUILD_SCHEMA,
                "extractionReceipt": {
                    "bytes": extraction.get("bytes"),
                    "sha256": extraction.get("sha256"),
                    "semanticIdentitySha256": _mapping(
                        extraction.get("document"),
                        "boundary extraction receipt document",
                    ).get("semanticIdentitySha256"),
                },
                "ingestSemanticSha256": ingest_semantic_sha256,
                "records": {
                    "bytes": records_fact["bytes"],
                    "sha256": records_fact["sha256"],
                },
                "requestedZooms": list(zooms),
                "runIdentitySha256": run_identity_sha256,
                "source": source,
                "tileIndex": {
                    "bytes": index_fact["bytes"],
                    "sha256": index_fact["sha256"],
                },
                "typedOutlines": {
                    "coastline": SemanticSubtype.COASTLINE.value,
                    "countyLocal": SemanticSubtype.COUNTY_LOCAL_BOUNDARY.value,
                    "international": SemanticSubtype.INTERNATIONAL_BOUNDARY.value,
                    "otherAdmin": SemanticSubtype.OTHER_ADMIN_BOUNDARY.value,
                    "stateProvince": SemanticSubtype.STATE_PROVINCE_BOUNDARY.value,
                },
            }
            manifest_raw = _canonical_json_bytes(manifest)
            manifest_path.write_bytes(manifest_raw)
            manifest_fact = _file_fact(manifest_path)
            output_files = [manifest_fact, records_fact, index_fact]
            runtime_package_bytes = sum(
                int(item["bytes"]) for item in output_files
            )
            if runtime_package_bytes > limits.max_runtime_package_bytes:
                raise BoundaryProductionError(
                    "boundary output byte quota would be exceeded"
                )
            final_semantic_document = {
                "extractionReceiptSha256": extraction.get("sha256"),
                "ingestSemanticSha256": ingest_semantic_sha256,
                "manifestSha256": manifest_fact["sha256"],
                "rendererRunIdentitySha256": run_identity_sha256,
                "rendererSemanticStreamSha256": semantic_sha256,
                "sourceClosureSha256": source_sha256,
            }
            final_semantic_sha256 = hashlib.sha256(
                _FINAL_SEMANTIC_DOMAIN
                + _canonical_json_bytes(final_semantic_document)
            ).hexdigest()
            receipt = {
                "attribution": attribution,
                "build": {
                    "code": code,
                    "runIdentity": run_identity,
                    "runIdentitySha256": run_identity_sha256,
                },
                "catalogCountsClaimed": False,
                "execution": {
                    "limits": {
                        "destinationReserveBytes": limits.destination_reserve_bytes,
                        "maxFeaturesPerBatch": limits.max_features_per_batch,
                        "maxInFlightBatches": limits.max_in_flight_batches,
                        "maxInputBytesPerBatch": limits.max_input_bytes_per_batch,
                        "maxPointsPerBatch": limits.max_points_per_batch,
                        "maxRenderDatabaseBytes": limits.max_render_database_bytes,
                        "maxRuntimePackageBytes": limits.max_runtime_package_bytes,
                        "maxSpoolBytesPerBatch": limits.max_spool_bytes_per_batch,
                        "maxTotalSpoolBytes": limits.max_total_spool_bytes,
                    },
                    "preflight": {
                        "destinationAvailableBefore": destination_available_before,
                        "requiredDestinationBytes": required_destination_bytes,
                    },
                    "workers": limits.workers,
                },
                "extraction": extraction,
                "finalSemanticIdentitySha256": final_semantic_sha256,
                "ingest": {
                    "counts": ingest_document.get("counts"),
                    "receiptBytes": len(ingest_raw),
                    "receiptSha256": hashlib.sha256(ingest_raw).hexdigest(),
                    "semanticSha256": ingest_semantic_sha256,
                },
                "outputFiles": output_files,
                "packageId": package_id,
                "peakResources": {
                    **peaks,
                    **tile_peaks,
                    "androidRecordsPerTileCeiling": MAX_RECORDS_PER_TILE,
                    "androidRawTileByteCeiling": MAX_TILE_BYTES,
                    "declaredIndexBytes": declared_index_bytes,
                    "declaredIndexEntries": declared_index_entries,
                    "runtimePackageBytes": runtime_package_bytes,
                    "renderedRecords": rendered_records,
                    "streamedPresentTiles": streaming.present_tile_count,
                },
                "rendererSemanticStreamSha256": semantic_sha256,
                "schema": _BUILD_SCHEMA,
                "source": source,
            }
            receipt_path = stage_package / "build-receipt.json"
            receipt_path.write_bytes(_canonical_json_bytes(receipt))
            if output_directory.exists():
                raise BoundaryProductionError("boundary package output appeared during build")
            os.rename(stage_package, output_directory)
            return BoundaryBuildResult(
                output_directory=output_directory,
                build_receipt_path=output_directory / "build-receipt.json",
                renderer_semantic_sha256=semantic_sha256,
                records_sha256=str(records_fact["sha256"]),
                index_sha256=str(index_fact["sha256"]),
                present_tile_count=streaming.present_tile_count,
            )
        except (OSError, sqlite3.Error, ValueError) as error:
            if isinstance(error, BoundaryProductionError):
                raise
            raise BoundaryProductionError(f"boundary package build failed: {error}") from error


def _parse_cli_zooms(raw: str) -> tuple[int, ...]:
    try:
        values = tuple(int(item, 10) for item in raw.split(","))
    except ValueError as error:
        raise argparse.ArgumentTypeError("zooms must be comma-separated integers") from error
    if (
        not values
        or values != tuple(sorted(set(values)))
        or any(not 0 <= value <= 29 for value in values)
    ):
        raise argparse.ArgumentTypeError(
            "zooms must be unique ascending integers inside [0,29]"
        )
    return values


def _cli_limits(
    workers: int,
    *,
    max_spool_bytes_per_batch: int,
    max_total_spool_bytes: int,
    max_render_database_bytes: int,
    max_runtime_package_bytes: int,
    destination_reserve_bytes: int,
) -> BoundaryProductionLimits:
    base = BoundaryProductionLimits()
    in_flight = workers * 2
    return BoundaryProductionLimits(
        workers=workers,
        max_in_flight_batches=in_flight,
        max_features_per_batch=base.max_features_per_batch,
        max_points_per_batch=base.max_points_per_batch,
        max_input_bytes_per_batch=base.max_input_bytes_per_batch,
        max_spool_bytes_per_batch=max_spool_bytes_per_batch,
        max_total_spool_bytes=max_total_spool_bytes,
        max_render_database_bytes=max_render_database_bytes,
        max_runtime_package_bytes=max_runtime_package_bytes,
        destination_reserve_bytes=destination_reserve_bytes,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Ingest and render a receipt-bound OSM boundary supplement."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    ingest = commands.add_parser("ingest", help="ingest one native boundary closure")
    ingest.add_argument("--closure", type=Path, required=True)
    ingest.add_argument("--extraction-receipt", type=Path)
    ingest.add_argument("--database", type=Path, required=True)
    ingest.add_argument("--receipt", type=Path, required=True)
    ingest_defaults = BoundaryIngestLimits()
    ingest.add_argument(
        "--max-database-bytes",
        type=int,
        default=ingest_defaults.max_database_bytes,
    )
    ingest.add_argument(
        "--destination-reserve-bytes",
        type=int,
        default=ingest_defaults.destination_reserve_bytes,
    )
    build = commands.add_parser("build", help="render one boundary V3 supplement")
    build.add_argument("--database", type=Path, required=True)
    build.add_argument("--ingest-receipt", type=Path, required=True)
    build.add_argument("--output", type=Path, required=True)
    build.add_argument("--package-id", required=True)
    build.add_argument(
        "--zooms",
        type=_parse_cli_zooms,
        default=DEFAULT_BOUNDARY_ZOOMS,
        help="comma-separated zooms (default: 3 through 11)",
    )
    build.add_argument("--workers", type=int, choices=range(1, 11), default=10)
    defaults = BoundaryProductionLimits()
    build.add_argument(
        "--max-spool-bytes-per-batch",
        type=int,
        default=defaults.max_spool_bytes_per_batch,
    )
    build.add_argument(
        "--max-total-spool-bytes",
        type=int,
        default=defaults.max_total_spool_bytes,
    )
    build.add_argument(
        "--max-render-database-bytes",
        type=int,
        default=defaults.max_render_database_bytes,
    )
    build.add_argument(
        "--max-output-bytes",
        type=int,
        default=defaults.max_runtime_package_bytes,
    )
    build.add_argument(
        "--destination-reserve-bytes",
        type=int,
        default=defaults.destination_reserve_bytes,
    )
    build.add_argument("--resume-directory", type=Path)
    build.add_argument("--resume-ordinal", type=int, default=0)
    arguments = parser.parse_args(argv)
    try:
        if arguments.command == "ingest":
            extraction_receipt = arguments.extraction_receipt or Path(
                str(arguments.closure) + ".receipt.json"
            )
            result = ingest_boundary_closure(
                closure_opl=arguments.closure,
                extraction_receipt=extraction_receipt,
                database_path=arguments.database,
                ingest_receipt_path=arguments.receipt,
                limits=BoundaryIngestLimits(
                    max_database_bytes=arguments.max_database_bytes,
                    destination_reserve_bytes=arguments.destination_reserve_bytes,
                ),
            )
            output = {
                "database": str(result.database_path),
                "ingestReceipt": str(result.ingest_receipt_path),
                "ingestSemanticSha256": result.ingest_semantic_sha256,
                "relationMemberships": result.relation_memberships,
                "requiredNodes": result.required_nodes,
                "selectedFeatures": result.selected_features,
                "selectedWays": result.selected_ways,
                "sourceClosureSha256": result.source_closure_sha256,
            }
        else:
            result = build_boundary_package(
                database_path=arguments.database,
                ingest_receipt_path=arguments.ingest_receipt,
                output_directory=arguments.output,
                package_id=arguments.package_id,
                zooms=arguments.zooms,
                limits=_cli_limits(
                    arguments.workers,
                    max_spool_bytes_per_batch=arguments.max_spool_bytes_per_batch,
                    max_total_spool_bytes=arguments.max_total_spool_bytes,
                    max_render_database_bytes=arguments.max_render_database_bytes,
                    max_runtime_package_bytes=arguments.max_output_bytes,
                    destination_reserve_bytes=arguments.destination_reserve_bytes,
                ),
                resume_directory=arguments.resume_directory,
                resume_ordinal=arguments.resume_ordinal,
            )
            output = {
                "buildReceipt": str(result.build_receipt_path),
                "indexSha256": result.index_sha256,
                "outputDirectory": str(result.output_directory),
                "presentTileCount": result.present_tile_count,
                "recordsSha256": result.records_sha256,
                "rendererSemanticSha256": result.renderer_semantic_sha256,
            }
    except BoundaryProductionError as error:
        parser.exit(1, f"boundary production failed: {error}\n")
    print(json.dumps(output, ensure_ascii=False, separators=(",", ":"), sort_keys=True))
    return 0


__all__ = [
    "BoundaryBuildResult",
    "BoundaryIngestLimits",
    "BoundaryIngestResult",
    "BoundaryProductionError",
    "BoundaryProductionLimits",
    "BoundaryRenderInput",
    "DEFAULT_BOUNDARY_ZOOMS",
    "build_boundary_package",
    "ingest_boundary_closure",
    "iter_boundary_render_batches",
    "main",
]


if __name__ == "__main__":
    raise SystemExit(main())
