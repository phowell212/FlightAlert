from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import Mapping
from xml.parsers import expat


LOCKED_SOURCE_WSL_PATH = (
    "/mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/source/"
    "maryland-260710.osm.pbf"
)
BROAD_EXTRACTION_OUTPUT_WSL_PATH = (
    "/mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/selection/"
    "waterway-broad-v1.osm"
)

_WSL_EXECUTABLE = r"C:\Windows\System32\wsl.exe"
_WSL_DISTRIBUTION = "Ubuntu"
_LOCALE = "C.UTF-8"
_RUNTIME_ROOT = "/home/haquilus/flightalert-exp8-tools/osmium-1.11.1/root"
_OSMIUM_BINARY = f"{_RUNTIME_ROOT}/usr/bin/osmium"
_RUNTIME_LIBRARY_PATH = f"{_RUNTIME_ROOT}/usr/lib/x86_64-linux-gnu"
_GENERATOR = "flight-alert-exp8-osmium-1.11.1"
_BROAD_FILTERS = ("w/waterway", "r/type=waterway")

_MAX_OSM_OBJECT_ID = (1 << 63) - 1
_POSITIVE_DECIMAL = re.compile(r"[1-9][0-9]*\Z")
_CANONICAL_ROOT_ID = re.compile(r"([wr])([1-9][0-9]*)\Z")
_TIMESTAMP = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z")
_FORBIDDEN_XML_DECLARATION = re.compile(
    br"<!\s*(?:DOCTYPE|ENTITY)\b",
    re.IGNORECASE,
)
_LANGUAGE_NAME_KEY = re.compile(
    r"name:([A-Za-z]{2,3})(?:-([A-Za-z0-9]{2,8}))*\Z"
)

_ALLOWED_DIRECT_WATERWAY_VALUES = frozenset(
    {"river", "stream", "canal", "tidal_channel", "wadi"}
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
_LIFECYCLE_KEYS = (
    "abandoned",
    "construction",
    "demolished",
    "disused",
    "proposed",
    "razed",
)
_FALSE_TAG_VALUES = frozenset({"", "0", "false", "no"})
_SELECTION_MATERIAL_KEYS = frozenset(
    {
        "candidateCounts",
        "rejectedRelationCounts",
        "rejectedRelations",
        "rejectedWayCounts",
        "rejectedWays",
        "rootIdsSha256",
        "roots",
        "schema",
        "selectedCounts",
    }
)
_SELECTION_MATERIAL_SCHEMA = "flight-alert-exp8-osm-selection-material-v1"
_REPORT_SCHEMA = (
    "flight-alert-exp8-osm-independent-selection-verification-report-v1"
)
_GENERIC_PROFILE = "flight-alert-exp8-osm-independent-selection-generic-v1"


class IndependentSelectionVerificationError(ValueError):
    """Broad OSM candidates do not exactly support the claimed root selection."""


@dataclass(frozen=True, slots=True)
class VerificationLimits:
    max_broad_xml_bytes: int = 256 * 1024 * 1024
    max_objects: int = 2_000_000
    max_references: int = 10_000_000
    max_tags: int = 5_000_000
    max_text_utf8_bytes: int = 16 * 1024
    max_root_ids_bytes: int = 8 * 1024 * 1024
    max_selection_material_bytes: int = 128 * 1024 * 1024

    def __post_init__(self) -> None:
        for label, value in (
            ("broad XML byte", self.max_broad_xml_bytes),
            ("OSM object", self.max_objects),
            ("OSM reference", self.max_references),
            ("OSM tag", self.max_tags),
            ("OSM text UTF-8 byte", self.max_text_utf8_bytes),
            ("root ID byte", self.max_root_ids_bytes),
            ("selection material byte", self.max_selection_material_bytes),
        ):
            if type(value) is not int or value < 0:
                raise IndependentSelectionVerificationError(
                    f"{label} ceiling must be a nonnegative integer"
                )


def _validated_wsl_path(value: str, label: str) -> str:
    if type(value) is not str or not value or "\\" in value:
        raise IndependentSelectionVerificationError(
            f"{label} must be an absolute WSL path"
        )
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in value):
        raise IndependentSelectionVerificationError(
            f"{label} contains a forbidden control character"
        )
    parsed = PurePosixPath(value)
    if not parsed.is_absolute() or ".." in parsed.parts or str(parsed) != value:
        raise IndependentSelectionVerificationError(
            f"{label} must be a normalized absolute WSL path"
        )
    return value


@dataclass(frozen=True, slots=True)
class BroadExtractionCommandContract:
    """Data-only contract for the future broad extraction; it cannot execute."""

    source_wsl_path: str = LOCKED_SOURCE_WSL_PATH
    output_wsl_path: str = BROAD_EXTRACTION_OUTPUT_WSL_PATH

    def __post_init__(self) -> None:
        source = _validated_wsl_path(self.source_wsl_path, "source path")
        output = _validated_wsl_path(self.output_wsl_path, "output path")
        if source == output:
            raise IndependentSelectionVerificationError(
                "source and broad extraction output paths must be different"
            )

    @property
    def argv(self) -> tuple[str, ...]:
        return (
            _WSL_EXECUTABLE,
            "-d",
            _WSL_DISTRIBUTION,
            "--",
            "/usr/bin/env",
            "-i",
            f"LC_ALL={_LOCALE}",
            f"LANG={_LOCALE}",
            "LANGUAGE=C",
            f"LD_LIBRARY_PATH={_RUNTIME_LIBRARY_PATH}",
            _OSMIUM_BINARY,
            "tags-filter",
            "--no-progress",
            "-R",
            "--generator",
            _GENERATOR,
            "-f",
            "osm",
            "-O",
            "-o",
            self.output_wsl_path,
            self.source_wsl_path,
            *_BROAD_FILTERS,
        )


@dataclass(frozen=True, slots=True)
class _Way:
    object_id: int
    version: int
    timestamp: str
    node_refs: tuple[int, ...]
    tags: tuple[tuple[str, str], ...]


@dataclass(frozen=True, slots=True)
class _RelationMember:
    object_type: str
    ref: int
    role: str
    ordinal: int


@dataclass(frozen=True, slots=True)
class _Relation:
    object_id: int
    version: int
    timestamp: str
    members: tuple[_RelationMember, ...]
    tags: tuple[tuple[str, str], ...]


@dataclass(frozen=True, slots=True)
class _BroadDataset:
    ways: Mapping[int, _Way]
    relations: Mapping[int, _Relation]


@dataclass(frozen=True, slots=True)
class IndependentSelectionVerificationResult:
    report_bytes: bytes
    report_sha256: str


def _canonical_json_bytes(document: Mapping[str, object]) -> bytes:
    try:
        text = json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
    except (TypeError, ValueError, UnicodeEncodeError, RecursionError) as error:
        raise IndependentSelectionVerificationError(
            f"document cannot be canonically encoded: {error}"
        ) from error
    return (text + "\n").encode("utf-8")


def _positive_integer(value: str | None, label: str) -> int:
    if type(value) is not str or _POSITIVE_DECIMAL.fullmatch(value) is None:
        raise IndependentSelectionVerificationError(
            f"{label} must use canonical positive decimal syntax"
        )
    parsed = int(value)
    if parsed > _MAX_OSM_OBJECT_ID:
        raise IndependentSelectionVerificationError(
            f"{label} exceeds the positive signed-63 OSM ID ceiling"
        )
    return parsed


def _canonical_timestamp(value: str | None, label: str) -> str:
    if type(value) is not str or _TIMESTAMP.fullmatch(value) is None:
        raise IndependentSelectionVerificationError(
            f"{label} timestamp is missing or not canonical UTC"
        )
    return value


class _BoundedBroadXmlParser:
    def __init__(self, limits: VerificationLimits) -> None:
        self._limits = limits
        self._stack: list[str] = []
        self._ways: dict[int, _Way] = {}
        self._relations: dict[int, _Relation] = {}
        self._current_kind: str | None = None
        self._current_id: int | None = None
        self._current_version: int | None = None
        self._current_timestamp: str | None = None
        self._current_refs: list[int] = []
        self._current_members: list[_RelationMember] = []
        self._current_tags: dict[str, str] = {}
        self._objects = 0
        self._references = 0
        self._tags = 0

    def parse(self, raw: bytes) -> _BroadDataset:
        parser = expat.ParserCreate()
        parser.buffer_text = True
        parser.StartElementHandler = self._start_element
        parser.EndElementHandler = self._end_element
        parser.CharacterDataHandler = self._character_data
        parser.XmlDeclHandler = self._xml_declaration
        parser.ProcessingInstructionHandler = self._processing_instruction
        parser.StartDoctypeDeclHandler = self._forbidden_declaration
        parser.EntityDeclHandler = self._forbidden_declaration
        parser.UnparsedEntityDeclHandler = self._forbidden_declaration
        parser.ExternalEntityRefHandler = self._forbidden_external_entity
        parser.SkippedEntityHandler = self._forbidden_declaration
        parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
        try:
            parser.Parse(raw, True)
        except IndependentSelectionVerificationError:
            raise
        except expat.ExpatError as error:
            raise IndependentSelectionVerificationError(
                f"broad OSM XML is invalid: {error}"
            ) from error
        if self._stack:
            raise IndependentSelectionVerificationError(
                "broad OSM XML ended with unclosed elements"
            )
        return _BroadDataset(ways=self._ways, relations=self._relations)

    def _xml_declaration(
        self,
        version: str,
        encoding: str | None,
        standalone: int,
    ) -> None:
        del standalone
        if version != "1.0" or (
            encoding is not None and encoding.casefold() != "utf-8"
        ):
            raise IndependentSelectionVerificationError(
                "broad OSM XML declaration must use XML 1.0 and UTF-8"
            )

    def _forbidden_declaration(self, *args: object) -> None:
        del args
        raise IndependentSelectionVerificationError(
            "broad OSM XML DTD and entity declarations are forbidden"
        )

    def _forbidden_external_entity(self, *args: object) -> int:
        del args
        raise IndependentSelectionVerificationError(
            "broad OSM XML external entities are forbidden"
        )

    def _processing_instruction(self, target: str, data: str) -> None:
        del target, data
        raise IndependentSelectionVerificationError(
            "broad OSM XML processing instructions are forbidden"
        )

    def _check_text(self, value: str, label: str, *, allow_empty: bool = True) -> str:
        if not allow_empty and not value:
            raise IndependentSelectionVerificationError(f"{label} must not be empty")
        if len(value.encode("utf-8")) > self._limits.max_text_utf8_bytes:
            raise IndependentSelectionVerificationError(
                f"{label} exceeds the UTF-8 byte ceiling"
            )
        return value

    def _increment(self, field: str) -> None:
        if field == "objects":
            self._objects += 1
            value, ceiling = self._objects, self._limits.max_objects
        elif field == "references":
            self._references += 1
            value, ceiling = self._references, self._limits.max_references
        else:
            self._tags += 1
            value, ceiling = self._tags, self._limits.max_tags
        if value > ceiling:
            raise IndependentSelectionVerificationError(
                f"broad OSM XML exceeds the {field} ceiling {ceiling}"
            )

    def _begin_object(self, kind: str, attributes: dict[str, str]) -> None:
        self._increment("objects")
        object_id = _positive_integer(attributes.get("id"), f"{kind} ID")
        self._current_kind = kind
        self._current_id = object_id
        self._current_version = _positive_integer(
            attributes.get("version"),
            f"{kind} {object_id} version",
        )
        self._current_timestamp = _canonical_timestamp(
            attributes.get("timestamp"),
            f"{kind} {object_id}",
        )
        self._current_refs = []
        self._current_members = []
        self._current_tags = {}

    def _start_element(self, name: str, attributes: dict[str, str]) -> None:
        parent = self._stack[-1] if self._stack else None
        if parent is None:
            if name != "osm" or attributes.get("version") != "0.6":
                raise IndependentSelectionVerificationError(
                    "broad OSM XML root must be osm version 0.6"
                )
            generator = attributes.get("generator", "")
            self._check_text(generator, "OSM generator")
        elif parent == "osm":
            if name in {"way", "relation"}:
                if self._current_kind is not None:
                    raise IndependentSelectionVerificationError(
                        "broad OSM XML objects must not overlap"
                    )
                self._begin_object(name, attributes)
            elif name not in {"bounds", "note", "meta"}:
                raise IndependentSelectionVerificationError(
                    f"unexpected broad OSM XML object {name!r}"
                )
        elif parent == "way" and name == "nd":
            self._increment("references")
            assert self._current_id is not None
            self._current_refs.append(
                _positive_integer(
                    attributes.get("ref"),
                    f"way {self._current_id} node ref",
                )
            )
        elif parent == "relation" and name == "member":
            self._increment("references")
            assert self._current_id is not None
            object_type = attributes.get("type", "")
            if object_type not in {"node", "way", "relation"}:
                raise IndependentSelectionVerificationError(
                    f"relation {self._current_id} has unsupported member type"
                )
            role = self._check_text(
                attributes.get("role", ""),
                f"relation {self._current_id} member role",
            )
            self._current_members.append(
                _RelationMember(
                    object_type=object_type,
                    ref=_positive_integer(
                        attributes.get("ref"),
                        f"relation {self._current_id} member ref",
                    ),
                    role=role,
                    ordinal=len(self._current_members),
                )
            )
        elif parent in {"way", "relation"} and name == "tag":
            self._increment("tags")
            assert self._current_id is not None
            key = attributes.get("k")
            value = attributes.get("v")
            if key is None or value is None:
                raise IndependentSelectionVerificationError(
                    f"{parent} {self._current_id} contains an invalid tag"
                )
            self._check_text(key, "OSM tag key", allow_empty=False)
            self._check_text(value, "OSM tag value")
            if key in self._current_tags:
                raise IndependentSelectionVerificationError(
                    f"{parent} {self._current_id} contains duplicate tag {key!r}"
                )
            self._current_tags[key] = value
        else:
            raise IndependentSelectionVerificationError(
                f"unexpected nested broad OSM XML element {name!r}"
            )
        self._stack.append(name)

    def _end_element(self, name: str) -> None:
        if not self._stack or self._stack[-1] != name:
            raise IndependentSelectionVerificationError(
                f"broad OSM XML element {name!r} closed out of order"
            )
        if name in {"way", "relation"}:
            self._finish_object(name)
        self._stack.pop()

    def _finish_object(self, kind: str) -> None:
        if (
            self._current_kind != kind
            or self._current_id is None
            or self._current_version is None
            or self._current_timestamp is None
        ):
            raise IndependentSelectionVerificationError(
                "broad OSM XML object state is inconsistent"
            )
        tags = tuple(sorted(self._current_tags.items()))
        if kind == "way":
            if "waterway" not in self._current_tags:
                raise IndependentSelectionVerificationError(
                    f"broad extraction contains non-waterway way {self._current_id}"
                )
            if self._current_id in self._ways:
                raise IndependentSelectionVerificationError(
                    f"broad extraction contains duplicate way ID {self._current_id}"
                )
            self._ways[self._current_id] = _Way(
                object_id=self._current_id,
                version=self._current_version,
                timestamp=self._current_timestamp,
                node_refs=tuple(self._current_refs),
                tags=tags,
            )
        else:
            if self._current_tags.get("type") != "waterway":
                raise IndependentSelectionVerificationError(
                    f"broad extraction contains non-waterway relation {self._current_id}"
                )
            if self._current_id in self._relations:
                raise IndependentSelectionVerificationError(
                    f"broad extraction contains duplicate relation ID {self._current_id}"
                )
            self._relations[self._current_id] = _Relation(
                object_id=self._current_id,
                version=self._current_version,
                timestamp=self._current_timestamp,
                members=tuple(self._current_members),
                tags=tags,
            )
        self._current_kind = None
        self._current_id = None
        self._current_version = None
        self._current_timestamp = None
        self._current_refs = []
        self._current_members = []
        self._current_tags = {}

    def _character_data(self, data: str) -> None:
        if data.strip() and (not self._stack or self._stack[-1] != "note"):
            raise IndependentSelectionVerificationError(
                "broad OSM XML contains unexpected character data"
            )


def _parse_broad_xml(raw: bytes, limits: VerificationLimits) -> _BroadDataset:
    if type(raw) is not bytes:
        raise IndependentSelectionVerificationError(
            "broad OSM XML must be exact immutable bytes"
        )
    if len(raw) > limits.max_broad_xml_bytes:
        raise IndependentSelectionVerificationError(
            f"broad OSM XML exceeds the {limits.max_broad_xml_bytes}-byte ceiling"
        )
    if _FORBIDDEN_XML_DECLARATION.search(raw) is not None:
        raise IndependentSelectionVerificationError(
            "broad OSM XML DTD and entity declarations are forbidden"
        )
    return _BoundedBroadXmlParser(limits).parse(raw)


def parse_canonical_root_ids(
    raw: bytes,
    *,
    limits: VerificationLimits = VerificationLimits(),
) -> tuple[tuple[int, ...], tuple[int, ...]]:
    if not isinstance(limits, VerificationLimits):
        raise IndependentSelectionVerificationError("verification limits are invalid")
    if type(raw) is not bytes:
        raise IndependentSelectionVerificationError(
            "root IDs must be exact immutable bytes"
        )
    if len(raw) > limits.max_root_ids_bytes:
        raise IndependentSelectionVerificationError(
            f"root IDs exceed the {limits.max_root_ids_bytes}-byte ceiling"
        )
    if not raw:
        return (), ()
    if b"\r" in raw or not raw.endswith(b"\n"):
        raise IndependentSelectionVerificationError(
            "root IDs must be canonical ASCII records with final LF"
        )
    try:
        lines = raw.decode("ascii", errors="strict").split("\n")[:-1]
    except UnicodeDecodeError as error:
        raise IndependentSelectionVerificationError(
            "root IDs must be canonical ASCII"
        ) from error
    way_ids: list[int] = []
    relation_ids: list[int] = []
    previous_way = 0
    previous_relation = 0
    relation_seen = False
    for line in lines:
        match = _CANONICAL_ROOT_ID.fullmatch(line)
        if match is None:
            raise IndependentSelectionVerificationError(
                f"root ID record is not canonical: {line!r}"
            )
        object_type, digits = match.groups()
        object_id = int(digits)
        if object_id > _MAX_OSM_OBJECT_ID:
            raise IndependentSelectionVerificationError(
                "root ID exceeds the positive signed-63 OSM ID ceiling"
            )
        if object_type == "w":
            if relation_seen or object_id <= previous_way:
                raise IndependentSelectionVerificationError(
                    "way root IDs must be unique, sorted, and precede relations"
                )
            way_ids.append(object_id)
            previous_way = object_id
        else:
            relation_seen = True
            if object_id <= previous_relation:
                raise IndependentSelectionVerificationError(
                    "relation root IDs must be unique and sorted"
                )
            relation_ids.append(object_id)
            previous_relation = object_id
    return tuple(way_ids), tuple(relation_ids)


def parse_canonical_selection_material(
    raw: bytes,
    *,
    limits: VerificationLimits = VerificationLimits(),
) -> dict[str, object]:
    if not isinstance(limits, VerificationLimits):
        raise IndependentSelectionVerificationError("verification limits are invalid")
    if type(raw) is not bytes:
        raise IndependentSelectionVerificationError(
            "selection material must be exact immutable bytes"
        )
    if len(raw) > limits.max_selection_material_bytes:
        raise IndependentSelectionVerificationError(
            "selection material exceeds the "
            f"{limits.max_selection_material_bytes}-byte ceiling"
        )
    if not raw.endswith(b"\n") or b"\r" in raw or raw.startswith(b"\xef\xbb\xbf"):
        raise IndependentSelectionVerificationError(
            "selection material must be canonical UTF-8 JSON with final LF"
        )
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise IndependentSelectionVerificationError(
            "selection material is not strict UTF-8"
        ) from error

    def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        document: dict[str, object] = {}
        for key, value in pairs:
            if key in document:
                raise IndependentSelectionVerificationError(
                    f"selection material contains duplicate JSON key {key!r}"
                )
            document[key] = value
        return document

    def reject_constant(value: str) -> object:
        raise IndependentSelectionVerificationError(
            f"selection material contains forbidden JSON number {value!r}"
        )

    try:
        decoded = json.loads(
            text,
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except IndependentSelectionVerificationError:
        raise
    except (json.JSONDecodeError, RecursionError, ValueError) as error:
        raise IndependentSelectionVerificationError(
            f"selection material is not strict JSON: {error}"
        ) from error
    if type(decoded) is not dict:
        raise IndependentSelectionVerificationError(
            "selection material JSON root must be an object"
        )
    if set(decoded) != _SELECTION_MATERIAL_KEYS:
        raise IndependentSelectionVerificationError(
            "selection material has an unexpected exact schema"
        )
    if decoded.get("schema") != _SELECTION_MATERIAL_SCHEMA:
        raise IndependentSelectionVerificationError(
            "selection material schema identifier is not exact"
        )
    if _canonical_json_bytes(decoded) != raw:
        raise IndependentSelectionVerificationError(
            "selection material JSON is not strictly canonical"
        )
    return decoded


def _supported_display_names(
    tags: tuple[tuple[str, str], ...],
) -> tuple[tuple[str, str], ...]:
    selected: list[tuple[str, str]] = []
    for key, value in tags:
        supported = key in _DIRECT_DISPLAY_NAME_KEYS
        if not supported and _LANGUAGE_NAME_KEY.fullmatch(key) is not None:
            supported = not any(
                subtag.casefold() in _NON_LANGUAGE_NAME_SUFFIXES
                for subtag in key[5:].split("-")
            )
        if supported and bool(value.strip()):
            selected.append((key, value))
    return tuple(sorted(selected))


def _way_rejection_reason(way: _Way) -> str | None:
    tags = dict(way.tags)
    if tags.get("waterway") not in _ALLOWED_DIRECT_WATERWAY_VALUES:
        return "unsupported_waterway"
    if len(way.node_refs) < 2:
        return "insufficient_geometry"
    if way.node_refs[0] == way.node_refs[-1]:
        return "closed"
    if tags.get("area", "").strip().casefold() not in _FALSE_TAG_VALUES:
        return "area"
    for key in _LIFECYCLE_KEYS:
        if tags.get(key, "").strip().casefold() not in _FALSE_TAG_VALUES:
            return "lifecycle"
        if f"{key}:waterway" in tags:
            return "lifecycle"
    if not _supported_display_names(way.tags):
        return "no_display_name"
    return None


def _relation_rejection_reason(relation: _Relation) -> str | None:
    tags = dict(relation.tags)
    if tags.get("type") != "waterway":
        return "unsupported_type"
    if not _supported_display_names(relation.tags):
        return "no_display_name"
    return None


def _selection_document(
    dataset: _BroadDataset,
    root_ids: bytes,
) -> tuple[dict[str, object], tuple[int, ...], tuple[int, ...]]:
    rejected_ways: list[dict[str, object]] = []
    selected_way_ids: list[int] = []
    for object_id, way in sorted(dataset.ways.items()):
        reason = _way_rejection_reason(way)
        if reason is None:
            selected_way_ids.append(object_id)
        else:
            rejected_ways.append({"id": object_id, "reason": reason})

    rejected_relations: list[dict[str, object]] = []
    selected_relation_ids: list[int] = []
    for object_id, relation in sorted(dataset.relations.items()):
        reason = _relation_rejection_reason(relation)
        if reason is None:
            selected_relation_ids.append(object_id)
        else:
            rejected_relations.append({"id": object_id, "reason": reason})

    roots: list[dict[str, object]] = []
    for object_id in selected_way_ids:
        way = dataset.ways[object_id]
        source_object: dict[str, object] = {
            "id": object_id,
            "nodeRefs": list(way.node_refs),
            "objectType": "way",
            "tags": [list(item) for item in way.tags],
            "timestamp": way.timestamp,
            "version": way.version,
        }
        roots.append(
            {
                **source_object,
                "displayNames": [
                    list(item) for item in _supported_display_names(way.tags)
                ],
                "objectSha256": hashlib.sha256(
                    _canonical_json_bytes(source_object)
                ).hexdigest(),
                "waterway": dict(way.tags)["waterway"],
            }
        )
    for object_id in selected_relation_ids:
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
        roots.append(
            {
                **source_object,
                "displayNames": [
                    list(item)
                    for item in _supported_display_names(relation.tags)
                ],
                "objectSha256": hashlib.sha256(
                    _canonical_json_bytes(source_object)
                ).hexdigest(),
            }
        )

    way_reason_counts = Counter(str(item["reason"]) for item in rejected_ways)
    relation_reason_counts = Counter(
        str(item["reason"]) for item in rejected_relations
    )
    document: dict[str, object] = {
        "candidateCounts": {
            "nodes": 0,
            "relations": len(dataset.relations),
            "ways": len(dataset.ways),
        },
        "rejectedRelationCounts": dict(sorted(relation_reason_counts.items())),
        "rejectedRelations": rejected_relations,
        "rejectedWayCounts": dict(sorted(way_reason_counts.items())),
        "rejectedWays": rejected_ways,
        "rootIdsSha256": hashlib.sha256(root_ids).hexdigest(),
        "roots": roots,
        "schema": _SELECTION_MATERIAL_SCHEMA,
        "selectedCounts": {
            "relations": len(selected_relation_ids),
            "ways": len(selected_way_ids),
        },
    }
    return document, tuple(selected_way_ids), tuple(selected_relation_ids)


def verify_independent_selection(
    broad_xml: bytes,
    root_ids: bytes,
    selection_material: bytes,
    *,
    limits: VerificationLimits = VerificationLimits(),
) -> IndependentSelectionVerificationResult:
    """Independently reconcile production selection bytes to a broad OSM superset."""

    if not isinstance(limits, VerificationLimits):
        raise IndependentSelectionVerificationError("verification limits are invalid")
    parsed_way_ids, parsed_relation_ids = parse_canonical_root_ids(
        root_ids,
        limits=limits,
    )
    parsed_material = parse_canonical_selection_material(
        selection_material,
        limits=limits,
    )
    dataset = _parse_broad_xml(broad_xml, limits)
    expected_document, expected_way_ids, expected_relation_ids = _selection_document(
        dataset,
        root_ids,
    )
    if (
        parsed_way_ids != expected_way_ids
        or parsed_relation_ids != expected_relation_ids
    ):
        raise IndependentSelectionVerificationError(
            "production root IDs contain additions, omissions, or changed candidates"
        )
    expected_material = _canonical_json_bytes(expected_document)
    if parsed_material != expected_document or selection_material != expected_material:
        raise IndependentSelectionVerificationError(
            "production selection material does not exactly reconcile to broad input"
        )

    report: dict[str, object] = {
        "candidateEnvelope": {
            "relationTag": ["type", "waterway"],
            "wayTagKey": "waterway",
        },
        "broadInput": {
            "bytes": len(broad_xml),
            "sha256": hashlib.sha256(broad_xml).hexdigest(),
        },
        "candidateCounts": expected_document["candidateCounts"],
        "profile": _GENERIC_PROFILE,
        "rejectedRelationCounts": expected_document["rejectedRelationCounts"],
        "rejectedWayCounts": expected_document["rejectedWayCounts"],
        "rootIds": {
            "bytes": len(root_ids),
            "sha256": hashlib.sha256(root_ids).hexdigest(),
        },
        "schema": _REPORT_SCHEMA,
        "selectedCounts": expected_document["selectedCounts"],
        "selectedRootIds": [
            *(f"w{object_id}" for object_id in expected_way_ids),
            *(f"r{object_id}" for object_id in expected_relation_ids),
        ],
        "selectionMaterial": {
            "bytes": len(selection_material),
            "sha256": hashlib.sha256(selection_material).hexdigest(),
        },
        "verified": True,
    }
    report_bytes = _canonical_json_bytes(report)
    return IndependentSelectionVerificationResult(
        report_bytes=report_bytes,
        report_sha256=hashlib.sha256(report_bytes).hexdigest(),
    )


__all__ = [
    "BROAD_EXTRACTION_OUTPUT_WSL_PATH",
    "LOCKED_SOURCE_WSL_PATH",
    "BroadExtractionCommandContract",
    "IndependentSelectionVerificationError",
    "IndependentSelectionVerificationResult",
    "VerificationLimits",
    "parse_canonical_root_ids",
    "parse_canonical_selection_material",
    "verify_independent_selection",
]
