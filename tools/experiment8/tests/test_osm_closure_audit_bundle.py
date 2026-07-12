from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from contextlib import ExitStack
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

import tools.experiment8.osm_closure_audit_bundle as bundle_module
from tools.experiment8.osm_closure_audit_bundle import (
    SYNTHETIC_RELATION_CLOSURE_PROFILE,
    BundleContractError,
    BundleFile,
    RelationClosureAuditBundle,
    audit_predicted_with_cached_global,
    build_maryland_relation_closure_audit_bundle,
    build_relation_closure_audit_bundle,
    write_relation_closure_audit_bundle,
)
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
    ClosureProbeEvidence,
    ProcessEvidence,
    RuntimeAttestation,
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
)


SOURCE_WSL_PATH = (
    "/mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/source/"
    "maryland-260710.osm.pbf"
)
SOURCE_IDENTITY_SHA256 = "1" * 64
SELECTION_MANIFEST_SHA256 = "2" * 64
CANDIDATE_XML_SHA256 = "3" * 64


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")


def _argv_bytes(argv: tuple[str, ...]) -> bytes:
    return (
        json.dumps(list(argv), ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode("utf-8")


def _timed(line: str, elapsed: str = " 0:00") -> bytes:
    return f"[{elapsed}] {line}\n".encode("utf-8")


def _getid_stderr(
    relation_ids: tuple[int, ...], missing: MissingReferences
) -> bytes:
    lines = (
        "Started osmium getid",
        f"  osmium version {PINNED_OSMIUM_VERSION}",
        f"  libosmium version {PINNED_LIBOSMIUM_VERSION}",
        "Command line options and default settings:",
        "  input options:",
        f"    file name: {SOURCE_WSL_PATH}",
        "    file format: ",
        "  output options:",
        "    file name: /dev/null",
        "    file format: pbf",
        f"    generator: osmium/{PINNED_OSMIUM_VERSION}",
        "    overwrite: yes",
        "    fsync: no",
        "  other options:",
        "    add referenced objects: yes",
        "    remove tags on non-matching objects: no",
        "    work with history files: no",
        "    default object type: node",
        "    looking for these ids:",
        "      nodes:",
        "      ways:",
        "      relations: " + " ".join(str(value) for value in relation_ids),
        "Following references...",
        "  Reading input file to find relations in relations...",
        "  Reading input file to find nodes/ways in relations...",
        "  Reading input file to find nodes in ways...",
        "Done following references.",
        "Opening input file...",
        "Opening output file...",
        "Copying matching objects to output file...",
        "Closing output file...",
        "Closing input file...",
    )
    output = b"".join(_timed(line) for line in lines)
    if missing.count:
        output += _timed(f"Did not find {missing.count} object(s).", " 0:04")
        for label, values in (
            ("node", missing.node_ids),
            ("way", missing.way_ids),
            ("relation", missing.relation_ids),
        ):
            if values:
                output += (
                    f"Missing {label} IDs: "
                    + " ".join(str(value) for value in values)
                    + "\n"
                ).encode("ascii")
    else:
        output += _timed("Found all objects.", " 0:04")
    output += _timed("Peak memory used: 0 MBytes", " 0:04")
    output += _timed("Done.", " 0:04")
    return output


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


def _process(
    argv: tuple[str, ...], returncode: int, stdout: bytes, stderr: bytes
) -> ProcessEvidence:
    return ProcessEvidence(argv=argv, returncode=returncode, stdout=stdout, stderr=stderr)


def _probe_record(
    relation_ids: tuple[int, ...], missing: MissingReferences
) -> ClosureProbeEvidence:
    release = _process(
        _base_wsl_argv() + ("/usr/bin/lsb_release", "-ds"),
        0,
        f"{PINNED_UBUNTU_RELEASE}\n".encode("ascii"),
        b"",
    )
    hash_argv = _base_wsl_argv() + (
        "/usr/bin/sha256sum",
        "--binary",
        OSMIUM_BINARY_PATH,
        BOOST_LIBRARY_PATH,
        SOURCE_WSL_PATH,
    )
    hash_stdout = (
        f"{OSMIUM_BINARY_SHA256} *{OSMIUM_BINARY_PATH}\n"
        f"{BOOST_LIBRARY_SHA256} *{BOOST_LIBRARY_PATH}\n"
        f"{MARYLAND_SOURCE_SHA256} *{SOURCE_WSL_PATH}\n"
    ).encode("utf-8")
    pre_hash = _process(hash_argv, 0, hash_stdout, b"")
    getid_argv = _base_wsl_argv() + (
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
        SOURCE_WSL_PATH,
        *(f"r{relation_id}" for relation_id in relation_ids),
    )
    getid = _process(
        getid_argv,
        1 if missing.count else 0,
        b"",
        _getid_stderr(relation_ids, missing),
    )
    runtime = RuntimeAttestation(
        ubuntu_distribution=PINNED_UBUNTU_DISTRIBUTION,
        ubuntu_release=PINNED_UBUNTU_RELEASE,
        locale=PINNED_LOCALE,
        runtime_root=RUNTIME_ROOT,
        osmium_binary_path=OSMIUM_BINARY_PATH,
        osmium_binary_sha256=OSMIUM_BINARY_SHA256,
        boost_library_path=BOOST_LIBRARY_PATH,
        boost_library_sha256=BOOST_LIBRARY_SHA256,
        release_process=release,
        hash_process=pre_hash,
    )
    return ClosureProbeEvidence(
        relation_ids=relation_ids,
        source_wsl_path=SOURCE_WSL_PATH,
        source_sha256=MARYLAND_SOURCE_SHA256,
        missing_references=missing,
        runtime=runtime,
        process=getid,
        post_hash_process=_process(hash_argv, 0, hash_stdout, b""),
    )


def _fixture() -> tuple[
    RootSelection,
    RegionalClosureClassification,
    RelationClosureAudit,
    tuple[ClosureProbeEvidence, ...],
]:
    empty = MissingReferences((), (), ())
    missing_99 = MissingReferences((), (99,), ())
    roots = RootSelection(way_ids=(10, 11), relation_ids=(20, 21, 22, 23))
    classification = RegionalClosureClassification(
        complete_way_ids=(10, 11),
        complete_relation_ids=(20, 23),
        incomplete_relations=(
            IncompleteRelationRoot(
                relation_id=21,
                direct_missing_members=(
                    OsmRelationMember("way", 99, "main_stream", ordinal=7),
                ),
                dependency_relation_ids=(),
            ),
            IncompleteRelationRoot(
                relation_id=22,
                direct_missing_members=(),
                dependency_relation_ids=(21,),
            ),
        ),
        missing_references=missing_99,
    )
    audit = RelationClosureAudit(
        complete_relation_ids=(20, 23),
        incomplete_relations=(
            IncompleteRelationClosure(21, missing_99),
            IncompleteRelationClosure(22, missing_99),
        ),
        global_missing_references=missing_99,
        probed_batches=((20, 21, 22, 23), (21,), (22,), (20, 23)),
    )
    records = (
        _probe_record((20, 21, 22, 23), missing_99),
        _probe_record((21,), missing_99),
        _probe_record((22,), missing_99),
        _probe_record((20, 23), empty),
    )
    return roots, classification, audit, records


def _bundle():
    roots, classification, audit, records = _fixture()
    return build_relation_closure_audit_bundle(
        profile=SYNTHETIC_RELATION_CLOSURE_PROFILE,
        source_identity_sha256=SOURCE_IDENTITY_SHA256,
        selection_manifest_sha256=SELECTION_MANIFEST_SHA256,
        candidate_xml_sha256=CANDIDATE_XML_SHA256,
        roots=roots,
        classification=classification,
        audit=audit,
        probe_records=records,
    )


class CanonicalBundleTests(unittest.TestCase):
    def test_builds_separate_deterministic_classification_execution_and_raw_bytes(self) -> None:
        roots, classification, audit, records = _fixture()

        first = build_relation_closure_audit_bundle(
            profile=SYNTHETIC_RELATION_CLOSURE_PROFILE,
            source_identity_sha256=SOURCE_IDENTITY_SHA256,
            selection_manifest_sha256=SELECTION_MANIFEST_SHA256,
            candidate_xml_sha256=CANDIDATE_XML_SHA256,
            roots=roots,
            classification=classification,
            audit=audit,
            probe_records=records,
        )
        second = build_relation_closure_audit_bundle(
            profile=SYNTHETIC_RELATION_CLOSURE_PROFILE,
            source_identity_sha256=SOURCE_IDENTITY_SHA256,
            selection_manifest_sha256=SELECTION_MANIFEST_SHA256,
            candidate_xml_sha256=CANDIDATE_XML_SHA256,
            roots=roots,
            classification=classification,
            audit=audit,
            probe_records=records,
        )

        self.assertEqual(first, second)
        classification_doc = json.loads(first.classification_bytes)
        self.assertEqual(
            classification_doc["schema"],
            "flight-alert-exp8-osm-relation-closure-classification-v2",
        )
        self.assertEqual(
            classification_doc["profile"], SYNTHETIC_RELATION_CLOSURE_PROFILE
        )
        self.assertFalse(classification_doc["wholeWorldEligible"])
        self.assertNotIn(
            "memberOrdinalUnavailableInCandidateAuditV1", classification_doc
        )
        self.assertEqual(
            classification_doc["counts"],
            {
                "completeRelations": 2,
                "completeWays": 2,
                "selectedRelations": 4,
                "selectedWays": 2,
                "sourceIncompleteRelations": 2,
            },
        )
        relations = {item["id"]: item for item in classification_doc["relations"]}
        self.assertEqual(relations[20]["status"], "complete")
        self.assertEqual(relations[21]["status"], "source_incomplete")
        self.assertEqual(relations[21]["missingReferences"]["wayIds"], [99])
        self.assertEqual(
            relations[21]["directMissingMembers"],
            [
                {
                    "memberOrdinal": 7,
                    "missingMemberOrder": 0,
                    "objectType": "way",
                    "ref": 99,
                    "role": "main_stream",
                }
            ],
        )
        self.assertEqual(relations[22]["dependencyRelationIds"], [21])
        proof = classification_doc["closureProof"]
        self.assertTrue(proof["globalEqualsSingletonUnion"])
        self.assertTrue(proof["retainedUnionIsComplete"])
        self.assertEqual(proof["globalMissingReferences"], proof["singletonUnion"])
        self.assertEqual(
            proof["probePlan"],
            {
                "globalBatches": 1,
                "retainedUnionBatches": 1,
                "singletonBatches": 2,
                "totalBatches": 4,
            },
        )
        self.assertEqual(
            first.classification_bytes,
            _canonical_json_bytes(classification_doc),
        )
        forbidden = (b"/mnt/", b"relativeFile", b"argv", b"stdout", b"stderr", b"elapsed")
        for value in forbidden:
            self.assertNotIn(value, first.classification_bytes)

        execution_doc = json.loads(first.execution_manifest_bytes)
        self.assertEqual(
            execution_doc["classificationSha256"],
            hashlib.sha256(first.classification_bytes).hexdigest(),
        )
        self.assertEqual(execution_doc["sourceIdentitySha256"], SOURCE_IDENTITY_SHA256)
        self.assertEqual(execution_doc["probeCount"], 4)
        self.assertEqual(execution_doc["probes"][0]["batchRelationIds"], [20, 21, 22, 23])
        self.assertEqual(execution_doc["probes"][0]["processes"]["getid"]["returnCode"], 1)
        self.assertNotIn(SOURCE_WSL_PATH, first.execution_manifest_bytes.decode("utf-8"))

        raw_files = {item.relative_path: item.content for item in first.raw_files}
        self.assertEqual(len(raw_files), 4 * 4 * 3)
        getid_process = execution_doc["probes"][0]["processes"]["getid"]
        self.assertEqual(
            raw_files[getid_process["argv"]["relativeFile"]],
            _argv_bytes(records[0].process.argv),
        )
        self.assertEqual(
            raw_files[getid_process["stdout"]["relativeFile"]],
            records[0].process.stdout,
        )
        self.assertEqual(
            raw_files[getid_process["stderr"]["relativeFile"]],
            records[0].process.stderr,
        )
        self.assertEqual(
            getid_process["stderr"]["sha256"],
            hashlib.sha256(records[0].process.stderr).hexdigest(),
        )


class CachedGlobalOrchestratorTests(unittest.TestCase):
    def test_cached_global_is_one_of_four_actual_records_but_is_not_rescanned(self) -> None:
        empty = MissingReferences((), (), ())
        missing_99 = MissingReferences((), (99,), ())

        class SyntheticRecordingProbe:
            def __init__(self) -> None:
                self._records = [_probe_record((20, 21, 22, 23), missing_99)]
                self.actual_calls: list[tuple[int, ...]] = []

            @property
            def records(self) -> tuple[ClosureProbeEvidence, ...]:
                return tuple(self._records)

            def __call__(self, batch: tuple[int, ...]) -> MissingReferences:
                self.actual_calls.append(batch)
                missing = {
                    (21,): missing_99,
                    (22,): missing_99,
                    (20, 23): empty,
                }[batch]
                self._records.append(_probe_record(batch, missing))
                return missing

        probe = SyntheticRecordingProbe()

        audit = audit_predicted_with_cached_global(
            relation_ids=(20, 21, 22, 23),
            predicted_incomplete_ids=(21, 22),
            probe=probe,
        )

        self.assertEqual(
            audit.probed_batches,
            ((20, 21, 22, 23), (21,), (22,), (20, 23)),
        )
        self.assertEqual(probe.actual_calls, [(21,), (22,), (20, 23)])
        self.assertEqual(len(probe.records), 4)
        self.assertEqual(
            tuple(record.relation_ids for record in probe.records),
            audit.probed_batches,
        )

    def test_cached_global_must_be_the_only_existing_exact_batch_record(self) -> None:
        missing = MissingReferences((), (99,), ())

        class BadProbe:
            def __init__(self, records: tuple[ClosureProbeEvidence, ...]) -> None:
                self.records = records

            def __call__(self, batch: tuple[int, ...]) -> MissingReferences:
                raise AssertionError("invalid cached state must fail before probing")

        cases = (
            BadProbe(()),
            BadProbe((_probe_record((20,), missing),)),
            BadProbe(
                (
                    _probe_record((20, 21), missing),
                    _probe_record((20, 21), missing),
                )
            ),
        )
        for probe in cases:
            with self.subTest(records=len(probe.records)):
                with self.assertRaisesRegex(BundleContractError, "cached global"):
                    audit_predicted_with_cached_global(
                        relation_ids=(20, 21),
                        predicted_incomplete_ids=(21,),
                        probe=probe,
                    )

    def test_cached_global_record_is_revalidated_before_it_can_drive_the_audit(self) -> None:
        missing = MissingReferences((), (99,), ())
        valid = _probe_record((20, 21), missing)
        forged = replace(
            valid,
            process=replace(valid.process, argv=valid.process.argv + ("--forged",)),
        )

        class ForgedProbe:
            records = (forged,)

            def __call__(self, batch: tuple[int, ...]) -> MissingReferences:
                raise AssertionError("forged cached evidence must fail before probing")

        with self.assertRaisesRegex(BundleContractError, "pinned probe"):
            audit_predicted_with_cached_global(
                relation_ids=(20, 21),
                predicted_incomplete_ids=(21,),
                probe=ForgedProbe(),
            )

    def test_twenty_four_predicted_incomplete_roots_require_exactly_twenty_six_scans(self) -> None:
        all_ids = tuple(range(1, 27))
        incomplete_ids = tuple(range(2, 26))
        retained_ids = (1, 26)
        empty = MissingReferences((), (), ())
        singleton_missing = {
            relation_id: MissingReferences((), (1000 + relation_id,), ())
            for relation_id in incomplete_ids
        }
        global_missing = MissingReferences(
            (), tuple(1000 + relation_id for relation_id in incomplete_ids), ()
        )

        class TwentySixScanProbe:
            def __init__(self) -> None:
                self._records = [_probe_record(all_ids, global_missing)]
                self.actual_calls: list[tuple[int, ...]] = []

            @property
            def records(self) -> tuple[ClosureProbeEvidence, ...]:
                return tuple(self._records)

            def __call__(self, batch: tuple[int, ...]) -> MissingReferences:
                self.actual_calls.append(batch)
                missing = (
                    singleton_missing[batch[0]]
                    if len(batch) == 1
                    else empty
                )
                self._records.append(_probe_record(batch, missing))
                return missing

        probe = TwentySixScanProbe()

        audit = audit_predicted_with_cached_global(
            relation_ids=all_ids,
            predicted_incomplete_ids=incomplete_ids,
            probe=probe,
        )

        self.assertEqual(len(probe.records), 26)
        self.assertEqual(len(probe.actual_calls), 25)
        self.assertEqual(probe.actual_calls[:24], [(value,) for value in incomplete_ids])
        self.assertEqual(probe.actual_calls[-1], retained_ids)
        self.assertEqual(len(audit.probed_batches), 26)

    def test_oversized_plan_is_rejected_before_any_cached_or_new_probe_work(self) -> None:
        class NeverProbe:
            records: tuple[ClosureProbeEvidence, ...] = ()
            calls = 0

            def __call__(self, batch: tuple[int, ...]) -> MissingReferences:
                self.calls += 1
                raise AssertionError("oversized plan must fail before probing")

        probe = NeverProbe()

        with self.assertRaisesRegex(BundleContractError, "probe.*ceiling"):
            audit_predicted_with_cached_global(
                relation_ids=tuple(range(1, 259)),
                predicted_incomplete_ids=tuple(range(2, 258)),
                probe=probe,
            )

        self.assertEqual(probe.calls, 0)


class TransactionalWriterTests(unittest.TestCase):
    def test_native_no_replace_error_preserves_both_source_and_target_paths(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source")
            target = Path(temporary, "target")
            source.mkdir()
            target.mkdir()

            with self.assertRaises(OSError) as caught:
                bundle_module._windows_move_directory_no_replace(source, target)

            self.assertEqual(Path(caught.exception.filename), source)
            self.assertEqual(Path(caught.exception.filename2), target)
            self.assertTrue(source.is_dir())
            self.assertTrue(target.is_dir())

    def test_writes_every_document_and_raw_file_then_atomically_finalizes(self) -> None:
        bundle = _bundle()
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            destination = parent / "closure-audit"

            result = write_relation_closure_audit_bundle(bundle, destination)

            expected = {
                "classification.json": bundle.classification_bytes,
                "execution-evidence.json": bundle.execution_manifest_bytes,
                **{item.relative_path: item.content for item in bundle.raw_files},
            }
            actual = {
                path.relative_to(destination).as_posix(): path.read_bytes()
                for path in destination.rglob("*")
                if path.is_file()
            }
            self.assertEqual(actual, expected)
            self.assertEqual(result.destination, destination.resolve())
            self.assertEqual(result.file_count, len(expected))
            self.assertEqual(result.total_bytes, sum(len(value) for value in expected.values()))
            self.assertEqual(
                result.classification_sha256,
                hashlib.sha256(bundle.classification_bytes).hexdigest(),
            )
            self.assertEqual(
                result.execution_manifest_sha256,
                hashlib.sha256(bundle.execution_manifest_bytes).hexdigest(),
            )
            self.assertEqual(list(parent.glob("*.staging")), [])

    def test_existing_destination_is_never_overwritten(self) -> None:
        bundle = _bundle()
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary, "closure-audit")
            destination.mkdir()
            sentinel = destination / "sentinel.bin"
            sentinel.write_bytes(b"keep")

            with self.assertRaisesRegex(BundleContractError, "already exists"):
                write_relation_closure_audit_bundle(bundle, destination)

            self.assertEqual(sentinel.read_bytes(), b"keep")
            self.assertEqual(tuple(destination.iterdir()), (sentinel,))

    def test_finalization_failure_removes_staging_and_leaves_destination_absent(self) -> None:
        bundle = _bundle()
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            destination = parent / "closure-audit"

            with patch(
                "tools.experiment8.osm_closure_audit_bundle."
                "_windows_move_directory_no_replace",
                side_effect=OSError("injected finalization failure"),
                create=True,
            ):
                with self.assertRaisesRegex(BundleContractError, "transaction failed"):
                    write_relation_closure_audit_bundle(bundle, destination)

            self.assertFalse(destination.exists())
            self.assertEqual(tuple(parent.iterdir()), ())

    def test_destination_created_at_finalization_race_is_not_replaced(self) -> None:
        bundle = _bundle()
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            destination = parent / "closure-audit"

            def create_destination_then_finalize(source: Path, target: Path) -> None:
                target.mkdir()
                (target / "sentinel.bin").write_bytes(b"keep")
                raise OSError("simulated Windows no-replace refusal")

            with patch.object(
                bundle_module,
                "_windows_move_directory_no_replace",
                side_effect=create_destination_then_finalize,
                create=True,
            ):
                with self.assertRaisesRegex(BundleContractError, "transaction failed"):
                    write_relation_closure_audit_bundle(bundle, destination)

            self.assertEqual(
                (destination / "sentinel.bin").read_bytes(),
                b"keep",
            )
            self.assertEqual(
                tuple(path.name for path in parent.iterdir()),
                ("closure-audit",),
            )

    def test_staging_creation_failure_is_canonical_and_writes_nothing(self) -> None:
        bundle = _bundle()
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            destination = parent / "closure-audit"

            with patch(
                "tools.experiment8.osm_closure_audit_bundle.tempfile.mkdtemp",
                side_effect=OSError("injected staging failure"),
            ):
                with self.assertRaisesRegex(BundleContractError, "staging"):
                    write_relation_closure_audit_bundle(bundle, destination)

            self.assertFalse(destination.exists())
            self.assertEqual(tuple(parent.iterdir()), ())

    def test_writer_revalidates_documents_raw_bindings_and_rejects_orphans(self) -> None:
        bundle = _bundle()
        execution = json.loads(bundle.execution_manifest_bytes)
        execution["probes"][0]["processes"]["getid"]["stderr"]["sha256"] = "f" * 64
        forged_hash = replace(
            bundle,
            execution_manifest_bytes=_canonical_json_bytes(execution),
        )
        cases = (
            replace(bundle, classification_bytes=b"{}\n"),
            forged_hash,
            replace(bundle, raw_files=bundle.raw_files[1:]),
            replace(
                bundle,
                raw_files=(*bundle.raw_files, BundleFile("raw/orphan.bin", b"orphan")),
            ),
        )

        for index, forged in enumerate(cases):
            with self.subTest(index=index), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary, "closure-audit")

                with self.assertRaisesRegex(
                    BundleContractError, "bundle|classification|manifest|raw"
                ):
                    write_relation_closure_audit_bundle(forged, destination)

                self.assertFalse(destination.exists())
                self.assertEqual(tuple(Path(temporary).iterdir()), ())

    def test_every_probe_process_raw_hash_is_recomputed_by_writer(self) -> None:
        bundle = _bundle()
        for probe_index in range(4):
            for process_name in ("release", "preHash", "getid", "postHash"):
                with self.subTest(probe=probe_index, process=process_name):
                    execution = json.loads(bundle.execution_manifest_bytes)
                    execution["probes"][probe_index]["processes"][process_name][
                        "stdout"
                    ]["sha256"] = "0" * 64
                    forged = replace(
                        bundle,
                        execution_manifest_bytes=_canonical_json_bytes(execution),
                    )
                    with tempfile.TemporaryDirectory() as temporary:
                        destination = Path(temporary, "closure-audit")

                        with self.assertRaisesRegex(
                            BundleContractError, "raw bytes"
                        ):
                            write_relation_closure_audit_bundle(forged, destination)

                        self.assertEqual(tuple(Path(temporary).iterdir()), ())

    def test_writer_rejects_unsupported_rename_semantics_before_staging(self) -> None:
        bundle = _bundle()
        with tempfile.TemporaryDirectory() as temporary, patch.object(
            bundle_module, "_WINDOWS_NO_REPLACE_RENAME", False, create=True
        ):
            destination = Path(temporary, "closure-audit")

            with self.assertRaisesRegex(BundleContractError, "Windows"):
                write_relation_closure_audit_bundle(bundle, destination)

            self.assertEqual(tuple(Path(temporary).iterdir()), ())

    def test_mid_write_failure_removes_partial_staging_tree(self) -> None:
        bundle = _bundle()
        original = bundle_module._write_exclusive_file
        calls = 0

        def fail_after_one_file(root: Path, item: BundleFile) -> None:
            nonlocal calls
            calls += 1
            if calls == 2:
                raise OSError("injected mid-write failure")
            original(root, item)

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            bundle_module,
            "_write_exclusive_file",
            side_effect=fail_after_one_file,
        ):
            parent = Path(temporary)
            destination = parent / "closure-audit"

            with self.assertRaisesRegex(BundleContractError, "transaction failed"):
                write_relation_closure_audit_bundle(bundle, destination)

            self.assertFalse(destination.exists())
            self.assertEqual(tuple(parent.iterdir()), ())


class FailClosedReconciliationTests(unittest.TestCase):
    def _build(
        self,
        *,
        roots: RootSelection | None = None,
        classification: RegionalClosureClassification | None = None,
        audit: RelationClosureAudit | None = None,
        records: tuple[ClosureProbeEvidence, ...] | None = None,
    ):
        fixture_roots, fixture_classification, fixture_audit, fixture_records = _fixture()
        return build_relation_closure_audit_bundle(
            profile=SYNTHETIC_RELATION_CLOSURE_PROFILE,
            source_identity_sha256=SOURCE_IDENTITY_SHA256,
            selection_manifest_sha256=SELECTION_MANIFEST_SHA256,
            candidate_xml_sha256=CANDIDATE_XML_SHA256,
            roots=fixture_roots if roots is None else roots,
            classification=(
                fixture_classification if classification is None else classification
            ),
            audit=fixture_audit if audit is None else audit,
            probe_records=fixture_records if records is None else records,
        )

    def test_generic_builder_cannot_claim_maryland_and_exact_factory_is_gated(self) -> None:
        roots, classification, audit, records = _fixture()
        common = {
            "source_identity_sha256": SOURCE_IDENTITY_SHA256,
            "selection_manifest_sha256": SELECTION_MANIFEST_SHA256,
            "candidate_xml_sha256": CANDIDATE_XML_SHA256,
            "roots": roots,
            "classification": classification,
            "audit": audit,
            "probe_records": records,
        }

        with self.assertRaisesRegex(BundleContractError, "synthetic profile"):
            build_relation_closure_audit_bundle(
                profile=MARYLAND_REGIONAL_PROFILE,
                **common,
            )
        with self.assertRaisesRegex(BundleContractError, "Maryland.*gated"):
            build_maryland_relation_closure_audit_bundle(**common)

    def test_future_maryland_gate_rejects_nonmatching_classification_model_digest(
        self,
    ) -> None:
        roots, classification, audit, records = _fixture()
        frozen_classification_sha256 = "4" * 64

        class WrongModelBundle:
            classification_sha256 = "5" * 64

        constants = (
            ("_MARYLAND_EXACT_SOURCE_IDENTITY_SHA256", SOURCE_IDENTITY_SHA256),
            (
                "_MARYLAND_EXACT_SELECTION_MANIFEST_SHA256",
                SELECTION_MANIFEST_SHA256,
            ),
            ("_MARYLAND_EXACT_CANDIDATE_XML_SHA256", CANDIDATE_XML_SHA256),
            ("_MARYLAND_EXACT_ROOTS", roots),
            (
                "_MARYLAND_EXACT_CLASSIFICATION_SHA256",
                frozen_classification_sha256,
            ),
        )
        with ExitStack() as stack:
            for name, value in constants:
                stack.enter_context(patch.object(bundle_module, name, value))
            with self.assertRaisesRegex(BundleContractError, "frozen identity/model"):
                bundle_module._validate_profile_instance(
                    profile=MARYLAND_REGIONAL_PROFILE,
                    source_identity_sha256=SOURCE_IDENTITY_SHA256,
                    selection_manifest_sha256=SELECTION_MANIFEST_SHA256,
                    candidate_xml_sha256=CANDIDATE_XML_SHA256,
                    roots=roots,
                    classification_sha256="5" * 64,
                )
            builder = stack.enter_context(
                patch.object(
                    bundle_module,
                    "_build_relation_closure_audit_bundle",
                    return_value=WrongModelBundle(),
                )
            )
            with self.assertRaisesRegex(BundleContractError, "classification"):
                build_maryland_relation_closure_audit_bundle(
                    source_identity_sha256=SOURCE_IDENTITY_SHA256,
                    selection_manifest_sha256=SELECTION_MANIFEST_SHA256,
                    candidate_xml_sha256=CANDIDATE_XML_SHA256,
                    roots=roots,
                    classification=classification,
                    audit=audit,
                    probe_records=records,
                )

            builder.assert_called_once()

    def test_model_union_batch_and_transcript_mismatches_are_fatal(self) -> None:
        roots, classification, audit, records = _fixture()
        cases = (
            (
                {"classification": replace(
                    classification,
                    missing_references=MissingReferences((), (100,), ()),
                )},
                "globally missing|global missing",
            ),
            (
                {"audit": replace(audit, probed_batches=audit.probed_batches[:-1])},
                "probe batches",
            ),
            (
                {"records": records[:-1]},
                "record count",
            ),
            (
                {"records": (
                    replace(
                        records[0],
                        process=replace(
                            records[0].process,
                            stderr=records[0].process.stderr + b"ERROR: late failure\n",
                        ),
                    ),
                    *records[1:],
                )},
                "transcript",
            ),
            (
                {"roots": RootSelection(way_ids=roots.way_ids, relation_ids=(20, 22, 23))},
                "selected|classified incomplete",
            ),
        )
        for kwargs, message in cases:
            with self.subTest(message=message):
                with self.assertRaisesRegex(BundleContractError, message):
                    self._build(**kwargs)

    def test_forged_runtime_getid_argv_and_release_evidence_are_fatal(self) -> None:
        _, _, _, records = _fixture()
        first = records[0]
        forged_hash = replace(
            first.runtime.hash_process,
            stdout=b"self-consistent but not pinned\n",
        )
        forged_runtime = replace(first.runtime, hash_process=forged_hash)
        cases = (
            replace(
                first,
                runtime=forged_runtime,
                post_hash_process=replace(
                    first.post_hash_process,
                    stdout=b"self-consistent but not pinned\n",
                ),
            ),
            replace(
                first,
                process=replace(
                    first.process,
                    argv=first.process.argv + ("--unexpected-option",),
                ),
            ),
            replace(
                first,
                runtime=replace(
                    first.runtime,
                    release_process=replace(
                        first.runtime.release_process,
                        stdout=b"Ubuntu counterfeit\n",
                    ),
                ),
            ),
        )
        for forged in cases:
            with self.subTest(argv=forged.process.argv[-1]):
                with self.assertRaisesRegex(BundleContractError, "pinned probe"):
                    self._build(records=(forged, *records[1:]))

    def test_nested_process_must_be_exact_evidence_and_cannot_lie_about_hashes(self) -> None:
        _, _, _, records = _fixture()
        first = records[0]
        real = first.runtime.release_process

        class HashLyingProcess:
            argv = real.argv
            returncode = real.returncode
            stdout = real.stdout
            stderr = real.stderr
            argv_sha256 = real.argv_sha256
            stdout_sha256 = "0" * 64
            stderr_sha256 = "f" * 64

        forged = replace(
            first,
            runtime=replace(
                first.runtime,
                release_process=HashLyingProcess(),  # type: ignore[arg-type]
            ),
        )

        with self.assertRaisesRegex(BundleContractError, "process.*evidence"):
            self._build(records=(forged, *records[1:]))

    def test_incomplete_dependency_cycle_is_invalid_not_regional_clipping(self) -> None:
        _, classification, _, _ = _fixture()
        cyclic = replace(
            classification,
            incomplete_relations=(
                replace(
                    classification.incomplete_relations[0],
                    dependency_relation_ids=(22,),
                ),
                replace(
                    classification.incomplete_relations[1],
                    dependency_relation_ids=(21,),
                ),
            ),
        )

        with self.assertRaisesRegex(BundleContractError, "dependency cycle"):
            self._build(classification=cyclic)

    def test_deep_acyclic_dependency_chain_fails_bounded_not_by_python_recursion(self) -> None:
        relation_ids = tuple(range(1, 1_101))
        missing = MissingReferences((), (99,), ())
        roots = RootSelection(way_ids=(), relation_ids=relation_ids)
        classification = RegionalClosureClassification(
            complete_way_ids=(),
            complete_relation_ids=(),
            incomplete_relations=(
                IncompleteRelationRoot(
                    relation_id=relation_id,
                    direct_missing_members=(
                        (OsmRelationMember("way", 99, "", ordinal=0),)
                        if relation_id == relation_ids[-1]
                        else ()
                    ),
                    dependency_relation_ids=(
                        ()
                        if relation_id == relation_ids[-1]
                        else (relation_id + 1,)
                    ),
                )
                for relation_id in relation_ids
            ),
            missing_references=missing,
        )
        audit = RelationClosureAudit(
            complete_relation_ids=(),
            incomplete_relations=tuple(
                IncompleteRelationClosure(relation_id, missing)
                for relation_id in relation_ids
            ),
            global_missing_references=missing,
            probed_batches=(
                relation_ids,
                *((relation_id,) for relation_id in relation_ids),
            ),
        )

        with self.assertRaisesRegex(BundleContractError, "probe plan"):
            self._build(
                roots=roots,
                classification=classification,
                audit=audit,
                records=(),
            )

    def test_direct_missing_members_require_exact_source_ordinals(self) -> None:
        _, classification, _, _ = _fixture()
        missing_ordinal = replace(
            classification,
            incomplete_relations=(
                replace(
                    classification.incomplete_relations[0],
                    direct_missing_members=(
                        OsmRelationMember("way", 99, "main_stream"),
                    ),
                ),
                classification.incomplete_relations[1],
            ),
        )

        with self.assertRaisesRegex(BundleContractError, "ordinal"):
            self._build(classification=missing_ordinal)

    def test_bundle_types_enforce_independent_file_and_total_byte_ceilings(self) -> None:
        with self.assertRaisesRegex(BundleContractError, "canonical relative"):
            BundleFile(".", b"not a file")

        with patch(
            "tools.experiment8.osm_closure_audit_bundle._MAX_RAW_FILE_BYTES", 2
        ):
            with self.assertRaisesRegex(BundleContractError, "raw-file byte ceiling"):
                BundleFile("raw/too-large.bin", b"123")

        with patch(
            "tools.experiment8.osm_closure_audit_bundle._MAX_BUNDLE_BYTES", 10
        ):
            with self.assertRaisesRegex(BundleContractError, "bundle byte ceiling"):
                RelationClosureAuditBundle(
                    classification_bytes=b"12345",
                    execution_manifest_bytes=b"67890",
                    raw_files=(BundleFile("raw/one.bin", b"x"),),
                )

        for constant, value, message in (
            ("_MAX_SELECTED_WAYS", 1, "selected way count"),
            ("_MAX_RELATION_LINKS", 1, "relation linkage"),
            ("_MAX_SERIALIZED_MISSING_REFERENCES", 1, "missing-reference"),
            ("_MAX_PROBE_RECORDS", 3, "probe plan"),
        ):
            with self.subTest(constant=constant), patch.object(
                bundle_module, constant, value
            ):
                with self.assertRaisesRegex(BundleContractError, message):
                    self._build()

    def test_a_selected_root_can_never_be_classified_as_a_missing_reference(self) -> None:
        roots, classification, audit, _ = _fixture()
        missing_selected = MissingReferences((), (), (21,))
        selected_missing_classification = replace(
            classification,
            incomplete_relations=(
                IncompleteRelationRoot(
                    relation_id=21,
                    direct_missing_members=(
                        OsmRelationMember("relation", 21, "self_reference"),
                    ),
                    dependency_relation_ids=(),
                ),
                classification.incomplete_relations[1],
            ),
            missing_references=missing_selected,
        )
        selected_missing_audit = replace(
            audit,
            incomplete_relations=(
                IncompleteRelationClosure(21, missing_selected),
                IncompleteRelationClosure(22, missing_selected),
            ),
            global_missing_references=missing_selected,
        )
        records = (
            _probe_record(roots.relation_ids, missing_selected),
            _probe_record((21,), missing_selected),
            _probe_record((22,), missing_selected),
            _probe_record((20, 23), MissingReferences((), (), ())),
        )

        with self.assertRaisesRegex(BundleContractError, "selected relation.*missing"):
            self._build(
                classification=selected_missing_classification,
                audit=selected_missing_audit,
                records=records,
            )

    def test_per_root_missing_sets_cannot_be_swapped_while_global_union_matches(self) -> None:
        roots, classification, audit, _ = _fixture()
        missing_99 = MissingReferences((), (99,), ())
        missing_100 = MissingReferences((), (100,), ())
        global_missing = MissingReferences((), (99, 100), ())
        two_direct_roots = replace(
            classification,
            incomplete_relations=(
                IncompleteRelationRoot(
                    relation_id=21,
                    direct_missing_members=(
                        OsmRelationMember("way", 99, "main_stream", ordinal=7),
                    ),
                    dependency_relation_ids=(),
                ),
                IncompleteRelationRoot(
                    relation_id=22,
                    direct_missing_members=(
                        OsmRelationMember("way", 100, "main_stream", ordinal=8),
                    ),
                    dependency_relation_ids=(),
                ),
            ),
            missing_references=global_missing,
        )
        swapped_audit = replace(
            audit,
            incomplete_relations=(
                IncompleteRelationClosure(21, missing_100),
                IncompleteRelationClosure(22, missing_99),
            ),
            global_missing_references=global_missing,
        )
        records = (
            _probe_record(roots.relation_ids, global_missing),
            _probe_record((21,), missing_100),
            _probe_record((22,), missing_99),
            _probe_record((20, 23), MissingReferences((), (), ())),
        )

        with self.assertRaisesRegex(BundleContractError, "per-root missing set"):
            self._build(
                classification=two_direct_roots,
                audit=swapped_audit,
                records=records,
            )


if __name__ == "__main__":
    unittest.main()
