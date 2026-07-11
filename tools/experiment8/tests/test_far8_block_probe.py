from __future__ import annotations

import hashlib
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path
from typing import Any
from unittest import mock

from tools.experiment8.far8_block_probe import (
    FAR8_BLOCK_RECORD_BYTES,
    FAR8_FIXED_BYTES,
    Far8ProbeError,
    measure_far6_chunk,
    measure_far6_package,
    write_canonical_report,
)


TILE_PAYLOAD_BYTES = 256 * 256 * 4
TILE_CLASS_BYTES = 256 * 256


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _fixture_bytes(tile_count: int) -> tuple[bytes, bytes]:
    payload = b"".join(
        bytes(((tile * 17 + index * 13) & 0xFF for index in range(TILE_PAYLOAD_BYTES)))
        for tile in range(tile_count)
    )
    classes = b"".join(
        bytes(((tile * 7 + index * 3) % 23 for index in range(TILE_CLASS_BYTES)))
        for tile in range(tile_count)
    )
    return payload, classes


def _write_far6(path: Path, tile_count: int, codec: str) -> tuple[bytes, bytes]:
    payload, classes = _fixture_bytes(tile_count)
    if codec == "raw":
        payload_stored = payload
        class_stored = classes
        level = 0
    elif codec == "zlib_miniz":
        payload_stored = zlib.compress(payload, 9)
        class_stored = zlib.compress(classes, 9)
        level = 9
    else:
        raise AssertionError(codec)
    header = {
        "schemaVersion": 1,
        "magic": "FAR6",
        "version": 1,
        "source": "unit-test-real-bytes",
        "classification": "unit-test",
        "tileCount": tile_count,
        "tileSize": 256,
        "payloadCodec": "raw_fixture_rgba8" if codec == "raw" else "zlib_fixture_rgba8",
        "classCodec": "uint8_fixture" if codec == "raw" else "zlib_uint8_fixture",
        "payloadBytes": len(payload),
        "classBytes": len(classes),
        "payloadSha256": _sha256(payload),
        "classSha256": _sha256(classes),
    }
    if codec == "zlib_miniz":
        header.update(
            {
                "storageCodec": codec,
                "zlibLevel": level,
                "payloadStoredBytes": len(payload_stored),
                "classStoredBytes": len(class_stored),
                "payloadStoredSha256": _sha256(payload_stored),
                "classStoredSha256": _sha256(class_stored),
            }
        )
    encoded_header = json.dumps(header, separators=(",", ":")).encode("utf-8")
    fixed = struct.pack(
        "<4sIIIQQ",
        b"FAR6",
        1,
        tile_count,
        len(encoded_header),
        len(payload_stored),
        len(class_stored),
    )
    path.write_bytes(fixed + encoded_header + payload_stored + class_stored)
    return payload, classes


class Far8BlockProbeTest(unittest.TestCase):
    def test_raw_far6_is_measured_in_bounded_lossless_blocks(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "raw.far6"
            payload, classes = _write_far6(path, tile_count=3, codec="raw")

            result = measure_far6_chunk(path, group_tiles=2, zstd_level=3)

            self.assertEqual(2, result["blockCount"])
            self.assertEqual(3, result["tileCount"])
            self.assertEqual(_sha256(payload), result["payloadRawSha256"])
            self.assertEqual(_sha256(classes), result["classRawSha256"])
            self.assertEqual(_sha256(path.read_bytes()), result["sourceFar6Sha256"])
            self.assertTrue(result["zstdReadbackVerified"])
            self.assertEqual([2, 1], [block["tileCount"] for block in result["blocks"]])
            self.assertLessEqual(
                max(block["payloadRawBytes"] + block["classRawBytes"] for block in result["blocks"]),
                2 * (TILE_PAYLOAD_BYTES + TILE_CLASS_BYTES),
            )
            self.assertEqual(
                FAR8_FIXED_BYTES
                + result["headerBytes"]
                + result["blockCount"] * FAR8_BLOCK_RECORD_BYTES
                + result["payloadStoredBytes"]
                + result["classStoredBytes"],
                result["projectedFar8Bytes"],
            )

    def test_zlib_far6_is_verified_and_measurement_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "zlib.far6"
            _write_far6(path, tile_count=2, codec="zlib_miniz")

            first = measure_far6_chunk(path, group_tiles=1, zstd_level=3)
            second = measure_far6_chunk(path, group_tiles=1, zstd_level=3)

            self.assertEqual(first, second)
            self.assertEqual("zlib_miniz", first["sourceStorageCodec"])
            self.assertTrue(all(block["payloadStoredSha256"] for block in first["blocks"]))
            self.assertTrue(all(block["classStoredSha256"] for block in first["blocks"]))

    def test_corruption_and_trailing_bytes_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "corrupt.far6"
            _write_far6(path, tile_count=1, codec="zlib_miniz")
            damaged = bytearray(path.read_bytes())
            damaged[-1] ^= 0x01
            path.write_bytes(damaged)
            with self.assertRaises(Far8ProbeError):
                measure_far6_chunk(path, group_tiles=1, zstd_level=3)

            clean = Path(directory) / "trailing.far6"
            _write_far6(clean, tile_count=1, codec="raw")
            clean.write_bytes(clean.read_bytes() + b"unexpected")
            with self.assertRaises(Far8ProbeError):
                measure_far6_chunk(clean, group_tiles=1, zstd_level=3)

    def test_package_inventory_and_canonical_report_are_bound(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            chunks = root / "chunks"
            chunks.mkdir()
            _write_far6(chunks / "b.far6", tile_count=1, codec="raw")
            _write_far6(chunks / "a.far6", tile_count=1, codec="zlib_miniz")
            manifest = {
                "schemaVersion": 1,
                "package": "fixture",
                "chunkCount": 2,
                "recordCount": 2,
            }
            manifest_path = root / "manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            report = measure_far6_package(root, group_tiles=1, zstd_level=3)
            parallel = measure_far6_package(root, group_tiles=1, zstd_level=3, workers=2)
            parallel_16 = measure_far6_package(root, group_tiles=1, zstd_level=3, workers=16)
            self.assertEqual(report, parallel)
            self.assertEqual(report, parallel_16)
            self.assertEqual(["a.far6", "b.far6"], [item["name"] for item in report["chunks"]])
            self.assertEqual(2, report["sourceChunkCount"])
            self.assertEqual(2, report["sourceTileCount"])
            self.assertEqual(_sha256(manifest_path.read_bytes()), report["sourceManifestSha256"])

            output = root / "report.json"
            write_canonical_report(output, report)
            expected = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
            self.assertEqual(expected, output.read_bytes())

            manifest["chunkCount"] = 3
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaises(Far8ProbeError):
                measure_far6_package(root, group_tiles=1, zstd_level=3)

    def test_package_distinguishes_audited_rows_from_explicit_repair_duplicates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            chunks = root / "chunks"
            chunks.mkdir()
            _write_far6(chunks / "a.far6", tile_count=2, codec="zlib_miniz")
            _write_far6(chunks / "b.far6", tile_count=1, codec="raw")
            manifest_path = root / "manifest.json"
            manifest = {
                "schemaVersion": 1,
                "package": "fixture-with-boundary-repair",
                "chunkCount": 2,
                "recordCount": 2,
                "queueRowsAudited": 3,
                "duplicateRows": [
                    {
                        "key": {"z": 1, "x": 1, "y": 0},
                        "keptChunkId": 1,
                        "duplicateChunkId": 2,
                        "boundaryRepair": True,
                    }
                ],
            }
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            report = measure_far6_package(root, group_tiles=1, zstd_level=3)

            self.assertEqual(3, report["sourceDecodedTileCount"])
            self.assertEqual(3, report["sourceQueueRowsAudited"])
            self.assertEqual(2, report["sourceUniqueTileCount"])
            self.assertEqual(1, report["sourceDuplicateTileCount"])

            malformed = [
                {**manifest, "queueRowsAudited": 2},
                {**manifest, "recordCount": 1},
                {
                    **manifest,
                    "duplicateRows": [
                        {
                            **manifest["duplicateRows"][0],
                            "boundaryRepair": False,
                        }
                    ],
                },
                {
                    **manifest,
                    "duplicateRows": manifest["duplicateRows"] * 2,
                    "queueRowsAudited": 4,
                },
                {
                    **manifest,
                    "duplicateRows": [
                        {
                            **manifest["duplicateRows"][0],
                            "key": {"z": 1, "x": 2, "y": 0},
                        }
                    ],
                },
            ]
            for case in malformed:
                with self.subTest(case=case):
                    manifest_path.write_text(json.dumps(case), encoding="utf-8")
                    with self.assertRaises(Far8ProbeError):
                        measure_far6_package(root, group_tiles=1, zstd_level=3)

    def test_package_checkpoints_resume_by_exact_remeasurement_and_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "package"
            chunks = root / "chunks"
            chunks.mkdir(parents=True)
            _write_far6(chunks / "a.far6", tile_count=1, codec="zlib_miniz")
            _write_far6(chunks / "b.far6", tile_count=1, codec="raw")
            (root / "manifest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "package": "checkpoint-fixture",
                        "chunkCount": 2,
                        "recordCount": 2,
                    }
                ),
                encoding="utf-8",
            )
            checkpoints = Path(directory) / "checkpoints"

            first = measure_far6_package(
                root,
                group_tiles=1,
                zstd_level=3,
                checkpoint_dir=checkpoints,
            )
            checkpoint_files = sorted(checkpoints.glob("*.json"))
            self.assertEqual(2, len(checkpoint_files))

            with mock.patch(
                "tools.experiment8.far8_block_probe.measure_far6_chunk",
                wraps=measure_far6_chunk,
            ) as remeasured:
                resumed = measure_far6_package(
                    root,
                    group_tiles=1,
                    zstd_level=3,
                    checkpoint_dir=checkpoints,
                    workers=2,
                )
            self.assertEqual(first, resumed)
            self.assertEqual(2, remeasured.call_count)
            first_report = Path(directory) / "first-report.json"
            resumed_report = Path(directory) / "resumed-report.json"
            write_canonical_report(first_report, first)
            write_canonical_report(resumed_report, resumed)
            self.assertEqual(first_report.read_bytes(), resumed_report.read_bytes())
            self.assertEqual(_sha256(first_report.read_bytes()), _sha256(resumed_report.read_bytes()))

            checkpoint_files[0].unlink()
            with mock.patch(
                "tools.experiment8.far8_block_probe.measure_far6_chunk",
                wraps=measure_far6_chunk,
            ) as measured:
                partial = measure_far6_package(
                    root,
                    group_tiles=1,
                    zstd_level=3,
                    checkpoint_dir=checkpoints,
                )
            self.assertEqual(first, partial)
            self.assertEqual(2, measured.call_count)

            before_wrong_parameters = set(checkpoints.glob("*.json"))
            wrong_parameters = measure_far6_package(
                root,
                group_tiles=1,
                zstd_level=4,
                checkpoint_dir=checkpoints,
            )
            self.assertNotEqual(first["zstdLevel"], wrong_parameters["zstdLevel"])
            self.assertEqual(2, len(set(checkpoints.glob("*.json")) - before_wrong_parameters))

            original = checkpoint_files[1].read_bytes()
            checkpoint_files[1].write_bytes(b"not-json")
            with self.assertRaises(Far8ProbeError):
                measure_far6_package(
                    root,
                    group_tiles=1,
                    zstd_level=3,
                    checkpoint_dir=checkpoints,
                )
            checkpoint_files[1].write_bytes(original)

            source = chunks / "b.far6"
            damaged = bytearray(source.read_bytes())
            damaged[-1] ^= 1
            source.write_bytes(damaged)
            with self.assertRaises(Far8ProbeError):
                measure_far6_package(
                    root,
                    group_tiles=1,
                    zstd_level=3,
                    checkpoint_dir=checkpoints,
                )

    def test_canonically_resigned_checkpoint_tampering_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "package"
            chunks = root / "chunks"
            chunks.mkdir(parents=True)
            _write_far6(chunks / "a.far6", tile_count=3, codec="zlib_miniz")
            (root / "manifest.json").write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "package": "checkpoint-tamper-fixture",
                        "chunkCount": 1,
                        "recordCount": 3,
                    }
                ),
                encoding="utf-8",
            )
            checkpoints = Path(directory) / "checkpoints"
            measure_far6_package(
                root,
                group_tiles=2,
                zstd_level=3,
                checkpoint_dir=checkpoints,
            )

            checkpoint_path = next(checkpoints.glob("*.json"))
            original = checkpoint_path.read_bytes()

            def stored_sizes(result: dict[str, Any]) -> None:
                delta = 1_000_000_000
                blocks = result["blocks"]
                assert isinstance(blocks, list)
                blocks[0]["payloadStoredBytes"] += delta
                result["payloadStoredBytes"] += delta
                result["projectedFar8Bytes"] += delta

            def compressed_hash(result: dict[str, Any]) -> None:
                blocks = result["blocks"]
                assert isinstance(blocks, list)
                blocks[0]["payloadStoredSha256"] = "0" * 64

            def class_stored_sizes(result: dict[str, Any]) -> None:
                delta = 1_000_000_000
                blocks = result["blocks"]
                assert isinstance(blocks, list)
                blocks[0]["classStoredBytes"] += delta
                result["classStoredBytes"] += delta
                result["projectedFar8Bytes"] += delta

            def class_compressed_hash(result: dict[str, Any]) -> None:
                blocks = result["blocks"]
                assert isinstance(blocks, list)
                blocks[0]["classStoredSha256"] = "0" * 64

            def header_and_projection(result: dict[str, Any]) -> None:
                result["headerBytes"] += 1
                result["projectedFar8Bytes"] += 1

            def source_codec(result: dict[str, Any]) -> None:
                result["sourceStorageCodec"] = "raw"

            def block_index(result: dict[str, Any]) -> None:
                blocks = result["blocks"]
                assert isinstance(blocks, list)
                blocks[0]["blockIndex"] = 1

            def projected_total(result: dict[str, Any]) -> None:
                result["projectedFar8Bytes"] += 1

            def source_slice_bounds(result: dict[str, Any]) -> None:
                blocks = result["blocks"]
                assert isinstance(blocks, list) and len(blocks) == 2
                blocks[0]["tileCount"] = 1
                blocks[0]["payloadRawBytes"] = TILE_PAYLOAD_BYTES
                blocks[0]["classRawBytes"] = TILE_CLASS_BYTES
                blocks[1]["startTile"] = 1
                blocks[1]["tileCount"] = 2
                blocks[1]["payloadRawBytes"] = 2 * TILE_PAYLOAD_BYTES
                blocks[1]["classRawBytes"] = 2 * TILE_CLASS_BYTES

            def source_slice_hash(result: dict[str, Any]) -> None:
                blocks = result["blocks"]
                assert isinstance(blocks, list)
                blocks[0]["payloadRawSha256"] = "0" * 64

            def aggregate_raw_hash(result: dict[str, Any]) -> None:
                result["classRawSha256"] = "0" * 64

            cases = [
                ("stored sizes", stored_sizes, "differs from exact source recompression", True),
                ("compressed hash", compressed_hash, "differs from exact source recompression", True),
                ("class stored sizes", class_stored_sizes, "differs from exact source recompression", True),
                ("class compressed hash", class_compressed_hash, "differs from exact source recompression", True),
                ("header and projection", header_and_projection, "differs from exact source recompression", True),
                ("codec", source_codec, "differs from exact source recompression", True),
                ("block index", block_index, "block index is not contiguous", True),
                ("projected total", projected_total, "projected byte count mismatch", True),
                ("source slice bounds", source_slice_bounds, "differs from exact source recompression", True),
                ("source slice hash", source_slice_hash, "differs from exact source recompression", True),
                ("aggregate raw hash", aggregate_raw_hash, "differs from exact source recompression", True),
                ("canonical self-hash", lambda result: None, "result SHA-256 mismatch", False),
            ]
            for name, mutate, expected_error, resign in cases:
                with self.subTest(name=name):
                    checkpoint = json.loads(original.decode("utf-8"))
                    result = checkpoint["result"]
                    assert isinstance(result, dict)
                    mutate(result)
                    if resign:
                        checkpoint["resultSha256"] = _sha256(
                            (json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
                        )
                    else:
                        checkpoint["resultSha256"] = "0" * 64
                    checkpoint_path.write_bytes(
                        (json.dumps(checkpoint, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
                    )
                    with self.assertRaisesRegex(Far8ProbeError, expected_error):
                        measure_far6_package(
                            root,
                            group_tiles=2,
                            zstd_level=3,
                            checkpoint_dir=checkpoints,
                        )


if __name__ == "__main__":
    unittest.main()
