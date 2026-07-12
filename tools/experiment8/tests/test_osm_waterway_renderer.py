from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.experiment8.model import TileKey
from tools.experiment8.osm_hydro_source import parse_osm_xml_bytes
from tools.experiment8.osm_waterway_renderer import (
    build_named_waterway_package,
    build_named_waterway_relation,
)
from tools.experiment8.reference_presentation_policy import ProminenceTier
from tools.experiment8.renderer_tile_package import (
    decode_tile_payload,
    encode_tile_payload,
)
from tools.experiment8.semantic_model import geometry_intersects_tile


_OSM = b"""<?xml version="1.0" encoding="UTF-8"?>
<osm version="0.6" generator="flight-alert-test">
  <node id="1" version="1" timestamp="2026-07-11T00:00:00Z" lat="39.0000000" lon="-76.0000000" />
  <node id="2" version="1" timestamp="2026-07-11T00:00:00Z" lat="39.1000000" lon="-75.7000000" />
  <node id="3" version="1" timestamp="2026-07-11T00:00:00Z" lat="39.2000000" lon="-75.4000000" />
  <way id="10" version="1" timestamp="2026-07-11T00:00:00Z">
    <nd ref="1"/><nd ref="2"/><nd ref="3"/>
    <tag k="name" v="Test River"/><tag k="waterway" v="river"/>
  </way>
  <relation id="20" version="1" timestamp="2026-07-11T00:00:00Z">
    <member type="way" ref="10" role=""/>
    <tag k="type" v="waterway"/><tag k="name" v="Test River"/>
  </relation>
</osm>
"""


class OsmWaterwayRendererTest(unittest.TestCase):
    def test_complete_named_relation_becomes_policy_bound_adaptive_path_labels(self) -> None:
        dataset = parse_osm_xml_bytes(_OSM)
        source_generation = hashlib.sha256(_OSM).hexdigest()
        classifier = hashlib.sha256(b"test-classifier").hexdigest()

        feature = build_named_waterway_relation(
            dataset=dataset,
            relation_id=20,
            source_generation_sha256=source_generation,
            classifier_sha256=classifier,
            primary_source_field_id=0x4F534D4E414D4501,
            zooms=(5, 6, 7),
        )

        self.assertEqual("Test River", feature.name)
        self.assertGreater(feature.complete_relation_length_m, 25_000)
        self.assertLess(feature.complete_relation_length_m, 500_000)
        self.assertEqual(ProminenceTier.REGIONAL_MAJOR, feature.prominence_tier)
        self.assertEqual(593, feature.visibility_rule.min_zoom_centi)
        self.assertEqual(628, feature.visibility_rule.full_alpha_zoom_centi)
        self.assertEqual((5, 6, 7), tuple(sorted({tile.z for tile in feature.tiles})))
        self.assertTrue(feature.tiles)

        for tile, records in feature.tiles.items():
            self.assertIsInstance(tile, TileKey)
            self.assertTrue(records)
            for item in records:
                self.assertEqual("Test River", item.sourced_text.primary_text)
                self.assertIsNone(item.sourced_text.english_text)
                self.assertTrue(
                    geometry_intersects_tile(item.renderer_record.variant.geometry, tile)
                )
                self.assertEqual(
                    feature.prominence_decision.semantic_priority,
                    item.renderer_record.variant.priority,
                )
            encoded = encode_tile_payload(tile, records)
            decoded = decode_tile_payload(tile, encoded)
            self.assertEqual(tile, decoded.coordinate)
            self.assertEqual(
                tuple(item.renderer_record for item in records),
                tuple(item.renderer_record for item in decoded.records),
            )
            self.assertEqual(
                tuple(item.sourced_text.canonical_bytes for item in records),
                tuple(item.sourced_text.canonical_bytes for item in decoded.records),
            )

    def test_build_is_byte_deterministic_and_rejects_wrong_relation_type(self) -> None:
        dataset = parse_osm_xml_bytes(_OSM)
        arguments = dict(
            dataset=dataset,
            relation_id=20,
            source_generation_sha256=hashlib.sha256(_OSM).hexdigest(),
            classifier_sha256=hashlib.sha256(b"test-classifier").hexdigest(),
            primary_source_field_id=77,
            zooms=(6,),
        )
        first = build_named_waterway_relation(**arguments)
        second = build_named_waterway_relation(**arguments)
        self.assertEqual(first, second)
        self.assertEqual(
            [encode_tile_payload(tile, first.tiles[tile]) for tile in sorted(first.tiles)],
            [encode_tile_payload(tile, second.tiles[tile]) for tile in sorted(second.tiles)],
        )

        wrong = _OSM.replace(b'<tag k="type" v="waterway"/>', b'<tag k="type" v="route"/>')
        with self.assertRaisesRegex(ValueError, "named waterway relation"):
            build_named_waterway_relation(
                dataset=parse_osm_xml_bytes(wrong),
                relation_id=20,
                source_generation_sha256=hashlib.sha256(wrong).hexdigest(),
                classifier_sha256=hashlib.sha256(b"test-classifier").hexdigest(),
                primary_source_field_id=77,
                zooms=(6,),
            )

    def test_real_package_builder_hash_pins_source_and_writes_schema_v3_runtime_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source.osm"
            source.write_bytes(_OSM)
            output = root / "world-experiment8-binary-v3"
            receipt = build_named_waterway_package(
                source_path=source,
                output_directory=output,
                package_id="world-experiment8-binary-v3",
                relation_ids=(20,),
                expected_source_sha256=hashlib.sha256(_OSM).hexdigest(),
                zooms=(5, 6, 7),
            )

            manifest = json.loads((output / "manifest.json").read_text("utf-8"))
            self.assertEqual(3, manifest["schemaVersion"])
            self.assertEqual(
                "flightalert.reference.renderer-tile.v1",
                manifest["payloadSchema"],
            )
            self.assertFalse(manifest["coverage"]["completeWholeEarthDictionary"])
            self.assertEqual(hashlib.sha256(_OSM).hexdigest(), receipt.source_sha256)
            self.assertEqual((20,), receipt.relation_ids)
            self.assertGreater(receipt.present_tile_count, 0)
            self.assertGreater((output / "records.fadictpack").stat().st_size, 0)
            self.assertGreater((output / "tile-index.bin").stat().st_size, 0)

            with self.assertRaisesRegex(ValueError, "source SHA-256"):
                build_named_waterway_package(
                    source_path=source,
                    output_directory=root / "rejected",
                    package_id="world-experiment8-binary-v3",
                    relation_ids=(20,),
                    expected_source_sha256="0" * 64,
                    zooms=(6,),
                )


if __name__ == "__main__":
    unittest.main()
