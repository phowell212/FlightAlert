from __future__ import annotations

import hashlib
import json
import math
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import tools.experiment8.sample as sample_module
from tools.experiment8.model import TileKey
from tools.experiment8.sample import (
    LATITUDE_EDGES,
    LONGITUDE_EDGES,
    SampleError,
    build_sample_manifest,
    latitude_band,
    longitude_sector,
    lon_lat_to_tile,
    sample_rank,
)


POPULATION_HEADER = "serviceId\tserviceName\tz\tx\ty"
SIZE_HEADER = "z\tx\ty\tsourceSha256\tsourceBytes\tdecodedBytes\tfeatureCount\n"


def _sha_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _json_lines(path: Path) -> list[dict[str, object]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


def _write_inputs(
    base: Path,
    population_tiles: list[TileKey],
    sizes: dict[TileKey, int],
    *,
    population_name: str = "population.tsv",
) -> tuple[Path, Path, Path, Path]:
    population = base / population_name
    rows = [POPULATION_HEADER]
    rows.extend(f"10\tfixture\t{tile.z}\t{tile.x}\t{tile.y}" for tile in population_tiles)
    population.write_bytes(("\n".join(rows) + "\n").encode("utf-8"))
    population_sha = _sha_file(population)

    source_sizes = base / f"{population.stem}-source-sizes.tsv"
    with source_sizes.open("w", encoding="utf-8", newline="\n") as output:
        output.write(SIZE_HEADER)
        for tile in sorted(sizes, key=lambda item: item.packed):
            output.write(
                f"{tile.z}\t{tile.x}\t{tile.y}\t{'a' * 64}\t{sizes[tile]}\t"
                f"{sizes[tile] * 2}\t1\n"
            )
    size_sha = _sha_file(source_sizes)
    counts: dict[int, int] = {}
    for tile in population_tiles:
        counts[tile.z] = counts.get(tile.z, 0) + 1

    verified_lock = base / f"{population.stem}-verified-lock.json"
    verified_lock.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "sourceLockSha256": "b" * 64,
                "populationPath": str(population),
                "population": {
                    "rowCount": len(population_tiles),
                    "countsByZoom": {str(z): count for z, count in sorted(counts.items())},
                    "sha256": population_sha,
                },
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    missing = sorted(set(population_tiles).difference(sizes), key=lambda tile: tile.packed)
    size_summary = base / f"{population.stem}-source-size-summary.json"
    size_summary.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "populationSha256": population_sha,
                "populationRowCount": len(population_tiles),
                "uniqueTileCount": len(sizes),
                "missingPopulationCount": len(missing),
                "missingPopulationTiles": [f"{tile.z}/{tile.x}/{tile.y}" for tile in missing],
                "outputBytes": source_sizes.stat().st_size,
                "outputSha256": size_sha,
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return verified_lock, population, source_sizes, size_summary


def _build(
    inputs: tuple[Path, Path, Path, Path],
    output: Path,
    *,
    stage: str = "a",
    census_max_z: int = 0,
    random_per_stratum: int = 2,
    tail_per_zoom: int = 1,
    sort_chunk_rows: int = 2,
    fixture_points: tuple[tuple[str, float, float], ...] = (),
    fixture_zooms: tuple[int, ...] = (5,),
):
    lock, population, sizes, summary = inputs
    return build_sample_manifest(
        verified_source_lock_path=lock,
        expected_verified_source_lock_sha256=_sha_file(lock),
        population_path=population,
        source_sizes_path=sizes,
        source_size_summary_path=summary,
        expected_source_size_summary_sha256=_sha_file(summary),
        stage=stage,
        output_dir=output,
        census_max_z=census_max_z,
        random_per_stratum=random_per_stratum,
        tail_per_zoom=tail_per_zoom,
        sort_chunk_rows=sort_chunk_rows,
        fixture_points=fixture_points,
        fixture_zooms=fixture_zooms,
    )


class GeographicContractTests(unittest.TestCase):
    def test_equal_area_band_boundaries_are_stable_and_north_owns_edge(self) -> None:
        self.assertEqual(latitude_band(-90.0), 0)
        self.assertEqual(latitude_band(0.0), 3)
        self.assertEqual(latitude_band(89.0), 5)
        self.assertEqual(latitude_band(90.0), 5)
        for index, edge in enumerate(LATITUDE_EDGES[1:-1], start=1):
            self.assertEqual(latitude_band(math.nextafter(edge, -math.inf)), index - 1)
            self.assertEqual(latitude_band(edge), index)
            self.assertEqual(latitude_band(math.nextafter(edge, math.inf)), index)

    def test_longitude_boundaries_are_stable_and_east_owns_edge(self) -> None:
        self.assertEqual(longitude_sector(-180.0), 0)
        self.assertEqual(longitude_sector(180.0), 7)
        for index, edge in enumerate(LONGITUDE_EDGES[1:-1], start=1):
            self.assertEqual(longitude_sector(math.nextafter(edge, -math.inf)), index - 1)
            self.assertEqual(longitude_sector(edge), index)
            self.assertEqual(longitude_sector(math.nextafter(edge, math.inf)), index)

    def test_rejects_invalid_geographic_values(self) -> None:
        for value in (math.nan, math.inf, -math.inf, -90.0001, 90.0001):
            with self.subTest(latitude=value), self.assertRaises(ValueError):
                latitude_band(value)
        for value in (math.nan, math.inf, -math.inf, -180.0001, 180.0001):
            with self.subTest(longitude=value), self.assertRaises(ValueError):
                longitude_sector(value)

    def test_known_xyz_and_rank_constants(self) -> None:
        self.assertEqual(lon_lat_to_tile(16, -74.0060, 40.7128), TileKey(16, 19295, 24640))
        self.assertEqual(lon_lat_to_tile(16, -0.1278, 51.5074), TileKey(16, 32744, 21792))
        self.assertEqual(lon_lat_to_tile(16, 179.5, 52.0), TileKey(16, 65444, 21647))
        self.assertEqual(
            sample_rank(TileKey(9, 28, 128)).hex(),
            "a7db84874f44e2d3eea9cfac627159d81b1664cf1f8e590c3240084e7014e8ab",
        )
        self.assertEqual(
            sample_rank(TileKey(16, 63809, 42195)).hex(),
            "4e4dc04f4487eba36daa3e5d4da7968673d799c6a9c141c8d32e44d3b35d620c",
        )


class SampleManifestTests(unittest.TestCase):
    def test_census_certainty_tail_and_random_refill_are_exclusive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            census = TileKey(0, 0, 0)
            high = [TileKey(6, x, 20) for x in (10, 11, 12, 13, 14)]
            missing = high[1]
            sizes = {census: 5, high[0]: 1000, high[2]: 10, high[3]: 20, high[4]: 30}
            inputs = _write_inputs(base, [high[4], census, high[1], high[2], high[0], high[3]], sizes)

            result = _build(inputs, base / "out", census_max_z=0, random_per_stratum=2, tail_per_zoom=1)
            rows = _json_lines(base / "out" / "sample.jsonl")
            by_key = {(row["z"], row["x"], row["y"]): row for row in rows}

            self.assertEqual(by_key[(0, 0, 0)]["selection"], "census")
            self.assertEqual(by_key[(6, high[0].x, 20)]["selection"], "tail")
            self.assertEqual(by_key[(6, missing.x, 20)]["selection"], "uncatalogued")
            random_rows = [row for row in rows if row["selection"] == "random"]
            candidates = [high[2], high[3], high[4]]
            expected = sorted(candidates, key=lambda tile: (sample_rank(tile), tile.packed))[:2]
            self.assertEqual(
                [TileKey(int(row["z"]), int(row["x"]), int(row["y"])) for row in random_rows],
                sorted(expected, key=lambda tile: tile.packed),
            )
            self.assertEqual(result.sample_row_count, 5)
            self.assertEqual(sum(result.selection_counts.values()), 5)
            self.assertEqual(len({(row["z"], row["x"], row["y"]) for row in rows}), len(rows))

    def test_stage_a_keys_are_subset_of_b_when_random_becomes_tail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tiles = [TileKey(6, x, 20) for x in (10, 11, 12, 13, 14)]
            ranked = sorted(tiles, key=lambda tile: (sample_rank(tile), tile.packed))
            sizes = {tile: 10 + index for index, tile in enumerate(tiles)}
            sizes[ranked[0]] = 900
            biggest = next(tile for tile in tiles if tile != ranked[0])
            sizes[biggest] = 1000
            inputs = _write_inputs(base, list(reversed(tiles)), sizes)

            _build(inputs, base / "a", stage="a", random_per_stratum=1, tail_per_zoom=1)
            _build(inputs, base / "b", stage="b", random_per_stratum=2, tail_per_zoom=2)
            a_rows = _json_lines(base / "a" / "sample.jsonl")
            b_rows = _json_lines(base / "b" / "sample.jsonl")
            a = {(row["z"], row["x"], row["y"]): row["selection"] for row in a_rows}
            b = {(row["z"], row["x"], row["y"]): row["selection"] for row in b_rows}

            self.assertTrue(set(a).issubset(b))
            self.assertEqual(a[(ranked[0].z, ranked[0].x, ranked[0].y)], "random")
            self.assertEqual(b[(ranked[0].z, ranked[0].x, ranked[0].y)], "tail")

    def test_equal_size_tail_and_synthetic_rank_ties_use_packed_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            low = TileKey(6, 10, 20)
            high = TileKey(6, 11, 20)
            inputs = _write_inputs(base, [high, low], {high: 100, low: 100})

            _build(inputs, base / "tail", random_per_stratum=0, tail_per_zoom=1)
            tail_rows = _json_lines(base / "tail" / "sample.jsonl")
            self.assertEqual(
                (tail_rows[0]["z"], tail_rows[0]["x"], tail_rows[0]["y"]),
                (low.z, low.x, low.y),
            )

            with mock.patch.object(sample_module, "sample_rank", return_value=b"\0" * 32):
                _build(inputs, base / "rank", random_per_stratum=1, tail_per_zoom=0)
            rank_rows = _json_lines(base / "rank" / "sample.jsonl")
            self.assertEqual(
                (rank_rows[0]["z"], rank_rows[0]["x"], rank_rows[0]["y"]),
                (low.z, low.x, low.y),
            )

    def test_shuffled_population_and_chunk_sizes_keep_manifests_identical(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tiles = [TileKey(6, x, 20) for x in (10, 11, 12, 13, 14, 15)]
            sizes = {tile: 100 + index for index, tile in enumerate(tiles)}
            first = _write_inputs(base, tiles, sizes, population_name="first.tsv")
            second = _write_inputs(base, [tiles[3], tiles[0], tiles[5], tiles[1], tiles[4], tiles[2]], sizes, population_name="second.tsv")

            _build(first, base / "one", sort_chunk_rows=2)
            _build(second, base / "two", sort_chunk_rows=5)

            self.assertEqual((base / "one" / "sample.jsonl").read_bytes(), (base / "two" / "sample.jsonl").read_bytes())
            self.assertEqual((base / "one" / "fixtures.jsonl").read_bytes(), (base / "two" / "fixtures.jsonl").read_bytes())

    def test_fixture_manifest_preserves_names_and_known_empty_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            present = lon_lat_to_tile(5, -74.0060, 40.7128)
            inputs = _write_inputs(base, [present], {present: 100})
            points = (
                ("new-york-b", 40.7128, -74.0060),
                ("new-york-a", 40.7128, -74.0060),
                ("london", 51.5074, -0.1278),
            )

            _build(inputs, base / "out", fixture_points=points)
            fixtures = _json_lines(base / "out" / "fixtures.jsonl")

            self.assertEqual(len(fixtures), 2)
            states = {tuple(row["fixtureNames"]): row["sourceState"] for row in fixtures}
            self.assertEqual(states[("new-york-a", "new-york-b")], "present")
            self.assertEqual(states[("london",)], "known_empty")

    def test_outputs_are_canonical_and_strata_reconcile(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tiles = [TileKey(0, 0, 0), TileKey(6, 10, 20), TileKey(6, 11, 20)]
            inputs = _write_inputs(base, tiles, {tile: 100 for tile in tiles})
            _build(inputs, base / "out", census_max_z=0, random_per_stratum=1, tail_per_zoom=1)

            raw = (base / "out" / "sample.jsonl").read_bytes()
            self.assertNotIn(b"generatedAt", raw)
            self.assertNotIn(b"\r", raw)
            for line in raw.splitlines(keepends=True):
                document = json.loads(line)
                expected = (json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
                self.assertEqual(line, expected)
            summary = json.loads((base / "out" / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["sampleRowCount"], sum(summary["selectionCounts"].values()))
            for stratum in summary["strata"]:
                self.assertEqual(
                    stratum["sampleCount"],
                    stratum["certaintyCount"] + stratum["randomSelectedCount"],
                )

    def test_rejects_population_catalog_and_summary_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            inputs = list(_write_inputs(base, [tile], {tile: 10}))
            inputs[1].write_bytes(
                inputs[1].read_bytes().replace(b"10\tfixture\t", b"10\tfixturE\t", 1)
            )
            with self.assertRaisesRegex(SampleError, "population SHA-256 mismatch"):
                _build(tuple(inputs), base / "bad-pop")

            inputs = list(_write_inputs(base, [tile], {tile: 10}, population_name="fresh.tsv"))
            inputs[2].write_bytes(inputs[2].read_bytes().replace(b"\t10\t20\t", b"\t11\t20\t"))
            with self.assertRaisesRegex(SampleError, "source-size SHA-256 mismatch"):
                _build(tuple(inputs), base / "bad-size")

            inputs = list(_write_inputs(base, [tile], {tile: 10}, population_name="third.tsv"))
            document = json.loads(inputs[3].read_text(encoding="utf-8"))
            document["populationSha256"] = "0" * 64
            inputs[3].write_text(json.dumps(document), encoding="utf-8")
            with self.assertRaisesRegex(SampleError, "summary population SHA-256"):
                _build(tuple(inputs), base / "bad-summary")

    def test_rejects_unpinned_verified_lock_and_catalog_summary_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            lock, population, sizes, summary = _write_inputs(base, [tile], {tile: 10})
            with self.assertRaisesRegex(SampleError, "verified source-lock file SHA-256"):
                build_sample_manifest(
                    verified_source_lock_path=lock,
                    expected_verified_source_lock_sha256="0" * 64,
                    population_path=population,
                    source_sizes_path=sizes,
                    source_size_summary_path=summary,
                    expected_source_size_summary_sha256=_sha_file(summary),
                    stage="a",
                    output_dir=base / "bad-lock",
                )
            with self.assertRaisesRegex(SampleError, "source-size summary file SHA-256"):
                build_sample_manifest(
                    verified_source_lock_path=lock,
                    expected_verified_source_lock_sha256=_sha_file(lock),
                    population_path=population,
                    source_sizes_path=sizes,
                    source_size_summary_path=summary,
                    expected_source_size_summary_sha256="0" * 64,
                    stage="a",
                    output_dir=base / "bad-summary-file",
                )

    def test_cleans_output_temps_when_fixture_generation_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            inputs = _write_inputs(base, [tile], {tile: 10})
            output = base / "out"
            with mock.patch.object(
                sample_module,
                "_write_fixtures",
                side_effect=SampleError("injected fixture failure"),
            ):
                with self.assertRaisesRegex(SampleError, "injected fixture failure"):
                    _build(inputs, output)
            self.assertEqual(list(output.glob("*.tmp")), [])
            self.assertFalse((output / "summary.json").exists())

    def test_module_cli_builds_sample(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            lock, population, sizes, summary = _write_inputs(base, [tile], {tile: 10})
            output = base / "out"
            completed = subprocess.run(
                [
                    sys.executable,
                    "-m",
                    "tools.experiment8.make_sample",
                    "--verified-source-lock",
                    str(lock),
                    "--expected-verified-source-lock-sha256",
                    _sha_file(lock),
                    "--population",
                    str(population),
                    "--source-sizes",
                    str(sizes),
                    "--source-size-summary",
                    str(summary),
                    "--expected-source-size-summary-sha256",
                    _sha_file(summary),
                    "--stage",
                    "a",
                    "--out",
                    str(output),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertTrue((output / "summary.json").is_file())


if __name__ == "__main__":
    unittest.main()
