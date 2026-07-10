from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, Mapping, Sequence

from .model import TileKey


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

_ALLOWED_HEADERS = {LEGACY_HEADER, MODERN_HEADER}
_HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_UNSIGNED_DECIMAL = re.compile(r"[0-9]+\Z")
_TEXT_COLUMNS = {"sourceSha256", "decodedSha256", "chunkPath", "storageCodec"}


class SourceSizeError(ValueError):
    """An Experiment 6 source-size artifact violated a catalog invariant."""


@dataclass(frozen=True, slots=True)
class SourceSizeRecord:
    tile: TileKey
    source_sha256: str
    source_bytes: int
    decoded_bytes: int
    feature_count: int


@dataclass(frozen=True, slots=True)
class SourceSizeSummary:
    physical_row_count: int
    unique_tile_count: int
    duplicate_copies: int
    counts_by_zoom: Mapping[int, int]
    missing_population_tiles: tuple[TileKey, ...]
    population_sha256: str
    output_sha256: str


@dataclass(frozen=True, slots=True)
class _CatalogValue:
    source_sha256: bytes
    source_bytes: int
    decoded_bytes: int
    feature_count: int


@dataclass(frozen=True, slots=True)
class _InputFile:
    root: Path
    relative_path: str
    path: Path
    size: int
    sha256: str


def source_size_tail_key(record: SourceSizeRecord) -> tuple[int, int]:
    """Sort larger source responses first, then packed keys ascending."""

    return -record.source_bytes, record.tile.packed


def _sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def _resolved_file(path: Path, label: str) -> Path:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise SourceSizeError(f"{label} is unavailable: {path}: {error}") from error
    if not resolved.is_file():
        raise SourceSizeError(f"{label} is not a file: {resolved}")
    return resolved


def _resolved_root(path: Path) -> Path:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise SourceSizeError(f"shard root is unavailable: {path}: {error}") from error
    if not resolved.is_dir():
        raise SourceSizeError(f"shard root is not a directory: {resolved}")
    return resolved


def _decode_line(raw_line: bytes, path: Path, line_number: int) -> str:
    if raw_line.endswith(b"\n"):
        raw_line = raw_line[:-1]
        if raw_line.endswith(b"\r"):
            raw_line = raw_line[:-1]
    try:
        return raw_line.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SourceSizeError(
            f"{path} is not valid UTF-8 at line {line_number}: {error}"
        ) from error


def _unsigned(value: str, field: str, path: Path, line_number: int) -> int:
    if _UNSIGNED_DECIMAL.fullmatch(value) is None:
        raise SourceSizeError(
            f"{path}:{line_number} field {field} must be a nonnegative decimal: {value!r}"
        )
    return int(value)


def _sha_bytes(value: str, field: str, path: Path, line_number: int) -> bytes:
    if _HEX_SHA256.fullmatch(value) is None:
        raise SourceSizeError(
            f"{path}:{line_number} field {field} is not a SHA-256 value: {value!r}"
        )
    return bytes.fromhex(value)


def _load_population(path: Path) -> tuple[set[int], str, dict[int, int]]:
    resolved = _resolved_file(path, "population")
    digest = hashlib.sha256()
    packed_keys: set[int] = set()
    counts: Counter[int] = Counter()
    with resolved.open("rb") as source:
        header_raw = source.readline()
        digest.update(header_raw)
        if _decode_line(header_raw, resolved, 1) != POPULATION_HEADER:
            raise SourceSizeError(
                f"population header mismatch: expected {POPULATION_HEADER!r}"
            )
        for line_number, raw_line in enumerate(source, start=2):
            digest.update(raw_line)
            line = _decode_line(raw_line, resolved, line_number)
            fields = line.split("\t")
            if len(fields) != 5:
                raise SourceSizeError(
                    f"population {resolved}:{line_number} has {len(fields)} fields, expected 5"
                )
            service_id, service_name, z_text, x_text, y_text = fields
            if not service_id or not service_name:
                raise SourceSizeError(
                    f"population source identity is empty at {resolved}:{line_number}"
                )
            z = _unsigned(z_text, "z", resolved, line_number)
            x = _unsigned(x_text, "x", resolved, line_number)
            y = _unsigned(y_text, "y", resolved, line_number)
            try:
                tile = TileKey(z, x, y)
            except ValueError as error:
                raise SourceSizeError(
                    f"invalid population tile at {resolved}:{line_number}: {z}/{x}/{y}: {error}"
                ) from error
            if tile.packed in packed_keys:
                raise SourceSizeError(
                    f"duplicate population tile {tile.z}/{tile.x}/{tile.y}"
                )
            packed_keys.add(tile.packed)
            counts[tile.z] += 1
    return packed_keys, digest.hexdigest(), dict(sorted(counts.items()))


def _parse_tile_index(path: Path) -> Iterator[tuple[int, _CatalogValue]]:
    """Parse one exact Experiment 6 index without opening its FAR6 payload."""

    with path.open("rb") as source:
        header_raw = source.readline()
        header = _decode_line(header_raw, path, 1)
        if header not in _ALLOWED_HEADERS:
            raise SourceSizeError(f"tile-index header mismatch in {path}: {header!r}")
        columns = header.split("\t")
        positions = {name: index for index, name in enumerate(columns)}
        for line_number, raw_line in enumerate(source, start=2):
            line = _decode_line(raw_line, path, line_number)
            fields = line.split("\t")
            if len(fields) != len(columns):
                raise SourceSizeError(
                    f"{path}:{line_number} has {len(fields)} fields, expected {len(columns)}"
                )
            for index, column in enumerate(columns):
                value = fields[index]
                if column in ("sourceSha256", "decodedSha256"):
                    _sha_bytes(value, column, path, line_number)
                elif column not in _TEXT_COLUMNS:
                    _unsigned(value, column, path, line_number)
                elif not value:
                    raise SourceSizeError(
                        f"{path}:{line_number} field {column} is empty"
                    )
            z = _unsigned(fields[positions["z"]], "z", path, line_number)
            x = _unsigned(fields[positions["x"]], "x", path, line_number)
            y = _unsigned(fields[positions["y"]], "y", path, line_number)
            try:
                tile = TileKey(z, x, y)
            except ValueError as error:
                raise SourceSizeError(
                    f"invalid tile at {path}:{line_number}: {z}/{x}/{y}: {error}"
                ) from error
            yield tile.packed, _CatalogValue(
                source_sha256=_sha_bytes(
                    fields[positions["sourceSha256"]],
                    "sourceSha256",
                    path,
                    line_number,
                ),
                source_bytes=_unsigned(
                    fields[positions["sourceBytes"]], "sourceBytes", path, line_number
                ),
                decoded_bytes=_unsigned(
                    fields[positions["decodedBytes"]], "decodedBytes", path, line_number
                ),
                feature_count=_unsigned(
                    fields[positions["featureCount"]], "featureCount", path, line_number
                ),
            )


def _inventory_digest(files: Iterable[_InputFile], include_root: bool) -> str:
    digest = hashlib.sha256()
    for item in sorted(
        files,
        key=lambda entry: (
            str(entry.root).casefold() if include_root else "",
            entry.relative_path,
        ),
    ):
        parts = []
        if include_root:
            parts.append(str(item.root).encode("utf-8"))
        parts.append(item.relative_path.encode("utf-8"))
        for part in parts:
            digest.update(len(part).to_bytes(4, "big"))
            digest.update(part)
        digest.update(item.size.to_bytes(8, "big"))
        digest.update(bytes.fromhex(item.sha256))
    return digest.hexdigest()


def _discover_indexes(roots: Sequence[Path]) -> tuple[list[Path], list[_InputFile]]:
    if not roots:
        raise SourceSizeError("at least one shard root is required")
    resolved_roots = sorted(
        {_resolved_root(Path(root)) for root in roots},
        key=lambda path: str(path).casefold(),
    )
    inputs: list[_InputFile] = []
    for root in resolved_roots:
        candidates = sorted(
            (
                path
                for path in root.rglob("tile-index.tsv")
                if path.parent.name == "package"
            ),
            key=lambda path: path.relative_to(root).as_posix(),
        )
        if not candidates:
            raise SourceSizeError(f"shard root has no package/tile-index.tsv files: {root}")
        for path in candidates:
            stat = path.stat()
            inputs.append(
                _InputFile(
                    root=root,
                    relative_path=path.relative_to(root).as_posix(),
                    path=path,
                    size=stat.st_size,
                    sha256=_sha256_file(path),
                )
            )
    return resolved_roots, inputs


def _assert_duplicate_equal(
    tile: TileKey,
    existing: _CatalogValue,
    candidate: _CatalogValue,
) -> None:
    comparisons = (
        ("sourceSha256", existing.source_sha256, candidate.source_sha256),
        ("sourceBytes", existing.source_bytes, candidate.source_bytes),
        ("decodedBytes", existing.decoded_bytes, candidate.decoded_bytes),
        ("featureCount", existing.feature_count, candidate.feature_count),
    )
    for field, left, right in comparisons:
        if left != right:
            raise SourceSizeError(
                f"conflicting duplicate {field} for tile {tile.z}/{tile.x}/{tile.y}"
            )


def _open_atomic_text(path: Path):
    return tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    )


def _input_summary(roots: Sequence[Path], inputs: Sequence[_InputFile]) -> dict[str, object]:
    root_documents: list[dict[str, object]] = []
    for root in roots:
        root_files = [item for item in inputs if item.root == root]
        root_documents.append(
            {
                "path": str(root),
                "fileCount": len(root_files),
                "bytes": sum(item.size for item in root_files),
                "inventorySha256": _inventory_digest(root_files, include_root=False),
            }
        )
    file_documents = [
        {
            "root": str(item.root),
            "relativePath": item.relative_path,
            "bytes": item.size,
            "sha256": item.sha256,
        }
        for item in sorted(inputs, key=lambda entry: (str(entry.root).casefold(), entry.relative_path))
    ]
    return {
        "inputRoots": root_documents,
        "inputFiles": file_documents,
        "inputFileCount": len(inputs),
        "inputFileInventorySha256": _inventory_digest(inputs, include_root=True),
    }


def build_source_size_catalog(
    population_path: Path,
    shard_roots: Sequence[Path],
    output_dir: Path,
) -> SourceSizeSummary:
    """Recover one hash-bound source-size record for every indexed population tile."""

    population = _resolved_file(Path(population_path), "population")
    population_keys, population_sha256, population_counts = _load_population(population)
    roots, input_files = _discover_indexes([Path(root) for root in shard_roots])

    groups: dict[tuple[str, int, str], list[_InputFile]] = {}
    for item in input_files:
        groups.setdefault((item.relative_path, item.size, item.sha256), []).append(item)

    catalog: dict[int, _CatalogValue] = {}
    physical_rows = 0
    duplicate_copies = 0
    for group_key in sorted(groups):
        copies = groups[group_key]
        representative = min(copies, key=lambda item: str(item.path).casefold())
        representative_rows = 0
        for packed, value in _parse_tile_index(representative.path):
            representative_rows += 1
            physical_rows += 1
            if packed not in population_keys:
                tile = TileKey.from_packed(packed)
                raise SourceSizeError(
                    f"catalog coordinate outside population: {tile.z}/{tile.x}/{tile.y}"
                )
            existing = catalog.get(packed)
            if existing is None:
                catalog[packed] = value
            else:
                _assert_duplicate_equal(TileKey.from_packed(packed), existing, value)
                duplicate_copies += 1
        skipped_copies = len(copies) - 1
        if skipped_copies:
            physical_rows += representative_rows * skipped_copies
            duplicate_copies += representative_rows * skipped_copies

    missing_packed = sorted(population_keys.difference(catalog))
    missing_tiles = tuple(TileKey.from_packed(packed) for packed in missing_packed)
    catalog_counts = dict(
        sorted(Counter(TileKey.from_packed(packed).z for packed in catalog).items())
    )
    if physical_rows - len(catalog) != duplicate_copies:
        raise SourceSizeError(
            "duplicate accounting mismatch: "
            f"{physical_rows} - {len(catalog)} != {duplicate_copies}"
        )
    if len(catalog) + len(missing_tiles) != len(population_keys):
        raise SourceSizeError("catalog/population reconciliation failed")

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    tsv_path = output / "source-sizes.tsv"
    summary_path = output / "source-sizes-summary.json"
    tsv_temporary: Path | None = None
    summary_temporary: Path | None = None
    tsv_replaced = False
    try:
        with _open_atomic_text(tsv_path) as temporary:
            tsv_temporary = Path(temporary.name)
            temporary.write(OUTPUT_HEADER)
            for packed in sorted(catalog):
                tile = TileKey.from_packed(packed)
                value = catalog[packed]
                temporary.write(
                    f"{tile.z}\t{tile.x}\t{tile.y}\t{value.source_sha256.hex()}\t"
                    f"{value.source_bytes}\t{value.decoded_bytes}\t{value.feature_count}\n"
                )
            temporary.flush()
            os.fsync(temporary.fileno())
        output_sha256 = _sha256_file(tsv_temporary)
        output_bytes = tsv_temporary.stat().st_size

        input_document = _input_summary(roots, input_files)
        document: dict[str, object] = {
            "schemaVersion": 1,
            "populationPath": str(population),
            "populationSha256": population_sha256,
            "populationRowCount": len(population_keys),
            "populationCountsByZoom": {
                str(zoom): count for zoom, count in population_counts.items()
            },
            "physicalRowCount": physical_rows,
            "uniqueTileCount": len(catalog),
            "duplicateCopies": duplicate_copies,
            "countsByZoom": {
                str(zoom): count for zoom, count in catalog_counts.items()
            },
            "missingPopulationCount": len(missing_tiles),
            "missingPopulationTiles": [
                f"{tile.z}/{tile.x}/{tile.y}" for tile in missing_tiles
            ],
            "uniqueInputFileCount": len(groups),
            "duplicateInputFileCopies": len(input_files) - len(groups),
            "outputFile": tsv_path.name,
            "outputBytes": output_bytes,
            "outputSha256": output_sha256,
            **input_document,
        }
        with _open_atomic_text(summary_path) as temporary:
            summary_temporary = Path(temporary.name)
            json.dump(document, temporary, indent=2, sort_keys=True)
            temporary.write("\n")
            temporary.flush()
            os.fsync(temporary.fileno())

        os.replace(tsv_temporary, tsv_path)
        tsv_temporary = None
        tsv_replaced = True
        os.replace(summary_temporary, summary_path)
        summary_temporary = None
    except BaseException:
        if tsv_replaced:
            summary_path.unlink(missing_ok=True)
        raise
    finally:
        if tsv_temporary is not None:
            tsv_temporary.unlink(missing_ok=True)
        if summary_temporary is not None:
            summary_temporary.unlink(missing_ok=True)

    return SourceSizeSummary(
        physical_row_count=physical_rows,
        unique_tile_count=len(catalog),
        duplicate_copies=duplicate_copies,
        counts_by_zoom=catalog_counts,
        missing_population_tiles=missing_tiles,
        population_sha256=population_sha256,
        output_sha256=output_sha256,
    )


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Recover the Experiment 8 source-size catalog from Experiment 6 indexes."
    )
    parser.add_argument("--population", required=True, type=Path)
    parser.add_argument("--shard-root", required=True, action="append", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _parser().parse_args(argv)
    try:
        build_source_size_catalog(
            arguments.population,
            arguments.shard_root,
            arguments.out,
        )
    except (OSError, SourceSizeError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
