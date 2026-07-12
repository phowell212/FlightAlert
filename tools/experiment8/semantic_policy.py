from __future__ import annotations

import base64
import hashlib
import json
from copy import deepcopy
from dataclasses import dataclass
from enum import IntEnum
from types import MappingProxyType
from typing import Mapping

from .reference_presentation_policy import (
    FilterId,
    PRESENTATION_POLICY_SHA256,
    SemanticSubtype,
    filter_spec,
)
from .semantic_model import (
    FeatureKind,
    LandEvidence,
    LayerGroup,
    ProtectedStatus,
)


class SemanticPolicyError(ValueError):
    """A source value cannot satisfy the explicit Experiment 8 policy."""


class TransportSubtype(IntEnum):
    """Versioned transportation-only wire values, disjoint from presentation filters."""

    FREEWAY = 1000
    HIGHWAY = 1010
    FREEWAY_HIGHWAY_RAMP = 1020
    MAJOR_ROAD = 1030
    MAJOR_ROAD_RAMP = 1040
    MINOR_ROAD = 1050
    MINOR_ROAD_RAMP = 1060
    LOCAL_ROAD = 1070
    SERVICE_ROAD = 1080
    PEDESTRIAN_WAY = 1090
    FOUR_WHEEL_DRIVE = 1100
    RAILROAD = 1110
    FERRY = 1120
    TRAIL_PATH = 1130
    AIRPORT = 1140
    PORT = 1150
    TRANSPORTATION_PLACE = 1160
    OTHER_TRANSPORTATION = 1170


class MasterOnlyGeometrySubtype(IntEnum):
    """Geometry identities intentionally outside all presentation filter partitions."""

    WATERCOURSE_LINE = 2000
    WATER_AREA_OUTLINE = 2010


class RenderStyleToken(IntEnum):
    """Flight Alert presentation identities; never provider style-layer identities."""

    REGION_LABEL_V1 = 0xE8010001
    PLACE_LABEL_V1 = 0xE8010002
    WATER_LABEL_V1 = 0xE8010003
    WATER_LINE_V1 = 0xE8010004
    REGION_BOUNDARY_V1 = 0xE8010005
    COASTLINE_V1 = 0xE8010006
    PUBLIC_LAND_LABEL_V1 = 0xE8010007
    NEUTRAL_CONTEXT_LABEL_V1 = 0xE8010008
    TRANSPORT_LABEL_V1 = 0xE8010009
    TRANSPORT_LINE_V1 = 0xE801000A
    NAMED_GEOMETRY_FALLBACK_V1 = 0xE801000B
    WATER_AREA_OUTLINE_V1 = 0xE801000C


_FEATURE_KIND_BY_STYLE_TYPE: Mapping[str, FeatureKind] = MappingProxyType(
    {
        "fill": FeatureKind.POLYGON_OUTLINE,
        "line": FeatureKind.LINE,
        "symbol": FeatureKind.LABEL,
    }
)
_LABEL_RENDER_TOKEN_BY_GROUP: Mapping[LayerGroup, RenderStyleToken] = MappingProxyType(
    {
        LayerGroup.REGIONS: RenderStyleToken.REGION_LABEL_V1,
        LayerGroup.PLACES: RenderStyleToken.PLACE_LABEL_V1,
        LayerGroup.WATER: RenderStyleToken.WATER_LABEL_V1,
        LayerGroup.PUBLIC_LANDS: RenderStyleToken.PUBLIC_LAND_LABEL_V1,
        LayerGroup.CONTEXT: RenderStyleToken.NEUTRAL_CONTEXT_LABEL_V1,
        LayerGroup.TRANSPORTATION: RenderStyleToken.TRANSPORT_LABEL_V1,
    }
)
_LABEL_KIND_BY_GROUP: Mapping[LayerGroup, str] = MappingProxyType(
    {
        group: (
            "transportation_label"
            if group is LayerGroup.TRANSPORTATION
            else "label"
        )
        for group in _LABEL_RENDER_TOKEN_BY_GROUP
    }
)
_LINE_RENDER_TOKEN_BY_GROUP: Mapping[LayerGroup, RenderStyleToken] = MappingProxyType(
    {
        LayerGroup.TRANSPORTATION: RenderStyleToken.TRANSPORT_LINE_V1,
        LayerGroup.WATER: RenderStyleToken.WATER_LINE_V1,
    }
)
_LINE_KIND_BY_GROUP: Mapping[LayerGroup, str] = MappingProxyType(
    {
        LayerGroup.TRANSPORTATION: "transportation_line",
        LayerGroup.WATER: "water_line",
    }
)
_VIZ_EXCLUDED_VALUE = 3
_ONE_WAY_DIRECTIONS = frozenset({"F", "T"})
_BOUNDARY_SYMBOLS = tuple(range(12))


PUBLIC_LAND_RENDER_STYLE_TOKEN_ID = RenderStyleToken.PUBLIC_LAND_LABEL_V1.value
FALLBACK_RENDER_STYLE_TOKEN_ID = RenderStyleToken.NAMED_GEOMETRY_FALLBACK_V1.value


RETAINED_RAW_PROPERTIES = (
    "Alt_ID",
    "DirTravel",
    "DisplayID",
    "DisputeID",
    "SelectionPriority",
    "Viz",
    "_label_class",
    "_len",
    "_maxzoom",
    "_minzoom",
    "_symbol",
)

PRESENTATION_FILTER_SUBTYPES: Mapping[str, tuple[int, ...]] = MappingProxyType(
    {
        filter_id.value: tuple(
            subtype.value for subtype in filter_spec(filter_id).subtypes
        )
        for filter_id in FilterId
    }
)


@dataclass(frozen=True, slots=True)
class SourceLayerPolicy:
    source_layer: str
    layer_group: LayerGroup
    accepted_types: tuple[str, ...]
    family: str
    default_label_subtype: int | None = None
    default_line_subtype: int | None = None
    fallback_text_source_field: str | None = None
    fallback_label_subtype: int | None = None
    fallback_label_kind: str | None = None

    def __post_init__(self) -> None:
        if not self.source_layer or self.source_layer.rstrip() != self.source_layer:
            raise SemanticPolicyError("source-layer policy name must be canonical")
        if type(self.layer_group) is not LayerGroup:
            raise SemanticPolicyError("source-layer policy group must use LayerGroup")
        if not self.accepted_types or any(
            value not in {"fill", "line", "symbol"} for value in self.accepted_types
        ):
            raise SemanticPolicyError("source-layer policy has an unsupported style type")
        for value in (self.default_label_subtype, self.default_line_subtype):
            if value is not None and (type(value) is not int or value < 0):
                raise SemanticPolicyError("semantic subtype must be a nonnegative integer")
        if (self.fallback_text_source_field is None) != (
            self.fallback_label_subtype is None
        ) or (self.fallback_text_source_field is None) != (
            self.fallback_label_kind is None
        ):
            raise SemanticPolicyError(
                "fallback field, subtype, and semantic kind must be declared together"
            )
        if self.fallback_label_subtype is not None and (
            type(self.fallback_label_subtype) is not int
            or self.fallback_label_subtype < 0
        ):
            raise SemanticPolicyError(
                "fallback label subtype must be a nonnegative integer"
            )
        if self.fallback_label_kind is not None and (
            type(self.fallback_label_kind) is not str
            or not self.fallback_label_kind
        ):
            raise SemanticPolicyError("fallback label kind must be nonempty text")


def _policy(
    source_layer: str,
    group: LayerGroup,
    accepted_types: tuple[str, ...],
    family: str,
    *,
    label: int | IntEnum | None = None,
    line: int | IntEnum | None = None,
    fallback: str | None = None,
    fallback_label: int | IntEnum | None = None,
    fallback_kind: str | None = None,
) -> SourceLayerPolicy:
    return SourceLayerPolicy(
        source_layer=source_layer,
        layer_group=group,
        accepted_types=accepted_types,
        family=family,
        default_label_subtype=None if label is None else int(label),
        default_line_subtype=None if line is None else int(line),
        fallback_text_source_field=fallback,
        fallback_label_subtype=(
            None if fallback_label is None else int(fallback_label)
        ),
        fallback_label_kind=fallback_kind,
    )


_SOURCE_POLICIES = (
    _policy("Continent", LayerGroup.REGIONS, ("symbol",), "labels", label=SemanticSubtype.COUNTRY_TERRITORY),
    _policy("Admin0 point", LayerGroup.REGIONS, ("symbol",), "labels", label=SemanticSubtype.COUNTRY_TERRITORY),
    _policy("Admin1 area/label", LayerGroup.REGIONS, ("symbol",), "labels", label=SemanticSubtype.FIRST_ORDER_REGION),
    _policy("Admin2 area/label", LayerGroup.REGIONS, ("symbol",), "labels", label=SemanticSubtype.SECOND_LOCAL_REGION),
    _policy("Disputed label point", LayerGroup.REGIONS, ("symbol",), "labels", label=SemanticSubtype.COUNTRY_TERRITORY),
    _policy("City large scale", LayerGroup.PLACES, ("symbol",), "labels", label=SemanticSubtype.CITY_TOWN),
    _policy("City small scale", LayerGroup.PLACES, ("symbol",), "labels", label=SemanticSubtype.CITY_TOWN),
    _policy("Neighborhood", LayerGroup.PLACES, ("symbol",), "labels", label=SemanticSubtype.LOCAL_PLACE),
    _policy("Boundary line", LayerGroup.REGIONS, ("line",), "boundaries", line=SemanticSubtype.OTHER_ADMIN_BOUNDARY),
    _policy("Watershed boundary", LayerGroup.WATER, ("line",), "boundaries", line=SemanticSubtype.WATERSHED_WATER_BOUNDARY),
    _policy("Coastline", LayerGroup.WATER, ("line",), "water", line=SemanticSubtype.COASTLINE),
    _policy("Water line small scale", LayerGroup.WATER, ("line",), "water", line=MasterOnlyGeometrySubtype.WATERCOURSE_LINE),
    _policy("Water line medium scale", LayerGroup.WATER, ("line",), "water", line=MasterOnlyGeometrySubtype.WATERCOURSE_LINE),
    _policy(
        "Water line large scale",
        LayerGroup.WATER,
        ("line",),
        "water",
        line=MasterOnlyGeometrySubtype.WATERCOURSE_LINE,
        fallback="_name_en",
        fallback_label=SemanticSubtype.UNSPECIFIED_WATERCOURSE,
        fallback_kind="named_geometry_fallback",
    ),
    _policy("Water line", LayerGroup.WATER, ("line",), "water", line=MasterOnlyGeometrySubtype.WATERCOURSE_LINE),
    _policy("Water line/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.UNSPECIFIED_WATERCOURSE),
    _policy("Water point", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.LAKE_RESERVOIR),
    _policy("Water area/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.LAKE_RESERVOIR),
    _policy("Water area large scale/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.LAKE_RESERVOIR),
    _policy("Water area medium scale/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.LAKE_RESERVOIR),
    _policy("Water area small scale/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.LAKE_RESERVOIR),
    _policy("Marine area/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.OCEAN_SEA),
    _policy("Marine waterbody/label", LayerGroup.WATER, ("symbol",), "labels", label=SemanticSubtype.OCEAN_SEA),
    _policy("Water area", LayerGroup.WATER, ("fill",), "water", line=SemanticSubtype.OTHER_SOURCED_OUTLINE),
    _policy("Water area large scale", LayerGroup.WATER, ("fill",), "water", line=SemanticSubtype.OTHER_SOURCED_OUTLINE),
    _policy("Water area medium scale", LayerGroup.WATER, ("fill",), "water", line=SemanticSubtype.OTHER_SOURCED_OUTLINE),
    _policy("Water area small scale", LayerGroup.WATER, ("fill",), "water", line=SemanticSubtype.OTHER_SOURCED_OUTLINE),
    _policy("Marine park/label", LayerGroup.PUBLIC_LANDS, ("symbol",), "public_lands", label=SemanticSubtype.PROTECTED_LAND),
    _policy("Openspace or forest/label", LayerGroup.CONTEXT, ("symbol",), "labels", label=SemanticSubtype.LOCAL_PLACE),
    _policy("Park or farming/label", LayerGroup.CONTEXT, ("symbol",), "labels", label=SemanticSubtype.LOCAL_PLACE),
    _policy("Admin0 forest or park/label", LayerGroup.CONTEXT, ("symbol",), "labels", label=SemanticSubtype.LOCAL_PLACE),
    _policy("Admin1 forest or park/label", LayerGroup.CONTEXT, ("symbol",), "labels", label=SemanticSubtype.LOCAL_PLACE),
    _policy("Road", LayerGroup.TRANSPORTATION, ("line",), "transportation", line=TransportSubtype.OTHER_TRANSPORTATION),
    _policy("Road tunnel", LayerGroup.TRANSPORTATION, ("line",), "transportation", line=TransportSubtype.OTHER_TRANSPORTATION),
    _policy("Road/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.OTHER_TRANSPORTATION),
    _policy("Road tunnel/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.OTHER_TRANSPORTATION),
    _policy("Railroad", LayerGroup.TRANSPORTATION, ("line",), "transportation", line=TransportSubtype.RAILROAD),
    _policy("Railroad/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.RAILROAD),
    _policy("Ferry", LayerGroup.TRANSPORTATION, ("line",), "transportation", line=TransportSubtype.FERRY),
    _policy("Ferry/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.FERRY),
    _policy("Trail or path", LayerGroup.TRANSPORTATION, ("line",), "transportation", line=TransportSubtype.TRAIL_PATH),
    _policy("Trail or path/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.TRAIL_PATH),
    _policy("Pedestrian/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.PEDESTRIAN_WAY),
    _policy("Transportation/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.OTHER_TRANSPORTATION),
    _policy("Transportation place", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.TRANSPORTATION_PLACE),
    _policy("Airport/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.AIRPORT),
    _policy("Exit", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.OTHER_TRANSPORTATION),
    _policy("Port/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.PORT),
    _policy("Freight/label", LayerGroup.TRANSPORTATION, ("symbol",), "transportation", label=TransportSubtype.OTHER_TRANSPORTATION),
)


if len({item.source_layer for item in _SOURCE_POLICIES}) != len(_SOURCE_POLICIES):
    raise RuntimeError("source-layer policy must have exactly one owner")


SOURCE_LAYER_POLICIES: Mapping[str, SourceLayerPolicy] = MappingProxyType(
    {item.source_layer: item for item in _SOURCE_POLICIES}
)
SOURCE_LAYER_GROUPS: Mapping[str, LayerGroup] = MappingProxyType(
    {item.source_layer: item.layer_group for item in _SOURCE_POLICIES}
)


@dataclass(frozen=True, slots=True)
class SemanticClassification:
    layer_group: LayerGroup
    feature_kind: FeatureKind
    semantic_subtype: int
    kind: str
    render_style_token_id: int
    admin_level: int | None = None
    disputed: bool = False
    coastline: bool = False
    intermittent: bool = False
    tunnel: bool = False
    shield: bool = False
    one_way: bool = False
    land_evidence: LandEvidence = LandEvidence.NOT_APPLICABLE
    protected_status: ProtectedStatus = ProtectedStatus.NOT_APPLICABLE

    def __post_init__(self) -> None:
        if type(self.layer_group) is not LayerGroup:
            raise SemanticPolicyError("classification group must use LayerGroup")
        if type(self.feature_kind) is not FeatureKind:
            raise SemanticPolicyError("classification kind must use FeatureKind")
        if type(self.semantic_subtype) is not int or self.semantic_subtype < 0:
            raise SemanticPolicyError("classification subtype must be a nonnegative integer")
        if not self.kind:
            raise SemanticPolicyError("classification kind must be explicit")
        if type(self.render_style_token_id) is not int or self.render_style_token_id <= 0:
            raise SemanticPolicyError("render token must be a nonzero integer")
        if self.admin_level is not None and (
            type(self.admin_level) is not int or not 0 <= self.admin_level <= 5
        ):
            raise SemanticPolicyError("admin level must be an integer in [0, 5]")
        for value in (
            self.disputed,
            self.coastline,
            self.intermittent,
            self.tunnel,
            self.shield,
            self.one_way,
        ):
            if type(value) is not bool:
                raise SemanticPolicyError("classification flags must be Boolean values")
        if type(self.land_evidence) is not LandEvidence:
            raise SemanticPolicyError("land evidence enum is unknown")
        if type(self.protected_status) is not ProtectedStatus:
            raise SemanticPolicyError("protected-status enum is unknown")
        if self.layer_group is LayerGroup.PUBLIC_LANDS and (
            self.land_evidence is not LandEvidence.SOURCE_EXPLICIT
            or self.protected_status is not ProtectedStatus.SOURCE_EXPLICIT
        ):
            raise SemanticPolicyError("PUBLIC_LANDS requires source-explicit evidence")


def semantic_classification_document(
    classification: SemanticClassification,
) -> dict[str, object]:
    if not isinstance(classification, SemanticClassification):
        raise SemanticPolicyError(
            "semantic classification document requires a classification"
        )
    return {
        "adminLevel": classification.admin_level,
        "coastline": classification.coastline,
        "disputed": classification.disputed,
        "featureKind": classification.feature_kind.value,
        "intermittent": classification.intermittent,
        "kind": classification.kind,
        "landEvidence": classification.land_evidence.value,
        "layerGroup": classification.layer_group.value,
        "oneWay": classification.one_way,
        "protectedStatus": classification.protected_status.value,
        "renderStyleTokenId": classification.render_style_token_id,
        "semanticSubtype": classification.semantic_subtype,
        "shield": classification.shield,
        "tunnel": classification.tunnel,
    }


def _exact_int(properties: Mapping[str, object], name: str, *, required: bool) -> int | None:
    if name not in properties:
        if required:
            raise SemanticPolicyError(f"missing required {name} integer")
        return None
    value = properties[name]
    if type(value) is not int:
        raise SemanticPolicyError(f"{name} must be an exact integer")
    return value


def _viz_excluded(properties: Mapping[str, object]) -> bool:
    value = _exact_int(properties, "Viz", required=False)
    return value == _VIZ_EXCLUDED_VALUE


def _boundary_subtype(admin_level: int) -> SemanticSubtype:
    if admin_level == 0:
        return SemanticSubtype.INTERNATIONAL_BOUNDARY
    if admin_level == 1:
        return SemanticSubtype.STATE_PROVINCE_BOUNDARY
    if admin_level == 2:
        return SemanticSubtype.COUNTY_LOCAL_BOUNDARY
    return SemanticSubtype.OTHER_ADMIN_BOUNDARY


def classify_boundary(
    source_layer: str, properties: Mapping[str, object]
) -> SemanticClassification | None:
    if source_layer == "Watershed boundary":
        return SemanticClassification(
            LayerGroup.WATER,
            FeatureKind.LINE,
            SemanticSubtype.WATERSHED_WATER_BOUNDARY.value,
            "watershed_water_boundary",
            RenderStyleToken.WATER_LINE_V1.value,
        )
    if source_layer != "Boundary line":
        return None
    if _viz_excluded(properties):
        return None
    symbol = _exact_int(properties, "_symbol", required=True)
    assert symbol is not None
    if 0 <= symbol <= 5:
        admin_level = symbol
        disputed = False
    elif 6 <= symbol <= 11:
        dispute_id = _exact_int(properties, "DisputeID", required=False)
        if dispute_id in (None, 0):
            return None
        admin_level = symbol - 6
        disputed = True
    else:
        return None
    return SemanticClassification(
        LayerGroup.REGIONS,
        FeatureKind.LINE,
        _boundary_subtype(admin_level).value,
        "disputed_admin_boundary" if disputed else "admin_boundary",
        RenderStyleToken.REGION_BOUNDARY_V1.value,
        admin_level=admin_level,
        disputed=disputed,
    )


def classify_coastline(
    source_layer: str, properties: Mapping[str, object]
) -> SemanticClassification | None:
    del properties
    if source_layer != "Coastline":
        return None
    return SemanticClassification(
        LayerGroup.WATER,
        FeatureKind.LINE,
        SemanticSubtype.COASTLINE.value,
        "coastline",
        RenderStyleToken.COASTLINE_V1.value,
        coastline=True,
    )


_WATER_LINE_KIND = MappingProxyType({
    0: ("stream_or_river", MasterOnlyGeometrySubtype.WATERCOURSE_LINE, False),
    1: ("canal_or_ditch", MasterOnlyGeometrySubtype.WATERCOURSE_LINE, False),
    4: ("stream_or_river", MasterOnlyGeometrySubtype.WATERCOURSE_LINE, True),
})


def classify_water_line(
    source_layer: str, properties: Mapping[str, object]
) -> SemanticClassification | None:
    if source_layer != "Water line":
        return None
    symbol = _exact_int(properties, "_symbol", required=True)
    item = _WATER_LINE_KIND.get(symbol)
    if item is None:
        return None
    kind, subtype, intermittent = item
    return SemanticClassification(
        LayerGroup.WATER,
        FeatureKind.LINE,
        subtype.value,
        kind,
        RenderStyleToken.WATER_LINE_V1.value,
        intermittent=intermittent,
    )


_WATER_AREA_KIND_BY_SOURCE = MappingProxyType({
    "Water area": MappingProxyType({
        7: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, False),
        6: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, True),
        3: ("swamp_or_marsh", SemanticSubtype.OTHER_SOURCED_OUTLINE, False),
        1: ("playa", SemanticSubtype.OTHER_SOURCED_OUTLINE, False),
        2: ("ice", SemanticSubtype.OTHER_SOURCED_OUTLINE, False),
    }),
    "Water area large scale": MappingProxyType({
        0: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, False),
        1: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, True),
    }),
    "Water area medium scale": MappingProxyType({
        0: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, False),
        1: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, True),
    }),
    "Water area small scale": MappingProxyType({
        None: ("lake_river_or_bay", MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE, False),
    }),
})


def classify_water_area(
    source_layer: str, properties: Mapping[str, object]
) -> SemanticClassification | None:
    table = _WATER_AREA_KIND_BY_SOURCE.get(source_layer)
    if table is None:
        return None
    symbol = (
        None
        if source_layer == "Water area small scale" and "_symbol" not in properties
        else _exact_int(properties, "_symbol", required=True)
    )
    item = table.get(symbol)
    if item is None:
        return None
    kind, subtype, intermittent = item
    return SemanticClassification(
        LayerGroup.WATER,
        FeatureKind.POLYGON_OUTLINE,
        subtype.value,
        kind,
        RenderStyleToken.WATER_AREA_OUTLINE_V1.value,
        intermittent=intermittent,
    )


_ROAD_SUBTYPE = MappingProxyType({
    0: TransportSubtype.FREEWAY,
    1: TransportSubtype.HIGHWAY,
    2: TransportSubtype.FREEWAY_HIGHWAY_RAMP,
    3: TransportSubtype.MAJOR_ROAD,
    4: TransportSubtype.MAJOR_ROAD_RAMP,
    5: TransportSubtype.MINOR_ROAD,
    6: TransportSubtype.MINOR_ROAD_RAMP,
    7: TransportSubtype.LOCAL_ROAD,
    8: TransportSubtype.SERVICE_ROAD,
    9: TransportSubtype.PEDESTRIAN_WAY,
    10: TransportSubtype.FOUR_WHEEL_DRIVE,
})


def classify_road(
    source_layer: str, properties: Mapping[str, object]
) -> SemanticClassification | None:
    if source_layer not in {"Road", "Road tunnel"}:
        return None
    if _viz_excluded(properties):
        return None
    symbol = _exact_int(properties, "_symbol", required=True)
    subtype = _ROAD_SUBTYPE.get(symbol)
    if subtype is None:
        return None
    tunnel = source_layer == "Road tunnel"
    return SemanticClassification(
        LayerGroup.TRANSPORTATION,
        FeatureKind.LINE,
        subtype.value,
        subtype.name.lower(),
        RenderStyleToken.TRANSPORT_LINE_V1.value,
        tunnel=tunnel,
    )


_SHIELD_NAMES = (
    "Rectangle hexagon brown white",
    "Rectangle hexagon green white",
    "Rectangle hexagon red white",
    "Rectangle hexagon blue white",
    "Octagon green white",
    "Hexagon orange black",
    "Hexagon green white",
    "Hexagon red white",
    "Hexagon white black",
    "Pentagon green yellow",
    "Pentagon green white",
    "Pentagon yellow black",
    "Pentagon blue white",
    "Pentagon white black",
    "Pentagon inverse white black",
    "Rectangle green yellow",
    "Rectangle green white",
    "Rectangle yellow black",
    "Hexagon blue white",
    "Rectangle red white",
    "Rectangle blue white",
    "Rectangle white black",
    "V-shaped white black",
    "U-shaped blue white",
    "U-shaped red white",
    "U-shaped yellow black",
    "U-shaped green leaf",
    "U-shaped white green",
    "U-shaped white black",
    "Secondary Hwy red white",
    "Secondary Hwy green white",
    "Secondary Hwy white black",
    "Shield white black",
    "Shield blue white",
)
ROAD_SHIELD_STYLE_IDS = frozenset(
    {f"Road/label/{name}" for name in _SHIELD_NAMES}
    | {f"Road/label/{name} (Alt)" for name in _SHIELD_NAMES}
)

_ONE_WAY_SUFFIX_SUBTYPE = MappingProxyType({
    "One-way arrow local road": TransportSubtype.LOCAL_ROAD,
    "One-way arrow minor road": TransportSubtype.MINOR_ROAD,
    "One-way arrow major road": TransportSubtype.MAJOR_ROAD,
    "One-way arrow freeway, motorway, highway ramp": (
        TransportSubtype.FREEWAY_HIGHWAY_RAMP
    ),
    "One-way arrow freeway, motorway, highway": TransportSubtype.FREEWAY,
})
ONE_WAY_STYLE_CLASSIFICATIONS: Mapping[
    str, tuple[TransportSubtype, bool]
] = MappingProxyType(
    {
        f"{source}/{suffix}": (subtype, source == "Road tunnel/label")
        for source in ("Road/label", "Road tunnel/label")
        for suffix, subtype in _ONE_WAY_SUFFIX_SUBTYPE.items()
    }
)
ONE_WAY_STYLE_IDS = frozenset(ONE_WAY_STYLE_CLASSIFICATIONS)


@dataclass(frozen=True, slots=True)
class TransportStyleFlags:
    shield: bool
    one_way: bool


def transport_style_flags(style_layer_id: str) -> TransportStyleFlags:
    if type(style_layer_id) is not str:
        raise SemanticPolicyError("style-layer identity must be text")
    return TransportStyleFlags(
        shield=style_layer_id in ROAD_SHIELD_STYLE_IDS,
        one_way=style_layer_id in ONE_WAY_STYLE_IDS,
    )


def classify_one_way_style(
    source_layer: str,
    style_layer_id: str,
    properties: Mapping[str, object],
    *,
    require_direction: bool = True,
) -> SemanticClassification | None:
    item = ONE_WAY_STYLE_CLASSIFICATIONS.get(style_layer_id)
    if item is None:
        return None
    expected_source_layer = (
        "Road tunnel/label" if item[1] else "Road/label"
    )
    if source_layer != expected_source_layer:
        raise SemanticPolicyError(
            "one-way style identity violates source-layer ownership"
        )
    if require_direction:
        direction = properties.get("DirTravel")
        if type(direction) is not str or direction not in _ONE_WAY_DIRECTIONS:
            return None
    subtype, tunnel = item
    return SemanticClassification(
        LayerGroup.TRANSPORTATION,
        FeatureKind.LINE,
        subtype.value,
        "one_way_arrow",
        RenderStyleToken.TRANSPORT_LINE_V1.value,
        tunnel=tunnel,
        one_way=True,
    )


_DISPUTED_LABEL_STYLE_CLASSIFICATIONS = MappingProxyType({
    "Disputed label point/Island": (
        LayerGroup.WATER,
        SemanticSubtype.ISLAND_ISLET,
        "disputed_island_label",
        RenderStyleToken.WATER_LABEL_V1,
    ),
    "Disputed label point/Waterbody": (
        LayerGroup.WATER,
        SemanticSubtype.LAKE_RESERVOIR,
        "disputed_waterbody_label",
        RenderStyleToken.WATER_LABEL_V1,
    ),
    "Disputed label point/Admin0": (
        LayerGroup.REGIONS,
        SemanticSubtype.COUNTRY_TERRITORY,
        "disputed_admin0_label",
        RenderStyleToken.REGION_LABEL_V1,
    ),
})


def classify_disputed_label_style(
    source_layer: str,
    style_layer_id: str,
) -> SemanticClassification | None:
    item = _DISPUTED_LABEL_STYLE_CLASSIFICATIONS.get(style_layer_id)
    if item is None:
        return None
    if source_layer != "Disputed label point":
        raise SemanticPolicyError(
            "disputed style identity violates source-layer ownership"
        )
    group, subtype, kind, token = item
    return SemanticClassification(
        group,
        FeatureKind.LABEL,
        subtype.value,
        kind,
        token.value,
        disputed=True,
    )


_TRANSPORT_PLACE_SUBTYPE = MappingProxyType({
    "Place/Transportation/Airport": TransportSubtype.AIRPORT,
    "Place/Transportation/Airport Terminal": TransportSubtype.AIRPORT,
    "Place/Transportation/Heliport": TransportSubtype.AIRPORT,
    "Place/Transportation/Port": TransportSubtype.PORT,
    "Place/Transportation/Marina": TransportSubtype.PORT,
    "Place/Transportation/Pier": TransportSubtype.PORT,
    "Place/Transportation/Dock": TransportSubtype.PORT,
    "Place/Transportation/Boating": TransportSubtype.PORT,
    "Place/Transportation/Water Transit": TransportSubtype.PORT,
    "Place/Transportation/Ferry Terminal": TransportSubtype.PORT,
    "Place/Transportation/Rail Ferry": TransportSubtype.FERRY,
    "Place/Transportation/Off Road Trailhead": TransportSubtype.TRAIL_PATH,
})
_ALL_TRANSPORT_PLACE_STYLE_IDS = frozenset(
    {
        "Place/Transportation/Other Transportation",
        "Place/Transportation/Cargo Center",
        "Place/Transportation/Weigh Station",
        "Place/Transportation/Truck Parking",
        "Place/Transportation/Truck Stop",
        "Place/Transportation/Off-Road Vehicle Area",
        "Place/Transportation/Off Road Trailhead",
        "Place/Transportation/Rail Ferry",
        "Place/Transportation/Taxi",
        "Place/Transportation/Railyard",
        "Place/Transportation/Boating",
        "Place/Transportation/Gondola",
        "Place/Transportation/Dock",
        "Place/Transportation/Pier",
        "Place/Transportation/Port",
        "Place/Transportation/Marina",
        "Place/Transportation/Water Transit",
        "Place/Transportation/Ferry Terminal",
        "Place/Transportation/Border Crossing",
        "Place/Transportation/Rest Area",
        "Place/Transportation/Tollbooth",
        "Place/Transportation/Highway Exit",
        "Place/Transportation/Tunnel",
        "Place/Transportation/Bridge",
        "Place/Transportation/Bicycle Sharing Location",
        "Place/Transportation/Parking",
        "Place/Transportation/Heliport",
        "Place/Transportation/Airport Terminal",
        "Place/Transportation/Airport",
        "Place/Transportation/Local Transit",
        "Place/Transportation/Bus Station",
        "Place/Transportation/Metro Station",
        "Place/Transportation/Lightrail",
        "Place/Transportation/Rail Station",
        "Place/Transportation/Train Station",
    }
)


def classify_transportation_place(
    style_layer_id: str, properties: Mapping[str, object]
) -> SemanticClassification | None:
    del properties
    if style_layer_id not in _ALL_TRANSPORT_PLACE_STYLE_IDS:
        return None
    subtype = _TRANSPORT_PLACE_SUBTYPE.get(
        style_layer_id, TransportSubtype.TRANSPORTATION_PLACE
    )
    return SemanticClassification(
        LayerGroup.TRANSPORTATION,
        FeatureKind.LABEL,
        subtype.value,
        subtype.name.lower(),
        RenderStyleToken.TRANSPORT_LABEL_V1.value,
    )


_SOURCE_EXPLICIT_LAND_STYLES = frozenset({"Marine park/label/Default"})
_AMBIGUOUS_LAND_SOURCES = frozenset(
    {
        "Admin0 forest or park/label",
        "Admin1 forest or park/label",
        "Openspace or forest/label",
        "Park or farming/label",
    }
)


def classify_land(
    source_layer: str,
    style_layer_id: str,
    properties: Mapping[str, object],
) -> SemanticClassification | None:
    del properties
    if (
        source_layer != "Marine park/label"
        and source_layer not in _AMBIGUOUS_LAND_SOURCES
    ):
        return None
    _require_source_style_identity_owned(source_layer, style_layer_id)
    if (
        source_layer == "Marine park/label"
        and style_layer_id in _SOURCE_EXPLICIT_LAND_STYLES
    ):
        return SemanticClassification(
            LayerGroup.PUBLIC_LANDS,
            FeatureKind.LABEL,
            SemanticSubtype.PROTECTED_LAND.value,
            "source_explicit_protected_land",
            RenderStyleToken.PUBLIC_LAND_LABEL_V1.value,
            land_evidence=LandEvidence.SOURCE_EXPLICIT,
            protected_status=ProtectedStatus.SOURCE_EXPLICIT,
        )
    if source_layer in _AMBIGUOUS_LAND_SOURCES:
        return SemanticClassification(
            LayerGroup.CONTEXT,
            FeatureKind.LABEL,
            SemanticSubtype.LOCAL_PLACE.value,
            "ambiguous_land_context",
            RenderStyleToken.NEUTRAL_CONTEXT_LABEL_V1.value,
            land_evidence=LandEvidence.AMBIGUOUS,
            protected_status=ProtectedStatus.AMBIGUOUS,
        )
    raise SemanticPolicyError("land style has no exact classification")


_CITY_CAPITAL_STYLE_IDS = frozenset(
    {
        "City small scale/other capital",
        "City small scale/town large other capital",
        "City small scale/small other capital",
        "City small scale/medium other capital",
        "City small scale/town small admin0 capital",
        "City small scale/town large admin0 capital",
        "City small scale/small admin0 capital",
        "City small scale/medium admin0 capital",
        "City small scale/large other capital",
        "City small scale/x large admin2 capital",
        "City small scale/large admin0 capital",
        "City small scale/x large admin1 capital",
        "City small scale/x large admin0 capital",
    }
)

_WATER_LABEL_SUBTYPE = MappingProxyType({
    "Water point/Stream or river": SemanticSubtype.UNSPECIFIED_WATERCOURSE,
    "Water point/Lake or reservoir": SemanticSubtype.LAKE_RESERVOIR,
    "Water point/Bay or inlet": SemanticSubtype.BAY_SOUND,
    "Water point/Sea or ocean": SemanticSubtype.OCEAN_SEA,
    "Water point/Canal or ditch": SemanticSubtype.CANAL_CHANNEL,
    "Water point/Island": SemanticSubtype.ISLAND_ISLET,
    "Water area/label/Canal or ditch": SemanticSubtype.CANAL_CHANNEL,
    "Water area/label/Small river": SemanticSubtype.RIVER,
    "Water area/label/Large river": SemanticSubtype.RIVER,
    "Water area/label/Small lake or reservoir": SemanticSubtype.LAKE_RESERVOIR,
    "Water area/label/Large lake or reservoir": SemanticSubtype.LAKE_RESERVOIR,
    "Water area/label/Bay or inlet": SemanticSubtype.BAY_SOUND,
    "Water area/label/Small island": SemanticSubtype.ISLAND_ISLET,
    "Water area/label/Large island": SemanticSubtype.ISLAND_ISLET,
    "Water area large scale/label/River": SemanticSubtype.RIVER,
    "Water area large scale/label/Lake or lake intermittent": SemanticSubtype.LAKE_RESERVOIR,
})


_ROAD_LABEL_SUBTYPE = MappingProxyType({
    "Road/label/Pedestrian": TransportSubtype.PEDESTRIAN_WAY,
    "Road/label/Local": TransportSubtype.LOCAL_ROAD,
    "Road/label/Minor": TransportSubtype.MINOR_ROAD,
    "Road/label/Major": TransportSubtype.MAJOR_ROAD,
    "Road/label/Major, alt name": TransportSubtype.MAJOR_ROAD,
    "Road/label/Highway": TransportSubtype.HIGHWAY,
    "Road/label/Freeway Motorway": TransportSubtype.FREEWAY,
    "Road/label/Freeway Motorway, alt name": TransportSubtype.FREEWAY,
    "Road tunnel/label/Pedestrian": TransportSubtype.PEDESTRIAN_WAY,
    "Road tunnel/label/Local": TransportSubtype.LOCAL_ROAD,
    "Road tunnel/label/Minor": TransportSubtype.MINOR_ROAD,
    "Road tunnel/label/Major": TransportSubtype.MAJOR_ROAD,
    "Road tunnel/label/Major, alt name": TransportSubtype.MAJOR_ROAD,
    "Road tunnel/label/Highway": TransportSubtype.HIGHWAY,
    "Road tunnel/label/Freeway Motorway": TransportSubtype.FREEWAY,
    "Road tunnel/label/Freeway Motorway, alt name": TransportSubtype.FREEWAY,
})


_STYLE_OWNERSHIP_DOMAIN = b"FAE8STYLEOWNER1\0"
_OWNED_SOURCE_STYLE_PAIR_BLOB = base64.b64decode(
    "AL959e/SsWXiViyQItcSsQ72wn5tbT5kJjbRwHRDRAIBhX1jrlFFA2QvoJtlLYdCQEgIoQvV+bTFXXe4HUnX0gLouEr2YZmJ"
    "UEKvczGtumsS55v/CQ4Xz78eUH2k+iGnA3grJWWqVqciYslsOik7wFFGwISROXp1kGkQqFH9KdQDjtnonQ1uSbuVQvrppPyE"
    "I5l3UuSPzxOyrBP7xRy8KQOqDkjEf3fQtHv9opnfCzVYnTraapGY744TR7bpMaQbBO4xAs6a55RoazvkzPgy6HI/TtVE2r2V"
    "IS+gxnAma18FlxPyYhvQLt1mK+/LEbvJFlHRQprCIxwsC9uS171uAwYXgElZF+E8thfR/cc7MvpzKiFQLQ/kIvzwu/ir9APC"
    "B4vehbf6edeFM8+S80BQULAY8dBHy7pH5pijf8kL+5cHu/W4nwAo2WSjR9OJp/dJKB9bKGAmooDXPtmqLVKWiQhvuD92Huo9"
    "gvLU4lqULijY/B/cRUVn3oy5CKQRh/LMCRos4YMbXAY/ZFKqcNChabhXg2I5+E2YlHJOh7SlrqMJ13iUPIRJi7AafzQzI1Et"
    "TS82iQwKOfR8dYhd8JCBcwnZuLgegM/kDm9p5Y51G5CApWUXADlbTfrlwSUKeULDCkQ60klpPzVHUV0ISq4oLpog+PyMVQUx"
    "56N2byB2230K0XGcx89yJpkRHGqXbNeVEOEHGp26Jo68QkMZWxfTbgyq5Elq2azBE+hy9F6or1hDIEBBisqbOnJ6JR2N3U8U"
    "D/W6miDPJXD79LN0YNe6UdpTySOhG0MnFkNTU1sIuCoUQuYIMQdjJY8Zn9vFenrZ80ihZgQnjzIjITSQknS6qBVNbVQagu/Z"
    "/4i/SPVH82+2hIOSWU8Kv5/NCFK403jqFnauxvneyPFo23lGUz7CrQwhSz/LJa7nvfibtWtVnLUWkugXyr8Kgo8vF56fz9rl"
    "FJvod7X9tYtyqONNOVfsuhn1EQznEfJHdUCVnbzf+Mydxz9F0/tGBj4ShFHb31qbGhArkfAEpw+dK9EKsglXqhmRPttrDJ59"
    "LIFqA9klQQUaX3tjTwHgjhlkf3AiWR4AWYg126qDtYflGxL0VkI+Ghp87VGK0/7sfBnDfm/uGbP8Z4QJbuevzRzSUWPyvF6j"
    "G4atxezR0TiWxk7gnnvZzDbmQTLlX1qssT2wdQS+/wseWzEqIV9Jxjxx5fJTEXGcxwAxZVMF7o+mu3j4UQUQnB9u8IkRYX6q"
    "fXCjVWfVt/joLCqXjLhtHeL/jUmGGvpJH+8vkfLsFqzt1tIlxK8UYSToVef7P+evCFzyJt3yRyAgnNKpczCzv0ctdFrGcHVh"
    "xym8dHScuGa0KLpWuVGImCERMLEVb8KvyC4ZbQcPpVQzKBVi9mRalfTnGVXz3I/JIsOyEHTGOHjDf/4HLzzfATdweVZJG5Uw"
    "v7qy5s60fuQi6pEGkI946Gy9uNQ1o0j9JhweAKjMRSjOyVQs0OZrFCMoOs/AJy3Vidl40yQII9lGgJYE5jyZH2BvX0TinNQ3"
    "JfdY3yVoP2N53w0asqA5ucuPtzALrUMdv2l27C0iGOAmaU0QCPUGNueNY5SpXaTtiBJ7DYaS5Z3EdUG502QraifnTPguZJJW"
    "CN+CTQ6++7/XF1ndSrn1Nn4gb6v22YPqKAJTdMK61lhbO5eI5uWW6hbISIUU9spmg9BYJMn+iFIqWus9Z/rugPBowRhkIn+y"
    "be4oyA6ODjNu3PL4BpqpmSqrxpVJ3s76MVOUPmzbJdDNjQ7u3Rhy3SJ/GnBtcTOTKrjErnkEJKeWcBWm1+/el3tnf1kF1wD/"
    "Qk+h5x/mhkArzyQHUlaiv2QLp8IXWMr4yEKziOiV7Vm6CAReOJEJKiwOJywYiJTfg3S/BMrVhxeSI1PFFtj9MrCju+H0H4ST"
    "LDaC0W8UbYHl5Y6YoxAnbwWVvLJmxphX4zrMR6kzK7Usroqj+2GsrchFuCiECz23TjW0gqiGTwEWuFapaJqc4yzm0ESRaQbr"
    "7T5G8O2FZiEyGxs+G5d0j73KmJmHoWWLLQRVz/fkpsCQI5QWq2elhgz4oQpmc8EI16KbrqzNGfAtitQeY1mtoMmiKGG1SfLh"
    "AupxhNXUFwUqJDmnxdV+5C4j1Mk03868XzZgdvTmCtxxoHFdJLOrfZ9HVZEVrV/+LqRJCGQxBeyF0pbTbPHBf4tc/hM4JYy6"
    "v7u+e3XcXvYvfO2+GcRJNv2AR+MstV9Bm9Z92qaHG02qZNtT73ptWy/3ClEn9js3/jpepc+EFrVxBJdBusGq6dCJMWLyNLFV"
    "MLep11U9cyW3/ZB4a1p8hclnuZYwJF/JUF0p2HCeDxQxSvLh4+bc1tKnfYHFHZ3XX58hAVt3jgLm/ORSJXdKUjGv1nBuXpSb"
    "ik3SmgJvPyghpoUxXIAp17MRJxCBHv8hMu1OsIcNA9UYrM0EwqVxQ6xF6eg14yNnE9kxsxiK2n4z7SpPofKnNQpEeD/cAQcK"
    "lb6EBeBmkhLQ14x/zjFi3TSIf8nf22pugeFJ1tjx4YOFwfq9lq5uiav7LZBTnxhKNTDz9W8uOaXD9B9CU08OZIdWGyvmBRAn"
    "L0gt5xc5DXY17lrM99E+aUx6c1b56TcBYaG//LO/sLUeb60D6+3uljawquzTxSqqQiuLGIkWMyB8/2EVxfi79+tPEbeP3bHh"
    "Nrj3uQZj57f1egkp1VYAhcMI/3CfAHDwpz2S34Oz+TM4LgUrGH//KnrH474XRC0ctRdFUbXrfmVqb0XVfpbpQThDOmt86e4G"
    "E6DNke+JDf695y5qea5RCiv6OqkZP1zrOh0CX4tEPdP3jssRgNxTDorT4kzixL03On+LOcYVL/A6t7zsdFjRaWaBvQW6h8V8"
    "OqSMy9ljMrC0DB7noXDVEDrutYNf8v/1+A4seprx2LkuVCelnSCK0C1FQfSpThuUPILq4Mxpovr9dJ+Aycw0qOzDjmCkjTLM"
    "E2GCPCvsJWs9TUyLRafdsVpu7hLiFJb5lG2GcEZ8ffw8NkdEL5aCPT2PLBoskXn9vL1gZn3tY+FZX7b5H+DKuDW4SWpghNfO"
    "Ptpp3EkDu/LhmMXaT/nKv5Cs6MR+ykNCs8qYLhiG/68/RwU5PyNyuWoxtIxrkwSYFmppS92Ea+5XjWy+Zly10UDBY+akUY6v"
    "1MTshj2rIvqa9VrMSShRgpbqmENRpebOQSiasK2gcUxi0HdNs4qUE22b2RY3hXSPM427eMEMowNBtdJX1VXMQ8xcW3s4C5ix"
    "yL3d8p1lvw99qfmz8On65UJpxjIwbmq181hQwW0tcT8y7h7/toCZYJOxe4Amn3ZxRDBtQnI3X4zq8vZ/wyn+uFe7kA/+Lc5q"
    "1kwDGxsmJctEUgGTOwiWHdgEryeGykQ0e/U7KbR5LZ1fqFUdLkhAekW/IobK5qxxGkLLwM3EwdynjmLoDtNS6UuZkGhxuix2"
    "R1dFYtN/HA8i6pf8xBLmf19LmZTnDdRELlKdtMFxVnlIT6jlfIrhkqZcZGvZ+n/2XQoob+eZxRw84s8nbPeMw0sG18uEI8lz"
    "DZ9pp9TRJ3y1CqOirSpj+8A6sa2TclevS0X2pdy3VmEDbHQfKkvKwGdDXldWjXgOdpcsYfFRLWdLqtluDFNc3WKrUpy0opw0"
    "QL2pgELpQpdqH5JSWaHWBEwKNQIVycJWqbFQ/w6cEmHEyCig02s3zQVqgIUgf5P5TY/H4maL//QT8LZnLoBtWjnn6SiQJvJG"
    "zw8Ne+3OaBFNmfcMen/2YDsltO+/vp1yf+PnhCaYl7xOK11Uit9lxk5HoEwuYbALILPUbKCmLJ7ooCbunVLXKFqDIrZMfrTJ"
    "T5eXMSoQuJ4kbbTzaQD5Qv9ofa3Nee4/BJhPo57rcW1Qb+yql8///7L3ux1Aa3+PxiEL+XuzdIjAwsPPiVcvBFGuZiju4Tuc"
    "mY9h+XPD7XYXlMLQzd6fyMeNliDmAx96UnQQLARk0YYcy2Wd4Gzkh/JiravucXTGkv1kOmBA2MlUEFyY3ds/z6yponuBdAgi"
    "NR/4Sh4OKAIOqo8lPaLKdVQ0aE6IcD81mENNgm8MKF0UpW8ZAYREgWksTbVfyhT+VICvyyqribzX32tLmbsvZFwSRt14qvH7"
    "acoTJVVc8K9Uz6TiR1xNaO7/q84tTckIOKs/E7P4Jdm0zB7zGqCwO1UXgQAgxIqR/UYI5+jK9ybj9tl4Ncy8OiRjT5EX17jr"
    "VT1H+tQHUErjl3NgMld4lHPUwFaItt0wLtJtAlxZAkVWqk2NJ/27/aI/g096GIZ/bzaDcdNYhgDLjcvxea/A6Fb9s2ESbp6m"
    "hQpZ/yjmiypH/1XLaPvJIH8wC4G9tA1XV4ARIMrz5A8NnLA2+hGrFYmVkCDKyF3ZSmUjGTjcwG5ZWmbg+9WPTwLIF6hkcZIl"
    "Pj8LrKwb/yaq872qLFXSxlqnvWKV8TGqLboAMSq06UhumOh4z9qVAmUEFwCaiVZeW9LEJ3bkvZnt4BhVwFhlffPU9k9CWS+r"
    "Ixcatm9UgIFcARusZB5go6O2N+JZtK8CbnMCjS/c29RV65gsuOiGk1yrEtP89wL0cEBMeQ2bEfuT6txr2SOastt+oYgPWpAd"
    "XrgwYFJcw8KQi2+GzbCrrl2Vfbi1ojGqFs4zmtr7tBdhF/+TcWVSy+m6mQhbbbhqlHzMFK8nCEOsy+vl+x+ALGFayBVapyqu"
    "p5r5dAW7eFWva3/x7VNsqhX32J5aDCCkYbaMVtwaaklp+MA7vD2M4dhQ6P+haStl1kkDu7v3jtdkAvnicynJjtFbITtnYyvk"
    "ZOU5EUYxE/3JIpZqvgAWamXPkNDrmewh+6QDpKlOYoiK02lPVhcIoW0SBiRQqpJ8Z2UR+bp5HgB3tasf/nBCLOc2ZLpap/w3"
    "ArFZ/FUn0DlqC8d7ZrM4Vv+A2tAYlDOies3KjnI1hvQDMnrFbWF2QmutJpE4WRLynTe9kT7JsNs/G28HiE2rmcIVUEkCpTxz"
    "bO1MhFQEE4pQBezUqJoyaurkzt4JVJerymXi0VGprxFvlaaLocw1FOwGFUerqKJ/6URf/7AnSoBkE8ws67ihf2/aBRkDLpvg"
    "Nh7RQqlXZFBhGTlV5eZ1T2ehHsGF0in1cW31xBhLZzm3AU6i3TgQ2xMzvNBN8nMOaYc3MHIlAV5xo0GeoYThL1muNcd6LJrz"
    "b2N6Ep7rmiJA2ob3UyDbTnG3cjgGxIa4pU4aVQ+LACY/bufTcoBriRyzvSjGJcKSc01q6O7G7vNnjyYvwxFb7O4HaELbMjYW"
    "B8DavV6bt8dzqnfIK/qkmS5C5OfGr2K7GqS8iKShDNR06SYY6wwb4HQBIODCN8v08uYdC1lR5Jgz/E23fDsmaIeCMhdEnEJP"
    "dQfaa1U8/BgTSzxAObQqPLpuINMqq5GsZnqsApIAdDB1CS3jY+HH+hw011XUR7GCj/MVNOD/vfgl0SiwCqJ8S3Uh4fCPOsuX"
    "Drb6/c4JBOWUmYSYQrTDgZkiWy0pxwBtdUkkE5V1oM3f6rihtyyY+NbPK1RicP3z+EX2qjnPOzx1fck63eARfIBpTBQMtbpy"
    "e8gH+/K/V8MkIAYJmirc/nWmpy8ba9VvPqgxM8t1JBQiBj+Lr3PGNkHNb8fw19kSdx9zCU2izGxsuIoGM9WaA1gD57ITryiH"
    "x/zwJyLdi5N3JiDylA5WdyULiAo7pfoqItB1IEul8eV37ue3YnuoaXgs0z2eT8XsjzYmjXMF64csdhxYdx5m1MGBnp6A3/n9"
    "eEY8IK3bVCo8e/4syTviGIQTHVpSXAmtGavyk+9j6wR5IqDc3vm8C1OXxYGLFWF8v18g5hXR3/9XUcmanNIIpnlcrVW1F0NG"
    "XOyMlkZAP9GmRHSMzcTgcnJ34K4hY2XNeXEoDXJ77ASi0velfyS5zFIgG6tBa/k9fiABaLptOF16wpOI3yqVlJ1gLSaL1gU8"
    "iwRUDRWvIJvAoLHIrzV/HXxqjqyH8sPRv4l8vl0s8o32Zf1/696Ib4LgLfCA0QrefIcFhvwtHDqgARw4IS4zlD9bxSjWVSiM"
    "HtDeIDWMCC5/QIpHMXJoTzTgFb2qfJC3/g12yVVCllStXn88tI/K9H/cohtlQLW9exbbDGhqsrWng3951RpS7VyDLSw9ej1+"
    "gDI5mItX+8oL93+VcryQTl12/Iax1UKoA31ziGzSjkmAqferVyy1nIHej76VCqRw+VLkX3piM/8AUC8psZMtvIGgmqIKoVUD"
    "uj7rsF7+y7bln8+EmLU7Ljy9xdN7+fgHgfb4C7BtwrIVDDd55Fb7F4cI3J5d6AGmDtdqVc76+o+B/acoeqGCLuUURitDSBIB"
    "yYEhSShw6DC00SiZJUxD8YIeyvp2+J0YfQHpnX3tOWZ7Tb0P0Jhvj0soZuDUrva8g4XSvjSMUR6sfTi6qd622ujqpPY7+2LG"
    "bKLt8BRUekWEcu4P8qMVTAejzgVnID3Ok3tuIg96kCmvacV3rbJNvYS+uEZ+quy7ENHXQMnSMs2S4ildorCR1cDj1vQqpO8E"
    "hRvdl0Eu09GJq/V6CLQ9vTSKH5joaoNqHRhq1hO/lAqFTI19gzYAFVdJDo5/+FHyNsPF/1boJHLOFOqIeoOHCYb5gOsWyDu7"
    "Db2tHb6ast0wu9sqEGH0m/s/SLneNPF5hwGQt58zFhvXEL4br3WZzviEoiAe2dV1qpPjkzYCpuWHaJ8Q2JKrapZxiszdE2RK"
    "aWW2dSlJ9stUG05nx2f574dt3yjPkWQy0BaDh4Ok2x7zQSQdXW55pd+oil+SQm8nh+Z6ag++/bVDUkRiShTJ2R+He9Otfz6c"
    "drjyl1neCymIYnuxjfYfTUT5rJT6kKlfTBPSv8Y7+T1Iqtgb+W25oorTp7KzsmoTrC1sJR4iQU7vvuKYoywiIR7rs292SU3P"
    "j1wnn/07G17SIxhYRBmyVSNP2RMkgyug5oK9xwV0V2eP/03SMrN+ysSJGAdRBks/jWsCBhrvH21QdeA77ch6hJGPZ0dBmGe8"
    "DH6UXPKTas3dEFZTMOVi61HVraaxZINBkfoZXT/FCotdxqzOcUa3cUex1A6idjVwF9gFjq7UAzySDhePNGFwX9i9y55YWDKN"
    "ppRJPrP0My1MQgeLmw1lGZLvj5h+hNVKnC3DAAALfzo+yN5zzPRmof6Iz353hoxmmpTviLKS4xbF6604h6GDIdOkhzL5cF9i"
    "ZQ2htkoYtoWay47caU6ImiecOLxflwIdSNxWtPuhU1eRFoESzz3QA5xHdyA4Vy8iHvwqxn6LahWMtRKkPSXQ/D9fnXsVIGlE"
    "nFqnyVTND6fg6BRDWkdl5cssu6Y/2hSi99Xu4MbNeoWcgC5y2aFIHELg64dyBxUvGtFRfj9Kxdowym1amwXrAp0I47FWfOqr"
    "r1NdgTJjfKVoAmMYEutOvvXq4J2kRvnGnWWKLMyGNxWpAWFw0Pnm9xkYijlk+/nNiq5wtf3b2Eyd1KZIkLj/aEwYGz1cRPcs"
    "qWZKkJAM/dIGILAu406nMZ5hOYDzf0YO6nEbTZr9y6T48bFRS812n8fAsICBmIZQnorHXJlRd0XO8bstJbzORs2VJQwP3Nmd"
    "mgugeAiMEO2gaXvvoBypbDResHAlOCqv1KAtfYijtzKiSZz3+RlIZqEO0WGSpC4oKdXmaQAne9GxufNWhTWlbQI4hqWZcWmo"
    "oeS/eCEQOve+QAZ1Ju4ESrDUgdZos+UVCvo85Nu9BlGh96Kt9z+WPy0ndJpMnWQIOc6ZM73pgNmRk10QW1oBOaL0RGSpwljc"
    "JXmuAvTzd7TALic+Vjh6ZeVsZ/Zr2EQoo/FS71WC0hfWko88rfbOD4HnSgOfBoogrKFe1W8JAB2lLaIpZ3ZmNsHtzMMZHuka"
    "XQiv+PVKtbTMgZbroN3chqX2ozrhSwXpfzzV8ysqUa+vcWNRshyMI+TpmCTk3HcHpyn1ZRQcWyGyH98PejVwx6RSa2cC2JRx"
    "Twky2bNYS/Kn1nsTKtaxoHwWhML/biRqH+0ARFllO/ynUh1PjZtPoqkc0mgF2fSpIAEEBWrXXcPtRDGrrAKIFlzEiQgFPLNE"
    "qYT+KMZHh0CPezvfIlclXdmkRaYZMYU/pzE5mCcrYpapl+qo1V9LGWdLdqamjUJ5AmrMUkdw6fuAuS8f5UbP9qnPfFvzKfSk"
    "iegqXJZg/4bIDsdYA0Dz3w9YGcwr7jP3qvVlxYeI08zjM12zyB/L2RR8zUP+s8AkVoDR1hKH7XGr+vKKD4x2zN6h648+rfxT"
    "8US/1CdFO34vDy0CEL2TjK2m2n6lzPn6DifcSIwfEzNzB6PdCWT0TeT7hOuPUfJWrcKM5AnXDDfWGcDqb/EriJ6EQj9EAol+"
    "jPXQ5c/NLqStx2mmUtH7L7NH6LvNFoz/5t9RmehAvEIfijY3GLeWJ6+1CV7ejNDMKNl6iHWH9tRxKgq0WHIxpRKP4sJkVTza"
    "sL8WbHxKdW5Qwn6qOO90fWLRQI/MuuoSuvGDNzi/m0GxIGK3t/+xNNlZEgS/1qR182JkT/lsmXcmhGTcRCRnRrSvLhCp4B0B"
    "0431rE3k8XjK/k+oHGeE34F3e3noo1lttSdMroAB11fgxICOgG/bP5cjUVWYNzDPF/fKoL7wYMO1PmT+h41+a98maa+VaohW"
    "+anhwTGoUgjUuymQ8vahJrXyyBw6UaNO8BQYsJazLbZSwBAaiSMyyxZ153oMXr0htgRPqyxe6PASUq07DnwhjghtAaQKH7X2"
    "kHoeXInl+562hXe5+S8PC4J8qd7VNiaeeuB2w8Xm3GgBcaQfjqlDv7pPrYLUoQuax9yyjNNyxDQQAzKnCvvW65+X/pmZWK+H"
    "upx4/tYv8q0QVM5AWDQC+2oeU7gGjg215aWRc6Stsf+7aN9THR06fbAeY+/VqsK6uQI7ud9a6A4mH3Hvox3zObwei31ifwUr"
    "68QhYRPFXymF1+/7OaU3cElUfzRViN4BvfWTkYdkhITrm7OfV6JXDnzbvGyM3eed49Wzm8QHAYq+2Xg+2IHiAwnDDkkR7QQJ"
    "eACi/A1VQ5IeLKRyGQZN2L76VSikjYJiV5rlTUm3jdX2oO4WAA9yHQfddy8OM8UrwIK4529jtCT56bQ4LTPSydJyrj9VcEk4"
    "Znr58dbZNVDAy8xRE5L1x7xxT1897g6mp1aDWDTNm9ZXNXNOb/Pc8MNe6Uy/V1cG+RZGWOPoJYW4McoAceuCafCsPCud8nH0"
    "w9Y1Bp0+I3TiEaO1oqYHBaRmvj0eTjjZ2xqsPOPxqmzEaX2jK+k28Lfj9eZGnay3s7KAChP5yohiazSmLOm4Z8UqbjWT7qPp"
    "+OCWIJyahVHtaS4CJVEMYuC/HDGG+Z6gxiBOh9SPCl2PSS/osMhg1Qrq8fphRXQPIbgWhjTh9lLGZTHKB/WGS7JorrvlnQa4"
    "qptq5PxCIItFo/3WLc6Hg8as+hcMBNzpsoCagCSKZTljzmx0JkJHmd1w+yv8OTVBxveOIV0g+hUzW+tlH3rFgQxCYF4LrCG1"
    "gBA+EkThCJ7HNKZhIbTzDgMP/4jde35aGYT1cvwVbOkSrCQVffWOusoSj4vzgXT6Prqlu2eV1ElSqdvzwrvWLFyy4cGJUR15"
    "yspro6pqcoDD5Nb1inr3KStdq8k6mfQ2BAsUwH9XDEPK5yrp8/0PwK8MRD+Ys13rBV8MdINGqM0yhWA7KkD4c8tCdubSSgtU"
    "dz2I3QiO1G1aQoA3iLvt9zh+nKIEMiufzO3mS3ZS+Ku14ZbPpZMsHoGBq2s22+Q7+i7B4wiiYe/NOT9NWPqcRHfwqXc6aU6d"
    "NOqis6XzgY6PrE/D+atIzM3frvxG77Fl1AgjvdiUTXf1S8aM0ejWQYoUvhj+5OxWzl6NVwTAtNiVKWF7pe0RVBkthGimOG4t"
    "zlRhWBDOj9nPo6KM/WlRukNrChOmqpaaC1YlFyIKjfeGE5Y8FzfNadBE537b97hCLz+M6PnGSdSqUmLWsFiEIYrwA2rlEwMm"
    "0Jtij5JkGQeP/mcaaDllzYjcMF53Mq1Si5lYmkDFJ2jRZjjF1CUqJDiFfiJRPAk9TGPYCHPENV4VAP+thpl9KNGWHsFjFvbh"
    "+49DHMDCRxRR51uHVq3/pE0ucwY3YqBq0vQmpz6bghc2T7J5bOFDeS7nfvT65+njiEEw+IQbBHvTOgR/1PO9xNyqS8PJyE9J"
    "Y1CwuHOyJsd1Cf4mP8BwS9XeX+4wxwChQZwFD+KnE1ETcuKTKK9U5NNYYvtR3bix1zgnCXdfnPBRVSeApKZR1ENjdCo6as78"
    "8yQSof5kw0/Xs59Fg/zHz1g7wrjAVKVujYiiN2SuvCRQUOkHhMbMQtf0U0Bl2k/DG6T6ib1YMuEU3T6E928mrh1HRktpuRVe"
    "2KE730eNipqJrhGbZ3yRaYEAMWwe9640eC0JByWbj0LY0M+YHjN85Z1jqNfBlJnvPWAOPPsdBOYPPRLQJtL82dk/mTG2o/e3"
    "HDRHX/lWD3oFwDc12IvoG5v/Td8xejXJ3CIB+69O62oLx0QdFJQDOAOGmyZi0IakOlj3h7zsHr7c2nGU/8xgOsuFRwj4iIJ4"
    "ocZHsIPoFgpmE0uhATBBxt1gCZMiEwTYbkLJs93bxn+BcePv0tKfdeQvmGLWVPY53lRNavh43yxJ3JhVvgRN3qNWegvuLeDQ"
    "S/Pu5Io4/QjesTmOgWmm+qILkvjdBd84a/TE0JdtTraWRr/6VeGcyt7ylGBIiyaRST1CwryWun/eke5KZRfAsLOb6nna4RU9"
    "4AeXV6UKkkY1evZp8wq2VO3rH91ammprrDkIQ4FUtOvhJTzTBc3JFfD7kfW/wak6eSOlZTXbZYJDHfR8qVEhMeKbhrTbydjD"
    "hjQpvfjhebt4xxTOCVseSN9kmfc+Gkll5cmFIGNk8msKLVexDT79Ac1lGle0wx/vScYiMCVZzF3m7VYzKet9iEr6dyJubTBR"
    "dr+/v6SD/17U1zUcjgU3Qeb19dClY0cy2uQaB0vAU3amYQK+V9PWixClJkGzy6te5xbD6Ql/sBkdB/6Dt+++65G3L4sLtCId"
    "ZLvqbiVhujDoiJitLJJC3MyMdjN3SQy2vFUrv7EHkifcLfFKu/3UtOiwomcikgYK+cQtpWDMBoxX+5F1/GgZzUyC/VMBtUyQ"
    "6OAxwEvdN+sqoX2xWm+jl5/MQ3QIZHIt1eQje9H+PaXqE7V6zXG2Y98FTmB6SrsjMjx0tzpOAg54ViZkioYfcursP9Q8j04j"
    "01zUyjzKkYYHPqbpZKkc6rOymkxkYXHO63YCGg6zOju9tPJHxl9T4qZZg/vN8uKSBmL0Ax4olUbrzb6n7D1R0OWc+eElyYR6"
    "or9fdDOYNADZX5VJ67EWi+zaIAYICpSFtRrfTC610ryuNjscVoQikpBQ89CgBeV17dIRTJ5w2O8BmUPrTwYAMV353mEkdyWl"
    "jwsm3c2Crc7t5slequh3Nm2Ada0WKvqnqFmw7ccIRnVSq9M6phNEcO6MwHtZ7AFtgZ8aTZ+Z+e/vOccAiGA+le4cksCv63Qi"
    "7wzeX117DCF5WyyJertEWJ4s35nSoYGsO+R2o25sSn7vnbtvbIfR0JbjxnyJsVXURjXSR/r6qwIb4I2j6GmFVPCD8BHZOw43"
    "/rd09rarePFkASLY/IRPjJCRumqwmqz68NwqG4QIRVuhgbJkacmfIQdYaSWal4l8gV528WhDe/jw/M5M4x9OCYVhSVlDSvEp"
    "XwX+QT74m0p+n98juY3Nf/EkByYQMyEi3vXYiXEWO8Pq3CipGSegy5lX7QJNxwQn8eZOX78QF6ojik7R5eIfOXDbGHWBjcy/"
    "oohkT7Lj9BHzShdp1iGyGluL9QbmqHMKEA0S9A9CZ3S8ym/K6qwMivQiCno19vdTdQKFZECXWgQ0SVroK0dWQnTaLUF5vqqS"
    "9xnNUwNTCXDpqbaDc6GSqVK3ofp3lBaHXak6EPMoAGD3HI+25xnIG5J8cmNNFp6DUwiAV7Qmef3iSSLPLOwGdvgMNmRG37kO"
    "i7r2SUbcdZFaH7tJTkrNPfH+sd3uf88t+JD94V/Eafb1tWz/3CEMzkLyd6yKrnhN3OL+98V39rz49CMrYwSxoKtoUGjj0IY6"
    "MEKbDocfmBWh2XBSFwwL8fj3MnQWjMOip8FnNv3Z341xbw4XQ+yFSLtJpESQ+n7z+czpjkTkNKdvDZMXsXJCek4T5EaWXP7c"
    "h3rqsN4YE8z6ecnhdMQ5jI5W+U9TrwlmffOvZ8NmtMCebx+2kepZlfq0IHU1I8zuVDF7Va8nDOsQUcGMxKStORIg3w0TcH1F"
    "+1aTaCXbBvDbW8nRPTBG2+1rNMEhd64sb+R53/6utn77nIRUz3wrj4LLUp6e1c6D+Qmld4tOd1A6vHe5RicWJ/xZ0NbTmtgM"
    "fC1r5nKxzLhpllporB9Nqwg6/LaJaSoU/Skwe+4fajB6mA+ZCAi1pPYJUMc/lTE01Xh18hYlElP98Fu8Q7Y5Xj6aerq1wbSE"
    "u+ti8wI2nlnjEi4sW6/uxv36Gp78qfIAAN3yT9C+0WzxU0JOzot1RnjqJrAMCZql"
)
if len(_OWNED_SOURCE_STYLE_PAIR_BLOB) != 294 * hashlib.sha256().digest_size:
    raise RuntimeError("source/style ownership table has the wrong byte length")

OWNED_SOURCE_STYLE_PAIR_SHA256 = frozenset(
    _OWNED_SOURCE_STYLE_PAIR_BLOB[offset : offset + hashlib.sha256().digest_size]
    for offset in range(
        0,
        len(_OWNED_SOURCE_STYLE_PAIR_BLOB),
        hashlib.sha256().digest_size,
    )
)
if len(OWNED_SOURCE_STYLE_PAIR_SHA256) != 294:
    raise RuntimeError("source/style ownership table contains duplicate identities")


def source_style_identity_sha256(
    source_layer: str,
    style_layer_id: str,
) -> bytes:
    if type(source_layer) is not str or not source_layer:
        raise SemanticPolicyError("source layer must be a nonempty canonical string")
    if type(style_layer_id) is not str or not style_layer_id:
        raise SemanticPolicyError("style layer ID must be a nonempty canonical string")
    source_bytes = source_layer.encode("utf-8")
    style_bytes = style_layer_id.encode("utf-8")
    return hashlib.sha256(
        _STYLE_OWNERSHIP_DOMAIN
        + len(source_bytes).to_bytes(4, "big")
        + source_bytes
        + len(style_bytes).to_bytes(4, "big")
        + style_bytes
    ).digest()


def source_style_identity_is_owned(
    source_layer: str,
    style_layer_id: str,
) -> bool:
    return (
        source_style_identity_sha256(source_layer, style_layer_id)
        in OWNED_SOURCE_STYLE_PAIR_SHA256
    )


def _require_source_style_identity_owned(
    source_layer: str,
    style_layer_id: str,
) -> None:
    if not source_style_identity_is_owned(source_layer, style_layer_id):
        raise SemanticPolicyError(
            "style identity is not owned by source layer"
        )


def classification_for_style_rule(
    source_layer: str,
    style_layer_id: str,
    layer_type: str,
    selector_properties: Mapping[str, object],
) -> SemanticClassification:
    source_policy = SOURCE_LAYER_POLICIES.get(source_layer)
    if source_policy is None or layer_type not in source_policy.accepted_types:
        raise SemanticPolicyError("style rule is not owned by the source-layer policy")
    _require_source_style_identity_owned(source_layer, style_layer_id)

    one_way = classify_one_way_style(
        source_layer,
        style_layer_id,
        selector_properties,
        require_direction=False,
    )
    if one_way is not None:
        return one_way
    disputed_label = classify_disputed_label_style(source_layer, style_layer_id)
    if disputed_label is not None:
        return disputed_label

    land = classify_land(source_layer, style_layer_id, selector_properties)
    if land is not None:
        return land
    if source_layer == "Boundary line":
        properties = dict(selector_properties)
        symbol = properties.get("_symbol")
        if type(symbol) is int and 6 <= symbol <= 11:
            properties["DisputeID"] = 1
        boundary = classify_boundary(source_layer, properties)
        if boundary is not None:
            return boundary
        raise SemanticPolicyError("boundary selector has no exact classification")
    if source_layer == "Watershed boundary":
        result = classify_boundary(source_layer, selector_properties)
        assert result is not None
        return result
    if source_layer == "Coastline":
        result = classify_coastline(source_layer, selector_properties)
        assert result is not None
        return result
    if source_layer == "Water line":
        result = classify_water_line(source_layer, selector_properties)
        if result is not None:
            return result
        raise SemanticPolicyError("water-line selector has no exact classification")
    if source_layer in _WATER_AREA_KIND_BY_SOURCE:
        result = classify_water_area(source_layer, selector_properties)
        if result is not None:
            return result
        raise SemanticPolicyError("water-area selector has no exact classification")
    if source_layer in {"Road", "Road tunnel"}:
        result = classify_road(source_layer, selector_properties)
        if result is not None:
            return result
        raise SemanticPolicyError("road selector has no exact classification")
    if source_layer == "Transportation place":
        result = classify_transportation_place(style_layer_id, selector_properties)
        if result is not None:
            return result
        raise SemanticPolicyError(
            "transportation-place style has no exact classification"
        )
    if source_layer == "Disputed label point":
        raise SemanticPolicyError("disputed style has no exact classification")

    feature_kind = _FEATURE_KIND_BY_STYLE_TYPE[layer_type]
    if feature_kind is FeatureKind.LABEL:
        subtype = source_policy.default_label_subtype
        if source_layer in {"City large scale", "City small scale"}:
            subtype = (
                SemanticSubtype.CAPITAL_MAJOR_CITY.value
                if style_layer_id in _CITY_CAPITAL_STYLE_IDS
                else SemanticSubtype.CITY_TOWN.value
            )
        subtype = _WATER_LABEL_SUBTYPE.get(style_layer_id, subtype)
        subtype = _ROAD_LABEL_SUBTYPE.get(style_layer_id, subtype)
        flags = transport_style_flags(style_layer_id)
        token = _LABEL_RENDER_TOKEN_BY_GROUP[source_policy.layer_group].value
        kind = _LABEL_KIND_BY_GROUP[source_policy.layer_group]
        if isinstance(subtype, IntEnum):
            subtype = int(subtype)
        if type(subtype) is not int:
            raise SemanticPolicyError("label rule lacks an explicit semantic subtype")
        return SemanticClassification(
            source_policy.layer_group,
            FeatureKind.LABEL,
            subtype,
            kind,
            token,
            tunnel=source_layer == "Road tunnel/label",
            shield=flags.shield,
            one_way=flags.one_way,
        )

    subtype = source_policy.default_line_subtype
    if type(subtype) is not int:
        raise SemanticPolicyError("line rule lacks an explicit semantic subtype")
    token = _LINE_RENDER_TOKEN_BY_GROUP[source_policy.layer_group].value
    return SemanticClassification(
        source_policy.layer_group,
        FeatureKind.LINE,
        subtype,
        _LINE_KIND_BY_GROUP[source_policy.layer_group],
        token,
    )


def classification_matches(
    expected: SemanticClassification,
    *,
    source_layer: str,
    style_layer_id: str,
    properties: Mapping[str, object],
) -> bool:
    if not isinstance(expected, SemanticClassification):
        raise SemanticPolicyError("runtime classification requires an expected policy")
    if not source_style_identity_is_owned(source_layer, style_layer_id):
        return False
    if style_layer_id in ONE_WAY_STYLE_IDS:
        try:
            actual = classify_one_way_style(
                source_layer, style_layer_id, properties
            )
        except SemanticPolicyError:
            return False
    elif source_layer == "Boundary line" or source_layer == "Watershed boundary":
        actual = classify_boundary(source_layer, properties)
    elif source_layer == "Coastline":
        actual = classify_coastline(source_layer, properties)
    elif source_layer == "Water line":
        actual = classify_water_line(source_layer, properties)
    elif source_layer in _WATER_AREA_KIND_BY_SOURCE:
        actual = classify_water_area(source_layer, properties)
    elif source_layer in {"Road", "Road tunnel"}:
        actual = classify_road(source_layer, properties)
    elif style_layer_id in _DISPUTED_LABEL_STYLE_CLASSIFICATIONS:
        try:
            actual = classify_disputed_label_style(source_layer, style_layer_id)
        except SemanticPolicyError:
            return False
    else:
        source_policy = SOURCE_LAYER_POLICIES.get(source_layer)
        if source_policy is None or len(source_policy.accepted_types) != 1:
            return False
        try:
            actual = classification_for_style_rule(
                source_layer,
                style_layer_id,
                source_policy.accepted_types[0],
                properties,
            )
        except SemanticPolicyError:
            return False
    return actual == expected


def _owned_source_layer_for_style_id(style_layer_id: str) -> str:
    matches = tuple(
        source_layer
        for source_layer in SOURCE_LAYER_POLICIES
        if style_layer_id == source_layer
        or style_layer_id.startswith(f"{source_layer}/")
    )
    if not matches:
        raise SemanticPolicyError(
            "style identity has no source-layer ownership"
        )
    return max(matches, key=len)


def _classification_behavior_outcome(
    classification: SemanticClassification,
) -> dict[str, object]:
    return {
        "kind": "classification",
        "value": semantic_classification_document(classification),
    }


def _exact_integer_public_vectors(
    *,
    case_prefix: str,
    source_layer: str,
    base_properties: Mapping[str, object],
    unknown_outcome: Mapping[str, object],
    style_layer_id: str | None = None,
    layer_type: str | None = None,
) -> list[dict[str, object]]:
    vectors: list[dict[str, object]] = []
    for input_kind, value in (
        ("boolean", True),
        ("float", 0.0),
        ("string", "0"),
    ):
        properties = dict(base_properties)
        properties["_symbol"] = value
        vector: dict[str, object] = {
            "case": f"{case_prefix}-{input_kind}-symbol",
            "inputKind": input_kind,
            "outcome": {
                "kind": "error",
                "message": "_symbol must be an exact integer",
            },
            "properties": properties,
            "sourceLayer": source_layer,
        }
        if style_layer_id is not None:
            vector["styleLayerId"] = style_layer_id
        if layer_type is not None:
            vector["layerType"] = layer_type
        vectors.append(vector)
    missing: dict[str, object] = {
        "case": f"{case_prefix}-missing-symbol",
        "inputKind": "missing",
        "outcome": {
            "kind": "error",
            "message": "missing required _symbol integer",
        },
        "properties": dict(base_properties),
        "sourceLayer": source_layer,
    }
    unknown_properties = dict(base_properties)
    unknown_properties["_symbol"] = 99
    unknown: dict[str, object] = {
        "case": f"{case_prefix}-unknown-symbol",
        "inputKind": "unknown",
        "outcome": dict(unknown_outcome),
        "properties": unknown_properties,
        "sourceLayer": source_layer,
    }
    for vector in (missing, unknown):
        if style_layer_id is not None:
            vector["styleLayerId"] = style_layer_id
        if layer_type is not None:
            vector["layerType"] = layer_type
    vectors.extend((missing, unknown))
    return vectors


def _accepted_classifier_domains_document() -> dict[str, object]:
    return {
        "classification_for_style_rule": {
            "acceptedLayerTypesBySource": {
                source_layer: list(source_policy.accepted_types)
                for source_layer, source_policy in sorted(
                    SOURCE_LAYER_POLICIES.items()
                )
            },
            "ownedSourceStylePairSha256": sorted(
                digest.hex() for digest in OWNED_SOURCE_STYLE_PAIR_SHA256
            )
        },
        "classify_boundary": {
            "Boundary line": list(_BOUNDARY_SYMBOLS),
            "Watershed boundary": ["fixed"],
        },
        "classify_coastline": ["Coastline"],
        "classify_disputed_label_style": {
            style_layer_id: "Disputed label point"
            for style_layer_id in sorted(_DISPUTED_LABEL_STYLE_CLASSIFICATIONS)
        },
        "classify_land": {
            "ambiguousSources": sorted(_AMBIGUOUS_LAND_SOURCES),
            "sourceExplicitStyleIds": sorted(_SOURCE_EXPLICIT_LAND_STYLES),
        },
        "classify_one_way_style": {
            style_layer_id: (
                "Road tunnel/label" if item[1] else "Road/label"
            )
            for style_layer_id, item in sorted(ONE_WAY_STYLE_CLASSIFICATIONS.items())
        },
        "classify_road": {
            source_layer: sorted(_ROAD_SUBTYPE)
            for source_layer in ("Road", "Road tunnel")
        },
        "classify_transportation_place": sorted(_ALL_TRANSPORT_PLACE_STYLE_IDS),
        "classify_water_area": {
            source_layer: [
                "missing" if value is None else value
                for value in sorted(
                    table,
                    key=lambda item: -1 if item is None else item,
                )
            ]
            for source_layer, table in sorted(_WATER_AREA_KIND_BY_SOURCE.items())
        },
        "classify_water_line": {"Water line": sorted(_WATER_LINE_KIND)},
    }


def _public_classifier_behavior_vectors_document(
) -> dict[str, list[dict[str, object]]]:
    one_way_style_id = sorted(ONE_WAY_STYLE_CLASSIFICATIONS)[0]
    one_way_subtype, one_way_tunnel = ONE_WAY_STYLE_CLASSIFICATIONS[
        one_way_style_id
    ]
    one_way_source = "Road tunnel/label" if one_way_tunnel else "Road/label"
    one_way_outcome = _classification_behavior_outcome(
        SemanticClassification(
            LayerGroup.TRANSPORTATION,
            FeatureKind.LINE,
            one_way_subtype.value,
            "one_way_arrow",
            RenderStyleToken.TRANSPORT_LINE_V1.value,
            tunnel=one_way_tunnel,
            one_way=True,
        )
    )
    disputed_style_id = sorted(_DISPUTED_LABEL_STYLE_CLASSIFICATIONS)[0]
    disputed_group, disputed_subtype, disputed_kind, disputed_token = (
        _DISPUTED_LABEL_STYLE_CLASSIFICATIONS[disputed_style_id]
    )
    disputed_outcome = _classification_behavior_outcome(
        SemanticClassification(
            disputed_group,
            FeatureKind.LABEL,
            disputed_subtype.value,
            disputed_kind,
            disputed_token.value,
            disputed=True,
        )
    )
    transport_style_id = sorted(_ALL_TRANSPORT_PLACE_STYLE_IDS)[0]
    transport_subtype = _TRANSPORT_PLACE_SUBTYPE.get(
        transport_style_id, TransportSubtype.TRANSPORTATION_PLACE
    )
    transport_outcome = _classification_behavior_outcome(
        SemanticClassification(
            LayerGroup.TRANSPORTATION,
            FeatureKind.LABEL,
            transport_subtype.value,
            transport_subtype.name.lower(),
            RenderStyleToken.TRANSPORT_LABEL_V1.value,
        )
    )
    coastline_outcome = _classification_behavior_outcome(
        SemanticClassification(
            LayerGroup.WATER,
            FeatureKind.LINE,
            SemanticSubtype.COASTLINE.value,
            "coastline",
            RenderStyleToken.COASTLINE_V1.value,
            coastline=True,
        )
    )
    land_outcome = _classification_behavior_outcome(
        SemanticClassification(
            LayerGroup.CONTEXT,
            FeatureKind.LABEL,
            SemanticSubtype.LOCAL_PLACE.value,
            "ambiguous_land_context",
            RenderStyleToken.NEUTRAL_CONTEXT_LABEL_V1.value,
            land_evidence=LandEvidence.AMBIGUOUS,
            protected_status=ProtectedStatus.AMBIGUOUS,
        )
    )
    none = {"kind": "none"}
    vectors: dict[str, list[dict[str, object]]] = {
        "classify_boundary": _exact_integer_public_vectors(
            case_prefix="boundary",
            source_layer="Boundary line",
            base_properties={"Viz": 0},
            unknown_outcome=none,
        ),
        "classify_water_line": _exact_integer_public_vectors(
            case_prefix="water-line",
            source_layer="Water line",
            base_properties={},
            unknown_outcome=none,
        ),
        "classify_water_area": _exact_integer_public_vectors(
            case_prefix="water-area",
            source_layer="Water area",
            base_properties={},
            unknown_outcome=none,
        ),
        "classify_road": _exact_integer_public_vectors(
            case_prefix="road",
            source_layer="Road",
            base_properties={"Viz": 0},
            unknown_outcome=none,
        ),
        "classification_for_style_rule": _exact_integer_public_vectors(
            case_prefix="style-rule-water-line",
            source_layer="Water line",
            style_layer_id="Water line/Stream or river",
            layer_type="line",
            base_properties={},
            unknown_outcome={
                "kind": "error",
                "message": "water-line selector has no exact classification",
            },
        ),
        "classify_coastline": [
            {
                "case": "coastline-boolean-source",
                "inputKind": "boolean",
                "outcome": none,
                "properties": {},
                "sourceLayer": True,
            },
            {
                "case": "coastline-float-source",
                "inputKind": "float",
                "outcome": none,
                "properties": {},
                "sourceLayer": 1.0,
            },
            {
                "case": "coastline-exact-string-source",
                "inputKind": "string",
                "outcome": coastline_outcome,
                "properties": {},
                "sourceLayer": "Coastline",
            },
            {
                "case": "coastline-missing-source",
                "inputKind": "missing",
                "outcome": none,
                "properties": {},
                "sourceLayer": "",
            },
            {
                "case": "coastline-unknown-source",
                "inputKind": "unknown",
                "outcome": none,
                "properties": {},
                "sourceLayer": "Unknown",
            },
        ],
        "classify_one_way_style": [
            {
                "case": f"one-way-{input_kind}-direction",
                "inputKind": input_kind,
                "outcome": outcome,
                "properties": properties,
                "requireDirection": True,
                "sourceLayer": one_way_source,
                "styleLayerId": one_way_style_id,
            }
            for input_kind, properties, outcome in (
                ("boolean", {"DirTravel": True}, none),
                ("float", {"DirTravel": 1.0}, none),
                ("string", {"DirTravel": "F"}, one_way_outcome),
                ("missing", {}, none),
                ("unknown", {"DirTravel": "?"}, none),
            )
        ],
        "classify_disputed_label_style": [
            {
                "case": "disputed-boolean-source",
                "inputKind": "boolean",
                "outcome": {
                    "kind": "error",
                    "message": "disputed style identity violates source-layer ownership",
                },
                "sourceLayer": True,
                "styleLayerId": disputed_style_id,
            },
            {
                "case": "disputed-float-source",
                "inputKind": "float",
                "outcome": {
                    "kind": "error",
                    "message": "disputed style identity violates source-layer ownership",
                },
                "sourceLayer": 1.0,
                "styleLayerId": disputed_style_id,
            },
            {
                "case": "disputed-exact-string-style",
                "inputKind": "string",
                "outcome": disputed_outcome,
                "sourceLayer": "Disputed label point",
                "styleLayerId": disputed_style_id,
            },
            {
                "case": "disputed-missing-style",
                "inputKind": "missing",
                "outcome": none,
                "sourceLayer": "Disputed label point",
                "styleLayerId": "",
            },
            {
                "case": "disputed-unknown-style",
                "inputKind": "unknown",
                "outcome": none,
                "sourceLayer": "Disputed label point",
                "styleLayerId": "Unknown",
            },
        ],
        "classify_transportation_place": [
            {
                "case": f"transport-place-{input_kind}-style",
                "inputKind": input_kind,
                "outcome": outcome,
                "properties": {},
                "styleLayerId": style_layer_id,
            }
            for input_kind, style_layer_id, outcome in (
                ("boolean", True, none),
                ("float", 1.0, none),
                ("string", transport_style_id, transport_outcome),
                ("missing", "", none),
                ("unknown", "Unknown", none),
            )
        ],
        "classify_land": [
            {
                "case": f"land-{input_kind}-style",
                "inputKind": input_kind,
                "outcome": outcome,
                "properties": {},
                "sourceLayer": "Park or farming/label",
                "styleLayerId": style_layer_id,
            }
            for input_kind, style_layer_id, outcome in (
                (
                    "boolean",
                    True,
                    {
                        "kind": "error",
                        "message": "style layer ID must be a nonempty canonical string",
                    },
                ),
                (
                    "float",
                    1.0,
                    {
                        "kind": "error",
                        "message": "style layer ID must be a nonempty canonical string",
                    },
                ),
                ("string", "Park or farming/label/Default", land_outcome),
                (
                    "missing",
                    "",
                    {
                        "kind": "error",
                        "message": "style layer ID must be a nonempty canonical string",
                    },
                ),
                (
                    "unknown",
                    "Park or farming/label/Fake",
                    {
                        "kind": "error",
                        "message": "style identity is not owned by source layer",
                    },
                ),
            )
        ],
    }
    vectors["classification_for_style_rule"].append(
        {
            "case": "style-rule-known-source-unowned-style",
            "inputKind": "unknown",
            "layerType": "line",
            "outcome": {
                "kind": "error",
                "message": "style identity is not owned by source layer",
            },
            "properties": {"_symbol": 0},
            "sourceLayer": "Water line",
            "styleLayerId": "Water line/Fake",
        }
    )
    vectors["classification_for_style_rule"].append(
        {
            "case": "style-rule-owned-pair-wrong-layer-type",
            "inputKind": "unknown",
            "layerType": "symbol",
            "outcome": {
                "kind": "error",
                "message": "style rule is not owned by the source-layer policy",
            },
            "properties": {"_symbol": 0},
            "sourceLayer": "Water line",
            "styleLayerId": "Water line/Stream or river",
        }
    )
    for classifier_name, source_layer, properties in (
        (
            "classify_boundary",
            "Water line",
            {"Viz": 0, "_symbol": 0},
        ),
        ("classify_coastline", "Boundary line", {}),
        ("classify_water_line", "Boundary line", {"_symbol": 0}),
        ("classify_water_area", "Road", {"_symbol": 7}),
        ("classify_road", "Water line", {"Viz": 0, "_symbol": 0}),
    ):
        vectors[classifier_name].append(
            {
                "case": f"{classifier_name}-wrong-known-source",
                "inputKind": "unknown",
                "outcome": none,
                "properties": properties,
                "sourceLayer": source_layer,
            }
        )
    vectors["classify_one_way_style"].append(
        {
            "case": "one-way-wrong-known-source",
            "inputKind": "unknown",
            "outcome": {
                "kind": "error",
                "message": "one-way style identity violates source-layer ownership",
            },
            "properties": {"DirTravel": "F"},
            "requireDirection": True,
            "sourceLayer": "Water line/label",
            "styleLayerId": one_way_style_id,
        }
    )
    vectors["classify_disputed_label_style"].append(
        {
            "case": "disputed-wrong-known-source",
            "inputKind": "unknown",
            "outcome": {
                "kind": "error",
                "message": "disputed style identity violates source-layer ownership",
            },
            "sourceLayer": "City small scale",
            "styleLayerId": disputed_style_id,
        }
    )
    vectors["classify_land"].append(
        {
            "case": "land-wrong-known-source",
            "inputKind": "unknown",
            "outcome": none,
            "properties": {},
            "sourceLayer": "City small scale",
            "styleLayerId": "Park or farming/label/Default",
        }
    )
    return {key: vectors[key] for key in sorted(vectors)}


def _classifier_behavior_vectors_document() -> dict[str, object]:
    exact_integer_vectors: list[dict[str, object]] = []
    for name, required in (
        ("_symbol", True),
        ("DisputeID", False),
        ("Viz", False),
    ):
        exact_integer_vectors.extend(
            (
                {
                    "case": f"{name}-exact-integer",
                    "inputKind": "integer",
                    "name": name,
                    "properties": {name: 7},
                    "required": required,
                    "outcome": {"kind": "value", "value": 7},
                },
                {
                    "case": f"{name}-boolean-is-not-an-integer",
                    "inputKind": "boolean",
                    "name": name,
                    "properties": {name: True},
                    "required": required,
                    "outcome": {
                        "kind": "error",
                        "message": f"{name} must be an exact integer",
                    },
                },
                {
                    "case": f"{name}-float-is-not-an-integer",
                    "inputKind": "float",
                    "name": name,
                    "properties": {name: 7.0},
                    "required": required,
                    "outcome": {
                        "kind": "error",
                        "message": f"{name} must be an exact integer",
                    },
                },
                {
                    "case": f"{name}-string-is-not-an-integer",
                    "inputKind": "string",
                    "name": name,
                    "properties": {name: "7"},
                    "required": required,
                    "outcome": {
                        "kind": "error",
                        "message": f"{name} must be an exact integer",
                    },
                },
                {
                    "case": f"{name}-is-missing",
                    "inputKind": "missing",
                    "name": name,
                    "properties": {},
                    "required": required,
                    "outcome": (
                        {
                            "kind": "error",
                            "message": f"missing required {name} integer",
                        }
                        if required
                        else {"kind": "value", "value": None}
                    ),
                },
            )
        )
    return {
        "acceptedDomains": _accepted_classifier_domains_document(),
        "publicClassifiers": _public_classifier_behavior_vectors_document(),
        "schema": "flight-alert-exp8-classifier-behavior-v2",
        "exactInteger": exact_integer_vectors,
        "unknownSelectors": [
            {
                "case": "unknown-boundary-symbol",
                "classifier": "boundary",
                "inputKind": "unknown",
                "properties": {"Viz": 0, "_symbol": 99},
                "sourceLayer": "Boundary line",
                "outcome": {"kind": "none"},
            },
            {
                "case": "unknown-water-line-symbol",
                "classifier": "water-line",
                "inputKind": "unknown",
                "properties": {"_symbol": 2},
                "sourceLayer": "Water line",
                "outcome": {"kind": "none"},
            },
            {
                "case": "unknown-water-area-symbol",
                "classifier": "water-area",
                "inputKind": "unknown",
                "properties": {"_symbol": 99},
                "sourceLayer": "Water area",
                "outcome": {"kind": "none"},
            },
            {
                "case": "unknown-road-symbol",
                "classifier": "road",
                "inputKind": "unknown",
                "properties": {"Viz": 0, "_symbol": 99},
                "sourceLayer": "Road",
                "outcome": {"kind": "none"},
            },
            {
                "case": "unknown-transportation-place-style",
                "classifier": "transportation-place",
                "inputKind": "unknown",
                "properties": {},
                "styleLayerId": "Unknown",
                "outcome": {"kind": "none"},
            },
            {
                "case": "unknown-style-rule-source",
                "classifier": "style-rule",
                "inputKind": "unknown",
                "layerType": "line",
                "properties": {},
                "sourceLayer": "Unknown",
                "styleLayerId": "Unknown",
                "outcome": {
                    "kind": "error",
                    "message": "style rule is not owned by the source-layer policy",
                },
            },
        ],
    }


def _behavior_outcome(
    callable_result: object = None, *, error: Exception | None = None
) -> dict[str, object]:
    if error is not None:
        return {"kind": "error", "message": str(error)}
    if callable_result is None:
        return {"kind": "none"}
    if isinstance(callable_result, SemanticClassification):
        return _classification_behavior_outcome(callable_result)
    return {"kind": "value", "value": callable_result}


def _invoke_public_classifier_behavior_vector(
    classifier_name: str, vector: Mapping[str, object]
) -> SemanticClassification | None:
    if classifier_name == "classify_boundary":
        return classify_boundary(vector["sourceLayer"], vector["properties"])
    if classifier_name == "classify_coastline":
        return classify_coastline(vector["sourceLayer"], vector["properties"])
    if classifier_name == "classify_water_line":
        return classify_water_line(vector["sourceLayer"], vector["properties"])
    if classifier_name == "classify_water_area":
        return classify_water_area(vector["sourceLayer"], vector["properties"])
    if classifier_name == "classify_road":
        return classify_road(vector["sourceLayer"], vector["properties"])
    if classifier_name == "classify_one_way_style":
        return classify_one_way_style(
            vector["sourceLayer"],
            vector["styleLayerId"],
            vector["properties"],
            require_direction=vector["requireDirection"],
        )
    if classifier_name == "classify_disputed_label_style":
        return classify_disputed_label_style(
            vector["sourceLayer"], vector["styleLayerId"]
        )
    if classifier_name == "classify_transportation_place":
        return classify_transportation_place(
            vector["styleLayerId"], vector["properties"]
        )
    if classifier_name == "classify_land":
        return classify_land(
            vector["sourceLayer"],
            vector["styleLayerId"],
            vector["properties"],
        )
    if classifier_name == "classification_for_style_rule":
        return classification_for_style_rule(
            vector["sourceLayer"],
            vector["styleLayerId"],
            vector["layerType"],
            vector["properties"],
        )
    raise SemanticPolicyError("classifier behavior vector is unknown")


def _validate_classifier_behavior_vectors(vectors: Mapping[str, object]) -> None:
    exact_vectors = vectors["exactInteger"]
    unknown_vectors = vectors["unknownSelectors"]
    public_vectors = vectors["publicClassifiers"]
    accepted_domains = vectors["acceptedDomains"]
    if (
        type(exact_vectors) is not list
        or type(unknown_vectors) is not list
        or type(public_vectors) is not dict
        or type(accepted_domains) is not dict
    ):
        raise SemanticPolicyError("classifier behavior vector tables are malformed")
    for vector in exact_vectors:
        if type(vector) is not dict:
            raise SemanticPolicyError("classifier behavior exact-integer vector is malformed")
        try:
            value = _exact_int(
                vector["properties"],
                vector["name"],
                required=vector["required"],
            )
        except SemanticPolicyError as error:
            actual = _behavior_outcome(error=error)
        else:
            actual = {"kind": "value", "value": value}
        if actual != vector["outcome"]:
            raise SemanticPolicyError(
                f"classifier behavior vector {vector['case']!r} drifted"
            )

    expected_public_classifiers = {
        "classification_for_style_rule",
        "classify_boundary",
        "classify_coastline",
        "classify_disputed_label_style",
        "classify_land",
        "classify_one_way_style",
        "classify_road",
        "classify_transportation_place",
        "classify_water_area",
        "classify_water_line",
    }
    if set(public_vectors) != expected_public_classifiers:
        raise SemanticPolicyError("public classifier behavior vector set drifted")
    if set(accepted_domains) != expected_public_classifiers:
        raise SemanticPolicyError("accepted classifier domains drifted")
    for classifier_name in sorted(public_vectors):
        classifier_vectors = public_vectors[classifier_name]
        if type(classifier_vectors) is not list or not classifier_vectors:
            raise SemanticPolicyError(
                "public classifier behavior vector table is malformed"
            )
        if {
            vector.get("inputKind")
            for vector in classifier_vectors
            if type(vector) is dict
        } != {"boolean", "float", "missing", "string", "unknown"}:
            raise SemanticPolicyError(
                f"classifier behavior vector domain for {classifier_name!r} drifted"
            )
        for vector in classifier_vectors:
            if type(vector) is not dict:
                raise SemanticPolicyError(
                    "public classifier behavior vector is malformed"
                )
            try:
                result = _invoke_public_classifier_behavior_vector(
                    classifier_name, vector
                )
            except SemanticPolicyError as error:
                actual = _behavior_outcome(error=error)
            else:
                actual = _behavior_outcome(result)
            if actual != vector["outcome"]:
                raise SemanticPolicyError(
                    f"classifier behavior vector {vector['case']!r} drifted"
                )

    for vector in unknown_vectors:
        if type(vector) is not dict:
            raise SemanticPolicyError("classifier behavior unknown-selector vector is malformed")
        classifier = vector["classifier"]
        try:
            if classifier == "boundary":
                result = classify_boundary(
                    vector["sourceLayer"], vector["properties"]
                )
            elif classifier == "water-line":
                result = classify_water_line(
                    vector["sourceLayer"], vector["properties"]
                )
            elif classifier == "water-area":
                result = classify_water_area(
                    vector["sourceLayer"], vector["properties"]
                )
            elif classifier == "road":
                result = classify_road(vector["sourceLayer"], vector["properties"])
            elif classifier == "transportation-place":
                result = classify_transportation_place(
                    vector["styleLayerId"], vector["properties"]
                )
            elif classifier == "style-rule":
                result = classification_for_style_rule(
                    vector["sourceLayer"],
                    vector["styleLayerId"],
                    vector["layerType"],
                    vector["properties"],
                )
            else:
                raise SemanticPolicyError("classifier behavior vector is unknown")
        except SemanticPolicyError as error:
            actual = _behavior_outcome(error=error)
        else:
            actual = _behavior_outcome(result)
        if actual != vector["outcome"]:
            raise SemanticPolicyError(
                f"classifier behavior vector {vector['case']!r} drifted"
            )


def _canonical_policy_document() -> dict[str, object]:
    classifier_behavior_vectors = _classifier_behavior_vectors_document()
    _validate_classifier_behavior_vectors(classifier_behavior_vectors)
    document: dict[str, object] = {
        "classifierBehaviorVectors": classifier_behavior_vectors,
        "sourceStyleOwnership": {
            "schema": "flight-alert-exp8-source-style-ownership-v1",
            "digestDomain": "FAE8STYLEOWNER1",
            "ownedPairCount": len(OWNED_SOURCE_STYLE_PAIR_SHA256),
            "ownedPairSha256": sorted(
                digest.hex() for digest in OWNED_SOURCE_STYLE_PAIR_SHA256
            ),
        },
        "boundarySymbolTable": {
            str(value): semantic_classification_document(
                classify_boundary(
                    "Boundary line",
                    {
                        "_symbol": value,
                        "DisputeID": 1 if value >= 6 else 0,
                        "Viz": 0,
                    },
                )
            )
            for value in _BOUNDARY_SYMBOLS
        },
        "classificationEnumIds": {
            "featureKinds": {item.name: item.value for item in FeatureKind},
            "landEvidence": {item.name: item.value for item in LandEvidence},
            "layerGroups": {item.name: item.value for item in LayerGroup},
            "masterOnlyGeometrySubtypes": {
                item.name: item.value for item in MasterOnlyGeometrySubtype
            },
            "protectedStatus": {item.name: item.value for item in ProtectedStatus},
        },
        "landEvidence": {
            "ambiguousSources": sorted(_AMBIGUOUS_LAND_SOURCES),
            "ambiguousClassification": semantic_classification_document(
                classify_land(
                    "Park or farming/label",
                    "Park or farming/label/Default",
                    {},
                )
            ),
            "publicLandRequires": "SOURCE_EXPLICIT",
            "sourceExplicitClassification": semantic_classification_document(
                classify_land(
                    "Marine park/label", "Marine park/label/Default", {}
                )
            ),
            "sourceExplicitStyleIds": sorted(_SOURCE_EXPLICIT_LAND_STYLES),
        },
        "cityCapitalStyleIds": sorted(_CITY_CAPITAL_STYLE_IDS),
        "cityCapitalStyleClassifications": {
            style_id: semantic_classification_document(
                classification_for_style_rule(
                    _owned_source_layer_for_style_id(style_id),
                    style_id,
                    "symbol",
                    {},
                )
            )
            for style_id in sorted(_CITY_CAPITAL_STYLE_IDS)
        },
        "classifierDefaults": {
            "classificationUsesDisplayText": False,
            "cityCapitalSubtype": SemanticSubtype.CAPITAL_MAJOR_CITY.value,
            "cityNoncapitalSubtype": SemanticSubtype.CITY_TOWN.value,
            "featureKindByStyleType": {
                key: value.value
                for key, value in sorted(_FEATURE_KIND_BY_STYLE_TYPE.items())
            },
            "labelClassificationByLayerGroup": {
                str(group.value): {
                    "kind": _LABEL_KIND_BY_GROUP[group],
                    "renderStyleTokenId": token.value,
                }
                for group, token in sorted(
                    _LABEL_RENDER_TOKEN_BY_GROUP.items(),
                    key=lambda item: item[0].value,
                )
            },
            "lineClassificationByLayerGroup": {
                str(group.value): {
                    "kind": _LINE_KIND_BY_GROUP[group],
                    "renderStyleTokenId": token.value,
                }
                for group, token in sorted(
                    _LINE_RENDER_TOKEN_BY_GROUP.items(),
                    key=lambda item: item[0].value,
                )
            },
            "disputedBoundaryRequiresNonzeroDisputeId": True,
            "missingPropertiesCoerced": False,
            "oneWayDirections": sorted(_ONE_WAY_DIRECTIONS),
            "oneWayFeatureKind": FeatureKind.LINE.value,
            "semanticClassificationFieldDefaults": {
                "adminLevel": None,
                "coastline": False,
                "disputed": False,
                "intermittent": False,
                "landEvidence": LandEvidence.NOT_APPLICABLE.value,
                "oneWay": False,
                "protectedStatus": ProtectedStatus.NOT_APPLICABLE.value,
                "shield": False,
                "tunnel": False,
            },
            "sourceStyleIdentityRequired": True,
            "styleFilterMustAlsoMatchAtRuntime": True,
            "transportationPlaceDefaultSubtype": TransportSubtype.TRANSPORTATION_PLACE.value,
            "transportTunnelOrthogonal": True,
            "vizExcludedValue": _VIZ_EXCLUDED_VALUE,
            "waterAreaFeatureKind": FeatureKind.POLYGON_OUTLINE.value,
        },
        "fixedClassifications": {
            "coastline": semantic_classification_document(
                classify_coastline("Coastline", {})
            ),
            "watershedBoundary": semantic_classification_document(
                classify_boundary("Watershed boundary", {})
            ),
        },
        "disputedLabelStyleClassifications": {
            style_id: semantic_classification_document(
                classify_disputed_label_style("Disputed label point", style_id)
            )
            for style_id, _item in sorted(
                _DISPUTED_LABEL_STYLE_CLASSIFICATIONS.items()
            )
        },
        "oneWayStyleSubtypes": {
            style_id: semantic_classification_document(
                classify_one_way_style(
                    "Road tunnel/label" if item[1] else "Road/label",
                    style_id,
                    {},
                    require_direction=False,
                )
            )
            for style_id, item in sorted(ONE_WAY_STYLE_CLASSIFICATIONS.items())
        },
        "presentationFilters": {
            key: list(value)
            for key, value in sorted(PRESENTATION_FILTER_SUBTYPES.items())
        },
        "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
        "masterOnlyGeometrySemanticSubtypeIds": sorted(
            item.value for item in MasterOnlyGeometrySubtype
        ),
        "referencePresentationSemanticSubtypeIds": sorted(
            item.value for item in SemanticSubtype
        ),
        "renderStyleTokenIds": {
            item.name: item.value for item in RenderStyleToken
        },
        "retainedRawProperties": list(RETAINED_RAW_PROPERTIES),
        "roadSymbolTable": {
            str(key): value.value for key, value in sorted(_ROAD_SUBTYPE.items())
        },
        "roadSymbolClassificationsBySource": {
            source_layer: {
                str(key): semantic_classification_document(
                    classify_road(
                        source_layer, {"_symbol": key, "Viz": 0}
                    )
                )
                for key in sorted(_ROAD_SUBTYPE)
            }
            for source_layer in ("Road", "Road tunnel")
        },
        "roadLabelStyleSubtypes": {
            key: int(value) for key, value in sorted(_ROAD_LABEL_SUBTYPE.items())
        },
        "roadLabelStyleClassifications": {
            style_id: semantic_classification_document(
                classification_for_style_rule(
                    _owned_source_layer_for_style_id(style_id),
                    style_id,
                    "symbol",
                    {},
                )
            )
            for style_id in sorted(_ROAD_LABEL_SUBTYPE)
        },
        "roadShieldStyleIds": sorted(ROAD_SHIELD_STYLE_IDS),
        "roadShieldStyleClassifications": {
            style_id: semantic_classification_document(
                classification_for_style_rule(
                    "Road/label", style_id, "symbol", {}
                )
            )
            for style_id in sorted(ROAD_SHIELD_STYLE_IDS)
        },
        "schema": "flight-alert-exp8-semantic-policy-v3",
        "sourceLayers": {
            item.source_layer: {
                "acceptedTypes": list(item.accepted_types),
                "defaultLabelSubtype": item.default_label_subtype,
                "defaultLineSubtype": item.default_line_subtype,
                "fallbackTextSourceField": item.fallback_text_source_field,
                "fallbackLabelSubtype": item.fallback_label_subtype,
                "fallbackLabelKind": item.fallback_label_kind,
                "family": item.family,
                "layerGroup": item.layer_group.value,
            }
            for item in sorted(_SOURCE_POLICIES, key=lambda value: value.source_layer)
        },
        "transportSemanticSubtypeIds": {
            item.name: item.value for item in TransportSubtype
        },
        "transportPlaceStyleIds": sorted(_ALL_TRANSPORT_PLACE_STYLE_IDS),
        "transportPlaceSubtypeOverrides": {
            key: value.value
            for key, value in sorted(_TRANSPORT_PLACE_SUBTYPE.items())
        },
        "transportPlaceStyleClassifications": {
            style_id: semantic_classification_document(
                classify_transportation_place(style_id, {})
            )
            for style_id in sorted(_ALL_TRANSPORT_PLACE_STYLE_IDS)
        },
        "waterAreaSymbolTableBySource": {
            source_layer: {
                "default" if key is None else str(key): semantic_classification_document(
                    classify_water_area(
                        source_layer, {} if key is None else {"_symbol": key}
                    )
                )
                for key, item in sorted(
                    table.items(),
                    key=lambda value: (-1 if value[0] is None else value[0]),
                )
            }
            for source_layer, table in sorted(_WATER_AREA_KIND_BY_SOURCE.items())
        },
        "waterLabelStyleSubtypes": {
            key: value.value for key, value in sorted(_WATER_LABEL_SUBTYPE.items())
        },
        "waterLabelStyleClassifications": {
            style_id: semantic_classification_document(
                classification_for_style_rule(
                    _owned_source_layer_for_style_id(style_id),
                    style_id,
                    "symbol",
                    {},
                )
            )
            for style_id in sorted(_WATER_LABEL_SUBTYPE)
        },
        "waterLineSymbolTable": {
            str(key): semantic_classification_document(
                classify_water_line("Water line", {"_symbol": key})
            )
            for key in sorted(_WATER_LINE_KIND)
        },
    }
    return document


def semantic_policy_document() -> dict[str, object]:
    return deepcopy(_canonical_policy_document())


def canonical_semantic_policy_bytes(
    document: Mapping[str, object] | None = None,
) -> bytes:
    selected = _canonical_policy_document() if document is None else document
    return (
        json.dumps(
            selected,
            allow_nan=False,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def semantic_policy_sha256(
    document: Mapping[str, object] | None = None,
) -> str:
    return hashlib.sha256(
        b"FAE8SEMPOL3\0" + canonical_semantic_policy_bytes(document)
    ).hexdigest()


SEMANTIC_POLICY_SHA256 = semantic_policy_sha256()


if not {item.value for item in SemanticSubtype}.isdisjoint(
    item.value for item in TransportSubtype
):
    raise RuntimeError("transport subtype IDs must be disjoint from presentation subtypes")

if not {item.value for item in MasterOnlyGeometrySubtype}.isdisjoint(
    {item.value for item in SemanticSubtype} | {item.value for item in TransportSubtype}
):
    raise RuntimeError(
        "master-only geometry subtype IDs must be disjoint from filterable subtypes"
    )
