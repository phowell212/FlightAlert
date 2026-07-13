from __future__ import annotations

import hashlib
import http.server
import io
import json
import tempfile
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from functools import partial
from pathlib import Path
from unittest import mock

import tools.experiment8.reference_package_install as installer_module
import tools.experiment8.reference_release_assets as release_module
from tools.experiment8.reference_release_assets import (
    MAX_RELEASE_ASSET_BYTES,
    RELEASE_MANIFEST_NAME,
    _main,
    fetch_release_assets,
    prepare_release_assets,
)


PACKAGE_ID = "world-experiment8-binary-v4"
SOURCE_COMMIT = "b" * 40
MANDATORY_RESERVE_BYTES = 1_500_000_000
TEST_CHUNK_BYTES = 512
OVERSIZED_MANIFEST_MARKER = ".oversized-manifest-test"
GITHUB_RELEASE_DIRECTORY_URL = (
    "https://github.com/phowell212/FlightAlert/releases/download/v8/"
)
GITHUB_MANIFEST_URL = GITHUB_RELEASE_DIRECTORY_URL + RELEASE_MANIFEST_NAME
PUBLIC_TEST_ADDRESS = "93.184.216.34"


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _canonical(document: object) -> bytes:
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


class _ValidReleaseFixture:
    def __init__(self, root: Path) -> None:
        self.package = root / PACKAGE_ID
        self.package.mkdir()
        self.apk = root / "Flight Alert-debug.apk"
        self.apk.write_bytes(b"source-matched-apk")
        self.result = root / "final-package-result.json"

        records = b"\x00records\r\npack\xff-with-binary-crlf" * 40
        tile_index = b"tile-index"
        catalog = b"C" * 754
        renderer_sha256 = "7" * 64
        merger_sha256 = self._tool_sha256("v3_package_merger.py")
        finalizer_sha256 = self._tool_sha256("v3_class_catalog_finalizer.py")
        input_bindings = [
            {
                "manifestBytes": 101,
                "manifestSha256": "4" * 64,
                "packageId": "world-primary-v3",
                "recordsBytes": 202,
                "recordsSha256": "5" * 64,
                "role": "primary",
                "tileIndexBytes": 303,
                "tileIndexSha256": "6" * 64,
            }
        ]
        manifest = {
            "classCatalog": {
                "catalogSha256": _sha256(catalog),
                "rendererContractSha256": renderer_sha256,
            },
            "compatibility": {"emptyPresentTilesSharePayload": False},
            "coverage": {
                "completeDeclaredScope": True,
                "completeWholeEarthDictionary": True,
                "tileCount": 1,
                "zoomRanges": [
                    {
                        "firstOrdinal": 0,
                        "xMax": 0,
                        "xMin": 0,
                        "yMax": 0,
                        "yMin": 0,
                        "z": 0,
                    }
                ],
            },
            "merge": {
                "inputs": input_bindings,
                "mergerSha256": merger_sha256,
                "output": {
                    "recordsBytes": len(records),
                    "recordsSha256": _sha256(records),
                    "tileIndexBytes": len(tile_index),
                    "tileIndexSha256": _sha256(tile_index),
                },
                "schema": "flightalert.experiment8.v3-package-merge.v1",
            },
            "packageId": PACKAGE_ID,
            "payloadSchema": installer_module.RUNTIME_PAYLOAD_SCHEMA,
            "presentationPolicySha256": (
                installer_module.RUNTIME_PRESENTATION_POLICY_SHA256
            ),
            "rendererSemanticStreamSha256": renderer_sha256,
            "schemaVersion": installer_module.RUNTIME_SCHEMA_VERSION,
            "sourcedTextPolicySha256": (
                installer_module.RUNTIME_SOURCED_TEXT_POLICY_SHA256
            ),
            "unicodeScriptProfileSha256": (
                installer_module.RUNTIME_UNICODE_SCRIPT_PROFILE_SHA256
            ),
        }
        manifest_raw = _canonical(manifest)
        base_manifest_raw = b"pre-finalizer-manifest\n"
        runtime_bindings = [
            self._binding("manifest.json", manifest_raw),
            self._binding("records.fadictpack", records),
            self._binding("tile-index.bin", tile_index),
            self._binding("class-catalog.bin", catalog),
        ]
        merge_receipt = {
            "coverage": {
                "completeDeclaredScope": True,
                "completeWholeEarthDictionary": True,
                "presentTileCount": 1,
                "primaryWholeEarthPreserved": True,
                "tileCount": 1,
                "zoomRanges": manifest["coverage"]["zoomRanges"],
            },
            "inputs": input_bindings,
            "mergerSha256": merger_sha256,
            "outputFiles": [
                self._binding("manifest.json", base_manifest_raw),
                self._binding("records.fadictpack", records),
                self._binding("tile-index.bin", tile_index),
            ],
            "packageId": PACKAGE_ID,
            "rendererSemanticStreamSha256": renderer_sha256,
            "schema": "flightalert.experiment8.v3-package-merge-receipt.v1",
            "subtypeCounts": {},
        }
        finalization_receipt = {
            "coverage": {
                "declaredTileCount": 1,
                "missingTileCount": 0,
                "presentTileCount": 1,
                "rendererRecordCount": 0,
            },
            "finalizerSha256": finalizer_sha256,
            "inputFiles": [
                {
                    **self._binding("manifest.json", base_manifest_raw),
                    "role": "base-manifest-without-class-catalog",
                },
                self._binding("records.fadictpack", records),
                self._binding("tile-index.bin", tile_index),
            ],
            "outputFiles": runtime_bindings,
            "packageId": PACKAGE_ID,
            "rendererContractSha256": renderer_sha256,
            "rendererSemanticStreamSha256": renderer_sha256,
            "schema": (
                "flightalert.experiment8."
                "v3-class-catalog-finalization-receipt.v1"
            ),
            "subtypeCounts": {},
        }
        files = {
            "manifest.json": manifest_raw,
            "records.fadictpack": records,
            "tile-index.bin": tile_index,
            "merge-receipt.json": _canonical(merge_receipt),
            "class-catalog.bin": catalog,
            "class-catalog-finalization-receipt.json": _canonical(
                finalization_receipt
            ),
        }
        for name, raw in files.items():
            (self.package / name).write_bytes(raw)
        package_bytes = sum(len(raw) for raw in files.values())
        apk_bytes = self.apk.stat().st_size
        total_bytes = package_bytes + apk_bytes + MANDATORY_RESERVE_BYTES
        final_result = {
            "apk": {
                "bytes": apk_bytes,
                "path": str(self.apk.resolve()),
                "sha256": _sha256(self.apk.read_bytes()),
                "sourceCommit": SOURCE_COMMIT,
            },
            "footprint": {
                "hardCeilingBytes": 40_000_000_000,
                "hardStrictlyBelow": True,
                "mandatoryReserveBytes": MANDATORY_RESERVE_BYTES,
                "preferredCeilingBytes": 25_000_000_000,
                "preferredStrictlyBelow": True,
                "totalBytes": total_bytes,
            },
            "package": {
                "bytes": package_bytes,
                "packageId": PACKAGE_ID,
                "path": str(self.package.resolve()),
            },
            "schema": "flightalert.experiment8.final-package-monitor-result.v1",
            "state": "complete",
            "rebind": {"sourceCommit": SOURCE_COMMIT},
        }
        self.result.write_bytes(_canonical(final_result))

    @staticmethod
    def _binding(name: str, raw: bytes) -> dict[str, object]:
        return {"bytes": len(raw), "name": name, "sha256": _sha256(raw)}

    @staticmethod
    def _tool_sha256(name: str) -> str:
        return _sha256((Path(__file__).parents[1] / name).read_bytes())


class PrepareReleaseAssetsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = _ValidReleaseFixture(self.root)

    def _prepare_from_result(self, output_name: str) -> Path:
        return prepare_release_assets(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
            output_directory=self.root / output_name,
            chunk_bytes=TEST_CHUNK_BYTES,
        )

    def test_prepare_derives_source_commit_from_rebound_final_result(self) -> None:
        manifest_path = self._prepare_from_result("derived-commit-assets")

        manifest = json.loads(manifest_path.read_text("utf-8"))
        self.assertEqual(SOURCE_COMMIT, manifest["sourceCommit"])

    def test_prepare_requires_final_rebind_source_commit(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        del result["rebind"]
        self.fixture.result.write_bytes(_canonical(result))

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            self._prepare_from_result("missing-rebind-assets")

    def test_prepare_requires_apk_source_commit(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        del result["apk"]["sourceCommit"]
        self.fixture.result.write_bytes(_canonical(result))

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            self._prepare_from_result("missing-apk-commit-assets")

    def test_prepare_rejects_malformed_apk_source_commit(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["apk"]["sourceCommit"] = SOURCE_COMMIT.upper()
        self.fixture.result.write_bytes(_canonical(result))

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            self._prepare_from_result("malformed-commit-assets")

    def test_prepare_rejects_malformed_rebind_source_commit(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["rebind"]["sourceCommit"] = SOURCE_COMMIT[:-1]
        self.fixture.result.write_bytes(_canonical(result))

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            self._prepare_from_result("malformed-rebind-commit-assets")

    def test_prepare_rejects_mismatched_rebind_source_commit(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["rebind"]["sourceCommit"] = "c" * 40
        self.fixture.result.write_bytes(_canonical(result))

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            self._prepare_from_result("mismatched-commit-assets")

    def test_release_asset_plan_accepts_exactly_999_chunks(self) -> None:
        sizes = (994, 1, 1, 1, 1, 1)

        self.assertEqual(
            999,
            release_module._validate_release_asset_plan(sizes, 1),
        )

    def test_release_asset_plan_rejects_1000_chunks(self) -> None:
        sizes = (995, 1, 1, 1, 1, 1)

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            release_module._validate_release_asset_plan(sizes, 1)

    def test_prepare_rejects_excess_chunks_before_stage_creation(self) -> None:
        output = self.root / "too-many-release-assets"

        with mock.patch.object(
            release_module,
            "_create_stage",
            side_effect=AssertionError("stage must not be created"),
        ) as create_stage:
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                prepare_release_assets(
                    package_directory=self.fixture.package,
                    apk_path=self.fixture.apk,
                    final_result_path=self.fixture.result,
                    output_directory=output,
                    chunk_bytes=1,
                )

        create_stage.assert_not_called()
        self.assertFalse(output.exists())

    def test_prepare_is_deterministic_and_preserves_multichunk_binary_crlf(
        self,
    ) -> None:
        first = self.root / "release-assets-first"
        second = self.root / "release-assets-second"

        for output in (first, second):
            manifest_path = prepare_release_assets(
                package_directory=self.fixture.package,
                apk_path=self.fixture.apk,
                final_result_path=self.fixture.result,
                output_directory=output,
                chunk_bytes=TEST_CHUNK_BYTES,
            )
            self.assertEqual(output / RELEASE_MANIFEST_NAME, manifest_path)

        first_files = {
            path.name: path.read_bytes() for path in first.iterdir()
        }
        second_files = {
            path.name: path.read_bytes() for path in second.iterdir()
        }
        self.assertEqual(first_files, second_files)
        manifest_raw = first_files[RELEASE_MANIFEST_NAME]
        manifest = json.loads(manifest_raw.decode("utf-8", "strict"))
        self.assertEqual(_canonical(manifest), manifest_raw)
        self.assertEqual(
            "flightalert.experiment8.reference-release-manifest.v1",
            manifest["schema"],
        )
        self.assertEqual(SOURCE_COMMIT, manifest["sourceCommit"])
        self.assertEqual(PACKAGE_ID, manifest["package"]["packageId"])
        self.assertEqual(
            list(installer_module.PACKAGE_FILE_NAMES),
            [item["name"] for item in manifest["package"]["files"]],
        )
        self.assertEqual(
            {
                "bytes": self.fixture.apk.stat().st_size,
                "sha256": _sha256(self.fixture.apk.read_bytes()),
            },
            manifest["apk"],
        )
        self.assertEqual(
            {
                "bytes": self.fixture.result.stat().st_size,
                "sha256": _sha256(self.fixture.result.read_bytes()),
            },
            manifest["finalResult"],
        )

        records = next(
            item
            for item in manifest["package"]["files"]
            if item["name"] == "records.fadictpack"
        )
        self.assertGreater(len(records["chunks"]), 1)
        self.assertEqual(
            list(range(0, records["bytes"], TEST_CHUNK_BYTES)),
            [chunk["offset"] for chunk in records["chunks"]],
        )
        rebuilt = b"".join(
            first_files[chunk["asset"]] for chunk in records["chunks"]
        )
        self.assertEqual(
            (self.fixture.package / "records.fadictpack").read_bytes(),
            rebuilt,
        )
        for chunk in records["chunks"]:
            raw = first_files[chunk["asset"]]
            self.assertEqual(len(raw), chunk["bytes"])
            self.assertLessEqual(len(raw), TEST_CHUNK_BYTES)
            self.assertEqual(_sha256(raw), chunk["sha256"])

        asset_names = {
            chunk["asset"]
            for item in manifest["package"]["files"]
            for chunk in item["chunks"]
        }
        self.assertEqual(asset_names | {RELEASE_MANIFEST_NAME}, set(first_files))

    def test_prepare_rejects_host_install_plan_mismatch_without_residue(
        self,
    ) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["apk"]["sha256"] = "0" * 64
        self.fixture.result.write_bytes(_canonical(result))
        output = self.root / "invalid-release-assets"

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            prepare_release_assets(
                package_directory=self.fixture.package,
                apk_path=self.fixture.apk,
                final_result_path=self.fixture.result,
                output_directory=output,
                chunk_bytes=TEST_CHUNK_BYTES,
            )

        self.assertFalse(output.exists())
        self.assertEqual(
            [], list(self.root.glob(f".{output.name}.exp8-prepare-*.stage"))
        )

    def test_prepare_requires_every_chunk_to_be_strictly_below_two_gb(
        self,
    ) -> None:
        output = self.root / "oversized-chunk-assets"

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            prepare_release_assets(
                package_directory=self.fixture.package,
                apk_path=self.fixture.apk,
                final_result_path=self.fixture.result,
                output_directory=output,
                chunk_bytes=MAX_RELEASE_ASSET_BYTES,
            )

        self.assertFalse(output.exists())

    def test_prepare_failure_cleans_tokenized_stage(self) -> None:
        output = self.root / "failed-release-assets"

        with mock.patch.object(
            release_module.os,
            "fsync",
            side_effect=OSError("injected sync failure"),
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                prepare_release_assets(
                    package_directory=self.fixture.package,
                    apk_path=self.fixture.apk,
                    final_result_path=self.fixture.result,
                    output_directory=output,
                    chunk_bytes=TEST_CHUNK_BYTES,
                )

        self.assertFalse(output.exists())
        self.assertEqual(
            [], list(self.root.glob(f".{output.name}.exp8-prepare-*.stage"))
        )

    def test_prepare_preserves_preexisting_output(self) -> None:
        output = self.root / "existing-release-assets"
        output.mkdir()
        sentinel = output / "keep.txt"
        sentinel.write_bytes(b"keep\n")

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            prepare_release_assets(
                package_directory=self.fixture.package,
                apk_path=self.fixture.apk,
                final_result_path=self.fixture.result,
                output_directory=output,
                chunk_bytes=TEST_CHUNK_BYTES,
            )

        self.assertEqual([sentinel], list(output.iterdir()))
        self.assertEqual(b"keep\n", sentinel.read_bytes())

    def test_prepare_rejects_unbound_stage_entry_and_cleans_it(self) -> None:
        output = self.root / "injected-release-assets"
        real_write = release_module._write_exact_file

        def write_then_inject(path: Path, raw: bytes) -> None:
            real_write(path, raw)
            (path.parent / "unbound.bin").write_bytes(b"not-in-manifest")

        with mock.patch.object(
            release_module,
            "_write_exact_file",
            side_effect=write_then_inject,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                prepare_release_assets(
                    package_directory=self.fixture.package,
                    apk_path=self.fixture.apk,
                    final_result_path=self.fixture.result,
                    output_directory=output,
                    chunk_bytes=TEST_CHUNK_BYTES,
                )

        self.assertFalse(output.exists())
        self.assertEqual(
            [], list(self.root.glob(f".{output.name}.exp8-prepare-*.stage"))
        )

    def test_prepare_rejects_bound_shard_mutated_before_publication(self) -> None:
        output = self.root / "mutated-release-assets"
        real_write = release_module._write_exact_file

        def write_then_mutate(path: Path, raw: bytes) -> None:
            real_write(path, raw)
            shard = next(path.parent.glob("*.part"))
            shard_raw = shard.read_bytes()
            shard.write_bytes(bytes((shard_raw[0] ^ 0xFF,)) + shard_raw[1:])

        with mock.patch.object(
            release_module,
            "_write_exact_file",
            side_effect=write_then_mutate,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                prepare_release_assets(
                    package_directory=self.fixture.package,
                    apk_path=self.fixture.apk,
                    final_result_path=self.fixture.result,
                    output_directory=output,
                    chunk_bytes=TEST_CHUNK_BYTES,
                )

        self.assertFalse(output.exists())
        self.assertEqual(
            [], list(self.root.glob(f".{output.name}.exp8-prepare-*.stage"))
        )

    def test_prepare_leaves_safe_orphan_if_initial_identity_probe_fails(
        self,
    ) -> None:
        output = self.root / "identity-probe-release-assets"
        real_stat = Path.stat
        failed = False

        def fail_first_stage_stat(
            path: Path, *, follow_symlinks: bool = True
        ) -> object:
            nonlocal failed
            if (
                not failed
                and path.name.startswith(f".{output.name}.exp8-prepare-")
                and path.name.endswith(".stage")
                and not follow_symlinks
            ):
                failed = True
                raise OSError("injected stage identity failure")
            return real_stat(path, follow_symlinks=follow_symlinks)

        with mock.patch.object(
            Path,
            "stat",
            autospec=True,
            side_effect=fail_first_stage_stat,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                prepare_release_assets(
                    package_directory=self.fixture.package,
                    apk_path=self.fixture.apk,
                    final_result_path=self.fixture.result,
                    output_directory=output,
                    chunk_bytes=TEST_CHUNK_BYTES,
                )

        self.assertTrue(failed)
        self.assertFalse(output.exists())
        orphans = list(
            self.root.glob(f".{output.name}.exp8-prepare-*.stage")
        )
        self.assertEqual(1, len(orphans))
        self.assertEqual([], list(orphans[0].iterdir()))

    def test_prepare_preserves_empty_replacement_after_initial_probe_failure(
        self,
    ) -> None:
        output = self.root / "empty-replacement-probe-assets"
        real_stat = Path.stat
        original_stage: Path | None = None
        replacement_stage: Path | None = None

        def replace_with_empty_stage_then_fail_stat(
            path: Path, *, follow_symlinks: bool = True
        ) -> object:
            nonlocal original_stage, replacement_stage
            if (
                replacement_stage is None
                and path.name.startswith(f".{output.name}.exp8-prepare-")
                and path.name.endswith(".stage")
                and not follow_symlinks
            ):
                original_stage = path.with_name(path.name + ".original")
                path.rename(original_stage)
                path.mkdir()
                replacement_stage = path
                raise OSError("injected empty replacement identity ambiguity")
            return real_stat(path, follow_symlinks=follow_symlinks)

        with mock.patch.object(
            Path,
            "stat",
            autospec=True,
            side_effect=replace_with_empty_stage_then_fail_stat,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                self._prepare_from_result("empty-replacement-probe-assets")

        self.assertIsNotNone(original_stage)
        self.assertIsNotNone(replacement_stage)
        assert original_stage is not None
        assert replacement_stage is not None
        self.assertTrue(original_stage.is_dir())
        self.assertTrue(replacement_stage.is_dir())
        self.assertEqual([], list(replacement_stage.iterdir()))

    def test_prepare_never_adopts_replacement_after_initial_probe_failure(
        self,
    ) -> None:
        output = self.root / "ambiguous-probe-release-assets"
        real_stat = Path.stat
        replacement_stage: Path | None = None

        def replace_stage_then_fail_stat(
            path: Path, *, follow_symlinks: bool = True
        ) -> object:
            nonlocal replacement_stage
            if (
                replacement_stage is None
                and path.name.startswith(f".{output.name}.exp8-prepare-")
                and path.name.endswith(".stage")
                and not follow_symlinks
            ):
                path.rmdir()
                path.mkdir()
                (path / "competitor.txt").write_bytes(b"competitor\n")
                replacement_stage = path
                raise OSError("injected ambiguous stage identity")
            return real_stat(path, follow_symlinks=follow_symlinks)

        with mock.patch.object(
            Path,
            "stat",
            autospec=True,
            side_effect=replace_stage_then_fail_stat,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                self._prepare_from_result("ambiguous-probe-release-assets")

        self.assertIsNotNone(replacement_stage)
        assert replacement_stage is not None
        self.assertEqual(
            b"competitor\n",
            (replacement_stage / "competitor.txt").read_bytes(),
        )

    def test_prepare_revalidates_all_published_bytes_after_rename(self) -> None:
        output = self.root / "post-rename-mutated-release-assets"
        real_rename = release_module._rename_directory_no_replace
        mutated = False

        def rename_then_mutate(source: Path, target: Path) -> None:
            nonlocal mutated
            real_rename(source, target)
            shard = next(target.glob("*.part"))
            raw = shard.read_bytes()
            shard.write_bytes(bytes((raw[0] ^ 0xFF,)) + raw[1:])
            mutated = True

        with mock.patch.object(
            release_module,
            "_rename_directory_no_replace",
            side_effect=rename_then_mutate,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                self._prepare_from_result("post-rename-mutated-release-assets")

        self.assertTrue(mutated)
        self.assertFalse(output.exists())

    def test_prepare_rejects_source_changed_after_final_shard_hash(self) -> None:
        output = self.root / "late-source-change-assets"
        real_assert = release_module._assert_prepared_stage
        changed = False

        def assert_then_change_source(
            stage: Path,
            file_documents: list[dict[str, object]],
            manifest_raw: bytes,
        ) -> None:
            nonlocal changed
            real_assert(stage, file_documents, manifest_raw)
            records = self.fixture.package / "records.fadictpack"
            raw = records.read_bytes()
            records.write_bytes(bytes((raw[0] ^ 0xFF,)) + raw[1:])
            changed = True

        with mock.patch.object(
            release_module,
            "_assert_prepared_stage",
            side_effect=assert_then_change_source,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                prepare_release_assets(
                    package_directory=self.fixture.package,
                    apk_path=self.fixture.apk,
                    final_result_path=self.fixture.result,
                    output_directory=output,
                    chunk_bytes=TEST_CHUNK_BYTES,
                )

        self.assertTrue(changed)
        self.assertFalse(output.exists())
        self.assertEqual(
            [], list(self.root.glob(f".{output.name}.exp8-prepare-*.stage"))
        )


class DownloadUrlPolicyTest(unittest.TestCase):
    def _policy(self) -> object:
        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            return release_module._DownloadUrlPolicy.for_initial(
                GITHUB_MANIFEST_URL,
                allow_loopback_http=False,
            )

    def test_production_initial_url_is_exact_repository_github_release(self) -> None:
        malformed = (
            "https://example.invalid/phowell212/FlightAlert/releases/download/v8/"
            + RELEASE_MANIFEST_NAME,
            "https://github.com/phowell212/Other/releases/download/v8/"
            + RELEASE_MANIFEST_NAME,
            "https://github.com/phowell212/FlightAlert/releases/latest/"
            + RELEASE_MANIFEST_NAME,
            "https://user@github.com/phowell212/FlightAlert/releases/download/v8/"
            + RELEASE_MANIFEST_NAME,
            "https://github.com:444/phowell212/FlightAlert/releases/download/v8/"
            + RELEASE_MANIFEST_NAME,
            GITHUB_MANIFEST_URL + "#fragment",
        )

        for url in malformed:
            with self.subTest(url=url), mock.patch.object(
                release_module,
                "_resolve_host_addresses",
                return_value=(PUBLIC_TEST_ADDRESS,),
            ):
                with self.assertRaises(
                    installer_module.ReferencePackageInstallError
                ):
                    release_module._DownloadUrlPolicy.for_initial(
                        url,
                        allow_loopback_http=False,
                    )

    def test_redirect_rejects_cross_origin_attacker(self) -> None:
        policy = self._policy()
        attacker = "https://attacker.invalid/release/asset.part"

        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                policy.validate_redirect(attacker)

    def test_redirect_rejects_https_to_http_downgrade(self) -> None:
        policy = self._policy()
        downgraded = GITHUB_RELEASE_DIRECTORY_URL.replace("https://", "http://")
        downgraded += "asset.part"

        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                policy.validate_redirect(downgraded)

    def test_same_origin_redirect_cannot_escape_exact_release_directory(self) -> None:
        policy = self._policy()
        escaped = (
            "https://github.com/phowell212/FlightAlert/releases/download/other/"
            "asset.part"
        )

        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                policy.validate_redirect(escaped)

    def test_same_origin_redirect_can_stay_in_exact_release_directory(self) -> None:
        policy = self._policy()
        target = GITHUB_RELEASE_DIRECTORY_URL + "asset.part"

        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            self.assertEqual(target, policy.validate_redirect(target))

    def test_resolution_rejects_any_nonpublic_target_address(self) -> None:
        for address in (
            "127.0.0.1",
            "10.0.0.1",
            "169.254.1.1",
            "192.0.2.1",
            "0.0.0.0",
            "224.0.0.1",
            "::1",
        ):
            with self.subTest(address=address), mock.patch.object(
                release_module,
                "_resolve_host_addresses",
                return_value=(PUBLIC_TEST_ADDRESS, address),
            ):
                with self.assertRaises(
                    installer_module.ReferencePackageInstallError
                ):
                    release_module._DownloadUrlPolicy.for_initial(
                        GITHUB_MANIFEST_URL,
                        allow_loopback_http=False,
                    )

    def test_redirect_allows_exact_github_release_cdn_hosts(self) -> None:
        policy = self._policy()
        allowed = (
            "https://release-assets.githubusercontent.com/github-production-"
            "release-asset/123/asset?sp=signed",
            "https://objects.githubusercontent.com/github-production-release-"
            "asset/123/asset?sp=signed",
        )

        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            for target in allowed:
                with self.subTest(target=target):
                    self.assertEqual(target, policy.validate_redirect(target))

    def test_redirect_rejects_unsafe_cdn_authority_or_fragment(self) -> None:
        policy = self._policy()
        unsafe = (
            "https://user@release-assets.githubusercontent.com/asset",
            "https://release-assets.githubusercontent.com:444/asset",
            "https://objects.githubusercontent.com/asset#fragment",
        )

        with mock.patch.object(
            release_module,
            "_resolve_host_addresses",
            return_value=(PUBLIC_TEST_ADDRESS,),
        ):
            for target in unsafe:
                with self.subTest(target=target):
                    with self.assertRaises(
                        installer_module.ReferencePackageInstallError
                    ):
                        policy.validate_redirect(target)


class _SilentFileHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, _: str, *args: object) -> None:
        del args

    def do_GET(self) -> None:
        path = Path(self.translate_path(self.path))
        if path.name == RELEASE_MANIFEST_NAME and (
            path.parent / OVERSIZED_MANIFEST_MARKER
        ).exists():
            declared = installer_module.MAX_JSON_BYTES + 1
            self.send_response(200)
            self.send_header("Content-Length", str(declared))
            self.end_headers()
            return
        if path.name == RELEASE_MANIFEST_NAME and path.stat().st_size > (
            installer_module.MAX_JSON_BYTES
        ):
            self.send_response(200)
            self.send_header("Content-Length", str(path.stat().st_size))
            self.end_headers()
            return
        super().do_GET()


class _QuietThreadingHttpServer(http.server.ThreadingHTTPServer):
    def handle_error(self, request: object, client_address: object) -> None:
        del request, client_address


class FetchReleaseAssetsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        fixture_root = self.root / "source"
        fixture_root.mkdir()
        self.fixture = _ValidReleaseFixture(fixture_root)
        self.assets = self.root / "release-assets"
        prepare_release_assets(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
            output_directory=self.assets,
            chunk_bytes=TEST_CHUNK_BYTES,
        )
        handler = partial(_SilentFileHandler, directory=str(self.assets))
        self.server = _QuietThreadingHttpServer(("127.0.0.1", 0), handler)
        self.server_thread = threading.Thread(
            target=self.server.serve_forever,
            daemon=True,
        )
        self.server_thread.start()
        self.addCleanup(self._stop_server)
        host, port = self.server.server_address
        self.manifest_url = f"http://{host}:{port}/{RELEASE_MANIFEST_NAME}"
        self.output = self.root / PACKAGE_ID

    @property
    def manifest_path(self) -> Path:
        return self.assets / RELEASE_MANIFEST_NAME

    def _manifest(self) -> dict[str, object]:
        return json.loads(self.manifest_path.read_text("utf-8"))

    def _write_manifest(self, manifest: object) -> None:
        self.manifest_path.write_bytes(_canonical(manifest))

    def _assert_fetch_rejected_without_residue(self) -> None:
        with self.assertRaises(installer_module.ReferencePackageInstallError):
            fetch_release_assets(
                manifest_url=self.manifest_url,
                output_directory=self.output,
                allow_loopback_http=True,
            )
        self.assertFalse(self.output.exists())
        self.assertEqual(
            [],
            list(
                self.root.glob(
                    f".{self.output.name}.exp8-fetch-*.stage"
                )
            ),
        )

    def _stop_server(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.server_thread.join(timeout=5)

    def test_fetch_round_trip_reconstructs_only_exact_package_files(self) -> None:
        result = fetch_release_assets(
            manifest_url=self.manifest_url,
            output_directory=self.output,
            allow_loopback_http=True,
        )

        self.assertEqual(self.output, result)
        self.assertEqual(
            list(installer_module.PACKAGE_FILE_NAMES),
            sorted(path.name for path in self.output.iterdir()),
        )
        for name in installer_module.PACKAGE_FILE_NAMES:
            self.assertEqual(
                (self.fixture.package / name).read_bytes(),
                (self.output / name).read_bytes(),
            )
        self.assertEqual(
            b"\x00records\r\npack\xff-with-binary-crlf" * 40,
            (self.output / "records.fadictpack").read_bytes(),
        )

    def test_fetch_rejects_corrupt_chunk_and_cleans_partial_stage(self) -> None:
        manifest = self._manifest()
        chunk = manifest["package"]["files"][0]["chunks"][0]
        asset = self.assets / chunk["asset"]
        raw = asset.read_bytes()
        asset.write_bytes(bytes((raw[0] ^ 0xFF,)) + raw[1:])

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_truncated_chunk_and_cleans_partial_stage(self) -> None:
        manifest = self._manifest()
        chunk = manifest["package"]["files"][0]["chunks"][0]
        asset = self.assets / chunk["asset"]
        asset.write_bytes(asset.read_bytes()[:-1])

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_noncanonical_manifest(self) -> None:
        manifest = self._manifest()
        self.manifest_path.write_text(
            json.dumps(manifest, indent=2, sort_keys=False),
            "utf-8",
        )

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_manifest_larger_than_strict_bound(self) -> None:
        (self.assets / OVERSIZED_MANIFEST_MARKER).touch()

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_traversal_file_name(self) -> None:
        manifest = self._manifest()
        manifest["package"]["files"][0]["name"] = "../escaped.bin"
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()
        self.assertFalse((self.root / "escaped.bin").exists())

    def test_fetch_rejects_traversal_asset_name(self) -> None:
        manifest = self._manifest()
        manifest["package"]["files"][0]["chunks"][0]["asset"] = (
            "../escaped.part"
        )
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_reordered_package_files(self) -> None:
        manifest = self._manifest()
        files = manifest["package"]["files"]
        files[0], files[1] = files[1], files[0]
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_duplicate_json_key(self) -> None:
        raw = self.manifest_path.read_bytes()
        needle = b'"schema":'
        duplicated = raw.replace(
            needle,
            (
                b'"schema":"flightalert.experiment8.'
                b'reference-release-manifest.v1","schema":'
            ),
            1,
        )
        self.assertNotEqual(raw, duplicated)
        self.manifest_path.write_bytes(duplicated)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_asset_name_collision(self) -> None:
        manifest = self._manifest()
        files = manifest["package"]["files"]
        files[1]["chunks"][0]["asset"] = files[0]["chunks"][0]["asset"]
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_more_than_999_shards_before_stage_creation(self) -> None:
        manifest = self._manifest()
        first = manifest["package"]["files"][0]
        old_bytes = first["bytes"]
        first["bytes"] = 1000
        first["chunks"] = [
            {
                "asset": f"{PACKAGE_ID}.00.{index:05d}.part",
                "bytes": 1,
                "offset": index,
                "sha256": "0" * 64,
            }
            for index in range(1000)
        ]
        manifest["package"]["bytes"] += 1000 - old_bytes
        self._write_manifest(manifest)

        with mock.patch.object(
            release_module,
            "_create_stage",
            side_effect=AssertionError("stage must not be created"),
        ) as create_stage:
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                fetch_release_assets(
                    manifest_url=self.manifest_url,
                    output_directory=self.output,
                    allow_loopback_http=True,
                )

        create_stage.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_fetch_rejects_chunk_offset_gap(self) -> None:
        manifest = self._manifest()
        records = next(
            item
            for item in manifest["package"]["files"]
            if item["name"] == "records.fadictpack"
        )
        self.assertGreater(len(records["chunks"]), 1)
        records["chunks"][1]["offset"] += 1
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_package_byte_total_mismatch(self) -> None:
        manifest = self._manifest()
        manifest["package"]["bytes"] += 1
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_authority_footprint_over_installer_ceiling(self) -> None:
        manifest = self._manifest()
        manifest["apk"]["bytes"] = installer_module.HARD_FOOTPRINT_CEILING_BYTES
        self._write_manifest(manifest)

        self._assert_fetch_rejected_without_residue()

    def test_fetch_rejects_plain_http_without_explicit_loopback_seam(self) -> None:
        with self.assertRaises(installer_module.ReferencePackageInstallError):
            fetch_release_assets(
                manifest_url=self.manifest_url,
                output_directory=self.output,
            )
        self.assertFalse(self.output.exists())

    def test_loopback_seam_rejects_nonloopback_plain_http(self) -> None:
        unsafe = (
            "http://192.0.2.1/releases/download/v1/"
            + RELEASE_MANIFEST_NAME
        )

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            fetch_release_assets(
                manifest_url=unsafe,
                output_directory=self.output,
                allow_loopback_http=True,
            )
        self.assertFalse(self.output.exists())

    def test_fetch_preserves_preexisting_output_without_network_merge(self) -> None:
        self.output.mkdir()
        sentinel = self.output / "keep.txt"
        sentinel.write_bytes(b"pre-existing\n")

        with self.assertRaises(installer_module.ReferencePackageInstallError):
            fetch_release_assets(
                manifest_url=self.manifest_url,
                output_directory=self.output,
                allow_loopback_http=True,
            )

        self.assertEqual([sentinel], list(self.output.iterdir()))
        self.assertEqual(b"pre-existing\n", sentinel.read_bytes())

    def test_fetch_publication_uses_atomic_no_replace_rename(self) -> None:
        real_publish = release_module._rename_directory_no_replace

        with mock.patch.object(
            release_module,
            "_rename_directory_no_replace",
            wraps=real_publish,
        ) as publish:
            fetch_release_assets(
                manifest_url=self.manifest_url,
                output_directory=self.output,
                allow_loopback_http=True,
            )

        publish.assert_called_once()

    def test_fetch_revalidates_all_published_bytes_after_rename(self) -> None:
        real_rename = release_module._rename_directory_no_replace
        mutated = False

        def rename_then_mutate(source: Path, target: Path) -> None:
            nonlocal mutated
            real_rename(source, target)
            manifest = target / "manifest.json"
            raw = manifest.read_bytes()
            manifest.write_bytes(bytes((raw[0] ^ 0xFF,)) + raw[1:])
            mutated = True

        with mock.patch.object(
            release_module,
            "_rename_directory_no_replace",
            side_effect=rename_then_mutate,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                fetch_release_assets(
                    manifest_url=self.manifest_url,
                    output_directory=self.output,
                    allow_loopback_http=True,
                )

        self.assertTrue(mutated)
        self.assertFalse(self.output.exists())

    def test_fetch_never_removes_replacement_after_rename(self) -> None:
        real_rename = release_module._rename_directory_no_replace
        sentinel = self.output / "competitor.txt"

        def rename_then_replace(source: Path, target: Path) -> None:
            real_rename(source, target)
            for item in target.iterdir():
                item.unlink()
            target.rmdir()
            target.mkdir()
            sentinel.write_bytes(b"competitor\n")

        with mock.patch.object(
            release_module,
            "_rename_directory_no_replace",
            side_effect=rename_then_replace,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                fetch_release_assets(
                    manifest_url=self.manifest_url,
                    output_directory=self.output,
                    allow_loopback_http=True,
                )

        self.assertEqual([sentinel], list(self.output.iterdir()))
        self.assertEqual(b"competitor\n", sentinel.read_bytes())

    def test_fetch_publication_race_preserves_competing_output(self) -> None:
        sentinel = self.output / "competitor.txt"

        def competing_publish(_: Path, target: Path) -> None:
            target.mkdir()
            sentinel.write_bytes(b"competitor\n")
            raise FileExistsError("injected publication race")

        with mock.patch.object(
            release_module,
            "_rename_directory_no_replace",
            side_effect=competing_publish,
        ):
            with self.assertRaises(installer_module.ReferencePackageInstallError):
                fetch_release_assets(
                    manifest_url=self.manifest_url,
                    output_directory=self.output,
                    allow_loopback_http=True,
                )

        self.assertEqual([sentinel], list(self.output.iterdir()))
        self.assertEqual(b"competitor\n", sentinel.read_bytes())
        self.assertEqual(
            [],
            list(
                self.root.glob(
                    f".{self.output.name}.exp8-fetch-*.stage"
                )
            ),
        )


class ReleaseAssetsCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = _ValidReleaseFixture(self.root)

    def test_prepare_cli_runs_real_validation_and_sharding(self) -> None:
        output = self.root / "cli-release-assets"
        stdout = io.StringIO()

        with redirect_stdout(stdout):
            exit_code = _main(
                [
                    "prepare",
                    "--package",
                    str(self.fixture.package),
                    "--apk",
                    str(self.fixture.apk),
                    "--final-result",
                    str(self.fixture.result),
                    "--output",
                    str(output),
                    "--chunk-bytes",
                    str(TEST_CHUNK_BYTES),
                ]
            )

        self.assertEqual(0, exit_code)
        self.assertEqual(str(output / RELEASE_MANIFEST_NAME), stdout.getvalue().strip())
        self.assertTrue((output / RELEASE_MANIFEST_NAME).is_file())

    def test_prepare_cli_has_no_unrelated_source_commit_argument(self) -> None:
        output = self.root / "old-cli-release-assets"
        stderr = io.StringIO()

        with (
            self.assertRaises(SystemExit),
            mock.patch.object(
                release_module,
                "prepare_release_assets",
                side_effect=AssertionError("parser must reject old argument"),
            ),
            redirect_stderr(stderr),
        ):
            _main(
                [
                    "prepare",
                    "--package",
                    str(self.fixture.package),
                    "--apk",
                    str(self.fixture.apk),
                    "--final-result",
                    str(self.fixture.result),
                    "--source-commit",
                    SOURCE_COMMIT,
                    "--output",
                    str(output),
                ]
            )

        self.assertIn("unrecognized arguments: --source-commit", stderr.getvalue())

    def test_fetch_cli_forwards_https_manifest_and_exact_output(self) -> None:
        output = self.root / PACKAGE_ID
        manifest_url = GITHUB_MANIFEST_URL
        stdout = io.StringIO()

        with (
            mock.patch.object(
                release_module,
                "fetch_release_assets",
                return_value=output,
            ) as fetch,
            redirect_stdout(stdout),
        ):
            exit_code = _main(
                [
                    "fetch",
                    "--manifest-url",
                    manifest_url,
                    "--output",
                    str(output),
                ]
            )

        self.assertEqual(0, exit_code)
        fetch.assert_called_once_with(
            manifest_url=manifest_url,
            output_directory=output,
        )
        self.assertEqual(str(output), stdout.getvalue().strip())

    def test_powershell_wrapper_forwards_only_manifest_url_and_output(self) -> None:
        wrapper = (
            Path(__file__).parents[2]
            / "download-reference-dictionary-experiment8.ps1"
        )
        text = wrapper.read_text("utf-8")

        self.assertIn("[string]$ManifestUrl", text)
        self.assertIn("[string]$Output", text)
        self.assertIn("tools.experiment8.reference_release_assets", text)
        self.assertIn("'fetch'", text)
        self.assertIn("'--manifest-url'", text)
        self.assertIn("'--output'", text)
        self.assertNotIn("allow-loopback", text.lower())


if __name__ == "__main__":
    unittest.main()
