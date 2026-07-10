from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import tools.experiment8.source_sizes as source_sizes
from tools.experiment8.model import TileKey
from tools.experiment8.source_sizes import (
    SourceSizeError,
    SourceSizeRecord,
    build_source_size_catalog,
    main,
    source_size_tail_key,
)


POPULATION_HEADER = "serviceId\tserviceName\tz\tx\ty"
LEGACY_HEADER = (
    "tileIndex\tz\tx\ty\tsourceSha256\tdecodedSha256\tsourceBytes\tdecodedBytes\t"
    "layerCount\tfeatureCount\tfeaturesWithGeometry\tgeometryCommands\tcoordinatePairs\t"
    "chunkPath\tpayloadOffset\tpayloadLength\tclassOffset\tclassLength"
)
MODERN_HEADER = (
    "tileIndex\tz\tx\ty\tsourceSha256\tdecodedSha256\tsourceBytes\tdecodedBytes\t"
    "layerCount\tfeatureCount\tfeaturesWithGeometry\tgeometryCommands\tcoordinatePairs\t"
    "chunkPath\tpayloadUncompressedOffset\tpayloadUncompressedLength\t"
    "classUncompressedOffset\tclassUncompressedLength\tpayloadStorageOffset\t"
    "payloadStorageLength\tclassStorageOffset\tclassStorageLength\tstorageCodec"
)
OUTPUT_HEADER = "z\tx\ty\tsourceSha256\tsourceBytes\tdecodedBytes\tfeatureCount\n"


def _sha(character: str) -> str:
    return character * 64


def _row(
    tile: TileKey,
    *,
    header: str = MODERN_HEADER,
    source_sha256: str | None = None,
    source_bytes: int | str = 100,
    decoded_bytes: int | str = 200,
    feature_count: int | str = 3,
    tile_index: int | str = 999,
    overrides: dict[str, str] | None = None,
) -> list[str]:
    values = {
        "tileIndex": str(tile_index),
        "z": str(tile.z),
        "x": str(tile.x),
        "y": str(tile.y),
        "sourceSha256": source_sha256 or _sha("a"),
        "decodedSha256": _sha("d"),
        "sourceBytes": str(source_bytes),
        "decodedBytes": str(decoded_bytes),
        "layerCount": "2",
        "featureCount": str(feature_count),
        "featuresWithGeometry": "2",
        "geometryCommands": "8",
        "coordinatePairs": "4",
        "chunkPath": "chunks/chunk-00000.far6",
        "payloadOffset": "0",
        "payloadLength": "200",
        "classOffset": "200",
        "classLength": "20",
        "payloadUncompressedOffset": "0",
        "payloadUncompressedLength": "200",
        "classUncompressedOffset": "200",
        "classUncompressedLength": "20",
        "payloadStorageOffset": "0",
        "payloadStorageLength": "100",
        "classStorageOffset": "100",
        "classStorageLength": "10",
        "storageCodec": "deflate",
    }
    values.update(overrides or {})
    return [values[column] for column in header.split("\t")]


def _write_population(path: Path, tiles: list[TileKey]) -> None:
    rows = [POPULATION_HEADER]
    rows.extend(f"10\tfixture\t{tile.z}\t{tile.x}\t{tile.y}" for tile in tiles)
    path.write_bytes(("\n".join(rows) + "\n").encode("utf-8"))


def _write_index(
    root: Path,
    shard: str,
    rows: list[list[str]],
    *,
    header: str = MODERN_HEADER,
    newline: bytes = b"\n",
) -> Path:
    path = root / shard / "package" / "tile-index.tsv"
    path.parent.mkdir(parents=True, exist_ok=True)
    encoded_rows = [header.encode("utf-8")]
    encoded_rows.extend("\t".join(row).encode("utf-8") for row in rows)
    path.write_bytes(newline.join(encoded_rows) + newline)
    return path


class SourceSizeCatalogTests(unittest.TestCase):
    def test_merges_identical_duplicate_rows_from_two_roots(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            population = base / "population.tsv"
            tile = TileKey(2, 1, 3)
            _write_population(population, [tile])
            d_root = base / "D"
            e_root = base / "E"
            first = _write_index(d_root, "same-shard", [_row(tile)])
            second = _write_index(e_root, "same-shard", [_row(tile)])
            output = base / "out"

            summary = build_source_size_catalog(population, [d_root, e_root], output)

            tsv_path = output / "source-sizes.tsv"
            document = json.loads(
                (output / "source-sizes-summary.json").read_text(encoding="utf-8")
            )
            self.assertEqual(summary.physical_row_count, 2)
            self.assertEqual(summary.unique_tile_count, 1)
            self.assertEqual(summary.duplicate_copies, 1)
            self.assertEqual(summary.counts_by_zoom, {2: 1})
            self.assertEqual(document["physicalRowCount"], 2)
            self.assertEqual(document["uniqueTileCount"], 1)
            self.assertEqual(document["duplicateCopies"], 1)
            self.assertEqual(
                document["populationSha256"],
                hashlib.sha256(population.read_bytes()).hexdigest(),
            )
            self.assertEqual(document["inputFileCount"], 2)
            self.assertEqual(document["uniqueInputFileCount"], 1)
            self.assertEqual(document["duplicateInputFileCopies"], 1)
            self.assertEqual(len(document["inputFiles"]), 2)
            self.assertEqual(document["inputFiles"][0]["sha256"], hashlib.sha256(first.read_bytes()).hexdigest())
            self.assertEqual(document["inputFiles"][1]["sha256"], hashlib.sha256(second.read_bytes()).hexdigest())
            self.assertRegex(document["inputFileInventorySha256"], r"^[0-9a-f]{64}$")
            self.assertRegex(document["inputRoots"][0]["inventorySha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(
                document["outputSha256"], hashlib.sha256(tsv_path.read_bytes()).hexdigest()
            )
            self.assertEqual(list(output.glob("*.tmp")), [])

    def test_pairs_identical_same_relative_path_files_and_parses_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            population = base / "population.tsv"
            tiles = [TileKey(1, 0, 0), TileKey(1, 1, 1)]
            _write_population(population, tiles)
            d_root = base / "D"
            e_root = base / "E"
            _write_index(d_root, "same-shard", [_row(tile) for tile in tiles])
            _write_index(e_root, "same-shard", [_row(tile) for tile in tiles])

            with mock.patch.object(
                source_sizes,
                "_parse_tile_index",
                wraps=source_sizes._parse_tile_index,
            ) as parse_index:
                summary = build_source_size_catalog(
                    population,
                    [e_root, d_root],
                    base / "out",
                )

            self.assertEqual(parse_index.call_count, 1)
            self.assertEqual(summary.physical_row_count, 4)
            self.assertEqual(summary.unique_tile_count, 2)
            self.assertEqual(summary.duplicate_copies, 2)

    def test_counts_identical_duplicate_rows_inside_one_index(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            population = base / "population.tsv"
            tile = TileKey(0, 0, 0)
            _write_population(population, [tile])
            root = base / "root"
            _write_index(root, "shard", [_row(tile), _row(tile)])

            summary = build_source_size_catalog(population, [root], base / "out")

            self.assertEqual(summary.physical_row_count, 2)
            self.assertEqual(summary.unique_tile_count, 1)
            self.assertEqual(summary.duplicate_copies, 1)

    def test_rejects_conflicting_duplicate_semantic_fields(self) -> None:
        changes = {
            "sourceSha256": {"source_sha256": _sha("b")},
            "sourceBytes": {"source_bytes": 101},
            "decodedBytes": {"decoded_bytes": 201},
            "featureCount": {"feature_count": 4},
        }
        for field, changed in changes.items():
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                tile = TileKey(1, 1, 0)
                population = base / "population.tsv"
                _write_population(population, [tile])
                first = base / "first"
                second = base / "second"
                _write_index(first, "a", [_row(tile)])
                _write_index(second, "b", [_row(tile, **changed)])
                output = base / "out"

                with self.assertRaisesRegex(SourceSizeError, field):
                    build_source_size_catalog(population, [first, second], output)

                self.assertFalse((output / "source-sizes-summary.json").exists())
                self.assertFalse((output / "source-sizes.tsv").exists())

    def test_reports_population_coordinates_missing_from_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            present = [TileKey(2, 3, 1), TileKey(1, 0, 1)]
            missing = TileKey(2, 0, 2)
            population = base / "population.tsv"
            _write_population(population, [present[0], missing, present[1]])
            root = base / "root"
            _write_index(root, "shard", [_row(present[0]), _row(present[1])])
            output = base / "out"

            summary = build_source_size_catalog(population, [root], output)
            document = json.loads(
                (output / "source-sizes-summary.json").read_text(encoding="utf-8")
            )

            self.assertEqual(summary.missing_population_tiles, (missing,))
            self.assertEqual(document["missingPopulationCount"], 1)
            self.assertEqual(document["missingPopulationTiles"], ["2/0/2"])
            self.assertNotIn("2\t0\t2\t", (output / "source-sizes.tsv").read_text())

    def test_rejects_unique_catalog_coordinate_outside_locked_population(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            population_tile = TileKey(1, 0, 0)
            extra_tile = TileKey(1, 1, 1)
            population = base / "population.tsv"
            _write_population(population, [population_tile])
            root = base / "root"
            _write_index(root, "shard", [_row(population_tile), _row(extra_tile)])
            output = base / "out"

            with self.assertRaisesRegex(SourceSizeError, "outside population.*1/1/1"):
                build_source_size_catalog(population, [root], output)

            self.assertFalse((output / "source-sizes.tsv").exists())
            self.assertFalse((output / "source-sizes-summary.json").exists())

    def test_tail_order_is_source_bytes_then_packed_key(self) -> None:
        records = [
            SourceSizeRecord(TileKey(2, 3, 1), _sha("a"), 100, 200, 1),
            SourceSizeRecord(TileKey(1, 1, 0), _sha("b"), 200, 300, 1),
            SourceSizeRecord(TileKey(1, 0, 1), _sha("c"), 200, 300, 1),
        ]

        ordered = sorted(records, key=source_size_tail_key)

        self.assertEqual(
            [record.tile for record in ordered],
            [TileKey(1, 0, 1), TileKey(1, 1, 0), TileKey(2, 3, 1)],
        )

    def test_accepts_exact_legacy_and_modern_headers(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            legacy_tile = TileKey(1, 1, 1)
            modern_tile = TileKey(2, 0, 3)
            population = base / "population.tsv"
            _write_population(population, [legacy_tile, modern_tile])
            root = base / "root"
            _write_index(root, "legacy", [_row(legacy_tile, header=LEGACY_HEADER)], header=LEGACY_HEADER)
            _write_index(root, "modern", [_row(modern_tile)], header=MODERN_HEADER)

            summary = build_source_size_catalog(population, [root], base / "out")

            self.assertEqual(summary.unique_tile_count, 2)

    def test_accepts_crlf_tsv_line_endings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            population = base / "population.tsv"
            population.write_bytes(
                (POPULATION_HEADER + "\r\n10\tfixture\t0\t0\t0\r\n").encode("utf-8")
            )
            root = base / "root"
            _write_index(root, "shard", [_row(tile)], newline=b"\r\n")

            summary = build_source_size_catalog(population, [root], base / "out")

            self.assertEqual(summary.unique_tile_count, 1)

    def test_does_not_trust_local_tile_index_and_sorts_by_packed_key(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            high = TileKey(3, 7, 7)
            low = TileKey(1, 0, 0)
            population = base / "population.tsv"
            _write_population(population, [high, low])
            root = base / "root"
            _write_index(
                root,
                "shard",
                [_row(high, tile_index=0), _row(low, tile_index=987654321)],
            )
            output = base / "out"

            build_source_size_catalog(population, [root], output)

            lines = (output / "source-sizes.tsv").read_text(encoding="utf-8").splitlines()
            self.assertEqual(lines[0] + "\n", OUTPUT_HEADER)
            self.assertEqual([line.split("\t")[:3] for line in lines[1:]], [["1", "0", "0"], ["3", "7", "7"]])

    def test_rejects_bad_schema_hash_numeric_coordinate_and_shape(self) -> None:
        cases: list[tuple[str, str, bytes, list[str]]] = []
        tile = TileKey(1, 0, 0)
        swapped = MODERN_HEADER.replace("sourceBytes\tdecodedBytes", "decodedBytes\tsourceBytes")
        cases.append(("header", swapped, b"\n", _row(tile)))
        cases.append(("SHA-256", MODERN_HEADER, b"\n", _row(tile, source_sha256="g" * 64)))
        cases.append(("decoded SHA-256", MODERN_HEADER, b"\n", _row(tile, overrides={"decodedSha256": "d" * 63})))
        cases.append(("nonnegative", MODERN_HEADER, b"\n", _row(tile, overrides={"layerCount": "-1"})))
        cases.append(("decimal", MODERN_HEADER, b"\n", _row(tile, overrides={"payloadStorageLength": "+1"})))
        cases.append(("tile", MODERN_HEADER, b"\n", _row(tile, overrides={"x": "2"})))
        cases.append(("fields", MODERN_HEADER, b"\n", _row(tile)[:-1]))
        for label, header, newline, row in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as directory:
                base = Path(directory)
                population = base / "population.tsv"
                _write_population(population, [tile])
                root = base / "root"
                _write_index(root, "shard", [row], header=header, newline=newline)

                with self.assertRaises(SourceSizeError):
                    build_source_size_catalog(population, [root], base / "out")

    def test_scans_only_package_tile_indexes_and_never_opens_far6(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            population = base / "population.tsv"
            _write_population(population, [tile])
            root = base / "root"
            _write_index(root, "valid", [_row(tile)])
            decoy = root / "not-a-package" / "tile-index.tsv"
            decoy.parent.mkdir(parents=True)
            decoy.write_text("not an index\n", encoding="utf-8")
            far6 = root / "valid" / "package" / "chunks" / "chunk-00000.far6"
            far6.parent.mkdir(parents=True)
            far6.write_bytes(b"must never be read")
            real_open = Path.open

            def guarded_open(path: Path, *args: object, **kwargs: object):
                if path.suffix.lower() == ".far6":
                    raise AssertionError("FAR6 payload was opened")
                return real_open(path, *args, **kwargs)

            with mock.patch("pathlib.Path.open", guarded_open):
                summary = build_source_size_catalog(population, [root], base / "out")

            self.assertEqual(summary.unique_tile_count, 1)

    def test_root_argument_order_does_not_change_outputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            low = TileKey(1, 0, 0)
            high = TileKey(1, 1, 1)
            population = base / "population.tsv"
            _write_population(population, [high, low])
            first = base / "A"
            second = base / "B"
            _write_index(first, "one", [_row(high, source_sha256=_sha("b"))])
            _write_index(second, "two", [_row(low, source_sha256=_sha("a"))])

            build_source_size_catalog(population, [first, second], base / "out-one")
            build_source_size_catalog(population, [second, first], base / "out-two")

            self.assertEqual(
                (base / "out-one" / "source-sizes.tsv").read_bytes(),
                (base / "out-two" / "source-sizes.tsv").read_bytes(),
            )
            self.assertEqual(
                (base / "out-one" / "source-sizes-summary.json").read_bytes(),
                (base / "out-two" / "source-sizes-summary.json").read_bytes(),
            )

    def test_summary_is_absent_if_second_atomic_replace_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            population = base / "population.tsv"
            _write_population(population, [tile])
            root = base / "root"
            _write_index(root, "shard", [_row(tile)])
            output = base / "out"
            output.mkdir()
            (output / "source-sizes.tsv").write_text("old tsv\n", encoding="utf-8")
            (output / "source-sizes-summary.json").write_text("{\"old\":true}\n", encoding="utf-8")
            real_replace = __import__("os").replace
            calls = 0

            def fail_second_replace(source: object, destination: object) -> None:
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("injected summary replace failure")
                real_replace(source, destination)

            with mock.patch("tools.experiment8.source_sizes.os.replace", side_effect=fail_second_replace):
                with self.assertRaisesRegex(OSError, "injected summary replace failure"):
                    build_source_size_catalog(population, [root], output)

            self.assertFalse((output / "source-sizes-summary.json").exists())
            self.assertEqual(list(output.glob("*.tmp")), [])

    def test_module_cli_builds_catalog_and_returns_nonzero_on_conflict(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            tile = TileKey(0, 0, 0)
            population = base / "population.tsv"
            _write_population(population, [tile])
            first = base / "first"
            second = base / "second"
            _write_index(first, "shard", [_row(tile)])
            output = base / "out"
            command = [
                sys.executable,
                "-m",
                "tools.experiment8.source_sizes",
                "--population",
                str(population),
                "--shard-root",
                str(first),
                "--out",
                str(output),
            ]

            completed = subprocess.run(command, capture_output=True, text=True, check=False)

            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertTrue((output / "source-sizes-summary.json").is_file())
            _write_index(second, "other", [_row(tile, source_sha256=_sha("b"))])
            conflict_output = base / "conflict-out"
            exit_code = main(
                [
                    "--population",
                    str(population),
                    "--shard-root",
                    str(first),
                    "--shard-root",
                    str(second),
                    "--out",
                    str(conflict_output),
                ]
            )
            self.assertNotEqual(exit_code, 0)
            self.assertFalse((conflict_output / "source-sizes-summary.json").exists())


if __name__ == "__main__":
    unittest.main()
