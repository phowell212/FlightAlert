from __future__ import annotations

import hashlib
import io
import json
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from typing import Sequence
from unittest import mock

import tools.experiment8.reference_package_install as installer_module
from tools.experiment8.reference_package_install import (
    AdbInstallDevice,
    AtomicDeviceLeaseClient,
    CommandResult,
    DevicePrestate,
    HostInstallPlan,
    ReferencePackageInstaller,
    ReferencePackageInstallError,
    RemoteFileIdentity,
    _main,
    parse_adb_devices,
)


PACKAGE_ID = "world-experiment8-binary-v3"
MANDATORY_RESERVE_BYTES = 1_500_000_000


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


class _ValidInstallFixture:
    def __init__(self, root: Path) -> None:
        self.package = root / PACKAGE_ID
        self.package.mkdir()
        self.apk = root / "Flight Alert-debug.apk"
        self.apk.write_bytes(b"source-matched-apk")
        self.result = root / "final-package-monitor-v3-r2.result.json"

        records = b"records-pack"
        tile_index = b"tile-index"
        catalog = b"C" * 754
        renderer_sha256 = "7" * 64
        merger_sha256 = self._tool_sha256("v3_package_merger.py")
        finalizer_sha256 = self._tool_sha256("v3_class_catalog_finalizer.py")
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
                    {"firstOrdinal": 0, "xMax": 0, "xMin": 0, "yMax": 0, "yMin": 0, "z": 0}
                ],
            },
            "merge": {
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
            "payloadSchema": "flightalert.reference.renderer-tile.v1",
            "presentationPolicySha256": "1" * 64,
            "rendererSemanticStreamSha256": renderer_sha256,
            "schemaVersion": 3,
            "sourcedTextPolicySha256": "2" * 64,
            "unicodeScriptProfileSha256": "3" * 64,
        }
        manifest_raw = _canonical(manifest)
        runtime_bindings = [
            self._binding("manifest.json", manifest_raw),
            self._binding("records.fadictpack", records),
            self._binding("tile-index.bin", tile_index),
            self._binding("class-catalog.bin", catalog),
        ]
        merge_receipt = {
            "coverage": {"completeWholeEarthDictionary": True, "tileCount": 1},
            "inputs": [],
            "mergerSha256": merger_sha256,
            "outputFiles": [
                self._binding("manifest.json", b"pre-finalizer-manifest\n"),
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
                "rendererRecordCount": 1,
            },
            "finalizerSha256": finalizer_sha256,
            "inputFiles": [],
            "outputFiles": runtime_bindings,
            "packageId": PACKAGE_ID,
            "rendererContractSha256": renderer_sha256,
            "rendererSemanticStreamSha256": renderer_sha256,
            "schema": "flightalert.experiment8.v3-class-catalog-finalization-receipt.v1",
            "subtypeCounts": {},
        }
        files = {
            "manifest.json": manifest_raw,
            "records.fadictpack": records,
            "tile-index.bin": tile_index,
            "merge-receipt.json": _canonical(merge_receipt),
            "class-catalog.bin": catalog,
            "class-catalog-finalization-receipt.json": _canonical(finalization_receipt),
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
        }
        self.result.write_bytes(_canonical(final_result))

    @staticmethod
    def _binding(name: str, raw: bytes) -> dict[str, object]:
        return {"bytes": len(raw), "name": name, "sha256": _sha256(raw)}

    @staticmethod
    def _tool_sha256(name: str) -> str:
        return _sha256((Path(__file__).parents[1] / name).read_bytes())


class HostInstallPlanTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.fixture = _ValidInstallFixture(Path(self.temporary.name))

    def validate(self) -> HostInstallPlan:
        return HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
        )

    def test_exact_final_package_and_apk_produce_immutable_plan(self) -> None:
        plan = self.validate()

        self.assertEqual(PACKAGE_ID, plan.package_id)
        self.assertEqual(6, len(plan.package_files))
        self.assertEqual(
            sum(item.byte_length for item in plan.package_files),
            plan.package_bytes,
        )
        self.assertEqual(_sha256(self.fixture.apk.read_bytes()), plan.apk_sha256)
        self.assertTrue(plan.preferred_strictly_below)
        self.assertTrue(plan.hard_strictly_below)

    def test_unexpected_package_entry_is_rejected(self) -> None:
        (self.fixture.package / "extra.bin").write_bytes(b"not-runtime")

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "exact six-file inventory"
        ):
            self.validate()


class _FakeLease:
    def __init__(self, *, held: bool = False) -> None:
        self.held = held
        self.acquired = False
        self.released = False
        self.token = "0123456789abcdef0123456789abcdef"

    def acquire(self, **_: object) -> str:
        if self.held:
            raise ReferencePackageInstallError("device lease is already held")
        self.acquired = True
        return self.token

    def heartbeat(self, token: str) -> None:
        if token != self.token or not self.acquired or self.released:
            raise ReferencePackageInstallError("invalid fake heartbeat")

    def release(self, token: str) -> None:
        if token != self.token or not self.acquired:
            raise ReferencePackageInstallError("invalid fake release")
        self.released = True


class _FakeDevice:
    def __init__(self, root: Path, *, available_bytes: int = 80_000_000_000) -> None:
        self.root = root
        self.available = available_bytes
        self.serial = "SERIAL"
        self.files: dict[str, bytes] = {}
        self.directories: set[str] = set()
        self.installed_apk = b"old-apk"
        self.preferences = b"<map><boolean name=\"outlines.coastlines\" value=\"true\" /></map>"
        self.running = True
        self.fail_at: str | None = None
        self.calls: list[str] = []

    def seed_package(self, directory: str, marker: bytes) -> None:
        self.directories.add(directory)
        self.files[f"{directory}/manifest.json"] = marker

    def require_single_ready_device(self) -> str:
        self.calls.append("require_single_ready_device")
        return self.serial

    def capture_prestate(
        self, evidence_directory: Path, final_package_path: str
    ) -> DevicePrestate:
        self.calls.append("capture_prestate")
        backup = evidence_directory / "installed-prestate.apk"
        backup.write_bytes(self.installed_apk)
        return DevicePrestate(
            serial=self.serial,
            apk_backup_path=backup,
            apk_bytes=len(self.installed_apk),
            apk_sha256=_sha256(self.installed_apk),
            preferences=self.preferences,
            app_was_running=self.running,
            final_package_was_present=final_package_path in self.directories,
        )

    def available_bytes(self, _: str) -> int:
        self.calls.append("available_bytes")
        return self.available

    def path_exists(self, path: str) -> bool:
        return path in self.directories or path in self.files

    def make_directory(self, path: str) -> None:
        self.calls.append(f"mkdir:{path}")
        if self.fail_at == "mkdir":
            raise ReferencePackageInstallError("injected mkdir failure")
        self.directories.add(path)

    def push_file(self, local: Path, remote: str) -> None:
        self.calls.append(f"push:{Path(remote).name}")
        if self.fail_at == f"push:{Path(remote).name}":
            raise ReferencePackageInstallError("injected push failure")
        self.files[remote] = local.read_bytes()

    def remote_file_identity(self, path: str) -> RemoteFileIdentity:
        raw = self.files[path]
        return RemoteFileIdentity(byte_length=len(raw), sha256=_sha256(raw))

    def force_stop(self) -> None:
        self.calls.append("force_stop")
        self.running = False

    def move_no_replace(self, source: str, destination: str) -> None:
        self.calls.append(f"move:{source}->{destination}")
        if self.fail_at == "move":
            raise ReferencePackageInstallError("injected move failure")
        if destination in self.directories or destination in self.files:
            raise ReferencePackageInstallError("destination already exists")
        if source in self.directories:
            self.directories.remove(source)
            self.directories.add(destination)
            moved = {
                destination + path[len(source) :]: raw
                for path, raw in self.files.items()
                if path == source or path.startswith(source + "/")
            }
            self.files = {
                path: raw
                for path, raw in self.files.items()
                if not (path == source or path.startswith(source + "/"))
            }
            self.files.update(moved)
            return
        raise ReferencePackageInstallError("source does not exist")

    def install_apk(self, path: Path) -> None:
        self.calls.append("install_apk")
        if self.fail_at == "install_apk":
            raise ReferencePackageInstallError("injected install failure")
        self.installed_apk = path.read_bytes()

    def restore_preferences(self, raw: bytes) -> None:
        self.calls.append("restore_preferences")
        self.preferences = raw

    def restore_running(self, was_running: bool) -> None:
        self.calls.append("restore_running")
        self.running = was_running

    def restore_apk(self, path: Path) -> None:
        self.calls.append("restore_apk")
        self.installed_apk = path.read_bytes()

    def remove_tree(self, path: str) -> None:
        self.calls.append(f"remove:{path}")
        if self.fail_at == "remove_once":
            self.fail_at = None
            raise ReferencePackageInstallError("injected remove failure")
        if self.fail_at == "remove":
            raise ReferencePackageInstallError("injected remove failure")
        self.directories.discard(path)
        self.files = {
            item: raw
            for item, raw in self.files.items()
            if not (item == path or item.startswith(path + "/"))
        }

    def verify_installed_apk(self) -> RemoteFileIdentity:
        self.calls.append("verify_installed_apk")
        return RemoteFileIdentity(
            byte_length=len(self.installed_apk),
            sha256=_sha256(self.installed_apk),
        )


class DeviceTransactionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.fixture = _ValidInstallFixture(self.root)
        self.plan = HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
        )
        self.evidence = self.root / "evidence"
        self.lease = _FakeLease()
        self.device = _FakeDevice(self.root)

    def install(self) -> None:
        ReferencePackageInstaller(
            plan=self.plan,
            device=self.device,
            lease=self.lease,
            evidence_directory=self.evidence,
        ).install()

    def test_success_atomically_replaces_package_and_preserves_explicit_preferences(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        expected_preferences = self.device.preferences

        self.install()

        self.assertTrue(self.lease.acquired)
        self.assertTrue(self.lease.released)
        self.assertTrue(self.device.running)
        self.assertEqual(expected_preferences, self.device.preferences)
        self.assertEqual(self.fixture.apk.read_bytes(), self.device.installed_apk)
        self.assertEqual(
            (self.fixture.package / "manifest.json").read_bytes(),
            self.device.files[f"{final}/manifest.json"],
        )
        self.assertLess(
            self.device.calls.index("force_stop"),
            self.device.calls.index("push:manifest.json"),
        )
        self.assertFalse(
            any(".exp8-install-" in path for path in self.device.directories)
        )

    def test_install_failure_restores_exact_package_apk_preferences_and_running_state(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        before_files = dict(self.device.files)
        before_apk = self.device.installed_apk
        before_preferences = self.device.preferences
        self.device.fail_at = "install_apk"

        with self.assertRaisesRegex(ReferencePackageInstallError, "install failure"):
            self.install()

        self.assertTrue(self.lease.released)
        self.assertEqual(before_files, self.device.files)
        self.assertEqual(before_apk, self.device.installed_apk)
        self.assertEqual(before_preferences, self.device.preferences)
        self.assertTrue(self.device.running)
        failure = json.loads((self.evidence / "install-failure.json").read_text("utf-8"))
        self.assertEqual("recovered", failure["state"])
        self.assertEqual([], failure["cleanupFailures"])
        self.assertFalse(
            any(".exp8-install-" in path for path in self.device.directories)
        )

    def test_push_failure_leaves_no_visible_partial_package(self) -> None:
        self.device.fail_at = "push:records.fadictpack"

        with self.assertRaisesRegex(ReferencePackageInstallError, "push failure"):
            self.install()

        self.assertNotIn(
            ReferencePackageInstaller.FINAL_PACKAGE_PATH, self.device.directories
        )
        self.assertTrue(self.lease.released)
        self.assertFalse(
            any(".exp8-install-" in path for path in self.device.directories)
        )

    def test_backup_cleanup_failure_rolls_back_and_invalidates_success_receipt(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        before_files = dict(self.device.files)
        self.device.fail_at = "remove_once"

        with self.assertRaisesRegex(ReferencePackageInstallError, "remove failure"):
            self.install()

        self.assertEqual(before_files, self.device.files)
        self.assertFalse((self.evidence / "install-receipt.json").exists())
        self.assertTrue(self.lease.released)

    def test_receipt_failure_cannot_delete_the_only_rollback_package(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        before_files = dict(self.device.files)

        with mock.patch.object(
            ReferencePackageInstaller,
            "_write_receipt",
            side_effect=ReferencePackageInstallError("injected receipt failure"),
        ):
            with self.assertRaisesRegex(
                ReferencePackageInstallError, "receipt failure"
            ):
                self.install()

        self.assertEqual(before_files, self.device.files)
        self.assertTrue(self.lease.released)

    def test_insufficient_space_fails_before_stage_creation(self) -> None:
        self.device.available = self.plan.package_bytes - 1

        with self.assertRaisesRegex(ReferencePackageInstallError, "free space"):
            self.install()

        self.assertFalse(any(call.startswith("mkdir:") for call in self.device.calls))
        self.assertNotIn("force_stop", self.device.calls)
        self.assertNotIn("restore_apk", self.device.calls)
        self.assertNotIn("restore_preferences", self.device.calls)
        self.assertTrue(self.lease.released)

    def test_held_lease_prevents_every_device_call(self) -> None:
        self.lease.held = True

        with self.assertRaisesRegex(ReferencePackageInstallError, "already held"):
            self.install()

        self.assertEqual([], self.device.calls)


class _ScriptedRunner:
    def __init__(self, steps: list[tuple[tuple[str, ...], CommandResult]]) -> None:
        self.steps = list(steps)
        self.calls: list[tuple[str, ...]] = []

    def run(
        self,
        arguments: Sequence[str],
        *,
        input_bytes: bytes | None = None,
        timeout_seconds: float | None = None,
    ) -> CommandResult:
        del input_bytes, timeout_seconds
        actual = tuple(arguments)
        self.calls.append(actual)
        if not self.steps:
            raise AssertionError(f"unexpected command: {actual!r}")
        expected, result = self.steps.pop(0)
        if actual != expected:
            raise AssertionError(f"expected {expected!r}, got {actual!r}")
        return result


class ProductionBoundaryTest(unittest.TestCase):
    def test_adb_device_parser_requires_one_ready_physical_row(self) -> None:
        self.assertEqual(
            "SERIAL",
            parse_adb_devices(
                b"List of devices attached\nSERIAL device product:x model:y device:z transport_id:1\n"
            ),
        )
        for raw in (
            b"List of devices attached\n",
            b"List of devices attached\nSERIAL offline transport_id:1\n",
            b"List of devices attached\nA device transport_id:1\nB device transport_id:2\n",
            b"daemon noise\nList of devices attached\nSERIAL device transport_id:1\n",
        ):
            with self.subTest(raw=raw):
                with self.assertRaises(ReferencePackageInstallError):
                    parse_adb_devices(raw)

    def test_atomic_lease_client_uses_exact_json_token_and_releases(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "a" * 32
        acquire = CommandResult(
            return_code=0,
            stdout=_canonical(
                {"held": True, "lease": {"token": token}, "token": token}
            ),
            stderr=b"",
        )
        heartbeat = CommandResult(
            return_code=0,
            stdout=_canonical(
                {"held": True, "lease": {"token": token}, "token": token}
            ),
            stderr=b"",
        )
        release = CommandResult(
            return_code=0,
            stdout=_canonical({"held": False, "lease": None, "token": ""}),
            stderr=b"",
        )
        runner = _ScriptedRunner(
            [
                (
                    (
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-File",
                        str(helper),
                        "acquire",
                        "-Owner",
                        "Zeus/Experiment8",
                        "-ThreadId",
                        "019f4dd9-f25e-7b53-94af-5fd25aeed93f",
                        "-Purpose",
                        "purpose",
                        "-Scenario",
                        "scenario",
                        "-StatefulEffects",
                        "effects",
                        "-ExpectedMinutes",
                        "180",
                    ),
                    acquire,
                ),
                (
                    (
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-File",
                        str(helper),
                        "heartbeat",
                        "-Token",
                        token,
                    ),
                    heartbeat,
                ),
                (
                    (
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-File",
                        str(helper),
                        "release",
                        "-Token",
                        token,
                    ),
                    release,
                ),
            ]
        )
        lease = AtomicDeviceLeaseClient(
            helper_path=helper,
            thread_id="019f4dd9-f25e-7b53-94af-5fd25aeed93f",
            runner=runner,
            powershell="powershell.exe",
            heartbeat_interval_seconds=3600.0,
        )

        actual = lease.acquire(
            owner="Zeus/Experiment8",
            purpose="purpose",
            scenario="scenario",
            stateful_effects="effects",
            expected_minutes=180,
        )
        lease.heartbeat(actual)
        lease.release(actual)

        self.assertEqual(token, actual)
        self.assertEqual([], runner.steps)

    def test_adb_identity_parser_uses_argument_arrays_and_exact_sha(self) -> None:
        runner = _ScriptedRunner(
            [
                (
                    ("adb.exe", "devices", "-l"),
                    CommandResult(
                        0,
                        b"List of devices attached\nSERIAL device transport_id:1\n",
                        b"",
                    ),
                ),
                (
                    (
                        "adb.exe",
                        "-s",
                        "SERIAL",
                        "shell",
                        "stat",
                        "-c",
                        "%s",
                        "/remote/file",
                    ),
                    CommandResult(0, b"123\n", b""),
                ),
                (
                    (
                        "adb.exe",
                        "-s",
                        "SERIAL",
                        "shell",
                        "sha256sum",
                        "/remote/file",
                    ),
                    CommandResult(0, ("f" * 64 + "  /remote/file\n").encode(), b""),
                ),
            ]
        )
        device = AdbInstallDevice(adb="adb.exe", runner=runner)

        self.assertEqual("SERIAL", device.require_single_ready_device())
        self.assertEqual(
            RemoteFileIdentity(byte_length=123, sha256="f" * 64),
            device.remote_file_identity("/remote/file"),
        )

    def test_validate_only_cli_prints_plan_without_constructing_adb(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = _ValidInstallFixture(Path(temporary))
            output = io.StringIO()
            with redirect_stdout(output):
                code = _main(
                    [
                        "--package",
                        str(fixture.package),
                        "--apk",
                        str(fixture.apk),
                        "--final-result",
                        str(fixture.result),
                        "--validate-only",
                    ]
                )

        self.assertEqual(0, code)
        document = json.loads(output.getvalue())
        self.assertEqual(PACKAGE_ID, document["packageId"])
        self.assertEqual(6, len(document["files"]))
        self.assertTrue(document["hardStrictlyBelow"])

    def test_powershell_wrapper_runs_the_real_validate_only_cli(self) -> None:
        wrapper = Path(__file__).parents[2] / "install-reference-dictionary-experiment8.ps1"
        with tempfile.TemporaryDirectory() as temporary:
            fixture = _ValidInstallFixture(Path(temporary))
            completed = subprocess.run(
                [
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-File",
                    str(wrapper),
                    "-PackageRoot",
                    str(fixture.package),
                    "-ApkPath",
                    str(fixture.apk),
                    "-FinalResult",
                    str(fixture.result),
                    "-ValidateOnly",
                ],
                cwd=Path(__file__).parents[3],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
            )

        self.assertEqual(0, completed.returncode, completed.stderr.decode("utf-8", "replace"))
        document = json.loads(completed.stdout.decode("utf-8", "strict"))
        self.assertEqual(PACKAGE_ID, document["packageId"])


class HostInstallPlanEdgeCaseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.fixture = _ValidInstallFixture(Path(self.temporary.name))

    def validate(self) -> HostInstallPlan:
        return HostInstallPlan.validate(
            package_directory=self.fixture.package,
            apk_path=self.fixture.apk,
            final_result_path=self.fixture.result,
        )

    def test_final_result_must_bind_the_exact_apk_hash(self) -> None:
        document = json.loads(self.fixture.result.read_text("utf-8"))
        document["apk"]["sha256"] = "0" * 64
        self.fixture.result.write_bytes(_canonical(document))

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "final result APK SHA-256 differs"
        ):
            self.validate()

    def test_package_id_mismatch_is_rejected(self) -> None:
        document = json.loads((self.fixture.package / "manifest.json").read_text("utf-8"))
        document["packageId"] = "different-package"
        (self.fixture.package / "manifest.json").write_bytes(_canonical(document))

        with self.assertRaisesRegex(ReferencePackageInstallError, "package ID"):
            self.validate()

    def test_finalization_receipt_must_bind_runtime_file_bytes(self) -> None:
        (self.fixture.package / "records.fadictpack").write_bytes(b"changed-records")

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "finalization receipt differs"
        ):
            self.validate()

    def test_noncanonical_receipt_json_is_rejected(self) -> None:
        path = self.fixture.package / "merge-receipt.json"
        document = json.loads(path.read_text("utf-8"))
        path.write_text(json.dumps(document, indent=2), encoding="utf-8")

        with self.assertRaisesRegex(ReferencePackageInstallError, "canonical JSON"):
            self.validate()

    def test_duplicate_json_key_is_rejected_before_policy_checks(self) -> None:
        path = self.fixture.package / "merge-receipt.json"
        path.write_bytes(b'{"schema":"one","schema":"two"}\n')

        with self.assertRaisesRegex(ReferencePackageInstallError, "repeats key"):
            self.validate()

    def test_package_file_changed_after_hash_is_rejected_before_plan_returns(self) -> None:
        original = installer_module._validate_receipts

        def mutate_after_receipt_validation(**arguments: object) -> None:
            original(**arguments)
            path = self.fixture.package / "records.fadictpack"
            path.write_bytes(b"changed-pack")

        with mock.patch.object(
            installer_module,
            "_validate_receipts",
            side_effect=mutate_after_receipt_validation,
        ):
            with self.assertRaisesRegex(
                ReferencePackageInstallError, "changed after validation"
            ):
                self.validate()

    def test_hard_ceiling_claim_cannot_be_relaxed_by_the_result(self) -> None:
        document = json.loads(self.fixture.result.read_text("utf-8"))
        document["footprint"]["totalBytes"] = 40_000_000_000
        document["footprint"]["hardStrictlyBelow"] = True
        self.fixture.result.write_bytes(_canonical(document))

        with self.assertRaisesRegex(ReferencePackageInstallError, "footprint total"):
            self.validate()


if __name__ == "__main__":
    unittest.main()
