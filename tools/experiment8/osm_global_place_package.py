from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from types import MappingProxyType
from typing import BinaryIO, Iterator, Mapping, Sequence

from .osm_hydro_source import OsmNode


EXPECTED_PLANET_PATH = Path(
    r"D:\FlightAlert-test-artifacts\experiment 8\planet-260629-reacquire\planet-260629.osm.pbf"
)
EXPECTED_PLANET_BYTES = 93_653_630_756
EXPECTED_PLANET_SHA256 = (
    "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
)

PINNED_OSMIUM_VERSION = "1.11.1"
PINNED_LIBOSMIUM_VERSION = "2.15.4"
OSMIUM_RUNTIME_ROOT = "/home/haquilus/flightalert-exp8-tools/osmium-1.11.1/root"
OSMIUM_BINARY_PATH = f"{OSMIUM_RUNTIME_ROOT}/usr/bin/osmium"
OSMIUM_LIBRARY_PATH = f"{OSMIUM_RUNTIME_ROOT}/usr/lib/x86_64-linux-gnu"
BOOST_LIBRARY_PATH = (
    f"{OSMIUM_LIBRARY_PATH}/libboost_program_options.so.1.71.0"
)
OSMIUM_BINARY_SHA256 = (
    "5575922905fb39fa87262e74ed2b0ac367086b5439468339e45bf3720c1821fc"
)
OSMIUM_DEB_SHA256 = (
    "d8e791ac3558aaafa95d3f6ac7329b15df2fb502bd6babff881e62830e49f906"
)
BOOST_LIBRARY_SHA256 = (
    "16a89b0d75de54bfef18b479eb1d38710e5c242246a17baffa11eb4f2d544663"
)

_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_POSITIVE_DECIMAL_RE = re.compile(r"[1-9][0-9]*\Z")
_NONNEGATIVE_DECIMAL_RE = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_COORDINATE_RE = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?\Z")
_TIMESTAMP_RE = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z"
)
_HEX = frozenset("0123456789abcdefABCDEF")
_DIRECT_OPL_SPECIAL = frozenset(" ,=@")
_MAX_SIGNED_63 = (1 << 63) - 1
_MAX_U32 = (1 << 32) - 1
_MAX_TEXT_UTF8_BYTES = 65_535
_MAX_LINE_BYTES = 4 * 1024 * 1024
_ADMITTED_PLACE_TAG_ORDER = ("capital", "name", "name:en", "place", "population")
_ADMITTED_PLACE_TAG_KEYS = frozenset(_ADMITTED_PLACE_TAG_ORDER)
_SEMANTIC_ADMISSION_POLICY_DOCUMENT = {
    "admittedValueKeys": list(_ADMITTED_PLACE_TAG_ORDER),
    "admittedValueRule": "Unicode general-category Cc excludes the whole node",
    "irrelevantValueRule": "validate OPL escape/scalar/UTF-8 bounds without materializing text",
    "schema": "flightalert.experiment8.osm-place-semantic-admission-policy.v1",
}
SEMANTIC_ADMISSION_POLICY_SHA256 = hashlib.sha256(
    (
        json.dumps(
            _SEMANTIC_ADMISSION_POLICY_DOCUMENT,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8", "strict")
).hexdigest()
RENDERER_SEMANTIC_OUTCOME_SCHEMA = (
    "flightalert.experiment8.osm-global-place-semantic-outcome-audit.v1"
)
RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES = hashlib.sha256().digest_size


class GlobalPlacePackageError(ValueError):
    """A global place extraction or package would violate its source contract."""


@dataclass(frozen=True, slots=True)
class StrictOplNode:
    node: OsmNode
    line_number: int
    byte_start: int
    byte_end: int
    admitted_control_fields: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class FileIdentity:
    path: Path
    bytes: int
    sha256: str
    stat_signature: tuple[int, int, int, int, int]


@dataclass(frozen=True, slots=True)
class OsmiumCommand:
    role: str
    arguments: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class GlobalPlaceExtractionResult:
    state: str
    output_directory: Path
    receipt: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class PlaceSemanticAdmissionAudit:
    node_count: int
    excluded_node_count: int
    control_capital_values: int
    control_english_names: int
    control_place_values: int
    control_population_values: int
    control_primary_names: int

    def __post_init__(self) -> None:
        values = (
            self.node_count,
            self.excluded_node_count,
            self.control_capital_values,
            self.control_english_names,
            self.control_place_values,
            self.control_population_values,
            self.control_primary_names,
        )
        if any(type(value) is not int or value < 0 for value in values):
            raise GlobalPlacePackageError(
                "semantic admission audit counts must be nonnegative integers"
            )
        if self.excluded_node_count > self.node_count:
            raise GlobalPlacePackageError(
                "semantic admission excluded-node count exceeds input nodes"
            )

    @classmethod
    def from_documents(
        cls,
        strict_audit: Mapping[str, object],
        semantic_audit: Mapping[str, object],
    ) -> "PlaceSemanticAdmissionAudit":
        field_counts = semantic_audit.get("controlCharacterFields")
        if (
            not isinstance(field_counts, dict)
            or semantic_audit.get("decodedValueAllowlist")
            != list(_ADMITTED_PLACE_TAG_ORDER)
        ):
            raise GlobalPlacePackageError("semantic admission audit shape differs")
        try:
            return cls(
                node_count=strict_audit["nodeCount"],
                excluded_node_count=semantic_audit["controlCharacterExcludedNodes"],
                control_capital_values=field_counts["capital"],
                control_english_names=field_counts["name:en"],
                control_place_values=field_counts["place"],
                control_population_values=field_counts["population"],
                control_primary_names=field_counts["name"],
            )
        except KeyError as error:
            raise GlobalPlacePackageError(
                "semantic admission audit lacks a required count"
            ) from error

    def document(self) -> dict[str, object]:
        return {
            "controlCharacterExcludedNodes": self.excluded_node_count,
            "controlCharacterFields": {
                "capital": self.control_capital_values,
                "name": self.control_primary_names,
                "name:en": self.control_english_names,
                "place": self.control_place_values,
                "population": self.control_population_values,
            },
            "decodedValueAllowlist": list(_ADMITTED_PLACE_TAG_ORDER),
            "nodeCount": self.node_count,
        }


@dataclass(frozen=True, slots=True)
class PlaceRendererSemanticOutcome:
    stream_sha256: str
    node_count: int
    audit_items: tuple[tuple[str, int], ...]
    feature_rows: int
    record_rows: int
    variant_rows: int

    def __post_init__(self) -> None:
        _require_sha256(self.stream_sha256, "renderer semantic outcome")
        if type(self.node_count) is not int or self.node_count < 0:
            raise GlobalPlacePackageError(
                "renderer semantic outcome node count is invalid"
            )
        if (
            type(self.audit_items) is not tuple
            or not self.audit_items
            or tuple(sorted(self.audit_items)) != self.audit_items
            or any(
                type(key) is not str
                or not key
                or type(value) is not int
                or value < 0
                for key, value in self.audit_items
            )
            or len({key for key, _ in self.audit_items}) != len(self.audit_items)
        ):
            raise GlobalPlacePackageError(
                "renderer semantic outcome audit is malformed"
            )
        for value in (self.feature_rows, self.record_rows, self.variant_rows):
            if type(value) is not int or value < 0:
                raise GlobalPlacePackageError(
                    "renderer semantic outcome row count is invalid"
                )
        audit = dict(self.audit_items)
        if (
            audit.get("inputNodes") != self.node_count
            or audit.get("renderedNodes") != self.feature_rows
            or audit.get("renderedNodes") != self.variant_rows
            or audit.get("writtenPostings") != self.record_rows
        ):
            raise GlobalPlacePackageError(
                "renderer semantic outcome audit and row counts differ"
            )

    @classmethod
    def from_document(
        cls, document: object
    ) -> "PlaceRendererSemanticOutcome":
        if not isinstance(document, dict) or set(document) != {
            "audit",
            "eventBytes",
            "nodeCount",
            "schema",
            "semanticRowCounts",
            "sha256",
        }:
            raise GlobalPlacePackageError(
                "renderer semantic outcome evidence fields differ"
            )
        audit = document.get("audit")
        rows = document.get("semanticRowCounts")
        if (
            document.get("schema") != RENDERER_SEMANTIC_OUTCOME_SCHEMA
            or document.get("eventBytes")
            != RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES
            or not isinstance(audit, dict)
            or not isinstance(rows, dict)
            or set(rows) != {"features", "records", "variants"}
        ):
            raise GlobalPlacePackageError(
                "renderer semantic outcome evidence is malformed"
            )
        return cls(
            stream_sha256=document.get("sha256"),
            node_count=document.get("nodeCount"),
            audit_items=tuple(sorted(audit.items())),
            feature_rows=rows["features"],
            record_rows=rows["records"],
            variant_rows=rows["variants"],
        )

    def document(self) -> dict[str, object]:
        return {
            "audit": dict(self.audit_items),
            "eventBytes": RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES,
            "nodeCount": self.node_count,
            "schema": RENDERER_SEMANTIC_OUTCOME_SCHEMA,
            "semanticRowCounts": {
                "features": self.feature_rows,
                "records": self.record_rows,
                "variants": self.variant_rows,
            },
            "sha256": self.stream_sha256,
        }


@dataclass(frozen=True, slots=True)
class PlaceSourceBinding:
    planet_path: str
    planet_bytes: int
    planet_sha256: str
    candidate_pbf_bytes: int
    candidate_pbf_sha256: str
    opl_bytes: int
    opl_sha256: str
    extraction_receipt_sha256: str
    recovery_receipt_sha256: str | None
    renderer_semantic_outcome_path: str
    renderer_semantic_outcome_bytes: int
    renderer_semantic_outcome: PlaceRendererSemanticOutcome
    semantic_admission_policy_sha256: str
    semantic_admission: PlaceSemanticAdmissionAudit

    def __post_init__(self) -> None:
        if type(self.planet_path) is not str or not self.planet_path:
            raise GlobalPlacePackageError("planet source path binding must be nonempty text")
        for value, label in (
            (self.planet_bytes, "planet byte length"),
            (self.candidate_pbf_bytes, "candidate PBF byte length"),
            (self.opl_bytes, "candidate OPL byte length"),
            (
                self.renderer_semantic_outcome_bytes,
                "renderer semantic outcome byte length",
            ),
        ):
            if type(value) is not int or value < 0:
                raise GlobalPlacePackageError(f"{label} must be a nonnegative integer")
        for value, label in (
            (self.planet_sha256, "planet SHA-256"),
            (self.candidate_pbf_sha256, "candidate PBF SHA-256"),
            (self.opl_sha256, "candidate OPL SHA-256"),
            (self.extraction_receipt_sha256, "extraction receipt SHA-256"),
        ):
            if (
                type(value) is not str
                or len(value) != 64
                or any(character not in "0123456789abcdef" for character in value)
            ):
                raise GlobalPlacePackageError(f"{label} must be lowercase hexadecimal")
        if self.recovery_receipt_sha256 is not None:
            _require_sha256(self.recovery_receipt_sha256, "recovery receipt")
        if (
            self.semantic_admission_policy_sha256
            != SEMANTIC_ADMISSION_POLICY_SHA256
        ):
            raise GlobalPlacePackageError("semantic admission policy identity differs")
        if not isinstance(self.semantic_admission, PlaceSemanticAdmissionAudit):
            raise GlobalPlacePackageError("semantic admission audit binding is invalid")
        if (
            type(self.renderer_semantic_outcome_path) is not str
            or not self.renderer_semantic_outcome_path
            or not isinstance(
                self.renderer_semantic_outcome, PlaceRendererSemanticOutcome
            )
            or self.renderer_semantic_outcome_bytes
            != (
                self.renderer_semantic_outcome.node_count
                * RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES
            )
        ):
            raise GlobalPlacePackageError(
                "renderer semantic outcome artifact binding is invalid"
            )

    def document(self) -> dict[str, object]:
        return {
            "candidateOpl": {"bytes": self.opl_bytes, "sha256": self.opl_sha256},
            "candidatePbf": {
                "bytes": self.candidate_pbf_bytes,
                "sha256": self.candidate_pbf_sha256,
            },
            "extractionReceiptSha256": self.extraction_receipt_sha256,
            "planet": {
                "bytes": self.planet_bytes,
                "path": self.planet_path,
                "sha256": self.planet_sha256,
            },
            "recoveryReceiptSha256": self.recovery_receipt_sha256,
            "rendererSemanticOutcome": {
                "artifact": {
                    "bytes": self.renderer_semantic_outcome_bytes,
                    "sha256": self.renderer_semantic_outcome.stream_sha256,
                },
                "evidence": self.renderer_semantic_outcome.document(),
            },
            "semanticAdmission": {
                "audit": self.semantic_admission.document(),
                "policySha256": self.semantic_admission_policy_sha256,
            },
        }


@dataclass(frozen=True, slots=True)
class GlobalPlaceBuildResult:
    state: str
    output_directory: Path
    receipt: Mapping[str, object]


def _require_sha256(value: object, label: str) -> str:
    if type(value) is not str or _SHA256_RE.fullmatch(value) is None:
        raise GlobalPlacePackageError(f"{label} must be lowercase hexadecimal SHA-256")
    return value


def _stat_signature(status: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        status.st_dev,
        status.st_ino,
        status.st_size,
        status.st_mtime_ns,
        status.st_ctime_ns,
    )


def _pinned_runtime_document() -> dict[str, object]:
    return {
        "binaryPath": OSMIUM_BINARY_PATH,
        "binarySha256": OSMIUM_BINARY_SHA256,
        "boostLibraryPath": BOOST_LIBRARY_PATH,
        "boostLibrarySha256": BOOST_LIBRARY_SHA256,
        "debSha256": OSMIUM_DEB_SHA256,
        "libosmiumVersion": PINNED_LIBOSMIUM_VERSION,
        "libraryPath": OSMIUM_LIBRARY_PATH,
        "locale": "C.UTF-8",
        "osmiumVersion": PINNED_OSMIUM_VERSION,
    }


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
    except (TypeError, ValueError, UnicodeError) as error:
        raise GlobalPlacePackageError("extraction document is not canonical JSON") from error


def _positive_decimal(raw: str, maximum: int, label: str) -> int:
    if _POSITIVE_DECIMAL_RE.fullmatch(raw) is None:
        raise GlobalPlacePackageError(f"{label} must use canonical positive decimal")
    value = int(raw)
    if value > maximum:
        raise GlobalPlacePackageError(f"{label} exceeds its integer ceiling")
    return value


def _nonnegative_decimal(raw: str, maximum: int, label: str) -> int:
    if _NONNEGATIVE_DECIMAL_RE.fullmatch(raw) is None:
        raise GlobalPlacePackageError(f"{label} must use canonical nonnegative decimal")
    value = int(raw)
    if value > maximum:
        raise GlobalPlacePackageError(f"{label} exceeds its integer ceiling")
    return value


def _scan_opl_string(
    raw: str,
    label: str,
    *,
    materialize: bool,
    reject_controls: bool,
) -> tuple[str | None, bool]:
    output: list[str] | None = [] if materialize else None
    utf8_bytes = 0
    has_control = False
    index = 0
    while index < len(raw):
        escape_start = raw.find("%", index)
        direct_end = len(raw) if escape_start < 0 else escape_start
        direct = raw[index:direct_end]
        if direct:
            if any(character in _DIRECT_OPL_SPECIAL for character in direct):
                raise GlobalPlacePackageError(
                    f"{label} contains a direct ambiguous OPL character"
                )
            try:
                utf8_bytes += len(direct.encode("utf-8", "strict"))
            except UnicodeEncodeError as error:
                raise GlobalPlacePackageError(f"{label} is not strict UTF-8") from error
            if materialize or reject_controls:
                if any(unicodedata.category(character) == "Cc" for character in direct):
                    has_control = True
                    if reject_controls:
                        raise GlobalPlacePackageError(
                            f"{label} contains a control character"
                        )
            if output is not None:
                output.append(direct)
        if utf8_bytes > _MAX_TEXT_UTF8_BYTES:
            raise GlobalPlacePackageError(f"{label} exceeds the UTF-8 byte ceiling")
        if escape_start < 0:
            break
        escape_end = raw.find("%", escape_start + 1)
        if escape_end < 0:
            raise GlobalPlacePackageError(f"{label} contains a truncated OPL escape")
        encoded = raw[escape_start + 1 : escape_end]
        if not 1 <= len(encoded) <= 6 or any(value not in _HEX for value in encoded):
            raise GlobalPlacePackageError(f"{label} contains a malformed OPL escape")
        scalar = int(encoded, 16)
        if scalar > 0x10FFFF or 0xD800 <= scalar <= 0xDFFF:
            raise GlobalPlacePackageError(
                f"{label} OPL escape is not a permitted Unicode scalar"
            )
        character = chr(scalar)
        utf8_bytes += len(character.encode("utf-8", "strict"))
        if utf8_bytes > _MAX_TEXT_UTF8_BYTES:
            raise GlobalPlacePackageError(f"{label} exceeds the UTF-8 byte ceiling")
        if materialize or reject_controls:
            if unicodedata.category(character) == "Cc":
                has_control = True
                if reject_controls:
                    raise GlobalPlacePackageError(
                        f"{label} contains a control character"
                    )
        if output is not None:
            output.append(character)
        index = escape_end + 1
    return ("".join(output) if output is not None else None), has_control


def _decode_opl_string(raw: str, label: str) -> str:
    decoded, _ = _scan_opl_string(
        raw,
        label,
        materialize=True,
        reject_controls=True,
    )
    assert decoded is not None
    return decoded


def _parse_coordinate(raw: str, *, latitude: bool, label: str) -> int:
    if _COORDINATE_RE.fullmatch(raw) is None:
        raise GlobalPlacePackageError(f"{label} is not a canonical OPL coordinate")
    negative = raw.startswith("-")
    unsigned = raw[1:] if negative else raw
    whole, separator, fraction = unsigned.partition(".")
    value = int(whole) * 10_000_000
    if separator:
        value += int(fraction.ljust(7, "0"))
    if negative:
        value = -value
    if negative and value == 0:
        raise GlobalPlacePackageError(f"{label} uses forbidden negative zero")
    maximum = 900_000_000 if latitude else 1_800_000_000
    if not -maximum <= value <= maximum:
        raise GlobalPlacePackageError(f"{label} lies outside the OSM coordinate domain")
    return value


def _parse_timestamp(raw: str, label: str) -> str:
    if _TIMESTAMP_RE.fullmatch(raw) is None:
        raise GlobalPlacePackageError(f"{label} is not canonical UTC")
    try:
        datetime.strptime(raw, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as error:
        raise GlobalPlacePackageError(f"{label} is not a real UTC instant") from error
    return raw


def _parse_tags(
    raw: str, line_number: int
) -> tuple[tuple[tuple[str, str], ...], tuple[str, ...]]:
    if not raw:
        return (), ()
    tags: list[tuple[str, str]] = []
    admitted_controls: list[str] = []
    keys: set[str] = set()
    for ordinal, encoded in enumerate(raw.split(","), start=1):
        if encoded.count("=") != 1:
            raise GlobalPlacePackageError(
                f"OPL line {line_number} tag {ordinal} is malformed"
            )
        raw_key, raw_value = encoded.split("=", 1)
        key = _decode_opl_string(raw_key, f"OPL line {line_number} tag key")
        if not key:
            raise GlobalPlacePackageError(f"OPL line {line_number} has an empty tag key")
        if key in keys:
            raise GlobalPlacePackageError(
                f"OPL line {line_number} contains duplicate decoded tag {key!r}"
            )
        keys.add(key)
        if key in _ADMITTED_PLACE_TAG_KEYS:
            value, has_control = _scan_opl_string(
                raw_value,
                f"OPL line {line_number} admitted tag {key!r} value",
                materialize=True,
                reject_controls=False,
            )
            assert value is not None
            tags.append((key, value))
            if has_control:
                admitted_controls.append(key)
        else:
            _scan_opl_string(
                raw_value,
                f"OPL line {line_number} irrelevant tag {key!r} value",
                materialize=False,
                reject_controls=False,
            )
    return tuple(tags), tuple(admitted_controls)


def _parse_opl_node(
    raw: bytes,
    *,
    line_number: int,
    byte_start: int,
    byte_end: int,
) -> StrictOplNode:
    if not raw:
        raise GlobalPlacePackageError(f"OPL line {line_number} is empty")
    if b"\r" in raw or b"\0" in raw or any(value < 0x20 or value == 0x7F for value in raw):
        raise GlobalPlacePackageError(f"OPL line {line_number} contains a forbidden byte")
    if raw.startswith(b" ") or raw.endswith(b" ") or b"  " in raw:
        raise GlobalPlacePackageError(
            f"OPL line {line_number} must use canonical single-space separators"
        )
    try:
        fields = raw.decode("utf-8", "strict").split(" ")
    except UnicodeDecodeError as error:
        raise GlobalPlacePackageError(f"OPL line {line_number} is not strict UTF-8") from error
    head = fields[0]
    if len(head) < 2 or head[0] != "n":
        raise GlobalPlacePackageError(
            f"OPL line {line_number} is not a direct NODE object"
        )
    object_id = _positive_decimal(
        head[1:], _MAX_SIGNED_63, f"OPL line {line_number} node ID"
    )
    order = {"v": 0, "d": 1, "c": 2, "t": 3, "i": 4, "u": 5, "T": 6, "x": 7, "y": 8}
    attributes: dict[str, str] = {}
    previous_rank = -1
    for field in fields[1:]:
        if not field:
            raise GlobalPlacePackageError(f"OPL line {line_number} contains an empty field")
        key = field[0]
        rank = order.get(key)
        if rank is None:
            raise GlobalPlacePackageError(
                f"OPL line {line_number} contains unknown attribute {key!r}"
            )
        if key in attributes:
            raise GlobalPlacePackageError(
                f"OPL line {line_number} contains duplicate attribute {key!r}"
            )
        if rank <= previous_rank:
            raise GlobalPlacePackageError(
                f"OPL line {line_number} attributes are not in canonical osmium order"
            )
        previous_rank = rank
        attributes[key] = field[1:]
    missing = {"v", "d", "t", "T", "x", "y"}.difference(attributes)
    if missing:
        raise GlobalPlacePackageError(
            f"OPL line {line_number} lacks required attributes {sorted(missing)!r}"
        )
    if attributes["d"] != "V":
        raise GlobalPlacePackageError(
            f"OPL line {line_number} is not a current visible node"
        )
    version = _positive_decimal(
        attributes["v"], _MAX_U32, f"OPL line {line_number} version"
    )
    if "c" in attributes:
        _nonnegative_decimal(
            attributes["c"], _MAX_SIGNED_63, f"OPL line {line_number} changeset"
        )
    if "i" in attributes:
        _nonnegative_decimal(
            attributes["i"], _MAX_SIGNED_63, f"OPL line {line_number} user ID"
        )
    if "u" in attributes:
        _decode_opl_string(attributes["u"], f"OPL line {line_number} user")
    timestamp = _parse_timestamp(
        attributes["t"], f"OPL line {line_number} timestamp"
    )
    tags, admitted_control_fields = _parse_tags(attributes["T"], line_number)
    node = OsmNode(
        object_id=object_id,
        version=version,
        timestamp=timestamp,
        longitude_e7=_parse_coordinate(
            attributes["x"], latitude=False, label=f"OPL line {line_number} longitude"
        ),
        latitude_e7=_parse_coordinate(
            attributes["y"], latitude=True, label=f"OPL line {line_number} latitude"
        ),
        tags=tags,
    )
    return StrictOplNode(
        node,
        line_number,
        byte_start,
        byte_end,
        admitted_control_fields,
    )


def iter_strict_place_opl(
    stream: BinaryIO,
    *,
    read_chunk_bytes: int = 1024 * 1024,
    max_line_bytes: int = _MAX_LINE_BYTES,
    initial_byte_offset: int = 0,
    initial_line_number: int = 0,
    previous_node_id: int = 0,
) -> Iterator[StrictOplNode]:
    """Stream canonical current node-only osmium OPL in strict Type_then_ID order."""

    if not callable(getattr(stream, "read", None)):
        raise GlobalPlacePackageError("OPL input must be a readable binary stream")
    if type(read_chunk_bytes) is not int or read_chunk_bytes <= 0:
        raise GlobalPlacePackageError("OPL read chunk must be a positive integer")
    if type(max_line_bytes) is not int or max_line_bytes <= 0:
        raise GlobalPlacePackageError("OPL line ceiling must be a positive integer")
    for value, label in (
        (initial_byte_offset, "initial OPL byte offset"),
        (initial_line_number, "initial OPL line number"),
        (previous_node_id, "previous OPL node ID"),
    ):
        if type(value) is not int or value < 0:
            raise GlobalPlacePackageError(f"{label} must be a nonnegative integer")
    tell = getattr(stream, "tell", None)
    if callable(tell) and tell() != initial_byte_offset:
        raise GlobalPlacePackageError(
            "OPL stream position differs from its declared initial byte offset"
        )
    pending = bytearray()
    line_number = initial_line_number
    stream_offset = initial_byte_offset
    line_start = initial_byte_offset
    previous_id = previous_node_id
    while True:
        chunk = stream.read(read_chunk_bytes)
        if type(chunk) is not bytes:
            raise GlobalPlacePackageError("OPL stream read must return immutable bytes")
        if len(chunk) > read_chunk_bytes:
            raise GlobalPlacePackageError("OPL stream returned more bytes than requested")
        if not chunk:
            break
        cursor = 0
        while cursor < len(chunk):
            newline = chunk.find(b"\n", cursor)
            end = len(chunk) if newline < 0 else newline
            addition = end - cursor
            if len(pending) + addition > max_line_bytes:
                raise GlobalPlacePackageError("OPL record exceeds the line byte ceiling")
            pending.extend(chunk[cursor:end])
            stream_offset += addition
            if newline < 0:
                break
            raw_line = bytes(pending)
            pending.clear()
            stream_offset += 1
            line_number += 1
            if len(raw_line) > max_line_bytes:
                raise GlobalPlacePackageError("OPL record exceeds the line byte ceiling")
            parsed = _parse_opl_node(
                raw_line,
                line_number=line_number,
                byte_start=line_start,
                byte_end=stream_offset,
            )
            object_id = parsed.node.object_id
            if object_id == previous_id:
                raise GlobalPlacePackageError(
                    "OPL input contains a duplicate/history NODE object"
                )
            if object_id < previous_id:
                raise GlobalPlacePackageError(
                    "OPL input is not monotonic Type_then_ID"
                )
            previous_id = object_id
            line_start = stream_offset
            yield parsed
            cursor = newline + 1
    if pending:
        raise GlobalPlacePackageError("OPL input must end with a final LF")


def verify_file_identity(
    path: Path,
    *,
    expected_bytes: int,
    expected_sha256: str,
    read_chunk_bytes: int = 4 * 1024 * 1024,
) -> FileIdentity:
    """Hash one stable regular file without ever materializing the complete file."""

    if type(read_chunk_bytes) is not int or read_chunk_bytes <= 0:
        raise GlobalPlacePackageError("source read chunk must be a positive integer")
    if not isinstance(path, Path) or not path.is_file() or path.is_symlink():
        raise GlobalPlacePackageError("source path is not one regular non-link file")
    if type(expected_bytes) is not int or expected_bytes < 0:
        raise GlobalPlacePackageError("expected source byte length is invalid")
    expected_digest = _require_sha256(expected_sha256, "expected source")
    before_path = path.stat()
    if before_path.st_size != expected_bytes:
        raise GlobalPlacePackageError(
            "source byte length differs before hashing: "
            f"expected {expected_bytes}, got {before_path.st_size}"
        )
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        before_handle = handle.seek(0, 2)
        if before_handle != expected_bytes:
            raise GlobalPlacePackageError("source handle byte length differs from its path")
        handle.seek(0)
        while True:
            chunk = handle.read(read_chunk_bytes)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after_handle = handle.seek(0, 2)
    after_path = path.stat()
    stable_before = _stat_signature(before_path)
    stable_after = _stat_signature(after_path)
    if stable_before != stable_after or total != expected_bytes or after_handle != expected_bytes:
        raise GlobalPlacePackageError("source identity changed while it was hashed")
    actual = digest.hexdigest()
    if actual != expected_digest:
        raise GlobalPlacePackageError(
            f"source SHA-256 differs: expected {expected_digest}, got {actual}"
        )
    return FileIdentity(path, total, actual, stable_after)


def _stream_file_identity(
    path: Path,
    *,
    read_chunk_bytes: int = 4 * 1024 * 1024,
) -> FileIdentity:
    if not isinstance(path, Path) or not path.is_file() or path.is_symlink():
        raise GlobalPlacePackageError(f"artifact is not one regular non-link file: {path}")
    before = path.stat()
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(read_chunk_bytes)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after_handle = handle.seek(0, 2)
    after = path.stat()
    stable_before = _stat_signature(before)
    stable_after = _stat_signature(after)
    if stable_before != stable_after or total != before.st_size or after_handle != total:
        raise GlobalPlacePackageError(f"artifact changed while it was hashed: {path}")
    return FileIdentity(path, total, digest.hexdigest(), stable_after)


def _read_bounded_file(path: Path, maximum_bytes: int, label: str) -> bytes:
    if type(maximum_bytes) is not int or maximum_bytes < 0:
        raise GlobalPlacePackageError(f"{label} read bound is invalid")
    try:
        with path.open("rb") as handle:
            raw = handle.read(maximum_bytes + 1)
    except OSError as error:
        raise GlobalPlacePackageError(f"{label} is not readable") from error
    if len(raw) > maximum_bytes:
        raise GlobalPlacePackageError(f"{label} exceeds its byte bound")
    return raw


def _empty_semantic_admission_audit() -> dict[str, object]:
    return {
        "controlCharacterExcludedNodes": 0,
        "controlCharacterFields": {
            field: 0 for field in _ADMITTED_PLACE_TAG_ORDER
        },
        "decodedValueAllowlist": list(_ADMITTED_PLACE_TAG_ORDER),
    }


def _strict_opl_audits_stream(
    stream: BinaryIO,
) -> tuple[dict[str, object], dict[str, object]]:
    node_count = 0
    first_node_id = None
    last_node_id = None
    semantic_audit = _empty_semantic_admission_audit()
    field_counts = semantic_audit["controlCharacterFields"]
    assert isinstance(field_counts, dict)
    for parsed in iter_strict_place_opl(stream):
        node_count += 1
        if first_node_id is None:
            first_node_id = parsed.node.object_id
        last_node_id = parsed.node.object_id
        if parsed.admitted_control_fields:
            semantic_audit["controlCharacterExcludedNodes"] = int(
                semantic_audit["controlCharacterExcludedNodes"]
            ) + 1
            for field in parsed.admitted_control_fields:
                field_counts[field] = int(field_counts[field]) + 1
    if node_count == 0:
        raise GlobalPlacePackageError("place extraction produced no strict current nodes")
    strict_audit = {
        "firstNodeId": first_node_id,
        "lastNodeId": last_node_id,
        "nodeCount": node_count,
        "ordering": "Type_then_ID strictly increasing",
        "visibility": "current visible only",
    }
    return strict_audit, semantic_audit


def _strict_opl_audits(path: Path) -> tuple[dict[str, object], dict[str, object]]:
    with path.open("rb") as handle:
        return _strict_opl_audits_stream(handle)


def build_osmium_extraction_commands(
    *,
    planet_linux_path: str,
    stage_linux_directory: str,
) -> tuple[OsmiumCommand, ...]:
    if type(planet_linux_path) is not str or not planet_linux_path.startswith("/"):
        raise GlobalPlacePackageError("planet WSL path must be absolute")
    if type(stage_linux_directory) is not str or not stage_linux_directory.startswith("/"):
        raise GlobalPlacePackageError("stage WSL directory must be absolute")
    stage = stage_linux_directory.rstrip("/")
    pbf = f"{stage}/place-nodes.pbf.partial"
    opl = f"{stage}/place-nodes.opl.partial"
    generator = "flight-alert-exp8-osmium-1.11.1-global-place-v1"
    return (
        OsmiumCommand(
            "fileinfo",
            ("fileinfo", "--no-progress", "-F", "pbf", "-e", "-c", planet_linux_path),
        ),
        OsmiumCommand(
            "tags-filter",
            (
                "tags-filter",
                "--no-progress",
                "--fsync",
                "--generator",
                generator,
                "-F",
                "pbf",
                "-R",
                "-f",
                "pbf",
                "-O",
                planet_linux_path,
                "n/place",
                "-o",
                pbf,
            ),
        ),
        OsmiumCommand(
            "fileinfo",
            ("fileinfo", "--no-progress", "-F", "pbf", "-e", "-c", pbf),
        ),
        OsmiumCommand(
            "cat",
            (
                "cat",
                "--no-progress",
                "--fsync",
                "--generator",
                generator,
                "-F",
                "pbf",
                "-t",
                "node",
                "-f",
                "opl",
                "-O",
                pbf,
                "-o",
                opl,
            ),
        ),
        OsmiumCommand(
            "fileinfo",
            ("fileinfo", "--no-progress", "-F", "opl", "-e", "-c", opl),
        ),
    )


def _windows_to_wsl_path(path: Path) -> str:
    try:
        completed = subprocess.run(
            ("wsl.exe", "--exec", "wslpath", "-a", str(path.resolve())),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise GlobalPlacePackageError("WSL path conversion could not start") from error
    if completed.returncode != 0 or len(completed.stdout) > 16 * 1024:
        raise GlobalPlacePackageError("WSL path conversion failed")
    try:
        converted = completed.stdout.decode("utf-8", "strict").strip()
    except UnicodeDecodeError as error:
        raise GlobalPlacePackageError("WSL path conversion is not strict UTF-8") from error
    if not converted.startswith("/") or "\0" in converted or "\n" in converted:
        raise GlobalPlacePackageError("WSL path conversion is not one absolute path")
    return converted


def _wsl_osmium_argv(arguments: Sequence[str]) -> tuple[str, ...]:
    return (
        "wsl.exe",
        "--exec",
        "env",
        "LC_ALL=C.UTF-8",
        "LANG=C.UTF-8",
        f"LD_LIBRARY_PATH={OSMIUM_LIBRARY_PATH}",
        OSMIUM_BINARY_PATH,
        *arguments,
    )


def _attest_pinned_osmium() -> dict[str, object]:
    def capture(argv: Sequence[str]) -> bytes:
        try:
            completed = subprocess.run(
                tuple(argv),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=60,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise GlobalPlacePackageError("pinned osmium attestation could not start") from error
        if completed.returncode != 0 or len(completed.stdout) > 64 * 1024:
            raise GlobalPlacePackageError("pinned osmium attestation failed")
        return completed.stdout

    version_raw = capture(_wsl_osmium_argv(("--version",)))
    try:
        version_text = version_raw.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise GlobalPlacePackageError("pinned osmium version output is not UTF-8") from error
    required = (
        f"osmium version {PINNED_OSMIUM_VERSION}\n",
        f"libosmium version {PINNED_LIBOSMIUM_VERSION}\n",
    )
    if not all(value in version_text for value in required):
        raise GlobalPlacePackageError("pinned osmium/libosmium version differs")
    sha_raw = capture(
        (
            "wsl.exe",
            "--exec",
            "/usr/bin/sha256sum",
            OSMIUM_BINARY_PATH,
            BOOST_LIBRARY_PATH,
        )
    )
    try:
        rows = [line.split() for line in sha_raw.decode("ascii", "strict").splitlines()]
    except UnicodeDecodeError as error:
        raise GlobalPlacePackageError("pinned osmium SHA output is not ASCII") from error
    digest_by_path = {
        fields[1].lstrip("*"): fields[0]
        for fields in rows
        if len(fields) == 2
    }
    if (
        digest_by_path.get(OSMIUM_BINARY_PATH) != OSMIUM_BINARY_SHA256
        or digest_by_path.get(BOOST_LIBRARY_PATH) != BOOST_LIBRARY_SHA256
    ):
        raise GlobalPlacePackageError("pinned osmium binary or Boost SHA-256 differs")
    return _pinned_runtime_document()


def _run_osmium_command(
    command: OsmiumCommand,
    stdout_path: Path,
    stderr_path: Path,
) -> int:
    try:
        with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            completed = subprocess.run(
                _wsl_osmium_argv(command.arguments),
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                check=False,
            )
    except OSError as error:
        raise GlobalPlacePackageError(f"osmium {command.role} could not start") from error
    return completed.returncode


def _identity_document(identity: FileIdentity, *, name: str | None = None) -> dict[str, object]:
    document: dict[str, object] = {
        "bytes": identity.bytes,
        "sha256": identity.sha256,
    }
    if name is not None:
        document["name"] = name
    return document


def _read_checkpoint(path: Path, expected_run_sha256: str) -> dict[str, object]:
    identity = _stream_file_identity(path)
    if identity.bytes > 16 * 1024 * 1024:
        raise GlobalPlacePackageError("extraction checkpoint exceeds 16 MiB")
    raw = _read_bounded_file(path, 16 * 1024 * 1024, "extraction checkpoint")
    try:
        document = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise GlobalPlacePackageError("extraction checkpoint is malformed") from error
    if not isinstance(document, dict) or _canonical_json_bytes(document) != raw:
        raise GlobalPlacePackageError("extraction checkpoint is not canonical JSON")
    if document.get("runIdentitySha256") != expected_run_sha256:
        raise GlobalPlacePackageError("extraction checkpoint identity differs")
    run_identity = document.get("runIdentity")
    if not isinstance(run_identity, dict) or hashlib.sha256(
        _canonical_json_bytes(run_identity)
    ).hexdigest() != expected_run_sha256:
        raise GlobalPlacePackageError("extraction checkpoint run identity is inconsistent")
    return document


def _write_checkpoint(path: Path, document: Mapping[str, object]) -> None:
    temporary = path.with_suffix(".json.tmp")
    raw = _canonical_json_bytes(document)
    with temporary.open("wb") as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _verify_checkpoint_files(stage: Path, files: Mapping[str, object]) -> None:
    for name, raw_identity in files.items():
        if not isinstance(raw_identity, dict):
            raise GlobalPlacePackageError("extraction checkpoint file identity is malformed")
        candidate = stage / name
        if not candidate.exists() and name.endswith(".partial"):
            candidate = stage / name[: -len(".partial")]
        verify_file_identity(
            candidate,
            expected_bytes=int(raw_identity["bytes"]),
            expected_sha256=str(raw_identity["sha256"]),
        )


def _command_sha256(command: OsmiumCommand) -> str:
    return hashlib.sha256(
        _canonical_json_bytes(
            {"arguments": list(command.arguments), "role": command.role}
        )
    ).hexdigest()


def _validate_execution_ledger(
    executions: Sequence[object],
    commands: Sequence[OsmiumCommand],
) -> None:
    if len(executions) > len(commands):
        raise GlobalPlacePackageError("extraction checkpoint has extra executions")
    for index, (raw_execution, command) in enumerate(
        zip(executions, commands, strict=False), start=1
    ):
        if not isinstance(raw_execution, dict):
            raise GlobalPlacePackageError(
                f"extraction checkpoint execution {index} is malformed"
            )
        if (
            raw_execution.get("role") != command.role
            or raw_execution.get("arguments") != list(command.arguments)
            or raw_execution.get("commandSha256") != _command_sha256(command)
            or raw_execution.get("exitCode") != 0
            or not isinstance(raw_execution.get("outputs"), list)
        ):
            raise GlobalPlacePackageError(
                f"extraction checkpoint execution {index} differs from its plan"
            )


def extract_global_place_opl(
    *,
    planet_path: Path = EXPECTED_PLANET_PATH,
    output_directory: Path,
    expected_planet_bytes: int = EXPECTED_PLANET_BYTES,
    expected_planet_sha256: str = EXPECTED_PLANET_SHA256,
) -> GlobalPlaceExtractionResult:
    """Run the pinned node/place PBF+OPL extraction after exact planet verification."""

    if not isinstance(output_directory, Path):
        raise GlobalPlacePackageError("extraction output must be a pathlib.Path")
    if output_directory.exists():
        raise GlobalPlacePackageError("extraction output directory already exists")
    source = verify_file_identity(
        planet_path,
        expected_bytes=expected_planet_bytes,
        expected_sha256=expected_planet_sha256,
    )
    source_signature = source.stat_signature
    if _stat_signature(planet_path.stat()) != source_signature:
        raise GlobalPlacePackageError("planet source changed after exact hashing")
    runtime = _attest_pinned_osmium()
    code = _stream_file_identity(Path(__file__).resolve())
    provisional_identity = {
        "code": _identity_document(code, name=Path(__file__).name),
        "outputName": output_directory.name,
        "runtime": runtime,
        "schema": "flightalert.experiment8.osm-global-place-extraction-identity.v1",
        "source": {
            "bytes": source.bytes,
            "path": str(planet_path),
            "sha256": source.sha256,
        },
    }
    provisional_sha = hashlib.sha256(_canonical_json_bytes(provisional_identity)).hexdigest()
    stage = output_directory.with_name(
        output_directory.name + ".partial-" + provisional_sha[:16]
    )
    created_stage = False
    if not stage.exists():
        stage.mkdir(parents=True, exist_ok=False)
        created_stage = True
    elif not stage.is_dir():
        raise GlobalPlacePackageError("extraction partial path is not a directory")
    planet_linux = _windows_to_wsl_path(planet_path)
    stage_linux = _windows_to_wsl_path(stage)
    commands = build_osmium_extraction_commands(
        planet_linux_path=planet_linux,
        stage_linux_directory=stage_linux,
    )
    run_identity = {**provisional_identity, "commands": [list(item.arguments) for item in commands]}
    run_sha256 = hashlib.sha256(_canonical_json_bytes(run_identity)).hexdigest()
    checkpoint_path = stage / "extraction-checkpoint.json"
    if checkpoint_path.exists():
        checkpoint = _read_checkpoint(checkpoint_path, run_sha256)
        files = checkpoint.get("files")
        if not isinstance(files, dict):
            raise GlobalPlacePackageError("extraction checkpoint lacks file identities")
        _verify_checkpoint_files(stage, files)
    elif not created_stage:
        raise GlobalPlacePackageError(
            "existing extraction partial directory lacks its exact checkpoint"
        )
    else:
        checkpoint = {
            "executions": [],
            "files": {},
            "runIdentity": run_identity,
            "runIdentitySha256": run_sha256,
            "schema": "flightalert.experiment8.osm-global-place-extraction-checkpoint.v1",
        }
        _write_checkpoint(checkpoint_path, checkpoint)
    executions = checkpoint["executions"]
    files = checkpoint["files"]
    if not isinstance(executions, list) or not isinstance(files, dict):
        raise GlobalPlacePackageError("extraction checkpoint execution ledger is malformed")
    _validate_execution_ledger(executions, commands)
    for index, command in enumerate(commands, start=1):
        if index <= len(executions):
            continue
        prefix = f"command-{index:02d}-{command.role}"
        stdout_path = stage / f"{prefix}.stdout"
        stderr_path = stage / f"{prefix}.stderr"
        returncode = _run_osmium_command(command, stdout_path, stderr_path)
        if type(returncode) is not int or returncode != 0:
            raise GlobalPlacePackageError(
                f"pinned osmium {command.role} failed with exit {returncode!r}"
            )
        generated = [stdout_path, stderr_path]
        if command.role == "tags-filter":
            generated.append(stage / "place-nodes.pbf.partial")
        if command.role == "cat":
            generated.append(stage / "place-nodes.opl.partial")
        generated_documents = []
        for generated_path in generated:
            identity = _stream_file_identity(generated_path)
            relative = generated_path.name
            document = _identity_document(identity, name=relative)
            files[relative] = {"bytes": identity.bytes, "sha256": identity.sha256}
            generated_documents.append(document)
        command_document = {
            "arguments": list(command.arguments),
            "commandSha256": _command_sha256(command),
            "exitCode": returncode,
            "outputs": generated_documents,
            "role": command.role,
        }
        executions.append(command_document)
        _write_checkpoint(checkpoint_path, checkpoint)
    pbf_partial = stage / "place-nodes.pbf.partial"
    opl_partial = stage / "place-nodes.opl.partial"
    pbf_final = stage / "place-nodes.pbf"
    opl_final = stage / "place-nodes.opl"
    outcome_final = stage / "place-semantic-outcomes.bin"
    pbf_source = pbf_partial if pbf_partial.exists() else pbf_final
    opl_source = opl_partial if opl_partial.exists() else opl_final
    pbf_identity = _stream_file_identity(pbf_source)
    opl_identity = _stream_file_identity(opl_source)
    for name, identity in (
        ("place-nodes.pbf.partial", pbf_identity),
        ("place-nodes.opl.partial", opl_identity),
    ):
        expected = files.get(name)
        if (
            not isinstance(expected, dict)
            or expected.get("bytes") != identity.bytes
            or expected.get("sha256") != identity.sha256
        ):
            raise GlobalPlacePackageError(
                f"extraction artifact {name} differs from its command checkpoint"
            )
    from .osm_global_place_store import _semantic_outcome_audits_stream

    with opl_source.open("rb") as opl_handle, outcome_final.open("xb") as outcome_handle:
        (
            strict_opl_audit,
            semantic_admission_audit,
            renderer_semantic_outcome,
        ) = _semantic_outcome_audits_stream(
            opl_handle,
            source_generation_sha256=source.sha256,
            outcome_stream=outcome_handle,
        )
        outcome_handle.flush()
        os.fsync(outcome_handle.fileno())
    outcome_identity = _stream_file_identity(outcome_final)
    if pbf_partial.exists():
        if pbf_final.exists():
            raise GlobalPlacePackageError("extraction PBF has duplicate final names")
        os.replace(pbf_partial, pbf_final)
    if opl_partial.exists():
        if opl_final.exists():
            raise GlobalPlacePackageError("extraction OPL has duplicate final names")
        os.replace(opl_partial, opl_final)
    receipt: dict[str, object] = {
        "artifacts": {
            "candidateOpl": _identity_document(opl_identity, name=opl_final.name),
            "candidatePbf": _identity_document(pbf_identity, name=pbf_final.name),
            "rendererSemanticOutcomes": _identity_document(
                outcome_identity, name=outcome_final.name
            ),
        },
        "code": _identity_document(code, name=Path(__file__).name),
        "commands": executions,
        "runIdentitySha256": run_sha256,
        "runtime": runtime,
        "rendererSemanticOutcome": renderer_semantic_outcome,
        "schema": "flightalert.experiment8.osm-global-place-extraction-receipt.v1",
        "semanticAdmissionAudit": semantic_admission_audit,
        "semanticAdmissionPolicySha256": SEMANTIC_ADMISSION_POLICY_SHA256,
        "selection": {
            "filterExpression": "n/place",
            "objectKind": "node",
            "referencedObjectsOmitted": True,
        },
        "source": {
            "bytes": source.bytes,
            "path": str(planet_path),
            "sha256": source.sha256,
        },
        "strictOplAudit": strict_opl_audit,
    }
    receipt_bytes = _canonical_json_bytes(receipt)
    (stage / "extraction-receipt.json").write_bytes(receipt_bytes)
    final_source_status = planet_path.stat()
    if source_signature != _stat_signature(final_source_status):
        raise GlobalPlacePackageError("planet source identity drifted during extraction")
    if _stream_file_identity(Path(__file__).resolve()).sha256 != code.sha256:
        raise GlobalPlacePackageError("extraction code identity drifted during execution")
    os.replace(stage, output_directory)
    verify_file_identity(
        output_directory / pbf_final.name,
        expected_bytes=pbf_identity.bytes,
        expected_sha256=pbf_identity.sha256,
    )
    verify_file_identity(
        output_directory / opl_final.name,
        expected_bytes=opl_identity.bytes,
        expected_sha256=opl_identity.sha256,
    )
    verify_file_identity(
        output_directory / outcome_final.name,
        expected_bytes=outcome_identity.bytes,
        expected_sha256=outcome_identity.sha256,
    )
    if _read_bounded_file(
        output_directory / "extraction-receipt.json",
        len(receipt_bytes),
        "published extraction receipt",
    ) != receipt_bytes:
        raise GlobalPlacePackageError("published extraction receipt differs")
    return GlobalPlaceExtractionResult(
        "complete", output_directory, MappingProxyType(receipt)
    )


def _validate_receipt_command_contract(
    receipt: Mapping[str, object],
    *,
    extraction_directory: Path,
    source: Mapping[str, object],
    pbf: Mapping[str, object],
    opl: Mapping[str, object],
    code: Mapping[str, object],
    runtime: Mapping[str, object],
) -> None:
    executions = receipt.get("commands")
    if not isinstance(executions, list) or len(executions) != 5:
        raise GlobalPlacePackageError("extraction receipt command ledger is incomplete")
    first_arguments = executions[0].get("arguments") if isinstance(executions[0], dict) else None
    filter_arguments = executions[1].get("arguments") if isinstance(executions[1], dict) else None
    if (
        not isinstance(first_arguments, list)
        or not first_arguments
        or type(first_arguments[-1]) is not str
        or not isinstance(filter_arguments, list)
    ):
        raise GlobalPlacePackageError("extraction receipt command arguments are malformed")
    try:
        output_index = filter_arguments.index("-o") + 1
        pbf_linux_path = filter_arguments[output_index]
    except (ValueError, IndexError) as error:
        raise GlobalPlacePackageError(
            "extraction receipt command output path is missing"
        ) from error
    if type(pbf_linux_path) is not str or not pbf_linux_path.endswith(
        "/place-nodes.pbf.partial"
    ):
        raise GlobalPlacePackageError("extraction receipt command PBF path differs")
    stage_linux_directory = pbf_linux_path[: -len("/place-nodes.pbf.partial")]
    expected_commands = build_osmium_extraction_commands(
        planet_linux_path=first_arguments[-1],
        stage_linux_directory=stage_linux_directory,
    )
    try:
        _validate_execution_ledger(executions, expected_commands)
    except GlobalPlacePackageError as error:
        raise GlobalPlacePackageError(
            "extraction receipt command ledger differs from the pinned plan"
        ) from error

    provisional_identity = {
        "code": dict(code),
        "outputName": extraction_directory.name,
        "runtime": dict(runtime),
        "schema": "flightalert.experiment8.osm-global-place-extraction-identity.v1",
        "source": dict(source),
    }
    provisional_sha256 = hashlib.sha256(
        _canonical_json_bytes(provisional_identity)
    ).hexdigest()
    expected_stage_name = (
        extraction_directory.name + ".partial-" + provisional_sha256[:16]
    )
    if stage_linux_directory.rstrip("/").rsplit("/", 1)[-1] != expected_stage_name:
        raise GlobalPlacePackageError(
            "extraction receipt command stage differs from its run identity"
        )
    run_identity = {
        **provisional_identity,
        "commands": [list(item.arguments) for item in expected_commands],
    }
    expected_run_sha256 = hashlib.sha256(
        _canonical_json_bytes(run_identity)
    ).hexdigest()
    if receipt.get("runIdentitySha256") != expected_run_sha256:
        raise GlobalPlacePackageError("extraction receipt run identity differs")

    expected_output_names = (
        ("command-01-fileinfo.stdout", "command-01-fileinfo.stderr"),
        (
            "command-02-tags-filter.stdout",
            "command-02-tags-filter.stderr",
            "place-nodes.pbf.partial",
        ),
        ("command-03-fileinfo.stdout", "command-03-fileinfo.stderr"),
        (
            "command-04-cat.stdout",
            "command-04-cat.stderr",
            "place-nodes.opl.partial",
        ),
        ("command-05-fileinfo.stdout", "command-05-fileinfo.stderr"),
    )
    artifact_by_partial = {
        "place-nodes.pbf.partial": pbf,
        "place-nodes.opl.partial": opl,
    }
    for execution, expected_names in zip(
        executions, expected_output_names, strict=True
    ):
        outputs = execution.get("outputs") if isinstance(execution, dict) else None
        if not isinstance(outputs, list) or len(outputs) != len(expected_names):
            raise GlobalPlacePackageError(
                "extraction receipt command output ledger is malformed"
            )
        for output, expected_name in zip(outputs, expected_names, strict=True):
            if (
                not isinstance(output, dict)
                or set(output) != {"bytes", "name", "sha256"}
                or output.get("name") != expected_name
                or type(output.get("bytes")) is not int
                or output["bytes"] < 0
            ):
                raise GlobalPlacePackageError(
                    "extraction receipt command output identity is malformed"
                )
            digest = _require_sha256(
                output.get("sha256"), "extraction receipt command output"
            )
            artifact = artifact_by_partial.get(expected_name)
            if artifact is not None:
                if (
                    output["bytes"] != artifact["bytes"]
                    or digest != artifact["sha256"]
                ):
                    raise GlobalPlacePackageError(
                        "extraction receipt command artifact identity differs"
                    )
                continue
            verify_file_identity(
                extraction_directory / expected_name,
                expected_bytes=output["bytes"],
                expected_sha256=digest,
            )


def _validate_strict_opl_audit(
    extraction_directory: Path,
    expected_audit: object,
    expected_semantic_audit: object,
) -> None:
    if not isinstance(expected_audit, dict) or not isinstance(
        expected_semantic_audit, dict
    ):
        raise GlobalPlacePackageError("extraction receipt strict OPL audit is malformed")
    actual, actual_semantic = _strict_opl_audits(
        extraction_directory / "place-nodes.opl"
    )
    if expected_audit != actual:
        raise GlobalPlacePackageError(
            "extraction receipt strict OPL audit differs from the artifact"
        )
    if expected_semantic_audit != actual_semantic:
        raise GlobalPlacePackageError(
            "extraction receipt semantic admission audit differs from the artifact"
        )


def source_binding_from_extraction_receipt(
    extraction_directory: Path,
) -> "PlaceSourceBinding":
    if not isinstance(extraction_directory, Path) or not extraction_directory.is_dir():
        raise GlobalPlacePackageError("extraction directory is not readable")
    receipt_path = extraction_directory / "extraction-receipt.json"
    receipt_identity = _stream_file_identity(receipt_path)
    if not 0 < receipt_identity.bytes <= 16 * 1024 * 1024:
        raise GlobalPlacePackageError("extraction receipt byte length is outside its bound")
    raw = _read_bounded_file(receipt_path, 16 * 1024 * 1024, "extraction receipt")
    try:
        receipt = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise GlobalPlacePackageError("extraction receipt is malformed") from error
    if (
        not isinstance(receipt, dict)
        or _canonical_json_bytes(receipt) != raw
        or receipt.get("schema")
        != "flightalert.experiment8.osm-global-place-extraction-receipt.v1"
    ):
        raise GlobalPlacePackageError("extraction receipt schema or canonical bytes differ")
    if set(receipt) != {
        "artifacts",
        "code",
        "commands",
        "runIdentitySha256",
        "runtime",
        "rendererSemanticOutcome",
        "schema",
        "selection",
        "semanticAdmissionAudit",
        "semanticAdmissionPolicySha256",
        "source",
        "strictOplAudit",
    }:
        raise GlobalPlacePackageError("extraction receipt has unexpected fields")
    source = receipt.get("source")
    artifacts = receipt.get("artifacts")
    if not isinstance(source, dict) or not isinstance(artifacts, dict):
        raise GlobalPlacePackageError("extraction receipt lacks source artifacts")
    if (
        set(source) != {"bytes", "path", "sha256"}
        or type(source.get("path")) is not str
        or not source["path"]
        or type(source.get("bytes")) is not int
        or source["bytes"] < 0
        or type(source.get("sha256")) is not str
    ):
        raise GlobalPlacePackageError("extraction receipt planet binding has wrong types")
    _require_sha256(source["sha256"], "extraction receipt planet")
    pbf = artifacts.get("candidatePbf")
    opl = artifacts.get("candidateOpl")
    outcomes = artifacts.get("rendererSemanticOutcomes")
    if (
        set(artifacts)
        != {"candidateOpl", "candidatePbf", "rendererSemanticOutcomes"}
        or not isinstance(pbf, dict)
        or not isinstance(opl, dict)
        or not isinstance(outcomes, dict)
    ):
        raise GlobalPlacePackageError("extraction receipt artifact binding is malformed")
    if (
        pbf.get("name") != "place-nodes.pbf"
        or opl.get("name") != "place-nodes.opl"
        or outcomes.get("name") != "place-semantic-outcomes.bin"
    ):
        raise GlobalPlacePackageError("extraction receipt artifact names are not canonical")
    for document in (pbf, opl, outcomes):
        if (
            set(document) != {"bytes", "name", "sha256"}
            or type(document.get("bytes")) is not int
            or document["bytes"] < 0
            or type(document.get("sha256")) is not str
        ):
            raise GlobalPlacePackageError("extraction receipt artifact identity has wrong types")
        _require_sha256(document["sha256"], "extraction receipt artifact")
    expected_code = _identity_document(
        _stream_file_identity(Path(__file__).resolve()), name=Path(__file__).name
    )
    if receipt.get("code") != expected_code:
        recovery_receipt = extraction_directory / "extraction-recovery-receipt.json"
        if recovery_receipt.is_file() and not recovery_receipt.is_symlink():
            from .osm_global_place_recovery import (
                source_binding_from_recovered_extraction,
            )

            return source_binding_from_recovered_extraction(extraction_directory)
        raise GlobalPlacePackageError("extraction receipt code identity differs")
    for document in (pbf, opl, outcomes):
        verify_file_identity(
            extraction_directory / str(document["name"]),
            expected_bytes=int(document["bytes"]),
            expected_sha256=str(document["sha256"]),
        )
    expected_runtime = _pinned_runtime_document()
    if receipt.get("runtime") != expected_runtime:
        raise GlobalPlacePackageError("extraction receipt runtime identity differs")
    if receipt.get("selection") != {
        "filterExpression": "n/place",
        "objectKind": "node",
        "referencedObjectsOmitted": True,
    }:
        raise GlobalPlacePackageError("extraction receipt selection contract differs")
    if (
        receipt.get("semanticAdmissionPolicySha256")
        != SEMANTIC_ADMISSION_POLICY_SHA256
    ):
        raise GlobalPlacePackageError(
            "extraction receipt semantic admission policy differs"
        )
    renderer_semantic_outcome = PlaceRendererSemanticOutcome.from_document(
        receipt.get("rendererSemanticOutcome")
    )
    if (
        outcomes["bytes"]
        != renderer_semantic_outcome.node_count
        * RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES
        or outcomes["sha256"] != renderer_semantic_outcome.stream_sha256
    ):
        raise GlobalPlacePackageError(
            "renderer semantic outcome artifact differs from its evidence"
        )
    _validate_receipt_command_contract(
        receipt,
        extraction_directory=extraction_directory,
        source=source,
        pbf=pbf,
        opl=opl,
        code=expected_code,
        runtime=expected_runtime,
    )
    _validate_strict_opl_audit(
        extraction_directory,
        receipt.get("strictOplAudit"),
        receipt.get("semanticAdmissionAudit"),
    )
    semantic_admission = PlaceSemanticAdmissionAudit.from_documents(
        receipt["strictOplAudit"], receipt["semanticAdmissionAudit"]
    )
    return PlaceSourceBinding(
        planet_path=str(source["path"]),
        planet_bytes=int(source["bytes"]),
        planet_sha256=str(source["sha256"]),
        candidate_pbf_bytes=int(pbf["bytes"]),
        candidate_pbf_sha256=str(pbf["sha256"]),
        opl_bytes=int(opl["bytes"]),
        opl_sha256=str(opl["sha256"]),
        extraction_receipt_sha256=receipt_identity.sha256,
        recovery_receipt_sha256=None,
        renderer_semantic_outcome_path=str(
            extraction_directory / "place-semantic-outcomes.bin"
        ),
        renderer_semantic_outcome_bytes=(
            int(outcomes["bytes"])
        ),
        renderer_semantic_outcome=renderer_semantic_outcome,
        semantic_admission_policy_sha256=SEMANTIC_ADMISSION_POLICY_SHA256,
        semantic_admission=semantic_admission,
    )


__all__ = [
    "EXPECTED_PLANET_BYTES",
    "EXPECTED_PLANET_PATH",
    "EXPECTED_PLANET_SHA256",
    "BOOST_LIBRARY_PATH",
    "BOOST_LIBRARY_SHA256",
    "FileIdentity",
    "GlobalPlaceExtractionResult",
    "GlobalPlaceBuildResult",
    "GlobalPlacePackageError",
    "OSMIUM_BINARY_PATH",
    "OSMIUM_BINARY_SHA256",
    "OSMIUM_DEB_SHA256",
    "OSMIUM_LIBRARY_PATH",
    "OSMIUM_RUNTIME_ROOT",
    "OsmiumCommand",
    "PlaceSemanticAdmissionAudit",
    "PlaceRendererSemanticOutcome",
    "PlaceSourceBinding",
    "PINNED_LIBOSMIUM_VERSION",
    "PINNED_OSMIUM_VERSION",
    "StrictOplNode",
    "SEMANTIC_ADMISSION_POLICY_SHA256",
    "RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES",
    "RENDERER_SEMANTIC_OUTCOME_SCHEMA",
    "build_osmium_extraction_commands",
    "extract_global_place_opl",
    "iter_strict_place_opl",
    "source_binding_from_extraction_receipt",
    "verify_file_identity",
]


def render_global_place_package(
    *,
    opl_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: PlaceSourceBinding,
    zooms: Sequence[int] = tuple(range(4, 12)),
    checkpoint_nodes: int = 10_000,
    pause_after_input_nodes: int | None = None,
) -> GlobalPlaceBuildResult:
    from .osm_global_place_store import render_global_place_package as implementation

    return implementation(
        opl_path=opl_path,
        output_directory=output_directory,
        work_directory=work_directory,
        package_id=package_id,
        source_binding=source_binding,
        zooms=zooms,
        checkpoint_nodes=checkpoint_nodes,
        pause_after_input_nodes=pause_after_input_nodes,
    )


__all__.append("render_global_place_package")


def global_place_plan_document() -> dict[str, object]:
    commands = build_osmium_extraction_commands(
        planet_linux_path="/PLANET_WSL",
        stage_linux_directory="/STAGE_WSL",
    )
    return {
        "extractionCommands": [
            {"arguments": list(command.arguments), "role": command.role}
            for command in commands
        ],
        "planet": {
            "bytes": EXPECTED_PLANET_BYTES,
            "path": str(EXPECTED_PLANET_PATH),
            "sha256": EXPECTED_PLANET_SHA256,
        },
        "render": {
            "boundedExternalState": "SQLite",
            "fullWorldZ4To11IndexBytes": sum(4**zoom for zoom in range(4, 12))
            * 24,
            "maximumPostingsPerRenderedNode": 8,
            "packageSchemaVersion": 3,
            "rawTileByteCeiling": 16 * 1024 * 1024,
            "recordsPerTileCeiling": 65_536,
            "sqliteCacheTargetBytes": 64 * 1024 * 1024,
            "zooms": list(range(4, 12)),
        },
        "runtime": {
            "boostLibraryPath": BOOST_LIBRARY_PATH,
            "boostLibrarySha256": BOOST_LIBRARY_SHA256,
            "libosmiumVersion": PINNED_LIBOSMIUM_VERSION,
            "locale": "C.UTF-8",
            "osmiumBinaryPath": OSMIUM_BINARY_PATH,
            "osmiumBinarySha256": OSMIUM_BINARY_SHA256,
            "osmiumDebSha256": OSMIUM_DEB_SHA256,
            "osmiumVersion": PINNED_OSMIUM_VERSION,
        },
        "schema": "flightalert.experiment8.osm-global-place-plan.v1",
    }


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="osm_global_place_package.py",
        description="Build the exact whole-world OSM direct-node place-label V3 supplement.",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("plan", help="print the immutable production plan without reading files")
    extract = commands.add_parser("extract", help="verify the planet and run pinned osmium")
    extract.add_argument("--planet", type=Path, default=EXPECTED_PLANET_PATH)
    extract.add_argument("--output", type=Path, required=True)
    commands.add_parser(
        "recover-retained",
        help="finalize only the exact pinned retained 260629 extraction stage",
    )
    render = commands.add_parser("render", help="render an exact completed extraction")
    render.add_argument("--extraction", type=Path, required=True)
    render.add_argument("--output", type=Path, required=True)
    render.add_argument("--work", type=Path, required=True)
    render.add_argument(
        "--package-id",
        default="world-osm-direct-node-place-labels-260629-v3",
    )
    render.add_argument("--checkpoint-nodes", type=int, default=10_000)
    return parser


def _write_json(stream: object, document: object) -> None:
    raw = _canonical_json_bytes(document)
    binary = getattr(stream, "buffer", None)
    if binary is not None:
        binary.write(raw)
        binary.flush()
    else:
        stream.write(raw.decode("utf-8"))
        stream.flush()


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _argument_parser().parse_args(argv)
    try:
        if arguments.command == "plan":
            _write_json(sys.stdout, global_place_plan_document())
            return 0
        if arguments.command == "extract":
            result = extract_global_place_opl(
                planet_path=arguments.planet,
                output_directory=arguments.output,
            )
        elif arguments.command == "recover-retained":
            from .osm_global_place_recovery import (
                recover_completed_extraction_stage,
            )

            result = recover_completed_extraction_stage()
        else:
            binding = source_binding_from_extraction_receipt(arguments.extraction)
            if (
                binding.planet_bytes != EXPECTED_PLANET_BYTES
                or binding.planet_sha256 != EXPECTED_PLANET_SHA256
            ):
                raise GlobalPlacePackageError(
                    "render extraction is not bound to the exact 260629 production planet"
                )
            result = render_global_place_package(
                opl_path=arguments.extraction / "place-nodes.opl",
                output_directory=arguments.output,
                work_directory=arguments.work,
                package_id=arguments.package_id,
                source_binding=binding,
                zooms=tuple(range(4, 12)),
                checkpoint_nodes=arguments.checkpoint_nodes,
            )
        _write_json(
            sys.stdout,
            {
                "outputDirectory": str(result.output_directory),
                "receipt": dict(result.receipt),
                "state": result.state,
            },
        )
        return 0
    except GlobalPlacePackageError as error:
        _write_json(
            sys.stderr,
            {
                "error": {
                    "class": f"{type(error).__module__}.{type(error).__qualname__}",
                    "message": str(error),
                },
                "state": "failed",
            },
        )
        return 2


__all__.extend(["global_place_plan_document", "main"])
