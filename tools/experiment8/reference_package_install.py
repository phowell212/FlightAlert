"""Fail-closed host and device installation for the Experiment 8 V3 package."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import stat
import subprocess
import tempfile
import threading
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping, Protocol, Sequence

from .reference_size_policy import (
    BUDGETED_RELEASE_V1,
    COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
    DESTINATION_RESERVE_BYTES,
    REFERENCE_SIZE_POLICY_MODES,
    evaluate_reference_size_policy,
    reference_size_policy_document,
)


PACKAGE_ID = "world-experiment8-binary-v4"
RUNTIME_SCHEMA_VERSION = 3
RUNTIME_PAYLOAD_SCHEMA = "flightalert.reference.renderer-tile.v1"
RUNTIME_PRESENTATION_POLICY_SHA256 = (
    "dce9bd4b789c0528318dbb184e17efe7465f444550ba0180efd82e1cb7219154"
)
RUNTIME_SOURCED_TEXT_POLICY_SHA256 = (
    "ca7bd559b3d84758d17e11b5e619f04da7e5093d67d0f90506ab509e2ef6543a"
)
RUNTIME_UNICODE_SCRIPT_PROFILE_SHA256 = (
    "4df49aafa0b507ca5517277c5f3db7faf855196a4b3a2124f4fae4e1f386fbeb"
)
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
INSTALL_POLICY_RELEASE = "release"
INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION = (
    "full-fidelity-visual-evaluation"
)
INSTALL_POLICIES = (
    INSTALL_POLICY_RELEASE,
    INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION,
)
FINAL_RESULT_STATE_COMPLETE = "complete"
FINAL_RESULT_STATE_FAILED_HARD_CEILING = "failed-hard-ceiling"
AUTHORITY_MERGE_SCHEMA = "flightalert.experiment8.v3-package-merge.v2"
AUTHORITY_MERGE_RECEIPT_SCHEMA = (
    "flightalert.experiment8.v3-package-merge-receipt.v2"
)
AUTHORITY_FINALIZATION_RECEIPT_SCHEMA = (
    "flightalert.experiment8.v3-class-catalog-finalization-receipt.v2"
)
RECEIPT_BOUND_VISUAL_AUTHORITY_SEMANTIC_VERIFICATION = {
    "mode": "receipt-bound-visual-evaluation",
    "runtimeFileDigestsVerifiedByMergeStream": True,
    "strictDocumentaryProofDeferred": True,
}
FINAL_SIZE_ACCOUNTING_SCOPE = (
    "final-six-file-package-after-class-catalog-finalization"
)
WATERWAY_BUILD_RECEIPT_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-build.v2"
)
WATERWAY_BUILD_RECEIPT_FIELDS = {
    "admission",
    "attribution",
    "build",
    "catalogCountsClaimed",
    "closureAudit",
    "finalSemanticIdentitySha256",
    "outputFiles",
    "packageId",
    "peakResources",
    "projection",
    "rendererSemanticStreamSha256",
    "rendererTextAudit",
    "schema",
    "source",
}
MAX_JSON_BYTES = 4 * 1024 * 1024
HASH_CHUNK_BYTES = 4 * 1024 * 1024
HEX_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
FILE_ATTRIBUTE_REPARSE_POINT = 0x400
MAX_SIGNED_64 = (1 << 63) - 1


class ReferencePackageInstallError(RuntimeError):
    """The source package, evidence, or device transaction is not acceptable."""


class _EvidenceInvocationLock:
    def __init__(self, path: Path) -> None:
        self.path = Path(path)
        self._file: object | None = None

    def __enter__(self) -> "_EvidenceInvocationLock":
        if not self.path.parent.is_dir():
            raise ReferencePackageInstallError(
                "evidence invocation lock parent is missing"
            )
        handle = self.path.open("a+b", buffering=0)
        try:
            if self.path.stat().st_size == 0:
                handle.write(b"\0")
            handle.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, ValueError) as error:
            handle.close()
            raise ReferencePackageInstallError(
                "another live installer invocation is already active"
            ) from error
        self._file = handle
        return self

    def __exit__(self, *_: object) -> None:
        handle = self._file
        self._file = None
        if handle is None:
            return
        try:
            handle.seek(0)
            if os.name == "nt":
                import msvcrt

                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()


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
    package_directory_identity: tuple[int, int, int, int, int]
    package_inventory: tuple[tuple[str, tuple[int, int, int, int, int]], ...]
    package_id: str
    package_files: tuple[InstallFile, ...]
    package_bytes: int
    apk_path: Path
    apk_bytes: int
    apk_sha256: str
    apk_stat_identity: tuple[int, int, int, int, int]
    mandatory_reserve_bytes: int
    total_footprint_bytes: int
    preferred_strictly_below: bool
    hard_strictly_below: bool
    install_policy: str
    declared_scope_complete: bool
    whole_earth_complete: bool

    @classmethod
    def validate(
        cls,
        *,
        package_directory: Path,
        apk_path: Path,
        final_result_path: Path,
        install_policy: str = INSTALL_POLICY_RELEASE,
        require_install_policy_binding: bool | None = None,
    ) -> "HostInstallPlan":
        install_policy = _validated_install_policy(install_policy)
        if require_install_policy_binding is None:
            require_install_policy_binding = (
                install_policy
                == INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION
            )
        elif type(require_install_policy_binding) is not bool:
            raise ReferencePackageInstallError(
                "install policy binding requirement has the wrong exact type"
            )
        release_policy = install_policy == INSTALL_POLICY_RELEASE
        package_directory = _real_directory(
            Path(package_directory), "package directory"
        )
        package_directory_identity = _identity(
            package_directory.stat(follow_symlinks=False)
        )
        package_inventory = _host_directory_inventory(package_directory)
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
        if release_policy and package_bytes >= HARD_PACKAGE_CEILING_BYTES:
            raise ReferencePackageInstallError(
                "package is not strictly below the 38,500,000,000-byte ceiling"
            )

        manifest = _read_strict_json(
            package_directory / "manifest.json", "manifest", canonical=True
        )
        if _exact_string(manifest.get("packageId"), "manifest package ID") != PACKAGE_ID:
            raise ReferencePackageInstallError("manifest package ID differs")
        if (
            _exact_integer(manifest.get("schemaVersion"), "manifest schema version")
            != RUNTIME_SCHEMA_VERSION
        ):
            raise ReferencePackageInstallError("manifest schema version differs")
        if (
            _exact_string(manifest.get("payloadSchema"), "manifest payload schema")
            != RUNTIME_PAYLOAD_SCHEMA
        ):
            raise ReferencePackageInstallError("manifest payload schema differs")
        if (
            _exact_sha256(
                manifest.get("presentationPolicySha256"),
                "manifest presentation policy SHA-256",
            )
            != RUNTIME_PRESENTATION_POLICY_SHA256
        ):
            raise ReferencePackageInstallError(
                "manifest presentation policy identity differs"
            )
        if (
            _exact_sha256(
                manifest.get("sourcedTextPolicySha256"),
                "manifest sourced-text policy SHA-256",
            )
            != RUNTIME_SOURCED_TEXT_POLICY_SHA256
        ):
            raise ReferencePackageInstallError(
                "manifest sourced-text policy identity differs"
            )
        if (
            _exact_sha256(
                manifest.get("unicodeScriptProfileSha256"),
                "manifest Unicode script profile SHA-256",
            )
            != RUNTIME_UNICODE_SCRIPT_PROFILE_SHA256
        ):
            raise ReferencePackageInstallError(
                "manifest Unicode script profile identity differs"
            )
        coverage = _exact_mapping(manifest.get("coverage"), "manifest coverage")
        declared_scope_complete = _exact_bool(
            coverage.get("completeDeclaredScope"),
            "manifest complete-declared-scope claim",
        )
        if release_policy and not declared_scope_complete:
            raise ReferencePackageInstallError("package declared scope is incomplete")
        whole_earth_complete = _exact_bool(
            coverage.get("completeWholeEarthDictionary"),
            "manifest whole-earth claim",
        )
        if release_policy and not whole_earth_complete:
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
            require_primary_whole_earth=(release_policy or whole_earth_complete),
            install_policy=install_policy,
        )

        apk = _hash_regular_file(apk_path, "APK")
        result = _read_strict_json(final_result_path, "final result", canonical=False)
        result_state = _validate_result_identity(
            result=result,
            package_directory=package_directory,
            package_bytes=package_bytes,
            apk=apk,
            install_policy=install_policy,
            require_install_policy_binding=require_install_policy_binding,
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
        if hard and result_state != FINAL_RESULT_STATE_COMPLETE:
            raise ReferencePackageInstallError(
                "final result hard-ceiling state contradicts its footprint"
            )
        if not hard and result_state != FINAL_RESULT_STATE_FAILED_HARD_CEILING:
            raise ReferencePackageInstallError(
                "final result hard-ceiling state contradicts its footprint"
            )
        if release_policy and not hard:
            raise ReferencePackageInstallError(
                "complete footprint is not strictly below 40,000,000,000 bytes"
            )

        for item in (*package_files, apk):
            _assert_install_file_unchanged(item)
        if (
            _identity(package_directory.stat(follow_symlinks=False))
            != package_directory_identity
            or _host_directory_inventory(package_directory) != package_inventory
        ):
            raise ReferencePackageInstallError(
                "package directory inventory changed during validation"
            )

        return cls(
            package_directory=package_directory,
            package_directory_identity=package_directory_identity,
            package_inventory=package_inventory,
            package_id=PACKAGE_ID,
            package_files=package_files,
            package_bytes=package_bytes,
            apk_path=apk.path,
            apk_bytes=apk.byte_length,
            apk_sha256=apk.sha256,
            apk_stat_identity=apk.stat_identity,
            mandatory_reserve_bytes=MANDATORY_RESERVE_BYTES,
            total_footprint_bytes=total,
            preferred_strictly_below=preferred,
            hard_strictly_below=hard,
            install_policy=install_policy,
            declared_scope_complete=declared_scope_complete,
            whole_earth_complete=whole_earth_complete,
        )


@dataclass(frozen=True)
class RemoteFileIdentity:
    byte_length: int
    sha256: str


@dataclass(frozen=True)
class RemoteEntryIdentity:
    name: str
    kind: str
    device: int
    inode: int

    def same_object(self, other: object) -> bool:
        return (
            isinstance(other, RemoteEntryIdentity)
            and self.kind == other.kind
            and self.device == other.device
            and self.inode == other.inode
        )


@dataclass(frozen=True)
class ListenerState:
    approval: str
    bound: bool


@dataclass(frozen=True)
class AppProcessState:
    pids: tuple[str, ...]
    resumed_component: str | None
    focused_component: str | None
    screen_interactive: bool = True
    device_locked: bool = False
    active_services: tuple[str, ...] = ()
    running_reason: str = ""

    def __post_init__(self) -> None:
        if (
            type(self.screen_interactive) is not bool
            or type(self.device_locked) is not bool
        ):
            raise ReferencePackageInstallError("screen/lock process state is malformed")
        if any(
            type(component) is not str
            or not component.startswith("com.flightalert/")
            for component in self.active_services
        ) or tuple(sorted(set(self.active_services))) != self.active_services:
            raise ReferencePackageInstallError("active Flight Alert services are malformed")
        reason = self.running_reason
        if not reason:
            if not self.pids:
                reason = "stopped"
            elif (
                self.screen_interactive
                and not self.device_locked
                and self.resumed_component is not None
                and self.resumed_component.startswith("com.flightalert/")
            ):
                reason = "foreground-activity"
            elif self.active_services:
                reason = "service"
            else:
                reason = "background-only"
            object.__setattr__(self, "running_reason", reason)
        if reason not in {
            "stopped",
            "foreground-activity",
            "bound-notification-listener",
            "service",
            "background-only",
        }:
            raise ReferencePackageInstallError("process running reason is malformed")
        if (reason == "stopped" and self.pids) or (
            reason != "stopped" and not self.pids
        ):
            raise ReferencePackageInstallError("process running reason contradicts PIDs")

    @property
    def running(self) -> bool:
        return bool(self.pids)


@dataclass(frozen=True)
class StorageFootprint:
    logical_bytes: int
    allocated_bytes: int

    def __post_init__(self) -> None:
        if (
            type(self.logical_bytes) is not int
            or type(self.allocated_bytes) is not int
            or self.logical_bytes < 0
            or self.allocated_bytes < 0
        ):
            raise ReferencePackageInstallError("storage footprint is malformed")

    @property
    def counted_bytes(self) -> int:
        return max(self.logical_bytes, self.allocated_bytes)


@dataclass(frozen=True)
class DevicePrestate:
    serial: str
    apk_backup_path: Path
    apk_bytes: int
    apk_sha256: str
    preferences: bytes = b""
    app_was_running: bool = False
    final_package_was_present: bool = False
    preferences_present: bool = True
    listener_state: ListenerState = ListenerState("", False)
    process_state: AppProcessState = AppProcessState((), None, None)
    reference_inventory: tuple[RemoteEntryIdentity, ...] = ()
    final_package_entry: RemoteEntryIdentity | None = None


class DeviceLease(Protocol):
    def acquisition_intent(self, **arguments: object) -> Mapping[str, object]: ...

    def acquire(self, **arguments: object) -> str: ...

    def heartbeat(self, token: str) -> None: ...

    def release(self, token: str) -> None: ...

    def confirm_acquisition(self, intent: Mapping[str, object]) -> str | None: ...

    def reconcile_acquisition(self, intent: Mapping[str, object]) -> None: ...

    def reconcile_unfinished(
        self, token: str, intent: Mapping[str, object] | None = None
    ) -> None: ...


class InstallDevice(Protocol):
    def set_mutation_guard(self, guard: Callable[[], None] | None) -> None: ...

    def require_single_ready_device(self) -> str: ...

    def prepare_evidence_directory(self, evidence_directory: Path) -> None: ...

    def capture_prestate(
        self, evidence_directory: Path, final_package_path: str
    ) -> DevicePrestate: ...

    def available_bytes(self, path: str) -> int: ...

    def path_exists(self, path: str) -> bool: ...

    def entry_identity(self, path: str) -> RemoteEntryIdentity | None: ...

    def immediate_inventory(self, root: str) -> tuple[RemoteEntryIdentity, ...]: ...

    def classify_reference_entry(
        self, root: str, entry: RemoteEntryIdentity
    ) -> str: ...

    def make_directory(self, path: str) -> None: ...

    def make_directory_no_replace(self, path: str) -> RemoteEntryIdentity: ...

    def push_file(self, local: Path, remote: str) -> None: ...

    def remote_file_identity(self, path: str) -> RemoteFileIdentity: ...

    def force_stop(self) -> None: ...

    def listener_state(self) -> ListenerState: ...

    def disallow_listener(self) -> None: ...

    def app_process_state(self) -> AppProcessState: ...

    def move_no_replace(self, source: str, destination: str) -> None: ...

    def install_apk(
        self,
        path: Path,
        *,
        expected_bytes: int,
        expected_sha256: str,
        expected_stat_identity: tuple[int, int, int, int, int],
    ) -> None: ...

    def restore_preferences(self, present: bool, raw: bytes) -> None: ...

    def preference_state(self) -> tuple[bool, bytes]: ...

    def restore_listener(self, state: ListenerState) -> None: ...

    def restore_process_state(self, state: AppProcessState) -> None: ...

    def restore_apk(self, path: Path) -> None: ...

    def remove_tree(self, path: str) -> None: ...

    def remove_bound_entry(
        self, path: str, expected: RemoteEntryIdentity
    ) -> None: ...

    def verify_installed_apk(self) -> RemoteFileIdentity: ...

    def storage_footprint(
        self, path: str, *, private: bool = False
    ) -> StorageFootprint: ...


class ReferencePackageInstaller:
    REFERENCE_ROOT = (
        "/storage/emulated/0/Android/data/com.flightalert/files/reference"
    )
    FINAL_PACKAGE_PATH = f"{REFERENCE_ROOT}/{PACKAGE_ID}"

    def __init__(
        self,
        *,
        plan: HostInstallPlan,
        device: InstallDevice,
        lease: DeviceLease,
        evidence_directory: Path,
        phase_hook: Callable[[str], None] | None = None,
    ) -> None:
        self.plan = plan
        self.device = device
        self.lease = lease
        self.evidence_directory = Path(evidence_directory)
        self.phase_hook = phase_hook

    def _invocation_lock_path(self) -> Path:
        canonical_evidence = os.path.normcase(
            str(self.evidence_directory.resolve(strict=False))
        )
        identity = hashlib.sha256(
            canonical_evidence.encode("utf-8", "strict")
        ).hexdigest()
        return Path(tempfile.gettempdir()) / f".flightalert-exp8-{identity}.lock"

    def install(self) -> None:
        with _EvidenceInvocationLock(self._invocation_lock_path()):
            self._install_under_invocation_lock()

    def _install_under_invocation_lock(self) -> None:
        _assert_host_plan_unchanged(self.plan)
        journal = self._load_or_create_install_journal()
        journal = self._reconcile_journal_lease(journal)
        if journal.get("state") == "pending-acceptance":
            if (self.evidence_directory / "pending-acceptance.json").is_file():
                raise ReferencePackageInstallError(
                    "installation is already pending explicit acceptance"
                )
            self._republish_pending_receipt(journal)
            return
        if journal.get("state") in {"accepted", "rolled-back"}:
            raise ReferencePackageInstallError("transaction journal is already terminal")
        if journal.get("state") == "rollback-in-progress":
            raise ReferencePackageInstallError(
                "rollback is already in progress; execute cannot continue"
            )
        if journal.get("state") != "active":
            raise ReferencePackageInstallError(
                "execute requires one active installation journal"
            )

        token: str | None = None
        released = False
        primary_error: Exception | None = None
        try:
            token = self._acquire_lease(journal, "install", "install")
            serial = self._guarded(token, "select device", self._select_device)
            self.device.prepare_evidence_directory(self.evidence_directory)
            if journal.get("prestate") is None:
                prestate = self._guarded(
                    token,
                    "capture device prestate",
                    lambda: self.device.capture_prestate(
                        self.evidence_directory, self.FINAL_PACKAGE_PATH
                    ),
                )
                self._validate_new_prestate(prestate, serial, token)
                journal = self._bind_prestate(journal, prestate, token)
            else:
                prestate = _prestate_from_document(journal["prestate"])
                if prestate.serial != serial:
                    raise ReferencePackageInstallError(
                        "unfinished journal belongs to a different device"
                    )
                recovery_errors = self._recover_under_lease(journal, prestate, token)
                if recovery_errors:
                    raise ReferencePackageInstallError(
                        "unfinished journal recovery failed: "
                        + " | ".join(recovery_errors)
                    )
            self._execute_install(journal, prestate, token)
        except Exception as error:
            primary_error = error
        finally:
            if token is not None:
                released = self._release_independently(token)
                if released:
                    journal = self._record_lease_released(journal, "install")

        if primary_error is not None:
            recovery_errors: list[str] = []
            if journal.get("prestate") is not None and journal.get(
                "deviceMutationStarted"
            ) is True:
                if not released:
                    recovery_errors.append("primary lease release was not confirmed")
                else:
                    recovery_errors.extend(self._recover_with_fresh_lease(journal))
            self._write_failure_receipt(primary_error, recovery_errors)
            if recovery_errors:
                raise ReferencePackageInstallError(
                    f"{primary_error}; recovery failures: {' | '.join(recovery_errors)}"
                ) from primary_error
            if isinstance(primary_error, ReferencePackageInstallError):
                raise primary_error
            raise ReferencePackageInstallError(str(primary_error)) from primary_error
        if not released:
            raise ReferencePackageInstallError(
                "installation verified but lease release was not confirmed"
            )

        journal["state"] = "pending-acceptance"
        journal = self._write_journal(journal, "pending-acceptance")
        _atomic_write_json(
            self.evidence_directory / "pending-acceptance.json",
            self._pending_receipt(journal),
        )

    def finalize_acceptance(self) -> None:
        if self.plan.install_policy != INSTALL_POLICY_RELEASE:
            raise ReferencePackageInstallError(
                "full-fidelity visual evaluation must remain pending acceptance"
            )
        with _EvidenceInvocationLock(self._invocation_lock_path()):
            self._finalize_under_invocation_lock()

    def _finalize_under_invocation_lock(self) -> None:
        _assert_host_plan_unchanged(self.plan)
        journal = self._load_journal()
        if journal.get("state") not in {"pending-acceptance", "finalizing-committed"}:
            raise ReferencePackageInstallError(
                "finalize requires one pending-acceptance journal"
            )
        self._assert_journal_plan(journal)
        journal = self._reconcile_journal_lease(journal)
        token: str | None = None
        released = False
        footprint: dict[str, object] | None = None
        final_inventory: tuple[RemoteEntryIdentity, ...] | None = None
        try:
            token = self._acquire_lease(
                journal, "finalize acceptance", "finalize"
            )
            serial = self._guarded(token, "select device", self._select_device)
            self.device.prepare_evidence_directory(self.evidence_directory)
            prestate = _prestate_from_document(journal["prestate"])
            if serial != prestate.serial:
                raise ReferencePackageInstallError(
                    "pending journal belongs to a different device"
                )
            if journal.get("state") == "pending-acceptance":
                self._verify_declared_rollback_backup(journal, prestate, token)
            self._verify_new_state(journal, prestate, token)
            if journal.get("state") == "pending-acceptance":
                residue = self._classify_finalize_residue(journal, token)
                unknown = [item for item in residue if item[1] == "unknown"]
                if unknown:
                    raise ReferencePackageInstallError(
                        "reference root contains unidentified residue"
                    )
                footprint = self._measure_footprint(token)
                self._enforce_hard_footprint(footprint)
                journal["state"] = "finalizing-committed"
                journal = self._write_journal(journal, "before-finalize-removal")
            for entry, classification in self._classify_finalize_residue(
                journal, token
            ):
                if classification == "active":
                    continue
                if classification == "unknown":
                    raise ReferencePackageInstallError(
                        "reference root contains unidentified residue"
                    )
                path = f"{self.REFERENCE_ROOT}/{entry.name}"
                journal = self._write_journal(
                    journal, f"before-finalize-remove-{entry.name}"
                )
                self._guarded(
                    token,
                    f"remove {classification}",
                    lambda path=path, entry=entry: self.device.remove_bound_entry(
                        path, entry
                    ),
                )
                journal = self._write_journal(
                    journal, f"after-finalize-remove-{entry.name}"
                )
            self._verify_one_active_package(journal, token)
            self._verify_new_state(journal, prestate, token)
            footprint = self._measure_footprint(token)
            self._enforce_hard_footprint(footprint)
            final_inventory = self._guarded(
                token,
                "capture final reference inventory",
                lambda: self.device.immediate_inventory(self.REFERENCE_ROOT),
            )
            journal = self._write_journal(journal, "finalize-verified")
        finally:
            if token is not None:
                released = self._release_independently(token)
                if released:
                    journal = self._record_lease_released(journal, "finalize")
        if not released or footprint is None or final_inventory is None:
            raise ReferencePackageInstallError(
                "finalization lease release was not confirmed"
            )
        receipt = {
            "deviceSerialSha256": hashlib.sha256(
                _exact_string(journal.get("deviceSerial"), "journal serial").encode(
                    "utf-8", "strict"
                )
            ).hexdigest(),
            "footprint": footprint,
            "package": _plan_document(self.plan),
            "referenceRootInventoryBefore": journal["prestate"][
                "referenceInventory"
            ],
            "referenceRootInventoryFinal": [
                _entry_document(item) for item in final_inventory
            ],
            "schema": "flightalert.experiment8.reference-acceptance.v2",
            "state": "accepted",
            "transactionToken": journal["transactionToken"],
        }
        _atomic_write_json(self.evidence_directory / "final-success.json", receipt)
        journal["state"] = "accepted"
        self._write_journal(journal, "accepted")

    def rollback(self) -> None:
        with _EvidenceInvocationLock(self._invocation_lock_path()):
            self._rollback_under_invocation_lock()

    def _rollback_under_invocation_lock(self) -> None:
        journal = self._load_journal()
        if journal.get("state") not in {
            "pending-acceptance",
            "rollback-in-progress",
            "rollback-verified",
        }:
            raise ReferencePackageInstallError(
                "rollback requires one pending-acceptance journal"
            )
        self._assert_journal_plan(journal)
        journal = self._reconcile_journal_lease(journal)
        if journal.get("state") == "pending-acceptance":
            journal["state"] = "rollback-in-progress"
            journal = self._write_journal(journal, "rollback-started")
        token: str | None = None
        released = False
        errors: list[str] = []
        try:
            token = self._acquire_lease(
                journal, "rollback pending acceptance", "rollback"
            )
            serial = self._guarded(token, "select device", self._select_device)
            self.device.prepare_evidence_directory(self.evidence_directory)
            prestate = _prestate_from_document(journal["prestate"])
            if serial != prestate.serial:
                raise ReferencePackageInstallError(
                    "pending journal belongs to a different device"
                )
            if journal.get("state") != "rollback-verified":
                errors.extend(
                    self._recover_under_lease(
                        journal,
                        prestate,
                        token,
                        completion_state="rollback-in-progress",
                    )
                )
            if not errors:
                self._verify_rolled_back_state(prestate, token)
                journal["state"] = "rollback-verified"
                journal = self._write_journal(journal, "rollback-verified")
        finally:
            if token is not None:
                released = self._release_independently(token)
                if released:
                    journal = self._record_lease_released(journal, "rollback")
        if errors:
            raise ReferencePackageInstallError(
                "rollback recovery failed: " + " | ".join(errors)
            )
        if not released:
            raise ReferencePackageInstallError("rollback lease release was not confirmed")
        _atomic_write_json(
            self.evidence_directory / "rollback-receipt.json",
            {
                "deviceSerialSha256": hashlib.sha256(
                    _exact_string(journal.get("deviceSerial"), "journal serial").encode(
                        "utf-8", "strict"
                    )
                ).hexdigest(),
                "schema": "flightalert.experiment8.reference-rollback.v2",
                "state": "rolled-back",
                "transactionToken": journal["transactionToken"],
                "referenceRootInventoryBefore": journal["prestate"][
                    "referenceInventory"
                ],
            },
        )
        journal["state"] = "rolled-back"
        self._write_journal(journal, "rolled-back")

    def _republish_pending_receipt(self, journal: dict[str, object]) -> None:
        token: str | None = None
        released = False
        try:
            token = self._acquire_lease(
                journal, "revalidate pending acceptance", "pending-receipt"
            )
            serial = self._guarded(token, "select device", self._select_device)
            self.device.prepare_evidence_directory(self.evidence_directory)
            prestate = _prestate_from_document(journal["prestate"])
            if serial != prestate.serial:
                raise ReferencePackageInstallError(
                    "pending journal belongs to a different device"
                )
            self._verify_declared_rollback_backup(journal, prestate, token)
            self._verify_new_state(journal, prestate, token)
        finally:
            if token is not None:
                released = self._release_independently(token)
                if released:
                    journal = self._record_lease_released(
                        journal, "pending-receipt"
                    )
        if not released:
            raise ReferencePackageInstallError(
                "pending receipt lease release was not confirmed"
            )
        _atomic_write_json(
            self.evidence_directory / "pending-acceptance.json",
            self._pending_receipt(journal),
        )

    def _load_or_create_install_journal(self) -> dict[str, object]:
        journal_path = self.evidence_directory / "transaction-journal.json"
        if self.evidence_directory.exists():
            if not journal_path.is_file():
                raise ReferencePackageInstallError(
                    "existing evidence directory has no transaction journal"
                )
            journal = self._load_journal()
            self._assert_journal_plan(journal)
            return journal
        self.evidence_directory.mkdir(parents=True, exist_ok=False)
        journal: dict[str, object] = {
            "activeLeaseToken": None,
            "backupEntry": None,
            "backupInventory": None,
            "deviceSerial": None,
            "deviceMutationStarted": False,
            "leaseAcquisitionIntent": None,
            "leaseReleased": False,
            "newPackageEntry": None,
            "paths": None,
            "phase": "host-plan-bound",
            "phaseSequence": 0,
            "prestate": None,
            "referenceClassifications": None,
            "schema": "flightalert.experiment8.reference-install-journal.v2",
            "sourcePlan": _plan_document(self.plan),
            "stageCreation": None,
            "state": "active",
            "transactionToken": None,
        }
        return self._write_journal(journal, "host-plan-bound")

    def _load_journal(self) -> dict[str, object]:
        path = self.evidence_directory / "transaction-journal.json"
        document = _read_strict_json(path, "transaction journal", canonical=True)
        if document.get("schema") != "flightalert.experiment8.reference-install-journal.v2":
            raise ReferencePackageInstallError("transaction journal schema differs")
        return document

    def _assert_journal_plan(self, journal: Mapping[str, object]) -> None:
        if journal.get("sourcePlan") != _plan_document(self.plan):
            raise ReferencePackageInstallError("transaction journal source plan differs")

    def _bind_prestate(
        self, journal: dict[str, object], prestate: DevicePrestate, token: str
    ) -> dict[str, object]:
        stale_pattern = re.compile(
            rf"\.{re.escape(PACKAGE_ID)}\.exp8-install-[0-9a-f]{{32}}\.(?:stage|backup|failed)\Z"
        )
        if any(stale_pattern.fullmatch(entry.name) for entry in prestate.reference_inventory):
            raise ReferencePackageInstallError(
                "reference root contains a stale transaction path"
            )
        classifications = []
        for entry in prestate.reference_inventory:
            classification = self._guarded(
                token,
                f"classify reference entry {entry.name}",
                lambda entry=entry: self.device.classify_reference_entry(
                    self.REFERENCE_ROOT, entry
                ),
            )
            classifications.append(
                {"classification": classification, "entry": _entry_document(entry)}
            )
        transaction_token = token
        journal["deviceSerial"] = prestate.serial
        journal["transactionToken"] = transaction_token
        journal["paths"] = {
            "backup": f"{self.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{transaction_token}.backup",
            "failed": f"{self.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{transaction_token}.failed",
            "stage": f"{self.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{transaction_token}.stage",
        }
        journal["prestate"] = _prestate_document(prestate)
        journal["referenceClassifications"] = classifications
        journal["leaseReleased"] = False
        return self._write_journal(journal, "prestate-captured")

    def _validate_new_prestate(
        self, prestate: DevicePrestate, serial: str, token: str
    ) -> None:
        if prestate.serial != serial:
            raise ReferencePackageInstallError("device serial changed during prestate")
        if prestate.process_state.running_reason == "background-only":
            raise ReferencePackageInstallError(
                "background-only app state cannot be restored safely"
            )
        if (
            prestate.process_state.running_reason == "bound-notification-listener"
            and not prestate.listener_state.bound
        ):
            raise ReferencePackageInstallError(
                "bound-listener process state has no bound listener"
            )
        if (
            prestate.process_state.running_reason == "service"
            and not prestate.process_state.active_services
        ):
            raise ReferencePackageInstallError(
                "service process state has no restorable service"
            )
        final = self._guarded(
            token,
            "recheck prestate final package identity",
            lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
        )
        if prestate.final_package_entry is None:
            if final is not None:
                raise ReferencePackageInstallError(
                    "final package identity changed during prestate"
                )
        elif final is None or not prestate.final_package_entry.same_object(final):
            raise ReferencePackageInstallError(
                "final package identity changed during prestate"
            )

    def _execute_install(
        self, journal: dict[str, object], prestate: DevicePrestate, token: str
    ) -> None:
        paths = _journal_paths(journal)
        required_free = _required_install_free_bytes(self.plan)
        external_files_root = self.REFERENCE_ROOT.rsplit("/", 1)[0]
        available = self._guarded(
            token,
            "measure free space",
            lambda: self.device.available_bytes(external_files_root),
        )
        if available < required_free:
            raise ReferencePackageInstallError(
                "device free space is below the staged package requirement"
            )
        for path in paths.values():
            entry = self._guarded(
                token,
                f"probe journal path {path.rsplit('/', 1)[-1]}",
                lambda path=path: self.device.entry_identity(path),
            )
            if entry is not None:
                raise ReferencePackageInstallError(
                    "journal-bound installation path still exists after recovery"
                )

        journal["deviceMutationStarted"] = True
        journal = self._write_journal(journal, "before-listener-disallow")
        self._guarded(token, "disallow listener", self.device.disallow_listener)
        disabled_listener = self._guarded(
            token, "verify disabled listener", self.device.listener_state
        )
        if disabled_listener.bound or _approval_contains_listener(
            disabled_listener.approval, AdbInstallDevice.LISTENER_COMPONENT
        ):
            raise ReferencePackageInstallError(
                "notification listener remains approved or bound"
            )
        journal = self._write_journal(journal, "after-listener-disallow")
        self._guarded(token, "force-stop app", self.device.force_stop)
        stopped = self._guarded(
            token, "verify stopped process", self.device.app_process_state
        )
        if stopped.pids or _component_is_app(stopped.resumed_component) or _component_is_app(
            stopped.focused_component
        ):
            raise ReferencePackageInstallError(
                "app still has a PID, resumed component, or focused component"
            )
        journal = self._write_journal(journal, "after-force-stop")
        _assert_host_plan_unchanged(self.plan)

        self._guarded(
            token,
            "create reference root",
            lambda: self.device.make_directory(self.REFERENCE_ROOT),
        )
        journal["stageCreation"] = {
            "path": paths["stage"],
            "state": "authorized-empty-no-replace",
        }
        journal = self._write_journal(journal, "before-stage-create")
        stage_entry = self._guarded(
            token,
            "create stage without replacement",
            lambda: self.device.make_directory_no_replace(paths["stage"]),
        )
        journal["newPackageEntry"] = _entry_document(stage_entry)
        journal["stageCreation"] = {
            "entry": _entry_document(stage_entry),
            "path": paths["stage"],
            "state": "identity-bound",
        }
        journal = self._write_journal(journal, "stage-created")
        order = tuple(
            item for item in self.plan.package_files if item.name != "manifest.json"
        ) + tuple(
            item for item in self.plan.package_files if item.name == "manifest.json"
        )
        for item in order:
            _assert_install_file_unchanged(item)
            remote = f"{paths['stage']}/{item.name}"
            self._guarded(
                token,
                f"push {item.name}",
                lambda item=item, remote=remote: self.device.push_file(item.path, remote),
            )
            actual = self._guarded(
                token,
                f"verify staged {item.name}",
                lambda remote=remote: self.device.remote_file_identity(remote),
            )
            if actual.byte_length != item.byte_length or actual.sha256 != item.sha256:
                raise ReferencePackageInstallError(
                    f"staged device identity differs for {item.name}"
                )
            journal = self._write_journal(journal, f"pushed-{item.name}")

        if prestate.final_package_entry is not None:
            journal = self._write_journal(journal, "before-old-move")
            self._guarded(
                token,
                "move old package to backup",
                lambda: self.device.move_no_replace(
                    self.FINAL_PACKAGE_PATH, paths["backup"]
                ),
            )
            backup = self._guarded(
                token,
                "verify backup package identity",
                lambda: self.device.entry_identity(paths["backup"]),
            )
            if backup is None or not prestate.final_package_entry.same_object(backup):
                raise ReferencePackageInstallError("backup package identity differs")
            journal["backupEntry"] = _entry_document(backup)
            backup_inventory = self._guarded(
                token,
                "capture rollback backup inventory",
                lambda: self.device.immediate_inventory(paths["backup"]),
            )
            journal["backupInventory"] = [
                _entry_document(item) for item in backup_inventory
            ]
            journal = self._write_journal(journal, "after-old-move")

        journal = self._write_journal(journal, "before-new-publish")
        self._guarded(
            token,
            "publish staged package",
            lambda: self.device.move_no_replace(paths["stage"], self.FINAL_PACKAGE_PATH),
        )
        final = self._guarded(
            token,
            "verify published package identity",
            lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
        )
        expected_new = _entry_from_document(journal["newPackageEntry"])
        if final is None or not expected_new.same_object(final):
            raise ReferencePackageInstallError("published package identity differs")
        journal["newPackageEntry"] = _entry_document(final)
        journal = self._write_journal(journal, "after-new-publish")

        journal = self._write_journal(journal, "before-apk-install")
        _assert_apk_plan_unchanged(self.plan)
        self._guarded(
            token,
            "install APK",
            lambda: self.device.install_apk(
                self.plan.apk_path,
                expected_bytes=self.plan.apk_bytes,
                expected_sha256=self.plan.apk_sha256,
                expected_stat_identity=self.plan.apk_stat_identity,
            ),
        )
        installed = self._guarded(
            token, "verify installed APK immediately", self.device.verify_installed_apk
        )
        if (
            installed.byte_length != self.plan.apk_bytes
            or installed.sha256 != self.plan.apk_sha256
        ):
            raise ReferencePackageInstallError("installed APK identity differs")
        journal = self._write_journal(journal, "after-apk-install")
        self._restore_application_prestate(prestate, token)
        journal = self._write_journal(journal, "after-prestate-restored")
        self._verify_new_state(journal, prestate, token)
        self._write_journal(journal, "pending-verified")

    def _restore_application_prestate(
        self, prestate: DevicePrestate, token: str
    ) -> None:
        self._guarded(
            token,
            "restore preferences",
            lambda: self.device.restore_preferences(
                prestate.preferences_present, prestate.preferences
            ),
        )
        self._guarded(
            token,
            "restore notification listener",
            lambda: self.device.restore_listener(prestate.listener_state),
        )
        self._guarded(
            token,
            "restore process and focus",
            lambda: self.device.restore_process_state(prestate.process_state),
        )

    def _verify_new_state(
        self,
        journal: Mapping[str, object],
        prestate: DevicePrestate,
        token: str,
    ) -> None:
        expected_new = _entry_from_document(journal["newPackageEntry"])
        actual_new = self._guarded(
            token,
            "verify active package root",
            lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
        )
        if actual_new is None or not expected_new.same_object(actual_new):
            raise ReferencePackageInstallError("active package root identity differs")
        active_inventory = self._guarded(
            token,
            "verify active package inventory",
            lambda: self.device.immediate_inventory(self.FINAL_PACKAGE_PATH),
        )
        if tuple(item.name for item in active_inventory) != PACKAGE_FILE_NAMES:
            raise ReferencePackageInstallError(
                "active package does not have the exact six-file inventory"
            )
        if any(item.kind != "regular" for item in active_inventory):
            raise ReferencePackageInstallError(
                "active package inventory must contain only regular files"
            )
        for item in self.plan.package_files:
            actual = self._guarded(
                token,
                f"verify active package file {item.name}",
                lambda item=item: self.device.remote_file_identity(
                    f"{self.FINAL_PACKAGE_PATH}/{item.name}"
                ),
            )
            if actual.byte_length != item.byte_length or actual.sha256 != item.sha256:
                raise ReferencePackageInstallError(
                    f"published device identity differs for {item.name}"
                )
        installed = self._guarded(
            token, "verify installed APK", self.device.verify_installed_apk
        )
        if installed.byte_length != self.plan.apk_bytes or installed.sha256 != self.plan.apk_sha256:
            raise ReferencePackageInstallError("installed APK identity differs")
        listener = self._guarded(
            token, "verify restored listener", self.device.listener_state
        )
        if listener != prestate.listener_state:
            raise ReferencePackageInstallError("notification listener restoration differs")
        process = self._guarded(
            token, "verify restored process/focus", self.device.app_process_state
        )
        if not _app_state_equivalent(process, prestate.process_state):
            raise ReferencePackageInstallError("process/focus restoration differs")
        present, raw = self._guarded(
            token, "verify restored preferences", self.device.preference_state
        )
        if present is not prestate.preferences_present or raw != prestate.preferences:
            raise ReferencePackageInstallError("preference restoration differs")

    def _verify_declared_rollback_backup(
        self,
        journal: Mapping[str, object],
        prestate: DevicePrestate,
        token: str,
    ) -> None:
        paths = _journal_paths(journal)
        backup_name = paths["backup"].rsplit("/", 1)[-1]
        inventory = self._guarded(
            token,
            "verify rollback backup inventory",
            lambda: self.device.immediate_inventory(self.REFERENCE_ROOT),
        )
        matches = tuple(item for item in inventory if item.name == backup_name)
        raw_declared = journal.get("backupEntry")
        raw_backup_inventory = journal.get("backupInventory")
        previous = prestate.final_package_entry
        if previous is None:
            if (
                raw_declared is not None
                or raw_backup_inventory is not None
                or matches
            ):
                raise ReferencePackageInstallError(
                    "unexpected rollback backup is present"
                )
            return
        if type(raw_declared) is not dict:
            raise ReferencePackageInstallError(
                "declared rollback backup identity is missing"
            )
        declared = _entry_from_document(raw_declared)
        if not previous.same_object(declared):
            raise ReferencePackageInstallError(
                "declared rollback backup differs from prestate"
            )
        if len(matches) != 1 or matches[0] != declared:
            raise ReferencePackageInstallError(
                "rollback backup inventory or identity differs"
            )
        if type(raw_backup_inventory) is not list:
            raise ReferencePackageInstallError(
                "declared rollback backup inventory is missing"
            )
        declared_inventory = tuple(
            _entry_from_document(item) for item in raw_backup_inventory
        )
        actual_backup_inventory = self._guarded(
            token,
            "verify rollback backup contents",
            lambda: self.device.immediate_inventory(paths["backup"]),
        )
        if actual_backup_inventory != declared_inventory:
            raise ReferencePackageInstallError(
                "rollback backup contents inventory differs"
            )

    def _recover_with_fresh_lease(self, journal: dict[str, object]) -> list[str]:
        errors: list[str] = []
        token: str | None = None
        try:
            token = self._acquire_lease(
                journal, "journal-driven recovery", "recovery"
            )
            serial = self._guarded(token, "select recovery device", self._select_device)
            self.device.prepare_evidence_directory(self.evidence_directory)
            prestate = _prestate_from_document(journal["prestate"])
            if serial != prestate.serial:
                errors.append("recovery device serial differs")
            else:
                errors.extend(self._recover_under_lease(journal, prestate, token))
        except Exception as error:
            errors.append(f"recovery lease/action failed: {error}")
        finally:
            if token is not None:
                if self._release_independently(token):
                    self._record_lease_released(journal, "recovery")
                else:
                    errors.append("recovery lease release failed")
        return errors

    def _recover_under_lease(
        self,
        journal: dict[str, object],
        prestate: DevicePrestate,
        token: str,
        *,
        completion_state: str = "active",
    ) -> list[str]:
        errors: list[str] = []
        paths = _journal_paths(journal)
        probes: dict[str, RemoteEntryIdentity | None] = {}

        def attempt(label: str, action: Callable[[], object]) -> object | None:
            try:
                return self._guarded(token, label, action)
            except Exception as error:
                errors.append(f"{label}: {error}")
                return None

        for name, path in (
            ("final", self.FINAL_PACKAGE_PATH),
            ("stage", paths["stage"]),
            ("backup", paths["backup"]),
            ("failed", paths["failed"]),
        ):
            try:
                probes[name] = self._guarded(
                    token,
                    f"probe recovery {name}",
                    lambda path=path: self.device.entry_identity(path),
                )
            except Exception as error:
                errors.append(f"probe {name}: {error}")

        old = prestate.final_package_entry
        new_value = journal.get("newPackageEntry")
        new = _entry_from_document(new_value) if type(new_value) is dict else None
        final = probes.get("final")
        backup = probes.get("backup")
        failed = probes.get("failed")

        if new is None and probes.get("stage") is not None:
            stage = probes["stage"]
            raw_creation = journal.get("stageCreation")
            creation = (
                _exact_mapping(raw_creation, "stage creation authorization")
                if raw_creation is not None
                else {}
            )
            if (
                stage is not None
                and stage.kind == "directory"
                and creation.get("path") == paths["stage"]
                and creation.get("state") == "authorized-empty-no-replace"
            ):
                try:
                    stage_inventory = self._guarded(
                        token,
                        "verify authorized empty stage",
                        lambda: self.device.immediate_inventory(paths["stage"]),
                    )
                except Exception as error:
                    errors.append(f"verify authorized empty stage: {error}")
                else:
                    if stage_inventory:
                        errors.append(
                            "unbound stage is not empty and cannot be identified"
                        )
                    else:
                        attempt(
                            "remove authorized empty stage",
                            lambda stage=stage: self.device.remove_bound_entry(
                                paths["stage"], stage
                            ),
                        )
                        probes["stage"] = None
            else:
                errors.append("unbound stage identity is ambiguous")

        attempt("force-stop before recovery", self.device.force_stop)
        old_restored = old is None
        if old is not None:
            if final is not None and old.same_object(final):
                old_restored = True
            elif backup is not None and old.same_object(backup):
                if final is not None:
                    if new is None or not new.same_object(final):
                        errors.append("recovery final package identity is unknown")
                    elif failed is None:
                        attempt(
                            "quarantine new package",
                            lambda: self.device.move_no_replace(
                                self.FINAL_PACKAGE_PATH, paths["failed"]
                            ),
                        )
                    elif not new.same_object(failed):
                        errors.append("recovery failed path identity is unknown")
                try:
                    final_after = self._guarded(
                        token,
                        "probe final after quarantine",
                        lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
                    )
                except Exception as error:
                    errors.append(f"probe final after quarantine: {error}")
                    final_after = None
                if final_after is None:
                    attempt(
                        "restore previous final package",
                        lambda: self.device.move_no_replace(
                            paths["backup"], self.FINAL_PACKAGE_PATH
                        ),
                    )
                try:
                    restored = self._guarded(
                        token,
                        "verify previous final package",
                        lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
                    )
                    old_restored = restored is not None and old.same_object(restored)
                except Exception as error:
                    errors.append(f"verify previous final package: {error}")
            elif final is None and "final" in probes:
                errors.append("previous package is absent from final and backup")
            else:
                errors.append("previous package cannot be safely identified")
        elif final is not None:
            if new is not None and new.same_object(final):
                attempt(
                    "remove new package for first-run rollback",
                    lambda: self.device.remove_bound_entry(self.FINAL_PACKAGE_PATH, final),
                )
                try:
                    old_restored = (
                        self._guarded(
                            token,
                            "verify empty original final path",
                            lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
                        )
                        is None
                    )
                except Exception as error:
                    errors.append(f"verify empty original final path: {error}")
            else:
                errors.append("first-run rollback final identity is unknown")

        if old is not None and not old_restored:
            errors.append("previous package was not verified restored")

        for name in ("stage", "failed"):
            entry = probes.get(name)
            if entry is None:
                try:
                    entry = self._guarded(
                        token,
                        f"probe {name} for cleanup",
                        lambda name=name: self.device.entry_identity(paths[name]),
                    )
                except Exception as error:
                    errors.append(f"probe {name} for cleanup: {error}")
                    continue
            if entry is not None:
                if new is None or not new.same_object(entry):
                    errors.append(f"{name} cleanup identity is unknown")
                elif old_restored:
                    attempt(
                        f"remove {name}",
                        lambda name=name, entry=entry: self.device.remove_bound_entry(
                            paths[name], entry
                        ),
                    )

        attempt(
            "restore previous APK",
            lambda: self.device.restore_apk(prestate.apk_backup_path),
        )
        attempt(
            "restore preferences",
            lambda: self.device.restore_preferences(
                prestate.preferences_present, prestate.preferences
            ),
        )
        attempt(
            "restore notification listener",
            lambda: self.device.restore_listener(prestate.listener_state),
        )
        attempt(
            "restore process/focus",
            lambda: self.device.restore_process_state(prestate.process_state),
        )
        if not errors:
            journal["state"] = completion_state
            journal["deviceMutationStarted"] = False
            self._write_journal(journal, "recovery-complete")
        else:
            self._write_journal(journal, "recovery-incomplete")
        return errors

    def _verify_rolled_back_state(
        self, prestate: DevicePrestate, token: str
    ) -> None:
        actual = self._guarded(
            token,
            "verify rolled-back package root",
            lambda: self.device.entry_identity(self.FINAL_PACKAGE_PATH),
        )
        if prestate.final_package_entry is None:
            if actual is not None:
                raise ReferencePackageInstallError("rollback left an active package")
        elif actual is None or not prestate.final_package_entry.same_object(actual):
            raise ReferencePackageInstallError("rollback package identity differs")
        apk = self._guarded(
            token, "verify rolled-back APK", self.device.verify_installed_apk
        )
        if apk.byte_length != prestate.apk_bytes or apk.sha256 != prestate.apk_sha256:
            raise ReferencePackageInstallError("rollback APK identity differs")
        listener = self._guarded(
            token, "verify rolled-back listener", self.device.listener_state
        )
        if listener != prestate.listener_state:
            raise ReferencePackageInstallError("rollback listener state differs")
        process = self._guarded(
            token,
            "verify rolled-back process/focus",
            self.device.app_process_state,
        )
        if not _app_state_equivalent(process, prestate.process_state):
            raise ReferencePackageInstallError("rollback process/focus differs")
        present, raw = self._guarded(
            token, "verify rolled-back preferences", self.device.preference_state
        )
        if present is not prestate.preferences_present or raw != prestate.preferences:
            raise ReferencePackageInstallError("rollback preferences differ")

    def _classify_finalize_residue(
        self, journal: Mapping[str, object], token: str
    ) -> list[tuple[RemoteEntryIdentity, str]]:
        entries = self._guarded(
            token,
            "capture finalization residue inventory",
            lambda: self.device.immediate_inventory(self.REFERENCE_ROOT),
        )
        new = _entry_from_document(journal["newPackageEntry"])
        paths = _journal_paths(journal)
        classified: list[tuple[RemoteEntryIdentity, str]] = []
        originals = [
            (
                _entry_from_document(item["entry"]),
                _exact_string(item.get("classification"), "entry classification"),
            )
            for item in journal["referenceClassifications"]
        ]
        old = _prestate_from_document(journal["prestate"]).final_package_entry
        for entry in entries:
            full_path = f"{self.REFERENCE_ROOT}/{entry.name}"
            if full_path == self.FINAL_PACKAGE_PATH and new.same_object(entry):
                classified.append((entry, "active"))
                continue
            if full_path == paths["backup"] and old is not None and old.same_object(entry):
                classified.append((entry, "rollback-backup"))
                continue
            if full_path in (paths["stage"], paths["failed"]) and new.same_object(entry):
                classified.append((entry, "journal-residue"))
                continue
            match = next(
                (
                    classification
                    for original, classification in originals
                    if original.same_object(entry)
                ),
                "unknown",
            )
            if match in {
                "inactive-package",
                "source-pbf",
                "trace",
                "previous-active-package",
            }:
                classified.append((entry, match))
            else:
                classified.append((entry, "unknown"))
        return classified

    def _verify_one_active_package(
        self, journal: Mapping[str, object], token: str
    ) -> None:
        inventory = self._guarded(
            token,
            "verify one active reference package",
            lambda: self.device.immediate_inventory(self.REFERENCE_ROOT),
        )
        if len(inventory) != 1 or inventory[0].name != PACKAGE_ID:
            raise ReferencePackageInstallError(
                "final reference inventory is not exactly one active package"
            )
        expected = _entry_from_document(journal["newPackageEntry"])
        if not expected.same_object(inventory[0]):
            raise ReferencePackageInstallError("final active package identity differs")

    def _measure_footprint(self, token: str) -> dict[str, object]:
        components = {
            "activePackage": self._guarded(
                token,
                "measure active package footprint",
                lambda: self.device.storage_footprint(self.FINAL_PACKAGE_PATH),
            ),
            "installedApk": self._guarded(
                token,
                "measure installed APK footprint",
                lambda: self.device.storage_footprint("installed-apk"),
            ),
            "cache": self._guarded(
                token,
                "measure private cache footprint",
                lambda: self.device.storage_footprint("cache", private=True),
            ),
            "codeCache": self._guarded(
                token,
                "measure private code cache footprint",
                lambda: self.device.storage_footprint("code_cache", private=True),
            ),
            "externalCache": self._guarded(
                token,
                "measure external cache footprint",
                lambda: self.device.storage_footprint(
                    "/storage/emulated/0/Android/data/com.flightalert/cache"
                ),
            ),
        }
        counted = sum(item.counted_bytes for item in components.values())
        return {
            "components": {
                name: {
                    "allocatedBytes": item.allocated_bytes,
                    "countedBytes": item.counted_bytes,
                    "logicalBytes": item.logical_bytes,
                }
                for name, item in components.items()
            },
            "countedBytes": counted,
            "hardCeilingBytes": HARD_FOOTPRINT_CEILING_BYTES,
            "hardStrictlyBelow": counted < HARD_FOOTPRINT_CEILING_BYTES,
            "preferredCeilingBytes": PREFERRED_FOOTPRINT_CEILING_BYTES,
            "preferredStrictlyBelow": counted < PREFERRED_FOOTPRINT_CEILING_BYTES,
        }

    @staticmethod
    def _enforce_hard_footprint(footprint: Mapping[str, object]) -> None:
        if footprint.get("hardStrictlyBelow") is not True:
            raise ReferencePackageInstallError(
                "actual footprint is not strictly below 40,000,000,000 bytes"
            )

    def _pending_receipt(self, journal: Mapping[str, object]) -> dict[str, object]:
        return {
            "deviceSerialSha256": hashlib.sha256(
                _exact_string(journal.get("deviceSerial"), "journal serial").encode(
                    "utf-8", "strict"
                )
            ).hexdigest(),
            "package": _plan_document(self.plan),
            "referenceRootClassifications": journal["referenceClassifications"],
            "referenceRootInventoryBefore": journal["prestate"][
                "referenceInventory"
            ],
            "rollbackBackup": _journal_paths(journal)["backup"],
            "schema": "flightalert.experiment8.reference-pending-acceptance.v2",
            "state": "pending-acceptance",
            "transactionToken": journal["transactionToken"],
        }

    def _write_journal(
        self, journal: dict[str, object], phase: str
    ) -> dict[str, object]:
        journal["phase"] = phase
        journal["phaseSequence"] = int(journal.get("phaseSequence", 0)) + 1
        _atomic_write_json(
            self.evidence_directory / "transaction-journal.json", journal
        )
        if self.phase_hook is not None:
            self.phase_hook(phase)
        return journal

    def _record_lease_acquired(
        self, journal: dict[str, object], token: str, operation: str
    ) -> dict[str, object]:
        journal["activeLeaseToken"] = token
        journal["leaseReleased"] = False
        return self._write_journal(journal, f"{operation}-lease-token-recorded")

    def _record_lease_released(
        self, journal: dict[str, object], operation: str
    ) -> dict[str, object]:
        journal["leaseReleased"] = True
        journal["activeLeaseToken"] = None
        journal["leaseAcquisitionIntent"] = None
        return self._write_journal(journal, f"{operation}-lease-released")

    def _reconcile_journal_lease(
        self, journal: dict[str, object]
    ) -> dict[str, object]:
        active = journal.get("activeLeaseToken")
        raw_intent = journal.get("leaseAcquisitionIntent")
        intent = (
            dict(_exact_mapping(raw_intent, "lease acquisition intent"))
            if raw_intent is not None
            else None
        )
        if active is None and intent is None:
            return journal
        if active is not None:
            if type(active) is not str or re.fullmatch(r"[0-9a-f]{32}", active) is None:
                raise ReferencePackageInstallError(
                    "unfinished journal lease token is malformed"
                )
            self.lease.reconcile_unfinished(active, intent)
        else:
            if intent is None:
                raise ReferencePackageInstallError(
                    "unfinished lease acquisition intent is missing"
                )
            self.lease.reconcile_acquisition(intent)
        return self._record_lease_released(journal, "stale")

    def _guarded(
        self, token: str, label: str, action: Callable[[], object]
    ) -> object:
        try:
            self.lease.heartbeat(token)
            result = action()
            self.lease.heartbeat(token)
            return result
        except Exception as error:
            if isinstance(error, ReferencePackageInstallError):
                raise
            raise ReferencePackageInstallError(f"{label} failed: {error}") from error

    def _lease_arguments(self, operation: str) -> dict[str, object]:
        return {
            "invocation_id": uuid.uuid4().hex,
            "owner": "Zeus/Experiment8",
            "purpose": f"{operation} finalized whole-world Experiment 8 package",
            "scenario": f"transactional reference package {operation}",
            "stateful_effects": (
                "same-signature APK replacement and atomic external reference-package swap; "
                "no app-data or cache clear"
            ),
            "expected_minutes": 180,
        }

    def _acquire_lease(
        self, journal: dict[str, object], operation: str, phase_label: str
    ) -> str:
        arguments = self._lease_arguments(operation)
        identity = dict(self.lease.acquisition_intent(**arguments))
        intent: dict[str, object] = {
            **identity,
            "deviceOperation": operation,
        }
        _validate_lease_acquisition_intent(intent, arguments, operation)
        journal["leaseAcquisitionIntent"] = intent
        journal["activeLeaseToken"] = None
        journal["leaseReleased"] = False
        self._write_journal(journal, f"{phase_label}-lease-acquisition-intent")

        token: str | None = None
        try:
            token = self.lease.acquire(**arguments)
            if re.fullmatch(r"[0-9a-f]{32}", token) is None:
                raise ReferencePackageInstallError("device lease token is malformed")
            self._record_lease_acquired(journal, token, phase_label)
            try:
                self.lease.heartbeat(token)
            except Exception as heartbeat_error:
                confirmed = self.lease.confirm_acquisition(intent)
                if confirmed != token:
                    raise ReferencePackageInstallError(
                        "device lease ownership could not be confirmed after heartbeat failure"
                    ) from heartbeat_error
            self.device.set_mutation_guard(lambda: self.lease.heartbeat(token))
            return token
        except BaseException:
            if token is not None:
                released = self._release_independently(token)
                if released:
                    self._record_lease_released(journal, phase_label)
                elif re.fullmatch(r"[0-9a-f]{32}", token) is not None:
                    journal["activeLeaseToken"] = token
                    try:
                        self._write_journal(
                            journal, f"{phase_label}-lease-release-unconfirmed"
                        )
                    except BaseException:
                        pass
            raise

    def _release_independently(self, token: str) -> bool:
        self.device.set_mutation_guard(None)
        try:
            self.lease.release(token)
            return True
        except BaseException:
            return False

    def _select_device(self) -> str:
        serial = self.device.require_single_ready_device()
        if type(serial) is not str or not serial or any(
            character.isspace() for character in serial
        ):
            raise ReferencePackageInstallError("ready device serial is malformed")
        return serial

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
        _atomic_write_json(
            self.evidence_directory / "install-failure.json", receipt
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
    attributes = {
        item.split(":", 1)[0]: item.split(":", 1)[1]
        for item in columns[2:]
        if ":" in item
    }
    emulator_markers = " ".join(
        attributes.get(key, "").casefold() for key in ("product", "model", "device")
    )
    if serial.casefold().startswith("emulator-") or any(
        marker in emulator_markers for marker in ("emulator", "generic", "sdk_")
    ):
        raise ReferencePackageInstallError("sole adb row is not a physical device")
    return serial


def parse_remote_inventory(
    raw: bytes, root: str
) -> tuple[RemoteEntryIdentity, ...]:
    text = _strict_ascii_text(raw, "remote inventory", maximum=MAX_JSON_BYTES)
    prefix = root.rstrip("/") + "/"
    entries: list[RemoteEntryIdentity] = []
    for line in text.splitlines():
        if not line:
            continue
        columns = line.split("\t") if "\t" in line else line.split("|")
        if len(columns) != 4:
            raise ReferencePackageInstallError("remote inventory row is malformed")
        raw_kind, raw_device, raw_inode, path = columns
        if raw_kind == "directory":
            kind = "directory"
        elif raw_kind == "regular file":
            kind = "regular"
        else:
            raise ReferencePackageInstallError(
                "remote inventory contains an unsupported file type"
            )
        if not path.startswith(prefix):
            raise ReferencePackageInstallError("remote inventory path is outside its root")
        name = path[len(prefix) :]
        if "/" in name or re.fullmatch(r"[A-Za-z0-9._-]+", name) is None:
            raise ReferencePackageInstallError("remote inventory name is unsafe")
        if (
            re.fullmatch(r"[1-9][0-9]*", raw_device) is None
            or re.fullmatch(r"[1-9][0-9]*", raw_inode) is None
        ):
            raise ReferencePackageInstallError("remote inventory identity is malformed")
        entries.append(
            RemoteEntryIdentity(name, kind, int(raw_device), int(raw_inode))
        )
    entries.sort(key=lambda item: item.name)
    if len({item.name for item in entries}) != len(entries):
        raise ReferencePackageInstallError("remote inventory repeats an entry")
    return tuple(entries)


def parse_listener_binding(raw: bytes, component: str) -> bool:
    text = _strict_utf8_text(
        raw, "notification listener service dump", maximum=MAX_JSON_BYTES
    )
    lines = text.splitlines()
    start = next(
        (
            index
            for index, line in enumerate(lines)
            if re.fullmatch(r"\s{4}Live notification listeners \([0-9]+\):", line)
        ),
        None,
    )
    if start is None:
        raise ReferencePackageInstallError(
            "notification listener dump has no listeners section"
        )
    section: list[str] = []
    for line in lines[start + 1 :]:
        if line.startswith("    ") and not line.startswith("      "):
            break
        section.append(line)
    service_pattern = re.compile(rf"^\s{{6}}{re.escape(component)}\s+\(user\s+[0-9]+\):")
    return any(service_pattern.search(line) is not None for line in section)


def _parse_component_line(raw: bytes, marker: str, label: str) -> str | None:
    text = _strict_utf8_text(raw, label, maximum=MAX_JSON_BYTES)
    matching = [line for line in text.splitlines() if marker in line]
    if not matching:
        return None
    components: set[str] = set()
    pattern = re.compile(r"([A-Za-z0-9._]+/[A-Za-z0-9._$]+)")
    for line in matching:
        match = pattern.search(line.split(marker, 1)[1])
        if match is not None:
            components.add(match.group(1))
    if not components:
        return None
    if len(components) != 1:
        raise ReferencePackageInstallError(f"{label} is ambiguous")
    return next(iter(components))


def _parse_screen_interactive(raw: bytes) -> bool:
    text = _strict_utf8_text(raw, "power state", maximum=MAX_JSON_BYTES)
    states = re.findall(r"(?m)^\s*mWakefulness=(Awake|Asleep|Dozing|Dreaming)\s*$", text)
    if len(states) != 1:
        raise ReferencePackageInstallError("power wakefulness state is ambiguous")
    return states[0] == "Awake"


def _parse_device_locked(raw: bytes) -> bool:
    text = _strict_utf8_text(raw, "window policy state", maximum=MAX_JSON_BYTES)
    values = re.findall(
        r"(?m)^\s*(?:mShowingLockscreen|keyguardShowing|showing)=(true|false)\s*$",
        text,
    )
    if not values or len(set(values)) != 1:
        raise ReferencePackageInstallError("device lock state is ambiguous")
    return values[0] == "true"


def _parse_active_app_services(raw: bytes) -> tuple[str, ...]:
    text = _strict_utf8_text(raw, "Flight Alert service state", maximum=MAX_JSON_BYTES)
    pattern = re.compile(
        r"\b(com\.flightalert/(?:\.[A-Za-z0-9_.$]+|com\.flightalert\.[A-Za-z0-9_.$]+))\b"
    )
    return tuple(sorted(set(pattern.findall(text))))


def _app_state_equivalent(actual: AppProcessState, expected: AppProcessState) -> bool:
    return (
        actual.running is expected.running
        and actual.resumed_component == expected.resumed_component
        and actual.focused_component == expected.focused_component
        and actual.screen_interactive is expected.screen_interactive
        and actual.device_locked is expected.device_locked
        and actual.active_services == expected.active_services
    )


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
        self._active_intent: Mapping[str, object] | None = None

    def acquisition_intent(self, **arguments: object) -> Mapping[str, object]:
        owner = _required_argument(arguments, "owner")
        purpose = _required_argument(arguments, "purpose")
        scenario = _required_argument(arguments, "scenario")
        effects = _required_argument(arguments, "stateful_effects")
        minutes = arguments.get("expected_minutes")
        if type(minutes) is not int or minutes <= 0:
            raise ReferencePackageInstallError("device lease minutes are malformed")
        invocation_id = _required_argument(arguments, "invocation_id")
        if re.fullmatch(r"[0-9a-f]{32}", invocation_id) is None:
            raise ReferencePackageInstallError(
                "device lease invocation ID is malformed"
            )
        return {
            "expectedMinutes": minutes,
            "invocationId": invocation_id,
            "owner": owner,
            "purpose": purpose,
            "scenario": f"{scenario} [invocation:{invocation_id}]",
            "statefulEffects": effects,
            "threadId": self.thread_id,
        }

    def acquire(self, **arguments: object) -> str:
        if self._token is not None:
            raise ReferencePackageInstallError("device lease is already active here")
        intent = self.acquisition_intent(**arguments)
        try:
            document = self._call(
                "acquire",
                "-Owner",
                str(intent["owner"]),
                "-ThreadId",
                self.thread_id,
                "-Purpose",
                str(intent["purpose"]),
                "-Scenario",
                str(intent["scenario"]),
                "-StatefulEffects",
                str(intent["statefulEffects"]),
                "-ExpectedMinutes",
                str(intent["expectedMinutes"]),
            )
        except Exception as acquire_error:
            token = self._status_token_for_intent(intent)
            if token is None:
                raise acquire_error
            self._activate_token(token, intent)
            return token
        token = _exact_string(document.get("token"), "device lease token")
        if not _exact_bool(document.get("held"), "device lease held state"):
            raise ReferencePackageInstallError("device lease acquire did not hold the lease")
        if re.fullmatch(r"[0-9a-f]{32}", token) is None:
            raise ReferencePackageInstallError("device lease token is malformed")
        self._activate_token(token, intent)
        return token

    def _activate_token(
        self, token: str, intent: Mapping[str, object]
    ) -> None:
        self._token = token
        self._active_intent = dict(intent)
        self._stop.clear()
        self._background_error = None
        self._thread = threading.Thread(
            target=self._heartbeat_loop,
            name="flightalert-exp8-device-lease-heartbeat",
            daemon=True,
        )
        self._thread.start()

    def heartbeat(self, token: str) -> None:
        self._assert_token(token)
        self._raise_background_error()
        self._heartbeat_with_reconciliation(token)

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
        if _exact_bool(document.get("held"), "device release held state"):
            raise ReferencePackageInstallError("device lease remains held after release")
        with self._lock:
            status = self._call("status")
        if _exact_bool(status.get("held"), "device release status held state"):
            raise ReferencePackageInstallError(
                "device lease status remains held after release"
            )
        status_token = status.get("token")
        if status_token not in (None, ""):
            raise ReferencePackageInstallError(
                "device lease status retains a token after release"
            )
        self._token = None
        self._thread = None
        self._background_error = None
        self._active_intent = None

    def confirm_acquisition(self, intent: Mapping[str, object]) -> str | None:
        with self._lock:
            token = self._status_token_for_intent(intent)
        if token is not None and self._token not in (None, token):
            raise ReferencePackageInstallError(
                "confirmed device lease token differs from local ownership"
            )
        return token

    def reconcile_acquisition(self, intent: Mapping[str, object]) -> None:
        if self._token is not None:
            raise ReferencePackageInstallError(
                "cannot reconcile an acquisition intent while a lease is active"
            )
        token = self._status_token_for_intent(intent)
        if token is None:
            return
        released = self._call("release", "-Token", token)
        if _exact_bool(released.get("held"), "intent lease release state"):
            raise ReferencePackageInstallError(
                "acquisition-intent device lease remains held"
            )
        self._assert_status_unheld(self._call("status"), "intent lease final status")

    def reconcile_unfinished(
        self, token: str, intent: Mapping[str, object] | None = None
    ) -> None:
        if self._token is not None:
            raise ReferencePackageInstallError(
                "cannot reconcile an unfinished lease while one is active"
            )
        if re.fullmatch(r"[0-9a-f]{32}", token) is None:
            raise ReferencePackageInstallError("unfinished lease token is malformed")
        if intent is not None:
            returned = self._status_token_for_intent(intent)
        else:
            returned = self._status_token(self._call("status"), "unfinished lease status")
        if returned is None:
            return
        if returned != token:
            raise ReferencePackageInstallError(
                "held device lease does not match the unfinished journal"
            )
        released = self._call("release", "-Token", token)
        if _exact_bool(released.get("held"), "unfinished lease release state"):
            raise ReferencePackageInstallError("unfinished device lease remains held")
        self._assert_status_unheld(
            self._call("status"), "unfinished lease final status"
        )

    def _status_token_for_intent(
        self, intent: Mapping[str, object]
    ) -> str | None:
        status = self._call("status")
        token = self._status_token(status, "lease acquisition status")
        if token is None:
            return None
        lease = _exact_mapping(status.get("lease"), "held lease identity")
        expected = {
            "owner": intent.get("owner"),
            "threadId": intent.get("threadId"),
            "purpose": intent.get("purpose"),
            "scenario": intent.get("scenario"),
            "statefulEffects": intent.get("statefulEffects"),
        }
        for name, value in expected.items():
            if lease.get(name) != value:
                raise ReferencePackageInstallError(
                    f"held lease {name} does not match acquisition intent"
                )
        if lease.get("token") != token:
            raise ReferencePackageInstallError(
                "held lease token does not match acquisition status"
            )
        return token

    @staticmethod
    def _status_token(
        status: Mapping[str, object], label: str
    ) -> str | None:
        held = _exact_bool(status.get("held"), label)
        if not held:
            AtomicDeviceLeaseClient._assert_status_unheld(status, label)
            return None
        lease_value = status.get("lease")
        if type(lease_value) is dict:
            token = lease_value.get("token")
        else:
            token = status.get("token")
        if type(token) is not str or re.fullmatch(r"[0-9a-f]{32}", token) is None:
            raise ReferencePackageInstallError(f"{label} token is malformed")
        top_token = status.get("token")
        if top_token not in (None, "", token):
            raise ReferencePackageInstallError(f"{label} top-level token differs")
        return token

    @staticmethod
    def _assert_status_unheld(
        status: Mapping[str, object], label: str
    ) -> None:
        if _exact_bool(status.get("held"), label):
            raise ReferencePackageInstallError(f"{label} remains held")
        if status.get("token") not in (None, "") or status.get("lease") not in (
            None,
            "",
        ):
            raise ReferencePackageInstallError(f"{label} retains lease identity")

    def _heartbeat_loop(self) -> None:
        while not self._stop.wait(self.heartbeat_interval_seconds):
            token = self._token
            if token is None:
                return
            try:
                self._heartbeat_with_reconciliation(token)
            except BaseException as error:
                self._background_error = error
                self._stop.set()
                return

    def _heartbeat_with_reconciliation(self, token: str) -> None:
        heartbeat_error: Exception | None = None
        try:
            with self._lock:
                document = self._call("heartbeat", "-Token", token)
            if not _exact_bool(document.get("held"), "device heartbeat held state"):
                raise ReferencePackageInstallError("device heartbeat lost the lease")
            returned = _exact_string(document.get("token"), "device heartbeat token")
            if returned != token:
                raise ReferencePackageInstallError("device heartbeat token differs")
            return
        except Exception as error:
            heartbeat_error = error

        intent = self._active_intent
        if intent is not None:
            try:
                with self._lock:
                    confirmed = self._status_token_for_intent(intent)
                if confirmed == token:
                    return
            except Exception as status_error:
                raise ReferencePackageInstallError(
                    "device heartbeat failed and ownership status was ambiguous"
                ) from status_error
        raise ReferencePackageInstallError(
            "device heartbeat failed and exact ownership was not retained"
        ) from heartbeat_error

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
    ALERT_SERVICE_COMPONENTS = frozenset(
        {
            "com.flightalert/.alerts.AircraftAlertService",
            "com.flightalert/com.flightalert.alerts.AircraftAlertService",
        }
    )
    PREFERENCE_PATH = "shared_prefs/flight_alert.xml"
    LISTENER_COMPONENT = (
        "com.flightalert/"
        "com.flightalert.alerts.MonitoringNotificationHiderService"
    )
    LISTENER_SERVICE_COMPONENTS = frozenset(
        {
            LISTENER_COMPONENT,
            "com.flightalert/.alerts.MonitoringNotificationHiderService",
        }
    )

    def __init__(self, *, adb: str, runner: CommandRunner) -> None:
        self.adb = adb
        self.runner = runner
        self.serial: str | None = None
        self.evidence_directory: Path | None = None
        self._verification_counter = 0
        self._mutation_guard: Callable[[], None] | None = None

    def set_mutation_guard(self, guard: Callable[[], None] | None) -> None:
        if guard is not None and not callable(guard):
            raise ReferencePackageInstallError("device mutation guard is malformed")
        self._mutation_guard = guard

    def require_single_ready_device(self) -> str:
        result = self.runner.run((self.adb, "devices", "-l"), timeout_seconds=30.0)
        if result.return_code != 0:
            raise ReferencePackageInstallError(
                f"adb devices failed: {_bounded_error(result.stderr)}"
            )
        self.serial = parse_adb_devices(result.stdout)
        return self.serial

    def prepare_evidence_directory(self, evidence_directory: Path) -> None:
        if not Path(evidence_directory).is_dir():
            raise ReferencePackageInstallError("device evidence directory is missing")
        self.evidence_directory = Path(evidence_directory)

    def capture_prestate(
        self, evidence_directory: Path, final_package_path: str
    ) -> DevicePrestate:
        serial = self._require_serial()
        self.prepare_evidence_directory(evidence_directory)
        remote_apk = self._installed_base_path()
        apk_backup = evidence_directory / "installed-prestate.apk"
        self._checked(("pull", remote_apk, str(apk_backup)), timeout=180.0)
        apk = _hash_regular_file(apk_backup, "installed prestate APK")
        preferences_present, preferences = self.preference_state()
        if preferences_present:
            _atomic_write_bytes(
                evidence_directory / "preferences-prestate.xml", preferences
            )
        listener = self.listener_state()
        process = self.app_process_state()
        if (
            process.running
            and listener.bound
            and process.running_reason != "foreground-activity"
        ):
            process = AppProcessState(
                pids=process.pids,
                resumed_component=process.resumed_component,
                focused_component=process.focused_component,
                screen_interactive=process.screen_interactive,
                device_locked=process.device_locked,
                active_services=process.active_services,
                running_reason="bound-notification-listener",
            )
        inventory = self.immediate_inventory(ReferencePackageInstaller.REFERENCE_ROOT)
        final_entry = self.entry_identity(final_package_path)
        return DevicePrestate(
            serial=serial,
            apk_backup_path=apk_backup,
            apk_bytes=apk.byte_length,
            apk_sha256=apk.sha256,
            preferences=preferences,
            app_was_running=process.running,
            final_package_was_present=final_entry is not None,
            preferences_present=preferences_present,
            listener_state=listener,
            process_state=process,
            reference_inventory=inventory,
            final_package_entry=final_entry,
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

    def entry_identity(self, path: str) -> RemoteEntryIdentity | None:
        result = self._adb(
            ("shell", "stat", "-c", "%F|%d|%i|%n", path),
            timeout=30.0,
            allow_failure=True,
        )
        if result.return_code == 1 and not result.stdout:
            return None
        if result.return_code != 0:
            raise ReferencePackageInstallError(
                f"device entry identity probe failed: {_bounded_error(result.stderr)}"
            )
        entries = parse_remote_inventory(result.stdout, path.rsplit("/", 1)[0])
        if len(entries) != 1 or entries[0].name != path.rsplit("/", 1)[-1]:
            raise ReferencePackageInstallError("device entry identity output differs")
        return entries[0]

    def immediate_inventory(self, root: str) -> tuple[RemoteEntryIdentity, ...]:
        if not self.path_exists(root):
            return ()
        result = self._checked(
            (
                "shell",
                "find",
                root,
                "-mindepth",
                "1",
                "-maxdepth",
                "1",
                "-exec",
                "stat",
                "-c",
                "%F|%d|%i|%n",
                "{}",
                "+",
            ),
            timeout=60.0,
        )
        return parse_remote_inventory(result.stdout, root)

    def classify_reference_entry(
        self, root: str, entry: RemoteEntryIdentity
    ) -> str:
        path = f"{root}/{entry.name}"
        if path == ReferencePackageInstaller.FINAL_PACKAGE_PATH:
            return "previous-active-package"
        lowered = entry.name.casefold()
        if entry.kind == "regular":
            if lowered.endswith((".osm.pbf", ".pbf")):
                return "source-pbf"
            if lowered.endswith((".trace", ".perfetto-trace")):
                return "trace"
            return "unknown"
        result = self._adb(
            ("exec-out", "cat", f"{path}/manifest.json"),
            timeout=30.0,
            allow_failure=True,
        )
        if result.return_code != 0 or not result.stdout or len(result.stdout) > MAX_JSON_BYTES:
            return "unknown"
        try:
            manifest = _parse_strict_json_bytes(
                result.stdout, f"reference package manifest {entry.name}"
            )
            _exact_string(manifest.get("packageId"), "reference package ID")
            _exact_integer(manifest.get("schemaVersion"), "reference package schema")
        except ReferencePackageInstallError:
            return "unknown"
        return "inactive-package"

    def make_directory(self, path: str) -> None:
        self._mutating_checked(("shell", "mkdir", "-p", "--", path), timeout=30.0)
        if not self.path_exists(path):
            raise ReferencePackageInstallError("device directory creation did not persist")

    def make_directory_no_replace(self, path: str) -> RemoteEntryIdentity:
        if self.path_exists(path):
            raise ReferencePackageInstallError(
                "device no-replace directory already exists"
            )
        self._mutating_checked(("shell", "mkdir", "--", path), timeout=30.0)
        entry = self.entry_identity(path)
        if entry is None or entry.kind != "directory":
            raise ReferencePackageInstallError(
                "device no-replace directory identity is unavailable"
            )
        return entry

    def push_file(self, local: Path, remote: str) -> None:
        self._mutating_checked(("push", str(local), remote), timeout=None)

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
        self._mutating_checked(("shell", "am", "force-stop", self.APP_ID), timeout=30.0)

    def listener_state(self) -> ListenerState:
        approval_result = self._checked(
            ("shell", "settings", "get", "secure", "enabled_notification_listeners"),
            timeout=30.0,
        )
        approval = _strict_ascii_text(
            approval_result.stdout,
            "notification listener approval",
            maximum=128 * 1024,
        ).strip()
        if approval == "null":
            approval = ""
        dump = self._checked(
            ("shell", "dumpsys", "notification", "--noredact"), timeout=60.0
        )
        return ListenerState(
            approval=approval,
            bound=parse_listener_binding(dump.stdout, self.LISTENER_COMPONENT),
        )

    def disallow_listener(self) -> None:
        self._mutating_checked(
            (
                "shell",
                "cmd",
                "notification",
                "disallow_listener",
                self.LISTENER_COMPONENT,
            ),
            timeout=30.0,
        )

    def app_process_state(self) -> AppProcessState:
        pid_result = self._adb(
            ("shell", "pidof", self.APP_ID), timeout=15.0, allow_failure=True
        )
        if pid_result.return_code not in (0, 1):
            raise ReferencePackageInstallError("pidof state probe failed")
        pid_text = _strict_ascii_text(
            pid_result.stdout, "pidof state", maximum=4096
        ).strip()
        if pid_result.return_code == 0:
            pids = tuple(pid_text.split())
            if not pids or any(
                re.fullmatch(r"[1-9][0-9]*", item) is None for item in pids
            ):
                raise ReferencePackageInstallError("running process state is malformed")
        else:
            if pid_text:
                raise ReferencePackageInstallError(
                    "stopped process state is contradictory"
                )
            pids = ()
        activity = self._checked(
            ("shell", "dumpsys", "activity", "activities"), timeout=60.0
        )
        window = self._checked(
            ("shell", "dumpsys", "window", "windows"), timeout=60.0
        )
        power = self._checked(("shell", "dumpsys", "power"), timeout=60.0)
        policy = self._checked(
            ("shell", "dumpsys", "window", "policy"), timeout=60.0
        )
        services = self._checked(
            ("shell", "dumpsys", "activity", "services", self.APP_ID),
            timeout=60.0,
        )
        return AppProcessState(
            pids=pids,
            resumed_component=_parse_component_line(
                activity.stdout, "mResumedActivity", "resumed activity"
            ),
            focused_component=_parse_component_line(
                window.stdout, "mCurrentFocus", "focused window"
            ),
            screen_interactive=_parse_screen_interactive(power.stdout),
            device_locked=_parse_device_locked(policy.stdout),
            active_services=_parse_active_app_services(services.stdout),
        )

    def move_no_replace(self, source: str, destination: str) -> None:
        if not self.path_exists(source) or self.path_exists(destination):
            raise ReferencePackageInstallError("device atomic move precondition differs")
        self._mutating_checked(
            ("shell", "mv", "-n", "--", source, destination), timeout=60.0
        )
        if self.path_exists(source) or not self.path_exists(destination):
            raise ReferencePackageInstallError("device atomic move did not persist")

    def install_apk(
        self,
        path: Path,
        *,
        expected_bytes: int,
        expected_sha256: str,
        expected_stat_identity: tuple[int, int, int, int, int],
    ) -> None:
        self._guard_device_mutation()
        actual = _hash_regular_file(path, "APK immediately before adb install")
        if (
            actual.byte_length != expected_bytes
            or actual.sha256 != expected_sha256
            or actual.stat_identity != expected_stat_identity
        ):
            raise ReferencePackageInstallError("APK changed after final lease guard")
        result = self._mutating_checked(
            ("install", "-r", str(path)), timeout=300.0
        )
        text = _strict_ascii_text(result.stdout, "adb install output", maximum=128 * 1024)
        if not any(line.strip() == "Success" for line in text.splitlines()):
            raise ReferencePackageInstallError("adb install did not report Success")

    def preference_state(self) -> tuple[bool, bytes]:
        exists = self._adb(
            (
                "shell",
                "run-as",
                self.APP_ID,
                "test",
                "-e",
                self.PREFERENCE_PATH,
            ),
            timeout=30.0,
            allow_failure=True,
        )
        if exists.return_code == 1 and not exists.stdout:
            return False, b""
        if exists.return_code != 0:
            raise ReferencePackageInstallError("preference presence probe failed")
        result = self._checked(
            ("exec-out", "run-as", self.APP_ID, "cat", self.PREFERENCE_PATH),
            timeout=30.0,
        )
        if len(result.stdout) > MAX_JSON_BYTES:
            raise ReferencePackageInstallError("installed app preferences are oversized")
        return True, result.stdout

    def restore_preferences(self, present: bool, raw: bytes) -> None:
        if type(present) is not bool or type(raw) is not bytes:
            raise ReferencePackageInstallError("preference restoration state is malformed")
        if not present:
            if raw:
                raise ReferencePackageInstallError(
                    "absent preference state contains bytes"
                )
            self._mutating_checked(
                (
                    "shell",
                    "run-as",
                    self.APP_ID,
                    "rm",
                    "-f",
                    "--",
                    self.PREFERENCE_PATH,
                ),
                timeout=30.0,
            )
            if self.preference_state() != (False, b""):
                raise ReferencePackageInstallError(
                    "absent preference restoration differs"
                )
            return
        evidence = self._require_evidence_directory()
        token = uuid.uuid4().hex
        host = evidence / f"preferences-restore-{token}.xml"
        remote = f"/data/local/tmp/flightalert-exp8-preferences-{token}.xml"
        private_stage = f"shared_prefs/.flight_alert.xml.exp8-{token}.tmp"
        _atomic_write_bytes(host, raw)
        try:
            self._mutating_checked(("push", str(host), remote), timeout=60.0)
            self._mutating_checked(("shell", "chmod", "0644", remote), timeout=30.0)
            self._mutating_checked(
                ("shell", "run-as", self.APP_ID, "cp", remote, private_stage),
                timeout=30.0,
            )
            self._mutating_checked(
                ("shell", "run-as", self.APP_ID, "chmod", "0600", private_stage),
                timeout=30.0,
            )
            self._mutating_checked(
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
            self._mutating_adb(
                ("shell", "rm", "-f", "--", remote),
                timeout=30.0,
                allow_failure=True,
            )
        if self.preference_state() != (True, raw):
            raise ReferencePackageInstallError("preference restoration readback differs")

    def restore_listener(self, state: ListenerState) -> None:
        self._mutating_checked(
            (
                "shell",
                "cmd",
                "notification",
                "allow_listener" if state.bound else "disallow_listener",
                self.LISTENER_COMPONENT,
            ),
            timeout=30.0,
        )
        if state.approval:
            self._mutating_checked(
                (
                    "shell",
                    "settings",
                    "put",
                    "secure",
                    "enabled_notification_listeners",
                    state.approval,
                ),
                timeout=30.0,
            )
        else:
            self._mutating_checked(
                (
                    "shell",
                    "settings",
                    "delete",
                    "secure",
                    "enabled_notification_listeners",
                ),
                timeout=30.0,
            )
        if self.listener_state() != state:
            raise ReferencePackageInstallError(
                "notification listener restoration readback differs"
            )

    def restore_process_state(self, state: AppProcessState) -> None:
        if not state.running:
            self.force_stop()
        else:
            for component in state.active_services:
                if component in self.LISTENER_SERVICE_COMPONENTS:
                    continue
                start_verb = (
                    "start-foreground-service"
                    if component in self.ALERT_SERVICE_COMPONENTS
                    else "startservice"
                )
                self._mutating_checked(
                    ("shell", "am", start_verb, "-n", component),
                    timeout=60.0,
                )
            if state.running_reason == "foreground-activity":
                if (
                    not state.screen_interactive
                    or state.device_locked
                    or not _component_is_app(state.resumed_component)
                ):
                    raise ReferencePackageInstallError(
                        "foreground process state is unsafe to restore"
                    )
                self._mutating_checked(
                    ("shell", "am", "start", "-n", state.resumed_component),
                    timeout=60.0,
                )
            elif state.running_reason not in {
                "bound-notification-listener",
                "service",
            }:
                raise ReferencePackageInstallError(
                    "background-only process state cannot be restored safely"
                )
        if not _app_state_equivalent(self.app_process_state(), state):
            raise ReferencePackageInstallError(
                "process/focus restoration readback differs"
            )

    def restore_apk(self, path: Path) -> None:
        expected = _hash_regular_file(path, "prestate APK for restoration")
        result = self._mutating_checked(
            ("install", "-r", "-d", str(path)), timeout=300.0
        )
        text = _strict_ascii_text(
            result.stdout, "adb rollback install output", maximum=128 * 1024
        )
        if not any(line.strip() == "Success" for line in text.splitlines()):
            raise ReferencePackageInstallError(
                "same-signature downgrade APK install did not report Success"
            )
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
        self._mutating_checked(
            ("shell", "rm", "-rf", "--", path), timeout=180.0
        )
        if self.path_exists(path):
            raise ReferencePackageInstallError("device tree remains after removal")

    def remove_bound_entry(
        self, path: str, expected: RemoteEntryIdentity
    ) -> None:
        if not path.startswith(ReferencePackageInstaller.REFERENCE_ROOT + "/"):
            raise ReferencePackageInstallError("bound removal is outside reference root")
        if path.rsplit("/", 1)[-1] != expected.name:
            raise ReferencePackageInstallError("bound removal name differs")
        actual = self.entry_identity(path)
        if actual is None or actual != expected:
            raise ReferencePackageInstallError("bound removal identity differs")
        self._mutating_checked(
            ("shell", "rm", "-rf", "--", path), timeout=180.0
        )
        if self.entry_identity(path) is not None:
            raise ReferencePackageInstallError("bound device entry remains after removal")

    def storage_footprint(
        self, path: str, *, private: bool = False
    ) -> StorageFootprint:
        if path == "installed-apk":
            remote = self._installed_base_path()
            result = self._checked(
                ("shell", "stat", "-c", "%s %b %B", remote), timeout=30.0
            )
            values = _strict_ascii_text(
                result.stdout, "installed APK footprint", maximum=4096
            ).strip().split()
            if len(values) != 3 or any(
                re.fullmatch(r"0|[1-9][0-9]*", value) is None for value in values
            ):
                raise ReferencePackageInstallError(
                    "installed APK footprint is malformed"
                )
            logical, blocks, block_size = (int(value) for value in values)
            return StorageFootprint(logical, blocks * block_size)
        if private:
            test = self._adb(
                ("shell", "run-as", self.APP_ID, "test", "-e", path),
                timeout=30.0,
                allow_failure=True,
            )
            prefix = ("shell", "run-as", self.APP_ID)
        else:
            test = self._adb(
                ("shell", "test", "-e", path),
                timeout=30.0,
                allow_failure=True,
            )
            prefix = ("shell",)
        if test.return_code == 1 and not test.stdout:
            return StorageFootprint(0, 0)
        if test.return_code != 0:
            raise ReferencePackageInstallError("storage footprint path probe failed")
        allocated_result = self._checked(
            (*prefix, "du", "-sk", "--", path), timeout=180.0
        )
        allocated_text = _strict_ascii_text(
            allocated_result.stdout, "allocated storage footprint", maximum=4096
        ).strip()
        allocated_match = re.fullmatch(r"(0|[1-9][0-9]*)[ \t]+.+", allocated_text)
        if allocated_match is None:
            raise ReferencePackageInstallError(
                "allocated storage footprint is malformed"
            )
        logical_result = self._checked(
            (
                *prefix,
                "find",
                path,
                "-type",
                "f",
                "-exec",
                "stat",
                "-c",
                "%s",
                "{}",
                "+",
            ),
            timeout=180.0,
        )
        logical_text = _strict_ascii_text(
            logical_result.stdout, "logical storage footprint", maximum=MAX_JSON_BYTES
        )
        sizes = [line.strip() for line in logical_text.splitlines() if line.strip()]
        if any(re.fullmatch(r"0|[1-9][0-9]*", value) is None for value in sizes):
            raise ReferencePackageInstallError(
                "logical storage footprint is malformed"
            )
        return StorageFootprint(
            logical_bytes=sum(int(value) for value in sizes),
            allocated_bytes=int(allocated_match.group(1)) * 1024,
        )

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

    def _mutating_checked(
        self, arguments: Sequence[str], *, timeout: float | None
    ) -> CommandResult:
        result = self._mutating_adb(
            arguments, timeout=timeout, allow_failure=True
        )
        if result.return_code != 0:
            raise ReferencePackageInstallError(
                f"adb command failed ({arguments[0]}): {_bounded_error(result.stderr)}"
            )
        return result

    def _mutating_adb(
        self,
        arguments: Sequence[str],
        *,
        timeout: float | None,
        allow_failure: bool,
    ) -> CommandResult:
        self._guard_device_mutation()
        return self._adb(arguments, timeout=timeout, allow_failure=allow_failure)

    def _guard_device_mutation(self) -> None:
        guard = self._mutation_guard
        if guard is None:
            raise ReferencePackageInstallError(
                "device mutation requires an active lease guard"
            )
        guard()

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
    parser.add_argument(
        "--install-policy",
        choices=INSTALL_POLICIES,
        default=INSTALL_POLICY_RELEASE,
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--validate-only", action="store_true")
    mode.add_argument("--execute", action="store_true")
    mode.add_argument("--finalize-acceptance", action="store_true")
    mode.add_argument("--rollback", action="store_true")
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
        install_policy=parsed.install_policy,
        require_install_policy_binding=(
            parsed.install_policy
            == INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION
        ),
    )
    if parsed.validate_only:
        print(_canonical_json_bytes(_plan_document(plan)).decode("utf-8"), end="")
        return 0
    if parsed.lease_helper is None or parsed.thread_id is None or parsed.evidence_directory is None:
        parser.error(
            "device transaction modes require --lease-helper, --thread-id, and "
            "--evidence-directory"
        )
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
    if parsed.execute:
        installer.install()
        receipt_path = evidence / "pending-acceptance.json"
    elif parsed.finalize_acceptance:
        installer.finalize_acceptance()
        receipt_path = evidence / "final-success.json"
    else:
        installer.rollback()
        receipt_path = evidence / "rollback-receipt.json"
    print(receipt_path.read_text("utf-8"), end="")
    return 0


def _entry_document(entry: RemoteEntryIdentity) -> dict[str, object]:
    return {
        "device": entry.device,
        "inode": entry.inode,
        "kind": entry.kind,
        "name": entry.name,
    }


def _entry_from_document(value: object) -> RemoteEntryIdentity:
    document = _exact_mapping(value, "remote entry identity")
    _exact_fields(
        document, {"device", "inode", "kind", "name"}, "remote entry identity fields"
    )
    name = _exact_string(document.get("name"), "remote entry name")
    if re.fullmatch(r"[A-Za-z0-9._-]+", name) is None:
        raise ReferencePackageInstallError("remote entry name is unsafe")
    kind = _exact_string(document.get("kind"), "remote entry kind")
    if kind not in {"directory", "regular"}:
        raise ReferencePackageInstallError("remote entry kind is unsupported")
    return RemoteEntryIdentity(
        name=name,
        kind=kind,
        device=_exact_integer(document.get("device"), "remote entry device"),
        inode=_exact_integer(document.get("inode"), "remote entry inode"),
    )


def _prestate_document(prestate: DevicePrestate) -> dict[str, object]:
    return {
        "apk": {
            "backupPath": str(prestate.apk_backup_path.resolve(strict=True)),
            "bytes": prestate.apk_bytes,
            "sha256": prestate.apk_sha256,
        },
        "finalPackageEntry": (
            _entry_document(prestate.final_package_entry)
            if prestate.final_package_entry is not None
            else None
        ),
        "listener": {
            "approval": prestate.listener_state.approval,
            "bound": prestate.listener_state.bound,
        },
        "preferences": {
            "base64": base64.b64encode(prestate.preferences).decode("ascii"),
            "present": prestate.preferences_present,
        },
        "process": {
            "activeServices": list(prestate.process_state.active_services),
            "deviceLocked": prestate.process_state.device_locked,
            "focusedComponent": prestate.process_state.focused_component,
            "pids": list(prestate.process_state.pids),
            "resumedComponent": prestate.process_state.resumed_component,
            "runningReason": prestate.process_state.running_reason,
            "screenInteractive": prestate.process_state.screen_interactive,
        },
        "referenceInventory": [
            _entry_document(item) for item in prestate.reference_inventory
        ],
        "serial": prestate.serial,
    }


def _prestate_from_document(value: object) -> DevicePrestate:
    document = _exact_mapping(value, "journal prestate")
    apk = _exact_mapping(document.get("apk"), "journal prestate APK")
    backup = _real_file(
        Path(_exact_string(apk.get("backupPath"), "journal APK backup path")),
        "journal APK backup",
    )
    expected_bytes = _exact_integer(apk.get("bytes"), "journal APK bytes")
    expected_sha = _exact_sha256(apk.get("sha256"), "journal APK SHA-256")
    actual_apk = _hash_regular_file(backup, "journal APK backup")
    if actual_apk.byte_length != expected_bytes or actual_apk.sha256 != expected_sha:
        raise ReferencePackageInstallError("journal APK backup identity differs")
    preferences = _exact_mapping(
        document.get("preferences"), "journal preferences"
    )
    encoded = preferences.get("base64")
    if type(encoded) is not str:
        raise ReferencePackageInstallError("journal preference bytes are malformed")
    try:
        raw_preferences = base64.b64decode(encoded.encode("ascii"), validate=True)
    except (UnicodeError, ValueError) as error:
        raise ReferencePackageInstallError(
            "journal preference bytes are malformed"
        ) from error
    listener = _exact_mapping(document.get("listener"), "journal listener")
    approval = listener.get("approval")
    if type(approval) is not str:
        raise ReferencePackageInstallError("journal listener approval is malformed")
    process = _exact_mapping(document.get("process"), "journal process")
    raw_pids = process.get("pids")
    if type(raw_pids) is not list or any(
        type(pid) is not str or re.fullmatch(r"[1-9][0-9]*", pid) is None
        for pid in raw_pids
    ):
        raise ReferencePackageInstallError("journal process PIDs are malformed")
    resumed = process.get("resumedComponent")
    focused = process.get("focusedComponent")
    if resumed is not None and type(resumed) is not str:
        raise ReferencePackageInstallError("journal resumed component is malformed")
    if focused is not None and type(focused) is not str:
        raise ReferencePackageInstallError("journal focused component is malformed")
    raw_services = process.get("activeServices")
    if type(raw_services) is not list or any(
        type(component) is not str for component in raw_services
    ):
        raise ReferencePackageInstallError("journal active services are malformed")
    running_reason = _exact_string(
        process.get("runningReason"), "journal process running reason"
    )
    raw_inventory = document.get("referenceInventory")
    if type(raw_inventory) is not list:
        raise ReferencePackageInstallError("journal reference inventory is malformed")
    inventory = tuple(_entry_from_document(item) for item in raw_inventory)
    final_value = document.get("finalPackageEntry")
    final_entry = (
        _entry_from_document(final_value) if final_value is not None else None
    )
    serial = _exact_string(document.get("serial"), "journal device serial")
    return DevicePrestate(
        serial=serial,
        apk_backup_path=backup,
        apk_bytes=expected_bytes,
        apk_sha256=expected_sha,
        preferences=raw_preferences,
        app_was_running=bool(raw_pids),
        final_package_was_present=final_entry is not None,
        preferences_present=_exact_bool(
            preferences.get("present"), "journal preference presence"
        ),
        listener_state=ListenerState(
            approval=approval,
            bound=_exact_bool(listener.get("bound"), "journal listener binding"),
        ),
        process_state=AppProcessState(
            pids=tuple(raw_pids),
            resumed_component=resumed,
            focused_component=focused,
            screen_interactive=_exact_bool(
                process.get("screenInteractive"), "journal screen interactive state"
            ),
            device_locked=_exact_bool(
                process.get("deviceLocked"), "journal device locked state"
            ),
            active_services=tuple(raw_services),
            running_reason=running_reason,
        ),
        reference_inventory=inventory,
        final_package_entry=final_entry,
    )


def _journal_paths(journal: Mapping[str, object]) -> dict[str, str]:
    document = _exact_mapping(journal.get("paths"), "journal paths")
    token = _exact_string(journal.get("transactionToken"), "transaction token")
    if re.fullmatch(r"[0-9a-f]{32}", token) is None:
        raise ReferencePackageInstallError("transaction token is malformed")
    expected = {
        "stage": f"{ReferencePackageInstaller.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.stage",
        "backup": f"{ReferencePackageInstaller.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.backup",
        "failed": f"{ReferencePackageInstaller.REFERENCE_ROOT}/.{PACKAGE_ID}.exp8-install-{token}.failed",
    }
    if document != expected:
        raise ReferencePackageInstallError("journal tokenized paths differ")
    return expected


def _component_is_app(component: str | None) -> bool:
    return component is not None and (
        component.startswith("com.flightalert/")
        or component.startswith("com.flightalert.")
    )


def _approval_contains_listener(approval: str, component: str) -> bool:
    return component in {item for item in approval.split(":") if item}


def _atomic_write_json(path: Path, document: object) -> None:
    _atomic_write_bytes(path, _canonical_json_bytes(document))


def _atomic_write_bytes(path: Path, raw: bytes) -> None:
    path = Path(path)
    if not path.parent.is_dir():
        raise ReferencePackageInstallError("atomic write parent directory is missing")
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("xb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        if os.name == "nt":
            _windows_replace_write_through(temporary, path)
        else:
            os.replace(temporary, path)
            _fsync_parent_directory(path.parent)
        if path.read_bytes() != raw:
            raise ReferencePackageInstallError("atomic write readback differs")
    except ReferencePackageInstallError:
        raise
    except OSError as error:
        raise ReferencePackageInstallError("atomic durable write failed") from error
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass


def _fsync_parent_directory(directory: Path) -> None:
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _windows_replace_write_through(source: Path, destination: Path) -> None:
    import ctypes

    move_file_ex = ctypes.windll.kernel32.MoveFileExW
    move_file_ex.argtypes = [ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint]
    move_file_ex.restype = ctypes.c_int
    replace_existing = 0x1
    write_through = 0x8
    if not move_file_ex(
        str(source), str(destination), replace_existing | write_through
    ):
        raise OSError(ctypes.get_last_error(), "MoveFileExW failed")


def _plan_document(plan: HostInstallPlan) -> dict[str, object]:
    document: dict[str, object] = {
        "apk": {
            "bytes": plan.apk_bytes,
            "path": str(plan.apk_path),
            "sha256": plan.apk_sha256,
            "statIdentity": list(plan.apk_stat_identity),
        },
        "files": [
            {
                "bytes": item.byte_length,
                "name": item.name,
                "sha256": item.sha256,
                "statIdentity": list(item.stat_identity),
            }
            for item in plan.package_files
        ],
        "hardStrictlyBelow": plan.hard_strictly_below,
        "packageBytes": plan.package_bytes,
        "packageId": plan.package_id,
        "packagePath": str(plan.package_directory),
        "packageDirectoryIdentity": list(plan.package_directory_identity),
        "packageInventory": [
            {"name": name, "statIdentity": list(identity)}
            for name, identity in plan.package_inventory
        ],
        "preferredStrictlyBelow": plan.preferred_strictly_below,
        "schema": "flightalert.experiment8.reference-install-plan.v1",
        "totalFootprintBytes": plan.total_footprint_bytes,
    }
    if plan.install_policy != INSTALL_POLICY_RELEASE:
        document["installPolicy"] = plan.install_policy
        document["declaredScopeComplete"] = plan.declared_scope_complete
        document["wholeEarthComplete"] = plan.whole_earth_complete
    return document


def _json_value(value: object) -> object:
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    return value


def _validated_authority_size_binding(value: object, label: str) -> Mapping[str, object]:
    binding = _exact_mapping(value, label)
    _exact_fields(
        binding,
        {"document", "documentSha256", "mode", "module", "schema"},
        f"{label} fields",
    )
    if binding.get("schema") != (
        "flightalert.experiment8.reference-size-policy-binding.v1"
    ):
        raise ReferencePackageInstallError(f"{label} schema differs")
    document = _exact_mapping(binding.get("document"), f"{label} document")
    if document != _json_value(reference_size_policy_document()):
        raise ReferencePackageInstallError(f"{label} document differs")
    if _exact_sha256(
        binding.get("documentSha256"), f"{label} document SHA-256"
    ) != hashlib.sha256(_canonical_json_bytes(document)).hexdigest():
        raise ReferencePackageInstallError(f"{label} document hash differs")
    mode = _exact_string(binding.get("mode"), f"{label} mode")
    if mode not in REFERENCE_SIZE_POLICY_MODES:
        raise ReferencePackageInstallError(f"{label} mode differs")
    module = _exact_mapping(binding.get("module"), f"{label} module")
    _exact_fields(module, {"bytes", "sha256"}, f"{label} module fields")
    if _exact_integer(module.get("bytes"), f"{label} module bytes") <= 0:
        raise ReferencePackageInstallError(f"{label} module is empty")
    _exact_sha256(module.get("sha256"), f"{label} module SHA-256")
    return binding


def _validated_authority_size_decision(
    value: object,
    *,
    binding: Mapping[str, object],
    required_package_bytes: int,
    label: str,
) -> Mapping[str, object]:
    decision = _exact_mapping(value, label)
    mode = binding["mode"]
    expected_fields = {
        "authorized",
        "availableDestinationBytes",
        "hardComponentPackageCeilingExceeded",
        "hardMandatoryPhoneFootprintCeilingExceeded",
        "mandatoryPhoneFootprintBytes",
        "mode",
        "preferredComponentPackageCeilingExceeded",
        "preferredMandatoryPhoneFootprintCeilingExceeded",
        "requiredPackageBytes",
        "requiredWithReserveBytes",
        "schema",
    }
    visual = mode == COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    if visual:
        expected_fields.update(
            {
                "publicationBoundaryAuthorized",
                "publicationBoundaryDestinationFreeBytes",
                "publicationBoundaryRequiredReserveBytes",
            }
        )
    _exact_fields(decision, expected_fields, f"{label} fields")
    available = decision.get("availableDestinationBytes")
    if visual:
        available = _exact_integer(
            available, f"{label} available destination bytes"
        )
    elif available is not None:
        raise ReferencePackageInstallError(
            f"{label} budgeted destination capacity must be null"
        )
    expected = dict(
        evaluate_reference_size_policy(
            mode=mode,
            required_package_bytes=required_package_bytes,
            available_destination_bytes=available,
        )
    )
    if visual:
        boundary = _exact_integer(
            decision.get("publicationBoundaryDestinationFreeBytes"),
            f"{label} publication boundary bytes",
        )
        if _exact_integer(
            decision.get("publicationBoundaryRequiredReserveBytes"),
            f"{label} publication boundary reserve",
        ) != DESTINATION_RESERVE_BYTES:
            raise ReferencePackageInstallError(
                f"{label} publication boundary reserve differs"
            )
        boundary_authorized = boundary >= DESTINATION_RESERVE_BYTES
        expected.update(
            {
                "authorized": bool(expected["authorized"])
                and boundary_authorized,
                "publicationBoundaryAuthorized": boundary_authorized,
                "publicationBoundaryDestinationFreeBytes": boundary,
                "publicationBoundaryRequiredReserveBytes": (
                    DESTINATION_RESERVE_BYTES
                ),
            }
        )
    if decision != expected:
        raise ReferencePackageInstallError(f"{label} accounting differs")
    if decision.get("authorized") is not True:
        raise ReferencePackageInstallError(f"{label} is not authorized")
    return decision


def _validate_receipts(
    *,
    manifest: Mapping[str, object],
    merge_receipt: Mapping[str, object],
    finalization_receipt: Mapping[str, object],
    file_by_name: Mapping[str, InstallFile],
    require_primary_whole_earth: bool = True,
    install_policy: str = INSTALL_POLICY_RELEASE,
) -> None:
    merge_receipt_schema = _exact_string(
        merge_receipt.get("schema"), "merge receipt schema"
    )
    finalization_receipt_schema = _exact_string(
        finalization_receipt.get("schema"), "finalization receipt schema"
    )
    authority_v2 = merge_receipt_schema == AUTHORITY_MERGE_RECEIPT_SCHEMA
    if authority_v2 != (
        finalization_receipt_schema == AUTHORITY_FINALIZATION_RECEIPT_SCHEMA
    ):
        raise ReferencePackageInstallError(
            "merge and finalization authority schemas differ"
        )
    merge_receipt_fields = {
        "coverage",
        "inputs",
        "mergerSha256",
        "outputFiles",
        "packageId",
        "rendererSemanticStreamSha256",
        "schema",
        "subtypeCounts",
    }
    finalization_receipt_fields = {
        "coverage",
        "finalizerSha256",
        "inputFiles",
        "outputFiles",
        "packageId",
        "rendererContractSha256",
        "rendererSemanticStreamSha256",
        "schema",
        "subtypeCounts",
    }
    if authority_v2:
        merge_receipt_fields.update({"authorityReceipts", "sizePolicy"})
        if "accountingConvergencePadding" in merge_receipt:
            padding = merge_receipt["accountingConvergencePadding"]
            if type(padding) is not str or len(padding) > 63 or set(padding) - {"x"}:
                raise ReferencePackageInstallError(
                    "merge accounting convergence padding is malformed"
                )
            merge_receipt_fields.add("accountingConvergencePadding")
        finalization_receipt_fields.update(
            {"authorityReceipts", "mergeReceipt", "sizePolicy"}
        )
    _exact_fields(
        merge_receipt,
        merge_receipt_fields,
        "merge receipt schema fields",
    )
    _exact_fields(
        finalization_receipt,
        finalization_receipt_fields,
        "finalization receipt schema fields",
    )
    if not authority_v2 and merge_receipt_schema != (
        "flightalert.experiment8.v3-package-merge-receipt.v1"
    ):
        raise ReferencePackageInstallError("merge receipt schema differs")
    if not authority_v2 and finalization_receipt_schema != (
        "flightalert.experiment8.v3-class-catalog-finalization-receipt.v1"
    ):
        raise ReferencePackageInstallError("finalization receipt schema differs")
    for receipt, label in (
        (merge_receipt, "merge receipt"),
        (finalization_receipt, "finalization receipt"),
    ):
        if _exact_string(receipt.get("packageId"), f"{label} package ID") != PACKAGE_ID:
            raise ReferencePackageInstallError(f"{label} package ID differs")

    merge = _exact_mapping(manifest.get("merge"), "manifest merge contract")
    manifest_merge_fields = {"inputs", "mergerSha256", "output", "schema"}
    if authority_v2:
        manifest_merge_fields.update({"authorityReceipts", "sizePolicy"})
        if "authoritySemanticVerification" in merge:
            if (
                merge.get("authoritySemanticVerification")
                != RECEIPT_BOUND_VISUAL_AUTHORITY_SEMANTIC_VERIFICATION
            ):
                raise ReferencePackageInstallError(
                    "manifest merge semantic verification differs"
                )
            manifest_merge_fields.add("authoritySemanticVerification")
    _exact_fields(
        merge,
        manifest_merge_fields,
        "manifest merge schema fields",
    )
    expected_merge_schema = (
        AUTHORITY_MERGE_SCHEMA
        if authority_v2
        else "flightalert.experiment8.v3-package-merge.v1"
    )
    if _exact_string(
        merge.get("schema"), "manifest merge schema"
    ) != expected_merge_schema:
        raise ReferencePackageInstallError("manifest merge schema differs")
    manifest_inputs = _merge_input_bindings(merge.get("inputs"), "manifest merge inputs")
    receipt_inputs = _merge_input_bindings(
        merge_receipt.get("inputs"), "merge receipt inputs"
    )
    if manifest_inputs != receipt_inputs:
        raise ReferencePackageInstallError("merge inputs differ from manifest")
    merger_sha256 = _exact_sha256(
        merge.get("mergerSha256"), "manifest merger SHA-256"
    )
    if _exact_sha256(
        merge_receipt.get("mergerSha256"), "merge receipt merger SHA-256"
    ) != merger_sha256:
        raise ReferencePackageInstallError("merge receipt merger SHA-256 differs")
    if merger_sha256 not in _checkout_stable_tool_hashes("v3_package_merger.py"):
        raise ReferencePackageInstallError("merger source SHA-256 differs")
    if authority_v2:
        manifest_authority = merge.get("authorityReceipts")
        receipt_authority = merge_receipt.get("authorityReceipts")
        final_authority = finalization_receipt.get("authorityReceipts")
        if (
            manifest_authority != receipt_authority
            or receipt_authority != final_authority
        ):
            raise ReferencePackageInstallError("authority receipts differ")
        if type(receipt_authority) is not list or not receipt_authority:
            raise ReferencePackageInstallError("authority receipts are empty")
        supplement_input_bindings: dict[
            str, dict[str, tuple[int, str]]
        ] = {}
        for binding in manifest_inputs[1:]:
            supplement_package_id = str(binding[1])
            if supplement_package_id in supplement_input_bindings:
                raise ReferencePackageInstallError(
                    "supplement input package IDs are ambiguous"
                )
            supplement_input_bindings[supplement_package_id] = {
                "manifest.json": (int(binding[2]), str(binding[3])),
                "records.fadictpack": (int(binding[4]), str(binding[5])),
                "tile-index.bin": (int(binding[6]), str(binding[7])),
            }
        authority_package_ids: set[str] = set()
        for index, item in enumerate(receipt_authority):
            authority = _exact_mapping(
                item, f"authority receipt[{index}]"
            )
            _exact_fields(
                authority,
                {"bytes", "document", "packageId", "role", "sha256"},
                f"authority receipt[{index}] fields",
            )
            package_id = _exact_string(
                authority.get("packageId"),
                f"authority receipt[{index}] package ID",
            )
            if package_id in authority_package_ids:
                raise ReferencePackageInstallError(
                    "authority receipt package IDs repeat"
                )
            authority_package_ids.add(package_id)
            if authority.get("role") != "supplement":
                raise ReferencePackageInstallError(
                    "authority receipt role differs"
                )
            document = _exact_mapping(
                authority.get("document"),
                f"authority receipt[{index}] document",
            )
            _exact_fields(
                document,
                WATERWAY_BUILD_RECEIPT_FIELDS,
                f"authority receipt[{index}] document fields",
            )
            if document.get("schema") != WATERWAY_BUILD_RECEIPT_SCHEMA:
                raise ReferencePackageInstallError(
                    "authority receipt document schema differs"
                )
            if _exact_string(
                document.get("packageId"),
                f"authority receipt[{index}] document package ID",
            ) != package_id:
                raise ReferencePackageInstallError(
                    "authority receipt package ID differs"
                )
            if package_id not in supplement_input_bindings:
                raise ReferencePackageInstallError(
                    "authority receipt does not bind one supplement input"
                )
            authority_output_bindings = _binding_map(
                document.get("outputFiles"),
                f"authority receipt[{index}] output files",
                MERGED_FILE_NAMES,
            )
            if (
                authority_output_bindings
                != supplement_input_bindings[package_id]
            ):
                raise ReferencePackageInstallError(
                    "authority receipt output files differ from supplement input"
                )
            document_bytes = _canonical_json_bytes(document)
            if _exact_integer(
                authority.get("bytes"),
                f"authority receipt[{index}] bytes",
            ) != len(document_bytes) or _exact_sha256(
                authority.get("sha256"),
                f"authority receipt[{index}] SHA-256",
            ) != hashlib.sha256(document_bytes).hexdigest():
                raise ReferencePackageInstallError(
                    "authority receipt byte binding differs"
                )

    finalizer_sha256 = _exact_sha256(
        finalization_receipt.get("finalizerSha256"),
        "finalization receipt finalizer SHA-256",
    )
    if finalizer_sha256 not in _checkout_stable_tool_hashes(
        "v3_class_catalog_finalizer.py"
    ):
        raise ReferencePackageInstallError("finalizer source SHA-256 differs")

    manifest_coverage = _exact_mapping(manifest.get("coverage"), "manifest coverage")
    _exact_fields(
        manifest_coverage,
        {
            "completeDeclaredScope",
            "completeWholeEarthDictionary",
            "tileCount",
            "zoomRanges",
        },
        "manifest coverage schema fields",
    )
    merge_coverage = _exact_mapping(
        merge_receipt.get("coverage"), "merge receipt coverage"
    )
    _exact_fields(
        merge_coverage,
        {
            "completeDeclaredScope",
            "completeWholeEarthDictionary",
            "presentTileCount",
            "primaryWholeEarthPreserved",
            "tileCount",
            "zoomRanges",
        },
        "merge coverage schema fields",
    )
    for key in (
        "completeDeclaredScope",
        "completeWholeEarthDictionary",
        "tileCount",
        "zoomRanges",
    ):
        if merge_coverage.get(key) != manifest_coverage.get(key):
            raise ReferencePackageInstallError(f"merge coverage differs for {key}")
    tile_count = _exact_integer(manifest_coverage.get("tileCount"), "declared tile count")
    if tile_count <= 0:
        raise ReferencePackageInstallError("merge coverage tile count is empty")
    present_tile_count = _exact_integer(
        merge_coverage.get("presentTileCount"), "merge present tile count"
    )
    if (
        present_tile_count != tile_count
        and install_policy != INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION
    ):
        raise ReferencePackageInstallError("merge coverage does not contain every tile")
    primary_whole_earth_preserved = _exact_bool(
        merge_coverage.get("primaryWholeEarthPreserved"),
        "merge primary whole-earth preservation",
    )
    if require_primary_whole_earth and not primary_whole_earth_preserved:
        raise ReferencePackageInstallError("merge coverage did not preserve the primary")
    if type(manifest_coverage.get("zoomRanges")) is not list:
        raise ReferencePackageInstallError("merge coverage zoom ranges have the wrong type")

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

    final_inputs = _finalization_input_bindings(
        finalization_receipt.get("inputFiles")
    )
    if final_inputs["manifest.json"] != merged_bindings["manifest.json"]:
        raise ReferencePackageInstallError(
            "merge-to-finalization manifest transition differs"
        )
    for name in ("records.fadictpack", "tile-index.bin"):
        if final_inputs[name] != merged_bindings[name]:
            raise ReferencePackageInstallError(
                f"merge-to-finalization transition differs for {name}"
            )
    if authority_v2:
        merge_receipt_binding = _exact_mapping(
            finalization_receipt.get("mergeReceipt"),
            "finalization merge receipt binding",
        )
        _exact_fields(
            merge_receipt_binding,
            {"bytes", "name", "sha256"},
            "finalization merge receipt binding fields",
        )
        if merge_receipt_binding.get("name") != "merge-receipt.json":
            raise ReferencePackageInstallError(
                "finalization merge receipt name differs"
            )
        actual_merge_receipt = file_by_name["merge-receipt.json"]
        if _exact_integer(
            merge_receipt_binding.get("bytes"),
            "finalization merge receipt bytes",
        ) != actual_merge_receipt.byte_length or _exact_sha256(
            merge_receipt_binding.get("sha256"),
            "finalization merge receipt SHA-256",
        ) != actual_merge_receipt.sha256:
            raise ReferencePackageInstallError(
                "finalization merge receipt binding differs"
            )

        manifest_size_policy = merge.get("sizePolicy")
        merge_size_policy = merge_receipt.get("sizePolicy")
        if manifest_size_policy != merge_size_policy:
            raise ReferencePackageInstallError(
                "merge size policies differ"
            )
        pre_size_policy = _exact_mapping(
            merge_size_policy, "merge size policy"
        )
        _exact_fields(
            pre_size_policy,
            {"accountingScope", "binding", "decision"},
            "merge size policy fields",
        )
        if pre_size_policy.get("accountingScope") != (
            "merge-output-before-class-catalog-finalization"
        ):
            raise ReferencePackageInstallError(
                "merge size-policy scope differs"
            )
        pre_binding = _validated_authority_size_binding(
            pre_size_policy.get("binding"), "merge size-policy binding"
        )
        expected_size_mode = (
            COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
            if install_policy
            == INSTALL_POLICY_FULL_FIDELITY_VISUAL_EVALUATION
            else BUDGETED_RELEASE_V1
        )
        if pre_binding["mode"] != expected_size_mode:
            raise ReferencePackageInstallError(
                "authority size-policy mode differs from install policy"
            )
        pre_required_bytes = (
            sum(byte_count for byte_count, _ in merged_bindings.values())
            + actual_merge_receipt.byte_length
        )
        _validated_authority_size_decision(
            pre_size_policy.get("decision"),
            binding=pre_binding,
            required_package_bytes=pre_required_bytes,
            label="merge size-policy decision",
        )

        final_size_policy = _exact_mapping(
            finalization_receipt.get("sizePolicy"),
            "finalization size policy",
        )
        _exact_fields(
            final_size_policy,
            {"accountingScope", "binding", "decision"},
            "finalization size policy fields",
        )
        if final_size_policy.get("accountingScope") != FINAL_SIZE_ACCOUNTING_SCOPE:
            raise ReferencePackageInstallError(
                "finalization size-policy scope differs"
            )
        final_binding = _validated_authority_size_binding(
            final_size_policy.get("binding"),
            "finalization size-policy binding",
        )
        if final_binding != pre_binding:
            raise ReferencePackageInstallError(
                "finalization size-policy binding differs"
            )
        _validated_authority_size_decision(
            final_size_policy.get("decision"),
            binding=final_binding,
            required_package_bytes=sum(
                item.byte_length for item in file_by_name.values()
            ),
            label="finalization size-policy decision",
        )

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
    _exact_fields(
        catalog_contract,
        {"catalogSha256", "rendererContractSha256"},
        "manifest class catalog schema fields",
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
    if _exact_sha256(
        finalization_receipt.get("rendererContractSha256"),
        "finalization renderer contract SHA-256",
    ) != semantic:
        raise ReferencePackageInstallError("finalization renderer contract differs")
    if _exact_sha256(
        merge_receipt.get("rendererSemanticStreamSha256"),
        "merge renderer semantic stream SHA-256",
    ) != semantic:
        raise ReferencePackageInstallError("merge semantic stream differs")

    if authority_v2:
        merge_counts = _authority_subtype_counts(
            merge_receipt.get("subtypeCounts"), "merge subtype counts"
        )
        final_counts = _authority_subtype_counts(
            finalization_receipt.get("subtypeCounts"),
            "finalization subtype counts",
        )
        renderer_record_count = sum(item[2] for item in final_counts)
    else:
        merge_counts = _subtype_counts(
            merge_receipt.get("subtypeCounts"), "merge subtype counts"
        )
        final_counts = _subtype_counts(
            finalization_receipt.get("subtypeCounts"),
            "finalization subtype counts",
        )
        renderer_record_count = sum(final_counts.values())
    if merge_counts != final_counts:
        raise ReferencePackageInstallError("merge and finalization subtype counts differ")
    final_coverage = _exact_mapping(
        finalization_receipt.get("coverage"), "finalization coverage"
    )
    _exact_fields(
        final_coverage,
        {
            "declaredTileCount",
            "missingTileCount",
            "presentTileCount",
            "rendererRecordCount",
        },
        "finalization coverage schema fields",
    )
    if _exact_integer(
        final_coverage.get("declaredTileCount"), "finalization declared tile count"
    ) != tile_count:
        raise ReferencePackageInstallError("finalization declared tile count differs")
    final_present_tile_count = _exact_integer(
        final_coverage.get("presentTileCount"), "finalization present tile count"
    )
    if final_present_tile_count != present_tile_count:
        raise ReferencePackageInstallError("finalization present tile count differs")
    final_missing_tile_count = _exact_integer(
        final_coverage.get("missingTileCount"), "finalization missing tile count"
    )
    expected_missing_tile_count = tile_count - present_tile_count
    if final_missing_tile_count != expected_missing_tile_count:
        raise ReferencePackageInstallError("final package has missing declared tiles")
    if _exact_integer(
        final_coverage.get("rendererRecordCount"),
        "finalization renderer record count",
    ) != renderer_record_count:
        raise ReferencePackageInstallError("finalization renderer record count differs")


def _validate_result_identity(
    *,
    result: Mapping[str, object],
    package_directory: Path,
    package_bytes: int,
    apk: InstallFile,
    install_policy: str = INSTALL_POLICY_RELEASE,
    require_install_policy_binding: bool = False,
) -> str:
    if result.get("schema") != "flightalert.experiment8.final-package-monitor-result.v1":
        raise ReferencePackageInstallError("final result schema differs")
    state = _exact_string(result.get("state"), "final result state")
    allowed_states = (
        {FINAL_RESULT_STATE_COMPLETE}
        if install_policy == INSTALL_POLICY_RELEASE
        else {
            FINAL_RESULT_STATE_COMPLETE,
            FINAL_RESULT_STATE_FAILED_HARD_CEILING,
        }
    )
    if state not in allowed_states:
        raise ReferencePackageInstallError(
            "final result state is not eligible for the install policy"
        )
    rebind = result.get("rebind")
    if rebind is None:
        if require_install_policy_binding:
            raise ReferencePackageInstallError(
                "final result install policy binding is missing"
            )
    else:
        rebind_document = _exact_mapping(rebind, "final result rebind")
        bound_policy = rebind_document.get("installPolicy")
        if bound_policy is None:
            if require_install_policy_binding:
                raise ReferencePackageInstallError(
                    "final result install policy binding is missing"
                )
        elif _validated_install_policy(bound_policy) != install_policy:
            raise ReferencePackageInstallError(
                "final result install policy binding differs"
            )
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
    return state


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
        _exact_fields(
            document,
            {"bytes", "name", "sha256"},
            f"{label}[{index}] schema fields",
        )
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


def _merge_input_bindings(value: object, label: str) -> tuple[tuple[object, ...], ...]:
    if type(value) is not list or not value:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    bindings: list[tuple[object, ...]] = []
    expected = {
        "manifestBytes",
        "manifestSha256",
        "packageId",
        "recordsBytes",
        "recordsSha256",
        "role",
        "tileIndexBytes",
        "tileIndexSha256",
    }
    for index, item in enumerate(value):
        document = _exact_mapping(item, f"{label}[{index}]")
        _exact_fields(document, expected, f"{label}[{index}] schema fields")
        binding = (
            _exact_string(document.get("role"), f"{label}[{index}] role"),
            _exact_string(document.get("packageId"), f"{label}[{index}] package ID"),
            _exact_integer(document.get("manifestBytes"), f"{label}[{index}] manifest bytes"),
            _exact_sha256(document.get("manifestSha256"), f"{label}[{index}] manifest SHA-256"),
            _exact_integer(document.get("recordsBytes"), f"{label}[{index}] records bytes"),
            _exact_sha256(document.get("recordsSha256"), f"{label}[{index}] records SHA-256"),
            _exact_integer(document.get("tileIndexBytes"), f"{label}[{index}] index bytes"),
            _exact_sha256(document.get("tileIndexSha256"), f"{label}[{index}] index SHA-256"),
        )
        bindings.append(binding)
    roles = tuple(item[0] for item in bindings)
    if roles[0] != "primary" or any(role != "supplement" for role in roles[1:]):
        raise ReferencePackageInstallError(f"{label} roles are not canonical")
    return tuple(bindings)


def _finalization_input_bindings(value: object) -> dict[str, tuple[int, str]]:
    label = "finalization receipt input files"
    if type(value) is not list:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    result: dict[str, tuple[int, str]] = {}
    for index, item in enumerate(value):
        document = _exact_mapping(item, f"{label}[{index}]")
        name = _exact_string(document.get("name"), f"{label}[{index}] name")
        expected_fields = {"bytes", "name", "sha256"}
        if name == "manifest.json":
            expected_fields.add("role")
            if (
                _exact_string(document.get("role"), f"{label}[{index}] role")
                != "base-manifest-without-class-catalog"
            ):
                raise ReferencePackageInstallError(
                    "finalization manifest input role differs"
                )
        _exact_fields(document, expected_fields, f"{label}[{index}] schema fields")
        if name in result:
            raise ReferencePackageInstallError(f"{label} repeats {name}")
        result[name] = (
            _exact_integer(document.get("bytes"), f"{label}[{index}] bytes"),
            _exact_sha256(document.get("sha256"), f"{label}[{index}] SHA-256"),
        )
    if tuple(sorted(result)) != tuple(sorted(MERGED_FILE_NAMES)):
        raise ReferencePackageInstallError(f"{label} inventory differs")
    return result


def _authority_subtype_counts(
    value: object,
    label: str,
) -> tuple[tuple[int, str, int, int, int], ...]:
    if type(value) is not list or not value:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    result: list[tuple[int, str, int, int, int]] = []
    previous_subtype = -1
    for index, item in enumerate(value):
        document = _exact_mapping(item, f"{label}[{index}]")
        _exact_fields(
            document,
            {
                "canonicalVariantIds",
                "distinctFeatureIds",
                "postings",
                "semanticSubtype",
                "semanticSubtypeName",
            },
            f"{label}[{index}] fields",
        )
        subtype = _exact_integer(
            document.get("semanticSubtype"), f"{label}[{index}] subtype"
        )
        if subtype <= previous_subtype:
            raise ReferencePackageInstallError(f"{label} are not strictly ordered")
        previous_subtype = subtype
        name = _exact_string(
            document.get("semanticSubtypeName"), f"{label}[{index}] name"
        )
        if re.fullmatch(r"[A-Z][A-Z0-9_]*", name) is None:
            raise ReferencePackageInstallError(f"{label}[{index}] name is malformed")
        postings = _exact_integer(
            document.get("postings"), f"{label}[{index}] postings"
        )
        distinct = _exact_integer(
            document.get("distinctFeatureIds"),
            f"{label}[{index}] distinct feature IDs",
        )
        variants = _exact_integer(
            document.get("canonicalVariantIds"),
            f"{label}[{index}] canonical variant IDs",
        )
        if distinct > postings or variants > postings:
            raise ReferencePackageInstallError(
                f"{label}[{index}] counts are impossible"
            )
        result.append((subtype, name, postings, distinct, variants))
    return tuple(result)


def _subtype_counts(value: object, label: str) -> dict[str, int]:
    document = _exact_mapping(value, label)
    counts: dict[str, int] = {}
    for name, count in document.items():
        if type(name) is not str or not name or re.fullmatch(r"[a-z][a-z0-9-]*", name) is None:
            raise ReferencePackageInstallError(f"{label} contains a malformed subtype")
        counts[name] = _exact_integer(count, f"{label} {name}")
    return counts


def _exact_fields(
    document: Mapping[str, object], expected: set[str], label: str
) -> None:
    if set(document) != expected:
        raise ReferencePackageInstallError(f"{label} differ")


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


def _checkout_stable_tool_hashes(name: str) -> frozenset[str]:
    path = Path(__file__).resolve().parent / name
    raw = _read_bounded_regular_file(path, f"tool {name}", MAX_JSON_BYTES)
    if raw.startswith(b"\xef\xbb\xbf") or b"\0" in raw:
        raise ReferencePackageInstallError(f"tool {name} is not canonical UTF-8 source")
    try:
        raw.decode("utf-8", "strict")
    except UnicodeError as error:
        raise ReferencePackageInstallError(
            f"tool {name} is not canonical UTF-8 source"
        ) from error
    canonical = raw.replace(b"\r\n", b"\n")
    if b"\r" in canonical:
        raise ReferencePackageInstallError(f"tool {name} contains a lone carriage return")
    crlf = canonical.replace(b"\n", b"\r\n")
    return frozenset(
        hashlib.sha256(candidate).hexdigest() for candidate in (canonical, crlf)
    )


def _host_directory_inventory(
    directory: Path,
) -> tuple[tuple[str, tuple[int, int, int, int, int]], ...]:
    try:
        entries = tuple(sorted(directory.iterdir(), key=lambda item: item.name))
        inventory = tuple(
            (item.name, _identity(item.stat(follow_symlinks=False))) for item in entries
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "package directory inventory cannot be inspected"
        ) from error
    if tuple(name for name, _ in inventory) != PACKAGE_FILE_NAMES:
        raise ReferencePackageInstallError(
            "package does not have the exact six-file inventory"
        )
    if any(not stat.S_ISREG(identity[-1]) for _, identity in inventory):
        raise ReferencePackageInstallError("package inventory contains a non-file entry")
    return inventory


def _assert_host_plan_unchanged(plan: HostInstallPlan) -> None:
    try:
        directory_identity = _identity(
            plan.package_directory.stat(follow_symlinks=False)
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "package directory inventory changed before execution"
        ) from error
    if (
        directory_identity != plan.package_directory_identity
        or _host_directory_inventory(plan.package_directory) != plan.package_inventory
    ):
        raise ReferencePackageInstallError(
            "package directory inventory changed before execution"
        )
    for item in plan.package_files:
        _assert_install_file_unchanged(item)
    _assert_apk_plan_unchanged(plan)


def _required_install_free_bytes(plan: HostInstallPlan) -> int:
    total = 0
    for label, value in (
        ("package", plan.package_bytes),
        ("APK", plan.apk_bytes),
        ("mandatory reserve", MANDATORY_RESERVE_BYTES),
    ):
        if type(value) is not int or value < 0:
            raise ReferencePackageInstallError(
                f"{label} install byte budget is malformed"
            )
        if value > MAX_SIGNED_64 - total:
            raise ReferencePackageInstallError(
                "device install byte budget would overflow signed 64-bit"
            )
        total += value
    return total


def _assert_apk_plan_unchanged(plan: HostInstallPlan) -> None:
    apk = _hash_regular_file(plan.apk_path, "APK")
    if (
        apk.byte_length != plan.apk_bytes
        or apk.sha256 != plan.apk_sha256
        or apk.stat_identity != plan.apk_stat_identity
    ):
        raise ReferencePackageInstallError("APK changed after validation")


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


def _validated_install_policy(value: object) -> str:
    policy = _exact_string(value, "install policy")
    if policy not in INSTALL_POLICIES:
        raise ReferencePackageInstallError("install policy is unsupported")
    return policy


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


def _strict_utf8_text(raw: bytes, label: str, *, maximum: int) -> str:
    if type(raw) is not bytes or len(raw) > maximum or b"\0" in raw:
        raise ReferencePackageInstallError(f"{label} bytes are malformed")
    try:
        return raw.decode("utf-8", "strict").replace("\r\n", "\n")
    except UnicodeError as error:
        raise ReferencePackageInstallError(f"{label} is not strict UTF-8") from error


def _required_argument(arguments: Mapping[str, object], name: str) -> str:
    value = arguments.get(name)
    if type(value) is not str or not value.strip():
        raise ReferencePackageInstallError(f"device lease {name} is required")
    return value


def _validate_lease_acquisition_intent(
    intent: Mapping[str, object],
    arguments: Mapping[str, object],
    operation: str,
) -> None:
    _exact_fields(
        intent,
        {
            "deviceOperation",
            "expectedMinutes",
            "invocationId",
            "owner",
            "purpose",
            "scenario",
            "statefulEffects",
            "threadId",
        },
        "lease acquisition intent fields",
    )
    expected = {
        "deviceOperation": operation,
        "expectedMinutes": arguments["expected_minutes"],
        "owner": arguments["owner"],
        "purpose": arguments["purpose"],
        "invocationId": arguments["invocation_id"],
        "scenario": (
            f"{arguments['scenario']} [invocation:{arguments['invocation_id']}]"
        ),
        "statefulEffects": arguments["stateful_effects"],
    }
    for name, value in expected.items():
        if intent.get(name) != value:
            raise ReferencePackageInstallError(
                f"lease acquisition intent {name} differs"
            )
    thread_id = intent.get("threadId")
    if type(thread_id) is not str or not thread_id.strip():
        raise ReferencePackageInstallError(
            "lease acquisition intent thread ID is malformed"
        )


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
    "AppProcessState",
    "AtomicDeviceLeaseClient",
    "CommandResult",
    "DevicePrestate",
    "HostInstallPlan",
    "InstallFile",
    "ListenerState",
    "ReferencePackageInstaller",
    "ReferencePackageInstallError",
    "RemoteFileIdentity",
    "RemoteEntryIdentity",
    "StorageFootprint",
    "parse_adb_devices",
    "parse_listener_binding",
    "parse_remote_inventory",
]
