from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from tools.experiment8.osm_hydro_source import (
    ALLOWED_DIRECT_WATERWAY_VALUES,
    CHESTER_INSPECTION_SHA256,
    CHESTER_SOURCE_SHA256,
    MARYLAND_SOURCE_BYTES,
    MARYLAND_SOURCE_MD5,
    MARYLAND_SOURCE_URL,
    POLICY_SHA256,
    build_storage_preflight,
    OsmRelation,
    OsmRelationMember,
    OsmWay,
    SourceContractError,
    assemble_relation_parts,
    canonical_policy_bytes,
    compute_reference_closure,
    inspect_osm_xml,
    is_direct_way_root,
    is_named_waterway_relation_root,
    parse_e7,
    parse_osm_xml,
    select_roots,
    supported_display_names,
    verify_locked_chester_fixture,
    verify_maryland_source,
    write_inspection_report,
    write_policy,
    write_storage_preflight,
)


def _tags(**values: str) -> tuple[tuple[str, str], ...]:
    return tuple(sorted(values.items()))


def _way(
    object_id: int,
    refs: tuple[int, ...],
    *,
    name: str | None = "River Test",
    waterway: str = "river",
) -> OsmWay:
    values = {"waterway": waterway}
    if name is not None:
        values["name"] = name
    return OsmWay(
        object_id=object_id,
        version=1,
        timestamp="2026-01-02T03:04:05Z",
        node_refs=refs,
        tags=_tags(**values),
    )


class ExactCoordinateTests(unittest.TestCase):
    def test_e7_parser_is_decimal_exact_and_never_float_rounded(self) -> None:
        self.assertEqual(parse_e7("-76.0393082", axis="longitude"), -760393082)
        self.assertEqual(parse_e7("39.1077820", axis="latitude"), 391077820)
        self.assertEqual(parse_e7("-0.0000000", axis="latitude"), 0)

        with self.assertRaisesRegex(SourceContractError, "more than seven decimal"):
            parse_e7("1.00000001", axis="longitude")
        with self.assertRaisesRegex(SourceContractError, "longitude"):
            parse_e7("180.0000001", axis="longitude")
        with self.assertRaisesRegex(SourceContractError, "latitude"):
            parse_e7("-90.0000001", axis="latitude")
        with self.assertRaisesRegex(SourceContractError, "finite decimal"):
            parse_e7("NaN", axis="latitude")


class SelectionPolicyTests(unittest.TestCase):
    def test_policy_admits_only_frozen_direct_line_values(self) -> None:
        self.assertEqual(
            ALLOWED_DIRECT_WATERWAY_VALUES,
            frozenset({"river", "stream", "canal", "tidal_channel", "wadi"}),
        )
        for value in sorted(ALLOWED_DIRECT_WATERWAY_VALUES):
            self.assertTrue(is_direct_way_root(_way(1, (1, 2), waterway=value)))
        for value in ("dam", "weir", "lock_gate", "dock", "riverbank", "ditch", "drain", "brook"):
            self.assertFalse(is_direct_way_root(_way(1, (1, 2), waterway=value)))

    def test_direct_way_requires_exact_supported_nonblank_display_text(self) -> None:
        self.assertFalse(is_direct_way_root(_way(1, (1, 2), name=None)))
        self.assertFalse(is_direct_way_root(_way(1, (1, 2), name="  ")))
        self.assertTrue(
            is_direct_way_root(
                OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(waterway="river", int_name="Río Uno"))
            )
        )
        self.assertFalse(
            is_direct_way_root(
                OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(waterway="river", old_name="Old River"))
            )
        )

    def test_area_closed_degenerate_and_lifecycle_ways_are_not_direct_roots(self) -> None:
        base = {"waterway": "river", "name": "River"}
        rejected = (
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1,), _tags(**base)),
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2, 1), _tags(**base)),
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(**base, area="yes")),
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(**base, disused="yes")),
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(**base, abandoned="true")),
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(**base, construction="river")),
            OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(**base, **{"disused:waterway": "river"})),
        )
        for way in rejected:
            with self.subTest(tags=way.tags, refs=way.node_refs):
                self.assertFalse(is_direct_way_root(way))
        self.assertTrue(
            is_direct_way_root(
                OsmWay(1, 1, "2026-01-02T03:04:05Z", (1, 2), _tags(**base, area="no", disused="no"))
            )
        )

    def test_display_names_preserve_exact_unicode_and_reject_semantic_name_variants(self) -> None:
        decomposed = "Cafe\u0301 River"
        names = supported_display_names(
            _tags(
                name=decomposed,
                int_name="International River",
                official_name="Official River",
                **{
                    "name:en": "English River",
                    "name:zh-Hant": "繁體河",
                    "name:left": "Left bank",
                    "name:pronunciation": "not display text",
                    "old_name": "Former River",
                },
            )
        )
        self.assertEqual(
            names,
            (
                ("int_name", "International River"),
                ("name", decomposed),
                ("name:en", "English River"),
                ("name:zh-Hant", "繁體河"),
                ("official_name", "Official River"),
            ),
        )
        self.assertIn("\u0301", names[1][1])

    def test_relation_root_uses_explicit_type_and_its_own_name(self) -> None:
        relation = OsmRelation(
            object_id=9,
            version=2,
            timestamp="2026-01-02T03:04:05Z",
            members=(OsmRelationMember("way", 1, ""),),
            tags=_tags(type="waterway", name="River Test"),
        )
        self.assertTrue(is_named_waterway_relation_root(relation))
        self.assertFalse(
            is_named_waterway_relation_root(
                OsmRelation(9, 2, relation.timestamp, relation.members, _tags(type="route", name="River Test"))
            )
        )
        self.assertFalse(
            is_named_waterway_relation_root(
                OsmRelation(9, 2, relation.timestamp, relation.members, _tags(type="waterway"))
            )
        )

    def test_policy_bytes_are_canonical_versioned_and_stable(self) -> None:
        first = canonical_policy_bytes()
        second = canonical_policy_bytes()
        self.assertEqual(first, second)
        self.assertTrue(first.endswith(b"\n"))
        document = json.loads(first)
        self.assertEqual(document["schema"], "flight-alert-exp8-osm-waterway-policy-v1")
        self.assertEqual(document["allowedDirectWaterwayValues"], sorted(ALLOWED_DIRECT_WATERWAY_VALUES))
        self.assertEqual(hashlib.sha256(first).hexdigest(), hashlib.sha256(second).hexdigest())
        self.assertEqual(hashlib.sha256(first).hexdigest(), POLICY_SHA256)

    def test_fixed_source_lock_constants_match_the_reviewed_contract(self) -> None:
        self.assertEqual(MARYLAND_SOURCE_URL, "https://download.geofabrik.de/north-america/us/maryland-260710.osm.pbf")
        self.assertEqual(MARYLAND_SOURCE_BYTES, 212_933_228)
        self.assertEqual(MARYLAND_SOURCE_MD5, "2642fa017680941a2fab4f96c23d9c03")
        self.assertEqual(CHESTER_SOURCE_SHA256, "beea8b394d26fa86e3c372b678420a5fb84af801be7378a681f2f2976f35e99d")
        self.assertEqual(CHESTER_INSPECTION_SHA256, "0445785b4f9e0a91c5b9cd09401bbee4ca1bd60d8d893d9b5b5c33a70ea28e6f")


class ClosureAndRelationTests(unittest.TestCase):
    def test_selected_roots_and_reference_only_objects_never_collapse(self) -> None:
        xml = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="test">
  <node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.0000000" lon="-76.0000000" />
  <node id="2" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.1000000" lon="-76.1000000" />
  <node id="3" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.2000000" lon="-76.2000000" />
  <way id="10" version="3" timestamp="2026-01-01T00:00:00Z">
    <nd ref="1"/><nd ref="2"/>
    <tag k="waterway" v="river"/><tag k="name" v="River One"/>
  </way>
  <way id="11" version="4" timestamp="2026-01-01T00:00:00Z">
    <nd ref="2"/><nd ref="3"/>
    <tag k="waterway" v="river"/>
  </way>
  <relation id="20" version="5" timestamp="2026-01-01T00:00:00Z">
    <member type="way" ref="10" role=""/><member type="way" ref="11" role=""/>
    <tag k="type" v="waterway"/><tag k="name" v="River One"/>
  </relation>
</osm>
"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            source.write_text(xml, encoding="utf-8", newline="\n")
            dataset = parse_osm_xml(source)

        roots = select_roots(dataset)
        self.assertEqual(roots.way_ids, (10,))
        self.assertEqual(roots.relation_ids, (20,))
        closure = compute_reference_closure(dataset, roots)
        self.assertEqual(closure.selected_way_ids, (10,))
        self.assertEqual(closure.selected_relation_ids, (20,))
        self.assertEqual(closure.reference_only_way_ids, (11,))
        self.assertEqual(closure.reference_only_node_ids, (1, 2, 3))

    def test_missing_reference_fails_closed_with_object_identity(self) -> None:
        xml = """<osm version="0.6"><node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="0" lon="0"/><way id="10" version="1" timestamp="2026-01-01T00:00:00Z"><nd ref="1"/><nd ref="999"/><tag k="waterway" v="river"/><tag k="name" v="River"/></way></osm>"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            source.write_text(xml, encoding="utf-8")
            dataset = parse_osm_xml(source)
        with self.assertRaisesRegex(SourceContractError, "way 10.*node 999"):
            compute_reference_closure(dataset, select_roots(dataset))

    def test_relation_assembly_uses_member_order_and_exact_node_ids_only(self) -> None:
        ways = {
            1: _way(1, (10, 11)),
            2: _way(2, (11, 12)),
            3: _way(3, (20, 21)),
        }
        relation = OsmRelation(
            50,
            1,
            "2026-01-02T03:04:05Z",
            (
                OsmRelationMember("way", 1, ""),
                OsmRelationMember("way", 2, ""),
                OsmRelationMember("way", 3, "side_stream"),
            ),
            _tags(type="waterway", name="River Test"),
        )
        self.assertEqual(
            assemble_relation_parts(relation, ways=ways, relations={50: relation}),
            ((10, 11, 12), (20, 21)),
        )

    def test_relation_assembly_does_not_reverse_or_join_approximate_endpoints(self) -> None:
        ways = {
            1: _way(1, (10, 11)),
            2: _way(2, (12, 11)),
            3: _way(3, (30, 31)),
        }
        relation = OsmRelation(
            50,
            1,
            "2026-01-02T03:04:05Z",
            tuple(OsmRelationMember("way", object_id, "") for object_id in (1, 2, 3)),
            _tags(type="waterway", name="River Test"),
        )
        self.assertEqual(
            assemble_relation_parts(relation, ways=ways, relations={50: relation}),
            ((10, 11), (12, 11), (30, 31)),
        )

    def test_nested_relation_cycle_is_fatal(self) -> None:
        first = OsmRelation(
            50,
            1,
            "2026-01-02T03:04:05Z",
            (OsmRelationMember("relation", 51, ""),),
            _tags(type="waterway", name="River Test"),
        )
        second = OsmRelation(
            51,
            1,
            "2026-01-02T03:04:05Z",
            (OsmRelationMember("relation", 50, ""),),
            _tags(type="waterway", name="River Test"),
        )
        with self.assertRaisesRegex(SourceContractError, "relation cycle.*50.*51.*50"):
            assemble_relation_parts(first, ways={}, relations={50: first, 51: second})


class XmlInspectionTests(unittest.TestCase):
    def test_inspection_is_deterministic_exact_and_preserves_antimeridian(self) -> None:
        xml = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="fixture">
  <node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="1.0000000" lon="179.9999999" />
  <node id="2" version="1" timestamp="2026-01-01T00:00:00Z" lat="1.1000000" lon="-179.9999999" />
  <way id="10" version="2" timestamp="2026-01-01T00:00:00Z"><nd ref="1"/><nd ref="2"/><tag k="waterway" v="river"/><tag k="name" v="河川"/></way>
</osm>
"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            source.write_text(xml, encoding="utf-8", newline="\n")
            first = inspect_osm_xml(source)
            second = inspect_osm_xml(source)

        self.assertEqual(first, second)
        self.assertEqual(first["sourceSha256"], hashlib.sha256(xml.encode("utf-8")).hexdigest())
        self.assertEqual(first["selectedRoots"], {"relations": 0, "ways": 1})
        self.assertEqual(first["closure"], {"referenceOnlyNodes": 2, "referenceOnlyRelations": 0, "referenceOnlyWays": 0})
        self.assertEqual(first["e7Bounds"], [-1799999999, 10000000, 1799999999, 11000000])
        self.assertEqual(first["rootIds"], ["w10"])

    def test_optional_corridor_reports_only_exact_root_way_bound_intersections(self) -> None:
        xml = """<osm version="0.6" generator="fixture">
  <node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.0000000" lon="-76.0000000"/>
  <node id="2" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.1000000" lon="-76.1000000"/>
  <node id="3" version="1" timestamp="2026-01-01T00:00:00Z" lat="40.0000000" lon="-77.0000000"/>
  <node id="4" version="1" timestamp="2026-01-01T00:00:00Z" lat="40.1000000" lon="-77.1000000"/>
  <way id="10" version="1" timestamp="2026-01-01T00:00:00Z"><nd ref="1"/><nd ref="2"/><tag k="waterway" v="river"/><tag k="name" v="Near"/></way>
  <way id="11" version="1" timestamp="2026-01-01T00:00:00Z"><nd ref="3"/><nd ref="4"/><tag k="waterway" v="river"/><tag k="name" v="Far"/></way>
</osm>"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            source.write_text(xml, encoding="utf-8")
            report = inspect_osm_xml(
                source,
                corridor_e7=(-761000000, 389500000, -759500000, 391500000),
            )
        self.assertEqual(report["corridorE7"], [-761000000, 389500000, -759500000, 391500000])
        self.assertEqual(report["intersectingRootWayIds"], [10])

    def test_relation_member_order_type_identity_and_roles_are_documented(self) -> None:
        xml = """<osm version="0.6" generator="fixture">
  <node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="39" lon="-76"/>
  <node id="2" version="1" timestamp="2026-01-01T00:00:00Z" lat="40" lon="-77"/>
  <way id="10" version="3" timestamp="2026-01-01T00:00:00Z"><nd ref="1"/><nd ref="2"/><tag k="waterway" v="river"/></way>
  <relation id="20" version="4" timestamp="2026-01-01T00:00:00Z"><member type="way" ref="10" role="main_stream"/><tag k="type" v="waterway"/><tag k="name" v="River"/></relation>
</osm>"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            source.write_text(xml, encoding="utf-8")
            report = inspect_osm_xml(source)
        self.assertEqual(
            report["relationMembers"],
            {"20": [{"objectType": "way", "ref": 10, "role": "main_stream"}]},
        )

    def test_report_and_policy_writes_are_canonical_atomic_and_hash_checked(self) -> None:
        xml = """<osm version="0.6" generator="fixture">
  <node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.0000000" lon="-76.0000000"/>
  <node id="2" version="1" timestamp="2026-01-01T00:00:00Z" lat="39.1000000" lon="-76.1000000"/>
  <way id="10" version="1" timestamp="2026-01-01T00:00:00Z"><nd ref="1"/><nd ref="2"/><tag k="waterway" v="river"/><tag k="name" v="River"/></way>
</osm>"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            report = Path(temporary, "report.json")
            policy = Path(temporary, "policy.json")
            source.write_text(xml, encoding="utf-8")
            expected = hashlib.sha256(source.read_bytes()).hexdigest()

            report_hash = write_inspection_report(
                source,
                report,
                expected_source_sha256=expected,
                corridor_e7=(-761000000, 389500000, -759500000, 391500000),
            )
            policy_hash = write_policy(policy)
            report_bytes = report.read_bytes()
            policy_bytes = policy.read_bytes()

            self.assertTrue(report_bytes.endswith(b"\n"))
            self.assertEqual(report_hash, hashlib.sha256(report_bytes).hexdigest())
            self.assertEqual(policy_bytes, canonical_policy_bytes())
            self.assertEqual(policy_hash, hashlib.sha256(policy_bytes).hexdigest())
            self.assertFalse(list(Path(temporary).glob("*.tmp")))

            report.write_bytes(b"sentinel")
            with self.assertRaisesRegex(SourceContractError, "source SHA-256 mismatch"):
                write_inspection_report(
                    source,
                    report,
                    expected_source_sha256="0" * 64,
                )
            self.assertEqual(report.read_bytes(), b"sentinel")


class StoragePreflightTests(unittest.TestCase):
    IDENTITIES = {
        "archive": {"identity": "archive-id", "media": "SATA HDD"},
        "fastPilot": {"identity": "fast-id", "media": "SSD spanned volume"},
        "runtime": {"identity": "runtime-id", "media": "NVMe SSD"},
    }

    def test_preflight_records_exact_bytes_and_toolchain_identity(self) -> None:
        usages = {
            "C:\\": SimpleNamespace(total=1_000, used=400, free=600),
            "D:\\": SimpleNamespace(total=2_000, used=1_000, free=1_000),
            "E:\\": SimpleNamespace(total=3_000, used=500, free=2_500),
        }

        def fake_usage(path: str | Path) -> SimpleNamespace:
            return usages[str(path)]

        with patch("tools.experiment8.osm_hydro_source.shutil.disk_usage", side_effect=fake_usage):
            report = build_storage_preflight(
                {"archive": "D:\\", "fastPilot": "E:\\", "runtime": "C:\\"},
                minimum_free_bytes={"archive": 900, "fastPilot": 2_000, "runtime": 500},
                volume_identities=self.IDENTITIES,
            )
        self.assertEqual(report["schema"], "flight-alert-exp8-osm-storage-preflight-v1")
        self.assertEqual(report["volumes"]["fastPilot"]["freeBytes"], 2_500)
        self.assertEqual(report["volumes"]["fastPilot"]["minimumFreeBytes"], 2_000)
        self.assertTrue(report["volumes"]["fastPilot"]["passed"])
        self.assertEqual(report["volumes"]["fastPilot"]["identity"], "fast-id")
        self.assertEqual(report["volumes"]["fastPilot"]["media"], "SSD spanned volume")
        self.assertEqual(report["toolchain"]["osmiumBinarySha256"], "5575922905fb39fa87262e74ed2b0ac367086b5439468339e45bf3720c1821fc")

    def test_failed_preflight_does_not_replace_existing_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary, "preflight.json")
            output.write_bytes(b"sentinel")
            with patch(
                "tools.experiment8.osm_hydro_source.shutil.disk_usage",
                return_value=SimpleNamespace(total=1_000, used=900, free=100),
            ):
                with self.assertRaisesRegex(SourceContractError, "fastPilot.*requires 200"):
                    write_storage_preflight(
                        output,
                        {"fastPilot": "E:\\"},
                        minimum_free_bytes={"fastPilot": 200},
                        volume_identities={"fastPilot": self.IDENTITIES["fastPilot"]},
                    )
            self.assertEqual(output.read_bytes(), b"sentinel")

    def test_volume_roots_are_canonicalized_before_measurement_and_evidence(self) -> None:
        def fake_usage(path: str | Path) -> SimpleNamespace:
            self.assertEqual(str(path), "E:\\")
            return SimpleNamespace(total=1_000, used=100, free=900)

        with patch("tools.experiment8.osm_hydro_source.shutil.disk_usage", side_effect=fake_usage):
            report = build_storage_preflight(
                {"fastPilot": "E:\\\\"},
                minimum_free_bytes={"fastPilot": 800},
                volume_identities={"fastPilot": self.IDENTITIES["fastPilot"]},
            )
        self.assertEqual(report["volumes"]["fastPilot"]["root"], "E:\\")


class LockedEvidenceTests(unittest.TestCase):
    def test_maryland_verifier_checks_bytes_md5_and_returns_sha256(self) -> None:
        payload = b"locked maryland test payload"
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "maryland.osm.pbf")
            source.write_bytes(payload)
            with patch("tools.experiment8.osm_hydro_source.MARYLAND_SOURCE_BYTES", len(payload)), patch(
                "tools.experiment8.osm_hydro_source.MARYLAND_SOURCE_MD5",
                hashlib.md5(payload).hexdigest(),
            ):
                verified = verify_maryland_source(source)
        self.assertEqual(verified.bytes, len(payload))
        self.assertEqual(verified.md5, hashlib.md5(payload).hexdigest())
        self.assertEqual(verified.sha256, hashlib.sha256(payload).hexdigest())

    def test_maryland_verifier_rejects_wrong_length_before_acceptance(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "maryland.osm.pbf")
            source.write_bytes(b"short")
            with self.assertRaisesRegex(SourceContractError, "Maryland source bytes mismatch"):
                verify_maryland_source(source)

    def test_locked_chester_wrapper_enforces_source_and_report_hashes(self) -> None:
        xml = """<osm version="0.6" generator="fixture"><node id="1" version="1" timestamp="2026-01-01T00:00:00Z" lat="39" lon="-76"/><node id="2" version="1" timestamp="2026-01-01T00:00:00Z" lat="40" lon="-77"/><way id="10" version="1" timestamp="2026-01-01T00:00:00Z"><nd ref="1"/><nd ref="2"/><tag k="waterway" v="river"/><tag k="name" v="River"/></way></osm>"""
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary, "source.osm.xml")
            source.write_text(xml, encoding="utf-8")
            source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
            report = inspect_osm_xml(source, corridor_e7=(-1_800_000_000, -900_000_000, 1_800_000_000, 900_000_000))
            report_bytes = (json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
            with patch("tools.experiment8.osm_hydro_source.CHESTER_SOURCE_SHA256", source_hash), patch(
                "tools.experiment8.osm_hydro_source.CHESTER_INSPECTION_SHA256",
                hashlib.sha256(report_bytes).hexdigest(),
            ), patch(
                "tools.experiment8.osm_hydro_source.CHESTER_CORRIDOR_E7",
                (-1_800_000_000, -900_000_000, 1_800_000_000, 900_000_000),
            ):
                self.assertEqual(
                    verify_locked_chester_fixture(source),
                    hashlib.sha256(report_bytes).hexdigest(),
                )


if __name__ == "__main__":
    unittest.main()
