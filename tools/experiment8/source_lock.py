from __future__ import annotations

import hashlib
import json
import os
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit

from .model import PopulationSummary, SourceLock, TileKey


POPULATION_HEADER = "serviceId\tserviceName\tz\tx\ty"
_HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_UNSIGNED_DECIMAL = re.compile(r"[0-9]+\Z")


class SourceLockError(ValueError):
    """A source-lock artifact violated a required identity invariant."""


@dataclass(frozen=True, slots=True)
class VerifiedSourceDescriptor:
    path: Path
    sha256: str
    source_name: str
    service_url: str
    style_sha256: str
    metadata_sha256: str


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


def _descriptor_text(document: dict[str, object], key: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or not value.strip():
        raise SourceLockError(f"source descriptor field {key!r} is missing or empty")
    return value


def _validated_service_url(value: object) -> str:
    if (
        not isinstance(value, str)
        or not value
        or any(character.isspace() for character in value)
    ):
        raise SourceLockError("source descriptor service URL is missing or invalid")
    try:
        parsed = urlsplit(value)
        hostname = parsed.hostname
        _ = parsed.port
    except ValueError as error:
        raise SourceLockError(f"source descriptor service URL is invalid: {value!r}") from error
    if parsed.scheme.lower() != "https" or not parsed.netloc or hostname is None:
        raise SourceLockError(
            f"source descriptor service URL must be absolute HTTPS: {value!r}"
        )
    if parsed.username is not None or parsed.password is not None:
        raise SourceLockError(
            f"source descriptor service URL must not contain credentials: {value!r}"
        )
    return value


def verify_source_descriptor(
    source_lock_path: str | os.PathLike[str],
    expected_source_lock_sha256: str,
) -> VerifiedSourceDescriptor:
    resolved_path = _required_file(source_lock_path, "source descriptor")
    expected_hash = _expected_sha256(expected_source_lock_sha256, "source descriptor")
    try:
        raw_document = resolved_path.read_bytes()
    except OSError as error:
        raise SourceLockError(
            f"source descriptor is unreadable: {resolved_path}: {error}"
        ) from error
    actual_hash = hashlib.sha256(raw_document).hexdigest()
    if actual_hash != expected_hash:
        raise SourceLockError(
            "source descriptor SHA-256 mismatch: "
            f"expected {expected_hash}, got {actual_hash}"
        )

    try:
        document = json.loads(raw_document.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceLockError(
            f"source descriptor is not valid UTF-8 JSON: {resolved_path}: {error}"
        ) from error
    if not isinstance(document, dict):
        raise SourceLockError("source descriptor root must be a JSON object")
    schema_version = document.get("schemaVersion")
    if type(schema_version) is not int or schema_version != 1:
        raise SourceLockError(
            f"source descriptor schemaVersion mismatch: expected 1, got {schema_version!r}"
        )
    return VerifiedSourceDescriptor(
        path=resolved_path,
        sha256=actual_hash,
        source_name=_descriptor_text(document, "source"),
        service_url=_validated_service_url(document.get("serviceUrl")),
        style_sha256=_expected_sha256(
            _descriptor_text(document, "styleSha256"),
            "source descriptor style",
        ),
        metadata_sha256=_expected_sha256(
            _descriptor_text(document, "metadataSha256"),
            "source descriptor metadata",
        ),
    )


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
    source_lock_path: str | os.PathLike[str],
    style_path: str | os.PathLike[str],
    metadata_path: str | os.PathLike[str],
    population_path: str | os.PathLike[str],
    expected_source_lock_sha256: str,
    expected_style_sha256: str,
    expected_metadata_sha256: str,
    expected_population_sha256: str,
    expected_counts_by_zoom: Mapping[int, int],
) -> SourceLock:
    """Verify source identity and population; its full TSV hash binds service fields."""

    source_descriptor = verify_source_descriptor(
        source_lock_path,
        expected_source_lock_sha256,
    )

    resolved_style = _required_file(style_path, "style")
    resolved_metadata = _required_file(metadata_path, "metadata")
    resolved_population = _required_file(population_path, "population")
    expected_style = _expected_sha256(expected_style_sha256, "style")
    expected_metadata = _expected_sha256(expected_metadata_sha256, "metadata")
    expected_population = _expected_sha256(expected_population_sha256, "population")
    expected_counts = _expected_counts(expected_counts_by_zoom)

    if source_descriptor.style_sha256 != expected_style:
        raise SourceLockError(
            "source descriptor style SHA-256 mismatch: "
            f"expected {expected_style}, got {source_descriptor.style_sha256}"
        )
    if source_descriptor.metadata_sha256 != expected_metadata:
        raise SourceLockError(
            "source descriptor metadata SHA-256 mismatch: "
            f"expected {expected_metadata}, got {source_descriptor.metadata_sha256}"
        )

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
        source_name=source_descriptor.source_name,
        service_url=source_descriptor.service_url,
        source_lock_path=source_descriptor.path,
        source_lock_sha256=source_descriptor.sha256,
        style_path=resolved_style,
        metadata_path=resolved_metadata,
        style_sha256=actual_style,
        metadata_sha256=actual_metadata,
        population_path=resolved_population,
        population=population,
    )
