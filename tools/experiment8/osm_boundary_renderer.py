from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from types import MappingProxyType
from typing import BinaryIO, Mapping, Sequence

from .model import TileKey
from .osm_global_waterway_package import (
    StrictOplNode,
    StrictOplRelation,
    StrictOplWay,
    iter_strict_waterway_opl,
)
from .osm_global_waterway_renderer import (
    ExactWaterwayPoint,
    _candidate_tiles,
    _renderer_geometry,
    _u64_identity,
    _unwrapped_world_points,
    adaptive_complete_parts,
)
from .reference_presentation_policy import (
    SemanticSubtype,
    outline_visibility_rule,
    style_family_for_subtype,
)
from .renderer_tile_package import RendererTileRecord
from .semantic_model import (
    FeatureKind,
    LandEvidence,
    LayerGroup,
    ProtectedStatus,
    RendererRecord,
    TilePosting,
    empty_normalized_placement,
    make_canonical_variant,
    renderer_geometry_fingerprint,
)


class BoundaryRendererError(ValueError):
    """An OSM boundary source cannot produce a source-complete outline."""


_ADMIN_SUBTYPE = {
    "2": SemanticSubtype.INTERNATIONAL_BOUNDARY,
    "3": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
    "4": SemanticSubtype.STATE_PROVINCE_BOUNDARY,
    "5": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
    "6": SemanticSubtype.COUNTY_LOCAL_BOUNDARY,
    "7": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
    "8": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
    "9": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
    "10": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
    "11": SemanticSubtype.OTHER_ADMIN_BOUNDARY,
}
_BOUNDARY_RELATION_TYPES = frozenset(("boundary", "multipolygon"))
_GEOMETRY_ROLES = frozenset(("outer", "inner"))
_FEATURE_DOMAIN = b"FAE8OSMBOUNDARYWAY1\0"


def _boundary_zoom_admitted(subtype: SemanticSubtype, zoom: int) -> bool:
    return not (
        subtype is SemanticSubtype.COASTLINE and zoom < 7
        or subtype is SemanticSubtype.OTHER_ADMIN_BOUNDARY and zoom < 8
    )


@dataclass(frozen=True, slots=True, order=True)
class BoundaryRelationMembership:
    relation_id: int
    role: str
    ordinal: int


@dataclass(frozen=True, slots=True)
class SelectedBoundaryWay:
    way: StrictOplWay
    subtype: SemanticSubtype
    direct: bool
    memberships: tuple[BoundaryRelationMembership, ...]


@dataclass(frozen=True, slots=True)
class BoundaryOplSelection:
    nodes: Mapping[int, StrictOplNode]
    ways: tuple[SelectedBoundaryWay, ...]


@dataclass(frozen=True, slots=True)
class BoundaryRendererFeature:
    way_id: int
    subtype: SemanticSubtype
    tiles: Mapping[TileKey, tuple[RendererTileRecord, ...]]


def _admin_subtype(tags: Mapping[str, str]) -> SemanticSubtype | None:
    if tags.get("boundary") != "administrative":
        return None
    return admin_boundary_subtype(tags.get("admin_level", ""))


def admin_boundary_subtype(admin_level: str) -> SemanticSubtype | None:
    return _ADMIN_SUBTYPE.get(admin_level) if type(admin_level) is str else None


def _direct_subtypes(way: StrictOplWay) -> tuple[SemanticSubtype, ...]:
    tags = dict(way.tags)
    result: list[SemanticSubtype] = []
    if tags.get("natural") == "coastline":
        result.append(SemanticSubtype.COASTLINE)
    admin = _admin_subtype(tags)
    if admin is not None:
        result.append(admin)
    return tuple(result)


def select_boundary_opl(stream: BinaryIO) -> BoundaryOplSelection:
    """Select exact OSM coastline/admin way geometry from one recursive OPL closure."""

    nodes: dict[int, StrictOplNode] = {}
    ways: dict[int, StrictOplWay] = {}
    relations: list[StrictOplRelation] = []
    for record in iter_strict_waterway_opl(stream):
        value = record.value
        if isinstance(value, StrictOplNode):
            nodes[value.object_id] = value
        elif isinstance(value, StrictOplWay):
            ways[value.object_id] = value
        else:
            relations.append(value)

    selected: dict[
        tuple[int, SemanticSubtype],
        tuple[StrictOplWay, bool, set[BoundaryRelationMembership]],
    ] = {}

    def add(
        way: StrictOplWay,
        subtype: SemanticSubtype,
        membership: BoundaryRelationMembership | None,
    ) -> None:
        key = (way.object_id, subtype)
        existing = selected.get(key)
        if existing is None:
            memberships: set[BoundaryRelationMembership] = set()
            if membership is not None:
                memberships.add(membership)
            selected[key] = (way, membership is None, memberships)
            return
        source_way, direct, memberships = existing
        if source_way != way:
            raise BoundaryRendererError(f"way {way.object_id} source payload changed")
        if membership is None:
            direct = True
        else:
            memberships.add(membership)
        selected[key] = (source_way, direct, memberships)

    for way in ways.values():
        for subtype in _direct_subtypes(way):
            add(way, subtype, None)

    for relation in relations:
        tags = dict(relation.tags)
        subtype = _admin_subtype(tags)
        if subtype is None or tags.get("type") not in _BOUNDARY_RELATION_TYPES:
            continue
        for member in relation.members:
            if member.role not in _GEOMETRY_ROLES:
                continue
            if member.object_type != "w":
                raise BoundaryRendererError(
                    f"boundary relation {relation.object_id} has unsupported "
                    f"{member.role} member type {member.object_type!r}"
                )
            way = ways.get(member.ref)
            if way is None:
                raise BoundaryRendererError(
                    f"boundary relation {relation.object_id} references missing way {member.ref}"
                )
            add(
                way,
                subtype,
                BoundaryRelationMembership(
                    relation_id=relation.object_id,
                    role=member.role,
                    ordinal=member.ordinal,
                ),
            )

    ordered = tuple(
        SelectedBoundaryWay(
            way=way,
            subtype=subtype,
            direct=direct,
            memberships=tuple(sorted(memberships)),
        )
        for (way_id, subtype), (way, direct, memberships) in sorted(
            selected.items(), key=lambda item: (item[0][0], item[0][1].value)
        )
    )
    return BoundaryOplSelection(MappingProxyType(nodes), ordered)


def _source_feature_sha256(
    selected: SelectedBoundaryWay,
    nodes: Mapping[int, StrictOplNode],
    source_generation_sha256: str,
) -> bytes:
    way = selected.way
    document = {
        "direct": selected.direct,
        "memberships": [
            [item.relation_id, item.role, item.ordinal]
            for item in selected.memberships
        ],
        "nodes": [
            [node_id, nodes[node_id].longitude_e7, nodes[node_id].latitude_e7]
            for node_id in way.node_refs
        ],
        "sourceGenerationSha256": source_generation_sha256,
        "subtype": selected.subtype.value,
        "tags": [list(item) for item in way.tags],
        "timestamp": way.timestamp,
        "version": way.version,
        "wayId": way.object_id,
    }
    canonical = json.dumps(
        document,
        allow_nan=False,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8", "strict")
    return hashlib.sha256(_FEATURE_DOMAIN + canonical).digest()


def _source_style_ids(selected: SelectedBoundaryWay) -> tuple[int, ...]:
    labels: set[str] = set()
    if selected.direct:
        tags = dict(selected.way.tags)
        if selected.subtype is SemanticSubtype.COASTLINE:
            labels.add("openstreetmap.tag.natural.coastline")
        else:
            labels.add(
                "openstreetmap.tag.boundary.administrative.admin_level."
                + tags["admin_level"]
            )
    labels.update(
        "openstreetmap.relation.boundary.administrative." + membership.role
        for membership in selected.memberships
    )
    return tuple(sorted(_u64_identity(label) for label in labels))


def _closed_ring_anchors(
    points: Sequence[ExactWaterwayPoint],
    way_id: int,
) -> frozenset[int]:
    """Retain a deterministic nondegenerate triangle when simplifying a closed ring."""

    if len(points) < 4 or points[0].node_id != points[-1].node_id:
        raise BoundaryRendererError(f"closed boundary way {way_id} is malformed")
    projected = _unwrapped_world_points(tuple(points[:-1]))
    origin_x, origin_y = projected[0]
    second = max(
        range(1, len(projected)),
        key=lambda index: (
            (projected[index][0] - origin_x) ** 2
            + (projected[index][1] - origin_y) ** 2,
            -index,
        ),
    )
    second_x, second_y = projected[second]
    third = max(
        (index for index in range(1, len(projected)) if index != second),
        key=lambda index: (
            abs(
                (second_x - origin_x) * (projected[index][1] - origin_y)
                - (second_y - origin_y) * (projected[index][0] - origin_x)
            ),
            -index,
        ),
        default=-1,
    )
    if third < 0 or (
        (second_x - origin_x) * (projected[third][1] - origin_y)
        == (second_y - origin_y) * (projected[third][0] - origin_x)
    ):
        raise BoundaryRendererError(f"closed boundary way {way_id} is degenerate")
    return frozenset(
        (points[0].node_id, points[second].node_id, points[third].node_id)
    )


def build_boundary_way_feature(
    selected: SelectedBoundaryWay,
    *,
    nodes: Mapping[int, StrictOplNode],
    source_generation_sha256: str,
    zooms: Sequence[int],
) -> BoundaryRendererFeature:
    """Build deterministic per-zoom V3 line-outline postings for one selected OSM way."""

    if not isinstance(selected, SelectedBoundaryWay):
        raise BoundaryRendererError("boundary renderer requires one selected way")
    if (
        type(source_generation_sha256) is not str
        or len(source_generation_sha256) != 64
        or any(character not in "0123456789abcdef" for character in source_generation_sha256)
    ):
        raise BoundaryRendererError("source generation SHA-256 must be lowercase hexadecimal")
    normalized_zooms = tuple(sorted(zooms))
    if not normalized_zooms or len(set(normalized_zooms)) != len(normalized_zooms):
        raise BoundaryRendererError("boundary renderer zooms must be nonempty and unique")
    if any(type(zoom) is not int or not 0 <= zoom <= 29 for zoom in normalized_zooms):
        raise BoundaryRendererError("boundary renderer zoom is outside [0,29]")
    if len(selected.way.node_refs) < 2:
        raise BoundaryRendererError(f"boundary way {selected.way.object_id} has fewer than two nodes")

    points: list[ExactWaterwayPoint] = []
    for node_id in selected.way.node_refs:
        node = nodes.get(node_id)
        if node is None:
            raise BoundaryRendererError(
                f"boundary way {selected.way.object_id} references missing node {node_id}"
            )
        points.append(ExactWaterwayPoint(node_id, node.longitude_e7, node.latitude_e7))
    if points[0].node_id == points[-1].node_id:
        required_node_ids = _closed_ring_anchors(points, selected.way.object_id)
    else:
        required_node_ids = frozenset((points[0].node_id, points[-1].node_id))

    source_sha256 = _source_feature_sha256(selected, nodes, source_generation_sha256)
    feature_id = int.from_bytes(source_sha256[:8], "big")
    if feature_id == 0:
        raise BoundaryRendererError("boundary source feature produced the forbidden zero hot ID")
    dedupe_id = _u64_identity(
        f"openstreetmap.way.{selected.way.object_id}.semantic-subtype.{selected.subtype.value}"
    )
    source_style_ids = _source_style_ids(selected)
    visibility = outline_visibility_rule(selected.subtype)
    layer_group = (
        LayerGroup.WATER
        if selected.subtype is SemanticSubtype.COASTLINE
        else LayerGroup.REGIONS
    )
    records: dict[TileKey, list[RendererTileRecord]] = {}
    for zoom in normalized_zooms:
        if (
            (zoom + 1) * 100 <= visibility.min_zoom_centi
            or not _boundary_zoom_admitted(selected.subtype, zoom)
        ):
            continue
        try:
            simplified = adaptive_complete_parts(
                (tuple(points),),
                zoom=zoom,
                required_node_ids=required_node_ids,
            )
            geometry = _renderer_geometry(simplified)
            candidates = _candidate_tiles(geometry, zoom)
        except ValueError as error:
            raise BoundaryRendererError(
                f"boundary way {selected.way.object_id} geometry is invalid: {error}"
            ) from error
        geometry_identity = renderer_geometry_fingerprint(geometry)
        variant = make_canonical_variant(
            dedupe_id=dedupe_id,
            geometry_id=geometry_identity.hot_id,
            source_layer_id=_u64_identity("openstreetmap.way.boundary-outline"),
            source_scale_band_id=_u64_identity(
                f"openstreetmap.way.boundary-outline.zoom.{zoom}"
            ),
            layer_group=layer_group,
            feature_kind=FeatureKind.LINE,
            semantic_subtype=selected.subtype.value,
            source_style_layer_ids=source_style_ids,
            render_style_token_ids=(
                _u64_identity(
                    "flightalert.reference."
                    + style_family_for_subtype(selected.subtype).value
                ),
            ),
            text=None,
            geometry=geometry,
            min_zoom_centi=visibility.min_zoom_centi,
            max_zoom_centi=visibility.max_zoom_centi,
            fade_in_centi=visibility.full_alpha_zoom_centi,
            fade_out_centi=visibility.fade_out_zoom_centi,
            draw_order=visibility.draw_order,
            priority=visibility.priority,
            placement=empty_normalized_placement(),
            land_evidence=LandEvidence.NOT_APPLICABLE,
            protected_status=ProtectedStatus.NOT_APPLICABLE,
            flags=2 if selected.subtype is SemanticSubtype.COASTLINE else 0,
        )
        owner_tile, _ = min(candidates, key=lambda item: (item[0].packed, item[1]))
        for tile, world_wrap in candidates:
            posting = TilePosting(
                requested_tile=tile,
                feature_id=feature_id,
                canonical_variant_id=variant.canonical_variant_id,
                owner_tile=owner_tile,
                world_wrap=world_wrap,
            )
            records.setdefault(tile, []).append(
                RendererTileRecord(RendererRecord(posting, variant), None)
            )
    if not records:
        raise BoundaryRendererError(
            "boundary way is not visible in the requested zoom range"
        )
    return BoundaryRendererFeature(
        way_id=selected.way.object_id,
        subtype=selected.subtype,
        tiles=MappingProxyType(
            {
                tile: tuple(records[tile])
                for tile in sorted(records, key=lambda item: (item.z, item.y, item.x))
            }
        ),
    )


__all__ = [
    "BoundaryOplSelection",
    "BoundaryRendererFeature",
    "BoundaryRelationMembership",
    "BoundaryRendererError",
    "SelectedBoundaryWay",
    "admin_boundary_subtype",
    "build_boundary_way_feature",
    "select_boundary_opl",
]
