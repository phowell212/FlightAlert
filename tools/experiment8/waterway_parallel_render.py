from __future__ import annotations

import hashlib
import os
import stat
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO, Iterable, Sequence

from .model import TileKey
from .osm_global_waterway_package import GlobalWaterwayPackageError
from .osm_global_waterway_renderer import (
    ExactWaterwayFeature,
    ExactWaterwayPoint,
    build_adaptive_waterway_feature,
)
from .renderer_tile_package import (
    MAX_RENDERER_RECORD_BYTES,
    MAX_SOURCED_TEXT_RECORD_BYTES,
)
from .semantic_model import (
    HotIdRegistry,
    renderer_record_bytes,
    variant_fingerprint,
)


_JOB_MAGIC = b"FAE8WRJOB"
_JOB_VERSION = 1
_BATCH_MAGIC = b"FAE8WRBATCH"
_BATCH_VERSION = 1
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
class FeatureRenderBatchJob:
    start_ordinal: int
    features: tuple[ExactWaterwayFeature, ...]
    source_generation_sha256: str
    classifier_sha256: str
    zooms: tuple[int, ...]
    render_run_identity_sha256: str
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
        rendered_feature_row=rendered_feature_row,
        registry_claims=claims,
        identity_rows=tuple(identity_rows),
        record_rows=record_rows,
        posting_bytes=posting_bytes,
    )


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


def _initial_frame_encoded_bytes(
    exact: ExactWaterwayFeature,
    registry_claims: tuple[RegistryClaim, ...],
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
    budget = _FrameByteBudget(
        maximum_encoded_bytes,
        _initial_frame_encoded_bytes(exact, registry_claims),
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


def _windows_file_rename_info_buffer(destination: Path) -> object:
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
    information.replace_if_exists = False
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
    end_ordinal = job.start_ordinal + len(job.features)
    file_name = _expected_batch_file_name(job.start_ordinal, end_ordinal)
    temporary_name = f"{file_name}.tmp-{os.getpid()}"
    source_range_sha256 = _source_range_sha256(job.features)
    point_count = sum(_feature_point_count(feature) for feature in job.features)
    try:
        with _AnchoredSpoolDirectory(
            spool_root, "feature batch spool directory"
        ) as anchored_spool:
            handle: BinaryIO | None = None
            owned_identity: tuple[int, int] | None = None
            try:
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
        if (
            not 1 <= frame_count <= _MAX_BATCH_FEATURES
            or frame_count != expected_count
        ):
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
        if _stat_signature(after_handle) != _stat_signature(before):
            raise _error("feature batch spool changed while reading")
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


__all__ = [
    "FeatureRenderBatchJob",
    "FeatureRenderFrame",
    "RecordingHotIdRegistry",
    "RegistryClaim",
    "SpoolDescriptor",
    "decode_feature_batch_job",
    "encode_feature_batch_job",
    "read_feature_batch",
    "render_feature_batch_job",
    "replay_registry_claims",
]
