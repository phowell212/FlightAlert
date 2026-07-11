"""Lossless, bounded-block FAR6 to FAR8 size probe.

This tool does not declare the retained Experiment 6 raster package usable.  It
only verifies the retained bytes and measures an independently decodable Zstd
block layout without materializing a whole-world replacement package.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import struct
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterator, Sequence

import zstandard


FAR6_FIXED = struct.Struct("<4sIIIQQ")
# Proposed FAR8 container accounting is exact for these fixed layouts.  The
# probe writes no FAR8 package yet: magic/version/tile and block counts/header
# length, then table/payload/class/footer byte counts; each table row binds a
# tile range, relative payload/class locations and lengths, and both raw hashes.
FAR8_FIXED = struct.Struct("<4sIIIIIQQQQ")
FAR8_BLOCK_RECORD = struct.Struct("<IHHQIIQII32s32s")
FAR8_FIXED_BYTES = FAR8_FIXED.size
FAR8_BLOCK_RECORD_BYTES = FAR8_BLOCK_RECORD.size
PAYLOAD_BYTES_PER_TILE = 256 * 256 * 4
CLASS_BYTES_PER_TILE = 256 * 256
MAX_FAR6_HEADER_BYTES = 1 << 20
READ_BYTES = 1 << 16
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
CHUNK_PROBE = "flight-alert-far8-bounded-zstd-v1"
CHECKPOINT_KIND = "flight-alert-far8-bounded-zstd-chunk-checkpoint-v1"


class Far8ProbeError(ValueError):
    """The retained input or measured output violated the probe contract."""


@dataclass(frozen=True)
class Far6Descriptor:
    path: Path
    prefix: bytes
    header: dict[str, Any]
    tile_count: int
    storage_codec: str
    payload_offset: int
    payload_stored_bytes: int
    payload_raw_bytes: int
    class_offset: int
    class_stored_bytes: int
    class_raw_bytes: int
    payload_stored_sha256: str
    class_stored_sha256: str
    header_variant: str


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise Far8ProbeError(message)


def _exact_int(value: Any, field: str) -> int:
    _require(type(value) is int and value >= 0, f"{field} must be a nonnegative integer")
    return value


def _sha256_field(value: Any, field: str) -> str:
    _require(isinstance(value, str) and SHA256_RE.fullmatch(value) is not None, f"{field} must be lowercase SHA-256")
    return value


def _decode_unique_json(raw: bytes, source: str) -> dict[str, Any]:
    def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise Far8ProbeError(f"{source} repeats JSON key {key}")
            result[key] = value
        return result

    try:
        decoded = json.loads(raw.decode("utf-8", errors="strict"), object_pairs_hook=unique_object)
    except Far8ProbeError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise Far8ProbeError(f"{source} is not strict UTF-8 JSON: {exc}") from exc
    _require(isinstance(decoded, dict), f"{source} must be a JSON object")
    return decoded


def _read_far6_descriptor(path: Path) -> Far6Descriptor:
    path = Path(path)
    _require(path.is_file(), f"FAR6 chunk is not a file: {path}")
    size = path.stat().st_size
    _require(size >= FAR6_FIXED.size, "FAR6 chunk is truncated before its fixed header")
    with path.open("rb") as stream:
        fixed = stream.read(FAR6_FIXED.size)
        _require(len(fixed) == FAR6_FIXED.size, "FAR6 fixed header read was short")
        magic, version, tile_count, header_bytes, payload_stored, class_stored = FAR6_FIXED.unpack(fixed)
        _require(magic == b"FAR6", "FAR6 magic is invalid")
        _require(version == 1, "FAR6 version is unsupported")
        _require(tile_count > 0, "FAR6 tile count must be positive")
        _require(0 < header_bytes <= MAX_FAR6_HEADER_BYTES, "FAR6 JSON header length is invalid")
        expected_size = FAR6_FIXED.size + header_bytes + payload_stored + class_stored
        _require(size == expected_size, "FAR6 file length does not exactly match its fixed header")
        header_raw = stream.read(header_bytes)
        _require(len(header_raw) == header_bytes, "FAR6 JSON header read was short")
    header = _decode_unique_json(header_raw, "FAR6 header")

    exact_fields = {
        "schemaVersion": 1,
        "magic": "FAR6",
        "version": 1,
        "tileCount": tile_count,
        "tileSize": 256,
    }
    for name, expected in exact_fields.items():
        _require(header.get(name) == expected and type(header.get(name)) is type(expected), f"FAR6 {name} contradicts its container")
    payload_raw = _exact_int(header.get("payloadBytes"), "FAR6 payloadBytes")
    class_raw = _exact_int(header.get("classBytes"), "FAR6 classBytes")
    _require(payload_raw == tile_count * PAYLOAD_BYTES_PER_TILE, "FAR6 payload byte count is not exact RGBA8888 tiles")
    _require(class_raw == tile_count * CLASS_BYTES_PER_TILE, "FAR6 class byte count is not exact uint8 tiles")
    payload_raw_sha = _sha256_field(header.get("payloadSha256"), "FAR6 payloadSha256")
    class_raw_sha = _sha256_field(header.get("classSha256"), "FAR6 classSha256")
    declared_codec = header.get("storageCodec")
    if declared_codec is None:
        _require(
            all(
                name not in header
                for name in (
                    "zlibLevel",
                    "payloadStoredBytes",
                    "classStoredBytes",
                    "payloadStoredSha256",
                    "classStoredSha256",
                )
            ),
            "legacy raw FAR6 header cannot partially declare modern storage fields",
        )
        _require(
            isinstance(header.get("payloadCodec"), str) and header["payloadCodec"].startswith("raw_")
            and isinstance(header.get("classCodec"), str) and not header["classCodec"].startswith("zlib_"),
            "legacy FAR6 without storageCodec is not explicitly raw",
        )
        codec = "raw"
        header_variant = "legacy_raw_v1"
        payload_stored_sha = payload_raw_sha
        class_stored_sha = class_raw_sha
    else:
        _require(declared_codec in {"raw", "zlib_miniz"}, "FAR6 storage codec is unsupported")
        codec = declared_codec
        header_variant = "storage_fields_v1"
        _require(
            header.get("payloadStoredBytes") == payload_stored
            and type(header.get("payloadStoredBytes")) is int,
            "FAR6 payloadStoredBytes contradicts its container",
        )
        _require(
            header.get("classStoredBytes") == class_stored
            and type(header.get("classStoredBytes")) is int,
            "FAR6 classStoredBytes contradicts its container",
        )
        payload_stored_sha = _sha256_field(header.get("payloadStoredSha256"), "FAR6 payloadStoredSha256")
        class_stored_sha = _sha256_field(header.get("classStoredSha256"), "FAR6 classStoredSha256")
    if codec == "raw":
        _require(payload_stored == payload_raw and class_stored == class_raw, "raw FAR6 stored lengths must equal raw lengths")
        if header_variant == "storage_fields_v1":
            _require(header.get("zlibLevel") == 0, "raw FAR6 must declare zlib level zero")
            _require(payload_stored_sha == payload_raw_sha and class_stored_sha == class_raw_sha, "raw FAR6 stored hashes must equal raw hashes")
    else:
        level = _exact_int(header.get("zlibLevel"), "FAR6 zlibLevel")
        _require(1 <= level <= 9, "compressed FAR6 zlib level is outside [1, 9]")

    prefix = fixed + header_raw
    payload_offset = len(prefix)
    class_offset = payload_offset + payload_stored
    return Far6Descriptor(
        path=path,
        prefix=prefix,
        header=header,
        tile_count=tile_count,
        storage_codec=codec,
        payload_offset=payload_offset,
        payload_stored_bytes=payload_stored,
        payload_raw_bytes=payload_raw,
        class_offset=class_offset,
        class_stored_bytes=class_stored,
        class_raw_bytes=class_raw,
        payload_stored_sha256=payload_stored_sha,
        class_stored_sha256=class_stored_sha,
        header_variant=header_variant,
    )


def _iter_verified_raw_blocks(
    descriptor: Far6Descriptor,
    *,
    offset: int,
    stored_bytes: int,
    raw_bytes: int,
    stored_sha256: str,
    raw_sha256: str,
    raw_block_bytes: int,
    source_hasher: Any,
) -> Iterator[bytes]:
    stored_hasher = hashlib.sha256()
    raw_hasher = hashlib.sha256()
    raw_count = 0
    buffer = bytearray()

    def accept(raw: bytes) -> Iterator[bytes]:
        nonlocal raw_count
        raw_count += len(raw)
        _require(raw_count <= raw_bytes, "FAR6 decoded stream exceeds its declared raw length")
        raw_hasher.update(raw)
        buffer.extend(raw)
        while len(buffer) >= raw_block_bytes:
            block = bytes(buffer[:raw_block_bytes])
            del buffer[:raw_block_bytes]
            yield block

    with descriptor.path.open("rb") as stream:
        stream.seek(offset)
        remaining = stored_bytes
        if descriptor.storage_codec == "raw":
            while remaining:
                chunk = stream.read(min(READ_BYTES, remaining))
                _require(chunk, "FAR6 raw stored stream ended early")
                remaining -= len(chunk)
                stored_hasher.update(chunk)
                source_hasher.update(chunk)
                yield from accept(chunk)
        else:
            decoder = zlib.decompressobj()
            pending = b""
            while remaining or pending:
                if not pending:
                    chunk = stream.read(min(READ_BYTES, remaining))
                    _require(chunk, "FAR6 zlib stored stream ended early")
                    remaining -= len(chunk)
                    stored_hasher.update(chunk)
                    source_hasher.update(chunk)
                    pending = chunk
                while pending:
                    room = raw_block_bytes - len(buffer)
                    try:
                        decoded = decoder.decompress(pending, room)
                    except zlib.error as exc:
                        raise Far8ProbeError(f"FAR6 zlib stream is corrupt: {exc}") from exc
                    pending = decoder.unconsumed_tail
                    if decoded:
                        yield from accept(decoded)
                    if not pending:
                        break
                    _require(decoded or room == 0, "FAR6 zlib decoder made no progress")
            _require(decoder.eof, "FAR6 zlib stream is truncated")
            _require(not decoder.unused_data, "FAR6 zlib stored blob contains trailing stream data")
            try:
                tail = decoder.flush()
            except zlib.error as exc:
                raise Far8ProbeError(f"FAR6 zlib stream flush failed: {exc}") from exc
            if tail:
                yield from accept(tail)

    if buffer:
        yield bytes(buffer)
    _require(raw_count == raw_bytes, "FAR6 decoded stream length does not match its header")
    _require(stored_hasher.hexdigest() == stored_sha256, "FAR6 stored stream SHA-256 mismatch")
    _require(raw_hasher.hexdigest() == raw_sha256, "FAR6 raw stream SHA-256 mismatch")


def _compress_blocks(
    blocks: Iterator[bytes],
    *,
    bytes_per_tile: int,
    group_tiles: int,
    compressor: Any,
) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    start_tile = 0
    decompressor = zstandard.ZstdDecompressor()
    for index, raw in enumerate(blocks):
        _require(len(raw) > 0 and len(raw) % bytes_per_tile == 0, "decoded block does not contain whole fixed-size tiles")
        tile_count = len(raw) // bytes_per_tile
        _require(1 <= tile_count <= group_tiles, "decoded block exceeds its tile bound")
        stored = compressor.compress(raw)
        try:
            restored = decompressor.decompress(stored, max_output_size=len(raw))
        except zstandard.ZstdError as exc:
            raise Far8ProbeError(f"measured Zstd block failed immediate readback: {exc}") from exc
        _require(restored == raw, "measured Zstd block readback differs from verified FAR6 bytes")
        result.append(
            {
                "blockIndex": index,
                "startTile": start_tile,
                "tileCount": tile_count,
                "rawBytes": len(raw),
                "storedBytes": len(stored),
                "rawSha256": hashlib.sha256(raw).hexdigest(),
                "storedSha256": hashlib.sha256(stored).hexdigest(),
            }
        )
        start_tile += tile_count
    return result


def _canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def measure_far6_chunk(path: Path, *, group_tiles: int = 16, zstd_level: int = 12) -> dict[str, Any]:
    """Verify one FAR6 chunk and measure a deterministic bounded Zstd layout."""

    _require(type(group_tiles) is int and 1 <= group_tiles <= 64, "group_tiles must be an integer in [1, 64]")
    _require(type(zstd_level) is int and -7 <= zstd_level <= 22, "zstd_level is outside the supported probe range")
    descriptor = _read_far6_descriptor(Path(path))
    source_hasher = hashlib.sha256(descriptor.prefix)
    compressor = zstandard.ZstdCompressor(
        level=zstd_level,
        threads=0,
        write_checksum=True,
        write_content_size=True,
        write_dict_id=False,
    )

    payload = _compress_blocks(
        _iter_verified_raw_blocks(
            descriptor,
            offset=descriptor.payload_offset,
            stored_bytes=descriptor.payload_stored_bytes,
            raw_bytes=descriptor.payload_raw_bytes,
            stored_sha256=descriptor.payload_stored_sha256,
            raw_sha256=descriptor.header["payloadSha256"],
            raw_block_bytes=group_tiles * PAYLOAD_BYTES_PER_TILE,
            source_hasher=source_hasher,
        ),
        bytes_per_tile=PAYLOAD_BYTES_PER_TILE,
        group_tiles=group_tiles,
        compressor=compressor,
    )
    classes = _compress_blocks(
        _iter_verified_raw_blocks(
            descriptor,
            offset=descriptor.class_offset,
            stored_bytes=descriptor.class_stored_bytes,
            raw_bytes=descriptor.class_raw_bytes,
            stored_sha256=descriptor.class_stored_sha256,
            raw_sha256=descriptor.header["classSha256"],
            raw_block_bytes=group_tiles * CLASS_BYTES_PER_TILE,
            source_hasher=source_hasher,
        ),
        bytes_per_tile=CLASS_BYTES_PER_TILE,
        group_tiles=group_tiles,
        compressor=compressor,
    )
    _require(len(payload) == len(classes), "payload and class block counts disagree")
    blocks: list[dict[str, Any]] = []
    for payload_block, class_block in zip(payload, classes):
        _require(
            payload_block["blockIndex"] == class_block["blockIndex"]
            and payload_block["startTile"] == class_block["startTile"]
            and payload_block["tileCount"] == class_block["tileCount"],
            "payload and class block tile ranges disagree",
        )
        blocks.append(
            {
                "blockIndex": payload_block["blockIndex"],
                "startTile": payload_block["startTile"],
                "tileCount": payload_block["tileCount"],
                "payloadRawBytes": payload_block["rawBytes"],
                "payloadStoredBytes": payload_block["storedBytes"],
                "payloadRawSha256": payload_block["rawSha256"],
                "payloadStoredSha256": payload_block["storedSha256"],
                "classRawBytes": class_block["rawBytes"],
                "classStoredBytes": class_block["storedBytes"],
                "classRawSha256": class_block["rawSha256"],
                "classStoredSha256": class_block["storedSha256"],
            }
        )
    _require(sum(block["tileCount"] for block in blocks) == descriptor.tile_count, "measured blocks do not cover every FAR6 tile")

    payload_stored_total = sum(block["payloadStoredBytes"] for block in blocks)
    class_stored_total = sum(block["classStoredBytes"] for block in blocks)
    far8_header = {
        "schemaVersion": 1,
        "magic": "FAR8",
        "version": 1,
        "sourceMagic": "FAR6",
        "sourceFar6Sha256": source_hasher.hexdigest(),
        "tileCount": descriptor.tile_count,
        "tileSize": 256,
        "blockTileLimit": group_tiles,
        "blockCount": len(blocks),
        "storageCodec": "zstd_frame_per_block",
        "zstdLevel": zstd_level,
        "zstdVersion": zstandard.__version__,
        "zstdReadbackVerified": True,
        "payloadFormat": "RGBA8888",
        "classMaskFormat": "uint8",
        "payloadRawSha256": descriptor.header["payloadSha256"],
        "classRawSha256": descriptor.header["classSha256"],
    }
    header_bytes = len(_canonical_bytes(far8_header))
    projected = (
        FAR8_FIXED_BYTES
        + header_bytes
        + len(blocks) * FAR8_BLOCK_RECORD_BYTES
        + payload_stored_total
        + class_stored_total
    )
    return {
        "schemaVersion": 1,
        "probe": CHUNK_PROBE,
        "sourceFar6Sha256": source_hasher.hexdigest(),
        "sourceFar6Bytes": descriptor.path.stat().st_size,
        "sourceStorageCodec": descriptor.storage_codec,
        "sourceHeaderVariant": descriptor.header_variant,
        "tileCount": descriptor.tile_count,
        "blockTileLimit": group_tiles,
        "blockCount": len(blocks),
        "zstdLevel": zstd_level,
        "zstdVersion": zstandard.__version__,
        "zstdReadbackVerified": True,
        "payloadRawBytes": descriptor.payload_raw_bytes,
        "classRawBytes": descriptor.class_raw_bytes,
        "payloadRawSha256": descriptor.header["payloadSha256"],
        "classRawSha256": descriptor.header["classSha256"],
        "payloadStoredBytes": payload_stored_total,
        "classStoredBytes": class_stored_total,
        "headerBytes": header_bytes,
        "blockRecordBytes": FAR8_BLOCK_RECORD_BYTES,
        "projectedFar8Bytes": projected,
        "blocks": blocks,
    }


def _hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while True:
            chunk = stream.read(1 << 20)
            if not chunk:
                return digest.hexdigest()
            digest.update(chunk)


def _measure_package_chunk_task(arguments: tuple[str, int, int]) -> dict[str, Any]:
    path_text, group_tiles, zstd_level = arguments
    path = Path(path_text)
    return {"name": path.name, **measure_far6_chunk(path, group_tiles=group_tiles, zstd_level=zstd_level)}


_CHUNK_RESULT_KEYS = {
    "name",
    "schemaVersion",
    "probe",
    "sourceFar6Sha256",
    "sourceFar6Bytes",
    "sourceStorageCodec",
    "sourceHeaderVariant",
    "tileCount",
    "blockTileLimit",
    "blockCount",
    "zstdLevel",
    "zstdVersion",
    "zstdReadbackVerified",
    "payloadRawBytes",
    "classRawBytes",
    "payloadRawSha256",
    "classRawSha256",
    "payloadStoredBytes",
    "classStoredBytes",
    "headerBytes",
    "blockRecordBytes",
    "projectedFar8Bytes",
    "blocks",
}
_BLOCK_RESULT_KEYS = {
    "blockIndex",
    "startTile",
    "tileCount",
    "payloadRawBytes",
    "payloadStoredBytes",
    "payloadRawSha256",
    "payloadStoredSha256",
    "classRawBytes",
    "classStoredBytes",
    "classRawSha256",
    "classStoredSha256",
}
_CHECKPOINT_KEYS = {
    "schemaVersion",
    "checkpoint",
    "sourceManifestSha256",
    "sourceName",
    "sourceFar6Bytes",
    "sourceFar6Sha256",
    "groupTiles",
    "zstdLevel",
    "zstdVersion",
    "probeImplementationSha256",
    "resultSha256",
    "result",
}


def _validate_chunk_result(
    result: dict[str, Any],
    *,
    source_path: Path,
    group_tiles: int,
    zstd_level: int,
) -> None:
    _require(set(result) == _CHUNK_RESULT_KEYS, "FAR8 chunk result field set is unsupported")
    _require(result.get("name") == source_path.name, "FAR8 chunk result source name mismatch")
    _require(result.get("schemaVersion") == 1, "FAR8 chunk result schema is unsupported")
    _require(result.get("probe") == CHUNK_PROBE, "FAR8 chunk result probe identity mismatch")
    _require(
        result.get("sourceFar6Bytes") == source_path.stat().st_size,
        "FAR8 chunk result source byte count mismatch",
    )
    _sha256_field(result.get("sourceFar6Sha256"), "FAR8 chunk result sourceFar6Sha256")
    _require(result.get("sourceStorageCodec") in {"raw", "zlib_miniz"}, "FAR8 chunk result source codec is unsupported")
    _require(
        result.get("sourceHeaderVariant") in {"legacy_raw_v1", "storage_fields_v1"},
        "FAR8 chunk result source header variant is unsupported",
    )
    tile_count = _exact_int(result.get("tileCount"), "FAR8 chunk result tileCount")
    _require(tile_count > 0, "FAR8 chunk result tileCount must be positive")
    _require(result.get("blockTileLimit") == group_tiles, "FAR8 chunk result group size mismatch")
    _require(result.get("zstdLevel") == zstd_level, "FAR8 chunk result Zstd level mismatch")
    _require(result.get("zstdVersion") == zstandard.__version__, "FAR8 chunk result Zstd version mismatch")
    _require(result.get("zstdReadbackVerified") is True, "FAR8 chunk result lacks Zstd readback proof")
    _require(result.get("payloadRawBytes") == tile_count * PAYLOAD_BYTES_PER_TILE, "FAR8 chunk payload raw bytes mismatch")
    _require(result.get("classRawBytes") == tile_count * CLASS_BYTES_PER_TILE, "FAR8 chunk class raw bytes mismatch")
    _sha256_field(result.get("payloadRawSha256"), "FAR8 chunk result payloadRawSha256")
    _sha256_field(result.get("classRawSha256"), "FAR8 chunk result classRawSha256")
    blocks = result.get("blocks")
    _require(isinstance(blocks, list) and blocks, "FAR8 chunk result blocks must be a nonempty array")
    _require(result.get("blockCount") == len(blocks), "FAR8 chunk result blockCount mismatch")
    next_tile = 0
    payload_stored = 0
    class_stored = 0
    for index, block in enumerate(blocks):
        _require(isinstance(block, dict) and set(block) == _BLOCK_RESULT_KEYS, "FAR8 block result field set is unsupported")
        _require(block.get("blockIndex") == index, "FAR8 block index is not contiguous")
        _require(block.get("startTile") == next_tile, "FAR8 block tile ranges are not contiguous")
        block_tiles = _exact_int(block.get("tileCount"), "FAR8 block tileCount")
        _require(1 <= block_tiles <= group_tiles, "FAR8 block tile count exceeds its bound")
        _require(block.get("payloadRawBytes") == block_tiles * PAYLOAD_BYTES_PER_TILE, "FAR8 block payload raw bytes mismatch")
        _require(block.get("classRawBytes") == block_tiles * CLASS_BYTES_PER_TILE, "FAR8 block class raw bytes mismatch")
        block_payload_stored = _exact_int(block.get("payloadStoredBytes"), "FAR8 block payloadStoredBytes")
        block_class_stored = _exact_int(block.get("classStoredBytes"), "FAR8 block classStoredBytes")
        _require(block_payload_stored > 0 and block_class_stored > 0, "FAR8 block stored byte counts must be positive")
        for field in ("payloadRawSha256", "payloadStoredSha256", "classRawSha256", "classStoredSha256"):
            _sha256_field(block.get(field), f"FAR8 block {field}")
        next_tile += block_tiles
        payload_stored += block_payload_stored
        class_stored += block_class_stored
    _require(next_tile == tile_count, "FAR8 block results do not cover the source tiles")
    _require(result.get("payloadStoredBytes") == payload_stored, "FAR8 chunk payload stored total mismatch")
    _require(result.get("classStoredBytes") == class_stored, "FAR8 chunk class stored total mismatch")
    header_bytes = _exact_int(result.get("headerBytes"), "FAR8 chunk result headerBytes")
    _require(header_bytes > 0, "FAR8 chunk result headerBytes must be positive")
    _require(result.get("blockRecordBytes") == FAR8_BLOCK_RECORD_BYTES, "FAR8 chunk block record size mismatch")
    _require(
        result.get("projectedFar8Bytes")
        == FAR8_FIXED_BYTES + header_bytes + len(blocks) * FAR8_BLOCK_RECORD_BYTES + payload_stored + class_stored,
        "FAR8 chunk projected byte count mismatch",
    )


def _checkpoint_path(
    checkpoint_dir: Path,
    *,
    source_manifest_sha256: str,
    source_name: str,
    group_tiles: int,
    zstd_level: int,
) -> Path:
    identity = {
        "schemaVersion": 1,
        "sourceManifestSha256": source_manifest_sha256,
        "sourceName": source_name,
        "groupTiles": group_tiles,
        "zstdLevel": zstd_level,
    }
    return checkpoint_dir / f"{hashlib.sha256(_canonical_bytes(identity)).hexdigest()}.json"


def _probe_implementation_sha256() -> str:
    return _hash_file(Path(__file__).resolve())


def _load_chunk_checkpoint(
    checkpoint_path: Path,
    *,
    source_path: Path,
    source_manifest_sha256: str,
    group_tiles: int,
    zstd_level: int,
    implementation_sha256: str,
) -> dict[str, Any] | None:
    if not checkpoint_path.exists():
        return None
    _require(checkpoint_path.is_file(), f"FAR8 checkpoint is not a file: {checkpoint_path}")
    raw = checkpoint_path.read_bytes()
    checkpoint = _decode_unique_json(raw, f"FAR8 checkpoint {checkpoint_path.name}")
    _require(raw == _canonical_bytes(checkpoint), "FAR8 checkpoint is not canonical JSON")
    _require(set(checkpoint) == _CHECKPOINT_KEYS, "FAR8 checkpoint field set is unsupported")
    _require(checkpoint.get("schemaVersion") == 1, "FAR8 checkpoint schema is unsupported")
    _require(checkpoint.get("checkpoint") == CHECKPOINT_KIND, "FAR8 checkpoint identity mismatch")
    _require(checkpoint.get("sourceManifestSha256") == source_manifest_sha256, "FAR8 checkpoint manifest identity mismatch")
    _require(checkpoint.get("sourceName") == source_path.name, "FAR8 checkpoint source name mismatch")
    _require(checkpoint.get("groupTiles") == group_tiles, "FAR8 checkpoint group size mismatch")
    _require(checkpoint.get("zstdLevel") == zstd_level, "FAR8 checkpoint Zstd level mismatch")
    _require(checkpoint.get("zstdVersion") == zstandard.__version__, "FAR8 checkpoint Zstd version mismatch")
    _require(
        checkpoint.get("probeImplementationSha256") == implementation_sha256,
        "FAR8 checkpoint was produced by a different probe implementation",
    )
    source_bytes = source_path.stat().st_size
    _require(checkpoint.get("sourceFar6Bytes") == source_bytes, "FAR8 checkpoint source byte count mismatch")
    source_sha256 = _hash_file(source_path)
    _require(checkpoint.get("sourceFar6Sha256") == source_sha256, "FAR8 checkpoint source SHA-256 mismatch")
    result = checkpoint.get("result")
    _require(isinstance(result, dict), "FAR8 checkpoint result must be an object")
    _require(
        checkpoint.get("resultSha256") == hashlib.sha256(_canonical_bytes(result)).hexdigest(),
        "FAR8 checkpoint result SHA-256 mismatch",
    )
    _validate_chunk_result(result, source_path=source_path, group_tiles=group_tiles, zstd_level=zstd_level)
    _require(result.get("sourceFar6Sha256") == source_sha256, "FAR8 checkpoint result source SHA-256 mismatch")
    # A canonical self-hash detects corruption; only exact remeasurement can validate this untrusted claim.
    remeasured = {
        "name": source_path.name,
        **measure_far6_chunk(source_path, group_tiles=group_tiles, zstd_level=zstd_level),
    }
    _require(
        _canonical_bytes(result) == _canonical_bytes(remeasured),
        "FAR8 checkpoint result differs from exact source recompression",
    )
    return remeasured


def _write_chunk_checkpoint(
    checkpoint_path: Path,
    *,
    result: dict[str, Any],
    source_path: Path,
    source_manifest_sha256: str,
    group_tiles: int,
    zstd_level: int,
    implementation_sha256: str,
) -> None:
    _validate_chunk_result(result, source_path=source_path, group_tiles=group_tiles, zstd_level=zstd_level)
    checkpoint = {
        "schemaVersion": 1,
        "checkpoint": CHECKPOINT_KIND,
        "sourceManifestSha256": source_manifest_sha256,
        "sourceName": source_path.name,
        "sourceFar6Bytes": result["sourceFar6Bytes"],
        "sourceFar6Sha256": result["sourceFar6Sha256"],
        "groupTiles": group_tiles,
        "zstdLevel": zstd_level,
        "zstdVersion": zstandard.__version__,
        "probeImplementationSha256": implementation_sha256,
        "resultSha256": hashlib.sha256(_canonical_bytes(result)).hexdigest(),
        "result": result,
    }
    write_canonical_report(checkpoint_path, checkpoint)


def _validate_package_tile_accounting(
    manifest: dict[str, Any],
    *,
    expected_chunks: int,
    decoded_tiles: int,
) -> tuple[int, int, int]:
    unique_tiles = _exact_int(manifest.get("recordCount"), "package recordCount")
    if "queueRowsAudited" not in manifest:
        _require("duplicateRows" not in manifest, "package duplicateRows requires queueRowsAudited")
        _require(decoded_tiles == unique_tiles, "FAR6 decoded tile count contradicts package recordCount")
        return unique_tiles, unique_tiles, 0

    audited_tiles = _exact_int(manifest.get("queueRowsAudited"), "package queueRowsAudited")
    _require(audited_tiles >= unique_tiles, "package queueRowsAudited is below recordCount")
    duplicate_rows = manifest.get("duplicateRows")
    _require(isinstance(duplicate_rows, list), "package duplicateRows must be an array")
    duplicate_count = audited_tiles - unique_tiles
    _require(len(duplicate_rows) == duplicate_count, "package duplicateRows does not reconcile audited and unique rows")
    if "duplicateQueueRowsDedupedForLookup" in manifest:
        _require(
            manifest.get("duplicateQueueRowsDedupedForLookup") == duplicate_count
            and type(manifest.get("duplicateQueueRowsDedupedForLookup")) is int,
            "package duplicateQueueRowsDedupedForLookup mismatch",
        )
    seen_keys: set[tuple[int, int, int]] = set()
    seen_duplicate_chunks: set[int] = set()
    for row in duplicate_rows:
        _require(
            isinstance(row, dict)
            and set(row) == {"key", "keptChunkId", "duplicateChunkId", "boundaryRepair"},
            "package duplicate row field set is unsupported",
        )
        key = row.get("key")
        _require(isinstance(key, dict) and set(key) == {"z", "x", "y"}, "package duplicate row key is invalid")
        z = _exact_int(key.get("z"), "package duplicate row z")
        x = _exact_int(key.get("x"), "package duplicate row x")
        y = _exact_int(key.get("y"), "package duplicate row y")
        _require(z <= 30 and x < (1 << z) and y < (1 << z), "package duplicate row tile key is outside its zoom")
        kept_chunk = _exact_int(row.get("keptChunkId"), "package duplicate row keptChunkId")
        duplicate_chunk = _exact_int(row.get("duplicateChunkId"), "package duplicate row duplicateChunkId")
        _require(
            1 <= kept_chunk <= expected_chunks
            and 1 <= duplicate_chunk <= expected_chunks
            and kept_chunk != duplicate_chunk,
            "package duplicate row chunk IDs are invalid",
        )
        _require(row.get("boundaryRepair") is True, "package duplicate row is not an explicit boundary repair")
        tile_key = (z, x, y)
        _require(tile_key not in seen_keys, "package duplicateRows repeats a tile key")
        _require(duplicate_chunk not in seen_duplicate_chunks, "package duplicateRows repeats a duplicate chunk ID")
        seen_keys.add(tile_key)
        seen_duplicate_chunks.add(duplicate_chunk)
    _require(decoded_tiles == audited_tiles, "FAR6 decoded tile count contradicts package queueRowsAudited")
    return unique_tiles, audited_tiles, duplicate_count


def measure_far6_package(
    package_root: Path,
    *,
    group_tiles: int = 16,
    zstd_level: int = 12,
    workers: int = 1,
    checkpoint_dir: Path | None = None,
    progress: Callable[[int, int, Path], None] | None = None,
) -> dict[str, Any]:
    """Measure every manifest-bound FAR6 chunk in one retained phone package."""

    root = Path(package_root)
    manifest_path = root / "manifest.json"
    chunks_dir = root / "chunks"
    _require(manifest_path.is_file(), "FAR6 package manifest.json is missing")
    _require(chunks_dir.is_dir(), "FAR6 package chunks directory is missing")
    manifest_bytes = manifest_path.read_bytes()
    manifest = _decode_unique_json(manifest_bytes, "FAR6 package manifest")
    expected_chunks = _exact_int(manifest.get("chunkCount"), "package chunkCount")
    _require(type(group_tiles) is int and 1 <= group_tiles <= 64, "group_tiles must be an integer in [1, 64]")
    _require(type(zstd_level) is int and -7 <= zstd_level <= 22, "zstd_level is outside the supported probe range")
    _require(type(workers) is int and 1 <= workers <= 32, "workers must be an integer in [1, 32]")
    paths = sorted(chunks_dir.glob("*.far6"), key=lambda item: item.name)
    _require(len(paths) == expected_chunks, "FAR6 package chunk inventory contradicts manifest chunkCount")
    _require(expected_chunks > 0, "FAR6 package must contain at least one chunk")

    manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
    checkpoint_root: Path | None = None
    implementation_sha256: str | None = None
    if checkpoint_dir is not None:
        checkpoint_root = Path(checkpoint_dir)
        checkpoint_root.mkdir(parents=True, exist_ok=True)
        _require(checkpoint_root.is_dir(), "FAR8 checkpoint path is not a directory")
        implementation_sha256 = _probe_implementation_sha256()

    measured_slots: list[dict[str, Any] | None] = [None] * len(paths)
    missing: list[tuple[int, Path]] = []
    completed = 0
    for slot, path in enumerate(paths):
        cached: dict[str, Any] | None = None
        if checkpoint_root is not None:
            checkpoint_path = _checkpoint_path(
                checkpoint_root,
                source_manifest_sha256=manifest_sha256,
                source_name=path.name,
                group_tiles=group_tiles,
                zstd_level=zstd_level,
            )
            cached = _load_chunk_checkpoint(
                checkpoint_path,
                source_path=path,
                source_manifest_sha256=manifest_sha256,
                group_tiles=group_tiles,
                zstd_level=zstd_level,
                implementation_sha256=implementation_sha256,
            )
        if cached is None:
            missing.append((slot, path))
        else:
            measured_slots[slot] = cached
            completed += 1
            if progress is not None:
                progress(completed, len(paths), path)

    tasks = [(str(path), group_tiles, zstd_level) for _, path in missing]
    if workers == 1:
        iterator = map(_measure_package_chunk_task, tasks)
        measured_missing = zip(missing, iterator)
        for (slot, path), chunk in measured_missing:
            measured_slots[slot] = chunk
            if checkpoint_root is not None:
                checkpoint_path = _checkpoint_path(
                    checkpoint_root,
                    source_manifest_sha256=manifest_sha256,
                    source_name=path.name,
                    group_tiles=group_tiles,
                    zstd_level=zstd_level,
                )
                _write_chunk_checkpoint(
                    checkpoint_path,
                    result=chunk,
                    source_path=path,
                    source_manifest_sha256=manifest_sha256,
                    group_tiles=group_tiles,
                    zstd_level=zstd_level,
                    implementation_sha256=implementation_sha256,
                )
            completed += 1
            if progress is not None:
                progress(completed, len(paths), path)
    else:
        with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as executor:
            iterator = executor.map(_measure_package_chunk_task, tasks, chunksize=1)
            for (slot, path), chunk in zip(missing, iterator):
                measured_slots[slot] = chunk
                if checkpoint_root is not None:
                    checkpoint_path = _checkpoint_path(
                        checkpoint_root,
                        source_manifest_sha256=manifest_sha256,
                        source_name=path.name,
                        group_tiles=group_tiles,
                        zstd_level=zstd_level,
                    )
                    _write_chunk_checkpoint(
                        checkpoint_path,
                        result=chunk,
                        source_path=path,
                        source_manifest_sha256=manifest_sha256,
                        group_tiles=group_tiles,
                        zstd_level=zstd_level,
                        implementation_sha256=implementation_sha256,
                    )
                completed += 1
                if progress is not None:
                    progress(completed, len(paths), path)
    _require(all(item is not None for item in measured_slots), "FAR8 package measurement left an incomplete chunk slot")
    measured = [item for item in measured_slots if item is not None]
    source_tiles = sum(item["tileCount"] for item in measured)
    unique_tiles, audited_tiles, duplicate_tiles = _validate_package_tile_accounting(
        manifest,
        expected_chunks=expected_chunks,
        decoded_tiles=source_tiles,
    )

    index_bytes = 0
    index_sha256: str | None = None
    index_name = manifest.get("index")
    if index_name is not None:
        _require(isinstance(index_name, str) and index_name and Path(index_name).name == index_name, "package index path must be one local filename")
        index_path = root / index_name
        _require(index_path.is_file(), "manifest-bound FAR6 package index is missing")
        index_bytes = index_path.stat().st_size
        index_sha256 = _hash_file(index_path)
        declared_index_sha = manifest.get("indexSha256")
        if declared_index_sha is not None:
            _require(index_sha256 == _sha256_field(declared_index_sha, "package indexSha256"), "FAR6 package index SHA-256 mismatch")

    projected_chunks = sum(item["projectedFar8Bytes"] for item in measured)
    source_chunk_bytes = sum(item["sourceFar6Bytes"] for item in measured)
    source_package_bytes = source_chunk_bytes + index_bytes + len(manifest_bytes)
    projected_package_bytes = projected_chunks + index_bytes + len(manifest_bytes)
    return {
        "schemaVersion": 1,
        "probe": "flight-alert-far8-bounded-zstd-package-v1",
        "sourceManifestSha256": manifest_sha256,
        "sourceManifestBytes": len(manifest_bytes),
        "sourceIndexSha256": index_sha256,
        "sourceIndexBytes": index_bytes,
        "sourceChunkCount": len(measured),
        "sourceTileCount": source_tiles,
        "sourceDecodedTileCount": source_tiles,
        "sourceQueueRowsAudited": audited_tiles,
        "sourceUniqueTileCount": unique_tiles,
        "sourceDuplicateTileCount": duplicate_tiles,
        "sourceChunkBytes": source_chunk_bytes,
        "sourcePackageBytes": source_package_bytes,
        "blockTileLimit": group_tiles,
        "zstdLevel": zstd_level,
        "zstdVersion": zstandard.__version__,
        "projectedChunkBytes": projected_chunks,
        "projectedFar8PackageBytes": projected_package_bytes,
        "packageCompressionRatio": projected_package_bytes / source_package_bytes,
        "chunks": measured,
    }


def write_canonical_report(path: Path, report: dict[str, Any]) -> None:
    """Atomically persist canonical bytes and verify the public readback."""

    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    encoded = _canonical_bytes(report)
    temporary = destination.with_name(f".{destination.name}.tmp-{os.getpid()}")
    try:
        with temporary.open("wb") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        _require(temporary.read_bytes() == encoded, "staged FAR8 probe report readback differs")
        os.replace(temporary, destination)
        _require(destination.read_bytes() == encoded, "installed FAR8 probe report readback differs")
    finally:
        if temporary.exists():
            temporary.unlink()


def _progress(index: int, total: int, path: Path) -> None:
    print(f"[{index}/{total}] {path.name}", file=sys.stderr, flush=True)


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("chunk", "package"):
        command = subparsers.add_parser(name)
        command.add_argument("--input", required=True, type=Path)
        command.add_argument("--output", required=True, type=Path)
        command.add_argument("--group-tiles", type=int, default=16)
        command.add_argument("--zstd-level", type=int, default=12)
        if name == "package":
            command.add_argument("--workers", type=int, default=1)
            command.add_argument("--checkpoint-dir", type=Path)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    if args.command == "chunk":
        report = measure_far6_chunk(args.input, group_tiles=args.group_tiles, zstd_level=args.zstd_level)
    else:
        report = measure_far6_package(
            args.input,
            group_tiles=args.group_tiles,
            zstd_level=args.zstd_level,
            workers=args.workers,
            checkpoint_dir=args.checkpoint_dir,
            progress=_progress,
        )
    write_canonical_report(args.output, report)
    print(
        json.dumps(
            {
                "output": str(args.output),
                "sha256": _hash_file(args.output),
                "bytes": args.output.stat().st_size,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
