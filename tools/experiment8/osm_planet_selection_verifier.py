from __future__ import annotations

import hashlib
import heapq
import json
import os
import re
import secrets
import stat as stat_module
import struct
import unicodedata
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO, Callable, Iterator, Mapping
from xml.parsers import expat


FIXTURE_BROAD_ENVELOPE_PROFILE = (
    "flight-alert-exp8-osm-planet-broad-envelope-fixture-v1"
)
LIVE_BROAD_ENVELOPE_PROFILE = (
    "flight-alert-exp8-osm-planet-true-broad-envelope-260629-v1"
)
LIVE_PLANET_SOURCE_SHA256 = (
    "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
)
LIVE_BROAD_ENVELOPE_PBF_BYTES = 1_566_751_189
LIVE_BROAD_ENVELOPE_PBF_SHA256 = (
    "0cb9c478ce621eedf4a889b72285da49822b48961212913bb95117be76757381"
)
LIVE_BROAD_ENVELOPE_OPL_BYTES = 16_008_341_070
LIVE_BROAD_ENVELOPE_OPL_SHA256 = (
    "dc11fd5cd430cec1aaa018a0cdce34ade1dee0e9f46ba8349e66e0c249850468"
)
LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT = "opl"
LIVE_BROAD_ENVELOPE_WAYS = 39_187_055
LIVE_BROAD_ENVELOPE_RELATIONS = 145_045

_REPORT_SCHEMA = "flight-alert-exp8-osm-planet-broad-verification-report-v3"
_OBSERVATION_SCHEMA = "flight-alert-exp8-osm-planet-broad-verification-observation-v2"
_PRODUCTION_SUMMARY_SCHEMA = "flight-alert-exp8-osm-planet-selection-summary-v2"
_FIXTURE_PRODUCTION_PROFILE = "flight-alert-exp8-osm-planet-name-envelope-fixture-v1"
_LIVE_PRODUCTION_PROFILE = "flight-alert-exp8-osm-planet-name-envelope-260629-v1"
_LIVE_PRODUCTION_NAME_ENVELOPE_PBF_BYTES = 419_750_356
_LIVE_PRODUCTION_NAME_ENVELOPE_PBF_SHA256 = (
    "ffb68c03d8fa2710bfd664dfd4ce43c01cc2fdbbb92599b4d892bd3bc0661b4d"
)
_LIVE_PRODUCTION_NAME_ENVELOPE_OPL_BYTES = 4_347_353_464
_LIVE_PRODUCTION_NAME_ENVELOPE_OPL_SHA256 = (
    "628622248814b1a83727cf19bd7e22cc4ad66b61589c6f137585fd555910785b"
)
_LIVE_PRODUCTION_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT = "opl"
_LIVE_PRODUCTION_NAME_ENVELOPE_WAYS = 5_301_765
_LIVE_PRODUCTION_NAME_ENVELOPE_RELATIONS = 135_237
_POLICY_SCHEMA = "flight-alert-exp8-osm-planet-root-policy-v1"
_ROOT_DOMAIN = b"FAE8OSMROOT1\0"
_BUCKET_DOMAIN = b"FAE8OSMBUCKET1\0"
_REJECTION_DOMAIN = b"FAE8OSMREJ1\0"
_MAX_SIGNED_63 = (1 << 63) - 1
_MAX_U32 = (1 << 32) - 1
_DECISION_STRUCT = struct.Struct("<BQI32sBQI")
_DECISION_RECORD_BYTES = _DECISION_STRUCT.size
_SELECTED_TUPLE_BYTES = 45
_SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
_POSITIVE = re.compile(r"[1-9][0-9]*\Z")
_NONNEGATIVE = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_UTC_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z"
)
_LANGUAGE_FIELD = re.compile(
    r"name:([A-Za-z]{2,3})(?:-([A-Za-z0-9]{2,8}))*\Z"
)
_ALLOWED_WATERWAYS = ("river", "stream", "canal", "tidal_channel", "wadi")
_DIRECT_NAMES = ("name", "int_name", "official_name")
_SEMANTIC_SUFFIXES = (
    "left",
    "right",
    "signed",
    "pronunciation",
    "etymology",
    "source",
    "old",
)
_LIFECYCLE_STATES = (
    "abandoned",
    "construction",
    "demolished",
    "disused",
    "proposed",
    "razed",
    "destroyed",
    "historic",
    "removed",
    "removal",
)
_FALSE_VALUES = ("", "0", "false", "no")
_WAY_REASONS = (
    (1, "WAY_UNSUPPORTED_WATERWAY_VALUE"),
    (2, "WAY_INSUFFICIENT_GEOMETRY"),
    (3, "WAY_CLOSED"),
    (4, "WAY_AREA"),
    (5, "WAY_LIFECYCLE"),
    (6, "WAY_NO_SUPPORTED_NAME_KEY"),
    (7, "WAY_SUPPORTED_NAMES_ALL_BLANK"),
)
_RELATION_REASONS = (
    (8, "RELATION_UNSUPPORTED_TYPE"),
    (9, "RELATION_NO_SUPPORTED_NAME_KEY"),
    (10, "RELATION_SUPPORTED_NAMES_ALL_BLANK"),
)
_RELATION_MEMBER_KIND = {"node": 0, "way": 1, "relation": 2}
_OPL_MEMBER_KIND = {"n": 0, "w": 1, "r": 2}
_HEX = frozenset("0123456789abcdefABCDEF")
_DIRECT_OPL_SPECIAL = frozenset(" ,=@")
_EXPECTED_PRODUCTION_NAMES = frozenset(
    {
        "root-ids.txt",
        "selected-tuples.bin",
        "selection-summary.json",
        *(f"roots-{bucket:03d}.bin" for bucket in range(256)),
    }
)


class VerificationError(ValueError):
    """The independent broad source cannot prove the production selection."""


def _json_bytes(document: Mapping[str, object]) -> bytes:
    try:
        return (
            json.dumps(
                document,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError) as error:
        raise VerificationError(f"canonical verifier JSON encoding failed: {error}") from error


def _policy_bytes() -> bytes:
    document = {
        "allowedDirectWaterwayValues": list(_ALLOWED_WATERWAYS),
        "areaFalseValues": list(_FALSE_VALUES),
        "displayNameDirectKeys": list(_DIRECT_NAMES),
        "displayNameLanguagePattern": _LANGUAGE_FIELD.pattern,
        "falseTagValues": list(_FALSE_VALUES),
        "lifecycleKeyRules": ["state", "state:waterway", "waterway:state"],
        "lifecycleStates": list(_LIFECYCLE_STATES),
        "nonLanguageNameSuffixes": list(_SEMANTIC_SUFFIXES),
        "nonNfcHandling": "preserve_exact_and_audit_only",
        "relationRejectionPrecedence": [list(item) for item in _RELATION_REASONS],
        "relationType": "waterway",
        "schema": _POLICY_SCHEMA,
        "wayGeometry": {"minimumNodeRefs": 2, "open": True},
        "wayRejectionPrecedence": [list(item) for item in _WAY_REASONS],
    }
    return _json_bytes(document)


GLOBAL_POLICY_SHA256 = hashlib.sha256(_policy_bytes()).hexdigest()


def _identity(value: str, label: str) -> str:
    if type(value) is not str or _SHA256_PATTERN.fullmatch(value) is None:
        raise VerificationError(f"{label} must be a lowercase SHA-256 identity")
    return value


@dataclass(frozen=True, slots=True)
class VerificationBindings:
    planet_source_sha256: str
    broad_pbf_bytes: int
    broad_pbf_sha256: str
    converted_stream_bytes: int
    converted_stream_sha256: str
    converted_stream_format: str
    runtime_sha256: str
    policy_sha256: str
    code_sha256: str

    def __post_init__(self) -> None:
        for label, value in (
            ("planet source SHA-256", self.planet_source_sha256),
            ("broad PBF SHA-256", self.broad_pbf_sha256),
            ("converted stream SHA-256", self.converted_stream_sha256),
            ("runtime SHA-256", self.runtime_sha256),
            ("policy SHA-256", self.policy_sha256),
            ("code SHA-256", self.code_sha256),
        ):
            _identity(value, label)
        for label, value in (
            ("broad PBF byte count", self.broad_pbf_bytes),
            ("converted stream byte count", self.converted_stream_bytes),
        ):
            if type(value) is not int or value < 0:
                raise VerificationError(f"{label} must be a nonnegative integer")
        if self.converted_stream_format not in {"opl", "xml"}:
            raise VerificationError("converted stream format must be exactly 'opl' or 'xml'")


@dataclass(frozen=True, slots=True)
class VerificationLimits:
    max_input_bytes: int = 16 * 1024 * 1024 * 1024
    max_line_bytes: int = 64 * 1024 * 1024
    max_objects: int = 40_000_000
    max_total_tags: int = 500_000_000
    max_total_references: int = 1_000_000_000
    max_tags_per_object: int = 65_535
    max_references_per_object: int = 10_000_000
    max_text_utf8_bytes: int = 65_535
    max_production_summary_bytes: int = 16 * 1024 * 1024
    max_production_tuple_bytes: int = 2 * 1024 * 1024 * 1024
    max_production_root_ids_bytes: int = 1024 * 1024 * 1024
    max_production_bucket_bytes: int = 2 * 1024 * 1024 * 1024
    max_production_total_bucket_bytes: int = 4 * 1024 * 1024 * 1024
    max_production_frame_bytes: int = 512 * 1024 * 1024
    sort_chunk_records: int = 250_000
    max_sort_runs: int = 256
    read_chunk_bytes: int = 1024 * 1024

    def __post_init__(self) -> None:
        for label, value in (
            ("input byte", self.max_input_bytes),
            ("line byte", self.max_line_bytes),
            ("object", self.max_objects),
            ("tag", self.max_total_tags),
            ("reference", self.max_total_references),
            ("per-object tag", self.max_tags_per_object),
            ("per-object reference", self.max_references_per_object),
            ("text UTF-8 byte", self.max_text_utf8_bytes),
            ("production summary byte", self.max_production_summary_bytes),
            ("production tuple byte", self.max_production_tuple_bytes),
            ("production root-ID byte", self.max_production_root_ids_bytes),
            ("production bucket byte", self.max_production_bucket_bytes),
            ("production total bucket byte", self.max_production_total_bucket_bytes),
            ("production frame byte", self.max_production_frame_bytes),
            ("external-sort run", self.max_sort_runs),
        ):
            if type(value) is not int or value < 0:
                raise VerificationError(f"{label} ceiling must be a nonnegative integer")
        if type(self.sort_chunk_records) is not int or self.sort_chunk_records <= 0:
            raise VerificationError("external-sort chunk records must be positive")
        if type(self.read_chunk_bytes) is not int or self.read_chunk_bytes <= 0:
            raise VerificationError("read chunk size must be positive")


@dataclass(frozen=True, slots=True)
class VerificationResult:
    report_bytes: bytes
    report_sha256: str
    observation_bytes: bytes
    observation_sha256: str
    selected_way_count: int
    selected_relation_count: int
    non_nfc_field_count: int
    non_nfc_record_count: int
    rejection_ledger_sha256: str
    planet_source_sha256: str
    broad_pbf_bytes: int
    broad_pbf_sha256: str
    converted_stream_bytes: int
    converted_stream_sha256: str
    converted_stream_format: str


@dataclass(frozen=True, slots=True)
class _Member:
    kind: int
    ref: int
    role: str
    ordinal: int


@dataclass(frozen=True, slots=True)
class _SourceObject:
    kind: int
    object_id: int
    version: int
    epoch_seconds: int
    tags: tuple[tuple[str, str], ...]
    refs: tuple[int, ...]
    members: tuple[_Member, ...]


@dataclass(slots=True)
class _BroadStats:
    digest: object
    bytes: int = 0
    objects: int = 0
    ways: int = 0
    relations: int = 0
    tags: int = 0
    references: int = 0


@dataclass(frozen=True, slots=True)
class _ProductionFacts:
    pinned: _PinnedProduction
    bucket_entries: tuple[dict[str, object], ...]
    input_objects: int
    converted_stream_bytes: int
    converted_stream_sha256: str
    ways: int
    relations: int
    non_nfc_fields: int
    non_nfc_records: int
    rejection_counts: tuple[tuple[int, int], ...]
    rejection_records: int
    rejection_ledger_sha256: str


def _positive(raw: str | None, maximum: int, label: str) -> int:
    maximum_digits = str(maximum)
    if type(raw) is not str or not raw or raw[0] == "0":
        raise VerificationError(f"{label} must use canonical positive decimal syntax")
    if len(raw) > len(maximum_digits):
        raise VerificationError(f"{label} exceeds its positive integer ceiling")
    if not raw.isascii() or not raw.isdigit():
        raise VerificationError(f"{label} must use canonical positive decimal syntax")
    if len(raw) == len(maximum_digits) and raw > maximum_digits:
        raise VerificationError(f"{label} exceeds its positive integer ceiling")
    try:
        return int(raw)
    except (ValueError, MemoryError) as error:
        raise VerificationError(f"{label} could not be converted to a bounded integer") from error


def _nonnegative(raw: str | None, maximum: int, label: str) -> int:
    maximum_digits = str(maximum)
    if type(raw) is not str or not raw or (len(raw) > 1 and raw[0] == "0"):
        raise VerificationError(f"{label} must use canonical nonnegative decimal syntax")
    if len(raw) > len(maximum_digits):
        raise VerificationError(f"{label} exceeds its integer ceiling")
    if not raw.isascii() or not raw.isdigit():
        raise VerificationError(f"{label} must use canonical nonnegative decimal syntax")
    if len(raw) == len(maximum_digits) and raw > maximum_digits:
        raise VerificationError(f"{label} exceeds its integer ceiling")
    try:
        return int(raw)
    except (ValueError, MemoryError) as error:
        raise VerificationError(f"{label} could not be converted to a bounded integer") from error


def _epoch(raw: str | None, label: str) -> int:
    if type(raw) is not str or _UTC_TIMESTAMP.fullmatch(raw) is None:
        raise VerificationError(f"{label} timestamp must be canonical UTC")
    try:
        value = datetime.strptime(raw, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as error:
        raise VerificationError(f"{label} timestamp is not a real UTC instant") from error
    delta = value - datetime(1970, 1, 1, tzinfo=timezone.utc)
    return delta.days * 86_400 + delta.seconds


def _bounded_text(value: str | None, label: str, limits: VerificationLimits) -> str:
    if type(value) is not str:
        raise VerificationError(f"{label} must be text")
    try:
        raw = value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise VerificationError(f"{label} is not valid Unicode") from error
    if len(raw) > limits.max_text_utf8_bytes:
        raise VerificationError(f"{label} exceeds the text UTF-8 byte ceiling")
    if "\x00" in value:
        raise VerificationError(f"{label} contains a forbidden NUL")
    return value


def _decode_opl(raw: str, label: str, limits: VerificationLimits) -> str:
    """Decode `%` + 1..6 hex + `%` under the independent Osmium rule.

    Values above U+10FFFF and UTF-16 surrogate code points are not Unicode
    scalars. U+0000 is a scalar but is rejected by the OSM text invariant after
    decoding; it is never treated as an alias for a literal percent sign.
    Direct OPL syntax characters and the undefined empty `%%` form are rejected.
    """

    result = bytearray()
    cursor = 0
    while cursor < len(raw):
        character = raw[cursor]
        if character != "%":
            if character in _DIRECT_OPL_SPECIAL:
                raise VerificationError(f"{label} contains a direct ambiguous OPL character")
            try:
                encoded = character.encode("utf-8")
            except UnicodeEncodeError as error:
                raise VerificationError(f"{label} is not valid Unicode") from error
            if len(result) + len(encoded) > limits.max_text_utf8_bytes:
                raise VerificationError(f"{label} exceeds the text UTF-8 byte ceiling")
            result.extend(encoded)
            cursor += 1
            continue
        end = cursor + 1
        count = 0
        codepoint = 0
        while end < len(raw) and raw[end] != "%" and count <= 6:
            digit = raw[end]
            if digit not in _HEX:
                raise VerificationError(f"{label} contains a malformed OPL escape")
            codepoint = (codepoint << 4) | int(digit, 16)
            count += 1
            end += 1
        if end >= len(raw):
            raise VerificationError(f"{label} contains a truncated OPL escape")
        if count < 1 or count > 6:
            raise VerificationError(f"{label} contains a malformed OPL escape")
        if codepoint > 0x10FFFF or 0xD800 <= codepoint <= 0xDFFF:
            raise VerificationError(f"{label} OPL escape is not a Unicode scalar value")
        try:
            encoded = chr(codepoint).encode("utf-8")
        except (ValueError, UnicodeEncodeError) as error:
            raise VerificationError(f"{label} OPL escape is not a Unicode scalar value") from error
        if len(result) + len(encoded) > limits.max_text_utf8_bytes:
            raise VerificationError(f"{label} exceeds the text UTF-8 byte ceiling")
        result.extend(encoded)
        cursor = end + 1
    try:
        value = bytes(result).decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise VerificationError(f"{label} is not valid Unicode") from error
    return _bounded_text(value, label, limits)


def _bounded_opl_segments(
    raw: str,
    delimiter: str,
    ceiling: int,
    ceiling_message: str,
):
    if raw == "":
        return
    start = 0
    count = 0
    for index, character in enumerate(raw):
        if character != delimiter:
            continue
        count += 1
        if count > ceiling:
            raise VerificationError(ceiling_message)
        yield raw[start:index]
        start = index + 1
    count += 1
    if count > ceiling:
        raise VerificationError(ceiling_message)
    yield raw[start:]


def _opl_tags(
    raw: str,
    label: str,
    limits: VerificationLimits,
    total_budget: int | None = None,
) -> tuple[tuple[str, str], ...]:
    if raw == "":
        return ()
    result: dict[str, str] = {}
    ceiling = limits.max_tags_per_object
    ceiling_message = f"{label} exceeds the per-object tag ceiling"
    if total_budget is not None and total_budget < ceiling:
        ceiling = total_budget
        ceiling_message = f"broad input exceeds the tag ceiling {limits.max_total_tags}"
    entries = _bounded_opl_segments(
        raw,
        ",",
        ceiling,
        ceiling_message,
    )
    assert entries is not None
    for entry in entries:
        separator = entry.find("=")
        if separator < 0 or entry.find("=", separator + 1) >= 0:
            raise VerificationError(f"{label} has malformed OPL tags")
        encoded_key = entry[:separator]
        encoded_value = entry[separator + 1 :]
        key = _decode_opl(encoded_key, f"{label} tag key", limits)
        value = _decode_opl(encoded_value, f"{label} tag value", limits)
        if not key:
            raise VerificationError(f"{label} contains an empty tag key")
        if key in result:
            raise VerificationError(f"{label} contains duplicate decoded tag {key!r}")
        result[key] = value
    return tuple(sorted(result.items(), key=lambda item: item[0].encode("utf-8")))


def _opl_tokens(raw: bytes, line_number: int):
    start = 0
    count = 0
    for index, value in enumerate(raw):
        if value != 0x20:
            continue
        if index == start:
            raise VerificationError(f"broad OPL line {line_number} has noncanonical separators")
        count += 1
        if count > 9:
            raise VerificationError(f"broad OPL line {line_number} has too many attributes")
        yield raw[start:index]
        start = index + 1
    if start == len(raw):
        raise VerificationError(f"broad OPL line {line_number} has noncanonical separators")
    count += 1
    if count > 9:
        raise VerificationError(f"broad OPL line {line_number} has too many attributes")
    yield raw[start:]


def _parse_opl_record(
    raw: bytes,
    line_number: int,
    limits: VerificationLimits,
    *,
    tag_budget: int | None = None,
    reference_budget: int | None = None,
) -> _SourceObject:
    if not raw:
        raise VerificationError(f"broad OPL line {line_number} is empty")
    if b"\r" in raw or b"\x00" in raw:
        raise VerificationError(f"broad OPL line {line_number} contains a forbidden byte")
    if any(value < 0x20 or value == 0x7F for value in raw):
        raise VerificationError(f"broad OPL line {line_number} contains a raw control")
    tokens = _opl_tokens(raw, line_number)
    try:
        head = next(tokens).decode("ascii", errors="strict")
    except (StopIteration, UnicodeDecodeError) as error:
        raise VerificationError(f"broad OPL line {line_number} has an unsupported object kind") from error
    if len(head) < 2 or head[0] not in {"w", "r"}:
        raise VerificationError(f"broad OPL line {line_number} has an unsupported object kind")
    kind = 1 if head[0] == "w" else 2
    object_id = _positive(head[1:], _MAX_SIGNED_63, f"broad OPL line {line_number} ID")
    expected_reference_field = "N" if kind == 1 else "M"
    permitted = {"v", "d", "c", "t", "i", "u", "T", expected_reference_field}
    attributes: dict[str, str] = {}
    for raw_token in tokens:
        try:
            token = raw_token.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise VerificationError(f"broad OPL line {line_number} is not strict UTF-8") from error
        key = token[:1]
        if key not in permitted:
            raise VerificationError(f"broad OPL line {line_number} has unknown attribute")
        if key in attributes:
            raise VerificationError(f"broad OPL line {line_number} has duplicate attribute {key!r}")
        attributes[key] = token[1:]
    required = {"v", "t", "T", expected_reference_field}
    if not required.issubset(attributes):
        raise VerificationError(f"broad OPL line {line_number} is missing required attributes")
    if "d" in attributes and attributes["d"] != "V":
        raise VerificationError(f"broad OPL line {line_number} is not a visible current object")
    if "c" in attributes:
        _nonnegative(attributes["c"], _MAX_SIGNED_63, "broad OPL changeset")
    if "i" in attributes:
        _nonnegative(attributes["i"], _MAX_SIGNED_63, "broad OPL user ID")
    if "u" in attributes:
        _decode_opl(attributes["u"], "broad OPL user", limits)
    version = _positive(attributes["v"], _MAX_U32, f"broad OPL {head} version")
    timestamp = _epoch(attributes["t"], f"broad OPL {head}")
    tags = _opl_tags(attributes["T"], f"broad OPL {head}", limits, tag_budget)
    if kind == 1:
        refs: list[int] = []
        if attributes["N"]:
            ceiling = limits.max_references_per_object
            ceiling_message = "broad OPL way exceeds the per-object reference ceiling"
            if reference_budget is not None and reference_budget < ceiling:
                ceiling = reference_budget
                ceiling_message = (
                    f"broad input exceeds the reference ceiling {limits.max_total_references}"
                )
            raw_refs = _bounded_opl_segments(
                attributes["N"],
                ",",
                ceiling,
                ceiling_message,
            )
            assert raw_refs is not None
            for raw_ref in raw_refs:
                if len(raw_ref) < 2 or raw_ref[0] != "n":
                    raise VerificationError(f"broad OPL {head} has malformed node ref")
                refs.append(_positive(raw_ref[1:], _MAX_SIGNED_63, "broad OPL node ref"))
        return _SourceObject(kind, object_id, version, timestamp, tags, tuple(refs), ())
    members: list[_Member] = []
    if attributes["M"]:
        ceiling = limits.max_references_per_object
        ceiling_message = "broad OPL relation exceeds the per-object reference ceiling"
        if reference_budget is not None and reference_budget < ceiling:
            ceiling = reference_budget
            ceiling_message = (
                f"broad input exceeds the reference ceiling {limits.max_total_references}"
            )
        raw_members = _bounded_opl_segments(
            attributes["M"],
            ",",
            ceiling,
            ceiling_message,
        )
        assert raw_members is not None
        for ordinal, raw_member in enumerate(raw_members):
            separator = raw_member.find("@", 1)
            if len(raw_member) < 3 or raw_member[0] not in _OPL_MEMBER_KIND or separator < 0:
                raise VerificationError(f"broad OPL {head} has malformed member")
            members.append(
                _Member(
                    kind=_OPL_MEMBER_KIND[raw_member[0]],
                    ref=_positive(raw_member[1:separator], _MAX_SIGNED_63, "broad OPL member ref"),
                    role=_decode_opl(raw_member[separator + 1 :], "broad OPL member role", limits),
                    ordinal=ordinal,
                )
            )
    return _SourceObject(kind, object_id, version, timestamp, tags, (), tuple(members))


def _name_key(key: str) -> bool:
    if key in _DIRECT_NAMES:
        return True
    if _LANGUAGE_FIELD.fullmatch(key) is None:
        return False
    tail = key[5:]
    start = 0
    while True:
        end = tail.find("-", start)
        if end < 0:
            return tail[start:].casefold() not in _SEMANTIC_SUFFIXES
        if tail[start:end].casefold() in _SEMANTIC_SUFFIXES:
            return False
        start = end + 1


def _reason(value: _SourceObject) -> int:
    tags = dict(value.tags)
    names = [tag_value for key, tag_value in value.tags if _name_key(key)]
    if value.kind == 1:
        if tags.get("waterway") not in _ALLOWED_WATERWAYS:
            return 1
        if len(value.refs) < 2:
            return 2
        if value.refs[0] == value.refs[-1]:
            return 3
        if tags.get("area", "").strip().casefold() not in _FALSE_VALUES:
            return 4
        for state in _LIFECYCLE_STATES:
            if tags.get(state, "").strip().casefold() not in _FALSE_VALUES:
                return 5
            if f"{state}:waterway" in tags or f"waterway:{state}" in tags:
                return 5
        if not names:
            return 6
        if not any(name.strip() for name in names):
            return 7
        return 0
    if tags.get("type") != "waterway":
        return 8
    if not names:
        return 9
    if not any(name.strip() for name in names):
        return 10
    return 0


def _append_text(output: bytearray, value: str) -> None:
    raw = value.encode("utf-8")
    if len(raw) > _MAX_U32:
        raise VerificationError("independent object text exceeds the u32 byte ceiling")
    output.extend(struct.pack("<I", len(raw)))
    output.extend(raw)


def _payload(value: _SourceObject) -> bytes:
    result = bytearray(struct.pack("<BQI", value.kind, value.object_id, value.version))
    result.extend(struct.pack("<qI", value.epoch_seconds, len(value.tags)))
    for key, tag_value in value.tags:
        _append_text(result, key)
        _append_text(result, tag_value)
    if value.kind == 1:
        result.extend(struct.pack("<I", len(value.refs)))
        for ref in value.refs:
            result.extend(struct.pack("<Q", ref))
    else:
        result.extend(struct.pack("<I", len(value.members)))
        for member in value.members:
            result.extend(struct.pack("<IBQ", member.ordinal, member.kind, member.ref))
            _append_text(result, member.role)
    if len(result) + 32 > _MAX_U32:
        raise VerificationError("independent object exceeds the u32 frame ceiling")
    return bytes(result)


def _non_nfc(value: _SourceObject) -> tuple[int, int]:
    count = sum(
        unicodedata.normalize("NFC", field) != field
        for pair in value.tags
        for field in pair
    ) + sum(
        unicodedata.normalize("NFC", member.role) != member.role
        for member in value.members
    )
    return count, int(count != 0)


@dataclass(frozen=True, slots=True)
class _HeldFileSnapshot:
    file_id: tuple[int, int]
    link_count: int
    size: int
    mtime_ns: int
    ctime_ns: int
    byte_length: int
    sha256: str


class _TrackedWriter:
    def __init__(self, raw: BinaryIO, label: str, path: Path) -> None:
        self._raw = raw
        self._label = label
        self.name = str(path)
        self._digest = hashlib.sha256()
        self._bytes = 0

    @property
    def expected_bytes(self) -> int:
        return self._bytes

    @property
    def expected_sha256(self) -> str:
        return self._digest.hexdigest()

    def write(self, raw: bytes) -> int:
        written = self._raw.write(raw)
        if written != len(raw):
            raise VerificationError(
                f"short write while staging {self._label}: "
                f"expected {len(raw)} bytes, wrote {written}"
            )
        self._digest.update(raw)
        self._bytes += written
        return written

    def close(self) -> None:
        self._raw.close()

    def __enter__(self) -> _TrackedWriter:
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> bool:
        del exc_type, exc_value, traceback
        self.close()
        return False

    def __getattr__(self, name: str):
        return getattr(self._raw, name)


def _is_reparse(status: os.stat_result) -> bool:
    return bool(
        getattr(status, "st_file_attributes", 0)
        & getattr(stat_module, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    )


def _open_held_reader(
    path: Path,
    label: str,
    *,
    deny_write: bool,
    deny_delete: bool,
) -> BinaryIO:
    if os.name == "nt":
        import ctypes
        import msvcrt
        from ctypes import wintypes

        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        create_file = kernel32.CreateFileW
        create_file.argtypes = [
            wintypes.LPCWSTR,
            wintypes.DWORD,
            wintypes.DWORD,
            ctypes.c_void_p,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.HANDLE,
        ]
        create_file.restype = wintypes.HANDLE
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = [wintypes.HANDLE]
        close_handle.restype = wintypes.BOOL
        share = 0x00000001
        if not deny_write:
            share |= 0x00000002
        if not deny_delete:
            share |= 0x00000004
        native = create_file(
            str(path),
            0x80000000,
            share,
            None,
            3,
            0x00200000,
            None,
        )
        if native == ctypes.c_void_p(-1).value:
            number = ctypes.get_last_error()
            raise VerificationError(
                f"{label} held handle open failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        try:
            fd = msvcrt.open_osfhandle(
                int(native),
                os.O_RDONLY | getattr(os, "O_BINARY", 0),
            )
        except BaseException:
            close_handle(native)
            raise
        try:
            return os.fdopen(fd, "rb")
        except BaseException:
            os.close(fd)
            raise

    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as error:
        raise VerificationError(f"{label} held handle open failed: {error}") from error
    try:
        return os.fdopen(fd, "rb")
    except BaseException:
        os.close(fd)
        raise


class _HeldDirectory:
    def __init__(self, fd: int) -> None:
        self._fd = fd

    def fileno(self) -> int:
        if self._fd < 0:
            raise ValueError("directory handle is closed")
        return self._fd

    def close(self) -> None:
        if self._fd >= 0:
            fd = self._fd
            os.close(fd)
            self._fd = -1


class _UnreleasedNativeDirectory(VerificationError):
    def __init__(
        self,
        message: str,
        *,
        native_handle: int = 0,
        fd: int = -1,
    ) -> None:
        super().__init__(message)
        self.native_handle = native_handle
        self.fd = fd

    def retry_cleanup(self) -> None:
        import ctypes
        from ctypes import wintypes

        if self.fd >= 0:
            _mark_windows_fd_for_delete(
                self.fd,
                "unreleased atomic scratch directory",
                require_single_link=False,
            )
            os.close(self.fd)
            self.fd = -1
        if self.native_handle == 0:
            return
        native = wintypes.HANDLE(self.native_handle)
        _mark_windows_native_handle_for_delete(
            native,
            "unreleased atomic scratch directory",
        )
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = [wintypes.HANDLE]
        close_handle.restype = wintypes.BOOL
        if not close_handle(native):
            number = ctypes.get_last_error()
            raise VerificationError(
                "unreleased atomic scratch directory handle close failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        self.native_handle = 0

    def __del__(self) -> None:
        if (
            getattr(self, "native_handle", 0) == 0
            and getattr(self, "fd", -1) < 0
        ):
            return
        try:
            self.retry_cleanup()
            return
        except BaseException:
            pass
        if getattr(self, "fd", -1) >= 0:
            try:
                os.close(self.fd)
            except BaseException:
                pass
            self.fd = -1
        try:
            import ctypes
            from ctypes import wintypes

            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            close_handle = kernel32.CloseHandle
            close_handle.argtypes = [wintypes.HANDLE]
            close_handle.restype = wintypes.BOOL
            close_handle(wintypes.HANDLE(self.native_handle))
        except BaseException:
            pass
        self.native_handle = 0


def _create_held_directory(path: Path, label: str) -> _HeldDirectory:
    if os.name == "nt":
        import ctypes
        import msvcrt
        from ctypes import wintypes

        class _UnicodeString(ctypes.Structure):
            _fields_ = [
                ("length", wintypes.USHORT),
                ("maximum_length", wintypes.USHORT),
                ("buffer", wintypes.LPWSTR),
            ]

        class _ObjectAttributes(ctypes.Structure):
            _fields_ = [
                ("length", wintypes.ULONG),
                ("root_directory", wintypes.HANDLE),
                ("object_name", ctypes.POINTER(_UnicodeString)),
                ("attributes", wintypes.ULONG),
                ("security_descriptor", ctypes.c_void_p),
                ("security_quality_of_service", ctypes.c_void_p),
            ]

        class _IoStatusValue(ctypes.Union):
            _fields_ = [
                ("status", wintypes.LONG),
                ("pointer", ctypes.c_void_p),
            ]

        class _IoStatusBlock(ctypes.Structure):
            _anonymous_ = ("value",)
            _fields_ = [
                ("value", _IoStatusValue),
                ("information", ctypes.c_size_t),
            ]

        absolute = os.path.abspath(path)
        if absolute.startswith("\\\\"):
            nt_path = "\\??\\UNC\\" + absolute.lstrip("\\")
        else:
            nt_path = "\\??\\" + absolute
        path_buffer = ctypes.create_unicode_buffer(nt_path)
        name = _UnicodeString(
            len(nt_path) * ctypes.sizeof(ctypes.c_wchar),
            (len(nt_path) + 1) * ctypes.sizeof(ctypes.c_wchar),
            ctypes.cast(path_buffer, wintypes.LPWSTR),
        )
        attributes = _ObjectAttributes(
            ctypes.sizeof(_ObjectAttributes),
            None,
            ctypes.pointer(name),
            0x00000040,
            None,
            None,
        )
        io_status = _IoStatusBlock()
        native = wintypes.HANDLE()
        ntdll = ctypes.WinDLL("ntdll", use_last_error=True)
        nt_create_file = ntdll.NtCreateFile
        nt_create_file.argtypes = [
            ctypes.POINTER(wintypes.HANDLE),
            wintypes.ULONG,
            ctypes.POINTER(_ObjectAttributes),
            ctypes.POINTER(_IoStatusBlock),
            ctypes.c_void_p,
            wintypes.ULONG,
            wintypes.ULONG,
            wintypes.ULONG,
            wintypes.ULONG,
            ctypes.c_void_p,
            wintypes.ULONG,
        ]
        nt_create_file.restype = wintypes.LONG
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = [wintypes.HANDLE]
        close_handle.restype = wintypes.BOOL
        try:
            status = nt_create_file(
                ctypes.byref(native),
                0x00100000 | 0x00010000 | 0x00000080,
                ctypes.byref(attributes),
                ctypes.byref(io_status),
                None,
                0x00000010,
                0x00000001 | 0x00000002 | 0x00000004,
                2,
                0x00000001 | 0x00000020 | 0x00200000,
                None,
                0,
            )
        except BaseException as primary:
            if native.value:
                try:
                    _mark_windows_native_handle_for_delete(native, label)
                except BaseException as cleanup_error:
                    raise _UnreleasedNativeDirectory(
                        f"{cleanup_error}; retained exact native handle",
                        native_handle=int(native.value),
                    ) from primary
                if not close_handle(native):
                    raise _UnreleasedNativeDirectory(
                        f"{label} retained delete-pending native handle",
                        native_handle=int(native.value),
                    ) from primary
            raise
        if status < 0:
            if native.value:
                try:
                    _mark_windows_native_handle_for_delete(native, label)
                except BaseException as cleanup_error:
                    raise _UnreleasedNativeDirectory(
                        f"{cleanup_error}; retained exact native handle",
                        native_handle=int(native.value),
                    )
                if not close_handle(native):
                    raise _UnreleasedNativeDirectory(
                        f"{label} retained failed-create native handle",
                        native_handle=int(native.value),
                    )
            rtl_status_to_dos_error = ntdll.RtlNtStatusToDosError
            rtl_status_to_dos_error.argtypes = [wintypes.LONG]
            rtl_status_to_dos_error.restype = wintypes.ULONG
            number = int(rtl_status_to_dos_error(status))
            raise VerificationError(
                f"{label} atomic creation failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        try:
            fd = msvcrt.open_osfhandle(
                int(native.value),
                os.O_RDONLY | getattr(os, "O_BINARY", 0),
            )
        except BaseException as primary:
            try:
                _mark_windows_native_handle_for_delete(
                    native,
                    f"{label} after handle wrapping failure",
                )
            except BaseException as cleanup_error:
                raise _UnreleasedNativeDirectory(
                    f"{cleanup_error}; retained exact native handle "
                    f"{int(native.value)}",
                    native_handle=int(native.value),
                ) from primary
            if not close_handle(native):
                raise _UnreleasedNativeDirectory(
                    f"{label} retained delete-pending native handle",
                    native_handle=int(native.value),
                ) from primary
            raise
        try:
            return _HeldDirectory(fd)
        except BaseException as primary:
            try:
                _mark_windows_fd_for_delete(
                    fd,
                    f"{label} after held-directory allocation failure",
                    require_single_link=False,
                )
            except BaseException as cleanup_error:
                raise _UnreleasedNativeDirectory(
                    f"{cleanup_error}; retained exact directory fd {fd}",
                    fd=fd,
                ) from primary
            try:
                os.close(fd)
            except OSError as close_error:
                raise _UnreleasedNativeDirectory(
                    f"{label} retained delete-pending directory fd: "
                    f"{close_error}",
                    fd=fd,
                ) from primary
            raise

    created = False
    try:
        path.mkdir()
        created = True
        fd = os.open(
            path,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
    except OSError as error:
        if created:
            try:
                os.rmdir(path)
            except OSError as cleanup_error:
                raise VerificationError(
                    f"{label} cleanup failed after creation: {cleanup_error}"
                ) from error
        raise VerificationError(f"{label} creation failed: {error}") from error
    try:
        return _HeldDirectory(fd)
    except BaseException as primary:
        try:
            os.close(fd)
            os.rmdir(path)
        except OSError as cleanup_error:
            raise VerificationError(
                f"{label} cleanup failed after held-directory allocation: "
                f"{cleanup_error}"
            ) from primary
        raise


def _held_file_snapshot(
    handle: BinaryIO,
    path: Path,
    label: str,
) -> _HeldFileSnapshot:
    digest = hashlib.sha256()
    byte_length = 0
    try:
        before = os.fstat(handle.fileno())
        path_before = os.stat(path, follow_symlinks=False)
        if (
            not stat_module.S_ISREG(before.st_mode)
            or not stat_module.S_ISREG(path_before.st_mode)
            or _is_reparse(before)
            or _is_reparse(path_before)
            or _file_id(before) != _file_id(path_before)
        ):
            raise VerificationError(f"{label} held identity is not exact")
        handle.seek(0)
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            byte_length += len(chunk)
            digest.update(chunk)
        after = os.fstat(handle.fileno())
        path_after = os.stat(path, follow_symlinks=False)
    except VerificationError:
        raise
    except (OSError, ValueError) as error:
        raise VerificationError(f"{label} held readback failed: {error}") from error
    before_identity = (
        _file_id(before),
        before.st_nlink,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    after_identity = (
        _file_id(after),
        after.st_nlink,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
    )
    if (
        before_identity != after_identity
        or _file_id(path_after) != _file_id(after)
        or not stat_module.S_ISREG(path_after.st_mode)
        or _is_reparse(path_after)
        or byte_length != after.st_size
    ):
        raise VerificationError(f"{label} changed during held readback")
    return _HeldFileSnapshot(
        file_id=_file_id(after),
        link_count=after.st_nlink,
        size=after.st_size,
        mtime_ns=after.st_mtime_ns,
        ctime_ns=after.st_ctime_ns,
        byte_length=byte_length,
        sha256=digest.hexdigest(),
    )


def _mark_windows_native_handle_for_delete(native_handle, label: str) -> None:
    import ctypes
    from ctypes import wintypes

    class _FileDispositionInfo(ctypes.Structure):
        _fields_ = [("delete_file", ctypes.c_ubyte)]

    class _FileDispositionInfoEx(ctypes.Structure):
        _fields_ = [("flags", wintypes.DWORD)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    set_information.restype = wintypes.BOOL
    extended = _FileDispositionInfoEx(0x00000001 | 0x00000002 | 0x00000010)
    if set_information(
        native_handle,
        21,
        ctypes.byref(extended),
        ctypes.sizeof(extended),
    ):
        return
    information = _FileDispositionInfo(1)
    if not set_information(
        native_handle,
        4,
        ctypes.byref(information),
        ctypes.sizeof(information),
    ):
        number = ctypes.get_last_error()
        raise VerificationError(
            f"{label} exact handle deletion failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )


def _mark_windows_fd_for_delete(
    fd: int,
    label: str,
    *,
    require_single_link: bool,
) -> None:
    import ctypes
    import msvcrt
    from ctypes import wintypes

    class _FileStandardInfo(ctypes.Structure):
        _fields_ = [
            ("allocation_size", ctypes.c_longlong),
            ("end_of_file", ctypes.c_longlong),
            ("number_of_links", wintypes.DWORD),
            ("delete_pending", ctypes.c_ubyte),
            ("directory", ctypes.c_ubyte),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    native_handle = wintypes.HANDLE(msvcrt.get_osfhandle(fd))
    if require_single_link:
        standard = _FileStandardInfo()
        if not kernel32.GetFileInformationByHandleEx(
            native_handle,
            1,
            ctypes.byref(standard),
            ctypes.sizeof(standard),
        ):
            number = ctypes.get_last_error()
            raise VerificationError(
                f"{label} native link-count query failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        if standard.number_of_links != 1 or os.fstat(fd).st_nlink != 1:
            raise VerificationError(
                f"{label} no longer has exactly one tracked link; preserved path"
            )
    _mark_windows_native_handle_for_delete(native_handle, label)


def _open_windows_delete_fd(path: Path, label: str, *, directory: bool) -> int:
    import ctypes
    import msvcrt
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    flags = 0x00200000 | (0x02000000 if directory else 0)
    handle = create_file(
        str(path),
        0x00010000 | 0x00000080 | 0x00100000,
        0x00000001 | 0x00000002 | 0x00000004,
        None,
        3,
        flags,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise VerificationError(
            f"{label} exact handle open failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    try:
        return msvcrt.open_osfhandle(
            int(handle),
            os.O_RDONLY | getattr(os, "O_BINARY", 0),
        )
    except BaseException:
        close_handle(handle)
        raise


def _delete_exact_owned_path(
    path: Path,
    expected_file_id: tuple[int, int],
    *,
    directory: bool,
    label: str,
) -> None:
    try:
        status = os.stat(path, follow_symlinks=False)
    except FileNotFoundError as error:
        raise VerificationError(
            f"{label} is missing or moved; unreleased FileId "
            f"{expected_file_id[0]}:{expected_file_id[1]}"
        ) from error
    except OSError as error:
        raise VerificationError(f"inspect {label}: {error}") from error
    valid_type = (
        stat_module.S_ISDIR(status.st_mode)
        if directory
        else stat_module.S_ISREG(status.st_mode)
    )
    if not valid_type or _is_reparse(status) or _file_id(status) != expected_file_id:
        raise VerificationError(
            f"{label} identity changed; preserved replacement"
        )

    if os.name == "nt":
        fd = _open_windows_delete_fd(path, label, directory=directory)
        operation_error: BaseException | None = None
        try:
            pinned = os.fstat(fd)
            pinned_type = (
                stat_module.S_ISDIR(pinned.st_mode)
                if directory
                else stat_module.S_ISREG(pinned.st_mode)
            )
            if (
                not pinned_type
                or _is_reparse(pinned)
                or _file_id(pinned) != expected_file_id
            ):
                raise VerificationError(
                    f"{label} identity changed before exact deletion; "
                    "preserved replacement"
                )
            _mark_windows_fd_for_delete(
                fd,
                label,
                require_single_link=not directory,
            )
        except BaseException as error:
            operation_error = error
        try:
            os.close(fd)
        except OSError as close_error:
            if operation_error is None:
                operation_error = VerificationError(
                    f"{label} exact delete handle close failed: {close_error}"
                )
        if operation_error is not None:
            raise operation_error
        return

    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    if directory:
        flags |= getattr(os, "O_DIRECTORY", 0)
    try:
        fd = os.open(path, flags)
    except OSError as error:
        raise VerificationError(f"{label} exact handle open failed: {error}") from error
    quarantine = path.parent / f".{path.name}.{secrets.token_hex(16)}.cleanup"
    try:
        pinned = os.fstat(fd)
        pinned_type = (
            stat_module.S_ISDIR(pinned.st_mode)
            if directory
            else stat_module.S_ISREG(pinned.st_mode)
        )
        if (
            not pinned_type
            or _file_id(pinned) != expected_file_id
            or (not directory and pinned.st_nlink != 1)
        ):
            raise VerificationError(
                f"{label} identity or link count changed; preserved replacement"
            )
        os.rename(path, quarantine)
        captured = os.stat(quarantine, follow_symlinks=False)
        captured_type = (
            stat_module.S_ISDIR(captured.st_mode)
            if directory
            else stat_module.S_ISREG(captured.st_mode)
        )
        if (
            not captured_type
            or _file_id(captured) != expected_file_id
            or _file_id(os.fstat(fd)) != expected_file_id
            or (not directory and captured.st_nlink != 1)
        ):
            if not directory and not os.path.lexists(path):
                try:
                    os.link(quarantine, path, follow_symlinks=False)
                except OSError:
                    pass
            raise VerificationError(
                f"{label} changed during quarantine; preserved captured path"
            )
        if directory:
            os.rmdir(quarantine)
        else:
            os.unlink(quarantine)
            if os.fstat(fd).st_nlink != 0:
                raise VerificationError(
                    f"{label} acquired another link during cleanup"
                )
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"{label} exact deletion failed: {error}") from error
    finally:
        os.close(fd)


class _UnreleasedAtomicFile(VerificationError):
    def __init__(
        self,
        message: str,
        path: Path,
        *,
        fd: int = -1,
        extra_fd: int = -1,
        native_handle: int = 0,
        extra_native_handle: int = 0,
        wrapped_handle: BinaryIO | None = None,
    ) -> None:
        super().__init__(message)
        self.path = path
        self.fd = fd
        self.extra_fd = extra_fd
        self.native_handle = native_handle
        self.extra_native_handle = extra_native_handle
        self.wrapped_handle = wrapped_handle

    def retry_cleanup(self) -> None:
        if self.fd >= 0:
            if os.name == "nt":
                _mark_windows_fd_for_delete(
                    self.fd,
                    f"atomic scratch artifact {self.path.name} retry",
                    require_single_link=False,
                )
            else:
                _delete_posix_created_fd(
                    self.path,
                    self.fd,
                    f"atomic scratch artifact {self.path.name} retry",
                )
        elif self.native_handle:
            import ctypes
            from ctypes import wintypes

            _mark_windows_native_handle_for_delete(
                wintypes.HANDLE(self.native_handle),
                f"atomic scratch artifact {self.path.name} retry",
            )
        if self.wrapped_handle is not None:
            self.wrapped_handle.close()
            self.wrapped_handle = None
        if self.extra_fd >= 0:
            try:
                os.close(self.extra_fd)
            except OSError as error:
                if error.errno != 9:
                    raise
            self.extra_fd = -1
        if self.extra_native_handle:
            import ctypes
            from ctypes import wintypes

            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
            kernel32.CloseHandle.restype = wintypes.BOOL
            if not kernel32.CloseHandle(
                wintypes.HANDLE(self.extra_native_handle)
            ):
                number = ctypes.get_last_error()
                raise VerificationError(
                    f"atomic scratch artifact {self.path.name} retained secondary "
                    f"native handle: {OSError(number, ctypes.FormatError(number))}"
                )
            self.extra_native_handle = 0
        if self.fd >= 0:
            os.close(self.fd)
            self.fd = -1
        if self.native_handle:
            import ctypes
            from ctypes import wintypes

            kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
            kernel32.CloseHandle.restype = wintypes.BOOL
            if not kernel32.CloseHandle(wintypes.HANDLE(self.native_handle)):
                number = ctypes.get_last_error()
                raise VerificationError(
                    f"atomic scratch artifact {self.path.name} retained native "
                    f"handle: {OSError(number, ctypes.FormatError(number))}"
                )
            self.native_handle = 0

    def __del__(self) -> None:
        try:
            self.retry_cleanup()
            return
        except BaseException:
            pass
        if getattr(self, "fd", -1) >= 0:
            try:
                os.close(self.fd)
            except BaseException:
                pass
            self.fd = -1
        if getattr(self, "extra_fd", -1) >= 0:
            try:
                os.close(self.extra_fd)
            except BaseException:
                pass
            self.extra_fd = -1
        if getattr(self, "extra_native_handle", 0):
            try:
                import ctypes
                from ctypes import wintypes

                kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
                kernel32.CloseHandle.restype = wintypes.BOOL
                kernel32.CloseHandle(
                    wintypes.HANDLE(self.extra_native_handle)
                )
            except BaseException:
                pass
            self.extra_native_handle = 0
        wrapped_handle = getattr(self, "wrapped_handle", None)
        if wrapped_handle is not None:
            try:
                wrapped_handle.close()
            except BaseException:
                pass
            self.wrapped_handle = None
        if getattr(self, "native_handle", 0):
            try:
                import ctypes
                from ctypes import wintypes

                kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
                kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
                kernel32.CloseHandle.restype = wintypes.BOOL
                kernel32.CloseHandle(wintypes.HANDLE(self.native_handle))
            except BaseException:
                pass
            self.native_handle = 0


def _delete_posix_created_fd(path: Path, fd: int, label: str) -> None:
    try:
        held = os.fstat(fd)
        bound = os.stat(path, follow_symlinks=False)
        if (
            not stat_module.S_ISREG(held.st_mode)
            or not stat_module.S_ISREG(bound.st_mode)
            or _file_id(held) != _file_id(bound)
            or held.st_nlink != 1
        ):
            raise VerificationError(
                f"{label} identity changed; retained exact creation fd"
            )
        os.unlink(path)
        if os.fstat(fd).st_nlink != 0:
            raise VerificationError(
                f"{label} acquired an untracked link during cleanup"
            )
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"{label} exact cleanup failed: {error}") from error


def _create_atomic_binary_writer(path: Path, label: str) -> BinaryIO:
    if os.name == "nt":
        import ctypes
        import msvcrt
        from ctypes import wintypes

        class _UnicodeString(ctypes.Structure):
            _fields_ = [
                ("length", wintypes.USHORT),
                ("maximum_length", wintypes.USHORT),
                ("buffer", wintypes.LPWSTR),
            ]

        class _ObjectAttributes(ctypes.Structure):
            _fields_ = [
                ("length", wintypes.ULONG),
                ("root_directory", wintypes.HANDLE),
                ("object_name", ctypes.POINTER(_UnicodeString)),
                ("attributes", wintypes.ULONG),
                ("security_descriptor", ctypes.c_void_p),
                ("security_quality_of_service", ctypes.c_void_p),
            ]

        class _IoStatusValue(ctypes.Union):
            _fields_ = [("status", wintypes.LONG), ("pointer", ctypes.c_void_p)]

        class _IoStatusBlock(ctypes.Structure):
            _anonymous_ = ("value",)
            _fields_ = [
                ("value", _IoStatusValue),
                ("information", ctypes.c_size_t),
            ]

        absolute = os.path.abspath(path)
        nt_path = (
            "\\??\\UNC\\" + absolute.lstrip("\\")
            if absolute.startswith("\\\\")
            else "\\??\\" + absolute
        )
        path_buffer = ctypes.create_unicode_buffer(nt_path)
        name = _UnicodeString(
            len(nt_path) * ctypes.sizeof(ctypes.c_wchar),
            (len(nt_path) + 1) * ctypes.sizeof(ctypes.c_wchar),
            ctypes.cast(path_buffer, wintypes.LPWSTR),
        )
        attributes = _ObjectAttributes(
            ctypes.sizeof(_ObjectAttributes),
            None,
            ctypes.pointer(name),
            0x00000040,
            None,
            None,
        )
        io_status = _IoStatusBlock()
        native = wintypes.HANDLE()
        writer_native = wintypes.HANDLE()
        ntdll = ctypes.WinDLL("ntdll", use_last_error=True)
        nt_create_file = ntdll.NtCreateFile
        nt_create_file.argtypes = [
            ctypes.POINTER(wintypes.HANDLE),
            wintypes.ULONG,
            ctypes.POINTER(_ObjectAttributes),
            ctypes.POINTER(_IoStatusBlock),
            ctypes.c_void_p,
            wintypes.ULONG,
            wintypes.ULONG,
            wintypes.ULONG,
            wintypes.ULONG,
            ctypes.c_void_p,
            wintypes.ULONG,
        ]
        nt_create_file.restype = wintypes.LONG
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        close_handle = kernel32.CloseHandle
        close_handle.argtypes = [wintypes.HANDLE]
        close_handle.restype = wintypes.BOOL
        get_current_process = kernel32.GetCurrentProcess
        get_current_process.argtypes = []
        get_current_process.restype = wintypes.HANDLE
        duplicate_handle = kernel32.DuplicateHandle
        duplicate_handle.argtypes = [
            wintypes.HANDLE,
            wintypes.HANDLE,
            wintypes.HANDLE,
            ctypes.POINTER(wintypes.HANDLE),
            wintypes.DWORD,
            wintypes.BOOL,
            wintypes.DWORD,
        ]
        duplicate_handle.restype = wintypes.BOOL
        current_process = get_current_process()
        try:
            status = nt_create_file(
                ctypes.byref(native),
                0x00100000
                | 0x00010000
                | 0x00000080
                | 0x00000002
                | 0x00000004
                | 0x00000010
                | 0x00000100,
                ctypes.byref(attributes),
                ctypes.byref(io_status),
                None,
                0x00000080,
                0x00000001 | 0x00000002 | 0x00000004,
                2,
                0x00000020 | 0x00000040 | 0x00200000,
                None,
                0,
            )
        except BaseException as primary:
            if native.value:
                try:
                    _mark_windows_native_handle_for_delete(native, label)
                except BaseException as cleanup_error:
                    raise _UnreleasedAtomicFile(
                        f"{cleanup_error}; retained exact native creation handle",
                        path,
                        native_handle=int(native.value),
                    ) from primary
                if not close_handle(native):
                    raise _UnreleasedAtomicFile(
                        f"{label} retained delete-pending native creation handle",
                        path,
                        native_handle=int(native.value),
                    ) from primary
            raise
        if status < 0:
            if native.value:
                try:
                    _mark_windows_native_handle_for_delete(native, label)
                except BaseException as cleanup_error:
                    raise _UnreleasedAtomicFile(
                        f"{cleanup_error}; retained exact native creation handle",
                        path,
                        native_handle=int(native.value),
                    )
                if not close_handle(native):
                    raise _UnreleasedAtomicFile(
                        f"{label} retained failed-create native handle",
                        path,
                        native_handle=int(native.value),
                    )
            rtl_status_to_dos_error = ntdll.RtlNtStatusToDosError
            rtl_status_to_dos_error.argtypes = [wintypes.LONG]
            rtl_status_to_dos_error.restype = wintypes.ULONG
            number = int(rtl_status_to_dos_error(status))
            raise VerificationError(
                f"{label} atomic creation failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        writer_fd = -1
        output: BinaryIO | None = None
        try:
            if not duplicate_handle(
                current_process,
                native,
                current_process,
                ctypes.byref(writer_native),
                0x00100000
                | 0x00000080
                | 0x00000002
                | 0x00000004
                | 0x00000010
                | 0x00000100,
                False,
                0,
            ):
                number = ctypes.get_last_error()
                raise VerificationError(
                    f"{label} exact writer handle duplication failed: "
                    f"{OSError(number, ctypes.FormatError(number))}"
                )
            writer_fd = msvcrt.open_osfhandle(
                int(writer_native.value),
                os.O_WRONLY | getattr(os, "O_BINARY", 0),
            )
            writer_native.value = None
            output = os.fdopen(writer_fd, "wb")
            writer_fd = -1
        except BaseException as primary:
            try:
                _mark_windows_native_handle_for_delete(native, label)
            except BaseException as cleanup_error:
                raise _UnreleasedAtomicFile(
                    f"{cleanup_error}; retained exact native creation handle",
                    path,
                    native_handle=int(native.value),
                    wrapped_handle=output,
                    extra_fd=writer_fd,
                    extra_native_handle=int(writer_native.value or 0),
                ) from primary
            if output is not None:
                try:
                    output.close()
                except OSError as error:
                    raise _UnreleasedAtomicFile(
                        f"{label} retained delete-pending creation and writer "
                        f"handles: {error}",
                        path,
                        native_handle=int(native.value),
                        wrapped_handle=output,
                    ) from primary
                output = None
            elif writer_fd >= 0:
                try:
                    os.close(writer_fd)
                except OSError as error:
                    if error.errno != 9:
                        raise _UnreleasedAtomicFile(
                            f"{label} retained delete-pending creation and writer "
                            f"fds: {error}",
                            path,
                            native_handle=int(native.value),
                            extra_fd=writer_fd,
                        ) from primary
                writer_fd = -1
            elif writer_native.value:
                if not close_handle(writer_native):
                    raise _UnreleasedAtomicFile(
                        f"{label} retained delete-pending creation and native "
                        "writer handles",
                        path,
                        native_handle=int(native.value),
                        extra_native_handle=int(writer_native.value),
                    ) from primary
                writer_native.value = None
            if not close_handle(native):
                raise _UnreleasedAtomicFile(
                    f"{label} retained delete-pending creation handle",
                    path,
                    native_handle=int(native.value),
                ) from primary
            raise
        if not close_handle(native):
            primary = VerificationError(
                f"{label} creation guardian release failed"
            )
            try:
                _mark_windows_native_handle_for_delete(native, label)
            except BaseException as cleanup_error:
                raise _UnreleasedAtomicFile(
                    f"{cleanup_error}; retained exact native creation handle",
                    path,
                    native_handle=int(native.value),
                    wrapped_handle=output,
                ) from primary
            try:
                output.close()
            except OSError as close_error:
                raise _UnreleasedAtomicFile(
                    f"{label} retained delete-pending creation and writer handles: "
                    f"{close_error}",
                    path,
                    native_handle=int(native.value),
                    wrapped_handle=output,
                ) from primary
            if not close_handle(native):
                raise _UnreleasedAtomicFile(
                    f"{label} retained delete-pending native creation handle",
                    path,
                    native_handle=int(native.value),
                ) from primary
            raise primary
        return output

    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        guardian_fd = os.open(path, flags, 0o600)
    except OSError as error:
        raise VerificationError(f"{label} atomic creation failed: {error}") from error
    wrapped_fd = -1
    try:
        wrapped_fd = os.dup(guardian_fd)
        output = os.fdopen(wrapped_fd, "wb")
        wrapped_fd = -1
    except BaseException as primary:
        try:
            _delete_posix_created_fd(path, guardian_fd, label)
        except BaseException as cleanup_error:
            raise _UnreleasedAtomicFile(
                f"{cleanup_error}; retained exact creation fd",
                path,
                fd=guardian_fd,
                extra_fd=wrapped_fd,
            ) from primary
        if wrapped_fd >= 0:
            try:
                os.close(wrapped_fd)
            except OSError as error:
                if error.errno != 9:
                    raise _UnreleasedAtomicFile(
                        f"{label} retained deleted creation fds: {error}",
                        path,
                        fd=guardian_fd,
                        extra_fd=wrapped_fd,
                    ) from primary
        try:
            os.close(guardian_fd)
        except OSError as error:
            raise _UnreleasedAtomicFile(
                f"{label} retained deleted creation fd: {error}",
                path,
                fd=guardian_fd,
            ) from primary
        raise
    try:
        os.close(guardian_fd)
    except OSError as primary:
        try:
            _delete_posix_created_fd(path, guardian_fd, label)
        except BaseException as cleanup_error:
            raise _UnreleasedAtomicFile(
                f"{cleanup_error}; retained exact creation fd",
                path,
                fd=guardian_fd,
                wrapped_handle=output,
            ) from primary
        try:
            output.close()
        except OSError as close_error:
            raise _UnreleasedAtomicFile(
                f"{label} retained deleted creation and writer handles: "
                f"{close_error}",
                path,
                fd=guardian_fd,
                wrapped_handle=output,
            ) from primary
        try:
            os.close(guardian_fd)
        except OSError as close_error:
            raise _UnreleasedAtomicFile(
                f"{label} retained deleted creation fd: {close_error}",
                path,
                fd=guardian_fd,
            ) from primary
        raise VerificationError(
            f"{label} creation guardian release failed: {primary}"
        ) from primary
    return output


class _VerifierScratch:
    def __init__(self, work: Path) -> None:
        self.directory = work / f"fae8-osm-verify-{secrets.token_hex(16)}"
        self._owned_files: dict[Path, tuple[int, int]] = {}
        self._guardians: dict[Path, BinaryIO] = {}
        self._writers: dict[Path, _TrackedWriter] = {}
        self._sealed_handles: dict[Path, BinaryIO] = {}
        self._sealed_snapshots: dict[Path, _HeldFileSnapshot] = {}
        self._states: dict[Path, str] = {}
        self._directory_id: tuple[int, int] | None = None
        self._directory_handle: _HeldDirectory | None = None
        self._pending_path: Path | None = None
        self._pending_file_id: tuple[int, int] | None = None
        self._pending_writer: _TrackedWriter | BinaryIO | None = None
        self._pending_guardian: BinaryIO | None = None
        self._pending_deleted = False
        try:
            self._directory_handle = _create_held_directory(
                self.directory,
                "verifier scratch directory",
            )
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(
                f"verifier scratch directory creation failed: {error}"
            ) from error
        created_status: os.stat_result | None = None
        try:
            created_status = os.fstat(self._directory_handle.fileno())
            path_status = os.stat(self.directory, follow_symlinks=False)
            if (
                not self._valid_directory(created_status)
                or not self._valid_directory(path_status)
                or _file_id(created_status) != _file_id(path_status)
            ):
                raise VerificationError(
                    "created verifier scratch path does not identify its exact held directory"
                )
            self._directory_id = _file_id(created_status)
        except BaseException as primary:
            failures: list[str] = []
            if created_status is None:
                try:
                    created_status = os.stat(self._directory_handle.fileno())
                except (OSError, ValueError, MemoryError) as error:
                    failures.append(
                        f"scratch directory identity was unavailable: {error}"
                    )
            if created_status is not None:
                try:
                    _delete_exact_owned_path(
                        self.directory,
                        (created_status.st_dev, created_status.st_ino),
                        directory=True,
                        label="scratch directory",
                    )
                except VerificationError as error:
                    failures.append(str(error))
                else:
                    try:
                        self._directory_handle.close()
                    except OSError as error:
                        failures.append(
                            f"close scratch directory creation handle: {error}"
                        )
                    else:
                        self._directory_handle = None
            if failures:
                raise VerificationError(
                    "verification cleanup failed after scratch directory tracking "
                    "failure: " + "; ".join(failures)
                ) from primary
            raise

    def __del__(self) -> None:
        collections = (
            getattr(self, "_writers", {}),
            getattr(self, "_guardians", {}),
            getattr(self, "_sealed_handles", {}),
        )
        seen: set[int] = set()
        for collection in collections:
            for handle in collection.values():
                if id(handle) in seen:
                    continue
                seen.add(id(handle))
                try:
                    handle.close()
                except BaseException:
                    pass
        for handle in (
            getattr(self, "_pending_writer", None),
            getattr(self, "_pending_guardian", None),
        ):
            if handle is None or id(handle) in seen:
                continue
            seen.add(id(handle))
            try:
                handle.close()
            except BaseException:
                pass
        directory_handle = getattr(self, "_directory_handle", None)
        if directory_handle is not None:
            try:
                directory_handle.close()
            except BaseException:
                pass

    @staticmethod
    def _is_reparse(status: os.stat_result) -> bool:
        return bool(
            getattr(status, "st_file_attributes", 0)
            & getattr(stat_module, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
        )

    @classmethod
    def _valid_directory(cls, status: os.stat_result) -> bool:
        return stat_module.S_ISDIR(status.st_mode) and not cls._is_reparse(status)

    @classmethod
    def _valid_file(cls, status: os.stat_result) -> bool:
        return stat_module.S_ISREG(status.st_mode) and not cls._is_reparse(status)

    @staticmethod
    def _same_file_id(
        status: os.stat_result,
        expected: tuple[int, int],
    ) -> bool:
        return status.st_dev == expected[0] and status.st_ino == expected[1]

    def _path(self, value: str | Path) -> Path:
        path = self.directory / value if type(value) is str else value
        if not isinstance(path, Path) or path.parent != self.directory:
            raise VerificationError("scratch artifact path is outside its owned directory")
        return path

    def _verify_directory(self) -> tuple[int, int]:
        expected = self._directory_id
        handle = self._directory_handle
        if expected is None or handle is None:
            raise VerificationError("verifier scratch directory ownership is missing")
        try:
            handle_status = os.fstat(handle.fileno())
            status = os.stat(self.directory, follow_symlinks=False)
        except OSError as error:
            raise VerificationError(
                f"verifier scratch directory identity check failed: {error}"
            ) from error
        if (
            not self._valid_directory(handle_status)
            or not self._valid_directory(status)
            or not self._same_file_id(handle_status, expected)
            or not self._same_file_id(status, expected)
        ):
            raise VerificationError(
                "verifier scratch directory identity changed or became a reparse point"
            )
        return expected

    def _unlink_exact(self, path: Path, expected_file_id: tuple[int, int]) -> None:
        _delete_exact_owned_path(
            path,
            expected_file_id,
            directory=False,
            label=f"scratch artifact {path.name}",
        )

    def create(self, name: str) -> _TrackedWriter:
        self._verify_directory()
        if self._pending_path is not None:
            raise VerificationError(
                "a previous scratch ownership failure remains unreleased"
            )
        path = self._path(name)
        raw_handle: BinaryIO | None = _create_atomic_binary_writer(
            path,
            f"scratch artifact {path.name}",
        )
        handle: _TrackedWriter | None = None
        guardian: BinaryIO | None = None
        file_id: tuple[int, int] | None = None
        try:
            self._pending_path = path
            self._pending_writer = raw_handle
            self._pending_deleted = False
            handle_status = os.fstat(raw_handle.fileno())
            file_id = _file_id(handle_status)
            self._pending_file_id = file_id
            handle = _TrackedWriter(raw_handle, path.name, path)
            self._pending_writer = handle
            raw_handle = None
            path_status = os.stat(path, follow_symlinks=False)
            if (
                not self._valid_file(handle_status)
                or not self._valid_file(path_status)
                or not self._same_file_id(path_status, file_id)
            ):
                raise VerificationError(
                    f"scratch artifact {path.name} identity changed while creating it"
                )
            guardian = _open_held_reader(
                path,
                f"scratch artifact {path.name} creation guardian",
                deny_write=False,
                deny_delete=False,
            )
            guardian_status = os.fstat(guardian.fileno())
            if (
                not self._valid_file(guardian_status)
                or not self._same_file_id(guardian_status, file_id)
            ):
                raise VerificationError(
                    f"scratch artifact {path.name} creation guardian is not exact"
                )
            self._pending_guardian = guardian
            self._owned_files[path] = file_id
            self._states[path] = "WRITING"
            self._guardians[path] = guardian
            guardian = None
            self._writers[path] = handle
            self._verify_directory()
            self._pending_path = None
            self._pending_file_id = None
            self._pending_writer = None
            self._pending_guardian = None
            self._pending_deleted = False
        except BaseException as error:
            pending_writer = handle if handle is not None else raw_handle
            if pending_writer is not None:
                self._pending_path = path
                self._pending_writer = pending_writer
                self._pending_deleted = False
            if file_id is None:
                try:
                    if pending_writer is None:
                        raise VerificationError(
                            f"scratch artifact {path.name} lost its atomic creation handle"
                        )
                    try:
                        pending_status = os.fstat(pending_writer.fileno())
                    except MemoryError:
                        pending_status = os.stat(pending_writer.fileno())
                    file_id = _file_id(pending_status)
                except BaseException as identity_error:
                    raise VerificationError(
                        "verification cleanup failed after scratch tracking failure: "
                        f"identify {path.name}: {identity_error}"
                    ) from error
                self._pending_path = path
                self._pending_file_id = file_id
                self._pending_writer = pending_writer
                self._pending_deleted = False
            if guardian is not None and self._pending_guardian is None:
                self._pending_guardian = guardian
            if self._pending_guardian is None:
                try:
                    recovered_guardian = _open_held_reader(
                        path,
                        f"scratch artifact {path.name} recovery guardian",
                        deny_write=False,
                        deny_delete=False,
                    )
                    try:
                        recovered_status = os.fstat(recovered_guardian.fileno())
                    except MemoryError:
                        recovered_status = os.stat(recovered_guardian.fileno())
                    if _file_id(recovered_status) != file_id:
                        raise VerificationError(
                            f"scratch artifact {path.name} recovery guardian is not exact"
                        )
                    self._pending_guardian = recovered_guardian
                except BaseException as guardian_error:
                    if "recovered_guardian" in locals():
                        recovered_guardian.close()
                    raise VerificationError(
                        "verification cleanup failed after scratch tracking failure: "
                        f"retain {path.name}: {guardian_error}"
                    ) from error
            failures = self._cleanup_pending_creation()
            if failures:
                raise VerificationError(
                    "verification cleanup failed after scratch tracking failure: "
                    + "; ".join(failures)
                ) from error
            raise
        assert handle is not None
        return handle

    def _cleanup_pending_creation(self) -> list[str]:
        path = self._pending_path
        expected_file_id = self._pending_file_id
        if path is None or expected_file_id is None:
            return []
        failures: list[str] = []
        writer = self._pending_writer
        guardian = self._pending_guardian
        if guardian is not None and writer is not None:
            try:
                writer.close()
            except OSError as error:
                failures.append(f"close pending writer {path.name}: {error}")
            else:
                self._pending_writer = None
                writer = None
        if not self._pending_deleted:
            try:
                self._unlink_exact(path, expected_file_id)
            except VerificationError as error:
                failures.append(str(error))
                return failures
            proof_handle = self._pending_guardian or self._pending_writer
            if proof_handle is None:
                failures.append(
                    f"pending scratch artifact {path.name} lost its exact cleanup handle"
                )
                return failures
            try:
                remaining_links = os.fstat(proof_handle.fileno()).st_nlink
            except (OSError, ValueError) as error:
                failures.append(f"recheck pending {path.name}: {error}")
                return failures
            if remaining_links != 0:
                failures.append(
                    f"pending scratch artifact {path.name} retained {remaining_links} "
                    "untracked hard link(s) after cleanup"
                )
                return failures
            self._pending_deleted = True
        for attribute, pending_handle in (
            ("_pending_writer", writer),
            ("_pending_guardian", guardian),
        ):
            if pending_handle is None:
                continue
            try:
                pending_handle.close()
            except OSError as error:
                failures.append(f"close pending {path.name}: {error}")
            else:
                setattr(self, attribute, None)
        if self._pending_writer is None and self._pending_guardian is None:
            self._owned_files.pop(path, None)
            self._states.pop(path, None)
            self._writers.pop(path, None)
            self._guardians.pop(path, None)
            self._pending_path = None
            self._pending_file_id = None
            self._pending_deleted = False
        return failures

    def path(self, name: str) -> Path:
        return self._path(name)

    def seal(
        self,
        value: str | Path,
        handle: _TrackedWriter,
    ) -> _HeldFileSnapshot:
        self._verify_directory()
        path = self._path(value)
        expected = self._owned_files.get(path)
        guardian = self._guardians.get(path)
        if (
            expected is None
            or guardian is None
            or self._writers.get(path) is not handle
            or self._states.get(path) != "WRITING"
        ):
            raise VerificationError(
                f"scratch artifact {path.name} cannot seal without exact WRITING ownership"
            )
        try:
            handle.flush()
            os.fsync(handle.fileno())
            writing_snapshot = _held_file_snapshot(
                guardian,
                path,
                f"scratch artifact {path.name} WRITING snapshot",
            )
            if (
                writing_snapshot.file_id != expected
                or writing_snapshot.link_count != 1
                or writing_snapshot.byte_length != handle.expected_bytes
                or writing_snapshot.sha256 != handle.expected_sha256
            ):
                raise VerificationError(
                    f"scratch artifact {path.name} changed before its held WRITING readback"
                )
            handle.close()
            self._writers.pop(path, None)
            sealed_handle = _open_held_reader(
                path,
                f"scratch artifact {path.name} SEALED pin",
                deny_write=True,
                deny_delete=False,
            )
            try:
                sealed_snapshot = _held_file_snapshot(
                    sealed_handle,
                    path,
                    f"scratch artifact {path.name} SEALED snapshot",
                )
                if writing_snapshot != sealed_snapshot:
                    raise VerificationError(
                        f"scratch artifact {path.name} drifted while transitioning "
                        "WRITING to SEALED"
                    )
                self._sealed_handles[path] = sealed_handle
                self._sealed_snapshots[path] = sealed_snapshot
                self._states[path] = "SEALED"
                sealed_handle = None
            finally:
                if sealed_handle is not None:
                    sealed_handle.close()
        except VerificationError:
            raise
        except (OSError, ValueError) as error:
            raise VerificationError(
                f"scratch artifact {path.name} sealing failed: {error}"
            ) from error
        return self._sealed_snapshots[path]

    def verify_handle(self, value: str | Path, handle: BinaryIO) -> None:
        self._verify_directory()
        path = self._path(value)
        expected = self._owned_files.get(path)
        sealed_handle = self._sealed_handles.get(path)
        sealed_snapshot = self._sealed_snapshots.get(path)
        if (
            expected is None
            or sealed_handle is None
            or sealed_snapshot is None
            or self._states.get(path) != "SEALED"
        ):
            raise VerificationError(
                f"scratch artifact SEALED ownership is missing for {path.name}"
            )
        try:
            handle_status = os.fstat(handle.fileno())
            path_status = os.stat(path, follow_symlinks=False)
        except OSError as error:
            raise VerificationError(
                f"scratch artifact identity check failed for {path.name}: {error}"
            ) from error
        if (
                not self._valid_file(handle_status)
                or not self._valid_file(path_status)
                or not self._same_file_id(handle_status, expected)
                or not self._same_file_id(path_status, expected)
        ):
            raise VerificationError(
                f"scratch artifact {path.name} identity changed or became a reparse point"
            )
        current_snapshot = _held_file_snapshot(
            sealed_handle,
            path,
            f"scratch artifact {path.name} SEALED recheck",
        )
        if current_snapshot != sealed_snapshot:
            raise VerificationError(
                f"scratch artifact {path.name} changed after SEALED"
            )

    def open_owned(self, value: str | Path) -> BinaryIO:
        self._verify_directory()
        path = self._path(value)
        expected = self._owned_files.get(path)
        if expected is None or self._states.get(path) != "SEALED":
            raise VerificationError(
                f"scratch artifact SEALED ownership is missing for {path.name}"
            )
        try:
            path_status = os.stat(path, follow_symlinks=False)
            if not self._valid_file(path_status) or not self._same_file_id(
                path_status,
                expected,
            ):
                raise VerificationError(
                    f"scratch artifact {path.name} identity changed or became a reparse point"
                )
            handle = path.open("rb")
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(
                f"scratch artifact open failed for {path.name}: {error}"
            ) from error
        try:
            self.verify_handle(path, handle)
        except BaseException as primary:
            try:
                handle.close()
            except OSError as close_error:
                raise VerificationError(
                    f"scratch artifact pin close failed for {path.name}: {close_error}"
                ) from primary
            raise
        return handle

    def terminal_snapshots(
        self,
        paths: list[Path],
    ) -> dict[Path, _HeldFileSnapshot]:
        snapshots: dict[Path, _HeldFileSnapshot] = {}
        for path in sorted(paths, key=lambda item: item.name):
            owned_path = self._path(path)
            handle = self._sealed_handles.get(owned_path)
            expected = self._sealed_snapshots.get(owned_path)
            if handle is None or expected is None:
                raise VerificationError(
                    f"scratch terminal snapshot is missing SEALED {owned_path.name}"
                )
            frozen = _open_held_reader(
                owned_path,
                f"scratch artifact {owned_path.name} terminal pin",
                deny_write=True,
                deny_delete=True,
            )
            try:
                frozen_snapshot = _held_file_snapshot(
                    frozen,
                    owned_path,
                    f"scratch artifact {owned_path.name} terminal pin snapshot",
                )
                if frozen_snapshot != expected:
                    raise VerificationError(
                        f"scratch artifact {owned_path.name} drifted while entering "
                        "terminal evidence"
                    )
                self._sealed_handles[owned_path] = frozen
                frozen = None
            finally:
                if frozen is not None:
                    frozen.close()
            handle.close()
            handle = self._sealed_handles[owned_path]
            actual = _held_file_snapshot(
                handle,
                owned_path,
                f"scratch artifact {owned_path.name} terminal snapshot",
            )
            if actual != expected:
                raise VerificationError(
                    f"scratch artifact {owned_path.name} drifted before terminal snapshot"
                )
            snapshots[owned_path] = actual
        self._verify_directory()
        return snapshots

    def terminalize(
        self,
        expected: dict[Path, _HeldFileSnapshot],
    ) -> None:
        actual = self.terminal_snapshots(list(expected))
        if actual != expected:
            raise VerificationError(
                "scratch terminal evidence does not reproduce the prebuilt result"
            )

    def cleanup(self) -> None:
        failures: list[str] = []
        failures.extend(self._cleanup_pending_creation())
        owned_guardian_ids = {
            id(handle)
            for path, handle in self._guardians.items()
            if path in self._owned_files
        }
        closed_orphan_ids: set[int] = set()
        for path, guardian in list(self._guardians.items()):
            guardian_id = id(guardian)
            if guardian_id in owned_guardian_ids or guardian_id in closed_orphan_ids:
                continue
            try:
                guardian.close()
            except OSError as error:
                failures.append(f"close released guardian {path.name}: {error}")
                continue
            closed_orphan_ids.add(guardian_id)
            for guardian_path, candidate in list(self._guardians.items()):
                if candidate is guardian:
                    self._guardians.pop(guardian_path, None)
        pending_path = self._pending_path
        expected_directory_id = self._directory_id
        directory_handle = self._directory_handle
        if expected_directory_id is None or directory_handle is None:
            if self._owned_files:
                failures.append("scratch directory ownership disappeared before file cleanup")
            if expected_directory_id is None and directory_handle is not None:
                try:
                    directory_handle.close()
                except OSError as error:
                    failures.append(
                        f"close deleted scratch directory held handle: {error}"
                    )
                else:
                    self._directory_handle = None
            if failures:
                raise VerificationError(
                    "verification cleanup failed: " + "; ".join(failures)
                )
            return
        try:
            held_directory_status = os.fstat(directory_handle.fileno())
            directory_status = os.stat(self.directory, follow_symlinks=False)
        except FileNotFoundError as error:
            raise VerificationError(
                "verification cleanup failed: scratch directory is missing or moved; "
                f"unreleased FileId {expected_directory_id[0]}:{expected_directory_id[1]}"
            ) from error
        except OSError as error:
            raise VerificationError(
                f"verification cleanup failed: inspect scratch directory: {error}"
            ) from error
        if (
            not self._valid_directory(held_directory_status)
            or not self._valid_directory(directory_status)
            or not self._same_file_id(held_directory_status, expected_directory_id)
            or not self._same_file_id(directory_status, expected_directory_id)
        ):
            raise VerificationError(
                "verification cleanup failed: scratch directory identity changed; "
                "preserved replacement"
            )

        for path, writer in list(self._writers.items()):
            try:
                writer.close()
            except OSError as error:
                failures.append(f"close WRITING {path.name}: {error}")
            else:
                self._writers.pop(path, None)
        for path, handle in list(self._sealed_handles.items()):
            try:
                handle.close()
            except OSError as error:
                failures.append(f"close SEALED {path.name}: {error}")
            else:
                self._sealed_handles.pop(path, None)

        for path, expected_file_id in sorted(
            [
                item
                for item in self._owned_files.items()
                if item[0] != pending_path
            ],
            key=lambda item: item[0].name,
        ):
            guardian = self._guardians.get(path)
            if guardian is None:
                failures.append(
                    f"scratch artifact {path.name} lost its exact cleanup guardian"
                )
                continue
            try:
                self._unlink_exact(path, expected_file_id)
            except VerificationError as error:
                failures.append(str(error))
            else:
                try:
                    remaining_links = os.fstat(guardian.fileno()).st_nlink
                except (OSError, ValueError) as error:
                    failures.append(f"recheck guardian {path.name}: {error}")
                    continue
                if remaining_links != 0:
                    failures.append(
                        f"scratch artifact {path.name} retained {remaining_links} "
                        "untracked hard link(s) after cleanup"
                    )
                    continue
                self._owned_files.pop(path, None)
                self._sealed_snapshots.pop(path, None)
                self._states.pop(path, None)
                try:
                    guardian.close()
                except OSError as error:
                    failures.append(f"close deleted guardian {path.name}: {error}")
                else:
                    self._guardians.pop(path, None)
        if not self._owned_files:
            try:
                _delete_exact_owned_path(
                    self.directory,
                    expected_directory_id,
                    directory=True,
                    label="scratch directory",
                )
            except VerificationError as error:
                failures.append(str(error))
            else:
                self._directory_id = None
                try:
                    directory_handle.close()
                except OSError as error:
                    failures.append(
                        f"close scratch directory held handle: {error}"
                    )
                else:
                    self._directory_handle = None
        if failures:
            raise VerificationError("verification cleanup failed: " + "; ".join(failures))


def _tracked_handle(
    scratch: _VerifierScratch,
    name: str,
    handles: list[BinaryIO],
) -> BinaryIO:
    handle = scratch.create(name)
    try:
        handles.append(handle)
    except BaseException:
        handle.close()
        raise
    return handle


def _close_tracked(handles: list[BinaryIO]) -> None:
    failures: list[str] = []
    while handles:
        handle = handles.pop()
        try:
            handle.close()
        except OSError as error:
            failures.append(f"close {getattr(handle, 'name', '<unknown>')}: {error}")
    if failures:
        raise VerificationError("verification cleanup failed: " + "; ".join(failures))


class _ExternalDecisionSort:
    def __init__(self, scratch: _VerifierScratch, chunk_records: int, max_runs: int) -> None:
        self.scratch = scratch
        self.chunk_records = chunk_records
        self.max_runs = max_runs
        self.pending: list[bytes] = []
        self.runs: list[Path] = []

    @staticmethod
    def _key(record: bytes) -> tuple[int, int]:
        return record[0], struct.unpack_from("<Q", record, 1)[0]

    def add(self, record: bytes) -> None:
        if len(record) != _DECISION_RECORD_BYTES:
            raise VerificationError("internal decision record has the wrong width")
        self.pending.append(record)
        if len(self.pending) == self.chunk_records:
            self._flush()

    def _flush(self) -> None:
        if not self.pending:
            return
        if len(self.runs) >= self.max_runs:
            raise VerificationError(
                f"broad decisions exceed the external-sort run ceiling {self.max_runs}"
            )
        self.pending.sort(key=self._key)
        path = self.scratch.path(f"run-{len(self.runs):06d}.bin")
        try:
            output = self.scratch.create(path.name)
            for record in self.pending:
                output.write(record)
            self.scratch.seal(path, output)
        except OSError as error:
            raise VerificationError(f"external-sort run write failed: {error}") from error
        self.pending.clear()
        self.runs.append(path)

    def finish(self) -> None:
        self._flush()

    def _records(self, path: Path) -> Iterator[bytes]:
        try:
            with self.scratch.open_owned(path) as source:
                while True:
                    record = source.read(_DECISION_RECORD_BYTES)
                    if not record:
                        break
                    if len(record) != _DECISION_RECORD_BYTES:
                        raise VerificationError("external-sort run is truncated")
                    yield record
                self.scratch.verify_handle(path, source)
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(f"external-sort run read failed: {error}") from error

    def merged(self) -> Iterator[bytes]:
        iterators = [self._records(path) for path in self.runs]
        heap: list[tuple[tuple[int, int], int, bytes]] = []
        for index, iterator in enumerate(iterators):
            try:
                record = next(iterator)
            except StopIteration:
                continue
            heapq.heappush(heap, (self._key(record), index, record))
        while heap:
            _, index, record = heapq.heappop(heap)
            yield record
            try:
                following = next(iterators[index])
            except StopIteration:
                continue
            heapq.heappush(heap, (self._key(following), index, following))


def _strict_object_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise VerificationError(f"production summary contains duplicate JSON key {key!r}")
        result[key] = value
    return result


def _production_summary(
    pinned: _PinnedProduction,
    limits: VerificationLimits,
) -> dict[str, object]:
    path = pinned.stage / "selection-summary.json"
    try:
        source = pinned.handle_for_read("selection-summary.json")
        status = os.fstat(source.fileno())
        size = status.st_size
        if size > limits.max_production_summary_bytes:
            raise VerificationError("production summary exceeds its byte ceiling")
        raw = source.read(size + 1)
        pinned.verify_handle("selection-summary.json")
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"production summary is unavailable: {error}") from error
    if len(raw) != size or not raw.endswith(b"\n") or b"\r" in raw:
        raise VerificationError("production summary bytes are not canonical")
    try:
        document = json.loads(raw, object_pairs_hook=_strict_object_pairs)
    except VerificationError:
        raise
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise VerificationError(f"production summary is not strict JSON: {error}") from error
    if type(document) is not dict or _json_bytes(document) != raw:
        raise VerificationError("production summary JSON is not strictly canonical")
    if document.get("schema") != _PRODUCTION_SUMMARY_SCHEMA:
        raise VerificationError("production summary schema is not exact")
    return document


def _file_fact(
    path: Path,
    *,
    max_bytes: int | None = None,
    ceiling_label: str = "artifact byte",
    owner: _VerifierScratch | None = None,
    pinned: _PinnedProduction | None = None,
) -> tuple[int, str]:
    digest = hashlib.sha256()
    count = 0
    if owner is not None:
        try:
            with owner.open_owned(path) as source:
                status = os.fstat(source.fileno())
                if max_bytes is not None and status.st_size > max_bytes:
                    raise VerificationError(
                        f"{path.name} exceeds the {ceiling_label} {max_bytes}"
                    )
                while True:
                    chunk = source.read(1024 * 1024)
                    if not chunk:
                        break
                    count += len(chunk)
                    if max_bytes is not None and count > max_bytes:
                        raise VerificationError(
                            f"{path.name} exceeds the {ceiling_label} {max_bytes}"
                        )
                    digest.update(chunk)
                after = os.fstat(source.fileno())
                owner.verify_handle(path, source)
                if count != status.st_size or _file_id(after) != _file_id(status):
                    raise VerificationError(
                        f"{path.name} changed during owned readback"
                    )
        except VerificationError:
            raise
        except (OSError, ValueError) as error:
            raise VerificationError(
                f"owned artifact read failed for {path.name}: {error}"
            ) from error
        return count, digest.hexdigest()
    if pinned is not None:
        try:
            source = pinned.handle_for_read(path.name)
            status = os.fstat(source.fileno())
            if max_bytes is not None and status.st_size > max_bytes:
                raise VerificationError(
                    f"{path.name} exceeds the {ceiling_label} {max_bytes}"
                )
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                count += len(chunk)
                if max_bytes is not None and count > max_bytes:
                    raise VerificationError(
                        f"{path.name} exceeds the {ceiling_label} {max_bytes}"
                    )
                digest.update(chunk)
            pinned.verify_handle(path.name)
            if count != status.st_size:
                raise VerificationError(
                    f"production artifact {path.name} changed during pinned readback"
                )
        except VerificationError:
            raise
        except (OSError, ValueError) as error:
            raise VerificationError(
                f"pinned production artifact read failed for {path.name}: {error}"
            ) from error
        return count, digest.hexdigest()
    try:
        if path.is_symlink():
            raise VerificationError(f"{path.name} must be a regular file")
        status = path.stat()
        if not stat_module.S_ISREG(status.st_mode):
            raise VerificationError(f"{path.name} must be a regular file")
        if max_bytes is not None and status.st_size > max_bytes:
            raise VerificationError(
                f"{path.name} exceeds the {ceiling_label} {max_bytes}"
            )
        with path.open("rb") as source:
            while True:
                chunk = source.read(1024 * 1024)
                if not chunk:
                    break
                count += len(chunk)
                if max_bytes is not None and count > max_bytes:
                    raise VerificationError(
                        f"{path.name} exceeds the {ceiling_label} {max_bytes}"
                    )
                digest.update(chunk)
        if count != status.st_size:
            raise VerificationError(f"{path.name} changed size during readback")
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"artifact read failed for {path.name}: {error}") from error
    return count, digest.hexdigest()


def _compare_files(
    expected: _PinnedProduction,
    expected_name: str,
    actual: Path,
    label: str,
    *,
    owner: _VerifierScratch | None = None,
) -> None:
    offset = 0
    if owner is not None:
        try:
            left = expected.handle_for_read(expected_name)
            with owner.open_owned(actual) as right:
                while True:
                    first = left.read(1024 * 1024)
                    second = right.read(1024 * 1024)
                    if first != second:
                        raise VerificationError(
                            f"{label} comparison found an addition, omission, or "
                            f"changed selection at byte {offset}"
                        )
                    if not first:
                        break
                    offset += len(first)
                expected.verify_handle(expected_name)
                owner.verify_handle(actual, right)
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(f"{label} comparison failed: {error}") from error
        return
    try:
        left = expected.handle_for_read(expected_name)
        with actual.open("rb") as right:
            while True:
                first = left.read(1024 * 1024)
                second = right.read(1024 * 1024)
                if first != second:
                    raise VerificationError(
                        f"{label} comparison found an addition, omission, or changed selection at byte {offset}"
                    )
                if not first:
                    expected.verify_handle(expected_name)
                    return
                offset += len(first)
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"{label} comparison failed: {error}") from error


class _XmlSax:
    def __init__(
        self,
        limits: VerificationLimits,
        stats: _BroadStats,
        emit: Callable[[_SourceObject], None],
    ) -> None:
        self.limits = limits
        self.stats = stats
        self.emit = emit
        self.stack: list[str] = []
        self.kind: int | None = None
        self.object_id = 0
        self.version = 0
        self.timestamp = 0
        self.tags: dict[str, str] = {}
        self.refs: list[int] = []
        self.members: list[_Member] = []
        self.in_tags = False
        self.note_utf8_bytes = 0

    def parser(self):
        parser = expat.ParserCreate("utf-8")
        parser.StartElementHandler = self.start
        parser.EndElementHandler = self.end
        parser.CharacterDataHandler = self.characters
        parser.ProcessingInstructionHandler = self.processing_instruction
        parser.StartDoctypeDeclHandler = self.forbidden_declaration
        parser.EntityDeclHandler = self.forbidden_declaration
        parser.UnparsedEntityDeclHandler = self.forbidden_declaration
        parser.ExternalEntityRefHandler = self.external_entity
        parser.SkippedEntityHandler = self.forbidden_declaration
        parser.SetParamEntityParsing(expat.XML_PARAM_ENTITY_PARSING_NEVER)
        return parser

    def forbidden_declaration(self, *args: object) -> None:
        del args
        raise VerificationError("broad XML DTD and entity declarations are forbidden")

    def external_entity(self, *args: object) -> int:
        del args
        raise VerificationError("broad XML external entities are forbidden")

    def processing_instruction(self, target: str, data: str) -> None:
        del target, data
        raise VerificationError("broad XML processing instructions are forbidden")

    def _object_attributes(self, name: str, attributes: dict[str, str]) -> None:
        if self.stats.objects >= self.limits.max_objects:
            raise VerificationError(
                f"broad input exceeds the object ceiling {self.limits.max_objects}"
            )
        allowed = {"id", "version", "timestamp", "visible", "changeset", "uid", "user"}
        if set(attributes).difference(allowed):
            raise VerificationError(f"broad XML {name} has unexpected attributes")
        if attributes.get("visible", "true") != "true":
            raise VerificationError(f"broad XML {name} is not a visible current object")
        if "changeset" in attributes:
            _nonnegative(attributes["changeset"], _MAX_SIGNED_63, f"broad XML {name} changeset")
        if "uid" in attributes:
            _nonnegative(attributes["uid"], _MAX_SIGNED_63, f"broad XML {name} uid")
        if "user" in attributes:
            _bounded_text(attributes["user"], f"broad XML {name} user", self.limits)
        self.kind = 1 if name == "way" else 2
        self.object_id = _positive(attributes.get("id"), _MAX_SIGNED_63, f"broad XML {name} ID")
        self.version = _positive(attributes.get("version"), _MAX_U32, f"broad XML {name} version")
        self.timestamp = _epoch(attributes.get("timestamp"), f"broad XML {name}")
        self.tags = {}
        self.refs = []
        self.members = []
        self.in_tags = False

    def start(self, name: str, attributes: dict[str, str]) -> None:
        parent = self.stack[-1] if self.stack else None
        if parent is None:
            if name != "osm" or attributes.get("version") != "0.6":
                raise VerificationError("broad XML root must be osm version 0.6")
            if set(attributes).difference({"version", "generator"}):
                raise VerificationError("broad XML root has unexpected attributes")
            _bounded_text(attributes.get("generator", ""), "broad XML generator", self.limits)
        elif parent == "osm":
            if name in {"bounds", "meta", "note"}:
                if self.kind is not None:
                    raise VerificationError("broad XML metadata overlaps an object")
                if name == "bounds":
                    required = {"minlat", "minlon", "maxlat", "maxlon"}
                    if not required.issubset(attributes) or set(attributes).difference(
                        required | {"origin"}
                    ):
                        raise VerificationError("broad XML bounds metadata is malformed")
                    for key, value in attributes.items():
                        _bounded_text(value, f"broad XML bounds {key}", self.limits)
                elif name == "meta":
                    if not attributes or set(attributes).difference({"osm_base", "areas"}):
                        raise VerificationError("broad XML meta metadata is malformed")
                    for key, value in attributes.items():
                        _bounded_text(value, f"broad XML meta {key}", self.limits)
                elif attributes:
                    raise VerificationError("broad XML note metadata has attributes")
                self.note_utf8_bytes = 0
            elif name not in {"way", "relation"} or self.kind is not None:
                raise VerificationError(f"broad XML contains unsupported object {name!r}")
            else:
                self._object_attributes(name, attributes)
        elif parent == "way" and name == "nd":
            if self.in_tags or set(attributes) != {"ref"}:
                raise VerificationError("broad XML way has malformed or unordered node refs")
            if len(self.refs) >= self.limits.max_references_per_object:
                raise VerificationError("broad XML way exceeds the per-object reference ceiling")
            if self.stats.references + len(self.refs) >= self.limits.max_total_references:
                raise VerificationError(
                    f"broad input exceeds the reference ceiling {self.limits.max_total_references}"
                )
            self.refs.append(_positive(attributes["ref"], _MAX_SIGNED_63, "broad XML node ref"))
        elif parent == "relation" and name == "member":
            if self.in_tags or set(attributes) != {"type", "ref", "role"}:
                raise VerificationError("broad XML relation has malformed or unordered members")
            if len(self.members) >= self.limits.max_references_per_object:
                raise VerificationError("broad XML relation exceeds the per-object reference ceiling")
            if self.stats.references + len(self.members) >= self.limits.max_total_references:
                raise VerificationError(
                    f"broad input exceeds the reference ceiling {self.limits.max_total_references}"
                )
            member_kind = attributes["type"]
            if member_kind not in _RELATION_MEMBER_KIND:
                raise VerificationError("broad XML relation has an unsupported member kind")
            self.members.append(
                _Member(
                    kind=_RELATION_MEMBER_KIND[member_kind],
                    ref=_positive(attributes["ref"], _MAX_SIGNED_63, "broad XML member ref"),
                    role=_bounded_text(attributes["role"], "broad XML member role", self.limits),
                    ordinal=len(self.members),
                )
            )
        elif parent in {"way", "relation"} and name == "tag":
            if set(attributes) != {"k", "v"}:
                raise VerificationError("broad XML object has a malformed tag")
            self.in_tags = True
            if len(self.tags) >= self.limits.max_tags_per_object:
                raise VerificationError("broad XML object exceeds the per-object tag ceiling")
            if self.stats.tags + len(self.tags) >= self.limits.max_total_tags:
                raise VerificationError(
                    f"broad input exceeds the tag ceiling {self.limits.max_total_tags}"
                )
            key = _bounded_text(attributes["k"], "broad XML tag key", self.limits)
            value = _bounded_text(attributes["v"], "broad XML tag value", self.limits)
            if not key:
                raise VerificationError("broad XML object contains an empty tag key")
            if key in self.tags:
                raise VerificationError(f"broad XML object contains duplicate tag {key!r}")
            self.tags[key] = value
        else:
            raise VerificationError(f"broad XML contains unexpected nested element {name!r}")
        self.stack.append(name)

    def end(self, name: str) -> None:
        if not self.stack or self.stack[-1] != name:
            raise VerificationError("broad XML elements closed out of order")
        if name in {"way", "relation"}:
            expected_kind = 1 if name == "way" else 2
            if self.kind != expected_kind:
                raise VerificationError("broad XML object state is inconsistent")
            tags = tuple(sorted(self.tags.items(), key=lambda item: item[0].encode("utf-8")))
            self.emit(
                _SourceObject(
                    kind=expected_kind,
                    object_id=self.object_id,
                    version=self.version,
                    epoch_seconds=self.timestamp,
                    tags=tags,
                    refs=tuple(self.refs),
                    members=tuple(self.members),
                )
            )
            self.kind = None
            self.tags = {}
            self.refs = []
            self.members = []
            self.in_tags = False
        self.stack.pop()

    def characters(self, data: str) -> None:
        if self.stack and self.stack[-1] == "note":
            try:
                self.note_utf8_bytes += len(data.encode("utf-8"))
            except UnicodeEncodeError as error:
                raise VerificationError("broad XML note is not valid Unicode") from error
            if self.note_utf8_bytes > self.limits.max_text_utf8_bytes:
                raise VerificationError("broad XML note exceeds the text UTF-8 byte ceiling")
        elif data.strip():
            raise VerificationError("broad XML contains unexpected character data")


def _read_chunks(
    stream: object,
    limits: VerificationLimits,
    stats: _BroadStats,
    label: str = "broad input",
):
    read = getattr(stream, "read", None)
    if not callable(read):
        raise VerificationError(f"{label} must be a readable bytes stream")
    while True:
        try:
            chunk = read(limits.read_chunk_bytes)
        except VerificationError:
            raise
        except Exception as error:
            raise VerificationError(f"{label} read failed: {error}") from error
        if type(chunk) is not bytes:
            raise VerificationError(f"{label} reads must return immutable bytes")
        if len(chunk) > limits.read_chunk_bytes:
            raise VerificationError(f"{label} returned more bytes than requested")
        if not chunk:
            return
        stats.bytes += len(chunk)
        if stats.bytes > limits.max_input_bytes:
            raise VerificationError(
                f"{label} exceeds the input byte ceiling {limits.max_input_bytes}"
            )
        stats.digest.update(chunk)
        yield chunk


def _parse_opl_stream(
    stream: object,
    limits: VerificationLimits,
    stats: _BroadStats,
    emit: Callable[[_SourceObject], None],
    *,
    label: str = "broad input",
) -> None:
    pending = bytearray()
    line_number = 0
    for chunk in _read_chunks(stream, limits, stats, label):
        cursor = 0
        while cursor < len(chunk):
            newline = chunk.find(b"\n", cursor)
            end = len(chunk) if newline < 0 else newline
            addition = end - cursor
            if len(pending) + addition > limits.max_line_bytes:
                raise VerificationError(
                    f"{label} OPL exceeds the line byte ceiling {limits.max_line_bytes}"
                )
            pending.extend(chunk[cursor:end])
            if newline < 0:
                break
            line_number += 1
            if stats.objects >= limits.max_objects:
                raise VerificationError(
                    f"{label} exceeds the object ceiling {limits.max_objects}"
                )
            emit(
                _parse_opl_record(
                    bytes(pending),
                    line_number,
                    limits,
                    tag_budget=limits.max_total_tags - stats.tags,
                    reference_budget=limits.max_total_references - stats.references,
                )
            )
            pending.clear()
            cursor = newline + 1
    if pending:
        raise VerificationError(f"{label} OPL must end with a final LF")


def _parse_xml_stream(
    stream: object,
    limits: VerificationLimits,
    stats: _BroadStats,
    emit: Callable[[_SourceObject], None],
) -> None:
    handler = _XmlSax(limits, stats, emit)
    parser = handler.parser()
    try:
        for chunk in _read_chunks(stream, limits, stats):
            parser.Parse(chunk, False)
        parser.Parse(b"", True)
    except VerificationError:
        raise
    except expat.ExpatError as error:
        raise VerificationError(f"broad XML is malformed: {error}") from error
    if handler.stack or handler.kind is not None:
        raise VerificationError("broad XML ended with incomplete object state")


def _validate_directory(value: str | Path, label: str) -> Path:
    try:
        path = Path(value)
    except TypeError as error:
        raise VerificationError(f"{label} path is invalid") from error
    if not path.is_dir() or path.is_symlink():
        raise VerificationError(f"{label} must be an existing real directory")
    return path


def _summary_path_value(document: dict[str, object], *keys: str) -> object:
    value: object = document
    for key in keys:
        if type(value) is not dict or key not in value:
            raise VerificationError(f"production summary is missing {'.'.join(keys)}")
        value = value[key]
    return value


def _exact_mapping(value: object, keys: set[str], label: str) -> dict[str, object]:
    if type(value) is not dict or set(value) != keys:
        raise VerificationError(f"{label} keys are not exact")
    return value


def _bounded_count(value: object, maximum: int, label: str) -> int:
    if type(value) is not int or value < 0 or value > maximum:
        raise VerificationError(f"{label} is outside its count ceiling")
    return value


class _BinaryCursor:
    def __init__(
        self,
        raw: bytes | memoryview,
        label: str,
        limits: VerificationLimits,
    ) -> None:
        self.raw = memoryview(raw)
        self.label = label
        self.limits = limits
        self.offset = 0

    def take(self, count: int) -> memoryview:
        end = self.offset + count
        if count < 0 or end > len(self.raw):
            raise VerificationError(f"{self.label} canonical payload is truncated")
        value = self.raw[self.offset:end]
        self.offset = end
        return value

    def unpack(self, pattern: str) -> tuple[object, ...]:
        size = struct.calcsize(pattern)
        try:
            value = struct.unpack(pattern, self.take(size))
        except struct.error as error:
            raise VerificationError(f"{self.label} canonical payload is malformed") from error
        return value

    def text(self) -> tuple[str, bytes]:
        (size,) = self.unpack("<I")
        assert type(size) is int
        if size > self.limits.max_text_utf8_bytes:
            raise VerificationError(f"{self.label} text exceeds the UTF-8 byte ceiling")
        raw = self.take(size)
        try:
            raw_bytes = raw.tobytes()
            value = raw_bytes.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise VerificationError(f"{self.label} text is not strict UTF-8") from error
        if "\x00" in value:
            raise VerificationError(f"{self.label} text contains a forbidden NUL")
        return value, raw_bytes


def _validate_bucket_frame(
    body: bytes | memoryview,
    bucket: int,
    label: str,
    limits: VerificationLimits,
) -> bytes:
    if len(body) < 32 + 25:
        raise VerificationError(f"{label} frame is too short")
    body_view = memoryview(body)
    payload = body_view[:-32]
    stored_digest = body_view[-32:]
    digest_state = hashlib.sha256()
    digest_state.update(_ROOT_DOMAIN)
    digest_state.update(payload)
    digest = digest_state.digest()
    if stored_digest.tobytes() != digest:
        raise VerificationError(f"{label} frame root digest is invalid")
    cursor = _BinaryCursor(payload, label, limits)
    kind, object_id, version = cursor.unpack("<BQI")
    cursor.unpack("<q")
    if kind not in {1, 2}:
        raise VerificationError(f"{label} frame has an unsupported object kind")
    if type(object_id) is not int or object_id <= 0 or object_id > _MAX_SIGNED_63:
        raise VerificationError(f"{label} frame has an invalid object ID")
    if type(version) is not int or version <= 0 or version > _MAX_U32:
        raise VerificationError(f"{label} frame has an invalid version")
    expected_bucket = hashlib.sha256(
        _BUCKET_DOMAIN + bytes((kind,)) + struct.pack("<Q", object_id)
    ).digest()[0]
    if expected_bucket != bucket:
        raise VerificationError(f"{label} frame is assigned to the wrong bucket")
    (tag_count,) = cursor.unpack("<I")
    assert type(tag_count) is int
    if tag_count > limits.max_tags_per_object or tag_count > (len(payload) - cursor.offset) // 8:
        raise VerificationError(f"{label} frame tag count exceeds its structural ceiling")
    previous_key: bytes | None = None
    for _ in range(tag_count):
        key, key_raw = cursor.text()
        cursor.text()
        if not key:
            raise VerificationError(f"{label} frame contains an empty tag key")
        if previous_key is not None and key_raw <= previous_key:
            raise VerificationError(f"{label} frame tags are duplicate or unordered")
        previous_key = key_raw
    (reference_count,) = cursor.unpack("<I")
    assert type(reference_count) is int
    if reference_count > limits.max_references_per_object:
        raise VerificationError(f"{label} frame reference count exceeds its ceiling")
    if kind == 1:
        if reference_count > (len(payload) - cursor.offset) // 8:
            raise VerificationError(f"{label} frame node references exceed remaining bytes")
        for _ in range(reference_count):
            (reference,) = cursor.unpack("<Q")
            if type(reference) is not int or reference <= 0 or reference > _MAX_SIGNED_63:
                raise VerificationError(f"{label} frame contains an invalid node reference")
    else:
        if reference_count > (len(payload) - cursor.offset) // 17:
            raise VerificationError(f"{label} frame members exceed remaining bytes")
        for ordinal in range(reference_count):
            stored_ordinal, member_kind, reference = cursor.unpack("<IBQ")
            if stored_ordinal != ordinal or member_kind not in {0, 1, 2}:
                raise VerificationError(f"{label} frame contains a malformed member")
            if type(reference) is not int or reference <= 0 or reference > _MAX_SIGNED_63:
                raise VerificationError(f"{label} frame contains an invalid member reference")
            cursor.text()
    if cursor.offset != len(payload):
        raise VerificationError(f"{label} frame has trailing canonical payload bytes")
    return struct.pack("<BQI32s", kind, object_id, version, digest)


def _validate_bucket_file(
    path: Path,
    bucket: int,
    expected_records: int,
    limits: VerificationLimits,
    pinned: _PinnedProduction,
) -> int:
    records = 0
    previous_key = (0, 0)
    try:
        source = pinned.handle_for_read(path.name)
        while True:
            header = source.read(4)
            if not header:
                break
            if len(header) != 4:
                raise VerificationError(f"production bucket {bucket:03d} has a truncated frame header")
            (body_bytes,) = struct.unpack("<I", header)
            if body_bytes > limits.max_production_frame_bytes:
                raise VerificationError(
                    f"production bucket {bucket:03d} frame exceeds the production frame byte ceiling"
                )
            body = source.read(body_bytes)
            if len(body) != body_bytes:
                raise VerificationError(f"production bucket {bucket:03d} has a truncated frame")
            tuple_record = _validate_bucket_frame(
                body,
                bucket,
                f"production bucket {bucket:03d} record {records}",
                limits,
            )
            key = (tuple_record[0], struct.unpack_from("<Q", tuple_record, 1)[0])
            if key <= previous_key:
                raise VerificationError(
                    f"production bucket {bucket:03d} objects are duplicate or unordered"
                )
            previous_key = key
            records += 1
            if records > expected_records:
                raise VerificationError(f"production bucket {bucket:03d} exceeds inventory records")
        pinned.verify_handle(path.name)
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"production bucket {bucket:03d} read failed: {error}") from error
    if records != expected_records:
        raise VerificationError(f"production bucket {bucket:03d} record count contradicts inventory")
    return records


def _validate_production_inventory(stage: Path, *, final: bool = False) -> None:
    try:
        stage_names = {path.name for path in stage.iterdir()}
    except OSError as error:
        label = "final production stage" if final else "production stage"
        raise VerificationError(f"{label} inventory failed: {error}") from error
    if stage_names != _EXPECTED_PRODUCTION_NAMES:
        if final:
            raise VerificationError("final production stage inventory changed before success")
        raise VerificationError("production stage artifact file names are not exact")


def _file_id(status: os.stat_result) -> tuple[int, int]:
    return status.st_dev, status.st_ino


def _status_signature(status: os.stat_result) -> tuple[int, int, int, int]:
    return status.st_nlink, status.st_size, status.st_mtime_ns, status.st_ctime_ns


class _PinnedProduction:
    def __init__(self, stage: Path) -> None:
        self.stage = stage
        self.handles: dict[str, BinaryIO] = {}
        self.file_ids: dict[str, tuple[int, int]] = {}
        self.signatures: dict[str, tuple[int, int, int, int]] = {}
        self.snapshots: dict[str, _HeldFileSnapshot] = {}
        try:
            for name in sorted(_EXPECTED_PRODUCTION_NAMES):
                path = stage / name
                handle = _open_held_reader(
                    path,
                    f"final production artifact {name} pin",
                    deny_write=True,
                    deny_delete=False,
                )
                try:
                    handle_status = os.fstat(handle.fileno())
                    if (
                        not stat_module.S_ISREG(handle_status.st_mode)
                        or _is_reparse(handle_status)
                        or handle_status.st_nlink != 1
                    ):
                        raise VerificationError(
                            f"final production artifact {name} is not an exact regular file"
                        )
                    self.handles[name] = handle
                    self.file_ids[name] = _file_id(handle_status)
                    self.signatures[name] = _status_signature(handle_status)
                except BaseException:
                    handle.close()
                    raise
            _validate_production_inventory(stage, final=True)
            self.verify_identities()
        except BaseException as primary:
            try:
                self.close()
            except VerificationError as cleanup_error:
                raise cleanup_error from primary
            raise

    def __del__(self) -> None:
        for handle in getattr(self, "handles", {}).values():
            try:
                handle.close()
            except BaseException:
                pass

    def verify_handle(self, name: str) -> None:
        handle = self.handles.get(name)
        expected_file_id = self.file_ids.get(name)
        expected_signature = self.signatures.get(name)
        if handle is None or expected_file_id is None or expected_signature is None:
            raise VerificationError(
                f"final production pin is missing for {name}"
            )
        try:
            handle_status = os.fstat(handle.fileno())
        except (OSError, ValueError) as error:
            raise VerificationError(
                f"final production handle check failed for {name}: {error}"
            ) from error
        if (
            not stat_module.S_ISREG(handle_status.st_mode)
            or _file_id(handle_status) != expected_file_id
            or _status_signature(handle_status) != expected_signature
        ):
            raise VerificationError(
                f"final production artifact identity or content drifted for {name}"
            )

    def handle_for_read(self, name: str) -> BinaryIO:
        self.verify_handle(name)
        handle = self.handles[name]
        try:
            handle.seek(0)
        except (OSError, ValueError) as error:
            raise VerificationError(
                f"final production artifact seek failed for {name}: {error}"
            ) from error
        return handle

    def verify_identities(
        self,
        signatures: dict[str, tuple[int, int, int, int]] | None = None,
    ) -> None:
        if set(self.handles) != _EXPECTED_PRODUCTION_NAMES:
            raise VerificationError("final production pin set is incomplete")
        for name, handle in self.handles.items():
            path = self.stage / name
            try:
                handle_status = os.fstat(handle.fileno())
                path_status = os.stat(path, follow_symlinks=False)
            except OSError as error:
                raise VerificationError(
                    f"final production identity check failed for {name}: {error}"
                ) from error
            expected_file_id = self.file_ids.get(name)
            if (
                expected_file_id is None
                or not stat_module.S_ISREG(handle_status.st_mode)
                or not stat_module.S_ISREG(path_status.st_mode)
                or _file_id(handle_status) != expected_file_id
                or _file_id(path_status) != expected_file_id
                or _status_signature(handle_status) != self.signatures.get(name)
                or (
                    signatures is not None
                    and _status_signature(handle_status) != signatures[name]
                )
            ):
                raise VerificationError(
                    f"final production artifact identity or content drifted for {name}"
                )

    def verify_evidence(self, summary: dict[str, object]) -> None:
        summary_raw = _json_bytes(summary)
        expected: dict[str, tuple[int, str]] = {
            "selection-summary.json": (
                len(summary_raw),
                hashlib.sha256(summary_raw).hexdigest(),
            ),
            "root-ids.txt": (
                summary["artifacts"]["rootIds"]["bytes"],
                summary["artifacts"]["rootIds"]["sha256"],
            ),
            "selected-tuples.bin": (
                summary["artifacts"]["selectedTuples"]["bytes"],
                summary["artifacts"]["selectedTuples"]["sha256"],
            ),
        }
        for entry in summary["artifacts"]["buckets"]["entries"]:
            expected[entry["filename"]] = (entry["bytes"], entry["sha256"])
        if set(expected) != _EXPECTED_PRODUCTION_NAMES:
            raise VerificationError("final production evidence set is incomplete")

        signatures: dict[str, tuple[int, int, int, int]] = {}
        for name in sorted(_EXPECTED_PRODUCTION_NAMES):
            handle = self.handles[name]
            digest = hashlib.sha256()
            size = 0
            try:
                before = os.fstat(handle.fileno())
                handle.seek(0)
                while True:
                    chunk = handle.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    digest.update(chunk)
                after = os.fstat(handle.fileno())
            except (OSError, ValueError) as error:
                raise VerificationError(
                    f"final production evidence read failed for {name}: {error}"
                ) from error
            if (
                _file_id(before) != self.file_ids[name]
                or _file_id(after) != self.file_ids[name]
                or _status_signature(before) != _status_signature(after)
                or size != after.st_size
            ):
                raise VerificationError(
                    f"final production artifact changed during evidence read for {name}"
                )
            if (size, digest.hexdigest()) != expected[name]:
                raise VerificationError(
                    f"final production artifact bytes or SHA-256 contradict evidence for {name}"
                )
            signatures[name] = _status_signature(after)

        summary_handle = self.handles["selection-summary.json"]
        try:
            summary_handle.seek(0)
            if summary_handle.read(len(summary_raw) + 1) != summary_raw:
                raise VerificationError(
                    "final production summary bytes contradict validated evidence"
                )
        except VerificationError:
            raise
        except (OSError, ValueError) as error:
            raise VerificationError(
                f"final production summary evidence read failed: {error}"
            ) from error
        self.verify_identities(signatures)

    def terminal_snapshots(
        self,
        summary: dict[str, object],
    ) -> dict[str, _HeldFileSnapshot]:
        _validate_production_inventory(self.stage, final=True)
        self.verify_evidence(summary)
        evidence_snapshots: dict[str, _HeldFileSnapshot] = {}
        for name in sorted(_EXPECTED_PRODUCTION_NAMES):
            evidence_snapshots[name] = _held_file_snapshot(
                self.handles[name],
                self.stage / name,
                f"final production artifact {name} pre-terminal snapshot",
            )
            if evidence_snapshots[name].link_count != 1:
                raise VerificationError(
                    f"final production artifact {name} acquired an untracked hard link"
                )
        for name in sorted(_EXPECTED_PRODUCTION_NAMES):
            old_handle = self.handles[name]
            frozen = _open_held_reader(
                self.stage / name,
                f"final production artifact {name} terminal pin",
                deny_write=True,
                deny_delete=True,
            )
            try:
                frozen_snapshot = _held_file_snapshot(
                    frozen,
                    self.stage / name,
                    f"final production artifact {name} terminal pin snapshot",
                )
                if frozen_snapshot != evidence_snapshots[name]:
                    raise VerificationError(
                        f"final production artifact {name} drifted while entering "
                        "terminal evidence"
                    )
                if frozen_snapshot.link_count != 1:
                    raise VerificationError(
                        f"final production artifact {name} terminal pin has an "
                        "untracked hard link"
                    )
                self.handles[name] = frozen
                frozen = None
            finally:
                if frozen is not None:
                    frozen.close()
            old_handle.close()
        _validate_production_inventory(self.stage, final=True)
        snapshots: dict[str, _HeldFileSnapshot] = {}
        for name in sorted(_EXPECTED_PRODUCTION_NAMES):
            snapshot = _held_file_snapshot(
                self.handles[name],
                self.stage / name,
                f"final production artifact {name} terminal snapshot",
            )
            if (
                snapshot.file_id != self.file_ids[name]
                or snapshot.link_count != 1
                or (
                    snapshot.link_count,
                    snapshot.size,
                    snapshot.mtime_ns,
                    snapshot.ctime_ns,
                )
                != self.signatures[name]
            ):
                raise VerificationError(
                    f"final production artifact {name} drifted before terminal snapshot"
                )
            snapshots[name] = snapshot
        self.snapshots = snapshots
        return snapshots

    def close(self) -> None:
        failures: list[str] = []
        while self.handles:
            name, handle = self.handles.popitem()
            try:
                handle.close()
            except OSError as error:
                failures.append(f"close {name}: {error}")
        self.file_ids.clear()
        self.signatures.clear()
        self.snapshots.clear()
        if failures:
            raise VerificationError(
                "final production pin release failed: " + "; ".join(failures)
            )


def _validate_production(
    stage: Path,
    pinned: _PinnedProduction,
    summary: dict[str, object],
    bindings: VerificationBindings,
    limits: VerificationLimits,
    expected_profile: str,
) -> _ProductionFacts:
    top = _exact_mapping(
        summary,
        {
            "artifacts",
            "identities",
            "input",
            "policy",
            "profile",
            "rejections",
            "schema",
            "selected",
        },
        "production summary",
    )
    if summary.get("profile") != expected_profile:
        raise VerificationError("production selection profile does not match broad verification profile")
    identities = _exact_mapping(
        top["identities"],
        {
            "candidatePbfBytes",
            "candidatePbfSha256",
            "codeSha256",
            "convertedStreamBytes",
            "convertedStreamFormat",
            "convertedStreamSha256",
            "planetSourceSha256",
            "policySha256",
            "runtimeSha256",
        },
        "production identities",
    )
    for key in (
        "candidatePbfSha256",
        "codeSha256",
        "convertedStreamSha256",
        "planetSourceSha256",
        "policySha256",
        "runtimeSha256",
    ):
        _identity(identities[key], f"production {key}")
    candidate_pbf_bytes = _bounded_count(
        identities["candidatePbfBytes"],
        limits.max_input_bytes,
        "production candidate PBF bytes",
    )
    converted_stream_bytes = _bounded_count(
        identities["convertedStreamBytes"],
        limits.max_input_bytes,
        "production converted stream bytes",
    )
    if identities["convertedStreamFormat"] != "opl":
        raise VerificationError("production converted stream format is not canonical OPL")
    if (
        expected_profile == _LIVE_PRODUCTION_PROFILE
        and identities["planetSourceSha256"] != LIVE_PLANET_SOURCE_SHA256
    ):
        raise VerificationError(
            "production live name-envelope summary planet source is not authoritative"
        )
    if identities["planetSourceSha256"] != bindings.planet_source_sha256:
        raise VerificationError("production and broad planet source identities differ")
    if identities["policySha256"] != GLOBAL_POLICY_SHA256:
        raise VerificationError("production policy identity differs from independent policy")
    policy = _exact_mapping(top["policy"], {"bytes", "document", "sha256"}, "production policy")
    policy_raw = _policy_bytes()
    if (
        type(policy["bytes"]) is not int
        or policy["bytes"] != len(policy_raw)
        or policy["sha256"] != GLOBAL_POLICY_SHA256
        or type(policy["document"]) is not dict
        or _json_bytes(policy["document"]) != policy_raw
    ):
        raise VerificationError("production policy document is not the exact independent policy")
    input_facts = _exact_mapping(
        top["input"],
        {"bytes", "objects", "references", "relations", "sha256", "tags", "ways"},
        "production input",
    )
    input_bytes = _bounded_count(input_facts["bytes"], limits.max_input_bytes, "production input bytes")
    input_objects = _bounded_count(input_facts["objects"], limits.max_objects, "production input objects")
    input_ways = _bounded_count(input_facts["ways"], limits.max_objects, "production input ways")
    input_relations = _bounded_count(input_facts["relations"], limits.max_objects, "production input relations")
    _bounded_count(input_facts["tags"], limits.max_total_tags, "production input tags")
    _bounded_count(input_facts["references"], limits.max_total_references, "production input references")
    input_sha = _identity(input_facts["sha256"], "production input SHA-256")
    if (
        input_objects != input_ways + input_relations
        or input_bytes != converted_stream_bytes
        or input_sha != identities["convertedStreamSha256"]
    ):
        raise VerificationError("production input counts or candidate identity are inconsistent")
    if expected_profile == _LIVE_PRODUCTION_PROFILE and (
        candidate_pbf_bytes != _LIVE_PRODUCTION_NAME_ENVELOPE_PBF_BYTES
        or identities["candidatePbfSha256"]
        != _LIVE_PRODUCTION_NAME_ENVELOPE_PBF_SHA256
        or converted_stream_bytes != _LIVE_PRODUCTION_NAME_ENVELOPE_OPL_BYTES
        or input_sha != _LIVE_PRODUCTION_NAME_ENVELOPE_OPL_SHA256
        or identities["convertedStreamFormat"]
        != _LIVE_PRODUCTION_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT
        or input_ways != _LIVE_PRODUCTION_NAME_ENVELOPE_WAYS
        or input_relations != _LIVE_PRODUCTION_NAME_ENVELOPE_RELATIONS
    ):
        raise VerificationError("production live name-envelope summary is not its exact locked envelope")

    selected = _exact_mapping(
        top["selected"],
        {"nonNfcFieldCount", "nonNfcRecordCount", "relations", "total", "ways"},
        "production selected",
    )
    production_ways = _bounded_count(selected["ways"], limits.max_objects, "production selected ways")
    production_relations = _bounded_count(selected["relations"], limits.max_objects, "production selected relations")
    selected_total = _bounded_count(selected["total"], limits.max_objects, "production selected total")
    non_nfc_fields = _bounded_count(
        selected["nonNfcFieldCount"],
        limits.max_total_tags * 2 + limits.max_total_references,
        "production non-NFC fields",
    )
    non_nfc_records = _bounded_count(selected["nonNfcRecordCount"], selected_total, "production non-NFC records")
    if selected_total != production_ways + production_relations:
        raise VerificationError("production selected counts are inconsistent")

    rejections = _exact_mapping(
        top["rejections"],
        {"countsByReasonId", "ledgerSha256", "records"},
        "production rejections",
    )
    counts = rejections["countsByReasonId"]
    if type(counts) is not dict or any(
        type(key) is not str
        or key not in {str(reason) for reason in range(1, 11)}
        or type(value) is not int
        or value <= 0
        for key, value in counts.items()
    ):
        raise VerificationError("production rejection counts are invalid")
    rejection_records = _bounded_count(rejections["records"], limits.max_objects, "production rejection records")
    if rejection_records != sum(counts.values()):
        raise VerificationError("production rejection records contradict counts")
    rejection_ledger_sha256 = _identity(
        rejections["ledgerSha256"],
        "production rejection ledger SHA-256",
    )
    if input_objects != selected_total + rejection_records:
        raise VerificationError(
            "production input objects do not partition into selected and rejection records"
        )
    if (
        rejection_records == 0
        and rejection_ledger_sha256
        != hashlib.sha256(_REJECTION_DOMAIN).hexdigest()
    ):
        raise VerificationError(
            "production empty rejection ledger does not have its canonical identity"
        )

    artifacts = _exact_mapping(top["artifacts"], {"buckets", "rootIds", "selectedTuples"}, "production artifacts")
    bucket_summary = _exact_mapping(
        artifacts["buckets"],
        {"bucketCount", "entries", "records"},
        "production bucket inventory",
    )
    if (
        type(bucket_summary["bucketCount"]) is not int
        or bucket_summary["bucketCount"] != 256
        or type(bucket_summary["records"]) is not int
        or bucket_summary["records"] != selected_total
    ):
        raise VerificationError("production bucket inventory counts are inconsistent")
    entries = bucket_summary["entries"]
    if type(entries) is not list or len(entries) != 256:
        raise VerificationError("production bucket inventory must contain exactly 256 entries")
    total_bucket_bytes = 0
    total_bucket_records = 0
    frozen_entries: list[dict[str, object]] = []
    for bucket, raw_entry in enumerate(entries):
        entry = _exact_mapping(
            raw_entry,
            {"bucket", "bytes", "filename", "records", "sha256"},
            f"production bucket inventory entry {bucket}",
        )
        name = f"roots-{bucket:03d}.bin"
        if (
            type(entry["bucket"]) is not int
            or entry["bucket"] != bucket
            or entry["filename"] != name
        ):
            raise VerificationError(f"production bucket inventory entry {bucket} has the wrong identity")
        if (
            type(entry["bytes"]) is not int
            or entry["bytes"] < 0
            or entry["bytes"] > limits.max_production_bucket_bytes
        ):
            raise VerificationError(
                f"production bucket {bucket:03d} exceeds the bucket byte ceiling"
            )
        expected_bytes = entry["bytes"]
        expected_records = _bounded_count(
            entry["records"],
            selected_total,
            f"production bucket {bucket:03d} records",
        )
        expected_sha = _identity(entry["sha256"], f"production bucket {bucket:03d} SHA-256")
        total_bucket_bytes += expected_bytes
        if total_bucket_bytes > limits.max_production_total_bucket_bytes:
            raise VerificationError("production buckets exceed the total bucket byte ceiling")
        total_bucket_records += expected_records
        path = stage / name
        actual_bytes, actual_sha = _file_fact(
            path,
            max_bytes=limits.max_production_bucket_bytes,
            ceiling_label="production bucket byte ceiling",
            pinned=pinned,
        )
        if actual_bytes != expected_bytes or actual_sha != expected_sha:
            raise VerificationError(f"production bucket {bucket:03d} bytes or SHA-256 contradict inventory")
        _validate_bucket_file(path, bucket, expected_records, limits, pinned)
        frozen_entries.append(dict(entry))
    if total_bucket_records != selected_total:
        raise VerificationError("production bucket record counts contradict selected total")

    tuple_path = stage / "selected-tuples.bin"
    root_path = stage / "root-ids.txt"
    tuple_summary = _exact_mapping(
        artifacts["selectedTuples"],
        {"bytes", "filename", "recordBytes", "records", "sha256"},
        "production selected tuple artifact",
    )
    root_summary = _exact_mapping(
        artifacts["rootIds"],
        {"bytes", "filename", "records", "sha256"},
        "production root-ID artifact",
    )
    if (
        tuple_summary["filename"] != "selected-tuples.bin"
        or type(tuple_summary["recordBytes"]) is not int
        or tuple_summary["recordBytes"] != _SELECTED_TUPLE_BYTES
        or type(tuple_summary["records"]) is not int
        or tuple_summary["records"] != selected_total
        or root_summary["filename"] != "root-ids.txt"
        or type(root_summary["records"]) is not int
        or root_summary["records"] != selected_total
    ):
        raise VerificationError("production tuple or root-ID artifact identity is inconsistent")
    _identity(tuple_summary["sha256"], "production selected tuple SHA-256")
    _identity(root_summary["sha256"], "production root-ID SHA-256")
    tuple_bytes, tuple_digest = _file_fact(
        tuple_path,
        max_bytes=limits.max_production_tuple_bytes,
        ceiling_label="production tuple byte ceiling",
        pinned=pinned,
    )
    root_bytes, root_digest = _file_fact(
        root_path,
        max_bytes=limits.max_production_root_ids_bytes,
        ceiling_label="production root-ID byte ceiling",
        pinned=pinned,
    )
    expected_tuple_bytes = _bounded_count(
        tuple_summary["bytes"],
        limits.max_production_tuple_bytes,
        "production selected tuple bytes",
    )
    expected_root_bytes = _bounded_count(
        root_summary["bytes"],
        limits.max_production_root_ids_bytes,
        "production root-ID bytes",
    )
    if tuple_bytes != expected_tuple_bytes or tuple_digest != tuple_summary["sha256"]:
        raise VerificationError("production selected tuple bytes or SHA-256 were tampered")
    if root_bytes != expected_root_bytes or root_digest != root_summary["sha256"]:
        raise VerificationError("production root ID bytes or SHA-256 were tampered")
    if tuple_bytes % _SELECTED_TUPLE_BYTES:
        raise VerificationError("production selected tuple file is truncated")
    if tuple_bytes != selected_total * _SELECTED_TUPLE_BYTES:
        raise VerificationError("production selected tuple byte count contradicts selected total")
    previous = (0, 0)
    records = 0
    counted = Counter()
    try:
        source = pinned.handle_for_read("selected-tuples.bin")
        roots = pinned.handle_for_read("root-ids.txt")
        while True:
            record = source.read(_SELECTED_TUPLE_BYTES)
            if not record:
                break
            if len(record) != _SELECTED_TUPLE_BYTES or record[0] not in {1, 2}:
                raise VerificationError("production selected tuple record is malformed")
            key = (record[0], struct.unpack_from("<Q", record, 1)[0])
            version = struct.unpack_from("<I", record, 9)[0]
            if key[1] <= 0 or key[1] > _MAX_SIGNED_63 or version <= 0:
                raise VerificationError("production selected tuple contains invalid numeric fields")
            if key <= previous:
                raise VerificationError("production selected tuples contain duplicates or are unordered")
            previous = key
            expected_root = ("w" if key[0] == 1 else "r") + str(key[1]) + "\n"
            root_line = roots.readline(64)
            if root_line != expected_root.encode("ascii"):
                raise VerificationError("production tuple and root-ID files disagree")
            counted[key[0]] += 1
            records += 1
            if records > selected_total:
                raise VerificationError("production selected tuple exceeds the summary record count")
        if roots.read(1):
            raise VerificationError("production root-ID file has extra records")
        pinned.verify_handle("selected-tuples.bin")
        pinned.verify_handle("root-ids.txt")
    except VerificationError:
        raise
    except OSError as error:
        raise VerificationError(f"production tuple validation failed: {error}") from error
    if records != selected_total:
        raise VerificationError("production selected tuple count contradicts summary")
    if counted[1] != production_ways or counted[2] != production_relations:
        raise VerificationError("production tuple kind counts contradict summary")
    pinned.verify_identities()
    return _ProductionFacts(
        pinned=pinned,
        bucket_entries=tuple(frozen_entries),
        input_objects=input_objects,
        converted_stream_bytes=converted_stream_bytes,
        converted_stream_sha256=input_sha,
        ways=production_ways,
        relations=production_relations,
        non_nfc_fields=non_nfc_fields,
        non_nfc_records=non_nfc_records,
        rejection_counts=tuple(
            sorted((int(reason), count) for reason, count in counts.items())
        ),
        rejection_records=rejection_records,
        rejection_ledger_sha256=rejection_ledger_sha256,
    )


def _validate_production_stream(
    stream: object,
    summary: dict[str, object],
    production: _ProductionFacts,
    limits: VerificationLimits,
) -> None:
    stats = _BroadStats(digest=hashlib.sha256())
    selected_counts: Counter[int] = Counter()
    rejection_counts: Counter[int] = Counter()
    rejection_chain = hashlib.sha256(_REJECTION_DOMAIN).digest()
    selected_non_nfc_fields = 0
    selected_non_nfc_records = 0
    previous_key = (0, 0)
    tuple_digest = hashlib.sha256()
    tuple_bytes = 0
    root_digest = hashlib.sha256()
    root_bytes = 0
    bucket_digests = [hashlib.sha256() for _ in range(256)]
    bucket_bytes = [0] * 256
    bucket_counts = [0] * 256

    def emit(value: _SourceObject) -> None:
        nonlocal rejection_chain
        nonlocal selected_non_nfc_fields, selected_non_nfc_records
        nonlocal tuple_bytes, root_bytes, previous_key

        stats.objects += 1
        if stats.objects > limits.max_objects:
            raise VerificationError(
                f"production stream exceeds the object ceiling {limits.max_objects}"
            )
        if value.kind == 1:
            stats.ways += 1
            reference_count = len(value.refs)
        else:
            stats.relations += 1
            reference_count = len(value.members)
        stats.tags += len(value.tags)
        stats.references += reference_count
        if stats.tags > limits.max_total_tags:
            raise VerificationError(
                f"production stream exceeds the tag ceiling {limits.max_total_tags}"
            )
        if stats.references > limits.max_total_references:
            raise VerificationError(
                "production stream exceeds the reference ceiling "
                f"{limits.max_total_references}"
            )

        key = (value.kind, value.object_id)
        if key == previous_key:
            raise VerificationError(
                "production stream contains a duplicate/history object under Type_then_ID"
            )
        if key < previous_key:
            raise VerificationError(
                "production stream is not monotonic Type_then_ID canonical OPL"
            )
        previous_key = key
        payload = _payload(value)
        object_digest = hashlib.sha256(_ROOT_DOMAIN + payload).digest()
        reason = _reason(value)
        if reason:
            rejection_counts[reason] += 1
            rejected_decision = struct.pack(
                "<BQI32sB",
                value.kind,
                value.object_id,
                value.version,
                object_digest,
                reason,
            )
            rejection_chain = hashlib.sha256(
                _REJECTION_DOMAIN + rejection_chain + rejected_decision
            ).digest()
            return

        tuple_record = struct.pack(
            "<BQI32s",
            value.kind,
            value.object_id,
            value.version,
            object_digest,
        )
        root_record = (
            ("w" if value.kind == 1 else "r") + str(value.object_id) + "\n"
        ).encode("ascii")
        frame = struct.pack("<I", len(payload) + 32) + payload + object_digest
        if len(frame) - 4 > limits.max_production_frame_bytes:
            raise VerificationError(
                "production stream selected frame exceeds the production frame byte ceiling"
            )
        bucket = hashlib.sha256(
            _BUCKET_DOMAIN
            + bytes((value.kind,))
            + struct.pack("<Q", value.object_id)
        ).digest()[0]
        tuple_digest.update(tuple_record)
        tuple_bytes += len(tuple_record)
        root_digest.update(root_record)
        root_bytes += len(root_record)
        bucket_digests[bucket].update(frame)
        bucket_bytes[bucket] += len(frame)
        bucket_counts[bucket] += 1
        selected_counts[value.kind] += 1
        fields, records = _non_nfc(value)
        selected_non_nfc_fields += fields
        selected_non_nfc_records += records

    _parse_opl_stream(
        stream,
        limits,
        stats,
        emit,
        label="production stream",
    )
    converted_stream_sha256 = stats.digest.hexdigest()
    if (
        stats.bytes != production.converted_stream_bytes
        or converted_stream_sha256 != production.converted_stream_sha256
    ):
        raise VerificationError(
            "production stream identity contradicts the production summary: "
            f"expected {production.converted_stream_bytes} bytes and "
            f"{production.converted_stream_sha256}, got {stats.bytes} bytes and "
            f"{converted_stream_sha256}"
        )

    input_facts = summary["input"]
    if (
        stats.bytes != input_facts["bytes"]
        or stats.objects != input_facts["objects"]
        or stats.references != input_facts["references"]
        or stats.relations != input_facts["relations"]
        or converted_stream_sha256 != input_facts["sha256"]
        or stats.tags != input_facts["tags"]
        or stats.ways != input_facts["ways"]
    ):
        raise VerificationError(
            "production stream input counts or SHA-256 contradict the production summary"
        )

    selected_total = selected_counts[1] + selected_counts[2]
    if (
        selected_counts[1] != production.ways
        or selected_counts[2] != production.relations
        or selected_non_nfc_fields != production.non_nfc_fields
        or selected_non_nfc_records != production.non_nfc_records
    ):
        raise VerificationError(
            "production stream selected decisions contradict the production summary"
        )
    expected_rejections = Counter(dict(production.rejection_counts))
    if (
        rejection_counts != expected_rejections
        or sum(rejection_counts.values()) != production.rejection_records
        or rejection_chain.hex() != production.rejection_ledger_sha256
    ):
        raise VerificationError(
            "production stream rejection decisions or ledger contradict the production summary"
        )

    artifacts = summary["artifacts"]
    tuple_summary = artifacts["selectedTuples"]
    root_summary = artifacts["rootIds"]
    if (
        tuple_bytes != selected_total * _SELECTED_TUPLE_BYTES
        or tuple_bytes != tuple_summary["bytes"]
        or selected_total != tuple_summary["records"]
        or tuple_digest.hexdigest() != tuple_summary["sha256"]
    ):
        raise VerificationError(
            "production stream selected tuple decisions contradict artifact evidence"
        )
    if (
        root_bytes != root_summary["bytes"]
        or selected_total != root_summary["records"]
        or root_digest.hexdigest() != root_summary["sha256"]
    ):
        raise VerificationError(
            "production stream root-ID decisions contradict artifact evidence"
        )
    total_bucket_bytes = 0
    for bucket, entry in enumerate(production.bucket_entries):
        total_bucket_bytes += bucket_bytes[bucket]
        if (
            bucket_bytes[bucket] != entry["bytes"]
            or bucket_counts[bucket] != entry["records"]
            or bucket_digests[bucket].hexdigest() != entry["sha256"]
        ):
            raise VerificationError(
                f"production stream bucket {bucket:03d} decisions contradict artifact evidence"
            )
    if total_bucket_bytes > limits.max_production_total_bucket_bytes:
        raise VerificationError(
            "production stream buckets exceed the total production byte ceiling"
        )


def _verify_planet_roots_once(
    broad_stream: object,
    production_stage_dir: str | Path,
    work_dir: str | Path,
    bindings: VerificationBindings,
    *,
    production_stream: object,
    production_input_format: str,
    input_format: str,
    profile: str,
    limits: VerificationLimits,
    workers: int,
) -> VerificationResult:
    """Validate exact production OPL, then compare broad-selected roots independently."""

    if not isinstance(bindings, VerificationBindings):
        raise VerificationError("verification bindings are missing or invalid")
    if not isinstance(limits, VerificationLimits):
        raise VerificationError("verification limits are missing or invalid")
    if type(workers) is not int or workers <= 0:
        raise VerificationError("worker count must be a positive integer")
    if production_input_format != "opl":
        raise VerificationError(
            "production stream format must be exactly canonical OPL"
        )
    if input_format not in {"opl", "xml"}:
        raise VerificationError("broad input format must be exactly 'opl' or 'xml'")
    if input_format != bindings.converted_stream_format:
        raise VerificationError("converted stream format does not match the parser format")
    if profile not in {FIXTURE_BROAD_ENVELOPE_PROFILE, LIVE_BROAD_ENVELOPE_PROFILE}:
        raise VerificationError("broad verification profile is unknown")
    if (
        profile == LIVE_BROAD_ENVELOPE_PROFILE
        and bindings.converted_stream_format
        != LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT
    ):
        raise VerificationError("live broad converted stream format must be canonical OPL")
    if bindings.policy_sha256 != GLOBAL_POLICY_SHA256:
        raise VerificationError("policy SHA-256 does not identify the independent global policy")
    expected_production_profile = (
        _LIVE_PRODUCTION_PROFILE
        if profile == LIVE_BROAD_ENVELOPE_PROFILE
        else _FIXTURE_PRODUCTION_PROFILE
    )
    pinned_production: _PinnedProduction | None = None
    try:
        production_stage = _validate_directory(production_stage_dir, "production stage")
        work = _validate_directory(work_dir, "verifier work directory")
        try:
            if next(work.iterdir(), None) is not None:
                raise VerificationError("verifier work directory must be empty before start")
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(f"verifier work directory inventory failed: {error}") from error
        pinned_production = _PinnedProduction(production_stage)
        production_summary = _production_summary(pinned_production, limits)
        production = _validate_production(
            production_stage,
            pinned_production,
            production_summary,
            bindings,
            limits,
            expected_production_profile,
        )
        _validate_production_stream(
            production_stream,
            production_summary,
            production,
            limits,
        )
    except VerificationError as error:
        if pinned_production is not None:
            try:
                pinned_production.close()
            except VerificationError as cleanup_error:
                raise cleanup_error from error
        raise
    except BaseException as error:
        if pinned_production is not None:
            try:
                pinned_production.close()
            except VerificationError as cleanup_error:
                raise cleanup_error from error
        if isinstance(error, MemoryError):
            raise VerificationError(
                "production validation memory allocation failed"
            ) from error
        if isinstance(
            error,
            (OSError, OverflowError, struct.error, ValueError, UnicodeError),
        ):
            raise VerificationError(f"production validation failed: {error}") from error
        raise

    scratch: _VerifierScratch | None = None
    open_handles: list[BinaryIO] = []

    def finish_handle(handle: BinaryIO, label: str) -> None:
        try:
            if scratch is None or not isinstance(handle, _TrackedWriter):
                raise VerificationError(f"{label} has no tracked WRITING owner")
            scratch.seal(Path(handle.name), handle)
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(f"{label} finalization failed: {error}") from error
        open_handles.remove(handle)

    try:
        stats = _BroadStats(digest=hashlib.sha256())
        selected_counts: Counter[int] = Counter()
        semantic_rejections: Counter[int] = Counter()
        selected_non_nfc_fields = 0
        selected_non_nfc_records = 0
        scratch = _VerifierScratch(work)
        sorter = _ExternalDecisionSort(scratch, limits.sort_chunk_records, limits.max_sort_runs)
        payload_name = "selected-object-frames.bin"
        payload_output = _tracked_handle(scratch, payload_name, open_handles)

        def emit(value: _SourceObject) -> None:
            nonlocal selected_non_nfc_fields, selected_non_nfc_records
            stats.objects += 1
            if stats.objects > limits.max_objects:
                raise VerificationError(f"broad input exceeds the object ceiling {limits.max_objects}")
            if value.kind == 1:
                stats.ways += 1
                references = len(value.refs)
            else:
                stats.relations += 1
                references = len(value.members)
            stats.tags += len(value.tags)
            stats.references += references
            if stats.tags > limits.max_total_tags:
                raise VerificationError(f"broad input exceeds the tag ceiling {limits.max_total_tags}")
            if stats.references > limits.max_total_references:
                raise VerificationError(
                    f"broad input exceeds the reference ceiling {limits.max_total_references}"
                )
            payload = _payload(value)
            object_digest = hashlib.sha256(_ROOT_DOMAIN + payload).digest()
            rejection = _reason(value)
            payload_offset = 0
            frame_bytes = 0
            if rejection:
                semantic_rejections[rejection] += 1
            else:
                body_bytes = len(payload) + 32
                if body_bytes > limits.max_production_frame_bytes:
                    raise VerificationError("independent selected frame exceeds the production frame byte ceiling")
                frame = struct.pack("<I", body_bytes) + payload + object_digest
                payload_offset = payload_output.tell()
                payload_output.write(frame)
                frame_bytes = len(frame)
                selected_counts[value.kind] += 1
                fields, records = _non_nfc(value)
                selected_non_nfc_fields += fields
                selected_non_nfc_records += records
            sorter.add(
                _DECISION_STRUCT.pack(
                    value.kind,
                    value.object_id,
                    value.version,
                    object_digest,
                    rejection,
                    payload_offset,
                    frame_bytes,
                )
            )

        if input_format == "opl":
            _parse_opl_stream(broad_stream, limits, stats, emit)
        else:
            _parse_xml_stream(broad_stream, limits, stats, emit)
        converted_stream_sha256 = stats.digest.hexdigest()
        if (
            stats.bytes != bindings.converted_stream_bytes
            or converted_stream_sha256 != bindings.converted_stream_sha256
        ):
            raise VerificationError(
                "converted stream identity mismatch: "
                f"expected {bindings.converted_stream_bytes} bytes and "
                f"{bindings.converted_stream_sha256}, got {stats.bytes} bytes and "
                f"{converted_stream_sha256}"
            )
        if profile == LIVE_BROAD_ENVELOPE_PROFILE and (
            bindings.planet_source_sha256 != LIVE_PLANET_SOURCE_SHA256
            or bindings.broad_pbf_bytes != LIVE_BROAD_ENVELOPE_PBF_BYTES
            or bindings.broad_pbf_sha256 != LIVE_BROAD_ENVELOPE_PBF_SHA256
            or bindings.converted_stream_bytes != LIVE_BROAD_ENVELOPE_OPL_BYTES
            or bindings.converted_stream_sha256 != LIVE_BROAD_ENVELOPE_OPL_SHA256
            or bindings.converted_stream_format
            != LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT
            or stats.ways != LIVE_BROAD_ENVELOPE_WAYS
            or stats.relations != LIVE_BROAD_ENVELOPE_RELATIONS
        ):
            raise VerificationError(
                "live broad envelope profile requires its exact planet source, broad PBF, "
                "canonical converted stream, format, way count, and relation count"
            )
        sorter.finish()
        finish_handle(payload_output, "selected object frame spool")

        verifier_tuples = scratch.path("verifier-selected-tuples.bin")
        verifier_roots = scratch.path("verifier-root-ids.txt")
        output_handles: dict[str, BinaryIO] = {
            "selected-tuples.bin": _tracked_handle(
                scratch, "verifier-selected-tuples.bin", open_handles
            ),
            "root-ids.txt": _tracked_handle(scratch, "verifier-root-ids.txt", open_handles),
        }
        bucket_counts = [0] * 256

        def output_handle(name: str) -> BinaryIO:
            handle = output_handles.get(name)
            if handle is None:
                handle = _tracked_handle(scratch, f"verifier-{name}", open_handles)
                output_handles[name] = handle
            return handle

        rejection_chain = hashlib.sha256(_REJECTION_DOMAIN).digest()
        merged_rejections: Counter[int] = Counter()
        merged_selected: Counter[int] = Counter()
        previous_key = (0, 0)
        payload_path = scratch.path(payload_name)
        try:
            with scratch.open_owned(payload_path) as payload_source:
                for record in sorter.merged():
                    kind, object_id, version, digest, reason, offset, frame_bytes = _DECISION_STRUCT.unpack(record)
                    key = (kind, object_id)
                    if key == previous_key:
                        raise VerificationError(
                            "broad input contains a duplicate/history object after external sort"
                        )
                    if key < previous_key:
                        raise VerificationError("external sort emitted unordered objects")
                    previous_key = key
                    canonical_decision = record[:46]
                    if reason:
                        if offset != 0 or frame_bytes != 0:
                            raise VerificationError("rejected decision unexpectedly references an object frame")
                        merged_rejections[reason] += 1
                        rejection_chain = hashlib.sha256(
                            _REJECTION_DOMAIN + rejection_chain + canonical_decision
                        ).digest()
                        continue
                    if frame_bytes < 4 or frame_bytes > limits.max_production_frame_bytes + 4:
                        raise VerificationError("selected decision frame length is outside its ceiling")
                    payload_source.seek(offset)
                    frame = payload_source.read(frame_bytes)
                    if len(frame) != frame_bytes or struct.unpack_from("<I", frame)[0] != frame_bytes - 4:
                        raise VerificationError("selected decision frame spool is truncated or inconsistent")
                    bucket = hashlib.sha256(
                        _BUCKET_DOMAIN + bytes((kind,)) + struct.pack("<Q", object_id)
                    ).digest()[0]
                    parsed_tuple = _validate_bucket_frame(
                        memoryview(frame)[4:],
                        bucket,
                        f"independent selected object {kind}:{object_id}",
                        limits,
                    )
                    if parsed_tuple != record[:_SELECTED_TUPLE_BYTES]:
                        raise VerificationError("independent selected object frame contradicts decision tuple")
                    output_handles["selected-tuples.bin"].write(record[:_SELECTED_TUPLE_BYTES])
                    prefix = "w" if kind == 1 else "r"
                    output_handles["root-ids.txt"].write(f"{prefix}{object_id}\n".encode("ascii"))
                    output_handle(f"roots-{bucket:03d}.bin").write(frame)
                    bucket_counts[bucket] += 1
                    merged_selected[kind] += 1
                scratch.verify_handle(payload_path, payload_source)
        except VerificationError:
            raise
        except OSError as error:
            raise VerificationError(f"verifier selected frame regeneration failed: {error}") from error

        for bucket in range(256):
            output_handle(f"roots-{bucket:03d}.bin")
        for name, handle in list(output_handles.items()):
            finish_handle(handle, f"verifier output {name}")
        output_handles.clear()

        if merged_rejections != semantic_rejections or merged_selected != selected_counts:
            raise VerificationError("external sort accounting differs from broad parse")
        _compare_files(
            production.pinned,
            "selected-tuples.bin",
            verifier_tuples,
            "selected tuple",
            owner=scratch,
        )
        _compare_files(
            production.pinned,
            "root-ids.txt",
            verifier_roots,
            "root ID",
            owner=scratch,
        )

        generated_buckets: list[Path] = []
        for bucket in range(256):
            generated_path = scratch.path(f"verifier-roots-{bucket:03d}.bin")
            generated_buckets.append(generated_path)
            _compare_files(
                production.pinned,
                f"roots-{bucket:03d}.bin",
                generated_path,
                f"selected object bucket {bucket:03d}",
                owner=scratch,
            )
        if (
            selected_counts[1] != production.ways
            or selected_counts[2] != production.relations
            or selected_non_nfc_fields != production.non_nfc_fields
            or selected_non_nfc_records != production.non_nfc_records
        ):
            raise VerificationError(
                "independent selected counts or non-NFC audit contradict production selection"
            )
        for bucket, generated_path in enumerate(generated_buckets):
            sealed = scratch._sealed_snapshots[generated_path]
            if sealed.byte_length > limits.max_production_bucket_bytes:
                raise VerificationError(
                    f"verifier-roots-{bucket:03d}.bin exceeds the production bucket "
                    f"byte ceiling {limits.max_production_bucket_bytes}"
                )
        candidate_scratch = {
            path: scratch._sealed_snapshots[path]
            for path in [verifier_tuples, verifier_roots, *generated_buckets]
        }
        bucket_inventory: list[dict[str, object]] = []
        for bucket, generated_path in enumerate(generated_buckets):
            snapshot = candidate_scratch[generated_path]
            bucket_inventory.append(
                {
                    "bucket": bucket,
                    "bytes": snapshot.byte_length,
                    "filename": f"roots-{bucket:03d}.bin",
                    "records": bucket_counts[bucket],
                    "sha256": snapshot.sha256,
                }
            )
        tuple_snapshot = candidate_scratch[verifier_tuples]
        root_snapshot = candidate_scratch[verifier_roots]
        tuple_bytes = tuple_snapshot.byte_length
        tuple_sha256 = tuple_snapshot.sha256
        root_bytes = root_snapshot.byte_length
        root_sha256 = root_snapshot.sha256
        selected_total = selected_counts[1] + selected_counts[2]
        inventory_raw = _json_bytes({"entries": bucket_inventory})
        report = {
            "broadSemantic": {
                "objects": stats.objects,
                "references": stats.references,
                "relations": stats.relations,
                "tags": stats.tags,
                "ways": stats.ways,
            },
            "comparison": {
                "bucketBytes": sum(entry["bytes"] for entry in bucket_inventory),
                "bucketCount": 256,
                "bucketInventorySha256": hashlib.sha256(inventory_raw).hexdigest(),
                "bucketRecords": selected_total,
                "rootIdBytes": root_bytes,
                "rootIdRecords": selected_total,
                "rootIdsSha256": root_sha256,
                "selectedTupleBytes": tuple_bytes,
                "selectedTupleRecords": selected_total,
                "selectedTuplesSha256": tuple_sha256,
            },
            "identities": {
                "broadPbfBytes": bindings.broad_pbf_bytes,
                "broadPbfSha256": bindings.broad_pbf_sha256,
                "planetSourceSha256": bindings.planet_source_sha256,
                "policySha256": bindings.policy_sha256,
            },
            "productionSelection": {
                "candidatePbfSha256": _summary_path_value(
                    production_summary, "identities", "candidatePbfSha256"
                ),
                "profile": production_summary["profile"],
            },
            "profile": profile,
            "rejections": {
                "countsByReasonId": {
                    str(reason): merged_rejections[reason]
                    for reason in sorted(merged_rejections)
                },
                "ledgerSha256": rejection_chain.hex(),
                "records": sum(merged_rejections.values()),
            },
            "schema": _REPORT_SCHEMA,
            "selected": {
                "nonNfcFieldCount": selected_non_nfc_fields,
                "nonNfcRecordCount": selected_non_nfc_records,
                "relations": selected_counts[2],
                "total": selected_total,
                "ways": selected_counts[1],
            },
        }
        observation = {
            "execution": {
                "codeSha256": bindings.code_sha256,
                "decisionRecordBytes": _DECISION_RECORD_BYTES,
                "runs": len(sorter.runs),
                "runtimeSha256": bindings.runtime_sha256,
                "sortChunkRecords": limits.sort_chunk_records,
                "workers": workers,
            },
            "convertedStream": {
                "bytes": stats.bytes,
                "format": input_format,
                "sha256": converted_stream_sha256,
            },
            "schema": _OBSERVATION_SCHEMA,
        }
        report_raw = _json_bytes(report)
        observation_raw = _json_bytes(observation)
        result = VerificationResult(
            report_bytes=report_raw,
            report_sha256=hashlib.sha256(report_raw).hexdigest(),
            observation_bytes=observation_raw,
            observation_sha256=hashlib.sha256(observation_raw).hexdigest(),
            selected_way_count=selected_counts[1],
            selected_relation_count=selected_counts[2],
            non_nfc_field_count=selected_non_nfc_fields,
            non_nfc_record_count=selected_non_nfc_records,
            rejection_ledger_sha256=rejection_chain.hex(),
            planet_source_sha256=bindings.planet_source_sha256,
            broad_pbf_bytes=bindings.broad_pbf_bytes,
            broad_pbf_sha256=bindings.broad_pbf_sha256,
            converted_stream_bytes=bindings.converted_stream_bytes,
            converted_stream_sha256=bindings.converted_stream_sha256,
            converted_stream_format=bindings.converted_stream_format,
        )
        _validate_production_inventory(production_stage, final=True)
        final_summary = _production_summary(pinned_production, limits)
        if final_summary != production_summary:
            raise VerificationError(
                "final production summary changed before terminal evidence"
            )
        _validate_production(
            production_stage,
            pinned_production,
            production_summary,
            bindings,
            limits,
            expected_production_profile,
        )
        pinned_production.terminal_snapshots(production_summary)
        scratch.terminalize(candidate_scratch)
        pinned_production.verify_identities()
        _validate_production_inventory(production_stage, final=True)
    except BaseException as error:
        cleanup_failures: list[str] = []
        try:
            _close_tracked(open_handles)
        except VerificationError as cleanup_error:
            cleanup_failures.append(str(cleanup_error))
        if scratch is not None:
            try:
                scratch.cleanup()
            except VerificationError as cleanup_error:
                cleanup_failures.append(str(cleanup_error))
        if pinned_production is not None:
            try:
                pinned_production.close()
            except VerificationError as cleanup_error:
                cleanup_failures.append(str(cleanup_error))
            pinned_production = None
        if cleanup_failures:
            raise VerificationError("verification cleanup failed after primary failure: " + " | ".join(cleanup_failures)) from error
        if isinstance(error, VerificationError):
            raise
        if isinstance(error, MemoryError):
            raise VerificationError("independent verification memory allocation failed") from error
        if isinstance(error, (OSError, OverflowError, struct.error, ValueError, UnicodeError)):
            raise VerificationError(f"independent verification failed: {error}") from error
        raise
    assert scratch is not None
    try:
        _close_tracked(open_handles)
        scratch.cleanup()
    except BaseException as primary:
        if pinned_production is not None:
            try:
                pinned_production.close()
            except VerificationError as cleanup_error:
                raise cleanup_error from primary
            pinned_production = None
        raise
    assert pinned_production is not None
    pinned_production.close()
    return result


def verify_planet_roots(
    broad_stream: object,
    production_stage_dir: str | Path,
    work_dir: str | Path,
    bindings: VerificationBindings,
    *,
    production_stream: object,
    production_input_format: str = "opl",
    input_format: str = "opl",
    profile: str = LIVE_BROAD_ENVELOPE_PROFILE,
    limits: VerificationLimits = VerificationLimits(),
    workers: int = 1,
) -> VerificationResult:
    """Require exact production OPL and translate the complete verifier lifecycle."""

    try:
        return _verify_planet_roots_once(
            broad_stream,
            production_stage_dir,
            work_dir,
            bindings,
            production_stream=production_stream,
            production_input_format=production_input_format,
            input_format=input_format,
            profile=profile,
            limits=limits,
            workers=workers,
        )
    except VerificationError:
        raise
    except MemoryError as error:
        raise VerificationError("independent verification memory allocation failed") from error
    except (OSError, OverflowError, struct.error, ValueError, UnicodeError) as error:
        raise VerificationError(f"independent verification failed: {error}") from error


__all__ = [
    "FIXTURE_BROAD_ENVELOPE_PROFILE",
    "GLOBAL_POLICY_SHA256",
    "LIVE_BROAD_ENVELOPE_CONVERTED_STREAM_FORMAT",
    "LIVE_BROAD_ENVELOPE_OPL_BYTES",
    "LIVE_BROAD_ENVELOPE_OPL_SHA256",
    "LIVE_BROAD_ENVELOPE_PBF_BYTES",
    "LIVE_BROAD_ENVELOPE_PBF_SHA256",
    "LIVE_BROAD_ENVELOPE_PROFILE",
    "LIVE_BROAD_ENVELOPE_RELATIONS",
    "LIVE_BROAD_ENVELOPE_WAYS",
    "LIVE_PLANET_SOURCE_SHA256",
    "VerificationBindings",
    "VerificationError",
    "VerificationLimits",
    "VerificationResult",
    "verify_planet_roots",
]
