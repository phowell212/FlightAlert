from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import stat
import struct
import sys
import tempfile
import unicodedata
import zlib
from collections import Counter
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from .model import TileKey
from .reference_presentation_policy import (
    PRESENTATION_POLICY_SHA256,
    ReferenceClassCatalog,
    SemanticSubtype,
    SubtypeCatalogCounts,
    canonical_class_catalog_bytes,
)
from .reference_size_policy import (
    COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
    DESTINATION_RESERVE_BYTES,
    REFERENCE_SIZE_POLICY_MODES,
    evaluate_reference_size_policy,
    reference_size_policy_document,
)
from .renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    INDEX_FLAG_PRESENT,
    MAX_TILE_BYTES,
    PAYLOAD_SCHEMA,
    SOURCED_TEXT_POLICY_SHA256,
    decode_tile_payload,
    raw_hash32,
)
from .semantic_model import renderer_order_key, renderer_record_bytes, variant_fingerprint
from .sourced_text import UNICODE_SCRIPT_PROFILE_SHA256
from .v3_package_merger import _extract_envelopes_fast


CATALOG_FILE_NAME = "class-catalog.bin"
RECEIPT_FILE_NAME = "class-catalog-finalization-receipt.json"

_RECEIPT_SCHEMA = "flightalert.experiment8.v3-class-catalog-finalization-receipt.v1"
_AUTHORITY_RECEIPT_SCHEMA = (
    "flightalert.experiment8.v3-class-catalog-finalization-receipt.v2"
)
_AUTHORITY_MERGE_SCHEMA = "flightalert.experiment8.v3-package-merge.v2"
_AUTHORITY_MERGE_RECEIPT_SCHEMA = (
    "flightalert.experiment8.v3-package-merge-receipt.v2"
)
_RECEIPT_BOUND_VISUAL_AUTHORITY_SEMANTIC_VERIFICATION = {
    "mode": "receipt-bound-visual-evaluation",
    "runtimeFileDigestsVerifiedByMergeStream": True,
    "strictDocumentaryProofDeferred": True,
}
_MERGE_RECEIPT_FILE_NAME = "merge-receipt.json"
_FINAL_SIZE_ACCOUNTING_SCOPE = (
    "final-six-file-package-after-class-catalog-finalization"
)
_WATERWAY_BUILD_RECEIPT_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-build.v2"
)
_WATERWAY_BUILD_RECEIPT_FIELDS = {
    "admission",
    "attribution",
    "build",
    "catalogCountsClaimed",
    "closureAudit",
    "finalSemanticIdentitySha256",
    "outputFiles",
    "packageId",
    "peakResources",
    "projection",
    "rendererSemanticStreamSha256",
    "rendererTextAudit",
    "schema",
    "source",
}
_AUTHORITY_PRE_FINAL_FILE_NAMES = {
    "manifest.json",
    "merge-receipt.json",
    "records.fadictpack",
    "tile-index.bin",
}
_AUTHORITY_FINAL_FILE_NAMES = {
    *_AUTHORITY_PRE_FINAL_FILE_NAMES,
    CATALOG_FILE_NAME,
    RECEIPT_FILE_NAME,
}
_SEMANTIC_STREAM_DOMAIN = b"flight-alert-exp8-semantic-v1\0"
_MAX_MANIFEST_BYTES = 16 * 1024 * 1024
_FILE_HASH_CHUNK_BYTES = 4 * 1024 * 1024
_ANDROID_MAX_INDEX_BYTES = (1 << 31) - 1
_ANDROID_MAX_RECORD_OFFSET = (1 << 63) - 1
_MAX_COMPRESSED_TILE_BYTES = (
    MAX_TILE_BYTES
    + (MAX_TILE_BYTES >> 12)
    + (MAX_TILE_BYTES >> 14)
    + (MAX_TILE_BYTES >> 25)
    + 13
)
_ZERO_INDEX_ENTRY = b"\0" * INDEX_ENTRY_BYTES
_U64_MAX = (1 << 64) - 1
_REPARSE_POINT_ATTRIBUTE = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
_WINDOWS_GENERIC_READ = 0x80000000
_WINDOWS_DELETE_ACCESS = 0x00010000
_WINDOWS_SYNCHRONIZE_ACCESS = 0x00100000
_WINDOWS_FILE_READ_ATTRIBUTES = 0x00000080
_WINDOWS_FILE_SHARE_READ = 0x00000001
_WINDOWS_FILE_SHARE_DELETE = 0x00000004
_WINDOWS_OPEN_EXISTING = 3
_WINDOWS_FILE_ATTRIBUTE_NORMAL = 0x00000080
_WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
_WINDOWS_FILE_RENAME_INFO_EX_CLASS = 22
_WINDOWS_FILE_RENAME_REPLACE_IF_EXISTS = 0x00000001
_WINDOWS_FILE_RENAME_POSIX_SEMANTICS = 0x00000002


class V3ClassCatalogFinalizationError(ValueError):
    """A V3 package cannot publish a truthful Experiment 8 class catalog."""


@dataclass(frozen=True, order=True, slots=True)
class _Window:
    z: int
    x_min: int
    x_max: int
    y_min: int
    y_max: int

    @property
    def tile_count(self) -> int:
        return (self.x_max - self.x_min + 1) * (self.y_max - self.y_min + 1)

    def ordinal(self, tile: TileKey) -> int:
        width = self.x_max - self.x_min + 1
        return (tile.y - self.y_min) * width + tile.x - self.x_min


@dataclass(frozen=True, slots=True)
class _Range:
    window: _Window
    first_ordinal: int


@dataclass(frozen=True, slots=True)
class _FileBinding:
    path: Path
    byte_length: int
    sha256: str
    device: int
    inode: int


@dataclass(slots=True)
class _OwnedStagedFile:
    path: Path
    identity: tuple[int, int, int, int]
    data: bytes


@dataclass(slots=True)
class _RetainedPublication:
    path: Path
    handle: BinaryIO
    identity: tuple[int, int, int, int]
    data: bytes
    committed: bool = False


@dataclass(frozen=True, slots=True)
class _Package:
    directory: Path
    package_id: str
    manifest: dict[str, object]
    manifest_raw: bytes
    base_manifest_bytes: bytes
    renderer_semantic_stream_sha256: str
    ranges: tuple[_Range, ...]
    tile_count: int
    complete_declared_scope: bool
    manifest_file: _FileBinding
    records_file: _FileBinding
    index_file: _FileBinding
    existing_catalog_sha256: str | None
    merge_receipt_file: _FileBinding | None
    authority_merge_receipt: Mapping[str, object] | None
    authority_receipts: tuple[Mapping[str, object], ...]
    authority_size_policy: Mapping[str, object] | None


@dataclass(frozen=True, slots=True)
class _Audit:
    semantic_sha256: str
    subtype_counts: Mapping[SemanticSubtype, SubtypeCatalogCounts]
    present_tile_count: int
    missing_tile_count: int
    renderer_record_count: int


@dataclass(frozen=True, slots=True)
class FinalizationResult:
    package_directory: Path
    catalog_sha256: str
    manifest_sha256: str
    receipt: Mapping[str, object]


def _canonical_json_bytes(document: object) -> bytes:
    try:
        return (
            json.dumps(
                document,
                allow_nan=False,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        ).encode("utf-8", "strict")
    except (TypeError, UnicodeError, ValueError) as error:
        raise V3ClassCatalogFinalizationError(
            "canonical JSON value is unsupported"
        ) from error


def _reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise V3ClassCatalogFinalizationError(
                f"JSON contains duplicate key {key!r}"
            )
        result[key] = value
    return result


def _reject_json_constant(value: str) -> object:
    raise V3ClassCatalogFinalizationError(
        f"JSON contains forbidden numeric constant {value}"
    )


def _strict_json(raw: bytes, label: str) -> dict[str, object]:
    if type(raw) is not bytes:
        raise V3ClassCatalogFinalizationError(f"{label} must be immutable bytes")
    try:
        document = json.loads(
            raw.decode("utf-8", "strict"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except V3ClassCatalogFinalizationError:
        raise
    except (UnicodeError, json.JSONDecodeError) as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} is not strict UTF-8 JSON"
        ) from error
    if type(document) is not dict:
        raise V3ClassCatalogFinalizationError(f"{label} root must be an object")
    return document


def _exact_int(value: object, label: str, minimum: int, maximum: int) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise V3ClassCatalogFinalizationError(
            f"{label} must be an exact integer in [{minimum}, {maximum}]"
        )
    return value


def _exact_bool(value: object, label: str) -> bool:
    if type(value) is not bool:
        raise V3ClassCatalogFinalizationError(f"{label} must be Boolean")
    return value


def _exact_text(value: object, label: str) -> str:
    if type(value) is not str or not value:
        raise V3ClassCatalogFinalizationError(f"{label} must be nonempty text")
    if unicodedata.normalize("NFC", value) != value:
        raise V3ClassCatalogFinalizationError(f"{label} must be NFC")
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        raise V3ClassCatalogFinalizationError(
            f"{label} contains invalid Unicode"
        )
    return value


def _sha256_text(value: object, label: str) -> str:
    if (
        type(value) is not str
        or len(value) != 64
        or any(character not in "0123456789abcdef" for character in value)
    ):
        raise V3ClassCatalogFinalizationError(
            f"{label} must be one lowercase SHA-256"
        )
    return value


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while True:
                chunk = handle.read(_FILE_HASH_CHUNK_BYTES)
                if not chunk:
                    break
                digest.update(chunk)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"cannot hash {path.name}: {error}"
        ) from error
    return digest.hexdigest()


def _file_binding(path: Path) -> _FileBinding:
    try:
        status = path.stat()
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"cannot inspect {path.name}: {error}"
        ) from error
    return _FileBinding(
        path=path,
        byte_length=status.st_size,
        sha256=_sha256_file(path),
        device=status.st_dev,
        inode=status.st_ino,
    )


def _validate_package_id(value: object) -> str:
    package_id = _exact_text(value, "V3 package ID")
    if package_id in {".", ".."} or any(
        character in package_id for character in ("/", "\\", "\0")
    ):
        raise V3ClassCatalogFinalizationError("V3 package ID is path-unsafe")
    return package_id


def _validate_declared_runtime_binding(
    manifest: Mapping[str, object],
    records: _FileBinding,
    index: _FileBinding,
) -> None:
    merge = manifest.get("merge")
    if merge is None:
        return
    if type(merge) is not dict:
        raise V3ClassCatalogFinalizationError("V3 merge metadata must be an object")
    output = merge.get("output")
    if type(output) is not dict:
        raise V3ClassCatalogFinalizationError(
            "V3 merge output binding must be an object"
        )
    declarations = (
        ("recordsBytes", records.byte_length, "V3 declared records byte count"),
        ("tileIndexBytes", index.byte_length, "V3 declared index byte count"),
    )
    for key, actual, label in declarations:
        if _exact_int(output.get(key), label, 0, _ANDROID_MAX_RECORD_OFFSET) != actual:
            raise V3ClassCatalogFinalizationError(f"{label} differs from its file")
    hashes = (
        ("recordsSha256", records.sha256, "V3 declared records SHA-256"),
        ("tileIndexSha256", index.sha256, "V3 declared index SHA-256"),
    )
    for key, actual, label in hashes:
        if _sha256_text(output.get(key), label) != actual:
            raise V3ClassCatalogFinalizationError(f"{label} differs from its file")


def _exact_mapping(value: object, label: str) -> dict[str, object]:
    if type(value) is not dict:
        raise V3ClassCatalogFinalizationError(f"{label} must be an object")
    return value


def _plain_json(value: object) -> object:
    if isinstance(value, Mapping):
        return {key: _plain_json(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain_json(item) for item in value]
    return value


def _validate_size_policy_binding(
    value: object,
    label: str,
) -> dict[str, object]:
    binding = _exact_mapping(value, label)
    if set(binding) != {"document", "documentSha256", "mode", "module", "schema"}:
        raise V3ClassCatalogFinalizationError(f"{label} fields differ")
    if binding.get("schema") != (
        "flightalert.experiment8.reference-size-policy-binding.v1"
    ):
        raise V3ClassCatalogFinalizationError(f"{label} schema differs")
    document = _exact_mapping(binding.get("document"), f"{label} document")
    expected_document = _plain_json(reference_size_policy_document())
    if document != expected_document:
        raise V3ClassCatalogFinalizationError(f"{label} document differs")
    if _sha256_text(
        binding.get("documentSha256"), f"{label} document SHA-256"
    ) != hashlib.sha256(_canonical_json_bytes(document)).hexdigest():
        raise V3ClassCatalogFinalizationError(f"{label} document hash differs")
    mode = _exact_text(binding.get("mode"), f"{label} mode")
    if mode not in REFERENCE_SIZE_POLICY_MODES:
        raise V3ClassCatalogFinalizationError(f"{label} mode differs")
    module = _exact_mapping(binding.get("module"), f"{label} module")
    if set(module) != {"bytes", "sha256"}:
        raise V3ClassCatalogFinalizationError(f"{label} module fields differ")
    if _exact_int(
        module.get("bytes"), f"{label} module bytes", 1, _ANDROID_MAX_RECORD_OFFSET
    ) <= 0:
        raise V3ClassCatalogFinalizationError(f"{label} module is empty")
    _sha256_text(
        module.get("sha256"), f"{label} module SHA-256"
    )
    return binding


def _validated_size_decision(
    value: object,
    *,
    binding: Mapping[str, object],
    required_package_bytes: int,
    label: str,
) -> dict[str, object]:
    decision = _exact_mapping(value, label)
    mode = binding["mode"]
    fields = {
        "authorized",
        "availableDestinationBytes",
        "hardComponentPackageCeilingExceeded",
        "hardMandatoryPhoneFootprintCeilingExceeded",
        "mandatoryPhoneFootprintBytes",
        "mode",
        "preferredComponentPackageCeilingExceeded",
        "preferredMandatoryPhoneFootprintCeilingExceeded",
        "requiredPackageBytes",
        "requiredWithReserveBytes",
        "schema",
    }
    visual = mode == COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    if visual:
        fields.update(
            {
                "publicationBoundaryAuthorized",
                "publicationBoundaryDestinationFreeBytes",
                "publicationBoundaryRequiredReserveBytes",
            }
        )
    if set(decision) != fields:
        raise V3ClassCatalogFinalizationError(f"{label} fields differ")
    available = decision.get("availableDestinationBytes")
    if visual:
        available = _exact_int(
            available,
            f"{label} available destination bytes",
            0,
            _ANDROID_MAX_RECORD_OFFSET,
        )
    elif available is not None:
        raise V3ClassCatalogFinalizationError(
            f"{label} budgeted destination capacity must be null"
        )
    expected = dict(
        evaluate_reference_size_policy(
            mode=mode,
            required_package_bytes=required_package_bytes,
            available_destination_bytes=available,
        )
    )
    if visual:
        boundary = _exact_int(
            decision.get("publicationBoundaryDestinationFreeBytes"),
            f"{label} publication boundary bytes",
            0,
            _ANDROID_MAX_RECORD_OFFSET,
        )
        if _exact_int(
            decision.get("publicationBoundaryRequiredReserveBytes"),
            f"{label} publication boundary reserve",
            0,
            _ANDROID_MAX_RECORD_OFFSET,
        ) != DESTINATION_RESERVE_BYTES:
            raise V3ClassCatalogFinalizationError(
                f"{label} publication boundary reserve differs"
            )
        boundary_authorized = boundary >= DESTINATION_RESERVE_BYTES
        if _exact_bool(
            decision.get("publicationBoundaryAuthorized"),
            f"{label} publication authorization",
        ) != boundary_authorized:
            raise V3ClassCatalogFinalizationError(
                f"{label} publication authorization differs"
            )
        expected["publicationBoundaryAuthorized"] = boundary_authorized
        expected["publicationBoundaryDestinationFreeBytes"] = boundary
        expected["publicationBoundaryRequiredReserveBytes"] = (
            DESTINATION_RESERVE_BYTES
        )
        expected["authorized"] = bool(expected["authorized"]) and boundary_authorized
    if decision != expected:
        raise V3ClassCatalogFinalizationError(f"{label} accounting differs")
    if decision["authorized"] is not True:
        raise V3ClassCatalogFinalizationError(f"{label} is not authorized")
    return decision


def _binding_map(
    value: object,
    *,
    label: str,
    expected_names: tuple[str, ...],
) -> dict[str, tuple[int, str]]:
    if type(value) is not list or len(value) != len(expected_names):
        raise V3ClassCatalogFinalizationError(f"{label} inventory differs")
    result: dict[str, tuple[int, str]] = {}
    for index, item in enumerate(value):
        binding = _exact_mapping(item, f"{label}[{index}]")
        if set(binding) != {"bytes", "name", "sha256"}:
            raise V3ClassCatalogFinalizationError(f"{label}[{index}] fields differ")
        name = _exact_text(binding.get("name"), f"{label}[{index}] name")
        if name in result:
            raise V3ClassCatalogFinalizationError(f"{label} repeats {name}")
        result[name] = (
            _exact_int(
                binding.get("bytes"),
                f"{label}[{index}] bytes",
                0,
                _ANDROID_MAX_RECORD_OFFSET,
            ),
            _sha256_text(binding.get("sha256"), f"{label}[{index}] SHA-256"),
        )
    if tuple(result) != expected_names:
        raise V3ClassCatalogFinalizationError(f"{label} inventory differs")
    return result


def _supplement_input_bindings(
    value: object,
) -> dict[str, dict[str, tuple[int, str]]]:
    if type(value) is not list or not value:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge inputs have the wrong exact type"
        )
    expected_fields = {
        "manifestBytes",
        "manifestSha256",
        "packageId",
        "recordsBytes",
        "recordsSha256",
        "role",
        "tileIndexBytes",
        "tileIndexSha256",
    }
    supplement_bindings: dict[str, dict[str, tuple[int, str]]] = {}
    for index, item in enumerate(value):
        binding = _exact_mapping(item, f"V3 authority merge inputs[{index}]")
        if set(binding) != expected_fields:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge input fields differ"
            )
        role = _exact_text(
            binding.get("role"), f"V3 authority merge inputs[{index}] role"
        )
        package_id = _validate_package_id(binding.get("packageId"))
        byte_facts = {
            field: _exact_int(
                binding.get(field),
                f"V3 authority merge inputs[{index}] {label}",
                0,
                _ANDROID_MAX_RECORD_OFFSET,
            )
            for field, label in (
                ("manifestBytes", "manifest bytes"),
                ("recordsBytes", "records bytes"),
                ("tileIndexBytes", "index bytes"),
            )
        }
        sha_facts = {
            field: _sha256_text(
                binding.get(field),
                f"V3 authority merge inputs[{index}] {label}",
            )
            for field, label in (
                ("manifestSha256", "manifest SHA-256"),
                ("recordsSha256", "records SHA-256"),
                ("tileIndexSha256", "index SHA-256"),
            )
        }
        if index == 0:
            if role != "primary":
                raise V3ClassCatalogFinalizationError(
                    "V3 authority merge input roles are not canonical"
                )
        elif role != "supplement":
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge input roles are not canonical"
            )
        else:
            if package_id in supplement_bindings:
                raise V3ClassCatalogFinalizationError(
                    "V3 authority merge supplement package IDs are ambiguous"
                )
            supplement_bindings[package_id] = {
                "manifest.json": (
                    byte_facts["manifestBytes"],
                    sha_facts["manifestSha256"],
                ),
                "records.fadictpack": (
                    byte_facts["recordsBytes"],
                    sha_facts["recordsSha256"],
                ),
                "tile-index.bin": (
                    byte_facts["tileIndexBytes"],
                    sha_facts["tileIndexSha256"],
                ),
            }
    return supplement_bindings


def _package_entry_names(directory: Path) -> set[str]:
    try:
        return {entry.name for entry in directory.iterdir()}
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "V3 authority exact package inventory is unreadable"
        ) from error


def _assert_authority_preflight_inventory(directory: Path) -> None:
    actual = _package_entry_names(directory)
    allowed = (
        _AUTHORITY_PRE_FINAL_FILE_NAMES,
        {*_AUTHORITY_PRE_FINAL_FILE_NAMES, CATALOG_FILE_NAME},
        _AUTHORITY_FINAL_FILE_NAMES,
    )
    if not any(actual == expected for expected in allowed):
        raise V3ClassCatalogFinalizationError(
            "V3 authority exact package inventory differs"
        )


def _assert_authority_final_inventory(directory: Path) -> None:
    if _package_entry_names(directory) != _AUTHORITY_FINAL_FILE_NAMES:
        raise V3ClassCatalogFinalizationError(
            "V3 authority exact final six-file package inventory differs"
        )


def _assert_authority_staged_receipt_inventory(
    directory: Path,
    stage: _OwnedStagedFile,
) -> None:
    actual = _package_entry_names(directory)
    if stage.path.name not in actual:
        raise V3ClassCatalogFinalizationError(
            "V3 authority staged receipt is absent from package inventory"
        )
    without_stage = actual - {stage.path.name}
    before_receipt = {*_AUTHORITY_PRE_FINAL_FILE_NAMES, CATALOG_FILE_NAME}
    if without_stage not in (before_receipt, _AUTHORITY_FINAL_FILE_NAMES):
        raise V3ClassCatalogFinalizationError(
            "V3 authority exact staged package inventory differs"
        )


def _load_authority_merge_receipt(
    *,
    directory: Path,
    manifest: Mapping[str, object],
    base_manifest_bytes: bytes,
    package_id: str,
    renderer_semantic_stream_sha256: str,
    records_file: _FileBinding,
    index_file: _FileBinding,
) -> tuple[
    _FileBinding | None,
    Mapping[str, object] | None,
    tuple[Mapping[str, object], ...],
    Mapping[str, object] | None,
]:
    merge = manifest.get("merge")
    if type(merge) is not dict or merge.get("schema") != _AUTHORITY_MERGE_SCHEMA:
        return None, None, (), None
    expected_merge_fields = {
        "authorityReceipts",
        "inputs",
        "mergerSha256",
        "output",
        "schema",
        "sizePolicy",
    }
    if "authoritySemanticVerification" in merge:
        if (
            merge.get("authoritySemanticVerification")
            != _RECEIPT_BOUND_VISUAL_AUTHORITY_SEMANTIC_VERIFICATION
        ):
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge semantic verification differs"
            )
        expected_merge_fields.add("authoritySemanticVerification")
    if set(merge) != expected_merge_fields:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge manifest fields differ"
        )
    receipt_path = directory / _MERGE_RECEIPT_FILE_NAME
    if not receipt_path.is_file() or receipt_path.is_symlink():
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt is missing or aliased"
        )
    receipt_file = _file_binding(receipt_path)
    if not 0 < receipt_file.byte_length <= _MAX_MANIFEST_BYTES:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt byte length is outside its bound"
        )
    try:
        with receipt_path.open("rb") as handle:
            receipt_raw = handle.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt is unreadable"
        ) from error
    if len(receipt_raw) != receipt_file.byte_length:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt changed while being read"
        )
    receipt = _strict_json(receipt_raw, "V3 authority merge receipt")
    if _canonical_json_bytes(receipt) != receipt_raw:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt is not canonical JSON"
        )
    expected_receipt_fields = {
        "authorityReceipts",
        "coverage",
        "inputs",
        "mergerSha256",
        "outputFiles",
        "packageId",
        "rendererSemanticStreamSha256",
        "schema",
        "sizePolicy",
        "subtypeCounts",
    }
    if "accountingConvergencePadding" in receipt:
        padding = receipt["accountingConvergencePadding"]
        if type(padding) is not str or len(padding) > 63 or set(padding) - {"x"}:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge accounting padding is malformed"
            )
        expected_receipt_fields.add("accountingConvergencePadding")
    if set(receipt) != expected_receipt_fields:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt fields differ"
        )
    if receipt.get("schema") != _AUTHORITY_MERGE_RECEIPT_SCHEMA:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt schema differs"
        )
    if _validate_package_id(receipt.get("packageId")) != package_id:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt package ID differs"
        )
    for key, label in (
        ("inputs", "inputs"),
        ("mergerSha256", "merger SHA-256"),
        ("authorityReceipts", "authority receipts"),
        ("sizePolicy", "size policy"),
    ):
        if receipt.get(key) != merge.get(key):
            raise V3ClassCatalogFinalizationError(
                f"V3 authority merge {label} differ"
            )
    if _sha256_text(
        receipt.get("rendererSemanticStreamSha256"),
        "V3 authority merge renderer semantic stream SHA-256",
    ) != renderer_semantic_stream_sha256:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge semantic stream differs"
        )
    authority_value = receipt.get("authorityReceipts")
    if type(authority_value) is not list or not authority_value:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge authority receipts are empty"
        )
    supplement_bindings = _supplement_input_bindings(
        receipt.get("inputs")
    )
    authority_receipts: list[Mapping[str, object]] = []
    package_ids: set[str] = set()
    for index, item in enumerate(authority_value):
        authority = _exact_mapping(
            item, f"V3 authority merge authorityReceipts[{index}]"
        )
        if set(authority) != {"bytes", "document", "packageId", "role", "sha256"}:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt fields differ"
            )
        authority_package_id = _validate_package_id(authority.get("packageId"))
        if authority_package_id in package_ids:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt package IDs repeat"
            )
        package_ids.add(authority_package_id)
        if authority.get("role") != "supplement":
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt role differs"
            )
        document = _exact_mapping(
            authority.get("document"),
            "V3 authority merge authority receipt document",
        )
        if (
            document.get("schema") != _WATERWAY_BUILD_RECEIPT_SCHEMA
            or set(document) != _WATERWAY_BUILD_RECEIPT_FIELDS
        ):
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt document schema differs"
            )
        if _validate_package_id(document.get("packageId")) != authority_package_id:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt package ID differs"
            )
        if authority_package_id not in supplement_bindings:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt does not bind one "
                "supplement input"
            )
        authority_output_bindings = _binding_map(
            document.get("outputFiles"),
            label="V3 authority merge authority receipt output files",
            expected_names=(
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
            ),
        )
        if authority_output_bindings != supplement_bindings[authority_package_id]:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt output files differ "
                "from supplement input"
            )
        document_bytes = _canonical_json_bytes(document)
        if _exact_int(
            authority.get("bytes"),
            "V3 authority merge authority receipt bytes",
            1,
            _MAX_MANIFEST_BYTES,
        ) != len(document_bytes) or _sha256_text(
            authority.get("sha256"),
            "V3 authority merge authority receipt SHA-256",
        ) != hashlib.sha256(document_bytes).hexdigest():
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge authority receipt byte binding differs"
            )
        authority_receipts.append(authority)

    bindings = _binding_map(
        receipt.get("outputFiles"),
        label="V3 authority merge output files",
        expected_names=("manifest.json", "records.fadictpack", "tile-index.bin"),
    )
    expected_bindings = {
        "manifest.json": (
            len(base_manifest_bytes),
            hashlib.sha256(base_manifest_bytes).hexdigest(),
        ),
        "records.fadictpack": (
            records_file.byte_length,
            records_file.sha256,
        ),
        "tile-index.bin": (index_file.byte_length, index_file.sha256),
    }
    if bindings != expected_bindings:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge output files differ"
        )
    coverage = _exact_mapping(receipt.get("coverage"), "V3 authority merge coverage")
    manifest_coverage = _exact_mapping(manifest.get("coverage"), "V3 coverage")
    for key in (
        "completeDeclaredScope",
        "completeWholeEarthDictionary",
        "tileCount",
        "zoomRanges",
    ):
        if coverage.get(key) != manifest_coverage.get(key):
            raise V3ClassCatalogFinalizationError(
                f"V3 authority merge coverage differs for {key}"
            )
    size_policy = _exact_mapping(receipt.get("sizePolicy"), "V3 authority size policy")
    if set(size_policy) != {"accountingScope", "binding", "decision"} or (
        size_policy.get("accountingScope")
        != "merge-output-before-class-catalog-finalization"
    ):
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge size-policy scope differs"
        )
    binding = _validate_size_policy_binding(
        size_policy.get("binding"), "V3 authority size-policy binding"
    )
    _validated_size_decision(
        size_policy.get("decision"),
        binding=binding,
        required_package_bytes=(
            len(base_manifest_bytes)
            + records_file.byte_length
            + index_file.byte_length
            + receipt_file.byte_length
        ),
        label="V3 authority merge size-policy decision",
    )
    if _file_binding(receipt_path) != receipt_file:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge receipt changed while being authenticated"
        )
    return receipt_file, receipt, tuple(authority_receipts), size_policy


def _load_package(directory: Path) -> _Package:
    if not isinstance(directory, Path):
        raise V3ClassCatalogFinalizationError(
            "V3 package directory must be a pathlib.Path"
        )
    try:
        directory = directory.resolve(strict=True)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "V3 package directory is not readable"
        ) from error
    if not directory.is_dir():
        raise V3ClassCatalogFinalizationError("V3 package directory is not readable")
    manifest_path = directory / "manifest.json"
    records_path = directory / "records.fadictpack"
    index_path = directory / "tile-index.bin"
    if not all(path.is_file() for path in (manifest_path, records_path, index_path)):
        raise V3ClassCatalogFinalizationError(
            "V3 package lacks its three runtime files"
        )
    if any(path.is_symlink() for path in (manifest_path, records_path, index_path)):
        raise V3ClassCatalogFinalizationError("V3 runtime file aliases are unsupported")
    try:
        manifest_size = manifest_path.stat().st_size
        if not 0 < manifest_size <= _MAX_MANIFEST_BYTES:
            raise V3ClassCatalogFinalizationError(
                "V3 manifest byte length is outside its bound"
            )
        with manifest_path.open("rb") as handle:
            manifest_raw = handle.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as error:
        raise V3ClassCatalogFinalizationError("V3 manifest is not readable") from error
    if len(manifest_raw) != manifest_size:
        raise V3ClassCatalogFinalizationError("V3 manifest changed while being read")
    manifest = _strict_json(manifest_raw, "V3 manifest")
    if _canonical_json_bytes(manifest) != manifest_raw:
        raise V3ClassCatalogFinalizationError("V3 manifest is not canonical JSON")
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 3:
        raise V3ClassCatalogFinalizationError(
            "V3 manifest schemaVersion must be exactly 3"
        )
    if manifest.get("payloadSchema") != PAYLOAD_SCHEMA:
        raise V3ClassCatalogFinalizationError("V3 payload schema is unsupported")
    if manifest.get("presentationPolicySha256") != PRESENTATION_POLICY_SHA256:
        raise V3ClassCatalogFinalizationError(
            "V3 presentation policy identity differs"
        )
    if manifest.get("sourcedTextPolicySha256") != SOURCED_TEXT_POLICY_SHA256:
        raise V3ClassCatalogFinalizationError("V3 sourced-text policy identity differs")
    if manifest.get("unicodeScriptProfileSha256") != UNICODE_SCRIPT_PROFILE_SHA256:
        raise V3ClassCatalogFinalizationError(
            "V3 Unicode script profile identity differs"
        )
    if manifest.get("compatibility") != {"emptyPresentTilesSharePayload": False}:
        raise V3ClassCatalogFinalizationError(
            "V3 compatibility contract is unsupported"
        )
    package_id = _validate_package_id(manifest.get("packageId"))
    renderer_semantic = _sha256_text(
        manifest.get("rendererSemanticStreamSha256"),
        "V3 renderer semantic stream SHA-256",
    )

    coverage = manifest.get("coverage")
    if type(coverage) is not dict:
        raise V3ClassCatalogFinalizationError("V3 coverage must be an object")
    complete_declared = _exact_bool(
        coverage.get("completeDeclaredScope"), "V3 complete-declared-scope claim"
    )
    complete_whole = _exact_bool(
        coverage.get("completeWholeEarthDictionary"),
        "V3 complete-whole-earth claim",
    )
    raw_ranges = coverage.get("zoomRanges")
    if type(raw_ranges) is not list or not raw_ranges:
        raise V3ClassCatalogFinalizationError(
            "V3 zoom ranges must be a nonempty array"
        )
    ranges: list[_Range] = []
    first_ordinal = 0
    previous_zoom = -1
    for range_number, raw_range in enumerate(raw_ranges):
        if type(raw_range) is not dict:
            raise V3ClassCatalogFinalizationError(
                f"V3 zoom range {range_number} must be an object"
            )
        z = _exact_int(raw_range.get("z"), "V3 range zoom", 0, 29)
        if z <= previous_zoom:
            raise V3ClassCatalogFinalizationError(
                "V3 zoom ranges are not strictly ordered"
            )
        previous_zoom = z
        limit = (1 << z) - 1
        window = _Window(
            z=z,
            x_min=_exact_int(raw_range.get("xMin"), "V3 range xMin", 0, limit),
            x_max=_exact_int(raw_range.get("xMax"), "V3 range xMax", 0, limit),
            y_min=_exact_int(raw_range.get("yMin"), "V3 range yMin", 0, limit),
            y_max=_exact_int(raw_range.get("yMax"), "V3 range yMax", 0, limit),
        )
        if window.x_min > window.x_max or window.y_min > window.y_max:
            raise V3ClassCatalogFinalizationError("V3 range bounds are reversed")
        if raw_range.get("tileCount") != window.tile_count:
            raise V3ClassCatalogFinalizationError(
                "V3 range tileCount is inconsistent"
            )
        ranges.append(_Range(window, first_ordinal))
        first_ordinal += window.tile_count
        if first_ordinal > _ANDROID_MAX_RECORD_OFFSET:
            raise V3ClassCatalogFinalizationError("V3 coverage tile count is too large")
    tile_count = _exact_int(
        coverage.get("tileCount"),
        "V3 coverage tileCount",
        1,
        _ANDROID_MAX_RECORD_OFFSET,
    )
    if tile_count != first_ordinal:
        raise V3ClassCatalogFinalizationError(
            "V3 coverage tileCount differs from its ranges"
        )
    if complete_whole and (
        not complete_declared
        or any(
            item.window.x_min != 0
            or item.window.y_min != 0
            or item.window.x_max != (1 << item.window.z) - 1
            or item.window.y_max != (1 << item.window.z) - 1
            for item in ranges
        )
    ):
        raise V3ClassCatalogFinalizationError(
            "V3 whole-earth claim is not full-world"
        )

    records_file = _file_binding(records_path)
    index_file = _file_binding(index_path)
    expected_index_bytes = tile_count * INDEX_ENTRY_BYTES
    if expected_index_bytes > _ANDROID_MAX_INDEX_BYTES:
        raise V3ClassCatalogFinalizationError(
            "V3 Android index exceeds its byte-array bound"
        )
    if index_file.byte_length != expected_index_bytes:
        raise V3ClassCatalogFinalizationError(
            "V3 binary index length differs from coverage"
        )
    if records_file.byte_length > _ANDROID_MAX_RECORD_OFFSET:
        raise V3ClassCatalogFinalizationError(
            "V3 records pack exceeds its Android offset bound"
        )
    _validate_declared_runtime_binding(manifest, records_file, index_file)

    existing_catalog_sha256 = None
    existing_catalog = manifest.get("classCatalog")
    if existing_catalog is not None:
        if type(existing_catalog) is not dict or set(existing_catalog) != {
            "catalogSha256",
            "rendererContractSha256",
        }:
            raise V3ClassCatalogFinalizationError(
                "V3 classCatalog binding must contain exactly its two fields"
            )
        existing_catalog_sha256 = _sha256_text(
            existing_catalog.get("catalogSha256"),
            "V3 class catalog SHA-256",
        )
        renderer_contract = _sha256_text(
            existing_catalog.get("rendererContractSha256"),
            "V3 renderer contract SHA-256 alias",
        )
        if renderer_contract != renderer_semantic:
            raise V3ClassCatalogFinalizationError(
                "V3 rendererContractSha256 alias differs from "
                "rendererSemanticStreamSha256"
            )

    base_manifest = dict(manifest)
    base_manifest.pop("classCatalog", None)
    base_manifest_bytes = _canonical_json_bytes(base_manifest)
    (
        merge_receipt_file,
        authority_merge_receipt,
        authority_receipts,
        authority_size_policy,
    ) = _load_authority_merge_receipt(
        directory=directory,
        manifest=base_manifest,
        base_manifest_bytes=base_manifest_bytes,
        package_id=package_id,
        renderer_semantic_stream_sha256=renderer_semantic,
        records_file=records_file,
        index_file=index_file,
    )
    if merge_receipt_file is not None:
        _assert_authority_preflight_inventory(directory)
    manifest_file = _file_binding(manifest_path)
    if manifest_file.sha256 != hashlib.sha256(manifest_raw).hexdigest():
        raise V3ClassCatalogFinalizationError("V3 manifest changed while being loaded")
    return _Package(
        directory=directory,
        package_id=package_id,
        manifest=manifest,
        manifest_raw=manifest_raw,
        base_manifest_bytes=base_manifest_bytes,
        renderer_semantic_stream_sha256=renderer_semantic,
        ranges=tuple(ranges),
        tile_count=tile_count,
        complete_declared_scope=complete_declared,
        manifest_file=manifest_file,
        records_file=records_file,
        index_file=index_file,
        existing_catalog_sha256=existing_catalog_sha256,
        merge_receipt_file=merge_receipt_file,
        authority_merge_receipt=authority_merge_receipt,
        authority_receipts=authority_receipts,
        authority_size_policy=authority_size_policy,
    )


def _validate_index(package: _Package) -> tuple[int, int]:
    expected_offset = 0
    present_tiles = 0
    missing_tiles = 0
    digest = hashlib.sha256()
    try:
        with package.index_file.path.open("rb") as handle:
            for ordinal in range(package.tile_count):
                entry = handle.read(INDEX_ENTRY_BYTES)
                if len(entry) != INDEX_ENTRY_BYTES:
                    raise V3ClassCatalogFinalizationError(
                        f"V3 index ended early at ordinal {ordinal}"
                    )
                digest.update(entry)
                if entry == _ZERO_INDEX_ENTRY:
                    missing_tiles += 1
                    continue
                offset, compressed_length, raw_length, _raw_hash, flags = struct.unpack(
                    "<QIIII", entry
                )
                if flags != INDEX_FLAG_PRESENT:
                    raise V3ClassCatalogFinalizationError(
                        f"V3 index flags are unsupported at ordinal {ordinal}"
                    )
                if offset != expected_offset:
                    raise V3ClassCatalogFinalizationError(
                        "V3 record offsets are not exact and contiguous"
                    )
                if not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES:
                    raise V3ClassCatalogFinalizationError(
                        "V3 compressed tile length exceeds its Android bound"
                    )
                if not 0 < raw_length <= MAX_TILE_BYTES:
                    raise V3ClassCatalogFinalizationError(
                        "V3 raw tile length exceeds its Android bound"
                    )
                if offset > _ANDROID_MAX_RECORD_OFFSET - compressed_length:
                    raise V3ClassCatalogFinalizationError(
                        "V3 record range overflows its Android offset bound"
                    )
                expected_offset += compressed_length
                if expected_offset > package.records_file.byte_length:
                    raise V3ClassCatalogFinalizationError(
                        "V3 record range exceeds its pack"
                    )
                present_tiles += 1
            if handle.read(1):
                raise V3ClassCatalogFinalizationError(
                    "V3 index contains trailing bytes"
                )
    except OSError as error:
        raise V3ClassCatalogFinalizationError("V3 index is not readable") from error
    if expected_offset != package.records_file.byte_length:
        raise V3ClassCatalogFinalizationError(
            "V3 records pack has unreferenced or missing bytes"
        )
    if package.complete_declared_scope and missing_tiles:
        raise V3ClassCatalogFinalizationError(
            "V3 complete-declared package contains a missing tile"
        )
    if digest.hexdigest() != package.index_file.sha256:
        raise V3ClassCatalogFinalizationError("V3 index changed during validation")
    return present_tiles, missing_tiles


def _semantic_tiles(package: _Package):
    for item in package.ranges:
        window = item.window
        for x in range(window.x_min, window.x_max + 1):
            for y in range(window.y_min, window.y_max + 1):
                tile = TileKey(window.z, x, y)
                yield tile, item.first_ordinal + window.ordinal(tile)


def _inflate_exact(compressed: bytes, raw_length: int, tile: TileKey) -> bytes:
    inflater = zlib.decompressobj(wbits=-zlib.MAX_WBITS)
    try:
        raw = inflater.decompress(compressed, raw_length + 1)
        if inflater.unconsumed_tail:
            raise V3ClassCatalogFinalizationError(
                f"V3 tile {tile.z}/{tile.x}/{tile.y} DEFLATE exceeds its declared length"
            )
        raw += inflater.flush()
    except zlib.error as error:
        raise V3ClassCatalogFinalizationError(
            f"V3 tile {tile.z}/{tile.x}/{tile.y} DEFLATE is corrupt"
        ) from error
    if not inflater.eof or inflater.unused_data or len(raw) != raw_length:
        raise V3ClassCatalogFinalizationError(
            f"V3 tile {tile.z}/{tile.x}/{tile.y} DEFLATE length is dishonest"
        )
    return raw


def _audit_from_authority_merge_receipt(package: _Package) -> _Audit | None:
    receipt = package.authority_merge_receipt
    if receipt is None:
        return None
    coverage = _exact_mapping(
        receipt.get("coverage"), "V3 authority merge coverage"
    )
    present_tile_count = _exact_int(
        coverage.get("presentTileCount"),
        "V3 authority merge present tile count",
        0,
        package.tile_count,
    )
    tile_count = _exact_int(
        coverage.get("tileCount"),
        "V3 authority merge tile count",
        package.tile_count,
        package.tile_count,
    )
    subtype_counts_value = receipt.get("subtypeCounts")
    if type(subtype_counts_value) is not list:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge subtype counts must be a list"
        )
    counts: dict[SemanticSubtype, SubtypeCatalogCounts] = {}
    for index, item in enumerate(subtype_counts_value):
        count = _exact_mapping(
            item, f"V3 authority merge subtypeCounts[{index}]"
        )
        if set(count) != {
            "canonicalVariantIds",
            "distinctFeatureIds",
            "postings",
            "semanticSubtype",
            "semanticSubtypeName",
        }:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge subtype count fields differ"
            )
        subtype_value = _exact_int(
            count.get("semanticSubtype"),
            "V3 authority merge semantic subtype",
            0,
            (1 << 31) - 1,
        )
        try:
            subtype = SemanticSubtype(subtype_value)
        except ValueError as error:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge semantic subtype is unknown"
            ) from error
        if count.get("semanticSubtypeName") != subtype.name:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge semantic subtype name differs"
            )
        if subtype in counts:
            raise V3ClassCatalogFinalizationError(
                "V3 authority merge subtype count repeats"
            )
        counts[subtype] = SubtypeCatalogCounts(
            distinct_feature_count=_exact_int(
                count.get("distinctFeatureIds"),
                "V3 authority merge distinct feature count",
                0,
                _ANDROID_MAX_RECORD_OFFSET,
            ),
            canonical_variant_count=_exact_int(
                count.get("canonicalVariantIds"),
                "V3 authority merge canonical variant count",
                0,
                _ANDROID_MAX_RECORD_OFFSET,
            ),
            posting_count=_exact_int(
                count.get("postings"),
                "V3 authority merge posting count",
                0,
                _ANDROID_MAX_RECORD_OFFSET,
            ),
        )
    if set(counts) != set(SemanticSubtype):
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge subtype counts are incomplete"
        )
    renderer_record_count = sum(
        item.posting_count for item in counts.values()
    )
    return _Audit(
        semantic_sha256=_sha256_text(
            receipt.get("rendererSemanticStreamSha256"),
            "V3 authority merge renderer semantic stream SHA-256",
        ),
        subtype_counts=counts,
        present_tile_count=present_tile_count,
        missing_tile_count=tile_count - present_tile_count,
        renderer_record_count=renderer_record_count,
    )


def _audit_package(package: _Package) -> _Audit:
    present_tiles, missing_tiles = _validate_index(package)
    semantic_digest = hashlib.sha256()
    semantic_digest.update(_SEMANTIC_STREAM_DOMAIN)
    posting_counts: Counter[SemanticSubtype] = Counter()
    renderer_record_count = 0
    with tempfile.TemporaryDirectory(prefix="flightalert-exp8-catalog-") as temporary:
        database_path = Path(temporary) / "identity-counts.sqlite"
        connection = sqlite3.connect(database_path)
        try:
            connection.execute("PRAGMA journal_mode=OFF")
            connection.execute("PRAGMA synchronous=OFF")
            connection.execute("PRAGMA temp_store=FILE")
            connection.execute(
                "CREATE TABLE features ("
                "subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
                "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
            )
            connection.execute(
                "CREATE TABLE variants ("
                "subtype INTEGER NOT NULL, identity BLOB NOT NULL, "
                "PRIMARY KEY (subtype, identity)) WITHOUT ROWID"
            )
            connection.execute(
                "CREATE TABLE variant_addresses ("
                "identity BLOB NOT NULL, digest BLOB NOT NULL, "
                "PRIMARY KEY (identity, digest)) WITHOUT ROWID"
            )
            with (
                package.index_file.path.open("rb") as index_handle,
                package.records_file.path.open("rb") as records_handle,
            ):
                decoded_present_tiles = 0
                for tile_number, (tile, ordinal) in enumerate(
                    _semantic_tiles(package), start=1
                ):
                    index_handle.seek(ordinal * INDEX_ENTRY_BYTES)
                    entry = index_handle.read(INDEX_ENTRY_BYTES)
                    if len(entry) != INDEX_ENTRY_BYTES:
                        raise V3ClassCatalogFinalizationError(
                            "V3 index ended early during semantic audit"
                        )
                    if entry == _ZERO_INDEX_ENTRY:
                        continue
                    (
                        offset,
                        compressed_length,
                        raw_length,
                        expected_hash32,
                        flags,
                    ) = struct.unpack("<QIIII", entry)
                    if (
                        flags != INDEX_FLAG_PRESENT
                        or not 0 < compressed_length <= _MAX_COMPRESSED_TILE_BYTES
                        or not 0 < raw_length <= MAX_TILE_BYTES
                        or offset > package.records_file.byte_length
                        or compressed_length
                        > package.records_file.byte_length - offset
                    ):
                        raise V3ClassCatalogFinalizationError(
                            "V3 index entry changed after structural validation"
                        )
                    records_handle.seek(offset)
                    compressed = records_handle.read(compressed_length)
                    if len(compressed) != compressed_length:
                        raise V3ClassCatalogFinalizationError(
                            "V3 records pack ended early during semantic audit"
                        )
                    payload = _inflate_exact(compressed, raw_length, tile)
                    if raw_hash32(payload) != expected_hash32:
                        raise V3ClassCatalogFinalizationError(
                            f"V3 tile {tile.z}/{tile.x}/{tile.y} integrity hash differs"
                        )
                    try:
                        ordered = sorted(
                            _extract_envelopes_fast(tile, payload),
                            key=lambda item: item.order_key,
                        )
                    except ValueError as error:
                        raise V3ClassCatalogFinalizationError(
                            f"V3 tile {tile.z}/{tile.x}/{tile.y} is not canonical: {error}"
                        ) from error
                    feature_rows: list[tuple[int, bytes]] = []
                    variant_rows: list[tuple[int, bytes]] = []
                    address_rows: list[tuple[bytes, bytes]] = []
                    for record in ordered:
                        body = struct.pack("<Q", tile.packed) + record.renderer_bytes
                        if len(body) > (1 << 32) - 1:
                            raise V3ClassCatalogFinalizationError(
                                "V3 semantic stream item exceeds its u32 bound"
                            )
                        semantic_digest.update(struct.pack("<I", len(body)))
                        semantic_digest.update(body)
                        posting_counts[record.subtype] += 1
                        renderer_record_count += 1
                        feature_identity = record.feature_id.to_bytes(8, "big")
                        variant_identity = record.variant_id.to_bytes(8, "big")
                        feature_rows.append((record.subtype.value, feature_identity))
                        variant_rows.append((record.subtype.value, variant_identity))
                        address_rows.append(
                            (
                                variant_identity,
                                record.variant_full_sha256,
                            )
                        )
                    connection.executemany(
                        "INSERT OR IGNORE INTO features (subtype, identity) VALUES (?, ?)",
                        feature_rows,
                    )
                    connection.executemany(
                        "INSERT OR IGNORE INTO variants (subtype, identity) VALUES (?, ?)",
                        variant_rows,
                    )
                    connection.executemany(
                        "INSERT OR IGNORE INTO variant_addresses (identity, digest) VALUES (?, ?)",
                        address_rows,
                    )
                    decoded_present_tiles += 1
                    if tile_number % 4096 == 0:
                        connection.commit()
                if decoded_present_tiles != present_tiles:
                    raise V3ClassCatalogFinalizationError(
                        "V3 present tile count changed during semantic audit"
                    )
            connection.commit()
            collision = connection.execute(
                "SELECT identity FROM variant_addresses "
                "GROUP BY identity HAVING COUNT(*) != 1 LIMIT 1"
            ).fetchone()
            if collision is not None:
                raise V3ClassCatalogFinalizationError(
                    "V3 canonical variant hot-ID collision is unresolved"
                )
            feature_counts = {
                int(subtype): int(count)
                for subtype, count in connection.execute(
                    "SELECT subtype, COUNT(*) FROM features GROUP BY subtype"
                )
            }
            variant_counts = {
                int(subtype): int(count)
                for subtype, count in connection.execute(
                    "SELECT subtype, COUNT(*) FROM variants GROUP BY subtype"
                )
            }
        finally:
            connection.close()
    counts = {
        subtype: SubtypeCatalogCounts(
            distinct_feature_count=feature_counts.get(subtype.value, 0),
            canonical_variant_count=variant_counts.get(subtype.value, 0),
            posting_count=posting_counts[subtype],
        )
        for subtype in SemanticSubtype
    }
    return _Audit(
        semantic_sha256=semantic_digest.hexdigest(),
        subtype_counts=counts,
        present_tile_count=present_tiles,
        missing_tile_count=missing_tiles,
        renderer_record_count=renderer_record_count,
    )


def _counts_document(
    counts: Mapping[SemanticSubtype, SubtypeCatalogCounts],
) -> list[dict[str, object]]:
    return [
        {
            "semanticSubtype": subtype.value,
            "semanticSubtypeName": subtype.name,
            "distinctFeatureIds": counts[subtype].distinct_feature_count,
            "canonicalVariantIds": counts[subtype].canonical_variant_count,
            "postings": counts[subtype].posting_count,
        }
        for subtype in SemanticSubtype
    ]


def _validate_authority_audit(package: _Package, audit: _Audit) -> None:
    receipt = package.authority_merge_receipt
    if receipt is None:
        return
    coverage = _exact_mapping(
        receipt.get("coverage"), "V3 authority merge coverage"
    )
    if set(coverage) != {
        "completeDeclaredScope",
        "completeWholeEarthDictionary",
        "presentTileCount",
        "primaryWholeEarthPreserved",
        "tileCount",
        "zoomRanges",
    }:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge coverage fields differ"
        )
    if _exact_int(
        coverage.get("presentTileCount"),
        "V3 authority merge present tile count",
        0,
        package.tile_count,
    ) != audit.present_tile_count:
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge present tile count differs"
        )
    _exact_bool(
        coverage.get("primaryWholeEarthPreserved"),
        "V3 authority merge primary whole-earth preservation",
    )
    if receipt.get("subtypeCounts") != _counts_document(audit.subtype_counts):
        raise V3ClassCatalogFinalizationError(
            "V3 authority merge subtype counts differ"
        )


def _validate_existing_catalog(package: _Package, audit: _Audit) -> None:
    if package.existing_catalog_sha256 is None:
        return
    catalog_path = package.directory / CATALOG_FILE_NAME
    if not catalog_path.is_file() or catalog_path.is_symlink():
        raise V3ClassCatalogFinalizationError(
            "V3 manifest declares a missing or aliased class catalog"
        )
    try:
        if catalog_path.stat().st_size != 754:
            raise V3ClassCatalogFinalizationError(
                "V3 existing class catalog is not exactly 754 bytes"
            )
        with catalog_path.open("rb") as catalog_handle:
            catalog_bytes = catalog_handle.read(755)
        if len(catalog_bytes) != 754:
            raise V3ClassCatalogFinalizationError(
                "V3 existing class catalog is not exactly 754 bytes"
            )
        catalog = ReferenceClassCatalog.from_verified_bytes(
            catalog_bytes,
            expected_catalog_sha256=package.existing_catalog_sha256,
            expected_renderer_semantic_stream_sha256=(
                package.renderer_semantic_stream_sha256
            ),
            expected_renderer_contract_sha256=(
                package.renderer_semantic_stream_sha256
            ),
            expected_presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
        )
    except V3ClassCatalogFinalizationError:
        raise
    except (OSError, ValueError) as error:
        raise V3ClassCatalogFinalizationError(
            f"V3 existing class catalog is corrupt: {error}"
        ) from error
    if dict(catalog.subtype_counts) != dict(audit.subtype_counts):
        raise V3ClassCatalogFinalizationError(
            "V3 existing class catalog counts differ from the package"
        )


def _finalizer_sha256() -> str:
    return _sha256_file(Path(__file__))


def _receipt_document(
    *,
    package: _Package,
    audit: _Audit,
    catalog_sha256: str,
    catalog_bytes: bytes,
    manifest_sha256: str,
    manifest_bytes: bytes,
    final_size_policy: Mapping[str, object] | None = None,
) -> dict[str, object]:
    receipt: dict[str, object] = {
        "schema": (
            _AUTHORITY_RECEIPT_SCHEMA
            if package.merge_receipt_file is not None
            else _RECEIPT_SCHEMA
        ),
        "packageId": package.package_id,
        "finalizerSha256": _finalizer_sha256(),
        "inputFiles": [
            {
                "name": "manifest.json",
                "role": "base-manifest-without-class-catalog",
                "bytes": len(package.base_manifest_bytes),
                "sha256": hashlib.sha256(package.base_manifest_bytes).hexdigest(),
            },
            {
                "name": "records.fadictpack",
                "bytes": package.records_file.byte_length,
                "sha256": package.records_file.sha256,
            },
            {
                "name": "tile-index.bin",
                "bytes": package.index_file.byte_length,
                "sha256": package.index_file.sha256,
            },
        ],
        "coverage": {
            "declaredTileCount": package.tile_count,
            "presentTileCount": audit.present_tile_count,
            "missingTileCount": audit.missing_tile_count,
            "rendererRecordCount": audit.renderer_record_count,
        },
        "rendererSemanticStreamSha256": audit.semantic_sha256,
        "rendererContractSha256": audit.semantic_sha256,
        "subtypeCounts": _counts_document(audit.subtype_counts),
        "outputFiles": [
            {
                "name": "manifest.json",
                "bytes": len(manifest_bytes),
                "sha256": manifest_sha256,
            },
            {
                "name": "records.fadictpack",
                "bytes": package.records_file.byte_length,
                "sha256": package.records_file.sha256,
            },
            {
                "name": "tile-index.bin",
                "bytes": package.index_file.byte_length,
                "sha256": package.index_file.sha256,
            },
            {
                "name": CATALOG_FILE_NAME,
                "bytes": len(catalog_bytes),
                "sha256": catalog_sha256,
            },
        ],
    }
    if package.merge_receipt_file is not None:
        if final_size_policy is None:
            raise V3ClassCatalogFinalizationError(
                "V3 authority final size policy is absent"
            )
        receipt["authorityReceipts"] = [
            dict(item) for item in package.authority_receipts
        ]
        receipt["mergeReceipt"] = {
            "bytes": package.merge_receipt_file.byte_length,
            "name": _MERGE_RECEIPT_FILE_NAME,
            "sha256": package.merge_receipt_file.sha256,
        }
        receipt["sizePolicy"] = dict(final_size_policy)
    return receipt


def _final_size_policy(
    package: _Package,
    *,
    required_package_bytes: int,
    publication_boundary_free_bytes: int,
) -> dict[str, object]:
    source = package.authority_size_policy
    if source is None:
        raise V3ClassCatalogFinalizationError(
            "V3 authority source size policy is absent"
        )
    binding = _validate_size_policy_binding(
        source.get("binding"), "V3 authority final size-policy binding"
    )
    source_decision = _exact_mapping(
        source.get("decision"), "V3 authority source size-policy decision"
    )
    mode = binding["mode"]
    available = source_decision.get("availableDestinationBytes")
    if mode == COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1:
        available = _exact_int(
            available,
            "V3 authority source available destination bytes",
            0,
            _ANDROID_MAX_RECORD_OFFSET,
        )
    else:
        available = None
    decision = dict(
        evaluate_reference_size_policy(
            mode=mode,
            required_package_bytes=required_package_bytes,
            available_destination_bytes=available,
        )
    )
    if mode == COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1:
        boundary = _exact_int(
            publication_boundary_free_bytes,
            "V3 authority final publication boundary bytes",
            0,
            _ANDROID_MAX_RECORD_OFFSET,
        )
        boundary_authorized = boundary >= DESTINATION_RESERVE_BYTES
        decision.update(
            {
                "authorized": bool(decision["authorized"])
                and boundary_authorized,
                "publicationBoundaryAuthorized": boundary_authorized,
                "publicationBoundaryDestinationFreeBytes": boundary,
                "publicationBoundaryRequiredReserveBytes": (
                    DESTINATION_RESERVE_BYTES
                ),
            }
        )
    if decision["authorized"] is not True:
        raise V3ClassCatalogFinalizationError(
            "V3 final six-file package lacks authenticated size capacity"
        )
    return {
        "accountingScope": _FINAL_SIZE_ACCOUNTING_SCOPE,
        "binding": binding,
        "decision": decision,
    }


def _converged_receipt(
    *,
    package: _Package,
    audit: _Audit,
    catalog_sha256: str,
    catalog_bytes: bytes,
    manifest_sha256: str,
    manifest_bytes: bytes,
    publication_boundary_free_bytes: int | None = None,
) -> tuple[dict[str, object], bytes]:
    if package.merge_receipt_file is None:
        receipt = _receipt_document(
            package=package,
            audit=audit,
            catalog_sha256=catalog_sha256,
            catalog_bytes=catalog_bytes,
            manifest_sha256=manifest_sha256,
            manifest_bytes=manifest_bytes,
        )
        return receipt, _canonical_json_bytes(receipt)
    if publication_boundary_free_bytes is None:
        raise V3ClassCatalogFinalizationError(
            "V3 authority final publication capacity is absent"
        )
    fixed_bytes = (
        len(manifest_bytes)
        + package.records_file.byte_length
        + package.index_file.byte_length
        + len(catalog_bytes)
        + package.merge_receipt_file.byte_length
    )
    required_package_bytes = fixed_bytes
    for _ in range(64):
        size_policy = _final_size_policy(
            package,
            required_package_bytes=required_package_bytes,
            publication_boundary_free_bytes=(
                publication_boundary_free_bytes
            ),
        )
        receipt = _receipt_document(
            package=package,
            audit=audit,
            catalog_sha256=catalog_sha256,
            catalog_bytes=catalog_bytes,
            manifest_sha256=manifest_sha256,
            manifest_bytes=manifest_bytes,
            final_size_policy=size_policy,
        )
        receipt_bytes = _canonical_json_bytes(receipt)
        next_required = fixed_bytes + len(receipt_bytes)
        if next_required == required_package_bytes:
            return receipt, receipt_bytes
        required_package_bytes = next_required
    raise V3ClassCatalogFinalizationError(
        "V3 final six-file size accounting did not converge"
    )


def _assert_file_binding(binding: _FileBinding) -> None:
    try:
        status = binding.path.stat()
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{binding.path.name} disappeared during finalization"
        ) from error
    if (
        status.st_size != binding.byte_length
        or status.st_dev != binding.device
        or status.st_ino != binding.inode
        or _sha256_file(binding.path) != binding.sha256
    ):
        raise V3ClassCatalogFinalizationError(
            f"{binding.path.name} changed during finalization"
        )


def _assert_authority_receipt_binding(package: _Package) -> None:
    if package.merge_receipt_file is not None:
        _assert_file_binding(package.merge_receipt_file)


def _stat_is_reparse(information: os.stat_result) -> bool:
    return stat.S_ISLNK(information.st_mode) or bool(
        getattr(information, "st_file_attributes", 0)
        & _REPARSE_POINT_ATTRIBUTE
    )


def _stat_identity(information: os.stat_result) -> tuple[int, int, int, int]:
    return (
        information.st_dev,
        information.st_ino,
        stat.S_IFMT(information.st_mode),
        getattr(information, "st_file_attributes", 0),
    )


def _validate_owned_stage_stat(information: os.stat_result, label: str) -> None:
    if _stat_is_reparse(information) or not stat.S_ISREG(information.st_mode):
        raise V3ClassCatalogFinalizationError(
            f"{label} is not a real regular file"
        )
    if information.st_nlink != 1:
        raise V3ClassCatalogFinalizationError(
            f"{label} must have exactly one link"
        )


def _assert_owned_stage(stage: _OwnedStagedFile, label: str) -> None:
    try:
        information = os.lstat(stage.path)
    except FileNotFoundError as error:
        raise V3ClassCatalogFinalizationError(f"{label} is missing") from error
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} cannot be inspected"
        ) from error
    _validate_owned_stage_stat(information, label)
    if _stat_identity(information) != stage.identity:
        raise V3ClassCatalogFinalizationError(
            f"{label} identity changed before publication"
        )


def _remove_owned_stage(stage: _OwnedStagedFile) -> None:
    if not os.path.lexists(stage.path):
        return
    label = f"staged {stage.path.name}"
    try:
        information = os.lstat(stage.path)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} cannot be inspected before cleanup"
        ) from error
    _validate_owned_stage_stat(information, label)
    if _stat_identity(information) != stage.identity:
        raise V3ClassCatalogFinalizationError(
            f"{label} identity changed before cleanup"
        )
    try:
        stage.path.unlink()
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} could not be removed during cleanup"
        ) from error


def _cleanup_owned_stages(stages: Sequence[_OwnedStagedFile]) -> None:
    first_error: BaseException | None = None
    for stage in stages:
        try:
            _remove_owned_stage(stage)
        except BaseException as error:
            if first_error is None:
                first_error = error
    if first_error is not None:
        raise first_error


def _stage_bytes(path: Path, data: bytes) -> _OwnedStagedFile:
    descriptor, raw_path = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    staged_path = Path(raw_path)
    created_information = os.fstat(descriptor)
    _validate_owned_stage_stat(created_information, f"staged {path.name}")
    staged = _OwnedStagedFile(
        path=staged_path,
        identity=_stat_identity(created_information),
        data=data,
    )
    try:
        with os.fdopen(descriptor, "w+b") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
            written_information = os.fstat(handle.fileno())
            _validate_owned_stage_stat(
                written_information,
                f"staged {path.name}",
            )
            if (
                _stat_identity(written_information) != staged.identity
                or written_information.st_size != len(data)
            ):
                raise V3ClassCatalogFinalizationError(
                    f"staged {path.name} identity changed while writing"
                )
            handle.seek(0)
            if handle.read(len(data) + 1) != data:
                raise V3ClassCatalogFinalizationError(
                    f"staged {path.name} readback differs while writing"
                )
        _assert_owned_stage(staged, f"staged {path.name}")
        if staged.path.read_bytes() != data:
            raise V3ClassCatalogFinalizationError(
                f"staged {path.name} readback differs"
            )
        return staged
    except Exception:
        try:
            os.close(descriptor)
        except OSError:
            pass
        _remove_owned_stage(staged)
        raise


def _rewrite_owned_stage(
    stage: _OwnedStagedFile,
    data: bytes,
    *,
    label: str,
) -> None:
    _assert_owned_stage(stage, label)
    try:
        with stage.path.open("r+b") as handle:
            if _stat_identity(os.fstat(handle.fileno())) != stage.identity:
                raise V3ClassCatalogFinalizationError(
                    f"{label} identity changed while open"
                )
            handle.seek(0)
            handle.truncate(0)
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
    except V3ClassCatalogFinalizationError:
        raise
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"{label} could not be rewritten"
        ) from error
    _assert_owned_stage(stage, label)
    if stage.path.read_bytes() != data:
        raise V3ClassCatalogFinalizationError(f"{label} readback differs")
    stage.data = data


def _publication_capacity(directory: Path) -> int:
    try:
        free = shutil.disk_usage(directory).free
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "V3 finalization publication capacity is unreadable"
        ) from error
    return _exact_int(
        free,
        "V3 finalization publication capacity",
        0,
        _ANDROID_MAX_RECORD_OFFSET,
    )


def _stage_converged_authority_receipt(
    *,
    package: _Package,
    audit: _Audit,
    catalog_sha256: str,
    catalog_bytes: bytes,
    manifest_sha256: str,
    manifest_bytes: bytes,
    receipt_path: Path,
) -> tuple[dict[str, object], bytes, _OwnedStagedFile]:
    boundary = _publication_capacity(package.directory)
    receipt, receipt_bytes = _converged_receipt(
        package=package,
        audit=audit,
        catalog_sha256=catalog_sha256,
        catalog_bytes=catalog_bytes,
        manifest_sha256=manifest_sha256,
        manifest_bytes=manifest_bytes,
        publication_boundary_free_bytes=boundary,
    )
    stage = _stage_bytes(receipt_path, receipt_bytes)
    try:
        for _ in range(16):
            observed = _publication_capacity(package.directory)
            if observed == boundary:
                confirmed = _publication_capacity(package.directory)
                if confirmed == observed:
                    return receipt, receipt_bytes, stage
                observed = confirmed
            boundary = observed
            receipt, receipt_bytes = _converged_receipt(
                package=package,
                audit=audit,
                catalog_sha256=catalog_sha256,
                catalog_bytes=catalog_bytes,
                manifest_sha256=manifest_sha256,
                manifest_bytes=manifest_bytes,
                publication_boundary_free_bytes=boundary,
            )
            _rewrite_owned_stage(
                stage,
                receipt_bytes,
                label="staged authority finalization receipt",
            )
    except BaseException:
        _remove_owned_stage(stage)
        raise
    _remove_owned_stage(stage)
    raise V3ClassCatalogFinalizationError(
        "V3 finalization publication capacity did not stabilize"
    )


def _sync_posix_directory_metadata(directory: Path) -> None:
    flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
    descriptor = os.open(directory, flags)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _open_windows_publication_file(
    path: Path,
    *,
    access: int,
    share: int,
) -> BinaryIO:
    if os.name != "nt" or not path.is_absolute():
        raise V3ClassCatalogFinalizationError(
            "Windows retained publication requires one absolute path"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    create_file = kernel32.CreateFileW
    create_file.argtypes = (
        wintypes.LPCWSTR,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.LPVOID,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.HANDLE,
    )
    create_file.restype = wintypes.HANDLE
    close_handle = kernel32.CloseHandle
    close_handle.argtypes = (wintypes.HANDLE,)
    close_handle.restype = wintypes.BOOL
    handle = create_file(
        str(path),
        access,
        share,
        None,
        _WINDOWS_OPEN_EXISTING,
        _WINDOWS_FILE_ATTRIBUTE_NORMAL | _WINDOWS_FILE_FLAG_OPEN_REPARSE_POINT,
        None,
    )
    if handle == ctypes.c_void_p(-1).value:
        error_code = ctypes.get_last_error()
        raise OSError(error_code, ctypes.FormatError(error_code), str(path))
    try:
        descriptor = msvcrt.open_osfhandle(
            int(handle), os.O_RDONLY | getattr(os, "O_BINARY", 0)
        )
    except BaseException:
        close_handle(handle)
        raise
    try:
        return os.fdopen(descriptor, "rb", buffering=0)
    except BaseException:
        os.close(descriptor)
        raise


def _open_windows_publication_publisher(path: Path) -> BinaryIO:
    return _open_windows_publication_file(
        path,
        access=(
            _WINDOWS_GENERIC_READ
            | _WINDOWS_DELETE_ACCESS
            | _WINDOWS_FILE_READ_ATTRIBUTES
            | _WINDOWS_SYNCHRONIZE_ACCESS
        ),
        share=_WINDOWS_FILE_SHARE_READ,
    )


def _open_windows_publication_candidate(path: Path) -> BinaryIO:
    return _open_windows_publication_file(
        path,
        access=(
            _WINDOWS_GENERIC_READ
            | _WINDOWS_FILE_READ_ATTRIBUTES
            | _WINDOWS_SYNCHRONIZE_ACCESS
        ),
        share=_WINDOWS_FILE_SHARE_READ | _WINDOWS_FILE_SHARE_DELETE,
    )


def _windows_file_rename_info_ex_buffer(destination: Path) -> object:
    if os.name != "nt" or not destination.is_absolute():
        raise V3ClassCatalogFinalizationError(
            "Windows retained publication requires one absolute destination"
        )
    if "\0" in str(destination):
        raise V3ClassCatalogFinalizationError(
            "Windows retained publication destination contains NUL"
        )
    import ctypes
    from ctypes import wintypes

    class _FileRenameInfoEx(ctypes.Structure):
        _fields_ = (
            ("flags", wintypes.DWORD),
            ("root_directory", wintypes.HANDLE),
            ("file_name_length", wintypes.DWORD),
            ("file_name", wintypes.WCHAR * 1),
        )

    name_offset = _FileRenameInfoEx.file_name.offset
    if (
        ctypes.sizeof(ctypes.c_void_p) != 8
        or name_offset != 20
        or ctypes.sizeof(_FileRenameInfoEx) != 24
    ):
        raise V3ClassCatalogFinalizationError(
            "Windows retained publication requires the verified x64 rename layout"
        )
    encoded = str(destination).encode("utf-16-le", "strict")
    buffer = ctypes.create_string_buffer(
        max(ctypes.sizeof(_FileRenameInfoEx), name_offset + len(encoded) + 2)
    )
    information = _FileRenameInfoEx.from_buffer(buffer)
    information.flags = (
        _WINDOWS_FILE_RENAME_REPLACE_IF_EXISTS
        | _WINDOWS_FILE_RENAME_POSIX_SEMANTICS
    )
    information.root_directory = None
    information.file_name_length = len(encoded)
    ctypes.memmove(ctypes.addressof(buffer) + name_offset, encoded, len(encoded))
    return buffer


def _windows_set_rename_information(
    publisher: BinaryIO,
    destination: Path,
) -> None:
    if os.name != "nt":
        raise V3ClassCatalogFinalizationError(
            "Windows retained publication requires Windows"
        )
    import ctypes
    import msvcrt
    from ctypes import wintypes

    buffer = _windows_file_rename_info_ex_buffer(destination)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    set_information = kernel32.SetFileInformationByHandle
    set_information.argtypes = (
        wintypes.HANDLE,
        ctypes.c_int,
        ctypes.c_void_p,
        wintypes.DWORD,
    )
    set_information.restype = wintypes.BOOL
    if not set_information(
        msvcrt.get_osfhandle(publisher.fileno()),
        _WINDOWS_FILE_RENAME_INFO_EX_CLASS,
        ctypes.byref(buffer),
        len(buffer),
    ):
        error_code = ctypes.get_last_error()
        raise OSError(
            error_code,
            ctypes.FormatError(error_code),
            str(destination),
        )


def _read_windows_retained_bytes(
    publication: _RetainedPublication,
    expected_length: int,
) -> bytes:
    publication.handle.seek(0)
    actual = publication.handle.read(expected_length + 1)
    publication.handle.seek(0)
    return actual


def _verify_windows_committed_target(
    destination: Path,
    publication: _RetainedPublication,
) -> None:
    information = os.lstat(destination)
    _validate_owned_stage_stat(information, f"published {destination.name}")
    handle_information = os.fstat(publication.handle.fileno())
    _validate_owned_stage_stat(
        handle_information,
        f"retained published {destination.name}",
    )
    if (
        _stat_identity(information) != publication.identity
        or _stat_identity(handle_information) != publication.identity
        or information.st_size != len(publication.data)
        or handle_information.st_size != len(publication.data)
    ):
        raise V3ClassCatalogFinalizationError(
            f"published {destination.name} identity changed after publication"
        )


def _assert_windows_retained_bytes(publication: _RetainedPublication) -> None:
    actual = _read_windows_retained_bytes(publication, len(publication.data))
    if actual != publication.data:
        raise V3ClassCatalogFinalizationError(
            f"published {publication.path.name} readback differs"
        )


def _windows_existing_canonical(path: Path) -> _RetainedPublication | None:
    try:
        information = os.lstat(path)
    except FileNotFoundError:
        return None
    _validate_owned_stage_stat(information, f"existing {path.name}")
    handle = _open_windows_publication_candidate(path)
    try:
        handle_information = os.fstat(handle.fileno())
        _validate_owned_stage_stat(
            handle_information,
            f"retained existing {path.name}",
        )
        identity = _stat_identity(information)
        if _stat_identity(handle_information) != identity:
            raise V3ClassCatalogFinalizationError(
                f"existing {path.name} identity changed while opening"
            )
        handle.seek(0)
        data = handle.read(information.st_size + 1)
        handle.seek(0)
        if len(data) != information.st_size:
            raise V3ClassCatalogFinalizationError(
                f"existing {path.name} readback differs"
            )
        return _RetainedPublication(
            path=path,
            handle=handle,
            identity=identity,
            data=data,
            committed=True,
        )
    except BaseException:
        handle.close()
        raise


class _PublicationSession:
    def __init__(self) -> None:
        self._retained: dict[Path, _RetainedPublication] = {}

    def __enter__(self) -> _PublicationSession:
        return self

    def __exit__(
        self,
        exception_type: type[BaseException] | None,
        exception: BaseException | None,
        traceback: object | None,
    ) -> bool:
        close_errors: list[OSError] = []
        for publication in tuple(self._retained.values()):
            if publication.handle.closed:
                continue
            try:
                publication.handle.close()
            except OSError as error:
                close_errors.append(error)
        self._retained.clear()
        if close_errors:
            close_error = V3ClassCatalogFinalizationError(
                "retained publication handle could not be closed"
            )
            if exception is None:
                raise close_error from close_errors[0]
            exception.add_note(f"{close_error}: {close_errors[0]}")
        return False

    @staticmethod
    def _key(path: Path) -> Path:
        return path.resolve(strict=False)

    def retained(self, path: Path) -> _RetainedPublication | None:
        publication = self._retained.get(self._key(path))
        if publication is None or not publication.committed:
            return None
        return publication

    def committed(self, path: Path) -> bool:
        key = self._key(path)
        publication = self._retained.get(key)
        return publication is not None and publication.committed

    def replace(
        self,
        staged: _OwnedStagedFile,
        destination: Path,
    ) -> _RetainedPublication | None:
        destination = self._key(destination)
        stage_parent = staged.path.parent.resolve(strict=False)
        if stage_parent != destination.parent:
            raise V3ClassCatalogFinalizationError(
                f"atomic {destination.name} publication must stay in one directory"
            )
        _assert_owned_stage(staged, f"staged {destination.name}")
        if os.name == "nt":
            return self._replace_windows(staged, destination)
        if os.name == "posix":
            try:
                os.replace(staged.path, destination)
                _sync_posix_directory_metadata(destination.parent)
            except OSError as error:
                raise V3ClassCatalogFinalizationError(
                    f"atomic {destination.name} publication failed: {error}"
                ) from error
            return None
        raise V3ClassCatalogFinalizationError(
            "durable class-catalog publication is unsupported on this platform"
        )

    def _replace_windows(
        self,
        staged: _OwnedStagedFile,
        destination: Path,
    ) -> _RetainedPublication:
        publisher: BinaryIO | None = None
        candidate: BinaryIO | None = None
        key = self._key(destination)
        old = self._retained.get(key)
        old_was_retained = old is not None
        committed = False
        pending_installed = False
        new_publication: _RetainedPublication | None = None
        try:
            publisher = _open_windows_publication_publisher(staged.path)
            publisher_information = os.fstat(publisher.fileno())
            _validate_owned_stage_stat(
                publisher_information,
                f"publisher staged {destination.name}",
            )
            if _stat_identity(publisher_information) != staged.identity:
                raise V3ClassCatalogFinalizationError(
                    f"staged {destination.name} identity changed while reopening publisher"
                )
            candidate = _open_windows_publication_candidate(staged.path)
            candidate_information = os.fstat(candidate.fileno())
            _validate_owned_stage_stat(
                candidate_information,
                f"candidate staged {destination.name}",
            )
            try:
                path_information = os.lstat(staged.path)
            except OSError as error:
                raise V3ClassCatalogFinalizationError(
                    f"staged {destination.name} cannot be inspected while reopened"
                ) from error
            _validate_owned_stage_stat(
                path_information,
                f"staged {destination.name}",
            )
            if (
                _stat_identity(candidate_information) != staged.identity
                or _stat_identity(path_information) != staged.identity
                or candidate_information.st_size != len(staged.data)
                or path_information.st_size != len(staged.data)
            ):
                raise V3ClassCatalogFinalizationError(
                    f"staged {destination.name} identity changed while reopening candidate"
                )
            candidate.seek(0)
            staged_readback = candidate.read(len(staged.data) + 1)
            candidate.seek(0)
            if staged_readback != staged.data:
                raise V3ClassCatalogFinalizationError(
                    f"staged {destination.name} readback differs while reopening"
                )

            if old is not None:
                _verify_windows_committed_target(destination, old)
                _assert_windows_retained_bytes(old)
            else:
                old = _windows_existing_canonical(destination)

            new_publication = _RetainedPublication(
                path=destination,
                handle=candidate,
                identity=staged.identity,
                data=staged.data,
            )
            self._retained[key] = new_publication
            pending_installed = True
            _windows_set_rename_information(publisher, destination)
            committed = True
            new_publication.committed = True
            candidate = None
            _verify_windows_committed_target(destination, new_publication)
            _assert_windows_retained_bytes(new_publication)
            return new_publication
        except OSError as error:
            if committed:
                raise
            raise V3ClassCatalogFinalizationError(
                f"atomic {destination.name} publication failed: {error}"
            ) from error
        finally:
            if not committed and pending_installed:
                if old_was_retained:
                    assert old is not None
                    self._retained[key] = old
                else:
                    self._retained.pop(key, None)
            if publisher is not None:
                publisher.close()
            if candidate is not None:
                candidate.close()
            if old is not None and (committed or not old_was_retained):
                old.handle.close()

    def readback(self, path: Path, expected: bytes, expected_sha256: str) -> None:
        publication = self.retained(path)
        if publication is None:
            _readback_path(path, expected, expected_sha256)
            return
        _verify_windows_committed_target(self._key(path), publication)
        actual = _read_windows_retained_bytes(publication, len(expected))
        if actual != expected or hashlib.sha256(actual).hexdigest() != expected_sha256:
            raise V3ClassCatalogFinalizationError(
                f"published {path.name} readback differs"
            )


def _replace_staged(
    staged: _OwnedStagedFile,
    destination: Path,
    publications: _PublicationSession | None = None,
) -> _RetainedPublication | None:
    if publications is not None:
        return publications.replace(staged, destination)
    with _PublicationSession() as local_publications:
        return local_publications.replace(staged, destination)


def _invalidate_existing_receipt(
    receipt_path: Path,
    publications: _PublicationSession,
) -> None:
    try:
        receipt_status = os.lstat(receipt_path)
    except FileNotFoundError:
        return
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            "existing finalization receipt cannot be inspected"
        ) from error
    if not stat.S_ISREG(receipt_status.st_mode):
        raise V3ClassCatalogFinalizationError(
            "existing finalization receipt is not a regular file"
        )
    staged_marker = _stage_bytes(receipt_path, b"")
    try:
        publications.replace(staged_marker, receipt_path)
    except Exception:
        _remove_owned_stage(staged_marker)
        raise
    publications.readback(receipt_path, b"", hashlib.sha256(b"").hexdigest())


def _readback_path(path: Path, expected: bytes, expected_sha256: str) -> None:
    try:
        information = os.lstat(path)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} is not readable"
        ) from error
    if (
        _stat_is_reparse(information)
        or not stat.S_ISREG(information.st_mode)
        or information.st_size != len(expected)
    ):
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} readback differs"
        )
    try:
        with path.open("rb") as handle:
            actual = handle.read(len(expected) + 1)
    except OSError as error:
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} is not readable"
        ) from error
    if actual != expected or hashlib.sha256(actual).hexdigest() != expected_sha256:
        raise V3ClassCatalogFinalizationError(
            f"published {path.name} readback differs"
        )


def _notify(hook: Callable[[str], None] | None, event: str) -> None:
    if hook is not None:
        hook(event)


def finalize_v3_class_catalog(
    package_directory: Path,
    *,
    publication_hook: Callable[[str], None] | None = None,
) -> FinalizationResult:
    """Audit one existing V3 package and atomically publish its class catalog."""

    if publication_hook is not None and not callable(publication_hook):
        raise V3ClassCatalogFinalizationError("publication hook must be callable")
    package = _load_package(package_directory)
    audit = _audit_from_authority_merge_receipt(package) or _audit_package(package)
    if audit.semantic_sha256 != package.renderer_semantic_stream_sha256:
        raise V3ClassCatalogFinalizationError(
            "V3 renderer semantic stream SHA-256 differs from the manifest"
        )
    _validate_authority_audit(package, audit)
    _validate_existing_catalog(package, audit)

    catalog_bytes = canonical_class_catalog_bytes(
        renderer_semantic_stream_sha256=audit.semantic_sha256,
        renderer_contract_sha256=audit.semantic_sha256,
        presentation_policy_sha256=PRESENTATION_POLICY_SHA256,
        subtype_counts=audit.subtype_counts,
    )
    if len(catalog_bytes) != 754:
        raise V3ClassCatalogFinalizationError(
            "canonical class catalog is not exactly 754 bytes"
        )
    catalog_sha256 = hashlib.sha256(catalog_bytes).hexdigest()
    final_manifest = dict(package.manifest)
    final_manifest["classCatalog"] = {
        "catalogSha256": catalog_sha256,
        "rendererContractSha256": audit.semantic_sha256,
    }
    final_manifest_bytes = _canonical_json_bytes(final_manifest)
    if len(final_manifest_bytes) > _MAX_MANIFEST_BYTES:
        raise V3ClassCatalogFinalizationError(
            "finalized V3 manifest byte length is outside its bound"
        )
    manifest_sha256 = hashlib.sha256(final_manifest_bytes).hexdigest()
    receipt: dict[str, object] | None = None
    receipt_bytes: bytes | None = None
    if package.merge_receipt_file is None:
        receipt, receipt_bytes = _converged_receipt(
            package=package,
            audit=audit,
            catalog_sha256=catalog_sha256,
            catalog_bytes=catalog_bytes,
            manifest_sha256=manifest_sha256,
            manifest_bytes=final_manifest_bytes,
        )

    catalog_path = package.directory / CATALOG_FILE_NAME
    manifest_path = package.directory / "manifest.json"
    receipt_path = package.directory / RECEIPT_FILE_NAME
    staged_paths: list[_OwnedStagedFile] = []
    publications = _PublicationSession()
    publications.__enter__()
    try:
        staged_catalog = _stage_bytes(catalog_path, catalog_bytes)
        staged_paths.append(staged_catalog)
        staged_manifest = _stage_bytes(manifest_path, final_manifest_bytes)
        staged_paths.append(staged_manifest)

        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _assert_authority_receipt_binding(package)

        _notify(publication_hook, "before_catalog_replace")
        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _assert_authority_receipt_binding(package)
        publications.replace(staged_catalog, catalog_path)
        staged_paths.remove(staged_catalog)
        publications.readback(catalog_path, catalog_bytes, catalog_sha256)
        _notify(publication_hook, "after_catalog_published")

        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _assert_authority_receipt_binding(package)

        if manifest_path.read_bytes() != package.manifest_raw:
            raise V3ClassCatalogFinalizationError(
                "V3 manifest changed before its catalog binding publication"
            )
        _notify(publication_hook, "before_manifest_replace")
        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _assert_authority_receipt_binding(package)
        _invalidate_existing_receipt(receipt_path, publications)
        _assert_file_binding(package.manifest_file)
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _assert_authority_receipt_binding(package)
        publications.readback(catalog_path, catalog_bytes, catalog_sha256)
        publications.replace(staged_manifest, manifest_path)
        staged_paths.remove(staged_manifest)
        publications.readback(manifest_path, final_manifest_bytes, manifest_sha256)
        _notify(publication_hook, "after_manifest_published")

        _notify(publication_hook, "before_receipt_replace")
        _assert_file_binding(package.records_file)
        _assert_file_binding(package.index_file)
        _assert_authority_receipt_binding(package)
        publications.readback(catalog_path, catalog_bytes, catalog_sha256)
        publications.readback(manifest_path, final_manifest_bytes, manifest_sha256)
        if package.merge_receipt_file is not None:
            _assert_authority_preflight_inventory(package.directory)
            receipt, receipt_bytes, staged_receipt = (
                _stage_converged_authority_receipt(
                    package=package,
                    audit=audit,
                    catalog_sha256=catalog_sha256,
                    catalog_bytes=catalog_bytes,
                    manifest_sha256=manifest_sha256,
                    manifest_bytes=final_manifest_bytes,
                    receipt_path=receipt_path,
                )
            )
        else:
            assert receipt_bytes is not None
            staged_receipt = _stage_bytes(receipt_path, receipt_bytes)
        staged_paths.append(staged_receipt)
        try:
            if package.merge_receipt_file is not None:
                _assert_authority_staged_receipt_inventory(
                    package.directory,
                    staged_receipt,
                )
            publications.replace(staged_receipt, receipt_path)
            staged_paths.remove(staged_receipt)
            assert receipt_bytes is not None
            publications.readback(
                receipt_path,
                receipt_bytes,
                hashlib.sha256(receipt_bytes).hexdigest(),
            )
            _notify(publication_hook, "after_receipt_published")

            publications.readback(
                receipt_path,
                receipt_bytes,
                hashlib.sha256(receipt_bytes).hexdigest(),
            )
            _assert_file_binding(package.records_file)
            _assert_file_binding(package.index_file)
            _assert_authority_receipt_binding(package)
            publications.readback(catalog_path, catalog_bytes, catalog_sha256)
            publications.readback(manifest_path, final_manifest_bytes, manifest_sha256)
            if package.merge_receipt_file is not None:
                _assert_authority_final_inventory(package.directory)
        except BaseException:
            if not publications.committed(receipt_path):
                _invalidate_existing_receipt(receipt_path, publications)
            raise
    finally:
        try:
            _cleanup_owned_stages(staged_paths)
        finally:
            publications.__exit__(*sys.exc_info())
    assert receipt is not None
    return FinalizationResult(
        package_directory=package.directory,
        catalog_sha256=catalog_sha256,
        manifest_sha256=manifest_sha256,
        receipt=receipt,
    )


def _main(arguments: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Independently audit an Experiment 8 V3 package and publish its "
            "canonical FAE8CAT1 class catalog."
        )
    )
    parser.add_argument("--package", required=True, type=Path)
    parsed = parser.parse_args(arguments)
    try:
        result = finalize_v3_class_catalog(parsed.package)
    except V3ClassCatalogFinalizationError as error:
        parser.exit(1, f"error: {error}\n")
    print(_canonical_json_bytes(result.receipt).decode("utf-8"), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())


__all__ = [
    "CATALOG_FILE_NAME",
    "RECEIPT_FILE_NAME",
    "FinalizationResult",
    "V3ClassCatalogFinalizationError",
    "finalize_v3_class_catalog",
]
