from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import ExitStack, contextmanager
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch

import tools.experiment8.osm_hydro_source as osm_hydro_source
import tools.experiment8.osm_pilot_bundle as osm_pilot_bundle
import tools.experiment8.osm_pilot_provenance as provenance
from tools.experiment8.osm_closure_probe import ProcessEvidence

from tools.experiment8.osm_hydro_source import (
    MARYLAND_REGIONAL_PROFILE,
    MARYLAND_SOURCE_BYTES,
    MARYLAND_SOURCE_MD5,
    MARYLAND_SOURCE_SHA256,
    MARYLAND_SOURCE_URL,
)
from tools.experiment8.osm_pilot_provenance import canonical_json_bytes


class OsmPilotProvenanceTests(unittest.TestCase):
    @staticmethod
    def _root_candidate_xml() -> bytes:
        return b"""<osm version=\"0.6\" generator=\"fixture\">
  <way id=\"10\" version=\"2\" timestamp=\"2026-07-10T19:00:00Z\">
    <nd ref=\"1\"/><nd ref=\"2\"/>
    <tag k=\"name\" v=\"Alpha Run\"/><tag k=\"waterway\" v=\"river\"/>
  </way>
  <way id=\"11\" version=\"1\" timestamp=\"2026-07-10T19:01:00Z\">
    <nd ref=\"3\"/><nd ref=\"3\"/>
    <tag k=\"name\" v=\"Closed Creek\"/><tag k=\"waterway\" v=\"stream\"/>
  </way>
  <relation id=\"20\" version=\"3\" timestamp=\"2026-07-10T19:02:00Z\">
    <member type=\"way\" ref=\"10\" role=\"main_stream\"/>
    <tag k=\"name\" v=\"Alpha River\"/><tag k=\"type\" v=\"waterway\"/>
  </relation>
  <relation id=\"21\" version=\"1\" timestamp=\"2026-07-10T19:03:00Z\">
    <tag k=\"type\" v=\"waterway\"/>
  </relation>
</osm>
"""

    @staticmethod
    def _fileinfo_capture(size: int = MARYLAND_SOURCE_BYTES) -> bytes:
        return f"""File:
  Name: /mnt/e/FlightAlert-exp8-work/osm-hydro-pilot/source/maryland-260710.osm.pbf
  Format: PBF
  Compression: none
  Size: {size}
Header:
  Bounding boxes:
    (-79.48857,37.88396,-74.954079,40.17054)
  With history: no
  Options:
    generator=osmium/1.16.0
    osmosis_replication_base_url=https://download.geofabrik.de/north-america/us/maryland-updates
    osmosis_replication_sequence_number=4845
    osmosis_replication_timestamp=2026-07-10T20:21:01Z
    pbf_dense_nodes=true
    pbf_optional_feature_0=Sort.Type_then_ID
    sorting=Type_then_ID
    timestamp=2026-07-10T20:21:01Z
Data:
  Bounding box: (-80.1318095,37.3264889,-74.9849355,41.1476119)
  Timestamps:
    First: 2005-11-30T04:26:15Z
    Last: 2026-07-10T19:30:40Z
  Objects ordered (by type and id): yes
  Multiple versions of same object: no
  CRC32: c452ca02
  Number of changesets: 0
  Number of nodes: 27286527
  Number of ways: 3178490
  Number of relations: 27883
  Smallest changeset ID: 0
  Smallest node ID: 272594
  Smallest way ID: 4268721
  Smallest relation ID: 50372
  Largest changeset ID: 0
  Largest node ID: 14003334163
  Largest way ID: 1537294226
  Largest relation ID: 21084788
  All objects have following metadata attributes: version+timestamp
  Some objects have following metadata attributes: version+timestamp
  Number of buffers: 40176 (avg 758 objects per buffer)
  Sum of buffer sizes: 2548206032 (2.548 GB)
  Sum of buffer capacities: 2635857920 (2.635 GB, 97% full)
""".encode("ascii")

    @staticmethod
    def _rewrite_bundle_payloads(
        directory: Path, payloads: dict[str, bytes]
    ) -> None:
        root_path = directory / "bundle-root.json"
        root = json.loads(root_path.read_bytes())
        records = {entry["logicalName"]: entry for entry in root["files"]}
        for logical_name, payload in payloads.items():
            (directory / logical_name).write_bytes(payload)
            records[logical_name]["bytes"] = len(payload)
            records[logical_name]["sha256"] = hashlib.sha256(payload).hexdigest()
        root_path.write_bytes(canonical_json_bytes(root))

    def test_provenance_modules_are_focused_acyclic_and_below_hard_size_ceiling(
        self,
    ) -> None:
        module_names = (
            "osm_pilot_common.py",
            "osm_pilot_runtime.py",
            "osm_pilot_bundle.py",
            "osm_pilot_provenance.py",
        )
        module_root = Path(provenance.__file__).resolve(strict=True).parent
        for name in module_names:
            with self.subTest(module=name):
                source = Path(module_root, name).read_text(encoding="utf-8")
                self.assertLessEqual(len(source.splitlines()), 3_000)
                if name != "osm_pilot_provenance.py":
                    self.assertNotIn("import osm_pilot_provenance", source)
                    self.assertNotIn("from tools.experiment8.osm_pilot_provenance", source)

    @staticmethod
    def _write_independent_selection_fixture(
        root: Path,
        broad_xml: bytes,
        root_ids: bytes,
        selection_material: bytes,
    ) -> tuple[Path, Path, Path, Path, bytes]:
        broad_path = root / "waterway-broad-v1.osm"
        root_ids_path = root / "broad-root-ids-v1.txt"
        material_path = root / "broad-selection-material-v1.json"
        report_path = root / "broad-independent-verification-v1.json"
        material_document = json.loads(selection_material)
        report = canonical_json_bytes(
            {
                "broadInput": {
                    "bytes": len(broad_xml),
                    "sha256": hashlib.sha256(broad_xml).hexdigest(),
                },
                "candidateCounts": material_document["candidateCounts"],
                "candidateEnvelope": {
                    "relationTag": ["type", "waterway"],
                    "wayTagKey": "waterway",
                },
                "profile": (
                    "flight-alert-exp8-osm-independent-selection-generic-v1"
                ),
                "rejectedRelationCounts": material_document[
                    "rejectedRelationCounts"
                ],
                "rejectedWayCounts": material_document["rejectedWayCounts"],
                "rootIds": {
                    "bytes": len(root_ids),
                    "sha256": hashlib.sha256(root_ids).hexdigest(),
                },
                "schema": (
                    "flight-alert-exp8-osm-independent-selection-"
                    "verification-report-v1"
                ),
                "selectedCounts": material_document["selectedCounts"],
                "selectedRootIds": root_ids.decode("ascii").splitlines(),
                "selectionMaterial": {
                    "bytes": len(selection_material),
                    "sha256": hashlib.sha256(selection_material).hexdigest(),
                },
                "verified": True,
            }
        )
        broad_path.write_bytes(broad_xml)
        root_ids_path.write_bytes(root_ids)
        material_path.write_bytes(selection_material)
        report_path.write_bytes(report)
        return broad_path, root_ids_path, material_path, report_path, report

    def test_clean_start_selection_locks_and_independent_broad_evidence_are_exact(
        self,
    ) -> None:
        expected_locks = {
            "MARYLAND_ROOT_IDS_BYTES": 88_831,
            "MARYLAND_ROOT_WAY_COUNT": 7_944,
            "MARYLAND_ROOT_RELATION_COUNT": 102,
            "MARYLAND_ROOT_IDS_SHA256": (
                "3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7"
            ),
            "MARYLAND_SELECTION_MATERIAL_BYTES": 9_135_827,
            "MARYLAND_SELECTION_MATERIAL_SHA256": (
                "d49e184605d9123d75970408d1a675288df681f8ed2d0b37e3c3d74bf0afd940"
            ),
            "MARYLAND_BROAD_XML_SHA256": (
                "f47eaeb4140d18674b850baab9820cf72f7f7d15c2272deb5511ea10aac91473"
            ),
            "MARYLAND_BROAD_ROOT_IDS_SHA256": (
                "3de3a9d901ddb76f8a594dcd1d37dc8da12c11e00196f5e4b830fa184b3275e7"
            ),
            "MARYLAND_BROAD_SELECTION_MATERIAL_SHA256": (
                "fb9c046a6c65a9fd342a704117ae5c32d6b360b2cd1b272f31c8d68b34e87f74"
            ),
            "MARYLAND_INDEPENDENT_REPORT_SHA256": (
                "18d43ab72de95e9f0dc22cf1bcdba60b7396045342fa7875171918789ffbfe95"
            ),
        }
        for name, expected in expected_locks.items():
            with self.subTest(lock=name):
                self.assertEqual(getattr(provenance, name), expected)

        broad_xml = self._root_candidate_xml()
        root_ids, material = osm_hydro_source.encode_selection_material(broad_xml)
        with tempfile.TemporaryDirectory() as temporary:
            paths = self._write_independent_selection_fixture(
                Path(temporary), broad_xml, root_ids, material
            )
            patches = (
                patch.object(provenance, "MARYLAND_BROAD_XML_BYTES", len(broad_xml)),
                patch.object(
                    provenance,
                    "MARYLAND_BROAD_XML_SHA256",
                    hashlib.sha256(broad_xml).hexdigest(),
                ),
                patch.object(
                    provenance, "MARYLAND_BROAD_ROOT_IDS_BYTES", len(root_ids)
                ),
                patch.object(
                    provenance,
                    "MARYLAND_BROAD_ROOT_IDS_SHA256",
                    hashlib.sha256(root_ids).hexdigest(),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_BROAD_SELECTION_MATERIAL_BYTES",
                    len(material),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_BROAD_SELECTION_MATERIAL_SHA256",
                    hashlib.sha256(material).hexdigest(),
                ),
                patch.object(
                    provenance, "MARYLAND_INDEPENDENT_REPORT_BYTES", len(paths[4])
                ),
                patch.object(
                    provenance,
                    "MARYLAND_INDEPENDENT_REPORT_SHA256",
                    hashlib.sha256(paths[4]).hexdigest(),
                ),
            )
            with ExitStack() as stack:
                for current in patches:
                    stack.enter_context(current)
                evidence = provenance.verify_independent_selection_evidence(
                    *paths[:4]
                )

            self.assertEqual(evidence.selected_way_count, 1)
            self.assertEqual(evidence.selected_relation_count, 1)
            self.assertEqual(evidence.root_ids_sha256, hashlib.sha256(root_ids).hexdigest())
            self.assertEqual(
                evidence.selection_material_sha256,
                hashlib.sha256(material).hexdigest(),
            )

    def test_local_runtime_factory_verifies_the_imported_selector_and_current_python(self) -> None:
        factory = getattr(provenance, "verify_local_code_runtime", None)
        self.assertIsNotNone(factory, "verified local runtime factory is missing")

        evidence = factory()

        selector_path = Path(osm_hydro_source.__file__).resolve(strict=True)
        python_path = Path(sys.executable).resolve(strict=True)
        self.assertEqual(
            evidence.selector_sha256,
            hashlib.sha256(selector_path.read_bytes()).hexdigest(),
        )
        self.assertEqual(
            evidence.python_executable_sha256,
            hashlib.sha256(python_path.read_bytes()).hexdigest(),
        )
        self.assertEqual(evidence.python_version, platform.python_version())
        self.assertEqual(evidence.python_platform, platform.platform())
        self.assertEqual(
            evidence.policy_sha256,
            hashlib.sha256(osm_hydro_source.canonical_policy_bytes()).hexdigest(),
        )
        self.assertIs(evidence.selector_callable, osm_hydro_source.encode_selection_material)
        self.assertEqual(
            evidence.selector_callable_module,
            "tools.experiment8.osm_hydro_source",
        )
        self.assertEqual(
            evidence.selector_callable_qualname,
            "encode_selection_material",
        )
        self.assertEqual(evidence.selector_callable_source_path, selector_path)
        self.assertEqual(
            evidence.selector_callable_code_sha256,
            provenance._CAPTURED_SELECTION_CODE_SHA256,
        )
        self.assertNotIn("selector", factory.__code__.co_varnames[: factory.__code__.co_argcount])

        original = osm_hydro_source.encode_selection_material
        calls: list[bytes] = []

        def byte_identical_wrapper(candidate_xml: bytes) -> tuple[bytes, bytes]:
            calls.append(candidate_xml)
            return original(candidate_xml)

        with patch.object(
            osm_hydro_source,
            "encode_selection_material",
            new=byte_identical_wrapper,
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "callable identity",
            ):
                factory()
        self.assertEqual(calls, [])

    def test_local_runtime_rejects_retained_selector_bytes_for_different_code(self) -> None:
        original_verify = provenance._verify_stable_file

        def replace_retained_source(path, **kwargs):
            verified = original_verify(path, **kwargs)
            if kwargs["logical_name"] != "imported osm_hydro_source module":
                return verified
            self.assertIsNotNone(verified.content)
            altered = verified.content.replace(
                b"generic root IDs",
                b"forged! root IDs",
                1,
            )
            self.assertNotEqual(altered, verified.content)
            self.assertEqual(len(altered), verified.bytes)
            return provenance._verified_instance(
                type(verified),
                path=verified.path,
                logical_name=verified.logical_name,
                identity=verified.identity,
                bytes=verified.bytes,
                sha256=hashlib.sha256(altered).hexdigest(),
                md5=verified.md5,
                content=altered,
            )

        with patch.object(
            provenance,
            "_verify_stable_file",
            side_effect=replace_retained_source,
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "compiled selector.*captured callable",
            ):
                provenance.verify_local_code_runtime()

    def test_local_runtime_rejects_selector_replacement_after_retained_read(self) -> None:
        original_verify = provenance._verify_stable_file
        selector_reads = 0

        def change_after_first_read(path, **kwargs):
            nonlocal selector_reads
            if kwargs["logical_name"].startswith("imported osm_hydro_source module"):
                selector_reads += 1
                if selector_reads == 2:
                    raise provenance.ProvenanceVerificationError(
                        "simulated selector replacement after retained read"
                    )
            return original_verify(path, **kwargs)

        with patch.object(
            provenance,
            "_verify_stable_file",
            side_effect=change_after_first_read,
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "changed during selector source attestation",
            ):
                provenance.verify_local_code_runtime()
        self.assertEqual(selector_reads, 2)

    def test_local_runtime_rejects_python_executable_post_attestation_drift(
        self,
    ) -> None:
        original_verify = provenance._verify_stable_file

        def reject_python_replay(path, **kwargs):
            if kwargs["logical_name"] == "current Python executable post-attestation":
                raise provenance.ProvenanceVerificationError(
                    "simulated Python executable replacement"
                )
            return original_verify(path, **kwargs)

        with patch.object(
            provenance,
            "_verify_stable_file",
            side_effect=reject_python_replay,
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "Python executable changed during local attestation",
            ):
                provenance.verify_local_code_runtime()

    def test_selector_snapshot_and_wrapper_globals_must_match_every_source_lock(
        self,
    ) -> None:
        mutations = (
            (provenance, "MARYLAND_SOURCE_URL", "https://example.invalid/stale.pbf"),
            (osm_hydro_source, "MARYLAND_SOURCE_BYTES", MARYLAND_SOURCE_BYTES + 1),
            (osm_hydro_source, "MARYLAND_SOURCE_MD5", "0" * 32),
            (osm_hydro_source, "MARYLAND_SOURCE_SHA256", "0" * 64),
            (osm_hydro_source, "MARYLAND_REGIONAL_PROFILE", "contradictory-profile"),
            (osm_hydro_source, "POLICY_SHA256", "f" * 64),
        )
        for target, name, value in mutations:
            with self.subTest(target=target.__name__, global_name=name), patch.object(
                target, name, value
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "snapshot.*global|source lock|policy SHA-256",
                ):
                    provenance.verify_local_code_runtime()

    def test_selector_code_identity_is_checkout_path_independent(self) -> None:
        source = Path(osm_hydro_source.__file__).resolve(strict=True).read_bytes()
        first_module, first_selector = provenance._compiled_selector_snapshot(
            source,
            Path(r"C:\first-checkout\tools\experiment8\osm_hydro_source.py"),
        )
        second_module, second_selector = provenance._compiled_selector_snapshot(
            source,
            Path(r"E:\second-checkout\tools\experiment8\osm_hydro_source.py"),
        )

        self.assertEqual(
            provenance._code_structure_sha256(first_selector),
            provenance._code_structure_sha256(second_selector),
        )
        self.assertEqual(first_selector.co_filename, provenance._SELECTOR_LOGICAL_FILENAME)
        self.assertEqual(second_selector.co_filename, provenance._SELECTOR_LOGICAL_FILENAME)
        self.assertEqual(first_module.co_filename, provenance._SELECTOR_LOGICAL_FILENAME)
        self.assertEqual(second_module.co_filename, provenance._SELECTOR_LOGICAL_FILENAME)

    def test_selector_executes_helpers_from_the_exact_verified_source_snapshot(self) -> None:
        original_verify = provenance._verify_stable_file
        altered_selector = None

        def stable_helper_only_change(path, **kwargs):
            nonlocal altered_selector
            if not kwargs["logical_name"].startswith(
                "imported osm_hydro_source module"
            ):
                return original_verify(path, **kwargs)
            if altered_selector is None:
                verified = original_verify(
                    path,
                    logical_name="imported osm_hydro_source module",
                    capture_content=True,
                    maximum_capture_bytes=4 * 1024 * 1024,
                )
                self.assertIsNotNone(verified.content)
                altered = verified.content.replace(
                    b'else "closed"',
                    b'else "forged"',
                    1,
                )
                self.assertNotEqual(altered, verified.content)
                self.assertEqual(len(altered), verified.bytes)
                altered_selector = provenance._verified_instance(
                    type(verified),
                    path=verified.path,
                    logical_name=verified.logical_name,
                    identity=verified.identity,
                    bytes=verified.bytes,
                    sha256=hashlib.sha256(altered).hexdigest(),
                    md5=verified.md5,
                    content=altered,
                )
            return altered_selector

        candidate_xml = self._root_candidate_xml()
        with tempfile.TemporaryDirectory() as temporary:
            components = self._verified_components(
                Path(temporary),
                candidate_xml_bytes=candidate_xml,
            )
            with patch.object(
                provenance,
                "_verify_stable_file",
                side_effect=stable_helper_only_change,
            ):
                altered_local = provenance.verify_local_code_runtime()

            self.assertNotEqual(
                altered_local.selector_sha256,
                components[0].selector_sha256,
            )
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "selection material does not exactly reconcile",
            ):
                provenance.assemble_verified_pilot_inputs(
                    altered_local,
                    *components[1:],
                )

    def test_local_runtime_factory_fails_on_any_pinned_python_mismatch(self) -> None:
        factory = getattr(provenance, "verify_local_code_runtime", None)
        self.assertIsNotNone(factory, "verified local runtime factory is missing")
        error_type = getattr(provenance, "ProvenanceVerificationError", ValueError)

        with patch.object(provenance, "PINNED_PYTHON_VERSION", "0.0.0"):
            with self.assertRaisesRegex(error_type, "Python version"):
                factory()

    def test_python_runtime_identity_binds_dll_dependencies_flags_and_cache_tag(
        self,
    ) -> None:
        evidence = provenance.verify_local_code_runtime()
        logical_names = tuple(
            item.logical_name for item in evidence.python_dependencies
        )

        self.assertEqual(evidence.python_cache_tag, "cpython-311")
        self.assertEqual(evidence.python_cache_tag, sys.implementation.cache_tag)
        self.assertEqual(
            dict(evidence.python_flags),
            {
                "bytes_warning": 0,
                "debug": 0,
                "dev_mode": False,
                "dont_write_bytecode": 0,
                "hash_randomization": 1,
                "ignore_environment": 0,
                "inspect": 0,
                "int_max_str_digits": -1,
                "interactive": 0,
                "isolated": 0,
                "no_site": 0,
                "no_user_site": 0,
                "optimize": 0,
                "quiet": 0,
                "safe_path": False,
                "utf8_mode": 0,
                "verbose": 0,
                "warn_default_encoding": 0,
            },
        )
        for required in (
            "runtime/python311.dll",
            "runtime/python3.dll",
            "runtime/vcruntime140.dll",
            "runtime/vcruntime140_1.dll",
            "native/libcrypto-1_1.dll",
            "stdlib/hashlib.py",
            "stdlib/calendar.py",
            "stdlib/datetime.py",
            "stdlib/json/decoder.py",
            "stdlib/re/_parser.py",
            "stdlib/decimal.py",
            "stdlib/xml/etree/ElementTree.py",
            "stdlib/email/utils.py",
            "extensions/_elementtree.pyd",
            "extensions/_decimal.pyd",
            "extensions/_hashlib.pyd",
            "extensions/pyexpat.pyd",
        ):
            self.assertIn(required, logical_names)
        self.assertEqual(logical_names, tuple(sorted(set(logical_names))))

    def test_python_runtime_dependency_verifier_rejects_mutated_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            dependency = Path(temporary, "parser.pyd")
            dependency.write_bytes(b"exact parser dependency")
            expected = hashlib.sha256(dependency.read_bytes()).hexdigest()
            specs = (("extensions/parser.pyd", dependency, expected),)

            verified = provenance._verify_python_runtime_dependencies(specs)
            self.assertEqual(verified[0].logical_name, "extensions/parser.pyd")
            dependency.write_bytes(b"mutated parser dependency")
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "SHA-256 mismatch",
            ):
                provenance._verify_python_runtime_dependencies(specs)

    def test_fileinfo_fixture_is_the_exact_real_osmium_1_11_1_transcript(self) -> None:
        capture = self._fileinfo_capture()

        self.assertEqual(len(capture), 1_478)
        self.assertEqual(
            hashlib.sha256(capture).hexdigest(),
            "51736a1fff93bd7703bee036ef260b198e2192b25a2020c9e6ef8b36a8dd0b9f",
        )
        self.assertEqual(provenance.MARYLAND_FILEINFO_BYTES, len(capture))
        self.assertEqual(
            provenance.MARYLAND_FILEINFO_SHA256,
            hashlib.sha256(capture).hexdigest(),
        )

    def test_raw_provider_capture_goldens_are_exact(self) -> None:
        head = (
            b"HTTP/1.1 200 OK\r\n"
            b"Date: Sat, 11 Jul 2026 00:55:30 GMT\r\n"
            b"Server: Apache\r\n"
            b"Last-Modified: Sat, 11 Jul 2026 00:06:58 GMT\r\n"
            b'ETag: "cb11a6c-6564aa2c1c7b3"\r\n'
            b"Accept-Ranges: bytes\r\n"
            b"Content-Length: 212933228\r\n"
            b"Content-Type: application/octet-stream\r\n"
            b"Age: 386\r\n"
            b"Cache-Status: download-proxy12;hit;detail=match\r\n"
            b"Via: 1.1 download-proxy12 (squid/6.14)\r\n"
            b"Connection: keep-alive\r\n\r\n"
        )
        sidecar = (
            b"2642fa017680941a2fab4f96c23d9c03  maryland-260710.osm.pbf\n"
        )

        self.assertEqual(len(head), 361)
        self.assertEqual(
            hashlib.sha256(head).hexdigest(),
            "b336ed40610a903f51812db36bcfb9cdd709e4a71e236e497b50ed80fc1f149f",
        )
        self.assertEqual(len(sidecar), 58)
        self.assertEqual(
            hashlib.sha256(sidecar).hexdigest(),
            "9783ab9cf51d1b013b0bf7eeb2a7066f17050ba285f7bd0a1c3063933273e322",
        )
        self.assertEqual(getattr(provenance, "MARYLAND_HEAD_BYTES", None), len(head))
        self.assertEqual(
            getattr(provenance, "MARYLAND_HEAD_SHA256", None),
            hashlib.sha256(head).hexdigest(),
        )
        self.assertEqual(
            getattr(provenance, "MARYLAND_MD5_SIDECAR_BYTES", None), len(sidecar)
        )
        self.assertEqual(
            getattr(provenance, "MARYLAND_MD5_SIDECAR_SHA256", None),
            hashlib.sha256(sidecar).hexdigest(),
        )

    def test_source_candidates_and_provider_factories_verify_exact_live_files(self) -> None:
        source_factory = getattr(provenance, "verify_maryland_source_file", None)
        candidate_factory = getattr(provenance, "verify_candidate_files", None)
        provider_factory = getattr(provenance, "verify_provider_captures", None)
        self.assertIsNotNone(source_factory, "verified Maryland source factory is missing")
        self.assertIsNotNone(candidate_factory, "verified candidate factory is missing")
        self.assertIsNotNone(provider_factory, "verified provider capture factory is missing")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source_bytes = b"locked Maryland source bytes"
            candidate_pbf_bytes = b"candidate pbf"
            candidate_xml_bytes = b"<osm version=\"0.6\"/>\n"
            source_path = root / "maryland-260710.osm.pbf"
            candidate_pbf_path = root / "waterway-candidates-v2.osm.pbf"
            candidate_xml_path = root / "waterway-candidates-v2.osm"
            source_path.write_bytes(source_bytes)
            candidate_pbf_path.write_bytes(candidate_pbf_bytes)
            candidate_xml_path.write_bytes(candidate_xml_bytes)

            final_url = root / "provider-final-url.txt"
            head = root / "provider-head.txt"
            sidecar = root / "provider-md5-sidecar.txt"
            fileinfo = root / "source-fileinfo.txt"
            final_url.write_bytes((MARYLAND_SOURCE_URL + "\r\n").encode("ascii"))
            head.write_bytes(
                (
                    "HTTP/2 200\r\n"
                    "Date: Sat, 11 Jul 2026 00:55:30 GMT\r\n"
                    f"content-length: {len(source_bytes)}\r\n"
                    "content-type: application/octet-stream\r\n\r\n"
                ).encode("ascii")
            )
            sidecar.write_bytes(
                f"{hashlib.md5(source_bytes).hexdigest()}  maryland-260710.osm.pbf\n".encode(
                    "ascii"
                )
            )
            fileinfo.write_bytes(self._fileinfo_capture(len(source_bytes)))

            patches = (
                patch.object(provenance, "_SOURCE_EVIDENCE_BYTES", len(source_bytes)),
                patch.object(
                    provenance,
                    "_SOURCE_EVIDENCE_MD5",
                    hashlib.md5(source_bytes).hexdigest(),
                ),
                patch.object(
                    provenance,
                    "_SOURCE_EVIDENCE_SHA256",
                    hashlib.sha256(source_bytes).hexdigest(),
                ),
                patch.object(provenance, "MARYLAND_HEAD_BYTES", head.stat().st_size),
                patch.object(
                    provenance,
                    "MARYLAND_HEAD_SHA256",
                    hashlib.sha256(head.read_bytes()).hexdigest(),
                ),
                patch.object(
                    provenance, "MARYLAND_MD5_SIDECAR_BYTES", sidecar.stat().st_size
                ),
                patch.object(
                    provenance,
                    "MARYLAND_MD5_SIDECAR_SHA256",
                    hashlib.sha256(sidecar.read_bytes()).hexdigest(),
                ),
                patch.object(
                    provenance, "WATERWAY_CANDIDATES_PBF_BYTES", len(candidate_pbf_bytes)
                ),
                patch.object(
                    provenance,
                    "WATERWAY_CANDIDATES_PBF_SHA256",
                    hashlib.sha256(candidate_pbf_bytes).hexdigest(),
                ),
                patch.object(
                    provenance, "WATERWAY_CANDIDATES_XML_BYTES", len(candidate_xml_bytes)
                ),
                patch.object(
                    provenance,
                    "WATERWAY_CANDIDATES_XML_SHA256",
                    hashlib.sha256(candidate_xml_bytes).hexdigest(),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_FILEINFO_BYTES",
                    len(self._fileinfo_capture(len(source_bytes))),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_FILEINFO_SHA256",
                    hashlib.sha256(
                        self._fileinfo_capture(len(source_bytes))
                    ).hexdigest(),
                ),
            )
            with ExitStack() as stack:
                for current_patch in patches:
                    stack.enter_context(current_patch)
                source = source_factory(source_path)
                candidates = candidate_factory(candidate_pbf_path, candidate_xml_path)
                provider = provider_factory(
                    final_url,
                    head,
                    sidecar,
                    fileinfo,
                    source=source,
                )
                invalid_metadata = self._fileinfo_capture(len(source_bytes)).replace(
                    b"All objects have following metadata attributes: version+timestamp",
                    b"All objects have following metadata attributes: version+timestamp+changeset",
                )
                fileinfo.write_bytes(invalid_metadata)
                with patch.object(
                    provenance, "MARYLAND_FILEINFO_BYTES", len(invalid_metadata)
                ), patch.object(
                    provenance,
                    "MARYLAND_FILEINFO_SHA256",
                    hashlib.sha256(invalid_metadata).hexdigest(),
                ):
                    with self.assertRaisesRegex(
                        provenance.ProvenanceVerificationError, "metadata"
                    ):
                        provider_factory(
                            final_url,
                            head,
                            sidecar,
                            fileinfo,
                            source=source,
                        )
                invalid_bounds = self._fileinfo_capture(len(source_bytes)).replace(
                    b"Bounding boxes:", b"Unbound header coordinates:", 1
                )
                fileinfo.write_bytes(invalid_bounds)
                with patch.object(
                    provenance, "MARYLAND_FILEINFO_BYTES", len(invalid_bounds)
                ), patch.object(
                    provenance,
                    "MARYLAND_FILEINFO_SHA256",
                    hashlib.sha256(invalid_bounds).hexdigest(),
                ):
                    with self.assertRaisesRegex(
                        provenance.ProvenanceVerificationError, "header bounds"
                    ):
                        provider_factory(
                            final_url,
                            head,
                            sidecar,
                            fileinfo,
                            source=source,
                        )

        self.assertEqual(source.sha256, hashlib.sha256(source_bytes).hexdigest())
        self.assertEqual(candidates.pbf_sha256, hashlib.sha256(candidate_pbf_bytes).hexdigest())
        self.assertEqual(candidates.xml_sha256, hashlib.sha256(candidate_xml_bytes).hexdigest())
        self.assertEqual(provider.final_url, MARYLAND_SOURCE_URL)
        self.assertEqual(provider.final_url_file.bytes, 72)
        self.assertEqual(
            provider.final_url_file.sha256,
            "91fd488b928115e7b3779b49b1833cfc0f9776fbc8f22597b42039e1c62b839f",
        )
        self.assertEqual(provider.content_length, len(source_bytes))
        self.assertEqual(provider.md5, hashlib.md5(source_bytes).hexdigest())

    def test_provider_factory_rejects_a_sidecar_not_bound_to_the_verified_source(self) -> None:
        source_factory = getattr(provenance, "verify_maryland_source_file", None)
        provider_factory = getattr(provenance, "verify_provider_captures", None)
        self.assertIsNotNone(source_factory, "verified Maryland source factory is missing")
        self.assertIsNotNone(provider_factory, "verified provider capture factory is missing")
        error_type = getattr(provenance, "ProvenanceVerificationError", ValueError)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = b"source"
            source_path = root / "source.pbf"
            source_path.write_bytes(payload)
            final_url = root / "url.txt"
            head = root / "head.txt"
            sidecar = root / "md5.txt"
            fileinfo = root / "fileinfo.txt"
            final_url.write_bytes((MARYLAND_SOURCE_URL + "\r\n").encode("ascii"))
            head.write_bytes(
                (
                    "HTTP/1.1 200 OK\r\n"
                    "Date: Sat, 11 Jul 2026 00:55:30 GMT\r\n"
                    f"Content-Length: {len(payload)}\r\n\r\n"
                ).encode(
                    "ascii"
                )
            )
            sidecar.write_bytes(b"00000000000000000000000000000000  maryland-260710.osm.pbf\n")
            fileinfo_bytes = self._fileinfo_capture(len(payload))
            fileinfo.write_bytes(fileinfo_bytes)
            with patch.object(provenance, "_SOURCE_EVIDENCE_BYTES", len(payload)), patch.object(
                provenance, "_SOURCE_EVIDENCE_MD5", hashlib.md5(payload).hexdigest()
            ), patch.object(
                provenance, "_SOURCE_EVIDENCE_SHA256", hashlib.sha256(payload).hexdigest()
            ), patch.object(
                provenance, "MARYLAND_HEAD_BYTES", head.stat().st_size
            ), patch.object(
                provenance,
                "MARYLAND_HEAD_SHA256",
                hashlib.sha256(head.read_bytes()).hexdigest(),
            ), patch.object(
                provenance, "MARYLAND_MD5_SIDECAR_BYTES", sidecar.stat().st_size
            ), patch.object(
                provenance,
                "MARYLAND_MD5_SIDECAR_SHA256",
                hashlib.sha256(sidecar.read_bytes()).hexdigest(),
            ), patch.object(
                provenance, "MARYLAND_FILEINFO_BYTES", len(fileinfo_bytes)
            ), patch.object(
                provenance,
                "MARYLAND_FILEINFO_SHA256",
                hashlib.sha256(fileinfo_bytes).hexdigest(),
            ):
                source = source_factory(source_path)
                with self.assertRaisesRegex(error_type, "MD5 sidecar"):
                    provider_factory(final_url, head, sidecar, fileinfo, source=source)

    def test_provider_factory_rejects_noncanonical_final_url_terminators(self) -> None:
        variants = (
            (MARYLAND_SOURCE_URL + "\n").encode("ascii"),
            (MARYLAND_SOURCE_URL + "\r\n\r\n").encode("ascii"),
            (MARYLAND_SOURCE_URL + "\r\nextra\r\n").encode("ascii"),
        )
        with tempfile.TemporaryDirectory() as temporary:
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(Path(temporary))
            )
            final_url_path = inputs.provider.final_url_file.path
            for variant in variants:
                with self.subTest(variant=variant):
                    final_url_path.write_bytes(variant)
                    with self._current_fixture_graph(inputs):
                        with self.assertRaisesRegex(
                            provenance.ProvenanceVerificationError,
                            "provider final URL",
                        ):
                            provenance.verify_provider_captures(
                                final_url_path,
                                inputs.provider.head_file.path,
                                inputs.provider.md5_sidecar_file.path,
                                inputs.provider.fileinfo_file.path,
                                source=inputs.source,
                            )

    def test_provider_response_date_is_exact_canonical_utc_and_not_pbf_time(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(Path(temporary))
            )
            self.assertEqual(
                inputs.provider.provider_response_date_utc,
                "2026-07-11T00:55:30Z",
            )
            self.assertEqual(
                inputs.provider.pbf_header_timestamp_utc,
                "2026-07-10T20:21:01Z",
            )
            self.assertNotEqual(
                inputs.provider.provider_response_date_utc,
                inputs.provider.pbf_header_timestamp_utc,
            )
            self.assertIsNone(inputs.provider.local_download_utc)
            self.assertEqual(inputs.provider.local_download_status, "unavailable")
            with self._current_fixture_graph(inputs):
                document = provenance.source_identity_document(inputs)
            self.assertEqual(
                document["providerEvidence"]["providerResponseDateUtc"],
                "2026-07-11T00:55:30Z",
            )
            self.assertEqual(
                document["header"]["pbfHeaderTimestampUtc"],
                "2026-07-10T20:21:01Z",
            )
            self.assertEqual(
                document["providerEvidence"]["localDownload"],
                {
                    "reason": (
                        "no contemporaneous local-download UTC capture was "
                        "retained; filesystem timestamps and provider/PBF times "
                        "are not substitutes"
                    ),
                    "status": "unavailable",
                    "utc": None,
                },
            )
            self.assertNotIn(
                "verified",
                document["providerEvidence"]["localDownload"],
            )

    def test_provider_factory_rejects_missing_duplicate_or_noncanonical_head_date(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(Path(temporary))
            )
            head_path = inputs.provider.head_file.path
            base = inputs.provider.head_file.content
            self.assertIsNotNone(base)
            assert base is not None
            date_line = b"Date: Sat, 11 Jul 2026 00:55:30 GMT\r\n"
            variants = {
                "missing": base.replace(date_line, b""),
                "duplicate": base.replace(date_line, date_line + date_line),
                "noncanonical": base.replace(
                    date_line,
                    b"Date: Sat, 11 Jul 2026 00:55:30 +0000\r\n",
                ),
                "wrong accepted instant": base.replace(
                    date_line,
                    b"Date: Sat, 11 Jul 2026 00:55:31 GMT\r\n",
                ),
            }
            for label, raw in variants.items():
                with self.subTest(label=label):
                    head_path.write_bytes(raw)
                    with self._current_fixture_graph(inputs), patch.object(
                        provenance, "MARYLAND_HEAD_BYTES", len(raw)
                    ), patch.object(
                        provenance,
                        "MARYLAND_HEAD_SHA256",
                        hashlib.sha256(raw).hexdigest(),
                    ):
                        with self.assertRaisesRegex(
                            provenance.ProvenanceVerificationError,
                            "provider HEAD Date",
                        ):
                            provenance.verify_provider_captures(
                                inputs.provider.final_url_file.path,
                                head_path,
                                inputs.provider.md5_sidecar_file.path,
                                inputs.provider.fileinfo_file.path,
                                source=inputs.source,
                            )

    @staticmethod
    def _runtime_runner(
        *,
        ldd_address: str,
        drift_post_hash: bool = False,
        drift_dependency_hash: bool = False,
    ):
        pinned_hash_calls = 0
        dependency_hash_calls = 0

        def result(argv: tuple[str, ...], stdout: bytes, stderr: bytes = b"", returncode: int = 0) -> ProcessEvidence:
            return ProcessEvidence(argv=argv, returncode=returncode, stdout=stdout, stderr=stderr)

        def run(argv: tuple[str, ...], **_: object) -> ProcessEvidence:
            nonlocal dependency_hash_calls, pinned_hash_calls
            if argv == (provenance.WSL_EXECUTABLE, "--list", "--verbose"):
                return result(
                    argv,
                    "  NAME      STATE           VERSION\r\n"
                    "* Ubuntu    Running         1\r\n".encode("utf-16le"),
                )
            if argv[-2:] == ("/usr/bin/lsb_release", "-ds"):
                return result(argv, b"Ubuntu 20.04.3 LTS\n")
            if argv[-2:] == ("/usr/bin/uname", "-srvmo"):
                return result(argv, b"Linux 5.15.0-test #1 SMP x86_64 GNU/Linux\n")
            if argv[-1] == "--version":
                return result(
                    argv,
                    b"osmium version 1.11.1\nlibosmium version 2.15.4\n"
                    b"Copyright fixture\n",
                )
            if argv[-2] == "/usr/bin/ldd":
                return result(
                    argv,
                    (
                        f"\tlinux-vdso.so.1 ({ldd_address})\n"
                        "\tlibboost_program_options.so.1.71.0 => "
                        f"{provenance.BOOST_LIBRARY_PATH} "
                        f"({ldd_address})\n"
                        f"\tlibz.so.1 => /lib/libz.so.1 ({ldd_address})\n"
                        f"\t/lib64/ld-linux-x86-64.so.2 ({ldd_address})\n"
                    ).encode("ascii"),
                )
            if "/usr/bin/sha256sum" in argv:
                paths = argv[argv.index("--binary") + 1 :]
                if paths == (
                    provenance.OSMIUM_BINARY_PATH,
                    provenance.BOOST_LIBRARY_PATH,
                    provenance.OSMIUM_DEB_PATH,
                    provenance.BOOST_DEB_PATH,
                ):
                    pinned_hash_calls += 1
                    hashes = [
                        provenance.OSMIUM_BINARY_SHA256,
                        provenance.BOOST_LIBRARY_SHA256,
                        provenance.OSMIUM_DEB_SHA256,
                        provenance.BOOST_DEB_SHA256,
                    ]
                    if drift_post_hash and pinned_hash_calls == 2:
                        hashes[0] = "0" * 64
                else:
                    dependency_hash_calls += 1
                    hashes = [
                        provenance.BOOST_LIBRARY_SHA256
                        if path == provenance.BOOST_LIBRARY_PATH
                        else hashlib.sha256(path.encode("utf-8")).hexdigest()
                        for path in paths
                    ]
                    if drift_dependency_hash and dependency_hash_calls == 2:
                        hashes[0] = "0" * 64
                return result(
                    argv,
                    b"".join(
                        f"{digest} *{path}\n".encode("utf-8")
                        for digest, path in zip(hashes, paths)
                    ),
                )
            raise AssertionError(f"unexpected runtime attestation argv: {argv!r}")

        return run

    @staticmethod
    def _independently_frozen_fixture_outputs(
        candidate_xml: bytes,
    ) -> tuple[bytes, bytes]:
        if candidate_xml == b'<osm version="0.6"/>\n':
            return (
                b"",
                b'{"candidateCounts":{"nodes":0,"relations":0,"ways":0},"rejectedRelationCounts":{},"rejectedRelations":[],"rejectedWayCounts":{},"rejectedWays":[],"rootIdsSha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","roots":[],"schema":"flight-alert-exp8-osm-selection-material-v1","selectedCounts":{"relations":0,"ways":0}}\n',
            )
        if candidate_xml == OsmPilotProvenanceTests._root_candidate_xml():
            return (
                b"w10\nr20\n",
                b'{"candidateCounts":{"nodes":0,"relations":2,"ways":2},"rejectedRelationCounts":{"no_display_name":1},"rejectedRelations":[{"id":21,"reason":"no_display_name"}],"rejectedWayCounts":{"closed":1},"rejectedWays":[{"id":11,"reason":"closed"}],"rootIdsSha256":"755975e13b077aa8b9902c575fd47ad07d989f849e59b9540b34c8d05c071eb4","roots":[{"displayNames":[["name","Alpha Run"]],"id":10,"nodeRefs":[1,2],"objectSha256":"f8474976a71e50ed20b4c3aa3ee84362271eaf6b145c0845e0e031a54fc5cf9f","objectType":"way","tags":[["name","Alpha Run"],["waterway","river"]],"timestamp":"2026-07-10T19:00:00Z","version":2,"waterway":"river"},{"displayNames":[["name","Alpha River"]],"id":20,"members":[{"objectType":"way","ordinal":0,"ref":10,"role":"main_stream"}],"objectSha256":"9b16f5f39030403363c89dfe2290409fdd751090a53127101e49f6785e5b6c5f","objectType":"relation","tags":[["name","Alpha River"],["type","waterway"]],"timestamp":"2026-07-10T19:02:00Z","version":3}],"schema":"flight-alert-exp8-osm-selection-material-v1","selectedCounts":{"relations":1,"ways":1}}\n',
            )
        raise AssertionError("candidate fixture output is not independently frozen")

    def _verified_components(
        self,
        root: Path,
        *,
        candidate_xml_bytes: bytes = b"<osm version=\"0.6\"/>\n",
    ):
        source_bytes = b"locked Maryland source bytes"
        candidate_pbf_bytes = b"candidate pbf"
        source_path = root / "maryland-260710.osm.pbf"
        candidate_pbf_path = root / "waterway-candidates-v2.osm.pbf"
        candidate_xml_path = root / "waterway-candidates-v2.osm"
        source_path.write_bytes(source_bytes)
        candidate_pbf_path.write_bytes(candidate_pbf_bytes)
        candidate_xml_path.write_bytes(candidate_xml_bytes)
        broad_root_ids, broad_material = self._independently_frozen_fixture_outputs(
            candidate_xml_bytes
        )
        independent_paths = self._write_independent_selection_fixture(
            root,
            candidate_xml_bytes,
            broad_root_ids,
            broad_material,
        )
        final_url = root / "provider-final-url.txt"
        head = root / "provider-head.txt"
        sidecar = root / "provider-md5-sidecar.txt"
        fileinfo = root / "source-fileinfo.txt"
        final_url.write_bytes((MARYLAND_SOURCE_URL + "\r\n").encode("ascii"))
        head.write_bytes(
            (
                "HTTP/2 200\r\n"
                "Date: Sat, 11 Jul 2026 00:55:30 GMT\r\n"
                f"content-length: {len(source_bytes)}\r\n"
                "content-type: application/octet-stream\r\n\r\n"
            ).encode("ascii")
        )
        sidecar.write_bytes(
            f"{hashlib.md5(source_bytes).hexdigest()}  maryland-260710.osm.pbf\n".encode(
                "ascii"
            )
        )
        fileinfo_bytes = self._fileinfo_capture(len(source_bytes))
        fileinfo.write_bytes(fileinfo_bytes)
        with patch.object(provenance, "_SOURCE_EVIDENCE_BYTES", len(source_bytes)), patch.object(
            provenance, "_SOURCE_EVIDENCE_MD5", hashlib.md5(source_bytes).hexdigest()
        ), patch.object(
            provenance, "_SOURCE_EVIDENCE_SHA256", hashlib.sha256(source_bytes).hexdigest()
        ), patch.multiple(
            provenance,
            MARYLAND_HEAD_BYTES=head.stat().st_size,
            MARYLAND_HEAD_SHA256=hashlib.sha256(head.read_bytes()).hexdigest(),
            MARYLAND_MD5_SIDECAR_BYTES=sidecar.stat().st_size,
            MARYLAND_MD5_SIDECAR_SHA256=hashlib.sha256(
                sidecar.read_bytes()
            ).hexdigest(),
        ), patch.object(
            provenance, "WATERWAY_CANDIDATES_PBF_BYTES", len(candidate_pbf_bytes)
        ), patch.object(
            provenance,
            "WATERWAY_CANDIDATES_PBF_SHA256",
            hashlib.sha256(candidate_pbf_bytes).hexdigest(),
        ), patch.object(
            provenance, "WATERWAY_CANDIDATES_XML_BYTES", len(candidate_xml_bytes)
        ), patch.object(
            provenance,
            "WATERWAY_CANDIDATES_XML_SHA256",
            hashlib.sha256(candidate_xml_bytes).hexdigest(),
        ), patch.object(
            provenance,
            "MARYLAND_FILEINFO_BYTES",
            len(fileinfo_bytes),
        ), patch.object(
            provenance,
            "MARYLAND_FILEINFO_SHA256",
            hashlib.sha256(fileinfo_bytes).hexdigest(),
        ), patch.object(
            provenance,
            "MARYLAND_BROAD_XML_BYTES",
            len(candidate_xml_bytes),
        ), patch.object(
            provenance,
            "MARYLAND_BROAD_XML_SHA256",
            hashlib.sha256(candidate_xml_bytes).hexdigest(),
        ), patch.object(
            provenance,
            "MARYLAND_BROAD_ROOT_IDS_BYTES",
            len(broad_root_ids),
        ), patch.object(
            provenance,
            "MARYLAND_BROAD_ROOT_IDS_SHA256",
            hashlib.sha256(broad_root_ids).hexdigest(),
        ), patch.object(
            provenance,
            "MARYLAND_BROAD_SELECTION_MATERIAL_BYTES",
            len(broad_material),
        ), patch.object(
            provenance,
            "MARYLAND_BROAD_SELECTION_MATERIAL_SHA256",
            hashlib.sha256(broad_material).hexdigest(),
        ), patch.object(
            provenance,
            "MARYLAND_INDEPENDENT_REPORT_BYTES",
            len(independent_paths[4]),
        ), patch.object(
            provenance,
            "MARYLAND_INDEPENDENT_REPORT_SHA256",
            hashlib.sha256(independent_paths[4]).hexdigest(),
        ):
            source = provenance.verify_maryland_source_file(source_path)
            candidates = provenance.verify_candidate_files(
                candidate_pbf_path, candidate_xml_path
            )
            provider = provenance.verify_provider_captures(
                final_url, head, sidecar, fileinfo, source=source
            )
            independent = provenance.verify_independent_selection_evidence(
                *independent_paths[:4]
            )
        local = provenance.verify_local_code_runtime()
        with patch.object(
            provenance,
            "run_bounded_process",
            side_effect=self._runtime_runner(ldd_address="0x1111"),
        ):
            wsl = provenance.attest_live_wsl_runtime()
        return local, source, provider, wsl, candidates, independent

    def test_independent_fixture_outputs_never_call_the_production_selector(self) -> None:
        factory = getattr(self, "_independently_frozen_fixture_outputs", None)
        self.assertIsNotNone(
            factory, "independently frozen fixture output factory is missing"
        )
        if factory is None:
            return
        forbidden_selector = Mock(
            side_effect=AssertionError("production selector used as fixture oracle")
        )
        with patch.object(
            osm_hydro_source,
            "encode_selection_material",
            forbidden_selector,
        ):
            empty = factory(b'<osm version="0.6"/>\n')
            roots = factory(self._root_candidate_xml())

        forbidden_selector.assert_not_called()
        self.assertEqual(empty[0], b"")
        self.assertEqual(
            hashlib.sha256(empty[1]).hexdigest(),
            "63ab663809ff89ff526f02a207ae5ba7216525f4b38a602e99f553eab98ac23a",
        )
        self.assertEqual(roots[0], b"w10\nr20\n")
        self.assertEqual(
            hashlib.sha256(roots[1]).hexdigest(),
            "fc5c8bd12a257b8847d2eb0265af500dcf410762c5c9cfd4383217fdcf4ecdbd",
        )
        with self.assertRaisesRegex(AssertionError, "not independently frozen"):
            factory(b'<osm version="0.6" generator="unfrozen"/>\n')

    @staticmethod
    def _fixture_lock_patches(inputs):
        return (
            patch.object(provenance, "_SOURCE_EVIDENCE_BYTES", inputs.source.bytes),
            patch.object(provenance, "_SOURCE_EVIDENCE_MD5", inputs.source.md5),
            patch.object(provenance, "_SOURCE_EVIDENCE_SHA256", inputs.source.sha256),
            patch.object(
                provenance, "MARYLAND_HEAD_BYTES", inputs.provider.head_file.bytes
            ),
            patch.object(
                provenance, "MARYLAND_HEAD_SHA256", inputs.provider.head_file.sha256
            ),
            patch.object(
                provenance,
                "MARYLAND_MD5_SIDECAR_BYTES",
                inputs.provider.md5_sidecar_file.bytes,
            ),
            patch.object(
                provenance,
                "MARYLAND_MD5_SIDECAR_SHA256",
                inputs.provider.md5_sidecar_file.sha256,
            ),
            patch.object(
                provenance,
                "WATERWAY_CANDIDATES_PBF_BYTES",
                inputs.candidates.pbf_bytes,
            ),
            patch.object(
                provenance,
                "WATERWAY_CANDIDATES_PBF_SHA256",
                inputs.candidates.pbf_sha256,
            ),
            patch.object(
                provenance,
                "WATERWAY_CANDIDATES_XML_BYTES",
                inputs.candidates.xml_bytes,
            ),
            patch.object(
                provenance,
                "WATERWAY_CANDIDATES_XML_SHA256",
                inputs.candidates.xml_sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_FILEINFO_BYTES",
                inputs.provider.fileinfo_file.bytes,
            ),
            patch.object(
                provenance,
                "MARYLAND_FILEINFO_SHA256",
                inputs.provider.fileinfo_file.sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_BROAD_XML_BYTES",
                inputs.independent.broad_xml_file.bytes,
            ),
            patch.object(
                provenance,
                "MARYLAND_BROAD_XML_SHA256",
                inputs.independent.broad_xml_sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_BROAD_ROOT_IDS_BYTES",
                inputs.independent.root_ids_file.bytes,
            ),
            patch.object(
                provenance,
                "MARYLAND_BROAD_ROOT_IDS_SHA256",
                inputs.independent.root_ids_sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_BROAD_SELECTION_MATERIAL_BYTES",
                inputs.independent.selection_material_file.bytes,
            ),
            patch.object(
                provenance,
                "MARYLAND_BROAD_SELECTION_MATERIAL_SHA256",
                inputs.independent.selection_material_sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_INDEPENDENT_REPORT_BYTES",
                inputs.independent.report_file.bytes,
            ),
            patch.object(
                provenance,
                "MARYLAND_INDEPENDENT_REPORT_SHA256",
                inputs.independent.report_sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_ROOT_IDS_BYTES",
                len(inputs.selection.root_ids),
            ),
            patch.object(
                provenance,
                "MARYLAND_ROOT_WAY_COUNT",
                len(inputs.selection.way_ids),
            ),
            patch.object(
                provenance,
                "MARYLAND_ROOT_RELATION_COUNT",
                len(inputs.selection.relation_ids),
            ),
            patch.object(
                provenance,
                "MARYLAND_ROOT_IDS_SHA256",
                inputs.selection.root_ids_sha256,
            ),
            patch.object(
                provenance,
                "MARYLAND_SELECTION_MATERIAL_BYTES",
                len(inputs.selection.selection_material),
            ),
            patch.object(
                provenance,
                "MARYLAND_SELECTION_MATERIAL_SHA256",
                inputs.selection.selection_material_sha256,
            ),
        )

    @contextmanager
    def _current_fixture_graph(self, inputs):
        with ExitStack() as stack:
            for current_lock in self._fixture_lock_patches(inputs):
                stack.enter_context(current_lock)
            stack.enter_context(
                patch.object(
                    provenance,
                    "attest_live_wsl_runtime",
                    return_value=inputs.wsl,
                )
            )
            yield

    def test_live_wsl_attestation_is_pinned_and_ldd_inventory_is_address_independent(self) -> None:
        factory = getattr(provenance, "attest_live_wsl_runtime", None)
        self.assertIsNotNone(factory, "live WSL runtime attestation factory is missing")

        with patch.object(
            provenance,
            "run_bounded_process",
            side_effect=self._runtime_runner(ldd_address="0x1111"),
        ):
            first = factory()
        with patch.object(
            provenance,
            "run_bounded_process",
            side_effect=self._runtime_runner(ldd_address="0x9999"),
        ):
            second = factory()

        self.assertEqual(first, second)
        self.assertEqual(first.osmium_binary_sha256, provenance.OSMIUM_BINARY_SHA256)
        self.assertEqual(first.boost_library_sha256, provenance.BOOST_LIBRARY_SHA256)
        self.assertEqual(first.wsl_version, 1)
        self.assertEqual(first.architecture, "x86_64")
        self.assertEqual(
            tuple(item.soname for item in first.ldd_inventory),
            (
                "ld-linux-x86-64.so.2",
                "libboost_program_options.so.1.71.0",
                "libz.so.1",
                "linux-vdso.so.1",
            ),
        )
        boost_dependency = next(
            item
            for item in first.ldd_inventory
            if item.soname == "libboost_program_options.so.1.71.0"
        )
        self.assertEqual(boost_dependency.sha256, provenance.BOOST_LIBRARY_SHA256)
        self.assertIsNone(first.ldd_inventory[-1].sha256)

    def test_live_wsl_attestation_rejects_pre_post_runtime_hash_drift(self) -> None:
        factory = getattr(provenance, "attest_live_wsl_runtime", None)
        self.assertIsNotNone(factory, "live WSL runtime attestation factory is missing")
        error_type = getattr(provenance, "ProvenanceVerificationError", ValueError)

        with patch.object(
            provenance,
            "run_bounded_process",
            side_effect=self._runtime_runner(ldd_address="0x1111", drift_post_hash=True),
        ):
            with self.assertRaisesRegex(error_type, "changed during attestation"):
                factory()

    def test_live_wsl_attestation_rejects_dependency_snapshot_drift(self) -> None:
        with patch.object(
            provenance,
            "run_bounded_process",
            side_effect=self._runtime_runner(
                ldd_address="0x1111", drift_dependency_hash=True
            ),
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError, "dependencies changed"
            ):
                provenance.attest_live_wsl_runtime()

    def test_source_identity_contains_exact_locked_header_and_source_facts(self) -> None:
        assembler = getattr(provenance, "assemble_verified_pilot_inputs", None)
        self.assertIsNotNone(assembler, "verified pilot input assembler is missing")
        with tempfile.TemporaryDirectory() as temporary:
            inputs = assembler(*self._verified_components(Path(temporary)))
            with self._current_fixture_graph(inputs):
                document = provenance.source_identity_document(inputs)

        self.assertEqual(document["profile"], MARYLAND_REGIONAL_PROFILE)
        self.assertEqual(
            document["source"],
            {
                "bytes": inputs.source.bytes,
                "md5": inputs.source.md5,
                "sha256": inputs.source.sha256,
                "url": MARYLAND_SOURCE_URL,
            },
        )
        self.assertEqual(document["header"]["replicationSequence"], 4845)
        self.assertEqual(
            document["header"]["replicationTimestamp"],
            "2026-07-10T20:21:01Z",
        )
        self.assertEqual(document["data"]["counts"]["nodes"], 27_286_527)
        self.assertEqual(document["data"]["counts"]["ways"], 3_178_490)
        self.assertEqual(document["data"]["counts"]["relations"], 27_883)
        self.assertNotIn("path", json.dumps(document).casefold())
        self.assertEqual(
            document["providerEvidence"]["finalUrlSha256"],
            inputs.provider.final_url_file.sha256,
        )
        self.assertEqual(
            document["providerEvidence"]["fileinfoCommand"],
            {
                "argv": [
                    "osmium",
                    "fileinfo",
                    "-e",
                    "-c",
                    "source/maryland-260710.osm.pbf",
                ],
                "binarySha256": inputs.wsl.osmium_binary_sha256,
                "libosmiumVersion": inputs.wsl.libosmium_version,
                "osmiumVersion": inputs.wsl.osmium_version,
            },
        )

    def test_runtime_manifest_locks_osmium_boost_wsl_locale_and_python(self) -> None:
        assembler = getattr(provenance, "assemble_verified_pilot_inputs", None)
        self.assertIsNotNone(assembler, "verified pilot input assembler is missing")
        with tempfile.TemporaryDirectory() as temporary:
            inputs = assembler(*self._verified_components(Path(temporary)))
            with self._current_fixture_graph(inputs):
                document = provenance.runtime_manifest_document(inputs)

        self.assertEqual(document["osmium"]["version"], "1.11.1")
        self.assertEqual(document["osmium"]["libosmiumVersion"], "2.15.4")
        self.assertEqual(document["osmium"]["debSha256"], provenance.OSMIUM_DEB_SHA256)
        self.assertEqual(
            document["boostProgramOptions"]["debSha256"], provenance.BOOST_DEB_SHA256
        )
        self.assertEqual(document["wsl"]["release"], "Ubuntu 20.04.3 LTS")
        self.assertEqual(document["wsl"]["version"], inputs.wsl.wsl_version)
        self.assertEqual(document["wsl"]["architecture"], inputs.wsl.architecture)
        self.assertEqual(document["wsl"]["locale"], "C.UTF-8")
        self.assertEqual(document["python"]["version"], "3.11.1")
        self.assertEqual(document["selector"]["sha256"], inputs.local.selector_sha256)
        self.assertEqual(
            document["selector"]["callable"],
            "tools.experiment8.osm_hydro_source.encode_selection_material",
        )
        self.assertEqual(
            document["selector"]["callableCodeSha256"],
            inputs.local.selector_callable_code_sha256,
        )
        self.assertEqual(
            document["selector"]["execution"],
            "isolated-verified-source-snapshot",
        )
        self.assertEqual(len(document["lddInventory"]), len(inputs.wsl.ldd_inventory))
        encoded = json.dumps(document).casefold()
        self.assertNotIn("c:\\", encoded)
        self.assertNotIn("/home/", encoded)

    def test_selection_manifest_records_the_exact_two_stage_candidate_pipeline(self) -> None:
        assembler = getattr(provenance, "assemble_verified_pilot_inputs", None)
        self.assertIsNotNone(assembler, "verified pilot input assembler is missing")
        with tempfile.TemporaryDirectory() as temporary:
            inputs = assembler(*self._verified_components(Path(temporary)))
            with self._current_fixture_graph(inputs):
                document = provenance.selection_command_manifest_document(inputs)

        self.assertEqual(document["profile"], MARYLAND_REGIONAL_PROFILE)
        self.assertEqual(document["policySha256"], inputs.local.policy_sha256)
        self.assertEqual(document["selectorModuleSha256"], inputs.local.selector_sha256)
        self.assertEqual(len(document["commands"]), 2)
        tags_filter, convert = document["commands"]
        self.assertEqual(tags_filter["tool"], "osmium tags-filter")
        self.assertIn("-R", tags_filter["argv"])
        self.assertEqual(
            tags_filter["output"]["sha256"], inputs.candidates.pbf_sha256
        )
        self.assertEqual(convert["tool"], "osmium cat")
        self.assertEqual(
            convert["output"]["sha256"], inputs.candidates.xml_sha256
        )
        self.assertNotIn("name", " ".join(tags_filter["argv"]))
        self.assertEqual(
            document["selectorCall"]["callable"],
            "tools.experiment8.osm_hydro_source.encode_selection_material",
        )
        self.assertEqual(
            document["selectorCall"]["input"],
            {
                "bytes": inputs.candidates.xml_bytes,
                "logicalName": "selection/waterway-candidates-v2.osm",
                "sha256": inputs.candidates.xml_sha256,
            },
        )
        self.assertEqual(
            document["selectorCall"]["outputs"],
            [
                {
                    "bytes": len(inputs.selection.root_ids),
                    "logicalName": "root-ids.txt",
                    "sha256": inputs.selection.root_ids_sha256,
                },
                {
                    "bytes": len(inputs.selection.selection_material),
                    "logicalName": "selection-material.json",
                    "sha256": inputs.selection.selection_material_sha256,
                },
            ],
        )

    def test_selector_derives_from_the_exact_stable_candidate_snapshot(self) -> None:
        candidate_xml = self._root_candidate_xml()
        expected_outputs = osm_hydro_source.encode_selection_material(candidate_xml)
        with tempfile.TemporaryDirectory() as temporary:
            components = self._verified_components(
                Path(temporary), candidate_xml_bytes=candidate_xml
            )
            inputs = provenance.assemble_verified_pilot_inputs(*components)

            self.assertEqual(inputs.selection.candidate_xml, candidate_xml)
            self.assertEqual(inputs.selection.root_ids, expected_outputs[0])
            self.assertEqual(inputs.selection.selection_material, expected_outputs[1])

            components[4].xml_file.path.write_bytes(b"changed before selector call\n")
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "changed after verification|SHA-256 mismatch|byte count mismatch",
            ):
                provenance.assemble_verified_pilot_inputs(*components)

    def test_clean_start_output_locks_reject_any_derived_result_drift(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(Path(temporary))
            )
            selection = inputs.selection
            patches = (
                patch.object(
                    provenance,
                    "WATERWAY_CANDIDATES_PBF_BYTES",
                    inputs.candidates.pbf_bytes,
                ),
                patch.object(
                    provenance,
                    "WATERWAY_CANDIDATES_PBF_SHA256",
                    inputs.candidates.pbf_sha256,
                ),
                patch.object(
                    provenance,
                    "WATERWAY_CANDIDATES_XML_BYTES",
                    inputs.candidates.xml_bytes,
                ),
                patch.object(
                    provenance,
                    "WATERWAY_CANDIDATES_XML_SHA256",
                    inputs.candidates.xml_sha256,
                ),
                patch.object(
                    provenance, "MARYLAND_ROOT_IDS_BYTES", len(selection.root_ids)
                ),
                patch.object(
                    provenance,
                    "MARYLAND_ROOT_IDS_SHA256",
                    selection.root_ids_sha256,
                ),
                patch.object(
                    provenance,
                    "MARYLAND_ROOT_WAY_COUNT",
                    len(selection.way_ids),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_ROOT_RELATION_COUNT",
                    len(selection.relation_ids),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_SELECTION_MATERIAL_BYTES",
                    len(selection.selection_material),
                ),
                patch.object(
                    provenance,
                    "MARYLAND_SELECTION_MATERIAL_SHA256",
                    selection.selection_material_sha256,
                ),
            )
            with ExitStack() as stack:
                for current in patches:
                    stack.enter_context(current)
                provenance._validate_accepted_maryland_selection(
                    inputs.candidates,
                    selection.root_ids,
                    selection.selection_material,
                    selection.way_ids,
                    selection.relation_ids,
                )
                with patch.object(
                    provenance,
                    "MARYLAND_SELECTION_MATERIAL_SHA256",
                    "0" * 64,
                ):
                    with self.assertRaisesRegex(
                        provenance.ProvenanceVerificationError,
                        "clean-start output locks",
                    ):
                        provenance._validate_accepted_maryland_selection(
                            inputs.candidates,
                            selection.root_ids,
                            selection.selection_material,
                            selection.way_ids,
                            selection.relation_ids,
                        )

    def test_independent_broad_evidence_is_required_replayed_and_manifest_bound(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            components = self._verified_components(Path(temporary))
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "independent broad selection",
            ):
                provenance.assemble_verified_pilot_inputs(
                    *components[:5], None
                )
            inputs = provenance.assemble_verified_pilot_inputs(*components)
            with self._current_fixture_graph(inputs):
                binding = provenance.selection_binding_document(inputs)
            self.assertEqual(
                binding["independentBroadEvidence"],
                {
                    "broadInput": {
                        "bytes": inputs.independent.broad_xml_file.bytes,
                        "logicalName": "independent/waterway-broad-v1.osm",
                        "sha256": inputs.independent.broad_xml_sha256,
                    },
                    "report": {
                        "bytes": inputs.independent.report_file.bytes,
                        "logicalName": (
                            "independent/broad-independent-verification-v1.json"
                        ),
                        "sha256": inputs.independent.report_sha256,
                    },
                    "rootIds": {
                        "bytes": inputs.independent.root_ids_file.bytes,
                        "logicalName": "independent/broad-root-ids-v1.txt",
                        "sha256": inputs.independent.root_ids_sha256,
                    },
                    "selectionMaterial": {
                        "bytes": inputs.independent.selection_material_file.bytes,
                        "logicalName": (
                            "independent/broad-selection-material-v1.json"
                        ),
                        "sha256": inputs.independent.selection_material_sha256,
                    },
                    "selectedCounts": {"relations": 0, "ways": 0},
                },
            )
            report_path = inputs.independent.report_file.path
            report_path.write_bytes(report_path.read_bytes() + b"tamper")
            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "independent broad selection evidence",
                ):
                    provenance.selection_binding_document(inputs)

    def test_assembler_rejects_a_byte_identical_selector_wrapper_before_invocation(self) -> None:
        candidate_xml = self._root_candidate_xml()
        original = osm_hydro_source.encode_selection_material
        calls: list[bytes] = []

        def byte_identical_wrapper(value: bytes) -> tuple[bytes, bytes]:
            calls.append(value)
            return original(value)

        with tempfile.TemporaryDirectory() as temporary:
            components = self._verified_components(
                Path(temporary), candidate_xml_bytes=candidate_xml
            )
            with patch.object(
                osm_hydro_source,
                "encode_selection_material",
                new=byte_identical_wrapper,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "callable identity",
                ):
                    provenance.assemble_verified_pilot_inputs(*components)

        self.assertEqual(calls, [])

    def test_selector_outputs_are_untrusted_and_strictly_reconciled(self) -> None:
        candidate_xml = self._root_candidate_xml()
        root_ids, material = osm_hydro_source.encode_selection_material(candidate_xml)
        document = json.loads(material)

        noncanonical_document = json.dumps(document, indent=2).encode("utf-8") + b"\n"
        duplicate_key_document = b'{"schema":"forged",' + material[1:]

        swapped_ids = b"r20\nw10\n"
        swapped_ids_document = json.loads(material)
        swapped_ids_document["rootIdsSha256"] = hashlib.sha256(swapped_ids).hexdigest()

        duplicate_ids = b"w10\nw10\nr20\n"
        duplicate_ids_document = json.loads(material)
        duplicate_ids_document["rootIdsSha256"] = hashlib.sha256(
            duplicate_ids
        ).hexdigest()

        forged_root_document = json.loads(material)
        forged_root_document["roots"][0]["id"] = 99

        wrong_count_document = json.loads(material)
        wrong_count_document["candidateCounts"]["ways"] += 1

        forbidden_claim_document = json.loads(material)
        forbidden_claim_document["policySha256"] = "0" * 64
        other_candidate_outputs = osm_hydro_source.encode_selection_material(
            b'<osm version="0.6"/>\n'
        )

        invalid_results = {
            "mutable root IDs": (bytearray(root_ids), material),
            "mutable material": (root_ids, bytearray(material)),
            "swapped outputs": (material, root_ids),
            "noncanonical JSON": (root_ids, noncanonical_document),
            "duplicate JSON key": (root_ids, duplicate_key_document),
            "noncanonical root order": (
                swapped_ids,
                canonical_json_bytes(swapped_ids_document),
            ),
            "duplicate root ID": (
                duplicate_ids,
                canonical_json_bytes(duplicate_ids_document),
            ),
            "forged root": (root_ids, canonical_json_bytes(forged_root_document)),
            "wrong candidate count": (
                root_ids,
                canonical_json_bytes(wrong_count_document),
            ),
            "outputs from another candidate snapshot": other_candidate_outputs,
            "forbidden provenance claim": (
                root_ids,
                canonical_json_bytes(forbidden_claim_document),
            ),
        }

        for label, result in invalid_results.items():
            with self.subTest(label=label):
                with self.assertRaises(provenance.ProvenanceVerificationError):
                    provenance._validate_selection_outputs(
                        candidate_xml,
                        result[0],
                        result[1],
                    )

        with patch.object(
            provenance,
            "MAX_SELECTION_MATERIAL_BYTES",
            len(material) - 1,
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError, "byte ceiling"
            ):
                provenance._validate_selection_outputs(
                    candidate_xml,
                    root_ids,
                    material,
                )

        with patch.object(
            provenance,
            "MAX_ROOT_IDS_BYTES",
            len(root_ids) - 1,
        ):
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError, "byte ceiling"
            ):
                provenance._validate_selection_outputs(
                    candidate_xml,
                    root_ids,
                    material,
                )

    def test_selection_material_oversized_integer_is_a_provenance_error(self) -> None:
        integer_limit = sys.get_int_max_str_digits()
        self.assertGreater(integer_limit, 0)
        material = b'{"value":' + (b"1" * (integer_limit + 1)) + b"}\n"

        with self.assertRaisesRegex(
            provenance.ProvenanceVerificationError,
            "strict JSON",
        ):
            provenance._decode_selection_material(material)

    def test_selection_binding_reconciles_every_current_input_and_output_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(Path(temporary))
            )
            with self._current_fixture_graph(inputs):
                document = provenance.selection_binding_document(inputs)
                source_document = provenance.source_identity_document(inputs)
                runtime_document = provenance.runtime_manifest_document(inputs)
                command_document = provenance.selection_command_manifest_document(
                    inputs
                )

        self.assertEqual(
            document["candidateXml"],
            {
                "bytes": inputs.candidates.xml_bytes,
                "logicalName": "selection/waterway-candidates-v2.osm",
                "sha256": inputs.candidates.xml_sha256,
            },
        )
        self.assertEqual(document["policySha256"], inputs.local.policy_sha256)
        self.assertEqual(
            document["selector"],
            {
                "module": inputs.local.selector_module,
                "sha256": inputs.local.selector_sha256,
            },
        )
        self.assertEqual(
            document["source"],
            {
                "bytes": inputs.source.bytes,
                "logicalName": "source/maryland-260710.osm.pbf",
                "sha256": inputs.source.sha256,
            },
        )
        self.assertEqual(
            document["sourceIdentitySha256"],
            hashlib.sha256(canonical_json_bytes(source_document)).hexdigest(),
        )
        self.assertEqual(
            document["runtimeManifestSha256"],
            hashlib.sha256(canonical_json_bytes(runtime_document)).hexdigest(),
        )
        self.assertEqual(
            document["selectionCommandManifestSha256"],
            hashlib.sha256(canonical_json_bytes(command_document)).hexdigest(),
        )
        self.assertEqual(
            document["outputs"],
            [
                {
                    "bytes": len(inputs.selection.root_ids),
                    "logicalName": "root-ids.txt",
                    "sha256": inputs.selection.root_ids_sha256,
                },
                {
                    "bytes": len(inputs.selection.selection_material),
                    "logicalName": "selection-material.json",
                    "sha256": inputs.selection.selection_material_sha256,
                },
            ],
        )

        with self.assertRaisesRegex(
            provenance.ProvenanceVerificationError, "live verification"
        ):
            replace(inputs.selection, root_ids_sha256="0" * 64)

    def test_canonical_json_is_sorted_compact_utf8_and_final_lf(self) -> None:
        encoded = canonical_json_bytes({"z": "Café", "a": [2, 1]})

        self.assertEqual(encoded, b'{"a":[2,1],"z":"Caf\xc3\xa9"}\n')
        self.assertEqual(
            hashlib.sha256(encoded).hexdigest(),
            "d4240af2bdfdd06eca4f0f59d340e833277c420fda46a8ee4c2f57294e077aea",
        )

    def test_public_document_builders_revalidate_once_and_reject_stale_fixture_locks(self) -> None:
        builders = (
            provenance.source_identity_document,
            provenance.runtime_manifest_document,
            provenance.selection_command_manifest_document,
            provenance.selection_binding_document,
        )
        with tempfile.TemporaryDirectory() as temporary:
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(Path(temporary))
            )

            for builder in builders:
                with self.subTest(builder=builder.__name__), ExitStack() as stack:
                    for current_lock in self._fixture_lock_patches(inputs):
                        stack.enter_context(current_lock)
                    runtime = stack.enter_context(
                        patch.object(
                            provenance,
                            "attest_live_wsl_runtime",
                            return_value=inputs.wsl,
                        )
                    )
                    builder(inputs)
                    self.assertEqual(runtime.call_count, 1)

            for builder in builders:
                with self.subTest(stale_builder=builder.__name__):
                    with self.assertRaises(provenance.ProvenanceVerificationError):
                        builder(inputs)

    def test_bundle_writer_accepts_only_verified_inputs_and_commits_one_rooted_directory(self) -> None:
        assembler = getattr(provenance, "assemble_verified_pilot_inputs", None)
        self.assertIsNotNone(assembler, "verified pilot input assembler is missing")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = assembler(*self._verified_components(root))
            destination = Path(temporary, "provenance")
            locks = self._fixture_lock_patches(inputs)

            with ExitStack() as stack:
                for current_lock in locks:
                    stack.enter_context(current_lock)
                runtime = stack.enter_context(
                    patch.object(
                        provenance,
                        "attest_live_wsl_runtime",
                        return_value=inputs.wsl,
                    )
                )
                result = provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertEqual(runtime.call_count, 3)
            self.assertEqual(
                result.selector_module_sha256,
                inputs.local.selector_sha256,
            )
            expected_files = {
                "bundle-root.json",
                "provider-final-url.txt",
                "provider-head.txt",
                "provider-md5-sidecar.txt",
                "root-ids.txt",
                "runtime-manifest.json",
                "selection-binding.json",
                "selection-command-manifest.json",
                "selection-material.json",
                "selector-source.py",
                "source-fileinfo.txt",
                "source-identity.json",
            }
            self.assertEqual(
                {path.name for path in destination.iterdir()}, expected_files
            )
            for filename, digest in result.document_sha256:
                self.assertEqual(
                    hashlib.sha256(Path(destination, filename).read_bytes()).hexdigest(),
                    digest,
                )
            bundle_root = json.loads(Path(destination, "bundle-root.json").read_bytes())
            self.assertEqual(bundle_root["schema"], "flight-alert-exp8-osm-provenance-root-v1")
            self.assertEqual(
                [item["logicalName"] for item in bundle_root["files"]],
                sorted(expected_files - {"bundle-root.json"}),
            )
            root_names = [item["logicalName"] for item in bundle_root["files"]]
            self.assertEqual(len(root_names), len(set(root_names)))
            bundle_members = {
                path.relative_to(destination).as_posix()
                for path in destination.rglob("*")
                if path.is_file()
            }
            self.assertEqual(
                set(root_names) | {"bundle-root.json"},
                bundle_members,
            )
            command_manifest = json.loads(
                Path(destination, "selection-command-manifest.json").read_bytes()
            )
            binding = json.loads(
                Path(destination, "selection-binding.json").read_bytes()
            )
            output_facts = [
                *command_manifest["selectorCall"]["outputs"],
                *binding["outputs"],
            ]
            self.assertEqual(
                {item["logicalName"] for item in output_facts},
                {"root-ids.txt", "selection-material.json"},
            )
            for output in output_facts:
                matches = [
                    item
                    for item in bundle_root["files"]
                    if item["logicalName"] == output["logicalName"]
                ]
                self.assertEqual(matches, [output])
                payload = Path(destination, output["logicalName"]).read_bytes()
                self.assertEqual(len(payload), output["bytes"])
                self.assertEqual(
                    hashlib.sha256(payload).hexdigest(), output["sha256"]
                )
            self.assertEqual(
                Path(destination, "root-ids.txt").read_bytes(),
                inputs.selection.root_ids,
            )
            self.assertEqual(
                Path(destination, "selection-material.json").read_bytes(),
                inputs.selection.selection_material,
            )
            self.assertEqual(
                Path(destination, "selection-binding.json").read_bytes(),
                canonical_json_bytes(binding),
            )

            arbitrary_selector = root / "arbitrary-selector.py"
            arbitrary_selector.write_bytes(b"not the imported selector\n")
            rejected = root / "rejected"
            error_type = getattr(provenance, "ProvenanceVerificationError", ValueError)
            with self.assertRaises(error_type):
                provenance.write_pilot_provenance_bundle(rejected, arbitrary_selector)
            self.assertFalse(rejected.exists())

            with self.assertRaises(FileExistsError):
                provenance.write_pilot_provenance_bundle(destination, inputs)
            self.assertEqual(
                {path.name for path in destination.iterdir()}, expected_files
            )

    def test_bundle_writer_revalidates_files_and_leaves_no_partial_directory(self) -> None:
        assembler = getattr(provenance, "assemble_verified_pilot_inputs", None)
        self.assertIsNotNone(assembler, "verified pilot input assembler is missing")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = assembler(*self._verified_components(root))
            inputs.candidates.xml_file.path.write_bytes(b"changed after verification\n")
            destination = root / "provenance"
            error_type = getattr(provenance, "ProvenanceVerificationError", ValueError)
            locks = self._fixture_lock_patches(inputs)

            with ExitStack() as stack:
                for current_lock in locks:
                    stack.enter_context(current_lock)
                stack.enter_context(
                    patch.object(
                        provenance,
                        "attest_live_wsl_runtime",
                        return_value=inputs.wsl,
                    )
                )
                with self.assertRaisesRegex(error_type, "changed after verification"):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".provenance.*.staging")))

    def test_verified_evidence_cannot_be_replaced_and_writer_replays_current_locks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError, "live verification"
            ):
                replace(inputs.candidates, pbf_sha256="0" * 64)

            destination = root / "stale-lock-provenance"
            with patch.object(
                provenance, "attest_live_wsl_runtime", return_value=inputs.wsl
            ):
                with self.assertRaises(provenance.ProvenanceVerificationError):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".stale-lock-provenance.*.staging")))

    def test_bundle_writer_cleans_staging_when_second_revalidation_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "second-check-provenance"
            locks = self._fixture_lock_patches(inputs)
            original_write = provenance._write_new_file

            def write_then_mutate(path: Path, content: bytes):
                file_key = original_write(path, content)
                if path.name == "source-identity.json":
                    inputs.candidates.xml_file.path.write_bytes(
                        b"changed after staging completed\n"
                    )
                return file_key

            with ExitStack() as stack:
                for current_lock in locks:
                    stack.enter_context(current_lock)
                stack.enter_context(
                    patch.object(
                        provenance,
                        "attest_live_wsl_runtime",
                        return_value=inputs.wsl,
                    )
                )
                stack.enter_context(
                    patch.object(
                        provenance,
                        "_write_new_file",
                        side_effect=write_then_mutate,
                    )
                )
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError, "changed after verification"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".second-check-provenance.*.staging")))

    def test_bundle_writer_holds_every_fixture_input_identity_through_success(
        self,
    ) -> None:
        cases = (
            (
                "Maryland source",
                "verify_maryland_source_file",
                lambda inputs: inputs.source.file.path,
            ),
            (
                "provider capture",
                "verify_provider_captures",
                lambda inputs: inputs.provider.head_file.path,
            ),
            (
                "candidate PBF",
                "verify_candidate_files",
                lambda inputs: inputs.candidates.pbf_file.path,
            ),
            (
                "independent broad XML",
                "verify_independent_selection_evidence",
                lambda inputs: inputs.independent.broad_xml_file.path,
            ),
        )
        for label, verifier_name, path_from in cases:
            with self.subTest(input=label), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                inputs = provenance.assemble_verified_pilot_inputs(
                    *self._verified_components(root)
                )
                destination = root / "held-input-provenance"
                original_verifier = getattr(provenance, verifier_name)
                original_readback = provenance.read_pilot_provenance_bundle
                input_path = path_from(inputs)
                expected_sha256 = hashlib.sha256(input_path.read_bytes()).hexdigest()
                verifier_calls = 0
                mutation_attempts = 0
                blocked_mutations = 0
                successful_mutations = 0

                def attempt_mutation() -> None:
                    nonlocal mutation_attempts, blocked_mutations
                    nonlocal successful_mutations
                    mutation_attempts += 1
                    try:
                        input_path.write_bytes(b"changed after its final check\n")
                    except OSError:
                        blocked_mutations += 1
                    else:
                        successful_mutations += 1

                def verify_then_attempt_mutation(*args, **kwargs):
                    nonlocal verifier_calls
                    current = original_verifier(*args, **kwargs)
                    verifier_calls += 1
                    if verifier_calls == 2:
                        attempt_mutation()
                    return current

                def mutate_during_readback(path: Path):
                    attempt_mutation()
                    return original_readback(path)

                with self._current_fixture_graph(inputs), patch.object(
                    provenance,
                    verifier_name,
                    side_effect=verify_then_attempt_mutation,
                ), patch.object(
                    provenance,
                    "read_pilot_provenance_bundle",
                    side_effect=mutate_during_readback,
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

                self.assertEqual(verifier_calls, 2)
                self.assertEqual(mutation_attempts, 2)
                self.assertEqual(
                    blocked_mutations,
                    2,
                    f"{label} was writable after its final verification",
                )
                self.assertEqual(successful_mutations, 0)
                self.assertEqual(
                    hashlib.sha256(input_path.read_bytes()).hexdigest(),
                    expected_sha256,
                )
                self.assertTrue(destination.is_dir())

    def test_exact_directory_snapshot_rejects_extra_missing_tampered_and_reparse(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            expected = {"a.bin": b"alpha", "b.bin": b"beta"}
            for name, payload in expected.items():
                Path(root, name).write_bytes(payload)
            snapshot = provenance._verify_exact_directory_payloads(root, expected)
            self.assertEqual(
                tuple((item.logical_name, item.sha256) for item in snapshot),
                tuple(
                    (
                        name,
                        hashlib.sha256(expected[name]).hexdigest(),
                    )
                    for name in sorted(expected)
                ),
            )

            original_link_check = osm_pilot_bundle._is_link_or_reparse

            def mark_payload_reparse(path: Path, raw_stat: os.stat_result) -> bool:
                return path.name == "a.bin" or original_link_check(path, raw_stat)

            with patch.object(
                osm_pilot_bundle,
                "_is_link_or_reparse",
                side_effect=mark_payload_reparse,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "non-reparse regular files",
                ):
                    provenance._verify_exact_directory_payloads(root, expected)

            Path(root, "extra.bin").write_bytes(b"extra")
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exact file set",
            ):
                provenance._verify_exact_directory_payloads(root, expected)
            Path(root, "extra.bin").unlink()

            Path(root, "a.bin").unlink()
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exact file set",
            ):
                provenance._verify_exact_directory_payloads(root, expected)
            Path(root, "a.bin").write_bytes(expected["a.bin"])

            Path(root, "b.bin").write_bytes(b"tampered")
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "byte count|SHA-256|exact bytes",
            ):
                provenance._verify_exact_directory_payloads(root, expected)

        fake_stat = Mock()
        fake_stat.st_file_attributes = stat.FILE_ATTRIBUTE_REPARSE_POINT
        self.assertTrue(
            provenance._is_link_or_reparse(Path("logical-entry"), fake_stat)
        )

    def test_exact_directory_snapshot_rejects_external_hardlink_alias(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = root / "snapshot"
            directory.mkdir()
            payload = b"exact snapshot bytes"
            payload_path = directory / "payload.bin"
            alias = root / "external-alias.bin"
            payload_path.write_bytes(payload)
            os.link(payload_path, alias)

            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exactly one link",
            ):
                provenance._verify_exact_directory_payloads(
                    directory, {"payload.bin": payload}
                )

            self.assertEqual(alias.read_bytes(), payload)

    def test_bundle_writer_rejects_staged_external_hardlink_alias(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "staged-hardlink-provenance"
            alias = root / "staged-external-alias.bin"
            original_write = provenance._write_new_file

            def write_then_link(path: Path, content: bytes):
                file_key = original_write(path, content)
                if path.name == "source-identity.json":
                    os.link(path.parent / "root-ids.txt", alias)
                return file_key

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_write_new_file",
                side_effect=write_then_link,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "exactly one link",
                ) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(
                destination.exists(),
                repr(
                    sorted(
                        (path.name, path.stat().st_nlink)
                        for path in destination.iterdir()
                    )
                    if destination.exists()
                    else []
                )
                + f"; error={raised.exception!r}; notes="
                + repr(getattr(raised.exception, "__notes__", ())),
            )
            self.assertEqual(alias.read_bytes(), inputs.selection.root_ids)
            self.assertFalse(
                list(root.glob(".staged-hardlink-provenance.*.staging"))
            )

    def test_bundle_writer_rejects_external_alias_before_child_reopen(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "reopen-hardlink-provenance"
            alias = root / "reopen-external-alias.bin"
            original_rename = provenance._rename_windows_owned_handle

            def rename_then_link(handle: int, target: Path) -> None:
                original_rename(handle, target)
                os.link(target / "root-ids.txt", alias)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_rename_windows_owned_handle",
                side_effect=rename_then_link,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "exactly one link",
                ) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(
                destination.exists(),
                repr(
                    sorted(
                        (path.name, path.stat().st_nlink)
                        for path in destination.iterdir()
                    )
                    if destination.exists()
                    else []
                )
                + f"; error={raised.exception!r}; notes="
                + repr(getattr(raised.exception, "__notes__", ())),
            )
            self.assertEqual(alias.read_bytes(), inputs.selection.root_ids)

    def test_bundle_writer_rejects_installed_external_hardlink_alias(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "installed-hardlink-provenance"
            alias = root / "installed-external-alias.bin"
            original_publish = provenance._publish_owned_directory

            def publish_then_link(receipt, target: Path) -> None:
                original_publish(receipt, target)
                os.link(target / "root-ids.txt", alias)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_publish_owned_directory",
                side_effect=publish_then_link,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "exactly one link",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertEqual(alias.read_bytes(), inputs.selection.root_ids)

    def test_bundle_writer_rejects_external_alias_after_public_readback(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "final-held-hardlink-provenance"
            alias = root / "final-held-external-alias.bin"
            original_readback = provenance.read_pilot_provenance_bundle

            def read_then_link(path: Path):
                result = original_readback(path)
                os.link(path / "root-ids.txt", alias)
                return result

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "read_pilot_provenance_bundle",
                side_effect=read_then_link,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "exactly one link",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertEqual(alias.read_bytes(), inputs.selection.root_ids)

    def test_bundle_writer_rejects_staged_payload_mutation_before_install(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "staged-tamper-provenance"
            original_write = provenance._write_new_file

            def write_then_tamper(path: Path, content: bytes):
                file_key = original_write(path, content)
                if path.name == "source-identity.json":
                    Path(path.parent, "root-ids.txt").write_bytes(b"tampered\n")
                return file_key

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_write_new_file",
                side_effect=write_then_tamper,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "staged|byte count|SHA-256|exact bytes",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".staged-tamper-provenance.*.staging")))

    def test_bundle_writer_rejects_installed_mutation_and_removes_owned_target(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "installed-tamper-provenance"
            original_rename = provenance._rename_windows_owned_handle

            def rename_then_tamper(handle: int, target: Path) -> None:
                original_rename(handle, target)
                Path(target, "root-ids.txt").write_bytes(b"installed tamper\n")

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_rename_windows_owned_handle",
                side_effect=rename_then_tamper,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "installed|held|byte count|SHA-256|exact bytes",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".installed-tamper-provenance.*.staging")))

    def test_bundle_writer_interrupt_immediately_after_move_removes_owned_target(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-move-provenance"
            original_rename = provenance._rename_windows_owned_handle

            def rename_then_interrupt(handle: int, target: Path) -> None:
                original_rename(handle, target)
                raise KeyboardInterrupt("interrupt after atomic move")

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_rename_windows_owned_handle",
                side_effect=rename_then_interrupt,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt,
                    "interrupt after atomic move",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".interrupted-move-provenance.*.staging")))

    def test_bundle_cleanup_never_recursively_deletes_a_replacement_directory(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            owned = root / ".owned.staging"
            displaced = root / ".owned-displaced.staging"
            owned.mkdir()
            owned_file = owned / "owned.bin"
            owned_file.write_bytes(b"owned")
            directory_stat = os.lstat(owned)
            expected_key = (directory_stat.st_dev, directory_stat.st_ino)
            file_stat = os.lstat(owned_file)
            expected_files = {
                "owned.bin": (file_stat.st_dev, file_stat.st_ino)
            }
            original_rmtree = shutil.rmtree
            swapped = False

            def swap_before_rmtree(path, *args, **kwargs):
                nonlocal swapped
                candidate = Path(path)
                if candidate == owned:
                    owned.rename(displaced)
                    owned.mkdir()
                    Path(owned, "replacement.bin").write_bytes(b"replacement")
                    swapped = True
                return original_rmtree(path, *args, **kwargs)

            with patch.object(
                shutil,
                "rmtree",
                side_effect=swap_before_rmtree,
            ):
                provenance._remove_owned_install_path(
                    owned,
                    expected_file_key=expected_key,
                    expected_files=expected_files,
                )

            self.assertFalse(swapped)
            self.assertFalse(owned.exists())
            self.assertFalse(displaced.exists())

    def test_bundle_writer_falls_back_to_exact_cleanup_after_child_mark_failure(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "child-mark-failure-provenance"
            original_revalidate = provenance._revalidate_verified_inputs
            original_close = provenance._close_windows_owned_handle
            original_fallback = provenance._remove_owned_install_path
            revalidation_calls = 0
            child_mark_failure_forced = False

            def fail_final_revalidation(current):
                nonlocal revalidation_calls
                revalidation_calls += 1
                if revalidation_calls == 2:
                    raise KeyboardInterrupt("interrupt after staging completed")
                return original_revalidate(current)

            def fail_first_child_mark(
                handle: int,
                *,
                delete: bool,
                directory: bool,
                retain_on_delete_failure: bool = False,
            ) -> None:
                nonlocal child_mark_failure_forced
                if delete and not directory and not child_mark_failure_forced:
                    child_mark_failure_forced = True
                    original_close(handle, delete=False, directory=False)
                    raise provenance.ProvenanceVerificationError(
                        "forced child deletion-mark failure"
                    )
                original_close(
                    handle,
                    delete=delete,
                    directory=directory,
                    retain_on_delete_failure=retain_on_delete_failure,
                )

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_revalidate_verified_inputs",
                side_effect=fail_final_revalidation,
            ), patch.object(
                provenance,
                "_close_windows_owned_handle",
                side_effect=fail_first_child_mark,
            ), patch.object(
                provenance,
                "_remove_owned_install_path",
                wraps=original_fallback,
            ) as exact_cleanup:
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt after staging completed"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            staging = list(root.glob(".child-mark-failure-provenance.*.staging"))
            self.assertTrue(child_mark_failure_forced)
            self.assertEqual(revalidation_calls, 2)
            self.assertFalse(
                staging,
                "writer stranded exact-owned staging entries: "
                + repr(
                    {
                        path.name: sorted(child.name for child in path.iterdir())
                        for path in staging
                    }
                ),
            )
            self.assertEqual(exact_cleanup.call_count, 1)
            self.assertFalse(destination.exists())

    def test_bundle_writer_rechecks_staging_immediately_before_rename(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "pre-rename-tamper-provenance"
            original_snapshot = provenance._verify_exact_directory_payloads
            snapshot_calls = 0

            def snapshot_then_mutate(directory: Path, payloads):
                nonlocal snapshot_calls
                snapshot_calls += 1
                result = original_snapshot(directory, payloads)
                if snapshot_calls == 1:
                    Path(directory, "root-ids.txt").write_bytes(
                        b"mutation during input replay\n"
                    )
                return result

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_verify_exact_directory_payloads",
                side_effect=snapshot_then_mutate,
            ), patch.object(
                provenance, "_rename_windows_owned_handle"
            ) as rename:
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "byte count|SHA-256|exact bytes",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertEqual(snapshot_calls, 2)
            rename.assert_not_called()
            self.assertFalse(destination.exists())
            self.assertFalse(list(root.glob(".pre-rename-tamper-provenance.*.staging")))

    def test_public_bundle_readback_rehashes_exact_persisted_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "readback-provenance"
            with self._current_fixture_graph(inputs):
                written = provenance.write_pilot_provenance_bundle(
                    destination, inputs
                )

            with self._current_fixture_graph(inputs):
                readback = provenance.read_pilot_provenance_bundle(destination)
            self.assertEqual(readback, written)
            self.assertEqual(
                dict(readback.document_sha256),
                {
                    path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in destination.iterdir()
                },
            )

            runtime_path = destination / "runtime-manifest.json"
            runtime_bytes = runtime_path.read_bytes()
            runtime_path.write_bytes(runtime_bytes + b"tamper")
            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "byte count|SHA-256|manifest",
                ):
                    provenance.read_pilot_provenance_bundle(destination)
            runtime_path.write_bytes(runtime_bytes)

            extra = destination / "extra.txt"
            extra.write_bytes(b"extra")
            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "exact file set",
                ):
                    provenance.read_pilot_provenance_bundle(destination)
            extra.unlink()

            root_path = destination / "bundle-root.json"
            root_document = json.loads(root_path.read_bytes())
            root_path.write_bytes(
                json.dumps(root_document, indent=2).encode("utf-8") + b"\n"
            )
            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "canonical|bundle root",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

            root_path.write_bytes(canonical_json_bytes(root_document))
            source_identity_path = destination / "source-identity.json"
            source_identity = json.loads(source_identity_path.read_bytes())
            source_identity["providerEvidence"]["localDownload"] = {
                "status": "available",
                "utc": "2026-07-11T03:42:25Z",
                "verified": True,
            }
            forged_source = canonical_json_bytes(source_identity)
            source_identity_path.write_bytes(forged_source)
            for entry in root_document["files"]:
                if entry["logicalName"] == "source-identity.json":
                    entry["bytes"] = len(forged_source)
                    entry["sha256"] = hashlib.sha256(forged_source).hexdigest()
            root_path.write_bytes(canonical_json_bytes(root_document))
            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "manifest graph|timestamp evidence",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

    def test_public_bundle_readback_rejects_external_hardlink_alias(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "readback-hardlink-provenance"
            alias = root / "readback-external-alias.bin"
            with self._current_fixture_graph(inputs):
                provenance.write_pilot_provenance_bundle(destination, inputs)
            os.link(destination / "root-ids.txt", alias)

            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "exactly one link",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

            self.assertEqual(alias.read_bytes(), inputs.selection.root_ids)

    def test_public_readback_rejects_self_consistent_forged_selector_and_wsl(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "forged-selector-provenance"
            with self._current_fixture_graph(inputs):
                provenance.write_pilot_provenance_bundle(destination, inputs)

            selector_bytes = b"def arbitrary_selector(_: bytes):\n    return b'', b''\n"
            selector_sha256 = hashlib.sha256(selector_bytes).hexdigest()
            runtime = json.loads(
                (destination / "runtime-manifest.json").read_bytes()
            )
            runtime["selector"]["sha256"] = selector_sha256
            runtime["selector"]["callableCodeSha256"] = "0" * 64
            runtime["wsl"]["kernel"] = "Linux forged-kernel x86_64 GNU/Linux"
            runtime_bytes = canonical_json_bytes(runtime)
            commands = json.loads(
                (destination / "selection-command-manifest.json").read_bytes()
            )
            commands["selectorModuleSha256"] = selector_sha256
            commands["runtimeManifestSha256"] = hashlib.sha256(
                runtime_bytes
            ).hexdigest()
            command_bytes = canonical_json_bytes(commands)
            binding = json.loads(
                (destination / "selection-binding.json").read_bytes()
            )
            binding["selector"]["sha256"] = selector_sha256
            binding["runtimeManifestSha256"] = hashlib.sha256(
                runtime_bytes
            ).hexdigest()
            binding["selectionCommandManifestSha256"] = hashlib.sha256(
                command_bytes
            ).hexdigest()
            self._rewrite_bundle_payloads(
                destination,
                {
                    "runtime-manifest.json": runtime_bytes,
                    "selection-binding.json": canonical_json_bytes(binding),
                    "selection-command-manifest.json": command_bytes,
                    "selector-source.py": selector_bytes,
                },
            )

            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "selector|runtime|WSL",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

    def test_public_readback_rejects_forged_raw_provider_head_capture(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "forged-provider-provenance"
            with self._current_fixture_graph(inputs):
                provenance.write_pilot_provenance_bundle(destination, inputs)

            forged_head = (
                b"HTTP/2 500\r\nDate: Thu, 01 Jan 1970 00:00:00 GMT\r\n"
                b"content-length: 0\r\ncontent-type: text/plain\r\n\r\n"
            )
            source_identity = json.loads(
                (destination / "source-identity.json").read_bytes()
            )
            source_identity["providerEvidence"]["headBytes"] = len(forged_head)
            source_identity["providerEvidence"]["headSha256"] = hashlib.sha256(
                forged_head
            ).hexdigest()
            source_bytes = canonical_json_bytes(source_identity)
            commands = json.loads(
                (destination / "selection-command-manifest.json").read_bytes()
            )
            commands["sourceIdentitySha256"] = hashlib.sha256(
                source_bytes
            ).hexdigest()
            command_bytes = canonical_json_bytes(commands)
            binding = json.loads(
                (destination / "selection-binding.json").read_bytes()
            )
            binding["sourceIdentitySha256"] = hashlib.sha256(
                source_bytes
            ).hexdigest()
            binding["selectionCommandManifestSha256"] = hashlib.sha256(
                command_bytes
            ).hexdigest()
            self._rewrite_bundle_payloads(
                destination,
                {
                    "provider-head.txt": forged_head,
                    "selection-binding.json": canonical_json_bytes(binding),
                    "selection-command-manifest.json": command_bytes,
                    "source-identity.json": source_bytes,
                },
            )

            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "provider HEAD|HTTP 200|content length|Date",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

    def test_public_readback_rejects_semantically_valid_forged_provider_head(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "semantically-forged-provider-provenance"
            with self._current_fixture_graph(inputs):
                provenance.write_pilot_provenance_bundle(destination, inputs)

            original = (destination / "provider-head.txt").read_bytes()
            forged = original.replace(
                b"content-type: application/octet-stream\r\n\r\n",
                b"content-type: application/octet-stream\r\n"
                b"x-forged-evidence: yes\r\n\r\n",
            )
            self.assertNotEqual(forged, original)
            source_identity = json.loads(
                (destination / "source-identity.json").read_bytes()
            )
            source_identity["providerEvidence"]["headBytes"] = len(forged)
            source_identity["providerEvidence"]["headSha256"] = hashlib.sha256(
                forged
            ).hexdigest()
            source_bytes = canonical_json_bytes(source_identity)
            commands = json.loads(
                (destination / "selection-command-manifest.json").read_bytes()
            )
            commands["sourceIdentitySha256"] = hashlib.sha256(
                source_bytes
            ).hexdigest()
            command_bytes = canonical_json_bytes(commands)
            binding = json.loads(
                (destination / "selection-binding.json").read_bytes()
            )
            binding["sourceIdentitySha256"] = hashlib.sha256(
                source_bytes
            ).hexdigest()
            binding["selectionCommandManifestSha256"] = hashlib.sha256(
                command_bytes
            ).hexdigest()
            self._rewrite_bundle_payloads(
                destination,
                {
                    "provider-head.txt": forged,
                    "selection-binding.json": canonical_json_bytes(binding),
                    "selection-command-manifest.json": command_bytes,
                    "source-identity.json": source_bytes,
                },
            )

            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "provider HEAD.*exact lock",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

    def test_public_readback_recomputes_all_manifest_cross_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "stale-cross-hash-provenance"
            with self._current_fixture_graph(inputs):
                provenance.write_pilot_provenance_bundle(destination, inputs)

            source_identity = json.loads(
                (destination / "source-identity.json").read_bytes()
            )
            source_identity["providerEvidence"]["fileinfoCommand"][
                "binarySha256"
            ] = "0" * 64
            commands = json.loads(
                (destination / "selection-command-manifest.json").read_bytes()
            )
            commands["runtimeManifestSha256"] = "1" * 64
            commands["sourceIdentitySha256"] = "2" * 64
            binding = json.loads(
                (destination / "selection-binding.json").read_bytes()
            )
            binding["runtimeManifestSha256"] = "3" * 64
            binding["selectionCommandManifestSha256"] = "4" * 64
            binding["sourceIdentitySha256"] = "5" * 64
            self._rewrite_bundle_payloads(
                destination,
                {
                    "selection-binding.json": canonical_json_bytes(binding),
                    "selection-command-manifest.json": canonical_json_bytes(commands),
                    "source-identity.json": canonical_json_bytes(source_identity),
                },
            )

            with self._current_fixture_graph(inputs):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "cross-hash|manifest graph|source identity",
                ):
                    provenance.read_pilot_provenance_bundle(destination)

    def test_bundle_writer_interrupt_during_staging_lstat_cleans_owned_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-staging-lstat-provenance"
            original_lstat = os.lstat
            interrupted = False

            def interrupt_staging_lstat(path: str | os.PathLike[str]):
                nonlocal interrupted
                candidate = Path(path)
                if (
                    not interrupted
                    and candidate.parent == root
                    and candidate.name.endswith(".staging")
                ):
                    interrupted = True
                    raise KeyboardInterrupt("interrupt during staging lstat")
                return original_lstat(path)

            with self._current_fixture_graph(inputs), patch.object(
                provenance.os,
                "lstat",
                side_effect=interrupt_staging_lstat,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt during staging lstat"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".interrupted-staging-lstat-provenance.*.staging"))
            )

    def test_first_staging_lstat_cleanup_preserves_an_empty_replacement_directory(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "replaced-first-staging-lstat-provenance"
            original_lstat = os.lstat
            interrupt = KeyboardInterrupt("interrupt after first staging lstat replacement")
            replacement: Path | None = None

            def replace_then_interrupt(path: str | os.PathLike[str]):
                nonlocal replacement
                candidate = Path(path)
                if (
                    replacement is None
                    and candidate.parent == root
                    and candidate.name.endswith(".staging")
                ):
                    displaced = candidate.with_name(candidate.name + ".displaced")
                    candidate.rename(displaced)
                    candidate.mkdir()
                    displaced.rmdir()
                    replacement = candidate
                    raise interrupt
                return original_lstat(path)

            with self._current_fixture_graph(inputs), patch.object(
                provenance.os,
                "lstat",
                side_effect=replace_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIs(raised.exception, interrupt)
            self.assertIsNotNone(replacement)
            assert replacement is not None
            self.assertTrue(replacement.is_dir())
            self.assertEqual(list(replacement.iterdir()), [])
            self.assertFalse(destination.exists())

    def test_post_child_handle_close_interrupt_removes_displaced_owned_directory_and_preserves_replacement(
        self,
    ) -> None:
        rename_owned = getattr(provenance, "_rename_windows_owned_handle", None)
        self.assertIsNotNone(rename_owned, "handle-authoritative rename is missing")
        if rename_owned is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "displaced-after-first-child-provenance"
            interrupt = KeyboardInterrupt("interrupt after displaced first child")
            displaced: Path | None = None
            replacement: Path | None = None

            def displace_replace_then_interrupt(handle: int, target: Path):
                nonlocal displaced, replacement
                active = provenance._ACTIVE_BUNDLE_WRITE_RECEIPTS.get()
                self.assertIsInstance(active, provenance._OwnedStagingDirectory)
                assert isinstance(active, provenance._OwnedStagingDirectory)
                self.assertTrue(active.files)
                self.assertTrue(
                    all(child.handle is None for child in active.files.values())
                )
                candidates = list(
                    root.glob(".displaced-after-first-child-provenance.*.staging")
                )
                self.assertEqual(len(candidates), 1)
                candidate = candidates[0]
                displaced = candidate.with_name(candidate.name + ".displaced")
                rename_owned(handle, displaced)
                candidate.mkdir()
                replacement = candidate
                raise interrupt

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_rename_windows_owned_handle",
                side_effect=displace_replace_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIs(raised.exception, interrupt)
            self.assertIsNotNone(displaced)
            self.assertIsNotNone(replacement)
            assert displaced is not None and replacement is not None
            self.assertFalse(displaced.exists())
            self.assertTrue(replacement.is_dir())
            self.assertEqual(list(replacement.iterdir()), [])
            self.assertFalse(destination.exists())

    def test_publication_reopens_every_exact_child_and_holds_handles_through_readback(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "exact-reopen-provenance"
            original_factory = provenance._create_owned_staging_directory
            original_acquire = provenance._acquire_owned_relative_handle
            original_readback = provenance.read_pilot_provenance_bundle
            reopened: dict[str, object] = {}
            parent_handles: set[int] = set()
            staging_receipts: list[object] = []

            def capture_factory(receipt, *args, **kwargs):
                staging_receipts.append(receipt)
                return original_factory(receipt, *args, **kwargs)

            def track_acquire(receipt, parent_handle, name, **kwargs):
                expected_key = receipt.file_key
                result = original_acquire(
                    receipt, parent_handle, name, **kwargs
                )
                if not kwargs["create"] and name not in reopened:
                    self.assertEqual(receipt.file_key, expected_key)
                    self.assertIsNotNone(receipt.handle)
                    reopened[name] = receipt
                    parent_handles.add(parent_handle)
                return result

            def assert_held_then_read(path: Path):
                expected = set(provenance._BUNDLE_PAYLOAD_NAMES) | {
                    "bundle-root.json"
                }
                self.assertEqual(set(reopened), expected)
                self.assertEqual(len(parent_handles), 1)
                self.assertTrue(
                    all(receipt.handle is not None for receipt in reopened.values())
                )
                return original_readback(path)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_create_owned_staging_directory",
                side_effect=capture_factory,
            ), patch.object(
                provenance,
                "_acquire_owned_relative_handle",
                side_effect=track_acquire,
            ), patch.object(
                provenance,
                "read_pilot_provenance_bundle",
                side_effect=assert_held_then_read,
            ):
                provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertTrue(reopened)
            self.assertTrue(
                all(receipt.handle is None for receipt in reopened.values())
            )
            self.assertEqual(len(staging_receipts), 1)
            self.assertIsNone(staging_receipts[0].handle)

    def test_publication_child_identity_mismatch_preserves_same_name_replacement(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "replaced-published-child-provenance"
            original_rename = provenance._rename_windows_owned_handle
            replacement = b"unowned replacement\n"

            def rename_then_replace_child(handle: int, target: Path):
                original_rename(handle, target)
                original = target / "root-ids.txt"
                original.unlink()
                original.write_bytes(replacement)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_rename_windows_owned_handle",
                side_effect=rename_then_replace_child,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "identity changed across publication",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertTrue(destination.is_dir())
            self.assertEqual(
                (destination / "root-ids.txt").read_bytes(), replacement
            )
            self.assertEqual(
                [path.name for path in destination.iterdir()], ["root-ids.txt"]
            )

    def test_installed_byte_identical_child_replacement_after_reopen_is_rejected(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "byte-identical-installed-replacement-provenance"
            original_publish = provenance._publish_owned_directory
            replacement_bytes: bytes | None = None

            def publish_then_replace(receipt, target: Path):
                nonlocal replacement_bytes
                original_publish(receipt, target)
                held = receipt.files["root-ids.txt"]
                self.assertIsNotNone(held.handle)
                self.assertIsNotNone(held.file_key)
                child = target / "root-ids.txt"
                replacement_bytes = child.read_bytes()
                child.unlink()
                child.write_bytes(replacement_bytes)
                replacement_stat = os.lstat(child)
                self.assertNotEqual(
                    (replacement_stat.st_dev, replacement_stat.st_ino),
                    held.file_key,
                )

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_publish_owned_directory",
                side_effect=publish_then_replace,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "identity|FileId|held",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIsNotNone(replacement_bytes)
            assert replacement_bytes is not None
            self.assertTrue(destination.is_dir())
            self.assertEqual(
                (destination / "root-ids.txt").read_bytes(), replacement_bytes
            )
            self.assertEqual(
                [path.name for path in destination.iterdir()], ["root-ids.txt"]
            )

    def test_native_staging_create_interrupt_before_status_receipt_cleans_directory(
        self,
    ) -> None:
        factory = getattr(provenance, "_create_owned_staging_directory", None)
        receipt_type = getattr(provenance, "_OwnedStagingDirectory", None)
        native_create = getattr(provenance, "_nt_create_file", None)
        self.assertIsNotNone(factory, "atomic staging ownership factory is missing")
        self.assertIsNotNone(receipt_type, "staging ownership receipt is missing")
        self.assertIsNotNone(native_create, "native directory creator is missing")
        if factory is None or receipt_type is None or native_create is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            interrupt = KeyboardInterrupt("interrupt before native status receipt")

            def create_then_interrupt(*args):
                native_create(*args)
                raise interrupt

            with patch.object(
                provenance,
                "_nt_create_file",
                side_effect=create_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    factory(
                        receipt_type(),
                        root,
                        prefix=".native-pre-receipt.",
                        suffix=".staging",
                    )

            self.assertIs(raised.exception, interrupt)
            self.assertFalse(list(root.glob(".native-pre-receipt.*.staging")))

    def test_writer_retains_created_directory_when_pre_status_delete_mark_fails(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "retained-native-directory-provenance"
            native_create = provenance._nt_create_file
            original_close = provenance._close_windows_owned_handle
            original_factory = provenance._create_owned_staging_directory
            interrupt = KeyboardInterrupt(
                "interrupt after native directory FILE_CREATED"
            )
            receipts: list[object] = []
            interrupted = False
            delete_failure_forced = False

            def capture_factory(receipt, *args, **kwargs):
                receipts.append(receipt)
                return original_factory(receipt, *args, **kwargs)

            def create_then_interrupt(*args):
                nonlocal interrupted
                status = native_create(*args)
                create_options = int(args[8].value)
                if status == 0 and create_options & 0x1 and not interrupted:
                    self.assertEqual(args[3]._obj.information, 2)
                    interrupted = True
                    raise interrupt
                return status

            def fail_first_delete(
                handle: int,
                *,
                delete: bool,
                directory: bool,
                retain_on_delete_failure: bool = False,
            ) -> None:
                nonlocal delete_failure_forced
                if delete and directory and not delete_failure_forced:
                    delete_failure_forced = True
                    error = provenance.ProvenanceVerificationError(
                        "forced native directory deletion-mark failure"
                    )
                    if retain_on_delete_failure:
                        error.owned_handle_retained = True
                    else:
                        original_close(handle, delete=False, directory=True)
                    raise error
                original_close(handle, delete=delete, directory=directory)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_create_owned_staging_directory",
                side_effect=capture_factory,
            ), patch.object(
                provenance,
                "_nt_create_file",
                side_effect=create_then_interrupt,
            ), patch.object(
                provenance,
                "_close_windows_owned_handle",
                side_effect=fail_first_delete,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIs(raised.exception, interrupt)
            self.assertTrue(interrupted)
            self.assertTrue(delete_failure_forced)
            self.assertEqual(len(receipts), 1)
            self.assertIsNone(receipts[0].handle)
            self.assertIsNotNone(receipts[0].file_key)
            self.assertFalse(receipts[0].created_unresolved)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".retained-native-directory-provenance.*.staging"))
            )

    def test_writer_retains_created_child_when_pre_status_delete_mark_fails(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "retained-native-child-provenance"
            native_create = provenance._nt_create_file
            original_close = provenance._close_windows_owned_handle
            original_factory = provenance._create_owned_staging_directory
            interrupt = KeyboardInterrupt("interrupt after native child FILE_CREATED")
            receipts: list[object] = []
            interrupted = False
            delete_failure_forced = False

            def capture_factory(receipt, *args, **kwargs):
                receipts.append(receipt)
                return original_factory(receipt, *args, **kwargs)

            def create_then_interrupt(*args):
                nonlocal interrupted
                status = native_create(*args)
                create_options = int(args[8].value)
                if status == 0 and create_options & 0x40 and not interrupted:
                    self.assertEqual(args[3]._obj.information, 2)
                    interrupted = True
                    raise interrupt
                return status

            def fail_first_delete(
                handle: int,
                *,
                delete: bool,
                directory: bool,
                retain_on_delete_failure: bool = False,
            ) -> None:
                nonlocal delete_failure_forced
                if delete and not directory and not delete_failure_forced:
                    delete_failure_forced = True
                    error = provenance.ProvenanceVerificationError(
                        "forced native child deletion-mark failure"
                    )
                    if retain_on_delete_failure:
                        error.owned_handle_retained = True
                    else:
                        original_close(handle, delete=False, directory=False)
                    raise error
                original_close(handle, delete=delete, directory=directory)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_create_owned_staging_directory",
                side_effect=capture_factory,
            ), patch.object(
                provenance,
                "_nt_create_file",
                side_effect=create_then_interrupt,
            ), patch.object(
                provenance,
                "_close_windows_owned_handle",
                side_effect=fail_first_delete,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIs(raised.exception, interrupt)
            self.assertTrue(interrupted)
            self.assertTrue(delete_failure_forced)
            self.assertEqual(len(receipts), 1)
            self.assertEqual(tuple(receipts[0].files), ("bundle-root.json",))
            child_receipt = receipts[0].files["bundle-root.json"]
            self.assertIsNone(child_receipt.handle)
            self.assertIsNotNone(child_receipt.file_key)
            self.assertFalse(child_receipt.created_unresolved)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".retained-native-child-provenance.*.staging"))
            )

    def test_compound_cleanup_error_retains_exact_recoverable_receipts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "compound-receipt-provenance"
            native_create = provenance._nt_create_file
            original_close = provenance._close_windows_owned_handle
            original_factory = provenance._create_owned_staging_directory
            interrupt = KeyboardInterrupt("interrupt after child FILE_CREATED")
            receipts: list[object] = []
            interrupted = False

            def capture_factory(receipt, *args, **kwargs):
                receipts.append(receipt)
                return original_factory(receipt, *args, **kwargs)

            def create_then_interrupt(*args):
                nonlocal interrupted
                status = native_create(*args)
                create_options = int(args[8].value)
                if status == 0 and create_options & 0x40 and not interrupted:
                    self.assertEqual(args[3]._obj.information, 2)
                    interrupted = True
                    raise interrupt
                return status

            def fail_every_delete(
                handle: int,
                *,
                delete: bool,
                directory: bool,
                retain_on_delete_failure: bool = False,
            ) -> None:
                if delete:
                    error = provenance.ProvenanceVerificationError(
                        "forced persistent deletion-mark failure"
                    )
                    if retain_on_delete_failure:
                        error.owned_handle_retained = True
                    else:
                        original_close(
                            handle, delete=False, directory=directory
                        )
                    raise error
                original_close(handle, delete=False, directory=directory)

            def fail_exact_cleanup(*args, **kwargs):
                raise provenance.ProvenanceVerificationError(
                    "forced exact path cleanup failure"
                )

            try:
                with self._current_fixture_graph(inputs), patch.object(
                    provenance,
                    "_create_owned_staging_directory",
                    side_effect=capture_factory,
                ), patch.object(
                    provenance,
                    "_nt_create_file",
                    side_effect=create_then_interrupt,
                ), patch.object(
                    provenance,
                    "_close_windows_owned_handle",
                    side_effect=fail_every_delete,
                ), patch.object(
                    provenance,
                    "_remove_owned_install_path",
                    side_effect=fail_exact_cleanup,
                ):
                    with self.assertRaises(
                        provenance.ProvenanceVerificationError
                    ) as raised:
                        provenance.write_pilot_provenance_bundle(
                            destination, inputs
                        )

                self.assertTrue(interrupted)
                self.assertEqual(len(receipts), 1)
                directory_receipt = receipts[0]
                self.assertIsNotNone(directory_receipt.path)
                self.assertIsNotNone(directory_receipt.handle)
                self.assertIsNotNone(directory_receipt.file_key)
                self.assertFalse(directory_receipt.created_unresolved)
                self.assertEqual(tuple(directory_receipt.files), ("bundle-root.json",))
                child_receipt = directory_receipt.files["bundle-root.json"]
                self.assertIsNotNone(child_receipt.handle)
                self.assertIsNotNone(child_receipt.file_key)
                self.assertFalse(child_receipt.created_unresolved)
                self.assertEqual(
                    raised.exception.owned_receipts,
                    (
                        (
                            ".",
                            directory_receipt.handle,
                            directory_receipt.file_key,
                            False,
                        ),
                        (
                            "bundle-root.json",
                            child_receipt.handle,
                            child_receipt.file_key,
                            False,
                        ),
                    ),
                )
            finally:
                if receipts:
                    directory_receipt = receipts[0]
                    for child_receipt in directory_receipt.files.values():
                        if child_receipt.handle is not None:
                            original_close(
                                child_receipt.handle,
                                delete=True,
                                directory=False,
                            )
                            child_receipt.handle = None
                    if directory_receipt.handle is not None:
                        original_close(
                            directory_receipt.handle,
                            delete=True,
                            directory=True,
                        )
                        directory_receipt.handle = None

    def test_native_child_create_interrupt_before_status_receipt_cleans_owned_tree(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-native-child-create-provenance"
            native_create = provenance._nt_create_file
            interrupt = KeyboardInterrupt("interrupt before native child status receipt")
            interrupted = False

            def create_child_then_interrupt(*args):
                nonlocal interrupted
                status = native_create(*args)
                create_options = int(args[8].value)
                if status == 0 and create_options & 0x40:
                    interrupted = True
                    raise interrupt
                return status

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_nt_create_file",
                side_effect=create_child_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIs(raised.exception, interrupt)
            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".interrupted-native-child-create-provenance.*.staging"))
            )

    def test_native_staging_non_file_created_success_deletes_exact_directory(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt = provenance._OwnedStagingDirectory()
            native_create = provenance._nt_create_file
            modified = False

            def return_non_file_created(*args):
                nonlocal modified
                status = native_create(*args)
                create_options = int(args[8].value)
                if status == 0 and create_options & 0x1:
                    args[3]._obj.information = 1
                    modified = True
                return status

            with patch.object(
                provenance,
                "_nt_create_file",
                side_effect=return_non_file_created,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "native staging directory creation was not exact",
                ):
                    provenance._create_owned_staging_directory(
                        receipt,
                        root,
                        prefix=".native-non-created.",
                        suffix=".staging",
                    )

            self.assertTrue(modified)
            self.assertIsNone(receipt.handle)
            self.assertFalse(list(root.glob(".native-non-created.*.staging")))

    def test_staging_creation_interrupt_before_identity_receipt_cleans_owned_directory(
        self,
    ) -> None:
        factory = getattr(provenance, "_create_owned_staging_directory", None)
        self.assertIsNotNone(factory, "atomic staging ownership factory is missing")
        if factory is None:
            return
        receipt_type = getattr(provenance, "_OwnedStagingDirectory", None)
        self.assertIsNotNone(receipt_type, "staging ownership receipt is missing")
        if receipt_type is None:
            return
        identity = getattr(provenance, "_windows_directory_identity", None)
        self.assertIsNotNone(identity, "held directory identity query is missing")
        if identity is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            interrupt = KeyboardInterrupt("interrupt before staging identity receipt")
            interrupted = False

            def interrupt_identity_receipt(handle: int):
                nonlocal interrupted
                if (
                    not interrupted
                    and list(root.glob(".pre-receipt.*.staging"))
                ):
                    interrupted = True
                    raise interrupt
                return identity(handle)

            with patch.object(
                provenance,
                "_windows_directory_identity",
                side_effect=interrupt_identity_receipt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    factory(
                        receipt_type(),
                        root,
                        prefix=".pre-receipt.",
                        suffix=".staging",
                    )

            self.assertIs(raised.exception, interrupt)
            self.assertTrue(interrupted)
            self.assertFalse(list(root.glob(".pre-receipt.*.staging")))

    def test_writer_recovers_compound_pre_receipt_identity_and_delete_failure(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "compound-pre-receipt-provenance"
            original_factory = provenance._create_owned_staging_directory
            original_identity = provenance._windows_directory_identity
            original_close = provenance._close_windows_owned_handle
            interrupt = KeyboardInterrupt(
                "interrupt before compound pre-receipt FileId"
            )
            receipts: list[object] = []
            identity_calls = 0
            deletion_failures = 0

            def capture_factory(receipt, *args, **kwargs):
                receipts.append(receipt)
                return original_factory(receipt, *args, **kwargs)

            def interrupt_first_identity(handle: int):
                nonlocal identity_calls
                identity_calls += 1
                if identity_calls == 1:
                    raise interrupt
                return original_identity(handle)

            def fail_first_delete(
                handle: int,
                *,
                delete: bool,
                directory: bool,
                retain_on_delete_failure: bool = False,
            ) -> None:
                nonlocal deletion_failures
                if delete and directory and deletion_failures == 0:
                    deletion_failures += 1
                    error = provenance.ProvenanceVerificationError(
                        "forced pre-receipt deletion-mark failure"
                    )
                    if retain_on_delete_failure:
                        error.owned_handle_retained = True
                    else:
                        original_close(
                            handle, delete=False, directory=directory
                        )
                    raise error
                original_close(handle, delete=delete, directory=directory)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_create_owned_staging_directory",
                side_effect=capture_factory,
            ), patch.object(
                provenance,
                "_windows_directory_identity",
                side_effect=interrupt_first_identity,
            ), patch.object(
                provenance,
                "_close_windows_owned_handle",
                side_effect=fail_first_delete,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            staging = list(
                root.glob(".compound-pre-receipt-provenance.*.staging")
            )
            self.assertIs(raised.exception, interrupt)
            self.assertEqual(deletion_failures, 1)
            self.assertFalse(
                staging,
                "compound pre-receipt failure stranded exact-created staging",
            )
            self.assertGreaterEqual(identity_calls, 2)
            self.assertEqual(len(receipts), 1)
            self.assertIsNone(receipts[0].handle)
            self.assertIsNotNone(receipts[0].file_key)
            self.assertFalse(receipts[0].created_unresolved)
            self.assertTrue(
                any(
                    "forced pre-receipt deletion-mark failure" in note
                    for note in getattr(interrupt, "__notes__", ())
                )
            )
            self.assertFalse(destination.exists())

    def test_staging_creation_interrupt_before_identity_preserves_replacement(
        self,
    ) -> None:
        factory = getattr(provenance, "_create_owned_staging_directory", None)
        self.assertIsNotNone(factory, "atomic staging ownership factory is missing")
        if factory is None:
            return
        receipt_type = getattr(provenance, "_OwnedStagingDirectory", None)
        self.assertIsNotNone(receipt_type, "staging ownership receipt is missing")
        if receipt_type is None:
            return
        identity = getattr(provenance, "_windows_directory_identity", None)
        self.assertIsNotNone(identity, "held directory identity query is missing")
        if identity is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            interrupt = KeyboardInterrupt(
                "interrupt after pre-receipt staging replacement"
            )
            replacement: Path | None = None

            def replace_then_interrupt(handle: int):
                nonlocal replacement
                candidates = list(root.glob(".replaced-pre-receipt.*.staging"))
                if replacement is None and candidates:
                    self.assertEqual(len(candidates), 1)
                    candidate = candidates[0]
                    displaced = candidate.with_name(candidate.name + ".displaced")
                    candidate.rename(displaced)
                    candidate.mkdir()
                    displaced.rmdir()
                    replacement = candidate
                    raise interrupt
                return identity(handle)

            with patch.object(
                provenance,
                "_windows_directory_identity",
                side_effect=replace_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    factory(
                        receipt_type(),
                        root,
                        prefix=".replaced-pre-receipt.",
                        suffix=".staging",
                    )

            self.assertIs(raised.exception, interrupt)
            self.assertIsNotNone(replacement)
            assert replacement is not None
            self.assertTrue(replacement.is_dir())
            self.assertEqual(list(replacement.iterdir()), [])

    def test_bundle_writer_interrupt_after_staging_factory_cleans_owned_directory(
        self,
    ) -> None:
        factory = getattr(provenance, "_create_owned_staging_directory", None)
        self.assertIsNotNone(factory, "atomic staging ownership factory is missing")
        if factory is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-after-staging-factory-provenance"
            interrupt = KeyboardInterrupt("interrupt after staging factory")

            def create_then_interrupt(*args, **kwargs):
                factory(*args, **kwargs)
                raise interrupt

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_create_owned_staging_directory",
                side_effect=create_then_interrupt,
            ):
                with self.assertRaises(KeyboardInterrupt) as raised:
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIs(raised.exception, interrupt)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(
                    root.glob(
                        ".interrupted-after-staging-factory-provenance.*.staging"
                    )
                )
            )

    def test_bundle_writer_interrupt_after_file_creation_keeps_original_and_cleans(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-file-receipt-provenance"
            original_write = provenance._write_new_file

            def write_then_interrupt(path: Path, content: bytes):
                original_write(path, content)
                raise KeyboardInterrupt("interrupt before ownership receipt")

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_write_new_file",
                side_effect=write_then_interrupt,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt before ownership receipt"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".interrupted-file-receipt-provenance.*.staging"))
            )

    def test_bundle_writer_interrupt_in_first_active_file_identity_cleans_created_file(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-first-active-fstat-provenance"
            original_identity = provenance._windows_directory_identity
            interrupted = False

            def interrupt_first_active_identity(handle: int):
                nonlocal interrupted
                if (
                    not interrupted
                    and provenance._ACTIVE_BUNDLE_WRITE_RECEIPTS.get() is not None
                ):
                    interrupted = True
                    staging = list(
                        root.glob(
                            ".interrupted-first-active-fstat-provenance.*.staging"
                        )
                    )
                    self.assertEqual(len(staging), 1)
                    self.assertTrue((staging[0] / "bundle-root.json").exists())
                    raise KeyboardInterrupt("interrupt in first active file identity")
                return original_identity(handle)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_windows_directory_identity",
                side_effect=interrupt_first_active_identity,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt in first active file identity"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(
                        root.glob(
                            ".interrupted-first-active-fstat-provenance.*.staging"
                        )
                )
            )

    def test_bundle_writer_interrupt_in_initial_staging_stat_cleans_empty_directory(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-initial-staging-stat-provenance"
            original_stat = os.stat
            interrupted = False

            def interrupt_initial_stat(path, *args, **kwargs):
                nonlocal interrupted
                candidate = Path(path)
                if (
                    not interrupted
                    and candidate.parent == root
                    and candidate.name.endswith(".staging")
                ):
                    interrupted = True
                    raise KeyboardInterrupt("interrupt in initial staging stat")
                return original_stat(path, *args, **kwargs)

            with self._current_fixture_graph(inputs), patch.object(
                provenance.os,
                "stat",
                side_effect=interrupt_initial_stat,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt in initial staging stat"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertTrue(interrupted)
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(
                    root.glob(
                        ".interrupted-initial-staging-stat-provenance.*.staging"
                    )
                )
            )

    def test_initial_staging_stat_cleanup_preserves_an_empty_replacement_directory(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "replaced-initial-staging-stat-provenance"
            original_stat = os.stat
            replacement: Path | None = None

            def replace_then_interrupt(path, *args, **kwargs):
                nonlocal replacement
                candidate = Path(path)
                if (
                    replacement is None
                    and candidate.parent == root
                    and candidate.name.endswith(".staging")
                ):
                    displaced = candidate.with_name(candidate.name + ".displaced")
                    candidate.rename(displaced)
                    candidate.mkdir()
                    displaced.rmdir()
                    replacement = candidate
                    raise KeyboardInterrupt("interrupt after staging replacement")
                return original_stat(path, *args, **kwargs)

            with self._current_fixture_graph(inputs), patch.object(
                provenance.os,
                "stat",
                side_effect=replace_then_interrupt,
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt after staging replacement"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertIsNotNone(replacement)
            assert replacement is not None
            self.assertTrue(replacement.is_dir())
            self.assertEqual(list(replacement.iterdir()), [])
            self.assertFalse(destination.exists())

    def test_bundle_writer_interrupt_during_public_readback_removes_owned_install(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "interrupted-readback-provenance"

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "read_pilot_provenance_bundle",
                side_effect=KeyboardInterrupt("interrupt during public readback"),
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt during public readback"
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".interrupted-readback-provenance.*.staging"))
            )

    def test_bundle_writer_never_returns_stale_success_after_journal_clear(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "clear-boundary-provenance"
            payload = inputs.provider.final_url_file.content
            self.assertIsNotNone(payload)
            assert payload is not None
            overwrite = b"https://example.invalid/changed-after-readback.pbf\n"
            original_clear = osm_pilot_bundle._PublicationJournal.clear
            clear_calls = 0
            overwrite_succeeded = False
            overwrite_errors: list[OSError] = []

            def clear_then_overwrite(journal) -> None:
                nonlocal clear_calls, overwrite_succeeded
                clear_calls += 1
                original_clear(journal)
                try:
                    (destination / "provider-final-url.txt").write_bytes(overwrite)
                except OSError as error:
                    overwrite_errors.append(error)
                else:
                    overwrite_succeeded = True

            result = None
            rejected = None
            with self._current_fixture_graph(inputs), patch.object(
                osm_pilot_bundle._PublicationJournal,
                "clear",
                new=clear_then_overwrite,
            ):
                try:
                    result = provenance.write_pilot_provenance_bundle(
                        destination, inputs
                    )
                except provenance.ProvenanceVerificationError as error:
                    rejected = error

            self.assertGreaterEqual(clear_calls, 1)
            if rejected is not None:
                self.assertTrue(
                    overwrite_succeeded,
                    "writer rejected without observing the clear-boundary mutation",
                )
                self.assertFalse(destination.exists())
                return

            self.assertIsNotNone(result)
            assert result is not None
            self.assertFalse(
                overwrite_succeeded,
                "writer returned success after its verified payload was overwritten",
            )
            self.assertEqual(len(overwrite_errors), 1)
            final_bytes = (destination / "provider-final-url.txt").read_bytes()
            self.assertEqual(final_bytes, payload)
            self.assertEqual(
                dict(result.document_sha256)["provider-final-url.txt"],
                hashlib.sha256(final_bytes).hexdigest(),
            )

    def test_bundle_writer_rejects_external_alias_at_final_input_boundary(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "terminal-input-boundary-provenance"
            alias = root / "terminal-input-boundary-alias.bin"
            original_verify = provenance._verify_held_input_bindings
            verify_calls = 0

            def link_during_second_verify(held) -> None:
                nonlocal verify_calls
                verify_calls += 1
                if verify_calls == 2:
                    os.link(destination / "root-ids.txt", alias)
                original_verify(held)

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_verify_held_input_bindings",
                side_effect=link_during_second_verify,
            ), self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exactly one link",
            ):
                provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertEqual(verify_calls, 2)
            self.assertFalse(destination.exists())
            self.assertEqual(alias.read_bytes(), inputs.selection.root_ids)

    def test_publication_journal_recovers_every_durable_boundary(self) -> None:
        journal_type = getattr(osm_pilot_bundle, "_PublicationJournal", None)
        self.assertIsNotNone(
            journal_type, "durable publication journal is missing"
        )
        if journal_type is None:
            return
        payloads = {"a.bin": b"alpha", "b.bin": b"beta"}
        boundaries = (
            "intent",
            "building",
            "staged",
            "renaming-before-move",
            "renaming-after-move",
            "installed",
            "accepted",
        )
        accepted_boundaries = {
            "renaming-after-move",
            "installed",
            "accepted",
        }

        for boundary in boundaries:
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                target = parent / "provenance"
                staging = parent / ".provenance.seed.staging"
                with journal_type(parent) as journal:
                    journal.begin(target, payloads)
                    if boundary != "intent":
                        staging.mkdir()
                        staging_stat = os.lstat(staging)
                        journal.record_staging(
                            staging,
                            (staging_stat.st_dev, staging_stat.st_ino),
                        )
                        names = ("a.bin",) if boundary == "building" else tuple(payloads)
                        for name in names:
                            child = staging / name
                            child.write_bytes(payloads[name])
                            child_stat = os.lstat(child)
                            journal.record_file(
                                name, (child_stat.st_dev, child_stat.st_ino)
                            )
                        if boundary != "building":
                            journal.mark_staged()
                        if boundary.startswith("renaming") or boundary in {
                            "installed",
                            "accepted",
                        }:
                            journal.mark_renaming()
                        if boundary in accepted_boundaries:
                            staging.rename(target)
                        if boundary in {"installed", "accepted"}:
                            journal.mark_installed()
                        if boundary == "accepted":
                            journal.mark_accepted()

                accepted_paths: list[Path] = []

                def accept(path: Path):
                    accepted_paths.append(path)
                    return tuple(
                        (item.logical_name, item.sha256)
                        for item in osm_pilot_bundle._verify_exact_directory_payloads(
                            path, payloads
                        )
                    )

                with journal_type(parent) as restarted:
                    recovered = restarted.recover(target, accept)

                if boundary in accepted_boundaries:
                    self.assertTrue(target.is_dir())
                    self.assertEqual(accepted_paths, [target])
                    self.assertEqual(
                        recovered,
                        tuple(
                            (name, hashlib.sha256(payloads[name]).hexdigest())
                            for name in sorted(payloads)
                        ),
                    )
                else:
                    self.assertFalse(target.exists())
                    self.assertFalse(staging.exists())
                    self.assertEqual(accepted_paths, [])
                    self.assertIsNone(recovered)

                with journal_type(parent) as repeated:
                    self.assertIsNone(repeated.recover(target, accept))
                self.assertFalse(
                    list(
                        parent.glob(
                            ".flight-alert-exp8-provenance-publication.*.json"
                        )
                    )
                )

    def test_publication_recovery_holds_exact_payload_bytes_through_accept_and_clear(
        self,
    ) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        payloads = {"a.bin": b"alpha"}
        alpha_digest = hashlib.sha256(b"alpha").hexdigest()
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "held-recovery-target"
            staging = parent / ".held-recovery.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, payloads)
                staging.mkdir()
                staging_stat = os.lstat(staging)
                journal.record_staging(
                    staging, (staging_stat.st_dev, staging_stat.st_ino)
                )
                child = staging / "a.bin"
                child.write_bytes(payloads["a.bin"])
                child_stat = os.lstat(child)
                journal.record_file(
                    "a.bin", (child_stat.st_dev, child_stat.st_ino)
                )
                journal.mark_staged()
                journal.mark_renaming()
                staging.rename(target)
                journal.mark_installed()

            mutation_errors: list[OSError] = []

            def accept(path: Path):
                accepted = tuple(
                    (item.logical_name, item.sha256)
                    for item in osm_pilot_bundle._verify_exact_directory_payloads(
                        path, payloads
                    )
                )
                try:
                    (path / "a.bin").write_bytes(b"omega")
                except OSError as error:
                    mutation_errors.append(error)
                return accepted

            with journal_type(parent) as restarted:
                original_clear = restarted.clear

                def clear_while_attempting_mutation() -> None:
                    try:
                        (target / "a.bin").write_bytes(b"omega")
                    except OSError as error:
                        mutation_errors.append(error)
                    original_clear()

                with patch.object(
                    restarted,
                    "clear",
                    side_effect=clear_while_attempting_mutation,
                ):
                    recovered = restarted.recover(target, accept)

            self.assertEqual(
                len(mutation_errors),
                2,
                "recovery payload was write-shareable during accept or slot clear",
            )
            self.assertEqual((target / "a.bin").read_bytes(), b"alpha")
            self.assertEqual(recovered, (("a.bin", alpha_digest),))
            self.assertFalse(
                list(
                    parent.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_publication_recovery_restarts_after_accepted_commit_before_clear(
        self,
    ) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        payloads = {"a.bin": b"alpha"}
        expected = (("a.bin", hashlib.sha256(b"alpha").hexdigest()),)
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "accepted-commit-crash-target"
            staging = parent / ".accepted-commit-crash.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, payloads)
                staging.mkdir()
                staging_raw = os.lstat(staging)
                journal.record_staging(
                    staging, (staging_raw.st_dev, staging_raw.st_ino)
                )
                child = staging / "a.bin"
                child.write_bytes(b"alpha")
                child_raw = os.lstat(child)
                journal.record_file(
                    "a.bin", (child_raw.st_dev, child_raw.st_ino)
                )
                journal.mark_staged()
                journal.mark_renaming()
                staging.rename(target)

            def accept(path: Path):
                return tuple(
                    (item.logical_name, item.sha256)
                    for item in osm_pilot_bundle._verify_exact_directory_payloads(
                        path, payloads
                    )
                )

            with journal_type(parent) as restarted, patch.object(
                restarted,
                "clear",
                side_effect=KeyboardInterrupt("crash after accepted commit"),
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "crash after accepted commit"
                ):
                    restarted.recover(target, accept)

            with journal_type(parent) as repeated:
                recovered = repeated.recover(target, accept)
            self.assertEqual(recovered, expected)
            self.assertEqual((target / "a.bin").read_bytes(), b"alpha")
            self.assertFalse(
                list(
                    parent.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_publication_slot_is_not_exposed_before_canonical_bytes_exist(self) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "canonical-crash-target"
            with journal_type(parent) as journal, patch.object(
                osm_pilot_bundle,
                "canonical_json_bytes",
                side_effect=KeyboardInterrupt("interrupt before canonical bytes"),
            ):
                with self.assertRaisesRegex(
                    KeyboardInterrupt, "interrupt before canonical bytes"
                ):
                    journal.begin(target, {"a.bin": b"alpha"})

            self.assertFalse(
                list(
                    parent.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                ),
                "a truncated canonical slot escaped an interrupted first commit",
            )
            self.assertFalse(list(parent.glob("*.tmp")))
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))

    def test_publication_slot_preserves_older_generation_when_next_encode_crashes(
        self,
    ) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "second-slot-crash-target"
            staging = parent / ".second-slot-crash.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, {"a.bin": b"alpha"})
                staging.mkdir()
                raw = os.lstat(staging)
                with patch.object(
                    osm_pilot_bundle,
                    "canonical_json_bytes",
                    side_effect=KeyboardInterrupt("interrupt before second encoding"),
                ):
                    with self.assertRaisesRegex(
                        KeyboardInterrupt, "interrupt before second encoding"
                    ):
                        journal.record_staging(staging, (raw.st_dev, raw.st_ino))

            slots = list(
                parent.glob(".flight-alert-exp8-provenance-publication.*.json")
            )
            self.assertEqual(len(slots), 1)
            self.assertFalse(list(parent.glob("*.tmp")))
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))
            self.assertTrue(
                staging.exists(), "intent-only recovery deleted an unjournaled path"
            )

    def test_publication_process_crash_after_temp_fsync_keeps_predecessor_exact(
        self,
    ) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        script = r"""
import os
import sys
from pathlib import Path
import tools.experiment8.osm_pilot_bundle as bundle

parent = Path(sys.argv[1])
target = parent / "fsync-crash-target"
staging = parent / ".fsync-crash.seed.staging"
raw = os.lstat(staging)
with bundle._PublicationJournal(parent) as journal:
    original_fsync = bundle.os.fsync
    def crash_after_fsync(fd):
        original_fsync(fd)
        os._exit(73)
    bundle.os.fsync = crash_after_fsync
    journal.record_staging(staging, (raw.st_dev, raw.st_ino))
"""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "fsync-crash-target"
            staging = parent / ".fsync-crash.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, {"a.bin": b"alpha"})
            staging.mkdir()
            completed = subprocess.run(
                [sys.executable, "-c", script, str(parent)],
                cwd=Path(provenance.__file__).resolve(strict=True).parents[2],
                env=os.environ.copy(),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(
                completed.returncode,
                73,
                completed.stdout + completed.stderr,
            )
            self.assertEqual(
                len(
                    list(
                        parent.glob(
                            ".flight-alert-exp8-provenance-publication.*.json"
                        )
                    )
                ),
                1,
            )
            self.assertFalse(
                list(
                    parent.glob(
                        "..flight-alert-exp8-provenance-publication.*.json.*.tmp"
                    )
                ),
                "delete-on-close did not remove the fsynced temporary slot",
            )
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))

    def test_publication_temp_creation_is_guarded_before_callback_entry(
        self,
    ) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        script = r"""
import os
import sys
from pathlib import Path
import tools.experiment8.osm_pilot_bundle as bundle

parent = Path(sys.argv[1])
target = parent / "pre-guard-crash-target"
def crash_at_guard_entry(*args, **kwargs):
    os._exit(75)
bundle._journal_created_windows_handle = crash_at_guard_entry
with bundle._PublicationJournal(parent) as journal:
    journal.begin(target, {"a.bin": b"alpha"})
os._exit(95)
"""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repository = Path(provenance.__file__).resolve(strict=True).parents[2]
            for _ in range(5):
                completed = subprocess.run(
                    [sys.executable, "-c", script, str(parent)],
                    cwd=repository,
                    env=os.environ.copy(),
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    completed.returncode,
                    75,
                    completed.stdout + completed.stderr,
                )

            self.assertFalse(
                [
                    parent / name
                    for name in osm_pilot_bundle._PUBLICATION_JOURNAL_TEMP_NAMES
                    if os.path.lexists(parent / name)
                ],
                "pre-guard process exits left a reserved journal temp",
            )
            target = parent / "pre-guard-crash-target"
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))
                restarted.begin(target, {"a.bin": b"alpha"})
                restarted.clear()

    def test_publication_pre_replace_crashes_have_bounded_recoverable_temps(
        self,
    ) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        script = r"""
import os
import sys
from pathlib import Path
import tools.experiment8.osm_pilot_bundle as bundle

parent = Path(sys.argv[1])
target = parent / "pre-replace-crash-target"
def crash_before_replace(source, destination):
    if not source.is_file():
        os._exit(91)
    os._exit(74)
bundle._replace_file_write_through = crash_before_replace
with bundle._PublicationJournal(parent) as journal:
    journal.begin(target, {"a.bin": b"alpha"})
os._exit(92)
"""
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            repository = Path(provenance.__file__).resolve(strict=True).parents[2]
            for _ in range(5):
                completed = subprocess.run(
                    [sys.executable, "-c", script, str(parent)],
                    cwd=repository,
                    env=os.environ.copy(),
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    completed.returncode,
                    74,
                    completed.stdout + completed.stderr,
                )

            leftovers = [
                path for path in parent.iterdir() if path.name.endswith(".tmp")
            ]
            self.assertLessEqual(
                len(leftovers),
                2,
                "repeated crashes accumulated unbounded prepared journal temps",
            )

            target = parent / "pre-replace-crash-target"
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))
                restarted.begin(target, {"a.bin": b"alpha"})
                restarted.clear()
            self.assertFalse(
                [path for path in parent.iterdir() if path.name.endswith(".tmp")]
            )

    def test_publication_preserves_an_unowned_reserved_temp(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            reserved = parent / (
                ".flight-alert-exp8-provenance-publication.a.json.tmp"
            )
            reserved.write_bytes(b"caller-owned replacement")

            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "prepared|temporary|recoverable|reserved",
            ):
                with osm_pilot_bundle._PublicationJournal(parent):
                    self.fail("an unowned reserved temp was ignored")

            self.assertEqual(reserved.read_bytes(), b"caller-owned replacement")

    def test_publication_load_rejects_equal_generation_divergence(self) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        names = osm_pilot_bundle._PUBLICATION_JOURNAL_NAMES
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "divergent-slot-target"
            staging = parent / ".divergent-slot.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, {"a.bin": b"alpha"})
                staging.mkdir()
                raw = os.lstat(staging)
                journal.record_staging(staging, (raw.st_dev, raw.st_ino))

            newer = json.loads((parent / names[1]).read_bytes())
            newer["targetName"] = "other-target"
            (parent / names[0]).write_bytes(canonical_json_bytes(newer))

            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "equal-generation|divergent",
            ):
                with journal_type(parent):
                    self.fail("divergent equal-generation slots were accepted")

    def test_publication_load_rejects_an_invalid_newer_owned_slot(self) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        names = osm_pilot_bundle._PUBLICATION_JOURNAL_NAMES
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "invalid-newer-target"
            staging = parent / ".invalid-newer.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, {"a.bin": b"alpha"})
                staging.mkdir()
                raw = os.lstat(staging)
                journal.record_staging(staging, (raw.st_dev, raw.st_ino))
                child = staging / "a.bin"
                child.write_bytes(b"alpha")
                child_raw = os.lstat(child)
                journal.record_file(
                    "a.bin", (child_raw.st_dev, child_raw.st_ino)
                )
                journal.mark_staged()

            (parent / names[1]).write_bytes(b"torn-newer-slot")
            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "invalid newer|no recoverable|invalid slot",
            ):
                with journal_type(parent):
                    self.fail("an invalid newer slot was silently ignored")

    def test_publication_journal_revalidates_parent_file_id_after_lock(self) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            journal = journal_type(parent)
            original_lstat = osm_pilot_bundle.os.lstat
            observed_post_lock_check = False

            def drift_parent_after_lock(path, *args, **kwargs):
                nonlocal observed_post_lock_check
                raw = original_lstat(path, *args, **kwargs)
                if Path(path) == parent and journal._lock_fd is not None:
                    observed_post_lock_check = True
                    values = list(raw)
                    values[1] += 1
                    return os.stat_result(values)
                return raw

            with patch.object(
                osm_pilot_bundle.os,
                "lstat",
                side_effect=drift_parent_after_lock,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "parent FileId changed|parent.*identity|parent.*reparse",
                ):
                    with journal:
                        journal.begin(parent / "parent-drift-target", {"a": b"a"})

            self.assertTrue(
                observed_post_lock_check,
                "the journal never revalidated its parent after acquiring the lock",
            )
            self.assertFalse(
                list(
                    parent.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_publication_journal_rejects_a_reparse_parent_before_lock(self) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            parent = root / "real-parent"
            parent.mkdir()
            reparse_parent = root / "reparse-parent"
            os.symlink(parent, reparse_parent, target_is_directory=True)

            with self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "non-reparse|reparse",
            ):
                journal_type(reparse_parent)

            self.assertFalse(
                (parent / osm_pilot_bundle._PUBLICATION_LOCK_NAME).exists()
            )

    def test_exact_cleanup_rechecks_python_and_native_links_before_disposition(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            path = parent / "python-links.bin"
            alias = parent / "python-links-alias.bin"
            path.write_bytes(b"alpha")
            raw = os.lstat(path)
            os.link(path, alias)
            with patch.object(
                osm_pilot_bundle, "_windows_fd_link_count", return_value=1
            ), self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exactly one link|single-link",
            ):
                osm_pilot_bundle._delete_exact_regular_file(
                    path,
                    (raw.st_dev, raw.st_ino),
                    require_single_link=True,
                )
            self.assertTrue(path.exists())
            self.assertTrue(alias.exists())

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            path = parent / "native-links.bin"
            alias = parent / "native-links-alias.bin"
            path.write_bytes(b"alpha")
            raw = os.lstat(path)
            native_link_count = osm_pilot_bundle._windows_fd_link_count

            def link_immediately_before_native_query(fd: int) -> int:
                os.link(path, alias)
                return native_link_count(fd)

            with patch.object(
                osm_pilot_bundle,
                "_windows_fd_link_count",
                side_effect=link_immediately_before_native_query,
            ), self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exactly one link|single-link",
            ):
                osm_pilot_bundle._delete_exact_regular_file(
                    path,
                    (raw.st_dev, raw.st_ino),
                    require_single_link=True,
                )
            self.assertTrue(path.exists())
            self.assertTrue(alias.exists())

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            path = parent / "disposition-links.bin"
            alias = parent / "disposition-links-alias.bin"
            path.write_bytes(b"alpha")
            raw = os.lstat(path)
            mark_for_delete = osm_pilot_bundle._mark_windows_fd_for_delete

            def link_at_disposition(fd: int, **kwargs) -> None:
                os.link(path, alias)
                mark_for_delete(fd, **kwargs)

            with patch.object(
                osm_pilot_bundle,
                "_mark_windows_fd_for_delete",
                side_effect=link_at_disposition,
            ), self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exactly one link|single-link",
            ):
                osm_pilot_bundle._delete_exact_regular_file(
                    path,
                    (raw.st_dev, raw.st_ino),
                    require_single_link=True,
                )
            self.assertTrue(path.exists())
            self.assertTrue(alias.exists())

    def test_exact_cleanup_never_mutates_a_swapped_replacement_namespace(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            owned = parent / "owned-bundle"
            owned.mkdir()
            owned_child = owned / "a.bin"
            owned_child.write_bytes(b"alpha")
            directory_raw = os.lstat(owned)
            child_raw = os.lstat(owned_child)
            replacement = parent / "caller-replacement"
            replacement.mkdir()
            replacement_child = replacement / "a.bin"
            os.link(owned_child, replacement_child)
            displaced = parent / "displaced-owned-bundle"
            original_fstat = osm_pilot_bundle.os.fstat
            swapped = False

            def swap_after_directory_pin(fd):
                nonlocal swapped
                raw = original_fstat(fd)
                if not swapped and stat.S_ISDIR(raw.st_mode):
                    owned.rename(displaced)
                    replacement.rename(owned)
                    swapped = True
                return raw

            with patch.object(
                osm_pilot_bundle.os,
                "fstat",
                side_effect=swap_after_directory_pin,
            ):
                try:
                    osm_pilot_bundle._remove_owned_install_path(
                        owned,
                        expected_file_key=(
                            directory_raw.st_dev,
                            directory_raw.st_ino,
                        ),
                        expected_files={
                            "a.bin": (child_raw.st_dev, child_raw.st_ino)
                        },
                    )
                except provenance.ProvenanceVerificationError:
                    pass

            self.assertTrue(swapped)
            self.assertTrue(owned.is_dir())
            self.assertTrue(
                (owned / "a.bin").exists(),
                "cleanup deleted a hardlink from the caller-owned replacement",
            )
            self.assertEqual(
                (owned / "a.bin").read_bytes(),
                b"alpha",
                "cleanup deleted a hardlink from the caller-owned replacement",
            )

    def test_publication_recovery_preserves_owned_child_with_new_hardlink(self) -> None:
        journal_type = osm_pilot_bundle._PublicationJournal
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "hardlink-recovery-target"
            staging = parent / ".hardlink-recovery.seed.staging"
            alias = parent / "outside-alias.bin"
            with journal_type(parent) as journal:
                journal.begin(target, {"a.bin": b"alpha"})
                staging.mkdir()
                staging_raw = os.lstat(staging)
                journal.record_staging(
                    staging, (staging_raw.st_dev, staging_raw.st_ino)
                )
                child = staging / "a.bin"
                child.write_bytes(b"alpha")
                child_raw = os.lstat(child)
                journal.record_file(
                    "a.bin", (child_raw.st_dev, child_raw.st_ino)
                )
            os.link(child, alias)

            with journal_type(parent) as restarted, self.assertRaisesRegex(
                provenance.ProvenanceVerificationError,
                "exactly one link|single-link",
            ):
                restarted.recover(target, lambda _: None)

            self.assertEqual(child.read_bytes(), b"alpha")
            self.assertEqual(alias.read_bytes(), b"alpha")
            self.assertTrue(
                list(
                    parent.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_publication_journal_enforces_parent_scoped_single_writer(self) -> None:
        journal_type = getattr(osm_pilot_bundle, "_PublicationJournal", None)
        self.assertIsNotNone(
            journal_type, "durable publication journal is missing"
        )
        if journal_type is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            with journal_type(parent):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "writer is active|lock acquisition failed",
                ):
                    with journal_type(parent):
                        self.fail("second publication writer acquired the parent")

    def test_bundle_writer_durably_journals_every_publication_phase(self) -> None:
        journal_type = getattr(provenance, "_PublicationJournal", None)
        barrier = getattr(provenance, "_directory_metadata_barrier", None)
        self.assertIsNotNone(
            journal_type, "bundle writer has no durable publication journal"
        )
        self.assertIsNotNone(
            barrier, "bundle writer has no post-rename metadata barrier"
        )
        if journal_type is None or barrier is None:
            return
        phases: list[str] = []

        class TrackingJournal(journal_type):
            def _commit(self, document):
                super()._commit(document)
                phases.append(self._state["phase"])

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "journaled-provenance"
            real_journal_barrier = osm_pilot_bundle._directory_metadata_barrier
            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_PublicationJournal",
                TrackingJournal,
            ), patch.object(
                osm_pilot_bundle,
                "_directory_metadata_barrier",
                wraps=real_journal_barrier,
            ) as journal_barrier, patch.object(
                provenance,
                "_directory_metadata_barrier",
                wraps=barrier,
            ) as rename_barrier:
                provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertEqual(phases[0], "intent")
            self.assertEqual(phases[-1], "accepted")
            self.assertEqual(
                {"intent", "building", "staged", "renaming", "installed", "accepted"},
                set(phases),
            )
            self.assertGreaterEqual(journal_barrier.call_count, len(phases))
            rename_barrier.assert_any_call(root)
            self.assertFalse(
                list(
                    root.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_bundle_writer_accepts_exact_installed_journal_after_restart(self) -> None:
        journal_type = getattr(provenance, "_PublicationJournal", None)
        self.assertIsNotNone(
            journal_type, "bundle writer has no durable publication journal"
        )
        if journal_type is None:
            return
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root)
            )
            destination = root / "restart-accepted-provenance"
            with self._current_fixture_graph(inputs):
                expected = provenance.write_pilot_provenance_bundle(
                    destination, inputs
                )
            payloads = {
                path.name: path.read_bytes() for path in destination.iterdir()
            }
            directory_stat = os.lstat(destination)
            with journal_type(root) as journal:
                journal.begin(destination, payloads)
                journal.record_staging(
                    root / ".restart-accepted.seed.staging",
                    (directory_stat.st_dev, directory_stat.st_ino),
                )
                for name in sorted(payloads):
                    child_stat = os.lstat(destination / name)
                    journal.record_file(
                        name, (child_stat.st_dev, child_stat.st_ino)
                    )
                journal.mark_staged()
                journal.mark_renaming()
                journal.mark_installed()

            with self._current_fixture_graph(inputs), patch.object(
                provenance,
                "_create_owned_staging_directory",
            ) as staging_factory:
                recovered = provenance.write_pilot_provenance_bundle(
                    destination, inputs
                )

            self.assertEqual(recovered, expected)
            staging_factory.assert_not_called()
            self.assertTrue(destination.is_dir())
            self.assertFalse(
                list(
                    root.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_publication_recovery_preserves_preexisting_and_replacement_paths(
        self,
    ) -> None:
        journal_type = getattr(osm_pilot_bundle, "_PublicationJournal", None)
        self.assertIsNotNone(
            journal_type, "durable publication journal is missing"
        )
        if journal_type is None:
            return
        payloads = {"a.bin": b"alpha", "b.bin": b"beta"}

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "preexisting"
            with journal_type(parent) as journal:
                journal.begin(target, payloads)
            target.mkdir()
            sentinel = target / "sentinel.bin"
            sentinel.write_bytes(b"preexisting")
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))
            self.assertEqual(sentinel.read_bytes(), b"preexisting")

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "staging-replacement-target"
            staging = parent / ".staging-replacement.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, payloads)
                staging.mkdir()
                raw = os.lstat(staging)
                journal.record_staging(staging, (raw.st_dev, raw.st_ino))
            displaced = parent / ".displaced-owned-staging"
            staging.rename(displaced)
            staging.mkdir()
            sentinel = staging / "sentinel.bin"
            sentinel.write_bytes(b"replacement staging")
            displaced.rmdir()
            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))
            self.assertEqual(sentinel.read_bytes(), b"replacement staging")

        for replace_child in (False, True):
            with self.subTest(replace_child=replace_child), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                target = parent / "installed-replacement-target"
                staging = parent / ".installed-replacement.seed.staging"
                with journal_type(parent) as journal:
                    journal.begin(target, payloads)
                    staging.mkdir()
                    raw = os.lstat(staging)
                    journal.record_staging(staging, (raw.st_dev, raw.st_ino))
                    for name, payload in payloads.items():
                        child = staging / name
                        child.write_bytes(payload)
                        child_raw = os.lstat(child)
                        journal.record_file(
                            name, (child_raw.st_dev, child_raw.st_ino)
                        )
                    journal.mark_staged()
                    journal.mark_renaming()
                    staging.rename(target)
                    journal.mark_installed()
                if replace_child:
                    (target / "a.bin").unlink()
                    sentinel = target / "a.bin"
                    sentinel.write_bytes(b"replacement child")
                else:
                    displaced = parent / ".displaced-owned-target"
                    target.rename(displaced)
                    target.mkdir()
                    sentinel = target / "sentinel.bin"
                    sentinel.write_bytes(b"replacement target")
                    shutil.rmtree(displaced)
                accept = Mock(side_effect=AssertionError("replacement was accepted"))
                with journal_type(parent) as restarted:
                    self.assertIsNone(restarted.recover(target, accept))
                accept.assert_not_called()
                self.assertTrue(target.is_dir())
                self.assertEqual(
                    sentinel.read_bytes(),
                    b"replacement child" if replace_child else b"replacement target",
                )
                if replace_child:
                    self.assertEqual(
                        [path.name for path in target.iterdir()], ["a.bin"]
                    )

    def test_publication_recovery_is_bounded_across_repeated_cleanup_crashes(
        self,
    ) -> None:
        journal_type = getattr(osm_pilot_bundle, "_PublicationJournal", None)
        self.assertIsNotNone(
            journal_type, "durable publication journal is missing"
        )
        if journal_type is None:
            return
        payloads = {"a.bin": b"alpha", "b.bin": b"beta"}
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            target = parent / "bounded-recovery-target"
            staging = parent / ".bounded-recovery.seed.staging"
            with journal_type(parent) as journal:
                journal.begin(target, payloads)
                staging.mkdir()
                raw = os.lstat(staging)
                journal.record_staging(staging, (raw.st_dev, raw.st_ino))
                for name, payload in payloads.items():
                    child = staging / name
                    child.write_bytes(payload)
                    child_raw = os.lstat(child)
                    journal.record_file(
                        name, (child_raw.st_dev, child_raw.st_ino)
                    )
                journal.mark_staged()

            original_delete = osm_pilot_bundle._delete_exact_regular_file
            for remaining in (1, 0):
                crashed = False

                def delete_then_crash(path: Path, expected_key):
                    nonlocal crashed
                    original_delete(path, expected_key)
                    if not crashed:
                        crashed = True
                        raise KeyboardInterrupt("simulated recovery crash")

                with patch.object(
                    osm_pilot_bundle,
                    "_delete_exact_regular_file",
                    side_effect=delete_then_crash,
                ):
                    with journal_type(parent) as restarted:
                        with self.assertRaisesRegex(
                            KeyboardInterrupt, "simulated recovery crash"
                        ):
                            restarted.recover(target, lambda _: None)
                self.assertTrue(crashed)
                self.assertTrue(staging.is_dir())
                self.assertEqual(len(list(staging.iterdir())), remaining)
                self.assertEqual(
                    len(list(parent.glob(".bounded-recovery*.staging"))), 1
                )

            with journal_type(parent) as restarted:
                self.assertIsNone(restarted.recover(target, lambda _: None))
            self.assertFalse(staging.exists())
            with journal_type(parent) as repeated:
                self.assertIsNone(repeated.recover(target, lambda _: None))
            self.assertFalse(
                list(
                    parent.glob(
                        ".flight-alert-exp8-provenance-publication.*.json"
                    )
                )
            )

    def test_native_created_paths_are_auto_deleted_before_journal_receipt(self) -> None:
        script = r"""
import os
import sys
from pathlib import Path
import tools.experiment8.osm_pilot_provenance as provenance
from tools.experiment8.osm_pilot_bundle import _PublicationJournal

parent = Path(sys.argv[1])
mode = sys.argv[2]
target = parent / "crash-target"
with _PublicationJournal(parent) as journal:
    journal.begin(target, {"a.bin": b"alpha"})
    receipt = provenance._OwnedStagingDirectory(journal=journal)
    if mode == "directory":
        journal.record_staging = lambda *args: os._exit(71)
        provenance._create_owned_staging_directory(
            receipt, parent, prefix=".directory-crash.", suffix=".staging"
        )
    else:
        provenance._create_owned_staging_directory(
            receipt, parent, prefix=".child-crash.", suffix=".staging"
        )
        token = provenance._ACTIVE_BUNDLE_WRITE_RECEIPTS.set(receipt)
        try:
            journal.record_file = lambda *args: os._exit(72)
            provenance._write_new_file(receipt.path / "a.bin", b"alpha")
        finally:
            provenance._ACTIVE_BUNDLE_WRITE_RECEIPTS.reset(token)
"""
        for mode, exit_code, pattern in (
            ("directory", 71, ".directory-crash.*.staging"),
            ("child", 72, ".child-crash.*.staging"),
        ):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                parent = Path(temporary)
                completed = subprocess.run(
                    [sys.executable, "-c", script, str(parent), mode],
                    cwd=Path(provenance.__file__).resolve(strict=True).parents[2],
                    env=os.environ.copy(),
                    check=False,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(
                    completed.returncode,
                    exit_code,
                    completed.stdout + completed.stderr,
                )
                with osm_pilot_bundle._PublicationJournal(parent) as restarted:
                    self.assertIsNone(
                        restarted.recover(
                            parent / "crash-target",
                            Mock(side_effect=AssertionError("partial path was accepted")),
                        )
                    )
                self.assertFalse(
                    list(parent.glob(pattern)),
                    "native-created path escaped both delete-on-close and recovery",
                )
                self.assertFalse(
                    list(
                        parent.glob(
                            ".flight-alert-exp8-provenance-publication.*.json"
                        )
                    )
                )

    def test_bundle_writer_rejects_post_assembly_selector_replacement_without_partial_output(self) -> None:
        candidate_xml = self._root_candidate_xml()
        original = osm_hydro_source.encode_selection_material
        calls: list[bytes] = []

        def byte_identical_wrapper(value: bytes) -> tuple[bytes, bytes]:
            calls.append(value)
            return original(value)

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            inputs = provenance.assemble_verified_pilot_inputs(
                *self._verified_components(root, candidate_xml_bytes=candidate_xml)
            )
            destination = root / "second-selector-check-provenance"

            with self._current_fixture_graph(inputs), patch.object(
                osm_hydro_source,
                "encode_selection_material",
                new=byte_identical_wrapper,
            ):
                with self.assertRaisesRegex(
                    provenance.ProvenanceVerificationError,
                    "callable identity",
                ):
                    provenance.write_pilot_provenance_bundle(destination, inputs)

            self.assertEqual(calls, [])
            self.assertFalse(destination.exists())
            self.assertFalse(
                list(root.glob(".second-selector-check-provenance.*.staging"))
            )


if __name__ == "__main__":
    unittest.main()
