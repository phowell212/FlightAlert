from __future__ import annotations

import copy
from dataclasses import FrozenInstanceError, fields, replace
import hashlib
import json
from pathlib import Path
import pickle
import re
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

from tools.experiment8 import sourced_text


REPO_ROOT = Path(__file__).resolve().parents[3]
SOURCE_PATH = Path(r"C:\FlightAlert-exp8-work\unicode\Scripts-17.0.0.txt")
PROPLIST_PATH = Path(r"C:\FlightAlert-exp8-work\unicode\PropList-17.0.0.txt")
PROFILE_PATH = (
    REPO_ROOT
    / "tools"
    / "experiment8"
    / "data"
    / "unicode-script-profile-17.0.0.json"
)
CONFORMANCE_PATH = (
    REPO_ROOT
    / "tools"
    / "experiment8"
    / "data"
    / "sourced-text-conformance-v1.json"
)


def _document_bytes(document: object) -> bytes:
    return sourced_text.canonical_json_bytes(document)


def _profile_document() -> dict[str, object]:
    document = json.loads(PROFILE_PATH.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise AssertionError("profile fixture must be a JSON object")
    return document


def _white_space_scalars(raw: bytes) -> tuple[int, ...]:
    text = raw.decode("utf-8", "strict")
    scalars: list[int] = []
    pattern = re.compile(
        r"([0-9A-F]{4,6})(?:\.\.([0-9A-F]{4,6}))?\s*;\s*White_Space"
    )
    for line in text.splitlines():
        body = line.split("#", 1)[0].strip()
        match = pattern.fullmatch(body)
        if match is None:
            continue
        start = int(match.group(1), 16)
        end = int(match.group(2) or match.group(1), 16)
        scalars.extend(range(start, end + 1))
    return tuple(scalars)


class FrozenUnicodeSourceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source_bytes = SOURCE_PATH.read_bytes()

    def test_frozen_unicode_source_has_exact_identity(self) -> None:
        self.assertEqual(len(self.source_bytes), 192_460)
        self.assertEqual(
            hashlib.sha256(self.source_bytes).hexdigest(),
            "9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf",
        )

    def test_frozen_white_space_source_and_scalar_set_have_exact_identity(self) -> None:
        raw = PROPLIST_PATH.read_bytes()
        self.assertEqual(len(raw), 145_465)
        self.assertEqual(
            hashlib.sha256(raw).hexdigest(),
            "130dcddcaadaf071008bdfce1e7743e04fdfbc910886f017d9f9ac931d8c64dd",
        )
        self.assertEqual(
            _white_space_scalars(raw),
            sourced_text.END_TRIM_SCALARS,
        )

    def test_generator_reproduces_tracked_profile_byte_for_byte(self) -> None:
        self.assertEqual(
            sourced_text.generate_unicode_script_profile(self.source_bytes),
            PROFILE_PATH.read_bytes(),
        )

    def test_generator_rejects_wrong_source_length(self) -> None:
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "length",
        ):
            sourced_text.generate_unicode_script_profile(self.source_bytes[:-1])

    def test_generator_rejects_wrong_source_hash_at_the_right_length(self) -> None:
        mutated = bytearray(self.source_bytes)
        mutated[-1] ^= 1
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "SHA-256",
        ):
            sourced_text.generate_unicode_script_profile(bytes(mutated))

    def test_source_parser_rejects_wrong_unicode_version(self) -> None:
        text = self.source_bytes.decode("utf-8").replace(
            "# Scripts-17.0.0.txt",
            "# Scripts-16.0.0.txt",
            1,
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "Unicode version",
        ):
            sourced_text._parse_scripts_ranges(text)

    def test_source_parser_rejects_malformed_range(self) -> None:
        text = "\n".join(
            (
                "# Scripts-17.0.0.txt",
                "# @missing: 0000..10FFFF; Unknown",
                "0000...0001 ; Common",
            )
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "malformed",
        ):
            sourced_text._parse_scripts_ranges(text)

    def test_source_parser_rejects_overlapping_ranges(self) -> None:
        text = "\n".join(
            (
                "# Scripts-17.0.0.txt",
                "# @missing: 0000..10FFFF; Unknown",
                "0000..0002 ; Common",
                "0002..0003 ; Latin",
            )
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "overlap",
        ):
            sourced_text._parse_scripts_ranges(text)

    def test_source_parser_rejects_descending_range(self) -> None:
        text = "\n".join(
            (
                "# Scripts-17.0.0.txt",
                "# @missing: 0000..10FFFF; Unknown",
                "0002..0001 ; Common",
            )
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "descending",
        ):
            sourced_text._parse_scripts_ranges(text)

    def test_source_parser_rejects_surrogate_range(self) -> None:
        text = "\n".join(
            (
                "# Scripts-17.0.0.txt",
                "# @missing: 0000..10FFFF; Unknown",
                "D800 ; Unknown",
            )
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "surrogate",
        ):
            sourced_text._parse_scripts_ranges(text)

    def test_source_parser_rejects_non_scalar_range(self) -> None:
        text = "\n".join(
            (
                "# Scripts-17.0.0.txt",
                "# @missing: 0000..10FFFF; Unknown",
                "110000 ; Unknown",
            )
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "scalar",
        ):
            sourced_text._parse_scripts_ranges(text)


class UnicodeScriptProfileTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.raw_profile = PROFILE_PATH.read_bytes()
        cls.profile = sourced_text.UnicodeScriptProfile.from_json_bytes(
            cls.raw_profile
        )

    def test_profile_json_is_canonical_and_identity_bound(self) -> None:
        document = json.loads(self.raw_profile.decode("utf-8"))
        self.assertEqual(self.raw_profile, _document_bytes(document))
        self.assertEqual(
            self.profile.profile_sha256,
            hashlib.sha256(self.raw_profile).digest(),
        )
        interval_document = [
            [interval.start, interval.end, interval.script]
            for interval in self.profile.intervals
        ]
        self.assertEqual(
            hashlib.sha256(_document_bytes(interval_document)).hexdigest(),
            sourced_text.UNICODE_SCRIPT_INTERVALS_SHA256,
        )

    def test_public_profile_construction_cannot_forge_derived_intervals(self) -> None:
        first = self.profile.intervals[0]
        forged_intervals = (
            sourced_text.ScriptInterval(first.start, first.end, "Latin"),
            *self.profile.intervals[1:],
        )
        with self.assertRaises(sourced_text.UnicodeScriptProfileError):
            replace(self.profile, intervals=forged_intervals)

        with self.assertRaises(sourced_text.UnicodeScriptProfileError):
            sourced_text.UnicodeScriptProfile(
                intervals=self.profile.intervals,
                profile_sha256=self.profile.profile_sha256,
                unicode_version=self.profile.unicode_version,
                uax24_revision=self.profile.uax24_revision,
                source_sha256=self.profile.source_sha256,
                source_bytes=self.profile.source_bytes,
                source_url=self.profile.source_url,
                algorithm=self.profile.algorithm,
            )

    def test_profile_loader_checks_frozen_length_and_hash_before_json(self) -> None:
        mutations = (
            ("truncated", self.raw_profile[:-1], "length"),
            ("oversized", self.raw_profile + b" ", "length"),
            (
                "same-length replacement",
                b"X" + self.raw_profile[1:],
                "SHA-256",
            ),
        )
        for label, raw, message in mutations:
            with self.subTest(case=label), mock.patch.object(
                sourced_text.json,
                "loads",
                side_effect=AssertionError("JSON parsing must not run"),
            ), self.assertRaisesRegex(
                sourced_text.UnicodeScriptProfileError,
                message,
            ):
                sourced_text.UnicodeScriptProfile.from_json_bytes(raw)

    def test_profile_loader_translates_json_memory_error(self) -> None:
        with mock.patch.object(
            sourced_text.json,
            "loads",
            side_effect=MemoryError,
        ), self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "memory",
        ):
            sourced_text.UnicodeScriptProfile.from_json_bytes(self.raw_profile)

    def test_path_loader_uses_bounded_handle_not_path_read_bytes(self) -> None:
        with mock.patch.object(
            Path,
            "read_bytes",
            side_effect=AssertionError("unbounded Path.read_bytes is forbidden"),
        ):
            profile = sourced_text.load_unicode_script_profile(PROFILE_PATH)
        self.assertEqual(profile.profile_sha256, self.profile.profile_sha256)

    def test_path_loader_rejects_truncation_and_oversize(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for label, raw in (
                ("truncated", self.raw_profile[:-1]),
                ("oversized", self.raw_profile + b"x"),
            ):
                with self.subTest(case=label):
                    path = root / f"{label}.json"
                    path.write_bytes(raw)
                    with self.assertRaisesRegex(
                        sourced_text.UnicodeScriptProfileError,
                        "length",
                    ):
                        sourced_text.load_unicode_script_profile(path)

    def test_path_loader_rejects_replacement_after_open(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / "profile.json"
            path.write_bytes(self.raw_profile)
            original_stat = Path.stat
            actual = path.stat()

            def replaced_stat(candidate: Path, *args: object, **kwargs: object) -> object:
                if candidate == path:
                    return SimpleNamespace(
                        st_dev=actual.st_dev,
                        st_ino=actual.st_ino + 1,
                    )
                return original_stat(candidate, *args, **kwargs)

            with mock.patch.object(
                Path,
                "stat",
                new=replaced_stat,
            ), self.assertRaisesRegex(
                sourced_text.UnicodeScriptProfileError,
                "replaced",
            ):
                sourced_text.load_unicode_script_profile(path)

    def test_path_loader_translates_read_memory_error(self) -> None:
        with mock.patch.object(
            sourced_text,
            "_read_bounded_profile_handle",
            side_effect=MemoryError,
            create=True,
        ), self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "memory",
        ):
            sourced_text.load_unicode_script_profile(PROFILE_PATH)
        self.assertEqual(self.profile.unicode_version, "17.0.0")
        self.assertEqual(self.profile.uax24_revision, 39)
        self.assertEqual(
            self.profile.source_sha256,
            bytes.fromhex(
                "9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf"
            ),
        )

    def test_profile_covers_every_unicode_scalar_exactly_once(self) -> None:
        expected_start = 0
        covered = 0
        for interval in self.profile.intervals:
            if expected_start == 0xD800:
                expected_start = 0xE000
            self.assertEqual(interval.start, expected_start)
            self.assertLessEqual(interval.start, interval.end)
            self.assertFalse(interval.start <= 0xDFFF and interval.end >= 0xD800)
            covered += interval.end - interval.start + 1
            expected_start = interval.end + 1
        self.assertEqual(expected_start, 0x110000)
        self.assertEqual(covered, 0x110000 - 0x800)

        classified = 0
        for scalar in range(0x110000):
            if 0xD800 <= scalar <= 0xDFFF:
                continue
            self.assertIsInstance(self.profile.script_for_scalar(scalar), str)
            classified += 1
        self.assertEqual(classified, covered)

    def test_profile_distinguishes_required_script_classes(self) -> None:
        self.assertEqual(self.profile.script_for_scalar(ord("A")), "Latin")
        self.assertEqual(self.profile.script_for_scalar(0x0301), "Inherited")
        self.assertEqual(self.profile.script_for_scalar(ord("0")), "Common")
        self.assertEqual(self.profile.script_for_scalar(0x1F600), "Common")
        self.assertEqual(self.profile.script_for_scalar(0xE000), "Unknown")
        self.assertEqual(self.profile.script_for_scalar(ord("ق")), "Arabic")
        self.assertEqual(self.profile.script_for_scalar(ord("東")), "Han")

    def test_profile_rejects_surrogates_and_non_scalars_at_lookup(self) -> None:
        for scalar in (-1, 0xD800, 0xDFFF, 0x110000):
            with self.subTest(scalar=scalar), self.assertRaisesRegex(
                sourced_text.UnicodeScriptProfileError,
                "scalar",
            ):
                self.profile.script_for_scalar(scalar)

    def test_profile_loader_rejects_noncanonical_json(self) -> None:
        noncanonical = json.dumps(
            _profile_document(),
            ensure_ascii=False,
            indent=2,
        ).encode("utf-8")
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "canonical JSON",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(noncanonical)

    def test_profile_loader_rejects_duplicate_json_keys(self) -> None:
        duplicate = self.raw_profile.replace(
            b'{"algorithm":',
            b'{"algorithm":"duplicate","algorithm":',
            1,
        )
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "duplicate",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(duplicate)

    def test_profile_loader_rejects_wrong_version(self) -> None:
        document = _profile_document()
        document["profileVersion"] = 2
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "profile version",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_non_integer_numeric_metadata(self) -> None:
        mutations = (
            ("profileVersion", 1.0),
            ("intervalCount", float(len(self.profile.intervals))),
            ("scalarCount", float(0x110000 - 0x800)),
            (
                "scriptCount",
                float(len({item.script for item in self.profile.intervals})),
            ),
        )
        for key, value in mutations:
            with self.subTest(key=key):
                document = _profile_document()
                document[key] = value
                with self.assertRaisesRegex(
                    sourced_text.UnicodeScriptProfileError,
                    "integer",
                ):
                    sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                        _document_bytes(document)
                    )

        for key, value in (("bytes", 192_460.0), ("uax24Revision", 39.0)):
            with self.subTest(source_key=key):
                document = _profile_document()
                source = document["source"]
                if not isinstance(source, dict):
                    raise AssertionError("profile source metadata must be an object")
                source[key] = value
                with self.assertRaisesRegex(
                    sourced_text.UnicodeScriptProfileError,
                    "integer",
                ):
                    sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                        _document_bytes(document)
                    )

    def test_profile_loader_rejects_wrong_source_identity(self) -> None:
        document = _profile_document()
        source = document["source"]
        if not isinstance(source, dict):
            raise AssertionError("profile source metadata must be an object")
        source["sha256"] = "0" * 64
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "source SHA-256",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_gapped_ranges(self) -> None:
        document = _profile_document()
        intervals = document["intervals"]
        if not isinstance(intervals, list) or not isinstance(intervals[0], list):
            raise AssertionError("profile intervals must be a list of lists")
        intervals[0][0] = 1
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "gap",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_overlapping_ranges(self) -> None:
        document = _profile_document()
        intervals = document["intervals"]
        if not isinstance(intervals, list):
            raise AssertionError("profile intervals must be a list")
        first = intervals[0]
        second = intervals[1]
        if not isinstance(first, list) or not isinstance(second, list):
            raise AssertionError("profile intervals must contain lists")
        second[0] = first[1]
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "overlap",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_unsorted_ranges(self) -> None:
        document = _profile_document()
        intervals = document["intervals"]
        if not isinstance(intervals, list):
            raise AssertionError("profile intervals must be a list")
        intervals[10], intervals[11] = intervals[11], intervals[10]
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "unsorted",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_surrogate_ranges(self) -> None:
        document = _profile_document()
        intervals = document["intervals"]
        if not isinstance(intervals, list):
            raise AssertionError("profile intervals must be a list")
        before_surrogates = next(
            interval
            for interval in intervals
            if isinstance(interval, list) and interval[1] == 0xD7FF
        )
        before_surrogates[1] = 0xD800
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "surrogate",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_non_scalar_ranges(self) -> None:
        document = _profile_document()
        intervals = document["intervals"]
        if not isinstance(intervals, list) or not isinstance(intervals[-1], list):
            raise AssertionError("profile intervals must be a list of lists")
        intervals[-1][1] = 0x110000
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "scalar",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )

    def test_profile_loader_rejects_descending_ranges(self) -> None:
        document = _profile_document()
        intervals = document["intervals"]
        if not isinstance(intervals, list) or not isinstance(intervals[4], list):
            raise AssertionError("profile intervals must be a list of lists")
        intervals[4][0] = intervals[4][1] + 1
        with self.assertRaisesRegex(
            sourced_text.UnicodeScriptProfileError,
            "descending",
        ):
            sourced_text.UnicodeScriptProfile._from_generated_json_bytes(
                _document_bytes(document)
            )


def _signals_document(signals: object) -> dict[str, bool]:
    return {
        "hasStrongLatin": signals.has_strong_latin,
        "hasStrongNonLatin": signals.has_strong_non_latin,
        "hasUnknown": signals.has_unknown,
    }


def _input_value(document: dict[str, object], name: str) -> object:
    if name in document:
        return document[name]
    repeat_key = f"{name}Repeat"
    if repeat_key in document:
        repeat = document[repeat_key]
        if not isinstance(repeat, dict) or set(repeat) != {"text", "count"}:
            raise AssertionError(
                f"{repeat_key} must contain exactly text and count"
            )
        text = repeat["text"]
        count = repeat["count"]
        if type(text) is not str or type(count) is not int or count < 0:
            raise AssertionError(
                f"{repeat_key} text/count types are invalid"
            )
        return text * count
    scalar_key = f"{name}Scalars"
    if scalar_key in document:
        scalars = document[scalar_key]
        if not isinstance(scalars, list):
            raise AssertionError(f"{scalar_key} must be a list")
        return "".join(chr(value) for value in scalars)
    return None


class SourcedTextConformanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.raw_vectors = CONFORMANCE_PATH.read_bytes()
        cls.vectors = json.loads(cls.raw_vectors.decode("utf-8"))
        if not isinstance(cls.vectors, dict):
            raise AssertionError("conformance vectors must contain an object")
        cls.policy = sourced_text.DEFAULT_SOURCED_TEXT_POLICY

    def test_conformance_json_and_policy_identities_are_canonical(self) -> None:
        self.assertEqual(self.raw_vectors, _document_bytes(self.vectors))
        self.assertEqual(
            self.vectors["format"],
            "flightalert-sourced-text-conformance",
        )
        self.assertEqual(self.vectors["version"], 1)
        identities = self.vectors["identities"]
        self.assertEqual(
            identities["profileSha256"],
            sourced_text.DEFAULT_UNICODE_SCRIPT_PROFILE.profile_sha256.hex(),
        )
        self.assertEqual(
            identities["policySha256"],
            self.policy.policy_sha256.hex(),
        )
        self.assertEqual(
            identities["sourceSha256"],
            sourced_text.UNICODE_SCRIPTS_SOURCE_SHA256,
        )
        self.assertEqual(
            identities["recordIdentityDomainHex"],
            sourced_text.SOURCED_TEXT_IDENTITY_DOMAIN.hex(),
        )
        self.assertEqual(
            identities["policyIdentityDomainHex"],
            sourced_text.SOURCED_TEXT_POLICY_DOMAIN.hex(),
        )

    def test_vectors_bind_exactly_one_shared_policy_token_and_decision_path(self) -> None:
        identities = self.vectors["identities"]
        self.assertEqual(identities["sharedPolicyCount"], 1)
        self.assertEqual(identities["presentationTokenCount"], 1)
        self.assertEqual(identities["decisionPathCount"], 1)
        self.assertEqual(
            identities["bilingualPresentationToken"],
            sourced_text.BILINGUAL_PRESENTATION_TOKEN,
        )
        self.assertEqual(
            identities["decisionPathId"],
            sourced_text.SHARED_DECISION_PATH_ID,
        )
        descriptor = json.loads(self.policy.descriptor_bytes.decode("utf-8"))
        self.assertEqual(descriptor["decisionPath"], identities["decisionPathId"])
        self.assertEqual(descriptor["scope"], "all-data-derived-map-text")
        self.assertIs(descriptor["layerSpecificDecisionOverrides"], False)

    def test_scalar_vectors_use_the_public_profile_classification(self) -> None:
        for case in self.vectors["scalarCases"]:
            with self.subTest(case=case["id"]):
                if case["expected"] == "ERROR":
                    with self.assertRaises(sourced_text.UnicodeScriptProfileError):
                        self.policy.classify_scalar(case["scalar"])
                else:
                    self.assertEqual(
                        self.policy.classify_scalar(case["scalar"]).name,
                        case["expected"],
                    )

    def test_text_vectors_use_the_public_shared_analysis(self) -> None:
        for case in self.vectors["textCases"]:
            with self.subTest(case=case["id"]):
                analysis = self.policy.analyze_text(case["text"])
                self.assertEqual(
                    analysis.canonical_text,
                    case["expected"]["canonicalText"],
                )
                self.assertEqual(
                    _signals_document(analysis.signals),
                    case["expected"]["signals"],
                )
                self.assertEqual(
                    analysis.bilingual_eligible,
                    case["expected"]["bilingualEligible"],
                )

    def test_source_exact_vectors_never_use_runtime_normalization_or_whitespace(self) -> None:
        for case in self.vectors["sourceExactCases"]:
            with self.subTest(case=case["id"]):
                analysis = self.policy.analyze_text(case["text"])
                self.assertEqual(
                    analysis.canonical_text,
                    case["expected"]["canonicalText"],
                )
                self.assertEqual(
                    _signals_document(analysis.signals),
                    case["expected"]["signals"],
                )
        descriptor = json.loads(self.policy.descriptor_bytes.decode("utf-8"))
        canonicalization = descriptor["canonicalization"]
        self.assertEqual(canonicalization["normalization"], "none")
        self.assertEqual(
            canonicalization["endTrimScalars"],
            list(sourced_text.END_TRIM_SCALARS),
        )
        self.assertEqual(
            canonicalization["endTrimSourceSha256"],
            sourced_text.UNICODE_PROPLIST_SOURCE_SHA256,
        )

    def test_record_vectors_use_the_public_shared_policy(self) -> None:
        for case in self.vectors["recordCases"]:
            with self.subTest(case=case["id"]):
                inputs = case["input"]
                expected = case["expected"]
                record = self.policy.create(
                    primary=_input_value(inputs, "primary"),
                    primary_source_field_id=inputs["primarySourceFieldId"],
                    declared_english=_input_value(inputs, "english"),
                    english_source_field_id=inputs.get("englishSourceFieldId"),
                )
                self.assertEqual(record.primary_text, expected["primaryText"])
                self.assertEqual(record.english_text, expected["englishText"])
                self.assertEqual(
                    record.primary_source_field_id,
                    expected["primarySourceFieldId"],
                )
                self.assertEqual(
                    record.english_source_field_id,
                    expected["englishSourceFieldId"],
                )
                self.assertEqual(record.layout_mode.name, expected["layout"])
                self.assertEqual(
                    record.english_gap_reason.name,
                    expected["englishGap"],
                )
                self.assertEqual(
                    _signals_document(record.primary_script_signals),
                    expected["primarySignals"],
                )
                actual_english_signals = (
                    None
                    if record.english_script_signals is None
                    else _signals_document(record.english_script_signals)
                )
                self.assertEqual(
                    actual_english_signals,
                    expected["englishSignals"],
                )
                self.assertEqual(
                    hashlib.sha256(record.canonical_bytes).hexdigest(),
                    expected["canonicalSha256"],
                )
                self.assertEqual(
                    record.full_sha256.hex(),
                    expected["fullSha256"],
                )
                self.assertEqual(
                    f"{record.hot_id:016x}",
                    expected["hotIdHex"],
                )
                if "canonicalBytesHex" in expected:
                    self.assertEqual(
                        record.canonical_bytes.hex(),
                        expected["canonicalBytesHex"],
                    )

    def test_invalid_vectors_fail_with_exact_public_error_codes(self) -> None:
        for case in self.vectors["invalidCases"]:
            with self.subTest(case=case["id"]):
                inputs = case["input"]
                with self.assertRaises(sourced_text.SourcedTextError) as caught:
                    self.policy.create(
                        primary=_input_value(inputs, "primary"),
                        primary_source_field_id=inputs["primarySourceFieldId"],
                        declared_english=_input_value(inputs, "english"),
                        english_source_field_id=inputs.get(
                            "englishSourceFieldId"
                        ),
                    )
                self.assertEqual(caught.exception.code.name, case["errorCode"])

    def test_vector_identity_mutation_groups_are_all_distinct(self) -> None:
        records: dict[str, object] = {}
        for case in self.vectors["recordCases"]:
            inputs = case["input"]
            records[case["id"]] = self.policy.create(
                primary=_input_value(inputs, "primary"),
                primary_source_field_id=inputs["primarySourceFieldId"],
                declared_english=_input_value(inputs, "english"),
                english_source_field_id=inputs.get("englishSourceFieldId"),
            )
        for group in self.vectors["identityMutationGroups"]:
            with self.subTest(group=group["id"]):
                identities = {
                    records[case_id].full_sha256
                    for case_id in group["caseIds"]
                }
                self.assertEqual(len(identities), len(group["caseIds"]))

    def test_vector_equivalence_and_non_equivalence_groups_are_exact(self) -> None:
        records: dict[str, object] = {}
        for case in self.vectors["recordCases"]:
            inputs = case["input"]
            records[case["id"]] = self.policy.create(
                primary=_input_value(inputs, "primary"),
                primary_source_field_id=inputs["primarySourceFieldId"],
                declared_english=_input_value(inputs, "english"),
                english_source_field_id=inputs.get("englishSourceFieldId"),
            )
        for group in self.vectors["canonicalEquivalenceGroups"]:
            with self.subTest(equivalent=group["id"]):
                identities = {
                    records[case_id].full_sha256
                    for case_id in group["caseIds"]
                }
                self.assertEqual(len(identities), 1)
        for group in self.vectors["canonicalNonEquivalenceGroups"]:
            with self.subTest(non_equivalent=group["id"]):
                identities = {
                    records[case_id].full_sha256
                    for case_id in group["caseIds"]
                }
                self.assertEqual(len(identities), len(group["caseIds"]))
        expected_by_id = {
            case["id"]: case["expected"]["fullSha256"]
            for case in self.vectors["recordCases"]
        }
        for case_id in self.vectors["crossRuntimeCaseIds"]:
            with self.subTest(cross_runtime_digest=case_id):
                self.assertEqual(
                    records[case_id].full_sha256.hex(),
                    expected_by_id[case_id],
                )


class SourcedTextApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = sourced_text.DEFAULT_SOURCED_TEXT_POLICY

    def test_default_profile_and_policy_are_reusable_immutable_instances(self) -> None:
        self.assertIs(
            sourced_text.DEFAULT_SOURCED_TEXT_POLICY.profile,
            sourced_text.DEFAULT_UNICODE_SCRIPT_PROFILE,
        )
        with self.assertRaises(FrozenInstanceError):
            self.policy.policy_sha256 = b"\0" * 32

    def test_policy_rejects_a_non_frozen_profile_identity(self) -> None:
        with self.assertRaises(sourced_text.UnicodeScriptProfileError):
            replace(
                sourced_text.DEFAULT_UNICODE_SCRIPT_PROFILE,
                profile_sha256=b"\x44" * 32,
            )

    def test_public_policy_construction_cannot_mint_records(self) -> None:
        with self.assertRaises(sourced_text.UnicodeScriptProfileError):
            sourced_text.SourcedTextPolicy(
                sourced_text.DEFAULT_UNICODE_SCRIPT_PROFILE
            )

    def test_copied_or_deserialized_policy_cannot_mint_records(self) -> None:
        copies = (
            copy.copy(self.policy),
            copy.deepcopy(self.policy),
            pickle.loads(pickle.dumps(self.policy)),
        )
        for copied_policy in copies:
            self.assertIsNot(copied_policy, self.policy)
            with self.assertRaises(sourced_text.UnicodeScriptProfileError):
                copied_policy.create(
                    primary="A",
                    primary_source_field_id=1,
                )

    def test_selected_text_utf8_ceiling_accepts_4096_and_rejects_4097(self) -> None:
        self.assertEqual(sourced_text.MAX_SOURCED_TEXT_UTF8_BYTES, 4096)
        ascii_boundary = self.policy.create(
            primary="A" * 4096,
            primary_source_field_id=801,
        )
        self.assertEqual(len(ascii_boundary.primary_text.encode("utf-8")), 4096)
        with self.assertRaises(sourced_text.SourcedTextError) as primary_error:
            self.policy.create(
                primary="A" * 4097,
                primary_source_field_id=801,
            )
        self.assertIs(
            primary_error.exception.code,
            sourced_text.SourcedTextErrorCode.PRIMARY_TOO_LONG,
        )

        multibyte_boundary = self.policy.create(
            primary="é" * 2048,
            primary_source_field_id=802,
        )
        self.assertEqual(
            len(multibyte_boundary.primary_text.encode("utf-8")),
            4096,
        )
        with self.assertRaises(sourced_text.SourcedTextError) as multibyte_error:
            self.policy.create(
                primary="é" * 2049,
                primary_source_field_id=802,
            )
        self.assertIs(
            multibyte_error.exception.code,
            sourced_text.SourcedTextErrorCode.PRIMARY_TOO_LONG,
        )

    def test_english_utf8_ceiling_and_end_trim_use_selected_bytes(self) -> None:
        ascii_boundary = self.policy.create(
            primary="東京",
            primary_source_field_id=811,
            declared_english="A" * 4096,
            english_source_field_id=812,
        )
        self.assertEqual(len(ascii_boundary.english_text.encode("utf-8")), 4096)
        with self.assertRaises(sourced_text.SourcedTextError) as ascii_error:
            self.policy.create(
                primary="東京",
                primary_source_field_id=811,
                declared_english="A" * 4097,
                english_source_field_id=812,
            )
        self.assertIs(
            ascii_error.exception.code,
            sourced_text.SourcedTextErrorCode.ENGLISH_TOO_LONG,
        )

        multibyte_boundary = self.policy.create(
            primary="東京",
            primary_source_field_id=813,
            declared_english="é" * 2048,
            english_source_field_id=814,
        )
        self.assertEqual(
            len(multibyte_boundary.english_text.encode("utf-8")),
            4096,
        )
        with self.assertRaises(sourced_text.SourcedTextError) as multibyte_error:
            self.policy.create(
                primary="東京",
                primary_source_field_id=813,
                declared_english="é" * 2049,
                english_source_field_id=814,
            )
        self.assertIs(
            multibyte_error.exception.code,
            sourced_text.SourcedTextErrorCode.ENGLISH_TOO_LONG,
        )

        trimmed = self.policy.create(
            primary="東京",
            primary_source_field_id=815,
            declared_english=("A" * 4093) + "\u3000",
            english_source_field_id=816,
        )
        self.assertEqual(len(trimmed.english_text.encode("utf-8")), 4093)
        with self.assertRaises(sourced_text.SourcedTextError) as raw_error:
            self.policy.create(
                primary="東京",
                primary_source_field_id=815,
                declared_english=("A" * 4096) + " ",
                english_source_field_id=816,
            )
        self.assertIs(
            raw_error.exception.code,
            sourced_text.SourcedTextErrorCode.ENGLISH_TOO_LONG,
        )

    def test_overlong_primary_is_rejected_before_script_scan(self) -> None:
        with mock.patch.object(
            sourced_text.SourcedTextPolicy,
            "classify_scalar",
            side_effect=AssertionError("script scan must not run"),
        ), self.assertRaises(sourced_text.SourcedTextError) as caught:
            self.policy.create(
                primary="A" * 4097,
                primary_source_field_id=821,
            )
        self.assertIs(
            caught.exception.code,
            sourced_text.SourcedTextErrorCode.PRIMARY_TOO_LONG,
        )

    def test_overlong_english_precedes_every_role_and_script_early_return(self) -> None:
        cases = (
            (
                "primary not eligible",
                "Latin primary",
                831,
                "A" * 4097,
                832,
            ),
            (
                "English field is primary",
                "القاهرة",
                833,
                "A" * 4097,
                833,
            ),
            (
                "English field ID missing",
                "القاهرة",
                834,
                "A" * 4097,
                None,
            ),
            (
                "multibyte primary not eligible",
                "Latin primary",
                835,
                "é" * 2049,
                836,
            ),
        )
        for label, primary, primary_id, english, english_id in cases:
            with self.subTest(case=label):
                with self.assertRaises(
                    sourced_text.SourcedTextError
                ) as caught:
                    self.policy.create(
                        primary=primary,
                        primary_source_field_id=primary_id,
                        declared_english=english,
                        english_source_field_id=english_id,
                    )
                self.assertIs(
                    caught.exception.code,
                    sourced_text.SourcedTextErrorCode.ENGLISH_TOO_LONG,
                )

    def test_overlong_english_is_rejected_before_primary_script_scan(self) -> None:
        with mock.patch.object(
            sourced_text.SourcedTextPolicy,
            "classify_scalar",
            side_effect=AssertionError("primary script scan must not run"),
        ), self.assertRaises(sourced_text.SourcedTextError) as caught:
            self.policy.create(
                primary="القاهرة",
                primary_source_field_id=841,
                declared_english="A" * 4097,
                english_source_field_id=841,
            )
        self.assertIs(
            caught.exception.code,
            sourced_text.SourcedTextErrorCode.ENGLISH_TOO_LONG,
        )

    def test_policy_and_vectors_bind_the_exact_text_ceiling(self) -> None:
        descriptor = json.loads(self.policy.descriptor_bytes.decode("utf-8"))
        self.assertEqual(descriptor["maxSourcedTextUtf8Bytes"], 4096)
        vectors = json.loads(CONFORMANCE_PATH.read_text(encoding="utf-8"))
        self.assertEqual(
            vectors["identities"]["maxSourcedTextUtf8Bytes"],
            4096,
        )

    def test_record_is_immutable_and_contains_no_render_or_glyph_state(self) -> None:
        record = self.policy.create(
            primary="القاهرة",
            primary_source_field_id=101,
            declared_english="Cairo",
            english_source_field_id=102,
        )
        with self.assertRaises(FrozenInstanceError):
            record.primary_text = "changed"
        field_names = {item.name for item in fields(record)}
        forbidden_fragments = (
            "candidate",
            "character",
            "display_string",
            "glyph",
            "path",
            "placement",
            "subtitle_candidate",
        )
        for name in field_names:
            self.assertFalse(any(token in name for token in forbidden_fragments))
        self.assertFalse(hasattr(record, "__dict__"))
        self.assertFalse(hasattr(record, "subtitle_candidate"))
        self.assertEqual(
            [value for value in (record.primary_text, record.english_text) if value],
            ["القاهرة", "Cairo"],
        )

    def test_direct_constructor_rejects_forged_scripts_and_equal_field_roles(self) -> None:
        with self.assertRaises(sourced_text.SourcedTextError):
            sourced_text.SourcedMapText(
                primary_text="A",
                primary_source_field_id=701,
                english_text="東京",
                english_source_field_id=701,
                layout_mode=sourced_text.LayoutMode.PRIMARY_WITH_ENGLISH,
                english_gap_reason=sourced_text.EnglishGapReason.NONE,
                profile_sha256=self.policy.profile.profile_sha256,
                policy_sha256=self.policy.policy_sha256,
                primary_script_signals=sourced_text.ScriptSignals(
                    has_strong_latin=False,
                    has_strong_non_latin=True,
                    has_unknown=False,
                ),
                english_script_signals=sourced_text.ScriptSignals(
                    has_strong_latin=True,
                    has_strong_non_latin=False,
                    has_unknown=False,
                ),
            )

    def test_factory_executes_the_shared_script_decision_once(self) -> None:
        original = sourced_text.SourcedTextPolicy.classify_scalar

        def classify_once(policy: object, scalar: int) -> object:
            return original(policy, scalar)

        with mock.patch.object(
            sourced_text.SourcedTextPolicy,
            "classify_scalar",
            autospec=True,
            side_effect=classify_once,
        ) as classify:
            self.policy.create(
                primary="القاهرة",
                primary_source_field_id=711,
                declared_english="Cairo",
                english_source_field_id=712,
            )
        self.assertEqual(classify.call_count, len("القاهرة") + len("Cairo"))

    def test_replace_rejects_every_forged_derived_field(self) -> None:
        record = self.policy.create(
            primary="القاهرة",
            primary_source_field_id=101,
            declared_english="Cairo",
            english_source_field_id=102,
        )
        mutations = {
            "profile identity": {"profile_sha256": b"\x11" * 32},
            "policy identity": {"policy_sha256": b"\x22" * 32},
            "primary signals": {
                "primary_script_signals": sourced_text.ScriptSignals(
                    has_strong_latin=True,
                    has_strong_non_latin=False,
                    has_unknown=False,
                )
            },
            "English signals": {
                "english_script_signals": sourced_text.ScriptSignals(
                    has_strong_latin=False,
                    has_strong_non_latin=True,
                    has_unknown=False,
                )
            },
            "layout": {"layout_mode": sourced_text.LayoutMode.SINGLE},
            "gap": {
                "english_gap_reason": sourced_text.EnglishGapReason.MISSING
            },
            "primary eligibility": {"primary_text": "A"},
            "English script": {"english_text": "東京"},
            "exact equality": {"english_text": "القاهرة"},
            "field relationship": {"english_source_field_id": 101},
        }
        for label, mutation in mutations.items():
            with self.subTest(field=label), self.assertRaises(
                sourced_text.SourcedTextError
            ):
                replace(record, **mutation)

        for field_name, value in (
            ("canonical_bytes", b"forged"),
            ("canonical_sha256", b"\x33" * 32),
            ("full_sha256", b"\x44" * 32),
            ("hot_id", 1),
        ):
            with self.subTest(init_false_field=field_name), self.assertRaises(
                ValueError
            ):
                replace(record, **{field_name: value})

        gap_record = self.policy.create(
            primary="القاهرة",
            primary_source_field_id=201,
            declared_english=123,
            english_source_field_id=202,
        )
        with self.assertRaises(sourced_text.SourcedTextError):
            replace(
                gap_record,
                english_gap_reason=sourced_text.EnglishGapReason.BLANK,
            )

    def test_record_requires_exact_immutable_identity_bytes(self) -> None:
        record = self.policy.create(
            primary="القاهرة",
            primary_source_field_id=101,
            declared_english="Cairo",
            english_source_field_id=102,
        )
        for field_name in ("profile_sha256", "policy_sha256"):
            identity = getattr(record, field_name)
            for label, mutable_or_view in (
                ("bytearray", bytearray(identity)),
                ("memoryview", memoryview(identity)),
            ):
                with self.subTest(field=field_name, value_type=label), self.assertRaises(
                    sourced_text.SourcedTextError
                ):
                    replace(record, **{field_name: mutable_or_view})

    def test_record_requires_exact_types_for_every_derived_field(self) -> None:
        record = self.policy.create(
            primary="القاهرة",
            primary_source_field_id=101,
            declared_english="Cairo",
            english_source_field_id=102,
        )

        class DerivedSignals(sourced_text.ScriptSignals):
            pass

        mutations = {
            "layout integer": {"layout_mode": record.layout_mode.value},
            "gap integer": {"english_gap_reason": record.english_gap_reason.value},
            "primary signals subclass": {
                "primary_script_signals": DerivedSignals(
                    has_strong_latin=record.primary_script_signals.has_strong_latin,
                    has_strong_non_latin=(
                        record.primary_script_signals.has_strong_non_latin
                    ),
                    has_unknown=record.primary_script_signals.has_unknown,
                )
            },
            "English signals subclass": {
                "english_script_signals": DerivedSignals(
                    has_strong_latin=record.english_script_signals.has_strong_latin,
                    has_strong_non_latin=(
                        record.english_script_signals.has_strong_non_latin
                    ),
                    has_unknown=record.english_script_signals.has_unknown,
                )
            },
        }
        for label, mutation in mutations.items():
            with self.subTest(field=label), self.assertRaises(
                sourced_text.SourcedTextError
            ):
                replace(record, **mutation)

    def test_direct_gap_construction_requires_and_discards_detached_evidence(self) -> None:
        record = self.policy.create(
            primary="القاهرة",
            primary_source_field_id=101,
            declared_english=123,
            english_source_field_id=102,
        )
        reconstructed = sourced_text.SourcedMapText(
            primary_text=record.primary_text,
            primary_source_field_id=record.primary_source_field_id,
            english_text=record.english_text,
            english_source_field_id=record.english_source_field_id,
            layout_mode=record.layout_mode,
            english_gap_reason=record.english_gap_reason,
            profile_sha256=record.profile_sha256,
            policy_sha256=record.policy_sha256,
            primary_script_signals=record.primary_script_signals,
            english_script_signals=record.english_script_signals,
            declared_english_evidence=123,
        )
        self.assertEqual(reconstructed, record)
        self.assertNotIn(
            "declared_english_evidence",
            {item.name for item in fields(reconstructed)},
        )

    def test_record_identity_is_domain_separated_and_hot_id_is_big_endian(self) -> None:
        record = self.policy.create(
            primary="杉並区",
            primary_source_field_id=201,
            declared_english="Suginami",
            english_source_field_id=202,
        )
        self.assertEqual(
            record.full_sha256,
            hashlib.sha256(
                sourced_text.SOURCED_TEXT_IDENTITY_DOMAIN + record.canonical_bytes
            ).digest(),
        )
        self.assertEqual(record.hot_id, int.from_bytes(record.full_sha256[:8], "big"))

    def test_only_one_versioned_bilingual_presentation_token_exists(self) -> None:
        self.assertEqual(
            sourced_text.BILINGUAL_PRESENTATION_TOKEN,
            "flightalert.sourced-map-text.primary-with-english.v1",
        )
        self.assertNotIn(
            "presentation",
            {item.name for item in fields(sourced_text.SourcedMapText)},
        )


if __name__ == "__main__":
    unittest.main()
