from __future__ import annotations

import hashlib
import json
import multiprocessing
import os
import re
import shutil
import stat
import struct
from concurrent.futures import FIRST_COMPLETED, ProcessPoolExecutor, wait
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Callable, Iterable, Sequence

from .model import TileKey
from .osm_global_waterway_package import GlobalWaterwayPackageError
from .osm_global_waterway_renderer import (
    ExactWaterwayFeature,
    ExactWaterwayPoint,
    _SUBTYPE_BY_WATERWAY,
    _WORLD_DENOMINATOR,
    _candidate_tiles,
    _great_circle_length_m,
    _simplified_indices,
    _u64_identity,
    _unwrapped_world_points,
    build_adaptive_waterway_feature,
)
from .reference_presentation_policy import (
    LABEL_ACTIVE_BAND_LIMIT,
    LABEL_DISPLAY_MAX_ZOOM_CENTI,
    LABEL_FADE_OUT_ZOOM_CENTI,
    LINE_LABEL_REPEAT_SPACING_PX,
    PRESENTATION_POLICY_SHA256,
    REFERENCE_LABEL_COLLISION_GROUP,
    LabelFacts,
    SourceEvidenceContext,
    prominence_decision_for_label,
    prominence_decision_sha256,
    visibility_rule_for_label,
)
from .renderer_tile_package import (
    MAX_RENDERER_RECORD_BYTES,
    MAX_SOURCED_TEXT_RECORD_BYTES,
    _decode_renderer_record,
    _decode_sourced_text,
)
from .semantic_model import (
    FeatureKind,
    HotIdRegistry,
    LandEvidence,
    LayerGroup,
    PlacementSourceKind,
    ProminenceTier as RendererProminenceTier,
    ProtectedStatus,
    RendererGeometry,
    TextEvidenceKind,
    make_canonical_variant,
    make_normalized_placement,
    renderer_geometry_fingerprint,
    renderer_record_bytes,
    variant_fingerprint,
)
from .sourced_text import create_sourced_map_text


_JOB_MAGIC = b"FAE8WRJOB"
_JOB_VERSION = 2
_BATCH_MAGIC = b"FAE8WRBATCH"
_BATCH_VERSION = 2
_BATCH_SCHEMA = "flightalert.experiment8.waterway-render-batch.v2"
_SPOOL_OWNER_SCHEMA = "flightalert.experiment8.waterway-parallel-spool.v1"
_SPOOL_OWNER_FILE = "owner.json"
_SPOOL_OWNER_MAX_BYTES = 16 * 1024
_SPOOL_INVENTORY_STABLE_ATTEMPTS = 4
_DESTINATION_FREE_SPACE_RESERVE = 1_500_000_000
_SOURCE_RANGE_DOMAIN = b"FAE8WRSOURCERANGE1\0"
_SOURCE_FRAME_DOMAIN = b"FAE8WRSOURCEFRAME1\0"
_RENDER_CHECKPOINT_FEATURES = 100
_MAX_BATCH_FEATURES = 100
_MAX_FEATURE_PARTS = 65_536
_MAX_FEATURE_POINTS = MAX_RENDERER_RECORD_BYTES // struct.calcsize(">Qii")
_MAX_FEATURE_BYTES = 16 * 1024 * 1024
_MAX_JOB_BYTES = 128 * 1024 * 1024
_MAX_SPOOL_BYTES = 1024 * 1024 * 1024
_MAX_PATH_BYTES = 32_768
_MAX_TIMESTAMP_BYTES = 256
_MAX_SOURCE_KEY_BYTES = 1_024
_MAX_SOURCE_TEXT_BYTES = 4_096
_MAX_REGISTRY_DOMAIN_BYTES = 256
_MAX_REGISTRY_CANONICAL_BYTES = 16 * 1024 * 1024
_MAX_REGISTRY_CLAIMS = 4_096
_MAX_IDENTITY_ROWS = 4_096
_MAX_RECORD_ROWS = 1_000_000
_MAX_RECORD_ENVELOPE_BYTES = (
    4 + MAX_RENDERER_RECORD_BYTES + 4 + 32 + MAX_SOURCED_TEXT_RECORD_BYTES
)
_MAX_FILE_ORDINAL = 999_999_999_999
_MAX_PARALLEL_RENDER_WORKERS = 61 if os.name == "nt" else 64
_SPOOL_CHILD_PATTERN = re.compile(
    r"^(?P<start>[0-9]{12})-(?P<end>[0-9]{12})\.batch"
    r"(?P<temporary>\.tmp-[0-9]+)?$"
)
_REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
_WINDOWS_GENERIC_READ = 0x80000000
_WINDOWS_GENERIC_WRITE = 0x40000000
_WINDOWS_DELETE_ACCESS = 0x00010000
_WINDOWS_SYNCHRONIZE_ACCESS = 0x00100000
_WINDOWS_FILE_READ_ATTRIBUTES = 0x00000080
_WINDOWS_FILE_SHARE_READ = 0x00000001
_WINDOWS_FILE_SHARE_WRITE = 0x00000002
_WINDOWS_CREATE_NEW = 1
_WINDOWS_OPEN_EXISTING = 3
_WINDOWS_FILE_ATTRIBUTE_NORMAL = 0x00000080
_WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
_WINDOWS_FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
_WINDOWS_FILE_RENAME_INFO_CLASS = 3
_WINDOWS_FILE_DISPOSITION_INFO_CLASS = 4
_WATERWAY_CODES = {
    "river": 1,
    "stream": 2,
    "canal": 3,
    "tidal_channel": 4,
    "wadi": 5,
}
_WATERWAYS_BY_CODE = {value: key for key, value in _WATERWAY_CODES.items()}
_SOURCE_KIND_CODES = {"way": 1, "relation": 2}
_SOURCE_KINDS_BY_CODE = {value: key for key, value in _SOURCE_KIND_CODES.items()}
_IDENTITY_TABLE_CODES = {
    "feature_ids": 1,
    "variant_ids": 2,
    "geometry_ids": 3,
    "label_ids": 4,
    "sourced_ids": 5,
}
_IDENTITY_TABLES_BY_CODE = {
    value: key for key, value in _IDENTITY_TABLE_CODES.items()
}


def _error(message: str) -> GlobalWaterwayPackageError:
    return GlobalWaterwayPackageError(message)


def _require_int(value: object, minimum: int, maximum: int, label: str) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise _error(f"{label} is outside its canonical integer range")
    return value


def _require_sha256_text(value: object, label: str) -> str:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise _error(f"{label} must be lowercase hexadecimal SHA-256")
    return value


def _require_sha256_bytes(value: object, label: str) -> bytes:
    if type(value) is not bytes or len(value) != 32:
        raise _error(f"{label} must be 32 immutable SHA-256 bytes")
    return value


def _utf8(value: object, maximum_bytes: int, label: str) -> bytes:
    if type(value) is not str:
        raise _error(f"{label} must be text")
    try:
        encoded = value.encode("utf-8", "strict")
    except UnicodeError as error:
        raise _error(f"{label} is not strict UTF-8 text") from error
    if len(encoded) > maximum_bytes:
        raise _error(f"{label} exceeds its canonical byte bound")
    return encoded


def _feature_point_count(feature: ExactWaterwayFeature) -> int:
    return sum(len(part) for part in feature.parts)


@dataclass(frozen=True, slots=True)
class ParallelRenderLimits:
    workers: int
    max_in_flight_jobs: int
    max_in_flight_points: int
    max_points_per_job: int
    max_in_flight_input_bytes: int
    max_input_bytes_per_job: int
    max_spool_bytes: int
    max_spool_bytes_per_job: int

    def __post_init__(self) -> None:
        if (
            type(self.workers) is not int
            or not 1 <= self.workers <= _MAX_PARALLEL_RENDER_WORKERS
        ):
            raise _error("parallel render worker count is invalid")
        if (
            type(self.max_in_flight_jobs) is not int
            or not self.workers
            <= self.max_in_flight_jobs
            <= self.workers * 2
        ):
            raise _error("parallel render in-flight job bound is invalid")
        resource_values = (
            self.max_in_flight_points,
            self.max_points_per_job,
            self.max_in_flight_input_bytes,
            self.max_input_bytes_per_job,
            self.max_spool_bytes,
            self.max_spool_bytes_per_job,
        )
        if any(type(value) is not int or value <= 0 for value in resource_values):
            raise _error("parallel render resource bound is invalid")


def maximum_parallel_render_workers() -> int:
    """Return the current platform's honest ProcessPoolExecutor ceiling."""

    return _MAX_PARALLEL_RENDER_WORKERS


@dataclass(frozen=True, slots=True)
class FeatureRenderBatchJob:
    start_ordinal: int
    features: tuple[ExactWaterwayFeature, ...]
    source_generation_sha256: str
    classifier_sha256: str
    zooms: tuple[int, ...]
    render_run_identity_sha256: str
    package_id: str
    spool_owner_sha256: str
    spool_directory: str
    spool_byte_quota: int

    def __post_init__(self) -> None:
        _require_int(
            self.start_ordinal, 0, _MAX_FILE_ORDINAL, "feature batch start ordinal"
        )
        if (
            type(self.features) is not tuple
            or not self.features
            or len(self.features) > _MAX_BATCH_FEATURES
        ):
            raise _error("feature batch feature count is outside its bound")
        if self.start_ordinal + len(self.features) > _MAX_FILE_ORDINAL:
            raise _error("feature batch end ordinal is outside its file-name bound")
        end_ordinal = self.start_ordinal + len(self.features)
        if (
            self.start_ordinal // _RENDER_CHECKPOINT_FEATURES
            != (end_ordinal - 1) // _RENDER_CHECKPOINT_FEATURES
        ):
            raise _error("feature batch cannot cross a render checkpoint boundary")
        total_points = 0
        for feature in self.features:
            if type(feature) is not ExactWaterwayFeature:
                raise _error("feature batch contains a non-exact waterway feature")
            points = _feature_point_count(feature)
            if points > _MAX_FEATURE_POINTS:
                raise _error("feature batch exact feature exceeds its point bound")
            total_points += points
        if total_points > _MAX_FEATURE_POINTS * _MAX_BATCH_FEATURES:
            raise _error("feature batch aggregate point count exceeds its bound")
        _require_sha256_text(
            self.source_generation_sha256, "source generation identity"
        )
        _require_sha256_text(self.classifier_sha256, "classifier identity")
        _require_sha256_text(
            self.render_run_identity_sha256, "render run identity"
        )
        expected_owner = _spool_owner_bytes(
            package_id=self.package_id,
            render_run_identity_sha256=self.render_run_identity_sha256,
            source_document_sha256=self.source_generation_sha256,
        )
        owner_sha256 = _require_sha256_text(
            self.spool_owner_sha256, "feature batch spool owner identity"
        )
        if hashlib.sha256(expected_owner).hexdigest() != owner_sha256:
            raise _error(
                "feature batch spool owner identity differs from its package binding"
            )
        if (
            type(self.zooms) is not tuple
            or not self.zooms
            or len(self.zooms) > 30
            or len(set(self.zooms)) != len(self.zooms)
            or any(type(zoom) is not int or not 0 <= zoom <= 29 for zoom in self.zooms)
        ):
            raise _error("feature batch zooms must be nonempty, unique, and inside [0,29]")
        directory_bytes = _utf8(
            self.spool_directory, _MAX_PATH_BYTES, "feature batch spool directory"
        )
        if not directory_bytes or not Path(self.spool_directory).is_absolute():
            raise _error("feature batch spool directory must be one absolute path")
        _require_int(
            self.spool_byte_quota,
            1,
            _MAX_SPOOL_BYTES,
            "feature batch spool byte quota",
        )


@dataclass(frozen=True, slots=True)
class RegistryClaim:
    domain: bytes
    canonical_bytes: bytes
    full_sha256: bytes
    hot_id: int

    def __post_init__(self) -> None:
        if (
            type(self.domain) is not bytes
            or not self.domain.endswith(b"\0")
            or not 0 < len(self.domain) <= _MAX_REGISTRY_DOMAIN_BYTES
        ):
            raise _error("registry claim domain is malformed")
        if (
            type(self.canonical_bytes) is not bytes
            or len(self.canonical_bytes) > _MAX_REGISTRY_CANONICAL_BYTES
        ):
            raise _error("registry claim canonical bytes exceed their bound")
        _require_sha256_bytes(self.full_sha256, "registry claim full identity")
        _require_int(self.hot_id, 0, (1 << 64) - 1, "registry claim hot identity")


@dataclass(frozen=True, slots=True)
class SpoolDescriptor:
    start_ordinal: int
    end_ordinal_exclusive: int
    file_name: str
    byte_count: int
    sha256: str
    source_range_sha256: str
    point_count: int

    def __post_init__(self) -> None:
        _require_int(
            self.start_ordinal, 0, _MAX_FILE_ORDINAL, "spool start ordinal"
        )
        _require_int(
            self.end_ordinal_exclusive,
            self.start_ordinal + 1,
            _MAX_FILE_ORDINAL,
            "spool end ordinal",
        )
        if type(self.file_name) is not str or not self.file_name:
            raise _error("spool descriptor file name is malformed")
        _utf8(self.file_name, 256, "spool descriptor file name")
        _require_int(self.byte_count, 1, _MAX_SPOOL_BYTES, "spool byte count")
        _require_sha256_text(self.sha256, "spool file identity")
        _require_sha256_text(self.source_range_sha256, "spool source-range identity")
        _require_int(
            self.point_count,
            1,
            _MAX_FEATURE_POINTS * _MAX_BATCH_FEATURES,
            "spool source point count",
        )


@dataclass(frozen=True, slots=True)
class FeatureRenderFrame:
    ordinal: int
    source_kind: str
    source_id: int
    source_version: int
    source_timestamp: str
    waterway_type: str
    source_feature_sha256: bytes
    source_frame_sha256: bytes
    part_count: int
    point_count: int
    required_node_count: int
    geometry_source_witnesses: tuple[
        tuple[int, tuple[tuple[int, ...], ...]], ...
    ]
    rendered_feature_row: tuple[object, ...]
    registry_claims: tuple[RegistryClaim, ...]
    identity_rows: tuple[tuple[str, bytes, bytes], ...]
    record_rows: tuple[tuple[object, ...], ...]
    posting_bytes: int

    def __post_init__(self) -> None:
        _require_int(self.ordinal, 0, _MAX_FILE_ORDINAL, "feature frame ordinal")
        if self.source_kind not in _SOURCE_KIND_CODES:
            raise _error("feature frame source kind is unsupported")
        _require_int(self.source_id, 1, (1 << 63) - 1, "feature frame source ID")
        _require_int(
            self.source_version, 1, (1 << 63) - 1, "feature frame source version"
        )
        _utf8(self.source_timestamp, _MAX_TIMESTAMP_BYTES, "feature frame timestamp")
        if self.waterway_type not in _WATERWAY_CODES:
            raise _error("feature frame waterway type is unsupported")
        _require_sha256_bytes(
            self.source_feature_sha256, "feature frame exact source identity"
        )
        _require_sha256_bytes(
            self.source_frame_sha256, "feature frame canonical source identity"
        )
        _require_int(self.part_count, 1, _MAX_FEATURE_PARTS, "feature frame part count")
        _require_int(
            self.point_count, 2, _MAX_FEATURE_POINTS, "feature frame point count"
        )
        _require_int(
            self.required_node_count,
            0,
            self.point_count,
            "feature frame required-node count",
        )
        if (
            type(self.geometry_source_witnesses) is not tuple
            or not 1 <= len(self.geometry_source_witnesses) <= 30
        ):
            raise _error("feature frame geometry witness count is outside its bound")
        previous_zoom = -1
        for witness in self.geometry_source_witnesses:
            if type(witness) is not tuple or len(witness) != 2:
                raise _error("feature frame geometry source witness is malformed")
            zoom, parts = witness
            if type(zoom) is not int or not previous_zoom < zoom <= 29:
                raise _error("feature frame geometry witness zooms are not canonical")
            previous_zoom = zoom
            if type(parts) is not tuple or len(parts) != self.part_count:
                raise _error("feature frame geometry witness part count differs")
            selected_points = 0
            for indices in parts:
                if (
                    type(indices) is not tuple
                    or not 2 <= len(indices) <= self.point_count
                    or any(type(index) is not int for index in indices)
                    or any(
                        not 0 <= index < self.point_count
                        for index in indices
                    )
                    or any(
                        first >= second
                        for first, second in zip(indices, indices[1:])
                    )
                ):
                    raise _error("feature frame geometry source indices are malformed")
                selected_points += len(indices)
            if selected_points > self.point_count:
                raise _error("feature frame geometry witness exceeds source point count")
        expected_rendered_row = (
            self.ordinal,
            self.source_kind,
            self.source_id,
            self.waterway_type,
            self.source_feature_sha256,
        )
        if (
            type(self.rendered_feature_row) is not tuple
            or self.rendered_feature_row != expected_rendered_row
        ):
            raise _error("feature frame rendered-feature row differs from its source")
        if (
            type(self.registry_claims) is not tuple
            or len(self.registry_claims) > _MAX_REGISTRY_CLAIMS
            or any(type(claim) is not RegistryClaim for claim in self.registry_claims)
        ):
            raise _error("feature frame registry claim count or type is invalid")
        for claim in self.registry_claims:
            expected = hashlib.sha256(claim.domain + claim.canonical_bytes).digest()
            if (
                claim.full_sha256 != expected
                or claim.hot_id != int.from_bytes(expected[:8], "big")
            ):
                raise _error("feature frame registry claim fingerprint differs")
        if (
            type(self.identity_rows) is not tuple
            or not self.identity_rows
            or len(self.identity_rows) > _MAX_IDENTITY_ROWS
        ):
            raise _error("feature frame identity row count is outside its bound")
        seen_identities: set[tuple[str, bytes]] = set()
        for row in self.identity_rows:
            if type(row) is not tuple or len(row) != 3:
                raise _error("feature frame identity row is malformed")
            table, hot_id, full_sha256 = row
            if table not in _IDENTITY_TABLE_CODES:
                raise _error("feature frame identity table is unsupported")
            if type(hot_id) is not bytes or len(hot_id) != 8:
                raise _error("feature frame identity hot ID is malformed")
            _require_sha256_bytes(full_sha256, "feature frame identity full SHA-256")
            if hot_id != full_sha256[:8]:
                raise _error("feature frame identity row has a false hot ID")
            key = (table, hot_id)
            if key in seen_identities:
                raise _error("feature frame repeats an identity-table key")
            seen_identities.add(key)
        if (
            "feature_ids",
            self.source_feature_sha256[:8],
            self.source_feature_sha256,
        ) not in self.identity_rows:
            raise _error("feature frame omits its exact source identity row")
        if (
            type(self.record_rows) is not tuple
            or not self.record_rows
            or len(self.record_rows) > _MAX_RECORD_ROWS
        ):
            raise _error("feature frame record row count is outside its bound")
        for row in self.record_rows:
            _validate_record_row(
                row,
                expected_feature_hot=self.source_feature_sha256[:8],
                expected_source_type=self.waterway_type,
            )
        expected_posting_bytes = sum(len(row[11]) for row in self.record_rows)
        if self.posting_bytes != expected_posting_bytes:
            raise _error("feature frame posting-byte peak differs from its rows")


class RecordingHotIdRegistry(HotIdRegistry):
    """A normal collision registry that also records every successful call."""

    def __init__(self, digest_function=None) -> None:
        super().__init__(digest_function=digest_function)
        self._claims: list[RegistryClaim] = []

    @property
    def claims(self) -> tuple[RegistryClaim, ...]:
        return tuple(self._claims)

    def register(self, domain: bytes, canonical_bytes: bytes):
        fingerprint = super().register(domain, canonical_bytes)
        self._claims.append(
            RegistryClaim(
                domain=domain,
                canonical_bytes=canonical_bytes,
                full_sha256=fingerprint.full_sha256,
                hot_id=fingerprint.hot_id,
            )
        )
        return fingerprint


def replay_registry_claims(
    registry: HotIdRegistry, claims: Iterable[RegistryClaim]
) -> None:
    if not isinstance(registry, HotIdRegistry):
        raise _error("registry claim replay requires a HotIdRegistry")
    ordered: list[RegistryClaim] = []
    try:
        for claim in claims:
            if len(ordered) >= _MAX_REGISTRY_CLAIMS:
                raise _error("registry claim replay count exceeds its bound")
            ordered.append(claim)
    except TypeError as error:
        raise _error("registry claims must be one finite iterable") from error
    for claim in ordered:
        if type(claim) is not RegistryClaim:
            raise _error("registry claim replay received a malformed claim")
        fingerprint = registry.register(claim.domain, claim.canonical_bytes)
        if (
            fingerprint.full_sha256 != claim.full_sha256
            or fingerprint.hot_id != claim.hot_id
        ):
            raise _error("registry claim fingerprint differs during replay")


class _BufferWriter:
    def __init__(self, maximum_bytes: int, label: str) -> None:
        self._maximum_bytes = maximum_bytes
        self._label = label
        self._data = bytearray()

    def raw(self, value: bytes) -> None:
        if type(value) is not bytes:
            raise _error(f"{self._label} requires immutable bytes")
        if len(self._data) + len(value) > self._maximum_bytes:
            raise _error(f"{self._label} exceeds its canonical byte bound")
        self._data.extend(value)

    def u8(self, value: int, label: str) -> None:
        self.raw(struct.pack("<B", _require_int(value, 0, 0xFF, label)))

    def u32(self, value: int, label: str) -> None:
        self.raw(struct.pack("<I", _require_int(value, 0, 0xFFFFFFFF, label)))

    def u64(self, value: int, label: str) -> None:
        self.raw(struct.pack("<Q", _require_int(value, 0, (1 << 64) - 1, label)))

    def i32(self, value: int, label: str) -> None:
        self.raw(struct.pack("<i", _require_int(value, -(1 << 31), (1 << 31) - 1, label)))

    def boolean(self, value: bool, label: str) -> None:
        if type(value) is not bool:
            raise _error(f"{label} must be a boolean")
        self.u8(int(value), label)

    def blob(self, value: bytes, maximum_bytes: int, label: str) -> None:
        if type(value) is not bytes or len(value) > maximum_bytes:
            raise _error(f"{label} exceeds its canonical byte bound")
        self.u32(len(value), f"{label} byte count")
        self.raw(value)

    def text(self, value: str, maximum_bytes: int, label: str) -> None:
        self.blob(_utf8(value, maximum_bytes, label), maximum_bytes, label)

    def finish(self) -> bytes:
        return bytes(self._data)


class _MemoryReader:
    def __init__(self, data: bytes, maximum_bytes: int, label: str) -> None:
        if type(data) is not bytes or len(data) > maximum_bytes:
            raise _error(f"{label} byte length is outside its bound")
        self._view = memoryview(data)
        self._offset = 0
        self._label = label

    @property
    def remaining(self) -> int:
        return len(self._view) - self._offset

    def take(self, count: int, label: str) -> bytes:
        _require_int(count, 0, len(self._view), f"{label} byte count")
        end = self._offset + count
        if end > len(self._view):
            raise _error(f"{label} is truncated")
        value = bytes(self._view[self._offset : end])
        self._offset = end
        return value

    def u8(self, label: str) -> int:
        return struct.unpack("<B", self.take(1, label))[0]

    def u32(self, label: str) -> int:
        return struct.unpack("<I", self.take(4, label))[0]

    def u64(self, label: str) -> int:
        return struct.unpack("<Q", self.take(8, label))[0]

    def i32(self, label: str) -> int:
        return struct.unpack("<i", self.take(4, label))[0]

    def boolean(self, label: str) -> bool:
        value = self.u8(label)
        if value not in (0, 1):
            raise _error(f"{label} is not a canonical boolean")
        return bool(value)

    def blob(self, maximum_bytes: int, label: str) -> bytes:
        count = self.u32(f"{label} byte count")
        if count > maximum_bytes:
            raise _error(f"{label} byte count exceeds its bound")
        return self.take(count, label)

    def text(self, maximum_bytes: int, label: str) -> str:
        raw = self.blob(maximum_bytes, label)
        try:
            return raw.decode("utf-8", "strict")
        except UnicodeError as error:
            raise _error(f"{label} is not strict UTF-8 text") from error

    def finish(self, trailing_message: str) -> None:
        if self.remaining:
            raise _error(trailing_message)


def _source_kind_code(value: str) -> int:
    try:
        return _SOURCE_KIND_CODES[value]
    except KeyError as error:
        raise _error("exact source kind is unsupported") from error


def _waterway_code(value: str) -> int:
    try:
        return _WATERWAY_CODES[value]
    except KeyError as error:
        raise _error("exact waterway type is unsupported") from error


def _decode_code(values: dict[int, str], code: int, label: str) -> str:
    try:
        return values[code]
    except KeyError as error:
        raise _error(f"{label} code is unsupported") from error


def _encode_exact_feature(feature: ExactWaterwayFeature) -> bytes:
    if type(feature) is not ExactWaterwayFeature:
        raise _error("canonical feature encoder requires ExactWaterwayFeature")
    writer = _BufferWriter(_MAX_FEATURE_BYTES, "exact waterway feature")
    writer.u8(_source_kind_code(feature.source_kind), "exact source kind")
    writer.u64(feature.source_id, "exact source ID")
    writer.u64(feature.source_version, "exact source version")
    writer.text(
        feature.source_timestamp, _MAX_TIMESTAMP_BYTES, "exact source timestamp"
    )
    writer.u8(_waterway_code(feature.waterway_type), "exact waterway type")
    writer.text(feature.name_source_key, _MAX_SOURCE_KEY_BYTES, "exact source key")
    writer.text(feature.primary_name, _MAX_SOURCE_TEXT_BYTES, "exact primary name")
    writer.boolean(feature.english_name is not None, "exact English-name presence")
    if feature.english_name is not None:
        writer.text(
            feature.english_name, _MAX_SOURCE_TEXT_BYTES, "exact English name"
        )
    writer.boolean(
        feature.complete_named_relation, "exact complete-relation evidence"
    )
    point_count = _feature_point_count(feature)
    _require_int(point_count, 2, _MAX_FEATURE_POINTS, "exact feature point count")
    writer.u32(point_count, "exact feature point count")
    writer.u32(len(feature.parts), "exact feature part count")
    for part_index, part in enumerate(feature.parts):
        writer.u32(len(part), f"exact feature part {part_index} point count")
        for point in part:
            writer.u64(point.node_id, "exact point node ID")
            writer.i32(point.longitude_e7, "exact point longitude E7")
            writer.i32(point.latitude_e7, "exact point latitude E7")
    required = tuple(sorted(feature.required_node_ids))
    writer.u32(len(required), "exact required-node count")
    for node_id in required:
        writer.u64(node_id, "exact required-node ID")
    writer.raw(
        _require_sha256_bytes(
            feature.source_feature_sha256, "exact source feature identity"
        )
    )
    return writer.finish()


def _decode_exact_feature(data: bytes) -> ExactWaterwayFeature:
    reader = _MemoryReader(data, _MAX_FEATURE_BYTES, "exact waterway feature")
    source_kind = _decode_code(
        _SOURCE_KINDS_BY_CODE, reader.u8("exact source kind"), "exact source kind"
    )
    source_id = _require_int(
        reader.u64("exact source ID"), 1, (1 << 63) - 1, "exact source ID"
    )
    source_version = _require_int(
        reader.u64("exact source version"),
        1,
        (1 << 63) - 1,
        "exact source version",
    )
    source_timestamp = reader.text(
        _MAX_TIMESTAMP_BYTES, "exact source timestamp"
    )
    waterway_type = _decode_code(
        _WATERWAYS_BY_CODE,
        reader.u8("exact waterway type"),
        "exact waterway type",
    )
    name_source_key = reader.text(_MAX_SOURCE_KEY_BYTES, "exact source key")
    primary_name = reader.text(_MAX_SOURCE_TEXT_BYTES, "exact primary name")
    english_name = (
        reader.text(_MAX_SOURCE_TEXT_BYTES, "exact English name")
        if reader.boolean("exact English-name presence")
        else None
    )
    complete_named_relation = reader.boolean("exact complete-relation evidence")
    point_count = reader.u32("exact feature point count")
    if not 2 <= point_count <= _MAX_FEATURE_POINTS:
        raise _error("exact feature point count is outside its bound")
    part_count = reader.u32("exact feature part count")
    if not 1 <= part_count <= min(_MAX_FEATURE_PARTS, point_count // 2):
        raise _error("exact feature part count is outside its bound")
    parts: list[tuple[ExactWaterwayPoint, ...]] = []
    points_read = 0
    for part_index in range(part_count):
        count = reader.u32(f"exact feature part {part_index} point count")
        if count < 2 or count > point_count - points_read:
            raise _error("exact feature part point count is outside its bound")
        part = tuple(
            ExactWaterwayPoint(
                reader.u64("exact point node ID"),
                reader.i32("exact point longitude E7"),
                reader.i32("exact point latitude E7"),
            )
            for _ in range(count)
        )
        parts.append(part)
        points_read += count
    if points_read != point_count:
        raise _error("exact feature point count does not reconcile with its parts")
    required_count = reader.u32("exact required-node count")
    if required_count > point_count:
        raise _error("exact required-node count exceeds the feature point count")
    required_node_ids = tuple(
        reader.u64("exact required-node ID") for _ in range(required_count)
    )
    if tuple(sorted(set(required_node_ids))) != required_node_ids:
        raise _error("exact required-node IDs are not unique canonical order")
    source_feature_sha256 = reader.take(32, "exact source feature identity")
    reader.finish("exact waterway feature has trailing bytes")
    return ExactWaterwayFeature(
        source_kind=source_kind,
        source_id=source_id,
        source_version=source_version,
        source_timestamp=source_timestamp,
        waterway_type=waterway_type,
        name_source_key=name_source_key,
        primary_name=primary_name,
        english_name=english_name,
        complete_named_relation=complete_named_relation,
        parts=tuple(parts),
        required_node_ids=frozenset(required_node_ids),
        source_feature_sha256=source_feature_sha256,
    )


def _source_frame_sha256(feature_bytes: bytes) -> bytes:
    return hashlib.sha256(_SOURCE_FRAME_DOMAIN + feature_bytes).digest()


def _source_range_sha256(features: Sequence[ExactWaterwayFeature]) -> str:
    digest = hashlib.sha256(_SOURCE_RANGE_DOMAIN)
    for feature in features:
        encoded = _encode_exact_feature(feature)
        digest.update(struct.pack("<Q", len(encoded)))
        digest.update(encoded)
    return digest.hexdigest()


def encode_feature_batch_job(job: FeatureRenderBatchJob) -> bytes:
    if type(job) is not FeatureRenderBatchJob:
        raise _error("feature batch job encoder requires FeatureRenderBatchJob")
    writer = _BufferWriter(_MAX_JOB_BYTES, "feature batch job")
    writer.raw(_JOB_MAGIC)
    writer.u8(_JOB_VERSION, "feature batch job version")
    writer.u64(job.start_ordinal, "feature batch start ordinal")
    writer.u32(len(job.features), "feature batch feature count")
    for feature in job.features:
        writer.blob(
            _encode_exact_feature(feature),
            _MAX_FEATURE_BYTES,
            "feature batch exact feature",
        )
    writer.raw(bytes.fromhex(job.source_generation_sha256))
    writer.raw(bytes.fromhex(job.classifier_sha256))
    writer.u8(len(job.zooms), "feature batch zoom count")
    for zoom in job.zooms:
        writer.u8(zoom, "feature batch zoom")
    writer.raw(bytes.fromhex(job.render_run_identity_sha256))
    writer.text(job.package_id, 1_024, "feature batch package ID")
    writer.raw(bytes.fromhex(job.spool_owner_sha256))
    writer.text(job.spool_directory, _MAX_PATH_BYTES, "feature batch spool directory")
    writer.u64(job.spool_byte_quota, "feature batch spool byte quota")
    return writer.finish()


def decode_feature_batch_job(data: bytes) -> FeatureRenderBatchJob:
    reader = _MemoryReader(data, _MAX_JOB_BYTES, "feature batch job")
    if reader.take(len(_JOB_MAGIC), "feature batch job magic") != _JOB_MAGIC:
        raise _error("feature batch job magic is unsupported")
    if reader.u8("feature batch job version") != _JOB_VERSION:
        raise _error("feature batch job version is unsupported")
    start_ordinal = _require_int(
        reader.u64("feature batch start ordinal"),
        0,
        _MAX_FILE_ORDINAL,
        "feature batch start ordinal",
    )
    feature_count = reader.u32("feature batch feature count")
    if not 1 <= feature_count <= _MAX_BATCH_FEATURES:
        raise _error("feature batch feature count is outside its bound")
    end_ordinal = start_ordinal + feature_count
    if end_ordinal > _MAX_FILE_ORDINAL:
        raise _error("feature batch end ordinal is outside its file-name bound")
    if (
        start_ordinal // _RENDER_CHECKPOINT_FEATURES
        != (end_ordinal - 1) // _RENDER_CHECKPOINT_FEATURES
    ):
        raise _error("feature batch cannot cross a render checkpoint boundary")
    features = tuple(
        _decode_exact_feature(
            reader.blob(_MAX_FEATURE_BYTES, "feature batch exact feature")
        )
        for _ in range(feature_count)
    )
    source_generation_sha256 = reader.take(
        32, "feature batch source generation identity"
    ).hex()
    classifier_sha256 = reader.take(32, "feature batch classifier identity").hex()
    zoom_count = reader.u8("feature batch zoom count")
    if not 1 <= zoom_count <= 30:
        raise _error("feature batch zoom count is outside its bound")
    zooms = tuple(reader.u8("feature batch zoom") for _ in range(zoom_count))
    render_run_identity_sha256 = reader.take(
        32, "feature batch render run identity"
    ).hex()
    package_id = reader.text(1_024, "feature batch package ID")
    spool_owner_sha256 = reader.take(
        32, "feature batch spool owner identity"
    ).hex()
    spool_directory = reader.text(
        _MAX_PATH_BYTES, "feature batch spool directory"
    )
    spool_byte_quota = reader.u64("feature batch spool byte quota")
    reader.finish("feature batch job has trailing bytes")
    return FeatureRenderBatchJob(
        start_ordinal=start_ordinal,
        features=features,
        source_generation_sha256=source_generation_sha256,
        classifier_sha256=classifier_sha256,
        zooms=zooms,
        render_run_identity_sha256=render_run_identity_sha256,
        package_id=package_id,
        spool_owner_sha256=spool_owner_sha256,
        spool_directory=spool_directory,
        spool_byte_quota=spool_byte_quota,
    )


def _validate_record_row(
    row: tuple[object, ...],
    *,
    expected_feature_hot: bytes,
    expected_source_type: str,
) -> None:
    if type(row) is not tuple or len(row) != 14:
        raise _error("feature frame record SQL row is malformed")
    z = _require_int(row[0], 0, 29, "record zoom")
    y = _require_int(row[1], 0, (1 << z) - 1, "record tile y")
    x = _require_int(row[2], 0, (1 << z) - 1, "record tile x")
    TileKey(z, x, y)
    posting_key = row[3]
    if type(posting_key) is not bytes or len(posting_key) != 28:
        raise _error("feature frame posting key is malformed")
    feature_id, variant_id, _owner_tile, _world_wrap = struct.unpack(
        ">QQQi", posting_key
    )
    _require_int(row[4], -(1 << 31), (1 << 31) - 1, "record draw order")
    _require_int(row[5], -(1 << 31), (1 << 31) - 1, "record priority")
    _require_int(row[6], 0, 0xFF, "record layer group")
    _require_int(row[7], 0, 0xFF, "record feature kind")
    variant_hot = row[8]
    if type(variant_hot) is not bytes or len(variant_hot) != 8:
        raise _error("feature frame record variant identity is malformed")
    feature_hot = row[9]
    if type(feature_hot) is not bytes or len(feature_hot) != 8:
        raise _error("feature frame record feature identity is malformed")
    if feature_hot != expected_feature_hot or feature_id.to_bytes(8, "big") != feature_hot:
        raise _error("feature frame record differs from its exact source identity")
    if variant_id.to_bytes(8, "big") != variant_hot:
        raise _error("feature frame posting key differs from its variant identity")
    _require_sha256_bytes(row[10], "feature frame sourced-text identity")
    envelope = row[11]
    if (
        type(envelope) is not bytes
        or not envelope
        or len(envelope) > _MAX_RECORD_ENVELOPE_BYTES
    ):
        raise _error("feature frame record envelope length is outside its bound")
    _require_int(row[12], -(1 << 31), (1 << 31) - 1, "record semantic subtype")
    if row[13] != expected_source_type:
        raise _error("feature frame record source type differs from its exact source")


def _encode_record_row(writer: _BufferWriter, row: tuple[object, ...]) -> None:
    _validate_record_row(
        row,
        expected_feature_hot=row[9],
        expected_source_type=row[13],
    )
    writer.u8(row[0], "record zoom")
    writer.u32(row[1], "record tile y")
    writer.u32(row[2], "record tile x")
    writer.raw(row[3])
    writer.i32(row[4], "record draw order")
    writer.i32(row[5], "record priority")
    writer.u8(row[6], "record layer group")
    writer.u8(row[7], "record feature kind")
    writer.raw(row[8])
    writer.raw(row[9])
    writer.raw(row[10])
    writer.blob(row[11], _MAX_RECORD_ENVELOPE_BYTES, "record envelope")
    writer.i32(row[12], "record semantic subtype")
    writer.u8(_waterway_code(row[13]), "record source type")


def _decode_record_row(reader) -> tuple[object, ...]:
    z = _require_int(reader.u8("record zoom"), 0, 29, "record zoom")
    y = _require_int(reader.u32("record tile y"), 0, (1 << z) - 1, "record tile y")
    x = _require_int(reader.u32("record tile x"), 0, (1 << z) - 1, "record tile x")
    TileKey(z, x, y)
    posting_key = reader.take(28, "record posting key")
    draw_order = reader.i32("record draw order")
    priority = reader.i32("record priority")
    layer_group = reader.u8("record layer group")
    feature_kind = reader.u8("record feature kind")
    variant_identity = reader.take(8, "record variant identity")
    feature_identity = reader.take(8, "record feature identity")
    sourced_identity = reader.take(32, "record sourced-text identity")
    envelope = reader.blob(_MAX_RECORD_ENVELOPE_BYTES, "record envelope")
    subtype = reader.i32("record semantic subtype")
    source_type = _decode_code(
        _WATERWAYS_BY_CODE,
        reader.u8("record source type"),
        "record source type",
    )
    row = (
        z,
        y,
        x,
        posting_key,
        draw_order,
        priority,
        layer_group,
        feature_kind,
        variant_identity,
        feature_identity,
        sourced_identity,
        envelope,
        subtype,
        source_type,
    )
    _validate_record_row(
        row,
        expected_feature_hot=row[9],
        expected_source_type=row[13],
    )
    return row


def _encode_feature_render_frame(
    frame: FeatureRenderFrame, maximum_bytes: int
) -> bytes:
    if type(frame) is not FeatureRenderFrame:
        raise _error("feature frame encoder requires FeatureRenderFrame")
    writer = _BufferWriter(maximum_bytes, "feature render frame")
    writer.u64(frame.ordinal, "feature frame ordinal")
    writer.u8(_source_kind_code(frame.source_kind), "feature frame source kind")
    writer.u64(frame.source_id, "feature frame source ID")
    writer.u64(frame.source_version, "feature frame source version")
    writer.text(
        frame.source_timestamp, _MAX_TIMESTAMP_BYTES, "feature frame source timestamp"
    )
    writer.u8(_waterway_code(frame.waterway_type), "feature frame waterway type")
    writer.raw(frame.source_feature_sha256)
    writer.raw(frame.source_frame_sha256)
    writer.u32(frame.part_count, "feature frame part count")
    writer.u32(frame.point_count, "feature frame point count")
    writer.u32(frame.required_node_count, "feature frame required-node count")
    writer.u32(
        len(frame.geometry_source_witnesses),
        "feature frame geometry witness count",
    )
    for zoom, parts in frame.geometry_source_witnesses:
        writer.u8(zoom, "feature frame geometry witness zoom")
        for indices in parts:
            writer.u32(len(indices), "feature frame geometry witness point count")
            for index in indices:
                writer.u32(index, "feature frame geometry source index")
    rendered = frame.rendered_feature_row
    writer.u64(rendered[0], "rendered-feature ordinal")
    writer.u8(_source_kind_code(rendered[1]), "rendered-feature source kind")
    writer.u64(rendered[2], "rendered-feature source ID")
    writer.u8(_waterway_code(rendered[3]), "rendered-feature source type")
    writer.raw(rendered[4])
    writer.u32(len(frame.registry_claims), "feature frame registry claim count")
    for claim in frame.registry_claims:
        writer.blob(
            claim.domain, _MAX_REGISTRY_DOMAIN_BYTES, "registry claim domain"
        )
        writer.blob(
            claim.canonical_bytes,
            _MAX_REGISTRY_CANONICAL_BYTES,
            "registry claim canonical bytes",
        )
        writer.raw(claim.full_sha256)
        writer.u64(claim.hot_id, "registry claim hot identity")
    writer.u32(len(frame.identity_rows), "feature frame identity row count")
    for table, hot_id, full_sha256 in frame.identity_rows:
        writer.u8(_IDENTITY_TABLE_CODES[table], "feature frame identity table")
        writer.raw(hot_id)
        writer.raw(full_sha256)
    writer.u32(len(frame.record_rows), "feature frame record row count")
    for row in frame.record_rows:
        _encode_record_row(writer, row)
    writer.u64(frame.posting_bytes, "feature frame posting-byte peak")
    return writer.finish()


def _decode_feature_render_frame(reader) -> FeatureRenderFrame:
    ordinal = _require_int(
        reader.u64("feature frame ordinal"),
        0,
        _MAX_FILE_ORDINAL,
        "feature frame ordinal",
    )
    source_kind = _decode_code(
        _SOURCE_KINDS_BY_CODE,
        reader.u8("feature frame source kind"),
        "feature frame source kind",
    )
    source_id = _require_int(
        reader.u64("feature frame source ID"),
        1,
        (1 << 63) - 1,
        "feature frame source ID",
    )
    source_version = _require_int(
        reader.u64("feature frame source version"),
        1,
        (1 << 63) - 1,
        "feature frame source version",
    )
    source_timestamp = reader.text(
        _MAX_TIMESTAMP_BYTES, "feature frame source timestamp"
    )
    waterway_type = _decode_code(
        _WATERWAYS_BY_CODE,
        reader.u8("feature frame waterway type"),
        "feature frame waterway type",
    )
    source_feature_sha256 = reader.take(32, "feature frame exact source identity")
    source_frame_sha256 = reader.take(32, "feature frame canonical source identity")
    part_count = reader.u32("feature frame part count")
    if not 1 <= part_count <= _MAX_FEATURE_PARTS:
        raise _error("feature frame part count is outside its bound")
    point_count = reader.u32("feature frame point count")
    if not 2 <= point_count <= _MAX_FEATURE_POINTS:
        raise _error("feature frame point count is outside its bound")
    required_node_count = reader.u32("feature frame required-node count")
    if required_node_count > point_count:
        raise _error("feature frame required-node count exceeds its point count")
    witness_count = reader.u32("feature frame geometry witness count")
    if not 1 <= witness_count <= 30:
        raise _error("feature frame geometry witness count is outside its bound")
    geometry_source_witnesses: list[
        tuple[int, tuple[tuple[int, ...], ...]]
    ] = []
    for _ in range(witness_count):
        zoom = reader.u8("feature frame geometry witness zoom")
        parts: list[tuple[int, ...]] = []
        selected_points = 0
        for _part in range(part_count):
            selected_count = reader.u32(
                "feature frame geometry witness point count"
            )
            if not 2 <= selected_count <= point_count - selected_points:
                raise _error(
                    "feature frame geometry witness point count is outside its bound"
                )
            indices = tuple(
                reader.u32("feature frame geometry source index")
                for _index in range(selected_count)
            )
            parts.append(indices)
            selected_points += selected_count
        geometry_source_witnesses.append((zoom, tuple(parts)))
    rendered_feature_row = (
        reader.u64("rendered-feature ordinal"),
        _decode_code(
            _SOURCE_KINDS_BY_CODE,
            reader.u8("rendered-feature source kind"),
            "rendered-feature source kind",
        ),
        reader.u64("rendered-feature source ID"),
        _decode_code(
            _WATERWAYS_BY_CODE,
            reader.u8("rendered-feature source type"),
            "rendered-feature source type",
        ),
        reader.take(32, "rendered-feature exact source identity"),
    )
    claim_count = reader.u32("feature frame registry claim count")
    if claim_count > _MAX_REGISTRY_CLAIMS:
        raise _error("feature frame registry claim count exceeds its bound")
    claims = tuple(
        RegistryClaim(
            domain=reader.blob(
                _MAX_REGISTRY_DOMAIN_BYTES, "registry claim domain"
            ),
            canonical_bytes=reader.blob(
                _MAX_REGISTRY_CANONICAL_BYTES,
                "registry claim canonical bytes",
            ),
            full_sha256=reader.take(32, "registry claim full identity"),
            hot_id=reader.u64("registry claim hot identity"),
        )
        for _ in range(claim_count)
    )
    identity_count = reader.u32("feature frame identity row count")
    if not 1 <= identity_count <= _MAX_IDENTITY_ROWS:
        raise _error("feature frame identity row count is outside its bound")
    identity_rows: list[tuple[str, bytes, bytes]] = []
    for _ in range(identity_count):
        table = _decode_code(
            _IDENTITY_TABLES_BY_CODE,
            reader.u8("feature frame identity table"),
            "feature frame identity table",
        )
        identity_rows.append(
            (
                table,
                reader.take(8, "feature frame identity hot ID"),
                reader.take(32, "feature frame identity full SHA-256"),
            )
        )
    record_count = reader.u32("feature frame record row count")
    if not 1 <= record_count <= _MAX_RECORD_ROWS:
        raise _error("feature frame record row count is outside its bound")
    record_rows = tuple(_decode_record_row(reader) for _ in range(record_count))
    posting_bytes = reader.u64("feature frame posting-byte peak")
    return FeatureRenderFrame(
        ordinal=ordinal,
        source_kind=source_kind,
        source_id=source_id,
        source_version=source_version,
        source_timestamp=source_timestamp,
        waterway_type=waterway_type,
        source_feature_sha256=source_feature_sha256,
        source_frame_sha256=source_frame_sha256,
        part_count=part_count,
        point_count=point_count,
        required_node_count=required_node_count,
        geometry_source_witnesses=tuple(geometry_source_witnesses),
        rendered_feature_row=rendered_feature_row,
        registry_claims=claims,
        identity_rows=tuple(identity_rows),
        record_rows=record_rows,
        posting_bytes=posting_bytes,
    )


def validate_frame_against_exact_source(
    ordinal: int,
    exact: ExactWaterwayFeature,
    frame: FeatureRenderFrame,
) -> None:
    """Bind one decoded worker frame to the parent-held exact source value."""

    _require_int(ordinal, 0, _MAX_FILE_ORDINAL, "feature frame parent ordinal")
    if type(exact) is not ExactWaterwayFeature:
        raise _error("feature frame validation requires one exact source feature")
    if type(frame) is not FeatureRenderFrame:
        raise _error("feature frame validation requires one decoded frame")
    encoded_exact = _encode_exact_feature(exact)
    expected_source = (
        ordinal,
        exact.source_kind,
        exact.source_id,
        exact.source_version,
        exact.source_timestamp,
        exact.waterway_type,
        exact.source_feature_sha256,
        _source_frame_sha256(encoded_exact),
        len(exact.parts),
        _feature_point_count(exact),
        len(exact.required_node_ids),
    )
    actual_source = (
        frame.ordinal,
        frame.source_kind,
        frame.source_id,
        frame.source_version,
        frame.source_timestamp,
        frame.waterway_type,
        frame.source_feature_sha256,
        frame.source_frame_sha256,
        frame.part_count,
        frame.point_count,
        frame.required_node_count,
    )
    if actual_source != expected_source:
        raise _error("feature frame differs from its parent-held exact source")
    expected_rendered = (
        ordinal,
        exact.source_kind,
        exact.source_id,
        exact.waterway_type,
        exact.source_feature_sha256,
    )
    if frame.rendered_feature_row != expected_rendered:
        raise _error("feature frame rendered-feature row differs from its exact source")


def _decode_parent_record_envelope(
    envelope: bytes,
    tile: TileKey,
):
    reader = _MemoryReader(
        envelope,
        _MAX_RECORD_ENVELOPE_BYTES,
        "feature frame record envelope",
    )
    renderer_bytes = reader.blob(
        MAX_RENDERER_RECORD_BYTES,
        "feature frame renderer record",
    )
    if not renderer_bytes:
        raise _error("feature frame renderer record is empty")
    sourced_byte_count = reader.u32("feature frame sourced-text byte count")
    if not 0 < sourced_byte_count <= MAX_SOURCED_TEXT_RECORD_BYTES:
        raise _error("feature frame sourced-text byte count is outside its bound")
    sourced_sha256 = reader.take(32, "feature frame sourced-text identity")
    sourced_bytes = reader.take(
        sourced_byte_count,
        "feature frame sourced-text record",
    )
    reader.finish("feature frame record envelope has trailing bytes")
    try:
        renderer = _decode_renderer_record(tile, renderer_bytes)
        sourced = _decode_sourced_text(sourced_bytes, sourced_sha256)
    except ValueError as error:
        raise _error("feature frame record envelope is not canonical") from error
    return renderer, sourced


def _remember_validated_identity(
    identities: dict[tuple[str, bytes], bytes],
    table: str,
    hot_id: bytes,
    full_sha256: bytes,
) -> None:
    previous = identities.setdefault((table, hot_id), full_sha256)
    if previous != full_sha256:
        raise _error("fatal 64-bit identity collision between unequal canonical values")


def _normalized_validation_zooms(zooms: tuple[int, ...]) -> tuple[int, ...]:
    if (
        type(zooms) is not tuple
        or not zooms
        or len(set(zooms)) != len(zooms)
        or any(type(zoom) is not int or not 0 <= zoom <= 29 for zoom in zooms)
    ):
        raise _error("feature frame expected zooms are malformed")
    return zooms


def _geometry_world_parts(
    geometry: RendererGeometry,
) -> tuple[tuple[tuple[int, int], ...], ...]:
    if not isinstance(geometry, RendererGeometry):
        raise _error("feature frame geometry proof requires renderer geometry")
    denominator = geometry.world_denominator
    if _WORLD_DENOMINATOR % denominator:
        raise _error("feature frame geometry is not derived from exact world coordinates")
    multiplier = _WORLD_DENOMINATOR // denominator
    flat = tuple(
        (
            geometry.world_coordinate_numerators[index] * multiplier,
            geometry.world_coordinate_numerators[index + 1] * multiplier,
        )
        for index in range(0, len(geometry.world_coordinate_numerators), 2)
    )
    ends = geometry.parts[1:] + (len(flat),)
    return tuple(flat[start:end] for start, end in zip(geometry.parts, ends))


def _validate_exact_geometry_selection(
    exact: ExactWaterwayFeature,
    projected_parts: tuple[tuple[tuple[int, int], ...], ...],
    geometry: RendererGeometry,
    source_indices_by_part: tuple[tuple[int, ...], ...],
    *,
    zoom: int,
) -> None:
    selected_parts = _geometry_world_parts(geometry)
    if (
        len(selected_parts) != len(projected_parts)
        or len(source_indices_by_part) != len(projected_parts)
    ):
        raise _error("feature frame geometry part count differs from exact source")
    pixel_denominator = (1 << zoom) * 512
    tolerance = max(
        1,
        (_WORLD_DENOMINATOR + pixel_denominator - 1) // pixel_denominator,
    )
    tolerance_squared = tolerance * tolerance
    for exact_part, projected, selected, indices in zip(
        exact.parts,
        projected_parts,
        selected_parts,
        source_indices_by_part,
    ):
        required_indices = frozenset(
            index
            for index, point in enumerate(exact_part)
            if point.node_id in exact.required_node_ids
        )
        final_source_index = len(projected) - 1
        if (
            type(indices) is not tuple
            or len(indices) != len(selected)
            or indices[0] != 0
            or indices[-1] != final_source_index
            or any(
                type(index) is not int or not 0 <= index <= final_source_index
                for index in indices
            )
            or any(
                first >= second
                for first, second in zip(indices, indices[1:])
            )
        ):
            raise _error("feature frame geometry source-index witness is malformed")
        if not required_indices.issubset(indices):
            raise _error("feature frame geometry omits an exact required source node")
        if any(
            selected_coordinate != projected[index]
            for selected_coordinate, index in zip(selected, indices)
        ):
            raise _error("feature frame geometry is not derived from exact source points")
        for start, end in zip(indices, indices[1:]):
            first_x, first_y = projected[start]
            last_x, last_y = projected[end]
            delta_x = last_x - first_x
            delta_y = last_y - first_y
            segment_squared = delta_x * delta_x + delta_y * delta_y
            for x, y in projected[start + 1 : end]:
                if segment_squared:
                    cross = abs(
                        delta_x * (first_y - y)
                        - (first_x - x) * delta_y
                    )
                    if cross * cross > tolerance_squared * segment_squared:
                        raise _error(
                            "feature frame geometry exceeds the exact half-pixel omission bound"
                        )
                elif (x - first_x) ** 2 + (y - first_y) ** 2 > tolerance_squared:
                    raise _error(
                        "feature frame geometry exceeds the exact half-pixel omission bound"
                    )


def validate_and_decode_record_rows(
    exact: ExactWaterwayFeature,
    frame: FeatureRenderFrame,
    *,
    source_generation_sha256: str,
    classifier_sha256: str,
    expected_zooms: tuple[int, ...],
) -> tuple[tuple[object, ...], ...]:
    """Decode every envelope and prove its SQL/identity relationships."""

    if type(exact) is not ExactWaterwayFeature:
        raise _error("feature frame row validation requires one exact source feature")
    if type(frame) is not FeatureRenderFrame:
        raise _error("feature frame row validation requires one decoded frame")
    _require_sha256_text(
        source_generation_sha256,
        "feature frame source generation SHA-256",
    )
    _require_sha256_text(
        classifier_sha256,
        "feature frame classifier SHA-256",
    )
    normalized_zooms = _normalized_validation_zooms(expected_zooms)
    expected_registry = RecordingHotIdRegistry()
    primary_field_id = _u64_identity(
        "openstreetmap.tag." + exact.name_source_key,
        expected_registry,
    )
    english_field_id = (
        _u64_identity("openstreetmap.tag.name:en", expected_registry)
        if exact.english_name is not None
        else None
    )
    evidence_context = SourceEvidenceContext(
        source_generation_sha256=source_generation_sha256,
        classifier_sha256=classifier_sha256,
        source_field_id=primary_field_id,
    )
    complete_length_m = _great_circle_length_m(exact.parts)
    subtype = _SUBTYPE_BY_WATERWAY[exact.waterway_type]
    facts = LabelFacts(
        subtype=subtype,
        evidence_context=evidence_context,
        complete_named_relation=exact.complete_named_relation,
        complete_relation_length_m=(
            complete_length_m if exact.complete_named_relation else None
        ),
    )
    decision = prominence_decision_for_label(facts)
    visibility = visibility_rule_for_label(facts)
    active_zooms = tuple(
        zoom
        for zoom in normalized_zooms
        if (zoom + 1) * 100 > visibility.min_zoom_centi
    )
    if not active_zooms:
        raise _error("feature frame exact source is invisible at every requested zoom")
    projected_parts = tuple(
        _unwrapped_world_points(part)
        for part in exact.parts
    )
    witnesses_by_zoom = dict(frame.geometry_source_witnesses)
    feature_hot = exact.source_feature_sha256[:8]
    feature_id = int.from_bytes(feature_hot, "big")
    try:
        expected_sourced = create_sourced_map_text(
            primary=exact.primary_name,
            primary_source_field_id=primary_field_id,
            declared_english=exact.english_name,
            english_source_field_id=english_field_id,
        )
    except ValueError as error:
        raise _error("feature frame exact sourced text cannot be reconstructed") from error
    expected_subtype = subtype.value
    expected_identities: dict[tuple[str, bytes], bytes] = {}
    _remember_validated_identity(
        expected_identities,
        "feature_ids",
        feature_hot,
        exact.source_feature_sha256,
    )
    validated_rows: list[tuple[object, ...]] = []
    variants_by_zoom: dict[int, object] = {}
    actual_postings: list[tuple[TileKey, int, int, TileKey, int]] = []
    for row in frame.record_rows:
        _validate_record_row(
            row,
            expected_feature_hot=feature_hot,
            expected_source_type=exact.waterway_type,
        )
        tile = TileKey(int(row[0]), int(row[2]), int(row[1]))
        renderer, sourced = _decode_parent_record_envelope(row[11], tile)
        posting = renderer.posting
        variant = renderer.variant
        placement = variant.placement
        posting_key = struct.pack(
            ">QQQi",
            posting.feature_id,
            posting.canonical_variant_id,
            posting.owner_tile.packed,
            posting.world_wrap,
        )
        if posting.requested_tile != tile or bytes(row[3]) != posting_key:
            raise _error("feature frame record tile or posting envelope differs")
        if (
            posting.feature_id != feature_id
            or variant.dedupe_id != feature_id
            or bytes(row[9]) != feature_hot
            or placement.source_feature_sha256 != exact.source_feature_sha256
            or placement.placement_source_feature_id != feature_id
            or placement.provider_feature_id != exact.source_id
        ):
            raise _error("feature frame record differs from its exact source identity")
        variant_hot = variant.canonical_variant_id.to_bytes(8, "big")
        if (
            bytes(row[8]) != variant_hot
            or int(row[4]) != variant.draw_order
            or int(row[5]) != variant.priority
            or int(row[6]) != variant.layer_group.value
            or int(row[7]) != variant.feature_kind.value
            or int(row[12]) != variant.semantic_subtype
            or variant.semantic_subtype != expected_subtype
            or variant.text != exact.primary_name
            or placement.text_source_field_id != primary_field_id
        ):
            raise _error("feature frame record variant differs from its exact label")
        if (
            sourced.canonical_bytes != expected_sourced.canonical_bytes
            or sourced.full_sha256 != expected_sourced.full_sha256
            or bytes(row[10]) != sourced.full_sha256
        ):
            raise _error("feature frame sourced text differs from its exact source")
        zoom = placement.source_zoom
        if posting.requested_tile.z != zoom:
            raise _error("feature frame posting zoom differs from its label geometry")
        previous_variant = variants_by_zoom.setdefault(zoom, variant)
        if previous_variant != variant:
            raise _error("feature frame contains unequal variants at one zoom")
        actual_postings.append(
            (
                posting.requested_tile,
                posting.feature_id,
                posting.canonical_variant_id,
                posting.owner_tile,
                posting.world_wrap,
            )
        )
        geometry_hot = variant.geometry_id.to_bytes(8, "big")
        label_hot = placement.label_candidate_id.to_bytes(8, "big")
        sourced_hot = sourced.full_sha256[:8]
        _remember_validated_identity(
            expected_identities,
            "geometry_ids",
            geometry_hot,
            placement.placement_geometry_sha256,
        )
        _remember_validated_identity(
            expected_identities,
            "label_ids",
            label_hot,
            placement.label_candidate_sha256,
        )
        _remember_validated_identity(
            expected_identities,
            "variant_ids",
            variant_hot,
            variant_fingerprint(variant).full_sha256,
        )
        _remember_validated_identity(
            expected_identities,
            "sourced_ids",
            sourced_hot,
            sourced.full_sha256,
        )
        validated_rows.append(row)
    if set(variants_by_zoom) != set(active_zooms):
        raise _error("feature frame posting zooms differ from the exact source")
    if set(witnesses_by_zoom) != set(active_zooms):
        raise _error("feature frame geometry witnesses differ from requested zooms")
    expected_postings_by_tile: dict[
        TileKey,
        list[tuple[TileKey, int, int, TileKey, int]],
    ] = {}
    for zoom in active_zooms:
        actual_variant = variants_by_zoom[zoom]
        geometry = actual_variant.geometry
        _validate_exact_geometry_selection(
            exact,
            projected_parts,
            geometry,
            witnesses_by_zoom[zoom],
            zoom=zoom,
        )
        geometry_identity = renderer_geometry_fingerprint(geometry)
        candidates = _candidate_tiles(geometry, zoom)
        owner_tile, owner_wrap = min(
            candidates,
            key=lambda item: (item[0].packed, item[1]),
        )
        scale = 1 << zoom
        owner_raw_x = owner_tile.x + owner_wrap * scale
        edge_domain = (
            geometry.bounds_numerators[0] * scale
            - owner_raw_x * geometry.world_denominator,
            geometry.bounds_numerators[1] * scale
            - owner_tile.y * geometry.world_denominator,
            geometry.bounds_numerators[2] * scale
            - owner_raw_x * geometry.world_denominator,
            geometry.bounds_numerators[3] * scale
            - owner_tile.y * geometry.world_denominator,
        )
        expected_placement = make_normalized_placement(
            text=exact.primary_name,
            source_feature_sha256=exact.source_feature_sha256,
            placement_geometry_sha256=geometry_identity.full_sha256,
            text_evidence_kind=TextEvidenceKind.SOURCE_FIELD,
            text_source_field_id=primary_field_id,
            placement_source_feature_id=feature_id,
            placement_geometry_id=geometry_identity.hot_id,
            source_tile=owner_tile,
            source_zoom=zoom,
            source_declared_extent=geometry.world_denominator,
            source_edge_domain=edge_domain,
            placement_source_kind=(
                PlacementSourceKind.EXACT_PARENT_PATH
                if exact.complete_named_relation
                else PlacementSourceKind.DIRECT_SOURCE_PATH
            ),
            display_min_zoom_centi=visibility.min_zoom_centi,
            display_max_zoom_centi=LABEL_DISPLAY_MAX_ZOOM_CENTI,
            spacing_px=LINE_LABEL_REPEAT_SPACING_PX,
            max_angle_degrees=visibility.max_bend_centi_degrees // 100,
            collision_group=REFERENCE_LABEL_COLLISION_GROUP,
            semantic_priority=decision.semantic_priority,
            prominence_tier=RendererProminenceTier[decision.tier.name],
            provider_rank=decision.provider_rank,
            complete_geometry_measure_bucket=(
                decision.complete_geometry_measure_bucket
            ),
            prominence_rule_id=decision.prominence_rule_id,
            prominence_decision_sha256=bytes.fromhex(
                prominence_decision_sha256(decision)
            ),
            avoid_edges=True,
            keep_upright=True,
            active_band_limit=LABEL_ACTIVE_BAND_LIMIT,
            style_policy_sha256=bytes.fromhex(PRESENTATION_POLICY_SHA256),
            provider_feature_id=exact.source_id,
            identity_registry=expected_registry,
        )
        expected_variant = make_canonical_variant(
            dedupe_id=feature_id,
            geometry_id=geometry_identity.hot_id,
            source_layer_id=_u64_identity(
                f"openstreetmap.{exact.source_kind}.waterway",
                expected_registry,
            ),
            source_scale_band_id=_u64_identity(
                f"openstreetmap.waterway.adaptive-half-pixel.z{zoom}",
                expected_registry,
            ),
            layer_group=LayerGroup.WATER,
            feature_kind=FeatureKind.LABEL,
            semantic_subtype=subtype.value,
            source_style_layer_ids=(
                _u64_identity(
                    "openstreetmap.tag.waterway." + exact.waterway_type,
                    expected_registry,
                ),
            ),
            render_style_token_ids=(
                _u64_identity(
                    "flightalert.reference.water." + exact.waterway_type,
                    expected_registry,
                ),
            ),
            text=exact.primary_name,
            geometry=geometry,
            min_zoom_centi=visibility.min_zoom_centi,
            max_zoom_centi=LABEL_DISPLAY_MAX_ZOOM_CENTI,
            fade_in_centi=visibility.full_alpha_zoom_centi,
            fade_out_centi=LABEL_FADE_OUT_ZOOM_CENTI,
            draw_order=40,
            priority=decision.semantic_priority,
            placement=expected_placement,
            land_evidence=LandEvidence.NOT_APPLICABLE,
            protected_status=ProtectedStatus.NOT_APPLICABLE,
            flags=0,
            identity_registry=expected_registry,
        )
        if actual_variant != expected_variant:
            raise _error("feature frame variant differs from exact source rendering")
        for tile, world_wrap in candidates:
            expected_postings_by_tile.setdefault(tile, []).append(
                (
                    tile,
                    feature_id,
                    expected_variant.canonical_variant_id,
                    owner_tile,
                    world_wrap,
                )
            )
    expected_postings = tuple(
        posting
        for tile in sorted(expected_postings_by_tile)
        for posting in expected_postings_by_tile[tile]
    )
    if tuple(actual_postings) != expected_postings:
        raise _error("feature frame postings differ from exact source tile coverage")
    if frame.registry_claims != expected_registry.claims:
        raise _error("feature frame registry claims differ from validated records")
    actual_identities = tuple(frame.identity_rows)
    if (
        len(actual_identities) != len(expected_identities)
        or {
            (table, hot_id, full_sha256)
            for table, hot_id, full_sha256 in actual_identities
        }
        != {
            (table, hot_id, full_sha256)
            for (table, hot_id), full_sha256 in expected_identities.items()
        }
    ):
        raise _error("feature frame identity rows differ from decoded records")
    if frame.posting_bytes != sum(len(row[11]) for row in validated_rows):
        raise _error("feature frame posting-byte peak differs from decoded records")
    return tuple(validated_rows)


@dataclass(frozen=True, slots=True)
class _RecordEnvelopeParts:
    renderer_bytes: bytes
    sourced_sha256: bytes
    sourced_bytes: bytes

    @property
    def encoded_byte_count(self) -> int:
        return (
            4
            + len(self.renderer_bytes)
            + 4
            + len(self.sourced_sha256)
            + len(self.sourced_bytes)
        )


def _record_envelope_parts(record: object, tile: TileKey) -> _RecordEnvelopeParts:
    renderer = record.renderer_record
    sourced = record.sourced_text
    if renderer.posting.requested_tile != tile or sourced is None:
        raise _error("waterway posting cannot form one canonical label envelope")
    renderer_bytes = renderer_record_bytes(renderer)
    sourced_bytes = sourced.canonical_bytes
    if not 0 < len(renderer_bytes) <= MAX_RENDERER_RECORD_BYTES:
        raise _error("waterway renderer record byte length is outside its bound")
    if not 0 < len(sourced_bytes) <= MAX_SOURCED_TEXT_RECORD_BYTES:
        raise _error("waterway sourced-text byte length is outside its bound")
    sourced_sha256 = _require_sha256_bytes(
        sourced.full_sha256,
        "waterway sourced-text full identity",
    )
    return _RecordEnvelopeParts(
        renderer_bytes=renderer_bytes,
        sourced_sha256=sourced_sha256,
        sourced_bytes=sourced_bytes,
    )


def _record_envelope(
    record: object,
    tile: TileKey,
    prepared_parts: _RecordEnvelopeParts | None = None,
) -> bytes:
    parts = (
        _record_envelope_parts(record, tile)
        if prepared_parts is None
        else prepared_parts
    )
    if not isinstance(parts, _RecordEnvelopeParts):
        raise _error("waterway record envelope parts are invalid")
    return b"".join(
        (
            struct.pack("<I", len(parts.renderer_bytes)),
            parts.renderer_bytes,
            struct.pack("<I", len(parts.sourced_bytes)),
            parts.sourced_sha256,
            parts.sourced_bytes,
        )
    )


def _remember_identity(
    rows: list[tuple[str, bytes, bytes]],
    seen: dict[tuple[str, bytes], bytes],
    table: str,
    hot_id: bytes,
    full_sha256: bytes,
) -> bool:
    previous = seen.get((table, hot_id))
    if previous is not None:
        if previous != full_sha256:
            raise _error("fatal 64-bit identity collision between unequal canonical values")
        return False
    seen[(table, hot_id)] = full_sha256
    rows.append((table, hot_id, full_sha256))
    return True


class _FrameByteBudget:
    def __init__(self, maximum_bytes: int, initial_bytes: int) -> None:
        _require_int(
            maximum_bytes,
            1,
            _MAX_SPOOL_BYTES,
            "feature render frame remaining byte quota",
        )
        self.maximum_bytes = maximum_bytes
        self.used_bytes = 0
        self.reserve(initial_bytes)

    def reserve(self, byte_count: int) -> None:
        _require_int(
            byte_count,
            0,
            _MAX_SPOOL_BYTES,
            "feature render frame encoded byte reservation",
        )
        if self.used_bytes + byte_count > self.maximum_bytes:
            raise _error("feature render frame exceeds its remaining spool byte quota")
        self.used_bytes += byte_count


def _geometry_source_witnesses(
    exact: ExactWaterwayFeature,
    zooms: tuple[int, ...],
) -> tuple[tuple[int, tuple[tuple[int, ...], ...]], ...]:
    """Record the worker's exact simplification indices without parent rerendering."""

    normalized_zooms = _normalized_validation_zooms(zooms)
    witnesses: list[tuple[int, tuple[tuple[int, ...], ...]]] = []
    for zoom in normalized_zooms:
        pixel_denominator = (1 << zoom) * 512
        tolerance = max(
            1,
            (_WORLD_DENOMINATOR + pixel_denominator - 1) // pixel_denominator,
        )
        parts: list[tuple[int, ...]] = []
        for part in exact.parts:
            projected = _unwrapped_world_points(part)
            anchors = tuple(
                sorted(
                    {0, len(part) - 1}
                    | {
                        index
                        for index, point in enumerate(part)
                        if point.node_id in exact.required_node_ids
                    }
                )
            )
            kept: set[int] = set()
            for start, end in zip(anchors, anchors[1:]):
                kept.update(
                    start + index
                    for index in _simplified_indices(
                        projected[start : end + 1],
                        tolerance,
                    )
                )
            parts.append(tuple(sorted(kept)))
        witnesses.append((zoom, tuple(parts)))
    return tuple(witnesses)


def _initial_frame_encoded_bytes(
    exact: ExactWaterwayFeature,
    registry_claims: tuple[RegistryClaim, ...],
    geometry_source_witnesses: tuple[
        tuple[int, tuple[tuple[int, ...], ...]], ...
    ],
) -> int:
    timestamp_bytes = _utf8(
        exact.source_timestamp,
        _MAX_TIMESTAMP_BYTES,
        "feature frame source timestamp",
    )
    claim_bytes = sum(
        4
        + len(claim.domain)
        + 4
        + len(claim.canonical_bytes)
        + 32
        + 8
        for claim in registry_claims
    )
    witness_bytes = 4 + sum(
        1 + sum(4 + 4 * len(indices) for indices in parts)
        for _zoom, parts in geometry_source_witnesses
    )
    return (
        8
        + 1
        + 8
        + 8
        + 4
        + len(timestamp_bytes)
        + 1
        + 32
        + 32
        + 4
        + 4
        + 4
        + witness_bytes
        + 8
        + 1
        + 8
        + 1
        + 32
        + 4
        + claim_bytes
        + 4
        + 4
        + 8
    )


def _encoded_record_row_bytes_for_envelope_length(envelope_byte_count: int) -> int:
    _require_int(
        envelope_byte_count,
        1,
        _MAX_RECORD_ENVELOPE_BYTES,
        "feature frame record envelope byte count",
    )
    return (
        1
        + 4
        + 4
        + 28
        + 4
        + 4
        + 1
        + 1
        + 8
        + 8
        + 32
        + 4
        + envelope_byte_count
        + 4
        + 1
    )


def _encoded_record_row_bytes(envelope: bytes) -> int:
    if type(envelope) is not bytes:
        raise _error("feature frame record envelope must be immutable bytes")
    return _encoded_record_row_bytes_for_envelope_length(len(envelope))


def _build_feature_render_frame(
    *,
    ordinal: int,
    exact: ExactWaterwayFeature,
    rendered: object,
    registry_claims: tuple[RegistryClaim, ...],
    maximum_encoded_bytes: int,
) -> FeatureRenderFrame:
    geometry_source_witnesses = _geometry_source_witnesses(
        exact,
        tuple(sorted({tile.z for tile in rendered.tiles})),
    )
    budget = _FrameByteBudget(
        maximum_encoded_bytes,
        _initial_frame_encoded_bytes(
            exact,
            registry_claims,
            geometry_source_witnesses,
        ),
    )
    feature_hot = exact.source_feature_sha256[:8]
    identity_rows: list[tuple[str, bytes, bytes]] = []
    seen_identities: dict[tuple[str, bytes], bytes] = {}
    if _remember_identity(
        identity_rows,
        seen_identities,
        "feature_ids",
        feature_hot,
        exact.source_feature_sha256,
    ):
        budget.reserve(1 + 8 + 32)
    record_rows: list[tuple[object, ...]] = []
    posting_bytes = 0
    for tile, records in rendered.tiles.items():
        if not records:
            raise _error("waterway feature emitted an empty tile group")
        for record in records:
            renderer = record.renderer_record
            variant = renderer.variant
            posting = renderer.posting
            sourced = record.sourced_text
            if sourced is None:
                raise _error("waterway label lacks exact sourced text")
            if posting.feature_id.to_bytes(8, "big") != feature_hot:
                raise _error(
                    "waterway renderer feature ID differs from exact source identity"
                )
            geometry_hot = variant.geometry_id.to_bytes(8, "big")
            label_hot = variant.placement.label_candidate_id.to_bytes(8, "big")
            variant_hot = variant.canonical_variant_id.to_bytes(8, "big")
            sourced_hot = sourced.hot_id.to_bytes(8, "big")
            if _remember_identity(
                identity_rows,
                seen_identities,
                "geometry_ids",
                geometry_hot,
                variant.placement.placement_geometry_sha256,
            ):
                budget.reserve(1 + 8 + 32)
            if _remember_identity(
                identity_rows,
                seen_identities,
                "label_ids",
                label_hot,
                variant.placement.label_candidate_sha256,
            ):
                budget.reserve(1 + 8 + 32)
            if _remember_identity(
                identity_rows,
                seen_identities,
                "variant_ids",
                variant_hot,
                variant_fingerprint(variant).full_sha256,
            ):
                budget.reserve(1 + 8 + 32)
            if _remember_identity(
                identity_rows,
                seen_identities,
                "sourced_ids",
                sourced_hot,
                sourced.full_sha256,
            ):
                budget.reserve(1 + 8 + 32)
            envelope_parts = _record_envelope_parts(record, tile)
            budget.reserve(
                _encoded_record_row_bytes_for_envelope_length(
                    envelope_parts.encoded_byte_count
                )
            )
            envelope = _record_envelope(record, tile, envelope_parts)
            posting_key = struct.pack(
                ">QQQi",
                posting.feature_id,
                posting.canonical_variant_id,
                posting.owner_tile.packed,
                posting.world_wrap,
            )
            record_rows.append(
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
                )
            )
            posting_bytes += len(envelope)
            if len(record_rows) > _MAX_RECORD_ROWS:
                raise _error("feature frame record row count exceeds its bound")
    feature_bytes = _encode_exact_feature(exact)
    return FeatureRenderFrame(
        ordinal=ordinal,
        source_kind=exact.source_kind,
        source_id=exact.source_id,
        source_version=exact.source_version,
        source_timestamp=exact.source_timestamp,
        waterway_type=exact.waterway_type,
        source_feature_sha256=exact.source_feature_sha256,
        source_frame_sha256=_source_frame_sha256(feature_bytes),
        part_count=len(exact.parts),
        point_count=_feature_point_count(exact),
        required_node_count=len(exact.required_node_ids),
        geometry_source_witnesses=geometry_source_witnesses,
        rendered_feature_row=(
            ordinal,
            exact.source_kind,
            exact.source_id,
            exact.waterway_type,
            exact.source_feature_sha256,
        ),
        registry_claims=registry_claims,
        identity_rows=tuple(identity_rows),
        record_rows=tuple(record_rows),
        posting_bytes=posting_bytes,
    )


def _expected_batch_file_name(start_ordinal: int, end_ordinal_exclusive: int) -> str:
    _require_int(start_ordinal, 0, _MAX_FILE_ORDINAL, "spool start ordinal")
    _require_int(
        end_ordinal_exclusive,
        start_ordinal + 1,
        _MAX_FILE_ORDINAL,
        "spool end ordinal",
    )
    return f"{start_ordinal:012d}-{end_ordinal_exclusive:012d}.batch"


def _is_reparse(value: os.stat_result) -> bool:
    return bool(getattr(value, "st_file_attributes", 0) & _REPARSE_POINT)


def _directory_identity(observed: os.stat_result) -> tuple[int, int, int, int]:
    return (
        int(observed.st_dev),
        int(observed.st_ino),
        int(observed.st_mode),
        int(getattr(observed, "st_file_attributes", 0)),
    )


def _require_plain_directory(
    path: Path, label: str
) -> tuple[tuple[Path, tuple[int, int, int, int]], ...]:
    absolute = Path(os.path.abspath(path))
    chain: list[tuple[Path, tuple[int, int, int, int]]] = []
    for candidate in reversed((absolute, *absolute.parents)):
        try:
            observed = os.lstat(candidate)
        except OSError as error:
            raise _error(f"{label} or one of its ancestors is unavailable") from error
        if (
            stat.S_ISLNK(observed.st_mode)
            or not stat.S_ISDIR(observed.st_mode)
            or _is_reparse(observed)
        ):
            raise _error(
                f"{label} and every ancestor must be plain non-link, non-reparse directories"
            )
        chain.append((candidate, _directory_identity(observed)))
    return tuple(chain)


def _require_directory_chain_unchanged(
    chain: tuple[tuple[Path, tuple[int, int, int, int]], ...],
    label: str,
) -> None:
    for candidate, expected_identity in chain:
        try:
            observed = os.lstat(candidate)
        except OSError as error:
            raise _error(f"{label} ancestor disappeared during use") from error
        if (
            stat.S_ISLNK(observed.st_mode)
            or not stat.S_ISDIR(observed.st_mode)
            or _is_reparse(observed)
            or _directory_identity(observed) != expected_identity
        ):
            raise _error(f"{label} ancestor changed or became a link/reparse point")


def _create_windows_owned_temp(path: Path) -> BinaryIO:
    """Create one exact temp whose retained handle denies mutation/replacement."""

    if os.name != "nt" or not path.is_absolute():
        raise _error("Windows owned temp creation requires an absolute path")
    import ctypes
    import msvcrt
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = (wintypes.HANDLE,)
    close_handle.restype = wintypes.BOOL
    handle = create_file(
        str(path),
        _WINDOWS_GENERIC_READ
        | _WINDOWS_GENERIC_WRITE
        | _WINDOWS_DELETE_ACCESS
        | _WINDOWS_FILE_READ_ATTRIBUTES
        | _WINDOWS_SYNCHRONIZE_ACCESS,
        _WINDOWS_FILE_SHARE_READ,
        None,
        _WINDOWS_CREATE_NEW,
        _WINDOWS_FILE_ATTRIBUTE_NORMAL | _WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        error_code = ctypes.get_last_error()
        raise OSError(error_code, ctypes.FormatError(error_code), str(path))
    try:
        descriptor = msvcrt.open_osfhandle(
            int(handle), os.O_RDWR | getattr(os, "O_BINARY", 0)
        )
    except BaseException as open_error:
        try:
            _mark_windows_native_handle_for_delete(int(handle))
        except BaseException as cleanup_error:
            open_error.add_note(
                f"owned native temp cleanup failed: {cleanup_error}"
            )
        close_handle(handle)
        raise
    try:
        return os.fdopen(descriptor, "w+b", buffering=0)
    except BaseException as stream_error:
        try:
            _mark_windows_native_handle_for_delete(
                msvcrt.get_osfhandle(descriptor)
            )
        except BaseException as cleanup_error:
            stream_error.add_note(
                f"owned native temp cleanup failed: {cleanup_error}"
            )
        os.close(descriptor)
        raise


def _open_windows_owned_existing(path: Path, *, delete_access: bool) -> BinaryIO:
    """Open one exact existing leaf while denying write, rename, and replacement."""

    if os.name != "nt" or not path.is_absolute():
        raise _error("Windows owned existing-file open requires an absolute path")
    import ctypes
    import msvcrt
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = (wintypes.HANDLE,)
    close_handle.restype = wintypes.BOOL
    access = _WINDOWS_GENERIC_READ | _WINDOWS_FILE_READ_ATTRIBUTES | _WINDOWS_SYNCHRONIZE_ACCESS
    if delete_access:
        access |= _WINDOWS_DELETE_ACCESS
    handle = create_file(
        str(path),
        access,
        _WINDOWS_FILE_SHARE_READ,
        None,
        _WINDOWS_OPEN_EXISTING,
        _WINDOWS_FILE_ATTRIBUTE_NORMAL | _WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        error_code = ctypes.get_last_error()
        raise OSError(error_code, ctypes.FormatError(error_code), str(path))
    try:
        descriptor = msvcrt.open_osfhandle(
            int(handle), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        close_handle(handle)
        raise
    try:
        return os.fdopen(descriptor, "rb", buffering=0)
    except BaseException:
        os.close(descriptor)
        raise


def _windows_file_rename_info_buffer(
    destination: Path, *, replace_if_exists: bool = False
) -> object:
    if os.name != "nt" or not destination.is_absolute():
        raise _error("Windows handle publication requires an absolute destination")
    if "\x00" in str(destination):
        raise _error("Windows handle publication destination contains NUL")
    import ctypes
    from ctypes import wintypes

    class _FileRenameInfo(ctypes.Structure):
        _fields_ = (
            ("replace_if_exists", wintypes.BOOLEAN),
            ("root_directory", wintypes.HANDLE),
            ("file_name_length", wintypes.DWORD),
            ("file_name", wintypes.WCHAR * 1),
        )

    encoded = str(destination).encode("utf-16-le")
    name_offset = _FileRenameInfo.file_name.offset
    buffer = ctypes.create_string_buffer(name_offset + len(encoded) + 2)
    information = _FileRenameInfo.from_buffer(buffer)
    information.replace_if_exists = replace_if_exists
    information.root_directory = None
    information.file_name_length = len(encoded)
    ctypes.memmove(ctypes.addressof(buffer) + name_offset, encoded, len(encoded))
    return buffer


def _publish_windows_owned_temp_no_replace(
    handle: BinaryIO,
    destination: Path,
) -> None:
    """Rename the object held by *handle* without replacing a destination."""

    if os.name != "nt":
        raise _error("Windows handle publication requires Windows")
    import ctypes
    import msvcrt
    from ctypes import wintypes

    buffer = _windows_file_rename_info_buffer(destination)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    )
    set_information.restype = wintypes.BOOL
    if not set_information(
        msvcrt.get_osfhandle(handle.fileno()),
        _WINDOWS_FILE_RENAME_INFO_CLASS,
        ctypes.byref(buffer),
        len(buffer),
    ):
        error_code = ctypes.get_last_error()
        raise OSError(
            error_code,
            ctypes.FormatError(error_code),
            str(destination),
        )


def _publish_windows_owned_temp_replace(
    handle: BinaryIO,
    destination: Path,
) -> None:
    """Atomically replace *destination* with the exact object held by *handle*."""

    if os.name != "nt":
        raise _error("Windows handle replacement requires Windows")
    import ctypes
    import msvcrt
    from ctypes import wintypes

    buffer = _windows_file_rename_info_buffer(
        destination, replace_if_exists=True
    )
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    )
    set_information.restype = wintypes.BOOL
    if not set_information(
        msvcrt.get_osfhandle(handle.fileno()),
        _WINDOWS_FILE_RENAME_INFO_CLASS,
        ctypes.byref(buffer),
        len(buffer),
    ):
        error_code = ctypes.get_last_error()
        raise OSError(
            error_code,
            ctypes.FormatError(error_code),
            str(destination),
        )


def _mark_windows_native_handle_for_delete(native_handle: int) -> None:
    if os.name != "nt":
        raise _error("Windows handle disposition requires Windows")
    import ctypes
    from ctypes import wintypes

    class _FileDispositionInfo(ctypes.Structure):
        _fields_ = (("delete_file", ctypes.c_ubyte),)

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    )
    set_information.restype = wintypes.BOOL
    information = _FileDispositionInfo(1)
    if not set_information(
        native_handle,
        _WINDOWS_FILE_DISPOSITION_INFO_CLASS,
        ctypes.byref(information),
        ctypes.sizeof(information),
    ):
        error_code = ctypes.get_last_error()
        raise OSError(error_code, ctypes.FormatError(error_code))


def _mark_windows_owned_temp_for_delete(handle: BinaryIO) -> None:
    if os.name != "nt":
        raise _error("Windows handle disposition requires Windows")
    import msvcrt

    _mark_windows_native_handle_for_delete(
        msvcrt.get_osfhandle(handle.fileno())
    )


class _AnchoredSpoolDirectory:
    """Pin a verified directory chain for every side effect in one spool job."""

    def __init__(self, path: Path, label: str) -> None:
        self._label = label
        self._chain = _require_plain_directory(path, label)
        self.root = self._chain[-1][0]
        self._windows_handles: list[int] = []
        self._windows_kernel32: object | None = None

    def __enter__(self) -> _AnchoredSpoolDirectory:
        try:
            if os.name == "nt":
                self._hold_windows_chain()
            else:
                raise _error(
                    f"{self._label} requires Windows retained-handle safety"
                )
            _require_directory_chain_unchanged(self._chain, self._label)
        except BaseException:
            self._close_holds()
            raise
        return self

    def __exit__(
        self,
        exception_type: type[BaseException] | None,
        exception: BaseException | None,
        traceback: object | None,
    ) -> bool:
        try:
            self._close_holds()
        except OSError as error:
            close_error = _error(f"{self._label} anchor could not be released")
            if exception is None:
                raise close_error from error
            exception.add_note(f"{close_error}: {error}")
        return False

    def _hold_windows_chain(self) -> None:
        import ctypes
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        create_file = kernel32.CreateFileW
        create_file.argtypes = (
            wintypes.LPCWSTR,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.LPVOID,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.HANDLE,
        )
        create_file.restype = wintypes.HANDLE
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = (wintypes.HANDLE,)
        close_handle.restype = wintypes.BOOL
        invalid_handle = ctypes.c_void_p(-1).value
        self._windows_kernel32 = kernel32
        for candidate, expected_identity in self._chain:
            handle = create_file(
                str(candidate),
                _WINDOWS_FILE_READ_ATTRIBUTES,
                _WINDOWS_FILE_SHARE_READ | _WINDOWS_FILE_SHARE_WRITE,
                None,
                _WINDOWS_OPEN_EXISTING,
                _WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT
                | _WINDOWS_FILE_FLAG_BACKUP_SEMANTICS,
                None,
            )
            if handle == invalid_handle:
                error_code = ctypes.get_last_error()
                raise OSError(
                    error_code,
                    ctypes.FormatError(error_code),
                    str(candidate),
                )
            self._windows_handles.append(int(handle))
            _require_directory_chain_unchanged(
                ((candidate, expected_identity),), self._label
            )

    def _close_holds(self) -> None:
        first_error: OSError | None = None
        if self._windows_kernel32 is not None:
            close_handle = self._windows_kernel32.CloseHandle
            while self._windows_handles:
                handle = self._windows_handles.pop()
                if not close_handle(handle) and first_error is None:
                    import ctypes

                    error_code = ctypes.get_last_error()
                    first_error = OSError(
                        error_code,
                        ctypes.FormatError(error_code),
                    )
            self._windows_kernel32 = None
        if first_error is not None:
            raise first_error

    def _require_leaf_name(self, name: str) -> str:
        if (
            type(name) is not str
            or not name
            or Path(name).name != name
            or "/" in name
            or "\\" in name
        ):
            raise _error(f"{self._label} file name must stay within its root")
        return name

    def require_unchanged(self) -> None:
        _require_directory_chain_unchanged(self._chain, self._label)

    def open_exclusive(self, name: str) -> tuple[BinaryIO, tuple[int, int]]:
        leaf_name = self._require_leaf_name(name)
        if os.name != "nt" or not self._windows_handles:
            raise _error(f"{self._label} is not safely anchored")
        handle = _create_windows_owned_temp(self.root / leaf_name)
        try:
            observed = os.fstat(handle.fileno())
            _require_plain_file_stat(observed, "feature batch newly created spool")
            return handle, _owned_file_identity(observed)
        except BaseException as validation_error:
            if os.name == "nt":
                try:
                    _mark_windows_owned_temp_for_delete(handle)
                except BaseException as cleanup_error:
                    validation_error.add_note(
                        f"owned temp validation cleanup failed: {cleanup_error}"
                    )
            handle.close()
            raise

    def open_existing(
        self,
        name: str,
        *,
        label: str,
        delete_access: bool,
    ) -> tuple[BinaryIO, tuple[int, int]]:
        leaf_name = self._require_leaf_name(name)
        if os.name != "nt" or not self._windows_handles:
            raise _error(f"{self._label} is not safely anchored")
        try:
            before = self.lstat(leaf_name)
        except OSError as error:
            raise _error(f"{label} is unavailable") from error
        _require_plain_file_stat(before, label)
        try:
            handle = _open_windows_owned_existing(
                self.root / leaf_name,
                delete_access=delete_access,
            )
        except OSError as error:
            raise _error(f"{label} could not be opened safely") from error
        try:
            opened = os.fstat(handle.fileno())
            _require_plain_file_stat(opened, label)
            expected_identity = _owned_file_identity(before)
            if _owned_file_identity(opened) != expected_identity:
                raise _error(f"{label} changed while opening")
            after = self.lstat(leaf_name)
            _require_plain_file_stat(after, label)
            if _owned_file_identity(after) != expected_identity:
                raise _error(f"{label} name identity changed while opening")
            return handle, expected_identity
        except BaseException:
            handle.close()
            raise

    def lstat(self, name: str) -> os.stat_result:
        leaf_name = self._require_leaf_name(name)
        if os.name != "nt" or not self._windows_handles:
            raise _error(f"{self._label} is not safely anchored")
        return os.lstat(self.root / leaf_name)

    def publish_owned_no_replace(
        self,
        source_name: str,
        destination_name: str,
        handle: BinaryIO,
        expected_identity: tuple[int, int],
    ) -> None:
        source_leaf = self._require_leaf_name(source_name)
        destination_leaf = self._require_leaf_name(destination_name)
        observed = os.fstat(handle.fileno())
        _require_plain_file_stat(observed, "feature batch owned temporary spool")
        if _owned_file_identity(observed) != expected_identity:
            raise _error("feature batch temporary spool handle identity changed")
        if os.name != "nt" or not self._windows_handles:
            raise _error(f"{self._label} is not safely anchored")
        source_stat = self.lstat(source_leaf)
        _require_plain_file_stat(source_stat, "feature batch temporary spool")
        if _owned_file_identity(source_stat) != expected_identity:
            raise _error("feature batch temporary spool name identity changed")
        _publish_windows_owned_temp_no_replace(
            handle,
            self.root / destination_leaf,
        )

    def dispose_owned_open_file(
        self,
        candidate_names: Sequence[str],
        handle: BinaryIO,
        expected_identity: tuple[int, int],
    ) -> None:
        leaves = tuple(self._require_leaf_name(name) for name in candidate_names)
        try:
            observed = os.fstat(handle.fileno())
            _require_plain_file_stat(observed, "feature batch owned temporary spool")
            if _owned_file_identity(observed) != expected_identity:
                raise _error(
                    "feature batch temporary spool handle identity changed before cleanup"
                )
            if os.name != "nt" or not self._windows_handles:
                raise _error(f"{self._label} is not safely anchored")
            _mark_windows_owned_temp_for_delete(handle)
        finally:
            handle.close()
        for leaf_name in leaves:
            try:
                remaining = self.lstat(leaf_name)
            except FileNotFoundError:
                continue
            if _owned_file_identity(remaining) == expected_identity:
                raise _error("feature batch owned temporary spool survived cleanup")

    def dispose_existing_open_file(
        self,
        name: str,
        handle: BinaryIO,
        expected_identity: tuple[int, int],
        *,
        label: str,
    ) -> None:
        leaf_name = self._require_leaf_name(name)
        try:
            observed = os.fstat(handle.fileno())
            _require_plain_file_stat(observed, label)
            if _owned_file_identity(observed) != expected_identity:
                raise _error(f"{label} handle identity changed before removal")
            named = self.lstat(leaf_name)
            _require_plain_file_stat(named, label)
            if _owned_file_identity(named) != expected_identity:
                raise _error(f"{label} name identity changed before removal")
            _mark_windows_owned_temp_for_delete(handle)
        finally:
            handle.close()
        try:
            remaining = self.lstat(leaf_name)
        except FileNotFoundError:
            return
        if _owned_file_identity(remaining) == expected_identity:
            raise _error(f"{label} survived exact-handle removal")


def _require_plain_file_stat(observed: os.stat_result, label: str) -> None:
    if (
        stat.S_ISLNK(observed.st_mode)
        or not stat.S_ISREG(observed.st_mode)
        or _is_reparse(observed)
    ):
        raise _error(f"{label} must be one plain regular non-link, non-reparse file")
    if getattr(observed, "st_nlink", 1) != 1:
        raise _error(f"{label} must not be a multiply linked file")


def _stat_signature(observed: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        int(observed.st_dev),
        int(observed.st_ino),
        int(observed.st_size),
        int(observed.st_mtime_ns),
        int(observed.st_ctime_ns),
    )


def _owned_file_identity(observed: os.stat_result) -> tuple[int, int]:
    return (int(observed.st_dev), int(observed.st_ino))


class DurableProgressFile:
    """Own one bounded progress leaf and replace it with retained-file identity."""

    _MAX_BYTES = 64 * 1024

    def __init__(self, path: Path) -> None:
        if not isinstance(path, Path) or not path.is_absolute():
            raise _error("waterway render progress file must be one absolute path")
        self.path = Path(os.path.abspath(path))
        self._parent_chain = _require_plain_directory(
            self.path.parent, "waterway render progress parent"
        )
        self._identity: tuple[int, int] | None = None
        self._existing_bytes: bytes | None = None
        self._read_existing()

    @property
    def existing_bytes(self) -> bytes | None:
        return self._existing_bytes

    def _read_plain_handle(self, handle: BinaryIO, label: str) -> bytes:
        observed = os.fstat(handle.fileno())
        _require_plain_file_stat(observed, label)
        if observed.st_size > self._MAX_BYTES:
            raise _error(f"{label} exceeds its byte ceiling")
        handle.seek(0)
        return _read_exact_handle_bytes(
            handle,
            int(observed.st_size),
            label=label,
        )

    def _read_existing(self) -> None:
        if os.name == "nt":
            with _AnchoredSpoolDirectory(
                self.path.parent, "waterway render progress parent"
            ) as anchored:
                if anchored._chain != self._parent_chain:
                    raise _error("waterway render progress parent identity changed")
                try:
                    observed = anchored.lstat(self.path.name)
                except FileNotFoundError:
                    self._identity = None
                    self._existing_bytes = None
                    return
                _require_plain_file_stat(observed, "waterway render progress file")
                handle, identity = anchored.open_existing(
                    self.path.name,
                    label="waterway render progress file",
                    delete_access=False,
                )
                try:
                    raw = self._read_plain_handle(
                        handle, "waterway render progress file"
                    )
                finally:
                    handle.close()
                anchored.require_unchanged()
                self._identity = identity
                self._existing_bytes = raw
                return
        _require_directory_chain_unchanged(
            self._parent_chain, "waterway render progress parent"
        )
        try:
            before = os.lstat(self.path)
        except FileNotFoundError:
            self._identity = None
            self._existing_bytes = None
            return
        _require_plain_file_stat(before, "waterway render progress file")
        with self.path.open("rb", buffering=0) as handle:
            opened = os.fstat(handle.fileno())
            if _owned_file_identity(opened) != _owned_file_identity(before):
                raise _error("waterway render progress file changed while opening")
            raw = self._read_plain_handle(handle, "waterway render progress file")
        after = os.lstat(self.path)
        if _owned_file_identity(after) != _owned_file_identity(before):
            raise _error("waterway render progress file name identity changed")
        self._identity = _owned_file_identity(before)
        self._existing_bytes = raw

    def _require_expected_destination(self) -> None:
        try:
            observed = os.lstat(self.path)
        except FileNotFoundError:
            if self._identity is not None:
                raise _error("waterway render progress file disappeared")
            return
        _require_plain_file_stat(observed, "waterway render progress file")
        if self._identity is None:
            raise _error("waterway render progress file appeared unexpectedly")
        if _owned_file_identity(observed) != self._identity:
            raise _error("waterway render progress file identity changed")

    @staticmethod
    def _write_all(handle: BinaryIO, raw: bytes) -> None:
        offset = 0
        while offset < len(raw):
            written = handle.write(raw[offset:])
            if written is None or written <= 0:
                raise _error("waterway render progress write made no progress")
            offset += written

    def replace(self, raw: bytes) -> None:
        if type(raw) is not bytes or not 0 < len(raw) <= self._MAX_BYTES:
            raise _error("waterway render progress bytes are outside their bound")
        temporary_name = f"{self.path.name}.tmp-{os.getpid()}"
        try:
            if os.name == "nt":
                self._replace_windows(raw, temporary_name)
            else:
                self._replace_posix(raw, temporary_name)
        except GlobalWaterwayPackageError:
            raise
        except OSError as error:
            raise _error(
                "waterway render progress could not be replaced atomically"
            ) from error

    def _replace_windows(self, raw: bytes, temporary_name: str) -> None:
        handle: BinaryIO | None = None
        identity: tuple[int, int] | None = None
        with _AnchoredSpoolDirectory(
            self.path.parent, "waterway render progress parent"
        ) as anchored:
            if anchored._chain != self._parent_chain:
                raise _error("waterway render progress parent identity changed")
            self._require_expected_destination()
            try:
                anchored.lstat(temporary_name)
            except FileNotFoundError:
                pass
            else:
                raise _error("waterway render progress temporary file already exists")
            try:
                handle, identity = anchored.open_exclusive(temporary_name)
                self._write_all(handle, raw)
                handle.flush()
                os.fsync(handle.fileno())
                anchored.require_unchanged()
                temporary = anchored.lstat(temporary_name)
                _require_plain_file_stat(
                    temporary, "waterway render progress temporary file"
                )
                if (
                    _owned_file_identity(temporary) != identity
                    or temporary.st_size != len(raw)
                ):
                    raise _error(
                        "waterway render progress temporary identity or bytes changed"
                    )
                self._require_expected_destination()
                if self._identity is None:
                    anchored.publish_owned_no_replace(
                        temporary_name,
                        self.path.name,
                        handle,
                        identity,
                    )
                else:
                    _publish_windows_owned_temp_replace(handle, self.path)
                anchored.require_unchanged()
                published = anchored.lstat(self.path.name)
                _require_plain_file_stat(published, "waterway render progress file")
                if (
                    _owned_file_identity(published) != identity
                    or published.st_size != len(raw)
                ):
                    raise _error(
                        "waterway render progress published identity or bytes changed"
                    )
                handle.seek(0)
                if self._read_plain_handle(
                    handle, "waterway render progress file"
                ) != raw:
                    raise _error("waterway render progress published bytes changed")
                os.fsync(handle.fileno())
                handle.close()
                handle = None
                self._identity = identity
                self._existing_bytes = raw
            except BaseException as operation_error:
                if handle is not None and identity is not None:
                    try:
                        anchored.dispose_owned_open_file(
                            (temporary_name, self.path.name),
                            handle,
                            identity,
                        )
                    except BaseException as cleanup_error:
                        operation_error.add_note(
                            f"owned progress temporary cleanup failed: {cleanup_error}"
                        )
                    finally:
                        handle = None
                raise
            finally:
                if handle is not None:
                    handle.close()

    def _replace_posix(self, raw: bytes, temporary_name: str) -> None:
        _require_directory_chain_unchanged(
            self._parent_chain, "waterway render progress parent"
        )
        self._require_expected_destination()
        temporary = self.path.parent / temporary_name
        try:
            with temporary.open("xb", buffering=0) as handle:
                self._write_all(handle, raw)
                handle.flush()
                os.fsync(handle.fileno())
            self._require_expected_destination()
            if self._identity is None:
                os.link(temporary, self.path, follow_symlinks=False)
                temporary.unlink()
            else:
                os.replace(temporary, self.path)
            directory_descriptor = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory_descriptor)
            finally:
                os.close(directory_descriptor)
        except BaseException:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
            raise
        observed = os.lstat(self.path)
        _require_plain_file_stat(observed, "waterway render progress file")
        if self.path.read_bytes() != raw:
            raise _error("waterway render progress published bytes changed")
        self._identity = _owned_file_identity(observed)
        self._existing_bytes = raw


class _QuotaFileWriter:
    def __init__(self, handle: BinaryIO, byte_quota: int) -> None:
        self._handle = handle
        self._quota = byte_quota
        self.byte_count = 0
        self._digest = hashlib.sha256()

    @property
    def sha256(self) -> str:
        return self._digest.hexdigest()

    def raw(self, value: bytes) -> None:
        if type(value) is not bytes:
            raise _error("spool writer requires immutable bytes")
        if self.byte_count + len(value) > self._quota:
            raise _error("feature batch exceeds its reserved spool byte quota")
        view = memoryview(value)
        written = 0
        while written < len(view):
            count = self._handle.write(view[written:])
            if count is None or count <= 0:
                raise _error("feature batch spool write made no progress")
            written += count
        self.byte_count += len(value)
        self._digest.update(value)

    def u8(self, value: int, label: str) -> None:
        self.raw(struct.pack("<B", _require_int(value, 0, 0xFF, label)))

    def u32(self, value: int, label: str) -> None:
        self.raw(struct.pack("<I", _require_int(value, 0, 0xFFFFFFFF, label)))

    def u64(self, value: int, label: str) -> None:
        self.raw(struct.pack("<Q", _require_int(value, 0, (1 << 64) - 1, label)))


def render_feature_batch_job(job_bytes: bytes) -> SpoolDescriptor:
    job = decode_feature_batch_job(job_bytes)
    spool_root = Path(job.spool_directory)
    expected_owner = _spool_owner_bytes(
        package_id=job.package_id,
        render_run_identity_sha256=job.render_run_identity_sha256,
        source_document_sha256=job.source_generation_sha256,
    )
    if hashlib.sha256(expected_owner).hexdigest() != job.spool_owner_sha256:
        raise _error("feature batch spool owner package binding changed")
    end_ordinal = job.start_ordinal + len(job.features)
    file_name = _expected_batch_file_name(job.start_ordinal, end_ordinal)
    temporary_name = f"{file_name}.tmp-{os.getpid()}"
    source_range_sha256 = _source_range_sha256(job.features)
    point_count = sum(_feature_point_count(feature) for feature in job.features)
    try:
        with _AnchoredSpoolDirectory(
            spool_root, "feature batch spool directory"
        ) as anchored_spool:
            owner_handle: BinaryIO | None = None
            handle: BinaryIO | None = None
            owned_identity: tuple[int, int] | None = None
            try:
                anchored_spool.require_unchanged()
                owner_handle, _owner_identity = _open_matching_spool_owner(
                    anchored_spool,
                    expected_owner,
                    delete_access=False,
                )
                anchored_spool.require_unchanged()
                handle, owned_identity = anchored_spool.open_exclusive(
                    temporary_name
                )
                anchored_spool.require_unchanged()
                writer = _QuotaFileWriter(handle, job.spool_byte_quota)
                writer.raw(_BATCH_MAGIC)
                writer.u8(_BATCH_VERSION, "feature batch spool version")
                writer.u64(job.start_ordinal, "feature batch spool start ordinal")
                writer.u64(end_ordinal, "feature batch spool end ordinal")
                writer.raw(bytes.fromhex(job.render_run_identity_sha256))
                writer.raw(bytes.fromhex(source_range_sha256))
                writer.u64(point_count, "feature batch spool point count")
                writer.u32(len(job.features), "feature batch spool frame count")
                for offset, exact in enumerate(job.features):
                    registry = RecordingHotIdRegistry()
                    rendered = build_adaptive_waterway_feature(
                        feature=exact,
                        source_generation_sha256=job.source_generation_sha256,
                        classifier_sha256=job.classifier_sha256,
                        zooms=job.zooms,
                        identity_registry=registry,
                    )
                    remaining_bytes = job.spool_byte_quota - writer.byte_count
                    if remaining_bytes <= 8:
                        raise _error(
                            "feature batch lacks remaining quota for a length-prefixed frame"
                        )
                    frame_byte_quota = remaining_bytes - 8
                    frame = _build_feature_render_frame(
                        ordinal=job.start_ordinal + offset,
                        exact=exact,
                        rendered=rendered,
                        registry_claims=registry.claims,
                        maximum_encoded_bytes=frame_byte_quota,
                    )
                    encoded_frame = _encode_feature_render_frame(
                        frame, frame_byte_quota
                    )
                    writer.u64(len(encoded_frame), "feature render frame byte count")
                    writer.raw(encoded_frame)
                handle.flush()
                os.fsync(handle.fileno())
                byte_count = writer.byte_count
                spool_sha256 = writer.sha256
                temporary_stat = anchored_spool.lstat(temporary_name)
                _require_plain_file_stat(
                    temporary_stat, "feature batch temporary spool"
                )
                if _owned_file_identity(temporary_stat) != owned_identity:
                    raise _error("feature batch temporary spool identity differs")
                if temporary_stat.st_size != byte_count:
                    raise _error(
                        "feature batch temporary spool size differs after fsync"
                    )
                anchored_spool.require_unchanged()
                anchored_spool.publish_owned_no_replace(
                    temporary_name,
                    file_name,
                    handle,
                    owned_identity,
                )
                anchored_spool.require_unchanged()
                published = anchored_spool.lstat(file_name)
                _require_plain_file_stat(published, "feature batch published spool")
                if _owned_file_identity(published) != owned_identity:
                    raise _error("feature batch published spool identity differs")
                if published.st_size != byte_count:
                    raise _error("feature batch published spool size differs")
                handle.close()
                handle = None
            except BaseException as operation_error:
                if handle is not None and owned_identity is not None:
                    try:
                        anchored_spool.dispose_owned_open_file(
                            (temporary_name, file_name),
                            handle,
                            owned_identity,
                        )
                    except BaseException as cleanup_error:
                        operation_error.add_note(
                            "owned feature batch temp cleanup failed: "
                            f"{cleanup_error}"
                        )
                    finally:
                        handle = None
                raise
            finally:
                if handle is not None:
                    handle.close()
                if owner_handle is not None:
                    owner_handle.close()
    except GlobalWaterwayPackageError:
        raise
    except OSError as error:
        raise _error("feature batch spool could not be published atomically") from error
    return SpoolDescriptor(
        start_ordinal=job.start_ordinal,
        end_ordinal_exclusive=end_ordinal,
        file_name=file_name,
        byte_count=byte_count,
        sha256=spool_sha256,
        source_range_sha256=source_range_sha256,
        point_count=point_count,
    )


class _HashingFileReader:
    def __init__(self, handle: BinaryIO, byte_count: int) -> None:
        self._handle = handle
        self._byte_count = byte_count
        self._position = 0
        self._digest = hashlib.sha256()

    @property
    def remaining(self) -> int:
        return self._byte_count - self._position

    @property
    def sha256(self) -> str:
        return self._digest.hexdigest()

    def take(self, count: int, label: str) -> bytes:
        _require_int(count, 0, self._byte_count, f"{label} byte count")
        if count > self.remaining:
            raise _error(f"{label} is truncated")
        output = bytearray()
        while len(output) < count:
            chunk = self._handle.read(count - len(output))
            if not chunk:
                raise _error(f"{label} is truncated")
            output.extend(chunk)
        value = bytes(output)
        self._position += count
        self._digest.update(value)
        return value

    def u8(self, label: str) -> int:
        return struct.unpack("<B", self.take(1, label))[0]

    def u32(self, label: str) -> int:
        return struct.unpack("<I", self.take(4, label))[0]

    def u64(self, label: str) -> int:
        return struct.unpack("<Q", self.take(8, label))[0]

    def finish(self, trailing_message: str) -> None:
        if self.remaining:
            raise _error(trailing_message)


class _BoundedStreamReader:
    def __init__(self, parent: _HashingFileReader, byte_count: int, label: str) -> None:
        if not 0 < byte_count <= _MAX_SPOOL_BYTES or byte_count > parent.remaining:
            raise _error(f"{label} byte count is outside its bound")
        self._parent = parent
        self._remaining = byte_count
        self._label = label

    @property
    def remaining(self) -> int:
        return self._remaining

    def take(self, count: int, label: str) -> bytes:
        _require_int(count, 0, _MAX_SPOOL_BYTES, f"{label} byte count")
        if count > self._remaining:
            raise _error(f"{label} is truncated")
        value = self._parent.take(count, label)
        self._remaining -= count
        return value

    def u8(self, label: str) -> int:
        return struct.unpack("<B", self.take(1, label))[0]

    def u32(self, label: str) -> int:
        return struct.unpack("<I", self.take(4, label))[0]

    def u64(self, label: str) -> int:
        return struct.unpack("<Q", self.take(8, label))[0]

    def i32(self, label: str) -> int:
        return struct.unpack("<i", self.take(4, label))[0]

    def blob(self, maximum_bytes: int, label: str) -> bytes:
        count = self.u32(f"{label} byte count")
        if count > maximum_bytes:
            raise _error(f"{label} byte count exceeds its bound")
        return self.take(count, label)

    def text(self, maximum_bytes: int, label: str) -> str:
        raw = self.blob(maximum_bytes, label)
        try:
            return raw.decode("utf-8", "strict")
        except UnicodeError as error:
            raise _error(f"{label} is not strict UTF-8 text") from error

    def finish(self) -> None:
        if self._remaining:
            raise _error(f"{self._label} has trailing bytes")


def _open_verified_spool(path: Path, before: os.stat_result):
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as error:
        raise _error("feature batch spool could not be opened safely") from error
    handle = os.fdopen(descriptor, "rb", buffering=0)
    try:
        opened = os.fstat(handle.fileno())
        _require_plain_file_stat(opened, "feature batch opened spool")
        if _stat_signature(opened) != _stat_signature(before):
            raise _error("feature batch spool changed while opening")
    except BaseException:
        handle.close()
        raise
    return handle


def _read_feature_batch_handle(
    handle: BinaryIO,
    descriptor: SpoolDescriptor,
    *,
    expected_render_run_identity_sha256: str,
    expected_source_range_sha256: str,
    expected_stat: os.stat_result,
) -> tuple[FeatureRenderFrame, ...]:
    if type(descriptor) is not SpoolDescriptor:
        raise _error("feature batch reader requires SpoolDescriptor")
    expected_run = _require_sha256_text(
        expected_render_run_identity_sha256, "expected render run identity"
    )
    expected_source_range = _require_sha256_text(
        expected_source_range_sha256, "expected source-range identity"
    )
    opened = os.fstat(handle.fileno())
    _require_plain_file_stat(opened, "feature batch opened spool")
    if _stat_signature(opened) != _stat_signature(expected_stat):
        raise _error("feature batch spool changed before reading")
    if opened.st_size != descriptor.byte_count:
        raise _error("feature batch spool byte count differs from its descriptor")
    handle.seek(0)
    reader = _HashingFileReader(handle, descriptor.byte_count)
    if reader.take(len(_BATCH_MAGIC), "feature batch spool magic") != _BATCH_MAGIC:
        raise _error("feature batch spool magic is unsupported")
    if reader.u8("feature batch spool version") != _BATCH_VERSION:
        raise _error("feature batch spool version is unsupported")
    start_ordinal = reader.u64("feature batch spool start ordinal")
    end_ordinal = reader.u64("feature batch spool end ordinal")
    render_run_sha256 = reader.take(32, "feature batch render run identity").hex()
    source_range_sha256 = reader.take(
        32, "feature batch source-range identity"
    ).hex()
    point_count = reader.u64("feature batch spool point count")
    frame_count = reader.u32("feature batch spool frame count")
    if (start_ordinal, end_ordinal) != (
        descriptor.start_ordinal,
        descriptor.end_ordinal_exclusive,
    ):
        raise _error("feature batch spool ordinal range differs from its descriptor")
    if render_run_sha256 != expected_run:
        raise _error("feature batch spool render run identity differs")
    if (
        source_range_sha256 != descriptor.source_range_sha256
        or source_range_sha256 != expected_source_range
    ):
        raise _error("feature batch spool source-range SHA-256 differs")
    if point_count != descriptor.point_count:
        raise _error("feature batch spool point count differs from its descriptor")
    expected_count = end_ordinal - start_ordinal
    if not 1 <= frame_count <= _MAX_BATCH_FEATURES or frame_count != expected_count:
        raise _error("feature batch spool frame count differs from its ordinal range")
    frames: list[FeatureRenderFrame] = []
    for index in range(frame_count):
        frame_byte_count = reader.u64("feature render frame byte count")
        frame_reader = _BoundedStreamReader(
            reader, frame_byte_count, "feature render frame"
        )
        frame = _decode_feature_render_frame(frame_reader)
        frame_reader.finish()
        if frame.ordinal != start_ordinal + index:
            raise _error("feature batch frame ordinal is not contiguous")
        frames.append(frame)
    if sum(frame.point_count for frame in frames) != point_count:
        raise _error("feature batch spool point count differs from its frames")
    reader.finish("feature batch spool has trailing bytes")
    if reader.sha256 != descriptor.sha256:
        raise _error("feature batch spool SHA-256 differs from its descriptor")
    after_handle = os.fstat(handle.fileno())
    _require_plain_file_stat(after_handle, "feature batch opened spool")
    if _stat_signature(after_handle) != _stat_signature(expected_stat):
        raise _error("feature batch spool changed while reading")
    return tuple(frames)


def read_feature_batch(
    spool_directory: str | os.PathLike[str],
    descriptor: SpoolDescriptor,
    *,
    expected_render_run_identity_sha256: str,
    expected_source_range_sha256: str,
) -> tuple[FeatureRenderFrame, ...]:
    if type(descriptor) is not SpoolDescriptor:
        raise _error("feature batch reader requires SpoolDescriptor")
    expected_run = _require_sha256_text(
        expected_render_run_identity_sha256, "expected render run identity"
    )
    expected_source_range = _require_sha256_text(
        expected_source_range_sha256, "expected source-range identity"
    )
    root = Path(spool_directory)
    if not root.is_absolute():
        raise _error("feature batch spool root must be absolute")
    root_chain = _require_plain_directory(root, "feature batch spool root")
    expected_name = _expected_batch_file_name(
        descriptor.start_ordinal, descriptor.end_ordinal_exclusive
    )
    if (
        descriptor.file_name != expected_name
        or Path(descriptor.file_name).name != descriptor.file_name
    ):
        raise _error("feature batch spool name or range is not canonical under its root")
    path = root / descriptor.file_name
    try:
        if os.path.commonpath(
            (os.path.abspath(root), os.path.abspath(path))
        ) != os.path.abspath(root):
            raise _error("feature batch spool lies outside its root")
    except ValueError as error:
        raise _error("feature batch spool lies outside its root") from error
    try:
        before = os.lstat(path)
    except OSError as error:
        raise _error("feature batch spool is unavailable") from error
    _require_plain_file_stat(before, "feature batch spool")
    if before.st_size != descriptor.byte_count:
        raise _error("feature batch spool byte count differs from its descriptor")
    handle = _open_verified_spool(path, before)
    try:
        _require_directory_chain_unchanged(root_chain, "feature batch spool root")
        frames = _read_feature_batch_handle(
            handle,
            descriptor,
            expected_render_run_identity_sha256=expected_run,
            expected_source_range_sha256=expected_source_range,
            expected_stat=before,
        )
    finally:
        handle.close()
    _require_directory_chain_unchanged(root_chain, "feature batch spool root")
    try:
        after_path = os.lstat(path)
    except OSError as error:
        raise _error("feature batch spool disappeared after reading") from error
    _require_plain_file_stat(after_path, "feature batch spool")
    if _stat_signature(after_path) != _stat_signature(before):
        raise _error("feature batch spool changed after reading")
    return tuple(frames)


def _canonical_json_bytes(document: object) -> bytes:
    try:
        return (
            json.dumps(
                document,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as error:
        raise _error("parallel spool owner is not canonical JSON") from error


def _spool_owner_bytes(
    *,
    package_id: str,
    render_run_identity_sha256: str,
    source_document_sha256: str,
) -> bytes:
    package_bytes = _utf8(package_id, 1_024, "parallel spool package ID")
    if not package_bytes or "\x00" in package_id:
        raise _error("parallel spool package ID is invalid")
    render_run = _require_sha256_text(
        render_run_identity_sha256, "parallel spool render run identity"
    )
    source_document = _require_sha256_text(
        source_document_sha256, "parallel spool source document identity"
    )
    encoded = _canonical_json_bytes(
        {
            "batchSchema": _BATCH_SCHEMA,
            "packageId": package_id,
            "renderRunIdentitySha256": render_run,
            "schema": _SPOOL_OWNER_SCHEMA,
            "sourceDocumentSha256": source_document,
        }
    )
    if len(encoded) > _SPOOL_OWNER_MAX_BYTES:
        raise _error("parallel spool owner exceeds its byte bound")
    return encoded


def _absolute_spool_directory(value: str | os.PathLike[str]) -> Path:
    try:
        supplied = Path(value)
    except (TypeError, ValueError) as error:
        raise _error("parallel spool directory path is invalid") from error
    if not supplied.is_absolute():
        raise _error("parallel spool directory must be one absolute path")
    try:
        return Path(os.path.abspath(supplied))
    except (OSError, ValueError) as error:
        raise _error("parallel spool directory path is invalid") from error


def _read_exact_handle_bytes(
    handle: BinaryIO,
    expected_size: int,
    *,
    label: str,
) -> bytes:
    observed = os.fstat(handle.fileno())
    _require_plain_file_stat(observed, label)
    if observed.st_size != expected_size:
        raise _error(f"{label} byte count differs")
    chunks: list[bytes] = []
    remaining = expected_size
    while remaining:
        chunk = handle.read(remaining)
        if not chunk:
            raise _error(f"{label} is truncated")
        chunks.append(chunk)
        remaining -= len(chunk)
    if handle.read(1):
        raise _error(f"{label} has trailing bytes")
    return b"".join(chunks)


def _open_matching_spool_owner(
    anchored: _AnchoredSpoolDirectory,
    expected_owner: bytes,
    *,
    delete_access: bool,
) -> tuple[BinaryIO, tuple[int, int]]:
    handle, identity = anchored.open_existing(
        _SPOOL_OWNER_FILE,
        label="parallel spool owner",
        delete_access=delete_access,
    )
    try:
        if _read_exact_handle_bytes(
            handle,
            len(expected_owner),
            label="parallel spool owner",
        ) != expected_owner:
            raise _error("parallel spool owner differs from the active render run")
        anchored.require_unchanged()
        return handle, identity
    except BaseException:
        handle.close()
        raise


def _publish_spool_owner(
    anchored: _AnchoredSpoolDirectory,
    owner_bytes: bytes,
) -> None:
    temporary_name = f"{_SPOOL_OWNER_FILE}.tmp-{os.getpid()}"
    handle: BinaryIO | None = None
    identity: tuple[int, int] | None = None
    try:
        handle, identity = anchored.open_exclusive(temporary_name)
        offset = 0
        while offset < len(owner_bytes):
            written = handle.write(owner_bytes[offset:])
            if written is None or written <= 0:
                raise _error("parallel spool owner write made no progress")
            offset += written
        handle.flush()
        os.fsync(handle.fileno())
        anchored.require_unchanged()
        temporary = anchored.lstat(temporary_name)
        _require_plain_file_stat(temporary, "parallel spool owner temporary file")
        if _owned_file_identity(temporary) != identity:
            raise _error("parallel spool owner temporary identity changed")
        if temporary.st_size != len(owner_bytes):
            raise _error("parallel spool owner temporary byte count differs")
        anchored.publish_owned_no_replace(
            temporary_name,
            _SPOOL_OWNER_FILE,
            handle,
            identity,
        )
        anchored.require_unchanged()
        published = anchored.lstat(_SPOOL_OWNER_FILE)
        _require_plain_file_stat(published, "parallel spool owner")
        if _owned_file_identity(published) != identity:
            raise _error("parallel spool owner published identity changed")
        if published.st_size != len(owner_bytes):
            raise _error("parallel spool owner published byte count differs")
        handle.close()
        handle = None
    except BaseException as operation_error:
        if handle is not None and identity is not None:
            try:
                anchored.dispose_owned_open_file(
                    (temporary_name, _SPOOL_OWNER_FILE),
                    handle,
                    identity,
                )
            except BaseException as cleanup_error:
                operation_error.add_note(
                    f"owned parallel spool owner cleanup failed: {cleanup_error}"
                )
            finally:
                handle = None
        raise
    finally:
        if handle is not None:
            handle.close()
    owner_handle, _owner_identity = _open_matching_spool_owner(
        anchored,
        owner_bytes,
        delete_access=False,
    )
    owner_handle.close()


def _validated_spool_children(
    anchored: _AnchoredSpoolDirectory,
    *,
    allow_batch_children: bool,
) -> tuple[tuple[str, tuple[int, int]], ...]:
    try:
        names = tuple(sorted(entry.name for entry in os.scandir(anchored.root)))
    except OSError as error:
        raise _error("parallel spool children could not be enumerated") from error
    removable: list[tuple[str, tuple[int, int]]] = []
    for name in names:
        if name == _SPOOL_OWNER_FILE:
            continue
        if not allow_batch_children or _SPOOL_CHILD_PATTERN.fullmatch(name) is None:
            raise _error("parallel spool directory contains an unknown child")
        try:
            observed = anchored.lstat(name)
        except OSError as error:
            raise _error("parallel spool child is unavailable") from error
        _require_plain_file_stat(observed, "parallel spool child")
        removable.append((name, _owned_file_identity(observed)))
    anchored.require_unchanged()
    return tuple(removable)


def _remove_validated_spool_child(
    anchored: _AnchoredSpoolDirectory,
    name: str,
    expected_identity: tuple[int, int],
) -> None:
    handle, identity = anchored.open_existing(
        name,
        label="parallel spool child",
        delete_access=True,
    )
    if identity != expected_identity:
        handle.close()
        raise _error("parallel spool child identity changed before removal")
    anchored.dispose_existing_open_file(
        name,
        handle,
        identity,
        label="parallel spool child",
    )
    anchored.require_unchanged()


def _remove_exact_empty_directory(
    path: Path,
    expected_identity: tuple[int, int, int, int],
) -> None:
    if os.name != "nt":
        raise _error("parallel spool removal requires Windows retained-handle safety")
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = (wintypes.HANDLE,)
    close_handle.restype = wintypes.BOOL
    handle = create_file(
        str(path),
        _WINDOWS_DELETE_ACCESS | _WINDOWS_FILE_READ_ATTRIBUTES | _WINDOWS_SYNCHRONIZE_ACCESS,
        _WINDOWS_FILE_SHARE_READ | _WINDOWS_FILE_SHARE_WRITE,
        None,
        _WINDOWS_OPEN_EXISTING,
        _WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT | _WINDOWS_FILE_FLAG_BACKUP_SEMANTICS,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        error_code = ctypes.get_last_error()
        raise _error("parallel spool directory could not be opened for removal") from OSError(
            error_code, ctypes.FormatError(error_code), str(path)
        )
    try:
        observed = os.lstat(path)
        if (
            stat.S_ISLNK(observed.st_mode)
            or not stat.S_ISDIR(observed.st_mode)
            or _is_reparse(observed)
            or _directory_identity(observed) != expected_identity
        ):
            raise _error("parallel spool directory identity changed before removal")
        _mark_windows_native_handle_for_delete(int(handle))
    finally:
        if not close_handle(handle):
            error_code = ctypes.get_last_error()
            raise _error("parallel spool directory handle could not be closed") from OSError(
                error_code, ctypes.FormatError(error_code), str(path)
            )
    try:
        os.lstat(path)
    except FileNotFoundError:
        return
    except OSError as error:
        raise _error("parallel spool directory removal could not be verified") from error
    raise _error("parallel spool directory survived exact-handle removal")


def _finish_owner_backup_path(root: Path, owner_bytes: bytes) -> Path:
    prefix = f".{root.name}.owner-finish-"
    try:
        with os.scandir(root.parent) as children:
            stale_names = tuple(
                sorted(entry.name for entry in children if entry.name.startswith(prefix))
            )
    except OSError as error:
        raise _error("parallel spool finish backups could not be enumerated") from error
    if stale_names:
        raise _error(
            "parallel spool finish found retained owner evidence requiring recovery"
        )
    suffix = f"{os.getpid()}-{hashlib.sha256(owner_bytes).hexdigest()[:16]}"
    return root.parent / f"{prefix}{suffix}"


def _restore_finish_owner(
    *,
    root: Path,
    root_identity: tuple[int, int, int, int],
    backup_path: Path,
    owner_handle: BinaryIO,
    owner_identity: tuple[int, int],
    owner_bytes: bytes,
) -> None:
    try:
        root_stat = os.lstat(root)
    except OSError as error:
        raise _error(
            "parallel spool owner backup cannot be restored because its root is unavailable"
        ) from error
    if (
        stat.S_ISLNK(root_stat.st_mode)
        or not stat.S_ISDIR(root_stat.st_mode)
        or _is_reparse(root_stat)
        or _directory_identity(root_stat) != root_identity
    ):
        raise _error(
            "parallel spool owner backup cannot be restored into a changed root"
        )
    with _AnchoredSpoolDirectory(root, "parallel spool directory") as anchored:
        if anchored._chain[-1][1] != root_identity:
            raise _error(
                "parallel spool owner backup root identity changed during restoration"
            )
        try:
            anchored.lstat(_SPOOL_OWNER_FILE)
        except FileNotFoundError:
            pass
        else:
            raise _error(
                "parallel spool owner backup restoration would replace evidence"
            )
        _publish_windows_owned_temp_no_replace(
            owner_handle,
            root / _SPOOL_OWNER_FILE,
        )
        published = anchored.lstat(_SPOOL_OWNER_FILE)
        _require_plain_file_stat(published, "parallel spool restored owner")
        if _owned_file_identity(published) != owner_identity:
            raise _error("parallel spool restored owner identity changed")
        if published.st_size != len(owner_bytes):
            raise _error("parallel spool restored owner byte count changed")
        owner_handle.seek(0)
        if _read_exact_handle_bytes(
            owner_handle,
            len(owner_bytes),
            label="parallel spool restored owner",
        ) != owner_bytes:
            raise _error("parallel spool restored owner content changed")
        anchored.require_unchanged()
    try:
        os.lstat(backup_path)
    except FileNotFoundError:
        return
    except OSError as error:
        raise _error("parallel spool owner backup restoration is unverified") from error
    raise _error("parallel spool owner backup remained after restoration")


def _finish_recovery_error(
    primary_error: BaseException,
    recovery_error: BaseException,
    backup_path: Path,
) -> GlobalWaterwayPackageError:
    combined = _error(
        "parallel spool finish could not restore its owner; recovery is required"
    )
    combined.add_note(f"primary finish failure: {primary_error}")
    combined.add_note(f"owner recovery failure: {recovery_error}")
    combined.add_note(f"retained owner evidence path: {backup_path}")
    return combined


def prepare_spool_directory(
    spool_directory: str | os.PathLike[str],
    *,
    package_id: str,
    render_run_identity_sha256: str,
    source_document_sha256: str,
) -> Path:
    root = _absolute_spool_directory(spool_directory)
    owner_bytes = _spool_owner_bytes(
        package_id=package_id,
        render_run_identity_sha256=render_run_identity_sha256,
        source_document_sha256=source_document_sha256,
    )
    try:
        existing = os.lstat(root)
    except FileNotFoundError:
        parent = root.parent
        try:
            with _AnchoredSpoolDirectory(
                parent, "parallel spool parent directory"
            ) as anchored_parent:
                anchored_parent.require_unchanged()
                try:
                    os.mkdir(root)
                except OSError as error:
                    raise _error("parallel spool directory could not be created exclusively") from error
                created = os.lstat(root)
                if (
                    stat.S_ISLNK(created.st_mode)
                    or not stat.S_ISDIR(created.st_mode)
                    or _is_reparse(created)
                ):
                    raise _error("parallel spool directory creation produced a link/reparse point")
                created_identity = _directory_identity(created)
                with _AnchoredSpoolDirectory(
                    root, "parallel spool directory"
                ) as anchored:
                    if anchored._chain[-1][1] != created_identity:
                        raise _error("parallel spool directory identity changed after creation")
                    _publish_spool_owner(anchored, owner_bytes)
                anchored_parent.require_unchanged()
        except GlobalWaterwayPackageError:
            raise
        except OSError as error:
            raise _error("parallel spool directory could not be prepared") from error
        return root
    except OSError as error:
        raise _error("parallel spool directory could not be inspected") from error
    if (
        stat.S_ISLNK(existing.st_mode)
        or not stat.S_ISDIR(existing.st_mode)
        or _is_reparse(existing)
    ):
        raise _error("parallel spool directory must be a plain non-link directory")
    try:
        with _AnchoredSpoolDirectory(root, "parallel spool directory") as anchored:
            owner_handle, _owner_identity = _open_matching_spool_owner(
                anchored,
                owner_bytes,
                delete_access=False,
            )
            try:
                removable = _validated_spool_children(
                    anchored,
                    allow_batch_children=True,
                )
                for name, identity in removable:
                    _remove_validated_spool_child(anchored, name, identity)
                if _validated_spool_children(
                    anchored,
                    allow_batch_children=False,
                ):
                    raise _error("parallel spool cleanup left a child behind")
            finally:
                owner_handle.close()
    except GlobalWaterwayPackageError:
        raise
    except OSError as error:
        raise _error("parallel spool directory could not be resumed safely") from error
    return root


def finish_spool_directory(
    spool_directory: str | os.PathLike[str],
    *,
    package_id: str,
    render_run_identity_sha256: str,
    source_document_sha256: str,
) -> None:
    root = _absolute_spool_directory(spool_directory)
    owner_bytes = _spool_owner_bytes(
        package_id=package_id,
        render_run_identity_sha256=render_run_identity_sha256,
        source_document_sha256=source_document_sha256,
    )
    owner_handle: BinaryIO | None = None
    owner_identity: tuple[int, int] | None = None
    backup_path: Path | None = None
    owner_moved = False
    try:
        try:
            with _AnchoredSpoolDirectory(root, "parallel spool directory") as anchored:
                root_identity = anchored._chain[-1][1]
                owner_handle, owner_identity = _open_matching_spool_owner(
                    anchored,
                    owner_bytes,
                    delete_access=True,
                )
                children = _validated_spool_children(
                    anchored,
                    allow_batch_children=False,
                )
                if children:
                    raise _error("parallel spool directory is not empty")
                backup_path = _finish_owner_backup_path(root, owner_bytes)
                _publish_windows_owned_temp_no_replace(
                    owner_handle,
                    backup_path,
                )
                owner_moved = True
                backup = os.lstat(backup_path)
                _require_plain_file_stat(backup, "parallel spool finish owner backup")
                if _owned_file_identity(backup) != owner_identity:
                    raise _error("parallel spool finish owner backup identity changed")
                if backup.st_size != len(owner_bytes):
                    raise _error("parallel spool finish owner backup byte count changed")
                try:
                    anchored.lstat(_SPOOL_OWNER_FILE)
                except FileNotFoundError:
                    pass
                else:
                    raise _error(
                        "parallel spool finish owner name was replaced during teardown"
                    )
                anchored.require_unchanged()
        except BaseException as staging_error:
            if (
                owner_moved
                and owner_handle is not None
                and owner_identity is not None
                and backup_path is not None
            ):
                try:
                    _restore_finish_owner(
                        root=root,
                        root_identity=root_identity,
                        backup_path=backup_path,
                        owner_handle=owner_handle,
                        owner_identity=owner_identity,
                        owner_bytes=owner_bytes,
                    )
                except BaseException as recovery_error:
                    owner_handle.close()
                    owner_handle = None
                    raise _finish_recovery_error(
                        staging_error,
                        recovery_error,
                        backup_path,
                    ) from recovery_error
            if owner_handle is not None:
                owner_handle.close()
                owner_handle = None
            raise
        assert owner_handle is not None
        assert owner_identity is not None
        assert backup_path is not None
        try:
            _remove_exact_empty_directory(root, root_identity)
        except BaseException as removal_error:
            try:
                _restore_finish_owner(
                    root=root,
                    root_identity=root_identity,
                    backup_path=backup_path,
                    owner_handle=owner_handle,
                    owner_identity=owner_identity,
                    owner_bytes=owner_bytes,
                )
            except BaseException as recovery_error:
                owner_handle.close()
                owner_handle = None
                raise _finish_recovery_error(
                    removal_error,
                    recovery_error,
                    backup_path,
                ) from recovery_error
            owner_handle.close()
            owner_handle = None
            raise
        try:
            _mark_windows_owned_temp_for_delete(owner_handle)
        except BaseException as disposition_error:
            owner_handle.close()
            owner_handle = None
            retained = _error(
                "parallel spool directory was removed but its owner backup requires recovery"
            )
            retained.add_note(f"retained owner evidence path: {backup_path}")
            raise retained from disposition_error
        owner_handle.close()
        owner_handle = None
        try:
            os.lstat(backup_path)
        except FileNotFoundError:
            pass
        except OSError as error:
            raise _error(
                "parallel spool finish owner backup removal could not be verified"
            ) from error
        else:
            raise _error("parallel spool finish owner backup survived removal")
    except GlobalWaterwayPackageError:
        raise
    except OSError as error:
        raise _error("parallel spool directory could not be finished safely") from error
    finally:
        if owner_handle is not None:
            owner_handle.close()


@dataclass(frozen=True, slots=True)
class _ReadyRenderJob:
    start_ordinal: int
    end_ordinal_exclusive: int
    features: tuple[ExactWaterwayFeature, ...]
    point_count: int
    input_bytes: bytes
    source_range_sha256: str
    spool_byte_quota: int


@dataclass(slots=True)
class _RenderReservation:
    start_ordinal: int
    end_ordinal_exclusive: int
    features: tuple[ExactWaterwayFeature, ...]
    point_count: int
    input_byte_count: int
    source_range_sha256: str
    spool_byte_quota: int
    spool_reserved: bool = True


@dataclass(frozen=True, slots=True)
class _CompletedRenderBatch:
    descriptor: SpoolDescriptor
    frames: tuple[FeatureRenderFrame, ...]


@dataclass(frozen=True, slots=True)
class _YieldedSpool:
    descriptor: SpoolDescriptor
    file_identity: tuple[int, int]


class ParallelFeatureRenderer:
    """Bounded parent scheduler that returns authenticated batches by ordinal."""

    def __init__(
        self,
        features: Iterable[ExactWaterwayFeature],
        *,
        start_ordinal: int,
        package_id: str,
        source_generation_sha256: str,
        classifier_sha256: str,
        zooms: Sequence[int],
        render_run_identity_sha256: str,
        spool_directory: str | os.PathLike[str],
        limits: ParallelRenderLimits,
        pause_after_features: int | None = None,
        executor_factory: Callable[..., object] = ProcessPoolExecutor,
        wait_for_futures: Callable[..., object] = wait,
        disk_usage: Callable[[Path], object] = shutil.disk_usage,
    ) -> None:
        _require_int(
            start_ordinal,
            0,
            _MAX_FILE_ORDINAL,
            "parallel render start ordinal",
        )
        if type(limits) is not ParallelRenderLimits:
            raise _error("parallel render limits are invalid")
        self._limits = limits
        self._source_generation_sha256 = _require_sha256_text(
            source_generation_sha256, "parallel render source generation identity"
        )
        self._classifier_sha256 = _require_sha256_text(
            classifier_sha256, "parallel render classifier identity"
        )
        self._render_run_identity_sha256 = _require_sha256_text(
            render_run_identity_sha256, "parallel render run identity"
        )
        expected_owner_bytes = _spool_owner_bytes(
            package_id=package_id,
            render_run_identity_sha256=self._render_run_identity_sha256,
            source_document_sha256=self._source_generation_sha256,
        )
        self._package_id = package_id
        self._owner_sha256 = hashlib.sha256(expected_owner_bytes).hexdigest()
        try:
            self._zooms = tuple(zooms)
        except TypeError as error:
            raise _error("parallel render zooms are invalid") from error
        if (
            not self._zooms
            or len(self._zooms) > 30
            or len(set(self._zooms)) != len(self._zooms)
            or any(
                type(zoom) is not int or not 0 <= zoom <= 29
                for zoom in self._zooms
            )
        ):
            raise _error("parallel render zooms are invalid")
        if pause_after_features is not None:
            _require_int(
                pause_after_features,
                1,
                _MAX_FILE_ORDINAL,
                "parallel render pause feature count",
            )
            if pause_after_features < start_ordinal:
                raise _error("parallel render pause target precedes its start ordinal")
        self._pause_after_features = pause_after_features
        self._root = _absolute_spool_directory(spool_directory)
        root_chain = _require_plain_directory(
            self._root, "parallel render spool directory"
        )
        self._root_identity = root_chain[-1][1]
        try:
            owner_stat = os.lstat(self._root / _SPOOL_OWNER_FILE)
        except OSError as error:
            raise _error("parallel render spool owner is unavailable") from error
        _require_plain_file_stat(owner_stat, "parallel render spool owner")
        if not 0 < owner_stat.st_size <= _SPOOL_OWNER_MAX_BYTES:
            raise _error("parallel render spool owner byte count is invalid")
        owner_handle = _open_verified_spool(
            self._root / _SPOOL_OWNER_FILE,
            owner_stat,
        )
        try:
            self._owner_bytes = _read_exact_handle_bytes(
                owner_handle,
                owner_stat.st_size,
                label="parallel render spool owner",
            )
        finally:
            owner_handle.close()
        _require_directory_chain_unchanged(
            root_chain, "parallel render spool directory"
        )
        if self._owner_bytes != expected_owner_bytes:
            raise _error("parallel render spool owner differs from the active render run")
        self._owner_identity = _owned_file_identity(owner_stat)
        if limits.max_spool_bytes_per_job > _MAX_SPOOL_BYTES:
            raise _error("parallel render per-job spool quota exceeds the batch codec")
        try:
            self._feature_iterator = iter(features)
        except TypeError as error:
            raise _error("parallel render features must be one iterable") from error
        if not callable(executor_factory) or not callable(wait_for_futures) or not callable(disk_usage):
            raise _error("parallel render scheduler dependency is invalid")
        self._wait_for_futures = wait_for_futures
        self._disk_usage = disk_usage
        self._next_input_ordinal = start_ordinal
        self._next_yield_ordinal = start_ordinal
        self._pending_feature: tuple[ExactWaterwayFeature, bytes, int] | None = None
        self._ready_job: _ReadyRenderJob | None = None
        self._input_exhausted = pause_after_features == start_ordinal
        self._reservations: dict[int, _RenderReservation] = {}
        self._future_to_start: dict[object, int] = {}
        self._completed: dict[int, _CompletedRenderBatch] = {}
        self._known_ranges: dict[tuple[int, int], int] = {}
        self._yielded_spools: dict[str, _YieldedSpool] = {}
        self._closed = False
        self._failed = False
        try:
            spawn_context = multiprocessing.get_context("spawn")
            self._executor = executor_factory(
                max_workers=limits.workers,
                mp_context=spawn_context,
            )
        except BaseException as error:
            raise _error("parallel render executor could not be created") from error

    def __enter__(self) -> ParallelFeatureRenderer:
        return self

    def __exit__(
        self,
        exception_type: type[BaseException] | None,
        exception: BaseException | None,
        traceback: object | None,
    ) -> bool:
        if not self._closed:
            self._abort(exception)
        return False

    def _take_input_feature(self) -> tuple[ExactWaterwayFeature, bytes, int] | None:
        if self._pending_feature is not None:
            pending = self._pending_feature
            self._pending_feature = None
            return pending
        try:
            feature = next(self._feature_iterator)
        except StopIteration:
            self._input_exhausted = True
            return None
        if type(feature) is not ExactWaterwayFeature:
            raise _error("parallel render input contains a non-exact feature")
        encoded = _encode_exact_feature(feature)
        return feature, encoded, _feature_point_count(feature)

    def _fixed_job_byte_count(self) -> int:
        spool_path_bytes = _utf8(
            str(self._root), _MAX_PATH_BYTES, "parallel render spool directory"
        )
        package_id_bytes = _utf8(
            self._package_id, 1_024, "parallel render package ID"
        )
        return (
            len(_JOB_MAGIC)
            + 1
            + 8
            + 4
            + 32
            + 32
            + 1
            + len(self._zooms)
            + 32
            + 4
            + len(package_id_bytes)
            + 32
            + 4
            + len(spool_path_bytes)
            + 8
        )

    def _source_range_from_encoded_features(
        self, encoded_features: Sequence[bytes]
    ) -> str:
        digest = hashlib.sha256(_SOURCE_RANGE_DOMAIN)
        for encoded in encoded_features:
            digest.update(struct.pack("<Q", len(encoded)))
            digest.update(encoded)
        return digest.hexdigest()

    def _build_ready_job(self) -> _ReadyRenderJob | None:
        if self._ready_job is not None:
            return self._ready_job
        if self._input_exhausted:
            return None
        if (
            self._pause_after_features is not None
            and self._next_input_ordinal >= self._pause_after_features
        ):
            self._input_exhausted = True
            return None
        start = self._next_input_ordinal
        checkpoint_end = ((start // _RENDER_CHECKPOINT_FEATURES) + 1) * _RENDER_CHECKPOINT_FEATURES
        boundary = min(start + _MAX_BATCH_FEATURES, checkpoint_end)
        if self._pause_after_features is not None:
            boundary = min(boundary, self._pause_after_features)
        features: list[ExactWaterwayFeature] = []
        encoded_features: list[bytes] = []
        point_count = 0
        encoded_byte_count = self._fixed_job_byte_count()
        while start + len(features) < boundary:
            next_feature = self._take_input_feature()
            if next_feature is None:
                break
            feature, encoded, points = next_feature
            candidate_points = point_count + points
            candidate_bytes = encoded_byte_count + 4 + len(encoded)
            exceeds_batching_bound = (
                candidate_points > self._limits.max_points_per_job
                or candidate_bytes > self._limits.max_input_bytes_per_job
            )
            if features and exceeds_batching_bound:
                self._pending_feature = next_feature
                break
            features.append(feature)
            encoded_features.append(encoded)
            point_count = candidate_points
            encoded_byte_count = candidate_bytes
            if exceeds_batching_bound:
                break
        if not features:
            return None
        job = FeatureRenderBatchJob(
            start_ordinal=start,
            features=tuple(features),
            source_generation_sha256=self._source_generation_sha256,
            classifier_sha256=self._classifier_sha256,
            zooms=self._zooms,
            render_run_identity_sha256=self._render_run_identity_sha256,
            package_id=self._package_id,
            spool_owner_sha256=self._owner_sha256,
            spool_directory=str(self._root),
            spool_byte_quota=self._limits.max_spool_bytes_per_job,
        )
        job_bytes = encode_feature_batch_job(job)
        if len(job_bytes) != encoded_byte_count:
            raise _error("parallel render exact input byte accounting differs")
        end = start + len(features)
        self._next_input_ordinal = end
        self._ready_job = _ReadyRenderJob(
            start_ordinal=start,
            end_ordinal_exclusive=end,
            features=tuple(features),
            point_count=point_count,
            input_bytes=job_bytes,
            source_range_sha256=self._source_range_from_encoded_features(
                encoded_features
            ),
            spool_byte_quota=self._limits.max_spool_bytes_per_job,
        )
        return self._ready_job

    def _reservation_totals(self) -> tuple[int, int]:
        return (
            sum(value.point_count for value in self._reservations.values()),
            sum(value.input_byte_count for value in self._reservations.values()),
        )

    def _spool_inventory_names(
        self,
        anchored: _AnchoredSpoolDirectory,
    ) -> tuple[str, ...]:
        try:
            with os.scandir(anchored.root) as children:
                names = tuple(sorted(entry.name for entry in children))
        except OSError as error:
            raise _error("parallel render spool inventory is unavailable") from error
        anchored.require_unchanged()
        return names

    def _classify_spool_inventory_names(
        self,
        names: Sequence[str],
    ) -> dict[tuple[int, int], tuple[str, bool, int]]:
        if names.count(_SPOOL_OWNER_FILE) != 1:
            raise _error("parallel render spool inventory lacks its exact owner")
        by_range: dict[tuple[int, int], tuple[str, bool, int]] = {}
        for name in names:
            if name == _SPOOL_OWNER_FILE:
                continue
            matched = _SPOOL_CHILD_PATTERN.fullmatch(name)
            if matched is None:
                raise _error("parallel render spool contains an unknown child")
            key = (
                int(matched.group("start")),
                int(matched.group("end")),
            )
            quota = self._known_ranges.get(key)
            if quota is None:
                raise _error(
                    "parallel render spool contains an unknown range child"
                )
            temporary = matched.group("temporary") is not None
            reservation = self._reservations.get(key[0])
            if temporary and (
                reservation is None
                or reservation.end_ordinal_exclusive != key[1]
                or not reservation.spool_reserved
            ):
                raise _error(
                    "parallel render spool contains a stale temporary child"
                )
            if key in by_range:
                raise _error("parallel render spool range has multiple children")
            by_range[key] = (name, temporary, quota)
        return by_range

    def _active_inventory_transition_is_retryable(
        self,
        before: dict[tuple[int, int], tuple[str, bool, int]],
        after: dict[tuple[int, int], tuple[str, bool, int]],
    ) -> bool:
        changed = False
        for key in set(before) | set(after):
            old = before.get(key)
            new = after.get(key)
            if old == new:
                continue
            changed = True
            reservation = self._reservations.get(key[0])
            if (
                reservation is None
                or reservation.end_ordinal_exclusive != key[1]
                or not reservation.spool_reserved
                or new is None
            ):
                return False
            if old is None:
                continue
            if old[1] and not new[1]:
                continue
            return False
        return changed

    def _missing_temp_was_published(
        self,
        missing_name: str,
        before: dict[tuple[int, int], tuple[str, bool, int]],
        after: dict[tuple[int, int], tuple[str, bool, int]],
    ) -> bool:
        changed_keys = {
            key for key in set(before) | set(after) if before.get(key) != after.get(key)
        }
        if not changed_keys:
            return False
        matching_keys = tuple(
            key
            for key in changed_keys
            if before.get(key) is not None and before[key][0] == missing_name
        )
        if len(matching_keys) != 1:
            return False
        missing_key = matching_keys[0]
        old = before[missing_key]
        new = after.get(missing_key)
        reservation = self._reservations.get(missing_key[0])
        if (
            new is None
            or not old[1]
            or new[1]
            or reservation is None
            or reservation.end_ordinal_exclusive != missing_key[1]
            or not reservation.spool_reserved
        ):
            return False
        other_changed_keys = changed_keys - {missing_key}
        if not other_changed_keys:
            return True
        other_before = {
            key: value for key, value in before.items() if key != missing_key
        }
        other_after = {
            key: value for key, value in after.items() if key != missing_key
        }
        return self._active_inventory_transition_is_retryable(
            other_before,
            other_after,
        )

    def _account_stable_spool_inventory(
        self,
        classified: dict[tuple[int, int], tuple[str, bool, int]],
        observed_by_name: dict[str, os.stat_result],
    ) -> tuple[int, int, int]:
        actual_by_range: dict[tuple[int, int], int] = {}
        for key, (name, _temporary, quota) in classified.items():
            observed = observed_by_name[name]
            if observed.st_size > quota:
                raise _error("parallel render spool child exceeds its byte quota")
            actual_by_range[key] = int(observed.st_size)
        actual_bytes = sum(actual_by_range.values())
        reserved_growth = 0
        for reservation in self._reservations.values():
            if reservation.spool_reserved:
                key = (
                    reservation.start_ordinal,
                    reservation.end_ordinal_exclusive,
                )
                reserved_growth += (
                    reservation.spool_byte_quota - actual_by_range.get(key, 0)
                )
        accounted_bytes = actual_bytes + reserved_growth
        if actual_bytes > self._limits.max_spool_bytes:
            raise _error("parallel render actual spool bytes exceed their bound")
        if accounted_bytes > self._limits.max_spool_bytes:
            raise _error(
                "parallel render actual and reserved spool bytes exceed their bound"
            )
        return actual_bytes, reserved_growth, accounted_bytes

    def _inspect_spool_inventory(self) -> tuple[int, int, int]:
        try:
            with _AnchoredSpoolDirectory(
                self._root, "parallel render spool directory"
            ) as anchored:
                if anchored._chain[-1][1] != self._root_identity:
                    raise _error("parallel render spool directory identity changed")
                owner_handle, owner_identity = _open_matching_spool_owner(
                    anchored,
                    self._owner_bytes,
                    delete_access=False,
                )
                try:
                    if owner_identity != self._owner_identity:
                        raise _error("parallel render spool owner identity changed")
                    for _attempt in range(_SPOOL_INVENTORY_STABLE_ATTEMPTS):
                        before_names = self._spool_inventory_names(anchored)
                        before = self._classify_spool_inventory_names(before_names)
                        key_by_name = {
                            value[0]: key for key, value in before.items()
                        }
                        second_by_name: dict[str, os.stat_result] = {}
                        missing_name: str | None = None
                        changed_temporary = False
                        for name in before_names:
                            if name == _SPOOL_OWNER_FILE:
                                continue
                            try:
                                first = anchored.lstat(name)
                                _require_plain_file_stat(
                                    first, "parallel render spool child"
                                )
                                key = key_by_name[name]
                                if first.st_size > before[key][2]:
                                    raise _error(
                                        "parallel render spool child exceeds its byte quota"
                                    )
                                second = anchored.lstat(name)
                                _require_plain_file_stat(
                                    second, "parallel render spool child"
                                )
                                if second.st_size > before[key][2]:
                                    raise _error(
                                        "parallel render spool child exceeds its byte quota"
                                    )
                            except FileNotFoundError:
                                missing_name = name
                                break
                            second_by_name[name] = second
                            if _stat_signature(first) != _stat_signature(second):
                                if before[key][1]:
                                    changed_temporary = True
                                else:
                                    raise _error(
                                        "parallel render published spool changed during inventory"
                                    )
                        after_names = self._spool_inventory_names(anchored)
                        after = self._classify_spool_inventory_names(after_names)
                        if missing_name is not None:
                            if self._missing_temp_was_published(
                                missing_name,
                                before,
                                after,
                            ):
                                continue
                            raise _error(
                                "parallel render spool child vanished during inventory"
                            )
                        if before_names != after_names:
                            if self._active_inventory_transition_is_retryable(
                                before,
                                after,
                            ):
                                continue
                            raise _error(
                                "parallel render spool inventory changed unexpectedly"
                            )
                        if changed_temporary:
                            continue
                        anchored.require_unchanged()
                        return self._account_stable_spool_inventory(
                            before,
                            second_by_name,
                        )
                    raise _error(
                        "parallel render spool inventory did not stabilize after bounded retries"
                    )
                finally:
                    owner_handle.close()
        except GlobalWaterwayPackageError:
            raise
        except OSError as error:
            raise _error("parallel render spool inventory could not be verified") from error
        raise AssertionError("stable spool inventory returned without an accounting result")

    def _ready_job_fits(self, ready: _ReadyRenderJob) -> bool:
        if ready.point_count > self._limits.max_in_flight_points:
            raise _error("parallel render job points exceed the aggregate bound")
        if len(ready.input_bytes) > self._limits.max_in_flight_input_bytes:
            raise _error("parallel render job input bytes exceed the aggregate bound")
        if ready.spool_byte_quota > self._limits.max_spool_bytes:
            raise _error("parallel render job spool reservation exceeds the aggregate bound")
        points, input_bytes = self._reservation_totals()
        aggregate_blocked = (
            len(self._reservations) >= self._limits.max_in_flight_jobs
            or points + ready.point_count > self._limits.max_in_flight_points
            or input_bytes + len(ready.input_bytes)
            > self._limits.max_in_flight_input_bytes
        )
        if aggregate_blocked:
            if not self._reservations:
                raise _error("parallel render job cannot fit its aggregate bounds")
            return False
        _actual, reserved_growth, accounted = self._inspect_spool_inventory()
        if accounted + ready.spool_byte_quota > self._limits.max_spool_bytes:
            if self._reservations:
                return False
            raise _error("parallel render spool reservation exceeds its bound")
        try:
            free_bytes = self._disk_usage(self._root).free
        except (AttributeError, OSError, TypeError, ValueError) as error:
            raise _error("parallel render spool capacity could not be measured") from error
        if type(free_bytes) is not int or free_bytes < 0:
            raise _error("parallel render spool capacity is invalid")
        required_free = (
            _DESTINATION_FREE_SPACE_RESERVE
            + reserved_growth
            + ready.spool_byte_quota
        )
        if free_bytes < required_free:
            if self._reservations:
                return False
            raise _error("parallel render spool capacity cannot retain the destination reserve")
        return True

    def _submit_ready_job(self, ready: _ReadyRenderJob) -> None:
        key = (ready.start_ordinal, ready.end_ordinal_exclusive)
        if ready.start_ordinal in self._reservations or key in self._known_ranges:
            raise _error("parallel render attempted to reserve a duplicate ordinal range")
        reservation = _RenderReservation(
            start_ordinal=ready.start_ordinal,
            end_ordinal_exclusive=ready.end_ordinal_exclusive,
            features=ready.features,
            point_count=ready.point_count,
            input_byte_count=len(ready.input_bytes),
            source_range_sha256=ready.source_range_sha256,
            spool_byte_quota=ready.spool_byte_quota,
        )
        self._reservations[ready.start_ordinal] = reservation
        self._known_ranges[key] = ready.spool_byte_quota
        future = self._executor.submit(render_feature_batch_job, ready.input_bytes)
        if future in self._future_to_start:
            raise _error("parallel render executor returned a duplicate future")
        self._future_to_start[future] = ready.start_ordinal
        self._ready_job = None

    def _submit_until_blocked(self) -> None:
        while len(self._reservations) < self._limits.max_in_flight_jobs:
            ready = self._build_ready_job()
            if ready is None:
                return
            if not self._ready_job_fits(ready):
                return
            self._submit_ready_job(ready)

    def _accept_completed_future(self, future: object) -> None:
        try:
            start = self._future_to_start[future]
        except KeyError as error:
            raise _error("parallel render waiter returned an unknown child future") from error
        reservation = self._reservations[start]
        descriptor = future.result()
        if type(descriptor) is not SpoolDescriptor:
            raise _error("parallel render worker returned a malformed descriptor")
        expected_range = (
            reservation.start_ordinal,
            reservation.end_ordinal_exclusive,
        )
        if (descriptor.start_ordinal, descriptor.end_ordinal_exclusive) != expected_range:
            raise _error("parallel render descriptor ordinal range differs")
        if descriptor.file_name != _expected_batch_file_name(*expected_range):
            raise _error("parallel render descriptor name lies outside its owned range")
        if descriptor.point_count != reservation.point_count:
            raise _error("parallel render descriptor point count differs")
        if descriptor.source_range_sha256 != reservation.source_range_sha256:
            raise _error("parallel render descriptor source-range SHA-256 differs")
        if descriptor.byte_count > reservation.spool_byte_quota:
            raise _error("parallel render descriptor exceeds its reserved spool bytes")
        frames = read_feature_batch(
            self._root,
            descriptor,
            expected_render_run_identity_sha256=self._render_run_identity_sha256,
            expected_source_range_sha256=reservation.source_range_sha256,
        )
        if tuple(frame.ordinal for frame in frames) != tuple(
            range(reservation.start_ordinal, reservation.end_ordinal_exclusive)
        ):
            raise _error("parallel render frames are not the reserved contiguous range")
        self._inspect_spool_inventory()
        reservation.spool_reserved = False
        self._completed[start] = _CompletedRenderBatch(descriptor, frames)
        del self._future_to_start[future]

    def _collect_completed(self) -> None:
        done = []
        for future, start in self._future_to_start.items():
            if future.done():
                done.append((start, future))
        for _start, future in sorted(done, key=lambda row: row[0]):
            self._accept_completed_future(future)

    def _wait_for_one_completion(self) -> None:
        futures = tuple(self._future_to_start)
        if not futures:
            raise _error("parallel render has no future for its ordinal gap")
        waited = self._wait_for_futures(
            futures,
            return_when=FIRST_COMPLETED,
        )
        try:
            completed_values, _pending_values = waited
            done = tuple(completed_values)
        except (TypeError, ValueError) as error:
            raise _error("parallel render waiter returned malformed completed futures") from error
        if not done:
            raise _error("parallel render waiter returned no completed future")
        known = set(futures)
        if any(future not in known or not future.done() for future in done):
            raise _error("parallel render waiter returned an unknown or incomplete future")
        for future in sorted(done, key=lambda value: self._future_to_start[value]):
            self._accept_completed_future(future)
        self._collect_completed()

    def _take_next_completed(
        self,
    ) -> tuple[
        SpoolDescriptor,
        tuple[ExactWaterwayFeature, ...],
        tuple[FeatureRenderFrame, ...],
    ]:
        start = self._next_yield_ordinal
        completed = self._completed[start]
        reservation = self._reservations[start]
        if reservation.spool_reserved:
            raise _error("parallel render attempted to yield an unverified spool")
        if completed.descriptor.end_ordinal_exclusive != reservation.end_ordinal_exclusive:
            raise _error("parallel render completed range differs from its reservation")
        self._inspect_spool_inventory()
        self._remember_yielded_spool(completed.descriptor)
        del self._completed[start]
        del self._reservations[start]
        self._next_yield_ordinal = reservation.end_ordinal_exclusive
        return completed.descriptor, reservation.features, completed.frames

    def _remember_yielded_spool(self, descriptor: SpoolDescriptor) -> None:
        if descriptor.file_name in self._yielded_spools:
            raise _error("parallel render yielded the same spool more than once")
        with _AnchoredSpoolDirectory(
            self._root, "parallel render spool directory"
        ) as anchored:
            if anchored._chain[-1][1] != self._root_identity:
                raise _error("parallel render spool directory identity changed")
            owner_handle, owner_identity = _open_matching_spool_owner(
                anchored,
                self._owner_bytes,
                delete_access=False,
            )
            try:
                if owner_identity != self._owner_identity:
                    raise _error("parallel render spool owner identity changed")
                spool_handle, file_identity = anchored.open_existing(
                    descriptor.file_name,
                    label="parallel render yielded spool",
                    delete_access=False,
                )
                try:
                    observed = os.fstat(spool_handle.fileno())
                    if observed.st_size != descriptor.byte_count:
                        raise _error(
                            "parallel render yielded spool byte count changed"
                        )
                finally:
                    spool_handle.close()
            finally:
                owner_handle.close()
        self._yielded_spools[descriptor.file_name] = _YieldedSpool(
            descriptor=descriptor,
            file_identity=file_identity,
        )

    def release_batch(self, descriptor: SpoolDescriptor) -> None:
        """Delete one yielded spool after its parent checkpoint is committed."""

        if self._failed:
            raise _error("parallel render preserves yielded spools after failure")
        if type(descriptor) is not SpoolDescriptor:
            raise _error("parallel render release requires a yielded descriptor")
        yielded = self._yielded_spools.get(descriptor.file_name)
        if yielded is None or yielded.descriptor != descriptor:
            raise _error("parallel render release requires an exact yielded spool")
        key = (descriptor.start_ordinal, descriptor.end_ordinal_exclusive)
        if key not in self._known_ranges:
            raise _error("parallel render yielded spool lacks its reserved range")
        self._inspect_spool_inventory()
        removed = False
        try:
            with _AnchoredSpoolDirectory(
                self._root, "parallel render spool directory"
            ) as anchored:
                if anchored._chain[-1][1] != self._root_identity:
                    raise _error("parallel render spool directory identity changed")
                owner_handle, owner_identity = _open_matching_spool_owner(
                    anchored,
                    self._owner_bytes,
                    delete_access=False,
                )
                try:
                    if owner_identity != self._owner_identity:
                        raise _error("parallel render spool owner identity changed")
                    spool_handle, file_identity = anchored.open_existing(
                        descriptor.file_name,
                        label="parallel render yielded spool",
                        delete_access=True,
                    )
                    try:
                        if file_identity != yielded.file_identity:
                            raise _error(
                                "parallel render yielded spool identity changed before release"
                            )
                        observed = os.fstat(spool_handle.fileno())
                        if observed.st_size != descriptor.byte_count:
                            raise _error(
                                "parallel render yielded spool byte count changed before release"
                            )
                        _read_feature_batch_handle(
                            spool_handle,
                            descriptor,
                            expected_render_run_identity_sha256=(
                                self._render_run_identity_sha256
                            ),
                            expected_source_range_sha256=(
                                descriptor.source_range_sha256
                            ),
                            expected_stat=observed,
                        )
                        self._inspect_spool_inventory()
                        anchored.dispose_existing_open_file(
                            descriptor.file_name,
                            spool_handle,
                            file_identity,
                            label="parallel render yielded spool",
                        )
                        spool_handle = None
                        removed = True
                    finally:
                        if spool_handle is not None:
                            spool_handle.close()
                finally:
                    owner_handle.close()
        finally:
            if removed:
                del self._yielded_spools[descriptor.file_name]
                removed_quota = self._known_ranges.pop(key, None)
                if removed_quota is None:
                    raise _error(
                        "parallel render released range lacked its reservation"
                    )

    def _shutdown(self, *, cancel_futures: bool) -> None:
        if self._closed:
            return
        if cancel_futures:
            for future in tuple(self._future_to_start):
                future.cancel()
        self._executor.shutdown(
            wait=True,
            cancel_futures=cancel_futures,
        )
        self._closed = True

    def _abort(self, exception: BaseException | None = None) -> None:
        self._failed = True
        try:
            self._shutdown(cancel_futures=True)
        except BaseException as shutdown_error:
            combined = _error(
                "parallel render executor shutdown failed; worker quiescence is "
                "unproven and spool recovery is required"
            )
            if exception is not None:
                combined.add_note(f"primary parallel render failure: {exception}")
            combined.add_note(
                f"parallel render executor shutdown failure: {shutdown_error}"
            )
            raise combined from shutdown_error

    def close(self) -> None:
        if not self._closed:
            self._abort()

    def next_batch(
        self,
    ) -> tuple[
        SpoolDescriptor,
        tuple[ExactWaterwayFeature, ...],
        tuple[FeatureRenderFrame, ...],
    ] | None:
        if self._failed:
            raise _error("parallel render scheduler cannot continue after failure")
        if self._closed:
            return None
        try:
            while True:
                self._collect_completed()
                if self._next_yield_ordinal in self._completed:
                    return self._take_next_completed()
                self._submit_until_blocked()
                self._collect_completed()
                if self._next_yield_ordinal in self._completed:
                    return self._take_next_completed()
                if not self._reservations and self._input_exhausted:
                    self._inspect_spool_inventory()
                    self._shutdown(cancel_futures=False)
                    return None
                self._wait_for_one_completion()
        except BaseException as error:
            self._abort(error)
            raise


__all__ = [
    "FeatureRenderBatchJob",
    "FeatureRenderFrame",
    "DurableProgressFile",
    "ParallelFeatureRenderer",
    "ParallelRenderLimits",
    "RecordingHotIdRegistry",
    "RegistryClaim",
    "SpoolDescriptor",
    "decode_feature_batch_job",
    "encode_feature_batch_job",
    "finish_spool_directory",
    "maximum_parallel_render_workers",
    "prepare_spool_directory",
    "read_feature_batch",
    "render_feature_batch_job",
    "replay_registry_claims",
    "validate_and_decode_record_rows",
    "validate_frame_against_exact_source",
]
