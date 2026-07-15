from __future__ import annotations

import hashlib
import io
import json
import os
import runpy
import struct
import sys
import tempfile
import unittest
import warnings
import zlib
from pathlib import Path
from unittest.mock import Mock, patch
from contextlib import redirect_stderr, redirect_stdout


FIXTURE_DIRECTORY = Path(__file__).with_name("fixtures")
CLOSURE_FIXTURE = FIXTURE_DIRECTORY / "global-waterway-closure.opl"
ROOT_FIXTURE = FIXTURE_DIRECTORY / "global-waterway-root-ids.txt"


def _source_binding(opl: Path, roots: Path):
    from tools.experiment8.osm_global_waterway_package import WaterwaySourceBinding

    opl_raw = opl.read_bytes()
    root_raw = roots.read_bytes()
    return WaterwaySourceBinding(
        planet_path="fixture://planet.osm.pbf",
        planet_bytes=123,
        planet_sha256=hashlib.sha256(b"fixture planet").hexdigest(),
        selection_manifest_sha256=hashlib.sha256(b"fixture selection manifest").hexdigest(),
        selection_policy_sha256=(
            "7ddea49ea1501790519b6b47c2cd8170ce3043218551f1b978c98ffb35e7b50c"
        ),
        root_ids_bytes=len(root_raw),
        root_ids_sha256=hashlib.sha256(root_raw).hexdigest(),
        closure_pbf_bytes=456,
        closure_pbf_sha256=hashlib.sha256(b"fixture closure pbf").hexdigest(),
        closure_opl_bytes=len(opl_raw),
        closure_opl_sha256=hashlib.sha256(opl_raw).hexdigest(),
        extraction_receipt_sha256=hashlib.sha256(b"fixture extraction receipt").hexdigest(),
    )


def _present_payloads(package: Path):
    from tools.experiment8.model import TileKey
    from tools.experiment8.renderer_tile_package import (
        INDEX_ENTRY_BYTES,
        INDEX_FLAG_PRESENT,
        decode_tile_payload,
        raw_hash32,
    )

    manifest = json.loads((package / "manifest.json").read_text("utf-8"))
    index = (package / "tile-index.bin").read_bytes()
    records = (package / "records.fadictpack").read_bytes()
    ordinal = 0
    for window in manifest["coverage"]["zoomRanges"]:
        for y in range(window["yMin"], window["yMax"] + 1):
            for x in range(window["xMin"], window["xMax"] + 1):
                entry = index[ordinal * INDEX_ENTRY_BYTES : (ordinal + 1) * INDEX_ENTRY_BYTES]
                ordinal += 1
                offset, compressed_bytes, raw_bytes, expected_hash, flags = struct.unpack(
                    "<QIIII", entry
                )
                if flags == 0:
                    self_zero = (offset, compressed_bytes, raw_bytes, expected_hash)
                    if self_zero != (0, 0, 0, 0):
                        raise AssertionError("missing tile index entry is not canonical zero")
                    continue
                if flags != INDEX_FLAG_PRESENT:
                    raise AssertionError("fixture index has unsupported flags")
                compressed = records[offset : offset + compressed_bytes]
                payload = zlib.decompress(compressed, -15)
                if len(payload) != raw_bytes or raw_hash32(payload) != expected_hash:
                    raise AssertionError("fixture payload integrity differs")
                tile = TileKey(window["z"], x, y)
                yield tile, payload, decode_tile_payload(tile, payload)
    if ordinal * INDEX_ENTRY_BYTES != len(index):
        raise AssertionError("fixture index traversal did not consume every entry")


def _selection_manifest(planet_raw: bytes, root_raw: bytes) -> bytes:
    from tools.experiment8.osm_planet_selection import (
        GLOBAL_POLICY_SHA256,
        LIVE_NAME_ENVELOPE_OPL_BYTES,
        LIVE_NAME_ENVELOPE_OPL_SHA256,
        LIVE_NAME_ENVELOPE_PBF_BYTES,
        LIVE_NAME_ENVELOPE_PBF_SHA256,
        LIVE_NAME_ENVELOPE_PROFILE,
    )
    from tools.experiment8.osm_planet_selection_verifier import (
        LIVE_BROAD_ENVELOPE_OPL_BYTES,
        LIVE_BROAD_ENVELOPE_OPL_SHA256,
        LIVE_BROAD_ENVELOPE_PBF_BYTES,
        LIVE_BROAD_ENVELOPE_PBF_SHA256,
        LIVE_BROAD_ENVELOPE_PROFILE,
    )
    from tools.experiment8.run_osm_global_selection import canonical_json_bytes

    semantic = {
        "identities": {
            "inputs": {
                "broadOpl": {
                    "bytes": LIVE_BROAD_ENVELOPE_OPL_BYTES,
                    "sha256": LIVE_BROAD_ENVELOPE_OPL_SHA256,
                },
                "broadPbf": {
                    "bytes": LIVE_BROAD_ENVELOPE_PBF_BYTES,
                    "sha256": LIVE_BROAD_ENVELOPE_PBF_SHA256,
                },
                "planetPbf": {
                    "bytes": len(planet_raw),
                    "sha256": hashlib.sha256(planet_raw).hexdigest(),
                },
                "productionOpl": {
                    "bytes": LIVE_NAME_ENVELOPE_OPL_BYTES,
                    "sha256": LIVE_NAME_ENVELOPE_OPL_SHA256,
                },
                "productionPbf": {
                    "bytes": LIVE_NAME_ENVELOPE_PBF_BYTES,
                    "sha256": LIVE_NAME_ENVELOPE_PBF_SHA256,
                },
            },
            "policySha256": GLOBAL_POLICY_SHA256,
            "runnerSha256": "1" * 64,
            "runtimeSha256": "2" * 64,
            "selectorSha256": "3" * 64,
            "verifierSha256": "4" * 64,
        },
        "producer": {
            "artifacts": [
                {
                    "bytes": len(root_raw),
                    "path": "root-ids.txt",
                    "sha256": hashlib.sha256(root_raw).hexdigest(),
                }
            ],
            "resultSha256": "5" * 64,
            "schema": "flight-alert-exp8-osm-global-producer-readback-v1",
        },
        "profiles": {
            "selection": LIVE_NAME_ENVELOPE_PROFILE,
            "verification": LIVE_BROAD_ENVELOPE_PROFILE,
        },
        "runtime": {"fixture": True},
        "schema": "flight-alert-exp8-osm-global-semantic-manifest-v1",
        "verifier": {"resultSha256": "6" * 64},
    }
    return canonical_json_bytes(
        {
            "execution": {"attemptId": "fixture"},
            "schema": "flight-alert-exp8-osm-global-final-manifest-v1",
            "semantic": semantic,
            "semanticSha256": hashlib.sha256(canonical_json_bytes(semantic)).hexdigest(),
        }
    )


def _accepted_selection_run(run_directory: Path, manifest: Path, roots: Path):
    from tools.experiment8.osm_global_waterway_package import (
        CompletedGlobalSelectionRun,
    )

    fact = {"bytes": 1, "sha256": hashlib.sha256(b"x").hexdigest()}
    return CompletedGlobalSelectionRun(
        run_directory=run_directory,
        run_id=run_directory.name,
        attempt_id="fixture-attempt",
        selection_manifest_path=manifest,
        root_ids_path=roots,
        complete_marker=fact,
        verification_report=fact,
        verification_observation=fact,
    )


def _attested_osmium_runtime(pipeline):
    document = pipeline.pinned_runtime_document()
    document.update(
        {
            "attestationSchema": (
                "flightalert.experiment8.osm-global-waterway-osmium-attestation.v1"
            ),
            "dependencyFiles": [
                {
                    "path": document["boostLibraryPath"],
                    "sha256": document["boostLibrarySha256"],
                }
            ],
            "lddTranscript": {
                "bytes": 12,
                "sha256": hashlib.sha256(b"fixture ldd\n").hexdigest(),
            },
            "wslKernelTranscript": {
                "bytes": 15,
                "sha256": hashlib.sha256(b"fixture kernel\n").hexdigest(),
            },
            "pythonRuntime": pipeline.python_runtime_identity_document(),
        }
    )
    return document

def _timed(payload: str) -> str:
    return f"[ 0:00] {payload}"


def _getid_stderr(
    *, source: str, output: str, ways: int, relations: int, generator: str
) -> bytes:
    from tools.experiment8.osm_global_waterway_package import (
        PINNED_LIBOSMIUM_VERSION,
        PINNED_OSMIUM_VERSION,
    )

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
        _timed(f"    generator: {generator}"),
        _timed("    overwrite: no"),
        _timed("    fsync: yes"),
        _timed("  other options:"),
        _timed("    add referenced objects: yes"),
        _timed("    remove tags on non-matching objects: no"),
        _timed("    work with history files: no"),
        _timed("    default object type: node"),
        _timed(f"    looking for 0 node ID(s), {ways} way ID(s), and {relations} relation ID(s)"),
        _timed("Following references..."),
        _timed("  Reading input file to find relations in relations..."),
        _timed("  Reading input file to find nodes/ways in relations..."),
        _timed("  Reading input file to find nodes in ways..."),
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
    return ("\n".join(lines) + "\n").encode("utf-8")


def _check_refs_stderr(input_path: str, *, missing_nodes: int = 0) -> bytes:
    from tools.experiment8.osm_global_waterway_package import (
        PINNED_LIBOSMIUM_VERSION,
        PINNED_OSMIUM_VERSION,
    )

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
        "There are 12 nodes, 6 ways, and 1 relations in this file.",
        f"Nodes     in ways      missing: {missing_nodes}",
        "Nodes     in relations missing: 0",
        "Ways      in relations missing: 0",
        "Relations in relations missing: 0",
        _timed("Memory used for indexes: 1 MBytes"),
        _timed("Peak memory used: 18 MBytes"),
        _timed("Done."),
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


class GlobalWaterwaySourceContractTests(unittest.TestCase):
    def test_plan_binds_exact_global_sources_and_recursive_pinned_osmium(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        plan = pipeline.global_waterway_plan_document()

        self.assertEqual(
            "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f",
            plan["planet"]["sha256"],
        )
        self.assertEqual(93_653_630_756, plan["planet"]["bytes"])
        self.assertEqual(
            "7ddea49ea1501790519b6b47c2cd8170ce3043218551f1b978c98ffb35e7b50c",
            plan["selection"]["policySha256"],
        )
        self.assertEqual(
            "ffb68c03d8fa2710bfd664dfd4ce43c01cc2fdbbb92599b4d892bd3bc0661b4d",
            plan["selection"]["displayPbf"]["sha256"],
        )
        self.assertEqual(
            r"C:\FlightAlert-exp8-work\planet-260629\global-waterways\inputs\display-name-envelope-candidates.osmium-1.11.1.osm.pbf",
            plan["selection"]["displayPbf"]["path"],
        )
        self.assertEqual(
            "628622248814b1a83727cf19bd7e22cc4ad66b61589c6f137585fd555910785b",
            plan["selection"]["displayOpl"]["sha256"],
        )
        self.assertEqual(
            "0cb9c478ce621eedf4a889b72285da49822b48961212913bb95117be76757381",
            plan["selection"]["broadPbf"]["sha256"],
        )
        self.assertEqual(
            "dc11fd5cd430cec1aaa018a0cdce34ade1dee0e9f46ba8349e66e0c249850468",
            plan["selection"]["broadOpl"]["sha256"],
        )
        self.assertEqual(
            r"C:\FlightAlert-exp8-work\planet-260629\global-waterways\inputs\true-waterway-broad.osmium-1.11.1.opl",
            plan["selection"]["broadOpl"]["path"],
        )
        commands = plan["closureCommands"]
        roles = [command["role"] for command in commands]
        self.assertEqual(
            ["fileinfo", "getid-recursive", "check-refs", "cat-opl", "fileinfo"],
            roles,
        )
        getid = commands[1]["arguments"]
        self.assertEqual("getid", getid[0])
        self.assertIn("-r", getid)
        self.assertIn("-i", getid)
        self.assertEqual(list(range(4, 12)), plan["render"]["zooms"])
        self.assertEqual(
            23_500_000_000,
            plan["render"]["preferredComponentPackageByteCeiling"],
        )
        self.assertEqual(
            38_500_000_000,
            plan["render"]["hardComponentPackageByteCeiling"],
        )
        self.assertEqual(
            25_000_000_000,
            plan["render"]["preferredMandatoryPhoneFootprintBytes"],
        )
        self.assertEqual(
            40_000_000_000,
            plan["render"]["hardMandatoryPhoneFootprintBytes"],
        )
        self.assertEqual(
            1_500_000_000,
            plan["render"]["reservedNonComponentFootprintBytes"],
        )

    def test_strict_opl_preserves_exact_text_coordinates_and_member_order(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            StrictOplNode,
            StrictOplRelation,
            StrictOplWay,
            iter_strict_waterway_opl,
        )

        with CLOSURE_FIXTURE.open("rb") as handle:
            records = tuple(iter_strict_waterway_opl(handle))

        self.assertEqual(19, len(records))
        self.assertTrue(all(record.byte_start < record.byte_end for record in records))
        first = records[0]
        self.assertIsInstance(first.value, StrictOplNode)
        self.assertEqual((-761_000_000, 390_000_000), (first.value.longitude_e7, first.value.latitude_e7))
        way = next(
            record.value
            for record in records
            if isinstance(record.value, StrictOplWay) and record.value.object_id == 100
        )
        self.assertEqual((1, 2, 3, 4), way.node_refs)
        self.assertEqual("Малая река", dict(way.tags)["name"])
        relation = next(
            record.value
            for record in records
            if isinstance(record.value, StrictOplRelation)
        )
        self.assertEqual(
            (("w", 100, "main", 0), ("w", 101, "main", 1), ("n", 6, "mouth", 2)),
            tuple((member.object_type, member.ref, member.role, member.ordinal) for member in relation.members),
        )

    def test_relation_assembly_joins_only_adjacent_shared_node_ids(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            StrictOplMember,
            StrictOplRelation,
            StrictOplWay,
            assemble_exact_relation_paths,
        )

        ways = {
            1: StrictOplWay(1, 1, "2026-06-29T00:00:00Z", (), (1, 2, 3)),
            2: StrictOplWay(2, 1, "2026-06-29T00:00:00Z", (), (3, 4)),
            3: StrictOplWay(3, 1, "2026-06-29T00:00:00Z", (), (9, 10)),
            4: StrictOplWay(4, 1, "2026-06-29T00:00:00Z", (), (10, 11)),
        }
        relation = StrictOplRelation(
            20,
            1,
            "2026-06-29T00:00:00Z",
            (("name", "Exact River"), ("type", "waterway")),
            tuple(
                StrictOplMember("w", ref, "main", ordinal)
                for ordinal, ref in enumerate((1, 2, 3, 4))
            ),
        )

        parts = assemble_exact_relation_paths(
            relation,
            ways=ways,
            relations={20: relation},
            existing_node_ids=frozenset(range(1, 12)),
        )

        self.assertEqual(((1, 2, 3, 4), (9, 10, 11)), parts)

    def test_relation_assembly_fails_closed_on_missing_reference_or_cycle(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
            StrictOplMember,
            StrictOplRelation,
            assemble_exact_relation_paths,
        )

        missing = StrictOplRelation(
            1,
            1,
            "2026-06-29T00:00:00Z",
            (("name", "Missing"), ("type", "waterway")),
            (StrictOplMember("w", 99, "main", 0),),
        )
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "missing way 99"):
            assemble_exact_relation_paths(
                missing,
                ways={},
                relations={1: missing},
                existing_node_ids=frozenset(),
            )

        first = StrictOplRelation(
            1,
            1,
            "2026-06-29T00:00:00Z",
            (("name", "Cycle"), ("type", "waterway")),
            (StrictOplMember("r", 2, "", 0),),
        )
        second = StrictOplRelation(
            2,
            1,
            "2026-06-29T00:00:00Z",
            (),
            (StrictOplMember("r", 1, "", 0),),
        )
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "cycle"):
            assemble_exact_relation_paths(
                first,
                ways={},
                relations={1: first, 2: second},
                existing_node_ids=frozenset(),
            )


class GlobalWaterwayExtractionTests(unittest.TestCase):
    def test_atomic_directory_publish_is_no_replace_and_restores_failed_readback(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        self.assertTrue(hasattr(pipeline, "_publish_directory_no_replace"))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage = root / "stage"
            target = root / "target"
            stage.mkdir()
            (stage / "artifact.bin").write_bytes(b"exact")
            target.mkdir()
            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError, "already exists"
            ):
                pipeline._publish_directory_no_replace(
                    stage, target, lambda installed: None
                )
            self.assertTrue(stage.is_dir())
            self.assertTrue(target.is_dir())

            target.rmdir()

            def reject_installed(installed: Path) -> None:
                (installed / "artifact.bin").write_bytes(b"mutated")
                raise pipeline.GlobalWaterwayPackageError("readback differs")

            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError, "readback differs"
            ):
                pipeline._publish_directory_no_replace(
                    stage, target, reject_installed
                )
            self.assertFalse(target.exists())
            self.assertEqual(b"mutated", (stage / "artifact.bin").read_bytes())

    def test_selection_gate_rejects_a_standalone_forged_manifest(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            forged = root / "forged-selection-run"
            forged.mkdir()
            (forged / "final-manifest.json").write_bytes(
                _selection_manifest(b"claimed planet", ROOT_FIXTURE.read_bytes())
            )
            (forged / "root-ids.txt").write_bytes(ROOT_FIXTURE.read_bytes())

            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError,
                "completed global selection run",
            ):
                pipeline.validate_completed_global_selection_run(forged)

    def test_extraction_rejects_transient_selection_manifest_swap_before_copy(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8.run_osm_global_selection import canonical_json_bytes

        planet_raw = b"fixture verified planet"
        root_raw = ROOT_FIXTURE.read_bytes()
        original = _selection_manifest(planet_raw, root_raw)
        document = json.loads(original)
        document["semantic"]["producer"]["resultSha256"] = "7" * 64
        document["semanticSha256"] = hashlib.sha256(
            canonical_json_bytes(document["semantic"])
        ).hexdigest()
        swapped = canonical_json_bytes(document)
        real_read = pipeline._read_bounded
        injected = False
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.pbf"
            roots = root / "root-ids.txt"
            selection = root / "final-manifest.json"
            selection_run = root / "selection-run"
            selection_run.mkdir()
            planet.write_bytes(planet_raw)
            roots.write_bytes(root_raw)
            selection.write_bytes(original)

            def transient_read(path, maximum_bytes, label):
                nonlocal injected
                if path == selection and label == "selection final manifest" and not injected:
                    selection.write_bytes(swapped)
                    raw = real_read(path, maximum_bytes, label)
                    selection.write_bytes(original)
                    injected = True
                    return raw
                return real_read(path, maximum_bytes, label)

            with patch.object(
                pipeline, "_read_bounded", side_effect=transient_read
            ), patch.object(
                pipeline,
                "_attest_pinned_osmium",
                return_value=_attested_osmium_runtime(pipeline),
            ), patch.object(
                pipeline,
                "validate_completed_global_selection_run",
                return_value=_accepted_selection_run(
                    selection_run, selection, roots
                ),
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalWaterwayPackageError,
                    "copied selection evidence differs",
                ):
                    pipeline.extract_global_waterway_closure(
                        planet_path=planet,
                        selection_run_directory=selection_run,
                        output_directory=root / "extraction",
                        expected_planet_bytes=len(planet_raw),
                        expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                    )

    def test_extraction_resumes_exact_commands_and_publishes_bound_receipt(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        planet_raw = b"fixture verified planet"
        root_raw = ROOT_FIXTURE.read_bytes()
        fixture_opl = CLOSURE_FIXTURE.read_bytes()
        runtime = _attested_osmium_runtime(pipeline)
        calls = []
        cat_attempts = 0
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.pbf"
            roots = root / "root-ids.txt"
            selection = root / "final-manifest.json"
            selection_run = root / "selection-run"
            selection_run.mkdir()
            output = root / "extraction"
            planet.write_bytes(planet_raw)
            roots.write_bytes(root_raw)
            selection.write_bytes(_selection_manifest(planet_raw, root_raw))

            def fake_run(command, stdout_path, stderr_path):
                nonlocal cat_attempts
                calls.append(command.role)
                stdout_path.write_bytes(b"")
                stderr_path.write_bytes(b"")
                if command.role == "getid-recursive":
                    output_path = command.arguments[command.arguments.index("-o") + 1]
                    source_path = command.arguments[command.arguments.index("-i") - 1]
                    generator = command.arguments[
                        command.arguments.index("--generator") + 1
                    ]
                    stderr_path.write_bytes(
                        _getid_stderr(
                            source=source_path,
                            output=output_path,
                            ways=5,
                            relations=1,
                            generator=generator,
                        )
                    )
                    (stdout_path.parent / Path(output_path).name).write_bytes(
                        b"fixture pbf closure"
                    )
                elif command.role == "check-refs":
                    stderr_path.write_bytes(_check_refs_stderr(command.arguments[-1]))
                elif command.role == "cat-opl":
                    cat_attempts += 1
                    output_path = command.arguments[command.arguments.index("-o") + 1]
                    (stdout_path.parent / Path(output_path).name).write_bytes(
                        b"interrupted" if cat_attempts == 1 else fixture_opl
                    )
                    if cat_attempts == 1:
                        return 9
                else:
                    stdout_path.write_bytes(b"fileinfo fixture\n")
                return 0

            patches = (
                patch.object(pipeline, "_attest_pinned_osmium", return_value=runtime),
                patch.object(
                    pipeline,
                    "validate_completed_global_selection_run",
                    return_value=_accepted_selection_run(
                        selection_run, selection, roots
                    ),
                ),
                patch.object(
                    pipeline,
                    "_windows_to_wsl_path",
                    side_effect=lambda value: (
                        "/mnt/test/planet.pbf"
                        if value == planet
                        else "/mnt/test/" + value.name
                    ),
                ),
                patch.object(pipeline, "_run_osmium_command", side_effect=fake_run),
            )
            with patches[0], patches[1], patches[2], patches[3]:
                with self.assertRaisesRegex(
                    pipeline.GlobalWaterwayPackageError, "cat-opl.*exit 9"
                ):
                    pipeline.extract_global_waterway_closure(
                        planet_path=planet,
                        selection_run_directory=selection_run,
                        output_directory=output,
                        expected_planet_bytes=len(planet_raw),
                        expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                    )
                result = pipeline.extract_global_waterway_closure(
                    planet_path=planet,
                    selection_run_directory=selection_run,
                    output_directory=output,
                    expected_planet_bytes=len(planet_raw),
                    expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                )

            self.assertEqual("complete", result.state)
            self.assertEqual(
                [
                    "fileinfo",
                    "getid-recursive",
                    "check-refs",
                    "cat-opl",
                    "cat-opl",
                    "fileinfo",
                ],
                calls,
            )
            self.assertEqual(root_raw, (output / "root-ids.txt").read_bytes())
            self.assertEqual(fixture_opl, (output / "waterway-closure.opl").read_bytes())
            receipt = json.loads((output / "extraction-receipt.json").read_text("utf-8"))
            original_receipt_raw = (output / "extraction-receipt.json").read_bytes()
            self.assertEqual(
                "flightalert.experiment8.osm-global-waterway-extraction.v1",
                receipt["schema"],
            )
            self.assertEqual(0, receipt["closureAudit"]["missingReferences"])
            self.assertEqual(19, receipt["closureAudit"]["oplObjects"])
            self.assertEqual(
                "flightalert.experiment8.osm-global-waterway-osmium-attestation.v1",
                receipt["runtime"]["attestationSchema"],
            )
            self.assertTrue(receipt["runtime"]["dependencyFiles"])
            binding = pipeline.source_binding_from_extraction_receipt(output)
            self.assertEqual(result.source_binding, binding)
            self.assertEqual(hashlib.sha256(planet_raw).hexdigest(), binding.planet_sha256)
            check_log = output / "command-03-check-refs.stderr"
            check_log.write_bytes(
                _check_refs_stderr(
                    receipt["commands"][2]["arguments"][-1],
                    missing_nodes=1,
                )
            )
            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError, "check-refs"
            ):
                pipeline.source_binding_from_extraction_receipt(output)
            check_log.write_bytes(
                _check_refs_stderr(receipt["commands"][2]["arguments"][-1])
            )
            receipt["code"]["sha256"] = "0" * 64
            (output / "extraction-receipt.json").write_bytes(
                pipeline._canonical_json_bytes(receipt)
            )
            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError, "code identity"
            ):
                pipeline.source_binding_from_extraction_receipt(output)
            (output / "extraction-receipt.json").write_bytes(original_receipt_raw)
            (output / "unexpected.bin").write_bytes(b"must not publish")
            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError, "unexpected.*inventory"
            ):
                pipeline.source_binding_from_extraction_receipt(output)

    def test_extraction_rejects_nonzero_check_refs_evidence(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        planet_raw = b"fixture verified planet"
        root_raw = ROOT_FIXTURE.read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.pbf"
            roots = root / "root-ids.txt"
            selection = root / "final-manifest.json"
            selection_run = root / "selection-run"
            selection_run.mkdir()
            output = root / "extraction"
            planet.write_bytes(planet_raw)
            roots.write_bytes(root_raw)
            selection.write_bytes(_selection_manifest(planet_raw, root_raw))

            def fake_run(command, stdout_path, stderr_path):
                stdout_path.write_bytes(b"")
                stderr_path.write_bytes(b"")
                if command.role == "getid-recursive":
                    output_path = command.arguments[command.arguments.index("-o") + 1]
                    source_path = command.arguments[command.arguments.index("-i") - 1]
                    generator = command.arguments[
                        command.arguments.index("--generator") + 1
                    ]
                    stderr_path.write_bytes(
                        _getid_stderr(
                            source=source_path,
                            output=output_path,
                            ways=5,
                            relations=1,
                            generator=generator,
                        )
                    )
                    (stdout_path.parent / Path(output_path).name).write_bytes(b"pbf")
                elif command.role == "check-refs":
                    stderr_path.write_bytes(
                        _check_refs_stderr(command.arguments[-1], missing_nodes=1)
                    )
                elif command.role == "cat-opl":
                    output_path = command.arguments[command.arguments.index("-o") + 1]
                    (stdout_path.parent / Path(output_path).name).write_bytes(
                        CLOSURE_FIXTURE.read_bytes()
                    )
                return 0

            with patch.object(
                pipeline,
                "_attest_pinned_osmium",
                return_value=_attested_osmium_runtime(pipeline),
            ), patch.object(
                pipeline,
                "_windows_to_wsl_path",
                side_effect=lambda value: (
                    "/mnt/test/planet.pbf"
                    if value == planet
                    else "/mnt/test/" + value.name
                ),
            ), patch.object(
                pipeline, "_run_osmium_command", side_effect=fake_run
            ), patch.object(
                pipeline,
                "validate_completed_global_selection_run",
                return_value=_accepted_selection_run(
                    selection_run, selection, roots
                ),
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalWaterwayPackageError, "missing.*reference"
                ):
                    pipeline.extract_global_waterway_closure(
                        planet_path=planet,
                        selection_run_directory=selection_run,
                        output_directory=output,
                        expected_planet_bytes=len(planet_raw),
                        expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                    )
            self.assertFalse(output.exists())


class GlobalWaterwayCliTests(unittest.TestCase):
    def test_direct_module_entry_delegates_to_canonical_module(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as canonical

        calls: list[object] = []

        def fake_main(argv=None):
            calls.append(argv)
            return 37

        with patch.object(canonical, "main", side_effect=fake_main), patch.object(
            sys, "argv", [str(Path(canonical.__file__)), "plan"]
        ), redirect_stdout(io.StringIO()):
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_module(
                        "tools.experiment8.osm_global_waterway_package",
                        run_name="__main__",
                        alter_sys=True,
                    )

        self.assertEqual(37, raised.exception.code)
        self.assertEqual([None], calls)

    def test_plan_cli_is_read_only_and_emits_canonical_contract(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        output = io.StringIO()
        with patch.object(Path, "open", side_effect=AssertionError("plan must not open files")):
            with redirect_stdout(output):
                exit_code = pipeline.main(["plan"])

        self.assertEqual(0, exit_code)
        document = json.loads(output.getvalue())
        self.assertEqual(
            "flightalert.experiment8.osm-global-waterway-plan.v1",
            document["schema"],
        )
        self.assertEqual(list(range(4, 12)), document["render"]["zooms"])

    def test_recover_cli_validates_platform_workers_and_positive_pause(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        maximum_workers = 61 if os.name == "nt" else 64
        required = [
            "recover-render",
            "--extraction",
            "extraction",
            "--output",
            "output",
            "--work",
            "work",
            "--package-id",
            "fixture",
            "--failure-log",
            "failure.log",
            "--backup-receipt",
            "backup.json",
        ]
        arguments = pipeline._argument_parser().parse_args(
            required
            + [
                "--render-workers",
                str(maximum_workers),
                "--pause-after-features",
                "300",
                "--progress-file",
                "progress.json",
            ]
        )
        self.assertEqual(maximum_workers, arguments.render_workers)
        self.assertEqual(300, arguments.pause_after_features)
        self.assertEqual(Path("progress.json"), arguments.progress_file)

        maximum_pause = 999_999_999_999
        maximum_arguments = pipeline._argument_parser().parse_args(
            required + ["--pause-after-features", str(maximum_pause)]
        )
        self.assertEqual(maximum_pause, maximum_arguments.pause_after_features)

        for option, value in (
            ("--render-workers", "0"),
            ("--render-workers", str(maximum_workers + 1)),
            ("--pause-after-features", "0"),
            ("--pause-after-features", str(maximum_pause + 1)),
        ):
            with self.subTest(option=option, value=value), redirect_stderr(
                io.StringIO()
            ), self.assertRaises(SystemExit):
                pipeline._argument_parser().parse_args(required + [option, value])

    def test_recover_cli_threads_execution_controls_without_changing_defaults(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            progress = root / "progress.json"
            result = pipeline.GlobalWaterwayBuildResult(
                "paused", root / "output", {"state": "paused"}
            )
            output = io.StringIO()
            with patch.object(
                pipeline,
                "recover_global_waterway_package",
                return_value=result,
            ) as recover, redirect_stdout(output):
                exit_code = pipeline.main(
                    [
                        "recover-render",
                        "--extraction",
                        str(root / "extraction"),
                        "--output",
                        str(root / "output"),
                        "--work",
                        str(root / "work"),
                        "--package-id",
                        "fixture",
                        "--failure-log",
                        str(root / "failure.log"),
                        "--backup-receipt",
                        str(root / "backup.json"),
                        "--render-workers",
                        "10",
                        "--pause-after-features",
                        "300",
                        "--progress-file",
                        str(progress),
                    ]
                )

            self.assertEqual(0, exit_code)
            recover.assert_called_once_with(
                extraction_directory=root / "extraction",
                output_directory=root / "output",
                work_directory=root / "work",
                package_id="fixture",
                failure_log=root / "failure.log",
                backup_receipt=root / "backup.json",
                checkpoint_features=100,
                render_workers=10,
                pause_after_features=300,
                progress_file=progress,
                size_policy_mode=(
                    pipeline.size_policy_module.DEFAULT_REFERENCE_SIZE_POLICY_MODE
                ),
            )

            defaults = pipeline._argument_parser().parse_args(
                [
                    "recover-render",
                    "--extraction",
                    "extraction",
                    "--output",
                    "output",
                    "--work",
                    "work",
                    "--package-id",
                    "fixture",
                    "--failure-log",
                    "failure.log",
                    "--backup-receipt",
                    "backup.json",
                ]
            )
            self.assertEqual(1, defaults.render_workers)
            self.assertIsNone(defaults.pause_after_features)
            self.assertIsNone(defaults.progress_file)

    def test_progress_location_requires_a_real_parent_and_stays_outside_output(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            progress_parent = root / "evidence"
            progress_parent.mkdir()
            progress = progress_parent / "progress.json"
            output = root / "output"
            self.assertEqual(
                progress,
                store._validate_render_progress_location(progress, output),
            )

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "progress.*parent|directory"
            ):
                store._validate_render_progress_location(
                    root / "missing" / "progress.json", output
                )

            output.mkdir()
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "progress.*output|outside"
            ):
                store._validate_render_progress_location(
                    output / "progress.json", output
                )

            if os.name == "nt":
                cased_progress = Path(str(output).swapcase()) / "progress.json"
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "progress.*output|outside"
                ):
                    store._validate_render_progress_location(
                        cased_progress, output
                    )

            real_parent = root / "real-progress"
            real_parent.mkdir()
            linked_parent = root / "linked-progress"
            try:
                os.symlink(real_parent, linked_parent, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks are unavailable: {error}")
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "link|reparse|plain"
            ):
                store._validate_render_progress_location(
                    linked_parent / "progress.json", root / "other-output"
                )

    def test_recovery_facade_threads_execution_controls_to_bound_recovery(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8 import osm_global_waterway_recovery as recovery

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            extraction = root / "extraction"
            output = root / "output"
            work = root / "work"
            failure = root / "failure.log"
            backup = root / "backup.json"
            progress = root / "progress.json"
            binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
            result = pipeline.GlobalWaterwayBuildResult(
                "paused", output, {"state": "paused"}
            )
            with patch.object(
                pipeline,
                "_source_binding_from_recovery_extraction",
                return_value=binding,
            ), patch.object(
                recovery,
                "_recover_bound_global_waterway_package",
                return_value=result,
            ) as implementation:
                actual = pipeline.recover_global_waterway_package(
                    extraction_directory=extraction,
                    output_directory=output,
                    work_directory=work,
                    package_id="fixture",
                    failure_log=failure,
                    backup_receipt=backup,
                    checkpoint_features=100,
                    render_workers=10,
                    pause_after_features=300,
                    progress_file=progress,
                )

            self.assertIs(result, actual)
            call = implementation.call_args
            self.assertEqual(10, call.kwargs["render_workers"])
            self.assertEqual(300, call.kwargs["pause_after_features"])
            self.assertEqual(progress, call.kwargs["progress_file"])

    def test_runner_and_powershell_wrapper_target_host_only_pipeline(self) -> None:
        runner = Path("tools/experiment8/run_osm_global_waterway_package.py")
        wrapper = Path("tools/build-osm-global-waterway-package.ps1")

        self.assertTrue(runner.is_file())
        self.assertTrue(wrapper.is_file())
        runner_text = runner.read_text("utf-8")
        wrapper_text = wrapper.read_text("utf-8")
        self.assertIn("osm_global_waterway_package import main", runner_text)
        self.assertIn(
            "py -3.11 -m tools.experiment8.run_osm_global_waterway_package",
            wrapper_text,
        )
        self.assertNotIn("adb", wrapper_text.casefold())
        self.assertNotIn("gradlew", wrapper_text.casefold())


class GlobalWaterwayStoreTests(unittest.TestCase):
    def test_ingest_resume_authenticates_sqlite_rows_against_bound_opl_prefix(self) -> None:
        import sqlite3

        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
        from tools.experiment8.osm_global_waterway_store import ingest_global_waterway_closure

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary) / "work"
            paused = ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=work,
                source_binding=binding,
                checkpoint_objects=3,
                pause_after_objects=10,
            )
            connection = sqlite3.connect(paused.database_path)
            try:
                connection.execute(
                    "UPDATE nodes SET longitude_e7=longitude_e7+1 WHERE id=1"
                )
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "SQLite.*(payload|source rows)"
            ):
                ingest_global_waterway_closure(
                    opl_path=CLOSURE_FIXTURE,
                    root_ids_path=ROOT_FIXTURE,
                    work_directory=work,
                    source_binding=binding,
                    checkpoint_objects=3,
                )

    def test_sqlite_ingest_pauses_resumes_and_separates_roots_from_closure_refs(self) -> None:
        from tools.experiment8.osm_global_waterway_store import (
            ingest_global_waterway_closure,
        )

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        real_read_bytes = Path.read_bytes

        def reject_bulk_source_read(path: Path) -> bytes:
            if path.resolve() in {CLOSURE_FIXTURE.resolve(), ROOT_FIXTURE.resolve()}:
                raise AssertionError("global closure inputs must be streamed")
            return real_read_bytes(path)

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            Path, "read_bytes", reject_bulk_source_read
        ):
            root = Path(temporary)
            paused = ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "resumed",
                source_binding=binding,
                checkpoint_objects=3,
                pause_after_objects=10,
            )
            self.assertEqual("paused", paused.state)
            self.assertEqual(10, paused.receipt["checkpoint"]["inputObjects"])

            resumed = ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "resumed",
                source_binding=binding,
                checkpoint_objects=3,
            )
            clean = ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "clean",
                source_binding=binding,
                checkpoint_objects=3,
            )

        self.assertEqual("complete", resumed.state)
        self.assertEqual("complete", clean.state)
        self.assertEqual(resumed.receipt["semanticSha256"], clean.receipt["semanticSha256"])
        audit = resumed.receipt["closureAudit"]
        self.assertEqual(12, audit["nodes"])
        self.assertEqual(6, audit["ways"])
        self.assertEqual(1, audit["relations"])
        self.assertEqual(5, audit["selectedWayRoots"])
        self.assertEqual(1, audit["selectedRelationRoots"])
        self.assertEqual(1, audit["referenceOnlyWays"])
        self.assertEqual(0, audit["missingReferences"])
        self.assertEqual(64 * 1024 * 1024, resumed.receipt["peakResources"]["sqliteCacheTargetBytes"])
        self.assertGreater(
            resumed.receipt["peakResources"]["observedPersistentSqliteBytesAtCheckpoints"],
            0,
        )

    def test_ingest_fails_closed_on_incomplete_way_geometry(self) -> None:
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
        from tools.experiment8.osm_global_waterway_store import ingest_global_waterway_closure

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "missing-node.opl"
            opl.write_bytes(CLOSURE_FIXTURE.read_bytes().replace(b"Nn11,n12", b"Nn11,n999"))
            binding = _source_binding(opl, ROOT_FIXTURE)

            with self.assertRaisesRegex(GlobalWaterwayPackageError, "missing node 999"):
                ingest_global_waterway_closure(
                    opl_path=opl,
                    root_ids_path=ROOT_FIXTURE,
                    work_directory=root / "work",
                    source_binding=binding,
                    checkpoint_objects=4,
                )

    def test_ingest_rejects_root_identity_or_resume_identity_drift(self) -> None:
        from dataclasses import replace

        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError
        from tools.experiment8.osm_global_waterway_store import ingest_global_waterway_closure

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "root-ID.*identity"):
                ingest_global_waterway_closure(
                    opl_path=CLOSURE_FIXTURE,
                    root_ids_path=ROOT_FIXTURE,
                    work_directory=root / "bad-root",
                    source_binding=replace(binding, root_ids_sha256="0" * 64),
                    checkpoint_objects=2,
                )

            ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "resume",
                source_binding=binding,
                checkpoint_objects=2,
                pause_after_objects=2,
            )
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "checkpoint identity"):
                ingest_global_waterway_closure(
                    opl_path=CLOSURE_FIXTURE,
                    root_ids_path=ROOT_FIXTURE,
                    work_directory=root / "resume",
                    source_binding=replace(
                        binding,
                        selection_manifest_sha256=hashlib.sha256(b"different").hexdigest(),
                    ),
                    checkpoint_objects=2,
                )


class GlobalWaterwayRendererTests(unittest.TestCase):
    def test_complete_renderer_code_identity_includes_parallel_renderer(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel

        identities = store._render_code_identities()
        self.assertIn("waterwayParallelRender", identities)
        self.assertEqual(
            store._stream_identity(
                Path(parallel.__file__).resolve(),
                "waterwayParallelRender code",
            ),
            identities["waterwayParallelRender"],
        )

    def test_complete_renderer_code_identity_includes_tile_key_model(self) -> None:
        from tools.experiment8 import model
        from tools.experiment8 import osm_global_waterway_store as store

        identities = store._render_code_identities()
        self.assertIn("model", identities)
        self.assertEqual(
            store._stream_identity(Path(model.__file__).resolve(), "model code"),
            identities["model"],
        )

    def test_classifier_identity_includes_tile_key_model(self) -> None:
        from tools.experiment8 import model
        from tools.experiment8 import osm_global_waterway_renderer as renderer

        streamed = []

        def record_module(_digest, path, *, maximum_bytes):
            self.assertEqual(renderer._MAX_CLASSIFIER_MODULE_BYTES, maximum_bytes)
            streamed.append(Path(path).resolve())
            return 0

        with patch.object(
            renderer,
            "_update_classifier_digest_bounded",
            side_effect=record_module,
        ):
            renderer.classifier_identity_sha256()
        self.assertIn(Path(model.__file__).resolve(), streamed)

    def test_classifier_identity_streams_without_unbounded_read_bytes(self) -> None:
        from tools.experiment8 import osm_global_waterway_renderer as renderer

        with patch.object(
            Path,
            "read_bytes",
            side_effect=AssertionError("classifier code must be streamed"),
        ):
            identity = renderer.classifier_identity_sha256()
        self.assertRegex(identity, r"\A[0-9a-f]{64}\Z")

    def test_classifier_stream_has_an_exact_module_byte_ceiling(self) -> None:
        from tools.experiment8 import osm_global_waterway_renderer as renderer
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        self.assertTrue(hasattr(renderer, "_update_classifier_digest_bounded"))
        with tempfile.TemporaryDirectory() as temporary:
            oversized = Path(temporary) / "oversized.py"
            oversized.write_bytes(b"x" * 33)
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "exceeds 32 classifier bytes"
            ):
                renderer._update_classifier_digest_bounded(
                    hashlib.sha256(), oversized, maximum_bytes=32
                )

    def test_dependency_evidence_is_one_fixed_size_digest(self) -> None:
        from sys import getsizeof

        from tools.experiment8 import osm_global_waterway_store as store

        evidence = store._DependencyEvidence()
        initial_size = getsizeof(evidence)
        for index in range(10_000):
            evidence.add(["node", index, hashlib.sha256(str(index).encode()).hexdigest()])
        self.assertFalse(hasattr(evidence, "__dict__"))
        self.assertEqual(initial_size, getsizeof(evidence))
        self.assertEqual(10_000, evidence.document()["records"])
        self.assertRegex(evidence.document()["sha256"], r"\A[0-9a-f]{64}\Z")
        evidence._records = (1 << 64) - 1
        with self.assertRaisesRegex(
            store.GlobalWaterwayPackageError, "dependency record count"
        ):
            evidence.add(["node", 10_001, "0" * 64])

    def test_relation_assembly_consumes_occurrences_incrementally(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_renderer import ExactWaterwayPoint

        consumed = {"first": False}

        class MarkingPoints:
            def __init__(self, points, marker: str):
                self._points = points
                self._marker = marker

            def __iter__(self):
                consumed[self._marker] = True
                return iter(self._points)

            def __getitem__(self, index):
                return self._points[index]

        def occurrences(connection, relation_id, **kwargs):
            del connection, relation_id, kwargs
            yield "candidate", {
                "path": ((2, 200, -1), (1, 100, 0)),
                "points": MarkingPoints((
                    ExactWaterwayPoint(1_000, -760_000_000, 390_000_000),
                    ExactWaterwayPoint(1_001, -759_000_000, 391_000_000),
                ), "first"),
                "waterwayType": "river",
            }
            self.assertTrue(
                consumed["first"],
                "the first occurrence was retained instead of consumed incrementally",
            )
            yield "candidate", {
                "path": ((2, 200, -1), (1, 101, 1)),
                "points": (
                    ExactWaterwayPoint(1_001, -759_000_000, 391_000_000),
                    ExactWaterwayPoint(1_002, -758_000_000, 392_000_000),
                ),
                "waterwayType": "river",
            }

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "work",
                source_binding=binding,
                checkpoint_objects=3,
            )
            import sqlite3

            connection = sqlite3.connect(result.database_path)
            try:
                with patch.object(store, "_candidate_relation_events", occurrences):
                    analysis = store._analyze_admission_root(
                        connection, 2, 200, materialize=True
                    )
                    features = tuple(store._analysis_features(analysis))
            finally:
                connection.close()

        relation = next(feature for feature in features if feature.source_kind == "relation")
        self.assertEqual(
            ((1_000, 1_001, 1_002),),
            tuple(
                tuple(point.node_id for point in part) for part in relation.parts
            ),
        )

    def test_dynamic_source_field_ids_reject_64_bit_collisions(self) -> None:
        from tools.experiment8 import osm_global_waterway_renderer as renderer
        from tools.experiment8.semantic_model import HotIdRegistry, IdentityCollisionError

        registry = HotIdRegistry(digest_function=lambda value: b"x" * 32)
        renderer._u64_identity("openstreetmap.tag.name", registry)
        with self.assertRaisesRegex(IdentityCollisionError, "collision"):
            renderer._u64_identity("openstreetmap.tag.name:en", registry)

    def test_nested_interleaved_relation_retains_exact_parts_by_source_type(self) -> None:
        from tools.experiment8.osm_global_waterway_store import (
            ingest_global_waterway_closure,
            iter_exact_waterway_features,
        )

        opl_raw = b"".join(
            [
                f"n{node} v1 t2026-06-29T00:00:00Z x{-80 + node}.0 y40.0\n".encode()
                for node in range(1, 7)
            ]
            + [
                b"w10 v1 t2026-06-29T00:00:00Z Twaterway=river Nn1,n2\n",
                b"w11 v1 t2026-06-29T00:00:00Z Twaterway=stream Nn2,n3\n",
                b"w12 v1 t2026-06-29T00:00:00Z Twaterway=river Nn3,n4\n",
                b"w13 v1 t2026-06-29T00:00:00Z Twaterway=canal Nn4,n5\n",
                b"w14 v1 t2026-06-29T00:00:00Z Twaterway=stream Nn5,n6\n",
                b"r20 v1 t2026-06-29T00:00:00Z Ttype=waterway Mw13@,w14@\n",
                b"r30 v1 t2026-06-29T00:00:00Z Tname=Mixed%20%Waterway,type=waterway Mw10@,w11@,w12@,r20@\n",
            ]
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "closure.opl"
            roots = root / "roots.txt"
            opl.write_bytes(opl_raw)
            roots.write_bytes(b"r30\n")
            binding = _source_binding(opl, roots)
            result = ingest_global_waterway_closure(
                opl_path=opl,
                root_ids_path=roots,
                work_directory=root / "work",
                source_binding=binding,
                checkpoint_objects=3,
            )
            features = tuple(
                iter_exact_waterway_features(result.database_path, source_binding=binding)
            )

        self.assertEqual(["river", "stream", "canal"], [f.waterway_type for f in features])
        self.assertEqual(
            (((1, 2), (3, 4)), ((2, 3), (5, 6)), ((4, 5),)),
            tuple(
                tuple(tuple(point.node_id for point in part) for part in feature.parts)
                for feature in features
            ),
        )

    def test_relation_geometry_budget_accepts_exact_limits_and_rejects_overflow(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store

        def budget():
            return store._CandidateUsage(
                replace(
                    store._AdmissionLimits.production(),
                    max_candidate_depth=2,
                    max_candidate_relation_visits=2,
                    max_candidate_raw_parts=2,
                    max_candidate_points=4,
                )
            )

        exact = budget()
        exact.enter_relation(1)
        exact.enter_relation(2)
        exact.reserve_way(2)
        exact.reserve_way(2)
        self.assertEqual((2, 4, 2), (exact.raw_parts, exact.raw_points, exact.relation_visits))

        with self.assertRaisesRegex(store.GlobalWaterwayPackageError, "depth ceiling"):
            budget().enter_relation(3)
        visits = budget()
        visits.enter_relation(1)
        visits.enter_relation(2)
        with self.assertRaisesRegex(
            store.GlobalWaterwayPackageError, "relation-visit ceiling"
        ):
            visits.enter_relation(2)
        parts = budget()
        parts.reserve_way(2)
        parts.reserve_way(2)
        with self.assertRaisesRegex(store.GlobalWaterwayPackageError, "part ceiling"):
            parts.reserve_way(0)
        points = budget()
        points.reserve_way(4)
        with self.assertRaisesRegex(store.GlobalWaterwayPackageError, "point ceiling"):
            points.reserve_way(1)

    def test_relation_geometry_policy_blocks_overflow_before_way_materialization(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        opl_raw = b"".join(
            [
                f"n{node} v1 t2026-06-29T00:00:00Z x{-80 + node}.0 y40.0\n".encode()
                for node in range(1, 5)
            ]
            + [
                b"w10 v1 t2026-06-29T00:00:00Z Twaterway=river Nn1,n2\n",
                b"w11 v1 t2026-06-29T00:00:00Z Twaterway=river Nn3,n4\n",
                b"r20 v1 t2026-06-29T00:00:00Z Tname=Bounded%20%River,type=waterway Mw10@,w11@\n",
            ]
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "closure.opl"
            roots = root / "roots.txt"
            opl.write_bytes(opl_raw)
            roots.write_bytes(b"r20\n")
            binding = _source_binding(opl, roots)
            result = store.ingest_global_waterway_closure(
                opl_path=opl,
                root_ids_path=roots,
                work_directory=root / "work",
                source_binding=binding,
                checkpoint_objects=3,
            )
            import sqlite3
            from dataclasses import replace

            production = store._AdmissionLimits.production()
            connection = sqlite3.connect(result.database_path)
            try:
                exact = replace(
                    production,
                    max_candidate_depth=1,
                    max_candidate_relation_visits=1,
                    max_candidate_raw_parts=2,
                    max_candidate_points=4,
                )
                analysis = store._analyze_admission_root(
                    connection, 2, 20, limits=exact
                )
                self.assertEqual(1, len(analysis.candidates))

                materialize_points = store._point_rows
                for limit, message in (
                    ({"max_candidate_raw_parts": 1}, "raw-part ceiling"),
                    ({"max_candidate_points": 3}, "raw-point ceiling"),
                ):
                    with self.subTest(limit=limit), patch.object(
                        store, "_point_rows", wraps=materialize_points
                    ) as point_rows:
                        with self.assertRaisesRegex(
                            store.GlobalWaterwayPackageError, message
                        ):
                            store._analyze_admission_root(
                                connection,
                                2,
                                20,
                                limits=replace(production, **limit),
                            )
                        self.assertEqual(
                            1,
                            point_rows.call_count,
                            "overflowing second way was materialized before rejection",
                        )
            finally:
                connection.close()

    def test_relation_geometry_policy_bounds_deep_nesting_at_exact_depth(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        maximum_depth = store._AdmissionLimits.production().max_candidate_depth

        def closure(depth: int) -> bytes:
            records = [
                b"n1 v1 t2026-06-29T00:00:00Z x-76.0 y39.0\n",
                b"n2 v1 t2026-06-29T00:00:00Z x-75.9 y39.1\n",
                b"w10 v1 t2026-06-29T00:00:00Z Twaterway=river Nn1,n2\n",
            ]
            for level in range(1, depth + 1):
                member = "w10" if level == depth else f"r{level + 1}"
                tags = "name=Deep%20%River,type=waterway" if level == 1 else "type=waterway"
                records.append(
                    f"r{level} v1 t2026-06-29T00:00:00Z T{tags} M{member}@\n".encode()
                )
            return b"".join(records)

        def ingest(root: Path, depth: int):
            root.mkdir(parents=True)
            opl = root / "closure.opl"
            roots = root / "roots.txt"
            opl.write_bytes(closure(depth))
            roots.write_bytes(b"r1\n")
            binding = _source_binding(opl, roots)
            result = store.ingest_global_waterway_closure(
                opl_path=opl,
                root_ids_path=roots,
                work_directory=root / "work",
                source_binding=binding,
                checkpoint_objects=16,
            )
            return result, binding

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            exact, exact_binding = ingest(root / "exact", maximum_depth)
            self.assertEqual(
                1,
                len(
                    tuple(
                        store.iter_exact_waterway_features(
                            exact.database_path, source_binding=exact_binding
                        )
                    )
                ),
            )
            with self.assertRaisesRegex(
                store.GlobalWaterwayPackageError, "depth ceiling"
            ):
                ingest(root / "overflow", maximum_depth + 1)

    def test_adaptive_complete_path_preserves_parts_endpoints_and_increases_detail(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayPoint,
            adaptive_complete_parts,
        )

        part = tuple(
            ExactWaterwayPoint(
                node_id=index + 1,
                longitude_e7=-760_000_000 + index * 100_000,
                latitude_e7=390_000_000 + (10_000 if index % 2 else -10_000),
            )
            for index in range(21)
        )
        second = (
            ExactWaterwayPoint(100, -750_000_000, 390_000_000),
            ExactWaterwayPoint(101, -749_000_000, 391_000_000),
        )

        coarse = adaptive_complete_parts((part, second), zoom=4)
        detailed = adaptive_complete_parts((part, second), zoom=11)

        self.assertEqual(2, len(coarse))
        self.assertEqual((part[0], part[-1]), (coarse[0][0], coarse[0][-1]))
        self.assertEqual(second, coarse[1])
        self.assertLess(len(coarse[0]), len(detailed[0]))
        self.assertLessEqual(len(detailed[0]), len(part))

    def test_store_exposes_only_selected_exact_features_with_relation_joins(self) -> None:
        from tools.experiment8.osm_global_waterway_store import (
            ingest_global_waterway_closure,
            iter_exact_waterway_features,
        )

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root,
                source_binding=binding,
                checkpoint_objects=4,
            )
            features = tuple(
                iter_exact_waterway_features(result.database_path, source_binding=binding)
            )

        self.assertEqual(6, len(features))
        self.assertEqual(
            [("way", 100), ("way", 102), ("way", 103), ("way", 104), ("way", 105), ("relation", 200)],
            [(feature.source_kind, feature.source_id) for feature in features],
        )
        relation = features[-1]
        self.assertTrue(relation.complete_named_relation)
        self.assertEqual("river", relation.waterway_type)
        self.assertEqual("Великая река", relation.primary_name)
        self.assertEqual("Great River", relation.english_name)
        self.assertEqual(((1, 2, 3, 4, 5, 6),), tuple(
            tuple(point.node_id for point in part) for part in relation.parts
        ))
        self.assertEqual(frozenset({4}), relation.required_node_ids)
        from tools.experiment8.osm_global_waterway_renderer import adaptive_complete_parts

        coarse = adaptive_complete_parts(
            relation.parts,
            zoom=4,
            required_node_ids=relation.required_node_ids,
        )
        self.assertIn(4, {point.node_id for point in coarse[0]})
        self.assertNotEqual(features[0].source_feature_sha256, relation.source_feature_sha256)
        self.assertNotIn(101, {feature.source_id for feature in features})

    def test_exact_feature_stream_uses_authenticated_direct_way_fast_path(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=4,
            )
            connection = sqlite3.connect(result.database_path)
            try:
                source_document = store._source_document_from_database(connection)
                expected = []
                for root_kind, root_id in connection.execute(
                    "SELECT kind,id FROM roots ORDER BY kind,id"
                ):
                    analysis = store._analyze_admission_root(
                        connection,
                        int(root_kind),
                        int(root_id),
                        materialize=True,
                        source_document=source_document,
                    )
                    expected.extend(store._analysis_features(analysis))
            finally:
                connection.close()

            real_analyze = store._analyze_admission_root

            def reject_direct_reanalysis(connection, root_kind, root_id, **kwargs):
                if root_kind == 1:
                    raise AssertionError("direct way used legacy admission reanalysis")
                return real_analyze(connection, root_kind, root_id, **kwargs)

            with patch.object(
                store,
                "_analyze_admission_root",
                side_effect=reject_direct_reanalysis,
            ):
                connection = sqlite3.connect(result.database_path)
                try:
                    actual = tuple(
                        store._iter_render_waterway_features(
                            connection,
                            source_binding=binding,
                        )
                    )
                finally:
                    connection.close()

        self.assertEqual(tuple(expected), actual)
        self.assertTrue(any(feature.source_kind == "way" for feature in actual))
        self.assertTrue(any(feature.source_kind == "relation" for feature in actual))

    def test_direct_way_fast_path_rejects_geometry_drift_against_admission_hashes(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=4,
            )
            connection = sqlite3.connect(result.database_path)
            try:
                connection.execute(
                    "UPDATE nodes SET longitude_e7=longitude_e7+1 WHERE id=1"
                )
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                store.GlobalWaterwayPackageError,
                "candidate.*differs",
            ):
                connection = sqlite3.connect(result.database_path)
                try:
                    tuple(
                        store._iter_render_waterway_features(
                            connection,
                            source_binding=binding,
                        )
                    )
                finally:
                    connection.close()

    def test_direct_way_fast_path_rejects_complete_admission_entry_drift(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        mutations = (
            ("missing schema field", lambda entry: entry.pop("geometryUsage")),
            (
                "boolean integer field",
                lambda entry: entry.__setitem__("candidateFeatureCount", True),
            ),
            ("framed byte column", None),
            ("status column", None),
        )
        for label, mutate in mutations:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                result = store.ingest_global_waterway_closure(
                    opl_path=CLOSURE_FIXTURE,
                    root_ids_path=ROOT_FIXTURE,
                    work_directory=Path(temporary),
                    source_binding=binding,
                    checkpoint_objects=4,
                )
                connection = sqlite3.connect(result.database_path)
                try:
                    if mutate is not None:
                        raw = bytes(
                            connection.execute(
                                "SELECT entry_json FROM admission_roots "
                                "WHERE root_kind=1 AND root_id=100"
                            ).fetchone()[0]
                        )
                        entry = json.loads(raw.decode("utf-8", "strict"))
                        mutate(entry)
                        changed = store._canonical_json_no_lf_bytes(entry)
                        connection.execute(
                            "UPDATE admission_roots SET entry_json=?,entry_sha=?,"
                            "framed_bytes=? WHERE root_kind=1 AND root_id=100",
                            (changed, hashlib.sha256(changed).digest(), 8 + len(changed)),
                        )
                    elif label == "framed byte column":
                        connection.execute(
                            "UPDATE admission_roots SET framed_bytes=framed_bytes+1 "
                            "WHERE root_kind=1 AND root_id=100"
                        )
                    else:
                        connection.execute(
                            "UPDATE admission_roots SET status='no_line_geometry' "
                            "WHERE root_kind=1 AND root_id=100"
                        )
                    connection.commit()
                finally:
                    connection.close()

                with self.assertRaisesRegex(
                    store.GlobalWaterwayPackageError,
                    "admitted direct-way.*(?:schema|column)",
                ):
                    connection = sqlite3.connect(result.database_path)
                    try:
                        tuple(
                            store._iter_render_waterway_features(
                                connection,
                                source_binding=binding,
                            )
                        )
                    finally:
                        connection.close()

    def test_direct_way_fast_path_rejects_coordinated_source_name_drift(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=4,
            )
            connection = sqlite3.connect(result.database_path)
            try:
                analysis = store._analyze_admission_root(
                    connection,
                    1,
                    100,
                    materialize=True,
                )
                entry = dict(analysis.entry)
                entry["primaryName"] = "Coordinated rewrite"
                entry["ownedNameField"] = {
                    "key": entry["nameSourceKey"],
                    "value": entry["primaryName"],
                }
                candidate = analysis.candidates[0]
                candidate_metadata, source_metadata = store._feature_metadata(
                    root=entry["root"],
                    source_document=store._source_document_from_database(connection),
                    name_source_key=entry["nameSourceKey"],
                    primary_name=entry["primaryName"],
                    english_name=entry["englishName"],
                    waterway_type=candidate.waterway_type,
                    complete_named_relation=False,
                    required_node_ids=frozenset(),
                    source_occurrence_paths=candidate.source_occurrence_paths,
                    dependency_evidence=entry["dependencyEvidence"],
                )
                candidate_sha256, source_sha256 = store._hash_materialized_candidate(
                    candidate_metadata=candidate_metadata,
                    source_metadata=source_metadata,
                    parts=candidate.parts,
                )
                changed = store._canonical_json_no_lf_bytes(entry)
                connection.execute(
                    "UPDATE admission_roots SET entry_json=?,entry_sha=?,framed_bytes=? "
                    "WHERE root_kind=1 AND root_id=100",
                    (changed, hashlib.sha256(changed).digest(), 8 + len(changed)),
                )
                connection.execute(
                    "UPDATE admission_candidates SET candidate_feature_sha=?,"
                    "source_feature_sha=? WHERE root_kind=1 AND root_id=100 "
                    "AND feature_ordinal=0",
                    (candidate_sha256, source_sha256),
                )
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                store.GlobalWaterwayPackageError,
                "entry schema",
            ):
                connection = sqlite3.connect(result.database_path)
                try:
                    tuple(
                        store._iter_render_waterway_features(
                            connection,
                            source_binding=binding,
                        )
                    )
                finally:
                    connection.close()

    def test_direct_way_batch_rejects_text_entry_before_byte_cap_bypass(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=4,
            )
            connection = sqlite3.connect(result.database_path)
            try:
                raw = bytes(
                    connection.execute(
                        "SELECT entry_json FROM admission_roots "
                        "WHERE root_kind=1 AND root_id=100"
                    ).fetchone()[0]
                )
                text = raw.decode("utf-8", "strict")
                self.assertLess(len(text), len(raw))
                connection.execute(
                    "UPDATE admission_roots SET entry_json=? "
                    "WHERE root_kind=1 AND root_id=100",
                    (text,),
                )
                connection.commit()
                with patch.object(
                    store,
                    "_DIRECT_WAY_STREAM_BATCH_ENTRY_BYTES",
                    len(raw),
                ):
                    with self.assertRaisesRegex(
                        store.GlobalWaterwayPackageError,
                        "direct-way root.*(?:storage|exceeds)",
                    ):
                        store._direct_way_root_id_batch(connection, 0)
            finally:
                connection.close()

    def test_direct_way_fast_path_hard_bounds_actual_point_rows(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=4,
            )
            connection = sqlite3.connect(result.database_path)
            try:
                connection.execute(
                    "INSERT INTO way_nodes(way_id,ordinal,node_id) VALUES (100,4,1)"
                )
                connection.commit()
                with patch.object(store, "_DIRECT_WAY_STREAM_BATCH_POINTS", 4):
                    with self.assertRaisesRegex(
                        store.GlobalWaterwayPackageError,
                        "direct-way point batch exceeds",
                    ):
                        tuple(
                            store._iter_render_waterway_features(
                                connection,
                                source_binding=binding,
                            )
                        )
            finally:
                connection.close()

    def test_direct_way_fast_path_rejects_coordinated_dependency_hash_drift(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=4,
            )
            connection = sqlite3.connect(result.database_path)
            try:
                analysis = store._analyze_admission_root(
                    connection,
                    1,
                    100,
                    materialize=True,
                )
                entry = dict(analysis.entry)
                entry["dependencyEvidence"] = {
                    "records": entry["dependencyEvidence"]["records"],
                    "sha256": "0" * 64,
                }
                candidate = analysis.candidates[0]
                candidate_metadata, source_metadata = store._feature_metadata(
                    root=entry["root"],
                    source_document=store._source_document_from_database(connection),
                    name_source_key=entry["nameSourceKey"],
                    primary_name=entry["primaryName"],
                    english_name=entry["englishName"],
                    waterway_type=candidate.waterway_type,
                    complete_named_relation=False,
                    required_node_ids=frozenset(),
                    source_occurrence_paths=candidate.source_occurrence_paths,
                    dependency_evidence=entry["dependencyEvidence"],
                )
                candidate_sha256, source_sha256 = store._hash_materialized_candidate(
                    candidate_metadata=candidate_metadata,
                    source_metadata=source_metadata,
                    parts=candidate.parts,
                )
                changed = store._canonical_json_no_lf_bytes(entry)
                connection.execute(
                    "UPDATE admission_roots SET entry_json=?,entry_sha=?,framed_bytes=? "
                    "WHERE root_kind=1 AND root_id=100",
                    (changed, hashlib.sha256(changed).digest(), 8 + len(changed)),
                )
                connection.execute(
                    "UPDATE admission_candidates SET candidate_feature_sha=?,"
                    "source_feature_sha=? WHERE root_kind=1 AND root_id=100 "
                    "AND feature_ordinal=0",
                    (candidate_sha256, source_sha256),
                )
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                store.GlobalWaterwayPackageError,
                "entry schema",
            ):
                connection = sqlite3.connect(result.database_path)
                try:
                    tuple(
                        store._iter_render_waterway_features(
                            connection,
                            source_binding=binding,
                        )
                    )
                finally:
                    connection.close()

    def test_direct_way_fast_path_is_exact_across_closed_cursor_batches(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        feature_count = 205
        opl_raw = b"".join(
            f"n{node} v1 t2026-06-29T00:00:00Z x-76.0 y39.0\n".encode()
            for node in range(1, feature_count * 2 + 1)
        ) + b"".join(
            (
                f"w{source_id} v1 t2026-06-29T00:00:00Z "
                f"Twaterway=river,name=River%20%{source_id} "
                f"Nn{source_id * 2 - 1},n{source_id * 2}\n"
            ).encode()
            for source_id in range(1, feature_count + 1)
        )
        roots_raw = b"".join(
            f"w{source_id}\n".encode()
            for source_id in range(1, feature_count + 1)
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "closure.opl"
            roots = root / "roots.txt"
            opl.write_bytes(opl_raw)
            roots.write_bytes(roots_raw)
            binding = _source_binding(opl, roots)
            result = store.ingest_global_waterway_closure(
                opl_path=opl,
                root_ids_path=roots,
                work_directory=root / "work",
                source_binding=binding,
                checkpoint_objects=100,
            )
            expected = tuple(
                store.iter_exact_waterway_features(
                    result.database_path,
                    source_binding=binding,
                )
            )
            connection = sqlite3.connect(result.database_path)
            try:
                with patch.object(store, "_DIRECT_WAY_STREAM_BATCH_POINTS", 1):
                    with self.assertRaisesRegex(
                        store.GlobalWaterwayPackageError,
                        "direct-way root exceeds",
                    ):
                        store._direct_way_root_id_batch(connection, 0)
                with patch.object(
                    store,
                    "_DIRECT_WAY_STREAM_BATCH_ENTRY_BYTES",
                    1,
                ):
                    with self.assertRaisesRegex(
                        store.GlobalWaterwayPackageError,
                        "direct-way root exceeds",
                    ):
                        store._direct_way_root_id_batch(connection, 0)
                stream = iter(
                    store._iter_render_waterway_features(
                        connection,
                        source_binding=binding,
                    )
                )
                actual = []
                for batch_count in (100, 100, 5):
                    actual.extend(next(stream) for _ in range(batch_count))
                    connection.commit()
                with self.assertRaises(StopIteration):
                    next(stream)
            finally:
                connection.close()

        self.assertEqual(feature_count, len(actual))
        self.assertEqual(expected, tuple(actual))

    def test_typed_renderer_uses_policy_prominence_and_same_source_english(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )
        from tools.experiment8.osm_global_waterway_store import (
            ingest_global_waterway_closure,
            iter_exact_waterway_features,
        )
        from tools.experiment8.reference_presentation_policy import SemanticSubtype
        from tools.experiment8.sourced_text import LayoutMode

        binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        with tempfile.TemporaryDirectory() as temporary:
            result = ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=Path(temporary),
                source_binding=binding,
                checkpoint_objects=5,
            )
            exact = tuple(
                iter_exact_waterway_features(result.database_path, source_binding=binding)
            )
        rendered = {
            (feature.source_kind, feature.source_id): build_adaptive_waterway_feature(
                feature=feature,
                source_generation_sha256=binding.planet_sha256,
                classifier_sha256=classifier_identity_sha256(),
                zooms=tuple(range(4, 12)),
            )
            for feature in exact
        }

        expected_subtypes = {
            ("way", 100): SemanticSubtype.RIVER,
            ("way", 102): SemanticSubtype.STREAM_CREEK,
            ("way", 103): SemanticSubtype.CANAL_CHANNEL,
            ("way", 104): SemanticSubtype.CANAL_CHANNEL,
            ("way", 105): SemanticSubtype.UNSPECIFIED_WATERCOURSE,
            ("relation", 200): SemanticSubtype.RIVER,
        }
        self.assertEqual(
            expected_subtypes,
            {key: feature.semantic_subtype for key, feature in rendered.items()},
        )
        relation = rendered[("relation", 200)]
        stream = rendered[("way", 102)]
        self.assertEqual(5, min(tile.z for tile in relation.tiles))
        self.assertEqual(8, min(tile.z for tile in stream.tiles))
        relation_record = next(iter(next(iter(relation.tiles.values()))))
        self.assertEqual(LayoutMode.PRIMARY_WITH_ENGLISH, relation_record.sourced_text.layout_mode)
        self.assertEqual("Великая река", relation_record.sourced_text.primary_text)
        self.assertEqual("Great River", relation_record.sourced_text.english_text)
        self.assertEqual(
            set(range(5, 12)), set(relation.variant_point_counts_by_zoom)
        )
        self.assertLessEqual(
            relation.variant_point_counts_by_zoom[5],
            relation.variant_point_counts_by_zoom[11],
        )
        canal_variant = next(
            record.renderer_record.variant
            for records in rendered[("way", 103)].tiles.values()
            for record in records
        )
        tidal_variant = next(
            record.renderer_record.variant
            for records in rendered[("way", 104)].tiles.values()
            for record in records
        )
        self.assertNotEqual(
            canal_variant.source_style_layer_ids,
            tidal_variant.source_style_layer_ids,
        )

    def test_positive_sub_meter_path_keeps_exact_geometry_with_one_meter_prominence(
        self,
    ) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )

        feature = ExactWaterwayFeature(
            source_kind="way",
            source_id=5_148_596,
            source_version=1,
            source_timestamp="2026-06-29T00:00:00Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="River Tove",
            english_name=None,
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(35_695_846, -10_313_541, 521_283_820),
                ExactWaterwayPoint(35_695_848, -10_313_494, 521_283_843),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=hashlib.sha256(
                b"real River Tove way 5148596"
            ).digest(),
        )

        rendered = build_adaptive_waterway_feature(
            feature=feature,
            source_generation_sha256="1" * 64,
            classifier_sha256=classifier_identity_sha256(),
            zooms=tuple(range(4, 12)),
        )

        self.assertEqual(1, rendered.complete_length_m)
        self.assertTrue(rendered.tiles)

    def test_truly_zero_distance_path_still_fails_closed(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )

        feature = ExactWaterwayFeature(
            source_kind="way",
            source_id=9,
            source_version=1,
            source_timestamp="2026-06-29T00:00:00Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="Zero River",
            english_name=None,
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(1, -10_313_541, 521_283_820),
                ExactWaterwayPoint(2, -10_313_541, 521_283_820),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=hashlib.sha256(b"zero-distance fixture").digest(),
        )

        with self.assertRaisesRegex(
            GlobalWaterwayPackageError,
            "zero geographic length",
        ):
            build_adaptive_waterway_feature(
                feature=feature,
                source_generation_sha256="1" * 64,
                classifier_sha256=classifier_identity_sha256(),
                zooms=tuple(range(4, 12)),
            )

    def test_mixed_script_name_en_is_retained_as_an_honest_gap(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )
        from tools.experiment8.sourced_text import EnglishGapReason, LayoutMode

        feature = ExactWaterwayFeature(
            source_kind="way",
            source_id=22_734_030,
            source_version=1,
            source_timestamp="2026-06-29T00:00:00Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="Кіші Алматы",
            english_name="Kіshі Almaty",
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(1, 768_000_000, 432_000_000),
                ExactWaterwayPoint(2, 769_000_000, 433_000_000),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=bytes.fromhex(
                "eb4eb7e313daf64b40f30591a57c982b"
                "fde68e5806bd2924905c0681c234affe"
            ),
        )

        rendered = build_adaptive_waterway_feature(
            feature=feature,
            source_generation_sha256="1" * 64,
            classifier_sha256=classifier_identity_sha256(),
            zooms=tuple(range(4, 12)),
        )
        sourced = next(
            record.sourced_text
            for records in rendered.tiles.values()
            for record in records
        )

        self.assertEqual("Кіші Алматы", sourced.primary_text)
        self.assertIsNone(sourced.english_text)
        self.assertEqual(LayoutMode.SINGLE, sourced.layout_mode)
        self.assertEqual(
            EnglishGapReason.HAS_STRONG_NON_LATIN,
            sourced.english_gap_reason,
        )
        self.assertIsNotNone(sourced.english_source_field_id)

    def test_non_nfc_primary_uses_exact_nfc_source_alternate_without_normalizing(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
            _u64_identity,
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )

        feature = ExactWaterwayFeature(
            source_kind="way",
            source_id=26_002_882,
            source_version=15,
            source_timestamp="2022-06-09T00:57:41Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="গড়াই-মধুমতি নদী",
            english_name="Gorai-Madhumati River",
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(1, -760_000_000, 390_000_000),
                ExactWaterwayPoint(2, -750_000_000, 400_000_000),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=bytes.fromhex(
                "d161b2019ea8dc5e6931e8c56abc3ee9"
                "4a64e6dd4b6f059b5aeb826e9ae1ca35"
            ),
        )

        rendered = build_adaptive_waterway_feature(
            feature=feature,
            source_generation_sha256="1" * 64,
            classifier_sha256=classifier_identity_sha256(),
            zooms=tuple(range(4, 12)),
        )
        records = tuple(
            record for tile_records in rendered.tiles.values() for record in tile_records
        )
        self.assertTrue(records)
        for record in records:
            sourced = record.sourced_text
            self.assertIsNotNone(sourced)
            self.assertEqual("Gorai-Madhumati River", sourced.primary_text)
            self.assertIsNone(sourced.english_text)
            self.assertEqual(
                _u64_identity("openstreetmap.tag.name:en"),
                sourced.primary_source_field_id,
            )
            self.assertNotIn("গড়াই-মধুমতি নদী".encode("utf-8"), sourced.canonical_bytes)
            self.assertNotIn("গড়াই-মধুমতি নদী".encode("utf-8"), sourced.canonical_bytes)
            self.assertEqual(
                feature.source_feature_sha256[:8],
                record.renderer_record.posting.feature_id.to_bytes(8, "big"),
            )

    def test_non_nfc_primary_without_exact_nfc_alternate_is_audited_and_omitted(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )

        feature = ExactWaterwayFeature(
            source_kind="way",
            source_id=1,
            source_version=1,
            source_timestamp="2026-06-29T00:00:00Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="Cafe\u0301 River",
            english_name=None,
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(1, -760_000_000, 390_000_000),
                ExactWaterwayPoint(2, -750_000_000, 400_000_000),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=hashlib.sha256(b"non-nfc fixture").digest(),
        )

        rendered = build_adaptive_waterway_feature(
            feature=feature,
            source_generation_sha256="1" * 64,
            classifier_sha256=classifier_identity_sha256(),
            zooms=tuple(range(4, 12)),
        )
        self.assertEqual({}, dict(rendered.tiles))
        audit = store._advance_renderer_text_audit(
            store._empty_renderer_text_audit(), feature
        )
        self.assertEqual(1, audit["sourceFeatures"])
        self.assertEqual(1, audit["nonNfcPrimaryFeatures"])
        self.assertEqual(0, audit["exactSourceAlternateFeatures"])
        self.assertEqual(1, audit["unrepresentableOmissions"])

    def test_non_nfc_primary_uses_deterministic_exact_language_source_alternate(self) -> None:
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
            _u64_identity,
            build_adaptive_waterway_feature,
            classifier_identity_sha256,
        )

        feature = ExactWaterwayFeature(
            source_kind="way",
            source_id=30_803_714,
            source_version=4,
            source_timestamp="2026-06-29T00:00:00Z",
            waterway_type="stream",
            name_source_key="name",
            primary_name="A\u030a",
            english_name=None,
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(1, 100_000_000, 600_000_000),
                ExactWaterwayPoint(2, 110_000_000, 610_000_000),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=hashlib.sha256(b"language alternate").digest(),
            v3_source_alternate_key="name:se",
            v3_source_alternate_name="Johka",
        )

        rendered = build_adaptive_waterway_feature(
            feature=feature,
            source_generation_sha256="1" * 64,
            classifier_sha256=classifier_identity_sha256(),
            zooms=tuple(range(4, 12)),
        )
        records = tuple(
            record for tile_records in rendered.tiles.values() for record in tile_records
        )
        self.assertTrue(records)
        for record in records:
            self.assertEqual("Johka", record.renderer_record.variant.text)
            self.assertEqual("Johka", record.sourced_text.primary_text)
            self.assertEqual(
                _u64_identity("openstreetmap.tag.name:se"),
                record.sourced_text.primary_source_field_id,
            )

    def test_source_alternate_selection_matches_frozen_census_order(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        tags = (
            ("name", "Leavá" + "s\u030c" + "johka"),
            ("int_name", "International fallback"),
            ("name:fit", "Pessijoki"),
            ("name:se", "Leavášjohka"),
            ("waterway", "river"),
        )
        self.assertEqual(
            ("name:se", "Leavášjohka"),
            store._v3_exact_source_alternate(
                tags,
                primary_key="name",
                primary_name=tags[0][1],
                english_name=None,
            ),
        )
        self.assertEqual(
            ("name:fit", "Pessijoki"),
            store._v3_exact_source_alternate(
                (
                    ("name", "Besse" + "s\u030c" + "johka"),
                    ("name:fit", "Pessijoki"),
                    ("int_name", "International fallback"),
                    ("waterway", "river"),
                ),
                primary_key="name",
                primary_name="Besse" + "s\u030c" + "johka",
                english_name=None,
            ),
        )

    def test_v3_source_alternate_rejects_replacement_and_control_scalars(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        tags = (
            ("name", "Cafe\u0301 River"),
            ("name:fr", "Rivi\ufffdre"),
            ("official_name", "River\x01Name"),
            ("waterway", "river"),
        )

        self.assertEqual(
            (None, None),
            store._v3_exact_source_alternate(
                tags,
                primary_key="name",
                primary_name=tags[0][1],
                english_name=None,
            ),
        )

    def test_renderer_text_audit_binds_exact_source_alternate_without_raw_rewrite(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_renderer import (
            ExactWaterwayFeature,
            ExactWaterwayPoint,
        )

        ordinary = ExactWaterwayFeature(
            source_kind="way",
            source_id=1,
            source_version=1,
            source_timestamp="2026-06-29T00:00:00Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="Ordinary River",
            english_name=None,
            complete_named_relation=False,
            parts=((
                ExactWaterwayPoint(1, -760_000_000, 390_000_000),
                ExactWaterwayPoint(2, -750_000_000, 400_000_000),
            ),),
            required_node_ids=frozenset(),
            source_feature_sha256=hashlib.sha256(b"ordinary fixture").digest(),
        )
        bengali = ExactWaterwayFeature(
            source_kind="way",
            source_id=26_002_882,
            source_version=15,
            source_timestamp="2022-06-09T00:57:41Z",
            waterway_type="river",
            name_source_key="name",
            primary_name="গড়াই-মধুমতি নদী",
            english_name="Gorai-Madhumati River",
            complete_named_relation=False,
            parts=ordinary.parts,
            required_node_ids=frozenset(),
            source_feature_sha256=bytes.fromhex(
                "d161b2019ea8dc5e6931e8c56abc3ee9"
                "4a64e6dd4b6f059b5aeb826e9ae1ca35"
            ),
        )

        initial = store._empty_renderer_text_audit()
        after_ordinary = store._advance_renderer_text_audit(initial, ordinary)
        final = store._advance_renderer_text_audit(after_ordinary, bengali)
        repeated = store._advance_renderer_text_audit(
            store._advance_renderer_text_audit(initial, ordinary), bengali
        )

        self.assertEqual(final, repeated)
        self.assertEqual(2, final["sourceFeatures"])
        self.assertEqual(1, final["nonNfcPrimaryFeatures"])
        self.assertEqual(1, final["exactSourceAlternateFeatures"])
        self.assertEqual(0, final["unrepresentableOmissions"])
        self.assertEqual({"name:en": 1}, final["alternateSourceFieldCounts"])
        self.assertNotEqual(initial["aggregateSha256"], final["aggregateSha256"])
        self.assertEqual(
            store._renderer_text_policy_binding()["sha256"],
            final["policySha256"],
        )
        mutated = dict(final)
        mutated["sourceFeatures"] = 3
        with self.assertRaisesRegex(
            store.GlobalWaterwayPackageError, "renderer text audit"
        ):
            store._validated_renderer_text_audit(mutated)


class GlobalWaterwayPublicationTests(unittest.TestCase):
    def test_store_cannot_mint_caller_asserted_production_truth(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8 import osm_global_waterway_store as store

        asserted = replace(
            _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE),
            planet_path=str(pipeline.EXPECTED_PLANET_PATH),
            planet_bytes=pipeline.EXPECTED_PLANET_BYTES,
            planet_sha256=pipeline.EXPECTED_PLANET_SHA256,
        )
        self.assertNotIn("render_global_waterway_package", store.__all__)
        self.assertFalse(hasattr(store, "render_global_waterway_package"))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(
                pipeline.GlobalWaterwayPackageError, "fixture://"
            ):
                store.render_fixture_global_waterway_package(
                    opl_path=CLOSURE_FIXTURE,
                    root_ids_path=ROOT_FIXTURE,
                    output_directory=root / "asserted-production",
                    work_directory=root / "work",
                    package_id="asserted-production",
                    source_binding=asserted,
                    checkpoint_objects=4,
                    checkpoint_features=2,
                )

    def _build(
        self,
        root: Path,
        name: str,
        *,
        pause_after_features: int | None = None,
        render_workers: int = 1,
        progress_file: Path | None = None,
        checkpoint_features: int = 2,
        size_policy_mode: str = "budgeted-release-v1",
    ):
        from tools.experiment8.osm_global_waterway_package import (
            render_fixture_global_waterway_package,
        )

        return render_fixture_global_waterway_package(
            opl_path=CLOSURE_FIXTURE,
            root_ids_path=ROOT_FIXTURE,
            output_directory=root / name,
            work_directory=root / f"{name}-work",
            package_id="fixture-global-waterways-v3",
            source_binding=_source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE),
            checkpoint_objects=4,
            checkpoint_features=checkpoint_features,
            pause_after_features=pause_after_features,
            render_workers=render_workers,
            progress_file=progress_file,
            size_policy_mode=size_policy_mode,
        )

    def test_progress_schema_and_worker_change_preserve_exact_package_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            progress = evidence / "progress.json"
            paused = self._build(
                root,
                "resumed",
                pause_after_features=3,
                render_workers=2,
                progress_file=progress,
                checkpoint_features=100,
            )
            self.assertEqual("paused", paused.state)
            raw = progress.read_bytes()
            document = json.loads(raw)
            self.assertEqual(
                {
                    "checkpointFeatures",
                    "committedFeatures",
                    "elapsedNanoseconds",
                    "hardBounds",
                    "renderRunIdentitySha256",
                    "schema",
                    "throughputFeaturesPerSecond",
                    "totalAdmittedFeatures",
                    "updatedUtc",
                    "workerCount",
                },
                set(document),
            )
            self.assertEqual(
                "flightalert.experiment8.waterway-render-progress.v1",
                document["schema"],
            )
            self.assertEqual(paused.receipt["runIdentitySha256"], document["renderRunIdentitySha256"])
            self.assertEqual(100, document["checkpointFeatures"])
            self.assertEqual(3, document["committedFeatures"])
            self.assertEqual(6, document["totalAdmittedFeatures"])
            self.assertEqual(2, document["workerCount"])
            self.assertIs(type(document["elapsedNanoseconds"]), int)
            self.assertGreaterEqual(document["elapsedNanoseconds"], 0)
            self.assertIsNot(type(document["throughputFeaturesPerSecond"]), bool)
            self.assertGreaterEqual(document["throughputFeaturesPerSecond"], 0)
            self.assertTrue(document["updatedUtc"].endswith("Z"))
            self.assertEqual(
                {
                    "maxInFlightInputBytes": 256 * 1024 * 1024,
                    "maxInFlightJobs": 4,
                    "maxInFlightPoints": 1_048_576,
                    "maxInputBytesPerJob": 128 * 1024 * 1024,
                    "maxPointsPerJob": 524_288,
                    "maxSpoolBytes": 4 * 1024 * 1024 * 1024,
                    "maxSpoolBytesPerJob": 1024 * 1024 * 1024,
                },
                document["hardBounds"],
            )
            self.assertEqual(
                (
                    json.dumps(
                        document,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                        allow_nan=False,
                    )
                    + "\n"
                ).encode("utf-8"),
                raw,
            )

            resumed = self._build(
                root,
                "resumed",
                render_workers=1,
                progress_file=progress,
                checkpoint_features=100,
            )
            self.assertEqual("complete", resumed.state)
            completed_progress = json.loads(progress.read_bytes())
            self.assertEqual(6, completed_progress["committedFeatures"])
            self.assertEqual(1, completed_progress["workerCount"])
            self.assertEqual(
                document["renderRunIdentitySha256"],
                completed_progress["renderRunIdentitySha256"],
            )

            clean = self._build(
                root,
                "clean",
                render_workers=1,
                checkpoint_features=100,
            )
            self.assertEqual("complete", clean.state)
            self.assertEqual(
                clean.receipt["rendererSemanticStreamSha256"],
                resumed.receipt["rendererSemanticStreamSha256"],
            )
            for name in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                self.assertEqual(
                    (root / "clean" / name).read_bytes(),
                    (root / "resumed" / name).read_bytes(),
                    name,
                )

    def test_progress_resume_rejects_unknown_other_run_or_ahead_document(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "unknown-evidence"
            evidence.mkdir()
            progress = evidence / "progress.json"
            progress.write_bytes(b"unknown progress\n")
            from tools.experiment8 import osm_global_waterway_store as store

            ingest = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "unknown-work",
                source_binding=_source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE),
                checkpoint_objects=4,
            )
            database_before = ingest.database_path.read_bytes()
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "unknown|malformed|canonical"
            ):
                self._build(root, "unknown", progress_file=progress)
            self.assertEqual(b"unknown progress\n", progress.read_bytes())
            self.assertEqual(database_before, ingest.database_path.read_bytes())

        for mutation, pattern in (
            ("ahead", "ahead"),
            ("other-run", "another render run|belongs"),
            ("hard-bounds", "hard bounds|invalid"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                evidence = root / "evidence"
                evidence.mkdir()
                progress = evidence / "progress.json"
                paused = self._build(
                    root,
                    "resumed",
                    pause_after_features=3,
                    progress_file=progress,
                    checkpoint_features=100,
                )
                self.assertEqual("paused", paused.state)
                document = json.loads(progress.read_bytes())
                if mutation == "ahead":
                    document["committedFeatures"] = 4
                elif mutation == "other-run":
                    document["renderRunIdentitySha256"] = "0" * 64
                else:
                    document["hardBounds"]["maxInFlightPoints"] = 1
                    document["hardBounds"]["maxPointsPerJob"] = 999_999_999
                tampered = (
                    json.dumps(
                        document,
                        ensure_ascii=False,
                        sort_keys=True,
                        separators=(",", ":"),
                        allow_nan=False,
                    )
                    + "\n"
                ).encode("utf-8")
                progress.write_bytes(tampered)
                database = root / "resumed-work" / "waterway-state.sqlite"
                database_before = database.read_bytes()
                database_stat = database.stat()
                progress_stat = progress.stat()
                with self.assertRaisesRegex(GlobalWaterwayPackageError, pattern):
                    self._build(
                        root,
                        "resumed",
                        progress_file=progress,
                        checkpoint_features=100,
                    )
                self.assertEqual(tampered, progress.read_bytes())
                self.assertEqual(database_before, database.read_bytes())
                self.assertEqual(
                    (database_stat.st_dev, database_stat.st_ino),
                    (database.stat().st_dev, database.stat().st_ino),
                )
                self.assertEqual(
                    (progress_stat.st_dev, progress_stat.st_ino),
                    (progress.stat().st_dev, progress.stat().st_ino),
                )

    def test_every_progress_hard_bound_is_policy_authenticated(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            progress = Path(temporary) / "progress.json"
            limits = store._default_parallel_render_limits(2)
            publisher = store._RenderProgressPublisher(
                progress,
                render_run_sha256="2" * 64,
                checkpoint_features=100,
                total_admitted_features=1,
                initial_committed_features=0,
                limits=limits,
            )
            publisher.publish(0)
            valid = json.loads(progress.read_bytes())

            for key in tuple(valid["hardBounds"]):
                with self.subTest(key=key):
                    mutated = json.loads(json.dumps(valid))
                    mutated["hardBounds"][key] += 1
                    progress.write_bytes(
                        (
                            json.dumps(
                                mutated,
                                ensure_ascii=False,
                                sort_keys=True,
                                separators=(",", ":"),
                                allow_nan=False,
                            )
                            + "\n"
                        ).encode("utf-8")
                    )
                    candidate = store._RenderProgressPublisher(
                        progress,
                        render_run_sha256="2" * 64,
                        checkpoint_features=100,
                        total_admitted_features=1,
                        initial_committed_features=0,
                        limits=limits,
                    )
                    with self.assertRaisesRegex(
                        GlobalWaterwayPackageError, "hard bounds|invalid"
                    ):
                        candidate.reconcile(0)

    def test_progress_parser_normalizes_bounded_hostile_json(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        hostile_documents = (
            b'{"value":' + (b"9" * 5_000) + b"}\n",
            (b"[" * 1_100) + b"0" + (b"]" * 1_100) + b"\n",
        )
        for raw in hostile_documents:
            with self.subTest(prefix=raw[:16]), tempfile.TemporaryDirectory() as temporary:
                progress = Path(temporary) / "progress.json"
                progress.write_bytes(raw)
                publisher = store._RenderProgressPublisher(
                    progress,
                    render_run_sha256="1" * 64,
                    checkpoint_features=100,
                    total_admitted_features=0,
                    initial_committed_features=0,
                    limits=store._default_parallel_render_limits(),
                )
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "unknown|malformed"
                ):
                    publisher.reconcile(0)

    def test_pre_ingest_progress_and_pause_validation_leave_no_mutation(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for evidence_kind in ("unknown", "other-run"):
            with self.subTest(evidence=evidence_kind), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                progress = root / "progress.json"
                if evidence_kind == "unknown":
                    progress.write_bytes(b"unknown progress\n")
                else:
                    publisher = store._RenderProgressPublisher(
                        progress,
                        render_run_sha256="0" * 64,
                        checkpoint_features=2,
                        total_admitted_features=0,
                        initial_committed_features=0,
                        limits=store._default_parallel_render_limits(),
                    )
                    publisher.publish(0)
                before = progress.stat()
                before_raw = progress.read_bytes()

                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "progress|render run|SQLite|database|unknown|malformed",
                ):
                    self._build(root, "fresh", progress_file=progress)

                after = progress.stat()
                self.assertEqual(before_raw, progress.read_bytes())
                self.assertEqual((before.st_dev, before.st_ino), (after.st_dev, after.st_ino))
                self.assertEqual(before.st_mtime_ns, after.st_mtime_ns)
                self.assertFalse((root / "fresh-work").exists())
                self.assertFalse((root / "fresh").exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "pause.*maximum|pause.*range|pause.*invalid"
            ):
                self._build(
                    root,
                    "oversized-pause",
                    pause_after_features=1_000_000_000_000,
                )
            self.assertFalse((root / "oversized-pause-work").exists())
            self.assertFalse((root / "oversized-pause").exists())

    def test_progress_exact_integer_bounds_and_duplicate_guard(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            progress = root / "progress.json"
            limits = store._default_parallel_render_limits()
            publisher = store._RenderProgressPublisher(
                progress,
                render_run_sha256="3" * 64,
                checkpoint_features=1,
                total_admitted_features=1,
                initial_committed_features=0,
                limits=limits,
            )
            publisher.publish(0)
            valid = json.loads(progress.read_bytes())

            for key in ("checkpointFeatures", "totalAdmittedFeatures"):
                with self.subTest(boolean_field=key):
                    mutated = dict(valid)
                    mutated[key] = True
                    progress.write_bytes(
                        (
                            json.dumps(
                                mutated,
                                ensure_ascii=False,
                                sort_keys=True,
                                separators=(",", ":"),
                                allow_nan=False,
                            )
                            + "\n"
                        ).encode("utf-8")
                    )
                    candidate = store._RenderProgressPublisher(
                        progress,
                        render_run_sha256="3" * 64,
                        checkpoint_features=1,
                        total_admitted_features=1,
                        initial_committed_features=0,
                        limits=limits,
                    )
                    with self.assertRaisesRegex(
                        GlobalWaterwayPackageError, "checkpoint|admitted|count|malformed"
                    ):
                        candidate.reconcile(0)

            maximum = (1 << 63) - 1
            boundary_path = root / "boundary.json"
            boundary = store._RenderProgressPublisher(
                boundary_path,
                render_run_sha256="4" * 64,
                checkpoint_features=maximum,
                total_admitted_features=0,
                initial_committed_features=0,
                limits=limits,
            )
            boundary.publish(0)
            resumed_boundary = store._RenderProgressPublisher(
                boundary_path,
                render_run_sha256="4" * 64,
                checkpoint_features=maximum,
                total_admitted_features=0,
                initial_committed_features=0,
                limits=limits,
            )
            resumed_boundary.reconcile(0)
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "checkpoint.*invalid|checkpoint.*bound"
            ):
                store._RenderProgressPublisher(
                    root / "too-large.json",
                    render_run_sha256="5" * 64,
                    checkpoint_features=maximum + 1,
                    total_admitted_features=0,
                    initial_committed_features=0,
                    limits=limits,
                )

            duplicate_path = root / "duplicate.json"
            duplicate = store._RenderProgressPublisher(
                duplicate_path,
                render_run_sha256="6" * 64,
                checkpoint_features=2,
                total_admitted_features=0,
                initial_committed_features=0,
                limits=limits,
            )
            duplicate.publish(0)
            original = duplicate_path.read_bytes()
            tampered_buffer = bytearray(original)
            tampered_buffer[original.index(b"6" * 64)] = ord("7")
            tampered = bytes(tampered_buffer)
            self.assertEqual(len(original), len(tampered))
            self.assertNotEqual(original, tampered)
            with duplicate_path.open("r+b", buffering=0) as handle:
                handle.write(tampered)
                handle.flush()
                os.fsync(handle.fileno())
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "progress.*changed|bytes|identity"
            ):
                duplicate.publish(0)

    def test_progress_session_excludes_appearance_during_internal_ingest_commit(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.waterway_parallel_render import DurableProgressFile
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            progress = root / "progress.json"
            real_commit = store._progress_guarded_commit
            attempted_at_ingest_checkpoint = False

            def attempt_competing_publisher(connection, validator) -> None:
                nonlocal attempted_at_ingest_checkpoint
                checkpoint = store._meta_get(connection, "checkpoint")
                if (
                    validator is not None
                    and not attempted_at_ingest_checkpoint
                    and isinstance(checkpoint, dict)
                    and checkpoint.get("inputObjects", 0) > 0
                    and checkpoint.get("ingestComplete") is False
                ):
                    attempted_at_ingest_checkpoint = True
                    with self.assertRaisesRegex(
                        GlobalWaterwayPackageError, "session.*owned"
                    ):
                        DurableProgressFile(progress, acquire_session=True)
                    self.assertFalse(progress.exists())
                real_commit(connection, validator)

            with patch.object(
                store,
                "_progress_guarded_commit",
                side_effect=attempt_competing_publisher,
            ):
                result = self._build(root, "guarded-ingest", progress_file=progress)

            self.assertEqual("complete", result.state)
            self.assertTrue(attempted_at_ingest_checkpoint)
            self.assertTrue(progress.is_file())
            released = DurableProgressFile(progress, acquire_session=True)
            released.close()

    def test_progress_session_remains_owned_through_final_package_publish(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.waterway_parallel_render import DurableProgressFile
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            progress = evidence / "progress.json"
            real_publish = store._publish
            attempted_during_publish = False

            def probe_competing_session(*args, **kwargs):
                nonlocal attempted_during_publish
                attempted_during_publish = True
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "session.*owned"
                ):
                    DurableProgressFile(progress, acquire_session=True)
                return real_publish(*args, **kwargs)

            with patch.object(
                store,
                "_publish",
                side_effect=probe_competing_session,
            ):
                result = self._build(
                    root,
                    "guarded-publish",
                    progress_file=progress,
                )

            self.assertEqual("complete", result.state)
            self.assertTrue(attempted_during_publish)
            self.assertTrue(progress.is_file())
            released = DurableProgressFile(progress, acquire_session=True)
            released.close()

    def test_progress_publisher_closes_owned_file_but_not_injected_file(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.waterway_parallel_render import DurableProgressFile

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            limits = store._default_parallel_render_limits()
            owned = store._RenderProgressPublisher(
                root / "owned.json",
                render_run_sha256="8" * 64,
                checkpoint_features=2,
                total_admitted_features=0,
                initial_committed_features=0,
                limits=limits,
            )
            owned_close = Mock(wraps=owned._file.close)
            owned._file.close = owned_close
            owned.close()
            owned_close.assert_called_once_with()

            borrowed_file = DurableProgressFile(
                root / "borrowed.json", acquire_session=True
            )
            borrowed_close = Mock(wraps=borrowed_file.close)
            borrowed_file.close = borrowed_close
            borrowed = store._RenderProgressPublisher(
                borrowed_file.path,
                render_run_sha256="9" * 64,
                checkpoint_features=2,
                total_admitted_features=0,
                initial_committed_features=0,
                limits=limits,
                _durable_file=borrowed_file,
            )
            try:
                borrowed.close()
                borrowed_close.assert_not_called()
            finally:
                borrowed_file.close()

    def test_same_inode_progress_rewrite_is_rejected_at_fresh_resume_boundaries(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for boundary, target_preflight in (("pre-ingest", 1), ("stage", 2)):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                name = f"same-inode-{boundary}"
                evidence = root / "evidence"
                evidence.mkdir()
                progress = evidence / "progress.json"
                paused = self._build(
                    root,
                    name,
                    pause_after_features=3,
                    progress_file=progress,
                )
                self.assertEqual("paused", paused.state)
                database = root / f"{name}-work" / "waterway-state.sqlite"
                database_before = database.read_bytes()
                database_stat = database.stat()
                renderer_before = self._renderer_snapshot(database)
                connection = sqlite3.connect(database)
                try:
                    render_meta_before = tuple(
                        connection.execute(
                            "SELECT key,value FROM meta WHERE key IN "
                            "('renderRunIdentity','renderCheckpoint','peaks') "
                            "ORDER BY key"
                        )
                    )
                finally:
                    connection.close()
                progress_stat = progress.stat()
                real_preflight = store._RenderProgressPublisher.preflight
                calls = 0
                rewrote = False
                rewrite_blocked = False

                def rewrite_after_target_preflight(
                    publisher, committed_features: int
                ) -> None:
                    nonlocal calls, rewrote, rewrite_blocked
                    real_preflight(publisher, committed_features)
                    calls += 1
                    if calls != target_preflight:
                        return
                    raw = progress.read_bytes()
                    changed = bytearray(raw)
                    marker = raw.index(b'"renderRunIdentitySha256":"') + len(
                        b'"renderRunIdentitySha256":"'
                    )
                    changed[marker] = (
                        ord("0") if changed[marker] != ord("0") else ord("1")
                    )
                    try:
                        with progress.open("r+b", buffering=0) as handle:
                            handle.write(changed)
                            handle.flush()
                            os.fsync(handle.fileno())
                    except OSError as error:
                        rewrite_blocked = True
                        raise GlobalWaterwayPackageError(
                            "progress rewrite denied by session ownership"
                        ) from error
                    rewrote = True

                with patch.object(
                    store._RenderProgressPublisher,
                    "preflight",
                    new=rewrite_after_target_preflight,
                ), self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "progress.*changed|progress.*bytes|another render run|identity|"
                    "progress.*denied",
                ):
                    self._build(root, name, progress_file=progress)

                self.assertTrue(rewrote or rewrite_blocked)
                self.assertGreaterEqual(calls, target_preflight)
                self.assertEqual(
                    (progress_stat.st_dev, progress_stat.st_ino),
                    (progress.stat().st_dev, progress.stat().st_ino),
                )
                if boundary == "pre-ingest":
                    self.assertEqual(database_before, database.read_bytes())
                self.assertEqual(renderer_before, self._renderer_snapshot(database))
                connection = sqlite3.connect(database)
                try:
                    self.assertEqual(
                        render_meta_before,
                        tuple(
                            connection.execute(
                                "SELECT key,value FROM meta WHERE key IN "
                                "('renderRunIdentity','renderCheckpoint','peaks') "
                                "ORDER BY key"
                            )
                        ),
                    )
                finally:
                    connection.close()
                self.assertEqual(
                    (database_stat.st_dev, database_stat.st_ino),
                    (database.stat().st_dev, database.stat().st_ino),
                )
                self.assertFalse((root / name).exists())

    def test_commit_before_progress_failure_is_stale_behind_and_repairs_on_resume(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            progress = evidence / "progress.json"
            real_replace = parallel.DurableProgressFile.replace

            def fail_after_second_checkpoint(progress_file, raw: bytes) -> None:
                document = json.loads(raw)
                if document["committedFeatures"] == 4:
                    raise GlobalWaterwayPackageError(
                        "injected crash after SQLite commit"
                    )
                real_replace(progress_file, raw)

            with patch.object(
                parallel.DurableProgressFile,
                "replace",
                new=fail_after_second_checkpoint,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError, "injected crash"
            ):
                self._build(
                    root,
                    "crash-resume",
                    progress_file=progress,
                    checkpoint_features=2,
                )

            self.assertEqual(2, json.loads(progress.read_bytes())["committedFeatures"])
            database = root / "crash-resume-work" / "waterway-state.sqlite"
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(
                    4,
                    store._meta_get(connection, "renderCheckpoint")[
                        "renderedFeatures"
                    ],
                )
            finally:
                connection.close()
            spools = tuple((root / "crash-resume-work").glob("waterway-render-spool-*"))
            self.assertEqual(1, len(spools))
            self.assertTrue(tuple(spools[0].glob("*.batch")))

            transitions: list[int] = []

            def record_repair(progress_file, raw: bytes) -> None:
                transitions.append(json.loads(raw)["committedFeatures"])
                real_replace(progress_file, raw)

            with patch.object(
                parallel.DurableProgressFile,
                "replace",
                new=record_repair,
            ):
                resumed = self._build(
                    root,
                    "crash-resume",
                    render_workers=2,
                    progress_file=progress,
                    checkpoint_features=2,
                )
            self.assertEqual("complete", resumed.state)
            self.assertEqual([4, 6], transitions)
            self.assertEqual(6, json.loads(progress.read_bytes())["committedFeatures"])

            clean = self._build(root, "crash-clean", checkpoint_features=2)
            self.assertEqual("complete", clean.state)
            for name in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                self.assertEqual(
                    (root / "crash-clean" / name).read_bytes(),
                    (root / "crash-resume" / name).read_bytes(),
                    name,
                )

    def test_first_commit_before_progress_failure_repairs_absent_progress(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence = root / "evidence"
            evidence.mkdir()
            progress = evidence / "progress.json"
            real_replace = parallel.DurableProgressFile.replace
            failed_first_publication = False

            def fail_first_publication(progress_file, raw: bytes) -> None:
                nonlocal failed_first_publication
                document = json.loads(raw)
                if not failed_first_publication:
                    failed_first_publication = True
                    self.assertEqual(2, document["committedFeatures"])
                    raise GlobalWaterwayPackageError(
                        "injected crash before first progress publication"
                    )
                real_replace(progress_file, raw)

            with patch.object(
                parallel.DurableProgressFile,
                "replace",
                new=fail_first_publication,
            ), self.assertRaisesRegex(GlobalWaterwayPackageError, "injected crash"):
                self._build(
                    root,
                    "absent-progress-resume",
                    progress_file=progress,
                    checkpoint_features=2,
                )

            self.assertTrue(failed_first_publication)
            self.assertFalse(progress.exists())
            database = root / "absent-progress-resume-work" / "waterway-state.sqlite"
            connection = sqlite3.connect(database)
            try:
                self.assertEqual(
                    2,
                    store._meta_get(connection, "renderCheckpoint")[
                        "renderedFeatures"
                    ],
                )
            finally:
                connection.close()

            repaired_transitions: list[int] = []

            def record_repair(progress_file, raw: bytes) -> None:
                repaired_transitions.append(json.loads(raw)["committedFeatures"])
                real_replace(progress_file, raw)

            with patch.object(
                parallel.DurableProgressFile,
                "replace",
                new=record_repair,
            ):
                resumed = self._build(
                    root,
                    "absent-progress-resume",
                    progress_file=progress,
                    checkpoint_features=2,
                )

            self.assertEqual("complete", resumed.state)
            self.assertEqual([2, 4, 6], repaired_transitions)
            self.assertEqual(6, json.loads(progress.read_bytes())["committedFeatures"])

    def test_three_hundred_feature_probe_pauses_then_resumes_with_other_workers(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8 import waterway_parallel_render as parallel

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "probe-closure.opl"
            roots = root / "probe-root-ids.txt"
            opl_lines: list[str] = []
            root_lines: list[str] = []
            for ordinal in range(301):
                first_node = ordinal * 2 + 1
                second_node = first_node + 1
                longitude = -120.0 + ordinal / 10_000
                latitude = 35.0 + (ordinal % 100) / 10_000
                opl_lines.extend(
                    (
                        f"n{first_node} v1 dV t2026-06-29T00:00:00Z "
                        f"x{longitude:.7f} y{latitude:.7f}",
                        f"n{second_node} v1 dV t2026-06-29T00:00:00Z "
                        f"x{longitude + 0.0001:.7f} y{latitude + 0.0001:.7f}",
                    )
                )
            for ordinal in range(301):
                way_id = 100_000 + ordinal
                first_node = ordinal * 2 + 1
                opl_lines.append(
                    f"w{way_id} v1 dV t2026-06-29T00:01:00Z "
                    f"Tname=Probe%20%River%20%{ordinal},waterway=river "
                    f"Nn{first_node},n{first_node + 1}"
                )
                root_lines.append(f"w{way_id}")
            opl.write_text("\n".join(opl_lines) + "\n", encoding="utf-8", newline="\n")
            roots.write_text(
                "\n".join(root_lines) + "\n", encoding="ascii", newline="\n"
            )
            binding = _source_binding(opl, roots)
            evidence = root / "evidence"
            evidence.mkdir()
            progress = evidence / "progress.json"
            real_replace = parallel.DurableProgressFile.replace
            first_transitions: list[int] = []

            def record_first(progress_file, raw: bytes) -> None:
                first_transitions.append(json.loads(raw)["committedFeatures"])
                real_replace(progress_file, raw)

            with patch.object(
                parallel.DurableProgressFile,
                "replace",
                new=record_first,
            ):
                paused = pipeline.render_fixture_global_waterway_package(
                    opl_path=opl,
                    root_ids_path=roots,
                    output_directory=root / "resumed",
                    work_directory=root / "resumed-work",
                    package_id="fixture-300-feature-waterway-probe",
                    source_binding=binding,
                    checkpoint_objects=1_000,
                    checkpoint_features=100,
                    pause_after_features=300,
                    render_workers=1,
                    progress_file=progress,
                )
            self.assertEqual("paused", paused.state)
            self.assertEqual([100, 200, 300], first_transitions)
            paused_progress = json.loads(progress.read_bytes())
            self.assertEqual(300, paused_progress["committedFeatures"])
            self.assertEqual(301, paused_progress["totalAdmittedFeatures"])
            self.assertEqual(1, paused_progress["workerCount"])

            resume_transitions: list[int] = []

            def record_resume(progress_file, raw: bytes) -> None:
                resume_transitions.append(json.loads(raw)["committedFeatures"])
                real_replace(progress_file, raw)

            with patch.object(
                parallel.DurableProgressFile,
                "replace",
                new=record_resume,
            ):
                resumed = pipeline.render_fixture_global_waterway_package(
                    opl_path=opl,
                    root_ids_path=roots,
                    output_directory=root / "resumed",
                    work_directory=root / "resumed-work",
                    package_id="fixture-300-feature-waterway-probe",
                    source_binding=binding,
                    checkpoint_objects=1_000,
                    checkpoint_features=100,
                    render_workers=2,
                    progress_file=progress,
                )
            self.assertEqual("complete", resumed.state)
            self.assertEqual([301], resume_transitions)
            completed_progress = json.loads(progress.read_bytes())
            self.assertEqual(301, completed_progress["committedFeatures"])
            self.assertEqual(2, completed_progress["workerCount"])

            clean = pipeline.render_fixture_global_waterway_package(
                opl_path=opl,
                root_ids_path=roots,
                output_directory=root / "clean",
                work_directory=root / "clean-work",
                package_id="fixture-300-feature-waterway-probe",
                source_binding=binding,
                checkpoint_objects=1_000,
                checkpoint_features=100,
                render_workers=2,
            )
            self.assertEqual("complete", clean.state)
            self.assertEqual(
                clean.receipt["rendererSemanticStreamSha256"],
                resumed.receipt["rendererSemanticStreamSha256"],
            )
            for name in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
            ):
                self.assertEqual(
                    (root / "clean" / name).read_bytes(),
                    (root / "resumed" / name).read_bytes(),
                    name,
                )

    @staticmethod
    def _parallel_limits(workers: int, *, max_in_flight_jobs: int | None = None):
        from tools.experiment8.waterway_parallel_render import ParallelRenderLimits

        jobs = max_in_flight_jobs or workers * 2
        return ParallelRenderLimits(
            workers=workers,
            max_in_flight_jobs=jobs,
            max_in_flight_points=100_000_000,
            max_points_per_job=1,
            max_in_flight_input_bytes=jobs * 128 * 1024 * 1024,
            max_input_bytes_per_job=128 * 1024 * 1024,
            max_spool_bytes=(jobs + 1) * 64 * 1024 * 1024,
            max_spool_bytes_per_job=64 * 1024 * 1024,
        )

    @staticmethod
    def _renderer_snapshot(database: Path):
        import sqlite3

        connection = sqlite3.connect(database)
        try:
            records = tuple(
                connection.execute(
                    "SELECT z,y,x,posting_key,draw_order,priority,layer_group,"
                    "feature_kind,variant_id,feature_id,sourced_sha,envelope,subtype,"
                    "source_type FROM records ORDER BY z,y,x,posting_key"
                )
            )
            rendered = tuple(
                connection.execute(
                    "SELECT ordinal,source_kind,source_id,source_type,feature_sha "
                    "FROM rendered_features ORDER BY ordinal"
                )
            )
            identities = {
                table: tuple(
                    connection.execute(
                        f"SELECT hot_id,full_sha FROM {table} ORDER BY hot_id"
                    )
                )
                for table in (
                    "feature_ids",
                    "geometry_ids",
                    "label_ids",
                    "variant_ids",
                    "sourced_ids",
                )
            }
            return records, rendered, identities
        finally:
            connection.close()

    def _build_with_parallel(
        self,
        root: Path,
        name: str,
        *,
        limits,
        executor_factory=None,
        staged_ordinals: list[int] | None = None,
    ):
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel

        contexts = [
            patch.object(
                store,
                "_default_parallel_render_limits",
                return_value=limits,
                create=True,
            )
        ]
        if executor_factory is not None:
            real_renderer = parallel.ParallelFeatureRenderer

            def reverse_renderer(*args, **kwargs):
                kwargs["executor_factory"] = executor_factory
                kwargs["wait_for_futures"] = executor_factory.wait
                kwargs["disk_usage"] = lambda _path: type(
                    "Usage", (), {"free": 100_000_000_000}
                )()
                return real_renderer(*args, **kwargs)

            contexts.append(
                patch.object(
                    parallel,
                    "ParallelFeatureRenderer",
                    side_effect=reverse_renderer,
                )
            )
        if staged_ordinals is not None:
            real_stage = getattr(store, "_stage_feature_render_frame", None)

            def record_stage(*args, **kwargs):
                staged_ordinals.append(kwargs["ordinal"])
                return real_stage(*args, **kwargs)

            contexts.append(
                patch.object(
                    store,
                    "_stage_feature_render_frame",
                    side_effect=record_stage,
                    create=True,
                )
            )
        with contexts[0]:
            if len(contexts) == 1:
                return self._build(root, name)
            with contexts[1]:
                if len(contexts) == 2:
                    return self._build(root, name)
                with contexts[2]:
                    return self._build(root, name)

    def test_renderer_staging_uses_one_connection_across_dirty_cache_spill(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_renderer import (
            classifier_identity_sha256,
        )

        source_binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        zooms = tuple(range(4, 12))
        checkpoint_features = 2
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ingest = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "work",
                source_binding=source_binding,
                checkpoint_objects=4,
            )
            self.assertEqual("complete", ingest.state)
            self.assertGreaterEqual(
                len(
                    tuple(
                        store.iter_exact_waterway_features(
                            ingest.database_path,
                            source_binding=source_binding,
                        )
                    )
                ),
                2,
            )
            render_identity = store._render_run_identity(
                package_id="fixture-global-waterways-v3",
                source_binding=source_binding,
                zooms=zooms,
                checkpoint_features=checkpoint_features,
                code_identities=store._render_code_identities(),
                classifier_sha256=classifier_identity_sha256(),
                admission_receipt=ingest.receipt["admission"],
                ingest_semantic_sha256=ingest.receipt["semanticSha256"],
            )

            real_connect = sqlite3.connect

            def immediate_connect(*args, **kwargs):
                kwargs.setdefault("timeout", 0.0)
                return real_connect(*args, **kwargs)

            writer = real_connect(ingest.database_path)
            try:
                writer.execute("PRAGMA journal_mode=DELETE")
                writer.execute("PRAGMA synchronous=FULL")
                writer.execute("PRAGMA temp_store=FILE")
                writer.execute("PRAGMA mmap_size=0")
                writer.execute("PRAGMA cache_size=1")
                writer.execute("PRAGMA cache_spill=1")
                with patch.object(
                    store.sqlite3, "connect", side_effect=immediate_connect
                ):
                    old_topology = store.iter_exact_waterway_features(
                        ingest.database_path,
                        source_binding=source_binding,
                    )
                    try:
                        next(old_topology)
                        writer.execute(
                            "CREATE TABLE renderer_lock_probe(payload BLOB NOT NULL)"
                        )
                        writer.execute(
                            "INSERT INTO renderer_lock_probe(payload) VALUES (?)",
                            (sqlite3.Binary(b"\0" * (1024 * 1024)),),
                        )
                        with self.assertRaisesRegex(
                            sqlite3.OperationalError, "database is locked"
                        ):
                            next(old_topology)
                    finally:
                        old_topology.close()
                        writer.rollback()

                    complete = store._stage_renderer_records(
                        writer,
                        ingest.database_path,
                        source_binding=source_binding,
                        zooms=zooms,
                        checkpoint_features=checkpoint_features,
                        pause_after_features=None,
                        run_identity=render_identity,
                    )
                checkpoint = store._meta_get(writer, "renderCheckpoint")
                self.assertTrue(complete)
                self.assertTrue(checkpoint["renderComplete"])
                self.assertGreaterEqual(checkpoint["renderedFeatures"], 2)
                self.assertTrue(writer.in_transaction)
                writer.rollback()
            finally:
                writer.close()

    def test_render_writer_uses_exact_runtime_cache_contract(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel

        render_connections = []

        class RecordingConnection(sqlite3.Connection):
            def close(self) -> None:
                try:
                    query_only = int(self.execute("PRAGMA query_only").fetchone()[0])
                    render_identity = self.execute(
                        "SELECT 1 FROM meta WHERE key='renderRunIdentity'"
                    ).fetchone()
                    if query_only == 0 and render_identity is not None:
                        render_connections.append(
                            {
                                pragma: self.execute(f"PRAGMA {pragma}").fetchone()[0]
                                for pragma in (
                                    "journal_mode",
                                    "synchronous",
                                    "temp_store",
                                    "mmap_size",
                                    "cache_size",
                                )
                            }
                        )
                finally:
                    super().close()

        real_connect = sqlite3.connect

        def recording_connect(*args, **kwargs):
            return real_connect(*args, factory=RecordingConnection, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(
                store.sqlite3, "connect", side_effect=recording_connect
            ):
                result = self._build(Path(temporary), "runtime")

        self.assertEqual("complete", result.state)
        self.assertEqual(
            [
                {
                    "journal_mode": "delete",
                    "synchronous": 2,
                    "temp_store": 1,
                    "mmap_size": 0,
                    "cache_size": -65536,
                }
            ],
            render_connections,
        )
        self.assertEqual(1, len(render_connections))
        self.assertNotIn("sqlite3", parallel.__dict__)

    def test_parallel_staging_uses_exactly_one_parent_sqlite_connection(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_renderer import (
            classifier_identity_sha256,
        )

        source_binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        zooms = tuple(range(4, 12))
        checkpoint_features = 2
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ingest = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "work",
                source_binding=source_binding,
                checkpoint_objects=4,
            )
            render_identity = store._render_run_identity(
                package_id="fixture-global-waterways-v3",
                source_binding=source_binding,
                zooms=zooms,
                checkpoint_features=checkpoint_features,
                code_identities=store._render_code_identities(),
                classifier_sha256=classifier_identity_sha256(),
                admission_receipt=ingest.receipt["admission"],
                ingest_semantic_sha256=ingest.receipt["semanticSha256"],
            )
            real_connect = sqlite3.connect
            parent_connects = []

            def recording_connect(*args, **kwargs):
                parent_connects.append((args, kwargs))
                return real_connect(*args, **kwargs)

            with patch.object(
                store.sqlite3,
                "connect",
                side_effect=recording_connect,
            ):
                connection = store.sqlite3.connect(ingest.database_path)
                try:
                    store._configure_render_connection(connection)
                    complete = store._stage_renderer_records(
                        connection,
                        ingest.database_path,
                        source_binding=source_binding,
                        zooms=zooms,
                        checkpoint_features=checkpoint_features,
                        pause_after_features=None,
                        run_identity=render_identity,
                        parallel_limits=self._parallel_limits(1),
                        spool_directory=root / "spool",
                    )
                finally:
                    connection.close()

        self.assertTrue(complete)
        self.assertEqual(1, len(parent_connects), parent_connects)

    def test_workers_one_two_and_reverse_completion_are_byte_and_table_identical(self) -> None:
        from tools.experiment8.tests.test_waterway_parallel_render import (
            _ReverseExecutorFactory,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            one = self._build_with_parallel(
                root,
                "workers-one",
                limits=self._parallel_limits(1),
            )
            two = self._build_with_parallel(
                root,
                "workers-two",
                limits=self._parallel_limits(2),
            )
            staged_ordinals: list[int] = []
            reverse = self._build_with_parallel(
                root,
                "workers-reverse",
                limits=self._parallel_limits(2),
                executor_factory=_ReverseExecutorFactory(),
                staged_ordinals=staged_ordinals,
            )

            self.assertEqual(
                list(range(len(staged_ordinals))),
                staged_ordinals,
            )
            self.assertTrue(staged_ordinals)
            self.assertEqual(
                one.receipt["rendererSemanticStreamSha256"],
                two.receipt["rendererSemanticStreamSha256"],
            )
            self.assertEqual(
                one.receipt["rendererSemanticStreamSha256"],
                reverse.receipt["rendererSemanticStreamSha256"],
            )
            for filename in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                expected = (root / "workers-one" / filename).read_bytes()
                self.assertEqual(
                    expected,
                    (root / "workers-two" / filename).read_bytes(),
                    filename,
                )
                self.assertEqual(
                    expected,
                    (root / "workers-reverse" / filename).read_bytes(),
                    filename,
                )
            expected_snapshot = self._renderer_snapshot(
                root / "workers-one-work" / "waterway-state.sqlite"
            )
            self.assertEqual(
                expected_snapshot,
                self._renderer_snapshot(
                    root / "workers-two-work" / "waterway-state.sqlite"
                ),
            )
            self.assertEqual(
                expected_snapshot,
                self._renderer_snapshot(
                    root / "workers-reverse-work" / "waterway-state.sqlite"
                ),
            )

    def test_failure_before_checkpoint_rolls_back_every_staged_feature(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )
        from tools.experiment8.osm_global_waterway_renderer import (
            classifier_identity_sha256,
        )

        source_binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        zooms = tuple(range(4, 12))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            ingest = store.ingest_global_waterway_closure(
                opl_path=CLOSURE_FIXTURE,
                root_ids_path=ROOT_FIXTURE,
                work_directory=root / "work",
                source_binding=source_binding,
                checkpoint_objects=4,
            )
            exact_features = tuple(
                store.iter_exact_waterway_features(
                    ingest.database_path,
                    source_binding=source_binding,
                )
            )
            checkpoint_features = len(exact_features) + 1
            identity = store._render_run_identity(
                package_id="fixture-global-waterways-v3",
                source_binding=source_binding,
                zooms=zooms,
                checkpoint_features=checkpoint_features,
                code_identities=store._render_code_identities(),
                classifier_sha256=classifier_identity_sha256(),
                admission_receipt=ingest.receipt["admission"],
                ingest_semantic_sha256=ingest.receipt["semanticSha256"],
            )
            real_stage = store._stage_feature_render_frame

            def fail_final_feature(*args, **kwargs):
                if kwargs["ordinal"] == len(exact_features) - 1:
                    raise GlobalWaterwayPackageError("forced final feature failure")
                return real_stage(*args, **kwargs)

            connection = sqlite3.connect(ingest.database_path)
            try:
                store._configure_render_connection(connection)
                with patch.object(
                    store,
                    "_stage_feature_render_frame",
                    side_effect=fail_final_feature,
                ), self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "forced final feature failure",
                ):
                    store._stage_renderer_records(
                        connection,
                        ingest.database_path,
                        source_binding=source_binding,
                        zooms=zooms,
                        checkpoint_features=checkpoint_features,
                        pause_after_features=None,
                        run_identity=identity,
                        parallel_limits=self._parallel_limits(1),
                        spool_directory=root / "spool",
                    )
                self.assertFalse(connection.in_transaction)
                self.assertEqual(
                    0,
                    connection.execute(
                        "SELECT COUNT(*) FROM rendered_features"
                    ).fetchone()[0],
                )
                self.assertEqual(
                    0,
                    connection.execute("SELECT COUNT(*) FROM records").fetchone()[0],
                )
                self.assertEqual(
                    0,
                    store._meta_get(connection, "renderCheckpoint")[
                        "renderedFeatures"
                    ],
                )
                self.assertTrue((root / "spool" / "owner.json").is_file())
            finally:
                connection.close()

    def test_interior_reducer_collisions_preserve_checkpoint_and_later_spools(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel
        from tools.experiment8.osm_global_waterway_renderer import (
            classifier_identity_sha256,
        )
        from tools.experiment8.tests.test_waterway_parallel_render import (
            _ReverseExecutorFactory,
        )

        collision_targets = (
            "registry_claims",
            "feature_ids",
            "geometry_ids",
            "label_ids",
            "variant_ids",
            "sourced_ids",
        )
        source_binding = _source_binding(CLOSURE_FIXTURE, ROOT_FIXTURE)
        zooms = tuple(range(4, 12))
        checkpoint_features = 2
        for collision_target in collision_targets:
            with self.subTest(collision_target=collision_target), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                ingest = store.ingest_global_waterway_closure(
                    opl_path=CLOSURE_FIXTURE,
                    root_ids_path=ROOT_FIXTURE,
                    work_directory=root / "work",
                    source_binding=source_binding,
                    checkpoint_objects=4,
                )
                render_identity = store._render_run_identity(
                    package_id="fixture-global-waterways-v3",
                    source_binding=source_binding,
                    zooms=zooms,
                    checkpoint_features=checkpoint_features,
                    code_identities=store._render_code_identities(),
                    classifier_sha256=classifier_identity_sha256(),
                    admission_receipt=ingest.receipt["admission"],
                    ingest_semantic_sha256=ingest.receipt["semanticSha256"],
                )
                spool = root / "spool"
                connection = sqlite3.connect(ingest.database_path)
                try:
                    store._configure_render_connection(connection)
                    paused = store._stage_renderer_records(
                        connection,
                        ingest.database_path,
                        source_binding=source_binding,
                        zooms=zooms,
                        checkpoint_features=checkpoint_features,
                        pause_after_features=2,
                        run_identity=render_identity,
                        parallel_limits=self._parallel_limits(
                            1,
                            max_in_flight_jobs=1,
                        ),
                        spool_directory=spool,
                    )
                    self.assertFalse(paused)
                    checkpoint_before = store._meta_get(
                        connection,
                        "renderCheckpoint",
                    )
                    snapshot_before = self._renderer_snapshot(ingest.database_path)
                    completed_ranges = []

                    def remember_completion(job, descriptor):
                        completed_ranges.append(
                            (
                                job.start_ordinal,
                                descriptor.end_ordinal_exclusive,
                            )
                        )

                    factory = _ReverseExecutorFactory(
                        completion_hook=remember_completion
                    )
                    real_renderer = parallel.ParallelFeatureRenderer

                    def reverse_renderer(*args, **kwargs):
                        kwargs["executor_factory"] = factory
                        kwargs["wait_for_futures"] = factory.wait
                        kwargs["disk_usage"] = lambda _path: type(
                            "Usage", (), {"free": 100_000_000_000}
                        )()
                        return real_renderer(*args, **kwargs)

                    real_stage = store._stage_feature_render_frame

                    def inject_collision(*args, **kwargs):
                        if kwargs["ordinal"] == 3:
                            self.assertTrue(
                                any(start > 3 for start, _end in completed_ranges),
                                completed_ranges,
                            )
                            if collision_target == "registry_claims":
                                registry = kwargs["registry"]
                                target_claim = next(
                                    claim
                                    for claim in kwargs["frame"].registry_claims
                                    if (
                                        claim.full_sha256,
                                        claim.domain,
                                        claim.canonical_bytes,
                                    )
                                    not in registry._entries.values()
                                )
                                collision_hot = next(iter(registry._entries))
                                target_preimage = (
                                    target_claim.domain
                                    + target_claim.canonical_bytes
                                )

                                def colliding_digest(data: bytes) -> bytes:
                                    normal = hashlib.sha256(data).digest()
                                    if data == target_preimage:
                                        return (
                                            collision_hot.to_bytes(8, "big")
                                            + normal[8:]
                                        )
                                    return normal

                                registry._digest_function = colliding_digest
                            else:
                                table, hot_id, full_sha = next(
                                    identity
                                    for identity in kwargs["frame"].identity_rows
                                    if identity[0] == collision_target
                                )
                                conflicting_sha = bytes((full_sha[0] ^ 1,)) + full_sha[1:]
                                stage_connection = (
                                    args[0] if args else kwargs["connection"]
                                )
                                stage_connection.execute(
                                    f"INSERT OR REPLACE INTO {table}(hot_id,full_sha) VALUES (?,?)",
                                    (hot_id, conflicting_sha),
                                )
                        return real_stage(*args, **kwargs)

                    with patch.object(
                        parallel,
                        "ParallelFeatureRenderer",
                        side_effect=reverse_renderer,
                    ), patch.object(
                        store,
                        "_stage_feature_render_frame",
                        side_effect=inject_collision,
                    ), self.assertRaisesRegex(Exception, "collision"):
                        store._stage_renderer_records(
                            connection,
                            ingest.database_path,
                            source_binding=source_binding,
                            zooms=zooms,
                            checkpoint_features=checkpoint_features,
                            pause_after_features=None,
                            run_identity=render_identity,
                            parallel_limits=self._parallel_limits(
                                2,
                                max_in_flight_jobs=4,
                            ),
                            spool_directory=spool,
                        )

                    self.assertFalse(connection.in_transaction)
                    self.assertEqual(
                        checkpoint_before,
                        store._meta_get(connection, "renderCheckpoint"),
                    )
                    self.assertEqual(
                        snapshot_before,
                        self._renderer_snapshot(ingest.database_path),
                    )
                    self.assertTrue(
                        any(start > 3 for start, _end in completed_ranges),
                        completed_ranges,
                    )
                    self.assertTrue(
                        any(
                            path.suffix == ".batch"
                            and int(path.name[:12]) > 3
                            for path in spool.iterdir()
                        ),
                        tuple(path.name for path in spool.iterdir()),
                    )
                finally:
                    connection.close()

    def test_corrupt_later_spool_keeps_prior_checkpoint_and_resume_matches_clean(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )
        from tools.experiment8.tests.test_waterway_parallel_render import (
            _ReverseExecutorFactory,
        )

        corrupted = False

        def corrupt_after_first_checkpoint(job, descriptor):
            nonlocal corrupted
            if corrupted or job.start_ordinal < 2:
                return
            corrupted = True
            spool = Path(job.spool_directory) / descriptor.file_name
            with spool.open("r+b") as handle:
                handle.seek(-1, 2)
                last = handle.read(1)
                handle.seek(-1, 2)
                handle.write(bytes((last[0] ^ 0x01,)))
                handle.flush()

        factory = _ReverseExecutorFactory(completion_hook=corrupt_after_first_checkpoint)
        limits = self._parallel_limits(1, max_in_flight_jobs=1)
        real_renderer = parallel.ParallelFeatureRenderer

        def corrupting_renderer(*args, **kwargs):
            kwargs["executor_factory"] = factory
            kwargs["wait_for_futures"] = factory.wait
            kwargs["disk_usage"] = lambda _path: type(
                "Usage", (), {"free": 100_000_000_000}
            )()
            return real_renderer(*args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            observed_error = None
            with patch.object(
                store,
                "_default_parallel_render_limits",
                return_value=limits,
                create=True,
            ), patch.object(
                parallel,
                "ParallelFeatureRenderer",
                side_effect=corrupting_renderer,
            ):
                try:
                    self._build(root, "resumed")
                except GlobalWaterwayPackageError as error:
                    observed_error = error

            self.assertIsNotNone(observed_error)
            self.assertRegex(
                str(observed_error),
                "SHA-256|identity|spool|feature frame|posting-byte",
            )
            self.assertTrue(corrupted, repr(observed_error))
            database = root / "resumed-work" / "waterway-state.sqlite"
            connection = sqlite3.connect(database)
            try:
                checkpoint = store._meta_get(connection, "renderCheckpoint")
                self.assertEqual(2, checkpoint["renderedFeatures"])
                self.assertEqual(
                    2,
                    connection.execute(
                        "SELECT COUNT(*) FROM rendered_features"
                    ).fetchone()[0],
                )
            finally:
                connection.close()

            resumed = self._build_with_parallel(
                root,
                "resumed",
                limits=self._parallel_limits(1),
            )
            clean = self._build_with_parallel(
                root,
                "clean",
                limits=self._parallel_limits(1),
            )
            self.assertEqual("complete", resumed.state)
            self.assertEqual("complete", clean.state)
            for filename in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                self.assertEqual(
                    (root / "clean" / filename).read_bytes(),
                    (root / "resumed" / filename).read_bytes(),
                    filename,
                )

    def test_fixture_publication_is_real_v3_typed_source_honest_and_deterministic(self) -> None:
        from tools.experiment8.reference_presentation_policy import SemanticSubtype
        from tools.experiment8.sourced_text import LayoutMode

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = self._build(root, "first")
            second = self._build(root, "second")

            self.assertEqual("complete", first.state)
            self.assertEqual("complete", second.state)
            for filename in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                self.assertEqual(
                    (root / "first" / filename).read_bytes(),
                    (root / "second" / filename).read_bytes(),
                    filename,
                )
            manifest = json.loads((root / "first" / "manifest.json").read_text("utf-8"))
            self.assertEqual(3, manifest["schemaVersion"])
            self.assertFalse(manifest["coverage"]["completeDeclaredScope"])
            self.assertFalse(manifest["coverage"]["completeWholeEarthDictionary"])
            self.assertNotIn("classCatalog", manifest)
            supplement = manifest["globalWaterwaySupplement"]
            self.assertTrue(supplement["fixtureOnly"])
            self.assertEqual(list(range(4, 12)), supplement["requestedZooms"])
            self.assertEqual(
                "OpenStreetMap contributors", supplement["attribution"]["credit"]
            )
            self.assertIn("extraction receipt", supplement["attribution"]["sourceOffer"])
            text_audit = supplement["rendererTextAudit"]
            self.assertEqual(
                first.receipt["rendererTextAudit"],
                text_audit,
            )
            self.assertEqual(
                first.receipt["build"]["runIdentity"]["rendererTextPolicy"],
                {
                    "document": supplement["rendererTextPolicy"]["document"],
                    "sha256": supplement["rendererTextPolicy"]["sha256"],
                },
            )
            self.assertEqual(0, text_audit["nonNfcPrimaryFeatures"])
            self.assertEqual(0, text_audit["exactSourceAlternateFeatures"])
            self.assertEqual(0, text_audit["unrepresentableOmissions"])
            self.assertGreater(text_audit["sourceFeatures"], 0)
            self.assertEqual(
                {
                    "canal": {"filterId": "labels.canals", "semanticSubtype": 350},
                    "river": {"filterId": "labels.rivers", "semanticSubtype": 330},
                    "stream": {"filterId": "labels.streams", "semanticSubtype": 340},
                    "tidal_channel": {"filterId": "labels.canals", "semanticSubtype": 350},
                    "wadi": {"filterId": "labels.streams", "semanticSubtype": 360},
                },
                supplement["typedWaterways"],
            )

            names = set()
            subtypes_by_name = {}
            bilingual = {}
            decoded_avoid_edges = []
            for _, _, decoded in _present_payloads(root / "first"):
                for record in decoded.records:
                    sourced = record.sourced_text
                    assert sourced is not None
                    name = sourced.primary_text
                    names.add(name)
                    subtypes_by_name[name] = record.renderer_record.variant.semantic_subtype
                    bilingual[name] = (sourced.layout_mode, sourced.english_text)
                    decoded_avoid_edges.append(
                        record.renderer_record.variant.placement.avoid_edges
                    )
            self.assertTrue(decoded_avoid_edges)
            self.assertTrue(all(decoded_avoid_edges))
            self.assertEqual(
                {
                    "Малая река",
                    "Little Creek",
                    "Ship Canal",
                    "Tidal Reach",
                    "وادي",
                    "Великая река",
                },
                names,
            )
            self.assertEqual(SemanticSubtype.RIVER.value, subtypes_by_name["Великая река"])
            self.assertEqual(SemanticSubtype.STREAM_CREEK.value, subtypes_by_name["Little Creek"])
            self.assertEqual(SemanticSubtype.CANAL_CHANNEL.value, subtypes_by_name["Tidal Reach"])
            self.assertEqual(SemanticSubtype.UNSPECIFIED_WATERCOURSE.value, subtypes_by_name["وادي"])
            self.assertEqual(
                (LayoutMode.PRIMARY_WITH_ENGLISH, "Great River"),
                bilingual["Великая река"],
            )
            self.assertEqual(
                (LayoutMode.PRIMARY_WITH_ENGLISH, "Wadi"), bilingual["وادي"]
            )
            receipt = first.receipt
            projection = receipt["projection"]
            self.assertEqual(
                23_500_000_000,
                projection["preferredComponentPackageByteCeiling"],
            )
            self.assertEqual(
                38_500_000_000,
                projection["hardComponentPackageByteCeiling"],
            )
            self.assertEqual(
                40_000_000_000,
                projection["hardMandatoryPhoneFootprintBytes"],
            )
            self.assertEqual(
                1_500_000_000,
                projection["reservedNonComponentFootprintBytes"],
            )
            self.assertFalse(projection["preferredCeilingExceeded"])
            self.assertEqual(
                sum(item["bytes"] for item in receipt["outputFiles"]),
                projection["runtimePackageBytes"],
            )
            self.assertFalse(receipt["catalogCountsClaimed"])
            self.assertEqual(
                sum(path.stat().st_size for path in (root / "first").iterdir()),
                projection["publishedDirectoryBytes"],
            )

    def test_feature_checkpoint_resume_matches_clean_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = self._build(root, "resumed", pause_after_features=3)
            self.assertEqual("paused", paused.state)
            self.assertFalse((root / "resumed").exists())
            self.assertEqual(3, paused.receipt["checkpoint"]["renderedFeatures"])

            resumed = self._build(root, "resumed")
            clean = self._build(root, "clean")
            self.assertEqual("complete", resumed.state)
            for filename in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                self.assertEqual(
                    (root / "resumed" / filename).read_bytes(),
                    (root / "clean" / filename).read_bytes(),
                    filename,
                )

    def test_feature_checkpoint_resume_rejects_resealed_text_audit_tamper(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = self._build(root, "audit-tamper", pause_after_features=2)
            self.assertEqual("paused", paused.state)
            database = root / "audit-tamper-work" / "waterway-state.sqlite"
            connection = sqlite3.connect(database)
            try:
                checkpoint = dict(store._meta_get(connection, "renderCheckpoint"))
                audit = dict(checkpoint["rendererTextAudit"])
                audit["sourceFeatures"] = 1
                audit["stateSha256"] = store._renderer_text_audit_state_sha256(audit)
                checkpoint["rendererTextAudit"] = audit
                store._meta_set(connection, "renderCheckpoint", checkpoint)
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                store.GlobalWaterwayPackageError,
                "checkpoint text audit differs from exact source prefix",
            ):
                self._build(root, "audit-tamper")

    def test_reached_pause_target_stays_paused_instead_of_publishing_a_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = self._build(root, "resumed", pause_after_features=2)
            self.assertEqual("paused", first.state)
            second = self._build(root, "resumed", pause_after_features=2)
            self.assertEqual("paused", second.state)
            self.assertEqual(2, second.receipt["checkpoint"]["renderedFeatures"])
            self.assertFalse((root / "resumed").exists())

            resumed = self._build(root, "resumed")
            clean = self._build(root, "clean")
            self.assertEqual("complete", resumed.state)
            for filename in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
            ):
                self.assertEqual(
                    (root / "clean" / filename).read_bytes(),
                    (root / "resumed" / filename).read_bytes(),
                    filename,
                )

    def test_feature_checkpoint_resume_authenticates_staged_record_rows(self) -> None:
        import sqlite3

        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = self._build(root, "resumed", pause_after_features=3)
            self.assertEqual("paused", paused.state)
            database = root / "resumed-work" / "waterway-state.sqlite"
            connection = sqlite3.connect(database)
            try:
                row = connection.execute(
                    "SELECT rowid,envelope FROM records ORDER BY z,y,x,posting_key LIMIT 1"
                ).fetchone()
                self.assertIsNotNone(row)
                rowid, envelope = row
                changed = bytearray(envelope)
                changed[-1] ^= 0x01
                connection.execute(
                    "UPDATE records SET envelope=? WHERE rowid=?",
                    (bytes(changed), rowid),
                )
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "rendered feature prefix.*differs"
            ):
                self._build(root, "resumed")

    def test_resume_binds_exact_python_sqlite_and_zlib_runtime(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        runtime = store._runtime_identity_document()
        self.assertEqual(
            {"bytes", "sha256"}, set(runtime["components"]["pythonExecutable"])
        )
        self.assertEqual(
            {"bytes", "sha256"}, set(runtime["components"]["hashlibExtension"])
        )
        self.assertEqual(
            {"bytes", "sha256"}, set(runtime["components"]["unicodedataExtension"])
        )
        self.assertTrue(
            {"hashlib", "json", "re", "pathlib"}.issubset(runtime["stdlibFiles"])
        )
        self.assertEqual(
            __import__("unicodedata").unidata_version,
            runtime["semantics"]["unicodeDataVersion"],
        )
        self.assertIn("opensslVersion", runtime["semantics"])
        self.assertIn("sqliteVersion", runtime)
        self.assertIn("zlibRuntimeVersion", runtime)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = self._build(root, "resumed", pause_after_features=2)
            self.assertEqual("paused", paused.state)
            drifted = json.loads(json.dumps(runtime))
            drifted["zlibRuntimeVersion"] = "drifted-fixture"
            with patch.object(
                store, "_runtime_identity_document", return_value=drifted
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "checkpoint identity"
                ):
                    self._build(root, "resumed")

    def test_runtime_identity_binds_loaded_openssl_dll_chain_and_semantics(self) -> None:
        import ssl

        from tools.experiment8 import osm_global_waterway_package as pipeline

        runtime = pipeline.python_runtime_identity_document()
        loaded = runtime["loadedModuleOrigins"]
        for module_name in ("libcrypto-1_1.dll", "libssl-1_1.dll"):
            origin = loaded[module_name]
            self.assertEqual(
                "GetModuleHandleW+GetModuleFileNameW", origin["identityMethod"]
            )
            self.assertEqual(module_name, origin["moduleName"])
            self.assertEqual(module_name, Path(origin["path"]).name.casefold())
            self.assertEqual(
                pipeline._stream_identity(
                    Path(origin["path"]), f"test loaded {module_name}"
                ),
                runtime["components"][origin["component"]],
            )
        crypto = runtime["components"][loaded["libcrypto-1_1.dll"]["component"]]
        self.assertEqual(3_441_504, crypto["bytes"])
        self.assertEqual(
            "976ce72efd0a8aeeb6e21ad441aa9138434314ea07f777432205947cdb149541",
            crypto["sha256"],
        )
        semantics = runtime["semantics"]
        self.assertEqual(ssl.OPENSSL_VERSION, semantics["opensslVersion"])
        self.assertEqual(ssl.OPENSSL_VERSION_NUMBER, semantics["opensslVersionNumber"])
        self.assertEqual(list(ssl.OPENSSL_VERSION_INFO), semantics["opensslVersionInfo"])
        self.assertNotEqual("unavailable", semantics["opensslVersion"])

    def test_line_candidate_policy_is_bound_in_run_identity_and_receipt(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            result = self._build(Path(temporary), "package")
        policy = store._line_candidate_policy_document()
        self.assertEqual(policy, result.receipt["admission"]["policy"]["document"])
        self.assertEqual(
            store.LINE_CANDIDATE_POLICY_SHA256,
            result.receipt["build"]["runIdentity"]["admissionPolicySha256"],
        )
        self.assertNotIn("relationGeometryPolicy", result.receipt)
        limits = policy["geometry"]["limits"]
        self.assertEqual(64, limits["maxCandidateDepth"])
        self.assertEqual(65_536, limits["maxCandidateRelationVisits"])
        self.assertEqual(65_536, limits["maxCandidateRawParts"])
        self.assertEqual(524_288, limits["maxCandidatePoints"])

    def test_runtime_resume_authenticates_prefix_and_publication_is_atomic(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaisesRegex(GlobalWaterwayPackageError, "atomic.*publication"):
                    self._build(root, "package")
            partial = next(root.glob("package.partial-*"))
            records = partial / "records.fadictpack"
            with records.open("r+b") as handle:
                first = handle.read(1)
                self.assertTrue(first)
                handle.seek(0)
                handle.write(bytes((first[0] ^ 0xFF,)))
                handle.flush()
                import os

                os.fsync(handle.fileno())
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "records prefix"):
                self._build(root, "package")

    def test_completed_render_checkpoint_reauthenticates_sqlite_records(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "atomic.*publication"
                ):
                    self._build(root, "package")
            database = root / "package-work" / "waterway-state.sqlite"
            connection = sqlite3.connect(database)
            try:
                row = connection.execute(
                    "SELECT rowid,envelope FROM records ORDER BY z,y,x,posting_key LIMIT 1"
                ).fetchone()
                self.assertIsNotNone(row)
                rowid, envelope = row
                changed = bytearray(envelope)
                changed[-1] ^= 0x01
                connection.execute(
                    "UPDATE records SET envelope=? WHERE rowid=?",
                    (bytes(changed), rowid),
                )
                connection.commit()
            finally:
                connection.close()

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "rendered feature prefix.*differs"
            ):
                self._build(root, "package")
            self.assertFalse((root / "package").exists())

    def test_rename_boundary_mutation_restores_unpublished_partial(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        real_rename = store.os.rename

        def mutate_then_rename(source, destination):
            source = Path(source)
            if source.is_dir() and source.name.startswith("package.partial-"):
                records = source / "records.fadictpack"
                with records.open("r+b") as handle:
                    first = handle.read(1)
                    handle.seek(0)
                    handle.write(bytes((first[0] ^ 0xFF,)))
                    handle.flush()
                return real_rename(source, destination)
            return real_rename(source, destination)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(store.os, "rename", side_effect=mutate_then_rename):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "published records.*identity differs"
                ):
                    self._build(root, "package")
            self.assertFalse((root / "package").exists())
            partials = tuple(root.glob("package.partial-*"))
            self.assertEqual(1, len(partials))

    def test_publication_rejects_unexpected_partial_inventory(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaisesRegex(GlobalWaterwayPackageError, "atomic.*publication"):
                    self._build(root, "package")
            partial = next(root.glob("package.partial-*"))
            (partial / "unexpected.bin").write_bytes(b"must not ride publication")
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "unexpected.*inventory"
            ):
                self._build(root, "package")

    def test_collision_and_hard_storage_ceiling_fail_closed(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import GlobalWaterwayPackageError

        connection = sqlite3.connect(":memory:")
        connection.execute(
            "CREATE TABLE identities(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL)"
        )
        store._insert_hot_identity(connection, "identities", b"12345678", b"a" * 32)
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "collision"):
            store._insert_hot_identity(
                connection, "identities", b"12345678", b"b" * 32
            )
        connection.close()
        for table in (
            "feature_ids",
            "geometry_ids",
            "label_ids",
            "variant_ids",
            "sourced_ids",
        ):
            with self.subTest(table=table):
                connection = sqlite3.connect(":memory:")
                connection.execute(
                    f"CREATE TABLE {table}(hot_id BLOB PRIMARY KEY,full_sha BLOB NOT NULL)"
                )
                store._insert_hot_identity(
                    connection,
                    table,
                    b"12345678",
                    b"a" * 32,
                )
                with self.assertRaisesRegex(GlobalWaterwayPackageError, "collision"):
                    store._insert_hot_identity(
                        connection,
                        table,
                        b"12345678",
                        b"b" * 32,
                    )
                connection.close()
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "NFC"):
            store._validated_package_id("Cafe\u0301-waterways")
        store.enforce_global_waterway_storage_ceiling(38_499_999_999)
        with self.assertRaisesRegex(GlobalWaterwayPackageError, "38,500,000,000"):
            store.enforce_global_waterway_storage_ceiling(38_500_000_000)

    def test_visual_evaluation_package_binds_capacity_policy_without_pruning(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy

        events = []
        available_measurements = []
        real_build_runtime_files = store._build_runtime_files
        real_enforce = store.enforce_global_waterway_storage_ceiling
        real_projection = store._projection_stats
        real_semantic = store._renderer_semantic_sha256

        def record_capacity(_output_directory):
            events.append("capacity")
            measured = 100_000_000_000 if not available_measurements else 90_000_000_000
            available_measurements.append(measured)
            return measured

        def record_runtime_build(*args, **kwargs):
            events.append("runtime-build")
            self.assertEqual(
                size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                kwargs.get("size_policy_mode"),
            )
            self.assertEqual(
                100_000_000_000,
                kwargs.get("available_destination_bytes"),
            )
            return real_build_runtime_files(*args, **kwargs)

        def record_projection(*args, **kwargs):
            events.append("projection")
            return real_projection(*args, **kwargs)

        def record_semantic(*args, **kwargs):
            events.append("semantic")
            return real_semantic(*args, **kwargs)

        enforced_modes = []

        def record_enforcement(*args, **kwargs):
            enforced_modes.append(kwargs.get("size_policy_mode"))
            return real_enforce(*args, **kwargs)

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            size_policy,
            "destination_available_bytes",
            side_effect=record_capacity,
        ), patch.object(
            store,
            "_build_runtime_files",
            side_effect=record_runtime_build,
        ), patch.object(
            store,
            "_projection_stats",
            side_effect=record_projection,
        ), patch.object(
            store,
            "_renderer_semantic_sha256",
            side_effect=record_semantic,
        ), patch.object(
            store,
            "enforce_global_waterway_storage_ceiling",
            side_effect=record_enforcement,
        ):
            root = Path(temporary)
            result = self._build(
                root,
                "visual",
                size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
        self.assertEqual("complete", result.state)
        receipt = result.receipt
        binding = receipt["build"]["sizePolicy"]["binding"]
        decision = receipt["build"]["sizePolicy"]["decision"]
        self.assertEqual(
            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
            binding["mode"],
        )
        self.assertEqual(binding, receipt["build"]["runIdentity"]["sizePolicy"])
        self.assertTrue(decision["authorized"])
        self.assertEqual(
            receipt["projection"]["publishedDirectoryBytes"],
            decision["requiredPackageBytes"],
        )
        self.assertEqual(100_000_000_000, decision["availableDestinationBytes"])
        self.assertEqual(
            90_000_000_000,
            decision["publicationBoundaryDestinationFreeBytes"],
        )
        self.assertEqual(
            size_policy.DESTINATION_RESERVE_BYTES,
            decision["publicationBoundaryRequiredReserveBytes"],
        )
        self.assertTrue(decision["publicationBoundaryAuthorized"])
        self.assertEqual(
            ["semantic", "projection", "capacity", "runtime-build"],
            events[:4],
        )
        self.assertGreaterEqual(events.count("capacity"), 2)
        self.assertEqual(90_000_000_000, available_measurements[-1])
        self.assertIn("runtime-build", events)
        self.assertTrue(enforced_modes)
        self.assertNotIn(None, enforced_modes[1:])
        self.assertFalse(receipt["projection"]["hardComponentCeilingExceeded"])
        self.assertFalse(
            receipt["projection"]["hardMandatoryPhoneFootprintCeilingExceeded"]
        )

    def test_visual_capacity_is_measured_only_after_partial_ownership(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        measurements = []

        def measured(_output_directory):
            measurements.append(True)
            return 100_000_000_000

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            size_policy,
            "destination_available_bytes",
            side_effect=measured,
        ), patch.object(
            store,
            "_ensure_owned_partial_directory",
            side_effect=GlobalWaterwayPackageError("synthetic owner stop"),
        ):
            root = Path(temporary)
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "owner stop"):
                self._build(
                    root,
                    "visual-owner",
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            connection = sqlite3.connect(
                root / "visual-owner-work" / "waterway-state.sqlite"
            )
            try:
                self.assertIsNone(store._meta_get(connection, "sizePolicyCapacity"))
            finally:
                connection.close()
        self.assertEqual([], measurements)

    def test_visual_capacity_rejects_partial_inventory_race(self) -> None:
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            raced = False

            def mutate_partial_during_capacity(_output_directory):
                nonlocal raced
                if not raced:
                    partial = next(root.glob("visual-race.partial-*"))
                    (partial / "manifest.json").write_bytes(b"raced\n")
                    raced = True
                return 100_000_000_000

            with patch.object(
                size_policy,
                "destination_available_bytes",
                side_effect=mutate_partial_during_capacity,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "partial inventory changed while measuring capacity",
            ):
                self._build(
                    root,
                    "visual-race",
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertTrue(raced)

    def test_visual_capacity_authority_survives_owned_partial_resume(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=100_000_000_000,
            ), patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "atomic.*publication"
                ):
                    self._build(
                        root,
                        "visual-resume",
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                    )
            partial = next(root.glob("visual-resume.partial-*"))
            partial_bytes_at_resume = sum(
                path.stat().st_size for path in partial.iterdir()
            )
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=size_policy.DESTINATION_RESERVE_BYTES,
            ):
                resumed = self._build(
                    root,
                    "visual-resume",
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
        self.assertEqual("complete", resumed.state)
        decision = resumed.receipt["build"]["sizePolicy"]["decision"]
        self.assertEqual(
            size_policy.DESTINATION_RESERVE_BYTES + partial_bytes_at_resume,
            decision["availableDestinationBytes"],
        )
        self.assertEqual(
            size_policy.DESTINATION_RESERVE_BYTES,
            decision["publicationBoundaryDestinationFreeBytes"],
        )

    def test_fixture_package_is_accepted_by_independent_catalog_finalizer(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import finalize_v3_class_catalog

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._build(root, "package")
            finalized = finalize_v3_class_catalog(root / "package")

            manifest = json.loads((root / "package" / "manifest.json").read_text("utf-8"))
            self.assertEqual(754, (root / "package" / "class-catalog.bin").stat().st_size)
            self.assertEqual(
                finalized.catalog_sha256, manifest["classCatalog"]["catalogSha256"]
            )
            self.assertEqual(
                manifest["rendererSemanticStreamSha256"],
                manifest["classCatalog"]["rendererContractSha256"],
            )


class GlobalWaterwayRenderRecoveryTests(unittest.TestCase):
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
    _PINNED_META = (
        "runIdentity",
        "checkpoint",
        "admissionRunIdentity",
        "admissionCheckpoint",
        "admissionReceipt",
        "ingestReceipt",
        "renderRunIdentity",
        "renderCheckpoint",
    )

    @staticmethod
    def _fact(raw: bytes) -> tuple[int, str]:
        return len(raw), hashlib.sha256(raw).hexdigest()

    @staticmethod
    def _canonical(document: object) -> bytes:
        return (
            json.dumps(
                document,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            )
            + "\n"
        ).encode("utf-8")

    def _prepare_recovery(self, root: Path):
        import shutil
        import sqlite3

        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_recovery import (
            WaterwayRenderRecoveryAuthority,
        )

        extraction = root / "extraction"
        extraction.mkdir(parents=True)
        opl = extraction / "waterway-closure.opl"
        roots = extraction / "root-ids.txt"
        shutil.copyfile(CLOSURE_FIXTURE, opl)
        shutil.copyfile(ROOT_FIXTURE, roots)
        binding = _source_binding(opl, roots)
        output = root / "recovered"
        work = root / "work"
        paused = pipeline.render_fixture_global_waterway_package(
            opl_path=opl,
            root_ids_path=roots,
            output_directory=output,
            work_directory=work,
            package_id="fixture-global-waterways-v3",
            source_binding=binding,
            checkpoint_objects=4,
            checkpoint_features=2,
            pause_after_features=3,
        )
        self.assertEqual("paused", paused.state)
        database = work / "waterway-state.sqlite"
        connection = sqlite3.connect(database)
        try:
            old_run = store._meta_get(connection, "runIdentity")
            old_run["code"]["store"]["sha256"] = "0" * 64
            old_run_sha256 = hashlib.sha256(self._canonical(old_run)).hexdigest()
            ingest = store._meta_get(connection, "ingestReceipt")
            ingest["runIdentity"] = old_run
            ingest["runIdentitySha256"] = old_run_sha256
            old_render = store._meta_get(connection, "renderRunIdentity")
            old_render["code"]["store"]["sha256"] = "0" * 64
            store._meta_set(connection, "runIdentity", old_run)
            store._meta_set(connection, "ingestReceipt", ingest)
            store._meta_set(connection, "renderRunIdentity", old_render)
            connection.commit()
            meta_facts = []
            for key in self._PINNED_META:
                raw = bytes(
                    connection.execute(
                        "SELECT value FROM meta WHERE key=?", (key,)
                    ).fetchone()[0]
                )
                byte_count, sha256 = self._fact(raw)
                meta_facts.append((key, byte_count, sha256))
            source_counts = tuple(
                (table, int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]))
                for table in self._SOURCE_TABLES
            )
            renderer_counts = tuple(
                (table, int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]))
                for table in self._RENDERER_TABLES
            )
        finally:
            connection.close()

        database_raw = database.read_bytes()
        failure_log = root / "stderr.log"
        failure_log.write_bytes(
            b"Traceback (most recent call last):\n"
            b"sqlite3.OperationalError: database is locked\n"
        )
        failure_raw = failure_log.read_bytes()
        authority = WaterwayRenderRecoveryAuthority(
            package_id="fixture-global-waterways-v3",
            checkpoint_features=2,
            rendered_features=3,
            database_bytes=len(database_raw),
            database_sha256=hashlib.sha256(database_raw).hexdigest(),
            failure_log_bytes=len(failure_raw),
            failure_log_sha256=hashlib.sha256(failure_raw).hexdigest(),
            meta_identities=tuple(meta_facts),
            source_table_counts=source_counts,
            renderer_table_counts=renderer_counts,
            predecessor_size_policy_mode=size_policy.BUDGETED_RELEASE_V1,
            predecessor_size_policy_identity_bound=True,
            intended_size_policy_mode=size_policy.BUDGETED_RELEASE_V1,
        )
        database_fact = {
            "bytes": authority.database_bytes,
            "sha256": authority.database_sha256,
        }
        failure_fact = {
            "bytes": authority.failure_log_bytes,
            "sha256": authority.failure_log_sha256,
        }
        backup_receipt = root / "backup-receipt.json"
        backup_receipt.write_bytes(
            self._canonical(
                {
                    "backupDatabase": database_fact,
                    "failureLog": failure_fact,
                    "schema": (
                        "flightalert.experiment8.osm-global-waterway-"
                        "render-recovery-backup.v1"
                    ),
                    "sourceDatabase": database_fact,
                    "state": "complete",
                }
            )
        )
        return {
            "authority": authority,
            "backup_receipt": backup_receipt,
            "binding": binding,
            "database": database,
            "extraction": extraction,
            "failure_log": failure_log,
            "opl": opl,
            "output": output,
            "roots": roots,
            "work": work,
        }

    def _snapshot_database(self, database: Path):
        import sqlite3

        connection = sqlite3.connect(database)
        try:
            counts = {
                table: int(
                    connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                )
                for table in self._SOURCE_TABLES + self._RENDERER_TABLES
            }
            meta = {
                key: bytes(value)
                for key, value in connection.execute(
                    "SELECT key,value FROM meta ORDER BY key"
                )
            }
        finally:
            connection.close()
        return database.read_bytes(), counts, meta

    def test_recovery_extraction_reader_binds_receipt_without_reparsing_opl(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as source
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            extraction = Path(temporary) / "extraction"
            extraction.mkdir()
            for name in source._extraction_inventory_names():
                (extraction / name).write_bytes((name + "\n").encode("utf-8"))
            artifact_names = {
                "checkpoint": "extraction-checkpoint.json",
                "closureOpl": "waterway-closure.opl",
                "closurePbf": "waterway-closure.pbf",
                "rootIds": "root-ids.txt",
                "selectionManifest": "selection-final-manifest.json",
            }
            artifacts = {}
            for key, name in artifact_names.items():
                raw = (extraction / name).read_bytes()
                artifacts[key] = {
                    "bytes": len(raw),
                    "name": name,
                    "sha256": hashlib.sha256(raw).hexdigest(),
                }
            planet_path = Path(temporary) / "planet-260629.osm.pbf"
            planet_fact = {
                "bytes": 123,
                "path": str(planet_path),
                "sha256": hashlib.sha256(b"planet").hexdigest(),
            }
            receipt = {
                "artifacts": artifacts,
                "schema": "flightalert.experiment8.osm-global-waterway-extraction.v1",
                "source": planet_fact,
            }
            receipt_raw = self._canonical(receipt)
            (extraction / "extraction-receipt.json").write_bytes(receipt_raw)
            with patch.object(source, "EXPECTED_PLANET_PATH", planet_path), patch.object(
                source, "EXPECTED_PLANET_BYTES", planet_fact["bytes"]
            ), patch.object(
                source, "EXPECTED_PLANET_SHA256", planet_fact["sha256"]
            ):
                binding = recovery._source_binding_from_recovery_extraction(
                    extraction
                )
                self.assertEqual(str(planet_path), binding.planet_path)
                self.assertEqual(
                    artifacts["closureOpl"]["sha256"], binding.closure_opl_sha256
                )
                self.assertEqual(
                    hashlib.sha256(receipt_raw).hexdigest(),
                    binding.extraction_receipt_sha256,
                )

                manifest = extraction / "selection-final-manifest.json"
                manifest.write_bytes(b"drifted-selection-manifest\n")
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "selectionManifest identity differs",
                ):
                    recovery._source_binding_from_recovery_extraction(extraction)

    def _recover(
        self,
        prepared,
        *,
        pause_after_features: int | None = None,
        render_workers: int = 1,
        progress_file: Path | None = None,
        size_policy_mode: str = "budgeted-release-v1",
    ):
        from tools.experiment8 import osm_global_waterway_package as pipeline

        patches = [
            patch.object(
                pipeline,
                "_PRODUCTION_WATERWAY_RENDER_RECOVERY_AUTHORITY",
                prepared["authority"],
            ),
            patch.object(
                pipeline,
                "_source_binding_from_recovery_extraction",
                return_value=prepared["binding"],
            ),
        ]
        with patches[0], patches[1]:
            return pipeline.recover_global_waterway_package(
                extraction_directory=prepared["extraction"],
                output_directory=prepared["output"],
                work_directory=prepared["work"],
                package_id="fixture-global-waterways-v3",
                failure_log=prepared["failure_log"],
                backup_receipt=prepared["backup_receipt"],
                checkpoint_features=2,
                render_workers=render_workers,
                pause_after_features=pause_after_features,
                progress_file=progress_file,
                size_policy_mode=size_policy_mode,
            )

    def test_recovery_execution_controls_reach_parallel_stage_only(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root)
            progress = root / "progress.json"
            with patch.object(
                store, "_stage_renderer_records", return_value=False
            ) as stage:
                result = self._recover(
                    prepared,
                    pause_after_features=4,
                    render_workers=2,
                    progress_file=progress,
                )

            self.assertEqual("paused", result.state)
            arguments = stage.call_args.kwargs
            self.assertEqual(4, arguments["pause_after_features"])
            self.assertEqual(progress, arguments["progress_file"])
            self.assertEqual(2, arguments["parallel_limits"].workers)
            self.assertNotIn("renderWorkers", arguments["run_identity"])
            self.assertNotIn("pauseAfterFeatures", arguments["run_identity"])
            self.assertNotIn("progressFile", arguments["run_identity"])

    def test_unknown_progress_is_rejected_before_destructive_recovery_reset(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root)
            progress = root / "progress.json"
            progress.write_bytes(b"unknown progress\n")
            before = self._snapshot_database(prepared["database"])

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "unknown|malformed|canonical"
            ):
                self._recover(prepared, progress_file=progress)

            self.assertEqual(before, self._snapshot_database(prepared["database"]))
            self.assertEqual(b"unknown progress\n", progress.read_bytes())

    def test_oversized_pause_is_rejected_before_destructive_recovery_reset(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root)
            before = self._snapshot_database(prepared["database"])

            with self.assertRaisesRegex(
                GlobalWaterwayPackageError, "pause.*maximum|pause.*range|pause.*invalid"
            ):
                self._recover(
                    prepared,
                    pause_after_features=1_000_000_000_000,
                )

            self.assertEqual(before, self._snapshot_database(prepared["database"]))
            self.assertFalse(prepared["output"].exists())

    def test_same_inode_progress_rewrite_is_rejected_before_recovery_reset(self) -> None:
        import shutil

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root)
            incident_database = prepared["database"].read_bytes()
            progress = root / "progress.json"
            minted = self._recover(
                prepared,
                pause_after_features=2,
                progress_file=progress,
            )
            self.assertEqual("paused", minted.state)
            self.assertTrue(progress.is_file())
            progress_document = json.loads(progress.read_bytes())
            progress_document["committedFeatures"] = 0
            progress.write_bytes(self._canonical(progress_document))

            prepared["database"].write_bytes(incident_database)
            for spool in prepared["work"].glob("waterway-render-spool-*"):
                self.assertEqual(prepared["work"], spool.parent)
                shutil.rmtree(spool)
            before = self._snapshot_database(prepared["database"])
            progress_before = progress.stat()
            real_preflight = store._RenderProgressPublisher.preflight
            rewrote = False
            rewrite_blocked = False

            def rewrite_after_preflight(publisher, committed_features: int) -> None:
                nonlocal rewrote, rewrite_blocked
                real_preflight(publisher, committed_features)
                if rewrote:
                    return
                raw = progress.read_bytes()
                changed = bytearray(raw)
                marker = raw.index(b'"renderRunIdentitySha256":"') + len(
                    b'"renderRunIdentitySha256":"'
                )
                changed[marker] = ord("0") if changed[marker] != ord("0") else ord("1")
                try:
                    with progress.open("r+b", buffering=0) as handle:
                        handle.write(changed)
                        handle.flush()
                        os.fsync(handle.fileno())
                except OSError as error:
                    rewrite_blocked = True
                    raise GlobalWaterwayPackageError(
                        "progress rewrite denied by session ownership"
                    ) from error
                rewrote = True

            with patch.object(
                store._RenderProgressPublisher,
                "preflight",
                new=rewrite_after_preflight,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "progress.*changed|progress.*bytes|another render run|identity|"
                "progress.*denied",
            ):
                self._recover(prepared, progress_file=progress)

            self.assertTrue(rewrote or rewrite_blocked)
            progress_after = progress.stat()
            self.assertEqual(
                (progress_before.st_dev, progress_before.st_ino),
                (progress_after.st_dev, progress_after.st_ino),
            )
            self.assertEqual(before, self._snapshot_database(prepared["database"]))
            self.assertFalse(prepared["output"].exists())

    def _validate_recovery(self, prepared, *, size_policy_mode="budgeted-release-v1"):
        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store

        with patch.object(
            pipeline,
            "_PRODUCTION_WATERWAY_RENDER_RECOVERY_AUTHORITY",
            prepared["authority"],
        ), patch.object(
            pipeline,
            "_source_binding_from_recovery_extraction",
            return_value=prepared["binding"],
        ), patch.object(
            recovery,
            "_reset_renderer_state",
            side_effect=AssertionError("validator attempted renderer reset"),
        ), patch.object(
            store,
            "_stage_renderer_records",
            side_effect=AssertionError("validator attempted renderer staging"),
        ), patch.object(
            store,
            "_publish",
            side_effect=AssertionError("validator attempted publication"),
        ):
            return pipeline.validate_global_waterway_render_recovery(
                extraction_directory=prepared["extraction"],
                output_directory=prepared["output"],
                work_directory=prepared["work"],
                package_id="fixture-global-waterways-v3",
                failure_log=prepared["failure_log"],
                backup_receipt=prepared["backup_receipt"],
                checkpoint_features=2,
                size_policy_mode=size_policy_mode,
            )

    def test_validate_recovery_is_query_only_and_mutation_free(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            before = self._snapshot_database(prepared["database"])
            real_connect = recovery.sqlite3.connect
            calls = []

            def recording_connect(*args, **kwargs):
                calls.append((args, dict(kwargs)))
                return real_connect(*args, **kwargs)

            with patch.object(recovery.sqlite3, "connect", side_effect=recording_connect):
                validation = self._validate_recovery(prepared)
            self.assertEqual("accepted", validation["state"])
            self.assertFalse(validation["resumed"])
            self.assertEqual(
                "flightalert.experiment8.osm-global-waterway-render-recovery-validation.v1",
                validation["schema"],
            )
            self.assertEqual(1, validation["queryOnly"])
            self.assertEqual(before, self._snapshot_database(prepared["database"]))
            self.assertTrue(calls)
            uri_args, uri_kwargs = calls[-1]
            self.assertTrue(uri_kwargs.get("uri"))
            self.assertIn("mode=ro", str(uri_args[0]))

    def test_validate_recovery_accepts_windows_incident_line_ending_only_for_exact_failure(self) -> None:
        from dataclasses import replace

        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        cases = (
            (
                "exact-windows-incident",
                b"Traceback (most recent call last):\r\n"
                b"sqlite3.OperationalError: database is locked\r\n",
                True,
            ),
            (
                "different-windows-failure",
                b"Traceback (most recent call last):\r\n"
                b"sqlite3.OperationalError: database is malformed\r\n",
                False,
            ),
        )
        for label, failure_raw, accepted in cases:
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                prepared["failure_log"].write_bytes(failure_raw)
                failure_fact = {
                    "bytes": len(failure_raw),
                    "sha256": hashlib.sha256(failure_raw).hexdigest(),
                }
                prepared["authority"] = replace(
                    prepared["authority"],
                    failure_log_bytes=failure_fact["bytes"],
                    failure_log_sha256=failure_fact["sha256"],
                )
                receipt = json.loads(
                    prepared["backup_receipt"].read_text("utf-8")
                )
                receipt["failureLog"] = failure_fact
                prepared["backup_receipt"].write_bytes(self._canonical(receipt))
                before = self._snapshot_database(prepared["database"])
                if accepted:
                    validation = self._validate_recovery(prepared)
                    self.assertEqual("accepted", validation["state"])
                else:
                    with self.assertRaisesRegex(
                        GlobalWaterwayPackageError,
                        "failure class/message differs from exact incident",
                    ):
                        self._validate_recovery(prepared)
                self.assertEqual(
                    before, self._snapshot_database(prepared["database"])
                )

    def test_validate_recovery_rejects_malformed_evidence_without_mutation(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            prepared["backup_receipt"].write_bytes(b"{}\n")
            before = self._snapshot_database(prepared["database"])
            with self.assertRaises(GlobalWaterwayPackageError):
                self._validate_recovery(prepared)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_validate_recovery_authenticates_exact_renderer_prefix_and_ids(self) -> None:
        import sqlite3

        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        mutations = {
            "record-envelope": (
                "UPDATE records SET envelope=zeroblob(length(envelope)) "
                "WHERE rowid=(SELECT MIN(rowid) FROM records)"
            ),
            "rendered-feature": (
                "UPDATE rendered_features SET source_type='wadi' WHERE ordinal=0"
            ),
            "feature-id": (
                "UPDATE feature_ids SET full_sha=zeroblob(32) "
                "WHERE hot_id=(SELECT MIN(hot_id) FROM feature_ids)"
            ),
            "variant-id": (
                "UPDATE variant_ids SET full_sha=zeroblob(32) "
                "WHERE hot_id=(SELECT MIN(hot_id) FROM variant_ids)"
            ),
            "geometry-id": (
                "UPDATE geometry_ids SET full_sha=zeroblob(32) "
                "WHERE hot_id=(SELECT MIN(hot_id) FROM geometry_ids)"
            ),
            "label-id": (
                "UPDATE label_ids SET full_sha=zeroblob(32) "
                "WHERE hot_id=(SELECT MIN(hot_id) FROM label_ids)"
            ),
            "sourced-id": (
                "UPDATE sourced_ids SET full_sha=zeroblob(32) "
                "WHERE hot_id=(SELECT MIN(hot_id) FROM sourced_ids)"
            ),
        }
        for label, statement in mutations.items():
            with self.subTest(label=label), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                paused = self._recover(prepared, pause_after_features=4)
                self.assertEqual("paused", paused.state)
                connection = sqlite3.connect(prepared["database"])
                try:
                    connection.execute(statement)
                    connection.commit()
                finally:
                    connection.close()
                before = self._snapshot_database(prepared["database"])
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "prefix|identity",
                ):
                    self._validate_recovery(prepared)
                self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_validate_recovery_rejects_absent_zero_byte_runtime_with_wrong_hash(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        def abandon_empty_partial(connection, _database, partial, _windows, **_kwargs):
            store._ensure_owned_partial_directory(connection, partial)
            store._meta_set(
                connection,
                "buildCheckpoint",
                {
                    "indexBytes": 0,
                    "indexSha256": "f" * 64,
                    "nextOrdinal": 0,
                    "recordsBytes": 0,
                    "recordsSha256": "e" * 64,
                },
            )
            connection.commit()
            raise GlobalWaterwayPackageError("synthetic empty-partial stop")

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            with patch.object(
                store,
                "_build_runtime_files",
                side_effect=abandon_empty_partial,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "synthetic empty-partial stop",
            ):
                self._recover(prepared)
            before = self._snapshot_database(prepared["database"])
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "prefix SHA-256",
            ):
                self._validate_recovery(prepared)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_validation_does_not_replace_execution_revalidation(self) -> None:
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            validation = self._validate_recovery(prepared)
            self.assertEqual("accepted", validation["state"])
            prepared["roots"].write_bytes(prepared["roots"].read_bytes() + b"w999\n")
            before = self._snapshot_database(prepared["database"])
            with self.assertRaises(GlobalWaterwayPackageError):
                self._recover(prepared)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_validate_recovery_uses_one_read_snapshot_for_renderer_prefix(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            paused = self._recover(prepared, pause_after_features=4)
            self.assertEqual("paused", paused.state)
            real_validate = store._validated_renderer_prefix_stream
            transaction_states = []

            def record_transaction(connection, **kwargs):
                transaction_states.append(connection.in_transaction)
                return real_validate(connection, **kwargs)

            with patch.object(
                store,
                "_validated_renderer_prefix_stream",
                side_effect=record_transaction,
            ):
                validation = self._validate_recovery(prepared)
            self.assertEqual("accepted", validation["state"])
            self.assertEqual([True], transaction_states)

    def test_first_recovery_validates_exact_incident_renderer_prefix(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            connection = sqlite3.connect(prepared["database"])
            try:
                incident_identity = store._meta_get(
                    connection,
                    "renderRunIdentity",
                )
            finally:
                connection.close()
            real_validate = store._validated_renderer_prefix_stream
            calls = []

            def record_incident_prefix(connection, **kwargs):
                calls.append(kwargs)
                return real_validate(connection, **kwargs)

            with patch.object(
                store,
                "_validated_renderer_prefix_stream",
                side_effect=record_incident_prefix,
            ):
                validation = self._validate_recovery(prepared)
            self.assertEqual("accepted", validation["state"])
            self.assertEqual(1, len(calls))
            self.assertEqual(
                prepared["authority"].rendered_features,
                calls[0]["rendered_prefix"],
            )
            self.assertEqual(incident_identity, calls[0]["run_identity"])

    def test_first_recovery_hashes_sources_only_at_destructive_boundary(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_require_identity = store._require_identity
            recovery_labels = []

            def record_identity(*args, **kwargs):
                label = kwargs["label"]
                if label.startswith("recovery "):
                    recovery_labels.append(label)
                return real_require_identity(*args, **kwargs)

            with patch.object(
                store,
                "_require_identity",
                side_effect=record_identity,
            ):
                result = self._recover(prepared, pause_after_features=2)

            self.assertEqual("paused", result.state)
            self.assertEqual(
                [
                    "recovery root-ID file at destructive boundary",
                    "recovery closure OPL at destructive boundary",
                ],
                recovery_labels,
            )

    def test_first_recovery_counts_only_at_locked_destructive_boundary(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_source_counts = recovery._require_source_counts
            real_renderer_counts = recovery._require_renderer_counts
            source_transactions = []
            renderer_transactions = []

            def record_source_counts(connection, authority):
                source_transactions.append(connection.in_transaction)
                return real_source_counts(connection, authority)

            def record_renderer_counts(connection, authority):
                renderer_transactions.append(connection.in_transaction)
                return real_renderer_counts(connection, authority)

            with patch.object(
                recovery,
                "_require_source_counts",
                side_effect=record_source_counts,
            ), patch.object(
                recovery,
                "_require_renderer_counts",
                side_effect=record_renderer_counts,
            ):
                result = self._recover(prepared, pause_after_features=2)

            self.assertEqual("paused", result.state)
            self.assertEqual([True], source_transactions)
            self.assertEqual([True], renderer_transactions)

    def test_first_recovery_count_mismatch_rejects_at_locked_boundary(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        cases = (
            ("source_table_counts", "_require_source_counts"),
            ("renderer_table_counts", "_require_renderer_counts"),
        )
        for field_name, helper_name in cases:
            with self.subTest(
                family=field_name
            ), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                counts = getattr(prepared["authority"], field_name)
                prepared["authority"] = replace(
                    prepared["authority"],
                    **{
                        field_name: (
                            (counts[0][0], counts[0][1] + 1),
                            *counts[1:],
                        )
                    },
                )
                before = self._snapshot_database(prepared["database"])
                real_require_counts = getattr(recovery, helper_name)
                transaction_states = []

                def record_mismatch(connection, authority):
                    transaction_states.append(connection.in_transaction)
                    return real_require_counts(connection, authority)

                with patch.object(
                    recovery,
                    helper_name,
                    side_effect=record_mismatch,
                ), self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)

                self.assertEqual([True], transaction_states)
                self.assertEqual(
                    before,
                    self._snapshot_database(prepared["database"]),
                )

    def test_query_only_recovery_keeps_full_identity_and_count_preflight(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_require_identity = store._require_identity
            real_source_counts = recovery._require_source_counts
            real_renderer_counts = recovery._require_renderer_counts
            recovery_labels = []
            count_families = []

            def record_identity(*args, **kwargs):
                label = kwargs["label"]
                if label.startswith("recovery "):
                    recovery_labels.append(label)
                return real_require_identity(*args, **kwargs)

            def record_source_counts(connection, authority):
                count_families.append("source")
                return real_source_counts(connection, authority)

            def record_renderer_counts(connection, authority):
                count_families.append("renderer")
                return real_renderer_counts(connection, authority)

            with patch.object(
                store,
                "_require_identity",
                side_effect=record_identity,
            ), patch.object(
                recovery,
                "_require_source_counts",
                side_effect=record_source_counts,
            ), patch.object(
                recovery,
                "_require_renderer_counts",
                side_effect=record_renderer_counts,
            ):
                validation = self._validate_recovery(prepared)

            self.assertEqual("accepted", validation["state"])
            self.assertEqual(["source", "renderer"], count_families)
            self.assertEqual(
                [
                    "recovery root-ID file",
                    "recovery closure OPL",
                    "recovery root-ID file at destructive boundary",
                    "recovery closure OPL at destructive boundary",
                ],
                recovery_labels,
            )

    def test_recovery_resume_keeps_identity_counts_and_current_prefix_validation(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            paused = self._recover(prepared, pause_after_features=4)
            self.assertEqual("paused", paused.state)
            real_require_identity = store._require_identity
            real_source_counts = recovery._require_source_counts
            real_renderer_counts = recovery._require_renderer_counts
            real_prefix_validation = store._validated_renderer_prefix_stream
            recovery_labels = []
            count_families = []
            validated_prefixes = []

            def record_identity(*args, **kwargs):
                label = kwargs["label"]
                if label.startswith("recovery "):
                    recovery_labels.append(label)
                return real_require_identity(*args, **kwargs)

            def record_source_counts(connection, authority):
                count_families.append("source")
                return real_source_counts(connection, authority)

            def record_renderer_counts(connection, authority):
                count_families.append("renderer")
                return real_renderer_counts(connection, authority)

            def record_prefix(connection, **kwargs):
                validated_prefixes.append(kwargs["rendered_prefix"])
                return real_prefix_validation(connection, **kwargs)

            with patch.object(
                store,
                "_require_identity",
                side_effect=record_identity,
            ), patch.object(
                recovery,
                "_require_source_counts",
                side_effect=record_source_counts,
            ), patch.object(
                recovery,
                "_require_renderer_counts",
                side_effect=record_renderer_counts,
            ), patch.object(
                store,
                "_validated_renderer_prefix_stream",
                side_effect=record_prefix,
            ):
                resumed = self._recover(prepared)

            self.assertEqual("complete", resumed.state)
            self.assertEqual(
                ["recovery root-ID file", "recovery closure OPL"],
                recovery_labels,
            )
            self.assertEqual(["source"], count_families)
            self.assertTrue(validated_prefixes)
            self.assertEqual(4, validated_prefixes[0])

    def test_recovery_renderer_prefix_holds_write_reservation(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            paused = self._recover(prepared, pause_after_features=4)
            self.assertEqual("paused", paused.state)
            real_validate = store._validated_renderer_prefix_stream
            transaction_states = []

            def record_transaction(connection, **kwargs):
                transaction_states.append(connection.in_transaction)
                return real_validate(connection, **kwargs)

            with patch.object(
                store,
                "_validated_renderer_prefix_stream",
                side_effect=record_transaction,
            ):
                resumed = self._recover(prepared)
            self.assertEqual("complete", resumed.state)
            self.assertGreaterEqual(len(transaction_states), 2)
            self.assertTrue(transaction_states[-1])

    def test_recovery_progress_session_outlives_sqlite_connection(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )
        from tools.experiment8.waterway_parallel_render import DurableProgressFile

        for (
            pause_after_features,
            expected_state,
            output_published,
            transaction_active,
        ) in (
            (None, "complete", True, True),
            (4, "paused", False, False),
        ):
            with self.subTest(
                state=expected_state
            ), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                prepared = self._prepare_recovery(root)
                progress = root / "recovery-progress.json"
                real_connect = recovery.sqlite3.connect
                probe = {
                    "attempted": False,
                    "denied": False,
                    "output_published": False,
                    "transaction_active": False,
                }

                class ProbedConnection(sqlite3.Connection):
                    def close(connection) -> None:
                        if not probe["attempted"]:
                            probe["attempted"] = True
                            probe["output_published"] = prepared["output"].is_dir()
                            probe["transaction_active"] = connection.in_transaction
                            try:
                                contender = DurableProgressFile(
                                    progress, acquire_session=True
                                )
                            except GlobalWaterwayPackageError as error:
                                self.assertRegex(str(error), "session.*owned")
                                probe["denied"] = True
                            else:
                                contender.close()
                        super().close()

                def probed_connect(*args, **kwargs):
                    return real_connect(*args, factory=ProbedConnection, **kwargs)

                with patch.object(
                    recovery.sqlite3,
                    "connect",
                    side_effect=probed_connect,
                ):
                    result = self._recover(
                        prepared,
                        pause_after_features=pause_after_features,
                        progress_file=progress,
                    )

                self.assertEqual(expected_state, result.state)
                self.assertEqual(
                    {
                        "attempted": True,
                        "denied": True,
                        "output_published": output_published,
                        "transaction_active": transaction_active,
                    },
                    probe,
                )
                released = DurableProgressFile(progress, acquire_session=True)
                released.close()

    def test_recovery_cleanup_orders_closes_and_preserves_primary_errors(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery

        events = []
        operation_error = RuntimeError("primary recovery failure")

        def close_connection() -> None:
            events.append("connection")
            raise RuntimeError("connection close failure")

        def close_progress() -> None:
            events.append("progress")
            raise RuntimeError("progress close failure")

        recovery._close_recovery_resources(
            connection_close=close_connection,
            progress_close=close_progress,
            operation_error=operation_error,
        )
        self.assertEqual(["connection", "progress"], events)
        self.assertEqual(
            [
                "waterway recovery SQLite connection close also failed: "
                "connection close failure",
                "waterway recovery progress session close also failed: "
                "progress close failure",
            ],
            operation_error.__notes__,
        )

        events.clear()
        with self.assertRaisesRegex(
            RuntimeError, "connection close failure"
        ) as raised:
            recovery._close_recovery_resources(
                connection_close=close_connection,
                progress_close=close_progress,
                operation_error=None,
            )
        self.assertEqual(["connection", "progress"], events)
        self.assertEqual(
            [
                "waterway recovery progress session close also failed: "
                "progress close failure"
            ],
            raised.exception.__notes__,
        )

    def test_recovery_exception_remains_primary_when_both_closes_fail(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import waterway_parallel_render as parallel
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root)
            progress = root / "recovery-progress.json"
            real_connect = recovery.sqlite3.connect
            real_progress_close = parallel.DurableProgressFile.close

            class CloseFailConnection(sqlite3.Connection):
                def close(connection) -> None:
                    super().close()
                    raise RuntimeError("connection close failure")

            def probed_connect(*args, **kwargs):
                return real_connect(*args, factory=CloseFailConnection, **kwargs)

            def close_progress_then_fail(durable) -> None:
                real_progress_close(durable)
                raise RuntimeError("progress close failure")

            with patch.object(
                recovery.sqlite3,
                "connect",
                side_effect=probed_connect,
            ), patch.object(
                store,
                "_publish",
                side_effect=GlobalWaterwayPackageError("publication failure"),
            ), patch.object(
                parallel.DurableProgressFile,
                "close",
                new=close_progress_then_fail,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError, "publication failure"
            ) as raised:
                self._recover(prepared, progress_file=progress)

            self.assertEqual(
                [
                    "waterway recovery SQLite connection close also failed: "
                    "connection close failure",
                    "waterway recovery progress session close also failed: "
                    "progress close failure",
                ],
                raised.exception.__notes__,
            )
            released = parallel.DurableProgressFile(
                progress, acquire_session=True
            )
            real_progress_close(released)

    def test_renderer_checkpoint_rejects_external_writer_between_reservations(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            paused = self._recover(prepared, pause_after_features=4)
            self.assertEqual("paused", paused.state)
            real_commit = store._commit_render_checkpoint
            injected = False

            def commit_then_external_writer(
                connection,
                database_path,
                checkpoint,
                peaks,
            ):
                nonlocal injected
                real_commit(connection, database_path, checkpoint, peaks)
                if not injected:
                    external = sqlite3.connect(database_path)
                    try:
                        store._meta_set(
                            external,
                            "syntheticExternalWriter",
                            {"committed": True},
                        )
                        external.commit()
                    finally:
                        external.close()
                    injected = True

            with patch.object(
                store,
                "_commit_render_checkpoint",
                side_effect=commit_then_external_writer,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "another connection",
            ):
                self._recover(prepared)
            self.assertTrue(injected)

    def test_renderer_rejects_external_writer_between_reset_and_staging(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_configure = store._configure_render_connection
            injected = False

            def configure_then_external_writer(connection):
                nonlocal injected
                real_configure(connection)
                external = sqlite3.connect(prepared["database"])
                try:
                    store._meta_set(
                        external,
                        "syntheticPostResetWriter",
                        {"committed": True},
                    )
                    external.commit()
                finally:
                    external.close()
                injected = True

            with patch.object(
                store,
                "_configure_render_connection",
                side_effect=configure_then_external_writer,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "another connection",
            ):
                self._recover(prepared)
            self.assertTrue(injected)

    def test_renderer_rejects_external_writer_after_final_checkpoint(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_commit = store._commit_render_checkpoint
            injected = False

            def commit_then_external_writer(
                connection,
                database_path,
                checkpoint,
                peaks,
            ):
                nonlocal injected
                real_commit(connection, database_path, checkpoint, peaks)
                if checkpoint["renderComplete"] and not injected:
                    external = sqlite3.connect(database_path)
                    try:
                        store._meta_set(
                            external,
                            "syntheticPostRenderWriter",
                            {"committed": True},
                        )
                        external.commit()
                    finally:
                        external.close()
                    injected = True

            with patch.object(
                store,
                "_commit_render_checkpoint",
                side_effect=commit_then_external_writer,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "another connection",
            ):
                self._recover(prepared)
            self.assertTrue(injected)

    def test_renderer_rejects_external_writer_after_runtime_checkpoint(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_resume = store._resume_renderer_write_reservation
            injected = False

            def inject_before_runtime_reservation(connection, trusted_version):
                nonlocal injected
                build_checkpoint = store._meta_get(connection, "buildCheckpoint")
                if (
                    not injected
                    and not connection.in_transaction
                    and isinstance(build_checkpoint, dict)
                    and build_checkpoint.get("nextOrdinal", 0) > 0
                ):
                    external = sqlite3.connect(prepared["database"])
                    try:
                        store._meta_set(
                            external,
                            "syntheticPostRuntimeWriter",
                            {"committed": True},
                        )
                        external.commit()
                    finally:
                        external.close()
                    injected = True
                return real_resume(connection, trusted_version)

            with patch.object(
                store,
                "_resume_renderer_write_reservation",
                side_effect=inject_before_runtime_reservation,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "another connection",
            ):
                self._recover(prepared)
            self.assertTrue(injected)

    def test_recovery_requested_policy_cannot_mint_transition_authority(self) -> None:
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            before = self._snapshot_database(prepared["database"])
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "transition"):
                self._recover(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_recovery_rechecks_bound_sources_at_destructive_boundary(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for key in ("roots", "opl"):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                real_validate = recovery._validate_first_recovery
                validated_snapshot = None

                def drift_after_validation(*args, **kwargs):
                    nonlocal validated_snapshot
                    plan = real_validate(*args, **kwargs)
                    prepared[key].write_bytes(prepared[key].read_bytes() + b"drift\n")
                    validated_snapshot = self._snapshot_database(prepared["database"])
                    return plan

                with patch.object(
                    recovery,
                    "_validate_first_recovery",
                    side_effect=drift_after_validation,
                ), self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
                self.assertEqual(
                    validated_snapshot,
                    self._snapshot_database(prepared["database"]),
                )

    def test_recovery_rechecks_all_renderer_code_at_destructive_boundary(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for drifted_module in ("store", "model", "waterwayParallelRender"):
            with self.subTest(
                drifted_module=drifted_module
            ), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                current = store._render_code_identities()
                drifted = {key: dict(value) for key, value in current.items()}
                drifted[drifted_module]["sha256"] = "f" * 64
                real_validate = recovery._validate_first_recovery
                validated = False

                def mark_validated(*args, **kwargs):
                    nonlocal validated
                    plan = real_validate(*args, **kwargs)
                    validated = True
                    return plan

                def code_identity():
                    return drifted if validated else current

                with patch.object(
                    recovery,
                    "_validate_first_recovery",
                    side_effect=mark_validated,
                ), patch.object(
                    store,
                    "_render_code_identities",
                    side_effect=code_identity,
                ), self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "code identity",
                ):
                    self._recover(prepared)

    def test_recovery_resume_rejects_noncanonical_or_impossible_utc_timestamp(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        malformed_values = (
            "2026-99-99T99:99:99Z",
            "2026-02-29T00:00:00Z",
            "2026-01-01T00:00:00+00:00",
            "2026-01-01T00:00:00.000000Z",
        )
        for malformed in malformed_values:
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                paused = self._recover(prepared, pause_after_features=4)
                self.assertEqual("paused", paused.state)
                connection = sqlite3.connect(prepared["database"])
                try:
                    receipt = store._meta_get(connection, "renderRecoveryReceipt")
                    receipt["recoveredAtUtc"] = malformed
                    store._meta_set(connection, "renderRecoveryReceipt", receipt)
                    connection.commit()
                finally:
                    connection.close()
                before = self._snapshot_database(prepared["database"])
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
                self.assertEqual(before, self._snapshot_database(prepared["database"]))
    def test_recovery_rejects_every_independent_drift_before_mutation(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        mutations = (
            "ingest-incomplete",
            "admission-incomplete",
            "fatal-nonzero",
            "old-run-identity",
            "old-render-identity",
            "checkpoint",
            "render-row-count",
            "source-hash",
            "failure-log-hash",
            "backup-receipt",
            "sqlite-sidecar",
            "output-exists",
            "partial-exists",
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                connection = sqlite3.connect(prepared["database"])
                try:
                    if mutation == "ingest-incomplete":
                        document = store._meta_get(connection, "checkpoint")
                        document["ingestComplete"] = False
                        store._meta_set(connection, "checkpoint", document)
                    elif mutation == "admission-incomplete":
                        document = store._meta_get(connection, "admissionCheckpoint")
                        document["admissionComplete"] = False
                        store._meta_set(connection, "admissionCheckpoint", document)
                    elif mutation == "fatal-nonzero":
                        document = store._meta_get(connection, "admissionReceipt")
                        document["fatalCount"] = 1
                        store._meta_set(connection, "admissionReceipt", document)
                    elif mutation == "old-run-identity":
                        document = store._meta_get(connection, "runIdentity")
                        document["checkpointObjects"] += 1
                        store._meta_set(connection, "runIdentity", document)
                    elif mutation == "old-render-identity":
                        document = store._meta_get(connection, "renderRunIdentity")
                        document["checkpointFeatures"] += 1
                        store._meta_set(connection, "renderRunIdentity", document)
                    elif mutation == "checkpoint":
                        document = store._meta_get(connection, "renderCheckpoint")
                        document["renderedFeatures"] += 1
                        store._meta_set(connection, "renderCheckpoint", document)
                    elif mutation == "render-row-count":
                        connection.execute(
                            "DELETE FROM records WHERE rowid=(SELECT MIN(rowid) FROM records)"
                        )
                    connection.commit()
                finally:
                    connection.close()
                if mutation == "source-hash":
                    prepared["roots"].write_bytes(
                        prepared["roots"].read_bytes() + b"w999\n"
                    )
                elif mutation == "failure-log-hash":
                    prepared["failure_log"].write_bytes(b"different failure\n")
                elif mutation == "backup-receipt":
                    document = json.loads(prepared["backup_receipt"].read_text("utf-8"))
                    document["state"] = "failed"
                    prepared["backup_receipt"].write_bytes(self._canonical(document))
                elif mutation == "output-exists":
                    prepared["output"].mkdir()
                elif mutation == "partial-exists":
                    prepared["output"].with_name(
                        prepared["output"].name + ".partial-unowned"
                    ).mkdir()
                before = self._snapshot_database(prepared["database"])
                if mutation == "sqlite-sidecar":
                    Path(str(prepared["database"]) + "-journal").write_bytes(
                        b"sidecar"
                    )
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
                self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_recovered_publication_reports_actual_bytes_including_recovery_evidence(self) -> None:
        from tools.experiment8 import osm_global_waterway_package as pipeline

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root / "recovery")
            result = self._recover(prepared)
            clean_output = root / "clean"
            clean = pipeline.render_fixture_global_waterway_package(
                opl_path=prepared["opl"],
                root_ids_path=prepared["roots"],
                output_directory=clean_output,
                work_directory=root / "clean-work",
                package_id="fixture-global-waterways-v3",
                source_binding=prepared["binding"],
                checkpoint_objects=4,
                checkpoint_features=2,
            )
            self.assertEqual("complete", result.state)
            self.assertEqual("complete", clean.state)
            for name in ("manifest.json", "records.fadictpack", "tile-index.bin"):
                self.assertEqual(
                    (prepared["output"] / name).read_bytes(),
                    (clean_output / name).read_bytes(),
                    name,
                )
            recovered_receipt = json.loads(
                (prepared["output"] / "build-receipt.json").read_text("utf-8")
            )
            clean_receipt = json.loads(
                (clean_output / "build-receipt.json").read_text("utf-8")
            )
            recovery = recovered_receipt["build"]["recovery"]
            self.assertEqual(1, recovery["resetCount"])
            self.assertTrue(recovery["transactionComplete"])
            binding = recovered_receipt["build"]["sizePolicy"]["binding"]
            self.assertEqual(
                binding,
                recovered_receipt["build"]["runIdentity"]["sizePolicy"],
            )
            self.assertEqual(
                binding,
                recovery["sizePolicyTransition"]["intended"],
            )
            self.assertEqual(
                "budgeted-release-v1",
                recovery["sizePolicyTransition"]["predecessor"]["mode"],
            )
            self.assertTrue(recovery["sizePolicyDecision"]["authorized"])
            actual_published_bytes = sum(
                path.stat().st_size for path in prepared["output"].iterdir()
            )
            self.assertEqual(
                actual_published_bytes,
                recovered_receipt["projection"]["publishedDirectoryBytes"],
            )
            self.assertEqual(
                actual_published_bytes,
                recovered_receipt["build"]["sizePolicy"]["decision"][
                    "requiredPackageBytes"
                ],
            )
            self.assertEqual(actual_published_bytes, recovery["publishedDirectoryBytes"])
            self.assertEqual(
                actual_published_bytes,
                recovery["sizePolicyDecision"]["requiredPackageBytes"],
            )
            self.assertNotEqual(clean_receipt, recovered_receipt)

    def test_recovered_actual_bytes_drive_historical_threshold_booleans(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import reference_size_policy as size_policy

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            size_policy,
            "destination_available_bytes",
            return_value=100_000_000_000,
        ):
            root = Path(temporary)
            first = self._prepare_recovery(root / "first")
            first["authority"] = replace(
                first["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            first_result = self._recover(
                first,
                size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            self.assertEqual("complete", first_result.state)
            threshold = sum(path.stat().st_size for path in first["output"].iterdir())

            second = self._prepare_recovery(root / "second")
            second["authority"] = replace(
                second["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            real_evaluate = size_policy.evaluate_reference_size_policy

            def evaluate_at_boundary(**kwargs):
                decision = dict(real_evaluate(**kwargs))
                crossed = kwargs["required_package_bytes"] >= threshold
                for key in (
                    "preferredComponentPackageCeilingExceeded",
                    "preferredMandatoryPhoneFootprintCeilingExceeded",
                    "hardComponentPackageCeilingExceeded",
                    "hardMandatoryPhoneFootprintCeilingExceeded",
                ):
                    decision[key] = crossed
                return decision

            with patch.object(
                size_policy,
                "evaluate_reference_size_policy",
                side_effect=evaluate_at_boundary,
            ):
                second_result = self._recover(
                    second,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertEqual("complete", second_result.state)
            receipt = second_result.receipt
            actual = sum(path.stat().st_size for path in second["output"].iterdir())
            self.assertGreaterEqual(actual, threshold)
            self.assertEqual(actual, receipt["projection"]["publishedDirectoryBytes"])
            top = receipt["build"]["sizePolicy"]["decision"]
            nested = receipt["build"]["recovery"]["sizePolicyDecision"]
            fields = (
                (
                    "preferredCeilingExceeded",
                    "preferredComponentPackageCeilingExceeded",
                ),
                (
                    "preferredMandatoryPhoneFootprintCeilingExceeded",
                    "preferredMandatoryPhoneFootprintCeilingExceeded",
                ),
                (
                    "hardComponentCeilingExceeded",
                    "hardComponentPackageCeilingExceeded",
                ),
                (
                    "hardMandatoryPhoneFootprintCeilingExceeded",
                    "hardMandatoryPhoneFootprintCeilingExceeded",
                ),
            )
            for projection_key, decision_key in fields:
                with self.subTest(decision_key=decision_key):
                    self.assertTrue(receipt["projection"][projection_key])
                    self.assertTrue(top[decision_key])
                    self.assertTrue(nested[decision_key])
            self.assertEqual(top, nested)

    def test_recovery_resume_rejects_size_policy_transition_tamper(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            paused = self._recover(prepared, pause_after_features=4)
            self.assertEqual("paused", paused.state)
            connection = sqlite3.connect(prepared["database"])
            try:
                receipt = store._meta_get(connection, "renderRecoveryReceipt")
                receipt["sizePolicyTransition"]["intended"]["mode"] = (
                    "complete-uncompressed-visual-evaluation-v1"
                )
                store._meta_set(connection, "renderRecoveryReceipt", receipt)
                connection.commit()
            finally:
                connection.close()
            before = self._snapshot_database(prepared["database"])
            with self.assertRaises(GlobalWaterwayPackageError):
                self._recover(prepared)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_legacy_unbound_budgeted_recovery_transitions_to_visual_policy(self) -> None:
        import sqlite3
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            connection = sqlite3.connect(prepared["database"])
            try:
                predecessor = store._meta_get(connection, "renderRunIdentity")
                predecessor.pop("sizePolicy")
                store._meta_set(connection, "renderRunIdentity", predecessor)
                connection.commit()
                meta_facts = []
                for key in self._PINNED_META:
                    raw = bytes(
                        connection.execute(
                            "SELECT value FROM meta WHERE key=?", (key,)
                        ).fetchone()[0]
                    )
                    byte_count, sha256 = self._fact(raw)
                    meta_facts.append((key, byte_count, sha256))
            finally:
                connection.close()
            database_raw = prepared["database"].read_bytes()
            prepared["authority"] = replace(
                prepared["authority"],
                database_bytes=len(database_raw),
                database_sha256=hashlib.sha256(database_raw).hexdigest(),
                meta_identities=tuple(meta_facts),
                predecessor_size_policy_mode=size_policy.BUDGETED_RELEASE_V1,
                predecessor_size_policy_identity_bound=False,
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            database_fact = {
                "bytes": len(database_raw),
                "sha256": hashlib.sha256(database_raw).hexdigest(),
            }
            backup = json.loads(prepared["backup_receipt"].read_text("utf-8"))
            backup["backupDatabase"] = database_fact
            backup["sourceDatabase"] = database_fact
            prepared["backup_receipt"].write_bytes(self._canonical(backup))
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=100_000_000_000,
            ):
                result = self._recover(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertEqual("complete", result.state)
            transition = result.receipt["build"]["recovery"][
                "sizePolicyTransition"
            ]
            self.assertEqual(
                {
                    "identityBound": False,
                    "mode": size_policy.BUDGETED_RELEASE_V1,
                },
                transition["predecessor"],
            )
            self.assertEqual(
                size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                transition["intended"]["mode"],
            )

    def test_legacy_unbound_recovery_rejects_every_nonvisual_transition(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            for predecessor, intended in (
                (
                    size_policy.BUDGETED_RELEASE_V1,
                    size_policy.BUDGETED_RELEASE_V1,
                ),
                (
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                    size_policy.BUDGETED_RELEASE_V1,
                ),
                (
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1,
                ),
            ):
                with self.subTest(
                    predecessor=predecessor,
                    intended=intended,
                ), self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "budgeted.*visual",
                ):
                    replace(
                        prepared["authority"],
                        predecessor_size_policy_mode=predecessor,
                        predecessor_size_policy_identity_bound=False,
                        intended_size_policy_mode=intended,
                    )

    def test_recovery_interruption_resumes_without_second_reset(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_package as pipeline
        from tools.experiment8 import osm_global_waterway_store as store

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            prepared = self._prepare_recovery(root / "recovery")
            paused = self._recover(prepared, pause_after_features=4)
            self.assertEqual("paused", paused.state)
            connection = sqlite3.connect(prepared["database"])
            try:
                first_receipt = bytes(
                    connection.execute(
                        "SELECT value FROM meta WHERE key='renderRecoveryReceipt'"
                    ).fetchone()[0]
                )
                self.assertEqual(
                    1, store._meta_get(connection, "renderRecoveryReceipt")["resetCount"]
                )
            finally:
                connection.close()
            resumed = self._recover(prepared)
            self.assertEqual("complete", resumed.state)
            connection = sqlite3.connect(prepared["database"])
            try:
                second_receipt = bytes(
                    connection.execute(
                        "SELECT value FROM meta WHERE key='renderRecoveryReceipt'"
                    ).fetchone()[0]
                )
            finally:
                connection.close()
            first_document = json.loads(first_receipt)
            second_document = json.loads(second_receipt)
            self.assertNotIn("publishedDirectoryBytes", second_document)
            self.assertNotIn("sizePolicyDecision", second_document)
            self.assertEqual(1, second_document["resetCount"])
            self.assertEqual(first_document, second_document)

            clean_output = root / "clean"
            clean = pipeline.render_fixture_global_waterway_package(
                opl_path=prepared["opl"],
                root_ids_path=prepared["roots"],
                output_directory=clean_output,
                work_directory=root / "clean-work",
                package_id="fixture-global-waterways-v3",
                source_binding=prepared["binding"],
                checkpoint_objects=4,
                checkpoint_features=2,
            )
            self.assertEqual("complete", clean.state)
            for name in ("manifest.json", "records.fadictpack", "tile-index.bin"):
                self.assertEqual(
                    (prepared["output"] / name).read_bytes(),
                    (clean_output / name).read_bytes(),
                    name,
                )

    def test_recovery_publication_stage_is_validatable_and_resumable(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError, "atomic.*publication"
                ):
                    self._recover(prepared)
            self.assertFalse(prepared["output"].exists())
            self.assertEqual(
                1,
                len(
                    tuple(
                        prepared["output"].parent.glob(
                            prepared["output"].name + ".partial-*"
                        )
                    )
                ),
            )
            validation = self._validate_recovery(prepared)
            self.assertTrue(validation["resumed"])
            resumed = self._recover(prepared)
            self.assertEqual("complete", resumed.state)
            self.assertTrue(prepared["output"].is_dir())

    def test_validate_recovery_resume_requires_owned_partial_checkpoint(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
            connection = sqlite3.connect(prepared["database"])
            try:
                connection.execute(
                    "DELETE FROM meta WHERE key='partialDirectoryOwner'"
                )
                connection.commit()
            finally:
                connection.close()
            before = self._snapshot_database(prepared["database"])
            with self.assertRaisesRegex(GlobalWaterwayPackageError, "partial.*owner"):
                self._validate_recovery(prepared)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_visual_recovery_capacity_checkpoint_cannot_mint_receipt_evidence(self) -> None:
        import sqlite3
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            prepared["authority"] = replace(
                prepared["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(
                        prepared,
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                    )
            connection = sqlite3.connect(prepared["database"])
            try:
                self.assertIsNone(store._meta_get(connection, "sizePolicyCapacity"))
                store._meta_set(
                    connection,
                    "sizePolicyCapacity",
                    {
                        "schema": "forged-retired-capacity-authority",
                        "availableDestinationBytesBeforeStaging": 10**15,
                    },
                )
                connection.commit()
            finally:
                connection.close()
            before = self._snapshot_database(prepared["database"])
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=size_policy.DESTINATION_RESERVE_BYTES - 1,
            ):
                with self.assertRaisesRegex(
                    GlobalWaterwayPackageError,
                    "available|capacity|reserve",
                ):
                    self._validate_recovery(
                        prepared,
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                    )
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_visual_recovery_resumes_after_boundary_measurement_failure(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            prepared["authority"] = replace(
                prepared["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            calls = 0

            def fail_boundary(_output_directory):
                nonlocal calls
                calls += 1
                if calls == 1:
                    return 100_000_000_000
                raise size_policy.ReferenceSizePolicyError(
                    "synthetic boundary read failure"
                )

            with patch.object(
                size_policy,
                "destination_available_bytes",
                side_effect=fail_boundary,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError, "synthetic boundary read failure"
            ):
                self._recover(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=100_000_000_000,
            ):
                validation = self._validate_recovery(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
                self.assertTrue(validation["resumed"])
                resumed = self._recover(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertEqual("complete", resumed.state)
            self.assertTrue(prepared["output"].is_dir())

    def test_visual_recovery_publication_stage_preserves_capacity_binding(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            size_policy,
            "destination_available_bytes",
            return_value=100_000_000_000,
        ):
            prepared = self._prepare_recovery(Path(temporary))
            prepared["authority"] = replace(
                prepared["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(
                        prepared,
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                    )
            validation = self._validate_recovery(
                prepared,
                size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            self.assertTrue(validation["resumed"])
            resumed = self._recover(
                prepared,
                size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            self.assertEqual("complete", resumed.state)
            self.assertTrue(prepared["output"].is_dir())

    def test_visual_recovery_validation_rejects_forged_sqlite_publication_bytes(self) -> None:
        import sqlite3
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            size_policy,
            "destination_available_bytes",
            return_value=100_000_000_000,
        ):
            prepared = self._prepare_recovery(Path(temporary))
            prepared["authority"] = replace(
                prepared["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            with patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(
                        prepared,
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                    )
            connection = sqlite3.connect(prepared["database"])
            try:
                receipt = store._meta_get(connection, "renderRecoveryReceipt")
                forged = store._bind_publication_boundary_capacity(
                    store.enforce_global_waterway_storage_ceiling(
                        1,
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                        available_destination_bytes=100_000_000_000,
                    ),
                    destination_free_bytes=100_000_000_000,
                )
                receipt["publishedDirectoryBytes"] = 1
                receipt["sizePolicyDecision"] = dict(forged)
                store._meta_set(connection, "renderRecoveryReceipt", receipt)
                connection.commit()
            finally:
                connection.close()
            before = self._snapshot_database(prepared["database"])
            with self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "receipt|published.*bytes|partial.*accounting",
            ):
                self._validate_recovery(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_visual_recovery_validation_uses_fresh_free_space_without_mutation(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8 import reference_size_policy as size_policy
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            prepared["authority"] = replace(
                prepared["authority"],
                intended_size_policy_mode=(
                    size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                ),
            )
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=100_000_000_000,
            ), patch.object(store.os, "rename", side_effect=OSError("stop")):
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(
                        prepared,
                        size_policy_mode=(
                            size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                        ),
                    )
            partial = next(prepared["output"].parent.glob("recovered.partial-*"))
            before_database = self._snapshot_database(prepared["database"])
            before_partial = {
                path.name: path.read_bytes() for path in partial.iterdir()
            }
            with patch.object(
                size_policy,
                "destination_available_bytes",
                return_value=size_policy.DESTINATION_RESERVE_BYTES - 1,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "capacity|reserve",
            ):
                self._validate_recovery(
                    prepared,
                    size_policy_mode=(
                        size_policy.COMPLETE_UNCOMPRESSED_VISUAL_EVALUATION_V1
                    ),
                )
            self.assertEqual(
                before_database,
                self._snapshot_database(prepared["database"]),
            )
            self.assertEqual(
                before_partial,
                {path.name: path.read_bytes() for path in partial.iterdir()},
            )

    def test_recovery_resume_rejects_malformed_sqlite_overhead_canonically(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for malformed in (None, "0", True, -1):
            with self.subTest(malformed=malformed), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                paused = self._recover(prepared, pause_after_features=4)
                self.assertEqual("paused", paused.state)
                connection = sqlite3.connect(prepared["database"])
                try:
                    receipt = store._meta_get(connection, "renderRecoveryReceipt")
                    receipt["sqliteEvidenceOverheadBytes"] = malformed
                    store._meta_set(connection, "renderRecoveryReceipt", receipt)
                    connection.commit()
                finally:
                    connection.close()
                before = self._snapshot_database(prepared["database"])
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
                self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_recovery_rechecks_exact_incident_under_write_reservation(self) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_validate = recovery._validate_first_recovery
            drifted_snapshot = None

            def drift_after_validation(*args, **kwargs):
                nonlocal drifted_snapshot
                plan = real_validate(*args, **kwargs)
                connection = sqlite3.connect(prepared["database"])
                try:
                    checkpoint = store._meta_get(connection, "renderCheckpoint")
                    checkpoint["renderedFeatures"] += 1
                    store._meta_set(connection, "renderCheckpoint", checkpoint)
                    connection.commit()
                finally:
                    connection.close()
                drifted_snapshot = self._snapshot_database(prepared["database"])
                return plan

            with patch.object(
                recovery,
                "_validate_first_recovery",
                side_effect=drift_after_validation,
            ):
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
            self.assertIsNotNone(drifted_snapshot)
            self.assertEqual(
                drifted_snapshot,
                self._snapshot_database(prepared["database"]),
            )

    def test_recovery_hashes_database_before_windows_write_reservation(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_connect = recovery.sqlite3.connect
            real_identity = recovery.source_module._stream_identity
            connections = []

            def recording_connect(*args, **kwargs):
                connection = real_connect(*args, **kwargs)
                connections.append(connection)
                return connection

            def reject_locked_database_hash(path, label):
                if label == "waterway recovery source database":
                    self.assertTrue(connections)
                    self.assertFalse(
                        connections[-1].in_transaction,
                        "Windows cannot stream SQLite lock bytes after BEGIN IMMEDIATE",
                    )
                return real_identity(path, label)

            with patch.object(
                recovery.sqlite3,
                "connect",
                side_effect=recording_connect,
            ), patch.object(
                recovery.source_module,
                "_stream_identity",
                side_effect=reject_locked_database_hash,
            ):
                result = self._recover(prepared, pause_after_features=2)

            self.assertEqual("paused", result.state)

    def test_recovery_rejects_database_drift_between_hash_and_write_reservation(
        self,
    ) -> None:
        import sqlite3

        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8 import osm_global_waterway_store as store
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            real_signature = recovery._recovery_database_stat_signature
            drifted = False
            signature_calls = 0

            def drift_after_post_hash_signature(path):
                nonlocal drifted, signature_calls
                signature = real_signature(path)
                signature_calls += 1
                if signature_calls == 2:
                    external = sqlite3.connect(prepared["database"])
                    try:
                        store._meta_set(
                            external,
                            "syntheticPreReservationWriter",
                            {"committed": True},
                        )
                        external.commit()
                    finally:
                        external.close()
                    drifted = True
                return signature

            with patch.object(
                recovery,
                "_recovery_database_stat_signature",
                side_effect=drift_after_post_hash_signature,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "database changed between identity and write reservation",
            ):
                self._recover(prepared)

            self.assertTrue(drifted)

    def test_recovery_rechecks_output_paths_under_write_reservation(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for mutation in ("output", "partial"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                real_validate = recovery._validate_first_recovery
                validated_snapshot = None

                def create_path_after_validation(*args, **kwargs):
                    nonlocal validated_snapshot
                    plan = real_validate(*args, **kwargs)
                    if mutation == "output":
                        prepared["output"].mkdir()
                    else:
                        prepared["output"].with_name(
                            prepared["output"].name + ".partial-raced"
                        ).mkdir()
                    validated_snapshot = self._snapshot_database(
                        prepared["database"]
                    )
                    return plan

                with patch.object(
                    recovery,
                    "_validate_first_recovery",
                    side_effect=create_path_after_validation,
                ):
                    with self.assertRaises(GlobalWaterwayPackageError):
                        self._recover(prepared)
                self.assertIsNotNone(validated_snapshot)
                self.assertEqual(
                    validated_snapshot,
                    self._snapshot_database(prepared["database"]),
                )

    def test_recovery_rechecks_output_paths_after_database_hash(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        for mutation in ("output", "partial"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                prepared = self._prepare_recovery(Path(temporary))
                real_identity = recovery.source_module._stream_identity
                hashed_snapshot = None

                def create_path_after_database_hash(path, label):
                    nonlocal hashed_snapshot
                    identity = real_identity(path, label)
                    if label == "waterway recovery source database":
                        if mutation == "output":
                            prepared["output"].mkdir()
                        else:
                            prepared["output"].with_name(
                                prepared["output"].name + ".partial-raced"
                            ).mkdir()
                        hashed_snapshot = self._snapshot_database(
                            prepared["database"]
                        )
                    return identity

                with patch.object(
                    recovery.source_module,
                    "_stream_identity",
                    side_effect=create_path_after_database_hash,
                ):
                    with self.assertRaises(GlobalWaterwayPackageError):
                        self._recover(prepared)
                self.assertIsNotNone(hashed_snapshot)
                self.assertEqual(
                    hashed_snapshot,
                    self._snapshot_database(prepared["database"]),
                )

    def test_recovery_rechecks_full_boundary_after_destructive_input_hashing(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            before = self._snapshot_database(prepared["database"])
            real_require = recovery._require_destructive_inputs_current

            def race_output_after_hashing(**kwargs):
                real_require(**kwargs)
                prepared["output"].mkdir()

            with patch.object(
                recovery,
                "_require_destructive_inputs_current",
                side_effect=race_output_after_hashing,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "output directory appeared before reset",
            ):
                self._recover(prepared)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_recovery_fast_boundary_is_last_after_slow_hashes(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            before = self._snapshot_database(prepared["database"])
            real_require = recovery._require_recovery_code_current
            calls = 0

            def race_output_after_final_slow_check(*args, **kwargs):
                nonlocal calls
                real_require(*args, **kwargs)
                calls += 1
                if calls == 3:
                    prepared["output"].mkdir()

            with patch.object(
                recovery,
                "_require_recovery_code_current",
                side_effect=race_output_after_final_slow_check,
            ), self.assertRaisesRegex(
                GlobalWaterwayPackageError,
                "output directory appeared before reset",
            ):
                self._recover(prepared)
            self.assertEqual(3, calls)
            self.assertEqual(before, self._snapshot_database(prepared["database"]))

    def test_recovery_code_identity_is_rechecked_before_publication(self) -> None:
        from tools.experiment8 import osm_global_waterway_recovery as recovery
        from tools.experiment8.osm_global_waterway_package import (
            GlobalWaterwayPackageError,
        )

        with tempfile.TemporaryDirectory() as temporary:
            prepared = self._prepare_recovery(Path(temporary))
            current = recovery._recovery_code_identity()
            drifted = dict(current)
            drifted["sha256"] = "f" * 64
            with patch.object(
                recovery,
                "_recovery_code_identity",
                side_effect=(current, current, current, drifted),
            ) as identity:
                with self.assertRaises(GlobalWaterwayPackageError):
                    self._recover(prepared)
            self.assertEqual(4, identity.call_count)
            self.assertFalse(prepared["output"].exists())
            self.assertFalse(prepared["output"].is_symlink())

    def test_store_cannot_mint_recovery_authority(self) -> None:
        from tools.experiment8 import osm_global_waterway_store as store

        self.assertNotIn("recover_global_waterway_package", store.__all__)
        self.assertFalse(hasattr(store, "recover_global_waterway_package"))
        self.assertFalse(hasattr(store, "WaterwayRenderRecoveryAuthority"))


if __name__ == "__main__":
    unittest.main()
