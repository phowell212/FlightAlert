from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import secrets
import shutil
import stat
import sys
import threading
import time
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO, Mapping

if __package__ in {None, ""}:
    repository_root = Path(__file__).resolve().parents[2]
    sys.path.insert(0, str(repository_root))

import tools.experiment8.osm_planet_selection as selection_module
import tools.experiment8.osm_planet_selection_verifier as verifier_module


_FIVE_GB_RESERVE_BYTES = 5_000_000_000
_MINIMUM_PRODUCTION_OUTPUT_BYTES = 64 * 1024 * 1024
_MINIMUM_VERIFIER_SCRATCH_BYTES = 256 * 1024 * 1024
_DOCUMENTARY_BYTES = 64 * 1024 * 1024
_CONFIG_SCHEMA = "flight-alert-exp8-osm-global-runner-config-v2"
_RUNTIME_SCHEMA = "flight-alert-exp8-osm-global-runtime-v1"
_CONTENT_SCHEMA = "flight-alert-exp8-osm-global-content-run-v2"
_PROCESS_IDENTITY_SCHEMA = "flight-alert-process-identity-v1"
_LOCK_SCHEMA = "flight-alert-exp8-osm-global-lock-v1"
_STATUS_SCHEMA = "flight-alert-exp8-osm-global-status-v1"
_EVENT_SCHEMA = "flight-alert-exp8-osm-global-event-v1"
_REFERENCE_LIMIT_DERIVATION = "authenticated-opl-bytes-floor-divided-by-two-v1"
_SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
_REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)


class RunnerError(RuntimeError):
    """The durable global selector run cannot be started or accepted."""


def canonical_json_bytes(document: Mapping[str, object]) -> bytes:
    try:
        encoded = json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        return (encoded + "\n").encode("utf-8")
    except (TypeError, ValueError, UnicodeError, RecursionError) as error:
        raise RunnerError(f"canonical JSON encoding failed: {error}") from error


def _sha256_identity(value: str, label: str) -> str:
    if type(value) is not str or _SHA256_PATTERN.fullmatch(value) is None:
        raise RunnerError(f"{label} must be a lowercase SHA-256 identity")
    return value


def _nonnegative_integer(value: object, label: str) -> int:
    if type(value) is not int or value < 0:
        raise RunnerError(f"{label} must be a nonnegative integer")
    return value


def _exact_keys(value: object, expected: set[str], label: str) -> dict[str, object]:
    if type(value) is not dict:
        raise RunnerError(f"{label} must be an object")
    actual = set(value)
    if actual != expected:
        raise RunnerError(
            f"{label} keys mismatch: expected {sorted(expected)}, got {sorted(actual)}"
        )
    return value


def _strict_json_bytes(raw: bytes, label: str) -> dict[str, object]:
    try:
        text = raw.decode("utf-8", errors="strict")

        def object_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
            result: dict[str, object] = {}
            for key, value in pairs:
                if key in result:
                    raise RunnerError(f"{label} contains duplicate key {key!r}")
                result[key] = value
            return result

        document = json.loads(
            text,
            object_pairs_hook=object_pairs,
            parse_constant=lambda value: (_ for _ in ()).throw(
                RunnerError(f"{label} contains non-finite number {value}")
            ),
        )
    except RunnerError:
        raise
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise RunnerError(f"{label} is not strict UTF-8 JSON: {error}") from error
    if type(document) is not dict:
        raise RunnerError(f"{label} root must be an object")
    return document


def _utc_now() -> str:
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def _fsync_directory(path: Path) -> None:
    if os.name == "nt":
        return
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _atomic_write(path: Path, raw: bytes) -> None:
    temporary = path.parent / f".{path.name}.{secrets.token_hex(16)}.tmp"
    try:
        with temporary.open("xb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        _fsync_directory(path.parent)
    except BaseException:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        raise


def _read_canonical_document(path: Path, label: str, *, max_bytes: int = 4 * 1024 * 1024) -> dict[str, object]:
    try:
        status = path.stat()
        if status.st_size > max_bytes:
            raise RunnerError(f"{label} exceeds its byte ceiling")
        raw = path.read_bytes()
    except RunnerError:
        raise
    except OSError as error:
        raise RunnerError(f"{label} cannot be read: {error}") from error
    document = _strict_json_bytes(raw, label)
    if canonical_json_bytes(document) != raw:
        raise RunnerError(f"{label} is not canonical JSON")
    return document


def _windows_boot_token() -> str:
    import ctypes
    from ctypes import wintypes

    class SystemTimeOfDayInformation(ctypes.Structure):
        _fields_ = [
            ("BootTime", ctypes.c_longlong),
            ("CurrentTime", ctypes.c_longlong),
            ("TimeZoneBias", ctypes.c_longlong),
            ("CurrentTimeZoneId", wintypes.ULONG),
            ("Reserved", wintypes.ULONG),
            ("BootTimeBias", ctypes.c_ulonglong),
            ("SleepTimeBias", ctypes.c_ulonglong),
        ]

    information = SystemTimeOfDayInformation()
    returned = wintypes.ULONG()
    query = ctypes.WinDLL("ntdll").NtQuerySystemInformation
    query.argtypes = [wintypes.ULONG, ctypes.c_void_p, wintypes.ULONG, ctypes.POINTER(wintypes.ULONG)]
    query.restype = wintypes.LONG
    status = query(
        3,
        ctypes.byref(information),
        ctypes.sizeof(information),
        ctypes.byref(returned),
    )
    if status != 0:
        raise RunnerError(f"Windows boot identity query failed with NTSTATUS {status:#x}")
    return f"windows-filetime-{information.BootTime:016x}"


def _boot_token() -> str:
    if os.name == "nt":
        return _windows_boot_token()
    boot_id = Path("/proc/sys/kernel/random/boot_id")
    if boot_id.is_file():
        try:
            value = boot_id.read_text(encoding="ascii").strip().lower()
        except (OSError, UnicodeError) as error:
            raise RunnerError(f"host boot identity cannot be read: {error}") from error
        if re.fullmatch(r"[0-9a-f-]{36}", value) is None:
            raise RunnerError("host boot identity is malformed")
        return f"linux-boot-id-{value}"
    raise RunnerError("this host does not expose a supported stable boot identity")


def _windows_process_start_token(pid: int) -> str | None:
    import ctypes
    from ctypes import wintypes

    process = ctypes.WinDLL("kernel32", use_last_error=True)
    open_process = process.OpenProcess
    open_process.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    open_process.restype = wintypes.HANDLE
    get_times = process.GetProcessTimes
    get_times.argtypes = [
        wintypes.HANDLE,
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
        ctypes.POINTER(wintypes.FILETIME),
    ]
    get_times.restype = wintypes.BOOL
    close_handle = process.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    handle = open_process(0x1000, False, pid)
    if not handle:
        return None
    try:
        creation = wintypes.FILETIME()
        exit_time = wintypes.FILETIME()
        kernel = wintypes.FILETIME()
        user = wintypes.FILETIME()
        if not get_times(handle, creation, exit_time, kernel, user):
            return None
        value = (creation.dwHighDateTime << 32) | creation.dwLowDateTime
        return f"windows-filetime-{value:016x}"
    finally:
        close_handle(handle)


def _process_start_token(pid: int) -> str | None:
    if type(pid) is not int or pid <= 0:
        return None
    if os.name == "nt":
        return _windows_process_start_token(pid)
    stat_path = Path(f"/proc/{pid}/stat")
    try:
        raw = stat_path.read_text(encoding="ascii")
    except (OSError, UnicodeError):
        return None
    closing = raw.rfind(")")
    if closing < 0:
        return None
    fields = raw[closing + 2 :].split()
    if len(fields) <= 19 or not fields[19].isascii() or not fields[19].isdigit():
        return None
    return f"linux-start-ticks-{fields[19]}"


def current_process_identity(pid: int | None = None) -> dict[str, object]:
    selected_pid = os.getpid() if pid is None else pid
    if type(selected_pid) is not int or selected_pid <= 0:
        raise RunnerError("process PID must be a positive integer")
    start_token = _process_start_token(selected_pid)
    if start_token is None:
        raise RunnerError(f"process {selected_pid} does not expose a stable start identity")
    return {
        "bootId": _boot_token(),
        "hostNode": platform.node(),
        "pid": selected_pid,
        "processStartToken": start_token,
        "schema": _PROCESS_IDENTITY_SCHEMA,
    }


def _process_identity_is_well_formed(identity: object) -> bool:
    if type(identity) is not dict or set(identity) != {
        "bootId",
        "hostNode",
        "pid",
        "processStartToken",
        "schema",
    }:
        return False
    if identity.get("schema") != _PROCESS_IDENTITY_SCHEMA:
        return False
    pid = identity.get("pid")
    if type(pid) is not int or pid <= 0:
        return False
    return all(
        type(identity.get(key)) is str and bool(identity.get(key))
        for key in ("bootId", "hostNode", "processStartToken")
    )


def process_identity_is_alive(identity: object) -> bool:
    if not _process_identity_is_well_formed(identity):
        return False
    assert isinstance(identity, dict)
    pid = identity["pid"]
    assert isinstance(pid, int)
    try:
        if identity.get("bootId") != _boot_token():
            return False
    except RunnerError:
        return False
    if identity.get("hostNode") != platform.node():
        return False
    return identity.get("processStartToken") == _process_start_token(pid)


class AtomicRunLock:
    def __init__(self, root: Path, lock_dir: Path, token: str) -> None:
        self.root = root
        self.lock_dir = lock_dir
        self.token = token
        self._released = False

    @classmethod
    def acquire(cls, root: Path, process_identity: Mapping[str, object]) -> "AtomicRunLock":
        root = validate_canonical_path(root, label="lock root", directory=True)
        if not process_identity_is_alive(dict(process_identity)):
            raise RunnerError("lock owner process identity is not live and exact")
        lock_dir = root / ".global-selection.lock"
        for _ in range(4):
            token = secrets.token_hex(32)
            try:
                lock_dir.mkdir()
            except FileExistsError:
                owner_path = lock_dir / "owner.json"
                if not owner_path.exists():
                    try:
                        age = time.time() - lock_dir.stat().st_mtime
                    except OSError:
                        continue
                    if age < 60:
                        raise RunnerError("another runner is atomically initializing its lock")
                    owner_live = False
                else:
                    try:
                        owner = _read_canonical_document(owner_path, "runner lock owner")
                    except RunnerError as error:
                        raise RunnerError(
                            f"existing runner lock owner is unverifiable: {error}"
                        ) from error
                    if set(owner) != {"acquiredUtc", "processIdentity", "schema", "token"}:
                        raise RunnerError("existing runner lock owner is unverifiable: keys mismatch")
                    if owner.get("schema") != _LOCK_SCHEMA:
                        raise RunnerError("existing runner lock owner is unverifiable: schema mismatch")
                    token_value = owner.get("token")
                    if (
                        type(token_value) is not str
                        or re.fullmatch(r"[0-9a-f]{64}", token_value) is None
                    ):
                        raise RunnerError("existing runner lock owner is unverifiable: token malformed")
                    acquired = owner.get("acquiredUtc")
                    if type(acquired) is not str:
                        raise RunnerError("existing runner lock owner is unverifiable: time missing")
                    try:
                        datetime.fromisoformat(acquired.replace("Z", "+00:00"))
                    except ValueError as error:
                        raise RunnerError(
                            "existing runner lock owner is unverifiable: time malformed"
                        ) from error
                    owner_identity = owner.get("processIdentity")
                    if not _process_identity_is_well_formed(owner_identity):
                        raise RunnerError(
                            "existing runner lock owner is unverifiable: process identity malformed"
                        )
                    owner_live = process_identity_is_alive(owner_identity)
                if owner_live:
                    raise RunnerError("another exact global selector runner owns the lock")
                tombstone = root / f".global-selection.stale.{secrets.token_hex(16)}"
                try:
                    os.replace(lock_dir, tombstone)
                except FileNotFoundError:
                    continue
                except OSError as error:
                    raise RunnerError(f"stale runner lock cannot be quarantined: {error}") from error
                shutil.rmtree(tombstone)
                continue
            except OSError as error:
                raise RunnerError(f"runner lock cannot be acquired: {error}") from error
            owner = {
                "acquiredUtc": _utc_now(),
                "processIdentity": dict(process_identity),
                "schema": _LOCK_SCHEMA,
                "token": token,
            }
            try:
                _atomic_write(lock_dir / "owner.json", canonical_json_bytes(owner))
            except BaseException:
                shutil.rmtree(lock_dir, ignore_errors=True)
                raise
            return cls(root, lock_dir, token)
        raise RunnerError("runner lock race did not converge")

    def release(self) -> None:
        if self._released:
            return
        owner = _read_canonical_document(self.lock_dir / "owner.json", "runner lock owner")
        if owner.get("schema") != _LOCK_SCHEMA or owner.get("token") != self.token:
            raise RunnerError("runner lock ownership changed before release")
        tombstone = self.root / f".global-selection.release.{self.token}"
        try:
            os.replace(self.lock_dir, tombstone)
            shutil.rmtree(tombstone)
            _fsync_directory(self.root)
        except OSError as error:
            raise RunnerError(f"runner lock release failed: {error}") from error
        self._released = True

    def __enter__(self) -> "AtomicRunLock":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.release()


def _directory_inventory(path: Path) -> dict[str, object]:
    if not path.exists():
        return {
            "bytes": 0,
            "entries": [],
            "fileCount": 0,
            "state": "absent",
            "transientMissingEntries": 0,
        }
    if not path.is_dir():
        return {
            "bytes": 0,
            "entries": [],
            "fileCount": 0,
            "state": "not-directory",
            "transientMissingEntries": 0,
        }
    entries: list[dict[str, object]] = []
    total = 0
    transient_missing = 0
    try:
        for child in sorted(path.rglob("*"), key=lambda item: item.relative_to(path).as_posix()):
            try:
                status = os.lstat(child)
            except FileNotFoundError:
                transient_missing += 1
                continue
            attributes = getattr(status, "st_file_attributes", 0)
            if stat.S_ISLNK(status.st_mode) or (attributes & _REPARSE_POINT):
                raise RunnerError(f"inventory path contains reparse entry {child}")
            if stat.S_ISREG(status.st_mode):
                relative = child.relative_to(path).as_posix()
                entries.append({"bytes": status.st_size, "path": relative})
                total += status.st_size
    except FileNotFoundError:
        transient_missing += 1
    except OSError as error:
        raise RunnerError(f"directory inventory failed for {path}: {error}") from error
    return {
        "bytes": total,
        "entries": entries,
        "fileCount": len(entries),
        "state": "present",
        "transientMissingEntries": transient_missing,
    }


class AttemptJournal:
    _NEXT = {
        "preflight": {"producer", "failed"},
        "producer": {"producer_readback", "failed"},
        "producer_readback": {"verifier", "failed"},
        "verifier": {"verifier_readback", "failed"},
        "verifier_readback": {"archive_manifest", "failed"},
        "archive_manifest": {"complete", "failed"},
        "complete": {"failed"},
        "failed": set(),
    }

    def __init__(
        self,
        *,
        attempt_dir: Path,
        run_id: str,
        attempt_id: str,
        process_identity: Mapping[str, object],
        identities: Mapping[str, object],
        paths: Mapping[str, str],
        progress: Mapping[str, ProgressReader],
        free_bytes: int,
        heartbeat_interval_seconds: float,
    ) -> None:
        self.attempt_dir = validate_canonical_path(
            attempt_dir, label="attempt directory", directory=True
        )
        if not re.fullmatch(r"fae8-osm-global-[0-9a-f]{32}", run_id):
            raise RunnerError("run ID is malformed")
        if type(attempt_id) is not str or not attempt_id.startswith("attempt-"):
            raise RunnerError("attempt ID is malformed")
        if not process_identity_is_alive(dict(process_identity)):
            raise RunnerError("journal process identity is not live and exact")
        if (
            not isinstance(heartbeat_interval_seconds, (int, float))
            or isinstance(heartbeat_interval_seconds, bool)
            or heartbeat_interval_seconds <= 0
            or heartbeat_interval_seconds > 30
        ):
            raise RunnerError("journal heartbeat interval must be positive and at most 30 seconds")
        _nonnegative_integer(free_bytes, "journal free byte count")
        self.run_id = run_id
        self.attempt_id = attempt_id
        self.process_identity = dict(process_identity)
        self.identities = dict(identities)
        self.paths = dict(paths)
        self.progress = dict(progress)
        self.free_bytes = free_bytes
        self.heartbeat_interval_seconds = float(heartbeat_interval_seconds)
        self.status_path = attempt_dir / "status.json"
        self.events_path = attempt_dir / "events.jsonl"
        if self.status_path.exists() or self.events_path.exists():
            raise RunnerError("attempt journal cannot reuse existing status or events")
        self.events_path.open("xb").close()
        self._state_lock = threading.RLock()
        self._stop = threading.Event()
        self._thread_error: BaseException | None = None
        self._thread: threading.Thread | None = None
        self._started_utc = _utc_now()
        self._started_ns = time.monotonic_ns()
        self._phase = "preflight"
        self._transition_sequence = 1
        transition_utc = _utc_now()
        self._last_transition = {
            "phase": "preflight",
            "sequence": 1,
            "utc": transition_utc,
        }
        self._heartbeat_sequence = 0
        self._append_event("preflight", transition_utc, None)
        self._write_status()

    @property
    def thread(self) -> threading.Thread:
        if self._thread is None:
            raise RunnerError("journal heartbeat has not been started")
        return self._thread

    @property
    def phase(self) -> str:
        with self._state_lock:
            return self._phase

    def _append_event(self, phase: str, utc: str, error: Mapping[str, object] | None) -> None:
        event: dict[str, object] = {
            "phase": phase,
            "runId": self.run_id,
            "schema": _EVENT_SCHEMA,
            "sequence": self._transition_sequence,
            "utc": utc,
        }
        if error is not None:
            event["error"] = dict(error)
        raw = canonical_json_bytes(event)
        with self.events_path.open("ab") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())

    def _status_document(self) -> dict[str, object]:
        progress = {
            role: {
                "bytesConsumed": reader.bytes_consumed,
                "totalBytes": reader.total_bytes,
            }
            for role, reader in sorted(self.progress.items())
        }
        inventories = {
            role: _directory_inventory(Path(path))
            for role, path in sorted(self.paths.items())
        }
        return {
            "attemptId": self.attempt_id,
            "elapsedNanoseconds": max(0, time.monotonic_ns() - self._started_ns),
            "freeBytesAtPreflight": self.free_bytes,
            "heartbeatSequence": self._heartbeat_sequence,
            "heartbeatUtc": _utc_now(),
            "identities": self.identities,
            "inventories": inventories,
            "lastDurableTransition": self._last_transition,
            "phase": self._phase,
            "processIdentity": self.process_identity,
            "progress": progress,
            "runId": self.run_id,
            "schema": _STATUS_SCHEMA,
            "startedUtc": self._started_utc,
        }

    def _write_status(self) -> None:
        with self._state_lock:
            self._heartbeat_sequence += 1
            _atomic_write(self.status_path, canonical_json_bytes(self._status_document()))

    def _heartbeat(self) -> None:
        try:
            while not self._stop.wait(self.heartbeat_interval_seconds):
                self._write_status()
        except BaseException as error:
            self._thread_error = error
            self._stop.set()

    def start(self) -> None:
        with self._state_lock:
            if self._thread is not None:
                raise RunnerError("journal heartbeat is already started")
            self._thread = threading.Thread(
                target=self._heartbeat,
                name=f"flightalert-exp8-heartbeat-{self.attempt_id}",
                daemon=False,
            )
            self._thread.start()

    def _raise_thread_error(self) -> None:
        if self._thread_error is not None:
            raise RunnerError(f"journal heartbeat failed: {self._thread_error}") from self._thread_error

    def transition(
        self,
        phase: str,
        *,
        error: Mapping[str, object] | None = None,
    ) -> None:
        with self._state_lock:
            self._raise_thread_error()
            if phase not in self._NEXT.get(self._phase, set()):
                raise RunnerError(f"illegal phase transition {self._phase!r} -> {phase!r}")
            self._transition_sequence += 1
            transition_utc = _utc_now()
            self._append_event(phase, transition_utc, error)
            self._phase = phase
            self._last_transition = {
                "phase": phase,
                "sequence": self._transition_sequence,
                "utc": transition_utc,
            }
            self._write_status()

    def stop(self) -> None:
        thread = self._thread
        if thread is None:
            return
        self._stop.set()
        thread.join(self.heartbeat_interval_seconds + 5)
        if thread.is_alive():
            raise RunnerError("journal heartbeat did not stop cleanly")
        self._raise_thread_error()


def _file_fact(path: Path, label: str) -> dict[str, object]:
    validate_canonical_path(path, label=label, directory=False)
    digest = hashlib.sha256()
    total = 0
    try:
        with path.open("rb", buffering=0) as handle:
            initial = os.fstat(handle.fileno())
            while True:
                raw = handle.read(4 * 1024 * 1024)
                if not raw:
                    break
                digest.update(raw)
                total += len(raw)
            final_handle = os.fstat(handle.fileno())
        final_path = os.stat(path, follow_symlinks=False)
    except OSError as error:
        raise RunnerError(f"{label} cannot be hashed: {error}") from error
    if (
        _status_signature(initial) != _status_signature(final_handle)
        or _status_signature(initial) != _status_signature(final_path)
    ):
        raise RunnerError(f"{label} changed while it was hashed")
    return {"bytes": total, "sha256": digest.hexdigest()}


def _fact_matches(path: Path, fact: object, label: str) -> None:
    document = _exact_keys(fact, {"bytes", "sha256"}, label)
    expected_bytes = _nonnegative_integer(document["bytes"], f"{label} bytes")
    expected_sha256 = _sha256_identity(document["sha256"], f"{label} SHA-256")
    actual = _file_fact(path, label)
    if actual != {"bytes": expected_bytes, "sha256": expected_sha256}:
        raise RunnerError(f"{label} bytes or SHA-256 drifted")


def _selection_artifact_names() -> tuple[str, ...]:
    return (
        "root-ids.txt",
        "selected-tuples.bin",
        "selection-summary.json",
        *(f"roots-{bucket:03d}.bin" for bucket in range(256)),
    )


def _readback_producer(
    result: object,
    stage: Path,
    limits: selection_module.SelectionLimits,
) -> dict[str, object]:
    if not isinstance(result, selection_module.SelectionResult):
        raise RunnerError("producer did not return the public SelectionResult contract")
    if not isinstance(limits, selection_module.SelectionLimits):
        raise RunnerError("producer limits are missing or invalid")
    expected_names = _selection_artifact_names()
    try:
        actual_names = tuple(sorted(child.name for child in stage.iterdir()))
    except OSError as error:
        raise RunnerError(f"producer stage cannot be inventoried: {error}") from error
    if actual_names != tuple(sorted(expected_names)):
        raise RunnerError("producer stage has a missing or unexpected artifact")
    inventory: list[dict[str, object]] = []
    facts: dict[str, dict[str, object]] = {}
    for name in expected_names:
        fact = _file_fact(stage / name, f"producer artifact {name}")
        facts[name] = fact
        inventory.append({"path": name, **fact})
    summary_path = stage / "selection-summary.json"
    summary_raw = summary_path.read_bytes()
    summary = _strict_json_bytes(summary_raw, "producer selection summary")
    if canonical_json_bytes(summary) != summary_raw:
        raise RunnerError("producer selection summary is not canonical JSON")
    summary_sha256 = hashlib.sha256(summary_raw).hexdigest()
    if (
        result.stage_dir != stage
        or result.summary_path != summary_path
        or result.selected_tuple_path != stage / "selected-tuples.bin"
        or result.root_ids_path != stage / "root-ids.txt"
        or result.summary_bytes != len(summary_raw)
        or result.summary_sha256 != summary_sha256
    ):
        raise RunnerError("producer returned paths or summary identity contradict readback")
    selected = _exact_keys(
        summary.get("selected"),
        {"nonNfcFieldCount", "nonNfcRecordCount", "relations", "total", "ways"},
        "producer selected summary",
    )
    ways = _nonnegative_integer(selected["ways"], "producer selected ways")
    relations = _nonnegative_integer(selected["relations"], "producer selected relations")
    total = _nonnegative_integer(selected["total"], "producer selected total")
    if total != ways + relations:
        raise RunnerError("producer selected total contradicts kind counts")
    if result.selected_way_count != ways or result.selected_relation_count != relations:
        raise RunnerError("producer SelectionResult selected counts contradict summary")
    identities = _exact_keys(
        summary.get("identities"),
        {
            "candidatePbfBytes",
            "candidatePbfSha256",
            "codeSha256",
            "convertedStreamBytes",
            "convertedStreamFormat",
            "convertedStreamSha256",
            "planetSourceSha256",
            "policySha256",
            "runtimeSha256",
        },
        "producer identities",
    )
    if (
        identities["planetSourceSha256"] != result.planet_source_sha256
        or identities["candidatePbfBytes"] != result.candidate_pbf_bytes
        or identities["candidatePbfSha256"] != result.candidate_pbf_sha256
        or identities["convertedStreamBytes"] != result.converted_stream_bytes
        or identities["convertedStreamSha256"] != result.converted_stream_sha256
        or identities["convertedStreamFormat"] != result.converted_stream_format
    ):
        raise RunnerError("producer SelectionResult identities contradict summary")
    artifacts = _exact_keys(
        summary.get("artifacts"), {"buckets", "rootIds", "selectedTuples"}, "producer artifacts"
    )
    root_ids = _exact_keys(
        artifacts["rootIds"], {"bytes", "filename", "records", "sha256"}, "root IDs evidence"
    )
    tuples = _exact_keys(
        artifacts["selectedTuples"],
        {"bytes", "filename", "recordBytes", "records", "sha256"},
        "selected tuple evidence",
    )
    for evidence, required_name in ((root_ids, "root-ids.txt"), (tuples, "selected-tuples.bin")):
        if evidence["filename"] != required_name:
            raise RunnerError(f"producer summary does not bind exact {required_name}")
        if facts[required_name] != {
            "bytes": evidence["bytes"],
            "sha256": evidence["sha256"],
        }:
            raise RunnerError(f"producer summary contradicts {required_name} readback")
    buckets = _exact_keys(
        artifacts["buckets"], {"bucketCount", "entries", "records"}, "producer buckets evidence"
    )
    entries = buckets["entries"]
    if type(entries) is not list or len(entries) != 256 or buckets["bucketCount"] != 256:
        raise RunnerError("producer summary bucket inventory is not exact")
    for bucket, entry_value in enumerate(entries):
        entry = _exact_keys(
            entry_value, {"bucket", "bytes", "filename", "records", "sha256"}, f"bucket {bucket}"
        )
        name = f"roots-{bucket:03d}.bin"
        if entry["bucket"] != bucket or entry["filename"] != name:
            raise RunnerError("producer bucket identity is not canonical")
        if facts[name] != {"bytes": entry["bytes"], "sha256": entry["sha256"]}:
            raise RunnerError(f"producer summary contradicts bucket {bucket:03d}")
    rejections = _exact_keys(
        summary.get("rejections"), {"countsByReasonId", "ledgerSha256", "records"}, "producer rejections"
    )
    if rejections["ledgerSha256"] != result.rejection_ledger_sha256:
        raise RunnerError("producer rejection ledger contradicts SelectionResult")
    result_document: dict[str, object] = {
        "artifacts": inventory,
        "convertedStream": {
            "bytes": result.converted_stream_bytes,
            "format": result.converted_stream_format,
            "sha256": result.converted_stream_sha256,
        },
        "limits": {"maxTotalReferences": limits.max_total_references},
        "rejectionLedgerSha256": result.rejection_ledger_sha256,
        "schema": "flight-alert-exp8-osm-global-producer-readback-v2",
        "selected": {"relations": relations, "total": total, "ways": ways},
        "summary": {"bytes": len(summary_raw), "sha256": summary_sha256},
    }
    result_document["resultSha256"] = hashlib.sha256(
        canonical_json_bytes(result_document)
    ).hexdigest()
    return result_document


def _write_verifier_documents(result: object, attempt_dir: Path) -> tuple[Path, Path]:
    if not isinstance(result, verifier_module.VerificationResult):
        raise RunnerError("verifier did not return the public VerificationResult contract")
    report_path = attempt_dir / "verification-report.json"
    observation_path = attempt_dir / "verification-observation.json"
    if report_path.exists() or observation_path.exists():
        raise RunnerError("verifier documentary paths already exist")
    _atomic_write(report_path, result.report_bytes)
    _atomic_write(observation_path, result.observation_bytes)
    return report_path, observation_path


def _readback_verifier(
    result: object,
    report_path: Path,
    observation_path: Path,
    producer: Mapping[str, object],
    limits: verifier_module.VerificationLimits,
) -> dict[str, object]:
    if not isinstance(result, verifier_module.VerificationResult):
        raise RunnerError("verifier did not return the public VerificationResult contract")
    if not isinstance(limits, verifier_module.VerificationLimits):
        raise RunnerError("verifier limits are missing or invalid")
    report_raw = report_path.read_bytes()
    observation_raw = observation_path.read_bytes()
    report = _strict_json_bytes(report_raw, "verification report")
    observation = _strict_json_bytes(observation_raw, "verification observation")
    if canonical_json_bytes(report) != report_raw or canonical_json_bytes(observation) != observation_raw:
        raise RunnerError("verifier documentary result is not canonical JSON")
    report_sha256 = hashlib.sha256(report_raw).hexdigest()
    observation_sha256 = hashlib.sha256(observation_raw).hexdigest()
    if (
        report_raw != result.report_bytes
        or observation_raw != result.observation_bytes
        or report_sha256 != result.report_sha256
        or observation_sha256 != result.observation_sha256
    ):
        raise RunnerError("verifier documentary readback contradicts VerificationResult")
    selected = _exact_keys(
        report.get("selected"),
        {"nonNfcFieldCount", "nonNfcRecordCount", "relations", "total", "ways"},
        "verifier selected report",
    )
    producer_selected = producer.get("selected")
    if type(producer_selected) is not dict:
        raise RunnerError("producer readback selected evidence is missing")
    if (
        selected["ways"] != producer_selected.get("ways")
        or selected["relations"] != producer_selected.get("relations")
        or selected["total"] != producer_selected.get("total")
        or result.selected_way_count != selected["ways"]
        or result.selected_relation_count != selected["relations"]
    ):
        raise RunnerError("verifier selected counts contradict producer or result")
    converted = _exact_keys(
        observation.get("convertedStream"), {"bytes", "format", "sha256"}, "verifier stream observation"
    )
    if (
        converted["bytes"] != result.converted_stream_bytes
        or converted["format"] != result.converted_stream_format
        or converted["sha256"] != result.converted_stream_sha256
    ):
        raise RunnerError("verifier stream observation contradicts VerificationResult")
    result_document: dict[str, object] = {
        "broadStream": dict(converted),
        "limits": {"maxTotalReferences": limits.max_total_references},
        "observation": {"bytes": len(observation_raw), "sha256": observation_sha256},
        "report": {"bytes": len(report_raw), "sha256": report_sha256},
        "schema": "flight-alert-exp8-osm-global-verifier-readback-v2",
        "selected": {
            "relations": result.selected_relation_count,
            "total": result.selected_way_count + result.selected_relation_count,
            "ways": result.selected_way_count,
        },
    }
    result_document["resultSha256"] = hashlib.sha256(
        canonical_json_bytes(result_document)
    ).hexdigest()
    return result_document


def _validate_producer_inventory(stage: Path, producer: Mapping[str, object]) -> None:
    entries = producer.get("artifacts")
    if type(entries) is not list:
        raise RunnerError("producer readback artifact inventory is missing")
    expected_names: list[str] = []
    for index, value in enumerate(entries):
        entry = _exact_keys(value, {"bytes", "path", "sha256"}, f"producer inventory {index}")
        name = entry["path"]
        if type(name) is not str or Path(name).name != name:
            raise RunnerError("producer inventory path is not a plain filename")
        expected_names.append(name)
        _fact_matches(
            stage / name,
            {"bytes": entry["bytes"], "sha256": entry["sha256"]},
            f"producer accepted artifact {name}",
        )
    actual_names = sorted(child.name for child in stage.iterdir())
    if actual_names != sorted(expected_names):
        raise RunnerError("producer accepted inventory drifted")


def _copy_exact(source: Path, destination: Path, expected: Mapping[str, object]) -> None:
    with source.open("rb", buffering=0) as reader, destination.open("xb", buffering=0) as writer:
        while True:
            raw = reader.read(1024 * 1024)
            if not raw:
                break
            writer.write(raw)
        writer.flush()
        os.fsync(writer.fileno())
    _fact_matches(destination, expected, f"mirrored document {destination.name}")


def _archive_documents(
    *,
    mirror_root: Path,
    run_id: str,
    attempt_id: str,
    sources: Mapping[str, Path],
) -> tuple[Path, dict[str, object]]:
    final = mirror_root / run_id
    if final.exists():
        raise RunnerError("documentary mirror run directory already exists")
    temporary = mirror_root / f".{run_id}.{attempt_id}.{secrets.token_hex(16)}.tmp"
    temporary.mkdir()
    facts: dict[str, dict[str, object]] = {}
    try:
        for name, source in sorted(sources.items()):
            if Path(name).name != name:
                raise RunnerError("documentary mirror filename is not plain")
            fact = _file_fact(source, f"documentary source {name}")
            _copy_exact(source, temporary / name, fact)
            facts[name] = fact
        mirror_document = {
            "attemptId": attempt_id,
            "documents": [
                {"filename": name, **fact} for name, fact in sorted(facts.items())
            ],
            "runId": run_id,
            "schema": "flight-alert-exp8-osm-global-documentary-mirror-v1",
        }
        mirror_raw = canonical_json_bytes(mirror_document)
        _atomic_write(temporary / "mirror-manifest.json", mirror_raw)
        os.replace(temporary, final)
        _fsync_directory(mirror_root)
        mirror_fact = _file_fact(final / "mirror-manifest.json", "documentary mirror manifest")
        return final, mirror_fact
    except BaseException:
        shutil.rmtree(temporary, ignore_errors=True)
        raise


def _new_attempt(context: PreflightContext) -> tuple[Path, Path, str]:
    run_dir = context.config.runs_root / context.content_run_id
    if run_dir.exists():
        validate_canonical_path(run_dir, label="content run directory", directory=True)
        if (run_dir / "complete.json").exists():
            inspection = inspect_run(run_dir)
            if inspection.get("state") == "complete":
                raise RunnerError("content run is already complete")
            raise RunnerError("content run has an invalid complete marker and cannot be reused")
    else:
        run_dir.mkdir()
    attempts = run_dir / "attempts"
    attempts.mkdir(exist_ok=True)
    validate_canonical_path(attempts, label="attempts directory", directory=True)
    for _ in range(8):
        time_component = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        attempt_id = f"attempt-{time_component}-{os.getpid()}-{secrets.token_hex(8)}"
        attempt_dir = attempts / attempt_id
        try:
            attempt_dir.mkdir()
        except FileExistsError:
            continue
        (attempt_dir / "production").mkdir()
        (attempt_dir / "verifier-work").mkdir()
        return run_dir, attempt_dir, attempt_id
    raise RunnerError("unique immutable attempt allocation failed")


@dataclass(frozen=True, slots=True)
class RunOutcome:
    run_id: str
    attempt_id: str
    run_dir: Path
    attempt_dir: Path
    mirror_dir: Path
    final_manifest_path: Path
    complete_path: Path


def _error_document(error: BaseException, phase: str) -> dict[str, object]:
    return {
        "class": f"{type(error).__module__}.{type(error).__qualname__}",
        "message": str(error),
        "phase": phase,
    }


def _execute_run(context: PreflightContext) -> RunOutcome:
    run_dir, attempt_dir, attempt_id = _new_attempt(context)
    production_stage = attempt_dir / "production"
    verifier_work = attempt_dir / "verifier-work"
    production_handle = context.pins["productionOpl"].rewind()
    broad_handle = context.pins["broadOpl"].rewind()
    production_progress = ProgressReader(
        production_handle, total_bytes=context.config.production_opl.bytes * 2
    )
    broad_progress = ProgressReader(broad_handle, total_bytes=context.config.broad_opl.bytes)
    journal = AttemptJournal(
        attempt_dir=attempt_dir,
        run_id=context.content_run_id,
        attempt_id=attempt_id,
        process_identity=current_process_identity(),
        identities=context.identities,
        paths={
            "productionStage": str(production_stage),
            "verifierScratch": str(verifier_work),
        },
        progress={"broadOpl": broad_progress, "productionOpl": production_progress},
        free_bytes=context.free_bytes,
        heartbeat_interval_seconds=context.config.heartbeat_seconds,
    )
    journal.start()
    mirror_dir: Path | None = None
    complete_path = run_dir / "complete.json"
    try:
        reference_limits = context.config.validated_reference_limits()
        selection_limits = replace(
            selection_module.SelectionLimits(),
            max_total_references=reference_limits.selection_max_total_references,
        )
        verification_limits = replace(
            verifier_module.VerificationLimits(),
            max_total_references=reference_limits.verification_max_total_references,
        )
        journal.transition("producer")
        producer_bindings = selection_module.SelectionBindings(
            planet_source_sha256=context.config.planet_pbf.sha256,
            candidate_pbf_bytes=context.config.production_pbf.bytes,
            candidate_pbf_sha256=context.config.production_pbf.sha256,
            converted_stream_bytes=context.config.production_opl.bytes,
            converted_stream_sha256=context.config.production_opl.sha256,
            converted_stream_format="opl",
            runtime_sha256=context.runtime_sha256,
            policy_sha256=context.config.policy_sha256,
            code_sha256=context.config.selector_sha256,
        )
        producer_result = selection_module.scan_planet_roots(
            production_progress,
            production_stage,
            producer_bindings,
            profile=context.config.selection_profile,
            limits=selection_limits,
            workers=context.config.workers,
        )

        journal.transition("producer_readback")
        producer = _readback_producer(producer_result, production_stage, selection_limits)

        journal.transition("verifier")
        production_progress.seek(0)
        broad_progress.seek(0)
        verifier_bindings = verifier_module.VerificationBindings(
            planet_source_sha256=context.config.planet_pbf.sha256,
            broad_pbf_bytes=context.config.broad_pbf.bytes,
            broad_pbf_sha256=context.config.broad_pbf.sha256,
            converted_stream_bytes=context.config.broad_opl.bytes,
            converted_stream_sha256=context.config.broad_opl.sha256,
            converted_stream_format="opl",
            runtime_sha256=context.runtime_sha256,
            policy_sha256=context.config.policy_sha256,
            code_sha256=context.config.verifier_sha256,
        )
        verifier_result = verifier_module.verify_planet_roots(
            broad_progress,
            production_stage,
            verifier_work,
            verifier_bindings,
            production_stream=production_progress,
            production_input_format="opl",
            input_format="opl",
            profile=context.config.verification_profile,
            limits=verification_limits,
            workers=context.config.workers,
        )
        report_path, observation_path = _write_verifier_documents(
            verifier_result, attempt_dir
        )

        journal.transition("verifier_readback")
        verifier = _readback_verifier(
            verifier_result,
            report_path,
            observation_path,
            producer,
            verification_limits,
        )

        journal.transition("archive_manifest")
        semantic = {
            "identities": context.identities,
            "producer": producer,
            "profiles": {
                "selection": context.config.selection_profile,
                "verification": context.config.verification_profile,
            },
            "runtime": context.runtime,
            "schema": "flight-alert-exp8-osm-global-semantic-manifest-v2",
            "verifier": verifier,
        }
        semantic_sha256 = hashlib.sha256(canonical_json_bytes(semantic)).hexdigest()
        final_manifest = {
            "execution": {
                "attemptId": attempt_id,
                "createdUtc": _utc_now(),
                "excludedRuntimeFields": [
                    "attemptId",
                    "createdUtc",
                    "freeBytesAtPreflight",
                    "heartbeatUtc",
                    "processIdentity",
                    "runId",
                ],
                "freeBytesAtPreflight": context.free_bytes,
                "runId": context.content_run_id,
                "spaceWatermark": context.space_watermark,
            },
            "schema": "flight-alert-exp8-osm-global-final-manifest-v1",
            "semantic": semantic,
            "semanticSha256": semantic_sha256,
        }
        final_manifest_path = attempt_dir / "final-manifest.json"
        _atomic_write(final_manifest_path, canonical_json_bytes(final_manifest))

        _validate_producer_inventory(production_stage, producer)
        _fact_matches(
            report_path,
            verifier["report"],
            "accepted verification report",
        )
        _fact_matches(
            observation_path,
            verifier["observation"],
            "accepted verification observation",
        )
        journal.stop()
        journal.transition("complete")

        mirror_dir, mirror_manifest_fact = _archive_documents(
            mirror_root=context.config.mirror_root,
            run_id=context.content_run_id,
            attempt_id=attempt_id,
            sources={
                "events.jsonl": journal.events_path,
                "final-manifest.json": final_manifest_path,
                "status.json": journal.status_path,
                "verification-observation.json": observation_path,
                "verification-report.json": report_path,
            },
        )
        final_manifest_fact = _file_fact(final_manifest_path, "accepted final manifest")
        status_fact = _file_fact(journal.status_path, "accepted final status")
        events_fact = _file_fact(journal.events_path, "accepted final events")
        marker = {
            "attemptId": attempt_id,
            "events": events_fact,
            "finalManifest": final_manifest_fact,
            "mirrorDirectory": str(mirror_dir),
            "mirrorManifest": mirror_manifest_fact,
            "producerResultSha256": producer["resultSha256"],
            "runId": context.content_run_id,
            "schema": "flight-alert-exp8-osm-global-complete-v1",
            "status": status_fact,
            "verifierObservation": verifier["observation"],
            "verifierReport": verifier["report"],
            "verifierResultSha256": verifier["resultSha256"],
        }
        _atomic_write(complete_path, canonical_json_bytes(marker))
        return RunOutcome(
            run_id=context.content_run_id,
            attempt_id=attempt_id,
            run_dir=run_dir,
            attempt_dir=attempt_dir,
            mirror_dir=mirror_dir,
            final_manifest_path=final_manifest_path,
            complete_path=complete_path,
        )
    except BaseException as error:
        phase = journal.phase
        failure = _error_document(error, phase)
        try:
            if journal.phase != "failed":
                journal.transition("failed", error=failure)
        except BaseException as journal_error:
            failure["journalFailure"] = str(journal_error)
        try:
            journal.stop()
        except BaseException as stop_error:
            failure["heartbeatStopFailure"] = str(stop_error)
        if mirror_dir is not None and not complete_path.exists():
            shutil.rmtree(mirror_dir, ignore_errors=True)
        if isinstance(error, RunnerError):
            raise
        raise RunnerError(
            f"global selector run failed during {phase}: "
            f"{type(error).__module__}.{type(error).__qualname__}: {error}"
        ) from error


def run_global_selection(config: RunConfig) -> RunOutcome:
    if not isinstance(config, RunConfig):
        raise RunnerError("runner config is missing or invalid")
    validate_canonical_path(config.runs_root, label="runs root", directory=True)
    lease: AtomicRunLock | None = None
    context: PreflightContext | None = None
    try:
        lease = AtomicRunLock.acquire(config.runs_root, current_process_identity())
        context = preflight(config)
        return _execute_run(context)
    finally:
        release_error: BaseException | None = None
        if lease is not None:
            try:
                lease.release()
            except BaseException as error:
                release_error = error
        if context is not None:
            try:
                context.close()
            except BaseException as error:
                if release_error is None:
                    release_error = error
        if release_error is not None:
            raise RunnerError(f"runner resource release failed: {release_error}") from release_error


def _validate_complete_run(run_dir: Path, marker: Mapping[str, object]) -> dict[str, object]:
    required = {
        "attemptId",
        "events",
        "finalManifest",
        "mirrorDirectory",
        "mirrorManifest",
        "producerResultSha256",
        "runId",
        "schema",
        "status",
        "verifierObservation",
        "verifierReport",
        "verifierResultSha256",
    }
    if set(marker) != required or marker.get("schema") != "flight-alert-exp8-osm-global-complete-v1":
        raise RunnerError("complete marker schema or keys are invalid")
    run_id = marker.get("runId")
    attempt_id = marker.get("attemptId")
    if run_id != run_dir.name or type(attempt_id) is not str:
        raise RunnerError("complete marker run or attempt identity is invalid")
    attempt_dir = run_dir / "attempts" / attempt_id
    validate_canonical_path(attempt_dir, label="accepted attempt", directory=True)
    _fact_matches(attempt_dir / "final-manifest.json", marker["finalManifest"], "final manifest")
    _fact_matches(attempt_dir / "status.json", marker["status"], "final status")
    _fact_matches(attempt_dir / "events.jsonl", marker["events"], "final events")
    _fact_matches(
        attempt_dir / "verification-report.json", marker["verifierReport"], "verification report"
    )
    _fact_matches(
        attempt_dir / "verification-observation.json",
        marker["verifierObservation"],
        "verification observation",
    )
    final_manifest = _read_canonical_document(
        attempt_dir / "final-manifest.json", "final manifest", max_bytes=64 * 1024 * 1024
    )
    semantic = final_manifest.get("semantic")
    if type(semantic) is not dict:
        raise RunnerError("final manifest semantic section is missing")
    semantic_sha = hashlib.sha256(canonical_json_bytes(semantic)).hexdigest()
    if semantic_sha != final_manifest.get("semanticSha256"):
        raise RunnerError("final manifest semantic SHA-256 drifted")
    producer = semantic.get("producer")
    verifier = semantic.get("verifier")
    if type(producer) is not dict or type(verifier) is not dict:
        raise RunnerError("final manifest readback sections are missing")
    if producer.get("resultSha256") != marker.get("producerResultSha256"):
        raise RunnerError("producer result hash contradicts complete marker")
    if verifier.get("resultSha256") != marker.get("verifierResultSha256"):
        raise RunnerError("verifier result hash contradicts complete marker")
    _validate_producer_inventory(attempt_dir / "production", producer)
    mirror_raw = marker.get("mirrorDirectory")
    if type(mirror_raw) is not str:
        raise RunnerError("complete marker mirror path is invalid")
    mirror_dir = validate_canonical_path(
        Path(mirror_raw), label="documentary mirror", directory=True
    )
    _fact_matches(
        mirror_dir / "mirror-manifest.json", marker["mirrorManifest"], "mirror manifest"
    )
    mirror_manifest = _read_canonical_document(
        mirror_dir / "mirror-manifest.json", "mirror manifest"
    )
    documents = mirror_manifest.get("documents")
    if type(documents) is not list:
        raise RunnerError("mirror document inventory is missing")
    expected_names: set[str] = {"mirror-manifest.json"}
    for index, value in enumerate(documents):
        entry = _exact_keys(value, {"bytes", "filename", "sha256"}, f"mirror entry {index}")
        name = entry["filename"]
        if type(name) is not str or Path(name).name != name:
            raise RunnerError("mirror entry filename is invalid")
        expected_names.add(name)
        _fact_matches(
            mirror_dir / name,
            {"bytes": entry["bytes"], "sha256": entry["sha256"]},
            f"mirrored {name}",
        )
    actual_names = {child.name for child in mirror_dir.iterdir()}
    if actual_names != expected_names:
        raise RunnerError("documentary mirror contains unexpected files")
    return {
        "attemptId": attempt_id,
        "runId": run_id,
        "state": "complete",
    }


def inspect_run(run_dir: str | Path, *, stale_after_seconds: float = 45.0) -> dict[str, object]:
    try:
        selected = validate_canonical_path(Path(run_dir), label="run inspection", directory=True)
        if (
            not isinstance(stale_after_seconds, (int, float))
            or isinstance(stale_after_seconds, bool)
            or stale_after_seconds <= 0
        ):
            raise RunnerError("stale heartbeat threshold must be positive")
        marker_path = selected / "complete.json"
        if marker_path.exists():
            marker = _read_canonical_document(marker_path, "complete marker")
            return _validate_complete_run(selected, marker)
        attempts = selected / "attempts"
        if not attempts.is_dir():
            raise RunnerError("run has no immutable attempts directory")
        candidates = [child for child in attempts.iterdir() if child.is_dir()]
        if not candidates:
            raise RunnerError("run has no attempt status")
        owner_identity: object = None
        owner_path = selected.parent / ".global-selection.lock" / "owner.json"
        if owner_path.is_file():
            try:
                owner = _read_canonical_document(owner_path, "runner lock owner")
                if owner.get("schema") == _LOCK_SCHEMA:
                    owner_identity = owner.get("processIdentity")
            except RunnerError:
                owner_identity = None
        records: list[tuple[Path, dict[str, object], float, int]] = []
        record_errors: list[str] = []
        now = datetime.now(timezone.utc)
        for attempt in candidates:
            status_path = attempt / "status.json"
            try:
                status = _read_canonical_document(
                    status_path, f"attempt status {attempt.name}"
                )
            except RunnerError as error:
                record_errors.append(str(error))
                continue
            if status.get("attemptId") != attempt.name or status.get("runId") != selected.name:
                raise RunnerError("attempt status path identity is inconsistent")
            heartbeat_raw = status.get("heartbeatUtc")
            if type(heartbeat_raw) is not str:
                raise RunnerError("attempt heartbeat time is missing")
            try:
                heartbeat = datetime.fromisoformat(heartbeat_raw.replace("Z", "+00:00"))
            except ValueError as error:
                raise RunnerError("attempt heartbeat time is malformed") from error
            age = (now - heartbeat).total_seconds()
            records.append((attempt, status, age, status_path.stat().st_mtime_ns))
        active = [
            record
            for record in records
            if record[1].get("phase") not in {"failed", "complete"}
            and record[2] <= stale_after_seconds
            and record[1].get("processIdentity") == owner_identity
            and process_identity_is_alive(owner_identity)
        ]
        if len(active) > 1:
            raise RunnerError("multiple attempts claim the exact live runner lock identity")
        if active:
            attempt, status, age, _ = active[0]
            return {
                "attemptId": status.get("attemptId"),
                "heartbeatAgeSeconds": max(0.0, age),
                "runId": status.get("runId"),
                "state": "running",
            }
        if record_errors:
            raise RunnerError(
                "no exact live attempt supersedes invalid preserved attempt evidence: "
                + " | ".join(record_errors)
            )
        if not records:
            raise RunnerError("run has no readable attempt status")
        attempt, status, age, _ = max(records, key=lambda value: (value[3], value[0].name))
        phase = status.get("phase")
        if phase == "failed":
            return {
                "attemptId": status.get("attemptId"),
                "runId": status.get("runId"),
                "state": "failed",
            }
        if phase == "complete":
            raise RunnerError("attempt claims complete without the last-written complete marker")
        return {
            "attemptId": status.get("attemptId"),
            "heartbeatAgeSeconds": max(0.0, age),
            "runId": status.get("runId"),
            "state": "stale",
        }
    except RunnerError as error:
        return {"reason": str(error), "state": "failed"}


def _normalized_path(value: Path) -> str:
    return os.path.normcase(os.path.normpath(str(value)))


def _existing_components(path: Path) -> list[Path]:
    result: list[Path] = []
    current = path
    while True:
        result.append(current)
        if current.parent == current:
            break
        current = current.parent
    result.reverse()
    return result


def validate_canonical_path(
    path: Path,
    *,
    label: str,
    directory: bool | None = None,
) -> Path:
    if not isinstance(path, Path):
        raise RunnerError(f"{label} path must be a pathlib.Path")
    if not path.is_absolute():
        raise RunnerError(f"{label} path must be absolute and canonical")
    if any(component in {".", ".."} for component in path.parts):
        raise RunnerError(f"{label} path is not canonical")
    try:
        resolved = path.resolve(strict=True)
    except (OSError, RuntimeError) as error:
        raise RunnerError(f"{label} path cannot be resolved exactly: {error}") from error
    if _normalized_path(path) != _normalized_path(resolved):
        raise RunnerError(f"{label} path is not canonical")
    for component in _existing_components(path):
        try:
            status = os.lstat(component)
        except OSError as error:
            raise RunnerError(f"{label} path component cannot be inspected: {error}") from error
        attributes = getattr(status, "st_file_attributes", 0)
        if stat.S_ISLNK(status.st_mode) or (attributes & _REPARSE_POINT):
            raise RunnerError(f"{label} path contains a symbolic link or reparse point")
    if directory is True and not path.is_dir():
        raise RunnerError(f"{label} path must identify a directory")
    if directory is False and not path.is_file():
        raise RunnerError(f"{label} path must identify a regular file")
    return path


@dataclass(frozen=True, slots=True)
class FileLock:
    path: Path
    bytes: int
    sha256: str

    def __post_init__(self) -> None:
        if not isinstance(self.path, Path):
            raise RunnerError("locked file path must be a pathlib.Path")
        _nonnegative_integer(self.bytes, "locked file byte count")
        _sha256_identity(self.sha256, "locked file SHA-256")

    def to_document(self) -> dict[str, object]:
        return {
            "bytes": self.bytes,
            "path": str(self.path),
            "sha256": self.sha256,
        }

    @classmethod
    def from_document(cls, value: object, label: str) -> "FileLock":
        document = _exact_keys(value, {"bytes", "path", "sha256"}, label)
        raw_path = document["path"]
        if type(raw_path) is not str or not raw_path:
            raise RunnerError(f"{label} path must be a nonempty string")
        return cls(
            path=Path(raw_path),
            bytes=_nonnegative_integer(document["bytes"], f"{label} byte count"),
            sha256=_sha256_identity(document["sha256"], f"{label} SHA-256"),
        )


@dataclass(frozen=True, slots=True)
class AuthenticatedReferenceLimits:
    selection_max_total_references: int
    verification_max_total_references: int

    def __post_init__(self) -> None:
        selection = _nonnegative_integer(
            self.selection_max_total_references,
            "selection total-reference ceiling",
        )
        verification = _nonnegative_integer(
            self.verification_max_total_references,
            "verification total-reference ceiling",
        )
        if verification < selection:
            raise RunnerError(
                "verification total-reference ceiling must cover the selection ceiling"
            )

    @classmethod
    def from_authenticated_opl_bytes(
        cls,
        production_opl_bytes: int,
        broad_opl_bytes: int,
    ) -> "AuthenticatedReferenceLimits":
        production_bytes = _nonnegative_integer(
            production_opl_bytes,
            "authenticated production OPL byte count",
        )
        broad_bytes = _nonnegative_integer(
            broad_opl_bytes,
            "authenticated broad OPL byte count",
        )
        # Every strict OPL way reference or relation member contains at least a
        # one-byte object-kind prefix and one positive-ID digit. The containing
        # stream therefore cannot encode more than floor(bytes / 2) references.
        selection = production_bytes // 2
        verification = max(production_bytes, broad_bytes) // 2
        return cls(
            selection_max_total_references=selection,
            verification_max_total_references=verification,
        )

    def to_document(self) -> dict[str, object]:
        return {
            "derivation": _REFERENCE_LIMIT_DERIVATION,
            "selection": {
                "maxTotalReferences": self.selection_max_total_references,
            },
            "verification": {
                "maxTotalReferences": self.verification_max_total_references,
            },
        }

    @classmethod
    def from_document(cls, value: object) -> "AuthenticatedReferenceLimits":
        document = _exact_keys(
            value,
            {"derivation", "selection", "verification"},
            "runner reference limits",
        )
        if document["derivation"] != _REFERENCE_LIMIT_DERIVATION:
            raise RunnerError("runner reference-limit derivation is unsupported")
        selection = _exact_keys(
            document["selection"],
            {"maxTotalReferences"},
            "selection reference limits",
        )
        verification = _exact_keys(
            document["verification"],
            {"maxTotalReferences"},
            "verification reference limits",
        )
        return cls(
            selection_max_total_references=_nonnegative_integer(
                selection["maxTotalReferences"],
                "selection total-reference ceiling",
            ),
            verification_max_total_references=_nonnegative_integer(
                verification["maxTotalReferences"],
                "verification total-reference ceiling",
            ),
        )


@dataclass(frozen=True, slots=True)
class RunConfig:
    runs_root: Path
    mirror_root: Path
    interpreter: FileLock
    dependency_lock: FileLock
    planet_pbf: FileLock
    production_pbf: FileLock
    production_opl: FileLock
    broad_pbf: FileLock
    broad_opl: FileLock
    selector_sha256: str
    verifier_sha256: str
    policy_sha256: str
    selection_profile: str
    verification_profile: str
    workers: int = 1
    heartbeat_seconds: int = 20
    reference_limits: AuthenticatedReferenceLimits | None = None

    def __post_init__(self) -> None:
        for label, value in (
            ("runs root", self.runs_root),
            ("mirror root", self.mirror_root),
        ):
            if not isinstance(value, Path):
                raise RunnerError(f"{label} must be a pathlib.Path")
        for label, value in (
            ("selector SHA-256", self.selector_sha256),
            ("verifier SHA-256", self.verifier_sha256),
            ("policy SHA-256", self.policy_sha256),
        ):
            _sha256_identity(value, label)
        if type(self.selection_profile) is not str or not self.selection_profile:
            raise RunnerError("selection profile must be a nonempty string")
        if type(self.verification_profile) is not str or not self.verification_profile:
            raise RunnerError("verification profile must be a nonempty string")
        if type(self.workers) is not int or self.workers <= 0:
            raise RunnerError("worker count must be a positive integer")
        if (
            type(self.heartbeat_seconds) is not int
            or self.heartbeat_seconds <= 0
            or self.heartbeat_seconds > 30
        ):
            raise RunnerError("heartbeat interval must be an integer from 1 through 30")
        if self.reference_limits is None:
            object.__setattr__(
                self,
                "reference_limits",
                AuthenticatedReferenceLimits.from_authenticated_opl_bytes(
                    self.production_opl.bytes,
                    self.broad_opl.bytes,
                ),
            )
        elif not isinstance(self.reference_limits, AuthenticatedReferenceLimits):
            raise RunnerError("runner reference limits are missing or invalid")

    def validated_reference_limits(self) -> AuthenticatedReferenceLimits:
        actual = self.reference_limits
        if not isinstance(actual, AuthenticatedReferenceLimits):
            raise RunnerError("runner reference limits are missing or invalid")
        expected = AuthenticatedReferenceLimits.from_authenticated_opl_bytes(
            self.production_opl.bytes,
            self.broad_opl.bytes,
        )
        if actual != expected:
            raise RunnerError(
                "runner reference limits are not exactly bound to the authenticated OPL byte identities"
            )
        return actual

    def to_document(self) -> dict[str, object]:
        reference_limits = self.validated_reference_limits()
        return {
            "files": {
                "broadOpl": self.broad_opl.to_document(),
                "broadPbf": self.broad_pbf.to_document(),
                "dependencyLock": self.dependency_lock.to_document(),
                "interpreter": self.interpreter.to_document(),
                "planetPbf": self.planet_pbf.to_document(),
                "productionOpl": self.production_opl.to_document(),
                "productionPbf": self.production_pbf.to_document(),
            },
            "heartbeatSeconds": self.heartbeat_seconds,
            "limits": reference_limits.to_document(),
            "locks": {
                "policySha256": self.policy_sha256,
                "selectorSha256": self.selector_sha256,
                "verifierSha256": self.verifier_sha256,
            },
            "profiles": {
                "selection": self.selection_profile,
                "verification": self.verification_profile,
            },
            "roots": {
                "mirror": str(self.mirror_root),
                "runs": str(self.runs_root),
            },
            "schema": _CONFIG_SCHEMA,
            "workers": self.workers,
        }

    @classmethod
    def from_document(cls, value: object) -> "RunConfig":
        document = _exact_keys(
            value,
            {
                "files",
                "heartbeatSeconds",
                "limits",
                "locks",
                "profiles",
                "roots",
                "schema",
                "workers",
            },
            "runner config",
        )
        if document["schema"] != _CONFIG_SCHEMA:
            raise RunnerError("runner config schema is unsupported")
        files = _exact_keys(
            document["files"],
            {
                "broadOpl",
                "broadPbf",
                "dependencyLock",
                "interpreter",
                "planetPbf",
                "productionOpl",
                "productionPbf",
            },
            "runner config files",
        )
        locks = _exact_keys(
            document["locks"],
            {"policySha256", "selectorSha256", "verifierSha256"},
            "runner config locks",
        )
        profiles = _exact_keys(
            document["profiles"], {"selection", "verification"}, "runner config profiles"
        )
        roots = _exact_keys(document["roots"], {"mirror", "runs"}, "runner config roots")
        for label, raw in (("runs root", roots["runs"]), ("mirror root", roots["mirror"])):
            if type(raw) is not str or not raw:
                raise RunnerError(f"{label} must be a nonempty string")
        config = cls(
            runs_root=Path(roots["runs"]),
            mirror_root=Path(roots["mirror"]),
            interpreter=FileLock.from_document(files["interpreter"], "interpreter"),
            dependency_lock=FileLock.from_document(files["dependencyLock"], "dependency lock"),
            planet_pbf=FileLock.from_document(files["planetPbf"], "planet PBF"),
            production_pbf=FileLock.from_document(files["productionPbf"], "production PBF"),
            production_opl=FileLock.from_document(files["productionOpl"], "production OPL"),
            broad_pbf=FileLock.from_document(files["broadPbf"], "broad PBF"),
            broad_opl=FileLock.from_document(files["broadOpl"], "broad OPL"),
            selector_sha256=_sha256_identity(locks["selectorSha256"], "selector SHA-256"),
            verifier_sha256=_sha256_identity(locks["verifierSha256"], "verifier SHA-256"),
            policy_sha256=_sha256_identity(locks["policySha256"], "policy SHA-256"),
            selection_profile=profiles["selection"],
            verification_profile=profiles["verification"],
            workers=document["workers"],
            heartbeat_seconds=document["heartbeatSeconds"],
            reference_limits=AuthenticatedReferenceLimits.from_document(document["limits"]),
        )
        config.validated_reference_limits()
        return config


def load_config(
    path: str | Path,
    *,
    selector_sha256: str,
    verifier_sha256: str,
    policy_sha256: str,
) -> RunConfig:
    config_path = Path(path)
    validate_canonical_path(config_path, label="runner config", directory=False)
    try:
        raw = config_path.read_bytes()
    except OSError as error:
        raise RunnerError(f"runner config cannot be read: {error}") from error
    if len(raw) > 1024 * 1024:
        raise RunnerError("runner config exceeds its 1 MiB ceiling")
    document = _strict_json_bytes(raw, "runner config")
    if canonical_json_bytes(document) != raw:
        raise RunnerError("runner config is not canonical JSON")
    config = RunConfig.from_document(document)
    for label, explicit, configured in (
        ("selector", selector_sha256, config.selector_sha256),
        ("verifier", verifier_sha256, config.verifier_sha256),
        ("policy", policy_sha256, config.policy_sha256),
    ):
        _sha256_identity(explicit, f"explicit {label} SHA-256")
        if explicit != configured:
            raise RunnerError(f"explicit {label} SHA-256 does not match canonical config")
    return config


def _status_signature(status: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        status.st_dev,
        status.st_ino,
        status.st_size,
        status.st_mtime_ns,
        status.st_ctime_ns,
    )


class _PinnedFile:
    def __init__(self, role: str, lock: FileLock) -> None:
        self.role = role
        self.lock = lock
        self.path = validate_canonical_path(lock.path, label=role, directory=False)
        self.handle: BinaryIO | None = None
        self.signature: tuple[int, int, int, int, int] | None = None
        try:
            handle = self.path.open("rb", buffering=0)
            handle_status = os.fstat(handle.fileno())
            path_status = os.stat(self.path, follow_symlinks=False)
            if not stat.S_ISREG(handle_status.st_mode):
                raise RunnerError(f"{role} must be a regular file")
            if (handle_status.st_dev, handle_status.st_ino) != (
                path_status.st_dev,
                path_status.st_ino,
            ):
                raise RunnerError(f"{role} path changed while it was opened")
            initial_signature = _status_signature(handle_status)
            digest = hashlib.sha256()
            total = 0
            while True:
                raw = handle.read(4 * 1024 * 1024)
                if not raw:
                    break
                digest.update(raw)
                total += len(raw)
            final_handle = os.fstat(handle.fileno())
            final_path = os.stat(self.path, follow_symlinks=False)
            if (
                _status_signature(final_handle) != initial_signature
                or _status_signature(final_path) != initial_signature
            ):
                raise RunnerError(f"{role} changed while its identity was hashed")
            actual_sha256 = digest.hexdigest()
            if total != lock.bytes or actual_sha256 != lock.sha256:
                raise RunnerError(
                    f"{role} identity mismatch: expected {lock.bytes} bytes/{lock.sha256}, "
                    f"got {total} bytes/{actual_sha256}"
                )
            handle.seek(0)
            self.handle = handle
            self.signature = initial_signature
        except BaseException:
            if "handle" in locals():
                handle.close()
            raise

    def verify(self) -> None:
        if self.handle is None or self.signature is None:
            raise RunnerError(f"{self.role} pin is closed")
        try:
            handle_status = os.fstat(self.handle.fileno())
            path_status = os.stat(self.path, follow_symlinks=False)
        except OSError as error:
            raise RunnerError(f"{self.role} identity cannot be rechecked: {error}") from error
        if (
            _status_signature(handle_status) != self.signature
            or _status_signature(path_status) != self.signature
        ):
            raise RunnerError(f"{self.role} identity drifted after preflight")

    def rewind(self) -> BinaryIO:
        self.verify()
        assert self.handle is not None
        self.handle.seek(0)
        return self.handle

    def close(self) -> None:
        if self.handle is not None:
            self.handle.close()
            self.handle = None


@dataclass(slots=True)
class PreflightContext:
    config: RunConfig
    pins: dict[str, _PinnedFile]
    inputs: dict[str, FileLock]
    runtime: dict[str, object]
    runtime_sha256: str
    identities: dict[str, object]
    content_run_id: str
    space_watermark: dict[str, int]
    free_bytes: int
    _closed: bool = False

    def close(self) -> None:
        if self._closed:
            return
        failures: list[str] = []
        for pin in reversed(tuple(self.pins.values())):
            try:
                pin.close()
            except OSError as error:
                failures.append(str(error))
        self._closed = True
        if failures:
            raise RunnerError("preflight handle cleanup failed: " + " | ".join(failures))

    def __enter__(self) -> "PreflightContext":
        return self

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        self.close()


def _runtime_document(interpreter: FileLock, dependency_lock: FileLock) -> dict[str, object]:
    return {
        "dependencyLock": {
            "bytes": dependency_lock.bytes,
            "sha256": dependency_lock.sha256,
        },
        "interpreter": {
            "bytes": interpreter.bytes,
            "sha256": interpreter.sha256,
        },
        "machine": platform.machine(),
        "osName": os.name,
        "platform": platform.platform(),
        "pythonImplementation": platform.python_implementation(),
        "schema": _RUNTIME_SCHEMA,
        "sysVersion": sys.version,
    }


def preflight(config: RunConfig) -> PreflightContext:
    if not isinstance(config, RunConfig):
        raise RunnerError("runner config is missing or invalid")
    reference_limits = config.validated_reference_limits()
    validate_canonical_path(config.runs_root, label="runs root", directory=True)
    validate_canonical_path(config.mirror_root, label="mirror root", directory=True)
    running_interpreter = Path(sys.executable).resolve(strict=True)
    if _normalized_path(config.interpreter.path) != _normalized_path(running_interpreter):
        raise RunnerError("configured interpreter is not the running interpreter")

    allowed_selection_profiles = {
        selection_module.FIXTURE_NAME_ENVELOPE_PROFILE,
        selection_module.LIVE_NAME_ENVELOPE_PROFILE,
    }
    allowed_verification_profiles = {
        verifier_module.FIXTURE_BROAD_ENVELOPE_PROFILE,
        verifier_module.LIVE_BROAD_ENVELOPE_PROFILE,
    }
    if config.selection_profile not in allowed_selection_profiles:
        raise RunnerError("selection profile is not public or supported")
    if config.verification_profile not in allowed_verification_profiles:
        raise RunnerError("verification profile is not public or supported")
    if (
        selection_module.GLOBAL_POLICY_SHA256 != config.policy_sha256
        or verifier_module.GLOBAL_POLICY_SHA256 != config.policy_sha256
    ):
        raise RunnerError("configured policy SHA-256 does not match both public APIs")

    selector_path = Path(selection_module.__file__).resolve(strict=True)
    verifier_path = Path(verifier_module.__file__).resolve(strict=True)
    runner_path = Path(__file__).resolve(strict=True)
    runner_fact = _file_fact(runner_path, "global runner code")
    code_locks = {
        "runnerCode": FileLock(
            runner_path,
            runner_fact["bytes"],
            runner_fact["sha256"],
        ),
        "selectorCode": FileLock(
            selector_path, selector_path.stat().st_size, config.selector_sha256
        ),
        "verifierCode": FileLock(
            verifier_path, verifier_path.stat().st_size, config.verifier_sha256
        ),
    }
    requested = {
        "interpreter": config.interpreter,
        "dependencyLock": config.dependency_lock,
        **code_locks,
        "planetPbf": config.planet_pbf,
        "productionPbf": config.production_pbf,
        "productionOpl": config.production_opl,
        "broadPbf": config.broad_pbf,
        "broadOpl": config.broad_opl,
    }
    pins: dict[str, _PinnedFile] = {}
    try:
        for role, lock in requested.items():
            pins[role] = _PinnedFile(role, lock)
        runtime = _runtime_document(config.interpreter, config.dependency_lock)
        runtime_sha256 = hashlib.sha256(canonical_json_bytes(runtime)).hexdigest()
        inputs = {
            role: requested[role]
            for role in ("planetPbf", "productionPbf", "productionOpl", "broadPbf", "broadOpl")
        }
        identities: dict[str, object] = {
            "inputs": {
                role: {"bytes": lock.bytes, "sha256": lock.sha256}
                for role, lock in sorted(inputs.items())
            },
            "policySha256": config.policy_sha256,
            "referenceLimits": reference_limits.to_document(),
            "runnerSha256": runner_fact["sha256"],
            "runtimeSha256": runtime_sha256,
            "selectorSha256": config.selector_sha256,
            "verifierSha256": config.verifier_sha256,
        }
        content_document = {
            "identities": identities,
            "profiles": {
                "selection": config.selection_profile,
                "verification": config.verification_profile,
            },
            "schema": _CONTENT_SCHEMA,
            "workers": config.workers,
        }
        content_sha256 = hashlib.sha256(canonical_json_bytes(content_document)).hexdigest()
        content_run_id = f"fae8-osm-global-{content_sha256[:32]}"
        space_watermark = compute_space_watermark(
            production_opl_bytes=config.production_opl.bytes,
            broad_opl_bytes=config.broad_opl.bytes,
        )
        try:
            free_bytes = shutil.disk_usage(config.runs_root).free
        except OSError as error:
            raise RunnerError(f"runs-root free space cannot be measured: {error}") from error
        if free_bytes < space_watermark["requiredFreeBytes"]:
            raise RunnerError(
                "runs-root free space is below the computed watermark: "
                f"need {space_watermark['requiredFreeBytes']}, have {free_bytes}"
            )
        return PreflightContext(
            config=config,
            pins=pins,
            inputs=inputs,
            runtime=runtime,
            runtime_sha256=runtime_sha256,
            identities=identities,
            content_run_id=content_run_id,
            space_watermark=space_watermark,
            free_bytes=free_bytes,
        )
    except BaseException:
        for pin in reversed(tuple(pins.values())):
            pin.close()
        raise


class ProgressReader:
    """Count bytes returned by a binary reader without changing stream semantics."""

    def __init__(self, stream: object, *, total_bytes: int) -> None:
        if type(total_bytes) is not int or total_bytes < 0:
            raise RunnerError("progress total must be a nonnegative integer")
        if not callable(getattr(stream, "read", None)):
            raise RunnerError("progress source must expose read()")
        self._stream = stream
        self._total_bytes = total_bytes
        self._bytes_consumed = 0
        self._lock = threading.Lock()

    @property
    def bytes_consumed(self) -> int:
        with self._lock:
            return self._bytes_consumed

    @property
    def total_bytes(self) -> int:
        return self._total_bytes

    def _add(self, count: int) -> None:
        with self._lock:
            self._bytes_consumed += count

    def read(self, size: int = -1) -> bytes:
        result = self._stream.read(size)
        if not isinstance(result, bytes):
            raise RunnerError("progress source read() must return bytes")
        self._add(len(result))
        return result

    def readinto(self, buffer: object) -> int:
        readinto = getattr(self._stream, "readinto", None)
        if not callable(readinto):
            raise RunnerError("progress source does not expose readinto()")
        result = readinto(buffer)
        if type(result) is not int or result < 0:
            raise RunnerError("progress source readinto() returned an invalid count")
        self._add(result)
        return result

    def read1(self, size: int = -1) -> bytes:
        read1 = getattr(self._stream, "read1", None)
        if not callable(read1):
            raise RunnerError("progress source does not expose read1()")
        result = read1(size)
        if not isinstance(result, bytes):
            raise RunnerError("progress source read1() must return bytes")
        self._add(len(result))
        return result

    def readinto1(self, buffer: object) -> int:
        readinto1 = getattr(self._stream, "readinto1", None)
        if not callable(readinto1):
            raise RunnerError("progress source does not expose readinto1()")
        result = readinto1(buffer)
        if type(result) is not int or result < 0:
            raise RunnerError("progress source readinto1() returned an invalid count")
        self._add(result)
        return result

    def readline(self, size: int = -1) -> bytes:
        result = self._stream.readline(size)
        if not isinstance(result, bytes):
            raise RunnerError("progress source readline() must return bytes")
        self._add(len(result))
        return result

    def readlines(self, hint: int = -1) -> list[bytes]:
        result = self._stream.readlines(hint)
        if type(result) is not list or any(not isinstance(value, bytes) for value in result):
            raise RunnerError("progress source readlines() must return a list of bytes")
        self._add(sum(len(value) for value in result))
        return result

    def __iter__(self) -> "ProgressReader":
        return self

    def __next__(self) -> bytes:
        result = next(self._stream)
        if not isinstance(result, bytes):
            raise RunnerError("progress source iteration must return bytes")
        self._add(len(result))
        return result

    def seek(self, offset: int, whence: int = 0) -> int:
        return self._stream.seek(offset, whence)

    def tell(self) -> int:
        return self._stream.tell()

    def __getattr__(self, name: str) -> object:
        return getattr(self._stream, name)


def compute_space_watermark(
    *,
    production_opl_bytes: int,
    broad_opl_bytes: int,
) -> dict[str, int]:
    for label, value in (
        ("production OPL byte count", production_opl_bytes),
        ("broad OPL byte count", broad_opl_bytes),
    ):
        if type(value) is not int or value < 0:
            raise RunnerError(f"{label} must be a nonnegative integer")
    production_output = max(
        _MINIMUM_PRODUCTION_OUTPUT_BYTES,
        production_opl_bytes * 2,
    )
    verifier_scratch = max(
        _MINIMUM_VERIFIER_SCRATCH_BYTES,
        broad_opl_bytes + production_opl_bytes,
    )
    atomic_duplicate = production_output + verifier_scratch
    result = {
        "reserveBytes": _FIVE_GB_RESERVE_BYTES,
        "productionOutputBytes": production_output,
        "verifierScratchOutputBytes": verifier_scratch,
        "atomicDuplicateBytes": atomic_duplicate,
        "documentaryBytes": _DOCUMENTARY_BYTES,
    }
    result["requiredFreeBytes"] = sum(result.values())
    return result


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="run_osm_global_selection.py",
        description="Durable fail-closed Experiment 8 global OSM selection runner",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="execute one exact content-addressed run")
    run.add_argument("--config", required=True)
    run.add_argument("--selector-sha256", required=True)
    run.add_argument("--verifier-sha256", required=True)
    run.add_argument("--policy-sha256", required=True)
    inspect = commands.add_parser("inspect", help="read one run without changing it")
    inspect.add_argument("--run-dir", required=True)
    inspect.add_argument("--stale-after-seconds", type=float, default=45.0)
    return parser


def _write_canonical_stream(stream: object, document: Mapping[str, object]) -> None:
    raw = canonical_json_bytes(document)
    binary = getattr(stream, "buffer", None)
    if binary is not None:
        binary.write(raw)
        binary.flush()
        return
    stream.write(raw.decode("utf-8"))
    stream.flush()


def main(argv: list[str] | None = None) -> int:
    arguments = _argument_parser().parse_args(argv)
    if arguments.command == "inspect":
        document = inspect_run(
            arguments.run_dir,
            stale_after_seconds=arguments.stale_after_seconds,
        )
        _write_canonical_stream(sys.stdout, document)
        return 0
    try:
        config = load_config(
            arguments.config,
            selector_sha256=arguments.selector_sha256,
            verifier_sha256=arguments.verifier_sha256,
            policy_sha256=arguments.policy_sha256,
        )
        outcome = run_global_selection(config)
        document = {
            "attemptId": outcome.attempt_id,
            "completePath": str(outcome.complete_path),
            "finalManifestPath": str(outcome.final_manifest_path),
            "mirrorDirectory": str(outcome.mirror_dir),
            "runId": outcome.run_id,
            "schema": "flight-alert-exp8-osm-global-run-outcome-v1",
            "state": "complete",
            "statusPath": str(outcome.attempt_dir / "status.json"),
        }
        _write_canonical_stream(sys.stdout, document)
        return 0
    except RunnerError as error:
        failure = {
            "error": {
                "class": f"{type(error).__module__}.{type(error).__qualname__}",
                "message": str(error),
            },
            "schema": "flight-alert-exp8-osm-global-run-outcome-v1",
            "state": "failed",
        }
        _write_canonical_stream(sys.stderr, failure)
        return 2


__all__ = [
    "AtomicRunLock",
    "AttemptJournal",
    "FileLock",
    "ProgressReader",
    "RunConfig",
    "RunOutcome",
    "RunnerError",
    "canonical_json_bytes",
    "compute_space_watermark",
    "current_process_identity",
    "inspect_run",
    "load_config",
    "main",
    "preflight",
    "process_identity_is_alive",
    "run_global_selection",
    "validate_canonical_path",
]


if __name__ == "__main__":
    raise SystemExit(main())
