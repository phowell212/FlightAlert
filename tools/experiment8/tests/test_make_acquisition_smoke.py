from __future__ import annotations

import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.experiment8.make_acquisition_smoke import (
    SmokeManifestError,
    build_acquisition_smoke,
    main,
)


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write(path: Path, rows: list[dict[str, object]]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as output:
        for row in rows:
            output.write(json.dumps(row, sort_keys=True, separators=(",", ":")) + "\n")


def _build(base: Path, fixtures: list[dict[str, object]], stage: list[dict[str, object]], name: str):
    fixture_path = base / f"{name}-fixtures.jsonl"
    stage_path = base / f"{name}-stage.jsonl"
    _write(fixture_path, fixtures)
    _write(stage_path, stage)
    output = base / name
    summary = build_acquisition_smoke(
        fixture_path=fixture_path,
        expected_fixture_sha256=_sha(fixture_path),
        stage_a_path=stage_path,
        expected_stage_a_sha256=_sha(stage_path),
        output_dir=output,
        first_count=1,
    )
    return output, summary


class AcquisitionSmokeTests(unittest.TestCase):
    def test_retains_explicit_known_empty_fixtures_and_reconciles_counts(self) -> None:
        fixtures = [
            {
                "fixtureNames": ["present-place"],
                "sourceState": "present",
                "z": 2,
                "x": 0,
                "y": 0,
            },
            {
                "fixtureNames": ["empty-ocean"],
                "sourceState": "known_empty",
                "z": 2,
                "x": 1,
                "y": 0,
            },
        ]
        stage = [{"z": 2, "x": 2, "y": 0}]
        with tempfile.TemporaryDirectory() as directory:
            output, summary = _build(Path(directory), fixtures, stage, "smoke")
            rows = [json.loads(line) for line in (output / "sample.jsonl").read_text().splitlines()]

            self.assertEqual(summary["fixtureRowCount"], 2)
            self.assertEqual(summary["fixturePresentCount"], 1)
            self.assertEqual(summary["fixtureKnownEmptyCount"], 1)
            self.assertEqual(summary["stageASelectedCount"], 1)
            self.assertEqual(summary["rowCount"], 3)
            self.assertEqual(summary["fetchableCount"], 2)
            self.assertEqual(summary["knownEmptyCount"], 1)
            self.assertEqual(
                [row["sourceState"] for row in rows],
                ["present", "known_empty", "present"],
            )
            self.assertEqual(
                hashlib.sha256((output / "sample.jsonl").read_bytes()).hexdigest(),
                summary["sampleSha256"],
            )
            self.assertEqual(
                json.loads((output / "summary.json").read_text()), summary
            )

    def test_input_order_does_not_change_canonical_outputs(self) -> None:
        fixtures = [
            {"fixtureNames": ["b"], "sourceState": "present", "z": 3, "x": 2, "y": 0},
            {"fixtureNames": ["a"], "sourceState": "known_empty", "z": 3, "x": 1, "y": 0},
        ]
        stage = [{"z": 3, "x": 4, "y": 0}, {"z": 3, "x": 3, "y": 0}]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            first, first_summary = _build(base, fixtures, stage, "first")
            second, second_summary = _build(
                base, list(reversed(fixtures)), list(reversed(stage)), "second"
            )
            self.assertEqual(
                (first / "sample.jsonl").read_bytes(),
                (second / "sample.jsonl").read_bytes(),
            )
            for summary in (first_summary, second_summary):
                summary.pop("fixtureInputSha256")
                summary.pop("stageAInputSha256")
            self.assertEqual(first_summary, second_summary)

    def test_present_overlap_is_merged_and_empty_overlap_is_rejected(self) -> None:
        present = [
            {"fixtureNames": ["same"], "sourceState": "present", "z": 2, "x": 0, "y": 0}
        ]
        stage = [{"z": 2, "x": 0, "y": 0}]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            output, summary = _build(base, present, stage, "merged")
            row = json.loads((output / "sample.jsonl").read_text())
            self.assertEqual(summary["overlapCount"], 1)
            self.assertEqual(summary["rowCount"], 1)
            self.assertEqual(row["selection"], "fixture+smoke")
            self.assertEqual(row["sources"], ["fixtures", "stage-a"])

            empty = [
                {
                    "fixtureNames": ["same"],
                    "sourceState": "known_empty",
                    "z": 2,
                    "x": 0,
                    "y": 0,
                }
            ]
            fixture_path = base / "empty.jsonl"
            stage_path = base / "stage.jsonl"
            _write(fixture_path, empty)
            _write(stage_path, stage)
            with self.assertRaisesRegex(SmokeManifestError, "contradicts known_empty"):
                build_acquisition_smoke(
                    fixture_path=fixture_path,
                    expected_fixture_sha256=_sha(fixture_path),
                    stage_a_path=stage_path,
                    expected_stage_a_sha256=_sha(stage_path),
                    output_dir=base / "rejected",
                    first_count=1,
                )

    def test_rejects_hash_mismatch_and_duplicate_input(self) -> None:
        row = {"fixtureNames": ["a"], "sourceState": "present", "z": 1, "x": 0, "y": 0}
        stage_row = {"z": 1, "x": 1, "y": 0}
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            fixture_path = base / "fixtures.jsonl"
            stage_path = base / "stage.jsonl"
            _write(fixture_path, [row])
            _write(stage_path, [stage_row])
            with self.assertRaisesRegex(SmokeManifestError, "fixture SHA-256 mismatch"):
                build_acquisition_smoke(
                    fixture_path=fixture_path,
                    expected_fixture_sha256="0" * 64,
                    stage_a_path=stage_path,
                    expected_stage_a_sha256=_sha(stage_path),
                    output_dir=base / "hash-failure",
                    first_count=1,
                )
            _write(fixture_path, [row, row])
            with self.assertRaisesRegex(SmokeManifestError, "duplicate fixture tile"):
                build_acquisition_smoke(
                    fixture_path=fixture_path,
                    expected_fixture_sha256=_sha(fixture_path),
                    stage_a_path=stage_path,
                    expected_stage_a_sha256=_sha(stage_path),
                    output_dir=base / "duplicate-failure",
                    first_count=1,
                )

    def test_output_replace_failure_restores_both_previous_files(self) -> None:
        old_fixtures = [
            {"fixtureNames": ["old"], "sourceState": "present", "z": 2, "x": 0, "y": 0}
        ]
        new_fixtures = [
            {"fixtureNames": ["new"], "sourceState": "present", "z": 2, "x": 1, "y": 0}
        ]
        stage = [{"z": 2, "x": 2, "y": 0}]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            output, _ = _build(base, old_fixtures, stage, "output")
            original = {
                name: (output / name).read_bytes() for name in ("sample.jsonl", "summary.json")
            }
            fixture_path = base / "new-fixtures.jsonl"
            stage_path = base / "new-stage.jsonl"
            _write(fixture_path, new_fixtures)
            _write(stage_path, stage)
            real_replace = os.replace

            def fail_summary(source: object, destination: object) -> None:
                if Path(destination).name == "summary.json" and Path(source).suffix == ".tmp":
                    raise OSError("injected smoke summary install failure")
                real_replace(source, destination)

            with mock.patch("tools.experiment8.acquire.os.replace", side_effect=fail_summary):
                with self.assertRaisesRegex(OSError, "injected smoke summary"):
                    build_acquisition_smoke(
                        fixture_path=fixture_path,
                        expected_fixture_sha256=_sha(fixture_path),
                        stage_a_path=stage_path,
                        expected_stage_a_sha256=_sha(stage_path),
                        output_dir=output,
                        first_count=1,
                    )
            self.assertEqual(
                {name: (output / name).read_bytes() for name in original}, original
            )
            self.assertEqual(list(output.glob("*.tmp")), [])
            self.assertEqual(list(output.glob("*.bak")), [])

    def test_cli_builds_manifest(self) -> None:
        fixtures = [
            {"fixtureNames": ["a"], "sourceState": "present", "z": 1, "x": 0, "y": 0}
        ]
        stage = [{"z": 1, "x": 1, "y": 0}]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            fixture_path = base / "fixtures.jsonl"
            stage_path = base / "stage.jsonl"
            _write(fixture_path, fixtures)
            _write(stage_path, stage)
            result = main(
                [
                    "--fixtures",
                    str(fixture_path),
                    "--expected-fixture-sha256",
                    _sha(fixture_path),
                    "--stage-a",
                    str(stage_path),
                    "--expected-stage-a-sha256",
                    _sha(stage_path),
                    "--first-count",
                    "1",
                    "--out",
                    str(base / "out"),
                ]
            )
            self.assertEqual(result, 0)
            self.assertTrue((base / "out" / "sample.jsonl").is_file())


if __name__ == "__main__":
    unittest.main()
