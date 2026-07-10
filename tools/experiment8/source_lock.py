from __future__ import annotations

import hashlib
import os
import re
from collections import Counter
from pathlib import Path
from typing import Mapping

from .model import PopulationSummary, SourceLock, TileKey


POPULATION_HEADER = "serviceId\tserviceName\tz\tx\ty"
_HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_UNSIGNED_DECIMAL = re.compile(r"[0-9]+\Z")


class SourceLockError(ValueError):
    """A source-lock artifact violated a required identity invariant."""


def sha256_file(path: str | os.PathLike[str], chunk_size: int = 1024 * 1024) -> str:
    """Return the SHA-256 of a file without loading it into memory."""

    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def _required_file(path: str | os.PathLike[str], label: str) -> Path:
    candidate = Path(path)
    try:
        resolved = candidate.resolve(strict=True)
    except OSError as error:
        raise SourceLockError(f"{label} file is unavailable: {candidate}: {error}") from error
    if not resolved.is_file():
        raise SourceLockError(f"{label} path is not a file: {resolved}")
    return resolved


def _expected_sha256(value: str, label: str) -> str:
    if not isinstance(value, str) or _HEX_SHA256.fullmatch(value) is None:
        raise SourceLockError(f"expected {label} SHA-256 is not 64 hexadecimal digits")
    return value.lower()


def _expected_counts(counts: Mapping[int, int]) -> dict[int, int]:
    normalized: dict[int, int] = {}
    for zoom, count in counts.items():
        if isinstance(zoom, bool) or not isinstance(zoom, int) or not 0 <= zoom <= 29:
            raise SourceLockError(f"expected population zoom is invalid: {zoom!r}")
        if isinstance(count, bool) or not isinstance(count, int) or count < 0:
            raise SourceLockError(
                f"expected population count for zoom {zoom} is invalid: {count!r}"
            )
        normalized[zoom] = count
    return dict(sorted(normalized.items()))


def _line_text(raw_line: bytes, line_number: int) -> str:
    if raw_line.endswith(b"\n"):
        raw_line = raw_line[:-1]
        if raw_line.endswith(b"\r"):
            raw_line = raw_line[:-1]
    try:
        return raw_line.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SourceLockError(
            f"population is not valid UTF-8 at line {line_number}: {error}"
        ) from error


def _stream_population(population_path: Path) -> tuple[str, int, dict[int, int]]:
    digest = hashlib.sha256()
    seen: set[int] = set()
    counts: Counter[int] = Counter()
    row_count = 0

    with population_path.open("rb") as population:
        header_raw = population.readline()
        digest.update(header_raw)
        if not header_raw:
            raise SourceLockError(
                f"population header mismatch: expected {POPULATION_HEADER!r}, got end of file"
            )
        header = _line_text(header_raw, 1)
        if header != POPULATION_HEADER:
            raise SourceLockError(
                f"population header mismatch: expected {POPULATION_HEADER!r}, got {header!r}"
            )

        for line_number, raw_line in enumerate(population, start=2):
            digest.update(raw_line)
            line = _line_text(raw_line, line_number)
            fields = line.split("\t")
            if len(fields) != 5:
                raise SourceLockError(
                    f"population row at line {line_number} has {len(fields)} fields, expected 5"
                )
            service_id, service_name, zoom_text, x_text, y_text = fields
            if not service_id or not service_name:
                raise SourceLockError(
                    f"population source identity is empty at line {line_number}"
                )
            coordinates = (zoom_text, x_text, y_text)
            if any(_UNSIGNED_DECIMAL.fullmatch(value) is None for value in coordinates):
                raise SourceLockError(
                    f"invalid population tile at line {line_number}: "
                    f"{zoom_text}/{x_text}/{y_text}"
                )
            try:
                tile = TileKey(int(zoom_text), int(x_text), int(y_text))
            except ValueError as error:
                raise SourceLockError(
                    f"invalid population tile at line {line_number}: "
                    f"{zoom_text}/{x_text}/{y_text}: {error}"
                ) from error
            if tile.packed in seen:
                raise SourceLockError(
                    f"duplicate population tile {tile.z}/{tile.x}/{tile.y} at line {line_number}"
                )
            seen.add(tile.packed)
            counts[tile.z] += 1
            row_count += 1

    return digest.hexdigest(), row_count, dict(sorted(counts.items()))


def verify_source_lock(
    *,
    source_name: str,
    service_url: str,
    style_path: str | os.PathLike[str],
    metadata_path: str | os.PathLike[str],
    population_path: str | os.PathLike[str],
    expected_style_sha256: str,
    expected_metadata_sha256: str,
    expected_population_sha256: str,
    expected_counts_by_zoom: Mapping[int, int],
) -> SourceLock:
    """Verify immutable source artifacts and the complete population inventory."""

    if not source_name.strip():
        raise SourceLockError("source name is empty")
    if not service_url.strip():
        raise SourceLockError("service URL is empty")

    resolved_style = _required_file(style_path, "style")
    resolved_metadata = _required_file(metadata_path, "metadata")
    resolved_population = _required_file(population_path, "population")
    expected_style = _expected_sha256(expected_style_sha256, "style")
    expected_metadata = _expected_sha256(expected_metadata_sha256, "metadata")
    expected_population = _expected_sha256(expected_population_sha256, "population")
    expected_counts = _expected_counts(expected_counts_by_zoom)

    actual_style = sha256_file(resolved_style)
    if actual_style != expected_style:
        raise SourceLockError(
            f"style SHA-256 mismatch: expected {expected_style}, got {actual_style}"
        )
    actual_metadata = sha256_file(resolved_metadata)
    if actual_metadata != expected_metadata:
        raise SourceLockError(
            f"metadata SHA-256 mismatch: expected {expected_metadata}, got {actual_metadata}"
        )

    actual_population, row_count, actual_counts = _stream_population(resolved_population)
    if actual_population != expected_population:
        raise SourceLockError(
            "population SHA-256 mismatch: "
            f"expected {expected_population}, got {actual_population}"
        )
    differing_counts = {
        zoom: {
            "expected": expected_counts.get(zoom, 0),
            "actual": actual_counts.get(zoom, 0),
        }
        for zoom in sorted(set(expected_counts) | set(actual_counts))
        if expected_counts.get(zoom, 0) != actual_counts.get(zoom, 0)
    }
    if differing_counts:
        raise SourceLockError(f"population counts mismatch: {differing_counts}")

    population = PopulationSummary(
        row_count=row_count,
        counts_by_zoom=actual_counts,
        sha256=actual_population,
    )
    return SourceLock(
        source_name=source_name,
        service_url=service_url,
        style_path=resolved_style,
        metadata_path=resolved_metadata,
        style_sha256=actual_style,
        metadata_sha256=actual_metadata,
        population_path=resolved_population,
        population=population,
    )
