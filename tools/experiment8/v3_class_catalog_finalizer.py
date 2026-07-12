from __future__ import annotations

import argparse
import hashlib
import json
import os
import sqlite3
import stat
import struct
import tempfile
import unicodedata
import zlib
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path

from .model import TileKey
from .reference_presentation_policy import (
    PRESENTATION_POLICY_SHA256,
    ReferenceClassCatalog,
    SemanticSubtype,
    SubtypeCatalogCounts,
    canonical_class_catalog_bytes,
)
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    INDEX_FLAG_PRESENT,
    MAX_TILE_BYTES,
    PAYLOAD_SCHEMA,
    SOURCED_TEXT_POLICY_SHA256,
    decode_tile_payload,
    raw_hash32,
)
from .semantic_model import renderer_order_key, renderer_record_bytes, variant_fingerprint
from .sourced_text import UNICODE_SCRIPT_PROFILE_SHA256


CATALOG_FILE_NAME = "class-catalog.bin"
RECEIPT_FILE_NAME = "class-catalog-finalization-receipt.json"

_RECEIPT_SCHEMA = "flightalert.experiment8.v3-class-catalog-finalization-receipt.v1"
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_MAX_MANIFEST_BYTES = 16 * 1024 * 1024
_FILE_HASH_CHUNK_BYTES = 4 * 1024 * 1024
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_ANDROID_MAX_RECORD_OFFSET = (1 << 63) - 1
_MAX_COMPRESSED_TILE_BYTES = (
    MAX_TILE_BYTES
    + (MAX_TILE_BYTES >> 12)
    + (MAX_TILE_BYTES >> 14)
    + (MAX_TILE_BYTES >> 25)
    + 13
)
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_U64_MAX = (1 << 64) - 1
_REPARSE_POINT_ATTRIBUTE = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


class V3ClassCatalogFinalizationError(ValueError):
    """A V3 package cannot publish a truthful Experiment 8 class catalog."""


@dataclass(frozen=True, order=True, slots=True)
class _Window:
    z: int
    x_min: int
    x_max: int
    y_min: int
    y_max: int

    @property
    def tile_count(self) -> int:
        return (self.x_max - self.x_min + 1) * (self.y_max - self.y_min + 1)

    def ordinal(self, tile: TileKey) -> int:
        width = self.x_max - self.x_min + 1
        return (tile.y - self.y_min) * width + tile.x - self.x_min


@dataclass(frozen=True, slots=True)
class _Range:
    window: _Window
    first_ordinal: int


@dataclass(frozen=True, slots=True)
class _FileBinding:
    path: Path
    byte_length: int
    sha256: str
    device: int
    inode: int


@dataclass(frozen=True, slots=True)
class _OwnedStagedFile:
    path: Path
    identity: tuple[int, int, int, int]


@dataclass(frozen=True, slots=True)
class _Package:
    directory: Path
    package_id: str
    manifest: dict[str, object]
    manifest_raw: bytes
    base_manifest_bytes: bytes
    renderer_semantic_stream_sha256: str
    ranges: tuple[_Range, ...]
    tile_count: int
    complete_declared_scope: bool
    manifest_file: _FileBinding
    records_file: _FileBinding
    index_file: _FileBinding
    existing_catalog_sha256: str | None


@dataclass(frozen=True, slots=True)
class _Audit:
    semantic_sha256: str
    subtype_counts: Mapping[SemanticSubtype, SubtypeCatalogCounts]
    present_tile_count: int
    missing_tile_count: int
    renderer_record_count: int


@dataclass(frozen=True, slots=True)
class FinalizationResult:
    package_directory: Path
    catalog_sha256: str
    manifest_sha256: str
    receipt: Mapping[str, object]


def _canonical_json_bytes(document: object) -> bytes:
    try:
        return (
            json.dumps(
                document,
                allow_nan=False,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, UnicodeError, ValueError) as error:
        raise V3ClassCatalogFinalizationError(
            "canonical JSON value is unsupported"
        ) from error


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise V3ClassCatalogFinalizationError(
                f"JSON contains duplicate key {key!r}"
            )
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise V3ClassCatalogFinalizationError(
        f"JSON contains forbidden numeric constant {value}"
    )


def _strict_json(raw: bytes, label: str) -> dict[str, object]:
    if type(raw) is not bytes:
        raise V3ClassCatalogFinalizationError(f"{label} must be immutable bytes")
    try:
        document = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except V3ClassCatalogFinalizationError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} is not strict UTF-8 JSON"
        ) from error
    if type(document) is not dict:
        raise V3ClassCatalogFinalizationError(f"{label} root must be an object")
    return document


def _exact_int(value: object, label: str, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise V3ClassCatalogFinalizationError(
            f"{label} must be an exact integer in [{minimum}, {maximum}]"
        )
    return value


def _exact_bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise V3ClassCatalogFinalizationError(f"{label} must be Boolean")
    return value


def _exact_text(value: object, label: str) -> str:
    if type(value) is not str or not value:
        raise V3ClassCatalogFinalizationError(f"{label} must be nonempty text")
    if unicodedata.normalize("NFC", value) != value:
        raise V3ClassCatalogFinalizationError(f"{label} must be NFC")
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        raise V3ClassCatalogFinalizationError(
            f"{label} contains invalid Unicode"
        )
    return value


def _sha256_text(value: object, label: str) -> str:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise V3ClassCatalogFinalizationError(
            f"{label} must be one lowercase SHA-256"
        )
    return value


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(_FILE_HASH_CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"cannot hash {path.name}: {error}"
        ) from error
    return digest.hexdigest()


def _file_binding(path: Path) -> _FileBinding:
    try:
        status = path.stat()
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"cannot inspect {path.name}: {error}"
        ) from error
    return _FileBinding(
        path=path,
        byte_length=status.st_size,
        sha256=_sha256_file(path),
        device=status.st_dev,
        inode=status.st_ino,
    )


def _validate_package_id(value: object) -> str:
    package_id = _exact_text(value, "V3 package ID")
    if package_id in {".", ".."} or any(
        character in package_id for character in ("/", "\\", "\0")
    ):
        raise V3ClassCatalogFinalizationError("V3 package ID is path-unsafe")
    return package_id


def _validate_declared_runtime_binding(
    manifest: Mapping[str, object],
    records: _FileBinding,
    index: _FileBinding,
) -> None:
    merge = manifest.get("merge")
    if merge is None:
        return
    if type(merge) is not dict:
        raise V3ClassCatalogFinalizationError("V3 merge metadata must be an object")
    output = merge.get("output")
    if type(output) is not dict:
        raise V3ClassCatalogFinalizationError(
            "V3 merge output binding must be an object"
        )
    declarations = (
        ("recordsBytes", records.byte_length, "V3 declared records byte count"),
        ("tileIndexBytes", index.byte_length, "V3 declared index byte count"),
    )
    for key, actual, label in declarations:
        if _exact_int(output.get(key), label, 0, _ANDROID_MAX_RECORD_OFFSET) != actual:
            raise V3ClassCatalogFinalizationError(f"{label} differs from its file")
    hashes = (
        ("recordsSha256", records.sha256, "V3 declared records SHA-256"),
        ("tileIndexSha256", index.sha256, "V3 declared index SHA-256"),
    )
    for key, actual, label in hashes:
        if _sha256_text(output.get(key), label) != actual:
            raise V3ClassCatalogFinalizationError(f"{label} differs from its file")


def _load_package(directory: Path) -> _Package:
    if not isinstance(directory, Path):
        raise V3ClassCatalogFinalizationError(
            "V3 package directory must be a pathlib.Path"
        )
    try:
        directory = directory.resolve(strict=True)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "V3 package directory is not readable"
        ) from error
    if not directory.is_dir():
        raise V3ClassCatalogFinalizationError("V3 package directory is not readable")
    manifest_path = directory / "manifest.json"
    records_path = directory / "records.fadictpack"
    index_path = directory / "tile-index.bin"
    if not all(path.is_file() for path in (manifest_path, records_path, index_path)):
        raise V3ClassCatalogFinalizationError(
            "V3 package lacks its three runtime files"
        )
    if any(path.is_symlink() for path in (manifest_path, records_path, index_path)):
        raise V3ClassCatalogFinalizationError("V3 runtime file aliases are unsupported")
    try:
        manifest_size = manifest_path.stat().st_size
        if not 0 < manifest_size <= _MAX_MANIFEST_BYTES:
            raise V3ClassCatalogFinalizationError(
                "V3 manifest byte length is outside its bound"
            )
        with manifest_path.open("rb") as handle:
            manifest_raw = handle.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as error:
        raise V3ClassCatalogFinalizationError("V3 manifest is not readable") from error
    if len(manifest_raw) != manifest_size:
        raise V3ClassCatalogFinalizationError("V3 manifest changed while being read")
    manifest = _strict_json(manifest_raw, "V3 manifest")
    if _canonical_json_bytes(manifest) != manifest_raw:
        raise V3ClassCatalogFinalizationError("V3 manifest is not canonical JSON")
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 3:
        raise V3ClassCatalogFinalizationError(
            "V3 manifest schemaVersion must be exactly 3"
        )
    if manifest.get("payloadSchema") != PAYLOAD_SCHEMA:
        raise V3ClassCatalogFinalizationError("V3 payload schema is unsupported")
    if manifest.get("presentationPolicySha256") != PRESENTATION_POLICY_SHA256:
        raise V3ClassCatalogFinalizationError(
            "V3 presentation policy identity differs"
        )
    if manifest.get("sourcedTextPolicySha256") != SOURCED_TEXT_POLICY_SHA256:
        raise V3ClassCatalogFinalizationError("V3 sourced-text policy identity differs")
    if manifest.get("unicodeScriptProfileSha256") != UNICODE_SCRIPT_PROFILE_SHA256:
        raise V3ClassCatalogFinalizationError(
            "V3 Unicode script profile identity differs"
        )
    if manifest.get("compatibility") != {"emptyPresentTilesSharePayload": False}:
        raise V3ClassCatalogFinalizationError(
            "V3 compatibility contract is unsupported"
        )
    package_id = _validate_package_id(manifest.get("packageId"))
    renderer_semantic = _sha256_text(
        manifest.get("rendererSemanticStreamSha256"),
        "V3 renderer semantic stream SHA-256",
    )

    coverage = manifest.get("coverage")
    if type(coverage) is not dict:
        raise V3ClassCatalogFinalizationError("V3 coverage must be an object")
    complete_declared = _exact_bool(
        coverage.get("completeDeclaredScope"), "V3 complete-declared-scope claim"
    )
    complete_whole = _exact_bool(
        coverage.get("completeWholeEarthDictionary"),
        "V3 complete-whole-earth claim",
    )
    raw_ranges = coverage.get("zoomRanges")
    if type(raw_ranges) is not list or not raw_ranges:
        raise V3ClassCatalogFinalizationError(
            "V3 zoom ranges must be a nonempty array"
        )
    ranges: list[_Range] = []
    first_ordinal = 0
    previous_zoom = -1
    for range_number, raw_range in enumerate(raw_ranges):
        if type(raw_range) is not dict:
            raise V3ClassCatalogFinalizationError(
                f"V3 zoom range {range_number} must be an object"
            )
        z = _exact_int(raw_range.get("z"), "V3 range zoom", 0, 29)
        if z <= previous_zoom:
            raise V3ClassCatalogFinalizationError(
                "V3 zoom ranges are not strictly ordered"
            )
        previous_zoom = z
        limit = (1 << z) - 1
        window = _Window(
            z=z,
            x_min=_exact_int(raw_range.get("xMin"), "V3 range xMin", 0, limit),
            x_max=_exact_int(raw_range.get("xMax"), "V3 range xMax", 0, limit),
            y_min=_exact_int(raw_range.get("yMin"), "V3 range yMin", 0, limit),
            y_max=_exact_int(raw_range.get("yMax"), "V3 range yMax", 0, limit),
        )
        if window.x_min > window.x_max or window.y_min > window.y_max:
            raise V3ClassCatalogFinalizationError("V3 range bounds are reversed")
        if raw_range.get("tileCount") != window.tile_count:
            raise V3ClassCatalogFinalizationError(
                "V3 range tileCount is inconsistent"
            )
        ranges.append(_Range(window, first_ordinal))
        first_ordinal += window.tile_count
        if first_ordinal > _ANDROID_MAX_RECORD_OFFSET:
            raise V3ClassCatalogFinalizationError("V3 coverage tile count is too large")
    tile_count = _exact_int(
        coverage.get("tileCount"),
        "V3 coverage tileCount",
        1,
        _ANDROID_MAX_RECORD_OFFSET,
    )
    if tile_count != first_ordinal:
        raise V3ClassCatalogFinalizationError(
            "V3 coverage tileCount differs from its ranges"
        )
    if complete_whole and (
        not complete_declared
        or any(
            item.window.x_min != 0
            or item.window.y_min != 0
            or item.window.x_max != (1 << item.window.z) - 1
            or item.window.y_max != (1 << item.window.z) - 1
            for item in ranges
        )
    ):
        raise V3ClassCatalogFinalizationError(
            "V3 whole-earth claim is not full-world"
        )

    records_file = _file_binding(records_path)
    index_file = _file_binding(index_path)
    expected_index_bytes = tile_count * INDEX_ENTRY_BYTES
    if expected_index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise V3ClassCatalogFinalizationError(
            "V3 Android index exceeds its byte-array bound"
        )
    if index_file.byte_length != expected_index_bytes:
        raise V3ClassCatalogFinalizationError(
            "V3 binary index length differs from coverage"
        )
    if records_file.byte_length > _ANDROID_MAX_RECORD_OFFSET:
        raise V3ClassCatalogFinalizationError(
            "V3 records pack exceeds its Android offset bound"
        )
    _validate_declared_runtime_binding(manifest, records_file, index_file)

    existing_catalog_sha256 = None
    existing_catalog = manifest.get("classCatalog")
    if existing_catalog is not None:
        if type(existing_catalog) is not dict or set(existing_catalog) != {
            "catalogSha256",
            "rendererContractSha256",
        }:
            raise V3ClassCatalogFinalizationError(
                "V3 classCatalog binding must contain exactly its two fields"
            )
        existing_catalog_sha256 = _sha256_text(
            existing_catalog.get("catalogSha256"),
            "V3 class catalog SHA-256",
        )
        renderer_contract = _sha256_text(
            existing_catalog.get("rendererContractSha256"),
            "V3 renderer contract SHA-256 alias",
        )
        if renderer_contract != renderer_semantic:
            raise V3ClassCatalogFinalizationError(
                "V3 rendererContractSha256 alias differs from "
                "rendererSemanticStreamSha256"
            )

    base_manifest = dict(manifest)
    base_manifest.pop("classCatalog", None)
    manifest_file = _file_binding(manifest_path)
    if manifest_file.sha256 != hashlib.sha256(manifest_raw).hexdigest():
        raise V3ClassCatalogFinalizationError("V3 manifest changed while being loaded")
    return _Package(
        directory=directory,
        package_id=package_id,
        manifest=manifest,
        manifest_raw=manifest_raw,
        base_manifest_bytes=_canonical_json_bytes(base_manifest),
        renderer_semantic_stream_sha256=renderer_semantic,
        ranges=tuple(ranges),
        tile_count=tile_count,
        complete_declared_scope=complete_declared,
        manifest_file=manifest_file,
        records_file=records_file,
        index_file=index_file,
        existing_catalog_sha256=existing_catalog_sha256,
    )


def _validate_index(package: _Package) -> tuple[int, int]:
    expected_offset = 0
    present_tiles = 0
    missing_tiles = 0
    digest = hashlib.sha256()
    try:
        with package.index_file.path.open("rb") as handle:
            for ordinal in range(package.tile_count):
                entry = handle.read(INDEX_ENTRY_BYTES)
                if len(entry) != INDEX_ENTRY_BYTES:
                    raise V3ClassCatalogFinalizationError(
                        f"V3 index ended early at ordinal {ordinal}"
                    )
                digest.update(entry)
                if entry == _ZERO_INDEX_ENTRY:
                    missing_tiles += 1
                    continue
                offset, compressed_length, raw_length, _raw_hash, flags = struct.unpack(
                    "<QIIII", entry
                )
                if flags != INDEX_FLAG_PRESENT:
                    raise V3ClassCatalogFinalizationError(
                        f"V3 index flags are unsupported at ordinal {ordinal}"
                    )
                if offset != expected_offset:
                    raise V3ClassCatalogFinalizationError(
                        "V3 record offsets are not exact and contiguous"
                    )
                if not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES:
                    raise V3ClassCatalogFinalizationError(
                        "V3 compressed tile length exceeds its Android bound"
                    )
                if not 0 < raw_length <= MAX_TILE_BYTES:
                    raise V3ClassCatalogFinalizationError(
                        "V3 raw tile length exceeds its Android bound"
                    )
                if offset > _ANDROID_MAX_RECORD_OFFSET - compressed_length:
                    raise V3ClassCatalogFinalizationError(
                        "V3 record range overflows its Android offset bound"
                    )
                expected_offset += compressed_length
                if expected_offset > package.records_file.byte_length:
                    raise V3ClassCatalogFinalizationError(
                        "V3 record range exceeds its pack"
                    )
                present_tiles += 1
            if handle.read(1):
                raise V3ClassCatalogFinalizationError(
                    "V3 index contains trailing bytes"
                )
    except OSError as error:
        raise V3ClassCatalogFinalizationError("V3 index is not readable") from error
    if expected_offset != package.records_file.byte_length:
        raise V3ClassCatalogFinalizationError(
            "V3 records pack has unreferenced or missing bytes"
        )
    if package.complete_declared_scope and missing_tiles:
        raise V3ClassCatalogFinalizationError(
            "V3 complete-declared package contains a missing tile"
        )
    if digest.hexdigest() != package.index_file.sha256:
        raise V3ClassCatalogFinalizationError("V3 index changed during validation")
    return present_tiles, missing_tiles


def _semantic_tiles(package: _Package):
    for item in package.ranges:
        window = item.window
        for x in range(window.x_min, window.x_max + 1):
            for y in range(window.y_min, window.y_max + 1):
                tile = TileKey(window.z, x, y)
                yield tile, item.first_ordinal + window.ordinal(tile)


def _inflate_exact(compressed: bytes, raw_length: int, tile: TileKey) -> bytes:
    inflater = zlib.decompressobj(wbits=-zlib.MAX_WBITS)
    try:
        raw = inflater.decompress(compressed, raw_length + 1)
        if inflater.unconsumed_tail:
            raise V3ClassCatalogFinalizationError(
                f"V3 tile {tile.z}/{tile.x}/{tile.y} DEFLATE exceeds its declared length"
            )
        raw += inflater.flush()
    except zlib.error as error:
        raise V3ClassCatalogFinalizationError(
            f"V3 tile {tile.z}/{tile.x}/{tile.y} DEFLATE is corrupt"
        ) from error
    if not inflater.eof or inflater.unused_data or len(raw) != raw_length:
        raise V3ClassCatalogFinalizationError(
            f"V3 tile {tile.z}/{tile.x}/{tile.y} DEFLATE length is dishonest"
        )
    return raw


def _audit_package(package: _Package) -> _Audit:
    present_tiles, missing_tiles = _validate_index(package)
    semantic_digest = hashlib.sha256()
    semantic_digest.update(_SEMANTIC_STREAM_DOMAIN)
    posting_counts: Counter[SemanticSubtype] = Counter()
    renderer_record_count = 0
    with tempfile.TemporaryDirectory(prefix="flightalert-exp8-catalog-") as temporary:
        database_path = Path(temporary) / "identity-counts.sqlite"
        connection = sqlite3.connect(database_path)
        try:
            connection.execute("PRAGMA journal_mode=OFF")
            connection.execute("PRAGMA synchronous=OFF")
            connection.execute("PRAGMA temp_store=FILE")
            connection.execute(
                "CREATE TABLE features ("
                "subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
                "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
            )
            connection.execute(
                "CREATE TABLE variants ("
                "subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
                "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
            )
            connection.execute(
                "CREATE TABLE variant_addresses ("
                "identity BLOB NOT NULL, digest BLOB NOT NULL, "
                "PRIMARY KEY (identity, digest)) WITHOUT ROWID"
            )
            with (
                package.index_file.path.open("rb") as index_handle,
                package.records_file.path.open("rb") as records_handle,
            ):
                decoded_present_tiles = 0
                for tile_number, (tile, ordinal) in enumerate(
                    _semantic_tiles(package), start=1
                ):
                    index_handle.seek(ordinal * INDEX_ENTRY_BYTES)
                    entry = index_handle.read(INDEX_ENTRY_BYTES)
                    if len(entry) != INDEX_ENTRY_BYTES:
                        raise V3ClassCatalogFinalizationError(
                            "V3 index ended early during semantic audit"
                        )
                    if entry == _ZERO_INDEX_ENTRY:
                        continue
                    (
                        offset,
                        compressed_length,
                        raw_length,
                        expected_hash32,
                        flags,
                    ) = struct.unpack("<QIIII", entry)
                    if (
                        flags != INDEX_FLAG_PRESENT
                        or not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES
                        or not 0 < raw_length <= MAX_TILE_BYTES
                        or offset > package.records_file.byte_length
                        or compressed_length
                        > package.records_file.byte_length - offset
                    ):
                        raise V3ClassCatalogFinalizationError(
                            "V3 index entry changed after structural validation"
                        )
                    records_handle.seek(offset)
                    compressed = records_handle.read(compressed_length)
                    if len(compressed) != compressed_length:
                        raise V3ClassCatalogFinalizationError(
                            "V3 records pack ended early during semantic audit"
                        )
                    payload = _inflate_exact(compressed, raw_length, tile)
                    if raw_hash32(payload) != expected_hash32:
                        raise V3ClassCatalogFinalizationError(
                            f"V3 tile {tile.z}/{tile.x}/{tile.y} integrity hash differs"
                        )
                    try:
                        decoded = decode_tile_payload(tile, payload)
                    except ValueError as error:
                        raise V3ClassCatalogFinalizationError(
                            f"V3 tile {tile.z}/{tile.x}/{tile.y} is not canonical: {error}"
                        ) from error
                    ordered = sorted(
                        (item.renderer_record for item in decoded.records),
                        key=renderer_order_key,
                    )
                    feature_rows: list[tuple[int, bytes]] = []
                    variant_rows: list[tuple[int, bytes]] = []
                    address_rows: list[tuple[bytes, bytes]] = []
                    for record in ordered:
                        try:
                            subtype = SemanticSubtype(record.variant.semantic_subtype)
                        except ValueError as error:
                            raise V3ClassCatalogFinalizationError(
                                "V3 renderer semantic subtype is outside the 23-class policy"
                            ) from error
                        canonical = renderer_record_bytes(record)
                        body = struct.pack("<Q", tile.packed) + canonical
                        if len(body) > (1 << 32) - 1:
                            raise V3ClassCatalogFinalizationError(
                                "V3 semantic stream item exceeds its u32 bound"
                            )
                        semantic_digest.update(struct.pack("<I", len(body)))
                        semantic_digest.update(body)
                        posting_counts[subtype] += 1
                        renderer_record_count += 1
                        feature_identity = record.posting.feature_id.to_bytes(8, "big")
                        variant_identity = record.variant.canonical_variant_id.to_bytes(
                            8, "big"
                        )
                        feature_rows.append((subtype.value, feature_identity))
                        variant_rows.append((subtype.value, variant_identity))
                        address_rows.append(
                            (
                                variant_identity,
                                variant_fingerprint(record.variant).full_sha256,
                            )
                        )
                    connection.executemany(
                        "INSERT OR IGNORE INTO features (subtype, identity) VALUES (?, ?)",
                        feature_rows,
                    )
                    connection.executemany(
                        "INSERT OR IGNORE INTO variants (subtype, identity) VALUES (?, ?)",
                        variant_rows,
                    )
                    connection.executemany(
                        "INSERT OR IGNORE INTO variant_addresses (identity, digest) VALUES (?, ?)",
                        address_rows,
                    )
                    decoded_present_tiles += 1
                    if tile_number % 4096 == 0:
                        connection.commit()
                if decoded_present_tiles != present_tiles:
                    raise V3ClassCatalogFinalizationError(
                        "V3 present tile count changed during semantic audit"
                    )
            connection.commit()
            collision = connection.execute(
                "SELECT identity FROM variant_addresses "
                "GROUP BY identity HAVING COUNT(*) != 1 LIMIT 1"
            ).fetchone()
            if collision is not None:
                raise V3ClassCatalogFinalizationError(
                    "V3 canonical variant hot-ID collision is unresolved"
                )
            feature_counts = {
                int(subtype): int(count)
                for subtype, count in connection.execute(
                    "SELECT subtype, COUNT(*) FROM features GROUP BY subtype"
                )
            }
            variant_counts = {
                int(subtype): int(count)
                for subtype, count in connection.execute(
                    "SELECT subtype, COUNT(*) FROM variants GROUP BY subtype"
                )
            }
        finally:
            connection.close()
    counts = {
        subtype: SubtypeCatalogCounts(
            distinct_feature_count=feature_counts.get(subtype.value, 0),
            canonical_variant_count=variant_counts.get(subtype.value, 0),
            posting_count=posting_counts[subtype],
        )
        for subtype in SemanticSubtype
    }
    return _Audit(
        semantic_sha256=semantic_digest.hexdigest(),
        subtype_counts=counts,
        present_tile_count=present_tiles,
        missing_tile_count=missing_tiles,
        renderer_record_count=renderer_record_count,
    )


def _counts_document(
    counts: Mapping[SemanticSubtype, SubtypeCatalogCounts],
) -> list[dict[str, object]]:
    return [
        {
            "semanticSubtype": subtype.value,
            "semanticSubtypeName": subtype.name,
            "distinctFeatureIds": counts[subtype].distinct_feature_count,
            "canonicalVariantIds": counts[subtype].canonical_variant_count,
            "postings": counts[subtype].posting_count,
        }
        for subtype in SemanticSubtype
    ]


def _validate_existing_catalog(package: _Package, audit: _Audit) -> None:
    if package.existing_catalog_sha256 is None:
        return
    catalog_path = package.directory / CATALOG_FILE_NAME
    if not catalog_path.is_file() or catalog_path.is_symlink():
        raise V3ClassCatalogFinalizationError(
            "V3 manifest declares a missing or aliased class catalog"
        )
    try:
        if catalog_path.stat().st_size != 754:
            raise V3ClassCatalogFinalizationError(
                "V3 existing class catalog is not exactly 754 bytes"
            )
        with catalog_path.open("rb") as catalog_handle:
            catalog_bytes = catalog_handle.read(755)
        if len(catalog_bytes) != 754:
            raise V3ClassCatalogFinalizationError(
                "V3 existing class catalog is not exactly 754 bytes"
            )
        catalog = ReferenceClassCatalog.from_verified_bytes(
            catalog_bytes,
            expected_catalog_sha256=package.existing_catalog_sha256,
            expected_renderer_semantic_stream_sha256=(
                package.renderer_semantic_stream_sha256
            ),
            expected_renderer_contract_sha256=(
                package.renderer_semantic_stream_sha256
            ),
            expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
        )
    except V3ClassCatalogFinalizationError:
        raise
    except (OSError, ValueError) as error:
        raise V3ClassCatalogFinalizationError(
            f"V3 existing class catalog is corrupt: {error}"
        ) from error
    if dict(catalog.subtype_counts) != dict(audit.subtype_counts):
        raise V3ClassCatalogFinalizationError(
            "V3 existing class catalog counts differ from the package"
        )


def _finalizer_sha256() -> str:
    return _sha256_file(Path(__file__))


def _receipt_document(
    *,
    package: _Package,
    audit: _Audit,
    catalog_sha256: str,
    catalog_bytes: bytes,
    manifest_sha256: str,
    manifest_bytes: bytes,
) -> dict[str, object]:
    return {
        "schema": _RECEIPT_SCHEMA,
        "packageId": package.package_id,
        "finalizerSha256": _finalizer_sha256(),
        "inputFiles": [
            {
                "name": "manifest.json",
                "role": "base-manifest-without-class-catalog",
                "bytes": len(package.base_manifest_bytes),
                "sha256": hashlib.sha256(package.base_manifest_bytes).hexdigest(),
            },
            {
                "name": "records.fadictpack",
                "bytes": package.records_file.byte_length,
                "sha256": package.records_file.sha256,
            },
            {
                "name": "tile-index.bin",
                "bytes": package.index_file.byte_length,
                "sha256": package.index_file.sha256,
            },
        ],
        "coverage": {
            "declaredTileCount": package.tile_count,
            "presentTileCount": audit.present_tile_count,
            "missingTileCount": audit.missing_tile_count,
            "rendererRecordCount": audit.renderer_record_count,
        },
        "rendererSemanticStreamSha256": audit.semantic_sha256,
        "rendererContractSha256": audit.semantic_sha256,
        "subtypeCounts": _counts_document(audit.subtype_counts),
        "outputFiles": [
            {
                "name": "manifest.json",
                "bytes": len(manifest_bytes),
                "sha256": manifest_sha256,
            },
            {
                "name": "records.fadictpack",
                "bytes": package.records_file.byte_length,
                "sha256": package.records_file.sha256,
            },
            {
                "name": "tile-index.bin",
                "bytes": package.index_file.byte_length,
                "sha256": package.index_file.sha256,
            },
            {
                "name": CATALOG_FILE_NAME,
                "bytes": len(catalog_bytes),
                "sha256": catalog_sha256,
            },
        ],
    }


def _assert_file_binding(binding: _FileBinding) -> None:
    try:
        status = binding.path.stat()
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{binding.path.name} disappeared during finalization"
        ) from error
    if (
        status.st_size != binding.byte_length
        or status.st_dev != binding.device
        or status.st_ino != binding.inode
        or _sha256_file(binding.path) != binding.sha256
    ):
        raise V3ClassCatalogFinalizationError(
            f"{binding.path.name} changed during finalization"
        )


def _stat_is_reparse(information: os.stat_result) -> bool:
    return stat.S_ISLNK(information.st_mode) or bool(
        getattr(information, "st_file_attributes", 0)
        & _REPARSE_POINT_ATTRIBUTE
    )


def _stat_identity(information: os.stat_result) -> tuple[int, int, int, int]:
    return (
        information.st_dev,
        information.st_ino,
        stat.S_IFMT(information.st_mode),
        getattr(information, "st_file_attributes", 0),
    )


def _validate_owned_stage_stat(information: os.stat_result, label: str) -> None:
    if _stat_is_reparse(information) or not stat.S_ISREG(information.st_mode):
        raise V3ClassCatalogFinalizationError(
            f"{label} is not a real regular file"
        )
    if information.st_nlink != 1:
        raise V3ClassCatalogFinalizationError(
            f"{label} must have exactly one link"
        )


def _assert_owned_stage(stage: _OwnedStagedFile, label: str) -> None:
    try:
        information = os.lstat(stage.path)
    except FileNotFoundError as error:
        raise V3ClassCatalogFinalizationError(f"{label} is missing") from error
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} cannot be inspected"
        ) from error
    _validate_owned_stage_stat(information, label)
    if _stat_identity(information) != stage.identity:
        raise V3ClassCatalogFinalizationError(
            f"{label} identity changed before publication"
        )


def _remove_owned_stage(stage: _OwnedStagedFile) -> None:
    if not os.path.lexists(stage.path):
        return
    label = f"staged {stage.path.name}"
    try:
        information = os.lstat(stage.path)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} cannot be inspected before cleanup"
        ) from error
    _validate_owned_stage_stat(information, label)
    if _stat_identity(information) != stage.identity:
        raise V3ClassCatalogFinalizationError(
            f"{label} identity changed before cleanup"
        )
    try:
        stage.path.unlink()
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} could not be removed during cleanup"
        ) from error


def _cleanup_owned_stages(stages: Sequence[_OwnedStagedFile]) -> None:
    first_error: BaseException | None = None
    for stage in stages:
        try:
            _remove_owned_stage(stage)
        except BaseException as error:
            if first_error is None:
                first_error = error
    if first_error is not None:
        raise first_error


def _stage_bytes(path: Path, data: bytes) -> _OwnedStagedFile:
    descriptor, raw_path = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    staged_path = Path(raw_path)
    created_information = os.fstat(descriptor)
    _validate_owned_stage_stat(created_information, f"staged {path.name}")
    staged = _OwnedStagedFile(
        path=staged_path,
        identity=_stat_identity(created_information),
    )
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        _assert_owned_stage(staged, f"staged {path.name}")
        if staged.path.read_bytes() != data:
            raise V3ClassCatalogFinalizationError(
                f"staged {path.name} readback differs"
            )
        return staged
    except Exception:
        try:
            os.close(descriptor)
        except OSError:
            pass
        _remove_owned_stage(staged)
        raise


def _sync_posix_directory_metadata(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(directory, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _windows_replace_write_through(source: Path, destination: Path) -> None:
    import ctypes
    from ctypes import wintypes

    move_file_ex = ctypes.WinDLL("kernel32", use_last_error=True).MoveFileExW
    move_file_ex.argtypes = (
        wintypes.LPCWSTR,
        wintypes.LPCWSTR,
        wintypes.DWORD,
    )
    move_file_ex.restype = wintypes.BOOL
    movefile_replace_existing = 0x1
    movefile_write_through = 0x8
    if not move_file_ex(
        str(source),
        str(destination),
        movefile_replace_existing | movefile_write_through,
    ):
        raise ctypes.WinError(ctypes.get_last_error())


def _replace_staged(staged: _OwnedStagedFile, destination: Path) -> None:
    if staged.path.parent != destination.parent:
        raise V3ClassCatalogFinalizationError(
            f"atomic {destination.name} publication must stay in one directory"
        )
    _assert_owned_stage(staged, f"staged {destination.name}")
    try:
        if os.name == "nt":
            _windows_replace_write_through(staged.path, destination)
        elif os.name == "posix":
            os.replace(staged.path, destination)
            _sync_posix_directory_metadata(destination.parent)
        else:
            raise V3ClassCatalogFinalizationError(
                "durable class-catalog publication is unsupported on this platform"
            )
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"atomic {destination.name} publication failed: {error}"
        ) from error


def _invalidate_existing_receipt(receipt_path: Path) -> None:
    try:
        receipt_status = os.lstat(receipt_path)
    except FileNotFoundError:
        return
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "existing finalization receipt cannot be inspected"
        ) from error
    if not stat.S_ISREG(receipt_status.st_mode):
        raise V3ClassCatalogFinalizationError(
            "existing finalization receipt is not a regular file"
        )
    staged_marker = _stage_bytes(receipt_path, b"")
    try:
        _replace_staged(staged_marker, receipt_path)
    except Exception:
        _remove_owned_stage(staged_marker)
        raise
    _readback(receipt_path, b"", hashlib.sha256(b"").hexdigest())


def _readback(path: Path, expected: bytes, expected_sha256: str) -> None:
    try:
        information = os.lstat(path)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} is not readable"
        ) from error
    if (
        _stat_is_reparse(information)
        or not stat.S_ISREG(information.st_mode)
        or information.st_size != len(expected)
    ):
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} readback differs"
        )
    try:
        with path.open("rb") as handle:
            actual = handle.read(len(expected) + 1)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} is not readable"
        ) from error
    if actual != expected or hashlib.sha256(actual).hexdigest() != expected_sha256:
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} readback differs"
        )


def _notify(hook: Callable[[str], None] | None, event: str) -> None:
    if hook is not None:
        hook(event)


def finalize_v3_class_catalog(
    package_directory: Path,
    *,
    publication_hook: Callable[[str], None] | None = None,
) -> FinalizationResult:
    """Audit one existing V3 package and atomically publish its class catalog."""

    if publication_hook is not None and not callable(publication_hook):
        raise V3ClassCatalogFinalizationError("publication hook must be callable")
    package = _load_package(package_directory)
    audit = _audit_package(package)
    if audit.semantic_sha256 != package.renderer_semantic_stream_sha256:
        raise V3ClassCatalogFinalizationError(
            "V3 renderer semantic stream SHA-256 differs from the manifest"
        )
    _validate_existing_catalog(package, audit)

    catalog_bytes = canonical_class_catalog_bytes(
        renderer_semantic_stream_sha256=audit.semantic_sha256,
        renderer_contract_sha256=audit.semantic_sha256,
        presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
        subtype_counts=audit.subtype_counts,
    )
    if len(catalog_bytes) != 754:
        raise V3ClassCatalogFinalizationError(
            "canonical class catalog is not exactly 754 bytes"
        )
    catalog_sha256 = hashlib.sha256(catalog_bytes).hexdigest()
    final_manifest = dict(package.manifest)
    final_manifest["classCatalog"] = {
        "catalogSha256": catalog_sha256,
        "rendererContractSha256": audit.semantic_sha256,
    }
    final_manifest_bytes = _canonical_json_bytes(final_manifest)
    if len(final_manifest_bytes) > _MAX_MANIFEST_BYTES:
        raise V3ClassCatalogFinalizationError(
            "finalized V3 manifest byte length is outside its bound"
        )
    manifest_sha256 = hashlib.sha256(final_manifest_bytes).hexdigest()
    receipt = _receipt_document(
        package=package,
        audit=audit,
        catalog_sha256=catalog_sha256,
        catalog_bytes=catalog_bytes,
        manifest_sha256=manifest_sha256,
        manifest_bytes=final_manifest_bytes,
    )
    receipt_bytes = _canonical_json_bytes(receipt)

    catalog_path = package.directory / CATALOG_FILE_NAME
    manifest_path = package.directory / "manifest.json"
    receipt_path = package.directory / RECEIPT_FILE_NAME
    result = FinalizationResult(
        package_directory=package.directory,
        catalog_sha256=catalog_sha256,
        manifest_sha256=manifest_sha256,
        receipt=receipt,
    )
    staged_paths: list[_OwnedStagedFile] = []
    try:
        staged_catalog = _stage_bytes(catalog_path, catalog_bytes)
        staged_paths.append(staged_catalog)
        staged_manifest = _stage_bytes(manifest_path, final_manifest_bytes)
        staged_paths.append(staged_manifest)

        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)

        _notify(publication_hook, "before_catalog_replace")
        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _replace_staged(staged_catalog, catalog_path)
        staged_paths.remove(staged_catalog)
        _readback(catalog_path, catalog_bytes, catalog_sha256)
        _notify(publication_hook, "after_catalog_published")

        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)

        if manifest_path.read_bytes() != package.manifest_raw:
            raise V3ClassCatalogFinalizationError(
                "V3 manifest changed before its catalog binding publication"
            )
        _notify(publication_hook, "before_manifest_replace")
        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _invalidate_existing_receipt(receipt_path)
        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _readback(catalog_path, catalog_bytes, catalog_sha256)
        _replace_staged(staged_manifest, manifest_path)
        staged_paths.remove(staged_manifest)
        _readback(manifest_path, final_manifest_bytes, manifest_sha256)
        _notify(publication_hook, "after_manifest_published")

        _notify(publication_hook, "before_receipt_replace")
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _readback(catalog_path, catalog_bytes, catalog_sha256)
        _readback(manifest_path, final_manifest_bytes, manifest_sha256)
        staged_receipt = _stage_bytes(receipt_path, receipt_bytes)
        staged_paths.append(staged_receipt)
        try:
            _replace_staged(staged_receipt, receipt_path)
            staged_paths.remove(staged_receipt)
            _readback(
                receipt_path,
                receipt_bytes,
                hashlib.sha256(receipt_bytes).hexdigest(),
            )
            _notify(publication_hook, "after_receipt_published")

            _readback(
                receipt_path,
                receipt_bytes,
                hashlib.sha256(receipt_bytes).hexdigest(),
            )
            _assert_file_binding(package.records_file)
            _assert_file_binding(package.index_file)
            _readback(catalog_path, catalog_bytes, catalog_sha256)
            _readback(manifest_path, final_manifest_bytes, manifest_sha256)
        except BaseException:
            _invalidate_existing_receipt(receipt_path)
            raise
    finally:
        _cleanup_owned_stages(staged_paths)
    return result


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Independently audit an Experiment 8 V3 package and publish its "
            "canonical FAE8CAT1 class catalog."
        )
    )
    parser.add_argument("--package", required=True, type=Path)
    parsed = parser.parse_args(arguments)
    try:
        result = finalize_v3_class_catalog(parsed.package)
    except V3ClassCatalogFinalizationError as error:
        parser.exit(1, f"error: {error}\n")
    print(_canonical_json_bytes(result.receipt).decode("utf-8"), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())


__all__ = [
    "CATALOG_FILE_NAME",
    "RECEIPT_FILE_NAME",
    "FinalizationResult",
    "V3ClassCatalogFinalizationError",
    "finalize_v3_class_catalog",
]
