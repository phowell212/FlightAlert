from __future__ import annotations

import hashlib
import json
import struct
import tempfile
import unittest
import zlib
from pathlib import Path

from tools.experiment8.model import TileKey
from tools.experiment8.semantic_model import (
    FeatureKind,
    GeometryKind,
    LandEvidence,
    LayerGroup,
    PlacementSourceKind,
    ProminenceTier,
    ProtectedStatus,
    RendererGeometry,
    RendererRecord,
    TextEvidenceKind,
    TilePosting,
    empty_normalized_placement,
    make_canonical_variant,
    make_normalized_placement,
    renderer_geometry_fingerprint,
    renderer_record_bytes,
)
from tools.experiment8.sourced_text import create_sourced_map_text


CAIRO_TEXT = "\u0627\u0644\u0642\u0627\u0647\u0631\u0629"
PRESENTATION_POLICY_SHA256 = (
    "dce9bd4b789c0528318dbb184e17efe7465f444550ba0180efd82e1cb7219154"
)
CAIRO_TILE_BYTES = bytes.fromhex(
    "4641453854494c453100010000000000000000010000000b0200004e385431081122334455667788"
    "da1772533acb1e46000000000000000400000000e60100004e385431040807060504030201d6b39d"
    "92164e54e7110000000000000003000000000000000101d20000000000000000000000010e000000"
    "d8a7d984d982d8a7d987d8b1d8a94a0000004e385431020104000000000000000100000000000000"
    "02000000010000000000000001000000000000000100000000000000010000000000000001000000"
    "0000000001000000000000008a02000010270000b20200001027000014000000a208000030010000"
    "4e38543120464b52b337f3ff8b8bfff337b3524b46eef2b65592474582a91f2f7886ba8fde5ea193"
    "7c191278c21122334455667788999999999999999999999999999999999999999999999999e7544e"
    "16929db3d6b4bcfb355501e7a227852c9c99c6795532b3a06cac68706601c9000000000000008877"
    "665544332211d6b39d92164e54e70000000000000004010010000000000000000000000000000000"
    "0000000000000000100000000000000010000000000000018a02000010270000e80300001e000000"
    "0100000000000000a208000002010100000001000807060504030201555555555555555555555555"
    "5555555555555555555555555555555555555555010104dce9bd4b789c0528318dbb184e17efe7465"
    "f444550ba0180efd82e1cb7219154017b00000000000000000000000000730000003b2758eb2cc1"
    "c17b02dff76a8a494155d057020e48bbc0f67ed119ea108f1cd735014df49aafa0b507ca5517277c"
    "5f3db7faf855196a4b3a2124f4fae4e1f386fbebca7bd559b3d84758d17e11b5e619f04da7e5093d"
    "67d0f90506ab509e2ef6543a020002c9000000000000000e000000d8a7d984d982d8a7d987d8b1d8"
    "a901ca00000000000000010105000000436169726f"
)


def _cairo_renderer_record() -> RendererRecord:
    coordinate = TileKey(1, 0, 0)
    geometry = RendererGeometry(
        kind=GeometryKind.POINT,
        parts=(0,),
        world_denominator=4,
        world_coordinate_numerators=(1, 1),
        bounds_numerators=(1, 1, 1, 1),
    )
    geometry_identity = renderer_geometry_fingerprint(geometry)
    source_feature_sha256 = bytes.fromhex("1122334455667788" + "99" * 24)
    placement = make_normalized_placement(
        text=CAIRO_TEXT,
        source_feature_sha256=source_feature_sha256,
        placement_geometry_sha256=geometry_identity.full_sha256,
        text_evidence_kind=TextEvidenceKind.SOURCE_FIELD,
        text_source_field_id=201,
        placement_source_feature_id=int.from_bytes(
            source_feature_sha256[:8], "big"
        ),
        placement_geometry_id=geometry_identity.hot_id,
        source_tile=coordinate,
        source_zoom=1,
        source_declared_extent=4096,
        source_edge_domain=(0, 0, 4096, 4096),
        placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_POINT,
        display_min_zoom_centi=650,
        display_max_zoom_centi=10_000,
        spacing_px=1_000,
        max_angle_degrees=30,
        collision_group=1,
        semantic_priority=2_210,
        prominence_tier=ProminenceTier.LOCAL,
        provider_rank=1,
        complete_geometry_measure_bucket=1,
        prominence_rule_id=0x0102030405060708,
        prominence_decision_sha256=b"\x55" * 32,
        avoid_edges=True,
        keep_upright=True,
        active_band_limit=4,
        style_policy_sha256=bytes.fromhex(PRESENTATION_POLICY_SHA256),
        provider_feature_id=123,
    )
    variant = make_canonical_variant(
        dedupe_id=0x0102030405060708,
        geometry_id=geometry_identity.hot_id,
        source_layer_id=17,
        source_scale_band_id=3,
        layer_group=LayerGroup.PLACES,
        feature_kind=FeatureKind.LABEL,
        semantic_subtype=210,
        source_style_layer_ids=(),
        render_style_token_ids=(),
        text=CAIRO_TEXT,
        geometry=geometry,
        min_zoom_centi=650,
        max_zoom_centi=10_000,
        fade_in_centi=690,
        fade_out_centi=10_000,
        draw_order=20,
        priority=2_210,
        placement=placement,
        land_evidence=LandEvidence.NOT_APPLICABLE,
        protected_status=ProtectedStatus.NOT_APPLICABLE,
        flags=0,
    )
    return RendererRecord(
        posting=TilePosting(
            requested_tile=coordinate,
            feature_id=0x8877665544332211,
            canonical_variant_id=variant.canonical_variant_id,
            owner_tile=coordinate,
            world_wrap=0,
        ),
        variant=variant,
    )


def _cairo_sourced_text():
    return create_sourced_map_text(
        primary=CAIRO_TEXT,
        primary_source_field_id=201,
        declared_english="Cairo",
        english_source_field_id=202,
    )


def _line_renderer_record(
    requested_tile: TileKey = TileKey(1, 0, 0),
) -> RendererRecord:
    geometry = RendererGeometry(
        kind=GeometryKind.PATH,
        parts=(0,),
        world_denominator=4,
        world_coordinate_numerators=(0, 0, 1, 1),
        bounds_numerators=(0, 0, 1, 1),
    )
    variant = make_canonical_variant(
        dedupe_id=0x1020304050607080,
        geometry_id=renderer_geometry_fingerprint(geometry).hot_id,
        source_layer_id=18,
        source_scale_band_id=4,
        layer_group=LayerGroup.WATER,
        feature_kind=FeatureKind.LINE,
        semantic_subtype=560,
        source_style_layer_ids=(),
        render_style_token_ids=(),
        text=None,
        geometry=geometry,
        min_zoom_centi=100,
        max_zoom_centi=600,
        fade_in_centi=200,
        fade_out_centi=500,
        draw_order=10,
        priority=0,
        placement=empty_normalized_placement(),
        land_evidence=LandEvidence.NOT_APPLICABLE,
        protected_status=ProtectedStatus.NOT_APPLICABLE,
        flags=0,
    )
    return RendererRecord(
        posting=TilePosting(
            requested_tile=requested_tile,
            feature_id=0x0123456789ABCDEF,
            canonical_variant_id=variant.canonical_variant_id,
            owner_tile=TileKey(1, 0, 0),
            world_wrap=0,
        ),
        variant=variant,
    )


class RendererTilePayloadGoldenTests(unittest.TestCase):
    def test_encoder_matches_the_kotlin_cairo_golden_fixture(self) -> None:
        try:
            from tools.experiment8.renderer_tile_package import (
                RendererTileRecord,
                encode_tile_payload,
            )
        except ModuleNotFoundError:
            self.fail("renderer tile package encoder is not implemented")

        actual = encode_tile_payload(
            TileKey(1, 0, 0),
            [RendererTileRecord(_cairo_renderer_record(), _cairo_sourced_text())],
        )

        self.assertEqual(actual, CAIRO_TILE_BYTES)

    def test_decoder_reads_the_kotlin_cairo_golden_fixture(self) -> None:
        from tools.experiment8.renderer_tile_package import decode_tile_payload

        decoded = decode_tile_payload(TileKey(1, 0, 0), CAIRO_TILE_BYTES)

        self.assertEqual(decoded.coordinate, TileKey(1, 0, 0))
        self.assertEqual(len(decoded.records), 1)
        record = decoded.records[0]
        self.assertEqual(record.renderer_record.posting.feature_id, 0x8877665544332211)
        self.assertEqual(
            record.renderer_record.variant.canonical_variant_id,
            0x461ECB3A537217DA,
        )
        self.assertEqual(record.sourced_text.primary_text, CAIRO_TEXT)
        self.assertEqual(record.sourced_text.primary_source_field_id, 201)
        self.assertEqual(record.sourced_text.english_text, "Cairo")

    def test_decoder_rejects_coordinate_and_record_count_drift(self) -> None:
        from tools.experiment8.renderer_tile_package import (
            RendererTilePackageError,
            decode_tile_payload,
        )

        changed_x = bytearray(CAIRO_TILE_BYTES)
        changed_x[11:15] = (1).to_bytes(4, "little")
        excess_count = bytearray(CAIRO_TILE_BYTES)
        excess_count[19:23] = (65_537).to_bytes(4, "little")

        for payload, message in (
            (bytes(changed_x), "coordinate"),
            (bytes(excess_count), "record count"),
        ):
            with self.subTest(message=message), self.assertRaisesRegex(
                RendererTilePackageError, message
            ):
                decode_tile_payload(TileKey(1, 0, 0), payload)

    def test_label_and_nonlabel_sourced_text_presence_is_exact(self) -> None:
        from tools.experiment8.renderer_tile_package import (
            RendererTilePackageError,
            RendererTileRecord,
            decode_tile_payload,
            encode_tile_payload,
        )

        with self.assertRaisesRegex(RendererTilePackageError, "labels require"):
            encode_tile_payload(
                TileKey(1, 0, 0),
                [RendererTileRecord(_cairo_renderer_record(), None)],
            )
        with self.assertRaisesRegex(RendererTilePackageError, "non-label"):
            encode_tile_payload(
                TileKey(1, 0, 0),
                [RendererTileRecord(_line_renderer_record(), _cairo_sourced_text())],
            )

        line_payload = encode_tile_payload(
            TileKey(1, 0, 0),
            [RendererTileRecord(_line_renderer_record(), None)],
        )
        decoded_line = decode_tile_payload(TileKey(1, 0, 0), line_payload)
        self.assertIsNone(decoded_line.records[0].sourced_text)
        self.assertIs(
            decoded_line.records[0].renderer_record.variant.feature_kind,
            FeatureKind.LINE,
        )

        label_without_sourced_text = CAIRO_TILE_BYTES[:550] + b"\0\0\0\0"
        with self.assertRaisesRegex(RendererTilePackageError, "labels require"):
            decode_tile_payload(TileKey(1, 0, 0), label_without_sourced_text)

        line_bytes = renderer_record_bytes(_line_renderer_record())
        sourced = _cairo_sourced_text()
        nonlabel_with_sourced_text = b"".join(
            (
                b"FAE8TILE1\0",
                bytes((1,)),
                (0).to_bytes(4, "little"),
                (0).to_bytes(4, "little"),
                (1).to_bytes(4, "little"),
                len(line_bytes).to_bytes(4, "little"),
                line_bytes,
                len(sourced.canonical_bytes).to_bytes(4, "little"),
                sourced.full_sha256,
                sourced.canonical_bytes,
            )
        )
        with self.assertRaisesRegex(RendererTilePackageError, "non-label"):
            decode_tile_payload(TileKey(1, 0, 0), nonlabel_with_sourced_text)

    def test_encoder_rejects_requested_tile_and_record_count_bounds(self) -> None:
        from tools.experiment8.renderer_tile_package import (
            RendererTilePackageError,
            RendererTileRecord,
            encode_tile_payload,
        )

        sourced = _cairo_sourced_text()
        with self.assertRaisesRegex(RendererTilePackageError, "requested tile"):
            encode_tile_payload(
                TileKey(1, 0, 0),
                [
                    RendererTileRecord(
                        RendererRecord(
                            posting=TilePosting(
                                requested_tile=TileKey(1, 1, 0),
                                feature_id=0x8877665544332211,
                                canonical_variant_id=(
                                    _cairo_renderer_record().variant.canonical_variant_id
                                ),
                                owner_tile=TileKey(1, 0, 0),
                                world_wrap=0,
                            ),
                            variant=_cairo_renderer_record().variant,
                        ),
                        sourced,
                    )
                ],
            )

        repeated = RendererTileRecord(_cairo_renderer_record(), sourced)
        with self.assertRaisesRegex(RendererTilePackageError, "record count"):
            encode_tile_payload(TileKey(1, 0, 0), [repeated] * 65_537)


class RendererTilePackageWriterTests(unittest.TestCase):
    def test_raw_deflate_and_index_entry_match_the_android_reader(self) -> None:
        from tools.experiment8.renderer_tile_package import (
            INDEX_ENTRY_BYTES,
            encode_index_entry,
            raw_deflate,
            raw_hash32,
        )

        compressed = raw_deflate(CAIRO_TILE_BYTES)
        hash32 = int.from_bytes(hashlib.sha256(CAIRO_TILE_BYTES).digest()[:4], "big")
        entry = encode_index_entry(
            offset=0,
            compressed_length=len(compressed),
            raw_length=len(CAIRO_TILE_BYTES),
            raw_hash32=hash32,
        )

        self.assertEqual(
            zlib.decompress(compressed, wbits=-zlib.MAX_WBITS),
            CAIRO_TILE_BYTES,
        )
        self.assertEqual(raw_hash32(CAIRO_TILE_BYTES), hash32)
        self.assertEqual(len(entry), INDEX_ENTRY_BYTES)
        self.assertEqual(
            struct.unpack("<QIIII", entry),
            (0, len(compressed), len(CAIRO_TILE_BYTES), hash32, 1),
        )

    def test_package_artifacts_are_deterministic_schema_v3_bytes(self) -> None:
        from tools.experiment8.reference_presentation_policy import (
            PRESENTATION_POLICY_SHA256 as AUTHORITATIVE_PRESENTATION_SHA256,
        )
        from tools.experiment8.renderer_tile_package import (
            PAYLOAD_SCHEMA,
            SOURCED_TEXT_POLICY_SHA256,
            build_package,
            raw_deflate,
        )
        from tools.experiment8.sourced_text import UNICODE_SCRIPT_PROFILE_SHA256

        payloads = {TileKey(1, 0, 0): CAIRO_TILE_BYTES}

        first = build_package("cairo-binary-v3", payloads)
        second = build_package("cairo-binary-v3", dict(reversed(payloads.items())))
        manifest = json.loads(first.manifest_bytes)

        self.assertEqual(first, second)
        self.assertEqual(first.records_bytes, raw_deflate(CAIRO_TILE_BYTES))
        self.assertTrue(first.manifest_bytes.endswith(b"\n"))
        self.assertEqual(manifest["schemaVersion"], 3)
        self.assertEqual(manifest["payloadSchema"], PAYLOAD_SCHEMA)
        self.assertEqual(
            manifest["presentationPolicySha256"],
            AUTHORITATIVE_PRESENTATION_SHA256,
        )
        self.assertEqual(
            manifest["sourcedTextPolicySha256"],
            SOURCED_TEXT_POLICY_SHA256,
        )
        self.assertEqual(
            manifest["unicodeScriptProfileSha256"],
            UNICODE_SCRIPT_PROFILE_SHA256,
        )
        self.assertEqual(
            manifest["coverage"],
            {
                "completeDeclaredScope": True,
                "completeWholeEarthDictionary": False,
                "tileCount": 1,
                "zoomRanges": [
                    {
                        "tileCount": 1,
                        "xMax": 0,
                        "xMin": 0,
                        "yMax": 0,
                        "yMin": 0,
                        "z": 1,
                    }
                ],
            },
        )

    def test_package_writer_emits_exact_runtime_files(self) -> None:
        from tools.experiment8.renderer_tile_package import write_package

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "cairo-binary-v3"

            artifacts = write_package(
                output,
                "cairo-binary-v3",
                {TileKey(1, 0, 0): CAIRO_TILE_BYTES},
            )

            self.assertEqual(
                sorted(path.name for path in output.iterdir()),
                ["manifest.json", "records.fadictpack", "tile-index.bin"],
            )
            self.assertEqual(
                (output / "manifest.json").read_bytes(), artifacts.manifest_bytes
            )
            self.assertEqual(
                (output / "records.fadictpack").read_bytes(),
                artifacts.records_bytes,
            )
            self.assertEqual(
                (output / "tile-index.bin").read_bytes(), artifacts.index_bytes
            )

    def test_package_rejects_payload_coordinate_drift(self) -> None:
        from tools.experiment8.renderer_tile_package import (
            RendererTilePackageError,
            build_package,
        )

        with self.assertRaisesRegex(RendererTilePackageError, "coordinate"):
            build_package(
                "wrong-coordinate",
                {TileKey(1, 1, 0): CAIRO_TILE_BYTES},
            )


if __name__ == "__main__":
    unittest.main()
