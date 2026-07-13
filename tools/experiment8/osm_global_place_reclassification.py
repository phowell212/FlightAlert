from __future__ import annotations

import errno
import hashlib
import json
import os
import stat
import sys
from pathlib import Path
from types import MappingProxyType
from typing import Callable, Mapping

from . import osm_global_place_package as pipeline
from . import osm_global_place_recovery as recovery
from . import reference_presentation_policy as presentation_policy
from .osm_global_place_package import (
    GlobalPlaceExtractionResult,
    GlobalPlacePackageError,
    PlaceRendererSemanticOutcome,
    PlaceSemanticAdmissionAudit,
    PlaceSourceBinding,
)


PRESENTATION_POLICY_SHA256 = presentation_policy.PRESENTATION_POLICY_SHA256


_RECLASSIFICATION_SCHEMA = (
    "flightalert.experiment8.osm-global-place-reclassification-receipt.v1"
)
_EXACT_SOURCE_PATH = Path(
    r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction-outcome-v2"
)
_EXACT_OUTPUT_PATH = Path(
    r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction-outcome-v3"
)
_HISTORICAL_FINALIZER_CODE = MappingProxyType(
    {
        "auditParser": MappingProxyType(
            {
                "bytes": 73_351,
                "name": "osm_global_place_package.py",
                "sha256": (
                    "7f7c18ff7d44d9ecfeb1d447a29bb65a104ca9bf93d8959f33a9d53cd8da1d8e"
                ),
            }
        ),
        "semanticOutcome": MappingProxyType(
            {
                "bytes": 83_314,
                "name": "osm_global_place_store.py",
                "sha256": (
                    "a3bfd11e8dcc46e93d8c523fbd209909f31cc34cb7ea6f3a7df792b493ac37a9"
                ),
            }
        ),
        "stageFinalizer": MappingProxyType(
            {
                "bytes": 39_967,
                "name": "osm_global_place_recovery.py",
                "sha256": (
                    "ae58c8e03c8a83617b9d7c8ad61e1367e43e87a1a3c87b64979d55211c1a15ba"
                ),
            }
        ),
    }
)
_HISTORICAL_FINALIZER_RUNTIME = MappingProxyType(
    {
        "pythonImplementation": "cpython",
        "pythonVersion": (3, 11, 1),
    }
)
_INVENTORY = tuple(
    sorted(
        (
            "place-nodes.opl",
            "place-nodes.pbf",
            "place-semantic-outcomes.bin",
            "reclassification-receipt.json",
        )
    )
)


SourceBindingLoader = Callable[[Path], PlaceSourceBinding]


class _HashingReader:
    def __init__(self, source: object) -> None:
        self.source = source
        self.digest = hashlib.sha256()
        self.total = 0

    def read(self, size: int = -1) -> bytes:
        if type(size) is not int or size <= 0:
            raise GlobalPlacePackageError(
                "reclassification OPL read must be positively bounded"
            )
        chunk = self.source.read(size)
        if type(chunk) is not bytes or len(chunk) > size:
            raise GlobalPlacePackageError(
                "reclassification OPL source returned an invalid chunk"
            )
        self.digest.update(chunk)
        self.total += len(chunk)
        return chunk

    def tell(self) -> int:
        return self.source.tell()


def _source_binding_from_historical_recovery(
    extraction_directory: Path,
) -> PlaceSourceBinding:
    recovery._require_exact_path(
        extraction_directory,
        _EXACT_SOURCE_PATH,
        "historical reclassification source",
    )
    return recovery._source_binding_from_recovered_extraction(
        _EXACT_SOURCE_PATH,
        recovery._EXACT_RETAINED_RECOVERY_CONTRACT,
        expected_finalizer_code=_HISTORICAL_FINALIZER_CODE,
        expected_finalizer_runtime=_HISTORICAL_FINALIZER_RUNTIME,
    )


def _code_identities() -> dict[str, object]:
    from . import osm_global_place_store as store

    identities = store._code_identities()
    identities.update(
        {
        "reclassifier": recovery._identity_document(Path(__file__).resolve()),
        "recoveryPrimitives": recovery._identity_document(
            Path(recovery.__file__).resolve()
        ),
        }
    )
    return {name: identities[name] for name in sorted(identities)}


def _semantic_contract() -> dict[str, str]:
    from .osm_place_renderer import classifier_identity_sha256

    actual_policy = presentation_policy.presentation_policy_sha256()
    if actual_policy != PRESENTATION_POLICY_SHA256:
        raise GlobalPlacePackageError(
            "canonical presentation policy SHA-256 differs from its constant"
        )
    classifier = classifier_identity_sha256()
    pipeline._require_sha256(classifier, "reclassification classifier")
    return {
        "classifierSha256": classifier,
        "presentationPolicySha256": actual_policy,
    }


def _runtime_document() -> dict[str, object]:
    return {
        "pythonImplementation": sys.implementation.name,
        "pythonVersion": list(sys.version_info[:3]),
    }


def _artifact_document(path: Path, *, name: str | None = None) -> dict[str, object]:
    return recovery._identity_document(path, name=name)


def _source_document(
    source_directory: Path,
    binding: PlaceSourceBinding,
) -> dict[str, object]:
    extraction_receipt = _artifact_document(
        source_directory / "extraction-receipt.json",
        name="extraction-receipt.json",
    )
    recovery_receipt = _artifact_document(
        source_directory / "extraction-recovery-receipt.json",
        name="extraction-recovery-receipt.json",
    )
    if (
        extraction_receipt["sha256"] != binding.extraction_receipt_sha256
        or binding.recovery_receipt_sha256 is None
        or recovery_receipt["sha256"] != binding.recovery_receipt_sha256
    ):
        raise GlobalPlacePackageError(
            "reclassification source receipt identity differs from its authenticated binding"
        )
    return {
        "candidateOpl": {
            "bytes": binding.opl_bytes,
            "name": "place-nodes.opl",
            "sha256": binding.opl_sha256,
        },
        "candidatePbf": {
            "bytes": binding.candidate_pbf_bytes,
            "name": "place-nodes.pbf",
            "sha256": binding.candidate_pbf_sha256,
        },
        "directory": os.path.abspath(source_directory),
        "extractionReceipt": extraction_receipt,
        "planet": {
            "bytes": binding.planet_bytes,
            "path": binding.planet_path,
            "sha256": binding.planet_sha256,
        },
        "recoveryReceipt": recovery_receipt,
        "semanticAdmission": {
            "audit": binding.semantic_admission.document(),
            "policySha256": binding.semantic_admission_policy_sha256,
        },
    }


def _output_document(output_directory: Path) -> dict[str, object]:
    return {
        "inventory": list(_INVENTORY),
        "name": output_directory.name,
        "path": os.path.abspath(output_directory),
        "role": "current-policy semantic reclassification of one authenticated recovered extraction",
    }


def _provenance_document() -> dict[str, object]:
    return {
        "copiedArtifacts": (
            "PBF and OPL were stream-copied and rehashed from the exact authenticated v2 source"
        ),
        "oldSemanticOutcomes": "not trusted and not copied",
        "osmiumCommandsExecuted": False,
        "semanticOutcomes": (
            "recomputed during the bounded OPL copy with current code and presentation policy"
        ),
        "sourceMutation": "none; authenticated source was opened read-only",
    }


def _partial_path(
    output_directory: Path,
    *,
    source: Mapping[str, object],
    code: Mapping[str, object],
    runtime: Mapping[str, object],
    semantic_contract: Mapping[str, object],
) -> Path:
    identity = {
        "code": dict(code),
        "output": _output_document(output_directory),
        "runtime": dict(runtime),
        "schema": "flightalert.experiment8.osm-global-place-reclassification-run.v1",
        "semanticContract": dict(semantic_contract),
        "source": dict(source),
    }
    digest = hashlib.sha256(pipeline._canonical_json_bytes(identity)).hexdigest()
    return output_directory.with_name(
        output_directory.name + ".reclassification-partial-" + digest[:16]
    )


def _partial_siblings(output_directory: Path) -> tuple[Path, ...]:
    prefix = output_directory.name + ".reclassification-partial-"
    normalized_prefix = os.path.normcase(prefix)
    return tuple(
        sorted(
            (
                candidate
                for candidate in output_directory.parent.iterdir()
                if os.path.normcase(candidate.name).startswith(normalized_prefix)
            ),
            key=lambda candidate: candidate.name,
        )
    )


def _receipt_document(
    *,
    output_directory: Path,
    source: Mapping[str, object],
    code: Mapping[str, object],
    runtime: Mapping[str, object],
    artifacts: Mapping[str, object],
    strict_audit: Mapping[str, object],
    semantic_audit: Mapping[str, object],
    outcome_audit: Mapping[str, object],
    semantic_contract: Mapping[str, object],
) -> dict[str, object]:
    return {
        "artifacts": dict(artifacts),
        "code": dict(code),
        "output": _output_document(output_directory),
        "presentationPolicySha256": semantic_contract["presentationPolicySha256"],
        "provenance": _provenance_document(),
        "rendererSemanticOutcome": dict(outcome_audit),
        "runtime": dict(runtime),
        "schema": _RECLASSIFICATION_SCHEMA,
        "semanticAdmissionAudit": dict(semantic_audit),
        "semanticAdmissionPolicy": {
            "document": pipeline._SEMANTIC_ADMISSION_POLICY_DOCUMENT,
            "sha256": pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        },
        "semanticContract": dict(semantic_contract),
        "source": dict(source),
        "strictOplAudit": dict(strict_audit),
    }


def _authenticated_source(
    source_directory: Path,
    source_binding_loader: SourceBindingLoader,
) -> tuple[PlaceSourceBinding, dict[str, object]]:
    recovery._require_real_directory(source_directory, "reclassification source")
    binding = source_binding_loader(source_directory)
    if not isinstance(binding, PlaceSourceBinding):
        raise GlobalPlacePackageError(
            "reclassification source loader returned an invalid binding"
        )
    return binding, _source_document(source_directory, binding)


def _read_canonical_reclassification_receipt(
    path: Path,
) -> tuple[dict[str, object], pipeline.FileIdentity]:
    limit = 16 * 1024 * 1024
    if recovery._is_link_or_reparse(path) or not path.is_file():
        raise GlobalPlacePackageError(
            "reclassification receipt is not one regular non-link file"
        )
    before_path = path.stat()
    with path.open("rb") as handle:
        before_handle = os.fstat(handle.fileno())
        if (
            not stat.S_ISREG(before_handle.st_mode)
            or (before_handle.st_dev, before_handle.st_ino)
            != (before_path.st_dev, before_path.st_ino)
        ):
            raise GlobalPlacePackageError(
                "reclassification receipt changed while opening"
            )
        raw = handle.read(limit + 1)
        after_handle = os.fstat(handle.fileno())
    after_path = path.stat()
    signature = pipeline._stat_signature(before_path)
    if (
        len(raw) > limit
        or len(raw) != before_path.st_size
        or recovery._is_link_or_reparse(path)
        or signature != pipeline._stat_signature(before_handle)
        or signature != pipeline._stat_signature(after_handle)
        or signature != pipeline._stat_signature(after_path)
    ):
        raise GlobalPlacePackageError(
            "reclassification receipt drifted while reading"
        )
    try:
        document = json.loads(raw.decode("utf-8", "strict"))
    except (UnicodeError, ValueError, json.JSONDecodeError) as error:
        raise GlobalPlacePackageError("reclassification receipt is malformed") from error
    if (
        not isinstance(document, dict)
        or pipeline._canonical_json_bytes(document) != raw
        or document.get("schema") != _RECLASSIFICATION_SCHEMA
    ):
        raise GlobalPlacePackageError(
            "reclassification receipt schema or canonical bytes differ"
        )
    return document, pipeline.FileIdentity(
        path=path,
        bytes=len(raw),
        sha256=hashlib.sha256(raw).hexdigest(),
        stat_signature=signature,
    )


def _verified_opl_semantics(
    path: Path,
    binding: PlaceSourceBinding,
) -> tuple[
    pipeline.FileIdentity,
    dict[str, object],
    dict[str, object],
    dict[str, object],
]:
    from . import osm_global_place_store as store

    if recovery._is_link_or_reparse(path) or not path.is_file():
        raise GlobalPlacePackageError(
            "reclassification OPL is not one regular non-link file"
        )
    before_path = path.stat()
    with path.open("rb") as handle:
        before_handle = os.fstat(handle.fileno())
        signature = pipeline._stat_signature(before_handle)
        if (
            not stat.S_ISREG(before_handle.st_mode)
            or (before_handle.st_dev, before_handle.st_ino)
            != (before_path.st_dev, before_path.st_ino)
            or before_handle.st_size != binding.opl_bytes
        ):
            raise GlobalPlacePackageError(
                "reclassification OPL changed while opening"
            )
        reader = _HashingReader(handle)
        strict, semantic, outcome = store._semantic_outcome_audits_stream(
            reader,
            source_generation_sha256=binding.planet_sha256,
        )
        after_handle = os.fstat(handle.fileno())
    after_path = path.stat()
    if (
        recovery._is_link_or_reparse(path)
        or signature != pipeline._stat_signature(before_path)
        or signature != pipeline._stat_signature(after_handle)
        or signature != pipeline._stat_signature(after_path)
        or reader.total != binding.opl_bytes
        or reader.digest.hexdigest() != binding.opl_sha256
    ):
        raise GlobalPlacePackageError(
            "reclassification OPL drifted during semantic validation"
        )
    return (
        pipeline.FileIdentity(
            path=path,
            bytes=reader.total,
            sha256=reader.digest.hexdigest(),
            stat_signature=signature,
        ),
        strict,
        semantic,
        outcome,
    )


def _validate_reclassification_output(
    directory: Path,
    *,
    source_directory: Path,
    output_directory: Path,
    source_binding_loader: SourceBindingLoader,
    expected_semantic: tuple[
        Mapping[str, object], Mapping[str, object], Mapping[str, object]
    ]
    | None = None,
    authenticated_source: tuple[PlaceSourceBinding, Mapping[str, object]]
    | None = None,
) -> tuple[dict[str, object], pipeline.FileIdentity, PlaceSourceBinding]:
    if authenticated_source is None:
        binding, source = _authenticated_source(
            source_directory, source_binding_loader
        )
    else:
        binding, source_document = authenticated_source
        if not isinstance(binding, PlaceSourceBinding):
            raise GlobalPlacePackageError(
                "reclassification authenticated source binding is invalid"
            )
        source = dict(source_document)
    code = _code_identities()
    runtime = _runtime_document()
    semantic_contract = _semantic_contract()
    recovery._require_real_directory(directory, "reclassified extraction")
    if recovery._directory_names(directory) != _INVENTORY:
        raise GlobalPlacePackageError("reclassified extraction inventory differs")
    receipt, receipt_identity = _read_canonical_reclassification_receipt(
        directory / "reclassification-receipt.json"
    )
    pbf = pipeline.verify_file_identity(
        directory / "place-nodes.pbf",
        expected_bytes=binding.candidate_pbf_bytes,
        expected_sha256=binding.candidate_pbf_sha256,
    )
    if expected_semantic is None:
        opl, actual_strict, actual_semantic, actual_outcome = (
            _verified_opl_semantics(
                directory / "place-nodes.opl",
                binding,
            )
        )
    else:
        opl = pipeline.verify_file_identity(
            directory / "place-nodes.opl",
            expected_bytes=binding.opl_bytes,
            expected_sha256=binding.opl_sha256,
        )
        actual_strict, actual_semantic, actual_outcome = (
            dict(document) for document in expected_semantic
        )
    strict = receipt.get("strictOplAudit")
    semantic = receipt.get("semanticAdmissionAudit")
    outcome = receipt.get("rendererSemanticOutcome")
    if not isinstance(strict, dict) or not isinstance(semantic, dict):
        raise GlobalPlacePackageError("reclassification semantic audits are malformed")
    admission = PlaceSemanticAdmissionAudit.from_documents(strict, semantic)
    if admission != binding.semantic_admission:
        raise GlobalPlacePackageError(
            "reclassification semantic admission differs from its source binding"
        )
    outcome_evidence = PlaceRendererSemanticOutcome.from_document(outcome)
    outcome_identity = pipeline.verify_file_identity(
        directory / "place-semantic-outcomes.bin",
        expected_bytes=(
            outcome_evidence.node_count
            * pipeline.RENDERER_SEMANTIC_OUTCOME_EVENT_BYTES
        ),
        expected_sha256=outcome_evidence.stream_sha256,
    )
    if (
        strict != actual_strict
        or semantic != actual_semantic
        or outcome != actual_outcome
    ):
        raise GlobalPlacePackageError(
            "reclassification semantic outcome differs from current code or policy"
        )
    if (
        _code_identities() != code
        or _runtime_document() != runtime
        or _semantic_contract() != semantic_contract
    ):
        raise GlobalPlacePackageError(
            "reclassification validation code, runtime, or semantic contract drifted"
        )
    artifacts = {
        "candidateOpl": {
            "bytes": opl.bytes,
            "name": "place-nodes.opl",
            "sha256": opl.sha256,
        },
        "candidatePbf": {
            "bytes": pbf.bytes,
            "name": "place-nodes.pbf",
            "sha256": pbf.sha256,
        },
        "rendererSemanticOutcomes": {
            "bytes": outcome_identity.bytes,
            "name": "place-semantic-outcomes.bin",
            "sha256": outcome_identity.sha256,
        },
    }
    expected = _receipt_document(
        output_directory=output_directory,
        source=source,
        code=code,
        runtime=runtime,
        artifacts=artifacts,
        strict_audit=actual_strict,
        semantic_audit=actual_semantic,
        outcome_audit=actual_outcome,
        semantic_contract=semantic_contract,
    )
    if receipt != expected:
        raise GlobalPlacePackageError(
            "reclassification receipt differs from the exact current contract"
        )
    return receipt, receipt_identity, binding


def _durable_rename(partial: Path, output: Path) -> None:
    if os.name == "nt":
        import ctypes

        move_file = ctypes.WinDLL("kernel32", use_last_error=True).MoveFileExW
        move_file.argtypes = (ctypes.c_wchar_p, ctypes.c_wchar_p, ctypes.c_uint32)
        move_file.restype = ctypes.c_int
        if not move_file(str(partial), str(output), 0x00000008):
            raise OSError(ctypes.get_last_error(), "MoveFileExW failed")
        return
    descriptor = os.open(partial, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    import ctypes

    libc = ctypes.CDLL(None, use_errno=True)
    rename_noreplace = getattr(libc, "renameat2", None)
    if rename_noreplace is None:
        raise OSError(
            errno.ENOTSUP,
            "atomic no-replace directory rename is unavailable",
        )
    rename_noreplace.argtypes = (
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    )
    rename_noreplace.restype = ctypes.c_int
    if rename_noreplace(
        -100,
        os.fsencode(partial),
        -100,
        os.fsencode(output),
        1,
    ) != 0:
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number))
    descriptor = os.open(output.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _publish_no_clobber(partial: Path, output: Path) -> None:
    if output.exists() or recovery._is_link_or_reparse(output):
        raise GlobalPlacePackageError("reclassification output already exists")
    try:
        _durable_rename(partial, output)
    except OSError as error:
        raise GlobalPlacePackageError(
            "atomic reclassification publication failed"
        ) from error


def _reclassify_recovered_extraction(
    *,
    source_directory: Path,
    output_directory: Path,
    source_binding_loader: SourceBindingLoader,
) -> GlobalPlaceExtractionResult:
    if not isinstance(source_directory, Path) or not isinstance(output_directory, Path):
        raise GlobalPlacePackageError("reclassification paths must be pathlib.Path values")
    recovery._require_real_directory(source_directory, "reclassification source")
    if source_directory.parent.resolve() != output_directory.parent.resolve():
        raise GlobalPlacePackageError(
            "reclassification output must be a sibling of its source"
        )
    if output_directory.exists() or recovery._is_link_or_reparse(output_directory):
        raise GlobalPlacePackageError("reclassification output already exists")
    binding, source = _authenticated_source(source_directory, source_binding_loader)
    code = _code_identities()
    runtime = _runtime_document()
    semantic_contract = _semantic_contract()
    partial = _partial_path(
        output_directory,
        source=source,
        code=code,
        runtime=runtime,
        semantic_contract=semantic_contract,
    )
    siblings = _partial_siblings(output_directory)
    if siblings not in ((), (partial,)):
        raise GlobalPlacePackageError(
            "reclassification output has an ambiguous or unowned partial sibling"
        )
    if siblings == (partial,):
        receipt, _, validated_binding = _validate_reclassification_output(
            partial,
            source_directory=source_directory,
            output_directory=output_directory,
            source_binding_loader=source_binding_loader,
        )
        final_binding, final_source = _authenticated_source(
            source_directory, source_binding_loader
        )
        if final_binding != validated_binding or final_source != receipt["source"]:
            raise GlobalPlacePackageError(
                "authenticated reclassification source changed before publication"
            )
        _publish_no_clobber(partial, output_directory)
        published, _, _ = _validate_reclassification_output(
            output_directory,
            source_directory=source_directory,
            output_directory=output_directory,
            source_binding_loader=source_binding_loader,
            expected_semantic=(
                receipt["strictOplAudit"],
                receipt["semanticAdmissionAudit"],
                receipt["rendererSemanticOutcome"],
            ),
            authenticated_source=(final_binding, final_source),
        )
        return GlobalPlaceExtractionResult(
            "complete", output_directory, MappingProxyType(published)
        )

    try:
        partial.mkdir(parents=False, exist_ok=False)
    except FileExistsError as error:
        raise GlobalPlacePackageError(
            "reclassification partial appeared concurrently"
        ) from error
    except OSError as error:
        raise GlobalPlacePackageError(
            "reclassification partial could not be created"
        ) from error
    recovery._require_real_directory(partial, "reclassification partial")
    pbf_identity = recovery._copy_verified(
        source_directory / "place-nodes.pbf",
        partial / "place-nodes.pbf",
        {
            "bytes": binding.candidate_pbf_bytes,
            "sha256": binding.candidate_pbf_sha256,
        },
    )
    (
        opl_identity,
        outcome_identity,
        strict_audit,
        semantic_audit,
        outcome_audit,
    ) = recovery._copy_and_audit_opl(
        source_directory / "place-nodes.opl",
        partial / "place-nodes.opl",
        partial / "place-semantic-outcomes.bin",
        {"bytes": binding.opl_bytes, "sha256": binding.opl_sha256},
        source_generation_sha256=binding.planet_sha256,
    )
    if (
        PlaceSemanticAdmissionAudit.from_documents(strict_audit, semantic_audit)
        != binding.semantic_admission
    ):
        raise GlobalPlacePackageError(
            "reclassification semantic admission differs from its source binding"
        )
    artifacts = {
        "candidateOpl": opl_identity,
        "candidatePbf": pbf_identity,
        "rendererSemanticOutcomes": outcome_identity,
    }
    receipt = _receipt_document(
        output_directory=output_directory,
        source=source,
        code=code,
        runtime=runtime,
        artifacts=artifacts,
        strict_audit=strict_audit,
        semantic_audit=semantic_audit,
        outcome_audit=outcome_audit,
        semantic_contract=semantic_contract,
    )
    recovery._write_fsynced(
        partial / "reclassification-receipt.json",
        pipeline._canonical_json_bytes(receipt),
    )
    if (
        _code_identities() != code
        or _runtime_document() != runtime
        or _semantic_contract() != semantic_contract
    ):
        raise GlobalPlacePackageError(
            "reclassifier code, runtime, or semantic contract drifted during processing"
        )
    validated, _, _ = _validate_reclassification_output(
        partial,
        source_directory=source_directory,
        output_directory=output_directory,
        source_binding_loader=source_binding_loader,
        expected_semantic=(strict_audit, semantic_audit, outcome_audit),
        authenticated_source=(binding, source),
    )
    final_binding, final_source = _authenticated_source(
        source_directory, source_binding_loader
    )
    if final_binding != binding or final_source != source:
        raise GlobalPlacePackageError(
            "authenticated reclassification source changed during processing"
        )
    _publish_no_clobber(partial, output_directory)
    published, _, _ = _validate_reclassification_output(
        output_directory,
        source_directory=source_directory,
        output_directory=output_directory,
        source_binding_loader=source_binding_loader,
        expected_semantic=(strict_audit, semantic_audit, outcome_audit),
        authenticated_source=(final_binding, final_source),
    )
    if validated != published:
        raise GlobalPlacePackageError(
            "published reclassification receipt differs after atomic rename"
        )
    return GlobalPlaceExtractionResult(
        "complete", output_directory, MappingProxyType(published)
    )


def reclassify_retained_extraction() -> GlobalPlaceExtractionResult:
    return _reclassify_recovered_extraction(
        source_directory=_EXACT_SOURCE_PATH,
        output_directory=_EXACT_OUTPUT_PATH,
        source_binding_loader=_source_binding_from_historical_recovery,
    )


def _source_binding_from_reclassified_extraction(
    extraction_directory: Path,
    *,
    source_directory: Path,
    output_directory: Path,
    source_binding_loader: SourceBindingLoader,
) -> PlaceSourceBinding:
    receipt, receipt_identity, source_binding = _validate_reclassification_output(
        extraction_directory,
        source_directory=source_directory,
        output_directory=output_directory,
        source_binding_loader=source_binding_loader,
    )
    final_source_binding, final_source = _authenticated_source(
        source_directory, source_binding_loader
    )
    if final_source_binding != source_binding or final_source != receipt["source"]:
        raise GlobalPlacePackageError(
            "authenticated reclassification source changed during v3 validation"
        )
    artifacts = receipt["artifacts"]
    outcome = PlaceRendererSemanticOutcome.from_document(
        receipt["rendererSemanticOutcome"]
    )
    return PlaceSourceBinding(
        planet_path=final_source_binding.planet_path,
        planet_bytes=final_source_binding.planet_bytes,
        planet_sha256=final_source_binding.planet_sha256,
        candidate_pbf_bytes=artifacts["candidatePbf"]["bytes"],
        candidate_pbf_sha256=artifacts["candidatePbf"]["sha256"],
        opl_bytes=artifacts["candidateOpl"]["bytes"],
        opl_sha256=artifacts["candidateOpl"]["sha256"],
        extraction_receipt_sha256=final_source_binding.extraction_receipt_sha256,
        recovery_receipt_sha256=receipt_identity.sha256,
        renderer_semantic_outcome_path=str(
            extraction_directory / "place-semantic-outcomes.bin"
        ),
        renderer_semantic_outcome_bytes=(
            artifacts["rendererSemanticOutcomes"]["bytes"]
        ),
        renderer_semantic_outcome=outcome,
        semantic_admission_policy_sha256=(
            final_source_binding.semantic_admission_policy_sha256
        ),
        semantic_admission=final_source_binding.semantic_admission,
    )


def source_binding_from_reclassified_extraction(
    extraction_directory: Path,
) -> PlaceSourceBinding:
    recovery._require_exact_path(
        extraction_directory,
        _EXACT_OUTPUT_PATH,
        "reclassified extraction",
    )
    return _source_binding_from_reclassified_extraction(
        _EXACT_OUTPUT_PATH,
        source_directory=_EXACT_SOURCE_PATH,
        output_directory=_EXACT_OUTPUT_PATH,
        source_binding_loader=_source_binding_from_historical_recovery,
    )


__all__ = [
    "reclassify_retained_extraction",
    "source_binding_from_reclassified_extraction",
]
