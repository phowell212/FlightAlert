from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.experiment8 import osm_place_renderer
from tools.experiment8.model import TileKey
from tools.experiment8.osm_hydro_source import parse_osm_xml_bytes
from tools.experiment8.osm_place_renderer import (
    OSM_CAPITAL_SOURCE_FIELD_ID,
    OSM_POPULATION_SOURCE_FIELD_ID,
    build_osm_place_node,
    build_osm_place_package,
)
from tools.experiment8.reference_presentation_policy import (
    ProminenceEvidenceKind,
    ProminenceTier,
    SemanticSubtype,
)
from tools.experiment8.renderer_tile_package import decode_tile_payload
from tools.experiment8.renderer_tile_package import encode_tile_payload
from tools.experiment8.semantic_model import (
    GeometryKind,
    LayerGroup,
    PlacementSourceKind,
)
from tools.experiment8.sourced_text import LayoutMode


_OSM = """<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="flight-alert-test">
  <node id="1" version="7" timestamp="2026-07-12T00:00:00Z" lat="37.9838100" lon="23.7275390">
    <tag k="capital" v="yes"/><tag k="name" v="Αθήνα"/><tag k="name:en" v="Athens"/>
    <tag k="place" v="city"/><tag k="population" v="664046"/>
  </node>
  <node id="2" version="3" timestamp="2026-07-12T00:00:00Z" lat="40.4167750" lon="-3.7037900">
    <tag k="capital" v="yes"/><tag k="name" v="Madrid"/><tag k="name:en" v="Madrid"/>
    <tag k="place" v="city"/><tag k="population" v="3223334"/>
  </node>
  <node id="3" version="2" timestamp="2026-07-12T00:00:00Z" lat="36.7462090" lon="-5.1612250">
    <tag k="name" v="Ronda"/><tag k="place" v="town"/><tag k="population" v="33877"/>
  </node>
  <node id="4" version="1" timestamp="2026-07-12T00:00:00Z" lat="38.0000000" lon="23.7000000">
    <tag k="name" v="Αττική"/><tag k="name:en" v="Attica"/><tag k="place" v="state"/>
  </node>
  <node id="5" version="1" timestamp="2026-07-12T00:00:00Z" lat="40.0000000" lon="-4.0000000">
    <tag k="name" v="España"/><tag k="name:en" v="Spain"/><tag k="place" v="country"/>
  </node>
  <node id="6" version="1" timestamp="2026-07-12T00:00:00Z" lat="50.0000000" lon="8.0000000">
    <tag k="capital" v="probably"/><tag k="name" v="Bad Evidence City"/>
    <tag k="place" v="city"/><tag k="population" v="1,000,000"/>
  </node>
  <node id="7" version="1" timestamp="2026-07-12T00:00:00Z" lat="37.9900000" lon="23.7100000">
    <tag k="name" v="Exact Locality"/><tag k="place" v="village"/>
  </node>
  <node id="8" version="1" timestamp="2026-07-12T00:00:00Z" lat="38.0100000" lon="23.7200000">
    <tag k="name" v="Not A Place Label"/><tag k="place" v="island"/>
  </node>
  <node id="9" version="1" timestamp="2026-07-12T00:00:00Z" lat="38.0200000" lon="23.7300000">
    <tag k="capital" v="8"/><tag k="name" v="Municipal Seat"/>
    <tag k="place" v="village"/><tag k="population" v="500"/>
  </node>
  <node id="10" version="1" timestamp="2026-07-12T00:00:00Z" lat="38.0300000" lon="23.7400000">
    <tag k="capital" v="4"/><tag k="name" v="Regional Seat"/>
    <tag k="place" v="town"/><tag k="population" v="500"/>
  </node>
  <node id="11" version="1" timestamp="2026-07-12T00:00:00Z" lat="38.0400000" lon="23.7500000">
    <tag k="capital" v="2"/><tag k="name" v="Undocumented Numeric National"/>
    <tag k="place" v="city"/><tag k="population" v="500"/>
  </node>
  <relation id="99" version="1" timestamp="2026-07-12T00:00:00Z">
    <member type="node" ref="1" role="label"/>
    <tag k="name" v="Inferred Region"/><tag k="place" v="region"/>
  </relation>
</osm>
""".encode("utf-8")


class OsmPlaceRendererTest(unittest.TestCase):
    def _build(self, node_id: int, *, zooms: tuple[int, ...] = (4, 5, 6)):
        return build_osm_place_node(
            dataset=parse_osm_xml_bytes(_OSM),
            node_id=node_id,
            source_generation_sha256=hashlib.sha256(_OSM).hexdigest(),
            classifier_sha256=hashlib.sha256(b"place-test-classifier").hexdigest(),
            primary_source_field_id=0x4F534D4E414D4501,
            english_source_field_id=0x4F534D4E414D4545,
            zooms=zooms,
        )

    def test_non_latin_capital_keeps_exact_primary_and_same_node_english(self) -> None:
        feature = self._build(1)

        self.assertEqual("Αθήνα", feature.name)
        self.assertEqual(SemanticSubtype.CAPITAL_MAJOR_CITY, feature.semantic_subtype)
        self.assertEqual(ProminenceTier.GLOBAL_MAJOR, feature.prominence_tier)
        self.assertEqual(
            ProminenceEvidenceKind.CAPITAL_LEVEL,
            feature.prominence_decision.evidence_kind,
        )
        self.assertEqual(
            OSM_CAPITAL_SOURCE_FIELD_ID,
            feature.prominence_decision.source_field_id,
        )
        self.assertEqual(425, feature.visibility_rule.min_zoom_centi)
        self.assertEqual(460, feature.visibility_rule.full_alpha_zoom_centi)
        self.assertEqual((4, 5, 6), tuple(sorted({tile.z for tile in feature.tiles})))

        for tile, records in feature.tiles.items():
            self.assertIsInstance(tile, TileKey)
            self.assertEqual(1, len(records))
            record = records[0]
            self.assertEqual("Αθήνα", record.sourced_text.primary_text)
            self.assertEqual("Athens", record.sourced_text.english_text)
            self.assertEqual(LayoutMode.PRIMARY_WITH_ENGLISH, record.sourced_text.layout_mode)
            variant = record.renderer_record.variant
            self.assertEqual(GeometryKind.POINT, variant.geometry.kind)
            self.assertEqual(LayerGroup.PLACES, variant.layer_group)
            self.assertEqual(
                PlacementSourceKind.DIRECT_SOURCE_POINT,
                variant.placement.placement_source_kind,
            )
            decoded = decode_tile_payload(tile, encode_tile_payload(tile, records))
            self.assertEqual(
                record.sourced_text.canonical_bytes,
                decoded.records[0].sourced_text.canonical_bytes,
            )

    def test_latin_city_stays_one_line_even_when_same_node_has_name_en(self) -> None:
        feature = self._build(2, zooms=(4,))
        record = next(iter(feature.tiles.values()))[0]

        self.assertEqual("Madrid", record.sourced_text.primary_text)
        self.assertIsNone(record.sourced_text.english_text)
        self.assertEqual(LayoutMode.SINGLE, record.sourced_text.layout_mode)
        self.assertEqual(SemanticSubtype.CAPITAL_MAJOR_CITY, feature.semantic_subtype)
        self.assertEqual(ProminenceTier.GLOBAL_MAJOR, feature.prominence_tier)

    def test_population_drives_tier_but_malformed_evidence_cannot_promote(self) -> None:
        town = self._build(3, zooms=(7,))
        malformed = self._build(6, zooms=(7,))

        self.assertEqual(SemanticSubtype.CITY_TOWN, town.semantic_subtype)
        self.assertEqual(ProminenceTier.LOCAL, town.prominence_tier)
        self.assertEqual(
            ProminenceEvidenceKind.POPULATION,
            town.prominence_decision.evidence_kind,
        )
        self.assertEqual(33_877, town.prominence_decision.evidence_value)
        self.assertEqual(
            OSM_POPULATION_SOURCE_FIELD_ID,
            town.prominence_decision.source_field_id,
        )
        self.assertEqual(SemanticSubtype.CITY_TOWN, malformed.semantic_subtype)
        self.assertEqual(ProminenceTier.LOCAL, malformed.prominence_tier)
        self.assertEqual(
            ProminenceEvidenceKind.TYPED_SUBTYPE_DEFAULT,
            malformed.prominence_decision.evidence_kind,
        )

    def test_numeric_capital_levels_only_promote_clearly_regional_admin_tiers(self) -> None:
        municipal = self._build(9, zooms=(7, 8, 9))
        regional = self._build(10, zooms=(5, 6))
        numeric_national = self._build(11, zooms=(6, 7))

        self.assertEqual(SemanticSubtype.LOCAL_PLACE, municipal.semantic_subtype)
        self.assertEqual(ProminenceTier.FINE, municipal.prominence_tier)
        self.assertEqual(
            ProminenceEvidenceKind.POPULATION,
            municipal.prominence_decision.evidence_kind,
        )
        self.assertEqual(500, municipal.prominence_decision.evidence_value)
        self.assertEqual(SemanticSubtype.CAPITAL_MAJOR_CITY, regional.semantic_subtype)
        self.assertEqual(ProminenceTier.REGIONAL_MAJOR, regional.prominence_tier)
        self.assertEqual(
            ProminenceEvidenceKind.CAPITAL_LEVEL,
            regional.prominence_decision.evidence_kind,
        )
        self.assertEqual(SemanticSubtype.CITY_TOWN, numeric_national.semantic_subtype)
        self.assertEqual(ProminenceTier.FINE, numeric_national.prominence_tier)
        self.assertEqual(
            ProminenceEvidenceKind.POPULATION,
            numeric_national.prominence_decision.evidence_kind,
        )

    def test_country_region_and_locality_are_typed_from_exact_place_tags(self) -> None:
        region = self._build(4, zooms=(5,))
        country = self._build(5, zooms=(4,))
        locality = self._build(7, zooms=(9,))

        self.assertEqual(SemanticSubtype.FIRST_ORDER_REGION, region.semantic_subtype)
        self.assertEqual(LayerGroup.REGIONS, next(iter(region.tiles.values()))[0].renderer_record.variant.layer_group)
        self.assertEqual(SemanticSubtype.COUNTRY_TERRITORY, country.semantic_subtype)
        self.assertEqual(ProminenceTier.GLOBAL_MAJOR, country.prominence_tier)
        self.assertEqual(SemanticSubtype.LOCAL_PLACE, locality.semantic_subtype)
        self.assertEqual(ProminenceTier.FINE, locality.prominence_tier)

    def test_package_omits_integer_lods_that_cannot_reach_the_visibility_band(self) -> None:
        local_city = self._build(3, zooms=(4, 5, 6, 7, 8, 9))
        fine_locality = self._build(7, zooms=(8, 9))

        self.assertEqual((6, 7, 8, 9), tuple(tile.z for tile in local_city.tiles))
        self.assertEqual((9,), tuple(tile.z for tile in fine_locality.tiles))
        self.assertEqual(1, len(fine_locality.tiles))
        self.assertEqual(1, len(next(iter(fine_locality.tiles.values()))))

    def test_relations_unknown_place_types_and_missing_names_cannot_supply_points(self) -> None:
        dataset = parse_osm_xml_bytes(_OSM)
        common = dict(
            dataset=dataset,
            source_generation_sha256=hashlib.sha256(_OSM).hexdigest(),
            classifier_sha256=hashlib.sha256(b"place-test-classifier").hexdigest(),
            primary_source_field_id=77,
            english_source_field_id=78,
            zooms=(5,),
        )
        with self.assertRaisesRegex(ValueError, "direct OSM place node"):
            build_osm_place_node(node_id=99, **common)
        with self.assertRaisesRegex(ValueError, "unsupported direct OSM place type"):
            build_osm_place_node(node_id=8, **common)

        missing_name = _OSM.replace(b'<tag k="name" v="Ronda"/>', b'')
        with self.assertRaisesRegex(ValueError, "canonical primary name"):
            build_osm_place_node(
                dataset=parse_osm_xml_bytes(missing_name),
                node_id=3,
                source_generation_sha256=hashlib.sha256(missing_name).hexdigest(),
                classifier_sha256=hashlib.sha256(b"place-test-classifier").hexdigest(),
                primary_source_field_id=77,
                zooms=(5,),
            )

    def test_package_is_byte_deterministic_hash_pinned_and_v3(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "europe-places.osm.xml"
            source.write_bytes(_OSM)
            expected_hash = hashlib.sha256(_OSM).hexdigest()
            outputs = (root / "first", root / "second")
            receipts = [
                build_osm_place_package(
                    source_path=source,
                    output_directory=output,
                    package_id="europe-place-pilot-v3",
                    node_ids=(1, 2, 3, 4, 5),
                    expected_source_sha256=expected_hash,
                    zooms=(4, 5, 6, 7),
                )
                for output in outputs
            ]

            for name in ("manifest.json", "records.fadictpack", "tile-index.bin", "build-receipt.json"):
                self.assertEqual((outputs[0] / name).read_bytes(), (outputs[1] / name).read_bytes())
            manifest = json.loads((outputs[0] / "manifest.json").read_text("utf-8"))
            self.assertEqual(3, manifest["schemaVersion"])
            self.assertEqual("flightalert.reference.renderer-tile.v1", manifest["payloadSchema"])
            self.assertFalse(manifest["coverage"]["completeWholeEarthDictionary"])
            self.assertEqual((1, 2, 3, 4, 5), receipts[0].node_ids)
            self.assertEqual(expected_hash, receipts[0].source_sha256)
            self.assertGreater(receipts[0].present_tile_count, 0)

            with self.assertRaisesRegex(ValueError, "source SHA-256"):
                build_osm_place_package(
                    source_path=source,
                    output_directory=root / "rejected",
                    package_id="europe-place-pilot-v3",
                    node_ids=(1,),
                    expected_source_sha256="0" * 64,
                    zooms=(5,),
                )

    def test_package_hash_and_records_use_the_same_immutable_source_bytes(self) -> None:
        replacement = _OSM.replace(
            b'<tag k="name" v="Ronda"/>',
            b'<tag k="name" v="Replacement Town"/>',
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.osm.xml"
            source.write_bytes(_OSM)
            output = root / "package"
            source_read_count = 0
            captured_payloads = {}
            real_read_bytes = Path.read_bytes
            real_write_package = osm_place_renderer.write_package

            def racing_read_bytes(path: Path) -> bytes:
                nonlocal source_read_count
                if path == source:
                    source_read_count += 1
                    return _OSM if source_read_count == 1 else replacement
                return real_read_bytes(path)

            def capture_write_package(
                output_directory,
                package_id,
                tile_payloads,
                **kwargs,
            ):
                captured_payloads.update(tile_payloads)
                return real_write_package(
                    output_directory,
                    package_id,
                    tile_payloads,
                    **kwargs,
                )

            with patch.object(Path, "read_bytes", racing_read_bytes), patch.object(
                osm_place_renderer,
                "write_package",
                side_effect=capture_write_package,
            ):
                receipt = build_osm_place_package(
                    source_path=source,
                    output_directory=output,
                    package_id="source-race-proof-v3",
                    node_ids=(3,),
                    expected_source_sha256=hashlib.sha256(_OSM).hexdigest(),
                    zooms=(6, 7),
                )

            decoded_names = {
                record.sourced_text.primary_text
                for tile, payload in captured_payloads.items()
                for record in decode_tile_payload(tile, payload).records
            }
            self.assertEqual(1, source_read_count)
            self.assertEqual({"Ronda"}, decoded_names)
            self.assertEqual(hashlib.sha256(_OSM).hexdigest(), receipt.source_sha256)


if __name__ == "__main__":
    unittest.main()
