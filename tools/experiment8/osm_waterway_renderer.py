from __future__ import annotations

import hashlib
import json
import math
import argparse
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Mapping, Sequence

from . import osm_hydro_source
from .model import TileKey
from .osm_hydro_source import (
    OsmDataset,
    assemble_relation_parts,
    is_named_waterway_relation_root,
    parse_osm_xml,
)
from .reference_presentation_policy import (
    LABEL_ACTIVE_BAND_LIMIT,
    LABEL_DISPLAY_MAX_ZOOM_CENTI,
    LABEL_FADE_OUT_ZOOM_CENTI,
    LINE_LABEL_REPEAT_SPACING_PX,
    PRESENTATION_POLICY_SHA256,
    REFERENCE_LABEL_COLLISION_GROUP,
    LabelFacts,
    LabelVisibilityRule,
    ProminenceDecision,
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
    geometry_intersects_tile,
    make_canonical_variant,
    make_normalized_placement,
    renderer_geometry_fingerprint,
)
from .sourced_text import create_sourced_map_text


_WORLD_DENOMINATOR = 1_000_000_000
_MAX_WEB_MERCATOR_LATITUDE = 85.05112878
_OSM_FEATURE_DOMAIN = b"FAE8OSMWATERWAY1\0"


@dataclass(frozen=True, slots=True)
class NamedWaterwayRendererFeature:
    relation_id: int
    name: str
    complete_relation_length_m: int
    prominence_tier: PolicyProminenceTier
    prominence_decision: ProminenceDecision
    visibility_rule: LabelVisibilityRule
    tiles: Mapping[TileKey, tuple[RendererTileRecord, ...]]


@dataclass(frozen=True, slots=True)
class NamedWaterwayPackageReceipt:
    package_id: str
    source_sha256: str
    source_bytes: int
    classifier_sha256: str
    relation_ids: tuple[int, ...]
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
                    "packageId": self.package_id,
                    "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
                    "presentTileCount": self.present_tile_count,
                    "recordsSha256": self.records_sha256,
                    "relationIds": list(self.relation_ids),
                    "schema": "flightalert.experiment8.waterway-package-build.v1",
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


def _u64_identity(label: str) -> int:
    digest = hashlib.sha256(b"FAE8OSMID1\0" + label.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big")


OSM_NAME_SOURCE_FIELD_ID = _u64_identity("openstreetmap.tag.name")
OSM_ENGLISH_NAME_SOURCE_FIELD_ID = _u64_identity("openstreetmap.tag.name:en")


def classifier_identity_sha256() -> str:
    digest = hashlib.sha256()
    digest.update(b"FAE8OSMWATERWAYCLASSIFIER1\0")
    digest.update(Path(__file__).read_bytes())
    digest.update(Path(osm_hydro_source.__file__).read_bytes())
    digest.update(bytes.fromhex(osm_hydro_source.POLICY_SHA256))
    digest.update(bytes.fromhex(PRESENTATION_POLICY_SHA256))
    return digest.hexdigest()


def _relation_canonical_bytes(
    dataset: OsmDataset,
    relation_id: int,
    parts: Sequence[Sequence[int]],
    source_generation_sha256: str,
) -> bytes:
    relation = dataset.relations[relation_id]
    document = {
        "members": [
            [member.object_type, member.ref, member.role, member.ordinal]
            for member in relation.members
        ],
        "nodes": [
            [
                node_id,
                dataset.nodes[node_id].longitude_e7,
                dataset.nodes[node_id].latitude_e7,
            ]
            for part in parts
            for node_id in part
        ],
        "relationId": relation.object_id,
        "sourceGenerationSha256": source_generation_sha256,
        "tags": [list(item) for item in relation.tags],
        "timestamp": relation.timestamp,
        "version": relation.version,
    }
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
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


def _renderer_geometry(
    dataset: OsmDataset,
    parts: Sequence[Sequence[int]],
) -> RendererGeometry:
    offsets: list[int] = []
    coordinates: list[int] = []
    point_count = 0
    previous_x: int | None = None
    for part in parts:
        if len(part) < 2:
            raise ValueError("named waterway relation contains an unusable path part")
        offsets.append(point_count)
        for node_id in part:
            try:
                node = dataset.nodes[node_id]
            except KeyError as error:
                raise ValueError(
                    f"named waterway relation references missing node {node_id}"
                ) from error
            x, y = _world_point(node.longitude_e7, node.latitude_e7)
            if previous_x is not None:
                while x - previous_x > _WORLD_DENOMINATOR // 2:
                    x -= _WORLD_DENOMINATOR
                while previous_x - x > _WORLD_DENOMINATOR // 2:
                    x += _WORLD_DENOMINATOR
            coordinates.extend((x, y))
            previous_x = x
            point_count += 1

    common_divisor = _WORLD_DENOMINATOR
    for coordinate in coordinates:
        common_divisor = math.gcd(common_divisor, abs(coordinate))
    denominator = _WORLD_DENOMINATOR // common_divisor
    reduced = tuple(value // common_divisor for value in coordinates)
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


def _great_circle_length_m(
    dataset: OsmDataset,
    parts: Sequence[Sequence[int]],
) -> int:
    radius_m = 6_371_008.8
    total = 0.0
    for part in parts:
        for first_id, second_id in zip(part, part[1:]):
            first = dataset.nodes[first_id]
            second = dataset.nodes[second_id]
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
    return math.floor(total + 0.5)


def _candidate_tiles(
    geometry: RendererGeometry,
    zooms: Sequence[int],
) -> tuple[tuple[TileKey, int], ...]:
    denominator = geometry.world_denominator
    min_x, min_y, max_x, max_y = geometry.bounds_numerators
    candidates: list[tuple[TileKey, int]] = []
    for zoom in zooms:
        if type(zoom) is not int or not 0 <= zoom <= 29:
            raise ValueError("waterway renderer zooms must be exact integers inside [0, 29]")
        scale = 1 << zoom
        raw_x_min = math.floor(min_x * scale / denominator)
        raw_x_max = math.floor(max_x * scale / denominator)
        y_min = max(0, math.floor(min_y * scale / denominator))
        y_max = min(scale - 1, math.floor(max_y * scale / denominator))
        for raw_x in range(raw_x_min, raw_x_max + 1):
            world_wrap, x = divmod(raw_x, scale)
            for y in range(y_min, y_max + 1):
                tile = TileKey(zoom, x, y)
                if geometry_intersects_tile(geometry, tile, world_wrap=world_wrap):
                    candidates.append((tile, world_wrap))
    return tuple(candidates)


def build_named_waterway_relation(
    *,
    dataset: OsmDataset,
    relation_id: int,
    source_generation_sha256: str,
    classifier_sha256: str,
    primary_source_field_id: int,
    zooms: Sequence[int],
    english_source_field_id: int | None = None,
) -> NamedWaterwayRendererFeature:
    if not isinstance(dataset, OsmDataset):
        raise ValueError("named waterway relation requires an OSM dataset")
    relation = dataset.relations.get(relation_id)
    if relation is None or not is_named_waterway_relation_root(relation):
        raise ValueError("requested object is not a complete named waterway relation")
    if len(source_generation_sha256) != 64 or any(
        value not in "0123456789abcdef" for value in source_generation_sha256
    ):
        raise ValueError("source generation SHA-256 must be lowercase hexadecimal")
    if len(classifier_sha256) != 64 or any(
        value not in "0123456789abcdef" for value in classifier_sha256
    ):
        raise ValueError("classifier SHA-256 must be lowercase hexadecimal")
    if type(primary_source_field_id) is not int or not 0 < primary_source_field_id < 1 << 64:
        raise ValueError("primary source-field ID must be a nonzero u64")
    if not zooms or len(set(zooms)) != len(tuple(zooms)):
        raise ValueError("waterway renderer zooms must be nonempty and unique")

    tags = dict(relation.tags)
    if len(tags) != len(relation.tags):
        raise ValueError("named waterway relation has duplicate tag keys")
    name = tags.get("name")
    if name is None or not name.strip() or name.strip() != name:
        raise ValueError("named waterway relation lacks a canonical primary name field")
    declared_english = tags.get("name:en")
    if declared_english is not None and english_source_field_id is None:
        raise ValueError("declared English name lacks its exact source-field ID")

    parts = assemble_relation_parts(
        relation,
        nodes=dataset.nodes,
        ways=dataset.ways,
        relations=dataset.relations,
    )
    geometry = _renderer_geometry(dataset, parts)
    geometry_identity = renderer_geometry_fingerprint(geometry)
    complete_length_m = _great_circle_length_m(dataset, parts)
    context = SourceEvidenceContext(
        source_generation_sha256=source_generation_sha256,
        classifier_sha256=classifier_sha256,
        source_field_id=primary_source_field_id,
    )
    facts = LabelFacts(
        subtype=SemanticSubtype.RIVER,
        evidence_context=context,
        complete_named_relation=True,
        complete_relation_length_m=complete_length_m,
    )
    decision = prominence_decision_for_label(facts)
    visibility = visibility_rule_for_label(facts)
    source_feature_sha256 = hashlib.sha256(
        _OSM_FEATURE_DOMAIN
        + _relation_canonical_bytes(
            dataset,
            relation_id,
            parts,
            source_generation_sha256,
        )
    ).digest()
    feature_id = int.from_bytes(source_feature_sha256[:8], "big")
    sourced_text = create_sourced_map_text(
        primary=name,
        primary_source_field_id=primary_source_field_id,
        declared_english=declared_english,
        english_source_field_id=english_source_field_id,
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
        source_edge_domain=(
            min(geometry.bounds_numerators[0], 0),
            min(geometry.bounds_numerators[1], 0),
            max(geometry.bounds_numerators[2], geometry.world_denominator),
            max(geometry.bounds_numerators[3], geometry.world_denominator),
        ),
        placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_PATH,
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
        prominence_decision_sha256=bytes.fromhex(prominence_decision_sha256(decision)),
        avoid_edges=False,
        keep_upright=True,
        active_band_limit=LABEL_ACTIVE_BAND_LIMIT,
        style_policy_sha256=bytes.fromhex(PRESENTATION_POLICY_SHA256),
        provider_feature_id=relation_id,
    )
    variant = make_canonical_variant(
        dedupe_id=feature_id,
        geometry_id=geometry_identity.hot_id,
        source_layer_id=_u64_identity("openstreetmap.relation.waterway"),
        source_scale_band_id=_u64_identity(
            "openstreetmap.relation.waterway.zoom." + ",".join(map(str, zooms))
        ),
        layer_group=LayerGroup.WATER,
        feature_kind=FeatureKind.LABEL,
        semantic_subtype=SemanticSubtype.RIVER.value,
        source_style_layer_ids=(_u64_identity("openstreetmap.tag.waterway.river"),),
        render_style_token_ids=(_u64_identity("flightalert.reference.water.river"),),
        text=name,
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
    )

    records: dict[TileKey, list[RendererTileRecord]] = {}
    for tile, world_wrap in _candidate_tiles(geometry, tuple(zooms)):
        posting = TilePosting(
            requested_tile=tile,
            feature_id=feature_id,
            canonical_variant_id=variant.canonical_variant_id,
            owner_tile=tile,
            world_wrap=world_wrap,
        )
        records.setdefault(tile, []).append(
            RendererTileRecord(RendererRecord(posting, variant), sourced_text)
        )
    if not records:
        raise ValueError("named waterway relation produced no renderer tiles")
    frozen = MappingProxyType(
        {tile: tuple(records[tile]) for tile in sorted(records)}
    )
    return NamedWaterwayRendererFeature(
        relation_id=relation_id,
        name=name,
        complete_relation_length_m=complete_length_m,
        prominence_tier=decision.tier,
        prominence_decision=decision,
        visibility_rule=visibility,
        tiles=frozen,
    )


def build_named_waterway_package(
    *,
    source_path: Path,
    output_directory: Path,
    package_id: str,
    relation_ids: Sequence[int],
    expected_source_sha256: str,
    zooms: Sequence[int],
) -> NamedWaterwayPackageReceipt:
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
    normalized_relations = tuple(relation_ids)
    if (
        not normalized_relations
        or len(set(normalized_relations)) != len(normalized_relations)
        or any(type(value) is not int or value <= 0 for value in normalized_relations)
    ):
        raise ValueError("relation IDs must be unique positive exact integers")
    normalized_zooms = tuple(zooms)
    if not normalized_zooms or len(set(normalized_zooms)) != len(normalized_zooms):
        raise ValueError("package zooms must be nonempty and unique")
    dataset = parse_osm_xml(source_path)
    classifier = classifier_identity_sha256()
    combined: dict[TileKey, list[RendererTileRecord]] = {}
    for relation_id in normalized_relations:
        relation = dataset.relations.get(relation_id)
        has_english = relation is not None and "name:en" in dict(relation.tags)
        feature = build_named_waterway_relation(
            dataset=dataset,
            relation_id=relation_id,
            source_generation_sha256=actual_source_sha256,
            classifier_sha256=classifier,
            primary_source_field_id=OSM_NAME_SOURCE_FIELD_ID,
            english_source_field_id=(
                OSM_ENGLISH_NAME_SOURCE_FIELD_ID if has_english else None
            ),
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
    receipt = NamedWaterwayPackageReceipt(
        package_id=package_id,
        source_sha256=actual_source_sha256,
        source_bytes=len(raw),
        classifier_sha256=classifier,
        relation_ids=normalized_relations,
        zooms=normalized_zooms,
        present_tile_count=len(payloads),
        declared_index_entries=len(artifacts.index_bytes) // INDEX_ENTRY_BYTES,
        manifest_sha256=hashlib.sha256(artifacts.manifest_bytes).hexdigest(),
        records_sha256=hashlib.sha256(artifacts.records_bytes).hexdigest(),
        index_sha256=hashlib.sha256(artifacts.index_bytes).hexdigest(),
    )
    (output_directory / "build-receipt.json").write_bytes(
        receipt.canonical_json_bytes()
    )
    return receipt


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build a source-honest Experiment 8 OSM waterway V3 package."
    )
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--source-sha256", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-id", default="world-experiment8-binary-v3")
    parser.add_argument("--relation-id", required=True, type=int, action="append")
    parser.add_argument("--zoom", required=True, type=int, action="append")
    parsed = parser.parse_args(arguments)
    receipt = build_named_waterway_package(
        source_path=parsed.source,
        output_directory=parsed.output,
        package_id=parsed.package_id,
        relation_ids=tuple(parsed.relation_id),
        expected_source_sha256=parsed.source_sha256,
        zooms=tuple(parsed.zoom),
    )
    print(receipt.canonical_json_bytes().decode("utf-8"), end="")
    return 0


__all__ = [
    "NamedWaterwayPackageReceipt",
    "NamedWaterwayRendererFeature",
    "build_named_waterway_package",
    "build_named_waterway_relation",
    "classifier_identity_sha256",
]


if __name__ == "__main__":
    raise SystemExit(_main())
