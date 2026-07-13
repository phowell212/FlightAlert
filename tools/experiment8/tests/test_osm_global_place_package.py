from __future__ import annotations

import hashlib
import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

from tools.experiment8.model import TileKey
from tools.experiment8.reference_presentation_policy import SemanticSubtype
from tools.experiment8.renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    MAX_RECORDS_PER_TILE,
    MAX_TILE_BYTES,
    decode_tile_payload,
)
from tools.experiment8.semantic_model import renderer_contract_hash
from tools.experiment8.sourced_text import LayoutMode


FIXTURE = Path(__file__).parent / "fixtures" / "global-place-nodes.opl"


def _source_binding_for(opl_path: Path, outcome_path: Path):
    from tools.experiment8 import osm_global_place_package as pipeline
    from tools.experiment8 import osm_global_place_store as store
    from tools.experiment8.osm_global_place_package import PlaceSourceBinding

    raw_bytes = opl_path.stat().st_size
    digest = hashlib.sha256()
    with opl_path.open("rb") as handle:
        while chunk := handle.read(97):
            digest.update(chunk)
    raw_sha256 = digest.hexdigest()
    outcome_buffer = io.BytesIO()
    with opl_path.open("rb") as handle:
        strict_audit, semantic_audit, outcome_audit = (
            store._semantic_outcome_audits_stream(
                handle,
                source_generation_sha256=(
                    "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
                ),
                outcome_stream=outcome_buffer,
            )
        )
    outcome_raw = outcome_buffer.getvalue()
    if outcome_path.exists():
        if outcome_path.read_bytes() != outcome_raw:
            raise AssertionError("fixture semantic outcome evidence differs")
    else:
        outcome_path.write_bytes(outcome_raw)
    return PlaceSourceBinding(
        planet_path="planet-260629.osm.pbf",
        planet_bytes=93_653_630_756,
        planet_sha256=(
            "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f"
        ),
        candidate_pbf_bytes=12_345,
        candidate_pbf_sha256=hashlib.sha256(b"fixture-place-pbf").hexdigest(),
        opl_bytes=raw_bytes,
        opl_sha256=raw_sha256,
        extraction_receipt_sha256=hashlib.sha256(b"fixture-extraction-receipt").hexdigest(),
        recovery_receipt_sha256=None,
        renderer_semantic_outcome_path=str(outcome_path),
        renderer_semantic_outcome_bytes=len(outcome_raw),
        renderer_semantic_outcome=(
            pipeline.PlaceRendererSemanticOutcome.from_document(outcome_audit)
        ),
        semantic_admission_policy_sha256=pipeline.SEMANTIC_ADMISSION_POLICY_SHA256,
        semantic_admission=pipeline.PlaceSemanticAdmissionAudit.from_documents(
            strict_audit, semantic_audit
        ),
    )


def _source_binding(outcome_path: Path):
    return _source_binding_for(FIXTURE, outcome_path)


def _iter_present_payloads(package: Path):
    manifest = json.loads((package / "manifest.json").read_text("utf-8"))
    records = package / "records.fadictpack"
    index = package / "tile-index.bin"
    ordinal = 0
    with records.open("rb") as records_handle, index.open("rb") as index_handle:
        for window in manifest["coverage"]["zoomRanges"]:
            for y in range(window["yMin"], window["yMax"] + 1):
                for x in range(window["xMin"], window["xMax"] + 1):
                    entry = index_handle.read(INDEX_ENTRY_BYTES)
                    if len(entry) != INDEX_ENTRY_BYTES:
                        raise AssertionError(f"short index at ordinal {ordinal}")
                    ordinal += 1
                    if entry == b"\0" * INDEX_ENTRY_BYTES:
                        continue
                    offset, compressed_length, raw_length, expected_hash32, flags = (
                        struct.unpack("<QIIII", entry)
                    )
                    assert flags == 1
                    records_handle.seek(offset)
                    compressed = records_handle.read(compressed_length)
                    payload = zlib.decompress(compressed, wbits=-zlib.MAX_WBITS)
                    assert len(payload) == raw_length
                    assert (
                        int.from_bytes(hashlib.sha256(payload).digest()[:4], "big")
                        == expected_hash32
                    )
                    tile = TileKey(window["z"], x, y)
                    yield tile, payload, decode_tile_payload(tile, payload)
        assert ordinal == manifest["coverage"]["tileCount"]
        assert index_handle.read(1) == b""


def _build(root: Path, name: str, **kwargs):
    from tools.experiment8.osm_global_place_package import render_global_place_package

    return render_global_place_package(
        opl_path=FIXTURE,
        output_directory=root / name,
        work_directory=root / f"{name}-work",
        package_id="global-osm-place-fixture-v3",
        source_binding=_source_binding(root / f"{name}-semantic-outcomes.bin"),
        zooms=tuple(range(4, 12)),
        checkpoint_nodes=2,
        **kwargs,
    )


def _make_recovery_stage(root: Path, recovery, pipeline):
    from tools.experiment8 import osm_global_place_store as store

    output_name = "fixture-recovered-extraction"
    source_raw = b"fixture-production-planet"
    source = {
        "bytes": len(source_raw),
        "path": str(root / "fixture-planet.pbf"),
        "sha256": hashlib.sha256(source_raw).hexdigest(),
    }
    extractor_code = dict(recovery.RECOVERABLE_EXTRACTOR_CODE)
    runtime = pipeline._pinned_runtime_document()
    provisional = {
        "code": extractor_code,
        "outputName": output_name,
        "runtime": runtime,
        "schema": "flightalert.experiment8.osm-global-place-extraction-identity.v1",
        "source": source,
    }
    provisional_sha256 = hashlib.sha256(
        pipeline._canonical_json_bytes(provisional)
    ).hexdigest()
    stage_name = output_name + ".partial-" + provisional_sha256[:16]
    stage = root / stage_name
    stage.mkdir()
    commands = pipeline.build_osmium_extraction_commands(
        planet_linux_path="/mnt/test/fixture-planet.pbf",
        stage_linux_directory="/mnt/test/" + stage_name,
    )
    opl_raw = b"".join(
        (
            b"n1 v1 dV t2026-06-29T00:00:00Z Tname=Pennard,note=First%0a%Second,place=village x1 y1\n",
            b"n2 v1 dV t2026-06-29T00:00:00Z Tname=Bad%0a%Name,place=town x2 y2\n",
            b"n3 v1 dV t2026-06-29T00:00:00Z Tname=Visible,place=town x3 y3\n",
        )
    )
    candidate_pbf = b"fixture-recovery-place-pbf"
    files = {}
    executions = []

    def identity(path: Path, name: str):
        raw = path.read_bytes()
        return {
            "bytes": len(raw),
            "name": name,
            "sha256": hashlib.sha256(raw).hexdigest(),
        }

    for ordinal, command in enumerate(commands, start=1):
        prefix = f"command-{ordinal:02d}-{command.role}"
        stdout = stage / f"{prefix}.stdout"
        stderr = stage / f"{prefix}.stderr"
        stdout.write_bytes(f"{ordinal}:{command.role}:stdout\n".encode("ascii"))
        stderr.write_bytes(b"")
        output_documents = [identity(stdout, stdout.name), identity(stderr, stderr.name)]
        if command.role == "tags-filter":
            artifact = stage / "place-nodes.pbf.partial"
            artifact.write_bytes(candidate_pbf)
            output_documents.append(identity(artifact, artifact.name))
        if command.role == "cat":
            artifact = stage / "place-nodes.opl.partial"
            artifact.write_bytes(opl_raw)
            output_documents.append(identity(artifact, artifact.name))
        for document in output_documents:
            files[document["name"]] = {
                "bytes": document["bytes"],
                "sha256": document["sha256"],
            }
        executions.append(
            {
                "arguments": list(command.arguments),
                "commandSha256": pipeline._command_sha256(command),
                "exitCode": 0,
                "outputs": output_documents,
                "role": command.role,
            }
        )
    run_identity = {
        **provisional,
        "commands": [list(command.arguments) for command in commands],
    }
    run_sha256 = hashlib.sha256(
        pipeline._canonical_json_bytes(run_identity)
    ).hexdigest()
    checkpoint = {
        "executions": executions,
        "files": files,
        "runIdentity": run_identity,
        "runIdentitySha256": run_sha256,
        "schema": "flightalert.experiment8.osm-global-place-extraction-checkpoint.v1",
    }
    checkpoint_raw = pipeline._canonical_json_bytes(checkpoint)
    checkpoint_path = stage / "extraction-checkpoint.json"
    checkpoint_path.write_bytes(checkpoint_raw)
    strict_audit, semantic_audit, outcome_audit = (
        store._semantic_outcome_audits_stream(
            io.BytesIO(opl_raw),
            source_generation_sha256=source["sha256"],
        )
    )
    output = root / output_name
    contract = recovery._RecoveryContract(
        checkpoint_bytes=len(checkpoint_raw),
        checkpoint_sha256=hashlib.sha256(checkpoint_raw).hexdigest(),
        extractor_code_bytes=extractor_code["bytes"],
        extractor_code_sha256=extractor_code["sha256"],
        extractor_run_identity_sha256=run_sha256,
        output_name=output_name,
        recovery_output_name=output_name,
        recovery_output_path=str(output),
        renderer_semantic_outcome_sha256=outcome_audit["sha256"],
        renderer_semantic_outcome_document_sha256=hashlib.sha256(
            pipeline._canonical_json_bytes(outcome_audit)
        ).hexdigest(),
        source_bytes=source["bytes"],
        source_path=source["path"],
        source_sha256=source["sha256"],
        stage_name=stage_name,
        strict_opl_audit_sha256=hashlib.sha256(
            pipeline._canonical_json_bytes(strict_audit)
        ).hexdigest(),
        semantic_admission_audit_sha256=hashlib.sha256(
            pipeline._canonical_json_bytes(semantic_audit)
        ).hexdigest(),
    )
    return stage, output, contract, checkpoint, opl_raw, candidate_pbf


class StrictPlaceOplTests(unittest.TestCase):
    def test_source_and_store_modules_import_independently(self) -> None:
        environment = dict(__import__("os").environ)
        environment["PYTHONDONTWRITEBYTECODE"] = "1"
        for module in (
            "tools.experiment8.osm_global_place_package",
            "tools.experiment8.osm_global_place_store",
        ):
            completed = subprocess.run(
                [sys.executable, "-c", f"import {module}"],
                cwd=Path(__file__).parents[3],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, completed.returncode, completed.stderr)

    def test_streams_exact_multilingual_current_nodes_without_whole_read(self) -> None:
        from tools.experiment8.osm_global_place_package import iter_strict_place_opl

        class BoundedReader(io.BytesIO):
            largest_read = 0

            def read(self, size: int = -1) -> bytes:
                if size < 0:
                    raise AssertionError("whole-stream read is forbidden")
                self.largest_read = max(self.largest_read, size)
                return super().read(size)

        reader = BoundedReader(FIXTURE.read_bytes())
        parsed = list(iter_strict_place_opl(reader, read_chunk_bytes=31))

        self.assertEqual(tuple(range(1, 11)), tuple(item.node.object_id for item in parsed))
        self.assertEqual("Αθήνα", dict(parsed[0].node.tags)["name"])
        self.assertEqual("القاهرة", dict(parsed[1].node.tags)["name"])
        self.assertEqual("北京市", dict(parsed[2].node.tags)["name"])
        self.assertEqual("Madrid", dict(parsed[3].node.tags)["name"])
        self.assertEqual(" ", dict(parsed[6].node.tags)["name"])
        self.assertEqual("1,000,000", dict(parsed[8].node.tags)["population"])
        self.assertEqual((237_275_390, 379_838_100), (
            parsed[0].node.longitude_e7,
            parsed[0].node.latitude_e7,
        ))
        self.assertLessEqual(reader.largest_read, 31)

    def test_rejects_history_duplicates_nonmonotonic_and_non_nodes(self) -> None:
        from tools.experiment8.osm_global_place_package import (
            GlobalPlacePackageError,
            iter_strict_place_opl,
        )

        prefix = (
            b"n2 v1 dV t2026-06-29T00:00:00Z Tname=Two,place=town x2 y2\n"
        )
        cases = (
            (prefix + prefix, "duplicate/history"),
            (
                prefix
                + b"n1 v1 dV t2026-06-29T00:00:00Z Tname=One,place=town x1 y1\n",
                "monotonic",
            ),
            (
                b"w1 v1 dV t2026-06-29T00:00:00Z Tname=Way,place=town Nn1,n2\n",
                "direct NODE",
            ),
        )
        for raw, message in cases:
            with self.subTest(message=message), self.assertRaisesRegex(
                GlobalPlacePackageError, message
            ):
                list(iter_strict_place_opl(io.BytesIO(raw), read_chunk_bytes=13))

    def test_resume_begins_at_an_exact_committed_line_boundary(self) -> None:
        from tools.experiment8.osm_global_place_package import iter_strict_place_opl

        raw = FIXTURE.read_bytes()
        first = list(iter_strict_place_opl(io.BytesIO(raw), read_chunk_bytes=37))[:3]
        offset = first[-1].byte_end
        resumed_stream = io.BytesIO(raw)
        resumed_stream.seek(offset)

        resumed = list(
            iter_strict_place_opl(
                resumed_stream,
                read_chunk_bytes=29,
                initial_byte_offset=offset,
                initial_line_number=3,
                previous_node_id=3,
            )
        )

        self.assertEqual(tuple(range(4, 11)), tuple(item.node.object_id for item in resumed))
        self.assertEqual(offset, resumed[0].byte_start)
        self.assertEqual(4, resumed[0].line_number)

    def test_canonical_subdegree_negative_coordinates_are_worldwide_valid(self) -> None:
        from tools.experiment8.osm_global_place_package import iter_strict_place_opl

        raw = (
            b"n1 v1 dV t2026-06-29T00:00:00Z "
            b"Tname=Greenwich,place=town x-0.1276 y-0.25\n"
        )
        parsed = list(iter_strict_place_opl(io.BytesIO(raw), read_chunk_bytes=17))

        self.assertEqual(-1_276_000, parsed[0].node.longitude_e7)
        self.assertEqual(-2_500_000, parsed[0].node.latitude_e7)

    def test_irrelevant_control_value_is_structure_checked_but_not_semantically_decoded(
        self,
    ) -> None:
        from tools.experiment8.osm_global_place_package import (
            GlobalPlacePackageError,
            iter_strict_place_opl,
        )

        clean = (
            b"n1 v1 dV t2026-06-29T00:00:00Z "
            b"Tname=Pennard,note=First%20%line,place=village x-4.066 y51.581\n"
        )
        controlled = (
            b"n1 v1 dV t2026-06-29T00:00:00Z "
            b"Tname=Pennard,note=First%0a%Second,place=village x-4.066 y51.581\n"
        )
        try:
            clean_node = list(iter_strict_place_opl(io.BytesIO(clean)))[0]
            controlled_node = list(iter_strict_place_opl(io.BytesIO(controlled)))[0]
        except GlobalPlacePackageError as error:
            self.fail(f"irrelevant tag control rejected the place node: {error}")

        self.assertEqual(clean_node.node, controlled_node.node)
        self.assertEqual(
            (("name", "Pennard"), ("place", "village")),
            controlled_node.node.tags,
        )
        self.assertEqual((), controlled_node.admitted_control_fields)
        self.assertNotEqual(hashlib.sha256(clean).digest(), hashlib.sha256(controlled).digest())

        malformed = controlled.replace(b"%0a%", b"%0g%")
        with self.assertRaisesRegex(GlobalPlacePackageError, "malformed OPL escape"):
            list(iter_strict_place_opl(io.BytesIO(malformed)))

    def test_escaped_nul_follows_the_same_semantic_control_policy(self) -> None:
        from tools.experiment8.osm_global_place_package import iter_strict_place_opl

        irrelevant = (
            b"n1 v1 dV t2026-06-29T00:00:00Z "
            b"Tname=Pennard,note=First%0%Second,place=village x1 y1\n"
        )
        admitted = (
            b"n1 v1 dV t2026-06-29T00:00:00Z "
            b"Tname=Bad%0%Name,place=village x1 y1\n"
        )

        irrelevant_node = list(iter_strict_place_opl(io.BytesIO(irrelevant)))[0]
        admitted_node = list(iter_strict_place_opl(io.BytesIO(admitted)))[0]

        self.assertEqual((), irrelevant_node.admitted_control_fields)
        self.assertEqual(("name",), admitted_node.admitted_control_fields)


class ExtractionBoundaryTests(unittest.TestCase):
    def test_exact_planet_constants_and_osmium_commands_are_bound(self) -> None:
        from tools.experiment8.osm_global_place_package import (
            EXPECTED_PLANET_BYTES,
            EXPECTED_PLANET_PATH,
            EXPECTED_PLANET_SHA256,
            OSMIUM_BINARY_SHA256,
            build_osmium_extraction_commands,
        )

        self.assertEqual(
            Path(r"D:\FlightAlert-test-artifacts\experiment 8\planet-260629-reacquire\planet-260629.osm.pbf"),
            EXPECTED_PLANET_PATH,
        )
        self.assertEqual(93_653_630_756, EXPECTED_PLANET_BYTES)
        self.assertEqual(
            "cd5113a1ac905fc33eef8f2a7d1276b31036b06aa07b65aa3b3fa86cc0fcc96f",
            EXPECTED_PLANET_SHA256,
        )
        self.assertEqual(
            "5575922905fb39fa87262e74ed2b0ac367086b5439468339e45bf3720c1821fc",
            OSMIUM_BINARY_SHA256,
        )
        commands = build_osmium_extraction_commands(
            planet_linux_path="/mnt/d/planet.osm.pbf",
            stage_linux_directory="/mnt/d/place-stage",
        )
        self.assertEqual(("fileinfo", "tags-filter", "fileinfo", "cat", "fileinfo"), tuple(
            command.role for command in commands
        ))
        tags = commands[1].arguments
        self.assertIn("-R", tags)
        self.assertIn("-O", tags)
        self.assertIn("pbf", tags[tags.index("-F") + 1 : tags.index("-F") + 2])
        self.assertIn("n/place", tags)
        self.assertNotIn("w/place", tags)
        self.assertNotIn("r/place", tags)
        self.assertTrue(any(value.endswith("/place-nodes.pbf.partial") for value in tags))
        cat = commands[3].arguments
        self.assertIn("opl", cat)
        self.assertIn("-O", cat)
        self.assertEqual("pbf", cat[cat.index("-F") + 1])
        self.assertTrue(any(value.endswith("/place-nodes.opl.partial") for value in cat))
        self.assertEqual("pbf", commands[2].arguments[commands[2].arguments.index("-F") + 1])
        self.assertEqual("opl", commands[4].arguments[commands[4].arguments.index("-F") + 1])

    def test_short_planet_is_rejected_before_hashing_or_process_launch(self) -> None:
        from tools.experiment8.osm_global_place_package import (
            GlobalPlacePackageError,
            verify_file_identity,
        )

        with tempfile.TemporaryDirectory() as temporary:
            planet = Path(temporary) / "planet.osm.pbf"
            planet.write_bytes(b"incomplete")
            with self.assertRaisesRegex(GlobalPlacePackageError, "byte length"):
                verify_file_identity(
                    planet,
                    expected_bytes=93_653_630_756,
                    expected_sha256="0" * 64,
                )

    def test_file_identity_rejects_whole_file_read_chunk_values(self) -> None:
        from tools.experiment8.osm_global_place_package import (
            GlobalPlacePackageError,
            verify_file_identity,
        )

        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source.bin"
            raw = b"bounded-source"
            source.write_bytes(raw)
            for invalid in (-1, 0, True):
                with self.subTest(read_chunk_bytes=invalid), self.assertRaisesRegex(
                    GlobalPlacePackageError, "read chunk"
                ):
                    verify_file_identity(
                        source,
                        expected_bytes=len(raw),
                        expected_sha256=hashlib.sha256(raw).hexdigest(),
                        read_chunk_bytes=invalid,
                    )

    def test_extraction_retains_artifact_command_runtime_and_source_bindings(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline

        fixture_raw = FIXTURE.read_bytes()
        planet_raw = b"complete-fixture-planet"
        candidate_pbf = b"fixture-current-node-place-pbf"
        runtime = pipeline._pinned_runtime_document()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.osm.pbf"
            output = root / "extraction"
            planet.write_bytes(planet_raw)

            def fake_run(command, stdout_path, stderr_path):
                stdout_path.write_bytes((command.role + " stdout\n").encode("ascii"))
                stderr_path.write_bytes((command.role + " stderr\n").encode("ascii"))
                stage = stdout_path.parent
                if command.role == "tags-filter":
                    (stage / "place-nodes.pbf.partial").write_bytes(candidate_pbf)
                if command.role == "cat":
                    (stage / "place-nodes.opl.partial").write_bytes(fixture_raw)
                return 0

            with patch.object(
                pipeline, "_attest_pinned_osmium", return_value=runtime
            ), patch.object(
                pipeline,
                "_windows_to_wsl_path",
                side_effect=lambda value: "/mnt/test/" + value.name,
            ), patch.object(
                pipeline, "_run_osmium_command", side_effect=fake_run
            ):
                result = pipeline.extract_global_place_opl(
                    planet_path=planet,
                    output_directory=output,
                    expected_planet_bytes=len(planet_raw),
                    expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                )

            self.assertEqual("complete", result.state)
            self.assertTrue((output / "place-nodes.pbf").is_file())
            self.assertTrue((output / "place-nodes.opl").is_file())
            self.assertFalse(any(root.glob("extraction.partial-*")))
            receipt_raw = (output / "extraction-receipt.json").read_bytes()
            receipt = json.loads(receipt_raw.decode("utf-8"))
            self.assertEqual(10, receipt["strictOplAudit"]["nodeCount"])
            self.assertEqual(runtime, receipt["runtime"])
            self.assertEqual(
                hashlib.sha256(planet_raw).hexdigest(),
                receipt["source"]["sha256"],
            )
            self.assertEqual(
                hashlib.sha256(candidate_pbf).hexdigest(),
                receipt["artifacts"]["candidatePbf"]["sha256"],
            )
            self.assertEqual(
                hashlib.sha256(fixture_raw).hexdigest(),
                receipt["artifacts"]["candidateOpl"]["sha256"],
            )
            self.assertEqual(5, len(receipt["commands"]))
            self.assertTrue(all(item["commandSha256"] for item in receipt["commands"]))
            binding = pipeline.source_binding_from_extraction_receipt(output)
            self.assertEqual(hashlib.sha256(receipt_raw).hexdigest(), binding.extraction_receipt_sha256)
            self.assertEqual(len(fixture_raw), binding.opl_bytes)

            mutations = {
                "runtime": lambda value: value["runtime"].__setitem__(
                    "osmiumVersion", "0.0.0"
                ),
                "code": lambda value: value["code"].__setitem__(
                    "sha256", "0" * 64
                ),
                "selection": lambda value: value["selection"].__setitem__(
                    "objectKind", "way"
                ),
                "command": lambda value: value["commands"][1]["arguments"].__setitem__(
                    value["commands"][1]["arguments"].index("n/place"), "w/place"
                ),
                "run identity": lambda value: value.__setitem__(
                    "runIdentitySha256", "0" * 64
                ),
                "strict OPL audit": lambda value: value["strictOplAudit"].__setitem__(
                    "nodeCount", 9
                ),
                "semantic admission audit": lambda value: value[
                    "semanticAdmissionAudit"
                ].__setitem__("controlCharacterExcludedNodes", 1),
                "semantic admission policy": lambda value: value.__setitem__(
                    "semanticAdmissionPolicySha256", "0" * 64
                ),
            }
            for label, mutate in mutations.items():
                with self.subTest(tampered=label):
                    tampered = json.loads(receipt_raw.decode("utf-8"))
                    mutate(tampered)
                    (output / "extraction-receipt.json").write_bytes(
                        pipeline._canonical_json_bytes(tampered)
                    )
                    with self.assertRaisesRegex(
                        pipeline.GlobalPlacePackageError, label
                    ):
                        pipeline.source_binding_from_extraction_receipt(output)
            (output / "extraction-receipt.json").write_bytes(receipt_raw)

    def test_extraction_receipt_audits_admitted_control_exclusions(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline

        opl_raw = b"".join(
            (
                b"n1 v1 dV t2026-06-29T00:00:00Z Tname=Pennard,note=First%0a%Second,place=village x1 y1\n",
                b"n2 v1 dV t2026-06-29T00:00:00Z Tname=Bad%0a%Name,place=town x2 y2\n",
                b"n3 v1 dV t2026-06-29T00:00:00Z Tname=Visible,place=town x3 y3\n",
            )
        )
        planet_raw = b"semantic-audit-fixture-planet"
        candidate_pbf = b"semantic-audit-fixture-pbf"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.pbf"
            output = root / "extraction"
            planet.write_bytes(planet_raw)

            def fake_run(command, stdout_path, stderr_path):
                stdout_path.write_bytes(b"stdout\n")
                stderr_path.write_bytes(b"stderr\n")
                if command.role == "tags-filter":
                    (stdout_path.parent / "place-nodes.pbf.partial").write_bytes(
                        candidate_pbf
                    )
                if command.role == "cat":
                    (stdout_path.parent / "place-nodes.opl.partial").write_bytes(
                        opl_raw
                    )
                return 0

            with patch.object(
                pipeline,
                "_attest_pinned_osmium",
                return_value=pipeline._pinned_runtime_document(),
            ), patch.object(
                pipeline,
                "_windows_to_wsl_path",
                side_effect=lambda value: "/mnt/test/" + value.name,
            ), patch.object(
                pipeline, "_run_osmium_command", side_effect=fake_run
            ):
                result = pipeline.extract_global_place_opl(
                    planet_path=planet,
                    output_directory=output,
                    expected_planet_bytes=len(planet_raw),
                    expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                )

            self.assertEqual(3, result.receipt["strictOplAudit"]["nodeCount"])
            self.assertIn("semanticAdmissionAudit", result.receipt)
            self.assertEqual(
                {
                    "controlCharacterExcludedNodes": 1,
                    "controlCharacterFields": {
                        "capital": 0,
                        "name": 1,
                        "name:en": 0,
                        "place": 0,
                        "population": 0,
                    },
                    "decodedValueAllowlist": [
                        "capital",
                        "name",
                        "name:en",
                        "place",
                        "population",
                    ],
                },
                result.receipt["semanticAdmissionAudit"],
            )
            binding = pipeline.source_binding_from_extraction_receipt(output)
            self.assertEqual(hashlib.sha256(opl_raw).hexdigest(), binding.opl_sha256)

    def test_extraction_rejects_path_replacement_in_the_post_hash_gap(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline

        original = b"verified-planet-source"
        replacement = b"replaced-planet-source"
        self.assertEqual(len(original), len(replacement))
        fixture_raw = FIXTURE.read_bytes()
        real_verify = pipeline.verify_file_identity
        replaced = False
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.pbf"
            output = root / "extraction"
            planet.write_bytes(original)

            def swap_after_verify(path, **kwargs):
                nonlocal replaced
                identity = real_verify(path, **kwargs)
                if path == planet and not replaced:
                    staged = root / "replacement.pbf"
                    staged.write_bytes(replacement)
                    os.replace(staged, planet)
                    replaced = True
                return identity

            def fake_run(command, stdout_path, stderr_path):
                stdout_path.write_bytes(b"stdout\n")
                stderr_path.write_bytes(b"stderr\n")
                if command.role == "tags-filter":
                    (stdout_path.parent / "place-nodes.pbf.partial").write_bytes(b"pbf")
                if command.role == "cat":
                    (stdout_path.parent / "place-nodes.opl.partial").write_bytes(
                        fixture_raw
                    )
                return 0

            with patch.object(
                pipeline, "verify_file_identity", side_effect=swap_after_verify
            ), patch.object(
                pipeline,
                "_attest_pinned_osmium",
                return_value=pipeline._pinned_runtime_document(),
            ), patch.object(
                pipeline,
                "_windows_to_wsl_path",
                side_effect=lambda value: "/mnt/test/" + value.name,
            ), patch.object(
                pipeline, "_run_osmium_command", side_effect=fake_run
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError, "changed after exact hashing"
                ):
                    pipeline.extract_global_place_opl(
                        planet_path=planet,
                        output_directory=output,
                        expected_planet_bytes=len(original),
                        expected_planet_sha256=hashlib.sha256(original).hexdigest(),
                    )

    def test_plan_cli_is_read_only_and_emits_the_exact_production_contract(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline

        output = io.StringIO()
        with redirect_stdout(output):
            exit_code = pipeline.main(["plan"])
        document = json.loads(output.getvalue())

        self.assertEqual(0, exit_code)
        self.assertEqual(str(pipeline.EXPECTED_PLANET_PATH), document["planet"]["path"])
        self.assertEqual(pipeline.EXPECTED_PLANET_BYTES, document["planet"]["bytes"])
        self.assertEqual(pipeline.EXPECTED_PLANET_SHA256, document["planet"]["sha256"])
        self.assertEqual(list(range(4, 12)), document["render"]["zooms"])
        self.assertEqual(134_215_680, document["render"]["fullWorldZ4To11IndexBytes"])
        self.assertEqual(
            pipeline.OSMIUM_BINARY_SHA256,
            document["runtime"]["osmiumBinarySha256"],
        )
        self.assertEqual(5, len(document["extractionCommands"]))

    def test_extraction_resumes_from_the_last_exact_successful_command(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline

        fixture_raw = FIXTURE.read_bytes()
        planet_raw = b"resume-fixture-planet"
        runtime = {
            "binaryPath": pipeline.OSMIUM_BINARY_PATH,
            "binarySha256": pipeline.OSMIUM_BINARY_SHA256,
            "libosmiumVersion": pipeline.PINNED_LIBOSMIUM_VERSION,
            "osmiumVersion": pipeline.PINNED_OSMIUM_VERSION,
        }
        calls = []
        cat_attempts = 0
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            planet = root / "planet.pbf"
            output = root / "extraction"
            planet.write_bytes(planet_raw)

            def fake_run(command, stdout_path, stderr_path):
                nonlocal cat_attempts
                calls.append(command.role)
                stdout_path.write_bytes(b"stdout\n")
                stderr_path.write_bytes(b"stderr\n")
                if command.role == "tags-filter":
                    (stdout_path.parent / "place-nodes.pbf.partial").write_bytes(b"pbf")
                if command.role == "cat":
                    cat_attempts += 1
                    (stdout_path.parent / "place-nodes.opl.partial").write_bytes(
                        b"interrupted" if cat_attempts == 1 else fixture_raw
                    )
                    return 9 if cat_attempts == 1 else 0
                return 0

            patches = (
                patch.object(pipeline, "_attest_pinned_osmium", return_value=runtime),
                patch.object(
                    pipeline,
                    "_windows_to_wsl_path",
                    side_effect=lambda value: "/mnt/test/" + value.name,
                ),
                patch.object(pipeline, "_run_osmium_command", side_effect=fake_run),
            )
            with patches[0], patches[1], patches[2]:
                with self.assertRaisesRegex(pipeline.GlobalPlacePackageError, "exit 9"):
                    pipeline.extract_global_place_opl(
                        planet_path=planet,
                        output_directory=output,
                        expected_planet_bytes=len(planet_raw),
                        expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                    )
                result = pipeline.extract_global_place_opl(
                    planet_path=planet,
                    output_directory=output,
                    expected_planet_bytes=len(planet_raw),
                    expected_planet_sha256=hashlib.sha256(planet_raw).hexdigest(),
                )

            self.assertEqual("complete", result.state)
            self.assertEqual(
                ["fileinfo", "tags-filter", "fileinfo", "cat", "cat", "fileinfo"],
                calls,
            )


class RecoveryBoundaryTests(unittest.TestCase):
    def _modules(self):
        from tools.experiment8 import osm_global_place_package as pipeline

        try:
            from tools.experiment8 import osm_global_place_recovery as recovery
        except ImportError as error:
            self.fail(f"recovery finalizer module is missing: {error}")
        return pipeline, recovery

    def test_recovery_preserves_old_stage_and_separates_extractor_from_finalizer_identity(
        self,
    ) -> None:
        pipeline, recovery = self._modules()
        from tools.experiment8 import osm_global_place_store as store

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, output, contract, checkpoint, opl_raw, candidate_pbf = (
                _make_recovery_stage(root, recovery, pipeline)
            )
            original_stage = {
                path.name: path.read_bytes() for path in stage.iterdir()
            }

            with patch.object(
                pipeline,
                "_run_osmium_command",
                side_effect=AssertionError("recovery must not rerun osmium"),
            ), patch.object(
                pipeline,
                "_attest_pinned_osmium",
                side_effect=AssertionError("recovery must not re-attest as extraction"),
            ), patch.object(
                store,
                "_semantic_outcome_audits_stream",
                wraps=store._semantic_outcome_audits_stream,
            ) as audit_stream:
                result = recovery._recover_completed_extraction_stage(
                    stage_directory=stage,
                    output_directory=output,
                    contract=contract,
                )

            self.assertEqual("complete", result.state)
            self.assertEqual(1, audit_stream.call_count)
            self.assertTrue(stage.is_dir())
            self.assertTrue(output.is_dir())
            self.assertEqual(
                original_stage,
                {path.name: path.read_bytes() for path in stage.iterdir()},
            )
            self.assertEqual(candidate_pbf, (output / "place-nodes.pbf").read_bytes())
            self.assertEqual(opl_raw, (output / "place-nodes.opl").read_bytes())
            receipt = result.receipt
            self.assertEqual(
                "flightalert.experiment8.osm-global-place-extraction-receipt.v1",
                receipt["schema"],
            )
            self.assertEqual(
                recovery.RECOVERABLE_EXTRACTOR_CODE,
                receipt["code"],
            )
            self.assertEqual(
                checkpoint["runIdentitySha256"],
                receipt["runIdentitySha256"],
            )
            recovery_receipt = json.loads(
                (output / "extraction-recovery-receipt.json").read_text("utf-8")
            )
            self.assertEqual(
                {
                    "name": output.name,
                    "path": str(output),
                    "role": "independent semantic-outcome-bound recovered extraction",
                },
                recovery_receipt["recoveryOutput"],
            )
            self.assertEqual(
                receipt["rendererSemanticOutcome"],
                recovery_receipt["rendererSemanticOutcomeAudit"],
            )
            outcome_artifact = receipt["artifacts"]["rendererSemanticOutcomes"]
            self.assertEqual(
                hashlib.sha256(
                    (output / "place-semantic-outcomes.bin").read_bytes()
                ).hexdigest(),
                outcome_artifact["sha256"],
            )
            self.assertNotEqual(
                receipt["code"]["sha256"],
                recovery_receipt["finalizer"]["code"]["auditParser"]["sha256"],
            )
            self.assertEqual(
                contract.checkpoint_sha256,
                recovery_receipt["producer"]["checkpoint"]["sha256"],
            )
            self.assertIn("code", recovery_receipt["producer"])
            self.assertEqual(
                recovery.RECOVERABLE_EXTRACTOR_CODE,
                recovery_receipt["producer"]["code"],
            )
            self.assertEqual(5, recovery_receipt["producer"]["completedCommandCount"])
            self.assertEqual(
                checkpoint["runIdentitySha256"],
                recovery_receipt["producer"]["runIdentitySha256"],
            )
            self.assertTrue(recovery_receipt["producer"]["noCommandsReexecuted"])
            self.assertEqual(3, receipt["strictOplAudit"]["nodeCount"])
            self.assertEqual(
                1,
                receipt["semanticAdmissionAudit"]["controlCharacterExcludedNodes"],
            )
            self.assertTrue((output / "extraction-recovery-receipt.json").is_file())
            binding = recovery._source_binding_from_recovered_extraction(
                output, contract
            )
            self.assertEqual(
                str(output / "place-semantic-outcomes.bin"),
                binding.renderer_semantic_outcome_path,
            )
            self.assertEqual(hashlib.sha256(opl_raw).hexdigest(), binding.opl_sha256)
            self.assertEqual(
                hashlib.sha256(
                    (output / "extraction-recovery-receipt.json").read_bytes()
                ).hexdigest(),
                binding.recovery_receipt_sha256,
            )

    def test_recovery_rejects_same_length_artifact_mutation_before_copy(self) -> None:
        pipeline, recovery = self._modules()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, output, contract, _, _, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            artifact = stage / "place-nodes.pbf.partial"
            with artifact.open("r+b") as handle:
                first = handle.read(1)
                handle.seek(0)
                handle.write(bytes((first[0] ^ 0xFF,)))
            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "SHA-256 differs"
            ):
                recovery._recover_completed_extraction_stage(
                    stage_directory=stage,
                    output_directory=output,
                    contract=contract,
                )
            self.assertFalse(output.exists())

    def test_completed_recovery_partial_retries_atomic_publication_without_source_mutation(
        self,
    ) -> None:
        pipeline, recovery = self._modules()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, output, contract, _, _, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            original_stage = {
                path.name: path.read_bytes() for path in stage.iterdir()
            }
            real_rename = recovery.os.rename

            def fail_final_rename(source, destination):
                if Path(destination) == output:
                    raise OSError("simulated final rename failure")
                return real_rename(source, destination)

            with patch.object(recovery.os, "rename", side_effect=fail_final_rename):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError, "atomic recovery publication"
                ):
                    recovery._recover_completed_extraction_stage(
                        stage_directory=stage,
                        output_directory=output,
                        contract=contract,
                    )

            self.assertTrue(stage.is_dir())
            self.assertFalse(output.exists())
            self.assertEqual(
                original_stage,
                {path.name: path.read_bytes() for path in stage.iterdir()},
            )
            result = recovery._recover_completed_extraction_stage(
                stage_directory=stage,
                output_directory=output,
                contract=contract,
            )
            self.assertEqual("complete", result.state)
            self.assertTrue(output.is_dir())

    def test_recovered_receipts_cannot_rewrite_checkpoint_provenance_consistently(
        self,
    ) -> None:
        pipeline, recovery = self._modules()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, output, contract, _, _, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            recovery._recover_completed_extraction_stage(
                stage_directory=stage,
                output_directory=output,
                contract=contract,
            )
            receipt_path = output / "extraction-receipt.json"
            receipt = json.loads(receipt_path.read_text("utf-8"))
            receipt["source"]["path"] = str(root / "false-planet.pbf")
            receipt_raw = pipeline._canonical_json_bytes(receipt)
            receipt_path.write_bytes(receipt_raw)

            recovery_path = output / "extraction-recovery-receipt.json"
            recovery_receipt = json.loads(recovery_path.read_text("utf-8"))
            rewritten_identity = {
                "bytes": len(receipt_raw),
                "name": "extraction-receipt.json",
                "sha256": hashlib.sha256(receipt_raw).hexdigest(),
            }
            recovery_receipt["freshExtractionReceipt"] = rewritten_identity
            for document in recovery_receipt["boundFiles"]:
                if document["name"] == "extraction-receipt.json":
                    document.update(rewritten_identity)
            recovery_path.write_bytes(
                pipeline._canonical_json_bytes(recovery_receipt)
            )

            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError,
                "authenticated checkpoint",
            ):
                recovery._source_binding_from_recovered_extraction(
                    output, contract
                )

    def test_public_recovery_refuses_non_pinned_paths(self) -> None:
        pipeline, recovery = self._modules()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, output, _, _, _, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "exact pinned"
            ):
                recovery.recover_completed_extraction_stage(
                    stage_directory=stage,
                    output_directory=output,
                )

    def test_cli_exposes_only_the_argument_free_exact_recovery(self) -> None:
        pipeline, recovery = self._modules()
        fake_result = type(
            "FakeRecoveryResult",
            (),
            {
                "output_directory": Path(
                    r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction"
                ),
                "receipt": {"schema": "fixture-recovery"},
                "state": "complete",
            },
        )()
        stdout = io.StringIO()
        with patch.object(
            recovery,
            "recover_completed_extraction_stage",
            return_value=fake_result,
        ) as exact_recovery, redirect_stdout(stdout):
            exit_code = pipeline.main(["recover-retained"])

        self.assertEqual(0, exit_code)
        exact_recovery.assert_called_once_with()
        self.assertEqual("complete", json.loads(stdout.getvalue())["state"])

    def test_historical_finalizer_pins_are_explicit_and_default_validation_stays_current(
        self,
    ) -> None:
        pipeline, recovery = self._modules()
        historical_code = {
            "auditParser": {
                "bytes": 73_351,
                "name": "osm_global_place_package.py",
                "sha256": (
                    "7f7c18ff7d44d9ecfeb1d447a29bb65a104ca9bf93d8959f33a9d53cd8da1d8e"
                ),
            },
            "semanticOutcome": {
                "bytes": 83_314,
                "name": "osm_global_place_store.py",
                "sha256": (
                    "a3bfd11e8dcc46e93d8c523fbd209909f31cc34cb7ea6f3a7df792b493ac37a9"
                ),
            },
            "stageFinalizer": {
                "bytes": 39_967,
                "name": "osm_global_place_recovery.py",
                "sha256": (
                    "ae58c8e03c8a83617b9d7c8ad61e1367e43e87a1a3c87b64979d55211c1a15ba"
                ),
            },
        }
        historical_runtime = {
            "pythonImplementation": "cpython",
            "pythonVersion": [3, 11, 1],
        }
        self.assertNotEqual(
            historical_code["auditParser"],
            recovery._finalizer_code()["auditParser"],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, output, contract, _, opl_raw, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            recovery._recover_completed_extraction_stage(
                stage_directory=stage,
                output_directory=output,
                contract=contract,
            )
            receipt_path = output / "extraction-recovery-receipt.json"
            receipt = json.loads(receipt_path.read_text("utf-8"))
            receipt["finalizer"] = {
                "code": historical_code,
                "runtime": historical_runtime,
            }
            receipt_path.write_bytes(pipeline._canonical_json_bytes(receipt))

            with patch.object(
                recovery, "_EXACT_OUTPUT_PATH", output
            ), patch.object(
                recovery, "_EXACT_RETAINED_RECOVERY_CONTRACT", contract
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError,
                    "exact recovery contract",
                ):
                    recovery.source_binding_from_recovered_extraction(output)

            binding = recovery._source_binding_from_recovered_extraction(
                output,
                contract,
                expected_finalizer_code=historical_code,
                expected_finalizer_runtime=historical_runtime,
            )
            self.assertEqual(hashlib.sha256(opl_raw).hexdigest(), binding.opl_sha256)
            self.assertEqual(
                hashlib.sha256(receipt_path.read_bytes()).hexdigest(),
                binding.recovery_receipt_sha256,
            )


class ReclassificationBoundaryTests(unittest.TestCase):
    def _fixture(self, root: Path):
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_recovery as recovery
        from tools.experiment8 import osm_place_renderer

        old_policy = "0" * 64
        with patch.object(
            osm_place_renderer, "PRESENTATION_POLICY_SHA256", old_policy
        ):
            stage, source, contract, _, _, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            recovery._recover_completed_extraction_stage(
                stage_directory=stage,
                output_directory=source,
                contract=contract,
            )
        output = root / "fixture-reclassified-extraction"

        def load_source(path: Path):
            return recovery._source_binding_from_recovered_extraction(path, contract)

        return pipeline, recovery, source, output, load_source

    def test_cli_exposes_only_argument_free_exact_reclassification(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        fake_result = type(
            "FakeReclassificationResult",
            (),
            {
                "output_directory": Path(
                    r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction-outcome-v4"
                ),
                "receipt": {"schema": "fixture-reclassification"},
                "state": "complete",
            },
        )()
        stdout = io.StringIO()
        with patch.object(
            reclassification,
            "reclassify_retained_extraction",
            return_value=fake_result,
        ) as exact_reclassification, redirect_stdout(stdout):
            exit_code = pipeline.main(["reclassify-retained"])

        self.assertEqual(0, exit_code)
        exact_reclassification.assert_called_once_with()
        self.assertEqual("complete", json.loads(stdout.getvalue())["state"])
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            pipeline._argument_parser().parse_args(
                ["reclassify-retained", "--output", "elsewhere"]
            )

    def test_production_reclassification_keeps_pinned_v2_source_and_publishes_v4(self) -> None:
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        self.assertEqual(
            Path(r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction-outcome-v2"),
            reclassification._EXACT_SOURCE_PATH,
        )
        self.assertEqual(
            Path(r"E:\FlightAlert-exp8-work\osm-global-place-260629-extraction-outcome-v4"),
            reclassification._EXACT_OUTPUT_PATH,
        )

    def test_reclassified_receipt_static_drift_fails_before_expensive_opl_scan(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            reclassification._reclassify_recovered_extraction(
                source_directory=source,
                output_directory=output,
                source_binding_loader=load_source,
            )
            receipt_path = output / "reclassification-receipt.json"
            receipt = json.loads(receipt_path.read_text("utf-8"))
            receipt["code"]["reclassifier"]["sha256"] = "0" * 64
            receipt_path.write_bytes(pipeline._canonical_json_bytes(receipt))

            with patch.object(
                reclassification,
                "_verified_opl_semantics",
                side_effect=AssertionError("expensive OPL scan must not start"),
            ) as semantic_scan:
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError,
                    "static contract differs before semantic validation",
                ):
                    reclassification._source_binding_from_reclassified_extraction(
                        output,
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            semantic_scan.assert_not_called()

    def test_exact_reclassification_loader_uses_immutable_historical_finalizer_pins(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification
        from tools.experiment8 import osm_global_place_recovery as recovery

        self.assertEqual(
            {
                "auditParser": (
                    73_351,
                    "7f7c18ff7d44d9ecfeb1d447a29bb65a104ca9bf93d8959f33a9d53cd8da1d8e",
                ),
                "semanticOutcome": (
                    83_314,
                    "a3bfd11e8dcc46e93d8c523fbd209909f31cc34cb7ea6f3a7df792b493ac37a9",
                ),
                "stageFinalizer": (
                    39_967,
                    "ae58c8e03c8a83617b9d7c8ad61e1367e43e87a1a3c87b64979d55211c1a15ba",
                ),
            },
            {
                role: (identity["bytes"], identity["sha256"])
                for role, identity in reclassification._HISTORICAL_FINALIZER_CODE.items()
            },
        )
        self.assertEqual(
            ("cpython", (3, 11, 1)),
            (
                reclassification._HISTORICAL_FINALIZER_RUNTIME[
                    "pythonImplementation"
                ],
                tuple(
                    reclassification._HISTORICAL_FINALIZER_RUNTIME[
                        "pythonVersion"
                    ]
                ),
            ),
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            stage, source, contract, _, opl_raw, _ = _make_recovery_stage(
                root, recovery, pipeline
            )
            recovery._recover_completed_extraction_stage(
                stage_directory=stage,
                output_directory=source,
                contract=contract,
            )
            receipt_path = source / "extraction-recovery-receipt.json"
            receipt = json.loads(receipt_path.read_text("utf-8"))
            receipt["finalizer"] = {
                "code": {
                    role: dict(identity)
                    for role, identity in reclassification._HISTORICAL_FINALIZER_CODE.items()
                },
                "runtime": {
                    "pythonImplementation": reclassification._HISTORICAL_FINALIZER_RUNTIME[
                        "pythonImplementation"
                    ],
                    "pythonVersion": list(
                        reclassification._HISTORICAL_FINALIZER_RUNTIME[
                            "pythonVersion"
                        ]
                    ),
                },
            }
            receipt_path.write_bytes(pipeline._canonical_json_bytes(receipt))

            with patch.object(
                reclassification, "_EXACT_SOURCE_PATH", source
            ), patch.object(
                recovery, "_EXACT_RETAINED_RECOVERY_CONTRACT", contract
            ):
                binding = reclassification._source_binding_from_historical_recovery(
                    source
                )
            self.assertEqual(hashlib.sha256(opl_raw).hexdigest(), binding.opl_sha256)

    def test_public_reclassification_and_binding_refuse_non_pinned_paths(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "exact pinned"
            ):
                reclassification.source_binding_from_reclassified_extraction(root)

    def test_reclassification_preserves_source_and_recomputes_current_policy_outcomes(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification
        from tools.experiment8 import osm_global_place_store as store

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            source_snapshot = {
                path.name: path.read_bytes() for path in source.iterdir()
            }
            authenticated_loader = Mock(wraps=load_source)
            old_receipt = json.loads(
                (source / "extraction-receipt.json").read_text("utf-8")
            )
            with patch.object(
                pipeline,
                "_run_osmium_command",
                side_effect=AssertionError("reclassification must not run osmium"),
            ), patch.object(
                store,
                "_semantic_outcome_audits_stream",
                wraps=store._semantic_outcome_audits_stream,
            ) as semantic_pass:
                result = reclassification._reclassify_recovered_extraction(
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=authenticated_loader,
                )

            self.assertEqual("complete", result.state)
            self.assertEqual(1, semantic_pass.call_count)
            self.assertEqual(2, authenticated_loader.call_count)
            self.assertEqual(
                source_snapshot,
                {path.name: path.read_bytes() for path in source.iterdir()},
            )
            self.assertEqual(
                (source / "place-nodes.pbf").read_bytes(),
                (output / "place-nodes.pbf").read_bytes(),
            )
            self.assertEqual(
                (source / "place-nodes.opl").read_bytes(),
                (output / "place-nodes.opl").read_bytes(),
            )
            receipt = json.loads(
                (output / "reclassification-receipt.json").read_text("utf-8")
            )
            self.assertNotEqual(
                old_receipt["rendererSemanticOutcome"]["sha256"],
                receipt["rendererSemanticOutcome"]["sha256"],
            )
            with (output / "place-nodes.opl").open("rb") as handle:
                strict, semantic, outcome = store._semantic_outcome_audits_stream(
                    handle,
                    source_generation_sha256=load_source(source).planet_sha256,
                )
            self.assertEqual(strict, receipt["strictOplAudit"])
            self.assertEqual(semantic, receipt["semanticAdmissionAudit"])
            self.assertEqual(outcome, receipt["rendererSemanticOutcome"])
            self.assertEqual(
                reclassification.PRESENTATION_POLICY_SHA256,
                receipt["presentationPolicySha256"],
            )
            self.assertEqual(os.path.abspath(source), receipt["source"]["directory"])
            self.assertEqual(os.path.abspath(output), receipt["output"]["path"])
            self.assertEqual(
                sorted(
                    (
                        "place-nodes.opl",
                        "place-nodes.pbf",
                        "place-semantic-outcomes.bin",
                        "reclassification-receipt.json",
                    )
                ),
                receipt["output"]["inventory"],
            )
            for field, filename in (
                ("extractionReceipt", "extraction-receipt.json"),
                ("recoveryReceipt", "extraction-recovery-receipt.json"),
            ):
                raw = (source / filename).read_bytes()
                self.assertEqual(len(raw), receipt["source"][field]["bytes"])
                self.assertEqual(
                    hashlib.sha256(raw).hexdigest(),
                    receipt["source"][field]["sha256"],
                )
            self.assertNotIn("commands", receipt)
            self.assertNotIn("extractor", receipt["code"])
            self.assertFalse(receipt["provenance"]["osmiumCommandsExecuted"])
            self.assertEqual(
                "none; authenticated source was opened read-only",
                receipt["provenance"]["sourceMutation"],
            )

    def test_same_length_source_mutation_is_rejected_before_copy(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            pbf = source / "place-nodes.pbf"
            with pbf.open("r+b") as handle:
                first = handle.read(1)
                handle.seek(0)
                handle.write(bytes((first[0] ^ 0xFF,)))

            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "SHA-256 differs"
            ):
                reclassification._reclassify_recovered_extraction(
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=load_source,
                )
            self.assertFalse(output.exists())
            self.assertEqual([], list(root.glob(output.name + ".reclassification-partial-*")))

    def test_completed_partial_is_validated_and_published_on_atomic_retry(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification
        from tools.experiment8 import osm_global_place_store as store

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            real_rename = reclassification._durable_rename

            def fail_publication(source_path, destination_path):
                if Path(destination_path) == output:
                    raise OSError("simulated final rename failure")
                return real_rename(source_path, destination_path)

            with patch.object(
                reclassification, "_durable_rename", side_effect=fail_publication
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError,
                    "atomic reclassification publication",
                ):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            partials = list(root.glob(output.name + ".reclassification-partial-*"))
            self.assertEqual(1, len(partials))

            with patch.object(
                store,
                "_semantic_outcome_audits_stream",
                wraps=store._semantic_outcome_audits_stream,
            ) as semantic_pass:
                result = reclassification._reclassify_recovered_extraction(
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=load_source,
                )
            self.assertEqual("complete", result.state)
            self.assertEqual(1, semantic_pass.call_count)
            self.assertTrue(output.is_dir())
            self.assertFalse(partials[0].exists())

    def test_publication_never_replaces_destination_created_after_precheck(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            with patch.object(
                reclassification,
                "_durable_rename",
                side_effect=OSError("leave completed partial"),
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError,
                    "atomic reclassification publication",
                ):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            partial = next(
                root.glob(output.name + ".reclassification-partial-*")
            )
            real_rename = reclassification._durable_rename

            def create_destination_then_rename(source_path: Path, output_path: Path):
                output_path.mkdir()
                return real_rename(source_path, output_path)

            with patch.object(
                reclassification,
                "_durable_rename",
                side_effect=create_destination_then_rename,
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError,
                    "atomic reclassification publication",
                ):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            self.assertTrue(partial.is_dir())
            self.assertTrue(output.is_dir())
            self.assertEqual([], list(output.iterdir()))

    def test_partial_receipt_or_inventory_tamper_is_rejected_without_overwrite(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        for tamper in ("artifact", "receipt", "inventory"):
            with self.subTest(tamper=tamper), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _, _, source, output, load_source = self._fixture(root)
                with patch.object(
                    reclassification,
                    "_durable_rename",
                    side_effect=OSError("leave completed partial"),
                ):
                    with self.assertRaisesRegex(
                        pipeline.GlobalPlacePackageError,
                        "atomic reclassification publication",
                    ):
                        reclassification._reclassify_recovered_extraction(
                            source_directory=source,
                            output_directory=output,
                            source_binding_loader=load_source,
                        )
                partial = next(
                    root.glob(output.name + ".reclassification-partial-*")
                )
                if tamper == "artifact":
                    artifact = partial / "place-nodes.pbf"
                    with artifact.open("r+b") as handle:
                        first = handle.read(1)
                        handle.seek(0)
                        handle.write(bytes((first[0] ^ 0xFF,)))
                elif tamper == "receipt":
                    receipt_path = partial / "reclassification-receipt.json"
                    receipt = json.loads(receipt_path.read_text("utf-8"))
                    receipt["runtime"]["pythonVersion"] = [9, 9, 9]
                    receipt_path.write_bytes(pipeline._canonical_json_bytes(receipt))
                else:
                    (partial / "unexpected.bin").write_bytes(b"foreign")

                with self.assertRaises(pipeline.GlobalPlacePackageError):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
                self.assertFalse(output.exists())
                self.assertTrue(partial.exists())

    def test_existing_output_and_unowned_partial_are_rejected_without_changes(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        for state in ("output", "foreign-partial"):
            with self.subTest(state=state), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                _, _, source, output, load_source = self._fixture(root)
                if state == "output":
                    obstacle = output
                else:
                    obstacle = root / (
                        output.name + ".reclassification-partial-foreign"
                    )
                obstacle.mkdir()
                marker = obstacle / "foreign.bin"
                marker.write_bytes(b"do-not-touch")

                with self.assertRaises(pipeline.GlobalPlacePackageError):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
                self.assertEqual(b"do-not-touch", marker.read_bytes())

    def test_semantic_contract_attests_dependencies_classifier_and_canonical_policy(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_place_reclassification as reclassification
        from tools.experiment8 import osm_global_place_store as store
        from tools.experiment8 import osm_place_renderer
        from tools.experiment8 import reference_presentation_policy as presentation_policy

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            result = reclassification._reclassify_recovered_extraction(
                source_directory=source,
                output_directory=output,
                source_binding_loader=load_source,
            )
            self.assertTrue(set(store._code_identities()).issubset(result.receipt["code"]))
            self.assertEqual(
                osm_place_renderer.classifier_identity_sha256(),
                result.receipt["semanticContract"]["classifierSha256"],
            )
            self.assertEqual(
                presentation_policy.PRESENTATION_POLICY_SHA256,
                result.receipt["semanticContract"]["presentationPolicySha256"],
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            with patch.object(
                presentation_policy,
                "presentation_policy_sha256",
                return_value="f" * 64,
            ):
                with self.assertRaisesRegex(
                    reclassification.GlobalPlacePackageError,
                    "canonical presentation policy",
                ):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            self.assertFalse(output.exists())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            original_code = reclassification._code_identities()
            drifted_code = dict(original_code)
            drifted_code["reclassifier"] = {
                "bytes": 1,
                "name": "osm_global_place_reclassification.py",
                "sha256": "0" * 64,
            }
            with patch.object(
                reclassification,
                "_code_identities",
                side_effect=(original_code, drifted_code),
            ):
                with self.assertRaisesRegex(
                    reclassification.GlobalPlacePackageError,
                    "semantic contract drifted",
                ):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            self.assertFalse(output.exists())

    def test_reclassification_receipt_read_detects_open_handle_drift(self) -> None:
        from types import SimpleNamespace

        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            receipt = Path(temporary) / "reclassification-receipt.json"
            receipt.write_bytes(
                pipeline._canonical_json_bytes(
                    {"schema": reclassification._RECLASSIFICATION_SCHEMA}
                )
            )
            real_fstat = reclassification.os.fstat
            calls = 0

            def drifting_fstat(descriptor: int):
                nonlocal calls
                calls += 1
                status = real_fstat(descriptor)
                if calls == 2:
                    return SimpleNamespace(
                        st_mode=status.st_mode,
                        st_dev=status.st_dev,
                        st_ino=status.st_ino,
                        st_size=status.st_size,
                        st_mtime_ns=status.st_mtime_ns + 1,
                        st_ctime_ns=status.st_ctime_ns,
                    )
                return status

            with patch.object(
                reclassification.os, "fstat", side_effect=drifting_fstat
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError, "drifted while reading"
                ):
                    reclassification._read_canonical_reclassification_receipt(
                        receipt
                    )

    def test_v3_binding_reauthenticates_v2_after_semantic_validation(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            reclassification._reclassify_recovered_extraction(
                source_directory=source,
                output_directory=output,
                source_binding_loader=load_source,
            )
            original = load_source(source)
            drifting_loader = Mock(
                side_effect=(
                    original,
                    replace(original, planet_path="changed-after-first-authentication.pbf"),
                )
            )
            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "source changed"
            ):
                reclassification._source_binding_from_reclassified_extraction(
                    output,
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=drifting_loader,
                )
            self.assertEqual(2, drifting_loader.call_count)

    def test_v3_binding_hashes_and_audits_opl_through_one_stable_open(self) -> None:
        from types import SimpleNamespace

        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            reclassification._reclassify_recovered_extraction(
                source_directory=source,
                output_directory=output,
                source_binding_loader=load_source,
            )
            target = output / "place-nodes.opl"
            real_open = Path.open
            target_opens = 0

            def tracking_open(path: Path, *args, **kwargs):
                nonlocal target_opens
                if path == target:
                    target_opens += 1
                return real_open(path, *args, **kwargs)

            with patch.object(Path, "open", new=tracking_open):
                reclassification._source_binding_from_reclassified_extraction(
                    output,
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=load_source,
                )
            self.assertEqual(1, target_opens)

            real_fstat = reclassification.os.fstat
            calls = 0

            def drifting_fstat(descriptor: int):
                nonlocal calls
                calls += 1
                status = real_fstat(descriptor)
                if calls == 2:
                    return SimpleNamespace(
                        st_mode=status.st_mode,
                        st_dev=status.st_dev,
                        st_ino=status.st_ino,
                        st_size=status.st_size,
                        st_mtime_ns=status.st_mtime_ns + 1,
                        st_ctime_ns=status.st_ctime_ns,
                    )
                return status

            with patch.object(
                reclassification.os, "fstat", side_effect=drifting_fstat
            ):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError,
                    "OPL drifted during semantic validation",
                ):
                    reclassification._verified_opl_semantics(
                        target,
                        load_source(source),
                    )

    def test_concurrent_partial_creator_collision_is_structured_and_non_destructive(
        self,
    ) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            real_mkdir = Path.mkdir
            collision = None

            def collide(path: Path, *args, **kwargs):
                nonlocal collision
                if path.name.startswith(output.name + ".reclassification-partial-"):
                    real_mkdir(path, *args, **kwargs)
                    collision = path
                    (path / "foreign.bin").write_bytes(b"other creator")
                    raise FileExistsError("simulated creator race")
                return real_mkdir(path, *args, **kwargs)

            with patch.object(Path, "mkdir", new=collide):
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError, "appeared concurrently"
                ):
                    reclassification._reclassify_recovered_extraction(
                        source_directory=source,
                        output_directory=output,
                        source_binding_loader=load_source,
                    )
            assert collision is not None
            self.assertEqual(b"other creator", (collision / "foreign.bin").read_bytes())
            self.assertFalse(output.exists())

    def test_symlink_partial_and_source_receipt_tamper_fail_closed(self) -> None:
        if not hasattr(os, "symlink"):
            self.skipTest("symlinks are unavailable")
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            foreign = root / "foreign"
            foreign.mkdir()
            link = root / (output.name + ".reclassification-partial-foreign")
            try:
                link.symlink_to(foreign, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks are unavailable: {error}")
            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "ambiguous or unowned"
            ):
                reclassification._reclassify_recovered_extraction(
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=load_source,
                )
            self.assertTrue(link.is_symlink())

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            receipt = source / "extraction-recovery-receipt.json"
            raw = bytearray(receipt.read_bytes())
            raw[-2] ^= 1
            receipt.write_bytes(bytes(raw))
            with self.assertRaises(pipeline.GlobalPlacePackageError):
                reclassification._reclassify_recovered_extraction(
                    source_directory=source,
                    output_directory=output,
                    source_binding_loader=load_source,
                )
            self.assertFalse(output.exists())

    def test_reclassified_output_supplies_usable_source_binding_and_dispatch(self) -> None:
        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8 import osm_global_place_reclassification as reclassification

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, source, output, load_source = self._fixture(root)
            result = reclassification._reclassify_recovered_extraction(
                source_directory=source,
                output_directory=output,
                source_binding_loader=load_source,
            )
            receipt_raw = (output / "reclassification-receipt.json").read_bytes()
            expected = reclassification._source_binding_from_reclassified_extraction(
                output,
                source_directory=source,
                output_directory=output,
                source_binding_loader=load_source,
            )
            with patch.object(
                reclassification,
                "source_binding_from_reclassified_extraction",
                return_value=expected,
            ) as exact_loader:
                actual = pipeline.source_binding_from_extraction_receipt(output)

            exact_loader.assert_called_once_with(output)
            self.assertEqual(expected, actual)
            self.assertEqual(
                hashlib.sha256(receipt_raw).hexdigest(),
                actual.recovery_receipt_sha256,
            )
            self.assertEqual(
                result.receipt["rendererSemanticOutcome"]["sha256"],
                actual.renderer_semantic_outcome.stream_sha256,
            )
            self.assertEqual(
                str(output / "place-semantic-outcomes.bin"),
                actual.renderer_semantic_outcome_path,
            )

        with tempfile.TemporaryDirectory() as temporary:
            malformed = Path(temporary)
            (malformed / "reclassification-receipt.json").mkdir()
            with patch.object(
                reclassification,
                "source_binding_from_reclassified_extraction",
                side_effect=pipeline.GlobalPlacePackageError(
                    "reclassified extraction inventory differs"
                ),
            ) as exact_loader:
                with self.assertRaisesRegex(
                    pipeline.GlobalPlacePackageError, "inventory differs"
                ):
                    pipeline.source_binding_from_extraction_receipt(malformed)
            exact_loader.assert_called_once_with(malformed)


class GlobalPlaceRendererTests(unittest.TestCase):
    def test_tile_grouping_enforces_raw_byte_bound_before_materializing_payload(self) -> None:
        from tools.experiment8 import osm_global_place_store as store

        header_bytes = len(store.TILE_PAYLOAD_MAGIC) + struct.calcsize("<BIII")
        envelope = struct.pack("<I", 1) + b"x" + b"y" * 15
        row = (4, 1, 2, 0, 0, 0, 0, b"v", b"f", b"s", envelope, 0)
        with patch.object(store, "MAX_TILE_BYTES", header_bytes + 25):
            with self.assertRaisesRegex(
                store.GlobalPlacePackageError, "raw bytes"
            ):
                list(store._group_rows((row, row)))

    def test_oversize_optional_english_is_audited_without_dropping_primary(self) -> None:
        from tools.experiment8 import osm_place_renderer
        from tools.experiment8.osm_global_place_package import iter_strict_place_opl
        from tools.experiment8.osm_global_place_store import render_global_place_package

        raw = (
            "n1 v1 dV t2026-06-29T00:00:00Z "
            "Tname=%391%%3b8%%3ae%%3bd%%3b1%,name:en="
            + "A" * 4097
            + ",place=city x23.727539 y37.98381\n"
        ).encode("ascii")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "oversize-english.opl"
            opl.write_bytes(raw)
            binding = _source_binding_for(
                opl, root / "oversize-semantic-outcomes.bin"
            )

            result = render_global_place_package(
                opl_path=opl,
                output_directory=root / "package",
                work_directory=root / "work",
                package_id="oversize-optional-english-v3",
                source_binding=binding,
                checkpoint_nodes=1,
            )

            records = [
                record
                for _, _, decoded in _iter_present_payloads(root / "package")
                for record in decoded.records
            ]
            self.assertTrue(records)
            self.assertTrue(
                all(record.sourced_text.layout_mode is LayoutMode.SINGLE for record in records)
            )
            self.assertEqual(1, result.receipt["sourceAudit"]["malformedEnglishNames"])
            self.assertEqual(1, result.receipt["sourceAudit"]["renderedNodes"])
            with opl.open("rb") as handle:
                node = next(iter_strict_place_opl(handle)).node
            expected_source_sha = hashlib.sha256(
                osm_place_renderer._OSM_FEATURE_DOMAIN
                + osm_place_renderer._node_canonical_bytes(
                    node, binding.planet_sha256
                )
            ).digest()
            self.assertTrue(
                all(
                    record.renderer_record.variant.placement.source_feature_sha256
                    == expected_source_sha
                    for record in records
                )
            )

    def test_controls_in_admitted_fields_exclude_and_audit_without_renderer_records(
        self,
    ) -> None:
        from tools.experiment8.osm_global_place_package import GlobalPlacePackageError
        from tools.experiment8.osm_global_place_store import render_global_place_package

        rows = (
            b"n1 v1 dV t2026-06-29T00:00:00Z Tname=Bad%0a%Name,place=town x1 y1\n",
            b"n2 v1 dV t2026-06-29T00:00:00Z Tname=BadEnglish,name:en=Bad%0a%English,place=town x2 y2\n",
            b"n3 v1 dV t2026-06-29T00:00:00Z Tname=BadPlace,place=to%0a%wn x3 y3\n",
            b"n4 v1 dV t2026-06-29T00:00:00Z Tname=BadPopulation,place=town,population=1%0a%0 x4 y4\n",
            b"n5 v1 dV t2026-06-29T00:00:00Z Tname=BadCapital,place=town,capital=y%0a%es x5 y5\n",
            b"n6 v1 dV t2026-06-29T00:00:00Z Tname=Bad%0a%Multi,place=town,population=1%0a%0 x6 y6\n",
            b"n7 v1 dV t2026-06-29T00:00:00Z Tname=OnlyVisibleLabel,place=town x7 y7\n",
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            opl = root / "admitted-controls.opl"
            opl.write_bytes(b"".join(rows))
            try:
                result = render_global_place_package(
                    opl_path=opl,
                    output_directory=root / "package",
                    work_directory=root / "work",
                    package_id="admitted-control-audit-v3",
                    source_binding=_source_binding_for(
                        opl, root / "controls-semantic-outcomes.bin"
                    ),
                    checkpoint_nodes=1,
                )
            except GlobalPlacePackageError as error:
                self.fail(f"admitted control was not audited as an exclusion: {error}")

            names = {
                record.sourced_text.primary_text
                for _, _, decoded in _iter_present_payloads(root / "package")
                for record in decoded.records
            }
            self.assertEqual({"OnlyVisibleLabel"}, names)
            audit = result.receipt["sourceAudit"]
            self.assertEqual(7, audit["inputNodes"])
            self.assertEqual(1, audit["renderedNodes"])
            self.assertEqual(6, audit["excludedAdmittedControlNodes"])
            self.assertEqual(2, audit["controlPrimaryNames"])
            self.assertEqual(1, audit["controlEnglishNames"])
            self.assertEqual(1, audit["controlPlaceValues"])
            self.assertEqual(2, audit["controlPopulationValues"])
            self.assertEqual(1, audit["controlCapitalValues"])

    def test_renderer_refuses_source_admission_audit_mismatch_before_publication(self) -> None:
        from dataclasses import replace

        from tools.experiment8 import osm_global_place_package as pipeline
        from tools.experiment8.osm_global_place_store import render_global_place_package

        try:
            audit_type = pipeline.PlaceSemanticAdmissionAudit
        except AttributeError as error:
            self.fail(f"typed semantic admission binding is missing: {error}")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binding = _source_binding(root / "fixture-semantic-outcomes.bin")
            bad_binding = replace(
                binding,
                semantic_admission=audit_type(
                    node_count=binding.semantic_admission.node_count,
                    excluded_node_count=1,
                    control_capital_values=0,
                    control_english_names=0,
                    control_place_values=0,
                    control_population_values=0,
                    control_primary_names=1,
                ),
            )
            with self.assertRaisesRegex(
                pipeline.GlobalPlacePackageError, "semantic admission audit"
            ):
                render_global_place_package(
                    opl_path=FIXTURE,
                    output_directory=root / "package",
                    work_directory=root / "work",
                    package_id="semantic-admission-mismatch-v3",
                    source_binding=bad_binding,
                    checkpoint_nodes=2,
                )
            self.assertFalse((root / "package").exists())

    def test_unowned_deterministic_partial_directory_is_never_truncated(self) -> None:
        from tools.experiment8.osm_global_place_package import GlobalPlacePackageError

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = _build(root, "owned", pause_after_input_nodes=4)
            partial = root / (
                "owned.partial-" + paused.receipt["runIdentitySha256"][:16]
            )
            partial.mkdir()
            (partial / "records.fadictpack").write_bytes(b"foreign-records")
            (partial / "tile-index.bin").write_bytes(b"foreign-index")

            with self.assertRaisesRegex(GlobalPlacePackageError, "ownership"):
                _build(root, "owned")
            self.assertEqual(
                b"foreign-records", (partial / "records.fadictpack").read_bytes()
            )
            self.assertEqual(b"foreign-index", (partial / "tile-index.bin").read_bytes())

    def test_runtime_file_resume_authenticates_checkpointed_prefixes(self) -> None:
        from tools.experiment8 import osm_global_place_store as store

        for filename in ("records.fadictpack", "tile-index.bin"):
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                with patch.object(store.os, "replace", side_effect=OSError("stop")):
                    with self.assertRaisesRegex(
                        store.GlobalPlacePackageError, "atomic global place publication"
                    ):
                        _build(root, "package")
                partial = next(root.glob("package.partial-*"))
                target = partial / filename
                with target.open("r+b") as handle:
                    first = handle.read(1)
                    self.assertTrue(first)
                    handle.seek(0)
                    handle.write(bytes((first[0] ^ 0xFF,)))
                    handle.flush()
                    os.fsync(handle.fileno())

                with self.assertRaisesRegex(
                    store.GlobalPlacePackageError, "prefix"
                ):
                    _build(root, "package")

    def test_ingest_resume_rejects_forged_eof_and_completed_checkpoint_state(self) -> None:
        import sqlite3
        from contextlib import closing

        from tools.experiment8 import osm_global_place_store as store

        for ingest_complete in (False, True):
            with self.subTest(ingestComplete=ingest_complete), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                paused = _build(root, "forged", pause_after_input_nodes=4)
                self.assertEqual("paused", paused.state)
                database = root / "forged-work" / "place-state.sqlite"
                with closing(sqlite3.connect(database)) as connection:
                    checkpoint = json.loads(
                        bytes(
                            connection.execute(
                                "SELECT value FROM meta WHERE key = 'checkpoint'"
                            ).fetchone()[0]
                        ).decode("utf-8", "strict")
                    )
                    audit = json.loads(
                        bytes(
                            connection.execute(
                                "SELECT value FROM meta WHERE key = 'audit'"
                            ).fetchone()[0]
                        ).decode("utf-8", "strict")
                    )
                    checkpoint.update(
                        {
                            "ingestComplete": ingest_complete,
                            "inputNodes": 10,
                            "lineNumber": 10,
                            "nextByteOffset": FIXTURE.stat().st_size,
                            "previousNodeId": 10,
                        }
                    )
                    audit["inputNodes"] = 10
                    connection.execute(
                        "UPDATE meta SET value = ? WHERE key = 'checkpoint'",
                        (store._canonical_json_bytes(checkpoint),),
                    )
                    connection.execute(
                        "UPDATE meta SET value = ? WHERE key = 'audit'",
                        (store._canonical_json_bytes(audit),),
                    )
                    connection.commit()

                with self.assertRaisesRegex(
                    store.GlobalPlacePackageError,
                    "ingest checkpoint",
                ):
                    _build(root, "forged")
                self.assertFalse((root / "forged").exists())

    def test_resealed_eof_audit_cannot_authorize_partial_semantic_tables(self) -> None:
        import sqlite3
        from contextlib import closing

        from tools.experiment8 import osm_global_place_store as store

        def read_meta(connection, key):
            return json.loads(
                bytes(
                    connection.execute(
                        "SELECT value FROM meta WHERE key = ?", (key,)
                    ).fetchone()[0]
                ).decode("utf-8", "strict")
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = _build(root, "forged", pause_after_input_nodes=4)
            clean = _build(root, "clean")
            self.assertEqual("paused", paused.state)
            self.assertEqual("complete", clean.state)

            with closing(
                sqlite3.connect(root / "clean-work" / "place-state.sqlite")
            ) as clean_connection:
                eof_checkpoint = read_meta(clean_connection, "checkpoint")
                eof_audit = read_meta(clean_connection, "audit")

            database = root / "forged-work" / "place-state.sqlite"
            with closing(sqlite3.connect(database)) as connection:
                run_identity = read_meta(connection, "runIdentity")
                paused_row_counts = {
                    table: int(
                        connection.execute(
                            f"SELECT COUNT(*) FROM {table}"
                        ).fetchone()[0]
                    )
                    for table in store._SEMANTIC_ROW_TABLES
                }
                eof_checkpoint["semanticRowCounts"] = paused_row_counts
                store._seal_ingest_checkpoint(
                    eof_checkpoint,
                    run_identity=run_identity,
                    audit=eof_audit,
                )
                connection.execute(
                    "UPDATE meta SET value = ? WHERE key = 'checkpoint'",
                    (store._canonical_json_bytes(eof_checkpoint),),
                )
                connection.execute(
                    "UPDATE meta SET value = ? WHERE key = 'audit'",
                    (store._canonical_json_bytes(eof_audit),),
                )
                connection.commit()

            with self.assertRaisesRegex(
                store.GlobalPlacePackageError,
                "semantic table counts",
            ):
                _build(root, "forged")
            self.assertFalse((root / "forged").exists())

    def test_resealed_balanced_skips_cannot_replace_unparsed_source_outcomes(self) -> None:
        import shutil
        import sqlite3
        from contextlib import closing

        from tools.experiment8 import osm_global_place_store as store

        def read_meta(connection, key):
            return json.loads(
                bytes(
                    connection.execute(
                        "SELECT value FROM meta WHERE key = ?", (key,)
                    ).fetchone()[0]
                ).decode("utf-8", "strict")
            )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = _build(root, "forged", pause_after_input_nodes=4)
            clean = _build(root, "clean")
            self.assertEqual("paused", paused.state)
            self.assertEqual("complete", clean.state)
            shutil.copyfile(
                root / "clean-semantic-outcomes.bin",
                root / "forged-semantic-outcomes.bin",
            )
            self.assertEqual(
                10 * hashlib.sha256().digest_size,
                (root / "forged-semantic-outcomes.bin").stat().st_size,
            )
            database = root / "forged-work" / "place-state.sqlite"
            with closing(sqlite3.connect(database)) as connection:
                checkpoint = read_meta(connection, "checkpoint")
                audit = read_meta(connection, "audit")
                run_identity = read_meta(connection, "runIdentity")
                checkpoint.update(
                    {
                        "ingestComplete": True,
                        "inputNodes": 10,
                        "lineNumber": 10,
                        "nextByteOffset": FIXTURE.stat().st_size,
                        "previousNodeId": 10,
                        "sourcePrefixSha256": hashlib.sha256(
                            FIXTURE.read_bytes()
                        ).hexdigest(),
                    }
                )
                audit["inputNodes"] = 10
                audit["missingPlaceValues"] += 6
                store._seal_ingest_checkpoint(
                    checkpoint,
                    run_identity=run_identity,
                    audit=audit,
                )
                connection.execute(
                    "UPDATE meta SET value = ? WHERE key = 'checkpoint'",
                    (store._canonical_json_bytes(checkpoint),),
                )
                connection.execute(
                    "UPDATE meta SET value = ? WHERE key = 'audit'",
                    (store._canonical_json_bytes(audit),),
                )
                connection.commit()

            with self.assertRaisesRegex(
                store.GlobalPlacePackageError,
                "semantic outcome",
            ):
                _build(root, "forged")
            self.assertFalse((root / "forged").exists())

    def test_fixture_build_is_source_honest_zoom_eligible_and_android_decodable(self) -> None:
        real_read_bytes = Path.read_bytes

        def guarded_read_bytes(path: Path) -> bytes:
            if path.resolve() == FIXTURE.resolve():
                raise AssertionError("global OPL must never be read as one bytes object")
            return real_read_bytes(path)

        with tempfile.TemporaryDirectory() as temporary, patch.object(
            Path, "read_bytes", guarded_read_bytes
        ):
            root = Path(temporary)
            result = _build(root, "package")

            self.assertEqual("complete", result.state)
            self.assertTrue((root / "package").is_dir())
            self.assertFalse((root / "package.partial").exists())
            manifest = json.loads((root / "package" / "manifest.json").read_text("utf-8"))
            self.assertEqual(3, manifest["schemaVersion"])
            self.assertFalse(manifest["coverage"]["completeDeclaredScope"])
            self.assertFalse(manifest["coverage"]["completeWholeEarthDictionary"])

            by_name: dict[str, list[tuple[TileKey, object]]] = {}
            all_renderer_records = []
            for tile, payload, decoded in _iter_present_payloads(root / "package"):
                self.assertLessEqual(len(payload), MAX_TILE_BYTES)
                self.assertLessEqual(len(decoded.records), MAX_RECORDS_PER_TILE)
                for record in decoded.records:
                    sourced = record.sourced_text
                    assert sourced is not None
                    by_name.setdefault(sourced.primary_text, []).append((tile, sourced))
                    all_renderer_records.append(record.renderer_record)

            self.assertNotIn(" ", by_name)
            self.assertNotIn("Unsupported Island", by_name)
            self.assertEqual(
                {"Αθήνα", "القاهرة", "北京市", "Madrid", "España", "Χωριό", "Bad Evidence City", "Αττική"},
                set(by_name),
            )
            for name, english in (("Αθήνα", "Athens"), ("القاهرة", "Cairo"), ("北京市", "Beijing")):
                sourced = by_name[name][0][1]
                self.assertEqual(LayoutMode.PRIMARY_WITH_ENGLISH, sourced.layout_mode)
                self.assertEqual(english, sourced.english_text)
            madrid = by_name["Madrid"][0][1]
            self.assertEqual(LayoutMode.SINGLE, madrid.layout_mode)
            self.assertIsNone(madrid.english_text)
            self.assertEqual(set(range(9, 12)), {tile.z for tile, _ in by_name["Χωριό"]})
            self.assertEqual(set(range(4, 12)), {tile.z for tile, _ in by_name["Αθήνα"]})
            self.assertEqual(
                renderer_contract_hash(all_renderer_records),
                manifest["rendererSemanticStreamSha256"],
            )

            receipt = result.receipt
            audit = receipt["sourceAudit"]
            self.assertEqual(10, audit["inputNodes"])
            self.assertEqual(8, audit["renderedNodes"])
            self.assertEqual(1, audit["blankPrimaryNames"])
            self.assertEqual(1, audit["unsupportedPlaceValues"])
            self.assertEqual(1, audit["invalidPopulationEvidence"])
            self.assertEqual(1, audit["invalidCapitalEvidence"])
            self.assertEqual(5, audit["acceptedPopulationEvidence"])
            self.assertEqual(2, audit["acceptedCapitalEvidence"])
            self.assertEqual(6, audit["presentPopulationEvidence"])
            self.assertEqual(3, audit["presentCapitalEvidence"])
            self.assertEqual(8, sum(item["distinctFeatureIds"] for item in receipt["subtypeCounts"]))
            self.assertEqual(23, len(receipt["subtypeCounts"]))

    def test_resume_and_clean_repeat_publish_identical_bytes_and_catalog_input(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paused = _build(root, "resumed", pause_after_input_nodes=4)
            self.assertEqual("paused", paused.state)
            self.assertFalse((root / "resumed").exists())

            resumed = _build(root, "resumed")
            clean = _build(root, "clean")
            self.assertEqual("complete", resumed.state)
            self.assertEqual("complete", clean.state)
            for filename in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "build-receipt.json",
                "class-catalog-input.json",
            ):
                self.assertEqual(
                    (root / "resumed" / filename).read_bytes(),
                    (root / "clean" / filename).read_bytes(),
                    filename,
                )

            catalog = json.loads(
                (root / "resumed" / "class-catalog-input.json").read_text("utf-8")
            )
            manifest = json.loads((root / "resumed" / "manifest.json").read_text("utf-8"))
            self.assertEqual(23, len(catalog["subtypeCounts"]))
            self.assertEqual(
                manifest["rendererSemanticStreamSha256"],
                catalog["rendererSemanticStreamSha256"],
            )
            nonzero = {
                item["semanticSubtypeName"]: item["distinctFeatureIds"]
                for item in catalog["subtypeCounts"]
                if item["distinctFeatureIds"]
            }
            self.assertEqual(
                {
                    SemanticSubtype.COUNTRY_TERRITORY.name: 1,
                    SemanticSubtype.FIRST_ORDER_REGION.name: 1,
                    SemanticSubtype.CAPITAL_MAJOR_CITY.name: 4,
                    SemanticSubtype.CITY_TOWN.name: 1,
                    SemanticSubtype.LOCAL_PLACE.name: 1,
                },
                nonzero,
            )
            peaks = resumed.receipt["peakResources"]
            self.assertLessEqual(peaks["recordsPerTile"], MAX_RECORDS_PER_TILE)
            self.assertLessEqual(peaks["rawTileBytes"], MAX_TILE_BYTES)
            self.assertGreater(peaks["observedPersistentSqliteBytesAtCheckpoints"], 0)
            self.assertEqual(64 * 1024 * 1024, peaks["sqliteCacheTargetBytes"])
            self.assertNotIn("sqliteCacheByteCeiling", peaks)
            self.assertFalse(
                peaks["measurementScope"]["transientSqliteTempFilesIncluded"]
            )

    def test_supplement_is_directly_accepted_by_the_v3_class_catalog_finalizer(self) -> None:
        from tools.experiment8.v3_class_catalog_finalizer import (
            finalize_v3_class_catalog,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _build(root, "package")

            finalized = finalize_v3_class_catalog(root / "package")

            self.assertEqual(754, (root / "package" / "class-catalog.bin").stat().st_size)
            manifest = json.loads((root / "package" / "manifest.json").read_text("utf-8"))
            self.assertEqual(finalized.catalog_sha256, manifest["classCatalog"]["catalogSha256"])
            self.assertEqual(
                manifest["rendererSemanticStreamSha256"],
                manifest["classCatalog"]["rendererContractSha256"],
            )


if __name__ == "__main__":
    unittest.main()
