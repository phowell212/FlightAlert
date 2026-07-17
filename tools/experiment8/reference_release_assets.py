"""Prepare, fetch, and materialize Experiment 8 reference release assets."""

from __future__ import annotations

import argparse
import copy
import ctypes
import errno
import hashlib
import http.client
import ipaddress
import os
import re
import shutil
import socket
import stat
import sys
import uuid
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Callable, Mapping, Sequence

from tools.experiment8 import reference_package_install as installer
from tools.experiment8.reference_package_install import (
    HostInstallPlan,
    ReferencePackageInstallError,
)


RELEASE_MANIFEST_NAME = (
    "world-experiment8-binary-v4.release-manifest.json"
)
RELEASE_MANIFEST_SCHEMA = (
    "flightalert.experiment8.reference-release-manifest.v2"
)
RELEASE_APK_ASSET_NAME = "FlightAlert-reference-preview.apk"
RELEASE_FINAL_RESULT_ASSET_NAME = (
    "world-experiment8-binary-v4.source-bound-result.json"
)
MATERIALIZED_FINAL_RESULT_NAME = "final-package-result.json"
MAX_RELEASE_ASSET_BYTES = 2_000_000_000
# GitHub permits 1,000 assets: manifest + APK + result leave 997 package shards.
MAX_RELEASE_CHUNKS = 997
DEFAULT_CHUNK_BYTES = 1_900_000_000
_FULL_COMMIT = re.compile(r"[0-9a-f]{40}\Z")
_DOWNLOAD_BUFFER_BYTES = 4 * 1024 * 1024
_DOWNLOAD_TIMEOUT_SECONDS = 60
_GITHUB_RELEASE_HOST = "github.com"
_GITHUB_RELEASE_PATH_PREFIX = (
    "/phowell212/FlightAlert/releases/download/"
)
_GITHUB_RELEASE_CDN_HOSTS = frozenset(
    {
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    }
)


def _reference_data_document(source_commit: str) -> dict[str, str]:
    return {
        "attribution": "© OpenStreetMap contributors",
        "copyrightUrl": "https://www.openstreetmap.org/copyright",
        "databaseLicense": "ODbL-1.0",
        "licenseUrl": "https://opendatacommons.org/licenses/odbl/1-0/",
        "noticeUrl": (
            "https://github.com/phowell212/FlightAlert/blob/"
            f"{source_commit}/THIRD_PARTY_REFERENCE_DATA.md"
        ),
        "sourceOffer": (
            "The complete machine-readable derived database is the six-file "
            "package reconstructed from the shards bound by this manifest "
            "and offered free of charge under ODbL 1.0."
        ),
    }


def _same_install_file(
    left: installer.InstallFile, right: installer.InstallFile
) -> bool:
    return (
        left.path == right.path
        and left.byte_length == right.byte_length
        and left.sha256 == right.sha256
        and left.stat_identity == right.stat_identity
    )


def _source_commit_from_final_result(final_result_path: Path) -> str:
    result = installer._read_strict_json(
        final_result_path,
        "final result",
        canonical=False,
    )
    apk = installer._exact_mapping(result.get("apk"), "final result APK")
    source_commit = installer._exact_string(
        apk.get("sourceCommit"),
        "final result APK source commit",
    )
    if _FULL_COMMIT.fullmatch(source_commit) is None:
        raise ReferencePackageInstallError(
            "final result APK source commit is malformed"
        )
    rebind = installer._exact_mapping(
        result.get("rebind"),
        "final result rebind",
    )
    rebound_commit = installer._exact_string(
        rebind.get("sourceCommit"),
        "final result rebind source commit",
    )
    if _FULL_COMMIT.fullmatch(rebound_commit) is None:
        raise ReferencePackageInstallError(
            "final result rebind source commit is malformed"
        )
    if rebound_commit != source_commit:
        raise ReferencePackageInstallError(
            "final result source commit bindings differ"
        )
    return source_commit


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
    stage: Path,
    target: Path,
    identity: tuple[int, int],
    validate_published: Callable[[Path], None],
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
        validate_published(target)
        final_information = target.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(final_information.st_mode)
            or installer._is_reparse(final_information)
            or (final_information.st_dev, final_information.st_ino) != identity
        ):
            raise ReferencePackageInstallError(
                "published release output changed during final validation"
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


def _validate_release_asset_plan(
    file_sizes: Sequence[int],
    chunk_bytes: int,
) -> int:
    if (
        type(chunk_bytes) is not int
        or chunk_bytes <= 0
        or chunk_bytes >= MAX_RELEASE_ASSET_BYTES
    ):
        raise ReferencePackageInstallError(
            "release asset chunk size must be positive and below 2 GB"
        )
    total_chunks = 0
    for size in file_sizes:
        if type(size) is not int or size <= 0:
            raise ReferencePackageInstallError(
                "release asset plan contains an invalid file size"
            )
        total_chunks += (size + chunk_bytes - 1) // chunk_bytes
        if total_chunks > MAX_RELEASE_CHUNKS:
            raise ReferencePackageInstallError(
                "release asset plan exceeds 997 package shards"
            )
    return total_chunks


def _split_download_url(url: str) -> urllib.parse.SplitResult:
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
        or not parsed.path.startswith("/")
        or port == 0
    ):
        raise ReferencePackageInstallError("release asset URL is malformed")
    return parsed


def _resolve_host_addresses(hostname: str, port: int) -> tuple[str, ...]:
    try:
        answers = socket.getaddrinfo(
            hostname,
            port,
            type=socket.SOCK_STREAM,
        )
    except (OSError, socket.gaierror) as error:
        raise ReferencePackageInstallError(
            "release asset host cannot be resolved"
        ) from error
    addresses = tuple(sorted({answer[4][0] for answer in answers}))
    if not addresses:
        raise ReferencePackageInstallError(
            "release asset host has no resolved addresses"
        )
    return addresses


def _assert_resolved_address_scope(
    hostname: str,
    port: int,
    *,
    loopback_only: bool,
) -> None:
    addresses = _resolve_host_addresses(hostname, port)
    for text in addresses:
        try:
            address = ipaddress.ip_address(text.split("%", 1)[0])
        except ValueError as error:
            raise ReferencePackageInstallError(
                "release asset host resolved to a malformed address"
            ) from error
        if loopback_only:
            allowed = address.is_loopback
        else:
            allowed = address.is_global and not (
                address.is_loopback
                or address.is_private
                or address.is_link_local
                or address.is_reserved
                or address.is_unspecified
                or address.is_multicast
            )
        if not allowed:
            raise ReferencePackageInstallError(
                "release asset host resolved to a nonpublic address"
            )


def _plain_release_path(path: str) -> str:
    decoded = urllib.parse.unquote(path)
    if (
        decoded != path
        or "\\" in decoded
        or "\0" in decoded
        or "//" in decoded
    ):
        raise ReferencePackageInstallError(
            "release asset URL path is malformed"
        )
    return decoded


def _github_release_directory(path: str) -> str:
    plain = _plain_release_path(path)
    if not plain.startswith(_GITHUB_RELEASE_PATH_PREFIX):
        raise ReferencePackageInstallError(
            "release asset URL is outside the FlightAlert releases"
        )
    remainder = plain[len(_GITHUB_RELEASE_PATH_PREFIX) :]
    parts = remainder.split("/")
    if (
        len(parts) != 2
        or any(part in {"", ".", ".."} for part in parts)
    ):
        raise ReferencePackageInstallError(
            "release asset URL is not one exact GitHub release asset"
        )
    return _GITHUB_RELEASE_PATH_PREFIX + parts[0] + "/"


class _DownloadUrlPolicy:
    def __init__(
        self,
        *,
        initial_url: str,
        release_directory: str,
        loopback_origin: tuple[str, str, int] | None,
    ) -> None:
        self.initial_url = initial_url
        self.release_directory = release_directory
        self._loopback_origin = loopback_origin

    @classmethod
    def for_initial(
        cls,
        url: str,
        *,
        allow_loopback_http: bool,
    ) -> "_DownloadUrlPolicy":
        parsed = _split_download_url(url)
        hostname = parsed.hostname
        if hostname is None:
            raise ReferencePackageInstallError("release asset URL is malformed")
        port = parsed.port
        if (
            allow_loopback_http
            and parsed.scheme == "http"
            and hostname in {"127.0.0.1", "::1"}
        ):
            effective_port = port if port is not None else 80
            _assert_resolved_address_scope(
                hostname,
                effective_port,
                loopback_only=True,
            )
            plain_path = _plain_release_path(parsed.path)
            if PurePosixPath(plain_path).name in {"", ".", ".."}:
                raise ReferencePackageInstallError(
                    "loopback release asset URL has no file name"
                )
            directory = plain_path.rsplit("/", 1)[0] + "/"
            return cls(
                initial_url=url,
                release_directory=directory,
                loopback_origin=("http", hostname, effective_port),
            )
        if (
            parsed.scheme != "https"
            or hostname != _GITHUB_RELEASE_HOST
            or port not in (None, 443)
            or parsed.query
        ):
            raise ReferencePackageInstallError(
                "release asset URL must be one FlightAlert GitHub release asset"
            )
        directory = _github_release_directory(parsed.path)
        _assert_resolved_address_scope(
            hostname,
            443,
            loopback_only=False,
        )
        return cls(
            initial_url=url,
            release_directory=directory,
            loopback_origin=None,
        )

    def validate_redirect(self, url: str) -> str:
        parsed = _split_download_url(url)
        hostname = parsed.hostname
        if hostname is None:
            raise ReferencePackageInstallError("release asset URL is malformed")
        port = parsed.port
        if self._loopback_origin is not None:
            effective_port = port if port is not None else 80
            origin = (parsed.scheme, hostname, effective_port)
            plain_path = _plain_release_path(parsed.path)
            directory = plain_path.rsplit("/", 1)[0] + "/"
            if origin != self._loopback_origin or directory != self.release_directory:
                raise ReferencePackageInstallError(
                    "loopback release redirect escaped its exact origin or directory"
                )
            _assert_resolved_address_scope(
                hostname,
                effective_port,
                loopback_only=True,
            )
            return url
        if parsed.scheme != "https" or port not in (None, 443):
            raise ReferencePackageInstallError(
                "release asset redirect must use default-port HTTPS"
            )
        if hostname == _GITHUB_RELEASE_HOST:
            if _github_release_directory(parsed.path) != self.release_directory:
                raise ReferencePackageInstallError(
                    "release asset redirect escaped its exact release directory"
                )
        elif hostname in _GITHUB_RELEASE_CDN_HOSTS:
            decoded = urllib.parse.unquote(parsed.path)
            if "\\" in decoded or "\0" in decoded:
                raise ReferencePackageInstallError(
                    "release asset CDN URL path is malformed"
                )
        else:
            raise ReferencePackageInstallError(
                "release asset redirect uses an untrusted origin"
            )
        _assert_resolved_address_scope(
            hostname,
            443,
            loopback_only=False,
        )
        return url


class _SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def __init__(self, *, policy: _DownloadUrlPolicy) -> None:
        super().__init__()
        self._policy = policy

    def redirect_request(
        self,
        request: urllib.request.Request,
        file_pointer: object,
        code: int,
        message: str,
        headers: Mapping[str, str],
        new_url: str,
    ) -> urllib.request.Request | None:
        absolute_url = urllib.parse.urljoin(request.full_url, new_url)
        validated_url = self._policy.validate_redirect(absolute_url)
        return super().redirect_request(
            request,
            file_pointer,
            code,
            message,
            headers,
            validated_url,
        )


def _open_download(url: str, *, allow_loopback_http: bool) -> object:
    policy = _DownloadUrlPolicy.for_initial(
        url,
        allow_loopback_http=allow_loopback_http,
    )
    opener = urllib.request.build_opener(
        _SafeRedirectHandler(policy=policy)
    )
    request = urllib.request.Request(
        policy.initial_url,
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
        policy.validate_redirect(final_url)
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
    _split_download_url(manifest_url)
    validated_url = manifest_url
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
        {
            "apk",
            "finalResult",
            "installPolicy",
            "package",
            "referenceData",
            "schema",
            "sourceCommit",
        },
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
    reference_data = installer._exact_mapping(
        manifest.get("referenceData"), "release manifest reference data"
    )
    installer._exact_fields(
        reference_data,
        set(_reference_data_document(source_commit)),
        "release manifest reference data fields",
    )
    if reference_data != _reference_data_document(source_commit):
        raise ReferencePackageInstallError(
            "release manifest reference data notice differs"
        )
    install_policy = installer._validated_install_policy(
        manifest.get("installPolicy")
    )
    authority_bytes: dict[str, int] = {}
    authority_assets: set[str] = set()
    for key, label, expected_asset in (
        ("apk", "APK", RELEASE_APK_ASSET_NAME),
        (
            "finalResult",
            "final result",
            RELEASE_FINAL_RESULT_ASSET_NAME,
        ),
    ):
        identity = installer._exact_mapping(
            manifest.get(key), f"release manifest {label}"
        )
        installer._exact_fields(
            identity,
            {"asset", "bytes", "sha256"},
            f"release manifest {label} fields",
        )
        asset = installer._exact_string(
            identity.get("asset"), f"release manifest {label} asset"
        )
        if asset != expected_asset or asset in authority_assets:
            raise ReferencePackageInstallError(
                f"release manifest {label} asset name differs"
            )
        authority_assets.add(asset)
        authority_bytes[key] = _exact_positive_integer(
            identity.get("bytes"), f"release manifest {label} bytes"
        )
        if authority_bytes[key] >= MAX_RELEASE_ASSET_BYTES:
            raise ReferencePackageInstallError(
                f"release manifest {label} is not below the asset ceiling"
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
    if (
        install_policy == installer.INSTALL_POLICY_RELEASE
        and package_bytes >= installer.HARD_PACKAGE_CEILING_BYTES
    ):
        raise ReferencePackageInstallError(
            "release manifest package is not below the package ceiling"
        )
    total_footprint = (
        package_bytes
        + authority_bytes["apk"]
        + installer.MANDATORY_RESERVE_BYTES
    )
    if (
        install_policy == installer.INSTALL_POLICY_RELEASE
        and total_footprint >= installer.HARD_FOOTPRINT_CEILING_BYTES
    ):
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
            if len(asset_names) > MAX_RELEASE_CHUNKS:
                raise ReferencePackageInstallError(
                    "release manifest exceeds 997 package shards"
                )
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
    authority_documents: Sequence[Mapping[str, object]],
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
    for identity in authority_documents:
        asset = installer._exact_string(
            identity.get("asset"), "prepared release authority asset"
        )
        if asset in expected:
            raise ReferencePackageInstallError(
                "prepared release authority asset inventory collides"
            )
        expected[asset] = (
            _exact_positive_integer(
                identity.get("bytes"),
                f"prepared release authority asset {asset} bytes",
            ),
            installer._exact_sha256(
                identity.get("sha256"),
                f"prepared release authority asset {asset} SHA-256",
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


def _copy_release_authority_asset(
    *, source: installer.InstallFile, asset_name: str, stage: Path
) -> dict[str, object]:
    destination = stage / asset_name
    digest = hashlib.sha256()
    copied = 0
    try:
        with source.path.open("rb") as source_handle, destination.open(
            "xb"
        ) as destination_handle:
            opened = os.fstat(source_handle.fileno())
            if installer._identity(opened) != source.stat_identity:
                raise ReferencePackageInstallError(
                    f"release authority {asset_name} changed before copying"
                )
            while copied < source.byte_length:
                raw = source_handle.read(
                    min(installer.HASH_CHUNK_BYTES, source.byte_length - copied)
                )
                if not raw:
                    raise ReferencePackageInstallError(
                        f"release authority {asset_name} was truncated"
                    )
                destination_handle.write(raw)
                digest.update(raw)
                copied += len(raw)
            if source_handle.read(1):
                raise ReferencePackageInstallError(
                    f"release authority {asset_name} exceeds its bound size"
                )
            destination_handle.flush()
            os.fsync(destination_handle.fileno())
            after = os.fstat(source_handle.fileno())
    except ReferencePackageInstallError:
        raise
    except OSError as error:
        raise ReferencePackageInstallError(
            f"release authority {asset_name} cannot be copied"
        ) from error
    if (
        copied != source.byte_length
        or digest.hexdigest() != source.sha256
        or installer._identity(after) != source.stat_identity
    ):
        raise ReferencePackageInstallError(
            f"release authority {asset_name} changed during copying"
        )
    installer._assert_install_file_unchanged(source)
    return {
        "asset": asset_name,
        "bytes": source.byte_length,
        "sha256": source.sha256,
    }


def prepare_release_assets(
    *,
    package_directory: Path,
    apk_path: Path,
    final_result_path: Path,
    output_directory: Path,
    chunk_bytes: int = DEFAULT_CHUNK_BYTES,
    install_policy: str = installer.INSTALL_POLICY_RELEASE,
) -> Path:
    """Validate and deterministically shard an exact Experiment 8 package."""

    if (
        type(chunk_bytes) is not int
        or chunk_bytes <= 0
        or chunk_bytes >= MAX_RELEASE_ASSET_BYTES
    ):
        raise ReferencePackageInstallError(
            "release asset chunk size must be positive and below 2 GB"
        )
    install_policy = installer._validated_install_policy(install_policy)
    output = _fresh_directory_target(
        Path(output_directory), "release asset output"
    )
    final_result_path = installer._real_file(
        Path(final_result_path), "final result"
    )
    result_file = installer._hash_regular_file(final_result_path, "final result")
    source_commit = _source_commit_from_final_result(final_result_path)
    plan = HostInstallPlan.validate(
        package_directory=Path(package_directory),
        apk_path=Path(apk_path),
        final_result_path=final_result_path,
        install_policy=install_policy,
        require_install_policy_binding=(
            install_policy
            != installer.INSTALL_POLICY_RELEASE
        ),
    )
    if plan.apk_bytes >= MAX_RELEASE_ASSET_BYTES:
        raise ReferencePackageInstallError(
            "release APK is not below the asset ceiling"
        )
    if not _same_install_file(
        result_file,
        installer._hash_regular_file(final_result_path, "final result"),
    ):
        raise ReferencePackageInstallError(
            "final result changed during host-plan validation"
        )
    _validate_release_asset_plan(
        tuple(item.byte_length for item in plan.package_files),
        chunk_bytes,
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
        apk_document = _copy_release_authority_asset(
            source=installer._hash_regular_file(plan.apk_path, "release APK"),
            asset_name=RELEASE_APK_ASSET_NAME,
            stage=stage,
        )
        result_document = _copy_release_authority_asset(
            source=result_file,
            asset_name=RELEASE_FINAL_RESULT_ASSET_NAME,
            stage=stage,
        )
        manifest = {
            "apk": apk_document,
            "finalResult": result_document,
            "installPolicy": install_policy,
            "package": {
                "bytes": plan.package_bytes,
                "files": file_documents,
                "packageId": installer.PACKAGE_ID,
            },
            "referenceData": _reference_data_document(source_commit),
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
        _assert_prepared_stage(
            stage,
            file_documents,
            manifest_raw,
            (apk_document, result_document),
        )
        _assert_validated_input_identities(plan, result_file)
        _publish_stage(
            stage,
            output,
            stage_identity,
            lambda published: _assert_prepared_stage(
                published,
                file_documents,
                manifest_raw,
                (apk_document, result_document),
            ),
        )
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
        _publish_stage(
            stage,
            output,
            stage_identity,
            lambda published: _assert_reconstructed_stage(
                published,
                files,
            ),
        )
        stage = None
        stage_identity = None
        return output
    finally:
        _cleanup_owned_stage(stage, stage_identity)


def _download_authority_asset(
    *,
    manifest_url: str,
    identity: Mapping[str, object],
    destination: Path,
    allow_loopback_http: bool,
) -> installer.InstallFile:
    asset = installer._exact_string(
        identity.get("asset"), "release authority asset"
    )
    expected_bytes = _exact_positive_integer(
        identity.get("bytes"), f"release authority {asset} bytes"
    )
    expected_sha256 = installer._exact_sha256(
        identity.get("sha256"), f"release authority {asset} SHA-256"
    )
    digest = hashlib.sha256()
    try:
        with destination.open("xb") as output_handle:
            _stream_downloaded_chunk(
                url=_asset_url(manifest_url, asset),
                output_handle=output_handle,
                expected_bytes=expected_bytes,
                expected_sha256=expected_sha256,
                file_digest=digest,
                allow_loopback_http=allow_loopback_http,
            )
            output_handle.flush()
            os.fsync(output_handle.fileno())
    except ReferencePackageInstallError:
        raise
    except OSError as error:
        raise ReferencePackageInstallError(
            f"release authority {asset} cannot be downloaded"
        ) from error
    downloaded = installer._hash_regular_file(
        destination, f"downloaded release authority {asset}"
    )
    if (
        downloaded.byte_length != expected_bytes
        or downloaded.sha256 != expected_sha256
        or digest.hexdigest() != expected_sha256
    ):
        raise ReferencePackageInstallError(
            f"downloaded release authority {asset} differs"
        )
    return downloaded


_MISSING_JSON_VALUE = object()


def _is_absolute_local_reference(value: str) -> bool:
    parsed = urllib.parse.urlsplit(value)
    return (
        parsed.scheme.lower() == "file"
        or PurePosixPath(value).is_absolute()
        or PureWindowsPath(value).is_absolute()
    )


def _assert_no_undocumented_local_paths(
    value: object,
    *,
    allowed_paths: frozenset[tuple[object, ...]],
    location: tuple[object, ...] = (),
) -> None:
    if isinstance(value, Mapping):
        for key, child in value.items():
            child_location = (*location, key)
            if (
                isinstance(key, str)
                and _is_absolute_local_reference(key)
            ):
                raise ReferencePackageInstallError(
                    "release authority contains an undocumented local path"
                )
            _assert_no_undocumented_local_paths(
                child,
                allowed_paths=allowed_paths,
                location=child_location,
            )
        return
    if isinstance(value, list):
        for index, child in enumerate(value):
            _assert_no_undocumented_local_paths(
                child,
                allowed_paths=allowed_paths,
                location=(*location, index),
            )
        return
    if (
        isinstance(value, str)
        and location not in allowed_paths
        and _is_absolute_local_reference(value)
    ):
        raise ReferencePackageInstallError(
            "release authority contains an undocumented local path"
        )


def _changed_json_paths(
    before: object,
    after: object,
    location: tuple[object, ...] = (),
) -> set[tuple[object, ...]]:
    if isinstance(before, Mapping) and isinstance(after, Mapping):
        changed: set[tuple[object, ...]] = set()
        for key in before.keys() | after.keys():
            if key not in before or key not in after:
                changed.add((*location, key))
            else:
                changed.update(
                    _changed_json_paths(
                        before[key], after[key], (*location, key)
                    )
                )
        return changed
    if isinstance(before, list) and isinstance(after, list):
        if len(before) != len(after):
            return {location}
        changed = set()
        for index, (before_item, after_item) in enumerate(zip(before, after)):
            changed.update(
                _changed_json_paths(
                    before_item, after_item, (*location, index)
                )
            )
        return changed
    return set() if before == after else {location}


def _materialized_result_bytes(
    *,
    template_path: Path,
    package_directory: Path,
    package_bytes: int,
    apk_path: Path,
    apk: installer.InstallFile,
    install_policy: str,
    source_commit: str,
) -> bytes:
    if _source_commit_from_final_result(template_path) != source_commit:
        raise ReferencePackageInstallError(
            "release authority source commit differs from its manifest"
        )
    template = installer._read_strict_json(
        template_path, "release authority final result", canonical=False
    )
    allowed_local_paths = frozenset({("apk", "path"), ("package", "path")})
    _assert_no_undocumented_local_paths(
        template,
        allowed_paths=allowed_local_paths,
    )
    result = copy.deepcopy(template)
    package = installer._exact_mapping(
        result.get("package"), "release authority package"
    )
    if (
        installer._exact_string(
            package.get("packageId"), "release authority package ID"
        )
        != installer.PACKAGE_ID
        or installer._exact_integer(
            package.get("bytes"), "release authority package bytes"
        )
        != package_bytes
    ):
        raise ReferencePackageInstallError(
            "release authority package identity differs"
        )
    apk_document = installer._exact_mapping(
        result.get("apk"), "release authority APK"
    )
    if (
        installer._exact_integer(
            apk_document.get("bytes"), "release authority APK bytes"
        )
        != apk.byte_length
        or installer._exact_sha256(
            apk_document.get("sha256"), "release authority APK SHA-256"
        )
        != apk.sha256
    ):
        raise ReferencePackageInstallError(
            "release authority APK identity differs"
        )
    rebind = installer._exact_mapping(
        result.get("rebind"), "release authority rebind"
    )
    if (
        installer._exact_string(
            rebind.get("sourceCommit"), "release authority rebind commit"
        )
        != source_commit
    ):
        raise ReferencePackageInstallError(
            "release authority rebind source commit differs"
        )
    bound_policy = rebind.get("installPolicy")
    if bound_policy is not None and (
        installer._validated_install_policy(bound_policy) != install_policy
    ):
        raise ReferencePackageInstallError(
            "release authority install policy differs from its manifest"
        )
    package_path = str(package_directory.resolve(strict=True))
    apk_local_path = str(apk_path.resolve(strict=False))
    prior_package_path = package.get("path")
    prior_apk_path = apk_document.get("path")
    prior_policy = rebind.get("installPolicy", _MISSING_JSON_VALUE)
    package["path"] = package_path
    apk_document["path"] = apk_local_path
    rebind["installPolicy"] = install_policy
    expected_delta = {
        path
        for path, before, after in (
            (("package", "path"), prior_package_path, package_path),
            (("apk", "path"), prior_apk_path, apk_local_path),
            (("rebind", "installPolicy"), prior_policy, install_policy),
        )
        if before != after
    }
    if _changed_json_paths(template, result) != expected_delta:
        raise ReferencePackageInstallError(
            "materialized authority changed undocumented result fields"
        )
    _assert_no_undocumented_local_paths(
        result,
        allowed_paths=allowed_local_paths,
    )
    return installer._canonical_json_bytes(result)


def _assert_materialized_authority(
    *,
    authority_directory: Path,
    package_directory: Path,
    manifest: Mapping[str, object],
) -> None:
    try:
        actual_names = tuple(
            sorted(path.name for path in authority_directory.iterdir())
        )
    except OSError as error:
        raise ReferencePackageInstallError(
            "materialized authority inventory cannot be inspected"
        ) from error
    if actual_names != (
        RELEASE_APK_ASSET_NAME,
        MATERIALIZED_FINAL_RESULT_NAME,
    ):
        raise ReferencePackageInstallError(
            "materialized authority inventory differs"
        )
    apk_identity = installer._exact_mapping(
        manifest.get("apk"), "release manifest APK"
    )
    apk = installer._hash_regular_file(
        authority_directory / RELEASE_APK_ASSET_NAME,
        "materialized authority APK",
    )
    if (
        apk.byte_length
        != _exact_positive_integer(
            apk_identity.get("bytes"), "release manifest APK bytes"
        )
        or apk.sha256
        != installer._exact_sha256(
            apk_identity.get("sha256"), "release manifest APK SHA-256"
        )
    ):
        raise ReferencePackageInstallError(
            "materialized authority APK differs from its manifest"
        )
    policy = installer._validated_install_policy(manifest.get("installPolicy"))
    plan = HostInstallPlan.validate(
        package_directory=package_directory,
        apk_path=apk.path,
        final_result_path=(
            authority_directory / MATERIALIZED_FINAL_RESULT_NAME
        ),
        install_policy=policy,
        require_install_policy_binding=(
            policy != installer.INSTALL_POLICY_RELEASE
        ),
    )
    if plan.apk_sha256 != apk.sha256:
        raise ReferencePackageInstallError(
            "materialized authority host plan differs"
        )


def materialize_release_authority(
    *,
    manifest_url: str,
    package_directory: Path,
    output_directory: Path,
    allow_loopback_http: bool = False,
) -> Path:
    """Download exact release authority and bind it to one local package root."""

    if type(allow_loopback_http) is not bool:
        raise ReferencePackageInstallError(
            "loopback HTTP test seam has the wrong exact type"
        )
    package = installer._real_directory(
        Path(package_directory), "reference package"
    )
    output = _fresh_directory_target(
        Path(output_directory), "materialized authority output"
    )
    manifest, validated_manifest_url = _download_manifest(
        manifest_url,
        allow_loopback_http=allow_loopback_http,
    )
    files = _validated_manifest_files(manifest)
    _assert_reconstructed_stage(package, files)
    policy = installer._validated_install_policy(manifest.get("installPolicy"))
    source_commit = installer._exact_string(
        manifest.get("sourceCommit"), "release manifest source commit"
    )
    package_document = installer._exact_mapping(
        manifest.get("package"), "release manifest package"
    )
    package_bytes = _exact_positive_integer(
        package_document.get("bytes"), "release manifest package bytes"
    )

    stage: Path | None = None
    stage_identity: tuple[int, int] | None = None
    try:
        stage, stage_identity = _create_stage(output, "materialize")
        apk = _download_authority_asset(
            manifest_url=validated_manifest_url,
            identity=installer._exact_mapping(
                manifest.get("apk"), "release manifest APK"
            ),
            destination=stage / RELEASE_APK_ASSET_NAME,
            allow_loopback_http=allow_loopback_http,
        )
        template_path = stage / RELEASE_FINAL_RESULT_ASSET_NAME
        _download_authority_asset(
            manifest_url=validated_manifest_url,
            identity=installer._exact_mapping(
                manifest.get("finalResult"), "release manifest final result"
            ),
            destination=template_path,
            allow_loopback_http=allow_loopback_http,
        )
        stage_result = stage / MATERIALIZED_FINAL_RESULT_NAME
        stage_raw = _materialized_result_bytes(
            template_path=template_path,
            package_directory=package,
            package_bytes=package_bytes,
            apk_path=apk.path,
            apk=apk,
            install_policy=policy,
            source_commit=source_commit,
        )
        _write_exact_file(stage_result, stage_raw)
        HostInstallPlan.validate(
            package_directory=package,
            apk_path=apk.path,
            final_result_path=stage_result,
            install_policy=policy,
            require_install_policy_binding=(
                policy != installer.INSTALL_POLICY_RELEASE
            ),
        )
        template_path.unlink()
        final_apk_path = output / RELEASE_APK_ASSET_NAME
        final_raw = _materialized_result_bytes(
            template_path=installer._real_file(
                stage_result, "staged materialized result"
            ),
            package_directory=package,
            package_bytes=package_bytes,
            apk_path=final_apk_path,
            apk=apk,
            install_policy=policy,
            source_commit=source_commit,
        )
        installer._atomic_write_bytes(stage_result, final_raw)
        _publish_stage(
            stage,
            output,
            stage_identity,
            lambda published: _assert_materialized_authority(
                authority_directory=published,
                package_directory=package,
                manifest=manifest,
            ),
        )
        stage = None
        stage_identity = None
        return output / MATERIALIZED_FINAL_RESULT_NAME
    finally:
        _cleanup_owned_stage(stage, stage_identity)


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Prepare, fetch, or materialize verified Experiment 8 release "
            "assets."
        )
    )
    subparsers = parser.add_subparsers(dest="operation", required=True)
    prepare = subparsers.add_parser("prepare")
    prepare.add_argument("--package", required=True, type=Path)
    prepare.add_argument("--apk", required=True, type=Path)
    prepare.add_argument("--final-result", required=True, type=Path)
    prepare.add_argument("--output", required=True, type=Path)
    prepare.add_argument(
        "--install-policy",
        choices=tuple(sorted(installer.INSTALL_POLICIES)),
        default=installer.INSTALL_POLICY_RELEASE,
    )
    prepare.add_argument(
        "--chunk-bytes",
        type=int,
        default=DEFAULT_CHUNK_BYTES,
    )
    fetch = subparsers.add_parser("fetch")
    fetch.add_argument("--manifest-url", required=True)
    fetch.add_argument("--output", required=True, type=Path)
    materialize = subparsers.add_parser("materialize")
    materialize.add_argument("--manifest-url", required=True)
    materialize.add_argument("--package", required=True, type=Path)
    materialize.add_argument("--output", required=True, type=Path)
    parsed = parser.parse_args(arguments)
    try:
        if parsed.operation == "prepare":
            result = prepare_release_assets(
                package_directory=parsed.package,
                apk_path=parsed.apk,
                final_result_path=parsed.final_result,
                output_directory=parsed.output,
                chunk_bytes=parsed.chunk_bytes,
                install_policy=parsed.install_policy,
            )
        elif parsed.operation == "fetch":
            result = fetch_release_assets(
                manifest_url=parsed.manifest_url,
                output_directory=parsed.output,
            )
        else:
            result = materialize_release_authority(
                manifest_url=parsed.manifest_url,
                package_directory=parsed.package,
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
    "MATERIALIZED_FINAL_RESULT_NAME",
    "RELEASE_APK_ASSET_NAME",
    "RELEASE_FINAL_RESULT_ASSET_NAME",
    "RELEASE_MANIFEST_NAME",
    "RELEASE_MANIFEST_SCHEMA",
    "_main",
    "fetch_release_assets",
    "materialize_release_authority",
    "prepare_release_assets",
]
