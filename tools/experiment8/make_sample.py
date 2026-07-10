from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Sequence

from .sample import SampleError, build_sample_manifest


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Build a deterministic Experiment 8 Stage A or Stage B sample manifest."
    )
    parser.add_argument("--verified-source-lock", required=True, type=Path)
    parser.add_argument("--expected-verified-source-lock-sha256", required=True)
    parser.add_argument("--population", required=True, type=Path)
    parser.add_argument("--source-sizes", required=True, type=Path)
    parser.add_argument("--source-size-summary", required=True, type=Path)
    parser.add_argument("--expected-source-size-summary-sha256", required=True)
    parser.add_argument("--stage", required=True, choices=("a", "b"))
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--sort-chunk-rows", type=int, default=250_000)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        build_sample_manifest(
            verified_source_lock_path=arguments.verified_source_lock,
            expected_verified_source_lock_sha256=arguments.expected_verified_source_lock_sha256,
            population_path=arguments.population,
            source_sizes_path=arguments.source_sizes,
            source_size_summary_path=arguments.source_size_summary,
            expected_source_size_summary_sha256=arguments.expected_source_size_summary_sha256,
            stage=arguments.stage,
            output_dir=arguments.out,
            sort_chunk_rows=arguments.sort_chunk_rows,
        )
    except (OSError, SampleError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
