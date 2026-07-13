from __future__ import annotations

import hashlib
import io
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from dataclasses import replace
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
                    {"firstOrdinal": 0, "xMax": 0, "xMin": 0, "yMax": 0, "yMin": 0, "z": 0}
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

    def acquisition_intent(self, **arguments: object) -> dict[str, object]:
        invocation_id = arguments["invocation_id"]
        return {
            "expectedMinutes": arguments["expected_minutes"],
            "invocationId": invocation_id,
            "owner": arguments["owner"],
            "purpose": arguments["purpose"],
            "scenario": f"{arguments['scenario']} [invocation:{invocation_id}]",
            "statefulEffects": arguments["stateful_effects"],
            "threadId": "fake-thread",
        }

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

    def confirm_acquisition(self, _: object) -> str | None:
        return self.token if self.acquired and not self.released else None

    def reconcile_acquisition(self, _: object) -> None:
        self.released = True

    def reconcile_unfinished(self, _: str, __: object | None = None) -> None:
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
        self.mutation_guard: object | None = None

    def set_mutation_guard(self, guard: object | None) -> None:
        self.mutation_guard = guard

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

    def install_apk(self, path: Path, **_: object) -> None:
        self.calls.append("install_apk")
        if self.fail_at == "install_apk":
            raise ReferencePackageInstallError("injected install failure")
        self.installed_apk = path.read_bytes()

    def restore_preferences(self, raw: bytes) -> None:
        self.calls.append("restore_preferences")
        self.preferences = raw

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
        self.events: list[str] = []
        self.lease = _StatefulLease(self.events)
        self.device = _StatefulDevice(self.root, self.events)

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

        self.assertEqual(1, self.lease.acquire_count)
        self.assertEqual(1, self.lease.release_count)
        self.assertTrue(self.device.app_process_state().running)
        self.assertEqual(expected_preferences, self.device.preferences)
        self.assertEqual(self.fixture.apk.read_bytes(), self.device.installed_apk)
        self.assertEqual(
            (self.fixture.package / "manifest.json").read_bytes(),
            self.device.files[f"{final}/manifest.json"],
        )
        self.assertLess(
            self.events.index("mutate:force-stop"),
            self.events.index("mutate:push:manifest.json"),
        )
        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        self.assertIsNotNone(self.device.entry_identity(journal["paths"]["backup"]))

    def test_install_failure_restores_exact_package_apk_preferences_and_running_state(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        old_entry = self.device.entry_identity(final)
        before_apk = self.device.installed_apk
        before_preferences = self.device.preferences
        before_process = self.device.app_process_state()
        self.device.fail_operation = "install-apk-after-effect"

        with self.assertRaisesRegex(ReferencePackageInstallError, "upgrade"):
            self.install()

        self.assertEqual(self.lease.acquire_count, self.lease.release_count)
        self.assertEqual(old_entry.inode, self.device.entry_identity(final).inode)
        self.assertEqual(before_apk, self.device.installed_apk)
        self.assertEqual(before_preferences, self.device.preferences)
        self.assertEqual(before_process, self.device.app_process_state())
        failure = json.loads((self.evidence / "install-failure.json").read_text("utf-8"))
        self.assertEqual("recovered", failure["state"])
        self.assertEqual([], failure["cleanupFailures"])
        self.assertFalse(
            any(".exp8-install-" in path for path in self.device.entries)
        )

    def test_push_failure_leaves_no_visible_partial_package(self) -> None:
        self.device.fail_operation = "push:records.fadictpack"

        with self.assertRaisesRegex(ReferencePackageInstallError, "push failure"):
            self.install()

        self.assertNotIn(ReferencePackageInstaller.FINAL_PACKAGE_PATH, self.device.entries)
        self.assertEqual(self.lease.acquire_count, self.lease.release_count)
        self.assertFalse(
            any(".exp8-install-" in path for path in self.device.entries)
        )

    def test_backup_cleanup_failure_rolls_back_and_invalidates_success_receipt(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        self.install()
        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        backup = journal["paths"]["backup"]

        with mock.patch.object(
            self.device,
            "remove_bound_entry",
            side_effect=ReferencePackageInstallError("injected remove failure"),
        ):
            with self.assertRaisesRegex(ReferencePackageInstallError, "remove failure"):
                ReferencePackageInstaller(
                    plan=self.plan,
                    device=self.device,
                    lease=self.lease,
                    evidence_directory=self.evidence,
                ).finalize_acceptance()

        self.assertIsNotNone(self.device.entry_identity(backup))
        self.assertFalse((self.evidence / "final-success.json").exists())
        self.assertEqual(self.lease.acquire_count, self.lease.release_count)

    def test_receipt_failure_cannot_delete_the_only_rollback_package(self) -> None:
        final = ReferencePackageInstaller.FINAL_PACKAGE_PATH
        self.device.seed_package(final, b"old-manifest")
        original = installer_module._atomic_write_json

        def fail_pending(path: Path, document: object) -> None:
            if path.name == "pending-acceptance.json":
                raise ReferencePackageInstallError("injected receipt failure")
            original(path, document)

        with mock.patch.object(installer_module, "_atomic_write_json", side_effect=fail_pending):
            with self.assertRaisesRegex(
                ReferencePackageInstallError, "receipt failure"
            ):
                self.install()

        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        self.assertEqual("pending-acceptance", journal["state"])
        self.assertIsNotNone(self.device.entry_identity(journal["paths"]["backup"]))
        self.assertEqual(1, self.lease.release_count)

        self.install()

        self.assertTrue((self.evidence / "pending-acceptance.json").is_file())

    def test_insufficient_space_fails_before_stage_creation(self) -> None:
        self.device.available = self.plan.package_bytes - 1

        with self.assertRaisesRegex(ReferencePackageInstallError, "free space"):
            self.install()

        self.assertFalse(any("mutate:mkdir:" in event for event in self.events))
        self.assertNotIn("mutate:force-stop", self.events)
        self.assertNotIn("mutate:restore-apk-downgrade", self.events)
        self.assertNotIn("mutate:restore-preferences", self.events)
        self.assertEqual(self.lease.acquire_count, self.lease.release_count)

    def test_free_space_includes_package_apk_and_mandatory_reserve(self) -> None:
        required = (
            self.plan.package_bytes
            + self.plan.apk_bytes
            + MANDATORY_RESERVE_BYTES
        )
        self.device.available = required - 1

        with self.assertRaisesRegex(ReferencePackageInstallError, "free space"):
            self.install()

        self.assertNotIn("mutate:disallow-listener", self.events)

    def test_exact_required_free_space_boundary_is_accepted(self) -> None:
        self.device.available = (
            self.plan.package_bytes
            + self.plan.apk_bytes
            + MANDATORY_RESERVE_BYTES
        )

        self.install()

        self.assertTrue((self.evidence / "pending-acceptance.json").is_file())

    def test_free_space_budget_accepts_exact_signed_64_bit_limit(self) -> None:
        maximum = installer_module.MAX_SIGNED_64
        package_bytes = maximum - self.plan.apk_bytes - MANDATORY_RESERVE_BYTES
        maximum_plan = replace(self.plan, package_bytes=package_bytes)
        self.device.available = maximum
        transaction = ReferencePackageInstaller(
            plan=maximum_plan,
            device=self.device,
            lease=self.lease,
            evidence_directory=self.evidence,
        )

        transaction.install()

        self.assertTrue((self.evidence / "pending-acceptance.json").is_file())

    def test_free_space_budget_rejects_signed_64_bit_overflow(self) -> None:
        oversized = replace(self.plan, package_bytes=(1 << 63) - 1)
        transaction = ReferencePackageInstaller(
            plan=oversized,
            device=self.device,
            lease=self.lease,
            evidence_directory=self.evidence,
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "overflow"):
            transaction.install()

        self.assertNotIn("mutate:disallow-listener", self.events)

    def test_held_lease_prevents_every_device_call(self) -> None:
        self.lease = _FakeLease(held=True)

        with self.assertRaisesRegex(ReferencePackageInstallError, "already held"):
            self.install()

        self.assertEqual([], self.events)


class _ProcessKilled(BaseException):
    pass


class _StatefulLease:
    def __init__(self, events: list[str] | None = None) -> None:
        self.events = events if events is not None else []
        self.acquire_count = 0
        self.release_count = 0
        self.heartbeat_count = 0
        self.fail_heartbeat_at: int | None = None
        self.fail_first_heartbeat_transport = False
        self.interrupt_after_acquire = False
        self.active_token: str | None = None
        self.active_intent: dict[str, object] | None = None

    def acquisition_intent(self, **arguments: object) -> dict[str, object]:
        invocation_id = arguments["invocation_id"]
        return {
            "expectedMinutes": arguments["expected_minutes"],
            "invocationId": invocation_id,
            "owner": arguments["owner"],
            "purpose": arguments["purpose"],
            "scenario": f"{arguments['scenario']} [invocation:{invocation_id}]",
            "statefulEffects": arguments["stateful_effects"],
            "threadId": "stateful-thread",
        }

    def acquire(self, **arguments: object) -> str:
        if self.active_token is not None:
            raise ReferencePackageInstallError("fake lease is already held")
        self.acquire_count += 1
        token = f"{self.acquire_count:032x}"
        self.active_token = token
        self.active_intent = self.acquisition_intent(**arguments)
        self.events.append(f"lease-acquire:{self.acquire_count}")
        if self.interrupt_after_acquire:
            self.interrupt_after_acquire = False
            raise _ProcessKilled("interrupted after helper acquire")
        return token

    def heartbeat(self, token: str) -> None:
        self.heartbeat_count += 1
        self.events.append(f"lease-heartbeat:{self.acquire_count}")
        if token != self.active_token:
            raise ReferencePackageInstallError("fake lease ownership differs")
        if self.fail_first_heartbeat_transport:
            self.fail_first_heartbeat_transport = False
            raise ReferencePackageInstallError("injected heartbeat transport failure")
        if self.fail_heartbeat_at == self.heartbeat_count:
            self.active_token = None
            raise ReferencePackageInstallError("injected lease loss")

    def release(self, token: str) -> None:
        self.events.append(f"lease-release:{self.acquire_count}")
        if self.active_token is not None and token != self.active_token:
            raise ReferencePackageInstallError("fake release token differs")
        self.active_token = None
        self.active_intent = None
        self.release_count += 1

    def confirm_acquisition(self, intent: object) -> str | None:
        self.events.append("lease-confirm-intent")
        expected = dict(intent)  # type: ignore[arg-type]
        expected.pop("deviceOperation", None)
        if expected != self.active_intent:
            return None
        return self.active_token

    def reconcile_acquisition(self, intent: object) -> None:
        self.events.append("lease-reconcile-intent")
        expected = dict(intent)  # type: ignore[arg-type]
        expected.pop("deviceOperation", None)
        if self.active_token is not None and expected != self.active_intent:
            raise ReferencePackageInstallError("acquisition intent ownership differs")
        self.active_token = None
        self.active_intent = None

    def reconcile_unfinished(self, token: str, _: object | None = None) -> None:
        self.events.append(f"lease-reconcile:{token}")
        if self.active_token is not None and self.active_token != token:
            raise ReferencePackageInstallError("stale lease token differs")
        self.active_token = None
        self.active_intent = None


class _StatefulDevice:
    def __init__(self, root: Path, events: list[str] | None = None) -> None:
        self.root = root
        self.events = events if events is not None else []
        self.serial = "SERIAL"
        self.available = 80_000_000_000
        self.files: dict[str, bytes] = {}
        self.entries: dict[str, tuple[str, int, int]] = {}
        self.next_inode = 100
        self.installed_apk = b"old-apk"
        self.preferences_present = True
        self.preferences = b"<map><boolean name=\"outlines.coastlines\" value=\"true\" /></map>"
        self.listener_approval = (
            "com.flightalert/com.flightalert.alerts.MonitoringNotificationHiderService"
        )
        self.listener_bound = True
        self.pids = ("4242",)
        self.resumed_component = "com.flightalert/.MainActivity"
        self.focused_component = "com.flightalert/.MainActivity"
        self.screen_interactive = True
        self.device_locked = False
        self.active_services: tuple[str, ...] = ()
        self.running_reason = "foreground-activity"
        self.fail_operation: str | None = None
        self.post_effect_error_source: str | None = None
        self.probe_failures: set[str] = set()
        self.recovery_probe_failure_path: str | None = None
        self.identity_mismatch_path: str | None = None
        self.classifications: dict[str, str] = {}
        self.footprints: dict[str, tuple[int, int]] = {}
        self.restore_apk_used_downgrade = False
        self.mutation_guard: object | None = None
        self.simulate_inner_preference_commands = False
        self.after_first_inner_preference_command: object | None = None

    def set_mutation_guard(self, guard: object | None) -> None:
        self.mutation_guard = guard

    def _entry(self, path: str) -> object:
        entry_type = getattr(installer_module, "RemoteEntryIdentity", None)
        if entry_type is None:
            raise AssertionError("RemoteEntryIdentity is missing")
        kind, device, inode = self.entries[path]
        return entry_type(name=path.rsplit("/", 1)[-1], kind=kind, device=device, inode=inode)

    def _listener(self) -> object:
        state_type = getattr(installer_module, "ListenerState", None)
        if state_type is None:
            raise AssertionError("ListenerState is missing")
        return state_type(approval=self.listener_approval, bound=self.listener_bound)

    def _process(self) -> object:
        state_type = getattr(installer_module, "AppProcessState", None)
        if state_type is None:
            raise AssertionError("AppProcessState is missing")
        return state_type(
            pids=self.pids,
            resumed_component=self.resumed_component,
            focused_component=self.focused_component,
            screen_interactive=self.screen_interactive,
            device_locked=self.device_locked,
            active_services=self.active_services,
            running_reason=self.running_reason if self.pids else "stopped",
        )

    def _footprint(self, path: str) -> object:
        footprint_type = getattr(installer_module, "StorageFootprint", None)
        if footprint_type is None:
            raise AssertionError("StorageFootprint is missing")
        logical, allocated = self.footprints.get(path, (0, 0))
        return footprint_type(logical_bytes=logical, allocated_bytes=allocated)

    def add_entry(self, path: str, *, kind: str = "directory", classification: str = "unknown") -> None:
        self.next_inode += 1
        self.entries[path] = (kind, 1, self.next_inode)
        self.classifications[path] = classification

    def seed_package(self, path: str, marker: bytes = b"old-manifest") -> None:
        self.add_entry(path, classification="previous-active-package")
        self.files[f"{path}/manifest.json"] = marker

    def prepare_evidence_directory(self, _: Path) -> None:
        self.events.append("prepare-evidence")

    def require_single_ready_device(self) -> str:
        self.events.append("select-device")
        return self.serial

    def capture_prestate(self, evidence_directory: Path, final_package_path: str) -> DevicePrestate:
        backup = evidence_directory / "installed-prestate.apk"
        backup.write_bytes(self.installed_apk)
        kwargs: dict[str, object] = {
            "serial": self.serial,
            "apk_backup_path": backup,
            "apk_bytes": len(self.installed_apk),
            "apk_sha256": _sha256(self.installed_apk),
            "preferences": self.preferences,
            "app_was_running": bool(self.pids),
            "final_package_was_present": final_package_path in self.entries,
        }
        fields = getattr(DevicePrestate, "__dataclass_fields__", {})
        if "preferences_present" in fields:
            kwargs = {
                "serial": self.serial,
                "apk_backup_path": backup,
                "apk_bytes": len(self.installed_apk),
                "apk_sha256": _sha256(self.installed_apk),
                "preferences_present": self.preferences_present,
                "preferences": self.preferences,
                "app_was_running": bool(self.pids),
                "final_package_was_present": final_package_path in self.entries,
                "listener_state": self._listener(),
                "process_state": self._process(),
                "reference_inventory": self.immediate_inventory(
                    ReferencePackageInstaller.REFERENCE_ROOT
                ),
                "final_package_entry": (
                    self._entry(final_package_path)
                    if final_package_path in self.entries
                    else None
                ),
            }
        return DevicePrestate(**kwargs)

    def available_bytes(self, _: str) -> int:
        return self.available

    def path_exists(self, path: str) -> bool:
        if path in self.probe_failures:
            raise ReferencePackageInstallError(f"injected path probe failure: {path}")
        return path in self.entries or path in self.files

    def entry_identity(self, path: str) -> object | None:
        if path in self.probe_failures:
            raise ReferencePackageInstallError(f"injected identity probe failure: {path}")
        return self._entry(path) if path in self.entries else None

    def immediate_inventory(self, root: str) -> tuple[object, ...]:
        prefix = root + "/"
        result = []
        for path in sorted(self.entries):
            suffix = path[len(prefix) :] if path.startswith(prefix) else ""
            if suffix and "/" not in suffix:
                result.append(self._entry(path))
        for path in sorted(self.files):
            suffix = path[len(prefix) :] if path.startswith(prefix) else ""
            if suffix and "/" not in suffix:
                result.append(
                    installer_module.RemoteEntryIdentity(
                        name=suffix,
                        kind="regular",
                        device=1,
                        inode=1_000_000
                        + sum(
                            (index + 1) * ord(character)
                            for index, character in enumerate(path)
                        ),
                    )
                )
        result.sort(key=lambda entry: entry.name)
        return tuple(result)

    def classify_reference_entry(self, root: str, entry: object) -> str:
        return self.classifications.get(f"{root}/{entry.name}", "unknown")

    def make_directory(self, path: str) -> None:
        self.events.append(f"mutate:mkdir:{path}")
        if path not in self.entries:
            self.add_entry(path)

    def make_directory_no_replace(self, path: str) -> object:
        self.events.append(f"mutate:mkdir-no-replace:{path}")
        if path in self.entries:
            raise ReferencePackageInstallError("fake no-replace directory exists")
        self.add_entry(path)
        return self._entry(path)

    def push_file(self, local: Path, remote: str) -> None:
        self.events.append(f"mutate:push:{Path(remote).name}")
        if self.fail_operation == f"push:{Path(remote).name}":
            raise ReferencePackageInstallError("injected push failure")
        self.files[remote] = local.read_bytes()

    def remote_file_identity(self, path: str) -> RemoteFileIdentity:
        raw = self.files[path]
        sha = _sha256(raw)
        if self.identity_mismatch_path == path:
            sha = "0" * 64
        return RemoteFileIdentity(byte_length=len(raw), sha256=sha)

    def listener_state(self) -> object:
        return self._listener()

    def disallow_listener(self) -> None:
        self.events.append("mutate:disallow-listener")
        component = (
            "com.flightalert/com.flightalert.alerts.MonitoringNotificationHiderService"
        )
        self.listener_approval = ":".join(
            item for item in self.listener_approval.split(":") if item != component
        )
        self.listener_bound = False

    def app_process_state(self) -> object:
        return self._process()

    def force_stop(self) -> None:
        self.events.append("mutate:force-stop")
        self.pids = ()
        self.resumed_component = None
        self.focused_component = None
        self.active_services = ()
        self.running_reason = "stopped"

    def move_no_replace(self, source: str, destination: str) -> None:
        self.events.append(f"mutate:move:{source}->{destination}")
        if self.fail_operation == "move-before-effect":
            raise ReferencePackageInstallError("injected move failure")
        if source not in self.entries or destination in self.entries:
            raise ReferencePackageInstallError("move precondition differs")
        self.entries[destination] = self.entries.pop(source)
        self.classifications[destination] = self.classifications.pop(source, "unknown")
        moved = {
            destination + path[len(source) :]: raw
            for path, raw in self.files.items()
            if path.startswith(source + "/")
        }
        self.files = {
            path: raw
            for path, raw in self.files.items()
            if not path.startswith(source + "/")
        }
        self.files.update(moved)
        if self.post_effect_error_source == source:
            self.post_effect_error_source = None
            raise ReferencePackageInstallError("injected post-effect move timeout")

    def install_apk(self, path: Path, **_: object) -> None:
        self.events.append("mutate:install-apk")
        self.installed_apk = path.read_bytes()
        if self.fail_operation == "install-apk-after-effect":
            if self.recovery_probe_failure_path is not None:
                self.probe_failures.add(self.recovery_probe_failure_path)
            raise ReferencePackageInstallError("injected install failure after upgrade")

    def restore_preferences(self, present: object, raw: bytes | None = None) -> None:
        self.events.append("mutate:restore-preferences")
        if self.simulate_inner_preference_commands:
            guard = self.mutation_guard
            if not callable(guard):
                raise ReferencePackageInstallError("missing fake mutation guard")
            guard()
            self.events.append("mutate:restore-preferences-inner-1")
            callback = self.after_first_inner_preference_command
            self.after_first_inner_preference_command = None
            if callable(callback):
                callback()
            guard()
            self.events.append("mutate:restore-preferences-inner-2")
        if raw is None:
            self.preferences_present = True
            self.preferences = present  # type: ignore[assignment]
        else:
            self.preferences_present = bool(present)
            self.preferences = raw if self.preferences_present else b""

    def preference_state(self) -> tuple[bool, bytes]:
        return self.preferences_present, self.preferences

    def restore_listener(self, state: object) -> None:
        self.events.append("mutate:restore-listener")
        self.listener_approval = state.approval
        self.listener_bound = state.bound

    def restore_process_state(self, state: object) -> None:
        self.events.append("mutate:restore-process")
        self.pids = state.pids
        self.resumed_component = state.resumed_component
        self.focused_component = state.focused_component
        self.screen_interactive = state.screen_interactive
        self.device_locked = state.device_locked
        self.active_services = state.active_services
        self.running_reason = state.running_reason

    def restore_apk(self, path: Path) -> None:
        self.events.append("mutate:restore-apk-downgrade")
        self.restore_apk_used_downgrade = True
        self.installed_apk = path.read_bytes()

    def remove_tree(self, path: str) -> None:
        self.events.append(f"mutate:remove:{path}")
        self.entries.pop(path, None)
        self.classifications.pop(path, None)
        self.files = {
            item: raw
            for item, raw in self.files.items()
            if not item.startswith(path + "/")
        }

    def remove_bound_entry(self, path: str, expected: object) -> None:
        actual = self.entry_identity(path)
        if actual != expected:
            raise ReferencePackageInstallError("bound removal identity differs")
        self.remove_tree(path)

    def verify_installed_apk(self) -> RemoteFileIdentity:
        sha = _sha256(self.installed_apk)
        if self.fail_operation == "verify-installed-apk":
            sha = "0" * 64
        return RemoteFileIdentity(len(self.installed_apk), sha)

    def storage_footprint(self, path: str, *, private: bool = False) -> object:
        del private
        return self._footprint(path)


class DurableTransactionTest(unittest.TestCase):
    def setUp(self) -> None:
        for name in (
            "RemoteEntryIdentity",
            "ListenerState",
            "AppProcessState",
            "StorageFootprint",
        ):
            self.assertTrue(hasattr(installer_module, name), f"{name} is missing")
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
        self.events: list[str] = []
        self.lease = _StatefulLease(self.events)
        self.device = _StatefulDevice(self.root, self.events)
        self.final = ReferencePackageInstaller.FINAL_PACKAGE_PATH

    def installer(self, **arguments: object) -> ReferencePackageInstaller:
        return ReferencePackageInstaller(
            plan=self.plan,
            device=self.device,
            lease=self.lease,
            evidence_directory=self.evidence,
            **arguments,
        )

    def test_each_lease_acquisition_has_a_unique_invocation_identity(self) -> None:
        first = self.installer()._lease_arguments("install")
        second = self.installer()._lease_arguments("install")

        self.assertRegex(str(first.get("invocation_id")), r"^[0-9a-f]{32}$")
        self.assertRegex(str(second.get("invocation_id")), r"^[0-9a-f]{32}$")
        self.assertNotEqual(first["invocation_id"], second["invocation_id"])

    def test_evidence_invocation_lock_rejects_a_second_live_owner(self) -> None:
        lock_type = getattr(installer_module, "_EvidenceInvocationLock", None)
        self.assertIsNotNone(lock_type, "_EvidenceInvocationLock is missing")
        lock_path = self.root / "evidence.invocation.lock"

        with lock_type(lock_path):
            with self.assertRaisesRegex(
                ReferencePackageInstallError, "already active"
            ):
                with lock_type(lock_path):
                    self.fail("second live invocation acquired the same host lock")

    def test_install_holds_the_evidence_invocation_lock_for_the_whole_operation(self) -> None:
        lock = mock.MagicMock()
        transaction = self.installer()
        expected_path = transaction._invocation_lock_path()

        with mock.patch.object(
            installer_module,
            "_EvidenceInvocationLock",
            return_value=lock,
            create=True,
        ) as lock_type:
            transaction.install()

        lock_type.assert_called_once_with(expected_path)
        lock.__enter__.assert_called_once_with()
        lock.__exit__.assert_called_once()

    def test_invocation_lock_preserves_nested_evidence_parent_creation(self) -> None:
        nested_evidence = self.root / "new" / "nested" / "evidence"
        transaction = ReferencePackageInstaller(
            plan=self.plan,
            device=self.device,
            lease=self.lease,
            evidence_directory=nested_evidence,
        )

        transaction.install()

        self.assertTrue((nested_evidence / "pending-acceptance.json").is_file())

    def test_nested_evidence_lock_identity_does_not_change_when_parent_appears(
        self,
    ) -> None:
        nested_evidence = self.root / "new" / "nested" / "evidence"
        transaction = ReferencePackageInstaller(
            plan=self.plan,
            device=self.device,
            lease=self.lease,
            evidence_directory=nested_evidence,
        )
        first_path = transaction._invocation_lock_path()

        with installer_module._EvidenceInvocationLock(first_path):
            nested_evidence.parent.mkdir(parents=True)
            second_path = transaction._invocation_lock_path()

            self.assertEqual(first_path, second_path)
            with self.assertRaisesRegex(
                ReferencePackageInstallError, "already active"
            ):
                with installer_module._EvidenceInvocationLock(second_path):
                    self.fail("second live invocation acquired a changed host lock")

    def test_install_ends_pending_acceptance_with_exact_backup_and_durable_journal(self) -> None:
        self.device.seed_package(self.final)
        old_entry = self.device.entry_identity(self.final)
        old_apk = self.device.installed_apk
        old_preferences = self.device.preferences
        old_listener = self.device.listener_state()
        old_process = self.device.app_process_state()

        self.installer().install()

        pending = json.loads((self.evidence / "pending-acceptance.json").read_text("utf-8"))
        journal = json.loads((self.evidence / "transaction-journal.json").read_text("utf-8"))
        backup = journal["paths"]["backup"]
        self.assertEqual("pending-acceptance", pending["state"])
        self.assertEqual("pending-acceptance", journal["state"])
        self.assertIsNone(journal["activeLeaseToken"])
        self.assertEqual(
            journal["prestate"]["referenceInventory"],
            pending["referenceRootInventoryBefore"],
        )
        self.assertEqual(old_entry.inode, self.device.entry_identity(backup).inode)
        self.assertEqual(self.fixture.apk.read_bytes(), self.device.installed_apk)
        self.assertNotEqual(old_apk, self.device.installed_apk)
        self.assertEqual(old_preferences, self.device.preferences)
        self.assertEqual(old_listener, self.device.listener_state())
        self.assertEqual(old_process, self.device.app_process_state())
        self.assertEqual(1, self.lease.release_count)
        self.assertLess(
            self.events.index("mutate:disallow-listener"),
            self.events.index("mutate:force-stop"),
        )
        first_push = next(i for i, event in enumerate(self.events) if event.startswith("mutate:push:"))
        self.assertLess(self.events.index("mutate:force-stop"), first_push)

    def test_disabling_this_listener_preserves_other_approval_bytes(self) -> None:
        own = "com.flightalert/com.flightalert.alerts.MonitoringNotificationHiderService"
        self.device.listener_approval = f"other.package/Listener:{own}"
        expected = self.device.listener_state()

        self.installer().install()

        self.assertEqual(expected, self.device.listener_state())

    def test_finalize_rejects_extra_file_added_inside_active_package(self) -> None:
        self.installer().install()
        self.device.files[f"{self.final}/late-source.pbf"] = b"unexpected"

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "exact six-file inventory"
        ):
            self.installer().finalize_acceptance()

        self.assertFalse((self.evidence / "final-success.json").exists())

    def test_finalize_rejects_active_package_file_type_substitution(self) -> None:
        self.installer().install()
        manifest = f"{self.final}/manifest.json"
        self.device.files.pop(manifest)
        self.device.add_entry(manifest, kind="directory")

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "regular files"
        ):
            self.installer().finalize_acceptance()

        self.assertFalse((self.evidence / "final-success.json").exists())

    def test_pending_receipt_republication_requires_rollback_backup(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        backup = journal["paths"]["backup"]
        self.device.remove_tree(backup)
        (self.evidence / "pending-acceptance.json").unlink()

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "rollback backup"
        ):
            self.installer().install()

    def test_pending_receipt_republication_rejects_changed_backup_identity(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        backup = journal["paths"]["backup"]
        self.device.entries.pop(backup)
        self.device.add_entry(backup, classification="previous-active-package")
        (self.evidence / "pending-acceptance.json").unlink()

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "rollback backup"
        ):
            self.installer().install()

    def test_pending_receipt_republication_rejects_changed_backup_inventory(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        backup = journal["paths"]["backup"]
        self.device.files[f"{backup}/unexpected.bin"] = b"mutated"
        (self.evidence / "pending-acceptance.json").unlink()

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "rollback backup"
        ):
            self.installer().install()

    def test_finalize_requires_declared_rollback_backup_before_commit(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        self.device.remove_tree(journal["paths"]["backup"])

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "rollback backup"
        ):
            self.installer().finalize_acceptance()

        self.assertFalse((self.evidence / "final-success.json").exists())

    def test_package_inventory_is_rechecked_after_quiescence_before_first_push(self) -> None:
        def mutate_source(phase: str) -> None:
            if phase == "after-force-stop":
                (self.fixture.package / "late-race.bin").write_bytes(b"raced")

        with self.assertRaisesRegex(ReferencePackageInstallError, "inventory changed"):
            self.installer(phase_hook=mutate_source).install()

        self.assertFalse(any(event.startswith("mutate:push:") for event in self.events))

    def test_apk_replacement_after_host_validation_is_never_installed(self) -> None:
        original_stat = self.fixture.apk.stat(follow_symlinks=False)
        replacement = b"R" * original_stat.st_size
        self.assertEqual(self.fixture.apk.stat().st_size, len(replacement))

        def replace_apk(phase: str) -> None:
            if phase == "before-apk-install":
                self.fixture.apk.write_bytes(replacement)
                os.utime(
                    self.fixture.apk,
                    ns=(original_stat.st_atime_ns, original_stat.st_mtime_ns),
                )

        with self.assertRaisesRegex(ReferencePackageInstallError, "APK changed"):
            self.installer(phase_hook=replace_apk).install()

        self.assertNotIn("mutate:install-apk", self.events)
        self.assertEqual(b"old-apk", self.device.installed_apk)

    def test_unrestorable_background_only_prestate_fails_before_device_mutation(self) -> None:
        self.device.listener_approval = ""
        self.device.listener_bound = False
        self.device.pids = ("4242",)
        self.device.resumed_component = None
        self.device.focused_component = None
        self.device.active_services = ()
        self.device.running_reason = "background-only"

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "background-only"
        ):
            self.installer().install()

        self.assertFalse(any(event.startswith("mutate:") for event in self.events))

    def test_post_effect_old_move_error_restores_only_old_package(self) -> None:
        self.device.seed_package(self.final)
        old_entry = self.device.entry_identity(self.final)
        self.device.post_effect_error_source = self.final

        with self.assertRaisesRegex(ReferencePackageInstallError, "post-effect"):
            self.installer().install()

        self.assertEqual(old_entry.inode, self.device.entry_identity(self.final).inode)
        self.assertEqual(b"old-apk", self.device.installed_apk)
        self.assertGreaterEqual(self.lease.acquire_count, 2)
        self.assertEqual(self.lease.acquire_count, self.lease.release_count)

    def test_post_effect_publish_error_restores_old_package(self) -> None:
        self.device.seed_package(self.final)
        old_entry = self.device.entry_identity(self.final)
        token = f"{1:032x}"
        stage = f"{ReferencePackageInstaller.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.stage"
        self.device.post_effect_error_source = stage

        with self.assertRaisesRegex(ReferencePackageInstallError, "post-effect"):
            self.installer().install()

        self.assertEqual(old_entry.inode, self.device.entry_identity(self.final).inode)
        self.assertEqual(b"old-apk", self.device.installed_apk)

    def test_restart_recovers_crash_after_stage_mkdir_before_identity_journal(self) -> None:
        original_make_directory = self.device.make_directory_no_replace
        crashed = False

        def crash_after_stage_effect(path: str) -> None:
            nonlocal crashed
            original_make_directory(path)
            if path.endswith(".stage") and not crashed:
                crashed = True
                raise _ProcessKilled("after stage mkdir effect")

        with mock.patch.object(
            self.device,
            "make_directory_no_replace",
            side_effect=crash_after_stage_effect,
        ):
            with self.assertRaises(_ProcessKilled):
                self.installer().install()

        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        stage = journal["paths"]["stage"]
        self.assertIsNone(journal.get("newPackageEntry"))
        self.assertIsNotNone(self.device.entry_identity(stage))

        self.installer().install()

        self.assertTrue((self.evidence / "pending-acceptance.json").is_file())
        self.assertIsNone(self.device.entry_identity(stage))

    def test_recovery_never_deletes_ambiguous_nonempty_unbound_stage(self) -> None:
        original_make_directory = self.device.make_directory_no_replace
        crashed = False

        def crash_after_stage_effect(path: str) -> None:
            nonlocal crashed
            original_make_directory(path)
            if path.endswith(".stage") and not crashed:
                crashed = True
                raise _ProcessKilled("after stage mkdir effect")

        with mock.patch.object(
            self.device,
            "make_directory_no_replace",
            side_effect=crash_after_stage_effect,
        ):
            with self.assertRaises(_ProcessKilled):
                self.installer().install()

        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        stage = journal["paths"]["stage"]
        self.device.files[f"{stage}/unbound.bin"] = b"ambiguous"

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "not empty"
        ):
            self.installer().install()

        self.assertIsNotNone(self.device.entry_identity(stage))
        self.assertIn(f"{stage}/unbound.bin", self.device.files)

    def test_recovery_probe_failure_does_not_skip_app_restore_or_lease_release(self) -> None:
        self.device.seed_package(self.final)
        self.device.fail_operation = "install-apk-after-effect"
        token = f"{1:032x}"
        stage = f"{ReferencePackageInstaller.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.stage"
        self.device.recovery_probe_failure_path = stage

        with self.assertRaises(ReferencePackageInstallError):
            self.installer().install()

        self.assertIn("mutate:restore-apk-downgrade", self.events)
        self.assertIn("mutate:restore-preferences", self.events)
        self.assertIn("mutate:restore-listener", self.events)
        self.assertIn("mutate:restore-process", self.events)
        self.assertEqual(self.lease.acquire_count, self.lease.release_count)
        self.assertTrue((self.evidence / "transaction-journal.json").exists())

    def test_first_run_without_preferences_is_preserved(self) -> None:
        self.device.preferences_present = False
        self.device.preferences = b""

        self.installer().install()

        self.assertFalse(self.device.preferences_present)
        self.assertEqual(b"", self.device.preferences)

    def test_upgrade_then_failure_uses_downgrade_capable_apk_rollback(self) -> None:
        self.device.seed_package(self.final)
        self.device.fail_operation = "install-apk-after-effect"

        with self.assertRaisesRegex(ReferencePackageInstallError, "upgrade"):
            self.installer().install()

        self.assertTrue(self.device.restore_apk_used_downgrade)
        self.assertEqual(b"old-apk", self.device.installed_apk)

    def test_finalize_removes_only_bound_residue_and_publishes_actual_footprint(self) -> None:
        self.device.seed_package(self.final)
        source = f"{ReferencePackageInstaller.REFERENCE_ROOT}/planet-latest.osm.pbf"
        self.device.add_entry(source, kind="regular", classification="source-pbf")
        self.device.footprints[self.final] = (100, 120)
        self.device.footprints["installed-apk"] = (20, 16)
        self.device.footprints["cache"] = (2, 4)
        self.device.footprints["code_cache"] = (3, 1)
        self.installer().install()

        finalize = getattr(self.installer(), "finalize_acceptance", None)
        self.assertIsNotNone(finalize, "finalize_acceptance is missing")
        finalize()

        receipt = json.loads((self.evidence / "final-success.json").read_text("utf-8"))
        self.assertEqual("accepted", receipt["state"])
        self.assertEqual(147, receipt["footprint"]["countedBytes"])
        self.assertTrue(receipt["footprint"]["preferredStrictlyBelow"])
        names = [entry.name for entry in self.device.immediate_inventory(ReferencePackageInstaller.REFERENCE_ROOT)]
        self.assertEqual([PACKAGE_ID], names)
        self.assertEqual([PACKAGE_ID], [item["name"] for item in receipt["referenceRootInventoryFinal"]])

    def test_finalize_hard_ceiling_is_strict_and_keeps_rollback_backup(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        journal = json.loads((self.evidence / "transaction-journal.json").read_text("utf-8"))
        backup = journal["paths"]["backup"]
        self.device.footprints[self.final] = (40_000_000_000, 1)

        finalize = getattr(self.installer(), "finalize_acceptance", None)
        self.assertIsNotNone(finalize, "finalize_acceptance is missing")
        with self.assertRaisesRegex(ReferencePackageInstallError, "40,000,000,000"):
            finalize()

        self.assertIsNotNone(self.device.entry_identity(backup))
        self.assertFalse((self.evidence / "final-success.json").exists())

    def test_finalize_receipt_failure_is_retryable_after_committed_cleanup(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        original = installer_module._atomic_write_json
        failed = False

        def fail_once(path: Path, document: object) -> None:
            nonlocal failed
            if path.name == "final-success.json" and not failed:
                failed = True
                raise ReferencePackageInstallError("injected final receipt failure")
            original(path, document)

        with mock.patch.object(installer_module, "_atomic_write_json", side_effect=fail_once):
            with self.assertRaisesRegex(ReferencePackageInstallError, "receipt failure"):
                self.installer().finalize_acceptance()

        self.installer().finalize_acceptance()

        self.assertEqual(
            "accepted",
            json.loads((self.evidence / "final-success.json").read_text("utf-8"))["state"],
        )

    def test_finalize_refuses_unidentified_residue_without_deleting_it(self) -> None:
        self.device.seed_package(self.final)
        unknown = f"{ReferencePackageInstaller.REFERENCE_ROOT}/do-not-touch"
        self.device.add_entry(unknown, classification="unknown")
        self.installer().install()

        finalize = getattr(self.installer(), "finalize_acceptance", None)
        self.assertIsNotNone(finalize, "finalize_acceptance is missing")
        with self.assertRaisesRegex(ReferencePackageInstallError, "unidentified"):
            finalize()

        self.assertIsNotNone(self.device.entry_identity(unknown))

    def test_rollback_restores_package_apk_preferences_listener_focus_and_process(self) -> None:
        self.device.seed_package(self.final)
        old_entry = self.device.entry_identity(self.final)
        old_state = (
            self.device.installed_apk,
            self.device.preferences_present,
            self.device.preferences,
            self.device.listener_state(),
            self.device.app_process_state(),
        )
        self.installer().install()

        rollback = getattr(self.installer(), "rollback", None)
        self.assertIsNotNone(rollback, "rollback is missing")
        rollback()

        self.assertEqual(old_entry.inode, self.device.entry_identity(self.final).inode)
        self.assertEqual(old_state[0], self.device.installed_apk)
        self.assertEqual(old_state[1], self.device.preferences_present)
        self.assertEqual(old_state[2], self.device.preferences)
        self.assertEqual(old_state[3], self.device.listener_state())
        self.assertEqual(old_state[4], self.device.app_process_state())
        receipt = json.loads((self.evidence / "rollback-receipt.json").read_text("utf-8"))
        self.assertEqual("rolled-back", receipt["state"])

    def test_rollback_receipt_failure_is_retryable_after_verified_restore(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        original = installer_module._atomic_write_json
        failed = False

        def fail_once(path: Path, document: object) -> None:
            nonlocal failed
            if path.name == "rollback-receipt.json" and not failed:
                failed = True
                raise ReferencePackageInstallError("injected rollback receipt failure")
            original(path, document)

        with mock.patch.object(installer_module, "_atomic_write_json", side_effect=fail_once):
            with self.assertRaisesRegex(ReferencePackageInstallError, "receipt failure"):
                self.installer().rollback()

        self.installer().rollback()

        self.assertEqual(
            "rolled-back",
            json.loads((self.evidence / "rollback-receipt.json").read_text("utf-8"))["state"],
        )

    def test_rollback_crash_after_recovery_remains_retryable_as_rollback(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        killed = False

        def crash_after_recovery(phase: str) -> None:
            nonlocal killed
            if phase == "recovery-complete" and not killed:
                killed = True
                raise _ProcessKilled(phase)

        with self.assertRaises(_ProcessKilled):
            self.installer(phase_hook=crash_after_recovery).rollback()

        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        self.assertEqual("rollback-in-progress", journal["state"])

        self.installer().rollback()

        self.assertEqual(
            "rolled-back",
            json.loads((self.evidence / "rollback-receipt.json").read_text("utf-8"))["state"],
        )

    def test_execute_cannot_redirect_a_rollback_in_progress_journal(self) -> None:
        self.device.seed_package(self.final)
        self.installer().install()
        journal_path = self.evidence / "transaction-journal.json"
        journal = json.loads(journal_path.read_text("utf-8"))
        journal["state"] = "rollback-in-progress"
        journal_path.write_bytes(_canonical(journal))

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "rollback.*in progress"
        ):
            self.installer().install()

    def test_restart_reconciles_each_irreversible_phase_before_reinstall(self) -> None:
        phases = (
            "after-listener-disallow",
            "after-force-stop",
            "stage-created",
            "pushed-records.fadictpack",
            "pushed-manifest.json",
            "before-old-move",
            "after-old-move",
            "before-new-publish",
            "after-new-publish",
            "before-apk-install",
            "after-apk-install",
            "after-prestate-restored",
        )
        for phase in phases:
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                fixture = _ValidInstallFixture(root)
                plan = HostInstallPlan.validate(
                    package_directory=fixture.package,
                    apk_path=fixture.apk,
                    final_result_path=fixture.result,
                )
                evidence = root / "evidence"
                events: list[str] = []
                lease = _StatefulLease(events)
                device = _StatefulDevice(root, events)
                device.seed_package(self.final)
                killed = False

                def hook(actual: str) -> None:
                    nonlocal killed
                    if actual == phase and not killed:
                        killed = True
                        raise _ProcessKilled(phase)

                first = ReferencePackageInstaller(
                    plan=plan,
                    device=device,
                    lease=lease,
                    evidence_directory=evidence,
                    phase_hook=hook,
                )
                with self.assertRaises(_ProcessKilled):
                    first.install()
                ReferencePackageInstaller(
                    plan=plan,
                    device=device,
                    lease=lease,
                    evidence_directory=evidence,
                ).install()
                pending = json.loads((evidence / "pending-acceptance.json").read_text("utf-8"))
                self.assertEqual("pending-acceptance", pending["state"])

    def test_restart_reconciles_journal_bound_stale_lease_before_fresh_acquire(self) -> None:
        self.device.seed_package(self.final)

        def kill_after_move(phase: str) -> None:
            if phase == "after-old-move":
                raise _ProcessKilled(phase)

        with self.assertRaises(_ProcessKilled):
            self.installer(phase_hook=kill_after_move).install()
        journal_path = self.evidence / "transaction-journal.json"
        journal = json.loads(journal_path.read_text("utf-8"))
        stale_token = "f" * 32
        journal["activeLeaseToken"] = stale_token
        journal["leaseReleased"] = False
        journal_path.write_bytes(_canonical(journal))
        self.lease.active_token = stale_token

        self.installer().install()

        reconcile = self.events.index(f"lease-reconcile:{stale_token}")
        acquire = self.events.index("lease-acquire:2")
        self.assertLess(reconcile, acquire)

    def test_first_heartbeat_transport_failure_retains_confirmed_ownership(self) -> None:
        self.lease.fail_first_heartbeat_transport = True

        self.installer().install()

        self.assertIn("lease-confirm-intent", self.events)
        self.assertEqual(1, self.lease.acquire_count)
        self.assertEqual(1, self.lease.release_count)

    def test_restart_recovers_interruption_between_acquire_and_token_journal(self) -> None:
        self.lease.interrupt_after_acquire = True

        with self.assertRaises(_ProcessKilled):
            self.installer().install()

        journal = json.loads(
            (self.evidence / "transaction-journal.json").read_text("utf-8")
        )
        self.assertIsNotNone(journal["leaseAcquisitionIntent"])
        self.assertIsNone(journal["activeLeaseToken"])
        self.assertIsNotNone(self.lease.active_token)

        self.installer().install()

        reconcile = self.events.index("lease-reconcile-intent")
        second_acquire = self.events.index("lease-acquire:2")
        self.assertLess(reconcile, second_acquire)
        self.assertEqual(self.lease.acquire_count, self.lease.release_count + 1)

    def test_lease_loss_stops_mutation_until_new_recovery_lease(self) -> None:
        self.device.seed_package(self.final)
        self.lease.fail_heartbeat_at = 19

        with self.assertRaisesRegex(ReferencePackageInstallError, "lease loss"):
            self.installer().install()

        second_acquire = self.events.index("lease-acquire:2")
        first_release = self.events.index("lease-release:1")
        self.assertLess(first_release, second_acquire)
        between = self.events[first_release + 1 : second_acquire]
        self.assertFalse(any(event.startswith("mutate:") for event in between))

    def test_inner_command_lease_loss_waits_for_journal_recovery_lease(self) -> None:
        self.device.simulate_inner_preference_commands = True

        def lose_before_second_inner_command() -> None:
            self.lease.fail_heartbeat_at = self.lease.heartbeat_count + 1

        self.device.after_first_inner_preference_command = (
            lose_before_second_inner_command
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "lease loss"):
            self.installer().install()

        second_acquire = self.events.index("lease-acquire:2")
        first_inner = self.events.index("mutate:restore-preferences-inner-1")
        self.assertLess(first_inner, second_acquire)
        self.assertNotIn(
            "mutate:restore-preferences-inner-2",
            self.events[first_inner + 1 : second_acquire],
        )
        self.assertIn(
            "mutate:restore-preferences-inner-2", self.events[second_acquire + 1 :]
        )

    def test_stale_tokenized_path_is_not_silently_ignored(self) -> None:
        stale = (
            f"{ReferencePackageInstaller.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-"
            f"{'f' * 32}.stage"
        )
        self.device.add_entry(stale)

        with self.assertRaisesRegex(ReferencePackageInstallError, "stale"):
            self.installer().install()

        self.assertIsNotNone(self.device.entry_identity(stale))


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
    def test_stage_creation_uses_no_replace_mkdir_and_returns_exact_identity(
        self,
    ) -> None:
        path = "/storage/emulated/0/reference/.stage"
        expected_command = (
            "adb.exe",
            "-s",
            "SERIAL",
            "shell",
            "mkdir",
            "--",
            path,
        )
        runner = _ScriptedRunner(
            [(expected_command, CommandResult(0, b"", b""))]
        )
        device = AdbInstallDevice(adb="adb.exe", runner=runner)
        device.serial = "SERIAL"
        device.set_mutation_guard(lambda: None)
        identity = installer_module.RemoteEntryIdentity(
            name=".stage", kind="directory", device=1, inode=2
        )

        with mock.patch.object(device, "path_exists", return_value=False), mock.patch.object(
            device, "entry_identity", return_value=identity
        ):
            actual = device.make_directory_no_replace(path)

        self.assertEqual(identity, actual)
        self.assertEqual([expected_command], runner.calls)

    def test_atomic_lease_intent_binds_invocation_id_into_helper_identity(self) -> None:
        lease = AtomicDeviceLeaseClient(
            helper_path=Path("C:/coordination/device-lease.ps1"),
            thread_id="thread",
            runner=_ScriptedRunner([]),
            heartbeat_interval_seconds=3600.0,
        )
        nonce = "1" * 32

        intent = lease.acquisition_intent(
            owner="owner",
            purpose="purpose",
            scenario="scenario",
            stateful_effects="effects",
            expected_minutes=1,
            invocation_id=nonce,
        )

        self.assertEqual(nonce, intent["invocationId"])
        self.assertEqual(f"scenario [invocation:{nonce}]", intent["scenario"])

    @staticmethod
    def _process_restore_device(
        steps: list[tuple[tuple[str, ...], CommandResult]],
    ) -> tuple[AdbInstallDevice, _ScriptedRunner]:
        runner = _ScriptedRunner(steps)
        device = AdbInstallDevice(adb="adb.exe", runner=runner)
        device.serial = "SERIAL"
        device.set_mutation_guard(lambda: None)
        return device, runner

    def test_restore_background_app_does_not_fabricate_an_activity_or_focus(self) -> None:
        listener = "com.flightalert/.alerts.MonitoringNotificationHiderService"
        state = installer_module.AppProcessState(
            pids=("42",),
            resumed_component=None,
            focused_component=None,
            screen_interactive=True,
            device_locked=False,
            active_services=(listener,),
            running_reason="bound-notification-listener",
        )
        device, runner = self._process_restore_device([])

        with mock.patch.object(device, "app_process_state", return_value=state):
            device.restore_process_state(state)

        self.assertEqual([], runner.calls)

    def test_restore_screen_off_locked_app_starts_only_its_required_service(self) -> None:
        service = "com.flightalert/.alerts.AircraftAlertService"
        start_service = (
            "adb.exe",
            "-s",
            "SERIAL",
            "shell",
            "am",
            "start-foreground-service",
            "-n",
            service,
        )
        state = installer_module.AppProcessState(
            pids=("43",),
            resumed_component=None,
            focused_component=None,
            screen_interactive=False,
            device_locked=True,
            active_services=(service,),
            running_reason="service",
        )
        device, runner = self._process_restore_device(
            [(start_service, CommandResult(0, b"Starting service\n", b""))]
        )

        with mock.patch.object(device, "app_process_state", return_value=state):
            device.restore_process_state(state)

        self.assertEqual([start_service], runner.calls)
        self.assertNotIn("start", start_service)

    def test_restore_foreground_app_starts_only_captured_flight_alert_activity(self) -> None:
        activity = "com.flightalert/.MainActivity"
        start = (
            "adb.exe",
            "-s",
            "SERIAL",
            "shell",
            "am",
            "start",
            "-n",
            activity,
        )
        state = installer_module.AppProcessState(
            pids=("44",),
            resumed_component=activity,
            focused_component=activity,
            screen_interactive=True,
            device_locked=False,
            active_services=(),
            running_reason="foreground-activity",
        )
        device, runner = self._process_restore_device(
            [(start, CommandResult(0, b"Starting: Intent\n", b""))]
        )

        with mock.patch.object(device, "app_process_state", return_value=state):
            device.restore_process_state(state)

        self.assertEqual([start], runner.calls)

    def test_restore_other_app_focus_never_launches_the_third_party_component(self) -> None:
        other = "com.example.other/.OtherActivity"
        listener = "com.flightalert/.alerts.MonitoringNotificationHiderService"
        state = installer_module.AppProcessState(
            pids=("45",),
            resumed_component=other,
            focused_component=other,
            screen_interactive=True,
            device_locked=False,
            active_services=(listener,),
            running_reason="bound-notification-listener",
        )
        device, runner = self._process_restore_device([])

        with mock.patch.object(device, "app_process_state", return_value=state):
            device.restore_process_state(state)

        self.assertEqual([], runner.calls)

    def test_restore_unbound_background_only_state_fails_without_mutation(self) -> None:
        state = installer_module.AppProcessState(
            pids=("46",),
            resumed_component=None,
            focused_component=None,
            screen_interactive=True,
            device_locked=False,
            active_services=(),
            running_reason="background-only",
        )
        device, runner = self._process_restore_device([])

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "background-only"
        ):
            device.restore_process_state(state)

        self.assertEqual([], runner.calls)

    def test_apk_is_rehashed_after_final_lease_guard_before_command_open(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            apk.write_bytes(b"original-apk-bytes")
            information = apk.stat(follow_symlinks=False)
            replacement = b"R" * information.st_size
            runner = _ScriptedRunner([])
            device = AdbInstallDevice(adb="adb.exe", runner=runner)
            device.serial = "SERIAL"

            def replace_during_final_guard() -> None:
                apk.write_bytes(replacement)
                os.utime(
                    apk,
                    ns=(information.st_atime_ns, information.st_mtime_ns),
                )

            device.set_mutation_guard(replace_during_final_guard)
            with self.assertRaisesRegex(ReferencePackageInstallError, "APK changed"):
                device.install_apk(
                    apk,
                    expected_bytes=information.st_size,
                    expected_sha256=_sha256(b"original-apk-bytes"),
                    expected_stat_identity=installer_module._identity(information),
                )

        self.assertEqual([], runner.calls)

    def test_apk_rechecks_lease_after_hash_before_install_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "app.apk"
            raw = b"verified-apk-bytes"
            apk.write_bytes(raw)
            information = apk.stat(follow_symlinks=False)
            install = ("adb.exe", "-s", "SERIAL", "install", "-r", str(apk))
            runner = _ScriptedRunner(
                [(install, CommandResult(0, b"Success\n", b""))]
            )
            device = AdbInstallDevice(adb="adb.exe", runner=runner)
            device.serial = "SERIAL"
            guard_calls = 0

            def lose_after_hash() -> None:
                nonlocal guard_calls
                guard_calls += 1
                if guard_calls == 2:
                    raise ReferencePackageInstallError("injected lease loss")

            device.set_mutation_guard(lose_after_hash)
            with self.assertRaisesRegex(ReferencePackageInstallError, "lease loss"):
                device.install_apk(
                    apk,
                    expected_bytes=len(raw),
                    expected_sha256=_sha256(raw),
                    expected_stat_identity=installer_module._identity(information),
                )

        self.assertEqual([], runner.calls)

    def test_app_process_probe_captures_lock_service_and_other_app_focus(self) -> None:
        other = "com.example.other/.OtherActivity"
        service = "com.flightalert/.alerts.AircraftAlertService"
        prefix = ("adb.exe", "-s", "SERIAL")
        runner = _ScriptedRunner(
            [
                (
                    (*prefix, "shell", "pidof", AdbInstallDevice.APP_ID),
                    CommandResult(0, b"42\n", b""),
                ),
                (
                    (*prefix, "shell", "dumpsys", "activity", "activities"),
                    CommandResult(0, b"no resumed activity\n", b""),
                ),
                (
                    (*prefix, "shell", "dumpsys", "window", "windows"),
                    CommandResult(
                        0, f"mCurrentFocus=Window{{1 u0 {other}}}\n".encode(), b""
                    ),
                ),
                (
                    (*prefix, "shell", "dumpsys", "power"),
                    CommandResult(0, b"  mWakefulness=Asleep\n", b""),
                ),
                (
                    (*prefix, "shell", "dumpsys", "window", "policy"),
                    CommandResult(0, b"mShowingLockscreen=true\n", b""),
                ),
                (
                    (
                        *prefix,
                        "shell",
                        "dumpsys",
                        "activity",
                        "services",
                        AdbInstallDevice.APP_ID,
                    ),
                    CommandResult(
                        0,
                        f"ServiceRecord{{abc u0 {service}}}\n".encode(),
                        b"",
                    ),
                ),
            ]
        )
        device = AdbInstallDevice(adb="adb.exe", runner=runner)
        device.serial = "SERIAL"

        state = device.app_process_state()

        self.assertFalse(state.screen_interactive)
        self.assertTrue(state.device_locked)
        self.assertEqual((service,), state.active_services)
        self.assertEqual("service", state.running_reason)
        self.assertEqual(other, state.focused_component)
        self.assertEqual([], runner.steps)

    def test_each_inner_mutation_rechecks_lease_before_next_command(self) -> None:
        component = AdbInstallDevice.LISTENER_COMPONENT
        approval = f"other.package/Listener:{component}"
        allow = (
            "adb.exe",
            "-s",
            "SERIAL",
            "shell",
            "cmd",
            "notification",
            "allow_listener",
            component,
        )
        settings = (
            "adb.exe",
            "-s",
            "SERIAL",
            "shell",
            "settings",
            "put",
            "secure",
            "enabled_notification_listeners",
            approval,
        )
        success = CommandResult(0, b"", b"")
        runner = _ScriptedRunner(
            [(allow, success), (allow, success), (settings, success)]
        )
        device = AdbInstallDevice(adb="adb.exe", runner=runner)
        device.serial = "SERIAL"
        state = installer_module.ListenerState(approval=approval, bound=True)
        guard_calls = 0

        def losing_guard() -> None:
            nonlocal guard_calls
            guard_calls += 1
            if guard_calls == 2:
                raise ReferencePackageInstallError("injected lease loss")

        with mock.patch.object(device, "listener_state", return_value=state):
            device.set_mutation_guard(losing_guard)
            with self.assertRaisesRegex(ReferencePackageInstallError, "lease loss"):
                device.restore_listener(state)
            self.assertEqual([allow], runner.calls)

            device.set_mutation_guard(lambda: None)
            device.restore_listener(state)

        self.assertEqual([allow, allow, settings], runner.calls)
        self.assertEqual([], runner.steps)

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
            b"List of devices attached\nemulator-5554 device product:sdk_phone model:sdk device:generic transport_id:1\n",
        ):
            with self.subTest(raw=raw):
                with self.assertRaises(ReferencePackageInstallError):
                    parse_adb_devices(raw)

    def test_atomic_lease_client_uses_exact_json_token_and_releases(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "a" * 32
        invocation_id = "0" * 32
        scenario = f"scenario [invocation:{invocation_id}]"
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
        status = CommandResult(
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
                        scenario,
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
                (
                    (
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-File",
                        str(helper),
                        "status",
                    ),
                    status,
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
            invocation_id=invocation_id,
        )
        lease.heartbeat(actual)
        lease.release(actual)

        self.assertEqual(token, actual)
        self.assertEqual([], runner.steps)

    def test_atomic_lease_confirms_owner_thread_after_heartbeat_transport_failure(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "d" * 32
        thread_id = "thread-heartbeat"
        invocation_id = "1" * 32
        scenario = f"scenario [invocation:{invocation_id}]"
        command_prefix = (
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(helper),
        )
        lease_identity = {
            "owner": "owner",
            "purpose": "purpose",
            "scenario": scenario,
            "statefulEffects": "effects",
            "threadId": thread_id,
            "token": token,
        }
        runner = _ScriptedRunner(
            [
                (
                    (
                        *command_prefix,
                        "acquire",
                        "-Owner",
                        "owner",
                        "-ThreadId",
                        thread_id,
                        "-Purpose",
                        "purpose",
                        "-Scenario",
                        scenario,
                        "-StatefulEffects",
                        "effects",
                        "-ExpectedMinutes",
                        "180",
                    ),
                    CommandResult(0, _canonical({"held": True, "token": token}), b""),
                ),
                (
                    (*command_prefix, "heartbeat", "-Token", token),
                    CommandResult(1, b"", b"transport timeout"),
                ),
                (
                    (*command_prefix, "status"),
                    CommandResult(
                        0,
                        _canonical(
                            {"held": True, "lease": lease_identity, "token": None}
                        ),
                        b"",
                    ),
                ),
                (
                    (*command_prefix, "release", "-Token", token),
                    CommandResult(0, _canonical({"held": False, "token": ""}), b""),
                ),
                (
                    (*command_prefix, "status"),
                    CommandResult(
                        0,
                        _canonical({"held": False, "lease": None, "token": ""}),
                        b"",
                    ),
                ),
            ]
        )
        lease = AtomicDeviceLeaseClient(
            helper_path=helper,
            thread_id=thread_id,
            runner=runner,
            heartbeat_interval_seconds=3600.0,
        )
        arguments = {
            "owner": "owner",
            "purpose": "purpose",
            "scenario": "scenario",
            "stateful_effects": "effects",
            "expected_minutes": 180,
            "invocation_id": invocation_id,
        }
        acquired = lease.acquire(**arguments)

        lease.heartbeat(acquired)
        self.assertEqual(token, lease._token)
        lease.release(token)

        self.assertEqual([], runner.steps)

    def test_atomic_lease_adopts_ambiguous_acquire_only_for_exact_intent_owner(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "e" * 32
        thread_id = "thread-acquire"
        invocation_id = "2" * 32
        scenario = f"scenario [invocation:{invocation_id}]"
        prefix = (
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(helper),
        )
        identity = {
            "owner": "owner",
            "purpose": "purpose",
            "scenario": scenario,
            "statefulEffects": "effects",
            "threadId": thread_id,
            "token": token,
        }
        runner = _ScriptedRunner(
            [
                (
                    (
                        *prefix,
                        "acquire",
                        "-Owner",
                        "owner",
                        "-ThreadId",
                        thread_id,
                        "-Purpose",
                        "purpose",
                        "-Scenario",
                        scenario,
                        "-StatefulEffects",
                        "effects",
                        "-ExpectedMinutes",
                        "1",
                    ),
                    CommandResult(1, b"", b"response lost"),
                ),
                (
                    (*prefix, "status"),
                    CommandResult(
                        0,
                        _canonical({"held": True, "lease": identity, "token": None}),
                        b"",
                    ),
                ),
                (
                    (*prefix, "release", "-Token", token),
                    CommandResult(0, _canonical({"held": False, "token": ""}), b""),
                ),
                (
                    (*prefix, "status"),
                    CommandResult(
                        0,
                        _canonical({"held": False, "lease": None, "token": ""}),
                        b"",
                    ),
                ),
            ]
        )
        lease = AtomicDeviceLeaseClient(
            helper_path=helper,
            thread_id=thread_id,
            runner=runner,
            heartbeat_interval_seconds=3600.0,
        )

        acquired = lease.acquire(
            owner="owner",
            purpose="purpose",
            scenario="scenario",
            stateful_effects="effects",
            expected_minutes=1,
            invocation_id=invocation_id,
        )
        lease.release(acquired)

        self.assertEqual(token, acquired)
        self.assertEqual([], runner.steps)

    def test_atomic_lease_never_adopts_another_live_invocation(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "f" * 32
        first_id = "4" * 32
        second_id = "5" * 32
        first_scenario = f"scenario [invocation:{first_id}]"
        second_scenario = f"scenario [invocation:{second_id}]"
        prefix = (
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(helper),
        )
        runner = _ScriptedRunner(
            [
                (
                    (
                        *prefix,
                        "acquire",
                        "-Owner",
                        "owner",
                        "-ThreadId",
                        "thread",
                        "-Purpose",
                        "purpose",
                        "-Scenario",
                        second_scenario,
                        "-StatefulEffects",
                        "effects",
                        "-ExpectedMinutes",
                        "1",
                    ),
                    CommandResult(1, b"", b"already held"),
                ),
                (
                    (*prefix, "status"),
                    CommandResult(
                        0,
                        _canonical(
                            {
                                "held": True,
                                "lease": {
                                    "owner": "owner",
                                    "purpose": "purpose",
                                    "scenario": first_scenario,
                                    "statefulEffects": "effects",
                                    "threadId": "thread",
                                    "token": token,
                                },
                                "token": None,
                            }
                        ),
                        b"",
                    ),
                ),
            ]
        )
        lease = AtomicDeviceLeaseClient(
            helper_path=helper,
            thread_id="thread",
            runner=runner,
            heartbeat_interval_seconds=3600.0,
        )

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "scenario.*does not match"
        ):
            lease.acquire(
                owner="owner",
                purpose="purpose",
                scenario="scenario",
                stateful_effects="effects",
                expected_minutes=1,
                invocation_id=second_id,
            )

        self.assertIsNone(lease._token)
        self.assertEqual([], runner.steps)

    def test_atomic_lease_release_keeps_token_when_status_is_inconsistent(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "b" * 32
        invocation_id = "3" * 32
        scenario = f"scenario [invocation:{invocation_id}]"
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
                        "owner",
                        "-ThreadId",
                        "thread",
                        "-Purpose",
                        "purpose",
                        "-Scenario",
                        scenario,
                        "-StatefulEffects",
                        "effects",
                        "-ExpectedMinutes",
                        "1",
                    ),
                    CommandResult(0, _canonical({"held": True, "token": token}), b""),
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
                    CommandResult(0, _canonical({"held": False, "token": ""}), b""),
                ),
                (
                    (
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-File",
                        str(helper),
                        "status",
                    ),
                    CommandResult(0, _canonical({"held": True, "token": token}), b""),
                ),
            ]
        )
        lease = AtomicDeviceLeaseClient(
            helper_path=helper,
            thread_id="thread",
            runner=runner,
            heartbeat_interval_seconds=3600.0,
        )
        acquired = lease.acquire(
            owner="owner",
            purpose="purpose",
            scenario="scenario",
            stateful_effects="effects",
            expected_minutes=1,
            invocation_id=invocation_id,
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "status"):
            lease.release(acquired)

        self.assertEqual(token, lease._token)

    def test_atomic_lease_reconciles_only_the_exact_unfinished_token(self) -> None:
        helper = Path("C:/coordination/device-lease.ps1")
        token = "c" * 32
        command_prefix = (
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(helper),
        )
        runner = _ScriptedRunner(
            [
                (
                    (*command_prefix, "status"),
                    CommandResult(0, _canonical({"held": True, "token": token}), b""),
                ),
                (
                    (*command_prefix, "release", "-Token", token),
                    CommandResult(0, _canonical({"held": False, "token": ""}), b""),
                ),
                (
                    (*command_prefix, "status"),
                    CommandResult(0, _canonical({"held": False, "token": ""}), b""),
                ),
            ]
        )
        lease = AtomicDeviceLeaseClient(
            helper_path=helper,
            thread_id="thread",
            runner=runner,
            heartbeat_interval_seconds=3600.0,
        )
        reconcile = getattr(lease, "reconcile_unfinished", None)
        self.assertIsNotNone(reconcile, "reconcile_unfinished is missing")

        reconcile(token)

        self.assertEqual([], runner.steps)

    def test_restore_apk_uses_same_signature_downgrade_capable_command(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / "old.apk"
            apk.write_bytes(b"old-apk")
            runner = _ScriptedRunner(
                [
                    (
                        ("adb.exe", "-s", "SERIAL", "install", "-r", "-d", str(apk)),
                        CommandResult(0, b"Success\n", b""),
                    )
                ]
            )
            device = AdbInstallDevice(adb="adb.exe", runner=runner)
            device.serial = "SERIAL"
            device.set_mutation_guard(lambda: None)
            with mock.patch.object(
                device,
                "verify_installed_apk",
                return_value=RemoteFileIdentity(len(b"old-apk"), _sha256(b"old-apk")),
            ):
                device.restore_apk(apk)

        self.assertEqual([], runner.steps)

    def test_first_run_preference_probe_returns_absent_without_fabricating_bytes(self) -> None:
        runner = _ScriptedRunner(
            [
                (
                    (
                        "adb.exe",
                        "-s",
                        "SERIAL",
                        "shell",
                        "run-as",
                        "com.flightalert",
                        "test",
                        "-e",
                        "shared_prefs/flight_alert.xml",
                    ),
                    CommandResult(1, b"", b""),
                )
            ]
        )
        device = AdbInstallDevice(adb="adb.exe", runner=runner)
        device.serial = "SERIAL"
        probe = getattr(device, "preference_state", None)
        self.assertIsNotNone(probe, "preference_state is missing")

        self.assertEqual((False, b""), probe())

    def test_remote_inventory_parser_rejects_symlinks_and_preserves_inode_identity(self) -> None:
        parser = getattr(installer_module, "parse_remote_inventory", None)
        self.assertIsNotNone(parser, "parse_remote_inventory is missing")
        root = ReferencePackageInstaller.REFERENCE_ROOT
        entries = parser(
            (
                f"directory\t1\t101\t{root}/legacy-package\n"
                f"regular file\t1\t102\t{root}/planet.osm.pbf\n"
            ).encode("ascii"),
            root,
        )
        self.assertEqual((101, 102), tuple(entry.inode for entry in entries))
        with self.assertRaisesRegex(ReferencePackageInstallError, "unsupported"):
            parser(
                f"symbolic link\t1\t103\t{root}/link\n".encode("ascii"),
                root,
            )

    def test_listener_binding_parser_requires_an_actual_bound_service_record(self) -> None:
        parser = getattr(installer_module, "parse_listener_binding", None)
        self.assertIsNotNone(parser, "parse_listener_binding is missing")
        component = (
            "com.flightalert/com.flightalert.alerts.MonitoringNotificationHiderService"
        )
        self.assertTrue(
            parser(
                (
                    f"unrelatedTitle=航班\n"
                    f"    Live notification listeners (1):\n"
                    f"      {component} (user 0): binder\n"
                    f"    Snoozed notification listeners (0):\n"
                ).encode("utf-8"),
                component,
            )
        )
        self.assertFalse(
            parser(
                (
                    f"    Allowed notification listeners:\n      {component}\n"
                    f"    Live notification listeners (0):\n"
                    f"    Snoozed notification listeners (0):\n"
                ).encode(),
                component,
            )
        )

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

    def test_cli_dispatches_finalize_and_rollback_modes(self) -> None:
        class FakeTransaction:
            def __init__(self, **arguments: object) -> None:
                self.evidence = arguments["evidence_directory"]

            def finalize_acceptance(self) -> None:
                (self.evidence / "final-success.json").write_bytes(
                    _canonical({"state": "accepted"})
                )

            def rollback(self) -> None:
                (self.evidence / "rollback-receipt.json").write_bytes(
                    _canonical({"state": "rolled-back"})
                )

        for flag, expected in (
            ("--finalize-acceptance", "accepted"),
            ("--rollback", "rolled-back"),
        ):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                fixture_root = root / "fixture"
                fixture_root.mkdir()
                fixture = _ValidInstallFixture(fixture_root)
                evidence = root / "evidence"
                evidence.mkdir()
                output = io.StringIO()
                with (
                    mock.patch.object(installer_module, "ReferencePackageInstaller", FakeTransaction),
                    mock.patch.object(installer_module, "AtomicDeviceLeaseClient"),
                    mock.patch.object(installer_module, "AdbInstallDevice"),
                    redirect_stdout(output),
                ):
                    code = _main(
                        [
                            "--package",
                            str(fixture.package),
                            "--apk",
                            str(fixture.apk),
                            "--final-result",
                            str(fixture.result),
                            flag,
                            "--lease-helper",
                            str(root / "lease.ps1"),
                            "--thread-id",
                            "thread",
                            "--evidence-directory",
                            str(evidence),
                        ]
                    )

            self.assertEqual(0, code)
            self.assertEqual(expected, json.loads(output.getvalue())["state"])

    def test_wrapper_exposes_explicit_finalize_and_rollback_switches(self) -> None:
        wrapper = Path(__file__).parents[2] / "install-reference-dictionary-experiment8.ps1"
        text = wrapper.read_text("utf-8")

        self.assertIn("[switch]$FinalizeAcceptance", text)
        self.assertIn("[switch]$Rollback", text)
        self.assertIn("--finalize-acceptance", text)
        self.assertIn("--rollback", text)
        self.assertNotIn("with -Execute", text)
        self.assertIn("with device transaction modes", text)


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

    def test_manifest_must_match_every_android_binary_v3_runtime_identity(self) -> None:
        manifest_path = self.fixture.package / "manifest.json"
        original = json.loads(manifest_path.read_text("utf-8"))
        cases = (
            ("payloadSchema", "wrong.payload", "payload schema"),
            ("presentationPolicySha256", "0" * 64, "presentation policy"),
            ("sourcedTextPolicySha256", "0" * 64, "sourced-text policy"),
            ("unicodeScriptProfileSha256", "0" * 64, "Unicode script profile"),
        )
        for field, value, message in cases:
            with self.subTest(field=field):
                changed = dict(original)
                changed[field] = value
                manifest_path.write_bytes(_canonical(changed))
                with self.assertRaisesRegex(ReferencePackageInstallError, message):
                    self.validate()
        manifest_path.write_bytes(_canonical(original))

    def test_manifest_missing_runtime_identity_fails_closed(self) -> None:
        manifest_path = self.fixture.package / "manifest.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        del manifest["payloadSchema"]
        manifest_path.write_bytes(_canonical(manifest))

        with self.assertRaisesRegex(
            ReferencePackageInstallError, "payload schema"
        ):
            self.validate()

    def test_installer_runtime_pins_match_authoritative_android_contracts(self) -> None:
        self.assertEqual(3, installer_module.RUNTIME_SCHEMA_VERSION)
        self.assertEqual(
            "flightalert.reference.renderer-tile.v1",
            installer_module.RUNTIME_PAYLOAD_SCHEMA,
        )
        self.assertEqual(
            "dce9bd4b789c0528318dbb184e17efe7465f444550ba0180efd82e1cb7219154",
            installer_module.RUNTIME_PRESENTATION_POLICY_SHA256,
        )
        self.assertEqual(
            "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a",
            installer_module.RUNTIME_SOURCED_TEXT_POLICY_SHA256,
        )
        self.assertEqual(
            "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb",
            installer_module.RUNTIME_UNICODE_SCRIPT_PROFILE_SHA256,
        )
        repository = Path(__file__).parents[3]
        loader = (
            repository
            / "app/src/main/java/com/flightalert/map/ReferenceDictionaryPackage.kt"
        ).read_text("utf-8")
        presentation = (
            repository
            / "app/src/main/java/com/flightalert/map/ReferencePresentationPolicy.kt"
        ).read_text("utf-8")
        sourced = (
            repository / "app/src/main/java/com/flightalert/map/SourcedMapText.kt"
        ).read_text("utf-8")
        self.assertIn(installer_module.RUNTIME_PAYLOAD_SCHEMA, loader)
        self.assertIn(installer_module.RUNTIME_PRESENTATION_POLICY_SHA256, presentation)
        self.assertIn(installer_module.RUNTIME_SOURCED_TEXT_POLICY_SHA256, sourced)
        self.assertIn(installer_module.RUNTIME_UNICODE_SCRIPT_PROFILE_SHA256, sourced)

    def _rewrite_package_json(self, name: str, mutate: object) -> None:
        path = self.fixture.package / name
        document = json.loads(path.read_text("utf-8"))
        mutate(document)  # type: ignore[operator]
        path.write_bytes(_canonical(document))

    def _refresh_manifest_and_result_bindings(self) -> None:
        manifest_path = self.fixture.package / "manifest.json"
        manifest_raw = manifest_path.read_bytes()
        finalization_path = (
            self.fixture.package / "class-catalog-finalization-receipt.json"
        )
        finalization = json.loads(finalization_path.read_text("utf-8"))
        manifest_binding = next(
            item for item in finalization["outputFiles"] if item["name"] == "manifest.json"
        )
        manifest_binding["bytes"] = len(manifest_raw)
        manifest_binding["sha256"] = _sha256(manifest_raw)
        finalization_path.write_bytes(_canonical(finalization))
        package_bytes = sum(
            path.stat().st_size for path in self.fixture.package.iterdir()
        )
        result = json.loads(self.fixture.result.read_text("utf-8"))
        result["package"]["bytes"] = package_bytes
        result["footprint"]["totalBytes"] = (
            package_bytes + self.fixture.apk.stat().st_size + MANDATORY_RESERVE_BYTES
        )
        self.fixture.result.write_bytes(_canonical(result))

    def test_receipt_schemas_reject_unknown_fields(self) -> None:
        self._rewrite_package_json(
            "merge-receipt.json", lambda document: document.__setitem__("extra", True)
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "schema fields"):
            self.validate()

    def test_merge_input_chain_must_match_manifest(self) -> None:
        self._rewrite_package_json(
            "merge-receipt.json",
            lambda document: document["inputs"][0].__setitem__("recordsBytes", 999),
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "merge inputs"):
            self.validate()

    def test_merge_chain_allows_multiple_explicit_supplement_inputs(self) -> None:
        manifest_path = self.fixture.package / "manifest.json"
        manifest = json.loads(manifest_path.read_text("utf-8"))
        first = dict(manifest["merge"]["inputs"][0])
        first["role"] = "supplement"
        first["packageId"] = "world-supplement-one-v3"
        second = dict(first)
        second["packageId"] = "world-supplement-two-v3"
        manifest["merge"]["inputs"].extend((first, second))
        manifest_path.write_bytes(_canonical(manifest))
        self._rewrite_package_json(
            "merge-receipt.json",
            lambda document: document["inputs"].extend((first, second)),
        )
        self._refresh_manifest_and_result_bindings()

        self.assertEqual(PACKAGE_ID, self.validate().package_id)

    def test_merge_coverage_must_prove_every_declared_tile_present(self) -> None:
        self._rewrite_package_json(
            "merge-receipt.json",
            lambda document: document["coverage"].__setitem__("presentTileCount", 0),
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "merge coverage"):
            self.validate()

    def test_finalizer_input_manifest_must_be_merge_output_manifest(self) -> None:
        self._rewrite_package_json(
            "class-catalog-finalization-receipt.json",
            lambda document: document["inputFiles"][0].__setitem__("sha256", "9" * 64),
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "manifest transition"):
            self.validate()

    def test_finalizer_renderer_contract_and_counts_are_bound(self) -> None:
        self._rewrite_package_json(
            "class-catalog-finalization-receipt.json",
            lambda document: document.__setitem__("rendererContractSha256", "8" * 64),
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "renderer contract"):
            self.validate()

    def test_merge_and_finalizer_subtype_counts_must_match(self) -> None:
        self._rewrite_package_json(
            "merge-receipt.json",
            lambda document: document.__setitem__("subtypeCounts", {"city": 1}),
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "subtype counts"):
            self.validate()

    def test_lf_and_crlf_tool_checkouts_have_one_accepted_source_identity(self) -> None:
        merger = Path(installer_module.__file__).parent / "v3_package_merger.py"
        finalizer = Path(installer_module.__file__).parent / "v3_class_catalog_finalizer.py"
        merger_lf = merger.read_bytes().replace(b"\r\n", b"\n")
        finalizer_lf = finalizer.read_bytes().replace(b"\r\n", b"\n")
        crlf_merger = _sha256(merger_lf.replace(b"\n", b"\r\n"))
        crlf_finalizer = _sha256(finalizer_lf.replace(b"\n", b"\r\n"))
        self._rewrite_package_json(
            "manifest.json",
            lambda document: document["merge"].__setitem__("mergerSha256", crlf_merger),
        )
        self._rewrite_package_json(
            "merge-receipt.json",
            lambda document: document.__setitem__("mergerSha256", crlf_merger),
        )
        self._rewrite_package_json(
            "class-catalog-finalization-receipt.json",
            lambda document: document.__setitem__("finalizerSha256", crlf_finalizer),
        )
        self._refresh_manifest_and_result_bindings()

        plan = self.validate()

        self.assertEqual(PACKAGE_ID, plan.package_id)

    def test_non_line_ending_tool_source_change_is_rejected(self) -> None:
        self._rewrite_package_json(
            "manifest.json",
            lambda document: document["merge"].__setitem__("mergerSha256", "a" * 64),
        )
        self._rewrite_package_json(
            "merge-receipt.json",
            lambda document: document.__setitem__("mergerSha256", "a" * 64),
        )

        with self.assertRaisesRegex(ReferencePackageInstallError, "merger source"):
            self.validate()

    def test_package_inventory_is_rechecked_before_device_or_lease_use(self) -> None:
        plan = self.validate()
        (self.fixture.package / "late-extra.bin").write_bytes(b"raced")
        lease = _FakeLease()
        device = _FakeDevice(Path(self.temporary.name))
        evidence = Path(self.temporary.name) / "evidence"

        with self.assertRaisesRegex(ReferencePackageInstallError, "inventory changed"):
            ReferencePackageInstaller(
                plan=plan,
                device=device,
                lease=lease,
                evidence_directory=evidence,
            ).install()

        self.assertFalse(lease.acquired)
        self.assertEqual([], device.calls)

    def test_tool_sources_are_pinned_to_lf_in_gitattributes(self) -> None:
        attributes = (Path(__file__).parents[3] / ".gitattributes").read_text("utf-8")

        self.assertIn("/tools/experiment8/v3_package_merger.py text eol=lf", attributes)
        self.assertIn(
            "/tools/experiment8/v3_class_catalog_finalizer.py text eol=lf", attributes
        )


if __name__ == "__main__":
    unittest.main()
