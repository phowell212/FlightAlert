from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import tempfile
from collections import Counter
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Callable, Mapping


ALLOWED_DIRECT_WATERWAY_VALUES = frozenset(
    {"river", "stream", "canal", "tidal_channel", "wadi"}
)
MARYLAND_SOURCE_URL = (
    "https://download.geofabrik.de/north-america/us/maryland-260710.osm.pbf"
)
MARYLAND_SOURCE_BYTES = 212_933_228
MARYLAND_SOURCE_MD5 = "2642fa017680941a2fab4f96c23d9c03"
MARYLAND_SOURCE_SHA256 = "7a9c9baf554aa424f27d80e7aa20ccc7d2d412815613afe93b6af06ba703f99f"
MARYLAND_REGIONAL_PROFILE = (
    "flight-alert-exp8-geofabrik-maryland-260710-regional-pilot-v1"
)
CHESTER_SOURCE_SHA256 = "beea8b394d26fa86e3c372b678420a5fb84af801be7378a681f2f2976f35e99d"
CHESTER_INSPECTION_SHA256 = "eb997eb532b2e0fc3b8dc81bf32954230769586d988c955871d67f90b62a2ed4"
CHESTER_CORRIDOR_E7 = (-760_819_530, 390_734_860, -760_087_480, 392_303_250)
POLICY_SHA256 = "7a2accdefd1ca9fb0604d83b97010e760e327cd02971c969180dc2ccea2bbac2"
EXPLICITLY_EXCLUDED_WATERWAY_VALUES = frozenset(
    {
        "dam",
        "weir",
        "lock_gate",
        "dock",
        "riverbank",
        "ditch",
        "drain",
    }
)
_DIRECT_DISPLAY_NAME_KEYS = frozenset({"name", "int_name", "official_name"})
_NON_LANGUAGE_NAME_SUFFIXES = frozenset(
    {
        "left",
        "right",
        "signed",
        "pronunciation",
        "etymology",
        "source",
        "old",
    }
)
_LIFECYCLE_KEYS = frozenset(
    {"abandoned", "construction", "demolished", "disused", "proposed", "razed"}
)
_FALSE_TAG_VALUES = frozenset({"", "0", "false", "no"})
_LANGUAGE_NAME_KEY = re.compile(
    r"name:([A-Za-z]{2,3})(?:-([A-Za-z0-9]{2,8}))*\Z"
)
_CANONICAL_DECIMAL = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?\Z")
_PLAIN_DECIMAL = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.([0-9]+))?\Z")
_FORBIDDEN_XML_DECLARATION = re.compile(
    br"<!\s*(?:DOCTYPE|ENTITY)\b", re.IGNORECASE
)
_TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z\Z")
_E7 = Decimal(10_000_000)
_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_OSMIUM_DEB_SHA256 = "d8e791ac3558aaafa95d3f6ac7329b15df2fb502bd6babff881e62830e49f906"
_BOOST_DEB_SHA256 = "389095c7167251ee73667031a4c0f45083a31347cc95faddbdf5b7d22ac4c774"
_OSMIUM_BINARY_SHA256 = "5575922905fb39fa87262e74ed2b0ac367086b5439468339e45bf3720c1821fc"
_BOOST_LIBRARY_SHA256 = "16a89b0d75de54bfef18b479eb1d38710e5c242246a17baffa11eb4f2d544663"
_MAX_OSM_OBJECT_ID = (1 << 63) - 1
_MAX_OSMIUM_DIAGNOSTIC_BYTES = 16 * 1024 * 1024
_MAX_OSMIUM_MISSING_IDS = 1_000_000


class SourceContractError(ValueError):
    """An OSM source object violated the frozen Experiment 8 source contract."""


@dataclass(frozen=True, slots=True)
class OsmXmlLimits:
    max_bytes: int = 64 * 1024 * 1024
    max_objects: int = 2_000_000
    max_references: int = 10_000_000
    max_tags: int = 5_000_000
    max_tag_utf8_bytes: int = 16 * 1024

    def __post_init__(self) -> None:
        for label, value in (
            ("XML byte", self.max_bytes),
            ("XML object", self.max_objects),
            ("XML reference", self.max_references),
            ("XML tag", self.max_tags),
            ("XML tag UTF-8 byte", self.max_tag_utf8_bytes),
        ):
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise SourceContractError(f"{label} ceiling must be a nonnegative integer")


@dataclass(frozen=True, slots=True)
class OsmNode:
    object_id: int
    version: int
    timestamp: str
    longitude_e7: int
    latitude_e7: int
    tags: tuple[tuple[str, str], ...] = ()


@dataclass(frozen=True, slots=True)
class OsmWay:
    object_id: int
    version: int
    timestamp: str
    node_refs: tuple[int, ...]
    tags: tuple[tuple[str, str], ...]


@dataclass(frozen=True, slots=True)
class OsmRelationMember:
    object_type: str
    ref: int
    role: str
    ordinal: int = -1

    def __post_init__(self) -> None:
        if self.object_type not in {"node", "way", "relation"}:
            raise SourceContractError(
                f"unsupported relation member type: {self.object_type!r}"
            )
        if (
            isinstance(self.ref, bool)
            or not isinstance(self.ref, int)
            or not 0 < self.ref <= _MAX_OSM_OBJECT_ID
        ):
            raise SourceContractError("relation member ref must be a positive signed-63 integer")
        if not isinstance(self.role, str):
            raise SourceContractError("relation member role must be text")
        if (
            isinstance(self.ordinal, bool)
            or not isinstance(self.ordinal, int)
            or self.ordinal < -1
        ):
            raise SourceContractError("relation member ordinal must be -1 or nonnegative")


@dataclass(frozen=True, slots=True)
class OsmRelation:
    object_id: int
    version: int
    timestamp: str
    members: tuple[OsmRelationMember, ...]
    tags: tuple[tuple[str, str], ...]


@dataclass(frozen=True, slots=True)
class OsmDataset:
    api_version: str
    generator: str
    nodes: Mapping[int, OsmNode]
    ways: Mapping[int, OsmWay]
    relations: Mapping[int, OsmRelation]


@dataclass(frozen=True, slots=True)
class RootSelection:
    way_ids: tuple[int, ...]
    relation_ids: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class ReferenceClosure:
    selected_way_ids: tuple[int, ...]
    selected_relation_ids: tuple[int, ...]
    reference_only_node_ids: tuple[int, ...]
    reference_only_way_ids: tuple[int, ...]
    reference_only_relation_ids: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class VerifiedSourceFile:
    path: Path
    bytes: int
    md5: str
    sha256: str


@dataclass(frozen=True, slots=True)
class MissingReferences:
    node_ids: tuple[int, ...]
    way_ids: tuple[int, ...]
    relation_ids: tuple[int, ...]

    def __post_init__(self) -> None:
        for label, object_ids in (
            ("node", self.node_ids),
            ("way", self.way_ids),
            ("relation", self.relation_ids),
        ):
            if not isinstance(object_ids, tuple):
                raise SourceContractError(f"missing {label} IDs must be a tuple")
            previous = 0
            for object_id in object_ids:
                if (
                    isinstance(object_id, bool)
                    or not isinstance(object_id, int)
                    or object_id > _MAX_OSM_OBJECT_ID
                    or object_id <= previous
                ):
                    raise SourceContractError(
                        f"missing {label} IDs must be strictly increasing positive integers"
                    )
                previous = object_id
        if self.count > _MAX_OSMIUM_MISSING_IDS:
            raise SourceContractError("missing-reference count exceeds the audit ceiling")

    @property
    def count(self) -> int:
        return len(self.node_ids) + len(self.way_ids) + len(self.relation_ids)


@dataclass(frozen=True, slots=True)
class IncompleteRelationRoot:
    relation_id: int
    direct_missing_members: tuple[OsmRelationMember, ...]
    dependency_relation_ids: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class RegionalClosureClassification:
    complete_way_ids: tuple[int, ...]
    complete_relation_ids: tuple[int, ...]
    incomplete_relations: tuple[IncompleteRelationRoot, ...]
    missing_references: MissingReferences


@dataclass(frozen=True, slots=True)
class IncompleteRelationClosure:
    relation_id: int
    missing_references: MissingReferences


@dataclass(frozen=True, slots=True)
class RelationClosureAudit:
    complete_relation_ids: tuple[int, ...]
    incomplete_relations: tuple[IncompleteRelationClosure, ...]
    global_missing_references: MissingReferences
    probed_batches: tuple[tuple[int, ...], ...]


def _positive_integer(value: str | None, label: str) -> int:
    if value is None or not value.isascii() or not value.isdecimal():
        raise SourceContractError(f"{label} must be a positive decimal integer")
    parsed = int(value)
    if parsed <= 0 or parsed > _MAX_OSM_OBJECT_ID:
        raise SourceContractError(f"{label} must be a positive signed-63 integer")
    return parsed


def _timestamp(value: str | None, label: str) -> str:
    if value is None or _TIMESTAMP.fullmatch(value) is None:
        raise SourceContractError(f"{label} timestamp is missing or not canonical UTC")
    return value


def parse_e7(value: str, *, axis: str) -> int:
    """Parse an OSM coordinate without passing through a binary float."""

    if axis == "longitude":
        minimum, maximum = -180 * 10_000_000, 180 * 10_000_000
    elif axis == "latitude":
        minimum, maximum = -90 * 10_000_000, 90 * 10_000_000
    else:
        raise SourceContractError(f"unsupported coordinate axis: {axis!r}")
    if isinstance(value, str) and value in {"NaN", "Infinity", "-Infinity"}:
        raise SourceContractError(f"{axis} must be a finite decimal")
    if not isinstance(value, str):
        raise SourceContractError(f"{axis} must use canonical decimal syntax")
    plain = _PLAIN_DECIMAL.fullmatch(value)
    if plain is not None and plain.group(1) is not None and len(plain.group(1)) > 7:
        raise SourceContractError(
            f"{axis} has more than seven decimal places of nonzero precision"
        )
    if _CANONICAL_DECIMAL.fullmatch(value) is None:
        raise SourceContractError(f"{axis} must use canonical decimal syntax")
    try:
        decimal = Decimal(value)
    except (InvalidOperation, ValueError) as error:
        raise SourceContractError(f"{axis} must be a finite decimal") from error
    if not decimal.is_finite():
        raise SourceContractError(f"{axis} must be a finite decimal")
    scaled = decimal * _E7
    integral = scaled.to_integral_value()
    if scaled != integral:
        raise SourceContractError(
            f"{axis} has more than seven decimal places of nonzero precision"
        )
    coordinate = int(integral)
    if not minimum <= coordinate <= maximum:
        raise SourceContractError(f"{axis} is outside its valid E7 range: {value!r}")
    return 0 if coordinate == 0 else coordinate


def _is_supported_name_key(key: str) -> bool:
    if key in _DIRECT_DISPLAY_NAME_KEYS:
        return True
    match = _LANGUAGE_NAME_KEY.fullmatch(key)
    if match is None:
        return False
    return not any(
        subtag.casefold() in _NON_LANGUAGE_NAME_SUFFIXES
        for subtag in key[5:].split("-")
    )


def supported_display_names(
    tags: tuple[tuple[str, str], ...],
) -> tuple[tuple[str, str], ...]:
    """Return exact, source-owned display fields in canonical key/value order."""

    selected = [
        (key, value)
        for key, value in tags
        if _is_supported_name_key(key) and bool(value.strip())
    ]
    return tuple(sorted(selected))


def _tag_map(tags: tuple[tuple[str, str], ...]) -> dict[str, str]:
    result: dict[str, str] = {}
    for key, value in tags:
        if key in result:
            raise SourceContractError(f"duplicate OSM tag key: {key!r}")
        result[key] = value
    return result


def _direct_way_rejection_reason(way: OsmWay) -> str | None:
    tags = _tag_map(way.tags)
    if tags.get("waterway") not in ALLOWED_DIRECT_WATERWAY_VALUES:
        return "unsupported_waterway"
    if len(way.node_refs) < 2 or way.node_refs[0] == way.node_refs[-1]:
        return "insufficient_geometry" if len(way.node_refs) < 2 else "closed"
    if tags.get("area", "").strip().casefold() not in _FALSE_TAG_VALUES:
        return "area"
    for key in _LIFECYCLE_KEYS:
        if tags.get(key, "").strip().casefold() not in _FALSE_TAG_VALUES:
            return "lifecycle"
        if f"{key}:waterway" in tags:
            return "lifecycle"
    if not supported_display_names(way.tags):
        return "no_display_name"
    return None


def is_direct_way_root(way: OsmWay) -> bool:
    return _direct_way_rejection_reason(way) is None


def is_named_waterway_relation_root(relation: OsmRelation) -> bool:
    tags = _tag_map(relation.tags)
    return tags.get("type") == "waterway" and bool(
        supported_display_names(relation.tags)
    )


def _relation_rejection_reason(relation: OsmRelation) -> str | None:
    tags = _tag_map(relation.tags)
    if tags.get("type") != "waterway":
        return "unsupported_type"
    if not supported_display_names(relation.tags):
        return "no_display_name"
    return None


def canonical_policy_bytes() -> bytes:
    document = {
        "allowedDirectWaterwayValues": sorted(ALLOWED_DIRECT_WATERWAY_VALUES),
        "excludedWaterwayValues": sorted(EXPLICITLY_EXCLUDED_WATERWAY_VALUES),
        "nameFields": sorted(_DIRECT_DISPLAY_NAME_KEYS),
        "nameLanguageKeyPattern": _LANGUAGE_NAME_KEY.pattern,
        "rejectAreaValuesOtherThan": sorted(_FALSE_TAG_VALUES),
        "rejectClosedWays": True,
        "rejectLifecycleKeys": sorted(_LIFECYCLE_KEYS),
        "rejectedNameSuffixes": sorted(_NON_LANGUAGE_NAME_SUFFIXES),
        "relationPredicate": {"type": "waterway", "requiresDisplayName": True},
        "schema": "flight-alert-exp8-osm-waterway-policy-v1",
    }
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _parse_tags(element: ElementTree.Element, label: str) -> tuple[tuple[str, str], ...]:
    tags: dict[str, str] = {}
    for child in element.findall("tag"):
        key = child.get("k")
        value = child.get("v")
        if key is None or not key or value is None:
            raise SourceContractError(f"{label} contains an invalid tag")
        if key in tags:
            raise SourceContractError(f"{label} contains duplicate tag {key!r}")
        tags[key] = value
    return tuple(sorted(tags.items()))


def _insert_unique(
    objects: dict[int, object], object_id: int, value: object, label: str
) -> None:
    if object_id in objects:
        raise SourceContractError(f"duplicate {label} ID {object_id}")
    objects[object_id] = value


def parse_osm_xml(
    path: str | Path, *, limits: OsmXmlLimits = OsmXmlLimits()
) -> OsmDataset:
    source = Path(path)
    try:
        raw = source.read_bytes()
    except OSError as error:
        raise SourceContractError(f"OSM XML is unavailable: {source}: {error}") from error
    return parse_osm_xml_bytes(raw, source_label=str(source), limits=limits)


def parse_osm_xml_bytes(
    raw: bytes,
    *,
    source_label: str = "<bytes>",
    limits: OsmXmlLimits = OsmXmlLimits(),
) -> OsmDataset:
    if not isinstance(raw, bytes):
        raise SourceContractError("OSM XML input must be bytes")
    if not isinstance(limits, OsmXmlLimits):
        raise SourceContractError("OSM XML limits are missing or invalid")
    if len(raw) > limits.max_bytes:
        raise SourceContractError(
            f"OSM XML exceeds the byte ceiling {limits.max_bytes}"
        )
    if _FORBIDDEN_XML_DECLARATION.search(raw) is not None:
        raise SourceContractError("OSM XML DTD or entity declarations are forbidden")
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as error:
        raise SourceContractError(
            f"OSM XML is invalid: {source_label}: {error}"
        ) from error
    if root.tag != "osm":
        raise SourceContractError(f"OSM XML root must be 'osm', got {root.tag!r}")
    api_version = root.get("version", "")
    if api_version != "0.6":
        raise SourceContractError(f"OSM API version must be '0.6', got {api_version!r}")
    generator = root.get("generator", "")

    object_count = 0
    reference_count = 0
    tag_count = 0
    for element in root:
        if element.tag in {"node", "way", "relation"}:
            object_count += 1
        if element.tag == "way":
            reference_count += len(element.findall("nd"))
        elif element.tag == "relation":
            reference_count += len(element.findall("member"))
        tags = element.findall("tag")
        tag_count += len(tags)
        for tag in tags:
            key = tag.get("k")
            value = tag.get("v")
            if key is not None and len(key.encode("utf-8")) > limits.max_tag_utf8_bytes:
                raise SourceContractError("OSM tag key exceeds the UTF-8 byte ceiling")
            if value is not None and len(value.encode("utf-8")) > limits.max_tag_utf8_bytes:
                raise SourceContractError("OSM tag value exceeds the UTF-8 byte ceiling")
    if object_count > limits.max_objects:
        raise SourceContractError(
            f"OSM XML exceeds the object ceiling {limits.max_objects}"
        )
    if reference_count > limits.max_references:
        raise SourceContractError(
            f"OSM XML exceeds the reference ceiling {limits.max_references}"
        )
    if tag_count > limits.max_tags:
        raise SourceContractError(f"OSM XML exceeds the tag ceiling {limits.max_tags}")

    nodes: dict[int, OsmNode] = {}
    ways: dict[int, OsmWay] = {}
    relations: dict[int, OsmRelation] = {}
    for element in root:
        if element.tag == "node":
            object_id = _positive_integer(element.get("id"), "node ID")
            node = OsmNode(
                object_id=object_id,
                version=_positive_integer(element.get("version"), f"node {object_id} version"),
                timestamp=_timestamp(element.get("timestamp"), f"node {object_id}"),
                longitude_e7=parse_e7(element.get("lon", ""), axis="longitude"),
                latitude_e7=parse_e7(element.get("lat", ""), axis="latitude"),
                tags=_parse_tags(element, f"node {object_id}"),
            )
            _insert_unique(nodes, object_id, node, "node")
        elif element.tag == "way":
            object_id = _positive_integer(element.get("id"), "way ID")
            refs = tuple(
                _positive_integer(child.get("ref"), f"way {object_id} node ref")
                for child in element.findall("nd")
            )
            way = OsmWay(
                object_id=object_id,
                version=_positive_integer(element.get("version"), f"way {object_id} version"),
                timestamp=_timestamp(element.get("timestamp"), f"way {object_id}"),
                node_refs=refs,
                tags=_parse_tags(element, f"way {object_id}"),
            )
            _insert_unique(ways, object_id, way, "way")
        elif element.tag == "relation":
            object_id = _positive_integer(element.get("id"), "relation ID")
            members: list[OsmRelationMember] = []
            for ordinal, child in enumerate(element.findall("member")):
                object_type = child.get("type", "")
                if object_type not in {"node", "way", "relation"}:
                    raise SourceContractError(
                        f"relation {object_id} has unsupported member type {object_type!r}"
                    )
                members.append(
                    OsmRelationMember(
                        object_type=object_type,
                        ref=_positive_integer(
                            child.get("ref"), f"relation {object_id} member ref"
                        ),
                        role=child.get("role", ""),
                        ordinal=ordinal,
                    )
                )
            relation = OsmRelation(
                object_id=object_id,
                version=_positive_integer(
                    element.get("version"), f"relation {object_id} version"
                ),
                timestamp=_timestamp(element.get("timestamp"), f"relation {object_id}"),
                members=tuple(members),
                tags=_parse_tags(element, f"relation {object_id}"),
            )
            _insert_unique(relations, object_id, relation, "relation")
        elif element.tag not in {"bounds", "note", "meta"}:
            raise SourceContractError(f"unexpected OSM XML element {element.tag!r}")
    return OsmDataset(
        api_version=api_version,
        generator=generator,
        nodes=nodes,
        ways=ways,
        relations=relations,
    )


def select_roots(dataset: OsmDataset) -> RootSelection:
    return RootSelection(
        way_ids=tuple(
            object_id
            for object_id, way in sorted(dataset.ways.items())
            if is_direct_way_root(way)
        ),
        relation_ids=tuple(
            object_id
            for object_id, relation in sorted(dataset.relations.items())
            if is_named_waterway_relation_root(relation)
        ),
    )


def compute_reference_closure(
    dataset: OsmDataset, roots: RootSelection
) -> ReferenceClosure:
    selected_ways = set(roots.way_ids)
    selected_relations = set(roots.relation_ids)
    reference_nodes: set[int] = set()
    reference_ways: set[int] = set()
    reference_relations: set[int] = set()
    visited_ways: set[int] = set()
    visited_relations: set[int] = set()

    def visit_way(object_id: int, owner: str) -> None:
        way = dataset.ways.get(object_id)
        if way is None:
            raise SourceContractError(f"{owner} references missing way {object_id}")
        if object_id in visited_ways:
            return
        visited_ways.add(object_id)
        for node_id in way.node_refs:
            if node_id not in dataset.nodes:
                raise SourceContractError(
                    f"way {object_id} references missing node {node_id}"
                )
            reference_nodes.add(node_id)

    def visit_relation(object_id: int, stack: tuple[int, ...]) -> None:
        if object_id in stack:
            cycle = " -> ".join(str(value) for value in stack + (object_id,))
            raise SourceContractError(f"relation cycle: {cycle}")
        relation = dataset.relations.get(object_id)
        if relation is None:
            owner = f"relation {stack[-1]}" if stack else "selected roots"
            raise SourceContractError(f"{owner} references missing relation {object_id}")
        if object_id in visited_relations:
            return
        next_stack = stack + (object_id,)
        for member in relation.members:
            if member.object_type == "node":
                if member.ref not in dataset.nodes:
                    raise SourceContractError(
                        f"relation {object_id} references missing node {member.ref}"
                    )
                reference_nodes.add(member.ref)
            elif member.object_type == "way":
                if member.ref not in selected_ways:
                    reference_ways.add(member.ref)
                visit_way(member.ref, f"relation {object_id}")
            else:
                if member.ref not in selected_relations:
                    reference_relations.add(member.ref)
                visit_relation(member.ref, next_stack)
        visited_relations.add(object_id)

    for way_id in roots.way_ids:
        visit_way(way_id, "selected roots")
    for relation_id in roots.relation_ids:
        visit_relation(relation_id, ())
    return ReferenceClosure(
        selected_way_ids=tuple(sorted(selected_ways)),
        selected_relation_ids=tuple(sorted(selected_relations)),
        reference_only_node_ids=tuple(sorted(reference_nodes)),
        reference_only_way_ids=tuple(sorted(reference_ways - selected_ways)),
        reference_only_relation_ids=tuple(
            sorted(reference_relations - selected_relations)
        ),
    )


_OSMIUM_NOT_FOUND = re.compile(
    r"\[\s*\d+:\d+\] Did not find ([0-9]+) object\(s\)\.\Z"
)
_OSMIUM_FOUND_ALL = re.compile(r"\[\s*\d+:\d+\] Found all objects\.\Z")
_OSMIUM_MISSING_IDS = re.compile(
    r"Missing (node|way|relation) IDs:(?: ([0-9]+(?: [0-9]+)*))?\Z"
)
_OSMIUM_PEAK = re.compile(
    r"\[\s*\d+:\d+\] Peak memory used: [0-9]+ MBytes\Z"
)
_OSMIUM_DONE = re.compile(r"\[\s*\d+:\d+\] Done\.\Z")


def parse_osmium_missing_references(output: str) -> MissingReferences:
    """Parse the fail-closed terminal summary emitted by pinned osmium getid."""

    if not isinstance(output, str):
        raise SourceContractError("osmium diagnostic output must be text")
    try:
        encoded_size = len(output.encode("utf-8", errors="strict"))
    except UnicodeEncodeError as error:
        raise SourceContractError("osmium diagnostic is not canonical UTF-8 text") from error
    if encoded_size > _MAX_OSMIUM_DIAGNOSTIC_BYTES:
        raise SourceContractError("osmium diagnostic exceeds the byte ceiling")
    if any(ord(character) < 32 and character not in "\n\r" for character in output):
        raise SourceContractError("osmium diagnostic contains control characters")

    lines = output.splitlines()
    summaries: list[tuple[int, str, int]] = []
    for index, line in enumerate(lines):
        if _OSMIUM_FOUND_ALL.fullmatch(line) is not None:
            summaries.append((index, "found_all", 0))
        elif (not_found := _OSMIUM_NOT_FOUND.fullmatch(line)) is not None:
            summaries.append((index, "not_found", int(not_found.group(1))))
    if len(summaries) != 1:
        raise SourceContractError(
            "osmium diagnostic must contain exactly one terminal object-count summary"
        )
    summary_index, state, expected_count = summaries[0]
    tail = lines[summary_index + 1 :]
    if (
        len(tail) < 2
        or _OSMIUM_PEAK.fullmatch(tail[-2]) is None
        or _OSMIUM_DONE.fullmatch(tail[-1]) is None
    ):
        raise SourceContractError(
            "osmium diagnostic does not end in the pinned terminal sequence"
        )
    missing_lines = tail[:-2]
    if state == "found_all":
        if missing_lines:
            raise SourceContractError("osmium diagnostic has output after success")
        return MissingReferences(node_ids=(), way_ids=(), relation_ids=())

    parsed: dict[str, tuple[int, ...]] = {}
    type_order = {"node": 0, "way": 1, "relation": 2}
    previous_type_order = -1
    for line in missing_lines:
        missing = _OSMIUM_MISSING_IDS.fullmatch(line)
        if missing is None:
            raise SourceContractError(
                f"unsupported or malformed osmium terminal line: {line!r}"
            )
        object_type, values = missing.groups()
        if object_type in parsed or type_order[object_type] <= previous_type_order:
            raise SourceContractError(
                "osmium missing-ID sections are duplicated or out of order"
            )
        previous_type_order = type_order[object_type]
        parsed[object_type] = tuple(
            int(value) for value in values.split(" ")
        ) if values else ()
    result = MissingReferences(
        node_ids=parsed.get("node", ()),
        way_ids=parsed.get("way", ()),
        relation_ids=parsed.get("relation", ()),
    )
    if result.count != expected_count or result.count == 0:
        raise SourceContractError(
            "osmium missing-reference count does not match its terminal summary"
        )
    return result


def require_zero_missing_references(
    missing: MissingReferences, *, context: str
) -> None:
    if not isinstance(context, str) or not context.strip():
        raise SourceContractError("closure context must be nonblank text")
    if missing.count:
        raise SourceContractError(
            f"{context} has {missing.count} missing references; exact closure is required"
        )


def audit_relation_root_closures(
    relation_ids: tuple[int, ...],
    probe: Callable[[tuple[int, ...]], MissingReferences],
) -> RelationClosureAudit:
    """Attribute a regional extract's missing closure to exact relation roots.

    The deterministic batch bisection is an evidence optimization only. Every
    failed leaf is reprobed as a singleton, and the singleton union must equal
    the initial all-root diagnostic exactly.
    """

    if not isinstance(relation_ids, tuple):
        raise SourceContractError("relation audit IDs must be a tuple")
    previous = 0
    for relation_id in relation_ids:
        if (
            isinstance(relation_id, bool)
            or not isinstance(relation_id, int)
            or relation_id <= previous
        ):
            raise SourceContractError(
                "relation audit IDs must be strictly increasing positive integers"
            )
        previous = relation_id
    if not relation_ids:
        empty = MissingReferences(node_ids=(), way_ids=(), relation_ids=())
        return RelationClosureAudit(
            complete_relation_ids=(),
            incomplete_relations=(),
            global_missing_references=empty,
            probed_batches=(),
        )

    probed_batches: list[tuple[int, ...]] = []

    def run(batch: tuple[int, ...]) -> MissingReferences:
        probed_batches.append(batch)
        result = probe(batch)
        if not isinstance(result, MissingReferences):
            raise SourceContractError(
                "relation closure probe returned an unsupported result"
            )
        return result

    global_missing = run(relation_ids)
    missing_selected = set(global_missing.relation_ids).intersection(relation_ids)
    if missing_selected:
        raise SourceContractError(
            f"selected relation {min(missing_selected)} is absent from the closure source"
        )

    complete: set[int] = set()
    incomplete: dict[int, MissingReferences] = {}

    def bisect(
        batch: tuple[int, ...], result: MissingReferences | None = None
    ) -> None:
        outcome = run(batch) if result is None else result
        if outcome.count == 0:
            complete.update(batch)
            return
        if len(batch) == 1:
            incomplete[batch[0]] = outcome
            return
        midpoint = len(batch) // 2
        bisect(batch[:midpoint])
        bisect(batch[midpoint:])

    bisect(relation_ids, global_missing)
    if complete.intersection(incomplete) or complete.union(incomplete) != set(
        relation_ids
    ):
        raise SourceContractError("relation closure audit did not classify every root")

    singleton_nodes: set[int] = set()
    singleton_ways: set[int] = set()
    singleton_relations: set[int] = set()
    for missing in incomplete.values():
        singleton_nodes.update(missing.node_ids)
        singleton_ways.update(missing.way_ids)
        singleton_relations.update(missing.relation_ids)
    singleton_union = MissingReferences(
        node_ids=tuple(sorted(singleton_nodes)),
        way_ids=tuple(sorted(singleton_ways)),
        relation_ids=tuple(sorted(singleton_relations)),
    )
    if singleton_union != global_missing:
        raise SourceContractError(
            "relation closure singleton union does not match the all-root diagnostic"
        )

    return RelationClosureAudit(
        complete_relation_ids=tuple(sorted(complete)),
        incomplete_relations=tuple(
            IncompleteRelationClosure(
                relation_id=relation_id,
                missing_references=incomplete[relation_id],
            )
            for relation_id in sorted(incomplete)
        ),
        global_missing_references=global_missing,
        probed_batches=tuple(probed_batches),
    )


def audit_predicted_relation_root_closures(
    relation_ids: tuple[int, ...],
    predicted_incomplete_ids: tuple[int, ...],
    probe: Callable[[tuple[int, ...]], MissingReferences],
) -> RelationClosureAudit:
    """Verify a direct-member prediction with singleton and retained-union probes."""

    for label, object_ids in (
        ("relation audit", relation_ids),
        ("predicted incomplete relation", predicted_incomplete_ids),
    ):
        if not isinstance(object_ids, tuple):
            raise SourceContractError(f"{label} IDs must be a tuple")
        previous = 0
        for object_id in object_ids:
            if (
                isinstance(object_id, bool)
                or not isinstance(object_id, int)
                or object_id <= previous
            ):
                raise SourceContractError(
                    f"{label} IDs must be strictly increasing positive integers"
                )
            previous = object_id
    relation_id_set = set(relation_ids)
    predicted_set = set(predicted_incomplete_ids)
    if not predicted_set.issubset(relation_id_set):
        raise SourceContractError(
            "predicted incomplete relation IDs must be a subset of selected roots"
        )
    if not relation_ids:
        empty = MissingReferences(node_ids=(), way_ids=(), relation_ids=())
        return RelationClosureAudit((), (), empty, ())

    probed_batches: list[tuple[int, ...]] = []

    def run(batch: tuple[int, ...]) -> MissingReferences:
        probed_batches.append(batch)
        result = probe(batch)
        if not isinstance(result, MissingReferences):
            raise SourceContractError(
                "relation closure probe returned an unsupported result"
            )
        return result

    global_missing = run(relation_ids)
    missing_selected = set(global_missing.relation_ids).intersection(relation_ids)
    if missing_selected:
        raise SourceContractError(
            f"selected relation {min(missing_selected)} is absent from the closure source"
        )

    incomplete: dict[int, MissingReferences] = {}
    for relation_id in predicted_incomplete_ids:
        missing = run((relation_id,))
        if missing.count == 0:
            raise SourceContractError(
                f"predicted-incomplete relation {relation_id} has complete closure"
            )
        incomplete[relation_id] = missing

    complete_relation_ids = tuple(
        relation_id
        for relation_id in relation_ids
        if relation_id not in predicted_set
    )
    if complete_relation_ids:
        retained_missing = run(complete_relation_ids)
        if retained_missing.count:
            raise SourceContractError(
                "predicted-complete relation union still has missing references"
            )

    singleton_nodes: set[int] = set()
    singleton_ways: set[int] = set()
    singleton_relations: set[int] = set()
    for missing in incomplete.values():
        singleton_nodes.update(missing.node_ids)
        singleton_ways.update(missing.way_ids)
        singleton_relations.update(missing.relation_ids)
    singleton_union = MissingReferences(
        node_ids=tuple(sorted(singleton_nodes)),
        way_ids=tuple(sorted(singleton_ways)),
        relation_ids=tuple(sorted(singleton_relations)),
    )
    if singleton_union != global_missing:
        raise SourceContractError(
            "relation closure singleton union does not match the all-root diagnostic"
        )

    return RelationClosureAudit(
        complete_relation_ids=complete_relation_ids,
        incomplete_relations=tuple(
            IncompleteRelationClosure(
                relation_id=relation_id,
                missing_references=incomplete[relation_id],
            )
            for relation_id in predicted_incomplete_ids
        ),
        global_missing_references=global_missing,
        probed_batches=tuple(probed_batches),
    )


def classify_regional_pilot_closure(
    dataset: OsmDataset,
    roots: RootSelection,
    missing: MissingReferences,
) -> RegionalClosureClassification:
    """Classify only directly provable boundary clipping in a regional pilot.

    Missing direct-way closure or any missing object that cannot be attributed
    to a selected relation's explicit member list remains fatal. This function
    is intentionally not a whole-world acceptance path.
    """

    if roots != select_roots(dataset):
        raise SourceContractError(
            "regional closure roots do not match the canonical source selection"
        )

    visited_relations: set[int] = set()

    def validate_relation_graph(relation_id: int, stack: tuple[int, ...]) -> None:
        if relation_id in stack:
            cycle = " -> ".join(str(value) for value in stack + (relation_id,))
            raise SourceContractError(f"relation cycle: {cycle}")
        if relation_id in visited_relations:
            return
        relation = dataset.relations.get(relation_id)
        if relation is None:
            return
        next_stack = stack + (relation_id,)
        for member in relation.members:
            if member.object_type == "relation" and member.ref in dataset.relations:
                validate_relation_graph(member.ref, next_stack)
        visited_relations.add(relation_id)

    for relation_id in roots.relation_ids:
        validate_relation_graph(relation_id, ())

    selected_way_ids = set(roots.way_ids)
    selected_relation_ids = set(roots.relation_ids)
    missing_way_ids = set(missing.way_ids)
    missing_relation_ids = set(missing.relation_ids)
    if selected_way_ids & missing_way_ids:
        object_id = min(selected_way_ids & missing_way_ids)
        raise SourceContractError(
            f"selected way {object_id} is absent from the closure source"
        )
    if selected_relation_ids & missing_relation_ids:
        object_id = min(selected_relation_ids & missing_relation_ids)
        raise SourceContractError(
            f"selected relation {object_id} is absent from the closure source"
        )

    missing_node_ids = set(missing.node_ids)
    for way_id in roots.way_ids:
        way = dataset.ways.get(way_id)
        if way is None:
            raise SourceContractError(f"selected way {way_id} is absent from candidates")
        damaged_nodes = missing_node_ids.intersection(way.node_refs)
        if damaged_nodes:
            raise SourceContractError(
                f"selected way {way_id} references missing node {min(damaged_nodes)}"
            )

    missing_keys = {
        *(('node', object_id) for object_id in missing.node_ids),
        *(('way', object_id) for object_id in missing.way_ids),
        *(('relation', object_id) for object_id in missing.relation_ids),
    }
    covered_keys: set[tuple[str, int]] = set()
    direct_missing: dict[int, tuple[OsmRelationMember, ...]] = {}
    for relation_id in roots.relation_ids:
        relation = dataset.relations.get(relation_id)
        if relation is None:
            raise SourceContractError(
                f"selected relation {relation_id} is absent from candidates"
            )
        members = tuple(
            member
            for member in relation.members
            if (member.object_type, member.ref) in missing_keys
        )
        if members:
            direct_missing[relation_id] = members
            covered_keys.update(
                (member.object_type, member.ref) for member in members
            )

    unowned = missing_keys - covered_keys
    if unowned:
        order = {"node": 0, "way": 1, "relation": 2}
        object_type, object_id = min(
            unowned, key=lambda item: (order[item[0]], item[1])
        )
        raise SourceContractError(
            f"missing {object_type} {object_id} is not owned directly by any "
            "selected relation root"
        )

    incomplete = set(direct_missing)
    dependencies: dict[int, tuple[int, ...]] = {}
    changed = True
    while changed:
        changed = False
        for relation_id in roots.relation_ids:
            relation = dataset.relations[relation_id]
            dependency_ids = tuple(
                sorted(
                    {
                        member.ref
                        for member in relation.members
                        if member.object_type == "relation"
                        and member.ref in incomplete
                    }
                )
            )
            if dependency_ids:
                dependencies[relation_id] = dependency_ids
                if relation_id not in incomplete:
                    incomplete.add(relation_id)
                    changed = True

    incomplete_relations = tuple(
        IncompleteRelationRoot(
            relation_id=relation_id,
            direct_missing_members=direct_missing.get(relation_id, ()),
            dependency_relation_ids=dependencies.get(relation_id, ()),
        )
        for relation_id in sorted(incomplete)
    )
    return RegionalClosureClassification(
        complete_way_ids=roots.way_ids,
        complete_relation_ids=tuple(
            relation_id
            for relation_id in roots.relation_ids
            if relation_id not in incomplete
        ),
        incomplete_relations=incomplete_relations,
        missing_references=missing,
    )


def assemble_relation_parts(
    relation: OsmRelation,
    *,
    nodes: Mapping[int, OsmNode],
    ways: Mapping[int, OsmWay],
    relations: Mapping[int, OsmRelation],
    _stack: tuple[int, ...] = (),
) -> tuple[tuple[int, ...], ...]:
    if relation.object_id in _stack:
        cycle = " -> ".join(
            str(value) for value in _stack + (relation.object_id,)
        )
        raise SourceContractError(f"relation cycle: {cycle}")
    stack = _stack + (relation.object_id,)
    member_parts: list[tuple[int, ...]] = []
    for member in relation.members:
        if member.object_type == "way":
            way = ways.get(member.ref)
            if way is None:
                raise SourceContractError(
                    f"relation {relation.object_id} references missing way {member.ref}"
                )
            if len(way.node_refs) < 2:
                raise SourceContractError(
                    f"relation {relation.object_id} way {member.ref} has fewer than two nodes"
                )
            member_parts.append(way.node_refs)
        elif member.object_type == "relation":
            nested = relations.get(member.ref)
            if nested is None:
                raise SourceContractError(
                    f"relation {relation.object_id} references missing relation {member.ref}"
                )
            member_parts.extend(
                assemble_relation_parts(
                    nested,
                    nodes=nodes,
                    ways=ways,
                    relations=relations,
                    _stack=stack,
                )
            )
        elif member.object_type == "node":
            # Point members such as source/mouth remain in the exact source
            # occurrence but cannot contribute coordinates to a line label.
            if member.ref not in nodes:
                raise SourceContractError(
                    f"relation {relation.object_id} references missing node {member.ref}"
                )
            continue
        else:
            raise SourceContractError(
                f"relation {relation.object_id} has unsupported member type "
                f"{member.object_type!r}"
            )

    assembled: list[list[int]] = []
    for part in member_parts:
        if assembled and assembled[-1][-1] == part[0]:
            assembled[-1].extend(part[1:])
        else:
            assembled.append(list(part))
    return tuple(tuple(part) for part in assembled)


def _validated_corridor_e7(
    corridor: tuple[int, int, int, int],
) -> tuple[int, int, int, int]:
    if (
        len(corridor) != 4
        or any(isinstance(value, bool) or not isinstance(value, int) for value in corridor)
    ):
        raise SourceContractError("corridor E7 bounds must contain four integers")
    min_lon, min_lat, max_lon, max_lat = corridor
    if not (-1_800_000_000 <= min_lon <= max_lon <= 1_800_000_000):
        raise SourceContractError("corridor longitude E7 bounds are invalid")
    if not (-900_000_000 <= min_lat <= max_lat <= 900_000_000):
        raise SourceContractError("corridor latitude E7 bounds are invalid")
    return corridor


def _way_bounds_e7(way: OsmWay, nodes: Mapping[int, OsmNode]) -> tuple[int, int, int, int]:
    if not way.node_refs:
        raise SourceContractError(f"way {way.object_id} has no node references")
    try:
        coordinates = [nodes[object_id] for object_id in way.node_refs]
    except KeyError as error:
        raise SourceContractError(
            f"way {way.object_id} references missing node {error.args[0]}"
        ) from error
    return (
        min(node.longitude_e7 for node in coordinates),
        min(node.latitude_e7 for node in coordinates),
        max(node.longitude_e7 for node in coordinates),
        max(node.latitude_e7 for node in coordinates),
    )


def _bounds_intersect(
    left: tuple[int, int, int, int], right: tuple[int, int, int, int]
) -> bool:
    return (
        left[0] <= right[2]
        and left[2] >= right[0]
        and left[1] <= right[3]
        and left[3] >= right[1]
    )


def inspect_osm_xml(
    path: str | Path,
    *,
    corridor_e7: tuple[int, int, int, int] | None = None,
) -> dict[str, object]:
    source = Path(path)
    try:
        raw = source.read_bytes()
    except OSError as error:
        raise SourceContractError(f"OSM XML is unavailable: {source}: {error}") from error
    dataset = parse_osm_xml_bytes(raw, source_label=str(source))
    roots = select_roots(dataset)
    closure = compute_reference_closure(dataset, roots)
    node_ids = closure.reference_only_node_ids
    if node_ids:
        longitudes = [dataset.nodes[object_id].longitude_e7 for object_id in node_ids]
        latitudes = [dataset.nodes[object_id].latitude_e7 for object_id in node_ids]
        bounds: list[int] | None = [
            min(longitudes),
            min(latitudes),
            max(longitudes),
            max(latitudes),
        ]
    else:
        bounds = None
    relation_parts = {
        str(object_id): [list(part) for part in assemble_relation_parts(
            dataset.relations[object_id],
            nodes=dataset.nodes,
            ways=dataset.ways,
            relations=dataset.relations,
        )]
        for object_id in roots.relation_ids
    }
    relation_members = {
        str(object_id): [
            {
                "objectType": member.object_type,
                "ordinal": member.ordinal,
                "ref": member.ref,
                "role": member.role,
            }
            for member in dataset.relations[object_id].members
        ]
        for object_id in roots.relation_ids
    }
    root_objects = {
        "relations": [
            {
                "id": relation.object_id,
                "members": relation_members[str(relation.object_id)],
                "tags": [list(item) for item in relation.tags],
                "timestamp": relation.timestamp,
                "version": relation.version,
            }
            for object_id in roots.relation_ids
            for relation in (dataset.relations[object_id],)
        ],
        "ways": [
            {
                "id": way.object_id,
                "nodeRefs": list(way.node_refs),
                "tags": [list(item) for item in way.tags],
                "timestamp": way.timestamp,
                "version": way.version,
            }
            for object_id in roots.way_ids
            for way in (dataset.ways[object_id],)
        ],
    }
    report: dict[str, object] = {
        "apiVersion": dataset.api_version,
        "closure": {
            "referenceOnlyNodes": len(closure.reference_only_node_ids),
            "referenceOnlyRelations": len(closure.reference_only_relation_ids),
            "referenceOnlyWays": len(closure.reference_only_way_ids),
        },
        "e7Bounds": bounds,
        "generator": dataset.generator,
        "objectCounts": {
            "nodes": len(dataset.nodes),
            "relations": len(dataset.relations),
            "ways": len(dataset.ways),
        },
        "policySha256": hashlib.sha256(canonical_policy_bytes()).hexdigest(),
        "relationMembers": relation_members,
        "relationParts": relation_parts,
        "rootIds": [
            *(f"w{object_id}" for object_id in roots.way_ids),
            *(f"r{object_id}" for object_id in roots.relation_ids),
        ],
        "selectedRoots": {
            "relations": len(roots.relation_ids),
            "ways": len(roots.way_ids),
        },
        "rootObjects": root_objects,
        "sourceBytes": len(raw),
        "sourceSha256": hashlib.sha256(raw).hexdigest(),
    }
    if corridor_e7 is not None:
        corridor = _validated_corridor_e7(corridor_e7)
        report["corridorE7"] = list(corridor)
        report["intersectingRootWayIds"] = [
            object_id
            for object_id in roots.way_ids
            if _bounds_intersect(
                _way_bounds_e7(dataset.ways[object_id], dataset.nodes), corridor
            )
        ]
    return report


def _canonical_json_bytes(document: Mapping[str, object]) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _atomic_write(path: str | Path, content: bytes) -> str:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            dir=target.parent,
            prefix=f".{target.name}.",
            suffix=".tmp",
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            temporary.write(content)
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, target)
        temporary_path = None
    finally:
        if temporary_path is not None:
            try:
                temporary_path.unlink()
            except FileNotFoundError:
                pass
    return hashlib.sha256(content).hexdigest()


def write_inspection_report(
    source_path: str | Path,
    output_path: str | Path,
    *,
    expected_source_sha256: str,
    corridor_e7: tuple[int, int, int, int] | None = None,
) -> str:
    if _SHA256.fullmatch(expected_source_sha256) is None:
        raise SourceContractError("expected source SHA-256 must be 64 hexadecimal digits")
    report = inspect_osm_xml(source_path, corridor_e7=corridor_e7)
    actual = report["sourceSha256"]
    if actual != expected_source_sha256.lower():
        raise SourceContractError(
            "source SHA-256 mismatch: "
            f"expected {expected_source_sha256.lower()}, got {actual}"
        )
    return _atomic_write(output_path, _canonical_json_bytes(report))


def write_policy(output_path: str | Path) -> str:
    return _atomic_write(output_path, canonical_policy_bytes())


def encode_selection_material(candidate_xml: bytes) -> tuple[bytes, bytes]:
    """Return generic root IDs and selection material with no provenance claims."""
    actual_policy_sha256 = hashlib.sha256(canonical_policy_bytes()).hexdigest()
    if actual_policy_sha256 != POLICY_SHA256:
        raise SourceContractError(
            "policy SHA-256 drift: "
            f"expected {POLICY_SHA256}, got {actual_policy_sha256}"
        )
    if type(candidate_xml) is not bytes:
        raise SourceContractError("candidate XML must be immutable bytes")
    dataset = parse_osm_xml_bytes(
        candidate_xml,
        source_label="candidate XML bytes",
    )
    if dataset.nodes:
        raise SourceContractError(
            "candidate XML must not contain nodes; recursive references belong to closure"
        )
    roots = select_roots(dataset)

    id_bytes = b"".join(
        [*(f"w{object_id}\n".encode("ascii") for object_id in roots.way_ids),
         *(f"r{object_id}\n".encode("ascii") for object_id in roots.relation_ids)]
    )
    id_sha256 = hashlib.sha256(id_bytes).hexdigest()
    rejected_way_entries = [
        {"id": object_id, "reason": reason}
        for object_id, way in sorted(dataset.ways.items())
        if (reason := _direct_way_rejection_reason(way)) is not None
    ]
    rejected_relation_entries = [
        {"id": object_id, "reason": reason}
        for object_id, relation in sorted(dataset.relations.items())
        if (reason := _relation_rejection_reason(relation)) is not None
    ]
    rejected_ways = Counter(
        str(entry["reason"]) for entry in rejected_way_entries
    )
    rejected_relations = Counter(
        str(entry["reason"]) for entry in rejected_relation_entries
    )
    root_entries: list[dict[str, object]] = []
    for object_id in roots.way_ids:
        way = dataset.ways[object_id]
        source_object: dict[str, object] = {
            "id": object_id,
            "nodeRefs": list(way.node_refs),
            "objectType": "way",
            "tags": [list(item) for item in way.tags],
            "timestamp": way.timestamp,
            "version": way.version,
        }
        root_entries.append(
            {
                **source_object,
                "displayNames": [list(item) for item in supported_display_names(way.tags)],
                "objectSha256": hashlib.sha256(
                    _canonical_json_bytes(source_object)
                ).hexdigest(),
                "waterway": _tag_map(way.tags)["waterway"],
            }
        )
    for object_id in roots.relation_ids:
        relation = dataset.relations[object_id]
        source_object = {
            "id": object_id,
            "members": [
                {
                    "objectType": member.object_type,
                    "ordinal": member.ordinal,
                    "ref": member.ref,
                    "role": member.role,
                }
                for member in relation.members
            ],
            "objectType": "relation",
            "tags": [list(item) for item in relation.tags],
            "timestamp": relation.timestamp,
            "version": relation.version,
        }
        root_entries.append(
            {
                **source_object,
                "displayNames": [
                    list(item) for item in supported_display_names(relation.tags)
                ],
                "objectSha256": hashlib.sha256(
                    _canonical_json_bytes(source_object)
                ).hexdigest(),
            }
        )
    document: dict[str, object] = {
        "candidateCounts": {
            "nodes": len(dataset.nodes),
            "relations": len(dataset.relations),
            "ways": len(dataset.ways),
        },
        "rejectedRelationCounts": dict(sorted(rejected_relations.items())),
        "rejectedRelations": rejected_relation_entries,
        "rejectedWayCounts": dict(sorted(rejected_ways.items())),
        "rejectedWays": rejected_way_entries,
        "rootIdsSha256": id_sha256,
        "roots": root_entries,
        "schema": "flight-alert-exp8-osm-selection-material-v1",
        "selectedCounts": {
            "relations": len(roots.relation_ids),
            "ways": len(roots.way_ids),
        },
    }
    return id_bytes, _canonical_json_bytes(document)


def verify_maryland_source(path: str | Path) -> VerifiedSourceFile:
    source = Path(path)
    try:
        resolved = source.resolve(strict=True)
        size = resolved.stat().st_size
    except OSError as error:
        raise SourceContractError(f"Maryland source is unavailable: {source}: {error}") from error
    if not resolved.is_file():
        raise SourceContractError(f"Maryland source is not a file: {resolved}")
    if size != MARYLAND_SOURCE_BYTES:
        raise SourceContractError(
            "Maryland source bytes mismatch: "
            f"expected {MARYLAND_SOURCE_BYTES}, got {size}"
        )
    try:
        md5 = hashlib.md5(usedforsecurity=False)
    except TypeError:
        md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    try:
        with resolved.open("rb") as stream:
            while chunk := stream.read(1024 * 1024):
                md5.update(chunk)
                sha256.update(chunk)
    except OSError as error:
        raise SourceContractError(f"Maryland source became unreadable: {resolved}: {error}") from error
    actual_md5 = md5.hexdigest()
    actual_sha256 = sha256.hexdigest()
    if actual_md5 != MARYLAND_SOURCE_MD5:
        raise SourceContractError(
            "Maryland source MD5 mismatch: "
            f"expected {MARYLAND_SOURCE_MD5}, got {actual_md5}"
        )
    if actual_sha256 != MARYLAND_SOURCE_SHA256:
        raise SourceContractError(
            "Maryland source SHA-256 mismatch: "
            f"expected {MARYLAND_SOURCE_SHA256}, got {actual_sha256}"
        )
    return VerifiedSourceFile(
        path=resolved,
        bytes=size,
        md5=actual_md5,
        sha256=actual_sha256,
    )


def verify_locked_chester_fixture(path: str | Path) -> str:
    report = inspect_osm_xml(path, corridor_e7=CHESTER_CORRIDOR_E7)
    actual_source = report["sourceSha256"]
    if actual_source != CHESTER_SOURCE_SHA256:
        raise SourceContractError(
            "Chester source SHA-256 mismatch: "
            f"expected {CHESTER_SOURCE_SHA256}, got {actual_source}"
        )
    report_hash = hashlib.sha256(_canonical_json_bytes(report)).hexdigest()
    if report_hash != CHESTER_INSPECTION_SHA256:
        raise SourceContractError(
            "Chester inspection SHA-256 mismatch: "
            f"expected {CHESTER_INSPECTION_SHA256}, got {report_hash}"
        )
    return report_hash


def build_storage_preflight(
    roots: Mapping[str, str | Path],
    *,
    minimum_free_bytes: Mapping[str, int],
    volume_identities: Mapping[str, Mapping[str, str]],
) -> dict[str, object]:
    if not roots:
        raise SourceContractError("storage preflight requires at least one volume")
    if set(roots) != set(minimum_free_bytes):
        raise SourceContractError(
            "storage preflight roots and minimum-free labels must match exactly"
        )
    if set(roots) != set(volume_identities):
        raise SourceContractError(
            "storage preflight roots and volume-identity labels must match exactly"
        )
    volumes: dict[str, object] = {}
    for label in sorted(roots):
        minimum = minimum_free_bytes[label]
        if isinstance(minimum, bool) or not isinstance(minimum, int) or minimum < 0:
            raise SourceContractError(
                f"storage preflight minimum for {label} must be a nonnegative integer"
            )
        root = os.path.abspath(str(roots[label]))
        identity = volume_identities[label]
        if set(identity) != {"identity", "media"}:
            raise SourceContractError(
                f"storage preflight identity for {label} must contain identity and media"
            )
        if any(
            not isinstance(value, str) or not value.strip()
            for value in identity.values()
        ):
            raise SourceContractError(
                f"storage preflight identity for {label} contains an empty value"
            )
        try:
            usage = shutil.disk_usage(root)
        except OSError as error:
            raise SourceContractError(
                f"storage preflight volume {label} is unavailable: {root}: {error}"
            ) from error
        volumes[label] = {
            "freeBytes": usage.free,
            "identity": identity["identity"],
            "media": identity["media"],
            "minimumFreeBytes": minimum,
            "passed": usage.free >= minimum,
            "root": root,
            "totalBytes": usage.total,
            "usedBytes": usage.used,
        }
    return {
        "policySha256": hashlib.sha256(canonical_policy_bytes()).hexdigest(),
        "schema": "flight-alert-exp8-osm-storage-preflight-v1",
        "toolchain": {
            "boostDebSha256": _BOOST_DEB_SHA256,
            "boostLibrarySha256": _BOOST_LIBRARY_SHA256,
            "libosmiumVersion": "2.15.4",
            "locale": "C.UTF-8",
            "osmiumBinarySha256": _OSMIUM_BINARY_SHA256,
            "osmiumDebSha256": _OSMIUM_DEB_SHA256,
            "osmiumVersion": "1.11.1",
        },
        "volumes": volumes,
    }


def write_storage_preflight(
    output_path: str | Path,
    roots: Mapping[str, str | Path],
    *,
    minimum_free_bytes: Mapping[str, int],
    volume_identities: Mapping[str, Mapping[str, str]],
) -> str:
    report = build_storage_preflight(
        roots,
        minimum_free_bytes=minimum_free_bytes,
        volume_identities=volume_identities,
    )
    for label, raw_volume in report["volumes"].items():
        volume = raw_volume
        if not volume["passed"]:
            raise SourceContractError(
                f"storage preflight {label} failed: free {volume['freeBytes']}, "
                f"requires {volume['minimumFreeBytes']}"
            )
    return _atomic_write(output_path, _canonical_json_bytes(report))


__all__ = [
    "ALLOWED_DIRECT_WATERWAY_VALUES",
    "CHESTER_CORRIDOR_E7",
    "CHESTER_INSPECTION_SHA256",
    "CHESTER_SOURCE_SHA256",
    "EXPLICITLY_EXCLUDED_WATERWAY_VALUES",
    "IncompleteRelationRoot",
    "IncompleteRelationClosure",
    "MissingReferences",
    "OsmDataset",
    "OsmNode",
    "OsmRelation",
    "OsmRelationMember",
    "OsmWay",
    "OsmXmlLimits",
    "MARYLAND_SOURCE_BYTES",
    "MARYLAND_SOURCE_MD5",
    "MARYLAND_SOURCE_SHA256",
    "MARYLAND_SOURCE_URL",
    "MARYLAND_REGIONAL_PROFILE",
    "POLICY_SHA256",
    "ReferenceClosure",
    "RegionalClosureClassification",
    "RelationClosureAudit",
    "RootSelection",
    "SourceContractError",
    "VerifiedSourceFile",
    "assemble_relation_parts",
    "audit_relation_root_closures",
    "audit_predicted_relation_root_closures",
    "build_storage_preflight",
    "canonical_policy_bytes",
    "classify_regional_pilot_closure",
    "compute_reference_closure",
    "encode_selection_material",
    "inspect_osm_xml",
    "is_direct_way_root",
    "is_named_waterway_relation_root",
    "parse_e7",
    "parse_osmium_missing_references",
    "parse_osm_xml",
    "parse_osm_xml_bytes",
    "select_roots",
    "require_zero_missing_references",
    "supported_display_names",
    "verify_locked_chester_fixture",
    "verify_maryland_source",
    "write_inspection_report",
    "write_policy",
    "write_storage_preflight",
]
