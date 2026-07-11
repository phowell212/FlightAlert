from __future__ import annotations

import io
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from tools.experiment8 import fetch_sample
from tools.experiment8.acquire import AcquisitionError


class FetchSampleCliTests(unittest.TestCase):
    @staticmethod
    def _arguments(*extra: str) -> list[str]:
        return [
            "--verified-source-lock",
            "verified-source-lock.json",
            "--expected-verified-source-lock-sha256",
            "a" * 64,
            "--sample",
            "sample.jsonl",
            "--expected-sample-sha256",
            "b" * 64,
            "--cache",
            "cache",
            "--out",
            "out",
            *extra,
        ]

    @staticmethod
    def _summary(*, failed_count: int = 0, row_count: int = 4) -> SimpleNamespace:
        return SimpleNamespace(failed_count=failed_count, row_count=row_count)

    def test_all_ready_returns_zero_and_passes_cli_options(self) -> None:
        cache = object()
        with mock.patch.object(fetch_sample, "PbfCache", return_value=cache) as cache_type, mock.patch.object(
            fetch_sample,
            "acquire_manifest",
            return_value=self._summary(),
        ) as acquire_manifest:
            result = fetch_sample.main(
                self._arguments(
                    "--max-cache-bytes",
                    "123456",
                    "--min-free-bytes",
                    "654321",
                )
            )

        self.assertEqual(result, 0)
        cache_type.assert_called_once_with(
            root=Path("cache"),
            verified_source_lock_path=Path("verified-source-lock.json"),
            expected_verified_source_lock_sha256="a" * 64,
            timeout_seconds=30.0,
            max_attempts=3,
            max_cache_bytes=123456,
            min_free_bytes=654321,
        )
        acquire_manifest.assert_called_once_with(
            Path("sample.jsonl"),
            "b" * 64,
            cache,
            8,
            Path("out"),
        )

    def test_failed_acquisitions_return_three(self) -> None:
        with mock.patch.object(fetch_sample, "PbfCache"), mock.patch.object(
            fetch_sample,
            "acquire_manifest",
            return_value=self._summary(failed_count=2, row_count=7),
        ), redirect_stderr(io.StringIO()) as stderr:
            result = fetch_sample.main(self._arguments())

        self.assertEqual(result, 3)
        self.assertIn("2 of 7 acquisitions failed", stderr.getvalue())

    def test_acquisition_error_returns_two(self) -> None:
        with mock.patch.object(fetch_sample, "PbfCache"), mock.patch.object(
            fetch_sample,
            "acquire_manifest",
            side_effect=AcquisitionError("sample rejected"),
        ), redirect_stderr(io.StringIO()) as stderr:
            result = fetch_sample.main(self._arguments())

        self.assertEqual(result, 2)
        self.assertIn("sample rejected", stderr.getvalue())

    def test_worker_bounds_are_forwarded_to_acquire_manifest(self) -> None:
        seen_workers: list[int] = []

        def acquire_with_worker_contract(
            sample_path: Path,
            expected_sample_sha256: str,
            cache: object,
            workers: int,
            output_directory: Path,
        ) -> SimpleNamespace:
            del sample_path, expected_sample_sha256, cache, output_directory
            seen_workers.append(workers)
            if not 1 <= workers <= 16:
                raise AcquisitionError(f"workers must be between 1 and 16: {workers}")
            return self._summary()

        with mock.patch.object(fetch_sample, "PbfCache"), mock.patch.object(
            fetch_sample,
            "acquire_manifest",
            side_effect=acquire_with_worker_contract,
        ):
            for workers, expected in ((0, 2), (17, 2), (16, 0)):
                with self.subTest(workers=workers), redirect_stderr(io.StringIO()):
                    result = fetch_sample.main(self._arguments("--workers", str(workers)))
                    self.assertEqual(result, expected)

        self.assertEqual(seen_workers, [0, 17, 16])


if __name__ == "__main__":
    unittest.main()
