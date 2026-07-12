from __future__ import annotations

import ctypes
import hashlib
import os
import re
import secrets
import stat
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import tools.experiment8.osm_hydro_source as osm_hydro_source
from tools.experiment8.osm_admission_evidence import (
    ADMISSION_GENERATOR,
    MARYLAND_COMPLETE_RELATION_COUNT,
    MARYLAND_ROOT_IDS_BYTES,
    MARYLAND_ROOT_IDS_SHA256,
    MARYLAND_SELECTED_RELATION_COUNT,
    MARYLAND_SELECTED_WAY_COUNT,
    AdmissionProbeError,
    CheckRefsSummary,
    MarylandAdmissionResult,
    RawProcessCapture,
    _MAX_ID_FILE_BYTES,
    _canonical_hash,
    _canonical_json_bytes,
    _canonical_posix_path,
    _sealed_result,
    _sha256,
    _strict_ids,
    encode_canonical_id_file,
    parse_canonical_id_file,
    parse_check_refs_result,
    parse_id_file_getid_result,
)
from tools.experiment8.osm_closure_audit_bundle import (
    audit_predicted_with_cached_global,
)
from tools.experiment8.osm_closure_probe import (
    BOOST_LIBRARY_PATH,
    BOOST_LIBRARY_SHA256,
    MAX_CAPTURE_BYTES,
    OSMIUM_BINARY_PATH,
    OSMIUM_BINARY_SHA256,
    PINNED_LOCALE,
    PINNED_UBUNTU_DISTRIBUTION,
    PinnedOsmiumClosureProbe,
    ProcessEvidence,
    RUNTIME_LIBRARY_PATH,
    WSL_EXECUTABLE,
    run_bounded_process,
)
from tools.experiment8.osm_hydro_source import (
    MARYLAND_REGIONAL_PROFILE,
    MARYLAND_SOURCE_SHA256,
    MissingReferences,
    OsmDataset,
    RegionalClosureClassification,
    RelationClosureAudit,
    RootSelection,
)


_GENERIC_READ_ACCESS = 0x80000000
_DELETE_ACCESS = 0x00010000
_FILE_READ_ATTRIBUTES = 0x00000080
_SYNCHRONIZE_ACCESS = 0x00100000
_FILE_SHARE_READ = 0x00000001
_FILE_SHARE_WRITE = 0x00000002
_FILE_SHARE_DELETE = 0x00000004
_OPEN_EXISTING = 3
_FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
_FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
_FILE_DISPOSITION_INFO_CLASS = 4
_FILE_RENAME_INFO_CLASS = 3
_FILE_ATTRIBUTE_REPARSE_POINT = getattr(
    stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x00000400
)


@dataclass(frozen=True, slots=True)
class _AdmissionSnapshot:
    source_path: Path
    source_wsl_path: str
    source_bytes: int
    source_sha256: str
    candidate_pbf_bytes: int
    candidate_pbf_sha256: str
    candidate_xml: bytes = field(repr=False)
    candidate_xml_sha256: str
    selector_sha256: str
    policy_sha256: str
    runtime_identity_sha256: str

    def __post_init__(self) -> None:
        if (
            not isinstance(self.source_path, Path)
            or not self.source_path.is_absolute()
            or isinstance(self.source_bytes, bool)
            or not isinstance(self.source_bytes, int)
            or self.source_bytes <= 0
            or isinstance(self.candidate_pbf_bytes, bool)
            or not isinstance(self.candidate_pbf_bytes, int)
            or self.candidate_pbf_bytes <= 0
            or type(self.candidate_xml) is not bytes
            or not self.candidate_xml
        ):
            raise AdmissionProbeError("admission snapshot paths/bytes are invalid")
        _canonical_posix_path(self.source_wsl_path, "snapshot source WSL path")
        for value, label in (
            (self.source_sha256, "snapshot source"),
            (self.candidate_pbf_sha256, "snapshot candidate PBF"),
            (self.candidate_xml_sha256, "snapshot candidate XML"),
            (self.selector_sha256, "snapshot selector"),
            (self.policy_sha256, "snapshot policy"),
            (self.runtime_identity_sha256, "snapshot runtime identity"),
        ):
            _canonical_hash(value, label)
        if _sha256(self.candidate_xml) != self.candidate_xml_sha256:
            raise AdmissionProbeError("snapshot candidate XML hash does not match bytes")


@dataclass(frozen=True, slots=True)
class _AdmissionExpectations:
    selected_way_count: int
    selected_relation_count: int
    complete_relation_count: int
    root_ids_bytes: int
    root_ids_sha256: str

    def __post_init__(self) -> None:
        for value, label in (
            (self.selected_way_count, "selected way count"),
            (self.selected_relation_count, "selected relation count"),
            (self.complete_relation_count, "complete relation count"),
            (self.root_ids_bytes, "root-ID byte count"),
        ):
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise AdmissionProbeError(f"{label} must be a positive integer")
        if self.complete_relation_count > self.selected_relation_count:
            raise AdmissionProbeError("complete relation count exceeds selected count")
        _canonical_hash(self.root_ids_sha256, "expected root IDs")


@dataclass(frozen=True, slots=True)
class _RelationGateOutcome:
    classification: RegionalClosureClassification
    audit: RelationClosureAudit
    raw_processes: tuple[RawProcessCapture, ...]


@dataclass(frozen=True, slots=True)
class _FileIdentity:
    device: int
    inode: int
    mode: int
    size: int
    modified_time_ns: int
    changed_time_ns: int


@dataclass(frozen=True, slots=True)
class _ComponentIdentity:
    device: int
    inode: int
    mode: int
    changed_time_ns: int


@dataclass(frozen=True, slots=True)
class _StableFile:
    path: Path
    identity: _FileIdentity
    bytes: int
    sha256: str
    content: bytes | None = field(default=None, repr=False)


@dataclass(slots=True)
class _PublishedGuardReceipt:
    fd: int | None = None
    identity: _FileIdentity | None = None
    path: Path | None = None


def _identity(raw: os.stat_result) -> _FileIdentity:
    return _FileIdentity(
        device=raw.st_dev,
        inode=raw.st_ino,
        mode=raw.st_mode,
        size=raw.st_size,
        modified_time_ns=raw.st_mtime_ns,
        changed_time_ns=raw.st_ctime_ns,
    )


def _component_identity(raw: os.stat_result) -> _ComponentIdentity:
    return _ComponentIdentity(
        raw.st_dev,
        raw.st_ino,
        raw.st_mode,
        raw.st_ctime_ns,
    )


def _lexists(path: Path) -> bool:
    return os.path.lexists(os.fspath(path))


def _is_reparse_stat(raw: os.stat_result) -> bool:
    return stat.S_ISLNK(raw.st_mode) or bool(
        getattr(raw, "st_file_attributes", 0) & _FILE_ATTRIBUTE_REPARSE_POINT
    )


def _component_snapshot(
    path: Path,
) -> tuple[tuple[Path, _ComponentIdentity], ...]:
    if not path.is_absolute():
        raise AdmissionProbeError("filesystem path must be absolute")
    if ".." in path.parts or any(
        any(ord(character) < 32 or ord(character) == 127 for character in part)
        for part in path.parts
    ):
        raise AdmissionProbeError("filesystem path is not canonical text")
    chain = tuple(reversed(path.parents)) + (path,)
    result: list[tuple[Path, _ComponentIdentity]] = []
    for component in chain:
        if not _lexists(component):
            raise AdmissionProbeError(f"path component is unavailable: {component}")
        try:
            raw = os.lstat(component)
        except OSError as error:
            raise AdmissionProbeError(
                f"path component could not be inspected: {component}: {error}"
            ) from error
        if _is_reparse_stat(raw):
            raise AdmissionProbeError(
                f"symlink/reparse path component is forbidden: {component}"
            )
        result.append(
            (
                component,
                _component_identity(raw),
            )
        )
    return tuple(result)


def _require_components_unchanged(
    path: Path,
    expected: tuple[tuple[Path, _ComponentIdentity], ...],
    *,
    label: str,
) -> None:
    if _component_snapshot(path) != expected:
        raise AdmissionProbeError(f"{label} path components changed")


def _stable_file(
    path: Path,
    *,
    label: str,
    capture: bool = False,
    maximum_capture_bytes: int | None = None,
) -> _StableFile:
    if not isinstance(path, Path) or not path.is_absolute():
        raise AdmissionProbeError(f"{label} path must be absolute")
    components_before = _component_snapshot(path)
    try:
        resolved = path.resolve(strict=True)
        stream = resolved.open("rb")
    except OSError as error:
        raise AdmissionProbeError(f"{label} is unavailable: {error}") from error
    digest = hashlib.sha256()
    captured = bytearray() if capture else None
    try:
        before_stat = os.fstat(stream.fileno())
        if not stat.S_ISREG(before_stat.st_mode) or _is_reparse_stat(before_stat):
            raise AdmissionProbeError(f"{label} is not a non-reparse regular file")
        before = _identity(before_stat)
        if maximum_capture_bytes is not None and before.size > maximum_capture_bytes:
            raise AdmissionProbeError(
                f"{label} exceeds its {maximum_capture_bytes}-byte ceiling"
            )
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            if captured is not None:
                captured.extend(chunk)
        after = _identity(os.fstat(stream.fileno()))
    except OSError as error:
        raise AdmissionProbeError(f"{label} became unreadable: {error}") from error
    finally:
        stream.close()
    try:
        path_after_stat = os.lstat(resolved)
    except OSError as error:
        raise AdmissionProbeError(f"{label} disappeared after hashing: {error}") from error
    if _is_reparse_stat(path_after_stat):
        raise AdmissionProbeError(f"{label} became a reparse point")
    path_after = _identity(path_after_stat)
    if before != after or before != path_after:
        raise AdmissionProbeError(f"{label} file identity changed while hashing")
    components_after = _component_snapshot(path)
    if components_after != components_before:
        raise AdmissionProbeError(f"{label} path components changed while hashing")
    return _StableFile(
        path=resolved,
        identity=before,
        bytes=before.size,
        sha256=digest.hexdigest(),
        content=bytes(captured) if captured is not None else None,
    )


def _validated_destination_snapshot(
    destination: str | Path,
) -> tuple[Path, tuple[tuple[Path, _ComponentIdentity], ...]]:
    target = Path(destination)
    if not target.is_absolute() or not target.name.endswith(".osm.pbf"):
        raise AdmissionProbeError(
            "admission destination must be an absolute new .osm.pbf path"
        )
    lexical_parent = target.parent
    parent_before = _component_snapshot(lexical_parent)
    try:
        parent = lexical_parent.resolve(strict=True)
    except OSError as error:
        raise AdmissionProbeError(f"destination parent is unavailable: {error}") from error
    parent_after = _component_snapshot(lexical_parent)
    if parent_after != parent_before:
        raise AdmissionProbeError(
            "destination parent path components changed during resolution"
        )
    resolved_parent_before = _component_snapshot(parent)
    if resolved_parent_before[-1][1] != parent_before[-1][1]:
        raise AdmissionProbeError(
            "destination parent resolution redirected to a different directory"
        )
    if not parent.is_dir():
        raise AdmissionProbeError("destination parent must be a directory")
    target = parent / target.name
    if _lexists(target):
        raise AdmissionProbeError(f"admission destination already exists: {target}")
    resolved_parent_after = _component_snapshot(parent)
    if resolved_parent_after != resolved_parent_before:
        raise AdmissionProbeError(
            "destination parent path components changed during validation"
        )
    return target, resolved_parent_after


def _validated_destination(destination: str | Path) -> Path:
    return _validated_destination_snapshot(destination)[0]


def _windows_path_to_wsl(path: Path, *, must_exist: bool) -> str:
    if not isinstance(path, Path) or not path.is_absolute():
        raise AdmissionProbeError("WSL conversion requires an absolute Windows path")
    if must_exist:
        try:
            value = path.resolve(strict=True)
        except OSError as error:
            raise AdmissionProbeError(f"WSL input path is unavailable: {error}") from error
    else:
        try:
            parent = path.parent.resolve(strict=True)
        except OSError as error:
            raise AdmissionProbeError(f"WSL output parent is unavailable: {error}") from error
        value = parent / path.name
    drive = value.drive
    if re.fullmatch(r"[A-Za-z]:", drive) is None:
        raise AdmissionProbeError("WSL path conversion requires a drive-letter path")
    relative_parts = value.parts[1:]
    if any(
        not part
        or part in {".", ".."}
        or "/" in part
        or any(ord(character) < 32 or ord(character) == 127 for character in part)
        for part in relative_parts
    ):
        raise AdmissionProbeError("Windows path cannot be represented canonically in WSL")
    wsl = f"/mnt/{drive[0].lower()}"
    if relative_parts:
        wsl += "/" + "/".join(relative_parts)
    return _canonical_posix_path(wsl, "derived WSL path")


def _base_wsl_argv() -> tuple[str, ...]:
    return (
        WSL_EXECUTABLE,
        "-d",
        PINNED_UBUNTU_DISTRIBUTION,
        "--",
        "/usr/bin/env",
        "-i",
        f"LC_ALL={PINNED_LOCALE}",
        f"LANG={PINNED_LOCALE}",
        "LANGUAGE=C",
    )


def _hash_argv(source_wsl_path: str) -> tuple[str, ...]:
    return _base_wsl_argv() + (
        "/usr/bin/sha256sum",
        "--binary",
        OSMIUM_BINARY_PATH,
        BOOST_LIBRARY_PATH,
        source_wsl_path,
    )


def _getid_argv(
    *,
    source_wsl_path: str,
    output_wsl_path: str,
    id_file_wsl_path: str,
    overwrite: bool,
    fsync: bool,
) -> tuple[str, ...]:
    options: tuple[str, ...] = (
        "getid",
        "--no-progress",
        "--verbose",
        "-r",
        "--generator",
        ADMISSION_GENERATOR,
        "-f",
        "pbf",
    )
    if overwrite:
        options += ("-O",)
    if fsync:
        options += ("--fsync",)
    return _base_wsl_argv() + (
        f"LD_LIBRARY_PATH={RUNTIME_LIBRARY_PATH}",
        OSMIUM_BINARY_PATH,
        *options,
        "-o",
        output_wsl_path,
        source_wsl_path,
        "-i",
        id_file_wsl_path,
    )


def _check_refs_argv(output_wsl_path: str) -> tuple[str, ...]:
    return _base_wsl_argv() + (
        f"LD_LIBRARY_PATH={RUNTIME_LIBRARY_PATH}",
        OSMIUM_BINARY_PATH,
        "check-refs",
        "--no-progress",
        "--verbose",
        "-r",
        "-F",
        "pbf",
        output_wsl_path,
    )


def _run_exact_process(
    runner: Callable[[tuple[str, ...]], ProcessEvidence],
    argv: tuple[str, ...],
    label: str,
) -> ProcessEvidence:
    if not callable(runner):
        raise AdmissionProbeError("private process runner is not callable")
    try:
        process = runner(argv)
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as error:
        raise AdmissionProbeError(f"{label} process failed to execute: {error}") from error
    if not isinstance(process, ProcessEvidence) or process.argv != argv:
        raise AdmissionProbeError(f"{label} did not return exact argv-bound evidence")
    if len(process.stdout) > MAX_CAPTURE_BYTES or len(process.stderr) > MAX_CAPTURE_BYTES:
        raise AdmissionProbeError(f"{label} process evidence exceeds the capture ceiling")
    return process


def _validate_hash_process(process: ProcessEvidence, snapshot: _AdmissionSnapshot) -> None:
    expected_stdout = (
        f"{OSMIUM_BINARY_SHA256} *{OSMIUM_BINARY_PATH}\n"
        f"{BOOST_LIBRARY_SHA256} *{BOOST_LIBRARY_PATH}\n"
        f"{snapshot.source_sha256} *{snapshot.source_wsl_path}\n"
    ).encode("utf-8")
    if process.returncode != 0 or process.stdout != expected_stdout or process.stderr != b"":
        raise AdmissionProbeError("runtime/source hash transcript did not match exact locks")


def _write_exclusive_fsync(
    path: Path,
    content: bytes,
    ownership: dict[str, _FileIdentity],
) -> None:
    if _lexists(path):
        raise AdmissionProbeError(f"staging file already exists: {path.name}")
    try:
        with path.open("xb") as stream:
            raw = os.fstat(stream.fileno())
            if _is_reparse_stat(raw) or not stat.S_ISREG(raw.st_mode):
                raise AdmissionProbeError(
                    f"staging file is not regular after exclusive create: {path.name}"
                )
            ownership[path.name] = _identity(raw)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except OSError as error:
        raise AdmissionProbeError(f"staging file write failed: {path.name}: {error}") from error


def _file_rename_info_buffer(destination: Path) -> ctypes.Array:
    if os.name != "nt" or not isinstance(destination, Path) or not destination.is_absolute():
        raise AdmissionProbeError("handle rename requires an absolute Windows destination")
    if "\x00" in str(destination):
        raise AdmissionProbeError("handle rename destination contains NUL")
    from ctypes import wintypes

    class _FileRenameInfo(ctypes.Structure):
        _fields_ = [
            ("ReplaceIfExists", wintypes.BOOLEAN),
            ("RootDirectory", wintypes.HANDLE),
            ("FileNameLength", wintypes.DWORD),
            ("FileName", wintypes.WCHAR * 1),
        ]

    encoded = str(destination).encode("utf-16-le")
    name_offset = _FileRenameInfo.FileName.offset
    buffer = ctypes.create_string_buffer(name_offset + len(encoded) + 2)
    information = _FileRenameInfo.from_buffer(buffer)
    information.ReplaceIfExists = False
    information.RootDirectory = None
    information.FileNameLength = len(encoded)
    ctypes.memmove(ctypes.addressof(buffer) + name_offset, encoded, len(encoded))
    return buffer


def _publish_no_replace(
    source: Path,
    destination: Path,
    *,
    guard_fd: int,
) -> None:
    """Rename through one strict DELETE handle without releasing its identity lock."""

    if source.parent.parent != destination.parent:
        raise AdmissionProbeError(
            "handle publication source is outside the receipted staging directory"
        )
    if _lexists(destination):
        raise AdmissionProbeError(f"admission destination already exists: {destination}")
    try:
        source_identity = _identity(os.lstat(source))
    except OSError as error:
        raise AdmissionProbeError(f"publication source is unavailable: {error}") from error
    _require_open_regular_file_identity(
        guard_fd,
        source_identity,
        label="handle publication source",
    )
    buffer = _file_rename_info_buffer(destination)
    from ctypes import wintypes
    import msvcrt

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    set_information.restype = wintypes.BOOL
    if not set_information(
        msvcrt.get_osfhandle(guard_fd),
        _FILE_RENAME_INFO_CLASS,
        ctypes.byref(buffer),
        len(buffer),
    ):
        number = ctypes.get_last_error()
        raise AdmissionProbeError(
            "atomic handle no-replace publication failed: "
            f"[WinError {number}] {ctypes.FormatError(number)}"
        )
    if _lexists(source) or not _lexists(destination):
        raise AdmissionProbeError("handle publication did not move exactly one source")


def _open_windows_delete_fd(
    path: Path,
    *,
    label: str,
    directory: bool,
    delete_access: bool = True,
    share_delete: bool = False,
    read_data: bool = False,
) -> int:
    """Open one exact non-reparse object and deny rename/delete replacement."""

    if os.name != "nt":
        raise AdmissionProbeError(
            f"{label} exact handle deletion requires the pinned Windows host"
        )
    from ctypes import wintypes
    import msvcrt

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = [
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_void_p,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    ]
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = [wintypes.HANDLE]
    close_handle.restype = wintypes.BOOL
    flags = _FILE_FLAG_OPEN_REPARSE_POINT
    if directory:
        flags |= _FILE_FLAG_BACKUP_SEMANTICS
    desired_access = _FILE_READ_ATTRIBUTES | _SYNCHRONIZE_ACCESS
    if delete_access:
        desired_access |= _DELETE_ACCESS
    if read_data:
        desired_access |= _GENERIC_READ_ACCESS
    share_mode = _FILE_SHARE_READ | (_FILE_SHARE_WRITE if directory else 0)
    if share_delete:
        share_mode |= _FILE_SHARE_DELETE
    handle = create_file(
        str(path),
        desired_access,
        share_mode,
        None,
        _OPEN_EXISTING,
        flags,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise AdmissionProbeError(
            f"{label} exact handle open failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    try:
        return msvcrt.open_osfhandle(
            int(handle), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        close_handle(handle)
        raise


def _mark_windows_fd_for_delete(fd: int, *, label: str) -> None:
    """Mark the object referenced by *fd*, never a later path occupant, for delete."""

    from ctypes import wintypes
    import msvcrt

    class _FileDispositionInfo(ctypes.Structure):
        _fields_ = [("delete_file", ctypes.c_ubyte)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    set_information.restype = wintypes.BOOL
    information = _FileDispositionInfo(1)
    handle = msvcrt.get_osfhandle(fd)
    if not set_information(
        handle,
        _FILE_DISPOSITION_INFO_CLASS,
        ctypes.byref(information),
        ctypes.sizeof(information),
    ):
        number = ctypes.get_last_error()
        raise AdmissionProbeError(
            f"{label} exact handle deletion failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )


def _same_retained_file_object(
    current: _FileIdentity, expected: _FileIdentity
) -> bool:
    """Compare stable object identity, allowing transaction-owned content drift."""

    return (
        current.device == expected.device
        and current.inode == expected.inode
        and stat.S_IFMT(current.mode) == stat.S_IFMT(expected.mode)
        and (
            os.name != "nt"
            or current.changed_time_ns == expected.changed_time_ns
        )
    )


def _same_file_object(raw: os.stat_result, expected: _FileIdentity) -> bool:
    return _same_retained_file_object(_identity(raw), expected)


def _require_open_regular_file_identity(
    fd: int, expected_identity: _FileIdentity, *, label: str
) -> None:
    try:
        raw = os.fstat(fd)
    except OSError as error:
        raise AdmissionProbeError(f"{label} handle inspection failed: {error}") from error
    if (
        _is_reparse_stat(raw)
        or not stat.S_ISREG(raw.st_mode)
        or not _same_file_object(raw, expected_identity)
    ):
        raise AdmissionProbeError(f"refusing to use a replaced/reparse {label}")


def _stable_guarded_file(fd: int, path: Path, *, label: str) -> _StableFile:
    """Hash one retained file handle while proving its expected path stays bound."""

    components_before = _component_snapshot(path)
    try:
        before_stat = os.fstat(fd)
        if not stat.S_ISREG(before_stat.st_mode) or _is_reparse_stat(before_stat):
            raise AdmissionProbeError(f"{label} is not a non-reparse regular file")
        before = _identity(before_stat)
        os.lseek(fd, 0, os.SEEK_SET)
        digest = hashlib.sha256()
        while chunk := os.read(fd, 1024 * 1024):
            digest.update(chunk)
        after = _identity(os.fstat(fd))
    except OSError as error:
        raise AdmissionProbeError(f"{label} handle read failed: {error}") from error
    try:
        path_after = _identity(os.lstat(path))
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise AdmissionProbeError(f"{label} path readback failed: {error}") from error
    if before != after or before != path_after:
        raise AdmissionProbeError(f"{label} identity changed during guarded readback")
    if _component_snapshot(path) != components_before:
        raise AdmissionProbeError(f"{label} path changed during guarded readback")
    return _StableFile(
        path=resolved,
        identity=before,
        bytes=before.size,
        sha256=digest.hexdigest(),
    )


def _delete_exact_regular_file(
    path: Path, expected_identity: _FileIdentity, *, label: str
) -> None:
    """Delete only the regular-file object whose stable identity was retained."""

    fd: int | None = None
    try:
        fd = _open_windows_delete_fd(path, label=label, directory=False)
    except AdmissionProbeError:
        if not _lexists(path):
            return
        raise
    except BaseException as error:
        if fd is not None:
            close_error = _release_exact_fd(fd, expected_identity, label=label)
            if close_error is not None:
                add_note = getattr(error, "add_note", None)
                if callable(add_note):
                    add_note(f"{label} acquisition cleanup also failed: {close_error}")
        raise
    operation_error: BaseException | None = None
    try:
        _require_open_regular_file_identity(fd, expected_identity, label=label)
        _mark_windows_fd_for_delete(fd, label=label)
    except BaseException as error:
        operation_error = error
    try:
        close_error = _release_exact_fd(fd, expected_identity, label=label)
    except BaseException as error:
        retry_error = _release_exact_fd(fd, expected_identity, label=label)
        close_error = error
        if retry_error is not None:
            add_note = getattr(close_error, "add_note", None)
            if callable(add_note):
                add_note(f"{label} outer release retry also failed: {retry_error}")
    if operation_error is not None:
        if close_error is not None:
            add_note = getattr(operation_error, "add_note", None)
            if callable(add_note):
                add_note(f"{label} release also failed: {close_error}")
        raise operation_error
    if close_error is not None:
        raise close_error
    if _lexists(path):
        raise AdmissionProbeError(
            f"{label} path was replaced or remained after exact deletion"
        )


def _close_fd_with_single_retry(
    fd: int,
    expected_identity: _FileIdentity | _ComponentIdentity,
    *,
    label: str,
) -> BaseException | None:
    """Close once, retrying only when an interruption left the exact fd open."""

    try:
        os.close(fd)
    except BaseException as error:
        try:
            raw = os.fstat(fd)
        except OSError:
            return error
        if isinstance(expected_identity, _FileIdentity):
            matches = _same_file_object(raw, expected_identity)
        else:
            matches = _component_identity(raw) == expected_identity
        if not matches:
            return error
        try:
            os.close(fd)
        except OSError:
            try:
                os.fstat(fd)
            except OSError:
                return error
            raise AdmissionProbeError(f"{label} remained open after close retry") from error
        except BaseException as retry_error:
            raise AdmissionProbeError(
                f"{label} close retry was interrupted"
            ) from retry_error
        return error
    return None


def _release_exact_fd(
    fd: int,
    expected_identity: _FileIdentity | _ComponentIdentity,
    *,
    label: str,
) -> BaseException | None:
    """Release a retained exact fd and preserve one interruption after retry."""

    try:
        return _close_fd_with_single_retry(
            fd,
            expected_identity,
            label=label,
        )
    except BaseException as error:
        retry_error = _close_fd_with_single_retry(
            fd,
            expected_identity,
            label=label,
        )
        if retry_error is not None:
            add_note = getattr(error, "add_note", None)
            if callable(add_note):
                add_note(f"{label} release retry also failed: {retry_error}")
        return error


def _abort_published_guard(
    receipt: _PublishedGuardReceipt,
    *,
    label: str,
) -> None:
    """Delete the exact retained publication object without a path handoff."""

    if receipt.fd is None and receipt.identity is None:
        return
    if receipt.fd is None or receipt.identity is None:
        raise AdmissionProbeError(f"{label} retained publication receipt is incomplete")
    fd = receipt.fd
    identity = receipt.identity
    mark_error: BaseException | None = None
    retry_error: BaseException | None = None
    try:
        _require_open_regular_file_identity(fd, identity, label=label)
        _mark_windows_fd_for_delete(fd, label=label)
    except BaseException as error:
        mark_error = error
        try:
            _require_open_regular_file_identity(fd, identity, label=label)
            _mark_windows_fd_for_delete(fd, label=label)
        except BaseException as error:
            retry_error = error
    close_error = _release_exact_fd(fd, identity, label=label)
    receipt.fd = None
    receipt.identity = None
    path = receipt.path
    receipt.path = None
    if path is not None and _lexists(path):
        try:
            remaining = os.lstat(path)
        except OSError as error:
            raise AdmissionProbeError(
                f"{label} path inspection failed after guarded deletion: {error}"
            ) from error
        if _same_file_object(remaining, identity):
            failure = AdmissionProbeError(f"{label} exact object remained after deletion")
            if retry_error is not None:
                raise failure from retry_error
            if mark_error is not None:
                raise failure from mark_error
            raise failure
        raise AdmissionProbeError(f"{label} path was replaced during guarded deletion")
    if retry_error is not None:
        if isinstance(mark_error, (KeyboardInterrupt, SystemExit)) and isinstance(
            retry_error, AdmissionProbeError
        ):
            raise retry_error from mark_error
        raise AdmissionProbeError(f"{label} delete mark retry failed") from retry_error
    if mark_error is not None:
        raise mark_error
    if close_error is not None:
        raise close_error


def _commit_published_guard(receipt: _PublishedGuardReceipt) -> None:
    """Cross the final commit boundary by releasing the last strict output guard."""

    if receipt.fd is None or receipt.identity is None or receipt.path is None:
        raise AdmissionProbeError("final publication guard receipt is incomplete")
    fd = receipt.fd
    identity = receipt.identity
    try:
        os.close(fd)
    except BaseException as error:
        try:
            raw = os.fstat(fd)
        except OSError:
            receipt.fd = None
            receipt.identity = None
            receipt.path = None
            return
        if not _same_file_object(raw, identity):
            receipt.fd = None
            receipt.identity = None
            receipt.path = None
            return
        try:
            _abort_published_guard(
                receipt,
                label="interrupted final publication commit",
            )
        except AdmissionProbeError as rollback_error:
            raise rollback_error from error
        raise
    receipt.fd = None
    receipt.identity = None
    receipt.path = None


@dataclass(slots=True)
class _DirectoryIdentityLock:
    path: Path
    expected_components: tuple[tuple[Path, _ComponentIdentity], ...]
    fd: int | None = None

    def reacquire(self) -> None:
        if self.fd is not None:
            raise AdmissionProbeError("destination parent identity lock is already held")
        fd: int | None = None
        try:
            fd = _open_windows_delete_fd(
                self.path,
                label="destination parent identity lock",
                directory=True,
            )
            self.fd = fd
            raw = os.fstat(fd)
            if (
                _is_reparse_stat(raw)
                or not stat.S_ISDIR(raw.st_mode)
                or _component_identity(raw) != self.expected_components[-1][1]
                or _component_snapshot(self.path) != self.expected_components
            ):
                raise AdmissionProbeError(
                    "destination parent changed before its identity lock was acquired"
                )
        except BaseException as error:
            try:
                if self.fd is not None:
                    self.release()
                elif fd is not None:
                    close_error = _release_exact_fd(
                        fd,
                        self.expected_components[-1][1],
                        label="failed destination parent identity lock",
                    )
                    if close_error is not None:
                        raise close_error
            except BaseException as close_error:
                add_note = getattr(error, "add_note", None)
                if callable(add_note):
                    add_note(
                        "failed destination parent identity lock release also "
                        f"failed: {close_error}"
                    )
            raise

    def release(self) -> None:
        if self.fd is None:
            return
        fd = self.fd
        try:
            release_error = _release_exact_fd(
                fd,
                self.expected_components[-1][1],
                label="destination parent identity lock",
            )
        except BaseException as error:
            retry_error = _release_exact_fd(
                fd,
                self.expected_components[-1][1],
                label="destination parent identity lock",
            )
            self.fd = None
            if retry_error is not None:
                add_note = getattr(error, "add_note", None)
                if callable(add_note):
                    add_note(f"parent lock release retry also failed: {retry_error}")
            raise
        self.fd = None
        if release_error is not None:
            raise release_error


def _rollback_published(path: Path, expected_identity: _FileIdentity) -> None:
    """Remove only the exact regular file identity moved by this transaction."""

    try:
        _delete_exact_regular_file(
            path,
            expected_identity,
            label="published rollback destination",
        )
    except (KeyboardInterrupt, SystemExit) as error:
        try:
            _delete_exact_regular_file(
                path,
                expected_identity,
                label="published rollback destination",
            )
        except AdmissionProbeError as retry_error:
            raise retry_error from error
        raise


def _cleanup_staging(
    staging: Path,
    parent: Path,
    parent_components: tuple[tuple[Path, _ComponentIdentity], ...],
    staging_identity: _ComponentIdentity,
    expected_files: dict[str, _FileIdentity],
    planned_files: frozenset[str] | None = None,
) -> None:
    """Delete only retained file identities beneath the retained staging directory."""

    if staging.parent != parent:
        raise AdmissionProbeError("refusing to clean staging outside destination parent")
    if not isinstance(staging_identity, _ComponentIdentity):
        raise AdmissionProbeError("staging cleanup identity is missing")
    if not isinstance(expected_files, dict) or any(
        not isinstance(name, str)
        or not name
        or name in {".", ".."}
        or Path(name).name != name
        or not isinstance(identity, _FileIdentity)
        for name, identity in expected_files.items()
    ):
        raise AdmissionProbeError("staging cleanup file identities are invalid")
    if planned_files is None:
        planned_files = frozenset(expected_files)
    if (
        not isinstance(planned_files, frozenset)
        or not set(expected_files).issubset(planned_files)
        or any(
            not isinstance(name, str)
            or not name
            or name in {".", ".."}
            or Path(name).name != name
            for name in planned_files
        )
    ):
        raise AdmissionProbeError("staging cleanup planned file receipts are invalid")
    _require_components_unchanged(
        parent,
        parent_components,
        label="destination parent before staging cleanup",
    )
    directory_fd: int | None = None
    try:
        directory_fd = _open_windows_delete_fd(
            staging, label="staging directory", directory=True
        )
    except AdmissionProbeError:
        if not _lexists(staging):
            return
        raise
    except BaseException as error:
        if directory_fd is not None:
            close_error = _release_exact_fd(
                directory_fd,
                staging_identity,
                label="staging directory",
            )
            if close_error is not None:
                add_note = getattr(error, "add_note", None)
                if callable(add_note):
                    add_note(
                        "staging directory acquisition cleanup also failed: "
                        f"{close_error}"
                    )
        raise
    cleanup_error: BaseException | None = None
    try:
        raw = os.fstat(directory_fd)
        if (
            _is_reparse_stat(raw)
            or not stat.S_ISDIR(raw.st_mode)
            or _component_identity(raw) != staging_identity
        ):
            raise AdmissionProbeError(
                "refusing to clean a replaced/reparse staging directory"
            )
        try:
            actual_names = {entry.name for entry in os.scandir(staging)}
        except OSError as error:
            raise AdmissionProbeError(
                f"staging cleanup enumeration failed: {error}"
            ) from error
        unknown_names = actual_names.difference(planned_files)
        if unknown_names:
            raise AdmissionProbeError(
                "refusing to clean staging with unowned entries: "
                + ", ".join(sorted(unknown_names))
            )
        for name in sorted(actual_names):
            expected_identity = expected_files.get(name)
            if expected_identity is None:
                pending = _stable_file(
                    staging / name,
                    label=f"planned staging file {name}",
                )
                expected_identity = pending.identity
            _delete_exact_regular_file(
                staging / name,
                expected_identity,
                label=f"staging file {name}",
            )
        try:
            remaining = tuple(entry.name for entry in os.scandir(staging))
        except OSError as error:
            raise AdmissionProbeError(
                f"staging cleanup re-enumeration failed: {error}"
            ) from error
        if remaining:
            raise AdmissionProbeError(
                "staging changed during cleanup; refusing recursive deletion"
            )
        _mark_windows_fd_for_delete(directory_fd, label="staging directory")
    except OSError as error:
        cleanup_error = AdmissionProbeError(f"staging cleanup failed: {error}")
        cleanup_error.__cause__ = error
    except BaseException as error:
        cleanup_error = error
    try:
        close_error = _release_exact_fd(
            directory_fd,
            staging_identity,
            label="staging directory",
        )
    except BaseException as error:
        retry_error = _release_exact_fd(
            directory_fd,
            staging_identity,
            label="staging directory",
        )
        close_error = error
        if retry_error is not None:
            add_note = getattr(close_error, "add_note", None)
            if callable(add_note):
                add_note(f"staging release retry also failed: {retry_error}")
    if cleanup_error is not None:
        if close_error is not None:
            add_note = getattr(cleanup_error, "add_note", None)
            if callable(add_note):
                add_note(f"staging directory release also failed: {close_error}")
        raise cleanup_error
    if close_error is not None:
        raise close_error
    if _lexists(staging):
        raise AdmissionProbeError(
            "staging path was replaced or remained after exact deletion"
        )


def _run_live_relation_gate(
    dataset: OsmDataset,
    roots: RootSelection,
    *,
    source_wsl_path: str,
    source_sha256: str,
) -> _RelationGateOutcome:
    probe = _LIVE_CLOSURE_PROBE(
        source_wsl_path=source_wsl_path,
        source_sha256=source_sha256,
    )
    global_missing = probe(roots.relation_ids)
    classification = osm_hydro_source.classify_regional_pilot_closure(
        dataset, roots, global_missing
    )
    predicted_incomplete = tuple(
        item.relation_id for item in classification.incomplete_relations
    )
    audit = audit_predicted_with_cached_global(
        relation_ids=roots.relation_ids,
        predicted_incomplete_ids=predicted_incomplete,
        probe=probe,
    )
    raw: list[RawProcessCapture] = []
    for index, record in enumerate(probe.records):
        for name, process in (
            ("release", record.runtime.release_process),
            ("pre-hash", record.runtime.hash_process),
            ("getid", record.process),
            ("post-hash", record.post_hash_process),
        ):
            raw.append(
                RawProcessCapture(
                    label=f"relation/{index:03d}/{name}", process=process
                )
            )
    return _RelationGateOutcome(classification, audit, tuple(raw))


def _validate_relation_outcome(
    outcome: _RelationGateOutcome,
    roots: RootSelection,
    expectations: _AdmissionExpectations,
) -> tuple[int, ...]:
    if not isinstance(outcome, _RelationGateOutcome):
        raise AdmissionProbeError("relation gate returned unsupported evidence")
    classification = outcome.classification
    audit = outcome.audit
    if (
        not isinstance(classification, RegionalClosureClassification)
        or not isinstance(audit, RelationClosureAudit)
        or not isinstance(outcome.raw_processes, tuple)
    ):
        raise AdmissionProbeError("relation gate evidence types are invalid")
    if classification.complete_way_ids != roots.way_ids:
        raise AdmissionProbeError("relation classification did not retain every way root")
    complete = _strict_ids(
        classification.complete_relation_ids, "complete relation IDs"
    )
    incomplete = tuple(item.relation_id for item in classification.incomplete_relations)
    _strict_ids(incomplete, "source-incomplete relation IDs")
    if (
        len(complete) != expectations.complete_relation_count
        or len(incomplete)
        != expectations.selected_relation_count - expectations.complete_relation_count
        or set(complete).intersection(incomplete)
        or set(complete).union(incomplete) != set(roots.relation_ids)
    ):
        raise AdmissionProbeError("relation complete/incomplete counts or partition mismatch")
    if (
        audit.complete_relation_ids != complete
        or tuple(item.relation_id for item in audit.incomplete_relations) != incomplete
        or audit.global_missing_references != classification.missing_references
    ):
        raise AdmissionProbeError("relation singleton audit differs from classification")
    retained = tuple(
        relation_id for relation_id in roots.relation_ids if relation_id not in set(incomplete)
    )
    expected_batches = (
        roots.relation_ids,
        *((relation_id,) for relation_id in incomplete),
        *((retained,) if retained else ()),
    )
    if audit.probed_batches != expected_batches:
        raise AdmissionProbeError(
            "relation audit did not execute global, singleton, retained batches exactly"
        )
    if len(outcome.raw_processes) != len(expected_batches) * 4:
        raise AdmissionProbeError("relation audit did not retain every raw process")
    if len({item.label for item in outcome.raw_processes}) != len(
        outcome.raw_processes
    ):
        raise AdmissionProbeError("relation audit raw process labels are not unique")
    if incomplete and audit.global_missing_references.count == 0:
        raise AdmissionProbeError("incomplete relation audit has no global missing set")
    for item in audit.incomplete_relations:
        if item.missing_references.count == 0:
            raise AdmissionProbeError(
                f"source-incomplete relation {item.relation_id} has empty evidence"
            )
    return complete


def _missing_document(missing: MissingReferences) -> dict[str, object]:
    return {
        "nodeIds": list(missing.node_ids),
        "relationIds": list(missing.relation_ids),
        "wayIds": list(missing.way_ids),
    }


def _execute_admission(
    *,
    snapshot: _AdmissionSnapshot,
    destination: str | Path,
    expectations: _AdmissionExpectations,
    runner: Callable[[tuple[str, ...]], ProcessEvidence],
    relation_gate: Callable[[OsmDataset, RootSelection], _RelationGateOutcome],
    reverify: Callable[[], _AdmissionSnapshot],
) -> MarylandAdmissionResult:
    """Private bounded seam; the public Maryland entry fixes every dependency."""

    if not isinstance(snapshot, _AdmissionSnapshot):
        raise AdmissionProbeError("admission requires one verified snapshot")
    if not isinstance(expectations, _AdmissionExpectations):
        raise AdmissionProbeError("admission expectations are missing")
    if not callable(relation_gate) or not callable(reverify):
        raise AdmissionProbeError("private admission dependencies are invalid")
    target, target_parent_components = _validated_destination_snapshot(destination)
    parent_lock = _DirectoryIdentityLock(
        target.parent,
        target_parent_components,
    )
    try:
        parent_lock.reacquire()
    except BaseException as error:
        try:
            parent_lock.release()
        except BaseException as release_error:
            add_note = getattr(error, "add_note", None)
            if callable(add_note):
                add_note(f"initial parent lock release also failed: {release_error}")
        raise
    source_guard_fd: int | None = None
    source_guard_identity: _FileIdentity | None = None
    publication_receipt = _PublishedGuardReceipt()

    def release_outer_guards() -> BaseException | None:
        nonlocal source_guard_fd, source_guard_identity
        release_error: BaseException | None = None
        if source_guard_fd is not None:
            fd = source_guard_fd
            try:
                identity = source_guard_identity
                if identity is None:
                    identity = _identity(os.fstat(fd))
                close_error = _release_exact_fd(
                    fd,
                    identity,
                    label="admission source identity lock",
                )
            except BaseException as error:
                try:
                    identity = source_guard_identity
                    if identity is None:
                        identity = _identity(os.fstat(fd))
                    retry_error = _release_exact_fd(
                        fd,
                        identity,
                        label="admission source identity lock",
                    )
                except BaseException as retry_failure:
                    release_error = retry_failure
                else:
                    source_guard_fd = None
                    source_guard_identity = None
                    release_error = error
                    if retry_error is not None:
                        add_note = getattr(release_error, "add_note", None)
                        if callable(add_note):
                            add_note(
                                "source guard release retry also failed: "
                                f"{retry_error}"
                            )
            else:
                source_guard_fd = None
                source_guard_identity = None
                if close_error is not None:
                    release_error = close_error
        try:
            parent_lock.release()
        except BaseException as error:
            if release_error is None:
                release_error = error
        return release_error

    try:
        source_guard_fd = _open_windows_delete_fd(
            snapshot.source_path,
            label="admission source identity lock",
            directory=False,
            delete_access=False,
            read_data=True,
        )
        locked_source = _stable_guarded_file(
            source_guard_fd,
            snapshot.source_path,
            label="admission source identity lock",
        )
        source_guard_identity = locked_source.identity
        if (
            locked_source.bytes != snapshot.source_bytes
            or locked_source.sha256 != snapshot.source_sha256
        ):
            raise AdmissionProbeError(
                "admission source identity lock differs from verified snapshot"
            )
        completed = _execute_admission_with_parent_lock(
            snapshot=snapshot,
            target=target,
            target_parent_components=target_parent_components,
            parent_lock=parent_lock,
            source_guard_fd=source_guard_fd,
            publication_receipt=publication_receipt,
            expectations=expectations,
            runner=runner,
            relation_gate=relation_gate,
            reverify=reverify,
        )
    except BaseException as error:
        rollback_error: BaseException | None = None
        try:
            _abort_published_guard(
                publication_receipt,
                label="failed outer admission publication",
            )
        except BaseException as abort_error:
            rollback_error = abort_error
        release_error = release_outer_guards()
        if rollback_error is not None:
            raise rollback_error from error
        if release_error is not None:
            add_note = getattr(error, "add_note", None)
            if callable(add_note):
                add_note(f"outer admission guard release also failed: {release_error}")
        raise

    result, published_identity = completed
    release_error = release_outer_guards()
    if release_error is not None:
        try:
            _abort_published_guard(
                publication_receipt,
                label="outer admission guard release rollback",
            )
        except AdmissionProbeError as rollback_error:
            if isinstance(release_error, (KeyboardInterrupt, SystemExit)):
                raise rollback_error from release_error
            raise AdmissionProbeError(
                "outer admission guard release failed and exact published "
                f"rollback failed: {rollback_error}"
            ) from release_error
        raise release_error
    _commit_published_guard(publication_receipt)
    return result


def _execute_admission_with_parent_lock(
    *,
    snapshot: _AdmissionSnapshot,
    target: Path,
    target_parent_components: tuple[tuple[Path, _ComponentIdentity], ...],
    parent_lock: _DirectoryIdentityLock,
    source_guard_fd: int,
    publication_receipt: _PublishedGuardReceipt,
    expectations: _AdmissionExpectations,
    runner: Callable[[tuple[str, ...]], ProcessEvidence],
    relation_gate: Callable[[OsmDataset, RootSelection], _RelationGateOutcome],
    reverify: Callable[[], _AdmissionSnapshot],
) -> tuple[MarylandAdmissionResult, _FileIdentity]:
    if (
        not isinstance(publication_receipt, _PublishedGuardReceipt)
        or publication_receipt.fd is not None
        or publication_receipt.identity is not None
        or publication_receipt.path is not None
    ):
        raise AdmissionProbeError("publication guard receipt was not empty")
    source_before = _stable_guarded_file(
        source_guard_fd,
        snapshot.source_path,
        label="admission source",
    )
    if (
        source_before.bytes != snapshot.source_bytes
        or source_before.sha256 != snapshot.source_sha256
    ):
        raise AdmissionProbeError("admission source does not match its verified snapshot")
    try:
        dataset = osm_hydro_source.parse_osm_xml_bytes(
            snapshot.candidate_xml,
            source_label="exact verified Maryland candidate XML",
        )
        if dataset.nodes:
            raise AdmissionProbeError("candidate XML contains reference-only nodes")
        roots = osm_hydro_source.select_roots(dataset)
    except AdmissionProbeError:
        raise
    except Exception as error:
        raise AdmissionProbeError(
            f"candidate XML root derivation failed: {error}"
        ) from error
    root_id_bytes = encode_canonical_id_file(roots.way_ids, roots.relation_ids)
    if (
        len(roots.way_ids) != expectations.selected_way_count
        or len(roots.relation_ids) != expectations.selected_relation_count
        or len(root_id_bytes) != expectations.root_ids_bytes
        or _sha256(root_id_bytes) != expectations.root_ids_sha256
    ):
        raise AdmissionProbeError(
            "candidate XML roots do not match exact count/byte/hash goldens"
        )
    try:
        relation_outcome = relation_gate(dataset, roots)
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as error:
        raise AdmissionProbeError(f"relation closure gate failed: {error}") from error
    complete_relations = _validate_relation_outcome(
        relation_outcome, roots, expectations
    )
    direct_way_ids = encode_canonical_id_file(roots.way_ids, ())
    admitted_ids = encode_canonical_id_file(roots.way_ids, complete_relations)
    parsed_admitted = parse_canonical_id_file(admitted_ids)
    if (
        parsed_admitted.way_ids != roots.way_ids
        or parsed_admitted.relation_ids != complete_relations
    ):
        raise AdmissionProbeError("admitted ID derivation is not canonical")

    hash_argv = _hash_argv(snapshot.source_wsl_path)
    pre_hash = _run_exact_process(runner, hash_argv, "pre-hash")
    _validate_hash_process(pre_hash, snapshot)
    captures: list[RawProcessCapture] = list(relation_outcome.raw_processes)
    captures.append(RawProcessCapture("admission/pre-hash", pre_hash))

    staging: Path | None = None
    staging_identity: _ComponentIdentity | None = None
    staging_files: dict[str, _FileIdentity] = {}
    staging_planned_files = frozenset(
        {"direct-way-ids.txt", "admitted-ids.txt", "admitted.osm.pbf"}
    )
    staging_creation_receipted = False
    staging_guard_fd: int | None = None
    way_id_guard_fd: int | None = None
    admitted_id_guard_fd: int | None = None
    check_output_guard_fd: int | None = None
    handoff_barrier_fd: int | None = None
    published = False
    published_identity: _FileIdentity | None = None
    published_guard_fd: int | None = None
    published_guard_identity: _FileIdentity | None = None
    result: MarylandAdmissionResult | None = None

    def release_owned_guard(
        fd: int,
        *,
        label: str,
        directory: bool = False,
    ) -> BaseException | None:
        def retained_identity() -> _FileIdentity | _ComponentIdentity:
            raw = os.fstat(fd)
            return _component_identity(raw) if directory else _identity(raw)

        try:
            identity = retained_identity()
            return _release_exact_fd(fd, identity, label=label)
        except BaseException as error:
            try:
                identity = retained_identity()
                retry_error = _release_exact_fd(fd, identity, label=label)
            except BaseException as retry_failure:
                raise AdmissionProbeError(
                    f"{label} release retry failed"
                ) from retry_failure
            if retry_error is not None:
                add_note = getattr(error, "add_note", None)
                if callable(add_note):
                    add_note(f"{label} release retry also failed: {retry_error}")
            return error

    def retry_owned_guard_release(
        fd: int,
        error: BaseException,
        *,
        label: str,
        directory: bool = False,
    ) -> BaseException:
        retry_error = release_owned_guard(
            fd,
            label=label,
            directory=directory,
        )
        if retry_error is not None:
            add_note = getattr(error, "add_note", None)
            if callable(add_note):
                add_note(f"{label} outer release retry also failed: {retry_error}")
        return error

    def discard_guarded_output() -> None:
        nonlocal handoff_barrier_fd, published_guard_fd, published_guard_identity
        barrier_release_error: BaseException | None = None
        if handoff_barrier_fd is not None:
            barrier_fd = handoff_barrier_fd
            try:
                barrier_release_error = release_owned_guard(
                    barrier_fd,
                    label="publication handoff read barrier",
                )
            except BaseException:
                raise
            else:
                handoff_barrier_fd = None
        if published_guard_fd is not None and published_guard_identity is not None:
            if publication_receipt.fd is None:
                publication_receipt.fd = published_guard_fd
            elif publication_receipt.fd != published_guard_fd:
                raise AdmissionProbeError("publication guard fd receipt changed")
            if publication_receipt.identity is None:
                publication_receipt.identity = published_guard_identity
            elif publication_receipt.identity != published_guard_identity:
                raise AdmissionProbeError("publication guard identity receipt changed")
            if publication_receipt.path is None:
                publication_receipt.path = output_path
            elif publication_receipt.path not in {output_path, target}:
                raise AdmissionProbeError("publication guard path receipt changed")
        if publication_receipt.fd is None and publication_receipt.identity is None:
            published_guard_fd = None
            published_guard_identity = None
            return
        try:
            _abort_published_guard(
                publication_receipt,
                label="guarded admitted output",
            )
        finally:
            published_guard_fd = publication_receipt.fd
            published_guard_identity = publication_receipt.identity
        if barrier_release_error is not None:
            raise barrier_release_error

    try:
        _require_components_unchanged(
            target.parent,
            target_parent_components,
            label="destination parent before staging",
        )
        for _attempt in range(128):
            staging = target.parent / (
                f".{target.name}.{secrets.token_hex(16)}.admission-staging"
            )
            staging_creation_receipted = True
            try:
                os.mkdir(staging, 0o700)
            except FileExistsError:
                staging_creation_receipted = False
                staging = None
                continue
            except OSError as error:
                staging_creation_receipted = False
                raise AdmissionProbeError(
                    f"admission staging could not be created: {error}"
                ) from error
            break
        else:
            staging_creation_receipted = False
            staging = None
            raise AdmissionProbeError("admission staging name allocation was exhausted")
        staging_components = _component_snapshot(staging)
        staging_identity = staging_components[-1][1]
        staging_guard_fd = _open_windows_delete_fd(
            staging,
            label="staging directory identity lock",
            directory=True,
        )
        staging_guard_raw = os.fstat(staging_guard_fd)
        if (
            _is_reparse_stat(staging_guard_raw)
            or not stat.S_ISDIR(staging_guard_raw.st_mode)
            or _component_identity(staging_guard_raw) != staging_identity
        ):
            raise AdmissionProbeError(
                "staging directory changed before its identity lock was acquired"
            )
        way_id_path = staging / "direct-way-ids.txt"
        admitted_id_path = staging / "admitted-ids.txt"
        output_path = staging / "admitted.osm.pbf"
        _write_exclusive_fsync(way_id_path, direct_way_ids, staging_files)
        _write_exclusive_fsync(admitted_id_path, admitted_ids, staging_files)
        way_id_file = _stable_file(
            way_id_path,
            label="direct-way ID file",
            capture=True,
            maximum_capture_bytes=_MAX_ID_FILE_BYTES,
        )
        staging_files[way_id_path.name] = way_id_file.identity
        if way_id_file.content != direct_way_ids:
            raise AdmissionProbeError("direct-way ID file changed after write")
        admitted_id_file = _stable_file(
            admitted_id_path,
            label="admitted ID file",
            capture=True,
            maximum_capture_bytes=_MAX_ID_FILE_BYTES,
        )
        staging_files[admitted_id_path.name] = admitted_id_file.identity
        if admitted_id_file.content != admitted_ids:
            raise AdmissionProbeError("admitted ID file changed after write")
        way_id_guard_fd = _open_windows_delete_fd(
            way_id_path,
            label="direct-way ID consumption lock",
            directory=False,
            delete_access=False,
            read_data=True,
        )
        admitted_id_guard_fd = _open_windows_delete_fd(
            admitted_id_path,
            label="admitted ID consumption lock",
            directory=False,
            delete_access=False,
            read_data=True,
        )
        for fd, path, expected, retained, label in (
            (
                way_id_guard_fd,
                way_id_path,
                direct_way_ids,
                way_id_file.identity,
                "direct-way ID consumption lock",
            ),
            (
                admitted_id_guard_fd,
                admitted_id_path,
                admitted_ids,
                admitted_id_file.identity,
                "admitted ID consumption lock",
            ),
        ):
            locked = _stable_guarded_file(fd, path, label=label)
            if (
                not _same_retained_file_object(locked.identity, retained)
                or locked.bytes != len(expected)
                or locked.sha256 != _sha256(expected)
            ):
                raise AdmissionProbeError(f"{label} differs from canonical bytes")
        if _lexists(output_path):
            raise AdmissionProbeError("admitted staging output already exists")

        way_id_wsl = _windows_path_to_wsl(way_id_path, must_exist=True)
        admitted_id_wsl = _windows_path_to_wsl(admitted_id_path, must_exist=True)
        output_wsl = _windows_path_to_wsl(output_path, must_exist=False)

        direct_argv = _getid_argv(
            source_wsl_path=snapshot.source_wsl_path,
            output_wsl_path="/dev/null",
            id_file_wsl_path=way_id_wsl,
            overwrite=True,
            fsync=False,
        )
        direct = _run_exact_process(runner, direct_argv, "direct-way getid")
        parse_id_file_getid_result(
            returncode=direct.returncode,
            stdout=direct.stdout,
            stderr=direct.stderr,
            source_wsl_path=snapshot.source_wsl_path,
            output_wsl_path="/dev/null",
            expected_way_count=len(roots.way_ids),
            expected_relation_count=0,
            overwrite=True,
            fsync=False,
        )
        direct_id_after = _stable_guarded_file(
            way_id_guard_fd,
            way_id_path,
            label="post-getid direct-way ID file",
        )
        if (
            not _same_retained_file_object(
                direct_id_after.identity, way_id_file.identity
            )
            or direct_id_after.bytes != len(direct_way_ids)
            or direct_id_after.sha256 != _sha256(direct_way_ids)
        ):
            raise AdmissionProbeError("direct-way ID file changed during getid")
        captures.append(RawProcessCapture("admission/direct-ways-getid", direct))

        admitted_argv = _getid_argv(
            source_wsl_path=snapshot.source_wsl_path,
            output_wsl_path=output_wsl,
            id_file_wsl_path=admitted_id_wsl,
            overwrite=False,
            fsync=True,
        )
        try:
            admitted = _run_exact_process(runner, admitted_argv, "admitted getid")
        except BaseException:
            if _lexists(output_path):
                if staging_guard_fd is None:
                    raise AdmissionProbeError(
                        "admitted output appeared without a staging identity lock"
                    )
                current_staging = os.fstat(staging_guard_fd)
                if (
                    _is_reparse_stat(current_staging)
                    or not stat.S_ISDIR(current_staging.st_mode)
                    or _component_identity(current_staging) != staging_identity
                ):
                    raise AdmissionProbeError(
                        "staging identity changed during interrupted admitted getid"
                    )
                _require_components_unchanged(
                    staging,
                    staging_components,
                    label="staging during interrupted admitted getid",
                )
                recovered_output = _stable_file(
                    output_path,
                    label="interrupted admitted getid staging output",
                )
                staging_files[output_path.name] = recovered_output.identity
            raise
        if _lexists(output_path):
            unverified_output = _stable_file(
                output_path, label="unverified admitted getid staging output"
            )
            staging_files[output_path.name] = unverified_output.identity
        parse_id_file_getid_result(
            returncode=admitted.returncode,
            stdout=admitted.stdout,
            stderr=admitted.stderr,
            source_wsl_path=snapshot.source_wsl_path,
            output_wsl_path=output_wsl,
            expected_way_count=len(roots.way_ids),
            expected_relation_count=len(complete_relations),
            overwrite=False,
            fsync=True,
        )
        admitted_id_after = _stable_guarded_file(
            admitted_id_guard_fd,
            admitted_id_path,
            label="post-getid admitted ID file",
        )
        if (
            not _same_retained_file_object(
                admitted_id_after.identity, admitted_id_file.identity
            )
            or admitted_id_after.bytes != len(admitted_ids)
            or admitted_id_after.sha256 != _sha256(admitted_ids)
        ):
            raise AdmissionProbeError("admitted ID file changed during getid")
        captures.append(RawProcessCapture("admission/admitted-getid", admitted))
        if not _lexists(output_path):
            raise AdmissionProbeError("admitted getid left no staging output")
        output_after_getid = _stable_file(
            output_path, label="admitted getid staging output"
        )
        if not _same_retained_file_object(
            output_after_getid.identity, staging_files[output_path.name]
        ):
            raise AdmissionProbeError("admitted staging output identity changed")
        if output_after_getid.bytes <= 0:
            raise AdmissionProbeError("admitted staging output is empty")
        check_output_guard_fd = _open_windows_delete_fd(
            output_path,
            label="check-refs output consumption lock",
            directory=False,
            delete_access=False,
            read_data=True,
        )
        guarded_output = _stable_guarded_file(
            check_output_guard_fd,
            output_path,
            label="check-refs output consumption lock",
        )
        if guarded_output != output_after_getid:
            raise AdmissionProbeError(
                "check-refs output lock differs from admitted getid output"
            )

        check_argv = _check_refs_argv(output_wsl)
        check = _run_exact_process(runner, check_argv, "check-refs")
        summary = parse_check_refs_result(
            returncode=check.returncode,
            stdout=check.stdout,
            stderr=check.stderr,
            input_wsl_path=output_wsl,
        )
        captures.append(RawProcessCapture("admission/check-refs", check))
        if (
            summary.node_count <= 0
            or summary.way_count < len(roots.way_ids)
            or summary.relation_count < len(complete_relations)
        ):
            raise AdmissionProbeError("check-refs object counts cannot contain all admitted roots")
        output_before = _stable_guarded_file(
            check_output_guard_fd,
            output_path,
            label="admitted staging output",
        )
        if output_before != output_after_getid:
            raise AdmissionProbeError("admitted output changed during check-refs")

        post_hash = _run_exact_process(runner, hash_argv, "post-hash")
        _validate_hash_process(post_hash, snapshot)
        captures.append(RawProcessCapture("admission/post-hash", post_hash))
        try:
            after_snapshot = reverify()
        except (KeyboardInterrupt, SystemExit):
            raise
        except Exception as error:
            raise AdmissionProbeError(f"exact locks changed during admission: {error}") from error
        if after_snapshot != snapshot:
            raise AdmissionProbeError(
                "exact source/candidate/runtime locks changed during admission"
            )
        source_after = _stable_guarded_file(
            source_guard_fd,
            snapshot.source_path,
            label="post-admission source",
        )
        if source_after != source_before:
            raise AdmissionProbeError("source changed during admission")
        output_after = _stable_guarded_file(
            check_output_guard_fd,
            output_path,
            label="post-attestation output",
        )
        if output_after != output_before:
            raise AdmissionProbeError("admitted output changed after check-refs")

        incomplete_ids = tuple(
            item.relation_id
            for item in relation_outcome.classification.incomplete_relations
        )
        semantic = _canonical_json_bytes(
            {
                "candidate": {
                    "pbfBytes": snapshot.candidate_pbf_bytes,
                    "pbfSha256": snapshot.candidate_pbf_sha256,
                    "xmlBytes": len(snapshot.candidate_xml),
                    "xmlSha256": snapshot.candidate_xml_sha256,
                },
                "checkRefs": {
                    "missingReferences": {
                        "changesets": summary.missing_changeset_references,
                        "nodes": summary.missing_node_references,
                        "relations": summary.missing_relation_references,
                        "ways": summary.missing_way_references,
                    },
                    "objectCounts": {
                        "nodes": summary.node_count,
                        "relations": summary.relation_count,
                        "ways": summary.way_count,
                    },
                },
                "counts": {
                    "admittedRelations": len(complete_relations),
                    "admittedWays": len(roots.way_ids),
                    "selectedRelations": len(roots.relation_ids),
                    "selectedWays": len(roots.way_ids),
                    "sourceIncompleteRelations": len(incomplete_ids),
                },
                "directWayClosure": {
                    "complete": True,
                    "idFileBytes": len(direct_way_ids),
                    "idFileSha256": _sha256(direct_way_ids),
                },
                "identity": {
                    "policySha256": snapshot.policy_sha256,
                    "profile": MARYLAND_REGIONAL_PROFILE,
                    "runtimeIdentitySha256": snapshot.runtime_identity_sha256,
                    "selectorSha256": snapshot.selector_sha256,
                    "sourceBytes": snapshot.source_bytes,
                    "sourceSha256": snapshot.source_sha256,
                },
                "output": {
                    "bytes": output_after.bytes,
                    "sha256": output_after.sha256,
                },
                "relationClosure": {
                    "completeRelationIds": list(complete_relations),
                    "globalMissingReferences": _missing_document(
                        relation_outcome.audit.global_missing_references
                    ),
                    "probeBatchCount": len(relation_outcome.audit.probed_batches),
                    "sourceIncompleteRelationIds": list(incomplete_ids),
                },
                "rootIds": {
                    "admittedBytes": len(admitted_ids),
                    "admittedSha256": _sha256(admitted_ids),
                    "selectedBytes": len(root_id_bytes),
                    "selectedSha256": _sha256(root_id_bytes),
                },
                "schema": "flight-alert-exp8-osm-maryland-admission-v1",
            }
        )
        if _lexists(target):
            raise AdmissionProbeError(
                f"admission destination already exists before publication: {target}"
            )
        _require_components_unchanged(
            target.parent,
            target_parent_components,
            label="destination parent before publication",
        )
        handoff_barrier_fd = _open_windows_delete_fd(
            output_path,
            label="publication handoff read barrier",
            directory=False,
            delete_access=False,
            share_delete=True,
            read_data=True,
        )
        handoff_file = _stable_guarded_file(
            handoff_barrier_fd,
            output_path,
            label="publication handoff read barrier",
        )
        if handoff_file != output_after:
            raise AdmissionProbeError(
                "publication handoff barrier differs from admitted output"
            )
        check_fd = check_output_guard_fd
        if check_fd is None:
            raise AdmissionProbeError("check-refs guard disappeared before handoff")
        check_close_error = _close_fd_with_single_retry(
            check_fd,
            output_after.identity,
            label="check-refs guard handoff",
        )
        check_output_guard_fd = None
        if check_close_error is not None:
            raise check_close_error
        published_guard_identity = output_after.identity
        published_guard_fd = _open_windows_delete_fd(
            output_path,
            label="admitted output publication guard",
            directory=False,
            read_data=True,
        )
        publication_receipt.fd = published_guard_fd
        publication_receipt.identity = published_guard_identity
        publication_receipt.path = output_path
        _require_open_regular_file_identity(
            published_guard_fd,
            published_guard_identity,
            label="admitted output publication guard",
        )
        barrier_fd = handoff_barrier_fd
        barrier_close_error = _close_fd_with_single_retry(
            barrier_fd,
            output_after.identity,
            label="publication handoff read barrier",
        )
        handoff_barrier_fd = None
        if barrier_close_error is not None:
            raise barrier_close_error
        parent_lock.release()
        try:
            try:
                _publish_no_replace(
                    output_path,
                    target,
                    guard_fd=published_guard_fd,
                )
            finally:
                parent_lock.reacquire()
        finally:
            if _lexists(target):
                try:
                    published_raw = os.lstat(target)
                except OSError:
                    published_raw = None
                if (
                    published_raw is not None
                    and not _is_reparse_stat(published_raw)
                    and stat.S_ISREG(published_raw.st_mode)
                    and _same_file_object(published_raw, output_after.identity)
                ):
                    published = True
                    published_identity = output_after.identity
                    publication_receipt.path = target
        if not published:
            raise AdmissionProbeError(
                "atomic publication did not commit the exact guarded output"
            )
        published_file = _stable_guarded_file(
            published_guard_fd,
            target,
            label="published admitted output",
        )
        if (
            published_file.bytes != output_after.bytes
            or published_file.sha256 != output_after.sha256
            or published_file.identity != output_after.identity
        ):
            raise AdmissionProbeError("published output readback differs from staging")
        _require_components_unchanged(
            target.parent,
            target_parent_components,
            label="destination parent after publication",
        )
        result = _sealed_result(
            destination=published_file.path,
            output_bytes=published_file.bytes,
            output_sha256=published_file.sha256,
            direct_way_id_file_bytes=direct_way_ids,
            admitted_id_file_bytes=admitted_ids,
            semantic_evidence=semantic,
            raw_processes=tuple(captures),
        )
    except BaseException as error:
        if published_guard_fd is not None:
            try:
                discard_guarded_output()
            except AdmissionProbeError as rollback_error:
                if isinstance(error, (KeyboardInterrupt, SystemExit)):
                    raise rollback_error from error
                raise AdmissionProbeError(
                    f"admission failed and exact guarded rollback failed: "
                    f"{rollback_error}"
                ) from error
        elif published_identity is not None:
            try:
                _rollback_published(target, published_identity)
            except AdmissionProbeError as rollback_error:
                if isinstance(error, (KeyboardInterrupt, SystemExit)):
                    raise rollback_error from error
                raise AdmissionProbeError(
                    f"admission failed and exact published rollback failed: "
                    f"{rollback_error}"
                ) from error
        raise
    finally:
        guard_release_error: BaseException | None = None
        if way_id_guard_fd is not None:
            try:
                release_error = release_owned_guard(
                    way_id_guard_fd,
                    label="direct-way ID consumption lock",
                )
            except BaseException as error:
                try:
                    release_error = retry_owned_guard_release(
                        way_id_guard_fd,
                        error,
                        label="direct-way ID consumption lock",
                    )
                except BaseException as retry_failure:
                    guard_release_error = retry_failure
                else:
                    way_id_guard_fd = None
                    guard_release_error = release_error
            else:
                way_id_guard_fd = None
                if release_error is not None:
                    guard_release_error = release_error
        if admitted_id_guard_fd is not None:
            try:
                release_error = release_owned_guard(
                    admitted_id_guard_fd,
                    label="admitted ID consumption lock",
                )
            except BaseException as error:
                try:
                    release_error = retry_owned_guard_release(
                        admitted_id_guard_fd,
                        error,
                        label="admitted ID consumption lock",
                    )
                except BaseException as retry_failure:
                    release_error = retry_failure
                else:
                    admitted_id_guard_fd = None
                if guard_release_error is None:
                    guard_release_error = release_error
            else:
                admitted_id_guard_fd = None
                if guard_release_error is None and release_error is not None:
                    guard_release_error = release_error
        if handoff_barrier_fd is not None:
            try:
                release_error = release_owned_guard(
                    handoff_barrier_fd,
                    label="publication handoff read barrier",
                )
            except BaseException as error:
                try:
                    release_error = retry_owned_guard_release(
                        handoff_barrier_fd,
                        error,
                        label="publication handoff read barrier",
                    )
                except BaseException as retry_failure:
                    release_error = retry_failure
                else:
                    handoff_barrier_fd = None
                if guard_release_error is None:
                    guard_release_error = release_error
            else:
                handoff_barrier_fd = None
                if guard_release_error is None and release_error is not None:
                    guard_release_error = release_error
        if check_output_guard_fd is not None:
            try:
                release_error = release_owned_guard(
                    check_output_guard_fd,
                    label="check-refs output consumption lock",
                )
            except BaseException as error:
                try:
                    release_error = retry_owned_guard_release(
                        check_output_guard_fd,
                        error,
                        label="check-refs output consumption lock",
                    )
                except BaseException as retry_failure:
                    release_error = retry_failure
                else:
                    check_output_guard_fd = None
                if guard_release_error is None:
                    guard_release_error = release_error
            else:
                check_output_guard_fd = None
                if guard_release_error is None and release_error is not None:
                    guard_release_error = release_error
        if staging_guard_fd is not None:
            try:
                release_error = release_owned_guard(
                    staging_guard_fd,
                    label="staging directory identity lock",
                    directory=True,
                )
            except BaseException as error:
                try:
                    release_error = retry_owned_guard_release(
                        staging_guard_fd,
                        error,
                        label="staging directory identity lock",
                        directory=True,
                    )
                except BaseException as retry_failure:
                    release_error = retry_failure
                else:
                    staging_guard_fd = None
                if guard_release_error is None:
                    guard_release_error = release_error
            else:
                staging_guard_fd = None
                if guard_release_error is None and release_error is not None:
                    guard_release_error = release_error
        if (
            staging is not None
            and staging_identity is None
            and staging_creation_receipted
            and _lexists(staging)
        ):
            try:
                _require_components_unchanged(
                    target.parent,
                    target_parent_components,
                    label="destination parent during staging receipt recovery",
                )
                recovered_staging = os.lstat(staging)
                if (
                    _is_reparse_stat(recovered_staging)
                    or not stat.S_ISDIR(recovered_staging.st_mode)
                ):
                    raise AdmissionProbeError(
                        "receipted staging path is not a non-reparse directory"
                    )
                staging_identity = _component_identity(recovered_staging)
            except BaseException as error:
                if guard_release_error is None:
                    guard_release_error = error
        if staging is not None and staging_identity is not None:
            try:
                _cleanup_staging(
                    staging,
                    target.parent,
                    target_parent_components,
                    staging_identity,
                    staging_files,
                    staging_planned_files,
                )
            except BaseException as error:
                if guard_release_error is None:
                    guard_release_error = error
        if guard_release_error is not None:
            try:
                if published_guard_fd is not None:
                    discard_guarded_output()
                elif published and published_identity is not None:
                    _rollback_published(target, published_identity)
            except AdmissionProbeError as rollback_error:
                if isinstance(guard_release_error, (KeyboardInterrupt, SystemExit)):
                    raise rollback_error from guard_release_error
                raise AdmissionProbeError(
                    "guard release/staging cleanup failed and exact published "
                    f"rollback failed: {rollback_error}"
                ) from guard_release_error
            raise guard_release_error
    try:
        if (
            result is None
            or published_identity is None
            or published_guard_fd is None
            or published_guard_identity != published_identity
            or publication_receipt.fd != published_guard_fd
            or publication_receipt.identity != published_identity
            or publication_receipt.path != target
        ):
            raise AdmissionProbeError(
                "admission completed without one retained published result"
            )
        _require_open_regular_file_identity(
            published_guard_fd,
            published_identity,
            label="final published output",
        )
        final_file = _stable_guarded_file(
            published_guard_fd,
            target,
            label="final published output",
        )
        if (
            final_file.path != result.destination
            or final_file.bytes != result.output_bytes
            or final_file.sha256 != result.output_sha256
            or final_file.identity != published_identity
        ):
            raise AdmissionProbeError(
                "final published output differs after staging cleanup"
            )
        _require_components_unchanged(
            target.parent,
            target_parent_components,
            label="destination parent at final publication return",
        )
    except BaseException as error:
        try:
            discard_guarded_output()
        except AdmissionProbeError as rollback_error:
            if isinstance(error, (KeyboardInterrupt, SystemExit)):
                raise rollback_error from error
            raise AdmissionProbeError(
                "final publication proof failed and exact rollback failed: "
                f"{rollback_error}"
            ) from error
        raise
    return result, published_identity


def _runtime_identity_document(local: object, wsl: object) -> dict[str, object]:
    return {
        "local": {
            "policySha256": getattr(local, "policy_sha256"),
            "pythonCacheTag": getattr(local, "python_cache_tag"),
            "pythonDependencies": [
                {
                    "bytes": item.bytes,
                    "logicalName": item.logical_name,
                    "sha256": item.sha256,
                }
                for item in getattr(local, "python_dependencies")
            ],
            "pythonExecutableSha256": getattr(local, "python_executable_sha256"),
            "pythonFlags": [
                [name, value]
                for name, value in getattr(local, "python_flags")
            ],
            "pythonImplementation": getattr(local, "python_implementation"),
            "pythonPlatform": getattr(local, "python_platform"),
            "pythonVersion": getattr(local, "python_version"),
            "selectorCallableCodeSha256": getattr(
                local, "selector_callable_code_sha256"
            ),
            "selectorSha256": getattr(local, "selector_sha256"),
        },
        "wsl": {
            "architecture": getattr(wsl, "architecture"),
            "boostDebSha256": getattr(wsl, "boost_deb_sha256"),
            "boostLibrarySha256": getattr(wsl, "boost_library_sha256"),
            "commands": [list(argv) for argv in getattr(wsl, "command_argv")],
            "distribution": getattr(wsl, "ubuntu_distribution"),
            "kernel": getattr(wsl, "kernel"),
            "ldd": [
                {
                    "resolvedPath": item.resolved_path,
                    "sha256": item.sha256,
                    "soname": item.soname,
                }
                for item in getattr(wsl, "ldd_inventory")
            ],
            "libosmiumVersion": getattr(wsl, "libosmium_version"),
            "locale": getattr(wsl, "locale"),
            "osmiumBinarySha256": getattr(wsl, "osmium_binary_sha256"),
            "osmiumDebSha256": getattr(wsl, "osmium_deb_sha256"),
            "osmiumVersion": getattr(wsl, "osmium_version"),
            "release": getattr(wsl, "ubuntu_release"),
            "version": getattr(wsl, "wsl_version"),
        },
    }


def _require_matching_provenance_goldens(provenance: object) -> None:
    expected = {
        "MARYLAND_ROOT_WAY_COUNT": MARYLAND_SELECTED_WAY_COUNT,
        "MARYLAND_ROOT_RELATION_COUNT": MARYLAND_SELECTED_RELATION_COUNT,
        "MARYLAND_ROOT_IDS_BYTES": MARYLAND_ROOT_IDS_BYTES,
        "MARYLAND_ROOT_IDS_SHA256": MARYLAND_ROOT_IDS_SHA256,
    }
    try:
        actual = {name: getattr(provenance, name) for name in expected}
    except AttributeError as error:
        raise AdmissionProbeError(
            "provenance root golden is unavailable"
        ) from error
    if actual != expected:
        raise AdmissionProbeError(
            "provenance root goldens do not match admission goldens"
        )


def _verify_live_snapshot(
    source_path: str | Path,
    candidate_pbf_path: str | Path,
    candidate_xml_path: str | Path,
) -> _AdmissionSnapshot:
    # The pinned provenance module is a live-only dependency. Keeping this import
    # inside the live verifier lets bounded parser/transaction tests run without
    # pretending that an unpinned interpreter can attest Maryland.
    import tools.experiment8.osm_pilot_provenance as osm_pilot_provenance

    _require_matching_provenance_goldens(osm_pilot_provenance)
    source_input = Path(source_path)
    candidate_pbf_input = Path(candidate_pbf_path)
    candidate_xml_input = Path(candidate_xml_path)
    source_file = _stable_file(source_input, label="locked Maryland source")
    candidate_pbf = _stable_file(candidate_pbf_input, label="locked candidate PBF")
    candidate_xml = _stable_file(
        candidate_xml_input,
        label="locked candidate XML",
        capture=True,
        maximum_capture_bytes=64 * 1024 * 1024,
    )
    if len({source_file.path, candidate_pbf.path, candidate_xml.path}) != 3:
        raise AdmissionProbeError("source and candidate paths must be distinct files")
    try:
        local = osm_pilot_provenance.verify_local_code_runtime()
        source = osm_pilot_provenance.verify_maryland_source_file(source_file.path)
        candidates = osm_pilot_provenance.verify_candidate_files(
            candidate_pbf.path, candidate_xml.path
        )
        wsl = osm_pilot_provenance.attest_live_wsl_runtime()
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as error:
        raise AdmissionProbeError(
            f"live provenance/runtime verification failed: {error}"
        ) from error
    if (
        source.bytes != source_file.bytes
        or source.sha256 != source_file.sha256
        or source.sha256 != MARYLAND_SOURCE_SHA256
        or candidates.pbf_bytes != candidate_pbf.bytes
        or candidates.pbf_sha256 != candidate_pbf.sha256
        or candidates.xml_bytes != candidate_xml.bytes
        or candidates.xml_sha256 != candidate_xml.sha256
        or candidate_xml.content is None
    ):
        raise AdmissionProbeError("public provenance evidence differs from stable file reads")
    policy_bytes = osm_hydro_source.canonical_policy_bytes()
    if (
        type(policy_bytes) is not bytes
        or _sha256(policy_bytes) != osm_hydro_source.POLICY_SHA256
        or local.policy_sha256 != osm_hydro_source.POLICY_SHA256
    ):
        raise AdmissionProbeError("live selector policy does not match the exact lock")
    runtime_identity_sha256 = _sha256(
        _canonical_json_bytes(_runtime_identity_document(local, wsl))
    )
    return _AdmissionSnapshot(
        source_path=source_file.path,
        source_wsl_path=_windows_path_to_wsl(source_file.path, must_exist=True),
        source_bytes=source_file.bytes,
        source_sha256=source_file.sha256,
        candidate_pbf_bytes=candidate_pbf.bytes,
        candidate_pbf_sha256=candidate_pbf.sha256,
        candidate_xml=candidate_xml.content,
        candidate_xml_sha256=candidate_xml.sha256,
        selector_sha256=local.selector_sha256,
        policy_sha256=local.policy_sha256,
        runtime_identity_sha256=runtime_identity_sha256,
    )


_MARYLAND_EXPECTATIONS = _AdmissionExpectations(
    selected_way_count=MARYLAND_SELECTED_WAY_COUNT,
    selected_relation_count=MARYLAND_SELECTED_RELATION_COUNT,
    complete_relation_count=MARYLAND_COMPLETE_RELATION_COUNT,
    root_ids_bytes=MARYLAND_ROOT_IDS_BYTES,
    root_ids_sha256=MARYLAND_ROOT_IDS_SHA256,
)
_LIVE_PROCESS_RUNNER = run_bounded_process
_LIVE_CLOSURE_PROBE = PinnedOsmiumClosureProbe
_LIVE_RELATION_GATE = _run_live_relation_gate
_LIVE_VERIFY_SNAPSHOT = _verify_live_snapshot
_LIVE_EXECUTOR = _execute_admission


def run_maryland_admission(
    source_path: str | Path,
    candidate_pbf_path: str | Path,
    candidate_xml_path: str | Path,
    destination: str | Path,
) -> MarylandAdmissionResult:
    """Run the exact live Maryland admission gate and publish one new PBF."""

    _validated_destination(destination)
    snapshot = _LIVE_VERIFY_SNAPSHOT(
        source_path, candidate_pbf_path, candidate_xml_path
    )

    def relation_gate(
        dataset: OsmDataset, roots: RootSelection
    ) -> _RelationGateOutcome:
        return _LIVE_RELATION_GATE(
            dataset,
            roots,
            source_wsl_path=snapshot.source_wsl_path,
            source_sha256=snapshot.source_sha256,
        )

    return _LIVE_EXECUTOR(
        snapshot=snapshot,
        destination=destination,
        expectations=_MARYLAND_EXPECTATIONS,
        runner=_LIVE_PROCESS_RUNNER,
        relation_gate=relation_gate,
        reverify=lambda: _LIVE_VERIFY_SNAPSHOT(
            source_path, candidate_pbf_path, candidate_xml_path
        ),
    )


__all__ = [
    "ADMISSION_GENERATOR",
    "AdmissionProbeError",
    "CheckRefsSummary",
    "MARYLAND_COMPLETE_RELATION_COUNT",
    "MARYLAND_ROOT_IDS_BYTES",
    "MARYLAND_ROOT_IDS_SHA256",
    "MARYLAND_SELECTED_RELATION_COUNT",
    "MARYLAND_SELECTED_WAY_COUNT",
    "MarylandAdmissionResult",
    "RawProcessCapture",
    "encode_canonical_id_file",
    "parse_canonical_id_file",
    "parse_check_refs_result",
    "parse_id_file_getid_result",
    "run_maryland_admission",
]
