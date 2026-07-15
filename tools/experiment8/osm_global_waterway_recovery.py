from __future__ import annotations

import hashlib
import os
import re
import sqlite3
import stat
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from types import MappingProxyType
from typing import Callable, Mapping, Sequence

from . import osm_global_waterway_package as source_module
from . import osm_global_waterway_store as store
from . import reference_size_policy as size_policy_module
from .osm_global_waterway_package import (
    GlobalWaterwayBuildResult,
    GlobalWaterwayPackageError,
    WaterwaySourceBinding,
)


_BACKUP_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-render-recovery-backup.v1"
)
_RECOVERY_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-render-recovery.v1"
)
_VALIDATION_SCHEMA = (
    "flightalert.experiment8.osm-global-waterway-render-recovery-validation.v1"
)
_TIMESTAMP = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z\Z"
)
_META_KEYS = (
    "runIdentity",
    "checkpoint",
    "admissionRunIdentity",
    "admissionCheckpoint",
    "admissionReceipt",
    "ingestReceipt",
    "renderRunIdentity",
    "renderCheckpoint",
)
_PRESERVED_META_KEYS = _META_KEYS[:6]
_SOURCE_TABLES = (
    "roots",
    "nodes",
    "ways",
    "way_nodes",
    "relations",
    "relation_members",
    "admission_roots",
    "admission_candidates",
)
_RENDERER_TABLES = (
    "records",
    "rendered_features",
    "feature_ids",
    "variant_ids",
    "geometry_ids",
    "label_ids",
    "sourced_ids",
)
_RENDERER_PEAK_KEYS = (
    "compressedTileBytes",
    "featurePostingBytes",
    "rawTileBytes",
    "recordsPerTile",
)


def _source_binding_from_recovery_extraction(
    extraction_directory: Path,
) -> WaterwaySourceBinding:
    if (
        not isinstance(extraction_directory, Path)
        or not extraction_directory.is_dir()
        or extraction_directory.is_symlink()
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery extraction directory is not one real directory"
        )
    source_module._require_exact_directory_inventory(
        extraction_directory,
        source_module._extraction_inventory_names(),
        "waterway recovery extraction directory",
    )
    receipt_path = extraction_directory / "extraction-receipt.json"
    receipt_identity = source_module._stream_identity(
        receipt_path, "waterway recovery extraction receipt"
    )
    receipt = source_module._strict_json(
        source_module._read_bounded(
            receipt_path,
            16 * 1024 * 1024,
            "waterway recovery extraction receipt",
        ),
        "waterway recovery extraction receipt",
    )
    if (
        receipt.get("schema")
        != "flightalert.experiment8.osm-global-waterway-extraction.v1"
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery extraction receipt schema is unsupported"
        )
    source = receipt.get("source")
    artifacts = receipt.get("artifacts")
    if type(source) is not dict or type(artifacts) is not dict:
        raise GlobalWaterwayPackageError(
            "waterway recovery extraction source/artifacts are malformed"
        )
    source_fact = source_module._require_sha_fact(
        {"bytes": source.get("bytes"), "sha256": source.get("sha256")},
        "waterway recovery planet",
    )
    if (
        source_fact
        != {
            "bytes": source_module.EXPECTED_PLANET_BYTES,
            "sha256": source_module.EXPECTED_PLANET_SHA256,
        }
        or source.get("path") != str(source_module.EXPECTED_PLANET_PATH)
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery extraction is not the exact 260629 planet"
        )
    expected_names = {
        "checkpoint": "extraction-checkpoint.json",
        "closureOpl": "waterway-closure.opl",
        "closurePbf": "waterway-closure.pbf",
        "rootIds": "root-ids.txt",
        "selectionManifest": "selection-final-manifest.json",
    }
    facts: dict[str, dict[str, object]] = {}
    for key, name in expected_names.items():
        item = artifacts.get(key)
        if type(item) is not dict or item.get("name") != name:
            raise GlobalWaterwayPackageError(
                f"waterway recovery extraction {key} is malformed"
            )
        fact = source_module._require_sha_fact(
            {"bytes": item.get("bytes"), "sha256": item.get("sha256")},
            f"waterway recovery extraction {key}",
        )
        if key not in {"rootIds", "closureOpl"}:
            if source_module._stream_identity(
                extraction_directory / name,
                f"waterway recovery extraction {key}",
            ) != fact:
                raise GlobalWaterwayPackageError(
                    f"waterway recovery extraction {key} identity differs"
                )
        facts[key] = fact
    return WaterwaySourceBinding(
        planet_path=str(source["path"]),
        planet_bytes=int(source_fact["bytes"]),
        planet_sha256=str(source_fact["sha256"]),
        selection_manifest_sha256=str(facts["selectionManifest"]["sha256"]),
        selection_policy_sha256=source_module.GLOBAL_POLICY_SHA256,
        root_ids_bytes=int(facts["rootIds"]["bytes"]),
        root_ids_sha256=str(facts["rootIds"]["sha256"]),
        closure_pbf_bytes=int(facts["closurePbf"]["bytes"]),
        closure_pbf_sha256=str(facts["closurePbf"]["sha256"]),
        closure_opl_bytes=int(facts["closureOpl"]["bytes"]),
        closure_opl_sha256=str(facts["closureOpl"]["sha256"]),
        extraction_receipt_sha256=str(receipt_identity["sha256"]),
    )


def _require_sha256(value: object, label: str) -> str:
    return source_module._require_sha256(value, label)


def _fact_document(byte_count: int, sha256: str) -> dict[str, object]:
    return {"bytes": byte_count, "sha256": sha256}


@dataclass(frozen=True, slots=True)
class WaterwayRenderRecoveryAuthority:
    package_id: str
    checkpoint_features: int
    rendered_features: int
    database_bytes: int
    database_sha256: str
    failure_log_bytes: int
    failure_log_sha256: str
    meta_identities: tuple[tuple[str, int, str], ...]
    source_table_counts: tuple[tuple[str, int], ...]
    renderer_table_counts: tuple[tuple[str, int], ...]
    predecessor_size_policy_mode: str = size_policy_module.BUDGETED_RELEASE_V1
    predecessor_size_policy_identity_bound: bool = True
    intended_size_policy_mode: str = size_policy_module.BUDGETED_RELEASE_V1

    def __post_init__(self) -> None:
        if (
            type(self.package_id) is not str
            or not self.package_id
            or type(self.checkpoint_features) is not int
            or self.checkpoint_features <= 0
            or type(self.rendered_features) is not int
            or self.rendered_features <= 0
        ):
            raise GlobalWaterwayPackageError(
                "waterway render recovery authority has invalid package/checkpoint facts"
            )
        for value, label in (
            (self.database_bytes, "recovery database bytes"),
            (self.failure_log_bytes, "recovery failure-log bytes"),
        ):
            if type(value) is not int or value <= 0:
                raise GlobalWaterwayPackageError(f"{label} must be positive")
        _require_sha256(self.database_sha256, "recovery database SHA-256")
        _require_sha256(self.failure_log_sha256, "recovery failure-log SHA-256")
        if tuple(item[0] for item in self.meta_identities) != _META_KEYS:
            raise GlobalWaterwayPackageError(
                "waterway render recovery meta authority is incomplete or unordered"
            )
        for key, byte_count, sha256 in self.meta_identities:
            if type(byte_count) is not int or byte_count <= 0:
                raise GlobalWaterwayPackageError(
                    f"waterway recovery {key} byte count must be positive"
                )
            _require_sha256(sha256, f"waterway recovery {key} SHA-256")
        for values, expected, label in (
            (self.source_table_counts, _SOURCE_TABLES, "source"),
            (self.renderer_table_counts, _RENDERER_TABLES, "renderer"),
        ):
            if tuple(item[0] for item in values) != expected:
                raise GlobalWaterwayPackageError(
                    f"waterway render recovery {label} table authority differs"
                )
            if any(type(item[1]) is not int or item[1] < 0 for item in values):
                raise GlobalWaterwayPackageError(
                    f"waterway render recovery {label} count is invalid"
                )
        renderer = dict(self.renderer_table_counts)
        if renderer["rendered_features"] != self.rendered_features:
            raise GlobalWaterwayPackageError(
                "waterway render recovery feature checkpoint/count authority differs"
            )
        try:
            size_policy_module.normalize_reference_size_policy_mode(
                self.predecessor_size_policy_mode
            )
            size_policy_module.normalize_reference_size_policy_mode(
                self.intended_size_policy_mode
            )
        except size_policy_module.ReferenceSizePolicyError as error:
            raise GlobalWaterwayPackageError(str(error)) from error
        if type(self.predecessor_size_policy_identity_bound) is not bool:
            raise GlobalWaterwayPackageError(
                "waterway render recovery predecessor size-policy binding is malformed"
            )
        if not self.predecessor_size_policy_identity_bound and (
            self.predecessor_size_policy_mode
            != size_policy_module.BUDGETED_RELEASE_V1
            or self.intended_size_policy_mode
            != size_policy_module.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
        ):
            raise GlobalWaterwayPackageError(
                "legacy waterway recovery must transition from budgeted release "
                "to complete visual evaluation"
            )

    def document(self) -> dict[str, object]:
        return {
            "checkpointFeatures": self.checkpoint_features,
            "database": _fact_document(
                self.database_bytes, self.database_sha256
            ),
            "failureLog": _fact_document(
                self.failure_log_bytes, self.failure_log_sha256
            ),
            "meta": {
                key: _fact_document(byte_count, sha256)
                for key, byte_count, sha256 in self.meta_identities
            },
            "packageId": self.package_id,
            "renderedFeatures": self.rendered_features,
            "rendererTableCounts": dict(self.renderer_table_counts),
            "schema": (
                "flightalert.experiment8.osm-global-waterway-"
                "render-recovery-authority.v1"
            ),
            "sourceTableCounts": dict(self.source_table_counts),
            "sizePolicyTransition": {
                "intendedMode": self.intended_size_policy_mode,
                "predecessorIdentityBound": (
                    self.predecessor_size_policy_identity_bound
                ),
                "predecessorMode": self.predecessor_size_policy_mode,
            },
        }

    @property
    def policy_sha256(self) -> str:
        return hashlib.sha256(
            source_module._canonical_json_bytes(self.document())
        ).hexdigest()


@dataclass(frozen=True, slots=True)
class _RecoveryPlan:
    resumed: bool
    recovery_receipt: Mapping[str, object]
    new_render_identity: Mapping[str, object]
    new_render_sha256: str
    incident_meta: tuple[tuple[str, bytes], ...]
    preserved_meta: tuple[tuple[str, bytes], ...]
    source_counts: tuple[tuple[str, int], ...]
    renderer_reset_counts: tuple[tuple[str, int], ...]


def _raw_meta(
    connection: sqlite3.Connection,
    key: str,
    *,
    required: bool = True,
) -> tuple[bytes, dict[str, object]] | None:
    row = connection.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    if row is None:
        if required:
            raise GlobalWaterwayPackageError(
                f"waterway recovery required meta {key} is absent"
            )
        return None
    raw = store._sqlite_blob(row[0], f"recovery meta {key}")
    document = source_module._strict_json(raw, f"waterway recovery meta {key}")
    return raw, document


def _identity(raw: bytes) -> dict[str, object]:
    return {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def _require_meta_fact(
    raw: bytes,
    *,
    key: str,
    authority: WaterwayRenderRecoveryAuthority,
) -> None:
    expected = {
        item[0]: _fact_document(item[1], item[2])
        for item in authority.meta_identities
    }[key]
    if _identity(raw) != expected:
        raise GlobalWaterwayPackageError(
            f"waterway recovery {key} identity differs from exact incident"
        )


def _table_counts(
    connection: sqlite3.Connection, tables: Sequence[str]
) -> tuple[tuple[str, int], ...]:
    return tuple(
        (
            table,
            int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]),
        )
        for table in tables
    )


def _require_source_counts(
    connection: sqlite3.Connection,
    authority: WaterwayRenderRecoveryAuthority,
) -> tuple[tuple[str, int], ...]:
    actual = _table_counts(connection, _SOURCE_TABLES)
    if actual != authority.source_table_counts:
        raise GlobalWaterwayPackageError(
            "waterway recovery source/admission table counts differ from exact incident"
        )
    return actual


def _require_renderer_counts(
    connection: sqlite3.Connection,
    authority: WaterwayRenderRecoveryAuthority,
) -> tuple[tuple[str, int], ...]:
    actual = _table_counts(connection, _RENDERER_TABLES)
    if actual != authority.renderer_table_counts:
        raise GlobalWaterwayPackageError(
            "waterway recovery renderer table counts differ from exact incident"
        )
    return actual


def _require_no_sidecars(database_path: Path) -> None:
    for suffix in ("-journal", "-wal", "-shm"):
        sidecar = Path(str(database_path) + suffix)
        if sidecar.exists() or sidecar.is_symlink():
            raise GlobalWaterwayPackageError(
                f"waterway recovery SQLite sidecar exists: {sidecar.name}"
            )


def _partial_directories(output_directory: Path) -> tuple[Path, ...]:
    try:
        return tuple(
            sorted(
                output_directory.parent.glob(output_directory.name + ".partial-*"),
                key=lambda path: path.name,
            )
        )
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "waterway recovery partial-directory inventory is unreadable"
        ) from error


def _require_owned_resume_partial(
    connection: sqlite3.Connection,
    partial_directory: Path,
) -> None:
    expected_owner = {
        "path": os.path.abspath(partial_directory),
        "schema": store._PARTIAL_OWNER_SCHEMA,
    }
    if store._meta_get(connection, "partialDirectoryOwner") != expected_owner:
        raise GlobalWaterwayPackageError(
            "waterway recovery resume partial owner differs"
        )
    try:
        partial_status = os.lstat(partial_directory)
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "waterway recovery resume partial owner path is unreadable"
        ) from error
    if (
        not stat.S_ISDIR(partial_status.st_mode)
        or getattr(partial_status, "st_file_attributes", 0) & store._REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery resume partial owner path is not one directory"
        )
    allowed_names = {
        "build-receipt.json",
        "manifest.json",
        "records.fadictpack",
        "tile-index.bin",
    }
    try:
        entries = tuple(partial_directory.iterdir())
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "waterway recovery resume partial inventory is unreadable"
        ) from error
    for entry in entries:
        try:
            entry_status = os.lstat(entry)
        except OSError as error:
            raise GlobalWaterwayPackageError(
                "waterway recovery resume partial inventory is unreadable"
            ) from error
        if (
            entry.name not in allowed_names
            or not stat.S_ISREG(entry_status.st_mode)
            or getattr(entry_status, "st_file_attributes", 0) & store._REPARSE_POINT
        ):
            raise GlobalWaterwayPackageError(
                "waterway recovery resume partial inventory differs"
            )
    checkpoint = store._meta_get(connection, "buildCheckpoint")
    if checkpoint is None:
        checkpoint = {
            "indexBytes": 0,
            "indexSha256": hashlib.sha256(b"").hexdigest(),
            "nextOrdinal": 0,
            "recordsBytes": 0,
            "recordsSha256": hashlib.sha256(b"").hexdigest(),
        }
    expected_keys = {
        "indexBytes",
        "indexSha256",
        "nextOrdinal",
        "recordsBytes",
        "recordsSha256",
    }
    if not isinstance(checkpoint, dict) or set(checkpoint) != expected_keys:
        raise GlobalWaterwayPackageError(
            "waterway recovery resume runtime checkpoint is malformed"
        )
    next_ordinal = checkpoint.get("nextOrdinal")
    index_bytes = checkpoint.get("indexBytes")
    if (
        type(next_ordinal) is not int
        or next_ordinal < 0
        or type(index_bytes) is not int
        or index_bytes != next_ordinal * store.INDEX_ENTRY_BYTES
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery resume runtime index checkpoint is inconsistent"
        )
    for label, file_name, bytes_key, sha_key in (
        ("records", "records.fadictpack", "recordsBytes", "recordsSha256"),
        ("index", "tile-index.bin", "indexBytes", "indexSha256"),
    ):
        byte_count = checkpoint.get(bytes_key)
        expected_sha256 = checkpoint.get(sha_key)
        if (
            type(byte_count) is not int
            or byte_count < 0
            or type(expected_sha256) is not str
            or len(expected_sha256) != 64
            or any(character not in "0123456789abcdef" for character in expected_sha256)
        ):
            raise GlobalWaterwayPackageError(
                f"waterway recovery resume {label} checkpoint is malformed"
            )
        path = partial_directory / file_name
        if not path.exists():
            if byte_count:
                raise GlobalWaterwayPackageError(
                    f"waterway recovery resume {label} prefix is missing"
                )
            if expected_sha256 != hashlib.sha256(b"").hexdigest():
                raise GlobalWaterwayPackageError(
                    f"waterway recovery resume {label} prefix SHA-256 differs"
                )
            continue
        digest = hashlib.sha256()
        remaining = byte_count
        try:
            with path.open("rb") as handle:
                while remaining:
                    block = handle.read(min(1024 * 1024, remaining))
                    if not block:
                        raise GlobalWaterwayPackageError(
                            f"waterway recovery resume {label} prefix ends early"
                        )
                    digest.update(block)
                    remaining -= len(block)
        except OSError as error:
            raise GlobalWaterwayPackageError(
                f"waterway recovery resume {label} prefix is unreadable"
            ) from error
        if digest.hexdigest() != expected_sha256:
            raise GlobalWaterwayPackageError(
                f"waterway recovery resume {label} prefix SHA-256 differs"
            )


def _require_external_evidence(
    *,
    failure_log: Path,
    backup_receipt: Path,
    authority: WaterwayRenderRecoveryAuthority,
) -> Mapping[str, object]:
    failure_identity = source_module._stream_identity(
        failure_log, "waterway recovery failure log"
    )
    expected_failure = _fact_document(
        authority.failure_log_bytes, authority.failure_log_sha256
    )
    if failure_identity != expected_failure:
        raise GlobalWaterwayPackageError(
            "waterway recovery failure-log identity differs from exact incident"
        )
    failure_raw = source_module._read_bounded(
        failure_log, authority.failure_log_bytes, "waterway recovery failure log"
    )
    if not failure_raw.endswith(
        (
            b"sqlite3.OperationalError: database is locked\n",
            b"sqlite3.OperationalError: database is locked\r\n",
        )
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery failure class/message differs from exact incident"
        )
    receipt = source_module._strict_json(
        source_module._read_bounded(
            backup_receipt, 64 * 1024, "waterway recovery backup receipt"
        ),
        "waterway recovery backup receipt",
    )
    database_fact = _fact_document(
        authority.database_bytes, authority.database_sha256
    )
    expected = {
        "backupDatabase": database_fact,
        "failureLog": expected_failure,
        "schema": _BACKUP_SCHEMA,
        "sourceDatabase": database_fact,
        "state": "complete",
    }
    if receipt != expected:
        raise GlobalWaterwayPackageError(
            "waterway recovery backup receipt differs from exact verified backup"
        )
    return MappingProxyType(receipt)


def _require_timestamp(value: object) -> str:
    if type(value) is not str or _TIMESTAMP.fullmatch(value) is None:
        raise GlobalWaterwayPackageError(
            "waterway recovery receipt timestamp is malformed"
        )
    try:
        parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as error:
        raise GlobalWaterwayPackageError(
            "waterway recovery receipt timestamp is malformed"
        ) from error
    if parsed.strftime("%Y-%m-%dT%H:%M:%SZ") != value:
        raise GlobalWaterwayPackageError(
            "waterway recovery receipt timestamp is not canonical UTC"
        )
    return value


def _recovery_code_identity() -> dict[str, object]:
    return source_module._stream_identity(
        Path(__file__).resolve(), "waterway recovery code"
    )


def _require_recovery_code_current(plan: _RecoveryPlan, boundary: str) -> None:
    if _recovery_code_identity() != plan.recovery_receipt.get("recoveryCode"):
        raise GlobalWaterwayPackageError(
            f"waterway recovery code identity drifted {boundary}"
        )


def _recovery_receipt_document(
    *,
    authority: WaterwayRenderRecoveryAuthority,
    recovered_at_utc: str,
    new_render_identity: Mapping[str, object],
    new_render_sha256: str,
    sqlite_evidence_overhead_bytes: int = 0,
) -> dict[str, object]:
    meta = {
        key: _fact_document(byte_count, sha256)
        for key, byte_count, sha256 in authority.meta_identities
    }
    recovery_code = _recovery_code_identity()
    document = {
        "authorityPolicySha256": authority.policy_sha256,
        "backupDatabase": _fact_document(
            authority.database_bytes, authority.database_sha256
        ),
        "databaseBeforeRecovery": _fact_document(
            authority.database_bytes, authority.database_sha256
        ),
        "failedRender": {
            "checkpoint": {
                "renderComplete": False,
                "renderedFeatures": authority.rendered_features,
            },
            "failureClass": "sqlite3.OperationalError",
            "failureLog": _fact_document(
                authority.failure_log_bytes, authority.failure_log_sha256
            ),
            "failureMessage": "database is locked",
            "renderRunIdentity": meta["renderRunIdentity"],
        },
        "newRenderRunIdentity": {
            "documentSha256": new_render_sha256,
            "schema": new_render_identity.get("schema"),
        },
        "preservedMeta": {key: meta[key] for key in _PRESERVED_META_KEYS},
        "recoveredAtUtc": recovered_at_utc,
        "recoveryCode": recovery_code,
        "rendererResetCounts": dict(authority.renderer_table_counts),
        "resetCount": 1,
        "schema": _RECOVERY_SCHEMA,
        "sourceTableCounts": dict(authority.source_table_counts),
        "sizePolicyTransition": {
            "intended": dict(new_render_identity["sizePolicy"]),
            "predecessor": {
                "identityBound": authority.predecessor_size_policy_identity_bound,
                "mode": authority.predecessor_size_policy_mode,
            },
        },
        "sqliteEvidenceOverheadBytes": sqlite_evidence_overhead_bytes,
        "transactionComplete": True,
    }
    return document


def _validate_complete_admission(
    *,
    source_binding: WaterwaySourceBinding,
    documents: Mapping[str, Mapping[str, object]],
    authority: WaterwayRenderRecoveryAuthority,
) -> None:
    checkpoint = documents["checkpoint"]
    admission_checkpoint = documents["admissionCheckpoint"]
    admission = documents["admissionReceipt"]
    ingest = documents["ingestReceipt"]
    render_identity = documents["renderRunIdentity"]
    predecessor_policy = render_identity.get("sizePolicy")
    if authority.predecessor_size_policy_identity_bound:
        try:
            expected_predecessor = dict(
                store._reference_size_policy_binding(
                    authority.predecessor_size_policy_mode
                )
            )
        except size_policy_module.ReferenceSizePolicyError as error:
            raise GlobalWaterwayPackageError(str(error)) from error
        if predecessor_policy != expected_predecessor:
            raise GlobalWaterwayPackageError(
                "waterway recovery predecessor size-policy identity differs"
            )
    elif predecessor_policy is not None:
        raise GlobalWaterwayPackageError(
            "legacy waterway recovery predecessor unexpectedly binds a size policy"
        )
    render_checkpoint = documents["renderCheckpoint"]
    if checkpoint.get("ingestComplete") is not True:
        raise GlobalWaterwayPackageError(
            "waterway recovery requires a complete exact ingest checkpoint"
        )
    if (
        admission_checkpoint.get("admissionComplete") is not True
        or admission_checkpoint.get("fatalCount") != 0
        or admission.get("fatalCount") != 0
        or ingest.get("state") != "complete"
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery requires complete zero-fatal admission"
        )
    if (
        ingest.get("source") != source_binding.document()
        or admission.get("source") != source_binding.document()
        or render_identity.get("source") != source_binding.document()
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery source binding differs from exact incident"
        )
    expected_render_checkpoint: dict[str, object] = {
        "renderComplete": False,
        "renderedFeatures": authority.rendered_features,
    }
    incident_text_policy = render_identity.get("rendererTextPolicy")
    if incident_text_policy is not None:
        if incident_text_policy != store._renderer_text_policy_binding():
            raise GlobalWaterwayPackageError(
                "waterway recovery predecessor text-policy identity differs"
            )
        incident_text_audit = store._validated_renderer_text_audit(
            render_checkpoint.get("rendererTextAudit")
        )
        if incident_text_audit["sourceFeatures"] != authority.rendered_features:
            raise GlobalWaterwayPackageError(
                "waterway recovery predecessor text-audit coverage differs"
            )
        expected_render_checkpoint["rendererTextAudit"] = incident_text_audit
    if (
        render_identity.get("packageId") != authority.package_id
        or render_identity.get("checkpointFeatures")
        != authority.checkpoint_features
        or render_checkpoint != expected_render_checkpoint
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery failed-render checkpoint differs from exact incident"
        )
    admission_document = ingest.get("admission")
    if admission_document != admission:
        raise GlobalWaterwayPackageError(
            "waterway recovery ingest/admission receipt cross-link differs"
        )


def _validate_first_recovery(
    connection: sqlite3.Connection,
    *,
    source_binding: WaterwaySourceBinding,
    authority: WaterwayRenderRecoveryAuthority,
    new_render_identity: Mapping[str, object],
    new_render_sha256: str,
    defer_counts_to_reset_boundary: bool,
) -> _RecoveryPlan:
    raw_documents: dict[str, tuple[bytes, dict[str, object]]] = {}
    for key in _META_KEYS:
        fact = _raw_meta(connection, key)
        assert fact is not None
        _require_meta_fact(fact[0], key=key, authority=authority)
        raw_documents[key] = fact
    documents = {key: value[1] for key, value in raw_documents.items()}
    _validate_complete_admission(
        source_binding=source_binding,
        documents=documents,
        authority=authority,
    )
    incident_render_identity = documents["renderRunIdentity"]
    raw_zooms = incident_render_identity.get("zooms")
    if (
        type(raw_zooms) is not list
        or not raw_zooms
        or any(type(value) is not int for value in raw_zooms)
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery incident render zoom identity is malformed"
        )
    store._validated_renderer_prefix_stream(
        connection,
        source_binding=source_binding,
        zooms=tuple(raw_zooms),
        run_identity=incident_render_identity,
        rendered_prefix=authority.rendered_features,
        stored_text_audit=documents["renderCheckpoint"].get("rendererTextAudit"),
    )
    if defer_counts_to_reset_boundary:
        source_counts = authority.source_table_counts
        renderer_counts = authority.renderer_table_counts
    else:
        source_counts = _require_source_counts(connection, authority)
        renderer_counts = _require_renderer_counts(connection, authority)
    recovered_at = (
        datetime.now(timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )
    receipt = _recovery_receipt_document(
        authority=authority,
        recovered_at_utc=recovered_at,
        new_render_identity=new_render_identity,
        new_render_sha256=new_render_sha256,
    )
    return _RecoveryPlan(
        resumed=False,
        recovery_receipt=MappingProxyType(receipt),
        new_render_identity=MappingProxyType(dict(new_render_identity)),
        new_render_sha256=new_render_sha256,
        incident_meta=tuple((key, raw_documents[key][0]) for key in _META_KEYS),
        preserved_meta=tuple(
            (key, raw_documents[key][0]) for key in _PRESERVED_META_KEYS
        ),
        source_counts=source_counts,
        renderer_reset_counts=renderer_counts,
    )


def _validate_recovery_resume(
    connection: sqlite3.Connection,
    *,
    source_binding: WaterwaySourceBinding,
    authority: WaterwayRenderRecoveryAuthority,
    new_render_identity: Mapping[str, object],
    new_render_sha256: str,
) -> _RecoveryPlan:
    raw_receipt = _raw_meta(connection, "renderRecoveryReceipt")
    assert raw_receipt is not None
    receipt = raw_receipt[1]
    recovered_at = _require_timestamp(receipt.get("recoveredAtUtc"))
    sqlite_evidence_overhead_bytes = receipt.get("sqliteEvidenceOverheadBytes")
    if (
        type(sqlite_evidence_overhead_bytes) is not int
        or sqlite_evidence_overhead_bytes < 0
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery SQLite evidence overhead is malformed on resume"
        )
    expected = _recovery_receipt_document(
        authority=authority,
        recovered_at_utc=recovered_at,
        new_render_identity=new_render_identity,
        new_render_sha256=new_render_sha256,
        sqlite_evidence_overhead_bytes=sqlite_evidence_overhead_bytes,
    )
    if receipt != expected:
        raise GlobalWaterwayPackageError(
            "waterway recovery receipt/code identity differs on resume"
        )
    preserved = []
    documents: dict[str, Mapping[str, object]] = {}
    for key in _PRESERVED_META_KEYS:
        fact = _raw_meta(connection, key)
        assert fact is not None
        _require_meta_fact(fact[0], key=key, authority=authority)
        preserved.append((key, fact[0]))
        documents[key] = fact[1]
    render_identity_fact = _raw_meta(connection, "renderRunIdentity")
    render_checkpoint_fact = _raw_meta(connection, "renderCheckpoint")
    assert render_identity_fact is not None and render_checkpoint_fact is not None
    if render_identity_fact[1] != dict(new_render_identity):
        raise GlobalWaterwayPackageError(
            "waterway recovery current render identity differs on resume"
        )
    checkpoint = render_checkpoint_fact[1]
    rendered_features = checkpoint.get("renderedFeatures")
    if (
        type(rendered_features) is not int
        or rendered_features < 0
        or type(checkpoint.get("renderComplete")) is not bool
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery render checkpoint is malformed on resume"
        )
    raw_zooms = new_render_identity.get("zooms")
    if (
        type(raw_zooms) is not list
        or not raw_zooms
        or any(type(value) is not int for value in raw_zooms)
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery render zoom identity is malformed on resume"
        )
    store._validated_renderer_prefix_stream(
        connection,
        source_binding=source_binding,
        zooms=tuple(raw_zooms),
        run_identity=new_render_identity,
        rendered_prefix=rendered_features,
        stored_text_audit=checkpoint.get("rendererTextAudit"),
    )
    _require_source_counts(connection, authority)
    return _RecoveryPlan(
        resumed=True,
        recovery_receipt=MappingProxyType(receipt),
        new_render_identity=MappingProxyType(dict(new_render_identity)),
        new_render_sha256=new_render_sha256,
        incident_meta=(),
        preserved_meta=tuple(preserved),
        source_counts=authority.source_table_counts,
        renderer_reset_counts=authority.renderer_table_counts,
    )


def _validate_recovery_plan(
    connection: sqlite3.Connection,
    *,
    database_path: Path,
    opl_path: Path,
    root_ids_path: Path,
    output_directory: Path,
    failure_log: Path,
    backup_receipt: Path,
    source_binding: WaterwaySourceBinding,
    authority: WaterwayRenderRecoveryAuthority,
    new_render_identity: Mapping[str, object],
    new_render_sha256: str,
    defer_first_reset_identity_and_counts: bool,
) -> _RecoveryPlan:
    _require_no_sidecars(database_path)
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway recovery output directory already exists"
        )
    if not defer_first_reset_identity_and_counts:
        store._require_identity(
            root_ids_path,
            expected_bytes=source_binding.root_ids_bytes,
            expected_sha256=source_binding.root_ids_sha256,
            label="recovery root-ID file",
        )
        store._require_identity(
            opl_path,
            expected_bytes=source_binding.closure_opl_bytes,
            expected_sha256=source_binding.closure_opl_sha256,
            label="recovery closure OPL",
        )
    _require_external_evidence(
        failure_log=failure_log,
        backup_receipt=backup_receipt,
        authority=authority,
    )
    stored_recovery = _raw_meta(
        connection, "renderRecoveryReceipt", required=False
    )
    if defer_first_reset_identity_and_counts and stored_recovery is not None:
        store._require_identity(
            root_ids_path,
            expected_bytes=source_binding.root_ids_bytes,
            expected_sha256=source_binding.root_ids_sha256,
            label="recovery root-ID file",
        )
        store._require_identity(
            opl_path,
            expected_bytes=source_binding.closure_opl_bytes,
            expected_sha256=source_binding.closure_opl_sha256,
            label="recovery closure OPL",
        )
    partials = _partial_directories(output_directory)
    if stored_recovery is None:
        if partials:
            raise GlobalWaterwayPackageError(
                "waterway recovery partial directory exists before reset"
            )
        return _validate_first_recovery(
            connection,
            source_binding=source_binding,
            authority=authority,
            new_render_identity=new_render_identity,
            new_render_sha256=new_render_sha256,
            defer_counts_to_reset_boundary=defer_first_reset_identity_and_counts,
        )
    expected_partial = output_directory.with_name(
        output_directory.name + ".partial-" + new_render_sha256[:16]
    )
    if any(partial != expected_partial for partial in partials):
        raise GlobalWaterwayPackageError(
            "waterway recovery has an unowned partial directory on resume"
        )
    owner = store._meta_get(connection, "partialDirectoryOwner")
    if partials:
        _require_owned_resume_partial(connection, expected_partial)
    elif owner is not None:
        raise GlobalWaterwayPackageError(
            "waterway recovery resume partial owner lacks its directory"
        )
    plan = _validate_recovery_resume(
        connection,
        source_binding=source_binding,
        authority=authority,
        new_render_identity=new_render_identity,
        new_render_sha256=new_render_sha256,
    )
    if (
        authority.intended_size_policy_mode
        == size_policy_module.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
    ):
        if partials:
            capacity_evidence = store._current_visual_staging_capacity_evidence(
                connection,
                partial_directory=expected_partial,
                output_directory=output_directory,
                size_policy_mode=authority.intended_size_policy_mode,
            )
            if capacity_evidence is None:
                raise GlobalWaterwayPackageError(
                    "visual-evaluation recovery capacity evidence is absent"
                )
            partial_bytes, available_bytes = capacity_evidence
        else:
            partial_bytes = 0
            try:
                available_bytes = size_policy_module.destination_available_bytes(
                    output_directory
                )
            except size_policy_module.ReferenceSizePolicyError as error:
                raise GlobalWaterwayPackageError(str(error)) from error
        store.enforce_global_waterway_storage_ceiling(
            partial_bytes,
            size_policy_mode=authority.intended_size_policy_mode,
            available_destination_bytes=available_bytes,
        )
    return plan


def _require_reset_paths_clear(
    *,
    database_path: Path,
    output_directory: Path,
) -> None:
    _require_no_sidecars(database_path)
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway recovery output directory appeared before reset"
        )
    if _partial_directories(output_directory):
        raise GlobalWaterwayPackageError(
            "waterway recovery partial directory appeared before reset"
        )


def _require_reset_boundary(
    *,
    database_path: Path,
    output_directory: Path,
    failure_log: Path,
    backup_receipt: Path,
    authority: WaterwayRenderRecoveryAuthority,
    plan: _RecoveryPlan,
) -> None:
    _require_reset_paths_clear(
        database_path=database_path,
        output_directory=output_directory,
    )
    _require_external_evidence(
        failure_log=failure_log,
        backup_receipt=backup_receipt,
        authority=authority,
    )
    _require_recovery_code_current(plan, "before reset")


def _require_destructive_inputs_current(
    *,
    opl_path: Path,
    root_ids_path: Path,
    source_binding: WaterwaySourceBinding,
    expected_code_identities: Mapping[str, object],
) -> None:
    store._require_identity(
        root_ids_path,
        expected_bytes=source_binding.root_ids_bytes,
        expected_sha256=source_binding.root_ids_sha256,
        label="recovery root-ID file at destructive boundary",
    )
    store._require_identity(
        opl_path,
        expected_bytes=source_binding.closure_opl_bytes,
        expected_sha256=source_binding.closure_opl_sha256,
        label="recovery closure OPL at destructive boundary",
    )
    if store._render_code_identities() != dict(expected_code_identities):
        raise GlobalWaterwayPackageError(
            "waterway recovery renderer code identity drifted before reset"
        )


def _recovery_database_stat_signature(database_path: Path) -> tuple[int, ...]:
    try:
        status = os.lstat(database_path)
    except OSError as error:
        raise GlobalWaterwayPackageError(
            "waterway recovery source database is unavailable"
        ) from error
    if (
        not stat.S_ISREG(status.st_mode)
        or getattr(status, "st_file_attributes", 0) & store._REPARSE_POINT
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery source database is not one regular non-link file"
        )
    return (
        int(status.st_dev),
        int(status.st_ino),
        int(status.st_size),
        int(status.st_mtime_ns),
        int(status.st_ctime_ns),
    )


def _reset_renderer_state(
    connection: sqlite3.Connection,
    *,
    database_path: Path,
    output_directory: Path,
    failure_log: Path,
    backup_receipt: Path,
    authority: WaterwayRenderRecoveryAuthority,
    plan: _RecoveryPlan,
    opl_path: Path,
    root_ids_path: Path,
    source_binding: WaterwaySourceBinding,
    expected_code_identities: Mapping[str, object],
    progress_validator: Callable[[], None] | None = None,
) -> _RecoveryPlan:
    updated_plan = plan
    try:
        if connection.in_transaction:
            raise GlobalWaterwayPackageError(
                "waterway recovery source identity requires an idle SQLite connection"
            )
        data_version_before_hash = int(
            connection.execute("PRAGMA data_version").fetchone()[0]
        )
        database_signature_before_hash = _recovery_database_stat_signature(
            database_path
        )
        database_identity = source_module._stream_identity(
            database_path, "waterway recovery source database"
        )
        database_signature_after_hash = _recovery_database_stat_signature(
            database_path
        )
        if database_signature_after_hash != database_signature_before_hash:
            raise GlobalWaterwayPackageError(
                "waterway recovery database changed while computing its identity"
            )
        if database_identity != _fact_document(
            authority.database_bytes, authority.database_sha256
        ):
            raise GlobalWaterwayPackageError(
                "waterway recovery database identity differs from exact incident"
            )

        connection.execute("BEGIN IMMEDIATE")
        if (
            int(connection.execute("PRAGMA data_version").fetchone()[0])
            != data_version_before_hash
            or _recovery_database_stat_signature(database_path)
            != database_signature_after_hash
        ):
            raise GlobalWaterwayPackageError(
                "waterway recovery database changed between identity and write reservation"
            )
        if progress_validator is not None:
            progress_validator()
        _require_reset_boundary(
            database_path=database_path,
            output_directory=output_directory,
            failure_log=failure_log,
            backup_receipt=backup_receipt,
            authority=authority,
            plan=plan,
        )
        for key, raw in plan.incident_meta:
            actual = _raw_meta(connection, key)
            if actual is None or actual[0] != raw:
                raise GlobalWaterwayPackageError(
                    f"waterway recovery incident {key} drifted before reset"
                )
        for key, raw in plan.preserved_meta:
            actual = _raw_meta(connection, key)
            if actual is None or actual[0] != raw:
                raise GlobalWaterwayPackageError(
                    f"waterway recovery preserved {key} drifted before reset"
                )
        if _require_source_counts(connection, authority) != plan.source_counts:
            raise GlobalWaterwayPackageError(
                "waterway recovery source counts drifted before reset"
            )
        if _require_renderer_counts(connection, authority) != plan.renderer_reset_counts:
            raise GlobalWaterwayPackageError(
                "waterway recovery renderer counts drifted before reset"
            )
        _require_reset_boundary(
            database_path=database_path,
            output_directory=output_directory,
            failure_log=failure_log,
            backup_receipt=backup_receipt,
            authority=authority,
            plan=plan,
        )
        _require_destructive_inputs_current(
            opl_path=opl_path,
            root_ids_path=root_ids_path,
            source_binding=source_binding,
            expected_code_identities=expected_code_identities,
        )
        _require_reset_boundary(
            database_path=database_path,
            output_directory=output_directory,
            failure_log=failure_log,
            backup_receipt=backup_receipt,
            authority=authority,
            plan=plan,
        )
        _require_reset_paths_clear(
            database_path=database_path,
            output_directory=output_directory,
        )
        if progress_validator is not None:
            progress_validator()
        for table in _RENDERER_TABLES:
            connection.execute(f"DELETE FROM {table}")
        connection.execute(
            "DELETE FROM meta WHERE key IN ("
            "'renderRunIdentity','renderCheckpoint','partialDirectoryOwner',"
            "'buildCheckpoint','sizePolicyCapacity')"
        )
        ingest = store._meta_get(connection, "ingestReceipt")
        peaks = dict(store._meta_get(connection, "peaks") or {})
        ingest_peaks = ingest.get("peakResources") if isinstance(ingest, dict) else None
        if not isinstance(ingest_peaks, dict):
            raise GlobalWaterwayPackageError(
                "waterway recovery ingest peak evidence is absent"
            )
        for key in _RENDERER_PEAK_KEYS:
            peaks[key] = 0
        for key in (
            "inputLineBytes",
            "observedPersistentSqliteBytesAtCheckpoints",
        ):
            value = ingest_peaks.get(key)
            if type(value) is not int or value < 0:
                raise GlobalWaterwayPackageError(
                    f"waterway recovery ingest peak {key} is malformed"
                )
            peaks[key] = value
        store._meta_set(connection, "peaks", peaks)
        store._meta_set(
            connection, "renderRunIdentity", dict(plan.new_render_identity)
        )
        store._meta_set(
            connection,
            "renderCheckpoint",
            {
                "renderComplete": False,
                "renderedFeatures": 0,
                "rendererTextAudit": store._empty_renderer_text_audit(),
            },
        )
        page_size = int(connection.execute("PRAGMA page_size").fetchone()[0])
        baseline_live_pages = int(
            connection.execute("PRAGMA page_count").fetchone()[0]
        ) - int(connection.execute("PRAGMA freelist_count").fetchone()[0])
        receipt = dict(plan.recovery_receipt)
        for _ in range(4):
            store._meta_set(connection, "renderRecoveryReceipt", receipt)
            live_pages = int(
                connection.execute("PRAGMA page_count").fetchone()[0]
            ) - int(connection.execute("PRAGMA freelist_count").fetchone()[0])
            overhead_bytes = max(0, live_pages - baseline_live_pages) * page_size
            if receipt.get("sqliteEvidenceOverheadBytes") == overhead_bytes:
                break
            receipt["sqliteEvidenceOverheadBytes"] = overhead_bytes
        else:
            raise GlobalWaterwayPackageError(
                "waterway recovery SQLite evidence overhead did not converge"
            )
        updated_plan = _RecoveryPlan(
            resumed=plan.resumed,
            recovery_receipt=MappingProxyType(receipt),
            new_render_identity=plan.new_render_identity,
            new_render_sha256=plan.new_render_sha256,
            incident_meta=plan.incident_meta,
            preserved_meta=plan.preserved_meta,
            source_counts=plan.source_counts,
            renderer_reset_counts=plan.renderer_reset_counts,
        )
        store._progress_guarded_commit(connection, progress_validator)
    except BaseException:
        if connection.in_transaction:
            connection.rollback()
        raise

    for key, raw in updated_plan.preserved_meta:
        actual = _raw_meta(connection, key)
        if actual is None or actual[0] != raw:
            raise GlobalWaterwayPackageError(
                f"waterway recovery preserved {key} differs after reset"
            )
    if _table_counts(connection, _SOURCE_TABLES) != updated_plan.source_counts:
        raise GlobalWaterwayPackageError(
            "waterway recovery source counts differ after reset"
        )
    if any(count != 0 for _, count in _table_counts(connection, _RENDERER_TABLES)):
        raise GlobalWaterwayPackageError(
            "waterway recovery renderer rows remain after reset"
        )
    if store._meta_get(connection, "renderRecoveryReceipt") != dict(
        updated_plan.recovery_receipt
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery receipt differs after reset"
        )
    if store._meta_get(connection, "renderRunIdentity") != dict(
        updated_plan.new_render_identity
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery new renderer identity differs after reset"
        )
    return updated_plan


def _checked_recovery_size_policy_mode(
    authority: WaterwayRenderRecoveryAuthority,
    size_policy_mode: object,
) -> str:
    try:
        checked = size_policy_module.normalize_reference_size_policy_mode(
            size_policy_mode
        )
    except size_policy_module.ReferenceSizePolicyError as error:
        raise GlobalWaterwayPackageError(str(error)) from error
    if checked != authority.intended_size_policy_mode:
        raise GlobalWaterwayPackageError(
            "waterway recovery requested size-policy transition lacks authority"
        )
    return checked


def _validate_bound_global_waterway_recovery(
    *,
    opl_path: Path,
    root_ids_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    failure_log: Path,
    backup_receipt: Path,
    authority: WaterwayRenderRecoveryAuthority,
    checkpoint_features: int,
    production_authority: object,
    size_policy_mode: object = size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE,
) -> Mapping[str, object]:
    from .osm_global_waterway_renderer import classifier_identity_sha256

    if production_authority is not source_module._PRODUCTION_RENDER_AUTHORITY:
        raise GlobalWaterwayPackageError(
            "waterway render recovery validation requires package-owned authority"
        )
    if not isinstance(authority, WaterwayRenderRecoveryAuthority):
        raise GlobalWaterwayPackageError(
            "waterway render recovery validation incident authority is absent"
        )
    if package_id != authority.package_id:
        raise GlobalWaterwayPackageError(
            "waterway render recovery validation package ID differs"
        )
    if checkpoint_features != authority.checkpoint_features:
        raise GlobalWaterwayPackageError(
            "waterway render recovery validation checkpoint cadence differs"
        )
    checked_mode = _checked_recovery_size_policy_mode(
        authority, size_policy_mode
    )
    checked_package_id = store._validated_package_id(package_id)
    database_path = work_directory / "waterway-state.sqlite"
    if not database_path.is_file() or database_path.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway render recovery validation database is absent or unsafe"
        )
    _require_no_sidecars(database_path)
    code_identities = store._render_code_identities()
    size_policy_binding = store._reference_size_policy_binding(checked_mode)
    if code_identities.get("referenceSizePolicy") != size_policy_binding.get(
        "module"
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery validation size-policy module identity differs"
        )
    classifier = classifier_identity_sha256()
    uri = database_path.resolve().as_uri() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    try:
        connection.execute("PRAGMA query_only=ON")
        query_only = int(connection.execute("PRAGMA query_only").fetchone()[0])
        if query_only != 1:
            raise GlobalWaterwayPackageError(
                "waterway recovery validation connection is not query-only"
            )
        connection.execute("BEGIN")
        ingest = store._meta_get(connection, "ingestReceipt")
        if not isinstance(ingest, dict) or not isinstance(
            ingest.get("admission"), dict
        ):
            raise GlobalWaterwayPackageError(
                "waterway render recovery validation ingest receipt is absent"
            )
        render_identity = store._render_run_identity(
            package_id=checked_package_id,
            source_binding=source_binding,
            zooms=store._DEFAULT_ZOOMS,
            checkpoint_features=checkpoint_features,
            code_identities=code_identities,
            classifier_sha256=classifier,
            admission_receipt=ingest["admission"],
            ingest_semantic_sha256=str(ingest["semanticSha256"]),
            size_policy_binding=size_policy_binding,
        )
        render_sha256 = hashlib.sha256(
            source_module._canonical_json_bytes(render_identity)
        ).hexdigest()
        plan = _validate_recovery_plan(
            connection,
            database_path=database_path,
            opl_path=opl_path,
            root_ids_path=root_ids_path,
            output_directory=output_directory,
            failure_log=failure_log,
            backup_receipt=backup_receipt,
            source_binding=source_binding,
            authority=authority,
            new_render_identity=render_identity,
            new_render_sha256=render_sha256,
            defer_first_reset_identity_and_counts=False,
        )
        database_identity = source_module._stream_identity(
            database_path, "waterway recovery validation database"
        )
        if not plan.resumed and database_identity != _fact_document(
            authority.database_bytes, authority.database_sha256
        ):
            raise GlobalWaterwayPackageError(
                "waterway recovery validation database identity differs"
            )
        if not plan.resumed:
            _require_reset_boundary(
                database_path=database_path,
                output_directory=output_directory,
                failure_log=failure_log,
                backup_receipt=backup_receipt,
                authority=authority,
                plan=plan,
            )
        else:
            _require_no_sidecars(database_path)
            if output_directory.exists() or output_directory.is_symlink():
                raise GlobalWaterwayPackageError(
                    "waterway recovery output directory appeared on resume"
                )
            _require_external_evidence(
                failure_log=failure_log,
                backup_receipt=backup_receipt,
                authority=authority,
            )
            _require_recovery_code_current(plan, "during resume validation")
        _require_destructive_inputs_current(
            opl_path=opl_path,
            root_ids_path=root_ids_path,
            source_binding=source_binding,
            expected_code_identities=code_identities,
        )
        return MappingProxyType(
            {
                "authorityPolicySha256": authority.policy_sha256,
                "database": database_identity,
                "queryOnly": query_only,
                "recoveryRunIdentitySha256": render_sha256,
                "resumed": plan.resumed,
                "schema": _VALIDATION_SCHEMA,
                "sizePolicyTransition": dict(
                    plan.recovery_receipt["sizePolicyTransition"]
                ),
                "source": source_binding.document(),
                "state": "accepted",
            }
        )
    finally:
        if connection.in_transaction:
            connection.rollback()
        connection.close()


def _close_recovery_resources(
    *,
    connection_close: Callable[[], None] | None,
    progress_close: Callable[[], None] | None,
    operation_error: BaseException | None,
) -> None:
    """Close SQLite before releasing progress ownership without masking errors."""

    connection_error: BaseException | None = None
    progress_error: BaseException | None = None
    if connection_close is not None:
        try:
            connection_close()
        except BaseException as error:
            connection_error = error
    if progress_close is not None:
        try:
            progress_close()
        except BaseException as error:
            progress_error = error

    if operation_error is not None:
        if connection_error is not None:
            operation_error.add_note(
                "waterway recovery SQLite connection close also failed: "
                f"{connection_error}"
            )
        if progress_error is not None:
            operation_error.add_note(
                "waterway recovery progress session close also failed: "
                f"{progress_error}"
            )
        return
    if connection_error is not None:
        if progress_error is not None:
            connection_error.add_note(
                "waterway recovery progress session close also failed: "
                f"{progress_error}"
            )
        raise connection_error
    if progress_error is not None:
        raise progress_error


def _recover_bound_global_waterway_package(
    *,
    opl_path: Path,
    root_ids_path: Path,
    output_directory: Path,
    work_directory: Path,
    package_id: str,
    source_binding: WaterwaySourceBinding,
    failure_log: Path,
    backup_receipt: Path,
    authority: WaterwayRenderRecoveryAuthority,
    checkpoint_features: int,
    render_workers: int = 1,
    pause_after_features: int | None = None,
    progress_file: Path | None = None,
    production_authority: object,
    size_policy_mode: object = size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE,
) -> GlobalWaterwayBuildResult:
    from .osm_global_waterway_renderer import classifier_identity_sha256
    from .waterway_parallel_render import (
        DurableProgressFile,
        maximum_parallel_render_features,
    )

    if pause_after_features is not None and (
        type(pause_after_features) is not int
        or not 1 <= pause_after_features <= maximum_parallel_render_features()
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery pause feature count is outside its valid range"
        )

    if production_authority is not source_module._PRODUCTION_RENDER_AUTHORITY:
        raise GlobalWaterwayPackageError(
            "waterway render recovery requires package-owned authority"
        )
    if not isinstance(authority, WaterwayRenderRecoveryAuthority):
        raise GlobalWaterwayPackageError(
            "waterway render recovery incident authority is absent"
        )
    if package_id != authority.package_id:
        raise GlobalWaterwayPackageError(
            "waterway render recovery package ID differs from exact incident"
        )
    if checkpoint_features != authority.checkpoint_features:
        raise GlobalWaterwayPackageError(
            "waterway render recovery checkpoint cadence differs from exact incident"
        )
    checked_size_policy_mode = _checked_recovery_size_policy_mode(
        authority, size_policy_mode
    )
    checked_package_id = store._validated_package_id(package_id)
    zooms = store._DEFAULT_ZOOMS
    database_path = work_directory / "waterway-state.sqlite"
    if not database_path.is_file() or database_path.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway render recovery database is absent or unsafe"
        )
    _require_no_sidecars(database_path)
    code_identities = store._render_code_identities()
    size_policy_binding = store._reference_size_policy_binding(
        checked_size_policy_mode
    )
    if code_identities.get("referenceSizePolicy") != size_policy_binding.get(
        "module"
    ):
        raise GlobalWaterwayPackageError(
            "waterway recovery size-policy module identity differs"
        )
    classifier = classifier_identity_sha256()
    progress_durable = None
    connection = None
    progress_preflight = None
    operation_error = None
    try:
        if progress_file is not None:
            progress_durable = DurableProgressFile(
                progress_file, acquire_session=True
            )
        connection = sqlite3.connect(database_path)
        trusted_data_version = int(
            connection.execute("PRAGMA data_version").fetchone()[0]
        )
        ingest = store._meta_get(connection, "ingestReceipt")
        if not isinstance(ingest, dict) or not isinstance(
            ingest.get("admission"), dict
        ):
            raise GlobalWaterwayPackageError(
                "waterway render recovery ingest receipt is absent"
            )
        render_identity = store._render_run_identity(
            package_id=checked_package_id,
            source_binding=source_binding,
            zooms=zooms,
            checkpoint_features=checkpoint_features,
            code_identities=code_identities,
            classifier_sha256=classifier,
            admission_receipt=ingest["admission"],
            ingest_semantic_sha256=str(ingest["semanticSha256"]),
            size_policy_binding=size_policy_binding,
        )
        render_sha256 = hashlib.sha256(
            source_module._canonical_json_bytes(render_identity)
        ).hexdigest()
        plan = _validate_recovery_plan(
            connection,
            database_path=database_path,
            opl_path=opl_path,
            root_ids_path=root_ids_path,
            output_directory=output_directory,
            failure_log=failure_log,
            backup_receipt=backup_receipt,
            source_binding=source_binding,
            authority=authority,
            new_render_identity=render_identity,
            new_render_sha256=render_sha256,
            defer_first_reset_identity_and_counts=True,
        )
        parallel_limits = store._default_parallel_render_limits(render_workers)
        if progress_file is not None:
            if plan.resumed:
                progress_checkpoint = store._meta_get(
                    connection, "renderCheckpoint"
                )
                progress_prefix = int(progress_checkpoint["renderedFeatures"])
            else:
                progress_prefix = 0
            total_admitted_features = int(
                connection.execute(
                    "SELECT COUNT(*) FROM admission_candidates"
                ).fetchone()[0]
            )
            progress_preflight = store._RenderProgressPublisher(
                progress_file,
                render_run_sha256=render_sha256,
                checkpoint_features=checkpoint_features,
                total_admitted_features=total_admitted_features,
                initial_committed_features=progress_prefix,
                limits=parallel_limits,
                _durable_file=progress_durable,
            )
            progress_preflight.preflight(progress_prefix)
        if not plan.resumed:
            plan = _reset_renderer_state(
                connection,
                database_path=database_path,
                output_directory=output_directory,
                failure_log=failure_log,
                backup_receipt=backup_receipt,
                authority=authority,
                plan=plan,
                opl_path=opl_path,
                root_ids_path=root_ids_path,
                source_binding=source_binding,
                expected_code_identities=code_identities,
                progress_validator=(
                    progress_preflight.require_unchanged
                    if progress_preflight is not None
                    else None
                ),
            )
        if progress_preflight is not None:
            progress_preflight.require_unchanged()
        store._configure_render_connection(connection)
        complete = store._stage_renderer_records(
            connection,
            database_path,
            source_binding=source_binding,
            zooms=zooms,
            checkpoint_features=checkpoint_features,
            pause_after_features=pause_after_features,
            run_identity=render_identity,
            trusted_data_version=trusted_data_version,
            parallel_limits=parallel_limits,
            progress_file=progress_file,
            progress_durable=progress_durable,
        )
        progress_preflight = None
        if not complete:
            return GlobalWaterwayBuildResult(
                "paused",
                output_directory,
                MappingProxyType(
                    {
                        "checkpoint": store._meta_get(
                            connection, "renderCheckpoint"
                        ),
                        "recovery": dict(plan.recovery_receipt),
                        "runIdentitySha256": render_sha256,
                        "schema": store._BUILD_SCHEMA,
                        "state": "paused",
                    }
                ),
            )
        windows = store._windows(connection)
        _require_recovery_code_current(plan, "before publication")
        partial_directory = output_directory.with_name(
            output_directory.name + ".partial-" + render_sha256[:16]
        )
        receipt = store._publish(
            connection,
            database_path,
            partial_directory=partial_directory,
            output_directory=output_directory,
            package_id=checked_package_id,
            source_binding=source_binding,
            windows=windows,
            render_run_identity=render_identity,
            render_run_sha256=render_sha256,
            code_identities=code_identities,
            recovery_receipt=plan.recovery_receipt,
            immediate_pre_publish_validator=lambda: _require_recovery_code_current(
                plan, "at immediate publication boundary"
            ),
        )
        return GlobalWaterwayBuildResult("complete", output_directory, receipt)
    except BaseException as error:
        operation_error = error
        raise
    finally:
        _close_recovery_resources(
            connection_close=(connection.close if connection is not None else None),
            progress_close=(
                progress_durable.close if progress_durable is not None else None
            ),
            operation_error=operation_error,
        )


__all__ = ["WaterwayRenderRecoveryAuthority"]
