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
from typing import BinaryIO, Callable, Iterator, Mapping, Sequence

from . import osm_global_waterway_package as source_module
from . import reference_size_policy as size_policy_module
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


_RUN_SCHEMA = "flightalert.experiment8.osm-global-waterway-ingest-run.v2"
_INGEST_SCHEMA = "flightalert.experiment8.osm-global-waterway-ingest.v2"
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
_SOURCE_FEATURE_DOMAIN = b"FAE8OSMGLOBALWATERWAYFEATURE3\0"
_CANDIDATE_FEATURE_DOMAIN = b"FAE8WATERCANDIDATEFEATURE2\0"
_CANDIDATE_STREAM_DOMAIN = b"FAE8WATERCANDIDATESTREAM2\0"
_ADMISSION_STREAM_DOMAIN = b"FAE8WATERADMISSION2\0"
_DEPENDENCY_EVIDENCE_DOMAIN = b"FAE8OSMWATERWAYDEPENDENCIES1\0"
_MAX_DEPENDENCY_RECORD_BYTES = 128 * 1024 * 1024
_MAX_DEPENDENCY_RECORDS = (1 << 64) - 1
_RELATION_POINT_ACCOUNTING_BYTES = struct.calcsize(">Qii")
_MAX_RELATION_DEPTH = source_module._MAX_LINE_BYTES // (1024 * 1024)
_MAX_RELATION_VISITS = source_module._MAX_LINE_BYTES // 1024
_MAX_RELATION_PARTS = MAX_RECORDS_PER_TILE
_MAX_RELATION_POINTS = MAX_RENDERER_RECORD_BYTES // _RELATION_POINT_ACCOUNTING_BYTES
_BUILD_SCHEMA = "flightalert.experiment8.osm-global-waterway-build.v2"
_RENDER_RUN_SCHEMA = "flightalert.experiment8.osm-global-waterway-render-run.v2"
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_DEFAULT_ZOOMS = tuple(range(4, 12))
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_PREFERRED_PHONE_FOOTPRINT_BYTES = (
    size_policy_module.PREFERRED_MANDATORY_PHONE_FOOTPRINT_BYTES
)
_HARD_PHONE_FOOTPRINT_BYTES = (
    size_policy_module.HARD_MANDATORY_PHONE_FOOTPRINT_BYTES
)
_PREFERRED_COMPONENT_PACKAGE_BYTES = (
    size_policy_module.PREFERRED_COMPONENT_PACKAGE_BYTES
)
_HARD_COMPONENT_PACKAGE_BYTES = size_policy_module.HARD_COMPONENT_PACKAGE_BYTES
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
_ROOT_STATUS_ORDER = (
    "fatal",
    "line_candidates_with_noncandidate_members",
    "line_candidates",
    "no_line_geometry",
    "no_supported_line_candidate",
)
_REASON_CODE_ORDER = (
    "relation_cycle",
    "unsupported_relation_waterway",
    "unsupported_member_way_waterway",
)
_LEGACY_REASON_ORDER = (
    "unsupported_relation_waterway",
    "unsupported_member_way_waterway",
    "no_usable_way_geometry",
    "relation_cycle",
)
_SUPPORTED_WATERWAY_ORDER = ("river", "stream", "canal", "tidal_channel", "wadi")
_ADMISSION_CHECKPOINT_ROOTS = 100
_MAX_ADMISSION_ENTRY_BYTES = 128 * 1024 * 1024
_MAX_STRUCTURAL_MEMBER_OCCURRENCES = 524_288
_MAX_STRUCTURAL_WAY_NODE_OCCURRENCES = 8_388_608
_LEGACY_PRODUCTION_COUNTS = {
    "unsupported_relation_waterway": 9_304,
    "unsupported_member_way_waterway": 1_357,
    "no_usable_way_geometry": 12,
    "relation_cycle": 1,
}
_LEGACY_PRODUCTION_LEDGER_BYTES = 94_632
_LEGACY_PRODUCTION_LEDGER_SHA256 = (
    "e63bb8e77f0fa8c432492028ba5a00b6da43a10cbd7cabdf10ad4435b0c422f2"
)
_PRODUCTION_STRUCTURAL_NO_WAY_COUNT = 19


@dataclass(frozen=True, slots=True)
class _AdmissionLimits:
    max_structural_depth: int
    max_structural_relation_visits: int
    max_structural_member_occurrences: int
    max_structural_way_node_occurrences: int
    max_candidate_depth: int
    max_candidate_relation_visits: int
    max_candidate_raw_parts: int
    max_candidate_points: int
    max_dependency_record_bytes: int
    max_dependency_records: int
    max_admission_entry_bytes: int

    @classmethod
    def production(cls) -> "_AdmissionLimits":
        return cls(
            max_structural_depth=_MAX_RELATION_DEPTH,
            max_structural_relation_visits=_MAX_RELATION_VISITS,
            max_structural_member_occurrences=_MAX_STRUCTURAL_MEMBER_OCCURRENCES,
            max_structural_way_node_occurrences=_MAX_STRUCTURAL_WAY_NODE_OCCURRENCES,
            max_candidate_depth=_MAX_RELATION_DEPTH,
            max_candidate_relation_visits=_MAX_RELATION_VISITS,
            max_candidate_raw_parts=_MAX_RELATION_PARTS,
            max_candidate_points=_MAX_RELATION_POINTS,
            max_dependency_record_bytes=_MAX_DEPENDENCY_RECORD_BYTES,
            max_dependency_records=_MAX_DEPENDENCY_RECORDS,
            max_admission_entry_bytes=_MAX_ADMISSION_ENTRY_BYTES,
        )


def _line_candidate_policy_document() -> dict[str, object]:
    limits = _AdmissionLimits.production()
    return {
        "candidateTraversal": {
            "cycleCheckBeforeBudget": True,
            "cycleOccurrence": "reason-and-join-barrier-without-recursion",
            "excludedBranchesConsumeGeometryBudget": False,
            "joinRule": "ordered-dfs-adjacent-same-effective-type-shared-endpoint-node-id",
            "nodeMembers": "audit-evidence-without-line-or-join-barrier",
            "reasonOccurrenceOrder": "ordered-depth-first-source-path",
            "relationTypePrecedence": [
                "exact-supported-declaration",
                "nearest-inherited-supported-type",
            ],
            "wayTypePrecedence": [
                "nearest-effective-relation-type",
                "exact-way-waterway",
            ],
            "supportedWayAccounting": (
                "charge raw part and all raw points before joining"
            ),
            "unsupportedRelationDeclaration": (
                "reason-and-join-barrier-without-candidate-descent"
            ),
            "unsupportedWayWithoutEffectiveRelationType": (
                "reason-and-join-barrier-without-coordinate-materialization"
            ),
        },
        "canonicalEvidence": {
            "admissionStreamDomain": "FAE8WATERADMISSION2\0",
            "candidateFeatureDomain": "FAE8WATERCANDIDATEFEATURE2\0",
            "candidateStreamDomain": "FAE8WATERCANDIDATESTREAM2\0",
            "candidateStreamEncoding": (
                "uint64-be length plus 32-byte candidate feature SHA-256"
            ),
            "dependencyEvidenceDomain": "FAE8OSMWATERWAYDEPENDENCIES1\0",
            "dependencyRecordEncoding": (
                "sorted-key compact UTF-8 with exactly one terminal LF"
            ),
            "dependencyStreamEncoding": (
                "domain then repeated uint64-be record byte length plus record bytes"
            ),
            "entryEncoding": "sorted-key compact UTF-8 without BOM or terminal LF",
            "featureMetadataEncoding": (
                "domain then uint64-be metadata byte length plus sorted-key compact UTF-8 "
                "without BOM or terminal LF"
            ),
            "framing": "uint64-be byte length followed by bytes",
            "geometryEncoding": (
                "uint64-be part count; per part P plus uint64-be point count; repeated "
                "uint64-be node ID plus int32-be longitude E7 plus int32-be latitude E7"
            ),
            "histogramUnits": {
                "reasonHistogram": "occurrences",
                "rootStatusHistogram": "roots",
            },
            "sourcePathEncoding": (
                "root [kindCode,id,-1], then source occurrences "
                "[kindCode,id,memberOrdinal]"
            ),
            "sourceFeatureDomain": "FAE8OSMGLOBALWATERWAYFEATURE3\0",
        },
        "completeness": {
            "incompletePlacementSource": "DIRECT_SOURCE_PATH",
            "relationCompleteWhen": (
                "exactly one supported candidate type and no excluded or cycle line occurrence"
            ),
            "relationIncompleteOmits": [
                "COMPLETE_RELATION_LENGTH",
                "complete-geometry prominence",
                "EXACT_PARENT_PATH",
            ],
            "selectedZeroCandidateMeaning": "exact-source-occurrence-not-semantic-empty",
        },
        "geometry": {
            "accounting": {
                "candidateFeatures": "post-join parts and points",
                "relationDepthAndVisits": "maximum-depth and occurrence visits",
                "supportedWays": "raw pre-join parts and points",
            },
            "limits": {
                "maxAdmissionEntryBytes": limits.max_admission_entry_bytes,
                "maxCandidateDepth": limits.max_candidate_depth,
                "maxCandidatePoints": limits.max_candidate_points,
                "maxCandidateRawParts": limits.max_candidate_raw_parts,
                "maxCandidateRelationVisits": limits.max_candidate_relation_visits,
                "maxDependencyRecordBytes": limits.max_dependency_record_bytes,
                "maxDependencyRecords": limits.max_dependency_records,
                "maxStructuralDepth": limits.max_structural_depth,
                "maxStructuralMemberOccurrences": limits.max_structural_member_occurrences,
                "maxStructuralRelationVisits": limits.max_structural_relation_visits,
                "maxStructuralWayNodeOccurrences": limits.max_structural_way_node_occurrences,
            },
            "resourceFailure": "fatal",
        },
        "legacyFirstTerminalReasonOrder": list(_LEGACY_REASON_ORDER),
        "reasonCodes": list(_REASON_CODE_ORDER),
        "rootKindOrder": [
            {"code": 1, "kind": "way"},
            {"code": 2, "kind": "relation"},
        ],
        "rootStatuses": list(_ROOT_STATUS_ORDER),
        "schema": "flightalert.experiment8.osm-waterway-line-candidate-policy.v2",
        "structuralTraversal": {
            "cycleEdge": "record-and-do-not-recurse",
            "fatalContradictions": [
                "missing-object",
                "malformed-or-noncontiguous-ordinal",
                "member-way-fewer-than-two-nodes",
                "unknown-member-object-kind",
                "selected-root-name-or-predicate-contradiction",
                "resource-ceiling",
            ],
            "scope": "complete reachable closure including candidate-pruned branches",
            "unsupportedWaterwayValue": (
                "audited-candidate-noncandidate-not-structural-fatal"
            ),
        },
        "supportedWaterways": list(_SUPPORTED_WATERWAY_ORDER),
    }


class _DependencyEvidence:
    __slots__ = ("_digest", "_limits", "_records")

    def __init__(self, limits: _AdmissionLimits | None = None) -> None:
        self._limits = limits or _AdmissionLimits.production()
        if not isinstance(self._limits, _AdmissionLimits):
            raise GlobalWaterwayPackageError(
                "waterway dependency evidence limits are invalid"
            )
        self._digest = hashlib.sha256()
        self._digest.update(_DEPENDENCY_EVIDENCE_DOMAIN)
        self._records = 0

    def add(self, record: Sequence[object]) -> None:
        if self._records >= self._limits.max_dependency_records:
            raise GlobalWaterwayPackageError(
                "waterway dependency record count exceeds its exact ceiling"
            )
        encoded = _canonical_json_bytes(list(record))
        if len(encoded) > self._limits.max_dependency_record_bytes:
            raise GlobalWaterwayPackageError(
                "one waterway dependency record exceeds its exact byte ceiling"
            )
        self._digest.update(struct.pack(">Q", len(encoded)))
        self._digest.update(encoded)
        self._records += 1

    def document(self) -> dict[str, object]:
        return {"records": self._records, "sha256": self._digest.hexdigest()}


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


def _canonical_json_no_lf_bytes(value: object) -> bytes:
    raw = _canonical_json_bytes(value)
    if not raw.endswith(b"\n") or raw.endswith(b"\r\n"):
        raise GlobalWaterwayPackageError("waterway store JSON LF contract differs")
    return raw[:-1]


LINE_CANDIDATE_POLICY_SHA256 = hashlib.sha256(
    _canonical_json_bytes(_line_candidate_policy_document())
).hexdigest()


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


def _sqlite_blob(value: object, label: str) -> bytes:
    if not isinstance(value, (bytes, bytearray, memoryview)):
        raise GlobalWaterwayPackageError(
            f"waterway {label} is not canonical SQLite BLOB evidence"
        )
    return bytes(value)


def _run_identity(
    source_binding: WaterwaySourceBinding,
    checkpoint_objects: int,
    checkpoint_admission_roots: int,
) -> dict[str, object]:
    code = {
        "source": _stream_identity(Path(source_module.__file__).resolve(), "waterway source code"),
        "store": _stream_identity(Path(__file__).resolve(), "waterway store code"),
    }
    return {
        "admissionCheckpointRoots": checkpoint_admission_roots,
        "admissionPolicy": {
            "document": _line_candidate_policy_document(),
            "sha256": LINE_CANDIDATE_POLICY_SHA256,
        },
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
            "CREATE TABLE admission_roots(root_kind INTEGER NOT NULL,root_id INTEGER NOT NULL,"
            "entry_json BLOB NOT NULL,entry_sha BLOB NOT NULL,framed_bytes INTEGER NOT NULL,"
            "status TEXT NOT NULL,candidate_stream_sha BLOB NOT NULL,"
            "PRIMARY KEY(root_kind,root_id)) WITHOUT ROWID;"
            "CREATE TABLE admission_candidates(root_kind INTEGER NOT NULL,root_id INTEGER NOT NULL,"
            "feature_ordinal INTEGER NOT NULL,waterway_type TEXT NOT NULL,"
            "complete_named_relation INTEGER NOT NULL,candidate_feature_sha BLOB NOT NULL,"
            "source_feature_sha BLOB NOT NULL,part_count INTEGER NOT NULL,point_count INTEGER NOT NULL,"
            "PRIMARY KEY(root_kind,root_id,feature_ordinal),"
            "UNIQUE(root_kind,root_id,waterway_type)) WITHOUT ROWID;"
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


def _reference_size_policy_binding(mode: object) -> Mapping[str, object]:
    try:
        immutable = size_policy_module.reference_size_policy_binding(mode)
        return MappingProxyType(
            json.loads(
                size_policy_module._canonical_json_bytes(immutable).decode(
                    "utf-8", "strict"
                )
            )
        )
    except size_policy_module.ReferenceSizePolicyError as error:
        raise GlobalWaterwayPackageError(str(error)) from error


def enforce_global_waterway_storage_ceiling(
    package_bytes: int,
    *,
    size_policy_mode: object = size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE,
    available_destination_bytes: int | None = None,
) -> Mapping[str, object]:
    try:
        decision = size_policy_module.evaluate_reference_size_policy(
            mode=size_policy_mode,
            required_package_bytes=package_bytes,
            available_destination_bytes=available_destination_bytes,
        )
    except size_policy_module.ReferenceSizePolicyError as error:
        raise GlobalWaterwayPackageError(str(error)) from error
    if not decision["authorized"]:
        if decision["mode"] == size_policy_module.BUDGETED_RELEASE_V1:
            raise GlobalWaterwayPackageError(
                "global waterway component must remain below 38,500,000,000 bytes"
            )
        raise GlobalWaterwayPackageError(
            "complete uncompressed visual evaluation lacks required destination "
            "capacity plus 1,500,000,000-byte reserve"
        )
    return decision


def _bind_publication_boundary_capacity(
    decision: Mapping[str, object],
    *,
    destination_free_bytes: int,
) -> Mapping[str, object]:
    if type(destination_free_bytes) is not int or destination_free_bytes < 0:
        raise GlobalWaterwayPackageError(
            "visual-evaluation publication-boundary capacity is malformed"
        )
    finalized = dict(decision)
    boundary_authorized = (
        destination_free_bytes >= size_policy_module.DESTINATION_RESERVE_BYTES
    )
    finalized.update(
        {
            "authorized": bool(finalized["authorized"]) and boundary_authorized,
            "publicationBoundaryAuthorized": boundary_authorized,
            "publicationBoundaryDestinationFreeBytes": destination_free_bytes,
            "publicationBoundaryRequiredReserveBytes": (
                size_policy_module.DESTINATION_RESERVE_BYTES
            ),
        }
    )
    if not finalized["authorized"]:
        raise GlobalWaterwayPackageError(
            "complete uncompressed visual evaluation lacks required destination "
            "capacity plus 1,500,000,000-byte reserve"
        )
    return MappingProxyType(finalized)


def _filesystem_status_signature(status: os.stat_result) -> tuple[int, ...]:
    return (
        int(status.st_dev),
        int(status.st_ino),
        int(status.st_mode),
        int(status.st_size),
        int(status.st_mtime_ns),
    )


def _owned_partial_entry_inventory(
    connection: sqlite3.Connection,
    *,
    partial_directory: Path,
) -> tuple[tuple[int, ...], tuple[tuple[object, ...], ...]]:
    expected_owner = {
        "path": os.path.abspath(partial_directory),
        "schema": _PARTIAL_OWNER_SCHEMA,
    }
    if _meta_get(connection, "partialDirectoryOwner") != expected_owner:
        raise GlobalWaterwayPackageError(
            "visual-evaluation partial capacity lacks exact ownership"
        )
    try:
        directory_status_before = os.lstat(partial_directory)
        entries = tuple(partial_directory.iterdir())
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "visual-evaluation owned partial inventory is unreadable"
        ) from error
    if (
        not stat.S_ISDIR(directory_status_before.st_mode)
        or getattr(directory_status_before, "st_file_attributes", 0)
        & _REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(
            "visual-evaluation owned partial directory is redirected"
        )
    allowed = {
        "build-receipt.json",
        "manifest.json",
        "records.fadictpack",
        "tile-index.bin",
    }
    inventory = []
    for entry in entries:
        try:
            status = os.lstat(entry)
        except OSError as error:
            raise GlobalWaterwayPackageError(
                "visual-evaluation owned partial inventory is unreadable"
            ) from error
        if (
            entry.name not in allowed
            or not stat.S_ISREG(status.st_mode)
            or getattr(status, "st_file_attributes", 0) & _REPARSE_POINT
        ):
            raise GlobalWaterwayPackageError(
                "visual-evaluation owned partial inventory differs"
            )
        inventory.append((entry.name, *_filesystem_status_signature(status)))
    try:
        directory_status_after = os.lstat(partial_directory)
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "visual-evaluation owned partial inventory is unreadable"
        ) from error
    if _filesystem_status_signature(
        directory_status_before
    ) != _filesystem_status_signature(directory_status_after):
        raise GlobalWaterwayPackageError(
            "visual-evaluation partial inventory changed while measuring capacity"
        )
    return (
        _filesystem_status_signature(directory_status_after),
        tuple(sorted(inventory)),
    )


def _owned_partial_entry_bytes(
    connection: sqlite3.Connection,
    *,
    partial_directory: Path,
) -> int:
    _, inventory = _owned_partial_entry_inventory(
        connection,
        partial_directory=partial_directory,
    )
    return sum(int(entry[4]) for entry in inventory)


def _current_visual_staging_capacity_evidence(
    connection: sqlite3.Connection,
    *,
    partial_directory: Path,
    output_directory: Path,
    size_policy_mode: object,
) -> tuple[int, int] | None:
    if (
        size_policy_mode
        != size_policy_module.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    ):
        return None
    before = _owned_partial_entry_inventory(
        connection,
        partial_directory=partial_directory,
    )
    try:
        free_bytes = size_policy_module.destination_available_bytes(output_directory)
    except size_policy_module.ReferenceSizePolicyError as error:
        raise GlobalWaterwayPackageError(str(error)) from error
    after = _owned_partial_entry_inventory(
        connection,
        partial_directory=partial_directory,
    )
    if after != before:
        raise GlobalWaterwayPackageError(
            "visual-evaluation partial inventory changed while measuring capacity"
        )
    partial_bytes = sum(int(entry[4]) for entry in after[1])
    return partial_bytes, free_bytes + partial_bytes


def _current_visual_staging_capacity(
    connection: sqlite3.Connection,
    *,
    partial_directory: Path,
    output_directory: Path,
    size_policy_mode: object,
) -> int | None:
    evidence = _current_visual_staging_capacity_evidence(
        connection,
        partial_directory=partial_directory,
        output_directory=output_directory,
        size_policy_mode=size_policy_mode,
    )
    return None if evidence is None else evidence[1]


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




@dataclass(slots=True)
class _StructuralUsage:
    limits: _AdmissionLimits
    max_depth: int = 0
    relation_visits: int = 0
    member_occurrences: int = 0
    way_node_occurrences: int = 0
    cycle_edges: int = 0
    has_reachable_way: bool = False

    def enter_relation(self, depth: int) -> None:
        if depth > self.limits.max_structural_depth:
            raise GlobalWaterwayPackageError(
                "waterway structural depth ceiling exceeded"
            )
        if self.relation_visits >= self.limits.max_structural_relation_visits:
            raise GlobalWaterwayPackageError(
                "waterway structural relation-visit ceiling exceeded"
            )
        self.relation_visits += 1
        self.max_depth = max(self.max_depth, depth)

    def member(self) -> None:
        if self.member_occurrences >= self.limits.max_structural_member_occurrences:
            raise GlobalWaterwayPackageError(
                "waterway structural member-occurrence ceiling exceeded"
            )
        self.member_occurrences += 1

    def way_node(self) -> None:
        if self.way_node_occurrences >= self.limits.max_structural_way_node_occurrences:
            raise GlobalWaterwayPackageError(
                "waterway structural way-node-occurrence ceiling exceeded"
            )
        self.way_node_occurrences += 1

    def document(self) -> dict[str, int | bool]:
        return {
            "cycleEdges": self.cycle_edges,
            "hasReachableWay": self.has_reachable_way,
            "maxDepth": self.max_depth,
            "memberOccurrences": self.member_occurrences,
            "relationVisits": self.relation_visits,
            "wayNodeOccurrences": self.way_node_occurrences,
        }


@dataclass(slots=True)
class _CandidateUsage:
    limits: _AdmissionLimits
    max_depth: int = 0
    relation_visits: int = 0
    raw_parts: int = 0
    raw_points: int = 0

    def enter_relation(self, depth: int) -> None:
        if depth > self.limits.max_candidate_depth:
            raise GlobalWaterwayPackageError(
                "waterway candidate depth ceiling exceeded"
            )
        if self.relation_visits >= self.limits.max_candidate_relation_visits:
            raise GlobalWaterwayPackageError(
                "waterway candidate relation-visit ceiling exceeded"
            )
        self.relation_visits += 1
        self.max_depth = max(self.max_depth, depth)

    def reserve_way(self, point_count: int) -> None:
        if self.raw_parts >= self.limits.max_candidate_raw_parts:
            raise GlobalWaterwayPackageError(
                "waterway candidate raw-part ceiling exceeded"
            )
        if point_count > self.limits.max_candidate_points - self.raw_points:
            raise GlobalWaterwayPackageError(
                "waterway candidate raw-point ceiling exceeded"
            )
        self.raw_parts += 1
        self.raw_points += point_count


@dataclass(frozen=True, slots=True)
class _CandidateEvidence:
    waterway_type: str
    complete_named_relation: bool
    parts: tuple[tuple[object, ...], ...]
    required_node_ids: frozenset[int]
    source_occurrence_paths: tuple[tuple[tuple[int, int, int], ...], ...]
    candidate_feature_sha256: bytes
    source_feature_sha256: bytes
    part_count: int
    point_count: int


@dataclass(frozen=True, slots=True)
class _AdmissionRootAnalysis:
    entry: Mapping[str, object]
    entry_bytes: bytes
    candidates: tuple[_CandidateEvidence, ...]
    legacy_first_terminal_reason: str | None


def _source_document_from_database(connection: sqlite3.Connection) -> dict[str, object]:
    run_identity = _meta_get(connection, "runIdentity")
    if not isinstance(run_identity, dict) or not isinstance(
        run_identity.get("source"), dict
    ):
        raise GlobalWaterwayPackageError(
            "waterway admission source identity is absent"
        )
    return dict(run_identity["source"])


def _frame_digest_value(digest: object, value: object) -> None:
    raw = _canonical_json_no_lf_bytes(value)
    digest.update(struct.pack(">Q", len(raw)))
    digest.update(raw)


def _start_geometry_digest(domain: bytes, metadata: Mapping[str, object]) -> object:
    digest = hashlib.sha256()
    digest.update(domain)
    _frame_digest_value(digest, dict(metadata))
    return digest


def _update_geometry_part_start(digest: object, point_count: int) -> None:
    digest.update(b"P")
    digest.update(struct.pack(">Q", point_count))


def _update_geometry_point(digest: object, point: object) -> None:
    digest.update(
        struct.pack(
            ">Qii", point.node_id, point.longitude_e7, point.latitude_e7
        )
    )


def _feature_metadata(
    *,
    root: Mapping[str, object],
    source_document: Mapping[str, object],
    name_source_key: str,
    primary_name: str,
    english_name: str | None,
    waterway_type: str,
    complete_named_relation: bool,
    required_node_ids: frozenset[int],
    source_occurrence_paths: Sequence[Sequence[Sequence[int]]],
    dependency_evidence: Mapping[str, object],
) -> tuple[dict[str, object], dict[str, object]]:
    candidate = {
        "completeNamedRelation": complete_named_relation,
        "requiredJoinNodeIds": sorted(required_node_ids),
        "sourceOccurrencePaths": [
            [list(component) for component in path]
            for path in source_occurrence_paths
        ],
        "waterwayType": waterway_type,
    }
    source = {
        **candidate,
        "dependencyEvidence": dict(dependency_evidence),
        "englishName": english_name,
        "nameSourceKey": name_source_key,
        "primaryName": primary_name,
        "root": dict(root),
        "source": dict(source_document),
    }
    return candidate, source


def _hash_materialized_candidate(
    *,
    candidate_metadata: Mapping[str, object],
    source_metadata: Mapping[str, object],
    parts: Sequence[Sequence[object]],
) -> tuple[bytes, bytes]:
    candidate_digest = _start_geometry_digest(
        _CANDIDATE_FEATURE_DOMAIN, candidate_metadata
    )
    source_digest = _start_geometry_digest(_SOURCE_FEATURE_DOMAIN, source_metadata)
    candidate_digest.update(struct.pack(">Q", len(parts)))
    source_digest.update(struct.pack(">Q", len(parts)))
    for part in parts:
        _update_geometry_part_start(candidate_digest, len(part))
        _update_geometry_part_start(source_digest, len(part))
        for point in part:
            _update_geometry_point(candidate_digest, point)
            _update_geometry_point(source_digest, point)
    return candidate_digest.digest(), source_digest.digest()


def _iter_exact_way_points(
    connection: sqlite3.Connection,
    way_id: int,
) -> Iterator[object]:
    from .osm_global_waterway_renderer import ExactWaterwayPoint

    rows = connection.execute(
        "SELECT wn.ordinal,n.id,n.longitude_e7,n.latitude_e7 "
        "FROM way_nodes wn JOIN nodes n ON n.id=wn.node_id "
        "WHERE wn.way_id=? ORDER BY wn.ordinal",
        (way_id,),
    )
    seen = 0
    try:
        for expected, row in enumerate(rows):
            ordinal, node_id, longitude_e7, latitude_e7 = row
            if int(ordinal) != expected:
                raise GlobalWaterwayPackageError(
                    f"waterway way {way_id} node order is malformed"
                )
            seen += 1
            yield ExactWaterwayPoint(
                int(node_id), int(longitude_e7), int(latitude_e7)
            )
    finally:
        rows.close()
    if seen < 2:
        raise GlobalWaterwayPackageError(
            f"waterway way {way_id} has incomplete geometry"
        )


def _structural_way(
    connection: sqlite3.Connection,
    way_id: int,
    evidence: _DependencyEvidence,
    usage: _StructuralUsage,
    path: Sequence[Sequence[int]],
) -> None:
    row = connection.execute(
        "SELECT payload_sha FROM ways WHERE id=?", (way_id,)
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(
            f"waterway structural closure references missing way {way_id}"
        )
    usage.has_reachable_way = True
    evidence.add(["way", way_id, bytes(row[0]).hex(), [list(item) for item in path]])
    rows = connection.execute(
        "SELECT wn.ordinal,wn.node_id,n.payload_sha "
        "FROM way_nodes wn LEFT JOIN nodes n ON n.id=wn.node_id "
        "WHERE wn.way_id=? ORDER BY wn.ordinal",
        (way_id,),
    )
    seen = 0
    try:
        for expected, (ordinal, node_id, payload_sha) in enumerate(rows):
            if int(ordinal) != expected:
                raise GlobalWaterwayPackageError(
                    f"waterway way {way_id} node ordinal is malformed or noncontiguous"
                )
            usage.way_node()
            if payload_sha is None:
                raise GlobalWaterwayPackageError(
                    f"waterway structural closure references missing node {int(node_id)}"
                )
            evidence.add(
                ["way-node", way_id, expected, int(node_id), bytes(payload_sha).hex()]
            )
            seen += 1
    finally:
        rows.close()
    if seen < 2:
        raise GlobalWaterwayPackageError(
            f"waterway structural member way {way_id} has fewer than two nodes"
        )


def _structural_relation(
    connection: sqlite3.Connection,
    relation_id: int,
    evidence: _DependencyEvidence,
    usage: _StructuralUsage,
    *,
    stack: tuple[int, ...],
    path: tuple[tuple[int, int, int], ...],
) -> None:
    if relation_id in stack:
        usage.cycle_edges += 1
        evidence.add(["cycle-edge", [list(item) for item in path]])
        return
    usage.enter_relation(len(stack) + 1)
    row = connection.execute(
        "SELECT payload_sha FROM relations WHERE id=?", (relation_id,)
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(
            f"waterway structural closure references missing relation {relation_id}"
        )
    evidence.add(
        ["relation", relation_id, bytes(row[0]).hex(), [list(item) for item in path]]
    )
    next_stack = stack + (relation_id,)
    rows = connection.execute(
        "SELECT ordinal,kind,ref,role FROM relation_members "
        "WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    )
    try:
        for expected, (ordinal, kind, ref, role) in enumerate(rows):
            if int(ordinal) != expected:
                raise GlobalWaterwayPackageError(
                    f"relation {relation_id} member ordinal is malformed or noncontiguous"
                )
            usage.member()
            kind = int(kind)
            ref = int(ref)
            if kind not in (0, 1, 2):
                raise GlobalWaterwayPackageError(
                    f"relation {relation_id} has unknown member object kind {kind}"
                )
            member_path = path + ((kind, ref, expected),)
            evidence.add(
                ["member", relation_id, expected, kind, ref, str(role)]
            )
            if kind == 0:
                node = connection.execute(
                    "SELECT payload_sha FROM nodes WHERE id=?", (ref,)
                ).fetchone()
                if node is None:
                    raise GlobalWaterwayPackageError(
                        f"waterway structural closure references missing node {ref}"
                    )
                evidence.add(["relation-node", ref, bytes(node[0]).hex()])
            elif kind == 1:
                _structural_way(connection, ref, evidence, usage, member_path)
            else:
                _structural_relation(
                    connection,
                    ref,
                    evidence,
                    usage,
                    stack=next_stack,
                    path=member_path,
                )
    finally:
        rows.close()


def _candidate_reason(
    reason_code: str,
    path: Sequence[Sequence[int]],
    *,
    declared: str | None,
    effective: str | None,
    way_declared: str | None = None,
) -> dict[str, object]:
    return {
        "declaredWaterway": declared,
        "effectiveWaterway": effective,
        "memberWayDeclaredWaterway": way_declared,
        "reasonCode": reason_code,
        "sourcePath": [list(item) for item in path],
    }


def _candidate_relation_events(
    connection: sqlite3.Connection,
    relation_id: int,
    *,
    inherited_type: str | None,
    stack: tuple[int, ...],
    path: tuple[tuple[int, int, int], ...],
    usage: _CandidateUsage,
    waterway_evidence: list[dict[str, object]],
):
    if relation_id in stack:
        row = connection.execute(
            "SELECT tags FROM relations WHERE id=?", (relation_id,)
        ).fetchone()
        if row is None:
            raise GlobalWaterwayPackageError(
                f"waterway candidate cycle references missing relation {relation_id}"
            )
        tags = _decode_tags(bytes(row[0]), f"relation {relation_id}")
        declared = dict(tags).get("waterway")
        effective = declared if declared in _ALLOWED_WATERWAYS else inherited_type
        waterway_evidence.append(
            {
                "declaredWaterway": declared,
                "effectiveWaterway": effective,
                "objectId": relation_id,
                "objectKind": "relation",
                "sourcePath": [list(item) for item in path],
            }
        )
        yield (
            "reason",
            _candidate_reason(
                "relation_cycle",
                path,
                declared=declared,
                effective=effective,
            ),
        )
        return
    usage.enter_relation(len(stack) + 1)
    row = connection.execute(
        "SELECT tags FROM relations WHERE id=?", (relation_id,)
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(
            f"waterway candidate references missing relation {relation_id}"
        )
    tags = _decode_tags(bytes(row[0]), f"relation {relation_id}")
    declared = dict(tags).get("waterway")
    effective = declared if declared in _ALLOWED_WATERWAYS else inherited_type
    waterway_evidence.append(
        {
            "declaredWaterway": declared,
            "effectiveWaterway": effective,
            "objectId": relation_id,
            "objectKind": "relation",
            "sourcePath": [list(item) for item in path],
        }
    )
    if declared is not None and declared not in _ALLOWED_WATERWAYS:
        yield (
            "reason",
            _candidate_reason(
                "unsupported_relation_waterway",
                path,
                declared=declared,
                effective=inherited_type,
            ),
        )
        return
    next_stack = stack + (relation_id,)
    rows = connection.execute(
        "SELECT ordinal,kind,ref FROM relation_members "
        "WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    )
    try:
        for expected, (ordinal, kind, ref) in enumerate(rows):
            if int(ordinal) != expected:
                raise GlobalWaterwayPackageError(
                    f"relation {relation_id} member ordinal is malformed or noncontiguous"
                )
            kind = int(kind)
            ref = int(ref)
            member_path = path + ((kind, ref, expected),)
            if kind == 0:
                yield ("node", member_path)
                continue
            if kind == 1:
                row = connection.execute(
                    "SELECT tags FROM ways WHERE id=?", (ref,)
                ).fetchone()
                if row is None:
                    raise GlobalWaterwayPackageError(
                        f"waterway candidate references missing way {ref}"
                    )
                way_tags = _decode_tags(bytes(row[0]), f"way {ref}")
                way_declared = dict(way_tags).get("waterway")
                way_effective = effective or way_declared
                waterway_evidence.append(
                    {
                        "declaredWaterway": way_declared,
                        "effectiveWaterway": way_effective,
                        "objectId": ref,
                        "objectKind": "way",
                        "sourcePath": [list(item) for item in member_path],
                    }
                )
                if way_effective not in _ALLOWED_WATERWAYS:
                    yield (
                        "reason",
                        _candidate_reason(
                            "unsupported_member_way_waterway",
                            member_path,
                            declared=declared,
                            effective=effective,
                            way_declared=way_declared,
                        ),
                    )
                    continue
                point_count = int(
                    connection.execute(
                        "SELECT COUNT(*) FROM way_nodes WHERE way_id=?", (ref,)
                    ).fetchone()[0]
                )
                usage.reserve_way(point_count)
                points = _point_rows(connection, ref, _DependencyEvidence(usage.limits))
                yield (
                    "candidate",
                    {
                        "path": member_path,
                        "points": points,
                        "waterwayType": way_effective,
                    },
                )
                continue
            if kind == 2:
                yield from _candidate_relation_events(
                    connection,
                    ref,
                    inherited_type=effective,
                    stack=next_stack,
                    path=member_path,
                    usage=usage,
                    waterway_evidence=waterway_evidence,
                )
                continue
            raise GlobalWaterwayPackageError(
                f"relation {relation_id} has unknown member object kind {kind}"
            )
    finally:
        rows.close()


def _legacy_first_terminal(
    connection: sqlite3.Connection,
    relation_id: int,
    *,
    inherited_type: str | None = None,
    stack: tuple[int, ...] = (),
) -> tuple[str | None, bool]:
    if relation_id in stack:
        return "relation_cycle", False
    row = connection.execute(
        "SELECT tags FROM relations WHERE id=?", (relation_id,)
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(
            f"legacy projection references missing relation {relation_id}"
        )
    tags = _decode_tags(bytes(row[0]), f"relation {relation_id}")
    declared = dict(tags).get("waterway")
    if declared is not None and declared not in _ALLOWED_WATERWAYS:
        return "unsupported_relation_waterway", False
    effective = declared or inherited_type
    found = False
    rows = connection.execute(
        "SELECT ordinal,kind,ref FROM relation_members "
        "WHERE relation_id=? ORDER BY ordinal",
        (relation_id,),
    )
    try:
        for expected, (ordinal, kind, ref) in enumerate(rows):
            if int(ordinal) != expected:
                raise GlobalWaterwayPackageError(
                    f"legacy relation {relation_id} member ordinal is malformed"
                )
            kind = int(kind)
            ref = int(ref)
            if kind == 0:
                continue
            if kind == 1:
                way = connection.execute(
                    "SELECT tags FROM ways WHERE id=?", (ref,)
                ).fetchone()
                if way is None:
                    raise GlobalWaterwayPackageError(
                        f"legacy projection references missing way {ref}"
                    )
                exact_way_type = dict(
                    _decode_tags(bytes(way[0]), f"way {ref}")
                ).get("waterway")
                if (effective or exact_way_type) not in _ALLOWED_WATERWAYS:
                    return "unsupported_member_way_waterway", found
                found = True
                continue
            if kind == 2:
                reason, child_found = _legacy_first_terminal(
                    connection,
                    ref,
                    inherited_type=effective,
                    stack=stack + (relation_id,),
                )
                if reason is not None:
                    return reason, found or child_found
                found = found or child_found
                continue
            raise GlobalWaterwayPackageError(
                f"legacy relation {relation_id} member kind is unsupported"
            )
    finally:
        rows.close()
    return None, found


def _root_row(
    connection: sqlite3.Connection,
    root_kind: int,
    root_id: int,
) -> tuple[dict[str, object], tuple[tuple[str, str], ...], str, str, str | None]:
    table = "ways" if root_kind == 1 else "relations"
    row = connection.execute(
        f"SELECT version,timestamp,tags,payload_sha FROM {table} WHERE id=?",
        (root_id,),
    ).fetchone()
    if row is None:
        raise GlobalWaterwayPackageError(
            f"selected root {table[:-1]} {root_id} is absent from closure"
        )
    version, timestamp, raw_tags, payload_sha = row
    tags = _decode_tags(bytes(raw_tags), f"selected {table[:-1]} {root_id}")
    name_key, primary, english = _source_names(tags)
    by_key = dict(tags)
    if root_kind == 1:
        if by_key.get("waterway") not in _ALLOWED_WATERWAYS:
            raise GlobalWaterwayPackageError(
                f"selected way {root_id} predicate contradicts supported waterway selection"
            )
    elif by_key.get("type") != "waterway":
        raise GlobalWaterwayPackageError(
            f"selected relation {root_id} predicate contradicts type=waterway selection"
        )
    root = {
        "id": root_id,
        "kind": "way" if root_kind == 1 else "relation",
        "kindCode": root_kind,
        "payloadSha256": bytes(payload_sha).hex(),
        "timestamp": str(timestamp),
        "version": int(version),
    }
    return root, tags, name_key, primary, english


def _direct_candidate(
    connection: sqlite3.Connection,
    *,
    root: Mapping[str, object],
    tags: Sequence[tuple[str, str]],
    name_key: str,
    primary: str,
    english: str | None,
    source_document: Mapping[str, object],
    dependency_evidence: Mapping[str, object],
    usage: _CandidateUsage,
    materialize: bool,
) -> _CandidateEvidence:
    root_id = int(root["id"])
    waterway_type = str(dict(tags)["waterway"])
    point_count = int(
        connection.execute(
            "SELECT COUNT(*) FROM way_nodes WHERE way_id=?", (root_id,)
        ).fetchone()[0]
    )
    usage.reserve_way(point_count)
    source_path = (((1, root_id, -1),),)
    candidate_meta, source_meta = _feature_metadata(
        root=root,
        source_document=source_document,
        name_source_key=name_key,
        primary_name=primary,
        english_name=english,
        waterway_type=waterway_type,
        complete_named_relation=False,
        required_node_ids=frozenset(),
        source_occurrence_paths=source_path,
        dependency_evidence=dependency_evidence,
    )
    candidate_digest = _start_geometry_digest(_CANDIDATE_FEATURE_DOMAIN, candidate_meta)
    source_digest = _start_geometry_digest(_SOURCE_FEATURE_DOMAIN, source_meta)
    candidate_digest.update(struct.pack(">Q", 1))
    source_digest.update(struct.pack(">Q", 1))
    _update_geometry_part_start(candidate_digest, point_count)
    _update_geometry_part_start(source_digest, point_count)
    points: list[object] | None = [] if materialize else None
    seen = 0
    for point in _iter_exact_way_points(connection, root_id):
        _update_geometry_point(candidate_digest, point)
        _update_geometry_point(source_digest, point)
        if points is not None:
            points.append(point)
        seen += 1
    if seen != point_count:
        raise GlobalWaterwayPackageError(
            f"direct way {root_id} point count drifted during admission"
        )
    parts = (tuple(points),) if points is not None else ()
    return _CandidateEvidence(
        waterway_type,
        False,
        parts,
        frozenset(),
        source_path,
        candidate_digest.digest(),
        source_digest.digest(),
        1,
        point_count,
    )


def _analyze_admission_root(
    connection: sqlite3.Connection,
    root_kind: int,
    root_id: int,
    *,
    limits: _AdmissionLimits | None = None,
    materialize: bool = False,
) -> _AdmissionRootAnalysis:
    if root_kind not in (1, 2):
        raise GlobalWaterwayPackageError("waterway admission root kind is unsupported")
    checked_limits = limits or _AdmissionLimits.production()
    if not isinstance(checked_limits, _AdmissionLimits):
        raise GlobalWaterwayPackageError("waterway admission limits are invalid")
    source_document = _source_document_from_database(connection)
    root, tags, name_key, primary, english = _root_row(
        connection, root_kind, root_id
    )
    dependency = _DependencyEvidence(checked_limits)
    structural = _StructuralUsage(checked_limits)
    root_path = ((root_kind, root_id, -1),)
    if root_kind == 1:
        _structural_way(connection, root_id, dependency, structural, root_path)
    else:
        _structural_relation(
            connection,
            root_id,
            dependency,
            structural,
            stack=(),
            path=root_path,
        )
    dependency_document = dependency.document()
    candidate_usage = _CandidateUsage(checked_limits)
    reasons: list[dict[str, object]] = []
    waterway_evidence: list[dict[str, object]] = []
    node_evidence_count = 0
    candidates: list[_CandidateEvidence] = []
    if root_kind == 1:
        waterway_type = str(dict(tags).get("waterway"))
        waterway_evidence.append(
            {
                "declaredWaterway": waterway_type,
                "effectiveWaterway": waterway_type,
                "objectId": root_id,
                "objectKind": "way",
                "sourcePath": [list(item) for item in root_path],
            }
        )
        candidates.append(
            _direct_candidate(
                connection,
                root=root,
                tags=tags,
                name_key=name_key,
                primary=primary,
                english=english,
                source_document=source_document,
                dependency_evidence=dependency_document,
                usage=candidate_usage,
                materialize=materialize,
            )
        )
        legacy_reason = None
    else:
        parts_by_type: dict[str, list[list[object]]] = {}
        required_by_type: dict[str, set[int]] = {}
        paths_by_type: dict[str, list[tuple[tuple[int, int, int], ...]]] = {}
        type_order: list[str] = []
        previous_type: str | None = None
        join_allowed = False
        for event_kind, value in _candidate_relation_events(
            connection,
            root_id,
            inherited_type=None,
            stack=(),
            path=root_path,
            usage=candidate_usage,
            waterway_evidence=waterway_evidence,
        ):
            if event_kind == "node":
                node_evidence_count += 1
                continue
            if event_kind == "reason":
                reasons.append(value)
                join_allowed = False
                previous_type = None
                continue
            waterway_type = str(value["waterwayType"])
            points = value["points"]
            path = tuple(tuple(int(component) for component in item) for item in value["path"])
            if waterway_type not in parts_by_type:
                parts_by_type[waterway_type] = []
                required_by_type[waterway_type] = set()
                paths_by_type[waterway_type] = []
                type_order.append(waterway_type)
            typed_parts = parts_by_type[waterway_type]
            if (
                join_allowed
                and previous_type == waterway_type
                and typed_parts
                and typed_parts[-1][-1].node_id == points[0].node_id
            ):
                required_by_type[waterway_type].add(points[0].node_id)
                typed_parts[-1].extend(points[1:])
            else:
                typed_parts.append(list(points))
            paths_by_type[waterway_type].append(path)
            previous_type = waterway_type
            join_allowed = True
        complete = len(type_order) == 1 and not reasons
        for waterway_type in type_order:
            parts = tuple(tuple(part) for part in parts_by_type[waterway_type])
            required = frozenset(required_by_type[waterway_type])
            occurrence_paths = tuple(paths_by_type[waterway_type])
            candidate_meta, source_meta = _feature_metadata(
                root=root,
                source_document=source_document,
                name_source_key=name_key,
                primary_name=primary,
                english_name=english,
                waterway_type=waterway_type,
                complete_named_relation=complete,
                required_node_ids=required,
                source_occurrence_paths=occurrence_paths,
                dependency_evidence=dependency_document,
            )
            candidate_sha, source_sha = _hash_materialized_candidate(
                candidate_metadata=candidate_meta,
                source_metadata=source_meta,
                parts=parts,
            )
            candidates.append(
                _CandidateEvidence(
                    waterway_type,
                    complete,
                    parts if materialize else (),
                    required,
                    occurrence_paths,
                    candidate_sha,
                    source_sha,
                    len(parts),
                    sum(len(part) for part in parts),
                )
            )
        legacy_reason, legacy_found = _legacy_first_terminal(connection, root_id)
        if legacy_reason is None and not legacy_found:
            legacy_reason = "no_usable_way_geometry"
    if candidates:
        status = (
            "line_candidates_with_noncandidate_members"
            if reasons
            else "line_candidates"
        )
    elif not structural.has_reachable_way:
        status = "no_line_geometry"
    else:
        status = "no_supported_line_candidate"
    candidate_stream = hashlib.sha256()
    candidate_stream.update(_CANDIDATE_STREAM_DOMAIN)
    for candidate in candidates:
        candidate_stream.update(struct.pack(">Q", 32))
        candidate_stream.update(candidate.candidate_feature_sha256)
    feature_parts = sum(candidate.part_count for candidate in candidates)
    feature_points = sum(candidate.point_count for candidate in candidates)
    entry: dict[str, object] = {
        "candidateFeatureCount": len(candidates),
        "candidateFeatureSha256": [
            candidate.candidate_feature_sha256.hex() for candidate in candidates
        ],
        "candidateStreamSha256": candidate_stream.hexdigest(),
        "dependencyEvidence": dependency_document,
        "englishName": english,
        "geometryUsage": {
            "candidateFeatureParts": feature_parts,
            "candidateFeaturePoints": feature_points,
            "maxDepth": candidate_usage.max_depth,
            "rawSupportedWayParts": candidate_usage.raw_parts,
            "rawSupportedWayPoints": candidate_usage.raw_points,
            "relationVisits": candidate_usage.relation_visits,
        },
        "legacyFirstTerminalReason": legacy_reason,
        "nameSourceKey": name_key,
        "nodeEvidenceCount": node_evidence_count,
        "ownedNameField": {"key": name_key, "value": primary},
        "primaryName": primary,
        "reasonOccurrences": reasons,
        "root": root,
        "status": status,
        "structural": structural.document(),
        "structuralStatus": "valid",
        "waterwayEvidence": waterway_evidence,
    }
    entry_bytes = _canonical_json_no_lf_bytes(entry)
    if len(entry_bytes) > checked_limits.max_admission_entry_bytes:
        raise GlobalWaterwayPackageError(
            "waterway admission entry byte ceiling exceeded"
        )
    return _AdmissionRootAnalysis(
        MappingProxyType(entry), entry_bytes, tuple(candidates), legacy_reason
    )


def _candidate_rows(analysis: _AdmissionRootAnalysis) -> tuple[tuple[object, ...], ...]:
    root = analysis.entry["root"]
    return tuple(
        (
            int(root["kindCode"]),
            int(root["id"]),
            ordinal,
            candidate.waterway_type,
            int(candidate.complete_named_relation),
            candidate.candidate_feature_sha256,
            candidate.source_feature_sha256,
            candidate.part_count,
            candidate.point_count,
        )
        for ordinal, candidate in enumerate(analysis.candidates)
    )


def _analysis_features(analysis: _AdmissionRootAnalysis):
    from .osm_global_waterway_renderer import ExactWaterwayFeature

    entry = analysis.entry
    root = entry["root"]
    for candidate in analysis.candidates:
        if not candidate.parts:
            raise GlobalWaterwayPackageError(
                "waterway renderer candidate geometry was not materialized"
            )
        yield ExactWaterwayFeature(
            str(root["kind"]),
            int(root["id"]),
            int(root["version"]),
            str(root["timestamp"]),
            candidate.waterway_type,
            str(entry["nameSourceKey"]),
            str(entry["primaryName"]),
            entry["englishName"],
            candidate.complete_named_relation,
            candidate.parts,
            candidate.required_node_ids,
            candidate.source_feature_sha256,
        )


def _iter_exact_waterway_features(
    connection: sqlite3.Connection,
    *,
    source_binding: WaterwaySourceBinding,
):
    """Recompute and authenticate every admitted root before yielding candidates."""

    run_identity = _meta_get(connection, "runIdentity")
    admission_receipt = _meta_get(connection, "admissionReceipt")
    if (
        not isinstance(run_identity, dict)
        or run_identity.get("source") != source_binding.document()
    ):
        raise GlobalWaterwayPackageError("waterway database source binding differs")
    if (
        not isinstance(admission_receipt, dict)
        or admission_receipt.get("fatalCount") != 0
        or admission_receipt.get("policy", {}).get("sha256")
        != LINE_CANDIDATE_POLICY_SHA256
    ):
        raise GlobalWaterwayPackageError(
            "waterway database lacks complete v2 admission evidence"
        )
    previous_kind = 0
    previous_id = 0
    while True:
        root_cursor = connection.execute(
            "SELECT kind,id FROM roots "
            "WHERE kind>? OR (kind=? AND id>?) ORDER BY kind,id LIMIT 1",
            (previous_kind, previous_kind, previous_id),
        )
        root_row = root_cursor.fetchone()
        root_cursor.close()
        if root_row is None:
            break
        root_kind, root_id = int(root_row[0]), int(root_row[1])
        previous_kind, previous_id = root_kind, root_id
        analysis = _analyze_admission_root(
            connection,
            root_kind,
            root_id,
            materialize=True,
        )
        stored = connection.execute(
            "SELECT entry_json,entry_sha,candidate_stream_sha "
            "FROM admission_roots WHERE root_kind=? AND root_id=?",
            (root_kind, root_id),
        ).fetchone()
        if stored is None:
            raise GlobalWaterwayPackageError(
                "waterway admitted root evidence is absent"
            )
        expected_entry_sha = hashlib.sha256(analysis.entry_bytes).digest()
        if (
            _sqlite_blob(stored[0], "admitted root entry") != analysis.entry_bytes
            or _sqlite_blob(stored[1], "admitted root entry SHA-256") != expected_entry_sha
            or _sqlite_blob(stored[2], "admitted candidate stream SHA-256").hex()
            != analysis.entry["candidateStreamSha256"]
        ):
            raise GlobalWaterwayPackageError(
                "waterway admitted root candidate stream differs from exact source"
            )
        actual_candidates = tuple(
            connection.execute(
                "SELECT root_kind,root_id,feature_ordinal,waterway_type,"
                "complete_named_relation,candidate_feature_sha,source_feature_sha,"
                "part_count,point_count FROM admission_candidates "
                "WHERE root_kind=? AND root_id=? ORDER BY feature_ordinal",
                (root_kind, root_id),
            )
        )
        if actual_candidates != _candidate_rows(analysis):
            raise GlobalWaterwayPackageError(
                "waterway admitted candidate feature evidence differs"
            )
        yield from _analysis_features(analysis)


def iter_exact_waterway_features(
    database_path: Path,
    *,
    source_binding: WaterwaySourceBinding,
):
    """Open the admitted store query-only and yield authenticated candidates."""

    if (
        not isinstance(database_path, Path)
        or not database_path.is_file()
        or database_path.is_symlink()
    ):
        raise GlobalWaterwayPackageError(
            "waterway database is not one regular non-link file"
        )
    if not isinstance(source_binding, WaterwaySourceBinding):
        raise GlobalWaterwayPackageError("waterway feature source binding is invalid")
    connection = sqlite3.connect(f"file:{database_path}?mode=ro", uri=True)
    try:
        connection.execute("PRAGMA query_only=ON")
        yield from _iter_exact_waterway_features(
            connection,
            source_binding=source_binding,
        )
    finally:
        connection.close()


def _root_status_histogram(counts: Mapping[str, int]) -> dict[str, object]:
    return {
        "unit": "roots",
        "values": [
            {"roots": int(counts.get(status, 0)), "status": status}
            for status in _ROOT_STATUS_ORDER
        ],
    }


def _reason_occurrence_histogram(counts: Mapping[str, int]) -> dict[str, object]:
    return {
        "unit": "occurrences",
        "values": [
            {
                "occurrences": int(counts.get(reason, 0)),
                "reasonCode": reason,
            }
            for reason in _REASON_CODE_ORDER
        ],
    }


def _legacy_histogram(counts: Mapping[str, int]) -> dict[str, object]:
    return {
        "unit": "roots",
        "values": [
            {"reasonCode": reason, "roots": int(counts.get(reason, 0))}
            for reason in _LEGACY_REASON_ORDER
        ],
    }


def _numeric_ledger(root_ids: Sequence[int]) -> tuple[bytes, dict[str, object]]:
    raw = (
        json.dumps(sorted(root_ids), ensure_ascii=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    return raw, {
        "bytes": len(raw),
        "count": len(root_ids),
        "sha256": hashlib.sha256(raw).hexdigest(),
    }


def _next_root(
    connection: sqlite3.Connection,
    after_kind: int,
    after_id: int,
) -> tuple[int, int] | None:
    row = connection.execute(
        "SELECT kind,id FROM roots WHERE (kind,id)>(?,?) "
        "ORDER BY kind,id LIMIT 1",
        (after_kind, after_id),
    ).fetchone()
    return None if row is None else (int(row[0]), int(row[1]))


def _checkpoint_next_document(value: tuple[int, int] | None) -> object:
    if value is None:
        return None
    return {"rootId": value[1], "rootKindCode": value[0]}


def _admission_checkpoint_document(
    *,
    processed_count: int,
    next_root: tuple[int, int] | None,
    framed_bytes: int,
    aggregate_digest: object,
    status_counts: Mapping[str, int],
    reason_counts: Mapping[str, int],
    fatal_count: int,
) -> dict[str, object]:
    return {
        "admissionComplete": next_root is None,
        "fatalCount": fatal_count,
        "nextRoot": _checkpoint_next_document(next_root),
        "prefixFramedBytes": framed_bytes,
        "prefixSha256": aggregate_digest.hexdigest(),
        "processedCount": processed_count,
        "reasonOccurrenceHistogram": _reason_occurrence_histogram(reason_counts),
        "rootStatusHistogram": _root_status_histogram(status_counts),
        "schema": "flightalert.experiment8.osm-waterway-admission-checkpoint.v2",
    }


def _stored_candidate_rows(
    connection: sqlite3.Connection,
    root_kind: int,
    root_id: int,
) -> tuple[tuple[object, ...], ...]:
    return tuple(
        connection.execute(
            "SELECT root_kind,root_id,feature_ordinal,waterway_type,"
            "complete_named_relation,candidate_feature_sha,source_feature_sha,"
            "part_count,point_count FROM admission_candidates "
            "WHERE root_kind=? AND root_id=? ORDER BY feature_ordinal",
            (root_kind, root_id),
        )
    )


def _authenticate_admission_prefix(
    connection: sqlite3.Connection,
    checkpoint: Mapping[str, object],
) -> tuple[
    object,
    int,
    int,
    tuple[int, int] | None,
    dict[str, int],
    dict[str, int],
    dict[str, int],
    list[int],
    list[int],
    int,
    int,
]:
    try:
        processed = int(checkpoint["processedCount"])
    except (KeyError, TypeError, ValueError) as error:
        raise GlobalWaterwayPackageError(
            "waterway admission checkpoint prefix is malformed"
        ) from error
    if processed < 0:
        raise GlobalWaterwayPackageError(
            "waterway admission checkpoint prefix is malformed"
        )
    if int(
        connection.execute("SELECT COUNT(*) FROM admission_roots").fetchone()[0]
    ) != processed:
        raise GlobalWaterwayPackageError(
            "waterway admission prefix root inventory differs"
        )
    orphan = connection.execute(
        "SELECT 1 FROM admission_candidates c LEFT JOIN admission_roots r "
        "ON r.root_kind=c.root_kind AND r.root_id=c.root_id "
        "WHERE r.root_id IS NULL LIMIT 1"
    ).fetchone()
    if orphan is not None:
        raise GlobalWaterwayPackageError(
            "waterway admission candidate evidence is orphaned"
        )
    root_rows = connection.execute("SELECT kind,id FROM roots ORDER BY kind,id")
    stored_rows = connection.execute(
        "SELECT root_kind,root_id,entry_json,entry_sha,framed_bytes,status,"
        "candidate_stream_sha FROM admission_roots ORDER BY root_kind,root_id"
    )
    aggregate = hashlib.sha256()
    aggregate.update(_ADMISSION_STREAM_DOMAIN)
    framed_bytes = len(_ADMISSION_STREAM_DOMAIN)
    status_counts = {status: 0 for status in _ROOT_STATUS_ORDER}
    reason_counts = {reason: 0 for reason in _REASON_CODE_ORDER}
    legacy_counts = {reason: 0 for reason in _LEGACY_REASON_ORDER}
    legacy_root_ids: list[int] = []
    no_way_root_ids: list[int] = []
    candidate_roots = 0
    zero_candidate_roots = 0
    last = (0, 0)
    try:
        for index in range(processed):
            root_row = root_rows.fetchone()
            stored = stored_rows.fetchone()
            if root_row is None or stored is None:
                raise GlobalWaterwayPackageError(
                    "waterway admission prefix order ended early"
                )
            root_kind, root_id = int(root_row[0]), int(root_row[1])
            if (int(stored[0]), int(stored[1])) != (root_kind, root_id):
                raise GlobalWaterwayPackageError(
                    "waterway admission prefix order drifted"
                )
            analysis = _analyze_admission_root(
                connection, root_kind, root_id, materialize=False
            )
            entry_sha = hashlib.sha256(analysis.entry_bytes).digest()
            if (
                _sqlite_blob(stored[2], "admission prefix entry") != analysis.entry_bytes
                or _sqlite_blob(stored[3], "admission prefix entry SHA-256") != entry_sha
                or int(stored[4]) != 8 + len(analysis.entry_bytes)
                or str(stored[5]) != analysis.entry["status"]
                or _sqlite_blob(stored[6], "admission prefix candidate stream SHA-256").hex()
                != analysis.entry["candidateStreamSha256"]
            ):
                raise GlobalWaterwayPackageError(
                    "waterway admission prefix evidence differs from exact source"
                )
            if _stored_candidate_rows(connection, root_kind, root_id) != _candidate_rows(
                analysis
            ):
                raise GlobalWaterwayPackageError(
                    "waterway admission prefix candidate evidence differs"
                )
            aggregate.update(struct.pack(">Q", len(analysis.entry_bytes)))
            aggregate.update(analysis.entry_bytes)
            framed_bytes += 8 + len(analysis.entry_bytes)
            status = str(analysis.entry["status"])
            status_counts[status] += 1
            for reason in analysis.entry["reasonOccurrences"]:
                reason_counts[str(reason["reasonCode"])] += 1
            if analysis.legacy_first_terminal_reason is not None:
                legacy_counts[analysis.legacy_first_terminal_reason] += 1
                legacy_root_ids.append(root_id)
            if not bool(analysis.entry["structural"]["hasReachableWay"]):
                no_way_root_ids.append(root_id)
            if int(analysis.entry["candidateFeatureCount"]):
                candidate_roots += 1
            else:
                zero_candidate_roots += 1
            last = (root_kind, root_id)
        if stored_rows.fetchone() is not None:
            raise GlobalWaterwayPackageError(
                "waterway admission prefix has extra root evidence"
            )
        next_root_row = root_rows.fetchone()
    finally:
        root_rows.close()
        stored_rows.close()
    next_root = (
        None
        if next_root_row is None
        else (int(next_root_row[0]), int(next_root_row[1]))
    )
    expected_checkpoint = _admission_checkpoint_document(
        processed_count=processed,
        next_root=next_root,
        framed_bytes=framed_bytes,
        aggregate_digest=aggregate,
        status_counts=status_counts,
        reason_counts=reason_counts,
        fatal_count=0,
    )
    if dict(checkpoint) != expected_checkpoint:
        raise GlobalWaterwayPackageError(
            "waterway admission checkpoint differs from authenticated prefix"
        )
    if processed and next_root is not None and last >= next_root:
        raise GlobalWaterwayPackageError(
            "waterway admission checkpoint next-key order differs"
        )
    return (
        aggregate,
        framed_bytes,
        processed,
        next_root,
        status_counts,
        reason_counts,
        legacy_counts,
        legacy_root_ids,
        no_way_root_ids,
        candidate_roots,
        zero_candidate_roots,
    )


def _commit_admission_checkpoint(
    connection: sqlite3.Connection,
    checkpoint: Mapping[str, object],
) -> None:
    _meta_set(connection, "admissionCheckpoint", dict(checkpoint))
    connection.commit()


def _admission_receipt_document(
    connection: sqlite3.Connection,
    *,
    aggregate: object,
    framed_bytes: int,
    processed: int,
    status_counts: Mapping[str, int],
    reason_counts: Mapping[str, int],
    legacy_counts: Mapping[str, int],
    legacy_root_ids: Sequence[int],
    no_way_root_ids: Sequence[int],
    candidate_roots: int,
    zero_candidate_roots: int,
    source_binding: WaterwaySourceBinding,
    ingest_semantic_sha256: str,
) -> dict[str, object]:
    _, legacy_ledger = _numeric_ledger(legacy_root_ids)
    _, no_way_ledger = _numeric_ledger(no_way_root_ids)
    if not source_binding.planet_path.startswith("fixture://"):
        if dict(legacy_counts) != _LEGACY_PRODUCTION_COUNTS:
            raise GlobalWaterwayPackageError(
                "production legacy first-terminal histogram differs from pinned evidence"
            )
        if (
            legacy_ledger["bytes"] != _LEGACY_PRODUCTION_LEDGER_BYTES
            or legacy_ledger["sha256"] != _LEGACY_PRODUCTION_LEDGER_SHA256
        ):
            raise GlobalWaterwayPackageError(
                "production legacy first-terminal ledger differs from pinned evidence"
            )
        if no_way_ledger["count"] != _PRODUCTION_STRUCTURAL_NO_WAY_COUNT:
            raise GlobalWaterwayPackageError(
                "production structural no-way ledger count differs from pinned evidence"
            )
    selected_way_count = int(
        connection.execute("SELECT COUNT(*) FROM roots WHERE kind=1").fetchone()[0]
    )
    selected_relation_count = int(
        connection.execute("SELECT COUNT(*) FROM roots WHERE kind=2").fetchone()[0]
    )
    policy_document = _line_candidate_policy_document()
    return {
        "aggregateSha256": aggregate.hexdigest(),
        "candidateRootCount": candidate_roots,
        "entryCount": processed,
        "fatalCount": 0,
        "framedBytes": framed_bytes,
        "ingestSemanticSha256": ingest_semantic_sha256,
        "legacyFirstTerminal": {
            "histogram": _legacy_histogram(legacy_counts),
            "ledger": legacy_ledger,
        },
        "policy": {
            "bytes": len(_canonical_json_bytes(policy_document)),
            "document": policy_document,
            "sha256": LINE_CANDIDATE_POLICY_SHA256,
        },
        "reasonOccurrenceHistogram": _reason_occurrence_histogram(reason_counts),
        "rootStatusHistogram": _root_status_histogram(status_counts),
        "schema": "flightalert.experiment8.osm-waterway-admission-receipt.v2",
        "selectedRelationCount": selected_relation_count,
        "selectedWayCount": selected_way_count,
        "source": source_binding.document(),
        "structuralNoReachableWay": {"ledger": no_way_ledger},
        "zeroCandidateRootCount": zero_candidate_roots,
    }


def _admit_waterway_candidates(
    connection: sqlite3.Connection,
    *,
    source_binding: WaterwaySourceBinding,
    ingest_semantic_sha256: str,
    checkpoint_roots: int,
    pause_after_roots: int | None,
) -> tuple[bool, Mapping[str, object]]:
    preexisting_receipt = _meta_get(connection, "admissionReceipt")
    if (
        preexisting_receipt is None
        and (
            int(connection.execute("SELECT COUNT(*) FROM records").fetchone()[0])
            or int(
                connection.execute(
                    "SELECT COUNT(*) FROM rendered_features"
                ).fetchone()[0]
            )
        )
    ):
        raise GlobalWaterwayPackageError(
            "waterway admission must complete before renderer rows exist"
        )
    admission_identity = {
        "ingestSemanticSha256": ingest_semantic_sha256,
        "policySha256": LINE_CANDIDATE_POLICY_SHA256,
        "schema": "flightalert.experiment8.osm-waterway-admission-run.v2",
        "source": source_binding.document(),
    }
    stored_identity = _meta_get(connection, "admissionRunIdentity")
    if stored_identity is None:
        _meta_set(connection, "admissionRunIdentity", admission_identity)
        digest = hashlib.sha256()
        digest.update(_ADMISSION_STREAM_DOMAIN)
        first_root = _next_root(connection, 0, 0)
        checkpoint = _admission_checkpoint_document(
            processed_count=0,
            next_root=first_root,
            framed_bytes=len(_ADMISSION_STREAM_DOMAIN),
            aggregate_digest=digest,
            status_counts={status: 0 for status in _ROOT_STATUS_ORDER},
            reason_counts={reason: 0 for reason in _REASON_CODE_ORDER},
            fatal_count=0,
        )
        _commit_admission_checkpoint(connection, checkpoint)
    elif stored_identity != admission_identity:
        raise GlobalWaterwayPackageError(
            "waterway admission identity differs from source/run/policy identities"
        )
    checkpoint = _meta_get(connection, "admissionCheckpoint")
    if not isinstance(checkpoint, dict):
        raise GlobalWaterwayPackageError("waterway admission checkpoint is absent")
    (
        aggregate,
        framed_bytes,
        processed,
        next_root,
        status_counts,
        reason_counts,
        legacy_counts,
        legacy_root_ids,
        no_way_root_ids,
        candidate_roots,
        zero_candidate_roots,
    ) = _authenticate_admission_prefix(connection, checkpoint)
    existing_receipt = preexisting_receipt
    if existing_receipt is not None:
        if next_root is not None or not isinstance(existing_receipt, dict):
            raise GlobalWaterwayPackageError(
                "waterway admission receipt contradicts checkpoint completion"
            )
        expected_receipt = _admission_receipt_document(
            connection,
            aggregate=aggregate,
            framed_bytes=framed_bytes,
            processed=processed,
            status_counts=status_counts,
            reason_counts=reason_counts,
            legacy_counts=legacy_counts,
            legacy_root_ids=legacy_root_ids,
            no_way_root_ids=no_way_root_ids,
            candidate_roots=candidate_roots,
            zero_candidate_roots=zero_candidate_roots,
            source_binding=source_binding,
            ingest_semantic_sha256=ingest_semantic_sha256,
        )
        if _canonical_json_bytes(existing_receipt) != _canonical_json_bytes(
            expected_receipt
        ):
            raise GlobalWaterwayPackageError(
                "waterway admission receipt differs from authenticated evidence"
            )
        return True, MappingProxyType(expected_receipt)
    since_commit = 0
    processed_this_call = 0
    while next_root is not None:
        root_kind, root_id = next_root
        analysis = _analyze_admission_root(
            connection, root_kind, root_id, materialize=False
        )
        entry_sha = hashlib.sha256(analysis.entry_bytes).digest()
        try:
            connection.execute(
                "INSERT INTO admission_roots(root_kind,root_id,entry_json,entry_sha,"
                "framed_bytes,status,candidate_stream_sha) VALUES (?,?,?,?,?,?,?)",
                (
                    root_kind,
                    root_id,
                    analysis.entry_bytes,
                    entry_sha,
                    8 + len(analysis.entry_bytes),
                    analysis.entry["status"],
                    bytes.fromhex(str(analysis.entry["candidateStreamSha256"])),
                ),
            )
            connection.executemany(
                "INSERT INTO admission_candidates(root_kind,root_id,feature_ordinal,"
                "waterway_type,complete_named_relation,candidate_feature_sha,"
                "source_feature_sha,part_count,point_count) VALUES (?,?,?,?,?,?,?,?,?)",
                _candidate_rows(analysis),
            )
        except sqlite3.IntegrityError as error:
            raise GlobalWaterwayPackageError(
                "waterway admission root or candidate evidence is duplicated"
            ) from error
        aggregate.update(struct.pack(">Q", len(analysis.entry_bytes)))
        aggregate.update(analysis.entry_bytes)
        framed_bytes += 8 + len(analysis.entry_bytes)
        processed += 1
        processed_this_call += 1
        since_commit += 1
        status_counts[str(analysis.entry["status"])] += 1
        for reason in analysis.entry["reasonOccurrences"]:
            reason_counts[str(reason["reasonCode"])] += 1
        if analysis.legacy_first_terminal_reason is not None:
            legacy_counts[analysis.legacy_first_terminal_reason] += 1
            legacy_root_ids.append(root_id)
        if not bool(analysis.entry["structural"]["hasReachableWay"]):
            no_way_root_ids.append(root_id)
        if int(analysis.entry["candidateFeatureCount"]):
            candidate_roots += 1
        else:
            zero_candidate_roots += 1
        next_root = _next_root(connection, root_kind, root_id)
        checkpoint = _admission_checkpoint_document(
            processed_count=processed,
            next_root=next_root,
            framed_bytes=framed_bytes,
            aggregate_digest=aggregate,
            status_counts=status_counts,
            reason_counts=reason_counts,
            fatal_count=0,
        )
        if since_commit >= checkpoint_roots:
            _commit_admission_checkpoint(connection, checkpoint)
            since_commit = 0
        if (
            pause_after_roots is not None
            and processed_this_call >= pause_after_roots
        ):
            _commit_admission_checkpoint(connection, checkpoint)
            return False, MappingProxyType(checkpoint)
    _commit_admission_checkpoint(connection, checkpoint)
    receipt = _admission_receipt_document(
        connection,
        aggregate=aggregate,
        framed_bytes=framed_bytes,
        processed=processed,
        status_counts=status_counts,
        reason_counts=reason_counts,
        legacy_counts=legacy_counts,
        legacy_root_ids=legacy_root_ids,
        no_way_root_ids=no_way_root_ids,
        candidate_roots=candidate_roots,
        zero_candidate_roots=zero_candidate_roots,
        source_binding=source_binding,
        ingest_semantic_sha256=ingest_semantic_sha256,
    )
    _meta_set(connection, "admissionReceipt", receipt)
    connection.commit()
    return True, MappingProxyType(receipt)


def ingest_global_waterway_closure(
    *,
    opl_path: Path,
    root_ids_path: Path,
    work_directory: Path,
    source_binding: WaterwaySourceBinding,
    checkpoint_objects: int = 10_000,
    checkpoint_admission_roots: int = _ADMISSION_CHECKPOINT_ROOTS,
    pause_after_objects: int | None = None,
    pause_after_admission_roots: int | None = None,
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
    if (
        type(checkpoint_admission_roots) is not int
        or checkpoint_admission_roots <= 0
    ):
        raise GlobalWaterwayPackageError(
            "admission checkpoint root count must be positive"
        )
    if pause_after_objects is not None and (
        type(pause_after_objects) is not int or pause_after_objects <= 0
    ):
        raise GlobalWaterwayPackageError("pause object count must be positive")
    if pause_after_admission_roots is not None and (
        type(pause_after_admission_roots) is not int
        or pause_after_admission_roots <= 0
    ):
        raise GlobalWaterwayPackageError(
            "pause admission root count must be positive"
        )
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
    run_identity = _run_identity(
        source_binding, checkpoint_objects, checkpoint_admission_roots
    )
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
        admission_complete, admission = _admit_waterway_candidates(
            connection,
            source_binding=source_binding,
            ingest_semantic_sha256=semantic_sha256,
            checkpoint_roots=checkpoint_admission_roots,
            pause_after_roots=pause_after_admission_roots,
        )
        if not admission_complete:
            return GlobalWaterwayIngestResult(
                "paused",
                database_path,
                MappingProxyType(
                    {
                        "admissionCheckpoint": dict(admission),
                        "runIdentitySha256": run_sha256,
                        "schema": _INGEST_SCHEMA,
                        "state": "paused",
                    }
                ),
            )
        peaks = dict(_meta_get(connection, "peaks") or {})
        receipt = {
            "admission": dict(admission),
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
        model,
        osm_global_waterway_renderer,
        reference_presentation_policy,
        renderer_tile_package,
        semantic_model,
        sourced_text,
        waterway_parallel_render,
    )

    modules = {
        "model": model,
        "presentationPolicy": reference_presentation_policy,
        "renderer": osm_global_waterway_renderer,
        "rendererTilePackage": renderer_tile_package,
        "referenceSizePolicy": size_policy_module,
        "semanticModel": semantic_model,
        "source": source_module,
        "sourcedText": sourced_text,
        "store": __import__(__name__, fromlist=["*"]),
        "waterwayParallelRender": waterway_parallel_render,
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
    admission_receipt: Mapping[str, object],
    ingest_semantic_sha256: str,
    size_policy_binding: Mapping[str, object] | None = None,
) -> dict[str, object]:
    policy = admission_receipt.get("policy")
    if not isinstance(policy, Mapping):
        raise GlobalWaterwayPackageError(
            "waterway render identity requires v2 admission policy evidence"
        )
    if size_policy_binding is None:
        size_policy_binding = _reference_size_policy_binding(
            size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE
        )
    return {
        "admissionAggregateSha256": admission_receipt["aggregateSha256"],
        "admissionPolicySha256": policy["sha256"],
        "checkpointFeatures": checkpoint_features,
        "classifierSha256": classifier_sha256,
        "code": dict(code_identities),
        "packageId": package_id,
        "ingestSemanticSha256": ingest_semantic_sha256,
        "runtime": _runtime_identity_document(),
        "schema": _RENDER_RUN_SCHEMA,
        "sizePolicy": dict(size_policy_binding),
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


_INSERT_RECORD_SQL = (
    "INSERT INTO records("
    "z,y,x,posting_key,draw_order,priority,layer_group,feature_kind,"
    "variant_id,feature_id,sourced_sha,envelope,subtype,source_type"
    ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
)
_INSERT_RENDERED_FEATURE_SQL = (
    "INSERT INTO rendered_features(ordinal,source_kind,source_id,source_type,feature_sha) "
    "VALUES (?,?,?,?,?)"
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


def _stage_feature_render_frame(
    connection: sqlite3.Connection,
    *,
    ordinal: int,
    exact: "ExactWaterwayFeature",
    frame: "FeatureRenderFrame",
    registry: "HotIdRegistry",
    peaks: dict[str, int],
) -> None:
    from .waterway_parallel_render import (
        replay_registry_claims,
        validate_and_decode_record_rows,
        validate_frame_against_exact_source,
    )

    validate_frame_against_exact_source(ordinal, exact, frame)
    record_rows = validate_and_decode_record_rows(exact, frame)
    replay_registry_claims(registry, frame.registry_claims)
    for table, hot_id, full_sha256 in frame.identity_rows:
        _insert_hot_identity(connection, table, hot_id, full_sha256)
    for row in record_rows:
        try:
            connection.execute(_INSERT_RECORD_SQL, row)
        except sqlite3.IntegrityError as error:
            raise GlobalWaterwayPackageError(
                "duplicate global waterway renderer posting"
            ) from error
    try:
        connection.execute(
            _INSERT_RENDERED_FEATURE_SQL,
            frame.rendered_feature_row,
        )
    except sqlite3.IntegrityError as error:
        raise GlobalWaterwayPackageError(
            "duplicate exact waterway feature in render traversal"
        ) from error
    peaks["featurePostingBytes"] = max(
        peaks["featurePostingBytes"],
        frame.posting_bytes,
    )


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


def _remember_expected_hot_identity(
    identities: dict[bytes, bytes],
    hot_id: bytes,
    full_sha256: bytes,
) -> None:
    previous = identities.setdefault(hot_id, full_sha256)
    if previous != full_sha256:
        raise GlobalWaterwayPackageError(
            "checkpointed renderer prefix contains a fatal hot-ID collision"
        )


def _extend_expected_renderer_identities(
    expected: dict[str, dict[bytes, bytes]],
    exact: object,
    rendered: object,
) -> None:
    feature_hot = exact.source_feature_sha256[:8]
    _remember_expected_hot_identity(
        expected["feature_ids"], feature_hot, exact.source_feature_sha256
    )
    for records in rendered.tiles.values():
        for record in records:
            renderer = record.renderer_record
            variant = renderer.variant
            sourced = record.sourced_text
            if sourced is None:
                raise GlobalWaterwayPackageError(
                    "checkpointed renderer prefix lacks exact sourced text"
                )
            _remember_expected_hot_identity(
                expected["geometry_ids"],
                variant.geometry_id.to_bytes(8, "big"),
                variant.placement.placement_geometry_sha256,
            )
            _remember_expected_hot_identity(
                expected["label_ids"],
                variant.placement.label_candidate_id.to_bytes(8, "big"),
                variant.placement.label_candidate_sha256,
            )
            _remember_expected_hot_identity(
                expected["variant_ids"],
                variant.canonical_variant_id.to_bytes(8, "big"),
                variant_fingerprint(variant).full_sha256,
            )
            _remember_expected_hot_identity(
                expected["sourced_ids"],
                sourced.hot_id.to_bytes(8, "big"),
                sourced.full_sha256,
            )


def _validate_renderer_identity_tables(
    connection: sqlite3.Connection,
    expected: Mapping[str, Mapping[bytes, bytes]],
) -> None:
    for table in (
        "feature_ids",
        "variant_ids",
        "geometry_ids",
        "label_ids",
        "sourced_ids",
    ):
        actual = tuple(
            (bytes(row[0]), bytes(row[1]))
            for row in connection.execute(
                f"SELECT hot_id,full_sha FROM {table} ORDER BY hot_id"
            )
        )
        wanted = tuple(sorted(expected[table].items()))
        if actual != wanted:
            raise GlobalWaterwayPackageError(
                f"checkpointed renderer {table} identity prefix differs"
            )


def _validated_renderer_prefix_stream(
    connection: sqlite3.Connection,
    *,
    source_binding: WaterwaySourceBinding,
    zooms: tuple[int, ...],
    run_identity: Mapping[str, object],
    rendered_prefix: int,
):
    from .osm_global_waterway_renderer import build_adaptive_waterway_feature
    from .semantic_model import HotIdRegistry

    if type(rendered_prefix) is not int or rendered_prefix < 0:
        raise GlobalWaterwayPackageError(
            "renderer checkpointed feature prefix is malformed"
        )
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
    expected = {
        table: {}
        for table in (
            "feature_ids",
            "variant_ids",
            "geometry_ids",
            "label_ids",
            "sourced_ids",
        )
    }
    registry = HotIdRegistry()
    source = iter(
        _iter_exact_waterway_features(connection, source_binding=source_binding)
    )
    for ordinal in range(rendered_prefix):
        try:
            exact = next(source)
        except StopIteration as error:
            raise GlobalWaterwayPackageError(
                "renderer feature traversal ended before checkpoint"
            ) from error
        rendered = build_adaptive_waterway_feature(
            feature=exact,
            source_generation_sha256=source_binding.planet_sha256,
            classifier_sha256=str(run_identity["classifierSha256"]),
            zooms=zooms,
            identity_registry=registry,
        )
        row = connection.execute(
            "SELECT source_kind,source_id,source_type,feature_sha "
            "FROM rendered_features WHERE ordinal=?",
            (ordinal,),
        ).fetchone()
        wanted = (
            exact.source_kind,
            exact.source_id,
            exact.waterway_type,
            exact.source_feature_sha256,
        )
        if row is None or (
            str(row[0]), int(row[1]), str(row[2]), bytes(row[3])
        ) != wanted:
            raise GlobalWaterwayPackageError(
                "renderer checkpointed feature prefix differs"
            )
        _validate_rendered_feature_prefix(connection, exact, rendered)
        _extend_expected_renderer_identities(expected, exact, rendered)
    _validate_renderer_identity_tables(connection, expected)
    return source, registry


def _commit_render_checkpoint(
    connection: sqlite3.Connection,
    database_path: Path,
    checkpoint: Mapping[str, object],
    peaks: dict[str, int],
) -> None:
    _meta_set(connection, "renderCheckpoint", dict(checkpoint))
    _meta_set(connection, "peaks", peaks)
    connection.commit()
    observed_bytes = _recovery_adjusted_database_bytes(connection, database_path)
    peaks["observedPersistentSqliteBytesAtCheckpoints"] = max(
        peaks["observedPersistentSqliteBytesAtCheckpoints"],
        observed_bytes,
    )
    _meta_set(connection, "peaks", peaks)
    connection.commit()


def _recovery_adjusted_database_bytes(
    connection: sqlite3.Connection, database_path: Path
) -> int:
    recovery = _meta_get(connection, "renderRecoveryReceipt")
    recovery_overhead = 0
    if isinstance(recovery, dict):
        value = recovery.get("sqliteEvidenceOverheadBytes")
        if type(value) is not int or value < 0:
            raise GlobalWaterwayPackageError(
                "waterway recovery SQLite evidence overhead is malformed"
            )
        recovery_overhead = value
    return max(0, _database_bytes(database_path) - recovery_overhead)


def _configure_render_connection(connection: sqlite3.Connection) -> None:
    connection.execute("PRAGMA journal_mode=DELETE")
    connection.execute("PRAGMA synchronous=FULL")
    connection.execute("PRAGMA temp_store=FILE")
    connection.execute("PRAGMA mmap_size=0")
    connection.execute("PRAGMA cache_size=-65536")


def _resume_renderer_write_reservation(
    connection: sqlite3.Connection,
    trusted_data_version: int | None,
) -> int:
    if connection.in_transaction:
        raise GlobalWaterwayPackageError(
            "renderer write reservation is already active"
        )
    connection.execute("BEGIN IMMEDIATE")
    observed = int(connection.execute("PRAGMA data_version").fetchone()[0])
    if trusted_data_version is None:
        return observed
    if observed != trusted_data_version:
        raise GlobalWaterwayPackageError(
            "renderer checkpoint database changed through another connection"
        )
    return trusted_data_version


def _default_parallel_render_limits():
    from .waterway_parallel_render import ParallelRenderLimits

    return ParallelRenderLimits(
        workers=1,
        max_in_flight_jobs=2,
        max_in_flight_points=1_048_576,
        max_points_per_job=524_288,
        max_in_flight_input_bytes=256 * 1024 * 1024,
        max_input_bytes_per_job=128 * 1024 * 1024,
        max_spool_bytes=2 * 1024 * 1024 * 1024,
        max_spool_bytes_per_job=1024 * 1024 * 1024,
    )


def _canonical_render_run_sha256(run_identity: Mapping[str, object]) -> str:
    return hashlib.sha256(_canonical_json_bytes(dict(run_identity))).hexdigest()


def _default_render_spool_directory(
    database_path: Path,
    render_run_identity_sha256: str,
) -> Path:
    return Path(
        os.path.abspath(
            database_path.parent
            / ("waterway-render-spool-" + render_run_identity_sha256[:16])
        )
    )


def _stage_renderer_records(
    connection: sqlite3.Connection,
    database_path: Path,
    *,
    source_binding: WaterwaySourceBinding,
    zooms: tuple[int, ...],
    checkpoint_features: int,
    pause_after_features: int | None,
    run_identity: Mapping[str, object],
    trusted_data_version: int | None = None,
    parallel_limits: "ParallelRenderLimits | None" = None,
    spool_directory: str | os.PathLike[str] | None = None,
) -> bool:
    from .waterway_parallel_render import (
        ParallelFeatureRenderer,
        ParallelRenderLimits,
        finish_spool_directory,
        prepare_spool_directory,
    )

    if parallel_limits is None:
        parallel_limits = _default_parallel_render_limits()
    if type(parallel_limits) is not ParallelRenderLimits:
        raise GlobalWaterwayPackageError("parallel render limits are invalid")
    render_run_sha256 = _canonical_render_run_sha256(run_identity)
    if spool_directory is None:
        spool_directory = _default_render_spool_directory(
            database_path,
            render_run_sha256,
        )
    package_id = run_identity.get("packageId")
    if type(package_id) is not str:
        raise GlobalWaterwayPackageError("renderer run identity package ID is malformed")

    try:
        admission = _meta_get(connection, "admissionReceipt")
        if (
            not isinstance(admission, dict)
            or admission.get("fatalCount") != 0
            or admission.get("aggregateSha256")
            != run_identity.get("admissionAggregateSha256")
            or admission.get("policy", {}).get("sha256")
            != run_identity.get("admissionPolicySha256")
        ):
            raise GlobalWaterwayPackageError(
                "renderer requires complete matching v2 waterway admission"
            )
        if (
            trusted_data_version is not None
            and (type(trusted_data_version) is not int or trusted_data_version < 0)
        ):
            raise GlobalWaterwayPackageError(
                "renderer trusted database version is malformed"
            )
        trusted_data_version = _resume_renderer_write_reservation(
            connection,
            trusted_data_version,
        )
        stored_identity = _meta_get(connection, "renderRunIdentity")
        if stored_identity is None:
            _meta_set(connection, "renderRunIdentity", dict(run_identity))
            _meta_set(
                connection,
                "renderCheckpoint",
                {"renderComplete": False, "renderedFeatures": 0},
            )
            connection.commit()
            _resume_renderer_write_reservation(connection, trusted_data_version)
        elif stored_identity != dict(run_identity):
            raise GlobalWaterwayPackageError(
                "renderer checkpoint identity differs from exact source/config/code identity"
            )
        checkpoint = dict(_meta_get(connection, "renderCheckpoint") or {})
        rendered_prefix = int(checkpoint.get("renderedFeatures", 0))
        source, registry = _validated_renderer_prefix_stream(
            connection,
            source_binding=source_binding,
            zooms=zooms,
            run_identity=run_identity,
            rendered_prefix=rendered_prefix,
        )
        if (
            pause_after_features is not None
            and pause_after_features <= rendered_prefix
        ):
            connection.rollback()
            return False
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
        owned_spool_directory = prepare_spool_directory(
            spool_directory,
            package_id=package_id,
            render_run_identity_sha256=render_run_sha256,
            source_document_sha256=source_binding.planet_sha256,
        )
        since_commit = 0
        seen = rendered_prefix
        pending_descriptors = []
        with ParallelFeatureRenderer(
            source,
            start_ordinal=rendered_prefix,
            package_id=package_id,
            source_generation_sha256=source_binding.planet_sha256,
            classifier_sha256=str(run_identity["classifierSha256"]),
            zooms=zooms,
            render_run_identity_sha256=render_run_sha256,
            spool_directory=owned_spool_directory,
            limits=parallel_limits,
            pause_after_features=pause_after_features,
        ) as renderer:
            while True:
                batch = renderer.next_batch()
                if batch is None:
                    break
                descriptor, exact_features, frames = batch
                if (
                    descriptor.start_ordinal != seen
                    or descriptor.end_ordinal_exclusive
                    != seen + len(exact_features)
                    or len(exact_features) != len(frames)
                ):
                    raise GlobalWaterwayPackageError(
                        "parallel renderer yielded a noncontiguous exact source range"
                    )
                pending_descriptors.append(descriptor)
                for exact, frame in zip(exact_features, frames):
                    ordinal = seen
                    _stage_feature_render_frame(
                        connection,
                        ordinal=ordinal,
                        exact=exact,
                        frame=frame,
                        registry=registry,
                        peaks=peaks,
                    )
                    seen = ordinal + 1
                    checkpoint = {
                        "renderComplete": False,
                        "renderedFeatures": seen,
                    }
                    since_commit += 1
                    checkpoint_committed = False
                    if since_commit >= checkpoint_features:
                        _commit_render_checkpoint(
                            connection, database_path, checkpoint, peaks
                        )
                        since_commit = 0
                        checkpoint_committed = True
                        releasable = [
                            value
                            for value in pending_descriptors
                            if value.end_ordinal_exclusive <= seen
                        ]
                        for committed_descriptor in releasable:
                            renderer.release_batch(committed_descriptor)
                            pending_descriptors.remove(committed_descriptor)
                    if pause_after_features is not None and seen >= pause_after_features:
                        if not checkpoint_committed:
                            _commit_render_checkpoint(
                                connection, database_path, checkpoint, peaks
                            )
                            releasable = [
                                value
                                for value in pending_descriptors
                                if value.end_ordinal_exclusive <= seen
                            ]
                            for committed_descriptor in releasable:
                                renderer.release_batch(committed_descriptor)
                                pending_descriptors.remove(committed_descriptor)
                        return False
                    if checkpoint_committed:
                        _resume_renderer_write_reservation(
                            connection,
                            trusted_data_version,
                        )
            checkpoint = {"renderComplete": True, "renderedFeatures": seen}
            _commit_render_checkpoint(connection, database_path, checkpoint, peaks)
            for descriptor in tuple(pending_descriptors):
                if descriptor.end_ordinal_exclusive > seen:
                    raise GlobalWaterwayPackageError(
                        "parallel renderer retained an uncommitted ordinal range"
                    )
                renderer.release_batch(descriptor)
                pending_descriptors.remove(descriptor)
            finish_spool_directory(
                owned_spool_directory,
                package_id=package_id,
                render_run_identity_sha256=render_run_sha256,
                source_document_sha256=source_binding.planet_sha256,
            )
        _resume_renderer_write_reservation(connection, trusted_data_version)
        return True
    except BaseException as error:
        try:
            connection.rollback()
        except BaseException as rollback_error:
            error.add_note(f"renderer checkpoint rollback failed: {rollback_error}")
        raise


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
    *,
    trusted_data_version: int | None = None,
) -> None:
    if trusted_data_version is not None and (
        not connection.in_transaction
        or int(connection.execute("PRAGMA data_version").fetchone()[0])
        != trusted_data_version
    ):
        raise GlobalWaterwayPackageError(
            "partial ownership lacks the trusted renderer write reservation"
        )
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
        if trusted_data_version is not None:
            _resume_renderer_write_reservation(
                connection,
                trusted_data_version,
            )
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
    *,
    size_policy_mode: object,
    available_destination_bytes: int | None,
    trusted_data_version: int | None = None,
) -> tuple[int, int, dict[str, int]]:
    _ensure_owned_partial_directory(
        connection,
        partial_directory,
        trusted_data_version=trusted_data_version,
    )
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
    enforce_global_waterway_storage_ceiling(
        expected_index_bytes + records_bytes,
        size_policy_mode=size_policy_mode,
        available_destination_bytes=available_destination_bytes,
    )
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
                    expected_index_bytes + records_bytes + len(compressed),
                    size_policy_mode=size_policy_mode,
                    available_destination_bytes=available_destination_bytes,
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
                if trusted_data_version is not None:
                    _resume_renderer_write_reservation(
                        connection,
                        trusted_data_version,
                    )
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
        _recovery_adjusted_database_bytes(connection, database_path),
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
    if trusted_data_version is not None:
        _resume_renderer_write_reservation(
            connection,
            trusted_data_version,
        )
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
    admission_aggregate_sha256: str,
    admission_policy_sha256: str,
    ingest_semantic_sha256: str,
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
            "admissionAggregateSha256": admission_aggregate_sha256,
            "admissionPolicySha256": admission_policy_sha256,
            "adaptiveGeometry": {
                "errorBound": "one-half-pixel-at-record-integer-zoom",
                "pathScope": "admitted-exact-path-or-incomplete-relation-subset",
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
            "ingestSemanticSha256": ingest_semantic_sha256,
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
    recovery_receipt: Mapping[str, object] | None = None,
    immediate_pre_publish_validator: Callable[[], None] | None = None,
) -> Mapping[str, object]:
    if not connection.in_transaction:
        raise GlobalWaterwayPackageError(
            "waterway publication lacks renderer write reservation"
        )
    trusted_data_version = int(
        connection.execute("PRAGMA data_version").fetchone()[0]
    )
    if _render_code_identities() != dict(code_identities):
        raise GlobalWaterwayPackageError("waterway renderer code identity drifted")
    raw_size_policy = render_run_identity.get("sizePolicy")
    if not isinstance(raw_size_policy, Mapping):
        raise GlobalWaterwayPackageError(
            "waterway renderer size-policy identity is absent"
        )
    policy_mode = raw_size_policy.get("mode")
    current_size_policy = _reference_size_policy_binding(policy_mode)
    if dict(raw_size_policy) != dict(current_size_policy):
        raise GlobalWaterwayPackageError(
            "waterway renderer size-policy identity drifted"
        )
    _ensure_owned_partial_directory(
        connection,
        partial_directory,
        trusted_data_version=trusted_data_version,
    )
    semantic_sha256 = _renderer_semantic_sha256(connection)
    projection = _projection_stats(connection, windows)
    available_destination_bytes = _current_visual_staging_capacity(
        connection,
        partial_directory=partial_directory,
        output_directory=output_directory,
        size_policy_mode=policy_mode,
    )
    _, present_tiles, peaks = _build_runtime_files(
        connection,
        database_path,
        partial_directory,
        windows,
        size_policy_mode=policy_mode,
        available_destination_bytes=available_destination_bytes,
        trusted_data_version=trusted_data_version,
    )
    if recovery_receipt is not None:
        ingest_for_peak = _meta_get(connection, "ingestReceipt")
        ingest_peak = (
            ingest_for_peak.get("peakResources", {}).get(
                "observedPersistentSqliteBytesAtCheckpoints"
            )
            if isinstance(ingest_for_peak, dict)
            else None
        )
        if type(ingest_peak) is not int or ingest_peak < 0:
            raise GlobalWaterwayPackageError(
                "waterway recovery ingest SQLite peak evidence is malformed"
            )
        peaks["observedPersistentSqliteBytesAtCheckpoints"] = max(
            ingest_peak,
            _recovery_adjusted_database_bytes(connection, database_path),
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
        admission_aggregate_sha256=str(
            render_run_identity["admissionAggregateSha256"]
        ),
        admission_policy_sha256=str(
            render_run_identity["admissionPolicySha256"]
        ),
        ingest_semantic_sha256=str(
            render_run_identity["ingestSemanticSha256"]
        ),
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
    size_decision = enforce_global_waterway_storage_ceiling(
        runtime_bytes,
        size_policy_mode=policy_mode,
        available_destination_bytes=available_destination_bytes,
    )
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
        "admission": ingest_receipt["admission"],
        "attribution": manifest["globalWaterwaySupplement"]["attribution"],
        "build": {
            "classifierSha256": render_run_identity["classifierSha256"],
            "code": dict(code_identities),
            "runIdentity": dict(render_run_identity),
            "runIdentitySha256": render_run_sha256,
            "sizePolicy": {
                "binding": dict(current_size_policy),
                "decision": dict(size_decision),
            },
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
        "rendererSemanticStreamSha256": semantic_sha256,
        "schema": _BUILD_SCHEMA,
        "source": source_binding.document(),
    }
    if recovery_receipt is not None:
        receipt["build"]["recovery"] = dict(recovery_receipt)
    final_semantic_document = {
        "admissionAggregateSha256": render_run_identity[
            "admissionAggregateSha256"
        ],
        "admissionPolicySha256": render_run_identity["admissionPolicySha256"],
        "ingestSemanticSha256": render_run_identity["ingestSemanticSha256"],
        "manifestSha256": output_files[0]["sha256"],
        "rendererRunIdentitySha256": render_run_sha256,
        "rendererSemanticStreamSha256": semantic_sha256,
        "schema": "flightalert.experiment8.osm-waterway-final-semantic-identity.v2",
        "source": source_binding.document(),
    }
    receipt["finalSemanticIdentitySha256"] = hashlib.sha256(
        b"FAE8WATERFINAL2\0" + _canonical_json_bytes(final_semantic_document)
    ).hexdigest()
    projection["publishedDirectoryBytes"] = 0

    def apply_size_decision(decision: Mapping[str, object]) -> None:
        receipt["build"]["sizePolicy"]["decision"] = dict(decision)
        projection["preferredCeilingExceeded"] = decision[
            "preferredComponentPackageCeilingExceeded"
        ]
        projection["hardComponentCeilingExceeded"] = decision[
            "hardComponentPackageCeilingExceeded"
        ]
        projection["preferredMandatoryPhoneFootprintCeilingExceeded"] = decision[
            "preferredMandatoryPhoneFootprintCeilingExceeded"
        ]
        projection["hardMandatoryPhoneFootprintCeilingExceeded"] = decision[
            "hardMandatoryPhoneFootprintCeilingExceeded"
        ]
        projection["mandatoryPhoneFootprintBytes"] = decision[
            "mandatoryPhoneFootprintBytes"
        ]

    final_recovery = None
    if recovery_receipt is not None:
        final_recovery = dict(recovery_receipt)
        final_recovery["publishedDirectoryBytes"] = 0
        final_recovery["sizePolicyDecision"] = dict(size_decision)
        receipt["build"]["recovery"] = final_recovery
    apply_size_decision(size_decision)

    def converge_receipt(
        publication_boundary_free_bytes: int | None,
    ) -> tuple[bytes, int, Mapping[str, object]]:
        nonlocal size_decision
        for padding_bytes in range(65):
            if padding_bytes:
                projection["accountingConvergencePadding"] = "x" * (
                    padding_bytes - 1
                )
            else:
                projection.pop("accountingConvergencePadding", None)
            for _ in range(32):
                candidate_bytes = _canonical_json_bytes(receipt)
                candidate_total = runtime_bytes + len(candidate_bytes)
                candidate_decision = enforce_global_waterway_storage_ceiling(
                    candidate_total,
                    size_policy_mode=policy_mode,
                    available_destination_bytes=available_destination_bytes,
                )
                if publication_boundary_free_bytes is not None:
                    candidate_decision = _bind_publication_boundary_capacity(
                        candidate_decision,
                        destination_free_bytes=publication_boundary_free_bytes,
                    )
                stable = (
                    projection["publishedDirectoryBytes"] == candidate_total
                    and receipt["build"]["sizePolicy"]["decision"]
                    == dict(candidate_decision)
                )
                if final_recovery is not None:
                    stable = (
                        stable
                        and final_recovery["publishedDirectoryBytes"]
                        == candidate_total
                        and final_recovery["sizePolicyDecision"]
                        == dict(candidate_decision)
                    )
                if stable:
                    return candidate_bytes, candidate_total, candidate_decision
                projection["publishedDirectoryBytes"] = candidate_total
                size_decision = candidate_decision
                apply_size_decision(candidate_decision)
                if final_recovery is not None:
                    final_recovery["publishedDirectoryBytes"] = candidate_total
                    final_recovery["sizePolicyDecision"] = dict(candidate_decision)
        raise GlobalWaterwayPackageError(
            "published waterway directory byte accounting did not converge"
        )

    receipt_path = partial_directory / "build-receipt.json"
    if (
        policy_mode
        == size_policy_module.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    ):
        bound_boundary_free = None
        for _ in range(16):
            try:
                measured_boundary_free = (
                    size_policy_module.destination_available_bytes(output_directory)
                )
            except size_policy_module.ReferenceSizePolicyError as error:
                raise GlobalWaterwayPackageError(str(error)) from error
            if measured_boundary_free == bound_boundary_free:
                break
            bound_boundary_free = measured_boundary_free
            receipt_bytes, published_bytes, size_decision = converge_receipt(
                bound_boundary_free
            )
            _write_synced(receipt_path, receipt_bytes)
        else:
            raise GlobalWaterwayPackageError(
                "visual-evaluation publication-boundary capacity did not stabilize"
            )
    else:
        receipt_bytes, published_bytes, size_decision = converge_receipt(None)
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
    if immediate_pre_publish_validator is not None:
        immediate_pre_publish_validator()
    current_available = None
    if (
        policy_mode
        == size_policy_module.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    ):
        try:
            current_available = size_policy_module.destination_available_bytes(
                output_directory
            )
        except size_policy_module.ReferenceSizePolicyError as error:
            raise GlobalWaterwayPackageError(str(error)) from error
    immediate_decision = enforce_global_waterway_storage_ceiling(
        published_bytes,
        size_policy_mode=policy_mode,
        available_destination_bytes=available_destination_bytes,
    )
    if (
        policy_mode
        == size_policy_module.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    ):
        immediate_decision = _bind_publication_boundary_capacity(
            immediate_decision,
            destination_free_bytes=current_available,
        )
        expected_decision = (
            receipt["build"]["sizePolicy"]["decision"]
            if recovery_receipt is None
            else receipt["build"]["recovery"]["sizePolicyDecision"]
        )
        if dict(immediate_decision) != expected_decision:
            raise GlobalWaterwayPackageError(
                "visual-evaluation destination capacity drifted after receipt binding"
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
    size_policy_mode: object = size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE,
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
    size_policy_binding = _reference_size_policy_binding(size_policy_mode)
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
    if code_identities.get("referenceSizePolicy") != size_policy_binding.get(
        "module"
    ):
        raise GlobalWaterwayPackageError(
            "waterway renderer size-policy module identity differs"
        )
    classifier = classifier_identity_sha256()
    render_identity = _render_run_identity(
        package_id=checked_package_id,
        source_binding=source_binding,
        zooms=normalized_zooms,
        checkpoint_features=checkpoint_features,
        code_identities=code_identities,
        classifier_sha256=classifier,
        admission_receipt=ingest.receipt["admission"],
        ingest_semantic_sha256=str(ingest.receipt["semanticSha256"]),
        size_policy_binding=size_policy_binding,
    )
    render_sha256 = _canonical_render_run_sha256(render_identity)
    partial_directory = output_directory.with_name(
        output_directory.name + ".partial-" + render_sha256[:16]
    )
    connection = sqlite3.connect(ingest.database_path)
    try:
        _configure_render_connection(connection)
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
    size_policy_mode: object = size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE,
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
        size_policy_mode=size_policy_mode,
    )


__all__ = [
    "enforce_global_waterway_storage_ceiling",
    "ingest_global_waterway_closure",
    "iter_exact_waterway_features",
    "render_fixture_global_waterway_package",
]
