from __future__ import annotations

import hashlib
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import patch


FIXTURE = Path(__file__).with_name("fixtures") / "boundary-closure.opl"


def _compact(document: object) -> bytes:
    return (json.dumps(document, ensure_ascii=False, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


def _write_extraction_receipt(path: Path, closure: Path) -> None:
    closure_raw = closure.read_bytes()
    source_raw = b"synthetic pbf identity"
    tool_raw = b"synthetic native extractor"
    semantic = {
        "policy": "flightalert.osm-boundary-closure.v1",
        "tool": {
            "name": "flightalert-native-osm-boundary-extractor",
            "version": "1",
            "sourceSha256": hashlib.sha256(tool_raw).hexdigest(),
        },
        "input": {
            "path": str(closure.resolve()),
            "bytes": len(source_raw),
            "sha256": hashlib.sha256(source_raw).hexdigest(),
        },
        "output": {
            "bytes": len(closure_raw),
            "sha256": hashlib.sha256(closure_raw).hexdigest(),
        },
        "counts": {
            "inputObjects": {"nodes": 12, "ways": 12, "relations": 4},
            "outputObjects": {"nodes": 12, "ways": 12, "relations": 4},
        },
    }
    semantic_raw = json.dumps(
        semantic, ensure_ascii=False, separators=(",", ":")
    ).encode("utf-8")
    receipt = {
        "schema": "flightalert.osm-boundary-extraction-receipt.v1",
        "state": "complete",
        "semantic": semantic,
        "semanticIdentitySha256": hashlib.sha256(semantic_raw).hexdigest(),
        "execution": {
            "executable": {
                "path": str(path.resolve()),
                "bytes": len(tool_raw),
                "sha256": hashlib.sha256(tool_raw).hexdigest(),
            },
            "workerCount": 1,
            "resourceBounds": {
                "maxWorkers": 10,
                "maxBlobHeaderBytes": 65_536,
                "maxBlobBytes": 67_108_864,
                "maxUncompressedBlobBytes": 33_554_432,
                "sortChunkBytes": 33_554_432,
                "bloomBytes": 33_554_432,
                "queueDepth": 10,
                "maxWorkBytes": 1_099_511_627_776,
                "maxOutputBytes": 68_719_476_736,
                "peakWorkBytes": 4_096,
            },
        },
        "failureClosedPolicy": {
            "completionMarker": "receipt-last",
            "existingTargets": "never-overwritten",
            "malformedOrIncompleteInput": "reject",
            "temporaryOutput": "removed-on-failure",
        },
    }
    path.write_bytes(_compact(receipt))


class BoundaryProductionIngestTests(unittest.TestCase):
    def test_ingest_uses_disk_tables_and_yields_bounded_ordered_render_batches(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionLimits,
            ingest_boundary_closure,
            iter_boundary_render_batches,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            closure = root / "boundary.opl"
            closure.write_bytes(FIXTURE.read_bytes())
            extraction = Path(str(closure) + ".receipt.json")
            database = root / "boundary.sqlite"
            ingest_receipt = root / "boundary-ingest-receipt.json"
            _write_extraction_receipt(extraction, closure)

            result = ingest_boundary_closure(
                closure_opl=closure,
                extraction_receipt=extraction,
                database_path=database,
                ingest_receipt_path=ingest_receipt,
            )

            self.assertEqual(result.selected_features, 10)
            self.assertEqual(result.selected_ways, 9)
            self.assertEqual(result.required_nodes, 12)
            self.assertEqual(result.relation_memberships, 5)
            with closing(sqlite3.connect(database)) as connection:
                self.assertEqual(
                    connection.execute("SELECT COUNT(*) FROM selected_features").fetchone()[0],
                    10,
                )
                self.assertEqual(
                    connection.execute("SELECT COUNT(*) FROM source_nodes").fetchone()[0],
                    12,
                )
            limits = BoundaryProductionLimits(
                workers=2,
                max_in_flight_batches=4,
                max_features_per_batch=2,
                max_points_per_batch=8,
                max_input_bytes_per_batch=64 * 1024,
                max_spool_bytes_per_batch=2 * 1024 * 1024,
                max_total_spool_bytes=8 * 1024 * 1024,
            )
            batches = tuple(iter_boundary_render_batches(database, limits=limits))
            flattened = [item for batch in batches for item in batch]

            self.assertEqual(len(flattened), 10)
            self.assertEqual(
                [(item.way.object_id, item.subtype.value) for item in flattened],
                sorted((item.way.object_id, item.subtype.value) for item in flattened),
            )
            self.assertTrue(all(len(batch) <= 2 for batch in batches))
            self.assertTrue(all(sum(len(item.way.node_refs) for item in batch) <= 8 for batch in batches))
            self.assertTrue(ingest_receipt.is_file())
            receipt = json.loads(ingest_receipt.read_text("utf-8"))
            self.assertEqual(
                receipt["schema"],
                "flightalert.experiment8.osm-boundary-ingest-receipt.v1",
            )
            self.assertEqual(
                receipt["sourceClosure"]["sha256"],
                hashlib.sha256(closure.read_bytes()).hexdigest(),
            )

    def test_extraction_output_tamper_is_rejected_without_partial_outputs(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            ingest_boundary_closure,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            closure = root / "boundary.opl"
            closure.write_bytes(FIXTURE.read_bytes())
            extraction = Path(str(closure) + ".receipt.json")
            database = root / "boundary.sqlite"
            ingest_receipt = root / "boundary-ingest-receipt.json"
            _write_extraction_receipt(extraction, closure)
            document = json.loads(extraction.read_text("utf-8"))
            document["semantic"]["output"]["sha256"] = "f" * 64
            semantic_raw = json.dumps(
                document["semantic"], ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
            document["semanticIdentitySha256"] = hashlib.sha256(
                semantic_raw
            ).hexdigest()
            extraction.write_bytes(_compact(document))

            with self.assertRaisesRegex(BoundaryProductionError, "extraction.*output"):
                ingest_boundary_closure(
                    closure_opl=closure,
                    extraction_receipt=extraction,
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                )

            self.assertFalse(database.exists())
            self.assertFalse(ingest_receipt.exists())

    def test_ingest_database_quota_failure_never_publishes_outputs(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryIngestLimits,
            BoundaryProductionError,
            ingest_boundary_closure,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            closure = root / "boundary.opl"
            closure.write_bytes(FIXTURE.read_bytes())
            extraction = Path(str(closure) + ".receipt.json")
            database = root / "boundary.sqlite"
            ingest_receipt = root / "boundary-ingest-receipt.json"
            _write_extraction_receipt(extraction, closure)

            with self.assertRaisesRegex(BoundaryProductionError, "ingest database.*quota"):
                ingest_boundary_closure(
                    closure_opl=closure,
                    extraction_receipt=extraction,
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                    limits=BoundaryIngestLimits(
                        max_database_bytes=4_096,
                        destination_reserve_bytes=1,
                    ),
                )

            self.assertFalse(database.exists())
            self.assertFalse(ingest_receipt.exists())


class BoundaryProductionBuildTests(unittest.TestCase):
    def _ingest(self, root: Path) -> tuple[Path, Path]:
        from tools.experiment8.osm_boundary_production import ingest_boundary_closure

        closure = root / "boundary.opl"
        closure.write_bytes(FIXTURE.read_bytes())
        extraction = Path(str(closure) + ".receipt.json")
        database = root / "boundary.sqlite"
        ingest_receipt = root / "boundary-ingest-receipt.json"
        _write_extraction_receipt(extraction, closure)
        ingest_boundary_closure(
            closure_opl=closure,
            extraction_receipt=extraction,
            database_path=database,
            ingest_receipt_path=ingest_receipt,
        )
        return database, ingest_receipt

    def test_publishable_view_applies_only_the_measured_hierarchy_gates(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            _create_publishable_render_view,
        )
        from tools.experiment8.semantic_model import FeatureKind, LayerGroup

        with closing(sqlite3.connect(":memory:")) as connection:
            connection.execute(
                """
                CREATE TABLE tile_records (
                    tile_z INTEGER, layer_group INTEGER, draw_order INTEGER,
                    priority INTEGER, feature_kind INTEGER, marker TEXT
                )
                """
            )
            connection.executemany(
                "INSERT INTO tile_records VALUES (?, ?, ?, ?, ?, ?)",
                (
                    (6, LayerGroup.WATER.value, 10, 0, FeatureKind.LINE.value, "drop coast"),
                    (7, LayerGroup.WATER.value, 10, 0, FeatureKind.LINE.value, "keep coast"),
                    (7, LayerGroup.REGIONS.value, 23, 6, FeatureKind.LINE.value, "drop other"),
                    (8, LayerGroup.REGIONS.value, 23, 6, FeatureKind.LINE.value, "keep other"),
                    (3, LayerGroup.REGIONS.value, 20, 1, FeatureKind.LINE.value, "keep international"),
                ),
            )
            _create_publishable_render_view(connection)

            self.assertEqual(
                tuple(
                    row[0]
                    for row in connection.execute(
                        "SELECT marker FROM publishable_tile_records ORDER BY marker"
                    )
                ),
                ("keep coast", "keep international", "keep other"),
            )

    def test_parallel_worker_counts_produce_identical_runtime_bytes_and_semantics(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            serial = root / "serial"
            parallel = root / "parallel"
            serial_result = build_boundary_package(
                database_path=database,
                ingest_receipt_path=ingest_receipt,
                output_directory=serial,
                package_id="boundary-fixture-v3",
                zooms=(4, 5, 6),
                limits=BoundaryProductionLimits(
                    workers=1,
                    max_in_flight_batches=2,
                    max_features_per_batch=2,
                    max_points_per_batch=8,
                    max_input_bytes_per_batch=64 * 1024,
                    max_spool_bytes_per_batch=2 * 1024 * 1024,
                    max_total_spool_bytes=4 * 1024 * 1024,
                ),
            )
            parallel_result = build_boundary_package(
                database_path=database,
                ingest_receipt_path=ingest_receipt,
                output_directory=parallel,
                package_id="boundary-fixture-v3",
                zooms=(4, 5, 6),
                limits=BoundaryProductionLimits(
                    workers=10,
                    max_in_flight_batches=20,
                    max_features_per_batch=2,
                    max_points_per_batch=8,
                    max_input_bytes_per_batch=64 * 1024,
                    max_spool_bytes_per_batch=2 * 1024 * 1024,
                    max_total_spool_bytes=40 * 1024 * 1024,
                ),
            )

            for name in ("manifest.json", "records.fadictpack", "tile-index.bin"):
                self.assertEqual((serial / name).read_bytes(), (parallel / name).read_bytes())
            self.assertEqual(
                serial_result.renderer_semantic_sha256,
                parallel_result.renderer_semantic_sha256,
            )
            serial_receipt = json.loads((serial / "build-receipt.json").read_text("utf-8"))
            parallel_receipt = json.loads(
                (parallel / "build-receipt.json").read_text("utf-8")
            )
            self.assertEqual(
                serial_receipt["schema"],
                "flightalert.experiment8.osm-boundary-build.v1",
            )
            self.assertEqual(serial_receipt["execution"]["workers"], 1)
            self.assertEqual(parallel_receipt["execution"]["workers"], 10)
            self.assertEqual(
                serial_receipt["rendererSemanticStreamSha256"],
                parallel_receipt["rendererSemanticStreamSha256"],
            )
            manifest = json.loads((serial / "manifest.json").read_text("utf-8"))
            self.assertEqual(
                manifest["globalBoundarySupplement"]["ingestSemanticSha256"],
                serial_receipt["ingest"]["semanticSha256"],
            )
            self.assertEqual(
                {path.name for path in serial.iterdir()},
                {
                    "build-receipt.json",
                    "manifest.json",
                    "records.fadictpack",
                    "tile-index.bin",
                },
            )

    def test_resumed_render_store_produces_identical_runtime_package(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionLimits,
            _RenderBatchJob,
            _create_render_store,
            _ingest_spool,
            _render_batch_job,
            build_boundary_package,
            iter_boundary_render_batches,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            baseline = root / "baseline"
            resumed = root / "resumed"
            stage = root / "resume-stage"
            spool_directory = stage / "spool"
            stage.mkdir()
            spool_directory.mkdir()
            limits = BoundaryProductionLimits(
                workers=1,
                max_in_flight_batches=2,
                max_features_per_batch=2,
                max_points_per_batch=8,
                max_input_bytes_per_batch=64 * 1024,
                max_spool_bytes_per_batch=2 * 1024 * 1024,
                max_total_spool_bytes=4 * 1024 * 1024,
            )
            baseline_result = build_boundary_package(
                database_path=database,
                ingest_receipt_path=ingest_receipt,
                output_directory=baseline,
                package_id="boundary-resume-fixture-v3",
                zooms=(4, 5, 6),
                limits=limits,
            )
            source_sha256 = json.loads(ingest_receipt.read_text("utf-8"))[
                "sourceClosure"
            ]["sha256"]
            batches = tuple(iter_boundary_render_batches(database, limits=limits))
            self.assertGreaterEqual(len(batches), 4)
            connection = _create_render_store(
                stage / "render.sqlite",
                max_bytes=limits.max_render_database_bytes,
            )
            try:
                descriptors = []
                for batch in batches[:3]:
                    spool = spool_directory / (
                        f"{batch[0].ordinal:012d}-{batch[-1].ordinal + 1:012d}.spool"
                    )
                    descriptors.append(
                        _render_batch_job(
                            _RenderBatchJob(
                                batch=batch,
                                source_generation_sha256=source_sha256,
                                zooms=(4, 5, 6),
                                spool_path=str(spool),
                                spool_byte_quota=limits.max_spool_bytes_per_batch,
                            )
                        )
                    )
                _ingest_spool(connection, descriptors[0])
                committed_spool = Path(descriptors[1].path).read_bytes()
                _ingest_spool(connection, descriptors[1])
                Path(descriptors[1].path).write_bytes(committed_spool)
            finally:
                connection.close()

            resumed_result = build_boundary_package(
                database_path=database,
                ingest_receipt_path=ingest_receipt,
                output_directory=resumed,
                package_id="boundary-resume-fixture-v3",
                zooms=(4, 5, 6),
                limits=limits,
                resume_directory=stage,
                resume_ordinal=descriptors[1].start_ordinal,
            )

            for name in ("manifest.json", "records.fadictpack", "tile-index.bin"):
                self.assertEqual((baseline / name).read_bytes(), (resumed / name).read_bytes())
            self.assertEqual(
                (
                    baseline_result.renderer_semantic_sha256,
                    baseline_result.records_sha256,
                    baseline_result.index_sha256,
                ),
                (
                    resumed_result.renderer_semantic_sha256,
                    resumed_result.records_sha256,
                    resumed_result.index_sha256,
                ),
            )
            receipt = json.loads((resumed / "build-receipt.json").read_text("utf-8"))
            self.assertEqual(receipt["peakResources"]["recoveredSpoolBatches"], 2)
            self.assertEqual(
                receipt["peakResources"]["renderStartOrdinal"],
                descriptors[1].end_ordinal,
            )

    def test_parallel_spool_quota_failure_never_publishes_output(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            output = root / "failed"
            with self.assertRaisesRegex(BoundaryProductionError, "spool"):
                build_boundary_package(
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                    output_directory=output,
                    package_id="boundary-fixture-v3",
                    zooms=(4,),
                    limits=BoundaryProductionLimits(
                        workers=1,
                        max_in_flight_batches=1,
                        max_features_per_batch=2,
                        max_points_per_batch=8,
                        max_input_bytes_per_batch=64 * 1024,
                        max_spool_bytes_per_batch=128,
                        max_total_spool_bytes=128,
                    ),
                )

            self.assertFalse(output.exists())

    def test_tampered_ingest_provenance_is_rejected_before_rendering(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            document = json.loads(ingest_receipt.read_text("utf-8"))
            document["extractionReceipt"]["sha256"] = "f" * 64
            ingest_receipt.write_bytes(
                (
                    json.dumps(
                        document,
                        ensure_ascii=False,
                        separators=(",", ":"),
                        sort_keys=True,
                    )
                    + "\n"
                ).encode("utf-8")
            )
            output = root / "tampered"

            with self.assertRaisesRegex(BoundaryProductionError, "extraction receipt"):
                build_boundary_package(
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                    output_directory=output,
                    package_id="boundary-fixture-v3",
                    zooms=(4,),
                    limits=BoundaryProductionLimits(
                        workers=1,
                        max_in_flight_batches=1,
                        max_features_per_batch=2,
                        max_points_per_batch=8,
                        max_input_bytes_per_batch=64 * 1024,
                        max_spool_bytes_per_batch=2 * 1024 * 1024,
                        max_total_spool_bytes=2 * 1024 * 1024,
                    ),
                )

            self.assertFalse(output.exists())

    def test_tile_record_ceiling_is_enforced_before_package_publication(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            output = root / "overfull"
            with patch(
                "tools.experiment8.osm_boundary_production.MAX_RECORDS_PER_TILE",
                1,
            ):
                with self.assertRaisesRegex(BoundaryProductionError, "tile record count"):
                    build_boundary_package(
                        database_path=database,
                        ingest_receipt_path=ingest_receipt,
                        output_directory=output,
                        package_id="boundary-fixture-v3",
                        zooms=(4,),
                        limits=BoundaryProductionLimits(
                            workers=1,
                            max_in_flight_batches=1,
                            max_features_per_batch=2,
                            max_points_per_batch=8,
                            max_input_bytes_per_batch=64 * 1024,
                            max_spool_bytes_per_batch=2 * 1024 * 1024,
                            max_total_spool_bytes=2 * 1024 * 1024,
                        ),
                    )

            self.assertFalse(output.exists())

    def test_runtime_output_quota_failure_never_publishes_output(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            output = root / "oversize"

            with self.assertRaisesRegex(BoundaryProductionError, "output byte quota"):
                build_boundary_package(
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                    output_directory=output,
                    package_id="boundary-fixture-v3",
                    zooms=(4,),
                    limits=BoundaryProductionLimits(
                        workers=1,
                        max_in_flight_batches=1,
                        max_features_per_batch=2,
                        max_points_per_batch=8,
                        max_input_bytes_per_batch=64 * 1024,
                        max_spool_bytes_per_batch=2 * 1024 * 1024,
                        max_total_spool_bytes=2 * 1024 * 1024,
                        max_runtime_package_bytes=1,
                    ),
                )

            self.assertFalse(output.exists())

    def test_render_database_quota_failure_never_publishes_output(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            output = root / "render-db-full"

            with self.assertRaisesRegex(BoundaryProductionError, "render database.*quota"):
                build_boundary_package(
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                    output_directory=output,
                    package_id="boundary-fixture-v3",
                    zooms=(4,),
                    limits=BoundaryProductionLimits(
                        workers=1,
                        max_in_flight_batches=1,
                        max_features_per_batch=2,
                        max_points_per_batch=8,
                        max_input_bytes_per_batch=64 * 1024,
                        max_spool_bytes_per_batch=2 * 1024 * 1024,
                        max_total_spool_bytes=2 * 1024 * 1024,
                        max_render_database_bytes=4_096,
                    ),
                )

            self.assertFalse(output.exists())

    def test_build_capacity_preflight_fails_before_workers_or_publication(self) -> None:
        from tools.experiment8.osm_boundary_production import (
            BoundaryProductionError,
            BoundaryProductionLimits,
            build_boundary_package,
        )

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            database, ingest_receipt = self._ingest(root)
            output = root / "no-capacity"

            with self.assertRaisesRegex(BoundaryProductionError, "capacity"):
                build_boundary_package(
                    database_path=database,
                    ingest_receipt_path=ingest_receipt,
                    output_directory=output,
                    package_id="boundary-fixture-v3",
                    zooms=(4,),
                    limits=BoundaryProductionLimits(
                        workers=1,
                        max_in_flight_batches=1,
                        max_features_per_batch=2,
                        max_points_per_batch=8,
                        max_input_bytes_per_batch=64 * 1024,
                        max_spool_bytes_per_batch=2 * 1024 * 1024,
                        max_total_spool_bytes=2 * 1024 * 1024,
                        max_render_database_bytes=16 * 1024 * 1024,
                        max_runtime_package_bytes=16 * 1024 * 1024,
                        destination_reserve_bytes=(1 << 63) - 1,
                    ),
                )

            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
