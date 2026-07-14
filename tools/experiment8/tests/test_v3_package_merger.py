from __future__ import annotations

import hashlib
import io
import json
import shutil
import struct
import tempfile
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
            "code": code,
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
