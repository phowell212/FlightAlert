from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import BinaryIO, Iterator, Mapping, Sequence

from .osm_admission_evidence import (
    ADMISSION_GENERATOR,
    AdmissionProbeError,
    parse_check_refs_result,
    parse_id_file_getid_result,
)
from .osm_global_place_package import (
    BOOST_LIBRARY_PATH,
    BOOST_LIBRARY_SHA256,
    EXPECTED_PLANET_BYTES,
    EXPECTED_PLANET_PATH,
    EXPECTED_PLANET_SHA256,
    OSMIUM_BINARY_PATH,
    OSMIUM_BINARY_SHA256,
    OSMIUM_DEB_SHA256,
    OSMIUM_LIBRARY_PATH,
    OsmiumCommand,
    PINNED_LIBOSMIUM_VERSION,
    PINNED_OSMIUM_VERSION,
    verify_file_identity,
)
from .osm_planet_selection import (
    GLOBAL_POLICY_SHA256,
    LIVE_NAME_ENVELOPE_OPL_BYTES,
    LIVE_NAME_ENVELOPE_OPL_SHA256,
    LIVE_NAME_ENVELOPE_PBF_BYTES,
    LIVE_NAME_ENVELOPE_PBF_SHA256,
    LIVE_NAME_ENVELOPE_PROFILE,
)
from .osm_planet_selection_verifier import (
    LIVE_BROAD_ENVELOPE_OPL_BYTES,
    LIVE_BROAD_ENVELOPE_OPL_SHA256,
    LIVE_BROAD_ENVELOPE_PBF_BYTES,
    LIVE_BROAD_ENVELOPE_PBF_SHA256,
    LIVE_BROAD_ENVELOPE_PROFILE,
)


_POSITIVE_DECIMAL = re.compile(r"[1-9][0-9]*\Z")
_COORDINATE = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]{1,7})?\Z")
_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z"
)
_HEX = frozenset("0123456789abcdefABCDEF")
_MAX_SIGNED_63 = (1 << 63) - 1
_MAX_TEXT_UTF8_BYTES = 65_535
_MAX_LINE_BYTES = 64 * 1024 * 1024
_TYPE_ORDER = {"n": 0, "w": 1, "r": 2}
_REPARSE_POINT = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
SELECTION_INPUT_ROOT = Path(
    r"C:\FlightAlert-exp8-work\planet-260629\global-waterways\inputs"
)
DISPLAY_PBF_PATH = (
    SELECTION_INPUT_ROOT
    / "display-name-envelope-candidates.osmium-1.11.1.osm.pbf"
)
DISPLAY_OPL_PATH = (
    SELECTION_INPUT_ROOT
    / "display-name-envelope-candidates.osmium-1.11.1.opl"
)
BROAD_PBF_PATH = SELECTION_INPUT_ROOT / "true-waterway-broad.osmium-1.11.1.osm.pbf"
BROAD_OPL_PATH = SELECTION_INPUT_ROOT / "true-waterway-broad.osmium-1.11.1.opl"


class GlobalWaterwayPackageError(ValueError):
    """A global waterway closure or V3 package would weaken source truth."""


_PRODUCTION_RENDER_AUTHORITY = object()


@dataclass(frozen=True, slots=True)
class StrictOplMember:
    object_type: str
    ref: int
    role: str
    ordinal: int


@dataclass(frozen=True, slots=True)
class StrictOplNode:
    object_id: int
    version: int
    timestamp: str
    tags: tuple[tuple[str, str], ...]
    longitude_e7: int
    latitude_e7: int


@dataclass(frozen=True, slots=True)
class StrictOplWay:
    object_id: int
    version: int
    timestamp: str
    tags: tuple[tuple[str, str], ...]
    node_refs: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class StrictOplRelation:
    object_id: int
    version: int
    timestamp: str
    tags: tuple[tuple[str, str], ...]
    members: tuple[StrictOplMember, ...]


StrictOplValue = StrictOplNode | StrictOplWay | StrictOplRelation


@dataclass(frozen=True, slots=True)
class StrictOplRecord:
    value: StrictOplValue
    line_number: int
    byte_start: int
    byte_end: int


def _require_sha256(value: object, label: str) -> str:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise GlobalWaterwayPackageError(f"{label} must be lowercase SHA-256")
    return value


@dataclass(frozen=True, slots=True)
class WaterwaySourceBinding:
    planet_path: str
    planet_bytes: int
    planet_sha256: str
    selection_manifest_sha256: str
    selection_policy_sha256: str
    root_ids_bytes: int
    root_ids_sha256: str
    closure_pbf_bytes: int
    closure_pbf_sha256: str
    closure_opl_bytes: int
    closure_opl_sha256: str
    extraction_receipt_sha256: str

    def __post_init__(self) -> None:
        if type(self.planet_path) is not str or not self.planet_path:
            raise GlobalWaterwayPackageError("planet source path binding is empty")
        for value, label in (
            (self.planet_bytes, "planet byte length"),
            (self.root_ids_bytes, "root-ID byte length"),
            (self.closure_pbf_bytes, "closure PBF byte length"),
            (self.closure_opl_bytes, "closure OPL byte length"),
        ):
            if type(value) is not int or value < 0:
                raise GlobalWaterwayPackageError(f"{label} must be nonnegative")
        for value, label in (
            (self.planet_sha256, "planet SHA-256"),
            (self.selection_manifest_sha256, "selection manifest SHA-256"),
            (self.selection_policy_sha256, "selection policy SHA-256"),
            (self.root_ids_sha256, "root-ID SHA-256"),
            (self.closure_pbf_sha256, "closure PBF SHA-256"),
            (self.closure_opl_sha256, "closure OPL SHA-256"),
            (self.extraction_receipt_sha256, "extraction receipt SHA-256"),
        ):
            _require_sha256(value, label)

    def document(self) -> dict[str, object]:
        return {
            "closureOpl": {
                "bytes": self.closure_opl_bytes,
                "sha256": self.closure_opl_sha256,
            },
            "closurePbf": {
                "bytes": self.closure_pbf_bytes,
                "sha256": self.closure_pbf_sha256,
            },
            "extractionReceiptSha256": self.extraction_receipt_sha256,
            "planet": {
                "bytes": self.planet_bytes,
                "path": self.planet_path,
                "sha256": self.planet_sha256,
            },
            "rootIds": {
                "bytes": self.root_ids_bytes,
                "sha256": self.root_ids_sha256,
            },
            "selectionManifestSha256": self.selection_manifest_sha256,
            "selectionPolicySha256": self.selection_policy_sha256,
        }


@dataclass(frozen=True, slots=True)
class GlobalWaterwayIngestResult:
    state: str
    database_path: Path
    receipt: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class GlobalWaterwayBuildResult:
    state: str
    output_directory: Path
    receipt: Mapping[str, object]


@dataclass(frozen=True, slots=True)
class GlobalWaterwayExtractionResult:
    state: str
    output_directory: Path
    receipt: Mapping[str, object]
    source_binding: WaterwaySourceBinding


@dataclass(frozen=True, slots=True)
class CompletedGlobalSelectionRun:
    run_directory: Path
    run_id: str
    attempt_id: str
    selection_manifest_path: Path
    root_ids_path: Path
    complete_marker: Mapping[str, object]
    verification_report: Mapping[str, object]
    verification_observation: Mapping[str, object]

    def document(self) -> dict[str, object]:
        return {
            "attemptId": self.attempt_id,
            "completeMarker": dict(self.complete_marker),
            "directory": str(self.run_directory),
            "runId": self.run_id,
            "verificationObservation": dict(self.verification_observation),
            "verificationReport": dict(self.verification_report),
        }


def _positive_integer(raw: str, label: str) -> int:
    if _POSITIVE_DECIMAL.fullmatch(raw) is None:
        raise GlobalWaterwayPackageError(f"{label} must be a canonical positive integer")
    value = int(raw)
    if value > _MAX_SIGNED_63:
        raise GlobalWaterwayPackageError(f"{label} exceeds signed-63")
    return value


def _decode_opl_string(raw: str, label: str) -> str:
    output: list[str] = []
    index = 0
    while index < len(raw):
        character = raw[index]
        if character != "%":
            output.append(character)
            index += 1
            continue
        end = raw.find("%", index + 1)
        if end < 0:
            raise GlobalWaterwayPackageError(f"{label} has an unterminated OPL escape")
        encoded = raw[index + 1 : end]
        if not 1 <= len(encoded) <= 6 or any(value not in _HEX for value in encoded):
            raise GlobalWaterwayPackageError(f"{label} has a malformed OPL escape")
        scalar = int(encoded, 16)
        if scalar > 0x10FFFF or 0xD800 <= scalar <= 0xDFFF:
            raise GlobalWaterwayPackageError(f"{label} has an invalid Unicode scalar")
        output.append(chr(scalar))
        index = end + 1
    decoded = "".join(output)
    try:
        encoded_utf8 = decoded.encode("utf-8", "strict")
    except UnicodeEncodeError as error:
        raise GlobalWaterwayPackageError(f"{label} is not strict UTF-8") from error
    if len(encoded_utf8) > _MAX_TEXT_UTF8_BYTES:
        raise GlobalWaterwayPackageError(f"{label} exceeds its UTF-8 byte ceiling")
    return decoded


def _coordinate_e7(raw: str, *, latitude: bool, label: str) -> int:
    if _COORDINATE.fullmatch(raw) is None:
        raise GlobalWaterwayPackageError(f"{label} is not a canonical decimal coordinate")
    negative = raw.startswith("-")
    unsigned = raw[1:] if negative else raw
    whole, separator, fraction = unsigned.partition(".")
    value = int(whole) * 10_000_000
    if separator:
        value += int(fraction.ljust(7, "0"))
    if negative:
        value = -value
    maximum = 900_000_000 if latitude else 1_800_000_000
    if not -maximum <= value <= maximum:
        raise GlobalWaterwayPackageError(f"{label} lies outside its coordinate domain")
    return 0 if value == 0 else value


def _parse_tags(raw: str, line_number: int) -> tuple[tuple[str, str], ...]:
    if not raw:
        return ()
    result: list[tuple[str, str]] = []
    keys: set[str] = set()
    for ordinal, item in enumerate(raw.split(","), start=1):
        if item.count("=") != 1:
            raise GlobalWaterwayPackageError(
                f"OPL line {line_number} tag {ordinal} is not one key/value pair"
            )
        encoded_key, encoded_value = item.split("=", 1)
        key = _decode_opl_string(encoded_key, f"OPL line {line_number} tag key")
        value = _decode_opl_string(encoded_value, f"OPL line {line_number} tag value")
        if not key:
            raise GlobalWaterwayPackageError(f"OPL line {line_number} has an empty tag key")
        if key in keys:
            raise GlobalWaterwayPackageError(
                f"OPL line {line_number} repeats tag key {key!r}"
            )
        keys.add(key)
        result.append((key, value))
    return tuple(result)


def _parse_refs(raw: str, line_number: int) -> tuple[int, ...]:
    if not raw:
        return ()
    result: list[int] = []
    for ordinal, item in enumerate(raw.split(","), start=1):
        if not item.startswith("n"):
            raise GlobalWaterwayPackageError(
                f"OPL line {line_number} way ref {ordinal} is not a node"
            )
        result.append(
            _positive_integer(item[1:], f"OPL line {line_number} way ref {ordinal}")
        )
    return tuple(result)


def _parse_members(raw: str, line_number: int) -> tuple[StrictOplMember, ...]:
    if not raw:
        return ()
    result: list[StrictOplMember] = []
    for ordinal, item in enumerate(raw.split(",")):
        if len(item) < 2 or item[0] not in _TYPE_ORDER or "@" not in item:
            raise GlobalWaterwayPackageError(
                f"OPL line {line_number} member {ordinal + 1} is malformed"
            )
        encoded_ref, encoded_role = item[1:].split("@", 1)
        result.append(
            StrictOplMember(
                object_type=item[0],
                ref=_positive_integer(
                    encoded_ref, f"OPL line {line_number} member {ordinal + 1} ref"
                ),
                role=_decode_opl_string(
                    encoded_role, f"OPL line {line_number} member {ordinal + 1} role"
                ),
                ordinal=ordinal,
            )
        )
    return tuple(result)


def _token_fields(raw_line: bytes, line_number: int) -> tuple[str, dict[str, str]]:
    try:
        line = raw_line.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise GlobalWaterwayPackageError(f"OPL line {line_number} is not strict UTF-8") from error
    if not line or line != line.strip(" ") or "\t" in line:
        raise GlobalWaterwayPackageError(
            f"OPL line {line_number} has noncanonical outer whitespace"
        )
    tokens = line.split(" ")
    if any(not token for token in tokens):
        raise GlobalWaterwayPackageError(f"OPL line {line_number} repeats a separator")
    object_token = tokens[0]
    if len(object_token) < 2 or object_token[0] not in _TYPE_ORDER:
        raise GlobalWaterwayPackageError(f"OPL line {line_number} has an unsupported object")
    fields: dict[str, str] = {}
    for token in tokens[1:]:
        prefix = token[0]
        if prefix in fields:
            raise GlobalWaterwayPackageError(
                f"OPL line {line_number} repeats field {prefix!r}"
            )
        fields[prefix] = token[1:]
    return object_token, fields


def _parse_opl_line(raw_line: bytes, line_number: int) -> StrictOplValue:
    object_token, fields = _token_fields(raw_line, line_number)
    object_type = object_token[0]
    object_id = _positive_integer(object_token[1:], f"OPL line {line_number} object ID")
    unknown = set(fields) - {"v", "d", "c", "t", "i", "u", "T", "x", "y", "N", "M"}
    if unknown:
        raise GlobalWaterwayPackageError(
            f"OPL line {line_number} has unsupported fields {sorted(unknown)!r}"
        )
    version = _positive_integer(fields.get("v", ""), f"OPL line {line_number} version")
    visibility = fields.get("d", "V")
    if visibility != "V":
        raise GlobalWaterwayPackageError(f"OPL line {line_number} is not current and visible")
    timestamp = fields.get("t", "")
    if _TIMESTAMP.fullmatch(timestamp) is None:
        raise GlobalWaterwayPackageError(f"OPL line {line_number} timestamp is not canonical UTC")
    tags = _parse_tags(fields.get("T", ""), line_number)
    if object_type == "n":
        if "N" in fields or "M" in fields or "x" not in fields or "y" not in fields:
            raise GlobalWaterwayPackageError(f"OPL line {line_number} node fields are malformed")
        return StrictOplNode(
            object_id,
            version,
            timestamp,
            tags,
            _coordinate_e7(fields["x"], latitude=False, label=f"OPL line {line_number} longitude"),
            _coordinate_e7(fields["y"], latitude=True, label=f"OPL line {line_number} latitude"),
        )
    if "x" in fields or "y" in fields:
        raise GlobalWaterwayPackageError(f"OPL line {line_number} non-node carries coordinates")
    if object_type == "w":
        if "M" in fields or "N" not in fields:
            raise GlobalWaterwayPackageError(f"OPL line {line_number} way fields are malformed")
        return StrictOplWay(
            object_id,
            version,
            timestamp,
            tags,
            _parse_refs(fields["N"], line_number),
        )
    if "N" in fields or "M" not in fields:
        raise GlobalWaterwayPackageError(f"OPL line {line_number} relation fields are malformed")
    return StrictOplRelation(
        object_id,
        version,
        timestamp,
        tags,
        _parse_members(fields["M"], line_number),
    )


def iter_strict_waterway_opl(
    stream: BinaryIO,
    *,
    initial_byte_offset: int = 0,
    initial_line_number: int = 0,
    previous_key: tuple[int, int] | None = None,
) -> Iterator[StrictOplRecord]:
    """Stream current OPL closure objects without normalizing any source field."""

    if not hasattr(stream, "readline"):
        raise GlobalWaterwayPackageError("strict OPL source must be a binary line stream")
    byte_offset = initial_byte_offset
    line_number = initial_line_number
    prior = previous_key
    while True:
        raw = stream.readline(_MAX_LINE_BYTES + 2)
        if not raw:
            break
        line_number += 1
        start = byte_offset
        byte_offset += len(raw)
        if len(raw) > _MAX_LINE_BYTES + 1:
            raise GlobalWaterwayPackageError(f"OPL line {line_number} exceeds its byte ceiling")
        if not raw.endswith(b"\n") or raw.endswith(b"\r\n"):
            raise GlobalWaterwayPackageError(f"OPL line {line_number} is not canonical LF text")
        value = _parse_opl_line(raw[:-1], line_number)
        kind = "n" if isinstance(value, StrictOplNode) else "w" if isinstance(value, StrictOplWay) else "r"
        key = (_TYPE_ORDER[kind], value.object_id)
        if prior is not None and key <= prior:
            raise GlobalWaterwayPackageError(
                "strict OPL closure is not unique Type_then_ID order"
            )
        prior = key
        yield StrictOplRecord(value, line_number, start, byte_offset)


def assemble_exact_relation_paths(
    relation: StrictOplRelation,
    *,
    ways: Mapping[int, StrictOplWay],
    relations: Mapping[int, StrictOplRelation],
    existing_node_ids: frozenset[int],
) -> tuple[tuple[int, ...], ...]:
    """Flatten exact relation membership in order and join shared node IDs only."""

    if not isinstance(relation, StrictOplRelation):
        raise GlobalWaterwayPackageError("relation assembly requires one strict relation")
    if type(existing_node_ids) is not frozenset:
        raise GlobalWaterwayPackageError("relation node inventory must be a frozen set")

    def member_ways(current: StrictOplRelation, stack: tuple[int, ...]) -> Iterator[StrictOplWay]:
        if current.object_id in stack:
            raise GlobalWaterwayPackageError("relation membership cycle is forbidden")
        next_stack = stack + (current.object_id,)
        for expected_ordinal, member in enumerate(current.members):
            if member.ordinal != expected_ordinal:
                raise GlobalWaterwayPackageError("relation member ordinals are not exact")
            if member.object_type == "n":
                if member.ref not in existing_node_ids:
                    raise GlobalWaterwayPackageError(
                        f"relation {current.object_id} references missing node {member.ref}"
                    )
                continue
            if member.object_type == "w":
                way = ways.get(member.ref)
                if way is None:
                    raise GlobalWaterwayPackageError(
                        f"relation {current.object_id} references missing way {member.ref}"
                    )
                yield way
                continue
            if member.object_type == "r":
                nested = relations.get(member.ref)
                if nested is None:
                    raise GlobalWaterwayPackageError(
                        f"relation {current.object_id} references missing relation {member.ref}"
                    )
                yield from member_ways(nested, next_stack)
                continue
            raise GlobalWaterwayPackageError("relation member object type is unsupported")

    parts: list[list[int]] = []
    for way in member_ways(relation, ()):
        if len(way.node_refs) < 2:
            raise GlobalWaterwayPackageError(
                f"relation member way {way.object_id} has fewer than two nodes"
            )
        for node_id in way.node_refs:
            if node_id not in existing_node_ids:
                raise GlobalWaterwayPackageError(
                    f"relation member way {way.object_id} references missing node {node_id}"
                )
        if parts and parts[-1][-1] == way.node_refs[0]:
            parts[-1].extend(way.node_refs[1:])
        else:
            parts.append(list(way.node_refs))
    if not parts:
        raise GlobalWaterwayPackageError(
            f"relation {relation.object_id} contains no usable exact way geometry"
        )
    return tuple(tuple(part) for part in parts)


def build_osmium_closure_commands(
    *,
    planet_linux_path: str,
    root_ids_linux_path: str,
    stage_linux_directory: str,
) -> tuple[OsmiumCommand, ...]:
    for value, label in (
        (planet_linux_path, "planet WSL path"),
        (root_ids_linux_path, "root-ID WSL path"),
        (stage_linux_directory, "stage WSL path"),
    ):
        if type(value) is not str or not value.startswith("/"):
            raise GlobalWaterwayPackageError(f"{label} must be absolute")
    stage = stage_linux_directory.rstrip("/")
    pbf = f"{stage}/waterway-closure.pbf"
    opl = f"{stage}/waterway-closure.opl"
    generator = ADMISSION_GENERATOR
    return (
        OsmiumCommand(
            "fileinfo",
            ("fileinfo", "--no-progress", "-F", "pbf", "-e", "-c", planet_linux_path),
        ),
        OsmiumCommand(
            "getid-recursive",
            (
                "getid",
                "--no-progress",
                "--verbose",
                "-r",
                "--generator",
                generator,
                "-f",
                "pbf",
                "--fsync",
                "-o",
                pbf,
                planet_linux_path,
                "-i",
                root_ids_linux_path,
            ),
        ),
        OsmiumCommand(
            "check-refs",
            ("check-refs", "--no-progress", "--verbose", "-r", "-F", "pbf", pbf),
        ),
        OsmiumCommand(
            "cat-opl",
            (
                "cat",
                "--no-progress",
                "--fsync",
                "--generator",
                generator,
                "-F",
                "pbf",
                "-f",
                "opl",
                "-O",
                pbf,
                "-o",
                opl,
            ),
        ),
        OsmiumCommand(
            "fileinfo",
            ("fileinfo", "--no-progress", "-F", "opl", "-e", "-c", opl),
        ),
    )


def _canonical_json_bytes(document: object) -> bytes:
    try:
        return (
            json.dumps(
                document,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, ValueError, UnicodeError) as error:
        raise GlobalWaterwayPackageError("waterway document is not canonical JSON") from error


def _read_bounded(path: Path, maximum_bytes: int, label: str) -> bytes:
    try:
        with path.open("rb") as handle:
            raw = handle.read(maximum_bytes + 1)
    except OSError as error:
        raise GlobalWaterwayPackageError(f"{label} is not readable") from error
    if len(raw) > maximum_bytes:
        raise GlobalWaterwayPackageError(f"{label} exceeds its byte ceiling")
    return raw


def _stream_identity(path: Path, label: str) -> dict[str, object]:
    try:
        link_status = os.lstat(path) if isinstance(path, Path) else None
    except OSError as error:
        raise GlobalWaterwayPackageError(
            f"{label} is not one regular non-link file"
        ) from error
    if (
        link_status is None
        or not stat.S_ISREG(link_status.st_mode)
        or getattr(link_status, "st_file_attributes", 0) & _REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(f"{label} is not one regular non-link file")
    before = path.stat()
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        opened = os.fstat(handle.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise GlobalWaterwayPackageError(f"{label} changed while opening")
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            total += len(chunk)
        after_handle = os.fstat(handle.fileno())
    after = path.stat()
    signature = (
        before.st_dev,
        before.st_ino,
        before.st_size,
        before.st_mtime_ns,
        before.st_ctime_ns,
    )
    if (
        signature
        != (
            after_handle.st_dev,
            after_handle.st_ino,
            after_handle.st_size,
            after_handle.st_mtime_ns,
            after_handle.st_ctime_ns,
        )
        or signature
        != (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        )
        or total != before.st_size
    ):
        raise GlobalWaterwayPackageError(f"{label} drifted while hashing")
    return {"bytes": total, "sha256": digest.hexdigest()}


def _loaded_windows_module_path(module_name: str) -> Path:
    if os.name != "nt":
        raise GlobalWaterwayPackageError(
            "loaded OpenSSL module identity requires the Windows host"
        )
    import ctypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    get_handle = kernel32.GetModuleHandleW
    get_handle.argtypes = (ctypes.c_wchar_p,)
    get_handle.restype = ctypes.c_void_p
    get_filename = kernel32.GetModuleFileNameW
    get_filename.argtypes = (
        ctypes.c_void_p,
        ctypes.c_wchar_p,
        ctypes.c_uint32,
    )
    get_filename.restype = ctypes.c_uint32
    handle = get_handle(module_name)
    if not handle:
        raise GlobalWaterwayPackageError(
            f"required loaded module {module_name} is absent"
        )
    buffer = ctypes.create_unicode_buffer(32_768)
    length = get_filename(handle, buffer, len(buffer))
    if length == 0 or length >= len(buffer):
        raise GlobalWaterwayPackageError(
            f"loaded module path for {module_name} is unavailable"
        )
    path = Path(buffer.value).resolve(strict=True)
    if path.name.casefold() != module_name.casefold():
        raise GlobalWaterwayPackageError(
            f"loaded module identity for {module_name} differs"
        )
    return path


def python_runtime_identity_document() -> dict[str, object]:
    import _hashlib
    import _ssl
    import _sqlite3
    import codecs
    import encodings.utf_8
    import math
    import pathlib
    import platform
    import sqlite3
    import ssl
    import unicodedata
    import zlib

    executable = Path(sys.executable).resolve(strict=True)
    components = {
        "pythonExecutable": _stream_identity(executable, "Python executable"),
    }
    native_origins: dict[str, str] = {}
    for name, module in (
        ("hashlib", _hashlib),
        ("math", math),
        ("ssl", _ssl),
        ("sqlite", _sqlite3),
        ("unicodedata", unicodedata),
        ("zlib", zlib),
    ):
        raw_path = getattr(module, "__file__", None)
        if type(raw_path) is str and raw_path:
            key = name + "Extension"
            components[key] = _stream_identity(
                Path(raw_path).resolve(strict=True), f"Python {name} extension"
            )
            native_origins[name] = key
        else:
            origin = getattr(getattr(module, "__spec__", None), "origin", None)
            native_origins[name] = str(origin or "built-in")
    loaded_module_origins = {}
    for module_name in ("libcrypto-1_1.dll", "libssl-1_1.dll"):
        path = _loaded_windows_module_path(module_name)
        component = "loadedDll:" + module_name
        components[component] = _stream_identity(
            path, f"loaded OpenSSL module {module_name}"
        )
        loaded_module_origins[module_name] = {
            "component": component,
            "identityMethod": "GetModuleHandleW+GetModuleFileNameW",
            "moduleName": module_name,
            "path": str(path),
        }
    for filename in (
        f"python{sys.version_info.major}{sys.version_info.minor}.dll",
        "python3.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",
    ):
        candidate = executable.parent / filename
        if candidate.is_file():
            components["runtimeDll:" + filename] = _stream_identity(
                candidate, f"Python runtime {filename}"
            )
    stdlib_modules = {
        "codecs": codecs,
        "encodings.utf_8": encodings.utf_8,
        "hashlib": hashlib,
        "json": json,
        "pathlib": pathlib,
        "re": re,
        "ssl": ssl,
    }
    stdlib_files = {}
    for name, module in sorted(stdlib_modules.items()):
        raw_path = getattr(module, "__file__", None)
        if type(raw_path) is not str or not raw_path:
            raise GlobalWaterwayPackageError(
                f"Python stdlib module {name} has no exact source identity"
            )
        stdlib_files[name] = _stream_identity(
            Path(raw_path).resolve(strict=True), f"Python stdlib {name}"
        )
    return {
        "cacheTag": sys.implementation.cache_tag,
        "components": dict(sorted(components.items())),
        "implementation": platform.python_implementation(),
        "loadedModuleOrigins": loaded_module_origins,
        "nativeModuleOrigins": dict(sorted(native_origins.items())),
        "pythonVersion": [
            sys.version_info.major,
            sys.version_info.minor,
            sys.version_info.micro,
            sys.version_info.releaselevel,
            sys.version_info.serial,
        ],
        "schema": "flightalert.experiment8.osm-global-waterway-python-runtime.v3",
        "semantics": {
            "algorithmsAvailable": sorted(hashlib.algorithms_available),
            "byteOrder": sys.byteorder,
            "defaultEncoding": sys.getdefaultencoding(),
            "fileSystemEncoding": sys.getfilesystemencoding(),
            "fileSystemErrors": sys.getfilesystemencodeerrors(),
            "opensslApiVersion": list(ssl._OPENSSL_API_VERSION),
            "opensslVersion": ssl.OPENSSL_VERSION,
            "opensslVersionInfo": list(ssl.OPENSSL_VERSION_INFO),
            "opensslVersionNumber": ssl.OPENSSL_VERSION_NUMBER,
            "unicodeDataVersion": unicodedata.unidata_version,
            "utf8Mode": int(sys.flags.utf8_mode),
        },
        "sqliteVersion": sqlite3.sqlite_version,
        "stdlibFiles": stdlib_files,
        "zlibCompileVersion": zlib.ZLIB_VERSION,
        "zlibRuntimeVersion": zlib.ZLIB_RUNTIME_VERSION,
    }


def _require_exact_directory_inventory(
    directory: Path,
    expected_names: set[str],
    label: str,
) -> dict[str, int]:
    absolute = Path(os.path.abspath(directory)) if isinstance(directory, Path) else None
    if absolute is not None:
        components = list(reversed(absolute.parents)) + [absolute]
        for component in components:
            if not component.exists() and not component.is_symlink():
                continue
            try:
                component_status = os.lstat(component)
            except OSError as error:
                raise GlobalWaterwayPackageError(
                    f"{label} path component is unreadable"
                ) from error
            if (
                stat.S_ISLNK(component_status.st_mode)
                or getattr(component_status, "st_file_attributes", 0)
                & _REPARSE_POINT
            ):
                raise GlobalWaterwayPackageError(
                    f"{label} path contains a symbolic-link or reparse-point alias"
                )
    if (
        not isinstance(directory, Path)
        or not directory.is_dir()
        or directory.is_symlink()
    ):
        raise GlobalWaterwayPackageError(f"{label} is not one real directory")
    try:
        directory_status = os.lstat(directory)
        entries = list(os.scandir(directory))
    except OSError as error:
        raise GlobalWaterwayPackageError(f"{label} inventory is unreadable") from error
    if getattr(directory_status, "st_file_attributes", 0) & _REPARSE_POINT:
        raise GlobalWaterwayPackageError(f"{label} is a reparse-point alias")
    actual_names = {entry.name for entry in entries}
    if actual_names != expected_names:
        raise GlobalWaterwayPackageError(
            f"{label} has unexpected or missing inventory"
        )
    sizes: dict[str, int] = {}
    for entry in entries:
        try:
            status = entry.stat(follow_symlinks=False)
        except OSError as error:
            raise GlobalWaterwayPackageError(
                f"{label} entry {entry.name!r} is unreadable"
            ) from error
        if (
            not stat.S_ISREG(status.st_mode)
            or getattr(status, "st_file_attributes", 0) & _REPARSE_POINT
        ):
            raise GlobalWaterwayPackageError(
                f"{label} entry {entry.name!r} is not one plain regular file"
            )
        sizes[entry.name] = status.st_size
    return sizes


def _directory_identity(path: Path, label: str) -> tuple[int, int]:
    try:
        status = os.lstat(path)
    except OSError as error:
        raise GlobalWaterwayPackageError(f"{label} is unavailable") from error
    if (
        not stat.S_ISDIR(status.st_mode)
        or getattr(status, "st_file_attributes", 0) & _REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(f"{label} is not one plain directory")
    return status.st_dev, status.st_ino


def _atomic_rename_directory_no_replace(source: Path, target: Path) -> None:
    if os.path.lexists(target):
        raise GlobalWaterwayPackageError(
            f"publication destination already exists: {target.name}"
        )
    try:
        if os.name == "nt":
            os.rename(source, target)
            return
        if sys.platform.startswith("linux"):
            import ctypes
            import errno

            library = ctypes.CDLL(None, use_errno=True)
            renameat2 = getattr(library, "renameat2", None)
            if renameat2 is None:
                raise GlobalWaterwayPackageError(
                    "atomic no-replace directory rename is unavailable"
                )
            renameat2.argtypes = (
                ctypes.c_int,
                ctypes.c_char_p,
                ctypes.c_int,
                ctypes.c_char_p,
                ctypes.c_uint,
            )
            renameat2.restype = ctypes.c_int
            result = renameat2(
                -100,
                os.fsencode(source),
                -100,
                os.fsencode(target),
                1,
            )
            if result == 0:
                return
            error_number = ctypes.get_errno()
            if error_number == errno.EEXIST:
                raise GlobalWaterwayPackageError(
                    f"publication destination already exists: {target.name}"
                )
            raise OSError(error_number, os.strerror(error_number), str(target))
        raise GlobalWaterwayPackageError(
            "atomic no-replace directory rename is unsupported on this host"
        )
    except GlobalWaterwayPackageError:
        raise
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "atomic no-replace directory publication failed"
        ) from error


def _publish_directory_no_replace(
    stage: Path,
    target: Path,
    verify_installed,
) -> None:
    if (
        not isinstance(stage, Path)
        or not isinstance(target, Path)
        or not callable(verify_installed)
    ):
        raise GlobalWaterwayPackageError(
            "atomic directory publication arguments are malformed"
        )
    if stage.parent.resolve() != target.parent.resolve():
        raise GlobalWaterwayPackageError(
            "atomic directory publication must stay within one parent"
        )
    source_identity = _directory_identity(stage, "publication staging directory")
    _atomic_rename_directory_no_replace(stage, target)
    installed = True
    try:
        if _directory_identity(target, "installed publication directory") != source_identity:
            raise GlobalWaterwayPackageError(
                "installed publication directory identity differs"
            )
        verify_installed(target)
    except BaseException as primary:
        if installed:
            try:
                if _directory_identity(
                    target, "failed installed publication directory"
                ) != source_identity:
                    raise GlobalWaterwayPackageError(
                        "failed installed publication ownership differs"
                    )
                _atomic_rename_directory_no_replace(target, stage)
                installed = False
                if _directory_identity(
                    stage, "restored publication staging directory"
                ) != source_identity:
                    raise GlobalWaterwayPackageError(
                        "restored publication staging identity differs"
                    )
            except BaseException as recovery:
                raise GlobalWaterwayPackageError(
                    f"failed publication could not be restored: {recovery}"
                ) from primary
        raise


def pinned_runtime_document() -> dict[str, object]:
    return {
        "binaryPath": OSMIUM_BINARY_PATH,
        "binarySha256": OSMIUM_BINARY_SHA256,
        "boostLibraryPath": BOOST_LIBRARY_PATH,
        "boostLibrarySha256": BOOST_LIBRARY_SHA256,
        "debSha256": OSMIUM_DEB_SHA256,
        "libosmiumVersion": PINNED_LIBOSMIUM_VERSION,
        "libraryPath": OSMIUM_LIBRARY_PATH,
        "locale": "C.UTF-8",
        "osmiumVersion": PINNED_OSMIUM_VERSION,
    }


def _validated_osmium_runtime_evidence(value: object) -> dict[str, object]:
    baseline = pinned_runtime_document()
    expected_keys = set(baseline) | {
        "attestationSchema",
        "dependencyFiles",
        "lddTranscript",
        "pythonRuntime",
        "wslKernelTranscript",
    }
    if type(value) is not dict or set(value) != expected_keys:
        raise GlobalWaterwayPackageError(
            "pinned osmium runtime attestation is malformed"
        )
    if any(value.get(key) != expected for key, expected in baseline.items()):
        raise GlobalWaterwayPackageError(
            "pinned osmium baseline identity differs"
        )
    if value.get("attestationSchema") != (
        "flightalert.experiment8.osm-global-waterway-osmium-attestation.v1"
    ):
        raise GlobalWaterwayPackageError(
            "pinned osmium runtime attestation schema differs"
        )
    dependencies = value.get("dependencyFiles")
    if type(dependencies) is not list or not dependencies:
        raise GlobalWaterwayPackageError(
            "pinned osmium dependency inventory is absent"
        )
    normalized_dependencies = []
    previous_path = ""
    for index, item in enumerate(dependencies):
        if type(item) is not dict or set(item) != {"path", "sha256"}:
            raise GlobalWaterwayPackageError(
                f"pinned osmium dependency {index} is malformed"
            )
        path = item.get("path")
        sha256 = item.get("sha256")
        if type(path) is not str or not path.startswith("/") or path <= previous_path:
            raise GlobalWaterwayPackageError(
                "pinned osmium dependency paths are not unique sorted absolutes"
            )
        _require_sha256(sha256, f"pinned osmium dependency {path} SHA-256")
        normalized_dependencies.append({"path": path, "sha256": sha256})
        previous_path = path
    if not any(
        item["path"] == BOOST_LIBRARY_PATH
        and item["sha256"] == BOOST_LIBRARY_SHA256
        for item in normalized_dependencies
    ):
        raise GlobalWaterwayPackageError(
            "pinned Boost dependency is absent from ldd evidence"
        )
    current_python_runtime = python_runtime_identity_document()
    if value.get("pythonRuntime") != current_python_runtime:
        raise GlobalWaterwayPackageError(
            "pinned osmium Python runtime identity differs"
        )
    return {
        **baseline,
        "attestationSchema": value["attestationSchema"],
        "dependencyFiles": normalized_dependencies,
        "lddTranscript": _require_sha_fact(
            value.get("lddTranscript"), "pinned osmium ldd transcript"
        ),
        "pythonRuntime": current_python_runtime,
        "wslKernelTranscript": _require_sha_fact(
            value.get("wslKernelTranscript"), "pinned osmium WSL kernel transcript"
        ),
    }


def _windows_to_wsl_path(path: Path) -> str:
    try:
        completed = subprocess.run(
            ("wsl.exe", "--exec", "wslpath", "-a", str(path.resolve())),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
            timeout=30,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise GlobalWaterwayPackageError("WSL path conversion could not start") from error
    if completed.returncode != 0 or len(completed.stdout) > 16 * 1024:
        raise GlobalWaterwayPackageError("WSL path conversion failed")
    try:
        converted = completed.stdout.decode("utf-8", "strict").strip()
    except UnicodeDecodeError as error:
        raise GlobalWaterwayPackageError("WSL path conversion is not strict UTF-8") from error
    if not converted.startswith("/") or "\0" in converted or "\n" in converted:
        raise GlobalWaterwayPackageError("WSL path conversion is not one absolute path")
    return converted


def _wsl_osmium_argv(arguments: Sequence[str]) -> tuple[str, ...]:
    return (
        "wsl.exe",
        "--exec",
        "env",
        "LC_ALL=C.UTF-8",
        "LANG=C.UTF-8",
        f"LD_LIBRARY_PATH={OSMIUM_LIBRARY_PATH}",
        OSMIUM_BINARY_PATH,
        *arguments,
    )


def _attest_pinned_osmium() -> dict[str, object]:
    def capture(argv: Sequence[str]) -> bytes:
        try:
            completed = subprocess.run(
                tuple(argv),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=False,
                timeout=60,
            )
        except (OSError, subprocess.SubprocessError) as error:
            raise GlobalWaterwayPackageError(
                "pinned osmium attestation could not start"
            ) from error
        if (
            completed.returncode != 0
            or len(completed.stdout) > 64 * 1024
            or len(completed.stderr) > 64 * 1024
        ):
            raise GlobalWaterwayPackageError("pinned osmium attestation failed")
        return completed.stdout

    version_raw = capture(_wsl_osmium_argv(("--version",)))
    try:
        version_text = version_raw.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise GlobalWaterwayPackageError(
            "pinned osmium version output is not strict UTF-8"
        ) from error
    if (
        f"osmium version {PINNED_OSMIUM_VERSION}\n" not in version_text
        or f"libosmium version {PINNED_LIBOSMIUM_VERSION}\n" not in version_text
    ):
        raise GlobalWaterwayPackageError("pinned osmium/libosmium version differs")
    sha_raw = capture(
        (
            "wsl.exe",
            "--exec",
            "/usr/bin/sha256sum",
            OSMIUM_BINARY_PATH,
            BOOST_LIBRARY_PATH,
        )
    )
    try:
        rows = [line.split() for line in sha_raw.decode("ascii", "strict").splitlines()]
    except UnicodeDecodeError as error:
        raise GlobalWaterwayPackageError("pinned osmium SHA output is not ASCII") from error
    digest_by_path = {
        fields[1].lstrip("*"): fields[0] for fields in rows if len(fields) == 2
    }
    if (
        digest_by_path.get(OSMIUM_BINARY_PATH) != OSMIUM_BINARY_SHA256
        or digest_by_path.get(BOOST_LIBRARY_PATH) != BOOST_LIBRARY_SHA256
    ):
        raise GlobalWaterwayPackageError(
            "pinned osmium binary or Boost SHA-256 differs"
        )
    ldd_raw = capture(
        (
            "wsl.exe",
            "--exec",
            "env",
            "LC_ALL=C.UTF-8",
            f"LD_LIBRARY_PATH={OSMIUM_LIBRARY_PATH}",
            "/usr/bin/ldd",
            OSMIUM_BINARY_PATH,
        )
    )
    kernel_raw = capture(("wsl.exe", "--exec", "/usr/bin/uname", "-a"))
    try:
        ldd_text = ldd_raw.decode("utf-8", "strict")
    except UnicodeDecodeError as error:
        raise GlobalWaterwayPackageError(
            "pinned osmium ldd output is not strict UTF-8"
        ) from error
    dependency_paths = sorted(
        {
            token
            for token in re.findall(r"(?:=>\s+)?(/[^\s()]+)", ldd_text)
        }
    )
    if BOOST_LIBRARY_PATH not in dependency_paths:
        raise GlobalWaterwayPackageError(
            "pinned osmium ldd output omits the locked Boost library"
        )
    dependency_sha_raw = capture(
        ("wsl.exe", "--exec", "/usr/bin/sha256sum", *dependency_paths)
    )
    try:
        dependency_rows = [
            line.split()
            for line in dependency_sha_raw.decode("ascii", "strict").splitlines()
        ]
    except UnicodeDecodeError as error:
        raise GlobalWaterwayPackageError(
            "pinned osmium dependency SHA output is not ASCII"
        ) from error
    dependency_sha_by_path = {
        fields[1].lstrip("*"): fields[0]
        for fields in dependency_rows
        if len(fields) == 2
    }
    if set(dependency_sha_by_path) != set(dependency_paths):
        raise GlobalWaterwayPackageError(
            "pinned osmium dependency SHA inventory differs from ldd"
        )
    runtime = {
        **pinned_runtime_document(),
        "attestationSchema": (
            "flightalert.experiment8.osm-global-waterway-osmium-attestation.v1"
        ),
        "dependencyFiles": [
            {"path": path, "sha256": dependency_sha_by_path[path]}
            for path in dependency_paths
        ],
        "lddTranscript": {
            "bytes": len(ldd_raw),
            "sha256": hashlib.sha256(ldd_raw).hexdigest(),
        },
        "pythonRuntime": python_runtime_identity_document(),
        "wslKernelTranscript": {
            "bytes": len(kernel_raw),
            "sha256": hashlib.sha256(kernel_raw).hexdigest(),
        },
    }
    return _validated_osmium_runtime_evidence(runtime)


def _run_osmium_command(
    command: OsmiumCommand,
    stdout_path: Path,
    stderr_path: Path,
) -> int:
    try:
        with stdout_path.open("wb") as stdout, stderr_path.open("wb") as stderr:
            completed = subprocess.run(
                _wsl_osmium_argv(command.arguments),
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                check=False,
            )
    except OSError as error:
        raise GlobalWaterwayPackageError(
            f"pinned osmium {command.role} could not start"
        ) from error
    return completed.returncode


def _require_sha_fact(value: object, label: str) -> dict[str, object]:
    if type(value) is not dict or set(value) != {"bytes", "sha256"}:
        raise GlobalWaterwayPackageError(f"{label} file fact is malformed")
    byte_count = value.get("bytes")
    sha256 = value.get("sha256")
    if type(byte_count) is not int or byte_count < 0:
        raise GlobalWaterwayPackageError(f"{label} byte count is malformed")
    _require_sha256(sha256, f"{label} SHA-256")
    return {"bytes": byte_count, "sha256": sha256}


def _strict_json(raw: bytes, label: str) -> dict[str, object]:
    try:
        document = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise GlobalWaterwayPackageError(f"{label} is not strict JSON") from error
    if type(document) is not dict or _canonical_json_bytes(document) != raw:
        raise GlobalWaterwayPackageError(f"{label} is not canonical JSON")
    return document


def validate_completed_global_selection_run(
    run_directory: Path,
) -> CompletedGlobalSelectionRun:
    """Read back one fully completed producer+verifier selection run."""

    if not isinstance(run_directory, Path):
        raise GlobalWaterwayPackageError(
            "completed global selection run path must be pathlib.Path"
        )
    from . import run_osm_global_selection as selection_runner

    try:
        selected = selection_runner.validate_canonical_path(
            run_directory,
            label="waterway completed global selection run",
            directory=True,
        )
    except selection_runner.RunnerError as error:
        raise GlobalWaterwayPackageError(
            f"completed global selection run is invalid: {error}"
        ) from error
    inspection = selection_runner.inspect_run(selected)
    if inspection.get("state") != "complete":
        reason = inspection.get("reason")
        suffix = f": {reason}" if type(reason) is str and reason else ""
        raise GlobalWaterwayPackageError(
            "selection input is not one completed global selection run" + suffix
        )
    run_id = inspection.get("runId")
    attempt_id = inspection.get("attemptId")
    if (
        type(run_id) is not str
        or run_id != selected.name
        or type(attempt_id) is not str
        or Path(attempt_id).name != attempt_id
        or attempt_id in {".", ".."}
    ):
        raise GlobalWaterwayPackageError(
            "completed global selection run identity is malformed"
        )
    attempt = selected / "attempts" / attempt_id
    manifest_path = attempt / "final-manifest.json"
    root_ids_path = attempt / "production" / "root-ids.txt"
    return CompletedGlobalSelectionRun(
        run_directory=selected,
        run_id=run_id,
        attempt_id=attempt_id,
        selection_manifest_path=manifest_path,
        root_ids_path=root_ids_path,
        complete_marker=_stream_identity(
            selected / "complete.json", "selection complete marker"
        ),
        verification_report=_stream_identity(
            attempt / "verification-report.json", "selection verification report"
        ),
        verification_observation=_stream_identity(
            attempt / "verification-observation.json",
            "selection verification observation",
        ),
    )


def _selection_binding(
    selection_manifest_path: Path,
    root_ids_path: Path,
    *,
    planet_bytes: int,
    planet_sha256: str,
) -> dict[str, object]:
    manifest_identity = _stream_identity(
        selection_manifest_path, "selection final manifest"
    )
    manifest = _strict_json(
        _read_bounded(selection_manifest_path, 16 * 1024 * 1024, "selection final manifest"),
        "selection final manifest",
    )
    if manifest.get("schema") != "flight-alert-exp8-osm-global-final-manifest-v1":
        raise GlobalWaterwayPackageError("selection final manifest schema is unsupported")
    semantic = manifest.get("semantic")
    if type(semantic) is not dict:
        raise GlobalWaterwayPackageError("selection final manifest lacks semantic evidence")
    if manifest.get("semanticSha256") != hashlib.sha256(
        _canonical_json_bytes(semantic)
    ).hexdigest():
        raise GlobalWaterwayPackageError("selection semantic SHA-256 differs")
    profiles = semantic.get("profiles")
    if profiles != {
        "selection": LIVE_NAME_ENVELOPE_PROFILE,
        "verification": LIVE_BROAD_ENVELOPE_PROFILE,
    }:
        raise GlobalWaterwayPackageError("selection profiles are not exact live profiles")
    identities = semantic.get("identities")
    if type(identities) is not dict or identities.get("policySha256") != GLOBAL_POLICY_SHA256:
        raise GlobalWaterwayPackageError("selection policy SHA-256 differs")
    inputs = identities.get("inputs")
    if type(inputs) is not dict:
        raise GlobalWaterwayPackageError("selection manifest lacks exact input identities")
    expected_inputs = {
        "planetPbf": {"bytes": planet_bytes, "sha256": planet_sha256},
        "productionPbf": {
            "bytes": LIVE_NAME_ENVELOPE_PBF_BYTES,
            "sha256": LIVE_NAME_ENVELOPE_PBF_SHA256,
        },
        "productionOpl": {
            "bytes": LIVE_NAME_ENVELOPE_OPL_BYTES,
            "sha256": LIVE_NAME_ENVELOPE_OPL_SHA256,
        },
        "broadPbf": {
            "bytes": LIVE_BROAD_ENVELOPE_PBF_BYTES,
            "sha256": LIVE_BROAD_ENVELOPE_PBF_SHA256,
        },
        "broadOpl": {
            "bytes": LIVE_BROAD_ENVELOPE_OPL_BYTES,
            "sha256": LIVE_BROAD_ENVELOPE_OPL_SHA256,
        },
    }
    for role, expected in expected_inputs.items():
        if _require_sha_fact(inputs.get(role), f"selection {role}") != expected:
            raise GlobalWaterwayPackageError(f"selection {role} identity differs")
    for key in (
        "runnerSha256",
        "runtimeSha256",
        "selectorSha256",
        "verifierSha256",
    ):
        _require_sha256(identities.get(key), f"selection {key}")
    producer = semantic.get("producer")
    verifier = semantic.get("verifier")
    if type(producer) is not dict or type(verifier) is not dict:
        raise GlobalWaterwayPackageError("selection producer/verifier evidence is absent")
    _require_sha256(producer.get("resultSha256"), "selection producer result SHA-256")
    _require_sha256(verifier.get("resultSha256"), "selection verifier result SHA-256")
    artifacts = producer.get("artifacts")
    if type(artifacts) is not list:
        raise GlobalWaterwayPackageError("selection producer artifact inventory is absent")
    root_entries = [
        item for item in artifacts if type(item) is dict and item.get("path") == "root-ids.txt"
    ]
    if len(root_entries) != 1:
        raise GlobalWaterwayPackageError("selection manifest root-ID binding is ambiguous")
    root_entry = root_entries[0]
    root_fact = _require_sha_fact(
        {"bytes": root_entry.get("bytes"), "sha256": root_entry.get("sha256")},
        "selection root IDs",
    )
    actual_root = _stream_identity(root_ids_path, "selection root-ID file")
    if actual_root != root_fact:
        raise GlobalWaterwayPackageError("selection root-ID file identity differs")
    return {
        "manifest": manifest_identity,
        "policySha256": GLOBAL_POLICY_SHA256,
        "producerResultSha256": producer["resultSha256"],
        "profiles": profiles,
        "rootIds": actual_root,
        "verifierResultSha256": verifier["resultSha256"],
    }


def _completed_selection_evidence(value: object) -> dict[str, object]:
    expected_keys = {
        "attemptId",
        "completeMarker",
        "directory",
        "runId",
        "verificationObservation",
        "verificationReport",
    }
    if type(value) is not dict or set(value) != expected_keys:
        raise GlobalWaterwayPackageError(
            "completed global selection run evidence is malformed"
        )
    directory = value.get("directory")
    run_id = value.get("runId")
    attempt_id = value.get("attemptId")
    if (
        type(directory) is not str
        or not directory
        or type(run_id) is not str
        or not run_id
        or type(attempt_id) is not str
        or not attempt_id
        or Path(attempt_id).name != attempt_id
        or attempt_id in {".", ".."}
    ):
        raise GlobalWaterwayPackageError(
            "completed global selection run identity evidence is malformed"
        )
    return {
        "attemptId": attempt_id,
        "completeMarker": _require_sha_fact(
            value.get("completeMarker"), "selection complete marker"
        ),
        "directory": directory,
        "runId": run_id,
        "verificationObservation": _require_sha_fact(
            value.get("verificationObservation"),
            "selection verification observation",
        ),
        "verificationReport": _require_sha_fact(
            value.get("verificationReport"), "selection verification report"
        ),
    }


def _root_counts(path: Path) -> tuple[int, int]:
    previous = (0, 0)
    ways = 0
    relations = 0
    with path.open("rb") as handle:
        line_number = 0
        while True:
            raw = handle.readline(64)
            if not raw:
                break
            line_number += 1
            if len(raw) >= 64 or not raw.endswith(b"\n") or raw.endswith(b"\r\n"):
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is not canonical bounded LF text"
                )
            try:
                text = raw[:-1].decode("ascii", "strict")
            except UnicodeDecodeError as error:
                raise GlobalWaterwayPackageError(
                    f"root-ID line {line_number} is not ASCII"
                ) from error
            if len(text) < 2 or text[0] not in {"w", "r"} or _POSITIVE_DECIMAL.fullmatch(text[1:]) is None:
                raise GlobalWaterwayPackageError(f"root-ID line {line_number} is malformed")
            object_id = _positive_integer(text[1:], f"root-ID line {line_number}")
            key = (1 if text[0] == "w" else 2, object_id)
            if key <= previous:
                raise GlobalWaterwayPackageError("root-ID file is not unique Type_then_ID order")
            previous = key
            if text[0] == "w":
                ways += 1
            else:
                relations += 1
    if not ways and not relations:
        raise GlobalWaterwayPackageError("root-ID file is empty")
    return ways, relations


def _copy_exact(source: Path, destination: Path, expected: Mapping[str, object]) -> None:
    if destination.exists():
        actual = _stream_identity(destination, destination.name)
        if actual != dict(expected):
            raise GlobalWaterwayPackageError(f"staged {destination.name} identity differs")
        return
    digest = hashlib.sha256()
    total = 0
    with source.open("rb") as reader, destination.open("xb") as writer:
        while True:
            chunk = reader.read(1024 * 1024)
            if not chunk:
                break
            writer.write(chunk)
            digest.update(chunk)
            total += len(chunk)
        writer.flush()
        os.fsync(writer.fileno())
    if {"bytes": total, "sha256": digest.hexdigest()} != dict(expected):
        raise GlobalWaterwayPackageError(f"copied {destination.name} identity differs")


def _write_checkpoint(path: Path, document: Mapping[str, object]) -> None:
    raw = _canonical_json_bytes(document)
    temporary = path.with_suffix(".json.tmp")
    with temporary.open("wb") as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _read_checkpoint(path: Path, run_sha256: str) -> dict[str, object]:
    document = _strict_json(
        _read_bounded(path, 16 * 1024 * 1024, "extraction checkpoint"),
        "extraction checkpoint",
    )
    if document.get("runIdentitySha256") != run_sha256:
        raise GlobalWaterwayPackageError("extraction checkpoint identity differs")
    return document


def _validate_checkpoint_files(stage: Path, files: object) -> None:
    if type(files) is not dict:
        raise GlobalWaterwayPackageError("extraction checkpoint files are malformed")
    for name, fact in files.items():
        if type(name) is not str or Path(name).name != name:
            raise GlobalWaterwayPackageError("extraction checkpoint filename is unsafe")
        expected = _require_sha_fact(fact, f"checkpoint {name}")
        if _stream_identity(stage / name, f"checkpoint {name}") != expected:
            raise GlobalWaterwayPackageError(f"checkpoint {name} identity differs")


def _extraction_inventory_names() -> set[str]:
    roles = (
        "fileinfo",
        "getid-recursive",
        "check-refs",
        "cat-opl",
        "fileinfo",
    )
    names = {
        "extraction-checkpoint.json",
        "extraction-receipt.json",
        "root-ids.txt",
        "selection-final-manifest.json",
        "waterway-closure.opl",
        "waterway-closure.pbf",
    }
    for index, role in enumerate(roles, start=1):
        prefix = f"command-{index:02d}-{role}"
        names.add(prefix + ".stdout")
        names.add(prefix + ".stderr")
    return names


def _validate_extraction_commands(
    extraction_directory: Path,
    command_documents: object,
    *,
    selected_way_count: int,
    selected_relation_count: int,
) -> object:
    if type(command_documents) is not list or len(command_documents) != 5:
        raise GlobalWaterwayPackageError("extraction command ledger is incomplete")
    first = command_documents[0]
    getid_document = command_documents[1]
    if type(first) is not dict or type(getid_document) is not dict:
        raise GlobalWaterwayPackageError("extraction command ledger is malformed")
    first_arguments = first.get("arguments")
    getid_arguments = getid_document.get("arguments")
    if type(first_arguments) is not list or type(getid_arguments) is not list:
        raise GlobalWaterwayPackageError("extraction command arguments are malformed")
    if (
        not first_arguments
        or not getid_arguments
        or "-o" not in getid_arguments
        or "-i" not in getid_arguments
    ):
        raise GlobalWaterwayPackageError("extraction getid command is malformed")
    planet_linux = first_arguments[-1]
    root_linux = getid_arguments[getid_arguments.index("-i") + 1]
    output_linux = getid_arguments[getid_arguments.index("-o") + 1]
    if not all(type(value) is str for value in (planet_linux, root_linux, output_linux)):
        raise GlobalWaterwayPackageError("extraction command paths are malformed")
    stage_linux = output_linux.rsplit("/", 1)[0]
    expected_commands = build_osmium_closure_commands(
        planet_linux_path=planet_linux,
        root_ids_linux_path=root_linux,
        stage_linux_directory=stage_linux,
    )
    for index, (document, expected) in enumerate(
        zip(command_documents, expected_commands), start=1
    ):
        if (
            type(document) is not dict
            or document.get("role") != expected.role
            or document.get("arguments") != list(expected.arguments)
            or document.get("exitCode") != 0
            or type(document.get("outputs")) is not list
        ):
            raise GlobalWaterwayPackageError(
                f"extraction command {index} contract differs"
            )
        for output in document["outputs"]:
            if type(output) is not dict or type(output.get("name")) is not str:
                raise GlobalWaterwayPackageError(
                    f"extraction {expected.role} output binding is malformed"
                )
            name = output["name"]
            if Path(name).name != name:
                raise GlobalWaterwayPackageError(
                    f"extraction {expected.role} output name is unsafe"
                )
            fact = _require_sha_fact(
                {"bytes": output.get("bytes"), "sha256": output.get("sha256")},
                f"extraction {expected.role} output {name}",
            )
            if _stream_identity(
                extraction_directory / name,
                f"extraction {expected.role} output {name}",
            ) != fact:
                raise GlobalWaterwayPackageError(
                    f"extraction {expected.role} output identity differs"
                )
    try:
        parse_id_file_getid_result(
            returncode=0,
            stdout=_read_bounded(
                extraction_directory / "command-02-getid-recursive.stdout",
                2 * 1024 * 1024,
                "getid stdout",
            ),
            stderr=_read_bounded(
                extraction_directory / "command-02-getid-recursive.stderr",
                2 * 1024 * 1024,
                "getid stderr",
            ),
            source_wsl_path=planet_linux,
            output_wsl_path=output_linux,
            expected_way_count=selected_way_count,
            expected_relation_count=selected_relation_count,
            overwrite=False,
            fsync=True,
        )
        return parse_check_refs_result(
            returncode=0,
            stdout=_read_bounded(
                extraction_directory / "command-03-check-refs.stdout",
                2 * 1024 * 1024,
                "check-refs stdout",
            ),
            stderr=_read_bounded(
                extraction_directory / "command-03-check-refs.stderr",
                2 * 1024 * 1024,
                "check-refs stderr",
            ),
            input_wsl_path=expected_commands[2].arguments[-1],
        )
    except AdmissionProbeError as error:
        raise GlobalWaterwayPackageError(
            f"extraction getid/check-refs evidence differs: {error}"
        ) from error


def global_waterway_plan_document() -> dict[str, object]:
    commands = build_osmium_closure_commands(
        planet_linux_path="/PLANET_WSL",
        root_ids_linux_path="/ROOT_IDS_WSL",
        stage_linux_directory="/STAGE_WSL",
    )
    return {
        "attribution": {
            "buildMethod": "Exact selected roots, pinned osmium recursive closure, and deterministic V3 adaptive-path cook",
            "copyrightUrl": "https://www.openstreetmap.org/copyright",
            "credit": "OpenStreetMap contributors",
            "databaseNotice": "Contains information from OpenStreetMap, available under ODbL 1.0",
            "databaseLicense": "ODbL-1.0",
            "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
            "sourceOffer": "Reproducible transformation inputs and exact extraction receipt are bound by SHA-256",
        },
        "closureCommands": [
            {"arguments": list(command.arguments), "role": command.role}
            for command in commands
        ],
        "planet": {
            "bytes": EXPECTED_PLANET_BYTES,
            "path": str(EXPECTED_PLANET_PATH),
            "sha256": EXPECTED_PLANET_SHA256,
        },
        "render": {
            "boundedExternalState": "SQLite",
            "fullWorldZ4To11IndexBytes": sum(4**zoom for zoom in range(4, 12)) * 24,
            "hardComponentPackageByteCeiling": 38_500_000_000,
            "hardMandatoryPhoneFootprintBytes": 40_000_000_000,
            "preferredComponentPackageByteCeiling": 23_500_000_000,
            "preferredMandatoryPhoneFootprintBytes": 25_000_000_000,
            "reservedNonComponentFootprintBytes": 1_500_000_000,
            "packageSchemaVersion": 3,
            "pathGeometry": "complete-source-path adaptive-by-integer-zoom",
            "zooms": list(range(4, 12)),
        },
        "runtime": {
            "boostLibraryPath": BOOST_LIBRARY_PATH,
            "boostLibrarySha256": BOOST_LIBRARY_SHA256,
            "libosmiumVersion": PINNED_LIBOSMIUM_VERSION,
            "libraryPath": OSMIUM_LIBRARY_PATH,
            "locale": "C.UTF-8",
            "osmiumBinaryPath": OSMIUM_BINARY_PATH,
            "osmiumBinarySha256": OSMIUM_BINARY_SHA256,
            "osmiumDebSha256": OSMIUM_DEB_SHA256,
            "osmiumVersion": PINNED_OSMIUM_VERSION,
        },
        "schema": "flightalert.experiment8.osm-global-waterway-plan.v1",
        "selection": {
            "broadOpl": {
                "bytes": LIVE_BROAD_ENVELOPE_OPL_BYTES,
                "path": str(BROAD_OPL_PATH),
                "sha256": LIVE_BROAD_ENVELOPE_OPL_SHA256,
            },
            "broadPbf": {
                "bytes": LIVE_BROAD_ENVELOPE_PBF_BYTES,
                "path": str(BROAD_PBF_PATH),
                "sha256": LIVE_BROAD_ENVELOPE_PBF_SHA256,
            },
            "displayOpl": {
                "bytes": LIVE_NAME_ENVELOPE_OPL_BYTES,
                "path": str(DISPLAY_OPL_PATH),
                "sha256": LIVE_NAME_ENVELOPE_OPL_SHA256,
            },
            "displayPbf": {
                "bytes": LIVE_NAME_ENVELOPE_PBF_BYTES,
                "path": str(DISPLAY_PBF_PATH),
                "sha256": LIVE_NAME_ENVELOPE_PBF_SHA256,
            },
            "policySha256": GLOBAL_POLICY_SHA256,
        },
        "typedWaterways": {
            "canal": {"filterId": "labels.canals", "semanticSubtype": 350},
            "river": {"filterId": "labels.rivers", "semanticSubtype": 330},
            "stream": {"filterId": "labels.streams", "semanticSubtype": 340},
            "tidal_channel": {"filterId": "labels.canals", "semanticSubtype": 350},
            "wadi": {"filterId": "labels.streams", "semanticSubtype": 360},
        },
    }


def extract_global_waterway_closure(
    *,
    planet_path: Path = EXPECTED_PLANET_PATH,
    selection_run_directory: Path,
    output_directory: Path,
    expected_planet_bytes: int = EXPECTED_PLANET_BYTES,
    expected_planet_sha256: str = EXPECTED_PLANET_SHA256,
) -> GlobalWaterwayExtractionResult:
    """Run pinned recursive closure from one independently verified selection."""

    if not all(
        isinstance(path, Path)
        for path in (
            planet_path,
            selection_run_directory,
            output_directory,
        )
    ):
        raise GlobalWaterwayPackageError("extraction paths must be pathlib.Path values")
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError("extraction output directory already exists")
    _require_sha256(expected_planet_sha256, "expected planet SHA-256")
    source = verify_file_identity(
        planet_path,
        expected_bytes=expected_planet_bytes,
        expected_sha256=expected_planet_sha256,
    )
    source_signature = source.stat_signature
    completed_selection = validate_completed_global_selection_run(
        selection_run_directory
    )
    selection_manifest_path = completed_selection.selection_manifest_path
    root_ids_path = completed_selection.root_ids_path
    selection = _selection_binding(
        selection_manifest_path,
        root_ids_path,
        planet_bytes=source.bytes,
        planet_sha256=source.sha256,
    )
    selection["completedRun"] = completed_selection.document()
    selected_ways, selected_relations = _root_counts(root_ids_path)
    runtime = _attest_pinned_osmium()
    code = _stream_identity(Path(__file__).resolve(), "waterway extraction code")
    provisional_identity = {
        "code": code,
        "outputName": output_directory.name,
        "runtime": runtime,
        "schema": "flightalert.experiment8.osm-global-waterway-extraction-run.v1",
        "selection": selection,
        "source": {
            "bytes": source.bytes,
            "path": str(planet_path),
            "sha256": source.sha256,
        },
    }
    provisional_sha256 = hashlib.sha256(
        _canonical_json_bytes(provisional_identity)
    ).hexdigest()
    stage = output_directory.with_name(
        output_directory.name + ".partial-" + provisional_sha256[:16]
    )
    created_stage = False
    if not stage.exists():
        stage.mkdir(parents=True, exist_ok=False)
        created_stage = True
    elif stage.is_symlink() or not stage.is_dir():
        raise GlobalWaterwayPackageError("extraction partial path is redirected")
    copied_root = stage / "root-ids.txt"
    copied_manifest = stage / "selection-final-manifest.json"
    _copy_exact(root_ids_path, copied_root, selection["rootIds"])
    _copy_exact(selection_manifest_path, copied_manifest, selection["manifest"])
    copied_selection = _selection_binding(
        copied_manifest,
        copied_root,
        planet_bytes=source.bytes,
        planet_sha256=source.sha256,
    )
    completed_selection_after_copy = validate_completed_global_selection_run(
        selection_run_directory
    )
    if (
        completed_selection_after_copy.document()
        != completed_selection.document()
        or completed_selection_after_copy.selection_manifest_path
        != selection_manifest_path
        or completed_selection_after_copy.root_ids_path != root_ids_path
    ):
        raise GlobalWaterwayPackageError(
            "completed global selection run drifted during extraction"
        )
    copied_selection["completedRun"] = completed_selection_after_copy.document()
    if copied_selection != selection:
        raise GlobalWaterwayPackageError("copied selection evidence differs")
    planet_linux = _windows_to_wsl_path(planet_path)
    stage_linux = _windows_to_wsl_path(stage)
    commands = build_osmium_closure_commands(
        planet_linux_path=planet_linux,
        root_ids_linux_path=stage_linux.rstrip("/") + "/root-ids.txt",
        stage_linux_directory=stage_linux,
    )
    run_identity = {
        **provisional_identity,
        "commands": [list(command.arguments) for command in commands],
    }
    run_sha256 = hashlib.sha256(_canonical_json_bytes(run_identity)).hexdigest()
    checkpoint_path = stage / "extraction-checkpoint.json"
    if checkpoint_path.exists():
        checkpoint = _read_checkpoint(checkpoint_path, run_sha256)
        _validate_checkpoint_files(stage, checkpoint.get("files"))
    elif not created_stage:
        raise GlobalWaterwayPackageError(
            "existing extraction partial directory lacks its exact checkpoint"
        )
    else:
        checkpoint = {
            "executions": [],
            "files": {
                "root-ids.txt": selection["rootIds"],
                "selection-final-manifest.json": selection["manifest"],
            },
            "runIdentity": run_identity,
            "runIdentitySha256": run_sha256,
            "schema": "flightalert.experiment8.osm-global-waterway-extraction-checkpoint.v1",
        }
        _write_checkpoint(checkpoint_path, checkpoint)
    executions = checkpoint.get("executions")
    files = checkpoint.get("files")
    if type(executions) is not list or type(files) is not dict:
        raise GlobalWaterwayPackageError("extraction checkpoint ledger is malformed")
    if len(executions) > len(commands):
        raise GlobalWaterwayPackageError("extraction checkpoint has excess commands")
    for index, execution in enumerate(executions):
        if (
            type(execution) is not dict
            or execution.get("role") != commands[index].role
            or execution.get("arguments") != list(commands[index].arguments)
            or execution.get("exitCode") != 0
        ):
            raise GlobalWaterwayPackageError(
                "extraction checkpoint command ledger differs"
            )
    for command_index, command in enumerate(commands, start=1):
        if command_index <= len(executions):
            continue
        generated_name = None
        if command.role == "getid-recursive":
            generated_name = "waterway-closure.pbf"
        elif command.role == "cat-opl":
            generated_name = "waterway-closure.opl"
        if generated_name is not None:
            generated_path = stage / generated_name
            if generated_path.exists():
                if generated_path.is_symlink() or not generated_path.is_file():
                    raise GlobalWaterwayPackageError(
                        f"failed {command.role} output is redirected"
                    )
                generated_path.unlink()
        prefix = f"command-{command_index:02d}-{command.role}"
        stdout_path = stage / f"{prefix}.stdout"
        stderr_path = stage / f"{prefix}.stderr"
        returncode = _run_osmium_command(command, stdout_path, stderr_path)
        if type(returncode) is not int or returncode != 0:
            raise GlobalWaterwayPackageError(
                f"pinned osmium {command.role} failed with exit {returncode!r}"
            )
        generated_paths = [stdout_path, stderr_path]
        if generated_name is not None:
            generated_paths.append(stage / generated_name)
        output_documents = []
        for generated_path in generated_paths:
            identity = _stream_identity(
                generated_path, f"{command.role} output {generated_path.name}"
            )
            files[generated_path.name] = identity
            output_documents.append({"name": generated_path.name, **identity})
        executions.append(
            {
                "arguments": list(command.arguments),
                "exitCode": returncode,
                "outputs": output_documents,
                "role": command.role,
            }
        )
        _write_checkpoint(checkpoint_path, checkpoint)
    _validate_checkpoint_files(stage, files)
    pbf_path = stage / "waterway-closure.pbf"
    opl_path = stage / "waterway-closure.opl"
    pbf_identity = _stream_identity(pbf_path, "waterway closure PBF")
    opl_identity = _stream_identity(opl_path, "waterway closure OPL")
    getid_stdout = _read_bounded(
        stage / "command-02-getid-recursive.stdout",
        2 * 1024 * 1024,
        "getid stdout",
    )
    getid_stderr = _read_bounded(
        stage / "command-02-getid-recursive.stderr",
        2 * 1024 * 1024,
        "getid stderr",
    )
    check = commands[2]
    check_stdout = _read_bounded(
        stage / "command-03-check-refs.stdout",
        2 * 1024 * 1024,
        "check-refs stdout",
    )
    check_stderr = _read_bounded(
        stage / "command-03-check-refs.stderr",
        2 * 1024 * 1024,
        "check-refs stderr",
    )
    try:
        parse_id_file_getid_result(
            returncode=0,
            stdout=getid_stdout,
            stderr=getid_stderr,
            source_wsl_path=planet_linux,
            output_wsl_path=stage_linux.rstrip("/") + "/waterway-closure.pbf",
            expected_way_count=selected_ways,
            expected_relation_count=selected_relations,
            overwrite=False,
            fsync=True,
        )
        check_summary = parse_check_refs_result(
            returncode=0,
            stdout=check_stdout,
            stderr=check_stderr,
            input_wsl_path=check.arguments[-1],
        )
    except AdmissionProbeError as error:
        raise GlobalWaterwayPackageError(
            f"pinned recursive closure evidence failed: {error}"
        ) from error
    opl_counts = {"nodes": 0, "ways": 0, "relations": 0}
    with opl_path.open("rb") as handle:
        for parsed in iter_strict_waterway_opl(handle):
            if isinstance(parsed.value, StrictOplNode):
                opl_counts["nodes"] += 1
            elif isinstance(parsed.value, StrictOplWay):
                opl_counts["ways"] += 1
            else:
                opl_counts["relations"] += 1
    if (
        opl_counts["nodes"] != check_summary.node_count
        or opl_counts["ways"] != check_summary.way_count
        or opl_counts["relations"] != check_summary.relation_count
    ):
        raise GlobalWaterwayPackageError(
            "strict OPL inventory differs from pinned check-refs evidence"
        )
    checkpoint_identity = _stream_identity(
        checkpoint_path, "waterway extraction checkpoint"
    )
    receipt: dict[str, object] = {
        "artifacts": {
            "checkpoint": {"name": checkpoint_path.name, **checkpoint_identity},
            "closureOpl": {"name": opl_path.name, **opl_identity},
            "closurePbf": {"name": pbf_path.name, **pbf_identity},
            "rootIds": {"name": copied_root.name, **selection["rootIds"]},
            "selectionManifest": {
                "name": copied_manifest.name,
                **selection["manifest"],
            },
        },
        "closureAudit": {
            "missingReferences": 0,
            "nodes": opl_counts["nodes"],
            "oplObjects": sum(opl_counts.values()),
            "relations": opl_counts["relations"],
            "selectedRelationRoots": selected_relations,
            "selectedWayRoots": selected_ways,
            "ways": opl_counts["ways"],
        },
        "code": code,
        "commands": executions,
        "runIdentitySha256": run_sha256,
        "runtime": runtime,
        "schema": "flightalert.experiment8.osm-global-waterway-extraction.v1",
        "selection": selection,
        "source": {
            "bytes": source.bytes,
            "path": str(planet_path),
            "sha256": source.sha256,
        },
    }
    receipt_bytes = _canonical_json_bytes(receipt)
    receipt_path = stage / "extraction-receipt.json"
    with receipt_path.open("wb") as handle:
        handle.write(receipt_bytes)
        handle.flush()
        os.fsync(handle.fileno())
    if source_signature != (
        planet_path.stat().st_dev,
        planet_path.stat().st_ino,
        planet_path.stat().st_size,
        planet_path.stat().st_mtime_ns,
        planet_path.stat().st_ctime_ns,
    ):
        raise GlobalWaterwayPackageError("planet source identity drifted during extraction")
    if _stream_identity(Path(__file__).resolve(), "waterway extraction code") != code:
        raise GlobalWaterwayPackageError("waterway extraction code identity drifted")
    _require_exact_directory_inventory(
        stage,
        _extraction_inventory_names(),
        "global waterway extraction partial directory",
    )
    _validate_checkpoint_files(stage, files)
    if _stream_identity(
        checkpoint_path, "waterway extraction checkpoint"
    ) != checkpoint_identity:
        raise GlobalWaterwayPackageError(
            "waterway extraction checkpoint drifted before publication"
        )
    receipt_identity = {
        "bytes": len(receipt_bytes),
        "sha256": hashlib.sha256(receipt_bytes).hexdigest(),
    }
    if _stream_identity(
        receipt_path, "waterway extraction receipt"
    ) != receipt_identity:
        raise GlobalWaterwayPackageError(
            "waterway extraction receipt drifted before publication"
        )

    def verify_published_extraction(installed: Path) -> None:
        _require_exact_directory_inventory(
            installed,
            _extraction_inventory_names(),
            "published global waterway extraction directory",
        )
        _validate_checkpoint_files(installed, files)
        if _stream_identity(
            installed / checkpoint_path.name,
            "published waterway extraction checkpoint",
        ) != checkpoint_identity:
            raise GlobalWaterwayPackageError(
                "published waterway extraction checkpoint differs"
            )
        if _stream_identity(
            installed / receipt_path.name,
            "published waterway extraction receipt",
        ) != receipt_identity:
            raise GlobalWaterwayPackageError(
                "published waterway extraction receipt differs"
            )

    _publish_directory_no_replace(
        stage,
        output_directory,
        verify_published_extraction,
    )
    receipt_sha256 = hashlib.sha256(receipt_bytes).hexdigest()
    binding = WaterwaySourceBinding(
        planet_path=str(planet_path),
        planet_bytes=source.bytes,
        planet_sha256=source.sha256,
        selection_manifest_sha256=str(selection["manifest"]["sha256"]),
        selection_policy_sha256=GLOBAL_POLICY_SHA256,
        root_ids_bytes=int(selection["rootIds"]["bytes"]),
        root_ids_sha256=str(selection["rootIds"]["sha256"]),
        closure_pbf_bytes=int(pbf_identity["bytes"]),
        closure_pbf_sha256=str(pbf_identity["sha256"]),
        closure_opl_bytes=int(opl_identity["bytes"]),
        closure_opl_sha256=str(opl_identity["sha256"]),
        extraction_receipt_sha256=receipt_sha256,
    )
    return GlobalWaterwayExtractionResult(
        "complete", output_directory, MappingProxyType(receipt), binding
    )


def source_binding_from_extraction_receipt(
    extraction_directory: Path,
) -> WaterwaySourceBinding:
    if (
        not isinstance(extraction_directory, Path)
        or not extraction_directory.is_dir()
        or extraction_directory.is_symlink()
    ):
        raise GlobalWaterwayPackageError("extraction directory is not one real directory")
    _require_exact_directory_inventory(
        extraction_directory,
        _extraction_inventory_names(),
        "published global waterway extraction directory",
    )
    receipt_path = extraction_directory / "extraction-receipt.json"
    receipt_identity = _stream_identity(receipt_path, "extraction receipt")
    receipt = _strict_json(
        _read_bounded(receipt_path, 16 * 1024 * 1024, "extraction receipt"),
        "extraction receipt",
    )
    if receipt.get("schema") != "flightalert.experiment8.osm-global-waterway-extraction.v1":
        raise GlobalWaterwayPackageError("extraction receipt schema is unsupported")
    source = receipt.get("source")
    artifacts = receipt.get("artifacts")
    if type(source) is not dict or type(artifacts) is not dict:
        raise GlobalWaterwayPackageError("extraction receipt source/artifacts are malformed")
    code_fact = _require_sha_fact(receipt.get("code"), "extraction code")
    if _stream_identity(Path(__file__).resolve(), "waterway extraction code") != code_fact:
        raise GlobalWaterwayPackageError("extraction code identity differs")
    source_fact = _require_sha_fact(
        {"bytes": source.get("bytes"), "sha256": source.get("sha256")},
        "extraction planet",
    )
    if type(source.get("path")) is not str or not source["path"]:
        raise GlobalWaterwayPackageError("extraction planet path is malformed")
    expected_names = {
        "checkpoint": "extraction-checkpoint.json",
        "closureOpl": "waterway-closure.opl",
        "closurePbf": "waterway-closure.pbf",
        "rootIds": "root-ids.txt",
        "selectionManifest": "selection-final-manifest.json",
    }
    facts: dict[str, dict[str, object]] = {}
    for key, name in expected_names.items():
        item = artifacts.get(key)
        if type(item) is not dict or item.get("name") != name:
            raise GlobalWaterwayPackageError(f"extraction {key} artifact is malformed")
        fact = _require_sha_fact(
            {"bytes": item.get("bytes"), "sha256": item.get("sha256")},
            f"extraction {key}",
        )
        if _stream_identity(extraction_directory / name, f"extraction {key}") != fact:
            raise GlobalWaterwayPackageError(f"extraction {key} identity differs")
        facts[key] = fact
    selection = _selection_binding(
        extraction_directory / "selection-final-manifest.json",
        extraction_directory / "root-ids.txt",
        planet_bytes=int(source_fact["bytes"]),
        planet_sha256=str(source_fact["sha256"]),
    )
    receipt_selection = receipt.get("selection")
    if type(receipt_selection) is not dict:
        raise GlobalWaterwayPackageError(
            "extraction completed selection evidence is absent"
        )
    selection["completedRun"] = _completed_selection_evidence(
        receipt_selection.get("completedRun")
    )
    if receipt_selection != selection:
        raise GlobalWaterwayPackageError("extraction selection evidence differs")
    audit = receipt.get("closureAudit")
    if type(audit) is not dict or audit.get("missingReferences") != 0:
        raise GlobalWaterwayPackageError("extraction closure audit is incomplete")
    selected_ways, selected_relations = _root_counts(
        extraction_directory / "root-ids.txt"
    )
    if (
        audit.get("selectedWayRoots") != selected_ways
        or audit.get("selectedRelationRoots") != selected_relations
    ):
        raise GlobalWaterwayPackageError("extraction selected-root audit differs")
    check_summary = _validate_extraction_commands(
        extraction_directory,
        receipt.get("commands"),
        selected_way_count=selected_ways,
        selected_relation_count=selected_relations,
    )
    counts = {"nodes": 0, "ways": 0, "relations": 0}
    with (extraction_directory / "waterway-closure.opl").open("rb") as handle:
        for parsed in iter_strict_waterway_opl(handle):
            if isinstance(parsed.value, StrictOplNode):
                counts["nodes"] += 1
            elif isinstance(parsed.value, StrictOplWay):
                counts["ways"] += 1
            else:
                counts["relations"] += 1
    if any(audit.get(key) != value for key, value in counts.items()) or audit.get(
        "oplObjects"
    ) != sum(counts.values()):
        raise GlobalWaterwayPackageError("extraction strict OPL audit differs")
    if (
        check_summary.node_count != counts["nodes"]
        or check_summary.way_count != counts["ways"]
        or check_summary.relation_count != counts["relations"]
    ):
        raise GlobalWaterwayPackageError(
            "extraction check-refs inventory differs from strict OPL"
        )
    if _validated_osmium_runtime_evidence(receipt.get("runtime")) != receipt.get(
        "runtime"
    ):
        raise GlobalWaterwayPackageError("extraction pinned runtime identity differs")
    return WaterwaySourceBinding(
        planet_path=str(source["path"]),
        planet_bytes=int(source_fact["bytes"]),
        planet_sha256=str(source_fact["sha256"]),
        selection_manifest_sha256=str(facts["selectionManifest"]["sha256"]),
        selection_policy_sha256=GLOBAL_POLICY_SHA256,
        root_ids_bytes=int(facts["rootIds"]["bytes"]),
        root_ids_sha256=str(facts["rootIds"]["sha256"]),
        closure_pbf_bytes=int(facts["closurePbf"]["bytes"]),
        closure_pbf_sha256=str(facts["closurePbf"]["sha256"]),
        closure_opl_bytes=int(facts["closureOpl"]["bytes"]),
        closure_opl_sha256=str(facts["closureOpl"]["sha256"]),
        extraction_receipt_sha256=str(receipt_identity["sha256"]),
    )


def render_global_waterway_package(
    *,
    extraction_directory: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    zooms: tuple[int, ...] = tuple(range(4, 12)),
    checkpoint_objects: int = 10_000,
    checkpoint_features: int = 100,
    pause_after_objects: int | None = None,
    pause_after_features: int | None = None,
) -> GlobalWaterwayBuildResult:
    """Render production only from a fully revalidated extraction receipt."""

    if not isinstance(extraction_directory, Path):
        raise GlobalWaterwayPackageError(
            "global waterway extraction path must be pathlib.Path"
        )
    source_binding = source_binding_from_extraction_receipt(extraction_directory)
    if (
        source_binding.planet_bytes != EXPECTED_PLANET_BYTES
        or source_binding.planet_sha256 != EXPECTED_PLANET_SHA256
        or source_binding.planet_path.startswith("fixture://")
    ):
        raise GlobalWaterwayPackageError(
            "render extraction is not bound to the exact verified 260629 planet"
        )
    from .osm_global_waterway_store import (
        _render_bound_global_waterway_package as implementation,
    )

    return implementation(
        opl_path=extraction_directory / "waterway-closure.opl",
        root_ids_path=extraction_directory / "root-ids.txt",
        output_directory=output_directory,
        work_directory=work_directory,
        package_id=package_id,
        source_binding=source_binding,
        zooms=zooms,
        checkpoint_objects=checkpoint_objects,
        checkpoint_features=checkpoint_features,
        pause_after_objects=pause_after_objects,
        pause_after_features=pause_after_features,
        production_authority=_PRODUCTION_RENDER_AUTHORITY,
    )


def render_fixture_global_waterway_package(
    *,
    opl_path: Path,
    root_ids_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    zooms: tuple[int, ...] = tuple(range(4, 12)),
    checkpoint_objects: int = 10_000,
    checkpoint_features: int = 100,
    pause_after_objects: int | None = None,
    pause_after_features: int | None = None,
) -> GlobalWaterwayBuildResult:
    if (
        not isinstance(source_binding, WaterwaySourceBinding)
        or not source_binding.planet_path.startswith("fixture://")
    ):
        raise GlobalWaterwayPackageError(
            "fixture waterway rendering requires an explicit fixture:// binding"
        )
    from .osm_global_waterway_store import (
        render_fixture_global_waterway_package as implementation,
    )

    return implementation(
        opl_path=opl_path,
        root_ids_path=root_ids_path,
        output_directory=output_directory,
        work_directory=work_directory,
        package_id=package_id,
        source_binding=source_binding,
        zooms=zooms,
        checkpoint_objects=checkpoint_objects,
        checkpoint_features=checkpoint_features,
        pause_after_objects=pause_after_objects,
        pause_after_features=pause_after_features,
    )


def _argument_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="osm_global_waterway_package.py",
        description=(
            "Build the exact whole-world OSM named-waterway Experiment 8 V3 supplement."
        ),
    )
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("plan", help="print the immutable production plan without reading files")
    extract = commands.add_parser(
        "extract", help="verify selection and planet, then run pinned recursive closure"
    )
    extract.add_argument("--planet", type=Path, default=EXPECTED_PLANET_PATH)
    extract.add_argument("--selection-run", type=Path, required=True)
    extract.add_argument("--output", type=Path, required=True)
    render = commands.add_parser(
        "render", help="render one exact completed recursive closure"
    )
    render.add_argument("--extraction", type=Path, required=True)
    render.add_argument("--output", type=Path, required=True)
    render.add_argument("--work", type=Path, required=True)
    render.add_argument(
        "--package-id", default="world-osm-named-waterways-260629-v3"
    )
    render.add_argument("--checkpoint-objects", type=int, default=10_000)
    render.add_argument("--checkpoint-features", type=int, default=100)
    return parser


def _write_json(stream: object, document: object) -> None:
    raw = _canonical_json_bytes(document)
    binary = getattr(stream, "buffer", None)
    if binary is not None:
        binary.write(raw)
        binary.flush()
    else:
        stream.write(raw.decode("utf-8"))
        stream.flush()


def main(argv: Sequence[str] | None = None) -> int:
    arguments = _argument_parser().parse_args(argv)
    try:
        if arguments.command == "plan":
            _write_json(sys.stdout, global_waterway_plan_document())
            return 0
        if arguments.command == "extract":
            result = extract_global_waterway_closure(
                planet_path=arguments.planet,
                selection_run_directory=arguments.selection_run,
                output_directory=arguments.output,
            )
        else:
            result = render_global_waterway_package(
                extraction_directory=arguments.extraction,
                output_directory=arguments.output,
                work_directory=arguments.work,
                package_id=arguments.package_id,
                zooms=tuple(range(4, 12)),
                checkpoint_objects=arguments.checkpoint_objects,
                checkpoint_features=arguments.checkpoint_features,
            )
        _write_json(
            sys.stdout,
            {
                "outputDirectory": str(result.output_directory),
                "receipt": dict(result.receipt),
                "state": result.state,
            },
        )
        return 0
    except GlobalWaterwayPackageError as error:
        _write_json(
            sys.stderr,
            {
                "error": {
                    "class": f"{type(error).__module__}.{type(error).__qualname__}",
                    "message": str(error),
                },
                "state": "failed",
            },
        )
        return 2


__all__ = [
    "CompletedGlobalSelectionRun",
    "GlobalWaterwayPackageError",
    "StrictOplMember",
    "StrictOplNode",
    "StrictOplRecord",
    "StrictOplRelation",
    "StrictOplWay",
    "GlobalWaterwayIngestResult",
    "GlobalWaterwayBuildResult",
    "GlobalWaterwayExtractionResult",
    "WaterwaySourceBinding",
    "assemble_exact_relation_paths",
    "build_osmium_closure_commands",
    "extract_global_waterway_closure",
    "global_waterway_plan_document",
    "iter_strict_waterway_opl",
    "validate_completed_global_selection_run",
    "main",
    "pinned_runtime_document",
    "render_global_waterway_package",
    "render_fixture_global_waterway_package",
    "source_binding_from_extraction_receipt",
]


if __name__ == "__main__":
    from tools.experiment8.osm_global_waterway_package import main as canonical_main

    raise SystemExit(canonical_main())
