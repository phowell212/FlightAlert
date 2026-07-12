from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath

from tools.experiment8.osm_closure_probe import (
    PINNED_LIBOSMIUM_VERSION,
    PINNED_OSMIUM_VERSION,
    ProcessEvidence,
)
from tools.experiment8.osm_hydro_source import RootSelection


ADMISSION_GENERATOR = "flight-alert-exp8-osm-admission-v1"
MARYLAND_SELECTED_WAY_COUNT = 7_944
MARYLAND_SELECTED_RELATION_COUNT = 102
MARYLAND_COMPLETE_RELATION_COUNT = 78
MARYLAND_ROOT_IDS_BYTES = 88_831
MARYLAND_ROOT_IDS_SHA256 = (
    "3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7"
)

_MAX_OSM_OBJECT_ID = (1 << 63) - 1
_MAX_ID_FILE_BYTES = 8 * 1024 * 1024
_MAX_TRANSCRIPT_BYTES = 2 * 1024 * 1024
_LOWER_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_CANONICAL_ID = re.compile(r"([wr])([1-9][0-9]*)\Z")
_TIMED_LINE = re.compile(r"\[\s*(?:0|[1-9][0-9]*):[0-5][0-9]\] (.*)\Z")
_PEAK_MEMORY = re.compile(r"Peak memory used: (?:0|[1-9][0-9]*) MBytes\Z")
_INDEX_MEMORY = re.compile(r"Memory used for indexes: (?:0|[1-9][0-9]*) MBytes\Z")
_CHECK_COUNTS = re.compile(
    r"There are (0|[1-9][0-9]*) nodes, (0|[1-9][0-9]*) ways, "
    r"and (0|[1-9][0-9]*) relations in this file\.\Z"
)
_MISSING_COUNT_LINES = (
    ("Nodes in ways missing: ", "nodes_in_ways"),
    ("Nodes in relations missing: ", "nodes_in_relations"),
    ("Ways in relations missing: ", "ways_in_relations"),
    ("Relations in relations missing: ", "relations_in_relations"),
)
_RESULT_SEAL = object()


class AdmissionProbeError(RuntimeError):
    """The exact Maryland admission gate failed closed."""


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _canonical_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or _LOWER_SHA256.fullmatch(value) is None:
        raise AdmissionProbeError(f"{label} must be a lowercase SHA-256")
    return value


def _strict_ids(values: tuple[int, ...], label: str) -> tuple[int, ...]:
    if not isinstance(values, tuple):
        raise AdmissionProbeError(f"{label} must be a tuple")
    previous = 0
    for value in values:
        if (
            isinstance(value, bool)
            or not isinstance(value, int)
            or value > _MAX_OSM_OBJECT_ID
            or value <= previous
        ):
            raise AdmissionProbeError(
                f"{label} must be strictly increasing positive signed-63 integers"
            )
        previous = value
    return values


def encode_canonical_id_file(
    way_ids: tuple[int, ...], relation_ids: tuple[int, ...]
) -> bytes:
    """Encode exact osmium ID-file bytes: sorted ways, then sorted relations."""

    ways = _strict_ids(way_ids, "way IDs")
    relations = _strict_ids(relation_ids, "relation IDs")
    if not ways and not relations:
        raise AdmissionProbeError("canonical ID file must contain at least one root")
    content = b"".join(
        (
            *(f"w{object_id}\n".encode("ascii") for object_id in ways),
            *(f"r{object_id}\n".encode("ascii") for object_id in relations),
        )
    )
    if len(content) > _MAX_ID_FILE_BYTES:
        raise AdmissionProbeError("canonical ID file exceeds its byte ceiling")
    return content


def parse_canonical_id_file(raw: bytes) -> RootSelection:
    """Parse only the canonical w<ID>-then-r<ID> LF record form."""

    if type(raw) is not bytes or not raw or len(raw) > _MAX_ID_FILE_BYTES:
        raise AdmissionProbeError("ID file must be nonempty bounded immutable bytes")
    if not raw.endswith(b"\n") or b"\r" in raw or b"\x00" in raw:
        raise AdmissionProbeError("ID file must be canonical LF-terminated ASCII")
    try:
        lines = raw[:-1].decode("ascii", errors="strict").split("\n")
    except UnicodeDecodeError as error:
        raise AdmissionProbeError("ID file is not canonical ASCII") from error
    if not lines or any(not line for line in lines):
        raise AdmissionProbeError("ID file contains an empty record")
    ways: list[int] = []
    relations: list[int] = []
    relation_seen = False
    previous_way = 0
    previous_relation = 0
    for line in lines:
        match = _CANONICAL_ID.fullmatch(line)
        if match is None:
            raise AdmissionProbeError(f"ID record is not canonical: {line!r}")
        object_type, raw_id = match.groups()
        object_id = int(raw_id)
        if object_id > _MAX_OSM_OBJECT_ID:
            raise AdmissionProbeError("ID record exceeds the signed-63 ceiling")
        if object_type == "w":
            if relation_seen or object_id <= previous_way:
                raise AdmissionProbeError(
                    "way IDs must be increasing, unique, and precede relations"
                )
            ways.append(object_id)
            previous_way = object_id
        else:
            relation_seen = True
            if object_id <= previous_relation:
                raise AdmissionProbeError(
                    "relation IDs must be strictly increasing and unique"
                )
            relations.append(object_id)
            previous_relation = object_id
    result = RootSelection(tuple(ways), tuple(relations))
    if encode_canonical_id_file(result.way_ids, result.relation_ids) != raw:
        raise AdmissionProbeError("ID file bytes do not round-trip canonically")
    return result


def _canonical_posix_path(value: str, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise AdmissionProbeError(f"{label} must be nonempty text")
    if any(ord(character) < 32 or ord(character) == 127 for character in value):
        raise AdmissionProbeError(f"{label} contains a control character")
    path = PurePosixPath(value)
    if (
        not path.is_absolute()
        or value.startswith("//")
        or str(path) != value
        or ".." in path.parts
        or "\\" in value
        or value == "/"
    ):
        raise AdmissionProbeError(f"{label} must be canonical absolute POSIX text")
    return value


def _transcript_lines(raw: bytes, label: str) -> list[str]:
    if type(raw) is not bytes or len(raw) > _MAX_TRANSCRIPT_BYTES:
        raise AdmissionProbeError(f"{label} must be bounded exact bytes")
    try:
        text = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise AdmissionProbeError(f"{label} is not strict UTF-8") from error
    if not text.endswith("\n") or "\r" in text or "\x00" in text:
        raise AdmissionProbeError(f"{label} is not canonical LF-terminated text")
    return text[:-1].split("\n")


def _timed_payload(line: str, label: str) -> str:
    match = _TIMED_LINE.fullmatch(line)
    if match is None:
        raise AdmissionProbeError(f"{label} is not a pinned timed diagnostic")
    return match.group(1)


def parse_id_file_getid_result(
    *,
    returncode: int,
    stdout: bytes,
    stderr: bytes,
    source_wsl_path: str,
    output_wsl_path: str,
    expected_way_count: int,
    expected_relation_count: int,
    overwrite: bool,
    fsync: bool,
) -> None:
    """Require the complete pinned id-file getid transcript and Found-all exit 0."""

    source = _canonical_posix_path(source_wsl_path, "getid source path")
    output = _canonical_posix_path(output_wsl_path, "getid output path")
    for value, label in (
        (expected_way_count, "expected way count"),
        (expected_relation_count, "expected relation count"),
    ):
        if isinstance(value, bool) or not isinstance(value, int) or value < 0:
            raise AdmissionProbeError(f"{label} must be a nonnegative integer")
    if expected_way_count + expected_relation_count == 0:
        raise AdmissionProbeError("getid must request at least one root")
    if not isinstance(overwrite, bool) or not isinstance(fsync, bool):
        raise AdmissionProbeError("getid overwrite/fsync expectations must be booleans")
    if isinstance(returncode, bool) or not isinstance(returncode, int):
        raise AdmissionProbeError("getid return code must be an integer")
    if returncode != 0:
        raise AdmissionProbeError(f"getid did not exit zero: {returncode}")
    if stdout != b"":
        raise AdmissionProbeError("getid stdout must be empty for file output")
    lines = _transcript_lines(stderr, "getid transcript")
    expected = [
        "Reading ID file...",
        "Started osmium getid",
        f"  osmium version {PINNED_OSMIUM_VERSION}",
        f"  libosmium version {PINNED_LIBOSMIUM_VERSION}",
        "Command line options and default settings:",
        "  input options:",
        f"    file name: {source}",
        "    file format: ",
        "  output options:",
        f"    file name: {output}",
        "    file format: pbf",
        f"    generator: {ADMISSION_GENERATOR}",
        f"    overwrite: {'yes' if overwrite else 'no'}",
        f"    fsync: {'yes' if fsync else 'no'}",
        "  other options:",
        "    add referenced objects: yes",
        "    remove tags on non-matching objects: no",
        "    work with history files: no",
        "    default object type: node",
        (
            f"    looking for 0 node ID(s), {expected_way_count} way ID(s), "
            f"and {expected_relation_count} relation ID(s)"
        ),
        "Following references...",
    ]
    if expected_relation_count:
        expected.extend(
            (
                "  Reading input file to find relations in relations...",
                "  Reading input file to find nodes/ways in relations...",
            )
        )
    expected.append("  Reading input file to find nodes in ways...")
    expected.extend(
        (
            "Done following references.",
            "Opening input file...",
            "Opening output file...",
            "Copying matching objects to output file...",
            "Closing output file...",
            "Closing input file...",
            "Found all objects.",
            "<peak-memory>",
            "Done.",
        )
    )
    if len(lines) != len(expected):
        raise AdmissionProbeError(
            f"getid transcript line count mismatch: expected {len(expected)}, got {len(lines)}"
        )
    for index, (line, wanted) in enumerate(zip(lines, expected), start=1):
        payload = _timed_payload(line, f"getid transcript line {index}")
        if wanted == "<peak-memory>":
            if _PEAK_MEMORY.fullmatch(payload) is None:
                raise AdmissionProbeError("getid peak-memory diagnostic is malformed")
        elif payload != wanted:
            raise AdmissionProbeError(
                f"getid transcript mismatch at line {index}: expected {wanted!r}"
            )


@dataclass(frozen=True, slots=True)
class CheckRefsSummary:
    node_count: int
    way_count: int
    relation_count: int
    missing_node_references: int
    missing_way_references: int
    missing_relation_references: int
    missing_changeset_references: int


def parse_check_refs_result(
    *,
    returncode: int,
    stdout: bytes,
    stderr: bytes,
    input_wsl_path: str,
) -> CheckRefsSummary:
    """Independently parse the exact pinned check-refs -r zero-missing transcript."""

    input_path = _canonical_posix_path(input_wsl_path, "check-refs input path")
    if isinstance(returncode, bool) or not isinstance(returncode, int):
        raise AdmissionProbeError("check-refs return code must be an integer")
    if stdout != b"":
        raise AdmissionProbeError("check-refs stdout must be empty without --show-ids")
    lines = _transcript_lines(stderr, "check-refs transcript")
    if len(lines) != 21:
        raise AdmissionProbeError(
            f"check-refs transcript line count mismatch: expected 21, got {len(lines)}"
        )
    expected_prefix = (
        "Started osmium check-refs",
        f"  osmium version {PINNED_OSMIUM_VERSION}",
        f"  libosmium version {PINNED_LIBOSMIUM_VERSION}",
        "Command line options and default settings:",
        "  input options:",
        f"    file name: {input_path}",
        "    file format: pbf",
        "  other options:",
        "    show ids: no",
        "    check relations: yes",
        "Reading nodes...",
        "Reading ways...",
        "Reading relations...",
    )
    for index, expected in enumerate(expected_prefix):
        if _timed_payload(lines[index], f"check-refs line {index + 1}") != expected:
            raise AdmissionProbeError(
                f"check-refs transcript mismatch at line {index + 1}"
            )
    counts_match = _CHECK_COUNTS.fullmatch(lines[13])
    if counts_match is None:
        raise AdmissionProbeError("check-refs object-count summary is malformed")
    node_count, way_count, relation_count = (
        int(value) for value in counts_match.groups()
    )
    missing: dict[str, int] = {}
    for index, (prefix, key) in enumerate(_MISSING_COUNT_LINES, start=14):
        line = lines[index]
        if not line.startswith(prefix):
            raise AdmissionProbeError(
                f"check-refs missing-reference row {index - 13} is malformed"
            )
        raw_count = line[len(prefix) :]
        if re.fullmatch(r"0|[1-9][0-9]*", raw_count) is None:
            raise AdmissionProbeError("check-refs missing-reference count is malformed")
        missing[key] = int(raw_count)
    if _INDEX_MEMORY.fullmatch(
        _timed_payload(lines[18], "check-refs index-memory line")
    ) is None:
        raise AdmissionProbeError("check-refs index-memory diagnostic is malformed")
    if _PEAK_MEMORY.fullmatch(
        _timed_payload(lines[19], "check-refs peak-memory line")
    ) is None:
        raise AdmissionProbeError("check-refs peak-memory diagnostic is malformed")
    if _timed_payload(lines[20], "check-refs completion line") != "Done.":
        raise AdmissionProbeError("check-refs completion diagnostic is malformed")
    missing_nodes = missing["nodes_in_ways"] + missing["nodes_in_relations"]
    missing_ways = missing["ways_in_relations"]
    missing_relations = missing["relations_in_relations"]
    # Pinned osmium check-refs reads OSM data, not a changeset file. The exact
    # grammar has no changeset-reference row; any extra row is rejected above.
    missing_changesets = 0
    if returncode != 0:
        raise AdmissionProbeError(f"check-refs did not exit zero: {returncode}")
    if missing_nodes or missing_ways or missing_relations or missing_changesets:
        raise AdmissionProbeError(
            "check-refs reported a missing node/way/relation/changeset reference"
        )
    return CheckRefsSummary(
        node_count=node_count,
        way_count=way_count,
        relation_count=relation_count,
        missing_node_references=missing_nodes,
        missing_way_references=missing_ways,
        missing_relation_references=missing_relations,
        missing_changeset_references=missing_changesets,
    )


@dataclass(frozen=True, slots=True)
class RawProcessCapture:
    label: str
    process: ProcessEvidence

    def __post_init__(self) -> None:
        if (
            not isinstance(self.label, str)
            or re.fullmatch(r"[a-z0-9][a-z0-9/-]*", self.label) is None
            or not isinstance(self.process, ProcessEvidence)
        ):
            raise AdmissionProbeError("raw process capture is malformed")



@dataclass(frozen=True, slots=True)
class MarylandAdmissionResult:
    destination: Path
    output_bytes: int
    output_sha256: str
    direct_way_id_file_bytes: bytes = field(repr=False)
    admitted_id_file_bytes: bytes = field(repr=False)
    semantic_evidence: bytes = field(repr=False)
    raw_processes: tuple[RawProcessCapture, ...] = field(repr=False)
    _seal: object = field(default=None, init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        if self._seal is not _RESULT_SEAL:
            raise AdmissionProbeError(
                "Maryland admission results must be created by the live admission gate"
            )
        if (
            not isinstance(self.destination, Path)
            or not self.destination.is_absolute()
            or isinstance(self.output_bytes, bool)
            or not isinstance(self.output_bytes, int)
            or self.output_bytes <= 0
            or type(self.semantic_evidence) is not bytes
            or not isinstance(self.raw_processes, tuple)
            or any(not isinstance(item, RawProcessCapture) for item in self.raw_processes)
        ):
            raise AdmissionProbeError("sealed admission result fields are inconsistent")
        _canonical_hash(self.output_sha256, "sealed output")
        parse_canonical_id_file(self.direct_way_id_file_bytes)
        parse_canonical_id_file(self.admitted_id_file_bytes)
        try:
            semantic = json.loads(self.semantic_evidence.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AdmissionProbeError("sealed semantic evidence is not JSON") from error
        if _canonical_json_bytes(semantic) != self.semantic_evidence:
            raise AdmissionProbeError("sealed semantic evidence is not canonical JSON")

    @property
    def semantic_evidence_sha256(self) -> str:
        return _sha256(self.semantic_evidence)


def _sealed_result(**values: object) -> MarylandAdmissionResult:
    expected = {
        "destination",
        "output_bytes",
        "output_sha256",
        "direct_way_id_file_bytes",
        "admitted_id_file_bytes",
        "semantic_evidence",
        "raw_processes",
    }
    if set(values) != expected:
        raise RuntimeError("internal admission-result construction mismatch")
    instance = object.__new__(MarylandAdmissionResult)
    for name, value in values.items():
        object.__setattr__(instance, name, value)
    object.__setattr__(instance, "_seal", _RESULT_SEAL)
    instance.__post_init__()
    return instance


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
]
