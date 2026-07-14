from __future__ import annotations

import hashlib
import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

from tools.experiment8.model import TileKey
from tools.experiment8.reference_presentation_policy import (
    PRESENTATION_POLICY_SHA256,
    ReferenceClassCatalog,
    SemanticSubtype,
    SubtypeCatalogCounts,
    canonical_class_catalog_bytes,
)
from tools.experiment8.renderer_tile_package import (
    RendererTileRecord,
    build_package,
    encode_index_entry,
    encode_tile_payload,
    raw_deflate,
    raw_hash32,
)
from tools.experiment8.semantic_model import renderer_contract_hash
from tools.experiment8.tests.test_renderer_tile_package import _line_renderer_record


def _canonical_json_bytes(document: object) -> bytes:
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


def _write_v3_package(
    directory: Path,
    payloads: dict[TileKey, bytes],
    records: tuple[object, ...],
    *,
    complete_declared_scope: bool = True,
) -> dict[str, object]:
    artifacts = build_package(
        "catalog-finalizer-fixture",
        payloads,
        complete_declared_scope=complete_declared_scope,
    )
    manifest = json.loads(artifacts.manifest_bytes.decode("utf-8"))
    manifest["rendererSemanticStreamSha256"] = renderer_contract_hash(records)
    manifest["retainedEvidence"] = {
        "ordered": [3, 2, 1],
        "text": "byte-semantically preserved",
    }
    directory.mkdir()
    (directory / "manifest.json").write_bytes(_canonical_json_bytes(manifest))
    (directory / "records.fadictpack").write_bytes(artifacts.records_bytes)
    (directory / "tile-index.bin").write_bytes(artifacts.index_bytes)
    return manifest


def _write_authority_v2_merged_package(
    root: Path,
    *,
    complete_whole_earth_dictionary: bool = True,
) -> Path:
    from tools.experiment8.tests.test_v3_package_merger import (
        _write_package,
        _write_recovered_water_build_receipt,
    )
    from tools.experiment8.v3_package_merger import merge_v3_packages

    tile = TileKey(0, 0, 0)
    record = _line_renderer_record(tile)
    payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
    primary = root / "primary"
    water = root / "water"
    output = root / "world-experiment8-binary-v4"
    _write_package(
        primary,
        "primary",
        {tile: payload},
        complete_whole_earth_dictionary=complete_whole_earth_dictionary,
    )
    _write_package(water, "water", {tile: payload})
    water_receipt = _write_recovered_water_build_receipt(water)
    merge_v3_packages(
        primary_directory=primary,
        supplement_directories=(water,),
        supplement_build_receipts=(water_receipt,),
        output_directory=output,
        package_id="world-experiment8-binary-v4",
    )
    return output


class V3ClassCatalogFinalizerTests(unittest.TestCase):
    def test_authority_v2_receipt_and_final_six_file_accounting_are_preserved(
        self,
    ) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            merge_receipt_path = package / "merge-receipt.json"
            original_merge_receipt_raw = merge_receipt_path.read_bytes()
            original_merge_receipt = json.loads(
                original_merge_receipt_raw.decode("utf-8")
            )
            original_manifest = json.loads(
                (package / "manifest.json").read_text("utf-8")
            )

            result = finalize_v3_class_catalog(package)

            self.assertEqual(
                original_merge_receipt_raw,
                merge_receipt_path.read_bytes(),
            )
            final_manifest = json.loads(
                (package / "manifest.json").read_text("utf-8")
            )
            self.assertEqual(
                original_manifest["merge"]["authorityReceipts"],
                final_manifest["merge"]["authorityReceipts"],
            )
            receipt = result.receipt
            self.assertEqual(
                "flightalert.experiment8."
                "v3-class-catalog-finalization-receipt.v2",
                receipt["schema"],
            )
            self.assertEqual(
                original_merge_receipt["authorityReceipts"],
                receipt["authorityReceipts"],
            )
            self.assertEqual(
                {
                    "bytes": len(original_merge_receipt_raw),
                    "name": "merge-receipt.json",
                    "sha256": hashlib.sha256(
                        original_merge_receipt_raw
                    ).hexdigest(),
                },
                receipt["mergeReceipt"],
            )
            size_policy = receipt["sizePolicy"]
            self.assertEqual(
                "final-six-file-package-after-class-catalog-finalization",
                size_policy["accountingScope"],
            )
            self.assertEqual(
                original_merge_receipt["sizePolicy"]["binding"],
                size_policy["binding"],
            )
            expected_package_bytes = sum(
                path.stat().st_size for path in package.iterdir()
            )
            self.assertEqual(6, len(tuple(package.iterdir())))
            self.assertEqual(
                expected_package_bytes,
                size_policy["decision"]["requiredPackageBytes"],
            )
            self.assertTrue(size_policy["decision"]["authorized"])
            self.assertEqual(
                _canonical_json_bytes(receipt),
                (package / RECEIPT_FILE_NAME).read_bytes(),
            )

    def test_authority_v2_merge_receipt_tamper_fails_before_publication(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            merge_receipt_path = package / "merge-receipt.json"
            merge_receipt = json.loads(merge_receipt_path.read_text("utf-8"))
            merge_receipt["authorityReceipts"][0]["sha256"] = "0" * 64
            merge_receipt_path.write_bytes(_canonical_json_bytes(merge_receipt))
            before = {
                path.name: path.read_bytes()
                for path in package.iterdir()
                if path.is_file()
            }

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "authority receipts differ",
            ):
                finalize_v3_class_catalog(package)

            self.assertEqual(
                before,
                {
                    path.name: path.read_bytes()
                    for path in package.iterdir()
                    if path.is_file()
                },
            )
            self.assertFalse((package / RECEIPT_FILE_NAME).exists())
            self.assertFalse((package / "class-catalog.bin").exists())

    def test_authority_v2_rejects_unrelated_self_hashed_authority(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            manifest_path = package / "manifest.json"
            receipt_path = package / "merge-receipt.json"
            original_manifest_bytes = manifest_path.read_bytes()
            original_receipt_bytes = receipt_path.read_bytes()
            manifest = json.loads(original_manifest_bytes)
            receipt = json.loads(original_receipt_bytes)
            for owner in (manifest["merge"], receipt):
                authority = owner["authorityReceipts"][0]
                authority["packageId"] = "other"
                authority["document"]["packageId"] = "other"
                document_bytes = _canonical_json_bytes(authority["document"])
                authority["bytes"] = len(document_bytes)
                authority["sha256"] = hashlib.sha256(document_bytes).hexdigest()
            forged_manifest_bytes = _canonical_json_bytes(manifest)
            self.assertEqual(len(original_manifest_bytes), len(forged_manifest_bytes))
            manifest_path.write_bytes(forged_manifest_bytes)
            receipt["outputFiles"][0].update(
                {
                    "bytes": len(forged_manifest_bytes),
                    "sha256": hashlib.sha256(forged_manifest_bytes).hexdigest(),
                }
            )
            forged_receipt_bytes = _canonical_json_bytes(receipt)
            self.assertEqual(len(original_receipt_bytes), len(forged_receipt_bytes))
            receipt_path.write_bytes(forged_receipt_bytes)

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "authority receipt.*supplement input",
            ):
                finalize_v3_class_catalog(package)

            self.assertFalse((package / "class-catalog.bin").exists())
            self.assertFalse(
                (package / "class-catalog-finalization-receipt.json").exists()
            )

    def test_authority_v2_receipt_outputs_must_match_supplement_input(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            manifest_path = package / "manifest.json"
            receipt_path = package / "merge-receipt.json"
            original_manifest_bytes = manifest_path.read_bytes()
            original_receipt_bytes = receipt_path.read_bytes()
            manifest = json.loads(original_manifest_bytes)
            receipt = json.loads(original_receipt_bytes)
            for owner in (manifest["merge"], receipt):
                authority = owner["authorityReceipts"][0]
                records = next(
                    item
                    for item in authority["document"]["outputFiles"]
                    if item["name"] == "records.fadictpack"
                )
                records["sha256"] = "f" * 64
                document_bytes = _canonical_json_bytes(authority["document"])
                authority["bytes"] = len(document_bytes)
                authority["sha256"] = hashlib.sha256(document_bytes).hexdigest()
            forged_manifest_bytes = _canonical_json_bytes(manifest)
            self.assertEqual(len(original_manifest_bytes), len(forged_manifest_bytes))
            manifest_path.write_bytes(forged_manifest_bytes)
            receipt["outputFiles"][0].update(
                {
                    "bytes": len(forged_manifest_bytes),
                    "sha256": hashlib.sha256(forged_manifest_bytes).hexdigest(),
                }
            )
            forged_receipt_bytes = _canonical_json_bytes(receipt)
            self.assertEqual(len(original_receipt_bytes), len(forged_receipt_bytes))
            receipt_path.write_bytes(forged_receipt_bytes)

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "authority receipt output files differ from supplement input",
            ):
                finalize_v3_class_catalog(package)

            self.assertFalse((package / "class-catalog.bin").exists())

    def test_authority_v2_rejects_unexpected_package_entry_before_publication(
        self,
    ) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            (package / "extra.bin").write_bytes(b"not part of the package")

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "exact package inventory",
            ):
                finalize_v3_class_catalog(package)

            self.assertFalse((package / "class-catalog.bin").exists())
            self.assertFalse(
                (package / "class-catalog-finalization-receipt.json").exists()
            )

    def test_authority_v2_uses_fresh_stable_publication_capacity(self) -> None:
        from tools.experiment8.reference_size_policy import DESTINATION_RESERVE_BYTES
        from tools.experiment8.v3_class_catalog_finalizer import (
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            insufficient = mock.Mock(free=DESTINATION_RESERVE_BYTES - 1)

            with mock.patch("shutil.disk_usage", return_value=insufficient), self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "final six-file package lacks authenticated size capacity",
            ):
                finalize_v3_class_catalog(package)

            receipt_path = package / "class-catalog-finalization-receipt.json"
            self.assertTrue(
                not receipt_path.exists() or receipt_path.read_bytes() == b""
            )

    def test_authority_v2_binds_two_equal_post_stage_capacity_reads(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))
            stable_free = 90_000_000_000
            capacity = mock.Mock(free=stable_free)

            with mock.patch(
                "shutil.disk_usage", return_value=capacity
            ) as disk_usage:
                result = finalize_v3_class_catalog(package)

            self.assertGreaterEqual(disk_usage.call_count, 3)
            self.assertEqual(
                stable_free,
                result.receipt["sizePolicy"]["decision"][
                    "publicationBoundaryDestinationFreeBytes"
                ],
            )

    def test_authority_v2_concurrent_extra_never_gets_a_valid_receipt(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            package = _write_authority_v2_merged_package(Path(temporary))

            def add_extra_before_receipt(event: str) -> None:
                if event == "before_receipt_replace":
                    (package / "extra.bin").write_bytes(b"raced")

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "exact package inventory",
            ):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=add_extra_before_receipt,
                )

            receipt_path = package / RECEIPT_FILE_NAME
            self.assertTrue(
                not receipt_path.exists() or receipt_path.read_bytes() == b""
            )

    def test_v1_finalization_receipt_retains_its_exact_legacy_field_set(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))

            receipt = finalize_v3_class_catalog(package).receipt

            self.assertEqual(
                "flightalert.experiment8."
                "v3-class-catalog-finalization-receipt.v1",
                receipt["schema"],
            )
            self.assertEqual(
                {
                    "coverage",
                    "finalizerSha256",
                    "inputFiles",
                    "outputFiles",
                    "packageId",
                    "rendererContractSha256",
                    "rendererSemanticStreamSha256",
                    "schema",
                    "subtypeCounts",
                },
                set(receipt),
            )

    def test_finalizes_exact_catalog_counts_and_preserves_runtime_bytes(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            original_manifest = _write_v3_package(
                package,
                {tile: payload},
                (record,),
            )
            original_records = (package / "records.fadictpack").read_bytes()
            original_index = (package / "tile-index.bin").read_bytes()

            result = finalize_v3_class_catalog(package)

            catalog_bytes = (package / "class-catalog.bin").read_bytes()
            final_manifest_bytes = (package / "manifest.json").read_bytes()
            final_manifest = json.loads(final_manifest_bytes.decode("utf-8"))
            self.assertEqual(754, len(catalog_bytes))
            self.assertEqual(
                hashlib.sha256(catalog_bytes).hexdigest(),
                result.catalog_sha256,
            )
            self.assertEqual(
                hashlib.sha256(final_manifest_bytes).hexdigest(),
                result.manifest_sha256,
            )
            self.assertEqual(
                {
                    "catalogSha256": result.catalog_sha256,
                    "rendererContractSha256": original_manifest[
                        "rendererSemanticStreamSha256"
                    ],
                },
                final_manifest.pop("classCatalog"),
            )
            self.assertEqual(original_manifest, final_manifest)
            self.assertEqual(
                original_records,
                (package / "records.fadictpack").read_bytes(),
            )
            self.assertEqual(
                original_index,
                (package / "tile-index.bin").read_bytes(),
            )
            self.assertEqual(
                _canonical_json_bytes(result.receipt),
                (package / RECEIPT_FILE_NAME).read_bytes(),
            )

            catalog = ReferenceClassCatalog.from_verified_bytes(
                catalog_bytes,
                expected_catalog_sha256=result.catalog_sha256,
                expected_renderer_semantic_stream_sha256=original_manifest[
                    "rendererSemanticStreamSha256"
                ],
                expected_renderer_contract_sha256=original_manifest[
                    "rendererSemanticStreamSha256"
                ],
                expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
            )
            counts = dict(catalog.subtype_counts)
            self.assertEqual(1, counts[SemanticSubtype.WATERSHED_WATER_BOUNDARY].posting_count)
            self.assertEqual(
                1,
                counts[SemanticSubtype.WATERSHED_WATER_BOUNDARY].distinct_feature_count,
            )
            self.assertEqual(
                1,
                counts[SemanticSubtype.WATERSHED_WATER_BOUNDARY].canonical_variant_count,
            )
            self.assertTrue(
                all(
                    counts[subtype].posting_count == 0
                    for subtype in SemanticSubtype
                    if subtype is not SemanticSubtype.WATERSHED_WATER_BOUNDARY
                )
            )

    def test_exact_bound_input_rejects_oversized_output_before_staging(self) -> None:
        from tools.experiment8 import v3_class_catalog_finalizer as finalizer

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            manifest = _write_v3_package(package, {tile: payload}, (record,))
            manifest["exactBoundPadding"] = ""
            unpadded = _canonical_json_bytes(manifest)
            padding_bytes = finalizer._MAX_MANIFEST_BYTES - len(unpadded)
            self.assertGreater(padding_bytes, 0)
            manifest["exactBoundPadding"] = "X" * padding_bytes
            exact_bound_manifest = _canonical_json_bytes(manifest)
            self.assertEqual(finalizer._MAX_MANIFEST_BYTES, len(exact_bound_manifest))
            (package / "manifest.json").write_bytes(exact_bound_manifest)
            before = {
                path.name: path.read_bytes()
                for path in package.iterdir()
            }

            with mock.patch.object(
                finalizer,
                "_stage_bytes",
                wraps=finalizer._stage_bytes,
            ) as stage_bytes:
                with self.assertRaisesRegex(
                    finalizer.V3ClassCatalogFinalizationError,
                    "finalized V3 manifest byte length is outside its bound",
                ):
                    finalizer.finalize_v3_class_catalog(package)

            stage_bytes.assert_not_called()
            self.assertEqual(
                before,
                {
                    path.name: path.read_bytes()
                    for path in package.iterdir()
                },
            )

    def test_repeat_is_byte_identical_and_zero_entry_sparse_coverage_is_counted(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            zero_package = root / "zero-entry"
            zero_tile = TileKey(0, 0, 0)
            zero_payload = encode_tile_payload(zero_tile, [])
            _write_v3_package(
                zero_package,
                {zero_tile: zero_payload},
                (),
                complete_declared_scope=False,
            )
            (zero_package / "tile-index.bin").write_bytes(b"\0" * 24)
            (zero_package / "records.fadictpack").write_bytes(b"")

            zero_result = finalize_v3_class_catalog(zero_package)

            self.assertEqual(
                {
                    "declaredTileCount": 1,
                    "missingTileCount": 1,
                    "presentTileCount": 0,
                    "rendererRecordCount": 0,
                },
                zero_result.receipt["coverage"],
            )
            self.assertEqual(
                hashlib.sha256(b"flight-alert-exp8-semantic-v1\0").hexdigest(),
                zero_result.receipt["rendererSemanticStreamSha256"],
            )
            self.assertTrue(
                all(item["postings"] == 0 for item in zero_result.receipt["subtypeCounts"])
            )

            sparse_package = root / "sparse"
            first = TileKey(2, 0, 0)
            second = TileKey(2, 1, 1)
            first_record = _line_renderer_record(first)
            second_record = _line_renderer_record(second)
            _write_v3_package(
                sparse_package,
                {
                    first: encode_tile_payload(
                        first, [RendererTileRecord(first_record, None)]
                    ),
                    second: encode_tile_payload(
                        second, [RendererTileRecord(second_record, None)]
                    ),
                },
                (first_record, second_record),
                complete_declared_scope=False,
            )

            first_result = finalize_v3_class_catalog(sparse_package)
            first_files = {
                name: (sparse_package / name).read_bytes()
                for name in (
                    "manifest.json",
                    "records.fadictpack",
                    "tile-index.bin",
                    "class-catalog.bin",
                    RECEIPT_FILE_NAME,
                )
            }
            second_result = finalize_v3_class_catalog(sparse_package)

            self.assertEqual(first_result.receipt, second_result.receipt)
            self.assertEqual(
                first_files,
                {name: (sparse_package / name).read_bytes() for name in first_files},
            )
            self.assertEqual(
                {
                    "declaredTileCount": 4,
                    "missingTileCount": 2,
                    "presentTileCount": 2,
                    "rendererRecordCount": 2,
                },
                first_result.receipt["coverage"],
            )
            watershed = next(
                item
                for item in first_result.receipt["subtypeCounts"]
                if item["semanticSubtype"]
                == SemanticSubtype.WATERSHED_WATER_BOUNDARY.value
            )
            self.assertEqual(1, watershed["distinctFeatureIds"])
            self.assertEqual(1, watershed["canonicalVariantIds"])
            self.assertEqual(2, watershed["postings"])

    def test_catalog_publication_precedes_manifest_and_crash_stays_unavailable(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            original_manifest = _write_v3_package(
                package,
                {tile: payload},
                (record,),
            )
            original_manifest_bytes = (package / "manifest.json").read_bytes()
            events: list[str] = []

            def crash_after_catalog(event: str) -> None:
                events.append(event)
                if event == "after_catalog_published":
                    raise RuntimeError("simulated process death boundary")

            with self.assertRaisesRegex(RuntimeError, "simulated process death"):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=crash_after_catalog,
                )

            self.assertEqual(
                ["before_catalog_replace", "after_catalog_published"],
                events,
            )
            self.assertEqual(
                original_manifest_bytes,
                (package / "manifest.json").read_bytes(),
            )
            self.assertNotIn(
                "classCatalog",
                json.loads((package / "manifest.json").read_text("utf-8")),
            )
            self.assertTrue((package / "class-catalog.bin").is_file())
            self.assertFalse((package / RECEIPT_FILE_NAME).exists())

            completed_events: list[str] = []
            result = finalize_v3_class_catalog(
                package,
                publication_hook=completed_events.append,
            )
            self.assertEqual(
                [
                    "before_catalog_replace",
                    "after_catalog_published",
                    "before_manifest_replace",
                    "after_manifest_published",
                    "before_receipt_replace",
                    "after_receipt_published",
                ],
                completed_events,
            )
            manifest = json.loads((package / "manifest.json").read_text("utf-8"))
            self.assertEqual(
                original_manifest["rendererSemanticStreamSha256"],
                manifest["classCatalog"]["rendererContractSha256"],
            )
            self.assertEqual(
                result.catalog_sha256,
                manifest["classCatalog"]["catalogSha256"],
            )

    def test_hook_time_input_mutation_cannot_publish_manifest_or_receipt(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            original_manifest = (package / "manifest.json").read_bytes()

            def mutate_after_preflight(event: str) -> None:
                if event != "before_catalog_replace":
                    return
                records_path = package / "records.fadictpack"
                records = bytearray(records_path.read_bytes())
                records[len(records) // 2] ^= 0x80
                records_path.write_bytes(records)

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "records.fadictpack changed",
            ):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=mutate_after_preflight,
                )

            self.assertEqual(
                original_manifest,
                (package / "manifest.json").read_bytes(),
            )
            self.assertNotIn(
                "classCatalog",
                json.loads((package / "manifest.json").read_text("utf-8")),
            )
            self.assertFalse((package / RECEIPT_FILE_NAME).exists())

    def test_hook_time_catalog_mutation_cannot_publish_manifest_or_receipt(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            original_manifest = (package / "manifest.json").read_bytes()

            def mutate_catalog_before_manifest(event: str) -> None:
                if event != "before_manifest_replace":
                    return
                catalog_path = package / "class-catalog.bin"
                catalog = bytearray(catalog_path.read_bytes())
                catalog[-1] ^= 1
                catalog_path.write_bytes(catalog)

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "class-catalog.bin readback differs",
            ):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=mutate_catalog_before_manifest,
                )

            self.assertEqual(
                original_manifest,
                (package / "manifest.json").read_bytes(),
            )
            self.assertFalse((package / RECEIPT_FILE_NAME).exists())

    def test_crash_after_manifest_cannot_leave_a_stale_existing_receipt(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            finalize_v3_class_catalog(package)
            receipt_path = package / RECEIPT_FILE_NAME
            stale_receipt = json.loads(receipt_path.read_text("utf-8"))
            stale_manifest_hash = next(
                item["sha256"]
                for item in stale_receipt["outputFiles"]
                if item["name"] == "manifest.json"
            )
            revised = json.loads((package / "manifest.json").read_text("utf-8"))
            revised.pop("classCatalog")
            revised["retainedEvidence"]["text"] = "revised before refinalization"
            (package / "manifest.json").write_bytes(_canonical_json_bytes(revised))

            def crash_after_manifest(event: str) -> None:
                if event == "after_manifest_published":
                    raise RuntimeError("simulated crash after manifest publication")

            with self.assertRaisesRegex(RuntimeError, "after manifest"):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=crash_after_manifest,
                )

            published_manifest = (package / "manifest.json").read_bytes()
            self.assertNotEqual(
                stale_manifest_hash,
                hashlib.sha256(published_manifest).hexdigest(),
            )
            self.assertEqual(
                "revised before refinalization",
                json.loads(published_manifest)["retainedEvidence"]["text"],
            )
            self.assertIn("classCatalog", json.loads(published_manifest))
            self.assertTrue(
                not receipt_path.exists() or receipt_path.read_bytes() == b""
            )

    def test_real_process_death_recovers_without_receipt_siblings(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            finalize_v3_class_catalog(package)
            child_program = "\n".join(
                (
                    "import os, sys",
                    "from pathlib import Path",
                    "from tools.experiment8.v3_class_catalog_finalizer import finalize_v3_class_catalog",
                    "def hook(event):",
                    "    if event == 'after_manifest_published':",
                    "        os._exit(77)",
                    "finalize_v3_class_catalog(Path(sys.argv[1]), publication_hook=hook)",
                )
            )

            child = subprocess.run(
                [sys.executable, "-c", child_program, str(package)],
                cwd=Path.cwd(),
                check=False,
            )

            self.assertEqual(77, child.returncode)
            finalize_v3_class_catalog(package)
            manifest_bytes = (package / "manifest.json").read_bytes()
            manifest = json.loads(manifest_bytes)
            receipt_bytes = (package / RECEIPT_FILE_NAME).read_bytes()
            receipt = json.loads(receipt_bytes)
            self.assertEqual(_canonical_json_bytes(receipt), receipt_bytes)
            self.assertEqual(
                hashlib.sha256(manifest_bytes).hexdigest(),
                next(
                    item["sha256"]
                    for item in receipt["outputFiles"]
                    if item["name"] == "manifest.json"
                ),
            )
            self.assertEqual(
                manifest["classCatalog"]["catalogSha256"],
                hashlib.sha256((package / "class-catalog.bin").read_bytes()).hexdigest(),
            )
            receipt_artifacts = sorted(
                path.name
                for path in package.iterdir()
                if path.name.startswith(f".{RECEIPT_FILE_NAME}.")
                and (
                    path.name.endswith(".tmp")
                    or path.name.endswith(".invalidated")
                )
            )
            self.assertEqual([], receipt_artifacts)

    def test_detected_failure_after_receipt_publication_invalidates_receipt(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))

            def corrupt_after_receipt(event: str) -> None:
                if event != "after_receipt_published":
                    return
                catalog_path = package / "class-catalog.bin"
                catalog = bytearray(catalog_path.read_bytes())
                catalog[-1] ^= 1
                catalog_path.write_bytes(catalog)

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "class-catalog.bin readback differs",
            ):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=corrupt_after_receipt,
                )

            receipt_path = package / RECEIPT_FILE_NAME
            self.assertTrue(
                not receipt_path.exists() or receipt_path.read_bytes() == b""
            )

    def test_receipt_replaced_after_publication_cannot_return_success(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            replacement = b"X" * 31

            def replace_receipt(event: str) -> None:
                if event == "after_receipt_published":
                    (package / RECEIPT_FILE_NAME).write_bytes(replacement)

            with self.assertRaisesRegex(
                V3ClassCatalogFinalizationError,
                "receipt.*readback differs",
            ):
                finalize_v3_class_catalog(
                    package,
                    publication_hook=replace_receipt,
                )

            receipt_path = package / RECEIPT_FILE_NAME
            self.assertTrue(
                not receipt_path.exists() or receipt_path.read_bytes() == b""
            )

    def test_terminal_receipt_readback_bounds_raced_growth_before_read(self) -> None:
        from tools.experiment8 import v3_class_catalog_finalizer as finalizer

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            receipt_path = package / finalizer.RECEIPT_FILE_NAME
            original_open = Path.open
            original_lstat = finalizer.os.lstat
            probe_active = False
            fake_size_used = False
            expected_length = 0
            read_sizes: list[int] = []

            class BoundedReadProbe:
                def __init__(self, handle) -> None:
                    self._handle = handle

                def __enter__(self):
                    self._handle.__enter__()
                    return self

                def __exit__(self, *arguments):
                    return self._handle.__exit__(*arguments)

                def read(self, size: int = -1) -> bytes:
                    read_sizes.append(size)
                    if size < 0 or size > expected_length + 1:
                        raise AssertionError(
                            f"terminal receipt read was not bounded: {size}"
                        )
                    return self._handle.read(size)

                def __getattr__(self, name: str):
                    return getattr(self._handle, name)

            def probed_open(path: Path, *arguments, **keywords):
                handle = original_open(path, *arguments, **keywords)
                mode = arguments[0] if arguments else keywords.get("mode", "r")
                if probe_active and path == receipt_path and mode == "rb":
                    return BoundedReadProbe(handle)
                return handle

            def probed_lstat(path, *arguments, **keywords):
                nonlocal fake_size_used
                information = original_lstat(path, *arguments, **keywords)
                if (
                    probe_active
                    and Path(path) == receipt_path
                    and not fake_size_used
                ):
                    fake_size_used = True
                    class ExpectedSizeStat:
                        st_size = expected_length

                        def __getattr__(self, name: str):
                            return getattr(information, name)

                    return ExpectedSizeStat()
                return information

            def replace_receipt(event: str) -> None:
                nonlocal probe_active, expected_length
                if event != "after_receipt_published":
                    return
                expected_length = len(receipt_path.read_bytes())
                receipt_path.write_bytes(b"X" * (2 * 1024 * 1024))
                probe_active = True

            with mock.patch.object(Path, "open", new=probed_open), mock.patch.object(
                finalizer.os,
                "lstat",
                side_effect=probed_lstat,
            ):
                with self.assertRaisesRegex(
                    finalizer.V3ClassCatalogFinalizationError,
                    "receipt.*readback differs",
                ):
                    finalizer.finalize_v3_class_catalog(
                        package,
                        publication_hook=replace_receipt,
                    )

            self.assertTrue(fake_size_used)
            self.assertGreater(expected_length, 0)
            self.assertTrue(read_sizes)
            self.assertEqual(expected_length + 1, read_sizes[0])
            self.assertTrue(
                all(0 <= size <= expected_length + 1 for size in read_sizes)
            )
            self.assertTrue(
                not receipt_path.exists() or receipt_path.read_bytes() == b""
            )

    def test_cleanup_preserves_replacements_for_every_staged_file_type(self) -> None:
        from tools.experiment8 import v3_class_catalog_finalizer as finalizer

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        targets = (
            "class-catalog.bin",
            "manifest.json",
            finalizer.RECEIPT_FILE_NAME,
        )
        for target in targets:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as temporary:
                package = Path(temporary) / "package"
                _write_v3_package(package, {tile: payload}, (record,))
                original_replace = finalizer._replace_staged
                attacker_bytes = f"attacker-owned:{target}".encode("ascii")
                attacker_paths: list[Path] = []
                swapped = False

                def swap_stage(staged, destination: Path) -> None:
                    nonlocal swapped
                    if destination.name != target or swapped:
                        original_replace(staged, destination)
                        return
                    stage_path = getattr(staged, "path", staged)
                    owned_copy = stage_path.with_name(stage_path.name + ".owned")
                    os.replace(stage_path, owned_copy)
                    stage_path.write_bytes(attacker_bytes)
                    attacker_paths.append(stage_path)
                    swapped = True
                    raise RuntimeError("simulated staged-path identity swap")

                with (
                    mock.patch.object(
                        finalizer,
                        "_replace_staged",
                        side_effect=swap_stage,
                    ),
                    self.assertRaisesRegex(
                        finalizer.V3ClassCatalogFinalizationError,
                        "identity changed before cleanup",
                    ),
                ):
                    finalizer.finalize_v3_class_catalog(package)

                self.assertTrue(swapped)
                self.assertEqual(1, len(attacker_paths))
                self.assertEqual(attacker_bytes, attacker_paths[0].read_bytes())

    def test_every_tamper_class_fails_before_publication(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])

        def rewrite_manifest(package: Path, mutate) -> dict[str, object]:
            document = json.loads((package / "manifest.json").read_text("utf-8"))
            mutate(document)
            (package / "manifest.json").write_bytes(_canonical_json_bytes(document))
            return document

        def install_catalog(
            package: Path,
            *,
            counts: dict[SemanticSubtype, SubtypeCatalogCounts],
            corrupt: bool = False,
        ) -> None:
            manifest = json.loads((package / "manifest.json").read_text("utf-8"))
            semantic = manifest["rendererSemanticStreamSha256"]
            catalog = canonical_class_catalog_bytes(
                renderer_semantic_stream_sha256=semantic,
                renderer_contract_sha256=semantic,
                presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
                subtype_counts=counts,
            )
            if corrupt:
                catalog = bytes([catalog[0] ^ 1]) + catalog[1:]
            (package / "class-catalog.bin").write_bytes(catalog)
            rewrite_manifest(
                package,
                lambda document: document.__setitem__(
                    "classCatalog",
                    {
                        "catalogSha256": hashlib.sha256(catalog).hexdigest(),
                        "rendererContractSha256": semantic,
                    },
                ),
            )

        cases = (
            "missing",
            "corrupt",
            "non-v3",
            "policy",
            "hash",
            "count",
            "index",
            "deflate",
            "alias",
            "catalog-corrupt",
            "catalog-count",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for case in cases:
                with self.subTest(case=case):
                    package = root / case
                    _write_v3_package(package, {tile: payload}, (record,))
                    if case == "missing":
                        (package / "records.fadictpack").unlink()
                    elif case == "corrupt":
                        malformed = bytearray(payload)
                        renderer_offset = len(b"FAE8TILE1\0") + struct.calcsize("<BIII") + 4
                        malformed[renderer_offset] ^= 1
                        compressed = raw_deflate(bytes(malformed))
                        (package / "records.fadictpack").write_bytes(compressed)
                        (package / "tile-index.bin").write_bytes(
                            encode_index_entry(
                                offset=0,
                                compressed_length=len(compressed),
                                raw_length=len(malformed),
                                raw_hash32=raw_hash32(bytes(malformed)),
                            )
                        )
                    elif case == "non-v3":
                        rewrite_manifest(
                            package,
                            lambda document: document.__setitem__("schemaVersion", 2),
                        )
                    elif case == "policy":
                        rewrite_manifest(
                            package,
                            lambda document: document.__setitem__(
                                "presentationPolicySha256", "0" * 64
                            ),
                        )
                    elif case == "hash":
                        rewrite_manifest(
                            package,
                            lambda document: document.__setitem__(
                                "rendererSemanticStreamSha256", "0" * 64
                            ),
                        )
                    elif case == "count":
                        rewrite_manifest(
                            package,
                            lambda document: document["coverage"].__setitem__(
                                "tileCount", 2
                            ),
                        )
                    elif case == "index":
                        entry = bytearray((package / "tile-index.bin").read_bytes())
                        struct.pack_into("<I", entry, 20, 2)
                        (package / "tile-index.bin").write_bytes(entry)
                    elif case == "deflate":
                        compressed = bytearray(
                            (package / "records.fadictpack").read_bytes()
                        )
                        compressed[len(compressed) // 2] ^= 0x80
                        (package / "records.fadictpack").write_bytes(compressed)
                    elif case == "alias":
                        manifest = json.loads(
                            (package / "manifest.json").read_text("utf-8")
                        )
                        rewrite_manifest(
                            package,
                            lambda document: document.__setitem__(
                                "classCatalog",
                                {
                                    "catalogSha256": "0" * 64,
                                    "rendererContractSha256": "1" * 64,
                                },
                            ),
                        )
                        self.assertNotEqual(
                            manifest["rendererSemanticStreamSha256"], "1" * 64
                        )
                    elif case in {"catalog-corrupt", "catalog-count"}:
                        zero_counts = {
                            subtype: SubtypeCatalogCounts(0, 0, 0)
                            for subtype in SemanticSubtype
                        }
                        install_catalog(
                            package,
                            counts=zero_counts,
                            corrupt=case == "catalog-corrupt",
                        )
                    before = {
                        path.name: path.read_bytes()
                        for path in package.iterdir()
                        if path.is_file()
                    }

                    with self.assertRaises(V3ClassCatalogFinalizationError):
                        finalize_v3_class_catalog(package)

                    after = {
                        path.name: path.read_bytes()
                        for path in package.iterdir()
                        if path.is_file()
                    }
                    self.assertEqual(before, after)
                    self.assertFalse((package / RECEIPT_FILE_NAME).exists())
                    self.assertFalse(
                        any(path.name.endswith(".tmp") for path in package.iterdir())
                    )

    def test_oversized_existing_catalog_is_rejected_before_materializing_read(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            V3ClassCatalogFinalizationError,
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            catalog_path = package / "class-catalog.bin"
            oversized = b"X" * 755
            catalog_path.write_bytes(oversized)
            manifest = json.loads((package / "manifest.json").read_text("utf-8"))
            manifest["classCatalog"] = {
                "catalogSha256": hashlib.sha256(oversized).hexdigest(),
                "rendererContractSha256": manifest[
                    "rendererSemanticStreamSha256"
                ],
            }
            (package / "manifest.json").write_bytes(_canonical_json_bytes(manifest))
            original_read_bytes = Path.read_bytes

            def reject_catalog_materialization(path: Path) -> bytes:
                if path == catalog_path:
                    raise AssertionError("oversized catalog was materialized")
                return original_read_bytes(path)

            with (
                mock.patch.object(
                    Path,
                    "read_bytes",
                    new=reject_catalog_materialization,
                ),
                self.assertRaisesRegex(
                    V3ClassCatalogFinalizationError,
                    "754",
                ),
            ):
                finalize_v3_class_catalog(package)

    def test_android_compressed_bound_fails_before_deflate_materialization(self) -> None:
        from tools.experiment8 import v3_class_catalog_finalizer as finalizer

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            entry = bytearray((package / "tile-index.bin").read_bytes())
            struct.pack_into("<I", entry, 8, (1 << 32) - 1)
            (package / "tile-index.bin").write_bytes(entry)

            with (
                mock.patch.object(
                    finalizer.zlib,
                    "decompressobj",
                    side_effect=AssertionError("DEFLATE was reached"),
                ),
                self.assertRaisesRegex(
                    finalizer.V3ClassCatalogFinalizationError,
                    "compressed.*Android bound",
                ),
            ):
                finalizer.finalize_v3_class_catalog(package)

            self.assertFalse((package / "class-catalog.bin").exists())

    def test_cli_prints_the_exact_persisted_deterministic_receipt(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            RECEIPT_FILE_NAME,
            _main,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            output = io.StringIO()

            with redirect_stdout(output):
                status = _main(["--package", str(package)])

            self.assertEqual(0, status)
            self.assertEqual(
                (package / RECEIPT_FILE_NAME).read_text("utf-8"),
                output.getvalue(),
            )

    @unittest.skipUnless(os.name == "nt", "Windows write-through contract")
    def test_windows_publication_uses_write_through_replace_in_order(self) -> None:
        from tools.experiment8 import v3_class_catalog_finalizer as finalizer

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            published: list[str] = []

            def write_through(source: Path, destination: Path) -> None:
                published.append(destination.name)
                os.replace(source, destination)

            with mock.patch.object(
                finalizer,
                "_windows_replace_write_through",
                side_effect=write_through,
            ):
                finalizer.finalize_v3_class_catalog(package)

            self.assertEqual(
                [
                    "class-catalog.bin",
                    "manifest.json",
                    finalizer.RECEIPT_FILE_NAME,
                ],
                published,
            )

    def test_relative_package_path_keeps_atomic_replacements_same_directory(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            finalize_v3_class_catalog,
        )

        tile = TileKey(1, 0, 0)
        record = _line_renderer_record(tile)
        payload = encode_tile_payload(tile, [RendererTileRecord(record, None)])
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as temporary:
            package = Path(temporary) / "package"
            _write_v3_package(package, {tile: payload}, (record,))
            relative_package = package.relative_to(Path.cwd())

            result = finalize_v3_class_catalog(relative_package)

            self.assertEqual(package.resolve(), result.package_directory)
            self.assertTrue((package / "class-catalog.bin").is_file())


if __name__ == "__main__":
    unittest.main()
