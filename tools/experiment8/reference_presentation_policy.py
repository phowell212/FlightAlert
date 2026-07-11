from __future__ import annotations

import hashlib
import json
import math
import re
import struct
from dataclasses import dataclass, replace
from enum import Enum, IntEnum
from typing import Mapping


GLOBAL_RIVER_COMPLETE_RELATION_MIN_LENGTH_M = 500_000
MAJOR_RIVER_COMPLETE_RELATION_MIN_LENGTH_M = 25_000
LOCAL_RIVER_COMPLETE_RELATION_MIN_LENGTH_M = 5_000
GLOBAL_CITY_POPULATION_MIN = 1_000_000
REGIONAL_CITY_POPULATION_MIN = 100_000
LOCAL_CITY_POPULATION_MIN = 10_000
GLOBAL_ISLAND_AREA_M2_MIN = 10_000_000_000
REGIONAL_ISLAND_AREA_M2_MIN = 500_000_000
LOCAL_ISLAND_AREA_M2_MIN = 25_000_000
GLOBAL_WATER_AREA_M2_MIN = 100_000_000_000
REGIONAL_WATER_AREA_M2_MIN = 5_000_000_000
LOCAL_WATER_AREA_M2_MIN = 100_000_000
REGIONAL_PROTECTED_AREA_M2_MIN = 5_000_000_000
LOCAL_PROTECTED_AREA_M2_MIN = 100_000_000
MAX_LINE_LABEL_BEND_CENTI_DEGREES = 3_000
UNCONDENSED_TEXT_SCALE_X_MILLI = 1_000
FULL_ALPHA_MILLI = 1_000
PRESENTATION_POLICY_DOMAIN = b"FAE8PRES1\0"
LABEL_DISPLAY_MAX_ZOOM_CENTI = 10_000
LABEL_FADE_OUT_ZOOM_CENTI = 10_000
LINE_LABEL_REPEAT_SPACING_PX = 1_000
REFERENCE_LABEL_COLLISION_GROUP = 1
LABEL_ACTIVE_BAND_LIMIT = 4
LABEL_END_CLEARANCE_MILLI_EM = 500
LABEL_COLLISION_PADDING_MILLI_EM = 180
LABEL_EDGE_CLEARANCE_MILLI_EM = 250
LABEL_MAX_PRESENTATIONS_PER_CANDIDATE_WRAP = 1
LABEL_HANDOFF_MAX_MS = 220
PRESENTATION_POLICY_SHA256 = (
    "40f4e98394dacfaaad7cdc195858d0b56fc72ba5c83ccfc1e75d71fff6f6395c"
)

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_I64_MAX = (1 << 63) - 1
_U64_MAX = (1 << 64) - 1
_CATALOG_DOMAIN = b"FAE8CAT1\0"
_CATALOG_VERSION = 1


class ReferencePolicyError(ValueError):
    """Reference presentation input cannot satisfy the Experiment 8 contract."""


class SemanticSubtype(IntEnum):
    COUNTRY_TERRITORY = 100
    FIRST_ORDER_REGION = 110
    SECOND_LOCAL_REGION = 120

    CAPITAL_MAJOR_CITY = 200
    CITY_TOWN = 210
    LOCAL_PLACE = 220
    ISLAND_ISLET = 230

    OCEAN_SEA = 300
    BAY_SOUND = 310
    LAKE_RESERVOIR = 320
    RIVER = 330
    STREAM_CREEK = 340
    CANAL_CHANNEL = 350
    UNSPECIFIED_WATERCOURSE = 360

    PROTECTED_LAND = 400

    COASTLINE = 500
    INTERNATIONAL_BOUNDARY = 510
    STATE_PROVINCE_BOUNDARY = 520
    COUNTY_LOCAL_BOUNDARY = 530
    OTHER_ADMIN_BOUNDARY = 540
    PROTECTED_AREA_OUTLINE = 550
    WATERSHED_WATER_BOUNDARY = 560
    OTHER_SOURCED_OUTLINE = 570


class FilterId(str, Enum):
    LABELS_REGIONS = "labels.regions"
    LABELS_PLACES = "labels.places"
    LABELS_ISLANDS = "labels.islands"
    LABELS_MAJOR_WATER = "labels.major_water"
    LABELS_RIVERS = "labels.rivers"
    LABELS_STREAMS = "labels.streams"
    LABELS_CANALS = "labels.canals"
    LABELS_PROTECTED_LANDS = "labels.protected_lands"

    OUTLINES_COASTLINES = "outlines.coastlines"
    OUTLINES_INTERNATIONAL = "outlines.international"
    OUTLINES_STATE_PROVINCE = "outlines.state_province"
    OUTLINES_COUNTY_LOCAL = "outlines.county_local"
    OUTLINES_PROTECTED_AREAS = "outlines.protected_areas"
    OUTLINES_WATER_BOUNDARIES = "outlines.water_boundaries"
    OUTLINES_OTHER = "outlines.other"


class FilterKind(str, Enum):
    LABEL = "label"
    OUTLINE = "outline"


class StyleFamily(str, Enum):
    REGION_COUNTRY = "region.country"
    REGION_FIRST_ORDER = "region.first_order"
    REGION_LOCAL = "region.local"
    PLACE_MAJOR = "place.major"
    PLACE = "place"
    PLACE_LOCAL = "place.local"
    ISLAND = "island"
    WATER_OCEAN = "water.ocean"
    WATER_BAY = "water.bay"
    WATER_LAKE = "water.lake"
    RIVER = "water.river"
    STREAM = "water.stream"
    CANAL = "water.canal"
    WATERCOURSE_UNSPECIFIED = "water.unspecified_course"
    PROTECTED_LAND = "land.protected"
    COASTLINE = "outline.coastline"
    INTERNATIONAL_BOUNDARY = "outline.international"
    STATE_PROVINCE_BOUNDARY = "outline.state_province"
    COUNTY_LOCAL_BOUNDARY = "outline.county_local"
    OTHER_ADMIN_BOUNDARY = "outline.other_admin"
    PROTECTED_AREA_OUTLINE = "outline.protected_area"
    WATERSHED_WATER_BOUNDARY = "outline.water_boundary"
    OTHER_SOURCED_OUTLINE = "outline.other"


class ProminenceTier(str, Enum):
    GLOBAL_MAJOR = "global_major"
    REGIONAL_MAJOR = "regional_major"
    LOCAL = "local"
    FINE = "fine"


_PROMINENCE_STRENGTH = {
    ProminenceTier.GLOBAL_MAJOR: 0,
    ProminenceTier.REGIONAL_MAJOR: 1,
    ProminenceTier.LOCAL: 2,
    ProminenceTier.FINE: 3,
}


class CapitalLevel(str, Enum):
    NONE = "none"
    REGIONAL = "regional"
    NATIONAL = "national"


PROMINENCE_TIER_CODE = {
    ProminenceTier.GLOBAL_MAJOR: 0,
    ProminenceTier.REGIONAL_MAJOR: 1,
    ProminenceTier.LOCAL: 2,
    ProminenceTier.FINE: 3,
}

SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE = {
    SemanticSubtype.COUNTRY_TERRITORY: 0,
    SemanticSubtype.OCEAN_SEA: 10,
    SemanticSubtype.CAPITAL_MAJOR_CITY: 20,
    SemanticSubtype.FIRST_ORDER_REGION: 30,
    SemanticSubtype.RIVER: 40,
    SemanticSubtype.BAY_SOUND: 50,
    SemanticSubtype.CITY_TOWN: 60,
    SemanticSubtype.ISLAND_ISLET: 70,
    SemanticSubtype.LAKE_RESERVOIR: 80,
    SemanticSubtype.SECOND_LOCAL_REGION: 90,
    SemanticSubtype.LOCAL_PLACE: 100,
    SemanticSubtype.PROTECTED_LAND: 110,
    SemanticSubtype.STREAM_CREEK: 120,
    SemanticSubtype.CANAL_CHANNEL: 130,
    SemanticSubtype.UNSPECIFIED_WATERCOURSE: 140,
}

_SEMANTIC_PRIORITY_TIER_STRIDE = 1_000


def semantic_priority_for(
    subtype: SemanticSubtype,
    tier: ProminenceTier,
) -> int:
    if subtype not in SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE:
        raise ReferencePolicyError("semantic priority requires a label subtype")
    if not isinstance(tier, ProminenceTier):
        raise ReferencePolicyError("semantic priority requires a prominence tier")
    return (
        PROMINENCE_TIER_CODE[tier] * _SEMANTIC_PRIORITY_TIER_STRIDE
        + SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE[subtype]
    )


class ProminenceEvidenceKind(IntEnum):
    PROVIDER_RANK = 1
    CAPITAL_LEVEL = 2
    POPULATION = 3
    COMPLETE_AREA_M2 = 4
    COMPLETE_RELATION_LENGTH_M = 5
    TYPED_SUBTYPE_DEFAULT = 6


class PlacementSourceKind(IntEnum):
    NONE = 0
    DIRECT_SOURCE_POINT = 1
    SOURCE_OWNED_AREA_LABEL_POINT = 2
    DIRECT_SOURCE_PATH = 3
    EXACT_PARENT_PATH = 4


@dataclass(frozen=True, slots=True)
class SourceEvidenceContext:
    source_generation_sha256: str
    classifier_sha256: str
    source_field_id: int

    def __post_init__(self) -> None:
        _require_sha256(self.source_generation_sha256, "source generation SHA-256")
        _require_sha256(self.classifier_sha256, "source classifier SHA-256")
        if (
            type(self.source_field_id) is not int
            or not 1 <= self.source_field_id <= _U64_MAX
        ):
            raise ReferencePolicyError("source field ID must be a nonzero u64")


@dataclass(frozen=True, slots=True)
class ProviderProminenceEvidence:
    context: SourceEvidenceContext
    tier: ProminenceTier
    raw_provider_rank: int

    def __post_init__(self) -> None:
        if not isinstance(self.context, SourceEvidenceContext):
            raise ReferencePolicyError("provider evidence needs source context")
        if not isinstance(self.tier, ProminenceTier):
            raise ReferencePolicyError("provider evidence tier is unknown")
        if (
            type(self.raw_provider_rank) is not int
            or not -(1 << 31) <= self.raw_provider_rank < 1 << 31
        ):
            raise ReferencePolicyError("raw provider rank must be signed 32-bit")


@dataclass(frozen=True, slots=True)
class ProminenceDecision:
    subtype: SemanticSubtype
    semantic_priority: int
    tier: ProminenceTier
    provider_rank: int | None
    complete_geometry_measure_bucket: int
    prominence_rule_id: int
    evidence_kind: ProminenceEvidenceKind
    evidence_value: int
    source_generation_sha256: str
    classifier_sha256: str
    source_field_id: int
    policy_sha256: str

    def __post_init__(self) -> None:
        if self.subtype not in SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE:
            raise ReferencePolicyError("prominence decision requires a label subtype")
        if not isinstance(self.tier, ProminenceTier):
            raise ReferencePolicyError("prominence decision tier is unknown")
        if self.semantic_priority != semantic_priority_for(self.subtype, self.tier):
            raise ReferencePolicyError("prominence decision semantic priority drifted")
        if self.provider_rank is not None and (
            type(self.provider_rank) is not int
            or not -(1 << 31) <= self.provider_rank < 1 << 31
        ):
            raise ReferencePolicyError("prominence provider rank must be signed 32-bit")
        if (
            type(self.complete_geometry_measure_bucket) is not int
            or not 0 <= self.complete_geometry_measure_bucket <= (1 << 16) - 1
        ):
            raise ReferencePolicyError("complete geometry measure bucket must be u16")
        if type(self.prominence_rule_id) is not int or not 0 <= self.prominence_rule_id <= _U64_MAX:
            raise ReferencePolicyError("prominence rule ID must be u64")
        if not isinstance(self.evidence_kind, ProminenceEvidenceKind):
            raise ReferencePolicyError("prominence evidence kind is unknown")
        if type(self.evidence_value) is not int or not -_I64_MAX - 1 <= self.evidence_value <= _I64_MAX:
            raise ReferencePolicyError("prominence evidence value must be signed 64-bit")
        _require_sha256(self.source_generation_sha256, "source generation SHA-256")
        _require_sha256(self.classifier_sha256, "source classifier SHA-256")
        if type(self.source_field_id) is not int or not 1 <= self.source_field_id <= _U64_MAX:
            raise ReferencePolicyError("prominence source field ID must be nonzero u64")
        if self.policy_sha256 != PRESENTATION_POLICY_SHA256:
            raise ReferencePolicyError("prominence decision policy SHA-256 drifted")
        expected_rule_id = _prominence_rule_id(
            self.subtype, self.tier, self.evidence_kind
        )
        if self.prominence_rule_id != expected_rule_id:
            raise ReferencePolicyError("prominence decision rule ID drifted")
        if (
            self.evidence_kind is ProminenceEvidenceKind.PROVIDER_RANK
        ) != (self.provider_rank is not None):
            raise ReferencePolicyError("provider-rank presence contradicts evidence kind")
        _validate_prominence_decision_semantics(self)


class CatalogControlStatus(str, Enum):
    AVAILABLE = "available"
    UNAVAILABLE = "unavailable"


@dataclass(frozen=True, slots=True)
class FilterSpec:
    filter_id: FilterId
    title: str
    kind: FilterKind
    subtypes: tuple[SemanticSubtype, ...]
    default_enabled: bool = True

    def __post_init__(self) -> None:
        if not self.title or self.title.strip() != self.title:
            raise ReferencePolicyError("filter title must be nonempty and canonical")
        if not self.subtypes:
            raise ReferencePolicyError("filter must own at least one semantic subtype")
        if len(set(self.subtypes)) != len(self.subtypes):
            raise ReferencePolicyError("filter cannot repeat a semantic subtype")


@dataclass(frozen=True, slots=True)
class StyleSpec:
    family: StyleFamily
    color_token: str
    halo_token: str
    font_slant: str
    font_weight: int
    letter_spacing_milli_em: int
    line_pattern: str
    line_width_milli_dp: int

    def __post_init__(self) -> None:
        if not self.color_token or not self.halo_token:
            raise ReferencePolicyError("style color and halo tokens must be explicit")
        if self.font_slant not in {"normal", "italic", "not_applicable"}:
            raise ReferencePolicyError("style font slant is unknown")
        if type(self.font_weight) is not int or not 0 <= self.font_weight <= 900:
            raise ReferencePolicyError("style font weight is outside [0, 900]")
        if (
            type(self.letter_spacing_milli_em) is not int
            or not 0 <= self.letter_spacing_milli_em <= 1_000
        ):
            raise ReferencePolicyError("style letter spacing is outside [0, 1000]")
        if self.line_pattern not in {
            "none",
            "solid",
            "long_dash",
            "short_dash",
            "dash_dot",
            "dot",
        }:
            raise ReferencePolicyError("style line pattern is unknown")
        if type(self.line_width_milli_dp) is not int or self.line_width_milli_dp < 0:
            raise ReferencePolicyError("style line width must be nonnegative")


@dataclass(frozen=True, slots=True)
class ResolvedStyleDetails:
    color_argb: int
    alpha_milli: int
    halo_argb: int
    halo_alpha_milli: int
    halo_width_milli_em: int
    line_halo_width_milli_dp: int
    dash_milli_dp: tuple[int, ...]
    dash_phase_milli_dp: int
    line_cap: str
    line_join: str

    def __post_init__(self) -> None:
        for value, label in (
            (self.color_argb, "style color"),
            (self.halo_argb, "style halo color"),
        ):
            if type(value) is not int or not 0 <= value <= (1 << 32) - 1:
                raise ReferencePolicyError(f"{label} must be canonical ARGB u32")
        for value, label in (
            (self.alpha_milli, "style alpha"),
            (self.halo_alpha_milli, "style halo alpha"),
        ):
            if type(value) is not int or not 0 <= value <= 1_000:
                raise ReferencePolicyError(f"{label} must be inside [0, 1000]")
        for value, label in (
            (self.halo_width_milli_em, "text halo width"),
            (self.line_halo_width_milli_dp, "line halo width"),
            (self.dash_phase_milli_dp, "dash phase"),
        ):
            if type(value) is not int or value < 0:
                raise ReferencePolicyError(f"{label} must be nonnegative")
        if type(self.dash_milli_dp) is not tuple or any(
            type(value) is not int or value <= 0 for value in self.dash_milli_dp
        ):
            raise ReferencePolicyError("dash array must contain positive exact integers")
        if len(self.dash_milli_dp) % 2 != 0:
            raise ReferencePolicyError("dash array must contain on/off pairs")
        if self.line_cap not in {"round", "butt"}:
            raise ReferencePolicyError("line cap is unsupported")
        if self.line_join not in {"round", "miter"}:
            raise ReferencePolicyError("line join is unsupported")


@dataclass(frozen=True, slots=True)
class LabelVisibilityRule:
    rule_id: str
    min_zoom_centi: int
    full_alpha_zoom_centi: int
    text_size_milli_sp: int
    max_bend_centi_degrees: int = MAX_LINE_LABEL_BEND_CENTI_DEGREES
    letter_spacing_milli_em: int = 70

    def __post_init__(self) -> None:
        if not self.rule_id or self.rule_id.strip() != self.rule_id:
            raise ReferencePolicyError("visibility rule ID must be nonempty and canonical")
        for value, label in (
            (self.min_zoom_centi, "minimum centizoom"),
            (self.full_alpha_zoom_centi, "full-alpha centizoom"),
            (self.text_size_milli_sp, "text size"),
            (self.max_bend_centi_degrees, "maximum bend"),
            (self.letter_spacing_milli_em, "letter spacing"),
        ):
            if type(value) is not int or value < 0:
                raise ReferencePolicyError(f"{label} must be a nonnegative integer")
        if self.min_zoom_centi >= self.full_alpha_zoom_centi:
            raise ReferencePolicyError("visibility fade interval must be nonempty")
        if self.text_size_milli_sp == 0:
            raise ReferencePolicyError("label text size must be positive")
        if self.max_bend_centi_degrees > MAX_LINE_LABEL_BEND_CENTI_DEGREES:
            raise ReferencePolicyError("visibility rule cannot weaken the bend ceiling")


@dataclass(frozen=True, slots=True)
class OutlineVisibilityRule:
    rule_id: str
    min_zoom_centi: int
    full_alpha_zoom_centi: int
    max_zoom_centi: int
    fade_out_zoom_centi: int
    draw_order: int
    priority: int

    def __post_init__(self) -> None:
        if not self.rule_id or self.rule_id.strip() != self.rule_id:
            raise ReferencePolicyError("outline rule ID must be nonempty and canonical")
        for value, label in (
            (self.min_zoom_centi, "outline minimum centizoom"),
            (self.full_alpha_zoom_centi, "outline full-alpha centizoom"),
            (self.max_zoom_centi, "outline maximum centizoom"),
            (self.fade_out_zoom_centi, "outline fade-out centizoom"),
        ):
            if type(value) is not int or not 0 <= value <= 10_000:
                raise ReferencePolicyError(f"{label} is outside [0, 10000]")
        if not (
            self.min_zoom_centi
            < self.full_alpha_zoom_centi
            <= self.fade_out_zoom_centi
            <= self.max_zoom_centi
        ):
            raise ReferencePolicyError("outline visibility interval is invalid")
        for value, label in (
            (self.draw_order, "outline draw order"),
            (self.priority, "outline priority"),
        ):
            if type(value) is not int or not -(1 << 31) <= value < 1 << 31:
                raise ReferencePolicyError(f"{label} must be signed 32-bit")


@dataclass(frozen=True, slots=True)
class WaterwayFacts:
    subtype: SemanticSubtype
    complete_named_relation: bool = False
    complete_relation_length_m: int | None = None
    evidence_context: SourceEvidenceContext | None = None
    provider_evidence: ProviderProminenceEvidence | None = None

    def __post_init__(self) -> None:
        if self.subtype not in _WATERCOURSE_SUBTYPES:
            raise ReferencePolicyError("waterway facts require a watercourse subtype")
        if type(self.complete_named_relation) is not bool:
            raise ReferencePolicyError("complete relation flag must be Boolean")
        if self.complete_relation_length_m is not None and (
            type(self.complete_relation_length_m) is not int
            or not 0 <= self.complete_relation_length_m <= _I64_MAX
        ):
            raise ReferencePolicyError(
                "complete relation length must be nonnegative signed 64-bit metres"
            )
        if self.evidence_context is not None and not isinstance(
            self.evidence_context, SourceEvidenceContext
        ):
            raise ReferencePolicyError("waterway evidence context is unsupported")
        if self.provider_evidence is not None and not isinstance(
            self.provider_evidence, ProviderProminenceEvidence
        ):
            raise ReferencePolicyError("waterway provider evidence is unsupported")
        if self.provider_evidence is not None and self.evidence_context is not None:
            raise ReferencePolicyError(
                "waterway provider evidence already owns the source context"
            )


@dataclass(frozen=True, slots=True)
class LabelFacts:
    subtype: SemanticSubtype
    evidence_context: SourceEvidenceContext | None = None
    provider_evidence: ProviderProminenceEvidence | None = None
    population: int | None = None
    population_verified: bool = False
    capital_level: CapitalLevel = CapitalLevel.NONE
    capital_level_verified: bool = False
    complete_area_m2: int | None = None
    complete_area_verified: bool = False
    complete_named_relation: bool = False
    complete_relation_length_m: int | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.subtype, SemanticSubtype) or self.subtype.value >= 500:
            raise ReferencePolicyError("label facts require a label semantic subtype")
        for value, label in (
            (self.population_verified, "population verification"),
            (self.capital_level_verified, "capital-level verification"),
            (self.complete_area_verified, "complete-area verification"),
            (self.complete_named_relation, "complete named-relation"),
        ):
            if type(value) is not bool:
                raise ReferencePolicyError(f"{label} flag must be Boolean")
        if self.evidence_context is not None and not isinstance(
            self.evidence_context, SourceEvidenceContext
        ):
            raise ReferencePolicyError("label evidence context is unsupported")
        if self.provider_evidence is not None and not isinstance(
            self.provider_evidence, ProviderProminenceEvidence
        ):
            raise ReferencePolicyError("label provider evidence is unsupported")
        if self.provider_evidence is not None and self.evidence_context is not None:
            raise ReferencePolicyError(
                "label provider evidence already owns the source context"
            )
        if not isinstance(self.capital_level, CapitalLevel):
            raise ReferencePolicyError("capital level must be a known typed value")
        for value, label in (
            (self.population, "population"),
            (self.complete_area_m2, "complete area"),
            (self.complete_relation_length_m, "complete relation length"),
        ):
            if value is not None and (
                type(value) is not int or not 0 <= value <= _I64_MAX
            ):
                raise ReferencePolicyError(
                    f"{label} must be a nonnegative signed 64-bit integer"
                )
        if self.population_verified and self.population is None:
            raise ReferencePolicyError("verified population requires an exact value")
        if self.complete_area_verified and self.complete_area_m2 is None:
            raise ReferencePolicyError("verified complete area requires an exact value")
        place_subtypes = (
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        )
        if self.subtype not in place_subtypes and (
            self.population is not None
            or self.population_verified
            or self.capital_level is not CapitalLevel.NONE
            or self.capital_level_verified
        ):
            raise ReferencePolicyError(
                "population evidence and capital evidence apply only to place labels"
            )
        area_subtypes = (
            SemanticSubtype.ISLAND_ISLET,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.PROTECTED_LAND,
        )
        if self.subtype not in area_subtypes and (
            self.complete_area_m2 is not None or self.complete_area_verified
        ):
            raise ReferencePolicyError(
                "complete area evidence does not apply to this label subtype"
            )
        if self.subtype not in _WATERCOURSE_SUBTYPES and (
            self.complete_named_relation or self.complete_relation_length_m is not None
        ):
            raise ReferencePolicyError(
                "relation-length evidence applies only to watercourse labels"
            )


@dataclass(frozen=True, slots=True)
class SubtypeCatalogCounts:
    distinct_feature_count: int
    canonical_variant_count: int
    posting_count: int

    def __post_init__(self) -> None:
        for value, label in (
            (self.distinct_feature_count, "distinct feature count"),
            (self.canonical_variant_count, "canonical variant count"),
            (self.posting_count, "posting count"),
        ):
            if type(value) is not int or not 0 <= value <= _U64_MAX:
                raise ReferencePolicyError(
                    f"catalog {label} must be a nonnegative u64 integer"
                )


@dataclass(frozen=True, slots=True)
class ReferenceClassCatalog:
    status: CatalogControlStatus
    reason: str
    renderer_semantic_stream_sha256: str | None
    renderer_contract_sha256: str | None
    presentation_policy_sha256: str | None
    catalog_sha256: str | None
    subtype_counts: tuple[tuple[SemanticSubtype, SubtypeCatalogCounts], ...]

    @classmethod
    def from_verified_bytes(
        cls,
        catalog_bytes: bytes,
        *,
        expected_catalog_sha256: str,
        expected_renderer_semantic_stream_sha256: str,
        expected_renderer_contract_sha256: str,
        expected_presentation_policy_sha256: str,
    ) -> "ReferenceClassCatalog":
        if type(catalog_bytes) is not bytes:
            raise ReferencePolicyError("catalog must be immutable exact bytes")
        expected_catalog_sha256 = _require_sha256(
            expected_catalog_sha256, "catalog SHA-256"
        )
        expected_renderer_semantic_stream_sha256 = _require_sha256(
            expected_renderer_semantic_stream_sha256,
            "expected renderer semantic stream SHA-256",
        )
        expected_renderer_contract_sha256 = _require_sha256(
            expected_renderer_contract_sha256,
            "expected renderer contract SHA-256",
        )
        expected_presentation_policy_sha256 = _require_sha256(
            expected_presentation_policy_sha256,
            "expected presentation policy SHA-256",
        )
        actual_catalog_sha256 = hashlib.sha256(catalog_bytes).hexdigest()
        if actual_catalog_sha256 != expected_catalog_sha256:
            raise ReferencePolicyError(
                "catalog SHA-256 mismatch: "
                f"expected {expected_catalog_sha256}, got {actual_catalog_sha256}"
            )
        (
            semantic_stream_sha256,
            renderer_contract_sha256,
            presentation_policy_sha256,
            normalized,
        ) = _decode_class_catalog_bytes(catalog_bytes)
        if semantic_stream_sha256 != expected_renderer_semantic_stream_sha256:
            raise ReferencePolicyError(
                "catalog renderer semantic stream does not match the installed package"
            )
        if renderer_contract_sha256 != expected_renderer_contract_sha256:
            raise ReferencePolicyError(
                "catalog renderer contract does not match the installed package"
            )
        if presentation_policy_sha256 != expected_presentation_policy_sha256:
            raise ReferencePolicyError(
                "catalog presentation policy does not match the installed package"
            )
        if presentation_policy_sha256 != PRESENTATION_POLICY_SHA256:
            raise ReferencePolicyError("catalog presentation policy is not supported")
        return cls(
            status=CatalogControlStatus.AVAILABLE,
            reason="verified",
            renderer_semantic_stream_sha256=semantic_stream_sha256,
            renderer_contract_sha256=renderer_contract_sha256,
            presentation_policy_sha256=presentation_policy_sha256,
            catalog_sha256=actual_catalog_sha256,
            subtype_counts=normalized,
        )

    @classmethod
    def unavailable(cls, reason: str) -> "ReferenceClassCatalog":
        if not isinstance(reason, str) or not reason or reason.strip() != reason:
            raise ReferencePolicyError("unavailable catalog reason must be explicit")
        return cls(
            status=CatalogControlStatus.UNAVAILABLE,
            reason=reason,
            renderer_semantic_stream_sha256=None,
            renderer_contract_sha256=None,
            presentation_policy_sha256=None,
            catalog_sha256=None,
            subtype_counts=(),
        )

    def __post_init__(self) -> None:
        if not isinstance(self.status, CatalogControlStatus):
            raise ReferencePolicyError("catalog status is unknown")
        if not isinstance(self.reason, str) or not self.reason:
            raise ReferencePolicyError("catalog status reason is required")
        if type(self.subtype_counts) is not tuple:
            raise ReferencePolicyError("catalog subtype counts must be a canonical tuple")
        previous_subtype_id = -1
        for entry in self.subtype_counts:
            if type(entry) is not tuple or len(entry) != 2:
                raise ReferencePolicyError("catalog has malformed canonical subtype counts")
            subtype, counts = entry
            if not isinstance(subtype, SemanticSubtype):
                raise ReferencePolicyError("catalog contains an unknown semantic subtype")
            if subtype.value <= previous_subtype_id:
                raise ReferencePolicyError(
                    "catalog canonical subtype counts must be strictly ID-sorted"
                )
            if not isinstance(counts, SubtypeCatalogCounts):
                raise ReferencePolicyError("catalog has unsupported subtype counts")
            previous_subtype_id = subtype.value
        if self.status is CatalogControlStatus.AVAILABLE:
            if self.reason != "verified":
                raise ReferencePolicyError("available catalog must be verifier-produced")
            semantic_stream = _require_sha256(
                self.renderer_semantic_stream_sha256,
                "renderer semantic stream SHA-256",
            )
            renderer_contract = _require_sha256(
                self.renderer_contract_sha256,
                "renderer contract SHA-256",
            )
            presentation_policy = _require_sha256(
                self.presentation_policy_sha256,
                "presentation policy SHA-256",
            )
            catalog_sha256 = _require_sha256(self.catalog_sha256, "catalog SHA-256")
            if presentation_policy != PRESENTATION_POLICY_SHA256:
                raise ReferencePolicyError("catalog presentation policy is unsupported")
            if tuple(subtype for subtype, _ in self.subtype_counts) != tuple(
                SemanticSubtype
            ):
                raise ReferencePolicyError("catalog must contain the exact subtype set")
            canonical = canonical_class_catalog_bytes(
                renderer_semantic_stream_sha256=semantic_stream,
                renderer_contract_sha256=renderer_contract,
                presentation_policy_sha256=presentation_policy,
                subtype_counts=dict(self.subtype_counts),
            )
            if hashlib.sha256(canonical).hexdigest() != catalog_sha256:
                raise ReferencePolicyError("catalog fields do not match its SHA-256")
        else:
            if any(
                value is not None
                for value in (
                    self.renderer_semantic_stream_sha256,
                    self.renderer_contract_sha256,
                    self.presentation_policy_sha256,
                    self.catalog_sha256,
                )
            ):
                raise ReferencePolicyError("unavailable catalog cannot claim verified hashes")
            if self.subtype_counts:
                raise ReferencePolicyError("unavailable catalog cannot expose class counts")


@dataclass(frozen=True, slots=True)
class FilterPanelCatalog:
    status: CatalogControlStatus
    reason: str
    filter_ids: tuple[FilterId, ...]


@dataclass(frozen=True, slots=True)
class FilterState:
    enabled: frozenset[FilterId]
    labels_master_enabled: bool
    outlines_master_enabled: bool

    def __post_init__(self) -> None:
        if not isinstance(self.enabled, frozenset) or any(
            not isinstance(item, FilterId) for item in self.enabled
        ):
            raise ReferencePolicyError("stored filters must be a frozen set of stable IDs")
        if type(self.labels_master_enabled) is not bool:
            raise ReferencePolicyError("label master state must be Boolean")
        if type(self.outlines_master_enabled) is not bool:
            raise ReferencePolicyError("outline master state must be Boolean")

    @classmethod
    def defaults(cls) -> "FilterState":
        return cls(
            enabled=frozenset(FilterId),
            labels_master_enabled=True,
            outlines_master_enabled=True,
        )

    def stored_enabled(self, filter_id: FilterId) -> bool:
        _require_filter_id(filter_id)
        return filter_id in self.enabled

    def effectively_enabled(self, filter_id: FilterId) -> bool:
        spec = filter_spec(filter_id)
        master_enabled = (
            self.labels_master_enabled
            if spec.kind is FilterKind.LABEL
            else self.outlines_master_enabled
        )
        return master_enabled and self.stored_enabled(filter_id)

    def with_filter(self, filter_id: FilterId, enabled: bool) -> "FilterState":
        _require_filter_id(filter_id)
        if type(enabled) is not bool:
            raise ReferencePolicyError("filter enabled state must be Boolean")
        next_enabled = set(self.enabled)
        if enabled:
            next_enabled.add(filter_id)
        else:
            next_enabled.discard(filter_id)
        return replace(self, enabled=frozenset(next_enabled))

    def with_labels_master(self, enabled: bool) -> "FilterState":
        if type(enabled) is not bool:
            raise ReferencePolicyError("label master state must be Boolean")
        return replace(self, labels_master_enabled=enabled)

    def with_outlines_master(self, enabled: bool) -> "FilterState":
        if type(enabled) is not bool:
            raise ReferencePolicyError("outline master state must be Boolean")
        return replace(self, outlines_master_enabled=enabled)


_FILTER_SPECS: tuple[FilterSpec, ...] = (
    FilterSpec(
        FilterId.LABELS_REGIONS,
        "Regions",
        FilterKind.LABEL,
        (
            SemanticSubtype.COUNTRY_TERRITORY,
            SemanticSubtype.FIRST_ORDER_REGION,
            SemanticSubtype.SECOND_LOCAL_REGION,
        ),
    ),
    FilterSpec(
        FilterId.LABELS_PLACES,
        "Places",
        FilterKind.LABEL,
        (
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        ),
    ),
    FilterSpec(
        FilterId.LABELS_ISLANDS,
        "Islands",
        FilterKind.LABEL,
        (SemanticSubtype.ISLAND_ISLET,),
    ),
    FilterSpec(
        FilterId.LABELS_MAJOR_WATER,
        "Oceans, bays & lakes",
        FilterKind.LABEL,
        (
            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
        ),
    ),
    FilterSpec(
        FilterId.LABELS_RIVERS,
        "Rivers",
        FilterKind.LABEL,
        (SemanticSubtype.RIVER,),
    ),
    FilterSpec(
        FilterId.LABELS_STREAMS,
        "Streams & creeks",
        FilterKind.LABEL,
        (
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
        ),
    ),
    FilterSpec(
        FilterId.LABELS_CANALS,
        "Canals & channels",
        FilterKind.LABEL,
        (SemanticSubtype.CANAL_CHANNEL,),
    ),
    FilterSpec(
        FilterId.LABELS_PROTECTED_LANDS,
        "Protected lands",
        FilterKind.LABEL,
        (SemanticSubtype.PROTECTED_LAND,),
    ),
    FilterSpec(
        FilterId.OUTLINES_COASTLINES,
        "Coastlines",
        FilterKind.OUTLINE,
        (SemanticSubtype.COASTLINE,),
    ),
    FilterSpec(
        FilterId.OUTLINES_INTERNATIONAL,
        "International borders",
        FilterKind.OUTLINE,
        (SemanticSubtype.INTERNATIONAL_BOUNDARY,),
    ),
    FilterSpec(
        FilterId.OUTLINES_STATE_PROVINCE,
        "State & province borders",
        FilterKind.OUTLINE,
        (SemanticSubtype.STATE_PROVINCE_BOUNDARY,),
    ),
    FilterSpec(
        FilterId.OUTLINES_COUNTY_LOCAL,
        "County & local borders",
        FilterKind.OUTLINE,
        (SemanticSubtype.COUNTY_LOCAL_BOUNDARY,),
    ),
    FilterSpec(
        FilterId.OUTLINES_PROTECTED_AREAS,
        "Protected-area outlines",
        FilterKind.OUTLINE,
        (SemanticSubtype.PROTECTED_AREA_OUTLINE,),
    ),
    FilterSpec(
        FilterId.OUTLINES_WATER_BOUNDARIES,
        "Water boundaries",
        FilterKind.OUTLINE,
        (SemanticSubtype.WATERSHED_WATER_BOUNDARY,),
    ),
    FilterSpec(
        FilterId.OUTLINES_OTHER,
        "Other sourced outlines",
        FilterKind.OUTLINE,
        (
            SemanticSubtype.OTHER_ADMIN_BOUNDARY,
            SemanticSubtype.OTHER_SOURCED_OUTLINE,
        ),
    ),
)

_FILTER_SPEC_BY_ID = {spec.filter_id: spec for spec in _FILTER_SPECS}


def _label_style(
    family: StyleFamily,
    *,
    color_token: str,
    font_slant: str,
    font_weight: int,
    letter_spacing_milli_em: int,
) -> StyleSpec:
    return StyleSpec(
        family=family,
        color_token=color_token,
        halo_token="reference.label_halo",
        font_slant=font_slant,
        font_weight=font_weight,
        letter_spacing_milli_em=letter_spacing_milli_em,
        line_pattern="none",
        line_width_milli_dp=0,
    )


def _outline_style(
    family: StyleFamily,
    *,
    color_token: str,
    line_pattern: str,
    line_width_milli_dp: int,
) -> StyleSpec:
    return StyleSpec(
        family=family,
        color_token=color_token,
        halo_token="reference.outline_halo",
        font_slant="not_applicable",
        font_weight=0,
        letter_spacing_milli_em=0,
        line_pattern=line_pattern,
        line_width_milli_dp=line_width_milli_dp,
    )


_STYLE_BY_SUBTYPE: dict[SemanticSubtype, StyleSpec] = {
    SemanticSubtype.COUNTRY_TERRITORY: _label_style(
        StyleFamily.REGION_COUNTRY,
        color_token="reference.region_country",
        font_slant="normal",
        font_weight=700,
        letter_spacing_milli_em=45,
    ),
    SemanticSubtype.FIRST_ORDER_REGION: _label_style(
        StyleFamily.REGION_FIRST_ORDER,
        color_token="reference.region_first_order",
        font_slant="normal",
        font_weight=600,
        letter_spacing_milli_em=35,
    ),
    SemanticSubtype.SECOND_LOCAL_REGION: _label_style(
        StyleFamily.REGION_LOCAL,
        color_token="reference.region_local",
        font_slant="normal",
        font_weight=500,
        letter_spacing_milli_em=25,
    ),
    SemanticSubtype.CAPITAL_MAJOR_CITY: _label_style(
        StyleFamily.PLACE_MAJOR,
        color_token="reference.place_major",
        font_slant="normal",
        font_weight=700,
        letter_spacing_milli_em=10,
    ),
    SemanticSubtype.CITY_TOWN: _label_style(
        StyleFamily.PLACE,
        color_token="reference.place",
        font_slant="normal",
        font_weight=600,
        letter_spacing_milli_em=5,
    ),
    SemanticSubtype.LOCAL_PLACE: _label_style(
        StyleFamily.PLACE_LOCAL,
        color_token="reference.place_local",
        font_slant="normal",
        font_weight=500,
        letter_spacing_milli_em=0,
    ),
    SemanticSubtype.ISLAND_ISLET: _label_style(
        StyleFamily.ISLAND,
        color_token="reference.island",
        font_slant="normal",
        font_weight=500,
        letter_spacing_milli_em=50,
    ),
    SemanticSubtype.OCEAN_SEA: _label_style(
        StyleFamily.WATER_OCEAN,
        color_token="reference.water_ocean",
        font_slant="italic",
        font_weight=500,
        letter_spacing_milli_em=50,
    ),
    SemanticSubtype.BAY_SOUND: _label_style(
        StyleFamily.WATER_BAY,
        color_token="reference.water_bay",
        font_slant="italic",
        font_weight=400,
        letter_spacing_milli_em=45,
    ),
    SemanticSubtype.LAKE_RESERVOIR: _label_style(
        StyleFamily.WATER_LAKE,
        color_token="reference.water_lake",
        font_slant="italic",
        font_weight=400,
        letter_spacing_milli_em=35,
    ),
    SemanticSubtype.RIVER: _label_style(
        StyleFamily.RIVER,
        color_token="reference.water_river",
        font_slant="italic",
        font_weight=400,
        letter_spacing_milli_em=70,
    ),
    SemanticSubtype.STREAM_CREEK: _label_style(
        StyleFamily.STREAM,
        color_token="reference.water_stream",
        font_slant="italic",
        font_weight=400,
        letter_spacing_milli_em=45,
    ),
    SemanticSubtype.CANAL_CHANNEL: _label_style(
        StyleFamily.CANAL,
        color_token="reference.water_canal",
        font_slant="normal",
        font_weight=500,
        letter_spacing_milli_em=35,
    ),
    SemanticSubtype.UNSPECIFIED_WATERCOURSE: _label_style(
        StyleFamily.WATERCOURSE_UNSPECIFIED,
        color_token="reference.water_unspecified",
        font_slant="italic",
        font_weight=400,
        letter_spacing_milli_em=40,
    ),
    SemanticSubtype.PROTECTED_LAND: _label_style(
        StyleFamily.PROTECTED_LAND,
        color_token="reference.protected_land",
        font_slant="italic",
        font_weight=500,
        letter_spacing_milli_em=35,
    ),
    SemanticSubtype.COASTLINE: _outline_style(
        StyleFamily.COASTLINE,
        color_token="reference.coastline",
        line_pattern="solid",
        line_width_milli_dp=900,
    ),
    SemanticSubtype.INTERNATIONAL_BOUNDARY: _outline_style(
        StyleFamily.INTERNATIONAL_BOUNDARY,
        color_token="reference.boundary_international",
        line_pattern="long_dash",
        line_width_milli_dp=1_100,
    ),
    SemanticSubtype.STATE_PROVINCE_BOUNDARY: _outline_style(
        StyleFamily.STATE_PROVINCE_BOUNDARY,
        color_token="reference.boundary_state_province",
        line_pattern="solid",
        line_width_milli_dp=850,
    ),
    SemanticSubtype.COUNTY_LOCAL_BOUNDARY: _outline_style(
        StyleFamily.COUNTY_LOCAL_BOUNDARY,
        color_token="reference.boundary_county_local",
        line_pattern="short_dash",
        line_width_milli_dp=650,
    ),
    SemanticSubtype.OTHER_ADMIN_BOUNDARY: _outline_style(
        StyleFamily.OTHER_ADMIN_BOUNDARY,
        color_token="reference.boundary_other_admin",
        line_pattern="dot",
        line_width_milli_dp=600,
    ),
    SemanticSubtype.PROTECTED_AREA_OUTLINE: _outline_style(
        StyleFamily.PROTECTED_AREA_OUTLINE,
        color_token="reference.outline_protected_area",
        line_pattern="dash_dot",
        line_width_milli_dp=700,
    ),
    SemanticSubtype.WATERSHED_WATER_BOUNDARY: _outline_style(
        StyleFamily.WATERSHED_WATER_BOUNDARY,
        color_token="reference.outline_water_boundary",
        line_pattern="short_dash",
        line_width_milli_dp=700,
    ),
    SemanticSubtype.OTHER_SOURCED_OUTLINE: _outline_style(
        StyleFamily.OTHER_SOURCED_OUTLINE,
        color_token="reference.outline_other",
        line_pattern="dot",
        line_width_milli_dp=600,
    ),
}


def _resolved_label(color_argb: int, alpha_milli: int) -> ResolvedStyleDetails:
    return ResolvedStyleDetails(
        color_argb=color_argb,
        alpha_milli=alpha_milli,
        halo_argb=0xFF071419,
        halo_alpha_milli=920,
        halo_width_milli_em=220,
        line_halo_width_milli_dp=0,
        dash_milli_dp=(),
        dash_phase_milli_dp=0,
        line_cap="round",
        line_join="round",
    )


def _resolved_outline(
    color_argb: int,
    alpha_milli: int,
    *,
    dash_milli_dp: tuple[int, ...],
) -> ResolvedStyleDetails:
    return ResolvedStyleDetails(
        color_argb=color_argb,
        alpha_milli=alpha_milli,
        halo_argb=0xFF061013,
        halo_alpha_milli=420,
        halo_width_milli_em=0,
        line_halo_width_milli_dp=350,
        dash_milli_dp=dash_milli_dp,
        dash_phase_milli_dp=0,
        line_cap="round",
        line_join="round",
    )


_RESOLVED_STYLE_BY_SUBTYPE: dict[SemanticSubtype, ResolvedStyleDetails] = {
    SemanticSubtype.COUNTRY_TERRITORY: _resolved_label(0xFFF4F7FA, 940),
    SemanticSubtype.FIRST_ORDER_REGION: _resolved_label(0xFFE5EDF4, 900),
    SemanticSubtype.SECOND_LOCAL_REGION: _resolved_label(0xFFD2DEE8, 840),
    SemanticSubtype.CAPITAL_MAJOR_CITY: _resolved_label(0xFFFFFFFF, 960),
    SemanticSubtype.CITY_TOWN: _resolved_label(0xFFF1F5F8, 920),
    SemanticSubtype.LOCAL_PLACE: _resolved_label(0xFFDDE5EA, 840),
    SemanticSubtype.ISLAND_ISLET: _resolved_label(0xFFE8DEC2, 880),
    SemanticSubtype.OCEAN_SEA: _resolved_label(0xFF8FD0F0, 900),
    SemanticSubtype.BAY_SOUND: _resolved_label(0xFFA4D8F1, 900),
    SemanticSubtype.LAKE_RESERVOIR: _resolved_label(0xFFB4E0F4, 880),
    SemanticSubtype.RIVER: _resolved_label(0xFFACDEF7, 920),
    SemanticSubtype.STREAM_CREEK: _resolved_label(0xFF91C7DF, 800),
    SemanticSubtype.CANAL_CHANNEL: _resolved_label(0xFF83BFD8, 820),
    SemanticSubtype.UNSPECIFIED_WATERCOURSE: _resolved_label(0xFF78AFC7, 760),
    SemanticSubtype.PROTECTED_LAND: _resolved_label(0xFFB2D5AE, 820),
    SemanticSubtype.COASTLINE: _resolved_outline(
        0xFFB7D4DE, 650, dash_milli_dp=()
    ),
    SemanticSubtype.INTERNATIONAL_BOUNDARY: _resolved_outline(
        0xFFDDE7ED, 800, dash_milli_dp=(6_000, 3_500)
    ),
    SemanticSubtype.STATE_PROVINCE_BOUNDARY: _resolved_outline(
        0xFFC7D3DB, 700, dash_milli_dp=()
    ),
    SemanticSubtype.COUNTY_LOCAL_BOUNDARY: _resolved_outline(
        0xFF9EAFBA, 550, dash_milli_dp=(3_000, 2_500)
    ),
    SemanticSubtype.OTHER_ADMIN_BOUNDARY: _resolved_outline(
        0xFF83949E, 450, dash_milli_dp=(1_000, 2_200)
    ),
    SemanticSubtype.PROTECTED_AREA_OUTLINE: _resolved_outline(
        0xFF91B68C, 600, dash_milli_dp=(5_000, 2_500, 1_000, 2_500)
    ),
    SemanticSubtype.WATERSHED_WATER_BOUNDARY: _resolved_outline(
        0xFF7FB2C8, 550, dash_milli_dp=(2_500, 2_000)
    ),
    SemanticSubtype.OTHER_SOURCED_OUTLINE: _resolved_outline(
        0xFF8E9EA7, 450, dash_milli_dp=(1_000, 2_500)
    ),
}


_WATERCOURSE_SUBTYPES = frozenset(
    {
        SemanticSubtype.RIVER,
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.CANAL_CHANNEL,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE,
    }
)

def _water_visibility_rule(
    rule_id: str,
    minimum: int,
    full: int,
    size: int,
    spacing: int,
) -> LabelVisibilityRule:
    return LabelVisibilityRule(
        rule_id=rule_id,
        min_zoom_centi=minimum,
        full_alpha_zoom_centi=full,
        text_size_milli_sp=size,
        letter_spacing_milli_em=spacing,
    )


_RIVER_RULES = {
    ProminenceTier.GLOBAL_MAJOR: _water_visibility_rule(
        "water.river.global_major", 550, 585, 11_000, 70
    ),
    ProminenceTier.REGIONAL_MAJOR: _water_visibility_rule(
        "water.river.regional_major", 593, 628, 10_500, 70
    ),
    ProminenceTier.LOCAL: _water_visibility_rule(
        "water.river.local", 688, 718, 9_750, 70
    ),
    ProminenceTier.FINE: _water_visibility_rule(
        "water.river.fine", 748, 783, 9_250, 70
    ),
}
_STREAM_RULES = {
    ProminenceTier.GLOBAL_MAJOR: _water_visibility_rule(
        "water.stream_creek.global_major", 668, 698, 9_750, 45
    ),
    ProminenceTier.REGIONAL_MAJOR: _water_visibility_rule(
        "water.stream_creek.regional_major", 708, 738, 9_500, 45
    ),
    ProminenceTier.LOCAL: _water_visibility_rule(
        "water.stream_creek.local", 748, 778, 9_250, 45
    ),
    ProminenceTier.FINE: _water_visibility_rule(
        "water.stream_creek.fine", 778, 813, 9_000, 45
    ),
}
_CANAL_RULES = {
    ProminenceTier.GLOBAL_MAJOR: _water_visibility_rule(
        "water.canal_channel.global_major", 668, 698, 9_750, 35
    ),
    ProminenceTier.REGIONAL_MAJOR: _water_visibility_rule(
        "water.canal_channel.regional_major", 698, 728, 9_500, 35
    ),
    ProminenceTier.LOCAL: _water_visibility_rule(
        "water.canal_channel.local", 728, 758, 9_250, 35
    ),
    ProminenceTier.FINE: _water_visibility_rule(
        "water.canal_channel.fine", 778, 808, 8_750, 35
    ),
}
_UNSPECIFIED_WATERCOURSE_RULES = {
    ProminenceTier.GLOBAL_MAJOR: _water_visibility_rule(
        "water.unspecified_course.global_major", 708, 738, 9_500, 40
    ),
    ProminenceTier.REGIONAL_MAJOR: _water_visibility_rule(
        "water.unspecified_course.regional_major", 738, 768, 9_250, 40
    ),
    ProminenceTier.LOCAL: _water_visibility_rule(
        "water.unspecified_course.local", 768, 798, 9_000, 40
    ),
    ProminenceTier.FINE: _water_visibility_rule(
        "water.unspecified_course.fine", 798, 833, 8_750, 40
    ),
}

_GENERIC_VISIBILITY_BANDS: dict[
    str, dict[ProminenceTier, tuple[int, int, int]]
] = {
    "region": {
        ProminenceTier.GLOBAL_MAJOR: (250, 300, 12_000),
        ProminenceTier.REGIONAL_MAJOR: (450, 500, 10_500),
        ProminenceTier.LOCAL: (700, 740, 9_250),
        ProminenceTier.FINE: (800, 840, 8_750),
    },
    "place": {
        ProminenceTier.GLOBAL_MAJOR: (425, 460, 11_500),
        ProminenceTier.REGIONAL_MAJOR: (525, 560, 10_750),
        ProminenceTier.LOCAL: (650, 690, 9_750),
        ProminenceTier.FINE: (775, 815, 9_000),
    },
    "island": {
        ProminenceTier.GLOBAL_MAJOR: (450, 490, 10_750),
        ProminenceTier.REGIONAL_MAJOR: (575, 615, 10_000),
        ProminenceTier.LOCAL: (700, 740, 9_250),
        ProminenceTier.FINE: (825, 865, 8_750),
    },
    "water_area": {
        ProminenceTier.GLOBAL_MAJOR: (250, 300, 12_000),
        ProminenceTier.REGIONAL_MAJOR: (500, 540, 10_500),
        ProminenceTier.LOCAL: (650, 690, 9_500),
        ProminenceTier.FINE: (800, 840, 8_750),
    },
    "protected_land": {
        ProminenceTier.GLOBAL_MAJOR: (550, 590, 10_000),
        ProminenceTier.REGIONAL_MAJOR: (650, 690, 9_500),
        ProminenceTier.LOCAL: (750, 790, 9_000),
        ProminenceTier.FINE: (850, 890, 8_750),
    },
}

_VISIBILITY_FAMILY_BY_SUBTYPE: dict[SemanticSubtype, str] = {
    SemanticSubtype.COUNTRY_TERRITORY: "region",
    SemanticSubtype.FIRST_ORDER_REGION: "region",
    SemanticSubtype.SECOND_LOCAL_REGION: "region",
    SemanticSubtype.CAPITAL_MAJOR_CITY: "place",
    SemanticSubtype.CITY_TOWN: "place",
    SemanticSubtype.LOCAL_PLACE: "place",
    SemanticSubtype.ISLAND_ISLET: "island",
    SemanticSubtype.OCEAN_SEA: "water_area",
    SemanticSubtype.BAY_SOUND: "water_area",
    SemanticSubtype.LAKE_RESERVOIR: "water_area",
    SemanticSubtype.PROTECTED_LAND: "protected_land",
}

_OUTLINE_RULES: dict[SemanticSubtype, OutlineVisibilityRule] = {
    SemanticSubtype.COASTLINE: OutlineVisibilityRule(
        "outline.coastline", 300, 350, 10_000, 10_000, 10, 0
    ),
    SemanticSubtype.INTERNATIONAL_BOUNDARY: OutlineVisibilityRule(
        "outline.international", 300, 350, 10_000, 10_000, 20, 1
    ),
    SemanticSubtype.STATE_PROVINCE_BOUNDARY: OutlineVisibilityRule(
        "outline.state_province", 450, 500, 10_000, 10_000, 21, 2
    ),
    SemanticSubtype.COUNTY_LOCAL_BOUNDARY: OutlineVisibilityRule(
        "outline.county_local", 650, 700, 10_000, 10_000, 22, 3
    ),
    SemanticSubtype.OTHER_ADMIN_BOUNDARY: OutlineVisibilityRule(
        "outline.other_admin", 750, 800, 10_000, 10_000, 23, 6
    ),
    SemanticSubtype.PROTECTED_AREA_OUTLINE: OutlineVisibilityRule(
        "outline.protected_area", 700, 750, 10_000, 10_000, 15, 4
    ),
    SemanticSubtype.WATERSHED_WATER_BOUNDARY: OutlineVisibilityRule(
        "outline.water_boundary", 750, 800, 10_000, 10_000, 16, 5
    ),
    SemanticSubtype.OTHER_SOURCED_OUTLINE: OutlineVisibilityRule(
        "outline.other", 800, 850, 10_000, 10_000, 17, 7
    ),
}


def _validate_static_tables() -> None:
    if set(_FILTER_SPEC_BY_ID) != set(FilterId):
        raise RuntimeError("reference filter table does not cover every stable filter ID")
    memberships: list[SemanticSubtype] = []
    for spec in _FILTER_SPECS:
        for subtype in spec.subtypes:
            if (subtype.value < 500) != (spec.kind is FilterKind.LABEL):
                raise RuntimeError("reference filter mixes label and outline subtypes")
            memberships.append(subtype)
    if len(memberships) != len(set(memberships)) or set(memberships) != set(
        SemanticSubtype
    ):
        raise RuntimeError("semantic subtypes must form one exact filter partition")
    if set(_STYLE_BY_SUBTYPE) != set(SemanticSubtype):
        raise RuntimeError("reference style table does not cover every semantic subtype")
    families = [spec.family for spec in _STYLE_BY_SUBTYPE.values()]
    if len(families) != len(set(families)):
        raise RuntimeError("each semantic subtype must have a distinct style family")
    if set(_RESOLVED_STYLE_BY_SUBTYPE) != set(SemanticSubtype):
        raise RuntimeError("resolved style table does not cover every semantic subtype")
    if len(set(_RESOLVED_STYLE_BY_SUBTYPE.values())) != len(SemanticSubtype):
        raise RuntimeError("each semantic subtype must resolve to a distinct visible style")
    label_subtypes = {subtype for subtype in SemanticSubtype if subtype.value < 500}
    outline_subtypes = set(SemanticSubtype).difference(label_subtypes)
    if set(SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE) != label_subtypes:
        raise RuntimeError("semantic-priority table must cover every label subtype")
    within_tier_priorities = tuple(
        SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE.values()
    )
    if (
        len(set(within_tier_priorities)) != len(within_tier_priorities)
        or any(
            type(value) is not int
            or not 0 <= value < _SEMANTIC_PRIORITY_TIER_STRIDE
            for value in within_tier_priorities
        )
    ):
        raise RuntimeError(
            "within-tier semantic priorities must be unique inside the tier stride"
        )
    if set(_OUTLINE_RULES) != outline_subtypes:
        raise RuntimeError("outline visibility table must cover every outline subtype")
    if PROMINENCE_TIER_CODE != {
        ProminenceTier.GLOBAL_MAJOR: 0,
        ProminenceTier.REGIONAL_MAJOR: 1,
        ProminenceTier.LOCAL: 2,
        ProminenceTier.FINE: 3,
    }:
        raise RuntimeError("prominence tier codes drifted")


_validate_static_tables()


def _require_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or _SHA256_RE.fullmatch(value) is None:
        raise ReferencePolicyError(f"{label} must be 64 lowercase hexadecimal characters")
    return value


def _prominence_rule_id(
    subtype: SemanticSubtype,
    tier: ProminenceTier,
    evidence_kind: ProminenceEvidenceKind,
) -> int:
    if subtype not in SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE:
        raise ReferencePolicyError("prominence rule requires a label subtype")
    if not isinstance(tier, ProminenceTier) or not isinstance(
        evidence_kind, ProminenceEvidenceKind
    ):
        raise ReferencePolicyError("prominence rule uses unknown typed values")
    preimage = struct.pack(
        "<IBB",
        subtype.value,
        PROMINENCE_TIER_CODE[tier],
        evidence_kind.value,
    )
    digest = hashlib.sha256(b"FAE8RULE1\0" + preimage).digest()
    return int.from_bytes(digest[:8], "big")


def complete_geometry_measure_bucket(
    measure: int | None,
    *,
    verified: bool,
) -> int:
    if type(verified) is not bool:
        raise ReferencePolicyError("geometry-measure verification flag must be Boolean")
    if not verified:
        return 0
    if measure is None or type(measure) is not int or not 0 <= measure <= _I64_MAX:
        raise ReferencePolicyError(
            "verified complete geometry measure must be nonnegative signed 64-bit"
        )
    if measure == 0:
        return 0
    exponent = measure.bit_length() - 1
    base = 1 << exponent
    fractional = ((measure - base) * 1_024) // base
    return min((1 << 16) - 1, 1 + exponent * 1_024 + fractional)


def _population_tier(population: int) -> ProminenceTier:
    if population >= GLOBAL_CITY_POPULATION_MIN:
        return ProminenceTier.GLOBAL_MAJOR
    if population >= REGIONAL_CITY_POPULATION_MIN:
        return ProminenceTier.REGIONAL_MAJOR
    if population >= LOCAL_CITY_POPULATION_MIN:
        return ProminenceTier.LOCAL
    return ProminenceTier.FINE


def _complete_area_tier(
    subtype: SemanticSubtype,
    area_m2: int,
) -> ProminenceTier:
    if subtype is SemanticSubtype.ISLAND_ISLET:
        return _tier_from_verified_area(
            area_m2,
            global_min=GLOBAL_ISLAND_AREA_M2_MIN,
            regional_min=REGIONAL_ISLAND_AREA_M2_MIN,
            local_min=LOCAL_ISLAND_AREA_M2_MIN,
        )
    if subtype in (SemanticSubtype.BAY_SOUND, SemanticSubtype.LAKE_RESERVOIR):
        return _tier_from_verified_area(
            area_m2,
            global_min=GLOBAL_WATER_AREA_M2_MIN,
            regional_min=REGIONAL_WATER_AREA_M2_MIN,
            local_min=LOCAL_WATER_AREA_M2_MIN,
        )
    if subtype is SemanticSubtype.PROTECTED_LAND:
        return _tier_from_verified_area(
            area_m2,
            global_min=None,
            regional_min=REGIONAL_PROTECTED_AREA_M2_MIN,
            local_min=LOCAL_PROTECTED_AREA_M2_MIN,
        )
    raise ReferencePolicyError("complete-area evidence is invalid for this subtype")


def _complete_relation_length_tier(
    subtype: SemanticSubtype,
    length_m: int,
) -> ProminenceTier:
    if subtype is SemanticSubtype.RIVER:
        if length_m >= GLOBAL_RIVER_COMPLETE_RELATION_MIN_LENGTH_M:
            return ProminenceTier.GLOBAL_MAJOR
        if length_m >= MAJOR_RIVER_COMPLETE_RELATION_MIN_LENGTH_M:
            return ProminenceTier.REGIONAL_MAJOR
        if length_m >= LOCAL_RIVER_COMPLETE_RELATION_MIN_LENGTH_M:
            return ProminenceTier.LOCAL
        return ProminenceTier.FINE
    if subtype in (
        SemanticSubtype.STREAM_CREEK,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE,
    ):
        return ProminenceTier.FINE
    if subtype is SemanticSubtype.CANAL_CHANNEL:
        return ProminenceTier.LOCAL
    raise ReferencePolicyError("relation-length evidence is invalid for this subtype")


def default_prominence_for_subtype(
    subtype: SemanticSubtype,
) -> ProminenceTier:
    defaults = {
        SemanticSubtype.COUNTRY_TERRITORY: ProminenceTier.GLOBAL_MAJOR,
        SemanticSubtype.FIRST_ORDER_REGION: ProminenceTier.REGIONAL_MAJOR,
        SemanticSubtype.SECOND_LOCAL_REGION: ProminenceTier.LOCAL,
        SemanticSubtype.CAPITAL_MAJOR_CITY: ProminenceTier.REGIONAL_MAJOR,
        SemanticSubtype.CITY_TOWN: ProminenceTier.LOCAL,
        SemanticSubtype.LOCAL_PLACE: ProminenceTier.FINE,
        SemanticSubtype.ISLAND_ISLET: ProminenceTier.FINE,
        SemanticSubtype.OCEAN_SEA: ProminenceTier.GLOBAL_MAJOR,
        SemanticSubtype.BAY_SOUND: ProminenceTier.LOCAL,
        SemanticSubtype.LAKE_RESERVOIR: ProminenceTier.LOCAL,
        SemanticSubtype.RIVER: ProminenceTier.FINE,
        SemanticSubtype.STREAM_CREEK: ProminenceTier.FINE,
        SemanticSubtype.CANAL_CHANNEL: ProminenceTier.LOCAL,
        SemanticSubtype.UNSPECIFIED_WATERCOURSE: ProminenceTier.FINE,
        SemanticSubtype.PROTECTED_LAND: ProminenceTier.FINE,
    }
    if subtype not in defaults:
        raise ReferencePolicyError("default prominence requires a label subtype")
    return defaults[subtype]


def _validate_prominence_decision_semantics(
    decision: ProminenceDecision,
) -> None:
    kind = decision.evidence_kind
    subtype = decision.subtype
    value = decision.evidence_value
    expected_bucket = 0
    if kind is ProminenceEvidenceKind.PROVIDER_RANK:
        if decision.provider_rank != value:
            raise ReferencePolicyError(
                "provider prominence evidence value must equal provider rank"
            )
        expected_tier = decision.tier
    elif kind is ProminenceEvidenceKind.CAPITAL_LEVEL:
        if subtype not in (
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        ) or value not in (1, 2):
            raise ReferencePolicyError("capital prominence evidence is impossible")
        expected_tier = (
            ProminenceTier.GLOBAL_MAJOR
            if value == 2
            else ProminenceTier.REGIONAL_MAJOR
        )
    elif kind is ProminenceEvidenceKind.POPULATION:
        if subtype not in (
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        ) or value < 0:
            raise ReferencePolicyError("population prominence evidence is impossible")
        expected_tier = _population_tier(value)
    elif kind is ProminenceEvidenceKind.COMPLETE_AREA_M2:
        if value < 0:
            raise ReferencePolicyError("complete-area prominence evidence is impossible")
        expected_tier = _complete_area_tier(subtype, value)
        expected_bucket = complete_geometry_measure_bucket(value, verified=True)
    elif kind is ProminenceEvidenceKind.COMPLETE_RELATION_LENGTH_M:
        if value < 0:
            raise ReferencePolicyError(
                "relation-length prominence evidence is impossible"
            )
        expected_tier = _complete_relation_length_tier(subtype, value)
        expected_bucket = complete_geometry_measure_bucket(value, verified=True)
    else:
        if value != subtype.value:
            raise ReferencePolicyError(
                "typed-default evidence value must equal the semantic subtype"
            )
        expected_tier = default_prominence_for_subtype(subtype)
    if decision.tier is not expected_tier:
        raise ReferencePolicyError("prominence decision tier contradicts its evidence")
    if decision.complete_geometry_measure_bucket != expected_bucket:
        raise ReferencePolicyError(
            "prominence decision measure bucket contradicts its evidence"
        )


def canonical_prominence_decision_bytes(decision: ProminenceDecision) -> bytes:
    if not isinstance(decision, ProminenceDecision):
        raise ReferencePolicyError("prominence encoder requires ProminenceDecision")
    output = bytearray(b"FAE8PDEC1\0")
    output.extend(bytes.fromhex(decision.policy_sha256))
    output.extend(struct.pack("<I", decision.subtype.value))
    output.extend(struct.pack("<i", decision.semantic_priority))
    output.extend(struct.pack("<B", PROMINENCE_TIER_CODE[decision.tier]))
    output.extend(struct.pack("<?", decision.provider_rank is not None))
    if decision.provider_rank is not None:
        output.extend(struct.pack("<i", decision.provider_rank))
    output.extend(struct.pack("<H", decision.complete_geometry_measure_bucket))
    output.extend(struct.pack("<Q", decision.prominence_rule_id))
    output.extend(struct.pack("<B", decision.evidence_kind.value))
    output.extend(struct.pack("<q", decision.evidence_value))
    output.extend(bytes.fromhex(decision.source_generation_sha256))
    output.extend(bytes.fromhex(decision.classifier_sha256))
    output.extend(struct.pack("<Q", decision.source_field_id))
    return bytes(output)


def prominence_decision_sha256(decision: ProminenceDecision) -> str:
    return hashlib.sha256(canonical_prominence_decision_bytes(decision)).hexdigest()


def canonical_class_catalog_bytes(
    *,
    renderer_semantic_stream_sha256: str,
    renderer_contract_sha256: str,
    presentation_policy_sha256: str,
    subtype_counts: Mapping[SemanticSubtype, SubtypeCatalogCounts],
) -> bytes:
    semantic_stream = _require_sha256(
        renderer_semantic_stream_sha256,
        "renderer semantic stream SHA-256",
    )
    renderer_contract = _require_sha256(
        renderer_contract_sha256,
        "renderer contract SHA-256",
    )
    presentation_policy = _require_sha256(
        presentation_policy_sha256,
        "presentation policy SHA-256",
    )
    if presentation_policy != PRESENTATION_POLICY_SHA256:
        raise ReferencePolicyError("catalog presentation policy is unsupported")
    if not isinstance(subtype_counts, Mapping):
        raise ReferencePolicyError("catalog subtype counts must be a mapping")
    if set(subtype_counts) != set(SemanticSubtype):
        raise ReferencePolicyError("catalog must contain the exact subtype set")
    output = bytearray(_CATALOG_DOMAIN)
    output.append(_CATALOG_VERSION)
    output.extend(bytes.fromhex(semantic_stream))
    output.extend(bytes.fromhex(renderer_contract))
    output.extend(bytes.fromhex(presentation_policy))
    output.extend(struct.pack("<I", len(SemanticSubtype)))
    for subtype in SemanticSubtype:
        counts = subtype_counts[subtype]
        if not isinstance(counts, SubtypeCatalogCounts):
            raise ReferencePolicyError("catalog has unsupported subtype counts")
        output.extend(
            struct.pack(
                "<IQQQ",
                subtype.value,
                counts.distinct_feature_count,
                counts.canonical_variant_count,
                counts.posting_count,
            )
        )
    return bytes(output)


def _decode_class_catalog_bytes(
    catalog_bytes: bytes,
) -> tuple[
    str,
    str,
    str,
    tuple[tuple[SemanticSubtype, SubtypeCatalogCounts], ...],
]:
    if type(catalog_bytes) is not bytes:
        raise ReferencePolicyError("catalog must be immutable exact bytes")
    prefix_size = len(_CATALOG_DOMAIN) + 1 + 32 * 3 + 4
    if len(catalog_bytes) < prefix_size:
        raise ReferencePolicyError("catalog bytes are truncated")
    offset = 0
    if catalog_bytes[: len(_CATALOG_DOMAIN)] != _CATALOG_DOMAIN:
        raise ReferencePolicyError("catalog domain is unknown")
    offset += len(_CATALOG_DOMAIN)
    version = catalog_bytes[offset]
    offset += 1
    if version != _CATALOG_VERSION:
        raise ReferencePolicyError(f"catalog version is unsupported: {version}")

    def digest() -> str:
        nonlocal offset
        value = catalog_bytes[offset : offset + 32]
        if len(value) != 32:
            raise ReferencePolicyError("catalog digest is truncated")
        offset += 32
        return value.hex()

    semantic_stream = digest()
    renderer_contract = digest()
    presentation_policy = digest()
    subtype_count = struct.unpack_from("<I", catalog_bytes, offset)[0]
    offset += 4
    if subtype_count != len(SemanticSubtype):
        raise ReferencePolicyError("catalog must contain the exact subtype set")
    expected_size = prefix_size + subtype_count * struct.calcsize("<IQQQ")
    if len(catalog_bytes) != expected_size:
        raise ReferencePolicyError("catalog byte length is noncanonical")
    normalized: list[tuple[SemanticSubtype, SubtypeCatalogCounts]] = []
    expected_subtypes = tuple(SemanticSubtype)
    for index in range(subtype_count):
        subtype_id, distinct, variants, postings = struct.unpack_from(
            "<IQQQ", catalog_bytes, offset
        )
        offset += struct.calcsize("<IQQQ")
        try:
            subtype = SemanticSubtype(subtype_id)
        except ValueError as error:
            raise ReferencePolicyError(
                f"catalog contains unknown semantic subtype {subtype_id}"
            ) from error
        if subtype is not expected_subtypes[index]:
            raise ReferencePolicyError(
                "catalog subtype entries must be exact and strictly ID-sorted"
            )
        normalized.append(
            (
                subtype,
                SubtypeCatalogCounts(
                    distinct_feature_count=distinct,
                    canonical_variant_count=variants,
                    posting_count=postings,
                ),
            )
        )
    return (
        semantic_stream,
        renderer_contract,
        presentation_policy,
        tuple(normalized),
    )


def _require_filter_id(value: object) -> FilterId:
    if not isinstance(value, FilterId):
        raise ReferencePolicyError("filter ID must be a known stable FilterId")
    return value


def filter_spec(filter_id: FilterId) -> FilterSpec:
    return _FILTER_SPEC_BY_ID[_require_filter_id(filter_id)]


def style_family_for_subtype(subtype: SemanticSubtype) -> StyleFamily:
    if not isinstance(subtype, SemanticSubtype):
        raise ReferencePolicyError("semantic subtype is unknown")
    return _STYLE_BY_SUBTYPE[subtype].family


def style_spec_for_subtype(subtype: SemanticSubtype) -> StyleSpec:
    if not isinstance(subtype, SemanticSubtype):
        raise ReferencePolicyError("semantic subtype is unknown")
    return _STYLE_BY_SUBTYPE[subtype]


def resolved_style_for_subtype(subtype: SemanticSubtype) -> ResolvedStyleDetails:
    if not isinstance(subtype, SemanticSubtype):
        raise ReferencePolicyError("semantic subtype is unknown")
    return _RESOLVED_STYLE_BY_SUBTYPE[subtype]


def outline_visibility_rule(subtype: SemanticSubtype) -> OutlineVisibilityRule:
    if not isinstance(subtype, SemanticSubtype) or subtype.value < 500:
        raise ReferencePolicyError("outline visibility requires an outline subtype")
    return _OUTLINE_RULES[subtype]


def available_filter_catalog(
    catalog: ReferenceClassCatalog | None,
) -> FilterPanelCatalog:
    if catalog is None:
        return FilterPanelCatalog(
            status=CatalogControlStatus.UNAVAILABLE,
            reason="reference_package_catalog_unavailable",
            filter_ids=(),
        )
    if not isinstance(catalog, ReferenceClassCatalog):
        raise ReferencePolicyError("reference class catalog has the wrong type")
    if catalog.status is CatalogControlStatus.UNAVAILABLE:
        return FilterPanelCatalog(catalog.status, catalog.reason, ())
    counts = dict(catalog.subtype_counts)
    available = tuple(
        spec.filter_id
        for spec in _FILTER_SPECS
        if sum(
            counts[subtype].distinct_feature_count for subtype in spec.subtypes
        )
        > 0
    )
    return FilterPanelCatalog(
        status=CatalogControlStatus.AVAILABLE,
        reason="verified",
        filter_ids=available,
    )


def _tier_from_verified_area(
    area_m2: int,
    *,
    global_min: int | None,
    regional_min: int,
    local_min: int,
) -> ProminenceTier:
    if global_min is not None and area_m2 >= global_min:
        return ProminenceTier.GLOBAL_MAJOR
    if area_m2 >= regional_min:
        return ProminenceTier.REGIONAL_MAJOR
    if area_m2 >= local_min:
        return ProminenceTier.LOCAL
    return ProminenceTier.FINE


def prominence_for_label(facts: LabelFacts) -> ProminenceTier:
    if not isinstance(facts, LabelFacts):
        raise ReferencePolicyError("label prominence requires LabelFacts")
    if facts.provider_evidence is not None:
        return facts.provider_evidence.tier

    subtype = facts.subtype
    if subtype is SemanticSubtype.COUNTRY_TERRITORY:
        return ProminenceTier.GLOBAL_MAJOR
    if subtype is SemanticSubtype.FIRST_ORDER_REGION:
        return ProminenceTier.REGIONAL_MAJOR
    if subtype is SemanticSubtype.SECOND_LOCAL_REGION:
        return ProminenceTier.LOCAL

    if subtype in (
        SemanticSubtype.CAPITAL_MAJOR_CITY,
        SemanticSubtype.CITY_TOWN,
        SemanticSubtype.LOCAL_PLACE,
    ):
        evidence_tiers: list[ProminenceTier] = []
        if facts.capital_level_verified:
            if facts.capital_level is CapitalLevel.NATIONAL:
                evidence_tiers.append(ProminenceTier.GLOBAL_MAJOR)
            elif facts.capital_level is CapitalLevel.REGIONAL:
                evidence_tiers.append(ProminenceTier.REGIONAL_MAJOR)
        if facts.population_verified:
            assert facts.population is not None
            evidence_tiers.append(_population_tier(facts.population))
        if evidence_tiers:
            return min(evidence_tiers, key=_PROMINENCE_STRENGTH.__getitem__)
        return default_prominence_for_subtype(subtype)

    if subtype is SemanticSubtype.ISLAND_ISLET:
        if not facts.complete_area_verified:
            return ProminenceTier.FINE
        assert facts.complete_area_m2 is not None
        return _complete_area_tier(subtype, facts.complete_area_m2)

    if subtype is SemanticSubtype.OCEAN_SEA:
        return ProminenceTier.GLOBAL_MAJOR
    if subtype in (SemanticSubtype.BAY_SOUND, SemanticSubtype.LAKE_RESERVOIR):
        if not facts.complete_area_verified:
            return ProminenceTier.LOCAL
        assert facts.complete_area_m2 is not None
        return _complete_area_tier(subtype, facts.complete_area_m2)

    if subtype in _WATERCOURSE_SUBTYPES:
        return prominence_for_waterway(
            WaterwayFacts(
                subtype=subtype,
                complete_named_relation=facts.complete_named_relation,
                complete_relation_length_m=facts.complete_relation_length_m,
                evidence_context=facts.evidence_context,
                provider_evidence=facts.provider_evidence,
            )
        )

    if subtype is SemanticSubtype.PROTECTED_LAND:
        if not facts.complete_area_verified:
            return ProminenceTier.FINE
        assert facts.complete_area_m2 is not None
        return _complete_area_tier(subtype, facts.complete_area_m2)
    raise ReferencePolicyError("label subtype has no prominence policy")


def prominence_decision_for_label(facts: LabelFacts) -> ProminenceDecision:
    if not isinstance(facts, LabelFacts):
        raise ReferencePolicyError("prominence decision requires LabelFacts")
    provider = facts.provider_evidence
    if provider is not None:
        if (
            facts.population_verified
            or facts.capital_level_verified
            or facts.complete_area_verified
            or (
                facts.complete_named_relation
                and facts.complete_relation_length_m is not None
            )
        ):
            raise ReferencePolicyError(
                "provider prominence cannot mix unbound fallback evidence"
            )
        context = provider.context
        tier = provider.tier
        kind = ProminenceEvidenceKind.PROVIDER_RANK
        evidence_value = provider.raw_provider_rank
        provider_rank: int | None = provider.raw_provider_rank
    else:
        context = facts.evidence_context
        if context is None:
            raise ReferencePolicyError(
                "authoritative prominence decision requires source evidence context"
            )
        tier = prominence_for_label(facts)
        provider_rank = None
        subtype = facts.subtype
        if subtype in (
            SemanticSubtype.CAPITAL_MAJOR_CITY,
            SemanticSubtype.CITY_TOWN,
            SemanticSubtype.LOCAL_PLACE,
        ):
            candidates: list[
                tuple[ProminenceTier, ProminenceEvidenceKind, int]
            ] = []
            if facts.capital_level_verified:
                if facts.capital_level is CapitalLevel.NATIONAL:
                    candidates.append(
                        (
                            ProminenceTier.GLOBAL_MAJOR,
                            ProminenceEvidenceKind.CAPITAL_LEVEL,
                            2,
                        )
                    )
                elif facts.capital_level is CapitalLevel.REGIONAL:
                    candidates.append(
                        (
                            ProminenceTier.REGIONAL_MAJOR,
                            ProminenceEvidenceKind.CAPITAL_LEVEL,
                            1,
                        )
                    )
            if facts.population_verified:
                assert facts.population is not None
                candidates.append(
                    (
                        _population_tier(facts.population),
                        ProminenceEvidenceKind.POPULATION,
                        facts.population,
                    )
                )
            if candidates:
                tier, kind, evidence_value = min(
                    candidates,
                    key=lambda item: (
                        _PROMINENCE_STRENGTH[item[0]],
                        item[1].value,
                    ),
                )
            else:
                kind = ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT
                evidence_value = subtype.value
        elif facts.complete_area_verified:
            assert facts.complete_area_m2 is not None
            kind = ProminenceEvidenceKind.COMPLETE_AREA_M2
            evidence_value = facts.complete_area_m2
        elif (
            facts.subtype in _WATERCOURSE_SUBTYPES
            and facts.complete_named_relation
            and facts.complete_relation_length_m is not None
        ):
            kind = ProminenceEvidenceKind.COMPLETE_RELATION_LENGTH_M
            evidence_value = facts.complete_relation_length_m
        else:
            kind = ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT
            evidence_value = facts.subtype.value

    geometry_measure: int | None = None
    geometry_verified = False
    if provider is not None:
        geometry_measure = None
    elif facts.complete_area_verified:
        geometry_measure = facts.complete_area_m2
        geometry_verified = True
    elif (
        facts.subtype in _WATERCOURSE_SUBTYPES
        and facts.complete_named_relation
        and facts.complete_relation_length_m is not None
    ):
        geometry_measure = facts.complete_relation_length_m
        geometry_verified = True
    bucket = complete_geometry_measure_bucket(
        geometry_measure,
        verified=geometry_verified,
    )
    return ProminenceDecision(
        subtype=facts.subtype,
        semantic_priority=semantic_priority_for(facts.subtype, tier),
        tier=tier,
        provider_rank=provider_rank,
        complete_geometry_measure_bucket=bucket,
        prominence_rule_id=_prominence_rule_id(facts.subtype, tier, kind),
        evidence_kind=kind,
        evidence_value=evidence_value,
        source_generation_sha256=context.source_generation_sha256,
        classifier_sha256=context.classifier_sha256,
        source_field_id=context.source_field_id,
        policy_sha256=PRESENTATION_POLICY_SHA256,
    )


def _visibility_rule_for_subtype_tier(
    subtype: SemanticSubtype,
    tier: ProminenceTier,
) -> LabelVisibilityRule:
    if subtype is SemanticSubtype.RIVER:
        return _RIVER_RULES[tier]
    if subtype is SemanticSubtype.STREAM_CREEK:
        return _STREAM_RULES[tier]
    if subtype is SemanticSubtype.CANAL_CHANNEL:
        return _CANAL_RULES[tier]
    if subtype is SemanticSubtype.UNSPECIFIED_WATERCOURSE:
        return _UNSPECIFIED_WATERCOURSE_RULES[tier]
    family = _VISIBILITY_FAMILY_BY_SUBTYPE.get(subtype)
    if family is None:
        raise ReferencePolicyError("label subtype has no visibility family")
    minimum, full, size = _GENERIC_VISIBILITY_BANDS[family][tier]
    return LabelVisibilityRule(
        rule_id=f"{subtype.name.lower()}.{tier.value}",
        min_zoom_centi=minimum,
        full_alpha_zoom_centi=full,
        text_size_milli_sp=size,
        letter_spacing_milli_em=style_spec_for_subtype(
            subtype
        ).letter_spacing_milli_em,
    )


def visibility_rule_for_label(facts: LabelFacts) -> LabelVisibilityRule:
    if not isinstance(facts, LabelFacts):
        raise ReferencePolicyError("label visibility requires LabelFacts")
    if facts.subtype in _WATERCOURSE_SUBTYPES:
        return visibility_rule_for_waterway(
            WaterwayFacts(
                subtype=facts.subtype,
                complete_named_relation=facts.complete_named_relation,
                complete_relation_length_m=facts.complete_relation_length_m,
                evidence_context=facts.evidence_context,
                provider_evidence=facts.provider_evidence,
            )
        )
    return _visibility_rule_for_subtype_tier(
        facts.subtype,
        prominence_for_label(facts),
    )


def prominence_for_waterway(facts: WaterwayFacts) -> ProminenceTier:
    if not isinstance(facts, WaterwayFacts):
        raise ReferencePolicyError("waterway prominence requires WaterwayFacts")
    if facts.provider_evidence is not None:
        return facts.provider_evidence.tier
    if (
        facts.complete_named_relation
        and facts.complete_relation_length_m is not None
    ):
        return _complete_relation_length_tier(
            facts.subtype,
            facts.complete_relation_length_m,
        )
    return default_prominence_for_subtype(facts.subtype)


def visibility_rule_for_waterway(facts: WaterwayFacts) -> LabelVisibilityRule:
    return _visibility_rule_for_subtype_tier(
        facts.subtype,
        prominence_for_waterway(facts),
    )


def centizoom(zoom: float) -> int:
    if isinstance(zoom, bool) or not isinstance(zoom, (int, float)):
        raise ReferencePolicyError("map zoom must be numeric")
    numeric = float(zoom)
    if not math.isfinite(numeric) or not 0.0 <= numeric <= 100.0:
        raise ReferencePolicyError("map zoom must be finite and inside [0, 100]")
    return math.floor(numeric * 100.0 + 0.5)


def label_alpha_milli(rule: LabelVisibilityRule, current_centizoom: int) -> int:
    if not isinstance(rule, LabelVisibilityRule):
        raise ReferencePolicyError("label alpha requires a visibility rule")
    if type(current_centizoom) is not int or current_centizoom < 0:
        raise ReferencePolicyError("current centizoom must be a nonnegative integer")
    if current_centizoom <= rule.min_zoom_centi:
        return 0
    if current_centizoom >= rule.full_alpha_zoom_centi:
        return FULL_ALPHA_MILLI
    return _fade_alpha_milli(
        current_centizoom - rule.min_zoom_centi,
        rule.full_alpha_zoom_centi - rule.min_zoom_centi,
    )


def _fade_alpha_milli(elapsed: int, duration: int) -> int:
    if type(elapsed) is not int or type(duration) is not int or duration <= 0:
        raise ReferencePolicyError("alpha interpolation requires integer progress")
    if elapsed <= 0:
        return 0
    if elapsed >= duration:
        return FULL_ALPHA_MILLI
    numerator = elapsed * FULL_ALPHA_MILLI
    denominator = duration
    magnitude, remainder = divmod(numerator, denominator)
    if remainder * 2 >= denominator:
        magnitude += 1
    return magnitude


def outline_alpha_milli(
    rule: OutlineVisibilityRule,
    current_centizoom: int,
) -> int:
    if not isinstance(rule, OutlineVisibilityRule):
        raise ReferencePolicyError("outline alpha requires an outline visibility rule")
    if type(current_centizoom) is not int or current_centizoom < 0:
        raise ReferencePolicyError("current centizoom must be a nonnegative integer")
    if (
        current_centizoom <= rule.min_zoom_centi
        or current_centizoom >= rule.max_zoom_centi
    ):
        return 0
    if current_centizoom < rule.full_alpha_zoom_centi:
        return _fade_alpha_milli(
            current_centizoom - rule.min_zoom_centi,
            rule.full_alpha_zoom_centi - rule.min_zoom_centi,
        )
    if current_centizoom <= rule.fade_out_zoom_centi:
        return FULL_ALPHA_MILLI
    return _fade_alpha_milli(
        rule.max_zoom_centi - current_centizoom,
        rule.max_zoom_centi - rule.fade_out_zoom_centi,
    )


def point_label_placement_eligible(
    *,
    placement_source_kind: PlacementSourceKind,
    exact_source_point: bool,
    source_text_evidence_verified: bool,
    inferred_centroid: bool,
) -> bool:
    if not isinstance(placement_source_kind, PlacementSourceKind):
        raise ReferencePolicyError("point placement source kind is unknown")
    for value, label in (
        (exact_source_point, "exact source point"),
        (source_text_evidence_verified, "source text evidence"),
        (inferred_centroid, "inferred centroid"),
    ):
        if type(value) is not bool:
            raise ReferencePolicyError(f"{label} flag must be Boolean")
    return (
        placement_source_kind
        in (
            PlacementSourceKind.DIRECT_SOURCE_POINT,
            PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
        )
        and exact_source_point
        and source_text_evidence_verified
        and not inferred_centroid
    )


def line_label_span_eligible(
    *,
    shaped_advance_milli_px: int,
    end_clearance_milli_px: int,
    available_span_milli_px: int,
    bend_centi_degrees: int,
    text_scale_x_milli: int,
    whole_text: bool,
) -> bool:
    values = (
        (shaped_advance_milli_px, "shaped advance"),
        (end_clearance_milli_px, "end clearance"),
        (available_span_milli_px, "available span"),
        (bend_centi_degrees, "bend"),
        (text_scale_x_milli, "horizontal text scale"),
    )
    for value, label in values:
        if type(value) is not int or not 0 <= value <= _I64_MAX:
            raise ReferencePolicyError(
                f"{label} must be a nonnegative signed 64-bit integer"
            )
    if type(whole_text) is not bool:
        raise ReferencePolicyError("whole-text flag must be Boolean")
    if shaped_advance_milli_px == 0:
        return False
    if not whole_text or text_scale_x_milli != UNCONDENSED_TEXT_SCALE_X_MILLI:
        return False
    if bend_centi_degrees > MAX_LINE_LABEL_BEND_CENTI_DEGREES:
        return False
    if end_clearance_milli_px > (_I64_MAX - shaped_advance_milli_px) // 2:
        raise ReferencePolicyError("required line-label span exceeds signed 64-bit")
    required_span = shaped_advance_milli_px + 2 * end_clearance_milli_px
    return available_span_milli_px >= required_span


def canonical_presentation_policy_bytes() -> bytes:
    subtype_records = []
    for subtype in SemanticSubtype:
        owner = next(spec for spec in _FILTER_SPECS if subtype in spec.subtypes)
        style = _STYLE_BY_SUBTYPE[subtype]
        resolved = _RESOLVED_STYLE_BY_SUBTYPE[subtype]
        subtype_records.append(
            {
                "filterId": owner.filter_id.value,
                "id": subtype.value,
                "name": subtype.name,
                "semanticPriorityByTier": {
                    tier.value: semantic_priority_for(subtype, tier)
                    for tier in ProminenceTier
                }
                if subtype.value < 500
                else None,
                "semanticPriorityWithinTier": (
                    SEMANTIC_PRIORITY_WITHIN_TIER_BY_SUBTYPE.get(subtype)
                ),
                "style": {
                    "colorToken": style.color_token,
                    "family": style.family.value,
                    "fontSlant": style.font_slant,
                    "fontWeight": style.font_weight,
                    "haloToken": style.halo_token,
                    "letterSpacingMilliEm": style.letter_spacing_milli_em,
                    "linePattern": style.line_pattern,
                    "lineWidthMilliDp": style.line_width_milli_dp,
                    "resolved": {
                        "alphaMilli": resolved.alpha_milli,
                        "colorArgb": resolved.color_argb,
                        "dashMilliDp": list(resolved.dash_milli_dp),
                        "dashPhaseMilliDp": resolved.dash_phase_milli_dp,
                        "haloAlphaMilli": resolved.halo_alpha_milli,
                        "haloArgb": resolved.halo_argb,
                        "haloWidthMilliEm": resolved.halo_width_milli_em,
                        "lineCap": resolved.line_cap,
                        "lineHaloWidthMilliDp": resolved.line_halo_width_milli_dp,
                        "lineJoin": resolved.line_join,
                    },
                },
            }
        )
    filter_records = [
        {
            "defaultEnabled": spec.default_enabled,
            "id": spec.filter_id.value,
            "kind": spec.kind.value,
            "subtypeIds": [subtype.value for subtype in spec.subtypes],
            "title": spec.title,
        }
        for spec in _FILTER_SPECS
    ]
    visibility_records = []
    for subtype in SemanticSubtype:
        if subtype.value >= 500:
            continue
        for tier in ProminenceTier:
            rule = _visibility_rule_for_subtype_tier(subtype, tier)
            visibility_records.append(
                {
                    "fullAlphaZoomCenti": rule.full_alpha_zoom_centi,
                    "letterSpacingMilliEm": rule.letter_spacing_milli_em,
                    "maxBendCentiDegrees": rule.max_bend_centi_degrees,
                    "maxBendDegreesStored": (
                        rule.max_bend_centi_degrees // 100
                    ),
                    "minZoomCenti": rule.min_zoom_centi,
                    "prominenceTier": tier.value,
                    "prominenceTierCode": PROMINENCE_TIER_CODE[tier],
                    "ruleId": rule.rule_id,
                    "semanticSubtypeId": subtype.value,
                    "textSizeMilliSp": rule.text_size_milli_sp,
                    "displayMaxZoomCenti": LABEL_DISPLAY_MAX_ZOOM_CENTI,
                    "fadeOutZoomCenti": LABEL_FADE_OUT_ZOOM_CENTI,
                }
            )
    outline_records = [
        {
            "drawOrder": rule.draw_order,
            "fadeOutZoomCenti": rule.fade_out_zoom_centi,
            "fullAlphaZoomCenti": rule.full_alpha_zoom_centi,
            "maxZoomCenti": rule.max_zoom_centi,
            "minZoomCenti": rule.min_zoom_centi,
            "priority": rule.priority,
            "ruleId": rule.rule_id,
            "semanticSubtypeId": subtype.value,
        }
        for subtype, rule in sorted(
            _OUTLINE_RULES.items(), key=lambda item: item[0].value
        )
    ]
    document = {
        "catalog": {
            "countDefinition": {
                "canonicalVariantCount": "distinct_canonical_variant_ids",
                "distinctFeatureCount": "distinct_admitted_feature_ids",
                "postingCount": "canonical_tile_posting_records",
            },
            "digestDomain": _CATALOG_DOMAIN.decode("ascii")[:-1] + "\\0",
            "exposeOnlyVerifiedNonzeroClasses": True,
            "missingOrCorruptBehavior": "unavailable_no_toggles",
            "packageBinding": (
                "manifest_binds_catalog_digest_and_renderer_semantic_stream_sha256"
            ),
            "subtypeCount": len(SemanticSubtype),
            "uiAvailabilityCount": "distinctFeatureCount",
            "version": _CATALOG_VERSION,
        },
        "filters": filter_records,
        "masterGatesPreserveSubtypeChoices": True,
        "outlineRules": outline_records,
        "placement": {
            "activeBandLimit": LABEL_ACTIVE_BAND_LIMIT,
            "alpha": {
                "fadeInterpolation": "nearest_integer_ties_away_from_zero",
                "fullAlphaMilli": FULL_ALPHA_MILLI,
                "labelEndpoints": "zero_at_or_below_min_full_at_or_above_full",
                "outlineEndpoints": (
                    "maximum_zero_precedes_fade_out_full_when_equal_"
                    "otherwise_zero_at_or_below_min_or_at_or_above_max_"
                    "and_full_from_full_through_fade_out"
                ),
            },
            "avoidEdges": True,
            "candidateOrder": [
                "semantic_priority",
                "prominence_tier",
                "provider_rank_present_first",
                "provider_rank_i32_smaller_first",
                "u16_max_minus_complete_geometry_measure_bucket",
                "candidate_id",
            ],
            "collisionCapsule": {
                "paddingMilliEm": LABEL_COLLISION_PADDING_MILLI_EM,
                "usesShapedAscentDescentHalo": True,
            },
            "collisionGroup": REFERENCE_LABEL_COLLISION_GROUP,
            "currentFractionalViewportRequired": True,
            "centizoomQuantization": {
                "acceptedZoomRange": [0, 100],
                "formulaForNonnegativeZoom": "floor(zoom_times_100_plus_0.5)",
                "nonfiniteRejected": True,
            },
            "displayMaxZoomCenti": LABEL_DISPLAY_MAX_ZOOM_CENTI,
            "endClearanceConversion": "ceil(text_em_milli_px*value/1000)",
            "endClearanceMilliEm": LABEL_END_CLEARANCE_MILLI_EM,
            "edgeClearanceMilliEm": LABEL_EDGE_CLEARANCE_MILLI_EM,
            "fadeOutZoomCenti": LABEL_FADE_OUT_ZOOM_CENTI,
            "handoff": "two_complete_runs_complementary_alpha",
            "handoffMaxMs": LABEL_HANDOFF_MAX_MS,
            "keepUpright": True,
            "maxPresentationsPerCandidateWrap": (
                LABEL_MAX_PRESENTATIONS_PER_CANDIDATE_WRAP
            ),
            "maximumBendCentiDegrees": MAX_LINE_LABEL_BEND_CENTI_DEGREES,
            "maximumBendDegreesStored": MAX_LINE_LABEL_BEND_CENTI_DEGREES // 100,
            "bendMeasurement": (
                "ceil_shortest_angle_unwrapped_tangent_span_centi_degrees"
            ),
            "minimumSpanFormula": "shaped_advance+2*end_clearance",
            "partialTextForbidden": True,
            "placementSourceKindCodes": {
                kind.name: kind.value for kind in PlacementSourceKind
            },
            "pointLabel": {
                "allowedPlacementSourceKinds": [
                    PlacementSourceKind.DIRECT_SOURCE_POINT.value,
                    PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT.value,
                ],
                "collision": (
                    "whole_shaped_bbox_plus_halo_padding_and_static_chrome"
                ),
                "edgePolicy": "whole_bbox_inside_edge_clearance_or_absent",
                "exactSourcePointRequired": True,
                "inferredCentroidForbidden": True,
                "verifiedSourceTextEvidenceRequired": True,
            },
            "prominenceRuleIdDomain": "FAE8RULE1\\0",
            "prominenceTierCodes": {
                tier.value: PROMINENCE_TIER_CODE[tier] for tier in ProminenceTier
            },
            "providerRank": {
                "missingSortsAfterPresent": True,
                "smallerSignedI32IsStronger": True,
            },
            "retainedScaledLabelBitmapForbidden": True,
            "repeatPhase": "label_candidate_id_mod_repeat_spacing_px",
            "repeatSpacingPx": LINE_LABEL_REPEAT_SPACING_PX,
            "sourceFraction": "exact_nonnegative_rational",
            "staticChromeCollides": True,
            "textScaleXMilli": UNCONDENSED_TEXT_SCALE_X_MILLI,
            "withinCandidateOrder": [
                "prior_still_valid_false_first",
                "negative_minimum_clearance_q8_px",
                "bend_centi_degrees",
                "center_distance_q8_px",
                "canonical_part_index",
                "canonical_segment_index",
                "exact_source_fraction",
                "repeat_ordinal",
                "candidate_id",
            ],
        },
        "prominenceDecision": {
            "canonicalDomain": "FAE8PDEC1\\0",
            "candidateBinding": "prominence_decision_sha256_equals_sha256_canonical_bytes",
            "canonicalFieldOrder": [
                "policy_sha256",
                "semantic_subtype_u32le",
                "semantic_priority_i32le",
                "prominence_tier_u8",
                "provider_rank_presence_bool",
                "optional_provider_rank_i32le",
                "complete_geometry_measure_bucket_u16le",
                "prominence_rule_id_u64le",
                "evidence_kind_u8",
                "evidence_value_i64le",
                "source_generation_sha256",
                "classifier_sha256",
                "source_field_id_u64le",
            ],
            "completeGeometryMeasureBucket": (
                "zero_if_unverified_or_zero_else_1_plus_floor_log2_times_1024_"
                "plus_floor_fractional_mantissa_times_1024_saturating_u16"
            ),
            "evidenceSemantics": {
                "capitalLevel": {
                    "allowedSubtypeIds": [200, 210, 220],
                    "bucket": 0,
                    "evidenceValueToTier": {
                        "1": ProminenceTier.REGIONAL_MAJOR.value,
                        "2": ProminenceTier.GLOBAL_MAJOR.value,
                    },
                },
                "completeAreaM2": {
                    "bucketFromEvidenceValue": True,
                    "nonnegativeI64": True,
                    "tierUsesSubtypeAreaThresholds": True,
                },
                "completeRelationLengthM": {
                    "bucketFromEvidenceValue": True,
                    "nonnegativeI64": True,
                    "tierUsesWatercourseSubtypeThresholds": True,
                },
                "population": {
                    "allowedSubtypeIds": [200, 210, 220],
                    "bucket": 0,
                    "nonnegativeI64": True,
                    "tierUsesPopulationThresholds": True,
                },
                "providerRank": {
                    "bucket": 0,
                    "cannotMixVerifiedFallbackEvidence": [
                        "population",
                        "capital_level",
                        "complete_area_m2",
                        "complete_relation_length_m",
                    ],
                    "evidenceValueEqualsProviderRank": True,
                    "tierComesFromBoundClassifier": True,
                },
                "typedSubtypeDefault": {
                    "bucket": 0,
                    "evidenceValueEqualsSubtypeId": True,
                    "tierUsesDefaultProminenceTierBySubtype": True,
                },
            },
            "evidenceKindCodes": {
                kind.name: kind.value for kind in ProminenceEvidenceKind
            },
            "semanticPriority": {
                "formula": "tier_code_times_stride_plus_within_tier_priority",
                "tierDominatesAllSubtypeClasses": True,
                "tierStride": _SEMANTIC_PRIORITY_TIER_STRIDE,
            },
            "sourceContextRequiredForAuthoritativeDecision": True,
        },
        "schema": "flight-alert-exp8-reference-presentation-policy-v4",
        "sourceClassifier": {
            "nameOrSuffixClassificationForbidden": True,
            "providerEvidenceRequiredFields": [
                "source_generation_sha256",
                "classifier_sha256",
                "source_field_id",
                "raw_provider_rank_i32",
                "selected_tier",
            ],
            "typedClassifierManifestRequired": True,
        },
        "subtypes": subtype_records,
        "visibility": {
            "defaultProminenceTierBySubtype": {
                str(subtype.value): default_prominence_for_subtype(subtype).value
                for subtype in SemanticSubtype
                if subtype.value < 500
            },
            "evidencePrecedence": [
                "verified_provider_rank_or_scale",
                "verified_explicit_capital_population_or_complete_geometry_measure",
                "conservative_typed_subtype_default",
            ],
            "globalCityPopulationMin": GLOBAL_CITY_POPULATION_MIN,
            "globalIslandAreaM2Min": GLOBAL_ISLAND_AREA_M2_MIN,
            "globalRiverCompleteRelationMinLengthM": (
                GLOBAL_RIVER_COMPLETE_RELATION_MIN_LENGTH_M
            ),
            "globalWaterAreaM2Min": GLOBAL_WATER_AREA_M2_MIN,
            "localCityPopulationMin": LOCAL_CITY_POPULATION_MIN,
            "localIslandAreaM2Min": LOCAL_ISLAND_AREA_M2_MIN,
            "localProtectedAreaM2Min": LOCAL_PROTECTED_AREA_M2_MIN,
            "localRiverCompleteRelationMinLengthM": (
                LOCAL_RIVER_COMPLETE_RELATION_MIN_LENGTH_M
            ),
            "localWaterAreaM2Min": LOCAL_WATER_AREA_M2_MIN,
            "majorRiverCompleteRelationMinLengthM": (
                MAJOR_RIVER_COMPLETE_RELATION_MIN_LENGTH_M
            ),
            "namesAndSuffixesCannotPromote": True,
            "numericDomain": "nonnegative_signed_i64",
            "regionalCityPopulationMin": REGIONAL_CITY_POPULATION_MIN,
            "regionalIslandAreaM2Min": REGIONAL_ISLAND_AREA_M2_MIN,
            "regionalProtectedAreaM2Min": REGIONAL_PROTECTED_AREA_M2_MIN,
            "regionalWaterAreaM2Min": REGIONAL_WATER_AREA_M2_MIN,
            "rules": visibility_records,
            "screenshotBoundaryCenti": 628,
            "strongestSameLevelEvidenceWins": True,
        },
    }
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def presentation_policy_sha256() -> str:
    return hashlib.sha256(
        PRESENTATION_POLICY_DOMAIN + canonical_presentation_policy_bytes()
    ).hexdigest()
