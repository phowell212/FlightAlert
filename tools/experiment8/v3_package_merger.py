from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
import shutil
import sqlite3
import struct
import unicodedata
import zlib
from collections import Counter
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path

from .model import TileKey
from .reference_presentation_policy import PRESENTATION_POLICY_SHA256, SemanticSubtype
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    INDEX_FLAG_PRESENT,
    MAX_RECORDS_PER_TILE,
    MAX_SOURCED_TEXT_RECORD_BYTES,
    MAX_TILE_BYTES,
    PAYLOAD_SCHEMA,
    SOURCED_TEXT_POLICY_SHA256,
    TILE_PAYLOAD_MAGIC,
    decode_tile_payload,
    encode_index_entry,
    raw_deflate,
    raw_hash32,
)
from .semantic_model import renderer_record_bytes, variant_fingerprint
from .sourced_text import UNICODE_SCRIPT_PROFILE_SHA256


_MERGE_SCHEMA = "flightalert.experiment8.v3-package-merge.v1"
_RECEIPT_SCHEMA = "flightalert.experiment8.v3-package-merge-receipt.v1"
_MAX_MANIFEST_BYTES = 16 * 1024 * 1024
_FILE_HASH_CHUNK_BYTES = 4 * 1024 * 1024
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_MAX_COMPRESSED_TILE_BYTES = (
    MAX_TILE_BYTES
    + (MAX_TILE_BYTES >> 12)
    + (MAX_TILE_BYTES >> 14)
    + (MAX_TILE_BYTES >> 25)
    + 13
)
_TILE_PAYLOAD_HEADER_BYTES = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")


class V3PackageMergeError(ValueError):
    """One or more V3 packages cannot be merged without weakening truth."""


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

    def contains(self, tile: TileKey) -> bool:
        return (
            tile.z == self.z
            and self.x_min <= tile.x <= self.x_max
            and self.y_min <= tile.y <= self.y_max
        )

    def ordinal(self, tile: TileKey) -> int:
        width = self.x_max - self.x_min + 1
        return (tile.y - self.y_min) * width + tile.x - self.x_min

    def document(self) -> dict[str, int]:
        return {
            "z": self.z,
            "xMin": self.x_min,
            "xMax": self.x_max,
            "yMin": self.y_min,
            "yMax": self.y_max,
            "tileCount": self.tile_count,
        }


@dataclass(frozen=True, slots=True)
class _Range:
    window: _Window
    first_ordinal: int


@dataclass(frozen=True, slots=True)
class _InputPackage:
    directory: Path
    package_id: str
    manifest_sha256: str
    manifest_bytes: int
    records_sha256: str
    records_bytes: int
    index_sha256: str
    index_bytes: int
    ranges: tuple[_Range, ...]
    tile_count: int
    complete_declared_scope: bool
    complete_whole_earth_dictionary: bool

    def ordinal(self, tile: TileKey) -> int | None:
        for item in self.ranges:
            if item.window.contains(tile):
                return item.first_ordinal + item.window.ordinal(tile)
        return None

    def binding(self, role: str) -> dict[str, object]:
        return {
            "role": role,
            "packageId": self.package_id,
            "manifestSha256": self.manifest_sha256,
            "manifestBytes": self.manifest_bytes,
            "recordsSha256": self.records_sha256,
            "recordsBytes": self.records_bytes,
            "tileIndexSha256": self.index_sha256,
            "tileIndexBytes": self.index_bytes,
        }


@dataclass(slots=True)
class _InputState:
    index_handle: object
    records_handle: object
    next_ordinal: int = 0
    next_record_offset: int = 0
    present_tiles: int = 0
    index_digest: object = field(default_factory=hashlib.sha256, repr=False)
    records_digest: object = field(default_factory=hashlib.sha256, repr=False)


@dataclass(frozen=True, slots=True)
class _RawEnvelope:
    raw: bytes
    renderer_bytes: bytes
    posting_key: tuple[int, int, int, int]
    order_key: tuple[int, int, int, int, int, int, bytes, bytes]
    subtype: SemanticSubtype
    feature_id: int
    variant_id: int
    variant_full_sha256: bytes


@dataclass(frozen=True, slots=True)
class MergeResult:
    output_directory: Path
    manifest: Mapping[str, object]
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
        raise V3PackageMergeError("canonical JSON value is unsupported") from error


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise V3PackageMergeError(f"JSON contains duplicate key {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise V3PackageMergeError(f"JSON contains forbidden numeric constant {value}")


def _strict_json(raw: bytes, label: str) -> dict[str, object]:
    if type(raw) is not bytes:
        raise V3PackageMergeError(f"{label} must be immutable bytes")
    try:
        document = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except V3PackageMergeError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise V3PackageMergeError(f"{label} is not strict UTF-8 JSON") from error
    if type(document) is not dict:
        raise V3PackageMergeError(f"{label} root must be an object")
    return document


def _exact_int(value: object, label: str, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise V3PackageMergeError(
            f"{label} must be an exact integer in [{minimum}, {maximum}]"
        )
    return value


def _exact_bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise V3PackageMergeError(f"{label} must be Boolean")
    return value


def _exact_text(value: object, label: str) -> str:
    if type(value) is not str or not value:
        raise V3PackageMergeError(f"{label} must be nonempty text")
    if unicodedata.normalize("NFC", value) != value:
        raise V3PackageMergeError(f"{label} must be NFC")
    if any(character == "\ufffd" or unicodedata.category(character) in {"Cc", "Cs"} for character in value):
        raise V3PackageMergeError(f"{label} contains invalid Unicode")
    return value


def _validate_package_id(value: object) -> str:
    package_id = _exact_text(value, "package ID")
    if package_id in {".", ".."} or any(character in package_id for character in ("/", "\\", "\0")):
        raise V3PackageMergeError("package ID is path-unsafe")
    return package_id


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
        raise V3PackageMergeError(f"cannot hash {path.name}: {error}") from error
    return digest.hexdigest()


def _load_input(directory: Path) -> _InputPackage:
    if not isinstance(directory, Path) or not directory.is_dir():
        raise V3PackageMergeError("V3 input directory is not readable")
    manifest_path = directory / "manifest.json"
    records_path = directory / "records.fadictpack"
    index_path = directory / "tile-index.bin"
    if not all(path.is_file() for path in (manifest_path, records_path, index_path)):
        raise V3PackageMergeError("V3 input lacks its three runtime files")
    manifest_size = manifest_path.stat().st_size
    if not 0 < manifest_size <= _MAX_MANIFEST_BYTES:
        raise V3PackageMergeError("V3 manifest byte length is outside its bound")
    try:
        with manifest_path.open("rb") as manifest_handle:
            manifest_raw = manifest_handle.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as error:
        raise V3PackageMergeError("V3 manifest is not readable") from error
    manifest_size = len(manifest_raw)
    if not 0 < manifest_size <= _MAX_MANIFEST_BYTES:
        raise V3PackageMergeError("V3 manifest byte length is outside its bound")
    manifest = _strict_json(manifest_raw, "V3 manifest")
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 3:
        raise V3PackageMergeError("V3 manifest schemaVersion must be exactly 3")
    if manifest.get("payloadSchema") != PAYLOAD_SCHEMA:
        raise V3PackageMergeError("V3 payload schema is unsupported")
    if manifest.get("presentationPolicySha256") != PRESENTATION_POLICY_SHA256:
        raise V3PackageMergeError("V3 presentation policy identity differs")
    if manifest.get("sourcedTextPolicySha256") != SOURCED_TEXT_POLICY_SHA256:
        raise V3PackageMergeError("V3 sourced-text policy identity differs")
    if manifest.get("unicodeScriptProfileSha256") != UNICODE_SCRIPT_PROFILE_SHA256:
        raise V3PackageMergeError("V3 Unicode script profile identity differs")
    if manifest.get("compatibility") != {"emptyPresentTilesSharePayload": False}:
        raise V3PackageMergeError("V3 compatibility contract is unsupported")
    package_id = _validate_package_id(manifest.get("packageId"))
    coverage = manifest.get("coverage")
    if type(coverage) is not dict:
        raise V3PackageMergeError("V3 coverage must be an object")
    complete_declared = _exact_bool(
        coverage.get("completeDeclaredScope"), "complete declared scope"
    )
    complete_whole = _exact_bool(
        coverage.get("completeWholeEarthDictionary"), "complete whole earth"
    )
    raw_ranges = coverage.get("zoomRanges")
    if type(raw_ranges) is not list or not raw_ranges:
        raise V3PackageMergeError("V3 zoom ranges must be a nonempty array")
    ranges: list[_Range] = []
    first_ordinal = 0
    previous_zoom = -1
    for ordinal, raw_range in enumerate(raw_ranges):
        if type(raw_range) is not dict:
            raise V3PackageMergeError(f"V3 zoom range {ordinal} must be an object")
        z = _exact_int(raw_range.get("z"), "range zoom", 0, 29)
        if z <= previous_zoom:
            raise V3PackageMergeError("V3 zoom ranges are not strictly ordered")
        previous_zoom = z
        limit = (1 << z) - 1
        window = _Window(
            z,
            _exact_int(raw_range.get("xMin"), "range xMin", 0, limit),
            _exact_int(raw_range.get("xMax"), "range xMax", 0, limit),
            _exact_int(raw_range.get("yMin"), "range yMin", 0, limit),
            _exact_int(raw_range.get("yMax"), "range yMax", 0, limit),
        )
        if window.x_min > window.x_max or window.y_min > window.y_max:
            raise V3PackageMergeError("V3 zoom range bounds are reversed")
        if raw_range.get("tileCount") != window.tile_count:
            raise V3PackageMergeError("V3 zoom range tileCount is inconsistent")
        ranges.append(_Range(window, first_ordinal))
        first_ordinal += window.tile_count
    tile_count = _exact_int(
        coverage.get("tileCount"), "coverage tileCount", 1, (1 << 63) - 1
    )
    if tile_count != first_ordinal:
        raise V3PackageMergeError("V3 coverage tileCount differs from its ranges")
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
        raise V3PackageMergeError("V3 whole-earth claim is not full-world")
    records_bytes = records_path.stat().st_size
    index_bytes = index_path.stat().st_size
    if index_bytes != tile_count * INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("V3 binary index length differs from coverage")
    if index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise V3PackageMergeError("V3 Android index exceeds its byte-array bound")
    return _InputPackage(
        directory=directory,
        package_id=package_id,
        manifest_sha256=hashlib.sha256(manifest_raw).hexdigest(),
        manifest_bytes=manifest_size,
        records_sha256=_sha256_file(records_path),
        records_bytes=records_bytes,
        index_sha256=_sha256_file(index_path),
        index_bytes=index_bytes,
        ranges=tuple(ranges),
        tile_count=tile_count,
        complete_declared_scope=complete_declared,
        complete_whole_earth_dictionary=complete_whole,
    )


def _union_windows(packages: Sequence[_InputPackage]) -> tuple[_Window, ...]:
    by_zoom: dict[int, list[_Window]] = {}
    for package in packages:
        for item in package.ranges:
            by_zoom.setdefault(item.window.z, []).append(item.window)
    return tuple(
        _Window(
            z,
            min(window.x_min for window in windows),
            max(window.x_max for window in windows),
            min(window.y_min for window in windows),
            max(window.y_max for window in windows),
        )
        for z, windows in sorted(by_zoom.items())
    )


def _extends_primary_scope(
    primary: _InputPackage,
    supplement: _InputPackage,
) -> bool:
    for supplement_range in supplement.ranges:
        if not any(
            primary_range.window.z == supplement_range.window.z
            and primary_range.window.x_min <= supplement_range.window.x_min
            and primary_range.window.x_max >= supplement_range.window.x_max
            and primary_range.window.y_min <= supplement_range.window.y_min
            and primary_range.window.y_max >= supplement_range.window.y_max
            for primary_range in primary.ranges
        ):
            return True
    return False


def _tiles_index_order(windows: Sequence[_Window]):
    for window in windows:
        for y in range(window.y_min, window.y_max + 1):
            for x in range(window.x_min, window.x_max + 1):
                yield TileKey(window.z, x, y)


def _tiles_semantic_order(windows: Sequence[_Window]):
    for window in windows:
        for x in range(window.x_min, window.x_max + 1):
            for y in range(window.y_min, window.y_max + 1):
                yield TileKey(window.z, x, y)


def _output_ordinal(windows: Sequence[_Window], tile: TileKey) -> int:
    first = 0
    for window in windows:
        if window.contains(tile):
            return first + window.ordinal(tile)
        first += window.tile_count
    raise V3PackageMergeError("output tile lies outside merged coverage")


def _inflate_exact(compressed: bytes, raw_length: int, label: str) -> bytes:
    inflater = zlib.decompressobj(wbits=-zlib.MAX_WBITS)
    try:
        raw = inflater.decompress(compressed, raw_length + 1)
        if inflater.unconsumed_tail:
            raise V3PackageMergeError(f"{label} DEFLATE exceeds its declared length")
        raw += inflater.flush()
    except zlib.error as error:
        raise V3PackageMergeError(f"{label} DEFLATE is corrupt") from error
    if not inflater.eof or inflater.unused_data or len(raw) != raw_length:
        raise V3PackageMergeError(f"{label} DEFLATE length is dishonest")
    return raw


def _extract_envelopes(tile: TileKey, payload: bytes) -> tuple[_RawEnvelope, ...]:
    try:
        decoded = decode_tile_payload(tile, payload)
    except ValueError as error:
        raise V3PackageMergeError(f"V3 tile {tile.z}/{tile.x}/{tile.y} is not canonical: {error}") from error
    offset = len(TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
    envelopes: list[_RawEnvelope] = []
    for record in decoded.records:
        start = offset
        if offset + 4 > len(payload):
            raise V3PackageMergeError("V3 renderer envelope is truncated")
        renderer_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4
        end_renderer = offset + renderer_length
        if end_renderer > len(payload):
            raise V3PackageMergeError("V3 renderer record is truncated")
        canonical_renderer = payload[offset:end_renderer]
        offset = end_renderer
        if offset + 4 > len(payload):
            raise V3PackageMergeError("V3 sourced-text envelope is truncated")
        sourced_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4
        sourced_digest = b""
        sourced_canonical = b""
        if sourced_length:
            if sourced_length > MAX_SOURCED_TEXT_RECORD_BYTES or offset + 32 + sourced_length > len(payload):
                raise V3PackageMergeError("V3 sourced-text envelope exceeds its bound")
            sourced_digest = payload[offset : offset + 32]
            offset += 32
            sourced_canonical = payload[offset : offset + sourced_length]
            offset += sourced_length
        expected_renderer = renderer_record_bytes(record.renderer_record)
        if canonical_renderer != expected_renderer:
            raise V3PackageMergeError("V3 renderer envelope differs from its canonical record")
        expected_sourced = record.sourced_text
        if expected_sourced is None:
            if sourced_length:
                raise V3PackageMergeError("V3 non-label unexpectedly carries sourced text")
        elif (
            sourced_canonical != expected_sourced.canonical_bytes
            or sourced_digest != expected_sourced.full_sha256
        ):
            raise V3PackageMergeError("V3 sourced-text envelope differs from its canonical identity")
        renderer = record.renderer_record
        variant = renderer.variant
        try:
            subtype = SemanticSubtype(variant.semantic_subtype)
        except ValueError as error:
            raise V3PackageMergeError("V3 renderer semantic subtype is unknown") from error
        posting = renderer.posting
        order_key = (
            variant.draw_order,
            variant.priority,
            variant.layer_group.value,
            variant.feature_kind.value,
            variant.canonical_variant_id,
            posting.feature_id,
            canonical_renderer,
            sourced_digest,
        )
        envelopes.append(
            _RawEnvelope(
                raw=payload[start:offset],
                renderer_bytes=canonical_renderer,
                posting_key=(
                    posting.feature_id,
                    posting.canonical_variant_id,
                    posting.owner_tile.packed,
                    posting.world_wrap,
                ),
                order_key=order_key,
                subtype=subtype,
                feature_id=posting.feature_id,
                variant_id=variant.canonical_variant_id,
                variant_full_sha256=variant_fingerprint(variant).full_sha256,
            )
        )
    if offset != len(payload):
        raise V3PackageMergeError("V3 tile contains trailing envelope bytes")
    return tuple(envelopes)


def _read_input_tile(
    package: _InputPackage,
    state: _InputState,
    tile: TileKey,
) -> tuple[bool, tuple[_RawEnvelope, ...]]:
    ordinal = package.ordinal(tile)
    if ordinal is None:
        return False, ()
    if ordinal != state.next_ordinal:
        raise V3PackageMergeError("V3 input coverage traversal is not canonical")
    entry = state.index_handle.read(INDEX_ENTRY_BYTES)
    state.next_ordinal += 1
    if len(entry) != INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("V3 input index ended early")
    state.index_digest.update(entry)
    if entry == _ZERO_INDEX_ENTRY:
        return False, ()
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    if flags != INDEX_FLAG_PRESENT:
        raise V3PackageMergeError("V3 input index flags are unsupported")
    if offset != state.next_record_offset:
        raise V3PackageMergeError("V3 input record offsets are not exact and contiguous")
    if not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES:
        raise V3PackageMergeError("V3 input compressed tile length exceeds its bound")
    if not 0 < raw_length <= MAX_TILE_BYTES:
        raise V3PackageMergeError("V3 input raw tile length exceeds its bound")
    if offset + compressed_length > package.records_bytes:
        raise V3PackageMergeError("V3 input record range exceeds its pack")
    compressed = state.records_handle.read(compressed_length)
    if len(compressed) != compressed_length:
        raise V3PackageMergeError("V3 input records pack ended early")
    state.records_digest.update(compressed)
    state.next_record_offset += compressed_length
    state.present_tiles += 1
    payload = _inflate_exact(compressed, raw_length, "V3 input")
    if raw_hash32(payload) != expected_hash32:
        raise V3PackageMergeError("V3 input tile integrity hash differs")
    return True, _extract_envelopes(tile, payload)


def _finish_input_validation(package: _InputPackage, state: _InputState) -> None:
    if state.next_ordinal != package.tile_count:
        raise V3PackageMergeError("V3 input index was not completely audited")
    if state.next_record_offset != package.records_bytes:
        raise V3PackageMergeError("V3 input records pack has unreferenced or missing bytes")
    if package.complete_declared_scope and state.present_tiles != package.tile_count:
        raise V3PackageMergeError("V3 complete-declared input contains a missing tile")
    if state.index_handle.read(1) or state.records_handle.read(1):
        raise V3PackageMergeError("V3 input runtime files changed while being merged")
    if (
        state.index_digest.hexdigest() != package.index_sha256
        or state.records_digest.hexdigest() != package.records_sha256
    ):
        raise V3PackageMergeError("V3 input runtime files changed while being merged")


def _merge_contribution(
    tile: TileKey,
    by_posting: dict[tuple[int, int, int, int], _RawEnvelope],
    payload_bytes: int,
    records: Sequence[_RawEnvelope],
) -> int:
    for record in records:
        previous = by_posting.get(record.posting_key)
        if previous is not None:
            if previous.raw != record.raw:
                raise V3PackageMergeError(
                    f"divergent duplicate renderer posting at {tile.z}/{tile.x}/{tile.y}"
                )
            continue
        if len(by_posting) >= MAX_RECORDS_PER_TILE:
            raise V3PackageMergeError("merged V3 tile record count exceeds its bound")
        payload_bytes += len(record.raw)
        if payload_bytes > MAX_TILE_BYTES:
            raise V3PackageMergeError("merged V3 tile byte length exceeds its bound")
        by_posting[record.posting_key] = record
    return payload_bytes


def _merge_payload(
    tile: TileKey,
    by_posting: Mapping[tuple[int, int, int, int], _RawEnvelope],
    payload_bytes: int,
) -> bytes:
    ordered = tuple(sorted(by_posting.values(), key=lambda item: item.order_key))
    if len(ordered) > MAX_RECORDS_PER_TILE:
        raise V3PackageMergeError("merged V3 tile record count exceeds its bound")
    payload = b"".join(
        (
            TILE_PAYLOAD_MAGIC,
            struct.pack("<BIII", tile.z, tile.x, tile.y, len(ordered)),
            *(item.raw for item in ordered),
        )
    )
    if len(payload) != payload_bytes or len(payload) > MAX_TILE_BYTES:
        raise V3PackageMergeError("merged V3 tile byte length exceeds its bound")
    _extract_envelopes(tile, payload)
    return payload


def _read_output_payload(
    records_handle: object,
    index_handle: object,
    windows: Sequence[_Window],
    tile: TileKey,
    records_bytes: int,
) -> bytes | None:
    ordinal = _output_ordinal(windows, tile)
    index_handle.seek(ordinal * INDEX_ENTRY_BYTES)
    entry = index_handle.read(INDEX_ENTRY_BYTES)
    if len(entry) != INDEX_ENTRY_BYTES:
        raise V3PackageMergeError("merged V3 audit index ended early")
    if entry == _ZERO_INDEX_ENTRY:
        return None
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    if (
        flags != INDEX_FLAG_PRESENT
        or not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES
        or not 0 < raw_length <= MAX_TILE_BYTES
        or offset + compressed_length > records_bytes
    ):
        raise V3PackageMergeError("merged V3 audit index entry is invalid")
    records_handle.seek(offset)
    compressed = records_handle.read(compressed_length)
    if len(compressed) != compressed_length:
        raise V3PackageMergeError("merged V3 audit records ended early")
    payload = _inflate_exact(compressed, raw_length, "merged V3 audit")
    if raw_hash32(payload) != expected_hash32:
        raise V3PackageMergeError("merged V3 audit integrity hash differs")
    return payload


def _audit_output(
    partial_directory: Path,
    windows: Sequence[_Window],
) -> tuple[str, list[dict[str, object]]]:
    records_path = partial_directory / "records.fadictpack"
    index_path = partial_directory / "tile-index.bin"
    database_path = partial_directory / "identity-counts.sqlite"
    database_path.unlink(missing_ok=True)
    digest = hashlib.sha256(_SEMANTIC_STREAM_DOMAIN)
    posting_counts: Counter[SemanticSubtype] = Counter()
    connection: sqlite3.Connection | None = None
    try:
        connection = sqlite3.connect(database_path)
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        connection.execute("PRAGMA temp_store=FILE")
        connection.execute(
            "CREATE TABLE features (subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
            "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
        )
        connection.execute(
            "CREATE TABLE variants (subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
            "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
        )
        connection.execute(
            "CREATE TABLE variant_payloads (identity BLOB NOT NULL, full_sha BLOB NOT NULL, "
            "PRIMARY KEY (identity, full_sha)) WITHOUT ROWID"
        )
        records_bytes = records_path.stat().st_size
        with records_path.open("rb") as records_handle, index_path.open("rb") as index_handle:
            for tile_number, tile in enumerate(_tiles_semantic_order(windows), start=1):
                payload = _read_output_payload(
                    records_handle, index_handle, windows, tile, records_bytes
                )
                if payload is None:
                    continue
                envelopes = _extract_envelopes(tile, payload)
                if list(envelopes) != sorted(envelopes, key=lambda item: item.order_key):
                    raise V3PackageMergeError("merged V3 renderer order is not canonical")
                feature_rows: list[tuple[int, bytes]] = []
                variant_rows: list[tuple[int, bytes]] = []
                payload_rows: list[tuple[bytes, bytes]] = []
                for record in envelopes:
                    body = struct.pack("<Q", tile.packed) + record.renderer_bytes
                    digest.update(struct.pack("<I", len(body)))
                    digest.update(body)
                    posting_counts[record.subtype] += 1
                    feature_rows.append(
                        (record.subtype.value, record.feature_id.to_bytes(8, "big"))
                    )
                    variant_rows.append(
                        (record.subtype.value, record.variant_id.to_bytes(8, "big"))
                    )
                    payload_rows.append(
                        (record.variant_id.to_bytes(8, "big"), record.variant_full_sha256)
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
                    "INSERT OR IGNORE INTO variant_payloads (identity, full_sha) VALUES (?, ?)",
                    payload_rows,
                )
                if tile_number % 4096 == 0:
                    connection.commit()
        connection.commit()
        if connection.execute(
            "SELECT identity FROM variant_payloads GROUP BY identity HAVING COUNT(*) > 1 LIMIT 1"
        ).fetchone() is not None:
            raise V3PackageMergeError("divergent duplicate canonical variant identity")
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
        subtype_counts = [
            {
                "semanticSubtype": subtype.value,
                "semanticSubtypeName": subtype.name,
                "distinctFeatureIds": feature_counts.get(subtype.value, 0),
                "canonicalVariantIds": variant_counts.get(subtype.value, 0),
                "postings": posting_counts[subtype],
            }
            for subtype in SemanticSubtype
        ]
        return digest.hexdigest(), subtype_counts
    finally:
        if connection is not None:
            connection.close()
        database_path.unlink(missing_ok=True)


def _merger_sha256() -> str:
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def _verify_published_output(
    output_directory: Path,
    *,
    manifest_bytes: bytes,
    receipt_bytes: bytes,
    records_sha256: str,
    records_bytes: int,
    index_sha256: str,
    index_bytes: int,
) -> None:
    expected = (
        (
            output_directory / "manifest.json",
            len(manifest_bytes),
            hashlib.sha256(manifest_bytes).hexdigest(),
        ),
        (
            output_directory / "merge-receipt.json",
            len(receipt_bytes),
            hashlib.sha256(receipt_bytes).hexdigest(),
        ),
        (output_directory / "records.fadictpack", records_bytes, records_sha256),
        (output_directory / "tile-index.bin", index_bytes, index_sha256),
    )
    try:
        for path, byte_count, sha256 in expected:
            if path.stat().st_size != byte_count or _sha256_file(path) != sha256:
                raise V3PackageMergeError(
                    "published output differs from its bound byte streams"
                )
    except OSError as error:
        raise V3PackageMergeError(
            "published output differs from its bound byte streams"
        ) from error


def merge_v3_packages(
    *,
    primary_directory: Path,
    supplement_directories: Sequence[Path],
    output_directory: Path,
    package_id: str,
) -> MergeResult:
    """Stream one primary and sparse supplements into one Android-readable V3 package."""

    if not isinstance(output_directory, Path):
        raise V3PackageMergeError("output directory must be a pathlib.Path")
    checked_package_id = _validate_package_id(package_id)
    if type(supplement_directories) not in (tuple, list):
        raise V3PackageMergeError("supplement directories must be an ordered sequence")
    if any(not isinstance(path, Path) for path in supplement_directories):
        raise V3PackageMergeError("supplement directories must be pathlib.Path values")
    if output_directory.exists():
        raise V3PackageMergeError("output directory already exists")
    partial_directory = output_directory.with_name(output_directory.name + ".partial")
    if partial_directory.exists():
        raise V3PackageMergeError("partial merge directory already exists")

    primary = _load_input(primary_directory)
    supplements = sorted(
        (_load_input(path) for path in supplement_directories),
        key=lambda item: (
            item.package_id,
            item.manifest_sha256,
            item.records_sha256,
            item.index_sha256,
        ),
    )
    packages = (primary, *supplements)
    windows = _union_windows(packages)
    input_bindings = [primary.binding("primary")]
    input_bindings.extend(package.binding("supplement") for package in supplements)
    merger_sha256 = _merger_sha256()
    total_tiles = sum(window.tile_count for window in windows)
    output_index_bytes = total_tiles * INDEX_ENTRY_BYTES
    if output_index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise V3PackageMergeError(
            "merged V3 Android index exceeds its byte-array bound"
        )
    partial_directory.mkdir(parents=True, exist_ok=False)
    output_records_path = partial_directory / "records.fadictpack"
    output_index_path = partial_directory / "tile-index.bin"
    try:
        output_offset = 0
        present_tiles = 0
        output_records_digest = hashlib.sha256()
        output_index_digest = hashlib.sha256()
        with contextlib.ExitStack() as stack:
            states = [
                _InputState(
                    stack.enter_context((package.directory / "tile-index.bin").open("rb")),
                    stack.enter_context((package.directory / "records.fadictpack").open("rb")),
                )
                for package in packages
            ]
            output_records = stack.enter_context(output_records_path.open("wb"))
            output_index = stack.enter_context(output_index_path.open("wb"))
            for tile in _tiles_index_order(windows):
                any_present = False
                merged_records: dict[
                    tuple[int, int, int, int], _RawEnvelope
                ] = {}
                merged_payload_bytes = _TILE_PAYLOAD_HEADER_BYTES
                for package, state in zip(packages, states, strict=True):
                    present, records = _read_input_tile(package, state, tile)
                    any_present = any_present or present
                    if present:
                        merged_payload_bytes = _merge_contribution(
                            tile,
                            merged_records,
                            merged_payload_bytes,
                            records,
                        )
                if not any_present:
                    output_index.write(_ZERO_INDEX_ENTRY)
                    output_index_digest.update(_ZERO_INDEX_ENTRY)
                    continue
                payload = _merge_payload(
                    tile,
                    merged_records,
                    merged_payload_bytes,
                )
                compressed = raw_deflate(payload)
                if len(compressed) > _MAX_COMPRESSED_TILE_BYTES:
                    raise V3PackageMergeError(
                        "merged V3 compressed tile length exceeds its bound"
                    )
                index_entry = encode_index_entry(
                    offset=output_offset,
                    compressed_length=len(compressed),
                    raw_length=len(payload),
                    raw_hash32=raw_hash32(payload),
                )
                output_index.write(index_entry)
                output_index_digest.update(index_entry)
                output_records.write(compressed)
                output_records_digest.update(compressed)
                output_offset += len(compressed)
                present_tiles += 1
            for package, state in zip(packages, states, strict=True):
                _finish_input_validation(package, state)
        if output_index_path.stat().st_size != output_index_bytes:
            raise V3PackageMergeError("merged V3 index length differs from coverage")
        if output_records_path.stat().st_size != output_offset:
            raise V3PackageMergeError("merged V3 records length differs from its stream")

        semantic_sha256, subtype_counts = _audit_output(partial_directory, windows)
        records_sha256 = output_records_digest.hexdigest()
        index_sha256 = output_index_digest.hexdigest()
        complete_declared = present_tiles == total_tiles
        complete_whole = (
            primary.complete_whole_earth_dictionary
            and complete_declared
            and all(
                not _extends_primary_scope(primary, supplement)
                or supplement.complete_whole_earth_dictionary
                for supplement in supplements
            )
            and all(
                window.x_min == 0
                and window.y_min == 0
                and window.x_max == (1 << window.z) - 1
                and window.y_max == (1 << window.z) - 1
                for window in windows
            )
        )
        primary_zooms = {item.window.z for item in primary.ranges}
        merged_by_zoom = {window.z: window for window in windows}
        primary_whole_preserved = (
            primary.complete_whole_earth_dictionary
            and all(
                merged_by_zoom[item.window.z] == item.window
                for item in primary.ranges
                if item.window.z in primary_zooms
            )
        )
        coverage = {
            "tileCount": total_tiles,
            "completeDeclaredScope": complete_declared,
            "completeWholeEarthDictionary": complete_whole,
            "zoomRanges": [window.document() for window in windows],
        }
        manifest: dict[str, object] = {
            "packageId": checked_package_id,
            "schemaVersion": 3,
            "payloadSchema": PAYLOAD_SCHEMA,
            "presentationPolicySha256": PRESENTATION_POLICY_SHA256,
            "sourcedTextPolicySha256": SOURCED_TEXT_POLICY_SHA256,
            "unicodeScriptProfileSha256": UNICODE_SCRIPT_PROFILE_SHA256,
            "compatibility": {"emptyPresentTilesSharePayload": False},
            "coverage": coverage,
            "rendererSemanticStreamSha256": semantic_sha256,
            "merge": {
                "schema": _MERGE_SCHEMA,
                "mergerSha256": merger_sha256,
                "inputs": input_bindings,
                "output": {
                    "recordsSha256": records_sha256,
                    "recordsBytes": output_offset,
                    "tileIndexSha256": index_sha256,
                    "tileIndexBytes": output_index_bytes,
                },
            },
        }
        manifest_bytes = _canonical_json_bytes(manifest)
        manifest_path = partial_directory / "manifest.json"
        manifest_path.write_bytes(manifest_bytes)
        receipt: dict[str, object] = {
            "schema": _RECEIPT_SCHEMA,
            "packageId": checked_package_id,
            "mergerSha256": merger_sha256,
            "inputs": input_bindings,
            "coverage": {
                **coverage,
                "presentTileCount": present_tiles,
                "primaryWholeEarthPreserved": primary_whole_preserved,
            },
            "rendererSemanticStreamSha256": semantic_sha256,
            "subtypeCounts": subtype_counts,
            "outputFiles": [
                {
                    "name": "manifest.json",
                    "bytes": len(manifest_bytes),
                    "sha256": hashlib.sha256(manifest_bytes).hexdigest(),
                },
                {
                    "name": "records.fadictpack",
                    "bytes": output_offset,
                    "sha256": records_sha256,
                },
                {
                    "name": "tile-index.bin",
                    "bytes": output_index_bytes,
                    "sha256": index_sha256,
                },
            ],
        }
        receipt_bytes = _canonical_json_bytes(receipt)
        (partial_directory / "merge-receipt.json").write_bytes(receipt_bytes)
        os.replace(partial_directory, output_directory)
        try:
            _verify_published_output(
                output_directory,
                manifest_bytes=manifest_bytes,
                receipt_bytes=receipt_bytes,
                records_sha256=records_sha256,
                records_bytes=output_offset,
                index_sha256=index_sha256,
                index_bytes=output_index_bytes,
            )
        except Exception:
            shutil.rmtree(output_directory, ignore_errors=True)
            raise
        return MergeResult(output_directory, manifest, receipt)
    except Exception:
        shutil.rmtree(partial_directory, ignore_errors=True)
        raise


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Stream a primary and sparse supplements into one Experiment 8 V3 package."
    )
    parser.add_argument("--primary", required=True, type=Path)
    parser.add_argument("--supplement", action="append", default=[], type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package-id", required=True)
    parsed = parser.parse_args(arguments)
    result = merge_v3_packages(
        primary_directory=parsed.primary,
        supplement_directories=tuple(parsed.supplement),
        output_directory=parsed.output,
        package_id=parsed.package_id,
    )
    print(_canonical_json_bytes(result.receipt).decode("utf-8"), end="")
    return 0


__all__ = [
    "MergeResult",
    "V3PackageMergeError",
    "merge_v3_packages",
]


if __name__ == "__main__":
    raise SystemExit(_main())
