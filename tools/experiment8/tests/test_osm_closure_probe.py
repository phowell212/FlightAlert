from __future__ import annotations

import hashlib
import io
import json
import sys
import unittest
from unittest.mock import patch

from tools.experiment8.osm_hydro_source import MissingReferences
from tools.experiment8.osm_closure_probe import (
    BOOST_LIBRARY_PATH,
    BOOST_LIBRARY_SHA256,
    ClosureProbeError,
    OSMIUM_BINARY_PATH,
    OSMIUM_BINARY_SHA256,
    PINNED_LOCALE,
    PINNED_UBUNTU_DISTRIBUTION,
    PINNED_UBUNTU_RELEASE,
    ProcessEvidence,
    PinnedOsmiumClosureProbe,
    parse_getid_result,
    probe_relation_root_closures,
    run_bounded_process,
)


SOURCE_PATH = (
    "/mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/source/"
    "maryland-260710.osm.pbf"
)
SOURCE_SHA256 = "7a9c9baf554aa424f27d80e7aa20ccc7d2d412815613afe93b6af06ba703f99f"


def _timed(line: str, elapsed: str = " 0:00") -> bytes:
    return f"[{elapsed}] {line}\n".encode("utf-8")


def _getid_stderr(
    relation_ids: tuple[int, ...],
    *,
    missing: MissingReferences | None = None,
) -> bytes:
    lines = (
        "Started osmium getid",
        "  osmium version 1.11.1",
        "  libosmium version 2.15.4",
        "Command line options and default settings:",
        "  input options:",
        f"    file name: {SOURCE_PATH}",
        "    file format: ",
        "  output options:",
        "    file name: /dev/null",
        "    file format: pbf",
        "    generator: osmium/1.11.1",
        "    overwrite: yes",
        "    fsync: no",
        "  other options:",
        "    add referenced objects: yes",
        "    remove tags on non-matching objects: no",
        "    work with history files: no",
        "    default object type: node",
        "    looking for these ids:",
        "      nodes:",
        "      ways:",
        "      relations: " + " ".join(str(value) for value in relation_ids),
        "Following references...",
        "  Reading input file to find relations in relations...",
        "  Reading input file to find nodes/ways in relations...",
        "  Reading input file to find nodes in ways...",
        "Done following references.",
        "Opening input file...",
        "Opening output file...",
        "Copying matching objects to output file...",
        "Closing output file...",
        "Closing input file...",
    )
    output = b"".join(_timed(line) for line in lines)
    if missing is None:
        output += _timed("Found all objects.", " 0:04")
    else:
        output += _timed(f"Did not find {missing.count} object(s).", " 0:04")
        for label, values in (
            ("node", missing.node_ids),
            ("way", missing.way_ids),
            ("relation", missing.relation_ids),
        ):
            if values:
                output += (
                    f"Missing {label} IDs: "
                    + " ".join(str(value) for value in values)
                    + "\n"
                ).encode("ascii")
    output += _timed("Peak memory used: 0 MBytes", " 0:04")
    output += _timed("Done.", " 0:04")
    return output


def _process(
    argv: tuple[str, ...],
    returncode: int,
    stdout: bytes,
    stderr: bytes,
) -> ProcessEvidence:
    return ProcessEvidence(
        argv=argv,
        returncode=returncode,
        stdout=stdout,
        stderr=stderr,
    )


class ExactGetidParserTests(unittest.TestCase):
    def test_exit_zero_accepts_only_the_exact_found_all_transcript(self) -> None:
        result = parse_getid_result(
            returncode=0,
            stdout=b"",
            stderr=_getid_stderr((12152277,)),
            source_wsl_path=SOURCE_PATH,
            relation_ids=(12152277,),
        )

        self.assertEqual(result, MissingReferences((), (), ()))

    def test_exit_one_accepts_only_exact_sorted_missing_ids_and_count(self) -> None:
        expected = MissingReferences(
            node_ids=(1528821241,),
            way_ids=(1168316844, 1353723351),
            relation_ids=(20611039,),
        )

        result = parse_getid_result(
            returncode=1,
            stdout=b"",
            stderr=_getid_stderr((11479485,), missing=expected),
            source_wsl_path=SOURCE_PATH,
            relation_ids=(11479485,),
        )

        self.assertEqual(result, expected)

    def test_exit_code_and_transcript_outcome_must_agree(self) -> None:
        missing = MissingReferences((), (99,), ())
        cases = (
            (0, _getid_stderr((20,), missing=missing), "exit 0.*missing"),
            (1, _getid_stderr((20,)), "exit 1.*Found all"),
            (2, b"osmium: operational failure\n", "unsupported exit code"),
        )
        for returncode, stderr, message in cases:
            with self.subTest(returncode=returncode):
                with self.assertRaisesRegex(ClosureProbeError, message):
                    parse_getid_result(
                        returncode=returncode,
                        stdout=b"",
                        stderr=stderr,
                        source_wsl_path=SOURCE_PATH,
                        relation_ids=(20,),
                    )

    def test_stdout_extra_lines_wrong_ids_and_operational_noise_are_fatal(self) -> None:
        valid = _getid_stderr((20, 21))
        cases = (
            (b"unexpected", valid, "stdout"),
            (b"", valid.replace(b"relations: 20 21", b"relations: 20 22"), "requested relation"),
            (b"", valid + b"ERROR: late write failure\n", "transcript"),
            (b"", valid.replace(b"Found all objects.\n", b"Found all objects.\r\n"), "transcript"),
        )
        for stdout, stderr, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ClosureProbeError, message):
                    parse_getid_result(
                        returncode=0,
                        stdout=stdout,
                        stderr=stderr,
                        source_wsl_path=SOURCE_PATH,
                        relation_ids=(20, 21),
                    )

    def test_missing_id_grammar_rejects_ambiguity_duplicates_and_bad_counts(self) -> None:
        valid = _getid_stderr((20,), missing=MissingReferences((7,), (99, 100), ()))
        cases = (
            (valid.replace(b"99 100", b"100 99"), "strictly increasing"),
            (valid.replace(b"99 100", b"99 99"), "strictly increasing"),
            (valid.replace(b"99 100", b"+99 100"), "missing way"),
            (valid.replace(b"99 100", b"99,100"), "missing way"),
            (valid.replace(b"Did not find 3", b"Did not find 4"), "count"),
            (valid.replace(b"Missing node IDs", b"Missing way IDs"), "duplicate"),
        )
        for stderr, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(ClosureProbeError, message):
                    parse_getid_result(
                        returncode=1,
                        stdout=b"",
                        stderr=stderr,
                        source_wsl_path=SOURCE_PATH,
                        relation_ids=(20,),
                    )


class BoundedProcessTests(unittest.TestCase):
    def test_keyboard_interrupt_during_reader_start_kills_and_reaps_child(self) -> None:
        class SetupProcess:
            def __init__(self) -> None:
                self.stdout = io.BytesIO()
                self.stderr = io.BytesIO()
                self.returncode: int | None = None
                self.killed = False
                self.wait_calls = 0

            def wait(self, timeout: float | None = None) -> int:
                self.wait_calls += 1
                if not self.killed:
                    raise AssertionError("setup-interrupted child was not killed")
                self.returncode = -9
                return self.returncode

            def kill(self) -> None:
                self.killed = True

        for interrupted_start in (1, 2):
            with self.subTest(interrupted_start=interrupted_start):
                process = SetupProcess()
                side_effect: list[BaseException | None] = [None] * (
                    interrupted_start - 1
                )
                side_effect.append(KeyboardInterrupt())
                with patch(
                    "tools.experiment8.osm_closure_probe.subprocess.Popen",
                    return_value=process,
                ), patch(
                    "tools.experiment8.osm_closure_probe.threading.Thread.start",
                    side_effect=side_effect,
                ):
                    with self.assertRaises(KeyboardInterrupt):
                        run_bounded_process(("fixture",))
                self.assertTrue(process.killed)
                self.assertGreaterEqual(process.wait_calls, 1)

    def test_keyboard_interrupt_kills_reaps_and_preserves_the_interrupt(self) -> None:
        class InterruptingProcess:
            def __init__(self) -> None:
                self.stdout = io.BytesIO()
                self.stderr = io.BytesIO()
                self.returncode: int | None = None
                self.killed = False
                self.wait_calls = 0

            def wait(self, timeout: float | None = None) -> int:
                self.wait_calls += 1
                if not self.killed:
                    raise KeyboardInterrupt
                self.returncode = -9
                return self.returncode

            def kill(self) -> None:
                self.killed = True

        process = InterruptingProcess()
        with patch(
            "tools.experiment8.osm_closure_probe.subprocess.Popen",
            return_value=process,
        ):
            with self.assertRaises(KeyboardInterrupt):
                run_bounded_process(("fixture",))
        self.assertTrue(process.killed)
        self.assertGreaterEqual(process.wait_calls, 2)

    def test_real_process_capture_is_immutable_and_hashes_exact_raw_bytes(self) -> None:
        argv = (
            sys.executable,
            "-c",
            "import sys;sys.stdout.buffer.write(b'out');sys.stderr.buffer.write(b'err')",
        )

        evidence = run_bounded_process(argv, max_output_bytes=64, timeout_seconds=5)

        self.assertEqual(evidence.returncode, 0)
        self.assertEqual(evidence.stdout, b"out")
        self.assertEqual(evidence.stderr, b"err")
        self.assertEqual(evidence.stdout_sha256, hashlib.sha256(b"out").hexdigest())
        self.assertEqual(evidence.stderr_sha256, hashlib.sha256(b"err").hexdigest())
        expected_argv_bytes = (
            json.dumps(list(argv), ensure_ascii=False, separators=(",", ":")) + "\n"
        ).encode("utf-8")
        self.assertEqual(
            evidence.argv_sha256, hashlib.sha256(expected_argv_bytes).hexdigest()
        )
        with self.assertRaises((AttributeError, TypeError)):
            evidence.stdout = b"changed"  # type: ignore[misc]

    def test_output_overflow_is_bounded_and_fatal_with_raw_prefix_evidence(self) -> None:
        argv = (
            sys.executable,
            "-c",
            "import sys;sys.stdout.buffer.write(b'x'*4096)",
        )

        with self.assertRaisesRegex(ClosureProbeError, "stdout exceeded") as raised:
            run_bounded_process(argv, max_output_bytes=32, timeout_seconds=5)

        self.assertIsNotNone(raised.exception.process_evidence)
        assert raised.exception.process_evidence is not None
        self.assertEqual(len(raised.exception.process_evidence.stdout), 33)
        self.assertEqual(raised.exception.process_evidence.stdout, b"x" * 33)

    def test_timeout_is_operational_failure_and_retains_bounded_process_evidence(self) -> None:
        argv = (sys.executable, "-c", "import time;time.sleep(5)")

        with self.assertRaisesRegex(ClosureProbeError, "timed out") as raised:
            run_bounded_process(argv, max_output_bytes=32, timeout_seconds=0.05)

        self.assertIsNotNone(raised.exception.process_evidence)


class PinnedRunnerTests(unittest.TestCase):
    def _fake_runner(self, calls: list[tuple[str, ...]], missing: MissingReferences):
        def run(
            argv: tuple[str, ...],
            *,
            max_output_bytes: int,
            timeout_seconds: float,
        ) -> ProcessEvidence:
            calls.append(argv)
            self.assertGreater(max_output_bytes, 0)
            self.assertGreater(timeout_seconds, 0)
            if argv[-2:] == ("/usr/bin/lsb_release", "-ds"):
                return _process(argv, 0, b"Ubuntu 20.04.3 LTS\n", b"")
            if "/usr/bin/sha256sum" in argv:
                stdout = (
                    f"{OSMIUM_BINARY_SHA256} *{OSMIUM_BINARY_PATH}\n"
                    f"{BOOST_LIBRARY_SHA256} *{BOOST_LIBRARY_PATH}\n"
                    f"{SOURCE_SHA256} *{SOURCE_PATH}\n"
                ).encode("ascii")
                return _process(argv, 0, stdout, b"")
            relation_ids = tuple(
                int(value[1:]) for value in argv if value.startswith("r") and value[1:].isdigit()
            )
            return _process(
                argv,
                0 if not missing.count else 1,
                b"",
                _getid_stderr(
                    relation_ids,
                    missing=missing if missing.count else None,
                ),
            )

        return run

    def test_probe_uses_only_exact_direct_argv_and_records_all_raw_evidence(self) -> None:
        calls: list[tuple[str, ...]] = []
        missing = MissingReferences((), (1168316844,), (20611039,))
        fake = self._fake_runner(calls, missing)

        with patch(
            "tools.experiment8.osm_closure_probe.run_bounded_process",
            side_effect=fake,
        ):
            evidence = probe_relation_root_closures(
                (11479485,),
                source_wsl_path=SOURCE_PATH,
                source_sha256=SOURCE_SHA256,
            )

        self.assertEqual(evidence.missing_references, missing)
        self.assertEqual(evidence.status, "source_incomplete")
        self.assertEqual(evidence.relation_ids, (11479485,))
        self.assertEqual(evidence.source_sha256, SOURCE_SHA256)
        self.assertEqual(evidence.runtime.ubuntu_release, PINNED_UBUNTU_RELEASE)
        self.assertEqual(evidence.process.stdout, b"")
        self.assertEqual(evidence.process.stderr, _getid_stderr((11479485,), missing=missing))
        self.assertEqual(evidence.process.stderr_sha256, hashlib.sha256(evidence.process.stderr).hexdigest())
        self.assertEqual(len(calls), 4)
        self.assertEqual(calls[0][-2:], ("/usr/bin/lsb_release", "-ds"))

        getid = calls[2]
        self.assertEqual(getid[0], r"C:\Windows\System32\wsl.exe")
        self.assertEqual(getid[1:5], ("-d", PINNED_UBUNTU_DISTRIBUTION, "--", "/usr/bin/env"))
        self.assertIn("-i", getid)
        self.assertIn(f"LC_ALL={PINNED_LOCALE}", getid)
        self.assertIn(f"LANG={PINNED_LOCALE}", getid)
        self.assertIn(OSMIUM_BINARY_PATH, getid)
        self.assertEqual(
            getid[getid.index("getid") + 1 : getid.index("getid") + 5],
            ("--no-progress", "-r", "--verbose-ids", "-f"),
        )
        self.assertEqual(getid[getid.index("-o") + 1], "/dev/null")
        self.assertEqual(getid[-2:], (SOURCE_PATH, "r11479485"))
        self.assertNotIn("bash", getid)
        self.assertNotIn("sh", getid)
        self.assertNotIn("-c", getid)
        self.assertEqual(calls[1], calls[3])
        self.assertEqual(
            evidence.post_hash_process.stdout,
            evidence.runtime.hash_process.stdout,
        )

    def test_callable_adapter_matches_audit_probe_api_and_retains_immutable_records(self) -> None:
        calls: list[tuple[str, ...]] = []
        missing = MissingReferences((7,), (99,), ())
        fake = self._fake_runner(calls, missing)
        probe = PinnedOsmiumClosureProbe(
            source_wsl_path=SOURCE_PATH,
            source_sha256=SOURCE_SHA256,
        )

        with patch(
            "tools.experiment8.osm_closure_probe.run_bounded_process",
            side_effect=fake,
        ):
            result = probe((20, 21))

        self.assertEqual(result, missing)
        self.assertEqual(len(probe.records), 1)
        self.assertEqual(probe.records[0].relation_ids, (20, 21))
        self.assertIsInstance(probe.records, tuple)

    def test_runtime_identity_hash_or_operational_mismatch_is_fatal(self) -> None:
        cases = (
            (b"Ubuntu 22.04 LTS\n", b"", "Ubuntu release"),
            (
                b"Ubuntu 20.04.3 LTS\n",
                b"0" * 64 + b" *wrong\n",
                "runtime hash transcript",
            ),
        )
        for release_stdout, hash_stdout, message in cases:
            calls = 0

            def fake(
                argv: tuple[str, ...],
                *,
                max_output_bytes: int,
                timeout_seconds: float,
            ) -> ProcessEvidence:
                nonlocal calls
                calls += 1
                if calls == 1:
                    return _process(argv, 0, release_stdout, b"")
                return _process(argv, 0, hash_stdout, b"")

            with self.subTest(message=message), patch(
                "tools.experiment8.osm_closure_probe.run_bounded_process",
                side_effect=fake,
            ):
                with self.assertRaisesRegex(ClosureProbeError, message):
                    probe_relation_root_closures(
                        (20,),
                        source_wsl_path=SOURCE_PATH,
                        source_sha256=SOURCE_SHA256,
                    )

    def test_post_probe_hash_mismatch_prevents_acceptance(self) -> None:
        calls: list[tuple[str, ...]] = []
        valid = self._fake_runner(calls, MissingReferences((), (), ()))
        invocation = 0

        def change_after_getid(
            argv: tuple[str, ...],
            *,
            max_output_bytes: int,
            timeout_seconds: float,
        ) -> ProcessEvidence:
            nonlocal invocation
            invocation += 1
            evidence = valid(
                argv,
                max_output_bytes=max_output_bytes,
                timeout_seconds=timeout_seconds,
            )
            if invocation == 4:
                return _process(argv, 0, b"changed after getid\n", b"")
            return evidence

        with patch(
            "tools.experiment8.osm_closure_probe.run_bounded_process",
            side_effect=change_after_getid,
        ):
            with self.assertRaisesRegex(ClosureProbeError, "post-probe.*hash"):
                probe_relation_root_closures(
                    (20,),
                    source_wsl_path=SOURCE_PATH,
                    source_sha256=SOURCE_SHA256,
                )

    def test_invalid_batch_source_path_or_hash_fails_before_spawning(self) -> None:
        cases = (
            ((21, 20), SOURCE_PATH, SOURCE_SHA256, "strictly increasing"),
            ((20,), "relative/source.pbf", SOURCE_SHA256, "absolute canonical"),
            ((20,), "//mnt/e/source.osm.pbf", SOURCE_SHA256, "absolute canonical"),
            ((20,), SOURCE_PATH, "A" * 64, "lowercase SHA-256"),
        )
        for relation_ids, source_path, source_hash, message in cases:
            with self.subTest(message=message), patch(
                "tools.experiment8.osm_closure_probe.run_bounded_process"
            ) as runner:
                with self.assertRaisesRegex(ClosureProbeError, message):
                    probe_relation_root_closures(
                        relation_ids,
                        source_wsl_path=source_path,
                        source_sha256=source_hash,
                    )
                runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()
