"""Prepare and fetch verified Experiment 8 reference release assets."""

from __future__ import annotations

import argparse
import ctypes
import errno
import hashlib
import http.client
import os
import re
import shutil
import stat
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath
from typing import Mapping, Sequence

from tools.experiment8 import reference_package_install as installer
from tools.experiment8.reference_package_install import (
    HostInstallPlan,
    ReferencePackageInstallError,
)


RELEASE_MANIFEST_NAME = (
    "world-experiment8-binary-v4.release-manifest.json"
)
RELEASE_MANIFEST_SCHEMA = (
    "flightalert.experiment8.reference-release-manifest.v1"
)
MAX_RELEASE_ASSET_BYTES = 2_000_000_000
DEFAULT_CHUNK_BYTES = 1_900_000_000
_FULL_COMMIT = re.compile(r"[0-9a-f]{40}\Z")
_DOWNLOAD_BUFFER_BYTES = 4 * 1024 * 1024
_DOWNLOAD_TIMEOUT_SECONDS = 60


def _same_install_file(
    left: installer.InstallFile, right: installer.InstallFile
) -> bool:
    return (
        left.path == right.path
        and left.byte_length == right.byte_length
        and left.sha256 == right.sha256
        and left.stat_identity == right.stat_identity
    )


def _fresh_directory_target(path: Path, label: str) -> Path:
    requested = Path(path)
    if not requested.name or requested.name in {".", ".."}:
        raise ReferencePackageInstallError(f"{label} has no usable name")
    parent = installer._real_directory(requested.parent, f"{label} parent")
    target = parent / requested.name
    if os.path.normcase(str(requested.resolve(strict=False))) != os.path.normcase(
        str(target)
    ):
        raise ReferencePackageInstallError(
            f"{label} does not resolve directly in its parent"
        )
    if os.path.lexists(target):
        raise ReferencePackageInstallError(f"{label} already exists")
    return target


def _create_stage(target: Path, operation: str) -> tuple[Path, tuple[int, int]]:
    stage = target.parent / (
        f".{target.name}.exp8-{operation}-{uuid.uuid4().hex}.stage"
    )
    try:
        stage.mkdir()
    except OSError as error:
        raise ReferencePackageInstallError(
            f"{operation} stage cannot be created"
        ) from error
    try:
        information = stage.stat(follow_symlinks=False)
    except OSError as error:
        try:
            recovery_information = stage.stat(follow_symlinks=False)
            recovery_identity = (
                recovery_information.st_dev,
                recovery_information.st_ino,
            )
            _cleanup_owned_stage(stage, recovery_identity)
        except OSError:
            pass
        raise ReferencePackageInstallError(
            f"{operation} stage identity cannot be inspected"
        ) from error
    if not stat.S_ISDIR(information.st_mode) or installer._is_reparse(information):
        raise ReferencePackageInstallError(
            f"{operation} stage is not one real directory"
        )
    return stage, (information.st_dev, information.st_ino)


def _cleanup_owned_stage(
    stage: Path | None, identity: tuple[int, int] | None
) -> None:
    if stage is None or identity is None:
        return
    try:
        information = stage.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(information.st_mode)
            or installer._is_reparse(information)
            or (information.st_dev, information.st_ino) != identity
        ):
            return
        shutil.rmtree(stage)
    except FileNotFoundError:
        return
    except OSError:
        return


def _publish_stage(
    stage: Path, target: Path, identity: tuple[int, int]
) -> None:
    if stage.parent != target.parent or os.path.lexists(target):
        raise ReferencePackageInstallError(
            "release asset output appeared before publication"
    )
    published = False
    try:
        _rename_directory_no_replace(stage, target)
        published = True
        information = target.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(information.st_mode)
            or installer._is_reparse(information)
            or (information.st_dev, information.st_ino) != identity
        ):
            raise ReferencePackageInstallError(
                "published release output identity differs from its stage"
            )
    except ReferencePackageInstallError:
        if published:
            _cleanup_owned_stage(target, identity)
        raise
    except OSError as error:
        if published:
            _cleanup_owned_stage(target, identity)
        raise ReferencePackageInstallError(
            "release asset output cannot be atomically published"
        ) from error


def _rename_directory_no_replace(source: Path, target: Path) -> None:
    if os.name == "nt":
        os.rename(source, target)
        return
    library = ctypes.CDLL(None, use_errno=True)
    if sys.platform.startswith("linux"):
        rename = getattr(library, "renameat2", None)
        if rename is None:
            raise OSError(
                errno.ENOTSUP,
                "atomic no-replace rename is unavailable",
            )
        rename.argtypes = (
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        )
        rename.restype = ctypes.c_int
        result = rename(
            -100,
            os.fsencode(source),
            -100,
            os.fsencode(target),
            1,
        )
    elif sys.platform == "darwin":
        rename = getattr(library, "renamex_np", None)
        if rename is None:
            raise OSError(
                errno.ENOTSUP,
                "atomic no-replace rename is unavailable",
            )
        rename.argtypes = (
            ctypes.c_char_p,
            ctypes.c_char_p,
            ctypes.c_uint,
        )
        rename.restype = ctypes.c_int
        result = rename(os.fsencode(source), os.fsencode(target), 4)
    else:
        raise OSError(
            errno.ENOTSUP,
            "atomic no-replace rename is unavailable",
        )
    if result != 0:
        error_number = ctypes.get_errno()
        raise OSError(
            error_number,
            os.strerror(error_number),
            str(target),
        )


def _exact_positive_integer(value: object, label: str) -> int:
    number = installer._exact_integer(value, label)
    if number <= 0:
        raise ReferencePackageInstallError(f"{label} must be positive")
    return number


def _exact_list(value: object, label: str) -> list[object]:
    if type(value) is not list:
        raise ReferencePackageInstallError(f"{label} has the wrong exact type")
    return value


def _validate_url(url: str, *, allow_loopback_http: bool) -> str:
    if (
        type(url) is not str
        or not url
        or url != url.strip()
        or any(ord(character) < 0x21 or ord(character) > 0x7E for character in url)
    ):
        raise ReferencePackageInstallError("release asset URL is malformed")
    try:
        url.encode("ascii", "strict")
        parsed = urllib.parse.urlsplit(url)
        port = parsed.port
    except (UnicodeError, ValueError) as error:
        raise ReferencePackageInstallError("release asset URL is malformed") from error
    if (
        not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or not parsed.path
    ):
        raise ReferencePackageInstallError("release asset URL is malformed")
    del port
    if parsed.scheme == "https":
        return url
    if (
        allow_loopback_http
        and parsed.scheme == "http"
        and parsed.hostname in {"127.0.0.1", "::1"}
    ):
        return url
    raise ReferencePackageInstallError(
        "release asset URL must use HTTPS outside the loopback test seam"
    )


class _SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, *, allow_loopback_http: bool) -> None:
        super().__init__()
        self._allow_loopback_http = allow_loopback_http

    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: object,
        code: int,
        message: str,
        headers: Mapping[str, str],
        new_url: str,
    ) -> urllib.request.Request | None:
        _validate_url(
            new_url,
            allow_loopback_http=self._allow_loopback_http,
        )
        return super().redirect_request(
            request,
            file_pointer,
            code,
            message,
            headers,
            new_url,
        )


def _open_download(url: str, *, allow_loopback_http: bool) -> object:
    validated = _validate_url(url, allow_loopback_http=allow_loopback_http)
    opener = urllib.request.build_opener(
        _SafeRedirectHandler(allow_loopback_http=allow_loopback_http)
    )
    request = urllib.request.Request(
        validated,
        headers={
            "Accept-Encoding": "identity",
            "User-Agent": "FlightAlert-Experiment8-Reference/1",
        },
        method="GET",
    )
    try:
        response = opener.open(request, timeout=_DOWNLOAD_TIMEOUT_SECONDS)
    except ReferencePackageInstallError:
        raise
    except (OSError, http.client.HTTPException, urllib.error.URLError) as error:
        raise ReferencePackageInstallError(
            "release asset download cannot be opened"
        ) from error
    try:
        final_url = response.geturl()
        _validate_url(final_url, allow_loopback_http=allow_loopback_http)
        encoding = response.headers.get("Content-Encoding")
        if encoding not in (None, "", "identity"):
            raise ReferencePackageInstallError(
                "release asset response uses content encoding"
            )
    except BaseException:
        response.close()
        raise
    return response


def _declared_content_length(response: object, label: str) -> int | None:
    text = response.headers.get("Content-Length")
    if text is None:
        return None
    if not text.isascii() or not text.isdecimal():
        raise ReferencePackageInstallError(f"{label} content length is malformed")
    return int(text)


def _download_manifest(
    manifest_url: str, *, allow_loopback_http: bool
) -> tuple[dict[str, object], str]:
    validated_url = _validate_url(
        manifest_url,
        allow_loopback_http=allow_loopback_http,
    )
    parsed = urllib.parse.urlsplit(validated_url)
    decoded_path = urllib.parse.unquote(parsed.path)
    if (
        "\\" in decoded_path
        or "\0" in decoded_path
        or PurePosixPath(decoded_path).name != RELEASE_MANIFEST_NAME
    ):
        raise ReferencePackageInstallError(
            "release manifest URL has the wrong file name"
        )
    response = _open_download(
        validated_url,
        allow_loopback_http=allow_loopback_http,
    )
    try:
        declared = _declared_content_length(response, "release manifest")
        if declared is not None and (
            declared <= 0 or declared > installer.MAX_JSON_BYTES
        ):
            raise ReferencePackageInstallError(
                "release manifest content length is outside its bound"
            )
        raw = response.read(installer.MAX_JSON_BYTES + 1)
    except ReferencePackageInstallError:
        raise
    except (OSError, http.client.HTTPException) as error:
        raise ReferencePackageInstallError(
            "release manifest cannot be downloaded"
        ) from error
    finally:
        response.close()
    if not raw or len(raw) > installer.MAX_JSON_BYTES:
        raise ReferencePackageInstallError(
            "release manifest bytes are outside their bound"
        )
    try:
        manifest = installer._parse_strict_json_bytes(raw, "release manifest")
        canonical = installer._canonical_json_bytes(manifest)
    except RecursionError as error:
        raise ReferencePackageInstallError(
            "release manifest nesting is malformed"
        ) from error
    if canonical != raw:
        raise ReferencePackageInstallError("release manifest is not canonical JSON")
    return manifest, validated_url


def _validated_manifest_files(
    manifest: Mapping[str, object],
) -> list[Mapping[str, object]]:
    installer._exact_fields(
        manifest,
        {"apk", "finalResult", "package", "schema", "sourceCommit"},
        "release manifest fields",
    )
    if manifest.get("schema") != RELEASE_MANIFEST_SCHEMA:
        raise ReferencePackageInstallError("release manifest schema differs")
    source_commit = installer._exact_string(
        manifest.get("sourceCommit"), "release manifest source commit"
    )
    if _FULL_COMMIT.fullmatch(source_commit) is None:
        raise ReferencePackageInstallError(
            "release manifest source commit is malformed"
        )
    authority_bytes: dict[str, int] = {}
    for key, label in (("apk", "APK"), ("finalResult", "final result")):
        identity = installer._exact_mapping(
            manifest.get(key), f"release manifest {label}"
        )
        installer._exact_fields(
            identity,
            {"bytes", "sha256"},
            f"release manifest {label} fields",
        )
        authority_bytes[key] = _exact_positive_integer(
            identity.get("bytes"), f"release manifest {label} bytes"
        )
        installer._exact_sha256(
            identity.get("sha256"), f"release manifest {label} SHA-256"
        )
    if authority_bytes["finalResult"] > installer.MAX_JSON_BYTES:
        raise ReferencePackageInstallError(
            "release manifest final result exceeds its byte bound"
        )

    package = installer._exact_mapping(
        manifest.get("package"), "release manifest package"
    )
    installer._exact_fields(
        package,
        {"bytes", "files", "packageId"},
        "release manifest package fields",
    )
    if (
        installer._exact_string(
            package.get("packageId"), "release manifest package ID"
        )
        != installer.PACKAGE_ID
    ):
        raise ReferencePackageInstallError("release manifest package ID differs")
    package_bytes = _exact_positive_integer(
        package.get("bytes"), "release manifest package bytes"
    )
    if package_bytes >= installer.HARD_PACKAGE_CEILING_BYTES:
        raise ReferencePackageInstallError(
            "release manifest package is not below the package ceiling"
        )
    total_footprint = (
        package_bytes
        + authority_bytes["apk"]
        + installer.MANDATORY_RESERVE_BYTES
    )
    if total_footprint >= installer.HARD_FOOTPRINT_CEILING_BYTES:
        raise ReferencePackageInstallError(
            "release manifest authority footprint is not below its hard ceiling"
        )
    files = _exact_list(package.get("files"), "release manifest files")
    if len(files) != len(installer.PACKAGE_FILE_NAMES):
        raise ReferencePackageInstallError(
            "release manifest file inventory differs"
        )

    validated_files: list[Mapping[str, object]] = []
    asset_names: set[str] = set()
    counted_package_bytes = 0
    for file_index, expected_name in enumerate(installer.PACKAGE_FILE_NAMES):
        item = installer._exact_mapping(
            files[file_index], f"release manifest file {file_index}"
        )
        installer._exact_fields(
            item,
            {"bytes", "chunks", "name", "sha256"},
            f"release manifest file {file_index} fields",
        )
        name = installer._exact_string(
            item.get("name"), f"release manifest file {file_index} name"
        )
        if name != expected_name:
            raise ReferencePackageInstallError(
                "release manifest file path or order differs"
            )
        file_bytes = _exact_positive_integer(
            item.get("bytes"), f"release manifest file {name} bytes"
        )
        installer._exact_sha256(
            item.get("sha256"), f"release manifest file {name} SHA-256"
        )
        chunks = _exact_list(
            item.get("chunks"), f"release manifest file {name} chunks"
        )
        if not chunks:
            raise ReferencePackageInstallError(
                f"release manifest file {name} has no chunks"
            )
        expected_offset = 0
        for chunk_index, chunk_value in enumerate(chunks):
            chunk = installer._exact_mapping(
                chunk_value,
                f"release manifest file {name} chunk {chunk_index}",
            )
            installer._exact_fields(
                chunk,
                {"asset", "bytes", "offset", "sha256"},
                f"release manifest file {name} chunk {chunk_index} fields",
            )
            asset = installer._exact_string(
                chunk.get("asset"),
                f"release manifest file {name} chunk {chunk_index} asset",
            )
            expected_asset = (
                f"{installer.PACKAGE_ID}.{file_index:02d}."
                f"{chunk_index:05d}.part"
            )
            if asset != expected_asset or asset in asset_names:
                raise ReferencePackageInstallError(
                    "release manifest asset name collides or is unsafe"
                )
            asset_names.add(asset)
            size = _exact_positive_integer(
                chunk.get("bytes"),
                f"release manifest file {name} chunk {chunk_index} bytes",
            )
            if size >= MAX_RELEASE_ASSET_BYTES:
                raise ReferencePackageInstallError(
                    "release manifest chunk is not below 2 GB"
                )
            offset = installer._exact_integer(
                chunk.get("offset"),
                f"release manifest file {name} chunk {chunk_index} offset",
            )
            if offset != expected_offset or size > file_bytes - expected_offset:
                raise ReferencePackageInstallError(
                    "release manifest chunk offsets contain a gap or overlap"
                )
            installer._exact_sha256(
                chunk.get("sha256"),
                f"release manifest file {name} chunk {chunk_index} SHA-256",
            )
            expected_offset += size
        if expected_offset != file_bytes:
            raise ReferencePackageInstallError(
                "release manifest chunks do not total their file"
            )
        counted_package_bytes += file_bytes
        validated_files.append(item)
    if counted_package_bytes != package_bytes:
        raise ReferencePackageInstallError(
            "release manifest files do not total the package"
        )
    return validated_files


def _write_exact_file(path: Path, raw: bytes) -> None:
    try:
        with path.open("xb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
    except OSError as error:
        raise ReferencePackageInstallError(
            f"release asset {path.name} cannot be written"
        ) from error


def _assert_prepared_stage(
    stage: Path,
    file_documents: list[dict[str, object]],
    manifest_raw: bytes,
) -> None:
    expected: dict[str, tuple[int, str]] = {
        RELEASE_MANIFEST_NAME: (
            len(manifest_raw),
            hashlib.sha256(manifest_raw).hexdigest(),
        )
    }
    for item in file_documents:
        for chunk_value in _exact_list(
            item.get("chunks"), "prepared release chunks"
        ):
            chunk = installer._exact_mapping(
                chunk_value, "prepared release chunk"
            )
            asset = installer._exact_string(
                chunk.get("asset"), "prepared release asset"
            )
            if asset in expected:
                raise ReferencePackageInstallError(
                    "prepared release asset inventory collides"
                )
            expected[asset] = (
                _exact_positive_integer(
                    chunk.get("bytes"), "prepared release asset bytes"
                ),
                installer._exact_sha256(
                    chunk.get("sha256"), "prepared release asset SHA-256"
                ),
            )
    try:
        actual_names = tuple(sorted(path.name for path in stage.iterdir()))
    except OSError as error:
        raise ReferencePackageInstallError(
            "prepared release asset inventory cannot be inspected"
        ) from error
    if actual_names != tuple(sorted(expected)):
        raise ReferencePackageInstallError(
            "prepared release asset inventory differs from its manifest"
        )
    for name, (expected_bytes, expected_sha256) in expected.items():
        actual = installer._hash_regular_file(
            stage / name, f"prepared release asset {name}"
        )
        if (
            actual.byte_length != expected_bytes
            or actual.sha256 != expected_sha256
        ):
            raise ReferencePackageInstallError(
                f"prepared release asset {name} differs from its manifest"
            )


def _assert_validated_input_identities(
    plan: HostInstallPlan, result_file: installer.InstallFile
) -> None:
    for item in plan.package_files:
        installer._assert_install_file_unchanged(item)
    installer._assert_install_file_unchanged(result_file)
    try:
        directory_identity = installer._identity(
            plan.package_directory.stat(follow_symlinks=False)
        )
        inventory = installer._host_directory_inventory(plan.package_directory)
        apk_information = plan.apk_path.stat(follow_symlinks=False)
    except OSError as error:
        raise ReferencePackageInstallError(
            "validated release inputs changed before publication"
        ) from error
    if (
        directory_identity != plan.package_directory_identity
        or inventory != plan.package_inventory
        or not stat.S_ISREG(apk_information.st_mode)
        or installer._is_reparse(apk_information)
        or installer._identity(apk_information) != plan.apk_stat_identity
    ):
        raise ReferencePackageInstallError(
            "validated release inputs changed before publication"
        )


def _stream_file_chunks(
    *,
    source: installer.InstallFile,
    file_index: int,
    stage: Path,
    chunk_bytes: int,
) -> dict[str, object]:
    chunks: list[dict[str, object]] = []
    file_digest = hashlib.sha256()
    offset = 0
    try:
        with source.path.open("rb") as source_handle:
            opened = os.fstat(source_handle.fileno())
            if installer._identity(opened) != source.stat_identity:
                raise ReferencePackageInstallError(
                    f"package file {source.name} changed before sharding"
                )
            chunk_index = 0
            while offset < source.byte_length:
                expected = min(chunk_bytes, source.byte_length - offset)
                asset_name = (
                    f"{installer.PACKAGE_ID}.{file_index:02d}."
                    f"{chunk_index:05d}.part"
                )
                asset_path = stage / asset_name
                asset_digest = hashlib.sha256()
                remaining = expected
                with asset_path.open("xb") as asset_handle:
                    while remaining:
                        raw = source_handle.read(
                            min(installer.HASH_CHUNK_BYTES, remaining)
                        )
                        if not raw:
                            raise ReferencePackageInstallError(
                                f"package file {source.name} was truncated"
                            )
                        asset_handle.write(raw)
                        asset_digest.update(raw)
                        file_digest.update(raw)
                        remaining -= len(raw)
                    asset_handle.flush()
                    os.fsync(asset_handle.fileno())
                asset_sha256 = asset_digest.hexdigest()
                chunks.append(
                    {
                        "asset": asset_name,
                        "bytes": expected,
                        "offset": offset,
                        "sha256": asset_sha256,
                    }
                )
                offset += expected
                chunk_index += 1
            after_handle = os.fstat(source_handle.fileno())
    except ReferencePackageInstallError:
        raise
    except OSError as error:
        raise ReferencePackageInstallError(
            f"package file {source.name} cannot be sharded"
        ) from error
    if (
        installer._identity(after_handle) != source.stat_identity
        or file_digest.hexdigest() != source.sha256
    ):
        raise ReferencePackageInstallError(
            f"package file {source.name} changed during sharding"
        )
    installer._assert_install_file_unchanged(source)
    return {
        "bytes": source.byte_length,
        "chunks": chunks,
        "name": source.name,
        "sha256": source.sha256,
    }


def prepare_release_assets(
    *,
    package_directory: Path,
    apk_path: Path,
    final_result_path: Path,
    source_commit: str,
    output_directory: Path,
    chunk_bytes: int = DEFAULT_CHUNK_BYTES,
) -> Path:
    """Validate and deterministically shard an exact Experiment 8 package."""

    if type(source_commit) is not str or _FULL_COMMIT.fullmatch(source_commit) is None:
        raise ReferencePackageInstallError(
            "source commit is not one full lowercase Git commit"
        )
    if (
        type(chunk_bytes) is not int
        or chunk_bytes <= 0
        or chunk_bytes >= MAX_RELEASE_ASSET_BYTES
    ):
        raise ReferencePackageInstallError(
            "release asset chunk size must be positive and below 2 GB"
        )
    output = _fresh_directory_target(
        Path(output_directory), "release asset output"
    )
    final_result_path = installer._real_file(
        Path(final_result_path), "final result"
    )
    result_file = installer._hash_regular_file(final_result_path, "final result")
    plan = HostInstallPlan.validate(
        package_directory=Path(package_directory),
        apk_path=Path(apk_path),
        final_result_path=final_result_path,
    )
    if not _same_install_file(
        result_file,
        installer._hash_regular_file(final_result_path, "final result"),
    ):
        raise ReferencePackageInstallError(
            "final result changed during host-plan validation"
        )

    stage: Path | None = None
    stage_identity: tuple[int, int] | None = None
    try:
        stage, stage_identity = _create_stage(output, "prepare")
        file_documents = [
            _stream_file_chunks(
                source=item,
                file_index=index,
                stage=stage,
                chunk_bytes=chunk_bytes,
            )
            for index, item in enumerate(plan.package_files)
        ]
        manifest = {
            "apk": {
                "bytes": plan.apk_bytes,
                "sha256": plan.apk_sha256,
            },
            "finalResult": {
                "bytes": result_file.byte_length,
                "sha256": result_file.sha256,
            },
            "package": {
                "bytes": plan.package_bytes,
                "files": file_documents,
                "packageId": installer.PACKAGE_ID,
            },
            "schema": RELEASE_MANIFEST_SCHEMA,
            "sourceCommit": source_commit,
        }
        _validated_manifest_files(manifest)
        manifest_raw = installer._canonical_json_bytes(manifest)
        if len(manifest_raw) > installer.MAX_JSON_BYTES:
            raise ReferencePackageInstallError(
                "release manifest exceeds its byte bound"
            )
        manifest_path = stage / RELEASE_MANIFEST_NAME
        _write_exact_file(manifest_path, manifest_raw)
        installer._assert_host_plan_unchanged(plan)
        if not _same_install_file(
            result_file,
            installer._hash_regular_file(final_result_path, "final result"),
        ):
            raise ReferencePackageInstallError(
                "final result changed during release preparation"
            )
        _assert_prepared_stage(stage, file_documents, manifest_raw)
        _assert_validated_input_identities(plan, result_file)
        _publish_stage(stage, output, stage_identity)
        stage = None
        stage_identity = None
        return output / RELEASE_MANIFEST_NAME
    finally:
        _cleanup_owned_stage(stage, stage_identity)


def _asset_url(manifest_url: str, asset_name: str) -> str:
    parsed = urllib.parse.urlsplit(manifest_url)
    directory = parsed.path.rsplit("/", 1)[0] + "/"
    base = urllib.parse.urlunsplit(
        (parsed.scheme, parsed.netloc, directory, "", "")
    )
    return urllib.parse.urljoin(base, asset_name)


def _stream_downloaded_chunk(
    *,
    url: str,
    output_handle: object,
    expected_bytes: int,
    expected_sha256: str,
    file_digest: object,
    allow_loopback_http: bool,
) -> None:
    response = _open_download(url, allow_loopback_http=allow_loopback_http)
    chunk_digest = hashlib.sha256()
    remaining = expected_bytes
    try:
        declared = _declared_content_length(response, "release asset")
        if declared is not None and declared != expected_bytes:
            raise ReferencePackageInstallError(
                "release asset content length differs"
            )
        while remaining:
            raw = response.read(min(_DOWNLOAD_BUFFER_BYTES, remaining))
            if not raw:
                raise ReferencePackageInstallError("release asset is truncated")
            output_handle.write(raw)
            chunk_digest.update(raw)
            file_digest.update(raw)
            remaining -= len(raw)
        if response.read(1):
            raise ReferencePackageInstallError(
                "release asset exceeds its declared bytes"
            )
    except ReferencePackageInstallError:
        raise
    except (OSError, http.client.HTTPException) as error:
        raise ReferencePackageInstallError(
            "release asset cannot be downloaded"
        ) from error
    finally:
        response.close()
    if chunk_digest.hexdigest() != expected_sha256:
        raise ReferencePackageInstallError("release asset SHA-256 differs")


def _assert_reconstructed_stage(
    stage: Path, files: list[Mapping[str, object]]
) -> None:
    try:
        actual_names = tuple(sorted(path.name for path in stage.iterdir()))
    except OSError as error:
        raise ReferencePackageInstallError(
            "reconstructed package inventory cannot be inspected"
        ) from error
    if actual_names != installer.PACKAGE_FILE_NAMES:
        raise ReferencePackageInstallError(
            "reconstructed package inventory differs"
        )
    for item in files:
        name = installer._exact_string(
            item.get("name"), "reconstructed package file name"
        )
        expected_bytes = _exact_positive_integer(
            item.get("bytes"), f"reconstructed package file {name} bytes"
        )
        expected_sha256 = installer._exact_sha256(
            item.get("sha256"),
            f"reconstructed package file {name} SHA-256",
        )
        actual = installer._hash_regular_file(
            stage / name, f"reconstructed package file {name}"
        )
        if (
            actual.byte_length != expected_bytes
            or actual.sha256 != expected_sha256
        ):
            raise ReferencePackageInstallError(
                f"reconstructed package file {name} differs"
            )


def fetch_release_assets(
    *,
    manifest_url: str,
    output_directory: Path,
    allow_loopback_http: bool = False,
) -> Path:
    """Fetch release shards and atomically reconstruct the exact package."""

    if type(allow_loopback_http) is not bool:
        raise ReferencePackageInstallError(
            "loopback HTTP test seam has the wrong exact type"
        )
    output = _fresh_directory_target(
        Path(output_directory), "reference package output"
    )
    if output.name != installer.PACKAGE_ID:
        raise ReferencePackageInstallError(
            "reference package output must use the exact package ID"
        )
    manifest, validated_manifest_url = _download_manifest(
        manifest_url,
        allow_loopback_http=allow_loopback_http,
    )
    files = _validated_manifest_files(manifest)

    stage: Path | None = None
    stage_identity: tuple[int, int] | None = None
    try:
        stage, stage_identity = _create_stage(output, "fetch")
        for item in files:
            name = installer._exact_string(
                item.get("name"), "release manifest file name"
            )
            expected_bytes = _exact_positive_integer(
                item.get("bytes"), f"release manifest file {name} bytes"
            )
            expected_sha256 = installer._exact_sha256(
                item.get("sha256"),
                f"release manifest file {name} SHA-256",
            )
            file_digest = hashlib.sha256()
            output_file = stage / name
            try:
                with output_file.open("xb") as output_handle:
                    for chunk_value in _exact_list(
                        item.get("chunks"),
                        f"release manifest file {name} chunks",
                    ):
                        chunk = installer._exact_mapping(
                            chunk_value,
                            f"release manifest file {name} chunk",
                        )
                        asset = installer._exact_string(
                            chunk.get("asset"),
                            f"release manifest file {name} chunk asset",
                        )
                        _stream_downloaded_chunk(
                            url=_asset_url(validated_manifest_url, asset),
                            output_handle=output_handle,
                            expected_bytes=_exact_positive_integer(
                                chunk.get("bytes"),
                                f"release manifest asset {asset} bytes",
                            ),
                            expected_sha256=installer._exact_sha256(
                                chunk.get("sha256"),
                                f"release manifest asset {asset} SHA-256",
                            ),
                            file_digest=file_digest,
                            allow_loopback_http=allow_loopback_http,
                        )
                    output_handle.flush()
                    os.fsync(output_handle.fileno())
            except ReferencePackageInstallError:
                raise
            except OSError as error:
                raise ReferencePackageInstallError(
                    f"reference package file {name} cannot be reconstructed"
                ) from error
            try:
                reconstructed_size = output_file.stat(
                    follow_symlinks=False
                ).st_size
            except OSError as error:
                raise ReferencePackageInstallError(
                    f"reconstructed package file {name} cannot be inspected"
                ) from error
            if (
                reconstructed_size != expected_bytes
                or file_digest.hexdigest() != expected_sha256
            ):
                raise ReferencePackageInstallError(
                    f"reconstructed package file {name} differs"
                )
        _assert_reconstructed_stage(stage, files)
        _publish_stage(stage, output, stage_identity)
        stage = None
        stage_identity = None
        return output
    finally:
        _cleanup_owned_stage(stage, stage_identity)


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Prepare or fetch verified Experiment 8 reference release shards."
        )
    )
    subparsers = parser.add_subparsers(dest="operation", required=True)
    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--package", required=True, type=Path)
    prepare.add_argument("--apk", required=True, type=Path)
    prepare.add_argument("--final-result", required=True, type=Path)
    prepare.add_argument("--source-commit", required=True)
    prepare.add_argument("--output", required=True, type=Path)
    prepare.add_argument(
        "--chunk-bytes",
        type=int,
        default=DEFAULT_CHUNK_BYTES,
    )
    fetch = subparsers.add_parser("fetch")
    fetch.add_argument("--manifest-url", required=True)
    fetch.add_argument("--output", required=True, type=Path)
    parsed = parser.parse_args(arguments)
    try:
        if parsed.operation == "prepare":
            result = prepare_release_assets(
                package_directory=parsed.package,
                apk_path=parsed.apk,
                final_result_path=parsed.final_result,
                source_commit=parsed.source_commit,
                output_directory=parsed.output,
                chunk_bytes=parsed.chunk_bytes,
            )
        else:
            result = fetch_release_assets(
                manifest_url=parsed.manifest_url,
                output_directory=parsed.output,
            )
    except ReferencePackageInstallError as error:
        parser.exit(1, f"error: {error}\n")
    print(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())


__all__ = [
    "DEFAULT_CHUNK_BYTES",
    "MAX_RELEASE_ASSET_BYTES",
    "RELEASE_MANIFEST_NAME",
    "RELEASE_MANIFEST_SCHEMA",
    "_main",
    "fetch_release_assets",
    "prepare_release_assets",
]
