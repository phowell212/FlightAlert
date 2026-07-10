from __future__ import annotations

import codecs
import contextlib
import io
import json
import math
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools.experiment8.model import TileKey
from tools.experiment8.source_lock import (
    SourceLockError,
    sha256_file,
    verify_source_lock,
)
from tools.experiment8.verify_source import main as verify_source_main


EXPECTED_HEADER = "serviceId\tserviceName\tz\tx\ty\n"


class TileKeyTests(unittest.TestCase):
    def test_pack_round_trip(self) -> None:
        key = TileKey(16, 63809, 42195)

        self.assertEqual(TileKey.from_packed(key.packed), key)

    def test_rejects_coordinate_outside_zoom(self) -> None:
        with self.assertRaisesRegex(ValueError, "tile out of range"):
            TileKey(5, 32, 0)

    def test_rejects_zoom_outside_supported_packing_range(self) -> None:
        with self.assertRaisesRegex(ValueError, "zoom out of range"):
            TileKey(30, 0, 0)

    def test_rejects_packed_value_with_unsupported_zoom(self) -> None:
        with self.assertRaisesRegex(ValueError, "zoom out of range"):
            TileKey.from_packed(30 << 58)

    def test_center_lon_lat_is_finite(self) -> None:
        lon, lat = TileKey(0, 0, 0).center_lon_lat()

        self.assertTrue(math.isfinite(lon))
        self.assertTrue(math.isfinite(lat))
        self.assertAlmostEqual(lon, 0.0)
        self.assertAlmostEqual(lat, 0.0)


class SourceLockTests(unittest.TestCase):
    def _write_fixture(
        self,
        root: Path,
        *,
        population_text: str | None = None,
    ) -> tuple[Path, Path, Path]:
        style_path = root / "style.json"
        metadata_path = root / "metadata.pjson"
        population_path = root / "population.tsv"
        style_path.write_bytes(b'{"version":8}\n')
        metadata_path.write_bytes(b'{"name":"fixture"}\n')
        population_path.write_text(
            population_text
            or (
                EXPECTED_HEADER
                + "10\tfixture\t0\t0\t0\n"
                + "10\tfixture\t1\t0\t0\n"
                + "10\tfixture\t1\t1\t0\n"
                + "10\tfixture\t1\t0\t1\n"
                + "10\tfixture\t1\t1\t1\n"
            ),
            encoding="utf-8",
            newline="",
        )
        return style_path, metadata_path, population_path

    def _verify(
        self,
        style_path: Path,
        metadata_path: Path,
        population_path: Path,
        **overrides: object,
    ):
        arguments: dict[str, object] = {
            "source_name": "Fixture VectorTileServer",
            "service_url": "https://example.test/VectorTileServer",
            "style_path": style_path,
            "metadata_path": metadata_path,
            "population_path": population_path,
            "expected_style_sha256": sha256_file(style_path),
            "expected_metadata_sha256": sha256_file(metadata_path),
            "expected_population_sha256": sha256_file(population_path),
            "expected_counts_by_zoom": {0: 1, 1: 4},
        }
        arguments.update(overrides)
        return verify_source_lock(**arguments)

    def test_verifies_expected_hashes_and_population_counts(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))
            expected_style_hash = sha256_file(paths[0])
            expected_metadata_hash = sha256_file(paths[1])
            expected_population_hash = sha256_file(paths[2])

            lock = self._verify(*paths)

        self.assertEqual(lock.population.row_count, 5)
        self.assertEqual(dict(lock.population.counts_by_zoom), {0: 1, 1: 4})
        self.assertEqual(lock.style_sha256, expected_style_hash)
        self.assertEqual(lock.metadata_sha256, expected_metadata_hash)
        self.assertEqual(lock.population.sha256, expected_population_hash)
        with self.assertRaises(TypeError):
            lock.population.counts_by_zoom[0] = 99  # type: ignore[index]

    def test_rejects_duplicate_population_coordinate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            duplicate = (
                EXPECTED_HEADER
                + "10\tfixture\t0\t0\t0\n"
                + "10\tfixture\t0\t0\t0\n"
            )
            paths = self._write_fixture(root, population_text=duplicate)

            with self.assertRaisesRegex(SourceLockError, "duplicate population tile 0/0/0"):
                self._verify(
                    *paths,
                    expected_population_sha256=sha256_file(paths[2]),
                    expected_counts_by_zoom={0: 2},
                )

    def test_rejects_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))

            with self.assertRaisesRegex(SourceLockError, "style SHA-256 mismatch"):
                self._verify(*paths, expected_style_sha256="0" * 64)

    def test_rejects_population_hash_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))

            with self.assertRaisesRegex(SourceLockError, "population SHA-256 mismatch"):
                self._verify(*paths, expected_population_sha256="f" * 64)

    def test_requires_exact_tab_separated_header(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = self._write_fixture(
                root,
                population_text="serviceId serviceName z x y\n10 fixture 0 0 0\n",
            )

            with self.assertRaisesRegex(SourceLockError, "population header mismatch"):
                self._verify(
                    *paths,
                    expected_population_sha256=sha256_file(paths[2]),
                    expected_counts_by_zoom={0: 1},
                )

    def test_rejects_malformed_or_out_of_range_population_coordinate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = self._write_fixture(
                root,
                population_text=EXPECTED_HEADER + "10\tfixture\t1\t2\t0\n",
            )

            with self.assertRaisesRegex(SourceLockError, "invalid population tile at line 2"):
                self._verify(
                    *paths,
                    expected_population_sha256=sha256_file(paths[2]),
                    expected_counts_by_zoom={1: 1},
                )

    def test_rejects_per_zoom_count_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))

            with self.assertRaisesRegex(SourceLockError, "population counts mismatch"):
                self._verify(*paths, expected_counts_by_zoom={0: 1, 1: 3})


class VerifySourceCliTests(unittest.TestCase):
    def _prepare_lock_directory(self, root: Path) -> tuple[Path, Path]:
        lock_dir = root / "source-lock"
        lock_dir.mkdir()
        style_path = lock_dir / "World_Basemap_v2-root-style.json"
        metadata_path = lock_dir / "World_Basemap_v2-service-metadata.pjson"
        population_path = root / "present-vector-tiles.tsv"
        style_path.write_bytes(b'{"version":8}\n')
        metadata_path.write_bytes(b'{"name":"fixture"}\n')
        population_path.write_text(
            EXPECTED_HEADER + "10\tfixture\t0\t0\t0\n",
            encoding="utf-8",
            newline="",
        )
        descriptor = {
            "source": "Fixture VectorTileServer",
            "serviceUrl": "https://example.test/VectorTileServer",
            "styleSha256": sha256_file(style_path),
            "metadataSha256": sha256_file(metadata_path),
        }
        (lock_dir / "fixture-source-lock.json").write_text(
            json.dumps(descriptor),
            encoding="utf-8",
        )
        return lock_dir, population_path

    def _common_cli_arguments(
        self,
        lock_dir: Path,
        population_path: Path,
        out_path: Path,
    ) -> list[str]:
        return [
            "--lock-dir",
            str(lock_dir),
            "--population",
            str(population_path),
            "--expected-style-sha256",
            sha256_file(lock_dir / "World_Basemap_v2-root-style.json"),
            "--expected-metadata-sha256",
            sha256_file(lock_dir / "World_Basemap_v2-service-metadata.pjson"),
            "--expected-population-sha256",
            sha256_file(population_path),
            "--out",
            str(out_path),
        ]

    def _run_cli_process(self, arguments: list[str]) -> subprocess.CompletedProcess[str]:
        environment = os.environ.copy()
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        return subprocess.run(
            [sys.executable, "-m", "tools.experiment8.verify_source", *arguments],
            cwd=Path(__file__).resolve().parents[3],
            env=environment,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )

    def test_cli_process_accepts_utf8_expected_counts_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_dir, population_path = self._prepare_lock_directory(root)
            counts_path = root / "expected-counts.json"
            counts_path.write_bytes(codecs.BOM_UTF8 + b'{"0":1}\r\n')
            out_path = root / "verified-source-lock.json"
            arguments = self._common_cli_arguments(
                lock_dir,
                population_path,
                out_path,
            )
            arguments.extend(["--expected-counts-file", str(counts_path)])

            completed = self._run_cli_process(arguments)

            self.assertEqual(completed.returncode, 0, completed.stderr)
            output = json.loads(out_path.read_text(encoding="utf-8"))

        self.assertEqual(output["population"]["countsByZoom"], {"0": 1})

    def test_cli_parser_rejects_both_expected_counts_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_dir, population_path = self._prepare_lock_directory(root)
            counts_path = root / "expected-counts.json"
            counts_path.write_text('{"0":1}\n', encoding="utf-8", newline="")
            out_path = root / "verified-source-lock.json"
            arguments = self._common_cli_arguments(
                lock_dir,
                population_path,
                out_path,
            )
            arguments.extend(
                [
                    "--expected-counts-json",
                    '{"0":1}',
                    "--expected-counts-file",
                    str(counts_path),
                ]
            )

            completed = self._run_cli_process(arguments)

        self.assertEqual(completed.returncode, 2)
        self.assertIn("not allowed with argument", completed.stderr)
        self.assertFalse(out_path.exists())

    def test_replace_failure_preserves_destination_and_cleans_temp_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_dir, population_path = self._prepare_lock_directory(root)
            out_path = root / "verified-source-lock.json"
            original_bytes = b'{"previous":"verified"}\n'
            out_path.write_bytes(original_bytes)
            arguments = self._common_cli_arguments(
                lock_dir,
                population_path,
                out_path,
            )
            arguments.extend(["--expected-counts-json", '{"0":1}'])
            stderr = io.StringIO()

            with (
                mock.patch(
                    "tools.experiment8.verify_source.os.replace",
                    side_effect=OSError("injected replace failure"),
                ),
                contextlib.redirect_stderr(stderr),
            ):
                exit_code = verify_source_main(arguments)

            remaining_temporary_files = list(root.glob("*.tmp"))
            preserved_bytes = out_path.read_bytes()

        self.assertNotEqual(exit_code, 0)
        self.assertIn("injected replace failure", stderr.getvalue())
        self.assertEqual(preserved_bytes, original_bytes)
        self.assertEqual(remaining_temporary_files, [])

    def test_writes_verified_descriptor_atomically(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_dir, population_path = self._prepare_lock_directory(root)
            out_path = root / "evidence" / "verified-source-lock.json"
            style_hash = sha256_file(lock_dir / "World_Basemap_v2-root-style.json")
            metadata_hash = sha256_file(
                lock_dir / "World_Basemap_v2-service-metadata.pjson"
            )

            exit_code = verify_source_main(
                [
                    "--lock-dir",
                    str(lock_dir),
                    "--population",
                    str(population_path),
                    "--expected-style-sha256",
                    style_hash,
                    "--expected-metadata-sha256",
                    metadata_hash,
                    "--expected-population-sha256",
                    sha256_file(population_path),
                    "--expected-counts-json",
                    '{"0":1}',
                    "--out",
                    str(out_path),
                ]
            )

            payload = json.loads(out_path.read_text(encoding="utf-8"))
            temporary_files = list(out_path.parent.glob("*.tmp"))

        self.assertEqual(exit_code, 0)
        self.assertEqual(payload["sourceName"], "Fixture VectorTileServer")
        self.assertEqual(payload["styleSha256"], style_hash)
        self.assertEqual(payload["metadataSha256"], metadata_hash)
        self.assertEqual(payload["population"]["rowCount"], 1)
        self.assertEqual(payload["population"]["countsByZoom"], {"0": 1})
        self.assertEqual(temporary_files, [])

    def test_returns_nonzero_without_output_on_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lock_dir, population_path = self._prepare_lock_directory(root)
            out_path = root / "verified-source-lock.json"
            stderr = io.StringIO()

            with contextlib.redirect_stderr(stderr):
                exit_code = verify_source_main(
                    [
                        "--lock-dir",
                        str(lock_dir),
                        "--population",
                        str(population_path),
                        "--expected-style-sha256",
                        "0" * 64,
                        "--expected-metadata-sha256",
                        sha256_file(
                            lock_dir / "World_Basemap_v2-service-metadata.pjson"
                        ),
                        "--expected-population-sha256",
                        sha256_file(population_path),
                        "--expected-counts-json",
                        '{"0":1}',
                        "--out",
                        str(out_path),
                    ]
                )

        self.assertNotEqual(exit_code, 0)
        self.assertIn("mismatch", stderr.getvalue())
        self.assertFalse(out_path.exists())


if __name__ == "__main__":
    unittest.main()
