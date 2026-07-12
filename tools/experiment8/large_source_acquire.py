"""Resumably acquire one dated, hash-locked whole-world source with evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Sequence
from urllib.parse import urlsplit


MD5_RE = re.compile(r"[0-9a-f]{32}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
UTC_RE = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z\Z")


class LargeSourceAcquireError(RuntimeError):
    """The dated source could not be acquired without weakening evidence."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise LargeSourceAcquireError(message)


@dataclass(frozen=True)
class LargeSourceSpec:
    url: str
    file_name: str
    expected_bytes: int
    expected_md5: str

    def __post_init__(self) -> None:
        parsed = urlsplit(self.url)
        _require(parsed.scheme == "https" and parsed.hostname is not None, "large source URL must be HTTPS")
        _require(
            self.file_name == Path(self.file_name).name and self.file_name not in {"", ".", ".."},
            "large source filename must be one local filename",
        )
        _require(parsed.path.endswith("/" + self.file_name), "large source URL path must end in the exact filename")
        _require("latest" not in self.file_name.lower(), "moving latest aliases are forbidden; use a dated source")
        _require(type(self.expected_bytes) is int and self.expected_bytes > 0, "expected source bytes must be positive")
        _require(MD5_RE.fullmatch(self.expected_md5) is not None, "expected source MD5 must be lowercase hex")


CurlRunner = Callable[[list[str], Path], bytes]
Clock = Callable[[], str]


def _clock() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def _atomic_json(path: Path, value: Any) -> bytes:
    encoded = _canonical_bytes(value)
    temporary = path.with_name(f".{path.name}.tmp-{os.getpid()}")
    _require(not temporary.exists(), f"stale acquisition staging file exists: {temporary}")
    try:
        with temporary.open("xb") as stream:
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        _require(temporary.read_bytes() == encoded, f"staged {path.name} readback differs")
        os.replace(temporary, path)
        _require(path.read_bytes() == encoded, f"installed {path.name} readback differs")
        return encoded
    finally:
        if temporary.exists():
            temporary.unlink()


def _load_unique_json(path: Path) -> dict[str, Any]:
    def unique(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            _require(key not in result, f"{path.name} repeats JSON key {key}")
            result[key] = value
        return result

    try:
        result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise LargeSourceAcquireError(f"cannot read {path.name}: {exc}") from exc
    _require(isinstance(result, dict), f"{path.name} must contain a JSON object")
    return result


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _hash_file(path: Path) -> dict[str, Any]:
    md5 = hashlib.md5()
    sha256 = hashlib.sha256()
    count = 0
    with path.open("rb") as stream:
        while True:
            block = stream.read(8 << 20)
            if not block:
                break
            count += len(block)
            md5.update(block)
            sha256.update(block)
    return {"bytes": count, "md5": md5.hexdigest(), "sha256": sha256.hexdigest()}


def _validate_utc(value: str, label: str) -> str:
    _require(isinstance(value, str) and UTC_RE.fullmatch(value) is not None, f"{label} clock is not canonical UTC")
    return value


def _default_curl_runner(args: list[str], stderr_path: Path) -> bytes:
    with stderr_path.open("ab") as stderr:
        process = subprocess.run(args, stdout=subprocess.PIPE, stderr=stderr, check=False)
    if process.returncode != 0:
        raise LargeSourceAcquireError(f"curl failed with exit code {process.returncode}; see {stderr_path.name}")
    return process.stdout.strip()


def _option_path(args: list[str], name: str) -> Path:
    try:
        return Path(args[args.index(name) + 1])
    except (ValueError, IndexError) as exc:
        raise LargeSourceAcquireError(f"internal curl invocation omitted {name}") from exc


def _parse_header_capture(raw: bytes, *, allowed_statuses: set[int], label: str) -> dict[str, str]:
    blocks = [block for block in raw.replace(b"\r\n", b"\n").split(b"\n\n") if block.startswith(b"HTTP/")]
    _require(blocks, f"{label} contains no HTTP response block")
    lines = blocks[-1].decode("iso-8859-1").split("\n")
    status_tokens = lines[0].split()
    _require(len(status_tokens) >= 2 and status_tokens[1].isdigit(), f"{label} final status line is invalid")
    status = int(status_tokens[1])
    _require(status in allowed_statuses, f"{label} final status {status} is not accepted")
    headers: dict[str, str] = {":status": str(status)}
    for line in lines[1:]:
        if not line:
            continue
        _require(":" in line and not line[0].isspace(), f"{label} contains malformed or folded headers")
        name, value = line.split(":", 1)
        key = name.strip().lower()
        normalized = value.strip()
        _require(key and key not in headers, f"{label} repeats final response header {key}")
        headers[key] = normalized
    return headers


def _effective_url(raw: bytes, label: str) -> str:
    try:
        value = raw.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise LargeSourceAcquireError(f"{label} effective URL is not UTF-8") from exc
    parsed = urlsplit(value)
    _require(parsed.scheme == "https" and parsed.hostname is not None, f"{label} effective URL is not HTTPS")
    return value


def _new_or_existing_state(path: Path, spec: LargeSourceSpec) -> dict[str, Any]:
    if path.exists():
        state = _load_unique_json(path)
        _require(state.get("schemaVersion") == 1, "acquisition state schema is unsupported")
        _require(state.get("sourceUrl") == spec.url, "acquisition state belongs to another URL")
        _require(state.get("fileName") == spec.file_name, "acquisition state belongs to another filename")
        _require(state.get("expectedBytes") == spec.expected_bytes, "acquisition state expected byte count drifted")
        _require(state.get("expectedMd5") == spec.expected_md5, "acquisition state expected MD5 drifted")
        _require(isinstance(state.get("attempts"), list), "acquisition state attempts are invalid")
        return state
    return {
        "schemaVersion": 1,
        "sourceUrl": spec.url,
        "fileName": spec.file_name,
        "expectedBytes": spec.expected_bytes,
        "expectedMd5": spec.expected_md5,
        "status": "preparing",
        "failure": None,
        "attempts": [],
    }


def _acquire_lock(path: Path) -> int:
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError as exc:
        raise LargeSourceAcquireError(f"large source acquisition lock already exists: {path}") from exc
    os.write(descriptor, f"pid={os.getpid()}\n".encode("ascii"))
    os.fsync(descriptor)
    return descriptor


def acquire_large_source(
    output_root: Path,
    spec: LargeSourceSpec,
    *,
    curl_path: str = "curl.exe",
    curl_runner: CurlRunner = _default_curl_runner,
    clock: Clock = _clock,
) -> dict[str, Any]:
    """Acquire, hash, atomically publish, and independently re-read one source."""

    root = Path(output_root)
    root.mkdir(parents=True, exist_ok=True)
    evidence = root / "evidence"
    evidence.mkdir(exist_ok=True)
    final_path = root / spec.file_name
    part_path = root / f"{spec.file_name}.part"
    state_path = root / "acquisition-state.json"
    report_path = root / "acquisition-report.json"
    source_lock_path = root / "source-lock.json"
    lock_path = root / ".acquisition.lock"
    lock_descriptor = _acquire_lock(lock_path)
    state = _new_or_existing_state(state_path, spec)
    try:
        if final_path.exists():
            _require(not part_path.exists(), "published source and resumable part cannot coexist")
            _require(report_path.is_file(), "published source lacks acquisition report")
            installed = _hash_file(final_path)
            _require(installed["bytes"] == spec.expected_bytes, "published source byte count drifted")
            _require(installed["md5"] == spec.expected_md5, "published source MD5 drifted")
            report = _load_unique_json(report_path)
            _require(report.get("installedReadbackSha256") == installed["sha256"], "published source SHA-256 drifted")
            return report

        resume_bytes = part_path.stat().st_size if part_path.exists() else 0
        _require(resume_bytes <= spec.expected_bytes, "resumable part exceeds expected source size")
        attempt_number = len(state["attempts"]) + 1
        head_path = evidence / f"head-attempt-{attempt_number:04d}.txt"
        head_temp = evidence / f".head-attempt-{attempt_number:04d}.tmp"
        get_path = evidence / f"get-attempt-{attempt_number:04d}.txt"
        get_temp = evidence / f".get-attempt-{attempt_number:04d}.tmp"
        progress_path = evidence / f"curl-attempt-{attempt_number:04d}.log"
        for temporary in (head_temp, get_temp):
            _require(not temporary.exists(), f"stale response-header staging file exists: {temporary}")

        head_started = _validate_utc(clock(), "HEAD start")
        head_effective_raw = curl_runner(
            [
                curl_path,
                "--location",
                "--head",
                "--fail",
                "--silent",
                "--show-error",
                "--dump-header",
                str(head_temp),
                "--output",
                "NUL",
                "--write-out",
                "%{url_effective}",
                spec.url,
            ],
            progress_path,
        )
        head_finished = _validate_utc(clock(), "HEAD finish")
        _require(head_temp.is_file(), "curl did not produce the HEAD response capture")
        head_raw = head_temp.read_bytes()
        head_headers = _parse_header_capture(head_raw, allowed_statuses={200}, label="HEAD capture")
        _require(head_headers.get("content-length") == str(spec.expected_bytes), "HEAD Content-Length differs from the locked size")
        _require(head_headers.get("accept-ranges", "").lower() == "bytes", "source does not advertise byte-range resume")
        etag = head_headers.get("etag")
        _require(etag is not None and etag != "", "HEAD response lacks an ETag")
        last_modified = head_headers.get("last-modified")
        _require(last_modified is not None and last_modified != "", "HEAD response lacks Last-Modified")
        head_effective = _effective_url(head_effective_raw, "HEAD")
        _require(urlsplit(head_effective).path.endswith("/" + spec.file_name), "HEAD redirect changed the dated filename")
        os.replace(head_temp, head_path)
        _require(head_path.read_bytes() == head_raw, "persisted HEAD capture readback differs")

        source_lock = {
            "schemaVersion": 1,
            "sourceUrl": spec.url,
            "effectiveUrl": head_effective,
            "fileName": spec.file_name,
            "expectedBytes": spec.expected_bytes,
            "expectedMd5": spec.expected_md5,
            "etag": etag,
            "lastModified": last_modified,
            "headCapture": head_path.name,
            "headCaptureBytes": len(head_raw),
            "headCaptureSha256": _sha256(head_raw),
            "localHeadStartedUtc": head_started,
            "localHeadFinishedUtc": head_finished,
        }
        source_lock_raw = _atomic_json(source_lock_path, source_lock)
        source_lock_sha = _sha256(source_lock_raw)

        download_started = _validate_utc(clock(), "download start")
        attempt = {
            "attempt": attempt_number,
            "resumeBytes": resume_bytes,
            "localHeadStartedUtc": head_started,
            "localHeadFinishedUtc": head_finished,
            "localDownloadStartedUtc": download_started,
            "localDownloadFinishedUtc": None,
            "effectiveUrl": head_effective,
            "headCaptureSha256": _sha256(head_raw),
            "getCaptureSha256": None,
        }
        state["attempts"].append(attempt)
        state["status"] = "downloading"
        state["failure"] = None
        state["sourceLockSha256"] = source_lock_sha
        _atomic_json(state_path, state)

        get_effective_raw = curl_runner(
            [
                curl_path,
                "--location",
                "--fail",
                "--retry",
                "100",
                "--retry-all-errors",
                "--connect-timeout",
                "30",
                "--speed-time",
                "60",
                "--speed-limit",
                "1024",
                "--continue-at",
                "-",
                "--header",
                f"If-Match: {etag}",
                "--dump-header",
                str(get_temp),
                "--output",
                str(part_path),
                "--write-out",
                "%{url_effective}",
                spec.url,
            ],
            progress_path,
        )
        download_finished = _validate_utc(clock(), "download finish")
        _require(get_temp.is_file(), "curl did not produce the GET response capture")
        get_raw = get_temp.read_bytes()
        get_headers = _parse_header_capture(get_raw, allowed_statuses={200, 206}, label="GET capture")
        _require(get_headers.get("etag") in {None, etag}, "GET ETag differs from the HEAD source lock")
        get_effective = _effective_url(get_effective_raw, "GET")
        _require(get_effective == head_effective, "GET effective URL differs from the HEAD source lock")
        os.replace(get_temp, get_path)
        _require(get_path.read_bytes() == get_raw, "persisted GET capture readback differs")
        attempt["localDownloadFinishedUtc"] = download_finished
        attempt["getCaptureSha256"] = _sha256(get_raw)
        state["status"] = "verifying"
        _atomic_json(state_path, state)

        _require(part_path.is_file(), "curl completed without a resumable source file")
        preinstall = _hash_file(part_path)
        _require(preinstall["bytes"] == spec.expected_bytes, "downloaded source byte count differs from the source lock")
        _require(preinstall["md5"] == spec.expected_md5, "downloaded source MD5 differs from the source lock")
        _require(SHA256_RE.fullmatch(preinstall["sha256"]) is not None, "downloaded source SHA-256 is invalid")
        _require(not final_path.exists(), "source destination appeared before atomic publication")
        os.replace(part_path, final_path)
        installed = _hash_file(final_path)
        _require(installed == preinstall, "installed source readback differs from pre-install verification")
        installed_finished = _validate_utc(clock(), "installed readback finish")

        report = {
            "schemaVersion": 1,
            "status": "verified_installed",
            "sourceUrl": spec.url,
            "effectiveUrl": head_effective,
            "fileName": spec.file_name,
            "expectedBytes": spec.expected_bytes,
            "expectedMd5": spec.expected_md5,
            "resumeBytes": resume_bytes,
            "localHeadStartedUtc": head_started,
            "localHeadFinishedUtc": head_finished,
            "localDownloadStartedUtc": download_started,
            "localDownloadFinishedUtc": download_finished,
            "localInstalledReadbackFinishedUtc": installed_finished,
            "headCaptureSha256": _sha256(head_raw),
            "getCaptureSha256": _sha256(get_raw),
            "sourceLockSha256": source_lock_sha,
            "preInstallBytes": preinstall["bytes"],
            "preInstallMd5": preinstall["md5"],
            "preInstallSha256": preinstall["sha256"],
            "installedReadbackBytes": installed["bytes"],
            "installedReadbackMd5": installed["md5"],
            "installedReadbackSha256": installed["sha256"],
        }
        _atomic_json(report_path, report)
        state["status"] = "verified_installed"
        state["failure"] = None
        state["installedReadbackSha256"] = installed["sha256"]
        _atomic_json(state_path, state)
        return report
    except Exception as exc:
        if not isinstance(exc, LargeSourceAcquireError):
            exc = LargeSourceAcquireError(str(exc))
        try:
            state["status"] = "failed"
            state["failure"] = str(exc)
            state["localFailureRecordedUtc"] = _validate_utc(clock(), "failure")
            _atomic_json(state_path, state)
        except Exception as state_exc:
            raise LargeSourceAcquireError(f"{exc}; additionally failed to persist failure state: {state_exc}") from exc
        raise exc
    finally:
        os.close(lock_descriptor)
        try:
            lock_path.unlink()
        except FileNotFoundError:
            pass


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", required=True, type=Path)
    parser.add_argument("--url", required=True)
    parser.add_argument("--file-name", required=True)
    parser.add_argument("--expected-bytes", required=True, type=int)
    parser.add_argument("--expected-md5", required=True)
    parser.add_argument("--curl", default="curl.exe")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = _build_parser().parse_args(argv)
    spec = LargeSourceSpec(args.url, args.file_name, args.expected_bytes, args.expected_md5)
    report = acquire_large_source(args.output_root, spec, curl_path=args.curl)
    print(json.dumps(report, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
