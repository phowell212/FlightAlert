"""Fail-closed host and device installation for the Experiment 8 V3 package."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Protocol, Sequence


PACKAGE_ID = "world-experiment8-binary-v3"
PACKAGE_FILE_NAMES = (
    "class-catalog-finalization-receipt.json",
    "class-catalog.bin",
    "manifest.json",
    "merge-receipt.json",
    "records.fadictpack",
    "tile-index.bin",
)
RUNTIME_FILE_NAMES = (
    "manifest.json",
    "records.fadictpack",
    "tile-index.bin",
    "class-catalog.bin",
)
MERGED_FILE_NAMES = (
    "manifest.json",
    "records.fadictpack",
    "tile-index.bin",
)
MANDATORY_RESERVE_BYTES = 1_500_000_000
PREFERRED_FOOTPRINT_CEILING_BYTES = 25_000_000_000
HARD_FOOTPRINT_CEILING_BYTES = 40_000_000_000
HARD_PACKAGE_CEILING_BYTES = 38_500_000_000
MAX_JSON_BYTES = 4 * 1024 * 1024
HASH_CHUNK_BYTES = 4 * 1024 * 1024
HEX_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
FILE_ATTRIBUTE_REPARSE_POINT = 0x400


class ReferencePackageInstallError(RuntimeError):
    """The source package, evidence, or device transaction is not acceptable."""


@dataclass(frozen=True)
class InstallFile:
    name: str
    path: Path
    byte_length: int
    sha256: str
    stat_identity: tuple[int, int, int, int, int]


@dataclass(frozen=True)
class HostInstallPlan:
    package_directory: Path
    package_id: str
    package_files: tuple[InstallFile, ...]
    package_bytes: int
    apk_path: Path
    apk_bytes: int
    apk_sha256: str
    mandatory_reserve_bytes: int
    total_footprint_bytes: int
    preferred_strictly_below: bool
    hard_strictly_below: bool

    @classmethod
    def validate(
        cls,
        *,
        package_directory: Path,
        apk_path: Path,
        final_result_path: Path,
    ) -> "HostInstallPlan":
        package_directory = _real_directory(
            Path(package_directory), "package directory"
        )
        apk_path = _real_file(Path(apk_path), "APK")
        final_result_path = _real_file(Path(final_result_path), "final result")

        actual_entries = tuple(
            sorted(item.name for item in package_directory.iterdir())
        )
        if actual_entries != PACKAGE_FILE_NAMES:
            raise ReferencePackageInstallError(
                "package does not have the exact six-file inventory"
            )

        package_files = tuple(
            _hash_regular_file(package_directory / name, f"package file {name}")
            for name in PACKAGE_FILE_NAMES
        )
        file_by_name = {item.name: item for item in package_files}
        package_bytes = sum(item.byte_length for item in package_files)
        if package_bytes >= HARD_PACKAGE_CEILING_BYTES:
            raise ReferencePackageInstallError(
                "package is not strictly below the 38,500,000,000-byte ceiling"
            )

        manifest = _read_strict_json(
            package_directory / "manifest.json", "manifest", canonical=True
        )
        if _exact_string(manifest.get("packageId"), "manifest package ID") != PACKAGE_ID:
            raise ReferencePackageInstallError("manifest package ID differs")
        if _exact_integer(manifest.get("schemaVersion"), "manifest schema version") != 3:
            raise ReferencePackageInstallError("manifest schema version differs")
        coverage = _exact_mapping(manifest.get("coverage"), "manifest coverage")
        if not _exact_bool(
            coverage.get("completeDeclaredScope"),
            "manifest complete-declared-scope claim",
        ):
            raise ReferencePackageInstallError("package declared scope is incomplete")
        if not _exact_bool(
            coverage.get("completeWholeEarthDictionary"),
            "manifest whole-earth claim",
        ):
            raise ReferencePackageInstallError("package is not whole-earth complete")

        merge_receipt = _read_strict_json(
            package_directory / "merge-receipt.json",
            "merge receipt",
            canonical=True,
        )
        finalization_receipt = _read_strict_json(
            package_directory / "class-catalog-finalization-receipt.json",
            "finalization receipt",
            canonical=True,
        )
        _validate_receipts(
            manifest=manifest,
            merge_receipt=merge_receipt,
            finalization_receipt=finalization_receipt,
            file_by_name=file_by_name,
        )

        apk = _hash_regular_file(apk_path, "APK")
        result = _read_strict_json(final_result_path, "final result", canonical=False)
        _validate_result_identity(
            result=result,
            package_directory=package_directory,
            package_bytes=package_bytes,
            apk=apk,
        )
        total = package_bytes + apk.byte_length + MANDATORY_RESERVE_BYTES
        if _exact_integer(
            _exact_mapping(result.get("footprint"), "final result footprint").get(
                "totalBytes"
            ),
            "final result footprint total",
        ) != total:
            raise ReferencePackageInstallError("final result footprint total differs")
        preferred = total < PREFERRED_FOOTPRINT_CEILING_BYTES
        hard = total < HARD_FOOTPRINT_CEILING_BYTES
        footprint = _exact_mapping(result.get("footprint"), "final result footprint")
        if _exact_bool(
            footprint.get("preferredStrictlyBelow"), "preferred footprint result"
        ) is not preferred:
            raise ReferencePackageInstallError("preferred footprint result differs")
        if _exact_bool(
            footprint.get("hardStrictlyBelow"), "hard footprint result"
        ) is not hard:
            raise ReferencePackageInstallError("hard footprint result differs")
        if not hard:
            raise ReferencePackageInstallError(
                "complete footprint is not strictly below 40,000,000,000 bytes"
            )

        for item in (*package_files, apk):
            _assert_install_file_unchanged(item)

        return cls(
            package_directory=package_directory,
            package_id=PACKAGE_ID,
            package_files=package_files,
            package_bytes=package_bytes,
            apk_path=apk.path,
            apk_bytes=apk.byte_length,
            apk_sha256=apk.sha256,
            mandatory_reserve_bytes=MANDATORY_RESERVE_BYTES,
            total_footprint_bytes=total,
            preferred_strictly_below=preferred,
            hard_strictly_below=hard,
        )


@dataclass(frozen=True)
class RemoteFileIdentity:
    byte_length: int
    sha256: str


@dataclass(frozen=True)
class DevicePrestate:
    serial: str
    apk_backup_path: Path
    apk_bytes: int
    apk_sha256: str
    preferences: bytes
    app_was_running: bool
    final_package_was_present: bool


class DeviceLease(Protocol):
    def acquire(self, **arguments: object) -> str: ...

    def heartbeat(self, token: str) -> None: ...

    def release(self, token: str) -> None: ...


class InstallDevice(Protocol):
    def require_single_ready_device(self) -> str: ...

    def capture_prestate(
        self, evidence_directory: Path, final_package_path: str
    ) -> DevicePrestate: ...

    def available_bytes(self, path: str) -> int: ...

    def path_exists(self, path: str) -> bool: ...

    def make_directory(self, path: str) -> None: ...

    def push_file(self, local: Path, remote: str) -> None: ...

    def remote_file_identity(self, path: str) -> RemoteFileIdentity: ...

    def force_stop(self) -> None: ...

    def move_no_replace(self, source: str, destination: str) -> None: ...

    def install_apk(self, path: Path) -> None: ...

    def restore_preferences(self, raw: bytes) -> None: ...

    def restore_running(self, was_running: bool) -> None: ...

    def restore_apk(self, path: Path) -> None: ...

    def remove_tree(self, path: str) -> None: ...

    def verify_installed_apk(self) -> RemoteFileIdentity: ...


class ReferencePackageInstaller:
    REFERENCE_ROOT = (
        "/storage/emulated/0/Android/data/com.flightalert/files/reference"
    )
    FINAL_PACKAGE_PATH = f"{REFERENCE_ROOT}/{PACKAGE_ID}"
    MINIMUM_COPY_MARGIN_BYTES = 64 * 1024 * 1024

    def __init__(
        self,
        *,
        plan: HostInstallPlan,
        device: InstallDevice,
        lease: DeviceLease,
        evidence_directory: Path,
    ) -> None:
        self.plan = plan
        self.device = device
        self.lease = lease
        self.evidence_directory = Path(evidence_directory)

    def install(self) -> None:
        if self.evidence_directory.exists():
            raise ReferencePackageInstallError("installation evidence path already exists")
        token: str | None = None
        prestate: DevicePrestate | None = None
        stage_path: str | None = None
        backup_path: str | None = None
        failed_path: str | None = None
        old_moved = False
        new_published = False
        device_mutated = False
        completed = False
        primary_error: BaseException | None = None
        cleanup_errors: list[str] = []
        try:
            token = self.lease.acquire(
                owner="Zeus/Experiment8",
                purpose="Install finalized whole-world Experiment 8 package and source-matched APK",
                scenario="transactional final package install",
                stateful_effects=(
                    "same-signature APK replacement and atomic external reference-package swap; "
                    "no app-data or cache clear"
                ),
                expected_minutes=180,
            )
            if re.fullmatch(r"[0-9a-f]{32}", token) is None:
                raise ReferencePackageInstallError("device lease token is malformed")
            stage_path = f"{self.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.stage"
            backup_path = f"{self.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.backup"
            failed_path = f"{self.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.failed"
            self.evidence_directory.mkdir(parents=True, exist_ok=False)
            serial = self.device.require_single_ready_device()
            if type(serial) is not str or not serial or any(character.isspace() for character in serial):
                raise ReferencePackageInstallError("ready device serial is malformed")
            prestate = self.device.capture_prestate(
                self.evidence_directory, self.FINAL_PACKAGE_PATH
            )
            if prestate.serial != serial:
                raise ReferencePackageInstallError("device serial changed during prestate")
            if self.device.path_exists(self.FINAL_PACKAGE_PATH) is not prestate.final_package_was_present:
                raise ReferencePackageInstallError("final package state changed during prestate")
            for path in (stage_path, backup_path, failed_path):
                if self.device.path_exists(path):
                    raise ReferencePackageInstallError(
                        "tokenized installation path already exists"
                    )
            required_free = self.plan.package_bytes + self.MINIMUM_COPY_MARGIN_BYTES
            external_files_root = self.REFERENCE_ROOT.rsplit("/", 1)[0]
            if self.device.available_bytes(external_files_root) < required_free:
                raise ReferencePackageInstallError(
                    "device free space is below the staged package requirement"
                )
            self.lease.heartbeat(token)
            device_mutated = True
            self.device.make_directory(self.REFERENCE_ROOT)
            self.device.make_directory(stage_path)
            order = tuple(
                item for item in self.plan.package_files if item.name != "manifest.json"
            ) + tuple(
                item for item in self.plan.package_files if item.name == "manifest.json"
            )
            for item in order:
                if item.name == "manifest.json":
                    self.device.force_stop()
                _assert_install_file_unchanged(item)
                remote = f"{stage_path}/{item.name}"
                self.lease.heartbeat(token)
                self.device.push_file(item.path, remote)
                actual = self.device.remote_file_identity(remote)
                if (
                    actual.byte_length != item.byte_length
                    or actual.sha256 != item.sha256
                ):
                    raise ReferencePackageInstallError(
                        f"staged device identity differs for {item.name}"
                    )
            self.lease.heartbeat(token)
            if prestate.final_package_was_present:
                self.device.move_no_replace(self.FINAL_PACKAGE_PATH, backup_path)
                old_moved = True
            self.device.move_no_replace(stage_path, self.FINAL_PACKAGE_PATH)
            new_published = True
            self.device.install_apk(self.plan.apk_path)
            installed = self.device.verify_installed_apk()
            if (
                installed.byte_length != self.plan.apk_bytes
                or installed.sha256 != self.plan.apk_sha256
            ):
                raise ReferencePackageInstallError("installed APK identity differs")
            for item in self.plan.package_files:
                actual = self.device.remote_file_identity(
                    f"{self.FINAL_PACKAGE_PATH}/{item.name}"
                )
                if (
                    actual.byte_length != item.byte_length
                    or actual.sha256 != item.sha256
                ):
                    raise ReferencePackageInstallError(
                        f"published device identity differs for {item.name}"
                    )
            self.device.restore_preferences(prestate.preferences)
            self.device.restore_running(prestate.app_was_running)
            self._write_receipt(serial)
            if old_moved:
                self.device.remove_tree(backup_path)
                old_moved = False
            completed = True
        except BaseException as error:
            primary_error = error
        finally:
            if not completed and prestate is not None and device_mutated:
                self._recover(
                    prestate=prestate,
                    stage_path=stage_path,
                    backup_path=backup_path,
                    failed_path=failed_path,
                    old_moved=old_moved,
                    new_published=new_published,
                    cleanup_errors=cleanup_errors,
                )
            if token is not None:
                try:
                    self.lease.release(token)
                except BaseException as error:
                    cleanup_errors.append(f"lease release failed: {error}")
        if primary_error is not None:
            if self.evidence_directory.is_dir():
                try:
                    self._write_failure_receipt(primary_error, cleanup_errors)
                except BaseException as error:
                    cleanup_errors.append(f"failure receipt write failed: {error}")
            if cleanup_errors:
                raise ReferencePackageInstallError(
                    f"{primary_error}; recovery failures: {' | '.join(cleanup_errors)}"
                ) from primary_error
            if isinstance(primary_error, ReferencePackageInstallError):
                raise primary_error
            raise ReferencePackageInstallError(str(primary_error)) from primary_error
        if cleanup_errors:
            raise ReferencePackageInstallError(
                "installation completed but cleanup failed: "
                + " | ".join(cleanup_errors)
            )

    def _recover(
        self,
        *,
        prestate: DevicePrestate,
        stage_path: str | None,
        backup_path: str | None,
        failed_path: str | None,
        old_moved: bool,
        new_published: bool,
        cleanup_errors: list[str],
    ) -> None:
        def attempt(label: str, action: object) -> None:
            try:
                action()  # type: ignore[operator]
            except BaseException as error:
                cleanup_errors.append(f"{label}: {error}")

        receipt_path = self.evidence_directory / "install-receipt.json"
        if receipt_path.exists():
            attempt("invalidate success receipt", receipt_path.unlink)
        attempt("force-stop before recovery", self.device.force_stop)
        if new_published and failed_path is not None:
            attempt(
                "quarantine failed published package",
                lambda: self.device.move_no_replace(
                    self.FINAL_PACKAGE_PATH, failed_path
                ),
            )
        if old_moved and backup_path is not None:
            attempt(
                "restore previous final package",
                lambda: self.device.move_no_replace(
                    backup_path, self.FINAL_PACKAGE_PATH
                ),
            )
        if stage_path is not None and self.device.path_exists(stage_path):
            attempt("remove staged package", lambda: self.device.remove_tree(stage_path))
        if failed_path is not None and self.device.path_exists(failed_path):
            attempt(
                "remove failed published package",
                lambda: self.device.remove_tree(failed_path),
            )
        if backup_path is not None and self.device.path_exists(backup_path):
            attempt("remove leftover backup", lambda: self.device.remove_tree(backup_path))
        attempt("restore previous APK", lambda: self.device.restore_apk(prestate.apk_backup_path))
        attempt(
            "restore preferences",
            lambda: self.device.restore_preferences(prestate.preferences),
        )
        attempt(
            "restore running state",
            lambda: self.device.restore_running(prestate.app_was_running),
        )

    def _write_receipt(self, serial: str) -> None:
        receipt = {
            "apk": {
                "bytes": self.plan.apk_bytes,
                "sha256": self.plan.apk_sha256,
            },
            "deviceSerialSha256": hashlib.sha256(
                serial.encode("utf-8", "strict")
            ).hexdigest(),
            "footprint": {
                "hardStrictlyBelow": self.plan.hard_strictly_below,
                "preferredStrictlyBelow": self.plan.preferred_strictly_below,
                "projectedBytes": self.plan.total_footprint_bytes,
            },
            "package": {
                "bytes": self.plan.package_bytes,
                "files": [
                    {
                        "bytes": item.byte_length,
                        "name": item.name,
                        "sha256": item.sha256,
                    }
                    for item in self.plan.package_files
                ],
                "packageId": self.plan.package_id,
            },
            "schema": "flightalert.experiment8.reference-install-receipt.v1",
            "state": "installed",
        }
        path = self.evidence_directory / "install-receipt.json"
        path.write_bytes(_canonical_json_bytes(receipt))

    def _write_failure_receipt(
        self, primary_error: BaseException, cleanup_errors: Sequence[str]
    ) -> None:
        receipt = {
            "apkSha256": self.plan.apk_sha256,
            "cleanupFailures": list(cleanup_errors),
            "packageId": self.plan.package_id,
            "primaryError": str(primary_error)[:4096],
            "schema": "flightalert.experiment8.reference-install-failure.v1",
            "state": "recovered" if not cleanup_errors else "recovery-incomplete",
        }
        (self.evidence_directory / "install-failure.json").write_bytes(
            _canonical_json_bytes(receipt)
        )


@dataclass(frozen=True)
class CommandResult:
    return_code: int
    stdout: bytes
    stderr: bytes


class CommandRunner(Protocol):
    def run(
        self,
        arguments: Sequence[str],
        *,
        input_bytes: bytes | None = None,
        timeout_seconds: float | None = None,
    ) -> CommandResult: ...


class SubprocessCommandRunner:
    def run(
        self,
        arguments: Sequence[str],
        *,
        input_bytes: bytes | None = None,
        timeout_seconds: float | None = None,
    ) -> CommandResult:
        if not arguments or any(type(item) is not str or not item for item in arguments):
            raise ReferencePackageInstallError("native command arguments are malformed")
        creation_flags = 0
        if os.name == "nt":
            creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            completed = subprocess.run(
                list(arguments),
                input=input_bytes,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                shell=False,
                timeout=timeout_seconds,
                creationflags=creation_flags,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise ReferencePackageInstallError(
                f"native command failed to execute: {arguments[0]}"
            ) from error
        return CommandResult(
            return_code=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )


def parse_adb_devices(raw: bytes) -> str:
    text = _strict_ascii_text(raw, "adb device list", maximum=128 * 1024)
    lines = text.splitlines()
    if not lines or lines[0] != "List of devices attached":
        raise ReferencePackageInstallError("adb device list header differs")
    rows = [line for line in lines[1:] if line]
    if len(rows) != 1:
        raise ReferencePackageInstallError("adb must expose exactly one device row")
    columns = rows[0].split()
    if len(columns) < 2 or columns[1] != "device":
        raise ReferencePackageInstallError("sole adb device is not ready")
    serial = columns[0]
    if re.fullmatch(r"[A-Za-z0-9._:-]+", serial) is None:
        raise ReferencePackageInstallError("adb serial is malformed")
    return serial


class AtomicDeviceLeaseClient:
    def __init__(
        self,
        *,
        helper_path: Path,
        thread_id: str,
        runner: CommandRunner,
        powershell: str = "powershell.exe",
        heartbeat_interval_seconds: float = 45.0,
    ) -> None:
        if type(thread_id) is not str or not thread_id.strip():
            raise ReferencePackageInstallError("device lease thread ID is required")
        if heartbeat_interval_seconds <= 0:
            raise ReferencePackageInstallError("lease heartbeat interval must be positive")
        self.helper_path = Path(helper_path)
        self.thread_id = thread_id
        self.runner = runner
        self.powershell = powershell
        self.heartbeat_interval_seconds = heartbeat_interval_seconds
        self._token: str | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._lock = threading.Lock()
        self._background_error: BaseException | None = None

    def acquire(self, **arguments: object) -> str:
        if self._token is not None:
            raise ReferencePackageInstallError("device lease is already active here")
        owner = _required_argument(arguments, "owner")
        purpose = _required_argument(arguments, "purpose")
        scenario = _required_argument(arguments, "scenario")
        effects = _required_argument(arguments, "stateful_effects")
        minutes = arguments.get("expected_minutes")
        if type(minutes) is not int or minutes <= 0:
            raise ReferencePackageInstallError("device lease minutes are malformed")
        document = self._call(
            "acquire",
            "-Owner",
            owner,
            "-ThreadId",
            self.thread_id,
            "-Purpose",
            purpose,
            "-Scenario",
            scenario,
            "-StatefulEffects",
            effects,
            "-ExpectedMinutes",
            str(minutes),
        )
        token = _exact_string(document.get("token"), "device lease token")
        if not _exact_bool(document.get("held"), "device lease held state"):
            raise ReferencePackageInstallError("device lease acquire did not hold the lease")
        if re.fullmatch(r"[0-9a-f]{32}", token) is None:
            raise ReferencePackageInstallError("device lease token is malformed")
        self._token = token
        self._stop.clear()
        self._background_error = None
        self._thread = threading.Thread(
            target=self._heartbeat_loop,
            name="flightalert-exp8-device-lease-heartbeat",
            daemon=True,
        )
        self._thread.start()
        return token

    def heartbeat(self, token: str) -> None:
        self._assert_token(token)
        self._raise_background_error()
        with self._lock:
            document = self._call("heartbeat", "-Token", token)
        if not _exact_bool(document.get("held"), "device heartbeat held state"):
            raise ReferencePackageInstallError("device heartbeat lost the lease")
        returned = _exact_string(document.get("token"), "device heartbeat token")
        if returned != token:
            raise ReferencePackageInstallError("device heartbeat token differs")

    def release(self, token: str) -> None:
        self._assert_token(token)
        self._stop.set()
        thread = self._thread
        if thread is not None:
            thread.join(timeout=max(5.0, self.heartbeat_interval_seconds + 5.0))
            if thread.is_alive():
                raise ReferencePackageInstallError("device heartbeat thread did not stop")
        with self._lock:
            document = self._call("release", "-Token", token)
        self._token = None
        self._thread = None
        if _exact_bool(document.get("held"), "device release held state"):
            raise ReferencePackageInstallError("device lease remains held after release")
        self._raise_background_error()

    def _heartbeat_loop(self) -> None:
        while not self._stop.wait(self.heartbeat_interval_seconds):
            token = self._token
            if token is None:
                return
            try:
                with self._lock:
                    document = self._call("heartbeat", "-Token", token)
                if (
                    not _exact_bool(document.get("held"), "background heartbeat held state")
                    or _exact_string(
                        document.get("token"), "background heartbeat token"
                    )
                    != token
                ):
                    raise ReferencePackageInstallError(
                        "background device heartbeat identity differs"
                    )
            except BaseException as error:
                self._background_error = error
                self._stop.set()
                return

    def _call(self, action: str, *arguments: str) -> Mapping[str, object]:
        result = self.runner.run(
            (
                self.powershell,
                "-NoProfile",
                "-NonInteractive",
                "-File",
                str(self.helper_path),
                action,
                *arguments,
            ),
            timeout_seconds=30.0,
        )
        if result.return_code != 0:
            detail = _bounded_error(result.stderr)
            raise ReferencePackageInstallError(
                f"device lease {action} failed ({result.return_code}): {detail}"
            )
        return _parse_strict_json_bytes(result.stdout, f"device lease {action}")

    def _assert_token(self, token: str) -> None:
        if self._token is None or token != self._token:
            raise ReferencePackageInstallError("device lease token differs")

    def _raise_background_error(self) -> None:
        if self._background_error is not None:
            raise ReferencePackageInstallError(
                f"background device heartbeat failed: {self._background_error}"
            ) from self._background_error


class AdbInstallDevice:
    APP_ID = "com.flightalert"
    MAIN_ACTIVITY = "com.flightalert/.MainActivity"
    PREFERENCE_PATH = "shared_prefs/flight_alert.xml"

    def __init__(self, *, adb: str, runner: CommandRunner) -> None:
        self.adb = adb
        self.runner = runner
        self.serial: str | None = None
        self.evidence_directory: Path | None = None
        self._verification_counter = 0

    def require_single_ready_device(self) -> str:
        result = self.runner.run((self.adb, "devices", "-l"), timeout_seconds=30.0)
        if result.return_code != 0:
            raise ReferencePackageInstallError(
                f"adb devices failed: {_bounded_error(result.stderr)}"
            )
        self.serial = parse_adb_devices(result.stdout)
        return self.serial

    def capture_prestate(
        self, evidence_directory: Path, final_package_path: str
    ) -> DevicePrestate:
        serial = self._require_serial()
        self.evidence_directory = evidence_directory
        remote_apk = self._installed_base_path()
        apk_backup = evidence_directory / "installed-prestate.apk"
        self._checked(("pull", remote_apk, str(apk_backup)), timeout=180.0)
        apk = _hash_regular_file(apk_backup, "installed prestate APK")
        preferences_result = self._adb(
            ("exec-out", "run-as", self.APP_ID, "cat", self.PREFERENCE_PATH),
            timeout=30.0,
            allow_failure=True,
        )
        if preferences_result.return_code != 0 or not preferences_result.stdout:
            raise ReferencePackageInstallError(
                "installed app preferences are unavailable before install"
            )
        if len(preferences_result.stdout) > MAX_JSON_BYTES:
            raise ReferencePackageInstallError("installed app preferences are oversized")
        preferences = preferences_result.stdout
        (evidence_directory / "preferences-prestate.xml").write_bytes(preferences)
        pid_result = self._adb(
            ("shell", "pidof", self.APP_ID), timeout=15.0, allow_failure=True
        )
        if pid_result.return_code not in (0, 1):
            raise ReferencePackageInstallError("pidof prestate failed")
        pid_text = _strict_ascii_text(
            pid_result.stdout, "pidof prestate", maximum=4096
        ).strip()
        if pid_result.return_code == 0:
            pids = pid_text.split()
            if not pids or any(re.fullmatch(r"[1-9][0-9]*", item) is None for item in pids):
                raise ReferencePackageInstallError("running process prestate is malformed")
            app_running = True
        else:
            if pid_text:
                raise ReferencePackageInstallError("stopped process prestate is contradictory")
            app_running = False
        return DevicePrestate(
            serial=serial,
            apk_backup_path=apk_backup,
            apk_bytes=apk.byte_length,
            apk_sha256=apk.sha256,
            preferences=preferences,
            app_was_running=app_running,
            final_package_was_present=self.path_exists(final_package_path),
        )

    def available_bytes(self, path: str) -> int:
        result = self._checked(("shell", "df", "-k", path), timeout=30.0)
        text = _strict_ascii_text(result.stdout, "device df", maximum=128 * 1024)
        rows = [line.split() for line in text.splitlines() if line.strip()]
        if len(rows) < 2 or len(rows[-1]) < 6:
            raise ReferencePackageInstallError("device df output is malformed")
        try:
            available_kib = int(rows[-1][3], 10)
        except ValueError as error:
            raise ReferencePackageInstallError("device free-space value is malformed") from error
        if available_kib < 0:
            raise ReferencePackageInstallError("device free-space value is negative")
        return available_kib * 1024

    def path_exists(self, path: str) -> bool:
        result = self._adb(
            ("shell", "test", "-e", path), timeout=15.0, allow_failure=True
        )
        if result.return_code == 0:
            return True
        if result.return_code == 1 and not result.stdout:
            return False
        raise ReferencePackageInstallError(
            f"device path probe failed for {path}: {_bounded_error(result.stderr)}"
        )

    def make_directory(self, path: str) -> None:
        self._checked(("shell", "mkdir", "-p", "--", path), timeout=30.0)
        if not self.path_exists(path):
            raise ReferencePackageInstallError("device directory creation did not persist")

    def push_file(self, local: Path, remote: str) -> None:
        self._checked(("push", str(local), remote), timeout=None)

    def remote_file_identity(self, path: str) -> RemoteFileIdentity:
        size_result = self._checked(
            ("shell", "stat", "-c", "%s", path), timeout=30.0
        )
        size_text = _strict_ascii_text(
            size_result.stdout, "device file size", maximum=4096
        ).strip()
        if re.fullmatch(r"0|[1-9][0-9]*", size_text) is None:
            raise ReferencePackageInstallError("device file size is malformed")
        sha_result = self._checked(("shell", "sha256sum", path), timeout=None)
        sha_text = _strict_ascii_text(
            sha_result.stdout, "device SHA-256", maximum=4096
        ).strip()
        match = re.fullmatch(r"([0-9a-f]{64})[ \t]+(.+)", sha_text)
        if match is None or match.group(2) != path:
            raise ReferencePackageInstallError("device SHA-256 output is malformed")
        return RemoteFileIdentity(byte_length=int(size_text), sha256=match.group(1))

    def force_stop(self) -> None:
        self._checked(("shell", "am", "force-stop", self.APP_ID), timeout=30.0)

    def move_no_replace(self, source: str, destination: str) -> None:
        if not self.path_exists(source) or self.path_exists(destination):
            raise ReferencePackageInstallError("device atomic move precondition differs")
        self._checked(
            ("shell", "mv", "-n", "--", source, destination), timeout=60.0
        )
        if self.path_exists(source) or not self.path_exists(destination):
            raise ReferencePackageInstallError("device atomic move did not persist")

    def install_apk(self, path: Path) -> None:
        result = self._checked(("install", "-r", str(path)), timeout=300.0)
        text = _strict_ascii_text(result.stdout, "adb install output", maximum=128 * 1024)
        if not any(line.strip() == "Success" for line in text.splitlines()):
            raise ReferencePackageInstallError("adb install did not report Success")

    def restore_preferences(self, raw: bytes) -> None:
        if not raw:
            raise ReferencePackageInstallError("preference restoration bytes are empty")
        evidence = self._require_evidence_directory()
        token = uuid.uuid4().hex
        host = evidence / f"preferences-restore-{token}.xml"
        remote = f"/data/local/tmp/flightalert-exp8-preferences-{token}.xml"
        private_stage = f"shared_prefs/.flight_alert.xml.exp8-{token}.tmp"
        host.write_bytes(raw)
        try:
            self._checked(("push", str(host), remote), timeout=60.0)
            self._checked(("shell", "chmod", "0644", remote), timeout=30.0)
            self._checked(
                ("shell", "run-as", self.APP_ID, "cp", remote, private_stage),
                timeout=30.0,
            )
            self._checked(
                ("shell", "run-as", self.APP_ID, "chmod", "0600", private_stage),
                timeout=30.0,
            )
            self._checked(
                (
                    "shell",
                    "run-as",
                    self.APP_ID,
                    "mv",
                    "-f",
                    "--",
                    private_stage,
                    self.PREFERENCE_PATH,
                ),
                timeout=30.0,
            )
        finally:
            self._adb(
                ("shell", "rm", "-f", "--", remote),
                timeout=30.0,
                allow_failure=True,
            )
        readback = self._checked(
            ("exec-out", "run-as", self.APP_ID, "cat", self.PREFERENCE_PATH),
            timeout=30.0,
        ).stdout
        if readback != raw:
            raise ReferencePackageInstallError("preference restoration readback differs")

    def restore_running(self, was_running: bool) -> None:
        if was_running:
            self._checked(
                ("shell", "am", "start", "-n", self.MAIN_ACTIVITY), timeout=60.0
            )
        else:
            self.force_stop()

    def restore_apk(self, path: Path) -> None:
        expected = _hash_regular_file(path, "prestate APK for restoration")
        self.install_apk(path)
        actual = self.verify_installed_apk()
        if actual.byte_length != expected.byte_length or actual.sha256 != expected.sha256:
            raise ReferencePackageInstallError("restored APK identity differs")

    def remove_tree(self, path: str) -> None:
        leaf = path.rsplit("/", 1)[-1]
        safe_token = re.fullmatch(
            rf"\.{re.escape(PACKAGE_ID)}\.exp8-install-[0-9a-f]{{32}}\.(?:stage|backup|failed)",
            leaf,
        )
        if path != ReferencePackageInstaller.FINAL_PACKAGE_PATH and safe_token is None:
            raise ReferencePackageInstallError("refusing unsafe device tree removal")
        if not path.startswith(ReferencePackageInstaller.REFERENCE_ROOT + "/"):
            raise ReferencePackageInstallError("device removal is outside reference root")
        self._checked(("shell", "rm", "-rf", "--", path), timeout=180.0)
        if self.path_exists(path):
            raise ReferencePackageInstallError("device tree remains after removal")

    def verify_installed_apk(self) -> RemoteFileIdentity:
        evidence = self._require_evidence_directory()
        remote = self._installed_base_path()
        self._verification_counter += 1
        local = evidence / f"installed-verification-{self._verification_counter:03d}.apk"
        self._checked(("pull", remote, str(local)), timeout=180.0)
        installed = _hash_regular_file(local, "installed APK verification")
        return RemoteFileIdentity(
            byte_length=installed.byte_length, sha256=installed.sha256
        )

    def _installed_base_path(self) -> str:
        result = self._checked(("shell", "pm", "path", self.APP_ID), timeout=30.0)
        text = _strict_ascii_text(result.stdout, "installed APK path", maximum=128 * 1024)
        rows = [line for line in text.splitlines() if line]
        paths = [line[len("package:") :] for line in rows if line.startswith("package:")]
        if len(rows) != 1 or len(paths) != 1 or not paths[0].endswith("/base.apk"):
            raise ReferencePackageInstallError("installed product is not one base APK")
        return paths[0]

    def _checked(
        self, arguments: Sequence[str], *, timeout: float | None
    ) -> CommandResult:
        result = self._adb(arguments, timeout=timeout, allow_failure=True)
        if result.return_code != 0:
            raise ReferencePackageInstallError(
                f"adb command failed ({arguments[0]}): {_bounded_error(result.stderr)}"
            )
        return result

    def _adb(
        self,
        arguments: Sequence[str],
        *,
        timeout: float | None,
        allow_failure: bool,
    ) -> CommandResult:
        serial = self._require_serial()
        result = self.runner.run(
            (self.adb, "-s", serial, *arguments), timeout_seconds=timeout
        )
        if not allow_failure and result.return_code != 0:
            raise ReferencePackageInstallError(
                f"adb command failed: {_bounded_error(result.stderr)}"
            )
        return result

    def _require_serial(self) -> str:
        if self.serial is None:
            raise ReferencePackageInstallError("adb device was not selected")
        return self.serial

    def _require_evidence_directory(self) -> Path:
        if self.evidence_directory is None:
            raise ReferencePackageInstallError("device prestate was not captured")
        return self.evidence_directory


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Validate or transactionally install Flight Alert Experiment 8."
    )
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--final-result", required=True, type=Path)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--validate-only", action="store_true")
    mode.add_argument("--execute", action="store_true")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--powershell", default="powershell.exe")
    parser.add_argument("--lease-helper", type=Path)
    parser.add_argument("--thread-id")
    parser.add_argument("--evidence-directory", type=Path)
    parsed = parser.parse_args(arguments)
    plan = HostInstallPlan.validate(
        package_directory=parsed.package,
        apk_path=parsed.apk,
        final_result_path=parsed.final_result,
    )
    if parsed.validate_only:
        print(_canonical_json_bytes(_plan_document(plan)).decode("utf-8"), end="")
        return 0
    if parsed.lease_helper is None or parsed.thread_id is None or parsed.evidence_directory is None:
        parser.error("--execute requires --lease-helper, --thread-id, and --evidence-directory")
    evidence = parsed.evidence_directory.resolve(strict=False)
    repository = Path(__file__).resolve().parents[2]
    if _is_within(evidence, repository):
        parser.error("installation evidence must stay outside the repository")
    runner = SubprocessCommandRunner()
    lease = AtomicDeviceLeaseClient(
        helper_path=parsed.lease_helper,
        thread_id=parsed.thread_id,
        runner=runner,
        powershell=parsed.powershell,
    )
    device = AdbInstallDevice(adb=parsed.adb, runner=runner)
    installer = ReferencePackageInstaller(
        plan=plan,
        device=device,
        lease=lease,
        evidence_directory=evidence,
    )
    installer.install()
    receipt_path = evidence / "install-receipt.json"
    print(receipt_path.read_text("utf-8"), end="")
    return 0


def _plan_document(plan: HostInstallPlan) -> dict[str, object]:
    return {
        "apk": {
            "bytes": plan.apk_bytes,
            "path": str(plan.apk_path),
            "sha256": plan.apk_sha256,
        },
        "files": [
            {"bytes": item.byte_length, "name": item.name, "sha256": item.sha256}
            for item in plan.package_files
        ],
        "hardStrictlyBelow": plan.hard_strictly_below,
        "packageBytes": plan.package_bytes,
        "packageId": plan.package_id,
        "packagePath": str(plan.package_directory),
        "preferredStrictlyBelow": plan.preferred_strictly_below,
        "schema": "flightalert.experiment8.reference-install-plan.v1",
        "totalFootprintBytes": plan.total_footprint_bytes,
    }


def _validate_receipts(
    *,
    manifest: Mapping[str, object],
    merge_receipt: Mapping[str, object],
    finalization_receipt: Mapping[str, object],
    file_by_name: Mapping[str, InstallFile],
) -> None:
    for receipt, label in (
        (merge_receipt, "merge receipt"),
        (finalization_receipt, "finalization receipt"),
    ):
        if _exact_string(receipt.get("packageId"), f"{label} package ID") != PACKAGE_ID:
            raise ReferencePackageInstallError(f"{label} package ID differs")

    merge = _exact_mapping(manifest.get("merge"), "manifest merge contract")
    merger_sha256 = _exact_sha256(
        merge.get("mergerSha256"), "manifest merger SHA-256"
    )
    if _exact_sha256(
        merge_receipt.get("mergerSha256"), "merge receipt merger SHA-256"
    ) != merger_sha256:
        raise ReferencePackageInstallError("merge receipt merger SHA-256 differs")
    current_merger = _hash_tool("v3_package_merger.py")
    if merger_sha256 != current_merger:
        raise ReferencePackageInstallError("merger source SHA-256 differs")

    finalizer_sha256 = _exact_sha256(
        finalization_receipt.get("finalizerSha256"),
        "finalization receipt finalizer SHA-256",
    )
    if finalizer_sha256 != _hash_tool("v3_class_catalog_finalizer.py"):
        raise ReferencePackageInstallError("finalizer source SHA-256 differs")

    final_bindings = _binding_map(
        finalization_receipt.get("outputFiles"),
        "finalization receipt output files",
        RUNTIME_FILE_NAMES,
    )
    for name in RUNTIME_FILE_NAMES:
        actual = file_by_name[name]
        expected = final_bindings[name]
        if (
            actual.byte_length != expected[0]
            or actual.sha256 != expected[1]
        ):
            raise ReferencePackageInstallError(
                f"finalization receipt differs for {name}"
            )

    merged_bindings = _binding_map(
        merge_receipt.get("outputFiles"),
        "merge receipt output files",
        MERGED_FILE_NAMES,
    )
    for name in ("records.fadictpack", "tile-index.bin"):
        actual = file_by_name[name]
        expected = merged_bindings[name]
        if actual.byte_length != expected[0] or actual.sha256 != expected[1]:
            raise ReferencePackageInstallError(f"merge receipt differs for {name}")

    output = _exact_mapping(merge.get("output"), "manifest merge output")
    for name, byte_key, sha_key in (
        ("records.fadictpack", "recordsBytes", "recordsSha256"),
        ("tile-index.bin", "tileIndexBytes", "tileIndexSha256"),
    ):
        actual = file_by_name[name]
        if _exact_integer(output.get(byte_key), f"manifest {byte_key}") != actual.byte_length:
            raise ReferencePackageInstallError(f"manifest differs for {name} bytes")
        if _exact_sha256(output.get(sha_key), f"manifest {sha_key}") != actual.sha256:
            raise ReferencePackageInstallError(f"manifest differs for {name} SHA-256")

    catalog_contract = _exact_mapping(
        manifest.get("classCatalog"), "manifest class catalog"
    )
    if _exact_sha256(
        catalog_contract.get("catalogSha256"), "manifest catalog SHA-256"
    ) != file_by_name["class-catalog.bin"].sha256:
        raise ReferencePackageInstallError("manifest class catalog SHA-256 differs")
    semantic = _exact_sha256(
        manifest.get("rendererSemanticStreamSha256"),
        "manifest renderer semantic stream SHA-256",
    )
    if _exact_sha256(
        catalog_contract.get("rendererContractSha256"),
        "manifest renderer contract SHA-256",
    ) != semantic:
        raise ReferencePackageInstallError("manifest renderer contract differs")
    if _exact_sha256(
        finalization_receipt.get("rendererSemanticStreamSha256"),
        "finalization renderer semantic stream SHA-256",
    ) != semantic:
        raise ReferencePackageInstallError("finalization semantic stream differs")
    final_coverage = _exact_mapping(
        finalization_receipt.get("coverage"), "finalization coverage"
    )
    if _exact_integer(
        final_coverage.get("missingTileCount"), "finalization missing tile count"
    ) != 0:
        raise ReferencePackageInstallError("final package has missing declared tiles")


def _validate_result_identity(
    *,
    result: Mapping[str, object],
    package_directory: Path,
    package_bytes: int,
    apk: InstallFile,
) -> None:
    if result.get("schema") != "flightalert.experiment8.final-package-monitor-result.v1":
        raise ReferencePackageInstallError("final result schema differs")
    if result.get("state") != "complete":
        raise ReferencePackageInstallError("final result is not complete")
    package = _exact_mapping(result.get("package"), "final result package")
    if _exact_string(package.get("packageId"), "final result package ID") != PACKAGE_ID:
        raise ReferencePackageInstallError("final result package ID differs")
    if not _same_path(
        _exact_string(package.get("path"), "final result package path"),
        package_directory,
    ):
        raise ReferencePackageInstallError("final result package path differs")
    if _exact_integer(package.get("bytes"), "final result package bytes") != package_bytes:
        raise ReferencePackageInstallError("final result package bytes differ")

    apk_result = _exact_mapping(result.get("apk"), "final result APK")
    if not _same_path(
        _exact_string(apk_result.get("path"), "final result APK path"), apk.path
    ):
        raise ReferencePackageInstallError("final result APK path differs")
    if _exact_integer(apk_result.get("bytes"), "final result APK bytes") != apk.byte_length:
        raise ReferencePackageInstallError("final result APK bytes differ")
    if _exact_sha256(
        apk_result.get("sha256"), "final result APK SHA-256"
    ) != apk.sha256:
        raise ReferencePackageInstallError("final result APK SHA-256 differs")

    footprint = _exact_mapping(result.get("footprint"), "final result footprint")
    expected_scalars = (
        ("mandatoryReserveBytes", MANDATORY_RESERVE_BYTES),
        ("preferredCeilingBytes", PREFERRED_FOOTPRINT_CEILING_BYTES),
        ("hardCeilingBytes", HARD_FOOTPRINT_CEILING_BYTES),
    )
    for key, expected in expected_scalars:
        if _exact_integer(footprint.get(key), f"final result {key}") != expected:
            raise ReferencePackageInstallError(f"final result {key} differs")


def _binding_map(
    value: object,
    label: str,
    expected_names: Sequence[str],
) -> dict[str, tuple[int, str]]:
    if type(value) is not list:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    bindings: dict[str, tuple[int, str]] = {}
    for index, item in enumerate(value):
        document = _exact_mapping(item, f"{label}[{index}]")
        name = _exact_string(document.get("name"), f"{label}[{index}] name")
        if name in bindings:
            raise ReferencePackageInstallError(f"{label} repeats {name}")
        bindings[name] = (
            _exact_integer(document.get("bytes"), f"{label}[{index}] bytes"),
            _exact_sha256(document.get("sha256"), f"{label}[{index}] SHA-256"),
        )
    if tuple(sorted(bindings)) != tuple(sorted(expected_names)):
        raise ReferencePackageInstallError(f"{label} inventory differs")
    return bindings


def _read_strict_json(path: Path, label: str, *, canonical: bool) -> dict[str, object]:
    raw = _read_bounded_regular_file(path, label, MAX_JSON_BYTES)
    try:
        document = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
    except ReferencePackageInstallError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReferencePackageInstallError(f"{label} is not strict JSON") from error
    if type(document) is not dict:
        raise ReferencePackageInstallError(f"{label} root has the wrong exact type")
    if canonical and _canonical_json_bytes(document) != raw:
        raise ReferencePackageInstallError(f"{label} is not canonical JSON")
    return document


def _read_bounded_regular_file(path: Path, label: str, maximum: int) -> bytes:
    path = _real_file(path, label)
    before = path.stat(follow_symlinks=False)
    if before.st_size <= 0 or before.st_size > maximum:
        raise ReferencePackageInstallError(f"{label} byte length is outside its bound")
    try:
        with path.open("rb") as handle:
            opened = os.fstat(handle.fileno())
            if _identity(opened) != _identity(before):
                raise ReferencePackageInstallError(f"{label} changed before read")
            raw = handle.read(maximum + 1)
            after_handle = os.fstat(handle.fileno())
    except OSError as error:
        raise ReferencePackageInstallError(f"{label} is not readable") from error
    after_path = path.stat(follow_symlinks=False)
    if len(raw) != before.st_size or len(raw) > maximum:
        raise ReferencePackageInstallError(f"{label} byte length changed during read")
    if (
        _identity(after_handle) != _identity(before)
        or _identity(after_path) != _identity(before)
    ):
        raise ReferencePackageInstallError(f"{label} changed during read")
    return raw


def _hash_regular_file(path: Path, label: str) -> InstallFile:
    path = _real_file(path, label)
    before = path.stat(follow_symlinks=False)
    digest = hashlib.sha256()
    total = 0
    try:
        with path.open("rb") as handle:
            opened = os.fstat(handle.fileno())
            if _identity(opened) != _identity(before):
                raise ReferencePackageInstallError(f"{label} changed before hash")
            while True:
                chunk = handle.read(HASH_CHUNK_BYTES)
                if not chunk:
                    break
                total += len(chunk)
                digest.update(chunk)
            after_handle = os.fstat(handle.fileno())
    except OSError as error:
        raise ReferencePackageInstallError(f"{label} is not readable") from error
    after_path = path.stat(follow_symlinks=False)
    if total != before.st_size:
        raise ReferencePackageInstallError(f"{label} length changed during hash")
    if (
        _identity(after_handle) != _identity(before)
        or _identity(after_path) != _identity(before)
    ):
        raise ReferencePackageInstallError(f"{label} changed during hash")
    return InstallFile(
        name=path.name,
        path=path,
        byte_length=total,
        sha256=digest.hexdigest(),
        stat_identity=_identity(before),
    )


def _assert_install_file_unchanged(item: InstallFile) -> None:
    try:
        information = item.path.stat(follow_symlinks=False)
    except OSError as error:
        raise ReferencePackageInstallError(
            f"{item.name} changed after validation"
        ) from error
    if (
        not stat.S_ISREG(information.st_mode)
        or _is_reparse(information)
        or _identity(information) != item.stat_identity
    ):
        raise ReferencePackageInstallError(
            f"{item.name} changed after validation"
        )


def _real_directory(path: Path, label: str) -> Path:
    try:
        information = path.stat(follow_symlinks=False)
    except OSError as error:
        raise ReferencePackageInstallError(f"{label} is missing") from error
    if not stat.S_ISDIR(information.st_mode) or _is_reparse(information):
        raise ReferencePackageInstallError(f"{label} is not one real directory")
    return path.resolve(strict=True)


def _real_file(path: Path, label: str) -> Path:
    try:
        information = path.stat(follow_symlinks=False)
    except OSError as error:
        raise ReferencePackageInstallError(f"{label} is missing") from error
    if not stat.S_ISREG(information.st_mode) or _is_reparse(information):
        raise ReferencePackageInstallError(f"{label} is not one regular file")
    return path.resolve(strict=True)


def _identity(information: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        information.st_dev,
        information.st_ino,
        information.st_size,
        information.st_mtime_ns,
        information.st_mode,
    )


def _is_reparse(information: os.stat_result) -> bool:
    return bool(
        getattr(information, "st_file_attributes", 0)
        & FILE_ATTRIBUTE_REPARSE_POINT
    )


def _hash_tool(name: str) -> str:
    path = Path(__file__).resolve().parent / name
    return _hash_regular_file(path, f"tool {name}").sha256


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
        raise ReferencePackageInstallError("canonical JSON encoding failed") from error


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    document: dict[str, object] = {}
    for key, value in pairs:
        if key in document:
            raise ReferencePackageInstallError(f"JSON repeats key {key!r}")
        document[key] = value
    return document


def _reject_constant(value: str) -> object:
    raise ReferencePackageInstallError(f"JSON contains forbidden constant {value}")


def _exact_mapping(value: object, label: str) -> Mapping[str, object]:
    if type(value) is not dict:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    return value


def _exact_string(value: object, label: str) -> str:
    if type(value) is not str or not value:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    return value


def _exact_integer(value: object, label: str) -> int:
    if type(value) is not int or value < 0:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    return value


def _exact_bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    return value


def _exact_sha256(value: object, label: str) -> str:
    text = _exact_string(value, label)
    if HEX_SHA256.fullmatch(text) is None:
        raise ReferencePackageInstallError(f"{label} is not exact lowercase SHA-256")
    return text


def _same_path(document_path: str, actual: Path) -> bool:
    try:
        expected = Path(document_path).resolve(strict=True)
    except OSError:
        return False
    return os.path.normcase(str(expected)) == os.path.normcase(str(actual))


def _parse_strict_json_bytes(raw: bytes, label: str) -> dict[str, object]:
    if type(raw) is not bytes or not raw or len(raw) > MAX_JSON_BYTES:
        raise ReferencePackageInstallError(f"{label} bytes are outside the bound")
    try:
        document = json.loads(
            raw.decode("utf-8-sig", "strict"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_constant,
        )
    except ReferencePackageInstallError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReferencePackageInstallError(f"{label} is not strict JSON") from error
    if type(document) is not dict:
        raise ReferencePackageInstallError(f"{label} root has the wrong exact type")
    return document


def _strict_ascii_text(raw: bytes, label: str, *, maximum: int) -> str:
    if type(raw) is not bytes or len(raw) > maximum or b"\0" in raw:
        raise ReferencePackageInstallError(f"{label} bytes are malformed")
    try:
        return raw.decode("ascii", "strict").replace("\r\n", "\n")
    except UnicodeError as error:
        raise ReferencePackageInstallError(f"{label} is not strict ASCII") from error


def _required_argument(arguments: Mapping[str, object], name: str) -> str:
    value = arguments.get(name)
    if type(value) is not str or not value.strip():
        raise ReferencePackageInstallError(f"device lease {name} is required")
    return value


def _bounded_error(raw: bytes) -> str:
    if type(raw) is not bytes:
        return "unavailable"
    return raw[:4096].decode("utf-8", "replace").strip() or "no stderr"


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


if __name__ == "__main__":
    try:
        raise SystemExit(_main())
    except ReferencePackageInstallError as error:
        raise SystemExit(f"error: {error}") from error


__all__ = [
    "AdbInstallDevice",
    "AtomicDeviceLeaseClient",
    "CommandResult",
    "DevicePrestate",
    "HostInstallPlan",
    "InstallFile",
    "ReferencePackageInstaller",
    "ReferencePackageInstallError",
    "RemoteFileIdentity",
]
