from __future__ import annotations

import hashlib
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
from typing import BinaryIO, Mapping


FIXTURE_NAME_ENVELOPE_PROFILE = (
    "flight-alert-exp8-osm-planet-name-envelope-fixture-v1"
)
LIVE_NAME_ENVELOPE_PROFILE = (
    "flight-alert-exp8-osm-planet-name-envelope-260629-v1"
)
LIVE_PLANET_SOURCE_SHA256 = (
    "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
)
LIVE_NAME_ENVELOPE_PBF_BYTES = 419_750_356
LIVE_NAME_ENVELOPE_PBF_SHA256 = (
    "ffb68c03d8fa2710bfd664dfd4ce43c01cc2fdbbb92599b4d892bd3bc0661b4d"
)
LIVE_NAME_ENVELOPE_OPL_BYTES = 4_347_353_464
LIVE_NAME_ENVELOPE_OPL_SHA256 = (
    "628622248814b1a83727cf19bd7e22cc4ad66b61589c6f137585fd555910785b"
)
LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT = "opl"
LIVE_NAME_ENVELOPE_WAYS = 5_301_765
LIVE_NAME_ENVELOPE_RELATIONS = 135_237

_SUMMARY_SCHEMA = "flight-alert-exp8-osm-planet-selection-summary-v2"
_POLICY_SCHEMA = "flight-alert-exp8-osm-planet-root-policy-v1"
_ROOT_DOMAIN = b"FAE8OSMROOT1\0"
_BUCKET_DOMAIN = b"FAE8OSMBUCKET1\0"
_REJECTION_DOMAIN = b"FAE8OSMREJ1\0"
_MAX_SIGNED_63 = (1 << 63) - 1
_MAX_U32 = (1 << 32) - 1
_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_POSITIVE_DECIMAL = re.compile(r"[1-9][0-9]*\Z")
_NONNEGATIVE_DECIMAL = re.compile(r"(?:0|[1-9][0-9]*)\Z")
_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z"
)
_LANGUAGE_NAME_KEY = re.compile(
    r"name:([A-Za-z]{2,3})(?:-([A-Za-z0-9]{2,8}))*\Z"
)
_ALLOWED_WATERWAY_VALUES = (
    "river",
    "stream",
    "canal",
    "tidal_channel",
    "wadi",
)
_DIRECT_NAME_KEYS = ("name", "int_name", "official_name")
_NON_LANGUAGE_SUFFIXES = (
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
_FALSE_TAG_VALUES = ("", "0", "false", "no")
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
_MEMBER_KINDS = {"n": 0, "w": 1, "r": 2}
_HEX_DIGITS = frozenset("0123456789abcdefABCDEF")
_DIRECT_OPL_SPECIAL = frozenset(" ,=@")
_FINAL_FILENAMES = (
    "root-ids.txt",
    *(f"roots-{bucket:03d}.bin" for bucket in range(256)),
    "selected-tuples.bin",
    "selection-summary.json",
)
_FINAL_FILENAME_SET = frozenset(_FINAL_FILENAMES)


class SelectionError(ValueError):
    """The streamed candidate or its bounded selection artifacts are invalid."""


def _canonical_json_bytes(document: Mapping[str, object]) -> bytes:
    try:
        encoded = json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        return (encoded + "\n").encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError) as error:
        raise SelectionError(f"canonical JSON encoding failed: {error}") from error


def canonical_policy_bytes() -> bytes:
    """Return the exact global root-policy v1 identity bytes."""

    document = {
        "allowedDirectWaterwayValues": list(_ALLOWED_WATERWAY_VALUES),
        "areaFalseValues": list(_FALSE_TAG_VALUES),
        "displayNameDirectKeys": list(_DIRECT_NAME_KEYS),
        "displayNameLanguagePattern": _LANGUAGE_NAME_KEY.pattern,
        "falseTagValues": list(_FALSE_TAG_VALUES),
        "lifecycleKeyRules": ["state", "state:waterway", "waterway:state"],
        "lifecycleStates": list(_LIFECYCLE_STATES),
        "nonLanguageNameSuffixes": list(_NON_LANGUAGE_SUFFIXES),
        "nonNfcHandling": "preserve_exact_and_audit_only",
        "relationRejectionPrecedence": [list(item) for item in _RELATION_REASONS],
        "relationType": "waterway",
        "schema": _POLICY_SCHEMA,
        "wayGeometry": {"minimumNodeRefs": 2, "open": True},
        "wayRejectionPrecedence": [list(item) for item in _WAY_REASONS],
    }
    return _canonical_json_bytes(document)


GLOBAL_POLICY_SHA256 = hashlib.sha256(canonical_policy_bytes()).hexdigest()


def _sha256_identity(value: str, label: str) -> str:
    if type(value) is not str or _SHA256.fullmatch(value) is None:
        raise SelectionError(f"{label} must be a lowercase SHA-256 identity")
    return value


@dataclass(frozen=True, slots=True)
class SelectionBindings:
    planet_source_sha256: str
    candidate_pbf_bytes: int
    candidate_pbf_sha256: str
    converted_stream_bytes: int
    converted_stream_sha256: str
    converted_stream_format: str
    runtime_sha256: str
    policy_sha256: str
    code_sha256: str

    def __post_init__(self) -> None:
        for label, value in (
            ("planet source SHA-256", self.planet_source_sha256),
            ("candidate PBF SHA-256", self.candidate_pbf_sha256),
            ("converted stream SHA-256", self.converted_stream_sha256),
            ("runtime SHA-256", self.runtime_sha256),
            ("policy SHA-256", self.policy_sha256),
            ("code SHA-256", self.code_sha256),
        ):
            _sha256_identity(value, label)
        for label, value in (
            ("candidate PBF byte count", self.candidate_pbf_bytes),
            ("converted stream byte count", self.converted_stream_bytes),
        ):
            if type(value) is not int or value < 0:
                raise SelectionError(f"{label} must be a nonnegative integer")
        if self.converted_stream_format not in {"opl", "xml"}:
            raise SelectionError("converted stream format must be exactly 'opl' or 'xml'")


@dataclass(frozen=True, slots=True)
class SelectionLimits:
    max_input_bytes: int = 5 * 1024 * 1024 * 1024
    max_line_bytes: int = 64 * 1024 * 1024
    max_objects: int = 5_500_000
    max_total_tags: int = 100_000_000
    max_total_references: int = 250_000_000
    max_tags_per_object: int = 65_535
    max_references_per_object: int = 10_000_000
    max_text_utf8_bytes: int = 65_535
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
        ):
            if type(value) is not int or value < 0:
                raise SelectionError(f"{label} ceiling must be a nonnegative integer")
        if type(self.read_chunk_bytes) is not int or self.read_chunk_bytes <= 0:
            raise SelectionError("read chunk size must be a positive integer")


@dataclass(frozen=True, slots=True)
class SelectionResult:
    stage_dir: Path
    summary_path: Path
    selected_tuple_path: Path
    root_ids_path: Path
    summary_bytes: int
    summary_sha256: str
    selected_way_count: int
    selected_relation_count: int
    rejection_ledger_sha256: str
    planet_source_sha256: str
    candidate_pbf_bytes: int
    candidate_pbf_sha256: str
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
class _Object:
    kind: int
    object_id: int
    version: int
    epoch_seconds: int
    tags: tuple[tuple[str, str], ...]
    references: tuple[int, ...]
    members: tuple[_Member, ...]


@dataclass(slots=True)
class _InputState:
    sha256: object
    bytes: int = 0
    records: int = 0
    ways: int = 0
    relations: int = 0
    tags: int = 0
    references: int = 0


def _positive_integer(raw: str, maximum: int, label: str) -> int:
    maximum_text = str(maximum)
    if type(raw) is not str or not raw or raw[0] == "0":
        raise SelectionError(f"{label} must use canonical positive decimal syntax")
    if len(raw) > len(maximum_text):
        raise SelectionError(f"{label} exceeds its positive integer ceiling")
    if not raw.isascii() or not raw.isdigit():
        raise SelectionError(f"{label} must use canonical positive decimal syntax")
    if len(raw) == len(maximum_text) and raw > maximum_text:
        raise SelectionError(f"{label} exceeds its positive integer ceiling")
    try:
        return int(raw)
    except (ValueError, MemoryError) as error:
        raise SelectionError(f"{label} could not be converted to a bounded integer") from error


def _nonnegative_integer(raw: str, maximum: int, label: str) -> int:
    maximum_text = str(maximum)
    if type(raw) is not str or not raw or (len(raw) > 1 and raw[0] == "0"):
        raise SelectionError(f"{label} must use canonical nonnegative decimal syntax")
    if len(raw) > len(maximum_text):
        raise SelectionError(f"{label} exceeds its integer ceiling")
    if not raw.isascii() or not raw.isdigit():
        raise SelectionError(f"{label} must use canonical nonnegative decimal syntax")
    if len(raw) == len(maximum_text) and raw > maximum_text:
        raise SelectionError(f"{label} exceeds its integer ceiling")
    try:
        return int(raw)
    except (ValueError, MemoryError) as error:
        raise SelectionError(f"{label} could not be converted to a bounded integer") from error


def _timestamp_epoch(raw: str, label: str) -> int:
    if _TIMESTAMP.fullmatch(raw) is None:
        raise SelectionError(f"{label} timestamp must be canonical UTC")
    try:
        parsed = datetime.strptime(raw, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as error:
        raise SelectionError(f"{label} timestamp is not a real UTC instant") from error
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    delta = parsed - epoch
    return delta.days * 86_400 + delta.seconds


def _check_text(value: str, label: str, limits: SelectionLimits) -> str:
    try:
        encoded = value.encode("utf-8")
    except UnicodeEncodeError as error:
        raise SelectionError(f"{label} is not valid Unicode text") from error
    if len(encoded) > limits.max_text_utf8_bytes:
        raise SelectionError(f"{label} exceeds the text UTF-8 byte ceiling")
    if "\x00" in value:
        raise SelectionError(f"{label} contains a forbidden NUL")
    return value


def _decode_opl_string(raw: str, label: str, limits: SelectionLimits) -> str:
    """Decode Osmium `%` + 1..6 hex digits + `%` escapes.

    Each escape must resolve to a Unicode scalar (U+0000..U+10FFFF excluding
    surrogates); the normal OSM text rule then rejects U+0000. OPL syntax
    characters must be escaped, and the undefined direct `%%` form is rejected.
    """

    output = bytearray()
    index = 0
    while index < len(raw):
        character = raw[index]
        if character != "%":
            if character in _DIRECT_OPL_SPECIAL:
                raise SelectionError(f"{label} contains a direct ambiguous OPL character")
            try:
                encoded = character.encode("utf-8")
            except UnicodeEncodeError as error:
                raise SelectionError(f"{label} is not valid Unicode text") from error
            if len(output) + len(encoded) > limits.max_text_utf8_bytes:
                raise SelectionError(f"{label} exceeds the text UTF-8 byte ceiling")
            output.extend(encoded)
            index += 1
            continue
        cursor = index + 1
        codepoint = 0
        digits = 0
        while cursor < len(raw) and raw[cursor] != "%" and digits <= 6:
            digit = raw[cursor]
            if digit not in _HEX_DIGITS:
                raise SelectionError(f"{label} contains a malformed OPL escape")
            codepoint = (codepoint << 4) | int(digit, 16)
            digits += 1
            cursor += 1
        if cursor >= len(raw):
            raise SelectionError(f"{label} contains a truncated OPL escape")
        if digits < 1 or digits > 6:
            raise SelectionError(f"{label} contains a malformed OPL escape")
        if codepoint > 0x10FFFF or 0xD800 <= codepoint <= 0xDFFF:
            raise SelectionError(f"{label} OPL escape is not a Unicode scalar value")
        try:
            encoded = chr(codepoint).encode("utf-8")
        except (ValueError, UnicodeEncodeError) as error:
            raise SelectionError(f"{label} OPL escape is not a Unicode scalar value") from error
        if len(output) + len(encoded) > limits.max_text_utf8_bytes:
            raise SelectionError(f"{label} exceeds the text UTF-8 byte ceiling")
        output.extend(encoded)
        index = cursor + 1
    try:
        decoded = bytes(output).decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise SelectionError(f"{label} is not valid Unicode text") from error
    return _check_text(decoded, label, limits)


def _bounded_segments(raw: str, delimiter: str, ceiling: int, label: str):
    if raw == "":
        return
    start = 0
    count = 0
    for index, character in enumerate(raw):
        if character != delimiter:
            continue
        count += 1
        if count > ceiling:
            raise SelectionError(f"{label} exceeds its per-object ceiling")
        yield raw[start:index]
        start = index + 1
    count += 1
    if count > ceiling:
        raise SelectionError(f"{label} exceeds its per-object ceiling")
    yield raw[start:]


def _parse_tags(
    raw: str,
    label: str,
    limits: SelectionLimits,
    total_budget: int | None = None,
) -> tuple[tuple[str, str], ...]:
    if not raw:
        return ()
    tags: dict[str, str] = {}
    try:
        ceiling = limits.max_tags_per_object
        ceiling_message = f"{label} exceeds the per-object tag ceiling"
        if total_budget is not None and total_budget < ceiling:
            ceiling = total_budget
            ceiling_message = f"OPL input exceeds the tag ceiling {limits.max_total_tags}"
        pairs = _bounded_segments(
            raw,
            ",",
            ceiling,
            f"{label} tag",
        )
        assert pairs is not None
        for pair in pairs:
            separator = pair.find("=")
            if separator < 0 or pair.find("=", separator + 1) >= 0:
                raise SelectionError(f"{label} contains a malformed tag pair")
            encoded_key = pair[:separator]
            encoded_value = pair[separator + 1 :]
            key = _decode_opl_string(encoded_key, f"{label} tag key", limits)
            value = _decode_opl_string(encoded_value, f"{label} tag value", limits)
            if not key:
                raise SelectionError(f"{label} contains an empty tag key")
            if key in tags:
                raise SelectionError(f"{label} contains duplicate decoded tag {key!r}")
            tags[key] = value
    except SelectionError as error:
        if "tag exceeds its per-object ceiling" in str(error):
            raise SelectionError(ceiling_message) from error
        raise
    return tuple(sorted(tags.items(), key=lambda item: item[0].encode("utf-8")))


def _parse_refs(
    raw: str,
    label: str,
    limits: SelectionLimits,
    total_budget: int | None = None,
) -> tuple[int, ...]:
    if not raw:
        return ()
    result: list[int] = []
    try:
        ceiling = limits.max_references_per_object
        ceiling_message = f"{label} exceeds the per-object reference ceiling"
        if total_budget is not None and total_budget < ceiling:
            ceiling = total_budget
            ceiling_message = (
                f"OPL input exceeds the reference ceiling {limits.max_total_references}"
            )
        encoded_refs = _bounded_segments(
            raw,
            ",",
            ceiling,
            f"{label} reference",
        )
        assert encoded_refs is not None
        for encoded in encoded_refs:
            if not encoded.startswith("n"):
                raise SelectionError(f"{label} contains a malformed node reference")
            result.append(
                _positive_integer(encoded[1:], _MAX_SIGNED_63, f"{label} node reference")
            )
    except SelectionError as error:
        if "reference exceeds its per-object ceiling" in str(error):
            raise SelectionError(ceiling_message) from error
        raise
    return tuple(result)


def _parse_members(
    raw: str,
    label: str,
    limits: SelectionLimits,
    total_budget: int | None = None,
) -> tuple[_Member, ...]:
    if not raw:
        return ()
    result: list[_Member] = []
    try:
        ceiling = limits.max_references_per_object
        ceiling_message = f"{label} exceeds the per-object reference ceiling"
        if total_budget is not None and total_budget < ceiling:
            ceiling = total_budget
            ceiling_message = (
                f"OPL input exceeds the reference ceiling {limits.max_total_references}"
            )
        encoded_members = _bounded_segments(
            raw,
            ",",
            ceiling,
            f"{label} reference",
        )
        assert encoded_members is not None
        for ordinal, encoded in enumerate(encoded_members):
            separator = encoded.find("@", 1)
            if not encoded or encoded[0] not in _MEMBER_KINDS or separator < 0:
                raise SelectionError(f"{label} contains a malformed relation member")
            encoded_ref = encoded[1:separator]
            encoded_role = encoded[separator + 1 :]
            result.append(
                _Member(
                    kind=_MEMBER_KINDS[encoded[0]],
                    ref=_positive_integer(
                        encoded_ref, _MAX_SIGNED_63, f"{label} member reference"
                    ),
                    role=_decode_opl_string(encoded_role, f"{label} member role", limits),
                    ordinal=ordinal,
                )
            )
    except SelectionError as error:
        if "reference exceeds its per-object ceiling" in str(error):
            raise SelectionError(ceiling_message) from error
        raise
    return tuple(result)


def _space_tokens(raw: bytes, line_number: int):
    start = 0
    count = 0
    for index, value in enumerate(raw):
        if value != 0x20:
            continue
        if index == start:
            raise SelectionError(f"OPL line {line_number} must use single-space separators")
        count += 1
        if count > 9:
            raise SelectionError(f"OPL line {line_number} contains too many attributes")
        yield raw[start:index]
        start = index + 1
    if start == len(raw):
        raise SelectionError(f"OPL line {line_number} must use single-space separators")
    count += 1
    if count > 9:
        raise SelectionError(f"OPL line {line_number} contains too many attributes")
    yield raw[start:]


def _parse_opl_line(
    raw: bytes,
    line_number: int,
    limits: SelectionLimits,
    *,
    tag_budget: int | None = None,
    reference_budget: int | None = None,
) -> _Object:
    if not raw:
        raise SelectionError(f"OPL line {line_number} is empty")
    if b"\r" in raw or b"\x00" in raw:
        raise SelectionError(f"OPL line {line_number} contains a forbidden byte")
    if any(value < 0x20 or value == 0x7F for value in raw):
        raise SelectionError(f"OPL line {line_number} contains a raw control character")
    tokens = _space_tokens(raw, line_number)
    try:
        head_raw = next(tokens)
        head = head_raw.decode("ascii", errors="strict")
    except (StopIteration, UnicodeDecodeError) as error:
        raise SelectionError(f"OPL line {line_number} contains an unsupported object kind") from error
    if len(head) < 2 or head[0] not in {"w", "r"}:
        raise SelectionError(f"OPL line {line_number} contains an unsupported object kind")
    kind = 1 if head[0] == "w" else 2
    object_id = _positive_integer(
        head[1:], _MAX_SIGNED_63, f"OPL line {line_number} object ID"
    )
    attributes: dict[str, str] = {}
    allowed = {"v", "d", "c", "t", "i", "u", "T", "N" if kind == 1 else "M"}
    for raw_field in tokens:
        try:
            field = raw_field.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise SelectionError(f"OPL line {line_number} is not strict UTF-8") from error
        key = field[0]
        if key not in allowed:
            raise SelectionError(f"OPL line {line_number} contains unknown attribute {key!r}")
        if key in attributes:
            raise SelectionError(f"OPL line {line_number} contains duplicate attribute {key!r}")
        attributes[key] = field[1:]
    reference_key = "N" if kind == 1 else "M"
    missing = {"v", "t", "T", reference_key}.difference(attributes)
    if missing:
        raise SelectionError(
            f"OPL line {line_number} is missing required attributes {sorted(missing)!r}"
        )
    if "d" in attributes and attributes["d"] != "V":
        raise SelectionError(f"OPL line {line_number} is not a visible current object")
    if "c" in attributes:
        _nonnegative_integer(
            attributes["c"], _MAX_SIGNED_63, f"OPL line {line_number} changeset"
        )
    if "i" in attributes:
        _nonnegative_integer(
            attributes["i"], _MAX_SIGNED_63, f"OPL line {line_number} user ID"
        )
    if "u" in attributes:
        _decode_opl_string(attributes["u"], f"OPL line {line_number} user", limits)
    version = _positive_integer(
        attributes["v"], _MAX_U32, f"OPL line {line_number} version"
    )
    epoch_seconds = _timestamp_epoch(
        attributes["t"], f"OPL line {line_number} object"
    )
    label = f"OPL line {line_number} object {head[0]}{object_id}"
    tags = _parse_tags(attributes["T"], label, limits, tag_budget)
    if kind == 1:
        references = _parse_refs(attributes["N"], label, limits, reference_budget)
        members: tuple[_Member, ...] = ()
    else:
        references = ()
        members = _parse_members(attributes["M"], label, limits, reference_budget)
    return _Object(
        kind=kind,
        object_id=object_id,
        version=version,
        epoch_seconds=epoch_seconds,
        tags=tags,
        references=references,
        members=members,
    )


def _is_supported_name_key(key: str) -> bool:
    if key in _DIRECT_NAME_KEYS:
        return True
    match = _LANGUAGE_NAME_KEY.fullmatch(key)
    if match is None:
        return False
    suffix = key[5:]
    start = 0
    while True:
        end = suffix.find("-", start)
        if end < 0:
            return suffix[start:].casefold() not in _NON_LANGUAGE_SUFFIXES
        if suffix[start:end].casefold() in _NON_LANGUAGE_SUFFIXES:
            return False
        start = end + 1


def _rejection_reason(value: _Object) -> int:
    tags = dict(value.tags)
    supported_values = [
        tag_value for key, tag_value in value.tags if _is_supported_name_key(key)
    ]
    if value.kind == 1:
        if tags.get("waterway") not in _ALLOWED_WATERWAY_VALUES:
            return 1
        if len(value.references) < 2:
            return 2
        if value.references[0] == value.references[-1]:
            return 3
        if tags.get("area", "").strip().casefold() not in _FALSE_TAG_VALUES:
            return 4
        for state in _LIFECYCLE_STATES:
            if tags.get(state, "").strip().casefold() not in _FALSE_TAG_VALUES:
                return 5
            if f"{state}:waterway" in tags or f"waterway:{state}" in tags:
                return 5
        if not supported_values:
            return 6
        if not any(value.strip() for value in supported_values):
            return 7
        return 0
    if tags.get("type") != "waterway":
        return 8
    if not supported_values:
        return 9
    if not any(value.strip() for value in supported_values):
        return 10
    return 0


def _encode_text(output: bytearray, value: str) -> None:
    raw = value.encode("utf-8")
    if len(raw) > _MAX_U32:
        raise SelectionError("canonical object text exceeds the u32 byte ceiling")
    output.extend(struct.pack("<I", len(raw)))
    output.extend(raw)


def _object_payload(value: _Object) -> bytes:
    output = bytearray()
    output.extend(struct.pack("<BQI", value.kind, value.object_id, value.version))
    output.extend(struct.pack("<q", value.epoch_seconds))
    output.extend(struct.pack("<I", len(value.tags)))
    for key, tag_value in value.tags:
        _encode_text(output, key)
        _encode_text(output, tag_value)
    if value.kind == 1:
        output.extend(struct.pack("<I", len(value.references)))
        for reference in value.references:
            output.extend(struct.pack("<Q", reference))
    else:
        output.extend(struct.pack("<I", len(value.members)))
        for member in value.members:
            output.extend(
                struct.pack("<IBQ", member.ordinal, member.kind, member.ref)
            )
            _encode_text(output, member.role)
    if len(output) + 32 > _MAX_U32:
        raise SelectionError("canonical object record exceeds the u32 frame ceiling")
    return bytes(output)


def _non_nfc_counts(value: _Object) -> tuple[int, int]:
    count = sum(
        unicodedata.normalize("NFC", field) != field
        for item in value.tags
        for field in item
    ) + sum(
        unicodedata.normalize("NFC", member.role) != member.role
        for member in value.members
    )
    return count, int(count > 0)


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
            raise SelectionError(
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


def _file_id(status: os.stat_result) -> tuple[int, int]:
    return status.st_dev, status.st_ino


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
            raise SelectionError(
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
        raise SelectionError(f"{label} held handle open failed: {error}") from error
    try:
        return os.fdopen(fd, "rb")
    except BaseException:
        os.close(fd)
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
            raise SelectionError(f"{label} held identity is not exact")
        handle.seek(0)
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            byte_length += len(chunk)
            digest.update(chunk)
        after = os.fstat(handle.fileno())
        path_after = os.stat(path, follow_symlinks=False)
    except SelectionError:
        raise
    except (OSError, ValueError) as error:
        raise SelectionError(f"{label} held readback failed: {error}") from error
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
        raise SelectionError(f"{label} changed during held readback")
    return _HeldFileSnapshot(
        file_id=_file_id(after),
        link_count=after.st_nlink,
        size=after.st_size,
        mtime_ns=after.st_mtime_ns,
        ctime_ns=after.st_ctime_ns,
        byte_length=byte_length,
        sha256=digest.hexdigest(),
    )


def _cleanup_fd_status(fd: int) -> os.stat_result:
    try:
        return os.fstat(fd)
    except MemoryError:
        return os.stat(fd)


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
        raise SelectionError(
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
            raise SelectionError(
                f"{label} native link-count query failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        if standard.number_of_links != 1 or _cleanup_fd_status(fd).st_nlink != 1:
            raise SelectionError(
                f"{label} no longer has exactly one tracked link; preserved path"
            )
    _mark_windows_native_handle_for_delete(native_handle, label)


def _open_windows_delete_fd(path: Path, label: str) -> int:
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
    handle = create_file(
        str(path),
        0x00010000 | 0x00000080 | 0x00100000,
        0x00000001 | 0x00000002 | 0x00000004,
        None,
        3,
        0x00200000,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise SelectionError(
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


def _unlink_exact_owned_file(
    path: Path,
    expected_file_id: tuple[int, int],
    *,
    require_single_link: bool = True,
) -> None:
    label = f"owned artifact {path.name}"
    try:
        status = os.stat(path, follow_symlinks=False)
    except FileNotFoundError as error:
        raise SelectionError(
            f"{label} is missing or moved; unreleased FileId "
            f"{expected_file_id[0]}:{expected_file_id[1]}"
        ) from error
    except OSError as error:
        raise SelectionError(f"inspect {path.name}: {error}") from error
    if (
        not stat_module.S_ISREG(status.st_mode)
        or _is_reparse(status)
        or _file_id(status) != expected_file_id
    ):
        raise SelectionError(
            f"{label} identity changed; preserved replacement"
        )

    if os.name == "nt":
        fd = _open_windows_delete_fd(path, label)
        operation_error: BaseException | None = None
        try:
            pinned = _cleanup_fd_status(fd)
            if (
                not stat_module.S_ISREG(pinned.st_mode)
                or _is_reparse(pinned)
                or _file_id(pinned) != expected_file_id
            ):
                raise SelectionError(
                    f"{label} identity changed before exact deletion; "
                    "preserved replacement"
                )
            _mark_windows_fd_for_delete(
                fd,
                label,
                require_single_link=require_single_link,
            )
        except BaseException as error:
            operation_error = error
        try:
            os.close(fd)
        except OSError as close_error:
            if operation_error is None:
                operation_error = SelectionError(
                    f"{label} exact delete handle close failed: {close_error}"
                )
        if operation_error is not None:
            raise operation_error
        return

    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(path, flags)
    except OSError as error:
        raise SelectionError(f"{label} exact handle open failed: {error}") from error
    quarantine = path.parent / f".{path.name}.{secrets.token_hex(16)}.cleanup"
    try:
        pinned = _cleanup_fd_status(fd)
        pinned_links = pinned.st_nlink
        if (
            not stat_module.S_ISREG(pinned.st_mode)
            or _file_id(pinned) != expected_file_id
            or (require_single_link and pinned_links != 1)
        ):
            raise SelectionError(
                f"{label} identity or link count changed; preserved replacement"
            )
        os.rename(path, quarantine)
        captured = os.stat(quarantine, follow_symlinks=False)
        if (
            not stat_module.S_ISREG(captured.st_mode)
            or _file_id(captured) != expected_file_id
            or _file_id(_cleanup_fd_status(fd)) != expected_file_id
            or captured.st_nlink != pinned_links
        ):
            if not os.path.lexists(path):
                try:
                    os.link(quarantine, path, follow_symlinks=False)
                except OSError:
                    pass
            raise SelectionError(
                f"{label} changed during quarantine; preserved captured path"
            )
        os.unlink(quarantine)
        if _cleanup_fd_status(fd).st_nlink != pinned_links - 1:
            raise SelectionError(
                f"{label} link count changed during cleanup"
            )
    except SelectionError:
        raise
    except OSError as error:
        raise SelectionError(f"{label} exact deletion failed: {error}") from error
    finally:
        os.close(fd)


class _UnreleasedAtomicFile(SelectionError):
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
                    f"atomic artifact {self.path.name} retry",
                    require_single_link=False,
                )
            else:
                _delete_posix_created_fd(
                    self.path,
                    self.fd,
                    f"atomic artifact {self.path.name} retry",
                )
        elif self.native_handle:
            import ctypes
            from ctypes import wintypes

            _mark_windows_native_handle_for_delete(
                wintypes.HANDLE(self.native_handle),
                f"atomic artifact {self.path.name} retry",
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
                raise SelectionError(
                    f"atomic artifact {self.path.name} retained secondary native "
                    f"handle: {OSError(number, ctypes.FormatError(number))}"
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
                raise SelectionError(
                    f"atomic artifact {self.path.name} retained native handle: "
                    f"{OSError(number, ctypes.FormatError(number))}"
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
            raise SelectionError(
                f"{label} identity changed; retained exact creation fd"
            )
        os.unlink(path)
        if os.fstat(fd).st_nlink != 0:
            raise SelectionError(
                f"{label} acquired an untracked link during cleanup"
            )
    except SelectionError:
        raise
    except OSError as error:
        raise SelectionError(f"{label} exact cleanup failed: {error}") from error


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
            raise SelectionError(
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
                raise SelectionError(
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
            primary = SelectionError(
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
        raise SelectionError(f"{label} atomic creation failed: {error}") from error
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
        raise SelectionError(
            f"{label} creation guardian release failed: {primary}"
        ) from primary
    return output


class _Artifacts:
    def __init__(self, stage_dir: Path) -> None:
        self.stage_dir = stage_dir
        self._nonce = secrets.token_hex(16)
        self._temporary: dict[str, Path] = {
            name: stage_dir / f".{name}.{self._nonce}.tmp" for name in _FINAL_FILENAMES
        }
        self._owned_temporary: set[Path] = set()
        self._owned_final: set[Path] = set()
        self._owned_file_ids: dict[Path, tuple[int, int]] = {}
        self._handles: dict[str, _TrackedWriter] = {}
        self._guardians: dict[Path, BinaryIO] = {}
        self._sealed_handles: dict[Path, BinaryIO] = {}
        self._sealed_snapshots: dict[Path, _HeldFileSnapshot] = {}
        self._final_handles: dict[Path, BinaryIO] = {}
        self._committed_snapshots: dict[Path, _HeldFileSnapshot] = {}
        self._states: dict[Path, str] = {}
        self._pending_path: Path | None = None
        self._pending_file_id: tuple[int, int] | None = None
        self._pending_writer: _TrackedWriter | BinaryIO | None = None
        self._pending_guardian: BinaryIO | None = None
        self._pending_deleted = False
        try:
            self._remember_handle(
                "root-ids.txt",
                self._create_temporary("root-ids.txt"),
            )
            self._remember_handle(
                "selected-tuples.bin",
                self._create_temporary("selected-tuples.bin"),
            )
            self.bucket_counts = [0] * 256
        except BaseException as primary:
            try:
                self.cleanup()
            except SelectionError as cleanup_error:
                raise cleanup_error from primary
            raise

    def __del__(self) -> None:
        collections = (
            getattr(self, "_handles", {}),
            getattr(self, "_guardians", {}),
            getattr(self, "_sealed_handles", {}),
            getattr(self, "_final_handles", {}),
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

    @staticmethod
    def _same_content(
        first: _HeldFileSnapshot,
        second: _HeldFileSnapshot,
    ) -> bool:
        return (
            first.file_id == second.file_id
            and first.size == second.size
            and first.byte_length == second.byte_length
            and first.sha256 == second.sha256
        )

    def _remember_handle(
        self,
        name: str,
        handle: _TrackedWriter,
    ) -> _TrackedWriter:
        try:
            self._handles[name] = handle
        except BaseException as primary:
            try:
                handle.close()
            except OSError as close_error:
                raise SelectionError(
                    f"selection cleanup failed after handle tracking failure: {close_error}"
                ) from primary
            raise
        return handle

    def _create_temporary(self, name: str) -> _TrackedWriter:
        if self._pending_path is not None:
            raise SelectionError(
                "a previous temporary ownership failure remains unreleased"
            )
        path = self._temporary[name]
        raw_handle: BinaryIO | None = _create_atomic_binary_writer(
            path,
            f"artifact {name}",
        )
        handle: _TrackedWriter | None = None
        guardian: BinaryIO | None = None
        file_id: tuple[int, int] | None = None
        try:
            self._pending_path = path
            self._pending_writer = raw_handle
            self._pending_deleted = False
            file_id = _file_id(os.fstat(raw_handle.fileno()))
            self._pending_file_id = file_id
            handle = _TrackedWriter(raw_handle, path.name, path)
            self._pending_writer = handle
            raw_handle = None
            guardian = _open_held_reader(
                path,
                f"artifact {name} creation guardian",
                deny_write=False,
                deny_delete=False,
            )
            guardian_status = os.fstat(guardian.fileno())
            path_status = os.stat(path, follow_symlinks=False)
            if (
                not stat_module.S_ISREG(guardian_status.st_mode)
                or _is_reparse(guardian_status)
                or _file_id(guardian_status) != file_id
                or _file_id(path_status) != file_id
            ):
                raise SelectionError(
                    f"artifact {name} identity changed while retaining its creation handle"
                )
            self._pending_guardian = guardian
            self._owned_temporary.add(path)
            self._owned_file_ids[path] = file_id
            self._states[path] = "WRITING"
            self._guardians[path] = guardian
            guardian = None
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
                        raise SelectionError(
                            f"artifact {name} lost its atomic creation handle"
                        )
                    file_id = _file_id(
                        _cleanup_fd_status(pending_writer.fileno())
                    )
                except BaseException as identity_error:
                    raise SelectionError(
                        "selection cleanup failed after temporary tracking failure: "
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
                        f"artifact {name} recovery guardian",
                        deny_write=False,
                        deny_delete=False,
                    )
                    recovered_status = _cleanup_fd_status(
                        recovered_guardian.fileno()
                    )
                    if _file_id(recovered_status) != file_id:
                        raise SelectionError(
                            f"artifact {name} recovery guardian is not exact"
                        )
                    self._pending_guardian = recovered_guardian
                except BaseException as guardian_error:
                    if "recovered_guardian" in locals():
                        recovered_guardian.close()
                    raise SelectionError(
                        "selection cleanup failed after temporary tracking failure: "
                        f"retain {path.name}: {guardian_error}"
                    ) from error
            cleanup_failures = self._cleanup_pending_creation()
            if cleanup_failures:
                raise SelectionError(
                    "selection cleanup failed after temporary tracking failure: "
                    + "; ".join(cleanup_failures)
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
                _unlink_exact_owned_file(path, expected_file_id)
            except SelectionError as error:
                failures.append(f"unlink {path.name}: {error}")
                return failures
            proof_handle = self._pending_guardian or self._pending_writer
            if proof_handle is None:
                failures.append(
                    f"pending artifact {path.name} lost its exact cleanup handle"
                )
                return failures
            try:
                remaining_links = _cleanup_fd_status(proof_handle.fileno()).st_nlink
            except (OSError, ValueError) as error:
                failures.append(f"recheck pending {path.name}: {error}")
                return failures
            if remaining_links != 0:
                failures.append(
                    f"pending artifact {path.name} retained {remaining_links} "
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
            self._owned_temporary.discard(path)
            self._owned_final.discard(path)
            self._owned_file_ids.pop(path, None)
            self._states.pop(path, None)
            self._guardians.pop(path, None)
            self._pending_path = None
            self._pending_file_id = None
            self._pending_deleted = False
        return failures

    def _handle(self, name: str) -> _TrackedWriter:
        handle = self._handles.get(name)
        if handle is None:
            handle = self._remember_handle(name, self._create_temporary(name))
        return handle

    def write_selected(self, value: _Object, payload: bytes, digest: bytes) -> None:
        bucket = hashlib.sha256(
            _BUCKET_DOMAIN + bytes((value.kind,)) + struct.pack("<Q", value.object_id)
        ).digest()[0]
        frame = struct.pack("<I", len(payload) + 32) + payload + digest
        self._handle(f"roots-{bucket:03d}.bin").write(frame)
        self.bucket_counts[bucket] += 1
        self._handles["selected-tuples.bin"].write(
            struct.pack("<BQI32s", value.kind, value.object_id, value.version, digest)
        )
        prefix = "w" if value.kind == 1 else "r"
        self._handles["root-ids.txt"].write(
            f"{prefix}{value.object_id}\n".encode("ascii")
        )

    def _seal_temporary(self, name: str) -> _HeldFileSnapshot:
        path = self._temporary[name]
        if self._states.get(path) != "WRITING":
            raise SelectionError(
                f"artifact {name} cannot seal from state {self._states.get(path)!r}"
            )
        writer = self._handles.get(name)
        guardian = self._guardians.get(path)
        expected_file_id = self._owned_file_ids.get(path)
        if writer is None or guardian is None or expected_file_id is None:
            raise SelectionError(f"artifact {name} writing ownership is incomplete")
        try:
            writer.flush()
            os.fsync(writer.fileno())
            writing_snapshot = _held_file_snapshot(
                guardian,
                path,
                f"artifact {name} WRITING snapshot",
            )
            if (
                writing_snapshot.file_id != expected_file_id
                or writing_snapshot.link_count != 1
                or writing_snapshot.byte_length != writer.expected_bytes
                or writing_snapshot.sha256 != writer.expected_sha256
            ):
                raise SelectionError(
                    f"artifact {name} changed before its held WRITING readback"
                )
            writer.close()
            self._handles.pop(name, None)
            sealed_handle = _open_held_reader(
                path,
                f"artifact {name} SEALED pin",
                deny_write=True,
                deny_delete=False,
            )
            try:
                sealed_snapshot = _held_file_snapshot(
                    sealed_handle,
                    path,
                    f"artifact {name} SEALED snapshot",
                )
                if writing_snapshot != sealed_snapshot:
                    raise SelectionError(
                        f"artifact {name} drifted while transitioning WRITING to SEALED"
                    )
                self._sealed_handles[path] = sealed_handle
                self._sealed_snapshots[path] = sealed_snapshot
                self._states[path] = "SEALED"
                sealed_handle = None
            finally:
                if sealed_handle is not None:
                    sealed_handle.close()
        except SelectionError:
            raise
        except (OSError, ValueError) as error:
            raise SelectionError(f"artifact {name} sealing failed: {error}") from error
        return self._sealed_snapshots[path]

    def finish_payloads(self) -> list[dict[str, object]]:
        for bucket in range(256):
            self._handle(f"roots-{bucket:03d}.bin")
        for name in list(self._handles):
            self._seal_temporary(name)
        inventory: list[dict[str, object]] = []
        for bucket in range(256):
            name = f"roots-{bucket:03d}.bin"
            snapshot = self._sealed_snapshots[self._temporary[name]]
            inventory.append(
                {
                    "bucket": bucket,
                    "bytes": snapshot.byte_length,
                    "filename": name,
                    "records": self.bucket_counts[bucket],
                    "sha256": snapshot.sha256,
                }
            )
        return inventory

    def write_summary(self, raw: bytes) -> None:
        path = self._temporary["selection-summary.json"]
        try:
            output = self._create_temporary("selection-summary.json")
            self._remember_handle("selection-summary.json", output)
            output.write(raw)
            snapshot = self._seal_temporary("selection-summary.json")
        except OSError as error:
            raise SelectionError(f"summary staging failed: {error}") from error
        if (
            snapshot.byte_length != len(raw)
            or snapshot.sha256 != hashlib.sha256(raw).hexdigest()
        ):
            raise SelectionError("summary readback did not reproduce staged bytes")

    def commit(self) -> None:
        for name in sorted(_FINAL_FILENAMES):
            source = self._temporary[name]
            destination = self.stage_dir / name
            source_id = self._owned_file_ids.get(source)
            source_handle = self._sealed_handles.get(source)
            sealed_snapshot = self._sealed_snapshots.get(source)
            guardian = self._guardians.get(source)
            if (
                source_id is None
                or source_handle is None
                or sealed_snapshot is None
                or guardian is None
                or self._states.get(source) != "SEALED"
            ):
                raise SelectionError(
                    f"artifact SEALED ownership is missing for {name}"
                )
            try:
                if _held_file_snapshot(
                    source_handle,
                    source,
                    f"artifact {name} SEALED commit snapshot",
                ) != sealed_snapshot:
                    raise SelectionError(
                        f"artifact {name} changed before SEALED commit"
                    )
                try:
                    os.link(source, destination, follow_symlinks=False)
                except OSError as error:
                    raise SelectionError(
                        f"artifact no-clobber commit failed for {name}: {error}"
                    ) from error
                try:
                    destination_status = os.stat(destination, follow_symlinks=False)
                    if (
                        not stat_module.S_ISREG(destination_status.st_mode)
                        or _is_reparse(destination_status)
                        or _file_id(destination_status) != source_id
                    ):
                        raise SelectionError(
                            f"artifact committed link identity changed for {name}"
                        )
                    self._owned_final.add(destination)
                    self._owned_file_ids[destination] = source_id
                except BaseException as primary:
                    self._owned_final.discard(destination)
                    self._owned_file_ids.pop(destination, None)
                    try:
                        self._compensate_created_final(
                            destination,
                            source_id,
                            source_handle,
                        )
                    except (OSError, SelectionError) as cleanup_error:
                        if isinstance(cleanup_error, OSError):
                            cleanup_error = SelectionError(
                                f"artifact compensation handle close failed for "
                                f"{name}: {cleanup_error}"
                            )
                        raise cleanup_error from primary
                    raise
                _unlink_exact_owned_file(
                    source,
                    source_id,
                    require_single_link=False,
                )
                self._owned_temporary.discard(source)
                self._owned_file_ids.pop(source, None)
                self._states.pop(source, None)
                self._guardians[destination] = guardian
                self._guardians.pop(source, None)
                committed_handle = _open_held_reader(
                    destination,
                    f"artifact {name} COMMITTED pin",
                    deny_write=True,
                    deny_delete=False,
                )
                try:
                    committed_snapshot = _held_file_snapshot(
                        committed_handle,
                        destination,
                        f"artifact {name} COMMITTED snapshot",
                    )
                    if not self._same_content(sealed_snapshot, committed_snapshot):
                        raise SelectionError(
                            f"artifact {name} bytes changed while transitioning "
                            "SEALED to COMMITTED"
                        )
                    if committed_snapshot.link_count != 1:
                        raise SelectionError(
                            f"artifact {name} COMMITTED with an untracked hard link"
                        )
                    self._final_handles[destination] = committed_handle
                    self._committed_snapshots[destination] = committed_snapshot
                    self._states[destination] = "COMMITTED"
                    committed_handle = None
                finally:
                    if committed_handle is not None:
                        committed_handle.close()
                self._sealed_handles.pop(source, None)
                self._sealed_snapshots.pop(source, None)
                source_handle.close()
            except OSError as error:
                raise SelectionError(
                    f"artifact temporary release failed for {name}: {error}"
                ) from error

    def _compensate_created_final(
        self,
        destination: Path,
        expected_file_id: tuple[int, int],
        proof_handle: BinaryIO,
    ) -> None:
        try:
            proof_status = os.fstat(proof_handle.fileno())
            destination_status = os.stat(destination, follow_symlinks=False)
        except FileNotFoundError as error:
            raise SelectionError(
                f"artifact exact-owned compensation lost {destination.name}; "
                f"unreleased FileId {expected_file_id[0]}:{expected_file_id[1]}"
            ) from error
        except OSError as error:
            raise SelectionError(
                f"artifact exact-owned compensation inspection failed for "
                f"{destination.name}: {error}"
            ) from error
        if (
            _file_id(proof_status) != expected_file_id
            or _file_id(destination_status) != expected_file_id
        ):
            return
        try:
            with destination.open("rb") as destination_handle:
                if (
                    _file_id(os.fstat(destination_handle.fileno()))
                    != expected_file_id
                    or not os.path.sameopenfile(
                        proof_handle.fileno(),
                        destination_handle.fileno(),
                    )
                ):
                    return
        except FileNotFoundError:
            return
        except OSError as error:
            raise SelectionError(
                f"artifact exact-owned compensation proof failed for "
                f"{destination.name}: {error}"
            ) from error
        try:
            _unlink_exact_owned_file(
                destination,
                expected_file_id,
                require_single_link=False,
            )
        except SelectionError as error:
            raise SelectionError(
                f"artifact exact-owned compensation failed for {destination.name}: {error}"
            ) from error

    def freeze_committed(self) -> None:
        for path in sorted(self._owned_final, key=lambda item: item.name):
            old_handle = self._final_handles.get(path)
            expected = self._committed_snapshots.get(path)
            if old_handle is None or expected is None:
                raise SelectionError(
                    f"COMMITTED terminal pin is missing for {path.name}"
                )
            frozen = _open_held_reader(
                path,
                f"artifact {path.name} terminal COMMITTED pin",
                deny_write=True,
                deny_delete=True,
            )
            try:
                frozen_snapshot = _held_file_snapshot(
                    frozen,
                    path,
                    f"artifact {path.name} terminal COMMITTED snapshot",
                )
                if frozen_snapshot != expected:
                    raise SelectionError(
                        f"artifact {path.name} drifted while entering terminal COMMITTED"
                    )
                self._final_handles[path] = frozen
                frozen = None
            finally:
                if frozen is not None:
                    frozen.close()
            old_handle.close()

    def release_ownership(self) -> None:
        close_failures: list[str] = []
        for path in sorted(list(self._owned_final), key=lambda item: item.name):
            final_handle = self._final_handles.get(path)
            if final_handle is not None:
                try:
                    final_handle.close()
                except OSError as error:
                    close_failures.append(f"close committed {path.name}: {error}")
                    continue
                else:
                    self._final_handles.pop(path, None)
            guardian = self._guardians.get(path)
            if guardian is not None:
                try:
                    guardian.close()
                except OSError as error:
                    close_failures.append(f"close guardian {path.name}: {error}")
                    continue
                else:
                    self._guardians.pop(path, None)
            if path not in self._final_handles and path not in self._guardians:
                self._committed_snapshots.pop(path, None)
                self._owned_file_ids.pop(path, None)
                self._states.pop(path, None)
                self._owned_final.discard(path)
        if close_failures:
            raise SelectionError(
                "selection final pin release failed: " + "; ".join(close_failures)
            )

    def cleanup(self) -> None:
        failures: list[str] = []
        failures.extend(self._cleanup_pending_creation())
        owned_guardian_ids = {
            id(handle)
            for path, handle in self._guardians.items()
            if path in self._owned_file_ids
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
        for name, handle in list(self._handles.items()):
            try:
                handle.close()
            except OSError as error:
                failures.append(f"close {getattr(handle, 'name', '<unknown>')}: {error}")
            else:
                self._handles.pop(name, None)
        for collection, label in (
            (self._final_handles, "committed"),
            (self._sealed_handles, "sealed"),
        ):
            for path, handle in list(collection.items()):
                try:
                    handle.close()
                except OSError as error:
                    failures.append(f"close {label} {path.name}: {error}")
                else:
                    collection.pop(path, None)
        owned_paths = {
            path
            for path in self._owned_temporary | self._owned_final
            if path != pending_path
        }
        for path in sorted(owned_paths, key=lambda item: item.name):
            expected_file_id = self._owned_file_ids.get(path)
            if expected_file_id is None:
                continue
            guardian = self._guardians.get(path)
            if guardian is None:
                for candidate in self._guardians.values():
                    try:
                        candidate_status = _cleanup_fd_status(candidate.fileno())
                    except (OSError, ValueError):
                        continue
                    if _file_id(candidate_status) == expected_file_id:
                        guardian = candidate
                        break
            if guardian is None:
                failures.append(
                    f"owned artifact {path.name} lost its exact cleanup guardian"
                )
                continue
            bound_paths: list[Path] = []
            for candidate in owned_paths:
                if self._owned_file_ids.get(candidate) != expected_file_id:
                    continue
                try:
                    candidate_status = os.stat(candidate, follow_symlinks=False)
                except OSError:
                    continue
                if (
                    stat_module.S_ISREG(candidate_status.st_mode)
                    and not _is_reparse(candidate_status)
                    and _file_id(candidate_status) == expected_file_id
                ):
                    bound_paths.append(candidate)
            try:
                held_links = _cleanup_fd_status(guardian.fileno()).st_nlink
            except (OSError, ValueError) as error:
                failures.append(f"inspect guardian {path.name}: {error}")
                continue
            if held_links != len(bound_paths):
                failures.append(
                    f"owned artifact {path.name} has {held_links} links but only "
                    f"{len(bound_paths)} tracked path bindings"
                )
                continue
            try:
                if len(bound_paths) == 1:
                    _unlink_exact_owned_file(path, expected_file_id)
                else:
                    _unlink_exact_owned_file(
                        path,
                        expected_file_id,
                        require_single_link=False,
                    )
            except SelectionError as error:
                failures.append(str(error))
                continue
            expected_remaining_links = len(bound_paths) - 1
            try:
                remaining_links = _cleanup_fd_status(guardian.fileno()).st_nlink
            except (OSError, ValueError) as error:
                failures.append(f"recheck guardian {path.name}: {error}")
                continue
            if remaining_links != expected_remaining_links:
                failures.append(
                    f"owned artifact {path.name} retained {remaining_links} links; "
                    f"expected {expected_remaining_links} tracked links after cleanup"
                )
                continue
            if expected_remaining_links:
                remaining_path = next(
                    (
                        other_path for other_path in bound_paths if other_path != path
                    ),
                    None,
                )
                if remaining_path is None:
                    failures.append(
                        f"owned artifact {path.name} lost its remaining tracked link"
                    )
                    continue
                self._guardians[remaining_path] = guardian
                for guardian_path, candidate in list(self._guardians.items()):
                    if candidate is guardian and guardian_path != remaining_path:
                        self._guardians.pop(guardian_path, None)
                cleared_paths = (path,)
            else:
                cleared_paths = tuple(
                    candidate
                    for candidate, candidate_id in self._owned_file_ids.items()
                    if candidate_id == expected_file_id
                )
            for cleared_path in cleared_paths:
                self._owned_temporary.discard(cleared_path)
                self._owned_final.discard(cleared_path)
                self._owned_file_ids.pop(cleared_path, None)
                self._sealed_snapshots.pop(cleared_path, None)
                self._committed_snapshots.pop(cleared_path, None)
                self._states.pop(cleared_path, None)
            if not expected_remaining_links:
                try:
                    guardian.close()
                except OSError as error:
                    failures.append(f"close deleted guardian {path.name}: {error}")
                else:
                    for guardian_path, candidate in list(self._guardians.items()):
                        if candidate is guardian:
                            self._guardians.pop(guardian_path, None)
        if failures:
            raise SelectionError("selection cleanup failed: " + "; ".join(failures))


def _stream_lines(
    stream: object, limits: SelectionLimits, state: _InputState
):
    read = getattr(stream, "read", None)
    if not callable(read):
        raise SelectionError("OPL input must be a readable bytes stream")
    pending = bytearray()
    line_number = 0
    while True:
        try:
            chunk = read(limits.read_chunk_bytes)
        except SelectionError:
            raise
        except Exception as error:
            raise SelectionError(f"OPL stream read failed: {error}") from error
        if type(chunk) is not bytes:
            raise SelectionError("OPL stream read must return immutable bytes")
        if len(chunk) > limits.read_chunk_bytes:
            raise SelectionError("OPL stream returned more bytes than requested")
        if not chunk:
            break
        state.bytes += len(chunk)
        if state.bytes > limits.max_input_bytes:
            raise SelectionError(
                f"OPL input exceeds the input byte ceiling {limits.max_input_bytes}"
            )
        state.sha256.update(chunk)
        cursor = 0
        while cursor < len(chunk):
            newline = chunk.find(b"\n", cursor)
            end = len(chunk) if newline < 0 else newline
            addition = end - cursor
            if len(pending) + addition > limits.max_line_bytes:
                raise SelectionError(
                    f"OPL record exceeds the line byte ceiling {limits.max_line_bytes}"
                )
            pending.extend(chunk[cursor:end])
            if newline < 0:
                break
            line_number += 1
            yield line_number, bytes(pending)
            pending.clear()
            cursor = newline + 1
    if pending:
        raise SelectionError("OPL input must end with a final LF")


def _validate_stage(stage_dir: str | Path) -> Path:
    try:
        stage = Path(stage_dir)
    except TypeError as error:
        raise SelectionError("staging directory path is invalid") from error
    if not stage.is_dir() or stage.is_symlink():
        raise SelectionError("staging directory must be an existing real directory")
    try:
        if next(stage.iterdir(), None) is not None:
            raise SelectionError("caller-owned staging directory must be empty before start")
    except SelectionError:
        raise
    except OSError as error:
        raise SelectionError(f"staging directory inventory failed: {error}") from error
    return stage


def _status_signature(status: os.stat_result) -> tuple[int, int, int, int]:
    return status.st_nlink, status.st_size, status.st_mtime_ns, status.st_ctime_ns


def _pinned_file_fact(
    artifacts: _Artifacts,
    name: str,
) -> tuple[int, str, tuple[int, int, int, int]]:
    path = artifacts.stage_dir / name
    handle = artifacts._final_handles.get(path)
    expected_file_id = artifacts._owned_file_ids.get(path)
    committed_snapshot = artifacts._committed_snapshots.get(path)
    if handle is None or expected_file_id is None or committed_snapshot is None:
        raise SelectionError(f"final artifact pin is missing for {name}")
    terminal = _held_file_snapshot(
        handle,
        path,
        f"final artifact {name} terminal snapshot",
    )
    if terminal != committed_snapshot or terminal.file_id != expected_file_id:
        raise SelectionError(f"final artifact changed after COMMITTED for {name}")
    return terminal.byte_length, terminal.sha256, (
        terminal.link_count,
        terminal.size,
        terminal.mtime_ns,
        terminal.ctime_ns,
    )


def _tuple_token(record: bytes) -> int:
    return int.from_bytes(
        hashlib.sha256(b"FAE8FINALSET1\0" + record).digest(),
        "little",
    )


def _validate_final_tuples_and_roots(
    artifacts: _Artifacts,
    selected_total: int,
    selected_ways: int,
    selected_relations: int,
) -> tuple[int, int, int]:
    tuple_handle = artifacts._final_handles[artifacts.stage_dir / "selected-tuples.bin"]
    root_handle = artifacts._final_handles[artifacts.stage_dir / "root-ids.txt"]
    previous = (0, 0)
    counted = Counter()
    records = 0
    token_xor = 0
    token_sum = 0
    try:
        tuple_handle.seek(0)
        root_handle.seek(0)
        while True:
            record = tuple_handle.read(45)
            if not record:
                break
            if len(record) != 45 or record[0] not in {1, 2}:
                raise SelectionError("final selected tuple record is malformed")
            kind = record[0]
            object_id = struct.unpack_from("<Q", record, 1)[0]
            version = struct.unpack_from("<I", record, 9)[0]
            key = (kind, object_id)
            if (
                object_id <= 0
                or object_id > _MAX_SIGNED_63
                or version <= 0
                or key <= previous
            ):
                raise SelectionError(
                    "final selected tuples contain invalid, duplicate, or unordered objects"
                )
            previous = key
            expected_root = ("w" if kind == 1 else "r") + str(object_id) + "\n"
            if root_handle.readline(64) != expected_root.encode("ascii"):
                raise SelectionError("final tuple and root-ID artifacts disagree")
            token = _tuple_token(record)
            token_xor ^= token
            token_sum = (token_sum + token) & ((1 << 256) - 1)
            counted[kind] += 1
            records += 1
            if records > selected_total:
                raise SelectionError("final selected tuples exceed the summary count")
        if root_handle.read(1):
            raise SelectionError("final root-ID artifact has extra records")
    except SelectionError:
        raise
    except (OSError, ValueError, struct.error) as error:
        raise SelectionError(f"final tuple semantic validation failed: {error}") from error
    if (
        records != selected_total
        or counted[1] != selected_ways
        or counted[2] != selected_relations
    ):
        raise SelectionError("final tuple or root-ID counts contradict the summary")
    return records, token_xor, token_sum


def _validate_final_bucket(
    artifacts: _Artifacts,
    bucket: int,
    expected_records: int,
    expected_bytes: int,
    limits: SelectionLimits,
) -> tuple[int, int, int]:
    name = f"roots-{bucket:03d}.bin"
    handle = artifacts._final_handles[artifacts.stage_dir / name]
    records = 0
    consumed = 0
    previous = (0, 0)
    token_xor = 0
    token_sum = 0

    def unpack_from_payload(
        payload: bytes,
        offset: int,
        pattern: str,
    ) -> tuple[tuple[object, ...], int]:
        width = struct.calcsize(pattern)
        end = offset + width
        if end > len(payload):
            raise SelectionError(f"final {name} canonical payload is truncated")
        try:
            return struct.unpack(pattern, payload[offset:end]), end
        except struct.error as error:
            raise SelectionError(f"final {name} canonical payload is malformed") from error

    def text_from_payload(payload: bytes, offset: int) -> tuple[bytes, int]:
        (size_values, offset_after_size) = unpack_from_payload(payload, offset, "<I")
        (size,) = size_values
        assert type(size) is int
        if size > limits.max_text_utf8_bytes:
            raise SelectionError(f"final {name} text exceeds its UTF-8 byte ceiling")
        end = offset_after_size + size
        if end > len(payload):
            raise SelectionError(f"final {name} text is truncated")
        raw = payload[offset_after_size:end]
        try:
            value = raw.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise SelectionError(f"final {name} text is not strict UTF-8") from error
        if "\x00" in value:
            raise SelectionError(f"final {name} text contains a forbidden NUL")
        return raw, end

    try:
        handle.seek(0)
        while True:
            header = handle.read(4)
            if not header:
                break
            if len(header) != 4:
                raise SelectionError(f"final {name} has a truncated frame header")
            (body_bytes,) = struct.unpack("<I", header)
            consumed += 4
            if body_bytes > expected_bytes - consumed:
                raise SelectionError(f"final {name} frame exceeds remaining artifact bytes")
            body = handle.read(body_bytes)
            consumed += len(body)
            if len(body) != body_bytes or len(body) < 61:
                raise SelectionError(f"final {name} has a truncated frame")
            payload = body[:-32]
            stored_digest = body[-32:]
            object_digest = hashlib.sha256(_ROOT_DOMAIN + payload).digest()
            if stored_digest != object_digest:
                raise SelectionError(f"final {name} frame root digest is invalid")
            (identity, offset) = unpack_from_payload(payload, 0, "<BQI")
            kind, object_id, version = identity
            (_, offset) = unpack_from_payload(payload, offset, "<q")
            if (
                kind not in {1, 2}
                or type(object_id) is not int
                or object_id <= 0
                or object_id > _MAX_SIGNED_63
                or type(version) is not int
                or version <= 0
            ):
                raise SelectionError(f"final {name} frame object identity is invalid")
            assigned_bucket = hashlib.sha256(
                _BUCKET_DOMAIN + bytes((kind,)) + struct.pack("<Q", object_id)
            ).digest()[0]
            if assigned_bucket != bucket:
                raise SelectionError(f"final {name} frame is assigned to the wrong bucket")
            (tag_values, offset) = unpack_from_payload(payload, offset, "<I")
            (tag_count,) = tag_values
            assert type(tag_count) is int
            if (
                tag_count > limits.max_tags_per_object
                or tag_count > (len(payload) - offset) // 8
            ):
                raise SelectionError(f"final {name} tag count is structurally invalid")
            previous_tag: bytes | None = None
            for _ in range(tag_count):
                tag_key, offset = text_from_payload(payload, offset)
                _, offset = text_from_payload(payload, offset)
                if not tag_key or (
                    previous_tag is not None and tag_key <= previous_tag
                ):
                    raise SelectionError(
                        f"final {name} tags are empty, duplicate, or unordered"
                    )
                previous_tag = tag_key
            (reference_values, offset) = unpack_from_payload(payload, offset, "<I")
            (reference_count,) = reference_values
            assert type(reference_count) is int
            if reference_count > limits.max_references_per_object:
                raise SelectionError(f"final {name} reference count exceeds its ceiling")
            if kind == 1:
                if reference_count > (len(payload) - offset) // 8:
                    raise SelectionError(f"final {name} node references are truncated")
                for _ in range(reference_count):
                    (reference_values, offset) = unpack_from_payload(
                        payload,
                        offset,
                        "<Q",
                    )
                    (reference,) = reference_values
                    if (
                        type(reference) is not int
                        or reference <= 0
                        or reference > _MAX_SIGNED_63
                    ):
                        raise SelectionError(f"final {name} node reference is invalid")
            else:
                if reference_count > (len(payload) - offset) // 17:
                    raise SelectionError(f"final {name} relation members are truncated")
                for ordinal in range(reference_count):
                    (member_values, offset) = unpack_from_payload(
                        payload,
                        offset,
                        "<IBQ",
                    )
                    stored_ordinal, member_kind, reference = member_values
                    if (
                        stored_ordinal != ordinal
                        or member_kind not in {0, 1, 2}
                        or type(reference) is not int
                        or reference <= 0
                        or reference > _MAX_SIGNED_63
                    ):
                        raise SelectionError(f"final {name} relation member is invalid")
                    _, offset = text_from_payload(payload, offset)
            if offset != len(payload):
                raise SelectionError(f"final {name} payload has trailing bytes")
            tuple_record = struct.pack(
                "<BQI32s",
                kind,
                object_id,
                version,
                object_digest,
            )
            key = (kind, object_id)
            if key <= previous:
                raise SelectionError(f"final {name} objects are duplicate or unordered")
            previous = key
            token = _tuple_token(tuple_record)
            token_xor ^= token
            token_sum = (token_sum + token) & ((1 << 256) - 1)
            records += 1
            if records > expected_records:
                raise SelectionError(f"final {name} exceeds its summary record count")
    except SelectionError:
        raise
    except (OSError, ValueError, struct.error) as error:
        raise SelectionError(f"final {name} semantic validation failed: {error}") from error
    if consumed != expected_bytes or records != expected_records:
        raise SelectionError(f"final {name} bytes or records contradict the summary")
    return records, token_xor, token_sum


def _validate_final_inventory(
    stage: Path,
    artifacts: _Artifacts,
    summary_raw: bytes,
    summary: dict[str, object],
    limits: SelectionLimits,
    result: SelectionResult,
) -> None:
    try:
        names = {path.name for path in stage.iterdir()}
    except OSError as error:
        raise SelectionError(f"final staging inventory failed: {error}") from error
    if names != _FINAL_FILENAME_SET:
        raise SelectionError("final staging inventory changed before success")
    artifacts.freeze_committed()

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
    if set(expected) != _FINAL_FILENAME_SET:
        raise SelectionError("final artifact evidence set is incomplete")
    if (
        result.stage_dir != stage
        or result.summary_path != stage / "selection-summary.json"
        or result.selected_tuple_path != stage / "selected-tuples.bin"
        or result.root_ids_path != stage / "root-ids.txt"
        or result.summary_bytes != len(summary_raw)
        or result.summary_sha256 != hashlib.sha256(summary_raw).hexdigest()
        or result.selected_way_count != summary["selected"]["ways"]
        or result.selected_relation_count != summary["selected"]["relations"]
        or result.rejection_ledger_sha256
        != summary["rejections"]["ledgerSha256"]
        or result.planet_source_sha256
        != summary["identities"]["planetSourceSha256"]
        or result.candidate_pbf_bytes
        != summary["identities"]["candidatePbfBytes"]
        or result.candidate_pbf_sha256
        != summary["identities"]["candidatePbfSha256"]
        or result.converted_stream_bytes
        != summary["identities"]["convertedStreamBytes"]
        or result.converted_stream_sha256
        != summary["identities"]["convertedStreamSha256"]
        or result.converted_stream_format
        != summary["identities"]["convertedStreamFormat"]
    ):
        raise SelectionError("prebuilt result contradicts final artifact evidence")

    stable_signatures: dict[str, tuple[int, int, int, int]] = {}
    for name in sorted(_FINAL_FILENAMES):
        actual_bytes, actual_sha, signature = _pinned_file_fact(artifacts, name)
        if (actual_bytes, actual_sha) != expected[name]:
            raise SelectionError(
                f"final artifact bytes or SHA-256 contradict evidence for {name}"
            )
        stable_signatures[name] = signature

    summary_handle = artifacts._final_handles[stage / "selection-summary.json"]
    try:
        summary_handle.seek(0)
        if summary_handle.read(len(summary_raw) + 1) != summary_raw:
            raise SelectionError("final summary bytes contradict returned evidence")
    except SelectionError:
        raise
    except (OSError, ValueError) as error:
        raise SelectionError(f"final summary semantic validation failed: {error}") from error

    selected = summary["selected"]
    tuple_aggregate = _validate_final_tuples_and_roots(
        artifacts,
        selected["total"],
        selected["ways"],
        selected["relations"],
    )
    bucket_records = 0
    bucket_xor = 0
    bucket_sum = 0
    entries = summary["artifacts"]["buckets"]["entries"]
    for bucket, entry in enumerate(entries):
        records, token_xor, token_sum = _validate_final_bucket(
            artifacts,
            bucket,
            entry["records"],
            entry["bytes"],
            limits,
        )
        bucket_records += records
        bucket_xor ^= token_xor
        bucket_sum = (bucket_sum + token_sum) & ((1 << 256) - 1)
    if (bucket_records, bucket_xor, bucket_sum) != tuple_aggregate:
        raise SelectionError(
            "final bucket objects do not reproduce the selected tuple evidence"
        )

    for name, signature in stable_signatures.items():
        path = stage / name
        handle = artifacts._final_handles.get(path)
        expected_file_id = artifacts._owned_file_ids.get(path)
        if handle is None or expected_file_id is None:
            raise SelectionError(f"final artifact ownership disappeared for {name}")
        try:
            handle_status = os.fstat(handle.fileno())
            path_status = os.stat(path, follow_symlinks=False)
        except OSError as error:
            raise SelectionError(
                f"final artifact identity recheck failed for {name}: {error}"
            ) from error
        if (
            _file_id(handle_status) != expected_file_id
            or _file_id(path_status) != expected_file_id
            or handle_status.st_nlink != 1
            or _status_signature(handle_status) != signature
        ):
            raise SelectionError(f"final artifact identity or content drifted for {name}")
    try:
        terminal_names = {path.name for path in stage.iterdir()}
    except OSError as error:
        raise SelectionError(
            f"final staging inventory recheck failed: {error}"
        ) from error
    if terminal_names != _FINAL_FILENAME_SET:
        raise SelectionError("final staging inventory changed at terminal evidence")


def scan_planet_roots(
    opl_stream: object,
    stage_dir: str | Path,
    bindings: SelectionBindings,
    *,
    profile: str = LIVE_NAME_ENVELOPE_PROFILE,
    limits: SelectionLimits = SelectionLimits(),
    workers: int = 1,
) -> SelectionResult:
    """Stream strict OPL roots into deterministic caller-owned staging artifacts."""

    if not isinstance(bindings, SelectionBindings):
        raise SelectionError("selection bindings are missing or invalid")
    if not isinstance(limits, SelectionLimits):
        raise SelectionError("selection limits are missing or invalid")
    if type(workers) is not int or workers <= 0:
        raise SelectionError("worker count must be a positive integer")
    if profile not in {FIXTURE_NAME_ENVELOPE_PROFILE, LIVE_NAME_ENVELOPE_PROFILE}:
        raise SelectionError("selection profile is unknown")
    if bindings.policy_sha256 != GLOBAL_POLICY_SHA256:
        raise SelectionError("policy SHA-256 does not identify the built-in global policy")
    if (
        profile == LIVE_NAME_ENVELOPE_PROFILE
        and bindings.planet_source_sha256 != LIVE_PLANET_SOURCE_SHA256
    ):
        raise SelectionError(
            "live name-envelope profile requires its exact planet source SHA-256"
        )
    artifacts: _Artifacts | None = None
    try:
        stage = _validate_stage(stage_dir)
        state = _InputState(sha256=hashlib.sha256())
        previous_key = (0, 0)
        selected_counts = Counter()
        rejection_counts: Counter[int] = Counter()
        rejection_chain = hashlib.sha256(_REJECTION_DOMAIN).digest()
        non_nfc_fields = 0
        non_nfc_records = 0
        artifacts = _Artifacts(stage)
        for line_number, raw_line in _stream_lines(opl_stream, limits, state):
            state.records += 1
            if state.records > limits.max_objects:
                raise SelectionError(
                    f"OPL input exceeds the object ceiling {limits.max_objects}"
                )
            value = _parse_opl_line(
                raw_line,
                line_number,
                limits,
                tag_budget=limits.max_total_tags - state.tags,
                reference_budget=limits.max_total_references - state.references,
            )
            key = (value.kind, value.object_id)
            if key == previous_key:
                raise SelectionError(
                    "OPL input contains a duplicate/history object under Type_then_ID"
                )
            if key < previous_key:
                raise SelectionError("OPL input is not monotonic Type_then_ID")
            previous_key = key
            if value.kind == 1:
                state.ways += 1
                reference_count = len(value.references)
            else:
                state.relations += 1
                reference_count = len(value.members)
            state.tags += len(value.tags)
            state.references += reference_count
            if state.tags > limits.max_total_tags:
                raise SelectionError(
                    f"OPL input exceeds the tag ceiling {limits.max_total_tags}"
                )
            if state.references > limits.max_total_references:
                raise SelectionError(
                    "OPL input exceeds the reference ceiling "
                    f"{limits.max_total_references}"
                )
            payload = _object_payload(value)
            object_digest = hashlib.sha256(_ROOT_DOMAIN + payload).digest()
            reason = _rejection_reason(value)
            if reason:
                rejection_counts[reason] += 1
                rejected_tuple = struct.pack(
                    "<BQI32sB",
                    value.kind,
                    value.object_id,
                    value.version,
                    object_digest,
                    reason,
                )
                rejection_chain = hashlib.sha256(
                    _REJECTION_DOMAIN + rejection_chain + rejected_tuple
                ).digest()
            else:
                assert artifacts is not None
                artifacts.write_selected(value, payload, object_digest)
                selected_counts[value.kind] += 1
                fields, records = _non_nfc_counts(value)
                non_nfc_fields += fields
                non_nfc_records += records
        converted_stream_sha256 = state.sha256.hexdigest()
        if bindings.converted_stream_format != "opl":
            raise SelectionError(
                "converted stream format mismatch: producer input must be canonical OPL"
            )
        if (
            state.bytes != bindings.converted_stream_bytes
            or converted_stream_sha256 != bindings.converted_stream_sha256
        ):
            raise SelectionError(
                "converted stream identity mismatch: "
                f"expected {bindings.converted_stream_bytes} bytes and "
                f"{bindings.converted_stream_sha256}, got {state.bytes} bytes and "
                f"{converted_stream_sha256}"
        )
        if profile == LIVE_NAME_ENVELOPE_PROFILE and (
            bindings.candidate_pbf_bytes != LIVE_NAME_ENVELOPE_PBF_BYTES
            or bindings.candidate_pbf_sha256 != LIVE_NAME_ENVELOPE_PBF_SHA256
            or bindings.converted_stream_bytes != LIVE_NAME_ENVELOPE_OPL_BYTES
            or bindings.converted_stream_sha256 != LIVE_NAME_ENVELOPE_OPL_SHA256
            or bindings.converted_stream_format
            != LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT
            or state.ways != LIVE_NAME_ENVELOPE_WAYS
            or state.relations != LIVE_NAME_ENVELOPE_RELATIONS
        ):
            raise SelectionError(
                "live name-envelope profile requires its exact candidate PBF, canonical "
                "converted stream, format, way count, and relation count"
        )
        assert artifacts is not None
        bucket_inventory = artifacts.finish_payloads()
        root_ids_snapshot = artifacts._sealed_snapshots[
            artifacts._temporary["root-ids.txt"]
        ]
        tuple_snapshot = artifacts._sealed_snapshots[
            artifacts._temporary["selected-tuples.bin"]
        ]
        root_ids_bytes = root_ids_snapshot.byte_length
        root_ids_sha256 = root_ids_snapshot.sha256
        tuple_bytes = tuple_snapshot.byte_length
        tuple_sha256 = tuple_snapshot.sha256
        selected_total = selected_counts[1] + selected_counts[2]
        if tuple_bytes != selected_total * 45:
            raise SelectionError("selected tuple output has an impossible byte count")
        policy_raw = canonical_policy_bytes()
        summary = {
            "artifacts": {
                "buckets": {
                    "bucketCount": 256,
                    "entries": bucket_inventory,
                    "records": selected_total,
                },
                "rootIds": {
                    "bytes": root_ids_bytes,
                    "filename": "root-ids.txt",
                    "records": selected_total,
                    "sha256": root_ids_sha256,
                },
                "selectedTuples": {
                    "bytes": tuple_bytes,
                    "filename": "selected-tuples.bin",
                    "recordBytes": 45,
                    "records": selected_total,
                    "sha256": tuple_sha256,
                },
            },
            "identities": {
                "candidatePbfBytes": bindings.candidate_pbf_bytes,
                "candidatePbfSha256": bindings.candidate_pbf_sha256,
                "codeSha256": bindings.code_sha256,
                "convertedStreamBytes": bindings.converted_stream_bytes,
                "convertedStreamFormat": bindings.converted_stream_format,
                "convertedStreamSha256": bindings.converted_stream_sha256,
                "planetSourceSha256": bindings.planet_source_sha256,
                "policySha256": bindings.policy_sha256,
                "runtimeSha256": bindings.runtime_sha256,
            },
            "input": {
                "bytes": state.bytes,
                "objects": state.records,
                "references": state.references,
                "relations": state.relations,
                "sha256": converted_stream_sha256,
                "tags": state.tags,
                "ways": state.ways,
            },
            "policy": {
                "bytes": len(policy_raw),
                "document": json.loads(policy_raw),
                "sha256": hashlib.sha256(policy_raw).hexdigest(),
            },
            "profile": profile,
            "rejections": {
                "countsByReasonId": {
                    str(reason): rejection_counts[reason]
                    for reason in sorted(rejection_counts)
                },
                "ledgerSha256": rejection_chain.hex(),
                "records": sum(rejection_counts.values()),
            },
            "schema": _SUMMARY_SCHEMA,
            "selected": {
                "nonNfcFieldCount": non_nfc_fields,
                "nonNfcRecordCount": non_nfc_records,
                "relations": selected_counts[2],
                "total": selected_total,
                "ways": selected_counts[1],
            },
        }
        summary_raw = _canonical_json_bytes(summary)
        artifacts.write_summary(summary_raw)
        summary_sha256 = hashlib.sha256(summary_raw).hexdigest()
        artifacts.commit()
        result = SelectionResult(
            stage_dir=stage,
            summary_path=stage / "selection-summary.json",
            selected_tuple_path=stage / "selected-tuples.bin",
            root_ids_path=stage / "root-ids.txt",
            summary_bytes=len(summary_raw),
            summary_sha256=summary_sha256,
            selected_way_count=selected_counts[1],
            selected_relation_count=selected_counts[2],
            rejection_ledger_sha256=rejection_chain.hex(),
            planet_source_sha256=bindings.planet_source_sha256,
            candidate_pbf_bytes=bindings.candidate_pbf_bytes,
            candidate_pbf_sha256=bindings.candidate_pbf_sha256,
            converted_stream_bytes=bindings.converted_stream_bytes,
            converted_stream_sha256=bindings.converted_stream_sha256,
            converted_stream_format=bindings.converted_stream_format,
        )
        _validate_final_inventory(
            stage,
            artifacts,
            summary_raw,
            summary,
            limits,
            result,
        )
        artifacts.release_ownership()
        artifacts = None
        return result
    except BaseException as error:
        if artifacts is not None:
            try:
                artifacts.cleanup()
            except SelectionError as cleanup_error:
                raise cleanup_error from error
        if isinstance(error, SelectionError):
            raise
        if isinstance(error, MemoryError):
            raise SelectionError("selection memory allocation failed") from error
        if isinstance(error, (OSError, OverflowError, struct.error, ValueError, UnicodeError)):
            raise SelectionError(f"selection staging failed: {error}") from error
        raise


__all__ = [
    "FIXTURE_NAME_ENVELOPE_PROFILE",
    "GLOBAL_POLICY_SHA256",
    "LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT",
    "LIVE_NAME_ENVELOPE_OPL_BYTES",
    "LIVE_NAME_ENVELOPE_OPL_SHA256",
    "LIVE_NAME_ENVELOPE_PBF_BYTES",
    "LIVE_NAME_ENVELOPE_PBF_SHA256",
    "LIVE_NAME_ENVELOPE_PROFILE",
    "LIVE_NAME_ENVELOPE_RELATIONS",
    "LIVE_NAME_ENVELOPE_WAYS",
    "LIVE_PLANET_SOURCE_SHA256",
    "SelectionBindings",
    "SelectionError",
    "SelectionLimits",
    "SelectionResult",
    "canonical_policy_bytes",
    "scan_planet_roots",
]
