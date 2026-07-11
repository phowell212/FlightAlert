from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from typing import Literal, Mapping, Sequence

from .acquire import _atomic_text, _commit_output_set
from .model import TileKey


_HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_MAX_INPUT_ROWS = 123_283
_MAX_LINE_BYTES = 16 * 1024


class SmokeManifestError(ValueError):
    """A smoke-manifest input or publication invariant was violated."""


def _read_rows(
    path: Path,
    expected_sha256: str,
    role: Literal["fixture", "stage"],
) -> dict[int, tuple[TileKey, dict[str, object]]]:
    if _HEX_SHA256.fullmatch(expected_sha256) is None:
        raise SmokeManifestError(f"expected {role} SHA-256 is invalid")
    try:
        resolved = path.resolve(strict=True)
        source = resolved.open("rb")
    except OSError as error:
        raise SmokeManifestError(f"{role} input is unavailable: {path}: {error}") from error
    digest = hashlib.sha256()
    rows: dict[int, tuple[TileKey, dict[str, object]]] = {}
    with source:
        line_number = 0
        while True:
            raw_line = source.readline(_MAX_LINE_BYTES + 1)
            if not raw_line:
                break
            line_number += 1
            digest.update(raw_line)
            if len(raw_line) > _MAX_LINE_BYTES:
                raise SmokeManifestError(
                    f"{role} line {line_number} exceeds {_MAX_LINE_BYTES}-byte limit"
                )
            if line_number > _MAX_INPUT_ROWS:
                raise SmokeManifestError(
                    f"{role} input exceeds Experiment 8 pilot row limit {_MAX_INPUT_ROWS}"
                )
            try:
                document = json.loads(raw_line)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise SmokeManifestError(
                    f"{role} line {line_number} is invalid JSON: {error}"
                ) from error
            if not isinstance(document, dict):
                raise SmokeManifestError(f"{role} line {line_number} must be an object")
            try:
                coordinates = tuple(document[name] for name in ("z", "x", "y"))
                if any(
                    isinstance(value, bool) or not isinstance(value, int)
                    for value in coordinates
                ):
                    raise TypeError("coordinates must be JSON integers")
                tile = TileKey(*coordinates)
            except (KeyError, TypeError, ValueError) as error:
                raise SmokeManifestError(
                    f"{role} line {line_number} has invalid coordinates"
                ) from error
            if tile.packed in rows:
                raise SmokeManifestError(
                    f"duplicate {role} tile {tile.z}/{tile.x}/{tile.y}"
                )
            source_state = document.get("sourceState")
            normalized: dict[str, object] = {}
            if role == "fixture":
                if source_state not in ("present", "known_empty"):
                    raise SmokeManifestError(
                        f"fixture line {line_number} has invalid sourceState {source_state!r}"
                    )
                fixture_names = document.get("fixtureNames")
                if (
                    not isinstance(fixture_names, list)
                    or not fixture_names
                    or any(not isinstance(name, str) or not name for name in fixture_names)
                ):
                    raise SmokeManifestError(
                        f"fixture line {line_number} has invalid fixtureNames"
                    )
                normalized["fixtureNames"] = sorted(set(fixture_names))
                normalized["sourceState"] = source_state
            else:
                if source_state not in (None, "present"):
                    raise SmokeManifestError(
                        f"stage line {line_number} is not population-present"
                    )
                normalized["sourceState"] = "present"
            rows[tile.packed] = (tile, normalized)
    actual_sha256 = digest.hexdigest()
    if actual_sha256 != expected_sha256.lower():
        raise SmokeManifestError(
            f"{role} SHA-256 mismatch: expected {expected_sha256.lower()}, got {actual_sha256}"
        )
    if not rows:
        raise SmokeManifestError(f"{role} input is empty")
    return rows


def _canonical_row(tile: TileKey, fields: Mapping[str, object]) -> dict[str, object]:
    return {**fields, "x": tile.x, "y": tile.y, "z": tile.z}


def build_acquisition_smoke(
    *,
    fixture_path: Path,
    expected_fixture_sha256: str,
    stage_a_path: Path,
    expected_stage_a_sha256: str,
    output_dir: Path,
    first_count: int = 64,
) -> dict[str, object]:
    if not 1 <= first_count <= 4096:
        raise SmokeManifestError(f"first_count must be between 1 and 4096: {first_count}")
    fixture_rows = _read_rows(Path(fixture_path), expected_fixture_sha256, "fixture")
    stage_rows = _read_rows(Path(stage_a_path), expected_stage_a_sha256, "stage")
    selected_stage = {
        packed: stage_rows[packed] for packed in sorted(stage_rows)[:first_count]
    }
    if len(selected_stage) != first_count:
        raise SmokeManifestError(
            f"stage input has only {len(selected_stage)} rows, fewer than requested {first_count}"
        )

    output_rows: dict[int, tuple[TileKey, dict[str, object]]] = {}
    fixture_present_count = 0
    fixture_known_empty_count = 0
    for packed, (tile, fixture) in fixture_rows.items():
        state = fixture["sourceState"]
        fixture_present_count += state == "present"
        fixture_known_empty_count += state == "known_empty"
        output_rows[packed] = (
            tile,
            {
                "fixtureNames": fixture["fixtureNames"],
                "selection": "fixture",
                "sourceState": state,
                "sources": ["fixtures"],
            },
        )

    overlap_count = 0
    for packed, (tile, _) in selected_stage.items():
        existing = output_rows.get(packed)
        if existing is None:
            output_rows[packed] = (
                tile,
                {
                    "selection": "smoke",
                    "sourceState": "present",
                    "sources": ["stage-a"],
                },
            )
            continue
        overlap_count += 1
        if existing[1]["sourceState"] != "present":
            raise SmokeManifestError(
                f"stage present tile contradicts known_empty fixture {tile.z}/{tile.x}/{tile.y}"
            )
        existing[1]["selection"] = "fixture+smoke"
        existing[1]["sources"] = ["fixtures", "stage-a"]

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    sample_path = output / "sample.jsonl"
    summary_path = output / "summary.json"
    sample_temp: Path | None = None
    summary_temp: Path | None = None
    try:
        with _atomic_text(sample_path) as sample:
            sample_temp = Path(sample.name)
            for packed in sorted(output_rows):
                tile, fields = output_rows[packed]
                sample.write(
                    json.dumps(
                        _canonical_row(tile, fields),
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                    + "\n"
                )
            sample.flush()
            os.fsync(sample.fileno())
        sample_bytes = sample_temp.stat().st_size
        sample_sha256 = hashlib.sha256(sample_temp.read_bytes()).hexdigest()
        fetchable_count = sum(
            fields["sourceState"] == "present" for _, fields in output_rows.values()
        )
        summary_document: dict[str, object] = {
            "schemaVersion": 1,
            "fixtureInputSha256": expected_fixture_sha256.lower(),
            "stageAInputSha256": expected_stage_a_sha256.lower(),
            "firstCount": first_count,
            "fixtureRowCount": len(fixture_rows),
            "fixturePresentCount": fixture_present_count,
            "fixtureKnownEmptyCount": fixture_known_empty_count,
            "stageASelectedCount": len(selected_stage),
            "overlapCount": overlap_count,
            "rowCount": len(output_rows),
            "fetchableCount": fetchable_count,
            "knownEmptyCount": len(output_rows) - fetchable_count,
            "sampleBytes": sample_bytes,
            "sampleSha256": sample_sha256,
        }
        with _atomic_text(summary_path) as summary:
            summary_temp = Path(summary.name)
            json.dump(summary_document, summary, indent=2, sort_keys=True)
            summary.write("\n")
            summary.flush()
            os.fsync(summary.fileno())
        _commit_output_set(
            ((sample_temp, sample_path), (summary_temp, summary_path))
        )
        sample_temp = summary_temp = None
        return summary_document
    finally:
        for path in (sample_temp, summary_temp):
            if path is not None:
                path.unlink(missing_ok=True)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Compose the source-honest Experiment 8 fixture + Stage A smoke manifest."
    )
    parser.add_argument("--fixtures", required=True, type=Path)
    parser.add_argument("--expected-fixture-sha256", required=True)
    parser.add_argument("--stage-a", required=True, type=Path)
    parser.add_argument("--expected-stage-a-sha256", required=True)
    parser.add_argument("--first-count", type=int, default=64)
    parser.add_argument("--out", required=True, type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        build_acquisition_smoke(
            fixture_path=arguments.fixtures,
            expected_fixture_sha256=arguments.expected_fixture_sha256,
            stage_a_path=arguments.stage_a,
            expected_stage_a_sha256=arguments.expected_stage_a_sha256,
            output_dir=arguments.out,
            first_count=arguments.first_count,
        )
    except (OSError, SmokeManifestError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
