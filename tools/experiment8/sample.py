from __future__ import annotations

import bisect
import contextlib
import hashlib
import heapq
import json
import math
import os
import re
import struct
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Mapping, Sequence

from .model import TileKey


LATITUDE_EDGES = (-90.0, -41.810315, -19.471221, 0.0, 19.471221, 41.810315, 90.0)
LONGITUDE_EDGES = (-180.0, -135.0, -90.0, -45.0, 0.0, 45.0, 90.0, 135.0, 180.0)
RANK_PREFIX = "flight-alert-exp8-pilot-v1"
WEB_MERCATOR_MAX_LATITUDE = 85.0511287798066
MISSING_SOURCE_BYTES = (1 << 64) - 1
POPULATION_HEADER = "serviceId\tserviceName\tz\tx\ty"
SOURCE_SIZE_HEADER = "z\tx\ty\tsourceSha256\tsourceBytes\tdecodedBytes\tfeatureCount"
FIXTURE_ZOOMS = (5, 8, 11, 13, 16)
MERGE_FAN_IN = 64
FIXTURE_POINTS = (
    ("new-york", 40.7128, -74.0060),
    ("london", 51.5074, -0.1278),
    ("sao-paulo", -23.5505, -46.6333),
    ("cape-town", -33.9249, 18.4241),
    ("cairo", 30.0444, 31.2357),
    ("mumbai", 19.0760, 72.8777),
    ("tokyo", 35.6762, 139.6503),
    ("sydney", -33.8688, 151.2093),
    ("yellowstone", 44.4280, -110.5885),
    ("amazon", -3.4653, -62.2159),
    ("greenland", 72.0, -40.0),
    ("fiji", -17.7134, 178.0650),
    ("aleutian-antimeridian", 52.0, 179.5),
    ("us-canada-boundary", 49.0, -123.0),
    ("india-pakistan-boundary", 32.5, 74.5),
    ("west-bank", 31.8, 35.2),
    ("western-sahara", 24.0, -13.0),
    ("great-barrier-reef", -18.2871, 147.6992),
)

_HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_UNSIGNED = re.compile(r"[0-9]+\Z")
_U64 = struct.Struct(">Q")
_SPOOL_RECORD = struct.Struct(">QQ")


class SampleError(ValueError):
    """A sampler input or deterministic selection invariant was violated."""


@dataclass(frozen=True, slots=True)
class SampleBuildResult:
    sample_row_count: int
    selection_counts: Mapping[str, int]
    sample_sha256: str
    fixture_sha256: str


def latitude_band(latitude: float) -> int:
    if not math.isfinite(latitude) or not LATITUDE_EDGES[0] <= latitude <= LATITUDE_EDGES[-1]:
        raise ValueError(f"latitude out of range: {latitude!r}")
    return bisect.bisect_right(LATITUDE_EDGES[1:-1], latitude)


def longitude_sector(longitude: float) -> int:
    if not math.isfinite(longitude) or not LONGITUDE_EDGES[0] <= longitude <= LONGITUDE_EDGES[-1]:
        raise ValueError(f"longitude out of range: {longitude!r}")
    return bisect.bisect_right(LONGITUDE_EDGES[1:-1], longitude)


def lon_lat_to_tile(z: int, longitude: float, latitude: float) -> TileKey:
    if not math.isfinite(longitude) or not -180.0 <= longitude <= 180.0:
        raise ValueError(f"longitude out of range: {longitude!r}")
    if not math.isfinite(latitude) or not -90.0 <= latitude <= 90.0:
        raise ValueError(f"latitude out of range: {latitude!r}")
    if not 0 <= z <= 29:
        raise ValueError(f"zoom out of range: {z}")
    scale = 1 << z
    x = math.floor(scale * (longitude + 180.0) / 360.0)
    x = min(scale - 1, max(0, x))
    clamped_latitude = min(WEB_MERCATOR_MAX_LATITUDE, max(-WEB_MERCATOR_MAX_LATITUDE, latitude))
    radians = math.radians(clamped_latitude)
    y = math.floor(scale * (1.0 - math.asinh(math.tan(radians)) / math.pi) / 2.0)
    y = min(scale - 1, max(0, y))
    return TileKey(z, x, y)


def sample_rank(tile: TileKey) -> bytes:
    return hashlib.sha256(
        f"{RANK_PREFIX}|{tile.z}|{tile.x}|{tile.y}".encode("ascii")
    ).digest()


def _sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def _load_json(path: Path, label: str) -> tuple[Path, dict[str, object], str]:
    try:
        resolved = path.resolve(strict=True)
        raw = resolved.read_bytes()
    except OSError as error:
        raise SampleError(f"{label} is unavailable: {path}: {error}") from error
    try:
        document = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SampleError(f"{label} is invalid JSON: {resolved}: {error}") from error
    if not isinstance(document, dict):
        raise SampleError(f"{label} root must be a JSON object")
    return resolved, document, hashlib.sha256(raw).hexdigest()


def _object(document: Mapping[str, object], key: str, label: str) -> dict[str, object]:
    value = document.get(key)
    if not isinstance(value, dict):
        raise SampleError(f"{label} field {key!r} must be an object")
    return value


def _integer(document: Mapping[str, object], key: str, label: str) -> int:
    value = document.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise SampleError(f"{label} field {key!r} must be a nonnegative integer")
    return value


def _hash(document: Mapping[str, object], key: str, label: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or _HEX_SHA256.fullmatch(value) is None:
        raise SampleError(f"{label} field {key!r} must be a SHA-256 value")
    return value.lower()


def _counts(document: Mapping[str, object], key: str, label: str) -> dict[int, int]:
    raw = _object(document, key, label)
    result: dict[int, int] = {}
    for z_text, count in raw.items():
        if not isinstance(z_text, str) or _UNSIGNED.fullmatch(z_text) is None:
            raise SampleError(f"{label} has an invalid zoom key: {z_text!r}")
        z = int(z_text)
        if str(z) != z_text or isinstance(count, bool) or not isinstance(count, int) or count < 0:
            raise SampleError(f"{label} has an invalid count for zoom {z_text!r}")
        result[z] = count
    return dict(sorted(result.items()))


def _decode_line(raw_line: bytes, path: Path, line_number: int) -> str:
    if raw_line.endswith(b"\n"):
        raw_line = raw_line[:-1]
        if raw_line.endswith(b"\r"):
            raw_line = raw_line[:-1]
    try:
        return raw_line.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SampleError(f"{path}:{line_number} is not valid UTF-8: {error}") from error


def _unsigned(value: str, field: str, path: Path, line_number: int) -> int:
    if _UNSIGNED.fullmatch(value) is None:
        raise SampleError(f"{path}:{line_number} field {field} is not a nonnegative decimal")
    return int(value)


def _write_run(path: Path, packed: list[int]) -> None:
    packed.sort()
    with path.open("wb") as output:
        for value in packed:
            output.write(_U64.pack(value))
        output.flush()
        os.fsync(output.fileno())


def _merge_run_files(inputs: Sequence[Path], output_path: Path) -> None:
    if not 2 <= len(inputs) <= MERGE_FAN_IN:
        raise SampleError(
            f"external merge fan-in must be between 2 and {MERGE_FAN_IN}: {len(inputs)}"
        )
    iterators = [_run_values(path) for path in inputs]
    try:
        with output_path.open("wb") as output:
            for packed in heapq.merge(*iterators):
                output.write(_U64.pack(packed))
            output.flush()
            os.fsync(output.fileno())
    finally:
        for iterator in iterators:
            iterator.close()


def _delete_runs(paths: Sequence[Path]) -> None:
    for path in paths:
        path.unlink(missing_ok=True)


def _population_runs(
    population_path: Path,
    work_dir: Path,
    chunk_rows: int,
) -> tuple[list[Path], str, int, dict[int, int]]:
    try:
        resolved = population_path.resolve(strict=True)
    except OSError as error:
        raise SampleError(f"population is unavailable: {population_path}: {error}") from error
    digest = hashlib.sha256()
    counts: Counter[int] = Counter()
    row_count = 0
    if MERGE_FAN_IN < 2:
        raise SampleError(f"MERGE_FAN_IN must be at least 2: {MERGE_FAN_IN}")
    levels: list[list[Path]] = []
    initial_serial = 0
    merge_serial = 0
    chunk: list[int] = []

    def add_run(run: Path) -> None:
        nonlocal merge_serial
        level = 0
        carry = run
        while True:
            while len(levels) <= level:
                levels.append([])
            levels[level].append(carry)
            if len(levels[level]) < MERGE_FAN_IN:
                return
            group = levels[level]
            levels[level] = []
            carry = work_dir / f"merge-{merge_serial:06d}.u64"
            merge_serial += 1
            _merge_run_files(group, carry)
            _delete_runs(group)
            level += 1

    with resolved.open("rb") as source:
        header_raw = source.readline()
        digest.update(header_raw)
        if _decode_line(header_raw, resolved, 1) != POPULATION_HEADER:
            raise SampleError(f"population header mismatch: {resolved}")
        for line_number, raw_line in enumerate(source, start=2):
            digest.update(raw_line)
            fields = _decode_line(raw_line, resolved, line_number).split("\t")
            if len(fields) != 5:
                raise SampleError(f"population row {line_number} has {len(fields)} fields")
            service_id, service_name, z_text, x_text, y_text = fields
            if not service_id or not service_name:
                raise SampleError(f"population source identity is empty at line {line_number}")
            z = _unsigned(z_text, "z", resolved, line_number)
            x = _unsigned(x_text, "x", resolved, line_number)
            y = _unsigned(y_text, "y", resolved, line_number)
            try:
                tile = TileKey(z, x, y)
            except ValueError as error:
                raise SampleError(f"invalid population tile {z}/{x}/{y}: {error}") from error
            chunk.append(tile.packed)
            counts[z] += 1
            row_count += 1
            if len(chunk) >= chunk_rows:
                run = work_dir / f"population-{initial_serial:06d}.u64"
                initial_serial += 1
                _write_run(run, chunk)
                add_run(run)
                chunk = []
    if chunk:
        run = work_dir / f"population-{initial_serial:06d}.u64"
        _write_run(run, chunk)
        add_run(run)
    runs = [path for level in levels for path in level]
    while len(runs) > MERGE_FAN_IN:
        collapsed: list[Path] = []
        for offset in range(0, len(runs), MERGE_FAN_IN):
            group = runs[offset : offset + MERGE_FAN_IN]
            if len(group) == 1:
                collapsed.extend(group)
                continue
            merged = work_dir / f"merge-{merge_serial:06d}.u64"
            merge_serial += 1
            _merge_run_files(group, merged)
            _delete_runs(group)
            collapsed.append(merged)
        runs = collapsed
    return runs, digest.hexdigest(), row_count, dict(sorted(counts.items()))


def _run_values(path: Path) -> Iterator[int]:
    with path.open("rb") as source:
        while raw := source.read(_U64.size):
            if len(raw) != _U64.size:
                raise SampleError(f"truncated population sort run: {path}")
            yield _U64.unpack(raw)[0]


def _merged_population(runs: Sequence[Path]) -> Iterator[int]:
    if not runs:
        return
    iterators = [_run_values(run) for run in runs]
    try:
        last: int | None = None
        for packed in heapq.merge(*iterators):
            if packed == last:
                tile = TileKey.from_packed(packed)
                raise SampleError(f"duplicate population tile {tile.z}/{tile.x}/{tile.y}")
            last = packed
            yield packed
    finally:
        for iterator in iterators:
            iterator.close()


def _source_sizes(
    path: Path,
    expected_sha256: str,
    expected_bytes: int,
) -> Iterator[tuple[int, int]]:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise SampleError(f"source-size catalog is unavailable: {path}: {error}") from error
    if resolved.stat().st_size != expected_bytes:
        raise SampleError(
            f"source-size byte length mismatch: expected {expected_bytes}, got {resolved.stat().st_size}"
        )
    digest = hashlib.sha256()
    last = -1
    with resolved.open("rb") as source:
        header_raw = source.readline()
        digest.update(header_raw)
        if _decode_line(header_raw, resolved, 1) != SOURCE_SIZE_HEADER:
            raise SampleError(f"source-size header mismatch: {resolved}")
        for line_number, raw_line in enumerate(source, start=2):
            digest.update(raw_line)
            fields = _decode_line(raw_line, resolved, line_number).split("\t")
            if len(fields) != 7:
                raise SampleError(f"source-size row {line_number} has {len(fields)} fields")
            z, x, y = (
                _unsigned(fields[0], "z", resolved, line_number),
                _unsigned(fields[1], "x", resolved, line_number),
                _unsigned(fields[2], "y", resolved, line_number),
            )
            try:
                tile = TileKey(z, x, y)
            except ValueError as error:
                raise SampleError(f"invalid source-size tile {z}/{x}/{y}: {error}") from error
            if tile.packed <= last:
                raise SampleError(f"source-size catalog is not strictly packed-key sorted at line {line_number}")
            last = tile.packed
            if _HEX_SHA256.fullmatch(fields[3]) is None:
                raise SampleError(f"invalid source SHA-256 at source-size line {line_number}")
            source_bytes = _unsigned(fields[4], "sourceBytes", resolved, line_number)
            if source_bytes >= MISSING_SOURCE_BYTES:
                raise SampleError(f"sourceBytes exceeds supported range at line {line_number}")
            _unsigned(fields[5], "decodedBytes", resolved, line_number)
            _unsigned(fields[6], "featureCount", resolved, line_number)
            yield tile.packed, source_bytes
    actual = digest.hexdigest()
    if actual != expected_sha256:
        raise SampleError(
            f"source-size SHA-256 mismatch: expected {expected_sha256}, got {actual}"
        )


def _next_or_none(iterator: Iterator[tuple[int, int]]) -> tuple[int, int] | None:
    try:
        return next(iterator)
    except StopIteration:
        return None


def _fixture_keys(
    fixture_points: Sequence[tuple[str, float, float]],
    fixture_zooms: Sequence[int],
) -> dict[int, set[str]]:
    result: dict[int, set[str]] = {}
    for name, latitude, longitude in fixture_points:
        if not name:
            raise SampleError("fixture name is empty")
        for z in fixture_zooms:
            tile = lon_lat_to_tile(z, longitude, latitude)
            result.setdefault(tile.packed, set()).add(name)
    return result


def _build_spool(
    population: Iterator[int],
    sizes: Iterator[tuple[int, int]],
    spool_path: Path,
    expected_size_rows: int,
    expected_missing: Sequence[int],
    fixture_keys: set[int],
) -> tuple[int, int, set[int]]:
    try:
        current_size = _next_or_none(sizes)
        row_count = 0
        size_rows = 0
        missing: list[int] = []
        present_fixtures: set[int] = set()
        with spool_path.open("wb") as spool:
            for packed in population:
                if current_size is not None and current_size[0] < packed:
                    tile = TileKey.from_packed(current_size[0])
                    raise SampleError(f"source-size catalog has tile outside population: {tile.z}/{tile.x}/{tile.y}")
                if current_size is not None and current_size[0] == packed:
                    source_bytes = current_size[1]
                    size_rows += 1
                    current_size = _next_or_none(sizes)
                else:
                    source_bytes = MISSING_SOURCE_BYTES
                    missing.append(packed)
                spool.write(_SPOOL_RECORD.pack(packed, source_bytes))
                if packed in fixture_keys:
                    present_fixtures.add(packed)
                row_count += 1
            spool.flush()
            os.fsync(spool.fileno())
        if current_size is not None:
            tile = TileKey.from_packed(current_size[0])
            raise SampleError(f"source-size catalog has tile outside population: {tile.z}/{tile.x}/{tile.y}")
        if size_rows != expected_size_rows:
            raise SampleError(f"source-size row-count mismatch: expected {expected_size_rows}, got {size_rows}")
        if tuple(missing) != tuple(expected_missing):
            raise SampleError("source-size summary missing-tile inventory mismatch")
        return row_count, len(missing), present_fixtures
    finally:
        for iterator in (population, sizes):
            close = getattr(iterator, "close", None)
            if close is not None:
                close()


def _spool_records(path: Path) -> Iterator[tuple[int, int]]:
    with path.open("rb") as source:
        while raw := source.read(_SPOOL_RECORD.size):
            if len(raw) != _SPOOL_RECORD.size:
                raise SampleError(f"truncated classified spool: {path}")
            yield _SPOOL_RECORD.unpack(raw)


def _tail_keys(path: Path, census_max_z: int, quota: int) -> set[int]:
    heaps: dict[int, list[tuple[int, int, int]]] = {}
    if quota == 0:
        return set()
    for packed, source_bytes in _spool_records(path):
        tile = TileKey.from_packed(packed)
        if tile.z <= census_max_z or source_bytes == MISSING_SOURCE_BYTES:
            continue
        key = (source_bytes, -packed, packed)
        heap = heaps.setdefault(tile.z, [])
        if len(heap) < quota:
            heapq.heappush(heap, key)
        elif key[:2] > heap[0][:2]:
            heapq.heapreplace(heap, key)
    return {entry[2] for heap in heaps.values() for entry in heap}


def _stratum(tile: TileKey) -> tuple[int, int, int]:
    longitude, latitude = tile.center_lon_lat()
    return tile.z, latitude_band(latitude), longitude_sector(longitude)


def _select_random(
    spool_path: Path,
    census_max_z: int,
    quota: int,
    tails: set[int],
) -> tuple[set[int], dict[tuple[int, int, int], dict[str, int]], int]:
    heaps: dict[tuple[int, int, int], list[tuple[int, int, int]]] = {}
    strata: dict[tuple[int, int, int], dict[str, int]] = {}
    census_count = 0
    for packed, source_bytes in _spool_records(spool_path):
        tile = TileKey.from_packed(packed)
        if tile.z <= census_max_z:
            census_count += 1
            continue
        key = _stratum(tile)
        stats = strata.setdefault(
            key,
            {"populationCount": 0, "certaintyCount": 0, "randomCandidateCount": 0},
        )
        stats["populationCount"] += 1
        if source_bytes == MISSING_SOURCE_BYTES or packed in tails:
            stats["certaintyCount"] += 1
            continue
        stats["randomCandidateCount"] += 1
        if quota == 0:
            continue
        rank_integer = int.from_bytes(sample_rank(tile), "big")
        entry = (-rank_integer, -packed, packed)
        heap = heaps.setdefault(key, [])
        if len(heap) < quota:
            heapq.heappush(heap, entry)
        else:
            candidate_key = (rank_integer, packed)
            worst_key = (-heap[0][0], -heap[0][1])
            if candidate_key < worst_key:
                heapq.heapreplace(heap, entry)
    selected = {entry[2] for heap in heaps.values() for entry in heap}
    for key, stats in strata.items():
        random_selected = len(heaps.get(key, ()))
        stats["randomSelectedCount"] = random_selected
        stats["sampleCount"] = stats["certaintyCount"] + random_selected
    return selected, strata, census_count


def _atomic_text(path: Path):
    return tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    )


def _reserve_backup_path(destination: Path) -> Path:
    with tempfile.NamedTemporaryFile(
        mode="wb",
        prefix=f".{destination.name}.",
        suffix=".bak",
        dir=destination.parent,
        delete=False,
    ) as reserved:
        backup = Path(reserved.name)
    backup.unlink()
    return backup


def _commit_output_set(replacements: Sequence[tuple[Path, Path]]) -> None:
    """Publish a group of files or restore the complete previous generation."""

    backups: list[tuple[Path, Path]] = []
    reserved_backups: list[Path] = []
    installed: list[Path] = []
    try:
        for _, destination in replacements:
            if not destination.exists():
                continue
            backup = _reserve_backup_path(destination)
            reserved_backups.append(backup)
            os.replace(destination, backup)
            backups.append((destination, backup))
        for source, destination in replacements:
            os.replace(source, destination)
            installed.append(destination)
    except BaseException as error:
        rollback_errors: list[str] = []
        for destination in reversed(installed):
            try:
                destination.unlink(missing_ok=True)
            except OSError as rollback_error:
                rollback_errors.append(f"remove {destination}: {rollback_error}")
        for destination, backup in reversed(backups):
            try:
                os.replace(backup, destination)
            except OSError as rollback_error:
                rollback_errors.append(f"restore {destination}: {rollback_error}")
        for backup in reserved_backups:
            try:
                backup.unlink(missing_ok=True)
            except OSError as rollback_error:
                rollback_errors.append(f"clean {backup}: {rollback_error}")
        if rollback_errors:
            raise SampleError(
                "sample output transaction and rollback failed: " + "; ".join(rollback_errors)
            ) from error
        raise
    else:
        for _, backup in backups:
            backup.unlink(missing_ok=True)


def _write_json_line(output, document: Mapping[str, object]) -> None:
    output.write(json.dumps(document, sort_keys=True, separators=(",", ":")))
    output.write("\n")


def _write_sample(
    spool_path: Path,
    path: Path,
    census_max_z: int,
    tails: set[int],
    random_keys: set[int],
) -> tuple[Path, int, dict[str, int], str, int]:
    temporary_path: Path | None = None
    counts: Counter[str] = Counter()
    rows = 0
    try:
        with _atomic_text(path) as temporary:
            temporary_path = Path(temporary.name)
            for packed, source_bytes in _spool_records(spool_path):
                tile = TileKey.from_packed(packed)
                if tile.z <= census_max_z:
                    selection = "census"
                elif source_bytes == MISSING_SOURCE_BYTES:
                    selection = "uncatalogued"
                elif packed in tails:
                    selection = "tail"
                elif packed in random_keys:
                    selection = "random"
                else:
                    continue
                document: dict[str, object] = {
                    "selection": selection,
                    "sourceBytes": None if source_bytes == MISSING_SOURCE_BYTES else source_bytes,
                    "x": tile.x,
                    "y": tile.y,
                    "z": tile.z,
                }
                if tile.z > census_max_z:
                    _, band, sector = _stratum(tile)
                    document["latitudeBand"] = band
                    document["longitudeSector"] = sector
                    document["rankSha256"] = sample_rank(tile).hex()
                _write_json_line(temporary, document)
                counts[selection] += 1
                rows += 1
            temporary.flush()
            os.fsync(temporary.fileno())
        assert temporary_path is not None
        return temporary_path, rows, dict(sorted(counts.items())), _sha256_file(temporary_path), temporary_path.stat().st_size
    except BaseException:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise


def _write_fixtures(
    path: Path,
    fixtures: Mapping[int, set[str]],
    present: set[int],
) -> tuple[Path, int, int, int, str, int]:
    temporary_path: Path | None = None
    present_count = 0
    empty_count = 0
    try:
        with _atomic_text(path) as temporary:
            temporary_path = Path(temporary.name)
            for packed in sorted(fixtures):
                tile = TileKey.from_packed(packed)
                state = "present" if packed in present else "known_empty"
                if state == "present":
                    present_count += 1
                else:
                    empty_count += 1
                _write_json_line(
                    temporary,
                    {
                        "fixtureNames": sorted(fixtures[packed]),
                        "selection": "fixture",
                        "sourceState": state,
                        "x": tile.x,
                        "y": tile.y,
                        "z": tile.z,
                    },
                )
            temporary.flush()
            os.fsync(temporary.fileno())
        assert temporary_path is not None
        return (
            temporary_path,
            len(fixtures),
            present_count,
            empty_count,
            _sha256_file(temporary_path),
            temporary_path.stat().st_size,
        )
    except BaseException:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
        raise


def _missing_packed(summary: Mapping[str, object]) -> tuple[int, ...]:
    raw = summary.get("missingPopulationTiles")
    if not isinstance(raw, list):
        raise SampleError("source-size summary missingPopulationTiles must be an array")
    result: list[int] = []
    for value in raw:
        if not isinstance(value, str):
            raise SampleError("source-size summary has a non-string missing tile")
        parts = value.split("/")
        if len(parts) != 3 or any(_UNSIGNED.fullmatch(part) is None for part in parts):
            raise SampleError(f"invalid missing tile key: {value!r}")
        try:
            tile = TileKey(*(int(part) for part in parts))
        except ValueError as error:
            raise SampleError(f"invalid missing tile key: {value!r}: {error}") from error
        result.append(tile.packed)
    if result != sorted(set(result)):
        raise SampleError("source-size summary missing tiles are not unique and packed-key sorted")
    return tuple(result)


def build_sample_manifest(
    *,
    verified_source_lock_path: Path,
    expected_verified_source_lock_sha256: str,
    population_path: Path,
    source_sizes_path: Path,
    source_size_summary_path: Path,
    expected_source_size_summary_sha256: str,
    stage: str,
    output_dir: Path,
    census_max_z: int = 8,
    random_per_stratum: int | None = None,
    tail_per_zoom: int | None = None,
    sort_chunk_rows: int = 250_000,
    fixture_points: Sequence[tuple[str, float, float]] = FIXTURE_POINTS,
    fixture_zooms: Sequence[int] = FIXTURE_ZOOMS,
) -> SampleBuildResult:
    if stage not in ("a", "b"):
        raise SampleError(f"stage must be 'a' or 'b', got {stage!r}")
    if random_per_stratum is None:
        random_per_stratum = 32 if stage == "a" else 256
    if tail_per_zoom is None:
        tail_per_zoom = 32 if stage == "a" else 256
    if not 0 <= census_max_z <= 29:
        raise SampleError(f"census_max_z out of range: {census_max_z}")
    if random_per_stratum < 0 or tail_per_zoom < 0 or sort_chunk_rows <= 0:
        raise SampleError("sample quotas must be nonnegative and sort_chunk_rows positive")

    if _HEX_SHA256.fullmatch(expected_verified_source_lock_sha256) is None:
        raise SampleError("expected verified source-lock SHA-256 is invalid")
    if _HEX_SHA256.fullmatch(expected_source_size_summary_sha256) is None:
        raise SampleError("expected source-size summary SHA-256 is invalid")
    _, lock, lock_sha = _load_json(Path(verified_source_lock_path), "verified source lock")
    if lock_sha != expected_verified_source_lock_sha256.lower():
        raise SampleError(
            "verified source-lock file SHA-256 mismatch: "
            f"expected {expected_verified_source_lock_sha256.lower()}, got {lock_sha}"
        )
    if lock.get("schemaVersion") != 1:
        raise SampleError("verified source lock schemaVersion must equal 1")
    lock_population = _object(lock, "population", "verified source lock")
    expected_population_sha = _hash(lock_population, "sha256", "verified source lock population")
    expected_population_rows = _integer(lock_population, "rowCount", "verified source lock population")
    expected_population_counts = _counts(lock_population, "countsByZoom", "verified source lock population")
    source_lock_sha = _hash(lock, "sourceLockSha256", "verified source lock")

    _, size_summary, size_summary_sha = _load_json(
        Path(source_size_summary_path), "source-size summary"
    )
    if size_summary_sha != expected_source_size_summary_sha256.lower():
        raise SampleError(
            "source-size summary file SHA-256 mismatch: "
            f"expected {expected_source_size_summary_sha256.lower()}, got {size_summary_sha}"
        )
    if size_summary.get("schemaVersion") != 1:
        raise SampleError("source-size summary schemaVersion must equal 1")
    summary_population_sha = _hash(size_summary, "populationSha256", "source-size summary")
    if summary_population_sha != expected_population_sha:
        raise SampleError(
            "summary population SHA-256 mismatch: "
            f"expected {expected_population_sha}, got {summary_population_sha}"
        )
    summary_population_rows = _integer(size_summary, "populationRowCount", "source-size summary")
    if summary_population_rows != expected_population_rows:
        raise SampleError("source-size summary population row-count mismatch")
    expected_size_rows = _integer(size_summary, "uniqueTileCount", "source-size summary")
    expected_missing_count = _integer(size_summary, "missingPopulationCount", "source-size summary")
    expected_missing = _missing_packed(size_summary)
    if len(expected_missing) != expected_missing_count:
        raise SampleError("source-size summary missing count does not match its inventory")
    if expected_size_rows + expected_missing_count != expected_population_rows:
        raise SampleError("source-size summary does not reconcile to population rows")
    expected_size_sha = _hash(size_summary, "outputSha256", "source-size summary")
    expected_size_bytes = _integer(size_summary, "outputBytes", "source-size summary")

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    sample_path = output / "sample.jsonl"
    fixture_path = output / "fixtures.jsonl"
    summary_path = output / "summary.json"
    fixture_map = _fixture_keys(fixture_points, fixture_zooms)

    sample_temporary: Path | None = None
    fixture_temporary: Path | None = None
    summary_temporary: Path | None = None
    with (
        tempfile.TemporaryDirectory(prefix=".sample-work-", dir=output) as work_text,
        contextlib.ExitStack() as output_cleanup,
    ):
        work = Path(work_text)
        runs, actual_population_sha, actual_population_rows, actual_population_counts = _population_runs(
            Path(population_path), work, sort_chunk_rows
        )
        if actual_population_sha != expected_population_sha:
            raise SampleError(
                f"population SHA-256 mismatch: expected {expected_population_sha}, got {actual_population_sha}"
            )
        if actual_population_rows != expected_population_rows:
            raise SampleError("population row-count mismatch")
        if actual_population_counts != expected_population_counts:
            raise SampleError("population per-zoom counts mismatch")

        spool_path = work / "classified-spool.bin"
        population_rows, missing_count, present_fixtures = _build_spool(
            _merged_population(runs),
            _source_sizes(Path(source_sizes_path), expected_size_sha, expected_size_bytes),
            spool_path,
            expected_size_rows,
            expected_missing,
            set(fixture_map),
        )
        if population_rows != expected_population_rows or missing_count != expected_missing_count:
            raise SampleError("classified spool does not reconcile to trusted inputs")

        tails = _tail_keys(spool_path, census_max_z, tail_per_zoom)
        random_keys, strata, census_count = _select_random(
            spool_path, census_max_z, random_per_stratum, tails
        )
        (
            sample_temporary,
            sample_rows,
            selection_counts,
            sample_sha,
            sample_bytes,
        ) = _write_sample(spool_path, sample_path, census_max_z, tails, random_keys)
        output_cleanup.callback(sample_temporary.unlink, missing_ok=True)
        (
            fixture_temporary,
            fixture_rows,
            fixture_present_count,
            fixture_empty_count,
            fixture_sha,
            fixture_bytes,
        ) = _write_fixtures(fixture_path, fixture_map, present_fixtures)
        output_cleanup.callback(fixture_temporary.unlink, missing_ok=True)

        strata_documents = [
            {
                "certaintyCount": stats["certaintyCount"],
                "latitudeBand": key[1],
                "longitudeSector": key[2],
                "populationCount": stats["populationCount"],
                "randomCandidateCount": stats["randomCandidateCount"],
                "randomSelectedCount": stats["randomSelectedCount"],
                "sampleCount": stats["sampleCount"],
                "z": key[0],
            }
            for key, stats in sorted(strata.items())
        ]
        if census_count + sum(item["sampleCount"] for item in strata_documents) != sample_rows:
            raise SampleError("sample count does not reconcile to census and strata")
        summary_document: dict[str, object] = {
            "schemaVersion": 1,
            "stage": stage,
            "parameters": {
                "censusMaxZoom": census_max_z,
                "latitudeEdges": list(LATITUDE_EDGES),
                "longitudeEdges": list(LONGITUDE_EDGES),
                "randomPerStratum": random_per_stratum,
                "rankPrefix": RANK_PREFIX,
                "tailPerZoom": tail_per_zoom,
            },
            "inputs": {
                "populationSha256": actual_population_sha,
                "sourceLockSha256": source_lock_sha,
                "sourceSizeSha256": expected_size_sha,
                "sourceSizeSummarySha256": size_summary_sha,
                "verifiedSourceLockSha256": lock_sha,
            },
            "populationRowCount": population_rows,
            "populationCountsByZoom": {
                str(z): count for z, count in actual_population_counts.items()
            },
            "sourceSizeRowCount": expected_size_rows,
            "missingSourceSizeCount": missing_count,
            "censusCount": census_count,
            "tailKeyCount": len(tails),
            "sampleRowCount": sample_rows,
            "selectionCounts": selection_counts,
            "sampleBytes": sample_bytes,
            "sampleSha256": sample_sha,
            "fixtureRowCount": fixture_rows,
            "fixturePresentCount": fixture_present_count,
            "fixtureKnownEmptyCount": fixture_empty_count,
            "fixtureBytes": fixture_bytes,
            "fixtureSha256": fixture_sha,
            "strata": strata_documents,
        }
        with _atomic_text(summary_path) as temporary:
            summary_temporary = Path(temporary.name)
            output_cleanup.callback(summary_temporary.unlink, missing_ok=True)
            json.dump(summary_document, temporary, indent=2, sort_keys=True)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())

        _commit_output_set(
            (
                (sample_temporary, sample_path),
                (fixture_temporary, fixture_path),
                (summary_temporary, summary_path),
            )
        )
        sample_temporary = None
        fixture_temporary = None
        summary_temporary = None

    return SampleBuildResult(
        sample_row_count=sample_rows,
        selection_counts=selection_counts,
        sample_sha256=sample_sha,
        fixture_sha256=fixture_sha,
    )
