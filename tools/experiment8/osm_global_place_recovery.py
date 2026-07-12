from __future__ import annotations

import hashlib
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import BinaryIO, Mapping

from . import osm_global_place_package as pipeline
from .osm_global_place_package import (
    GlobalPlaceExtractionResult,
    GlobalPlacePackageError,
    PlaceRendererSemanticOutcome,
    PlaceSemanticAdmissionAudit,
    PlaceSourceBinding,
)


RECOVERABLE_EXTRACTOR_CODE = MappingProxyType(
    {
        "bytes": 56_967,
        "name": "osm_global_place_package.py",
        "sha256": "29b6f5eb63da75441155c9625c692155d1acb0020324bf16855b094f96c7616e",
    }
)
_RECOVERY_SCHEMA = (
    "flightalert.experiment8.osm-global-place-extraction-recovery-receipt.v2"
)
_EXTRACTION_SCHEMA = "flightalert.experiment8.osm-global-place-extraction-receipt.v1"
_EMPTY_SHA256 = hashlib.sha256(b"").hexdigest()
_REPARSE_POINT = 0x400


@dataclass(frozen=True, slots=True)
class _RecoveryContract:
    checkpoint_bytes: int
    checkpoint_sha256: str
    extractor_code_bytes: int
    extractor_code_sha256: str
    extractor_run_identity_sha256: str
    output_name: str
    recovery_output_name: str
    recovery_output_path: str
    renderer_semantic_outcome_sha256: str
    renderer_semantic_outcome_document_sha256: str
    source_bytes: int
    source_path: str
    source_sha256: str
    stage_name: str
    strict_opl_audit_sha256: str
    semantic_admission_audit_sha256: str

    def __post_init__(self) -> None:
        for value, label in (
            (self.checkpoint_bytes, "checkpoint bytes"),
            (self.extractor_code_bytes, "extractor code bytes"),
            (self.source_bytes, "source bytes"),
        ):
            if type(value) is not int or value < 0:
                raise GlobalPlacePackageError(f"recovery {label} is invalid")
        for value, label in (
            (self.checkpoint_sha256, "checkpoint SHA-256"),
            (self.extractor_code_sha256, "extractor code SHA-256"),
            (self.extractor_run_identity_sha256, "run identity SHA-256"),
            (self.source_sha256, "source SHA-256"),
            (
                self.renderer_semantic_outcome_sha256,
                "renderer semantic outcome SHA-256",
            ),
            (
                self.renderer_semantic_outcome_document_sha256,
                "renderer semantic outcome document SHA-256",
            ),
            (self.strict_opl_audit_sha256, "strict OPL audit SHA-256"),
            (
                self.semantic_admission_audit_sha256,
                "semantic admission audit SHA-256",
            ),
        ):
            pipeline._require_sha256(value, f"recovery {label}")
        for value, label in (
            (self.output_name, "output name"),
            (self.recovery_output_name, "recovery output name"),
            (self.recovery_output_path, "recovery output path"),
            (self.source_path, "source path"),
            (self.stage_name, "stage name"),
        ):
            if type(value) is not str or not value:
                raise GlobalPlacePackageError(f"recovery {label} is empty")
        if self.extractor_code_bytes != RECOVERABLE_EXTRACTOR_CODE["bytes"] or (
            self.extractor_code_sha256 != RECOVERABLE_EXTRACTOR_CODE["sha256"]
        ):
            raise GlobalPlacePackageError("recovery extractor code is not the pinned producer")

    def document(self) -> dict[str, object]:
        return {
            "checkpoint": {
                "bytes": self.checkpoint_bytes,
                "sha256": self.checkpoint_sha256,
            },
            "extractorCode": {
                "bytes": self.extractor_code_bytes,
                "sha256": self.extractor_code_sha256,
            },
            "extractorRunIdentitySha256": self.extractor_run_identity_sha256,
            "outputName": self.output_name,
            "recoveryOutput": {
                "name": self.recovery_output_name,
                "path": self.recovery_output_path,
                "role": "independent semantic-outcome-bound recovered extraction",
            },
            "rendererSemanticOutcomeSha256": (
                self.renderer_semantic_outcome_sha256
            ),
            "rendererSemanticOutcomeDocumentSha256": (
                self.renderer_semantic_outcome_document_sha256
            ),
            "source": {
                "bytes": self.source_bytes,
                "path": self.source_path,
                "sha256": self.source_sha256,
            },
            "stageName": self.stage_name,
            "strictOplAuditSha256": self.strict_opl_audit_sha256,
            "semanticAdmissionAuditSha256": (
                self.semantic_admission_audit_sha256
            ),
        }


_EXACT_STAGE_PATH = Path(
    r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction.partial-a38e94db9511ee3f"
)
_EXACT_OUTPUT_PATH = Path(
    r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction-outcome-v2"
)
_EXACT_STRICT_OPL_AUDIT = {
    "firstNodeId": 54,
    "lastNodeId": 13_973_334_856,
    "nodeCount": 7_602_596,
    "ordering": "Type_then_ID strictly increasing",
    "visibility": "current visible only",
}
_EXACT_SEMANTIC_ADMISSION_AUDIT = {
    "controlCharacterExcludedNodes": 13,
    "controlCharacterFields": {
        "capital": 0,
        "name": 13,
        "name:en": 0,
        "place": 0,
        "population": 0,
    },
    "decodedValueAllowlist": ["capital", "name", "name:en", "place", "population"],
}
_EXACT_RETAINED_RECOVERY_CONTRACT = _RecoveryContract(
    checkpoint_bytes=7_388,
    checkpoint_sha256="0bb33544b343eb46601583dd37915252e7afdfd4e8f5e100c245eb9c58d78902",
    extractor_code_bytes=56_967,
    extractor_code_sha256="29b6f5eb63da75441155c9625c692155d1acb0020324bf16855b094f96c7616e",
    extractor_run_identity_sha256=(
        "460ee2c92ac8b078628571790c42af70b8afc5e0fa8f82f9181d4160c05765ef"
    ),
    output_name="osm-global-place-260629-extraction",
    recovery_output_name="osm-global-place-260629-extraction-outcome-v2",
    recovery_output_path=str(_EXACT_OUTPUT_PATH),
    renderer_semantic_outcome_sha256=(
        "de35e24fd9ce9b1b2617f1b425521db56edf5fe5977151a147a2bfbc456881dc"
    ),
    renderer_semantic_outcome_document_sha256=(
        "90f715ca79073259fc0e82c58e224ec5501da417f4ab9b179c32de7cbc85cc60"
    ),
    source_bytes=pipeline.EXPECTED_PLANET_BYTES,
    source_path=str(pipeline.EXPECTED_PLANET_PATH),
    source_sha256=pipeline.EXPECTED_PLANET_SHA256,
    stage_name=(
        "osm-global-place-260629-extraction.partial-a38e94db9511ee3f"
    ),
    strict_opl_audit_sha256=hashlib.sha256(
        pipeline._canonical_json_bytes(_EXACT_STRICT_OPL_AUDIT)
    ).hexdigest(),
    semantic_admission_audit_sha256=hashlib.sha256(
        pipeline._canonical_json_bytes(_EXACT_SEMANTIC_ADMISSION_AUDIT)
    ).hexdigest(),
)


def _is_link_or_reparse(path: Path) -> bool:
    try:
        status = path.lstat()
    except FileNotFoundError:
        return False
    return path.is_symlink() or bool(
        getattr(status, "st_file_attributes", 0) & _REPARSE_POINT
    )


def _require_real_directory(path: Path, label: str) -> None:
    if (
        not isinstance(path, Path)
        or not path.is_dir()
        or _is_link_or_reparse(path)
    ):
        raise GlobalPlacePackageError(f"{label} is not one real directory")


def _identity_document(path: Path, *, name: str | None = None) -> dict[str, object]:
    identity = pipeline._stream_file_identity(path)
    return {
        "bytes": identity.bytes,
        "name": name or path.name,
        "sha256": identity.sha256,
    }


def _write_fsynced(path: Path, raw: bytes) -> None:
    with path.open("xb") as handle:
        handle.write(raw)
        handle.flush()
        os.fsync(handle.fileno())


def _directory_names(path: Path) -> tuple[str, ...]:
    _require_real_directory(path, "recovery directory")
    names = []
    for candidate in path.iterdir():
        if (
            not candidate.is_file()
            or _is_link_or_reparse(candidate)
            or candidate.name in {".", ".."}
        ):
            raise GlobalPlacePackageError(
                "recovery directory contains a non-regular or redirected entry"
            )
        names.append(candidate.name)
    return tuple(sorted(names))


def _read_checkpoint(
    stage_directory: Path,
    contract: _RecoveryContract,
) -> tuple[dict[str, object], bytes]:
    checkpoint_path = stage_directory / "extraction-checkpoint.json"
    pipeline.verify_file_identity(
        checkpoint_path,
        expected_bytes=contract.checkpoint_bytes,
        expected_sha256=contract.checkpoint_sha256,
    )
    raw = pipeline._read_bounded_file(
        checkpoint_path, 16 * 1024 * 1024, "recovery checkpoint"
    )
    try:
        document = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise GlobalPlacePackageError("recovery checkpoint is malformed") from error
    if (
        not isinstance(document, dict)
        or pipeline._canonical_json_bytes(document) != raw
        or set(document)
        != {"executions", "files", "runIdentity", "runIdentitySha256", "schema"}
        or document.get("schema")
        != "flightalert.experiment8.osm-global-place-extraction-checkpoint.v1"
    ):
        raise GlobalPlacePackageError("recovery checkpoint schema or fields differ")
    return document, raw


def _expected_output_names() -> tuple[tuple[str, ...], ...]:
    return (
        ("command-01-fileinfo.stdout", "command-01-fileinfo.stderr"),
        (
            "command-02-tags-filter.stdout",
            "command-02-tags-filter.stderr",
            "place-nodes.pbf.partial",
        ),
        ("command-03-fileinfo.stdout", "command-03-fileinfo.stderr"),
        (
            "command-04-cat.stdout",
            "command-04-cat.stderr",
            "place-nodes.opl.partial",
        ),
        ("command-05-fileinfo.stdout", "command-05-fileinfo.stderr"),
    )


def _validate_checkpoint_document(
    checkpoint: Mapping[str, object],
    contract: _RecoveryContract,
) -> dict[str, dict[str, object]]:
    run_identity = checkpoint.get("runIdentity")
    executions = checkpoint.get("executions")
    files = checkpoint.get("files")
    if (
        not isinstance(run_identity, dict)
        or not isinstance(executions, list)
        or not isinstance(files, dict)
    ):
        raise GlobalPlacePackageError("recovery checkpoint ledgers are malformed")
    run_sha256 = hashlib.sha256(
        pipeline._canonical_json_bytes(run_identity)
    ).hexdigest()
    if (
        checkpoint.get("runIdentitySha256") != run_sha256
        or run_sha256 != contract.extractor_run_identity_sha256
    ):
        raise GlobalPlacePackageError("recovery extractor run identity differs")
    source = run_identity.get("source")
    code = run_identity.get("code")
    runtime = run_identity.get("runtime")
    if code != dict(RECOVERABLE_EXTRACTOR_CODE):
        raise GlobalPlacePackageError("recovery old producer code identity differs")
    if source != {
        "bytes": contract.source_bytes,
        "path": contract.source_path,
        "sha256": contract.source_sha256,
    }:
        raise GlobalPlacePackageError("recovery planet source identity differs")
    if runtime != pipeline._pinned_runtime_document():
        raise GlobalPlacePackageError("recovery pinned osmium runtime differs")
    if run_identity.get("outputName") != contract.output_name:
        raise GlobalPlacePackageError("recovery output name differs")
    provisional = dict(run_identity)
    raw_commands = provisional.pop("commands", None)
    if not isinstance(raw_commands, list):
        raise GlobalPlacePackageError("recovery command plan is missing")
    provisional_sha256 = hashlib.sha256(
        pipeline._canonical_json_bytes(provisional)
    ).hexdigest()
    if contract.stage_name != contract.output_name + ".partial-" + provisional_sha256[:16]:
        raise GlobalPlacePackageError("recovery stage suffix differs from producer identity")
    first_arguments = raw_commands[0] if raw_commands else None
    filter_arguments = raw_commands[1] if len(raw_commands) > 1 else None
    if not isinstance(first_arguments, list) or not isinstance(filter_arguments, list):
        raise GlobalPlacePackageError("recovery command arguments are malformed")
    try:
        pbf_linux = filter_arguments[filter_arguments.index("-o") + 1]
    except (ValueError, IndexError) as error:
        raise GlobalPlacePackageError("recovery PBF output argument is missing") from error
    if type(pbf_linux) is not str or not pbf_linux.endswith("/place-nodes.pbf.partial"):
        raise GlobalPlacePackageError("recovery PBF output argument differs")
    stage_linux = pbf_linux[: -len("/place-nodes.pbf.partial")]
    commands = pipeline.build_osmium_extraction_commands(
        planet_linux_path=first_arguments[-1],
        stage_linux_directory=stage_linux,
    )
    if raw_commands != [list(command.arguments) for command in commands]:
        raise GlobalPlacePackageError("recovery command plan differs")
    pipeline._validate_execution_ledger(executions, commands)

    expected_names = tuple(
        name for group in _expected_output_names() for name in group
    )
    if set(files) != set(expected_names):
        raise GlobalPlacePackageError("recovery checkpoint file inventory differs")
    checked: dict[str, dict[str, object]] = {}
    for execution, names in zip(executions, _expected_output_names(), strict=True):
        outputs = execution.get("outputs") if isinstance(execution, dict) else None
        if not isinstance(outputs, list) or len(outputs) != len(names):
            raise GlobalPlacePackageError("recovery command output ledger differs")
        for output, name in zip(outputs, names, strict=True):
            stored = files.get(name)
            if (
                not isinstance(output, dict)
                or set(output) != {"bytes", "name", "sha256"}
                or output.get("name") != name
                or not isinstance(stored, dict)
                or set(stored) != {"bytes", "sha256"}
                or type(output.get("bytes")) is not int
                or output["bytes"] < 0
                or type(stored.get("bytes")) is not int
                or stored["bytes"] != output["bytes"]
                or stored.get("sha256") != output.get("sha256")
            ):
                raise GlobalPlacePackageError(
                    "recovery command output does not match its file ledger"
                )
            digest = pipeline._require_sha256(
                output["sha256"], "recovery staged output"
            )
            checked[name] = {"bytes": output["bytes"], "sha256": digest}
    return checked


def _validate_checkpoint(
    stage_directory: Path,
    checkpoint: Mapping[str, object],
    contract: _RecoveryContract,
) -> dict[str, dict[str, object]]:
    if stage_directory.name != contract.stage_name:
        raise GlobalPlacePackageError("recovery stage basename differs")
    checked = _validate_checkpoint_document(checkpoint, contract)
    expected_inventory = tuple(
        sorted((*checked.keys(), "extraction-checkpoint.json"))
    )
    if _directory_names(stage_directory) != expected_inventory:
        raise GlobalPlacePackageError("recovery retained-stage inventory differs")
    for name, identity in checked.items():
        pipeline.verify_file_identity(
            stage_directory / name,
            expected_bytes=identity["bytes"],
            expected_sha256=identity["sha256"],
        )
    return checked


def _copy_verified(
    source: Path,
    destination: Path,
    expected: Mapping[str, object],
) -> dict[str, object]:
    before = source.stat()
    digest = hashlib.sha256()
    total = 0
    with source.open("rb") as source_handle, destination.open("xb") as destination_handle:
        opened = os.fstat(source_handle.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise GlobalPlacePackageError("recovery source changed while opening")
        while chunk := source_handle.read(1024 * 1024):
            destination_handle.write(chunk)
            digest.update(chunk)
            total += len(chunk)
        destination_handle.flush()
        os.fsync(destination_handle.fileno())
        after_handle = os.fstat(source_handle.fileno())
    after = source.stat()
    signature = pipeline._stat_signature(before)
    if (
        signature != pipeline._stat_signature(after_handle)
        or signature != pipeline._stat_signature(after)
        or total != expected["bytes"]
        or digest.hexdigest() != expected["sha256"]
    ):
        raise GlobalPlacePackageError("recovery source drifted or hash differed during copy")
    return {"bytes": total, "name": destination.name, "sha256": digest.hexdigest()}


class _CopyingReader:
    def __init__(self, source: BinaryIO, destination: BinaryIO) -> None:
        self.source = source
        self.destination = destination
        self.digest = hashlib.sha256()
        self.total = 0

    def read(self, size: int = -1) -> bytes:
        if type(size) is not int or size <= 0:
            raise GlobalPlacePackageError("recovery OPL read must be positively bounded")
        chunk = self.source.read(size)
        if type(chunk) is not bytes or len(chunk) > size:
            raise GlobalPlacePackageError("recovery OPL source returned an invalid chunk")
        if chunk:
            self.destination.write(chunk)
            self.digest.update(chunk)
            self.total += len(chunk)
        return chunk

    def tell(self) -> int:
        return self.source.tell()


def _copy_and_audit_opl(
    source: Path,
    destination: Path,
    outcome_destination: Path,
    expected: Mapping[str, object],
    *,
    source_generation_sha256: str,
) -> tuple[
    dict[str, object],
    dict[str, object],
    dict[str, object],
    dict[str, object],
    dict[str, object],
]:
    from . import osm_global_place_store as store

    before = source.stat()
    with (
        source.open("rb") as source_handle,
        destination.open("xb") as destination_handle,
        outcome_destination.open("xb") as outcome_handle,
    ):
        opened = os.fstat(source_handle.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise GlobalPlacePackageError("recovery OPL changed while opening")
        reader = _CopyingReader(source_handle, destination_handle)
        strict_audit, semantic_audit, outcome_audit = (
            store._semantic_outcome_audits_stream(
                reader,
                source_generation_sha256=source_generation_sha256,
                outcome_stream=outcome_handle,
            )
        )
        destination_handle.flush()
        os.fsync(destination_handle.fileno())
        outcome_handle.flush()
        os.fsync(outcome_handle.fileno())
        after_handle = os.fstat(source_handle.fileno())
    after = source.stat()
    signature = pipeline._stat_signature(before)
    if (
        signature != pipeline._stat_signature(after_handle)
        or signature != pipeline._stat_signature(after)
        or reader.total != expected["bytes"]
        or reader.digest.hexdigest() != expected["sha256"]
    ):
        raise GlobalPlacePackageError("recovery OPL drifted or hash differed during audit")
    identity = {
        "bytes": reader.total,
        "name": destination.name,
        "sha256": reader.digest.hexdigest(),
    }
    outcome_identity = _identity_document(
        outcome_destination, name=outcome_destination.name
    )
    if (
        outcome_identity["bytes"]
        != outcome_audit["nodeCount"] * outcome_audit["eventBytes"]
        or outcome_identity["sha256"] != outcome_audit["sha256"]
    ):
        raise GlobalPlacePackageError(
            "recovery semantic outcome artifact differs from its audit"
        )
    return identity, outcome_identity, strict_audit, semantic_audit, outcome_audit


def _finalizer_code() -> dict[str, object]:
    from . import osm_global_place_store as store

    return {
        "auditParser": _identity_document(Path(pipeline.__file__).resolve()),
        "semanticOutcome": _identity_document(Path(store.__file__).resolve()),
        "stageFinalizer": _identity_document(Path(__file__).resolve()),
    }


def _finalizer_runtime() -> dict[str, object]:
    return {
        "pythonImplementation": sys.implementation.name,
        "pythonVersion": list(sys.version_info[:3]),
    }


def _recovery_partial_path(
    output_directory: Path,
    contract: _RecoveryContract,
    finalizer_code: Mapping[str, object],
) -> Path:
    identity = {
        "contract": contract.document(),
        "finalizerCode": dict(finalizer_code),
        "outputPath": os.path.abspath(output_directory),
        "schema": "flightalert.experiment8.osm-global-place-recovery-run.v1",
    }
    digest = hashlib.sha256(pipeline._canonical_json_bytes(identity)).hexdigest()
    return output_directory.with_name(
        output_directory.name + ".recovery-partial-" + digest[:16]
    )


def _publish_no_clobber(partial: Path, output: Path) -> None:
    if output.exists() or _is_link_or_reparse(output):
        raise GlobalPlacePackageError("recovery output already exists")
    try:
        os.rename(partial, output)
    except OSError as error:
        raise GlobalPlacePackageError("atomic recovery publication failed") from error


def _expected_recovered_names() -> tuple[str, ...]:
    logs = tuple(
        name
        for group in _expected_output_names()
        for name in group
        if not name.startswith("place-nodes.")
    )
    return tuple(
        sorted(
            (
                *logs,
                "extraction-checkpoint.json",
                "extraction-receipt.json",
                "extraction-recovery-receipt.json",
                "place-nodes.opl",
                "place-nodes.pbf",
                "place-semantic-outcomes.bin",
            )
        )
    )


def _document_sha256(document: Mapping[str, object]) -> str:
    return hashlib.sha256(pipeline._canonical_json_bytes(document)).hexdigest()


def _require_contract_audits(
    strict: object,
    semantic: object,
    outcome: object,
    contract: _RecoveryContract,
) -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    if (
        not isinstance(strict, dict)
        or not isinstance(semantic, dict)
        or not isinstance(outcome, dict)
    ):
        raise GlobalPlacePackageError("recovered semantic OPL audit is malformed")
    if (
        _document_sha256(strict) != contract.strict_opl_audit_sha256
        or _document_sha256(semantic)
        != contract.semantic_admission_audit_sha256
    ):
        raise GlobalPlacePackageError("recovered semantic OPL audit differs from its pin")
    evidence = PlaceRendererSemanticOutcome.from_document(outcome)
    if (
        evidence.node_count != strict.get("nodeCount")
        or evidence.stream_sha256
        != contract.renderer_semantic_outcome_sha256
        or _document_sha256(outcome)
        != contract.renderer_semantic_outcome_document_sha256
    ):
        raise GlobalPlacePackageError(
            "recovered renderer semantic outcome differs from its pin"
        )
    PlaceSemanticAdmissionAudit.from_documents(strict, semantic)
    return strict, semantic, outcome


def _read_canonical_receipt(
    path: Path,
    *,
    label: str,
    schema: str,
) -> tuple[dict[str, object], pipeline.FileIdentity]:
    identity = pipeline._stream_file_identity(path)
    raw = pipeline._read_bounded_file(path, 16 * 1024 * 1024, label)
    try:
        document = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise GlobalPlacePackageError(f"{label} is malformed") from error
    if (
        not isinstance(document, dict)
        or pipeline._canonical_json_bytes(document) != raw
        or document.get("schema") != schema
    ):
        raise GlobalPlacePackageError(f"{label} schema or canonical bytes differ")
    return document, identity


def _validate_recovery_output(
    directory: Path,
    *,
    contract: _RecoveryContract,
) -> tuple[dict[str, object], pipeline.FileIdentity]:
    _require_real_directory(directory, "recovered extraction")
    if _directory_names(directory) != _expected_recovered_names():
        raise GlobalPlacePackageError("recovered extraction inventory differs")
    receipt, receipt_identity = _read_canonical_receipt(
        directory / "extraction-receipt.json",
        label="recovered extraction receipt",
        schema=_EXTRACTION_SCHEMA,
    )
    recovery, _ = _read_canonical_receipt(
        directory / "extraction-recovery-receipt.json",
        label="extraction recovery receipt",
        schema=_RECOVERY_SCHEMA,
    )
    checkpoint, checkpoint_raw = _read_checkpoint(directory, contract)
    checked = _validate_checkpoint_document(checkpoint, contract)
    strict, semantic, outcome = _require_contract_audits(
        receipt.get("strictOplAudit"),
        receipt.get("semanticAdmissionAudit"),
        recovery.get("rendererSemanticOutcomeAudit"),
        contract,
    )

    final_names = {
        "place-nodes.opl.partial": "place-nodes.opl",
        "place-nodes.pbf.partial": "place-nodes.pbf",
    }
    verified: dict[str, dict[str, object]] = {}
    for staged_name, expected in checked.items():
        final_name = final_names.get(staged_name, staged_name)
        identity = pipeline.verify_file_identity(
            directory / final_name,
            expected_bytes=expected["bytes"],
            expected_sha256=expected["sha256"],
        )
        verified[final_name] = {
            "bytes": identity.bytes,
            "name": final_name,
            "sha256": identity.sha256,
        }
    outcome_identity = pipeline.verify_file_identity(
        directory / "place-semantic-outcomes.bin",
        expected_bytes=outcome["nodeCount"] * outcome["eventBytes"],
        expected_sha256=outcome["sha256"],
    )
    verified["place-semantic-outcomes.bin"] = {
        "bytes": outcome_identity.bytes,
        "name": "place-semantic-outcomes.bin",
        "sha256": outcome_identity.sha256,
    }
    expected_artifacts = {
        "candidateOpl": verified["place-nodes.opl"],
        "candidatePbf": verified["place-nodes.pbf"],
        "rendererSemanticOutcomes": verified["place-semantic-outcomes.bin"],
    }
    expected_receipt = {
        "artifacts": expected_artifacts,
        "code": dict(RECOVERABLE_EXTRACTOR_CODE),
        "commands": checkpoint["executions"],
        "runIdentitySha256": checkpoint["runIdentitySha256"],
        "runtime": checkpoint["runIdentity"]["runtime"],
        "rendererSemanticOutcome": outcome,
        "schema": _EXTRACTION_SCHEMA,
        "selection": {
            "filterExpression": "n/place",
            "objectKind": "node",
            "referencedObjectsOmitted": True,
        },
        "semanticAdmissionAudit": semantic,
        "semanticAdmissionPolicySha256": pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        "source": checkpoint["runIdentity"]["source"],
        "strictOplAudit": strict,
    }
    if receipt != expected_receipt:
        raise GlobalPlacePackageError(
            "recovered extraction receipt differs from its authenticated checkpoint"
        )

    checkpoint_identity = {
        "bytes": len(checkpoint_raw),
        "name": "extraction-checkpoint.json",
        "sha256": hashlib.sha256(checkpoint_raw).hexdigest(),
    }
    fresh_receipt_identity = {
        "bytes": receipt_identity.bytes,
        "name": "extraction-receipt.json",
        "sha256": receipt_identity.sha256,
    }
    actual_bound = [
        *verified.values(),
        checkpoint_identity,
        fresh_receipt_identity,
    ]
    actual_bound.sort(key=lambda document: str(document["name"]))
    expected_recovery = {
        "boundFiles": actual_bound,
        "finalizer": {"code": _finalizer_code(), "runtime": _finalizer_runtime()},
        "freshExtractionReceipt": fresh_receipt_identity,
        "producer": {
            "checkpoint": checkpoint_identity,
            "code": dict(RECOVERABLE_EXTRACTOR_CODE),
            "completedCommandCount": len(checkpoint["executions"]),
            "contract": contract.document(),
            "noCommandsReexecuted": True,
            "runIdentitySha256": checkpoint["runIdentitySha256"],
        },
        "provenance": {
            "artifacts": "stream-copied and rehashed from the authenticated retained stage",
            "commands": "copied from the authenticated old-producer checkpoint",
            "semanticAudit": "recomputed while copying the exact OPL artifact",
        },
        "recoveryOutput": contract.document()["recoveryOutput"],
        "rendererSemanticOutcomeAudit": outcome,
        "schema": _RECOVERY_SCHEMA,
        "semanticOplAudit": {
            "semanticAdmissionAudit": semantic,
            "strictOplAudit": strict,
        },
        "semanticParserPolicy": {
            "document": pipeline._SEMANTIC_ADMISSION_POLICY_DOCUMENT,
            "sha256": pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        },
    }
    if recovery != expected_recovery:
        raise GlobalPlacePackageError(
            "extraction recovery receipt differs from the exact recovery contract"
        )
    return receipt, receipt_identity


def _recover_completed_extraction_stage(
    *,
    stage_directory: Path,
    output_directory: Path,
    contract: _RecoveryContract,
) -> GlobalPlaceExtractionResult:
    if not isinstance(contract, _RecoveryContract):
        raise GlobalPlacePackageError("recovery contract type is invalid")
    _require_real_directory(stage_directory, "retained recovery stage")
    if stage_directory.name != contract.stage_name:
        raise GlobalPlacePackageError("retained recovery stage name differs")
    if output_directory.name != contract.recovery_output_name:
        raise GlobalPlacePackageError("recovery output name differs")
    if _normalized_path(output_directory, "recovery output") != _normalized_path(
        Path(contract.recovery_output_path), "recovery contract output"
    ):
        raise GlobalPlacePackageError("recovery physical output path differs")
    if stage_directory.parent.resolve() != output_directory.parent.resolve():
        raise GlobalPlacePackageError("recovery output must be a sibling of its retained stage")
    if output_directory.exists() or _is_link_or_reparse(output_directory):
        raise GlobalPlacePackageError("recovery output already exists")
    checkpoint, checkpoint_raw = _read_checkpoint(stage_directory, contract)
    files = _validate_checkpoint(stage_directory, checkpoint, contract)
    finalizer_code = _finalizer_code()
    finalizer_runtime = _finalizer_runtime()
    partial = _recovery_partial_path(output_directory, contract, finalizer_code)
    if partial.exists() or _is_link_or_reparse(partial):
        receipt, _ = _validate_recovery_output(
            partial, contract=contract
        )
        _publish_no_clobber(partial, output_directory)
        published, _ = _validate_recovery_output(
            output_directory, contract=contract
        )
        return GlobalPlaceExtractionResult(
            "complete", output_directory, MappingProxyType(published)
        )
    partial.mkdir(parents=False, exist_ok=False)

    copied: dict[str, dict[str, object]] = {}
    strict_audit = None
    semantic_audit = None
    outcome_audit = None
    for name in sorted(files):
        destination_name = {
            "place-nodes.pbf.partial": "place-nodes.pbf",
            "place-nodes.opl.partial": "place-nodes.opl",
        }.get(name, name)
        if name == "place-nodes.opl.partial":
            (
                identity,
                outcome_identity,
                strict_audit,
                semantic_audit,
                outcome_audit,
            ) = _copy_and_audit_opl(
                stage_directory / name,
                partial / destination_name,
                partial / "place-semantic-outcomes.bin",
                files[name],
                source_generation_sha256=contract.source_sha256,
            )
            copied["place-semantic-outcomes.bin"] = outcome_identity
        else:
            identity = _copy_verified(
                stage_directory / name,
                partial / destination_name,
                files[name],
            )
        copied[destination_name] = identity
    _write_fsynced(partial / "extraction-checkpoint.json", checkpoint_raw)
    assert (
        strict_audit is not None
        and semantic_audit is not None
        and outcome_audit is not None
    )
    strict_audit, semantic_audit, outcome_audit = _require_contract_audits(
        strict_audit, semantic_audit, outcome_audit, contract
    )
    artifacts = {
        "candidateOpl": copied["place-nodes.opl"],
        "candidatePbf": copied["place-nodes.pbf"],
        "rendererSemanticOutcomes": copied["place-semantic-outcomes.bin"],
    }
    extraction_receipt = {
        "artifacts": artifacts,
        "code": dict(RECOVERABLE_EXTRACTOR_CODE),
        "commands": checkpoint["executions"],
        "runIdentitySha256": checkpoint["runIdentitySha256"],
        "runtime": checkpoint["runIdentity"]["runtime"],
        "rendererSemanticOutcome": outcome_audit,
        "schema": _EXTRACTION_SCHEMA,
        "selection": {
            "filterExpression": "n/place",
            "objectKind": "node",
            "referencedObjectsOmitted": True,
        },
        "semanticAdmissionAudit": semantic_audit,
        "semanticAdmissionPolicySha256": pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        "source": checkpoint["runIdentity"]["source"],
        "strictOplAudit": strict_audit,
    }
    extraction_raw = pipeline._canonical_json_bytes(extraction_receipt)
    _write_fsynced(partial / "extraction-receipt.json", extraction_raw)
    extraction_identity = _identity_document(
        partial / "extraction-receipt.json", name="extraction-receipt.json"
    )
    checkpoint_identity = {
        "bytes": len(checkpoint_raw),
        "name": "extraction-checkpoint.json",
        "sha256": hashlib.sha256(checkpoint_raw).hexdigest(),
    }
    bound_names = sorted(
        (*copied.keys(), "extraction-checkpoint.json", "extraction-receipt.json")
    )
    recovery_receipt = {
        "boundFiles": [
            _identity_document(partial / name, name=name) for name in bound_names
        ],
        "finalizer": {"code": finalizer_code, "runtime": finalizer_runtime},
        "freshExtractionReceipt": extraction_identity,
        "producer": {
            "checkpoint": checkpoint_identity,
            "code": dict(RECOVERABLE_EXTRACTOR_CODE),
            "completedCommandCount": len(checkpoint["executions"]),
            "contract": contract.document(),
            "noCommandsReexecuted": True,
            "runIdentitySha256": checkpoint["runIdentitySha256"],
        },
        "provenance": {
            "artifacts": "stream-copied and rehashed from the authenticated retained stage",
            "commands": "copied from the authenticated old-producer checkpoint",
            "semanticAudit": "recomputed while copying the exact OPL artifact",
        },
        "recoveryOutput": contract.document()["recoveryOutput"],
        "rendererSemanticOutcomeAudit": outcome_audit,
        "schema": _RECOVERY_SCHEMA,
        "semanticOplAudit": {
            "semanticAdmissionAudit": semantic_audit,
            "strictOplAudit": strict_audit,
        },
        "semanticParserPolicy": {
            "document": pipeline._SEMANTIC_ADMISSION_POLICY_DOCUMENT,
            "sha256": pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        },
    }
    _write_fsynced(
        partial / "extraction-recovery-receipt.json",
        pipeline._canonical_json_bytes(recovery_receipt),
    )
    if _finalizer_code() != finalizer_code or _finalizer_runtime() != finalizer_runtime:
        raise GlobalPlacePackageError("recovery finalizer identity drifted")
    if _directory_names(stage_directory) != tuple(
        sorted((*files.keys(), "extraction-checkpoint.json"))
    ):
        raise GlobalPlacePackageError("retained recovery stage changed during finalization")
    receipt, _ = _validate_recovery_output(
        partial, contract=contract
    )
    _publish_no_clobber(partial, output_directory)
    published, _ = _validate_recovery_output(
        output_directory, contract=contract
    )
    return GlobalPlaceExtractionResult(
        "complete", output_directory, MappingProxyType(published)
    )


def _normalized_path(path: Path, label: str) -> str:
    if not isinstance(path, Path):
        raise GlobalPlacePackageError(f"{label} is not a filesystem path")
    return os.path.normcase(os.path.realpath(os.path.abspath(os.fspath(path))))


def _require_exact_path(path: Path, expected: Path, label: str) -> None:
    if _normalized_path(path, label) != _normalized_path(expected, label):
        raise GlobalPlacePackageError(
            f"{label} differs from the exact pinned recovery path"
        )


def recover_completed_extraction_stage(
    *,
    stage_directory: Path = _EXACT_STAGE_PATH,
    output_directory: Path = _EXACT_OUTPUT_PATH,
) -> GlobalPlaceExtractionResult:
    _require_exact_path(stage_directory, _EXACT_STAGE_PATH, "retained recovery stage")
    _require_exact_path(output_directory, _EXACT_OUTPUT_PATH, "recovery output")
    return _recover_completed_extraction_stage(
        stage_directory=_EXACT_STAGE_PATH,
        output_directory=_EXACT_OUTPUT_PATH,
        contract=_EXACT_RETAINED_RECOVERY_CONTRACT,
    )


def _source_binding_from_recovered_extraction(
    extraction_directory: Path,
    contract: _RecoveryContract,
) -> PlaceSourceBinding:
    receipt, receipt_identity = _validate_recovery_output(
        extraction_directory, contract=contract
    )
    recovery_identity = pipeline._stream_file_identity(
        extraction_directory / "extraction-recovery-receipt.json"
    )
    strict = receipt["strictOplAudit"]
    semantic = receipt["semanticAdmissionAudit"]
    audit = PlaceSemanticAdmissionAudit.from_documents(strict, semantic)
    source = receipt["source"]
    pbf = receipt["artifacts"]["candidatePbf"]
    opl = receipt["artifacts"]["candidateOpl"]
    outcomes = receipt["artifacts"]["rendererSemanticOutcomes"]
    outcome_evidence = PlaceRendererSemanticOutcome.from_document(
        receipt["rendererSemanticOutcome"]
    )
    return PlaceSourceBinding(
        planet_path=source["path"],
        planet_bytes=source["bytes"],
        planet_sha256=source["sha256"],
        candidate_pbf_bytes=pbf["bytes"],
        candidate_pbf_sha256=pbf["sha256"],
        opl_bytes=opl["bytes"],
        opl_sha256=opl["sha256"],
        extraction_receipt_sha256=receipt_identity.sha256,
        recovery_receipt_sha256=recovery_identity.sha256,
        renderer_semantic_outcome_path=str(
            extraction_directory / "place-semantic-outcomes.bin"
        ),
        renderer_semantic_outcome_bytes=outcomes["bytes"],
        renderer_semantic_outcome=outcome_evidence,
        semantic_admission_policy_sha256=pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        semantic_admission=audit,
    )


def source_binding_from_recovered_extraction(
    extraction_directory: Path,
) -> PlaceSourceBinding:
    _require_exact_path(
        extraction_directory, _EXACT_OUTPUT_PATH, "recovered extraction"
    )
    return _source_binding_from_recovered_extraction(
        _EXACT_OUTPUT_PATH,
        _EXACT_RETAINED_RECOVERY_CONTRACT,
    )


__all__ = [
    "recover_completed_extraction_stage",
    "source_binding_from_recovered_extraction",
]
