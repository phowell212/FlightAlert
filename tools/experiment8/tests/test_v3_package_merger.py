from __future__ import annotations

import dataclasses
import hashlib
import io
import json
import shutil
import struct
import tempfile
import threading
import unittest
import zlib
from pathlib import Path
from unittest import mock

from tools.experiment8.model import TileKey
from tools.experiment8.renderer_tile_package import (
    INDEX_ENTRY_BYTES,
    RendererTileRecord,
    build_package,
    decode_tile_payload,
    encode_tile_payload,
)
from tools.experiment8.semantic_model import renderer_record_bytes
from tools.experiment8.sourced_text import create_sourced_map_text
from tools.experiment8.tests.test_renderer_tile_package import (
    CAIRO_TEXT,
    _cairo_renderer_record,
    _cairo_sourced_text,
    _line_renderer_record,
)


def _write_package(
    directory: Path,
    package_id: str,
    payloads: dict[TileKey, bytes],
    *,
    complete_declared_scope: bool = True,
    complete_whole_earth_dictionary: bool = False,
) -> None:
    artifacts = build_package(
        package_id,
        payloads,
        complete_declared_scope=complete_declared_scope,
        complete_whole_earth_dictionary=complete_whole_earth_dictionary,
    )
    directory.mkdir()
    (directory / "manifest.json").write_bytes(artifacts.manifest_bytes)
    (directory / "records.fadictpack").write_bytes(artifacts.records_bytes)
    (directory / "tile-index.bin").write_bytes(artifacts.index_bytes)


def _add_manifest_runtime_authority(directory: Path, key: str) -> None:
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text("utf-8"))
    manifest[key] = {
        "records": {
            "bytes": (directory / "records.fadictpack").stat().st_size,
            "sha256": _sha256(directory / "records.fadictpack"),
        },
        "tileIndex": {
            "bytes": (directory / "tile-index.bin").stat().st_size,
            "sha256": _sha256(directory / "tile-index.bin"),
        },
    }
    manifest_path.write_bytes(_canonical_json_bytes(manifest))


def _read_payload(directory: Path, tile: TileKey) -> bytes | None:
    manifest = json.loads((directory / "manifest.json").read_text("utf-8"))
    ordinal = 0
    selected = None
    for item in manifest["coverage"]["zoomRanges"]:
        width = item["xMax"] - item["xMin"] + 1
        if (
            item["z"] == tile.z
            and item["xMin"] <= tile.x <= item["xMax"]
            and item["yMin"] <= tile.y <= item["yMax"]
        ):
            selected = ordinal + (tile.y - item["yMin"]) * width + tile.x - item["xMin"]
            break
        ordinal += item["tileCount"]
    if selected is None:
        return None
    with (directory / "tile-index.bin").open("rb") as handle:
        handle.seek(selected * INDEX_ENTRY_BYTES)
        entry = handle.read(INDEX_ENTRY_BYTES)
    if entry == b"\0" * INDEX_ENTRY_BYTES:
        return None
    offset, compressed_length, raw_length, expected_hash32, flags = struct.unpack(
        "<QIIII", entry
    )
    assert flags == 1
    with (directory / "records.fadictpack").open("rb") as handle:
        handle.seek(offset)
        compressed = handle.read(compressed_length)
    payload = zlib.decompress(compressed, wbits=-zlib.MAX_WBITS)
    assert len(payload) == raw_length
    assert int.from_bytes(hashlib.sha256(payload).digest()[:4], "big") == expected_hash32
    return payload


def _raw_envelopes(payload: bytes) -> tuple[bytes, ...]:
    offset = len(b"FAE8TILE1\0") + struct.calcsize("<BIII")
    count = struct.unpack_from("<I", payload, len(b"FAE8TILE1\0") + 9)[0]
    envelopes = []
    for _ in range(count):
        start = offset
        renderer_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4 + renderer_length
        sourced_length = struct.unpack_from("<I", payload, offset)[0]
        offset += 4
        if sourced_length:
            offset += 32 + sourced_length
        envelopes.append(payload[start:offset])
    assert offset == len(payload)
    return tuple(envelopes)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_json_bytes(document: object) -> bytes:
    return (
        json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
        + "\n"
    ).encode("utf-8")


def _size_policy_binding(
    mode: str = "complete-uncompressed-visual-evaluation-v1",
) -> dict[str, object]:
    document = {
        "constraints": {
            "contentPruningAuthorized": False,
            "nonSizeBoundsMayBeWeakened": False,
            "visualEvaluationRequiresCompleteUncompressedPackage": True,
        },
        "destinationReserveBytes": 1_500_000_000,
        "historicalBudgets": {
            "hardComponentPackageBytes": 38_500_000_000,
            "hardMandatoryPhoneFootprintBytes": 40_000_000_000,
            "preferredComponentPackageBytes": 23_500_000_000,
            "preferredMandatoryPhoneFootprintBytes": 25_000_000_000,
        },
        "modes": [
            "budgeted-release-v1",
            "complete-uncompressed-visual-evaluation-v1",
        ],
        "schema": "flightalert.experiment8.reference-size-policy.v2",
        "visualEvaluationCapacityBasis": (
            "fresh-destination-free-plus-exact-owned-partial-before-staging-"
            "and-fresh-final-reserve-proof"
        ),
        "visualEvaluationCapacityPersistence": (
            "memory-only-sqlite-capacity-is-not-authority"
        ),
    }
    return {
        "document": document,
        "documentSha256": hashlib.sha256(_canonical_json_bytes(document)).hexdigest(),
        "mode": mode,
        "module": {"bytes": 245, "sha256": "1" * 64},
        "schema": "flightalert.experiment8.reference-size-policy-binding.v1",
    }


def _size_decision(
    required_bytes: int,
    mode: str = "complete-uncompressed-visual-evaluation-v1",
) -> dict[str, object]:
    reserve = 1_500_000_000
    mandatory = required_bytes + reserve
    decision: dict[str, object] = {
        "authorized": True,
        "availableDestinationBytes": (
            100_000_000_000
            if mode == "complete-uncompressed-visual-evaluation-v1"
            else None
        ),
        "hardComponentPackageCeilingExceeded": required_bytes >= 38_500_000_000,
        "hardMandatoryPhoneFootprintCeilingExceeded": mandatory >= 40_000_000_000,
        "mandatoryPhoneFootprintBytes": mandatory,
        "mode": mode,
        "preferredComponentPackageCeilingExceeded": required_bytes >= 23_500_000_000,
        "preferredMandatoryPhoneFootprintCeilingExceeded": mandatory >= 25_000_000_000,
        "requiredPackageBytes": required_bytes,
        "requiredWithReserveBytes": mandatory,
        "schema": "flightalert.experiment8.reference-size-decision.v1",
    }
    if mode == "complete-uncompressed-visual-evaluation-v1":
        decision.update(
            {
                "publicationBoundaryAuthorized": True,
                "publicationBoundaryDestinationFreeBytes": 90_000_000_000,
                "publicationBoundaryRequiredReserveBytes": reserve,
            }
        )
    return decision


def _package_semantic_sha256(package: Path) -> str:
    digest = hashlib.sha256(b"flight-alert-exp8-semantic-v1\0")
    manifest = json.loads((package / "manifest.json").read_text("utf-8"))
    for window in manifest["coverage"]["zoomRanges"]:
        for x in range(window["xMin"], window["xMax"] + 1):
            for y in range(window["yMin"], window["yMax"] + 1):
                tile = TileKey(window["z"], x, y)
                payload = _read_payload(package, tile)
                if payload is None:
                    continue
                for record in decode_tile_payload(tile, payload).records:
                    body = struct.pack("<Q", tile.packed) + renderer_record_bytes(
                        record.renderer_record
                    )
                    digest.update(struct.pack("<I", len(body)))
                    digest.update(body)
    return digest.hexdigest()


def _write_recovered_water_build_receipt(
    package: Path,
    *,
    mode: str = "complete-uncompressed-visual-evaluation-v1",
    predecessor_identity_bound: bool = False,
    predecessor_mode: str = "budgeted-release-v1",
) -> Path:
    manifest = json.loads((package / "manifest.json").read_text("utf-8"))
    manifest["rendererSemanticStreamSha256"] = _package_semantic_sha256(package)
    binding = _size_policy_binding(mode)
    source = {
        "closureOpl": {"bytes": 456, "sha256": "7" * 64},
        "closurePbf": {"bytes": 345, "sha256": "8" * 64},
        "extractionReceiptSha256": "9" * 64,
        "planet": {
            "bytes": 123,
            "path": "fixture://planet.osm.pbf",
            "sha256": "a" * 64,
        },
        "rootIds": {"bytes": 234, "sha256": "b" * 64},
        "selectionManifestSha256": "c" * 64,
        "selectionPolicySha256": "d" * 64,
    }
    attribution = {"credit": "OpenStreetMap contributors"}
    admission_policy = {"schema": "fixture-waterway-admission-policy.v1"}
    admission_policy_raw = _canonical_json_bytes(admission_policy)
    admission_policy_sha256 = hashlib.sha256(admission_policy_raw).hexdigest()
    admission = {
        "aggregateSha256": "2" * 64,
        "fatalCount": 0,
        "ingestSemanticSha256": "e" * 64,
        "policy": {
            "bytes": len(admission_policy_raw),
            "document": admission_policy,
            "sha256": admission_policy_sha256,
        },
        "schema": "flightalert.experiment8.osm-waterway-admission-receipt.v2",
        "source": source,
    }
    code = {
        "referenceSizePolicy": binding["module"],
        "renderer": {"bytes": 123, "sha256": "4" * 64},
    }
    recovered_publish_code = dict(code)
    recovered_publish_code["renderer"] = {"bytes": 124, "sha256": "5" * 64}
    run_identity = {
        "admissionAggregateSha256": admission["aggregateSha256"],
        "admissionPolicySha256": admission_policy_sha256,
        "checkpointFeatures": 1000,
        "classifierSha256": "3" * 64,
        "code": code,
        "ingestSemanticSha256": admission["ingestSemanticSha256"],
        "packageId": manifest["packageId"],
        "runtime": {"python": "fixture"},
        "schema": "flightalert.experiment8.osm-global-waterway-render-run.v2",
        "sizePolicy": binding,
        "source": source,
        "zooms": [1],
    }
    run_identity_sha256 = hashlib.sha256(
        _canonical_json_bytes(run_identity)
    ).hexdigest()
    manifest["globalWaterwaySupplement"] = {
        "admissionAggregateSha256": admission["aggregateSha256"],
        "admissionPolicySha256": admission_policy_sha256,
        "attribution": attribution,
        "classifierSha256": run_identity["classifierSha256"],
        "ingestSemanticSha256": admission["ingestSemanticSha256"],
        "records": {
            "bytes": (package / "records.fadictpack").stat().st_size,
            "sha256": _sha256(package / "records.fadictpack"),
        },
        "requestedZooms": [1],
        "runIdentitySha256": run_identity_sha256,
        "source": source,
        "tileIndex": {
            "bytes": (package / "tile-index.bin").stat().st_size,
            "sha256": _sha256(package / "tile-index.bin"),
        },
    }
    (package / "manifest.json").write_bytes(_canonical_json_bytes(manifest))
    runtime_files = [
        {
            "bytes": (package / name).stat().st_size,
            "name": name,
            "sha256": _sha256(package / name),
        }
        for name in ("manifest.json", "records.fadictpack", "tile-index.bin")
    ]
    preserved_meta = {
        name: {"bytes": ordinal + 10, "sha256": f"{ordinal:x}" * 64}
        for ordinal, name in enumerate(
            (
                "runIdentity",
                "checkpoint",
                "admissionRunIdentity",
                "admissionCheckpoint",
                "admissionReceipt",
                "ingestReceipt",
            ),
            start=1,
        )
    }
    recovery = {
        "authorityPolicySha256": "f" * 64,
        "backupDatabase": {"bytes": 999, "sha256": "1" * 64},
        "databaseBeforeRecovery": {"bytes": 999, "sha256": "1" * 64},
        "failedRender": {
            "checkpoint": {"renderComplete": False, "renderedFeatures": 42},
            "failureClass": "sqlite3.OperationalError",
            "failureLog": {"bytes": 22, "sha256": "2" * 64},
            "failureMessage": "database is locked",
            "renderRunIdentity": preserved_meta["runIdentity"],
        },
        "newRenderRunIdentity": {
            "documentSha256": run_identity_sha256,
            "schema": run_identity["schema"],
        },
        "preservedMeta": preserved_meta,
        "publishedDirectoryBytes": 0,
        "recoveredAtUtc": "2026-07-14T00:00:00Z",
        "recoveryCode": {"bytes": 321, "sha256": "3" * 64},
        "rendererResetCounts": {
            "feature_ids": 42,
            "geometry_ids": 42,
            "label_ids": 42,
            "records": 42,
            "rendered_features": 42,
            "sourced_ids": 42,
            "variant_ids": 42,
        },
        "resetCount": 1,
        "schema": (
            "flightalert.experiment8."
            "osm-global-waterway-render-recovery.v1"
        ),
        "sizePolicyDecision": _size_decision(0, mode),
        "sizePolicyTransition": {
            "intended": binding,
            "predecessor": {
                "identityBound": predecessor_identity_bound,
                "mode": predecessor_mode,
            },
        },
        "sourceTableCounts": {
            "admission_candidates": 10,
            "admission_roots": 10,
            "nodes": 10,
            "relation_members": 10,
            "relations": 10,
            "roots": 10,
            "way_nodes": 10,
            "ways": 10,
        },
        "sqliteEvidenceOverheadBytes": 1234,
        "transactionComplete": True,
    }
    final_semantic_document = {
        "admissionAggregateSha256": admission["aggregateSha256"],
        "admissionPolicySha256": admission_policy_sha256,
        "ingestSemanticSha256": admission["ingestSemanticSha256"],
        "manifestSha256": runtime_files[0]["sha256"],
        "rendererRunIdentitySha256": run_identity_sha256,
        "rendererSemanticStreamSha256": manifest[
            "rendererSemanticStreamSha256"
        ],
        "schema": "flightalert.experiment8.osm-waterway-final-semantic-identity.v2",
        "source": source,
    }
    receipt: dict[str, object] = {
        "admission": admission,
        "attribution": attribution,
        "build": {
            "classifierSha256": run_identity["classifierSha256"],
            "code": recovered_publish_code,
            "recovery": recovery,
            "runIdentity": run_identity,
            "runIdentitySha256": run_identity_sha256,
            "sizePolicy": {
                "binding": binding,
                "decision": _size_decision(0, mode),
            },
        },
        "catalogCountsClaimed": False,
        "closureAudit": {"missingReferences": 0},
        "finalSemanticIdentitySha256": hashlib.sha256(
            b"FAE8WATERFINAL2\0" + _canonical_json_bytes(final_semantic_document)
        ).hexdigest(),
        "outputFiles": runtime_files,
        "packageId": manifest["packageId"],
        "peakResources": {"processPeakRssMeasured": False},
        "projection": {"publishedDirectoryBytes": 0},
        "rendererTextAudit": {
            "aggregateSha256": "0" * 64,
            "eventCount": 0,
            "schema": "fixture-renderer-text-audit.v1",
            "stateSha256": "1" * 64,
        },
        "rendererSemanticStreamSha256": manifest["rendererSemanticStreamSha256"],
        "schema": "flightalert.experiment8.osm-global-waterway-build.v2",
        "source": source,
    }
    runtime_bytes = sum(item["bytes"] for item in runtime_files)
    receipt_path = package / "build-receipt.json"
    return _write_converged_build_receipt(
        receipt_path,
        receipt,
        runtime_bytes=runtime_bytes,
        mode=mode,
    )


def _write_converged_build_receipt(
    receipt_path: Path,
    receipt: dict[str, object],
    *,
    runtime_bytes: int,
    mode: str,
) -> Path:
    for _ in range(32):
        raw = _canonical_json_bytes(receipt)
        published_bytes = runtime_bytes + len(raw)
        decision = _size_decision(published_bytes, mode)
        receipt["projection"]["publishedDirectoryBytes"] = published_bytes
        receipt["build"]["sizePolicy"]["decision"] = decision
        receipt["build"]["recovery"]["publishedDirectoryBytes"] = published_bytes
        receipt["build"]["recovery"]["sizePolicyDecision"] = decision
        if runtime_bytes + len(_canonical_json_bytes(receipt)) == published_bytes:
            receipt_path.write_bytes(_canonical_json_bytes(receipt))
            return receipt_path
    raise AssertionError("fixture build receipt byte accounting did not converge")


class V3PackageMergerTests(unittest.TestCase):
    def _write_parallel_fixture(self, root: Path) -> tuple[Path, Path]:
        primary = root / "parallel-primary"
        supplement = root / "parallel-supplement"
        primary_payloads: dict[TileKey, bytes] = {}
        supplement_payloads: dict[TileKey, bytes] = {}
        for y in range(8):
            for x in range(8):
                tile = TileKey(3, x, y)
                first = _line_renderer_record(tile)
                second = dataclasses.replace(
                    first,
                    posting=dataclasses.replace(
                        first.posting,
                        feature_id=(
                            first.posting.feature_id ^ 0x0F0F0F0F0F0F0F0F
                        ),
                    ),
                )
                primary_payloads[tile] = encode_tile_payload(
                    tile, [RendererTileRecord(first, None)]
                )
                supplement_payloads[tile] = encode_tile_payload(
                    tile, [RendererTileRecord(second, None)]
                )
        _write_package(primary, "parallel-primary", primary_payloads)
        _write_package(supplement, "parallel-supplement", supplement_payloads)
        return primary, supplement

    def _inverted_parallel_deflater(self, original_deflate):
        first_tile = (0, 0)
        first_started = threading.Event()
        later_completed = threading.Event()
        observation_lock = threading.Lock()
        completion_order: list[tuple[int, int]] = []
        worker_threads: set[int] = set()

        def deflate(payload: bytes) -> bytes:
            _, x, y, _ = struct.unpack_from(
                "<BIII", payload, len(b"FAE8TILE1\0")
            )
            tile = (x, y)
            if tile == first_tile:
                first_started.set()
                if not later_completed.wait(5):
                    raise AssertionError("later compression did not overtake the first")
            else:
                if not first_started.wait(5):
                    raise AssertionError("first compression worker did not start")
            compressed = original_deflate(payload)
            with observation_lock:
                worker_threads.add(threading.get_ident())
                completion_order.append(tile)
            if tile != first_tile:
                later_completed.set()
            return compressed

        return deflate, completion_order, worker_threads

    def test_parallel_and_serial_binary_streams_are_byte_identical(self) -> None:
        from tools.experiment8 import v3_package_merger
        from tools.experiment8.v3_class_catalog_finalizer import _load_package

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = self._write_parallel_fixture(root)
            serial = root / "serial"
            parallel = root / "parallel"
            v3_package_merger.merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=serial,
                package_id="parallel-equivalence",
                compression_workers=1,
            )

            original_deflate = v3_package_merger.raw_deflate
            deliberately_reordered_deflate, completion_order, worker_threads = (
                self._inverted_parallel_deflater(original_deflate)
            )

            with mock.patch.object(
                v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
            ), mock.patch.object(
                v3_package_merger,
                "raw_deflate",
                deliberately_reordered_deflate,
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=parallel,
                    package_id="parallel-equivalence",
                    compression_workers=4,
                )

            self.assertGreaterEqual(len(worker_threads), 2)
            self.assertNotEqual(completion_order[0], (0, 0))
            self.assertGreater(completion_order.index((0, 0)), 0)
            for name in ("records.fadictpack", "tile-index.bin"):
                self.assertEqual(
                    (serial / name).read_bytes(),
                    (parallel / name).read_bytes(),
                )
            serial_manifest = json.loads((serial / "manifest.json").read_text("utf-8"))
            parallel_manifest = json.loads(
                (parallel / "manifest.json").read_text("utf-8")
            )
            serial_receipt = json.loads(
                (serial / "merge-receipt.json").read_text("utf-8")
            )
            parallel_receipt = json.loads(
                (parallel / "merge-receipt.json").read_text("utf-8")
            )
            self.assertEqual(serial_manifest, parallel_manifest)
            self.assertEqual(serial_receipt, parallel_receipt)
            self.assertEqual(_load_package(serial).tile_count, 64)
            self.assertEqual(_load_package(parallel).tile_count, 64)

    def test_parallel_queue_is_bounded_by_batch_count_and_raw_bytes(self) -> None:
        from tools.experiment8 import v3_package_merger

        class DeferredFuture:
            def __init__(self, owner, function, arguments):
                self.owner = owner
                self.function = function
                self.arguments = arguments

            def result(self):
                try:
                    return self.function(*self.arguments)
                finally:
                    self.owner.outstanding -= 1

        class DeferredExecutor:
            instances = []

            def __init__(self, *, max_workers, thread_name_prefix):
                self.max_workers = max_workers
                self.thread_name_prefix = thread_name_prefix
                self.outstanding = 0
                self.maximum_outstanding = 0
                self.__class__.instances.append(self)

            def submit(self, function, *arguments):
                self.outstanding += 1
                self.maximum_outstanding = max(
                    self.maximum_outstanding, self.outstanding
                )
                return DeferredFuture(self, function, arguments)

            def shutdown(self, *, wait, cancel_futures):
                self.assertions = (wait, cancel_futures)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = self._write_parallel_fixture(root)
            with mock.patch.object(
                v3_package_merger,
                "ThreadPoolExecutor",
                DeferredExecutor,
            ), mock.patch.object(
                v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
            ), mock.patch.object(
                v3_package_merger, "_PARALLEL_PENDING_RAW_BYTES", 1_500
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=root / "bounded",
                    package_id="bounded-parallel-queue",
                    compression_workers=4,
                )
            self.assertEqual(len(DeferredExecutor.instances), 1)
            raw_bounded = DeferredExecutor.instances.pop()
            self.assertEqual(raw_bounded.maximum_outstanding, 1)
            self.assertEqual(raw_bounded.outstanding, 0)
            self.assertEqual(raw_bounded.assertions, (True, True))

            with mock.patch.object(
                v3_package_merger,
                "ThreadPoolExecutor",
                DeferredExecutor,
            ), mock.patch.object(
                v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
            ), mock.patch.object(
                v3_package_merger,
                "_PARALLEL_PENDING_RAW_BYTES",
                1 << 30,
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=root / "batch-bounded",
                    package_id="batch-bounded-parallel-queue",
                    compression_workers=2,
                )
            self.assertEqual(len(DeferredExecutor.instances), 1)
            batch_bounded = DeferredExecutor.instances.pop()
            self.assertEqual(batch_bounded.maximum_outstanding, 4)
            self.assertEqual(batch_bounded.outstanding, 0)
            self.assertEqual(batch_bounded.assertions, (True, True))

    def test_earlier_worker_failure_precedes_a_later_input_failure(self) -> None:
        from tools.experiment8 import v3_package_merger

        class WorkerFailure(RuntimeError):
            pass

        class InputFailure(RuntimeError):
            pass

        def failing_input():
            yield b"first already-produced payload"
            raise InputFailure("later input failure")

        real_executor = v3_package_merger.ThreadPoolExecutor

        class RecordingExecutor:
            instances = []

            def __init__(self, *, max_workers, thread_name_prefix):
                self.delegate = real_executor(
                    max_workers=max_workers,
                    thread_name_prefix=thread_name_prefix,
                )
                self.shutdown_arguments = None
                self.__class__.instances.append(self)

            def submit(self, function, *arguments):
                return self.delegate.submit(function, *arguments)

            def shutdown(self, *, wait, cancel_futures):
                self.shutdown_arguments = (wait, cancel_futures)
                self.delegate.shutdown(
                    wait=wait,
                    cancel_futures=cancel_futures,
                )

        def fail_compression(_payload):
            raise WorkerFailure("earlier compression failure")

        with mock.patch.object(
            v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
        ), mock.patch.object(
            v3_package_merger,
            "raw_deflate",
            side_effect=fail_compression,
        ):
            with self.assertRaisesRegex(
                WorkerFailure, "earlier compression failure"
            ):
                list(
                    v3_package_merger._encode_tiles_ordered(
                        failing_input(), compression_workers=1
                    )
                )
            with mock.patch.object(
                v3_package_merger,
                "ThreadPoolExecutor",
                RecordingExecutor,
            ), self.assertRaisesRegex(
                WorkerFailure, "earlier compression failure"
            ):
                list(
                    v3_package_merger._encode_tiles_ordered(
                        failing_input(), compression_workers=2
                    )
                )
        self.assertEqual(len(RecordingExecutor.instances), 1)
        self.assertEqual(
            RecordingExecutor.instances[0].shutdown_arguments,
            (True, True),
        )
        self.assertFalse(
            any(
                thread.name.startswith("flightalert-v3-merge")
                for thread in threading.enumerate()
            )
        )

    def test_earlier_worker_failure_precedes_a_later_submit_failure(self) -> None:
        from tools.experiment8 import v3_package_merger

        class WorkerFailure(RuntimeError):
            pass

        class SubmitFailure(RuntimeError):
            pass

        real_executor = v3_package_merger.ThreadPoolExecutor

        class SecondSubmitFails:
            instances = []

            def __init__(self, *, max_workers, thread_name_prefix):
                self.delegate = real_executor(
                    max_workers=max_workers,
                    thread_name_prefix=thread_name_prefix,
                )
                self.submissions = 0
                self.shutdown_arguments = None
                self.worker_started = threading.Event()
                self.release_worker = threading.Event()
                self.__class__.instances.append(self)

            def submit(self, function, *arguments):
                self.submissions += 1
                if self.submissions == 1:
                    def run_first_submission():
                        self.worker_started.set()
                        if not self.release_worker.wait(5):
                            raise AssertionError("first compression worker was not released")
                        return function(*arguments)

                    return self.delegate.submit(run_first_submission)
                if not self.worker_started.wait(5):
                    raise AssertionError("first compression worker did not start")
                self.release_worker.set()
                raise SubmitFailure("later submit failure")

            def shutdown(self, *, wait, cancel_futures):
                self.shutdown_arguments = (wait, cancel_futures)
                self.release_worker.set()
                self.delegate.shutdown(
                    wait=wait,
                    cancel_futures=cancel_futures,
                )

        with mock.patch.object(
            v3_package_merger,
            "ThreadPoolExecutor",
            SecondSubmitFails,
        ), mock.patch.object(
            v3_package_merger,
            "raw_deflate",
            side_effect=WorkerFailure("earlier compression failure"),
        ), mock.patch.object(
            v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
        ), self.assertRaisesRegex(
            WorkerFailure, "earlier compression failure"
        ):
            list(
                v3_package_merger._encode_tiles_ordered(
                    (b"first", b"second"), compression_workers=2
                )
            )
        self.assertEqual(len(SecondSubmitFails.instances), 1)
        executor = SecondSubmitFails.instances[0]
        self.assertEqual(executor.submissions, 2)
        self.assertEqual(executor.shutdown_arguments, (True, True))
        self.assertFalse(
            any(
                thread.name.startswith("flightalert-v3-merge")
                for thread in threading.enumerate()
            )
        )

    def test_parallel_batch_preserves_earlier_output_failure_and_cleans_partial(
        self,
    ) -> None:
        from tools.experiment8 import v3_package_merger

        class WorkerFailure(RuntimeError):
            pass

        class OutputFailure(RuntimeError):
            pass

        first_tile = TileKey(1, 0, 0)
        second_tile = TileKey(1, 0, 1)
        payloads = {
            tile: encode_tile_payload(
                tile,
                [RendererTileRecord(_line_renderer_record(tile), None)],
            )
            for tile in (first_tile, second_tile)
        }
        original_deflate = v3_package_merger.raw_deflate
        original_open = Path.open

        def fail_second_compression(payload: bytes) -> bytes:
            _, _, y, _ = struct.unpack_from(
                "<BIII", payload, len(b"FAE8TILE1\0")
            )
            if y == second_tile.y:
                raise WorkerFailure("later compression failure")
            return original_deflate(payload)

        class FailingRecordsHandle:
            def __init__(self, delegate):
                self.delegate = delegate

            def __enter__(self):
                self.delegate.__enter__()
                return self

            def __exit__(self, *arguments):
                return self.delegate.__exit__(*arguments)

            def __getattr__(self, name):
                return getattr(self.delegate, name)

            def write(self, _payload):
                raise OutputFailure("earlier output failure")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            _write_package(primary, "output-failure-primary", payloads)
            for workers in (1, 2):
                with self.subTest(compression_workers=workers):
                    output = root / f"output-{workers}"
                    partial = output.with_name(output.name + ".partial")

                    def fail_records_open(path: Path, *arguments, **keywords):
                        delegate = original_open(path, *arguments, **keywords)
                        if path == partial / "records.fadictpack":
                            return FailingRecordsHandle(delegate)
                        return delegate

                    with mock.patch.object(
                        v3_package_merger,
                        "raw_deflate",
                        side_effect=fail_second_compression,
                    ), mock.patch.object(
                        v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 2
                    ), mock.patch.object(
                        Path, "open", fail_records_open
                    ), mock.patch.object(
                        v3_package_merger.os,
                        "replace",
                        side_effect=AssertionError("failed merge was published"),
                    ) as replace_mock, self.assertRaisesRegex(
                        OutputFailure, "earlier output failure"
                    ):
                        v3_package_merger.merge_v3_packages(
                            primary_directory=primary,
                            supplement_directories=(),
                            output_directory=output,
                            package_id=f"output-failure-{workers}",
                            compression_workers=workers,
                        )
                    replace_mock.assert_not_called()
                    self.assertFalse(output.exists())
                    self.assertFalse(partial.exists())
            self.assertFalse(
                any(
                    thread.name.startswith("flightalert-v3-merge")
                    for thread in threading.enumerate()
                )
            )

    def test_parallel_dual_failure_never_publishes_or_leaks_partial(self) -> None:
        from tools.experiment8 import v3_package_merger

        class WorkerFailure(RuntimeError):
            pass

        class InputFailure(RuntimeError):
            pass

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = self._write_parallel_fixture(root)
            output = root / "dual-failure-output"
            partial = output.with_name(output.name + ".partial")
            original_merge_payload = v3_package_merger._merge_payload
            merged_payload_calls = 0

            def fail_second_payload(*arguments, **keywords):
                nonlocal merged_payload_calls
                merged_payload_calls += 1
                if merged_payload_calls == 2:
                    raise InputFailure("later input failure")
                return original_merge_payload(*arguments, **keywords)

            with mock.patch.object(
                v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
            ), mock.patch.object(
                v3_package_merger,
                "raw_deflate",
                side_effect=WorkerFailure("earlier compression failure"),
            ), mock.patch.object(
                v3_package_merger,
                "_merge_payload",
                side_effect=fail_second_payload,
            ), mock.patch.object(
                v3_package_merger.os,
                "replace",
                side_effect=AssertionError("failed merge was published"),
            ) as replace_mock, self.assertRaisesRegex(
                WorkerFailure, "earlier compression failure"
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=output,
                    package_id="dual-failure-cleanup",
                    compression_workers=2,
                )
            replace_mock.assert_not_called()
            self.assertFalse(output.exists())
            self.assertFalse(partial.exists())

    def test_one_worker_is_a_serial_fallback_without_an_executor(self) -> None:
        from tools.experiment8 import v3_package_merger

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = self._write_parallel_fixture(root)
            with mock.patch.object(
                v3_package_merger,
                "ThreadPoolExecutor",
                side_effect=AssertionError("serial fallback created an executor"),
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=root / "serial",
                    package_id="serial-fallback",
                    compression_workers=1,
                )

    def test_cli_can_select_the_serial_compression_fallback(self) -> None:
        from tools.experiment8 import v3_package_merger

        captured = {}

        def capture_merge(**keywords):
            captured.update(keywords)
            return v3_package_merger.MergeResult(
                keywords["output_directory"], {}, {}
            )

        with mock.patch.object(
            v3_package_merger,
            "merge_v3_packages",
            side_effect=capture_merge,
        ):
            status = v3_package_merger._main(
                (
                    "--primary",
                    "primary",
                    "--supplement",
                    "supplement",
                    "--output",
                    "output",
                    "--package-id",
                    "cli-workers",
                    "--compression-workers",
                    "1",
                )
            )

        self.assertEqual(status, 0)
        self.assertEqual(captured["compression_workers"], 1)

    def test_merger_identity_drift_has_an_exact_canonical_whitelist(self) -> None:
        from tools.experiment8 import v3_package_merger

        def leaf_differences(left, right, path=()):
            if type(left) is not type(right):
                return {path}
            if isinstance(left, dict):
                if set(left) != set(right):
                    return {path}
                result = set()
                for key in left:
                    result.update(
                        leaf_differences(left[key], right[key], path + (key,))
                    )
                return result
            if isinstance(left, list):
                if len(left) != len(right):
                    return {path}
                result = set()
                for index, (left_item, right_item) in enumerate(zip(left, right)):
                    result.update(
                        leaf_differences(
                            left_item, right_item, path + (index,)
                        )
                    )
                return result
            return set() if left == right else {path}

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = self._write_parallel_fixture(root)
            old_output = root / "old-identity"
            new_output = root / "new-identity"
            with mock.patch.object(
                v3_package_merger, "_merger_sha256", return_value="1" * 64
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=old_output,
                    package_id="identity-whitelist",
                    compression_workers=1,
                )
            deliberately_reordered_deflate, completion_order, worker_threads = (
                self._inverted_parallel_deflater(v3_package_merger.raw_deflate)
            )
            with mock.patch.object(
                v3_package_merger, "_merger_sha256", return_value="2" * 64
            ), mock.patch.object(
                v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
            ), mock.patch.object(
                v3_package_merger,
                "raw_deflate",
                deliberately_reordered_deflate,
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=new_output,
                    package_id="identity-whitelist",
                    compression_workers=4,
                )

            self.assertGreaterEqual(len(worker_threads), 2)
            self.assertNotEqual(completion_order[0], (0, 0))
            self.assertGreater(completion_order.index((0, 0)), 0)
            for name in ("records.fadictpack", "tile-index.bin"):
                self.assertEqual(
                    (old_output / name).read_bytes(),
                    (new_output / name).read_bytes(),
                )
            old_manifest = json.loads(
                (old_output / "manifest.json").read_text("utf-8")
            )
            new_manifest = json.loads(
                (new_output / "manifest.json").read_text("utf-8")
            )
            old_receipt = json.loads(
                (old_output / "merge-receipt.json").read_text("utf-8")
            )
            new_receipt = json.loads(
                (new_output / "merge-receipt.json").read_text("utf-8")
            )
            self.assertEqual(
                leaf_differences(old_manifest, new_manifest),
                {("merge", "mergerSha256")},
            )
            self.assertEqual(
                leaf_differences(old_receipt, new_receipt),
                {
                    ("mergerSha256",),
                    ("outputFiles", 0, "sha256"),
                },
            )

    def test_compressed_input_length_is_bounded_before_record_read(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            _InputPackage,
            _InputState,
            _Range,
            _Window,
            _read_input_tile,
        )

        class ForbiddenRecordsRead:
            def read(self, length: int) -> bytes:
                raise AssertionError(f"attempted to materialize {length} compressed bytes")

        tile = TileKey(0, 0, 0)
        compressed_length = (1 << 32) - 1
        package = _InputPackage(
            directory=Path("."),
            package_id="bounded-input",
            manifest_sha256="0" * 64,
            manifest_bytes=1,
            records_sha256="0" * 64,
            records_bytes=compressed_length,
            index_sha256="0" * 64,
            index_bytes=INDEX_ENTRY_BYTES,
            ranges=(_Range(_Window(0, 0, 0, 0, 0), 0),),
            tile_count=1,
            complete_declared_scope=False,
            complete_whole_earth_dictionary=False,
        )
        state = _InputState(
            index_handle=io.BytesIO(
                struct.pack("<QIIII", 0, compressed_length, 1, 0, 1)
            ),
            records_handle=ForbiddenRecordsRead(),
        )

        with self.assertRaisesRegex(V3PackageMergeError, "compressed.*bound"):
            _read_input_tile(package, state, tile)

    def test_same_tile_contributions_are_deduped_before_payload_assembly(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            supplements = tuple(root / f"supplement-{index}" for index in range(8))
            output = root / "output"
            _write_package(primary, "primary", {tile: payload})
            for index, supplement in enumerate(supplements):
                _write_package(supplement, f"supplement-{index}", {tile: payload})
            original_merge_payload = v3_package_merger._merge_payload

            def guarded_merge_payload(tile_key, merged_records, *arguments):
                self.assertIsInstance(merged_records, dict)
                self.assertEqual(len(merged_records), 1)
                return original_merge_payload(tile_key, merged_records, *arguments)

            with mock.patch.object(
                v3_package_merger,
                "_merge_payload",
                guarded_merge_payload,
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=supplements,
                    output_directory=output,
                    package_id="incremental-dedupe",
                )

    def test_rectangular_union_rejects_an_android_unreadable_index(self) -> None:
        from tools.experiment8 import v3_package_merger

        first = TileKey(14, 0, 0)
        last = TileKey(14, (1 << 14) - 1, (1 << 14) - 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = root / "primary", root / "supplement"
            _write_package(
                primary,
                "primary",
                {
                    first: encode_tile_payload(
                        first,
                        [RendererTileRecord(_line_renderer_record(first), None)],
                    )
                },
            )
            _write_package(
                supplement,
                "supplement",
                {
                    last: encode_tile_payload(
                        last,
                        [RendererTileRecord(_line_renderer_record(last), None)],
                    )
                },
            )

            with mock.patch.object(
                v3_package_merger,
                "_tiles_index_order",
                side_effect=AssertionError("oversized union traversal began"),
            ):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError,
                    "Android.*index",
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(supplement,),
                        output_directory=root / "output",
                        package_id="oversized-union",
                    )

    def test_scope_extending_input_must_justify_whole_earth_completeness(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        primary_tile = TileKey(0, 0, 0)
        supplement_tiles = tuple(
            TileKey(1, x, y)
            for y in range(2)
            for x in range(2)
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = (
                root / "primary",
                root / "supplement",
                root / "output",
            )
            _write_package(
                primary,
                "whole-primary",
                {
                    primary_tile: encode_tile_payload(
                        primary_tile,
                        [RendererTileRecord(_line_renderer_record(primary_tile), None)],
                    )
                },
                complete_whole_earth_dictionary=True,
            )
            _write_package(
                supplement,
                "scope-extending-supplement",
                {
                    tile: encode_tile_payload(
                        tile,
                        [RendererTileRecord(_line_renderer_record(tile), None)],
                    )
                    for tile in supplement_tiles
                },
                complete_whole_earth_dictionary=False,
            )

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=output,
                package_id="conservative-whole-earth",
            )

            self.assertFalse(
                result.manifest["coverage"]["completeWholeEarthDictionary"]
            )

    def test_input_hashes_bind_the_exact_streams_consumed(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        renderer = _cairo_renderer_record()
        canonical = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, _cairo_sourced_text())],
        )
        alternate_text = create_sourced_map_text(
            primary=CAIRO_TEXT,
            primary_source_field_id=201,
            declared_english="Hotel",
            english_source_field_id=202,
        )
        alternate = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, alternate_text)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, replacement, output = (
                root / "primary",
                root / "replacement",
                root / "output",
            )
            _write_package(primary, "mutable-primary", {tile: canonical})
            _write_package(replacement, "mutable-primary", {tile: alternate})
            self.assertEqual(
                (primary / "records.fadictpack").stat().st_size,
                (replacement / "records.fadictpack").stat().st_size,
            )
            original_union_windows = v3_package_merger._union_windows

            def replace_after_hash(packages):
                shutil.copyfile(
                    replacement / "records.fadictpack",
                    primary / "records.fadictpack",
                )
                shutil.copyfile(
                    replacement / "tile-index.bin",
                    primary / "tile-index.bin",
                )
                return original_union_windows(packages)

            with mock.patch.object(
                v3_package_merger,
                "_union_windows",
                replace_after_hash,
            ):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError,
                    "changed while being merged",
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(),
                        output_directory=output,
                        package_id="hash-bound-input",
                    )
            self.assertFalse(output.exists())

    def test_manifest_binding_uses_the_exact_bytes_read_after_stat(self) -> None:
        from tools.experiment8.v3_package_merger import _load_input

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package_directory = root / "package"
            _write_package(package_directory, "manifest-race", {tile: payload})
            manifest_path = package_directory / "manifest.json"
            expanded_manifest = manifest_path.read_bytes() + b" "
            original_stat = Path.stat
            manifest_stat_calls = 0

            def racing_stat(path: Path, *arguments, **keywords):
                nonlocal manifest_stat_calls
                result = original_stat(path, *arguments, **keywords)
                if path == manifest_path:
                    manifest_stat_calls += 1
                    if manifest_stat_calls == 2:
                        manifest_path.write_bytes(expanded_manifest)
                return result

            with mock.patch.object(Path, "stat", racing_stat):
                loaded = _load_input(package_directory)

            self.assertEqual(loaded.manifest_bytes, len(expanded_manifest))
            self.assertEqual(
                loaded.manifest_sha256,
                hashlib.sha256(expanded_manifest).hexdigest(),
            )

    def test_manifest_growth_race_cannot_bypass_its_read_bound(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            _load_input,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            package_directory = root / "package"
            _write_package(package_directory, "manifest-bound", {tile: payload})
            manifest_path = package_directory / "manifest.json"
            manifest = manifest_path.read_bytes()
            oversized_manifest = manifest + b" " * (
                16 * 1024 * 1024 + 1 - len(manifest)
            )
            original_stat = Path.stat
            manifest_stat_calls = 0

            def racing_stat(path: Path, *arguments, **keywords):
                nonlocal manifest_stat_calls
                result = original_stat(path, *arguments, **keywords)
                if path == manifest_path:
                    manifest_stat_calls += 1
                    if manifest_stat_calls == 2:
                        manifest_path.write_bytes(oversized_manifest)
                return result

            with mock.patch.object(Path, "stat", racing_stat):
                with self.assertRaisesRegex(
                    V3PackageMergeError,
                    "manifest byte length.*bound",
                ):
                    _load_input(package_directory)

    def test_output_hashes_are_reverified_after_atomic_publication(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, output = root / "primary", root / "output"
            _write_package(primary, "primary", {tile: payload})
            original_replace = v3_package_merger.os.replace

            def publish_tampered(source: Path, destination: Path) -> None:
                records_path = source / "records.fadictpack"
                records = bytearray(records_path.read_bytes())
                records[len(records) // 2] ^= 0x80
                records_path.write_bytes(records)
                original_replace(source, destination)

            with mock.patch.object(
                v3_package_merger.os,
                "replace",
                publish_tampered,
            ):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError,
                    "published output differs",
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(),
                        output_directory=output,
                        package_id="publication-bound-output",
                    )
            self.assertFalse(output.exists())

    def test_parallel_post_write_audit_reopens_streams_before_publication(self) -> None:
        from tools.experiment8 import v3_package_merger

        tiles = (TileKey(1, 0, 0), TileKey(1, 0, 1))
        payloads = {
            tile: encode_tile_payload(
                tile,
                [RendererTileRecord(_line_renderer_record(tile), None)],
            )
            for tile in tiles
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, output = root / "primary", root / "output"
            partial = output.with_name(output.name + ".partial")
            _write_package(primary, "primary", payloads)
            original_audit = v3_package_merger._audit_output
            audit_calls = 0

            def corrupt_written_stream(directory, windows):
                nonlocal audit_calls
                audit_calls += 1
                records_path = directory / "records.fadictpack"
                records = bytearray(records_path.read_bytes())
                records[len(records) // 2] ^= 0x80
                records_path.write_bytes(records)
                return original_audit(directory, windows)

            with mock.patch.object(
                v3_package_merger,
                "_audit_output",
                side_effect=corrupt_written_stream,
            ), mock.patch.object(
                v3_package_merger, "_PARALLEL_BATCH_TILE_LIMIT", 1
            ), mock.patch.object(
                v3_package_merger.os,
                "replace",
                side_effect=AssertionError("unaudited output was published"),
            ) as replace_mock, self.assertRaises(
                v3_package_merger.V3PackageMergeError
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(),
                    output_directory=output,
                    package_id="post-write-audit",
                    compression_workers=2,
                )
            self.assertEqual(audit_calls, 1)
            replace_mock.assert_not_called()
            self.assertFalse(output.exists())
            self.assertFalse(partial.exists())

    def test_output_audit_does_not_random_seek_in_semantic_order(self) -> None:
        from tools.experiment8 import v3_package_merger

        tiles = (TileKey(2, 0, 0), TileKey(2, 3, 0), TileKey(2, 0, 3), TileKey(2, 3, 3))
        payloads = {
            tile: encode_tile_payload(
                tile,
                [RendererTileRecord(_line_renderer_record(tile), None)],
            )
            for tile in tiles
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, output = root / "primary", root / "out"
            _write_package(primary, "primary", payloads, complete_declared_scope=False)
            with mock.patch.object(
                v3_package_merger,
                "_read_output_payload",
                side_effect=AssertionError("output audit used random reads"),
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(),
                    output_directory=output,
                    package_id="sequential-output-audit",
                )
            self.assertTrue((output / "merge-receipt.json").is_file())

    def test_same_tile_merge_preserves_envelopes_orders_and_dedupes(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        cairo = encode_tile_payload(
            tile,
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )
        line = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, duplicate, output = (
                root / "primary",
                root / "supplement",
                root / "duplicate",
                root / "merged",
            )
            _write_package(primary, "primary", {tile: cairo})
            _write_package(supplement, "supplement", {tile: line})
            _write_package(duplicate, "duplicate", {tile: cairo})

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement, duplicate),
                output_directory=output,
                package_id="merged-reference-v3",
            )

            payload = _read_payload(output, tile)
            self.assertIsNotNone(payload)
            assert payload is not None
            decoded = decode_tile_payload(tile, payload)
            self.assertEqual(
                [record.renderer_record.posting.feature_id for record in decoded.records],
                [0x0123456789ABCDEF, 0x8877665544332211],
            )
            self.assertEqual(
                set(_raw_envelopes(payload)),
                set(_raw_envelopes(line) + _raw_envelopes(cairo)),
            )
            self.assertEqual(result.output_directory, output)
            counts = {
                item["semanticSubtype"]: item
                for item in result.receipt["subtypeCounts"]
            }
            self.assertEqual(len(counts), 23)
            self.assertEqual(
                counts[210],
                {
                    "semanticSubtype": 210,
                    "semanticSubtypeName": "CITY_TOWN",
                    "distinctFeatureIds": 1,
                    "canonicalVariantIds": 1,
                    "postings": 1,
                },
            )
            self.assertEqual(counts[560]["postings"], 1)

    def test_same_posting_with_divergent_sourced_text_is_rejected(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        renderer = _cairo_renderer_record()
        canonical = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, _cairo_sourced_text())],
        )
        divergent_text = create_sourced_map_text(
            primary=CAIRO_TEXT,
            primary_source_field_id=201,
            declared_english="Al Qahirah",
            english_source_field_id=202,
        )
        divergent = encode_tile_payload(
            tile,
            [RendererTileRecord(renderer, divergent_text)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(primary, "primary", {tile: canonical})
            _write_package(supplement, "supplement", {tile: divergent})

            with self.assertRaisesRegex(V3PackageMergeError, "divergent duplicate"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=output,
                    package_id="must-fail",
                )

    def test_disjoint_sparse_coverage_uses_zero_missing_ordinals(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        first = TileKey(1, 0, 0)
        last = TileKey(1, 1, 1)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(
                primary,
                "primary",
                {first: encode_tile_payload(first, [RendererTileRecord(_line_renderer_record(first), None)])},
            )
            _write_package(
                supplement,
                "supplement",
                {last: encode_tile_payload(last, [RendererTileRecord(_line_renderer_record(last), None)])},
            )

            merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=output,
                package_id="sparse-union",
            )

            manifest = json.loads((output / "manifest.json").read_text("utf-8"))
            self.assertEqual(
                manifest["coverage"],
                {
                    "completeDeclaredScope": False,
                    "completeWholeEarthDictionary": False,
                    "tileCount": 4,
                    "zoomRanges": [
                        {"z": 1, "xMin": 0, "xMax": 1, "yMin": 0, "yMax": 1, "tileCount": 4}
                    ],
                },
            )
            index = (output / "tile-index.bin").read_bytes()
            self.assertEqual(len(index), 4 * INDEX_ENTRY_BYTES)
            self.assertEqual(index[INDEX_ENTRY_BYTES : 3 * INDEX_ENTRY_BYTES], b"\0" * (2 * INDEX_ENTRY_BYTES))
            self.assertIsNotNone(_read_payload(output, first))
            self.assertIsNotNone(_read_payload(output, last))

    def test_primary_whole_world_claim_is_preserved(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(0, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(
                primary,
                "whole-primary",
                {tile: payload},
                complete_whole_earth_dictionary=True,
            )
            _write_package(supplement, "same-scope-supplement", {tile: payload})

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=output,
                package_id="whole-merged",
            )

            self.assertTrue(result.manifest["coverage"]["completeWholeEarthDictionary"])
            self.assertTrue(result.receipt["coverage"]["primaryWholeEarthPreserved"])

    def test_corrupt_deflate_and_wrong_policy_are_rejected(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, corrupt, wrong_policy = root / "primary", root / "corrupt", root / "wrong-policy"
            _write_package(primary, "primary", {tile: payload})
            _write_package(corrupt, "corrupt", {tile: payload})
            records = bytearray((corrupt / "records.fadictpack").read_bytes())
            records[len(records) // 2] ^= 0x80
            (corrupt / "records.fadictpack").write_bytes(records)
            with self.assertRaisesRegex(V3PackageMergeError, "DEFLATE|integrity|canonical"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(corrupt,),
                    output_directory=root / "corrupt-out",
                    package_id="corrupt-out",
                )

            _write_package(wrong_policy, "wrong-policy", {tile: payload})
            manifest = json.loads((wrong_policy / "manifest.json").read_text("utf-8"))
            manifest["presentationPolicySha256"] = "0" * 64
            (wrong_policy / "manifest.json").write_text(
                json.dumps(manifest, sort_keys=True, separators=(",", ":")) + "\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(V3PackageMergeError, "presentation policy"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(wrong_policy,),
                    output_directory=root / "policy-out",
                    package_id="policy-out",
                )

    def test_merged_tile_limit_is_enforced(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        cairo = encode_tile_payload(
            tile,
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )
        line = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = root / "primary", root / "supplement"
            _write_package(primary, "primary", {tile: cairo})
            _write_package(supplement, "supplement", {tile: line})
            with mock.patch.object(v3_package_merger, "MAX_RECORDS_PER_TILE", 1):
                with self.assertRaisesRegex(
                    v3_package_merger.V3PackageMergeError, "record count"
                ):
                    v3_package_merger.merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(supplement,),
                        output_directory=root / "out",
                        package_id="over-limit",
                    )

    def test_output_and_receipt_are_deterministic_and_hash_bound(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        primary_payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )
        supplement_payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement = root / "primary", root / "supplement"
            first, second = root / "first", root / "second"
            _write_package(primary, "primary", {tile: primary_payload})
            _write_package(supplement, "supplement", {tile: supplement_payload})

            merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=first,
                package_id="deterministic",
            )
            merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(supplement,),
                output_directory=second,
                package_id="deterministic",
            )

            for name in ("manifest.json", "records.fadictpack", "tile-index.bin", "merge-receipt.json"):
                self.assertEqual((first / name).read_bytes(), (second / name).read_bytes())
            receipt = json.loads((first / "merge-receipt.json").read_text("utf-8"))
            output_files = {item["name"]: item for item in receipt["outputFiles"]}
            for name in ("manifest.json", "records.fadictpack", "tile-index.bin"):
                self.assertEqual(output_files[name]["sha256"], _sha256(first / name))
                self.assertEqual(output_files[name]["bytes"], (first / name).stat().st_size)
            self.assertNotIn("rendererContractSha256", json.dumps(receipt))
            self.assertNotIn("classCatalog", receipt)
            self.assertRegex(receipt["rendererSemanticStreamSha256"], r"^[0-9a-f]{64}$")

    def test_records_and_index_are_never_materialized_as_whole_files(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        first = TileKey(5, 0, 0)
        last = TileKey(5, 31, 31)
        payloads = {
            first: encode_tile_payload(first, [RendererTileRecord(_line_renderer_record(first), None)]),
            last: encode_tile_payload(last, [RendererTileRecord(_line_renderer_record(last), None)]),
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            output = root / "output"
            _write_package(
                primary,
                "wide-sparse-primary",
                payloads,
                complete_declared_scope=False,
            )
            original = Path.read_bytes

            def guarded_read_bytes(path: Path) -> bytes:
                if path.name in {"records.fadictpack", "tile-index.bin"}:
                    raise AssertionError("runtime file was materialized")
                return original(path)

            with mock.patch.object(Path, "read_bytes", guarded_read_bytes):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(),
                    output_directory=output,
                    package_id="bounded-stream",
                )
            self.assertEqual((output / "tile-index.bin").stat().st_size, 1024 * INDEX_ENTRY_BYTES)

    def test_manifest_runtime_facts_avoid_redundant_input_prehash(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, supplement, output = root / "primary", root / "supplement", root / "out"
            _write_package(primary, "primary", {tile: payload})
            _write_package(supplement, "supplement", {tile: payload})
            _add_manifest_runtime_authority(primary, "globalPlaceSupplement")
            _add_manifest_runtime_authority(supplement, "globalPlaceSupplement")
            input_runtime_files = {
                (primary / "records.fadictpack").resolve(),
                (primary / "tile-index.bin").resolve(),
                (supplement / "records.fadictpack").resolve(),
                (supplement / "tile-index.bin").resolve(),
            }
            original_sha256_file = v3_package_merger._sha256_file

            def guarded_sha256_file(path: Path) -> str:
                if path.resolve() in input_runtime_files:
                    raise AssertionError("input runtime file was prehashed")
                return original_sha256_file(path)

            with mock.patch.object(
                v3_package_merger,
                "_sha256_file",
                guarded_sha256_file,
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(supplement,),
                    output_directory=output,
                    package_id="manifest-bound-runtime",
                )
            self.assertTrue((output / "records.fadictpack").is_file())

    def test_recovered_water_receipt_is_authenticated_and_carried_forward(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, water, output = root / "primary", root / "water", root / "out"
            _write_package(primary, "primary", {tile: payload})
            _write_package(water, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(water)
            source_receipt = json.loads(receipt_path.read_text("utf-8"))

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(water,),
                supplement_build_receipts=(receipt_path,),
                output_directory=output,
                package_id="merged-with-water-authority",
            )

            manifest_authority = result.manifest["merge"]["authorityReceipts"]
            receipt_authority = result.receipt["authorityReceipts"]
            self.assertEqual(
                "flightalert.experiment8.v3-package-merge.v2",
                result.manifest["merge"]["schema"],
            )
            self.assertEqual(
                "flightalert.experiment8.v3-package-merge-receipt.v2",
                result.receipt["schema"],
            )
            self.assertEqual(manifest_authority, receipt_authority)
            self.assertEqual(1, len(manifest_authority))
            carried = manifest_authority[0]
            self.assertEqual("supplement", carried["role"])
            self.assertEqual("water", carried["packageId"])
            self.assertEqual(source_receipt, carried["document"])
            self.assertEqual(receipt_path.stat().st_size, carried["bytes"])
            self.assertEqual(_sha256(receipt_path), carried["sha256"])
            self.assertEqual(
                source_receipt["build"]["sizePolicy"]["binding"],
                result.manifest["merge"]["sizePolicy"]["binding"],
            )
            self.assertEqual(
                result.manifest["merge"]["sizePolicy"],
                result.receipt["sizePolicy"],
            )
            self.assertEqual(
                "merge-output-before-class-catalog-finalization",
                result.receipt["sizePolicy"]["accountingScope"],
            )
            final_decision = result.receipt["sizePolicy"]["decision"]
            self.assertEqual(
                sum(path.stat().st_size for path in output.iterdir()),
                final_decision["requiredPackageBytes"],
            )
            self.assertNotEqual(
                source_receipt["build"]["sizePolicy"]["decision"][
                    "requiredPackageBytes"
                ],
                final_decision["requiredPackageBytes"],
            )
            self.assertTrue(final_decision["authorized"])

    def test_authority_receipt_is_bounded_without_path_read_bytes(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, water, output = root / "primary", root / "water", root / "out"
            _write_package(primary, "primary", {tile: payload})
            _write_package(water, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(water)
            original_read_bytes = Path.read_bytes

            def guarded_read_bytes(path: Path) -> bytes:
                if path.name == "build-receipt.json":
                    raise AssertionError("authority receipt was materialized without a bound")
                return original_read_bytes(path)

            with mock.patch.object(Path, "read_bytes", guarded_read_bytes):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(water,),
                    supplement_build_receipts=(receipt_path,),
                    output_directory=output,
                    package_id="bounded-authority-read",
                )

    def test_water_provenance_claims_are_cross_bound_before_carry_forward(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        for mutation, message in (
            ("source", "source"),
            ("admission", "admission"),
            ("final-semantic", "final semantic"),
            ("run-identity", "run identity"),
            ("recovery", "recovery database"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                primary, water = root / "primary", root / "water"
                _write_package(primary, "primary", {tile: payload})
                _write_package(water, "water", {tile: payload})
                receipt_path = _write_recovered_water_build_receipt(water)
                receipt = json.loads(receipt_path.read_text("utf-8"))
                if mutation == "source":
                    receipt["source"]["planet"]["sha256"] = "0" * 64
                elif mutation == "admission":
                    receipt["admission"]["aggregateSha256"] = "0" * 64
                elif mutation == "final-semantic":
                    receipt["finalSemanticIdentitySha256"] = "0" * 64
                elif mutation == "run-identity":
                    receipt["build"]["runIdentity"]["classifierSha256"] = "0" * 64
                    receipt["build"]["runIdentitySha256"] = hashlib.sha256(
                        _canonical_json_bytes(receipt["build"]["runIdentity"])
                    ).hexdigest()
                else:
                    receipt["build"]["recovery"]["databaseBeforeRecovery"][
                        "sha256"
                    ] = "0" * 64
                runtime_bytes = sum(
                    (water / name).stat().st_size
                    for name in ("manifest.json", "records.fadictpack", "tile-index.bin")
                )
                _write_converged_build_receipt(
                    receipt_path,
                    receipt,
                    runtime_bytes=runtime_bytes,
                    mode="complete-uncompressed-visual-evaluation-v1",
                )

                with self.assertRaisesRegex(V3PackageMergeError, message):
                    merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(water,),
                        supplement_build_receipts=(receipt_path,),
                        output_directory=root / "out",
                        package_id="must-fail",
                    )

    def test_authority_json_accounting_converges_at_policy_thresholds(self) -> None:
        from tools.experiment8.v3_package_merger import (
            _converge_authority_documents,
        )

        binding = _size_policy_binding()

        def converge(binary_bytes: int):
            merge_document: dict[str, object] = {}
            manifest = {"merge": merge_document}
            receipt = {
                "outputFiles": [
                    {"bytes": 0, "name": "manifest.json", "sha256": "0" * 64}
                ]
            }
            return _converge_authority_documents(
                manifest=manifest,
                merge_document=merge_document,
                receipt=receipt,
                size_binding=binding,
                binary_output_bytes=binary_bytes,
                initial_destination_free=100_000_000_000,
                publication_boundary_free=90_000_000_000,
            )

        base_manifest, base_receipt, base_decision = converge(0)
        base_overhead = len(base_manifest) + len(base_receipt)
        self.assertEqual(base_overhead, base_decision["requiredPackageBytes"])
        for threshold in (23_500_000_000, 38_500_000_000):
            observed: list[int] = []
            for adjustment in range(-256, 257):
                binary_bytes = threshold - base_overhead + adjustment
                manifest_raw, receipt_raw, decision = converge(binary_bytes)
                required = decision["requiredPackageBytes"]
                self.assertEqual(
                    binary_bytes + len(manifest_raw) + len(receipt_raw),
                    required,
                )
                observed.append(required)
            self.assertLess(min(observed), threshold)
            self.assertGreaterEqual(max(observed), threshold)

    def test_receipt_and_manifest_cannot_jointly_forge_semantic_stream(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, water = root / "primary", root / "water"
            _write_package(primary, "primary", {tile: payload})
            _write_package(water, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(water)
            manifest = json.loads((water / "manifest.json").read_text("utf-8"))
            manifest["rendererSemanticStreamSha256"] = "f" * 64
            (water / "manifest.json").write_bytes(_canonical_json_bytes(manifest))
            receipt = json.loads(receipt_path.read_text("utf-8"))
            receipt["rendererSemanticStreamSha256"] = "f" * 64
            manifest_fact = next(
                item
                for item in receipt["outputFiles"]
                if item["name"] == "manifest.json"
            )
            manifest_fact["bytes"] = (water / "manifest.json").stat().st_size
            manifest_fact["sha256"] = _sha256(water / "manifest.json")
            run_identity = receipt["build"]["runIdentity"]
            final_semantic_document = {
                "admissionAggregateSha256": run_identity[
                    "admissionAggregateSha256"
                ],
                "admissionPolicySha256": run_identity["admissionPolicySha256"],
                "ingestSemanticSha256": run_identity["ingestSemanticSha256"],
                "manifestSha256": manifest_fact["sha256"],
                "rendererRunIdentitySha256": receipt["build"][
                    "runIdentitySha256"
                ],
                "rendererSemanticStreamSha256": "f" * 64,
                "schema": (
                    "flightalert.experiment8."
                    "osm-waterway-final-semantic-identity.v2"
                ),
                "source": receipt["source"],
            }
            receipt["finalSemanticIdentitySha256"] = hashlib.sha256(
                b"FAE8WATERFINAL2\0"
                + _canonical_json_bytes(final_semantic_document)
            ).hexdigest()
            receipt_path.write_bytes(_canonical_json_bytes(receipt))

            with self.assertRaisesRegex(V3PackageMergeError, "renderer semantic"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(water,),
                    supplement_build_receipts=(receipt_path,),
                    output_directory=root / "out",
                    package_id="must-fail",
                )

    def test_semantic_authentication_uses_the_held_merge_streams(self) -> None:
        from tools.experiment8.v3_package_merger import (
            _input_semantic_sha256,
            _load_input,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "water"
            _write_package(package, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(package)
            expected = json.loads(receipt_path.read_text("utf-8"))[
                "rendererSemanticStreamSha256"
            ]
            loaded = _load_input(package)
            records_handle = io.BytesIO((package / "records.fadictpack").read_bytes())
            index_handle = io.BytesIO((package / "tile-index.bin").read_bytes())
            original_open = Path.open

            def guarded_open(path: Path, *args, **kwargs):
                if path.name in {"records.fadictpack", "tile-index.bin"}:
                    raise AssertionError("semantic authentication reopened a runtime path")
                return original_open(path, *args, **kwargs)

            with mock.patch.object(Path, "open", guarded_open):
                self.assertEqual(
                    expected,
                    _input_semantic_sha256(
                        loaded,
                        records_handle=records_handle,
                        index_handle=index_handle,
                    ),
                )

    def test_semantic_authentication_does_not_random_seek_in_semantic_order(self) -> None:
        from tools.experiment8 import v3_package_merger
        from tools.experiment8.v3_package_merger import (
            _input_semantic_sha256,
            _load_input,
        )

        tiles = (TileKey(2, 0, 0), TileKey(2, 3, 0), TileKey(2, 0, 3), TileKey(2, 3, 3))
        payloads = {
            tile: encode_tile_payload(
                tile,
                [RendererTileRecord(_line_renderer_record(tile), None)],
            )
            for tile in tiles
        }
        with tempfile.TemporaryDirectory() as temporary:
            package = Path(temporary) / "water"
            _write_package(package, "water", payloads, complete_declared_scope=False)
            receipt_path = _write_recovered_water_build_receipt(package)
            expected = json.loads(receipt_path.read_text("utf-8"))[
                "rendererSemanticStreamSha256"
            ]
            loaded = _load_input(package)
            with (package / "records.fadictpack").open("rb") as records_handle, (
                package / "tile-index.bin"
            ).open("rb") as index_handle, mock.patch.object(
                v3_package_merger,
                "_read_output_payload",
                side_effect=AssertionError("semantic authentication used random reads"),
            ):
                self.assertEqual(
                    expected,
                    _input_semantic_sha256(
                        loaded,
                        records_handle=records_handle,
                        index_handle=index_handle,
                    ),
                )

    def test_recovery_canonical_types_cannot_use_bool_integer_equality(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        for mutation, message in (
            ("reset-bool", "reset count"),
            ("identity-int", "predecessor identity"),
            ("policy-bool", "size-policy document"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                primary, water = root / "primary", root / "water"
                _write_package(primary, "primary", {tile: payload})
                _write_package(water, "water", {tile: payload})
                receipt_path = _write_recovered_water_build_receipt(water)
                receipt = json.loads(receipt_path.read_text("utf-8"))
                recovery = receipt["build"]["recovery"]
                if mutation == "reset-bool":
                    recovery["resetCount"] = True
                elif mutation == "identity-int":
                    recovery["sizePolicyTransition"]["predecessor"][
                        "identityBound"
                    ] = 0
                else:
                    bindings = (
                        receipt["build"]["sizePolicy"]["binding"],
                        receipt["build"]["runIdentity"]["sizePolicy"],
                        recovery["sizePolicyTransition"]["intended"],
                    )
                    for binding in bindings:
                        binding["document"]["constraints"][
                            "contentPruningAuthorized"
                        ] = 0
                        binding["documentSha256"] = hashlib.sha256(
                            _canonical_json_bytes(binding["document"])
                        ).hexdigest()
                runtime_bytes = sum(
                    (water / name).stat().st_size
                    for name in ("manifest.json", "records.fadictpack", "tile-index.bin")
                )
                _write_converged_build_receipt(
                    receipt_path,
                    receipt,
                    runtime_bytes=runtime_bytes,
                    mode="complete-uncompressed-visual-evaluation-v1",
                )

                with self.assertRaisesRegex(V3PackageMergeError, message):
                    merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(water,),
                        supplement_build_receipts=(receipt_path,),
                        output_directory=root / "out",
                        package_id="must-fail",
                    )

    def test_recovery_cannot_downgrade_or_reverse_the_size_policy_transition(self) -> None:
        from tools.experiment8 import v3_package_merger

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, water = root / "primary", root / "water"
            _write_package(primary, "primary", {tile: payload})
            _write_package(water, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(
                water,
                mode="budgeted-release-v1",
                predecessor_identity_bound=True,
                predecessor_mode="complete-uncompressed-visual-evaluation-v1",
            )
            usage = type("Usage", (), {"free": 1})()

            with mock.patch.object(
                v3_package_merger.shutil,
                "disk_usage",
                return_value=usage,
            ), self.assertRaisesRegex(
                v3_package_merger.V3PackageMergeError,
                "visual.*size policy|size-policy transition",
            ):
                v3_package_merger.merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(water,),
                    supplement_build_receipts=(receipt_path,),
                    output_directory=root / "out",
                    package_id="must-fail",
                )

    def test_authority_receipt_matches_supplement_by_package_id_not_position(self) -> None:
        from tools.experiment8.v3_package_merger import merge_v3_packages

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary = root / "primary"
            alpha, water, output = root / "alpha", root / "water", root / "out"
            _write_package(primary, "primary", {tile: payload})
            _write_package(alpha, "alpha", {tile: payload})
            _write_package(water, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(water)

            result = merge_v3_packages(
                primary_directory=primary,
                supplement_directories=(water, alpha),
                supplement_build_receipts=(receipt_path,),
                output_directory=output,
                package_id="dynamic-authority-match",
            )

            self.assertEqual(
                ["water"],
                [item["packageId"] for item in result.receipt["authorityReceipts"]],
            )

    def test_authority_receipt_runtime_or_recovery_tamper_is_rejected(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        for mutation, message in (
            ("runtime", "output files"),
            ("policy-code", "size-policy module.*code"),
            ("recovery-policy", "recovery.*size policy"),
            ("recovery-complete", "recovery transaction"),
        ):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                primary, water = root / "primary", root / "water"
                _write_package(primary, "primary", {tile: payload})
                _write_package(water, "water", {tile: payload})
                receipt_path = _write_recovered_water_build_receipt(water)
                receipt = json.loads(receipt_path.read_text("utf-8"))
                if mutation == "runtime":
                    receipt["outputFiles"][1]["sha256"] = "f" * 64
                elif mutation == "policy-code":
                    receipt["build"]["code"]["referenceSizePolicy"][
                        "sha256"
                    ] = "9" * 64
                elif mutation == "recovery-policy":
                    receipt["build"]["recovery"]["sizePolicyTransition"][
                        "intended"
                    ]["module"]["sha256"] = "9" * 64
                else:
                    receipt["build"]["recovery"]["transactionComplete"] = None
                receipt_path.write_bytes(_canonical_json_bytes(receipt))

                with self.assertRaisesRegex(V3PackageMergeError, message):
                    merge_v3_packages(
                        primary_directory=primary,
                        supplement_directories=(water,),
                        supplement_build_receipts=(receipt_path,),
                        output_directory=root / "out",
                        package_id="must-fail",
                    )

    def test_unmatched_or_duplicate_authority_receipt_is_rejected(self) -> None:
        from tools.experiment8.v3_package_merger import (
            V3PackageMergeError,
            merge_v3_packages,
        )

        tile = TileKey(1, 0, 0)
        payload = encode_tile_payload(
            tile,
            [RendererTileRecord(_line_renderer_record(tile), None)],
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            primary, water = root / "primary", root / "water"
            _write_package(primary, "primary", {tile: payload})
            _write_package(water, "water", {tile: payload})
            receipt_path = _write_recovered_water_build_receipt(water)

            with self.assertRaisesRegex(V3PackageMergeError, "duplicate"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(water,),
                    supplement_build_receipts=(receipt_path, receipt_path),
                    output_directory=root / "duplicate-out",
                    package_id="must-fail",
                )

            receipt = json.loads(receipt_path.read_text("utf-8"))
            receipt["packageId"] = "not-a-supplement"
            receipt_path.write_bytes(_canonical_json_bytes(receipt))
            with self.assertRaisesRegex(V3PackageMergeError, "does not match"):
                merge_v3_packages(
                    primary_directory=primary,
                    supplement_directories=(water,),
                    supplement_build_receipts=(receipt_path,),
                    output_directory=root / "unmatched-out",
                    package_id="must-fail",
                )


if __name__ == "__main__":
    unittest.main()
