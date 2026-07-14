from __future__ import annotations

import hashlib
import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from types import MappingProxyType
from typing import Mapping, Sequence

from . import osm_global_waterway_package as source_module
from . import osm_global_waterway_store as store
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
        b"sqlite3.OperationalError: database is locked\n"
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
    published_directory_bytes: int | None = None,
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
        "sqliteEvidenceOverheadBytes": sqlite_evidence_overhead_bytes,
        "transactionComplete": True,
    }
    if published_directory_bytes is not None:
        if type(published_directory_bytes) is not int or published_directory_bytes <= 0:
            raise GlobalWaterwayPackageError(
                "waterway recovery published byte accounting is malformed"
            )
        document["publishedDirectoryBytes"] = published_directory_bytes
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
    if (
        render_identity.get("packageId") != authority.package_id
        or render_identity.get("checkpointFeatures")
        != authority.checkpoint_features
        or render_checkpoint
        != {
            "renderComplete": False,
            "renderedFeatures": authority.rendered_features,
        }
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
        published_directory_bytes=receipt.get("publishedDirectoryBytes"),
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
    if int(
        connection.execute("SELECT COUNT(*) FROM rendered_features").fetchone()[0]
    ) != rendered_features:
        raise GlobalWaterwayPackageError(
            "waterway recovery rendered prefix count differs on resume"
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
) -> _RecoveryPlan:
    _require_no_sidecars(database_path)
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway recovery output directory already exists"
        )
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
        )
    expected_partial = output_directory.with_name(
        output_directory.name + ".partial-" + new_render_sha256[:16]
    )
    if any(partial != expected_partial for partial in partials):
        raise GlobalWaterwayPackageError(
            "waterway recovery has an unowned partial directory on resume"
        )
    return _validate_recovery_resume(
        connection,
        source_binding=source_binding,
        authority=authority,
        new_render_identity=new_render_identity,
        new_render_sha256=new_render_sha256,
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
    _require_no_sidecars(database_path)
    if output_directory.exists() or output_directory.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway recovery output directory appeared before reset"
        )
    if _partial_directories(output_directory):
        raise GlobalWaterwayPackageError(
            "waterway recovery partial directory appeared before reset"
        )
    _require_external_evidence(
        failure_log=failure_log,
        backup_receipt=backup_receipt,
        authority=authority,
    )
    _require_recovery_code_current(plan, "before reset")


def _reset_renderer_state(
    connection: sqlite3.Connection,
    *,
    database_path: Path,
    output_directory: Path,
    failure_log: Path,
    backup_receipt: Path,
    authority: WaterwayRenderRecoveryAuthority,
    plan: _RecoveryPlan,
) -> _RecoveryPlan:
    updated_plan = plan
    try:
        connection.execute("BEGIN IMMEDIATE")
        _require_reset_boundary(
            database_path=database_path,
            output_directory=output_directory,
            failure_log=failure_log,
            backup_receipt=backup_receipt,
            authority=authority,
            plan=plan,
        )
        database_identity = source_module._stream_identity(
            database_path, "waterway recovery source database"
        )
        if database_identity != _fact_document(
            authority.database_bytes, authority.database_sha256
        ):
            raise GlobalWaterwayPackageError(
                "waterway recovery database identity differs from exact incident"
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
        for table in _RENDERER_TABLES:
            connection.execute(f"DELETE FROM {table}")
        connection.execute(
            "DELETE FROM meta WHERE key IN ("
            "'renderRunIdentity','renderCheckpoint','partialDirectoryOwner',"
            "'buildCheckpoint')"
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
            {"renderComplete": False, "renderedFeatures": 0},
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
        connection.commit()
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
    production_authority: object,
) -> GlobalWaterwayBuildResult:
    from .osm_global_waterway_renderer import classifier_identity_sha256

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
    checked_package_id = store._validated_package_id(package_id)
    zooms = store._DEFAULT_ZOOMS
    database_path = work_directory / "waterway-state.sqlite"
    if not database_path.is_file() or database_path.is_symlink():
        raise GlobalWaterwayPackageError(
            "waterway render recovery database is absent or unsafe"
        )
    _require_no_sidecars(database_path)
    code_identities = store._render_code_identities()
    classifier = classifier_identity_sha256()
    connection = sqlite3.connect(database_path)
    try:
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
        )
        if not plan.resumed:
            plan = _reset_renderer_state(
                connection,
                database_path=database_path,
                output_directory=output_directory,
                failure_log=failure_log,
                backup_receipt=backup_receipt,
                authority=authority,
                plan=plan,
            )
        store._configure_render_connection(connection)
        complete = store._stage_renderer_records(
            connection,
            database_path,
            source_binding=source_binding,
            zooms=zooms,
            checkpoint_features=checkpoint_features,
            pause_after_features=None,
            run_identity=render_identity,
        )
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
    finally:
        connection.close()


__all__ = ["WaterwayRenderRecoveryAuthority"]
