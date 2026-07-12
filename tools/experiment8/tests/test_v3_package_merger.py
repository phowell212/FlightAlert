from __future__ import annotations

import hashlib
import io
import json
import shutil
import struct
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest import mock

from tools.experiment8.model import TileKey
from tools.experiment8.renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    RendererTileRecord,
    build_package,
    decode_tile_payload,
    encode_tile_payload,
)
from tools.experiment8.sourced_text import create_sourced_map_text
from tools.experiment8.tests.test_renderer_tile_package import (
    CAIRO_TEXT,
    _cairo_renderer_record,
    _cairo_sourced_text,
    _line_renderer_record,
)


def _write_package(
    directory: Path,
    package_id: str,
    payloads: dict[TileKey, bytes],
    *,
    complete_declared_scope: bool = True,
    complete_whole_earth_dictionary: bool = False,
) -> None:
    artifacts = build_package(
        package_id,
        payloads,
        complete_declared_scope=complete_declared_scope,
        complete_whole_earth_dictionary=complete_whole_earth_dictionary,
    )
    directory.mkdir()
    (directory / "manifest.json").write_bytes(artifacts.manifest_bytes)
    (directory / "records.fadictpack").write_bytes(artifacts.records_bytes)
    (directory / "tile-index.bin").write_bytes(artifacts.index_bytes)


def _read_payload(directory: Path, tile: TileKey) -> bytes | None:
    manifest = json.loads((directory / "manifest.json").read_text("utf-8"))
    ordinal = 0
    selected = None
    for item in manifest["coverage"]["zoomRanges"]:
        width = item["xMax"] - item["xMin"] + 1
        if (
            item["z"] == tile.z
            and item["xMin"] <= tile.x <= item["xMax"]
            and item["yMin"] <= tile.y <= item["yMax"]
        ):
            selected = ordinal + (tile.y - item["yMin"]) * width + tile.x - item["xMin"]
            break
        ordinal += item["tileCount"]
    if selected is None:
        return None
    with (directory / "tile-index.bin").open("rb") as handle:
        handle.seek(selected * INDEX_ENTRY_BYTES)
        entry = handle.read(INDEX_ENTRY_BYTES)
    if entry == b"\0" * INDEX_ENTRY_BYTES:
        return None
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    assert flags == 1
    with (directory / "records.fadictpack").open("rb") as handle:
        handle.seek(offset)
        compressed = handle.read(compressed_length)
    payload = zlib.decompress(compressed, wbits=-zlib.MAX_WBITS)
    assert len(payload) == raw_length
    assert int.from_bytes(hashlib.sha256(payload).digest()[:4], "big") == expected_hash32
    return payload


def _raw_envelopes(payload: bytes) -> tuple[bytes, ...]:
    offset = len(b"FAE8TILE1\0") + struct.calcsize("<BIII")
    count = struct.unpack_from("<I", payload, len(b"FAE8TILE1\0") + 9)[0]
    envelopes = []
    for _ in range(count):
        start = offset
        renderer_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4 + renderer_length
        sourced_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4
        if sourced_length:
            offset += 32 + sourced_length
        envelopes.append(payload[start:offset])
    assert offset == len(payload)
    return tuple(envelopes)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class V3PackageMergerTests(unittest.TestCase):
    def test_compressed_input_length_is_bounded_before_record_read(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            _InputPackage,
            _InputState,
            _Range,
            _Window,
            _read_input_tile,
        )

        class ForbiddenRecordsRead:
            def read(self, length: int) -> bytes:
                raise AssertionError(f"attempted to materialize {length} compressed bytes")

        tile = TileKey(0, 0, 0)
        compressed_length = (1 << 32) - 1
        package = _InputPackage(
            directory=Path("."),
            package_id="bounded-input",
            manifest_sha256="0" * 64,
            manifest_bytes=1,
            records_sha256="0" * 64,
            records_bytes=compressed_length,
            index_sha256="0" * 64,
            index_bytes=INDEX_ENTRY_BYTES,
            ranges=(_Range(_Window(0, 0, 0, 0, 0), 0),),
            tile_count=1,
            complete_declared_scope=False,
            complete_whole_earth_dictionary=False,
        )
        state = _InputState(
            index_handle=io.BytesIO(
                struct.pack("<QIIII", 0, compressed_length, 1, 0, 1)
            ),
            records_handle=ForbiddenRecordsRead(),
        )

        with self.assertRaisesRegex(V3PackageMergeError, "compressed.*bound"):
            _read_input_tile(package, state, tile)

    def test_same_tile_contributions_are_deduped_before_payload_assembly(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            supplements = tuple(root / f"supplement-{index}" for index in range(8))
            output = root / "output"
            _write_package(primary, "primary", {tile: payload})
            for index, supplement in enumerate(supplements):
                _write_package(supplement, f"supplement-{index}", {tile: payload})
            original_merge_payload = v3_package_merger._merge_payload

            def guarded_merge_payload(tile_key, merged_records, *arguments):
                self.assertIsInstance(merged_records, dict)
                self.assertEqual(len(merged_records), 1)
                return original_merge_payload(tile_key, merged_records, *arguments)

            with mock.patch.object(
                v3_package_merger,
                "_merge_payload",
                guarded_merge_payload,
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=supplements,
                    output_directory=output,
                    package_id="incremental-dedupe",
                )

    def test_rectangular_union_rejects_an_android_unreadable_index(self) -> None:
        from tools.experiment8 import v3_package_merger

        first = TileKey(14, 0, 0)
        last = TileKey(14, (1 << 14) - 1, (1 << 14) - 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = root / "primary", root / "supplement"
            _write_package(
                primary,
                "primary",
                {
                    first: encode_tile_payload(
                        first,
                        [RendererTileRecord(_line_renderer_record(first), None)],
                    )
                },
            )
            _write_package(
                supplement,
                "supplement",
                {
                    last: encode_tile_payload(
                        last,
                        [RendererTileRecord(_line_renderer_record(last), None)],
                    )
                },
            )

            with mock.patch.object(
                v3_package_merger,
                "_tiles_index_order",
                side_effect=AssertionError("oversized union traversal began"),
            ):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError,
                    "Android.*index",
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(supplement,),
                        output_directory=root / "output",
                        package_id="oversized-union",
                    )

    def test_scope_extending_input_must_justify_whole_earth_completeness(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        primary_tile = TileKey(0, 0, 0)
        supplement_tiles = tuple(
            TileKey(1, x, y)
            for y in range(2)
            for x in range(2)
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = (
                root / "primary",
                root / "supplement",
                root / "output",
            )
            _write_package(
                primary,
                "whole-primary",
                {
                    primary_tile: encode_tile_payload(
                        primary_tile,
                        [RendererTileRecord(_line_renderer_record(primary_tile), None)],
                    )
                },
                complete_whole_earth_dictionary=True,
            )
            _write_package(
                supplement,
                "scope-extending-supplement",
                {
                    tile: encode_tile_payload(
                        tile,
                        [RendererTileRecord(_line_renderer_record(tile), None)],
                    )
                    for tile in supplement_tiles
                },
                complete_whole_earth_dictionary=False,
            )

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=output,
                package_id="conservative-whole-earth",
            )

            self.assertFalse(
                result.manifest["coverage"]["completeWholeEarthDictionary"]
            )

    def test_input_hashes_bind_the_exact_streams_consumed(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        renderer = _cairo_renderer_record()
        canonical = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, _cairo_sourced_text())],
        )
        alternate_text = create_sourced_map_text(
            primary=CAIRO_TEXT,
            primary_source_field_id=201,
            declared_english="Hotel",
            english_source_field_id=202,
        )
        alternate = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, alternate_text)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, replacement, output = (
                root / "primary",
                root / "replacement",
                root / "output",
            )
            _write_package(primary, "mutable-primary", {tile: canonical})
            _write_package(replacement, "mutable-primary", {tile: alternate})
            self.assertEqual(
                (primary / "records.fadictpack").stat().st_size,
                (replacement / "records.fadictpack").stat().st_size,
            )
            original_union_windows = v3_package_merger._union_windows

            def replace_after_hash(packages):
                shutil.copyfile(
                    replacement / "records.fadictpack",
                    primary / "records.fadictpack",
                )
                shutil.copyfile(
                    replacement / "tile-index.bin",
                    primary / "tile-index.bin",
                )
                return original_union_windows(packages)

            with mock.patch.object(
                v3_package_merger,
                "_union_windows",
                replace_after_hash,
            ):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError,
                    "changed while being merged",
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(),
                        output_directory=output,
                        package_id="hash-bound-input",
                    )
            self.assertFalse(output.exists())

    def test_manifest_binding_uses_the_exact_bytes_read_after_stat(self) -> None:
        from tools.experiment8.v3_package_merger import _load_input

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package_directory = root / "package"
            _write_package(package_directory, "manifest-race", {tile: payload})
            manifest_path = package_directory / "manifest.json"
            expanded_manifest = manifest_path.read_bytes() + b" "
            original_stat = Path.stat
            manifest_stat_calls = 0

            def racing_stat(path: Path, *arguments, **keywords):
                nonlocal manifest_stat_calls
                result = original_stat(path, *arguments, **keywords)
                if path == manifest_path:
                    manifest_stat_calls += 1
                    if manifest_stat_calls == 2:
                        manifest_path.write_bytes(expanded_manifest)
                return result

            with mock.patch.object(Path, "stat", racing_stat):
                loaded = _load_input(package_directory)

            self.assertEqual(loaded.manifest_bytes, len(expanded_manifest))
            self.assertEqual(
                loaded.manifest_sha256,
                hashlib.sha256(expanded_manifest).hexdigest(),
            )

    def test_manifest_growth_race_cannot_bypass_its_read_bound(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            _load_input,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package_directory = root / "package"
            _write_package(package_directory, "manifest-bound", {tile: payload})
            manifest_path = package_directory / "manifest.json"
            manifest = manifest_path.read_bytes()
            oversized_manifest = manifest + b" " * (
                16 * 1024 * 1024 + 1 - len(manifest)
            )
            original_stat = Path.stat
            manifest_stat_calls = 0

            def racing_stat(path: Path, *arguments, **keywords):
                nonlocal manifest_stat_calls
                result = original_stat(path, *arguments, **keywords)
                if path == manifest_path:
                    manifest_stat_calls += 1
                    if manifest_stat_calls == 2:
                        manifest_path.write_bytes(oversized_manifest)
                return result

            with mock.patch.object(Path, "stat", racing_stat):
                with self.assertRaisesRegex(
                    V3PackageMergeError,
                    "manifest byte length.*bound",
                ):
                    _load_input(package_directory)

    def test_output_hashes_are_reverified_after_atomic_publication(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, output = root / "primary", root / "output"
            _write_package(primary, "primary", {tile: payload})
            original_replace = v3_package_merger.os.replace

            def publish_tampered(source: Path, destination: Path) -> None:
                records_path = source / "records.fadictpack"
                records = bytearray(records_path.read_bytes())
                records[len(records) // 2] ^= 0x80
                records_path.write_bytes(records)
                original_replace(source, destination)

            with mock.patch.object(
                v3_package_merger.os,
                "replace",
                publish_tampered,
            ):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError,
                    "published output differs",
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(),
                        output_directory=output,
                        package_id="publication-bound-output",
                    )
            self.assertFalse(output.exists())

    def test_same_tile_merge_preserves_envelopes_orders_and_dedupes(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        cairo = encode_tile_payload(
            tile,
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )
        line = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, duplicate, output = (
                root / "primary",
                root / "supplement",
                root / "duplicate",
                root / "merged",
            )
            _write_package(primary, "primary", {tile: cairo})
            _write_package(supplement, "supplement", {tile: line})
            _write_package(duplicate, "duplicate", {tile: cairo})

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement, duplicate),
                output_directory=output,
                package_id="merged-reference-v3",
            )

            payload = _read_payload(output, tile)
            self.assertIsNotNone(payload)
            assert payload is not None
            decoded = decode_tile_payload(tile, payload)
            self.assertEqual(
                [record.renderer_record.posting.feature_id for record in decoded.records],
                [0x0123456789ABCDEF, 0x8877665544332211],
            )
            self.assertEqual(
                set(_raw_envelopes(payload)),
                set(_raw_envelopes(line) + _raw_envelopes(cairo)),
            )
            self.assertEqual(result.output_directory, output)
            counts = {
                item["semanticSubtype"]: item
                for item in result.receipt["subtypeCounts"]
            }
            self.assertEqual(len(counts), 23)
            self.assertEqual(
                counts[210],
                {
                    "semanticSubtype": 210,
                    "semanticSubtypeName": "CITY_TOWN",
                    "distinctFeatureIds": 1,
                    "canonicalVariantIds": 1,
                    "postings": 1,
                },
            )
            self.assertEqual(counts[560]["postings"], 1)

    def test_same_posting_with_divergent_sourced_text_is_rejected(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        renderer = _cairo_renderer_record()
        canonical = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, _cairo_sourced_text())],
        )
        divergent_text = create_sourced_map_text(
            primary=CAIRO_TEXT,
            primary_source_field_id=201,
            declared_english="Al Qahirah",
            english_source_field_id=202,
        )
        divergent = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, divergent_text)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(primary, "primary", {tile: canonical})
            _write_package(supplement, "supplement", {tile: divergent})

            with self.assertRaisesRegex(V3PackageMergeError, "divergent duplicate"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=output,
                    package_id="must-fail",
                )

    def test_disjoint_sparse_coverage_uses_zero_missing_ordinals(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        first = TileKey(1, 0, 0)
        last = TileKey(1, 1, 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(
                primary,
                "primary",
                {first: encode_tile_payload(first, [RendererTileRecord(_line_renderer_record(first), None)])},
            )
            _write_package(
                supplement,
                "supplement",
                {last: encode_tile_payload(last, [RendererTileRecord(_line_renderer_record(last), None)])},
            )

            merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=output,
                package_id="sparse-union",
            )

            manifest = json.loads((output / "manifest.json").read_text("utf-8"))
            self.assertEqual(
                manifest["coverage"],
                {
                    "completeDeclaredScope": False,
                    "completeWholeEarthDictionary": False,
                    "tileCount": 4,
                    "zoomRanges": [
                        {"z": 1, "xMin": 0, "xMax": 1, "yMin": 0, "yMax": 1, "tileCount": 4}
                    ],
                },
            )
            index = (output / "tile-index.bin").read_bytes()
            self.assertEqual(len(index), 4 * INDEX_ENTRY_BYTES)
            self.assertEqual(index[INDEX_ENTRY_BYTES : 3 * INDEX_ENTRY_BYTES], b"\0" * (2 * INDEX_ENTRY_BYTES))
            self.assertIsNotNone(_read_payload(output, first))
            self.assertIsNotNone(_read_payload(output, last))

    def test_primary_whole_world_claim_is_preserved(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(0, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(
                primary,
                "whole-primary",
                {tile: payload},
                complete_whole_earth_dictionary=True,
            )
            _write_package(supplement, "same-scope-supplement", {tile: payload})

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=output,
                package_id="whole-merged",
            )

            self.assertTrue(result.manifest["coverage"]["completeWholeEarthDictionary"])
            self.assertTrue(result.receipt["coverage"]["primaryWholeEarthPreserved"])

    def test_corrupt_deflate_and_wrong_policy_are_rejected(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, corrupt, wrong_policy = root / "primary", root / "corrupt", root / "wrong-policy"
            _write_package(primary, "primary", {tile: payload})
            _write_package(corrupt, "corrupt", {tile: payload})
            records = bytearray((corrupt / "records.fadictpack").read_bytes())
            records[len(records) // 2] ^= 0x80
            (corrupt / "records.fadictpack").write_bytes(records)
            with self.assertRaisesRegex(V3PackageMergeError, "DEFLATE|integrity|canonical"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(corrupt,),
                    output_directory=root / "corrupt-out",
                    package_id="corrupt-out",
                )

            _write_package(wrong_policy, "wrong-policy", {tile: payload})
            manifest = json.loads((wrong_policy / "manifest.json").read_text("utf-8"))
            manifest["presentationPolicySha256"] = "0" * 64
            (wrong_policy / "manifest.json").write_text(
                json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(V3PackageMergeError, "presentation policy"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(wrong_policy,),
                    output_directory=root / "policy-out",
                    package_id="policy-out",
                )

    def test_merged_tile_limit_is_enforced(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        cairo = encode_tile_payload(
            tile,
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )
        line = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = root / "primary", root / "supplement"
            _write_package(primary, "primary", {tile: cairo})
            _write_package(supplement, "supplement", {tile: line})
            with mock.patch.object(v3_package_merger, "MAX_RECORDS_PER_TILE", 1):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError, "record count"
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(supplement,),
                        output_directory=root / "out",
                        package_id="over-limit",
                    )

    def test_output_and_receipt_are_deterministic_and_hash_bound(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        primary_payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )
        supplement_payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = root / "primary", root / "supplement"
            first, second = root / "first", root / "second"
            _write_package(primary, "primary", {tile: primary_payload})
            _write_package(supplement, "supplement", {tile: supplement_payload})

            merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=first,
                package_id="deterministic",
            )
            merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=second,
                package_id="deterministic",
            )

            for name in ("manifest.json", "records.fadictpack", "tile-index.bin", "merge-receipt.json"):
                self.assertEqual((first / name).read_bytes(), (second / name).read_bytes())
            receipt = json.loads((first / "merge-receipt.json").read_text("utf-8"))
            output_files = {item["name"]: item for item in receipt["outputFiles"]}
            for name in ("manifest.json", "records.fadictpack", "tile-index.bin"):
                self.assertEqual(output_files[name]["sha256"], _sha256(first / name))
                self.assertEqual(output_files[name]["bytes"], (first / name).stat().st_size)
            self.assertNotIn("rendererContractSha256", json.dumps(receipt))
            self.assertNotIn("classCatalog", receipt)
            self.assertRegex(receipt["rendererSemanticStreamSha256"], r"^[0-9a-f]{64}$")

    def test_records_and_index_are_never_materialized_as_whole_files(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        first = TileKey(5, 0, 0)
        last = TileKey(5, 31, 31)
        payloads = {
            first: encode_tile_payload(first, [RendererTileRecord(_line_renderer_record(first), None)]),
            last: encode_tile_payload(last, [RendererTileRecord(_line_renderer_record(last), None)]),
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            output = root / "output"
            _write_package(
                primary,
                "wide-sparse-primary",
                payloads,
                complete_declared_scope=False,
            )
            original = Path.read_bytes

            def guarded_read_bytes(path: Path) -> bytes:
                if path.name in {"records.fadictpack", "tile-index.bin"}:
                    raise AssertionError("runtime file was materialized")
                return original(path)

            with mock.patch.object(Path, "read_bytes", guarded_read_bytes):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(),
                    output_directory=output,
                    package_id="bounded-stream",
                )
            self.assertEqual((output / "tile-index.bin").stat().st_size, 1024 * INDEX_ENTRY_BYTES)


if __name__ == "__main__":
    unittest.main()
