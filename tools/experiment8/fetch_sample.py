from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

from .acquire import AcquisitionError, PbfCache, acquire_manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Acquire a hash-pinned Experiment 8 sample into the resumable PBF cache."
    )
    parser.add_argument("--verified-source-lock", required=True, type=Path)
    parser.add_argument("--expected-verified-source-lock-sha256", required=True)
    parser.add_argument("--sample", required=True, type=Path)
    parser.add_argument("--expected-sample-sha256", required=True)
    parser.add_argument("--cache", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--max-cache-bytes", type=int, default=23_500_000_000)
    parser.add_argument("--min-free-bytes", type=int, default=5_000_000_000)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        cache = PbfCache(
            root=arguments.cache,
            verified_source_lock_path=arguments.verified_source_lock,
            expected_verified_source_lock_sha256=arguments.expected_verified_source_lock_sha256,
            timeout_seconds=arguments.timeout_seconds,
            max_attempts=arguments.max_attempts,
            max_cache_bytes=arguments.max_cache_bytes,
            min_free_bytes=arguments.min_free_bytes,
        )
        summary = acquire_manifest(
            arguments.sample,
            arguments.expected_sample_sha256,
            cache,
            arguments.workers,
            arguments.out,
        )
    except (OSError, AcquisitionError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    if summary.failed_count:
        print(
            f"error: {summary.failed_count} of {summary.row_count} acquisitions failed",
            file=sys.stderr,
        )
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
