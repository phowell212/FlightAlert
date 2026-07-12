from __future__ import annotations
import hashlib, json, os, platform, re, stat, sys, tempfile, ctypes
from collections import Counter
from contextvars import ContextVar
from ctypes import wintypes
from dataclasses import dataclass, field
from datetime import timezone
from email.utils import format_datetime, parsedate_to_datetime
from pathlib import Path
from types import FunctionType, SimpleNamespace
import tools.experiment8.osm_hydro_source as osm_hydro_source
from tools.experiment8.osm_pilot_bundle import (_PUBLICATION_JOURNAL_NAMES,
    _PUBLICATION_JOURNAL_TEMP_NAMES, _PUBLICATION_LOCK_NAME, _PublicationJournal,
    _close_held_input_files, _decode_canonical_object, _directory_metadata_barrier, _hold_verified_input_files as
    _hold_verified_files, _is_link_or_reparse, _journal_created_windows_handle,
    _read_exact_rooted_bundle, _remove_owned_install_path, _verify_exact_directory_payloads, _verify_held_input_bindings)
from tools.experiment8.osm_pilot_common import (ProvenanceVerificationError, _LOWER_SHA256,
    _VERIFICATION_SEAL, _VerifiedFile, _identity, _verified_instance, _verify_stable_file, canonical_json_bytes)
from tools.experiment8.osm_pilot_runtime import (
    PINNED_PYTHON_CACHE_TAG, PINNED_PYTHON_EXECUTABLE_SHA256, PINNED_PYTHON_FLAGS,
    PINNED_PYTHON_IMPLEMENTATION, PINNED_PYTHON_PLATFORM, PINNED_PYTHON_VERSION,
    _CAPTURED_SELECTION_CALLABLE, _CAPTURED_SELECTION_CODE_SHA256,
    _SELECTION_CALLABLE_MODULE, _SELECTION_CALLABLE_QUALNAME, _SELECTOR_LOGICAL_FILENAME,
    _attest_selection_callable_identity, _code_structure_sha256, _compiled_selector_snapshot,
    _current_python_flags, _load_selector_snapshot, _python_runtime_dependency_specs)
_ROOT_IDS_LOGICAL_NAME = "root-ids.txt"
_SELECTION_MATERIAL_LOGICAL_NAME = "selection-material.json"
_BUNDLE_PAYLOAD_NAMES = (
    "provider-final-url.txt", "provider-head.txt", "provider-md5-sidecar.txt", "root-ids.txt",
    "runtime-manifest.json", "selection-binding.json", "selection-command-manifest.json",
    "selection-material.json", "selector-source.py", "source-fileinfo.txt", "source-identity.json")
_ACTIVE_BUNDLE_WRITE_RECEIPTS: ContextVar[object | None] = ContextVar("active_bundle_write_receipts", default=None)
@dataclass
class _OwnedHandle:
    handle: int | None = None; file_key: tuple[int, int] | None = None; created_unresolved: bool = False
@dataclass
class _OwnedStagingDirectory(_OwnedHandle):
    path: Path | None = None; files: dict[str, _OwnedHandle] = field(default_factory=dict); journal: _PublicationJournal | None = field(default=None, repr=False)
class _FileDispositionInfo(ctypes.Structure):
    _fields_ = [("delete_file", ctypes.c_ubyte)]
class _FileIdInfo(ctypes.Structure):
    _fields_ = [("volume_serial_number", ctypes.c_ulonglong), ("file_id", ctypes.c_ubyte * 16)]
class _FileStandardInfo(ctypes.Structure):
    _fields_ = [("allocation", ctypes.c_longlong), ("end", ctypes.c_longlong), ("links", wintypes.DWORD), ("delete_pending", ctypes.c_ubyte), ("directory", ctypes.c_ubyte)]
class _UnicodeString(ctypes.Structure):
    _fields_ = [("length", wintypes.USHORT), ("maximum_length", wintypes.USHORT), ("buffer", wintypes.LPWSTR)]
class _ObjectAttributes(ctypes.Structure):
    _fields_ = [("length", wintypes.ULONG), ("root_directory", wintypes.HANDLE),
        ("object_name", ctypes.POINTER(_UnicodeString)), ("attributes", wintypes.ULONG),
        ("security_descriptor", ctypes.c_void_p), ("security_quality_of_service", ctypes.c_void_p)]
class _IoStatusBlock(ctypes.Structure):
    _fields_ = [("status", ctypes.c_ssize_t), ("information", ctypes.c_size_t)]
def _last_windows_error(label: str) -> ProvenanceVerificationError: return ProvenanceVerificationError(f"{label}: {ctypes.WinError(ctypes.get_last_error())}")
def _windows_handle_delete_pending(handle: int) -> bool:
    information = _FileStandardInfo()
    if not ctypes.WinDLL("kernel32", use_last_error=True).GetFileInformationByHandleEx(
        wintypes.HANDLE(handle), 1, ctypes.byref(information), ctypes.sizeof(information)):
        raise _last_windows_error("owned staging handle state failed")
    return bool(information.delete_pending)
def _set_windows_handle_delete_pending(handle, delete: bool) -> None:
    information = _FileDispositionInfo(int(delete))
    if not ctypes.WinDLL("kernel32", use_last_error=True).SetFileInformationByHandle(handle, 4, ctypes.byref(information), ctypes.sizeof(information)): raise _last_windows_error("owned staging handle disposition failed")
def _verify_windows_handle_single_link(handle: int, label: str) -> None:
    information = _FileStandardInfo()
    if not ctypes.WinDLL("kernel32", use_last_error=True).GetFileInformationByHandleEx(
        wintypes.HANDLE(handle), 1, ctypes.byref(information), ctypes.sizeof(information)):
        raise _last_windows_error(f"{label} native link-count query failed")
    if information.links != 1: raise ProvenanceVerificationError(f"{label} must have exactly one link")
def _close_windows_owned_handle(handle: int, *, delete: bool, directory: bool,
    retain_on_delete_failure: bool = False) -> None:
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    raw_handle = wintypes.HANDLE(handle); reopened = None
    delete_requested, delete_succeeded = delete, not delete
    try:
        if delete and _windows_handle_delete_pending(handle):
            delete = False; delete_succeeded = True
        if delete:
            delete_handle = raw_handle
            if not directory:
                reopen_file = kernel32.ReOpenFile
                reopen_file.restype = wintypes.HANDLE
                reopened = reopen_file(raw_handle, 0x00010000, 0x7, 0x00200000)
                if reopened == ctypes.c_void_p(-1).value:
                    reopened = None
                    raise _last_windows_error("owned staging file reopen failed")
                delete_handle = wintypes.HANDLE(reopened)
            _set_windows_handle_delete_pending(delete_handle, True)
            delete_succeeded = True
    finally:
        pending = sys.exception()
        try:
            if reopened is not None and not kernel32.CloseHandle(
                wintypes.HANDLE(reopened)
            ) and pending is None:
                raise _last_windows_error("owned staging delete handle close failed")
        finally:
            pending = sys.exception()
            retain_raw = (retain_on_delete_failure and delete_requested and
                not delete_succeeded and pending is not None)
            if retain_raw: pending.owned_handle_retained = True
            elif not kernel32.CloseHandle(raw_handle) and pending is None:
                raise _last_windows_error("owned staging handle close failed")
def _windows_directory_identity(handle: int) -> tuple[int, int]:
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    information = _FileIdInfo()
    if not kernel32.GetFileInformationByHandleEx(
        wintypes.HANDLE(handle), 18, ctypes.byref(information), ctypes.sizeof(information)
    ):
        raise _last_windows_error("owned staging handle identity failed")
    return (information.volume_serial_number & 0xFFFFFFFF,
        int.from_bytes(bytes(information.file_id), "little"))
def _nt_create_file(*args) -> int:
    create_file = ctypes.WinDLL("ntdll").NtCreateFile
    create_file.restype = ctypes.c_long
    return int(create_file(*args))
def _acquire_owned_relative_handle(receipt: _OwnedHandle, parent_handle: int,
    name: str, *, directory: bool, create: bool, created_callback=None) -> None:
    if (receipt.handle is not None or (create and receipt.file_key is not None)
        or (not create and receipt.file_key is None) or receipt.created_unresolved):
        raise ProvenanceVerificationError("native ownership receipt state is invalid")
    if created_callback is not None and (not create or not callable(created_callback)):
        raise ProvenanceVerificationError("native creation callback is invalid")
    expected_key = receipt.file_key
    name_buffer = ctypes.create_unicode_buffer(name)
    name_bytes = len(name.encode("utf-16-le"))
    object_name = _UnicodeString(name_bytes,
        name_bytes + ctypes.sizeof(ctypes.c_wchar),
        ctypes.cast(name_buffer, wintypes.LPWSTR))
    attributes = _ObjectAttributes(ctypes.sizeof(_ObjectAttributes),
        wintypes.HANDLE(parent_handle), ctypes.pointer(object_name), 0x40, None, None)
    io_status = _IoStatusBlock()
    raw_handle = wintypes.HANDLE()
    kind = "directory" if directory else "file"
    try:
        status = _nt_create_file(
            ctypes.byref(raw_handle), wintypes.DWORD(
                0x00110081 if directory else (0x00100083 if create else 0x00100081)),
            ctypes.byref(attributes), ctypes.byref(io_status),
            ctypes.POINTER(ctypes.c_longlong)(),
            wintypes.ULONG(0x10 if directory else 0x80),
            wintypes.ULONG(0x7 if directory or create else 0x5),
            wintypes.ULONG(0x2 if create else 0x1), wintypes.ULONG(
                0x00204021 if directory else 0x00200060),
            ctypes.c_void_p(), wintypes.ULONG(0))
        if create and io_status.information == 2 and raw_handle.value is not None:
            receipt.handle = int(raw_handle.value); receipt.created_unresolved = True
        status_code = status & 0xFFFFFFFF
        if create and status_code == 0xC0000035:
            raise FileExistsError(name)
        expected_result = 2 if create else 1
        if status_code != 0 or io_status.information != expected_result:
            raise ProvenanceVerificationError(
                f"native staging {kind} {'creation' if create else 'reopen'} was not exact: "
                f"NTSTATUS 0x{status_code:08x}, result {io_status.information}")
        receipt.handle = int(raw_handle.value)
        if not directory:
            _verify_windows_handle_single_link(receipt.handle,
                "native staging file")
        observed_key = _windows_directory_identity(receipt.handle)
        if not create and observed_key != expected_key:
            raise ProvenanceVerificationError(
                "native staging file identity changed across publication")
        receipt.file_key = observed_key
        if created_callback is not None:
            _journal_created_windows_handle(receipt.handle, directory=directory,
                callback=created_callback, file_key=observed_key)
        receipt.created_unresolved = False
    except BaseException as error:
        if create and io_status.information == 2 and raw_handle.value is not None:
            receipt.handle = int(raw_handle.value); receipt.created_unresolved = True
        retained = False
        if raw_handle.value is not None:
            try:
                _close_windows_owned_handle(int(raw_handle.value), delete=create,
                    directory=directory,
                    retain_on_delete_failure=receipt.created_unresolved)
            except BaseException as cleanup_error:
                retained = bool(getattr(cleanup_error, "owned_handle_retained", False))
                error.add_note(f"pre-receipt cleanup also failed: {cleanup_error}")
        if not retained:
            receipt.handle = None; receipt.created_unresolved = False
            if create:
                receipt.file_key = None
        raise
def _create_owned_staging_directory(receipt: _OwnedStagingDirectory, parent: Path,
    *, prefix: str, suffix: str) -> None:
    if os.name != "nt":
        raise ProvenanceVerificationError("exact staging ownership requires the pinned Windows host")
    if (not isinstance(receipt, _OwnedStagingDirectory) or receipt.path is not None
        or receipt.handle is not None or receipt.file_key is not None
        or receipt.created_unresolved or receipt.files):
        raise ProvenanceVerificationError("staging ownership receipt is not empty")
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True); create_file = kernel32.CreateFileW
    create_file.restype = wintypes.HANDLE
    parent_handle = create_file(str(parent), 0xA0, 0x7, None, 3, 0x02200000, None)
    if parent_handle == ctypes.c_void_p(-1).value:
        raise _last_windows_error("owned staging parent open failed")
    try:
        candidates = tempfile._get_candidate_names()
        for _ in range(tempfile.TMP_MAX):
            name = f"{prefix}{next(candidates)}{suffix}"
            receipt.path = parent / name
            sys.audit("tempfile.mkdtemp", str(receipt.path))
            try:
                callback = (lambda key: receipt.journal.record_staging(receipt.path, key)
                    if receipt.journal is not None else None)
                _acquire_owned_relative_handle(receipt, int(parent_handle), name,
                    directory=True, create=True,
                    created_callback=callback if receipt.journal is not None else None)
                return
            except FileExistsError:
                receipt.path = None; continue
        raise FileExistsError("no usable atomic staging directory name found")
    finally:
        pending = sys.exception()
        if not kernel32.CloseHandle(wintypes.HANDLE(parent_handle)) and pending is None:
            raise _last_windows_error("owned staging parent close failed")
def _write_new_file(path: Path, content: bytes) -> tuple[int, int]:
    active = _ACTIVE_BUNDLE_WRITE_RECEIPTS.get()
    if (not isinstance(active, _OwnedStagingDirectory) or active.path != path.parent
        or active.handle is None or path.name in active.files):
        raise ProvenanceVerificationError("native staging file ownership is unavailable")
    if len(content) > 0xFFFFFFFF:
        raise ProvenanceVerificationError("native staging file payload is too large")
    receipt = _OwnedHandle()
    active.files[path.name] = receipt
    callback = (lambda key: active.journal.record_file(path.name, key)
        if active.journal is not None else None)
    _acquire_owned_relative_handle(receipt, active.handle, path.name, directory=False,
        create=True, created_callback=callback if active.journal is not None else None)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    written = wintypes.DWORD()
    if content:
        buffer = (ctypes.c_char * len(content)).from_buffer_copy(content)
        if not kernel32.WriteFile(wintypes.HANDLE(receipt.handle), buffer,
            len(content), ctypes.byref(written), None):
            raise _last_windows_error("owned staging file write failed")
    if written.value != len(content):
        raise ProvenanceVerificationError("owned staging file write was incomplete")
    if not kernel32.FlushFileBuffers(wintypes.HANDLE(receipt.handle)):
        raise _last_windows_error("owned staging file flush failed")
    assert receipt.file_key is not None
    return receipt.file_key
def _rename_windows_owned_handle(handle: int, target: Path) -> None:
    name = target.name
    class _FileRenameInformation(ctypes.Structure):
        _fields_ = [("replace", ctypes.c_ubyte), ("root", wintypes.HANDLE),
            ("name_bytes", wintypes.ULONG), ("name", ctypes.c_wchar * (len(name) + 1))]
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.restype = wintypes.HANDLE
    parent_handle = create_file(
        str(target.parent), 0xA0, 0x7, None, 3, 0x02200000, None)
    if parent_handle == ctypes.c_void_p(-1).value:
        raise _last_windows_error("owned staging rename parent open failed")
    try:
        information = _FileRenameInformation(0,
            wintypes.HANDLE(parent_handle),
            len(name.encode("utf-16-le")), name)
        io_status = _IoStatusBlock()
        rename_file = ctypes.WinDLL("ntdll").NtSetInformationFile
        rename_file.restype = ctypes.c_long
        status = int(rename_file(wintypes.HANDLE(handle), ctypes.byref(io_status),
            ctypes.byref(information), wintypes.ULONG(ctypes.sizeof(information)),
            wintypes.ULONG(10))) & 0xFFFFFFFF
        if status != 0:
            raise ProvenanceVerificationError(
                f"owned staging directory rename failed: NTSTATUS 0x{status:08x}")
        _directory_metadata_barrier(target.parent)
    finally:
        pending = sys.exception()
        if not kernel32.CloseHandle(wintypes.HANDLE(parent_handle)) and pending is None:
            raise _last_windows_error("owned staging rename parent close failed")
def _publish_owned_directory(receipt: _OwnedStagingDirectory, target: Path) -> None:
    if receipt.handle is None:
        raise ProvenanceVerificationError("owned staging directory handle is unavailable")
    for child in receipt.files.values():
        if child.handle is None:
            raise ProvenanceVerificationError("owned staging file handle is unavailable")
        handle = child.handle
        try:
            _close_windows_owned_handle(handle, delete=False, directory=False)
        finally:
            child.handle = None
    _rename_windows_owned_handle(receipt.handle, target)
    receipt.path = target
    for name, child in sorted(receipt.files.items()):
        _acquire_owned_relative_handle(
            child, receipt.handle, name, directory=False, create=False)
def _read_owned_file(handle: int, expected: bytes) -> str:
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    if not kernel32.SetFilePointerEx(
        wintypes.HANDLE(handle), ctypes.c_longlong(0), None, 0):
        raise _last_windows_error("held staging file rewind failed")
    buffer = ctypes.create_string_buffer(len(expected) + 1)
    read = wintypes.DWORD()
    if not kernel32.ReadFile(wintypes.HANDLE(handle), buffer, len(expected) + 1,
        ctypes.byref(read), None):
        raise _last_windows_error("held staging file read failed")
    content = bytes(buffer[:read.value])
    if content != expected:
        raise ProvenanceVerificationError("held staging file bytes changed")
    return hashlib.sha256(content).hexdigest()
def _verify_owned_handle_payloads(
    receipt: _OwnedStagingDirectory, payloads: dict[str, bytes],
) -> tuple[tuple[str, str], ...]:
    if receipt.handle is None or receipt.file_key is None or set(receipt.files) != set(payloads):
        raise ProvenanceVerificationError("held staging ownership graph is incomplete")
    if _windows_directory_identity(receipt.handle) != receipt.file_key:
        raise ProvenanceVerificationError("held staging directory identity changed")
    result = []
    for name in sorted(payloads):
        child = receipt.files[name]
        if child.handle is None or child.file_key is None:
            raise ProvenanceVerificationError("held staging file receipt is incomplete")
        _verify_windows_handle_single_link(child.handle, "held staging file")
        if _windows_directory_identity(child.handle) != child.file_key:
            raise ProvenanceVerificationError("held staging file identity changed")
        result.append((name, _read_owned_file(child.handle, payloads[name])))
    return tuple(result)
def _verify_owned_path_bindings(receipt: _OwnedStagingDirectory) -> None:
    if receipt.path is None or receipt.handle is None or receipt.file_key is None:
        raise ProvenanceVerificationError("held installed directory receipt is incomplete")
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    open_file = kernel32.CreateFileW
    open_file.restype = wintypes.HANDLE
    path_handle = open_file(str(receipt.path), 0x80, 0x7, None, 3, 0x02200000, None)
    if path_handle == ctypes.c_void_p(-1).value:
        raise _last_windows_error("installed directory binding open failed")
    try:
        if _windows_directory_identity(int(path_handle)) != receipt.file_key:
            raise ProvenanceVerificationError("installed directory FileId is not held")
    finally:
        _close_windows_owned_handle(int(path_handle), delete=False, directory=True)
    for name, child in sorted(receipt.files.items()):
        lookup = _OwnedHandle(file_key=child.file_key)
        try:
            _acquire_owned_relative_handle(
                lookup, receipt.handle, name, directory=False, create=False)
        finally:
            if lookup.handle is not None:
                _close_windows_owned_handle(
                    lookup.handle, delete=False, directory=False)
                lookup.handle = None
from tools.experiment8.osm_closure_probe import (
    BOOST_LIBRARY_SHA256, BOOST_LIBRARY_PATH, MAX_CAPTURE_BYTES, OSMIUM_BINARY_SHA256,
    OSMIUM_BINARY_PATH, PINNED_LIBOSMIUM_VERSION, PINNED_LOCALE, PINNED_OSMIUM_VERSION,
    PINNED_UBUNTU_DISTRIBUTION, PINNED_UBUNTU_RELEASE, RUNTIME_LIBRARY_PATH,
    WSL_EXECUTABLE, run_bounded_process)
from tools.experiment8.osm_hydro_source import (
    MARYLAND_REGIONAL_PROFILE, MARYLAND_SOURCE_BYTES, MARYLAND_SOURCE_MD5,
    MARYLAND_SOURCE_SHA256, MARYLAND_SOURCE_URL, POLICY_SHA256)
_SOURCE_EVIDENCE_BYTES = MARYLAND_SOURCE_BYTES
_SOURCE_EVIDENCE_MD5 = MARYLAND_SOURCE_MD5
_SOURCE_EVIDENCE_SHA256 = MARYLAND_SOURCE_SHA256
WATERWAY_CANDIDATES_PBF_BYTES = 1_636_870
WATERWAY_CANDIDATES_PBF_SHA256 = "7c013339c16b93acaf61672ec1e538f4a3f2c1f90bc7fe7bcfbe4feda2e4c1f1"
WATERWAY_CANDIDATES_XML_BYTES = 39_456_230
WATERWAY_CANDIDATES_XML_SHA256 = "9162124c9d3783aecf718116395b6e10ae85e6a852a5726e27c66cd51af20985"
MARYLAND_FILEINFO_BYTES = 1_478
MARYLAND_FILEINFO_SHA256 = "51736a1fff93bd7703bee036ef260b198e2192b25a2020c9e6ef8b36a8dd0b9f"
MARYLAND_FINAL_URL_BYTES = 72
MARYLAND_FINAL_URL_SHA256 = "91fd488b928115e7b3779b49b1833cfc0f9776fbc8f22597b42039e1c62b839f"
MARYLAND_HEAD_BYTES = 361
MARYLAND_HEAD_SHA256 = "b336ed40610a903f51812db36bcfb9cdd709e4a71e236e497b50ed80fc1f149f"
MARYLAND_MD5_SIDECAR_BYTES = 58
MARYLAND_MD5_SIDECAR_SHA256 = "9783ab9cf51d1b013b0bf7eeb2a7066f17050ba285f7bd0a1c3063933273e322"
MARYLAND_ROOT_IDS_BYTES = 88_831
MARYLAND_ROOT_WAY_COUNT = 7_944
MARYLAND_ROOT_RELATION_COUNT = 102
MARYLAND_ROOT_IDS_SHA256 = "3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7"
MARYLAND_SELECTION_MATERIAL_BYTES = 9_135_827
MARYLAND_SELECTION_MATERIAL_SHA256 = "d49e184605d9123d75970408d1a675288df681f8ed2d0b37e3c3d74bf0afd940"
MARYLAND_BROAD_XML_BYTES = 43_320_020
MARYLAND_BROAD_XML_SHA256 = "f47eaeb4140d18674b850baab9820cf72f7f7d15c2272deb5511ea10aac91473"
MARYLAND_BROAD_ROOT_IDS_BYTES = 88_831
MARYLAND_BROAD_ROOT_IDS_SHA256 = MARYLAND_ROOT_IDS_SHA256
MARYLAND_BROAD_SELECTION_MATERIAL_BYTES = 9_638_521
MARYLAND_BROAD_SELECTION_MATERIAL_SHA256 = "fb9c046a6c65a9fd342a704117ae5c32d6b360b2cd1b272f31c8d68b34e87f74"
MARYLAND_INDEPENDENT_REPORT_BYTES = 105_764
MARYLAND_INDEPENDENT_REPORT_SHA256 = "18d43ab72de95e9f0dc22cf1bcdba60b7396045342fa7875171918789ffbfe95"
MARYLAND_PROVIDER_RESPONSE_DATE_RFC7231 = "Sat, 11 Jul 2026 00:55:30 GMT"
MARYLAND_PROVIDER_RESPONSE_DATE_UTC = "2026-07-11T00:55:30Z"
MARYLAND_PBF_HEADER_TIMESTAMP_UTC = "2026-07-10T20:21:01Z"
LOCAL_DOWNLOAD_UTC_UNAVAILABLE_REASON = ("no contemporaneous local-download UTC capture was retained; "
    "filesystem timestamps and provider/PBF times are not substitutes")
PINNED_WSL_VERSION = 1
PINNED_WSL_ARCHITECTURE = "x86_64"
MAX_ROOT_IDS_BYTES = 8 * 1024 * 1024
MAX_SELECTION_MATERIAL_BYTES = 64 * 1024 * 1024
OSMIUM_DEB_SHA256 = "d8e791ac3558aaafa95d3f6ac7329b15df2fb502bd6babff881e62830e49f906"
BOOST_DEB_SHA256 = "389095c7167251ee73667031a4c0f45083a31347cc95faddbdf5b7d22ac4c774"
_RUNTIME_BASE = "/home/haquilus/flightalert-exp8-tools/osmium-1.11.1"
OSMIUM_DEB_PATH = f"{_RUNTIME_BASE}/debs/osmium-tool_1.11.1-1build2_amd64.deb"
BOOST_DEB_PATH = f"{_RUNTIME_BASE}/debs/libboost-program-options1.71.0_1.71.0-6ubuntu6_amd64.deb"
_CANONICAL_ROOT_ID = re.compile(r"([wr])([1-9][0-9]*)\Z")
_MAX_OSM_OBJECT_ID = (1 << 63) - 1
_SELECTION_MATERIAL_KEYS = frozenset({"candidateCounts", "rejectedRelationCounts",
    "rejectedRelations", "rejectedWayCounts", "rejectedWays", "rootIdsSha256",
    "roots", "schema", "selectedCounts"})
_FORBIDDEN_SELECTION_PROVENANCE_FIELDS = frozenset({"candidateXmlSha256", "policySha256",
    "profile", "provenance", "runtimeManifestSha256", "selectionCommandManifestSha256",
    "source", "sourceIdentitySha256", "waterwayCandidatesPbfSha256"})
_INDEPENDENT_REPORT_KEYS = frozenset({"broadInput", "candidateCounts", "candidateEnvelope",
    "profile", "rejectedRelationCounts", "rejectedWayCounts", "rootIds", "schema",
    "selectedCounts", "selectedRootIds", "selectionMaterial", "verified"})
def _attest_selector_snapshot_lock_globals(
    snapshot_selector_callable: FunctionType,
) -> None:
    if type(snapshot_selector_callable) is not FunctionType:
        raise ProvenanceVerificationError(
            "selector snapshot global attestation requires an exact function"
        )
    snapshot_globals = snapshot_selector_callable.__globals__
    lock_names = (
        "MARYLAND_SOURCE_URL",
        "MARYLAND_SOURCE_BYTES",
        "MARYLAND_SOURCE_MD5",
        "MARYLAND_SOURCE_SHA256",
        "MARYLAND_REGIONAL_PROFILE",
        "POLICY_SHA256",
    )
    for name in lock_names:
        wrapper_value = globals()[name]
        imported_value = getattr(osm_hydro_source, name, object())
        snapshot_value = snapshot_globals.get(name, object())
        if (
            type(imported_value) is not type(wrapper_value)
            or type(snapshot_value) is not type(wrapper_value)
            or imported_value != wrapper_value
            or snapshot_value != wrapper_value
        ):
            raise ProvenanceVerificationError(
                "selector snapshot global does not exactly match the wrapper source "
                f"lock {name}"
            )
@dataclass(frozen=True, slots=True)
class LocalCodeRuntimeEvidence:
    selector_file: _VerifiedFile = field(repr=False)
    python_file: _VerifiedFile = field(repr=False)
    python_dependencies: tuple[_VerifiedFile, ...] = field(repr=False)
    selector_callable: object = field(repr=False)
    selector_callable_source_path: Path = field(repr=False)
    selector_callable_module: str
    selector_callable_qualname: str
    selector_callable_code_sha256: str
    selector_module: str
    selector_sha256: str
    policy_sha256: str
    python_executable_sha256: str
    python_version: str
    python_implementation: str
    python_platform: str
    python_cache_tag: str
    python_flags: tuple[tuple[str, int | bool], ...]
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "local runtime evidence must be created by live verification"
            )
        if (
            not isinstance(self.selector_file, _VerifiedFile)
            or not isinstance(self.python_file, _VerifiedFile)
            or type(self.python_dependencies) is not tuple
            or not self.python_dependencies
            or not all(
                isinstance(item, _VerifiedFile)
                for item in self.python_dependencies
            )
            or self.selector_callable is not _CAPTURED_SELECTION_CALLABLE
            or self.selector_callable_source_path != self.selector_file.path
            or self.selector_callable_module != _SELECTION_CALLABLE_MODULE
            or self.selector_callable_qualname != _SELECTION_CALLABLE_QUALNAME
            or self.selector_callable_code_sha256
            != _CAPTURED_SELECTION_CODE_SHA256
            or self.selector_module != "tools.experiment8.osm_hydro_source"
            or self.selector_sha256 != self.selector_file.sha256
            or self.policy_sha256 != POLICY_SHA256
            or self.python_executable_sha256 != self.python_file.sha256
            or self.python_version != PINNED_PYTHON_VERSION
            or self.python_implementation != PINNED_PYTHON_IMPLEMENTATION
            or self.python_platform != PINNED_PYTHON_PLATFORM
            or self.python_cache_tag != PINNED_PYTHON_CACHE_TAG
            or self.python_flags != PINNED_PYTHON_FLAGS
        ):
            raise ProvenanceVerificationError(
                "local runtime evidence fields are inconsistent with verified files/locks"
            )
        expected_dependency_facts = tuple(
            (logical_name, expected_sha256)
            for logical_name, _, expected_sha256 in _python_runtime_dependency_specs()
        )
        actual_dependency_facts = tuple(
            (item.logical_name, item.sha256) for item in self.python_dependencies
        )
        if actual_dependency_facts != expected_dependency_facts:
            raise ProvenanceVerificationError(
                "local runtime dependencies do not match their portable exact locks"
            )
def _verify_python_runtime_dependencies(
    specs: tuple[tuple[str, Path, str], ...],
) -> tuple[_VerifiedFile, ...]:
    if type(specs) is not tuple or not specs:
        raise ProvenanceVerificationError(
            "Python runtime dependency specification is unavailable"
        )
    logical_names: list[str] = []
    for item in specs:
        if type(item) is not tuple or len(item) != 3:
            raise ProvenanceVerificationError(
                "Python runtime dependency specification is malformed"
            )
        logical_name, path, expected_sha256 = item
        if (
            type(logical_name) is not str
            or not logical_name
            or logical_name.startswith(("/", "\\"))
            or "\\" in logical_name
            or ":" in logical_name
            or any(part in {"", ".", ".."} for part in logical_name.split("/"))
            or not isinstance(path, Path)
            or _LOWER_SHA256.fullmatch(expected_sha256) is None
        ):
            raise ProvenanceVerificationError(
                "Python runtime dependency requires a portable logical name, "
                "Path, and canonical SHA-256"
            )
        logical_names.append(logical_name)
    if logical_names != sorted(set(logical_names)):
        raise ProvenanceVerificationError(
            "Python runtime dependency logical names must be unique and sorted"
        )
    def verify_pass(label: str) -> tuple[_VerifiedFile, ...]:
        del label
        return tuple(
            _verify_stable_file(
                path,
                logical_name=logical_name,
                expected_sha256=expected_sha256,
            )
            for logical_name, path, expected_sha256 in specs
        )
    first = verify_pass("initial snapshot")
    second = verify_pass("post-snapshot replay")
    if first != second:
        raise ProvenanceVerificationError(
            "Python runtime dependencies changed during attestation"
        )
    return first
def verify_local_code_runtime() -> LocalCodeRuntimeEvidence:
    selector_path = _attest_selection_callable_identity()
    selector = _verify_stable_file(
        selector_path,
        logical_name="imported osm_hydro_source module",
        capture_content=True,
        maximum_capture_bytes=4 * 1024 * 1024,
    )
    python = _verify_stable_file(
        Path(sys.executable),
        logical_name="current Python executable",
        expected_sha256=PINNED_PYTHON_EXECUTABLE_SHA256,
    )
    dependency_specs = _python_runtime_dependency_specs()
    python_dependencies = _verify_python_runtime_dependencies(dependency_specs)
    actual_version = platform.python_version()
    actual_implementation = platform.python_implementation()
    actual_platform = platform.platform()
    actual_cache_tag = sys.implementation.cache_tag
    actual_flags = _current_python_flags()
    policy_bytes = osm_hydro_source.canonical_policy_bytes()
    if not isinstance(policy_bytes, bytes):
        raise ProvenanceVerificationError(
            "imported osm_hydro_source returned non-byte canonical policy evidence"
        )
    actual_policy_sha256 = hashlib.sha256(policy_bytes).hexdigest()
    if (
        actual_policy_sha256 != POLICY_SHA256
        or actual_policy_sha256 != osm_hydro_source.POLICY_SHA256
    ):
        raise ProvenanceVerificationError(
            "imported osm_hydro_source policy SHA-256 does not match the lock"
        )
    if actual_version != PINNED_PYTHON_VERSION:
        raise ProvenanceVerificationError(
            f"Python version mismatch: expected {PINNED_PYTHON_VERSION}, got {actual_version}"
        )
    if actual_implementation != PINNED_PYTHON_IMPLEMENTATION:
        raise ProvenanceVerificationError(
            "Python implementation mismatch: "
            f"expected {PINNED_PYTHON_IMPLEMENTATION}, got {actual_implementation}"
        )
    if actual_platform != PINNED_PYTHON_PLATFORM:
        raise ProvenanceVerificationError(
            f"Python platform mismatch: expected {PINNED_PYTHON_PLATFORM}, got {actual_platform}"
        )
    if actual_cache_tag != PINNED_PYTHON_CACHE_TAG:
        raise ProvenanceVerificationError(
            "Python cache tag mismatch: "
            f"expected {PINNED_PYTHON_CACHE_TAG}, got {actual_cache_tag}"
        )
    if actual_flags != PINNED_PYTHON_FLAGS:
        raise ProvenanceVerificationError(
            "Python interpreter flags do not match the deterministic runtime lock"
        )
    if selector.content is None:
        raise ProvenanceVerificationError(
            "retained selector source bytes are unavailable for code attestation"
        )
    snapshot_selector_callable = _load_selector_snapshot(
        selector.content,
        selector.path,
        selector.sha256,
    )
    _attest_selector_snapshot_lock_globals(snapshot_selector_callable)
    snapshot_policy_callable = snapshot_selector_callable.__globals__.get(
        "canonical_policy_bytes"
    )
    if type(snapshot_policy_callable) is not FunctionType:
        raise ProvenanceVerificationError(
            "isolated selector snapshot has no exact canonical policy callable"
        )
    snapshot_policy_bytes = snapshot_policy_callable()
    if (
        type(snapshot_policy_bytes) is not bytes
        or hashlib.sha256(snapshot_policy_bytes).hexdigest()
        != actual_policy_sha256
    ):
        raise ProvenanceVerificationError(
            "isolated selector snapshot policy does not match verified policy bytes"
        )
    compiled_selector_code_sha256 = _CAPTURED_SELECTION_CODE_SHA256
    try:
        selector_after = _verify_stable_file(
            selector.path,
            logical_name="imported osm_hydro_source module post-compilation",
            expected_bytes=selector.bytes,
            expected_sha256=selector.sha256,
        )
    except ProvenanceVerificationError as error:
        raise ProvenanceVerificationError(
            "selector source changed during selector source attestation: "
            f"{error}"
        ) from error
    if (
        selector_after.path != selector.path
        or selector_after.identity != selector.identity
        or selector_after.bytes != selector.bytes
        or selector_after.sha256 != selector.sha256
    ):
        raise ProvenanceVerificationError(
            "selector source changed during selector source attestation"
        )
    if _attest_selection_callable_identity() != selector.path:
        raise ProvenanceVerificationError(
            "selection callable source identity changed during local attestation"
        )
    try:
        python_after = _verify_stable_file(
            python.path,
            logical_name="current Python executable post-attestation",
            expected_bytes=python.bytes,
            expected_sha256=python.sha256,
        )
    except ProvenanceVerificationError as error:
        raise ProvenanceVerificationError(
            f"Python executable changed during local attestation: {error}"
        ) from error
    if (
        python_after.path != python.path
        or python_after.identity != python.identity
        or python_after.sha256 != python.sha256
    ):
        raise ProvenanceVerificationError(
            "Python executable changed during local attestation"
        )
    dependencies_after = _verify_python_runtime_dependencies(dependency_specs)
    if dependencies_after != python_dependencies:
        raise ProvenanceVerificationError(
            "Python runtime dependencies changed during local attestation"
        )
    return _verified_instance(
        LocalCodeRuntimeEvidence,
        selector_file=selector,
        python_file=python,
        python_dependencies=python_dependencies,
        selector_callable=_CAPTURED_SELECTION_CALLABLE,
        selector_callable_source_path=selector_path,
        selector_callable_module=_SELECTION_CALLABLE_MODULE,
        selector_callable_qualname=_SELECTION_CALLABLE_QUALNAME,
        selector_callable_code_sha256=compiled_selector_code_sha256,
        selector_module="tools.experiment8.osm_hydro_source",
        selector_sha256=selector.sha256,
        policy_sha256=actual_policy_sha256,
        python_executable_sha256=python.sha256,
        python_version=actual_version,
        python_implementation=actual_implementation,
        python_platform=actual_platform,
        python_cache_tag=actual_cache_tag,
        python_flags=actual_flags,
    )
@dataclass(frozen=True, slots=True)
class VerifiedMarylandSource:
    file: _VerifiedFile = field(repr=False)
    bytes: int
    md5: str
    sha256: str
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "Maryland source evidence must be created by live verification"
            )
        if (
            not isinstance(self.file, _VerifiedFile)
            or self.bytes != self.file.bytes
            or self.md5 != self.file.md5
            or self.sha256 != self.file.sha256
            or self.bytes != _SOURCE_EVIDENCE_BYTES
            or self.md5 != _SOURCE_EVIDENCE_MD5
            or self.sha256 != _SOURCE_EVIDENCE_SHA256
        ):
            raise ProvenanceVerificationError(
                "Maryland source evidence fields are inconsistent with the current lock"
            )
@dataclass(frozen=True, slots=True)
class VerifiedCandidateFiles:
    pbf_file: _VerifiedFile = field(repr=False)
    xml_file: _VerifiedFile = field(repr=False)
    pbf_bytes: int
    pbf_sha256: str
    xml_bytes: int
    xml_sha256: str
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "candidate evidence must be created by live verification"
            )
        if (
            not isinstance(self.pbf_file, _VerifiedFile)
            or not isinstance(self.xml_file, _VerifiedFile)
            or self.pbf_bytes != self.pbf_file.bytes
            or self.pbf_sha256 != self.pbf_file.sha256
            or self.xml_bytes != self.xml_file.bytes
            or self.xml_sha256 != self.xml_file.sha256
            or self.pbf_bytes != WATERWAY_CANDIDATES_PBF_BYTES
            or self.pbf_sha256 != WATERWAY_CANDIDATES_PBF_SHA256
            or self.xml_bytes != WATERWAY_CANDIDATES_XML_BYTES
            or self.xml_sha256 != WATERWAY_CANDIDATES_XML_SHA256
            or self.pbf_file.path == self.xml_file.path
        ):
            raise ProvenanceVerificationError(
                "candidate evidence fields are inconsistent with verified files/locks"
            )
@dataclass(frozen=True, slots=True)
class VerifiedIndependentSelectionEvidence:
    broad_xml_file: _VerifiedFile = field(repr=False)
    root_ids_file: _VerifiedFile = field(repr=False)
    selection_material_file: _VerifiedFile = field(repr=False)
    report_file: _VerifiedFile = field(repr=False)
    broad_xml_sha256: str
    root_ids_sha256: str
    selection_material_sha256: str
    report_sha256: str
    selected_way_count: int
    selected_relation_count: int
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "independent selection evidence must be created by live verification"
            )
        files = (
            self.broad_xml_file,
            self.root_ids_file,
            self.selection_material_file,
            self.report_file,
        )
        if not all(isinstance(item, _VerifiedFile) for item in files):
            raise ProvenanceVerificationError(
                "independent selection evidence does not contain verified files"
            )
        if len({item.path for item in files}) != len(files):
            raise ProvenanceVerificationError(
                "independent selection evidence files must be distinct"
            )
        if (
            self.broad_xml_file.bytes != MARYLAND_BROAD_XML_BYTES
            or self.broad_xml_sha256 != self.broad_xml_file.sha256
            or self.broad_xml_sha256 != MARYLAND_BROAD_XML_SHA256
            or self.root_ids_file.bytes != MARYLAND_BROAD_ROOT_IDS_BYTES
            or self.root_ids_sha256 != self.root_ids_file.sha256
            or self.root_ids_sha256 != MARYLAND_BROAD_ROOT_IDS_SHA256
            or self.selection_material_file.bytes
            != MARYLAND_BROAD_SELECTION_MATERIAL_BYTES
            or self.selection_material_sha256
            != self.selection_material_file.sha256
            or self.selection_material_sha256
            != MARYLAND_BROAD_SELECTION_MATERIAL_SHA256
            or self.report_file.bytes != MARYLAND_INDEPENDENT_REPORT_BYTES
            or self.report_sha256 != self.report_file.sha256
            or self.report_sha256 != MARYLAND_INDEPENDENT_REPORT_SHA256
            or type(self.selected_way_count) is not int
            or type(self.selected_relation_count) is not int
            or self.selected_way_count < 0
            or self.selected_relation_count < 0
        ):
            raise ProvenanceVerificationError(
                "independent selection evidence fields are inconsistent with exact locks"
            )
@dataclass(frozen=True, slots=True)
class VerifiedProviderCaptures:
    final_url_file: _VerifiedFile = field(repr=False)
    head_file: _VerifiedFile = field(repr=False)
    md5_sidecar_file: _VerifiedFile = field(repr=False)
    fileinfo_file: _VerifiedFile = field(repr=False)
    final_url: str
    content_length: int
    md5: str
    provider_response_date_utc: str
    pbf_header_timestamp_utc: str
    local_download_utc: None
    local_download_status: str
    local_download_unavailable_reason: str
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "provider evidence must be created by live verification"
            )
        files = (
            self.final_url_file,
            self.head_file,
            self.md5_sidecar_file,
            self.fileinfo_file,
        )
        if not all(isinstance(item, _VerifiedFile) for item in files):
            raise ProvenanceVerificationError(
                "provider evidence does not contain verified files"
            )
        if (
            self.final_url != MARYLAND_SOURCE_URL
            or self.final_url_file.content
            != (MARYLAND_SOURCE_URL + "\r\n").encode("ascii")
            or self.final_url_file.bytes != MARYLAND_FINAL_URL_BYTES
            or self.final_url_file.sha256 != MARYLAND_FINAL_URL_SHA256
        ):
            raise ProvenanceVerificationError(
                "provider final URL claim is inconsistent with retained evidence"
            )
        if (
            self.head_file.bytes != MARYLAND_HEAD_BYTES
            or self.head_file.sha256 != MARYLAND_HEAD_SHA256
            or self.md5_sidecar_file.bytes != MARYLAND_MD5_SIDECAR_BYTES
            or self.md5_sidecar_file.sha256 != MARYLAND_MD5_SIDECAR_SHA256
        ):
            raise ProvenanceVerificationError(
                "provider HEAD/MD5 captures do not match their current exact locks"
            )
        head = self.head_file.content or b""
        sidecar = self.md5_sidecar_file.content or b""
        expected_length = str(self.content_length).encode("ascii")
        if re.findall(
            rb"^content-length:\s*([0-9]+)\s*\r?$",
            head,
            re.IGNORECASE | re.MULTILINE,
        ) != [expected_length]:
            raise ProvenanceVerificationError(
                "provider content-length claim is inconsistent with retained evidence"
            )
        if re.findall(
            rb"^Date: ([^\r\n]+)\r?$",
            head,
            re.MULTILINE,
        ) != [MARYLAND_PROVIDER_RESPONSE_DATE_RFC7231.encode("ascii")]:
            raise ProvenanceVerificationError(
                "provider HEAD Date claim is inconsistent with retained evidence"
            )
        sidecar_match = re.fullmatch(
            rb"([0-9a-f]{32})  maryland-260710\.osm\.pbf\r?\n", sidecar
        )
        if sidecar_match is None or sidecar_match.group(1).decode("ascii") != self.md5:
            raise ProvenanceVerificationError(
                "provider MD5 claim is inconsistent with retained evidence"
            )
        if (
            self.fileinfo_file.bytes != MARYLAND_FILEINFO_BYTES
            or self.fileinfo_file.sha256 != MARYLAND_FILEINFO_SHA256
            or self.provider_response_date_utc
            != MARYLAND_PROVIDER_RESPONSE_DATE_UTC
            or self.pbf_header_timestamp_utc
            != MARYLAND_PBF_HEADER_TIMESTAMP_UTC
            or self.local_download_utc is not None
            or self.local_download_status != "unavailable"
            or self.local_download_unavailable_reason
            != LOCAL_DOWNLOAD_UTC_UNAVAILABLE_REASON
        ):
            raise ProvenanceVerificationError(
                "provider fileinfo capture does not match its current exact lock"
            )
def verify_maryland_source_file(path: str | Path) -> VerifiedMarylandSource:
    verified = _verify_stable_file(
        path,
        logical_name="locked Maryland PBF",
        expected_bytes=_SOURCE_EVIDENCE_BYTES,
        expected_sha256=_SOURCE_EVIDENCE_SHA256,
        expected_md5=_SOURCE_EVIDENCE_MD5,
    )
    if verified.md5 is None:
        raise ProvenanceVerificationError("locked Maryland PBF MD5 was not computed")
    return _verified_instance(
        VerifiedMarylandSource,
        file=verified,
        bytes=verified.bytes,
        md5=verified.md5,
        sha256=verified.sha256,
    )
def verify_candidate_files(
    pbf_path: str | Path,
    xml_path: str | Path,
) -> VerifiedCandidateFiles:
    pbf = _verify_stable_file(
        pbf_path,
        logical_name="waterway candidate PBF",
        expected_bytes=WATERWAY_CANDIDATES_PBF_BYTES,
        expected_sha256=WATERWAY_CANDIDATES_PBF_SHA256,
    )
    xml = _verify_stable_file(
        xml_path,
        logical_name="waterway candidate XML",
        expected_bytes=WATERWAY_CANDIDATES_XML_BYTES,
        expected_sha256=WATERWAY_CANDIDATES_XML_SHA256,
    )
    if pbf.path == xml.path:
        raise ProvenanceVerificationError("candidate PBF and XML must be different files")
    return _verified_instance(
        VerifiedCandidateFiles,
        pbf_file=pbf,
        xml_file=xml,
        pbf_bytes=pbf.bytes,
        pbf_sha256=pbf.sha256,
        xml_bytes=xml.bytes,
        xml_sha256=xml.sha256,
    )
def _capture_text(file: _VerifiedFile, label: str) -> str:
    if file.content is None:
        raise ProvenanceVerificationError(f"{label} capture bytes are unavailable")
    try:
        return file.content.decode("ascii", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(f"{label} is not canonical ASCII evidence") from error
def _require_fileinfo_line(
    lines: list[str],
    index: int,
    expected: str,
    label: str,
) -> None:
    if lines[index] != expected:
        raise ProvenanceVerificationError(
            f"source fileinfo {label} mismatch: expected exactly "
            f"{expected!r}, got {lines[index]!r}"
        )
def _parse_fileinfo_bounds_e7(
    raw: str,
    *,
    label: str,
) -> tuple[int, int, int, int]:
    match = re.fullmatch(
        r"\((-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?),"
        r"(-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?),"
        r"(-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?),"
        r"(-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?)\)",
        raw,
    )
    if match is None:
        raise ProvenanceVerificationError(
            f"source fileinfo {label} bounds are not canonical plain decimals"
        )
    try:
        return (
            osm_hydro_source.parse_e7(match.group(1), axis="longitude"),
            osm_hydro_source.parse_e7(match.group(2), axis="latitude"),
            osm_hydro_source.parse_e7(match.group(3), axis="longitude"),
            osm_hydro_source.parse_e7(match.group(4), axis="latitude"),
        )
    except ValueError as error:
        raise ProvenanceVerificationError(
            f"source fileinfo {label} bounds are invalid: {error}"
        ) from error
def _validate_fileinfo_capture(text: str, source_bytes: int) -> None:
    if not text.endswith("\n") or "\r" in text:
        raise ProvenanceVerificationError(
            "source fileinfo must be canonical ASCII with LF line endings"
        )
    lines = text[:-1].split("\n")
    if len(lines) != 43:
        raise ProvenanceVerificationError(
            "source fileinfo does not have the exact osmium 1.11.1 extended shape"
        )
    fixed_lines = (
        (0, "File:", "file section"),
        (
            1,
            "  Name: /mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/source/"
            "maryland-260710.osm.pbf",
            "source name",
        ),
        (2, "  Format: PBF", "format"),
        (3, "  Compression: none", "compression"),
        (4, f"  Size: {source_bytes}", "size"),
        (5, "Header:", "header section"),
        (6, "  Bounding boxes:", "header bounds"),
        (8, "  With history: no", "history flag"),
        (9, "  Options:", "header options"),
        (10, "    generator=osmium/1.16.0", "generator"),
        (
            11,
            "    osmosis_replication_base_url=https://download.geofabrik.de/"
            "north-america/us/maryland-updates",
            "replication base URL",
        ),
        (12, "    osmosis_replication_sequence_number=4845", "replication sequence"),
        (
            13,
            "    osmosis_replication_timestamp=2026-07-10T20:21:01Z",
            "replication timestamp",
        ),
        (14, "    pbf_dense_nodes=true", "dense-node option"),
        (
            15,
            "    pbf_optional_feature_0=Sort.Type_then_ID",
            "sorting feature",
        ),
        (16, "    sorting=Type_then_ID", "sorting option"),
        (17, "    timestamp=2026-07-10T20:21:01Z", "header timestamp"),
        (18, "Data:", "data section"),
        (20, "  Timestamps:", "timestamp section"),
        (21, "    First: 2005-11-30T04:26:15Z", "first timestamp"),
        (22, "    Last: 2026-07-10T19:30:40Z", "last timestamp"),
        (23, "  Objects ordered (by type and id): yes", "object ordering"),
        (24, "  Multiple versions of same object: no", "multiple-version flag"),
        (25, "  CRC32: c452ca02", "CRC32"),
        (26, "  Number of changesets: 0", "changeset count"),
        (27, "  Number of nodes: 27286527", "node count"),
        (28, "  Number of ways: 3178490", "way count"),
        (29, "  Number of relations: 27883", "relation count"),
        (30, "  Smallest changeset ID: 0", "minimum changeset ID"),
        (31, "  Smallest node ID: 272594", "minimum node ID"),
        (32, "  Smallest way ID: 4268721", "minimum way ID"),
        (33, "  Smallest relation ID: 50372", "minimum relation ID"),
        (34, "  Largest changeset ID: 0", "maximum changeset ID"),
        (35, "  Largest node ID: 14003334163", "maximum node ID"),
        (36, "  Largest way ID: 1537294226", "maximum way ID"),
        (37, "  Largest relation ID: 21084788", "maximum relation ID"),
        (
            38,
            "  All objects have following metadata attributes: version+timestamp",
            "all-object metadata",
        ),
        (
            39,
            "  Some objects have following metadata attributes: version+timestamp",
            "some-object metadata",
        ),
        (
            40,
            "  Number of buffers: 40176 (avg 758 objects per buffer)",
            "buffer count",
        ),
        (
            41,
            "  Sum of buffer sizes: 2548206032 (2.548 GB)",
            "buffer sizes",
        ),
        (
            42,
            "  Sum of buffer capacities: 2635857920 (2.635 GB, 97% full)",
            "buffer capacities",
        ),
    )
    for index, expected, label in fixed_lines:
        _require_fileinfo_line(lines, index, expected, label)
    header_prefix = "    "
    if not lines[7].startswith(header_prefix):
        raise ProvenanceVerificationError(
            "source fileinfo header bounds indentation is not exact"
        )
    header_bounds = _parse_fileinfo_bounds_e7(
        lines[7][len(header_prefix) :],
        label="header",
    )
    if header_bounds != (-794_885_700, 378_839_600, -749_540_790, 401_705_400):
        raise ProvenanceVerificationError(
            f"source fileinfo header bounds mismatch: got {header_bounds!r}"
        )
    prefix = "  Bounding box: "
    if not lines[19].startswith(prefix):
        raise ProvenanceVerificationError(
            "source fileinfo data bounds line is not exact"
        )
    data_bounds = _parse_fileinfo_bounds_e7(
        lines[19][len(prefix) :],
        label="data",
    )
    if data_bounds != (-801_318_095, 373_264_889, -749_849_355, 411_476_119):
        raise ProvenanceVerificationError(
            f"source fileinfo data bounds mismatch: got {data_bounds!r}"
        )
def _parse_provider_response_date_utc(head_text: str) -> str:
    values = re.findall(r"^Date: ([^\r\n]+)\r?$", head_text, re.MULTILINE)
    if values != [MARYLAND_PROVIDER_RESPONSE_DATE_RFC7231]:
        raise ProvenanceVerificationError(
            "provider HEAD Date must occur exactly once as the accepted canonical "
            f"RFC 7231 value {MARYLAND_PROVIDER_RESPONSE_DATE_RFC7231!r}"
        )
    raw = values[0]
    try:
        parsed = parsedate_to_datetime(raw)
    except (TypeError, ValueError, OverflowError) as error:
        raise ProvenanceVerificationError(
            f"provider HEAD Date is not a valid UTC HTTP date: {error}"
        ) from error
    if parsed.tzinfo is None:
        raise ProvenanceVerificationError(
            "provider HEAD Date has no explicit UTC timezone"
        )
    utc = parsed.astimezone(timezone.utc)
    if format_datetime(utc, usegmt=True) != raw:
        raise ProvenanceVerificationError(
            "provider HEAD Date is not canonical RFC 7231 UTC"
        )
    normalized = utc.strftime("%Y-%m-%dT%H:%M:%SZ")
    if normalized != MARYLAND_PROVIDER_RESPONSE_DATE_UTC:
        raise ProvenanceVerificationError(
            "provider HEAD Date does not match the accepted acquisition response instant"
        )
    return normalized
def _verify_provider_capture_files(
    captures: tuple[_VerifiedFile, ...], *, source_bytes: int, source_md5: str
) -> VerifiedProviderCaptures:
    if (
        type(captures) is not tuple
        or len(captures) != 4
        or not all(isinstance(item, _VerifiedFile) for item in captures)
        or type(source_bytes) is not int
        or source_bytes <= 0
        or type(source_md5) is not str
        or re.fullmatch(r"[0-9a-f]{32}", source_md5) is None
    ):
        raise ProvenanceVerificationError("provider capture replay inputs are invalid")
    final_url_file, head_file, sidecar_file, fileinfo_file = captures
    final_url_text = _capture_text(final_url_file, "provider final URL")
    if final_url_text != MARYLAND_SOURCE_URL + "\r\n":
        raise ProvenanceVerificationError(
            f"provider final URL mismatch: expected {MARYLAND_SOURCE_URL!r}"
        )
    head_text = _capture_text(head_file, "provider HEAD response")
    statuses = re.findall(r"^HTTP/[^\s]+\s+([0-9]{3})(?:\s+.*)?\r?$", head_text, re.MULTILINE)
    if statuses != ["200"]:
        raise ProvenanceVerificationError(
            f"provider HEAD must contain exactly one HTTP 200 response, got {statuses!r}"
        )
    lengths = re.findall(
        r"^content-length:\s*([0-9]+)\s*\r?$",
        head_text,
        re.IGNORECASE | re.MULTILINE,
    )
    if lengths != [str(source_bytes)]:
        raise ProvenanceVerificationError(
            f"provider HEAD content length mismatch: expected {source_bytes}, got {lengths!r}"
        )
    provider_response_date_utc = _parse_provider_response_date_utc(head_text)
    sidecar_text = _capture_text(sidecar_file, "provider MD5 sidecar")
    sidecar_match = re.fullmatch(
        r"([0-9a-f]{32})  maryland-260710\.osm\.pbf\r?\n",
        sidecar_text,
    )
    if sidecar_match is None or sidecar_match.group(1) != source_md5:
        raise ProvenanceVerificationError(
            "provider MD5 sidecar does not bind the verified Maryland source"
        )
    fileinfo_text = _capture_text(fileinfo_file, "source fileinfo transcript")
    _validate_fileinfo_capture(fileinfo_text, source_bytes)
    return _verified_instance(
        VerifiedProviderCaptures,
        final_url_file=final_url_file,
        head_file=head_file,
        md5_sidecar_file=sidecar_file,
        fileinfo_file=fileinfo_file,
        final_url=MARYLAND_SOURCE_URL,
        content_length=source_bytes,
        md5=source_md5,
        provider_response_date_utc=provider_response_date_utc,
        pbf_header_timestamp_utc=MARYLAND_PBF_HEADER_TIMESTAMP_UTC,
        local_download_utc=None,
        local_download_status="unavailable",
        local_download_unavailable_reason=(
            LOCAL_DOWNLOAD_UTC_UNAVAILABLE_REASON
        ),
    )
def verify_provider_captures(
    final_url_path: str | Path,
    head_path: str | Path,
    md5_sidecar_path: str | Path,
    fileinfo_path: str | Path,
    *,
    source: VerifiedMarylandSource,
) -> VerifiedProviderCaptures:
    if not isinstance(source, VerifiedMarylandSource):
        raise ProvenanceVerificationError("provider evidence requires a verified source")
    captures = (
        _verify_stable_file(
            final_url_path,
            logical_name="provider final URL",
            expected_bytes=MARYLAND_FINAL_URL_BYTES,
            expected_sha256=MARYLAND_FINAL_URL_SHA256,
            capture_content=True,
            maximum_capture_bytes=4096,
        ),
        _verify_stable_file(
            head_path,
            logical_name="provider HEAD response",
            expected_bytes=MARYLAND_HEAD_BYTES,
            expected_sha256=MARYLAND_HEAD_SHA256,
            capture_content=True,
            maximum_capture_bytes=64 * 1024,
        ),
        _verify_stable_file(
            md5_sidecar_path,
            logical_name="provider MD5 sidecar",
            expected_bytes=MARYLAND_MD5_SIDECAR_BYTES,
            expected_sha256=MARYLAND_MD5_SIDECAR_SHA256,
            capture_content=True,
            maximum_capture_bytes=4096,
        ),
        _verify_stable_file(
            fileinfo_path,
            logical_name="source fileinfo transcript",
            expected_bytes=MARYLAND_FILEINFO_BYTES,
            expected_sha256=MARYLAND_FILEINFO_SHA256,
            capture_content=True,
            maximum_capture_bytes=1024 * 1024,
        ),
    )
    return _verify_provider_capture_files(
        captures, source_bytes=source.bytes, source_md5=source.md5
    )
@dataclass(frozen=True, slots=True, order=True)
class LddDependency:
    soname: str
    resolved_path: str | None
    sha256: str | None
@dataclass(frozen=True, slots=True)
class VerifiedWslRuntime:
    ubuntu_distribution: str
    ubuntu_release: str
    wsl_version: int
    architecture: str
    kernel: str
    locale: str
    osmium_version: str
    libosmium_version: str
    osmium_binary_sha256: str
    boost_library_sha256: str
    osmium_deb_sha256: str
    boost_deb_sha256: str
    ldd_inventory: tuple[LddDependency, ...]
    command_argv: tuple[tuple[str, ...], ...]
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "WSL runtime evidence must be created by live attestation"
            )
        if (
            self.ubuntu_distribution != PINNED_UBUNTU_DISTRIBUTION
            or self.ubuntu_release != PINNED_UBUNTU_RELEASE
            or self.wsl_version != PINNED_WSL_VERSION
            or self.architecture != PINNED_WSL_ARCHITECTURE
            or self.locale != PINNED_LOCALE
            or self.osmium_version != PINNED_OSMIUM_VERSION
            or self.libosmium_version != PINNED_LIBOSMIUM_VERSION
            or self.osmium_binary_sha256 != OSMIUM_BINARY_SHA256
            or self.boost_library_sha256 != BOOST_LIBRARY_SHA256
            or self.osmium_deb_sha256 != OSMIUM_DEB_SHA256
            or self.boost_deb_sha256 != BOOST_DEB_SHA256
            or tuple(sorted(self.ldd_inventory)) != self.ldd_inventory
            or not self.command_argv
        ):
            raise ProvenanceVerificationError(
                "WSL runtime evidence fields are inconsistent with the current lock"
            )
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
def _run_attestation(argv: tuple[str, ...]):
    process = run_bounded_process(
        argv,
        max_output_bytes=MAX_CAPTURE_BYTES,
        timeout_seconds=120.0,
    )
    if process.returncode != 0 or process.stderr != b"":
        raise ProvenanceVerificationError(
            f"runtime attestation command failed: {argv!r}; "
            f"return code {process.returncode}, stderr SHA-256 "
            f"{hashlib.sha256(process.stderr).hexdigest()}"
        )
    return process
def _parse_sha256sum(
    output: bytes,
    expected_paths: tuple[str, ...],
    *,
    label: str,
) -> tuple[str, ...]:
    try:
        text = output.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(f"{label} hash transcript is not UTF-8") from error
    if not text.endswith("\n") or "\r" in text:
        raise ProvenanceVerificationError(f"{label} hash transcript is not canonical LF text")
    lines = text[:-1].split("\n") if text[:-1] else []
    if len(lines) != len(expected_paths):
        raise ProvenanceVerificationError(
            f"{label} hash transcript has {len(lines)} rows, expected {len(expected_paths)}"
        )
    digests: list[str] = []
    for line, expected_path in zip(lines, expected_paths):
        match = re.fullmatch(r"([0-9a-f]{64}) [ *](.+)", line)
        if match is None or match.group(2) != expected_path:
            raise ProvenanceVerificationError(
                f"{label} hash transcript does not bind {expected_path!r}"
            )
        digests.append(match.group(1))
    return tuple(digests)
def _parse_ldd_paths(output: bytes) -> tuple[tuple[str, str | None], ...]:
    try:
        text = output.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError("ldd transcript is not UTF-8") from error
    if not text.endswith("\n") or "\r" in text:
        raise ProvenanceVerificationError("ldd transcript is not canonical LF text")
    dependencies: list[tuple[str, str | None]] = []
    for raw_line in text[:-1].split("\n"):
        line = raw_line.strip()
        if not line or "=> not found" in line:
            raise ProvenanceVerificationError("ldd transcript is empty or has an unresolved library")
        linked = re.fullmatch(r"([^\s]+) => (/[^\s]+) \(0x[0-9a-fA-F]+\)", line)
        direct = re.fullmatch(r"(/[^\s]+) \(0x[0-9a-fA-F]+\)", line)
        virtual = re.fullmatch(r"([^\s]+) \(0x[0-9a-fA-F]+\)", line)
        if linked is not None:
            dependencies.append((linked.group(1), linked.group(2)))
        elif direct is not None:
            path = direct.group(1)
            dependencies.append((path.rsplit("/", 1)[-1], path))
        elif virtual is not None and virtual.group(1) == "linux-vdso.so.1":
            dependencies.append((virtual.group(1), None))
        else:
            raise ProvenanceVerificationError(f"unsupported ldd row: {line!r}")
    ordered = tuple(sorted(dependencies))
    if len({soname for soname, _ in ordered}) != len(ordered):
        raise ProvenanceVerificationError("ldd inventory contains duplicate sonames")
    boost_rows = [path for soname, path in ordered if soname == "libboost_program_options.so.1.71.0"]
    if boost_rows != [BOOST_LIBRARY_PATH]:
        raise ProvenanceVerificationError(
            "ldd did not resolve the pinned Boost program-options library"
        )
    return ordered
def _parse_wsl_version(output: bytes) -> int:
    try:
        text = output.decode("utf-16le", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(
            "WSL distribution inventory is not UTF-16LE"
        ) from error
    if not text.endswith("\r\n") or "\x00" in text:
        raise ProvenanceVerificationError(
            "WSL distribution inventory is not canonical CRLF text"
        )
    lines = text[:-2].split("\r\n")
    if not lines or re.fullmatch(
        r"\s*NAME\s+STATE\s+VERSION\s*", lines[0]
    ) is None:
        raise ProvenanceVerificationError("WSL distribution inventory header mismatch")
    versions: list[int] = []
    for line in lines[1:]:
        match = re.fullmatch(
            r"\s*(?:\*\s*)?([^\s]+)\s+([^\s]+)\s+([12])\s*", line
        )
        if match is None:
            raise ProvenanceVerificationError(
                f"unsupported WSL distribution inventory row: {line!r}"
            )
        if match.group(1) == PINNED_UBUNTU_DISTRIBUTION:
            versions.append(int(match.group(3)))
    if versions != [PINNED_WSL_VERSION]:
        raise ProvenanceVerificationError(
            "pinned Ubuntu WSL version mismatch: "
            f"expected {PINNED_WSL_VERSION}, got {versions!r}"
        )
    return versions[0]
def attest_live_wsl_runtime() -> VerifiedWslRuntime:
    base = _base_wsl_argv()
    commands: list[tuple[str, ...]] = []
    wsl_inventory_argv = (WSL_EXECUTABLE, "--list", "--verbose")
    commands.append(wsl_inventory_argv)
    wsl_version = _parse_wsl_version(
        _run_attestation(wsl_inventory_argv).stdout
    )
    def run(inner: tuple[str, ...], *, library_path: bool = False):
        argv = base + ((f"LD_LIBRARY_PATH={RUNTIME_LIBRARY_PATH}",) if library_path else ()) + inner
        commands.append(argv)
        return _run_attestation(argv)
    release = run(("/usr/bin/lsb_release", "-ds"))
    expected_release = f"{PINNED_UBUNTU_RELEASE}\n".encode("ascii")
    if release.stdout != expected_release:
        raise ProvenanceVerificationError("pinned Ubuntu release attestation mismatch")
    kernel = run(("/usr/bin/uname", "-srvmo"))
    try:
        kernel_text = kernel.stdout.decode("ascii", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError("kernel attestation is not ASCII") from error
    if not kernel_text.endswith("\n") or "\r" in kernel_text or not kernel_text[:-1]:
        raise ProvenanceVerificationError("kernel attestation is not one canonical line")
    architecture_match = re.search(r" ([^\s]+) GNU/Linux\n\Z", kernel_text)
    if (
        architecture_match is None
        or architecture_match.group(1) != PINNED_WSL_ARCHITECTURE
    ):
        raise ProvenanceVerificationError(
            "pinned WSL architecture attestation mismatch"
        )
    architecture = architecture_match.group(1)
    osmium = run((OSMIUM_BINARY_PATH, "--version"), library_path=True)
    expected_prefix = (
        f"osmium version {PINNED_OSMIUM_VERSION}\n"
        f"libosmium version {PINNED_LIBOSMIUM_VERSION}\n"
    ).encode("ascii")
    if not osmium.stdout.startswith(expected_prefix):
        raise ProvenanceVerificationError("pinned osmium/libosmium version mismatch")
    pinned_paths = (
        OSMIUM_BINARY_PATH,
        BOOST_LIBRARY_PATH,
        OSMIUM_DEB_PATH,
        BOOST_DEB_PATH,
    )
    expected_pinned_hashes = (
        OSMIUM_BINARY_SHA256,
        BOOST_LIBRARY_SHA256,
        OSMIUM_DEB_SHA256,
        BOOST_DEB_SHA256,
    )
    pre_hash = run(("/usr/bin/sha256sum", "--binary", *pinned_paths))
    pre_digests = _parse_sha256sum(
        pre_hash.stdout, pinned_paths, label="pinned runtime pre-attestation"
    )
    if pre_digests != expected_pinned_hashes:
        raise ProvenanceVerificationError("pinned runtime/deb hashes do not match the lock")
    ldd = run(("/usr/bin/ldd", OSMIUM_BINARY_PATH), library_path=True)
    ldd_rows = _parse_ldd_paths(ldd.stdout)
    dependency_paths = tuple(path for _, path in ldd_rows if path is not None)
    dependency_hashes_process = run(
        ("/usr/bin/sha256sum", "--binary", *dependency_paths)
    )
    dependency_hashes = _parse_sha256sum(
        dependency_hashes_process.stdout,
        dependency_paths,
        label="ldd dependency",
    )
    ldd_after = run(("/usr/bin/ldd", OSMIUM_BINARY_PATH), library_path=True)
    if _parse_ldd_paths(ldd_after.stdout) != ldd_rows:
        raise ProvenanceVerificationError(
            "ldd dependency resolution changed during attestation"
        )
    dependency_hashes_after_process = run(
        ("/usr/bin/sha256sum", "--binary", *dependency_paths)
    )
    dependency_hashes_after = _parse_sha256sum(
        dependency_hashes_after_process.stdout,
        dependency_paths,
        label="ldd dependency post-attestation",
    )
    if dependency_hashes_after != dependency_hashes:
        raise ProvenanceVerificationError(
            "ldd dependencies changed during attestation"
        )
    digest_by_path = dict(zip(dependency_paths, dependency_hashes))
    if digest_by_path.get(BOOST_LIBRARY_PATH) != BOOST_LIBRARY_SHA256:
        raise ProvenanceVerificationError(
            "ldd dependency snapshot does not bind the pinned Boost library hash"
        )
    inventory = tuple(
        LddDependency(
            soname=soname,
            resolved_path=path,
            sha256=digest_by_path[path] if path is not None else None,
        )
        for soname, path in ldd_rows
    )
    post_hash = run(("/usr/bin/sha256sum", "--binary", *pinned_paths))
    post_digests = _parse_sha256sum(
        post_hash.stdout, pinned_paths, label="pinned runtime post-attestation"
    )
    if post_digests != pre_digests:
        raise ProvenanceVerificationError("pinned runtime files changed during attestation")
    return _verified_instance(
        VerifiedWslRuntime,
        ubuntu_distribution=PINNED_UBUNTU_DISTRIBUTION,
        ubuntu_release=PINNED_UBUNTU_RELEASE,
        wsl_version=wsl_version,
        architecture=architecture,
        kernel=kernel_text[:-1],
        locale=PINNED_LOCALE,
        osmium_version=PINNED_OSMIUM_VERSION,
        libosmium_version=PINNED_LIBOSMIUM_VERSION,
        osmium_binary_sha256=OSMIUM_BINARY_SHA256,
        boost_library_sha256=BOOST_LIBRARY_SHA256,
        osmium_deb_sha256=OSMIUM_DEB_SHA256,
        boost_deb_sha256=BOOST_DEB_SHA256,
        ldd_inventory=inventory,
        command_argv=tuple(commands),
    )
@dataclass(frozen=True, slots=True)
class VerifiedSelectionMaterial:
    candidate_xml: bytes = field(repr=False)
    candidate_xml_sha256: str
    selector_module_sha256: str
    policy_sha256: str
    root_ids: bytes = field(repr=False)
    root_ids_sha256: str
    selection_material: bytes = field(repr=False)
    selection_material_sha256: str
    way_ids: tuple[int, ...]
    relation_ids: tuple[int, ...]
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "selection material must be created by live verification"
            )
        if (
            type(self.candidate_xml) is not bytes
            or type(self.root_ids) is not bytes
            or type(self.selection_material) is not bytes
            or _LOWER_SHA256.fullmatch(self.candidate_xml_sha256) is None
            or _LOWER_SHA256.fullmatch(self.selector_module_sha256) is None
            or _LOWER_SHA256.fullmatch(self.policy_sha256) is None
            or _LOWER_SHA256.fullmatch(self.root_ids_sha256) is None
            or _LOWER_SHA256.fullmatch(self.selection_material_sha256) is None
        ):
            raise ProvenanceVerificationError(
                "verified selection material fields are not exact immutable evidence"
            )
        if (
            len(self.root_ids) > MAX_ROOT_IDS_BYTES
            or len(self.selection_material) > MAX_SELECTION_MATERIAL_BYTES
        ):
            raise ProvenanceVerificationError(
                "verified selection material exceeds its byte ceiling"
            )
        if (
            hashlib.sha256(self.candidate_xml).hexdigest()
            != self.candidate_xml_sha256
            or hashlib.sha256(self.root_ids).hexdigest() != self.root_ids_sha256
            or hashlib.sha256(self.selection_material).hexdigest()
            != self.selection_material_sha256
        ):
            raise ProvenanceVerificationError(
                "verified selection material hashes do not match its exact bytes"
            )
        parsed_way_ids, parsed_relation_ids = _parse_canonical_root_ids(
            self.root_ids
        )
        if (
            type(self.way_ids) is not tuple
            or type(self.relation_ids) is not tuple
            or self.way_ids != parsed_way_ids
            or self.relation_ids != parsed_relation_ids
        ):
            raise ProvenanceVerificationError(
                "verified selection material root ID claims are inconsistent"
            )
@dataclass(frozen=True, slots=True)
class VerifiedPilotInputs:
    local: LocalCodeRuntimeEvidence
    source: VerifiedMarylandSource
    provider: VerifiedProviderCaptures
    wsl: VerifiedWslRuntime
    candidates: VerifiedCandidateFiles
    independent: VerifiedIndependentSelectionEvidence
    selection: VerifiedSelectionMaterial
    _seal: object = field(default=None, init=False, repr=False, compare=False)
    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "pilot inputs must be assembled from verified evidence objects"
            )
        for value, expected in (
            (self.local, LocalCodeRuntimeEvidence),
            (self.source, VerifiedMarylandSource),
            (self.provider, VerifiedProviderCaptures),
            (self.wsl, VerifiedWslRuntime),
            (self.candidates, VerifiedCandidateFiles),
            (self.independent, VerifiedIndependentSelectionEvidence),
            (self.selection, VerifiedSelectionMaterial),
        ):
            if not isinstance(value, expected) or value._seal is not _VERIFICATION_SEAL:
                raise ProvenanceVerificationError(
                    "pilot inputs contain unsupported or unverified evidence"
                )
        if self.provider.content_length != self.source.bytes:
            raise ProvenanceVerificationError(
                "provider HEAD content length does not match the verified source"
            )
        if self.provider.md5 != self.source.md5:
            raise ProvenanceVerificationError(
                "provider MD5 evidence does not match the verified source"
            )
        if (
            self.selection.candidate_xml_sha256 != self.candidates.xml_sha256
            or len(self.selection.candidate_xml) != self.candidates.xml_bytes
            or self.selection.selector_module_sha256 != self.local.selector_sha256
            or self.selection.policy_sha256 != self.local.policy_sha256
        ):
            raise ProvenanceVerificationError(
                "selection material is not bound to the verified candidate and selector"
            )
        if (
            self.independent.root_ids_file.content != self.selection.root_ids
            or self.independent.root_ids_sha256
            != self.selection.root_ids_sha256
            or self.independent.selected_way_count != len(self.selection.way_ids)
            or self.independent.selected_relation_count
            != len(self.selection.relation_ids)
        ):
            raise ProvenanceVerificationError(
                "independent broad evidence does not bind the derived selection roots"
            )
def assemble_verified_pilot_inputs(
    local: LocalCodeRuntimeEvidence,
    source: VerifiedMarylandSource,
    provider: VerifiedProviderCaptures,
    wsl: VerifiedWslRuntime,
    candidates: VerifiedCandidateFiles,
    independent: VerifiedIndependentSelectionEvidence,
) -> VerifiedPilotInputs:
    expected_types = (
        (local, LocalCodeRuntimeEvidence, "local code/runtime"),
        (source, VerifiedMarylandSource, "Maryland source"),
        (provider, VerifiedProviderCaptures, "provider capture"),
        (wsl, VerifiedWslRuntime, "WSL runtime"),
        (candidates, VerifiedCandidateFiles, "candidate"),
        (
            independent,
            VerifiedIndependentSelectionEvidence,
            "independent broad selection",
        ),
    )
    for value, expected, label in expected_types:
        if not isinstance(value, expected) or value._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                f"{label} input is not a verified evidence object"
            )
    selection = _derive_verified_selection_material(local, candidates)
    return _verified_instance(
        VerifiedPilotInputs,
        local=local,
        source=source,
        provider=provider,
        wsl=wsl,
        candidates=candidates,
        independent=independent,
        selection=selection,
    )
@dataclass(frozen=True, slots=True)
class ProvenanceBundleResult:
    selector_module_sha256: str
    document_sha256: tuple[tuple[str, str], ...]
def _parse_canonical_root_ids(raw: bytes) -> tuple[tuple[int, ...], tuple[int, ...]]:
    if type(raw) is not bytes:
        raise ProvenanceVerificationError("root IDs must be exact immutable bytes")
    if len(raw) > MAX_ROOT_IDS_BYTES:
        raise ProvenanceVerificationError(
            f"root IDs exceed the {MAX_ROOT_IDS_BYTES}-byte ceiling"
        )
    if not raw:
        return (), ()
    if b"\r" in raw or not raw.endswith(b"\n"):
        raise ProvenanceVerificationError(
            "root IDs must be canonical ASCII records with final LF"
        )
    try:
        lines = raw.decode("ascii", errors="strict").split("\n")[:-1]
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(
            "root IDs must be canonical ASCII"
        ) from error
    way_ids: list[int] = []
    relation_ids: list[int] = []
    relation_seen = False
    previous_way_id = 0
    previous_relation_id = 0
    for line in lines:
        match = _CANONICAL_ROOT_ID.fullmatch(line)
        if match is None:
            raise ProvenanceVerificationError(
                f"root ID record is not canonical: {line!r}"
            )
        object_type, raw_object_id = match.groups()
        object_id = int(raw_object_id)
        if object_id > _MAX_OSM_OBJECT_ID:
            raise ProvenanceVerificationError(
                "root ID exceeds the positive signed-63 OSM ID ceiling"
            )
        if object_type == "w":
            if relation_seen or object_id <= previous_way_id:
                raise ProvenanceVerificationError(
                    "way root IDs must be duplicate-free, sorted, and precede relations"
                )
            way_ids.append(object_id)
            previous_way_id = object_id
        else:
            relation_seen = True
            if object_id <= previous_relation_id:
                raise ProvenanceVerificationError(
                    "relation root IDs must be duplicate-free and sorted"
                )
            relation_ids.append(object_id)
            previous_relation_id = object_id
    return tuple(way_ids), tuple(relation_ids)
def _decode_selection_material(raw: bytes) -> dict[str, object]:
    if type(raw) is not bytes:
        raise ProvenanceVerificationError(
            "selection material must be exact immutable bytes"
        )
    if len(raw) > MAX_SELECTION_MATERIAL_BYTES:
        raise ProvenanceVerificationError(
            "selection material exceeds the "
            f"{MAX_SELECTION_MATERIAL_BYTES}-byte ceiling"
        )
    if not raw.endswith(b"\n") or b"\r" in raw or raw.startswith(b"\xef\xbb\xbf"):
        raise ProvenanceVerificationError(
            "selection material must be canonical UTF-8 JSON with final LF"
        )
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(
            "selection material is not strict UTF-8"
        ) from error
    def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        document: dict[str, object] = {}
        for key, value in pairs:
            if key in document:
                raise ProvenanceVerificationError(
                    f"selection material contains duplicate JSON key {key!r}"
                )
            document[key] = value
        return document
    def reject_constant(value: str) -> object:
        raise ProvenanceVerificationError(
            f"selection material contains forbidden JSON number {value!r}"
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
            f"selection material is not strict JSON: {error}"
        ) from error
    if type(decoded) is not dict:
        raise ProvenanceVerificationError(
            "selection material JSON root must be an object"
        )
    if set(decoded) != _SELECTION_MATERIAL_KEYS:
        raise ProvenanceVerificationError(
            "selection material has an unexpected exact schema"
        )
    if decoded.get("schema") != "flight-alert-exp8-osm-selection-material-v1":
        raise ProvenanceVerificationError(
            "selection material schema identifier is not exact"
        )
    pending: list[object] = [decoded]
    while pending:
        value = pending.pop()
        if type(value) is dict:
            forbidden = set(value).intersection(
                _FORBIDDEN_SELECTION_PROVENANCE_FIELDS
            )
            if forbidden:
                raise ProvenanceVerificationError(
                    "generic selection material contains forbidden provenance fields: "
                    f"{sorted(forbidden)!r}"
                )
            pending.extend(value.values())
        elif type(value) is list:
            pending.extend(value)
    try:
        canonical = canonical_json_bytes(decoded)
    except (TypeError, ValueError, UnicodeEncodeError, RecursionError) as error:
        raise ProvenanceVerificationError(
            f"selection material cannot be canonically encoded: {error}"
        ) from error
    if canonical != raw:
        raise ProvenanceVerificationError(
            "selection material JSON is not strictly canonical"
        )
    return decoded
def _decode_independent_report(raw: bytes) -> dict[str, object]:
    if type(raw) is not bytes:
        raise ProvenanceVerificationError(
            "independent verification report must be exact immutable bytes"
        )
    if not raw.endswith(b"\n") or b"\r" in raw or raw.startswith(b"\xef\xbb\xbf"):
        raise ProvenanceVerificationError(
            "independent verification report must be canonical UTF-8 JSON with final LF"
        )
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ProvenanceVerificationError(
            "independent verification report is not strict UTF-8"
        ) from error
    def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
        document: dict[str, object] = {}
        for key, value in pairs:
            if key in document:
                raise ProvenanceVerificationError(
                    f"independent verification report contains duplicate JSON key {key!r}"
                )
            document[key] = value
        return document
    def reject_constant(value: str) -> object:
        raise ProvenanceVerificationError(
            "independent verification report contains forbidden JSON number "
            f"{value!r}"
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
            f"independent verification report is not strict JSON: {error}"
        ) from error
    if type(decoded) is not dict or set(decoded) != _INDEPENDENT_REPORT_KEYS:
        raise ProvenanceVerificationError(
            "independent verification report has an unexpected exact schema"
        )
    try:
        canonical = canonical_json_bytes(decoded)
    except (TypeError, ValueError, UnicodeEncodeError, RecursionError) as error:
        raise ProvenanceVerificationError(
            f"independent verification report cannot be canonically encoded: {error}"
        ) from error
    if canonical != raw:
        raise ProvenanceVerificationError(
            "independent verification report JSON is not strictly canonical"
        )
    return decoded
def verify_independent_selection_evidence(
    broad_xml_path: str | Path,
    root_ids_path: str | Path,
    selection_material_path: str | Path,
    report_path: str | Path,
) -> VerifiedIndependentSelectionEvidence:
    broad_xml = _verify_stable_file(
        broad_xml_path,
        logical_name="independent broad waterway XML",
        expected_bytes=MARYLAND_BROAD_XML_BYTES,
        expected_sha256=MARYLAND_BROAD_XML_SHA256,
    )
    root_ids_file = _verify_stable_file(
        root_ids_path,
        logical_name="independent broad root IDs",
        expected_bytes=MARYLAND_BROAD_ROOT_IDS_BYTES,
        expected_sha256=MARYLAND_BROAD_ROOT_IDS_SHA256,
        capture_content=True,
        maximum_capture_bytes=MAX_ROOT_IDS_BYTES,
    )
    material_file = _verify_stable_file(
        selection_material_path,
        logical_name="independent broad selection material",
        expected_bytes=MARYLAND_BROAD_SELECTION_MATERIAL_BYTES,
        expected_sha256=MARYLAND_BROAD_SELECTION_MATERIAL_SHA256,
        capture_content=True,
        maximum_capture_bytes=MAX_SELECTION_MATERIAL_BYTES,
    )
    report_file = _verify_stable_file(
        report_path,
        logical_name="independent broad verification report",
        expected_bytes=MARYLAND_INDEPENDENT_REPORT_BYTES,
        expected_sha256=MARYLAND_INDEPENDENT_REPORT_SHA256,
        capture_content=True,
        maximum_capture_bytes=4 * 1024 * 1024,
    )
    files = (broad_xml, root_ids_file, material_file, report_file)
    if len({item.path for item in files}) != len(files):
        raise ProvenanceVerificationError(
            "independent broad evidence inputs must be four distinct files"
        )
    root_ids = root_ids_file.content
    material = material_file.content
    report_raw = report_file.content
    if root_ids is None or material is None or report_raw is None:
        raise ProvenanceVerificationError(
            "independent broad evidence retained bytes are unavailable"
        )
    way_ids, relation_ids = _parse_canonical_root_ids(root_ids)
    material_document = _decode_selection_material(material)
    if (
        material_document.get("rootIdsSha256") != root_ids_file.sha256
        or material_document.get("selectedCounts")
        != {"relations": len(relation_ids), "ways": len(way_ids)}
    ):
        raise ProvenanceVerificationError(
            "independent broad material does not bind its exact root IDs and counts"
        )
    report = _decode_independent_report(report_raw)
    expected_selected_ids = root_ids.decode("ascii", errors="strict").splitlines()
    if (
        report.get("schema")
        != "flight-alert-exp8-osm-independent-selection-verification-report-v1"
        or report.get("profile")
        != "flight-alert-exp8-osm-independent-selection-generic-v1"
        or report.get("verified") is not True
        or report.get("candidateEnvelope")
        != {
            "relationTag": ["type", "waterway"],
            "wayTagKey": "waterway",
        }
        or report.get("broadInput")
        != {"bytes": broad_xml.bytes, "sha256": broad_xml.sha256}
        or report.get("rootIds")
        != {"bytes": root_ids_file.bytes, "sha256": root_ids_file.sha256}
        or report.get("selectionMaterial")
        != {"bytes": material_file.bytes, "sha256": material_file.sha256}
        or report.get("candidateCounts")
        != material_document.get("candidateCounts")
        or report.get("rejectedRelationCounts")
        != material_document.get("rejectedRelationCounts")
        or report.get("rejectedWayCounts")
        != material_document.get("rejectedWayCounts")
        or report.get("selectedCounts")
        != material_document.get("selectedCounts")
        or report.get("selectedRootIds") != expected_selected_ids
    ):
        raise ProvenanceVerificationError(
            "independent verification report does not exactly bind the accepted broad evidence"
        )
    return _verified_instance(
        VerifiedIndependentSelectionEvidence,
        broad_xml_file=broad_xml,
        root_ids_file=root_ids_file,
        selection_material_file=material_file,
        report_file=report_file,
        broad_xml_sha256=broad_xml.sha256,
        root_ids_sha256=root_ids_file.sha256,
        selection_material_sha256=material_file.sha256,
        report_sha256=report_file.sha256,
        selected_way_count=len(way_ids),
        selected_relation_count=len(relation_ids),
    )
def _expected_selection_document(
    candidate_xml: bytes,
    root_ids: bytes,
) -> tuple[dict[str, object], tuple[int, ...], tuple[int, ...]]:
    try:
        dataset = osm_hydro_source.parse_osm_xml_bytes(
            candidate_xml,
            source_label="verified candidate XML snapshot",
        )
        if dataset.nodes:
            raise ProvenanceVerificationError(
                "candidate XML contains nodes instead of selection-only objects"
            )
        roots = osm_hydro_source.select_roots(dataset)
    except ProvenanceVerificationError:
        raise
    except (ValueError, TypeError) as error:
        raise ProvenanceVerificationError(
            f"candidate XML cannot be independently reconciled: {error}"
        ) from error
    expected_root_ids = b"".join(
        [
            *(f"w{object_id}\n".encode("ascii") for object_id in roots.way_ids),
            *(
                f"r{object_id}\n".encode("ascii")
                for object_id in roots.relation_ids
            ),
        ]
    )
    if root_ids != expected_root_ids:
        raise ProvenanceVerificationError(
            "selector root IDs do not reconcile to the candidate XML snapshot"
        )
    rejected_way_entries: list[dict[str, object]] = []
    for object_id, way in sorted(dataset.ways.items()):
        reason = osm_hydro_source._direct_way_rejection_reason(way)
        if reason is not None:
            rejected_way_entries.append({"id": object_id, "reason": reason})
    rejected_relation_entries: list[dict[str, object]] = []
    for object_id, relation in sorted(dataset.relations.items()):
        reason = osm_hydro_source._relation_rejection_reason(relation)
        if reason is not None:
            rejected_relation_entries.append({"id": object_id, "reason": reason})
    rejected_ways = Counter(
        str(entry["reason"]) for entry in rejected_way_entries
    )
    rejected_relations = Counter(
        str(entry["reason"]) for entry in rejected_relation_entries
    )
    root_entries: list[dict[str, object]] = []
    for object_id in roots.way_ids:
        way = dataset.ways[object_id]
        source_object: dict[str, object] = {
            "id": object_id,
            "nodeRefs": list(way.node_refs),
            "objectType": "way",
            "tags": [list(item) for item in way.tags],
            "timestamp": way.timestamp,
            "version": way.version,
        }
        root_entries.append(
            {
                **source_object,
                "displayNames": [
                    list(item)
                    for item in osm_hydro_source.supported_display_names(way.tags)
                ],
                "objectSha256": hashlib.sha256(
                    canonical_json_bytes(source_object)
                ).hexdigest(),
                "waterway": dict(way.tags)["waterway"],
            }
        )
    for object_id in roots.relation_ids:
        relation = dataset.relations[object_id]
        source_object = {
            "id": object_id,
            "members": [
                {
                    "objectType": member.object_type,
                    "ordinal": member.ordinal,
                    "ref": member.ref,
                    "role": member.role,
                }
                for member in relation.members
            ],
            "objectType": "relation",
            "tags": [list(item) for item in relation.tags],
            "timestamp": relation.timestamp,
            "version": relation.version,
        }
        root_entries.append(
            {
                **source_object,
                "displayNames": [
                    list(item)
                    for item in osm_hydro_source.supported_display_names(
                        relation.tags
                    )
                ],
                "objectSha256": hashlib.sha256(
                    canonical_json_bytes(source_object)
                ).hexdigest(),
            }
        )
    document: dict[str, object] = {
        "candidateCounts": {
            "nodes": len(dataset.nodes),
            "relations": len(dataset.relations),
            "ways": len(dataset.ways),
        },
        "rejectedRelationCounts": dict(sorted(rejected_relations.items())),
        "rejectedRelations": rejected_relation_entries,
        "rejectedWayCounts": dict(sorted(rejected_ways.items())),
        "rejectedWays": rejected_way_entries,
        "rootIdsSha256": hashlib.sha256(root_ids).hexdigest(),
        "roots": root_entries,
        "schema": "flight-alert-exp8-osm-selection-material-v1",
        "selectedCounts": {
            "relations": len(roots.relation_ids),
            "ways": len(roots.way_ids),
        },
    }
    return document, tuple(roots.way_ids), tuple(roots.relation_ids)
def _validate_selection_outputs(
    candidate_xml: bytes,
    root_ids: bytes,
    selection_material: bytes,
) -> tuple[tuple[int, ...], tuple[int, ...]]:
    parsed_way_ids, parsed_relation_ids = _parse_canonical_root_ids(root_ids)
    decoded = _decode_selection_material(selection_material)
    expected, expected_way_ids, expected_relation_ids = _expected_selection_document(
        candidate_xml,
        root_ids,
    )
    if (
        parsed_way_ids != expected_way_ids
        or parsed_relation_ids != expected_relation_ids
    ):
        raise ProvenanceVerificationError(
            "canonical root ID records do not match the selected candidate roots"
        )
    if selection_material != canonical_json_bytes(expected) or decoded != expected:
        raise ProvenanceVerificationError(
            "selection material does not exactly reconcile to the candidate XML snapshot"
        )
    return expected_way_ids, expected_relation_ids
def _validate_accepted_maryland_selection(
    candidates: VerifiedCandidateFiles,
    root_ids: bytes,
    selection_material: bytes,
    way_ids: tuple[int, ...],
    relation_ids: tuple[int, ...],
) -> None:
    current_candidate_lock = (
        WATERWAY_CANDIDATES_PBF_BYTES,
        WATERWAY_CANDIDATES_PBF_SHA256,
        WATERWAY_CANDIDATES_XML_BYTES,
        WATERWAY_CANDIDATES_XML_SHA256,
    )
    candidate_facts = (
        candidates.pbf_bytes,
        candidates.pbf_sha256,
        candidates.xml_bytes,
        candidates.xml_sha256,
    )
    if candidate_facts != current_candidate_lock:
        return
    if (
        len(root_ids) != MARYLAND_ROOT_IDS_BYTES
        or hashlib.sha256(root_ids).hexdigest() != MARYLAND_ROOT_IDS_SHA256
        or len(way_ids) != MARYLAND_ROOT_WAY_COUNT
        or len(relation_ids) != MARYLAND_ROOT_RELATION_COUNT
        or len(selection_material) != MARYLAND_SELECTION_MATERIAL_BYTES
        or hashlib.sha256(selection_material).hexdigest()
        != MARYLAND_SELECTION_MATERIAL_SHA256
    ):
        raise ProvenanceVerificationError(
            "derived Maryland selection does not match the exact clean-start output locks"
        )
def _derive_verified_selection_material(
    local: LocalCodeRuntimeEvidence,
    candidates: VerifiedCandidateFiles,
) -> VerifiedSelectionMaterial:
    if (
        not isinstance(local, LocalCodeRuntimeEvidence)
        or local._seal is not _VERIFICATION_SEAL
        or not isinstance(candidates, VerifiedCandidateFiles)
        or candidates._seal is not _VERIFICATION_SEAL
    ):
        raise ProvenanceVerificationError(
            "selection material requires verified selector and candidate evidence"
        )
    if (
        local.selector_callable is not _CAPTURED_SELECTION_CALLABLE
        or local.selector_callable_module != _SELECTION_CALLABLE_MODULE
        or local.selector_callable_qualname != _SELECTION_CALLABLE_QUALNAME
        or local.selector_callable_code_sha256
        != _CAPTURED_SELECTION_CODE_SHA256
        or _attest_selection_callable_identity()
        != local.selector_callable_source_path
    ):
        raise ProvenanceVerificationError(
            "verified selector callable identity no longer matches the captured function"
        )
    selector_source = local.selector_file.content
    if selector_source is None:
        raise ProvenanceVerificationError(
            "verified selector snapshot has no retained source bytes"
        )
    snapshot_selector_callable = _load_selector_snapshot(
        selector_source,
        local.selector_callable_source_path,
        local.selector_sha256,
    )
    snapshot = _verify_stable_file(
        candidates.xml_file.path,
        logical_name="waterway candidate XML selector snapshot",
        expected_bytes=candidates.xml_bytes,
        expected_sha256=candidates.xml_sha256,
        capture_content=True,
        maximum_capture_bytes=candidates.xml_bytes,
    )
    if (
        snapshot.path != candidates.xml_file.path
        or snapshot.identity != candidates.xml_file.identity
        or snapshot.bytes != candidates.xml_file.bytes
        or snapshot.sha256 != candidates.xml_file.sha256
        or snapshot.content is None
    ):
        raise ProvenanceVerificationError(
            "candidate XML changed after verification before selector invocation"
        )
    candidate_xml = snapshot.content
    try:
        outputs = snapshot_selector_callable(candidate_xml)
    except Exception as error:
        raise ProvenanceVerificationError(
            f"verified selector snapshot callable failed: {error}"
        ) from error
    if _attest_selection_callable_identity() != local.selector_callable_source_path:
        raise ProvenanceVerificationError(
            "selection callable identity changed during selector invocation"
        )
    if type(outputs) is not tuple or len(outputs) != 2:
        raise ProvenanceVerificationError(
            "selection callable must return one exact two-byte tuple"
        )
    root_ids, selection_material = outputs
    if type(root_ids) is not bytes or type(selection_material) is not bytes:
        raise ProvenanceVerificationError(
            "selection callable outputs must be exact immutable bytes"
        )
    way_ids, relation_ids = _validate_selection_outputs(
        candidate_xml,
        root_ids,
        selection_material,
    )
    _validate_accepted_maryland_selection(
        candidates,
        root_ids,
        selection_material,
        way_ids,
        relation_ids,
    )
    return _verified_instance(
        VerifiedSelectionMaterial,
        candidate_xml=candidate_xml,
        candidate_xml_sha256=snapshot.sha256,
        selector_module_sha256=local.selector_sha256,
        policy_sha256=local.policy_sha256,
        root_ids=root_ids,
        root_ids_sha256=hashlib.sha256(root_ids).hexdigest(),
        selection_material=selection_material,
        selection_material_sha256=hashlib.sha256(selection_material).hexdigest(),
        way_ids=way_ids,
        relation_ids=relation_ids,
    )
def _require_verified_inputs(inputs: object) -> VerifiedPilotInputs:
    if not isinstance(inputs, VerifiedPilotInputs) or inputs._seal is not _VERIFICATION_SEAL:
        raise ProvenanceVerificationError(
            "provenance output requires one assembled VerifiedPilotInputs object"
        )
    return inputs
def _source_identity_document_from_facts(verified) -> dict[str, object]:
    return {
        "data": {
            "boundsE7": [-801_318_095, 373_264_889, -749_849_355, 411_476_119],
            "counts": {
                "changesets": 0,
                "nodes": 27_286_527,
                "relations": 27_883,
                "ways": 3_178_490,
            },
            "crc32": "c452ca02",
            "firstObjectTimestamp": "2005-11-30T04:26:15Z",
            "lastObjectTimestamp": "2026-07-10T19:30:40Z",
            "maximumIds": {
                "changesets": 0,
                "nodes": 14_003_334_163,
                "relations": 21_084_788,
                "ways": 1_537_294_226,
            },
            "metadata": {
                "changeset": False,
                "timestamp": True,
                "uid": False,
                "user": False,
                "version": True,
            },
            "minimumIds": {
                "changesets": 0,
                "nodes": 272_594,
                "relations": 50_372,
                "ways": 4_268_721,
            },
            "multipleVersions": False,
            "objectsOrdered": True,
        },
        "header": {
            "boundsE7": [-794_885_700, 378_839_600, -749_540_790, 401_705_400],
            "generator": "osmium/1.16.0",
            "pbfHeaderTimestampUtc": verified.provider.pbf_header_timestamp_utc,
            "replicationBaseUrl": (
                "https://download.geofabrik.de/north-america/us/maryland-updates"
            ),
            "replicationSequence": 4_845,
            "replicationTimestamp": "2026-07-10T20:21:01Z",
            "sorting": "Type_then_ID",
            "withHistory": False,
        },
        "profile": MARYLAND_REGIONAL_PROFILE,
        "providerEvidence": {
            "contentLength": verified.provider.content_length,
            "fileinfoCommand": {
                "argv": [
                    "osmium",
                    "fileinfo",
                    "-e",
                    "-c",
                    "source/maryland-260710.osm.pbf",
                ],
                "binarySha256": verified.wsl.osmium_binary_sha256,
                "libosmiumVersion": verified.wsl.libosmium_version,
                "osmiumVersion": verified.wsl.osmium_version,
            },
            "fileinfoBytes": verified.provider.fileinfo_file.bytes,
            "fileinfoSha256": verified.provider.fileinfo_file.sha256,
            "finalUrl": verified.provider.final_url,
            "finalUrlBytes": verified.provider.final_url_file.bytes,
            "finalUrlSha256": verified.provider.final_url_file.sha256,
            "headBytes": verified.provider.head_file.bytes,
            "headSha256": verified.provider.head_file.sha256,
            "localDownload": {
                "reason": (
                    verified.provider.local_download_unavailable_reason
                ),
                "status": verified.provider.local_download_status,
                "utc": verified.provider.local_download_utc,
            },
            "md5SidecarBytes": verified.provider.md5_sidecar_file.bytes,
            "md5SidecarSha256": verified.provider.md5_sidecar_file.sha256,
            "providerResponseDateUtc": (
                verified.provider.provider_response_date_utc
            ),
        },
        "schema": "flight-alert-exp8-osm-source-identity-v2",
        "source": {
            "bytes": verified.source.bytes,
            "md5": verified.source.md5,
            "sha256": verified.source.sha256,
            "url": MARYLAND_SOURCE_URL,
        },
        "verification": {
            "contentHashedFromOneOpenFile": True,
            "prePostFileIdentityMatched": True,
        },
    }
def _source_identity_document(inputs: VerifiedPilotInputs) -> dict[str, object]:
    return _source_identity_document_from_facts(_require_verified_inputs(inputs))
def source_identity_document(inputs: VerifiedPilotInputs) -> dict[str, object]:
    return _source_identity_document(_revalidate_verified_inputs(inputs))
def _portable_wsl_path(value: str) -> str:
    if value == WSL_EXECUTABLE:
        return "wsl.exe"
    return value.replace(_RUNTIME_BASE, "$PINNED_OSMIUM_RUNTIME")
def _runtime_manifest_document_from_facts(verified) -> dict[str, object]:
    return {
        "boostProgramOptions": {
            "debSha256": verified.wsl.boost_deb_sha256,
            "soname": "libboost_program_options.so.1.71.0",
            "sha256": verified.wsl.boost_library_sha256,
        },
        "lddInventory": [
            {
                "resolvedPath": (
                    _portable_wsl_path(item.resolved_path)
                    if item.resolved_path is not None
                    else None
                ),
                "sha256": item.sha256,
                "soname": item.soname,
            }
            for item in verified.wsl.ldd_inventory
        ],
        "osmium": {
            "binarySha256": verified.wsl.osmium_binary_sha256,
            "debSha256": verified.wsl.osmium_deb_sha256,
            "libosmiumVersion": verified.wsl.libosmium_version,
            "version": verified.wsl.osmium_version,
        },
        "profile": MARYLAND_REGIONAL_PROFILE,
        "python": {
            "cacheTag": verified.local.python_cache_tag,
            "dependencies": [
                {
                    "bytes": item.bytes,
                    "logicalName": item.logical_name,
                    "sha256": item.sha256,
                }
                for item in verified.local.python_dependencies
            ],
            "executable": {
                "bytes": verified.local.python_file.bytes,
                "logicalName": "runtime/python.exe",
                "sha256": verified.local.python_executable_sha256,
            },
            "executableSha256": verified.local.python_executable_sha256,
            "flags": dict(verified.local.python_flags),
            "implementation": verified.local.python_implementation,
            "platform": verified.local.python_platform,
            "version": verified.local.python_version,
        },
        "runtimeAttestationArgv": [
            [_portable_wsl_path(value) for value in argv]
            for argv in verified.wsl.command_argv
        ],
        "schema": "flight-alert-exp8-osm-runtime-manifest-v2",
        "selector": {
            "callable": (
                f"{verified.local.selector_callable_module}."
                f"{verified.local.selector_callable_qualname}"
            ),
            "callableCodeSha256": (
                verified.local.selector_callable_code_sha256
            ),
            "execution": "isolated-verified-source-snapshot",
            "module": verified.local.selector_module,
            "sha256": verified.local.selector_sha256,
        },
        "wsl": {
            "architecture": verified.wsl.architecture,
            "distribution": verified.wsl.ubuntu_distribution,
            "kernel": verified.wsl.kernel,
            "locale": verified.wsl.locale,
            "release": verified.wsl.ubuntu_release,
            "version": verified.wsl.wsl_version,
        },
    }
def _runtime_manifest_document(inputs: VerifiedPilotInputs) -> dict[str, object]:
    return _runtime_manifest_document_from_facts(_require_verified_inputs(inputs))
def runtime_manifest_document(inputs: VerifiedPilotInputs) -> dict[str, object]:
    return _runtime_manifest_document(_revalidate_verified_inputs(inputs))
def _selection_command_manifest_document_from_facts(verified) -> dict[str, object]:
    source_identity_sha256 = hashlib.sha256(
        canonical_json_bytes(_source_identity_document_from_facts(verified))
    ).hexdigest()
    runtime_manifest_sha256 = hashlib.sha256(
        canonical_json_bytes(_runtime_manifest_document_from_facts(verified))
    ).hexdigest()
    return {
        "commands": [
            {
                "argv": [
                    "tags-filter",
                    "--no-progress",
                    "-R",
                    "--generator",
                    "flight-alert-exp8-osmium-1.11.1",
                    "-f",
                    "pbf",
                    "-O",
                    "-o",
                    "selection/waterway-candidates-v2.osm.pbf",
                    "source/maryland-260710.osm.pbf",
                    "w/waterway=river,stream,canal,tidal_channel,wadi",
                    "r/type=waterway",
                ],
                "input": {
                    "bytes": verified.source.bytes,
                    "logicalName": "source/maryland-260710.osm.pbf",
                    "sha256": verified.source.sha256,
                },
                "output": {
                    "bytes": verified.candidates.pbf_bytes,
                    "logicalName": "selection/waterway-candidates-v2.osm.pbf",
                    "sha256": verified.candidates.pbf_sha256,
                },
                "tool": "osmium tags-filter",
            },
            {
                "argv": [
                    "cat",
                    "--no-progress",
                    "--generator",
                    "flight-alert-exp8-osmium-1.11.1",
                    "-f",
                    "osm",
                    "-O",
                    "-o",
                    "selection/waterway-candidates-v2.osm",
                    "selection/waterway-candidates-v2.osm.pbf",
                ],
                "input": {
                    "bytes": verified.candidates.pbf_bytes,
                    "logicalName": "selection/waterway-candidates-v2.osm.pbf",
                    "sha256": verified.candidates.pbf_sha256,
                },
                "output": {
                    "bytes": verified.candidates.xml_bytes,
                    "logicalName": "selection/waterway-candidates-v2.osm",
                    "sha256": verified.candidates.xml_sha256,
                },
                "tool": "osmium cat",
            },
        ],
        "policySha256": verified.local.policy_sha256,
        "profile": MARYLAND_REGIONAL_PROFILE,
        "runtimeManifestSha256": runtime_manifest_sha256,
        "schema": "flight-alert-exp8-osm-selection-command-manifest-v2",
        "selectorCall": {
            "callable": (
                f"{verified.local.selector_callable_module}."
                f"{verified.local.selector_callable_qualname}"
            ),
            "input": {
                "bytes": verified.candidates.xml_bytes,
                "logicalName": "selection/waterway-candidates-v2.osm",
                "sha256": verified.candidates.xml_sha256,
            },
            "outputs": [
                {
                    "bytes": len(verified.selection.root_ids),
                    "logicalName": _ROOT_IDS_LOGICAL_NAME,
                    "sha256": verified.selection.root_ids_sha256,
                },
                {
                    "bytes": len(verified.selection.selection_material),
                    "logicalName": _SELECTION_MATERIAL_LOGICAL_NAME,
                    "sha256": verified.selection.selection_material_sha256,
                },
            ],
        },
        "selectorModuleSha256": verified.local.selector_sha256,
        "sourceIdentitySha256": source_identity_sha256,
    }
def _selection_command_manifest_document(
    inputs: VerifiedPilotInputs,
) -> dict[str, object]:
    return _selection_command_manifest_document_from_facts(
        _require_verified_inputs(inputs)
    )
def selection_command_manifest_document(
    inputs: VerifiedPilotInputs,
) -> dict[str, object]:
    return _selection_command_manifest_document(_revalidate_verified_inputs(inputs))
def _selection_binding_document_from_facts(verified) -> dict[str, object]:
    source_identity_sha256 = hashlib.sha256(
        canonical_json_bytes(_source_identity_document_from_facts(verified))
    ).hexdigest()
    runtime_manifest_sha256 = hashlib.sha256(
        canonical_json_bytes(_runtime_manifest_document_from_facts(verified))
    ).hexdigest()
    selection_command_manifest_sha256 = hashlib.sha256(
        canonical_json_bytes(_selection_command_manifest_document_from_facts(verified))
    ).hexdigest()
    return {
        "candidateXml": {
            "bytes": verified.candidates.xml_bytes,
            "logicalName": "selection/waterway-candidates-v2.osm",
            "sha256": verified.candidates.xml_sha256,
        },
        "independentBroadEvidence": {
            "broadInput": {
                "bytes": verified.independent.broad_xml_file.bytes,
                "logicalName": "independent/waterway-broad-v1.osm",
                "sha256": verified.independent.broad_xml_sha256,
            },
            "report": {
                "bytes": verified.independent.report_file.bytes,
                "logicalName": (
                    "independent/broad-independent-verification-v1.json"
                ),
                "sha256": verified.independent.report_sha256,
            },
            "rootIds": {
                "bytes": verified.independent.root_ids_file.bytes,
                "logicalName": "independent/broad-root-ids-v1.txt",
                "sha256": verified.independent.root_ids_sha256,
            },
            "selectionMaterial": {
                "bytes": verified.independent.selection_material_file.bytes,
                "logicalName": (
                    "independent/broad-selection-material-v1.json"
                ),
                "sha256": verified.independent.selection_material_sha256,
            },
            "selectedCounts": {
                "relations": verified.independent.selected_relation_count,
                "ways": verified.independent.selected_way_count,
            },
        },
        "outputs": [
            {
                "bytes": len(verified.selection.root_ids),
                "logicalName": _ROOT_IDS_LOGICAL_NAME,
                "sha256": verified.selection.root_ids_sha256,
            },
            {
                "bytes": len(verified.selection.selection_material),
                "logicalName": _SELECTION_MATERIAL_LOGICAL_NAME,
                "sha256": verified.selection.selection_material_sha256,
            },
        ],
        "policySha256": verified.local.policy_sha256,
        "profile": MARYLAND_REGIONAL_PROFILE,
        "runtimeManifestSha256": runtime_manifest_sha256,
        "schema": "flight-alert-exp8-osm-selection-binding-v1",
        "selectionCommandManifestSha256": selection_command_manifest_sha256,
        "selector": {
            "module": verified.local.selector_module,
            "sha256": verified.local.selector_sha256,
        },
        "source": {
            "bytes": verified.source.bytes,
            "logicalName": "source/maryland-260710.osm.pbf",
            "sha256": verified.source.sha256,
        },
        "sourceIdentitySha256": source_identity_sha256,
    }
def _selection_binding_document(inputs: VerifiedPilotInputs) -> dict[str, object]:
    return _selection_binding_document_from_facts(_require_verified_inputs(inputs))
def selection_binding_document(inputs: VerifiedPilotInputs) -> dict[str, object]:
    return _selection_binding_document(_revalidate_verified_inputs(inputs))
def _verified_input_files(verified: VerifiedPilotInputs) -> tuple[_VerifiedFile, ...]:
    local, provider = verified.local, verified.provider
    candidates, independent = verified.candidates, verified.independent
    return (local.selector_file, local.python_file, *local.python_dependencies, verified.source.file,
        provider.final_url_file, provider.head_file, provider.md5_sidecar_file,
        provider.fileinfo_file, candidates.pbf_file, candidates.xml_file,
        independent.broad_xml_file, independent.root_ids_file,
        independent.selection_material_file, independent.report_file)
def _hold_verified_input_files(verified: VerifiedPilotInputs) -> list[tuple[_VerifiedFile, int]]:
    return _hold_verified_files(_verified_input_files(verified))
def _revalidate_verified_inputs(inputs: VerifiedPilotInputs) -> VerifiedPilotInputs:
    verified = _require_verified_inputs(inputs)
    def replay(label: str, factory, expected):
        try:
            current = factory()
        except ProvenanceVerificationError as error:
            raise ProvenanceVerificationError(f"{label} changed after verification or "
                f"failed its current lock: {error}") from error
        if current != expected:
            raise ProvenanceVerificationError(f"{label} changed after verification or "
                "no longer matches its current lock")
        return current
    local = replay("local selector/Python identity", verify_local_code_runtime,
        verified.local)
    source = replay("Maryland source",
        lambda: verify_maryland_source_file(verified.source.file.path),
        verified.source)
    provider = replay("provider capture evidence",
        lambda: verify_provider_captures(
            verified.provider.final_url_file.path, verified.provider.head_file.path,
            verified.provider.md5_sidecar_file.path,
            verified.provider.fileinfo_file.path, source=source), verified.provider)
    candidates = replay("candidate PBF/XML evidence",
        lambda: verify_candidate_files(verified.candidates.pbf_file.path,
            verified.candidates.xml_file.path), verified.candidates)
    independent = replay("independent broad selection evidence",
        lambda: verify_independent_selection_evidence(
            verified.independent.broad_xml_file.path, verified.independent.root_ids_file.path,
            verified.independent.selection_material_file.path,
            verified.independent.report_file.path), verified.independent)
    selection = replay("selection material",
        lambda: _derive_verified_selection_material(local, candidates),
        verified.selection)
    wsl = replay("WSL runtime identity", attest_live_wsl_runtime, verified.wsl)
    return _verified_instance(VerifiedPilotInputs, local=local, source=source,
        provider=provider, wsl=wsl, candidates=candidates,
        independent=independent, selection=selection)
def _captured_bytes(file: _VerifiedFile) -> bytes:
    if file.content is None:
        raise ProvenanceVerificationError(
            f"{file.logical_name} has no retained evidence bytes"
        )
    return file.content
def read_pilot_provenance_bundle(source: str | Path) -> ProvenanceBundleResult:
    directory = Path(source)
    _, snapshot = _read_exact_rooted_bundle(
        directory,
        expected_profile=MARYLAND_REGIONAL_PROFILE,
        expected_payload_names=_BUNDLE_PAYLOAD_NAMES,
    )
    files = {item.logical_name: item for item in snapshot}
    def document(logical_name: str) -> dict[str, object]:
        raw = files[logical_name].content
        if raw is None:
            raise ProvenanceVerificationError(
                f"persisted bundle document {logical_name!r} has no exact bytes"
            )
        return _decode_canonical_object(raw, logical_name)
    root_ids = files[_ROOT_IDS_LOGICAL_NAME].content
    material = files[_SELECTION_MATERIAL_LOGICAL_NAME].content
    if root_ids is None or material is None:
        raise ProvenanceVerificationError(
            "persisted selection output bytes are unavailable"
        )
    way_ids, relation_ids = _parse_canonical_root_ids(root_ids)
    material_document = _decode_selection_material(material)
    if (
        files[_ROOT_IDS_LOGICAL_NAME].bytes != MARYLAND_ROOT_IDS_BYTES
        or files[_ROOT_IDS_LOGICAL_NAME].sha256 != MARYLAND_ROOT_IDS_SHA256
        or len(way_ids) != MARYLAND_ROOT_WAY_COUNT
        or len(relation_ids) != MARYLAND_ROOT_RELATION_COUNT
        or files[_SELECTION_MATERIAL_LOGICAL_NAME].bytes
        != MARYLAND_SELECTION_MATERIAL_BYTES
        or files[_SELECTION_MATERIAL_LOGICAL_NAME].sha256
        != MARYLAND_SELECTION_MATERIAL_SHA256
        or material_document.get("rootIdsSha256")
        != files[_ROOT_IDS_LOGICAL_NAME].sha256
        or material_document.get("selectedCounts")
        != {"relations": len(relation_ids), "ways": len(way_ids)}
    ):
        raise ProvenanceVerificationError(
            "persisted selection material does not bind root-ids.txt"
        )
    local = verify_local_code_runtime()
    selector_bytes = files["selector-source.py"].content
    if selector_bytes is None or selector_bytes != local.selector_file.content:
        raise ProvenanceVerificationError(
            "persisted selector source does not match the current locked selector"
        )
    wsl = attest_live_wsl_runtime()
    provider = _verify_provider_capture_files(
        tuple(
            files[name]
            for name in (
                "provider-final-url.txt",
                "provider-head.txt",
                "provider-md5-sidecar.txt",
                "source-fileinfo.txt",
            )
        ),
        source_bytes=_SOURCE_EVIDENCE_BYTES,
        source_md5=_SOURCE_EVIDENCE_MD5,
    )
    facts = SimpleNamespace(
        local=local,
        source=SimpleNamespace(
            bytes=_SOURCE_EVIDENCE_BYTES,
            md5=_SOURCE_EVIDENCE_MD5,
            sha256=_SOURCE_EVIDENCE_SHA256,
        ),
        provider=provider,
        wsl=wsl,
        candidates=SimpleNamespace(
            pbf_bytes=WATERWAY_CANDIDATES_PBF_BYTES,
            pbf_sha256=WATERWAY_CANDIDATES_PBF_SHA256,
            xml_bytes=WATERWAY_CANDIDATES_XML_BYTES,
            xml_sha256=WATERWAY_CANDIDATES_XML_SHA256,
        ),
        independent=SimpleNamespace(
            broad_xml_file=SimpleNamespace(bytes=MARYLAND_BROAD_XML_BYTES),
            broad_xml_sha256=MARYLAND_BROAD_XML_SHA256,
            report_file=SimpleNamespace(bytes=MARYLAND_INDEPENDENT_REPORT_BYTES),
            report_sha256=MARYLAND_INDEPENDENT_REPORT_SHA256,
            root_ids_file=SimpleNamespace(bytes=MARYLAND_BROAD_ROOT_IDS_BYTES),
            root_ids_sha256=MARYLAND_BROAD_ROOT_IDS_SHA256,
            selection_material_file=SimpleNamespace(
                bytes=MARYLAND_BROAD_SELECTION_MATERIAL_BYTES
            ),
            selection_material_sha256=MARYLAND_BROAD_SELECTION_MATERIAL_SHA256,
            selected_relation_count=MARYLAND_ROOT_RELATION_COUNT,
            selected_way_count=MARYLAND_ROOT_WAY_COUNT,
        ),
        selection=SimpleNamespace(
            root_ids=root_ids,
            root_ids_sha256=files[_ROOT_IDS_LOGICAL_NAME].sha256,
            selection_material=material,
            selection_material_sha256=files[
                _SELECTION_MATERIAL_LOGICAL_NAME
            ].sha256,
        ),
    )
    persisted = {
        "runtime-manifest.json": document("runtime-manifest.json"),
        "selection-binding.json": document("selection-binding.json"),
        "selection-command-manifest.json": document(
            "selection-command-manifest.json"
        ),
        "source-identity.json": document("source-identity.json"),
    }
    expected = {
        "runtime-manifest.json": _runtime_manifest_document_from_facts(facts),
        "selection-binding.json": _selection_binding_document_from_facts(facts),
        "selection-command-manifest.json": (
            _selection_command_manifest_document_from_facts(facts)
        ),
        "source-identity.json": _source_identity_document_from_facts(facts),
    }
    for logical_name in persisted:
        if persisted[logical_name] != expected[logical_name]:
            raise ProvenanceVerificationError(
                "persisted manifest graph does not match the independently "
                f"recomputed current source/runtime/command/binding facts: {logical_name}"
            )
    return ProvenanceBundleResult(
        selector_module_sha256=local.selector_sha256,
        document_sha256=tuple(
            (item.logical_name, item.sha256) for item in snapshot
        ),
    )
def _write_locked_pilot_provenance_bundle(target: Path,
    inputs: VerifiedPilotInputs, journal: _PublicationJournal) -> ProvenanceBundleResult:
    if os.path.lexists(target):
        raise FileExistsError(f"provenance destination already exists: {target}")
    verified = _revalidate_verified_inputs(_require_verified_inputs(inputs))
    payloads = {
        "provider-final-url.txt": _captured_bytes(verified.provider.final_url_file),
        "provider-head.txt": _captured_bytes(verified.provider.head_file),
        "provider-md5-sidecar.txt": _captured_bytes(verified.provider.md5_sidecar_file),
        _ROOT_IDS_LOGICAL_NAME: verified.selection.root_ids,
        "runtime-manifest.json": canonical_json_bytes(_runtime_manifest_document(verified)),
        "selection-binding.json": canonical_json_bytes(_selection_binding_document(verified)),
        "selection-command-manifest.json": canonical_json_bytes(
            _selection_command_manifest_document(verified)),
        _SELECTION_MATERIAL_LOGICAL_NAME: verified.selection.selection_material,
        "selector-source.py": _captured_bytes(verified.local.selector_file),
        "source-fileinfo.txt": _captured_bytes(verified.provider.fileinfo_file),
        "source-identity.json": canonical_json_bytes(_source_identity_document(verified)),
    }
    root_document = {
        "files": [{"bytes": len(payloads[filename]), "logicalName": filename,
            "sha256": hashlib.sha256(payloads[filename]).hexdigest()}
            for filename in sorted(payloads)],
        "profile": MARYLAND_REGIONAL_PROFILE,
        "schema": "flight-alert-exp8-osm-provenance-root-v1"}
    payloads["bundle-root.json"] = canonical_json_bytes(root_document)
    journal.begin(target, payloads)
    staging_receipt = _OwnedStagingDirectory(journal=journal)
    installed_snapshot: tuple[_VerifiedFile, ...] | None = None
    held_inputs: list[tuple[_VerifiedFile, int]] = []
    def cleanup_owned_paths(primary: BaseException) -> None:
        failures: list[BaseException] = []
        def recover_created_identity(receipt: _OwnedHandle) -> None:
            if (receipt.handle is not None and receipt.file_key is None and receipt.created_unresolved):
                try:
                    receipt.file_key = _windows_directory_identity(receipt.handle); receipt.created_unresolved = False
                except BaseException as cleanup_error: failures.append(cleanup_error)
        for name, receipt in staging_receipt.files.items():
            recover_created_identity(receipt)
            if (receipt.handle is None and receipt.file_key is not None and staging_receipt.handle is not None):
                try:
                    _acquire_owned_relative_handle(receipt, staging_receipt.handle, name,
                        directory=False, create=False)
                except BaseException as cleanup_error: failures.append(cleanup_error)
            if receipt.handle is None: continue
            retained = False
            try:
                _close_windows_owned_handle(receipt.handle, delete=True,
                    directory=False, retain_on_delete_failure=True)
                receipt.created_unresolved = False
            except BaseException as cleanup_error:
                retained = bool(getattr(cleanup_error, "owned_handle_retained", False)); failures.append(cleanup_error)
            finally:
                if not retained: receipt.handle = None
        directory_deleted = directory_delete_failed = False
        recover_created_identity(staging_receipt)
        if staging_receipt.handle is not None:
            retained = False
            try:
                _close_windows_owned_handle(
                    staging_receipt.handle, delete=True, directory=True,
                    retain_on_delete_failure=True)
                directory_deleted = True; staging_receipt.created_unresolved = False
            except BaseException as cleanup_error:
                retained = bool(getattr(cleanup_error, "owned_handle_retained", False))
                directory_delete_failed = True; failures.append(cleanup_error)
            finally:
                if not retained: staging_receipt.handle = None
        if directory_deleted or (not directory_delete_failed and staging_receipt.file_key is None): return
        path_candidates = tuple(dict.fromkeys(path for path in (staging_receipt.path, target)
            if path is not None))
        expected_files = {name: receipt.file_key for name, receipt in staging_receipt.files.items()}
        removed_exact_owned_path = False
        for path in path_candidates:
            if not os.path.lexists(path): continue
            try:
                removed_exact_owned_path = _remove_owned_install_path(
                    path, expected_file_key=staging_receipt.file_key,
                    expected_files=expected_files) or removed_exact_owned_path
            except BaseException as cleanup_error: failures.append(cleanup_error)
        if removed_exact_owned_path:
            for receipt in (*staging_receipt.files.values(), staging_receipt):
                if receipt.handle is not None:
                    try:
                        _close_windows_owned_handle(receipt.handle, delete=False,
                            directory=receipt is staging_receipt)
                    except BaseException as cleanup_error: failures.append(cleanup_error)
                    else: receipt.handle = None
            if all(receipt.handle is None for receipt in (*staging_receipt.files.values(),
                staging_receipt)): return
        combined = ProvenanceVerificationError(
            "owned provenance cleanup failed after "
            f"{type(primary).__name__}: {primary}; cleanup failures: "
            + " | ".join(f"{type(item).__name__}: {item}" for item in failures))
        combined.owned_path_candidates = path_candidates
        combined.owned_directory_file_key = staging_receipt.file_key
        combined.owned_file_keys = tuple(sorted(expected_files.items()))
        combined.owned_unresolved_receipts = tuple(name for name, receipt in ((".",
            staging_receipt), *staging_receipt.files.items()) if receipt.created_unresolved)
        combined.owned_receipts = tuple((name, receipt.handle, receipt.file_key,
            receipt.created_unresolved) for name, receipt in
            ((".", staging_receipt), *staging_receipt.files.items()))
        raise combined from primary
    def release_owned_paths() -> None:
        for receipt in (*staging_receipt.files.values(), staging_receipt):
            if receipt.handle is None: continue
            try:
                _close_windows_owned_handle(receipt.handle, delete=False,
                    directory=receipt is staging_receipt)
            finally: receipt.handle = None
    try:
        _create_owned_staging_directory(staging_receipt, target.parent,
            prefix=f".{target.name}.", suffix=".staging")
        staging = staging_receipt.path
        staging_file_key = staging_receipt.file_key
        if staging is None or staging_file_key is None:
            raise ProvenanceVerificationError(
                "new staging directory ownership receipt is unavailable")
        owned_stat = os.lstat(staging)
        if (owned_stat.st_dev, owned_stat.st_ino) != staging_file_key:
            raise ProvenanceVerificationError("new staging directory identity changed")
        initial_stat = os.stat(staging, follow_symlinks=False)
        if (initial_stat.st_dev, initial_stat.st_ino) != staging_file_key:
            raise ProvenanceVerificationError("new staging directory identity changed")
        if staging_receipt.handle is None:
            raise ProvenanceVerificationError(
                "new staging directory ownership handle is unavailable")
        receipt_token = _ACTIVE_BUNDLE_WRITE_RECEIPTS.set(staging_receipt)
        try:
            staging_stat = os.lstat(staging)
            if (_is_link_or_reparse(staging, staging_stat) or
                (staging_stat.st_dev, staging_stat.st_ino) != staging_file_key):
                raise ProvenanceVerificationError(
                    "staging directory identity changed before publication")
            for filename in sorted(payloads):
                _write_new_file(staging / filename, payloads[filename])
            staged_snapshot = _verify_exact_directory_payloads(staging, payloads)
            journal.mark_staged()
            held_inputs = _hold_verified_input_files(verified)
            _revalidate_verified_inputs(verified)
            staged_replay = _verify_exact_directory_payloads(staging, payloads)
            if staged_replay != staged_snapshot:
                raise ProvenanceVerificationError(
                    "staged provenance file identity changed during live-input replay")
            journal.mark_renaming()
            _publish_owned_directory(staging_receipt, target)
            journal.mark_installed()
            installed_held = _verify_owned_handle_payloads(staging_receipt, payloads)
            installed_snapshot = _verify_exact_directory_payloads(target, payloads)
            if installed_held != tuple((item.logical_name, item.sha256)
                for item in installed_snapshot):
                raise ProvenanceVerificationError(
                    "installed pathname bytes do not match held objects")
            _verify_owned_path_bindings(staging_receipt)
        finally:
            _ACTIVE_BUNDLE_WRITE_RECEIPTS.reset(receipt_token)
        if installed_snapshot is None:
            raise ProvenanceVerificationError(
                "installed provenance snapshot was not persisted")
        readback = read_pilot_provenance_bundle(target)
        final_held = _verify_owned_handle_payloads(staging_receipt, payloads)
        if readback.document_sha256 != final_held or final_held != installed_held:
            raise ProvenanceVerificationError(
                "public installed bundle readback does not match held file hashes")
        _verify_owned_path_bindings(staging_receipt); _verify_held_input_bindings(held_inputs)
        journal.mark_accepted(); journal.clear()
        _verify_held_input_bindings(held_inputs); final_held = _verify_owned_handle_payloads(staging_receipt, payloads)
        final_paths = tuple((item.logical_name, item.sha256) for item in _verify_exact_directory_payloads(target, payloads))
        if readback.document_sha256 != final_held or final_held != final_paths: raise ProvenanceVerificationError("public installed bundle changed before terminal result")
        _verify_owned_path_bindings(staging_receipt); result = ProvenanceBundleResult(readback.selector_module_sha256, final_held)
        _close_held_input_files(held_inputs); release_owned_paths()
        return result
    except BaseException as error:
        try: _close_held_input_files(held_inputs)
        except BaseException as close_error:
            error.add_note(f"held input release also failed: {close_error}")
        cleanup_owned_paths(error)
        journal.clear()
        raise
def write_pilot_provenance_bundle(destination: str | Path,
    inputs: VerifiedPilotInputs) -> ProvenanceBundleResult:
    _require_verified_inputs(inputs)
    target = Path(destination)
    if (not target.name or target.name in {".", ".."} or target.name in {
        _PUBLICATION_LOCK_NAME, *_PUBLICATION_JOURNAL_NAMES, *_PUBLICATION_JOURNAL_TEMP_NAMES}):
        raise ProvenanceVerificationError("provenance destination name is unavailable")
    target.parent.mkdir(parents=True, exist_ok=True)
    parent = target.parent.resolve(strict=True); target = parent / target.name
    with _PublicationJournal(parent) as journal:
        recovered = journal.recover(target, read_pilot_provenance_bundle)
        if recovered is not None:
            if not isinstance(recovered, ProvenanceBundleResult):
                raise ProvenanceVerificationError("recovered provenance result has an invalid type")
            return recovered
        return _write_locked_pilot_provenance_bundle(target, inputs, journal)
__all__ = ["BOOST_DEB_SHA256", "BOOST_LIBRARY_SHA256", "BOOST_LIBRARY_PATH", "LddDependency", "LocalCodeRuntimeEvidence", "LOCAL_DOWNLOAD_UTC_UNAVAILABLE_REASON", "MARYLAND_BROAD_ROOT_IDS_BYTES", "MARYLAND_BROAD_ROOT_IDS_SHA256", "MARYLAND_BROAD_SELECTION_MATERIAL_BYTES", "MARYLAND_BROAD_SELECTION_MATERIAL_SHA256", "MARYLAND_BROAD_XML_BYTES", "MARYLAND_BROAD_XML_SHA256", "MARYLAND_FINAL_URL_BYTES", "MARYLAND_FINAL_URL_SHA256", "MARYLAND_HEAD_BYTES", "MARYLAND_HEAD_SHA256", "MARYLAND_MD5_SIDECAR_BYTES", "MARYLAND_MD5_SIDECAR_SHA256", "MARYLAND_FILEINFO_BYTES", "MARYLAND_FILEINFO_SHA256", "MARYLAND_INDEPENDENT_REPORT_BYTES", "MARYLAND_INDEPENDENT_REPORT_SHA256", "MARYLAND_PBF_HEADER_TIMESTAMP_UTC", "MARYLAND_PROVIDER_RESPONSE_DATE_UTC", "MARYLAND_ROOT_IDS_BYTES", "MARYLAND_ROOT_IDS_SHA256", "MARYLAND_ROOT_RELATION_COUNT", "MARYLAND_ROOT_WAY_COUNT", "MARYLAND_SELECTION_MATERIAL_BYTES", "MARYLAND_SELECTION_MATERIAL_SHA256", "MAX_ROOT_IDS_BYTES", "MAX_SELECTION_MATERIAL_BYTES", "OSMIUM_BINARY_SHA256", "OSMIUM_DEB_SHA256", "PINNED_PYTHON_EXECUTABLE_SHA256", "PINNED_PYTHON_CACHE_TAG", "PINNED_PYTHON_FLAGS", "PINNED_PYTHON_IMPLEMENTATION", "PINNED_PYTHON_PLATFORM", "PINNED_PYTHON_VERSION", "PINNED_WSL_ARCHITECTURE", "PINNED_WSL_VERSION", "ProvenanceVerificationError", "ProvenanceBundleResult", "VerifiedCandidateFiles", "VerifiedIndependentSelectionEvidence", "VerifiedMarylandSource", "VerifiedPilotInputs", "VerifiedProviderCaptures", "VerifiedSelectionMaterial", "VerifiedWslRuntime", "WATERWAY_CANDIDATES_PBF_BYTES", "WATERWAY_CANDIDATES_PBF_SHA256", "WATERWAY_CANDIDATES_XML_BYTES", "WATERWAY_CANDIDATES_XML_SHA256", "assemble_verified_pilot_inputs", "attest_live_wsl_runtime", "canonical_json_bytes", "read_pilot_provenance_bundle", "runtime_manifest_document", "selection_binding_document", "selection_command_manifest_document", "source_identity_document", "verify_candidate_files", "verify_independent_selection_evidence", "verify_local_code_runtime", "verify_maryland_source_file", "verify_provider_captures", "write_pilot_provenance_bundle"]
