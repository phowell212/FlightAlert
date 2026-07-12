from __future__ import annotations

import hashlib
import json
import struct
import zlib
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from .model import TileKey
from .reference_presentation_policy import (
    PRESENTATION_POLICY_SHA256,
    SemanticSubtype,
)
from .semantic_model import (
    CANONICAL_MAGIC,
    FeatureKind,
    RendererRecord,
    TilePosting,
    decode_canonical_variant,
    renderer_order_key,
    renderer_record_bytes,
)
from .sourced_text import (
    DEFAULT_SOURCED_TEXT_POLICY,
    END_TRIM_SCALARS,
    SOURCED_TEXT_IDENTITY_DOMAIN,
    SOURCED_TEXT_RECORD_TAG,
    SOURCED_TEXT_RECORD_VERSION,
    UNICODE_SCRIPT_PROFILE_SHA256,
    EnglishGapReason,
    LayoutMode,
    ScriptSignals,
    SourcedMapText,
)


TILE_PAYLOAD_MAGIC = b"FAE8TILE1\0"
PAYLOAD_SCHEMA = "flightalert.reference.renderer-tile.v1"
INDEX_ENTRY_BYTES = 24
INDEX_FLAG_PRESENT = 1
MAX_TILE_BYTES = 16 * 1024 * 1024
MAX_RECORDS_PER_TILE = 65_536
MAX_RENDERER_RECORD_BYTES = 8 * 1024 * 1024
MAX_SOURCED_TEXT_RECORD_BYTES = 8_300
MAX_SOURCED_TEXT_UTF8_BYTES = 4_096
SOURCED_TEXT_POLICY_SHA256 = DEFAULT_SOURCED_TEXT_POLICY.policy_sha256.hex()
_PRESENTATION_POLICY_IDENTITY = bytes.fromhex(PRESENTATION_POLICY_SHA256)
_SOURCED_TEXT_POLICY_IDENTITY = bytes.fromhex(SOURCED_TEXT_POLICY_SHA256)
_UNICODE_SCRIPT_PROFILE_IDENTITY = bytes.fromhex(UNICODE_SCRIPT_PROFILE_SHA256)
_END_TRIM_SCALARS = frozenset(END_TRIM_SCALARS)
_ENCODED_CANONICAL_VARIANT_TAG = 7
_RENDERER_RECORD_TAG = 8


class RendererTilePackageError(ValueError):
    """A renderer tile or package cannot satisfy the binary runtime contract."""


@dataclass(frozen=True, slots=True)
class RendererTileRecord:
    renderer_record: RendererRecord
    sourced_text: SourcedMapText | None


@dataclass(frozen=True, slots=True)
class DecodedSourcedText:
    primary_text: str
    primary_source_field_id: int
    english_text: str | None
    english_source_field_id: int | None
    layout_mode: LayoutMode
    english_gap_reason: EnglishGapReason
    primary_script_signals: ScriptSignals
    english_script_signals: ScriptSignals | None
    canonical_bytes: bytes
    full_sha256: bytes


@dataclass(frozen=True, slots=True)
class DecodedRendererTileRecord:
    renderer_record: RendererRecord
    sourced_text: DecodedSourcedText | None


@dataclass(frozen=True, slots=True)
class DecodedRendererTilePayload:
    coordinate: TileKey
    records: tuple[DecodedRendererTileRecord, ...]


@dataclass(frozen=True, slots=True)
class PackageArtifacts:
    manifest_bytes: bytes
    records_bytes: bytes
    index_bytes: bytes


def encode_tile_payload(
    coordinate: TileKey,
    records: Iterable[RendererTileRecord],
) -> bytes:
    """Encode one deterministic ``FAE8TILE1`` payload for the Android codec."""

    if type(coordinate) is not TileKey:
        raise RendererTilePackageError("tile coordinate must be a TileKey")
    ordered = tuple(records)
    if len(ordered) > MAX_RECORDS_PER_TILE:
        raise RendererTilePackageError("renderer record count exceeds its tile bound")
    for record in ordered:
        if type(record) is not RendererTileRecord:
            raise RendererTilePackageError("tile records must be RendererTileRecord values")
        renderer = record.renderer_record
        if renderer.posting.requested_tile != coordinate:
            raise RendererTilePackageError(
                "renderer record requested tile differs from its payload coordinate"
            )
        is_label = renderer.variant.feature_kind is FeatureKind.LABEL
        if is_label != (record.sourced_text is not None):
            raise RendererTilePackageError(
                "labels require sourced text and non-label records forbid it"
            )
        if record.sourced_text is not None:
            if renderer.variant.text != record.sourced_text.primary_text:
                raise RendererTilePackageError(
                    "canonical variant text differs from sourced primary text"
                )
            if (
                renderer.variant.placement.text_source_field_id
                != record.sourced_text.primary_source_field_id
            ):
                raise RendererTilePackageError(
                    "placement source field differs from sourced primary field"
                )
    ordered = tuple(
        sorted(
            ordered,
            key=lambda item: (
                renderer_order_key(item.renderer_record),
                item.sourced_text.full_sha256 if item.sourced_text is not None else b"",
            ),
        )
    )
    output = bytearray(TILE_PAYLOAD_MAGIC)
    output.extend(struct.pack("<BIII", coordinate.z, coordinate.x, coordinate.y, len(ordered)))
    for record in ordered:
        renderer_bytes = renderer_record_bytes(record.renderer_record)
        if not renderer_bytes or len(renderer_bytes) > MAX_RENDERER_RECORD_BYTES:
            raise RendererTilePackageError("renderer record byte length is outside its bound")
        output.extend(struct.pack("<I", len(renderer_bytes)))
        output.extend(renderer_bytes)
        sourced = record.sourced_text
        if sourced is None:
            output.extend(struct.pack("<I", 0))
        else:
            canonical = sourced.canonical_bytes
            if not canonical or len(canonical) > MAX_SOURCED_TEXT_RECORD_BYTES:
                raise RendererTilePackageError(
                    "sourced-text record byte length is outside its tile bound"
                )
            output.extend(struct.pack("<I", len(canonical)))
            output.extend(sourced.full_sha256)
            output.extend(canonical)
    if len(output) > MAX_TILE_BYTES:
        raise RendererTilePackageError("binary reference tile exceeds its byte bound")
    return bytes(output)


def decode_tile_payload(
    coordinate: TileKey,
    payload: bytes,
) -> DecodedRendererTilePayload:
    """Strictly decode and validate one Android renderer-tile payload."""

    if type(coordinate) is not TileKey:
        raise RendererTilePackageError("tile coordinate must be a TileKey")
    if type(payload) is not bytes:
        raise RendererTilePackageError("binary reference tile must be immutable bytes")
    if not payload or len(payload) > MAX_TILE_BYTES:
        raise RendererTilePackageError(
            "binary reference tile byte length is outside its bound"
        )
    try:
        reader = _ByteReader(payload)
        reader.expect(TILE_PAYLOAD_MAGIC, "binary reference tile magic is unsupported")
        encoded = TileKey(reader.u8(), reader.u32(), reader.u32())
        if encoded != coordinate:
            raise RendererTilePackageError(
                "binary reference tile coordinate does not match its index entry"
            )
        count = reader.u32()
        if count > MAX_RECORDS_PER_TILE:
            raise RendererTilePackageError(
                "binary reference tile record count exceeds its bound"
            )
        records: list[DecodedRendererTileRecord] = []
        for _ in range(count):
            renderer_bytes = reader.blob(
                MAX_RENDERER_RECORD_BYTES,
                "renderer record",
            )
            renderer = _decode_renderer_record(coordinate, renderer_bytes)
            sourced_length = reader.u32()
            sourced = None
            if sourced_length:
                if sourced_length > MAX_SOURCED_TEXT_RECORD_BYTES:
                    raise RendererTilePackageError(
                        "sourced-text record exceeds its tile bound"
                    )
                expected_digest = reader.take(32)
                canonical = reader.take(sourced_length)
                sourced = _decode_sourced_text(canonical, expected_digest)
            _validate_sourced_text_link(renderer, sourced)
            records.append(DecodedRendererTileRecord(renderer, sourced))
        reader.finish("binary reference tile has trailing bytes")
        return DecodedRendererTilePayload(coordinate, tuple(records))
    except RendererTilePackageError:
        raise
    except (OverflowError, UnicodeError, ValueError, struct.error) as error:
        raise RendererTilePackageError(
            str(error) or "binary reference tile is malformed"
        ) from error


def raw_deflate(payload: bytes) -> bytes:
    """Return deterministic RFC 1951 bytes without zlib/gzip framing."""

    if type(payload) is not bytes:
        raise RendererTilePackageError("raw DEFLATE input must be immutable bytes")
    compressor = zlib.compressobj(
        level=9,
        method=zlib.DEFLATED,
        wbits=-zlib.MAX_WBITS,
        memLevel=9,
        strategy=zlib.Z_DEFAULT_STRATEGY,
    )
    return compressor.compress(payload) + compressor.flush(zlib.Z_FINISH)


def raw_hash32(payload: bytes) -> int:
    """Return the Android index's big-endian first SHA-256 word."""

    if type(payload) is not bytes:
        raise RendererTilePackageError("raw hash input must be immutable bytes")
    return int.from_bytes(hashlib.sha256(payload).digest()[:4], "big")


def encode_index_entry(
    *,
    offset: int,
    compressed_length: int,
    raw_length: int,
    raw_hash32: int,
    flags: int = INDEX_FLAG_PRESENT,
) -> bytes:
    """Encode one 24-byte little-endian ``tile-index.bin`` entry."""

    values = (
        (offset, 0, (1 << 63) - 1, "record offset"),
        (compressed_length, 1, (1 << 31) - 1, "compressed length"),
        (raw_length, 1, (1 << 31) - 1, "raw length"),
        (raw_hash32, 0, (1 << 32) - 1, "raw hash32"),
        (flags, 0, (1 << 32) - 1, "index flags"),
    )
    for value, minimum, maximum, label in values:
        if type(value) is not int or not minimum <= value <= maximum:
            raise RendererTilePackageError(
                f"{label} must be an integer in [{minimum}, {maximum}]"
            )
    return struct.pack(
        "<QIIII",
        offset,
        compressed_length,
        raw_length,
        raw_hash32,
        flags,
    )


def build_package(
    package_id: str,
    tile_payloads: Mapping[TileKey, bytes],
    *,
    complete_declared_scope: bool = True,
    complete_whole_earth_dictionary: bool = False,
) -> PackageArtifacts:
    """Build deterministic schema-v3 manifest, record, and index bytes."""

    _validate_package_id(package_id)
    if not isinstance(tile_payloads, Mapping) or not tile_payloads:
        raise RendererTilePackageError(
            "package tile payloads must be a nonempty coordinate mapping"
        )
    if type(complete_declared_scope) is not bool:
        raise RendererTilePackageError("complete-declared-scope flag must be Boolean")
    if type(complete_whole_earth_dictionary) is not bool:
        raise RendererTilePackageError(
            "complete-whole-earth-dictionary flag must be Boolean"
        )
    checked: dict[TileKey, bytes] = {}
    for coordinate, payload in tile_payloads.items():
        if type(coordinate) is not TileKey:
            raise RendererTilePackageError(
                "package tile coordinates must be exact TileKey values"
            )
        decode_tile_payload(coordinate, payload)
        checked[coordinate] = payload
    zoom_ranges = _package_zoom_ranges(tuple(checked))
    covered_coordinates = tuple(
        TileKey(zoom, x, y)
        for zoom, x_min, x_max, y_min, y_max in zoom_ranges
        for y in range(y_min, y_max + 1)
        for x in range(x_min, x_max + 1)
    )
    if complete_declared_scope and len(covered_coordinates) != len(checked):
        raise RendererTilePackageError(
            "complete declared package scope contains an unencoded tile"
        )
    if complete_whole_earth_dictionary:
        if not complete_declared_scope or any(
            x_min != 0
            or y_min != 0
            or x_max != (1 << zoom) - 1
            or y_max != (1 << zoom) - 1
            for zoom, x_min, x_max, y_min, y_max in zoom_ranges
        ):
            raise RendererTilePackageError(
                "whole-earth dictionary claim requires complete full-world zoom ranges"
            )
    records_output = bytearray()
    index_output = bytearray()
    for coordinate in covered_coordinates:
        payload = checked.get(coordinate)
        if payload is None:
            index_output.extend(b"\0" * INDEX_ENTRY_BYTES)
            continue
        compressed = raw_deflate(payload)
        index_output.extend(
            encode_index_entry(
                offset=len(records_output),
                compressed_length=len(compressed),
                raw_length=len(payload),
                raw_hash32=raw_hash32(payload),
            )
        )
        records_output.extend(compressed)
    range_documents = []
    tile_count = 0
    for zoom, x_min, x_max, y_min, y_max in zoom_ranges:
        count = (x_max - x_min + 1) * (y_max - y_min + 1)
        tile_count += count
        range_documents.append(
            {
                "z": zoom,
                "xMin": x_min,
                "xMax": x_max,
                "yMin": y_min,
                "yMax": y_max,
                "tileCount": count,
            }
        )
    manifest = {
        "packageId": package_id,
        "schemaVersion": 3,
        "payloadSchema": PAYLOAD_SCHEMA,
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "sourcedTextPolicySha256": SOURCED_TEXT_POLICY_SHA256,
        "unicodeScriptProfileSha256": UNICODE_SCRIPT_PROFILE_SHA256,
        "compatibility": {"emptyPresentTilesSharePayload": False},
        "coverage": {
            "tileCount": tile_count,
            "completeDeclaredScope": complete_declared_scope,
            "completeWholeEarthDictionary": complete_whole_earth_dictionary,
            "zoomRanges": range_documents,
        },
    }
    manifest_bytes = (
        json.dumps(
            manifest,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8", "strict")
    return PackageArtifacts(
        manifest_bytes,
        bytes(records_output),
        bytes(index_output),
    )


def write_package(
    output_directory: Path,
    package_id: str,
    tile_payloads: Mapping[TileKey, bytes],
    *,
    complete_declared_scope: bool = True,
    complete_whole_earth_dictionary: bool = False,
) -> PackageArtifacts:
    """Write one new Android runtime package directory."""

    if not isinstance(output_directory, Path):
        raise RendererTilePackageError("package output directory must be a pathlib.Path")
    artifacts = build_package(
        package_id,
        tile_payloads,
        complete_declared_scope=complete_declared_scope,
        complete_whole_earth_dictionary=complete_whole_earth_dictionary,
    )
    try:
        output_directory.mkdir(parents=True, exist_ok=False)
        (output_directory / "manifest.json").write_bytes(artifacts.manifest_bytes)
        (output_directory / "records.fadictpack").write_bytes(
            artifacts.records_bytes
        )
        (output_directory / "tile-index.bin").write_bytes(artifacts.index_bytes)
    except OSError as error:
        raise RendererTilePackageError(
            f"runtime package could not be written: {error}"
        ) from error
    return artifacts


def _validate_package_id(package_id: str) -> None:
    if (
        type(package_id) is not str
        or not package_id
        or package_id in {".", ".."}
        or any(character in package_id for character in ("/", "\\", "\0"))
    ):
        raise RendererTilePackageError("package ID is empty or path-unsafe")


def _package_zoom_ranges(
    coordinates: tuple[TileKey, ...],
) -> tuple[tuple[int, int, int, int, int], ...]:
    ranges = []
    for zoom in sorted({coordinate.z for coordinate in coordinates}):
        at_zoom = tuple(
            coordinate for coordinate in coordinates if coordinate.z == zoom
        )
        ranges.append(
            (
                zoom,
                min(coordinate.x for coordinate in at_zoom),
                max(coordinate.x for coordinate in at_zoom),
                min(coordinate.y for coordinate in at_zoom),
                max(coordinate.y for coordinate in at_zoom),
            )
        )
    return tuple(ranges)


encode_raw_deflate = raw_deflate
build_renderer_tile_package = build_package
write_renderer_tile_package = write_package


def _decode_renderer_record(
    requested_tile: TileKey,
    canonical: bytes,
) -> RendererRecord:
    reader = _ByteReader(canonical)
    reader.n8_header(_RENDERER_RECORD_TAG, "renderer record")
    feature_id = reader.u64()
    canonical_variant_id = reader.u64()
    owner_tile = TileKey.from_packed(reader.u64())
    world_wrap = reader.i32()
    variant_bytes = reader.blob(MAX_RENDERER_RECORD_BYTES, "canonical variant")
    reader.finish("renderer record has trailing bytes")
    encoded_variant = b"".join(
        (
            CANONICAL_MAGIC,
            bytes((_ENCODED_CANONICAL_VARIANT_TAG,)),
            struct.pack("<Q", canonical_variant_id),
            struct.pack("<I", len(variant_bytes)),
            variant_bytes,
        )
    )
    variant = decode_canonical_variant(encoded_variant)
    try:
        subtype = SemanticSubtype(variant.semantic_subtype)
    except ValueError as error:
        raise RendererTilePackageError(
            "canonical variant semantic subtype is unknown"
        ) from error
    if (variant.feature_kind is FeatureKind.LABEL) != (subtype.value < 500):
        raise RendererTilePackageError(
            "feature kind and semantic subtype disagree"
        )
    if not (
        0 <= variant.min_zoom_centi <= variant.fade_in_centi
        <= variant.fade_out_centi <= variant.max_zoom_centi <= 10_000
    ):
        raise RendererTilePackageError("canonical variant zoom interval is invalid")
    if variant.feature_kind is FeatureKind.LABEL:
        if variant.placement.style_policy_sha256 != _PRESENTATION_POLICY_IDENTITY:
            raise RendererTilePackageError(
                "label placement uses an unsupported presentation policy"
            )
        if (
            variant.placement.semantic_priority != variant.priority
            or variant.placement.display_min_zoom_centi != variant.min_zoom_centi
            or variant.placement.display_max_zoom_centi != variant.max_zoom_centi
        ):
            raise RendererTilePackageError(
                "label placement and renderer visibility disagree"
            )
    posting = TilePosting(
        requested_tile=requested_tile,
        feature_id=feature_id,
        canonical_variant_id=canonical_variant_id,
        owner_tile=owner_tile,
        world_wrap=world_wrap,
    )
    record = RendererRecord(posting, variant)
    if renderer_record_bytes(record) != canonical:
        raise RendererTilePackageError("renderer record is not canonical")
    return record


def _decode_sourced_text(
    canonical: bytes,
    expected_digest: bytes,
) -> DecodedSourcedText:
    if not canonical or len(canonical) > MAX_SOURCED_TEXT_RECORD_BYTES:
        raise RendererTilePackageError(
            "sourced-text record byte length is outside its bound"
        )
    actual_digest = hashlib.sha256(
        SOURCED_TEXT_IDENTITY_DOMAIN + canonical
    ).digest()
    if actual_digest != expected_digest:
        raise RendererTilePackageError(
            "sourced-text record full SHA-256 does not match"
        )
    reader = _ByteReader(canonical)
    if (
        reader.u8() != SOURCED_TEXT_RECORD_TAG
        or reader.u8() != SOURCED_TEXT_RECORD_VERSION
    ):
        raise RendererTilePackageError(
            "sourced-text record tag or version is unsupported"
        )
    if reader.take(32) != _UNICODE_SCRIPT_PROFILE_IDENTITY:
        raise RendererTilePackageError(
            "sourced-text Unicode profile identity is unavailable"
        )
    if reader.take(32) != _SOURCED_TEXT_POLICY_IDENTITY:
        raise RendererTilePackageError(
            "sourced-text policy identity is unavailable"
        )
    try:
        layout = LayoutMode(reader.u8())
        gap = EnglishGapReason(reader.u8())
    except ValueError as error:
        raise RendererTilePackageError(
            "sourced-text layout or gap reason is unknown"
        ) from error
    primary_signals = _decode_script_signals(reader.u8())
    primary_field_id = _nonzero_field_id(reader.u64())
    primary_text = _read_sourced_text(reader, "primary")
    english_field_flag = reader.u8()
    if english_field_flag not in (0, 1):
        raise RendererTilePackageError(
            "English source-field presence flag is invalid"
        )
    english_field_id = (
        _nonzero_field_id(reader.u64()) if english_field_flag else None
    )
    english_text_flag = reader.u8()
    if english_text_flag not in (0, 1):
        raise RendererTilePackageError("English text presence flag is invalid")
    if english_text_flag:
        english_signals = _decode_script_signals(reader.u8())
        english_text = _read_sourced_text(reader, "English")
    else:
        english_signals = None
        english_text = None
    reader.finish("sourced-text record has trailing bytes")
    _validate_sourced_text_structure(
        primary_text,
        primary_field_id,
        english_text,
        english_field_id,
        layout,
        gap,
        primary_signals,
        english_signals,
    )
    return DecodedSourcedText(
        primary_text,
        primary_field_id,
        english_text,
        english_field_id,
        layout,
        gap,
        primary_signals,
        english_signals,
        canonical,
        actual_digest,
    )


def _decode_script_signals(mask: int) -> ScriptSignals:
    if mask not in range(8):
        raise RendererTilePackageError("script signal mask is invalid")
    return ScriptSignals(bool(mask & 1), bool(mask & 2), bool(mask & 4))


def _nonzero_field_id(value: int) -> int:
    if value == 0:
        raise RendererTilePackageError("source-field ID must be nonzero")
    return value


def _read_sourced_text(reader: _ByteReader, label: str) -> str:
    length = reader.u32()
    if length > MAX_SOURCED_TEXT_UTF8_BYTES or length > reader.remaining:
        raise RendererTilePackageError(
            f"{label} sourced text exceeds its UTF-8 bound"
        )
    try:
        value = reader.take(length).decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise RendererTilePackageError(
            f"{label} sourced text is not strict UTF-8"
        ) from error
    if not value or ord(value[-1]) in _END_TRIM_SCALARS:
        raise RendererTilePackageError(
            f"{label} sourced text is not canonically end-trimmed"
        )
    return value


def _validate_sourced_text_structure(
    primary_text: str,
    primary_field_id: int,
    english_text: str | None,
    english_field_id: int | None,
    layout: LayoutMode,
    gap: EnglishGapReason,
    primary_signals: ScriptSignals,
    english_signals: ScriptSignals | None,
) -> None:
    if english_text is not None and english_field_id is None:
        raise RendererTilePackageError(
            "English text lacks exact source-field evidence"
        )
    if (
        english_field_id == primary_field_id
        and gap is not EnglishGapReason.ENGLISH_FIELD_IS_PRIMARY
    ):
        raise RendererTilePackageError(
            "English and primary source-field identity conflict"
        )
    if layout is LayoutMode.SINGLE:
        if (
            gap is EnglishGapReason.NONE
            or english_text is not None
            or english_signals is not None
        ):
            raise RendererTilePackageError(
                "single-line sourced text retains bilingual state"
            )
        if (
            gap is EnglishGapReason.PRIMARY_NOT_ELIGIBLE
            and primary_signals.has_strong_non_latin
        ):
            raise RendererTilePackageError(
                "eligible primary is mislabeled not eligible"
            )
        if (
            gap is EnglishGapReason.MISSING
            and not primary_signals.has_strong_non_latin
        ):
            raise RendererTilePackageError(
                "ineligible primary is mislabeled missing English"
            )
        return
    if (
        gap is not EnglishGapReason.NONE
        or english_text is None
        or english_field_id is None
        or english_signals is None
        or english_field_id == primary_field_id
        or english_text == primary_text
        or not primary_signals.has_strong_non_latin
        or not english_signals.has_strong_latin
        or english_signals.has_strong_non_latin
        or english_signals.has_unknown
    ):
        raise RendererTilePackageError(
            "bilingual sourced-text structure is inconsistent"
        )


def _validate_sourced_text_link(
    renderer: RendererRecord,
    sourced: DecodedSourcedText | None,
) -> None:
    is_label = renderer.variant.feature_kind is FeatureKind.LABEL
    if is_label != (sourced is not None):
        raise RendererTilePackageError(
            "labels require sourced text and non-label records forbid it"
        )
    if sourced is not None:
        if renderer.variant.text != sourced.primary_text:
            raise RendererTilePackageError(
                "canonical variant text differs from sourced primary text"
            )
        if (
            renderer.variant.placement.text_source_field_id
            != sourced.primary_source_field_id
        ):
            raise RendererTilePackageError(
                "placement source field differs from sourced primary field"
            )


class _ByteReader:
    def __init__(self, data: bytes) -> None:
        self._data = data
        self._offset = 0

    @property
    def remaining(self) -> int:
        return len(self._data) - self._offset

    def take(self, length: int) -> bytes:
        if length < 0 or length > self.remaining:
            raise RendererTilePackageError("binary reference tile ended early")
        start = self._offset
        self._offset += length
        return self._data[start : self._offset]

    def expect(self, expected: bytes, message: str) -> None:
        if self.take(len(expected)) != expected:
            raise RendererTilePackageError(message)

    def n8_header(self, tag: int, label: str) -> None:
        self.expect(CANONICAL_MAGIC, f"{label} magic is unsupported")
        if self.u8() != tag:
            raise RendererTilePackageError(f"{label} tag is unsupported")

    def u8(self) -> int:
        return self.take(1)[0]

    def u32(self) -> int:
        return struct.unpack("<I", self.take(4))[0]

    def i32(self) -> int:
        return struct.unpack("<i", self.take(4))[0]

    def u64(self) -> int:
        return struct.unpack("<Q", self.take(8))[0]

    def blob(self, maximum: int, label: str) -> bytes:
        length = self.u32()
        if length > maximum or length > self.remaining:
            raise RendererTilePackageError(f"{label} exceeds its byte bound")
        return self.take(length)

    def finish(self, message: str) -> None:
        if self.remaining:
            raise RendererTilePackageError(message)
