from __future__ import annotations

import argparse
from bisect import bisect_right
from dataclasses import InitVar, dataclass, field
from enum import Enum, IntEnum
import hashlib
import json
import os
from pathlib import Path
import re
import struct
from typing import Sequence


UNICODE_SCRIPTS_VERSION = "17.0.0"
UNICODE_SCRIPTS_SOURCE_URL = (
    "https://www.unicode.org/Public/17.0.0/ucd/Scripts.txt"
)
UNICODE_SCRIPTS_SOURCE_BYTES = 192_460
UNICODE_SCRIPTS_SOURCE_SHA256 = (
    "9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf"
)
UAX24_REVISION = 39
UNICODE_PROPLIST_SOURCE_URL = (
    "https://www.unicode.org/Public/17.0.0/ucd/PropList.txt"
)
UNICODE_PROPLIST_SOURCE_BYTES = 145_465
UNICODE_PROPLIST_SOURCE_SHA256 = (
    "130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd"
)
END_TRIM_SCALARS = (
    0x0009,
    0x000A,
    0x000B,
    0x000C,
    0x000D,
    0x0020,
    0x0085,
    0x00A0,
    0x1680,
    0x2000,
    0x2001,
    0x2002,
    0x2003,
    0x2004,
    0x2005,
    0x2006,
    0x2007,
    0x2008,
    0x2009,
    0x200A,
    0x2028,
    0x2029,
    0x202F,
    0x205F,
    0x3000,
)
UNICODE_SCRIPT_PROFILE_FORMAT = "flightalert-unicode-script-profile"
UNICODE_SCRIPT_PROFILE_VERSION = 1
UNICODE_SCRIPT_PROFILE_ALGORITHM = "unicode-scripts-complete-intervals-v1"
UNICODE_SCRIPT_PROFILE_BYTES = 41_325
UNICODE_SCRIPT_PROFILE_SHA256 = (
    "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb"
)
UNICODE_SCRIPT_INTERVALS_SHA256 = (
    "3b5536cd83ac701018743168dcf2298b38098e32d5b52677027f2f37c2169d69"
)
UNICODE_MAX = 0x10FFFF
SURROGATE_START = 0xD800
SURROGATE_END = 0xDFFF
UNICODE_SCALAR_COUNT = 0x110000 - 0x800

SOURCED_TEXT_POLICY_FORMAT = "flightalert-sourced-text-policy"
SOURCED_TEXT_POLICY_VERSION = 1
SOURCED_TEXT_POLICY_DOMAIN = b"FAE8SOURCEDTEXTPOL1\0"
SOURCED_TEXT_IDENTITY_DOMAIN = b"FAE8SOURCEDTEXT1\0"
SOURCED_TEXT_RECORD_TAG = 53
SOURCED_TEXT_RECORD_VERSION = 1
BILINGUAL_PRESENTATION_TOKEN = (
    "flightalert.sourced-map-text.primary-with-english.v1"
)
SHARED_DECISION_PATH_ID = "flightalert.sourced-text.source-exact-v2"
MAX_SOURCED_TEXT_UTF8_BYTES = 4_096

_U64_MAX = (1 << 64) - 1

DEFAULT_PROFILE_PATH = (
    Path(__file__).resolve().parent
    / "data"
    / "unicode-script-profile-17.0.0.json"
)

_SCRIPT_RANGE_RE = re.compile(
    r"([0-9A-F]{4,6})(?:\.\.([0-9A-F]{4,6}))?"
    r"\s*;\s*([A-Za-z][A-Za-z0-9_]*)"
)
_SCRIPT_NAME_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*")
_END_TRIM_SCALAR_SET = frozenset(END_TRIM_SCALARS)
_EVIDENCE_UNSET = object()
_DECISION_SEAL = object()
_FROZEN_PROFILE_CONSTRUCTION_SEAL = object()
_GENERATED_PROFILE_CONSTRUCTION_SEAL = object()
_POLICY_CONSTRUCTION_SEAL = object()


class UnicodeScriptProfileError(ValueError):
    """A Unicode script source or canonical profile violates the frozen contract."""


def canonical_json_bytes(document: object) -> bytes:
    """Return the repository canonical JSON representation."""

    try:
        text = json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        return (text + "\n").encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeEncodeError) as error:
        raise UnicodeScriptProfileError(
            "document cannot be represented as canonical JSON"
        ) from error


@dataclass(frozen=True, slots=True, order=True)
class ScriptInterval:
    start: int
    end: int
    script: str = field(compare=False)


@dataclass(frozen=True, slots=True)
class UnicodeScriptProfile:
    intervals: tuple[ScriptInterval, ...]
    profile_sha256: bytes
    unicode_version: str
    uax24_revision: int
    source_sha256: bytes
    source_bytes: int
    source_url: str
    algorithm: str
    _construction_seal: InitVar[object] = None
    _starts: tuple[int, ...] = field(init=False, repr=False, compare=False)
    _is_exact_frozen_profile: bool = field(
        init=False,
        repr=False,
        compare=False,
    )

    def __post_init__(self, _construction_seal: object) -> None:
        if _construction_seal not in {
            _FROZEN_PROFILE_CONSTRUCTION_SEAL,
            _GENERATED_PROFILE_CONSTRUCTION_SEAL,
        }:
            raise UnicodeScriptProfileError(
                "Unicode script profiles must be derived from validated profile bytes"
            )
        if type(self.intervals) is not tuple or any(
            type(interval) is not ScriptInterval for interval in self.intervals
        ):
            raise UnicodeScriptProfileError(
                "profile intervals must be an exact immutable ScriptInterval tuple"
            )
        object.__setattr__(
            self,
            "_starts",
            tuple(interval.start for interval in self.intervals),
        )
        is_frozen = _construction_seal is _FROZEN_PROFILE_CONSTRUCTION_SEAL
        if is_frozen:
            _require_exact_frozen_profile_fields(self)
        object.__setattr__(self, "_is_exact_frozen_profile", is_frozen)

    @classmethod
    def from_json_bytes(cls, raw: bytes) -> "UnicodeScriptProfile":
        _require_frozen_profile_identity(raw)
        return cls._from_validated_json_bytes(
            raw,
            _construction_seal=_FROZEN_PROFILE_CONSTRUCTION_SEAL,
        )

    @classmethod
    def _from_generated_json_bytes(cls, raw: bytes) -> "UnicodeScriptProfile":
        return cls._from_validated_json_bytes(
            raw,
            _construction_seal=_GENERATED_PROFILE_CONSTRUCTION_SEAL,
        )

    @classmethod
    def _from_validated_json_bytes(
        cls,
        raw: bytes,
        *,
        _construction_seal: object,
    ) -> "UnicodeScriptProfile":
        document = _load_profile_document(raw)
        intervals = _profile_intervals(document)
        source = document["source"]
        if not isinstance(source, dict):
            raise UnicodeScriptProfileError("profile source must be an object")
        return cls(
            intervals=intervals,
            profile_sha256=hashlib.sha256(raw).digest(),
            unicode_version=source["unicodeVersion"],
            uax24_revision=source["uax24Revision"],
            source_sha256=bytes.fromhex(source["sha256"]),
            source_bytes=source["bytes"],
            source_url=source["url"],
            algorithm=document["algorithm"],
            _construction_seal=_construction_seal,
        )

    def script_for_scalar(self, scalar: int) -> str:
        _require_scalar(scalar, "script lookup")
        index = bisect_right(self._starts, scalar) - 1
        if index < 0:
            raise UnicodeScriptProfileError("script profile has a scalar gap")
        interval = self.intervals[index]
        if scalar > interval.end:
            raise UnicodeScriptProfileError("script profile has a scalar gap")
        return interval.script


def _require_exact_frozen_profile_fields(
    profile: UnicodeScriptProfile,
) -> None:
    expected_fields = (
        (type(profile.profile_sha256) is bytes, "profile SHA-256 type"),
        (
            profile.profile_sha256.hex() == UNICODE_SCRIPT_PROFILE_SHA256,
            "profile SHA-256",
        ),
        (type(profile.unicode_version) is str, "Unicode version type"),
        (profile.unicode_version == UNICODE_SCRIPTS_VERSION, "Unicode version"),
        (type(profile.uax24_revision) is int, "UAX #24 revision type"),
        (profile.uax24_revision == UAX24_REVISION, "UAX #24 revision"),
        (type(profile.source_sha256) is bytes, "source SHA-256 type"),
        (
            profile.source_sha256.hex() == UNICODE_SCRIPTS_SOURCE_SHA256,
            "source SHA-256",
        ),
        (type(profile.source_bytes) is int, "source byte count type"),
        (profile.source_bytes == UNICODE_SCRIPTS_SOURCE_BYTES, "source byte count"),
        (type(profile.source_url) is str, "source URL type"),
        (profile.source_url == UNICODE_SCRIPTS_SOURCE_URL, "source URL"),
        (type(profile.algorithm) is str, "profile algorithm type"),
        (
            profile.algorithm == UNICODE_SCRIPT_PROFILE_ALGORITHM,
            "profile algorithm",
        ),
    )
    for accepted, label in expected_fields:
        if not accepted:
            raise UnicodeScriptProfileError(
                f"frozen profile has the wrong {label}"
            )
    try:
        interval_document = [
            [interval.start, interval.end, interval.script]
            for interval in profile.intervals
        ]
        interval_sha256 = hashlib.sha256(
            canonical_json_bytes(interval_document)
        ).hexdigest()
    except MemoryError as error:
        raise UnicodeScriptProfileError(
            "profile interval identity memory allocation failed"
        ) from error
    if interval_sha256 != UNICODE_SCRIPT_INTERVALS_SHA256:
        raise UnicodeScriptProfileError(
            "profile intervals do not match the exact derived interval identity"
        )


def _require_scalar(value: object, label: str) -> int:
    if type(value) is not int or not 0 <= value <= UNICODE_MAX:
        raise UnicodeScriptProfileError(f"{label} must be a Unicode scalar")
    if SURROGATE_START <= value <= SURROGATE_END:
        raise UnicodeScriptProfileError(f"{label} must not be a surrogate scalar")
    return value


def _reject_duplicate_keys(
    pairs: list[tuple[str, object]],
) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise UnicodeScriptProfileError(
                f"profile JSON contains duplicate key {key!r}"
            )
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise UnicodeScriptProfileError(f"profile JSON contains invalid value {value}")


def _require_frozen_profile_identity(raw: bytes) -> None:
    if type(raw) is not bytes:
        raise UnicodeScriptProfileError("profile JSON must be immutable bytes")
    if len(raw) != UNICODE_SCRIPT_PROFILE_BYTES:
        raise UnicodeScriptProfileError(
            "profile byte length does not match the exact tracked profile"
        )
    try:
        actual_sha256 = hashlib.sha256(raw).hexdigest()
    except MemoryError as error:
        raise UnicodeScriptProfileError(
            "profile SHA-256 memory allocation failed"
        ) from error
    if actual_sha256 != UNICODE_SCRIPT_PROFILE_SHA256:
        raise UnicodeScriptProfileError(
            "profile SHA-256 does not match the exact tracked profile"
        )


def _load_profile_document(raw: bytes) -> dict[str, object]:
    if type(raw) is not bytes:
        raise UnicodeScriptProfileError("profile JSON must be immutable bytes")
    try:
        text = raw.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise UnicodeScriptProfileError("profile JSON is not strict UTF-8") from error
    try:
        document = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except UnicodeScriptProfileError:
        raise
    except MemoryError as error:
        raise UnicodeScriptProfileError(
            "profile JSON parsing failed because memory is unavailable"
        ) from error
    except (TypeError, ValueError, json.JSONDecodeError) as error:
        raise UnicodeScriptProfileError("profile JSON is malformed") from error
    if not isinstance(document, dict):
        raise UnicodeScriptProfileError("profile JSON must contain one object")
    try:
        canonical = canonical_json_bytes(document)
    except MemoryError as error:
        raise UnicodeScriptProfileError(
            "profile canonical JSON memory allocation failed"
        ) from error
    if canonical != raw:
        raise UnicodeScriptProfileError("profile is not canonical JSON")
    _validate_profile_metadata(document)
    return document


def _validate_profile_metadata(document: dict[str, object]) -> None:
    expected_keys = {
        "algorithm",
        "format",
        "intervalCount",
        "intervals",
        "profileVersion",
        "scalarCount",
        "scriptCount",
        "source",
    }
    if set(document) != expected_keys:
        raise UnicodeScriptProfileError("profile has an unexpected schema")
    for key in (
        "intervalCount",
        "profileVersion",
        "scalarCount",
        "scriptCount",
    ):
        if type(document[key]) is not int:
            raise UnicodeScriptProfileError(
                f"profile {key} must be an integer"
            )
    if document["format"] != UNICODE_SCRIPT_PROFILE_FORMAT:
        raise UnicodeScriptProfileError("profile format is not supported")
    if document["profileVersion"] != UNICODE_SCRIPT_PROFILE_VERSION:
        raise UnicodeScriptProfileError("profile version is not supported")
    if document["algorithm"] != UNICODE_SCRIPT_PROFILE_ALGORITHM:
        raise UnicodeScriptProfileError("profile algorithm is not supported")
    source = document["source"]
    if not isinstance(source, dict):
        raise UnicodeScriptProfileError("profile source must be an object")
    expected_source = {
        "bytes": UNICODE_SCRIPTS_SOURCE_BYTES,
        "sha256": UNICODE_SCRIPTS_SOURCE_SHA256,
        "uax24Revision": UAX24_REVISION,
        "unicodeVersion": UNICODE_SCRIPTS_VERSION,
        "url": UNICODE_SCRIPTS_SOURCE_URL,
    }
    if set(source) != set(expected_source):
        raise UnicodeScriptProfileError("profile source has an unexpected schema")
    for key in ("bytes", "uax24Revision"):
        if type(source[key]) is not int:
            raise UnicodeScriptProfileError(
                f"profile source {key} must be an integer"
            )
    if source["bytes"] != UNICODE_SCRIPTS_SOURCE_BYTES:
        raise UnicodeScriptProfileError("profile source byte length is wrong")
    if source["sha256"] != UNICODE_SCRIPTS_SOURCE_SHA256:
        raise UnicodeScriptProfileError("profile source SHA-256 is wrong")
    if source["unicodeVersion"] != UNICODE_SCRIPTS_VERSION:
        raise UnicodeScriptProfileError("profile source Unicode version is wrong")
    if source["uax24Revision"] != UAX24_REVISION:
        raise UnicodeScriptProfileError("profile source UAX #24 revision is wrong")
    if source["url"] != UNICODE_SCRIPTS_SOURCE_URL:
        raise UnicodeScriptProfileError("profile source URL is wrong")


def _profile_intervals(
    document: dict[str, object],
) -> tuple[ScriptInterval, ...]:
    raw_intervals = document["intervals"]
    if not isinstance(raw_intervals, list) or not raw_intervals:
        raise UnicodeScriptProfileError("profile intervals must be a nonempty list")
    intervals: list[ScriptInterval] = []
    for index, raw_interval in enumerate(raw_intervals):
        if not isinstance(raw_interval, list) or len(raw_interval) != 3:
            raise UnicodeScriptProfileError(
                f"profile interval {index} is malformed"
            )
        start, end, script = raw_interval
        if type(start) is not int or type(end) is not int:
            raise UnicodeScriptProfileError(
                f"profile interval {index} scalar bounds must be integers"
            )
        if not 0 <= start <= UNICODE_MAX or not 0 <= end <= UNICODE_MAX:
            raise UnicodeScriptProfileError(
                f"profile interval {index} contains a non-scalar bound"
            )
        if start > end:
            raise UnicodeScriptProfileError(
                f"profile interval {index} is descending"
            )
        if start <= SURROGATE_END and end >= SURROGATE_START:
            raise UnicodeScriptProfileError(
                f"profile interval {index} contains a surrogate"
            )
        if type(script) is not str or _SCRIPT_NAME_RE.fullmatch(script) is None:
            raise UnicodeScriptProfileError(
                f"profile interval {index} has a malformed script"
            )
        intervals.append(ScriptInterval(start, end, script))

    for previous, current in zip(intervals, intervals[1:]):
        if current.start < previous.start:
            raise UnicodeScriptProfileError("profile intervals are unsorted")
        if current.start <= previous.end:
            raise UnicodeScriptProfileError("profile intervals overlap")
        if current.start == previous.end + 1 and current.script == previous.script:
            raise UnicodeScriptProfileError(
                "profile contains adjacent unmerged script intervals"
            )

    expected_start = 0
    covered = 0
    for interval in intervals:
        if expected_start == SURROGATE_START:
            expected_start = SURROGATE_END + 1
        if interval.start != expected_start:
            raise UnicodeScriptProfileError("profile intervals contain a scalar gap")
        covered += interval.end - interval.start + 1
        expected_start = interval.end + 1
    if expected_start != UNICODE_MAX + 1:
        raise UnicodeScriptProfileError("profile intervals contain a scalar gap")
    if covered != UNICODE_SCALAR_COUNT:
        raise UnicodeScriptProfileError("profile scalar coverage count is wrong")
    if document["scalarCount"] != covered:
        raise UnicodeScriptProfileError("profile scalarCount is wrong")
    if document["intervalCount"] != len(intervals):
        raise UnicodeScriptProfileError("profile intervalCount is wrong")
    if document["scriptCount"] != len({item.script for item in intervals}):
        raise UnicodeScriptProfileError("profile scriptCount is wrong")
    required_scripts = {"Common", "Inherited", "Latin", "Unknown"}
    if not required_scripts.issubset(item.script for item in intervals):
        raise UnicodeScriptProfileError("profile omits a required script class")
    return tuple(intervals)


def _parse_scripts_ranges(text: str) -> tuple[ScriptInterval, ...]:
    if type(text) is not str:
        raise UnicodeScriptProfileError("Scripts.txt content must be text")
    lines = text.splitlines()
    if not lines or lines[0].strip() != f"# Scripts-{UNICODE_SCRIPTS_VERSION}.txt":
        raise UnicodeScriptProfileError("Scripts.txt has the wrong Unicode version")
    if "# @missing: 0000..10FFFF; Unknown" not in {
        line.strip() for line in lines
    }:
        raise UnicodeScriptProfileError("Scripts.txt has the wrong @missing rule")

    parsed: list[ScriptInterval] = []
    for line_number, line in enumerate(lines, 1):
        body = line.split("#", 1)[0].strip()
        if not body:
            continue
        match = _SCRIPT_RANGE_RE.fullmatch(body)
        if match is None:
            raise UnicodeScriptProfileError(
                f"Scripts.txt line {line_number} is malformed"
            )
        start = int(match.group(1), 16)
        end = int(match.group(2) or match.group(1), 16)
        if start > end:
            raise UnicodeScriptProfileError(
                f"Scripts.txt line {line_number} has a descending range"
            )
        if start > UNICODE_MAX or end > UNICODE_MAX:
            raise UnicodeScriptProfileError(
                f"Scripts.txt line {line_number} contains a non-scalar range"
            )
        if start <= SURROGATE_END and end >= SURROGATE_START:
            raise UnicodeScriptProfileError(
                f"Scripts.txt line {line_number} contains a surrogate range"
            )
        parsed.append(ScriptInterval(start, end, match.group(3)))
    if not parsed:
        raise UnicodeScriptProfileError("Scripts.txt contains no script ranges")

    parsed.sort(key=lambda item: (item.start, item.end, item.script))
    for previous, current in zip(parsed, parsed[1:]):
        if current.start <= previous.end:
            raise UnicodeScriptProfileError("Scripts.txt ranges overlap")
    return tuple(parsed)


def _append_merged(
    intervals: list[ScriptInterval],
    start: int,
    end: int,
    script: str,
) -> None:
    if start > end:
        return
    if (
        intervals
        and intervals[-1].script == script
        and intervals[-1].end + 1 == start
    ):
        previous = intervals[-1]
        intervals[-1] = ScriptInterval(previous.start, end, script)
        return
    intervals.append(ScriptInterval(start, end, script))


def _complete_script_intervals(
    explicit: Sequence[ScriptInterval],
) -> tuple[ScriptInterval, ...]:
    completed: list[ScriptInterval] = []
    for domain_start, domain_end in (
        (0, SURROGATE_START - 1),
        (SURROGATE_END + 1, UNICODE_MAX),
    ):
        cursor = domain_start
        for interval in explicit:
            if interval.end < domain_start or interval.start > domain_end:
                continue
            if interval.start > cursor:
                _append_merged(completed, cursor, interval.start - 1, "Unknown")
            _append_merged(
                completed,
                interval.start,
                interval.end,
                interval.script,
            )
            cursor = interval.end + 1
        if cursor <= domain_end:
            _append_merged(completed, cursor, domain_end, "Unknown")
    return tuple(completed)


def generate_unicode_script_profile(raw_source: bytes) -> bytes:
    if type(raw_source) is not bytes:
        raise UnicodeScriptProfileError("Scripts.txt source must be immutable bytes")
    if len(raw_source) != UNICODE_SCRIPTS_SOURCE_BYTES:
        raise UnicodeScriptProfileError(
            "Scripts.txt source length does not match the frozen source"
        )
    actual_sha256 = hashlib.sha256(raw_source).hexdigest()
    if actual_sha256 != UNICODE_SCRIPTS_SOURCE_SHA256:
        raise UnicodeScriptProfileError(
            "Scripts.txt source SHA-256 does not match the frozen source"
        )
    try:
        text = raw_source.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise UnicodeScriptProfileError("Scripts.txt is not strict UTF-8") from error
    intervals = _complete_script_intervals(_parse_scripts_ranges(text))
    document = {
        "algorithm": UNICODE_SCRIPT_PROFILE_ALGORITHM,
        "format": UNICODE_SCRIPT_PROFILE_FORMAT,
        "intervalCount": len(intervals),
        "intervals": [
            [interval.start, interval.end, interval.script]
            for interval in intervals
        ],
        "profileVersion": UNICODE_SCRIPT_PROFILE_VERSION,
        "scalarCount": sum(
            interval.end - interval.start + 1 for interval in intervals
        ),
        "scriptCount": len({interval.script for interval in intervals}),
        "source": {
            "bytes": UNICODE_SCRIPTS_SOURCE_BYTES,
            "sha256": UNICODE_SCRIPTS_SOURCE_SHA256,
            "uax24Revision": UAX24_REVISION,
            "unicodeVersion": UNICODE_SCRIPTS_VERSION,
            "url": UNICODE_SCRIPTS_SOURCE_URL,
        },
    }
    encoded = canonical_json_bytes(document)
    UnicodeScriptProfile.from_json_bytes(encoded)
    return encoded


def load_unicode_script_profile(
    path: Path = DEFAULT_PROFILE_PATH,
) -> UnicodeScriptProfile:
    if not isinstance(path, Path):
        raise UnicodeScriptProfileError("profile path must be a pathlib.Path")
    try:
        with path.open("rb") as handle:
            before = os.fstat(handle.fileno())
            if before.st_size != UNICODE_SCRIPT_PROFILE_BYTES:
                raise UnicodeScriptProfileError(
                    "profile file length does not match the exact tracked profile"
                )
            try:
                raw = _read_bounded_profile_handle(handle)
            except MemoryError as error:
                raise UnicodeScriptProfileError(
                    "profile read failed because memory is unavailable"
                ) from error
            after = os.fstat(handle.fileno())
            try:
                current_path = path.stat()
            except OSError as error:
                raise UnicodeScriptProfileError(
                    "profile path was replaced while its stable handle was read"
                ) from error
    except UnicodeScriptProfileError:
        raise
    except OSError as error:
        raise UnicodeScriptProfileError("profile file could not be read") from error
    if _stable_file_identity(before) != _stable_file_identity(after):
        raise UnicodeScriptProfileError(
            "profile stable handle changed while it was read"
        )
    if _open_file_identity(after) != _open_file_identity(current_path):
        raise UnicodeScriptProfileError(
            "profile path was replaced while its stable handle was read"
        )
    return UnicodeScriptProfile.from_json_bytes(raw)


def _read_bounded_profile_handle(handle: object) -> bytes:
    raw = handle.read(UNICODE_SCRIPT_PROFILE_BYTES + 1)
    if type(raw) is not bytes:
        raise UnicodeScriptProfileError("profile handle did not return bytes")
    if len(raw) != UNICODE_SCRIPT_PROFILE_BYTES:
        raise UnicodeScriptProfileError(
            "profile file length does not match the exact tracked profile"
        )
    trailing = handle.read(1)
    if trailing:
        raise UnicodeScriptProfileError(
            "profile file length exceeds the exact tracked profile"
        )
    return raw


def _open_file_identity(value: os.stat_result) -> tuple[int, int]:
    return (value.st_dev, value.st_ino)


def _stable_file_identity(value: os.stat_result) -> tuple[int, int, int, int]:
    return (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)


class LayoutMode(IntEnum):
    SINGLE = 1
    PRIMARY_WITH_ENGLISH = 2


class EnglishGapReason(IntEnum):
    NONE = 0
    PRIMARY_NOT_ELIGIBLE = 1
    ENGLISH_FIELD_IS_PRIMARY = 2
    MISSING = 3
    WRONG_TYPE = 4
    INVALID_UTF8 = 5
    BLANK = 6
    IDENTICAL = 7
    HAS_UNKNOWN = 8
    HAS_STRONG_NON_LATIN = 9
    NO_STRONG_LATIN = 10


class ScalarScriptClass(Enum):
    NEUTRAL = "neutral"
    STRONG_LATIN = "strong-latin"
    STRONG_NON_LATIN = "strong-non-latin"
    UNKNOWN = "unknown"


class SourcedTextErrorCode(Enum):
    PRIMARY_WRONG_TYPE = "primary-wrong-type"
    PRIMARY_INVALID_UTF8 = "primary-invalid-utf8"
    PRIMARY_BLANK = "primary-blank"
    PRIMARY_TOO_LONG = "primary-too-long"
    PRIMARY_FIELD_ID_INVALID = "primary-field-id-invalid"
    ENGLISH_FIELD_ID_REQUIRED = "english-field-id-required"
    ENGLISH_FIELD_ID_INVALID = "english-field-id-invalid"
    ENGLISH_TOO_LONG = "english-too-long"


class SourcedTextError(ValueError):
    """Source text cannot satisfy the shared canonical sourced-text policy."""

    def __init__(self, code: SourcedTextErrorCode, message: str) -> None:
        if not isinstance(code, SourcedTextErrorCode):
            raise TypeError("sourced-text error code must be a SourcedTextErrorCode")
        super().__init__(message)
        self.code = code


@dataclass(frozen=True, slots=True)
class ScriptSignals:
    has_strong_latin: bool
    has_strong_non_latin: bool
    has_unknown: bool

    def __post_init__(self) -> None:
        for value in (
            self.has_strong_latin,
            self.has_strong_non_latin,
            self.has_unknown,
        ):
            if type(value) is not bool:
                raise SourcedTextError(
                    SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                    "script signals must be Boolean",
                )

    @property
    def mask(self) -> int:
        return (
            (1 if self.has_strong_latin else 0)
            | (2 if self.has_strong_non_latin else 0)
            | (4 if self.has_unknown else 0)
        )


@dataclass(frozen=True, slots=True)
class TextAnalysis:
    canonical_text: str
    signals: ScriptSignals

    @property
    def bilingual_eligible(self) -> bool:
        return self.signals.has_strong_non_latin


@dataclass(frozen=True, slots=True)
class _SourcedTextDecision:
    primary_text: str
    primary_source_field_id: int
    english_text: str | None
    english_source_field_id: int | None
    layout_mode: LayoutMode
    english_gap_reason: EnglishGapReason
    primary_script_signals: ScriptSignals
    english_script_signals: ScriptSignals | None
    _seal: object = field(repr=False, compare=False)


def _require_field_id(
    value: object,
    *,
    code: SourcedTextErrorCode,
    label: str,
) -> int:
    if type(value) is not int or not 1 <= value <= _U64_MAX:
        raise SourcedTextError(code, f"{label} must be a nonzero u64")
    return value


def _canonicalize_primary(value: object) -> str:
    if type(value) is not str:
        raise SourcedTextError(
            SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
            "primary sourced text must be text",
        )
    try:
        _bounded_utf8_bytes(
            value,
            too_long_code=SourcedTextErrorCode.PRIMARY_TOO_LONG,
            label="primary sourced text",
        )
    except UnicodeEncodeError as error:
        raise SourcedTextError(
            SourcedTextErrorCode.PRIMARY_INVALID_UTF8,
            "primary sourced text is not strict UTF-8",
        ) from error
    canonical = _end_trim(value)
    if not canonical:
        raise SourcedTextError(
            SourcedTextErrorCode.PRIMARY_BLANK,
            "primary sourced text is blank after end trimming",
        )
    return canonical


def _bounded_utf8_bytes(
    value: str,
    *,
    too_long_code: SourcedTextErrorCode,
    label: str,
) -> bytes:
    if len(value) > MAX_SOURCED_TEXT_UTF8_BYTES:
        raise SourcedTextError(
            too_long_code,
            f"{label} exceeds {MAX_SOURCED_TEXT_UTF8_BYTES} Unicode scalars",
        )
    encoded = value.encode("utf-8", "strict")
    if len(encoded) > MAX_SOURCED_TEXT_UTF8_BYTES:
        raise SourcedTextError(
            too_long_code,
            f"{label} exceeds {MAX_SOURCED_TEXT_UTF8_BYTES} UTF-8 bytes",
        )
    return encoded


def _end_trim(value: str) -> str:
    end = len(value)
    while end and ord(value[end - 1]) in _END_TRIM_SCALAR_SET:
        end -= 1
    if end == len(value):
        return value
    return value[:end]


def _append_blob(output: bytearray, value: str) -> None:
    encoded = value.encode("utf-8", "strict")
    if len(encoded) > MAX_SOURCED_TEXT_UTF8_BYTES:
        raise SourcedTextError(
            SourcedTextErrorCode.PRIMARY_TOO_LONG,
            "canonical text exceeds the sourced-text UTF-8 ceiling",
        )
    output.extend(struct.pack("<I", len(encoded)))
    output.extend(encoded)


def _canonical_sourced_text_bytes(record: "SourcedMapText") -> bytes:
    output = bytearray((SOURCED_TEXT_RECORD_TAG, SOURCED_TEXT_RECORD_VERSION))
    output.extend(record.profile_sha256)
    output.extend(record.policy_sha256)
    output.extend(
        (
            record.layout_mode.value,
            record.english_gap_reason.value,
            record.primary_script_signals.mask,
        )
    )
    output.extend(struct.pack("<Q", record.primary_source_field_id))
    _append_blob(output, record.primary_text)
    output.append(1 if record.english_source_field_id is not None else 0)
    if record.english_source_field_id is not None:
        output.extend(struct.pack("<Q", record.english_source_field_id))
    output.append(1 if record.english_text is not None else 0)
    if record.english_text is not None:
        if record.english_script_signals is None:
            raise SourcedTextError(
                SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                "accepted English text requires script signals",
            )
        output.append(record.english_script_signals.mask)
        _append_blob(output, record.english_text)
    return bytes(output)


@dataclass(frozen=True, slots=True)
class SourcedMapText:
    primary_text: str
    primary_source_field_id: int
    english_text: str | None
    english_source_field_id: int | None
    layout_mode: LayoutMode
    english_gap_reason: EnglishGapReason
    profile_sha256: bytes
    policy_sha256: bytes
    primary_script_signals: ScriptSignals
    english_script_signals: ScriptSignals | None
    declared_english_evidence: InitVar[object] = _EVIDENCE_UNSET
    _validated_decision: InitVar[_SourcedTextDecision | None] = None
    canonical_bytes: bytes = field(init=False, repr=False)
    canonical_sha256: bytes = field(init=False, repr=False)
    full_sha256: bytes = field(init=False, repr=False)
    hot_id: int = field(init=False)

    def __post_init__(
        self,
        declared_english_evidence: object,
        _validated_decision: _SourcedTextDecision | None,
    ) -> None:
        exact_derived_types = (
            (type(self.profile_sha256) is bytes, "profile identity"),
            (type(self.policy_sha256) is bytes, "policy identity"),
            (type(self.layout_mode) is LayoutMode, "layout mode"),
            (
                type(self.english_gap_reason) is EnglishGapReason,
                "English gap reason",
            ),
            (
                type(self.primary_script_signals) is ScriptSignals,
                "primary script signals",
            ),
            (
                self.english_script_signals is None
                or type(self.english_script_signals) is ScriptSignals,
                "English script signals",
            ),
        )
        for accepted, label in exact_derived_types:
            if not accepted:
                raise SourcedTextError(
                    SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                    f"record {label} has the wrong exact type",
                )
        expected_profile = DEFAULT_UNICODE_SCRIPT_PROFILE.profile_sha256
        expected_policy = DEFAULT_SOURCED_TEXT_POLICY.policy_sha256
        if self.profile_sha256 != expected_profile:
            raise SourcedTextError(
                SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                "record Unicode profile identity is not the exact frozen profile",
            )
        if self.policy_sha256 != expected_policy:
            raise SourcedTextError(
                SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                "record policy identity is not the exact shared policy",
            )
        if _validated_decision is not None:
            if (
                type(_validated_decision) is not _SourcedTextDecision
                or _validated_decision._seal is not _DECISION_SEAL
            ):
                raise SourcedTextError(
                    SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                    "record decision proof is invalid",
                )
            decision = _validated_decision
        else:
            if declared_english_evidence is _EVIDENCE_UNSET:
                if self.english_text is not None:
                    declared_english_evidence = self.english_text
                elif (
                    self.english_gap_reason
                    in {
                        EnglishGapReason.MISSING,
                        EnglishGapReason.PRIMARY_NOT_ELIGIBLE,
                        EnglishGapReason.ENGLISH_FIELD_IS_PRIMARY,
                    }
                ):
                    declared_english_evidence = None
                else:
                    raise SourcedTextError(
                        SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                        "detached declared-English evidence is required to validate this gap",
                    )
            decision = _decide_sourced_text(
                DEFAULT_SOURCED_TEXT_POLICY,
                primary=self.primary_text,
                primary_source_field_id=self.primary_source_field_id,
                declared_english=declared_english_evidence,
                english_source_field_id=self.english_source_field_id,
            )
        supplied = (
            self.primary_text,
            self.primary_source_field_id,
            self.english_text,
            self.english_source_field_id,
            self.layout_mode,
            self.english_gap_reason,
            self.primary_script_signals,
            self.english_script_signals,
        )
        expected = (
            decision.primary_text,
            decision.primary_source_field_id,
            decision.english_text,
            decision.english_source_field_id,
            decision.layout_mode,
            decision.english_gap_reason,
            decision.primary_script_signals,
            decision.english_script_signals,
        )
        if supplied != expected:
            raise SourcedTextError(
                SourcedTextErrorCode.PRIMARY_WRONG_TYPE,
                "record fields do not match the one shared sourced-text decision",
            )
        canonical = _canonical_sourced_text_bytes(self)
        full_sha256 = hashlib.sha256(
            SOURCED_TEXT_IDENTITY_DOMAIN + canonical
        ).digest()
        object.__setattr__(self, "canonical_bytes", canonical)
        object.__setattr__(self, "canonical_sha256", hashlib.sha256(canonical).digest())
        object.__setattr__(self, "full_sha256", full_sha256)
        object.__setattr__(self, "hot_id", int.from_bytes(full_sha256[:8], "big"))


def _policy_descriptor(profile: UnicodeScriptProfile) -> dict[str, object]:
    return {
        "canonicalization": {
            "comparison": "exact",
            "endTrimProperty": "White_Space",
            "endTrimScalars": list(END_TRIM_SCALARS),
            "endTrimSourceBytes": UNICODE_PROPLIST_SOURCE_BYTES,
            "endTrimSourceSha256": UNICODE_PROPLIST_SOURCE_SHA256,
            "endTrimSourceUrl": UNICODE_PROPLIST_SOURCE_URL,
            "normalization": "none",
            "utf8": "strict",
        },
        "englishGapPrecedence": [
            "ENGLISH_FIELD_IS_PRIMARY",
            "PRIMARY_NOT_ELIGIBLE",
            "MISSING",
            "WRONG_TYPE",
            "INVALID_UTF8",
            "BLANK",
            "IDENTICAL",
            "HAS_UNKNOWN",
            "HAS_STRONG_NON_LATIN",
            "NO_STRONG_LATIN",
        ],
        "decisionPath": SHARED_DECISION_PATH_ID,
        "format": SOURCED_TEXT_POLICY_FORMAT,
        "layoutModes": ["SINGLE", "PRIMARY_WITH_ENGLISH"],
        "layerSpecificDecisionOverrides": False,
        "maxSourcedTextUtf8Bytes": MAX_SOURCED_TEXT_UTF8_BYTES,
        "presentationToken": BILINGUAL_PRESENTATION_TOKEN,
        "profile": {
            "algorithm": profile.algorithm,
            "sha256": profile.profile_sha256.hex(),
            "sourceSha256": profile.source_sha256.hex(),
        },
        "record": {
            "domainHex": SOURCED_TEXT_IDENTITY_DOMAIN.hex(),
            "tag": SOURCED_TEXT_RECORD_TAG,
            "version": SOURCED_TEXT_RECORD_VERSION,
        },
        "scriptPolicy": {
            "Common": "neutral",
            "Inherited": "neutral",
            "Latin": "strong-latin",
            "Unknown": "audited-neutral",
            "otherAssigned": "strong-non-latin",
        },
        "sourceRoles": "caller-verified-primary-and-declared-english-only",
        "scope": "all-data-derived-map-text",
        "version": SOURCED_TEXT_POLICY_VERSION,
    }


@dataclass(frozen=True, slots=True)
class SourcedTextPolicy:
    profile: UnicodeScriptProfile
    _construction_seal: InitVar[object] = None
    descriptor_bytes: bytes = field(init=False, repr=False)
    policy_sha256: bytes = field(init=False)
    _is_authorized_shared_policy: bool = field(
        init=False,
        repr=False,
        compare=False,
    )

    def __post_init__(self, _construction_seal: object) -> None:
        if _construction_seal is not _POLICY_CONSTRUCTION_SEAL:
            raise UnicodeScriptProfileError(
                "sourced-text policies must be constructed by the shared policy loader"
            )
        if (
            type(self.profile) is not UnicodeScriptProfile
            or self.profile._is_exact_frozen_profile is not True
        ):
            raise UnicodeScriptProfileError(
                "sourced-text policy requires the exact frozen Unicode profile"
            )
        _require_exact_frozen_profile_fields(self.profile)
        descriptor_bytes = canonical_json_bytes(_policy_descriptor(self.profile))
        object.__setattr__(self, "descriptor_bytes", descriptor_bytes)
        object.__setattr__(
            self,
            "policy_sha256",
            hashlib.sha256(SOURCED_TEXT_POLICY_DOMAIN + descriptor_bytes).digest(),
        )
        object.__setattr__(self, "_is_authorized_shared_policy", True)

    def classify_scalar(self, scalar: int) -> ScalarScriptClass:
        script = self.profile.script_for_scalar(scalar)
        if script in {"Common", "Inherited"}:
            return ScalarScriptClass.NEUTRAL
        if script == "Latin":
            return ScalarScriptClass.STRONG_LATIN
        if script == "Unknown":
            return ScalarScriptClass.UNKNOWN
        return ScalarScriptClass.STRONG_NON_LATIN

    def analyze_text(self, value: object) -> TextAnalysis:
        canonical = _canonicalize_primary(value)
        has_latin = False
        has_non_latin = False
        has_unknown = False
        for character in canonical:
            classification = self.classify_scalar(ord(character))
            if classification is ScalarScriptClass.STRONG_LATIN:
                has_latin = True
            elif classification is ScalarScriptClass.STRONG_NON_LATIN:
                has_non_latin = True
            elif classification is ScalarScriptClass.UNKNOWN:
                has_unknown = True
        return TextAnalysis(
            canonical_text=canonical,
            signals=ScriptSignals(
                has_strong_latin=has_latin,
                has_strong_non_latin=has_non_latin,
                has_unknown=has_unknown,
            ),
        )

    def create(
        self,
        *,
        primary: object,
        primary_source_field_id: object,
        declared_english: object = None,
        english_source_field_id: object = None,
    ) -> SourcedMapText:
        if (
            type(self) is not SourcedTextPolicy
            or self._is_authorized_shared_policy is not True
            or self is not DEFAULT_SOURCED_TEXT_POLICY
        ):
            raise UnicodeScriptProfileError(
                "records require the authorized shared sourced-text policy"
            )
        decision = _decide_sourced_text(
            self,
            primary=primary,
            primary_source_field_id=primary_source_field_id,
            declared_english=declared_english,
            english_source_field_id=english_source_field_id,
        )
        return SourcedMapText(
            primary_text=decision.primary_text,
            primary_source_field_id=decision.primary_source_field_id,
            english_text=decision.english_text,
            english_source_field_id=decision.english_source_field_id,
            layout_mode=decision.layout_mode,
            english_gap_reason=decision.english_gap_reason,
            profile_sha256=self.profile.profile_sha256,
            policy_sha256=self.policy_sha256,
            primary_script_signals=decision.primary_script_signals,
            english_script_signals=decision.english_script_signals,
            declared_english_evidence=declared_english,
            _validated_decision=decision,
        )


def _single_decision(
    *,
    primary: TextAnalysis,
    primary_source_field_id: int,
    english_source_field_id: int | None,
    gap: EnglishGapReason,
) -> _SourcedTextDecision:
    return _SourcedTextDecision(
        primary_text=primary.canonical_text,
        primary_source_field_id=primary_source_field_id,
        english_text=None,
        english_source_field_id=english_source_field_id,
        layout_mode=LayoutMode.SINGLE,
        english_gap_reason=gap,
        primary_script_signals=primary.signals,
        english_script_signals=None,
        _seal=_DECISION_SEAL,
    )


def _decide_sourced_text(
    policy: SourcedTextPolicy,
    *,
    primary: object,
    primary_source_field_id: object,
    declared_english: object,
    english_source_field_id: object,
) -> _SourcedTextDecision:
    if (
        type(policy) is not SourcedTextPolicy
        or policy._is_authorized_shared_policy is not True
        or policy is not DEFAULT_SOURCED_TEXT_POLICY
    ):
        raise TypeError("sourced-text decision requires the shared policy")
    english_is_strict_utf8 = True
    if type(declared_english) is str:
        try:
            _bounded_utf8_bytes(
                declared_english,
                too_long_code=SourcedTextErrorCode.ENGLISH_TOO_LONG,
                label="declared English sourced text",
            )
        except UnicodeEncodeError:
            english_is_strict_utf8 = False
    primary_analysis = policy.analyze_text(primary)
    primary_id = _require_field_id(
        primary_source_field_id,
        code=SourcedTextErrorCode.PRIMARY_FIELD_ID_INVALID,
        label="primary source-field ID",
    )
    english_id: int | None
    if english_source_field_id is None:
        english_id = None
    else:
        english_id = _require_field_id(
            english_source_field_id,
            code=SourcedTextErrorCode.ENGLISH_FIELD_ID_INVALID,
            label="English source-field ID",
        )
    if declared_english is not None and english_id is None:
        raise SourcedTextError(
            SourcedTextErrorCode.ENGLISH_FIELD_ID_REQUIRED,
            "declared English requires a nonzero source-field ID",
        )
    if english_id == primary_id:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.ENGLISH_FIELD_IS_PRIMARY,
        )
    if not primary_analysis.bilingual_eligible:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.PRIMARY_NOT_ELIGIBLE,
        )
    if declared_english is None:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.MISSING,
        )
    if type(declared_english) is not str:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.WRONG_TYPE,
        )
    if not english_is_strict_utf8:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.INVALID_UTF8,
        )
    canonical_english = _end_trim(declared_english)
    if not canonical_english:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.BLANK,
        )
    if canonical_english == primary_analysis.canonical_text:
        return _single_decision(
            primary=primary_analysis,
            primary_source_field_id=primary_id,
            english_source_field_id=english_id,
            gap=EnglishGapReason.IDENTICAL,
        )
    english_analysis = policy.analyze_text(canonical_english)
    if english_analysis.signals.has_unknown:
        gap = EnglishGapReason.HAS_UNKNOWN
    elif english_analysis.signals.has_strong_non_latin:
        gap = EnglishGapReason.HAS_STRONG_NON_LATIN
    elif not english_analysis.signals.has_strong_latin:
        gap = EnglishGapReason.NO_STRONG_LATIN
    else:
        return _SourcedTextDecision(
            primary_text=primary_analysis.canonical_text,
            primary_source_field_id=primary_id,
            english_text=english_analysis.canonical_text,
            english_source_field_id=english_id,
            layout_mode=LayoutMode.PRIMARY_WITH_ENGLISH,
            english_gap_reason=EnglishGapReason.NONE,
            primary_script_signals=primary_analysis.signals,
            english_script_signals=english_analysis.signals,
            _seal=_DECISION_SEAL,
        )
    return _single_decision(
        primary=primary_analysis,
        primary_source_field_id=primary_id,
        english_source_field_id=english_id,
        gap=gap,
    )


DEFAULT_UNICODE_SCRIPT_PROFILE = load_unicode_script_profile()
DEFAULT_SOURCED_TEXT_POLICY = SourcedTextPolicy(
    DEFAULT_UNICODE_SCRIPT_PROFILE,
    _construction_seal=_POLICY_CONSTRUCTION_SEAL,
)


def create_sourced_map_text(
    *,
    primary: object,
    primary_source_field_id: object,
    declared_english: object = None,
    english_source_field_id: object = None,
) -> SourcedMapText:
    return DEFAULT_SOURCED_TEXT_POLICY.create(
        primary=primary,
        primary_source_field_id=primary_source_field_id,
        declared_english=declared_english,
        english_source_field_id=english_source_field_id,
    )


def _main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Generate the frozen Flight Alert Unicode script profile."
    )
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args(argv)
    generated = generate_unicode_script_profile(arguments.source.read_bytes())
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_bytes(generated)
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
