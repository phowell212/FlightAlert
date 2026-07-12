from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import stat
import struct
import sys
import unicodedata
import zlib
from contextlib import ExitStack
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Any, Iterable, Iterator, Mapping, Sequence

from . import (
    model,
    osm_global_place_package as source_module,
    osm_hydro_source,
    osm_place_renderer,
    reference_presentation_policy,
    renderer_tile_package,
    semantic_model,
    sourced_text,
)
from .model import TileKey
from .osm_global_place_package import (
    GlobalPlaceBuildResult,
    GlobalPlacePackageError,
    PlaceSourceBinding,
    iter_strict_place_opl,
    verify_file_identity,
)
from .osm_hydro_source import OsmDataset, OsmNode
from .osm_place_renderer import (
    OSM_ENGLISH_NAME_SOURCE_FIELD_ID,
    OSM_NAME_SOURCE_FIELD_ID,
    build_osm_place_node,
    classifier_identity_sha256,
)
from .reference_presentation_policy import PRESENTATION_POLICY_SHA256, SemanticSubtype
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
from .sourced_text import LayoutMode, SourcedTextError, SourcedTextErrorCode


_BUILD_SCHEMA = "flightalert.experiment8.osm-global-place-build.v1"
_CATALOG_INPUT_SCHEMA = "flightalert.experiment8.class-catalog-input.v1"
_RUN_IDENTITY_SCHEMA = "flightalert.experiment8.osm-global-place-run-identity.v1"
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_DEFAULT_ZOOMS = tuple(range(4, 12))
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_MAX_COMPRESSED_TILE_BYTES = (
    MAX_TILE_BYTES
    + (MAX_TILE_BYTES >> 12)
    + (MAX_TILE_BYTES >> 14)
    + (MAX_TILE_BYTES >> 25)
    + 13
)
_CANONICAL_UNSIGNED = __import__("re").compile(r"(?:0|[1-9][0-9]*)\Z")
_I64_MAX = (1 << 63) - 1
_MAX_WEB_MERCATOR_LATITUDE_E7 = 850_511_287
_TILE_HEADER_BYTES = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
_EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
_PARTIAL_OWNERSHIP_SCHEMA = "flightalert.experiment8.osm-global-place-partial-owner.v1"
_INGEST_CHECKPOINT_STATE_SCHEMA = (
    "flightalert.experiment8.osm-global-place-ingest-checkpoint-state.v1"
)
_SEMANTIC_ROW_TABLES = ("features", "records", "variants")
_SEMANTIC_OUTCOME_EVENT_DOMAIN = b"flight-alert-exp8-place-semantic-outcome-v1\0"
_SEMANTIC_OUTCOME_EVENT_BYTES = hashlib.sha256().digest_size
_SEMANTIC_OUTCOME_AUDIT_SCHEMA = (
    "flightalert.experiment8.osm-global-place-semantic-outcome-audit.v1"
)
_OUTCOME_EXCLUDED_CONTROLS = 1
_OUTCOME_MISSING_PLACE = 2
_OUTCOME_UNSUPPORTED_PLACE = 3
_OUTCOME_MISSING_PRIMARY = 4
_OUTCOME_BLANK_PRIMARY = 5
_OUTCOME_NONCANONICAL_PRIMARY = 6
_OUTCOME_OVERSIZE_PRIMARY = 7
_OUTCOME_NON_NFC_PRIMARY = 8
_OUTCOME_RENDERED = 9
_CONTROL_AUDIT_KEYS = {
    "capital": "controlCapitalValues",
    "name": "controlPrimaryNames",
    "name:en": "controlEnglishNames",
    "place": "controlPlaceValues",
    "population": "controlPopulationValues",
}


class _OversizeOptionalEnglish(str):
    """Preserve exact source text while making optional overlong English non-displayable."""


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
        raise GlobalPlacePackageError("build document is not canonical JSON") from error


def _stream_identity(path: Path, chunk_bytes: int = 1024 * 1024) -> dict[str, object]:
    if (
        not isinstance(path, Path)
        or not path.is_file()
        or path.is_symlink()
        or _is_link_or_reparse(path)
    ):
        raise GlobalPlacePackageError(f"identity target is not one regular non-link file: {path}")
    before_path = path.stat()
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        before_handle = os.fstat(handle.fileno())
        if (before_handle.st_dev, before_handle.st_ino) != (
            before_path.st_dev,
            before_path.st_ino,
        ):
            raise GlobalPlacePackageError(f"identity target changed while opening: {path}")
        while True:
            chunk = handle.read(chunk_bytes)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after_handle = os.fstat(handle.fileno())
    after_path = path.stat()
    before_signature = (
        before_path.st_dev,
        before_path.st_ino,
        before_path.st_size,
        before_path.st_mtime_ns,
        before_path.st_ctime_ns,
    )
    if (
        before_signature
        != (
            after_handle.st_dev,
            after_handle.st_ino,
            after_handle.st_size,
            after_handle.st_mtime_ns,
            after_handle.st_ctime_ns,
        )
        or before_signature
        != (
            after_path.st_dev,
            after_path.st_ino,
            after_path.st_size,
            after_path.st_mtime_ns,
            after_path.st_ctime_ns,
        )
        or total != before_path.st_size
    ):
        raise GlobalPlacePackageError(f"identity target drifted while hashing: {path}")
    return {"bytes": total, "sha256": digest.hexdigest()}


def _code_identities() -> dict[str, object]:
    modules = {
        "globalPlaceSource": source_module,
        "globalPlaceStore": __import__(__name__, fromlist=["*"]),
        "model": model,
        "osmHydroSource": osm_hydro_source,
        "placeRenderer": osm_place_renderer,
        "presentationPolicy": reference_presentation_policy,
        "rendererTilePackage": renderer_tile_package,
        "semanticModel": semantic_model,
        "sourcedText": sourced_text,
    }
    return {
        name: _stream_identity(Path(module.__file__).resolve())
        for name, module in sorted(modules.items())
    }


def _validated_package_id(value: object) -> str:
    if (
        type(value) is not str
        or not value
        or value in {".", ".."}
        or any(character in value for character in ("/", "\\", "\0"))
    ):
        raise GlobalPlacePackageError("package ID is empty or path-unsafe")
    return value


def _run_identity_document(
    *,
    package_id: str,
    source_binding: PlaceSourceBinding,
    zooms: tuple[int, ...],
    checkpoint_nodes: int,
    code_identities: Mapping[str, object],
) -> dict[str, object]:
    return {
        "checkpointNodes": checkpoint_nodes,
        "classifierSha256": classifier_identity_sha256(),
        "code": dict(code_identities),
        "packageId": package_id,
        "schema": _RUN_IDENTITY_SCHEMA,
        "source": source_binding.document(),
        "runtime": {
            "pythonImplementation": sys.implementation.name,
            "pythonVersion": list(sys.version_info[:3]),
            "sqliteVersion": sqlite3.sqlite_version,
            "zlibVersion": zlib.ZLIB_VERSION,
        },
        "zooms": list(zooms),
    }


def _meta_get(connection: sqlite3.Connection, key: str) -> object | None:
    row = connection.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return None if row is None else json.loads(bytes(row[0]).decode("utf-8", "strict"))


def _meta_set(connection: sqlite3.Connection, key: str, value: object) -> None:
    connection.execute(
        "INSERT INTO meta(key, value) VALUES (?, ?) "
        "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, _canonical_json_bytes(value)),
    )


def _open_database(path: Path, run_identity: Mapping[str, object]) -> sqlite3.Connection:
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
            "CREATE TABLE meta (key TEXT PRIMARY KEY, value BLOB NOT NULL) WITHOUT ROWID;"
            "CREATE TABLE records ("
            "z INTEGER NOT NULL, y INTEGER NOT NULL, x INTEGER NOT NULL, "
            "posting_key BLOB NOT NULL, draw_order INTEGER NOT NULL, "
            "priority INTEGER NOT NULL, layer_group INTEGER NOT NULL, "
            "feature_kind INTEGER NOT NULL, variant_id BLOB NOT NULL, "
            "feature_id BLOB NOT NULL, sourced_sha BLOB NOT NULL, "
            "envelope BLOB NOT NULL, subtype INTEGER NOT NULL, "
            "UNIQUE(z, y, x, posting_key));"
            "CREATE INDEX records_semantic_coordinates ON records(z, x, y);"
            "CREATE TABLE features ("
            "subtype INTEGER NOT NULL, identity BLOB NOT NULL, payload_sha BLOB NOT NULL, "
            "PRIMARY KEY(subtype, identity)) WITHOUT ROWID;"
            "CREATE TABLE variants ("
            "subtype INTEGER NOT NULL, identity BLOB NOT NULL, payload_sha BLOB NOT NULL, "
            "PRIMARY KEY(subtype, identity)) WITHOUT ROWID;"
            "CREATE TABLE source_outcomes ("
            "sequence INTEGER PRIMARY KEY, node_id INTEGER NOT NULL UNIQUE, "
            "event_sha BLOB NOT NULL) WITHOUT ROWID;"
        )
        audit = _empty_audit()
        peaks = _empty_peaks()
        row_counts = {table: 0 for table in _SEMANTIC_ROW_TABLES}
        checkpoint = {
            "ingestComplete": False,
            "inputNodes": 0,
            "lineNumber": 0,
            "nextByteOffset": 0,
            "previousNodeId": 0,
            "semanticRowCounts": row_counts,
            "sourceOutcomeRows": 0,
            "sourcePrefixSha256": _EMPTY_SHA256,
            "stateSha256": "",
        }
        _require_semantic_table_counts(audit, row_counts)
        _seal_ingest_checkpoint(
            checkpoint,
            run_identity=run_identity,
            audit=audit,
        )
        _meta_set(connection, "runIdentity", run_identity)
        _meta_set(connection, "checkpoint", checkpoint)
        _meta_set(connection, "audit", audit)
        _meta_set(connection, "peaks", peaks)
        connection.commit()
    elif _meta_get(connection, "runIdentity") != dict(run_identity):
        connection.close()
        raise GlobalPlacePackageError(
            "checkpoint identity differs from the exact code/config/source identity"
        )
    return connection


def _empty_audit() -> dict[str, int]:
    return {
        "acceptedCapitalEvidence": 0,
        "acceptedPopulationEvidence": 0,
        "bilingualLabels": 0,
        "blankPrimaryNames": 0,
        "controlCapitalValues": 0,
        "controlEnglishNames": 0,
        "controlPlaceValues": 0,
        "controlPopulationValues": 0,
        "controlPrimaryNames": 0,
        "declaredEnglishNames": 0,
        "excludedAdmittedControlNodes": 0,
        "inputNodes": 0,
        "invalidCapitalEvidence": 0,
        "invalidPopulationEvidence": 0,
        "latinPrimarySingleLineLabels": 0,
        "malformedEnglishNames": 0,
        "missingPlaceValues": 0,
        "missingPrimaryNames": 0,
        "nonCanonicalPrimaryNames": 0,
        "nonNfcPrimaryNames": 0,
        "oversizePrimaryNames": 0,
        "presentCapitalEvidence": 0,
        "presentPopulationEvidence": 0,
        "renderedNodes": 0,
        "singleLineLabels": 0,
        "supportedPlaceNodes": 0,
        "unsupportedPlaceValues": 0,
        "webMercatorClampedNodes": 0,
        "writtenPostings": 0,
    }


def _empty_peaks() -> dict[str, int]:
    return {
        "compressedTileBytes": 0,
        "inputLineBytes": 0,
        "nodePostingBytes": 0,
        "rawTileBytes": 0,
        "recordsPerTile": 0,
        "observedPersistentSqliteBytesAtCheckpoints": 0,
    }


def _is_sha256(value: object) -> bool:
    return (
        type(value) is str
        and len(value) == 64
        and all(character in "0123456789abcdef" for character in value)
    )


def _semantic_row_counts(connection: sqlite3.Connection) -> dict[str, int]:
    return {
        table: int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
        for table in _SEMANTIC_ROW_TABLES
    }


def _ingest_checkpoint_state_sha256(
    checkpoint: Mapping[str, object],
    *,
    run_identity: Mapping[str, object],
    audit: Mapping[str, int],
) -> str:
    checkpoint_state = {
        key: value for key, value in checkpoint.items() if key != "stateSha256"
    }
    document = {
        "audit": dict(audit),
        "checkpoint": checkpoint_state,
        "runIdentitySha256": hashlib.sha256(
            _canonical_json_bytes(run_identity)
        ).hexdigest(),
        "schema": _INGEST_CHECKPOINT_STATE_SCHEMA,
    }
    return hashlib.sha256(_canonical_json_bytes(document)).hexdigest()


def _seal_ingest_checkpoint(
    checkpoint: dict[str, object],
    *,
    run_identity: Mapping[str, object],
    audit: Mapping[str, int],
) -> None:
    checkpoint["stateSha256"] = _ingest_checkpoint_state_sha256(
        checkpoint,
        run_identity=run_identity,
        audit=audit,
    )


def _require_nonnegative_counter_document(
    document: object,
    expected_keys: set[str],
    label: str,
) -> dict[str, int]:
    if not isinstance(document, dict) or set(document) != expected_keys:
        raise GlobalPlacePackageError(f"ingest checkpoint {label} fields differ")
    if any(type(value) is not int or value < 0 for value in document.values()):
        raise GlobalPlacePackageError(f"ingest checkpoint {label} counters are invalid")
    return dict(document)


def _require_semantic_table_counts(
    audit: Mapping[str, int],
    row_counts: Mapping[str, int],
) -> None:
    rendered_nodes = audit["renderedNodes"]
    expected_rows = {
        "features": rendered_nodes,
        "records": audit["writtenPostings"],
        "variants": rendered_nodes,
    }
    if dict(row_counts) != expected_rows:
        raise GlobalPlacePackageError(
            "ingest checkpoint semantic table counts differ from its audit"
        )
    classified_nodes = sum(
        audit[key]
        for key in (
            "excludedAdmittedControlNodes",
            "missingPlaceValues",
            "supportedPlaceNodes",
            "unsupportedPlaceValues",
        )
    )
    primary_outcomes = sum(
        audit[key]
        for key in (
            "blankPrimaryNames",
            "missingPrimaryNames",
            "nonCanonicalPrimaryNames",
            "nonNfcPrimaryNames",
            "oversizePrimaryNames",
            "renderedNodes",
        )
    )
    if (
        classified_nodes != audit["inputNodes"]
        or primary_outcomes != audit["supportedPlaceNodes"]
        or audit["singleLineLabels"] + audit["bilingualLabels"]
        != rendered_nodes
        or audit["acceptedPopulationEvidence"]
        + audit["invalidPopulationEvidence"]
        != audit["presentPopulationEvidence"]
        or audit["acceptedCapitalEvidence"] + audit["invalidCapitalEvidence"]
        != audit["presentCapitalEvidence"]
    ):
        raise GlobalPlacePackageError(
            "ingest checkpoint semantic audit accounting differs"
        )


def _validated_ingest_resume_state(
    connection: sqlite3.Connection,
    source_binding: PlaceSourceBinding,
) -> tuple[
    dict[str, object],
    dict[str, int],
    dict[str, int],
    dict[str, int],
    dict[str, object],
]:
    try:
        checkpoint = _meta_get(connection, "checkpoint")
        audit = _meta_get(connection, "audit")
        peaks = _meta_get(connection, "peaks")
        run_identity = _meta_get(connection, "runIdentity")
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise GlobalPlacePackageError("ingest checkpoint metadata is malformed") from error
    expected_checkpoint_fields = {
        "ingestComplete",
        "inputNodes",
        "lineNumber",
        "nextByteOffset",
        "previousNodeId",
        "semanticRowCounts",
        "sourceOutcomeRows",
        "sourcePrefixSha256",
        "stateSha256",
    }
    if not isinstance(checkpoint, dict) or set(checkpoint) != expected_checkpoint_fields:
        raise GlobalPlacePackageError("ingest checkpoint fields differ")
    if type(checkpoint["ingestComplete"]) is not bool:
        raise GlobalPlacePackageError("ingest checkpoint completion flag is invalid")
    for key in (
        "inputNodes",
        "lineNumber",
        "nextByteOffset",
        "previousNodeId",
        "sourceOutcomeRows",
    ):
        if type(checkpoint[key]) is not int or checkpoint[key] < 0:
            raise GlobalPlacePackageError(f"ingest checkpoint {key} is invalid")
    if (
        not _is_sha256(checkpoint["sourcePrefixSha256"])
        or not _is_sha256(checkpoint["stateSha256"])
    ):
        raise GlobalPlacePackageError("ingest checkpoint SHA-256 fields are invalid")
    row_counts = _require_nonnegative_counter_document(
        checkpoint["semanticRowCounts"],
        set(_SEMANTIC_ROW_TABLES),
        "semantic row count",
    )
    audit_document = _require_nonnegative_counter_document(
        audit, set(_empty_audit()), "audit"
    )
    peaks_document = _require_nonnegative_counter_document(
        peaks, set(_empty_peaks()), "peak"
    )
    if not isinstance(run_identity, dict):
        raise GlobalPlacePackageError("ingest checkpoint run identity is malformed")
    input_nodes = checkpoint["inputNodes"]
    line_number = checkpoint["lineNumber"]
    offset = checkpoint["nextByteOffset"]
    previous_node_id = checkpoint["previousNodeId"]
    if line_number != input_nodes or audit_document["inputNodes"] != input_nodes:
        raise GlobalPlacePackageError("ingest checkpoint node counters differ")
    if checkpoint["sourceOutcomeRows"] != input_nodes:
        raise GlobalPlacePackageError(
            "ingest checkpoint semantic outcome row count differs"
        )
    if offset > source_binding.opl_bytes:
        raise GlobalPlacePackageError("ingest checkpoint OPL offset exceeds its source")
    if input_nodes == 0:
        if offset != 0 or previous_node_id != 0:
            raise GlobalPlacePackageError("ingest checkpoint empty prefix state differs")
    elif offset == 0 or previous_node_id == 0:
        raise GlobalPlacePackageError("ingest checkpoint committed prefix is incomplete")
    if checkpoint["ingestComplete"] and offset != source_binding.opl_bytes:
        raise GlobalPlacePackageError("ingest checkpoint completed offset is not EOF")
    actual_row_counts = _semantic_row_counts(connection)
    if row_counts != actual_row_counts:
        raise GlobalPlacePackageError("ingest checkpoint semantic row counts differ")
    _require_semantic_table_counts(audit_document, actual_row_counts)
    actual_outcome_rows = int(
        connection.execute("SELECT COUNT(*) FROM source_outcomes").fetchone()[0]
    )
    if actual_outcome_rows != checkpoint["sourceOutcomeRows"]:
        raise GlobalPlacePackageError(
            "ingest checkpoint semantic outcome rows differ"
        )
    expected_state_sha256 = _ingest_checkpoint_state_sha256(
        checkpoint,
        run_identity=run_identity,
        audit=audit_document,
    )
    if checkpoint["stateSha256"] != expected_state_sha256:
        raise GlobalPlacePackageError("ingest checkpoint state SHA-256 differs")
    return checkpoint, audit_document, peaks_document, row_counts, run_identity


def _canonical_evidence(raw: str | None, minimum: int, maximum: int) -> bool:
    if raw is None or _CANONICAL_UNSIGNED.fullmatch(raw) is None:
        return False
    maximum_text = str(maximum)
    if len(raw) > len(maximum_text) or (
        len(raw) == len(maximum_text) and raw > maximum_text
    ):
        return False
    value = int(raw)
    return minimum <= value <= maximum


def _supported_place_value(raw: str) -> bool:
    try:
        osm_place_renderer._semantic_subtype(  # type: ignore[attr-defined]
            raw,
            population=None,
            capital_level=None,
        )
        return True
    except ValueError:
        return False


def _validate_primary(
    tags: Mapping[str, str], audit: dict[str, int]
) -> tuple[str | None, int]:
    name = tags.get("name")
    if name is None:
        audit["missingPrimaryNames"] += 1
        return None, _OUTCOME_MISSING_PRIMARY
    if not name.strip():
        audit["blankPrimaryNames"] += 1
        return None, _OUTCOME_BLANK_PRIMARY
    if name.strip() != name:
        audit["nonCanonicalPrimaryNames"] += 1
        return None, _OUTCOME_NONCANONICAL_PRIMARY
    try:
        encoded = name.encode("utf-8", "strict")
    except UnicodeEncodeError:
        audit["nonCanonicalPrimaryNames"] += 1
        return None, _OUTCOME_NONCANONICAL_PRIMARY
    if len(encoded) > sourced_text.MAX_SOURCED_TEXT_UTF8_BYTES:
        audit["oversizePrimaryNames"] += 1
        return None, _OUTCOME_OVERSIZE_PRIMARY
    if unicodedata.normalize("NFC", name) != name:
        audit["nonNfcPrimaryNames"] += 1
        return None, _OUTCOME_NON_NFC_PRIMARY
    return name, _OUTCOME_RENDERED


def _renderer_node_with_bounded_optional_english(
    node: OsmNode,
    tags: Mapping[str, str],
    audit: dict[str, int],
) -> OsmNode:
    english = tags.get("name:en")
    if english is None:
        return node
    encoded = english.encode("utf-8", "strict")
    if (
        len(english) <= sourced_text.MAX_SOURCED_TEXT_UTF8_BYTES
        and len(encoded) <= sourced_text.MAX_SOURCED_TEXT_UTF8_BYTES
    ):
        return node
    audit["malformedEnglishNames"] += 1
    exact_tags = tuple(
        (
            key,
            _OversizeOptionalEnglish(value) if key == "name:en" else value,
        )
        for key, value in node.tags
    )
    return OsmNode(
        object_id=node.object_id,
        version=node.version,
        timestamp=node.timestamp,
        longitude_e7=node.longitude_e7,
        latitude_e7=node.latitude_e7,
        tags=exact_tags,
    )


def _semantic_outcome_digest(
    *,
    node_id: int,
    outcome: int,
    control_mask: int = 0,
    feature: object | None = None,
) -> tuple[bytes, int, object | None]:
    payload = bytearray(_SEMANTIC_OUTCOME_EVENT_DOMAIN)
    payload.extend(struct.pack(">QBB", node_id, outcome, control_mask))
    if feature is None:
        if outcome == _OUTCOME_RENDERED:
            raise GlobalPlacePackageError("rendered semantic outcome lacks its feature")
        return hashlib.sha256(payload).digest(), 0, None
    if outcome != _OUTCOME_RENDERED or control_mask:
        raise GlobalPlacePackageError("semantic outcome feature state is inconsistent")
    first_record = None
    posting_count = 0
    stable_identity = None
    for tile_records in feature.tiles.values():
        if len(tile_records) != 1:
            raise GlobalPlacePackageError(
                "place semantic outcome requires one posting per tile"
            )
        record = tile_records[0]
        renderer = record.renderer_record
        sourced = record.sourced_text
        if sourced is None:
            raise GlobalPlacePackageError("place semantic outcome lacks sourced text")
        basic_identity = (
            renderer.posting.feature_id,
            renderer.variant.canonical_variant_id,
            renderer.variant.placement.source_feature_sha256,
            sourced.full_sha256,
            renderer.variant.semantic_subtype,
        )
        if stable_identity is None:
            stable_identity = (
                *basic_identity[:3],
                variant_fingerprint(renderer.variant).full_sha256,
                *basic_identity[3:],
            )
            first_record = record
        elif basic_identity != (
            stable_identity[0],
            stable_identity[1],
            stable_identity[2],
            stable_identity[4],
            stable_identity[5],
        ):
            raise GlobalPlacePackageError(
                "place semantic outcome postings have divergent identities"
            )
        posting_count += 1
    if first_record is None or stable_identity is None or posting_count <= 0:
        raise GlobalPlacePackageError("rendered semantic outcome has no postings")
    (
        feature_id,
        variant_id,
        source_feature_sha256,
        variant_sha256,
        sourced_sha256,
        subtype,
    ) = stable_identity
    payload.extend(source_feature_sha256)
    payload.extend(struct.pack(">QQII", feature_id, variant_id, subtype, posting_count))
    payload.extend(variant_sha256)
    payload.extend(sourced_sha256)
    return hashlib.sha256(payload).digest(), posting_count, first_record.sourced_text


def _evaluate_semantic_node(
    parsed: object,
    *,
    source_generation_sha256: str,
    classifier_sha256: str,
    zooms: tuple[int, ...],
    audit: dict[str, int],
) -> tuple[object | None, bytes]:
    node = parsed.node
    audit["inputNodes"] += 1
    if abs(node.latitude_e7) > _MAX_WEB_MERCATOR_LATITUDE_E7:
        audit["webMercatorClampedNodes"] += 1
    excluded_controls = parsed.admitted_control_fields
    if excluded_controls:
        audit["excludedAdmittedControlNodes"] += 1
        control_mask = 0
        for field in excluded_controls:
            audit[_CONTROL_AUDIT_KEYS[field]] += 1
            control_mask |= 1 << tuple(_CONTROL_AUDIT_KEYS).index(field)
        event, _, _ = _semantic_outcome_digest(
            node_id=node.object_id,
            outcome=_OUTCOME_EXCLUDED_CONTROLS,
            control_mask=control_mask,
        )
        return None, event
    tags = dict(node.tags)
    place_value = tags.get("place")
    if place_value is None:
        audit["missingPlaceValues"] += 1
        event, _, _ = _semantic_outcome_digest(
            node_id=node.object_id, outcome=_OUTCOME_MISSING_PLACE
        )
        return None, event
    if not _supported_place_value(place_value):
        audit["unsupportedPlaceValues"] += 1
        event, _, _ = _semantic_outcome_digest(
            node_id=node.object_id, outcome=_OUTCOME_UNSUPPORTED_PLACE
        )
        return None, event
    audit["supportedPlaceNodes"] += 1
    name, primary_outcome = _validate_primary(tags, audit)
    if name is None:
        event, _, _ = _semantic_outcome_digest(
            node_id=node.object_id, outcome=primary_outcome
        )
        return None, event
    population = tags.get("population")
    capital = tags.get("capital")
    if population is not None:
        audit["presentPopulationEvidence"] += 1
        if _canonical_evidence(population, 0, _I64_MAX):
            audit["acceptedPopulationEvidence"] += 1
        else:
            audit["invalidPopulationEvidence"] += 1
    if capital is not None:
        audit["presentCapitalEvidence"] += 1
        if capital == "yes" or _canonical_evidence(capital, 3, 5):
            audit["acceptedCapitalEvidence"] += 1
        else:
            audit["invalidCapitalEvidence"] += 1
    has_english = "name:en" in tags
    if has_english:
        audit["declaredEnglishNames"] += 1
    renderer_node = _renderer_node_with_bounded_optional_english(node, tags, audit)
    dataset = OsmDataset(
        api_version="0.6",
        generator="flight-alert-exp8-osmium-1.11.1-global-place-v1",
        nodes=MappingProxyType({node.object_id: renderer_node}),
        ways=MappingProxyType({}),
        relations=MappingProxyType({}),
    )
    try:
        feature = build_osm_place_node(
            dataset=dataset,
            node_id=node.object_id,
            source_generation_sha256=source_generation_sha256,
            classifier_sha256=classifier_sha256,
            primary_source_field_id=OSM_NAME_SOURCE_FIELD_ID,
            english_source_field_id=(
                OSM_ENGLISH_NAME_SOURCE_FIELD_ID if has_english else None
            ),
            zooms=zooms,
        )
    except SourcedTextError as error:
        if error.code is SourcedTextErrorCode.ENGLISH_TOO_LONG:
            raise GlobalPlacePackageError(
                "optional English exceeded its preflight byte bound"
            ) from error
        raise
    event, posting_count, sourced = _semantic_outcome_digest(
        node_id=node.object_id,
        outcome=_OUTCOME_RENDERED,
        feature=feature,
    )
    assert sourced is not None
    if sourced.layout_mode is LayoutMode.PRIMARY_WITH_ENGLISH:
        audit["bilingualLabels"] += 1
    else:
        audit["singleLineLabels"] += 1
        signals = sourced.primary_script_signals
        if signals.has_strong_latin and not signals.has_strong_non_latin:
            audit["latinPrimarySingleLineLabels"] += 1
    audit["renderedNodes"] += 1
    audit["writtenPostings"] += posting_count
    return feature, event


def _semantic_outcome_audits_stream(
    stream: object,
    *,
    source_generation_sha256: str,
    zooms: tuple[int, ...] = _DEFAULT_ZOOMS,
    outcome_stream: object | None = None,
) -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    classifier = classifier_identity_sha256()
    audit = _empty_audit()
    outcome_digest = hashlib.sha256()
    node_count = 0
    first_node_id = None
    last_node_id = None
    excluded_nodes = 0
    control_fields = {field: 0 for field in _CONTROL_AUDIT_KEYS}
    for parsed in iter_strict_place_opl(stream):
        node_count += 1
        if first_node_id is None:
            first_node_id = parsed.node.object_id
        last_node_id = parsed.node.object_id
        if parsed.admitted_control_fields:
            excluded_nodes += 1
            for field in parsed.admitted_control_fields:
                control_fields[field] += 1
        _, event = _evaluate_semantic_node(
            parsed,
            source_generation_sha256=source_generation_sha256,
            classifier_sha256=classifier,
            zooms=zooms,
            audit=audit,
        )
        outcome_digest.update(event)
        if outcome_stream is not None and outcome_stream.write(event) != len(event):
            raise GlobalPlacePackageError(
                "renderer semantic outcome evidence write was incomplete"
            )
    if node_count == 0:
        raise GlobalPlacePackageError("place extraction produced no semantic outcomes")
    strict = {
        "firstNodeId": first_node_id,
        "lastNodeId": last_node_id,
        "nodeCount": node_count,
        "ordering": "Type_then_ID strictly increasing",
        "visibility": "current visible only",
    }
    semantic = {
        "controlCharacterExcludedNodes": excluded_nodes,
        "controlCharacterFields": control_fields,
        "decodedValueAllowlist": list(_CONTROL_AUDIT_KEYS),
    }
    outcome = {
        "audit": audit,
        "eventBytes": _SEMANTIC_OUTCOME_EVENT_BYTES,
        "nodeCount": node_count,
        "schema": _SEMANTIC_OUTCOME_AUDIT_SCHEMA,
        "semanticRowCounts": {
            "features": audit["renderedNodes"],
            "records": audit["writtenPostings"],
            "variants": audit["renderedNodes"],
        },
        "sha256": outcome_digest.hexdigest(),
    }
    return strict, semantic, outcome


def _insert_identity(
    connection: sqlite3.Connection,
    table: str,
    *,
    subtype: int,
    identity: bytes,
    payload_sha: bytes,
) -> bool:
    cursor = connection.execute(
        f"INSERT OR IGNORE INTO {table}(subtype, identity, payload_sha) VALUES (?, ?, ?)",
        (subtype, identity, payload_sha),
    )
    if cursor.rowcount:
        return True
    row = connection.execute(
        f"SELECT payload_sha FROM {table} WHERE subtype = ? AND identity = ?",
        (subtype, identity),
    ).fetchone()
    if row is None or bytes(row[0]) != payload_sha:
        raise GlobalPlacePackageError(f"divergent duplicate {table[:-1]} identity")
    return False


def _record_envelope(record: object, tile: TileKey) -> bytes:
    renderer = record.renderer_record
    sourced = record.sourced_text
    if renderer.posting.requested_tile != tile or sourced is None:
        raise GlobalPlacePackageError("place posting cannot form a canonical label envelope")
    renderer_bytes = renderer_record_bytes(renderer)
    canonical_text = sourced.canonical_bytes
    if not 0 < len(renderer_bytes) <= MAX_RENDERER_RECORD_BYTES:
        raise GlobalPlacePackageError("place renderer record byte length is outside its bound")
    if not 0 < len(canonical_text) <= MAX_SOURCED_TEXT_RECORD_BYTES:
        raise GlobalPlacePackageError("place sourced-text byte length is outside its bound")
    envelope = b"".join(
        (
            struct.pack("<I", len(renderer_bytes)),
            renderer_bytes,
            struct.pack("<I", len(canonical_text)),
            sourced.full_sha256,
            canonical_text,
        )
    )
    return envelope


def _insert_feature(
    connection: sqlite3.Connection,
    *,
    node: object,
    feature: object,
    peaks: dict[str, int],
) -> tuple[int, int, int]:
    node_posting_bytes = 0
    first_record = None
    for tile, tile_records in feature.tiles.items():
        if len(tile_records) != 1:
            raise GlobalPlacePackageError("place node must emit exactly one posting per tile")
        record = tile_records[0]
        renderer = record.renderer_record
        variant = renderer.variant
        posting = renderer.posting
        sourced = record.sourced_text
        if sourced is None:
            raise GlobalPlacePackageError("place label lacks shared sourced text")
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
                "variant_id,feature_id,sourced_sha,envelope,subtype"
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
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
                    posting.feature_id.to_bytes(8, "big"),
                    sourced.full_sha256,
                    envelope,
                    variant.semantic_subtype,
                ),
            )
        except sqlite3.IntegrityError as error:
            raise GlobalPlacePackageError(
                f"duplicate renderer posting for OSM node {node.object_id}"
            ) from error
        node_posting_bytes += len(envelope)
        first_record = record if first_record is None else first_record
    assert first_record is not None
    renderer = first_record.renderer_record
    subtype = renderer.variant.semantic_subtype
    feature_id = renderer.posting.feature_id.to_bytes(8, "big")
    source_sha = renderer.variant.placement.source_feature_sha256
    variant_id = renderer.variant.canonical_variant_id.to_bytes(8, "big")
    variant_sha = variant_fingerprint(renderer.variant).full_sha256
    inserted_feature = _insert_identity(
        connection,
        "features",
        subtype=subtype,
        identity=feature_id,
        payload_sha=source_sha,
    )
    inserted_variant = _insert_identity(
        connection,
        "variants",
        subtype=subtype,
        identity=variant_id,
        payload_sha=variant_sha,
    )
    peaks["nodePostingBytes"] = max(peaks["nodePostingBytes"], node_posting_bytes)
    return len(feature.tiles), int(inserted_feature), int(inserted_variant)


def _database_bytes(path: Path) -> int:
    total = path.stat().st_size if path.exists() else 0
    for suffix in ("-journal", "-wal", "-shm"):
        candidate = Path(str(path) + suffix)
        if candidate.exists():
            total += candidate.stat().st_size
    return total


def _verified_open_opl(
    handle: object,
    path: Path,
    source_binding: PlaceSourceBinding,
    *,
    committed_prefix_bytes: int,
    committed_prefix_sha256: str,
) -> tuple[tuple[int, int, int, int, int], Any]:
    before_handle = os.fstat(handle.fileno())
    before_path = path.stat()
    signature = (
        before_handle.st_dev,
        before_handle.st_ino,
        before_handle.st_size,
        before_handle.st_mtime_ns,
        before_handle.st_ctime_ns,
    )
    if (
        (before_handle.st_dev, before_handle.st_ino)
        != (before_path.st_dev, before_path.st_ino)
        or before_handle.st_size != source_binding.opl_bytes
    ):
        raise GlobalPlacePackageError("open OPL handle differs from its source path")
    digest = hashlib.sha256()
    prefix_digest = hashlib.sha256()
    prefix_remaining = committed_prefix_bytes
    total = 0
    handle.seek(0)
    while True:
        chunk = handle.read(4 * 1024 * 1024)
        if not chunk:
            break
        digest.update(chunk)
        if prefix_remaining:
            prefix_chunk = chunk[:prefix_remaining]
            prefix_digest.update(prefix_chunk)
            prefix_remaining -= len(prefix_chunk)
        total += len(chunk)
    after_hash_handle = os.fstat(handle.fileno())
    after_hash_path = path.stat()
    if (
        signature
        != (
            after_hash_handle.st_dev,
            after_hash_handle.st_ino,
            after_hash_handle.st_size,
            after_hash_handle.st_mtime_ns,
            after_hash_handle.st_ctime_ns,
        )
        or (after_hash_handle.st_dev, after_hash_handle.st_ino)
        != (after_hash_path.st_dev, after_hash_path.st_ino)
        or total != source_binding.opl_bytes
        or digest.hexdigest() != source_binding.opl_sha256
    ):
        raise GlobalPlacePackageError("open OPL identity differs while it is hashed")
    if prefix_remaining or prefix_digest.hexdigest() != committed_prefix_sha256:
        raise GlobalPlacePackageError(
            "ingest checkpoint committed OPL prefix SHA-256 differs"
        )
    return signature, prefix_digest


def _verified_open_semantic_outcome_evidence(
    handle: object,
    path: Path,
    source_binding: PlaceSourceBinding,
) -> tuple[int, int, int, int, int]:
    before_handle = os.fstat(handle.fileno())
    before_path = path.stat()
    signature = (
        before_handle.st_dev,
        before_handle.st_ino,
        before_handle.st_size,
        before_handle.st_mtime_ns,
        before_handle.st_ctime_ns,
    )
    if (
        _is_link_or_reparse(path)
        or (before_handle.st_dev, before_handle.st_ino)
        != (before_path.st_dev, before_path.st_ino)
        or before_handle.st_size
        != source_binding.renderer_semantic_outcome_bytes
    ):
        raise GlobalPlacePackageError(
            "semantic outcome evidence differs from its source path"
        )
    digest = hashlib.sha256()
    total = 0
    handle.seek(0)
    while chunk := handle.read(4 * 1024 * 1024):
        digest.update(chunk)
        total += len(chunk)
    after_handle = os.fstat(handle.fileno())
    after_path = path.stat()
    if (
        signature
        != (
            after_handle.st_dev,
            after_handle.st_ino,
            after_handle.st_size,
            after_handle.st_mtime_ns,
            after_handle.st_ctime_ns,
        )
        or (after_handle.st_dev, after_handle.st_ino)
        != (after_path.st_dev, after_path.st_ino)
        or total != source_binding.renderer_semantic_outcome_bytes
        or digest.hexdigest()
        != source_binding.renderer_semantic_outcome.stream_sha256
    ):
        raise GlobalPlacePackageError(
            "semantic outcome evidence identity differs while it is hashed"
        )
    handle.seek(0)
    return signature


def _require_open_semantic_outcome_evidence_unchanged(
    handle: object,
    path: Path,
    expected_signature: tuple[int, int, int, int, int],
) -> None:
    handle_status = os.fstat(handle.fileno())
    path_status = path.stat()
    if (
        expected_signature
        != (
            handle_status.st_dev,
            handle_status.st_ino,
            handle_status.st_size,
            handle_status.st_mtime_ns,
            handle_status.st_ctime_ns,
        )
        or (handle_status.st_dev, handle_status.st_ino)
        != (path_status.st_dev, path_status.st_ino)
    ):
        raise GlobalPlacePackageError(
            "open semantic outcome evidence drifted during ingestion"
        )


def _require_source_outcome_prefix(
    connection: sqlite3.Connection,
    evidence_handle: object,
    *,
    expected_rows: int,
    expected_previous_node_id: int,
) -> None:
    rows = connection.execute(
        "SELECT sequence, node_id, event_sha FROM source_outcomes ORDER BY sequence"
    )
    count = 0
    last_node_id = 0
    for sequence, node_id, event_sha in rows:
        count += 1
        expected_event = evidence_handle.read(_SEMANTIC_OUTCOME_EVENT_BYTES)
        if (
            int(sequence) != count
            or len(expected_event) != _SEMANTIC_OUTCOME_EVENT_BYTES
            or bytes(event_sha) != expected_event
        ):
            raise GlobalPlacePackageError(
                "semantic outcome database prefix differs from extraction evidence"
            )
        last_node_id = int(node_id)
    if (
        count != expected_rows
        or last_node_id != expected_previous_node_id
        or evidence_handle.tell() != expected_rows * _SEMANTIC_OUTCOME_EVENT_BYTES
    ):
        raise GlobalPlacePackageError(
            "semantic outcome database prefix counters differ"
        )


def _advance_committed_opl_prefix(
    handle: object,
    digest: Any,
    *,
    start: int,
    end: int,
) -> None:
    if type(start) is not int or type(end) is not int or not 0 <= start <= end:
        raise GlobalPlacePackageError("ingest checkpoint OPL prefix range is invalid")
    if handle.tell() != start:
        raise GlobalPlacePackageError("ingest checkpoint OPL prefix cursor differs")
    remaining = end - start
    while remaining:
        chunk = handle.read(min(4 * 1024 * 1024, remaining))
        if not chunk:
            raise GlobalPlacePackageError("ingest checkpoint OPL prefix ends early")
        digest.update(chunk)
        remaining -= len(chunk)
    if handle.tell() != end:
        raise GlobalPlacePackageError("ingest checkpoint OPL prefix length differs")


def _require_open_opl_unchanged(
    handle: object,
    path: Path,
    expected_signature: tuple[int, int, int, int, int],
) -> None:
    handle_status = os.fstat(handle.fileno())
    path_status = path.stat()
    if (
        expected_signature
        != (
            handle_status.st_dev,
            handle_status.st_ino,
            handle_status.st_size,
            handle_status.st_mtime_ns,
            handle_status.st_ctime_ns,
        )
        or (handle_status.st_dev, handle_status.st_ino)
        != (path_status.st_dev, path_status.st_ino)
    ):
        raise GlobalPlacePackageError("open OPL identity drifted during ingestion")


def _commit_ingest_checkpoint(
    connection: sqlite3.Connection,
    database_path: Path,
    checkpoint: dict[str, object],
    audit: dict[str, int],
    peaks: dict[str, int],
    *,
    run_identity: Mapping[str, object],
    semantic_row_counts: Mapping[str, int],
    source_outcome_rows: int,
    source_prefix_sha256: str,
) -> None:
    checkpoint["semanticRowCounts"] = dict(semantic_row_counts)
    checkpoint["sourcePrefixSha256"] = source_prefix_sha256
    checkpoint["sourceOutcomeRows"] = source_outcome_rows
    if source_outcome_rows != int(checkpoint["inputNodes"]):
        raise GlobalPlacePackageError(
            "ingest checkpoint semantic outcome row count differs"
        )
    _require_semantic_table_counts(audit, semantic_row_counts)
    _seal_ingest_checkpoint(
        checkpoint,
        run_identity=run_identity,
        audit=audit,
    )
    _meta_set(connection, "checkpoint", checkpoint)
    _meta_set(connection, "audit", audit)
    _meta_set(connection, "peaks", peaks)
    connection.commit()
    peaks["observedPersistentSqliteBytesAtCheckpoints"] = max(
        peaks["observedPersistentSqliteBytesAtCheckpoints"],
        _database_bytes(database_path),
    )
    _meta_set(connection, "peaks", peaks)
    connection.commit()


def _require_terminal_semantic_admission_audit(
    source_binding: PlaceSourceBinding,
    audit: Mapping[str, int],
) -> None:
    expected = source_binding.semantic_admission
    actual = {
        "controlCapitalValues": audit["controlCapitalValues"],
        "controlEnglishNames": audit["controlEnglishNames"],
        "controlPlaceValues": audit["controlPlaceValues"],
        "controlPopulationValues": audit["controlPopulationValues"],
        "controlPrimaryNames": audit["controlPrimaryNames"],
        "excludedAdmittedControlNodes": audit["excludedAdmittedControlNodes"],
        "inputNodes": audit["inputNodes"],
    }
    required = {
        "controlCapitalValues": expected.control_capital_values,
        "controlEnglishNames": expected.control_english_names,
        "controlPlaceValues": expected.control_place_values,
        "controlPopulationValues": expected.control_population_values,
        "controlPrimaryNames": expected.control_primary_names,
        "excludedAdmittedControlNodes": expected.excluded_node_count,
        "inputNodes": expected.node_count,
    }
    if actual != required:
        raise GlobalPlacePackageError(
            "renderer semantic admission audit differs from its extraction binding"
        )


def _require_terminal_semantic_outcome(
    source_binding: PlaceSourceBinding,
    audit: Mapping[str, int],
    row_counts: Mapping[str, int],
    *,
    source_outcome_rows: int,
    evidence_handle: object,
) -> None:
    expected = source_binding.renderer_semantic_outcome
    expected_rows = {
        "features": expected.feature_rows,
        "records": expected.record_rows,
        "variants": expected.variant_rows,
    }
    if (
        dict(audit) != dict(expected.audit_items)
        or dict(row_counts) != expected_rows
        or source_outcome_rows != expected.node_count
        or evidence_handle.tell()
        != expected.node_count * _SEMANTIC_OUTCOME_EVENT_BYTES
        or evidence_handle.read(1) != b""
    ):
        raise GlobalPlacePackageError(
            "terminal semantic outcome differs from extraction evidence"
        )


def _ingest(
    connection: sqlite3.Connection,
    database_path: Path,
    *,
    opl_path: Path,
    source_binding: PlaceSourceBinding,
    zooms: tuple[int, ...],
    checkpoint_nodes: int,
    pause_after_input_nodes: int | None,
) -> bool:
    checkpoint, audit, peaks, row_counts, run_identity = (
        _validated_ingest_resume_state(connection, source_binding)
    )
    offset = int(checkpoint["nextByteOffset"])
    line_number = int(checkpoint["lineNumber"])
    previous_node_id = int(checkpoint["previousNodeId"])
    total_input = int(checkpoint["inputNodes"])
    source_outcome_rows = int(checkpoint["sourceOutcomeRows"])
    classifier = classifier_identity_sha256()
    since_commit = 0
    with ExitStack() as stack:
        handle = stack.enter_context(opl_path.open("rb"))
        open_signature, prefix_digest = _verified_open_opl(
            handle,
            opl_path,
            source_binding,
            committed_prefix_bytes=offset,
            committed_prefix_sha256=str(checkpoint["sourcePrefixSha256"]),
        )
        outcome_path = Path(source_binding.renderer_semantic_outcome_path)
        outcome_handle = stack.enter_context(outcome_path.open("rb"))
        outcome_signature = _verified_open_semantic_outcome_evidence(
            outcome_handle,
            outcome_path,
            source_binding,
        )
        _require_source_outcome_prefix(
            connection,
            outcome_handle,
            expected_rows=source_outcome_rows,
            expected_previous_node_id=previous_node_id,
        )
        if checkpoint["ingestComplete"] is True:
            _require_open_semantic_outcome_evidence_unchanged(
                outcome_handle, outcome_path, outcome_signature
            )
            _require_terminal_semantic_admission_audit(source_binding, audit)
            _require_terminal_semantic_outcome(
                source_binding,
                audit,
                row_counts,
                source_outcome_rows=source_outcome_rows,
                evidence_handle=outcome_handle,
            )
            return True
        prefix_handle = stack.enter_context(opl_path.open("rb"))
        _require_open_opl_unchanged(prefix_handle, opl_path, open_signature)
        prefix_handle.seek(offset)
        committed_prefix_offset = offset
        handle.seek(offset)
        for parsed in iter_strict_place_opl(
            handle,
            initial_byte_offset=offset,
            initial_line_number=line_number,
            previous_node_id=previous_node_id,
        ):
            node = parsed.node
            total_input += 1
            peaks["inputLineBytes"] = max(
                peaks["inputLineBytes"], parsed.byte_end - parsed.byte_start
            )
            feature, semantic_event = _evaluate_semantic_node(
                parsed,
                source_generation_sha256=source_binding.planet_sha256,
                classifier_sha256=classifier,
                zooms=zooms,
                audit=audit,
            )
            if feature is not None:
                record_delta, feature_delta, variant_delta = _insert_feature(
                    connection,
                    node=node,
                    feature=feature,
                    peaks=peaks,
                )
                row_counts["records"] += record_delta
                row_counts["features"] += feature_delta
                row_counts["variants"] += variant_delta
            expected_event = outcome_handle.read(_SEMANTIC_OUTCOME_EVENT_BYTES)
            if expected_event != semantic_event:
                raise GlobalPlacePackageError(
                    "derived semantic outcome differs from extraction evidence"
                )
            try:
                connection.execute(
                    "INSERT INTO source_outcomes(sequence, node_id, event_sha) "
                    "VALUES (?, ?, ?)",
                    (total_input, node.object_id, semantic_event),
                )
            except sqlite3.IntegrityError as error:
                raise GlobalPlacePackageError(
                    "duplicate semantic outcome database row"
                ) from error
            source_outcome_rows += 1
            checkpoint = {
                "ingestComplete": False,
                "inputNodes": total_input,
                "lineNumber": parsed.line_number,
                "nextByteOffset": parsed.byte_end,
                "previousNodeId": node.object_id,
            }
            offset = parsed.byte_end
            line_number = parsed.line_number
            previous_node_id = node.object_id
            since_commit += 1
            if since_commit >= checkpoint_nodes:
                _advance_committed_opl_prefix(
                    prefix_handle,
                    prefix_digest,
                    start=committed_prefix_offset,
                    end=parsed.byte_end,
                )
                committed_prefix_offset = parsed.byte_end
                _commit_ingest_checkpoint(
                    connection,
                    database_path,
                    checkpoint,
                    audit,
                    peaks,
                    run_identity=run_identity,
                    semantic_row_counts=row_counts,
                    source_outcome_rows=source_outcome_rows,
                    source_prefix_sha256=prefix_digest.hexdigest(),
                )
                since_commit = 0
            if pause_after_input_nodes is not None and total_input >= pause_after_input_nodes:
                _require_open_opl_unchanged(handle, opl_path, open_signature)
                _require_open_semantic_outcome_evidence_unchanged(
                    outcome_handle, outcome_path, outcome_signature
                )
                _advance_committed_opl_prefix(
                    prefix_handle,
                    prefix_digest,
                    start=committed_prefix_offset,
                    end=parsed.byte_end,
                )
                committed_prefix_offset = parsed.byte_end
                _commit_ingest_checkpoint(
                    connection,
                    database_path,
                    checkpoint,
                    audit,
                    peaks,
                    run_identity=run_identity,
                    semantic_row_counts=row_counts,
                    source_outcome_rows=source_outcome_rows,
                    source_prefix_sha256=prefix_digest.hexdigest(),
                )
                return False
        _require_open_opl_unchanged(handle, opl_path, open_signature)
        _require_open_opl_unchanged(prefix_handle, opl_path, open_signature)
        if offset != source_binding.opl_bytes:
            raise GlobalPlacePackageError(
                "strict OPL terminal offset differs from its source byte binding"
            )
        _require_terminal_semantic_admission_audit(source_binding, audit)
        _advance_committed_opl_prefix(
            prefix_handle,
            prefix_digest,
            start=committed_prefix_offset,
            end=offset,
        )
        _require_open_semantic_outcome_evidence_unchanged(
            outcome_handle, outcome_path, outcome_signature
        )
        _require_terminal_semantic_outcome(
            source_binding,
            audit,
            row_counts,
            source_outcome_rows=source_outcome_rows,
            evidence_handle=outcome_handle,
        )
        checkpoint["ingestComplete"] = True
        _commit_ingest_checkpoint(
            connection,
            database_path,
            checkpoint,
            audit,
            peaks,
            run_identity=run_identity,
            semantic_row_counts=row_counts,
            source_outcome_rows=source_outcome_rows,
            source_prefix_sha256=prefix_digest.hexdigest(),
        )
    return True


def _windows(connection: sqlite3.Connection) -> tuple[_Window, ...]:
    rows = connection.execute(
        "SELECT z, MIN(x), MAX(x), MIN(y), MAX(y) FROM records GROUP BY z ORDER BY z"
    ).fetchall()
    if not rows:
        raise GlobalPlacePackageError("no source-valid place labels reached a visible zoom")
    windows = tuple(_Window(*(int(value) for value in row)) for row in rows)
    total_tiles = sum(window.tile_count for window in windows)
    if total_tiles * INDEX_ENTRY_BYTES > _ANDROID_MAX_INDEX_BYTES:
        raise GlobalPlacePackageError("global place Android index exceeds its byte-array bound")
    return windows


def _tiles_index_order(windows: Sequence[_Window]) -> Iterator[TileKey]:
    for window in windows:
        for y in range(window.y_min, window.y_max + 1):
            for x in range(window.x_min, window.x_max + 1):
                yield TileKey(window.z, x, y)


def _tile_ordinal(windows: Sequence[_Window], z: int, x: int, y: int) -> int:
    first = 0
    for window in windows:
        if window.z == z:
            if not (
                window.x_min <= x <= window.x_max
                and window.y_min <= y <= window.y_max
            ):
                raise GlobalPlacePackageError("staged posting lies outside its zoom window")
            return (
                first
                + (y - window.y_min) * (window.x_max - window.x_min + 1)
                + x
                - window.x_min
            )
        first += window.tile_count
    raise GlobalPlacePackageError("staged posting has an undeclared zoom")


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


def _renderer_from_envelope(envelope: bytes) -> bytes:
    if len(envelope) < 4:
        raise GlobalPlacePackageError("staged renderer envelope is truncated")
    renderer_length = struct.unpack_from("<I", envelope, 0)[0]
    if not 0 < renderer_length <= MAX_RENDERER_RECORD_BYTES:
        raise GlobalPlacePackageError("staged renderer envelope length is invalid")
    end = 4 + renderer_length
    if end > len(envelope):
        raise GlobalPlacePackageError("staged renderer envelope ends early")
    return envelope[4:end]


def _record_rows(
    connection: sqlite3.Connection,
    *,
    semantic_order: bool,
) -> Iterator[tuple[object, ...]]:
    coordinate_order = "z, x, y" if semantic_order else "z, y, x"
    yield from connection.execute(
        "SELECT z,y,x,draw_order,priority,layer_group,feature_kind,"
        "variant_id,feature_id,sourced_sha,envelope,subtype "
        f"FROM records ORDER BY {coordinate_order}"
    )


def _group_rows(rows: Iterable[Sequence[object]]) -> Iterator[tuple[TileKey, list[Sequence[object]]]]:
    current_tile = None
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
            raise GlobalPlacePackageError(
                "place tile raw bytes exceed Android bound before materialization"
            )
        current.append(row)
        if len(current) > MAX_RECORDS_PER_TILE:
            raise GlobalPlacePackageError("place tile record count exceeds Android bound")
    if current_tile is not None:
        current.sort(key=_row_order)
        yield current_tile, current


def _semantic_sha256(connection: sqlite3.Connection) -> str:
    digest = hashlib.sha256(_SEMANTIC_STREAM_DOMAIN)
    for tile, rows in _group_rows(_record_rows(connection, semantic_order=True)):
        for row in rows:
            renderer = _renderer_from_envelope(bytes(row[10]))
            body = struct.pack("<Q", tile.packed) + renderer
            digest.update(struct.pack("<I", len(body)))
            digest.update(body)
    return digest.hexdigest()


def _subtype_counts(connection: sqlite3.Connection) -> list[dict[str, object]]:
    features = {
        int(subtype): int(count)
        for subtype, count in connection.execute(
            "SELECT subtype, COUNT(*) FROM features GROUP BY subtype"
        )
    }
    variants = {
        int(subtype): int(count)
        for subtype, count in connection.execute(
            "SELECT subtype, COUNT(*) FROM variants GROUP BY subtype"
        )
    }
    postings = {
        int(subtype): int(count)
        for subtype, count in connection.execute(
            "SELECT subtype, COUNT(*) FROM records GROUP BY subtype"
        )
    }
    return [
        {
            "canonicalVariantIds": variants.get(subtype.value, 0),
            "distinctFeatureIds": features.get(subtype.value, 0),
            "postings": postings.get(subtype.value, 0),
            "semanticSubtype": subtype.value,
            "semanticSubtypeName": subtype.name,
        }
        for subtype in SemanticSubtype
    ]


def _projection_stats(connection: sqlite3.Connection) -> dict[str, object]:
    by_zoom = []
    for row in connection.execute(
        "SELECT z, MIN(x), MAX(x), MIN(y), MAX(y), COUNT(*) "
        "FROM records GROUP BY z ORDER BY z"
    ):
        z, x_min, x_max, y_min, y_max, postings = (int(value) for value in row)
        present = int(
            connection.execute(
                "SELECT COUNT(*) FROM (SELECT 1 FROM records WHERE z = ? GROUP BY x,y)",
                (z,),
            ).fetchone()[0]
        )
        by_zoom.append(
            {
                "postings": postings,
                "presentTiles": present,
                "xMax": x_max,
                "xMin": x_min,
                "yMax": y_max,
                "yMin": y_min,
                "z": z,
            }
        )
    return {"byZoom": by_zoom, "projection": "EPSG:3857-slippy-tile"}


def _payload(tile: TileKey, rows: Sequence[Sequence[object]]) -> bytes:
    payload = b"".join(
        (
            TILE_PAYLOAD_MAGIC,
            struct.pack("<BIII", tile.z, tile.x, tile.y, len(rows)),
            *(bytes(row[10]) for row in rows),
        )
    )
    if len(payload) > MAX_TILE_BYTES:
        raise GlobalPlacePackageError("place tile raw bytes exceed Android bound")
    try:
        decoded = decode_tile_payload(tile, payload)
    except ValueError as error:
        raise GlobalPlacePackageError(
            f"place tile {tile.z}/{tile.x}/{tile.y} fails Android codec parity"
        ) from error
    if len(decoded.records) != len(rows):
        raise GlobalPlacePackageError("place tile Android decode count differs")
    return payload


def _is_link_or_reparse(path: Path) -> bool:
    try:
        status = path.lstat()
    except FileNotFoundError:
        return False
    return path.is_symlink() or bool(
        getattr(status, "st_file_attributes", 0) & 0x400
    )


def _ensure_owned_partial_directory(
    connection: sqlite3.Connection,
    partial_directory: Path,
) -> None:
    expected_owner = {
        "path": os.path.abspath(partial_directory),
        "schema": _PARTIAL_OWNERSHIP_SCHEMA,
    }
    owner = _meta_get(connection, "partialDirectoryOwner")
    exists = partial_directory.exists() or _is_link_or_reparse(partial_directory)
    if owner is None:
        if exists:
            raise GlobalPlacePackageError(
                "partial directory lacks this checkpoint database ownership"
            )
        partial_directory.mkdir(parents=True, exist_ok=False)
        if _is_link_or_reparse(partial_directory) or not partial_directory.is_dir():
            raise GlobalPlacePackageError(
                "partial directory ownership target is not one real directory"
            )
        _meta_set(connection, "partialDirectoryOwner", expected_owner)
        connection.commit()
        return
    if owner != expected_owner:
        raise GlobalPlacePackageError("partial directory ownership identity differs")
    if (
        not exists
        or _is_link_or_reparse(partial_directory)
        or not partial_directory.is_dir()
    ):
        raise GlobalPlacePackageError("owned partial directory is missing or redirected")


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
        raise GlobalPlacePackageError(f"build checkpoint {label} prefix is malformed")
    exists = path.exists() or _is_link_or_reparse(path)
    if exists and (_is_link_or_reparse(path) or not path.is_file()):
        raise GlobalPlacePackageError(
            f"build checkpoint {label} file is not one regular non-link file"
        )
    if not exists and prefix_bytes:
        raise GlobalPlacePackageError(f"build checkpoint {label} file is missing")
    mode = "r+b" if exists else "x+b"
    try:
        handle = path.open(mode)
    except OSError as error:
        raise GlobalPlacePackageError(
            f"build checkpoint {label} file could not be opened"
        ) from error
    try:
        if _is_link_or_reparse(path):
            raise GlobalPlacePackageError(
                f"build checkpoint {label} file was redirected while opening"
            )
        handle_status = os.fstat(handle.fileno())
        path_status = path.stat()
        if (
            not stat.S_ISREG(handle_status.st_mode)
            or (handle_status.st_dev, handle_status.st_ino)
            != (path_status.st_dev, path_status.st_ino)
            or handle_status.st_size < prefix_bytes
        ):
            raise GlobalPlacePackageError(
                f"build checkpoint {label} prefix length or identity differs"
            )
        digest = hashlib.sha256()
        remaining = prefix_bytes
        handle.seek(0)
        while remaining:
            chunk = handle.read(min(1024 * 1024, remaining))
            if not chunk:
                raise GlobalPlacePackageError(
                    f"build checkpoint {label} prefix ends early"
                )
            digest.update(chunk)
            remaining -= len(chunk)
        if digest.hexdigest() != prefix_sha256:
            raise GlobalPlacePackageError(
                f"build checkpoint {label} prefix SHA-256 differs"
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
    build_checkpoint = dict(
        _meta_get(connection, "buildCheckpoint")
        or {
            "indexBytes": 0,
            "indexSha256": _EMPTY_SHA256,
            "nextOrdinal": 0,
            "recordsBytes": 0,
            "recordsSha256": _EMPTY_SHA256,
        }
    )
    if set(build_checkpoint) != {
        "indexBytes",
        "indexSha256",
        "nextOrdinal",
        "recordsBytes",
        "recordsSha256",
    }:
        raise GlobalPlacePackageError("build checkpoint runtime file fields differ")
    next_ordinal = int(build_checkpoint["nextOrdinal"])
    records_bytes = int(build_checkpoint["recordsBytes"])
    index_bytes = int(build_checkpoint["indexBytes"])
    expected_total = sum(window.tile_count for window in windows)
    if index_bytes != next_ordinal * INDEX_ENTRY_BYTES:
        raise GlobalPlacePackageError("build checkpoint index offset is inconsistent")
    peaks = dict(_meta_get(connection, "peaks") or _empty_peaks())
    present_tiles = int(
        connection.execute(
            "SELECT COUNT(*) FROM (SELECT 1 FROM records GROUP BY z,x,y)"
        ).fetchone()[0]
    )
    grouped = iter(_group_rows(_record_rows(connection, semantic_order=False)))
    staged = next(grouped, None)
    while staged is not None and _tile_ordinal(
        windows, staged[0].z, staged[0].x, staged[0].y
    ) < next_ordinal:
        staged = next(grouped, None)
    with ExitStack() as stack:
        records_handle, records_digest = _open_verified_runtime_file(
            records_path,
            prefix_bytes=records_bytes,
            prefix_sha256=build_checkpoint["recordsSha256"],
            label="records",
        )
        stack.callback(records_handle.close)
        index_handle, index_digest = _open_verified_runtime_file(
            index_path,
            prefix_bytes=index_bytes,
            prefix_sha256=build_checkpoint["indexSha256"],
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
                    raise GlobalPlacePackageError(
                        "place tile compressed bytes exceed Android bound"
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
            raise GlobalPlacePackageError("staged records remain outside package traversal")
        records_handle.flush()
        index_handle.flush()
        os.fsync(records_handle.fileno())
        os.fsync(index_handle.fileno())
    if index_bytes != expected_total * INDEX_ENTRY_BYTES:
        raise GlobalPlacePackageError("place index length differs from declared coverage")
    if records_path.stat().st_size != records_bytes or index_path.stat().st_size != index_bytes:
        raise GlobalPlacePackageError("place runtime file length differs after flush")
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


def _runtime_manifest(
    *,
    package_id: str,
    windows: Sequence[_Window],
    present_tiles: int,
    semantic_sha256: str,
    run_identity_sha256: str,
    source_binding: PlaceSourceBinding,
    records_identity: Mapping[str, object],
    index_identity: Mapping[str, object],
) -> dict[str, object]:
    total_tiles = sum(window.tile_count for window in windows)
    return {
        "compatibility": {"emptyPresentTilesSharePayload": False},
        "coverage": {
            "completeDeclaredScope": present_tiles == total_tiles,
            "completeWholeEarthDictionary": False,
            "tileCount": total_tiles,
            "zoomRanges": [window.document() for window in windows],
        },
        "globalPlaceSupplement": {
            "buildSchema": _BUILD_SCHEMA,
            "records": dict(records_identity),
            "runIdentitySha256": run_identity_sha256,
            "source": source_binding.document(),
            "tileIndex": dict(index_identity),
        },
        "packageId": package_id,
        "payloadSchema": PAYLOAD_SCHEMA,
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "rendererSemanticStreamSha256": semantic_sha256,
        "schemaVersion": 3,
        "sourcedTextPolicySha256": SOURCED_TEXT_POLICY_SHA256,
        "unicodeScriptProfileSha256": UNICODE_SCRIPT_PROFILE_SHA256,
    }


def _output_file(path: Path) -> dict[str, object]:
    identity = _stream_identity(path)
    return {"bytes": identity["bytes"], "name": path.name, "sha256": identity["sha256"]}


def _publish(
    connection: sqlite3.Connection,
    database_path: Path,
    *,
    partial_directory: Path,
    output_directory: Path,
    package_id: str,
    source_binding: PlaceSourceBinding,
    run_identity_document: Mapping[str, object],
    run_identity_sha256: str,
    code_identities: Mapping[str, object],
    windows: Sequence[_Window],
    checkpoint_nodes: int,
) -> Mapping[str, object]:
    if _code_identities() != dict(code_identities):
        raise GlobalPlacePackageError("renderer code identity drifted during the build")
    semantic_sha256 = _semantic_sha256(connection)
    subtype_counts = _subtype_counts(connection)
    projection = _projection_stats(connection)
    _, present_tiles, peaks = _build_runtime_files(
        connection, database_path, partial_directory, windows
    )
    records_identity = _stream_identity(partial_directory / "records.fadictpack")
    index_identity = _stream_identity(partial_directory / "tile-index.bin")
    manifest = _runtime_manifest(
        package_id=package_id,
        windows=windows,
        present_tiles=present_tiles,
        semantic_sha256=semantic_sha256,
        run_identity_sha256=run_identity_sha256,
        source_binding=source_binding,
        records_identity=records_identity,
        index_identity=index_identity,
    )
    manifest_bytes = _canonical_json_bytes(manifest)
    (partial_directory / "manifest.json").write_bytes(manifest_bytes)
    catalog_input = {
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "rendererSemanticStreamSha256": semantic_sha256,
        "schema": _CATALOG_INPUT_SCHEMA,
        "subtypeCounts": subtype_counts,
    }
    (partial_directory / "class-catalog-input.json").write_bytes(
        _canonical_json_bytes(catalog_input)
    )
    audit = dict(_meta_get(connection, "audit") or _empty_audit())
    total_tiles = sum(window.tile_count for window in windows)
    receipt: dict[str, object] = {
        "build": {
            "checkpointNodes": checkpoint_nodes,
            "classifierSha256": run_identity_document["classifierSha256"],
            "code": dict(code_identities),
            "runIdentity": dict(run_identity_document),
            "runIdentitySha256": run_identity_sha256,
        },
        "coverage": {
            "declaredIndexEntries": total_tiles,
            "presentTileCount": present_tiles,
            "zooms": [window.z for window in windows],
        },
        "outputFiles": [],
        "packageId": package_id,
        "peakResources": {
            **peaks,
            "androidIndexByteCeiling": _ANDROID_MAX_INDEX_BYTES,
            "androidRecordsPerTileCeiling": MAX_RECORDS_PER_TILE,
            "androidRawTileByteCeiling": MAX_TILE_BYTES,
            "checkpointBatchNodes": checkpoint_nodes,
            "indexBytes": int(index_identity["bytes"]),
            "oplLineByteCeiling": 4 * 1024 * 1024,
            "oplReadChunkBytes": 1024 * 1024,
            "recordsBytes": int(records_identity["bytes"]),
            "measurementScope": {
                "persistentSqliteSampling": "database and visible journal/WAL/SHM files after checkpoint commits",
                "processPeakRssMeasured": False,
                "transientSqliteTempFilesIncluded": False,
            },
            "sqliteCacheTargetBytes": 64 * 1024 * 1024,
        },
        "projection": projection,
        "rendererSemanticStreamSha256": semantic_sha256,
        "schema": _BUILD_SCHEMA,
        "source": source_binding.document(),
        "sourceAudit": audit,
        "subtypeCounts": subtype_counts,
    }
    receipt["outputFiles"] = [
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
        _output_file(partial_directory / "class-catalog-input.json"),
    ]
    receipt_bytes = _canonical_json_bytes(receipt)
    (partial_directory / "build-receipt.json").write_bytes(receipt_bytes)
    if output_directory.exists() or _is_link_or_reparse(output_directory):
        raise GlobalPlacePackageError("global place output directory already exists")
    if _code_identities() != dict(code_identities):
        raise GlobalPlacePackageError("renderer code identity drifted before publication")
    try:
        os.replace(partial_directory, output_directory)
    except OSError as error:
        raise GlobalPlacePackageError("atomic global place publication failed") from error
    expected = {
        item["name"]: (item["bytes"], item["sha256"])
        for item in receipt["outputFiles"]
    }
    for name, (size, digest) in expected.items():
        verify_file_identity(
            output_directory / str(name),
            expected_bytes=int(size),
            expected_sha256=str(digest),
        )
    with (output_directory / "build-receipt.json").open("rb") as receipt_handle:
        published_receipt = receipt_handle.read(len(receipt_bytes) + 1)
    if published_receipt != receipt_bytes:
        raise GlobalPlacePackageError("published build receipt differs after atomic rename")
    return MappingProxyType(receipt)


def render_global_place_package(
    *,
    opl_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: PlaceSourceBinding,
    zooms: Sequence[int] = _DEFAULT_ZOOMS,
    checkpoint_nodes: int = 10_000,
    pause_after_input_nodes: int | None = None,
) -> GlobalPlaceBuildResult:
    """Render strict global place OPL through bounded SQLite into one atomic V3 supplement."""

    if not isinstance(opl_path, Path) or not isinstance(output_directory, Path) or not isinstance(
        work_directory, Path
    ):
        raise GlobalPlacePackageError("global place paths must be pathlib.Path values")
    if not isinstance(source_binding, PlaceSourceBinding):
        raise GlobalPlacePackageError("global place source binding is invalid")
    checked_package_id = _validated_package_id(package_id)
    normalized_zooms = tuple(zooms)
    if normalized_zooms != _DEFAULT_ZOOMS:
        raise GlobalPlacePackageError("global place supplement zooms must be exactly z4..11")
    if type(checkpoint_nodes) is not int or checkpoint_nodes <= 0:
        raise GlobalPlacePackageError("checkpoint node count must be positive")
    if pause_after_input_nodes is not None and (
        type(pause_after_input_nodes) is not int or pause_after_input_nodes <= 0
    ):
        raise GlobalPlacePackageError("pause node count must be positive when supplied")
    if output_directory.exists() or _is_link_or_reparse(output_directory):
        raise GlobalPlacePackageError("global place output directory already exists")
    if not opl_path.is_file() or opl_path.is_symlink():
        raise GlobalPlacePackageError("global place OPL is not one regular non-link file")
    if opl_path.stat().st_size != source_binding.opl_bytes:
        raise GlobalPlacePackageError("global place OPL byte length differs before ingestion")
    code_identities = _code_identities()
    run_document = _run_identity_document(
        package_id=checked_package_id,
        source_binding=source_binding,
        zooms=normalized_zooms,
        checkpoint_nodes=checkpoint_nodes,
        code_identities=code_identities,
    )
    run_sha256 = hashlib.sha256(_canonical_json_bytes(run_document)).hexdigest()
    work_directory.mkdir(parents=True, exist_ok=True)
    database_path = work_directory / "place-state.sqlite"
    partial_directory = output_directory.with_name(
        output_directory.name + ".partial-" + run_sha256[:16]
    )
    connection = _open_database(database_path, run_document)
    try:
        complete = _ingest(
            connection,
            database_path,
            opl_path=opl_path,
            source_binding=source_binding,
            zooms=normalized_zooms,
            checkpoint_nodes=checkpoint_nodes,
            pause_after_input_nodes=pause_after_input_nodes,
        )
        if not complete:
            checkpoint = _meta_get(connection, "checkpoint")
            paused_receipt = MappingProxyType(
                {
                    "checkpoint": checkpoint,
                    "runIdentitySha256": run_sha256,
                    "schema": _BUILD_SCHEMA,
                    "state": "paused",
                }
            )
            return GlobalPlaceBuildResult("paused", output_directory, paused_receipt)
        windows = _windows(connection)
        receipt = _publish(
            connection,
            database_path,
            partial_directory=partial_directory,
            output_directory=output_directory,
            package_id=checked_package_id,
            source_binding=source_binding,
            run_identity_document=run_document,
            run_identity_sha256=run_sha256,
            code_identities=code_identities,
            windows=windows,
            checkpoint_nodes=checkpoint_nodes,
        )
        return GlobalPlaceBuildResult("complete", output_directory, receipt)
    finally:
        connection.close()


__all__ = [
    "GlobalPlaceBuildResult",
    "PlaceSourceBinding",
    "render_global_place_package",
]
