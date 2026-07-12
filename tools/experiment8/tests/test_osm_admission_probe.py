from __future__ import annotations

import ctypes
import hashlib
import inspect
import json
import os
import re
import shutil
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path, PurePosixPath
from types import SimpleNamespace
from unittest import mock

import tools.experiment8.osm_admission_probe as admission_probe

from tools.experiment8.osm_admission_probe import (
    ADMISSION_GENERATOR,
    AdmissionProbeError,
    MARYLAND_COMPLETE_RELATION_COUNT,
    MARYLAND_ROOT_IDS_BYTES,
    MARYLAND_ROOT_IDS_SHA256,
    MARYLAND_SELECTED_RELATION_COUNT,
    MARYLAND_SELECTED_WAY_COUNT,
    MarylandAdmissionResult,
    RawProcessCapture,
    _AdmissionExpectations,
    _AdmissionSnapshot,
    _RelationGateOutcome,
    _execute_admission,
    _require_matching_provenance_goldens,
    _runtime_identity_document,
    encode_canonical_id_file,
    parse_canonical_id_file,
    parse_check_refs_result,
    parse_id_file_getid_result,
    run_maryland_admission,
)
from tools.experiment8.osm_closure_probe import (
    BOOST_LIBRARY_PATH,
    BOOST_LIBRARY_SHA256,
    OSMIUM_BINARY_PATH,
    OSMIUM_BINARY_SHA256,
    PINNED_LIBOSMIUM_VERSION,
    PINNED_OSMIUM_VERSION,
    ProcessEvidence,
    run_bounded_process,
)
from tools.experiment8.osm_hydro_source import (
    IncompleteRelationClosure,
    IncompleteRelationRoot,
    MissingReferences,
    OsmRelationMember,
    RegionalClosureClassification,
    RelationClosureAudit,
    RootSelection,
)


def _timed(payload: str) -> str:
    return f"[ 0:00] {payload}"


def _getid_stderr(
    *,
    source: str,
    output: str,
    node_count: int,
    way_count: int,
    relation_count: int,
    overwrite: bool,
    fsync: bool,
) -> bytes:
    lines = [
        _timed("Reading ID file..."),
        _timed("Started osmium getid"),
        _timed(f"  osmium version {PINNED_OSMIUM_VERSION}"),
        _timed(f"  libosmium version {PINNED_LIBOSMIUM_VERSION}"),
        _timed("Command line options and default settings:"),
        _timed("  input options:"),
        _timed(f"    file name: {source}"),
        _timed("    file format: "),
        _timed("  output options:"),
        _timed(f"    file name: {output}"),
        _timed("    file format: pbf"),
        _timed(f"    generator: {ADMISSION_GENERATOR}"),
        _timed(f"    overwrite: {'yes' if overwrite else 'no'}"),
        _timed(f"    fsync: {'yes' if fsync else 'no'}"),
        _timed("  other options:"),
        _timed("    add referenced objects: yes"),
        _timed("    remove tags on non-matching objects: no"),
        _timed("    work with history files: no"),
        _timed("    default object type: node"),
        _timed(
            f"    looking for {node_count} node ID(s), {way_count} way ID(s), "
            f"and {relation_count} relation ID(s)"
        ),
        _timed("Following references..."),
    ]
    if relation_count:
        lines.extend(
            [
                _timed("  Reading input file to find relations in relations..."),
                _timed("  Reading input file to find nodes/ways in relations..."),
            ]
        )
    if way_count or relation_count:
        lines.append(_timed("  Reading input file to find nodes in ways..."))
    lines.extend(
        [
            _timed("Done following references."),
            _timed("Opening input file..."),
            _timed("Opening output file..."),
            _timed("Copying matching objects to output file..."),
            _timed("Closing output file..."),
            _timed("Closing input file..."),
            _timed("Found all objects."),
            _timed("Peak memory used: 17 MBytes"),
            _timed("Done."),
        ]
    )
    return ("\n".join(lines) + "\n").encode("utf-8")


def _check_refs_stderr(
    input_path: str,
    *,
    nodes: int = 3,
    ways: int = 2,
    relations: int = 1,
    missing_nodes_in_ways: int = 0,
    missing_nodes_in_relations: int = 0,
    missing_ways_in_relations: int = 0,
    missing_relations_in_relations: int = 0,
) -> bytes:
    lines = [
        _timed("Started osmium check-refs"),
        _timed(f"  osmium version {PINNED_OSMIUM_VERSION}"),
        _timed(f"  libosmium version {PINNED_LIBOSMIUM_VERSION}"),
        _timed("Command line options and default settings:"),
        _timed("  input options:"),
        _timed(f"    file name: {input_path}"),
        _timed("    file format: pbf"),
        _timed("  other options:"),
        _timed("    show ids: no"),
        _timed("    check relations: yes"),
        _timed("Reading nodes..."),
        _timed("Reading ways..."),
        _timed("Reading relations..."),
        f"There are {nodes} nodes, {ways} ways, and {relations} relations in this file.",
        f"Nodes     in ways      missing: {missing_nodes_in_ways}",
        f"Nodes     in relations missing: {missing_nodes_in_relations}",
        f"Ways      in relations missing: {missing_ways_in_relations}",
        f"Relations in relations missing: {missing_relations_in_relations}",
        _timed("Memory used for indexes: 1 MBytes"),
        _timed("Peak memory used: 18 MBytes"),
        _timed("Done."),
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


class CanonicalIdFileTests(unittest.TestCase):
    def test_encodes_sorted_way_then_relation_records_with_final_lf(self) -> None:
        self.assertEqual(
            encode_canonical_id_file((7, 12), (3, 19)),
            b"w7\nw12\nr3\nr19\n",
        )

    def test_parser_round_trips_canonical_bytes(self) -> None:
        encoded = b"w7\nw12\nr3\nr19\n"
        self.assertEqual(
            parse_canonical_id_file(encoded),
            RootSelection(way_ids=(7, 12), relation_ids=(3, 19)),
        )

    def test_noncanonical_id_bytes_fail_closed(self) -> None:
        malformed = (
            b"w01\n",
            b"w1\rw2\n",
            b"w2\nw1\n",
            b"w1\nw1\n",
            b"r1\nw2\n",
            b"x1\n",
            b"w1",
            b"\n",
        )
        for raw in malformed:
            with self.subTest(raw=raw), self.assertRaises(AdmissionProbeError):
                parse_canonical_id_file(raw)

    def test_noncanonical_python_ids_fail_closed(self) -> None:
        for ways, relations in (
            ((2, 1), ()),
            ((1, 1), ()),
            ((True,), ()),
            ((0,), ()),
            ((), (3, 2)),
        ):
            with self.subTest(ways=ways, relations=relations):
                with self.assertRaises(AdmissionProbeError):
                    encode_canonical_id_file(ways, relations)


class GetidTranscriptTests(unittest.TestCase):
    SOURCE = "/mnt/e/source/maryland-260710.osm.pbf"
    OUTPUT = "/mnt/e/stage/admitted.osm.pbf"

    def test_exact_id_file_found_all_transcript_passes(self) -> None:
        parse_id_file_getid_result(
            returncode=0,
            stdout=b"",
            stderr=_getid_stderr(
                source=self.SOURCE,
                output=self.OUTPUT,
                node_count=0,
                way_count=2,
                relation_count=1,
                overwrite=False,
                fsync=True,
            ),
            source_wsl_path=self.SOURCE,
            output_wsl_path=self.OUTPUT,
            expected_way_count=2,
            expected_relation_count=1,
            overwrite=False,
            fsync=True,
        )

    def test_direct_way_probe_has_the_exact_short_reference_scan(self) -> None:
        parse_id_file_getid_result(
            returncode=0,
            stdout=b"",
            stderr=_getid_stderr(
                source=self.SOURCE,
                output="/dev/null",
                node_count=0,
                way_count=2,
                relation_count=0,
                overwrite=True,
                fsync=False,
            ),
            source_wsl_path=self.SOURCE,
            output_wsl_path="/dev/null",
            expected_way_count=2,
            expected_relation_count=0,
            overwrite=True,
            fsync=False,
        )

    def test_extra_malformed_or_missing_diagnostic_is_fatal(self) -> None:
        valid = _getid_stderr(
            source=self.SOURCE,
            output=self.OUTPUT,
            node_count=0,
            way_count=2,
            relation_count=1,
            overwrite=False,
            fsync=True,
        )
        mutations = (
            valid + _timed("extra").encode() + b"\n",
            valid.replace(b"Found all objects.", b"Did not find 1 object(s)."),
            valid.replace(b"2 way ID(s)", b"3 way ID(s)"),
            valid.replace(self.OUTPUT.encode(), b"/mnt/e/other.osm.pbf"),
            valid[:-1],
        )
        for stderr in mutations:
            with self.subTest(stderr=stderr[-80:]):
                with self.assertRaises(AdmissionProbeError):
                    parse_id_file_getid_result(
                        returncode=0,
                        stdout=b"",
                        stderr=stderr,
                        source_wsl_path=self.SOURCE,
                        output_wsl_path=self.OUTPUT,
                        expected_way_count=2,
                        expected_relation_count=1,
                        overwrite=False,
                        fsync=True,
                    )

    def test_nonzero_exit_or_stdout_is_fatal(self) -> None:
        valid = _getid_stderr(
            source=self.SOURCE,
            output=self.OUTPUT,
            node_count=0,
            way_count=2,
            relation_count=1,
            overwrite=False,
            fsync=True,
        )
        for returncode, stdout in ((1, b""), (0, b"unexpected\n")):
            with self.subTest(returncode=returncode, stdout=stdout):
                with self.assertRaises(AdmissionProbeError):
                    parse_id_file_getid_result(
                        returncode=returncode,
                        stdout=stdout,
                        stderr=valid,
                        source_wsl_path=self.SOURCE,
                        output_wsl_path=self.OUTPUT,
                        expected_way_count=2,
                        expected_relation_count=1,
                        overwrite=False,
                        fsync=True,
                    )


class CheckRefsTranscriptTests(unittest.TestCase):
    INPUT = "/mnt/e/stage/admitted.osm.pbf"

    def test_exact_zero_missing_transcript_passes(self) -> None:
        summary = parse_check_refs_result(
            returncode=0,
            stdout=b"",
            stderr=_check_refs_stderr(self.INPUT, nodes=12, ways=4, relations=2),
            input_wsl_path=self.INPUT,
        )
        self.assertEqual(
            (
                summary.node_count,
                summary.way_count,
                summary.relation_count,
                summary.missing_node_references,
                summary.missing_way_references,
                summary.missing_relation_references,
                summary.missing_changeset_references,
            ),
            (12, 4, 2, 0, 0, 0, 0),
        )

    def test_every_missing_reference_class_fails(self) -> None:
        fields = (
            "missing_nodes_in_ways",
            "missing_nodes_in_relations",
            "missing_ways_in_relations",
            "missing_relations_in_relations",
        )
        for field in fields:
            arguments = {field: 1}
            with self.subTest(field=field), self.assertRaises(AdmissionProbeError):
                parse_check_refs_result(
                    returncode=1,
                    stdout=b"",
                    stderr=_check_refs_stderr(self.INPUT, **arguments),
                    input_wsl_path=self.INPUT,
                )

    def test_extra_malformed_count_path_or_exit_mismatch_fails(self) -> None:
        valid = _check_refs_stderr(self.INPUT)
        cases = (
            (0, b"", valid + b"extra\n"),
            (0, b"", valid.replace(self.INPUT.encode(), b"/mnt/e/wrong.pbf")),
            (
                0,
                b"",
                valid.replace(
                    b"Nodes     in ways      missing: 0", b"Nodes missing: 0"
                ),
            ),
            (1, b"", valid),
            (0, b"unexpected\n", valid),
            (0, b"", valid[:-1]),
        )
        for returncode, stdout, stderr in cases:
            with self.subTest(stderr=stderr[-80:]), self.assertRaises(AdmissionProbeError):
                parse_check_refs_result(
                    returncode=returncode,
                    stdout=stdout,
                    stderr=stderr,
                    input_wsl_path=self.INPUT,
                )


_FIXTURE_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="fixture">
  <way id="10" version="1" timestamp="2026-07-10T00:00:00Z">
    <nd ref="100"/><nd ref="101"/><tag k="name" v="Alpha"/><tag k="waterway" v="river"/>
  </way>
  <way id="11" version="1" timestamp="2026-07-10T00:00:00Z">
    <nd ref="101"/><nd ref="102"/><tag k="name" v="Beta"/><tag k="waterway" v="stream"/>
  </way>
  <relation id="20" version="1" timestamp="2026-07-10T00:00:00Z">
    <member type="way" ref="10" role=""/><tag k="name" v="Alpha"/><tag k="type" v="waterway"/>
  </relation>
  <relation id="21" version="1" timestamp="2026-07-10T00:00:00Z">
    <member type="way" ref="999" role="main"/>
    <tag k="name" v="Clipped"/><tag k="type" v="waterway"/>
  </relation>
</osm>
"""


def _relation_outcome() -> _RelationGateOutcome:
    missing = MissingReferences(node_ids=(), way_ids=(999,), relation_ids=())
    classification = RegionalClosureClassification(
        complete_way_ids=(10, 11),
        complete_relation_ids=(20,),
        incomplete_relations=(
            IncompleteRelationRoot(
                relation_id=21,
                direct_missing_members=(
                    OsmRelationMember("way", 999, "main", ordinal=0),
                ),
                dependency_relation_ids=(),
            ),
        ),
        missing_references=missing,
    )
    audit = RelationClosureAudit(
        complete_relation_ids=(20,),
        incomplete_relations=(IncompleteRelationClosure(21, missing),),
        global_missing_references=missing,
        probed_batches=((20, 21), (21,), (20,)),
    )
    captures = tuple(
        RawProcessCapture(
            label=f"relation/{batch:03d}/{name}",
            process=ProcessEvidence(("fixture", str(batch), name), 0, b"", b""),
        )
        for batch in range(3)
        for name in ("release", "pre-hash", "getid", "post-hash")
    )
    return _RelationGateOutcome(classification, audit, captures)


def _windows_from_wsl(value: str) -> Path:
    match = re.fullmatch(r"/mnt/([a-z])/(.*)", value)
    if match is None:
        raise AssertionError(f"not a fixture WSL path: {value}")
    return Path(f"{match.group(1).upper()}:\\") / Path(
        *PurePosixPath(match.group(2)).parts
    )


class _FakeRunner:
    def __init__(self, snapshot: _AdmissionSnapshot) -> None:
        self.snapshot = snapshot
        self.calls: list[tuple[str, ...]] = []
        self.output_bytes = b"bounded synthetic admitted PBF"
        self.mutate_on_post_hash = False
        self.post_hash_output_mutation_denied = False
        self.mutate_admitted_ids = False
        self.admitted_id_mutation_denied = False
        self.mutate_on_check_refs = False
        self.check_refs_mutation_denied = False
        self._hash_call_count = 0

    def __call__(self, argv: tuple[str, ...]) -> ProcessEvidence:
        self.calls.append(argv)
        if "/usr/bin/sha256sum" in argv:
            self._hash_call_count += 1
            if self.mutate_on_post_hash and self._hash_call_count == 2:
                getid_output = next(
                    call[call.index("-o") + 1]
                    for call in self.calls
                    if "getid" in call and call[call.index("-o") + 1] != "/dev/null"
                )
                try:
                    _windows_from_wsl(getid_output).write_bytes(b"drifted")
                except OSError:
                    self.post_hash_output_mutation_denied = True
            stdout = (
                f"{OSMIUM_BINARY_SHA256} *{OSMIUM_BINARY_PATH}\n"
                f"{BOOST_LIBRARY_SHA256} *{BOOST_LIBRARY_PATH}\n"
                f"{self.snapshot.source_sha256} *{self.snapshot.source_wsl_path}\n"
            ).encode("utf-8")
            return ProcessEvidence(argv, 0, stdout, b"")
        if "getid" in argv:
            output = argv[argv.index("-o") + 1]
            if (
                hashlib.sha256(self.snapshot.source_path.read_bytes()).hexdigest()
                != self.snapshot.source_sha256
            ):
                raise AssertionError("fake getid observed non-snapshot source bytes")
            if output == "/dev/null":
                ways, relations = 2, 0
                overwrite, fsync = True, False
                expected_ids = b"w10\nw11\n"
            else:
                ways, relations = 2, 1
                overwrite, fsync = False, True
                expected_ids = b"w10\nw11\nr20\n"
                path = _windows_from_wsl(output)
                with path.open("xb") as stream:
                    stream.write(self.output_bytes)
                    stream.flush()
                    os.fsync(stream.fileno())
                if self.mutate_admitted_ids:
                    id_index = len(argv) - 1 - argv[::-1].index("-i")
                    try:
                        _windows_from_wsl(argv[id_index + 1]).write_bytes(
                            b"w10\nw12\nr20\n"
                        )
                    except OSError:
                        self.admitted_id_mutation_denied = True
            id_index = len(argv) - 1 - argv[::-1].index("-i")
            if _windows_from_wsl(argv[id_index + 1]).read_bytes() != expected_ids:
                raise AssertionError("fake getid observed noncanonical ID bytes")
            return ProcessEvidence(
                argv,
                0,
                b"",
                _getid_stderr(
                    source=self.snapshot.source_wsl_path,
                    output=output,
                    node_count=0,
                    way_count=ways,
                    relation_count=relations,
                    overwrite=overwrite,
                    fsync=fsync,
                ),
            )
        if "check-refs" in argv:
            input_path = argv[-1]
            if self.mutate_on_check_refs:
                try:
                    _windows_from_wsl(input_path).write_bytes(
                        b"changed during check-refs"
                    )
                except OSError:
                    self.check_refs_mutation_denied = True
            if _windows_from_wsl(input_path).read_bytes() != self.output_bytes:
                raise AssertionError("fake check-refs observed alternate output bytes")
            return ProcessEvidence(
                argv,
                0,
                b"",
                _check_refs_stderr(input_path, nodes=3, ways=2, relations=1),
            )
        raise AssertionError(f"unexpected fake command: {argv!r}")


class HandlePublicationTests(unittest.TestCase):
    def test_initial_parent_lock_return_interrupt_releases_receipted_handle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.osm.pbf"
            source.write_bytes(b"fixture")
            destination = root / "admitted.osm.pbf"
            snapshot = _AdmissionSnapshot(
                source_path=source.resolve(strict=True),
                source_wsl_path="/mnt/c/fixture/source.osm.pbf",
                source_bytes=len(b"fixture"),
                source_sha256=hashlib.sha256(b"fixture").hexdigest(),
                candidate_pbf_bytes=1,
                candidate_pbf_sha256="1" * 64,
                candidate_xml=b"<osm version='0.6'/>",
                candidate_xml_sha256=hashlib.sha256(
                    b"<osm version='0.6'/>"
                ).hexdigest(),
                selector_sha256="2" * 64,
                policy_sha256="3" * 64,
                runtime_identity_sha256="4" * 64,
            )
            expectations = _AdmissionExpectations(1, 1, 1, 1, "5" * 64)
            reacquire = admission_probe._DirectoryIdentityLock.reacquire
            interrupted = False

            def reacquire_then_interrupt(lock: object) -> None:
                nonlocal interrupted
                reacquire(lock)
                if not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe._DirectoryIdentityLock,
                "reacquire",
                new=reacquire_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=lambda _argv: (_ for _ in ()).throw(
                            AssertionError("runner must not execute")
                        ),
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            displaced = root.with_name(root.name + "-renamed")
            root.rename(displaced)
            displaced.rename(root)

    def test_parent_lock_receipts_fd_before_interruptible_validation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            displaced = root.with_name(root.name + "-renamed")
            expected = admission_probe._component_snapshot(root)
            lock = admission_probe._DirectoryIdentityLock(root, expected)
            component_snapshot = admission_probe._component_snapshot
            interrupted = False

            def interrupt_validation(path: Path):
                nonlocal interrupted
                if path == root and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                return component_snapshot(path)

            with mock.patch.object(
                admission_probe,
                "_component_snapshot",
                side_effect=interrupt_validation,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    lock.reacquire()
            self.assertTrue(interrupted)
            self.assertIsNone(lock.fd)
            root.rename(displaced)
            displaced.rename(root)

    def test_file_rename_info_buffer_is_exact_absolute_no_replace_shape(self) -> None:
        destination = Path(r"C:\fixture\admitted.osm.pbf")
        encoded = str(destination).encode("utf-16-le")

        raw = bytes(admission_probe._file_rename_info_buffer(destination))

        self.assertEqual(raw[:16], b"\x00" * 16)
        self.assertEqual(int.from_bytes(raw[16:20], "little"), len(encoded))
        self.assertEqual(raw[20:], encoded + b"\x00\x00")

    def test_handle_rename_refuses_destination_created_after_precheck(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / ".owned.admission-staging"
            staging.mkdir()
            source = staging / "admitted.osm.pbf"
            destination = root / "admitted.osm.pbf"
            source.write_bytes(b"transaction owned")
            guard_fd = admission_probe._open_windows_delete_fd(
                source,
                label="test strict publication guard",
                directory=False,
                read_data=True,
            )
            build_buffer = admission_probe._file_rename_info_buffer
            competitor_created = False

            def create_competitor(path: Path) -> ctypes.Array:
                nonlocal competitor_created
                buffer = build_buffer(path)
                destination.write_bytes(b"competitor owned")
                competitor_created = True
                return buffer

            try:
                with mock.patch.object(
                    admission_probe,
                    "_file_rename_info_buffer",
                    side_effect=create_competitor,
                ):
                    with self.assertRaisesRegex(
                        AdmissionProbeError,
                        "no-replace publication failed",
                    ):
                        admission_probe._publish_no_replace(
                            source,
                            destination,
                            guard_fd=guard_fd,
                        )
            finally:
                os.close(guard_fd)
            self.assertTrue(competitor_created)
            self.assertEqual(source.read_bytes(), b"transaction owned")
            self.assertEqual(destination.read_bytes(), b"competitor owned")


class ExactArgvBuilderTests(unittest.TestCase):
    _RUNTIME_ROOT = "/home/haquilus/flightalert-exp8-tools/osmium-1.11.1/root"
    _BASE = (
        r"C:\Windows\System32\wsl.exe",
        "-d",
        "Ubuntu",
        "--",
        "/usr/bin/env",
        "-i",
        "LC_ALL=C.UTF-8",
        "LANG=C.UTF-8",
        "LANGUAGE=C",
    )
    _RUNTIME = (
        f"LD_LIBRARY_PATH={_RUNTIME_ROOT}/usr/lib/x86_64-linux-gnu",
        f"{_RUNTIME_ROOT}/usr/bin/osmium",
    )

    def test_direct_getid_argv_is_exact(self) -> None:
        source = "/mnt/e/fixture/source.osm.pbf"
        id_file = "/mnt/c/staging/direct-way-ids.txt"

        self.assertEqual(
            admission_probe._getid_argv(
                source_wsl_path=source,
                output_wsl_path="/dev/null",
                id_file_wsl_path=id_file,
                overwrite=True,
                fsync=False,
            ),
            self._BASE
            + self._RUNTIME
            + (
                "getid",
                "--no-progress",
                "--verbose",
                "-r",
                "--generator",
                "flight-alert-exp8-osm-admission-v1",
                "-f",
                "pbf",
                "-O",
                "-o",
                "/dev/null",
                source,
                "-i",
                id_file,
            ),
        )

    def test_admitted_getid_argv_is_exact(self) -> None:
        source = "/mnt/e/fixture/source.osm.pbf"
        output = "/mnt/c/staging/admitted.osm.pbf"
        id_file = "/mnt/c/staging/admitted-ids.txt"

        self.assertEqual(
            admission_probe._getid_argv(
                source_wsl_path=source,
                output_wsl_path=output,
                id_file_wsl_path=id_file,
                overwrite=False,
                fsync=True,
            ),
            self._BASE
            + self._RUNTIME
            + (
                "getid",
                "--no-progress",
                "--verbose",
                "-r",
                "--generator",
                "flight-alert-exp8-osm-admission-v1",
                "-f",
                "pbf",
                "--fsync",
                "-o",
                output,
                source,
                "-i",
                id_file,
            ),
        )

    def test_check_refs_argv_is_exact(self) -> None:
        output = "/mnt/c/staging/admitted.osm.pbf"

        self.assertEqual(
            admission_probe._check_refs_argv(output),
            self._BASE
            + self._RUNTIME
            + (
                "check-refs",
                "--no-progress",
                "--verbose",
                "-r",
                "-F",
                "pbf",
                output,
            ),
        )


class AdmissionTransactionTests(unittest.TestCase):
    def _fixture(
        self, root: Path
    ) -> tuple[_AdmissionSnapshot, _AdmissionExpectations, _FakeRunner]:
        source = root / "source.osm.pbf"
        source.write_bytes(b"bounded source fixture")
        source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
        root_ids = encode_canonical_id_file((10, 11), (20, 21))
        snapshot = _AdmissionSnapshot(
            source_path=source.resolve(strict=True),
            source_wsl_path=(
                "/mnt/"
                + source.drive[0].lower()
                + "/"
                + source.resolve(strict=True).as_posix()[3:]
            ),
            source_bytes=source.stat().st_size,
            source_sha256=source_hash,
            candidate_pbf_bytes=17,
            candidate_pbf_sha256="1" * 64,
            candidate_xml=_FIXTURE_XML,
            candidate_xml_sha256=hashlib.sha256(_FIXTURE_XML).hexdigest(),
            selector_sha256="2" * 64,
            policy_sha256="3" * 64,
            runtime_identity_sha256="4" * 64,
        )
        expectations = _AdmissionExpectations(
            selected_way_count=2,
            selected_relation_count=2,
            complete_relation_count=1,
            root_ids_bytes=len(root_ids),
            root_ids_sha256=hashlib.sha256(root_ids).hexdigest(),
        )
        return snapshot, expectations, _FakeRunner(snapshot)

    def test_bounded_transaction_publishes_only_after_all_proofs(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            result = _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=runner,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )

            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertEqual(result.destination, destination.resolve(strict=True))
            self.assertEqual(result.output_bytes, len(runner.output_bytes))
            self.assertEqual(result.direct_way_id_file_bytes, b"w10\nw11\n")
            self.assertEqual(
                result.admitted_id_file_bytes,
                b"w10\nw11\nr20\n",
            )
            self.assertEqual(
                result.output_sha256,
                hashlib.sha256(runner.output_bytes).hexdigest(),
            )
            self.assertEqual(len(result.raw_processes), 17)
            self.assertEqual(
                [item.label for item in result.raw_processes[-5:]],
                [
                    "admission/pre-hash",
                    "admission/direct-ways-getid",
                    "admission/admitted-getid",
                    "admission/check-refs",
                    "admission/post-hash",
                ],
            )
            semantic = json.loads(result.semantic_evidence)
            self.assertEqual(semantic["counts"]["admittedWays"], 2)
            self.assertEqual(semantic["counts"]["admittedRelations"], 1)
            self.assertEqual(semantic["counts"]["sourceIncompleteRelations"], 1)
            self.assertEqual(
                semantic["checkRefs"]["missingReferences"],
                {"changesets": 0, "nodes": 0, "relations": 0, "ways": 0},
            )
            self.assertNotIn(str(root).encode(), result.semantic_evidence)
            self.assertNotIn(b"stdout", result.semantic_evidence)
            self.assertFalse(any(root.glob(".*.admission-staging")))

            self.assertEqual(len(runner.calls), 5)
            self.assertIn("/usr/bin/sha256sum", runner.calls[0])
            direct, admitted, check_refs = runner.calls[1:4]
            self.assertIn("getid", direct)
            self.assertIn("-r", direct)
            self.assertIn("-O", direct)
            self.assertNotIn("--fsync", direct)
            self.assertEqual(direct[direct.index("-o") + 1], "/dev/null")
            direct_id_index = len(direct) - 1 - direct[::-1].index("-i")
            self.assertTrue(direct[direct_id_index + 1].endswith("direct-way-ids.txt"))
            self.assertEqual(direct[direct_id_index - 1], snapshot.source_wsl_path)
            self.assertIn("getid", admitted)
            self.assertIn("-r", admitted)
            self.assertIn("--fsync", admitted)
            self.assertNotIn("-O", admitted)
            self.assertTrue(admitted[admitted.index("-o") + 1].endswith("admitted.osm.pbf"))
            admitted_id_index = len(admitted) - 1 - admitted[::-1].index("-i")
            self.assertTrue(admitted[admitted_id_index + 1].endswith("admitted-ids.txt"))
            self.assertIn("check-refs", check_refs)
            self.assertIn("-r", check_refs)
            self.assertEqual(check_refs[-3:], ("-F", "pbf", check_refs[-1]))
            for argv in runner.calls:
                self.assertNotIn("sh", argv)
                self.assertNotIn("bash", argv)
                self.assertNotIn("-c", argv)

    def test_existing_destination_is_fatal_before_process_execution(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            destination.write_bytes(b"owned by caller")
            with self.assertRaisesRegex(AdmissionProbeError, "already exists"):
                _execute_admission(
                    snapshot=snapshot,
                    destination=destination,
                    expectations=expectations,
                    runner=runner,
                    relation_gate=lambda _dataset, _roots: _relation_outcome(),
                    reverify=lambda: snapshot,
                )
            self.assertEqual(destination.read_bytes(), b"owned by caller")
            self.assertEqual(runner.calls, [])

    def test_post_hash_output_write_is_denied_while_attested(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            runner.mutate_on_post_hash = True
            destination = root / "admitted.osm.pbf"
            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=runner,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(runner.post_hash_output_mutation_denied)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_check_refs_output_write_is_denied_while_consumed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            runner.mutate_on_check_refs = True
            destination = root / "admitted.osm.pbf"
            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=runner,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(runner.check_refs_mutation_denied)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_check_refs_output_cannot_be_transiently_swapped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            swap_was_denied = False
            swap_succeeded = False

            def attempt_check_refs_swap(argv: tuple[str, ...]) -> ProcessEvidence:
                nonlocal swap_was_denied, swap_succeeded
                if "check-refs" in argv:
                    output = _windows_from_wsl(argv[-1])
                    displaced = output.with_name("admitted.displaced.osm.pbf")
                    try:
                        output.rename(displaced)
                    except OSError:
                        swap_was_denied = True
                    else:
                        swap_succeeded = True
                        output.write_bytes(b"alternate closure-valid PBF")
                        try:
                            return runner(argv)
                        finally:
                            output.unlink()
                            displaced.rename(output)
                return runner(argv)

            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=attempt_check_refs_swap,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(swap_was_denied)
            self.assertFalse(swap_succeeded)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)

    def test_reverification_drift_is_fatal_and_leaves_no_partial(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            changed = replace(snapshot, selector_sha256="f" * 64)
            with self.assertRaisesRegex(AdmissionProbeError, "locks changed"):
                _execute_admission(
                    snapshot=snapshot,
                    destination=destination,
                    expectations=expectations,
                    runner=runner,
                    relation_gate=lambda _dataset, _roots: _relation_outcome(),
                    reverify=lambda: changed,
                )
            self.assertFalse(destination.exists())

    def test_id_file_write_is_denied_while_getid_consumes_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            runner.mutate_admitted_ids = True
            destination = root / "admitted.osm.pbf"
            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=runner,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(runner.admitted_id_mutation_denied)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_admitted_id_file_cannot_be_transiently_swapped_during_getid(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            swap_was_denied = False
            swap_succeeded = False

            def attempt_id_swap(argv: tuple[str, ...]) -> ProcessEvidence:
                nonlocal swap_was_denied, swap_succeeded
                if "getid" in argv and argv[argv.index("-o") + 1] != "/dev/null":
                    id_index = len(argv) - 1 - argv[::-1].index("-i")
                    id_path = _windows_from_wsl(argv[id_index + 1])
                    displaced = id_path.with_name("admitted-ids.displaced")
                    try:
                        id_path.rename(displaced)
                    except OSError:
                        swap_was_denied = True
                    else:
                        swap_succeeded = True
                        id_path.write_bytes(b"w10\nw12\nr20\n")
                        try:
                            return runner(argv)
                        finally:
                            id_path.unlink()
                            displaced.rename(id_path)
                return runner(argv)

            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=attempt_id_swap,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(swap_was_denied)
            self.assertFalse(swap_succeeded)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)

    def test_source_cannot_be_transiently_swapped_during_getid(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            outer = Path(directory)
            source_root = outer / "source"
            destination_root = outer / "destination"
            source_root.mkdir()
            destination_root.mkdir()
            snapshot, expectations, runner = self._fixture(source_root)
            destination = destination_root / "admitted.osm.pbf"
            source_path = snapshot.source_path
            displaced = source_root / "source.displaced.osm.pbf"
            swap_was_denied = False
            swap_succeeded = False

            def attempt_source_swap(argv: tuple[str, ...]) -> ProcessEvidence:
                nonlocal swap_was_denied, swap_succeeded
                if "getid" in argv and argv[argv.index("-o") + 1] != "/dev/null":
                    try:
                        source_path.rename(displaced)
                    except OSError:
                        swap_was_denied = True
                    else:
                        swap_succeeded = True
                        source_path.write_bytes(b"forged source during getid")
                        try:
                            return runner(argv)
                        finally:
                            source_path.unlink()
                            displaced.rename(source_path)
                return runner(argv)

            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=attempt_source_swap,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(swap_was_denied)
            self.assertFalse(swap_succeeded)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)

    def test_post_publication_readback_failure_rolls_back_exact_destination(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            stable_file = admission_probe._stable_guarded_file

            def corrupt_readback(fd: int, path: Path, **arguments: object):
                result = stable_file(fd, path, **arguments)
                if arguments.get("label") == "published admitted output":
                    return replace(result, sha256="f" * 64)
                return result

            with mock.patch.object(
                admission_probe,
                "_stable_guarded_file",
                side_effect=corrupt_readback,
            ):
                with self.assertRaisesRegex(AdmissionProbeError, "published output"):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_at_guarded_rollback_mark_retries_exact_path_rollback(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            stable_file = admission_probe._stable_guarded_file
            mark_for_delete = admission_probe._mark_windows_fd_for_delete
            interrupted = False

            def fail_published_readback(fd: int, path: Path, **arguments: object):
                result = stable_file(fd, path, **arguments)
                if arguments.get("label") == "published admitted output":
                    raise AdmissionProbeError("forced published output readback failure")
                return result

            def interrupt_guarded_mark(fd: int, *, label: str) -> None:
                nonlocal interrupted
                if label == "guarded admitted output" and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                mark_for_delete(fd, label=label)

            with mock.patch.object(
                admission_probe,
                "_stable_guarded_file",
                side_effect=fail_published_readback,
            ), mock.patch.object(
                admission_probe,
                "_mark_windows_fd_for_delete",
                side_effect=interrupt_guarded_mark,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_guarded_rollback_mark_error_still_closes_and_deletes_exact_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            stable_file = admission_probe._stable_guarded_file
            mark_for_delete = admission_probe._mark_windows_fd_for_delete
            failed_once = False

            def fail_published_readback(fd: int, path: Path, **arguments: object):
                result = stable_file(fd, path, **arguments)
                if arguments.get("label") == "published admitted output":
                    raise AdmissionProbeError("forced published output readback failure")
                return result

            def fail_first_guarded_mark(fd: int, *, label: str) -> None:
                nonlocal failed_once
                if label == "guarded admitted output" and not failed_once:
                    failed_once = True
                    raise AdmissionProbeError("forced guarded delete mark failure")
                mark_for_delete(fd, label=label)

            with mock.patch.object(
                admission_probe,
                "_stable_guarded_file",
                side_effect=fail_published_readback,
            ), mock.patch.object(
                admission_probe,
                "_mark_windows_fd_for_delete",
                side_effect=fail_first_guarded_mark,
            ):
                with self.assertRaisesRegex(
                    AdmissionProbeError,
                    "guarded delete mark failure",
                ):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(failed_once)
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_immediately_after_move_rolls_back_published_destination(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            publish = admission_probe._publish_no_replace

            def interrupt_after_move(
                source: Path, target: Path, *, guard_fd: int
            ) -> None:
                publish(source, target, guard_fd=guard_fd)
                raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe,
                "_publish_no_replace",
                side_effect=interrupt_after_move,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_after_id_file_writes_cleans_owned_staging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            stable_file = admission_probe._stable_file

            def interrupt_first_id_read(path: Path, **arguments: object):
                if arguments.get("label") == "direct-way ID file":
                    raise KeyboardInterrupt
                return stable_file(path, **arguments)

            with mock.patch.object(
                admission_probe,
                "_stable_file",
                side_effect=interrupt_first_id_read,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_after_staging_mkdir_cleans_the_receipted_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            mkdir = os.mkdir
            created: Path | None = None

            def create_staging_then_interrupt(
                path: str | bytes | os.PathLike[str] | os.PathLike[bytes],
                *arguments: object,
                **keywords: object,
            ) -> None:
                nonlocal created
                mkdir(path, *arguments, **keywords)
                candidate = Path(path)
                if candidate.name.endswith(".admission-staging") and created is None:
                    created = candidate
                    raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe.os,
                "mkdir",
                side_effect=create_staging_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertIsNotNone(created)
            assert created is not None
            self.assertFalse(created.exists())
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_after_exclusive_id_create_cleans_the_planned_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            path_open = Path.open
            created: Path | None = None

            def create_id_then_interrupt(
                path: Path, *arguments: object, **keywords: object
            ):
                nonlocal created
                if path.name == "direct-way-ids.txt" and created is None:
                    stream = path_open(path, *arguments, **keywords)
                    stream.close()
                    created = path
                    raise KeyboardInterrupt
                return path_open(path, *arguments, **keywords)

            with mock.patch.object(Path, "open", new=create_id_then_interrupt):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertIsNotNone(created)
            assert created is not None
            self.assertFalse(created.exists())
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_before_output_identity_receipt_cleans_planned_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            stable_file = admission_probe._stable_file
            interrupted = False

            def interrupt_unverified_output(path: Path, **arguments: object):
                nonlocal interrupted
                if (
                    arguments.get("label")
                    == "unverified admitted getid staging output"
                    and not interrupted
                ):
                    interrupted = True
                    raise KeyboardInterrupt
                return stable_file(path, **arguments)

            with mock.patch.object(
                admission_probe,
                "_stable_file",
                side_effect=interrupt_unverified_output,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_after_getid_output_creation_cleans_owned_staging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"

            def interrupt_after_admitted_getid(
                argv: tuple[str, ...],
            ) -> ProcessEvidence:
                result = runner(argv)
                if "getid" in argv and argv[argv.index("-o") + 1] != "/dev/null":
                    raise KeyboardInterrupt
                return result

            with self.assertRaises(KeyboardInterrupt):
                _execute_admission(
                    snapshot=snapshot,
                    destination=destination,
                    expectations=expectations,
                    runner=interrupt_after_admitted_getid,
                    relation_gate=lambda _dataset, _roots: _relation_outcome(),
                    reverify=lambda: snapshot,
                )
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_after_staging_cleanup_rolls_back_published_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            cleanup = admission_probe._cleanup_staging

            def cleanup_then_interrupt(*args: object, **kwargs: object) -> None:
                cleanup(*args, **kwargs)
                raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe,
                "_cleanup_staging",
                side_effect=cleanup_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_while_releasing_guards_rolls_back_published_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            close = os.close
            interrupted = False

            def close_then_interrupt(fd: int) -> None:
                nonlocal interrupted
                close(fd)
                if destination.exists() and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=close_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())

    def test_interrupt_after_final_commit_close_returns_committed_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            publication_fd: int | None = None
            interrupted = False

            def remember_publication_handle(*args: object, **kwargs: object) -> int:
                nonlocal publication_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "admitted output publication guard":
                    publication_fd = fd
                return fd

            def close_publication_then_interrupt(fd: int) -> None:
                nonlocal interrupted
                close(fd)
                if fd == publication_fd and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_publication_handle,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=close_publication_then_interrupt,
            ):
                result = _execute_admission(
                    snapshot=snapshot,
                    destination=destination,
                    expectations=expectations,
                    runner=runner,
                    relation_gate=lambda _dataset, _roots: _relation_outcome(),
                    reverify=lambda: snapshot,
                )
            self.assertTrue(interrupted)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertEqual(result.destination, destination.resolve(strict=True))

    def test_interrupt_before_final_commit_close_rolls_back_by_retained_handle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            publication_fd: int | None = None
            interrupted = False

            def remember_publication_handle(*args: object, **kwargs: object) -> int:
                nonlocal publication_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "admitted output publication guard":
                    publication_fd = fd
                return fd

            def interrupt_before_publication_close(fd: int) -> None:
                nonlocal interrupted
                if fd == publication_fd and destination.exists() and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                close(fd)

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_publication_handle,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=interrupt_before_publication_close,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())

    def test_interrupt_before_each_handoff_close_keeps_guard_receipted(self) -> None:
        for selected_label in (
            "check-refs guard handoff",
            "publication handoff read barrier",
        ):
            with self.subTest(label=selected_label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                snapshot, expectations, runner = self._fixture(root)
                destination = root / "admitted.osm.pbf"
                close_exact = admission_probe._close_fd_with_single_retry
                interrupted = False

                def interrupt_selected_close(
                    fd: int,
                    identity: object,
                    *,
                    label: str,
                ):
                    nonlocal interrupted
                    if label == selected_label and not interrupted:
                        interrupted = True
                        raise KeyboardInterrupt
                    return close_exact(fd, identity, label=label)

                with mock.patch.object(
                    admission_probe,
                    "_close_fd_with_single_retry",
                    side_effect=interrupt_selected_close,
                ):
                    with self.assertRaises(KeyboardInterrupt):
                        _execute_admission(
                            snapshot=snapshot,
                            destination=destination,
                            expectations=expectations,
                            runner=runner,
                            relation_gate=lambda _dataset, _roots: _relation_outcome(),
                            reverify=lambda: snapshot,
                        )
                self.assertTrue(interrupted)
                self.assertFalse(destination.exists())
                self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_each_partial_publication_receipt_recovers_local_guard(self) -> None:
        for selected_field in ("fd", "identity", "path"):
            with self.subTest(field=selected_field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                snapshot, expectations, runner = self._fixture(root)
                destination = root / "admitted.osm.pbf"
                set_attribute = admission_probe._PublishedGuardReceipt.__setattr__
                interrupted = False

                def interrupt_selected_receipt(
                    receipt: object, name: str, value: object
                ) -> None:
                    nonlocal interrupted
                    if name == selected_field and value is not None and not interrupted:
                        interrupted = True
                        raise KeyboardInterrupt
                    set_attribute(receipt, name, value)

                with mock.patch.object(
                    admission_probe._PublishedGuardReceipt,
                    "__setattr__",
                    new=interrupt_selected_receipt,
                ):
                    with self.assertRaises(KeyboardInterrupt):
                        _execute_admission(
                            snapshot=snapshot,
                            destination=destination,
                            expectations=expectations,
                            runner=runner,
                            relation_gate=lambda _dataset, _roots: _relation_outcome(),
                            reverify=lambda: snapshot,
                        )
                self.assertTrue(interrupted)
                self.assertFalse(destination.exists())
                self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_before_inner_guard_close_retries_and_cleans_staging(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            way_guard_fd: int | None = None
            interrupted = False

            def remember_way_guard(*args: object, **kwargs: object) -> int:
                nonlocal way_guard_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "direct-way ID consumption lock":
                    way_guard_fd = fd
                return fd

            def interrupt_before_way_close(fd: int) -> None:
                nonlocal interrupted
                if fd == way_guard_fd and destination.exists() and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                close(fd)

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_way_guard,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=interrupt_before_way_close,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_interrupt_releasing_source_guard_rolls_back_committed_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            source_fd: int | None = None
            interrupted = False

            def remember_source_handle(*args: object, **kwargs: object) -> int:
                nonlocal source_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "admission source identity lock":
                    source_fd = fd
                return fd

            def close_source_then_interrupt(fd: int) -> None:
                nonlocal interrupted
                close(fd)
                if fd == source_fd and destination.exists() and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_source_handle,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=close_source_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())

    def test_interrupt_releasing_parent_lock_rolls_back_committed_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            parent_fd: int | None = None
            interrupted = False

            def remember_parent_handle(*args: object, **kwargs: object) -> int:
                nonlocal parent_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "destination parent identity lock":
                    parent_fd = fd
                return fd

            def close_parent_then_interrupt(fd: int) -> None:
                nonlocal interrupted
                close(fd)
                if fd == parent_fd and destination.exists() and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_parent_handle,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=close_parent_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    _execute_admission(
                        snapshot=snapshot,
                        destination=destination,
                        expectations=expectations,
                        runner=runner,
                        relation_gate=lambda _dataset, _roots: _relation_outcome(),
                        reverify=lambda: snapshot,
                    )
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())

    def test_rollback_never_uses_check_then_path_unlink(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            published = root / "published.osm.pbf"
            displaced = root / "owned-displaced.osm.pbf"
            published.write_bytes(b"transaction owned")
            expected = admission_probe._identity(os.lstat(published))
            original_unlink = Path.unlink
            swapped = False

            def swap_before_unlink(path: Path, *args: object, **kwargs: object) -> None:
                nonlocal swapped
                if path == published:
                    published.rename(displaced)
                    published.write_bytes(b"attacker replacement")
                    swapped = True
                original_unlink(path, *args, **kwargs)

            with mock.patch.object(Path, "unlink", new=swap_before_unlink):
                admission_probe._rollback_published(published, expected)
            self.assertFalse(swapped)
            self.assertFalse(published.exists())
            self.assertFalse(displaced.exists())

    def test_interrupt_at_exact_path_rollback_mark_retries_once(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            published = root / "published.osm.pbf"
            published.write_bytes(b"transaction owned")
            expected = admission_probe._identity(os.lstat(published))
            mark_for_delete = admission_probe._mark_windows_fd_for_delete
            interrupted = False

            def interrupt_first_mark(fd: int, *, label: str) -> None:
                nonlocal interrupted
                if label == "published rollback destination" and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                mark_for_delete(fd, label=label)

            with mock.patch.object(
                admission_probe,
                "_mark_windows_fd_for_delete",
                side_effect=interrupt_first_mark,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    admission_probe._rollback_published(published, expected)
            self.assertTrue(interrupted)
            self.assertFalse(published.exists())

    def test_interrupt_before_exact_rollback_close_retries_and_deletes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            published = root / "published.osm.pbf"
            published.write_bytes(b"transaction owned")
            expected = admission_probe._identity(os.lstat(published))
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            rollback_fd: int | None = None
            interrupted = False

            def remember_rollback_fd(*args: object, **kwargs: object) -> int:
                nonlocal rollback_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "published rollback destination":
                    rollback_fd = fd
                return fd

            def interrupt_before_rollback_close(fd: int) -> None:
                nonlocal interrupted
                if fd == rollback_fd and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                close(fd)

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_rollback_fd,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=interrupt_before_rollback_close,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    admission_probe._rollback_published(published, expected)
            self.assertTrue(interrupted)
            self.assertFalse(published.exists())

    def test_rollback_refuses_an_attacker_replacement_without_deleting_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            published = root / "published.osm.pbf"
            displaced = root / "owned-displaced.osm.pbf"
            published.write_bytes(b"transaction owned")
            expected = admission_probe._identity(os.lstat(published))
            published.rename(displaced)
            published.write_bytes(b"attacker replacement")

            with self.assertRaisesRegex(AdmissionProbeError, "replaced/reparse"):
                admission_probe._rollback_published(published, expected)
            self.assertEqual(published.read_bytes(), b"attacker replacement")
            self.assertEqual(displaced.read_bytes(), b"transaction owned")

    def test_interrupt_before_staging_directory_close_retries_exact_delete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / ".owned.admission-staging"
            staging.mkdir()
            parent_components = admission_probe._component_snapshot(root)
            staging_identity = admission_probe._component_snapshot(staging)[-1][1]
            open_handle = admission_probe._open_windows_delete_fd
            close = os.close
            directory_fd: int | None = None
            interrupted = False

            def remember_directory_fd(*args: object, **kwargs: object) -> int:
                nonlocal directory_fd
                fd = open_handle(*args, **kwargs)
                if kwargs.get("label") == "staging directory":
                    directory_fd = fd
                return fd

            def interrupt_before_directory_close(fd: int) -> None:
                nonlocal interrupted
                if fd == directory_fd and not interrupted:
                    interrupted = True
                    raise KeyboardInterrupt
                close(fd)

            with mock.patch.object(
                admission_probe,
                "_open_windows_delete_fd",
                side_effect=remember_directory_fd,
            ), mock.patch.object(
                admission_probe.os,
                "close",
                side_effect=interrupt_before_directory_close,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    admission_probe._cleanup_staging(
                        staging,
                        root,
                        parent_components,
                        staging_identity,
                        {},
                    )
            self.assertTrue(interrupted)
            self.assertFalse(staging.exists())

    def test_staging_cleanup_never_uses_check_then_recursive_path_delete(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / ".owned.admission-staging"
            displaced = root / ".owned-displaced.admission-staging"
            staging.mkdir()
            owned = staging / "owned.bin"
            owned.write_bytes(b"owned")
            parent_components = admission_probe._component_snapshot(root)
            staging_identity = admission_probe._component_snapshot(staging)[-1][1]
            expected_files = {"owned.bin": admission_probe._identity(os.lstat(owned))}
            original_rmtree = shutil.rmtree
            swapped = False

            def swap_before_rmtree(
                path: str | os.PathLike[str],
                *args: object,
                **kwargs: object,
            ) -> None:
                nonlocal swapped
                candidate = Path(path)
                if candidate == staging:
                    staging.rename(displaced)
                    staging.mkdir()
                    (staging / "replacement.bin").write_bytes(b"replacement")
                    swapped = True
                original_rmtree(path, *args, **kwargs)

            with mock.patch.object(shutil, "rmtree", side_effect=swap_before_rmtree):
                admission_probe._cleanup_staging(
                    staging,
                    root,
                    parent_components,
                    staging_identity,
                    expected_files,
                )
            self.assertFalse(swapped)
            self.assertFalse(staging.exists())
            self.assertFalse(displaced.exists())

    def test_staging_cleanup_refuses_an_attacker_replacement(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / ".owned.admission-staging"
            displaced = root / ".owned-displaced.admission-staging"
            staging.mkdir()
            owned = staging / "owned.bin"
            owned.write_bytes(b"owned")
            parent_components = admission_probe._component_snapshot(root)
            staging_identity = admission_probe._component_snapshot(staging)[-1][1]
            expected_files = {"owned.bin": admission_probe._identity(os.lstat(owned))}
            staging.rename(displaced)
            staging.mkdir()
            replacement = staging / "replacement.bin"
            replacement.write_bytes(b"replacement")

            with self.assertRaisesRegex(AdmissionProbeError, "replaced/reparse"):
                admission_probe._cleanup_staging(
                    staging,
                    root,
                    parent_components,
                    staging_identity,
                    expected_files,
                )
            self.assertEqual(replacement.read_bytes(), b"replacement")
            self.assertEqual((displaced / "owned.bin").read_bytes(), b"owned")

    def test_destination_parent_identity_is_locked_for_the_whole_admission(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            outer = Path(directory)
            root = outer / "destination-parent"
            displaced = outer / "destination-parent-displaced"
            root.mkdir()
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            rename_was_denied = False
            rename_succeeded = False

            def attempt_parent_swap(argv: tuple[str, ...]) -> ProcessEvidence:
                nonlocal rename_was_denied, rename_succeeded
                if not rename_was_denied and not rename_succeeded:
                    try:
                        root.rename(displaced)
                    except OSError:
                        rename_was_denied = True
                    else:
                        rename_succeeded = True
                        displaced.rename(root)
                return runner(argv)

            _execute_admission(
                snapshot=snapshot,
                destination=destination,
                expectations=expectations,
                runner=attempt_parent_swap,
                relation_gate=lambda _dataset, _roots: _relation_outcome(),
                reverify=lambda: snapshot,
            )
            self.assertTrue(rename_was_denied)
            self.assertFalse(rename_succeeded)

    def test_publication_guard_denies_parent_swap_during_move(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            outer = Path(directory)
            root = outer / "destination-parent"
            displaced = outer / "destination-parent-displaced"
            root.mkdir()
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            publish = admission_probe._publish_no_replace
            swap_was_denied = False

            def swap_parent_around_move(
                source: Path, target: Path, *, guard_fd: int
            ) -> None:
                nonlocal swap_was_denied
                try:
                    root.rename(displaced)
                except OSError:
                    swap_was_denied = True
                else:
                    displaced.rename(root)
                publish(source, target, guard_fd=guard_fd)

            with mock.patch.object(
                admission_probe,
                "_publish_no_replace",
                side_effect=swap_parent_around_move,
            ):
                _execute_admission(
                    snapshot=snapshot,
                    destination=destination,
                    expectations=expectations,
                    runner=runner,
                    relation_gate=lambda _dataset, _roots: _relation_outcome(),
                    reverify=lambda: snapshot,
                )
            self.assertTrue(swap_was_denied)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertFalse(any(root.glob(".*.admission-staging")))

    def test_strict_guard_denies_competing_reader_after_handle_rename(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot, expectations, runner = self._fixture(root)
            destination = root / "admitted.osm.pbf"
            publish = admission_probe._publish_no_replace
            competitor_was_denied = False
            competitor_acquired = False

            def publish_and_attempt_competitor(
                source: Path, target: Path, *, guard_fd: int
            ) -> None:
                nonlocal competitor_was_denied, competitor_acquired
                publish(source, target, guard_fd=guard_fd)
                try:
                    competing_fd = admission_probe._open_windows_delete_fd(
                        target,
                        label="competing read without delete sharing",
                        directory=False,
                        delete_access=False,
                        read_data=True,
                    )
                except AdmissionProbeError:
                    competitor_was_denied = True
                else:
                    competitor_acquired = True
                    os.close(competing_fd)

            with mock.patch.object(
                admission_probe,
                "_publish_no_replace",
                side_effect=publish_and_attempt_competitor,
            ):
                _execute_admission(
                    snapshot=snapshot,
                    destination=destination,
                    expectations=expectations,
                    runner=runner,
                    relation_gate=lambda _dataset, _roots: _relation_outcome(),
                    reverify=lambda: snapshot,
                )
            self.assertTrue(competitor_was_denied)
            self.assertFalse(competitor_acquired)
            self.assertEqual(destination.read_bytes(), runner.output_bytes)
            self.assertFalse(any(root.glob(".*.admission-staging")))


class PublicTrustBoundaryTests(unittest.TestCase):
    def test_maryland_admission_goldens_are_exact(self) -> None:
        self.assertEqual(
            (
                MARYLAND_SELECTED_WAY_COUNT,
                MARYLAND_SELECTED_RELATION_COUNT,
                MARYLAND_COMPLETE_RELATION_COUNT,
                MARYLAND_ROOT_IDS_BYTES,
                MARYLAND_ROOT_IDS_SHA256,
            ),
            (
                7_944,
                102,
                78,
                88_831,
                "3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7",
            ),
        )

    def test_provenance_root_goldens_must_match_admission_goldens(self) -> None:
        matching = SimpleNamespace(
            MARYLAND_ROOT_WAY_COUNT=7_944,
            MARYLAND_ROOT_RELATION_COUNT=102,
            MARYLAND_ROOT_IDS_BYTES=88_831,
            MARYLAND_ROOT_IDS_SHA256=MARYLAND_ROOT_IDS_SHA256,
        )
        _require_matching_provenance_goldens(matching)
        with self.assertRaisesRegex(AdmissionProbeError, "provenance.*golden"):
            _require_matching_provenance_goldens(
                SimpleNamespace(
                    **{
                        **matching.__dict__,
                        "MARYLAND_ROOT_RELATION_COUNT": 101,
                    }
                )
            )

    def test_stable_hash_rejects_path_component_identity_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.pbf"
            path.write_bytes(b"stable fixture")
            component_snapshot = admission_probe._component_snapshot
            stable_file = admission_probe._stable_file
            calls = 0

            def drifting_snapshot(value: Path):
                nonlocal calls
                calls += 1
                result = component_snapshot(value)
                if calls == 2:
                    last_path, identity = result[-1]
                    result = (
                        *result[:-1],
                        (last_path, replace(identity, inode=identity.inode + 1)),
                    )
                return result

            with mock.patch.object(
                admission_probe,
                "_component_snapshot",
                side_effect=drifting_snapshot,
            ):
                with self.assertRaisesRegex(AdmissionProbeError, "path components changed"):
                    stable_file(path.resolve(strict=True), label="fixture")

    def test_destination_rejects_parent_resolution_redirection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lexical = root / "lexical"
            redirected = root / "redirected"
            lexical.mkdir()
            redirected.mkdir()
            target = lexical / "admitted.osm.pbf"
            redirected_resolved = redirected.resolve(strict=True)
            resolve = Path.resolve

            def redirect_parent(path: Path, strict: bool = False) -> Path:
                if path == lexical:
                    return redirected_resolved
                return resolve(path, strict=strict)

            with mock.patch.object(Path, "resolve", new=redirect_parent):
                with self.assertRaisesRegex(
                    AdmissionProbeError,
                    "destination parent.*changed|redirected",
                ):
                    admission_probe._validated_destination(target)

    def test_result_constructor_cannot_mint_live_evidence(self) -> None:
        with self.assertRaisesRegex(AdmissionProbeError, "live admission"):
            MarylandAdmissionResult(
                destination=Path("C:/forged.osm.pbf"),
                output_bytes=1,
                output_sha256="0" * 64,
                direct_way_id_file_bytes=b"w1\n",
                admitted_id_file_bytes=b"w1\n",
                semantic_evidence=b"{}\n",
                raw_processes=(),
            )

    def test_runtime_identity_binds_python_dependencies_cache_and_flags(self) -> None:
        local = SimpleNamespace(
            policy_sha256="1" * 64,
            python_executable_sha256="2" * 64,
            python_implementation="CPython",
            python_platform="Windows",
            python_version="3.11.1",
            python_cache_tag="cpython-311",
            python_flags=(("optimize", 0), ("utf8_mode", False)),
            python_dependencies=(
                SimpleNamespace(
                    logical_name="runtime/python311.dll",
                    bytes=123,
                    sha256="3" * 64,
                ),
            ),
            selector_callable_code_sha256="4" * 64,
            selector_sha256="5" * 64,
        )
        wsl = SimpleNamespace(
            architecture="x86_64",
            boost_deb_sha256="6" * 64,
            boost_library_sha256="7" * 64,
            command_argv=(("wsl.exe", "--version"),),
            ubuntu_distribution="Ubuntu",
            kernel="kernel",
            ldd_inventory=(
                SimpleNamespace(
                    resolved_path="/lib/libc.so.6",
                    sha256="8" * 64,
                    soname="libc.so.6",
                ),
            ),
            libosmium_version="2.15.4",
            locale="C.UTF-8",
            osmium_binary_sha256="9" * 64,
            osmium_deb_sha256="a" * 64,
            osmium_version="1.11.1",
            ubuntu_release="Ubuntu 20.04.3 LTS",
            wsl_version=1,
        )
        document = _runtime_identity_document(local, wsl)
        self.assertEqual(document["local"]["pythonCacheTag"], "cpython-311")
        self.assertEqual(
            document["local"]["pythonFlags"],
            [["optimize", 0], ["utf8_mode", False]],
        )
        self.assertEqual(
            document["local"]["pythonDependencies"],
            [
                {
                    "bytes": 123,
                    "logicalName": "runtime/python311.dll",
                    "sha256": "3" * 64,
                }
            ],
        )

    def test_public_runner_has_no_runner_or_process_evidence_injection(self) -> None:
        signature = inspect.signature(run_maryland_admission)
        self.assertEqual(
            tuple(signature.parameters),
            ("source_path", "candidate_pbf_path", "candidate_xml_path", "destination"),
        )
        self.assertNotIn("ProcessEvidence", str(signature))
        self.assertIs(admission_probe._LIVE_PROCESS_RUNNER, run_bounded_process)
        self.assertIs(admission_probe._LIVE_EXECUTOR, admission_probe._execute_admission)
        self.assertIs(
            admission_probe._LIVE_RELATION_GATE,
            admission_probe._run_live_relation_gate,
        )
        self.assertIs(
            admission_probe._LIVE_VERIFY_SNAPSHOT,
            admission_probe._verify_live_snapshot,
        )
        with self.assertRaises(TypeError):
            run_maryland_admission(  # type: ignore[call-arg]
                "source",
                "candidate.pbf",
                "candidate.osm",
                "destination.osm.pbf",
                runner=lambda _argv: None,
            )


if __name__ == "__main__":
    unittest.main()
