from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path
from types import MappingProxyType
from typing import Mapping


BUDGETED_RELEASE_V1 = "budgeted-release-v1"
COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1 = (
    "complete-uncompressed-visual-evaluation-v1"
)
REFERENCE_SIZE_POLICY_MODES = (
    BUDGETED_RELEASE_V1,
    COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
)
DEFAULT_REFERENCE_SIZE_POLICY_MODE = BUDGETED_RELEASE_V1

PREFERRED_COMPONENT_PACKAGE_BYTES = 23_500_000_000
PREFERRED_MANDATORY_PHONE_FOOTPRINT_BYTES = 25_000_000_000
HARD_COMPONENT_PACKAGE_BYTES = 38_500_000_000
HARD_MANDATORY_PHONE_FOOTPRINT_BYTES = 40_000_000_000
DESTINATION_RESERVE_BYTES = 1_500_000_000


class ReferenceSizePolicyError(ValueError):
    pass


def _freeze(value: object) -> object:
    if isinstance(value, dict):
        return MappingProxyType(
            {key: _freeze(item) for key, item in value.items()}
        )
    if isinstance(value, list):
        return tuple(_freeze(item) for item in value)
    return value


def _json_value(value: object) -> object:
    if isinstance(value, Mapping):
        return {key: _json_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_value(item) for item in value]
    return value


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(
            _json_value(document),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + "\n"
    ).encode("utf-8")


def _module_identity() -> dict[str, object]:
    path = Path(__file__).resolve()
    before = path.stat()
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as handle:
        while True:
            block = handle.read(1024 * 1024)
            if not block:
                break
            digest.update(block)
            total += len(block)
        after_handle = path.stat()
    after = path.stat()
    signature = lambda value: (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
    )
    if (
        signature(before) != signature(after_handle)
        or signature(before) != signature(after)
        or total != before.st_size
    ):
        raise ReferenceSizePolicyError(
            "reference size policy module changed while hashing"
        )
    return MappingProxyType({"bytes": total, "sha256": digest.hexdigest()})


def normalize_reference_size_policy_mode(
    mode: object = DEFAULT_REFERENCE_SIZE_POLICY_MODE,
) -> str:
    if type(mode) is not str or mode not in REFERENCE_SIZE_POLICY_MODES:
        raise ReferenceSizePolicyError("unsupported reference size policy mode")
    return mode


def reference_size_policy_document() -> Mapping[str, object]:
    return _freeze({
        "constraints": {
            "contentPruningAuthorized": False,
            "nonSizeBoundsMayBeWeakened": False,
            "visualEvaluationRequiresCompleteUncompressedPackage": True,
        },
        "destinationReserveBytes": DESTINATION_RESERVE_BYTES,
        "historicalBudgets": {
            "hardComponentPackageBytes": HARD_COMPONENT_PACKAGE_BYTES,
            "hardMandatoryPhoneFootprintBytes": (
                HARD_MANDATORY_PHONE_FOOTPRINT_BYTES
            ),
            "preferredComponentPackageBytes": (
                PREFERRED_COMPONENT_PACKAGE_BYTES
            ),
            "preferredMandatoryPhoneFootprintBytes": (
                PREFERRED_MANDATORY_PHONE_FOOTPRINT_BYTES
            ),
        },
        "modes": list(REFERENCE_SIZE_POLICY_MODES),
        "schema": "flightalert.experiment8.reference-size-policy.v2",
        "visualEvaluationCapacityBasis": (
            "fresh-destination-free-plus-exact-owned-partial-before-staging-"
            "and-fresh-final-reserve-proof"
        ),
        "visualEvaluationCapacityPersistence": (
            "memory-only-sqlite-capacity-is-not-authority"
        ),
    })


def reference_size_policy_binding(
    mode: object = DEFAULT_REFERENCE_SIZE_POLICY_MODE,
) -> Mapping[str, object]:
    checked_mode = normalize_reference_size_policy_mode(mode)
    document = reference_size_policy_document()
    return MappingProxyType(
        {
            "document": document,
            "documentSha256": hashlib.sha256(
                _canonical_json_bytes(document)
            ).hexdigest(),
            "mode": checked_mode,
            "module": _module_identity(),
            "schema": "flightalert.experiment8.reference-size-policy-binding.v1",
        }
    )


def destination_available_bytes(output_directory: Path) -> int:
    if not isinstance(output_directory, Path):
        raise ReferenceSizePolicyError(
            "reference package destination must be pathlib.Path"
        )
    parent = output_directory.parent
    if not parent.is_dir() or parent.is_symlink():
        raise ReferenceSizePolicyError(
            "reference package destination parent is absent or unsafe"
        )
    available = shutil.disk_usage(parent).free
    if type(available) is not int or available < 0:
        raise ReferenceSizePolicyError(
            "reference package destination capacity is malformed"
        )
    return available


def evaluate_reference_size_policy(
    *,
    mode: object = DEFAULT_REFERENCE_SIZE_POLICY_MODE,
    required_package_bytes: int,
    available_destination_bytes: int | None,
) -> Mapping[str, object]:
    checked_mode = normalize_reference_size_policy_mode(mode)
    if type(required_package_bytes) is not int or required_package_bytes < 0:
        raise ReferenceSizePolicyError(
            "reference package required bytes must be nonnegative"
        )
    if checked_mode == COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1:
        if (
            type(available_destination_bytes) is not int
            or available_destination_bytes < 0
        ):
            raise ReferenceSizePolicyError(
                "visual-evaluation destination capacity must be nonnegative"
            )
    else:
        available_destination_bytes = None

    mandatory_footprint = required_package_bytes + DESTINATION_RESERVE_BYTES
    required_with_reserve = mandatory_footprint
    hard_component_exceeded = (
        required_package_bytes >= HARD_COMPONENT_PACKAGE_BYTES
    )
    hard_phone_exceeded = (
        mandatory_footprint >= HARD_MANDATORY_PHONE_FOOTPRINT_BYTES
    )
    preferred_component_exceeded = (
        required_package_bytes >= PREFERRED_COMPONENT_PACKAGE_BYTES
    )
    preferred_phone_exceeded = (
        mandatory_footprint >= PREFERRED_MANDATORY_PHONE_FOOTPRINT_BYTES
    )
    if checked_mode == BUDGETED_RELEASE_V1:
        authorized = not hard_component_exceeded
    else:
        assert type(available_destination_bytes) is int
        authorized = available_destination_bytes >= required_with_reserve

    return MappingProxyType(
        {
            "authorized": authorized,
            "availableDestinationBytes": available_destination_bytes,
            "hardComponentPackageCeilingExceeded": hard_component_exceeded,
            "hardMandatoryPhoneFootprintCeilingExceeded": hard_phone_exceeded,
            "mandatoryPhoneFootprintBytes": mandatory_footprint,
            "mode": checked_mode,
            "preferredComponentPackageCeilingExceeded": (
                preferred_component_exceeded
            ),
            "preferredMandatoryPhoneFootprintCeilingExceeded": (
                preferred_phone_exceeded
            ),
            "requiredPackageBytes": required_package_bytes,
            "requiredWithReserveBytes": required_with_reserve,
            "schema": "flightalert.experiment8.reference-size-decision.v1",
        }
    )


__all__ = [
    "BUDGETED_RELEASE_V1",
    "COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1",
    "DEFAULT_REFERENCE_SIZE_POLICY_MODE",
    "DESTINATION_RESERVE_BYTES",
    "REFERENCE_SIZE_POLICY_MODES",
    "ReferenceSizePolicyError",
    "destination_available_bytes",
    "evaluate_reference_size_policy",
    "normalize_reference_size_policy_mode",
    "reference_size_policy_binding",
    "reference_size_policy_document",
]
