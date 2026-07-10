from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Sequence

from .model import SourceLock
from .source_lock import SourceLockError, sha256_file, verify_source_lock


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify the Experiment 8 vector source lock and tile population."
    )
    parser.add_argument("--lock-dir", required=True, type=Path)
    parser.add_argument("--population", required=True, type=Path)
    parser.add_argument("--expected-style-sha256", required=True)
    parser.add_argument("--expected-metadata-sha256", required=True)
    parser.add_argument("--expected-population-sha256", required=True)
    expected_counts = parser.add_mutually_exclusive_group(required=True)
    expected_counts.add_argument("--expected-counts-json")
    expected_counts.add_argument("--expected-counts-file", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    return parser


def _load_descriptor(lock_dir: Path) -> tuple[Path, dict[str, object]]:
    try:
        resolved_dir = lock_dir.resolve(strict=True)
    except OSError as error:
        raise SourceLockError(f"source lock directory is unavailable: {lock_dir}: {error}") from error
    if not resolved_dir.is_dir():
        raise SourceLockError(f"source lock path is not a directory: {resolved_dir}")
    descriptors = sorted(resolved_dir.glob("*-source-lock.json"))
    if len(descriptors) != 1:
        raise SourceLockError(
            "source lock directory must contain exactly one '*-source-lock.json' descriptor; "
            f"found {len(descriptors)}"
        )
    descriptor_path = descriptors[0]
    try:
        document = json.loads(descriptor_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceLockError(
            f"source lock descriptor is unreadable: {descriptor_path}: {error}"
        ) from error
    if not isinstance(document, dict):
        raise SourceLockError("source lock descriptor root must be a JSON object")
    return descriptor_path, document


def _descriptor_text(document: dict[str, object], key: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or not value.strip():
        raise SourceLockError(f"source lock descriptor field {key!r} is missing or empty")
    return value


def _descriptor_hash(
    document: dict[str, object],
    key: str,
    expected: str,
    label: str,
) -> None:
    value = _descriptor_text(document, key).lower()
    if value != expected.lower():
        raise SourceLockError(
            f"source descriptor {label} SHA-256 mismatch: expected {expected.lower()}, got {value}"
        )


def _find_file_by_hash(lock_dir: Path, expected_hash: str, label: str) -> Path:
    matches: list[Path] = []
    for candidate in sorted(lock_dir.iterdir()):
        if candidate.is_file() and sha256_file(candidate) == expected_hash.lower():
            matches.append(candidate)
    if len(matches) != 1:
        raise SourceLockError(
            f"source lock directory must contain exactly one {label} file with SHA-256 "
            f"{expected_hash.lower()}; found {len(matches)}"
        )
    return matches[0]


def _parse_expected_counts(value: str) -> dict[int, int]:
    try:
        document = json.loads(value)
    except json.JSONDecodeError as error:
        raise SourceLockError(f"expected counts JSON is invalid: {error}") from error
    if not isinstance(document, dict):
        raise SourceLockError("expected counts JSON must be an object")
    counts: dict[int, int] = {}
    for zoom_text, count in document.items():
        try:
            zoom = int(zoom_text)
        except (TypeError, ValueError) as error:
            raise SourceLockError(
                f"expected counts JSON has an invalid zoom key: {zoom_text!r}"
            ) from error
        if str(zoom) != str(zoom_text):
            raise SourceLockError(
                f"expected counts JSON zoom key is not canonical: {zoom_text!r}"
            )
        if isinstance(count, bool) or not isinstance(count, int):
            raise SourceLockError(
                f"expected counts JSON value for zoom {zoom} is not an integer: {count!r}"
            )
        counts[zoom] = count
    return counts


def _load_expected_counts(arguments: argparse.Namespace) -> dict[int, int]:
    if arguments.expected_counts_file is not None:
        try:
            value = arguments.expected_counts_file.read_text(encoding="utf-8-sig")
        except (OSError, UnicodeDecodeError) as error:
            raise SourceLockError(
                f"expected counts file is unreadable: {arguments.expected_counts_file}: {error}"
            ) from error
    else:
        value = arguments.expected_counts_json
    return _parse_expected_counts(value)


def _descriptor_document(lock: SourceLock) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "sourceName": lock.source_name,
        "serviceUrl": lock.service_url,
        "stylePath": str(lock.style_path),
        "metadataPath": str(lock.metadata_path),
        "styleSha256": lock.style_sha256,
        "metadataSha256": lock.metadata_sha256,
        "populationPath": str(lock.population_path),
        "population": {
            "rowCount": lock.population.row_count,
            "countsByZoom": {
                str(zoom): count
                for zoom, count in lock.population.counts_by_zoom.items()
            },
            "sha256": lock.population.sha256,
        },
    }


def _atomic_write_json(path: Path, document: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            newline="\n",
            prefix=f".{path.name}.",
            suffix=".tmp",
            dir=path.parent,
            delete=False,
        ) as temporary:
            temporary_path = Path(temporary.name)
            json.dump(document, temporary, indent=2, sort_keys=True)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())
        os.replace(temporary_path, path)
        temporary_path = None
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


def run(arguments: argparse.Namespace) -> SourceLock:
    descriptor_path, descriptor = _load_descriptor(arguments.lock_dir)
    expected_style = arguments.expected_style_sha256.lower()
    expected_metadata = arguments.expected_metadata_sha256.lower()
    _descriptor_hash(descriptor, "styleSha256", expected_style, "style")
    _descriptor_hash(descriptor, "metadataSha256", expected_metadata, "metadata")
    style_path = _find_file_by_hash(descriptor_path.parent, expected_style, "style")
    metadata_path = _find_file_by_hash(
        descriptor_path.parent, expected_metadata, "metadata"
    )
    lock = verify_source_lock(
        source_name=_descriptor_text(descriptor, "source"),
        service_url=_descriptor_text(descriptor, "serviceUrl"),
        style_path=style_path,
        metadata_path=metadata_path,
        population_path=arguments.population,
        expected_style_sha256=expected_style,
        expected_metadata_sha256=expected_metadata,
        expected_population_sha256=arguments.expected_population_sha256,
        expected_counts_by_zoom=_load_expected_counts(arguments),
    )
    _atomic_write_json(arguments.out, _descriptor_document(lock))
    return lock


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        run(arguments)
    except (OSError, SourceLockError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
