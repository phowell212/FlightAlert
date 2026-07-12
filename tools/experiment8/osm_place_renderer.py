from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Mapping, Sequence

from . import osm_hydro_source
from .model import TileKey
from .osm_hydro_source import OsmDataset, OsmNode, parse_osm_xml_bytes
from .reference_presentation_policy import (
    LABEL_ACTIVE_BAND_LIMIT,
    LABEL_DISPLAY_MAX_ZOOM_CENTI,
    LABEL_FADE_OUT_ZOOM_CENTI,
    LINE_LABEL_REPEAT_SPACING_PX,
    PRESENTATION_POLICY_SHA256,
    REFERENCE_LABEL_COLLISION_GROUP,
    CapitalLevel,
    LabelFacts,
    LabelVisibilityRule,
    ProminenceDecision,
    ProminenceEvidenceKind,
    ProminenceTier as PolicyProminenceTier,
    SemanticSubtype,
    SourceEvidenceContext,
    prominence_decision_for_label,
    prominence_decision_sha256,
    visibility_rule_for_label,
)
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    RendererTileRecord,
    encode_tile_payload,
    write_package,
)
from .semantic_model import (
    FeatureKind,
    GeometryKind,
    LandEvidence,
    LayerGroup,
    PlacementSourceKind,
    ProminenceTier as RendererProminenceTier,
    ProtectedStatus,
    RendererGeometry,
    RendererRecord,
    TextEvidenceKind,
    TilePosting,
    make_canonical_variant,
    make_normalized_placement,
    point_label_dedupe_fingerprint,
    renderer_geometry_fingerprint,
)
from .sourced_text import create_sourced_map_text


_WORLD_DENOMINATOR = 1_000_000_000
_MAX_WEB_MERCATOR_LATITUDE = 85.05112878
_OSM_FEATURE_DOMAIN = b"FAE8OSMPLACE1\0"
_CANONICAL_UNSIGNED_DECIMAL = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_I64_MAX = (1 << 63) - 1


def _u64_identity(label: str) -> int:
    digest = hashlib.sha256(b"FAE8OSMID1\0" + label.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big")


OSM_NAME_SOURCE_FIELD_ID = _u64_identity("openstreetmap.tag.name")
OSM_ENGLISH_NAME_SOURCE_FIELD_ID = _u64_identity("openstreetmap.tag.name:en")
OSM_POPULATION_SOURCE_FIELD_ID = _u64_identity("openstreetmap.tag.population")
OSM_CAPITAL_SOURCE_FIELD_ID = _u64_identity("openstreetmap.tag.capital")


_FIRST_ORDER_REGION_VALUES = frozenset({"province", "region", "state"})
_SECOND_LOCAL_REGION_VALUES = frozenset({"county", "district", "municipality"})
_CITY_TOWN_VALUES = frozenset({"city", "town"})
_LOCAL_PLACE_VALUES = frozenset(
    {
        "hamlet",
        "isolated_dwelling",
        "locality",
        "neighbourhood",
        "quarter",
        "suburb",
        "village",
    }
)


@dataclass(frozen=True, slots=True)
class OsmPlaceRendererFeature:
    node_id: int
    name: str
    semantic_subtype: SemanticSubtype
    prominence_tier: PolicyProminenceTier
    prominence_decision: ProminenceDecision
    visibility_rule: LabelVisibilityRule
    tiles: Mapping[TileKey, tuple[RendererTileRecord, ...]]


@dataclass(frozen=True, slots=True)
class OsmPlacePackageReceipt:
    package_id: str
    source_sha256: str
    source_bytes: int
    classifier_sha256: str
    node_ids: tuple[int, ...]
    zooms: tuple[int, ...]
    present_tile_count: int
    declared_index_entries: int
    manifest_sha256: str
    records_sha256: str
    index_sha256: str

    def canonical_json_bytes(self) -> bytes:
        return (
            json.dumps(
                {
                    "classifierSha256": self.classifier_sha256,
                    "declaredIndexEntries": self.declared_index_entries,
                    "indexSha256": self.index_sha256,
                    "manifestSha256": self.manifest_sha256,
                    "nodeIds": list(self.node_ids),
                    "packageId": self.package_id,
                    "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
                    "presentTileCount": self.present_tile_count,
                    "recordsSha256": self.records_sha256,
                    "schema": "flightalert.experiment8.osm-place-package-build.v1",
                    "sourceBytes": self.source_bytes,
                    "sourceSha256": self.source_sha256,
                    "zooms": list(self.zooms),
                },
                allow_nan=False,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8")


def classifier_identity_sha256() -> str:
    digest = hashlib.sha256()
    digest.update(b"FAE8OSMPLACECLASSIFIER1\0")
    digest.update(Path(__file__).read_bytes())
    digest.update(Path(osm_hydro_source.__file__).read_bytes())
    digest.update(bytes.fromhex(osm_hydro_source.POLICY_SHA256))
    digest.update(bytes.fromhex(PRESENTATION_POLICY_SHA256))
    return digest.hexdigest()


def _require_sha256(value: str, label: str) -> None:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise ValueError(f"{label} must be lowercase hexadecimal")


def _node_canonical_bytes(
    node: OsmNode,
    source_generation_sha256: str,
) -> bytes:
    return json.dumps(
        {
            "latitudeE7": node.latitude_e7,
            "longitudeE7": node.longitude_e7,
            "nodeId": node.object_id,
            "sourceGenerationSha256": source_generation_sha256,
            "tags": [list(item) for item in node.tags],
            "timestamp": node.timestamp,
            "version": node.version,
        },
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _world_point(longitude_e7: int, latitude_e7: int) -> tuple[int, int]:
    longitude = longitude_e7 / 10_000_000.0
    latitude = max(
        -_MAX_WEB_MERCATOR_LATITUDE,
        min(_MAX_WEB_MERCATOR_LATITUDE, latitude_e7 / 10_000_000.0),
    )
    x = (longitude + 180.0) / 360.0
    latitude_radians = math.radians(latitude)
    y = (1.0 - math.asinh(math.tan(latitude_radians)) / math.pi) / 2.0
    return (
        math.floor(x * _WORLD_DENOMINATOR + 0.5),
        math.floor(y * _WORLD_DENOMINATOR + 0.5),
    )


def _point_geometry(node: OsmNode) -> RendererGeometry:
    x, y = _world_point(node.longitude_e7, node.latitude_e7)
    divisor = math.gcd(_WORLD_DENOMINATOR, math.gcd(abs(x), abs(y)))
    reduced_x = x // divisor
    reduced_y = y // divisor
    return RendererGeometry(
        kind=GeometryKind.POINT,
        parts=(0,),
        world_denominator=_WORLD_DENOMINATOR // divisor,
        world_coordinate_numerators=(reduced_x, reduced_y),
        bounds_numerators=(reduced_x, reduced_y, reduced_x, reduced_y),
    )


def _candidate_tiles(
    geometry: RendererGeometry,
    zooms: Sequence[int],
) -> tuple[tuple[TileKey, int], ...]:
    x, y = geometry.world_coordinate_numerators
    denominator = geometry.world_denominator
    candidates: list[tuple[TileKey, int]] = []
    for zoom in zooms:
        if type(zoom) is not int or not 0 <= zoom <= 29:
            raise ValueError("place renderer zooms must be exact integers inside [0, 29]")
        scale = 1 << zoom
        raw_x = math.floor(x * scale / denominator)
        world_wrap, tile_x = divmod(raw_x, scale)
        tile_y = max(0, min(scale - 1, math.floor(y * scale / denominator)))
        candidates.append((TileKey(zoom, tile_x, tile_y), world_wrap))
    return tuple(candidates)


def _canonical_population(raw: str | None) -> int | None:
    if raw is None or _CANONICAL_UNSIGNED_DECIMAL.fullmatch(raw) is None:
        return None
    value = int(raw)
    return value if value <= _I64_MAX else None


def _capital_level(raw: str | None) -> CapitalLevel | None:
    if raw == "yes":
        return CapitalLevel.NATIONAL
    if raw is not None and _CANONICAL_UNSIGNED_DECIMAL.fullmatch(raw) is not None:
        level = int(raw)
        if 3 <= level <= 5:
            return CapitalLevel.REGIONAL
    return None


def _semantic_subtype(
    place_value: str,
    *,
    population: int | None,
    capital_level: CapitalLevel | None,
) -> SemanticSubtype:
    if place_value == "country":
        return SemanticSubtype.COUNTRY_TERRITORY
    if place_value in _FIRST_ORDER_REGION_VALUES:
        return SemanticSubtype.FIRST_ORDER_REGION
    if place_value in _SECOND_LOCAL_REGION_VALUES:
        return SemanticSubtype.SECOND_LOCAL_REGION
    if place_value in _CITY_TOWN_VALUES:
        if capital_level is not None or (population is not None and population >= 1_000_000):
            return SemanticSubtype.CAPITAL_MAJOR_CITY
        return SemanticSubtype.CITY_TOWN
    if place_value in _LOCAL_PLACE_VALUES:
        return SemanticSubtype.LOCAL_PLACE
    raise ValueError(f"unsupported direct OSM place type: {place_value!r}")


def _layer_group(subtype: SemanticSubtype) -> LayerGroup:
    if subtype in {
        SemanticSubtype.COUNTRY_TERRITORY,
        SemanticSubtype.FIRST_ORDER_REGION,
        SemanticSubtype.SECOND_LOCAL_REGION,
    }:
        return LayerGroup.REGIONS
    return LayerGroup.PLACES


def _render_style_token(subtype: SemanticSubtype) -> int:
    suffixes = {
        SemanticSubtype.COUNTRY_TERRITORY: "region.country",
        SemanticSubtype.FIRST_ORDER_REGION: "region.first_order",
        SemanticSubtype.SECOND_LOCAL_REGION: "region.local",
        SemanticSubtype.CAPITAL_MAJOR_CITY: "place.major",
        SemanticSubtype.CITY_TOWN: "place",
        SemanticSubtype.LOCAL_PLACE: "place.local",
    }
    return _u64_identity("flightalert.reference." + suffixes[subtype])


def _facts_with_exact_evidence_context(
    *,
    subtype: SemanticSubtype,
    source_generation_sha256: str,
    classifier_sha256: str,
    primary_source_field_id: int,
    population: int | None,
    capital_level: CapitalLevel | None,
) -> LabelFacts:
    is_place = subtype in {
        SemanticSubtype.CAPITAL_MAJOR_CITY,
        SemanticSubtype.CITY_TOWN,
        SemanticSubtype.LOCAL_PLACE,
    }
    provisional = LabelFacts(
        subtype=subtype,
        evidence_context=SourceEvidenceContext(
            source_generation_sha256,
            classifier_sha256,
            primary_source_field_id,
        ),
        population=population if is_place else None,
        population_verified=is_place and population is not None,
        capital_level=capital_level if is_place and capital_level is not None else CapitalLevel.NONE,
        capital_level_verified=is_place and capital_level is not None,
    )
    provisional_decision = prominence_decision_for_label(provisional)
    if provisional_decision.evidence_kind is ProminenceEvidenceKind.POPULATION:
        evidence_field_id = OSM_POPULATION_SOURCE_FIELD_ID
    elif provisional_decision.evidence_kind is ProminenceEvidenceKind.CAPITAL_LEVEL:
        evidence_field_id = OSM_CAPITAL_SOURCE_FIELD_ID
    else:
        evidence_field_id = primary_source_field_id
    return LabelFacts(
        subtype=subtype,
        evidence_context=SourceEvidenceContext(
            source_generation_sha256,
            classifier_sha256,
            evidence_field_id,
        ),
        population=provisional.population,
        population_verified=provisional.population_verified,
        capital_level=provisional.capital_level,
        capital_level_verified=provisional.capital_level_verified,
    )


def build_osm_place_node(
    *,
    dataset: OsmDataset,
    node_id: int,
    source_generation_sha256: str,
    classifier_sha256: str,
    primary_source_field_id: int,
    zooms: Sequence[int],
    english_source_field_id: int | None = None,
) -> OsmPlaceRendererFeature:
    if not isinstance(dataset, OsmDataset):
        raise ValueError("place renderer requires an OSM dataset")
    if type(node_id) is not int or node_id <= 0:
        raise ValueError("place node ID must be a positive exact integer")
    node = dataset.nodes.get(node_id)
    if node is None:
        raise ValueError("requested object is not a direct OSM place node")
    _require_sha256(source_generation_sha256, "source generation SHA-256")
    _require_sha256(classifier_sha256, "classifier SHA-256")
    if type(primary_source_field_id) is not int or not 0 < primary_source_field_id < 1 << 64:
        raise ValueError("primary source-field ID must be a nonzero u64")
    if english_source_field_id is not None and (
        type(english_source_field_id) is not int
        or not 0 < english_source_field_id < 1 << 64
    ):
        raise ValueError("English source-field ID must be a nonzero u64")
    normalized_zooms = tuple(zooms)
    if not normalized_zooms or len(set(normalized_zooms)) != len(normalized_zooms):
        raise ValueError("place renderer zooms must be nonempty and unique")

    tags = dict(node.tags)
    if len(tags) != len(node.tags):
        raise ValueError("direct OSM place node has duplicate tag keys")
    name = tags.get("name")
    if name is None or not name.strip() or name.strip() != name:
        raise ValueError("direct OSM place node lacks a canonical primary name field")
    place_value = tags.get("place")
    if place_value is None:
        raise ValueError("requested object is not a direct OSM place node")
    population = _canonical_population(tags.get("population"))
    capital_level = _capital_level(tags.get("capital"))
    subtype = _semantic_subtype(
        place_value,
        population=population,
        capital_level=capital_level,
    )
    declared_english = tags.get("name:en")
    if declared_english is not None and english_source_field_id is None:
        raise ValueError("declared English name lacks its exact source-field ID")

    geometry = _point_geometry(node)
    geometry_identity = renderer_geometry_fingerprint(geometry)
    facts = _facts_with_exact_evidence_context(
        subtype=subtype,
        source_generation_sha256=source_generation_sha256,
        classifier_sha256=classifier_sha256,
        primary_source_field_id=primary_source_field_id,
        population=population,
        capital_level=capital_level,
    )
    decision = prominence_decision_for_label(facts)
    visibility = visibility_rule_for_label(facts)
    eligible_zooms = tuple(
        zoom
        for zoom in normalized_zooms
        if zoom * 100 + 99 >= visibility.min_zoom_centi
    )
    if not eligible_zooms:
        raise ValueError("direct OSM place node has no requested visible zoom band")
    source_feature_sha256 = hashlib.sha256(
        _OSM_FEATURE_DOMAIN + _node_canonical_bytes(node, source_generation_sha256)
    ).digest()
    feature_id = int.from_bytes(source_feature_sha256[:8], "big")
    sourced_text = create_sourced_map_text(
        primary=name,
        primary_source_field_id=primary_source_field_id,
        declared_english=declared_english,
        english_source_field_id=(
            english_source_field_id if declared_english is not None else None
        ),
    )
    placement = make_normalized_placement(
        text=name,
        source_feature_sha256=source_feature_sha256,
        placement_geometry_sha256=geometry_identity.full_sha256,
        text_evidence_kind=TextEvidenceKind.SOURCE_FIELD,
        text_source_field_id=primary_source_field_id,
        placement_source_feature_id=feature_id,
        placement_geometry_id=geometry_identity.hot_id,
        source_tile=TileKey(0, 0, 0),
        source_zoom=0,
        source_declared_extent=geometry.world_denominator,
        source_edge_domain=(0, 0, geometry.world_denominator, geometry.world_denominator),
        placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_POINT,
        display_min_zoom_centi=visibility.min_zoom_centi,
        display_max_zoom_centi=LABEL_DISPLAY_MAX_ZOOM_CENTI,
        spacing_px=LINE_LABEL_REPEAT_SPACING_PX,
        max_angle_degrees=0,
        collision_group=REFERENCE_LABEL_COLLISION_GROUP,
        semantic_priority=decision.semantic_priority,
        prominence_tier=RendererProminenceTier[decision.tier.name],
        provider_rank=decision.provider_rank,
        complete_geometry_measure_bucket=decision.complete_geometry_measure_bucket,
        prominence_rule_id=decision.prominence_rule_id,
        prominence_decision_sha256=bytes.fromhex(prominence_decision_sha256(decision)),
        avoid_edges=True,
        keep_upright=True,
        active_band_limit=LABEL_ACTIVE_BAND_LIMIT,
        style_policy_sha256=bytes.fromhex(PRESENTATION_POLICY_SHA256),
        provider_feature_id=node.object_id,
    )
    group = _layer_group(subtype)
    dedupe_id = point_label_dedupe_fingerprint(
        group,
        FeatureKind.LABEL,
        name,
        subtype.value,
        geometry,
    ).hot_id
    variant = make_canonical_variant(
        dedupe_id=dedupe_id,
        geometry_id=geometry_identity.hot_id,
        source_layer_id=_u64_identity("openstreetmap.node.place"),
        source_scale_band_id=_u64_identity(
            "openstreetmap.node.place.zoom." + ",".join(map(str, eligible_zooms))
        ),
        layer_group=group,
        feature_kind=FeatureKind.LABEL,
        semantic_subtype=subtype.value,
        source_style_layer_ids=(_u64_identity("openstreetmap.tag.place." + place_value),),
        render_style_token_ids=(_render_style_token(subtype),),
        text=name,
        geometry=geometry,
        min_zoom_centi=visibility.min_zoom_centi,
        max_zoom_centi=LABEL_DISPLAY_MAX_ZOOM_CENTI,
        fade_in_centi=visibility.full_alpha_zoom_centi,
        fade_out_centi=LABEL_FADE_OUT_ZOOM_CENTI,
        draw_order=50,
        priority=decision.semantic_priority,
        placement=placement,
        land_evidence=LandEvidence.NOT_APPLICABLE,
        protected_status=ProtectedStatus.NOT_APPLICABLE,
        flags=0,
    )

    records: dict[TileKey, tuple[RendererTileRecord, ...]] = {}
    for tile, world_wrap in _candidate_tiles(geometry, eligible_zooms):
        posting = TilePosting(
            requested_tile=tile,
            feature_id=feature_id,
            canonical_variant_id=variant.canonical_variant_id,
            owner_tile=tile,
            world_wrap=world_wrap,
        )
        records[tile] = (
            RendererTileRecord(RendererRecord(posting, variant), sourced_text),
        )
    return OsmPlaceRendererFeature(
        node_id=node.object_id,
        name=name,
        semantic_subtype=subtype,
        prominence_tier=decision.tier,
        prominence_decision=decision,
        visibility_rule=visibility,
        tiles=MappingProxyType({tile: records[tile] for tile in sorted(records)}),
    )


def build_osm_place_package(
    *,
    source_path: Path,
    output_directory: Path,
    package_id: str,
    node_ids: Sequence[int],
    expected_source_sha256: str,
    zooms: Sequence[int],
) -> OsmPlacePackageReceipt:
    if not isinstance(source_path, Path) or not source_path.is_file():
        raise ValueError("OSM source path is not a readable file")
    if not isinstance(output_directory, Path):
        raise ValueError("package output directory must be a pathlib.Path")
    raw = source_path.read_bytes()
    actual_source_sha256 = hashlib.sha256(raw).hexdigest()
    if actual_source_sha256 != expected_source_sha256:
        raise ValueError(
            "OSM source SHA-256 does not match: "
            f"expected {expected_source_sha256}, got {actual_source_sha256}"
        )
    normalized_nodes = tuple(node_ids)
    if (
        not normalized_nodes
        or len(set(normalized_nodes)) != len(normalized_nodes)
        or any(type(value) is not int or value <= 0 for value in normalized_nodes)
    ):
        raise ValueError("node IDs must be unique positive exact integers")
    normalized_zooms = tuple(zooms)
    if not normalized_zooms or len(set(normalized_zooms)) != len(normalized_zooms):
        raise ValueError("package zooms must be nonempty and unique")

    dataset = parse_osm_xml_bytes(raw, source_label=str(source_path))
    classifier = classifier_identity_sha256()
    combined: dict[TileKey, list[RendererTileRecord]] = {}
    for node_id in normalized_nodes:
        node = dataset.nodes.get(node_id)
        has_english = node is not None and "name:en" in dict(node.tags)
        feature = build_osm_place_node(
            dataset=dataset,
            node_id=node_id,
            source_generation_sha256=actual_source_sha256,
            classifier_sha256=classifier,
            primary_source_field_id=OSM_NAME_SOURCE_FIELD_ID,
            english_source_field_id=(OSM_ENGLISH_NAME_SOURCE_FIELD_ID if has_english else None),
            zooms=normalized_zooms,
        )
        for tile, records in feature.tiles.items():
            combined.setdefault(tile, []).extend(records)
    payloads = {
        tile: encode_tile_payload(tile, combined[tile])
        for tile in sorted(combined)
    }
    artifacts = write_package(
        output_directory,
        package_id,
        payloads,
        complete_declared_scope=False,
        complete_whole_earth_dictionary=False,
    )
    receipt = OsmPlacePackageReceipt(
        package_id=package_id,
        source_sha256=actual_source_sha256,
        source_bytes=len(raw),
        classifier_sha256=classifier,
        node_ids=normalized_nodes,
        zooms=normalized_zooms,
        present_tile_count=len(payloads),
        declared_index_entries=len(artifacts.index_bytes) // INDEX_ENTRY_BYTES,
        manifest_sha256=hashlib.sha256(artifacts.manifest_bytes).hexdigest(),
        records_sha256=hashlib.sha256(artifacts.records_bytes).hexdigest(),
        index_sha256=hashlib.sha256(artifacts.index_bytes).hexdigest(),
    )
    (output_directory / "build-receipt.json").write_bytes(receipt.canonical_json_bytes())
    return receipt


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build a source-honest Experiment 8 OSM place-label V3 package."
    )
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-id", default="world-experiment8-binary-v3")
    parser.add_argument("--node-id", required=True, type=int, action="append")
    parser.add_argument("--zoom", required=True, type=int, action="append")
    parsed = parser.parse_args(arguments)
    receipt = build_osm_place_package(
        source_path=parsed.source,
        output_directory=parsed.output,
        package_id=parsed.package_id,
        node_ids=tuple(parsed.node_id),
        expected_source_sha256=parsed.source_sha256,
        zooms=tuple(parsed.zoom),
    )
    print(receipt.canonical_json_bytes().decode("utf-8"), end="")
    return 0


__all__ = [
    "OSM_CAPITAL_SOURCE_FIELD_ID",
    "OSM_ENGLISH_NAME_SOURCE_FIELD_ID",
    "OSM_NAME_SOURCE_FIELD_ID",
    "OSM_POPULATION_SOURCE_FIELD_ID",
    "OsmPlacePackageReceipt",
    "OsmPlaceRendererFeature",
    "build_osm_place_node",
    "build_osm_place_package",
    "classifier_identity_sha256",
]


if __name__ == "__main__":
    raise SystemExit(_main())
