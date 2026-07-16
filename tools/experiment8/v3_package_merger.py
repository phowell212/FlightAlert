from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import shutil
import sqlite3
import struct
import tempfile
import unicodedata
import zlib
from collections import Counter, deque
from collections.abc import Iterable, Iterator, Mapping, Sequence
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

from .model import TileKey
from .reference_presentation_policy import PRESENTATION_POLICY_SHA256, SemanticSubtype
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    INDEX_FLAG_PRESENT,
    MAX_RECORDS_PER_TILE,
    MAX_SOURCED_TEXT_RECORD_BYTES,
    MAX_TILE_BYTES,
    PAYLOAD_SCHEMA,
    SOURCED_TEXT_POLICY_SHA256,
    TILE_PAYLOAD_MAGIC,
    decode_tile_payload,
    encode_index_entry,
    raw_deflate,
    raw_hash32,
)
from .semantic_model import renderer_record_bytes, variant_fingerprint
from .sourced_text import UNICODE_SCRIPT_PROFILE_SHA256


_MERGE_SCHEMA = "flightalert.experiment8.v3-package-merge.v1"
_RECEIPT_SCHEMA = "flightalert.experiment8.v3-package-merge-receipt.v1"
_AUTHORITY_MERGE_SCHEMA = "flightalert.experiment8.v3-package-merge.v2"
_AUTHORITY_RECEIPT_SCHEMA = (
    "flightalert.experiment8.v3-package-merge-receipt.v2"
)
_MAX_MANIFEST_BYTES = 16 * 1024 * 1024
_FILE_HASH_CHUNK_BYTES = 4 * 1024 * 1024
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_MAX_COMPRESSED_TILE_BYTES = (
    MAX_TILE_BYTES
    + (MAX_TILE_BYTES >> 12)
    + (MAX_TILE_BYTES >> 14)
    + (MAX_TILE_BYTES >> 25)
    + 13
)
_MAX_COMPRESSION_WORKERS = 64
_DEFAULT_COMPRESSION_WORKER_LIMIT = 12
_PARALLEL_BATCH_TILE_LIMIT = 512
_PARALLEL_BATCH_RAW_BYTES = 4 * 1024 * 1024
_PARALLEL_PENDING_RAW_BYTES = 128 * 1024 * 1024
_PARALLEL_PENDING_BATCHES_PER_WORKER = 2
_TILE_PAYLOAD_HEADER_BYTES = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
_WATERWAY_BUILD_SCHEMA = "flightalert.experiment8.osm-global-waterway-build.v2"
_WATERWAY_RECOVERY_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-render-recovery.v1"
)
_WATERWAY_ADMISSION_SCHEMA = (
    "flightalert.experiment8.osm-waterway-admission-receipt.v2"
)
_WATERWAY_RENDER_RUN_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-render-run.v2"
)
_WATERWAY_FINAL_SEMANTIC_SCHEMA = (
    "flightalert.experiment8.osm-waterway-final-semantic-identity.v2"
)
_WATERWAY_FINAL_SEMANTIC_DOMAIN = b"FAE8WATERFINAL2\0"
_RECOVERY_PRESERVED_META_KEYS = {
    "admissionCheckpoint",
    "admissionReceipt",
    "admissionRunIdentity",
    "checkpoint",
    "ingestReceipt",
    "runIdentity",
}
_RECOVERY_SOURCE_TABLES = {
    "admission_candidates",
    "admission_roots",
    "nodes",
    "relation_members",
    "relations",
    "roots",
    "way_nodes",
    "ways",
}
_RECOVERY_RENDERER_TABLES = {
    "feature_ids",
    "geometry_ids",
    "label_ids",
    "records",
    "rendered_features",
    "sourced_ids",
    "variant_ids",
}
_SIZE_POLICY_BINDING_SCHEMA = (
    "flightalert.experiment8.reference-size-policy-binding.v1"
)
_SIZE_POLICY_DECISION_SCHEMA = (
    "flightalert.experiment8.reference-size-decision.v1"
)
_BUDGETED_SIZE_POLICY = "budgeted-release-v1"
_VISUAL_SIZE_POLICY = "complete-uncompressed-visual-evaluation-v1"
_AUTHORITY_SEMANTIC_RECOMPUTE = "recompute"
_AUTHORITY_SEMANTIC_RECEIPT_BOUND_VISUAL = "receipt-bound-visual-evaluation"
_AUTHORITY_SEMANTIC_MODES = frozenset(
    {
        _AUTHORITY_SEMANTIC_RECOMPUTE,
        _AUTHORITY_SEMANTIC_RECEIPT_BOUND_VISUAL,
    }
)
_RECEIPT_BOUND_VISUAL_AUTHORITY_SEMANTIC_VERIFICATION = {
    "mode": _AUTHORITY_SEMANTIC_RECEIPT_BOUND_VISUAL,
    "strictDocumentaryProofDeferred": True,
    "runtimeFileDigestsVerifiedByMergeStream": True,
}
_SIZE_POLICY_DOCUMENT = {
    "constraints": {
        "contentPruningAuthorized": False,
        "nonSizeBoundsMayBeWeakened": False,
        "visualEvaluationRequiresCompleteUncompressedPackage": True,
    },
    "destinationReserveBytes": 1_500_000_000,
    "historicalBudgets": {
        "hardComponentPackageBytes": 38_500_000_000,
        "hardMandatoryPhoneFootprintBytes": 40_000_000_000,
        "preferredComponentPackageBytes": 23_500_000_000,
        "preferredMandatoryPhoneFootprintBytes": 25_000_000_000,
    },
    "modes": [_BUDGETED_SIZE_POLICY, _VISUAL_SIZE_POLICY],
    "schema": "flightalert.experiment8.reference-size-policy.v2",
    "visualEvaluationCapacityBasis": (
        "fresh-destination-free-plus-exact-owned-partial-before-staging-"
        "and-fresh-final-reserve-proof"
    ),
    "visualEvaluationCapacityPersistence": (
        "memory-only-sqlite-capacity-is-not-authority"
    ),
}


class V3PackageMergeError(ValueError):
    """One or more V3 packages cannot be merged without weakening truth."""


@dataclass(frozen=True, order=True, slots=True)
class _Window:
    z: int
    x_min: int
    x_max: int
    y_min: int
    y_max: int

    @property
    def tile_count(self) -> int:
        return (self.x_max - self.x_min + 1) * (self.y_max - self.y_min + 1)

    def contains(self, tile: TileKey) -> bool:
        return (
            tile.z == self.z
            and self.x_min <= tile.x <= self.x_max
            and self.y_min <= tile.y <= self.y_max
        )

    def ordinal(self, tile: TileKey) -> int:
        width = self.x_max - self.x_min + 1
        return (tile.y - self.y_min) * width + tile.x - self.x_min

    def document(self) -> dict[str, int]:
        return {
            "z": self.z,
            "xMin": self.x_min,
            "xMax": self.x_max,
            "yMin": self.y_min,
            "yMax": self.y_max,
            "tileCount": self.tile_count,
        }


@dataclass(frozen=True, slots=True)
class _Range:
    window: _Window
    first_ordinal: int


@dataclass(frozen=True, slots=True)
class _InputPackage:
    directory: Path
    package_id: str
    manifest_sha256: str
    manifest_bytes: int
    records_sha256: str
    records_bytes: int
    index_sha256: str
    index_bytes: int
    ranges: tuple[_Range, ...]
    tile_count: int
    complete_declared_scope: bool
    complete_whole_earth_dictionary: bool
    renderer_semantic_stream_sha256: str | None = None
    global_waterway_supplement: Mapping[str, object] | None = None

    def ordinal(self, tile: TileKey) -> int | None:
        for item in self.ranges:
            if item.window.contains(tile):
                return item.first_ordinal + item.window.ordinal(tile)
        return None

    def binding(self, role: str) -> dict[str, object]:
        return {
            "role": role,
            "packageId": self.package_id,
            "manifestSha256": self.manifest_sha256,
            "manifestBytes": self.manifest_bytes,
            "recordsSha256": self.records_sha256,
            "recordsBytes": self.records_bytes,
            "tileIndexSha256": self.index_sha256,
            "tileIndexBytes": self.index_bytes,
        }


@dataclass(frozen=True, slots=True)
class _AuthorityReceipt:
    package_directory: Path
    package_id: str
    document: Mapping[str, object]
    byte_count: int
    sha256: str
    size_policy_binding: Mapping[str, object]
    renderer_semantic_stream_sha256: str

    def evidence(self) -> dict[str, object]:
        return {
            "bytes": self.byte_count,
            "document": dict(self.document),
            "packageId": self.package_id,
            "role": "supplement",
            "sha256": self.sha256,
        }


@dataclass(slots=True)
class _InputState:
    index_handle: object
    records_handle: object
    next_ordinal: int = 0
    next_record_offset: int = 0
    present_tiles: int = 0
    index_digest: object = field(default_factory=hashlib.sha256, repr=False)
    records_digest: object = field(default_factory=hashlib.sha256, repr=False)


@dataclass(frozen=True, slots=True)
class _RawEnvelope:
    raw: bytes
    renderer_bytes: bytes
    posting_key: tuple[int, int, int, int]
    order_key: tuple[int, int, int, int, int, int, bytes, bytes]
    subtype: SemanticSubtype
    feature_id: int
    variant_id: int
    variant_full_sha256: bytes


@dataclass(frozen=True, slots=True)
class MergeResult:
    output_directory: Path
    manifest: Mapping[str, object]
    receipt: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class _EncodedTile:
    compressed: bytes
    raw_length: int
    raw_hash32: int


@dataclass(frozen=True, slots=True)
class _EncodedBatchResult:
    tiles: tuple[_EncodedTile | None, ...]
    failure: BaseException | None


@dataclass(frozen=True, slots=True)
class _PendingEncodedBatch:
    future: Future[_EncodedBatchResult]
    raw_bytes: int


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
    except (TypeError, UnicodeError, ValueError) as error:
        raise V3PackageMergeError("canonical JSON value is unsupported") from error


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise V3PackageMergeError(f"JSON contains duplicate key {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise V3PackageMergeError(f"JSON contains forbidden numeric constant {value}")


def _strict_json(raw: bytes, label: str) -> dict[str, object]:
    if type(raw) is not bytes:
        raise V3PackageMergeError(f"{label} must be immutable bytes")
    try:
        document = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except V3PackageMergeError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise V3PackageMergeError(f"{label} is not strict UTF-8 JSON") from error
    if type(document) is not dict:
        raise V3PackageMergeError(f"{label} root must be an object")
    return document


def _same_canonical_json(left: object, right: object) -> bool:
    return _canonical_json_bytes(left) == _canonical_json_bytes(right)


def _exact_int(value: object, label: str, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise V3PackageMergeError(
            f"{label} must be an exact integer in [{minimum}, {maximum}]"
        )
    return value


def _exact_bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise V3PackageMergeError(f"{label} must be Boolean")
    return value


def _exact_text(value: object, label: str) -> str:
    if type(value) is not str or not value:
        raise V3PackageMergeError(f"{label} must be nonempty text")
    if unicodedata.normalize("NFC", value) != value:
        raise V3PackageMergeError(f"{label} must be NFC")
    if any(character == "\ufffd" or unicodedata.category(character) in {"Cc", "Cs"} for character in value):
        raise V3PackageMergeError(f"{label} contains invalid Unicode")
    return value


def _exact_sha256(value: object, label: str) -> str:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise V3PackageMergeError(f"{label} must be one lowercase SHA-256")
    return value


def _exact_mapping(value: object, label: str) -> dict[str, object]:
    if type(value) is not dict:
        raise V3PackageMergeError(f"{label} must be an object")
    return value


def _exact_fact(value: object, label: str) -> dict[str, object]:
    fact = _exact_mapping(value, label)
    if set(fact) != {"bytes", "sha256"}:
        raise V3PackageMergeError(f"{label} fields differ")
    return {
        "bytes": _exact_int(fact.get("bytes"), f"{label} bytes", 0, (1 << 63) - 1),
        "sha256": _exact_sha256(fact.get("sha256"), f"{label} SHA-256"),
    }


def _validate_package_id(value: object) -> str:
    package_id = _exact_text(value, "package ID")
    if package_id in {".", ".."} or any(character in package_id for character in ("/", "\\", "\0")):
        raise V3PackageMergeError("package ID is path-unsafe")
    return package_id


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(_FILE_HASH_CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
    except OSError as error:
        raise V3PackageMergeError(f"cannot hash {path.name}: {error}") from error
    return digest.hexdigest()


def _runtime_identity_from_manifest(
    manifest: Mapping[str, object],
    *,
    records_path: Path,
    index_path: Path,
) -> tuple[int, str, int, str] | None:
    """Return manifest-bound runtime identity when a producer supplied one.

    Final Experiment 8 producer manifests already bind the two giant runtime
    streams in their source-specific authority section.  Reusing those facts
    avoids a redundant full-file prehash; merge-time streaming still recomputes
    both digests and fails closed before publication if the bytes differ.
    """

    authority = None
    for key in ("globalPlaceSupplement", "globalWaterwaySupplement"):
        value = manifest.get(key)
        if value is None:
            continue
        if authority is not None:
            raise V3PackageMergeError("V3 manifest carries multiple runtime authorities")
        authority = _exact_mapping(value, "V3 manifest runtime authority")
    if authority is None:
        return None
    records = _exact_fact(authority.get("records"), "V3 manifest records")
    tile_index = _exact_fact(authority.get("tileIndex"), "V3 manifest tile index")
    try:
        actual_records_bytes = records_path.stat().st_size
        actual_index_bytes = index_path.stat().st_size
    except OSError as error:
        raise V3PackageMergeError("V3 runtime files are not statable") from error
    if records["bytes"] != actual_records_bytes or tile_index["bytes"] != actual_index_bytes:
        raise V3PackageMergeError("V3 manifest runtime byte length differs")
    return (
        records["bytes"],
        records["sha256"],
        tile_index["bytes"],
        tile_index["sha256"],
    )


def _load_input(directory: Path) -> _InputPackage:
    if not isinstance(directory, Path) or not directory.is_dir():
        raise V3PackageMergeError("V3 input directory is not readable")
    manifest_path = directory / "manifest.json"
    records_path = directory / "records.fadictpack"
    index_path = directory / "tile-index.bin"
    if not all(path.is_file() for path in (manifest_path, records_path, index_path)):
        raise V3PackageMergeError("V3 input lacks its three runtime files")
    manifest_size = manifest_path.stat().st_size
    if not 0 < manifest_size <= _MAX_MANIFEST_BYTES:
        raise V3PackageMergeError("V3 manifest byte length is outside its bound")
    try:
        with manifest_path.open("rb") as manifest_handle:
            manifest_raw = manifest_handle.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as error:
        raise V3PackageMergeError("V3 manifest is not readable") from error
    manifest_size = len(manifest_raw)
    if not 0 < manifest_size <= _MAX_MANIFEST_BYTES:
        raise V3PackageMergeError("V3 manifest byte length is outside its bound")
    manifest = _strict_json(manifest_raw, "V3 manifest")
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 3:
        raise V3PackageMergeError("V3 manifest schemaVersion must be exactly 3")
    if manifest.get("payloadSchema") != PAYLOAD_SCHEMA:
        raise V3PackageMergeError("V3 payload schema is unsupported")
    if manifest.get("presentationPolicySha256") != PRESENTATION_POLICY_SHA256:
        raise V3PackageMergeError("V3 presentation policy identity differs")
    if manifest.get("sourcedTextPolicySha256") != SOURCED_TEXT_POLICY_SHA256:
        raise V3PackageMergeError("V3 sourced-text policy identity differs")
    if manifest.get("unicodeScriptProfileSha256") != UNICODE_SCRIPT_PROFILE_SHA256:
        raise V3PackageMergeError("V3 Unicode script profile identity differs")
    raw_semantic_sha256 = manifest.get("rendererSemanticStreamSha256")
    semantic_sha256 = (
        None
        if raw_semantic_sha256 is None
        else _exact_sha256(
            raw_semantic_sha256, "V3 renderer semantic stream SHA-256"
        )
    )
    raw_waterway_supplement = manifest.get("globalWaterwaySupplement")
    if raw_waterway_supplement is not None and type(raw_waterway_supplement) is not dict:
        raise V3PackageMergeError("V3 global waterway supplement must be an object")
    if manifest.get("compatibility") != {"emptyPresentTilesSharePayload": False}:
        raise V3PackageMergeError("V3 compatibility contract is unsupported")
    package_id = _validate_package_id(manifest.get("packageId"))
    coverage = manifest.get("coverage")
    if type(coverage) is not dict:
        raise V3PackageMergeError("V3 coverage must be an object")
    complete_declared = _exact_bool(
        coverage.get("completeDeclaredScope"), "complete declared scope"
    )
    complete_whole = _exact_bool(
        coverage.get("completeWholeEarthDictionary"), "complete whole earth"
    )
    raw_ranges = coverage.get("zoomRanges")
    if type(raw_ranges) is not list or not raw_ranges:
        raise V3PackageMergeError("V3 zoom ranges must be a nonempty array")
    ranges: list[_Range] = []
    first_ordinal = 0
    previous_zoom = -1
    for ordinal, raw_range in enumerate(raw_ranges):
        if type(raw_range) is not dict:
            raise V3PackageMergeError(f"V3 zoom range {ordinal} must be an object")
        z = _exact_int(raw_range.get("z"), "range zoom", 0, 29)
        if z <= previous_zoom:
            raise V3PackageMergeError("V3 zoom ranges are not strictly ordered")
        previous_zoom = z
        limit = (1 << z) - 1
        window = _Window(
            z,
            _exact_int(raw_range.get("xMin"), "range xMin", 0, limit),
            _exact_int(raw_range.get("xMax"), "range xMax", 0, limit),
            _exact_int(raw_range.get("yMin"), "range yMin", 0, limit),
            _exact_int(raw_range.get("yMax"), "range yMax", 0, limit),
        )
        if window.x_min > window.x_max or window.y_min > window.y_max:
            raise V3PackageMergeError("V3 zoom range bounds are reversed")
        if raw_range.get("tileCount") != window.tile_count:
            raise V3PackageMergeError("V3 zoom range tileCount is inconsistent")
        ranges.append(_Range(window, first_ordinal))
        first_ordinal += window.tile_count
    tile_count = _exact_int(
        coverage.get("tileCount"), "coverage tileCount", 1, (1 << 63) - 1
    )
    if tile_count != first_ordinal:
        raise V3PackageMergeError("V3 coverage tileCount differs from its ranges")
    if complete_whole and (
        not complete_declared
        or any(
            item.window.x_min != 0
            or item.window.y_min != 0
            or item.window.x_max != (1 << item.window.z) - 1
            or item.window.y_max != (1 << item.window.z) - 1
            for item in ranges
        )
    ):
        raise V3PackageMergeError("V3 whole-earth claim is not full-world")
    runtime_identity = _runtime_identity_from_manifest(
        manifest,
        records_path=records_path,
        index_path=index_path,
    )
    if runtime_identity is None:
        records_bytes = records_path.stat().st_size
        records_sha256 = _sha256_file(records_path)
        index_bytes = index_path.stat().st_size
        index_sha256 = _sha256_file(index_path)
    else:
        records_bytes, records_sha256, index_bytes, index_sha256 = runtime_identity
    if index_bytes != tile_count * INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("V3 binary index length differs from coverage")
    if index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise V3PackageMergeError("V3 Android index exceeds its byte-array bound")
    return _InputPackage(
        directory=directory,
        package_id=package_id,
        manifest_sha256=hashlib.sha256(manifest_raw).hexdigest(),
        manifest_bytes=manifest_size,
        records_sha256=records_sha256,
        records_bytes=records_bytes,
        index_sha256=index_sha256,
        index_bytes=index_bytes,
        ranges=tuple(ranges),
        tile_count=tile_count,
        complete_declared_scope=complete_declared,
        complete_whole_earth_dictionary=complete_whole,
        renderer_semantic_stream_sha256=semantic_sha256,
        global_waterway_supplement=raw_waterway_supplement,
    )


def _validated_size_policy_binding(value: object) -> dict[str, object]:
    binding = _exact_mapping(value, "build receipt size-policy binding")
    if set(binding) != {"document", "documentSha256", "mode", "module", "schema"}:
        raise V3PackageMergeError("build receipt size-policy binding fields differ")
    if binding.get("schema") != _SIZE_POLICY_BINDING_SCHEMA:
        raise V3PackageMergeError("build receipt size-policy binding schema differs")
    document = _exact_mapping(
        binding.get("document"), "build receipt size-policy document"
    )
    if not _same_canonical_json(document, _SIZE_POLICY_DOCUMENT):
        raise V3PackageMergeError("build receipt size-policy document differs")
    if _exact_sha256(
        binding.get("documentSha256"),
        "build receipt size-policy document SHA-256",
    ) != hashlib.sha256(_canonical_json_bytes(document)).hexdigest():
        raise V3PackageMergeError("build receipt size-policy document hash differs")
    mode = _exact_text(binding.get("mode"), "build receipt size-policy mode")
    if mode not in {_BUDGETED_SIZE_POLICY, _VISUAL_SIZE_POLICY}:
        raise V3PackageMergeError("build receipt size-policy mode differs")
    module = _exact_fact(binding.get("module"), "build receipt size-policy module")
    if module["bytes"] == 0:
        raise V3PackageMergeError("build receipt size-policy module is empty")
    return binding


def _validated_size_decision(
    value: object,
    *,
    binding: Mapping[str, object],
    required_package_bytes: int,
    label: str,
) -> dict[str, object]:
    decision = _exact_mapping(value, label)
    mode = binding["mode"]
    fields = {
        "authorized",
        "availableDestinationBytes",
        "hardComponentPackageCeilingExceeded",
        "hardMandatoryPhoneFootprintCeilingExceeded",
        "mandatoryPhoneFootprintBytes",
        "mode",
        "preferredComponentPackageCeilingExceeded",
        "preferredMandatoryPhoneFootprintCeilingExceeded",
        "requiredPackageBytes",
        "requiredWithReserveBytes",
        "schema",
    }
    if mode == _VISUAL_SIZE_POLICY:
        fields.update(
            {
                "publicationBoundaryAuthorized",
                "publicationBoundaryDestinationFreeBytes",
                "publicationBoundaryRequiredReserveBytes",
            }
        )
    if set(decision) != fields:
        raise V3PackageMergeError(f"{label} fields differ")
    if decision.get("schema") != _SIZE_POLICY_DECISION_SCHEMA:
        raise V3PackageMergeError(f"{label} schema differs")
    if decision.get("mode") != mode:
        raise V3PackageMergeError(f"{label} mode differs")
    required = _exact_int(
        decision.get("requiredPackageBytes"),
        f"{label} required package bytes",
        0,
        (1 << 63) - 1,
    )
    if required != required_package_bytes:
        raise V3PackageMergeError(f"{label} required package bytes differ")
    policy = _SIZE_POLICY_DOCUMENT
    reserve = int(policy["destinationReserveBytes"])
    budgets = policy["historicalBudgets"]
    assert isinstance(budgets, dict)
    mandatory = required + reserve
    expected_bools = {
        "hardComponentPackageCeilingExceeded": (
            required >= int(budgets["hardComponentPackageBytes"])
        ),
        "hardMandatoryPhoneFootprintCeilingExceeded": (
            mandatory >= int(budgets["hardMandatoryPhoneFootprintBytes"])
        ),
        "preferredComponentPackageCeilingExceeded": (
            required >= int(budgets["preferredComponentPackageBytes"])
        ),
        "preferredMandatoryPhoneFootprintCeilingExceeded": (
            mandatory >= int(budgets["preferredMandatoryPhoneFootprintBytes"])
        ),
    }
    for key, expected in expected_bools.items():
        if _exact_bool(decision.get(key), f"{label} {key}") != expected:
            raise V3PackageMergeError(f"{label} {key} differs")
    if _exact_int(
        decision.get("mandatoryPhoneFootprintBytes"),
        f"{label} mandatory footprint",
        0,
        (1 << 63) - 1,
    ) != mandatory or _exact_int(
        decision.get("requiredWithReserveBytes"),
        f"{label} required with reserve",
        0,
        (1 << 63) - 1,
    ) != mandatory:
        raise V3PackageMergeError(f"{label} reserve accounting differs")
    if mode == _VISUAL_SIZE_POLICY:
        available = _exact_int(
            decision.get("availableDestinationBytes"),
            f"{label} available destination bytes",
            0,
            (1 << 63) - 1,
        )
        boundary = _exact_int(
            decision.get("publicationBoundaryDestinationFreeBytes"),
            f"{label} publication boundary bytes",
            0,
            (1 << 63) - 1,
        )
        if _exact_int(
            decision.get("publicationBoundaryRequiredReserveBytes"),
            f"{label} publication boundary reserve",
            0,
            (1 << 63) - 1,
        ) != reserve:
            raise V3PackageMergeError(f"{label} publication reserve differs")
        boundary_authorized = boundary >= reserve
        if _exact_bool(
            decision.get("publicationBoundaryAuthorized"),
            f"{label} publication authorization",
        ) != boundary_authorized:
            raise V3PackageMergeError(f"{label} publication authorization differs")
        authorized = available >= mandatory and boundary_authorized
    else:
        if decision.get("availableDestinationBytes") is not None:
            raise V3PackageMergeError(f"{label} budgeted capacity must be null")
        authorized = not expected_bools["hardComponentPackageCeilingExceeded"]
    if _exact_bool(decision.get("authorized"), f"{label} authorization") != authorized:
        raise V3PackageMergeError(f"{label} authorization differs")
    return decision


def _exact_count_map(
    value: object,
    expected_names: set[str],
    label: str,
) -> dict[str, int]:
    counts = _exact_mapping(value, label)
    if set(counts) != expected_names:
        raise V3PackageMergeError(f"{label} fields differ")
    return {
        name: _exact_int(
            counts.get(name),
            f"{label} {name}",
            0,
            (1 << 63) - 1,
        )
        for name in sorted(expected_names)
    }


def _validated_waterway_manifest_authority(
    package: _InputPackage,
) -> dict[str, object]:
    supplement = _exact_mapping(
        package.global_waterway_supplement,
        "supplement global waterway manifest authority",
    )
    authority = {
        "admissionAggregateSha256": _exact_sha256(
            supplement.get("admissionAggregateSha256"),
            "supplement admission aggregate SHA-256",
        ),
        "admissionPolicySha256": _exact_sha256(
            supplement.get("admissionPolicySha256"),
            "supplement admission policy SHA-256",
        ),
        "attribution": _exact_mapping(
            supplement.get("attribution"),
            "supplement waterway attribution",
        ),
        "classifierSha256": _exact_sha256(
            supplement.get("classifierSha256"),
            "supplement classifier SHA-256",
        ),
        "ingestSemanticSha256": _exact_sha256(
            supplement.get("ingestSemanticSha256"),
            "supplement ingest semantic SHA-256",
        ),
        "records": _exact_fact(
            supplement.get("records"),
            "supplement manifest records",
        ),
        "requestedZooms": supplement.get("requestedZooms"),
        "runIdentitySha256": _exact_sha256(
            supplement.get("runIdentitySha256"),
            "supplement run identity SHA-256",
        ),
        "source": _exact_mapping(
            supplement.get("source"),
            "supplement waterway source",
        ),
        "tileIndex": _exact_fact(
            supplement.get("tileIndex"),
            "supplement manifest tile index",
        ),
    }
    zooms = authority["requestedZooms"]
    if (
        type(zooms) is not list
        or not zooms
        or any(type(zoom) is not int or not 0 <= zoom <= 29 for zoom in zooms)
        or zooms != sorted(set(zooms))
    ):
        raise V3PackageMergeError("supplement requested zooms differ")
    if authority["records"] != {
        "bytes": package.records_bytes,
        "sha256": package.records_sha256,
    } or authority["tileIndex"] != {
        "bytes": package.index_bytes,
        "sha256": package.index_sha256,
    }:
        raise V3PackageMergeError(
            "supplement global waterway manifest runtime identity differs"
        )
    return authority


def _validated_admission_authority(
    value: object,
    *,
    manifest_authority: Mapping[str, object],
) -> None:
    admission = _exact_mapping(value, "supplement build receipt admission")
    if admission.get("schema") != _WATERWAY_ADMISSION_SCHEMA:
        raise V3PackageMergeError("supplement build receipt admission schema differs")
    _exact_int(
        admission.get("fatalCount"),
        "supplement build receipt admission fatal count",
        0,
        0,
    )
    for field, label in (
        ("aggregateSha256", "admission aggregate"),
        ("ingestSemanticSha256", "admission ingest semantic"),
    ):
        expected = manifest_authority[
            "admissionAggregateSha256"
            if field == "aggregateSha256"
            else "ingestSemanticSha256"
        ]
        if _exact_sha256(admission.get(field), label) != expected:
            raise V3PackageMergeError(
                f"supplement build receipt {label} identity differs"
            )
    if not _same_canonical_json(
        admission.get("source"), manifest_authority["source"]
    ):
        raise V3PackageMergeError("supplement build receipt admission source differs")
    policy = _exact_mapping(
        admission.get("policy"), "supplement build receipt admission policy"
    )
    if set(policy) != {"bytes", "document", "sha256"}:
        raise V3PackageMergeError(
            "supplement build receipt admission policy fields differ"
        )
    policy_document = _exact_mapping(
        policy.get("document"), "supplement build receipt admission policy document"
    )
    policy_bytes = _canonical_json_bytes(policy_document)
    if _exact_int(
        policy.get("bytes"),
        "supplement build receipt admission policy bytes",
        1,
        _MAX_MANIFEST_BYTES,
    ) != len(policy_bytes) or _exact_sha256(
        policy.get("sha256"),
        "supplement build receipt admission policy SHA-256",
    ) != hashlib.sha256(policy_bytes).hexdigest():
        raise V3PackageMergeError(
            "supplement build receipt admission policy identity differs"
        )
    if policy["sha256"] != manifest_authority["admissionPolicySha256"]:
        raise V3PackageMergeError(
            "supplement build receipt admission policy differs from manifest"
        )


def _validated_recovery(
    value: object,
    *,
    binding: Mapping[str, object],
    decision: Mapping[str, object],
    published_bytes: int,
    run_identity_sha256: str,
    run_identity_schema: str,
) -> None:
    recovery = _exact_mapping(value, "build receipt recovery")
    if set(recovery) != {
        "authorityPolicySha256",
        "backupDatabase",
        "databaseBeforeRecovery",
        "failedRender",
        "newRenderRunIdentity",
        "preservedMeta",
        "publishedDirectoryBytes",
        "recoveredAtUtc",
        "recoveryCode",
        "rendererResetCounts",
        "resetCount",
        "schema",
        "sizePolicyDecision",
        "sizePolicyTransition",
        "sourceTableCounts",
        "sqliteEvidenceOverheadBytes",
        "transactionComplete",
    }:
        raise V3PackageMergeError("build receipt recovery fields differ")
    if recovery.get("schema") != _WATERWAY_RECOVERY_SCHEMA:
        raise V3PackageMergeError("build receipt recovery schema differs")
    if recovery.get("transactionComplete") is not True:
        raise V3PackageMergeError("build receipt recovery transaction is incomplete")
    _exact_int(
        recovery.get("resetCount"),
        "build receipt recovery reset count",
        1,
        1,
    )
    recovered_at = recovery.get("recoveredAtUtc")
    if type(recovered_at) is not str or not recovered_at.endswith("Z"):
        raise V3PackageMergeError("build receipt recovery timestamp differs")
    try:
        parsed = datetime.strptime(recovered_at, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as error:
        raise V3PackageMergeError("build receipt recovery timestamp differs") from error
    if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != recovered_at:
        raise V3PackageMergeError("build receipt recovery timestamp differs")
    _exact_sha256(
        recovery.get("authorityPolicySha256"),
        "build receipt recovery authority policy SHA-256",
    )
    backup = _exact_fact(
        recovery.get("backupDatabase"),
        "build receipt recovery backup database",
    )
    before = _exact_fact(
        recovery.get("databaseBeforeRecovery"),
        "build receipt recovery database before recovery",
    )
    if backup != before:
        raise V3PackageMergeError("build receipt recovery database identity differs")
    if backup["bytes"] == 0:
        raise V3PackageMergeError("build receipt recovery database is empty")
    recovery_code = _exact_fact(
        recovery.get("recoveryCode"), "build receipt recovery code"
    )
    if recovery_code["bytes"] == 0:
        raise V3PackageMergeError("build receipt recovery code is empty")
    _exact_int(
        recovery.get("sqliteEvidenceOverheadBytes"),
        "build receipt recovery SQLite evidence overhead",
        0,
        (1 << 63) - 1,
    )
    preserved = _exact_mapping(
        recovery.get("preservedMeta"), "build receipt recovery preserved meta"
    )
    if set(preserved) != _RECOVERY_PRESERVED_META_KEYS:
        raise V3PackageMergeError("build receipt recovery preserved meta fields differ")
    for name in sorted(_RECOVERY_PRESERVED_META_KEYS):
        _exact_fact(
            preserved.get(name), f"build receipt recovery preserved meta {name}"
        )
    renderer_counts = _exact_count_map(
        recovery.get("rendererResetCounts"),
        _RECOVERY_RENDERER_TABLES,
        "build receipt recovery renderer reset counts",
    )
    _exact_count_map(
        recovery.get("sourceTableCounts"),
        _RECOVERY_SOURCE_TABLES,
        "build receipt recovery source table counts",
    )
    failed = _exact_mapping(
        recovery.get("failedRender"), "build receipt recovery failed render"
    )
    if set(failed) != {
        "checkpoint",
        "failureClass",
        "failureLog",
        "failureMessage",
        "renderRunIdentity",
    }:
        raise V3PackageMergeError("build receipt recovery failed render fields differ")
    checkpoint = _exact_mapping(
        failed.get("checkpoint"), "build receipt recovery failed checkpoint"
    )
    if set(checkpoint) != {"renderComplete", "renderedFeatures"}:
        raise V3PackageMergeError(
            "build receipt recovery failed checkpoint fields differ"
        )
    if _exact_bool(
        checkpoint.get("renderComplete"),
        "build receipt recovery failed render completion",
    ):
        raise V3PackageMergeError(
            "build receipt recovery failed render is marked complete"
        )
    rendered_features = _exact_int(
        checkpoint.get("renderedFeatures"),
        "build receipt recovery failed rendered features",
        0,
        (1 << 63) - 1,
    )
    if renderer_counts["rendered_features"] != rendered_features:
        raise V3PackageMergeError(
            "build receipt recovery renderer reset count differs"
        )
    if (
        failed.get("failureClass") != "sqlite3.OperationalError"
        or failed.get("failureMessage") != "database is locked"
    ):
        raise V3PackageMergeError("build receipt recovery failure identity differs")
    _exact_fact(
        failed.get("failureLog"), "build receipt recovery failure log"
    )
    _exact_fact(
        failed.get("renderRunIdentity"),
        "build receipt recovery failed run identity",
    )
    new_run = _exact_mapping(
        recovery.get("newRenderRunIdentity"),
        "build receipt recovery new run identity",
    )
    if set(new_run) != {"documentSha256", "schema"}:
        raise V3PackageMergeError("build receipt recovery new run fields differ")
    if (
        _exact_sha256(
            new_run.get("documentSha256"),
            "build receipt recovery new run SHA-256",
        )
        != run_identity_sha256
        or _exact_text(
            new_run.get("schema"), "build receipt recovery new run schema"
        )
        != run_identity_schema
    ):
        raise V3PackageMergeError("build receipt recovery new run identity differs")
    transition = _exact_mapping(
        recovery.get("sizePolicyTransition"),
        "build receipt recovery size-policy transition",
    )
    intended = _exact_mapping(
        transition.get("intended"),
        "build receipt recovery intended size policy",
    )
    if not _same_canonical_json(intended, binding):
        raise V3PackageMergeError("build receipt recovery size policy differs")
    predecessor = _exact_mapping(
        transition.get("predecessor"),
        "build receipt recovery predecessor size policy",
    )
    if set(predecessor) != {"identityBound", "mode"}:
        raise V3PackageMergeError("build receipt recovery predecessor fields differ")
    predecessor_identity_bound = _exact_bool(
        predecessor.get("identityBound"),
        "build receipt recovery predecessor identity binding",
    )
    predecessor_mode = _exact_text(
        predecessor.get("mode"),
        "build receipt recovery predecessor mode",
    )
    if predecessor_identity_bound or predecessor_mode != _BUDGETED_SIZE_POLICY:
        raise V3PackageMergeError(
            "build receipt recovery size-policy transition differs"
        )
    recovery_decision = _exact_mapping(
        recovery.get("sizePolicyDecision"),
        "build receipt recovery size-policy decision",
    )
    if not _same_canonical_json(recovery_decision, decision):
        raise V3PackageMergeError("build receipt recovery size policy decision differs")
    if _exact_int(
        recovery.get("publishedDirectoryBytes"),
        "build receipt recovery published bytes",
        0,
        (1 << 63) - 1,
    ) != published_bytes:
        raise V3PackageMergeError("build receipt recovery published bytes differ")


def _load_authority_receipt(
    receipt_path: Path,
    package: _InputPackage,
) -> _AuthorityReceipt:
    if (
        not receipt_path.is_file()
        or receipt_path.is_symlink()
        or receipt_path.name != "build-receipt.json"
        or receipt_path.parent.resolve() != package.directory.resolve()
    ):
        raise V3PackageMergeError(
            "supplement build receipt is not its package build-receipt.json"
        )
    try:
        before = os.lstat(receipt_path)
    except OSError as error:
        raise V3PackageMergeError("supplement build receipt is unreadable") from error
    receipt_bytes = before.st_size
    if not 0 < receipt_bytes <= _MAX_MANIFEST_BYTES:
        raise V3PackageMergeError("supplement build receipt length is outside its bound")
    try:
        with receipt_path.open("rb") as handle:
            raw = handle.read(_MAX_MANIFEST_BYTES + 1)
            after_handle = os.fstat(handle.fileno())
        after = os.lstat(receipt_path)
    except OSError as error:
        raise V3PackageMergeError("supplement build receipt is unreadable") from error
    signature = lambda status: (
        status.st_dev,
        status.st_ino,
        status.st_size,
        status.st_mtime_ns,
    )
    if (
        len(raw) != receipt_bytes
        or signature(before) != signature(after_handle)
        or signature(before) != signature(after)
    ):
        raise V3PackageMergeError("supplement build receipt changed while reading")
    document = _strict_json(raw, "supplement build receipt")
    if raw != _canonical_json_bytes(document):
        raise V3PackageMergeError("supplement build receipt is not canonical JSON")
    if document.get("schema") != _WATERWAY_BUILD_SCHEMA:
        raise V3PackageMergeError("supplement build receipt schema differs")
    if set(document) != {
        "admission",
        "attribution",
        "build",
        "catalogCountsClaimed",
        "closureAudit",
        "finalSemanticIdentitySha256",
        "outputFiles",
        "packageId",
        "peakResources",
        "projection",
        "rendererTextAudit",
        "rendererSemanticStreamSha256",
        "schema",
        "source",
    }:
        raise V3PackageMergeError("supplement build receipt fields differ")
    if _exact_bool(
        document.get("catalogCountsClaimed"),
        "supplement build receipt catalog-count claim",
    ):
        raise V3PackageMergeError(
            "supplement build receipt cannot claim catalog counts"
        )
    _exact_mapping(document.get("closureAudit"), "supplement closure audit")
    _exact_mapping(document.get("peakResources"), "supplement peak resources")
    _exact_mapping(document.get("rendererTextAudit"), "supplement renderer text audit")
    if _validate_package_id(document.get("packageId")) != package.package_id:
        raise V3PackageMergeError("supplement build receipt package does not match")
    semantic_sha256 = _exact_sha256(
        document.get("rendererSemanticStreamSha256"),
        "supplement build receipt renderer semantic SHA-256",
    )
    if (
        package.renderer_semantic_stream_sha256 is None
        or semantic_sha256 != package.renderer_semantic_stream_sha256
    ):
        raise V3PackageMergeError(
            "supplement build receipt renderer semantic identity differs"
        )
    manifest_authority = _validated_waterway_manifest_authority(package)
    if not _same_canonical_json(
        document.get("source"), manifest_authority["source"]
    ):
        raise V3PackageMergeError("supplement build receipt source differs")
    if not _same_canonical_json(
        document.get("attribution"), manifest_authority["attribution"]
    ):
        raise V3PackageMergeError("supplement build receipt attribution differs")
    _validated_admission_authority(
        document.get("admission"), manifest_authority=manifest_authority
    )
    expected_files = {
        "manifest.json": (package.manifest_bytes, package.manifest_sha256),
        "records.fadictpack": (package.records_bytes, package.records_sha256),
        "tile-index.bin": (package.index_bytes, package.index_sha256),
    }
    raw_files = document.get("outputFiles")
    if type(raw_files) is not list or len(raw_files) != len(expected_files):
        raise V3PackageMergeError("supplement build receipt output files differ")
    actual_files: dict[str, tuple[int, str]] = {}
    for item in raw_files:
        fact = _exact_mapping(item, "supplement build receipt output file")
        if set(fact) != {"bytes", "name", "sha256"}:
            raise V3PackageMergeError("supplement build receipt output files differ")
        name = _exact_text(fact.get("name"), "supplement build receipt output name")
        if name in actual_files:
            raise V3PackageMergeError("supplement build receipt output files duplicate")
        actual_files[name] = (
            _exact_int(
                fact.get("bytes"),
                "supplement build receipt output bytes",
                0,
                (1 << 63) - 1,
            ),
            _exact_sha256(
                fact.get("sha256"),
                "supplement build receipt output SHA-256",
            ),
        )
    if actual_files != expected_files:
        raise V3PackageMergeError("supplement build receipt output files differ")
    try:
        inventory = {entry.name for entry in package.directory.iterdir()}
    except OSError as error:
        raise V3PackageMergeError("supplement build package inventory is unreadable") from error
    if inventory != {*expected_files, "build-receipt.json"}:
        raise V3PackageMergeError("supplement build package inventory differs")
    published_bytes = sum(value[0] for value in expected_files.values()) + receipt_bytes
    projection = _exact_mapping(
        document.get("projection"), "supplement build receipt projection"
    )
    if _exact_int(
        projection.get("publishedDirectoryBytes"),
        "supplement build receipt published bytes",
        0,
        (1 << 63) - 1,
    ) != published_bytes:
        raise V3PackageMergeError("supplement build receipt published bytes differ")
    build = _exact_mapping(document.get("build"), "supplement build receipt build")
    classifier_sha256 = _exact_sha256(
        build.get("classifierSha256"),
        "supplement build receipt classifier SHA-256",
    )
    if classifier_sha256 != manifest_authority["classifierSha256"]:
        raise V3PackageMergeError(
            "supplement build receipt classifier identity differs"
        )
    policy = _exact_mapping(
        build.get("sizePolicy"), "supplement build receipt size policy"
    )
    if set(policy) != {"binding", "decision"}:
        raise V3PackageMergeError("supplement build receipt size-policy fields differ")
    binding = _validated_size_policy_binding(policy.get("binding"))
    if binding["mode"] != _VISUAL_SIZE_POLICY:
        raise V3PackageMergeError(
            "recovered water authority requires the visual-evaluation size policy"
        )
    code = _exact_mapping(build.get("code"), "supplement build receipt code")
    policy_code = _exact_mapping(
        code.get("referenceSizePolicy"),
        "supplement build receipt size-policy code",
    )
    if not _same_canonical_json(policy_code, binding["module"]):
        raise V3PackageMergeError(
            "supplement build receipt size-policy module differs from build code"
        )
    run_identity = _exact_mapping(
        build.get("runIdentity"), "supplement build receipt run identity"
    )
    run_identity_sha256 = _exact_sha256(
        build.get("runIdentitySha256"),
        "supplement build receipt run identity SHA-256",
    )
    if (
        run_identity_sha256
        != hashlib.sha256(_canonical_json_bytes(run_identity)).hexdigest()
        or run_identity_sha256 != manifest_authority["runIdentitySha256"]
    ):
        raise V3PackageMergeError(
            "supplement build receipt run identity differs"
        )
    run_identity_schema = _exact_text(
        run_identity.get("schema"), "supplement build receipt run identity schema"
    )
    if run_identity_schema != _WATERWAY_RENDER_RUN_SCHEMA:
        raise V3PackageMergeError(
            "supplement build receipt run identity schema differs"
        )
    if (
        _validate_package_id(run_identity.get("packageId")) != package.package_id
        or _exact_sha256(
            run_identity.get("classifierSha256"),
            "supplement run classifier SHA-256",
        )
        != classifier_sha256
        or _exact_sha256(
            run_identity.get("admissionAggregateSha256"),
            "supplement run admission aggregate SHA-256",
        )
        != manifest_authority["admissionAggregateSha256"]
        or _exact_sha256(
            run_identity.get("admissionPolicySha256"),
            "supplement run admission policy SHA-256",
        )
        != manifest_authority["admissionPolicySha256"]
        or _exact_sha256(
            run_identity.get("ingestSemanticSha256"),
            "supplement run ingest semantic SHA-256",
        )
        != manifest_authority["ingestSemanticSha256"]
        or not _same_canonical_json(
            run_identity.get("source"), manifest_authority["source"]
        )
        or (
            "recovery" not in build
            and not _same_canonical_json(run_identity.get("code"), code)
        )
        or not _same_canonical_json(
            run_identity.get("zooms"), manifest_authority["requestedZooms"]
        )
    ):
        raise V3PackageMergeError(
            "supplement build receipt run identity differs from manifest"
        )
    run_size_policy = _exact_mapping(
        run_identity.get("sizePolicy"),
        "supplement build receipt run size policy",
    )
    if not _same_canonical_json(run_size_policy, binding):
        raise V3PackageMergeError("supplement build receipt run size policy differs")
    decision = _validated_size_decision(
        policy.get("decision"),
        binding=binding,
        required_package_bytes=published_bytes,
        label="supplement build receipt size-policy decision",
    )
    if "recovery" not in build:
        raise V3PackageMergeError("supplement build receipt recovery is absent")
    _validated_recovery(
        build.get("recovery"),
        binding=binding,
        decision=decision,
        published_bytes=published_bytes,
        run_identity_sha256=run_identity_sha256,
        run_identity_schema=run_identity_schema,
    )
    final_semantic_document = {
        "admissionAggregateSha256": manifest_authority[
            "admissionAggregateSha256"
        ],
        "admissionPolicySha256": manifest_authority["admissionPolicySha256"],
        "ingestSemanticSha256": manifest_authority["ingestSemanticSha256"],
        "manifestSha256": package.manifest_sha256,
        "rendererRunIdentitySha256": run_identity_sha256,
        "rendererSemanticStreamSha256": semantic_sha256,
        "schema": _WATERWAY_FINAL_SEMANTIC_SCHEMA,
        "source": manifest_authority["source"],
    }
    expected_final_semantic = hashlib.sha256(
        _WATERWAY_FINAL_SEMANTIC_DOMAIN
        + _canonical_json_bytes(final_semantic_document)
    ).hexdigest()
    if _exact_sha256(
        document.get("finalSemanticIdentitySha256"),
        "supplement build receipt final semantic SHA-256",
    ) != expected_final_semantic:
        raise V3PackageMergeError(
            "supplement build receipt final semantic identity differs"
        )
    return _AuthorityReceipt(
        package_directory=package.directory.resolve(),
        package_id=package.package_id,
        document=document,
        byte_count=receipt_bytes,
        sha256=hashlib.sha256(raw).hexdigest(),
        size_policy_binding=binding,
        renderer_semantic_stream_sha256=semantic_sha256,
    )


def _load_authority_receipts(
    receipt_paths: Sequence[Path],
    supplements: Sequence[_InputPackage],
) -> tuple[_AuthorityReceipt, ...]:
    if type(receipt_paths) not in (tuple, list):
        raise V3PackageMergeError(
            "supplement build receipts must be an ordered sequence"
        )
    if any(not isinstance(path, Path) for path in receipt_paths):
        raise V3PackageMergeError(
            "supplement build receipts must be pathlib.Path values"
        )
    packages_by_directory: dict[Path, _InputPackage] = {}
    duplicate_directories: set[Path] = set()
    for package in supplements:
        directory = package.directory.resolve()
        if directory in packages_by_directory:
            duplicate_directories.add(directory)
        packages_by_directory[directory] = package
    receipts: list[_AuthorityReceipt] = []
    seen: set[str] = set()
    for path in receipt_paths:
        directory = path.parent.resolve()
        if directory not in packages_by_directory or directory in duplicate_directories:
            raise V3PackageMergeError(
                "supplement build receipt package does not match one supplement"
            )
        receipt = _load_authority_receipt(path, packages_by_directory[directory])
        if receipt.package_id in seen:
            raise V3PackageMergeError("duplicate supplement build receipt")
        seen.add(receipt.package_id)
        receipts.append(receipt)
    receipts.sort(key=lambda item: (item.package_id, item.sha256))
    if receipts and any(
        not _same_canonical_json(
            receipt.size_policy_binding, receipts[0].size_policy_binding
        )
        for receipt in receipts[1:]
    ):
        raise V3PackageMergeError("supplement build receipt size policies differ")
    return tuple(receipts)


def _union_windows(packages: Sequence[_InputPackage]) -> tuple[_Window, ...]:
    by_zoom: dict[int, list[_Window]] = {}
    for package in packages:
        for item in package.ranges:
            by_zoom.setdefault(item.window.z, []).append(item.window)
    return tuple(
        _Window(
            z,
            min(window.x_min for window in windows),
            max(window.x_max for window in windows),
            min(window.y_min for window in windows),
            max(window.y_max for window in windows),
        )
        for z, windows in sorted(by_zoom.items())
    )


def _extends_primary_scope(
    primary: _InputPackage,
    supplement: _InputPackage,
) -> bool:
    for supplement_range in supplement.ranges:
        if not any(
            primary_range.window.z == supplement_range.window.z
            and primary_range.window.x_min <= supplement_range.window.x_min
            and primary_range.window.x_max >= supplement_range.window.x_max
            and primary_range.window.y_min <= supplement_range.window.y_min
            and primary_range.window.y_max >= supplement_range.window.y_max
            for primary_range in primary.ranges
        ):
            return True
    return False


def _tiles_index_order(windows: Sequence[_Window]):
    for window in windows:
        for y in range(window.y_min, window.y_max + 1):
            for x in range(window.x_min, window.x_max + 1):
                yield TileKey(window.z, x, y)


def _tiles_semantic_order(windows: Sequence[_Window]):
    for window in windows:
        for x in range(window.x_min, window.x_max + 1):
            for y in range(window.y_min, window.y_max + 1):
                yield TileKey(window.z, x, y)


def _output_ordinal(windows: Sequence[_Window], tile: TileKey) -> int:
    first = 0
    for window in windows:
        if window.contains(tile):
            return first + window.ordinal(tile)
        first += window.tile_count
    raise V3PackageMergeError("output tile lies outside merged coverage")


def _inflate_exact(compressed: bytes, raw_length: int, label: str) -> bytes:
    inflater = zlib.decompressobj(wbits=-zlib.MAX_WBITS)
    try:
        raw = inflater.decompress(compressed, raw_length + 1)
        if inflater.unconsumed_tail:
            raise V3PackageMergeError(f"{label} DEFLATE exceeds its declared length")
        raw += inflater.flush()
    except zlib.error as error:
        raise V3PackageMergeError(f"{label} DEFLATE is corrupt") from error
    if not inflater.eof or inflater.unused_data or len(raw) != raw_length:
        raise V3PackageMergeError(f"{label} DEFLATE length is dishonest")
    return raw


def _extract_envelopes(tile: TileKey, payload: bytes) -> tuple[_RawEnvelope, ...]:
    try:
        decoded = decode_tile_payload(tile, payload)
    except ValueError as error:
        raise V3PackageMergeError(f"V3 tile {tile.z}/{tile.x}/{tile.y} is not canonical: {error}") from error
    offset = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
    envelopes: list[_RawEnvelope] = []
    for record in decoded.records:
        start = offset
        if offset + 4 > len(payload):
            raise V3PackageMergeError("V3 renderer envelope is truncated")
        renderer_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4
        end_renderer = offset + renderer_length
        if end_renderer > len(payload):
            raise V3PackageMergeError("V3 renderer record is truncated")
        canonical_renderer = payload[offset:end_renderer]
        offset = end_renderer
        if offset + 4 > len(payload):
            raise V3PackageMergeError("V3 sourced-text envelope is truncated")
        sourced_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4
        sourced_digest = b""
        sourced_canonical = b""
        if sourced_length:
            if sourced_length > MAX_SOURCED_TEXT_RECORD_BYTES or offset + 32 + sourced_length > len(payload):
                raise V3PackageMergeError("V3 sourced-text envelope exceeds its bound")
            sourced_digest = payload[offset : offset + 32]
            offset += 32
            sourced_canonical = payload[offset : offset + sourced_length]
            offset += sourced_length
        expected_renderer = renderer_record_bytes(record.renderer_record)
        if canonical_renderer != expected_renderer:
            raise V3PackageMergeError("V3 renderer envelope differs from its canonical record")
        expected_sourced = record.sourced_text
        if expected_sourced is None:
            if sourced_length:
                raise V3PackageMergeError("V3 non-label unexpectedly carries sourced text")
        elif (
            sourced_canonical != expected_sourced.canonical_bytes
            or sourced_digest != expected_sourced.full_sha256
        ):
            raise V3PackageMergeError("V3 sourced-text envelope differs from its canonical identity")
        renderer = record.renderer_record
        variant = renderer.variant
        try:
            subtype = SemanticSubtype(variant.semantic_subtype)
        except ValueError as error:
            raise V3PackageMergeError("V3 renderer semantic subtype is unknown") from error
        posting = renderer.posting
        order_key = (
            variant.draw_order,
            variant.priority,
            variant.layer_group.value,
            variant.feature_kind.value,
            variant.canonical_variant_id,
            posting.feature_id,
            canonical_renderer,
            sourced_digest,
        )
        envelopes.append(
            _RawEnvelope(
                raw=payload[start:offset],
                renderer_bytes=canonical_renderer,
                posting_key=(
                    posting.feature_id,
                    posting.canonical_variant_id,
                    posting.owner_tile.packed,
                    posting.world_wrap,
                ),
                order_key=order_key,
                subtype=subtype,
                feature_id=posting.feature_id,
                variant_id=variant.canonical_variant_id,
                variant_full_sha256=variant_fingerprint(variant).full_sha256,
            )
        )
    if offset != len(payload):
        raise V3PackageMergeError("V3 tile contains trailing envelope bytes")
    return tuple(envelopes)


def _read_input_tile(
    package: _InputPackage,
    state: _InputState,
    tile: TileKey,
) -> tuple[bool, tuple[_RawEnvelope, ...]]:
    ordinal = package.ordinal(tile)
    if ordinal is None:
        return False, ()
    if ordinal != state.next_ordinal:
        raise V3PackageMergeError("V3 input coverage traversal is not canonical")
    entry = state.index_handle.read(INDEX_ENTRY_BYTES)
    state.next_ordinal += 1
    if len(entry) != INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("V3 input index ended early")
    state.index_digest.update(entry)
    if entry == _ZERO_INDEX_ENTRY:
        return False, ()
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    if flags != INDEX_FLAG_PRESENT:
        raise V3PackageMergeError("V3 input index flags are unsupported")
    if offset != state.next_record_offset:
        raise V3PackageMergeError("V3 input record offsets are not exact and contiguous")
    if not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES:
        raise V3PackageMergeError("V3 input compressed tile length exceeds its bound")
    if not 0 < raw_length <= MAX_TILE_BYTES:
        raise V3PackageMergeError("V3 input raw tile length exceeds its bound")
    if offset + compressed_length > package.records_bytes:
        raise V3PackageMergeError("V3 input record range exceeds its pack")
    compressed = state.records_handle.read(compressed_length)
    if len(compressed) != compressed_length:
        raise V3PackageMergeError("V3 input records pack ended early")
    state.records_digest.update(compressed)
    state.next_record_offset += compressed_length
    state.present_tiles += 1
    payload = _inflate_exact(compressed, raw_length, "V3 input")
    if raw_hash32(payload) != expected_hash32:
        raise V3PackageMergeError("V3 input tile integrity hash differs")
    return True, _extract_envelopes(tile, payload)


def _finish_input_validation(package: _InputPackage, state: _InputState) -> None:
    if state.next_ordinal != package.tile_count:
        raise V3PackageMergeError("V3 input index was not completely audited")
    if state.next_record_offset != package.records_bytes:
        raise V3PackageMergeError("V3 input records pack has unreferenced or missing bytes")
    if package.complete_declared_scope and state.present_tiles != package.tile_count:
        raise V3PackageMergeError("V3 complete-declared input contains a missing tile")
    if state.index_handle.read(1) or state.records_handle.read(1):
        raise V3PackageMergeError("V3 input runtime files changed while being merged")
    if (
        state.index_digest.hexdigest() != package.index_sha256
        or state.records_digest.hexdigest() != package.records_sha256
    ):
        raise V3PackageMergeError("V3 input runtime files changed while being merged")


def _merge_contribution(
    tile: TileKey,
    by_posting: dict[tuple[int, int, int, int], _RawEnvelope],
    payload_bytes: int,
    records: Sequence[_RawEnvelope],
) -> int:
    for record in records:
        previous = by_posting.get(record.posting_key)
        if previous is not None:
            if previous.raw != record.raw:
                raise V3PackageMergeError(
                    f"divergent duplicate renderer posting at {tile.z}/{tile.x}/{tile.y}"
                )
            continue
        if len(by_posting) >= MAX_RECORDS_PER_TILE:
            raise V3PackageMergeError("merged V3 tile record count exceeds its bound")
        payload_bytes += len(record.raw)
        if payload_bytes > MAX_TILE_BYTES:
            raise V3PackageMergeError("merged V3 tile byte length exceeds its bound")
        by_posting[record.posting_key] = record
    return payload_bytes


def _merge_payload(
    tile: TileKey,
    by_posting: Mapping[tuple[int, int, int, int], _RawEnvelope],
    payload_bytes: int,
) -> bytes:
    ordered = tuple(sorted(by_posting.values(), key=lambda item: item.order_key))
    if len(ordered) > MAX_RECORDS_PER_TILE:
        raise V3PackageMergeError("merged V3 tile record count exceeds its bound")
    payload = b"".join(
        (
            TILE_PAYLOAD_MAGIC,
            struct.pack("<BIII", tile.z, tile.x, tile.y, len(ordered)),
            *(item.raw for item in ordered),
        )
    )
    if len(payload) != payload_bytes or len(payload) > MAX_TILE_BYTES:
        raise V3PackageMergeError("merged V3 tile byte length exceeds its bound")
    # Every input envelope was validated above; _audit_output reopens and
    # validates the exact compressed stream before any package is published.
    return payload


def _validated_compression_workers(value: int | None) -> int:
    if value is None:
        detected = os.cpu_count()
        if type(detected) is not int or detected < 1:
            return 1
        return min(detected, _DEFAULT_COMPRESSION_WORKER_LIMIT)
    if type(value) is not int or not 1 <= value <= _MAX_COMPRESSION_WORKERS:
        raise V3PackageMergeError(
            f"compression workers must be an integer from 1 to {_MAX_COMPRESSION_WORKERS}"
        )
    return value


def _encode_tile_batch(
    payloads: tuple[bytes | None, ...],
) -> _EncodedBatchResult:
    encoded: list[_EncodedTile | None] = []
    for payload in payloads:
        try:
            if payload is None:
                encoded.append(None)
                continue
            compressed = raw_deflate(payload)
            if len(compressed) > _MAX_COMPRESSED_TILE_BYTES:
                raise V3PackageMergeError(
                    "merged V3 compressed tile length exceeds its bound"
                )
            encoded.append(
                _EncodedTile(
                    compressed=compressed,
                    raw_length=len(payload),
                    raw_hash32=raw_hash32(payload),
                )
            )
        except BaseException as error:
            return _EncodedBatchResult(
                tiles=tuple(encoded),
                failure=error,
            )
    return _EncodedBatchResult(tiles=tuple(encoded), failure=None)


def _encode_tiles_ordered(
    payloads: Iterable[bytes | None],
    *,
    compression_workers: int,
) -> Iterator[_EncodedTile | None]:
    """Compress bounded batches concurrently and publish results in input order."""

    if compression_workers == 1:
        for payload in payloads:
            result = _encode_tile_batch((payload,))
            yield from result.tiles
            if result.failure is not None:
                raise result.failure
        return

    executor = ThreadPoolExecutor(
        max_workers=compression_workers,
        thread_name_prefix="flightalert-v3-merge",
    )
    pending: deque[_PendingEncodedBatch] = deque()
    pending_raw_bytes = 0
    batch: list[bytes | None] = []
    batch_raw_bytes = 0
    pending_batch_limit = (
        compression_workers * _PARALLEL_PENDING_BATCHES_PER_WORKER
    )

    def drain_oldest() -> Iterator[_EncodedTile | None]:
        nonlocal pending_raw_bytes
        item = pending.popleft()
        pending_raw_bytes -= item.raw_bytes
        result = item.future.result()
        yield from result.tiles
        if result.failure is not None:
            raise result.failure

    def submit_batch() -> Iterator[_EncodedTile | None]:
        nonlocal batch, batch_raw_bytes, pending_raw_bytes
        if not batch:
            return
        while pending and (
            len(pending) >= pending_batch_limit
            or pending_raw_bytes + batch_raw_bytes
            > _PARALLEL_PENDING_RAW_BYTES
        ):
            yield from drain_oldest()
        submitted = tuple(batch)
        try:
            future = executor.submit(_encode_tile_batch, submitted)
        except Exception:
            while pending:
                yield from drain_oldest()
            raise
        pending.append(_PendingEncodedBatch(future, batch_raw_bytes))
        pending_raw_bytes += batch_raw_bytes
        batch = []
        batch_raw_bytes = 0

    try:
        source = iter(payloads)
        while True:
            try:
                payload = next(source)
            except StopIteration:
                break
            except Exception:
                yield from submit_batch()
                while pending:
                    yield from drain_oldest()
                raise
            payload_bytes = 0 if payload is None else len(payload)
            if batch and (
                len(batch) >= _PARALLEL_BATCH_TILE_LIMIT
                or batch_raw_bytes + payload_bytes > _PARALLEL_BATCH_RAW_BYTES
            ):
                yield from submit_batch()
            batch.append(payload)
            batch_raw_bytes += payload_bytes
            if (
                len(batch) >= _PARALLEL_BATCH_TILE_LIMIT
                or batch_raw_bytes >= _PARALLEL_BATCH_RAW_BYTES
            ):
                yield from submit_batch()
        yield from submit_batch()
        while pending:
            yield from drain_oldest()
    finally:
        executor.shutdown(wait=True, cancel_futures=True)


def _read_output_payload(
    records_handle: object,
    index_handle: object,
    windows: Sequence[_Window],
    tile: TileKey,
    records_bytes: int,
) -> bytes | None:
    ordinal = _output_ordinal(windows, tile)
    index_handle.seek(ordinal * INDEX_ENTRY_BYTES)
    entry = index_handle.read(INDEX_ENTRY_BYTES)
    if len(entry) != INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("merged V3 audit index ended early")
    if entry == _ZERO_INDEX_ENTRY:
        return None
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    if (
        flags != INDEX_FLAG_PRESENT
        or not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES
        or not 0 < raw_length <= MAX_TILE_BYTES
        or offset + compressed_length > records_bytes
    ):
        raise V3PackageMergeError("merged V3 audit index entry is invalid")
    records_handle.seek(offset)
    compressed = records_handle.read(compressed_length)
    if len(compressed) != compressed_length:
        raise V3PackageMergeError("merged V3 audit records ended early")
    payload = _inflate_exact(compressed, raw_length, "merged V3 audit")
    if raw_hash32(payload) != expected_hash32:
        raise V3PackageMergeError("merged V3 audit integrity hash differs")
    return payload


def _read_index_order_payload(
    records_handle: object,
    index_handle: object,
    tile: TileKey,
    *,
    records_bytes: int,
    next_record_offset: int,
) -> tuple[bytes | None, int]:
    entry = index_handle.read(INDEX_ENTRY_BYTES)
    if len(entry) != INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("V3 audit index ended early")
    if entry == _ZERO_INDEX_ENTRY:
        return None, next_record_offset
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    if (
        flags != INDEX_FLAG_PRESENT
        or offset != next_record_offset
        or not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES
        or not 0 < raw_length <= MAX_TILE_BYTES
        or offset + compressed_length > records_bytes
    ):
        raise V3PackageMergeError("V3 audit index entry is invalid")
    compressed = records_handle.read(compressed_length)
    if len(compressed) != compressed_length:
        raise V3PackageMergeError("V3 audit records ended early")
    payload = _inflate_exact(compressed, raw_length, "V3 audit")
    if raw_hash32(payload) != expected_hash32:
        raise V3PackageMergeError("V3 audit integrity hash differs")
    return payload, offset + compressed_length


def _input_semantic_sha256(
    package: _InputPackage,
    *,
    records_handle: object,
    index_handle: object,
) -> str:
    digest = hashlib.sha256(_SEMANTIC_STREAM_DOMAIN)
    windows = tuple(item.window for item in package.ranges)
    original_records_position = records_handle.tell()
    original_index_position = index_handle.tell()
    try:
        state = _InputState(index_handle=index_handle, records_handle=records_handle)
        with tempfile.TemporaryDirectory(
            prefix="flightalert-v3-semantic-",
            dir=str(package.directory.parent),
        ) as temporary:
            temporary_root = Path(temporary)
            for window_index, window in enumerate(windows):
                bucket_paths: dict[int, Path] = {}
                bucket_handles: dict[int, object] = {}
                try:
                    for tile in _tiles_index_order((window,)):
                        present, envelopes = _read_input_tile(package, state, tile)
                        if not present:
                            continue
                        if list(envelopes) != sorted(
                            envelopes, key=lambda item: item.order_key
                        ):
                            raise V3PackageMergeError(
                                "supplement renderer semantic order is not canonical"
                            )
                        handle = bucket_handles.get(tile.x)
                        if handle is None:
                            path = (
                                temporary_root
                                / f"window-{window_index:04d}-x-{tile.x:08x}.semantic"
                            )
                            handle = path.open("ab")
                            bucket_handles[tile.x] = handle
                            bucket_paths[tile.x] = path
                        for record in envelopes:
                            body = struct.pack("<Q", tile.packed) + record.renderer_bytes
                            handle.write(struct.pack("<I", len(body)))
                            handle.write(body)
                finally:
                    for handle in bucket_handles.values():
                        handle.close()
                for x in range(window.x_min, window.x_max + 1):
                    path = bucket_paths.get(x)
                    if path is None:
                        continue
                    with path.open("rb") as handle:
                        while True:
                            chunk = handle.read(_FILE_HASH_CHUNK_BYTES)
                            if not chunk:
                                break
                            digest.update(chunk)
        _finish_input_validation(package, state)
        state.index_digest = hashlib.sha256()
        state.records_digest = hashlib.sha256()
        state.next_ordinal = 0
        state.next_record_offset = 0
        state.present_tiles = 0
        records_handle.seek(original_records_position)
        index_handle.seek(original_index_position)
    except (OSError, AttributeError) as error:
        raise V3PackageMergeError(
            "supplement renderer semantic stream is unreadable"
        ) from error
    return digest.hexdigest()


def _audit_output(
    partial_directory: Path,
    windows: Sequence[_Window],
) -> tuple[str, list[dict[str, object]]]:
    records_path = partial_directory / "records.fadictpack"
    index_path = partial_directory / "tile-index.bin"
    database_path = partial_directory / "identity-counts.sqlite"
    database_path.unlink(missing_ok=True)
    digest = hashlib.sha256(_SEMANTIC_STREAM_DOMAIN)
    posting_counts: Counter[SemanticSubtype] = Counter()
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(database_path)
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        connection.execute("PRAGMA temp_store=FILE")
        connection.execute(
            "CREATE TABLE features (subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
            "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
        )
        connection.execute(
            "CREATE TABLE variants (subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
            "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
        )
        connection.execute(
            "CREATE TABLE variant_payloads (identity BLOB NOT NULL, full_sha BLOB NOT NULL, "
            "PRIMARY KEY (identity, full_sha)) WITHOUT ROWID"
        )
        records_bytes = records_path.stat().st_size
        with records_path.open("rb") as records_handle, index_path.open("rb") as index_handle:
            next_record_offset = 0
            with tempfile.TemporaryDirectory(
                prefix="flightalert-v3-output-semantic-",
                dir=str(partial_directory.parent),
            ) as temporary:
                temporary_root = Path(temporary)
                for window_index, window in enumerate(windows):
                    bucket_paths: dict[int, Path] = {}
                    bucket_handles: dict[int, object] = {}
                    try:
                        for tile_number, tile in enumerate(
                            _tiles_index_order((window,)), start=1
                        ):
                            payload, next_record_offset = _read_index_order_payload(
                                records_handle,
                                index_handle,
                                tile,
                                records_bytes=records_bytes,
                                next_record_offset=next_record_offset,
                            )
                            if payload is None:
                                continue
                            envelopes = _extract_envelopes(tile, payload)
                            if list(envelopes) != sorted(
                                envelopes, key=lambda item: item.order_key
                            ):
                                raise V3PackageMergeError(
                                    "merged V3 renderer order is not canonical"
                                )
                            handle = bucket_handles.get(tile.x)
                            if handle is None:
                                path = (
                                    temporary_root
                                    / f"window-{window_index:04d}-x-{tile.x:08x}.semantic"
                                )
                                handle = path.open("ab")
                                bucket_handles[tile.x] = handle
                                bucket_paths[tile.x] = path
                            feature_rows: list[tuple[int, bytes]] = []
                            variant_rows: list[tuple[int, bytes]] = []
                            payload_rows: list[tuple[bytes, bytes]] = []
                            for record in envelopes:
                                body = struct.pack("<Q", tile.packed) + record.renderer_bytes
                                handle.write(struct.pack("<I", len(body)))
                                handle.write(body)
                                posting_counts[record.subtype] += 1
                                feature_rows.append(
                                    (
                                        record.subtype.value,
                                        record.feature_id.to_bytes(8, "big"),
                                    )
                                )
                                variant_rows.append(
                                    (
                                        record.subtype.value,
                                        record.variant_id.to_bytes(8, "big"),
                                    )
                                )
                                payload_rows.append(
                                    (
                                        record.variant_id.to_bytes(8, "big"),
                                        record.variant_full_sha256,
                                    )
                                )
                            connection.executemany(
                                "INSERT OR IGNORE INTO features (subtype, identity) VALUES (?, ?)",
                                feature_rows,
                            )
                            connection.executemany(
                                "INSERT OR IGNORE INTO variants (subtype, identity) VALUES (?, ?)",
                                variant_rows,
                            )
                            connection.executemany(
                                "INSERT OR IGNORE INTO variant_payloads (identity, full_sha) VALUES (?, ?)",
                                payload_rows,
                            )
                            if tile_number % 4096 == 0:
                                connection.commit()
                    finally:
                        for handle in bucket_handles.values():
                            handle.close()
                    for x in range(window.x_min, window.x_max + 1):
                        path = bucket_paths.get(x)
                        if path is None:
                            continue
                        with path.open("rb") as handle:
                            while True:
                                chunk = handle.read(_FILE_HASH_CHUNK_BYTES)
                                if not chunk:
                                    break
                                digest.update(chunk)
            if next_record_offset != records_bytes:
                raise V3PackageMergeError("V3 audit records pack has trailing bytes")
            if index_handle.read(1) or records_handle.read(1):
                raise V3PackageMergeError("V3 audit runtime files have trailing bytes")
        connection.commit()
        if connection.execute(
            "SELECT identity FROM variant_payloads GROUP BY identity HAVING COUNT(*) > 1 LIMIT 1"
        ).fetchone() is not None:
            raise V3PackageMergeError("divergent duplicate canonical variant identity")
        feature_counts = {
            int(subtype): int(count)
            for subtype, count in connection.execute(
                "SELECT subtype, COUNT(*) FROM features GROUP BY subtype"
            )
        }
        variant_counts = {
            int(subtype): int(count)
            for subtype, count in connection.execute(
                "SELECT subtype, COUNT(*) FROM variants GROUP BY subtype"
            )
        }
        subtype_counts = [
            {
                "semanticSubtype": subtype.value,
                "semanticSubtypeName": subtype.name,
                "distinctFeatureIds": feature_counts.get(subtype.value, 0),
                "canonicalVariantIds": variant_counts.get(subtype.value, 0),
                "postings": posting_counts[subtype],
            }
            for subtype in SemanticSubtype
        ]
        return digest.hexdigest(), subtype_counts
    finally:
        if connection is not None:
            connection.close()
        database_path.unlink(missing_ok=True)


def _merger_sha256() -> str:
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def _output_size_decision(
    binding: Mapping[str, object],
    *,
    required_package_bytes: int,
    available_destination_bytes: int,
    publication_boundary_free_bytes: int,
) -> dict[str, object]:
    policy = _SIZE_POLICY_DOCUMENT
    reserve = int(policy["destinationReserveBytes"])
    budgets = policy["historicalBudgets"]
    assert isinstance(budgets, dict)
    mandatory = required_package_bytes + reserve
    hard_component = required_package_bytes >= int(
        budgets["hardComponentPackageBytes"]
    )
    hard_phone = mandatory >= int(budgets["hardMandatoryPhoneFootprintBytes"])
    preferred_component = required_package_bytes >= int(
        budgets["preferredComponentPackageBytes"]
    )
    preferred_phone = mandatory >= int(
        budgets["preferredMandatoryPhoneFootprintBytes"]
    )
    mode = binding["mode"]
    if mode == _VISUAL_SIZE_POLICY:
        boundary_authorized = publication_boundary_free_bytes >= reserve
        authorized = (
            available_destination_bytes >= mandatory and boundary_authorized
        )
        decision: dict[str, object] = {
            "authorized": authorized,
            "availableDestinationBytes": available_destination_bytes,
            "hardComponentPackageCeilingExceeded": hard_component,
            "hardMandatoryPhoneFootprintCeilingExceeded": hard_phone,
            "mandatoryPhoneFootprintBytes": mandatory,
            "mode": mode,
            "preferredComponentPackageCeilingExceeded": preferred_component,
            "preferredMandatoryPhoneFootprintCeilingExceeded": preferred_phone,
            "publicationBoundaryAuthorized": boundary_authorized,
            "publicationBoundaryDestinationFreeBytes": (
                publication_boundary_free_bytes
            ),
            "publicationBoundaryRequiredReserveBytes": reserve,
            "requiredPackageBytes": required_package_bytes,
            "requiredWithReserveBytes": mandatory,
            "schema": _SIZE_POLICY_DECISION_SCHEMA,
        }
    else:
        decision = {
            "authorized": not hard_component,
            "availableDestinationBytes": None,
            "hardComponentPackageCeilingExceeded": hard_component,
            "hardMandatoryPhoneFootprintCeilingExceeded": hard_phone,
            "mandatoryPhoneFootprintBytes": mandatory,
            "mode": mode,
            "preferredComponentPackageCeilingExceeded": preferred_component,
            "preferredMandatoryPhoneFootprintCeilingExceeded": preferred_phone,
            "requiredPackageBytes": required_package_bytes,
            "requiredWithReserveBytes": mandatory,
            "schema": _SIZE_POLICY_DECISION_SCHEMA,
        }
    if not decision["authorized"]:
        raise V3PackageMergeError(
            "merged V3 package lacks its authenticated size-policy capacity"
        )
    return decision


def _converge_authority_documents(
    *,
    manifest: dict[str, object],
    merge_document: dict[str, object],
    receipt: dict[str, object],
    size_binding: Mapping[str, object],
    binary_output_bytes: int,
    initial_destination_free: int,
    publication_boundary_free: int,
) -> tuple[bytes, bytes, dict[str, object]]:
    """Bind exact JSON bytes to capacity without a threshold-length cycle."""

    if type(binary_output_bytes) is not int or binary_output_bytes < 0:
        raise V3PackageMergeError("merged V3 binary output byte count is malformed")
    output_files = receipt.get("outputFiles")
    if type(output_files) is not list or not output_files:
        raise V3PackageMergeError("merged V3 receipt output files are malformed")
    for padding_bytes in range(65):
        if padding_bytes:
            receipt["accountingConvergencePadding"] = "x" * (padding_bytes - 1)
        else:
            receipt.pop("accountingConvergencePadding", None)
        decision = _output_size_decision(
            size_binding,
            required_package_bytes=0,
            available_destination_bytes=initial_destination_free,
            publication_boundary_free_bytes=publication_boundary_free,
        )
        for _ in range(64):
            size_policy = {
                "accountingScope": (
                    "merge-output-before-class-catalog-finalization"
                ),
                "binding": size_binding,
                "decision": decision,
            }
            merge_document["sizePolicy"] = size_policy
            receipt["sizePolicy"] = size_policy
            manifest_bytes = _canonical_json_bytes(manifest)
            output_files[0] = {
                "name": "manifest.json",
                "bytes": len(manifest_bytes),
                "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            }
            receipt_bytes = _canonical_json_bytes(receipt)
            required_package_bytes = (
                binary_output_bytes + len(manifest_bytes) + len(receipt_bytes)
            )
            next_decision = _output_size_decision(
                size_binding,
                required_package_bytes=required_package_bytes,
                available_destination_bytes=initial_destination_free,
                publication_boundary_free_bytes=publication_boundary_free,
            )
            if next_decision == decision:
                return manifest_bytes, receipt_bytes, decision
            decision = next_decision
    raise V3PackageMergeError(
        "merged V3 size-policy byte accounting did not converge"
    )


def _verify_published_output(
    output_directory: Path,
    *,
    manifest_bytes: bytes,
    receipt_bytes: bytes,
    records_sha256: str,
    records_bytes: int,
    index_sha256: str,
    index_bytes: int,
) -> None:
    expected = (
        (
            output_directory / "manifest.json",
            len(manifest_bytes),
            hashlib.sha256(manifest_bytes).hexdigest(),
        ),
        (
            output_directory / "merge-receipt.json",
            len(receipt_bytes),
            hashlib.sha256(receipt_bytes).hexdigest(),
        ),
        (output_directory / "records.fadictpack", records_bytes, records_sha256),
        (output_directory / "tile-index.bin", index_bytes, index_sha256),
    )
    try:
        for path, byte_count, sha256 in expected:
            if path.stat().st_size != byte_count or _sha256_file(path) != sha256:
                raise V3PackageMergeError(
                    "published output differs from its bound byte streams"
                )
    except OSError as error:
        raise V3PackageMergeError(
            "published output differs from its bound byte streams"
        ) from error


def merge_v3_packages(
    *,
    primary_directory: Path,
    supplement_directories: Sequence[Path],
    supplement_build_receipts: Sequence[Path] = (),
    output_directory: Path,
    package_id: str,
    compression_workers: int | None = None,
    authority_semantic_mode: str = _AUTHORITY_SEMANTIC_RECOMPUTE,
) -> MergeResult:
    """Stream one primary and sparse supplements into one Android-readable V3 package."""

    if not isinstance(output_directory, Path):
        raise V3PackageMergeError("output directory must be a pathlib.Path")
    compression_worker_count = _validated_compression_workers(
        compression_workers
    )
    if authority_semantic_mode not in _AUTHORITY_SEMANTIC_MODES:
        raise V3PackageMergeError("authority semantic mode is invalid")
    checked_package_id = _validate_package_id(package_id)
    if type(supplement_directories) not in (tuple, list):
        raise V3PackageMergeError("supplement directories must be an ordered sequence")
    if any(not isinstance(path, Path) for path in supplement_directories):
        raise V3PackageMergeError("supplement directories must be pathlib.Path values")
    if output_directory.exists():
        raise V3PackageMergeError("output directory already exists")
    partial_directory = output_directory.with_name(output_directory.name + ".partial")
    if partial_directory.exists():
        raise V3PackageMergeError("partial merge directory already exists")

    primary = _load_input(primary_directory)
    supplements = sorted(
        (_load_input(path) for path in supplement_directories),
        key=lambda item: (
            item.package_id,
            item.manifest_sha256,
            item.records_sha256,
            item.index_sha256,
        ),
    )
    authority_receipts = _load_authority_receipts(
        supplement_build_receipts, supplements
    )
    authority_by_package_directory = {
        receipt.package_directory: receipt for receipt in authority_receipts
    }
    authority_evidence = [receipt.evidence() for receipt in authority_receipts]
    initial_destination_free = None
    if authority_receipts:
        try:
            initial_destination_free = shutil.disk_usage(output_directory.parent).free
        except OSError as error:
            raise V3PackageMergeError(
                "merged V3 destination capacity is unreadable"
            ) from error
        if type(initial_destination_free) is not int or initial_destination_free < 0:
            raise V3PackageMergeError("merged V3 destination capacity is malformed")
    packages = (primary, *supplements)
    windows = _union_windows(packages)
    input_bindings = [primary.binding("primary")]
    input_bindings.extend(package.binding("supplement") for package in supplements)
    merger_sha256 = _merger_sha256()
    total_tiles = sum(window.tile_count for window in windows)
    output_index_bytes = total_tiles * INDEX_ENTRY_BYTES
    if output_index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise V3PackageMergeError(
            "merged V3 Android index exceeds its byte-array bound"
        )
    partial_directory.mkdir(parents=True, exist_ok=False)
    output_records_path = partial_directory / "records.fadictpack"
    output_index_path = partial_directory / "tile-index.bin"
    try:
        output_offset = 0
        present_tiles = 0
        output_records_digest = hashlib.sha256()
        output_index_digest = hashlib.sha256()
        with contextlib.ExitStack() as stack:
            states = [
                _InputState(
                    stack.enter_context((package.directory / "tile-index.bin").open("rb")),
                    stack.enter_context((package.directory / "records.fadictpack").open("rb")),
                )
                for package in packages
            ]
            for package, state in zip(packages, states, strict=True):
                authority = authority_by_package_directory.get(
                    package.directory.resolve()
                )
                if (
                    authority is None
                    or authority_semantic_mode
                    == _AUTHORITY_SEMANTIC_RECEIPT_BOUND_VISUAL
                ):
                    continue
                semantic_sha256 = _input_semantic_sha256(
                    package,
                    records_handle=state.records_handle,
                    index_handle=state.index_handle,
                )
                if semantic_sha256 != authority.renderer_semantic_stream_sha256:
                    raise V3PackageMergeError(
                        "supplement build receipt renderer semantic stream "
                        "differs from the held runtime files"
                    )
            output_records = stack.enter_context(output_records_path.open("wb"))
            output_index = stack.enter_context(output_index_path.open("wb"))

            def merged_payloads() -> Iterator[bytes | None]:
                for tile in _tiles_index_order(windows):
                    any_present = False
                    merged_records: dict[
                        tuple[int, int, int, int], _RawEnvelope
                    ] = {}
                    merged_payload_bytes = _TILE_PAYLOAD_HEADER_BYTES
                    for package, state in zip(packages, states, strict=True):
                        present, records = _read_input_tile(package, state, tile)
                        any_present = any_present or present
                        if present:
                            merged_payload_bytes = _merge_contribution(
                                tile,
                                merged_records,
                                merged_payload_bytes,
                                records,
                            )
                    if not any_present:
                        yield None
                        continue
                    yield _merge_payload(
                        tile,
                        merged_records,
                        merged_payload_bytes,
                    )

            encoded_tiles = _encode_tiles_ordered(
                merged_payloads(),
                compression_workers=compression_worker_count,
            )
            try:
                for encoded in encoded_tiles:
                    if encoded is None:
                        output_index.write(_ZERO_INDEX_ENTRY)
                        output_index_digest.update(_ZERO_INDEX_ENTRY)
                        continue
                    compressed = encoded.compressed
                    index_entry = encode_index_entry(
                        offset=output_offset,
                        compressed_length=len(compressed),
                        raw_length=encoded.raw_length,
                        raw_hash32=encoded.raw_hash32,
                    )
                    output_index.write(index_entry)
                    output_index_digest.update(index_entry)
                    output_records.write(compressed)
                    output_records_digest.update(compressed)
                    output_offset += len(compressed)
                    present_tiles += 1
            finally:
                encoded_tiles.close()
            for package, state in zip(packages, states, strict=True):
                _finish_input_validation(package, state)
        if output_index_path.stat().st_size != output_index_bytes:
            raise V3PackageMergeError("merged V3 index length differs from coverage")
        if output_records_path.stat().st_size != output_offset:
            raise V3PackageMergeError("merged V3 records length differs from its stream")

        semantic_sha256, subtype_counts = _audit_output(partial_directory, windows)
        records_sha256 = output_records_digest.hexdigest()
        index_sha256 = output_index_digest.hexdigest()
        complete_declared = present_tiles == total_tiles
        complete_whole = (
            primary.complete_whole_earth_dictionary
            and complete_declared
            and all(
                not _extends_primary_scope(primary, supplement)
                or supplement.complete_whole_earth_dictionary
                for supplement in supplements
            )
            and all(
                window.x_min == 0
                and window.y_min == 0
                and window.x_max == (1 << window.z) - 1
                and window.y_max == (1 << window.z) - 1
                for window in windows
            )
        )
        primary_zooms = {item.window.z for item in primary.ranges}
        merged_by_zoom = {window.z: window for window in windows}
        primary_whole_preserved = (
            primary.complete_whole_earth_dictionary
            and all(
                merged_by_zoom[item.window.z] == item.window
                for item in primary.ranges
                if item.window.z in primary_zooms
            )
        )
        coverage = {
            "tileCount": total_tiles,
            "completeDeclaredScope": complete_declared,
            "completeWholeEarthDictionary": complete_whole,
            "zoomRanges": [window.document() for window in windows],
        }
        merge_document: dict[str, object] = {
            "schema": (
                _AUTHORITY_MERGE_SCHEMA if authority_receipts else _MERGE_SCHEMA
            ),
            "mergerSha256": merger_sha256,
            "inputs": input_bindings,
            "output": {
                "recordsSha256": records_sha256,
                "recordsBytes": output_offset,
                "tileIndexSha256": index_sha256,
                "tileIndexBytes": output_index_bytes,
            },
        }
        if authority_receipts:
            merge_document["authorityReceipts"] = authority_evidence
            if (
                authority_semantic_mode
                == _AUTHORITY_SEMANTIC_RECEIPT_BOUND_VISUAL
            ):
                merge_document["authoritySemanticVerification"] = dict(
                    _RECEIPT_BOUND_VISUAL_AUTHORITY_SEMANTIC_VERIFICATION
                )
        manifest: dict[str, object] = {
            "packageId": checked_package_id,
            "schemaVersion": 3,
            "payloadSchema": PAYLOAD_SCHEMA,
            "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
            "sourcedTextPolicySha256": SOURCED_TEXT_POLICY_SHA256,
            "unicodeScriptProfileSha256": UNICODE_SCRIPT_PROFILE_SHA256,
            "compatibility": {"emptyPresentTilesSharePayload": False},
            "coverage": coverage,
            "rendererSemanticStreamSha256": semantic_sha256,
            "merge": merge_document,
        }
        manifest_path = partial_directory / "manifest.json"
        receipt: dict[str, object] = {
            "schema": (
                _AUTHORITY_RECEIPT_SCHEMA
                if authority_receipts
                else _RECEIPT_SCHEMA
            ),
            "packageId": checked_package_id,
            "mergerSha256": merger_sha256,
            "inputs": input_bindings,
            "coverage": {
                **coverage,
                "presentTileCount": present_tiles,
                "primaryWholeEarthPreserved": primary_whole_preserved,
            },
            "rendererSemanticStreamSha256": semantic_sha256,
            "subtypeCounts": subtype_counts,
            "outputFiles": [
                {
                    "name": "manifest.json",
                    "bytes": 0,
                    "sha256": "0" * 64,
                },
                {
                    "name": "records.fadictpack",
                    "bytes": output_offset,
                    "sha256": records_sha256,
                },
                {
                    "name": "tile-index.bin",
                    "bytes": output_index_bytes,
                    "sha256": index_sha256,
                },
            ],
        }
        if authority_receipts:
            receipt["authorityReceipts"] = authority_evidence
            assert initial_destination_free is not None
            size_binding = dict(authority_receipts[0].size_policy_binding)
            for _ in range(16):
                try:
                    publication_boundary_free = shutil.disk_usage(
                        partial_directory.parent
                    ).free
                except OSError as error:
                    raise V3PackageMergeError(
                        "merged V3 publication capacity is unreadable"
                    ) from error
                if (
                    type(publication_boundary_free) is not int
                    or publication_boundary_free < 0
                ):
                    raise V3PackageMergeError(
                        "merged V3 publication capacity is malformed"
                    )
                manifest_bytes, receipt_bytes, _ = (
                    _converge_authority_documents(
                        manifest=manifest,
                        merge_document=merge_document,
                        receipt=receipt,
                        size_binding=size_binding,
                        binary_output_bytes=output_offset + output_index_bytes,
                        initial_destination_free=initial_destination_free,
                        publication_boundary_free=publication_boundary_free,
                    )
                )
                if (
                    len(manifest_bytes) > _MAX_MANIFEST_BYTES
                    or len(receipt_bytes) > _MAX_MANIFEST_BYTES
                ):
                    raise V3PackageMergeError(
                        "merged V3 authority evidence exceeds its JSON bound"
                    )
                manifest_path.write_bytes(manifest_bytes)
                (partial_directory / "merge-receipt.json").write_bytes(
                    receipt_bytes
                )
                try:
                    observed_boundary_free = shutil.disk_usage(
                        partial_directory.parent
                    ).free
                except OSError as error:
                    raise V3PackageMergeError(
                        "merged V3 publication capacity is unreadable"
                    ) from error
                if observed_boundary_free == publication_boundary_free:
                    break
            else:
                raise V3PackageMergeError(
                    "merged V3 publication capacity did not stabilize"
                )
        else:
            manifest_bytes = _canonical_json_bytes(manifest)
            manifest_path.write_bytes(manifest_bytes)
            receipt["outputFiles"][0] = {
                "name": "manifest.json",
                "bytes": len(manifest_bytes),
                "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
            }
            receipt_bytes = _canonical_json_bytes(receipt)
            (partial_directory / "merge-receipt.json").write_bytes(receipt_bytes)
        os.replace(partial_directory, output_directory)
        try:
            _verify_published_output(
                output_directory,
                manifest_bytes=manifest_bytes,
                receipt_bytes=receipt_bytes,
                records_sha256=records_sha256,
                records_bytes=output_offset,
                index_sha256=index_sha256,
                index_bytes=output_index_bytes,
            )
        except Exception:
            shutil.rmtree(output_directory, ignore_errors=True)
            raise
        return MergeResult(output_directory, manifest, receipt)
    except Exception:
        shutil.rmtree(partial_directory, ignore_errors=True)
        raise


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Stream a primary and sparse supplements into one Experiment 8 V3 package."
    )
    parser.add_argument("--primary", required=True, type=Path)
    parser.add_argument("--supplement", action="append", default=[], type=Path)
    parser.add_argument(
        "--supplement-build-receipt",
        action="append",
        default=[],
        type=Path,
    )
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-id", required=True)
    parser.add_argument(
        "--compression-workers",
        type=int,
        help=(
            "ordered compression worker count; 1 selects the deterministic "
            "serial fallback"
        ),
    )
    parser.add_argument(
        "--authority-semantic-mode",
        choices=sorted(_AUTHORITY_SEMANTIC_MODES),
        default=_AUTHORITY_SEMANTIC_RECOMPUTE,
        help=(
            "strict recompute is the documentary default; "
            "receipt-bound-visual-evaluation skips duplicate authority semantic "
            "pre-scan for provisional visual package cooks only"
        ),
    )
    parsed = parser.parse_args(arguments)
    result = merge_v3_packages(
        primary_directory=parsed.primary,
        supplement_directories=tuple(parsed.supplement),
        supplement_build_receipts=tuple(parsed.supplement_build_receipt),
        output_directory=parsed.output,
        package_id=parsed.package_id,
        compression_workers=parsed.compression_workers,
        authority_semantic_mode=parsed.authority_semantic_mode,
    )
    print(_canonical_json_bytes(result.receipt).decode("utf-8"), end="")
    return 0


__all__ = [
    "MergeResult",
    "V3PackageMergeError",
    "merge_v3_packages",
]


if __name__ == "__main__":
    raise SystemExit(_main())
