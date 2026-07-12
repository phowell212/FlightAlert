from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path
from typing import Callable, Mapping

from tools.experiment8.osm_pilot_common import (
    ProvenanceVerificationError,
    _VerifiedFile,
    _identity,
    _verify_stable_file,
    canonical_json_bytes,
)


_LOWER_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_DELETE_ACCESS = 0x00010000
_FILE_LIST_DIRECTORY = 0x00000001
_FILE_READ_ATTRIBUTES = 0x00000080
_SYNCHRONIZE_ACCESS = 0x00100000
_FILE_SHARE_READ = 0x00000001
_FILE_SHARE_WRITE = 0x00000002
_FILE_SHARE_DELETE = 0x00000004
_GENERIC_READ = 0x80000000
_GENERIC_WRITE = 0x40000000
_CREATE_NEW = 1
_OPEN_EXISTING = 3
_FILE_FLAG_DELETE_ON_CLOSE = 0x04000000
_FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
_FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
_FILE_DISPOSITION_INFO_CLASS = 4
_PUBLICATION_SCHEMA = "flight-alert-exp8-provenance-publication-journal-v1"
_PUBLICATION_LOCK_NAME = ".flight-alert-exp8-provenance-publication.lock"
_PUBLICATION_JOURNAL_NAMES = (
    ".flight-alert-exp8-provenance-publication.a.json",
    ".flight-alert-exp8-provenance-publication.b.json",
)
_PUBLICATION_JOURNAL_TEMP_NAMES = tuple(
    f"{name}.tmp" for name in _PUBLICATION_JOURNAL_NAMES
)
_PUBLICATION_PHASES = (
    "intent",
    "building",
    "staged",
    "renaming",
    "installed",
    "accepted",
)


def _write_new_file(path: Path, content: bytes) -> tuple[int, int]:
    with path.open("xb") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
        raw = os.fstat(stream.fileno())
        return raw.st_dev, raw.st_ino


def _is_link_or_reparse(path: Path, raw_stat: os.stat_result) -> bool:
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return path.is_symlink() or bool(
        (getattr(raw_stat, "st_file_attributes", 0) or 0) & reparse_flag
    )


def _windows_handle_link_count(handle: int) -> int:
    import ctypes
    from ctypes import wintypes

    class _FileStandardInfo(ctypes.Structure):
        _fields_ = [
            ("allocation", ctypes.c_longlong),
            ("end", ctypes.c_longlong),
            ("links", wintypes.DWORD),
            ("delete_pending", ctypes.c_ubyte),
            ("directory", ctypes.c_ubyte),
        ]

    information = _FileStandardInfo()
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    if not kernel32.GetFileInformationByHandleEx(
        wintypes.HANDLE(handle),
        1,
        ctypes.byref(information),
        ctypes.sizeof(information),
    ):
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "directory snapshot native link-count query failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    return int(information.links)


def _windows_fd_link_count(fd: int) -> int:
    if os.name != "nt":
        return os.fstat(fd).st_nlink
    import msvcrt

    return _windows_handle_link_count(msvcrt.get_osfhandle(fd))


def _journal_created_windows_handle(
    handle: int,
    *,
    directory: bool,
    callback: Callable[[tuple[int, int]], None],
    file_key: tuple[int, int],
) -> None:
    import ctypes
    from ctypes import wintypes

    class _FileDispositionInfo(ctypes.Structure):
        _fields_ = [("delete_file", ctypes.c_ubyte)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    guard = wintypes.HANDLE(handle)
    separate_guard = False
    if not directory:
        reopen_file = kernel32.ReOpenFile
        reopen_file.restype = wintypes.HANDLE
        reopened = reopen_file(guard, _DELETE_ACCESS, 0x7, _FILE_FLAG_OPEN_REPARSE_POINT)
        if reopened == ctypes.c_void_p(-1).value:
            number = ctypes.get_last_error()
            raise ProvenanceVerificationError(
                "publication journal delete guard reopen failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        guard = wintypes.HANDLE(reopened)
        separate_guard = True
    try:
        for delete in (True, False):
            information = _FileDispositionInfo(int(delete))
            if not kernel32.SetFileInformationByHandle(
                guard,
                _FILE_DISPOSITION_INFO_CLASS,
                ctypes.byref(information),
                ctypes.sizeof(information),
            ):
                number = ctypes.get_last_error()
                raise ProvenanceVerificationError(
                    "publication journal delete guard disposition failed: "
                    f"{OSError(number, ctypes.FormatError(number))}"
                )
            if delete:
                callback(file_key)
    finally:
        pending = sys.exception()
        if separate_guard and not kernel32.CloseHandle(guard) and pending is None:
            number = ctypes.get_last_error()
            raise ProvenanceVerificationError(
                "publication journal delete guard close failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )


def _verify_single_link(path: Path, raw_stat: os.stat_result) -> None:
    if raw_stat.st_nlink != 1:
        raise ProvenanceVerificationError(
            "directory snapshot entries must have exactly one link"
        )
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_BINARY", 0))
    except OSError as error:
        raise ProvenanceVerificationError(
            f"directory snapshot link-count handle is unavailable: {error}"
        ) from error
    try:
        held_stat = os.fstat(fd)
        if (
            _identity(held_stat) != _identity(raw_stat)
            or held_stat.st_nlink != 1
            or _windows_fd_link_count(fd) != 1
        ):
            raise ProvenanceVerificationError(
                "directory snapshot entries must have exactly one link"
            )
    finally:
        os.close(fd)


def _open_held_input_file(file: _VerifiedFile) -> int:
    if os.name != "nt":
        raise ProvenanceVerificationError(
            "coherent final input identity requires the pinned Windows host"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    open_file = kernel32.CreateFileW
    open_file.restype = wintypes.HANDLE
    handle = open_file(str(file.path), 0x80000000, 0x1, None, 3, 0x00200000, None)
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            f"claimed input hold failed for {file.logical_name}: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    try:
        if _windows_handle_link_count(int(handle)) != 1:
            raise ProvenanceVerificationError(
                f"held claimed input {file.logical_name} must have exactly one link"
            )
        return msvcrt.open_osfhandle(
            int(handle), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        kernel32.CloseHandle(wintypes.HANDLE(handle))
        raise


def _verify_held_input_bindings(
    held: list[tuple[_VerifiedFile, int]],
) -> None:
    import msvcrt

    for file, descriptor in held:
        try:
            held_stat, path_stat = os.fstat(descriptor), os.lstat(file.path)
        except OSError as error:
            raise ProvenanceVerificationError(
                f"held claimed input became unavailable: {file.logical_name}: {error}"
            ) from error
        if (
            _is_link_or_reparse(file.path, path_stat)
            or not stat.S_ISREG(held_stat.st_mode)
            or not stat.S_ISREG(path_stat.st_mode)
            or _identity(held_stat) != file.identity
            or _identity(path_stat) != file.identity
            or held_stat.st_nlink != 1
            or path_stat.st_nlink != 1
            or _windows_handle_link_count(msvcrt.get_osfhandle(descriptor)) != 1
        ):
            raise ProvenanceVerificationError(
                "claimed input changed after verification: " + file.logical_name
            )


def _close_held_input_files(held: list[tuple[_VerifiedFile, int]]) -> None:
    first_error: OSError | None = None
    while held:
        _, descriptor = held.pop()
        try:
            os.close(descriptor)
        except OSError as error:
            if first_error is None:
                first_error = error
    if first_error is not None:
        raise ProvenanceVerificationError(
            f"held claimed input close failed: {first_error}"
        ) from first_error


def _hold_verified_input_files(
    files: tuple[_VerifiedFile, ...],
) -> list[tuple[_VerifiedFile, int]]:
    held: list[tuple[_VerifiedFile, int]] = []
    try:
        for file in files:
            held.append((file, _open_held_input_file(file)))
        _verify_held_input_bindings(held)
        return held
    except BaseException as error:
        try:
            _close_held_input_files(held)
        except BaseException as close_error:
            error.add_note(f"held input rollback also failed: {close_error}")
        raise


def _verify_exact_directory_payloads(
    directory: Path,
    expected_payloads: Mapping[str, bytes],
) -> tuple[_VerifiedFile, ...]:
    if not isinstance(directory, Path) or not isinstance(expected_payloads, Mapping):
        raise ProvenanceVerificationError(
            "directory snapshot requires a Path and exact payload mapping"
        )
    expected: dict[str, bytes] = {}
    for logical_name, payload in expected_payloads.items():
        if (
            type(logical_name) is not str
            or not logical_name
            or Path(logical_name).name != logical_name
            or logical_name in {".", ".."}
            or type(payload) is not bytes
        ):
            raise ProvenanceVerificationError(
                "directory snapshot payload names and bytes must be exact"
            )
        expected[logical_name] = payload
    if not expected:
        raise ProvenanceVerificationError(
            "directory snapshot requires at least one expected payload"
        )
    try:
        directory_before_stat = os.lstat(directory)
    except OSError as error:
        raise ProvenanceVerificationError(
            f"directory snapshot root is unavailable: {error}"
        ) from error
    if (
        _is_link_or_reparse(directory, directory_before_stat)
        or not stat.S_ISDIR(directory_before_stat.st_mode)
    ):
        raise ProvenanceVerificationError(
            "directory snapshot root must be a non-reparse directory"
        )
    directory_before = _identity(directory_before_stat)

    def exact_entries() -> dict[str, os.stat_result]:
        try:
            entries = list(os.scandir(directory))
        except OSError as error:
            raise ProvenanceVerificationError(
                f"directory snapshot file set is unreadable: {error}"
            ) from error
        names = [entry.name for entry in entries]
        if len(names) != len(set(names)) or set(names) != set(expected):
            raise ProvenanceVerificationError(
                "directory snapshot does not contain the exact file set"
            )
        snapshots: dict[str, os.stat_result] = {}
        for entry in entries:
            path = directory / entry.name
            try:
                raw = os.lstat(path)
            except OSError as error:
                raise ProvenanceVerificationError(
                    f"directory snapshot entry {entry.name!r} is unreadable: {error}"
                ) from error
            if _is_link_or_reparse(path, raw) or not stat.S_ISREG(raw.st_mode):
                raise ProvenanceVerificationError(
                    "directory snapshot entries must be non-reparse regular files"
                )
            _verify_single_link(path, raw)
            snapshots[entry.name] = raw
        return snapshots

    entry_stats = exact_entries()
    verified_files: list[_VerifiedFile] = []
    for logical_name in sorted(expected):
        payload = expected[logical_name]
        verified = _verify_stable_file(
            directory / logical_name,
            logical_name=logical_name,
            expected_bytes=len(payload),
            expected_sha256=hashlib.sha256(payload).hexdigest(),
            capture_content=True,
            maximum_capture_bytes=len(payload),
        )
        if (
            verified.identity != _identity(entry_stats[logical_name])
            or verified.content != payload
        ):
            raise ProvenanceVerificationError(
                f"directory snapshot payload {logical_name!r} does not have exact bytes"
            )
        verified_files.append(verified)

    final_entry_stats = exact_entries()
    for verified in verified_files:
        if _identity(final_entry_stats[verified.logical_name]) != verified.identity:
            raise ProvenanceVerificationError(
                "directory snapshot file identity changed during exact replay"
            )
    try:
        directory_after_stat = os.lstat(directory)
    except OSError as error:
        raise ProvenanceVerificationError(
            f"directory snapshot root disappeared: {error}"
        ) from error
    if (
        _is_link_or_reparse(directory, directory_after_stat)
        or _identity(directory_after_stat) != directory_before
    ):
        raise ProvenanceVerificationError(
            "directory snapshot root identity changed during verification"
        )
    return tuple(verified_files)


def _open_windows_delete_fd(path: Path, *, directory: bool) -> int:
    if os.name != "nt":
        raise ProvenanceVerificationError(
            "exact bundle cleanup requires the pinned Windows host"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

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
    desired_access = _DELETE_ACCESS | _FILE_READ_ATTRIBUTES | _SYNCHRONIZE_ACCESS
    if directory:
        desired_access |= _FILE_LIST_DIRECTORY
    handle = create_file(
        str(path),
        desired_access,
        _FILE_SHARE_READ | _FILE_SHARE_WRITE | _FILE_SHARE_DELETE,
        None,
        _OPEN_EXISTING,
        flags,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "exact bundle cleanup handle open failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    try:
        return msvcrt.open_osfhandle(
            int(handle), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        close_handle(handle)
        raise


def _windows_directory_names(fd: int) -> set[str]:
    import ctypes
    import msvcrt
    from ctypes import wintypes

    class _FileIdBothDirectoryInfo(ctypes.Structure):
        _fields_ = [
            ("next_entry_offset", wintypes.DWORD),
            ("file_index", wintypes.DWORD),
            ("creation_time", ctypes.c_longlong),
            ("last_access_time", ctypes.c_longlong),
            ("last_write_time", ctypes.c_longlong),
            ("change_time", ctypes.c_longlong),
            ("end_of_file", ctypes.c_longlong),
            ("allocation_size", ctypes.c_longlong),
            ("file_attributes", wintypes.DWORD),
            ("file_name_length", wintypes.DWORD),
            ("ea_size", wintypes.DWORD),
            ("short_name_length", ctypes.c_ubyte),
            ("short_name", wintypes.WCHAR * 12),
            ("file_id", ctypes.c_longlong),
        ]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    get_information = kernel32.GetFileInformationByHandleEx
    get_information.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    get_information.restype = wintypes.BOOL
    names: set[str] = set()
    information_class = 11
    while True:
        buffer = ctypes.create_string_buffer(64 * 1024)
        if not get_information(
            wintypes.HANDLE(msvcrt.get_osfhandle(fd)),
            information_class,
            buffer,
            len(buffer),
        ):
            number = ctypes.get_last_error()
            if number == 18:
                return names
            raise ProvenanceVerificationError(
                "exact bundle cleanup directory enumeration failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        information_class = 10
        offset = 0
        while True:
            header = _FileIdBothDirectoryInfo.from_buffer(buffer, offset)
            name_bytes = int(header.file_name_length)
            name_offset = offset + ctypes.sizeof(_FileIdBothDirectoryInfo)
            if (
                name_bytes % 2
                or name_offset + name_bytes > len(buffer)
                or header.next_entry_offset
                and (
                    header.next_entry_offset < ctypes.sizeof(_FileIdBothDirectoryInfo)
                    or offset + header.next_entry_offset >= len(buffer)
                )
            ):
                raise ProvenanceVerificationError(
                    "exact bundle cleanup directory enumeration is malformed"
                )
            name = ctypes.wstring_at(
                ctypes.addressof(buffer) + name_offset, name_bytes // 2
            )
            if name not in {".", ".."}:
                if not name or Path(name).name != name or name in names:
                    raise ProvenanceVerificationError(
                        "exact bundle cleanup directory names are unsafe"
                    )
                names.add(name)
            if not header.next_entry_offset:
                break
            offset += int(header.next_entry_offset)


def _open_windows_relative_delete_fd(directory_fd: int, name: str) -> int:
    if not name or Path(name).name != name or name in {".", ".."}:
        raise ProvenanceVerificationError(
            "exact bundle cleanup child name is unsafe"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

    class _UnicodeString(ctypes.Structure):
        _fields_ = [
            ("length", wintypes.USHORT),
            ("maximum_length", wintypes.USHORT),
            ("buffer", wintypes.LPWSTR),
        ]

    class _ObjectAttributes(ctypes.Structure):
        _fields_ = [
            ("length", wintypes.ULONG),
            ("root_directory", wintypes.HANDLE),
            ("object_name", ctypes.POINTER(_UnicodeString)),
            ("attributes", wintypes.ULONG),
            ("security_descriptor", ctypes.c_void_p),
            ("security_quality_of_service", ctypes.c_void_p),
        ]

    class _IoStatusBlock(ctypes.Structure):
        _fields_ = [
            ("status", ctypes.c_ssize_t),
            ("information", ctypes.c_size_t),
        ]

    name_buffer = ctypes.create_unicode_buffer(name)
    name_bytes = len(name.encode("utf-16-le"))
    object_name = _UnicodeString(
        name_bytes,
        name_bytes + ctypes.sizeof(ctypes.c_wchar),
        ctypes.cast(name_buffer, wintypes.LPWSTR),
    )
    attributes = _ObjectAttributes(
        ctypes.sizeof(_ObjectAttributes),
        wintypes.HANDLE(msvcrt.get_osfhandle(directory_fd)),
        ctypes.pointer(object_name),
        0x40,
        None,
        None,
    )
    io_status = _IoStatusBlock()
    handle = wintypes.HANDLE()
    create_file = ctypes.WinDLL("ntdll").NtCreateFile
    create_file.restype = ctypes.c_long
    status = int(
        create_file(
            ctypes.byref(handle),
            wintypes.DWORD(
                _DELETE_ACCESS | _FILE_READ_ATTRIBUTES | _SYNCHRONIZE_ACCESS
            ),
            ctypes.byref(attributes),
            ctypes.byref(io_status),
            ctypes.POINTER(ctypes.c_longlong)(),
            wintypes.ULONG(0x80),
            wintypes.ULONG(
                _FILE_SHARE_READ | _FILE_SHARE_WRITE | _FILE_SHARE_DELETE
            ),
            wintypes.ULONG(1),
            wintypes.ULONG(_FILE_FLAG_OPEN_REPARSE_POINT | 0x40 | 0x20),
            ctypes.c_void_p(),
            wintypes.ULONG(0),
        )
    ) & 0xFFFFFFFF
    if status != 0 or io_status.information != 1 or handle.value is None:
        if handle.value is not None:
            ctypes.WinDLL("kernel32").CloseHandle(handle)
        raise ProvenanceVerificationError(
            "exact bundle cleanup relative child open failed: "
            f"NTSTATUS 0x{status:08x}, result {io_status.information}"
        )
    try:
        return msvcrt.open_osfhandle(
            int(handle.value), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        ctypes.WinDLL("kernel32").CloseHandle(handle)
        raise


def _mark_windows_fd_for_delete(
    fd: int,
    *,
    require_single_link: bool = False,
) -> None:
    import ctypes
    import msvcrt
    from ctypes import wintypes

    class _FileDispositionInfo(ctypes.Structure):
        _fields_ = [("delete_file", ctypes.c_ubyte)]

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    if require_single_link and (
        os.fstat(fd).st_nlink != 1 or _windows_fd_link_count(fd) != 1
    ):
        raise ProvenanceVerificationError(
            "refusing delete disposition without exactly one link"
        )
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = [
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    ]
    set_information.restype = wintypes.BOOL
    information = _FileDispositionInfo(1)
    if not set_information(
        msvcrt.get_osfhandle(fd),
        _FILE_DISPOSITION_INFO_CLASS,
        ctypes.byref(information),
        ctypes.sizeof(information),
    ):
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "exact bundle cleanup handle deletion failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )


def _delete_exact_regular_file(
    path: Path,
    expected_key: tuple[int, int],
    *,
    require_single_link: bool = True,
) -> None:
    try:
        fd = _open_windows_delete_fd(path, directory=False)
    except ProvenanceVerificationError:
        if not os.path.lexists(path):
            return
        raise
    try:
        raw = os.fstat(fd)
        if (
            _is_link_or_reparse(path, raw)
            or not stat.S_ISREG(raw.st_mode)
            or (raw.st_dev, raw.st_ino) != expected_key
        ):
            raise ProvenanceVerificationError(
                "refusing to delete a replaced/reparse bundle file"
            )
        if require_single_link and (
            raw.st_nlink != 1 or _windows_fd_link_count(fd) != 1
        ):
            raise ProvenanceVerificationError(
                "refusing to delete a bundle file without exactly one link"
            )
        _mark_windows_fd_for_delete(
            fd, require_single_link=require_single_link
        )
    finally:
        os.close(fd)


def _remove_owned_install_path(
    path: Path,
    *,
    expected_file_key: tuple[int, int] | None = None,
    expected_files: Mapping[str, tuple[int, int]] | None = None,
) -> bool:
    if not os.path.lexists(path):
        return False
    if expected_file_key is None or not isinstance(expected_files, Mapping):
        raise ProvenanceVerificationError(
            "exact bundle cleanup identities are unavailable"
        )
    fd = _open_windows_delete_fd(path, directory=True)
    child_fds: dict[str, int] = {}
    try:
        raw = os.fstat(fd)
        reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
        if (
            stat.S_ISLNK(raw.st_mode)
            or bool(
                (getattr(raw, "st_file_attributes", 0) or 0) & reparse_flag
            )
            or not stat.S_ISDIR(raw.st_mode)
            or (raw.st_dev, raw.st_ino) != expected_file_key
        ):
            raise ProvenanceVerificationError(
                "refusing to clean a replaced/reparse bundle directory"
            )
        actual_names = _windows_directory_names(fd)
        unknown_names = actual_names.difference(expected_files)
        if unknown_names:
            raise ProvenanceVerificationError(
                "refusing to clean a bundle directory with unowned entries: "
                + ", ".join(sorted(unknown_names))
            )
        for name in sorted(actual_names):
            child_fd = _open_windows_relative_delete_fd(fd, name)
            child_fds[name] = child_fd
            child = os.fstat(child_fd)
            if (
                stat.S_ISLNK(child.st_mode)
                or bool(
                    (getattr(child, "st_file_attributes", 0) or 0)
                    & reparse_flag
                )
                or not stat.S_ISREG(child.st_mode)
                or (child.st_dev, child.st_ino) != expected_files[name]
            ):
                raise ProvenanceVerificationError(
                    "refusing to delete a replaced/reparse bundle file"
                )
        if _windows_directory_names(fd) != actual_names:
            raise ProvenanceVerificationError(
                "bundle directory changed during exact cleanup"
            )
        for name in sorted(child_fds):
            child_fd = child_fds.pop(name)
            try:
                _mark_windows_fd_for_delete(child_fd)
            finally:
                os.close(child_fd)
        if _windows_directory_names(fd):
            raise ProvenanceVerificationError(
                "bundle directory changed during exact cleanup"
            )
        _mark_windows_fd_for_delete(fd)
    finally:
        for child_fd in child_fds.values():
            os.close(child_fd)
        os.close(fd)
    return True


def _directory_metadata_barrier(directory: Path) -> bool:
    """Flush parent metadata, returning False only for unsupported Windows barriers."""
    if os.name != "nt":
        try:
            fd = os.open(directory, os.O_RDONLY)
        except OSError as error:
            raise ProvenanceVerificationError(
                f"directory metadata barrier open failed: {error}"
            ) from error
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        return True
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.restype = wintypes.HANDLE
    handle = create_file(
        str(directory),
        _FILE_READ_ATTRIBUTES,
        _FILE_SHARE_READ | _FILE_SHARE_WRITE | _FILE_SHARE_DELETE,
        None,
        _OPEN_EXISTING,
        _FILE_FLAG_OPEN_REPARSE_POINT | _FILE_FLAG_BACKUP_SEMANTICS,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "directory metadata barrier open failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    supported = True
    try:
        if not kernel32.FlushFileBuffers(wintypes.HANDLE(handle)):
            number = ctypes.get_last_error()
            if number in {1, 5, 6, 50}:
                supported = False
            else:
                raise ProvenanceVerificationError(
                    "directory metadata barrier failed: "
                    f"{OSError(number, ctypes.FormatError(number))}"
                )
    finally:
        if not kernel32.CloseHandle(wintypes.HANDLE(handle)):
            number = ctypes.get_last_error()
            raise ProvenanceVerificationError(
                "directory metadata barrier close failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
    return supported


def _open_windows_read_hold(
    path: Path,
    *,
    directory: bool,
    share_write: bool,
) -> int:
    if os.name != "nt":
        raise ProvenanceVerificationError(
            "publication identity holds require the pinned Windows host"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

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
    flags = _FILE_FLAG_OPEN_REPARSE_POINT
    if directory:
        flags |= _FILE_FLAG_BACKUP_SEMANTICS
    share = _FILE_SHARE_READ | (_FILE_SHARE_WRITE if share_write else 0)
    handle = create_file(
        str(path),
        _FILE_READ_ATTRIBUTES if directory else _GENERIC_READ,
        share,
        None,
        _OPEN_EXISTING,
        flags,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "publication identity hold open failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    try:
        return msvcrt.open_osfhandle(
            int(handle), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        kernel32.CloseHandle(wintypes.HANDLE(handle))
        raise


def _file_key(raw: os.stat_result) -> tuple[int, int]:
    return raw.st_dev, raw.st_ino


def _journal_name(value: object, label: str) -> str:
    if (
        type(value) is not str
        or not value
        or Path(value).name != value
        or value
        in {
            ".",
            "..",
            _PUBLICATION_LOCK_NAME,
            *_PUBLICATION_JOURNAL_NAMES,
            *_PUBLICATION_JOURNAL_TEMP_NAMES,
        }
    ):
        raise ProvenanceVerificationError(
            f"publication journal {label} is not an exact child name"
        )
    return value


def _journal_file_id(value: object, label: str) -> tuple[int, int]:
    if (
        type(value) is not list
        or len(value) != 2
        or any(type(item) is not int or item < 0 for item in value)
    ):
        raise ProvenanceVerificationError(
            f"publication journal {label} FileId is malformed"
        )
    return value[0], value[1]


def _prepare_guarded_journal_temp(
    parent: Path,
    slot_name: str,
    prepare: Callable[[Path, int, tuple[int, int]], None],
) -> tuple[Path, int, tuple[int, int]]:
    if os.name != "nt":
        raise ProvenanceVerificationError(
            "publication journal atomic writes require the pinned Windows host"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

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
    invalid = ctypes.c_void_p(-1).value
    try:
        temp_name = _PUBLICATION_JOURNAL_TEMP_NAMES[
            _PUBLICATION_JOURNAL_NAMES.index(slot_name)
        ]
    except ValueError as error:
        raise ProvenanceVerificationError(
            "publication journal temporary destination is not reserved"
        ) from error
    path = parent / temp_name
    handle = create_file(
        str(path),
        _GENERIC_READ | _GENERIC_WRITE | _DELETE_ACCESS,
        _FILE_SHARE_READ | _FILE_SHARE_WRITE | _FILE_SHARE_DELETE,
        None,
        _CREATE_NEW,
        _FILE_FLAG_OPEN_REPARSE_POINT | _FILE_FLAG_DELETE_ON_CLOSE,
        None,
    )
    if handle == invalid:
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "publication journal reserved temporary slot creation failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )
    try:
        fd = msvcrt.open_osfhandle(
            int(handle), os.O_RDWR | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        kernel32.CloseHandle(wintypes.HANDLE(handle))
        raise
    try:
        held = os.fstat(fd)
        created = os.lstat(path)
        key = _file_key(held)
        if (
            _is_link_or_reparse(path, created)
            or not stat.S_ISREG(created.st_mode)
            or not stat.S_ISREG(held.st_mode)
            or _file_key(created) != key
            or created.st_nlink != 1
            or held.st_nlink != 1
            or _windows_fd_link_count(fd) != 1
        ):
            raise ProvenanceVerificationError(
                "publication journal temporary slot identity is unsafe"
            )

        def guarded(receipt_key: tuple[int, int]) -> None:
            if receipt_key != key:
                raise ProvenanceVerificationError(
                    "publication journal temporary slot FileId receipt changed"
                )
            prepare(path, fd, key)

        _journal_created_windows_handle(
            int(handle),
            directory=False,
            callback=guarded,
            file_key=key,
        )
        reopen_file = kernel32.ReOpenFile
        reopen_file.restype = wintypes.HANDLE
        persistent_handle = reopen_file(
            wintypes.HANDLE(handle),
            _GENERIC_READ | _GENERIC_WRITE | _DELETE_ACCESS,
            _FILE_SHARE_READ | _FILE_SHARE_WRITE | _FILE_SHARE_DELETE,
            _FILE_FLAG_OPEN_REPARSE_POINT,
        )
        if persistent_handle == invalid:
            number = ctypes.get_last_error()
            raise ProvenanceVerificationError(
                "publication journal durable temporary handle reopen failed: "
                f"{OSError(number, ctypes.FormatError(number))}"
            )
        try:
            os.close(fd)
            fd = None

            class _FileDispositionInfo(ctypes.Structure):
                _fields_ = [("delete_file", ctypes.c_ubyte)]

            disposition = _FileDispositionInfo(0)
            if not kernel32.SetFileInformationByHandle(
                wintypes.HANDLE(persistent_handle),
                _FILE_DISPOSITION_INFO_CLASS,
                ctypes.byref(disposition),
                ctypes.sizeof(disposition),
            ):
                number = ctypes.get_last_error()
                raise ProvenanceVerificationError(
                    "publication journal durable temporary transition failed: "
                    f"{OSError(number, ctypes.FormatError(number))}"
                )
            fd = msvcrt.open_osfhandle(
                int(persistent_handle), os.O_RDWR | getattr(os, "O_BINARY", 0)
            )
            persistent_handle = None
        finally:
            pending = sys.exception()
            if persistent_handle is not None and not kernel32.CloseHandle(
                wintypes.HANDLE(persistent_handle)
            ) and pending is None:
                number = ctypes.get_last_error()
                raise ProvenanceVerificationError(
                    "publication journal durable temporary handle close failed: "
                    f"{OSError(number, ctypes.FormatError(number))}"
                )
        held = os.fstat(fd)
        created = os.lstat(path)
        if (
            _is_link_or_reparse(path, created)
            or not stat.S_ISREG(created.st_mode)
            or not stat.S_ISREG(held.st_mode)
            or _file_key(created) != key
            or _file_key(held) != key
            or created.st_nlink != 1
            or held.st_nlink != 1
            or _windows_fd_link_count(fd) != 1
        ):
            raise ProvenanceVerificationError(
                "publication journal temporary slot changed before atomic rename"
            )
        return path, fd, key
    except BaseException as error:
        if fd is not None:
            os.close(fd)
        if "key" in locals() and os.path.lexists(path):
            try:
                _delete_exact_regular_file(path, key)
            except BaseException as cleanup_error:
                error.add_note(
                    "publication journal temporary cleanup also failed: "
                    f"{type(cleanup_error).__name__}: {cleanup_error}"
                )
        raise


def _replace_file_write_through(source: Path, destination: Path) -> None:
    if os.name != "nt":
        os.replace(source, destination)
        _directory_metadata_barrier(destination.parent)
        return
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    move_file = kernel32.MoveFileExW
    move_file.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
    move_file.restype = wintypes.BOOL
    if not move_file(str(source), str(destination), 0x1 | 0x8):
        number = ctypes.get_last_error()
        raise ProvenanceVerificationError(
            "publication journal atomic write-through rename failed: "
            f"{OSError(number, ctypes.FormatError(number))}"
        )


def _validated_publication_state(
    document: dict[str, object],
) -> dict[str, object]:
    if set(document) != {
        "generation",
        "journalFileIds",
        "parentFileId",
        "payloads",
        "phase",
        "schema",
        "staging",
        "targetName",
    } or document.get("schema") != _PUBLICATION_SCHEMA:
        raise ProvenanceVerificationError(
            "publication journal schema is not exact"
        )
    generation = document["generation"]
    phase = document["phase"]
    if (
        type(generation) is not int
        or generation < 1
        or phase not in _PUBLICATION_PHASES
    ):
        raise ProvenanceVerificationError(
            "publication journal generation or phase is invalid"
        )
    _journal_file_id(document["parentFileId"], "parent")
    _journal_name(document["targetName"], "target")
    payloads = document["payloads"]
    if type(payloads) is not list or not payloads:
        raise ProvenanceVerificationError(
            "publication journal payload manifest is unavailable"
        )
    payload_names: list[str] = []
    for item in payloads:
        if type(item) is not dict or set(item) != {
            "bytes",
            "logicalName",
            "sha256",
        }:
            raise ProvenanceVerificationError(
                "publication journal payload record is malformed"
            )
        name = _journal_name(item["logicalName"], "payload")
        if (
            type(item["bytes"]) is not int
            or item["bytes"] < 0
            or item["bytes"] > 64 * 1024 * 1024
            or type(item["sha256"]) is not str
            or _LOWER_SHA256.fullmatch(item["sha256"]) is None
        ):
            raise ProvenanceVerificationError(
                "publication journal payload facts are malformed"
            )
        payload_names.append(name)
    if payload_names != sorted(set(payload_names)):
        raise ProvenanceVerificationError(
            "publication journal payload names are not exact and sorted"
        )
    slot_records = document["journalFileIds"]
    if type(slot_records) is not list or not slot_records:
        raise ProvenanceVerificationError(
            "publication journal slot identities are unavailable"
        )
    slot_names: list[str] = []
    for item in slot_records:
        if type(item) is not dict or set(item) != {"fileId", "name"}:
            raise ProvenanceVerificationError(
                "publication journal slot record is malformed"
            )
        name = item["name"]
        if name not in _PUBLICATION_JOURNAL_NAMES:
            raise ProvenanceVerificationError(
                "publication journal slot name is not reserved"
            )
        _journal_file_id(item["fileId"], "slot")
        slot_names.append(name)
    if slot_names != sorted(set(slot_names)):
        raise ProvenanceVerificationError(
            "publication journal slot records are not sorted and unique"
        )
    staging = document["staging"]
    if staging is None:
        if phase != "intent":
            raise ProvenanceVerificationError(
                "publication journal phase has no staging identity"
            )
        return document
    if type(staging) is not dict or set(staging) != {"fileId", "files", "name"}:
        raise ProvenanceVerificationError(
            "publication journal staging record is malformed"
        )
    _journal_name(staging["name"], "staging")
    _journal_file_id(staging["fileId"], "staging")
    files = staging["files"]
    if type(files) is not list:
        raise ProvenanceVerificationError(
            "publication journal child identity records are malformed"
        )
    file_names: list[str] = []
    for item in files:
        if type(item) is not dict or set(item) != {"fileId", "logicalName"}:
            raise ProvenanceVerificationError(
                "publication journal child identity record is malformed"
            )
        name = _journal_name(item["logicalName"], "child")
        if name not in payload_names:
            raise ProvenanceVerificationError(
                "publication journal child is not in the payload manifest"
            )
        _journal_file_id(item["fileId"], "child")
        file_names.append(name)
    if file_names != sorted(set(file_names)):
        raise ProvenanceVerificationError(
            "publication journal child identities are not sorted and unique"
        )
    if phase in {"staged", "renaming", "installed", "accepted"} and (
        file_names != payload_names
    ):
        raise ProvenanceVerificationError(
            "publication journal durable phase has an incomplete ownership graph"
        )
    return document


class _PublicationJournal:
    """Dual-slot, FileId-bound publication state held under one parent writer lock."""
    def __init__(self, parent: Path):
        if not isinstance(parent, Path):
            raise ProvenanceVerificationError(
                "publication journal parent must be a Path"
            )
        try:
            supplied_parent = Path(os.path.abspath(parent))
            supplied_stat = os.lstat(supplied_parent)
            if (
                _is_link_or_reparse(supplied_parent, supplied_stat)
                or not stat.S_ISDIR(supplied_stat.st_mode)
            ):
                raise ProvenanceVerificationError(
                    "publication journal parent must be a non-reparse directory"
                )
            self.parent = supplied_parent.resolve(strict=True)
            parent_stat = os.lstat(self.parent)
        except ProvenanceVerificationError:
            raise
        except OSError as error:
            raise ProvenanceVerificationError(
                f"publication journal parent is unavailable: {error}"
            ) from error
        if (
            _is_link_or_reparse(self.parent, parent_stat)
            or not stat.S_ISDIR(parent_stat.st_mode)
        ):
            raise ProvenanceVerificationError(
                "publication journal parent must be a non-reparse directory"
            )
        self.parent_key = _file_key(parent_stat)
        self._lock_fd: int | None = None
        self._parent_fd: int | None = None
        self._state: dict[str, object] | None = None
        self._generation = 0
        self._slot_keys: dict[str, tuple[int, int]] = {}

    def __enter__(self) -> _PublicationJournal:
        self._acquire_lock()
        try:
            self._pin_parent_after_lock()
            self._load()
        except BaseException:
            self._release_parent_hold()
            self._release_lock()
            raise
        return self

    def __exit__(self, *_: object) -> None:
        self._release_parent_hold()
        self._release_lock()

    def _pin_parent_after_lock(self) -> None:
        if self._lock_fd is None or self._parent_fd is not None:
            raise ProvenanceVerificationError(
                "publication journal parent pinning requires one held writer lock"
            )
        fd = _open_windows_read_hold(
            self.parent, directory=True, share_write=True
        )
        try:
            self._verify_parent_binding(fd)
        except BaseException:
            os.close(fd)
            raise
        self._parent_fd = fd

    def _verify_parent_binding(self, fd: int | None = None) -> None:
        descriptor = self._parent_fd if fd is None else fd
        if descriptor is None:
            raise ProvenanceVerificationError(
                "publication journal parent identity is not pinned"
            )
        try:
            held = os.fstat(descriptor)
            current = os.lstat(self.parent)
        except OSError as error:
            raise ProvenanceVerificationError(
                f"publication journal parent identity is unavailable: {error}"
            ) from error
        if (
            _is_link_or_reparse(self.parent, current)
            or not stat.S_ISDIR(held.st_mode)
            or not stat.S_ISDIR(current.st_mode)
            or _file_key(held) != self.parent_key
            or _file_key(current) != self.parent_key
        ):
            raise ProvenanceVerificationError(
                "publication journal parent FileId changed or became a reparse point"
            )

    def _release_parent_hold(self) -> None:
        if self._parent_fd is None:
            return
        fd, self._parent_fd = self._parent_fd, None
        os.close(fd)

    def _acquire_lock(self) -> None:
        if self._lock_fd is not None:
            raise ProvenanceVerificationError(
                "publication journal lock is already held"
            )
        path = self.parent / _PUBLICATION_LOCK_NAME
        try:
            if os.path.lexists(path):
                before = os.lstat(path)
                if (
                    _is_link_or_reparse(path, before)
                    or not stat.S_ISREG(before.st_mode)
                    or before.st_nlink != 1
                ):
                    raise ProvenanceVerificationError(
                        "publication journal lock must be a single-link regular file"
                    )
            fd = os.open(
                path,
                os.O_RDWR | os.O_CREAT | getattr(os, "O_BINARY", 0),
                0o600,
            )
            held = os.fstat(fd)
            after = os.lstat(path)
            if (
                not stat.S_ISREG(held.st_mode)
                or held.st_nlink != 1
                or _file_key(held) != _file_key(after)
                or _is_link_or_reparse(path, after)
            ):
                raise ProvenanceVerificationError(
                    "publication journal lock identity is unsafe"
                )
            if held.st_size == 0:
                os.write(fd, b"\0")
                os.fsync(fd)
                _directory_metadata_barrier(self.parent)
            elif held.st_size != 1:
                raise ProvenanceVerificationError(
                    "publication journal lock bytes are malformed"
                )
            import msvcrt

            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
        except BaseException as error:
            if "fd" in locals():
                os.close(fd)
            if isinstance(error, ProvenanceVerificationError):
                raise
            raise ProvenanceVerificationError(
                f"another publication writer is active or lock acquisition failed: {error}"
            ) from error
        self._lock_fd = fd

    def _release_lock(self) -> None:
        if self._lock_fd is None:
            return
        fd, self._lock_fd = self._lock_fd, None
        try:
            import msvcrt

            os.lseek(fd, 0, os.SEEK_SET)
            msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
        finally:
            os.close(fd)

    @staticmethod
    def _slot_keys_from(document: dict[str, object]) -> dict[str, tuple[int, int]]:
        return {
            item["name"]: _journal_file_id(item["fileId"], "slot")
            for item in document["journalFileIds"]
        }

    @staticmethod
    def _is_explicit_predecessor(
        older: dict[str, object],
        newer: dict[str, object],
    ) -> bool:
        if older["generation"] + 1 != newer["generation"]:
            return False
        for field in ("parentFileId", "payloads", "schema", "targetName"):
            if older[field] != newer[field]:
                return False
        old_phase, new_phase = older["phase"], newer["phase"]
        old_staging, new_staging = older["staging"], newer["staging"]
        if old_phase == "intent":
            return (
                old_staging is None
                and type(new_staging) is dict
                and new_phase == "building"
                and new_staging["files"] == []
            )
        if type(old_staging) is not dict or type(new_staging) is not dict:
            return False
        if old_phase == "building" and new_phase == "building":
            if (
                old_staging["fileId"] != new_staging["fileId"]
                or old_staging["name"] != new_staging["name"]
            ):
                return False
            old_files = {
                (item["logicalName"], tuple(item["fileId"]))
                for item in old_staging["files"]
            }
            new_files = {
                (item["logicalName"], tuple(item["fileId"]))
                for item in new_staging["files"]
            }
            return len(new_files) == len(old_files) + 1 and old_files < new_files
        transitions = {
            "building": {"staged"},
            "staged": {"renaming"},
            "renaming": {"installed", "accepted"},
            "installed": {"accepted"},
        }
        return (
            new_phase in transitions.get(old_phase, set())
            and old_staging == new_staging
        )

    def _read_slot(
        self,
        path: Path,
        *,
        binding_name: str | None = None,
        label: str = "slot",
    ) -> tuple[dict[str, object], tuple[int, int]]:
        raw = os.lstat(path)
        if (
            _is_link_or_reparse(path, raw)
            or not stat.S_ISREG(raw.st_mode)
            or raw.st_nlink != 1
            or raw.st_size < 1
            or raw.st_size > 1024 * 1024
        ):
            raise ProvenanceVerificationError(
                f"publication journal {label} is not an exact single-link regular file"
            )
        verified = _verify_stable_file(
            path,
            logical_name=f"publication journal {label}",
            capture_content=True,
            maximum_capture_bytes=1024 * 1024,
        )
        if verified.content is None:
            raise ProvenanceVerificationError(
                f"publication journal {label} bytes are unavailable"
            )
        document = _validated_publication_state(
            _decode_canonical_object(verified.content, "publication journal")
        )
        key = _file_key(raw)
        bound_name = path.name if binding_name is None else binding_name
        if self._slot_keys_from(document).get(bound_name) != key:
            raise ProvenanceVerificationError(
                f"publication journal {label} FileId is not self-bound"
            )
        return document, key

    def _load(self) -> None:
        self._verify_parent_binding()
        valid: list[tuple[dict[str, object], str, tuple[int, int]]] = []
        invalid: list[tuple[str, BaseException, tuple[int, int] | None]] = []
        for name in _PUBLICATION_JOURNAL_NAMES:
            path = self.parent / name
            if not os.path.lexists(path):
                continue
            try:
                document, key = self._read_slot(path)
            except BaseException as error:
                try:
                    raw = os.lstat(path)
                    key = _file_key(raw)
                except OSError:
                    key = None
                invalid.append((name, error, key))
            else:
                valid.append((document, name, key))
        prepared: list[
            tuple[dict[str, object], str, str, tuple[int, int]]
        ] = []
        invalid_prepared: list[tuple[str, BaseException]] = []
        for slot_name, temp_name in zip(
            _PUBLICATION_JOURNAL_NAMES, _PUBLICATION_JOURNAL_TEMP_NAMES
        ):
            path = self.parent / temp_name
            if not os.path.lexists(path):
                continue
            try:
                document, key = self._read_slot(
                    path,
                    binding_name=slot_name,
                    label="prepared temporary slot",
                )
            except BaseException as error:
                invalid_prepared.append((temp_name, error))
            else:
                prepared.append((document, temp_name, slot_name, key))
        if not valid and invalid:
            raise ProvenanceVerificationError(
                "publication journal has no recoverable durable slot: "
                + " | ".join(f"{name}: {error}" for name, error, _ in invalid)
            )
        if invalid:
            raise ProvenanceVerificationError(
                "publication journal contains an invalid newer or corrupted slot: "
                + " | ".join(f"{name}: {error}" for name, error, _ in invalid)
            )
        if invalid_prepared:
            raise ProvenanceVerificationError(
                "publication journal reserved prepared temporary slot is not "
                "recoverable: "
                + " | ".join(
                    f"{name}: {error}" for name, error in invalid_prepared
                )
            )
        if len(prepared) > 1:
            raise ProvenanceVerificationError(
                "publication journal has multiple prepared temporary generations"
            )
        for candidate, _, _ in valid:
            if (
                _journal_file_id(candidate["parentFileId"], "parent")
                != self.parent_key
            ):
                raise ProvenanceVerificationError(
                    "publication journal parent FileId changed"
                )
        valid.sort(key=lambda item: (item[0]["generation"], item[1]))
        if len(valid) == 2:
            older, newer = valid
            if older[0]["generation"] == newer[0]["generation"]:
                if older[0] != newer[0]:
                    raise ProvenanceVerificationError(
                        "publication journal has divergent equal-generation slots"
                    )
            elif not self._is_explicit_predecessor(older[0], newer[0]):
                raise ProvenanceVerificationError(
                    "publication journal slots are not an explicit predecessor chain"
                )
        if prepared:
            candidate, temp_name, slot_name, temp_key = prepared[0]
            if (
                _journal_file_id(candidate["parentFileId"], "parent")
                != self.parent_key
                or _PUBLICATION_JOURNAL_NAMES[
                    (candidate["generation"] - 1) % len(_PUBLICATION_JOURNAL_NAMES)
                ]
                != slot_name
            ):
                raise ProvenanceVerificationError(
                    "publication journal prepared temporary parent or destination "
                    "identity changed"
                )
            candidate_keys = self._slot_keys_from(candidate)
            if valid:
                newest = valid[-1][0]
                durable_keys = {name: key for _, name, key in valid}
                if (
                    not self._is_explicit_predecessor(newest, candidate)
                    or set(candidate_keys) != set(durable_keys) | {slot_name}
                    or any(
                        candidate_keys.get(name) != key
                        for name, key in durable_keys.items()
                        if name != slot_name
                    )
                ):
                    raise ProvenanceVerificationError(
                        "publication journal prepared temporary is not the exact "
                        "next durable generation"
                    )
            elif (
                candidate["generation"] != 1
                or candidate["phase"] != "intent"
                or set(candidate_keys) != {slot_name}
            ):
                raise ProvenanceVerificationError(
                    "publication journal initial prepared temporary is not exact"
                )
            _delete_exact_regular_file(
                self.parent / temp_name,
                temp_key,
                require_single_link=True,
            )
            _directory_metadata_barrier(self.parent)
        if not valid:
            return
        document = valid[-1][0]
        slot_keys = self._slot_keys_from(document)
        for _, name, key in valid:
            if slot_keys.get(name) != key:
                raise ProvenanceVerificationError(
                    "publication journal newest slot does not bind its predecessor"
                )
        present_names = {name for _, name, _ in valid}
        slot_keys = {
            name: key for name, key in slot_keys.items() if name in present_names
        }
        self._state = document
        self._generation = document["generation"]
        self._slot_keys = slot_keys

    def _commit(self, document: dict[str, object]) -> None:
        self._verify_parent_binding()
        generation = self._generation + 1
        name = _PUBLICATION_JOURNAL_NAMES[(generation - 1) % 2]
        path = self.parent / name
        expected_key = self._slot_keys.get(name)
        committed: dict[str, object] | None = None
        next_slot_keys: dict[str, tuple[int, int]] | None = None

        def verify_destination() -> None:
            present = os.path.lexists(path)
            if not present:
                if expected_key is not None:
                    raise ProvenanceVerificationError(
                        "publication journal slot disappeared"
                    )
                return
            before = os.lstat(path)
            if (
                expected_key is None
                or _file_key(before) != expected_key
                or _is_link_or_reparse(path, before)
                or not stat.S_ISREG(before.st_mode)
                or before.st_nlink != 1
            ):
                raise ProvenanceVerificationError(
                    "publication journal slot was replaced"
                )

        def prepare(
            temporary: Path,
            fd: int,
            key: tuple[int, int],
        ) -> None:
            nonlocal committed, next_slot_keys
            held = os.fstat(fd)
            if (
                not stat.S_ISREG(held.st_mode)
                or _file_key(held) != key
            ):
                raise ProvenanceVerificationError(
                    "publication journal temporary slot identity is unsafe"
                )
            next_slot_keys = dict(self._slot_keys)
            next_slot_keys[name] = key
            committed = dict(document)
            committed["generation"] = generation
            committed["journalFileIds"] = [
                {"fileId": list(next_slot_keys[item]), "name": item}
                for item in sorted(next_slot_keys)
            ]
            _validated_publication_state(committed)
            encoded = canonical_json_bytes(committed)
            os.lseek(fd, 0, os.SEEK_SET)
            os.ftruncate(fd, 0)
            written = 0
            while written < len(encoded):
                count = os.write(fd, encoded[written:])
                if count < 1:
                    raise ProvenanceVerificationError(
                        "publication journal temporary slot write made no progress"
                    )
                written += count
            os.fsync(fd)
            final_held = os.fstat(fd)
            if (
                _file_key(final_held) != key
                or final_held.st_size != len(encoded)
            ):
                raise ProvenanceVerificationError(
                    "publication journal temporary slot changed during durable write"
                )

        verify_destination()
        temporary: Path | None = None
        fd: int | None = None
        key: tuple[int, int] | None = None
        try:
            temporary, fd, key = _prepare_guarded_journal_temp(
                self.parent, name, prepare
            )
            verify_destination()
            self._verify_parent_binding()
            _replace_file_write_through(temporary, path)
            after = os.lstat(path)
            final_held = os.fstat(fd)
            if (
                _file_key(after) != key
                or _file_key(final_held) != key
                or after.st_nlink != 1
                or final_held.st_nlink != 1
                or _windows_fd_link_count(fd) != 1
                or _is_link_or_reparse(path, after)
            ):
                raise ProvenanceVerificationError(
                    "publication journal slot pathname changed after durable write"
                )
            _directory_metadata_barrier(self.parent)
        except BaseException as error:
            if temporary is not None and key is not None and os.path.lexists(temporary):
                try:
                    _delete_exact_regular_file(
                        temporary, key, require_single_link=True
                    )
                except BaseException as cleanup_error:
                    error.add_note(
                        "publication journal temporary cleanup also failed: "
                        f"{type(cleanup_error).__name__}: {cleanup_error}"
                    )
            if isinstance(error, (ProvenanceVerificationError, KeyboardInterrupt)):
                raise
            if isinstance(error, OSError):
                raise ProvenanceVerificationError(
                    f"publication journal durable write failed: {error}"
                ) from error
            raise
        finally:
            if fd is not None:
                os.close(fd)
        if committed is None or next_slot_keys is None:
            raise ProvenanceVerificationError(
                "publication journal atomic commit produced no durable state"
            )
        self._slot_keys = next_slot_keys
        self._state = committed
        self._generation = generation

    def begin(self, target: Path, payloads: Mapping[str, bytes]) -> None:
        if self._state is not None:
            raise ProvenanceVerificationError(
                "publication journal already contains a transaction"
            )
        if not isinstance(target, Path) or target.parent.resolve() != self.parent:
            raise ProvenanceVerificationError(
                "publication target is not an exact child of the journal parent"
            )
        target_name = _journal_name(target.name, "target")
        if not isinstance(payloads, Mapping) or not payloads:
            raise ProvenanceVerificationError(
                "publication journal requires exact payloads"
            )
        records = []
        for name in sorted(payloads):
            payload = payloads[name]
            _journal_name(name, "payload")
            if type(payload) is not bytes:
                raise ProvenanceVerificationError(
                    "publication journal payloads must be immutable bytes"
                )
            records.append(
                {
                    "bytes": len(payload),
                    "logicalName": name,
                    "sha256": hashlib.sha256(payload).hexdigest(),
                }
            )
        self._commit(
            {
                "generation": 0,
                "journalFileIds": [],
                "parentFileId": list(self.parent_key),
                "payloads": records,
                "phase": "intent",
                "schema": _PUBLICATION_SCHEMA,
                "staging": None,
                "targetName": target_name,
            }
        )

    def record_staging(self, staging: Path, file_key: tuple[int, int]) -> None:
        if (
            self._state is None
            or self._state["phase"] != "intent"
            or not isinstance(staging, Path)
            or staging.parent.resolve() != self.parent
            or type(file_key) is not tuple
            or len(file_key) != 2
        ):
            raise ProvenanceVerificationError(
                "publication staging receipt cannot be journaled"
            )
        document = dict(self._state)
        document["phase"] = "building"
        document["staging"] = {
            "fileId": list(file_key),
            "files": [],
            "name": _journal_name(staging.name, "staging"),
        }
        self._commit(document)

    def record_file(self, name: str, file_key: tuple[int, int]) -> None:
        if self._state is None or self._state["phase"] != "building":
            raise ProvenanceVerificationError(
                "publication child receipt cannot be journaled"
            )
        staging = dict(self._state["staging"])
        files = list(staging["files"])
        if name in {item["logicalName"] for item in files}:
            raise ProvenanceVerificationError(
                "publication child receipt is duplicated"
            )
        files.append({"fileId": list(file_key), "logicalName": name})
        files.sort(key=lambda item: item["logicalName"])
        staging["files"] = files
        document = dict(self._state)
        document["staging"] = staging
        self._commit(document)

    def _mark(self, expected: str, phase: str) -> None:
        if self._state is None or self._state["phase"] != expected:
            raise ProvenanceVerificationError(
                f"publication journal cannot transition from {expected} to {phase}"
            )
        document = dict(self._state)
        document["phase"] = phase
        self._commit(document)

    def mark_staged(self) -> None:
        if self._state is None or self._state["phase"] != "building":
            raise ProvenanceVerificationError(
                "publication journal has no completed staging graph"
            )
        expected = [item["logicalName"] for item in self._state["payloads"]]
        actual = [item["logicalName"] for item in self._state["staging"]["files"]]
        if actual != expected:
            raise ProvenanceVerificationError(
                "publication journal staging graph is incomplete"
            )
        self._mark("building", "staged")

    def mark_renaming(self) -> None:
        self._mark("staged", "renaming")

    def mark_installed(self) -> None:
        self._mark("renaming", "installed")

    def mark_accepted(self) -> None:
        self._mark("installed", "accepted")

    def _state_paths(self) -> tuple[Path, Path, dict[str, object]]:
        if self._state is None or self._state["staging"] is None:
            raise ProvenanceVerificationError(
                "publication journal has no owned path record"
            )
        staging = self._state["staging"]
        return (
            self.parent / staging["name"],
            self.parent / self._state["targetName"],
            staging,
        )

    @staticmethod
    def _has_directory_key(path: Path, key: tuple[int, int]) -> bool:
        if not os.path.lexists(path):
            return False
        raw = os.lstat(path)
        return (
            not _is_link_or_reparse(path, raw)
            and stat.S_ISDIR(raw.st_mode)
            and _file_key(raw) == key
        )

    @staticmethod
    def _file_keys(staging: dict[str, object]) -> dict[str, tuple[int, int]]:
        return {
            item["logicalName"]: _journal_file_id(item["fileId"], "child")
            for item in staging["files"]
        }

    def _payload_manifest(self) -> dict[str, tuple[int, str]]:
        if self._state is None:
            raise ProvenanceVerificationError(
                "publication journal payload manifest is unavailable"
            )
        return {
            item["logicalName"]: (item["bytes"], item["sha256"])
            for item in self._state["payloads"]
        }

    @staticmethod
    def _read_held_payload(
        fd: int,
        *,
        expected_bytes: int,
        expected_sha256: str,
    ) -> str:
        held = os.fstat(fd)
        if held.st_size != expected_bytes:
            raise ProvenanceVerificationError(
                "held publication payload byte count changed"
            )
        os.lseek(fd, 0, os.SEEK_SET)
        digest = hashlib.sha256()
        remaining = expected_bytes
        while remaining:
            chunk = os.read(fd, min(1024 * 1024, remaining))
            if not chunk:
                raise ProvenanceVerificationError(
                    "held publication payload ended before its manifest byte count"
                )
            digest.update(chunk)
            remaining -= len(chunk)
        if os.read(fd, 1):
            raise ProvenanceVerificationError(
                "held publication payload exceeds its manifest byte count"
            )
        actual = digest.hexdigest()
        if actual != expected_sha256:
            raise ProvenanceVerificationError(
                "held publication payload hash changed"
            )
        return actual

    def _verify_held_graph(
        self,
        path: Path,
        staging: dict[str, object],
        directory_fd: int,
        children: dict[str, int],
        manifest: dict[str, tuple[int, str]],
    ) -> tuple[tuple[str, str], ...]:
        directory_key = _journal_file_id(staging["fileId"], "staging")
        try:
            held_directory = os.fstat(directory_fd)
            path_directory = os.lstat(path)
            names = {entry.name for entry in os.scandir(path)}
        except OSError as error:
            raise ProvenanceVerificationError(
                f"held publication graph became unavailable: {error}"
            ) from error
        if (
            _is_link_or_reparse(path, path_directory)
            or not stat.S_ISDIR(held_directory.st_mode)
            or not stat.S_ISDIR(path_directory.st_mode)
            or _file_key(held_directory) != directory_key
            or _file_key(path_directory) != directory_key
            or names != set(children)
            or names != set(manifest)
        ):
            raise ProvenanceVerificationError(
                "held publication parent identity or exact child names changed"
            )
        expected_keys = self._file_keys(staging)
        digests: list[tuple[str, str]] = []
        for name in sorted(children):
            child = path / name
            fd = children[name]
            try:
                held = os.fstat(fd)
                current = os.lstat(child)
            except OSError as error:
                raise ProvenanceVerificationError(
                    f"held publication child became unavailable: {name}: {error}"
                ) from error
            if (
                _is_link_or_reparse(child, current)
                or not stat.S_ISREG(held.st_mode)
                or not stat.S_ISREG(current.st_mode)
                or _file_key(held) != expected_keys[name]
                or _file_key(current) != expected_keys[name]
                or held.st_nlink != 1
                or current.st_nlink != 1
                or _windows_fd_link_count(fd) != 1
            ):
                raise ProvenanceVerificationError(
                    f"held publication child identity changed: {name}"
                )
            byte_count, sha256 = manifest[name]
            digests.append(
                (
                    name,
                    self._read_held_payload(
                        fd,
                        expected_bytes=byte_count,
                        expected_sha256=sha256,
                    ),
                )
            )
        return tuple(digests)

    def _hold_complete_graph(
        self,
        path: Path,
        staging: dict[str, object],
    ) -> tuple[int, dict[str, int], dict[str, tuple[int, str]]]:
        directory_fd = _open_windows_read_hold(
            path, directory=True, share_write=False
        )
        children: dict[str, int] = {}
        manifest = self._payload_manifest()
        try:
            for name in sorted(manifest):
                children[name] = _open_windows_read_hold(
                    path / name, directory=False, share_write=False
                )
            self._verify_held_graph(
                path, staging, directory_fd, children, manifest
            )
            return directory_fd, children, manifest
        except BaseException:
            for fd in children.values():
                os.close(fd)
            os.close(directory_fd)
            raise

    @staticmethod
    def _close_held_graph(directory_fd: int, children: dict[str, int]) -> None:
        for fd in children.values():
            os.close(fd)
        os.close(directory_fd)

    @staticmethod
    def _verify_accept_result(
        result: object,
        expected: tuple[tuple[str, str], ...],
    ) -> None:
        claimed = getattr(result, "document_sha256", result)
        if type(claimed) is not tuple or claimed != expected:
            raise ProvenanceVerificationError(
                "publication accept result digests do not match final held bytes"
            )

    def _complete_graph(self, path: Path, staging: dict[str, object]) -> bool:
        directory_key = _journal_file_id(staging["fileId"], "staging")
        if not self._has_directory_key(path, directory_key):
            return False
        expected = self._file_keys(staging)
        manifest = self._payload_manifest()
        if set(expected) != set(manifest):
            return False
        try:
            entries = list(os.scandir(path))
        except OSError:
            return False
        if {item.name for item in entries} != set(expected):
            return False
        for entry in entries:
            child = path / entry.name
            try:
                raw = os.lstat(child)
            except OSError:
                return False
            if (
                _is_link_or_reparse(child, raw)
                or not stat.S_ISREG(raw.st_mode)
                or raw.st_nlink != 1
                or _file_key(raw) != expected[entry.name]
            ):
                return False
            byte_count, sha256 = manifest[entry.name]
            try:
                _verify_stable_file(
                    child,
                    logical_name=entry.name,
                    expected_bytes=byte_count,
                    expected_sha256=sha256,
                    capture_content=False,
                )
            except ProvenanceVerificationError:
                return False
        return True

    def _recover_path(self, path: Path, staging: dict[str, object]) -> None:
        directory_key = _journal_file_id(staging["fileId"], "staging")
        if not self._has_directory_key(path, directory_key):
            return
        expected = self._file_keys(staging)
        for entry in sorted(os.scandir(path), key=lambda item: item.name):
            child = path / entry.name
            key = expected.get(entry.name)
            if key is None:
                continue
            raw = os.lstat(child)
            if (
                _is_link_or_reparse(child, raw)
                or not stat.S_ISREG(raw.st_mode)
                or _file_key(raw) != key
            ):
                continue
            _delete_exact_regular_file(child, key)
        with os.scandir(path) as remaining:
            empty = next(remaining, None) is None
        if empty:
            _remove_owned_install_path(
                path,
                expected_file_key=directory_key,
                expected_files={},
            )

    def recover(
        self,
        requested_target: Path,
        accept: Callable[[Path], object],
    ) -> object | None:
        if self._state is None:
            return None
        if (
            not isinstance(requested_target, Path)
            or requested_target.parent.resolve() != self.parent
            or not callable(accept)
        ):
            raise ProvenanceVerificationError(
                "publication recovery contract is malformed"
            )
        if self._state["staging"] is None:
            self.clear()
            return None
        staging_path, target_path, staging = self._state_paths()
        phase = self._state["phase"]
        can_accept = phase in {"renaming", "installed", "accepted"}
        if can_accept and self._complete_graph(target_path, staging):
            held_graph: tuple[
                int,
                dict[str, int],
                dict[str, tuple[int, str]],
            ] | None = None
            accepted = False
            result: object | None = None
            try:
                held_graph = self._hold_complete_graph(target_path, staging)
                directory_fd, children, manifest = held_graph
                expected_digests = self._verify_held_graph(
                    target_path, staging, directory_fd, children, manifest
                )
                result = accept(target_path)
                final_digests = self._verify_held_graph(
                    target_path, staging, directory_fd, children, manifest
                )
                if final_digests != expected_digests:
                    raise ProvenanceVerificationError(
                        "publication payload digests changed during acceptance"
                    )
                self._verify_accept_result(result, final_digests)
                if phase != "accepted":
                    document = dict(self._state)
                    document["phase"] = "accepted"
                    self._commit(document)
                final_digests = self._verify_held_graph(
                    target_path, staging, directory_fd, children, manifest
                )
                self._verify_accept_result(result, final_digests)
                self.clear()
                final_digests = self._verify_held_graph(
                    target_path, staging, directory_fd, children, manifest
                )
                self._verify_accept_result(result, final_digests)
                accepted = True
            except Exception:
                accepted = False
            finally:
                if held_graph is not None:
                    self._close_held_graph(held_graph[0], held_graph[1])
            if accepted:
                return result if requested_target == target_path else None
            if self._state is not None:
                self._recover_path(target_path, staging)
        else:
            self._recover_path(target_path, staging)
        self._recover_path(staging_path, staging)
        self.clear()
        return None

    def clear(self) -> None:
        present_temps = [
            name
            for name in _PUBLICATION_JOURNAL_TEMP_NAMES
            if os.path.lexists(self.parent / name)
        ]
        if present_temps:
            raise ProvenanceVerificationError(
                "refusing to clear while a reserved prepared temporary slot exists: "
                + ", ".join(present_temps)
            )
        for name in _PUBLICATION_JOURNAL_NAMES:
            path = self.parent / name
            if not os.path.lexists(path):
                continue
            expected = self._slot_keys.get(name)
            raw = os.lstat(path)
            if (
                expected is None
                or _file_key(raw) != expected
                or _is_link_or_reparse(path, raw)
                or not stat.S_ISREG(raw.st_mode)
                or raw.st_nlink != 1
            ):
                raise ProvenanceVerificationError(
                    "refusing to clear a replaced publication journal slot"
                )
            _delete_exact_regular_file(path, expected, require_single_link=True)
        _directory_metadata_barrier(self.parent)
        self._state = None
        self._generation = 0
        self._slot_keys.clear()


def _decode_canonical_object(raw: bytes, label: str) -> dict[str, object]:
    if (
        type(raw) is not bytes
        or not raw.endswith(b"\n")
        or b"\r" in raw
        or raw.startswith(b"\xef\xbb\xbf")
    ):
        raise ProvenanceVerificationError(
            f"{label} must be canonical UTF-8 JSON with final LF"
        )
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(f"{label} is not strict UTF-8") from error

    def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ProvenanceVerificationError(
                    f"{label} contains duplicate JSON key {key!r}"
                )
            result[key] = value
        return result

    def reject_constant(value: str) -> object:
        raise ProvenanceVerificationError(
            f"{label} contains forbidden JSON number {value!r}"
        )

    try:
        decoded = json.loads(
            text,
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except ProvenanceVerificationError:
        raise
    except (ValueError, RecursionError) as error:
        raise ProvenanceVerificationError(
            f"{label} is not strict JSON: {error}"
        ) from error
    if type(decoded) is not dict or canonical_json_bytes(decoded) != raw:
        raise ProvenanceVerificationError(
            f"{label} is not a canonical JSON object"
        )
    return decoded


def _read_exact_rooted_bundle(
    directory: Path,
    *,
    expected_profile: str,
    expected_payload_names: tuple[str, ...],
) -> tuple[dict[str, object], tuple[_VerifiedFile, ...]]:
    if (
        not isinstance(directory, Path)
        or type(expected_profile) is not str
        or not expected_profile
        or type(expected_payload_names) is not tuple
        or expected_payload_names != tuple(sorted(set(expected_payload_names)))
        or "bundle-root.json" in expected_payload_names
    ):
        raise ProvenanceVerificationError(
            "rooted bundle readback contract is malformed"
        )
    root_file = _verify_stable_file(
        directory / "bundle-root.json",
        logical_name="bundle-root.json",
        capture_content=True,
        maximum_capture_bytes=4 * 1024 * 1024,
    )
    if root_file.content is None:
        raise ProvenanceVerificationError("bundle root retained bytes are unavailable")
    root = _decode_canonical_object(root_file.content, "bundle root")
    if (
        set(root) != {"files", "profile", "schema"}
        or root.get("profile") != expected_profile
        or root.get("schema") != "flight-alert-exp8-osm-provenance-root-v1"
        or type(root.get("files")) is not list
    ):
        raise ProvenanceVerificationError(
            "bundle root schema or profile is not exact"
        )
    manifest: dict[str, tuple[int, str]] = {}
    for entry in root["files"]:
        if type(entry) is not dict or set(entry) != {
            "bytes",
            "logicalName",
            "sha256",
        }:
            raise ProvenanceVerificationError(
                "bundle root file record has an unexpected exact schema"
            )
        logical_name = entry["logicalName"]
        byte_count = entry["bytes"]
        sha256 = entry["sha256"]
        if (
            type(logical_name) is not str
            or Path(logical_name).name != logical_name
            or logical_name in manifest
            or type(byte_count) is not int
            or byte_count < 0
            or byte_count > 64 * 1024 * 1024
            or type(sha256) is not str
            or _LOWER_SHA256.fullmatch(sha256) is None
        ):
            raise ProvenanceVerificationError(
                "bundle root file record values are not canonical"
            )
        manifest[logical_name] = (byte_count, sha256)
    if tuple(manifest) != expected_payload_names:
        raise ProvenanceVerificationError(
            "bundle root does not describe the exact file set in sorted order"
        )

    expected_payloads: dict[str, bytes] = {
        "bundle-root.json": root_file.content
    }
    for logical_name in expected_payload_names:
        byte_count, sha256 = manifest[logical_name]
        verified = _verify_stable_file(
            directory / logical_name,
            logical_name=logical_name,
            expected_bytes=byte_count,
            expected_sha256=sha256,
            capture_content=True,
            maximum_capture_bytes=64 * 1024 * 1024,
        )
        if verified.content is None:
            raise ProvenanceVerificationError(
                f"bundle payload {logical_name!r} retained bytes are unavailable"
            )
        expected_payloads[logical_name] = verified.content
    snapshot = _verify_exact_directory_payloads(directory, expected_payloads)
    return root, snapshot


__all__: list[str] = []
