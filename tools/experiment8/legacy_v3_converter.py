from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import sqlite3
import struct
import unicodedata
import zlib
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from .model import TileKey
from .reference_presentation_policy import (
    LABEL_ACTIVE_BAND_LIMIT,
    LABEL_DISPLAY_MAX_ZOOM_CENTI,
    LABEL_FADE_OUT_ZOOM_CENTI,
    LINE_LABEL_REPEAT_SPACING_PX,
    PRESENTATION_POLICY_SHA256,
    REFERENCE_LABEL_COLLISION_GROUP,
    LabelFacts,
    SemanticSubtype,
    SourceEvidenceContext,
    SubtypeCatalogCounts,
    outline_visibility_rule,
    prominence_decision_for_label,
    prominence_decision_sha256,
    visibility_rule_for_label,
)
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    INDEX_FLAG_PRESENT,
    MAX_TILE_BYTES,
    PAYLOAD_SCHEMA,
    SOURCED_TEXT_POLICY_SHA256,
    RendererTileRecord,
    decode_tile_payload,
    encode_index_entry,
    encode_tile_payload,
    raw_deflate,
    raw_hash32,
)
from .semantic_model import (
    FeatureKind,
    GeometryKind,
    LayerGroup,
    PlacementSourceKind,
    ProminenceTier as RendererProminenceTier,
    RendererGeometry,
    RendererRecord,
    SourceGeometry,
    TextEvidenceKind,
    TilePosting,
    empty_normalized_placement,
    make_canonical_variant,
    make_normalized_placement,
    renderer_geometry_fingerprint,
    renderer_geometry_from_source,
    renderer_order_key,
    renderer_record_bytes,
)
from .semantic_policy import (
    SEMANTIC_POLICY_SHA256,
    SOURCE_LAYER_POLICIES,
    SemanticClassification,
    SemanticPolicyError,
    classification_for_style_rule,
    source_style_identity_is_owned,
)
from .sourced_text import UNICODE_SCRIPT_PROFILE_SHA256, create_sourced_map_text


_STATE_SCHEMA = "flightalert.experiment8.legacy-v3-state.v1"
_REPORT_SCHEMA = "flightalert.experiment8.legacy-v3-conversion.v1"
_SOURCE_FEATURE_DOMAIN = b"FAE8LEGACYFEATURE1\0"
_IDENTITY_DOMAIN = b"FAE8LEGACYID1\0"
_CLASSIFIER_SHA256 = SEMANTIC_POLICY_SHA256
_DECLARED_EXTENT = 4096
_MAX_MANIFEST_BYTES = 16 * 1024 * 1024
_MAX_LEGACY_RAW_TILE_BYTES = 64 * 1024 * 1024
_LABEL_EXPECTED_GROUP = {
    SemanticSubtype.COUNTRY_TERRITORY: LayerGroup.REGIONS,
    SemanticSubtype.FIRST_ORDER_REGION: LayerGroup.REGIONS,
    SemanticSubtype.SECOND_LOCAL_REGION: LayerGroup.REGIONS,
    SemanticSubtype.CAPITAL_MAJOR_CITY: LayerGroup.PLACES,
    SemanticSubtype.CITY_TOWN: LayerGroup.PLACES,
    SemanticSubtype.LOCAL_PLACE: LayerGroup.PLACES,
    SemanticSubtype.ISLAND_ISLET: LayerGroup.PLACES,
    SemanticSubtype.OCEAN_SEA: LayerGroup.WATER,
    SemanticSubtype.BAY_SOUND: LayerGroup.WATER,
    SemanticSubtype.LAKE_RESERVOIR: LayerGroup.WATER,
    SemanticSubtype.RIVER: LayerGroup.WATER,
    SemanticSubtype.STREAM_CREEK: LayerGroup.WATER,
    SemanticSubtype.CANAL_CHANNEL: LayerGroup.WATER,
    SemanticSubtype.UNSPECIFIED_WATERCOURSE: LayerGroup.WATER,
    SemanticSubtype.PROTECTED_LAND: LayerGroup.PUBLIC_LANDS,
}


class LegacyConversionError(ValueError):
    """Legacy input cannot be converted without weakening source honesty."""


def _converter_sha256() -> str:
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


@dataclass(frozen=True, order=True, slots=True)
class TileWindow:
    z: int
    x_min: int
    x_max: int
    y_min: int
    y_max: int

    def __post_init__(self) -> None:
        if type(self.z) is not int or not 0 <= self.z <= 29:
            raise LegacyConversionError("window zoom is outside [0, 29]")
        limit = 1 << self.z
        values = (self.x_min, self.x_max, self.y_min, self.y_max)
        if any(type(value) is not int for value in values):
            raise LegacyConversionError("window bounds must be exact integers")
        if not (0 <= self.x_min <= self.x_max < limit):
            raise LegacyConversionError("window x bounds are invalid")
        if not (0 <= self.y_min <= self.y_max < limit):
            raise LegacyConversionError("window y bounds are invalid")

    @property
    def tile_count(self) -> int:
        return (self.x_max - self.x_min + 1) * (self.y_max - self.y_min + 1)

    def coordinates(self) -> Sequence[TileKey]:
        return tuple(
            TileKey(self.z, x, y)
            for y in range(self.y_min, self.y_max + 1)
            for x in range(self.x_min, self.x_max + 1)
        )

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
class ConversionResult:
    complete: bool
    processed_tiles: int
    planned_tiles: int
    output_directory: Path
    partial_directory: Path | None
    report: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class _SourceRange:
    window: TileWindow
    first_ordinal: int


@dataclass(frozen=True, slots=True)
class _SourcePackage:
    directory: Path
    manifest: Mapping[str, object]
    manifest_bytes: bytes
    manifest_sha256: str
    package_id: str
    ranges: tuple[_SourceRange, ...]
    tile_count: int
    records_bytes: int
    index_bytes: int
    complete_whole_earth_dictionary: bool

    def ordinal(self, tile: TileKey) -> int:
        for source_range in self.ranges:
            window = source_range.window
            if (
                tile.z == window.z
                and window.x_min <= tile.x <= window.x_max
                and window.y_min <= tile.y <= window.y_max
            ):
                return (
                    source_range.first_ordinal
                    + (tile.y - window.y_min) * (window.x_max - window.x_min + 1)
                    + tile.x
                    - window.x_min
                )
        raise LegacyConversionError(f"selected tile is outside source coverage: {tile}")


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
        raise LegacyConversionError("canonical JSON value is unsupported") from error


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise LegacyConversionError(f"JSON contains duplicate key {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise LegacyConversionError(f"JSON contains forbidden numeric constant {value}")


def _strict_json_document(raw: bytes, label: str) -> dict[str, object]:
    if type(raw) is not bytes:
        raise LegacyConversionError(f"{label} must be immutable bytes")
    try:
        text = raw.decode("utf-8", "strict")
        document = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except LegacyConversionError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise LegacyConversionError(f"{label} is not strict UTF-8 JSON") from error
    if type(document) is not dict:
        raise LegacyConversionError(f"{label} root must be an object")
    return document


def _exact_int(value: object, label: str, *, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise LegacyConversionError(
            f"{label} must be an exact integer in [{minimum}, {maximum}]"
        )
    return value


def _exact_text(value: object, label: str, *, allow_empty: bool = False) -> str:
    if type(value) is not str or (not allow_empty and not value):
        raise LegacyConversionError(f"{label} must be canonical text")
    if unicodedata.normalize("NFC", value) != value:
        raise LegacyConversionError(f"{label} is not NFC and cannot be silently repaired")
    for character in value:
        if character == "\ufffd" or unicodedata.category(character) in {"Cc", "Cs"}:
            raise LegacyConversionError(f"{label} contains invalid Unicode")
    if _looks_like_utf8_mojibake(value):
        raise LegacyConversionError(f"{label} contains probable UTF-8 mojibake")
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise LegacyConversionError(f"{label} is not strict UTF-8") from error
    return value


def _is_auditable_non_nfc_visible_text(value: object) -> bool:
    if (
        type(value) is not str
        or not value
        or unicodedata.normalize("NFC", value) == value
    ):
        return False
    for character in value:
        if character == "\ufffd" or unicodedata.category(character) in {"Cc", "Cs"}:
            raise LegacyConversionError("record visible text contains invalid Unicode")
    if _looks_like_utf8_mojibake(value):
        raise LegacyConversionError(
            "record visible text contains probable UTF-8 mojibake"
        )
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise LegacyConversionError("record visible text is not strict UTF-8") from error
    return True


def _validate_visible_text_structure(value: object) -> str:
    if type(value) is not str or not value:
        raise LegacyConversionError("record visible text must be canonical text")
    for character in value:
        if character == "\ufffd" or unicodedata.category(character) in {"Cc", "Cs"}:
            raise LegacyConversionError("record visible text contains invalid Unicode")
    try:
        value.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise LegacyConversionError("record visible text is not strict UTF-8") from error
    return value


def _looks_like_utf8_mojibake(value: str) -> bool:
    if value.isascii():
        return False
    # CP1252 preserves valid UTF-8 lead bytes C2..F4 as U+00C2..U+00F4.
    # Decode only a complete strict component so neighboring text is irrelevant.
    for index, character in enumerate(value):
        lead = ord(character)
        if 0xC2 <= lead <= 0xDF:
            component_length = 2
        elif 0xE0 <= lead <= 0xEF:
            component_length = 3
        elif 0xF0 <= lead <= 0xF4:
            component_length = 4
        else:
            continue
        component = value[index : index + component_length]
        if len(component) != component_length:
            continue
        try:
            encoded = component.encode("cp1252", "strict")
            repaired = encoded.decode("utf-8", "strict")
        except (UnicodeEncodeError, UnicodeDecodeError):
            continue
        if len(encoded) == component_length and repaired != component:
            return True
    return False


def _u64_identity(label: str) -> int:
    checked = _exact_text(label, "identity label")
    digest = hashlib.sha256(_IDENTITY_DOMAIN + checked.encode("utf-8", "strict")).digest()
    result = int.from_bytes(digest[:8], "big")
    if result == 0:
        raise LegacyConversionError("identity digest produced the forbidden zero hot ID")
    return result


def _load_source_package(source_directory: Path) -> _SourcePackage:
    if not isinstance(source_directory, Path) or not source_directory.is_dir():
        raise LegacyConversionError("legacy source directory is not readable")
    manifest_path = source_directory / "manifest.json"
    records_path = source_directory / "records.fadictpack"
    index_path = source_directory / "tile-index.bin"
    if not manifest_path.is_file() or not records_path.is_file() or not index_path.is_file():
        raise LegacyConversionError("legacy source lacks its three runtime files")
    manifest_bytes = manifest_path.read_bytes()
    if not manifest_bytes or len(manifest_bytes) > _MAX_MANIFEST_BYTES:
        raise LegacyConversionError("legacy manifest byte length is outside its bound")
    manifest = _strict_json_document(manifest_bytes, "legacy manifest")
    if manifest.get("schemaVersion") != 1:
        raise LegacyConversionError("legacy manifest schemaVersion must be exactly 1")
    storage = manifest.get("storage")
    if type(storage) is not dict or (
        storage.get("kind") != "flightalert-reference-tile-pack"
        or storage.get("version") != 1
        or storage.get("payloadEncoding") != "utf8-json"
        or storage.get("tileCompression") != "deflate-raw"
    ):
        raise LegacyConversionError("legacy storage contract is unsupported")
    package_id = _exact_text(manifest.get("packageId"), "legacy package ID")
    coverage = manifest.get("coverage")
    if type(coverage) is not dict:
        raise LegacyConversionError("legacy coverage must be an object")
    range_values = coverage.get("zoomRanges")
    if type(range_values) is not list or not range_values:
        raise LegacyConversionError("legacy zoom ranges must be a nonempty array")
    ranges: list[_SourceRange] = []
    first_ordinal = 0
    seen_zooms: set[int] = set()
    for index, value in enumerate(range_values):
        if type(value) is not dict:
            raise LegacyConversionError(f"legacy zoom range {index} must be an object")
        window = TileWindow(
            _exact_int(value.get("z"), "range zoom", minimum=0, maximum=29),
            _exact_int(value.get("xMin"), "range xMin", minimum=0, maximum=(1 << 29) - 1),
            _exact_int(value.get("xMax"), "range xMax", minimum=0, maximum=(1 << 29) - 1),
            _exact_int(value.get("yMin"), "range yMin", minimum=0, maximum=(1 << 29) - 1),
            _exact_int(value.get("yMax"), "range yMax", minimum=0, maximum=(1 << 29) - 1),
        )
        if window.z in seen_zooms:
            raise LegacyConversionError("legacy manifest has duplicate zoom ranges")
        seen_zooms.add(window.z)
        if value.get("tileCount") != window.tile_count:
            raise LegacyConversionError("legacy zoom range tileCount is inconsistent")
        ranges.append(_SourceRange(window, first_ordinal))
        first_ordinal += window.tile_count
    declared_tiles = _exact_int(
        coverage.get("tileCount"),
        "coverage tileCount",
        minimum=1,
        maximum=(1 << 63) - 1,
    )
    if declared_tiles != first_ordinal:
        raise LegacyConversionError("legacy coverage tileCount differs from its ranges")
    index_bytes = index_path.stat().st_size
    records_bytes = records_path.stat().st_size
    if index_bytes != declared_tiles * INDEX_ENTRY_BYTES:
        raise LegacyConversionError("legacy binary index length differs from coverage")
    if records_bytes <= 0:
        raise LegacyConversionError("legacy records pack is empty")
    size = manifest.get("size")
    if type(size) is dict:
        for field, actual in (
            ("payloadBytes", records_bytes),
            ("binaryIndexBytes", index_bytes),
        ):
            if field in size and size[field] != actual:
                raise LegacyConversionError(f"legacy manifest {field} differs from disk")
    complete_whole = coverage.get("completeWholeEarthDictionary") is True
    return _SourcePackage(
        directory=source_directory,
        manifest=manifest,
        manifest_bytes=manifest_bytes,
        manifest_sha256=hashlib.sha256(manifest_bytes).hexdigest(),
        package_id=package_id,
        ranges=tuple(ranges),
        tile_count=declared_tiles,
        records_bytes=records_bytes,
        index_bytes=index_bytes,
        complete_whole_earth_dictionary=complete_whole,
    )


def _normalize_plan(
    source: _SourcePackage,
    windows: Sequence[TileWindow],
    full: bool,
) -> tuple[TileWindow, ...]:
    if type(full) is not bool:
        raise LegacyConversionError("full-mode flag must be Boolean")
    normalized = tuple(windows)
    if full:
        if normalized:
            raise LegacyConversionError("full mode and selected windows are exclusive")
        return tuple(source_range.window for source_range in source.ranges)
    if not normalized:
        raise LegacyConversionError("window mode requires at least one tile window")
    if any(type(window) is not TileWindow for window in normalized):
        raise LegacyConversionError("selected windows must be exact TileWindow values")
    if len({window.z for window in normalized}) != len(normalized):
        raise LegacyConversionError("window mode supports one dense rectangle per zoom")
    source_by_zoom = {item.window.z: item.window for item in source.ranges}
    for window in normalized:
        available = source_by_zoom.get(window.z)
        if available is None or not (
            available.x_min <= window.x_min <= window.x_max <= available.x_max
            and available.y_min <= window.y_min <= window.y_max <= available.y_max
        ):
            raise LegacyConversionError("selected window is outside legacy coverage")
    return tuple(sorted(normalized))


def _plan_tile_at_ordinal(plan: Sequence[TileWindow], ordinal: int) -> TileKey:
    if type(ordinal) is not int or ordinal < 0:
        raise LegacyConversionError("planned tile ordinal must be nonnegative")
    remaining = ordinal
    for window in plan:
        if remaining < window.tile_count:
            width = window.x_max - window.x_min + 1
            y_offset, x_offset = divmod(remaining, width)
            return TileKey(
                window.z,
                window.x_min + x_offset,
                window.y_min + y_offset,
            )
        remaining -= window.tile_count
    raise LegacyConversionError("planned tile ordinal exceeds the selected scope")


def _read_source_tile(
    source: _SourcePackage,
    tile: TileKey,
    index_handle: object,
    records_handle: object,
) -> tuple[dict[str, object], bytes, int, int]:
    ordinal = source.ordinal(tile)
    index_handle.seek(ordinal * INDEX_ENTRY_BYTES)
    entry = index_handle.read(INDEX_ENTRY_BYTES)
    if len(entry) != INDEX_ENTRY_BYTES:
        raise LegacyConversionError(f"legacy index ended early at {tile}")
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    if flags not in (0, INDEX_FLAG_PRESENT):
        raise LegacyConversionError(f"legacy index flags are unsupported at {tile}")
    if not 0 < compressed_length <= source.records_bytes:
        raise LegacyConversionError(f"legacy compressed length is invalid at {tile}")
    if not 0 < raw_length <= _MAX_LEGACY_RAW_TILE_BYTES:
        raise LegacyConversionError(f"legacy raw length is invalid at {tile}")
    if offset + compressed_length > source.records_bytes:
        raise LegacyConversionError(f"legacy record range exceeds its pack at {tile}")
    records_handle.seek(offset)
    compressed = records_handle.read(compressed_length)
    if len(compressed) != compressed_length:
        raise LegacyConversionError(f"legacy records pack ended early at {tile}")
    inflater = zlib.decompressobj(wbits=-zlib.MAX_WBITS)
    try:
        raw = inflater.decompress(compressed, raw_length + 1) + inflater.flush()
    except zlib.error as error:
        raise LegacyConversionError(f"legacy raw DEFLATE is corrupt at {tile}") from error
    if (
        not inflater.eof
        or inflater.unused_data
        or inflater.unconsumed_tail
        or len(raw) != raw_length
    ):
        raise LegacyConversionError(f"legacy raw DEFLATE length is dishonest at {tile}")
    if raw_hash32(raw) != expected_hash32:
        raise LegacyConversionError(f"legacy raw hash32 differs at {tile}")
    document = _strict_json_document(raw, f"legacy tile {tile.z}/{tile.x}/{tile.y}")
    return document, hashlib.sha256(raw).digest(), compressed_length, raw_length


def _require_mapping(value: object, label: str) -> dict[str, object]:
    if type(value) is not dict:
        raise LegacyConversionError(f"{label} must be an object")
    return value


def _require_list(value: object, label: str) -> list[object]:
    if type(value) is not list:
        raise LegacyConversionError(f"{label} must be an array")
    return value


def _validate_tile_document(
    document: dict[str, object],
    tile: TileKey,
) -> tuple[list[object], list[object], list[object], dict[str, object]]:
    if document.get("schemaVersion") != 1:
        raise LegacyConversionError(f"legacy tile schema differs at {tile}")
    if document.get("tileKey") != f"{tile.z}/{tile.x}/{tile.y}":
        raise LegacyConversionError(f"legacy tileKey differs at {tile}")
    coordinate = _require_mapping(document.get("coordinate"), "tile coordinate")
    if coordinate != {"z": tile.z, "x": tile.x, "y": tile.y}:
        raise LegacyConversionError(f"legacy coordinate differs at {tile}")
    provenance = _require_mapping(document.get("provenance"), "tile provenance")
    _exact_text(provenance.get("vectorService"), "vector service")
    _exact_text(provenance.get("styleDigest"), "style digest")
    counts = _require_mapping(document.get("counts"), "tile counts")
    records = _require_mapping(document.get("records"), "tile records")
    labels = _require_list(records.get("labels"), "tile labels")
    boundaries = _require_list(records.get("boundaries"), "tile boundaries")
    transportation = _require_list(records.get("transportation"), "tile transportation")
    for name, values in (
        ("labels", labels),
        ("boundaries", boundaries),
        ("transportation", transportation),
    ):
        if counts.get(name) != len(values):
            raise LegacyConversionError(f"legacy {name} count differs at {tile}")
    return labels, boundaries, transportation, provenance


@dataclass(frozen=True, slots=True)
class _AuditedGeometry:
    parts: tuple[int, ...]
    coordinates: tuple[int, ...]
    bounds: tuple[int, int, int, int]
    point_count: int


def _audit_record_geometry(record: dict[str, object]) -> _AuditedGeometry:
    geometry = _require_mapping(record.get("geometry"), "record geometry")
    rings = _require_list(geometry.get("rings"), "geometry rings")
    ring_count = _exact_int(
        geometry.get("ringCount"), "geometry ringCount", minimum=1, maximum=1_048_576
    )
    point_count = _exact_int(
        geometry.get("pointCount"), "geometry pointCount", minimum=1, maximum=1_048_576
    )
    if len(rings) != ring_count:
        raise LegacyConversionError("geometry ringCount differs from its rings")
    offsets: list[int] = []
    coordinates: list[int] = []
    counted = 0
    for ring_index, ring_value in enumerate(rings):
        ring = _require_list(ring_value, f"geometry ring {ring_index}")
        if not ring:
            raise LegacyConversionError("geometry contains an empty ring")
        offsets.append(counted)
        for point_index, point_value in enumerate(ring):
            point = _require_list(point_value, f"geometry point {point_index}")
            if len(point) != 2:
                raise LegacyConversionError("geometry point must contain x and y")
            x = _exact_int(point[0], "geometry x", minimum=-(1 << 63), maximum=(1 << 63) - 1)
            y = _exact_int(point[1], "geometry y", minimum=-(1 << 63), maximum=(1 << 63) - 1)
            coordinates.extend((x, y))
            counted += 1
    if counted != point_count:
        raise LegacyConversionError("geometry pointCount differs from its rings")
    bounds_value = _require_mapping(geometry.get("bounds"), "geometry bounds")
    bounds = (
        _exact_int(bounds_value.get("minX"), "geometry minX", minimum=-(1 << 63), maximum=(1 << 63) - 1),
        _exact_int(bounds_value.get("minY"), "geometry minY", minimum=-(1 << 63), maximum=(1 << 63) - 1),
        _exact_int(bounds_value.get("maxX"), "geometry maxX", minimum=-(1 << 63), maximum=(1 << 63) - 1),
        _exact_int(bounds_value.get("maxY"), "geometry maxY", minimum=-(1 << 63), maximum=(1 << 63) - 1),
    )
    actual_bounds = (
        min(coordinates[0::2]),
        min(coordinates[1::2]),
        max(coordinates[0::2]),
        max(coordinates[1::2]),
    )
    if bounds != actual_bounds:
        raise LegacyConversionError("geometry bounds differ from exact points")
    return _AuditedGeometry(
        parts=tuple(offsets),
        coordinates=tuple(coordinates),
        bounds=bounds,
        point_count=counted,
    )


def _record_geometries(
    record: dict[str, object],
    tile: TileKey,
    feature_kind: FeatureKind,
) -> tuple[tuple[tuple[int, SourceGeometry, RendererGeometry], ...], int]:
    audited = _audit_record_geometry(record)
    if feature_kind is FeatureKind.LABEL:
        placement = record.get("labelPlacement")
        style_placement = record.get("styleLabelPlacement")
        if placement not in {"point", "line"} or style_placement not in {
            "point",
            "line",
        }:
            raise LegacyConversionError("label placement fields are unsupported")
        if placement == "point":
            part_ends = audited.parts[1:] + (audited.point_count,)
            if any(
                end - start != 1
                for start, end in zip(audited.parts, part_ends, strict=True)
            ):
                raise LegacyConversionError(
                    "point label source parts must each retain exactly one source point"
                )
            points = tuple(
                (
                    audited.coordinates[start * 2],
                    audited.coordinates[start * 2 + 1],
                )
                for start in audited.parts
            )
            seen_points: set[tuple[int, int]] = set()
            redundant_exact_point_parts = 0
            candidates: list[tuple[int, SourceGeometry, RendererGeometry]] = []
            # Source ring order is the stable part ordinal; no midpoint or
            # coordinate-derived reordering is allowed here.
            for source_part_ordinal, (x, y) in enumerate(points):
                if (x, y) in seen_points:
                    redundant_exact_point_parts += 1
                    continue
                seen_points.add((x, y))
                try:
                    source_geometry = SourceGeometry(
                        kind=GeometryKind.POINT,
                        tile_key=tile,
                        source_zoom=tile.z,
                        declared_extent=_DECLARED_EXTENT,
                        parts=(0,),
                        source_local_coordinates=(x, y),
                        bounds=(x, y, x, y),
                    )
                    candidates.append(
                        (
                            source_part_ordinal,
                            source_geometry,
                            renderer_geometry_from_source(source_geometry),
                        )
                    )
                except ValueError as error:
                    raise LegacyConversionError(
                        f"legacy geometry is invalid: {error}"
                    ) from error
            return tuple(candidates), redundant_exact_point_parts
        else:
            if style_placement != "line":
                raise LegacyConversionError(
                    "resolved line label contradicts point-only source styling"
                )
            kind = GeometryKind.PATH
    elif feature_kind is FeatureKind.LINE:
        kind = GeometryKind.PATH
    elif feature_kind is FeatureKind.POLYGON_OUTLINE:
        kind = GeometryKind.POLYGON
    else:
        raise LegacyConversionError("legacy feature kind is unsupported")
    try:
        source_geometry = SourceGeometry(
            kind=kind,
            tile_key=tile,
            source_zoom=tile.z,
            declared_extent=_DECLARED_EXTENT,
            parts=audited.parts,
            source_local_coordinates=audited.coordinates,
            bounds=audited.bounds,
        )
        return ((0, source_geometry, renderer_geometry_from_source(source_geometry)),), 0
    except ValueError as error:
        raise LegacyConversionError(f"legacy geometry is invalid: {error}") from error


def _record_style_ids(record: dict[str, object]) -> tuple[str, ...]:
    values = _require_list(record.get("styleLayerIds"), "record styleLayerIds")
    result = tuple(
        _exact_text(value, f"styleLayerIds[{index}]")
        for index, value in enumerate(values)
    )
    if len(set(result)) != len(result):
        raise LegacyConversionError("record styleLayerIds contains duplicates")
    return result


def _select_classification(
    record: dict[str, object],
    role: str,
) -> tuple[SemanticClassification | None, str | None, tuple[str, ...]]:
    source_layer = _exact_text(record.get("sourceLayer"), "record sourceLayer")
    style_ids = _record_style_ids(record)
    source_policy = SOURCE_LAYER_POLICIES.get(source_layer)
    if source_policy is None:
        return None, f"{role}.unsupported_source_layer", style_ids
    if not style_ids:
        return None, f"{role}.unsupported_style_identity", style_ids
    if role == "labels":
        layer_type = "symbol"
    else:
        accepted = tuple(
            value for value in source_policy.accepted_types if value in {"line", "fill"}
        )
        if len(accepted) != 1:
            return None, f"{role}.unsupported_style_identity", style_ids
        layer_type = accepted[0]
    if any(not source_style_identity_is_owned(source_layer, item) for item in style_ids):
        return None, f"{role}.unsupported_style_identity", style_ids
    properties = _require_mapping(record.get("properties"), "record properties")
    classifications: list[SemanticClassification] = []
    for style_id in style_ids:
        try:
            classifications.append(
                classification_for_style_rule(
                    source_layer,
                    style_id,
                    layer_type,
                    properties,
                )
            )
        except SemanticPolicyError as error:
            raise LegacyConversionError(
                f"owned style classification is invalid for {source_layer}/{style_id}: {error}"
            ) from error
    unique = tuple(dict.fromkeys(classifications))
    if len(unique) == 1:
        selected = unique[0]
    else:
        default_subtype = (
            source_policy.default_label_subtype
            if role == "labels"
            else source_policy.default_line_subtype
        )
        matching = tuple(item for item in unique if item.semantic_subtype == default_subtype)
        if len(matching) != 1:
            return None, f"{role}.ambiguous_style_classification", style_ids
        selected = matching[0]
        comparable = {
            (
                item.layer_group,
                item.feature_kind,
                item.kind,
                item.render_style_token_id,
                item.land_evidence,
                item.protected_status,
            )
            for item in unique
        }
        if len(comparable) != 1:
            return None, f"{role}.ambiguous_style_classification", style_ids
    try:
        subtype = SemanticSubtype(selected.semantic_subtype)
    except ValueError:
        return None, f"{role}.unsupported_semantic_subtype", style_ids
    if role == "labels":
        if selected.feature_kind is not FeatureKind.LABEL:
            raise LegacyConversionError("label record classified as a non-label")
        if _LABEL_EXPECTED_GROUP.get(subtype) is not selected.layer_group:
            return None, f"{role}.unsupported_presentation_group", style_ids
    elif selected.feature_kind not in {FeatureKind.LINE, FeatureKind.POLYGON_OUTLINE}:
        raise LegacyConversionError("boundary record classified as a label")
    return selected, None, style_ids


def _feature_sha256(
    source: _SourcePackage,
    raw_tile_sha256: bytes,
    tile: TileKey,
    role: str,
    ordinal: int,
    record: dict[str, object],
) -> bytes:
    canonical = _canonical_json_bytes(record)
    return hashlib.sha256(
        _SOURCE_FEATURE_DOMAIN
        + bytes.fromhex(source.manifest_sha256)
        + raw_tile_sha256
        + tile.packed.to_bytes(8, "big")
        + role.encode("ascii")
        + ordinal.to_bytes(4, "big")
        + canonical
    ).digest()


def _record_to_renderer(
    *,
    source: _SourcePackage,
    tile: TileKey,
    raw_tile_sha256: bytes,
    provenance: dict[str, object],
    role: str,
    ordinal: int,
    value: object,
) -> tuple[tuple[RendererTileRecord, ...], str | None, int]:
    record = _require_mapping(value, f"{role} record {ordinal}")
    expected_role = "label" if role == "labels" else "boundary"
    if record.get("role") != expected_role:
        raise LegacyConversionError(f"{role} record has the wrong role")
    _exact_int(record.get("featureIndex"), "record featureIndex", minimum=0, maximum=(1 << 32) - 1)
    dedupe_key = _exact_text(record.get("dedupeKey"), "record dedupeKey")
    if len(dedupe_key) != 24 or any(character not in "0123456789abcdef" for character in dedupe_key):
        raise LegacyConversionError("record dedupeKey must be exact lowercase 96-bit hex")
    classification, dropped_reason, style_ids = _select_classification(record, role)
    if classification is None:
        # Unsupported records are still structurally audited before being omitted.
        _audit_record_geometry(record)
        return (), dropped_reason, 0
    geometry_candidates, redundant_exact_point_parts = _record_geometries(
        record,
        tile,
        classification.feature_kind,
    )
    source_feature_sha256 = _feature_sha256(
        source,
        raw_tile_sha256,
        tile,
        role,
        ordinal,
        record,
    )
    feature_id = int.from_bytes(source_feature_sha256[:8], "big")
    if feature_id == 0:
        raise LegacyConversionError("source feature produced the forbidden zero hot ID")
    vector_service = _exact_text(provenance.get("vectorService"), "vector service")
    style_digest = _exact_text(provenance.get("styleDigest"), "style digest")
    source_layer = _exact_text(record.get("sourceLayer"), "record sourceLayer")
    source_layer_id = _u64_identity(f"{vector_service}.layer.{source_layer}")
    scale_band_id = _u64_identity(
        f"{vector_service}.style.{style_digest}.zoom.{tile.z}"
    )
    source_style_ids = tuple(
        _u64_identity(f"{vector_service}.style-layer.{source_layer}.{style_id}")
        for style_id in style_ids
    )
    dedupe_id = _u64_identity(
        f"{source.package_id}.dedupe.{source_layer}.{dedupe_key}"
    )
    flags = (
        (1 if classification.disputed else 0)
        | (2 if classification.coastline else 0)
        | (4 if classification.intermittent else 0)
        | (8 if classification.tunnel else 0)
        | (16 if classification.shield else 0)
        | (32 if classification.one_way else 0)
    )
    subtype = SemanticSubtype(classification.semantic_subtype)
    sourced_text = None
    if classification.feature_kind is FeatureKind.LABEL:
        names = _require_mapping(record.get("names"), "record names")
        declared_english = names.get("_name_en")
        visible_text = _validate_visible_text_structure(record.get("text"))
        if (
            type(declared_english) is str
            and _looks_like_utf8_mojibake(declared_english)
        ) or _looks_like_utf8_mojibake(visible_text):
            return (), "labels.probable_mojibake", redundant_exact_point_parts
        primary_field_id = _u64_identity(
            f"{vector_service}.{source_layer}.record.text"
        )
        english_field_id = (
            _u64_identity(f"{vector_service}.{source_layer}.names._name_en")
            if declared_english is not None
            else None
        )
        primary_is_non_nfc = _is_auditable_non_nfc_visible_text(visible_text)
        text = (
            visible_text
            if primary_is_non_nfc
            else _exact_text(visible_text, "record visible text")
        )
        assert type(text) is str
        sourced_text = create_sourced_map_text(
            primary=text,
            primary_source_field_id=primary_field_id,
            declared_english=declared_english,
            english_source_field_id=english_field_id,
        )
        if primary_is_non_nfc:
            return (), "labels.invalid_visible_text", redundant_exact_point_parts
        if sourced_text.primary_text != text:
            return (), "labels.invalid_visible_text", redundant_exact_point_parts
        if (
            declared_english is not None
            and sourced_text.english_text is not None
            and sourced_text.english_text != declared_english
        ):
            return (), "labels.invalid_english_text", redundant_exact_point_parts
        evidence_context = SourceEvidenceContext(
            source_generation_sha256=source.manifest_sha256,
            classifier_sha256=_CLASSIFIER_SHA256,
            source_field_id=primary_field_id,
        )
        facts = LabelFacts(subtype=subtype, evidence_context=evidence_context)
        decision = prominence_decision_for_label(facts)
        visibility = visibility_rule_for_label(facts)
        minimum = visibility.min_zoom_centi
        maximum = LABEL_DISPLAY_MAX_ZOOM_CENTI
        fade_in = visibility.full_alpha_zoom_centi
        fade_out = LABEL_FADE_OUT_ZOOM_CENTI
        draw_order = 0
        priority = decision.semantic_priority
    else:
        text = None
        visibility = outline_visibility_rule(subtype)
        minimum = visibility.min_zoom_centi
        maximum = visibility.max_zoom_centi
        fade_in = visibility.full_alpha_zoom_centi
        fade_out = visibility.fade_out_zoom_centi
        draw_order = visibility.draw_order
        priority = visibility.priority
    renderer_records: list[RendererTileRecord] = []
    try:
        for (
            _source_part_ordinal,
            _source_geometry,
            renderer_geometry,
        ) in geometry_candidates:
            geometry_identity = renderer_geometry_fingerprint(renderer_geometry)
            if classification.feature_kind is FeatureKind.LABEL:
                placement_kind = (
                    PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT
                    if renderer_geometry.kind is GeometryKind.POINT
                    and record.get("styleLabelPlacement") == "line"
                    else PlacementSourceKind.DIRECT_SOURCE_POINT
                    if renderer_geometry.kind is GeometryKind.POINT
                    else PlacementSourceKind.DIRECT_SOURCE_PATH
                )
                placement = make_normalized_placement(
                    text=text,
                    source_feature_sha256=source_feature_sha256,
                    placement_geometry_sha256=geometry_identity.full_sha256,
                    text_evidence_kind=TextEvidenceKind.SOURCE_FIELD,
                    text_source_field_id=primary_field_id,
                    placement_source_feature_id=feature_id,
                    placement_geometry_id=geometry_identity.hot_id,
                    source_tile=tile,
                    source_zoom=tile.z,
                    source_declared_extent=_DECLARED_EXTENT,
                    source_edge_domain=(0, 0, _DECLARED_EXTENT, _DECLARED_EXTENT),
                    placement_source_kind=placement_kind,
                    display_min_zoom_centi=visibility.min_zoom_centi,
                    display_max_zoom_centi=LABEL_DISPLAY_MAX_ZOOM_CENTI,
                    spacing_px=LINE_LABEL_REPEAT_SPACING_PX,
                    max_angle_degrees=visibility.max_bend_centi_degrees // 100,
                    collision_group=REFERENCE_LABEL_COLLISION_GROUP,
                    semantic_priority=decision.semantic_priority,
                    prominence_tier=RendererProminenceTier[decision.tier.name],
                    provider_rank=decision.provider_rank,
                    complete_geometry_measure_bucket=decision.complete_geometry_measure_bucket,
                    prominence_rule_id=decision.prominence_rule_id,
                    prominence_decision_sha256=bytes.fromhex(
                        prominence_decision_sha256(decision)
                    ),
                    avoid_edges=False,
                    keep_upright=True,
                    active_band_limit=LABEL_ACTIVE_BAND_LIMIT,
                    style_policy_sha256=bytes.fromhex(PRESENTATION_POLICY_SHA256),
                )
            else:
                placement = empty_normalized_placement()
            variant = make_canonical_variant(
                dedupe_id=dedupe_id,
                geometry_id=geometry_identity.hot_id,
                source_layer_id=source_layer_id,
                source_scale_band_id=scale_band_id,
                layer_group=classification.layer_group,
                feature_kind=classification.feature_kind,
                semantic_subtype=classification.semantic_subtype,
                source_style_layer_ids=source_style_ids,
                render_style_token_ids=(classification.render_style_token_id,),
                text=text,
                geometry=renderer_geometry,
                min_zoom_centi=minimum,
                max_zoom_centi=maximum,
                fade_in_centi=fade_in,
                fade_out_centi=fade_out,
                draw_order=draw_order,
                priority=priority,
                placement=placement,
                land_evidence=classification.land_evidence,
                protected_status=classification.protected_status,
                flags=flags,
            )
            posting = TilePosting(
                requested_tile=tile,
                feature_id=feature_id,
                canonical_variant_id=variant.canonical_variant_id,
                owner_tile=tile,
                world_wrap=0,
            )
            renderer_records.append(
                RendererTileRecord(RendererRecord(posting, variant), sourced_text)
            )
        return tuple(renderer_records), None, redundant_exact_point_parts
    except ValueError as error:
        raise LegacyConversionError(f"renderer record is invalid: {error}") from error


def _convert_tile_document(
    *,
    source: _SourcePackage,
    tile: TileKey,
    raw_tile_sha256: bytes,
    document: dict[str, object],
) -> tuple[bytes, Counter[str], Counter[str], Counter[str], Counter[str]]:
    labels, boundaries, transportation, provenance = _validate_tile_document(
        document, tile
    )
    input_counts = Counter(
        labels=len(labels),
        boundaries=len(boundaries),
        transportation=len(transportation),
    )
    converted_counts: Counter[str] = Counter()
    dropped_counts: Counter[str] = Counter()
    source_part_audit_counts: Counter[str] = Counter()
    renderer_records: list[RendererTileRecord] = []
    for role, values in (("labels", labels), ("boundaries", boundaries)):
        for ordinal, value in enumerate(values):
            converted, reason, redundant_exact_point_parts = _record_to_renderer(
                source=source,
                tile=tile,
                raw_tile_sha256=raw_tile_sha256,
                provenance=provenance,
                role=role,
                ordinal=ordinal,
                value=value,
            )
            if redundant_exact_point_parts:
                source_part_audit_counts[
                    f"{role}.redundant_exact_point_parts"
                ] += redundant_exact_point_parts
            if not converted:
                assert reason is not None
                dropped_counts[reason] += 1
            else:
                renderer_records.extend(converted)
                converted_counts[role] += len(converted)
    for ordinal, value in enumerate(transportation):
        record = _require_mapping(value, f"transportation record {ordinal}")
        if record.get("role") != "transportation":
            raise LegacyConversionError("transportation record has the wrong role")
        dropped_counts["transportation.unsupported"] += 1
    try:
        payload = encode_tile_payload(tile, renderer_records)
    except ValueError as error:
        raise LegacyConversionError(f"V3 tile encoding failed at {tile}: {error}") from error
    if len(payload) > MAX_TILE_BYTES:
        raise LegacyConversionError(f"V3 tile exceeds its Android bound at {tile}")
    return (
        payload,
        input_counts,
        converted_counts,
        dropped_counts,
        source_part_audit_counts,
    )


def _state_document(
    *,
    config_sha256: str,
    next_ordinal: int,
    records_bytes: int,
    index_bytes: int,
    input_counts: Counter[str],
    converted_counts: Counter[str],
    dropped_counts: Counter[str],
    source_part_audit_counts: Counter[str],
    selected_input_compressed_bytes: int,
    selected_input_raw_bytes: int,
) -> dict[str, object]:
    return {
        "schema": _STATE_SCHEMA,
        "configSha256": config_sha256,
        "nextOrdinal": next_ordinal,
        "recordsBytes": records_bytes,
        "indexBytes": index_bytes,
        "inputCounts": dict(sorted(input_counts.items())),
        "convertedCounts": dict(sorted(converted_counts.items())),
        "droppedCounts": dict(sorted(dropped_counts.items())),
        "sourcePartAuditCounts": dict(sorted(source_part_audit_counts.items())),
        "selectedInputCompressedBytes": selected_input_compressed_bytes,
        "selectedInputRawBytes": selected_input_raw_bytes,
    }


def _write_state(path: Path, state: Mapping[str, object]) -> None:
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_bytes(_canonical_json_bytes(state))
    os.replace(temporary, path)


def _counter_from_state(value: object, label: str) -> Counter[str]:
    mapping = _require_mapping(value, label)
    result: Counter[str] = Counter()
    for key, count in mapping.items():
        checked_key = _exact_text(key, f"{label} key")
        result[checked_key] = _exact_int(
            count, f"{label} count", minimum=0, maximum=(1 << 63) - 1
        )
    return result


def _load_state(path: Path, config_sha256: str, planned_tiles: int) -> dict[str, object]:
    if not path.is_file():
        raise LegacyConversionError("resume requested without a conversion state")
    state = _strict_json_document(path.read_bytes(), "conversion state")
    if state.get("schema") != _STATE_SCHEMA or state.get("configSha256") != config_sha256:
        raise LegacyConversionError("conversion state does not match this exact request")
    _exact_int(
        state.get("nextOrdinal"),
        "state nextOrdinal",
        minimum=0,
        maximum=planned_tiles,
    )
    _exact_int(
        state.get("recordsBytes"),
        "state recordsBytes",
        minimum=0,
        maximum=(1 << 63) - 1,
    )
    _exact_int(
        state.get("indexBytes"),
        "state indexBytes",
        minimum=0,
        maximum=planned_tiles * INDEX_ENTRY_BYTES,
    )
    _counter_from_state(state.get("inputCounts"), "state inputCounts")
    _counter_from_state(state.get("convertedCounts"), "state convertedCounts")
    _counter_from_state(state.get("droppedCounts"), "state droppedCounts")
    _counter_from_state(
        state.get("sourcePartAuditCounts"), "state sourcePartAuditCounts"
    )
    return state


def _flush_and_checkpoint(
    *,
    records_handle: object,
    index_handle: object,
    state_path: Path,
    state: Mapping[str, object],
) -> None:
    records_handle.flush()
    index_handle.flush()
    os.fsync(records_handle.fileno())
    os.fsync(index_handle.fileno())
    _write_state(state_path, state)


def _runtime_manifest(
    *,
    source: _SourcePackage,
    package_id: str,
    plan: Sequence[TileWindow],
    complete_declared_scope: bool,
    complete_whole_earth_dictionary: bool,
    renderer_semantic_stream_sha256: str | None,
    converter_sha256: str,
) -> bytes:
    tile_count = sum(window.tile_count for window in plan)
    document = {
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
            "zoomRanges": [window.document() for window in plan],
        },
        "source": {
            "format": "flightalert-reference-tile-pack.v1",
            "packageId": source.package_id,
            "manifestSha256": source.manifest_sha256,
            "semanticPolicySha256": _CLASSIFIER_SHA256,
            "converterSha256": converter_sha256,
        },
    }
    if renderer_semantic_stream_sha256 is not None:
        if len(renderer_semantic_stream_sha256) != 64 or any(
            character not in "0123456789abcdef"
            for character in renderer_semantic_stream_sha256
        ):
            raise LegacyConversionError("renderer semantic stream SHA-256 is invalid")
        document["rendererSemanticStreamSha256"] = renderer_semantic_stream_sha256
    return _canonical_json_bytes(document)


def _is_full_world_plan(plan: Sequence[TileWindow]) -> bool:
    return all(
        window.x_min == 0
        and window.y_min == 0
        and window.x_max == (1 << window.z) - 1
        and window.y_max == (1 << window.z) - 1
        for window in plan
    )


def _report_document(
    *,
    source: _SourcePackage,
    package_id: str,
    full: bool,
    plan: Sequence[TileWindow],
    state: Mapping[str, object],
    manifest_bytes: bytes,
    renderer_semantic_stream_sha256: str | None = None,
    subtype_counts: Mapping[SemanticSubtype, SubtypeCatalogCounts] | None = None,
    converter_sha256: str,
) -> dict[str, object]:
    planned_tiles = sum(window.tile_count for window in plan)
    audited_tiles = int(state["nextOrdinal"])
    output_records = int(state["recordsBytes"])
    output_index = int(state["indexBytes"])
    selected_compressed = int(state["selectedInputCompressedBytes"])
    projected_records = (
        (output_records * source.records_bytes + selected_compressed // 2)
        // selected_compressed
        if selected_compressed
        else 0
    )
    projected_runtime = (
        projected_records
        + source.tile_count * INDEX_ENTRY_BYTES
        + len(manifest_bytes)
    )
    whole_earth = (
        full
        and audited_tiles == source.tile_count
        and source.complete_whole_earth_dictionary
        and _is_full_world_plan(plan)
    )
    normalized_subtype_counts = []
    if subtype_counts is not None:
        if set(subtype_counts) != set(SemanticSubtype):
            raise LegacyConversionError("subtype audit does not cover the exact subtype set")
        normalized_subtype_counts = [
            {
                "semanticSubtype": subtype.value,
                "semanticSubtypeName": subtype.name,
                "distinctFeatureIds": subtype_counts[subtype].distinct_feature_count,
                "canonicalVariantIds": subtype_counts[subtype].canonical_variant_count,
                "postings": subtype_counts[subtype].posting_count,
            }
            for subtype in SemanticSubtype
        ]
    return {
        "schema": _REPORT_SCHEMA,
        "packageId": package_id,
        "sourcePackageId": source.package_id,
        "sourceManifestSha256": source.manifest_sha256,
        "converterSha256": converter_sha256,
        "sourceRecordsBytes": source.records_bytes,
        "sourceIndexBytes": source.index_bytes,
        "mode": "full" if full else "window",
        "plannedTileCount": planned_tiles,
        "auditedTileCount": audited_tiles,
        "plannedScopeAuditComplete": audited_tiles == planned_tiles,
        "allInputTilesAudited": full and audited_tiles == source.tile_count,
        "inputCounts": state["inputCounts"],
        "convertedCounts": state["convertedCounts"],
        "droppedCounts": state["droppedCounts"],
        "sourcePartAuditCounts": state["sourcePartAuditCounts"],
        "bytes": {
            "selectedInputCompressed": selected_compressed,
            "selectedInputRaw": state["selectedInputRawBytes"],
            "outputManifest": len(manifest_bytes),
            "outputRecords": output_records,
            "outputIndex": output_index,
            "outputRuntime": len(manifest_bytes) + output_records + output_index,
        },
        "projection": {
            "basis": "selected-output-records-to-selected-input-compressed-bytes",
            "projectedFullRecordsBytes": projected_records,
            "projectedFullRuntimeBytes": projected_runtime,
        },
        "runtimeClaims": {
            "completeDeclaredScope": audited_tiles == planned_tiles,
            "completeWholeEarthDictionary": whole_earth,
        },
        "rendererSemanticStreamSha256": renderer_semantic_stream_sha256,
        "subtypeCounts": normalized_subtype_counts,
        "classCatalog": {
            "emitted": False,
            "reason": (
                "renderer_contract_sha256_not_defined_by_current_runtime_package"
                if renderer_semantic_stream_sha256 is not None
                else "conversion_incomplete"
            ),
            "rendererSemanticStreamSha256": renderer_semantic_stream_sha256,
            "rendererContractSha256": None,
            "catalogSha256": None,
        },
        "windows": [window.document() for window in plan],
    }


def _result_progress_report(
    *,
    source: _SourcePackage,
    package_id: str,
    full: bool,
    plan: Sequence[TileWindow],
    state: Mapping[str, object],
    converter_sha256: str,
) -> dict[str, object]:
    manifest = _runtime_manifest(
        source=source,
        package_id=package_id,
        plan=plan,
        complete_declared_scope=False,
        complete_whole_earth_dictionary=False,
        renderer_semantic_stream_sha256=None,
        converter_sha256=converter_sha256,
    )
    return _report_document(
        source=source,
        package_id=package_id,
        full=full,
        plan=plan,
        state=state,
        manifest_bytes=manifest,
        converter_sha256=converter_sha256,
    )


def _output_ordinal(plan: Sequence[TileWindow], tile: TileKey) -> int:
    first_ordinal = 0
    for window in plan:
        if tile.z == window.z:
            if not (
                window.x_min <= tile.x <= window.x_max
                and window.y_min <= tile.y <= window.y_max
            ):
                break
            return (
                first_ordinal
                + (tile.y - window.y_min) * (window.x_max - window.x_min + 1)
                + tile.x
                - window.x_min
            )
        first_ordinal += window.tile_count
    raise LegacyConversionError("output semantic audit tile is outside its plan")


def _packed_plan_tiles(plan: Sequence[TileWindow]):
    for window in plan:
        for x in range(window.x_min, window.x_max + 1):
            for y in range(window.y_min, window.y_max + 1):
                yield TileKey(window.z, x, y)


def _audit_output_semantics(
    *,
    partial_directory: Path,
    plan: Sequence[TileWindow],
) -> tuple[str, dict[SemanticSubtype, SubtypeCatalogCounts]]:
    records_path = partial_directory / "records.fadictpack"
    index_path = partial_directory / "tile-index.bin"
    database_path = partial_directory / "identity-counts.sqlite"
    database_path.unlink(missing_ok=True)
    semantic_digest = hashlib.sha256()
    semantic_digest.update(b"flight-alert-exp8-semantic-v1\0")
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
        with index_path.open("rb") as index_handle, records_path.open("rb") as records_handle:
            for tile_number, tile in enumerate(_packed_plan_tiles(plan), start=1):
                ordinal = _output_ordinal(plan, tile)
                index_handle.seek(ordinal * INDEX_ENTRY_BYTES)
                entry = index_handle.read(INDEX_ENTRY_BYTES)
                if len(entry) != INDEX_ENTRY_BYTES:
                    raise LegacyConversionError("V3 semantic audit index ended early")
                offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
                    "<QIIII", entry
                )
                if flags != INDEX_FLAG_PRESENT or compressed_length <= 0 or raw_length <= 0:
                    raise LegacyConversionError("V3 semantic audit found a missing tile")
                records_handle.seek(offset)
                compressed = records_handle.read(compressed_length)
                if len(compressed) != compressed_length:
                    raise LegacyConversionError("V3 semantic audit records ended early")
                try:
                    payload = zlib.decompress(compressed, wbits=-zlib.MAX_WBITS)
                except zlib.error as error:
                    raise LegacyConversionError("V3 semantic audit DEFLATE is corrupt") from error
                if len(payload) != raw_length or raw_hash32(payload) != expected_hash32:
                    raise LegacyConversionError("V3 semantic audit tile integrity differs")
                decoded = decode_tile_payload(tile, payload)
                ordered = sorted(
                    (item.renderer_record for item in decoded.records),
                    key=renderer_order_key,
                )
                feature_rows: list[tuple[int, bytes]] = []
                variant_rows: list[tuple[int, bytes]] = []
                for record in ordered:
                    try:
                        subtype = SemanticSubtype(record.variant.semantic_subtype)
                    except ValueError as error:
                        raise LegacyConversionError(
                            "V3 semantic audit found an unsupported subtype"
                        ) from error
                    canonical = renderer_record_bytes(record)
                    body = struct.pack("<Q", tile.packed) + canonical
                    semantic_digest.update(struct.pack("<I", len(body)))
                    semantic_digest.update(body)
                    posting_counts[subtype] += 1
                    feature_rows.append(
                        (subtype.value, record.posting.feature_id.to_bytes(8, "big"))
                    )
                    variant_rows.append(
                        (
                            subtype.value,
                            record.variant.canonical_variant_id.to_bytes(8, "big"),
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
                if tile_number % 4096 == 0:
                    connection.commit()
        connection.commit()
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
        counts = {
            subtype: SubtypeCatalogCounts(
                distinct_feature_count=feature_counts.get(subtype.value, 0),
                canonical_variant_count=variant_counts.get(subtype.value, 0),
                posting_count=posting_counts[subtype],
            )
            for subtype in SemanticSubtype
        }
        return semantic_digest.hexdigest(), counts
    finally:
        if connection is not None:
            connection.close()
        database_path.unlink(missing_ok=True)


def convert_legacy_package(
    *,
    source_directory: Path,
    output_directory: Path,
    package_id: str,
    windows: Sequence[TileWindow] = (),
    full: bool = False,
    resume: bool = False,
    max_tiles: int | None = None,
    checkpoint_every: int = 256,
) -> ConversionResult:
    """Stream legacy tiles into a deterministic Android BINARY_V3 package."""

    if not isinstance(output_directory, Path):
        raise LegacyConversionError("output directory must be a pathlib.Path")
    checked_package_id = _exact_text(package_id, "output package ID")
    if checked_package_id in {".", ".."} or any(
        character in checked_package_id for character in ("/", "\\", "\0")
    ):
        raise LegacyConversionError("output package ID is path-unsafe")
    if type(resume) is not bool:
        raise LegacyConversionError("resume flag must be Boolean")
    if max_tiles is not None:
        _exact_int(max_tiles, "max_tiles", minimum=1, maximum=(1 << 63) - 1)
    _exact_int(
        checkpoint_every,
        "checkpoint_every",
        minimum=1,
        maximum=(1 << 31) - 1,
    )
    source = _load_source_package(source_directory)
    converter_sha256 = _converter_sha256()
    plan = _normalize_plan(source, windows, full)
    planned_tiles = sum(window.tile_count for window in plan)
    config = {
        "sourceManifestSha256": source.manifest_sha256,
        "sourceRecordsBytes": source.records_bytes,
        "sourceIndexBytes": source.index_bytes,
        "packageId": checked_package_id,
        "mode": "full" if full else "window",
        "windows": [window.document() for window in plan],
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "semanticPolicySha256": _CLASSIFIER_SHA256,
        "converterSha256": converter_sha256,
    }
    config_sha256 = hashlib.sha256(_canonical_json_bytes(config)).hexdigest()
    partial_directory = output_directory.with_name(output_directory.name + ".partial")
    state_path = partial_directory / "conversion-state.json"
    partial_records = partial_directory / "records.fadictpack"
    partial_index = partial_directory / "tile-index.bin"
    if output_directory.exists():
        raise LegacyConversionError("output directory already exists")
    if resume:
        if not partial_directory.is_dir():
            raise LegacyConversionError("resume requested without a partial directory")
        state = _load_state(state_path, config_sha256, planned_tiles)
    else:
        if partial_directory.exists():
            raise LegacyConversionError("partial output already exists; use --resume")
        partial_directory.mkdir(parents=True, exist_ok=False)
        partial_records.write_bytes(b"")
        partial_index.write_bytes(b"")
        state = _state_document(
            config_sha256=config_sha256,
            next_ordinal=0,
            records_bytes=0,
            index_bytes=0,
            input_counts=Counter(),
            converted_counts=Counter(),
            dropped_counts=Counter(),
            source_part_audit_counts=Counter(),
            selected_input_compressed_bytes=0,
            selected_input_raw_bytes=0,
        )
        _write_state(state_path, state)
    if not partial_records.is_file() or not partial_index.is_file():
        raise LegacyConversionError("partial runtime files are missing")
    next_ordinal = int(state["nextOrdinal"])
    records_bytes = int(state["recordsBytes"])
    index_bytes = int(state["indexBytes"])
    if index_bytes != next_ordinal * INDEX_ENTRY_BYTES:
        raise LegacyConversionError("partial index length state is inconsistent")
    input_counts = _counter_from_state(state["inputCounts"], "state inputCounts")
    converted_counts = _counter_from_state(
        state["convertedCounts"], "state convertedCounts"
    )
    dropped_counts = _counter_from_state(
        state["droppedCounts"], "state droppedCounts"
    )
    source_part_audit_counts = _counter_from_state(
        state["sourcePartAuditCounts"], "state sourcePartAuditCounts"
    )
    selected_compressed = int(state["selectedInputCompressedBytes"])
    selected_raw = int(state["selectedInputRawBytes"])
    processed_this_run = 0
    source_index_path = source.directory / "tile-index.bin"
    source_records_path = source.directory / "records.fadictpack"
    with (
        source_index_path.open("rb") as source_index,
        source_records_path.open("rb") as source_records,
        partial_records.open("r+b") as output_records,
        partial_index.open("r+b") as output_index,
    ):
        output_records.truncate(records_bytes)
        output_index.truncate(index_bytes)
        output_records.seek(records_bytes)
        output_index.seek(index_bytes)
        while next_ordinal < planned_tiles and (
            max_tiles is None or processed_this_run < max_tiles
        ):
            tile = _plan_tile_at_ordinal(plan, next_ordinal)
            document, raw_sha256, compressed_length, raw_length = _read_source_tile(
                source,
                tile,
                source_index,
                source_records,
            )
            (
                payload,
                tile_input,
                tile_converted,
                tile_dropped,
                tile_source_part_audit,
            ) = _convert_tile_document(
                source=source,
                tile=tile,
                raw_tile_sha256=raw_sha256,
                document=document,
            )
            compressed = raw_deflate(payload)
            output_index.write(
                encode_index_entry(
                    offset=records_bytes,
                    compressed_length=len(compressed),
                    raw_length=len(payload),
                    raw_hash32=raw_hash32(payload),
                )
            )
            output_records.write(compressed)
            records_bytes += len(compressed)
            index_bytes += INDEX_ENTRY_BYTES
            next_ordinal += 1
            processed_this_run += 1
            input_counts.update(tile_input)
            converted_counts.update(tile_converted)
            dropped_counts.update(tile_dropped)
            source_part_audit_counts.update(tile_source_part_audit)
            selected_compressed += compressed_length
            selected_raw += raw_length
            state = _state_document(
                config_sha256=config_sha256,
                next_ordinal=next_ordinal,
                records_bytes=records_bytes,
                index_bytes=index_bytes,
                input_counts=input_counts,
                converted_counts=converted_counts,
                dropped_counts=dropped_counts,
                source_part_audit_counts=source_part_audit_counts,
                selected_input_compressed_bytes=selected_compressed,
                selected_input_raw_bytes=selected_raw,
            )
            if next_ordinal % checkpoint_every == 0:
                _flush_and_checkpoint(
                    records_handle=output_records,
                    index_handle=output_index,
                    state_path=state_path,
                    state=state,
                )
        _flush_and_checkpoint(
            records_handle=output_records,
            index_handle=output_index,
            state_path=state_path,
            state=state,
        )
    if next_ordinal < planned_tiles:
        report = _result_progress_report(
            source=source,
            package_id=checked_package_id,
            full=full,
            plan=plan,
            state=state,
            converter_sha256=converter_sha256,
        )
        return ConversionResult(
            complete=False,
            processed_tiles=next_ordinal,
            planned_tiles=planned_tiles,
            output_directory=output_directory,
            partial_directory=partial_directory,
            report=report,
        )
    whole_earth = (
        full
        and next_ordinal == source.tile_count
        and source.complete_whole_earth_dictionary
        and _is_full_world_plan(plan)
    )
    renderer_semantic_stream_sha256, subtype_counts = _audit_output_semantics(
        partial_directory=partial_directory,
        plan=plan,
    )
    manifest_bytes = _runtime_manifest(
        source=source,
        package_id=checked_package_id,
        plan=plan,
        complete_declared_scope=True,
        complete_whole_earth_dictionary=whole_earth,
        renderer_semantic_stream_sha256=renderer_semantic_stream_sha256,
        converter_sha256=converter_sha256,
    )
    report = _report_document(
        source=source,
        package_id=checked_package_id,
        full=full,
        plan=plan,
        state=state,
        manifest_bytes=manifest_bytes,
        renderer_semantic_stream_sha256=renderer_semantic_stream_sha256,
        subtype_counts=subtype_counts,
        converter_sha256=converter_sha256,
    )
    (partial_directory / "manifest.json").write_bytes(manifest_bytes)
    (partial_directory / "conversion-report.json").write_bytes(
        _canonical_json_bytes(report)
    )
    os.replace(partial_directory, output_directory)
    (output_directory / "conversion-state.json").unlink(missing_ok=True)
    return ConversionResult(
        complete=True,
        processed_tiles=next_ordinal,
        planned_tiles=planned_tiles,
        output_directory=output_directory,
        partial_directory=None,
        report=report,
    )


def parse_tile_window(value: str) -> TileWindow:
    if type(value) is not str:
        raise argparse.ArgumentTypeError("window must be z/xMin/xMax/yMin/yMax")
    components = value.split("/")
    if len(components) != 5:
        raise argparse.ArgumentTypeError("window must be z/xMin/xMax/yMin/yMax")
    try:
        numbers = tuple(int(component, 10) for component in components)
        return TileWindow(*numbers)
    except (LegacyConversionError, ValueError) as error:
        raise argparse.ArgumentTypeError(str(error)) from error


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Stream a legacy real-source dictionary into Experiment 8 BINARY_V3."
    )
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-id", required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--full", action="store_true")
    mode.add_argument("--window", action="append", type=parse_tile_window)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--max-tiles", type=int)
    parser.add_argument("--checkpoint-every", type=int, default=256)
    parsed = parser.parse_args(arguments)
    result = convert_legacy_package(
        source_directory=parsed.source,
        output_directory=parsed.output,
        package_id=parsed.package_id,
        windows=tuple(parsed.window or ()),
        full=parsed.full,
        resume=parsed.resume,
        max_tiles=parsed.max_tiles,
        checkpoint_every=parsed.checkpoint_every,
    )
    print(_canonical_json_bytes(result.report).decode("utf-8"), end="")
    return 0


__all__ = [
    "ConversionResult",
    "LegacyConversionError",
    "TileWindow",
    "convert_legacy_package",
    "parse_tile_window",
]


if __name__ == "__main__":
    raise SystemExit(_main())
