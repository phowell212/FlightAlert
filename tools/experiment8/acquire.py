from __future__ import annotations

import concurrent.futures
import datetime as dt
import email.utils
import gzip
import hashlib
import io
import json
import os
import re
import socket
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Literal, Mapping, Sequence

from .model import TileKey


_HEX_SHA256 = re.compile(r"[0-9a-fA-F]{64}\Z")
_USER_AGENT = "FlightAlert-Experiment8/1.0"


class AcquisitionError(ValueError):
    """A source, cache, transport, or PBF invariant was violated."""


@dataclass(frozen=True, slots=True)
class EntryPaths:
    relative_cache_key: str
    wire_path: Path
    pbf_path: Path
    sidecar_path: Path


@dataclass(frozen=True, slots=True)
class AcquisitionResult:
    tile: TileKey
    status: Literal["ready", "failed"]
    response_sha256: str | None
    pbf_sha256: str | None
    response_bytes: int
    pbf_bytes: int
    attempts: int
    error: str | None
    cache_hit: bool = False
    layer_count: int = 0
    feature_count: int = 0


@dataclass(frozen=True, slots=True)
class AcquisitionSummary:
    input_row_count: int
    row_count: int
    ready_count: int
    failed_count: int
    skipped_known_empty_count: int
    acquisition_sha256: str


@dataclass(frozen=True, slots=True)
class _SourceContext:
    verified_lock_sha256: str
    source_lock_sha256: str
    service_url: str
    tile_url_template: str
    metadata_sha256: str
    style_sha256: str
    service_item_id: str
    current_version: str
    tile_compression: str
    tile_format: str
    tile_rows: int
    tile_cols: int
    min_lod: int
    max_lod: int
    generation_id: str


def _sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def _read_json(path: Path, label: str) -> tuple[Path, dict[str, object], bytes]:
    try:
        resolved = path.resolve(strict=True)
        raw = resolved.read_bytes()
    except OSError as error:
        raise AcquisitionError(f"{label} is unavailable: {path}: {error}") from error
    try:
        document = json.loads(raw.decode("utf-8-sig"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AcquisitionError(f"{label} is invalid JSON: {resolved}: {error}") from error
    if not isinstance(document, dict):
        raise AcquisitionError(f"{label} root must be a JSON object")
    return resolved, document, raw


def _text(document: Mapping[str, object], key: str, label: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or not value:
        raise AcquisitionError(f"{label} field {key!r} is missing or empty")
    return value


def _sha_field(document: Mapping[str, object], key: str, label: str) -> str:
    value = _text(document, key, label).lower()
    if _HEX_SHA256.fullmatch(value) is None:
        raise AcquisitionError(f"{label} field {key!r} is not a SHA-256 value")
    return value


def _integer(document: Mapping[str, object], key: str, label: str) -> int:
    value = document.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise AcquisitionError(f"{label} field {key!r} is not a nonnegative integer")
    return value


def _object(document: Mapping[str, object], key: str, label: str) -> dict[str, object]:
    value = document.get(key)
    if not isinstance(value, dict):
        raise AcquisitionError(f"{label} field {key!r} must be an object")
    return value


def _verify_file(path_text: str, expected_sha256: str, label: str) -> tuple[Path, bytes]:
    path = Path(path_text)
    try:
        resolved = path.resolve(strict=True)
        raw = resolved.read_bytes()
    except OSError as error:
        raise AcquisitionError(f"{label} is unavailable: {path}: {error}") from error
    actual = hashlib.sha256(raw).hexdigest()
    if actual != expected_sha256:
        raise AcquisitionError(
            f"{label} SHA-256 mismatch: expected {expected_sha256}, got {actual}"
        )
    return resolved, raw


def _source_context(
    verified_source_lock_path: Path,
    expected_verified_source_lock_sha256: str,
) -> _SourceContext:
    if _HEX_SHA256.fullmatch(expected_verified_source_lock_sha256) is None:
        raise AcquisitionError("expected verified source-lock SHA-256 is invalid")
    _, verified, verified_raw = _read_json(verified_source_lock_path, "verified source lock")
    verified_sha = hashlib.sha256(verified_raw).hexdigest()
    if verified_sha != expected_verified_source_lock_sha256.lower():
        raise AcquisitionError(
            "verified source-lock SHA-256 mismatch: "
            f"expected {expected_verified_source_lock_sha256.lower()}, got {verified_sha}"
        )
    if verified.get("schemaVersion") != 1:
        raise AcquisitionError("verified source-lock schemaVersion must equal 1")

    source_lock_sha = _sha_field(verified, "sourceLockSha256", "verified source lock")
    _, source_lock_raw = _verify_file(
        _text(verified, "sourceLockPath", "verified source lock"),
        source_lock_sha,
        "source-lock descriptor",
    )
    try:
        descriptor = json.loads(source_lock_raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AcquisitionError(f"source-lock descriptor is invalid JSON: {error}") from error
    if not isinstance(descriptor, dict) or descriptor.get("schemaVersion") != 1:
        raise AcquisitionError("source-lock descriptor schemaVersion must equal 1")

    metadata_sha = _sha_field(verified, "metadataSha256", "verified source lock")
    style_sha = _sha_field(verified, "styleSha256", "verified source lock")
    _, metadata_raw = _verify_file(
        _text(verified, "metadataPath", "verified source lock"), metadata_sha, "source metadata"
    )
    _verify_file(_text(verified, "stylePath", "verified source lock"), style_sha, "source style")
    if _sha_field(descriptor, "metadataSha256", "source-lock descriptor") != metadata_sha:
        raise AcquisitionError("descriptor metadata identity mismatch")
    if _sha_field(descriptor, "styleSha256", "source-lock descriptor") != style_sha:
        raise AcquisitionError("descriptor style identity mismatch")

    service_url = _text(descriptor, "serviceUrl", "source-lock descriptor")
    tile_url_template = _text(descriptor, "tileUrlTemplate", "source-lock descriptor")
    service_parts = urllib.parse.urlsplit(service_url)
    if service_parts.scheme != "https" or not service_parts.hostname or service_parts.username:
        raise AcquisitionError("locked service URL must be credential-free HTTPS")
    try:
        metadata = json.loads(metadata_raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AcquisitionError(f"source metadata is invalid JSON: {error}") from error
    if not isinstance(metadata, dict):
        raise AcquisitionError("source metadata root must be an object")
    tile_info = _object(metadata, "tileInfo", "source metadata")
    resource_info = _object(metadata, "resourceInfo", "source metadata")
    tile_compression = _text(resource_info, "tileCompression", "source metadata").lower()
    tile_format = _text(tile_info, "format", "source metadata").lower()
    if tile_compression != "gzip" or tile_format != "pbf":
        raise AcquisitionError(
            f"unsupported locked tile encoding: compression={tile_compression}, format={tile_format}"
        )
    generation = {
        "coordinateConvention": "xyz-cache/url-z-y-x",
        "currentVersion": str(metadata.get("currentVersion")),
        "maxLOD": _integer(metadata, "maxLOD", "source metadata"),
        "metadataSha256": metadata_sha,
        "minLOD": _integer(metadata, "minLOD", "source metadata"),
        "serviceItemId": _text(metadata, "serviceItemId", "source metadata"),
        "serviceUrl": service_url,
        "styleSha256": style_sha,
        "tileCols": _integer(tile_info, "cols", "source metadata"),
        "tileCompression": tile_compression,
        "tileFormat": tile_format,
        "tileRows": _integer(tile_info, "rows", "source metadata"),
        "tileUrlTemplate": tile_url_template,
    }
    generation_id = hashlib.sha256(
        json.dumps(generation, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    return _SourceContext(
        verified_lock_sha256=verified_sha,
        source_lock_sha256=source_lock_sha,
        service_url=service_url,
        tile_url_template=tile_url_template,
        metadata_sha256=metadata_sha,
        style_sha256=style_sha,
        service_item_id=str(generation["serviceItemId"]),
        current_version=str(generation["currentVersion"]),
        tile_compression=tile_compression,
        tile_format=tile_format,
        tile_rows=int(generation["tileRows"]),
        tile_cols=int(generation["tileCols"]),
        min_lod=int(generation["minLOD"]),
        max_lod=int(generation["maxLOD"]),
        generation_id=generation_id,
    )


class PbfCache:
    def __init__(
        self,
        *,
        root: Path,
        verified_source_lock_path: Path,
        expected_verified_source_lock_sha256: str,
        request_url_template: str | None = None,
        allow_loopback_http: bool = False,
        timeout_seconds: float = 30.0,
        max_attempts: int = 3,
        max_wire_bytes: int = 16 * 1024 * 1024,
        max_pbf_bytes: int = 64 * 1024 * 1024,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        if timeout_seconds <= 0 or not 1 <= max_attempts <= 10:
            raise AcquisitionError("timeout must be positive and max_attempts between 1 and 10")
        if max_wire_bytes <= 0 or max_pbf_bytes <= 0:
            raise AcquisitionError("acquisition byte ceilings must be positive")
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.source = _source_context(
            Path(verified_source_lock_path), expected_verified_source_lock_sha256
        )
        self.source_generation_id = self.source.generation_id
        self.request_url_template = request_url_template or self.source.tile_url_template
        self.allow_loopback_http = allow_loopback_http
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max_attempts
        self.max_wire_bytes = max_wire_bytes
        self.max_pbf_bytes = max_pbf_bytes
        self.sleeper = sleeper
        self._validate_template()
        self._tile_locks: dict[int, threading.Lock] = {}
        self._tile_locks_guard = threading.Lock()

    def _validate_template(self) -> None:
        try:
            example = self.request_url_template.format(z=0, y=0, x=0)
        except (KeyError, ValueError) as error:
            raise AcquisitionError(f"invalid tile URL template: {error}") from error
        parts = urllib.parse.urlsplit(example)
        loopback = parts.hostname in ("127.0.0.1", "localhost", "::1")
        if parts.scheme == "http" and not (self.allow_loopback_http and loopback):
            raise AcquisitionError("tile URL must use HTTPS; HTTP is permitted only for loopback tests")
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username:
            raise AcquisitionError("tile URL must be an absolute credential-free HTTP(S) URL")

    def _url(self, tile: TileKey) -> str:
        return self.request_url_template.format(z=tile.z, y=tile.y, x=tile.x)

    def _validate_final_url(self, requested: str, final: str) -> None:
        requested_parts = urllib.parse.urlsplit(requested)
        final_parts = urllib.parse.urlsplit(final)
        if (
            final_parts.scheme != requested_parts.scheme
            or final_parts.netloc.lower() != requested_parts.netloc.lower()
        ):
            raise AcquisitionError(f"redirect left locked source origin: {final}")

    def entry_paths(self, tile: TileKey) -> EntryPaths:
        relative = f"tiles/{tile.z}/{tile.x}/{tile.y}"
        base = self.root / "generations" / self.source_generation_id / "tiles" / str(tile.z) / str(tile.x)
        return EntryPaths(
            relative_cache_key=relative,
            wire_path=base / f"{tile.y}.wire.gz",
            pbf_path=base / f"{tile.y}.pbf",
            sidecar_path=base / f"{tile.y}.json",
        )

    def _lock_for(self, tile: TileKey) -> threading.Lock:
        with self._tile_locks_guard:
            return self._tile_locks.setdefault(tile.packed, threading.Lock())

    def _quarantine(self, tile: TileKey, paths: EntryPaths) -> None:
        existing = [path for path in (paths.wire_path, paths.pbf_path, paths.sidecar_path) if path.exists()]
        if not existing:
            return
        destination = (
            self.root
            / "quarantine"
            / self.source_generation_id
            / str(tile.z)
            / str(tile.x)
            / str(tile.y)
            / str(time.time_ns())
        )
        destination.mkdir(parents=True, exist_ok=False)
        for path in existing:
            os.replace(path, destination / path.name)

    def _cached(self, tile: TileKey, paths: EntryPaths) -> AcquisitionResult | None:
        existing = [path.exists() for path in (paths.wire_path, paths.pbf_path, paths.sidecar_path)]
        if not any(existing):
            return None
        if not all(existing):
            self._quarantine(tile, paths)
            return None
        try:
            sidecar = json.loads(paths.sidecar_path.read_text(encoding="utf-8"))
            if not isinstance(sidecar, dict) or sidecar.get("schemaVersion") != 1:
                raise AcquisitionError("cache sidecar schema mismatch")
            if sidecar.get("sourceGenerationId") != self.source_generation_id:
                raise AcquisitionError("cache source generation mismatch")
            if sidecar.get("tile") != {"z": tile.z, "x": tile.x, "y": tile.y}:
                raise AcquisitionError("cache coordinate mismatch")
            if sidecar.get("relativeCacheKey") != paths.relative_cache_key:
                raise AcquisitionError("cache key mismatch")
            requested_url = self._url(tile)
            if sidecar.get("requestedUrl") != requested_url:
                raise AcquisitionError("cache requested URL mismatch")
            final_url = sidecar.get("finalUrl")
            if not isinstance(final_url, str):
                raise AcquisitionError("cache final URL is missing")
            self._validate_final_url(requested_url, final_url)
            wire = _object(sidecar, "wire", "cache sidecar")
            decoded = _object(sidecar, "decoded", "cache sidecar")
            response = _object(sidecar, "response", "cache sidecar")
            if (
                response.get("status") != 200
                or not str(response.get("contentType", "")).lower().startswith(
                    "application/octet-stream"
                )
                or str(response.get("contentEncoding", "")).lower() != "gzip"
            ):
                raise AcquisitionError("cache response validation marker mismatch")
            if wire.get("codec") != "gzip" or wire.get("integrity") != "passed":
                raise AcquisitionError("cache wire validation marker mismatch")
            if (
                decoded.get("codec") != "mvt-pbf"
                or decoded.get("decode") != "passed"
                or decoded.get("yCoordDown") is not True
            ):
                raise AcquisitionError("cache decoded validation marker mismatch")
            wire_bytes = _integer(wire, "bytes", "cache sidecar wire")
            pbf_bytes = _integer(decoded, "bytes", "cache sidecar decoded")
            wire_sha = _sha_field(wire, "sha256", "cache sidecar wire")
            pbf_sha = _sha_field(decoded, "sha256", "cache sidecar decoded")
            if paths.wire_path.stat().st_size != wire_bytes or _sha256_file(paths.wire_path) != wire_sha:
                raise AcquisitionError("cache wire hash or length mismatch")
            if paths.pbf_path.stat().st_size != pbf_bytes or _sha256_file(paths.pbf_path) != pbf_sha:
                raise AcquisitionError("cache decoded hash or length mismatch")
            return AcquisitionResult(
                tile=tile,
                status="ready",
                response_sha256=wire_sha,
                pbf_sha256=pbf_sha,
                response_bytes=wire_bytes,
                pbf_bytes=pbf_bytes,
                attempts=0,
                error=None,
                cache_hit=True,
                layer_count=_integer(decoded, "layerCount", "cache sidecar decoded"),
                feature_count=_integer(decoded, "featureCount", "cache sidecar decoded"),
            )
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, AcquisitionError, TypeError):
            self._quarantine(tile, paths)
            return None

    def _decode(self, wire: bytes, content_encoding: str) -> tuple[bytes, int, int]:
        if content_encoding.strip().lower() != "gzip":
            raise AcquisitionError(
                f"gzip content encoding mismatch: expected 'gzip', got {content_encoding!r}"
            )
        if not wire.startswith(b"\x1f\x8b"):
            raise AcquisitionError(
                f"gzip encoding mismatch: header={content_encoding!r}, magic={wire[:2].hex()}"
            )
        try:
            with gzip.GzipFile(fileobj=io.BytesIO(wire), mode="rb") as compressed:
                pbf = compressed.read(self.max_pbf_bytes + 1)
        except (OSError, EOFError) as error:
            raise AcquisitionError(f"gzip integrity failure: {error}") from error
        if len(pbf) > self.max_pbf_bytes:
            raise AcquisitionError(f"decoded PBF exceeds {self.max_pbf_bytes} bytes")
        try:
            from mapbox_vector_tile import decode

            decoded = decode(pbf, default_options={"y_coord_down": True})
        except Exception as error:
            raise AcquisitionError(f"PBF decode failure: {error}") from error
        if not isinstance(decoded, dict):
            raise AcquisitionError("PBF decode did not return layers")
        layer_count = len(decoded)
        feature_count = 0
        for layer in decoded.values():
            if not isinstance(layer, dict) or not isinstance(layer.get("features"), list):
                raise AcquisitionError("PBF layer has invalid feature collection")
            feature_count += len(layer["features"])
        return pbf, layer_count, feature_count

    def _temporary_bytes(self, destination: Path, data: bytes) -> Path:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{destination.name}.",
            suffix=".tmp",
            dir=destination.parent,
            delete=False,
        ) as temporary:
            path = Path(temporary.name)
            temporary.write(data)
            temporary.flush()
            os.fsync(temporary.fileno())
        return path

    def _store(self, paths: EntryPaths, wire: bytes, pbf: bytes, sidecar: dict[str, object]) -> None:
        wire_temp: Path | None = None
        pbf_temp: Path | None = None
        sidecar_temp: Path | None = None
        installed: list[Path] = []
        try:
            wire_temp = self._temporary_bytes(paths.wire_path, wire)
            pbf_temp = self._temporary_bytes(paths.pbf_path, pbf)
            sidecar_bytes = (json.dumps(sidecar, indent=2, sort_keys=True) + "\n").encode("utf-8")
            sidecar_temp = self._temporary_bytes(paths.sidecar_path, sidecar_bytes)
            for temporary_path, destination in (
                (wire_temp, paths.wire_path),
                (pbf_temp, paths.pbf_path),
                (sidecar_temp, paths.sidecar_path),
            ):
                os.replace(temporary_path, destination)
                installed.append(destination)
            wire_temp = pbf_temp = sidecar_temp = None
        except BaseException:
            for path in installed:
                path.unlink(missing_ok=True)
            raise
        finally:
            for path in (wire_temp, pbf_temp, sidecar_temp):
                if path is not None:
                    path.unlink(missing_ok=True)

    def _retry_delay(self, attempt: int, retry_after: str | None) -> float:
        if retry_after:
            try:
                return max(0.0, min(float(retry_after), 60.0))
            except ValueError:
                try:
                    retry_at = email.utils.parsedate_to_datetime(retry_after)
                    if retry_at.tzinfo is None:
                        retry_at = retry_at.replace(tzinfo=dt.timezone.utc)
                    seconds = (retry_at - dt.datetime.now(dt.timezone.utc)).total_seconds()
                    return max(0.0, min(seconds, 60.0))
                except (TypeError, ValueError, OverflowError):
                    pass
        return min(float(2 ** (attempt - 1)), 8.0)

    def acquire(self, tile: TileKey) -> AcquisitionResult:
        with self._lock_for(tile):
            paths = self.entry_paths(tile)
            cached = self._cached(tile, paths)
            if cached is not None:
                return cached
            requested_url = self._url(tile)
            last_error = "acquisition did not start"
            for attempt in range(1, self.max_attempts + 1):
                try:
                    request = urllib.request.Request(
                        requested_url,
                        headers={"User-Agent": _USER_AGENT, "Accept-Encoding": "identity"},
                        method="GET",
                    )
                    with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                        final_url = response.geturl()
                        self._validate_final_url(requested_url, final_url)
                        status = int(response.status)
                        content_type = response.headers.get("Content-Type", "")
                        content_encoding = response.headers.get("Content-Encoding", "")
                        wire = response.read(self.max_wire_bytes + 1)
                        headers = {name.lower(): value for name, value in response.headers.items()}
                    if status != 200:
                        raise AcquisitionError(f"HTTP {status}")
                    if len(wire) > self.max_wire_bytes:
                        raise AcquisitionError(f"wire response exceeds {self.max_wire_bytes} bytes")
                    if not content_type.lower().startswith("application/octet-stream"):
                        raise AcquisitionError(f"unexpected content type: {content_type!r}")
                    pbf, layer_count, feature_count = self._decode(wire, content_encoding)
                    wire_sha = hashlib.sha256(wire).hexdigest()
                    pbf_sha = hashlib.sha256(pbf).hexdigest()
                    acquired_at = dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z")
                    sidecar: dict[str, object] = {
                        "schemaVersion": 1,
                        "sourceGenerationId": self.source_generation_id,
                        "verifiedSourceLockSha256": self.source.verified_lock_sha256,
                        "sourceLockSha256": self.source.source_lock_sha256,
                        "metadataSha256": self.source.metadata_sha256,
                        "styleSha256": self.source.style_sha256,
                        "serviceItemId": self.source.service_item_id,
                        "currentVersion": self.source.current_version,
                        "tile": {"z": tile.z, "x": tile.x, "y": tile.y},
                        "relativeCacheKey": paths.relative_cache_key,
                        "requestedUrl": requested_url,
                        "finalUrl": final_url,
                        "request": {
                            "method": "GET",
                            "userAgent": _USER_AGENT,
                            "acceptEncoding": "identity",
                        },
                        "response": {
                            "status": status,
                            "contentType": content_type,
                            "contentEncoding": content_encoding,
                            "contentLength": headers.get("content-length"),
                            "etag": headers.get("etag"),
                            "lastModified": headers.get("last-modified"),
                            "cacheControl": headers.get("cache-control"),
                        },
                        "wire": {
                            "codec": "gzip",
                            "bytes": len(wire),
                            "sha256": wire_sha,
                            "integrity": "passed",
                        },
                        "decoded": {
                            "codec": "mvt-pbf",
                            "bytes": len(pbf),
                            "sha256": pbf_sha,
                            "layerCount": layer_count,
                            "featureCount": feature_count,
                            "decode": "passed",
                            "yCoordDown": True,
                        },
                        "attempts": attempt,
                        "acquiredAtUtc": acquired_at,
                    }
                    self._store(paths, wire, pbf, sidecar)
                    return AcquisitionResult(
                        tile=tile,
                        status="ready",
                        response_sha256=wire_sha,
                        pbf_sha256=pbf_sha,
                        response_bytes=len(wire),
                        pbf_bytes=len(pbf),
                        attempts=attempt,
                        error=None,
                        cache_hit=False,
                        layer_count=layer_count,
                        feature_count=feature_count,
                    )
                except urllib.error.HTTPError as error:
                    last_error = f"HTTP {error.code}: {error.reason}"
                    retryable = error.code == 429 or 500 <= error.code <= 599
                    if retryable and attempt < self.max_attempts:
                        self.sleeper(self._retry_delay(attempt, error.headers.get("Retry-After")))
                        continue
                    break
                except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
                    last_error = f"transport failure: {error}"
                    if attempt < self.max_attempts:
                        self.sleeper(self._retry_delay(attempt, None))
                        continue
                    break
                except (OSError, AcquisitionError, ValueError) as error:
                    last_error = str(error)
                    break
            return AcquisitionResult(
                tile=tile,
                status="failed",
                response_sha256=None,
                pbf_sha256=None,
                response_bytes=0,
                pbf_bytes=0,
                attempts=attempt,
                error=last_error,
            )


def _sample_tiles(path: Path, expected_sha256: str) -> tuple[list[TileKey], int, int]:
    if _HEX_SHA256.fullmatch(expected_sha256) is None:
        raise AcquisitionError("expected sample SHA-256 is invalid")
    try:
        resolved = path.resolve(strict=True)
        raw = resolved.read_bytes()
    except OSError as error:
        raise AcquisitionError(f"sample is unavailable: {path}: {error}") from error
    actual = hashlib.sha256(raw).hexdigest()
    if actual != expected_sha256.lower():
        raise AcquisitionError(
            f"sample SHA-256 mismatch: expected {expected_sha256.lower()}, got {actual}"
        )
    tiles: dict[int, TileKey] = {}
    input_row_count = 0
    skipped_known_empty_count = 0
    for line_number, raw_line in enumerate(raw.splitlines(), start=1):
        input_row_count += 1
        try:
            document = json.loads(raw_line)
        except json.JSONDecodeError as error:
            raise AcquisitionError(f"sample line {line_number} is invalid JSON: {error}") from error
        if not isinstance(document, dict):
            raise AcquisitionError(f"sample line {line_number} must be an object")
        try:
            coordinates = tuple(document[name] for name in ("z", "x", "y"))
            if any(isinstance(value, bool) or not isinstance(value, int) for value in coordinates):
                raise TypeError("coordinates must be JSON integers")
            tile = TileKey(*coordinates)
        except (KeyError, TypeError, ValueError) as error:
            raise AcquisitionError(f"sample line {line_number} has invalid coordinates") from error
        source_state = document.get("sourceState")
        if source_state == "known_empty":
            skipped_known_empty_count += 1
            continue
        if source_state not in (None, "present"):
            raise AcquisitionError(
                f"sample line {line_number} has unsupported sourceState {source_state!r}"
            )
        if tile.packed in tiles:
            raise AcquisitionError(f"duplicate sample tile {tile.z}/{tile.x}/{tile.y}")
        tiles[tile.packed] = tile
    return (
        [tiles[packed] for packed in sorted(tiles)],
        input_row_count,
        skipped_known_empty_count,
    )


def _atomic_text(path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    return tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        newline="\n",
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
        delete=False,
    )


def _reserve_backup_path(destination: Path) -> Path:
    with tempfile.NamedTemporaryFile(
        mode="wb",
        prefix=f".{destination.name}.",
        suffix=".bak",
        dir=destination.parent,
        delete=False,
    ) as reserved:
        backup = Path(reserved.name)
    backup.unlink()
    return backup


def _commit_output_set(replacements: Sequence[tuple[Path, Path]]) -> None:
    backups: list[tuple[Path, Path]] = []
    reserved: list[Path] = []
    installed: list[Path] = []
    try:
        for _, destination in replacements:
            if not destination.exists():
                continue
            backup = _reserve_backup_path(destination)
            reserved.append(backup)
            os.replace(destination, backup)
            backups.append((destination, backup))
        for source, destination in replacements:
            os.replace(source, destination)
            installed.append(destination)
    except BaseException as error:
        rollback_errors: list[str] = []
        for destination in reversed(installed):
            try:
                destination.unlink(missing_ok=True)
            except OSError as rollback_error:
                rollback_errors.append(f"remove {destination}: {rollback_error}")
        for destination, backup in reversed(backups):
            try:
                os.replace(backup, destination)
            except OSError as rollback_error:
                rollback_errors.append(f"restore {destination}: {rollback_error}")
        for backup in reserved:
            try:
                backup.unlink(missing_ok=True)
            except OSError as rollback_error:
                rollback_errors.append(f"clean {backup}: {rollback_error}")
        if rollback_errors:
            raise AcquisitionError(
                "acquisition output transaction and rollback failed: "
                + "; ".join(rollback_errors)
            ) from error
        raise
    else:
        for _, backup in backups:
            backup.unlink(missing_ok=True)


def acquire_manifest(
    sample_path: Path,
    expected_sample_sha256: str,
    cache: PbfCache,
    workers: int,
    output_dir: Path,
) -> AcquisitionSummary:
    if not 1 <= workers <= 16:
        raise AcquisitionError(f"workers must be between 1 and 16: {workers}")
    tiles, input_row_count, skipped_known_empty_count = _sample_tiles(
        Path(sample_path), expected_sample_sha256
    )
    results: dict[int, AcquisitionResult] = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = {executor.submit(cache.acquire, tile): tile for tile in tiles}
        for future in concurrent.futures.as_completed(futures):
            tile = futures[future]
            try:
                result = future.result()
            except BaseException as error:
                result = AcquisitionResult(
                    tile=tile,
                    status="failed",
                    response_sha256=None,
                    pbf_sha256=None,
                    response_bytes=0,
                    pbf_bytes=0,
                    attempts=0,
                    error=f"worker failure: {error}",
                )
            results[tile.packed] = result

    output = Path(output_dir)
    output.mkdir(parents=True, exist_ok=True)
    inventory_path = output / "acquisition.jsonl"
    summary_path = output / "acquisition-summary.json"
    audit_path = output / "acquisition-audit.jsonl"
    inventory_temp: Path | None = None
    summary_temp: Path | None = None
    audit_temp: Path | None = None
    try:
        with _atomic_text(inventory_path) as inventory:
            inventory_temp = Path(inventory.name)
            for packed in sorted(results):
                result = results[packed]
                document: dict[str, object] = {
                    "relativeCacheKey": cache.entry_paths(result.tile).relative_cache_key,
                    "sourceGenerationId": cache.source_generation_id,
                    "status": result.status,
                    "x": result.tile.x,
                    "y": result.tile.y,
                    "z": result.tile.z,
                }
                if result.status == "ready":
                    document.update(
                        {
                            "decodedBytes": result.pbf_bytes,
                            "decodedSha256": result.pbf_sha256,
                            "featureCount": result.feature_count,
                            "layerCount": result.layer_count,
                            "sourceBytes": result.response_bytes,
                            "sourceSha256": result.response_sha256,
                        }
                    )
                else:
                    document["error"] = "acquisition_failed"
                inventory.write(json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n")
            inventory.flush()
            os.fsync(inventory.fileno())
        inventory_sha = _sha256_file(inventory_temp)
        inventory_bytes = inventory_temp.stat().st_size
        ready_count = sum(result.status == "ready" for result in results.values())
        failed_count = len(results) - ready_count
        summary_document = {
            "schemaVersion": 1,
            "sampleSha256": expected_sample_sha256.lower(),
            "sourceGenerationId": cache.source_generation_id,
            "inputRowCount": input_row_count,
            "rowCount": len(results),
            "readyCount": ready_count,
            "failedCount": failed_count,
            "skippedKnownEmptyCount": skipped_known_empty_count,
            "acquisitionBytes": inventory_bytes,
            "acquisitionSha256": inventory_sha,
        }
        with _atomic_text(summary_path) as summary:
            summary_temp = Path(summary.name)
            json.dump(summary_document, summary, indent=2, sort_keys=True)
            summary.write("\n")
            summary.flush()
            os.fsync(summary.fileno())
        with _atomic_text(audit_path) as audit:
            audit_temp = Path(audit.name)
            for packed in sorted(results):
                result = results[packed]
                audit.write(
                    json.dumps(
                        {
                            "attempts": result.attempts,
                            "cacheHit": result.cache_hit,
                            "error": result.error,
                            "status": result.status,
                            "x": result.tile.x,
                            "y": result.tile.y,
                            "z": result.tile.z,
                        },
                        sort_keys=True,
                        separators=(",", ":"),
                    )
                    + "\n"
                )
            audit.flush()
            os.fsync(audit.fileno())
        _commit_output_set(
            (
                (inventory_temp, inventory_path),
                (audit_temp, audit_path),
                (summary_temp, summary_path),
            )
        )
        inventory_temp = summary_temp = audit_temp = None
    finally:
        for path in (inventory_temp, summary_temp, audit_temp):
            if path is not None:
                path.unlink(missing_ok=True)
    return AcquisitionSummary(
        input_row_count=input_row_count,
        row_count=len(results),
        ready_count=ready_count,
        failed_count=failed_count,
        skipped_known_empty_count=skipped_known_empty_count,
        acquisition_sha256=inventory_sha,
    )
