from __future__ import annotations

import hashlib
import math
import struct
import unicodedata
from dataclasses import dataclass, fields
from enum import IntEnum
from typing import Callable, Iterable, Mapping

from .model import TileKey


CANONICAL_MAGIC = b"N8T1"
MAX_GEOMETRY_POINTS = 1_048_576
MAX_RENDERER_HEAP_BYTES = 33_554_432
MAX_PLACEMENT_MEMBERSHIPS = 4
MAX_CANONICAL_STRING_BYTES = 1_048_576
MAX_CANONICAL_TABLE_REFERENCES = 262_144

HOT_RENDERER_BASE_HEAP_BYTES = 256
GEOMETRY_POINT_HEAP_BYTES = 16
GEOMETRY_PART_HEAP_BYTES = 4
STYLE_REFERENCE_HEAP_BYTES = 8

_I32_MIN = -(1 << 31)
_I32_MAX = (1 << 31) - 1
_I64_MIN = -(1 << 63)
_I64_MAX = (1 << 63) - 1
_U32_MAX = (1 << 32) - 1
_ZERO_SHA256 = b"\x00" * 32

_SOURCE_GEOMETRY_TAG = 1
_RENDERER_GEOMETRY_TAG = 2
_SOURCE_OCCURRENCE_TAG = 3
_CANONICAL_VARIANT_PAYLOAD_TAG = 4
_TILE_POSTING_TAG = 5
_LINE_LABEL_CANDIDATE_TAG = 6
_ENCODED_CANONICAL_VARIANT_TAG = 7
_RENDERER_RECORD_TAG = 8
_IDENTITY_EVIDENCE_TAG = 0x33
_SOURCE_AUDIT_TAG = 0x34


class SemanticModelError(ValueError):
    """A typed semantic record violates the renderer contract."""


class CanonicalEncodingError(SemanticModelError):
    """Canonical bytes are malformed or cannot represent the requested value."""


class IdentityCollisionError(SemanticModelError):
    """Unequal canonical values share one hot 64-bit identifier."""


class HeapLimitError(SemanticModelError):
    """A reconstructed renderer result exceeds its exact heap ceiling."""


class LayerGroup(IntEnum):
    PLACES = 1
    WATER = 2
    REGIONS = 3
    PUBLIC_LANDS = 4
    TRANSPORTATION = 5
    CONTEXT = 6


class FeatureKind(IntEnum):
    LABEL = 1
    LINE = 2
    POLYGON_OUTLINE = 3


class GeometryKind(IntEnum):
    POINT = 1
    PATH = 2
    POLYGON = 3


class TextEvidenceKind(IntEnum):
    NONE = 0
    SOURCE_FIELD = 1
    PINNED_STYLE = 2
    FLIGHT_ALERT_POLICY = 3
    PROVIDER_STABLE_JOIN = 4


class ProjectionMode(IntEnum):
    DIRECT_SOURCE_PATH = 1
    EXACT_PARENT_PATH = 2


class LandEvidence(IntEnum):
    NOT_APPLICABLE = 0
    SOURCE_EXPLICIT = 1
    NAME_DERIVED = 2
    AMBIGUOUS = 3


class ProtectedStatus(IntEnum):
    NOT_APPLICABLE = 0
    SOURCE_EXPLICIT = 1
    NAME_DERIVED = 2
    AMBIGUOUS = 3


DigestFunction = Callable[[bytes], bytes]


@dataclass(frozen=True, slots=True)
class Fingerprint:
    full_sha256: bytes
    hot_id: int

    def __post_init__(self) -> None:
        _require_sha256_bytes(self.full_sha256, "full SHA-256")
        _require_uint(self.hot_id, 64, "hot ID")


@dataclass(frozen=True, slots=True)
class IdentityEvidence:
    domain: bytes
    full_sha256: bytes
    hot_id: int
    canonical_preimage: bytes

    def __post_init__(self) -> None:
        if type(self.domain) is not bytes or not self.domain.endswith(b"\0"):
            raise SemanticModelError("identity evidence domain must end in NUL")
        _require_sha256_bytes(self.full_sha256, "identity evidence full SHA-256")
        _require_uint(self.hot_id, 64, "identity evidence hot ID")
        if type(self.canonical_preimage) is not bytes:
            raise SemanticModelError("identity evidence preimage must be immutable bytes")
        expected = _digest(self.domain, self.canonical_preimage)
        if self.full_sha256 != expected.full_sha256:
            raise SemanticModelError("identity evidence full SHA-256 does not match preimage")
        if self.hot_id != expected.hot_id:
            raise SemanticModelError("identity evidence hot ID does not match full SHA-256")

    @classmethod
    def from_preimage(cls, domain: bytes, canonical_preimage: bytes) -> "IdentityEvidence":
        identity = _digest(domain, canonical_preimage)
        return cls(domain, identity.full_sha256, identity.hot_id, canonical_preimage)


def _require_int(
    value: object,
    minimum: int,
    maximum: int,
    label: str,
) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise SemanticModelError(
            f"{label} must be an integer in [{minimum}, {maximum}], got {value!r}"
        )
    return value


def _require_uint(value: object, bits: int, label: str) -> int:
    return _require_int(value, 0, (1 << bits) - 1, label)


def _require_bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise SemanticModelError(f"{label} must be a boolean, got {value!r}")
    return value


def _require_tile(value: object, label: str) -> TileKey:
    if not isinstance(value, TileKey):
        raise SemanticModelError(f"{label} must be a TileKey, got {value!r}")
    if any(type(component) is not int for component in (value.z, value.x, value.y)):
        raise SemanticModelError(f"{label} coordinates must be exact integers")
    try:
        TileKey(value.z, value.x, value.y)
    except ValueError as error:
        raise SemanticModelError(f"invalid {label}: {error}") from error
    return value


def _require_enum(value: object, enum_type: type[IntEnum], label: str) -> IntEnum:
    if type(value) is not enum_type:
        raise SemanticModelError(f"{label} must be {enum_type.__name__}, got {value!r}")
    return value


def _require_tuple(value: object, label: str) -> tuple[object, ...]:
    if type(value) is not tuple:
        raise SemanticModelError(f"{label} must be an immutable tuple")
    return value


def _require_sha256_bytes(value: object, label: str) -> bytes:
    if type(value) is not bytes or len(value) != 32:
        raise SemanticModelError(f"{label} must be exactly 32 bytes")
    return value


def _require_sha256_hex(value: object, label: str) -> str:
    if type(value) is not str or len(value) != 64:
        raise SemanticModelError(f"{label} must be 64 lowercase hexadecimal digits")
    try:
        decoded = bytes.fromhex(value)
    except ValueError as error:
        raise SemanticModelError(
            f"{label} must be 64 lowercase hexadecimal digits"
        ) from error
    if len(decoded) != 32 or value != value.lower():
        raise SemanticModelError(f"{label} must be 64 lowercase hexadecimal digits")
    return value


def _require_nfc(value: object, label: str, *, allow_empty: bool = True) -> str:
    if type(value) is not str:
        raise CanonicalEncodingError(f"{label} must be a string")
    if not allow_empty and not value:
        raise CanonicalEncodingError(f"{label} must not be empty")
    if unicodedata.normalize("NFC", value) != value:
        raise CanonicalEncodingError(f"{label} must already be NFC; implicit rewriting is forbidden")
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise CanonicalEncodingError(f"{label} is not valid UTF-8 text") from error
    return value


def _digest(
    domain: bytes,
    canonical_bytes: bytes,
    digest_function: DigestFunction | None = None,
) -> Fingerprint:
    if type(domain) is not bytes or not domain.endswith(b"\0"):
        raise CanonicalEncodingError("digest domain must be bytes ending in NUL")
    function = digest_function or (lambda value: hashlib.sha256(value).digest())
    full_digest = function(domain + canonical_bytes)
    _require_sha256_bytes(full_digest, "digest function result")
    return Fingerprint(
        full_sha256=full_digest,
        hot_id=int.from_bytes(full_digest[:8], "big", signed=False),
    )


class HotIdRegistry:
    """Rejects 64-bit collisions without rewriting or merging unequal bytes."""

    def __init__(self, digest_function: DigestFunction | None = None) -> None:
        self._digest_function = digest_function
        self._entries: dict[int, tuple[bytes, bytes, bytes]] = {}

    def register(self, domain: bytes, canonical_bytes: bytes) -> Fingerprint:
        identity = _digest(domain, canonical_bytes)
        collision_probe = (
            _digest(domain, canonical_bytes, self._digest_function)
            if self._digest_function is not None
            else identity
        )
        candidate = (identity.full_sha256, domain, canonical_bytes)
        previous = self._entries.get(collision_probe.hot_id)
        if previous is not None and previous != candidate:
            raise IdentityCollisionError(
                "fatal 64-bit identity collision between unequal canonical bytes"
            )
        self._entries[collision_probe.hot_id] = candidate
        return identity


class _Writer:
    def __init__(self) -> None:
        self.data = bytearray()

    def raw(self, value: bytes) -> None:
        self.data.extend(value)

    def u8(self, value: int) -> None:
        self.data.extend(struct.pack("<B", _require_uint(value, 8, "u8")))

    def u32(self, value: int) -> None:
        self.data.extend(struct.pack("<I", _require_uint(value, 32, "u32")))

    def u64(self, value: int) -> None:
        self.data.extend(struct.pack("<Q", _require_uint(value, 64, "u64")))

    def i32(self, value: int) -> None:
        self.data.extend(struct.pack("<i", _require_int(value, _I32_MIN, _I32_MAX, "i32")))

    def i64(self, value: int) -> None:
        self.data.extend(struct.pack("<q", _require_int(value, _I64_MIN, _I64_MAX, "i64")))

    def boolean(self, value: bool) -> None:
        self.u8(1 if _require_bool(value, "boolean") else 0)

    def blob(self, value: bytes) -> None:
        if type(value) is not bytes:
            raise CanonicalEncodingError("canonical blob must be immutable bytes")
        self.u32(len(value))
        self.raw(value)

    def text(self, value: str, label: str = "text") -> None:
        encoded = _require_nfc(value, label).encode("utf-8")
        if len(encoded) > MAX_CANONICAL_STRING_BYTES:
            raise CanonicalEncodingError(
                f"{label} exceeds canonical UTF-8 byte ceiling {MAX_CANONICAL_STRING_BYTES}"
            )
        self.blob(encoded)

    def tile(self, value: TileKey) -> None:
        self.u64(_require_tile(value, "tile").packed)

    def finish(self) -> bytes:
        return bytes(self.data)


class _Reader:
    def __init__(self, data: bytes) -> None:
        if type(data) is not bytes:
            raise CanonicalEncodingError("canonical input must be immutable bytes")
        self.data = data
        self.offset = 0

    @property
    def remaining(self) -> int:
        return len(self.data) - self.offset

    def take(self, length: int) -> bytes:
        if type(length) is not int or length < 0 or length > self.remaining:
            raise CanonicalEncodingError("truncated canonical record")
        start = self.offset
        self.offset += length
        return self.data[start : self.offset]

    def _unpack(self, format_text: str, length: int) -> int:
        return int(struct.unpack(format_text, self.take(length))[0])

    def u8(self) -> int:
        return self._unpack("<B", 1)

    def u32(self) -> int:
        return self._unpack("<I", 4)

    def u64(self) -> int:
        return self._unpack("<Q", 8)

    def i32(self) -> int:
        return self._unpack("<i", 4)

    def i64(self) -> int:
        return self._unpack("<q", 8)

    def boolean(self) -> bool:
        value = self.u8()
        if value not in (0, 1):
            raise CanonicalEncodingError(f"invalid canonical boolean: {value}")
        return bool(value)

    def blob(self) -> bytes:
        return self.take(self.u32())

    def text(self, label: str = "text") -> str:
        length = self.u32()
        if length > MAX_CANONICAL_STRING_BYTES:
            raise CanonicalEncodingError(
                f"{label} exceeds canonical UTF-8 byte ceiling {MAX_CANONICAL_STRING_BYTES}"
            )
        try:
            value = self.take(length).decode("utf-8", "strict")
        except UnicodeDecodeError as error:
            raise CanonicalEncodingError(f"{label} is not valid UTF-8") from error
        return _require_nfc(value, label)

    def tile(self) -> TileKey:
        packed = self.u64()
        try:
            return TileKey.from_packed(packed)
        except ValueError as error:
            raise CanonicalEncodingError(f"invalid packed tile key: {packed}") from error

    def header(self, tag: int) -> None:
        if self.take(len(CANONICAL_MAGIC)) != CANONICAL_MAGIC:
            raise CanonicalEncodingError("canonical magic mismatch")
        actual = self.u8()
        if actual != tag:
            raise CanonicalEncodingError(
                f"canonical record tag mismatch: expected {tag}, got {actual}"
            )

    def finish(self) -> None:
        if self.remaining:
            raise CanonicalEncodingError(
                f"canonical record has {self.remaining} trailing byte(s)"
            )


def _record_writer(tag: int) -> _Writer:
    writer = _Writer()
    writer.raw(CANONICAL_MAGIC)
    writer.u8(tag)
    return writer


def encode_identity_evidence(evidence: IdentityEvidence) -> bytes:
    if not isinstance(evidence, IdentityEvidence):
        raise SemanticModelError("identity evidence encoder requires IdentityEvidence")
    writer = _record_writer(_IDENTITY_EVIDENCE_TAG)
    writer.blob(evidence.domain)
    writer.raw(evidence.full_sha256)
    writer.u64(evidence.hot_id)
    writer.blob(evidence.canonical_preimage)
    return writer.finish()


def decode_identity_evidence(data: bytes) -> IdentityEvidence:
    reader = _Reader(data)
    reader.header(_IDENTITY_EVIDENCE_TAG)
    domain = reader.blob()
    full_sha256 = reader.take(32)
    hot_id = reader.u64()
    preimage = reader.blob()
    reader.finish()
    return IdentityEvidence(domain, full_sha256, hot_id, preimage)


def source_audit_sha256(evidence_items: Iterable[IdentityEvidence]) -> str:
    encoded_items: list[bytes] = []
    seen: set[tuple[bytes, int]] = set()
    for evidence in evidence_items:
        if not isinstance(evidence, IdentityEvidence):
            raise SemanticModelError("source audit requires IdentityEvidence records")
        key = (evidence.domain, evidence.hot_id)
        if key in seen:
            raise SemanticModelError("duplicate identity evidence in source audit")
        seen.add(key)
        encoded_items.append(encode_identity_evidence(evidence))
    encoded_items.sort()
    writer = _record_writer(_SOURCE_AUDIT_TAG)
    writer.u32(len(encoded_items))
    for encoded in encoded_items:
        writer.blob(encoded)
    return hashlib.sha256(b"exp8-source-audit-v1\0" + writer.finish()).hexdigest()


def canonical_typed_properties_bytes(properties: Mapping[str, object]) -> bytes:
    if not isinstance(properties, Mapping):
        raise CanonicalEncodingError("typed properties must be a mapping")
    encoded_items: list[tuple[bytes, bytes]] = []
    for key, value in properties.items():
        key_text = _require_nfc(key, "property key", allow_empty=False)
        key_bytes = key_text.encode("utf-8")
        value_writer = _Writer()
        if value is None:
            value_writer.u8(0)
        elif type(value) is bool:
            value_writer.u8(1)
            value_writer.boolean(value)
        elif type(value) is int:
            value_writer.u8(2)
            value_writer.i64(value)
        elif type(value) is str:
            value_writer.u8(3)
            value_writer.text(value, f"property {key_text!r}")
        elif type(value) is bytes:
            value_writer.u8(4)
            value_writer.blob(value)
        elif type(value) is float:
            raise CanonicalEncodingError(
                f"property {key_text!r} is a float; use a fixed integer"
            )
        else:
            raise CanonicalEncodingError(
                f"property {key_text!r} has unsupported typed value {type(value).__name__}"
            )
        encoded_items.append((key_bytes, value_writer.finish()))
    encoded_items.sort(key=lambda item: item[0])
    writer = _record_writer(0x30)
    writer.u32(len(encoded_items))
    for key_bytes, value_bytes in encoded_items:
        writer.blob(key_bytes)
        writer.blob(value_bytes)
    return writer.finish()


def assign_canonical_ids(values: Iterable[bytes]) -> dict[bytes, int]:
    unique: set[bytes] = set()
    for value in values:
        if type(value) is not bytes:
            raise CanonicalEncodingError("canonical table values must be immutable bytes")
        unique.add(value)
    return {value: index for index, value in enumerate(sorted(unique))}


def _validate_parts_and_coordinates(
    kind: GeometryKind,
    parts: tuple[int, ...],
    coordinates: tuple[int, ...],
    bounds: tuple[int, int, int, int],
) -> None:
    _require_enum(kind, GeometryKind, "geometry kind")
    _require_tuple(parts, "geometry parts")
    _require_tuple(coordinates, "geometry coordinates")
    _require_tuple(bounds, "geometry bounds")
    if len(bounds) != 4:
        raise SemanticModelError("geometry bounds must contain exactly four integers")
    for index, value in enumerate(coordinates):
        _require_int(value, _I64_MIN, _I64_MAX, f"geometry coordinate {index}")
    for index, value in enumerate(bounds):
        _require_int(value, _I64_MIN, _I64_MAX, f"geometry bound {index}")
    if not coordinates or len(coordinates) % 2:
        raise SemanticModelError("geometry coordinates must contain complete x/y pairs")
    point_count = len(coordinates) // 2
    if point_count > MAX_GEOMETRY_POINTS:
        raise SemanticModelError(
            f"geometry exceeds point-count ceiling {MAX_GEOMETRY_POINTS}: {point_count}"
        )
    if not parts or parts[0] != 0:
        raise SemanticModelError("geometry parts must be nonempty and start at point 0")
    previous = -1
    for index, offset in enumerate(parts):
        _require_uint(offset, 32, f"geometry part {index}")
        if offset <= previous or offset >= point_count:
            raise SemanticModelError("geometry part offsets must be strictly increasing and in range")
        previous = offset
    expected_bounds = (
        min(coordinates[0::2]),
        min(coordinates[1::2]),
        max(coordinates[0::2]),
        max(coordinates[1::2]),
    )
    if bounds != expected_bounds:
        raise SemanticModelError(
            f"geometry bounds must be exact: expected {expected_bounds}, got {bounds}"
        )
    ends = parts[1:] + (point_count,)
    for part_index, (start, end) in enumerate(zip(parts, ends)):
        part_points = end - start
        if kind is GeometryKind.POINT and part_points < 1:
            raise SemanticModelError(f"point part {part_index} is empty")
        if kind is GeometryKind.PATH and part_points < 2:
            raise SemanticModelError(f"path part {part_index} must contain at least two points")
        if kind is GeometryKind.POLYGON:
            if part_points < 4:
                raise SemanticModelError(
                    f"polygon ring {part_index} must contain at least four points"
                )
            first = coordinates[start * 2 : start * 2 + 2]
            last = coordinates[(end - 1) * 2 : end * 2]
            if first != last:
                raise SemanticModelError(
                    f"polygon ring {part_index} must remain exactly closed"
                )


@dataclass(frozen=True, slots=True)
class SourceGeometry:
    kind: GeometryKind
    tile_key: TileKey
    source_zoom: int
    declared_extent: int
    parts: tuple[int, ...]
    source_local_coordinates: tuple[int, ...]
    bounds: tuple[int, int, int, int]

    def __post_init__(self) -> None:
        _require_enum(self.kind, GeometryKind, "source geometry kind")
        tile = _require_tile(self.tile_key, "source geometry tile")
        _require_int(self.source_zoom, 0, 29, "source zoom")
        if self.source_zoom != tile.z:
            raise SemanticModelError(
                f"source zoom {self.source_zoom} does not match tile zoom {tile.z}"
            )
        _require_int(self.declared_extent, 1, _I64_MAX, "declared extent")
        denominator = (1 << self.source_zoom) * self.declared_extent
        if denominator > _I64_MAX:
            raise SemanticModelError("world-rational denominator exceeds signed 64-bit range")
        _validate_parts_and_coordinates(
            self.kind,
            self.parts,
            self.source_local_coordinates,
            self.bounds,
        )


@dataclass(frozen=True, slots=True)
class RendererGeometry:
    kind: GeometryKind
    parts: tuple[int, ...]
    world_denominator: int
    world_coordinate_numerators: tuple[int, ...]
    bounds_numerators: tuple[int, int, int, int]

    def __post_init__(self) -> None:
        _require_enum(self.kind, GeometryKind, "renderer geometry kind")
        _require_int(self.world_denominator, 1, _I64_MAX, "world denominator")
        _validate_parts_and_coordinates(
            self.kind,
            self.parts,
            self.world_coordinate_numerators,
            self.bounds_numerators,
        )
        common_divisor = self.world_denominator
        for numerator in self.world_coordinate_numerators:
            common_divisor = math.gcd(common_divisor, abs(numerator))
        if common_divisor != 1:
            raise SemanticModelError(
                "renderer world rationals must use one fully reduced denominator"
            )


def _write_parts_coordinates_bounds(
    writer: _Writer,
    parts: tuple[int, ...],
    coordinates: tuple[int, ...],
    bounds: tuple[int, int, int, int],
) -> None:
    writer.u32(len(parts))
    for part in parts:
        writer.u32(part)
    writer.u32(len(coordinates))
    for coordinate in coordinates:
        writer.i64(coordinate)
    for bound in bounds:
        writer.i64(bound)


def _read_parts_coordinates_bounds(
    reader: _Reader,
) -> tuple[tuple[int, ...], tuple[int, ...], tuple[int, int, int, int]]:
    part_count = reader.u32()
    if part_count > MAX_GEOMETRY_POINTS:
        raise CanonicalEncodingError("geometry part count exceeds point-count ceiling")
    parts = tuple(reader.u32() for _ in range(part_count))
    coordinate_count = reader.u32()
    if coordinate_count > MAX_GEOMETRY_POINTS * 2:
        raise CanonicalEncodingError("geometry coordinate count exceeds point-count ceiling")
    coordinates = tuple(reader.i64() for _ in range(coordinate_count))
    bounds_values = tuple(reader.i64() for _ in range(4))
    bounds = (bounds_values[0], bounds_values[1], bounds_values[2], bounds_values[3])
    return parts, coordinates, bounds


def encode_source_geometry(geometry: SourceGeometry) -> bytes:
    if not isinstance(geometry, SourceGeometry):
        raise SemanticModelError("source geometry encoder requires SourceGeometry")
    writer = _record_writer(_SOURCE_GEOMETRY_TAG)
    writer.u8(geometry.kind.value)
    writer.tile(geometry.tile_key)
    writer.u8(geometry.source_zoom)
    writer.u64(geometry.declared_extent)
    _write_parts_coordinates_bounds(
        writer,
        geometry.parts,
        geometry.source_local_coordinates,
        geometry.bounds,
    )
    return writer.finish()


def decode_source_geometry(data: bytes) -> SourceGeometry:
    reader = _Reader(data)
    reader.header(_SOURCE_GEOMETRY_TAG)
    try:
        kind = GeometryKind(reader.u8())
    except ValueError as error:
        raise CanonicalEncodingError("unknown source geometry kind") from error
    tile = reader.tile()
    source_zoom = reader.u8()
    extent = reader.u64()
    parts, coordinates, bounds = _read_parts_coordinates_bounds(reader)
    reader.finish()
    return SourceGeometry(kind, tile, source_zoom, extent, parts, coordinates, bounds)


def encode_renderer_geometry(geometry: RendererGeometry) -> bytes:
    if not isinstance(geometry, RendererGeometry):
        raise SemanticModelError("renderer geometry encoder requires RendererGeometry")
    writer = _record_writer(_RENDERER_GEOMETRY_TAG)
    writer.u8(geometry.kind.value)
    writer.u64(geometry.world_denominator)
    _write_parts_coordinates_bounds(
        writer,
        geometry.parts,
        geometry.world_coordinate_numerators,
        geometry.bounds_numerators,
    )
    return writer.finish()


def decode_renderer_geometry(data: bytes) -> RendererGeometry:
    reader = _Reader(data)
    reader.header(_RENDERER_GEOMETRY_TAG)
    try:
        kind = GeometryKind(reader.u8())
    except ValueError as error:
        raise CanonicalEncodingError("unknown renderer geometry kind") from error
    denominator = reader.u64()
    parts, coordinates, bounds = _read_parts_coordinates_bounds(reader)
    reader.finish()
    return RendererGeometry(kind, parts, denominator, coordinates, bounds)


def renderer_geometry_from_source(source: SourceGeometry) -> RendererGeometry:
    if not isinstance(source, SourceGeometry):
        raise SemanticModelError("renderer conversion requires SourceGeometry")
    extent = source.declared_extent
    denominator = (1 << source.source_zoom) * extent
    numerators: list[int] = []
    for index in range(0, len(source.source_local_coordinates), 2):
        x = source.tile_key.x * extent + source.source_local_coordinates[index]
        y = source.tile_key.y * extent + source.source_local_coordinates[index + 1]
        _require_int(x, _I64_MIN, _I64_MAX, "world x numerator")
        _require_int(y, _I64_MIN, _I64_MAX, "world y numerator")
        numerators.extend((x, y))
    divisor = denominator
    for numerator in numerators:
        divisor = math.gcd(divisor, abs(numerator))
    reduced_denominator = denominator // divisor
    reduced = tuple(value // divisor for value in numerators)
    bounds = (
        min(reduced[0::2]),
        min(reduced[1::2]),
        max(reduced[0::2]),
        max(reduced[1::2]),
    )
    return RendererGeometry(
        kind=source.kind,
        parts=source.parts,
        world_denominator=reduced_denominator,
        world_coordinate_numerators=reduced,
        bounds_numerators=bounds,
    )


def renderer_geometry_fingerprint(geometry: RendererGeometry) -> Fingerprint:
    return _digest(b"exp8-renderer-geometry-v1\0", encode_renderer_geometry(geometry))


def point_wrap_anchor(geometry: RendererGeometry) -> tuple[int, int, int]:
    if geometry.kind is not GeometryKind.POINT or len(geometry.world_coordinate_numerators) != 2:
        raise SemanticModelError("point wrap identity requires exactly one point")
    x, y = geometry.world_coordinate_numerators
    return x % geometry.world_denominator, y, geometry.world_denominator


def point_label_dedupe_fingerprint(
    layer_group: LayerGroup,
    feature_kind: FeatureKind,
    display_text: str,
    semantic_class_id: int,
    geometry: RendererGeometry,
) -> Fingerprint:
    _require_enum(layer_group, LayerGroup, "point-label layer group")
    _require_enum(feature_kind, FeatureKind, "point-label feature kind")
    if feature_kind is not FeatureKind.LABEL:
        raise SemanticModelError("point-label dedupe requires LABEL feature kind")
    text = _require_nfc(display_text, "point-label display text", allow_empty=False)
    _require_uint(semantic_class_id, 64, "point-label semantic class ID")
    anchor_x, anchor_y, denominator = point_wrap_anchor(geometry)
    writer = _record_writer(0x32)
    writer.u8(layer_group.value)
    writer.u8(feature_kind.value)
    writer.text(text, "point-label display text")
    writer.u64(semantic_class_id)
    writer.i64(anchor_x)
    writer.i64(anchor_y)
    writer.u64(denominator)
    return _digest(b"exp8-point-label-dedupe-v1\0", writer.finish())


def non_point_dedupe_id(feature_id: int, feature_kind: FeatureKind) -> int:
    _require_uint(feature_id, 64, "feature ID")
    _require_enum(feature_kind, FeatureKind, "feature kind")
    if feature_kind is FeatureKind.LABEL:
        raise SemanticModelError("point labels require exact anchor-based dedupe identity")
    return feature_id


def interpolate_fixed_integer(
    start_value: int,
    end_value: int,
    start_position: int,
    end_position: int,
    position: int,
) -> int:
    """Interpolate fixed integers, clamping and rounding ties away from zero."""

    start_value = _require_int(start_value, _I64_MIN, _I64_MAX, "start value")
    end_value = _require_int(end_value, _I64_MIN, _I64_MAX, "end value")
    start_position = _require_int(
        start_position, _I64_MIN, _I64_MAX, "start position"
    )
    end_position = _require_int(end_position, _I64_MIN, _I64_MAX, "end position")
    position = _require_int(position, _I64_MIN, _I64_MAX, "position")
    if end_position <= start_position:
        raise SemanticModelError("interpolation positions must be strictly increasing")
    if position <= start_position:
        return start_value
    if position >= end_position:
        return end_value
    denominator = end_position - start_position
    numerator = (end_value - start_value) * (position - start_position)
    magnitude, remainder = divmod(abs(numerator), denominator)
    if remainder * 2 >= denominator:
        magnitude += 1
    delta = magnitude if numerator >= 0 else -magnitude
    return _require_int(start_value + delta, _I64_MIN, _I64_MAX, "interpolated value")


def _orientation(
    a: tuple[int, int], b: tuple[int, int], c: tuple[int, int]
) -> int:
    value = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    return (value > 0) - (value < 0)


def _on_segment(
    a: tuple[int, int], b: tuple[int, int], point: tuple[int, int]
) -> bool:
    return (
        min(a[0], b[0]) <= point[0] <= max(a[0], b[0])
        and min(a[1], b[1]) <= point[1] <= max(a[1], b[1])
        and _orientation(a, b, point) == 0
    )


def _segments_intersect(
    a: tuple[int, int],
    b: tuple[int, int],
    c: tuple[int, int],
    d: tuple[int, int],
) -> bool:
    o1 = _orientation(a, b, c)
    o2 = _orientation(a, b, d)
    o3 = _orientation(c, d, a)
    o4 = _orientation(c, d, b)
    if o1 != o2 and o3 != o4:
        return True
    return (
        (o1 == 0 and _on_segment(a, b, c))
        or (o2 == 0 and _on_segment(a, b, d))
        or (o3 == 0 and _on_segment(c, d, a))
        or (o4 == 0 and _on_segment(c, d, b))
    )


def geometry_intersects_tile(
    geometry: RendererGeometry,
    tile: TileKey,
    *,
    world_wrap: int = 0,
) -> bool:
    if not isinstance(geometry, RendererGeometry):
        raise SemanticModelError("intersection requires RendererGeometry")
    tile = _require_tile(tile, "intersection tile")
    _require_int(world_wrap, _I32_MIN, _I32_MAX, "world wrap")
    scale = 1 << tile.z
    denominator = geometry.world_denominator
    left = (tile.x + world_wrap * scale) * denominator
    right = left + denominator
    top = tile.y * denominator
    bottom = top + denominator
    points = tuple(
        (
            geometry.world_coordinate_numerators[index] * scale,
            geometry.world_coordinate_numerators[index + 1] * scale,
        )
        for index in range(0, len(geometry.world_coordinate_numerators), 2)
    )

    def inside(point: tuple[int, int]) -> bool:
        return left <= point[0] <= right and top <= point[1] <= bottom

    if any(inside(point) for point in points):
        return True
    if geometry.kind is GeometryKind.POINT:
        return False
    rectangle_edges = (
        ((left, top), (right, top)),
        ((right, top), (right, bottom)),
        ((right, bottom), (left, bottom)),
        ((left, bottom), (left, top)),
    )
    ends = geometry.parts[1:] + (len(points),)
    for start, end in zip(geometry.parts, ends):
        for index in range(start, end - 1):
            segment_start = points[index]
            segment_end = points[index + 1]
            if any(
                _segments_intersect(segment_start, segment_end, edge_start, edge_end)
                for edge_start, edge_end in rectangle_edges
            ):
                return True
    return False


@dataclass(frozen=True, slots=True)
class NormalizedPlacement:
    label_candidate_id: int
    label_candidate_sha256: bytes
    source_feature_sha256: bytes
    placement_geometry_sha256: bytes
    text_evidence_kind: TextEvidenceKind
    text_source_field_id: int
    placement_source_feature_id: int
    placement_geometry_id: int
    source_tile: TileKey
    source_zoom: int
    source_declared_extent: int
    source_edge_domain: tuple[int, int, int, int]
    projection_mode: ProjectionMode
    display_min_zoom_centi: int
    display_max_zoom_centi: int
    spacing_px: int
    max_angle_degrees: int
    collision_group: int
    priority: int
    avoid_edges: bool
    keep_upright: bool
    active_band_limit: int
    style_policy_sha256: bytes
    provider_feature_id: int | None

    def __post_init__(self) -> None:
        _require_uint(self.label_candidate_id, 64, "label candidate ID")
        _require_sha256_bytes(self.label_candidate_sha256, "label candidate SHA-256")
        _require_sha256_bytes(self.source_feature_sha256, "source feature SHA-256")
        _require_sha256_bytes(self.placement_geometry_sha256, "placement geometry SHA-256")
        _require_enum(self.text_evidence_kind, TextEvidenceKind, "text evidence kind")
        _require_uint(self.text_source_field_id, 64, "text source field ID")
        _require_uint(self.placement_source_feature_id, 64, "placement source feature ID")
        _require_uint(self.placement_geometry_id, 64, "placement geometry ID")
        tile = _require_tile(self.source_tile, "placement source tile")
        _require_int(self.source_zoom, 0, 29, "placement source zoom")
        if tile.z != self.source_zoom:
            raise SemanticModelError("placement source zoom must equal source tile zoom")
        _require_int(
            self.source_declared_extent,
            1,
            _I64_MAX,
            "placement source declared extent",
        )
        domain = _require_tuple(self.source_edge_domain, "source edge domain")
        if len(domain) != 4:
            raise SemanticModelError("source edge domain must contain four signed integers")
        for index, value in enumerate(domain):
            _require_int(value, _I64_MIN, _I64_MAX, f"source edge domain {index}")
        if domain[0] > domain[2] or domain[1] > domain[3]:
            raise SemanticModelError("source edge domain bounds are reversed")
        _require_enum(self.projection_mode, ProjectionMode, "projection mode")
        if self.text_evidence_kind is TextEvidenceKind.NONE:
            expected_non_applicable = (
                self.label_candidate_id == 0
                and self.label_candidate_sha256 == _ZERO_SHA256
                and self.source_feature_sha256 == _ZERO_SHA256
                and self.placement_geometry_sha256 == _ZERO_SHA256
                and self.text_source_field_id == 0
                and self.placement_source_feature_id == 0
                and self.placement_geometry_id == 0
                and self.source_tile == TileKey(0, 0, 0)
                and self.source_zoom == 0
                and self.source_declared_extent == 1
                and self.source_edge_domain == (0, 0, 0, 0)
                and self.projection_mode is ProjectionMode.DIRECT_SOURCE_PATH
                and self.display_min_zoom_centi == 0
                and self.display_max_zoom_centi == 0
                and self.spacing_px == 0
                and self.max_angle_degrees == 0
                and self.collision_group == 0
                and self.priority == 0
                and self.avoid_edges is False
                and self.keep_upright is False
                and self.active_band_limit == 0
                and self.style_policy_sha256 == _ZERO_SHA256
                and self.provider_feature_id is None
            )
            if not expected_non_applicable:
                raise SemanticModelError(
                    "unlabeled records require the one canonical non-applicable placement"
                )
            return
        expected_source_hot = int.from_bytes(self.source_feature_sha256[:8], "big")
        if self.placement_source_feature_id != expected_source_hot:
            raise SemanticModelError(
                "placement source feature full digest does not match its hot ID"
            )
        expected_geometry_hot = int.from_bytes(
            self.placement_geometry_sha256[:8], "big"
        )
        if self.placement_geometry_id != expected_geometry_hot:
            raise SemanticModelError(
                "placement geometry full digest does not match its hot ID"
            )
        _require_int(self.display_min_zoom_centi, 0, 10_000, "display minimum centizoom")
        _require_int(self.display_max_zoom_centi, 0, 10_000, "display maximum centizoom")
        if self.display_min_zoom_centi >= self.display_max_zoom_centi:
            raise SemanticModelError("display centizoom interval must be nonempty")
        _require_uint(self.spacing_px, 32, "label spacing pixels")
        if self.spacing_px == 0:
            raise SemanticModelError("label spacing pixels must be positive")
        _require_int(self.max_angle_degrees, 0, 180, "maximum label angle")
        _require_uint(self.collision_group, 64, "collision group")
        _require_int(self.priority, _I32_MIN, _I32_MAX, "placement priority")
        _require_bool(self.avoid_edges, "avoid-edges flag")
        _require_bool(self.keep_upright, "keep-upright flag")
        _require_int(self.active_band_limit, 1, 29, "active band limit")
        _require_sha256_bytes(self.style_policy_sha256, "style/policy SHA-256")
        if self.provider_feature_id is not None:
            _require_uint(self.provider_feature_id, 64, "provider feature ID")
        if self.text_evidence_kind is TextEvidenceKind.PROVIDER_STABLE_JOIN:
            if self.provider_feature_id is None or self.provider_feature_id == 0:
                raise SemanticModelError(
                    "provider stable join cannot use an absent or zero provider feature ID"
                )


def canonical_line_label_candidate_bytes(
    placement: NormalizedPlacement,
    text: str,
) -> bytes:
    if not isinstance(placement, NormalizedPlacement):
        raise SemanticModelError("line-label identity requires NormalizedPlacement")
    if placement.text_evidence_kind is TextEvidenceKind.NONE:
        raise SemanticModelError("line-label identity cannot use non-applicable placement")
    text = _require_nfc(text, "line-label text", allow_empty=False)
    writer = _record_writer(_LINE_LABEL_CANDIDATE_TAG)
    writer.raw(placement.source_feature_sha256)
    writer.raw(placement.placement_geometry_sha256)
    writer.u8(placement.text_evidence_kind.value)
    writer.u64(placement.text_source_field_id)
    writer.u64(placement.placement_source_feature_id)
    writer.u64(placement.placement_geometry_id)
    writer.boolean(placement.provider_feature_id is not None)
    if placement.provider_feature_id is not None:
        writer.u64(placement.provider_feature_id)
    writer.text(text, "line-label text")
    writer.raw(placement.style_policy_sha256)
    writer.tile(placement.source_tile)
    writer.u8(placement.source_zoom)
    writer.u64(placement.source_declared_extent)
    for value in placement.source_edge_domain:
        writer.i64(value)
    writer.u8(placement.projection_mode.value)
    writer.i32(placement.display_min_zoom_centi)
    writer.i32(placement.display_max_zoom_centi)
    writer.u32(placement.spacing_px)
    writer.u32(placement.max_angle_degrees)
    writer.u64(placement.collision_group)
    writer.i32(placement.priority)
    writer.boolean(placement.avoid_edges)
    writer.boolean(placement.keep_upright)
    writer.u8(placement.active_band_limit)
    return writer.finish()


def line_label_candidate_fingerprint(
    placement: NormalizedPlacement,
    text: str,
) -> Fingerprint:
    return _digest(
        b"FAE8LLB1\0",
        canonical_line_label_candidate_bytes(placement, text),
    )


def make_normalized_placement(
    *,
    text: str,
    source_feature_sha256: bytes,
    placement_geometry_sha256: bytes,
    text_evidence_kind: TextEvidenceKind,
    text_source_field_id: int,
    placement_source_feature_id: int,
    placement_geometry_id: int,
    source_tile: TileKey,
    source_zoom: int,
    source_declared_extent: int,
    source_edge_domain: tuple[int, int, int, int],
    projection_mode: ProjectionMode,
    display_min_zoom_centi: int,
    display_max_zoom_centi: int,
    spacing_px: int,
    max_angle_degrees: int,
    collision_group: int,
    priority: int,
    avoid_edges: bool,
    keep_upright: bool,
    active_band_limit: int,
    style_policy_sha256: bytes,
    provider_feature_id: int | None = None,
    identity_registry: HotIdRegistry | None = None,
) -> NormalizedPlacement:
    provisional = NormalizedPlacement(
        label_candidate_id=0,
        label_candidate_sha256=_ZERO_SHA256,
        source_feature_sha256=source_feature_sha256,
        placement_geometry_sha256=placement_geometry_sha256,
        text_evidence_kind=text_evidence_kind,
        text_source_field_id=text_source_field_id,
        placement_source_feature_id=placement_source_feature_id,
        placement_geometry_id=placement_geometry_id,
        source_tile=source_tile,
        source_zoom=source_zoom,
        source_declared_extent=source_declared_extent,
        source_edge_domain=source_edge_domain,
        projection_mode=projection_mode,
        display_min_zoom_centi=display_min_zoom_centi,
        display_max_zoom_centi=display_max_zoom_centi,
        spacing_px=spacing_px,
        max_angle_degrees=max_angle_degrees,
        collision_group=collision_group,
        priority=priority,
        avoid_edges=avoid_edges,
        keep_upright=keep_upright,
        active_band_limit=active_band_limit,
        style_policy_sha256=style_policy_sha256,
        provider_feature_id=provider_feature_id,
    )
    candidate_bytes = canonical_line_label_candidate_bytes(provisional, text)
    identity = (
        identity_registry.register(b"FAE8LLB1\0", candidate_bytes)
        if identity_registry is not None
        else _digest(b"FAE8LLB1\0", candidate_bytes)
    )
    return NormalizedPlacement(
        **{
            **{field.name: getattr(provisional, field.name) for field in fields(provisional)},
            "label_candidate_id": identity.hot_id,
            "label_candidate_sha256": identity.full_sha256,
        }
    )


def replace_normalized_placement(
    placement: NormalizedPlacement,
    *,
    text: str | None,
    identity_registry: HotIdRegistry | None = None,
    **changes: object,
) -> NormalizedPlacement:
    if not isinstance(placement, NormalizedPlacement):
        raise SemanticModelError("placement replacement requires NormalizedPlacement")
    if text is None:
        raise CanonicalEncodingError("line-label replacement requires whole text")
    values = {field.name: getattr(placement, field.name) for field in fields(placement)}
    values.update(changes)
    values["label_candidate_id"] = 0
    values["label_candidate_sha256"] = _ZERO_SHA256
    provisional = NormalizedPlacement(**values)
    candidate_bytes = canonical_line_label_candidate_bytes(provisional, text)
    identity = (
        identity_registry.register(b"FAE8LLB1\0", candidate_bytes)
        if identity_registry is not None
        else _digest(b"FAE8LLB1\0", candidate_bytes)
    )
    values["label_candidate_id"] = identity.hot_id
    values["label_candidate_sha256"] = identity.full_sha256
    return NormalizedPlacement(**values)


def _encode_placement(placement: NormalizedPlacement) -> bytes:
    writer = _record_writer(0x20)
    writer.u64(placement.label_candidate_id)
    writer.raw(placement.label_candidate_sha256)
    writer.raw(placement.source_feature_sha256)
    writer.raw(placement.placement_geometry_sha256)
    writer.u8(placement.text_evidence_kind.value)
    writer.u64(placement.text_source_field_id)
    writer.u64(placement.placement_source_feature_id)
    writer.u64(placement.placement_geometry_id)
    writer.tile(placement.source_tile)
    writer.u8(placement.source_zoom)
    writer.u64(placement.source_declared_extent)
    for value in placement.source_edge_domain:
        writer.i64(value)
    writer.u8(placement.projection_mode.value)
    writer.i32(placement.display_min_zoom_centi)
    writer.i32(placement.display_max_zoom_centi)
    writer.u32(placement.spacing_px)
    writer.u32(placement.max_angle_degrees)
    writer.u64(placement.collision_group)
    writer.i32(placement.priority)
    writer.boolean(placement.avoid_edges)
    writer.boolean(placement.keep_upright)
    writer.u8(placement.active_band_limit)
    writer.raw(placement.style_policy_sha256)
    writer.boolean(placement.provider_feature_id is not None)
    if placement.provider_feature_id is not None:
        writer.u64(placement.provider_feature_id)
    return writer.finish()


def _decode_placement(data: bytes) -> NormalizedPlacement:
    reader = _Reader(data)
    reader.header(0x20)
    label_id = reader.u64()
    label_sha = reader.take(32)
    source_sha = reader.take(32)
    geometry_sha = reader.take(32)
    try:
        evidence = TextEvidenceKind(reader.u8())
    except ValueError as error:
        raise CanonicalEncodingError("unknown text evidence kind") from error
    source_field_id = reader.u64()
    source_feature_id = reader.u64()
    geometry_id = reader.u64()
    source_tile = reader.tile()
    source_zoom = reader.u8()
    source_declared_extent = reader.u64()
    domain_values = tuple(reader.i64() for _ in range(4))
    source_domain = (
        domain_values[0],
        domain_values[1],
        domain_values[2],
        domain_values[3],
    )
    try:
        projection = ProjectionMode(reader.u8())
    except ValueError as error:
        raise CanonicalEncodingError("unknown projection mode") from error
    minimum = reader.i32()
    maximum = reader.i32()
    spacing = reader.u32()
    max_angle = reader.u32()
    collision = reader.u64()
    priority = reader.i32()
    avoid_edges = reader.boolean()
    keep_upright = reader.boolean()
    active_limit = reader.u8()
    style_sha = reader.take(32)
    has_provider = reader.boolean()
    provider = reader.u64() if has_provider else None
    reader.finish()
    return NormalizedPlacement(
        label_id,
        label_sha,
        source_sha,
        geometry_sha,
        evidence,
        source_field_id,
        source_feature_id,
        geometry_id,
        source_tile,
        source_zoom,
        source_declared_extent,
        source_domain,
        projection,
        minimum,
        maximum,
        spacing,
        max_angle,
        collision,
        priority,
        avoid_edges,
        keep_upright,
        active_limit,
        style_sha,
        provider,
    )


def empty_normalized_placement() -> NormalizedPlacement:
    return NormalizedPlacement(
        label_candidate_id=0,
        label_candidate_sha256=_ZERO_SHA256,
        source_feature_sha256=_ZERO_SHA256,
        placement_geometry_sha256=_ZERO_SHA256,
        text_evidence_kind=TextEvidenceKind.NONE,
        text_source_field_id=0,
        placement_source_feature_id=0,
        placement_geometry_id=0,
        source_tile=TileKey(0, 0, 0),
        source_zoom=0,
        source_declared_extent=1,
        source_edge_domain=(0, 0, 0, 0),
        projection_mode=ProjectionMode.DIRECT_SOURCE_PATH,
        display_min_zoom_centi=0,
        display_max_zoom_centi=0,
        spacing_px=0,
        max_angle_degrees=0,
        collision_group=0,
        priority=0,
        avoid_edges=False,
        keep_upright=False,
        active_band_limit=0,
        style_policy_sha256=_ZERO_SHA256,
        provider_feature_id=None,
    )


@dataclass(frozen=True, slots=True)
class SourceOccurrence:
    tile_key: TileKey
    source_feature_ordinal: int
    feature_id: int
    dedupe_id: int
    source_layer: str
    source_geometry: SourceGeometry
    source_audit_sha256: str

    def __post_init__(self) -> None:
        tile = _require_tile(self.tile_key, "source occurrence tile")
        _require_uint(self.source_feature_ordinal, 32, "source feature ordinal")
        _require_uint(self.feature_id, 64, "feature ID")
        _require_uint(self.dedupe_id, 64, "dedupe ID")
        _require_nfc(self.source_layer, "source layer", allow_empty=False)
        if not isinstance(self.source_geometry, SourceGeometry):
            raise SemanticModelError("source occurrence geometry must be SourceGeometry")
        if self.source_geometry.tile_key != tile:
            raise SemanticModelError("source occurrence tile and geometry tile differ")
        _require_sha256_hex(self.source_audit_sha256, "source audit SHA-256")


def encode_source_occurrence(occurrence: SourceOccurrence) -> bytes:
    if not isinstance(occurrence, SourceOccurrence):
        raise SemanticModelError("source occurrence encoder requires SourceOccurrence")
    writer = _record_writer(_SOURCE_OCCURRENCE_TAG)
    writer.tile(occurrence.tile_key)
    writer.u32(occurrence.source_feature_ordinal)
    writer.u64(occurrence.feature_id)
    writer.u64(occurrence.dedupe_id)
    writer.text(occurrence.source_layer, "source layer")
    writer.blob(encode_source_geometry(occurrence.source_geometry))
    writer.raw(bytes.fromhex(occurrence.source_audit_sha256))
    return writer.finish()


def decode_source_occurrence(data: bytes) -> SourceOccurrence:
    reader = _Reader(data)
    reader.header(_SOURCE_OCCURRENCE_TAG)
    tile = reader.tile()
    ordinal = reader.u32()
    feature_id = reader.u64()
    dedupe_id = reader.u64()
    layer = reader.text("source layer")
    geometry = decode_source_geometry(reader.blob())
    audit = reader.take(32).hex()
    reader.finish()
    return SourceOccurrence(tile, ordinal, feature_id, dedupe_id, layer, geometry, audit)


def source_occurrence_fingerprint(occurrence: SourceOccurrence) -> Fingerprint:
    return _digest(b"exp8-source-occurrence-v1\0", encode_source_occurrence(occurrence))


def feature_fingerprint(
    *,
    generation_sha256: bytes,
    tile_key: TileKey,
    source_layer: str,
    typed_properties: Mapping[str, object],
    source_geometry: SourceGeometry,
    duplicate_ordinal: int,
) -> Fingerprint:
    _require_sha256_bytes(generation_sha256, "source generation SHA-256")
    tile = _require_tile(tile_key, "feature tile")
    layer = _require_nfc(source_layer, "source layer", allow_empty=False)
    if not isinstance(source_geometry, SourceGeometry):
        raise SemanticModelError("feature identity requires SourceGeometry")
    if source_geometry.tile_key != tile:
        raise SemanticModelError("feature identity tile and source geometry tile differ")
    _require_uint(duplicate_ordinal, 32, "duplicate occurrence ordinal")
    writer = _record_writer(0x31)
    writer.raw(generation_sha256)
    writer.tile(tile)
    writer.text(layer, "source layer")
    writer.blob(canonical_typed_properties_bytes(typed_properties))
    writer.blob(encode_source_geometry(source_geometry))
    writer.u32(duplicate_ordinal)
    return _digest(b"exp8-feature-v1\0", writer.finish())


@dataclass(frozen=True, slots=True)
class CanonicalVariant:
    canonical_variant_id: int
    dedupe_id: int
    geometry_id: int
    source_layer_id: int
    source_scale_band_id: int
    layer_group: LayerGroup
    feature_kind: FeatureKind
    semantic_subtype: int
    source_style_layer_ids: tuple[int, ...]
    render_style_token_ids: tuple[int, ...]
    text: str | None
    geometry: RendererGeometry
    min_zoom_centi: int
    max_zoom_centi: int
    fade_in_centi: int
    fade_out_centi: int
    draw_order: int
    priority: int
    placement: NormalizedPlacement
    land_evidence: LandEvidence
    protected_status: ProtectedStatus
    flags: int

    def __post_init__(self) -> None:
        _validate_variant_fields(self)
        expected = variant_fingerprint(self)
        if self.canonical_variant_id != expected.hot_id:
            raise SemanticModelError(
                "canonical variant ID is not the FAE8VAR1 self-address of its payload"
            )


def _validate_id_tuple(value: object, label: str) -> tuple[int, ...]:
    items = _require_tuple(value, label)
    if len(items) > MAX_CANONICAL_TABLE_REFERENCES:
        raise SemanticModelError(
            f"{label} exceeds canonical reference ceiling {MAX_CANONICAL_TABLE_REFERENCES}"
        )
    for index, item in enumerate(items):
        _require_uint(item, 64, f"{label} item {index}")
    return items  # type: ignore[return-value]


def _validate_variant_fields(variant: CanonicalVariant) -> None:
    _require_uint(variant.canonical_variant_id, 64, "canonical variant ID")
    _require_uint(variant.dedupe_id, 64, "dedupe ID")
    _require_uint(variant.geometry_id, 64, "geometry ID")
    _require_uint(variant.source_layer_id, 64, "source layer ID")
    _require_uint(variant.source_scale_band_id, 64, "source scale-band ID")
    _require_enum(variant.layer_group, LayerGroup, "layer group")
    _require_enum(variant.feature_kind, FeatureKind, "feature kind")
    _require_uint(variant.semantic_subtype, 32, "semantic subtype")
    _validate_id_tuple(variant.source_style_layer_ids, "source style layer IDs")
    _validate_id_tuple(variant.render_style_token_ids, "render style token IDs")
    if variant.text is not None:
        _require_nfc(variant.text, "visible text", allow_empty=False)
    if not isinstance(variant.geometry, RendererGeometry):
        raise SemanticModelError("canonical variant geometry must be RendererGeometry")
    actual_geometry = renderer_geometry_fingerprint(variant.geometry)
    if variant.geometry_id != actual_geometry.hot_id:
        raise SemanticModelError("geometry ID does not address exact renderer geometry")
    _require_int(variant.min_zoom_centi, 0, 10_000, "minimum centizoom")
    _require_int(variant.max_zoom_centi, 0, 10_000, "maximum centizoom")
    if variant.min_zoom_centi >= variant.max_zoom_centi:
        raise SemanticModelError("variant centizoom interval must be nonempty")
    _require_int(variant.fade_in_centi, 0, 10_000, "fade-in centizoom")
    _require_int(variant.fade_out_centi, 0, 10_000, "fade-out centizoom")
    _require_int(variant.draw_order, _I32_MIN, _I32_MAX, "draw order")
    _require_int(variant.priority, _I32_MIN, _I32_MAX, "priority")
    if not isinstance(variant.placement, NormalizedPlacement):
        raise SemanticModelError("canonical variant placement must be NormalizedPlacement")
    if variant.feature_kind is FeatureKind.LABEL:
        if variant.text is None:
            raise SemanticModelError("LABEL variants require one whole visible text run")
        if variant.geometry.kind not in (GeometryKind.POINT, GeometryKind.PATH):
            raise SemanticModelError("LABEL variants require point or path geometry")
        candidate = line_label_candidate_fingerprint(variant.placement, variant.text)
        if (
            variant.placement.label_candidate_id != candidate.hot_id
            or variant.placement.label_candidate_sha256 != candidate.full_sha256
        ):
            raise SemanticModelError("label candidate ID does not address every FAE8LLB1 field")
        if variant.placement.placement_geometry_id != variant.geometry_id:
            raise SemanticModelError("placement geometry ID and canonical geometry ID differ")
        if variant.placement.placement_geometry_sha256 != actual_geometry.full_sha256:
            raise SemanticModelError("placement geometry digest and canonical geometry differ")
    else:
        if variant.text is not None:
            raise SemanticModelError("unlabeled line/polygon variants must not carry text")
        if variant.placement != empty_normalized_placement():
            raise SemanticModelError(
                "unlabeled records require the canonical non-applicable placement"
            )
        if (
            variant.feature_kind is FeatureKind.LINE
            and variant.geometry.kind is not GeometryKind.PATH
        ):
            raise SemanticModelError("LINE variants require path geometry")
        if (
            variant.feature_kind is FeatureKind.POLYGON_OUTLINE
            and variant.geometry.kind is not GeometryKind.POLYGON
        ):
            raise SemanticModelError("POLYGON_OUTLINE variants require polygon geometry")
    _require_enum(variant.land_evidence, LandEvidence, "land evidence")
    _require_enum(variant.protected_status, ProtectedStatus, "protected status")
    if variant.layer_group is LayerGroup.PUBLIC_LANDS and (
        variant.land_evidence is not LandEvidence.SOURCE_EXPLICIT
        or variant.protected_status is not ProtectedStatus.SOURCE_EXPLICIT
    ):
        raise SemanticModelError(
            "PUBLIC_LANDS group requires SOURCE_EXPLICIT land evidence and status"
        )
    _require_uint(variant.flags, 32, "variant flags")


def _canonical_variant_payload(
    *,
    dedupe_id: int,
    geometry_id: int,
    source_layer_id: int,
    source_scale_band_id: int,
    layer_group: LayerGroup,
    feature_kind: FeatureKind,
    semantic_subtype: int,
    source_style_layer_ids: tuple[int, ...],
    render_style_token_ids: tuple[int, ...],
    text: str | None,
    geometry: RendererGeometry,
    min_zoom_centi: int,
    max_zoom_centi: int,
    fade_in_centi: int,
    fade_out_centi: int,
    draw_order: int,
    priority: int,
    placement: NormalizedPlacement,
    land_evidence: LandEvidence,
    protected_status: ProtectedStatus,
    flags: int,
) -> bytes:
    writer = _record_writer(_CANONICAL_VARIANT_PAYLOAD_TAG)
    writer.u64(dedupe_id)
    writer.u64(geometry_id)
    writer.u64(source_layer_id)
    writer.u64(source_scale_band_id)
    writer.u8(layer_group.value)
    writer.u8(feature_kind.value)
    writer.u32(semantic_subtype)
    writer.u32(len(source_style_layer_ids))
    for value in source_style_layer_ids:
        writer.u64(value)
    writer.u32(len(render_style_token_ids))
    for value in render_style_token_ids:
        writer.u64(value)
    writer.boolean(text is not None)
    if text is not None:
        writer.text(text, "visible text")
    writer.blob(encode_renderer_geometry(geometry))
    writer.i32(min_zoom_centi)
    writer.i32(max_zoom_centi)
    writer.i32(fade_in_centi)
    writer.i32(fade_out_centi)
    writer.i32(draw_order)
    writer.i32(priority)
    writer.blob(_encode_placement(placement))
    writer.u8(land_evidence.value)
    writer.u8(protected_status.value)
    writer.u32(flags)
    return writer.finish()


def canonical_variant_bytes(variant: CanonicalVariant) -> bytes:
    if not isinstance(variant, CanonicalVariant):
        raise SemanticModelError("canonical variant encoder requires CanonicalVariant")
    values = {
        field.name: getattr(variant, field.name)
        for field in fields(variant)
        if field.name != "canonical_variant_id"
    }
    return _canonical_variant_payload(**values)


def variant_fingerprint(variant: CanonicalVariant) -> Fingerprint:
    return _digest(b"FAE8VAR1\0", canonical_variant_bytes(variant))


def make_canonical_variant(
    *,
    dedupe_id: int,
    geometry_id: int,
    source_layer_id: int,
    source_scale_band_id: int,
    layer_group: LayerGroup,
    feature_kind: FeatureKind,
    semantic_subtype: int,
    source_style_layer_ids: tuple[int, ...],
    render_style_token_ids: tuple[int, ...],
    text: str | None,
    geometry: RendererGeometry,
    min_zoom_centi: int,
    max_zoom_centi: int,
    fade_in_centi: int,
    fade_out_centi: int,
    draw_order: int,
    priority: int,
    placement: NormalizedPlacement,
    land_evidence: LandEvidence,
    protected_status: ProtectedStatus,
    flags: int,
    identity_registry: HotIdRegistry | None = None,
) -> CanonicalVariant:
    values = locals().copy()
    registry = values.pop("identity_registry")
    provisional = object.__new__(CanonicalVariant)
    object.__setattr__(provisional, "canonical_variant_id", 0)
    for name, value in values.items():
        object.__setattr__(provisional, name, value)
    _validate_variant_fields(provisional)
    payload = _canonical_variant_payload(**values)
    identity = (
        registry.register(b"FAE8VAR1\0", payload)
        if registry is not None
        else _digest(b"FAE8VAR1\0", payload)
    )
    return CanonicalVariant(canonical_variant_id=identity.hot_id, **values)


def replace_canonical_variant(
    variant: CanonicalVariant,
    **changes: object,
) -> CanonicalVariant:
    if not isinstance(variant, CanonicalVariant):
        raise SemanticModelError("variant replacement requires CanonicalVariant")
    values = {
        field.name: getattr(variant, field.name)
        for field in fields(variant)
        if field.name != "canonical_variant_id"
    }
    values.update(changes)
    return make_canonical_variant(**values)


def encode_canonical_variant(variant: CanonicalVariant) -> bytes:
    writer = _record_writer(_ENCODED_CANONICAL_VARIANT_TAG)
    writer.u64(variant.canonical_variant_id)
    writer.blob(canonical_variant_bytes(variant))
    return writer.finish()


def _decode_variant_payload(data: bytes) -> dict[str, object]:
    reader = _Reader(data)
    reader.header(_CANONICAL_VARIANT_PAYLOAD_TAG)
    dedupe_id = reader.u64()
    geometry_id = reader.u64()
    source_layer_id = reader.u64()
    source_scale_band_id = reader.u64()
    try:
        layer_group = LayerGroup(reader.u8())
        feature_kind = FeatureKind(reader.u8())
    except ValueError as error:
        raise CanonicalEncodingError("unknown layer group or feature kind") from error
    subtype = reader.u32()
    source_style_count = reader.u32()
    if (
        source_style_count > MAX_CANONICAL_TABLE_REFERENCES
        or source_style_count > reader.remaining // 8
    ):
        raise CanonicalEncodingError("source-style count exceeds canonical bounds")
    source_styles = tuple(reader.u64() for _ in range(source_style_count))
    render_token_count = reader.u32()
    if (
        render_token_count > MAX_CANONICAL_TABLE_REFERENCES
        or render_token_count > reader.remaining // 8
    ):
        raise CanonicalEncodingError("render-token count exceeds canonical bounds")
    render_tokens = tuple(reader.u64() for _ in range(render_token_count))
    text = reader.text("visible text") if reader.boolean() else None
    geometry = decode_renderer_geometry(reader.blob())
    min_zoom = reader.i32()
    max_zoom = reader.i32()
    fade_in = reader.i32()
    fade_out = reader.i32()
    draw_order = reader.i32()
    priority = reader.i32()
    placement = _decode_placement(reader.blob())
    try:
        land_evidence = LandEvidence(reader.u8())
        protected_status = ProtectedStatus(reader.u8())
    except ValueError as error:
        raise CanonicalEncodingError("unknown land evidence or protected status") from error
    flags = reader.u32()
    reader.finish()
    return {
        "dedupe_id": dedupe_id,
        "geometry_id": geometry_id,
        "source_layer_id": source_layer_id,
        "source_scale_band_id": source_scale_band_id,
        "layer_group": layer_group,
        "feature_kind": feature_kind,
        "semantic_subtype": subtype,
        "source_style_layer_ids": source_styles,
        "render_style_token_ids": render_tokens,
        "text": text,
        "geometry": geometry,
        "min_zoom_centi": min_zoom,
        "max_zoom_centi": max_zoom,
        "fade_in_centi": fade_in,
        "fade_out_centi": fade_out,
        "draw_order": draw_order,
        "priority": priority,
        "placement": placement,
        "land_evidence": land_evidence,
        "protected_status": protected_status,
        "flags": flags,
    }


def decode_canonical_variant(data: bytes) -> CanonicalVariant:
    reader = _Reader(data)
    reader.header(_ENCODED_CANONICAL_VARIANT_TAG)
    encoded_id = reader.u64()
    payload = reader.blob()
    reader.finish()
    values = _decode_variant_payload(payload)
    variant = make_canonical_variant(**values)
    if variant.canonical_variant_id != encoded_id:
        raise CanonicalEncodingError(
            "encoded canonical variant ID does not match its FAE8VAR1 payload"
        )
    return variant


@dataclass(frozen=True, slots=True)
class TilePosting:
    requested_tile: TileKey
    feature_id: int
    canonical_variant_id: int
    owner_tile: TileKey
    world_wrap: int

    def __post_init__(self) -> None:
        _require_tile(self.requested_tile, "requested tile")
        _require_uint(self.feature_id, 64, "posting feature ID")
        _require_uint(self.canonical_variant_id, 64, "posting canonical variant ID")
        _require_tile(self.owner_tile, "posting owner tile")
        _require_int(self.world_wrap, _I32_MIN, _I32_MAX, "posting world wrap")


def encode_tile_posting(posting: TilePosting) -> bytes:
    if not isinstance(posting, TilePosting):
        raise SemanticModelError("posting encoder requires TilePosting")
    writer = _record_writer(_TILE_POSTING_TAG)
    writer.tile(posting.requested_tile)
    writer.u64(posting.feature_id)
    writer.u64(posting.canonical_variant_id)
    writer.tile(posting.owner_tile)
    writer.i32(posting.world_wrap)
    return writer.finish()


def decode_tile_posting(data: bytes) -> TilePosting:
    reader = _Reader(data)
    reader.header(_TILE_POSTING_TAG)
    posting = TilePosting(
        reader.tile(),
        reader.u64(),
        reader.u64(),
        reader.tile(),
        reader.i32(),
    )
    reader.finish()
    return posting


def validate_public_land_presentation(
    variant: CanonicalVariant,
    public_land_token_ids: Iterable[int],
) -> None:
    if not isinstance(variant, CanonicalVariant):
        raise SemanticModelError("land presentation validation requires CanonicalVariant")
    protected_tokens: set[int] = set()
    for token in public_land_token_ids:
        protected_tokens.add(_require_uint(token, 64, "public-land render token ID"))
    makes_public_claim = (
        variant.layer_group is LayerGroup.PUBLIC_LANDS
        or bool(protected_tokens.intersection(variant.render_style_token_ids))
    )
    if makes_public_claim and (
        variant.land_evidence is not LandEvidence.SOURCE_EXPLICIT
        or variant.protected_status is not ProtectedStatus.SOURCE_EXPLICIT
    ):
        raise SemanticModelError(
            "PUBLIC_LANDS group/tokens require SOURCE_EXPLICIT land evidence and status"
        )


def source_edge_eligible(
    placement: NormalizedPlacement,
    anchor_x_numerator: int,
    anchor_y_numerator: int,
    anchor_denominator: int,
    *,
    margin_source_units: int,
) -> bool:
    if not isinstance(placement, NormalizedPlacement):
        raise SemanticModelError("source-edge test requires NormalizedPlacement")
    _require_int(anchor_x_numerator, _I64_MIN, _I64_MAX, "anchor x numerator")
    _require_int(anchor_y_numerator, _I64_MIN, _I64_MAX, "anchor y numerator")
    _require_int(anchor_denominator, 1, _I64_MAX, "anchor denominator")
    _require_uint(margin_source_units, 64, "source-edge margin")
    if not placement.avoid_edges:
        return True
    minimum_x, minimum_y, maximum_x, maximum_y = placement.source_edge_domain
    minimum_x += margin_source_units
    minimum_y += margin_source_units
    maximum_x -= margin_source_units
    maximum_y -= margin_source_units
    if minimum_x > maximum_x or minimum_y > maximum_y:
        return False
    extent = placement.source_declared_extent
    source_scale = (1 << placement.source_zoom) * extent
    left = placement.source_tile.x * extent + minimum_x
    right = placement.source_tile.x * extent + maximum_x
    top = placement.source_tile.y * extent + minimum_y
    bottom = placement.source_tile.y * extent + maximum_y
    return (
        left * anchor_denominator
        <= anchor_x_numerator * source_scale
        <= right * anchor_denominator
        and top * anchor_denominator
        <= anchor_y_numerator * source_scale
        <= bottom * anchor_denominator
    )


@dataclass(frozen=True, slots=True)
class PlacementMembership:
    requested_tile: TileKey
    label_candidate_id: int
    source_feature_id: int
    placement_geometry_id: int
    owner_tile: TileKey
    metatile_id: int
    feature_page_id: int
    local_ordinal: int
    world_wrap: int

    def __post_init__(self) -> None:
        _require_tile(self.requested_tile, "placement requested tile")
        _require_uint(self.label_candidate_id, 64, "placement label candidate ID")
        _require_uint(self.source_feature_id, 64, "placement source feature ID")
        _require_uint(self.placement_geometry_id, 64, "placement geometry ID")
        _require_tile(self.owner_tile, "placement owner tile")
        _require_uint(self.metatile_id, 64, "placement metatile ID")
        _require_uint(self.feature_page_id, 64, "placement feature page ID")
        _require_uint(self.local_ordinal, 32, "placement local ordinal")
        _require_int(self.world_wrap, _I32_MIN, _I32_MAX, "placement world wrap")


@dataclass(frozen=True, slots=True)
class ParentLabelBand:
    variant: CanonicalVariant

    def __post_init__(self) -> None:
        if not isinstance(self.variant, CanonicalVariant):
            raise SemanticModelError("parent label band requires CanonicalVariant")
        if self.variant.text is None:
            raise SemanticModelError("parent label band requires one whole visible label")
        if self.variant.feature_kind is not FeatureKind.LABEL:
            raise SemanticModelError("parent label band requires LABEL feature kind")
        if self.variant.geometry.kind is not GeometryKind.PATH:
            raise SemanticModelError("parent label band requires an exact canonical path")

    @classmethod
    def from_variant(cls, variant: CanonicalVariant) -> "ParentLabelBand":
        return cls(variant)

    @property
    def source_zoom(self) -> int:
        return self.variant.placement.source_zoom

    @property
    def active_band_limit(self) -> int:
        return self.variant.placement.active_band_limit


def derive_descendant_memberships(
    band: ParentLabelBand,
    requested_tiles: Iterable[TileKey],
    *,
    world_wraps: Iterable[int] = (0,),
    membership_limit: int = MAX_PLACEMENT_MEMBERSHIPS,
) -> tuple[PlacementMembership, ...]:
    if not isinstance(band, ParentLabelBand):
        raise SemanticModelError("descendant membership requires ParentLabelBand")
    _require_int(membership_limit, 1, _U32_MAX, "membership limit")
    wraps = sorted(set(world_wraps))
    for world_wrap in wraps:
        _require_int(world_wrap, _I32_MIN, _I32_MAX, "membership world wrap")
    unique_tiles: dict[int, TileKey] = {}
    for tile in requested_tiles:
        checked = _require_tile(tile, "requested descendant tile")
        unique_tiles[checked.packed] = checked
    variant = band.variant
    placement = variant.placement
    memberships: list[PlacementMembership] = []
    for tile in (unique_tiles[key] for key in sorted(unique_tiles)):
        zoom_delta = tile.z - placement.source_zoom
        zoom_centi = tile.z * 100
        if (
            zoom_delta < 0
            or zoom_delta > placement.active_band_limit
            or zoom_centi < placement.display_min_zoom_centi
            or zoom_centi >= placement.display_max_zoom_centi
        ):
            continue
        for world_wrap in wraps:
            if not geometry_intersects_tile(
                variant.geometry,
                tile,
                world_wrap=world_wrap,
            ):
                continue
            memberships.append(
                PlacementMembership(
                    requested_tile=tile,
                    label_candidate_id=placement.label_candidate_id,
                    source_feature_id=placement.placement_source_feature_id,
                    placement_geometry_id=placement.placement_geometry_id,
                    owner_tile=placement.source_tile,
                    metatile_id=tile.packed,
                    feature_page_id=0,
                    local_ordinal=0,
                    world_wrap=world_wrap,
                )
            )
            if len(memberships) > membership_limit:
                raise SemanticModelError(
                    f"exact placement memberships exceed hard limit {membership_limit}"
                )
    return tuple(memberships)


def viewport_label_candidates(
    memberships: Iterable[PlacementMembership],
    variants_by_candidate_id: Mapping[int, CanonicalVariant],
) -> tuple[CanonicalVariant, ...]:
    selected: dict[int, CanonicalVariant] = {}
    for membership in memberships:
        if not isinstance(membership, PlacementMembership):
            raise SemanticModelError("viewport membership has wrong type")
        variant = variants_by_candidate_id.get(membership.label_candidate_id)
        if variant is None:
            raise SemanticModelError(
                f"missing label candidate {membership.label_candidate_id}"
            )
        if variant.placement.label_candidate_id != membership.label_candidate_id:
            raise SemanticModelError("membership resolves to the wrong label candidate")
        if variant.geometry_id != membership.placement_geometry_id:
            raise SemanticModelError("membership resolves to the wrong exact path")
        if (
            variant.placement.placement_source_feature_id
            != membership.source_feature_id
        ):
            raise SemanticModelError("membership resolves to the wrong source feature")
        previous = selected.get(membership.label_candidate_id)
        if previous is not None and canonical_variant_bytes(previous) != canonical_variant_bytes(variant):
            raise IdentityCollisionError("one label candidate ID resolves to unequal variants")
        selected[membership.label_candidate_id] = variant
    return tuple(
        sorted(
            selected.values(),
            key=lambda variant: (variant.placement.priority, variant.placement.label_candidate_id),
        )
    )


def label_repeat_phase(label_candidate_id: int, spacing_px: int) -> int:
    _require_uint(label_candidate_id, 64, "label candidate ID")
    _require_uint(spacing_px, 32, "label spacing pixels")
    if spacing_px == 0:
        raise SemanticModelError("label spacing pixels must be positive")
    return label_candidate_id % spacing_px


def whole_label_accepted(
    continuous_path_milli_px: int,
    shaped_run_milli_px: int,
    measured_bend_degrees: int,
    maximum_bend_degrees: int,
    collision_free: bool,
) -> bool:
    _require_uint(continuous_path_milli_px, 64, "continuous path length")
    _require_uint(shaped_run_milli_px, 64, "shaped run length")
    _require_int(measured_bend_degrees, 0, 180, "measured bend")
    _require_int(maximum_bend_degrees, 0, 180, "maximum bend")
    _require_bool(collision_free, "collision-free decision")
    return (
        collision_free
        and continuous_path_milli_px >= shaped_run_milli_px
        and measured_bend_degrees <= maximum_bend_degrees
    )


@dataclass(frozen=True, slots=True)
class RendererRecord:
    posting: TilePosting
    variant: CanonicalVariant

    def __post_init__(self) -> None:
        if not isinstance(self.posting, TilePosting) or not isinstance(
            self.variant, CanonicalVariant
        ):
            raise SemanticModelError("renderer record requires posting and variant")
        if self.posting.canonical_variant_id != self.variant.canonical_variant_id:
            raise SemanticModelError("posting references a different canonical variant")


def canonical_renderer_bytes(record: RendererRecord) -> bytes:
    if not isinstance(record, RendererRecord):
        raise SemanticModelError("canonical renderer bytes require RendererRecord")
    return renderer_record_bytes(record)


def renderer_order_key(
    record: RendererRecord,
) -> tuple[int, int, int, int, int, int, bytes]:
    if not isinstance(record, RendererRecord):
        raise SemanticModelError("renderer ordering requires RendererRecord")
    return (
        record.variant.draw_order,
        record.variant.priority,
        record.variant.layer_group.value,
        record.variant.feature_kind.value,
        record.variant.canonical_variant_id,
        record.posting.feature_id,
        canonical_renderer_bytes(record),
    )


def reconstruct_renderer_records(
    postings: Iterable[TilePosting],
    variants_by_id: Mapping[int, CanonicalVariant],
    *,
    public_land_token_ids: Iterable[int],
) -> tuple[RendererRecord, ...]:
    public_tokens = tuple(public_land_token_ids)
    records: list[RendererRecord] = []
    for posting in postings:
        if not isinstance(posting, TilePosting):
            raise SemanticModelError("renderer reconstruction received a non-posting")
        variant = variants_by_id.get(posting.canonical_variant_id)
        if variant is None:
            raise SemanticModelError(
                f"missing canonical variant {posting.canonical_variant_id}"
            )
        if variant.canonical_variant_id != posting.canonical_variant_id:
            raise SemanticModelError("canonical variant lookup key is dishonest")
        validate_public_land_presentation(variant, public_tokens)
        records.append(RendererRecord(posting, variant))
    records.sort(
        key=lambda record: (record.posting.requested_tile.packed, renderer_order_key(record))
    )
    return tuple(records)


def renderer_record_bytes(record: RendererRecord) -> bytes:
    if not isinstance(record, RendererRecord):
        raise SemanticModelError("renderer record encoder requires RendererRecord")
    writer = _record_writer(_RENDERER_RECORD_TAG)
    writer.u64(record.posting.feature_id)
    writer.u64(record.variant.canonical_variant_id)
    writer.tile(record.posting.owner_tile)
    writer.i32(record.posting.world_wrap)
    writer.blob(canonical_variant_bytes(record.variant))
    return writer.finish()


def renderer_contract_hash(records: Iterable[RendererRecord]) -> str:
    ordered = sorted(
        records,
        key=lambda record: (record.posting.requested_tile.packed, renderer_order_key(record)),
    )
    digest = hashlib.sha256()
    digest.update(b"flight-alert-exp8-semantic-v1\0")
    for record in ordered:
        body_writer = _Writer()
        body_writer.tile(record.posting.requested_tile)
        body_writer.raw(renderer_record_bytes(record))
        body = body_writer.finish()
        digest.update(struct.pack("<I", len(body)))
        digest.update(body)
    return digest.hexdigest()


def renderer_record_heap_weight(record: RendererRecord) -> int:
    if not isinstance(record, RendererRecord):
        raise SemanticModelError("heap accounting requires RendererRecord")
    variant = record.variant
    point_count = len(variant.geometry.world_coordinate_numerators) // 2
    text_bytes = len(variant.text.encode("utf-8")) if variant.text is not None else 0
    style_references = len(variant.source_style_layer_ids) + len(
        variant.render_style_token_ids
    )
    return (
        HOT_RENDERER_BASE_HEAP_BYTES
        + point_count * GEOMETRY_POINT_HEAP_BYTES
        + len(variant.geometry.parts) * GEOMETRY_PART_HEAP_BYTES
        + style_references * STYLE_REFERENCE_HEAP_BYTES
        + text_bytes
    )


def renderer_records_heap_weight(
    records: Iterable[RendererRecord],
    *,
    ceiling: int = MAX_RENDERER_HEAP_BYTES,
) -> int:
    _require_uint(ceiling, 64, "renderer heap ceiling")
    total = 0
    for record in records:
        total += renderer_record_heap_weight(record)
        if total > ceiling:
            raise HeapLimitError(
                f"reconstructed renderer heap {total} exceeds ceiling {ceiling}"
            )
    return total


def is_disputed(dispute_id: int) -> bool:
    _require_uint(dispute_id, 64, "DisputeID")
    return dispute_id != 0


# Public semantic names make the payload-vs-wrapper distinction explicit.
source_geometry_bytes = encode_source_geometry
renderer_geometry_bytes = encode_renderer_geometry
source_occurrence_bytes = encode_source_occurrence
tile_posting_bytes = encode_tile_posting
