from __future__ import annotations

import hashlib
import math
import os
import stat
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Mapping, Sequence

from . import model, reference_presentation_policy, semantic_model, sourced_text
from .model import TileKey
from .osm_global_waterway_package import GlobalWaterwayPackageError
from .reference_presentation_policy import (
    LABEL_ACTIVE_BAND_LIMIT,
    LABEL_DISPLAY_MAX_ZOOM_CENTI,
    LABEL_FADE_OUT_ZOOM_CENTI,
    LINE_LABEL_REPEAT_SPACING_PX,
    PRESENTATION_POLICY_SHA256,
    REFERENCE_LABEL_COLLISION_GROUP,
    LabelFacts,
    ProminenceDecision,
    ProminenceTier as PolicyProminenceTier,
    SemanticSubtype,
    SourceEvidenceContext,
    prominence_decision_for_label,
    prominence_decision_sha256,
    visibility_rule_for_label,
)
from .renderer_tile_package import RendererTileRecord
from .semantic_model import (
    FeatureKind,
    GeometryKind,
    HotIdRegistry,
    LandEvidence,
    LayerGroup,
    PlacementSourceKind,
    ProminenceTier as RendererProminenceTier,
    ProtectedStatus,
    RendererGeometry,
    RendererRecord,
    TextEvidenceKind,
    TilePosting,
    geometry_intersects_tile,
    make_canonical_variant,
    make_normalized_placement,
    renderer_geometry_fingerprint,
)
from .sourced_text import create_sourced_map_text


_WORLD_DENOMINATOR = 1_000_000_000
_MAX_WEB_MERCATOR_LATITUDE = 85.05112878
_FEATURE_DOMAIN = b"FAE8OSMGLOBALWATERWAYFEATURE3\0"
_ALLOWED_WATERWAYS = frozenset(
    ("river", "stream", "canal", "tidal_channel", "wadi")
)
_SUBTYPE_BY_WATERWAY = {
    "river": SemanticSubtype.RIVER,
    "stream": SemanticSubtype.STREAM_CREEK,
    "canal": SemanticSubtype.CANAL_CHANNEL,
    "tidal_channel": SemanticSubtype.CANAL_CHANNEL,
    "wadi": SemanticSubtype.UNSPECIFIED_WATERCOURSE,
}
_MAX_CLASSIFIER_MODULE_BYTES = 16 * 1024 * 1024
_MAX_CLASSIFIER_TOTAL_BYTES = 64 * 1024 * 1024
_REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


def _u64_identity(label: str, registry: HotIdRegistry | None = None) -> int:
    canonical = label.encode("utf-8", "strict")
    if registry is not None:
        return registry.register(b"FAE8OSMID1\0", canonical).hot_id
    digest = hashlib.sha256(b"FAE8OSMID1\0" + canonical).digest()
    return int.from_bytes(digest[:8], "big")


@dataclass(frozen=True, slots=True)
class ExactWaterwayPoint:
    node_id: int
    longitude_e7: int
    latitude_e7: int

    def __post_init__(self) -> None:
        if type(self.node_id) is not int or not 0 < self.node_id < 1 << 63:
            raise GlobalWaterwayPackageError("waterway point node ID must be positive signed-63")
        if type(self.longitude_e7) is not int or not -1_800_000_000 <= self.longitude_e7 <= 1_800_000_000:
            raise GlobalWaterwayPackageError("waterway point longitude E7 is invalid")
        if type(self.latitude_e7) is not int or not -900_000_000 <= self.latitude_e7 <= 900_000_000:
            raise GlobalWaterwayPackageError("waterway point latitude E7 is invalid")


@dataclass(frozen=True, slots=True)
class ExactWaterwayFeature:
    source_kind: str
    source_id: int
    source_version: int
    source_timestamp: str
    waterway_type: str
    name_source_key: str
    primary_name: str
    english_name: str | None
    complete_named_relation: bool
    parts: tuple[tuple[ExactWaterwayPoint, ...], ...]
    required_node_ids: frozenset[int]
    source_feature_sha256: bytes

    def __post_init__(self) -> None:
        if self.source_kind not in {"way", "relation"}:
            raise GlobalWaterwayPackageError("waterway feature source kind is unsupported")
        if type(self.source_id) is not int or not 0 < self.source_id < 1 << 63:
            raise GlobalWaterwayPackageError("waterway source ID must be positive signed-63")
        if type(self.source_version) is not int or self.source_version <= 0:
            raise GlobalWaterwayPackageError("waterway source version must be positive")
        if self.waterway_type not in _ALLOWED_WATERWAYS:
            raise GlobalWaterwayPackageError("waterway source type is unsupported")
        if type(self.primary_name) is not str or not self.primary_name:
            raise GlobalWaterwayPackageError("waterway source primary name is empty")
        if self.english_name is not None and type(self.english_name) is not str:
            raise GlobalWaterwayPackageError("waterway English source name is malformed")
        if type(self.complete_named_relation) is not bool or (
            self.source_kind == "way" and self.complete_named_relation
        ):
            raise GlobalWaterwayPackageError("complete relation evidence contradicts source kind")
        if not self.parts or any(len(part) < 2 for part in self.parts):
            raise GlobalWaterwayPackageError("waterway feature lacks complete usable path parts")
        if type(self.required_node_ids) is not frozenset:
            raise GlobalWaterwayPackageError("required waterway join nodes must be a frozen set")
        available_node_ids = {
            point.node_id for part in self.parts for point in part
        }
        if any(
            type(node_id) is not int or node_id not in available_node_ids
            for node_id in self.required_node_ids
        ):
            raise GlobalWaterwayPackageError(
                "required waterway join node is absent from exact geometry"
            )
        if type(self.source_feature_sha256) is not bytes or len(self.source_feature_sha256) != 32:
            raise GlobalWaterwayPackageError("waterway source feature identity must be SHA-256 bytes")


@dataclass(frozen=True, slots=True)
class AdaptiveWaterwayRendererFeature:
    source_kind: str
    source_id: int
    waterway_type: str
    semantic_subtype: SemanticSubtype
    primary_name: str
    complete_length_m: int
    prominence_tier: PolicyProminenceTier
    prominence_decision: ProminenceDecision
    variant_point_counts_by_zoom: Mapping[int, int]
    tiles: Mapping[TileKey, tuple[RendererTileRecord, ...]]


def _update_classifier_digest_bounded(
    digest: object,
    path: Path,
    *,
    maximum_bytes: int,
) -> int:
    if type(maximum_bytes) is not int or maximum_bytes <= 0:
        raise GlobalWaterwayPackageError(
            "classifier module byte ceiling must be positive"
        )
    try:
        before = os.lstat(path)
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "classifier module is unavailable"
        ) from error
    if (
        not stat.S_ISREG(before.st_mode)
        or getattr(before, "st_file_attributes", 0) & _REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(
            "classifier module is not one plain regular file"
        )
    if before.st_size > maximum_bytes:
        raise GlobalWaterwayPackageError(
            f"classifier module exceeds {maximum_bytes} classifier bytes"
        )
    total = 0
    with path.open("rb") as handle:
        opened = os.fstat(handle.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise GlobalWaterwayPackageError(
                "classifier module changed while opening"
            )
        while True:
            chunk = handle.read(min(1024 * 1024, maximum_bytes - total + 1))
            if not chunk:
                break
            total += len(chunk)
            if total > maximum_bytes:
                raise GlobalWaterwayPackageError(
                    f"classifier module exceeds {maximum_bytes} classifier bytes"
                )
            digest.update(chunk)
        after_handle = os.fstat(handle.fileno())
    after = os.lstat(path)
    signature = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    if signature != (
        after_handle.st_dev,
        after_handle.st_ino,
        after_handle.st_size,
        after_handle.st_mtime_ns,
        after_handle.st_ctime_ns,
    ) or signature != (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
    ):
        raise GlobalWaterwayPackageError(
            "classifier module drifted while streaming"
        )
    return total


def classifier_identity_sha256() -> str:
    digest = hashlib.sha256(b"FAE8OSMGLOBALWATERWAYCLASSIFIER1\0")
    total = 0
    for module in (
        __import__(__name__, fromlist=["*"]),
        model,
        reference_presentation_policy,
        semantic_model,
        sourced_text,
    ):
        total += _update_classifier_digest_bounded(
            digest,
            Path(module.__file__),
            maximum_bytes=_MAX_CLASSIFIER_MODULE_BYTES,
        )
        if total > _MAX_CLASSIFIER_TOTAL_BYTES:
            raise GlobalWaterwayPackageError(
                "classifier source exceeds 67108864 total classifier bytes"
            )
    return digest.hexdigest()


def _world_point(point: ExactWaterwayPoint) -> tuple[int, int]:
    longitude = point.longitude_e7 / 10_000_000.0
    latitude = max(
        -_MAX_WEB_MERCATOR_LATITUDE,
        min(_MAX_WEB_MERCATOR_LATITUDE, point.latitude_e7 / 10_000_000.0),
    )
    x = (longitude + 180.0) / 360.0
    latitude_radians = math.radians(latitude)
    y = (1.0 - math.asinh(math.tan(latitude_radians)) / math.pi) / 2.0
    return (
        math.floor(x * _WORLD_DENOMINATOR + 0.5),
        math.floor(y * _WORLD_DENOMINATOR + 0.5),
    )


def _unwrapped_world_points(
    part: Sequence[ExactWaterwayPoint],
) -> tuple[tuple[int, int], ...]:
    result: list[tuple[int, int]] = []
    previous_x: int | None = None
    for point in part:
        x, y = _world_point(point)
        if previous_x is not None:
            while x - previous_x > _WORLD_DENOMINATOR // 2:
                x -= _WORLD_DENOMINATOR
            while previous_x - x > _WORLD_DENOMINATOR // 2:
                x += _WORLD_DENOMINATOR
        result.append((x, y))
        previous_x = x
    return tuple(result)


def _simplified_indices(
    coordinates: Sequence[tuple[int, int]],
    tolerance: int,
) -> tuple[int, ...]:
    if len(coordinates) < 2:
        raise GlobalWaterwayPackageError("adaptive path part has fewer than two points")
    keep = {0, len(coordinates) - 1}
    pending = [(0, len(coordinates) - 1)]
    tolerance_squared = tolerance * tolerance
    while pending:
        start, end = pending.pop()
        if end <= start + 1:
            continue
        first_x, first_y = coordinates[start]
        last_x, last_y = coordinates[end]
        delta_x = last_x - first_x
        delta_y = last_y - first_y
        segment_squared = delta_x * delta_x + delta_y * delta_y
        selected = -1
        selected_measure = -1
        if segment_squared:
            for index in range(start + 1, end):
                x, y = coordinates[index]
                cross = abs(delta_x * (first_y - y) - (first_x - x) * delta_y)
                measure = cross * cross
                if measure > selected_measure:
                    selected_measure = measure
                    selected = index
            exceeds = selected_measure > tolerance_squared * segment_squared
        else:
            for index in range(start + 1, end):
                x, y = coordinates[index]
                measure = (x - first_x) ** 2 + (y - first_y) ** 2
                if measure > selected_measure:
                    selected_measure = measure
                    selected = index
            exceeds = selected_measure > tolerance_squared
        if exceeds:
            keep.add(selected)
            pending.append((start, selected))
            pending.append((selected, end))
    return tuple(sorted(keep))


def adaptive_complete_parts(
    parts: Sequence[Sequence[ExactWaterwayPoint]],
    *,
    zoom: int,
    required_node_ids: frozenset[int] = frozenset(),
) -> tuple[tuple[ExactWaterwayPoint, ...], ...]:
    """Keep every complete part while bounding simplification to half a zoom pixel."""

    if type(zoom) is not int or not 0 <= zoom <= 29:
        raise GlobalWaterwayPackageError("adaptive waterway zoom must be inside [0,29]")
    if not parts:
        raise GlobalWaterwayPackageError("adaptive waterway geometry has no parts")
    if type(required_node_ids) is not frozenset:
        raise GlobalWaterwayPackageError("adaptive required node IDs must be a frozen set")
    pixel_denominator = (1 << zoom) * 512
    tolerance = max(1, (_WORLD_DENOMINATOR + pixel_denominator - 1) // pixel_denominator)
    output: list[tuple[ExactWaterwayPoint, ...]] = []
    for part in parts:
        exact = tuple(part)
        projected = _unwrapped_world_points(exact)
        anchors = sorted(
            {0, len(exact) - 1}
            | {
                index
                for index, point in enumerate(exact)
                if point.node_id in required_node_ids
            }
        )
        kept: set[int] = set()
        for start, end in zip(anchors, anchors[1:]):
            kept.update(
                start + index
                for index in _simplified_indices(
                    projected[start : end + 1], tolerance
                )
            )
        indices = tuple(sorted(kept))
        simplified = tuple(exact[index] for index in indices)
        if simplified[0] != exact[0] or simplified[-1] != exact[-1]:
            raise GlobalWaterwayPackageError("adaptive path lost a source endpoint")
        output.append(simplified)
    return tuple(output)


def _renderer_geometry(
    parts: Sequence[Sequence[ExactWaterwayPoint]],
) -> RendererGeometry:
    offsets: list[int] = []
    coordinates: list[int] = []
    point_count = 0
    for part in parts:
        offsets.append(point_count)
        projected = _unwrapped_world_points(tuple(part))
        for x, y in projected:
            coordinates.extend((x, y))
            point_count += 1
    divisor = _WORLD_DENOMINATOR
    for coordinate in coordinates:
        divisor = math.gcd(divisor, abs(coordinate))
    denominator = _WORLD_DENOMINATOR // divisor
    reduced = tuple(value // divisor for value in coordinates)
    return RendererGeometry(
        kind=GeometryKind.PATH,
        parts=tuple(offsets),
        world_denominator=denominator,
        world_coordinate_numerators=reduced,
        bounds_numerators=(
            min(reduced[0::2]),
            min(reduced[1::2]),
            max(reduced[0::2]),
            max(reduced[1::2]),
        ),
    )


def _great_circle_length_m(parts: Sequence[Sequence[ExactWaterwayPoint]]) -> int:
    radius_m = 6_371_008.8
    total = 0.0
    for part in parts:
        for first, second in zip(part, part[1:]):
            first_latitude = math.radians(first.latitude_e7 / 10_000_000.0)
            second_latitude = math.radians(second.latitude_e7 / 10_000_000.0)
            delta_latitude = second_latitude - first_latitude
            delta_longitude = math.radians(
                (second.longitude_e7 - first.longitude_e7) / 10_000_000.0
            )
            haversine = (
                math.sin(delta_latitude / 2.0) ** 2
                + math.cos(first_latitude)
                * math.cos(second_latitude)
                * math.sin(delta_longitude / 2.0) ** 2
            )
            total += 2.0 * radius_m * math.asin(min(1.0, math.sqrt(haversine)))
    if total <= 0.0:
        raise GlobalWaterwayPackageError("waterway path has zero geographic length")
    return max(1, math.floor(total + 0.5))


def _candidate_tiles(
    geometry: RendererGeometry,
    zoom: int,
) -> tuple[tuple[TileKey, int], ...]:
    denominator = geometry.world_denominator
    scale = 1 << zoom
    points = tuple(
        (
            geometry.world_coordinate_numerators[index],
            geometry.world_coordinate_numerators[index + 1],
        )
        for index in range(0, len(geometry.world_coordinate_numerators), 2)
    )
    raw_candidates: set[tuple[int, int]] = set()
    ends = geometry.parts[1:] + (len(points),)
    for start, end in zip(geometry.parts, ends):
        for first, second in zip(points[start:end], points[start + 1 : end]):
            raw_x_min = math.floor(min(first[0], second[0]) * scale / denominator)
            raw_x_max = math.floor(max(first[0], second[0]) * scale / denominator)
            y_min = max(0, math.floor(min(first[1], second[1]) * scale / denominator))
            y_max = min(scale - 1, math.floor(max(first[1], second[1]) * scale / denominator))
            for raw_x in range(raw_x_min, raw_x_max + 1):
                for y in range(y_min, y_max + 1):
                    raw_candidates.add((raw_x, y))
    candidates: list[tuple[TileKey, int]] = []
    for raw_x, y in sorted(raw_candidates, key=lambda item: (item[1], item[0])):
        world_wrap, x = divmod(raw_x, scale)
        tile = TileKey(zoom, x, y)
        if geometry_intersects_tile(geometry, tile, world_wrap=world_wrap):
            candidates.append((tile, world_wrap))
    if not candidates:
        raise GlobalWaterwayPackageError("waterway adaptive geometry produced no tiles")
    return tuple(candidates)


def build_adaptive_waterway_feature(
    *,
    feature: ExactWaterwayFeature,
    source_generation_sha256: str,
    classifier_sha256: str,
    zooms: Sequence[int],
    identity_registry: HotIdRegistry | None = None,
) -> AdaptiveWaterwayRendererFeature:
    if not isinstance(feature, ExactWaterwayFeature):
        raise GlobalWaterwayPackageError("adaptive renderer requires an exact waterway feature")
    for value, label in (
        (source_generation_sha256, "source generation SHA-256"),
        (classifier_sha256, "classifier SHA-256"),
    ):
        if type(value) is not str or len(value) != 64 or any(
            character not in "0123456789abcdef" for character in value
        ):
            raise GlobalWaterwayPackageError(f"{label} must be lowercase hexadecimal")
    normalized_zooms = tuple(zooms)
    if not normalized_zooms or len(set(normalized_zooms)) != len(normalized_zooms):
        raise GlobalWaterwayPackageError("adaptive waterway zooms must be nonempty and unique")
    if any(type(zoom) is not int or not 0 <= zoom <= 29 for zoom in normalized_zooms):
        raise GlobalWaterwayPackageError("adaptive waterway zoom is outside [0,29]")
    if feature.source_feature_sha256 == b"\0" * 32:
        raise GlobalWaterwayPackageError("waterway source feature identity is empty")
    if unicodedata.normalize("NFC", feature.primary_name) != feature.primary_name:
        raise GlobalWaterwayPackageError(
            "non-NFC primary source name cannot enter V3 without normalization"
        )

    registry = identity_registry or HotIdRegistry()
    subtype = _SUBTYPE_BY_WATERWAY[feature.waterway_type]
    primary_field_id = _u64_identity(
        "openstreetmap.tag." + feature.name_source_key, registry
    )
    english_field_id = (
        _u64_identity("openstreetmap.tag.name:en", registry)
        if feature.english_name is not None
        else None
    )
    context = SourceEvidenceContext(
        source_generation_sha256=source_generation_sha256,
        classifier_sha256=classifier_sha256,
        source_field_id=primary_field_id,
    )
    complete_length_m = _great_circle_length_m(feature.parts)
    facts = LabelFacts(
        subtype=subtype,
        evidence_context=context,
        complete_named_relation=feature.complete_named_relation,
        complete_relation_length_m=(
            complete_length_m if feature.complete_named_relation else None
        ),
    )
    decision = prominence_decision_for_label(facts)
    visibility = visibility_rule_for_label(facts)
    exact_text = create_sourced_map_text(
        primary=feature.primary_name,
        primary_source_field_id=primary_field_id,
        declared_english=feature.english_name,
        english_source_field_id=english_field_id,
    )
    if exact_text.primary_text != feature.primary_name:
        raise GlobalWaterwayPackageError(
            "sourced-text policy would alter the exact primary source name"
        )
    if (
        feature.english_name is not None
        and exact_text.primary_script_signals.has_strong_non_latin
        and exact_text.english_text != feature.english_name
    ):
        raise GlobalWaterwayPackageError(
            "non-Latin source primary cannot retain its exact same-source name:en"
        )

    feature_id = int.from_bytes(feature.source_feature_sha256[:8], "big")
    combined: dict[TileKey, list[RendererTileRecord]] = {}
    point_counts: dict[int, int] = {}
    for zoom in normalized_zooms:
        if (zoom + 1) * 100 <= visibility.min_zoom_centi:
            continue
        adaptive_parts = adaptive_complete_parts(
            feature.parts,
            zoom=zoom,
            required_node_ids=feature.required_node_ids,
        )
        geometry = _renderer_geometry(adaptive_parts)
        geometry_identity = renderer_geometry_fingerprint(geometry)
        candidates = _candidate_tiles(geometry, zoom)
        owner_tile, owner_wrap = min(
            candidates, key=lambda item: (item[0].packed, item[1])
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
        placement = make_normalized_placement(
            text=feature.primary_name,
            source_feature_sha256=feature.source_feature_sha256,
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
                if feature.complete_named_relation
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
            complete_geometry_measure_bucket=decision.complete_geometry_measure_bucket,
            prominence_rule_id=decision.prominence_rule_id,
            prominence_decision_sha256=bytes.fromhex(
                prominence_decision_sha256(decision)
            ),
            avoid_edges=True,
            keep_upright=True,
            active_band_limit=LABEL_ACTIVE_BAND_LIMIT,
            style_policy_sha256=bytes.fromhex(PRESENTATION_POLICY_SHA256),
            provider_feature_id=feature.source_id,
            identity_registry=registry,
        )
        variant = make_canonical_variant(
            dedupe_id=feature_id,
            geometry_id=geometry_identity.hot_id,
            source_layer_id=_u64_identity(
                f"openstreetmap.{feature.source_kind}.waterway", registry
            ),
            source_scale_band_id=_u64_identity(
                f"openstreetmap.waterway.adaptive-half-pixel.z{zoom}", registry
            ),
            layer_group=LayerGroup.WATER,
            feature_kind=FeatureKind.LABEL,
            semantic_subtype=subtype.value,
            source_style_layer_ids=(
                _u64_identity(
                    "openstreetmap.tag.waterway." + feature.waterway_type,
                    registry,
                ),
            ),
            render_style_token_ids=(
                _u64_identity(
                    "flightalert.reference.water." + feature.waterway_type,
                    registry,
                ),
            ),
            text=feature.primary_name,
            geometry=geometry,
            min_zoom_centi=visibility.min_zoom_centi,
            max_zoom_centi=LABEL_DISPLAY_MAX_ZOOM_CENTI,
            fade_in_centi=visibility.full_alpha_zoom_centi,
            fade_out_centi=LABEL_FADE_OUT_ZOOM_CENTI,
            draw_order=40,
            priority=decision.semantic_priority,
            placement=placement,
            land_evidence=LandEvidence.NOT_APPLICABLE,
            protected_status=ProtectedStatus.NOT_APPLICABLE,
            flags=0,
            identity_registry=registry,
        )
        point_counts[zoom] = sum(len(part) for part in adaptive_parts)
        for tile, world_wrap in candidates:
            posting = TilePosting(
                requested_tile=tile,
                feature_id=feature_id,
                canonical_variant_id=variant.canonical_variant_id,
                owner_tile=owner_tile,
                world_wrap=world_wrap,
            )
            combined.setdefault(tile, []).append(
                RendererTileRecord(RendererRecord(posting, variant), exact_text)
            )
    if not combined:
        raise GlobalWaterwayPackageError(
            "waterway feature is not visible in the requested zoom range"
        )
    return AdaptiveWaterwayRendererFeature(
        source_kind=feature.source_kind,
        source_id=feature.source_id,
        waterway_type=feature.waterway_type,
        semantic_subtype=subtype,
        primary_name=feature.primary_name,
        complete_length_m=complete_length_m,
        prominence_tier=decision.tier,
        prominence_decision=decision,
        variant_point_counts_by_zoom=MappingProxyType(dict(sorted(point_counts.items()))),
        tiles=MappingProxyType(
            {tile: tuple(combined[tile]) for tile in sorted(combined)}
        ),
    )


__all__ = [
    "AdaptiveWaterwayRendererFeature",
    "ExactWaterwayFeature",
    "ExactWaterwayPoint",
    "adaptive_complete_parts",
    "build_adaptive_waterway_feature",
    "classifier_identity_sha256",
]
