from __future__ import annotations

import hashlib
import heapq
import json
import os
import re
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import cast

from tools.experiment8.osm_closure_probe import (
    BOOST_LIBRARY_PATH,
    BOOST_LIBRARY_SHA256,
    OSMIUM_BINARY_PATH,
    OSMIUM_BINARY_SHA256,
    PINNED_LIBOSMIUM_VERSION,
    PINNED_LOCALE,
    PINNED_OSMIUM_VERSION,
    PINNED_UBUNTU_DISTRIBUTION,
    PINNED_UBUNTU_RELEASE,
    RUNTIME_LIBRARY_PATH,
    RUNTIME_ROOT,
    WSL_EXECUTABLE,
    MAX_CAPTURE_BYTES,
    ClosureProbeEvidence,
    ProcessEvidence,
    RuntimeAttestation,
    parse_getid_result,
)
from tools.experiment8.osm_hydro_source import (
    MARYLAND_REGIONAL_PROFILE,
    MARYLAND_SOURCE_SHA256,
    IncompleteRelationClosure,
    IncompleteRelationRoot,
    MissingReferences,
    OsmRelationMember,
    RegionalClosureClassification,
    RelationClosureAudit,
    RootSelection,
    audit_predicted_relation_root_closures,
)


_LOWER_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
_MAX_SELECTED_RELATIONS = 4_096
_MAX_SELECTED_WAYS = 250_000
_MAX_RELATION_LINKS = 100_000
_MAX_SERIALIZED_MISSING_REFERENCES = 1_000_000
_MAX_PROBE_RECORDS = 256
_MAX_RAW_FILES = 4_096
_MAX_RAW_FILE_BYTES = 8 * 1024 * 1024
_MAX_BUNDLE_BYTES = 64 * 1024 * 1024
_WINDOWS_NO_REPLACE_RENAME = os.name == "nt"
_MOVEFILE_WRITE_THROUGH = 0x00000008

SYNTHETIC_RELATION_CLOSURE_PROFILE = (
    "flight-alert-exp8-osm-relation-closure-synthetic-v1"
)

# These derived identities stay deliberately unset until the verified Maryland
# provenance/admission chain establishes canonical values.  The Maryland
# factory below is fail-closed while any value is provisional or absent.
_MARYLAND_EXACT_SOURCE_IDENTITY_SHA256: str | None = None
_MARYLAND_EXACT_SELECTION_MANIFEST_SHA256: str | None = None
_MARYLAND_EXACT_CANDIDATE_XML_SHA256: str | None = None
_MARYLAND_EXACT_ROOTS: RootSelection | None = None
_MARYLAND_EXACT_CLASSIFICATION_SHA256: str | None = None


class BundleContractError(ValueError):
    """Relation-closure evidence was inconsistent or unsafe to serialize."""


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _canonical_argv_bytes(argv: tuple[str, ...]) -> bytes:
    return (
        json.dumps(list(argv), ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode("utf-8")


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


@dataclass(frozen=True, slots=True)
class BundleFile:
    relative_path: str
    content: bytes

    def __post_init__(self) -> None:
        if not isinstance(self.relative_path, str) or not self.relative_path:
            raise BundleContractError("bundle relative path must be nonempty text")
        path = PurePosixPath(self.relative_path)
        if (
            path.is_absolute()
            or self.relative_path == "."
            or str(path) != self.relative_path
            or ".." in path.parts
            or self.relative_path.startswith("//")
            or "\\" in self.relative_path
        ):
            raise BundleContractError("bundle path must be canonical relative POSIX text")
        if not isinstance(self.content, bytes):
            raise BundleContractError("bundle file content must be exact bytes")
        if len(self.content) > _MAX_RAW_FILE_BYTES:
            raise BundleContractError("bundle file exceeds the raw-file byte ceiling")


@dataclass(frozen=True, slots=True)
class RelationClosureAuditBundle:
    classification_bytes: bytes
    execution_manifest_bytes: bytes
    raw_files: tuple[BundleFile, ...]

    def __post_init__(self) -> None:
        if (
            not isinstance(self.classification_bytes, bytes)
            or not isinstance(self.execution_manifest_bytes, bytes)
            or not isinstance(self.raw_files, tuple)
            or any(not isinstance(item, BundleFile) for item in self.raw_files)
        ):
            raise BundleContractError("bundle content is not immutable exact bytes")
        paths = tuple(item.relative_path for item in self.raw_files)
        if len(set(paths)) != len(paths):
            raise BundleContractError("bundle contains duplicate raw-file paths")
        if len(self.raw_files) > _MAX_RAW_FILES:
            raise BundleContractError("bundle raw-file count exceeds the ceiling")
        total_bytes = (
            len(self.classification_bytes)
            + len(self.execution_manifest_bytes)
            + sum(len(item.content) for item in self.raw_files)
        )
        if total_bytes > _MAX_BUNDLE_BYTES:
            raise BundleContractError("bundle byte ceiling exceeded")

    @property
    def classification_sha256(self) -> str:
        return _sha256(self.classification_bytes)

    @property
    def execution_manifest_sha256(self) -> str:
        return _sha256(self.execution_manifest_bytes)


@dataclass(frozen=True, slots=True)
class BundleWriteResult:
    destination: Path
    classification_sha256: str
    execution_manifest_sha256: str
    file_count: int
    total_bytes: int


def _canonical_hash(value: object, label: str) -> str:
    if not isinstance(value, str) or _LOWER_SHA256.fullmatch(value) is None:
        raise BundleContractError(f"{label} must be a lowercase SHA-256")
    return value


def _strict_ids(values: tuple[int, ...], label: str) -> tuple[int, ...]:
    if not isinstance(values, tuple):
        raise BundleContractError(f"{label} must be a tuple")
    previous = 0
    for value in values:
        if (
            isinstance(value, bool)
            or not isinstance(value, int)
            or value <= previous
        ):
            raise BundleContractError(
                f"{label} must be strictly increasing positive integers"
            )
        previous = value
    return values


def _missing_document(missing: MissingReferences) -> dict[str, object]:
    if not isinstance(missing, MissingReferences):
        raise BundleContractError("missing references use an unsupported type")
    return {
        "nodeIds": list(missing.node_ids),
        "relationIds": list(missing.relation_ids),
        "wayIds": list(missing.way_ids),
    }


def _union_missing(values: tuple[MissingReferences, ...]) -> MissingReferences:
    nodes: set[int] = set()
    ways: set[int] = set()
    relations: set[int] = set()
    for missing in values:
        nodes.update(missing.node_ids)
        ways.update(missing.way_ids)
        relations.update(missing.relation_ids)
    return MissingReferences(
        node_ids=tuple(sorted(nodes)),
        way_ids=tuple(sorted(ways)),
        relation_ids=tuple(sorted(relations)),
    )


def _expected_probe_batches(
    roots: RootSelection, incomplete_ids: tuple[int, ...]
) -> tuple[tuple[int, ...], ...]:
    incomplete = set(incomplete_ids)
    retained = tuple(
        relation_id
        for relation_id in roots.relation_ids
        if relation_id not in incomplete
    )
    return (
        roots.relation_ids,
        *((relation_id,) for relation_id in incomplete_ids),
        *((retained,) if retained else ()),
    )


def _validate_models(
    roots: RootSelection,
    classification: RegionalClosureClassification,
    audit: RelationClosureAudit,
) -> tuple[
    tuple[int, ...],
    dict[int, IncompleteRelationRoot],
    dict[int, MissingReferences],
    MissingReferences,
    tuple[tuple[int, ...], ...],
]:
    if not isinstance(roots, RootSelection):
        raise BundleContractError("roots use an unsupported type")
    if not isinstance(classification, RegionalClosureClassification):
        raise BundleContractError("classification uses an unsupported type")
    if not isinstance(audit, RelationClosureAudit):
        raise BundleContractError("relation audit uses an unsupported type")
    way_ids = _strict_ids(roots.way_ids, "selected way IDs")
    relation_ids = _strict_ids(roots.relation_ids, "selected relation IDs")
    if len(way_ids) > _MAX_SELECTED_WAYS:
        raise BundleContractError("selected way count exceeds the audit ceiling")
    if not relation_ids or len(relation_ids) > _MAX_SELECTED_RELATIONS:
        raise BundleContractError("selected relation count is outside the audit bounds")
    if classification.complete_way_ids != way_ids:
        raise BundleContractError("regional classification does not retain every way root")
    complete_ids = _strict_ids(
        classification.complete_relation_ids, "complete relation IDs"
    )
    if audit.complete_relation_ids != complete_ids:
        raise BundleContractError("classification and audit complete relation IDs differ")

    incomplete_by_id: dict[int, IncompleteRelationRoot] = {}
    relation_link_count = 0
    previous = 0
    selected_set = set(relation_ids)
    missing_selected_ways = set(classification.missing_references.way_ids).intersection(
        way_ids
    )
    if missing_selected_ways:
        raise BundleContractError(
            f"selected way {min(missing_selected_ways)} is globally missing"
        )
    missing_selected_relations = set(
        classification.missing_references.relation_ids
    ).intersection(relation_ids)
    if missing_selected_relations:
        raise BundleContractError(
            f"selected relation {min(missing_selected_relations)} is globally missing"
        )
    for item in classification.incomplete_relations:
        relation_id = item.relation_id
        if relation_id <= previous or relation_id not in selected_set:
            raise BundleContractError(
                "classified incomplete relation IDs must be selected and increasing"
            )
        previous = relation_id
        if not item.direct_missing_members and not item.dependency_relation_ids:
            raise BundleContractError(
                f"incomplete relation {relation_id} has no direct or dependency evidence"
            )
        dependencies = _strict_ids(
            item.dependency_relation_ids,
            f"relation {relation_id} dependency IDs",
        )
        if relation_id in dependencies:
            raise BundleContractError(
                f"incomplete relation {relation_id} depends on itself"
            )
        relation_link_count += len(item.direct_missing_members) + len(dependencies)
        if relation_link_count > _MAX_RELATION_LINKS:
            raise BundleContractError(
                "incomplete relation linkage exceeds the audit ceiling"
            )
        incomplete_by_id[relation_id] = item
    incomplete_ids = tuple(incomplete_by_id)
    if set(complete_ids).intersection(incomplete_ids) or set(complete_ids).union(
        incomplete_ids
    ) != selected_set:
        raise BundleContractError(
            "every selected relation must be exactly complete or source_incomplete"
        )
    for relation_id, raw_item in incomplete_by_id.items():
        item = raw_item
        if not set(item.dependency_relation_ids).issubset(incomplete_by_id):
            raise BundleContractError(
                f"relation {relation_id} has an unclassified incomplete dependency"
            )
        previous_ordinal = -1
        for member in item.direct_missing_members:
            if member.ordinal < 0 or member.ordinal <= previous_ordinal:
                raise BundleContractError(
                    f"relation {relation_id} direct missing member ordinal is unavailable "
                    "or not increasing"
                )
            previous_ordinal = member.ordinal
            if member.object_type not in {"node", "way", "relation"}:
                raise BundleContractError(
                    f"relation {relation_id} has an unsupported missing member type"
                )
            expected_set = {
                "node": set(classification.missing_references.node_ids),
                "way": set(classification.missing_references.way_ids),
                "relation": set(classification.missing_references.relation_ids),
            }[member.object_type]
            if member.ref not in expected_set:
                raise BundleContractError(
                    f"relation {relation_id} direct member is not globally missing"
                )

    dependency_counts = {
        relation_id: len(item.dependency_relation_ids)
        for relation_id, item in incomplete_by_id.items()
    }
    dependents: dict[int, list[int]] = {
        relation_id: [] for relation_id in incomplete_ids
    }
    for relation_id, item in incomplete_by_id.items():
        for dependency_id in item.dependency_relation_ids:
            dependents[dependency_id].append(relation_id)
    ready = [
        relation_id
        for relation_id, count in dependency_counts.items()
        if count == 0
    ]
    heapq.heapify(ready)
    dependency_order: list[int] = []
    while ready:
        relation_id = heapq.heappop(ready)
        dependency_order.append(relation_id)
        for dependent_id in dependents[relation_id]:
            dependency_counts[dependent_id] -= 1
            if dependency_counts[dependent_id] == 0:
                heapq.heappush(ready, dependent_id)
    if len(dependency_order) != len(incomplete_ids):
        cycle_members = tuple(
            relation_id
            for relation_id in incomplete_ids
            if dependency_counts[relation_id] > 0
        )
        preview = ", ".join(str(value) for value in cycle_members[:8])
        raise BundleContractError(
            f"incomplete dependency cycle involves relation IDs: {preview}"
        )

    audited_missing: dict[int, MissingReferences] = {}
    previous = 0
    for item in audit.incomplete_relations:
        if item.relation_id <= previous or item.relation_id not in incomplete_by_id:
            raise BundleContractError(
                "audited incomplete relation IDs do not match classification"
            )
        if item.missing_references.count == 0:
            raise BundleContractError(
                f"audited incomplete relation {item.relation_id} has empty closure"
            )
        previous = item.relation_id
        audited_missing[item.relation_id] = item.missing_references
    if tuple(audited_missing) != incomplete_ids:
        raise BundleContractError(
            "audited incomplete relation IDs do not match classification"
        )
    if (
        sum(value.count for value in audited_missing.values())
        > _MAX_SERIALIZED_MISSING_REFERENCES
    ):
        raise BundleContractError(
            "per-root missing-reference serialization exceeds the audit ceiling"
        )

    expected_per_root: dict[int, MissingReferences] = {}
    for relation_id in dependency_order:
        item = incomplete_by_id[relation_id]
        direct_nodes = {
            member.ref
            for member in item.direct_missing_members
            if member.object_type == "node"
        }
        direct_ways = {
            member.ref
            for member in item.direct_missing_members
            if member.object_type == "way"
        }
        direct_relations = {
            member.ref
            for member in item.direct_missing_members
            if member.object_type == "relation"
        }
        direct = MissingReferences(
            node_ids=tuple(sorted(direct_nodes)),
            way_ids=tuple(sorted(direct_ways)),
            relation_ids=tuple(sorted(direct_relations)),
        )
        result = _union_missing(
            (
                direct,
                *(
                    expected_per_root[dependency_id]
                    for dependency_id in item.dependency_relation_ids
                ),
            )
        )
        expected_per_root[relation_id] = result

    for relation_id in incomplete_ids:
        if audited_missing[relation_id] != expected_per_root[relation_id]:
            raise BundleContractError(
                f"relation {relation_id} per-root missing set does not match direct "
                "members and dependencies"
            )
    if classification.missing_references != audit.global_missing_references:
        raise BundleContractError("classification and audit global missing sets differ")
    singleton_union = _union_missing(tuple(audited_missing.values()))
    if singleton_union != audit.global_missing_references:
        raise BundleContractError(
            "incomplete singleton union does not match the global missing set"
        )
    expected_batches = _expected_probe_batches(roots, incomplete_ids)
    if len(expected_batches) > _MAX_PROBE_RECORDS:
        raise BundleContractError("audit probe plan exceeds the bundle ceiling")
    if audit.probed_batches != expected_batches:
        raise BundleContractError("audit probe batches are not the canonical cached plan")
    return (
        incomplete_ids,
        incomplete_by_id,
        audited_missing,
        singleton_union,
        expected_batches,
    )


def _expected_record_missing(
    index: int,
    expected_batches: tuple[tuple[int, ...], ...],
    global_missing: MissingReferences,
    audited_missing: dict[int, MissingReferences],
) -> MissingReferences:
    if index == 0:
        return global_missing
    batch = expected_batches[index]
    if len(batch) == 1 and batch[0] in audited_missing:
        return audited_missing[batch[0]]
    return MissingReferences(node_ids=(), way_ids=(), relation_ids=())


def _base_wsl_argv() -> tuple[str, ...]:
    return (
        WSL_EXECUTABLE,
        "-d",
        PINNED_UBUNTU_DISTRIBUTION,
        "--",
        "/usr/bin/env",
        "-i",
        f"LC_ALL={PINNED_LOCALE}",
        f"LANG={PINNED_LOCALE}",
        "LANGUAGE=C",
    )


def _validate_pinned_probe_record(
    record: ClosureProbeEvidence, index: int
) -> None:
    runtime = record.runtime
    if not isinstance(runtime, RuntimeAttestation):
        raise BundleContractError(f"probe {index} runtime is not exact evidence")
    process_outputs = (
        runtime.release_process,
        runtime.hash_process,
        record.process,
        record.post_hash_process,
    )
    if any(not isinstance(process, ProcessEvidence) for process in process_outputs):
        raise BundleContractError(f"probe {index} process is not exact evidence")
    if any(
        len(process.stdout) > MAX_CAPTURE_BYTES
        or len(process.stderr) > MAX_CAPTURE_BYTES
        or len(_canonical_argv_bytes(process.argv)) > MAX_CAPTURE_BYTES
        for process in process_outputs
    ):
        raise BundleContractError(f"probe {index} exceeds pinned process byte bounds")
    if (
        runtime.ubuntu_distribution != PINNED_UBUNTU_DISTRIBUTION
        or runtime.ubuntu_release != PINNED_UBUNTU_RELEASE
        or runtime.locale != PINNED_LOCALE
        or runtime.runtime_root != RUNTIME_ROOT
        or runtime.osmium_binary_path != OSMIUM_BINARY_PATH
        or runtime.osmium_binary_sha256 != OSMIUM_BINARY_SHA256
        or runtime.boost_library_path != BOOST_LIBRARY_PATH
        or runtime.boost_library_sha256 != BOOST_LIBRARY_SHA256
        or record.source_sha256 != MARYLAND_SOURCE_SHA256
    ):
        raise BundleContractError(f"probe {index} is not the pinned probe identity")
    source_path = record.source_wsl_path
    source = PurePosixPath(source_path)
    if (
        not source.is_absolute()
        or source_path.startswith("//")
        or str(source) != source_path
        or ".." in source.parts
        or "\\" in source_path
        or any(ord(character) < 32 or ord(character) == 127 for character in source_path)
    ):
        raise BundleContractError(f"probe {index} has a noncanonical pinned source path")
    base = _base_wsl_argv()
    expected_release_argv = base + ("/usr/bin/lsb_release", "-ds")
    expected_hash_argv = base + (
        "/usr/bin/sha256sum",
        "--binary",
        OSMIUM_BINARY_PATH,
        BOOST_LIBRARY_PATH,
        source_path,
    )
    expected_hash_stdout = (
        f"{OSMIUM_BINARY_SHA256} *{OSMIUM_BINARY_PATH}\n"
        f"{BOOST_LIBRARY_SHA256} *{BOOST_LIBRARY_PATH}\n"
        f"{MARYLAND_SOURCE_SHA256} *{source_path}\n"
    ).encode("utf-8")
    release = runtime.release_process
    pre = runtime.hash_process
    post = record.post_hash_process
    expected_getid_argv = base + (
        f"LD_LIBRARY_PATH={RUNTIME_LIBRARY_PATH}",
        OSMIUM_BINARY_PATH,
        "getid",
        "--no-progress",
        "-r",
        "--verbose-ids",
        "-f",
        "pbf",
        "-O",
        "-o",
        "/dev/null",
        source_path,
        *(f"r{relation_id}" for relation_id in record.relation_ids),
    )
    if (
        release.argv != expected_release_argv
        or release.returncode != 0
        or release.stdout != f"{PINNED_UBUNTU_RELEASE}\n".encode("ascii")
        or release.stderr != b""
        or pre.argv != expected_hash_argv
        or post.argv != expected_hash_argv
        or pre.returncode != 0
        or post.returncode != 0
        or pre.stdout != expected_hash_stdout
        or post.stdout != expected_hash_stdout
        or pre.stderr != b""
        or post.stderr != b""
        or record.process.argv != expected_getid_argv
    ):
        raise BundleContractError(f"probe {index} is not exact pinned probe evidence")


def _validate_probe_records(
    records: tuple[ClosureProbeEvidence, ...],
    expected_batches: tuple[tuple[int, ...], ...],
    global_missing: MissingReferences,
    audited_missing: dict[int, MissingReferences],
) -> None:
    if not isinstance(records, tuple) or len(records) != len(expected_batches):
        raise BundleContractError("probe record count does not match the audit plan")
    if len(records) > _MAX_PROBE_RECORDS:
        raise BundleContractError("probe record count exceeds the bundle ceiling")
    source_sha256: str | None = None
    runtime_identity: tuple[object, ...] | None = None
    retained_evidence_bytes = 0
    for index, (record, expected_batch) in enumerate(zip(records, expected_batches)):
        if not isinstance(record, ClosureProbeEvidence):
            raise BundleContractError("probe records use an unsupported type")
        if record.relation_ids != expected_batch:
            raise BundleContractError(f"probe {index} batch does not match the audit")
        _validate_pinned_probe_record(record, index)
        for process in (
            record.runtime.release_process,
            record.runtime.hash_process,
            record.process,
            record.post_hash_process,
        ):
            retained_evidence_bytes += (
                len(_canonical_argv_bytes(process.argv))
                + len(process.stdout)
                + len(process.stderr)
            )
        if retained_evidence_bytes > _MAX_BUNDLE_BYTES:
            raise BundleContractError(
                "retained probe evidence exceeds the bundle byte ceiling"
            )
        expected_missing = _expected_record_missing(
            index, expected_batches, global_missing, audited_missing
        )
        if record.missing_references != expected_missing:
            raise BundleContractError(f"probe {index} missing set does not match the audit")
        try:
            reparsed = parse_getid_result(
                returncode=record.process.returncode,
                stdout=record.process.stdout,
                stderr=record.process.stderr,
                source_wsl_path=record.source_wsl_path,
                relation_ids=record.relation_ids,
            )
        except Exception as error:
            raise BundleContractError(
                f"probe {index} getid transcript is not independently valid"
            ) from error
        if reparsed != expected_missing:
            raise BundleContractError(f"probe {index} transcript result differs from audit")
        if source_sha256 is None:
            source_sha256 = record.source_sha256
        elif record.source_sha256 != source_sha256:
            raise BundleContractError("probe records use different source artifacts")
        identity = (
            record.runtime.ubuntu_distribution,
            record.runtime.ubuntu_release,
            record.runtime.locale,
            record.runtime.runtime_root,
            record.runtime.osmium_binary_path,
            record.runtime.osmium_binary_sha256,
            record.runtime.boost_library_path,
            record.runtime.boost_library_sha256,
        )
        if runtime_identity is None:
            runtime_identity = identity
        elif identity != runtime_identity:
            raise BundleContractError("probe records use different runtime identities")
        pre = record.runtime.hash_process
        post = record.post_hash_process
        if (
            pre.argv != post.argv
            or pre.returncode != 0
            or post.returncode != 0
            or pre.stdout != post.stdout
            or pre.stderr != b""
            or post.stderr != b""
        ):
            raise BundleContractError(
                f"probe {index} pre/post source attestations do not match"
            )


def _process_raw_files(
    probe_index: int, name: str, process: ProcessEvidence
) -> tuple[dict[str, object], tuple[BundleFile, BundleFile, BundleFile]]:
    base = f"raw/probes/{probe_index:03d}/{name}"
    argv_bytes = _canonical_argv_bytes(process.argv)
    argv_path = f"{base}/argv.json"
    stdout_path = f"{base}/stdout.bin"
    stderr_path = f"{base}/stderr.bin"
    descriptor = {
        "argv": {
            "bytes": len(argv_bytes),
            "relativeFile": argv_path,
            "sha256": _sha256(argv_bytes),
        },
        "returnCode": process.returncode,
        "stderr": {
            "bytes": len(process.stderr),
            "relativeFile": stderr_path,
            "sha256": _sha256(process.stderr),
        },
        "stdout": {
            "bytes": len(process.stdout),
            "relativeFile": stdout_path,
            "sha256": _sha256(process.stdout),
        },
    }
    if descriptor["argv"]["sha256"] != process.argv_sha256:
        raise BundleContractError("process argv canonical hash changed")
    return (
        descriptor,
        (
            BundleFile(argv_path, argv_bytes),
            BundleFile(stdout_path, process.stdout),
            BundleFile(stderr_path, process.stderr),
        ),
    )


def _execution_document_and_raw_files(
    *,
    classification_sha256: str,
    profile: str,
    source_identity_sha256: str,
    records: tuple[ClosureProbeEvidence, ...],
) -> tuple[dict[str, object], tuple[BundleFile, ...]]:
    raw_files: list[BundleFile] = []
    probes: list[dict[str, object]] = []
    for index, record in enumerate(records):
        processes: dict[str, object] = {}
        for name, process in (
            ("release", record.runtime.release_process),
            ("preHash", record.runtime.hash_process),
            ("getid", record.process),
            ("postHash", record.post_hash_process),
        ):
            descriptor, files = _process_raw_files(index, name, process)
            processes[name] = descriptor
            raw_files.extend(files)
        probes.append(
            {
                "batchRelationIds": list(record.relation_ids),
                "index": index,
                "missingReferenceCount": record.missing_references.count,
                "processes": processes,
                "sourceArtifactSha256": record.source_sha256,
                "status": record.status,
            }
        )
    first = records[0]
    document: dict[str, object] = {
        "classificationSha256": classification_sha256,
        "probeCount": len(records),
        "probes": probes,
        "profile": profile,
        "runtime": {
            "boostLibrarySha256": first.runtime.boost_library_sha256,
            "libosmiumVersion": PINNED_LIBOSMIUM_VERSION,
            "locale": first.runtime.locale,
            "osmiumBinarySha256": first.runtime.osmium_binary_sha256,
            "osmiumVersion": PINNED_OSMIUM_VERSION,
            "ubuntuDistribution": first.runtime.ubuntu_distribution,
            "ubuntuRelease": first.runtime.ubuntu_release,
        },
        "schema": "flight-alert-exp8-osm-relation-closure-execution-v1",
        "sourceArtifactSha256": first.source_sha256,
        "sourceIdentitySha256": source_identity_sha256,
    }
    return document, tuple(raw_files)


def _build_relation_closure_audit_bundle(
    *,
    profile: str,
    source_identity_sha256: str,
    selection_manifest_sha256: str,
    candidate_xml_sha256: str,
    roots: RootSelection,
    classification: RegionalClosureClassification,
    audit: RelationClosureAudit,
    probe_records: tuple[ClosureProbeEvidence, ...],
) -> RelationClosureAuditBundle:
    """Build deterministic classification and separately bound execution evidence."""

    if profile not in {
        SYNTHETIC_RELATION_CLOSURE_PROFILE,
        MARYLAND_REGIONAL_PROFILE,
    }:
        raise BundleContractError(f"unsupported or downgraded profile: {profile!r}")
    source_identity = _canonical_hash(source_identity_sha256, "source identity")
    selection_hash = _canonical_hash(
        selection_manifest_sha256, "selection manifest"
    )
    candidate_hash = _canonical_hash(candidate_xml_sha256, "candidate XML")
    (
        incomplete_ids,
        incomplete_by_id,
        audited_missing,
        singleton_union,
        expected_batches,
    ) = _validate_models(roots, classification, audit)
    _validate_probe_records(
        probe_records,
        expected_batches,
        audit.global_missing_references,
        audited_missing,
    )

    empty = MissingReferences(node_ids=(), way_ids=(), relation_ids=())
    relation_documents: list[dict[str, object]] = []
    for relation_id in roots.relation_ids:
        if relation_id in incomplete_by_id:
            item = incomplete_by_id[relation_id]
            missing = audited_missing[relation_id]
            status = "source_incomplete"
            direct_members = [
                {
                    "memberOrdinal": member.ordinal,
                    "missingMemberOrder": index,
                    "objectType": member.object_type,
                    "ref": member.ref,
                    "role": member.role,
                }
                for index, member in enumerate(item.direct_missing_members)
            ]
            dependencies = list(item.dependency_relation_ids)
        else:
            missing = empty
            status = "complete"
            direct_members = []
            dependencies = []
        relation_documents.append(
            {
                "dependencyRelationIds": dependencies,
                "directMissingMembers": direct_members,
                "id": relation_id,
                "missingReferences": _missing_document(missing),
                "status": status,
            }
        )
    retained_batches = 1 if classification.complete_relation_ids else 0
    classification_document: dict[str, object] = {
        "candidateXmlSha256": candidate_hash,
        "closureProof": {
            "globalEqualsSingletonUnion": True,
            "globalMissingReferences": _missing_document(
                audit.global_missing_references
            ),
            "probePlan": {
                "globalBatches": 1,
                "retainedUnionBatches": retained_batches,
                "singletonBatches": len(incomplete_ids),
                "totalBatches": len(expected_batches),
            },
            "retainedUnionIsComplete": True,
            "retainedUnionMissingReferences": _missing_document(empty),
            "singletonUnion": _missing_document(singleton_union),
        },
        "counts": {
            "completeRelations": len(classification.complete_relation_ids),
            "completeWays": len(classification.complete_way_ids),
            "selectedRelations": len(roots.relation_ids),
            "selectedWays": len(roots.way_ids),
            "sourceIncompleteRelations": len(incomplete_ids),
        },
        "profile": profile,
        "relations": relation_documents,
        "schema": "flight-alert-exp8-osm-relation-closure-classification-v2",
        "selectedRelationIds": list(roots.relation_ids),
        "selectedWayIds": list(roots.way_ids),
        "selectionManifestSha256": selection_hash,
        "sourceIdentitySha256": source_identity,
        "wholeWorldEligible": False,
    }
    classification_bytes = _canonical_json_bytes(classification_document)
    execution_document, raw_files = _execution_document_and_raw_files(
        classification_sha256=_sha256(classification_bytes),
        profile=profile,
        source_identity_sha256=source_identity,
        records=probe_records,
    )
    return RelationClosureAuditBundle(
        classification_bytes=classification_bytes,
        execution_manifest_bytes=_canonical_json_bytes(execution_document),
        raw_files=raw_files,
    )


def build_relation_closure_audit_bundle(
    *,
    profile: str,
    source_identity_sha256: str,
    selection_manifest_sha256: str,
    candidate_xml_sha256: str,
    roots: RootSelection,
    classification: RegionalClosureClassification,
    audit: RelationClosureAudit,
    probe_records: tuple[ClosureProbeEvidence, ...],
) -> RelationClosureAuditBundle:
    """Build an honestly synthetic bundle for bounded contract/test evidence."""

    if profile != SYNTHETIC_RELATION_CLOSURE_PROFILE:
        raise BundleContractError(
            "generic construction requires the explicit synthetic profile; "
            "Maryland claims require the exact verified factory"
        )
    return _build_relation_closure_audit_bundle(
        profile=profile,
        source_identity_sha256=source_identity_sha256,
        selection_manifest_sha256=selection_manifest_sha256,
        candidate_xml_sha256=candidate_xml_sha256,
        roots=roots,
        classification=classification,
        audit=audit,
        probe_records=probe_records,
    )


def build_maryland_relation_closure_audit_bundle(
    *,
    source_identity_sha256: str,
    selection_manifest_sha256: str,
    candidate_xml_sha256: str,
    roots: RootSelection,
    classification: RegionalClosureClassification,
    audit: RelationClosureAudit,
    probe_records: tuple[ClosureProbeEvidence, ...],
) -> RelationClosureAuditBundle:
    """Build only the frozen, exact Maryland instance; currently fail-closed."""

    expected = (
        _MARYLAND_EXACT_SOURCE_IDENTITY_SHA256,
        _MARYLAND_EXACT_SELECTION_MANIFEST_SHA256,
        _MARYLAND_EXACT_CANDIDATE_XML_SHA256,
        _MARYLAND_EXACT_ROOTS,
        _MARYLAND_EXACT_CLASSIFICATION_SHA256,
    )
    if any(value is None for value in expected):
        raise BundleContractError(
            "Maryland exact bundle construction is gated until all derived "
            "provenance identities, the root model, and the exact classification "
            "digest are frozen"
        )
    if (
        source_identity_sha256 != _MARYLAND_EXACT_SOURCE_IDENTITY_SHA256
        or selection_manifest_sha256
        != _MARYLAND_EXACT_SELECTION_MANIFEST_SHA256
        or candidate_xml_sha256 != _MARYLAND_EXACT_CANDIDATE_XML_SHA256
        or roots != _MARYLAND_EXACT_ROOTS
    ):
        raise BundleContractError(
            "Maryland audit inputs do not match the frozen verified identity/model"
        )
    bundle = _build_relation_closure_audit_bundle(
        profile=MARYLAND_REGIONAL_PROFILE,
        source_identity_sha256=source_identity_sha256,
        selection_manifest_sha256=selection_manifest_sha256,
        candidate_xml_sha256=candidate_xml_sha256,
        roots=roots,
        classification=classification,
        audit=audit,
        probe_records=probe_records,
    )
    if bundle.classification_sha256 != _MARYLAND_EXACT_CLASSIFICATION_SHA256:
        raise BundleContractError(
            "Maryland audit classification does not match the frozen exact model"
        )
    return bundle


def audit_predicted_with_cached_global(
    *,
    relation_ids: tuple[int, ...],
    predicted_incomplete_ids: tuple[int, ...],
    probe: object,
) -> RelationClosureAudit:
    """Reuse one already-recorded global probe and execute only remaining batches."""

    relations = _strict_ids(relation_ids, "relation audit IDs")
    predicted = _strict_ids(
        predicted_incomplete_ids, "predicted incomplete relation IDs"
    )
    if not relations:
        raise BundleContractError("cached-global audit requires selected relations")
    if len(relations) > _MAX_SELECTED_RELATIONS:
        raise BundleContractError("selected relation count exceeds the audit ceiling")
    if not set(predicted).issubset(relations):
        raise BundleContractError("predicted incomplete relations must be selected")
    retained_count = len(relations) - len(predicted)
    planned_probe_count = 1 + len(predicted) + (1 if retained_count else 0)
    if planned_probe_count > _MAX_PROBE_RECORDS:
        raise BundleContractError("planned probe count exceeds the probe-record ceiling")
    records = getattr(probe, "records", None)
    if (
        not isinstance(records, tuple)
        or len(records) != 1
        or not isinstance(records[0], ClosureProbeEvidence)
        or records[0].relation_ids != relations
    ):
        raise BundleContractError(
            "cached global probe must be the only existing exact batch record"
        )
    cached = records[0]
    _validate_pinned_probe_record(cached, 0)
    try:
        cached_result = parse_getid_result(
            returncode=cached.process.returncode,
            stdout=cached.process.stdout,
            stderr=cached.process.stderr,
            source_wsl_path=cached.source_wsl_path,
            relation_ids=cached.relation_ids,
        )
    except Exception as error:
        raise BundleContractError(
            "cached global pinned probe transcript is invalid"
        ) from error
    if cached_result != cached.missing_references:
        raise BundleContractError(
            "cached global pinned probe result does not match its evidence"
        )
    if not callable(probe):
        raise BundleContractError("cached global probe is not callable")
    consumed = False

    def cached_first(batch: tuple[int, ...]) -> MissingReferences:
        nonlocal consumed
        if not consumed:
            consumed = True
            if batch != relations:
                raise BundleContractError(
                    "closure audit did not request the cached global batch first"
                )
            return cached.missing_references
        result = probe(batch)
        if not isinstance(result, MissingReferences):
            raise BundleContractError("closure probe returned an unsupported result")
        return result

    audit = audit_predicted_relation_root_closures(
        relations, predicted, cached_first
    )
    if not consumed:
        raise BundleContractError("closure audit did not consume the cached global result")
    final_records = getattr(probe, "records", None)
    if (
        not isinstance(final_records, tuple)
        or len(final_records) != len(audit.probed_batches)
        or tuple(record.relation_ids for record in final_records)
        != audit.probed_batches
    ):
        raise BundleContractError(
            "cached-global actual probe records do not match the logical audit batches"
        )
    _validate_probe_records(
        final_records,
        audit.probed_batches,
        audit.global_missing_references,
        {
            item.relation_id: item.missing_references
            for item in audit.incomplete_relations
        },
    )
    return audit


def _decode_canonical_json(content: bytes, label: str) -> object:
    def object_pairs(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise BundleContractError(f"{label} contains duplicate JSON keys")
            result[key] = value
        return result

    def reject_constant(value: str) -> object:
        raise BundleContractError(f"{label} contains non-finite JSON: {value}")

    try:
        document = json.loads(
            content.decode("utf-8"),
            object_pairs_hook=object_pairs,
            parse_constant=reject_constant,
        )
    except BundleContractError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BundleContractError(f"{label} is not canonical UTF-8 JSON") from error
    if _canonical_json_bytes(document) != content:
        raise BundleContractError(f"{label} JSON bytes are not canonical")
    return document


def _object(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict) or any(not isinstance(key, str) for key in value):
        raise BundleContractError(f"{label} must be a JSON object")
    return value


def _array(value: object, label: str) -> list[object]:
    if not isinstance(value, list):
        raise BundleContractError(f"{label} must be a JSON array")
    return value


def _text(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise BundleContractError(f"{label} must be JSON text")
    return value


def _json_ids(value: object, label: str) -> tuple[int, ...]:
    values = _array(value, label)
    if any(isinstance(item, bool) or not isinstance(item, int) for item in values):
        raise BundleContractError(f"{label} must contain only integers")
    return _strict_ids(cast(tuple[int, ...], tuple(values)), label)


def _json_missing(value: object, label: str) -> MissingReferences:
    document = _object(value, label)
    try:
        if set(document) != {"nodeIds", "relationIds", "wayIds"}:
            raise BundleContractError(f"{label} fields are not canonical")
        return MissingReferences(
            node_ids=_json_ids(document["nodeIds"], f"{label} node IDs"),
            way_ids=_json_ids(document["wayIds"], f"{label} way IDs"),
            relation_ids=_json_ids(
                document["relationIds"], f"{label} relation IDs"
            ),
        )
    except BundleContractError:
        raise
    except Exception as error:
        raise BundleContractError(f"{label} is not canonical") from error


def _raw_process_from_manifest(
    *,
    probe_index: int,
    process_name: str,
    value: object,
    raw_by_path: dict[str, bytes],
    referenced_paths: set[str],
) -> ProcessEvidence:
    document = _object(value, f"probe {probe_index} {process_name} process")
    if set(document) != {"argv", "returnCode", "stderr", "stdout"}:
        raise BundleContractError(
            f"probe {probe_index} {process_name} process fields are not canonical"
        )
    returncode = document["returnCode"]
    if isinstance(returncode, bool) or not isinstance(returncode, int):
        raise BundleContractError(
            f"probe {probe_index} {process_name} return code is not an integer"
        )
    contents: dict[str, bytes] = {}
    for stream_name, filename in (
        ("argv", "argv.json"),
        ("stdout", "stdout.bin"),
        ("stderr", "stderr.bin"),
    ):
        descriptor = _object(
            document[stream_name],
            f"probe {probe_index} {process_name} {stream_name}",
        )
        if set(descriptor) != {"bytes", "relativeFile", "sha256"}:
            raise BundleContractError(
                f"probe {probe_index} {process_name} {stream_name} descriptor "
                "is not canonical"
            )
        byte_count = descriptor["bytes"]
        relative_path = descriptor["relativeFile"]
        digest = descriptor["sha256"]
        expected_path = (
            f"raw/probes/{probe_index:03d}/{process_name}/{filename}"
        )
        if (
            isinstance(byte_count, bool)
            or not isinstance(byte_count, int)
            or byte_count < 0
            or relative_path != expected_path
        ):
            raise BundleContractError(
                f"probe {probe_index} {process_name} {stream_name} raw binding "
                "is not canonical"
            )
        canonical_digest = _canonical_hash(
            digest,
            f"probe {probe_index} {process_name} {stream_name}",
        )
        content = raw_by_path.get(expected_path)
        if content is None:
            raise BundleContractError(f"manifest raw file is missing: {expected_path}")
        if expected_path in referenced_paths:
            raise BundleContractError(f"manifest raw file is multiply bound: {expected_path}")
        referenced_paths.add(expected_path)
        if len(content) != byte_count or _sha256(content) != canonical_digest:
            raise BundleContractError(
                f"manifest raw bytes do not match descriptor: {expected_path}"
            )
        contents[stream_name] = content
    argv_value = _decode_canonical_json(
        contents["argv"],
        f"probe {probe_index} {process_name} argv",
    )
    argv_items = _array(argv_value, f"probe {probe_index} {process_name} argv")
    if not argv_items or any(
        not isinstance(argument, str) or not argument for argument in argv_items
    ):
        raise BundleContractError(
            f"probe {probe_index} {process_name} argv is not exact text"
        )
    argv = tuple(argv_items)
    if _canonical_argv_bytes(argv) != contents["argv"]:
        raise BundleContractError(
            f"probe {probe_index} {process_name} argv encoding is not canonical"
        )
    return ProcessEvidence(
        argv=argv,
        returncode=returncode,
        stdout=contents["stdout"],
        stderr=contents["stderr"],
    )


def _validate_profile_instance(
    *,
    profile: str,
    source_identity_sha256: str,
    selection_manifest_sha256: str,
    candidate_xml_sha256: str,
    roots: RootSelection,
    classification_sha256: str,
) -> None:
    if profile == SYNTHETIC_RELATION_CLOSURE_PROFILE:
        return
    if profile != MARYLAND_REGIONAL_PROFILE:
        raise BundleContractError(f"bundle profile is unsupported: {profile!r}")
    expected = (
        _MARYLAND_EXACT_SOURCE_IDENTITY_SHA256,
        _MARYLAND_EXACT_SELECTION_MANIFEST_SHA256,
        _MARYLAND_EXACT_CANDIDATE_XML_SHA256,
        _MARYLAND_EXACT_ROOTS,
        _MARYLAND_EXACT_CLASSIFICATION_SHA256,
    )
    if any(value is None for value in expected):
        raise BundleContractError(
            "Maryland-labeled bundle is gated until the exact derived identity/model "
            "is frozen"
        )
    if (
        source_identity_sha256 != _MARYLAND_EXACT_SOURCE_IDENTITY_SHA256
        or selection_manifest_sha256
        != _MARYLAND_EXACT_SELECTION_MANIFEST_SHA256
        or candidate_xml_sha256 != _MARYLAND_EXACT_CANDIDATE_XML_SHA256
        or roots != _MARYLAND_EXACT_ROOTS
        or classification_sha256 != _MARYLAND_EXACT_CLASSIFICATION_SHA256
    ):
        raise BundleContractError(
            "Maryland-labeled bundle does not match the frozen identity/model"
        )


def _validate_bundle_integrity(bundle: RelationClosureAuditBundle) -> None:
    """Reconstruct the bundle from retained bytes and require exact equality."""

    classification_document = _object(
        _decode_canonical_json(bundle.classification_bytes, "classification"),
        "classification",
    )
    execution_document = _object(
        _decode_canonical_json(bundle.execution_manifest_bytes, "execution manifest"),
        "execution manifest",
    )
    try:
        profile = classification_document["profile"]
        source_identity = classification_document["sourceIdentitySha256"]
        selection_hash = classification_document["selectionManifestSha256"]
        candidate_hash = classification_document["candidateXmlSha256"]
        if not isinstance(profile, str):
            raise BundleContractError("classification profile must be text")
        source_identity = _canonical_hash(
            source_identity, "classification source identity"
        )
        selection_hash = _canonical_hash(
            selection_hash, "classification selection manifest"
        )
        candidate_hash = _canonical_hash(
            candidate_hash, "classification candidate XML"
        )
        roots = RootSelection(
            way_ids=_json_ids(
                classification_document["selectedWayIds"], "selected way IDs"
            ),
            relation_ids=_json_ids(
                classification_document["selectedRelationIds"],
                "selected relation IDs",
            ),
        )
        relation_values = _array(
            classification_document["relations"], "classification relations"
        )
        complete_relation_ids: list[int] = []
        incomplete_roots: list[IncompleteRelationRoot] = []
        incomplete_closures: list[IncompleteRelationClosure] = []
        for index, relation_value in enumerate(relation_values):
            relation = _object(relation_value, f"classification relation {index}")
            relation_id = relation["id"]
            if (
                isinstance(relation_id, bool)
                or not isinstance(relation_id, int)
                or relation_id <= 0
            ):
                raise BundleContractError(
                    f"classification relation {index} ID is not positive"
                )
            status = relation["status"]
            missing = _json_missing(
                relation["missingReferences"],
                f"classification relation {relation_id} missing references",
            )
            if status == "complete":
                complete_relation_ids.append(relation_id)
            elif status == "source_incomplete":
                members: list[OsmRelationMember] = []
                direct_values = _array(
                    relation["directMissingMembers"],
                    f"classification relation {relation_id} direct members",
                )
                for member_index, member_value in enumerate(direct_values):
                    member = _object(
                        member_value,
                        f"classification relation {relation_id} direct member",
                    )
                    if member.get("missingMemberOrder") != member_index:
                        raise BundleContractError(
                            f"classification relation {relation_id} direct-member "
                            "order is not canonical"
                        )
                    object_type = member["objectType"]
                    ref = member["ref"]
                    role = member["role"]
                    ordinal = member["memberOrdinal"]
                    if (
                        not isinstance(object_type, str)
                        or isinstance(ref, bool)
                        or not isinstance(ref, int)
                        or not isinstance(role, str)
                        or isinstance(ordinal, bool)
                        or not isinstance(ordinal, int)
                    ):
                        raise BundleContractError(
                            f"classification relation {relation_id} direct member "
                            "has unsupported values"
                        )
                    members.append(
                        OsmRelationMember(object_type, ref, role, ordinal=ordinal)
                    )
                dependencies = _json_ids(
                    relation["dependencyRelationIds"],
                    f"classification relation {relation_id} dependencies",
                )
                incomplete_roots.append(
                    IncompleteRelationRoot(
                        relation_id=relation_id,
                        direct_missing_members=tuple(members),
                        dependency_relation_ids=dependencies,
                    )
                )
                incomplete_closures.append(
                    IncompleteRelationClosure(relation_id, missing)
                )
            else:
                raise BundleContractError(
                    f"classification relation {relation_id} status is unsupported"
                )
        closure_proof = _object(
            classification_document["closureProof"], "classification closure proof"
        )
        global_missing = _json_missing(
            closure_proof["globalMissingReferences"],
            "classification global missing references",
        )
    except BundleContractError:
        raise
    except (KeyError, TypeError, IndexError, ValueError) as error:
        raise BundleContractError(
            "classification document is incomplete or malformed"
        ) from error

    classification = RegionalClosureClassification(
        complete_way_ids=roots.way_ids,
        complete_relation_ids=tuple(complete_relation_ids),
        incomplete_relations=tuple(incomplete_roots),
        missing_references=global_missing,
    )
    incomplete_ids = tuple(item.relation_id for item in incomplete_roots)
    expected_batches = _expected_probe_batches(roots, incomplete_ids)
    audit = RelationClosureAudit(
        complete_relation_ids=tuple(complete_relation_ids),
        incomplete_relations=tuple(incomplete_closures),
        global_missing_references=global_missing,
        probed_batches=expected_batches,
    )
    _validate_profile_instance(
        profile=profile,
        source_identity_sha256=source_identity,
        selection_manifest_sha256=selection_hash,
        candidate_xml_sha256=candidate_hash,
        roots=roots,
        classification_sha256=bundle.classification_sha256,
    )

    raw_by_path = {item.relative_path: item.content for item in bundle.raw_files}
    referenced_paths: set[str] = set()
    try:
        probe_values = _array(execution_document["probes"], "execution probes")
        runtime_document = _object(execution_document["runtime"], "execution runtime")
        records: list[ClosureProbeEvidence] = []
        audited_missing = {
            item.relation_id: item.missing_references for item in incomplete_closures
        }
        for index, probe_value in enumerate(probe_values):
            probe_document = _object(probe_value, f"execution probe {index}")
            processes_document = _object(
                probe_document["processes"], f"execution probe {index} processes"
            )
            processes = {
                name: _raw_process_from_manifest(
                    probe_index=index,
                    process_name=name,
                    value=processes_document[name],
                    raw_by_path=raw_by_path,
                    referenced_paths=referenced_paths,
                )
                for name in ("release", "preHash", "getid", "postHash")
            }
            pre_hash_argv = processes["preHash"].argv
            if not pre_hash_argv:
                raise BundleContractError(f"execution probe {index} pre-hash argv is empty")
            relation_ids = _json_ids(
                probe_document["batchRelationIds"],
                f"execution probe {index} batch relation IDs",
            )
            expected_missing = _expected_record_missing(
                index, expected_batches, global_missing, audited_missing
            )
            runtime = RuntimeAttestation(
                ubuntu_distribution=_text(
                    runtime_document["ubuntuDistribution"],
                    "execution Ubuntu distribution",
                ),
                ubuntu_release=_text(
                    runtime_document["ubuntuRelease"], "execution Ubuntu release"
                ),
                locale=_text(runtime_document["locale"], "execution locale"),
                runtime_root=RUNTIME_ROOT,
                osmium_binary_path=OSMIUM_BINARY_PATH,
                osmium_binary_sha256=_canonical_hash(
                    runtime_document["osmiumBinarySha256"],
                    "execution osmium binary",
                ),
                boost_library_path=BOOST_LIBRARY_PATH,
                boost_library_sha256=_canonical_hash(
                    runtime_document["boostLibrarySha256"],
                    "execution Boost library",
                ),
                release_process=processes["release"],
                hash_process=processes["preHash"],
            )
            records.append(
                ClosureProbeEvidence(
                    relation_ids=relation_ids,
                    source_wsl_path=pre_hash_argv[-1],
                    source_sha256=_canonical_hash(
                        probe_document["sourceArtifactSha256"],
                        f"execution probe {index} source artifact",
                    ),
                    missing_references=expected_missing,
                    runtime=runtime,
                    process=processes["getid"],
                    post_hash_process=processes["postHash"],
                )
            )
    except BundleContractError:
        raise
    except (KeyError, TypeError, IndexError, ValueError) as error:
        raise BundleContractError(
            "execution manifest is incomplete or malformed"
        ) from error
    if referenced_paths != set(raw_by_path):
        missing_bindings = sorted(set(raw_by_path).difference(referenced_paths))
        raise BundleContractError(
            "bundle contains unreferenced raw files: " + ", ".join(missing_bindings[:3])
        )

    rebuilt = _build_relation_closure_audit_bundle(
        profile=profile,
        source_identity_sha256=source_identity,
        selection_manifest_sha256=selection_hash,
        candidate_xml_sha256=candidate_hash,
        roots=roots,
        classification=classification,
        audit=audit,
        probe_records=tuple(records),
    )
    if rebuilt != bundle:
        raise BundleContractError(
            "bundle documents/raw files do not equal the canonical reconstructed bundle"
        )


def _write_exclusive_file(root: Path, item: BundleFile) -> None:
    target = root.joinpath(*PurePosixPath(item.relative_path).parts)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as stream:
        stream.write(item.content)
        stream.flush()
        os.fsync(stream.fileno())


def _windows_move_directory_no_replace(source: Path, target: Path) -> None:
    """Atomically publish on Windows without replacement and wait for disk commit."""

    if not _WINDOWS_NO_REPLACE_RENAME:
        raise OSError("Windows no-replace move is unavailable")
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    move_file_ex = kernel32.MoveFileExW
    move_file_ex.argtypes = [wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD]
    move_file_ex.restype = wintypes.BOOL
    if not move_file_ex(str(source), str(target), _MOVEFILE_WRITE_THROUGH):
        error_number = ctypes.get_last_error()
        raise OSError(
            error_number,
            ctypes.FormatError(error_number),
            str(source),
            error_number,
            str(target),
        )


def write_relation_closure_audit_bundle(
    bundle: RelationClosureAuditBundle,
    destination: str | Path,
) -> BundleWriteResult:
    """Write a new bundle transactionally; never replace an existing destination."""

    if not isinstance(bundle, RelationClosureAuditBundle):
        raise BundleContractError("writer requires a relation-closure audit bundle")
    if not _WINDOWS_NO_REPLACE_RENAME:
        raise BundleContractError(
            "bundle writer requires Windows atomic no-replace rename semantics"
        )
    _validate_bundle_integrity(bundle)
    target = Path(destination)
    if not target.name or target.name in {".", ".."}:
        raise BundleContractError("bundle destination must name a new directory")
    try:
        parent = target.parent.resolve(strict=True)
    except OSError as error:
        raise BundleContractError(f"bundle parent is unavailable: {error}") from error
    if not parent.is_dir():
        raise BundleContractError("bundle parent must be an existing directory")
    target = parent / target.name
    if target.exists() or target.is_symlink():
        raise BundleContractError(f"bundle destination already exists: {target}")

    files = (
        BundleFile("classification.json", bundle.classification_bytes),
        BundleFile("execution-evidence.json", bundle.execution_manifest_bytes),
        *bundle.raw_files,
    )
    try:
        staging: Path | None = Path(
            tempfile.mkdtemp(
                prefix=f".{target.name}.", suffix=".staging", dir=parent
            )
        )
    except OSError as error:
        raise BundleContractError(
            f"bundle staging directory could not be created: {error}"
        ) from error
    try:
        assert staging is not None
        for item in files:
            _write_exclusive_file(staging, item)
        if target.exists() or target.is_symlink():
            raise BundleContractError(
                f"bundle destination already exists before finalization: {target}"
            )
        _windows_move_directory_no_replace(staging, target)
        staging = None
    except BaseException as error:
        cleanup_error: OSError | None = None
        if staging is not None and staging.exists():
            try:
                shutil.rmtree(staging)
            except OSError as failure:
                cleanup_error = failure
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            raise
        message = f"bundle transaction failed: {error}"
        if cleanup_error is not None:
            message += f"; staging cleanup failed: {cleanup_error}"
        raise BundleContractError(message) from error
    return BundleWriteResult(
        destination=target.resolve(strict=True),
        classification_sha256=bundle.classification_sha256,
        execution_manifest_sha256=bundle.execution_manifest_sha256,
        file_count=len(files),
        total_bytes=sum(len(item.content) for item in files),
    )


__all__ = [
    "SYNTHETIC_RELATION_CLOSURE_PROFILE",
    "BundleContractError",
    "BundleFile",
    "BundleWriteResult",
    "RelationClosureAuditBundle",
    "audit_predicted_with_cached_global",
    "build_maryland_relation_closure_audit_bundle",
    "build_relation_closure_audit_bundle",
    "write_relation_closure_audit_bundle",
]
