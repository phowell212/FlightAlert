from __future__ import annotations

import hashlib
import json
import sqlite3
import struct
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch


_SYNTHETIC_BASE = 8_000_000_000_000_000
_POLICY_DOCUMENT = {
    "candidateTraversal": {
        "cycleCheckBeforeBudget": True,
        "cycleOccurrence": "reason-and-join-barrier-without-recursion",
        "excludedBranchesConsumeGeometryBudget": False,
        "joinRule": "ordered-dfs-adjacent-same-effective-type-shared-endpoint-node-id",
        "nodeMembers": "audit-evidence-without-line-or-join-barrier",
        "reasonOccurrenceOrder": "ordered-depth-first-source-path",
        "relationTypePrecedence": [
            "exact-supported-declaration",
            "nearest-inherited-supported-type",
        ],
        "wayTypePrecedence": [
            "nearest-effective-relation-type",
            "exact-way-waterway",
        ],
        "supportedWayAccounting": "charge raw part and all raw points before joining",
        "unsupportedRelationDeclaration": (
            "reason-and-join-barrier-without-candidate-descent"
        ),
        "unsupportedWayWithoutEffectiveRelationType": (
            "reason-and-join-barrier-without-coordinate-materialization"
        ),
    },
    "canonicalEvidence": {
        "admissionStreamDomain": "FAE8WATERADMISSION2\u0000",
        "candidateFeatureDomain": "FAE8WATERCANDIDATEFEATURE2\u0000",
        "candidateStreamDomain": "FAE8WATERCANDIDATESTREAM2\u0000",
        "candidateStreamEncoding": "uint64-be length plus 32-byte candidate feature SHA-256",
        "dependencyEvidenceDomain": "FAE8OSMWATERWAYDEPENDENCIES1\u0000",
        "dependencyRecordEncoding": (
            "sorted-key compact UTF-8 with exactly one terminal LF"
        ),
        "dependencyStreamEncoding": (
            "domain then repeated uint64-be record byte length plus record bytes"
        ),
        "entryEncoding": "sorted-key compact UTF-8 without BOM or terminal LF",
        "featureMetadataEncoding": (
            "domain then uint64-be metadata byte length plus sorted-key compact UTF-8 "
            "without BOM or terminal LF"
        ),
        "framing": "uint64-be byte length followed by bytes",
        "geometryEncoding": (
            "uint64-be part count; per part P plus uint64-be point count; repeated "
            "uint64-be node ID plus int32-be longitude E7 plus int32-be latitude E7"
        ),
        "histogramUnits": {
            "reasonHistogram": "occurrences",
            "rootStatusHistogram": "roots",
        },
        "sourcePathEncoding": (
            "root [kindCode,id,-1], then source occurrences "
            "[kindCode,id,memberOrdinal]"
        ),
        "sourceFeatureDomain": "FAE8OSMGLOBALWATERWAYFEATURE3\u0000",
    },
    "completeness": {
        "incompletePlacementSource": "DIRECT_SOURCE_PATH",
        "relationCompleteWhen": (
            "exactly one supported candidate type and no excluded or cycle line occurrence"
        ),
        "relationIncompleteOmits": [
            "COMPLETE_RELATION_LENGTH",
            "complete-geometry prominence",
            "EXACT_PARENT_PATH",
        ],
        "selectedZeroCandidateMeaning": "exact-source-occurrence-not-semantic-empty",
    },
    "geometry": {
        "accounting": {
            "candidateFeatures": "post-join parts and points",
            "relationDepthAndVisits": "maximum-depth and occurrence visits",
            "supportedWays": "raw pre-join parts and points",
        },
        "limits": {
            "maxAdmissionEntryBytes": 134_217_728,
            "maxCandidateDepth": 64,
            "maxCandidatePoints": 524_288,
            "maxCandidateRawParts": 65_536,
            "maxCandidateRelationVisits": 65_536,
            "maxDependencyRecordBytes": 134_217_728,
            "maxDependencyRecords": 18_446_744_073_709_551_615,
            "maxStructuralDepth": 64,
            "maxStructuralMemberOccurrences": 524_288,
            "maxStructuralRelationVisits": 65_536,
            "maxStructuralWayNodeOccurrences": 8_388_608,
        },
        "resourceFailure": "fatal",
    },
    "legacyFirstTerminalReasonOrder": [
        "unsupported_relation_waterway",
        "unsupported_member_way_waterway",
        "no_usable_way_geometry",
        "relation_cycle",
    ],
    "reasonCodes": [
        "relation_cycle",
        "unsupported_relation_waterway",
        "unsupported_member_way_waterway",
    ],
    "rootKindOrder": [
        {"code": 1, "kind": "way"},
        {"code": 2, "kind": "relation"},
    ],
    "rootStatuses": [
        "fatal",
        "line_candidates_with_noncandidate_members",
        "line_candidates",
        "no_line_geometry",
        "no_supported_line_candidate",
    ],
    "schema": "flightalert.experiment8.osm-waterway-line-candidate-policy.v2",
    "structuralTraversal": {
        "cycleEdge": "record-and-do-not-recurse",
        "fatalContradictions": [
            "missing-object",
            "malformed-or-noncontiguous-ordinal",
            "member-way-fewer-than-two-nodes",
            "unknown-member-object-kind",
            "selected-root-name-or-predicate-contradiction",
            "resource-ceiling",
        ],
        "scope": "complete reachable closure including candidate-pruned branches",
        "unsupportedWaterwayValue": "audited-candidate-noncandidate-not-structural-fatal",
    },
    "supportedWaterways": ["river", "stream", "canal", "tidal_channel", "wadi"],
}
_POLICY_SHA256 = "d6fe6d0e1e4400736abbbfd3f379a502ee6b36d0d3900b65cff2a9169a1c99f6"


def _canonical_bytes(value: object, *, terminal_lf: bool = True) -> bytes:
    suffix = "\n" if terminal_lf else ""
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        + suffix
    ).encode("utf-8")


def _fixture_binding(opl: Path, roots: Path):
    from tools.experiment8.osm_global_waterway_package import WaterwaySourceBinding

    opl_raw = opl.read_bytes()
    roots_raw = roots.read_bytes()
    return WaterwaySourceBinding(
        planet_path="fixture://candidate-policy-v2.osm.pbf",
        planet_bytes=1,
        planet_sha256=hashlib.sha256(b"synthetic-v2-planet").hexdigest(),
        selection_manifest_sha256=hashlib.sha256(b"synthetic-v2-manifest").hexdigest(),
        selection_policy_sha256=(
            "7ddea49ea1501790519b6b47c2cd8170ce3043218551f1b978c98ffb35e7b50c"
        ),
        root_ids_bytes=len(roots_raw),
        root_ids_sha256=hashlib.sha256(roots_raw).hexdigest(),
        closure_pbf_bytes=1,
        closure_pbf_sha256=hashlib.sha256(b"synthetic-v2-closure-pbf").hexdigest(),
        closure_opl_bytes=len(opl_raw),
        closure_opl_sha256=hashlib.sha256(opl_raw).hexdigest(),
        extraction_receipt_sha256=hashlib.sha256(
            b"synthetic-v2-extraction-receipt"
        ).hexdigest(),
    )


def _tags(values: dict[str, str]) -> str:
    if not values:
        return ""
    return " T" + ",".join(f"{key}={value}" for key, value in values.items())


def _write_fixture(
    directory: Path,
    *,
    nodes: dict[int, tuple[str, str]],
    ways: dict[int, tuple[dict[str, str], tuple[int, ...]]],
    relations: dict[int, tuple[dict[str, str], tuple[tuple[str, int, str], ...]]],
    roots: tuple[tuple[str, int], ...],
) -> tuple[Path, Path]:
    lines: list[str] = []
    for object_id in sorted(nodes):
        longitude, latitude = nodes[object_id]
        lines.append(
            f"n{object_id} v1 dV t2026-06-29T00:00:00Z x{longitude} y{latitude}"
        )
    for object_id in sorted(ways):
        tags, refs = ways[object_id]
        lines.append(
            f"w{object_id} v1 dV t2026-06-29T00:00:00Z"
            f"{_tags(tags)} N" + ",".join(f"n{ref}" for ref in refs)
        )
    for object_id in sorted(relations):
        tags, members = relations[object_id]
        encoded = ",".join(f"{kind}{ref}@{role}" for kind, ref, role in members)
        lines.append(
            f"r{object_id} v1 dV t2026-06-29T00:00:00Z"
            f"{_tags(tags)} M{encoded}"
        )
    opl = directory / "synthetic-waterway-closure.opl"
    root_ids = directory / "synthetic-waterway-root-ids.txt"
    opl.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    root_ids.write_bytes(
        "".join(f"{kind}{object_id}\n" for kind, object_id in roots).encode("ascii")
    )
    return opl, root_ids


def _standard_nodes(count: int = 8) -> dict[int, tuple[str, str]]:
    return {
        _SYNTHETIC_BASE + index: (f"-{70 + index}.0000000", "40.0000000")
        for index in range(1, count + 1)
    }


def _ingest(
    directory: Path,
    *,
    ways: dict[int, tuple[dict[str, str], tuple[int, ...]]],
    relations: dict[int, tuple[dict[str, str], tuple[tuple[str, int, str], ...]]],
    roots: tuple[tuple[str, int], ...],
    nodes: dict[int, tuple[str, str]] | None = None,
    work_name: str = "work",
    pause_after_admission_roots: int | None = None,
    checkpoint_admission_roots: int = 1,
):
    from tools.experiment8.osm_global_waterway_store import (
        ingest_global_waterway_closure,
    )

    opl, root_ids = _write_fixture(
        directory,
        nodes=nodes or _standard_nodes(),
        ways=ways,
        relations=relations,
        roots=roots,
    )
    binding = _fixture_binding(opl, root_ids)
    result = ingest_global_waterway_closure(
        opl_path=opl,
        root_ids_path=root_ids,
        work_directory=directory / work_name,
        source_binding=binding,
        checkpoint_objects=3,
        checkpoint_admission_roots=checkpoint_admission_roots,
        pause_after_admission_roots=pause_after_admission_roots,
    )
    return result, binding, opl, root_ids


def _entry(database: Path, kind: int, object_id: int) -> dict[str, object]:
    connection = sqlite3.connect(database)
    try:
        row = connection.execute(
            "SELECT entry_json FROM admission_roots WHERE root_kind=? AND root_id=?",
            (kind, object_id),
        ).fetchone()
    finally:
        connection.close()
    if row is None:
        raise AssertionError("admission root entry is absent")
    raw = bytes(row[0])
    if raw.endswith(b"\n") or raw.startswith(b"\xef\xbb\xbf"):
        raise AssertionError("admission entry is not canonical no-LF UTF-8")
    value = json.loads(raw.decode("utf-8", "strict"))
    if _canonical_bytes(value, terminal_lf=False) != raw:
        raise AssertionError("admission entry bytes are not canonical")
    return value


def _features(database: Path, binding):
    from tools.experiment8.osm_global_waterway_store import iter_exact_waterway_features

    return tuple(iter_exact_waterway_features(database, source_binding=binding))


class CandidatePolicyIdentityTests(unittest.TestCase):
    def test_exact_policy_document_and_sha256_are_pinned(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        self.assertEqual(_POLICY_DOCUMENT, store._line_candidate_policy_document())
        self.assertEqual(3_935, len(_canonical_bytes(_POLICY_DOCUMENT)))
        self.assertEqual(_POLICY_SHA256, store.LINE_CANDIDATE_POLICY_SHA256)
        self.assertEqual(
            _POLICY_SHA256,
            hashlib.sha256(_canonical_bytes(_POLICY_DOCUMENT)).hexdigest(),
        )

    def test_authenticated_package_source_has_exact_lf_git_rule_and_identity(self) -> None:
        repository = Path(__file__).parents[3]
        attributes = (repository / ".gitattributes").read_text("utf-8").splitlines()
        self.assertEqual(
            1,
            attributes.count("/tools/experiment8/*.py text eol=lf"),
        )
        immediate_sources = sorted((repository / "tools/experiment8").glob("*.py"))
        self.assertTrue(immediate_sources)
        self.assertEqual(
            [],
            [path.name for path in immediate_sources if b"\r\n" in path.read_bytes()],
        )
        rule = "/tools/experiment8/osm_global_waterway_package.py text eol=lf"
        self.assertEqual(1, attributes.count(rule))
        source = (repository / "tools/experiment8/osm_global_waterway_package.py").read_bytes()
        self.assertEqual(96_418, len(source))
        self.assertEqual(
            "b0dde525e09ac8748e56355420a3833d6cdc0b56e67432e2148fe1c89d7eae22",
            hashlib.sha256(source).hexdigest(),
        )
        self.assertNotIn(b"\r\n", source)

    def test_dependency_and_feature_hash_encodings_are_independently_reconstructed(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_renderer import ExactWaterwayPoint

        records = (["synthetic", 1], ["synthetic", 2, {"exact": True}])
        evidence = store._DependencyEvidence()
        expected_dependency = hashlib.sha256()
        expected_dependency.update(b"FAE8OSMWATERWAYDEPENDENCIES1\0")
        for record in records:
            evidence.add(record)
            raw = _canonical_bytes(record)
            expected_dependency.update(struct.pack(">Q", len(raw)))
            expected_dependency.update(raw)
        self.assertEqual(
            expected_dependency.hexdigest(), evidence.document()["sha256"]
        )

        candidate_metadata = {
            "completeNamedRelation": False,
            "waterwayType": "river",
        }
        source_metadata = {**candidate_metadata, "source": "synthetic"}
        parts = (
            (
                ExactWaterwayPoint(
                    _SYNTHETIC_BASE + 1, -700_000_000, 400_000_000
                ),
                ExactWaterwayPoint(
                    _SYNTHETIC_BASE + 2, -699_999_999, 400_000_001
                ),
            ),
        )
        actual_candidate, actual_source = store._hash_materialized_candidate(
            candidate_metadata=candidate_metadata,
            source_metadata=source_metadata,
            parts=parts,
        )

        def independent_feature_hash(domain: bytes, metadata: object) -> bytes:
            digest = hashlib.sha256()
            digest.update(domain)
            metadata_raw = _canonical_bytes(metadata, terminal_lf=False)
            digest.update(struct.pack(">Q", len(metadata_raw)))
            digest.update(metadata_raw)
            digest.update(struct.pack(">Q", len(parts)))
            for part in parts:
                digest.update(b"P")
                digest.update(struct.pack(">Q", len(part)))
                for point in part:
                    digest.update(
                        struct.pack(
                            ">Qii",
                            point.node_id,
                            point.longitude_e7,
                            point.latitude_e7,
                        )
                    )
            return digest.digest()

        self.assertEqual(
            independent_feature_hash(
                b"FAE8WATERCANDIDATEFEATURE2\0", candidate_metadata
            ),
            actual_candidate,
        )
        self.assertEqual(
            independent_feature_hash(
                b"FAE8OSMGLOBALWATERWAYFEATURE3\0", source_metadata
            ),
            actual_source,
        )


class CandidateTraversalTests(unittest.TestCase):
    def test_node_only_relation_is_audited_no_line_geometry_not_known_empty(self) -> None:
        relation = _SYNTHETIC_BASE + 401
        node = _SYNTHETIC_BASE + 1
        with tempfile.TemporaryDirectory() as temporary:
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={},
                relations={
                    relation: (
                        {"type": "waterway", "name": "SyntheticNodeOnly"},
                        (("n", node, "spring"),),
                    )
                },
                roots=(("r", relation),),
            )
            entry = _entry(result.database_path, 2, relation)
        self.assertEqual("no_line_geometry", entry["status"])
        self.assertEqual(1, entry["nodeEvidenceCount"])
        self.assertEqual(0, entry["candidateFeatureCount"])
        self.assertNotIn("KnownEmpty", json.dumps(entry))

    def test_unsupported_root_is_audited_barrier_while_structure_reaches_way(self) -> None:
        way = _SYNTHETIC_BASE + 201
        relation = _SYNTHETIC_BASE + 402
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary:
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={way: ({"waterway": "river"}, (n1, n2))},
                relations={
                    relation: (
                        {
                            "type": "waterway",
                            "name": "SyntheticUnsupportedRoot",
                            "waterway": "drain",
                        },
                        (("w", way, "main"),),
                    )
                },
                roots=(("r", relation),),
            )
            entry = _entry(result.database_path, 2, relation)
        self.assertTrue(entry["structural"]["hasReachableWay"])
        self.assertEqual("no_supported_line_candidate", entry["status"])
        self.assertEqual(
            ["unsupported_relation_waterway"],
            [item["reasonCode"] for item in entry["reasonOccurrences"]],
        )
        self.assertEqual(0, entry["geometryUsage"]["rawSupportedWayParts"])

    def test_unsupported_member_way_is_audited_without_materializing_points(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        way = _SYNTHETIC_BASE + 202
        relation = _SYNTHETIC_BASE + 403
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            store,
            "_point_rows",
            side_effect=AssertionError("excluded way coordinates were materialized"),
        ):
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={way: ({"waterway": "drain"}, (n1, n2))},
                relations={
                    relation: (
                        {"type": "waterway", "name": "SyntheticUnsupportedMember"},
                        (("w", way, "main"),),
                    )
                },
                roots=(("r", relation),),
            )
            entry = _entry(result.database_path, 2, relation)
        self.assertEqual("no_supported_line_candidate", entry["status"])
        self.assertEqual(
            "unsupported_member_way_waterway",
            entry["reasonOccurrences"][0]["reasonCode"],
        )
        self.assertEqual(0, entry["geometryUsage"]["rawSupportedWayPoints"])

    def test_pure_and_mixed_cycles_are_cycle_safe_and_statused(self) -> None:
        way = _SYNTHETIC_BASE + 203
        pure = _SYNTHETIC_BASE + 404
        mixed = _SYNTHETIC_BASE + 405
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary:
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={way: ({"waterway": "river"}, (n1, n2))},
                relations={
                    pure: (
                        {"type": "waterway", "name": "SyntheticPureCycle"},
                        (("r", pure, "loop"),),
                    ),
                    mixed: (
                        {
                            "type": "waterway",
                            "name": "SyntheticMixedCycle",
                            "waterway": "river",
                        },
                        (("w", way, "main"), ("r", mixed, "loop")),
                    ),
                },
                roots=(("r", pure), ("r", mixed)),
            )
            pure_entry = _entry(result.database_path, 2, pure)
            mixed_entry = _entry(result.database_path, 2, mixed)
        self.assertEqual("no_line_geometry", pure_entry["status"])
        self.assertEqual(
            "line_candidates_with_noncandidate_members", mixed_entry["status"]
        )
        pure_reason = pure_entry["reasonOccurrences"][0]
        mixed_reason = mixed_entry["reasonOccurrences"][0]
        self.assertEqual("relation_cycle", pure_reason["reasonCode"])
        self.assertEqual(
            (None, None),
            (pure_reason["declaredWaterway"], pure_reason["effectiveWaterway"]),
        )
        self.assertEqual(
            ("river", "river"),
            (
                mixed_reason["declaredWaterway"],
                mixed_reason["effectiveWaterway"],
            ),
        )
        for entry, reason in ((pure_entry, pure_reason), (mixed_entry, mixed_reason)):
            matching = [
                evidence
                for evidence in entry["waterwayEvidence"]
                if evidence["sourcePath"] == reason["sourcePath"]
            ]
            self.assertEqual(1, len(matching))
            self.assertEqual(
                (
                    reason["declaredWaterway"],
                    reason["effectiveWaterway"],
                ),
                (
                    matching[0]["declaredWaterway"],
                    matching[0]["effectiveWaterway"],
                ),
            )
        self.assertEqual(1, mixed_entry["structural"]["cycleEdges"])

    def test_mixed_supported_and_unsupported_branches_retains_incomplete_candidate(self) -> None:
        supported, excluded = _SYNTHETIC_BASE + 204, _SYNTHETIC_BASE + 205
        relation = _SYNTHETIC_BASE + 406
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={
                    supported: ({"waterway": "river"}, (n1, n2)),
                    excluded: ({"waterway": "drain"}, (n2, n3)),
                },
                relations={
                    relation: (
                        {"type": "waterway", "name": "SyntheticMixedBranches"},
                        (("w", supported, "main"), ("w", excluded, "drain")),
                    )
                },
                roots=(("r", relation),),
            )
            feature = _features(result.database_path, binding)[0]
            entry = _entry(result.database_path, 2, relation)
        self.assertFalse(feature.complete_named_relation)
        self.assertEqual(
            "line_candidates_with_noncandidate_members", entry["status"]
        )

    def test_multiple_supported_types_make_every_relation_subset_incomplete(self) -> None:
        river, stream = _SYNTHETIC_BASE + 206, _SYNTHETIC_BASE + 207
        relation = _SYNTHETIC_BASE + 407
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={
                    river: ({"waterway": "river"}, (n1, n2)),
                    stream: ({"waterway": "stream"}, (n2, n3)),
                },
                relations={
                    relation: (
                        {"type": "waterway", "name": "SyntheticMultipleTypes"},
                        (("w", river, "main"), ("w", stream, "tributary")),
                    )
                },
                roots=(("r", relation),),
            )
            features = _features(result.database_path, binding)
        self.assertEqual({"river", "stream"}, {feature.waterway_type for feature in features})
        self.assertTrue(all(not feature.complete_named_relation for feature in features))

    def test_supported_ancestor_precedence_overrides_exact_way_value(self) -> None:
        way = _SYNTHETIC_BASE + 208
        relation = _SYNTHETIC_BASE + 408
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={way: ({"waterway": "drain"}, (n1, n2))},
                relations={
                    relation: (
                        {
                            "type": "waterway",
                            "name": "SyntheticAncestorPrecedence",
                            "waterway": "river",
                        },
                        (("w", way, "main"),),
                    )
                },
                roots=(("r", relation),),
            )
            feature = _features(result.database_path, binding)[0]
            entry = _entry(result.database_path, 2, relation)
        self.assertEqual("river", feature.waterway_type)
        way_evidence = [
            item for item in entry["waterwayEvidence"] if item["objectKind"] == "way"
        ][0]
        self.assertEqual("drain", way_evidence["declaredWaterway"])
        self.assertEqual("river", way_evidence["effectiveWaterway"])

    def test_nested_supported_override_and_untagged_inheritance(self) -> None:
        first, second = _SYNTHETIC_BASE + 209, _SYNTHETIC_BASE + 210
        nested_override, nested_inherit, root_relation = (
            _SYNTHETIC_BASE + 409,
            _SYNTHETIC_BASE + 410,
            _SYNTHETIC_BASE + 411,
        )
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={
                    first: ({}, (n1, n2)),
                    second: ({}, (n2, n3)),
                },
                relations={
                    nested_override: (
                        {"waterway": "stream"},
                        (("w", first, "main"),),
                    ),
                    nested_inherit: ({}, (("w", second, "main"),)),
                    root_relation: (
                        {
                            "type": "waterway",
                            "name": "SyntheticNestedTypes",
                            "waterway": "river",
                        },
                        (
                            ("r", nested_override, "tributary"),
                            ("r", nested_inherit, "main"),
                        ),
                    ),
                },
                roots=(("r", root_relation),),
            )
            features = _features(result.database_path, binding)
        self.assertEqual({"river", "stream"}, {feature.waterway_type for feature in features})

    def test_unsupported_nested_relation_is_a_join_barrier_and_structure_checks_below_it(self) -> None:
        before, hidden, after = (
            _SYNTHETIC_BASE + 211,
            _SYNTHETIC_BASE + 212,
            _SYNTHETIC_BASE + 213,
        )
        nested, relation = _SYNTHETIC_BASE + 412, _SYNTHETIC_BASE + 413
        n1, n2, n3, n4 = (_SYNTHETIC_BASE + index for index in (1, 2, 3, 4))
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={
                    before: ({}, (n1, n2)),
                    hidden: ({}, (n2, n3)),
                    after: ({}, (n2, n4)),
                },
                relations={
                    nested: ({"waterway": "drain"}, (("w", hidden, "hidden"),)),
                    relation: (
                        {
                            "type": "waterway",
                            "name": "SyntheticBarrier",
                            "waterway": "river",
                        },
                        (
                            ("w", before, "main"),
                            ("r", nested, "barrier"),
                            ("w", after, "main"),
                        ),
                    ),
                },
                roots=(("r", relation),),
            )
            feature = _features(result.database_path, binding)[0]
            entry = _entry(result.database_path, 2, relation)
        self.assertEqual(2, len(feature.parts), "unsupported nested branch must break joining")
        self.assertEqual(2, entry["geometryUsage"]["rawSupportedWayParts"])
        self.assertEqual("unsupported_relation_waterway", entry["reasonOccurrences"][0]["reasonCode"])
        self.assertTrue(entry["structural"]["hasReachableWay"])

    def test_node_evidence_does_not_break_join_or_complete_relation(self) -> None:
        first, second = _SYNTHETIC_BASE + 214, _SYNTHETIC_BASE + 215
        relation = _SYNTHETIC_BASE + 414
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={first: ({}, (n1, n2)), second: ({}, (n2, n3))},
                relations={
                    relation: (
                        {
                            "type": "waterway",
                            "name": "SyntheticNodeJoin",
                            "waterway": "river",
                        },
                        (("w", first, "main"), ("n", n2, "spring"), ("w", second, "main")),
                    )
                },
                roots=(("r", relation),),
            )
            feature = _features(result.database_path, binding)[0]
            entry = _entry(result.database_path, 2, relation)
        self.assertTrue(feature.complete_named_relation)
        self.assertEqual(1, len(feature.parts))
        self.assertEqual(frozenset({n2}), feature.required_node_ids)
        self.assertEqual(1, entry["nodeEvidenceCount"])

    def test_selected_member_way_remains_an_independent_direct_root_candidate(self) -> None:
        way = _SYNTHETIC_BASE + 216
        relation = _SYNTHETIC_BASE + 415
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={
                    way: (
                        {"name": "SyntheticSelectedWay", "waterway": "river"},
                        (n1, n2),
                    )
                },
                relations={
                    relation: (
                        {"type": "waterway", "name": "SyntheticSelectedRelation"},
                        (("w", way, "main"),),
                    )
                },
                roots=(("w", way), ("r", relation)),
            )
            features = _features(result.database_path, binding)
        self.assertEqual(
            {("way", way), ("relation", relation)},
            {(feature.source_kind, feature.source_id) for feature in features},
        )

    def test_direct_way_admission_uses_its_streaming_point_path(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        way = _SYNTHETIC_BASE + 217
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            store,
            "_point_rows",
            side_effect=AssertionError("direct admission used relation point materialization"),
        ):
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={
                    way: (
                        {"name": "SyntheticStreamingWay", "waterway": "river"},
                        (n1, n2),
                    )
                },
                relations={},
                roots=(("w", way),),
            )
        self.assertEqual(1, result.receipt["admission"]["candidateRootCount"])


class AdmissionDurabilityAndFatalTests(unittest.TestCase):
    def test_next_root_uses_one_ordered_seek_for_lexicographic_successors(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        class RecordingConnection(sqlite3.Connection):
            successor_queries: list[tuple[str, tuple[object, ...]]]

            def execute(self, sql: str, parameters=(), /):
                cursor = super().execute(sql, parameters)
                if sql.startswith("SELECT kind,id FROM roots "):
                    self.successor_queries.append((sql, tuple(parameters)))
                return cursor

        connection = sqlite3.connect(":memory:", factory=RecordingConnection)
        try:
            connection.successor_queries = []
            connection.execute(
                "CREATE TABLE roots(kind INTEGER NOT NULL,id INTEGER NOT NULL,"
                "PRIMARY KEY(kind,id)) WITHOUT ROWID"
            )
            expected = tuple(
                (kind, object_id)
                for kind in (1, 2)
                for object_id in range(1, 1_025, 2)
            )
            connection.executemany(
                "INSERT INTO roots(kind,id) VALUES(?,?)",
                expected,
            )
            actual: list[tuple[int, int] | None] = []
            after = (0, 0)
            while True:
                successor = store._next_root(connection, *after)
                actual.append(successor)
                if successor is None:
                    break
                after = successor

            self.assertEqual((*expected, None), tuple(actual))
            self.assertEqual(len(actual), len(connection.successor_queries))
            query, parameters = connection.successor_queries[
                len(connection.successor_queries) // 2
            ]
            plan = tuple(
                str(row[3])
                for row in connection.execute(
                    "EXPLAIN QUERY PLAN " + query,
                    parameters,
                )
            )
        finally:
            connection.close()

        self.assertNotIn("MULTI-INDEX OR", plan)
        self.assertNotIn("USE TEMP B-TREE FOR ORDER BY", plan)
        self.assertNotIn(" OR ", query)
        self.assertEqual(1, len(plan))
        self.assertRegex(
            plan[0],
            r"^SEARCH roots USING PRIMARY KEY \(\(kind,id\)>",
        )

    def _two_root_fixture(
        self,
        directory: Path,
        *,
        pause: int | None,
        work: str,
        checkpoint_admission_roots: int = 1,
    ):
        way1, way2 = _SYNTHETIC_BASE + 301, _SYNTHETIC_BASE + 302
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        return _ingest(
            directory,
            ways={
                way1: ({"name": "SyntheticDirectOne", "waterway": "river"}, (n1, n2)),
                way2: ({"name": "SyntheticDirectTwo", "waterway": "stream"}, (n2, n3)),
            },
            relations={},
            roots=(("w", way1), ("w", way2)),
            work_name=work,
            pause_after_admission_roots=pause,
            checkpoint_admission_roots=checkpoint_admission_roots,
        )

    def test_admission_checkpoint_resume_matches_clean_and_precedes_renderer_rows(self) -> None:
        from tools.experiment8.osm_global_waterway_store import ingest_global_waterway_closure

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused, binding, opl, roots = self._two_root_fixture(root, pause=1, work="resumed")
            self.assertEqual("paused", paused.state)
            connection = sqlite3.connect(paused.database_path)
            try:
                self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM records").fetchone()[0])
                self.assertEqual(
                    0,
                    connection.execute("SELECT COUNT(*) FROM rendered_features").fetchone()[0],
                )
            finally:
                connection.close()
            resumed = ingest_global_waterway_closure(
                opl_path=opl,
                root_ids_path=roots,
                work_directory=root / "resumed",
                source_binding=binding,
                checkpoint_objects=3,
                checkpoint_admission_roots=1,
            )
            clean, _, _, _ = self._two_root_fixture(root, pause=None, work="clean")
        self.assertEqual("complete", resumed.state)
        self.assertEqual(
            resumed.receipt["admission"], clean.receipt["admission"]
        )

    def test_resume_rejects_entry_candidate_or_order_tamper(self) -> None:
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
        from tools.experiment8.osm_global_waterway_store import ingest_global_waterway_closure

        mutations = (
            "UPDATE admission_roots SET entry_json=entry_json||x'20' WHERE root_id=(SELECT MIN(root_id) FROM admission_roots)",
            "UPDATE admission_candidates SET candidate_feature_sha=x'" + "00" * 32 + "'",
            "UPDATE admission_roots SET root_id=root_id+99",
        )
        for index, mutation in enumerate(mutations):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                paused, binding, opl, roots = self._two_root_fixture(root, pause=1, work="resume")
                connection = sqlite3.connect(paused.database_path)
                try:
                    connection.execute(mutation)
                    connection.commit()
                finally:
                    connection.close()
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "admission.*(prefix|candidate|order|evidence|canonical)",
                ):
                    ingest_global_waterway_closure(
                        opl_path=opl,
                        root_ids_path=roots,
                        work_directory=root / "resume",
                        source_binding=binding,
                        checkpoint_objects=3,
                        checkpoint_admission_roots=1,
                    )

    def test_completed_receipt_is_reconstructed_before_resume_or_publication(self) -> None:
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
        from tools.experiment8.osm_global_waterway_store import (
            ingest_global_waterway_closure,
            render_fixture_global_waterway_package,
        )

        mutations = (
            (("aggregateSha256",), lambda _value: "0" * 64),
            (("candidateRootCount",), lambda value: value + 1),
            (
                ("rootStatusHistogram", "values", 2, "roots"),
                lambda value: value + 1,
            ),
            (
                ("reasonOccurrenceHistogram", "values", 0, "occurrences"),
                lambda value: value + 1,
            ),
            (
                ("legacyFirstTerminal", "ledger", "sha256"),
                lambda _value: "0" * 64,
            ),
            (
                ("structuralNoReachableWay", "ledger", "count"),
                lambda value: value + 1,
            ),
            (("policy", "sha256"), lambda _value: "0" * 64),
            (
                ("source", "selectionPolicySha256"),
                lambda _value: "0" * 64,
            ),
            (("ingestSemanticSha256",), lambda _value: "0" * 64),
        )
        for path, mutate in mutations:
            with self.subTest(path=path), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                completed, binding, opl, roots = self._two_root_fixture(
                    root, pause=None, work="complete"
                )
                connection = sqlite3.connect(completed.database_path)
                try:
                    row = connection.execute(
                        "SELECT value FROM meta WHERE key='admissionReceipt'"
                    ).fetchone()
                    receipt = json.loads(bytes(row[0]).decode("utf-8"))
                    target = receipt
                    for component in path[:-1]:
                        target = target[component]
                    final = path[-1]
                    target[final] = mutate(target[final])
                    connection.execute(
                        "UPDATE meta SET value=? WHERE key='admissionReceipt'",
                        (_canonical_bytes(receipt),),
                    )
                    connection.commit()
                finally:
                    connection.close()
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "admission receipt.*authenticated",
                ):
                    ingest_global_waterway_closure(
                        opl_path=opl,
                        root_ids_path=roots,
                        work_directory=root / "complete",
                        source_binding=binding,
                        checkpoint_objects=3,
                        checkpoint_admission_roots=1,
                    )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            completed, binding, opl, roots = self._two_root_fixture(
                root,
                pause=None,
                work="publication",
                checkpoint_admission_roots=100,
            )
            connection = sqlite3.connect(completed.database_path)
            try:
                row = connection.execute(
                    "SELECT value FROM meta WHERE key='admissionReceipt'"
                ).fetchone()
                receipt = json.loads(bytes(row[0]).decode("utf-8"))
                receipt["aggregateSha256"] = "0" * 64
                connection.execute(
                    "UPDATE meta SET value=? WHERE key='admissionReceipt'",
                    (_canonical_bytes(receipt),),
                )
                connection.commit()
            finally:
                connection.close()
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "admission receipt.*authenticated",
            ):
                render_fixture_global_waterway_package(
                    opl_path=opl,
                    root_ids_path=roots,
                    output_directory=root / "output",
                    work_directory=root / "publication",
                    package_id="synthetic-tampered-admission-receipt",
                    source_binding=binding,
                    checkpoint_objects=3,
                    checkpoint_features=1,
                )

    def test_renderer_recomputes_candidate_feature_and_root_stream_digests(self) -> None:
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result, binding, _, _ = self._two_root_fixture(root, pause=None, work="work")
            connection = sqlite3.connect(result.database_path)
            try:
                connection.execute(
                    "UPDATE admission_candidates SET candidate_feature_sha=? "
                    "WHERE root_id=(SELECT MIN(root_id) FROM admission_candidates)",
                    (b"\0" * 32,),
                )
                connection.commit()
            finally:
                connection.close()
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "candidate.*differs"):
                _features(result.database_path, binding)

    def test_structural_fatals_abort_globally_before_renderer_rows(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        relation = _SYNTHETIC_BASE + 501
        way = _SYNTHETIC_BASE + 351
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        cases = {
            "missing object": (
                {},
                {relation: ({"type": "waterway", "name": "SyntheticMissing"}, (("w", way, "main"),))},
            ),
            "root predicate": (
                {way: ({"waterway": "river"}, (n1, n2))},
                {relation: ({"type": "route", "name": "SyntheticWrongPredicate"}, (("w", way, "main"),))},
            ),
            "root name": (
                {way: ({"waterway": "river"}, (n1, n2))},
                {relation: ({"type": "waterway"}, (("w", way, "main"),))},
            ),
            "short way": (
                {way: ({"waterway": "river"}, (n1,))},
                {relation: ({"type": "waterway", "name": "SyntheticShort"}, (("w", way, "main"),))},
            ),
        }
        for label, (ways, relations) in cases.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                with self.assertRaises(GlobalWaterwayPackageError):
                    _ingest(root, ways=ways, relations=relations, roots=(("r", relation),))
                database = root / "work" / "waterway-state.sqlite"
                if database.exists():
                    connection = sqlite3.connect(database)
                    try:
                        self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM records").fetchone()[0])
                    finally:
                        connection.close()

        with tempfile.TemporaryDirectory() as temporary:
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={way: ({"waterway": "river"}, (n1, n2))},
                relations={relation: ({"type": "waterway", "name": "SyntheticTamper"}, (("w", way, "main"),))},
                roots=(("r", relation),),
            )
            connection = sqlite3.connect(result.database_path)
            try:
                for sql, restore in (
                    (
                        "UPDATE relation_members SET ordinal=2",
                        "UPDATE relation_members SET ordinal=0",
                    ),
                    (
                        "UPDATE relation_members SET kind=9",
                        "UPDATE relation_members SET kind=1",
                    ),
                ):
                    connection.execute(sql)
                    connection.commit()
                    with self.assertRaises(GlobalWaterwayPackageError):
                        store._analyze_admission_root(connection, 2, relation)
                    connection.execute(restore)
                    connection.commit()
            finally:
                connection.close()

    def test_fatal_below_unsupported_barrier_is_not_hidden_by_candidate_pruning(self) -> None:
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        hidden_way = _SYNTHETIC_BASE + 353
        nested, relation = _SYNTHETIC_BASE + 504, _SYNTHETIC_BASE + 505
        n1, missing = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 99
        with tempfile.TemporaryDirectory() as temporary, self.assertRaisesRegex(
            GlobalWaterwayPackageError, "missing node"
        ):
            _ingest(
                Path(temporary),
                nodes={n1: ("-70.0000000", "40.0000000")},
                ways={hidden_way: ({}, (n1, missing))},
                relations={
                    nested: (
                        {"waterway": "drain"},
                        (("w", hidden_way, "hidden"),),
                    ),
                    relation: (
                        {
                            "type": "waterway",
                            "name": "SyntheticFatalBelowBarrier",
                            "waterway": "river",
                        },
                        (("r", nested, "barrier"),),
                    ),
                },
                roots=(("r", relation),),
            )

    def test_all_structural_and_candidate_resource_limits_are_fatal_with_small_injection(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        way = _SYNTHETIC_BASE + 352
        child, relation = _SYNTHETIC_BASE + 502, _SYNTHETIC_BASE + 503
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        with tempfile.TemporaryDirectory() as temporary:
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={way: ({}, (n1, n2, n3))},
                relations={
                    child: ({}, (("w", way, "main"),)),
                    relation: ({"type": "waterway", "name": "SyntheticLimits", "waterway": "river"}, (("r", child, "main"),)),
                },
                roots=(("r", relation),),
            )
            production = store._AdmissionLimits.production()
            cases = {
                "structural depth": replace(production, max_structural_depth=1),
                "structural visits": replace(production, max_structural_relation_visits=1),
                "structural members": replace(production, max_structural_member_occurrences=1),
                "structural way nodes": replace(production, max_structural_way_node_occurrences=2),
                "candidate depth": replace(production, max_candidate_depth=1),
                "candidate visits": replace(production, max_candidate_relation_visits=1),
                "candidate parts": replace(production, max_candidate_raw_parts=0),
                "candidate points": replace(production, max_candidate_points=2),
                "dependency records": replace(production, max_dependency_records=0),
                "dependency record bytes": replace(
                    production, max_dependency_record_bytes=1
                ),
                "entry bytes": replace(production, max_admission_entry_bytes=1),
            }
            connection = sqlite3.connect(result.database_path)
            try:
                for label, limits in cases.items():
                    with self.subTest(label=label), self.assertRaisesRegex(
                        GlobalWaterwayPackageError, "ceiling"
                    ):
                        store._analyze_admission_root(
                            connection, 2, relation, limits=limits
                        )
            finally:
                connection.close()

        record = ["synthetic-dependency", _SYNTHETIC_BASE + 999]
        exact_record_bytes = len(_canonical_bytes(record))
        exact_limits = replace(
            store._AdmissionLimits.production(),
            max_dependency_records=1,
            max_dependency_record_bytes=exact_record_bytes,
        )
        evidence = store._DependencyEvidence(exact_limits)
        evidence.add(record)
        self.assertEqual(1, evidence.document()["records"])
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "record count.*ceiling"):
            evidence.add(record)
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "record.*byte ceiling"):
            store._DependencyEvidence(
                replace(exact_limits, max_dependency_record_bytes=exact_record_bytes - 1)
            ).add(record)


class CompletenessAndReceiptTests(unittest.TestCase):
    def test_incomplete_relation_uses_direct_path_without_complete_prominence(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )
        from tools.experiment8.reference_presentation_policy import (
            ProminenceEvidenceKind,
        )
        from tools.experiment8.semantic_model import PlacementSourceKind

        supported, excluded = _SYNTHETIC_BASE + 361, _SYNTHETIC_BASE + 362
        relation = _SYNTHETIC_BASE + 511
        n1, n2, n3 = (_SYNTHETIC_BASE + index for index in (1, 2, 3))
        with tempfile.TemporaryDirectory() as temporary:
            result, binding, _, _ = _ingest(
                Path(temporary),
                ways={
                    supported: ({"waterway": "river"}, (n1, n2)),
                    excluded: ({"waterway": "drain"}, (n2, n3)),
                },
                relations={relation: ({"type": "waterway", "name": "SyntheticIncomplete"}, (("w", supported, "main"), ("w", excluded, "excluded")))},
                roots=(("r", relation),),
            )
            feature = _features(result.database_path, binding)[0]
            rendered = build_adaptive_waterway_feature(
                feature=feature,
                source_generation_sha256=binding.planet_sha256,
                classifier_sha256=classifier_identity_sha256(),
                zooms=tuple(range(4, 12)),
            )
        placements = [
            record.renderer_record.variant.placement
            for records in rendered.tiles.values()
            for record in records
        ]
        self.assertTrue(placements)
        self.assertTrue(
            all(item.placement_source_kind is PlacementSourceKind.DIRECT_SOURCE_PATH for item in placements)
        )
        self.assertEqual(0, rendered.prominence_decision.complete_geometry_measure_bucket)
        self.assertIsNot(
            ProminenceEvidenceKind.COMPLETE_RELATION_LENGTH_M,
            rendered.prominence_decision.evidence_kind,
        )

    def test_receipt_binds_admission_aggregate_histogram_units_and_legacy_ledgers(self) -> None:
        way = _SYNTHETIC_BASE + 363
        relation = _SYNTHETIC_BASE + 512
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary:
            result, _, _, _ = _ingest(
                Path(temporary),
                ways={way: ({"waterway": "drain"}, (n1, n2))},
                relations={relation: ({"type": "waterway", "name": "SyntheticReceipt"}, (("w", way, "main"),))},
                roots=(("r", relation),),
            )
        receipt = result.receipt["admission"]
        self.assertEqual(_POLICY_SHA256, receipt["policy"]["sha256"])
        self.assertEqual("roots", receipt["rootStatusHistogram"]["unit"])
        self.assertEqual("occurrences", receipt["reasonOccurrenceHistogram"]["unit"])
        self.assertEqual(0, receipt["fatalCount"])
        self.assertEqual(1, receipt["zeroCandidateRootCount"])
        self.assertEqual(1, receipt["legacyFirstTerminal"]["ledger"]["count"])
        self.assertEqual(0, receipt["structuralNoReachableWay"]["ledger"]["count"])
        self.assertRegex(receipt["aggregateSha256"], r"\A[0-9a-f]{64}\Z")

    def test_fixture_publication_binds_admission_in_render_manifest_receipt_and_final_identity(self) -> None:
        from tools.experiment8.osm_global_waterway_store import render_fixture_global_waterway_package

        way = _SYNTHETIC_BASE + 364
        n1, n2 = _SYNTHETIC_BASE + 1, _SYNTHETIC_BASE + 2
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, binding, opl, roots = _ingest(
                root,
                ways={way: ({"name": "SyntheticPublished", "waterway": "river"}, (n1, n2))},
                relations={},
                roots=(("w", way),),
                work_name="preflight",
            )
            built = render_fixture_global_waterway_package(
                opl_path=opl,
                root_ids_path=roots,
                output_directory=root / "package",
                work_directory=root / "package-work",
                package_id="synthetic-waterway-v4",
                source_binding=binding,
                checkpoint_objects=3,
                checkpoint_features=1,
            )
            manifest = json.loads((root / "package" / "manifest.json").read_text("utf-8"))
        admission = built.receipt["admission"]
        supplement = manifest["globalWaterwaySupplement"]
        self.assertEqual(admission["aggregateSha256"], supplement["admissionAggregateSha256"])
        self.assertEqual(_POLICY_SHA256, supplement["admissionPolicySha256"])
        self.assertEqual(
            "admitted-exact-path-or-incomplete-relation-subset",
            supplement["adaptiveGeometry"]["pathScope"],
        )
        self.assertEqual(
            admission["aggregateSha256"],
            built.receipt["build"]["runIdentity"]["admissionAggregateSha256"],
        )
        self.assertRegex(built.receipt["finalSemanticIdentitySha256"], r"\A[0-9a-f]{64}\Z")


if __name__ == "__main__":
    unittest.main()
