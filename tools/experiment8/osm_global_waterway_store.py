from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import stat
import struct
import unicodedata
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import BinaryIO, Mapping, Sequence

from . import osm_global_waterway_package as source_module
from .osm_global_waterway_package import (
    GLOBAL_POLICY_SHA256,
    GlobalWaterwayBuildResult,
    GlobalWaterwayIngestResult,
    GlobalWaterwayPackageError,
    StrictOplNode,
    StrictOplMember,
    StrictOplRelation,
    StrictOplWay,
    WaterwaySourceBinding,
    _publish_directory_no_replace,
    _require_exact_directory_inventory,
    iter_strict_waterway_opl,
)
from .model import TileKey
from .reference_presentation_policy import PRESENTATION_POLICY_SHA256
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    INDEX_FLAG_PRESENT,
    MAX_RECORDS_PER_TILE,
    MAX_RENDERER_RECORD_BYTES,
    MAX_SOURCED_TEXT_RECORD_BYTES,
    MAX_TILE_BYTES,
    PAYLOAD_SCHEMA,
    SOURCED_TEXT_POLICY_SHA256,
    TILE_PAYLOAD_MAGIC,
    UNICODE_SCRIPT_PROFILE_SHA256,
    decode_tile_payload,
    encode_index_entry,
    raw_deflate,
    raw_hash32,
)
from .semantic_model import renderer_record_bytes, variant_fingerprint


_RUN_SCHEMA = "flightalert.experiment8.osm-global-waterway-ingest-run.v1"
_INGEST_SCHEMA = "flightalert.experiment8.osm-global-waterway-ingest.v1"
_SEMANTIC_DOMAIN = b"FAE8OSMWATERWAYCLOSURE1\0"
_SOURCE_PREFIX_DOMAIN = b"FAE8OSMWATERWAYSQLITEPREFIX1\0"
_ROOT_KIND = {"w": 1, "r": 2}
_MEMBER_KIND = {"n": 0, "w": 1, "r": 2}
_LANGUAGE_NAME_KEY = re.compile(
    r"name:([A-Za-z]{2,3})(?:-([A-Za-z0-9]{2,8}))*\Z"
)
_NON_LANGUAGE_SUFFIXES = frozenset(
    ("left", "right", "signed", "pronunciation", "etymology", "source", "old")
)
_ALLOWED_WATERWAYS = frozenset(
    ("river", "stream", "canal", "tidal_channel", "wadi")
)
_SOURCE_FEATURE_DOMAIN = b"FAE8OSMGLOBALWATERWAYFEATURE2\0"
_DEPENDENCY_EVIDENCE_DOMAIN = b"FAE8OSMWATERWAYDEPENDENCIES1\0"
_MAX_DEPENDENCY_RECORD_BYTES = 128 * 1024 * 1024
_MAX_DEPENDENCY_RECORDS = (1 << 64) - 1
_RELATION_POINT_ACCOUNTING_BYTES = struct.calcsize(">Qii")
_MAX_RELATION_DEPTH = source_module._MAX_LINE_BYTES // (1024 * 1024)
_MAX_RELATION_VISITS = source_module._MAX_LINE_BYTES // 1024
_MAX_RELATION_PARTS = MAX_RECORDS_PER_TILE
_MAX_RELATION_POINTS = MAX_RENDERER_RECORD_BYTES // _RELATION_POINT_ACCOUNTING_BYTES
_BUILD_SCHEMA = "flightalert.experiment8.osm-global-waterway-build.v1"
_RENDER_RUN_SCHEMA = "flightalert.experiment8.osm-global-waterway-render-run.v1"
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_DEFAULT_ZOOMS = tuple(range(4, 12))
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_PREFERRED_PHONE_FOOTPRINT_BYTES = 25_000_000_000
_HARD_PHONE_FOOTPRINT_BYTES = 40_000_000_000
_PREFERRED_COMPONENT_PACKAGE_BYTES = 23_500_000_000
_HARD_COMPONENT_PACKAGE_BYTES = 38_500_000_000
_RESERVED_NON_COMPONENT_FOOTPRINT_BYTES = (
    _HARD_PHONE_FOOTPRINT_BYTES - _HARD_COMPONENT_PACKAGE_BYTES
)
_TILE_HEADER_BYTES = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
_MAX_COMPRESSED_TILE_BYTES = (
    MAX_TILE_BYTES
    + (MAX_TILE_BYTES >> 12)
    + (MAX_TILE_BYTES >> 14)
    + (MAX_TILE_BYTES >> 25)
    + 13
)
_PARTIAL_OWNER_SCHEMA = "flightalert.experiment8.osm-global-waterway-partial-owner.v1"
_REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


class _DependencyEvidence:
    __slots__ = ("_digest", "_records")

    def __init__(self) -> None:
        self._digest = hashlib.sha256()
        self._digest.update(_DEPENDENCY_EVIDENCE_DOMAIN)
        self._records = 0

    def add(self, record: Sequence[object]) -> None:
        if self._records >= _MAX_DEPENDENCY_RECORDS:
            raise GlobalWaterwayPackageError(
                "waterway dependency record count exceeds its exact ceiling"
            )
        encoded = _canonical_json_bytes(list(record))
        if len(encoded) > _MAX_DEPENDENCY_RECORD_BYTES:
            raise GlobalWaterwayPackageError(
                "one waterway dependency record exceeds its exact byte ceiling"
            )
        self._digest.update(struct.pack(">Q", len(encoded)))
        self._digest.update(encoded)
        self._records += 1

    def document(self) -> dict[str, object]:
        return {"records": self._records, "sha256": self._digest.hexdigest()}


@dataclass(slots=True)
class _RelationGeometryBudget:
    max_depth: int
    max_relation_visits: int
    max_parts: int
    max_points: int
    relation_visits: int = 0
    parts: int = 0
    points: int = 0

    def enter_relation(self, depth: int) -> None:
        if depth > self.max_depth:
            raise GlobalWaterwayPackageError(
                "nested waterway relation depth ceiling exceeded"
            )
        if self.relation_visits >= self.max_relation_visits:
            raise GlobalWaterwayPackageError(
                "nested waterway relation-visit ceiling exceeded"
            )
        self.relation_visits += 1

    def reserve_way(self, point_count: int) -> None:
        if self.parts >= self.max_parts:
            raise GlobalWaterwayPackageError(
                "nested waterway relation part ceiling exceeded"
            )
        if point_count > self.max_points - self.points:
            raise GlobalWaterwayPackageError(
                "nested waterway relation point ceiling exceeded"
            )
        self.parts += 1
        self.points += point_count

    def usage(self) -> dict[str, int]:
        return {
            "parts": self.parts,
            "points": self.points,
            "relationVisits": self.relation_visits,
        }


def _relation_geometry_policy_document() -> dict[str, object]:
    return {
        "maxDepth": _MAX_RELATION_DEPTH,
        "maxParts": _MAX_RELATION_PARTS,
        "maxPoints": _MAX_RELATION_POINTS,
        "maxRelationVisits": _MAX_RELATION_VISITS,
        "schema": "flightalert.experiment8.osm-waterway-relation-geometry-policy.v1",
        "sourceLimits": {
            "depthUnitBytes": 1024 * 1024,
            "oplLineBytes": source_module._MAX_LINE_BYTES,
            "partCeilingRecordsPerTile": MAX_RECORDS_PER_TILE,
            "pointAccountingBytes": _RELATION_POINT_ACCOUNTING_BYTES,
            "rendererRecordBytes": MAX_RENDERER_RECORD_BYTES,
            "relationVisitUnitBytes": 1024,
        },
    }


@dataclass(frozen=True, slots=True)
class _Window:
    z: int
    x_min: int
    x_max: int
    y_min: int
    y_max: int

    @property
    def tile_count(self) -> int:
        return (self.x_max - self.x_min + 1) * (self.y_max - self.y_min + 1)

    def document(self) -> dict[str, int]:
        return {
            "tileCount": self.tile_count,
            "xMax": self.x_max,
            "xMin": self.x_min,
            "yMax": self.y_max,
            "yMin": self.y_min,
            "z": self.z,
        }


def _canonical_json_bytes(value: object) -> bytes:
    try:
        return (
            json.dumps(
                value,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as error:
        raise GlobalWaterwayPackageError("waterway store JSON is not canonical") from error


def _tags_bytes(tags: Sequence[tuple[str, str]]) -> bytes:
    return _canonical_json_bytes([list(item) for item in tags])


def _stream_identity(path: Path, label: str) -> dict[str, object]:
    try:
        link_status = os.lstat(path) if isinstance(path, Path) else None
    except OSError as error:
        raise GlobalWaterwayPackageError(
            f"{label} is not one regular non-link file"
        ) from error
    if (
        link_status is None
        or not stat.S_ISREG(link_status.st_mode)
        or getattr(link_status, "st_file_attributes", 0) & _REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(f"{label} is not one regular non-link file")
    before = path.stat()
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        opened = os.fstat(handle.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise GlobalWaterwayPackageError(f"{label} changed while opening")
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after_handle = os.fstat(handle.fileno())
    after = path.stat()
    signature = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    if (
        signature
        != (
            after_handle.st_dev,
            after_handle.st_ino,
            after_handle.st_size,
            after_handle.st_mtime_ns,
            after_handle.st_ctime_ns,
        )
        or signature
        != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        )
        or total != before.st_size
    ):
        raise GlobalWaterwayPackageError(f"{label} drifted while hashing")
    return {"bytes": total, "sha256": digest.hexdigest()}


def _require_identity(
    path: Path,
    *,
    expected_bytes: int,
    expected_sha256: str,
    label: str,
) -> None:
    actual = _stream_identity(path, label)
    if actual != {"bytes": expected_bytes, "sha256": expected_sha256}:
        raise GlobalWaterwayPackageError(
            f"{label} identity differs: expected {expected_bytes} bytes and "
            f"{expected_sha256}, got {actual['bytes']} bytes and {actual['sha256']}"
        )


def _runtime_identity_document() -> dict[str, object]:
    return source_module.python_runtime_identity_document()


def _meta_get(connection: sqlite3.Connection, key: str) -> object | None:
    row = connection.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return None if row is None else json.loads(bytes(row[0]).decode("utf-8", "strict"))


def _meta_set(connection: sqlite3.Connection, key: str, value: object) -> None:
    connection.execute(
        "INSERT INTO meta(key,value) VALUES (?,?) "
        "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
        (key, _canonical_json_bytes(value)),
    )


def _run_identity(
    source_binding: WaterwaySourceBinding,
    checkpoint_objects: int,
) -> dict[str, object]:
    code = {
        "source": _stream_identity(Path(source_module.__file__).resolve(), "waterway source code"),
        "store": _stream_identity(Path(__file__).resolve(), "waterway store code"),
    }
    return {
        "checkpointObjects": checkpoint_objects,
        "code": code,
        "schema": _RUN_SCHEMA,
        "source": source_binding.document(),
        "runtime": _runtime_identity_document(),
    }


def _open_database(
    path: Path,
    run_identity: Mapping[str, object],
) -> tuple[sqlite3.Connection, bool]:
    path.parent.mkdir(parents=True, exist_ok=True)
    existed = path.exists()
    connection = sqlite3.connect(path)
    connection.execute("PRAGMA journal_mode=DELETE")
    connection.execute("PRAGMA synchronous=FULL")
    connection.execute("PRAGMA temp_store=FILE")
    connection.execute("PRAGMA mmap_size=0")
    connection.execute("PRAGMA cache_size=-65536")
    if not existed:
        connection.executescript(
            "CREATE TABLE meta(key TEXT PRIMARY KEY,value BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE roots(kind INTEGER NOT NULL,id INTEGER NOT NULL,"
            "PRIMARY KEY(kind,id)) WITHOUT ROWID;"
            "CREATE TABLE nodes(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,"
            "timestamp TEXT NOT NULL,longitude_e7 INTEGER NOT NULL,latitude_e7 INTEGER NOT NULL,"
            "tags BLOB NOT NULL,payload_sha BLOB NOT NULL);"
            "CREATE TABLE ways(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,"
            "timestamp TEXT NOT NULL,tags BLOB NOT NULL,payload_sha BLOB NOT NULL);"
            "CREATE TABLE way_nodes(way_id INTEGER NOT NULL,ordinal INTEGER NOT NULL,"
            "node_id INTEGER NOT NULL,PRIMARY KEY(way_id,ordinal)) WITHOUT ROWID;"
            "CREATE INDEX way_nodes_node ON way_nodes(node_id);"
            "CREATE TABLE relations(id INTEGER PRIMARY KEY,version INTEGER NOT NULL,"
            "timestamp TEXT NOT NULL,tags BLOB NOT NULL,payload_sha BLOB NOT NULL);"
            "CREATE TABLE relation_members(relation_id INTEGER NOT NULL,ordinal INTEGER NOT NULL,"
            "kind INTEGER NOT NULL,ref INTEGER NOT NULL,role TEXT NOT NULL,"
            "PRIMARY KEY(relation_id,ordinal)) WITHOUT ROWID;"
            "CREATE INDEX relation_members_ref ON relation_members(kind,ref);"
            "CREATE TABLE rendered_features(ordinal INTEGER PRIMARY KEY,source_kind TEXT NOT NULL,"
            "source_id INTEGER NOT NULL,source_type TEXT NOT NULL,feature_sha BLOB NOT NULL,"
            "UNIQUE(source_kind,source_id,source_type));"
            "CREATE TABLE feature_ids(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE variant_ids(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE geometry_ids(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE label_ids(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE sourced_ids(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE records(z INTEGER NOT NULL,y INTEGER NOT NULL,x INTEGER NOT NULL,"
            "posting_key BLOB NOT NULL,draw_order INTEGER NOT NULL,priority INTEGER NOT NULL,"
            "layer_group INTEGER NOT NULL,feature_kind INTEGER NOT NULL,variant_id BLOB NOT NULL,"
            "feature_id BLOB NOT NULL,sourced_sha BLOB NOT NULL,envelope BLOB NOT NULL,"
            "subtype INTEGER NOT NULL,source_type TEXT NOT NULL,"
            "UNIQUE(z,y,x,posting_key));"
            "CREATE INDEX records_semantic_coordinates ON records(z,x,y);"
        )
        _meta_set(connection, "runIdentity", dict(run_identity))
        _meta_set(
            connection,
            "checkpoint",
            {
                "ingestComplete": False,
                "inputObjects": 0,
                "lineNumber": 0,
                "nextByteOffset": 0,
                "previousId": 0,
                "previousKind": -1,
            },
        )
        _meta_set(
            connection,
            "peaks",
            {
                "compressedTileBytes": 0,
                "featurePostingBytes": 0,
                "inputLineBytes": 0,
                "observedPersistentSqliteBytesAtCheckpoints": 0,
                "rawTileBytes": 0,
                "recordsPerTile": 0,
            },
        )
        connection.commit()
    elif _meta_get(connection, "runIdentity") != dict(run_identity):
        connection.close()
        raise GlobalWaterwayPackageError(
            "checkpoint identity differs from exact source/config/code identity"
        )
    return connection, not existed


def _insert_roots(connection: sqlite3.Connection, root_ids_path: Path) -> tuple[int, int]:
    previous = (0, 0)
    counts = {1: 0, 2: 0}
    with root_ids_path.open("rb") as handle:
        line_number = 0
        while True:
            raw = handle.readline(64)
            if not raw:
                break
            line_number += 1
            if len(raw) >= 64 or not raw.endswith(b"\n") or raw.endswith(b"\r\n"):
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is not bounded canonical LF text"
                )
            try:
                text = raw[:-1].decode("ascii", "strict")
            except UnicodeDecodeError as error:
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is not ASCII"
                ) from error
            if len(text) < 2 or text[0] not in _ROOT_KIND or not text[1:].isdigit() or text[1] == "0":
                raise GlobalWaterwayPackageError(f"root-ID line {line_number} is malformed")
            object_id = int(text[1:])
            if object_id > (1 << 63) - 1:
                raise GlobalWaterwayPackageError(f"root-ID line {line_number} exceeds signed-63")
            key = (_ROOT_KIND[text[0]], object_id)
            if key <= previous:
                raise GlobalWaterwayPackageError("root-ID file is not unique Type_then_ID order")
            previous = key
            connection.execute("INSERT INTO roots(kind,id) VALUES (?,?)", key)
            counts[key[0]] += 1
    if not sum(counts.values()):
        raise GlobalWaterwayPackageError("root-ID file is empty")
    connection.commit()
    return counts[1], counts[2]


def _validate_root_table(
    connection: sqlite3.Connection,
    root_ids_path: Path,
) -> None:
    rows = iter(connection.execute("SELECT kind,id FROM roots ORDER BY kind,id"))
    actual = next(rows, None)
    previous = (0, 0)
    seen = 0
    with root_ids_path.open("rb") as handle:
        line_number = 0
        while True:
            raw = handle.readline(64)
            if not raw:
                break
            line_number += 1
            if len(raw) >= 64 or not raw.endswith(b"\n") or raw.endswith(b"\r\n"):
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is not bounded canonical LF text"
                )
            try:
                text = raw[:-1].decode("ascii", "strict")
            except UnicodeDecodeError as error:
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is not ASCII"
                ) from error
            if (
                len(text) < 2
                or text[0] not in _ROOT_KIND
                or not text[1:].isdigit()
                or text[1] == "0"
            ):
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is malformed"
                )
            object_id = int(text[1:])
            key = (_ROOT_KIND[text[0]], object_id)
            if object_id > (1 << 63) - 1 or key <= previous:
                raise GlobalWaterwayPackageError(
                    "root-ID file is not canonical Type_then_ID order"
                )
            previous = key
            if actual is None or (int(actual[0]), int(actual[1])) != key:
                raise GlobalWaterwayPackageError(
                    "bound root-ID file differs from committed SQLite roots"
                )
            actual = next(rows, None)
            seen += 1
    if not seen or actual is not None:
        raise GlobalWaterwayPackageError(
            "bound root-ID file differs from committed SQLite roots"
        )


def _payload_sha(value: object) -> bytes:
    if isinstance(value, StrictOplNode):
        document: object = {
            "id": value.object_id,
            "kind": "node",
            "latitudeE7": value.latitude_e7,
            "longitudeE7": value.longitude_e7,
            "tags": [list(item) for item in value.tags],
            "timestamp": value.timestamp,
            "version": value.version,
        }
    elif isinstance(value, StrictOplWay):
        document = {
            "id": value.object_id,
            "kind": "way",
            "nodeRefs": list(value.node_refs),
            "tags": [list(item) for item in value.tags],
            "timestamp": value.timestamp,
            "version": value.version,
        }
    elif isinstance(value, StrictOplRelation):
        document = {
            "id": value.object_id,
            "kind": "relation",
            "members": [
                [member.object_type, member.ref, member.role, member.ordinal]
                for member in value.members
            ],
            "tags": [list(item) for item in value.tags],
            "timestamp": value.timestamp,
            "version": value.version,
        }
    else:
        raise GlobalWaterwayPackageError("strict OPL object type is unsupported")
    return hashlib.sha256(_canonical_json_bytes(document)).digest()


def _insert_object(connection: sqlite3.Connection, value: object) -> None:
    try:
        if isinstance(value, StrictOplNode):
            connection.execute(
                "INSERT INTO nodes(id,version,timestamp,longitude_e7,latitude_e7,tags,payload_sha) "
                "VALUES (?,?,?,?,?,?,?)",
                (
                    value.object_id,
                    value.version,
                    value.timestamp,
                    value.longitude_e7,
                    value.latitude_e7,
                    _tags_bytes(value.tags),
                    _payload_sha(value),
                ),
            )
            return
        if isinstance(value, StrictOplWay):
            connection.execute(
                "INSERT INTO ways(id,version,timestamp,tags,payload_sha) VALUES (?,?,?,?,?)",
                (
                    value.object_id,
                    value.version,
                    value.timestamp,
                    _tags_bytes(value.tags),
                    _payload_sha(value),
                ),
            )
            connection.executemany(
                "INSERT INTO way_nodes(way_id,ordinal,node_id) VALUES (?,?,?)",
                (
                    (value.object_id, ordinal, node_id)
                    for ordinal, node_id in enumerate(value.node_refs)
                ),
            )
            return
        if isinstance(value, StrictOplRelation):
            connection.execute(
                "INSERT INTO relations(id,version,timestamp,tags,payload_sha) VALUES (?,?,?,?,?)",
                (
                    value.object_id,
                    value.version,
                    value.timestamp,
                    _tags_bytes(value.tags),
                    _payload_sha(value),
                ),
            )
            connection.executemany(
                "INSERT INTO relation_members(relation_id,ordinal,kind,ref,role) "
                "VALUES (?,?,?,?,?)",
                (
                    (
                        value.object_id,
                        member.ordinal,
                        _MEMBER_KIND[member.object_type],
                        member.ref,
                        member.role,
                    )
                    for member in value.members
                ),
            )
            return
    except sqlite3.IntegrityError as error:
        raise GlobalWaterwayPackageError("closure contains a duplicate object or ordinal") from error
    raise GlobalWaterwayPackageError("closure object type is unsupported")


def _database_bytes(path: Path) -> int:
    total = path.stat().st_size if path.exists() else 0
    for suffix in ("-journal", "-wal", "-shm"):
        candidate = Path(str(path) + suffix)
        if candidate.exists():
            total += candidate.stat().st_size
    return total


def _commit_checkpoint(
    connection: sqlite3.Connection,
    database_path: Path,
    checkpoint: Mapping[str, object],
    peaks: dict[str, int],
) -> None:
    _meta_set(connection, "checkpoint", dict(checkpoint))
    _meta_set(connection, "peaks", peaks)
    connection.commit()
    peaks["observedPersistentSqliteBytesAtCheckpoints"] = max(
        peaks["observedPersistentSqliteBytesAtCheckpoints"],
        _database_bytes(database_path),
    )
    _meta_set(connection, "peaks", peaks)
    connection.commit()


def _signature(status: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        status.st_dev,
        status.st_ino,
        status.st_size,
        status.st_mtime_ns,
        status.st_ctime_ns,
    )


def _verified_open_opl(
    opl_path: Path,
    binding: WaterwaySourceBinding,
) -> tuple[BinaryIO, tuple[int, int, int, int, int]]:
    handle = opl_path.open("rb")
    try:
        before_path = opl_path.stat()
        before_handle = os.fstat(handle.fileno())
        signature = _signature(before_handle)
        if (
            (before_path.st_dev, before_path.st_ino)
            != (before_handle.st_dev, before_handle.st_ino)
            or before_handle.st_size != binding.closure_opl_bytes
        ):
            raise GlobalWaterwayPackageError("closure OPL changed while opening")
        digest = hashlib.sha256()
        total = 0
        while True:
            chunk = handle.read(4 * 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        if total != binding.closure_opl_bytes or digest.hexdigest() != binding.closure_opl_sha256:
            raise GlobalWaterwayPackageError("closure OPL identity differs")
        if _signature(os.fstat(handle.fileno())) != signature or _signature(opl_path.stat()) != signature:
            raise GlobalWaterwayPackageError("closure OPL drifted while hashing")
        return handle, signature
    except BaseException:
        handle.close()
        raise


def _require_open_unchanged(
    handle: BinaryIO,
    path: Path,
    expected: tuple[int, int, int, int, int],
) -> None:
    if _signature(os.fstat(handle.fileno())) != expected or _signature(path.stat()) != expected:
        raise GlobalWaterwayPackageError("closure OPL drifted during ingestion")


def _ingest(
    connection: sqlite3.Connection,
    database_path: Path,
    *,
    opl_path: Path,
    source_binding: WaterwaySourceBinding,
    checkpoint_objects: int,
    pause_after_objects: int | None,
    authenticate_prefix: bool,
) -> bool:
    checkpoint = dict(_meta_get(connection, "checkpoint") or {})
    if authenticate_prefix:
        _validate_ingest_prefix(
            connection,
            opl_path=opl_path,
            source_binding=source_binding,
            checkpoint=checkpoint,
        )
    if checkpoint.get("ingestComplete") is True:
        _require_identity(
            opl_path,
            expected_bytes=source_binding.closure_opl_bytes,
            expected_sha256=source_binding.closure_opl_sha256,
            label="closure OPL",
        )
        return True
    peaks = dict(
        _meta_get(connection, "peaks")
        or {"inputLineBytes": 0, "observedPersistentSqliteBytesAtCheckpoints": 0}
    )
    offset = int(checkpoint["nextByteOffset"])
    line_number = int(checkpoint["lineNumber"])
    previous_kind = int(checkpoint["previousKind"])
    previous_id = int(checkpoint["previousId"])
    total = int(checkpoint["inputObjects"])
    since_commit = 0
    handle, open_signature = _verified_open_opl(opl_path, source_binding)
    try:
        handle.seek(offset)
        prior = None if previous_kind < 0 else (previous_kind, previous_id)
        for parsed in iter_strict_waterway_opl(
            handle,
            initial_byte_offset=offset,
            initial_line_number=line_number,
            previous_key=prior,
        ):
            value = parsed.value
            _insert_object(connection, value)
            total += 1
            kind = 0 if isinstance(value, StrictOplNode) else 1 if isinstance(value, StrictOplWay) else 2
            checkpoint = {
                "ingestComplete": False,
                "inputObjects": total,
                "lineNumber": parsed.line_number,
                "nextByteOffset": parsed.byte_end,
                "previousId": value.object_id,
                "previousKind": kind,
            }
            peaks["inputLineBytes"] = max(
                peaks["inputLineBytes"], parsed.byte_end - parsed.byte_start
            )
            offset = parsed.byte_end
            line_number = parsed.line_number
            previous_kind = kind
            previous_id = value.object_id
            since_commit += 1
            if since_commit >= checkpoint_objects:
                _commit_checkpoint(connection, database_path, checkpoint, peaks)
                since_commit = 0
            if pause_after_objects is not None and total >= pause_after_objects:
                _require_open_unchanged(handle, opl_path, open_signature)
                _commit_checkpoint(connection, database_path, checkpoint, peaks)
                return False
        _require_open_unchanged(handle, opl_path, open_signature)
    finally:
        handle.close()
    if offset != source_binding.closure_opl_bytes:
        raise GlobalWaterwayPackageError("closure OPL terminal offset differs from binding")
    checkpoint["ingestComplete"] = True
    _commit_checkpoint(connection, database_path, checkpoint, peaks)
    return True


def _missing_reference(connection: sqlite3.Connection) -> tuple[str, int] | None:
    row = connection.execute(
        "SELECT wn.node_id FROM way_nodes wn LEFT JOIN nodes n ON n.id=wn.node_id "
        "WHERE n.id IS NULL ORDER BY wn.way_id,wn.ordinal LIMIT 1"
    ).fetchone()
    if row is not None:
        return "node", int(row[0])
    queries = (
        (0, "nodes", "node"),
        (1, "ways", "way"),
        (2, "relations", "relation"),
    )
    for kind, table, label in queries:
        row = connection.execute(
            f"SELECT rm.ref FROM relation_members rm LEFT JOIN {table} target ON target.id=rm.ref "
            "WHERE rm.kind=? AND target.id IS NULL ORDER BY rm.relation_id,rm.ordinal LIMIT 1",
            (kind,),
        ).fetchone()
        if row is not None:
            return label, int(row[0])
    return None


def _relation_has_geometry(
    connection: sqlite3.Connection,
    relation_id: int,
    stack: tuple[int, ...] = (),
) -> bool:
    if relation_id in stack:
        raise GlobalWaterwayPackageError("relation membership cycle is forbidden")
    found = False
    for ordinal, kind, ref in connection.execute(
        "SELECT ordinal,kind,ref FROM relation_members WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    ):
        if int(ordinal) < 0:
            raise GlobalWaterwayPackageError("relation member ordinal is negative")
        kind = int(kind)
        ref = int(ref)
        if kind == 1:
            count = int(
                connection.execute(
                    "SELECT COUNT(*) FROM way_nodes WHERE way_id=?", (ref,)
                ).fetchone()[0]
            )
            if count < 2:
                raise GlobalWaterwayPackageError(
                    f"relation member way {ref} has fewer than two nodes"
                )
            found = True
        elif kind == 2:
            found = _relation_has_geometry(connection, ref, stack + (relation_id,)) or found
    return found


def _validate_closure(connection: sqlite3.Connection) -> dict[str, int]:
    missing = _missing_reference(connection)
    if missing is not None:
        raise GlobalWaterwayPackageError(f"closure references missing {missing[0]} {missing[1]}")
    short_way = connection.execute(
        "SELECT w.id FROM ways w WHERE (SELECT COUNT(*) FROM way_nodes wn WHERE wn.way_id=w.id)<2 "
        "ORDER BY w.id LIMIT 1"
    ).fetchone()
    if short_way is not None:
        raise GlobalWaterwayPackageError(
            f"closure way {int(short_way[0])} has fewer than two nodes"
        )
    for kind, object_id in connection.execute("SELECT kind,id FROM roots ORDER BY kind,id"):
        table = "ways" if int(kind) == 1 else "relations"
        if connection.execute(f"SELECT 1 FROM {table} WHERE id=?", (object_id,)).fetchone() is None:
            raise GlobalWaterwayPackageError(
                f"selected root {table[:-1]} {int(object_id)} is absent from closure"
            )
        if int(kind) == 2 and not _relation_has_geometry(connection, int(object_id)):
            raise GlobalWaterwayPackageError(
                f"selected relation {int(object_id)} contains no usable exact way geometry"
            )
    nodes = int(connection.execute("SELECT COUNT(*) FROM nodes").fetchone()[0])
    ways = int(connection.execute("SELECT COUNT(*) FROM ways").fetchone()[0])
    relations = int(connection.execute("SELECT COUNT(*) FROM relations").fetchone()[0])
    selected_ways = int(
        connection.execute("SELECT COUNT(*) FROM roots WHERE kind=1").fetchone()[0]
    )
    selected_relations = int(
        connection.execute("SELECT COUNT(*) FROM roots WHERE kind=2").fetchone()[0]
    )
    return {
        "missingReferences": 0,
        "nodes": nodes,
        "referenceOnlyRelations": relations - selected_relations,
        "referenceOnlyWays": ways - selected_ways,
        "relations": relations,
        "selectedRelationRoots": selected_relations,
        "selectedWayRoots": selected_ways,
        "ways": ways,
    }


def _digest_field(digest: object, value: object) -> None:
    if type(value) is int:
        digest.update(b"i" + int(value).to_bytes(8, "little", signed=True))
    elif type(value) is str:
        raw = value.encode("utf-8", "strict")
        digest.update(b"s" + struct.pack("<I", len(raw)) + raw)
    elif type(value) is bytes:
        digest.update(b"b" + struct.pack("<I", len(value)) + value)
    else:
        raise GlobalWaterwayPackageError("SQLite semantic field type is unsupported")


def _semantic_sha256(connection: sqlite3.Connection) -> str:
    digest = hashlib.sha256(_SEMANTIC_DOMAIN)
    streams = (
        ("roots", "SELECT kind,id FROM roots ORDER BY kind,id"),
        (
            "nodes",
            "SELECT id,version,timestamp,longitude_e7,latitude_e7,tags,payload_sha "
            "FROM nodes ORDER BY id",
        ),
        ("ways", "SELECT id,version,timestamp,tags,payload_sha FROM ways ORDER BY id"),
        ("way_nodes", "SELECT way_id,ordinal,node_id FROM way_nodes ORDER BY way_id,ordinal"),
        (
            "relations",
            "SELECT id,version,timestamp,tags,payload_sha FROM relations ORDER BY id",
        ),
        (
            "relation_members",
            "SELECT relation_id,ordinal,kind,ref,role FROM relation_members "
            "ORDER BY relation_id,ordinal",
        ),
    )
    for name, query in streams:
        name_raw = name.encode("ascii")
        digest.update(struct.pack("<I", len(name_raw)) + name_raw)
        for row in connection.execute(query):
            digest.update(struct.pack("<I", len(row)))
            for value in row:
                _digest_field(digest, value)
    return digest.hexdigest()


def _insert_hot_identity(
    connection: sqlite3.Connection,
    table: str,
    hot_id: bytes,
    full_sha: bytes,
) -> None:
    if table not in {"feature_ids", "variant_ids", "geometry_ids", "label_ids", "sourced_ids", "identities"}:
        raise GlobalWaterwayPackageError("hot identity table is unsupported")
    if type(hot_id) is not bytes or len(hot_id) != 8:
        raise GlobalWaterwayPackageError("hot identity must be eight immutable bytes")
    if type(full_sha) is not bytes or len(full_sha) != 32:
        raise GlobalWaterwayPackageError("full identity must be immutable SHA-256 bytes")
    cursor = connection.execute(
        f"INSERT OR IGNORE INTO {table}(hot_id,full_sha) VALUES (?,?)",
        (hot_id, full_sha),
    )
    if cursor.rowcount:
        return
    row = connection.execute(
        f"SELECT full_sha FROM {table} WHERE hot_id=?", (hot_id,)
    ).fetchone()
    if row is None or bytes(row[0]) != full_sha:
        raise GlobalWaterwayPackageError(
            "fatal 64-bit identity collision between unequal canonical values"
        )


def enforce_global_waterway_storage_ceiling(package_bytes: int) -> None:
    if type(package_bytes) is not int or package_bytes < 0:
        raise GlobalWaterwayPackageError("package storage projection must be nonnegative")
    if package_bytes >= _HARD_COMPONENT_PACKAGE_BYTES:
        raise GlobalWaterwayPackageError(
            "global waterway component must remain below 38,500,000,000 bytes"
        )


def _decode_tags(raw: object, label: str) -> tuple[tuple[str, str], ...]:
    if type(raw) is not bytes:
        raise GlobalWaterwayPackageError(f"{label} tag payload is not immutable bytes")
    try:
        value = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GlobalWaterwayPackageError(f"{label} tag payload is malformed") from error
    if (
        type(value) is not list
        or any(
            type(item) is not list
            or len(item) != 2
            or type(item[0]) is not str
            or type(item[1]) is not str
            for item in value
        )
    ):
        raise GlobalWaterwayPackageError(f"{label} tag payload shape is malformed")
    tags = tuple((item[0], item[1]) for item in value)
    if len(dict(tags)) != len(tags):
        raise GlobalWaterwayPackageError(f"{label} repeats a tag key")
    if _tags_bytes(tags) != raw:
        raise GlobalWaterwayPackageError(f"{label} tag payload is not canonical")
    return tags


def _prefix_digest_update(digest: object, value: object) -> None:
    if isinstance(value, StrictOplNode):
        kind = 0
    elif isinstance(value, StrictOplWay):
        kind = 1
    elif isinstance(value, StrictOplRelation):
        kind = 2
    else:
        raise GlobalWaterwayPackageError(
            "waterway SQLite prefix contains an unsupported object"
        )
    digest.update(bytes((kind,)))
    digest.update(value.object_id.to_bytes(8, "big"))
    digest.update(_payload_sha(value))


def _bound_opl_prefix_digest(
    opl_path: Path,
    source_binding: WaterwaySourceBinding,
    checkpoint: Mapping[str, object],
) -> str:
    expected_offset = checkpoint.get("nextByteOffset")
    expected_objects = checkpoint.get("inputObjects")
    expected_line = checkpoint.get("lineNumber")
    expected_kind = checkpoint.get("previousKind")
    expected_id = checkpoint.get("previousId")
    if any(
        type(value) is not int or value < 0
        for value in (
            expected_offset,
            expected_objects,
            expected_line,
            expected_id,
        )
    ) or type(expected_kind) is not int or not -1 <= expected_kind <= 2:
        raise GlobalWaterwayPackageError(
            "waterway ingest checkpoint prefix fields are malformed"
        )
    digest = hashlib.sha256(_SOURCE_PREFIX_DOMAIN)
    count = 0
    terminal_offset = 0
    terminal_line = 0
    terminal_kind = -1
    terminal_id = 0
    handle, signature = _verified_open_opl(opl_path, source_binding)
    try:
        handle.seek(0)
        for parsed in iter_strict_waterway_opl(handle):
            if parsed.byte_end > expected_offset:
                break
            _prefix_digest_update(digest, parsed.value)
            count += 1
            terminal_offset = parsed.byte_end
            terminal_line = parsed.line_number
            terminal_kind = (
                0
                if isinstance(parsed.value, StrictOplNode)
                else 1
                if isinstance(parsed.value, StrictOplWay)
                else 2
            )
            terminal_id = parsed.value.object_id
        _require_open_unchanged(handle, opl_path, signature)
    finally:
        handle.close()
    if (
        count != expected_objects
        or terminal_offset != expected_offset
        or terminal_line != expected_line
        or terminal_kind != expected_kind
        or terminal_id != expected_id
    ):
        raise GlobalWaterwayPackageError(
            "bound OPL prefix contradicts the waterway ingest checkpoint"
        )
    return digest.hexdigest()


def _sqlite_prefix_digest(connection: sqlite3.Connection) -> tuple[str, int]:
    digest = hashlib.sha256(_SOURCE_PREFIX_DOMAIN)
    count = 0
    for row in connection.execute(
        "SELECT id,version,timestamp,tags,payload_sha,longitude_e7,latitude_e7 "
        "FROM nodes ORDER BY id"
    ):
        object_id, version, timestamp, raw_tags, stored_sha, longitude, latitude = row
        value = StrictOplNode(
            int(object_id),
            int(version),
            str(timestamp),
            _decode_tags(bytes(raw_tags), f"node {int(object_id)}"),
            int(longitude),
            int(latitude),
        )
        if bytes(stored_sha) != _payload_sha(value):
            raise GlobalWaterwayPackageError(
                f"SQLite node {int(object_id)} payload differs from its canonical row"
            )
        _prefix_digest_update(digest, value)
        count += 1

    node_rows = iter(
        connection.execute(
            "SELECT way_id,ordinal,node_id FROM way_nodes ORDER BY way_id,ordinal"
        )
    )
    pending_node = next(node_rows, None)
    for row in connection.execute(
        "SELECT id,version,timestamp,tags,payload_sha FROM ways ORDER BY id"
    ):
        object_id, version, timestamp, raw_tags, stored_sha = row
        refs: list[int] = []
        while pending_node is not None and int(pending_node[0]) == int(object_id):
            if int(pending_node[1]) != len(refs):
                raise GlobalWaterwayPackageError(
                    f"SQLite way {int(object_id)} node ordinals are malformed"
                )
            refs.append(int(pending_node[2]))
            pending_node = next(node_rows, None)
        value = StrictOplWay(
            int(object_id),
            int(version),
            str(timestamp),
            _decode_tags(bytes(raw_tags), f"way {int(object_id)}"),
            tuple(refs),
        )
        if bytes(stored_sha) != _payload_sha(value):
            raise GlobalWaterwayPackageError(
                f"SQLite way {int(object_id)} payload differs from its canonical row"
            )
        _prefix_digest_update(digest, value)
        count += 1
    if pending_node is not None:
        raise GlobalWaterwayPackageError("SQLite contains orphan way-node rows")

    member_rows = iter(
        connection.execute(
            "SELECT relation_id,ordinal,kind,ref,role FROM relation_members "
            "ORDER BY relation_id,ordinal"
        )
    )
    pending_member = next(member_rows, None)
    member_kind = {0: "n", 1: "w", 2: "r"}
    for row in connection.execute(
        "SELECT id,version,timestamp,tags,payload_sha FROM relations ORDER BY id"
    ):
        object_id, version, timestamp, raw_tags, stored_sha = row
        members = []
        while pending_member is not None and int(pending_member[0]) == int(object_id):
            ordinal = int(pending_member[1])
            kind = int(pending_member[2])
            if ordinal != len(members) or kind not in member_kind:
                raise GlobalWaterwayPackageError(
                    f"SQLite relation {int(object_id)} member rows are malformed"
                )
            members.append(
                StrictOplMember(
                    member_kind[kind],
                    int(pending_member[3]),
                    str(pending_member[4]),
                    ordinal,
                )
            )
            pending_member = next(member_rows, None)
        value = StrictOplRelation(
            int(object_id),
            int(version),
            str(timestamp),
            _decode_tags(bytes(raw_tags), f"relation {int(object_id)}"),
            tuple(members),
        )
        if bytes(stored_sha) != _payload_sha(value):
            raise GlobalWaterwayPackageError(
                f"SQLite relation {int(object_id)} payload differs from its canonical row"
            )
        _prefix_digest_update(digest, value)
        count += 1
    if pending_member is not None:
        raise GlobalWaterwayPackageError("SQLite contains orphan relation-member rows")
    return digest.hexdigest(), count


def _validate_ingest_prefix(
    connection: sqlite3.Connection,
    *,
    opl_path: Path,
    source_binding: WaterwaySourceBinding,
    checkpoint: Mapping[str, object],
) -> None:
    expected = _bound_opl_prefix_digest(opl_path, source_binding, checkpoint)
    actual, count = _sqlite_prefix_digest(connection)
    if actual != expected or count != checkpoint.get("inputObjects"):
        raise GlobalWaterwayPackageError(
            "bound OPL prefix differs from committed SQLite source rows"
        )


def _supported_language_name(key: str) -> bool:
    match = _LANGUAGE_NAME_KEY.fullmatch(key)
    if match is None:
        return False
    return all(
        component.casefold() not in _NON_LANGUAGE_SUFFIXES
        for component in key[5:].split("-")
    )


def _source_names(tags: Sequence[tuple[str, str]]) -> tuple[str, str, str | None]:
    by_key = dict(tags)
    primary_keys: list[str] = []
    for key in ("name", "official_name"):
        if key in by_key:
            primary_keys.append(key)
    primary_keys.extend(
        key
        for key, _ in tags
        if key != "name:en" and _supported_language_name(key) and key not in primary_keys
    )
    for key in ("int_name", "name:en"):
        if key in by_key and key not in primary_keys:
            primary_keys.append(key)
    for key in primary_keys:
        value = by_key[key]
        if value.strip():
            english = by_key.get("name:en") if key != "name:en" else None
            if english is not None and not english.strip():
                english = None
            return key, value, english
    raise GlobalWaterwayPackageError("selected waterway root has no nonblank exact source name")


def _point_rows(
    connection: sqlite3.Connection,
    way_id: int,
    evidence: _DependencyEvidence,
):
    from .osm_global_waterway_renderer import ExactWaterwayPoint

    rows = connection.execute(
        "SELECT wn.ordinal,n.id,n.longitude_e7,n.latitude_e7,n.payload_sha "
        "FROM way_nodes wn JOIN nodes n ON n.id=wn.node_id "
        "WHERE wn.way_id=? ORDER BY wn.ordinal",
        (way_id,),
    )
    points = []
    try:
        for expected, row in enumerate(rows):
            ordinal, node_id, longitude_e7, latitude_e7, payload_sha = row
            if int(ordinal) != expected:
                raise GlobalWaterwayPackageError(
                    f"waterway way {way_id} node order is malformed"
                )
            digest = bytes(payload_sha)
            evidence.add(["node", int(node_id), digest.hex()])
            points.append(
                ExactWaterwayPoint(int(node_id), int(longitude_e7), int(latitude_e7))
            )
    finally:
        rows.close()
    if len(points) < 2:
        raise GlobalWaterwayPackageError(f"waterway way {way_id} has incomplete geometry")
    return tuple(points)


def _way_source(
    connection: sqlite3.Connection,
    way_id: int,
    evidence: _DependencyEvidence,
    geometry_budget: _RelationGeometryBudget | None = None,
) -> tuple[int, str, tuple[tuple[str, str], ...], bytes, object]:
    row = connection.execute(
        "SELECT version,timestamp,tags,payload_sha FROM ways WHERE id=?", (way_id,)
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(f"waterway source references missing way {way_id}")
    version, timestamp, raw_tags, payload_sha = row
    tags = _decode_tags(bytes(raw_tags), f"way {way_id}")
    digest = bytes(payload_sha)
    evidence.add(["way", way_id, digest.hex()])
    if geometry_budget is not None:
        point_count = connection.execute(
            "SELECT COUNT(*) FROM way_nodes WHERE way_id=?", (way_id,)
        ).fetchone()
        geometry_budget.reserve_way(int(point_count[0]))
    points = _point_rows(connection, way_id, evidence)
    return int(version), str(timestamp), tags, digest, points


def _feature_identity(
    *,
    source_binding: WaterwaySourceBinding,
    source_kind: str,
    source_id: int,
    source_version: int,
    source_timestamp: str,
    waterway_type: str,
    name_source_key: str,
    primary_name: str,
    english_name: str | None,
    parts: Sequence[Sequence[object]],
    required_node_ids: frozenset[int],
    dependency_evidence: Mapping[str, object],
) -> bytes:
    document = {
        "dependencyEvidence": dict(dependency_evidence),
        "englishName": english_name,
        "nameSourceKey": name_source_key,
        "parts": [
            [
                [point.node_id, point.longitude_e7, point.latitude_e7]
                for point in part
            ]
            for part in parts
        ],
        "planetSha256": source_binding.planet_sha256,
        "primaryName": primary_name,
        "requiredJoinNodeIds": sorted(required_node_ids),
        "sourceId": source_id,
        "sourceKind": source_kind,
        "sourceTimestamp": source_timestamp,
        "sourceVersion": source_version,
        "waterwayType": waterway_type,
    }
    return hashlib.sha256(_SOURCE_FEATURE_DOMAIN + _canonical_json_bytes(document)).digest()


def _relation_occurrences(
    connection: sqlite3.Connection,
    relation_id: int,
    evidence: _DependencyEvidence,
    *,
    geometry_budget: _RelationGeometryBudget,
    inherited_type: str | None = None,
    stack: tuple[int, ...] = (),
):
    if relation_id in stack:
        raise GlobalWaterwayPackageError("relation membership cycle is forbidden")
    geometry_budget.enter_relation(len(stack) + 1)
    row = connection.execute(
        "SELECT tags,payload_sha FROM relations WHERE id=?", (relation_id,)
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(f"waterway source references missing relation {relation_id}")
    tags = _decode_tags(bytes(row[0]), f"relation {relation_id}")
    evidence.add(["relation", relation_id, bytes(row[1]).hex()])
    declared_type = dict(tags).get("waterway")
    if declared_type is not None and declared_type not in _ALLOWED_WATERWAYS:
        raise GlobalWaterwayPackageError(
            f"relation {relation_id} has unsupported exact waterway type {declared_type!r}"
        )
    effective_type = declared_type or inherited_type
    next_stack = stack + (relation_id,)
    rows = connection.execute(
        "SELECT ordinal,kind,ref,role FROM relation_members "
        "WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    )
    try:
        for expected_ordinal, row in enumerate(rows):
            ordinal, kind, ref, role = row
            if int(ordinal) != expected_ordinal:
                raise GlobalWaterwayPackageError(
                    f"relation {relation_id} member order is malformed"
                )
            kind = int(kind)
            ref = int(ref)
            evidence.add(
                ["member", relation_id, expected_ordinal, kind, ref, str(role)]
            )
            if kind == 0:
                node = connection.execute(
                    "SELECT payload_sha FROM nodes WHERE id=?", (ref,)
                ).fetchone()
                if node is None:
                    raise GlobalWaterwayPackageError(
                        f"relation {relation_id} references missing node {ref}"
                    )
                evidence.add(["relation-node", ref, bytes(node[0]).hex()])
                continue
            if kind == 1:
                _, _, way_tags, _, points = _way_source(
                    connection, ref, evidence, geometry_budget
                )
                waterway_type = effective_type or dict(way_tags).get("waterway")
                if waterway_type not in _ALLOWED_WATERWAYS:
                    raise GlobalWaterwayPackageError(
                        f"relation member way {ref} lacks one exact supported waterway type"
                    )
                yield waterway_type, points
                continue
            if kind == 2:
                yield from _relation_occurrences(
                    connection,
                    ref,
                    evidence,
                    geometry_budget=geometry_budget,
                    inherited_type=effective_type,
                    stack=next_stack,
                )
                continue
            raise GlobalWaterwayPackageError(
                f"relation {relation_id} member type {kind} is unsupported"
            )
    finally:
        rows.close()


def iter_exact_waterway_features(
    database_path: Path,
    *,
    source_binding: WaterwaySourceBinding,
):
    """Yield selected roots only, with exact recursive source geometry and text."""

    from .osm_global_waterway_renderer import ExactWaterwayFeature

    if not isinstance(database_path, Path) or not database_path.is_file() or database_path.is_symlink():
        raise GlobalWaterwayPackageError("waterway database is not one regular non-link file")
    if not isinstance(source_binding, WaterwaySourceBinding):
        raise GlobalWaterwayPackageError("waterway feature source binding is invalid")
    connection = sqlite3.connect(f"file:{database_path}?mode=ro", uri=True)
    try:
        run_identity = _meta_get(connection, "runIdentity")
        checkpoint = _meta_get(connection, "checkpoint")
        if not isinstance(run_identity, dict) or run_identity.get("source") != source_binding.document():
            raise GlobalWaterwayPackageError("waterway database source binding differs")
        if not isinstance(checkpoint, dict) or checkpoint.get("ingestComplete") is not True:
            raise GlobalWaterwayPackageError("waterway database ingest is incomplete")
        def root_rows():
            previous_kind = -1
            previous_id = 0
            while True:
                cursor = connection.execute(
                    "SELECT kind,id FROM roots "
                    "WHERE kind>? OR (kind=? AND id>?) ORDER BY kind,id LIMIT 1",
                    (previous_kind, previous_kind, previous_id),
                )
                row = cursor.fetchone()
                cursor.close()
                if row is None:
                    return
                previous_kind = int(row[0])
                previous_id = int(row[1])
                yield previous_kind, previous_id

        for kind, source_id in root_rows():
            kind = int(kind)
            source_id = int(source_id)
            if kind == 1:
                evidence = _DependencyEvidence()
                version, timestamp, tags, _, points = _way_source(
                    connection, source_id, evidence
                )
                waterway_type = dict(tags).get("waterway")
                if waterway_type not in _ALLOWED_WATERWAYS:
                    raise GlobalWaterwayPackageError(
                        f"selected way {source_id} lacks one exact supported waterway type"
                    )
                name_key, primary, english = _source_names(tags)
                parts = (points,)
                identity = _feature_identity(
                    source_binding=source_binding,
                    source_kind="way",
                    source_id=source_id,
                    source_version=version,
                    source_timestamp=timestamp,
                    waterway_type=waterway_type,
                    name_source_key=name_key,
                    primary_name=primary,
                    english_name=english,
                    parts=parts,
                    required_node_ids=frozenset(),
                    dependency_evidence=evidence.document(),
                )
                yield ExactWaterwayFeature(
                    "way",
                    source_id,
                    version,
                    timestamp,
                    waterway_type,
                    name_key,
                    primary,
                    english,
                    False,
                    parts,
                    frozenset(),
                    identity,
                )
                continue
            row = connection.execute(
                "SELECT version,timestamp,tags FROM relations WHERE id=?", (source_id,)
            ).fetchone()
            if row is None:
                raise GlobalWaterwayPackageError(
                    f"selected relation {source_id} is absent from closure"
                )
            version, timestamp, raw_tags = row
            tags = _decode_tags(bytes(raw_tags), f"relation {source_id}")
            name_key, primary, english = _source_names(tags)
            evidence = _DependencyEvidence()
            policy = _relation_geometry_policy_document()
            geometry_budget = _RelationGeometryBudget(
                int(policy["maxDepth"]),
                int(policy["maxRelationVisits"]),
                int(policy["maxParts"]),
                int(policy["maxPoints"]),
            )
            parts_by_type: dict[str, list[list[object]]] = {}
            required_by_type: dict[str, set[int]] = {}
            type_order: list[str] = []
            previous_type: str | None = None
            has_occurrences = False
            for waterway_type, points in _relation_occurrences(
                connection,
                source_id,
                evidence,
                geometry_budget=geometry_budget,
            ):
                has_occurrences = True
                if waterway_type not in parts_by_type:
                    parts_by_type[waterway_type] = []
                    required_by_type[waterway_type] = set()
                    type_order.append(waterway_type)
                typed_parts = parts_by_type[waterway_type]
                if (
                    previous_type == waterway_type
                    and typed_parts
                    and typed_parts[-1][-1].node_id == points[0].node_id
                ):
                    required_by_type[waterway_type].add(points[0].node_id)
                    typed_parts[-1].extend(points[1:])
                else:
                    typed_parts.append(list(points))
                previous_type = waterway_type
            if not has_occurrences:
                raise GlobalWaterwayPackageError(
                    f"selected relation {source_id} contains no exact path geometry"
                )
            dependency_evidence = evidence.document()
            for waterway_type in type_order:
                parts = tuple(tuple(part) for part in parts_by_type[waterway_type])
                identity = _feature_identity(
                    source_binding=source_binding,
                    source_kind="relation",
                    source_id=source_id,
                    source_version=int(version),
                    source_timestamp=str(timestamp),
                    waterway_type=waterway_type,
                    name_source_key=name_key,
                    primary_name=primary,
                    english_name=english,
                    parts=parts,
                    required_node_ids=frozenset(required_by_type[waterway_type]),
                    dependency_evidence=dependency_evidence,
                )
                yield ExactWaterwayFeature(
                    "relation",
                    source_id,
                    int(version),
                    str(timestamp),
                    waterway_type,
                    name_key,
                    primary,
                    english,
                    True,
                    parts,
                    frozenset(required_by_type[waterway_type]),
                    identity,
                )
    finally:
        connection.close()


def ingest_global_waterway_closure(
    *,
    opl_path: Path,
    root_ids_path: Path,
    work_directory: Path,
    source_binding: WaterwaySourceBinding,
    checkpoint_objects: int = 10_000,
    pause_after_objects: int | None = None,
) -> GlobalWaterwayIngestResult:
    """Stream one verified recursive closure into a resumable SQLite source store."""

    if not all(isinstance(path, Path) for path in (opl_path, root_ids_path, work_directory)):
        raise GlobalWaterwayPackageError("waterway ingest paths must be pathlib.Path values")
    if not isinstance(source_binding, WaterwaySourceBinding):
        raise GlobalWaterwayPackageError("waterway source binding is invalid")
    if source_binding.selection_policy_sha256 != GLOBAL_POLICY_SHA256:
        raise GlobalWaterwayPackageError("selection policy identity differs from global policy")
    if type(checkpoint_objects) is not int or checkpoint_objects <= 0:
        raise GlobalWaterwayPackageError("checkpoint object count must be positive")
    if pause_after_objects is not None and (
        type(pause_after_objects) is not int or pause_after_objects <= 0
    ):
        raise GlobalWaterwayPackageError("pause object count must be positive")
    _require_identity(
        root_ids_path,
        expected_bytes=source_binding.root_ids_bytes,
        expected_sha256=source_binding.root_ids_sha256,
        label="root-ID file",
    )
    _require_identity(
        opl_path,
        expected_bytes=source_binding.closure_opl_bytes,
        expected_sha256=source_binding.closure_opl_sha256,
        label="closure OPL",
    )
    run_identity = _run_identity(source_binding, checkpoint_objects)
    database_path = work_directory / "waterway-state.sqlite"
    connection, created = _open_database(database_path, run_identity)
    try:
        if created:
            _insert_roots(connection, root_ids_path)
        else:
            _validate_root_table(connection, root_ids_path)
        complete = _ingest(
            connection,
            database_path,
            opl_path=opl_path,
            source_binding=source_binding,
            checkpoint_objects=checkpoint_objects,
            pause_after_objects=pause_after_objects,
            authenticate_prefix=not created,
        )
        run_sha256 = hashlib.sha256(_canonical_json_bytes(run_identity)).hexdigest()
        if not complete:
            return GlobalWaterwayIngestResult(
                "paused",
                database_path,
                MappingProxyType(
                    {
                        "checkpoint": _meta_get(connection, "checkpoint"),
                        "runIdentitySha256": run_sha256,
                        "schema": _INGEST_SCHEMA,
                        "state": "paused",
                    }
                ),
            )
        audit = _validate_closure(connection)
        semantic_sha256 = _semantic_sha256(connection)
        peaks = dict(_meta_get(connection, "peaks") or {})
        receipt = {
            "checkpoint": _meta_get(connection, "checkpoint"),
            "closureAudit": audit,
            "peakResources": {
                **peaks,
                "measurementScope": {
                    "persistentSqliteSampling": (
                        "database and visible journal/WAL/SHM files after checkpoint commits"
                    ),
                    "processPeakRssMeasured": False,
                    "transientSqliteTempFilesIncluded": False,
                },
                "sqliteCacheTargetBytes": 64 * 1024 * 1024,
            },
            "runIdentity": run_identity,
            "runIdentitySha256": run_sha256,
            "schema": _INGEST_SCHEMA,
            "semanticSha256": semantic_sha256,
            "source": source_binding.document(),
            "state": "complete",
        }
        _meta_set(connection, "ingestReceipt", receipt)
        connection.commit()
        return GlobalWaterwayIngestResult(
            "complete", database_path, MappingProxyType(receipt)
        )
    finally:
        connection.close()


def _render_code_identities() -> dict[str, object]:
    from . import (
        osm_global_waterway_renderer,
        reference_presentation_policy,
        renderer_tile_package,
        semantic_model,
        sourced_text,
    )

    modules = {
        "presentationPolicy": reference_presentation_policy,
        "renderer": osm_global_waterway_renderer,
        "rendererTilePackage": renderer_tile_package,
        "semanticModel": semantic_model,
        "source": source_module,
        "sourcedText": sourced_text,
        "store": __import__(__name__, fromlist=["*"]),
    }
    return {
        name: _stream_identity(Path(module.__file__).resolve(), f"{name} code")
        for name, module in sorted(modules.items())
    }


def _render_run_identity(
    *,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    zooms: tuple[int, ...],
    checkpoint_features: int,
    code_identities: Mapping[str, object],
    classifier_sha256: str,
) -> dict[str, object]:
    return {
        "checkpointFeatures": checkpoint_features,
        "classifierSha256": classifier_sha256,
        "code": dict(code_identities),
        "packageId": package_id,
        "relationGeometryPolicy": _relation_geometry_policy_document(),
        "runtime": _runtime_identity_document(),
        "schema": _RENDER_RUN_SCHEMA,
        "source": source_binding.document(),
        "zooms": list(zooms),
    }


def _record_envelope(record: object, tile: TileKey) -> bytes:
    renderer = record.renderer_record
    sourced = record.sourced_text
    if renderer.posting.requested_tile != tile or sourced is None:
        raise GlobalWaterwayPackageError(
            "waterway posting cannot form one canonical label envelope"
        )
    renderer_bytes = renderer_record_bytes(renderer)
    sourced_bytes = sourced.canonical_bytes
    if not 0 < len(renderer_bytes) <= MAX_RENDERER_RECORD_BYTES:
        raise GlobalWaterwayPackageError(
            "waterway renderer record byte length is outside its bound"
        )
    if not 0 < len(sourced_bytes) <= MAX_SOURCED_TEXT_RECORD_BYTES:
        raise GlobalWaterwayPackageError(
            "waterway sourced-text byte length is outside its bound"
        )
    return b"".join(
        (
            struct.pack("<I", len(renderer_bytes)),
            renderer_bytes,
            struct.pack("<I", len(sourced_bytes)),
            sourced.full_sha256,
            sourced_bytes,
        )
    )


def _stage_rendered_feature(
    connection: sqlite3.Connection,
    *,
    ordinal: int,
    exact: object,
    rendered: object,
    peaks: dict[str, int],
) -> None:
    feature_hot = exact.source_feature_sha256[:8]
    _insert_hot_identity(
        connection, "feature_ids", feature_hot, exact.source_feature_sha256
    )
    posting_bytes = 0
    for tile, records in rendered.tiles.items():
        if not records:
            raise GlobalWaterwayPackageError("waterway feature emitted an empty tile group")
        for record in records:
            renderer = record.renderer_record
            variant = renderer.variant
            posting = renderer.posting
            sourced = record.sourced_text
            if sourced is None:
                raise GlobalWaterwayPackageError("waterway label lacks exact sourced text")
            if posting.feature_id.to_bytes(8, "big") != feature_hot:
                raise GlobalWaterwayPackageError(
                    "waterway renderer feature ID differs from exact source identity"
                )
            geometry_hot = variant.geometry_id.to_bytes(8, "big")
            geometry_sha = variant.placement.placement_geometry_sha256
            label_hot = variant.placement.label_candidate_id.to_bytes(8, "big")
            label_sha = variant.placement.label_candidate_sha256
            variant_hot = variant.canonical_variant_id.to_bytes(8, "big")
            variant_sha = variant_fingerprint(variant).full_sha256
            sourced_hot = sourced.hot_id.to_bytes(8, "big")
            _insert_hot_identity(connection, "geometry_ids", geometry_hot, geometry_sha)
            _insert_hot_identity(connection, "label_ids", label_hot, label_sha)
            _insert_hot_identity(connection, "variant_ids", variant_hot, variant_sha)
            _insert_hot_identity(
                connection, "sourced_ids", sourced_hot, sourced.full_sha256
            )
            envelope = _record_envelope(record, tile)
            posting_key = struct.pack(
                ">QQQi",
                posting.feature_id,
                posting.canonical_variant_id,
                posting.owner_tile.packed,
                posting.world_wrap,
            )
            try:
                connection.execute(
                    "INSERT INTO records("
                    "z,y,x,posting_key,draw_order,priority,layer_group,feature_kind,"
                    "variant_id,feature_id,sourced_sha,envelope,subtype,source_type"
                    ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        tile.z,
                        tile.y,
                        tile.x,
                        posting_key,
                        variant.draw_order,
                        variant.priority,
                        variant.layer_group.value,
                        variant.feature_kind.value,
                        variant_hot,
                        feature_hot,
                        sourced.full_sha256,
                        envelope,
                        variant.semantic_subtype,
                        exact.waterway_type,
                    ),
                )
            except sqlite3.IntegrityError as error:
                raise GlobalWaterwayPackageError(
                    "duplicate global waterway renderer posting"
                ) from error
            posting_bytes += len(envelope)
    try:
        connection.execute(
            "INSERT INTO rendered_features(ordinal,source_kind,source_id,source_type,feature_sha) "
            "VALUES (?,?,?,?,?)",
            (
                ordinal,
                exact.source_kind,
                exact.source_id,
                exact.waterway_type,
                exact.source_feature_sha256,
            ),
        )
    except sqlite3.IntegrityError as error:
        raise GlobalWaterwayPackageError(
            "duplicate exact waterway feature in render traversal"
        ) from error
    peaks["featurePostingBytes"] = max(peaks["featurePostingBytes"], posting_bytes)


def _expected_rendered_feature_rows(exact: object, rendered: object) -> tuple[tuple[object, ...], ...]:
    feature_hot = exact.source_feature_sha256[:8]
    rows: list[tuple[object, ...]] = []
    for tile, records in rendered.tiles.items():
        for record in records:
            renderer = record.renderer_record
            variant = renderer.variant
            posting = renderer.posting
            sourced = record.sourced_text
            if sourced is None or posting.feature_id.to_bytes(8, "big") != feature_hot:
                raise GlobalWaterwayPackageError(
                    "waterway rendered prefix has invalid feature identity"
                )
            posting_key = struct.pack(
                ">QQQi",
                posting.feature_id,
                posting.canonical_variant_id,
                posting.owner_tile.packed,
                posting.world_wrap,
            )
            rows.append(
                (
                    tile.z,
                    tile.y,
                    tile.x,
                    posting_key,
                    variant.draw_order,
                    variant.priority,
                    variant.layer_group.value,
                    variant.feature_kind.value,
                    variant.canonical_variant_id.to_bytes(8, "big"),
                    feature_hot,
                    sourced.full_sha256,
                    _record_envelope(record, tile),
                    variant.semantic_subtype,
                    exact.waterway_type,
                )
            )
    return tuple(sorted(rows, key=lambda row: (row[0], row[1], row[2], row[3])))


def _validate_rendered_feature_prefix(
    connection: sqlite3.Connection,
    exact: object,
    rendered: object,
) -> None:
    expected = _expected_rendered_feature_rows(exact, rendered)
    actual = tuple(
        connection.execute(
            "SELECT z,y,x,posting_key,draw_order,priority,layer_group,feature_kind,"
            "variant_id,feature_id,sourced_sha,envelope,subtype,source_type "
            "FROM records WHERE feature_id=? ORDER BY z,y,x,posting_key",
            (exact.source_feature_sha256[:8],),
        )
    )
    normalized = tuple(
        (
            int(row[0]),
            int(row[1]),
            int(row[2]),
            bytes(row[3]),
            int(row[4]),
            int(row[5]),
            int(row[6]),
            int(row[7]),
            bytes(row[8]),
            bytes(row[9]),
            bytes(row[10]),
            bytes(row[11]),
            int(row[12]),
            str(row[13]),
        )
        for row in actual
    )
    if normalized != expected:
        raise GlobalWaterwayPackageError(
            "checkpointed rendered feature prefix differs from exact rerender"
        )


def _commit_render_checkpoint(
    connection: sqlite3.Connection,
    database_path: Path,
    checkpoint: Mapping[str, object],
    peaks: dict[str, int],
) -> None:
    _meta_set(connection, "renderCheckpoint", dict(checkpoint))
    _meta_set(connection, "peaks", peaks)
    connection.commit()
    peaks["observedPersistentSqliteBytesAtCheckpoints"] = max(
        peaks["observedPersistentSqliteBytesAtCheckpoints"],
        _database_bytes(database_path),
    )
    _meta_set(connection, "peaks", peaks)
    connection.commit()


def _stage_renderer_records(
    connection: sqlite3.Connection,
    database_path: Path,
    *,
    source_binding: WaterwaySourceBinding,
    zooms: tuple[int, ...],
    checkpoint_features: int,
    pause_after_features: int | None,
    run_identity: Mapping[str, object],
) -> bool:
    from .osm_global_waterway_renderer import (
        build_adaptive_waterway_feature,
    )
    from .semantic_model import HotIdRegistry

    stored_identity = _meta_get(connection, "renderRunIdentity")
    if stored_identity is None:
        _meta_set(connection, "renderRunIdentity", dict(run_identity))
        _meta_set(
            connection,
            "renderCheckpoint",
            {"renderComplete": False, "renderedFeatures": 0},
        )
        connection.commit()
    elif stored_identity != dict(run_identity):
        raise GlobalWaterwayPackageError(
            "renderer checkpoint identity differs from exact source/config/code identity"
        )
    checkpoint = dict(_meta_get(connection, "renderCheckpoint") or {})
    rendered_prefix = int(checkpoint.get("renderedFeatures", 0))
    stored_feature_count = int(
        connection.execute("SELECT COUNT(*) FROM rendered_features").fetchone()[0]
    )
    orphan_record = connection.execute(
        "SELECT 1 FROM records r LEFT JOIN rendered_features f "
        "ON r.feature_id=substr(f.feature_sha,1,8) "
        "WHERE f.ordinal IS NULL LIMIT 1"
    ).fetchone()
    if stored_feature_count != rendered_prefix or orphan_record is not None:
        raise GlobalWaterwayPackageError(
            "renderer checkpointed feature inventory differs"
        )
    peaks = dict(_meta_get(connection, "peaks") or {})
    for key in (
        "compressedTileBytes",
        "featurePostingBytes",
        "inputLineBytes",
        "observedPersistentSqliteBytesAtCheckpoints",
        "rawTileBytes",
        "recordsPerTile",
    ):
        peaks.setdefault(key, 0)
    registry = HotIdRegistry()
    since_commit = 0
    seen = 0
    for ordinal, exact in enumerate(
        iter_exact_waterway_features(database_path, source_binding=source_binding)
    ):
        seen = ordinal + 1
        rendered = build_adaptive_waterway_feature(
            feature=exact,
            source_generation_sha256=source_binding.planet_sha256,
            classifier_sha256=str(run_identity["classifierSha256"]),
            zooms=zooms,
            identity_registry=registry,
        )
        if ordinal < rendered_prefix:
            row = connection.execute(
                "SELECT source_kind,source_id,source_type,feature_sha "
                "FROM rendered_features WHERE ordinal=?",
                (ordinal,),
            ).fetchone()
            expected = (
                exact.source_kind,
                exact.source_id,
                exact.waterway_type,
                exact.source_feature_sha256,
            )
            if row is None or (
                str(row[0]), int(row[1]), str(row[2]), bytes(row[3])
            ) != expected:
                raise GlobalWaterwayPackageError(
                    "renderer checkpointed feature prefix differs"
                )
            _validate_rendered_feature_prefix(connection, exact, rendered)
            continue
        _stage_rendered_feature(
            connection,
            ordinal=ordinal,
            exact=exact,
            rendered=rendered,
            peaks=peaks,
        )
        checkpoint = {"renderComplete": False, "renderedFeatures": ordinal + 1}
        since_commit += 1
        if since_commit >= checkpoint_features:
            _commit_render_checkpoint(
                connection, database_path, checkpoint, peaks
            )
            since_commit = 0
        if pause_after_features is not None and ordinal + 1 >= pause_after_features:
            _commit_render_checkpoint(
                connection, database_path, checkpoint, peaks
            )
            return False
    if seen < rendered_prefix:
        raise GlobalWaterwayPackageError("renderer feature traversal ended before checkpoint")
    checkpoint = {"renderComplete": True, "renderedFeatures": seen}
    _commit_render_checkpoint(connection, database_path, checkpoint, peaks)
    return True


def _windows(connection: sqlite3.Connection) -> tuple[_Window, ...]:
    rows = connection.execute(
        "SELECT z,MIN(x),MAX(x),MIN(y),MAX(y) FROM records GROUP BY z ORDER BY z"
    ).fetchall()
    if not rows:
        raise GlobalWaterwayPackageError(
            "no source-valid waterway labels reached the requested zooms"
        )
    windows = tuple(_Window(*(int(value) for value in row)) for row in rows)
    index_bytes = sum(window.tile_count for window in windows) * INDEX_ENTRY_BYTES
    if index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise GlobalWaterwayPackageError(
            "global waterway Android index exceeds its byte-array bound"
        )
    enforce_global_waterway_storage_ceiling(index_bytes)
    return windows


def _tiles_index_order(windows: Sequence[_Window]):
    for window in windows:
        for y in range(window.y_min, window.y_max + 1):
            for x in range(window.x_min, window.x_max + 1):
                yield TileKey(window.z, x, y)


def _tile_ordinal(windows: Sequence[_Window], tile: TileKey) -> int:
    first = 0
    for window in windows:
        if window.z == tile.z:
            if not (
                window.x_min <= tile.x <= window.x_max
                and window.y_min <= tile.y <= window.y_max
            ):
                raise GlobalWaterwayPackageError(
                    "staged waterway posting lies outside its zoom window"
                )
            return (
                first
                + (tile.y - window.y_min) * (window.x_max - window.x_min + 1)
                + tile.x
                - window.x_min
            )
        first += window.tile_count
    raise GlobalWaterwayPackageError("staged waterway posting has an undeclared zoom")


def _renderer_from_envelope(envelope: bytes) -> bytes:
    if len(envelope) < 4:
        raise GlobalWaterwayPackageError("staged waterway envelope is truncated")
    renderer_length = struct.unpack_from("<I", envelope, 0)[0]
    if not 0 < renderer_length <= MAX_RENDERER_RECORD_BYTES:
        raise GlobalWaterwayPackageError(
            "staged waterway renderer envelope length is invalid"
        )
    end = 4 + renderer_length
    if end > len(envelope):
        raise GlobalWaterwayPackageError("staged waterway renderer envelope ends early")
    return envelope[4:end]


def _row_order(row: Sequence[object]) -> tuple[object, ...]:
    return (
        int(row[3]),
        int(row[4]),
        int(row[5]),
        int(row[6]),
        bytes(row[7]),
        bytes(row[8]),
        _renderer_from_envelope(bytes(row[10])),
        bytes(row[9]),
    )


def _record_rows(
    connection: sqlite3.Connection,
    *,
    semantic_order: bool,
):
    coordinate_order = "z,x,y" if semantic_order else "z,y,x"
    yield from connection.execute(
        "SELECT z,y,x,draw_order,priority,layer_group,feature_kind,"
        "variant_id,feature_id,sourced_sha,envelope,subtype,source_type "
        f"FROM records ORDER BY {coordinate_order}"
    )


def _group_rows(rows):
    current_tile: TileKey | None = None
    current: list[Sequence[object]] = []
    current_raw_bytes = _TILE_HEADER_BYTES
    for row in rows:
        tile = TileKey(int(row[0]), int(row[2]), int(row[1]))
        if current_tile is not None and tile != current_tile:
            current.sort(key=_row_order)
            yield current_tile, current
            current = []
            current_raw_bytes = _TILE_HEADER_BYTES
        current_tile = tile
        current_raw_bytes += len(bytes(row[10]))
        if current_raw_bytes > MAX_TILE_BYTES:
            raise GlobalWaterwayPackageError(
                "waterway tile raw bytes exceed Android bound before materialization"
            )
        current.append(row)
        if len(current) > MAX_RECORDS_PER_TILE:
            raise GlobalWaterwayPackageError(
                "waterway tile record count exceeds Android bound"
            )
    if current_tile is not None:
        current.sort(key=_row_order)
        yield current_tile, current


def _renderer_semantic_sha256(connection: sqlite3.Connection) -> str:
    digest = hashlib.sha256(_SEMANTIC_STREAM_DOMAIN)
    for tile, rows in _group_rows(_record_rows(connection, semantic_order=True)):
        for row in rows:
            body = struct.pack("<Q", tile.packed) + _renderer_from_envelope(
                bytes(row[10])
            )
            digest.update(struct.pack("<I", len(body)))
            digest.update(body)
    return digest.hexdigest()


def _payload(tile: TileKey, rows: Sequence[Sequence[object]]) -> bytes:
    payload = b"".join(
        (
            TILE_PAYLOAD_MAGIC,
            struct.pack("<BIII", tile.z, tile.x, tile.y, len(rows)),
            *(bytes(row[10]) for row in rows),
        )
    )
    if len(payload) > MAX_TILE_BYTES:
        raise GlobalWaterwayPackageError("waterway tile raw bytes exceed Android bound")
    try:
        decoded = decode_tile_payload(tile, payload)
    except ValueError as error:
        raise GlobalWaterwayPackageError(
            f"waterway tile {tile.z}/{tile.x}/{tile.y} fails Android codec parity"
        ) from error
    if len(decoded.records) != len(rows):
        raise GlobalWaterwayPackageError("waterway tile Android decode count differs")
    return payload


def _ensure_owned_partial_directory(
    connection: sqlite3.Connection,
    partial_directory: Path,
) -> None:
    expected = {
        "path": os.path.abspath(partial_directory),
        "schema": _PARTIAL_OWNER_SCHEMA,
    }
    owner = _meta_get(connection, "partialDirectoryOwner")
    exists = partial_directory.exists() or partial_directory.is_symlink()
    if owner is None:
        if exists:
            raise GlobalWaterwayPackageError(
                "partial directory lacks this checkpoint database ownership"
            )
        partial_directory.mkdir(parents=True, exist_ok=False)
        created_status = os.lstat(partial_directory)
        if (
            not stat.S_ISDIR(created_status.st_mode)
            or getattr(created_status, "st_file_attributes", 0) & _REPARSE_POINT
        ):
            raise GlobalWaterwayPackageError(
                "partial directory ownership target is redirected"
            )
        _meta_set(connection, "partialDirectoryOwner", expected)
        connection.commit()
        return
    if owner != expected:
        raise GlobalWaterwayPackageError("partial directory ownership identity differs")
    current_status = os.lstat(partial_directory) if exists else None
    if (
        current_status is None
        or not stat.S_ISDIR(current_status.st_mode)
        or getattr(current_status, "st_file_attributes", 0) & _REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError("owned partial directory is missing or redirected")


def _open_verified_runtime_file(
    path: Path,
    *,
    prefix_bytes: int,
    prefix_sha256: object,
    label: str,
):
    if (
        type(prefix_bytes) is not int
        or prefix_bytes < 0
        or type(prefix_sha256) is not str
        or len(prefix_sha256) != 64
        or any(character not in "0123456789abcdef" for character in prefix_sha256)
    ):
        raise GlobalWaterwayPackageError(
            f"runtime checkpoint {label} prefix is malformed"
        )
    exists = path.exists() or path.is_symlink()
    if exists:
        status = os.lstat(path)
        if (
            not stat.S_ISREG(status.st_mode)
            or getattr(status, "st_file_attributes", 0) & _REPARSE_POINT
        ):
            raise GlobalWaterwayPackageError(
                f"runtime checkpoint {label} is not one regular non-link file"
            )
    if not exists and prefix_bytes:
        raise GlobalWaterwayPackageError(f"runtime checkpoint {label} file is missing")
    handle = path.open("r+b" if exists else "x+b")
    try:
        handle_status = os.fstat(handle.fileno())
        path_status = path.stat()
        if (
            (handle_status.st_dev, handle_status.st_ino)
            != (path_status.st_dev, path_status.st_ino)
            or handle_status.st_size < prefix_bytes
        ):
            raise GlobalWaterwayPackageError(
                f"runtime checkpoint {label} prefix length or identity differs"
            )
        digest = hashlib.sha256()
        remaining = prefix_bytes
        while remaining:
            chunk = handle.read(min(1024 * 1024, remaining))
            if not chunk:
                raise GlobalWaterwayPackageError(
                    f"runtime checkpoint {label} prefix ends early"
                )
            digest.update(chunk)
            remaining -= len(chunk)
        if digest.hexdigest() != prefix_sha256:
            raise GlobalWaterwayPackageError(
                f"runtime checkpoint {label} prefix SHA-256 differs"
            )
        handle.truncate(prefix_bytes)
        handle.seek(prefix_bytes)
        return handle, digest
    except BaseException:
        handle.close()
        raise


def _build_runtime_files(
    connection: sqlite3.Connection,
    database_path: Path,
    partial_directory: Path,
    windows: Sequence[_Window],
) -> tuple[int, int, dict[str, int]]:
    _ensure_owned_partial_directory(connection, partial_directory)
    records_path = partial_directory / "records.fadictpack"
    index_path = partial_directory / "tile-index.bin"
    checkpoint = dict(
        _meta_get(connection, "buildCheckpoint")
        or {
            "indexBytes": 0,
            "indexSha256": hashlib.sha256(b"").hexdigest(),
            "nextOrdinal": 0,
            "recordsBytes": 0,
            "recordsSha256": hashlib.sha256(b"").hexdigest(),
        }
    )
    expected_keys = {
        "indexBytes",
        "indexSha256",
        "nextOrdinal",
        "recordsBytes",
        "recordsSha256",
    }
    if set(checkpoint) != expected_keys:
        raise GlobalWaterwayPackageError("runtime build checkpoint fields differ")
    next_ordinal = int(checkpoint["nextOrdinal"])
    records_bytes = int(checkpoint["recordsBytes"])
    index_bytes = int(checkpoint["indexBytes"])
    if index_bytes != next_ordinal * INDEX_ENTRY_BYTES:
        raise GlobalWaterwayPackageError("runtime index checkpoint offset is inconsistent")
    expected_total = sum(window.tile_count for window in windows)
    expected_index_bytes = expected_total * INDEX_ENTRY_BYTES
    enforce_global_waterway_storage_ceiling(expected_index_bytes + records_bytes)
    present_tiles = int(
        connection.execute(
            "SELECT COUNT(*) FROM (SELECT 1 FROM records GROUP BY z,x,y)"
        ).fetchone()[0]
    )
    peaks = dict(_meta_get(connection, "peaks") or {})
    for key in (
        "compressedTileBytes",
        "featurePostingBytes",
        "inputLineBytes",
        "observedPersistentSqliteBytesAtCheckpoints",
        "rawTileBytes",
        "recordsPerTile",
    ):
        peaks.setdefault(key, 0)
    grouped = iter(_group_rows(_record_rows(connection, semantic_order=False)))
    staged = next(grouped, None)
    while staged is not None and _tile_ordinal(windows, staged[0]) < next_ordinal:
        staged = next(grouped, None)
    with ExitStack() as stack:
        records_handle, records_digest = _open_verified_runtime_file(
            records_path,
            prefix_bytes=records_bytes,
            prefix_sha256=checkpoint["recordsSha256"],
            label="records",
        )
        stack.callback(records_handle.close)
        index_handle, index_digest = _open_verified_runtime_file(
            index_path,
            prefix_bytes=index_bytes,
            prefix_sha256=checkpoint["indexSha256"],
            label="index",
        )
        stack.callback(index_handle.close)
        for ordinal, tile in enumerate(_tiles_index_order(windows)):
            if ordinal < next_ordinal:
                continue
            if staged is None or staged[0] != tile:
                entry = _ZERO_INDEX_ENTRY
            else:
                rows = staged[1]
                payload = _payload(tile, rows)
                compressed = raw_deflate(payload)
                if len(compressed) > _MAX_COMPRESSED_TILE_BYTES:
                    raise GlobalWaterwayPackageError(
                        "waterway tile compressed bytes exceed Android bound"
                    )
                enforce_global_waterway_storage_ceiling(
                    expected_index_bytes + records_bytes + len(compressed)
                )
                entry = encode_index_entry(
                    offset=records_bytes,
                    compressed_length=len(compressed),
                    raw_length=len(payload),
                    raw_hash32=raw_hash32(payload),
                    flags=INDEX_FLAG_PRESENT,
                )
                records_handle.write(compressed)
                records_digest.update(compressed)
                records_bytes += len(compressed)
                peaks["recordsPerTile"] = max(peaks["recordsPerTile"], len(rows))
                peaks["rawTileBytes"] = max(peaks["rawTileBytes"], len(payload))
                peaks["compressedTileBytes"] = max(
                    peaks["compressedTileBytes"], len(compressed)
                )
                staged = next(grouped, None)
            index_handle.write(entry)
            index_digest.update(entry)
            index_bytes += INDEX_ENTRY_BYTES
            if (ordinal + 1) % 4096 == 0:
                records_handle.flush()
                index_handle.flush()
                os.fsync(records_handle.fileno())
                os.fsync(index_handle.fileno())
                _meta_set(
                    connection,
                    "buildCheckpoint",
                    {
                        "indexBytes": index_bytes,
                        "indexSha256": index_digest.hexdigest(),
                        "nextOrdinal": ordinal + 1,
                        "recordsBytes": records_bytes,
                        "recordsSha256": records_digest.hexdigest(),
                    },
                )
                _meta_set(connection, "peaks", peaks)
                connection.commit()
        if staged is not None:
            raise GlobalWaterwayPackageError(
                "staged waterway records remain outside package traversal"
            )
        records_handle.flush()
        index_handle.flush()
        os.fsync(records_handle.fileno())
        os.fsync(index_handle.fileno())
    if index_bytes != expected_index_bytes:
        raise GlobalWaterwayPackageError("waterway index length differs from coverage")
    if records_path.stat().st_size != records_bytes or index_path.stat().st_size != index_bytes:
        raise GlobalWaterwayPackageError("waterway runtime file length differs after flush")
    peaks["observedPersistentSqliteBytesAtCheckpoints"] = max(
        peaks["observedPersistentSqliteBytesAtCheckpoints"],
        _database_bytes(database_path),
    )
    _meta_set(
        connection,
        "buildCheckpoint",
        {
            "indexBytes": index_bytes,
            "indexSha256": index_digest.hexdigest(),
            "nextOrdinal": expected_total,
            "recordsBytes": records_bytes,
            "recordsSha256": records_digest.hexdigest(),
        },
    )
    _meta_set(connection, "peaks", peaks)
    connection.commit()
    return records_bytes, present_tiles, peaks


def _projection_stats(
    connection: sqlite3.Connection,
    windows: Sequence[_Window],
) -> dict[str, object]:
    by_zoom = []
    for window in windows:
        postings = int(
            connection.execute(
                "SELECT COUNT(*) FROM records WHERE z=?", (window.z,)
            ).fetchone()[0]
        )
        present = int(
            connection.execute(
                "SELECT COUNT(*) FROM (SELECT 1 FROM records WHERE z=? GROUP BY x,y)",
                (window.z,),
            ).fetchone()[0]
        )
        by_zoom.append(
            {
                "declaredIndexEntries": window.tile_count,
                "postings": postings,
                "presentTiles": present,
                "xMax": window.x_max,
                "xMin": window.x_min,
                "yMax": window.y_max,
                "yMin": window.y_min,
                "z": window.z,
            }
        )
    source_types = [
        {"features": int(count), "waterwayType": str(source_type)}
        for source_type, count in connection.execute(
            "SELECT source_type,COUNT(*) FROM rendered_features "
            "GROUP BY source_type ORDER BY source_type"
        )
    ]
    return {
        "byZoom": by_zoom,
        "projection": "EPSG:3857-slippy-tile",
        "sourceTypeFeatures": source_types,
    }


def _write_synced(path: Path, raw: bytes) -> None:
    with path.open("wb") as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())


def _output_file(path: Path) -> dict[str, object]:
    identity = _stream_identity(path, path.name)
    return {
        "bytes": identity["bytes"],
        "name": path.name,
        "sha256": identity["sha256"],
    }


def _manifest_document(
    *,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    windows: Sequence[_Window],
    semantic_sha256: str,
    classifier_sha256: str,
    run_identity_sha256: str,
    records_identity: Mapping[str, object],
    index_identity: Mapping[str, object],
) -> dict[str, object]:
    total_tiles = sum(window.tile_count for window in windows)
    return {
        "compatibility": {"emptyPresentTilesSharePayload": False},
        "coverage": {
            "completeDeclaredScope": False,
            "completeWholeEarthDictionary": False,
            "tileCount": total_tiles,
            "zoomRanges": [window.document() for window in windows],
        },
        "globalWaterwaySupplement": {
            "adaptiveGeometry": {
                "errorBound": "one-half-pixel-at-record-integer-zoom",
                "pathScope": "complete-source-path",
                "sourceEndpointsPreserved": True,
            },
            "attribution": {
                "buildMethod": (
                    "Exact selected roots, pinned osmium recursive closure, and "
                    "deterministic V3 adaptive-path cook"
                ),
                "copyrightUrl": "https://www.openstreetmap.org/copyright",
                "credit": "OpenStreetMap contributors",
                "databaseNotice": (
                    "Contains information from OpenStreetMap, available under ODbL 1.0"
                ),
                "databaseLicense": "ODbL-1.0",
                "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
                "sourceOffer": (
                    "Reproducible transformation inputs and exact extraction receipt "
                    "are bound by SHA-256"
                ),
            },
            "buildSchema": _BUILD_SCHEMA,
            "classifierSha256": classifier_sha256,
            "fixtureOnly": source_binding.planet_path.startswith("fixture://"),
            "records": dict(records_identity),
            "requestedZooms": list(_DEFAULT_ZOOMS),
            "rootPolicySha256": source_binding.selection_policy_sha256,
            "runIdentitySha256": run_identity_sha256,
            "selectionManifestSha256": source_binding.selection_manifest_sha256,
            "source": source_binding.document(),
            "tileIndex": dict(index_identity),
            "typedWaterways": {
                "canal": {"filterId": "labels.canals", "semanticSubtype": 350},
                "river": {"filterId": "labels.rivers", "semanticSubtype": 330},
                "stream": {"filterId": "labels.streams", "semanticSubtype": 340},
                "tidal_channel": {
                    "filterId": "labels.canals",
                    "semanticSubtype": 350,
                },
                "wadi": {"filterId": "labels.streams", "semanticSubtype": 360},
            },
        },
        "packageId": package_id,
        "payloadSchema": PAYLOAD_SCHEMA,
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "rendererSemanticStreamSha256": semantic_sha256,
        "schemaVersion": 3,
        "sourcedTextPolicySha256": SOURCED_TEXT_POLICY_SHA256,
        "unicodeScriptProfileSha256": UNICODE_SCRIPT_PROFILE_SHA256,
    }


def _publish(
    connection: sqlite3.Connection,
    database_path: Path,
    *,
    partial_directory: Path,
    output_directory: Path,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    windows: Sequence[_Window],
    render_run_identity: Mapping[str, object],
    render_run_sha256: str,
    code_identities: Mapping[str, object],
) -> Mapping[str, object]:
    if _render_code_identities() != dict(code_identities):
        raise GlobalWaterwayPackageError("waterway renderer code identity drifted")
    semantic_sha256 = _renderer_semantic_sha256(connection)
    projection = _projection_stats(connection, windows)
    _, present_tiles, peaks = _build_runtime_files(
        connection, database_path, partial_directory, windows
    )
    records_identity = _stream_identity(
        partial_directory / "records.fadictpack", "waterway records"
    )
    index_identity = _stream_identity(
        partial_directory / "tile-index.bin", "waterway tile index"
    )
    manifest = _manifest_document(
        package_id=package_id,
        source_binding=source_binding,
        windows=windows,
        semantic_sha256=semantic_sha256,
        classifier_sha256=str(render_run_identity["classifierSha256"]),
        run_identity_sha256=render_run_sha256,
        records_identity=records_identity,
        index_identity=index_identity,
    )
    manifest_bytes = _canonical_json_bytes(manifest)
    manifest_path = partial_directory / "manifest.json"
    _write_synced(manifest_path, manifest_bytes)
    output_files = [
        {
            "bytes": len(manifest_bytes),
            "name": "manifest.json",
            "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
        },
        {
            "bytes": records_identity["bytes"],
            "name": "records.fadictpack",
            "sha256": records_identity["sha256"],
        },
        {
            "bytes": index_identity["bytes"],
            "name": "tile-index.bin",
            "sha256": index_identity["sha256"],
        },
    ]
    runtime_bytes = sum(int(item["bytes"]) for item in output_files)
    enforce_global_waterway_storage_ceiling(runtime_bytes)
    ingest_receipt = _meta_get(connection, "ingestReceipt")
    if not isinstance(ingest_receipt, dict):
        raise GlobalWaterwayPackageError("waterway ingest receipt is absent")
    projection.update(
        {
            "declaredIndexEntries": sum(window.tile_count for window in windows),
            "fullWorldZ4To11IndexBytes": sum(4**zoom for zoom in _DEFAULT_ZOOMS)
            * INDEX_ENTRY_BYTES,
            "hardComponentPackageByteCeiling": _HARD_COMPONENT_PACKAGE_BYTES,
            "hardMandatoryPhoneFootprintBytes": _HARD_PHONE_FOOTPRINT_BYTES,
            "preferredCeilingExceeded": (
                runtime_bytes >= _PREFERRED_COMPONENT_PACKAGE_BYTES
            ),
            "preferredComponentPackageByteCeiling": (
                _PREFERRED_COMPONENT_PACKAGE_BYTES
            ),
            "preferredMandatoryPhoneFootprintBytes": (
                _PREFERRED_PHONE_FOOTPRINT_BYTES
            ),
            "reservedNonComponentFootprintBytes": (
                _RESERVED_NON_COMPONENT_FOOTPRINT_BYTES
            ),
            "presentTileCount": present_tiles,
            "runtimePackageBytes": runtime_bytes,
        }
    )
    receipt: dict[str, object] = {
        "attribution": manifest["globalWaterwaySupplement"]["attribution"],
        "build": {
            "classifierSha256": render_run_identity["classifierSha256"],
            "code": dict(code_identities),
            "runIdentity": dict(render_run_identity),
            "runIdentitySha256": render_run_sha256,
        },
        "catalogCountsClaimed": False,
        "closureAudit": ingest_receipt["closureAudit"],
        "outputFiles": output_files,
        "packageId": package_id,
        "peakResources": {
            **peaks,
            "androidIndexByteCeiling": _ANDROID_MAX_INDEX_BYTES,
            "androidRecordsPerTileCeiling": MAX_RECORDS_PER_TILE,
            "androidRawTileByteCeiling": MAX_TILE_BYTES,
            "hardComponentPackageByteCeiling": _HARD_COMPONENT_PACKAGE_BYTES,
            "hardMandatoryPhoneFootprintBytes": _HARD_PHONE_FOOTPRINT_BYTES,
            "reservedNonComponentFootprintBytes": (
                _RESERVED_NON_COMPONENT_FOOTPRINT_BYTES
            ),
            "measurementScope": {
                "persistentSqliteSampling": (
                    "database and visible journal/WAL/SHM files after checkpoint commits"
                ),
                "processPeakRssMeasured": False,
                "transientSqliteTempFilesIncluded": False,
            },
            "sqliteCacheTargetBytes": 64 * 1024 * 1024,
        },
        "projection": projection,
        "relationGeometryPolicy": render_run_identity["relationGeometryPolicy"],
        "rendererSemanticStreamSha256": semantic_sha256,
        "schema": _BUILD_SCHEMA,
        "source": source_binding.document(),
    }
    projection["publishedDirectoryBytes"] = 0
    for _ in range(8):
        receipt_bytes = _canonical_json_bytes(receipt)
        published_bytes = runtime_bytes + len(receipt_bytes)
        if projection["publishedDirectoryBytes"] == published_bytes:
            break
        projection["publishedDirectoryBytes"] = published_bytes
    else:
        raise GlobalWaterwayPackageError(
            "published waterway directory byte accounting did not converge"
        )
    enforce_global_waterway_storage_ceiling(published_bytes)
    receipt_path = partial_directory / "build-receipt.json"
    _write_synced(receipt_path, receipt_bytes)
    partial_sizes = _require_exact_directory_inventory(
        partial_directory,
        {
            "build-receipt.json",
            "manifest.json",
            "records.fadictpack",
            "tile-index.bin",
        },
        "global waterway package partial directory",
    )
    if sum(partial_sizes.values()) != published_bytes:
        raise GlobalWaterwayPackageError(
            "global waterway package byte accounting differs before publication"
        )
    for item in output_files:
        _require_identity(
            partial_directory / str(item["name"]),
            expected_bytes=int(item["bytes"]),
            expected_sha256=str(item["sha256"]),
            label=f"staged {item['name']}",
        )
    _require_identity(
        receipt_path,
        expected_bytes=len(receipt_bytes),
        expected_sha256=hashlib.sha256(receipt_bytes).hexdigest(),
        label="staged build receipt",
    )
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError("global waterway output directory already exists")
    if _render_code_identities() != dict(code_identities):
        raise GlobalWaterwayPackageError(
            "waterway renderer code identity drifted before publication"
        )

    def verify_published_package(installed: Path) -> None:
        published_sizes = _require_exact_directory_inventory(
            installed,
            {
                "build-receipt.json",
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
            },
            "published global waterway package directory",
        )
        if published_sizes != partial_sizes:
            raise GlobalWaterwayPackageError(
                "published global waterway package inventory bytes differ"
            )
        for item in output_files:
            _require_identity(
                installed / str(item["name"]),
                expected_bytes=int(item["bytes"]),
                expected_sha256=str(item["sha256"]),
                label=f"published {item['name']}",
            )
        _require_identity(
            installed / "build-receipt.json",
            expected_bytes=len(receipt_bytes),
            expected_sha256=hashlib.sha256(receipt_bytes).hexdigest(),
            label="published build receipt",
        )

    immediate_sizes = _require_exact_directory_inventory(
        partial_directory,
        set(partial_sizes),
        "global waterway package immediate pre-rename directory",
    )
    if immediate_sizes != partial_sizes:
        raise GlobalWaterwayPackageError(
            "global waterway package bytes drifted before atomic rename"
        )
    for item in output_files:
        _require_identity(
            partial_directory / str(item["name"]),
            expected_bytes=int(item["bytes"]),
            expected_sha256=str(item["sha256"]),
            label=f"immediate pre-rename {item['name']}",
        )
    _require_identity(
        receipt_path,
        expected_bytes=len(receipt_bytes),
        expected_sha256=hashlib.sha256(receipt_bytes).hexdigest(),
        label="immediate pre-rename build receipt",
    )
    _publish_directory_no_replace(
        partial_directory,
        output_directory,
        verify_published_package,
    )
    return MappingProxyType(receipt)


def _validated_package_id(value: object) -> str:
    if (
        type(value) is not str
        or not value
        or value in {".", ".."}
        or any(character in value for character in ("/", "\\", "\0"))
    ):
        raise GlobalWaterwayPackageError("package ID is empty or path-unsafe")
    if unicodedata.normalize("NFC", value) != value:
        raise GlobalWaterwayPackageError("package ID must be NFC")
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        raise GlobalWaterwayPackageError("package ID contains invalid Unicode")
    return value


def _render_bound_global_waterway_package(
    *,
    opl_path: Path,
    root_ids_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    zooms: Sequence[int] = _DEFAULT_ZOOMS,
    checkpoint_objects: int = 10_000,
    checkpoint_features: int = 100,
    pause_after_objects: int | None = None,
    pause_after_features: int | None = None,
    production_authority: object | None = None,
) -> GlobalWaterwayBuildResult:
    """Cook one verified global closure into a deterministic atomic V3 supplement."""

    from .osm_global_waterway_renderer import classifier_identity_sha256

    if not all(
        isinstance(path, Path)
        for path in (
            opl_path,
            root_ids_path,
            output_directory,
            work_directory,
        )
    ):
        raise GlobalWaterwayPackageError("global waterway paths must be pathlib.Path values")
    fixture_only = source_binding.planet_path.startswith("fixture://")
    if fixture_only:
        if production_authority is not None:
            raise GlobalWaterwayPackageError(
                "fixture waterway rendering cannot use production authority"
            )
    elif (
        production_authority is not source_module._PRODUCTION_RENDER_AUTHORITY
        or source_binding.planet_bytes != source_module.EXPECTED_PLANET_BYTES
        or source_binding.planet_sha256 != source_module.EXPECTED_PLANET_SHA256
    ):
        raise GlobalWaterwayPackageError(
            "production waterway rendering requires authenticated extraction authority"
        )
    checked_package_id = _validated_package_id(package_id)
    normalized_zooms = tuple(zooms)
    if normalized_zooms != _DEFAULT_ZOOMS:
        raise GlobalWaterwayPackageError(
            "global waterway supplement zooms must be exactly z4..11"
        )
    if type(checkpoint_features) is not int or checkpoint_features <= 0:
        raise GlobalWaterwayPackageError("checkpoint feature count must be positive")
    if pause_after_features is not None and (
        type(pause_after_features) is not int or pause_after_features <= 0
    ):
        raise GlobalWaterwayPackageError("pause feature count must be positive")
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError("global waterway output directory already exists")
    ingest = ingest_global_waterway_closure(
        opl_path=opl_path,
        root_ids_path=root_ids_path,
        work_directory=work_directory,
        source_binding=source_binding,
        checkpoint_objects=checkpoint_objects,
        pause_after_objects=pause_after_objects,
    )
    if ingest.state != "complete":
        return GlobalWaterwayBuildResult(
            "paused", output_directory, ingest.receipt
        )
    code_identities = _render_code_identities()
    classifier = classifier_identity_sha256()
    render_identity = _render_run_identity(
        package_id=checked_package_id,
        source_binding=source_binding,
        zooms=normalized_zooms,
        checkpoint_features=checkpoint_features,
        code_identities=code_identities,
        classifier_sha256=classifier,
    )
    render_sha256 = hashlib.sha256(
        _canonical_json_bytes(render_identity)
    ).hexdigest()
    partial_directory = output_directory.with_name(
        output_directory.name + ".partial-" + render_sha256[:16]
    )
    connection = sqlite3.connect(ingest.database_path)
    try:
        connection.execute("PRAGMA journal_mode=DELETE")
        connection.execute("PRAGMA synchronous=FULL")
        complete = _stage_renderer_records(
            connection,
            ingest.database_path,
            source_binding=source_binding,
            zooms=normalized_zooms,
            checkpoint_features=checkpoint_features,
            pause_after_features=pause_after_features,
            run_identity=render_identity,
        )
        if not complete:
            return GlobalWaterwayBuildResult(
                "paused",
                output_directory,
                MappingProxyType(
                    {
                        "checkpoint": _meta_get(connection, "renderCheckpoint"),
                        "runIdentitySha256": render_sha256,
                        "schema": _BUILD_SCHEMA,
                        "state": "paused",
                    }
                ),
            )
        windows = _windows(connection)
        receipt = _publish(
            connection,
            ingest.database_path,
            partial_directory=partial_directory,
            output_directory=output_directory,
            package_id=checked_package_id,
            source_binding=source_binding,
            windows=windows,
            render_run_identity=render_identity,
            render_run_sha256=render_sha256,
            code_identities=code_identities,
        )
        return GlobalWaterwayBuildResult("complete", output_directory, receipt)
    finally:
        connection.close()


def render_fixture_global_waterway_package(
    *,
    opl_path: Path,
    root_ids_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    zooms: Sequence[int] = _DEFAULT_ZOOMS,
    checkpoint_objects: int = 10_000,
    checkpoint_features: int = 100,
    pause_after_objects: int | None = None,
    pause_after_features: int | None = None,
) -> GlobalWaterwayBuildResult:
    if (
        not isinstance(source_binding, WaterwaySourceBinding)
        or not source_binding.planet_path.startswith("fixture://")
    ):
        raise GlobalWaterwayPackageError(
            "fixture waterway rendering requires an explicit fixture:// binding"
        )
    return _render_bound_global_waterway_package(
        opl_path=opl_path,
        root_ids_path=root_ids_path,
        output_directory=output_directory,
        work_directory=work_directory,
        package_id=package_id,
        source_binding=source_binding,
        zooms=zooms,
        checkpoint_objects=checkpoint_objects,
        checkpoint_features=checkpoint_features,
        pause_after_objects=pause_after_objects,
        pause_after_features=pause_after_features,
    )


__all__ = [
    "enforce_global_waterway_storage_ceiling",
    "ingest_global_waterway_closure",
    "iter_exact_waterway_features",
    "render_fixture_global_waterway_package",
]
