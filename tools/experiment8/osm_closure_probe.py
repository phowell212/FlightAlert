from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import threading
from dataclasses import dataclass
from pathlib import PurePosixPath
from typing import BinaryIO

from tools.experiment8.osm_hydro_source import MissingReferences


PINNED_UBUNTU_DISTRIBUTION = "Ubuntu"
PINNED_UBUNTU_RELEASE = "Ubuntu 20.04.3 LTS"
PINNED_LOCALE = "C.UTF-8"
PINNED_OSMIUM_VERSION = "1.11.1"
PINNED_LIBOSMIUM_VERSION = "2.15.4"
WSL_EXECUTABLE = r"C:\Windows\System32\wsl.exe"
RUNTIME_ROOT = "/home/haquilus/flightalert-exp8-tools/osmium-1.11.1/root"
OSMIUM_BINARY_PATH = f"{RUNTIME_ROOT}/usr/bin/osmium"
BOOST_LIBRARY_PATH = (
    f"{RUNTIME_ROOT}/usr/lib/x86_64-linux-gnu/"
    "libboost_program_options.so.1.71.0"
)
RUNTIME_LIBRARY_PATH = f"{RUNTIME_ROOT}/usr/lib/x86_64-linux-gnu"
OSMIUM_BINARY_SHA256 = (
    "5575922905fb39fa87262e74ed2b0ac367086b5439468339e45bf3720c1821fc"
)
BOOST_LIBRARY_SHA256 = (
    "16a89b0d75de54bfef18b479eb1d38710e5c242246a17baffa11eb4f2d544663"
)
MAX_CAPTURE_BYTES = 256 * 1024
PROCESS_TIMEOUT_SECONDS = 120.0

_MAX_OSM_OBJECT_ID = (1 << 63) - 1
_LOWER_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_TIMED_LINE = re.compile(r"\[\s*(0|[1-9][0-9]*):([0-5][0-9])\] (.*)\Z")
_MISSING_SUMMARY = re.compile(r"Did not find ([1-9][0-9]*) object\(s\)\.\Z")
_MISSING_IDS = re.compile(
    r"Missing (node|way|relation) IDs: ([0-9]+(?: [0-9]+)*)\Z"
)
_PEAK_MEMORY = re.compile(r"Peak memory used: (0|[1-9][0-9]*) MBytes\Z")


class ClosureProbeError(RuntimeError):
    """The pinned closure probe could not produce an unambiguous audit result."""

    def __init__(
        self,
        message: str,
        *,
        process_evidence: ProcessEvidence | None = None,
    ) -> None:
        super().__init__(message)
        self.process_evidence = process_evidence


def _canonical_argv_bytes(argv: tuple[str, ...]) -> bytes:
    return (
        json.dumps(list(argv), ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode("utf-8")


@dataclass(frozen=True, slots=True)
class ProcessEvidence:
    argv: tuple[str, ...]
    returncode: int
    stdout: bytes
    stderr: bytes

    def __post_init__(self) -> None:
        if (
            not isinstance(self.argv, tuple)
            or not self.argv
            or any(not isinstance(value, str) or not value for value in self.argv)
        ):
            raise TypeError("process argv must be a nonempty tuple of nonempty strings")
        if isinstance(self.returncode, bool) or not isinstance(self.returncode, int):
            raise TypeError("process return code must be an integer")
        if not isinstance(self.stdout, bytes) or not isinstance(self.stderr, bytes):
            raise TypeError("process output must be exact bytes")

    @property
    def argv_sha256(self) -> str:
        return hashlib.sha256(_canonical_argv_bytes(self.argv)).hexdigest()

    @property
    def stdout_sha256(self) -> str:
        return hashlib.sha256(self.stdout).hexdigest()

    @property
    def stderr_sha256(self) -> str:
        return hashlib.sha256(self.stderr).hexdigest()


@dataclass(frozen=True, slots=True)
class RuntimeAttestation:
    ubuntu_distribution: str
    ubuntu_release: str
    locale: str
    runtime_root: str
    osmium_binary_path: str
    osmium_binary_sha256: str
    boost_library_path: str
    boost_library_sha256: str
    release_process: ProcessEvidence
    hash_process: ProcessEvidence


@dataclass(frozen=True, slots=True)
class ClosureProbeEvidence:
    relation_ids: tuple[int, ...]
    source_wsl_path: str
    source_sha256: str
    missing_references: MissingReferences
    runtime: RuntimeAttestation
    process: ProcessEvidence
    post_hash_process: ProcessEvidence

    @property
    def status(self) -> str:
        return "source_incomplete" if self.missing_references.count else "complete"


@dataclass(slots=True)
class _LimitedCapture:
    maximum: int
    content: bytearray
    overflowed: bool = False
    error: BaseException | None = None

    def add(self, chunk: bytes) -> None:
        remaining = self.maximum + 1 - len(self.content)
        if remaining > 0:
            self.content.extend(chunk[:remaining])
        if len(chunk) > remaining or len(self.content) > self.maximum:
            self.overflowed = True


def _read_pipe(stream: BinaryIO, capture: _LimitedCapture) -> None:
    try:
        while True:
            chunk = stream.read(16 * 1024)
            if not chunk:
                break
            capture.add(chunk)
    except BaseException as error:
        capture.error = error
    finally:
        try:
            stream.close()
        except OSError as error:
            if capture.error is None:
                capture.error = error


def _validated_process_limits(
    max_output_bytes: int, timeout_seconds: float
) -> tuple[int, float]:
    if (
        isinstance(max_output_bytes, bool)
        or not isinstance(max_output_bytes, int)
        or max_output_bytes <= 0
    ):
        raise ClosureProbeError("maximum process output must be a positive integer")
    if (
        isinstance(timeout_seconds, bool)
        or not isinstance(timeout_seconds, (int, float))
        or timeout_seconds <= 0
    ):
        raise ClosureProbeError("process timeout must be positive")
    return max_output_bytes, float(timeout_seconds)


def run_bounded_process(
    argv: tuple[str, ...],
    *,
    max_output_bytes: int = MAX_CAPTURE_BYTES,
    timeout_seconds: float = PROCESS_TIMEOUT_SECONDS,
) -> ProcessEvidence:
    """Run one argv-only process while retaining at most limit+1 bytes per stream."""

    maximum, timeout = _validated_process_limits(max_output_bytes, timeout_seconds)
    if (
        not isinstance(argv, tuple)
        or not argv
        or any(not isinstance(value, str) or not value for value in argv)
    ):
        raise ClosureProbeError("process argv must be a nonempty tuple of strings")
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    try:
        process = subprocess.Popen(
            argv,
            shell=False,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            close_fds=True,
            creationflags=creationflags,
        )
    except OSError as error:
        raise ClosureProbeError(f"process could not start: {error}") from error
    stdout_thread: threading.Thread | None = None
    stderr_thread: threading.Thread | None = None
    try:
        if process.stdout is None or process.stderr is None:
            raise ClosureProbeError("process pipes were not created")

        stdout_capture = _LimitedCapture(maximum, bytearray())
        stderr_capture = _LimitedCapture(maximum, bytearray())
        stdout_thread = threading.Thread(
            target=_read_pipe,
            args=(process.stdout, stdout_capture),
            name="closure-probe-stdout",
            daemon=True,
        )
        stderr_thread = threading.Thread(
            target=_read_pipe,
            args=(process.stderr, stderr_capture),
            name="closure-probe-stderr",
            daemon=True,
        )
        stdout_thread.start()
        stderr_thread.start()

        timed_out = False
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            process.kill()
            process.wait()
        stdout_thread.join(timeout=5)
        stderr_thread.join(timeout=5)

        evidence = ProcessEvidence(
            argv=argv,
            returncode=process.returncode if process.returncode is not None else -1,
            stdout=bytes(stdout_capture.content),
            stderr=bytes(stderr_capture.content),
        )
        if stdout_thread.is_alive() or stderr_thread.is_alive():
            raise ClosureProbeError(
                "process output readers did not terminate", process_evidence=evidence
            )
        if stdout_capture.error is not None or stderr_capture.error is not None:
            error = stdout_capture.error or stderr_capture.error
            raise ClosureProbeError(
                f"process output capture failed: {error}", process_evidence=evidence
            )
        if timed_out:
            raise ClosureProbeError(
                f"process timed out after {timeout:g} seconds",
                process_evidence=evidence,
            )
        if stdout_capture.overflowed:
            raise ClosureProbeError(
                f"process stdout exceeded {maximum} bytes",
                process_evidence=evidence,
            )
        if stderr_capture.overflowed:
            raise ClosureProbeError(
                f"process stderr exceeded {maximum} bytes",
                process_evidence=evidence,
            )
        return evidence
    except BaseException:
        try:
            process.kill()
        except BaseException:
            pass
        try:
            process.wait()
        except BaseException:
            pass
        for thread in (stdout_thread, stderr_thread):
            if thread is None:
                continue
            try:
                thread.join(timeout=5)
            except BaseException:
                pass
        raise


def _validate_relation_ids(relation_ids: tuple[int, ...]) -> tuple[int, ...]:
    if not isinstance(relation_ids, tuple) or not relation_ids:
        raise ClosureProbeError("relation IDs must be a nonempty tuple")
    previous = 0
    for relation_id in relation_ids:
        if (
            isinstance(relation_id, bool)
            or not isinstance(relation_id, int)
            or relation_id > _MAX_OSM_OBJECT_ID
            or relation_id <= previous
        ):
            raise ClosureProbeError(
                "relation IDs must be strictly increasing positive integers"
            )
        previous = relation_id
    return relation_ids


def _validate_source_path(source_wsl_path: str) -> str:
    if not isinstance(source_wsl_path, str) or not source_wsl_path:
        raise ClosureProbeError("source WSL path must be nonempty text")
    if any(ord(character) < 32 or ord(character) == 127 for character in source_wsl_path):
        raise ClosureProbeError("source WSL path contains a control character")
    path = PurePosixPath(source_wsl_path)
    if (
        not path.is_absolute()
        or source_wsl_path.startswith("//")
        or source_wsl_path != str(path)
        or ".." in path.parts
        or "\\" in source_wsl_path
        or source_wsl_path == "/"
    ):
        raise ClosureProbeError("source WSL path must be absolute canonical POSIX text")
    return source_wsl_path


def _validate_source_sha256(source_sha256: str) -> str:
    if not isinstance(source_sha256, str) or _LOWER_SHA256.fullmatch(source_sha256) is None:
        raise ClosureProbeError("source hash must be a lowercase SHA-256")
    return source_sha256


def _payload(line: str, label: str) -> str:
    match = _TIMED_LINE.fullmatch(line)
    if match is None:
        raise ClosureProbeError(f"getid transcript has an invalid {label} line")
    return match.group(3)


def _expected_common_transcript(
    source_wsl_path: str, relation_ids: tuple[int, ...]
) -> tuple[str, ...]:
    return (
        "Started osmium getid",
        f"  osmium version {PINNED_OSMIUM_VERSION}",
        f"  libosmium version {PINNED_LIBOSMIUM_VERSION}",
        "Command line options and default settings:",
        "  input options:",
        f"    file name: {source_wsl_path}",
        "    file format: ",
        "  output options:",
        "    file name: /dev/null",
        "    file format: pbf",
        f"    generator: osmium/{PINNED_OSMIUM_VERSION}",
        "    overwrite: yes",
        "    fsync: no",
        "  other options:",
        "    add referenced objects: yes",
        "    remove tags on non-matching objects: no",
        "    work with history files: no",
        "    default object type: node",
        "    looking for these ids:",
        "      nodes:",
        "      ways:",
        "      relations: " + " ".join(str(value) for value in relation_ids),
        "Following references...",
        "  Reading input file to find relations in relations...",
        "  Reading input file to find nodes/ways in relations...",
        "  Reading input file to find nodes in ways...",
        "Done following references.",
        "Opening input file...",
        "Opening output file...",
        "Copying matching objects to output file...",
        "Closing output file...",
        "Closing input file...",
    )


def _parse_missing_ids(line: str) -> tuple[str, tuple[int, ...]]:
    match = _MISSING_IDS.fullmatch(line)
    if match is None:
        label_match = re.match(r"Missing (node|way|relation) IDs:", line)
        label = label_match.group(1) if label_match is not None else "reference"
        raise ClosureProbeError(f"missing {label} ID line is malformed")
    label = match.group(1)
    values = tuple(int(value) for value in match.group(2).split(" "))
    previous = 0
    for value in values:
        if value > _MAX_OSM_OBJECT_ID or value <= previous:
            raise ClosureProbeError(
                f"missing {label} IDs must be strictly increasing positive integers"
            )
        previous = value
    return label, values


def parse_getid_result(
    *,
    returncode: int,
    stdout: bytes,
    stderr: bytes,
    source_wsl_path: str,
    relation_ids: tuple[int, ...],
) -> MissingReferences:
    """Parse the complete pinned-osmium transcript and bind it to the exit code."""

    relations = _validate_relation_ids(relation_ids)
    source = _validate_source_path(source_wsl_path)
    if returncode not in {0, 1}:
        raise ClosureProbeError(f"getid returned unsupported exit code {returncode}")
    if stdout != b"":
        raise ClosureProbeError("getid stdout must be empty when output is /dev/null")
    if not isinstance(stderr, bytes):
        raise ClosureProbeError("getid stderr must be exact bytes")
    try:
        transcript = stderr.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise ClosureProbeError("getid transcript is not valid UTF-8") from error
    if not transcript.endswith("\n") or "\r" in transcript or "\x00" in transcript:
        raise ClosureProbeError("getid transcript is not canonical LF-terminated text")
    lines = transcript[:-1].split("\n")
    common = _expected_common_transcript(source, relations)
    if len(lines) < len(common) + 3:
        raise ClosureProbeError("getid transcript is truncated")
    for index, expected in enumerate(common):
        actual = _payload(lines[index], f"preamble {index + 1}")
        if actual != expected:
            if expected.startswith("      relations:"):
                raise ClosureProbeError(
                    "getid transcript requested relation IDs do not match the probe"
                )
            raise ClosureProbeError(
                f"getid transcript preamble mismatch at line {index + 1}"
            )

    cursor = len(common)
    outcome = _payload(lines[cursor], "outcome")
    cursor += 1
    if outcome == "Found all objects.":
        if returncode != 0:
            raise ClosureProbeError("getid exit 1 transcript says Found all objects")
        if len(lines) != cursor + 2:
            raise ClosureProbeError("getid Found-all transcript has extra content")
        peak = _payload(lines[cursor], "peak-memory")
        done = _payload(lines[cursor + 1], "completion")
        if _PEAK_MEMORY.fullmatch(peak) is None or done != "Done.":
            raise ClosureProbeError("getid Found-all transcript suffix is invalid")
        return MissingReferences(node_ids=(), way_ids=(), relation_ids=())

    summary = _MISSING_SUMMARY.fullmatch(outcome)
    if summary is None:
        raise ClosureProbeError("getid transcript has an ambiguous outcome")
    if returncode != 1:
        raise ClosureProbeError("getid exit 0 transcript reports missing objects")
    expected_count = int(summary.group(1))
    parsed: dict[str, tuple[int, ...]] = {}
    order = {"node": 0, "way": 1, "relation": 2}
    previous_order = -1
    while cursor < len(lines) and lines[cursor].startswith("Missing "):
        label, values = _parse_missing_ids(lines[cursor])
        if label in parsed:
            raise ClosureProbeError(f"duplicate missing {label} IDs section")
        if order[label] <= previous_order:
            raise ClosureProbeError("missing ID sections are out of canonical order")
        parsed[label] = values
        previous_order = order[label]
        cursor += 1
    missing = MissingReferences(
        node_ids=parsed.get("node", ()),
        way_ids=parsed.get("way", ()),
        relation_ids=parsed.get("relation", ()),
    )
    if missing.count != expected_count:
        raise ClosureProbeError(
            "getid missing-reference count does not match the parsed ID count"
        )
    if len(lines) != cursor + 2:
        raise ClosureProbeError("getid missing transcript has extra content")
    peak = _payload(lines[cursor], "peak-memory")
    done = _payload(lines[cursor + 1], "completion")
    if _PEAK_MEMORY.fullmatch(peak) is None or done != "Done.":
        raise ClosureProbeError("getid missing transcript suffix is invalid")
    return missing


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


def _attest_runtime_and_source(
    source_wsl_path: str, source_sha256: str
) -> RuntimeAttestation:
    release_argv = _base_wsl_argv() + ("/usr/bin/lsb_release", "-ds")
    release = run_bounded_process(
        release_argv,
        max_output_bytes=MAX_CAPTURE_BYTES,
        timeout_seconds=PROCESS_TIMEOUT_SECONDS,
    )
    if (
        release.returncode != 0
        or release.stdout != f"{PINNED_UBUNTU_RELEASE}\n".encode("ascii")
        or release.stderr != b""
    ):
        raise ClosureProbeError(
            "pinned Ubuntu release attestation failed", process_evidence=release
        )

    hash_argv = _base_wsl_argv() + (
        "/usr/bin/sha256sum",
        "--binary",
        OSMIUM_BINARY_PATH,
        BOOST_LIBRARY_PATH,
        source_wsl_path,
    )
    hashes = run_bounded_process(
        hash_argv,
        max_output_bytes=MAX_CAPTURE_BYTES,
        timeout_seconds=PROCESS_TIMEOUT_SECONDS,
    )
    expected_hashes = _expected_hash_transcript(source_wsl_path, source_sha256)
    if hashes.returncode != 0 or hashes.stdout != expected_hashes or hashes.stderr != b"":
        raise ClosureProbeError(
            "pinned runtime hash transcript did not match", process_evidence=hashes
        )
    return RuntimeAttestation(
        ubuntu_distribution=PINNED_UBUNTU_DISTRIBUTION,
        ubuntu_release=PINNED_UBUNTU_RELEASE,
        locale=PINNED_LOCALE,
        runtime_root=RUNTIME_ROOT,
        osmium_binary_path=OSMIUM_BINARY_PATH,
        osmium_binary_sha256=OSMIUM_BINARY_SHA256,
        boost_library_path=BOOST_LIBRARY_PATH,
        boost_library_sha256=BOOST_LIBRARY_SHA256,
        release_process=release,
        hash_process=hashes,
    )


def _expected_hash_transcript(source_wsl_path: str, source_sha256: str) -> bytes:
    return (
        f"{OSMIUM_BINARY_SHA256} *{OSMIUM_BINARY_PATH}\n"
        f"{BOOST_LIBRARY_SHA256} *{BOOST_LIBRARY_PATH}\n"
        f"{source_sha256} *{source_wsl_path}\n"
    ).encode("utf-8")


def _require_post_probe_hash_attestation(
    process: ProcessEvidence, source_wsl_path: str, source_sha256: str
) -> None:
    if (
        process.returncode != 0
        or process.stdout != _expected_hash_transcript(source_wsl_path, source_sha256)
        or process.stderr != b""
    ):
        raise ClosureProbeError(
            "post-probe runtime/source hash transcript did not match",
            process_evidence=process,
        )


def probe_relation_root_closures(
    relation_ids: tuple[int, ...],
    *,
    source_wsl_path: str,
    source_sha256: str,
) -> ClosureProbeEvidence:
    """Probe one deterministic relation-root batch with the pinned WSL runtime."""

    relations = _validate_relation_ids(relation_ids)
    source = _validate_source_path(source_wsl_path)
    source_hash = _validate_source_sha256(source_sha256)
    runtime = _attest_runtime_and_source(source, source_hash)
    getid_argv = _base_wsl_argv() + (
        f"LD_LIBRARY_PATH={RUNTIME_LIBRARY_PATH}",
        OSMIUM_BINARY_PATH,
        "getid",
        "--no-progress",
        "-r",
        "--verbose-ids",
        "-f",
        "pbf",
        "-O",
        "-o",
        "/dev/null",
        source,
        *(f"r{relation_id}" for relation_id in relations),
    )
    process = run_bounded_process(
        getid_argv,
        max_output_bytes=MAX_CAPTURE_BYTES,
        timeout_seconds=PROCESS_TIMEOUT_SECONDS,
    )
    post_hash_process = run_bounded_process(
        runtime.hash_process.argv,
        max_output_bytes=MAX_CAPTURE_BYTES,
        timeout_seconds=PROCESS_TIMEOUT_SECONDS,
    )
    _require_post_probe_hash_attestation(
        post_hash_process, source, source_hash
    )
    try:
        missing = parse_getid_result(
            returncode=process.returncode,
            stdout=process.stdout,
            stderr=process.stderr,
            source_wsl_path=source,
            relation_ids=relations,
        )
    except ClosureProbeError as error:
        if error.process_evidence is None:
            error.process_evidence = process
        raise
    return ClosureProbeEvidence(
        relation_ids=relations,
        source_wsl_path=source,
        source_sha256=source_hash,
        missing_references=missing,
        runtime=runtime,
        process=process,
        post_hash_process=post_hash_process,
    )


class PinnedOsmiumClosureProbe:
    """Callable adapter for the Experiment 8 relation-closure audit functions."""

    __slots__ = ("_source_wsl_path", "_source_sha256", "_records")

    def __init__(self, *, source_wsl_path: str, source_sha256: str) -> None:
        self._source_wsl_path = _validate_source_path(source_wsl_path)
        self._source_sha256 = _validate_source_sha256(source_sha256)
        self._records: list[ClosureProbeEvidence] = []

    @property
    def records(self) -> tuple[ClosureProbeEvidence, ...]:
        return tuple(self._records)

    def __call__(self, relation_ids: tuple[int, ...]) -> MissingReferences:
        evidence = probe_relation_root_closures(
            relation_ids,
            source_wsl_path=self._source_wsl_path,
            source_sha256=self._source_sha256,
        )
        self._records.append(evidence)
        return evidence.missing_references


__all__ = [
    "BOOST_LIBRARY_PATH",
    "BOOST_LIBRARY_SHA256",
    "ClosureProbeError",
    "ClosureProbeEvidence",
    "MAX_CAPTURE_BYTES",
    "OSMIUM_BINARY_PATH",
    "OSMIUM_BINARY_SHA256",
    "PINNED_LIBOSMIUM_VERSION",
    "PINNED_LOCALE",
    "PINNED_OSMIUM_VERSION",
    "PINNED_UBUNTU_DISTRIBUTION",
    "PINNED_UBUNTU_RELEASE",
    "PROCESS_TIMEOUT_SECONDS",
    "PinnedOsmiumClosureProbe",
    "ProcessEvidence",
    "RUNTIME_LIBRARY_PATH",
    "RUNTIME_ROOT",
    "RuntimeAttestation",
    "WSL_EXECUTABLE",
    "parse_getid_result",
    "probe_relation_root_closures",
    "run_bounded_process",
]
