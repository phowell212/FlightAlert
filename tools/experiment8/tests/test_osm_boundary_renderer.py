from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path


FIXTURE = Path(__file__).with_name("fixtures") / "boundary-closure.opl"
SOURCE_SHA256 = hashlib.sha256(b"boundary fixture source").hexdigest()


class OsmBoundarySelectionTests(unittest.TestCase):
    def test_admin_level_mapping_covers_every_supported_level_exactly(self) -> None:
        from tools.experiment8.osm_boundary_renderer import admin_boundary_subtype
        from tools.experiment8.reference_presentation_policy import SemanticSubtype

        expected = {
            2: SemanticSubtype.INTERNATIONAL_BOUNDARY,
            4: SemanticSubtype.STATE_PROVINCE_BOUNDARY,
            6: SemanticSubtype.COUNTY_LOCAL_BOUNDARY,
        }
        for level in range(2, 12):
            self.assertIs(
                admin_boundary_subtype(str(level)),
                expected.get(level, SemanticSubtype.OTHER_ADMIN_BOUNDARY),
            )
        for unsupported in ("", "02", "1", "12", "county"):
            self.assertIsNone(admin_boundary_subtype(unsupported))

    def test_admits_coastline_and_maps_supported_admin_levels(self) -> None:
        from tools.experiment8.osm_boundary_renderer import select_boundary_opl
        from tools.experiment8.reference_presentation_policy import SemanticSubtype

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)

        keys = tuple((item.way.object_id, item.subtype) for item in selected.ways)
        self.assertIn((100, SemanticSubtype.COASTLINE), keys)
        self.assertIn((101, SemanticSubtype.INTERNATIONAL_BOUNDARY), keys)
        self.assertIn((102, SemanticSubtype.STATE_PROVINCE_BOUNDARY), keys)
        self.assertIn((103, SemanticSubtype.COUNTY_LOCAL_BOUNDARY), keys)
        self.assertIn((104, SemanticSubtype.OTHER_ADMIN_BOUNDARY), keys)
        self.assertIn((105, SemanticSubtype.OTHER_ADMIN_BOUNDARY), keys)
        self.assertNotIn(106, {way_id for way_id, _ in keys})
        self.assertNotIn(107, {way_id for way_id, _ in keys})
        self.assertEqual(keys, tuple(sorted(keys, key=lambda item: (item[0], item[1].value))))

    def test_relation_outer_and_inner_members_are_kept_and_pair_deduped(self) -> None:
        from tools.experiment8.osm_boundary_renderer import select_boundary_opl
        from tools.experiment8.reference_presentation_policy import SemanticSubtype

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)

        by_key = {(item.way.object_id, item.subtype): item for item in selected.ways}
        state = SemanticSubtype.STATE_PROVINCE_BOUNDARY
        other = SemanticSubtype.OTHER_ADMIN_BOUNDARY
        self.assertEqual(
            tuple((item.relation_id, item.role) for item in by_key[(108, state)].memberships),
            ((200, "outer"),),
        )
        self.assertEqual(
            tuple((item.relation_id, item.role) for item in by_key[(109, state)].memberships),
            ((200, "inner"),),
        )
        self.assertTrue(by_key[(111, state)].direct)
        self.assertEqual(
            tuple((item.relation_id, item.role) for item in by_key[(111, state)].memberships),
            ((200, "outer"), (201, "outer")),
        )
        self.assertIn((108, other), by_key)
        self.assertNotIn((110, state), by_key)
        self.assertEqual(
            len([item for item in selected.ways if item.way.object_id == 111 and item.subtype is state]),
            1,
        )


class OsmBoundaryRendererTests(unittest.TestCase):
    def test_omits_integer_lods_that_cannot_reach_the_outline_visibility_band(self) -> None:
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )
        from tools.experiment8.reference_presentation_policy import SemanticSubtype

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)
        county = next(
            item
            for item in selected.ways
            if item.way.object_id == 103
            and item.subtype is SemanticSubtype.COUNTY_LOCAL_BOUNDARY
        )
        coastline = next(
            item for item in selected.ways if item.subtype is SemanticSubtype.COASTLINE
        )
        other = next(
            item
            for item in selected.ways
            if item.subtype is SemanticSubtype.OTHER_ADMIN_BOUNDARY
        )

        feature = build_boundary_way_feature(
            county,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(4, 5, 6),
        )

        self.assertEqual({tile.z for tile in feature.tiles}, {6})
        coastline_feature = build_boundary_way_feature(
            coastline,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(4, 5, 7),
        )
        other_feature = build_boundary_way_feature(
            other,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(7, 8),
        )
        self.assertEqual({tile.z for tile in coastline_feature.tiles}, {7})
        self.assertEqual({tile.z for tile in other_feature.tiles}, {8})

    def test_builds_exact_v3_outline_records_from_the_pinned_policy(self) -> None:
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )
        from tools.experiment8.reference_presentation_policy import (
            SemanticSubtype,
            outline_visibility_rule,
        )
        from tools.experiment8.renderer_tile_package import (
            decode_tile_payload,
            encode_tile_payload,
        )
        from tools.experiment8.semantic_model import (
            FeatureKind,
            LayerGroup,
            empty_normalized_placement,
        )

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)
        coastline = next(
            item for item in selected.ways if item.subtype is SemanticSubtype.COASTLINE
        )
        feature = build_boundary_way_feature(
            coastline,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(7, 8),
        )
        rule = outline_visibility_rule(SemanticSubtype.COASTLINE)

        self.assertEqual(feature.way_id, 100)
        self.assertEqual(feature.subtype, SemanticSubtype.COASTLINE)
        self.assertEqual({tile.z for tile in feature.tiles}, {7, 8})
        for tile, records in feature.tiles.items():
            decoded = decode_tile_payload(tile, encode_tile_payload(tile, records))
            self.assertGreater(len(decoded.records), 0)
            for item in decoded.records:
                record = item.renderer_record
                variant = record.variant
                self.assertIsNone(item.sourced_text)
                self.assertIs(variant.feature_kind, FeatureKind.LINE)
                self.assertIs(variant.layer_group, LayerGroup.WATER)
                self.assertEqual(variant.semantic_subtype, SemanticSubtype.COASTLINE.value)
                self.assertEqual(variant.min_zoom_centi, rule.min_zoom_centi)
                self.assertEqual(variant.fade_in_centi, rule.full_alpha_zoom_centi)
                self.assertEqual(variant.max_zoom_centi, rule.max_zoom_centi)
                self.assertEqual(variant.fade_out_centi, rule.fade_out_zoom_centi)
                self.assertEqual(variant.draw_order, rule.draw_order)
                self.assertEqual(variant.priority, rule.priority)
                self.assertEqual(variant.placement, empty_normalized_placement())
                self.assertEqual(variant.flags, 2)

    def test_same_way_and_subtype_have_one_stable_dedupe_id_across_zooms(self) -> None:
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )
        from tools.experiment8.reference_presentation_policy import SemanticSubtype

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)
        item = next(
            item
            for item in selected.ways
            if item.way.object_id == 111
            and item.subtype is SemanticSubtype.STATE_PROVINCE_BOUNDARY
        )
        first = build_boundary_way_feature(
            item,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(4, 5),
        )
        second = build_boundary_way_feature(
            item,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(5, 4),
        )

        first_ids = {
            record.renderer_record.variant.dedupe_id
            for records in first.tiles.values()
            for record in records
        }
        second_ids = {
            record.renderer_record.variant.dedupe_id
            for records in second.tiles.values()
            for record in records
        }
        self.assertEqual(len(first_ids), 1)
        self.assertEqual(first_ids, second_ids)

    def test_complete_canonical_geometry_is_reused_across_tile_postings(self) -> None:
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )
        from tools.experiment8.reference_presentation_policy import SemanticSubtype

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)
        coastline = next(
            item for item in selected.ways if item.subtype is SemanticSubtype.COASTLINE
        )
        feature = build_boundary_way_feature(
            coastline,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(11,),
        )

        self.assertGreater(len(feature.tiles), 1)
        records = [
            item.renderer_record
            for tile_records in feature.tiles.values()
            for item in tile_records
        ]
        self.assertEqual(
            len({record.variant.canonical_variant_id for record in records}),
            1,
        )
        self.assertEqual(len({record.variant.geometry for record in records}), 1)
        self.assertEqual(len({record.posting.owner_tile for record in records}), 1)

    def test_closed_ring_keeps_three_noncollinear_vertices_plus_closure(self) -> None:
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )

        source = b"""\
n9001 v1 dV t2026-06-29T00:00:01Z x0.0000000 y0.0000000
n9002 v1 dV t2026-06-29T00:00:02Z x0.0001000 y0.0000000
n9003 v1 dV t2026-06-29T00:00:03Z x0.0001000 y0.0001000
n9004 v1 dV t2026-06-29T00:00:04Z x0.0000000 y0.0001000
w9000 v1 dV t2026-06-29T00:01:00Z Tnatural=coastline Nn9001,n9002,n9003,n9004,n9001
"""
        selected = select_boundary_opl(io.BytesIO(source))
        feature = build_boundary_way_feature(
            selected.ways[0],
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(11,),
        )
        geometry = next(
            iter(next(iter(feature.tiles.values())))
        ).renderer_record.variant.geometry
        points = tuple(
            zip(
                geometry.world_coordinate_numerators[0::2],
                geometry.world_coordinate_numerators[1::2],
            )
        )

        self.assertGreaterEqual(len(points), 4)
        self.assertEqual(points[0], points[-1])
        self.assertGreaterEqual(len(set(points[:-1])), 3)
        first, second, third = points[:3]
        self.assertNotEqual(
            (second[0] - first[0]) * (third[1] - first[1]),
            (second[1] - first[1]) * (third[0] - first[0]),
        )


class BoundaryStreamingPackageTests(unittest.TestCase):
    def test_rejects_index_larger_than_the_android_byte_array_bound(self) -> None:
        from tools.experiment8.renderer_tile_package import (
            RendererTilePackageError,
            _validate_zoom_ranges,
        )

        with self.assertRaisesRegex(RendererTilePackageError, "Android index"):
            _validate_zoom_ranges(((14, 0, (1 << 14) - 1, 0, (1 << 14) - 1),))

    def test_late_generator_failure_leaves_no_publishable_package(self) -> None:
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )
        from tools.experiment8.reference_presentation_policy import SemanticSubtype
        from tools.experiment8.renderer_tile_package import write_streaming_package

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)
        item = next(
            item for item in selected.ways if item.subtype is SemanticSubtype.COASTLINE
        )
        feature = build_boundary_way_feature(
            item,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(7,),
        )
        tile = min(feature.tiles, key=lambda value: (value.z, value.y, value.x))

        def failing_groups():
            yield tile, feature.tiles[tile]
            raise RuntimeError("fixture generator failed")

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "boundary-package"
            with self.assertRaisesRegex(RuntimeError, "fixture generator failed"):
                write_streaming_package(
                    output,
                    "fixture-boundary-v3",
                    ((7, 0, 127, 0, 127),),
                    failing_groups(),
                )

            self.assertFalse(output.exists())
            self.assertEqual(tuple(root.iterdir()), ())

    def test_writes_declared_index_without_materializing_all_tiles(self) -> None:
        from tools.experiment8.model import TileKey
        from tools.experiment8.osm_boundary_renderer import (
            build_boundary_way_feature,
            select_boundary_opl,
        )
        from tools.experiment8.reference_presentation_policy import SemanticSubtype
        from tools.experiment8.renderer_tile_package import (
            INDEX_ENTRY_BYTES,
            write_streaming_package,
        )

        with FIXTURE.open("rb") as stream:
            selected = select_boundary_opl(stream)
        item = next(
            item for item in selected.ways if item.subtype is SemanticSubtype.COASTLINE
        )
        feature = build_boundary_way_feature(
            item,
            nodes=selected.nodes,
            source_generation_sha256=SOURCE_SHA256,
            zooms=(7,),
        )
        groups = ((tile, feature.tiles[tile]) for tile in sorted(feature.tiles, key=lambda t: (t.z, t.y, t.x)))

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "boundary-package"
            result = write_streaming_package(
                output,
                "fixture-boundary-v3",
                ((7, 0, 127, 0, 127),),
                groups,
                complete_declared_scope=False,
                complete_whole_earth_dictionary=False,
            )
            manifest = json.loads((output / "manifest.json").read_text("utf-8"))

            self.assertEqual(manifest["schemaVersion"], 3)
            self.assertEqual(manifest["coverage"]["tileCount"], 16_384)
            self.assertEqual((output / "tile-index.bin").stat().st_size, 16_384 * INDEX_ENTRY_BYTES)
            self.assertEqual(result.present_tile_count, len(feature.tiles))
            self.assertGreater(result.records_bytes, 0)
            self.assertEqual(result.index_bytes, 16_384 * INDEX_ENTRY_BYTES)
            self.assertEqual(result.manifest_sha256, hashlib.sha256((output / "manifest.json").read_bytes()).hexdigest())
            self.assertTrue(all(type(tile) is TileKey for tile in feature.tiles))


if __name__ == "__main__":
    unittest.main()
