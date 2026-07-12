from __future__ import annotations

import hashlib
import inspect
import io
import json
import os
import ast
import struct
import sys
import tempfile
import unittest
import unicodedata
from dataclasses import replace
from pathlib import Path
from unittest import mock

import tools.experiment8.osm_planet_selection as selection_module


try:
    from tools.experiment8.osm_planet_selection import (
        FIXTURE_NAME_ENVELOPE_PROFILE,
        GLOBAL_POLICY_SHA256,
        LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT,
        LIVE_NAME_ENVELOPE_OPL_BYTES,
        LIVE_NAME_ENVELOPE_OPL_SHA256,
        LIVE_NAME_ENVELOPE_PBF_BYTES,
        LIVE_NAME_ENVELOPE_PBF_SHA256,
        LIVE_NAME_ENVELOPE_PROFILE,
        LIVE_NAME_ENVELOPE_RELATIONS,
        LIVE_NAME_ENVELOPE_WAYS,
        SelectionBindings,
        SelectionError,
        SelectionLimits,
        canonical_policy_bytes,
        scan_planet_roots,
    )
except ModuleNotFoundError:
    FIXTURE_NAME_ENVELOPE_PROFILE = None
    GLOBAL_POLICY_SHA256 = None
    LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT = None
    LIVE_NAME_ENVELOPE_OPL_BYTES = None
    LIVE_NAME_ENVELOPE_OPL_SHA256 = None
    LIVE_NAME_ENVELOPE_PBF_BYTES = None
    LIVE_NAME_ENVELOPE_PBF_SHA256 = None
    LIVE_NAME_ENVELOPE_PROFILE = None
    LIVE_NAME_ENVELOPE_RELATIONS = None
    LIVE_NAME_ENVELOPE_WAYS = None
    SelectionBindings = None
    SelectionError = None
    SelectionLimits = None
    canonical_policy_bytes = None
    scan_planet_roots = None


_TIMESTAMP = "2026-06-28T23:59:59Z"
_ROOT_DOMAIN = b"FAE8OSMROOT1\0"
_BUCKET_DOMAIN = b"FAE8OSMBUCKET1\0"
_REJECTION_DOMAIN = b"FAE8OSMREJ1\0"
_AUTHORITATIVE_PLANET_SHA256 = (
    "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
)


def _opl(*lines: str) -> bytes:
    return ("\n".join(lines) + ("\n" if lines else "")).encode("utf-8")


def _way(
    object_id: int,
    *,
    version: int = 1,
    tags: str = "name=River,waterway=river",
    refs: str = "n30,n10,n20",
) -> str:
    return f"w{object_id} v{version} t{_TIMESTAMP} T{tags} N{refs}"


def _relation(
    object_id: int,
    *,
    version: int = 1,
    tags: str = "name=River,type=waterway",
    members: str = "w9@main,n7@source,r8@tributary",
) -> str:
    return f"r{object_id} v{version} t{_TIMESTAMP} T{tags} M{members}"


class _ShortReadStream:
    def __init__(self, raw: bytes, widths: tuple[int, ...] = (1, 2, 5, 3)) -> None:
        self._raw = raw
        self._offset = 0
        self._widths = widths
        self._reads = 0

    def read(self, size: int) -> bytes:
        if self._offset == len(self._raw):
            return b""
        width = min(size, self._widths[self._reads % len(self._widths)])
        self._reads += 1
        result = self._raw[self._offset : self._offset + width]
        self._offset += len(result)
        return result


class _ExplodingReadStream:
    def __init__(self, raw: bytes) -> None:
        self._raw = raw
        self._read = False

    def read(self, size: int) -> bytes:
        if not self._read:
            self._read = True
            return self._raw[: min(size, 7)]
        raise RuntimeError("injected reader failure")


def _sha(label: str) -> str:
    return hashlib.sha256(label.encode("ascii")).hexdigest()


@unittest.skipIf(scan_planet_roots is None, "production module is not implemented yet")
class PlanetSelectionTests(unittest.TestCase):
    def _bindings(self, raw: bytes):
        assert SelectionBindings is not None
        assert GLOBAL_POLICY_SHA256 is not None
        return SelectionBindings(
            planet_source_sha256=_sha("source"),
            candidate_pbf_bytes=len(raw),
            candidate_pbf_sha256=_sha("candidate-pbf"),
            converted_stream_bytes=len(raw),
            converted_stream_sha256=hashlib.sha256(raw).hexdigest(),
            converted_stream_format="opl",
            runtime_sha256=_sha("runtime"),
            policy_sha256=GLOBAL_POLICY_SHA256,
            code_sha256=_sha("code"),
        )

    def _scan(
        self,
        raw: bytes,
        stage: Path,
        *,
        stream=None,
        workers: int = 1,
        limits=None,
        bindings=None,
        profile=None,
    ):
        assert scan_planet_roots is not None
        kwargs = {
            "workers": workers,
            "profile": (
                FIXTURE_NAME_ENVELOPE_PROFILE
                if profile is None
                else profile
            ),
        }
        if limits is not None:
            kwargs["limits"] = limits
        return scan_planet_roots(
            stream if stream is not None else io.BytesIO(raw),
            stage,
            bindings if bindings is not None else self._bindings(raw),
            **kwargs,
        )

    def _summary(self, stage: Path) -> dict[str, object]:
        raw = (stage / "selection-summary.json").read_bytes()
        self.assertTrue(raw.endswith(b"\n"))
        self.assertNotIn(b"\r", raw)
        document = json.loads(raw)
        self.assertEqual(
            raw,
            (json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
        )
        return document

    def test_policy_bytes_freeze_reason_precedence_and_conservative_lifecycle(self) -> None:
        assert canonical_policy_bytes is not None
        assert GLOBAL_POLICY_SHA256 is not None
        raw = canonical_policy_bytes()
        document = json.loads(raw)

        self.assertEqual(hashlib.sha256(raw).hexdigest(), GLOBAL_POLICY_SHA256)
        self.assertEqual(
            document["wayRejectionPrecedence"],
            [
                [1, "WAY_UNSUPPORTED_WATERWAY_VALUE"],
                [2, "WAY_INSUFFICIENT_GEOMETRY"],
                [3, "WAY_CLOSED"],
                [4, "WAY_AREA"],
                [5, "WAY_LIFECYCLE"],
                [6, "WAY_NO_SUPPORTED_NAME_KEY"],
                [7, "WAY_SUPPORTED_NAMES_ALL_BLANK"],
            ],
        )
        self.assertEqual(
            document["relationRejectionPrecedence"],
            [
                [8, "RELATION_UNSUPPORTED_TYPE"],
                [9, "RELATION_NO_SUPPORTED_NAME_KEY"],
                [10, "RELATION_SUPPORTED_NAMES_ALL_BLANK"],
            ],
        )
        self.assertEqual(
            document["lifecycleStates"],
            [
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
            ],
        )
        self.assertEqual(document["lifecycleKeyRules"], ["state", "state:waterway", "waterway:state"])
        self.assertEqual(document["falseTagValues"], ["", "0", "false", "no"])

    def test_streams_selected_objects_to_canonical_buckets_tuples_and_root_ids(self) -> None:
        decomposed = "Cafe\u0301 River"
        self.assertNotEqual(unicodedata.normalize("NFC", decomposed), decomposed)
        raw = _opl(
            _way(
                11,
                version=7,
                tags=f"z=last,name={decomposed.replace(' ', '%20%')},waterway=river,a=first",
                refs="n30,n10,n20",
            ),
            _relation(
                12,
                version=3,
                tags="official_name=Rio%20%%26%%20%Canal,type=waterway",
                members="w90@main,n70@source,r80@side%2c%role",
            ),
        )
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            result = self._scan(raw, stage, stream=_ShortReadStream(raw))

            self.assertEqual(result.selected_way_count, 1)
            self.assertEqual(result.selected_relation_count, 1)
            self.assertEqual((stage / "root-ids.txt").read_bytes(), b"w11\nr12\n")
            tuples = (stage / "selected-tuples.bin").read_bytes()
            self.assertEqual(len(tuples), 2 * 45)
            self.assertEqual(tuples[0], 1)
            self.assertEqual(struct.unpack_from("<Q", tuples, 1)[0], 11)
            self.assertEqual(struct.unpack_from("<I", tuples, 9)[0], 7)
            self.assertEqual(tuples[45], 2)
            self.assertEqual(struct.unpack_from("<Q", tuples, 46)[0], 12)

            bucket = hashlib.sha256(_BUCKET_DOMAIN + b"\x01" + struct.pack("<Q", 11)).digest()[0]
            bucket_raw = (stage / f"roots-{bucket:03d}.bin").read_bytes()
            framed_length = struct.unpack_from("<I", bucket_raw)[0]
            self.assertEqual(framed_length, len(bucket_raw) - 4)
            payload = bucket_raw[4:-32]
            digest = bucket_raw[-32:]
            self.assertEqual(digest, hashlib.sha256(_ROOT_DOMAIN + payload).digest())
            self.assertEqual(digest, tuples[13:45])
            self.assertEqual(payload[0], 1)
            self.assertEqual(struct.unpack_from("<Q", payload, 1)[0], 11)
            self.assertEqual(struct.unpack_from("<I", payload, 9)[0], 7)
            tag_count = struct.unpack_from("<I", payload, 21)[0]
            self.assertEqual(tag_count, 4)
            offset = 25
            tags: list[tuple[str, str]] = []
            for _ in range(tag_count):
                key_length = struct.unpack_from("<I", payload, offset)[0]
                offset += 4
                key = payload[offset : offset + key_length].decode("utf-8")
                offset += key_length
                value_length = struct.unpack_from("<I", payload, offset)[0]
                offset += 4
                value = payload[offset : offset + value_length].decode("utf-8")
                offset += value_length
                tags.append((key, value))
            self.assertEqual(tags, [("a", "first"), ("name", decomposed), ("waterway", "river"), ("z", "last")])
            ref_count = struct.unpack_from("<I", payload, offset)[0]
            offset += 4
            self.assertEqual(ref_count, 3)
            self.assertEqual(struct.unpack_from("<QQQ", payload, offset), (30, 10, 20))

            relation_bucket = hashlib.sha256(_BUCKET_DOMAIN + b"\x02" + struct.pack("<Q", 12)).digest()[0]
            relation_raw = (stage / f"roots-{relation_bucket:03d}.bin").read_bytes()
            relation_payload = relation_raw[4:-32]
            offset = 25
            for _ in range(struct.unpack_from("<I", relation_payload, 21)[0]):
                key_length = struct.unpack_from("<I", relation_payload, offset)[0]
                offset += 4 + key_length
                value_length = struct.unpack_from("<I", relation_payload, offset)[0]
                offset += 4 + value_length
            self.assertEqual(struct.unpack_from("<I", relation_payload, offset)[0], 3)
            offset += 4
            decoded_members: list[tuple[int, int, int, str]] = []
            for _ in range(3):
                ordinal = struct.unpack_from("<I", relation_payload, offset)[0]
                kind = relation_payload[offset + 4]
                ref = struct.unpack_from("<Q", relation_payload, offset + 5)[0]
                role_length = struct.unpack_from("<I", relation_payload, offset + 13)[0]
                role = relation_payload[offset + 17 : offset + 17 + role_length].decode("utf-8")
                offset += 17 + role_length
                decoded_members.append((ordinal, kind, ref, role))
            self.assertEqual(decoded_members, [(0, 1, 90, "main"), (1, 0, 70, "source"), (2, 2, 80, "side,role")])

            summary = self._summary(stage)
            self.assertEqual(summary["input"]["bytes"], len(raw))
            self.assertEqual(summary["input"]["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(summary["selected"]["nonNfcFieldCount"], 1)
            self.assertEqual(summary["selected"]["nonNfcRecordCount"], 1)
            self.assertEqual(summary["artifacts"]["buckets"]["bucketCount"], 256)
            self.assertEqual(len(summary["artifacts"]["buckets"]["entries"]), 256)
            self.assertEqual(sorted(path.name for path in stage.iterdir())[:3], ["root-ids.txt", "roots-000.bin", "roots-001.bin"])
            self.assertEqual(len(list(stage.iterdir())), 259)

    def test_rejections_use_frozen_reason_precedence_and_ordered_hash_chain(self) -> None:
        raw = _opl(
            _way(1, tags="name=x,waterway=ditch"),
            _way(2, refs="n1"),
            _way(3, refs="n1,n2,n1"),
            _way(4, tags="area=yes,name=x,waterway=river"),
            _way(5, tags="disused=yes,name=x,waterway=river"),
            _way(6, tags="name:left=x,waterway=river"),
            _way(7, tags="name=%20%%09%,waterway=river"),
            _relation(1, tags="name=x,type=route"),
            _relation(2, tags="name:source=x,type=waterway"),
            _relation(3, tags="int_name=%20%,type=waterway"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            result = self._scan(raw, stage)
            summary = self._summary(stage)

            self.assertEqual((stage / "root-ids.txt").read_bytes(), b"")
            self.assertEqual((stage / "selected-tuples.bin").read_bytes(), b"")
            self.assertEqual(summary["rejections"]["countsByReasonId"], {str(value): 1 for value in range(1, 11)})
            self.assertEqual(summary["rejections"]["records"], 10)
            self.assertEqual(result.rejection_ledger_sha256, summary["rejections"]["ledgerSha256"])
            self.assertNotEqual(result.rejection_ledger_sha256, hashlib.sha256(_REJECTION_DOMAIN).hexdigest())

    def test_every_global_lifecycle_key_variant_is_reason_five(self) -> None:
        states = (
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
        lines: list[str] = []
        object_id = 1
        for state in states:
            for key, value in ((state, "YES"), (f"{state}:waterway", "no"), (f"waterway:{state}", "0")):
                lines.append(_way(object_id, tags=f"{key}={value},name=x,waterway=river"))
                object_id += 1
        for state in states:
            lines.append(_way(object_id, tags=f"{state}=%20%no%20%,name=x,waterway=river"))
            object_id += 1
        raw = _opl(*lines)
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            self._scan(raw, stage)
            summary = self._summary(stage)
            self.assertEqual(summary["rejections"]["countsByReasonId"], {"5": 30})
            self.assertEqual(summary["selected"]["ways"], 10)

    def test_supported_name_language_pattern_rejects_semantic_suffixes_and_blank_values(self) -> None:
        raw = _opl(
            _way(1, tags="name:en-US=River,waterway=river"),
            _way(2, tags="name:zh-Hant=河,waterway=stream"),
            _way(3, tags="name:old=former,waterway=canal"),
            _way(4, tags="name:en_US=bad,waterway=wadi"),
            _way(5, tags="official_name=%20%,waterway=tidal_channel"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            self._scan(raw, stage)
            self.assertEqual((stage / "root-ids.txt").read_bytes(), b"w1\nw2\n")
            self.assertEqual(
                self._summary(stage)["rejections"]["countsByReasonId"],
                {"6": 2, "7": 1},
            )

    def test_worker_count_changes_no_output_bytes_or_inventory(self) -> None:
        raw = _opl(*(_way(value, version=(value % 3) + 1) for value in range(1, 75)), _relation(100))
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            first_stage = Path(first)
            second_stage = Path(second)
            self._scan(raw, first_stage, workers=1)
            self._scan(raw, second_stage, workers=11)
            first_files = {path.name: path.read_bytes() for path in first_stage.iterdir()}
            second_files = {path.name: path.read_bytes() for path in second_stage.iterdir()}
            self.assertEqual(first_files, second_files)

    def test_type_then_id_order_and_history_duplicates_are_fatal(self) -> None:
        cases = {
            "way ID decreases": _opl(_way(2), _way(1)),
            "way history duplicate": _opl(_way(1, version=1), _way(1, version=2)),
            "way after relation": _opl(_relation(1), _way(2)),
            "relation ID decreases": _opl(_relation(2), _relation(1)),
            "relation history duplicate": _opl(_relation(1, version=1), _relation(1, version=2)),
        }
        for label, raw in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                stage = Path(temporary)
                with self.assertRaisesRegex(SelectionError, "Type_then_ID|duplicate|history"):
                    self._scan(raw, stage)
                self.assertEqual(list(stage.iterdir()), [])

    def test_malformed_opl_utf8_escapes_ids_versions_timestamps_and_tags_are_fatal(self) -> None:
        cases = {
            "missing final LF": _way(1).encode("utf-8"),
            "empty record": b"\n",
            "CR": _opl(_way(1)).replace(b"\n", b"\r\n"),
            "invalid UTF-8": _opl(_way(1)).replace(b"River", b"\xff"),
            "truncated escape": _opl(_way(1, tags="name=%2,waterway=river")),
            "nonhex escape": _opl(_way(1, tags="name=%zz%,waterway=river")),
            "invalid codepoint": _opl(_way(1, tags="name=%110000%,waterway=river")),
            "duplicate decoded tag": _opl(_way(1, tags="name=x,na%6d%e=y,waterway=river")),
            "empty tag key": _opl(_way(1, tags="=x,name=y,waterway=river")),
            "zero ID": _opl(_way(1)).replace(b"w1 ", b"w0 ", 1),
            "leading-zero ID": _opl(_way(1)).replace(b"w1 ", b"w01 ", 1),
            "signed ID": _opl(_way(1)).replace(b"w1 ", b"w+1 ", 1),
            "too-large ID": _opl(_way(1)).replace(b"w1 ", b"w9223372036854775808 ", 1),
            "zero version": _opl(_way(1)).replace(b"v1 ", b"v0 ", 1),
            "leading-zero version": _opl(_way(1)).replace(b"v1 ", b"v01 ", 1),
            "too-large version": _opl(_way(1)).replace(b"v1 ", b"v4294967296 ", 1),
            "invalid timestamp": _opl(_way(1)).replace(_TIMESTAMP.encode(), b"2026-02-30T00:00:00Z"),
            "duplicate attribute": _opl(_way(1)).replace(b" v1 ", b" v1 v2 "),
            "node object": _opl("n1 v1 t2026-06-28T23:59:59Z Tname=x x0 y0"),
            "invalid ref": _opl(_way(1, refs="n1,n0")),
            "invalid member": _opl(_relation(1, members="x1@role")),
        }
        for label, raw in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                stage = Path(temporary)
                with self.assertRaises(SelectionError):
                    self._scan(raw, stage)
                self.assertEqual(list(stage.iterdir()), [])

    def test_official_opl_escape_grammar_is_one_to_six_hex_digits_without_aliases(self) -> None:
        assert SelectionLimits is not None
        limits = SelectionLimits()
        decode = selection_module._decode_opl_string

        self.assertEqual(decode("%A%", "fixture", limits), "\n")
        self.assertEqual(decode("%25%", "fixture", limits), "%")
        self.assertEqual(decode("%1F600%", "fixture", limits), "\U0001f600")
        self.assertEqual(decode("%000041%", "fixture", limits), "A")

        invalid = (
            "%0%",
            "%00%",
            "%%",
            "%0000041%",
            "%G%",
            "%D800%",
            "%110000%",
            "direct@ambiguous",
        )
        for raw in invalid:
            with self.subTest(raw=raw), self.assertRaises(SelectionError):
                decode(raw, "fixture", limits)

    def test_decimal_fields_are_lexically_bounded_before_python_int_conversion(self) -> None:
        huge = "9" * 5_000
        with self.assertRaises(SelectionError):
            selection_module._positive_integer(huge, (1 << 63) - 1, "fixture ID")
        with self.assertRaises(SelectionError):
            selection_module._nonnegative_integer(huge, (1 << 63) - 1, "fixture version")

        cases = (
            _opl(_way(1)).replace(b"w1 ", f"w{huge} ".encode("ascii"), 1),
            _opl(_way(1)).replace(b"v1 ", f"v{huge} ".encode("ascii"), 1),
            _opl(_way(1, refs=f"n{huge},n2")),
        )
        for raw in cases:
            with self.subTest(field=hashlib.sha256(raw).hexdigest()), tempfile.TemporaryDirectory() as temporary:
                stage = Path(temporary)
                with self.assertRaises(SelectionError):
                    self._scan(raw, stage)
                self.assertEqual(list(stage.iterdir()), [])

    def test_separator_heavy_records_use_bounded_token_scans(self) -> None:
        source = Path(selection_module.__file__).read_text(encoding="utf-8")
        tree = ast.parse(source)
        bounded_functions = {
            "_parse_opl_line",
            "_parse_tags",
            "_parse_refs",
            "_parse_members",
        }
        split_calls: list[tuple[str, int]] = []
        for node in tree.body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in bounded_functions:
                for child in ast.walk(node):
                    if (
                        isinstance(child, ast.Call)
                        and isinstance(child.func, ast.Attribute)
                        and child.func.attr == "split"
                    ):
                        split_calls.append((node.name, child.lineno))
        self.assertEqual(split_calls, [])

        assert SelectionLimits is not None
        tags = ",".join(f"k{index}=v" for index in range(8_000))
        raw = _opl(_way(1, tags=tags))
        limits = replace(
            SelectionLimits(),
            max_line_bytes=len(raw) - 1,
            max_tags_per_object=2,
        )
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with self.assertRaisesRegex(SelectionError, "per-object tag ceiling"):
                self._scan(raw, stage, limits=limits)
            self.assertEqual(list(stage.iterdir()), [])

    def test_memory_error_is_typed_and_cleans_exact_owned_temporaries(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch.object(selection_module, "_object_payload", side_effect=MemoryError("injected allocation")):
                with self.assertRaisesRegex(SelectionError, "memory|allocation"):
                    self._scan(raw, stage)
            self.assertEqual(list(stage.iterdir()), [])

    def test_writer_wrapper_allocation_failure_cleans_atomic_temporary(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch.object(
                selection_module,
                "_TrackedWriter",
                side_effect=MemoryError("injected writer wrapper allocation"),
            ):
                with self.assertRaisesRegex(MemoryError, "writer wrapper allocation"):
                    selection_module._Artifacts(stage)
            self.assertEqual(list(stage.iterdir()), [])

    @unittest.skipUnless(os.name == "nt", "native atomic creation is Windows-specific")
    def test_native_atomic_creation_cleans_buffer_wrapper_failure(self) -> None:
        real_fdopen = selection_module.os.fdopen

        def fail_write_wrapper(fd, mode="r", *args, **kwargs):
            if mode == "wb":
                raise MemoryError("injected buffered writer allocation")
            return real_fdopen(fd, mode, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch.object(
                selection_module.os,
                "fdopen",
                side_effect=fail_write_wrapper,
            ):
                with self.assertRaisesRegex(
                    MemoryError,
                    "buffered writer allocation",
                ):
                    selection_module._Artifacts(stage)
            self.assertEqual(list(stage.iterdir()), [])

    @unittest.skipUnless(os.name == "nt", "native atomic creation is Windows-specific")
    def test_nt_create_return_conversion_failure_deletes_preallocated_handle(self) -> None:
        import ctypes

        real_win_dll = ctypes.WinDLL
        real_ntdll = real_win_dll("ntdll", use_last_error=True)

        class RaiseAfterCreate:
            argtypes = None
            restype = None

            def __call__(self, *args):
                real = real_ntdll.NtCreateFile
                real.argtypes = self.argtypes
                real.restype = self.restype
                status = real(*args)
                if status >= 0:
                    raise MemoryError("injected NtCreateFile return conversion")
                return status

        class FakeNtdll:
            NtCreateFile = RaiseAfterCreate()
            RtlNtStatusToDosError = real_ntdll.RtlNtStatusToDosError

        def fake_win_dll(name, *args, **kwargs):
            if str(name).lower() == "ntdll":
                return FakeNtdll()
            return real_win_dll(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "return-conversion.bin"
            with mock.patch.object(
                ctypes,
                "WinDLL",
                side_effect=fake_win_dll,
            ):
                with self.assertRaisesRegex(
                    MemoryError,
                    "NtCreateFile return conversion",
                ):
                    selection_module._create_atomic_binary_writer(path, "fixture")
            self.assertFalse(path.exists())

    @unittest.skipUnless(os.name == "nt", "native atomic creation is Windows-specific")
    def test_atomic_file_retry_retains_secondary_fd_after_disposition_failure(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "retained-atomic.bin"
            with mock.patch.object(
                selection_module.os,
                "fdopen",
                side_effect=MemoryError("injected buffer allocation"),
            ), mock.patch.object(
                selection_module,
                "_mark_windows_native_handle_for_delete",
                side_effect=MemoryError("injected disposition allocation"),
            ):
                with self.assertRaises(selection_module._UnreleasedAtomicFile) as caught:
                    selection_module._create_atomic_binary_writer(path, "fixture")

            retained = caught.exception
            self.assertGreater(retained.native_handle, 0)
            self.assertGreaterEqual(retained.extra_fd, 0)
            retained.retry_cleanup()
            self.assertEqual(retained.native_handle, 0)
            self.assertEqual(retained.extra_fd, -1)
            self.assertEqual(retained.extra_native_handle, 0)
            self.assertIsNone(retained.wrapped_handle)
            self.assertFalse(path.exists())

    def test_atomic_creation_does_not_use_high_level_open_create_window(self) -> None:
        real_open = Path.open
        attempted = False

        def create_then_fail(path: Path, *args, **kwargs):
            nonlocal attempted
            mode = args[0] if args else kwargs.get("mode", "r")
            if mode == "xb":
                attempted = True
                created = real_open(path, *args, **kwargs)
                created.close()
                raise MemoryError("injected high-level open wrapper allocation")
            return real_open(path, *args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch(
                "pathlib.Path.open",
                autospec=True,
                side_effect=create_then_fail,
            ):
                artifacts = selection_module._Artifacts(stage)
                artifacts.cleanup()
            self.assertFalse(attempted)
            self.assertEqual(list(stage.iterdir()), [])

    def test_result_allocation_failure_leaves_no_owned_committed_evidence(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch.object(
                selection_module,
                "SelectionResult",
                side_effect=MemoryError("injected result allocation"),
            ):
                with self.assertRaisesRegex(SelectionError, "memory|allocation"):
                    self._scan(raw, stage)
            self.assertEqual(list(stage.iterdir()), [])

    def test_result_is_prebuilt_before_terminal_linearization(self) -> None:
        raw = _opl(_way(1))
        events: list[str] = []
        real_result = selection_module.SelectionResult
        real_terminal = selection_module._validate_final_inventory

        def build_result(*args, **kwargs):
            events.append("result")
            return real_result(*args, **kwargs)

        def terminal(*args, **kwargs):
            events.append("terminal")
            return real_terminal(*args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch.object(
                selection_module,
                "SelectionResult",
                side_effect=build_result,
            ), mock.patch.object(
                selection_module,
                "_validate_final_inventory",
                side_effect=terminal,
            ):
                result = self._scan(raw, stage)

        self.assertIsInstance(result, real_result)
        self.assertEqual(events, ["result", "terminal"])

    def test_hard_ceilings_are_checked_incrementally_with_deterministic_messages(self) -> None:
        assert SelectionLimits is not None
        raw = _opl(_way(1), _way(2))
        base = SelectionLimits()
        cases = (
            (replace(base, max_input_bytes=len(raw) - 1), "input byte ceiling"),
            (replace(base, max_line_bytes=len(_way(1).encode("utf-8")) - 1), "line byte ceiling"),
            (replace(base, max_objects=1), "object ceiling"),
            (replace(base, max_total_tags=3), "tag ceiling"),
            (replace(base, max_total_references=5), "reference ceiling"),
            (replace(base, max_tags_per_object=1), "per-object tag ceiling"),
            (replace(base, max_references_per_object=2), "per-object reference ceiling"),
            (replace(base, max_text_utf8_bytes=4), "text UTF-8 byte ceiling"),
        )
        for limits, message in cases:
            with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary:
                with self.assertRaisesRegex(SelectionError, message):
                    self._scan(raw, Path(temporary), limits=limits)

    def test_binding_and_live_profile_claims_fail_closed(self) -> None:
        assert LIVE_NAME_ENVELOPE_PROFILE is not None
        self.assertEqual(
            getattr(selection_module, "LIVE_PLANET_SOURCE_SHA256", None),
            _AUTHORITATIVE_PLANET_SHA256,
        )
        assert LIVE_NAME_ENVELOPE_PBF_BYTES == 419_750_356
        assert LIVE_NAME_ENVELOPE_PBF_SHA256 == "ffb68c03d8fa2710bfd664dfd4ce43c01cc2fdbbb92599b4d892bd3bc0661b4d"
        assert LIVE_NAME_ENVELOPE_OPL_BYTES == 4_347_353_464
        assert LIVE_NAME_ENVELOPE_OPL_SHA256 == "628622248814b1a83727cf19bd7e22cc4ad66b61589c6f137585fd555910785b"
        assert LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT == "opl"
        assert LIVE_NAME_ENVELOPE_WAYS == 5_301_765
        assert LIVE_NAME_ENVELOPE_RELATIONS == 135_237
        self.assertEqual(SelectionLimits().max_input_bytes, 5 * 1024 * 1024 * 1024)
        self.assertGreater(SelectionLimits().max_input_bytes, LIVE_NAME_ENVELOPE_OPL_BYTES)
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with self.assertRaisesRegex(SelectionError, "converted stream"):
                self._scan(
                    raw,
                    stage,
                    bindings=replace(
                        self._bindings(raw),
                        converted_stream_sha256="0" * 64,
                    ),
                )
            self.assertEqual(list(stage.iterdir()), [])

    def test_live_boundary_accepts_separate_exact_pbf_and_canonical_opl_facts(self) -> None:
        required_fields = {
            "planet_source_sha256",
            "candidate_pbf_bytes",
            "candidate_pbf_sha256",
            "converted_stream_bytes",
            "converted_stream_sha256",
            "converted_stream_format",
        }
        self.assertTrue(
            required_fields.issubset(SelectionBindings.__dataclass_fields__),
            "selection bindings must distinguish candidate PBF facts from converted OPL facts",
        )
        raw = _opl(_way(1), _relation(2))
        candidate_pbf = b"locked candidate PBF fixture"
        bindings = SelectionBindings(
            planet_source_sha256=_AUTHORITATIVE_PLANET_SHA256,
            candidate_pbf_bytes=len(candidate_pbf),
            candidate_pbf_sha256=hashlib.sha256(candidate_pbf).hexdigest(),
            converted_stream_bytes=len(raw),
            converted_stream_sha256=hashlib.sha256(raw).hexdigest(),
            converted_stream_format="opl",
            runtime_sha256=_sha("runtime"),
            policy_sha256=GLOBAL_POLICY_SHA256,
            code_sha256=_sha("code"),
        )
        locked = {
            "LIVE_NAME_ENVELOPE_PBF_BYTES": len(candidate_pbf),
            "LIVE_NAME_ENVELOPE_PBF_SHA256": hashlib.sha256(candidate_pbf).hexdigest(),
            "LIVE_NAME_ENVELOPE_OPL_BYTES": len(raw),
            "LIVE_NAME_ENVELOPE_OPL_SHA256": hashlib.sha256(raw).hexdigest(),
            "LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT": "opl",
            "LIVE_NAME_ENVELOPE_WAYS": 1,
            "LIVE_NAME_ENVELOPE_RELATIONS": 1,
        }
        with tempfile.TemporaryDirectory() as temporary, mock.patch.multiple(
            selection_module,
            create=True,
            **locked,
        ):
            stage = Path(temporary)
            result = self._scan(
                raw,
                stage,
                bindings=bindings,
                profile=LIVE_NAME_ENVELOPE_PROFILE,
            )
            self.assertEqual(result.planet_source_sha256, bindings.planet_source_sha256)
            self.assertEqual(result.candidate_pbf_bytes, len(candidate_pbf))
            self.assertEqual(result.candidate_pbf_sha256, bindings.candidate_pbf_sha256)
            self.assertEqual(result.converted_stream_bytes, len(raw))
            self.assertEqual(result.converted_stream_sha256, bindings.converted_stream_sha256)
            self.assertEqual(result.converted_stream_format, "opl")
            identities = self._summary(stage)["identities"]
            self.assertEqual(
                identities,
                {
                    "candidatePbfBytes": len(candidate_pbf),
                    "candidatePbfSha256": bindings.candidate_pbf_sha256,
                    "codeSha256": bindings.code_sha256,
                    "convertedStreamBytes": len(raw),
                    "convertedStreamFormat": "opl",
                    "convertedStreamSha256": bindings.converted_stream_sha256,
                    "planetSourceSha256": bindings.planet_source_sha256,
                    "policySha256": bindings.policy_sha256,
                    "runtimeSha256": bindings.runtime_sha256,
                },
            )

    def test_live_boundary_rejects_wrong_pbf_stream_and_format_independently(self) -> None:
        raw = _opl(_way(1))
        candidate_pbf = b"locked candidate PBF fixture"
        bindings = SelectionBindings(
            planet_source_sha256=_AUTHORITATIVE_PLANET_SHA256,
            candidate_pbf_bytes=len(candidate_pbf),
            candidate_pbf_sha256=hashlib.sha256(candidate_pbf).hexdigest(),
            converted_stream_bytes=len(raw),
            converted_stream_sha256=hashlib.sha256(raw).hexdigest(),
            converted_stream_format="opl",
            runtime_sha256=_sha("runtime"),
            policy_sha256=GLOBAL_POLICY_SHA256,
            code_sha256=_sha("code"),
        )
        locked = {
            "LIVE_NAME_ENVELOPE_PBF_BYTES": len(candidate_pbf),
            "LIVE_NAME_ENVELOPE_PBF_SHA256": hashlib.sha256(candidate_pbf).hexdigest(),
            "LIVE_NAME_ENVELOPE_OPL_BYTES": len(raw),
            "LIVE_NAME_ENVELOPE_OPL_SHA256": hashlib.sha256(raw).hexdigest(),
            "LIVE_NAME_ENVELOPE_CONVERTED_STREAM_FORMAT": "opl",
            "LIVE_NAME_ENVELOPE_WAYS": 1,
            "LIVE_NAME_ENVELOPE_RELATIONS": 0,
        }
        cases = (
            (
                replace(bindings, planet_source_sha256=_sha("wrong-planet-source")),
                "planet source",
            ),
            (replace(bindings, candidate_pbf_bytes=len(candidate_pbf) + 1), "candidate PBF"),
            (replace(bindings, candidate_pbf_sha256="0" * 64), "candidate PBF"),
            (replace(bindings, converted_stream_bytes=len(raw) + 1), "converted stream"),
            (replace(bindings, converted_stream_sha256="0" * 64), "converted stream"),
            (replace(bindings, converted_stream_format="xml"), "format"),
        )
        with mock.patch.multiple(selection_module, create=True, **locked):
            for bad_bindings, message in cases:
                with self.subTest(message=message), tempfile.TemporaryDirectory() as temporary:
                    stage = Path(temporary)
                    with self.assertRaisesRegex(SelectionError, message):
                        self._scan(
                            raw,
                            stage,
                            bindings=bad_bindings,
                            profile=LIVE_NAME_ENVELOPE_PROFILE,
                        )
                    self.assertEqual(list(stage.iterdir()), [])

    def test_live_profile_is_default_and_fixture_profile_is_explicit(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with self.assertRaisesRegex(SelectionError, "live name-envelope"):
                scan_planet_roots(io.BytesIO(raw), stage, self._bindings(raw))
            self.assertEqual(list(stage.iterdir()), [])

        fixture_profile = "flight-alert-exp8-osm-planet-name-envelope-fixture-v1"
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            result = scan_planet_roots(
                io.BytesIO(raw),
                stage,
                self._bindings(raw),
                profile=fixture_profile,
            )
            self.assertEqual(result.selected_way_count, 1)
        self.assertEqual(
            getattr(selection_module, "FIXTURE_NAME_ENVELOPE_PROFILE", None),
            fixture_profile,
        )
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with self.assertRaisesRegex(SelectionError, "live name-envelope"):
                self._scan(raw, stage, profile=LIVE_NAME_ENVELOPE_PROFILE)
            self.assertEqual(list(stage.iterdir()), [])

    def test_invalid_stream_stage_bindings_limits_and_workers_are_fatal(self) -> None:
        assert SelectionBindings is not None
        assert SelectionLimits is not None
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            invalid_calls = (
                lambda: scan_planet_roots(bytearray(raw), stage, self._bindings(raw)),
                lambda: scan_planet_roots(io.BytesIO(raw), stage / "missing", self._bindings(raw)),
                lambda: scan_planet_roots(io.BytesIO(raw), stage, object()),
                lambda: scan_planet_roots(io.BytesIO(raw), stage, self._bindings(raw), limits=object()),
                lambda: scan_planet_roots(io.BytesIO(raw), stage, self._bindings(raw), workers=0),
                lambda: scan_planet_roots(
                    io.BytesIO(raw),
                    stage,
                    self._bindings(raw),
                    profile="flight-alert-exp8-osm-planet-selection-generic-v1",
                ),
                lambda: SelectionLimits(max_input_bytes=-1),
                lambda: SelectionBindings(
                    planet_source_sha256="A" * 64,
                    candidate_pbf_bytes=len(raw),
                    candidate_pbf_sha256=_sha("candidate-pbf"),
                    converted_stream_bytes=len(raw),
                    converted_stream_sha256=hashlib.sha256(raw).hexdigest(),
                    converted_stream_format="opl",
                    runtime_sha256=_sha("runtime"),
                    policy_sha256=GLOBAL_POLICY_SHA256,
                    code_sha256=_sha("code"),
                ),
            )
            for call in invalid_calls:
                with self.subTest(call=call), self.assertRaises(SelectionError):
                    call()

    def test_partial_commit_failure_removes_only_files_created_by_this_call(self) -> None:
        raw = _opl(_way(1), _relation(2))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            real_link = os.link
            calls = 0

            def fail_after_two(source, destination, *args, **kwargs):
                nonlocal calls
                calls += 1
                if calls == 3:
                    raise OSError("injected rename failure")
                return real_link(source, destination, *args, **kwargs)

            with mock.patch("tools.experiment8.osm_planet_selection.os.link", side_effect=fail_after_two):
                with self.assertRaisesRegex(SelectionError, "injected rename failure"):
                    self._scan(raw, stage)
            self.assertEqual(list(stage.iterdir()), [])

        real_artifacts = selection_module._Artifacts

        class FailingFinalOwnership(set[Path]):
            def __init__(self, target: str, replacement: bytes | None) -> None:
                super().__init__()
                self.target = target
                self.replacement = replacement
                self.replacement_denied = False

            def add(self, path: Path) -> None:
                if path.name == self.target:
                    if self.replacement is not None:
                        try:
                            path.unlink()
                            path.write_bytes(self.replacement)
                        except PermissionError:
                            self.replacement_denied = True
                    raise MemoryError("injected final ownership allocation")
                super().add(path)

        cases = (
            ("root-ids.txt", None),
            ("roots-127.bin", None),
            ("root-ids.txt", b"concurrent replacement"),
        )
        for target, replacement in cases:
            with self.subTest(target=target, replacement=replacement is not None):
                final_ownership = FailingFinalOwnership(target, replacement)

                class TrackingFailureArtifacts(real_artifacts):
                    def __init__(self, stage_dir: Path) -> None:
                        super().__init__(stage_dir)
                        self._owned_final = final_ownership

                with tempfile.TemporaryDirectory() as temporary:
                    stage = Path(temporary)
                    with mock.patch.object(
                        selection_module,
                        "_Artifacts",
                        TrackingFailureArtifacts,
                    ):
                        with self.assertRaisesRegex(SelectionError, "memory|allocation"):
                            self._scan(raw, stage)
                    expected = (
                        {}
                        if replacement is None or final_ownership.replacement_denied
                        else {target: replacement}
                    )
                    self.assertEqual(
                        {path.name: path.read_bytes() for path in stage.iterdir()},
                        expected,
                    )

    def test_same_inode_overwrite_at_first_readback_cannot_rebind_selected_output(self) -> None:
        raw = _opl(_way(1, version=1))
        bucket = hashlib.sha256(
            _BUCKET_DOMAIN + bytes((1,)) + struct.pack("<Q", 1)
        ).digest()[0]
        real_finish_payloads = selection_module._Artifacts.finish_payloads
        mutation: dict[str, object] = {}

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)

            def overwrite_before_first_readback(artifacts):
                if not mutation:
                    artifacts._handles["selected-tuples.bin"].flush()
                    artifacts._handles[f"roots-{bucket:03d}.bin"].flush()
                    tuple_path = next(stage.glob(".selected-tuples.bin.*.tmp"))
                    bucket_path = next(stage.glob(f".roots-{bucket:03d}.bin.*.tmp"))
                    try:
                        tuple_raw = bytearray(tuple_path.read_bytes())
                        bucket_raw = bytearray(bucket_path.read_bytes())
                    except PermissionError as error:
                        mutation["write_denied"] = True
                        raise SelectionError(
                            "WRITING external read/write denied"
                        ) from error
                    self.assertEqual(len(tuple_raw), 45)
                    self.assertGreaterEqual(len(bucket_raw), 4 + 13 + 32)
                    body_bytes = struct.unpack_from("<I", bucket_raw, 0)[0]
                    self.assertEqual(body_bytes + 4, len(bucket_raw))
                    payload = bytearray(bucket_raw[4:-32])
                    struct.pack_into("<I", payload, 9, 2)
                    digest = hashlib.sha256(_ROOT_DOMAIN + payload).digest()
                    replacement_bucket = (
                        struct.pack("<I", len(payload) + 32) + payload + digest
                    )
                    struct.pack_into("<I", tuple_raw, 9, 2)
                    tuple_raw[13:45] = digest
                    identities: dict[str, tuple[tuple[int, int], tuple[int, int]]] = {}
                    for target, replacement in (
                        (tuple_path, bytes(tuple_raw)),
                        (bucket_path, replacement_bucket),
                    ):
                        before = target.stat()
                        self.assertEqual(before.st_size, len(replacement))
                        with target.open("r+b") as output:
                            output.seek(0)
                            output.write(replacement)
                            output.flush()
                            os.fsync(output.fileno())
                        after = target.stat()
                        identities[target.name] = (
                            (before.st_dev, before.st_ino),
                            (after.st_dev, after.st_ino),
                        )
                    mutation.update(
                        {
                            "first_readback": "finish_payloads",
                            "identities": identities,
                        }
                    )
                return real_finish_payloads(artifacts)

            with mock.patch.object(
                selection_module._Artifacts,
                "finish_payloads",
                autospec=True,
                side_effect=overwrite_before_first_readback,
            ):
                with self.assertRaisesRegex(
                    SelectionError,
                    "changed|drift|readback|sealed|write|identity",
                ):
                    self._scan(raw, stage)

            self.assertTrue(mutation)
            for before, after in mutation.get("identities", {}).values():
                self.assertEqual(before, after)
            self.assertFalse((stage / "selection-summary.json").exists())

    def test_untracked_hard_links_fail_at_seal_commit_and_cleanup(self) -> None:
        raw = _opl(_way(1, version=1))

        with self.subTest(transition="WRITING to SEALED"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                stage = root / "stage"
                stage.mkdir()
                outside = root / "seal-alias.bin"
                real_finish = selection_module._Artifacts.finish_payloads

                def add_alias_before_seal(artifacts):
                    source = artifacts._temporary["selected-tuples.bin"]
                    os.link(source, outside)
                    return real_finish(artifacts)

                try:
                    with mock.patch.object(
                        selection_module._Artifacts,
                        "finish_payloads",
                        autospec=True,
                        side_effect=add_alias_before_seal,
                    ):
                        with self.assertRaisesRegex(
                            SelectionError,
                            "cleanup|link|SEALED|identity|ownership",
                        ):
                            self._scan(raw, stage)
                finally:
                    if outside.exists():
                        outside.unlink()

        with self.subTest(transition="SEALED to COMMITTED"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                stage = root / "stage"
                stage.mkdir()
                outside = root / "commit-alias.bin"
                real_link = os.link

                def add_alias_during_commit(source, destination, *args, **kwargs):
                    result = real_link(source, destination, *args, **kwargs)
                    if Path(destination).name == "selected-tuples.bin":
                        real_link(source, outside)
                    return result

                try:
                    with mock.patch.object(
                        selection_module.os,
                        "link",
                        side_effect=add_alias_during_commit,
                    ):
                        with self.assertRaisesRegex(
                            SelectionError,
                            "cleanup|link|COMMITTED|identity|ownership",
                        ):
                            self._scan(raw, stage)
                finally:
                    if outside.exists():
                        outside.unlink()

        with self.subTest(transition="cleanup disposition"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                stage = root / "stage"
                stage.mkdir()
                outside = root / "cleanup-alias.bin"
                artifacts = selection_module._Artifacts(stage)
                target = artifacts._temporary["root-ids.txt"]
                real_unlink = selection_module._unlink_exact_owned_file

                def add_alias_during_delete(
                    path: Path,
                    expected_file_id,
                    *,
                    require_single_link: bool = True,
                ):
                    if path == target:
                        os.link(path, outside)
                        return real_unlink(
                            path,
                            expected_file_id,
                            require_single_link=False,
                        )
                    return real_unlink(
                        path,
                        expected_file_id,
                        require_single_link=require_single_link,
                    )

                try:
                    with mock.patch.object(
                        selection_module,
                        "_unlink_exact_owned_file",
                        side_effect=add_alias_during_delete,
                    ):
                        with self.assertRaisesRegex(
                            SelectionError,
                            "cleanup|link|FileId|ownership",
                        ):
                            artifacts.cleanup()
                    self.assertTrue(outside.exists())
                finally:
                    artifacts.__del__()
                    if outside.exists():
                        outside.unlink()

    @unittest.skipUnless(os.name == "nt", "sealed sharing is NTFS-specific")
    def test_selector_sealed_handle_denies_new_writers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            artifacts = selection_module._Artifacts(stage)
            artifacts.finish_payloads()
            target = artifacts._temporary["selected-tuples.bin"]
            try:
                with self.assertRaises(PermissionError):
                    target.open("r+b")
            finally:
                artifacts.cleanup()

    def test_deleted_guardian_close_failure_retries_without_stale_path_ownership(self) -> None:
        class FailCloseOnce:
            def __init__(self, raw) -> None:
                self.raw = raw
                self.failed = False

            def close(self) -> None:
                if not self.failed:
                    self.failed = True
                    raise OSError("injected deleted guardian close failure")
                self.raw.close()

            def __getattr__(self, name: str):
                return getattr(self.raw, name)

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            artifacts = selection_module._Artifacts(stage)
            target = artifacts._temporary["root-ids.txt"]
            guardian = FailCloseOnce(artifacts._guardians[target])
            artifacts._guardians[target] = guardian

            with self.assertRaisesRegex(
                SelectionError,
                "close deleted guardian.*injected",
            ):
                artifacts.cleanup()
            self.assertFalse(target.exists())
            self.assertNotIn(target, artifacts._owned_file_ids)
            self.assertIs(artifacts._guardians[target], guardian)

            artifacts.cleanup()
            self.assertEqual(list(stage.iterdir()), [])
            self.assertEqual(artifacts._guardians, {})

    def test_partial_release_close_failure_keeps_only_live_exact_ownership(self) -> None:
        class FailCloseOnce:
            def __init__(self, raw) -> None:
                self.raw = raw
                self.failed = False

            def close(self) -> None:
                if not self.failed:
                    self.failed = True
                    raise OSError("injected release guardian close failure")
                self.raw.close()

            def __getattr__(self, name: str):
                return getattr(self.raw, name)

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            artifacts = selection_module._Artifacts(stage)
            artifacts.finish_payloads()
            artifacts.write_summary(b"{}\n")
            artifacts.commit()
            artifacts.freeze_committed()
            target = stage / "root-ids.txt"
            guardian = FailCloseOnce(artifacts._guardians[target])
            artifacts._guardians[target] = guardian

            with self.assertRaisesRegex(
                SelectionError,
                "final pin release.*injected",
            ):
                artifacts.release_ownership()
            self.assertNotIn(target, artifacts._final_handles)
            self.assertIn(target, artifacts._owned_file_ids)
            self.assertIs(artifacts._guardians[target], guardian)

            artifacts.cleanup()
            self.assertFalse(target.exists())
            self.assertNotIn(target, artifacts._owned_file_ids)

    def test_partial_initialization_and_reader_failures_preserve_caller_stage(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            real_create = selection_module._create_atomic_binary_writer

            def fail_second_artifact(path: Path, label: str):
                if "selected-tuples.bin" in path.name and path.name.endswith(".tmp"):
                    raise OSError("injected open failure")
                return real_create(path, label)

            with mock.patch.object(
                selection_module,
                "_create_atomic_binary_writer",
                side_effect=fail_second_artifact,
            ):
                with self.assertRaisesRegex(SelectionError, "injected open failure"):
                    self._scan(raw, stage)
            self.assertEqual(list(stage.iterdir()), [])

        constructor_lines, constructor_start = inspect.getsourcelines(
            selection_module._Artifacts.__init__
        )
        bucket_counts_line = constructor_start + next(
            index
            for index, line in enumerate(constructor_lines)
            if "self.bucket_counts = [0] * 256" in line
        )

        def fail_bucket_counts(frame, event, argument):
            del argument
            if (
                event == "line"
                and frame.f_code is selection_module._Artifacts.__init__.__code__
                and frame.f_lineno == bucket_counts_line
            ):
                raise MemoryError("injected bucket-count allocation")
            return fail_bucket_counts

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            sys.settrace(fail_bucket_counts)
            try:
                with self.assertRaisesRegex(SelectionError, "memory|allocation"):
                    self._scan(raw, stage)
            finally:
                sys.settrace(None)
            self.assertEqual(list(stage.iterdir()), [])

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with mock.patch.object(
                selection_module.os,
                "fstat",
                side_effect=MemoryError("injected temporary FileId allocation"),
            ):
                with self.assertRaisesRegex(SelectionError, "memory|allocation"):
                    self._scan(raw, stage)
            self.assertEqual(list(stage.iterdir()), [])

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            with self.assertRaisesRegex(SelectionError, "injected reader failure"):
                self._scan(raw, stage, stream=_ExplodingReadStream(raw))
            self.assertEqual(list(stage.iterdir()), [])

    def test_tracking_and_deletion_failure_retains_exact_temporary_ownership(self) -> None:
        class TrackThenFail(dict[Path, tuple[int, int]]):
            def __init__(self, existing) -> None:
                super().__init__(existing)
                self.attempted_file_id: tuple[int, int] | None = None

            def __setitem__(self, key: Path, value: tuple[int, int]) -> None:
                if key.name.startswith(".roots-000.bin."):
                    self.attempted_file_id = value
                    raise MemoryError("injected temporary ownership allocation")
                super().__setitem__(key, value)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "stage"
            stage.mkdir()
            artifacts = selection_module._Artifacts(stage)
            ownership = TrackThenFail(artifacts._owned_file_ids)
            artifacts._owned_file_ids = ownership
            try:
                with mock.patch.object(
                    selection_module,
                    "_unlink_exact_owned_file",
                    side_effect=SelectionError(
                        "injected missing moved temporary FileId"
                    ),
                ):
                    with self.assertRaisesRegex(
                        SelectionError,
                        "cleanup|FileId|identity|missing|moved|ownership",
                    ):
                        artifacts._create_temporary("roots-000.bin")

                self.assertIsNotNone(ownership.attempted_file_id)
                self.assertEqual(
                    getattr(artifacts, "_pending_file_id", None),
                    ownership.attempted_file_id,
                )
                self.assertIsNotNone(
                    getattr(artifacts, "_pending_guardian", None),
                )
            finally:
                artifacts.__del__()

    def test_preexisting_reserved_artifact_is_never_replaced(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            reserved = stage / "root-ids.txt"
            reserved.write_bytes(b"caller")
            with self.assertRaisesRegex(SelectionError, "must be empty"):
                self._scan(raw, stage)
            self.assertEqual(reserved.read_bytes(), b"caller")
            self.assertEqual(list(stage.iterdir()), [reserved])

    def test_caller_owned_stage_must_be_empty_before_start(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            witness = stage / "caller-owned.bin"
            witness.write_bytes(b"preserve exactly")
            with self.assertRaisesRegex(SelectionError, "must be empty"):
                self._scan(raw, stage)
            self.assertEqual({path.name: path.read_bytes() for path in stage.iterdir()}, {witness.name: b"preserve exactly"})

    def test_concurrently_created_temporary_is_not_claimed_or_removed(self) -> None:
        raw = _opl(_way(1))
        nonce = "ab" * 16
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            collision = stage / f".root-ids.txt.{nonce}.tmp"
            real_create = selection_module._create_atomic_binary_writer

            def collide(path: Path, label: str):
                if path == collision and not collision.exists():
                    collision.write_bytes(b"concurrent")
                return real_create(path, label)

            with mock.patch.object(selection_module.secrets, "token_hex", return_value=nonce):
                with mock.patch.object(
                    selection_module,
                    "_create_atomic_binary_writer",
                    side_effect=collide,
                ):
                    with self.assertRaises(SelectionError):
                        self._scan(raw, stage)
            self.assertEqual(collision.read_bytes(), b"concurrent")

    def test_concurrently_created_final_artifact_is_not_replaced_or_cleaned(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            collision = stage / "root-ids.txt"
            real_link = os.link

            def collide(source, destination, *args, **kwargs):
                destination_path = Path(destination)
                if destination_path == collision and not collision.exists():
                    collision.write_bytes(b"concurrent final")
                return real_link(source, destination, *args, **kwargs)

            with mock.patch.object(selection_module.os, "link", side_effect=collide):
                with self.assertRaisesRegex(SelectionError, "no-clobber commit"):
                    self._scan(raw, stage)
            self.assertEqual(collision.read_bytes(), b"concurrent final")
            self.assertEqual(list(stage.iterdir()), [collision])

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            collision = stage / "root-ids.txt"
            real_link = os.link
            calls = 0

            def replace_just_created_link(source, destination, *args, **kwargs):
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("injected failure after replacement")
                result = real_link(source, destination, *args, **kwargs)
                if Path(destination) == collision:
                    collision.unlink()
                    collision.write_bytes(b"concurrent replacement after link")
                return result

            with mock.patch.object(
                selection_module.os,
                "link",
                side_effect=replace_just_created_link,
            ):
                with self.assertRaisesRegex(
                    SelectionError,
                    "identity changed|injected failure after replacement",
                ):
                    self._scan(raw, stage)
            self.assertEqual(
                {path.name: path.read_bytes() for path in stage.iterdir()},
                {collision.name: b"concurrent replacement after link"},
            )

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            real_link = os.link
            replacement = b"concurrent temporary replacement after link"
            replaced_source: Path | None = None
            replacement_denied = False

            def replace_source_path_after_link(source, destination, *args, **kwargs):
                nonlocal replaced_source, replacement_denied
                result = real_link(source, destination, *args, **kwargs)
                destination_path = Path(destination)
                if destination_path.name == "root-ids.txt" and replaced_source is None:
                    replaced_source = Path(source)
                    try:
                        replaced_source.unlink()
                        replaced_source.write_bytes(replacement)
                    except PermissionError:
                        replacement_denied = True
                return result

            failure: SelectionError | None = None
            with mock.patch.object(
                selection_module.os,
                "link",
                side_effect=replace_source_path_after_link,
            ):
                try:
                    self._scan(raw, stage)
                except SelectionError as error:
                    failure = error
            self.assertIsNotNone(replaced_source)
            assert replaced_source is not None
            if replacement_denied:
                self.assertIsNone(failure)
                self.assertEqual((stage / "root-ids.txt").read_bytes(), b"w1\n")
                self.assertFalse(replaced_source.exists())
            else:
                self.assertIsNotNone(failure)
                assert failure is not None
                self.assertRegex(str(failure), "identity changed")
                self.assertEqual(
                    {path.name: path.read_bytes() for path in stage.iterdir()},
                    {replaced_source.name: replacement},
                )
                self.assertFalse((stage / "root-ids.txt").exists())

    def test_final_inventory_rejects_reader_injected_extra_and_cleans_only_owned_files(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            extra = stage / "concurrent-extra.bin"
            real_iterdir = Path.iterdir
            stage_reads = 0

            def inject_on_second_stage_read(path: Path):
                nonlocal stage_reads
                if path == stage:
                    stage_reads += 1
                    if stage_reads == 2:
                        extra.write_bytes(b"preserve concurrent path")
                return real_iterdir(path)

            with mock.patch(
                "pathlib.Path.iterdir",
                autospec=True,
                side_effect=inject_on_second_stage_read,
            ):
                with self.assertRaisesRegex(SelectionError, "final.*inventory|inventory.*changed"):
                    self._scan(raw, stage)
            self.assertGreaterEqual(stage_reads, 2)
            self.assertEqual(
                {path.name: path.read_bytes() for path in stage.iterdir()},
                {extra.name: b"preserve concurrent path"},
            )

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            extra = stage / "post-inventory-extra.bin"
            real_freeze = selection_module._Artifacts.freeze_committed

            def inject_after_initial_inventory(artifacts):
                extra.write_bytes(b"preserve post-inventory path")
                return real_freeze(artifacts)

            with mock.patch.object(
                selection_module._Artifacts,
                "freeze_committed",
                new=inject_after_initial_inventory,
            ):
                with self.assertRaisesRegex(
                    SelectionError,
                    "final.*inventory|inventory.*changed",
                ):
                    self._scan(raw, stage)
            self.assertEqual(
                {path.name: path.read_bytes() for path in stage.iterdir()},
                {extra.name: b"preserve post-inventory path"},
            )

        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            replaced = stage / "root-ids.txt"
            replacement = b"w999999\n"
            real_iterdir = Path.iterdir
            stage_reads = 0
            replacement_denied = False

            def replace_artifact_on_second_stage_read(path: Path):
                nonlocal stage_reads, replacement_denied
                if path == stage:
                    stage_reads += 1
                    if stage_reads == 2:
                        try:
                            replaced.unlink()
                            replaced.write_bytes(replacement)
                        except PermissionError:
                            replacement_denied = True
                return real_iterdir(path)

            failure: SelectionError | None = None
            with mock.patch(
                "pathlib.Path.iterdir",
                autospec=True,
                side_effect=replace_artifact_on_second_stage_read,
            ):
                try:
                    self._scan(raw, stage)
                except SelectionError as error:
                    failure = error
            self.assertGreaterEqual(stage_reads, 2)
            if replacement_denied:
                self.assertIsNone(failure)
                self.assertEqual(replaced.read_bytes(), b"w1\n")
            else:
                self.assertIsNotNone(failure)
                assert failure is not None
                self.assertRegex(
                    str(failure),
                    "final|identity|readback|SHA-256|semantic",
                )
                self.assertEqual(
                    {path.name: path.read_bytes() for path in stage.iterdir()},
                    {replaced.name: replacement},
                )

    def test_terminal_semantic_read_rejects_new_untracked_hard_link(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "stage"
            stage.mkdir()
            alias = root / "terminal-hard-link.bin"
            real_validate = selection_module._validate_final_tuples_and_roots
            linked = False

            def add_link_after_initial_facts(artifacts, *args, **kwargs):
                nonlocal linked
                result = real_validate(artifacts, *args, **kwargs)
                os.link(stage / "root-ids.txt", alias)
                linked = True
                return result

            with mock.patch.object(
                selection_module,
                "_validate_final_tuples_and_roots",
                side_effect=add_link_after_initial_facts,
            ):
                with self.assertRaisesRegex(
                    SelectionError,
                    "cleanup|hard link|link count|drift|changed|identity",
                ):
                    self._scan(raw, stage)
            self.assertTrue(linked)
            self.assertTrue(alias.exists())
            self.assertEqual(alias.read_bytes(), b"w1\n")

    def test_cleanup_preserves_replacement_swapped_after_identity_check(self) -> None:
        replacement = b"caller-owned replacement"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "stage"
            stage.mkdir()
            artifacts = selection_module._Artifacts(stage)
            target = artifacts._temporary["root-ids.txt"]
            moved = root / "moved-owned-root-ids.tmp"
            real_stat = selection_module.os.stat
            swapped = False

            def swap_after_identity(value, *args, **kwargs):
                nonlocal swapped
                status = real_stat(value, *args, **kwargs)
                if (
                    not swapped
                    and isinstance(value, (str, os.PathLike))
                    and Path(value) == target
                ):
                    os.replace(target, moved)
                    target.write_bytes(replacement)
                    swapped = True
                return status

            failure: SelectionError | None = None
            with mock.patch.object(
                selection_module.os,
                "stat",
                side_effect=swap_after_identity,
            ):
                try:
                    artifacts.cleanup()
                except SelectionError as error:
                    failure = error

            self.assertTrue(swapped)
            self.assertIsNotNone(failure)
            assert failure is not None
            self.assertRegex(str(failure), "cleanup|identity|replacement")
            self.assertEqual(target.read_bytes(), replacement)
            self.assertTrue(moved.exists())

    def test_cleanup_never_succeeds_while_renamed_owned_selector_files_survive(self) -> None:
        def assert_deleted_or_failed(
            artifacts,
            moved: Path,
        ) -> None:
            failure: SelectionError | None = None
            try:
                artifacts.cleanup()
            except SelectionError as error:
                failure = error
            if moved.exists():
                self.assertIsNotNone(failure)
                assert failure is not None
                self.assertRegex(str(failure), "cleanup|FileId|identity|unreleased|missing")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "temporary-stage"
            stage.mkdir()
            artifacts = selection_module._Artifacts(stage)
            artifacts.finish_payloads()
            target = artifacts._temporary["root-ids.txt"]
            moved = root / "renamed-owned-temporary.bin"
            target.rename(moved)
            assert_deleted_or_failed(artifacts, moved)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "final-stage"
            stage.mkdir()
            artifacts = selection_module._Artifacts(stage)
            artifacts.finish_payloads()
            artifacts.write_summary(b"{}\n")
            artifacts.commit()
            target = stage / "root-ids.txt"
            moved = root / "renamed-owned-final.bin"
            target.rename(moved)
            assert_deleted_or_failed(artifacts, moved)

    def test_cleanup_failure_is_typed_and_surfaces_over_primary_failure(self) -> None:
        raw = _opl(_way(1))
        with tempfile.TemporaryDirectory() as temporary:
            stage = Path(temporary)
            real_delete = selection_module._unlink_exact_owned_file

            def fail_owned_unlink(path: Path, expected_file_id):
                if path.name.endswith(".tmp"):
                    raise SelectionError("injected cleanup failure")
                return real_delete(path, expected_file_id)

            with mock.patch.object(
                selection_module,
                "_unlink_exact_owned_file",
                side_effect=fail_owned_unlink,
            ):
                with self.assertRaisesRegex(SelectionError, "cleanup.*injected cleanup failure"):
                    self._scan(raw, stage, stream=_ExplodingReadStream(raw))


class PlanetSelectionRedGate(unittest.TestCase):
    def test_production_module_exists(self) -> None:
        self.assertIsNotNone(
            scan_planet_roots,
            "tools.experiment8.osm_planet_selection must be implemented",
        )


if __name__ == "__main__":
    unittest.main()
