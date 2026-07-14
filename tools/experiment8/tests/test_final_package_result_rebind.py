from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

import tools.experiment8.final_package_result_rebind as rebind_module
import tools.experiment8.reference_package_install as installer_module
from tools.experiment8.final_package_result_rebind import (
    CANONICAL_APK_RELATIVE_PATH,
    FINAL_APK_SELECTION,
    _main,
    rebind_final_package_result,
)
from tools.experiment8.reference_package_install import (
    HostInstallPlan,
    ReferencePackageInstallError,
)


PACKAGE_ID = "world-experiment8-binary-v4"
ORIGINAL_SOURCE_COMMIT = "a" * 40
MANDATORY_RESERVE_BYTES = 1_500_000_000
STALE_FINAL_APK = b"ignored-stale-foreign-apk-from-an-earlier-build"
BUILT_FINAL_APK = (
    b"fresh-gradle-clean-assemble-debug-output-bound-to-current-head"
)


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


class _PlanningResultFixture:
    def __init__(self, root: Path) -> None:
        self.package = root / PACKAGE_ID
        self.package.mkdir()
        self.apk = root / "planning" / "Flight Alert-debug.apk"
        self.apk.parent.mkdir()
        self.apk.write_bytes(b"source-matched-planning-apk")
        self.result = root / "final-package-monitor-v4-r3.result.json"

        records = b"records-pack"
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
                "selection": (
                    "frozen source-matched planning footprint snapshot"
                ),
                "sha256": _sha256(self.apk.read_bytes()),
                "sourceCommit": ORIGINAL_SOURCE_COMMIT,
            },
            "completedUtc": "2026-07-13T20:00:00.0000000Z",
            "evidence": {
                "finalizerStderr": "finalizer.stderr.log",
                "finalizerStdout": "finalizer.stdout.json",
                "mergeStderr": "merge.stderr.log",
                "mergeStdout": "merge.stdout.json",
                "monitorLog": "monitor.log",
            },
            "footprint": {
                "hardCeilingBytes": installer_module.HARD_FOOTPRINT_CEILING_BYTES,
                "hardStrictlyBelow": True,
                "mandatoryReserveBytes": MANDATORY_RESERVE_BYTES,
                "preferredCeilingBytes": (
                    installer_module.PREFERRED_FOOTPRINT_CEILING_BYTES
                ),
                "preferredStrictlyBelow": True,
                "totalBytes": total_bytes,
            },
            "mergeFreeBytes": 50_000_000_000,
            "package": {
                "bytes": package_bytes,
                "packageId": PACKAGE_ID,
                "path": str(self.package.resolve()),
            },
            "schema": "flightalert.experiment8.final-package-monitor-result.v1",
            "state": "complete",
            "toolHashes": {
                "finalizerSha256": finalizer_sha256,
                "mergerSha256": merger_sha256,
            },
        }
        self.result.write_bytes(_canonical(final_result))

    @staticmethod
    def _binding(name: str, raw: bytes) -> dict[str, object]:
        return {"bytes": len(raw), "name": name, "sha256": _sha256(raw)}

    @staticmethod
    def _tool_sha256(name: str) -> str:
        return _sha256((Path(__file__).parents[1] / name).read_bytes())


class FinalPackageResultRebindTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = _PlanningResultFixture(self.root)
        self.repository = self.root / "source"
        self.repository.mkdir()
        self.final_apk = (
            self.repository
            / "build"
            / "outputs"
            / "apk"
            / "debug"
            / "Flight Alert-debug.apk"
        )
        self.final_apk.parent.mkdir(parents=True)
        self.final_apk.write_bytes(STALE_FINAL_APK)
        (self.repository / "tracked.txt").write_text("clean\n", "utf-8")
        (self.repository / ".gitignore").write_text("/build/\n", "utf-8")
        (self.repository / "test-gradle-output.apk").write_bytes(BUILT_FINAL_APK)
        self.gradle_wrapper = self.repository / "gradlew.bat"
        if os.name == "nt":
            self.gradle_wrapper.write_text(
                "\r\n".join(
                    (
                        "@echo off",
                        'if not "%~1"=="clean" exit /b 41',
                        'if not "%~2"=="assembleDebug" exit /b 42',
                        'if not "%~3"=="--no-daemon" exit /b 43',
                        'if not "%~4"=="" exit /b 44',
                        'if "%FLIGHT_ALERT_TEST_GRADLE_FAIL%"=="1" exit /b 45',
                        'if exist "build" rmdir /s /q "build"',
                        'mkdir "build\\outputs\\apk\\debug" || exit /b 46',
                        (
                            'copy /b /y "test-gradle-output.apk" '
                            '"build\\outputs\\apk\\debug\\Flight Alert-debug.apk" '
                            '>nul || exit /b 47'
                        ),
                        "exit /b 0",
                        "",
                    )
                ),
                "utf-8",
                newline="",
            )
        else:
            self.gradle_wrapper.write_text(
                "\n".join(
                    (
                        "#!/bin/sh",
                        '[ "$#" -eq 3 ] || exit 40',
                        '[ "$1" = clean ] || exit 41',
                        '[ "$2" = assembleDebug ] || exit 42',
                        '[ "$3" = --no-daemon ] || exit 43',
                        '[ "${FLIGHT_ALERT_TEST_GRADLE_FAIL:-}" != 1 ] || exit 45',
                        'rm -rf -- "build" || exit 46',
                        'mkdir -p -- "build/outputs/apk/debug" || exit 47',
                        (
                            'cp -- "test-gradle-output.apk" '
                            '"build/outputs/apk/debug/Flight Alert-debug.apk" '
                            '|| exit 48'
                        ),
                        "",
                    )
                ),
                "utf-8",
            )
            self.gradle_wrapper.chmod(0o755)
        self._git("init", "-q")
        self._git("config", "user.email", "flight-alert-test@example.invalid")
        self._git("config", "user.name", "Flight Alert Test")
        self._git("config", "commit.gpgSign", "false")
        self._git("add", ".")
        self._git("commit", "-qm", "final source")
        self.head = self._git("rev-parse", "--verify", "HEAD")
        self.output = self.root / "final-package-monitor-v4-final.result.json"

    def _git(self, *arguments: str) -> str:
        completed = subprocess.run(
            ["git", "-C", str(self.repository), *arguments],
            check=False,
            capture_output=True,
            text=True,
        )
        if completed.returncode != 0:
            self.fail(
                f"git {' '.join(arguments)} failed: "
                f"{completed.stdout}{completed.stderr}"
            )
        return completed.stdout.strip()

    def _rebind(
        self, *, apk: Path | None = None, install_policy: str | None = None
    ) -> None:
        arguments: dict[str, object] = {
            "package_directory": self.fixture.package,
            "apk_path": self.final_apk if apk is None else apk,
            "source_repository": self.repository,
            "planning_result_path": self.fixture.result,
            "output_path": self.output,
        }
        if install_policy is not None:
            arguments["install_policy"] = install_policy
        rebind_final_package_result(
            **arguments,  # type: ignore[arg-type]
        )

    def _use_authority_v2_package(self) -> None:
        from tools.experiment8.tests.test_v3_class_catalog_finalizer import (
            _write_authority_v2_merged_package,
        )
        from tools.experiment8.v3_class_catalog_finalizer import (
            finalize_v3_class_catalog,
        )

        authority_root = self.root / "authority-v2"
        authority_root.mkdir()
        self.fixture.package = _write_authority_v2_merged_package(
            authority_root
        )
        finalize_v3_class_catalog(self.fixture.package)
        result = json.loads(self.fixture.result.read_text("utf-8"))
        package_bytes = sum(
            path.stat().st_size for path in self.fixture.package.iterdir()
        )
        result["package"].update(
            {
                "bytes": package_bytes,
                "path": str(self.fixture.package.resolve()),
            }
        )
        total_bytes = (
            package_bytes
            + self.fixture.apk.stat().st_size
            + MANDATORY_RESERVE_BYTES
        )
        result["footprint"].update(
            {
                "hardStrictlyBelow": True,
                "preferredStrictlyBelow": True,
                "totalBytes": total_bytes,
            }
        )
        result["state"] = "complete"
        self.fixture.result.write_bytes(_canonical(result))

    def _assert_rejected_without_output(self) -> None:
        with self.assertRaises(ReferencePackageInstallError):
            self._rebind()
        self.assertFalse(self.output.exists())
        self.assertEqual(
            [], list(self.output.parent.glob(f".{self.output.name}.stage-*"))
        )

    def test_cli_rebinds_only_apk_identity_and_derived_provenance(self) -> None:
        original_raw = self.fixture.result.read_bytes()
        original = json.loads(original_raw.decode("utf-8"))
        package_before = {
            path.name: path.read_bytes() for path in self.fixture.package.iterdir()
        }
        HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
        )
        with self.assertRaises(ReferencePackageInstallError):
            HostInstallPlan.validate(
                package_directory=self.fixture.package,
                apk_path=self.final_apk,
                final_result_path=self.fixture.result,
            )

        exit_code = _main(
            [
                "--package",
                str(self.fixture.package),
                "--apk",
                str(self.final_apk),
                "--source-repository",
                str(self.repository),
                "--planning-result",
                str(self.fixture.result),
                "--output",
                str(self.output),
            ]
        )

        self.assertEqual(0, exit_code)
        self.assertTrue(self.output.is_file())
        self.assertFalse(self.output.is_symlink())
        rebound_raw = self.output.read_bytes()
        rebound = json.loads(rebound_raw.decode("utf-8"))
        self.assertEqual(_canonical(rebound), rebound_raw)
        HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.final_apk,
            final_result_path=self.output,
        )
        self.assertEqual(original_raw, self.fixture.result.read_bytes())
        self.assertEqual(
            package_before,
            {path.name: path.read_bytes() for path in self.fixture.package.iterdir()},
        )

        expected = copy.deepcopy(original)
        expected["apk"] = {
            "bytes": self.final_apk.stat().st_size,
            "path": str(self.final_apk.resolve()),
            "selection": FINAL_APK_SELECTION,
            "sha256": _sha256(self.final_apk.read_bytes()),
            "sourceCommit": self.head,
        }
        expected["completedUtc"] = rebound["completedUtc"]
        expected["footprint"]["totalBytes"] = (
            expected["package"]["bytes"]
            + expected["apk"]["bytes"]
            + expected["footprint"]["mandatoryReserveBytes"]
        )
        expected["footprint"]["preferredStrictlyBelow"] = (
            expected["footprint"]["totalBytes"]
            < expected["footprint"]["preferredCeilingBytes"]
        )
        expected["footprint"]["hardStrictlyBelow"] = (
            expected["footprint"]["totalBytes"]
            < expected["footprint"]["hardCeilingBytes"]
        )
        expected["rebind"] = {
            "originalResultSha256": _sha256(original_raw),
            "sourceCommit": self.head,
        }
        self.assertEqual(expected, rebound)
        self.assertRegex(
            rebound["completedUtc"],
            r"\A\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z\Z",
        )
        datetime.fromisoformat(rebound["completedUtc"].removesuffix("Z") + "+00:00")

    def test_clean_gradle_build_replaces_ignored_stale_apk_before_binding(
        self,
    ) -> None:
        self.assertEqual(STALE_FINAL_APK, self.final_apk.read_bytes())
        self.assertEqual("", self._git("check-ignore", "--quiet", self.final_apk))

        self._rebind()

        self.assertEqual(BUILT_FINAL_APK, self.final_apk.read_bytes())
        rebound = json.loads(self.output.read_text("utf-8"))
        self.assertEqual(len(BUILT_FINAL_APK), rebound["apk"]["bytes"])
        self.assertEqual(_sha256(BUILT_FINAL_APK), rebound["apk"]["sha256"])

    def test_rejects_nonzero_gradle_build_without_publishing(self) -> None:
        with mock.patch.dict(
            os.environ, {"FLIGHT_ALERT_TEST_GRADLE_FAIL": "1"}
        ):
            self._assert_rejected_without_output()

    def test_rejects_nested_directory_instead_of_exact_git_top_level(self) -> None:
        nested_repository = self.repository / "nested"
        nested_repository.mkdir()
        shutil.copy2(self.gradle_wrapper, nested_repository / "gradlew.bat")
        shutil.copy2(
            self.repository / "test-gradle-output.apk",
            nested_repository / "test-gradle-output.apk",
        )
        nested_apk = nested_repository / CANONICAL_APK_RELATIVE_PATH
        nested_apk.parent.mkdir(parents=True)
        nested_apk.write_bytes(STALE_FINAL_APK)

        with self.assertRaises(ReferencePackageInstallError):
            rebind_final_package_result(
                package_directory=self.fixture.package,
                apk_path=nested_apk,
                source_repository=nested_repository,
                planning_result_path=self.fixture.result,
                output_path=self.output,
            )
        self.assertFalse(self.output.exists())

    def test_rejects_reparse_metadata_on_a_canonical_path_component(self) -> None:
        build_component = self.repository / "build"
        real_stat = Path.stat

        def marked_stat(
            path: Path, *, follow_symlinks: bool = True
        ) -> os.stat_result:
            information = real_stat(path, follow_symlinks=follow_symlinks)
            if Path(path) == build_component and not follow_symlinks:
                marked = mock.Mock(wraps=information)
                marked.st_mode = information.st_mode
                marked.st_file_attributes = (
                    getattr(information, "st_file_attributes", 0)
                    | installer_module.FILE_ATTRIBUTE_REPARSE_POINT
                )
                return marked
            return information

        with mock.patch.object(
            Path, "stat", autospec=True, side_effect=marked_stat
        ):
            self._assert_rejected_without_output()

    def test_rejects_directory_link_escape_in_canonical_apk_path(self) -> None:
        build_link = self.repository / "build"
        shutil.rmtree(build_link)
        escaped_build = self.root / "escaped-build"
        escaped_apk = escaped_build / CANONICAL_APK_RELATIVE_PATH.relative_to(
            "build"
        )
        escaped_apk.parent.mkdir(parents=True)
        escaped_apk.write_bytes(STALE_FINAL_APK)
        if os.name == "nt":
            junction = subprocess.run(
                [
                    "cmd.exe",
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(build_link),
                    str(escaped_build),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            if junction.returncode != 0:
                self.skipTest(
                    "directory junction creation unavailable: "
                    f"{junction.stdout}{junction.stderr}"
                )
        else:
            try:
                build_link.symlink_to(escaped_build, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlink creation unavailable: {error}")

        def remove_directory_link() -> None:
            try:
                os.rmdir(build_link)
            except FileNotFoundError:
                pass

        self.addCleanup(remove_directory_link)
        with mock.patch.object(
            rebind_module,
            "_clean_build_final_apk",
            side_effect=AssertionError("build must not run through a reparse path"),
        ):
            with self.assertRaises(ReferencePackageInstallError):
                self._rebind()
        self.assertTrue(escaped_apk.is_file())
        self.assertFalse(self.output.exists())

    def test_rejects_unstaged_tracked_source_change(self) -> None:
        (self.repository / "tracked.txt").write_text("dirty\n", "utf-8")

        self._assert_rejected_without_output()

    def test_rejects_staged_tracked_source_change(self) -> None:
        (self.repository / "tracked.txt").write_text("staged\n", "utf-8")
        self._git("add", "tracked.txt")

        self._assert_rejected_without_output()

    def test_rejects_noncanonical_apk_location(self) -> None:
        alternate = self.repository / "Flight Alert-debug.apk"
        alternate.write_bytes(self.final_apk.read_bytes())
        self._git("add", alternate.name)
        self._git("commit", "-qm", "add alternate APK")

        with self.assertRaises(ReferencePackageInstallError):
            self._rebind(apk=alternate)
        self.assertFalse(self.output.exists())

    def test_rejects_changed_original_result(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["apk"]["sha256"] = "0" * 64
        self.fixture.result.write_bytes(_canonical(result))

        self._assert_rejected_without_output()

    def test_rejects_planning_result_with_existing_rebind_provenance(self) -> None:
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["rebind"] = {
            "originalResultSha256": "0" * 64,
            "sourceCommit": "b" * 40,
        }
        self.fixture.result.write_bytes(_canonical(result))
        HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
        )

        self._assert_rejected_without_output()

    def test_rejects_original_result_changed_after_initial_validation(self) -> None:
        original_raw = self.fixture.result.read_bytes()
        changed_raw = original_raw.replace(
            ORIGINAL_SOURCE_COMMIT.encode("ascii"), b"b" * 40
        )
        self.assertNotEqual(original_raw, changed_raw)
        before = self.fixture.result.stat(follow_symlinks=False)
        real_validate = rebind_module.HostInstallPlan.validate
        changed = False

        def validate_then_change(**arguments: Path) -> HostInstallPlan:
            nonlocal changed
            plan = real_validate(**arguments)
            result_path = Path(arguments["final_result_path"])
            if not changed and result_path.resolve() == self.fixture.result.resolve():
                self.fixture.result.write_bytes(changed_raw)
                os.utime(
                    self.fixture.result,
                    ns=(before.st_atime_ns, before.st_mtime_ns),
                )
                changed = True
            return plan

        with mock.patch.object(
            rebind_module.HostInstallPlan,
            "validate",
            side_effect=validate_then_change,
        ):
            self._assert_rejected_without_output()

    def test_rejects_changed_original_apk(self) -> None:
        self.fixture.apk.write_bytes(b"changed-planning-apk")

        self._assert_rejected_without_output()

    def test_rejects_changed_package(self) -> None:
        records = self.fixture.package / "records.fadictpack"
        records.write_bytes(records.read_bytes() + b"changed")

        self._assert_rejected_without_output()

    def test_rejects_preexisting_output_without_changing_it(self) -> None:
        sentinel = b"pre-existing-output\n"
        self.output.write_bytes(sentinel)

        with self.assertRaises(ReferencePackageInstallError):
            self._rebind()
        self.assertEqual(sentinel, self.output.read_bytes())

    def test_publication_race_never_replaces_a_competing_output(self) -> None:
        sentinel = b"competing-output\n"
        real_lexists = rebind_module.os.path.lexists
        output_checks = 0

        def appear_after_final_freshness_check(path: object) -> bool:
            nonlocal output_checks
            if Path(path) == self.output:
                output_checks += 1
                if output_checks == 2:
                    self.output.write_bytes(sentinel)
                    return False
            return real_lexists(path)

        with mock.patch.object(
            rebind_module.os.path,
            "lexists",
            side_effect=appear_after_final_freshness_check,
        ):
            with self.assertRaises(ReferencePackageInstallError):
                self._rebind()
        self.assertEqual(2, output_checks)
        self.assertEqual(sentinel, self.output.read_bytes())
        self.assertEqual(
            [], list(self.output.parent.glob(f".{self.output.name}.stage-*"))
        )

    def test_authority_v2_package_rebinds_to_exact_final_apk(self) -> None:
        self._use_authority_v2_package()
        policy = installer_module.INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION

        self._rebind(install_policy=policy)

        rebound = json.loads(self.output.read_text("utf-8"))
        plan = HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.final_apk,
            final_result_path=self.output,
            install_policy=policy,
        )
        self.assertEqual(plan.package_bytes, rebound["package"]["bytes"])
        self.assertEqual(self.head, rebound["apk"]["sourceCommit"])
        self.assertEqual(_sha256(BUILT_FINAL_APK), rebound["apk"]["sha256"])
        self.assertEqual(policy, rebound["rebind"]["installPolicy"])

    def test_publication_mutation_removes_only_the_tool_created_link(self) -> None:
        real_link = rebind_module.os.link

        def link_then_mutate(
            source: Path,
            destination: Path,
            *,
            follow_symlinks: bool = True,
        ) -> None:
            real_link(
                source,
                destination,
                follow_symlinks=follow_symlinks,
            )
            Path(destination).write_bytes(b"mutated-after-publication-link\n")

        with mock.patch.object(
            rebind_module.os, "link", side_effect=link_then_mutate
        ):
            self._assert_rejected_without_output()

    def test_stage_sync_failure_removes_only_the_owned_stage(self) -> None:
        with mock.patch.object(
            rebind_module.os, "fsync", side_effect=OSError("sync failed")
        ):
            self._assert_rejected_without_output()

    def test_rejects_staged_result_changed_after_installer_validation(self) -> None:
        real_validate = rebind_module.HostInstallPlan.validate
        changed = False

        def validate_then_change(**arguments: Path) -> HostInstallPlan:
            nonlocal changed
            plan = real_validate(**arguments)
            result_path = Path(arguments["final_result_path"])
            if not changed and result_path != self.fixture.result:
                document = json.loads(result_path.read_text("utf-8"))
                document["rebind"]["originalResultSha256"] = "0" * 64
                result_path.write_bytes(_canonical(document))
                changed = True
            return plan

        with mock.patch.object(
            rebind_module.HostInstallPlan,
            "validate",
            side_effect=validate_then_change,
        ):
            self._assert_rejected_without_output()

    def test_rejects_package_changed_after_staged_result_validation(self) -> None:
        real_validate = rebind_module.HostInstallPlan.validate
        changed = False

        def validate_then_change(**arguments: Path) -> HostInstallPlan:
            nonlocal changed
            plan = real_validate(**arguments)
            result_path = Path(arguments["final_result_path"])
            if not changed and result_path != self.fixture.result:
                records = self.fixture.package / "records.fadictpack"
                records.write_bytes(records.read_bytes() + b"late-change")
                changed = True
            return plan

        with mock.patch.object(
            rebind_module.HostInstallPlan,
            "validate",
            side_effect=validate_then_change,
        ):
            self._assert_rejected_without_output()

    def test_rejects_rebound_hard_ceiling_overflow(self) -> None:
        original = json.loads(self.fixture.result.read_text("utf-8"))
        original_total = original["footprint"]["totalBytes"]
        hard_ceiling = original_total + 1
        self.assertGreater(
            original["package"]["bytes"]
            + self.final_apk.stat().st_size
            + MANDATORY_RESERVE_BYTES,
            hard_ceiling,
        )
        original["footprint"]["hardCeilingBytes"] = hard_ceiling
        self.fixture.result.write_bytes(_canonical(original))

        with mock.patch.object(
            installer_module, "HARD_FOOTPRINT_CEILING_BYTES", hard_ceiling
        ):
            HostInstallPlan.validate(
                package_directory=self.fixture.package,
                apk_path=self.fixture.apk,
                final_result_path=self.fixture.result,
            )
            self._assert_rejected_without_output()

    def test_visual_evaluation_rebind_preserves_failed_hard_ceiling_claim(self) -> None:
        policy = getattr(
            installer_module, "INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION", None
        )
        self.assertEqual("full-fidelity-visual-evaluation", policy)
        original = json.loads(self.fixture.result.read_text("utf-8"))
        original_total = original["footprint"]["totalBytes"]
        hard_ceiling = original_total + 1
        original["footprint"]["hardCeilingBytes"] = hard_ceiling
        self.fixture.result.write_bytes(_canonical(original))

        with mock.patch.object(
            installer_module, "HARD_FOOTPRINT_CEILING_BYTES", hard_ceiling
        ):
            self._rebind(install_policy=policy)
            rebound = json.loads(self.output.read_text("utf-8"))
            plan = HostInstallPlan.validate(
                package_directory=self.fixture.package,
                apk_path=self.final_apk,
                final_result_path=self.output,
                install_policy=policy,
            )

        self.assertFalse(rebound["footprint"]["hardStrictlyBelow"])
        self.assertEqual("failed-hard-ceiling", rebound["state"])
        self.assertEqual(policy, rebound["rebind"]["installPolicy"])
        self.assertEqual(policy, plan.install_policy)


if __name__ == "__main__":
    unittest.main()
