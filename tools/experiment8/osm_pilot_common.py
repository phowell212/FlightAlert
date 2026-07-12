from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Mapping


_LOWER_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_VERIFICATION_SEAL = object()


class ProvenanceVerificationError(ValueError):
    """Live evidence does not match the locked OSM pilot provenance."""


def canonical_json_bytes(document: Mapping[str, object]) -> bytes:
    if not isinstance(document, Mapping):
        raise ValueError("canonical JSON document must be a mapping")
    return (
        json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _verified_instance(cls, /, **values):
    expected = {item.name for item in fields(cls) if item.name != "_seal"}
    if set(values) != expected:
        raise RuntimeError(
            f"internal verified construction mismatch for {cls.__name__}: "
            f"expected {sorted(expected)!r}, got {sorted(values)!r}"
        )
    instance = object.__new__(cls)
    for name, value in values.items():
        object.__setattr__(instance, name, value)
    object.__setattr__(instance, "_seal", _VERIFICATION_SEAL)
    instance.__post_init__()
    return instance


@dataclass(frozen=True, slots=True)
class _FileIdentity:
    device: int
    inode: int
    size: int
    modified_time_ns: int
    changed_time_ns: int


@dataclass(frozen=True, slots=True)
class _VerifiedFile:
    path: Path = field(repr=False, compare=False)
    logical_name: str
    identity: _FileIdentity = field(repr=False)
    bytes: int
    sha256: str
    md5: str | None = None
    content: bytes | None = field(default=None, repr=False)
    _seal: object = field(default=None, init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        if self._seal is not _VERIFICATION_SEAL:
            raise ProvenanceVerificationError(
                "verified files must be created by a live evidence factory"
            )
        if (
            not isinstance(self.path, Path)
            or not self.path.is_absolute()
            or not self.logical_name
            or self.identity.size != self.bytes
            or _LOWER_SHA256.fullmatch(self.sha256) is None
        ):
            raise ProvenanceVerificationError("verified file fields are inconsistent")
        if self.md5 is not None and re.fullmatch(r"[0-9a-f]{32}", self.md5) is None:
            raise ProvenanceVerificationError("verified file MD5 is not canonical")
        if self.content is not None:
            if len(self.content) != self.bytes:
                raise ProvenanceVerificationError(
                    "retained evidence byte count is inconsistent"
                )
            if hashlib.sha256(self.content).hexdigest() != self.sha256:
                raise ProvenanceVerificationError(
                    "retained evidence SHA-256 is inconsistent"
                )
            if self.md5 is not None and hashlib.md5(
                self.content, usedforsecurity=False
            ).hexdigest() != self.md5:
                raise ProvenanceVerificationError(
                    "retained evidence MD5 is inconsistent"
                )


def _identity(raw: os.stat_result) -> _FileIdentity:
    return _FileIdentity(
        device=raw.st_dev,
        inode=raw.st_ino,
        size=raw.st_size,
        modified_time_ns=raw.st_mtime_ns,
        changed_time_ns=raw.st_ctime_ns,
    )


def _verify_stable_file(
    path: str | Path,
    *,
    logical_name: str,
    expected_bytes: int | None = None,
    expected_sha256: str | None = None,
    expected_md5: str | None = None,
    capture_content: bool = False,
    maximum_capture_bytes: int | None = None,
) -> _VerifiedFile:
    try:
        resolved = Path(path).resolve(strict=True)
        stream = resolved.open("rb")
    except OSError as error:
        raise ProvenanceVerificationError(
            f"{logical_name} is unavailable: {error}"
        ) from error
    sha256 = hashlib.sha256()
    md5 = hashlib.md5(usedforsecurity=False) if expected_md5 is not None else None
    captured = bytearray() if capture_content else None
    try:
        before_stat = os.fstat(stream.fileno())
        if not stat.S_ISREG(before_stat.st_mode):
            raise ProvenanceVerificationError(f"{logical_name} is not a regular file")
        before = _identity(before_stat)
        if expected_bytes is not None and before.size != expected_bytes:
            raise ProvenanceVerificationError(
                f"{logical_name} byte count mismatch: expected {expected_bytes}, "
                f"got {before.size}"
            )
        if maximum_capture_bytes is not None and before.size > maximum_capture_bytes:
            raise ProvenanceVerificationError(
                f"{logical_name} exceeds its {maximum_capture_bytes}-byte "
                "evidence ceiling"
            )
        while chunk := stream.read(1024 * 1024):
            sha256.update(chunk)
            if md5 is not None:
                md5.update(chunk)
            if captured is not None:
                captured.extend(chunk)
        after = _identity(os.fstat(stream.fileno()))
    except OSError as error:
        raise ProvenanceVerificationError(
            f"{logical_name} became unreadable: {error}"
        ) from error
    finally:
        stream.close()
    try:
        path_after = _identity(resolved.stat())
    except OSError as error:
        raise ProvenanceVerificationError(
            f"{logical_name} disappeared after verification: {error}"
        ) from error
    if before != after or before != path_after:
        raise ProvenanceVerificationError(
            f"{logical_name} file identity changed during verification"
        )
    actual_sha256 = sha256.hexdigest()
    actual_md5 = md5.hexdigest() if md5 is not None else None
    if expected_sha256 is not None and actual_sha256 != expected_sha256:
        raise ProvenanceVerificationError(
            f"{logical_name} SHA-256 mismatch: expected {expected_sha256}, "
            f"got {actual_sha256}"
        )
    if expected_md5 is not None and actual_md5 != expected_md5:
        raise ProvenanceVerificationError(
            f"{logical_name} MD5 mismatch: expected {expected_md5}, got {actual_md5}"
        )
    return _verified_instance(
        _VerifiedFile,
        path=resolved,
        logical_name=logical_name,
        identity=before,
        bytes=before.size,
        sha256=actual_sha256,
        md5=actual_md5,
        content=bytes(captured) if captured is not None else None,
    )


__all__ = ["ProvenanceVerificationError", "canonical_json_bytes"]
