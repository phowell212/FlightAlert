from __future__ import annotations

import hashlib
import inspect
import struct
import unittest
from dataclasses import FrozenInstanceError, replace

import tools.experiment8.semantic_model as semantic_model

from tools.experiment8.model import TileKey
from tools.experiment8.semantic_model import (
    CANONICAL_MAGIC,
    MAX_GEOMETRY_POINTS,
    MAX_RENDERER_HEAP_BYTES,
    CanonicalEncodingError,
    CanonicalVariant,
    FeatureKind,
    GeometryKind,
    HeapLimitError,
    HotIdRegistry,
    IdentityEvidence,
    IdentityCollisionError,
    LandEvidence,
    LayerGroup,
    NormalizedPlacement,
    ParentLabelBand,
    PlacementSourceKind,
    PlacementMembership,
    ProminenceTier,
    ProtectedStatus,
    RendererGeometry,
    SemanticModelError,
    SourceGeometry,
    SourceOccurrence,
    TextEvidenceKind,
    TilePosting,
    assign_canonical_ids,
    canonical_line_label_candidate_bytes,
    canonical_typed_properties_bytes,
    canonical_variant_bytes,
    decode_canonical_variant,
    decode_identity_evidence,
    decode_renderer_geometry,
    decode_source_geometry,
    decode_source_occurrence,
    decode_tile_posting,
    derive_descendant_memberships,
    encode_canonical_variant,
    encode_identity_evidence,
    encode_renderer_geometry,
    encode_source_geometry,
    encode_source_occurrence,
    encode_tile_posting,
    feature_fingerprint,
    geometry_intersects_tile,
    is_disputed,
    interpolate_fixed_integer,
    label_repeat_phase,
    line_label_candidate_fingerprint,
    empty_normalized_placement,
    make_canonical_variant,
    make_normalized_placement,
    non_point_dedupe_id,
    point_wrap_anchor,
    point_label_dedupe_fingerprint,
    reconstruct_renderer_records,
    renderer_contract_hash,
    renderer_geometry_fingerprint,
    renderer_geometry_from_source,
    renderer_order_key,
    renderer_record_bytes,
    renderer_record_heap_weight,
    renderer_records_heap_weight,
    replace_canonical_variant,
    replace_normalized_placement,
    source_occurrence_fingerprint,
    source_edge_eligible,
    source_audit_sha256,
    validate_public_land_presentation,
    variant_fingerprint,
    viewport_label_candidates,
    whole_label_accepted,
)


def _sha(byte: int) -> bytes:
    return bytes([byte]) * 32


def _source_path(
    *,
    tile: TileKey = TileKey(1, 0, 0),
    extent: int = 100,
    coordinates: tuple[int, ...] = (10, 50, 90, 50),
    parts: tuple[int, ...] = (0,),
) -> SourceGeometry:
    xs = coordinates[0::2]
    ys = coordinates[1::2]
    return SourceGeometry(
        kind=GeometryKind.PATH,
        tile_key=tile,
        source_zoom=tile.z,
        declared_extent=extent,
        parts=parts,
        source_local_coordinates=coordinates,
        bounds=(min(xs), min(ys), max(xs), max(ys)),
    )


def _source_point(
    *,
    tile: TileKey = TileKey(1, 0, 0),
    extent: int = 100,
    x: int = 25,
    y: int = 25,
) -> SourceGeometry:
    return SourceGeometry(
        kind=GeometryKind.POINT,
        tile_key=tile,
        source_zoom=tile.z,
        declared_extent=extent,
        parts=(0,),
        source_local_coordinates=(x, y),
        bounds=(x, y, x, y),
    )


def _placement(
    geometry: RendererGeometry,
    *,
    text: str = "Chester River",
    source_tile: TileKey = TileKey(1, 0, 0),
    source_feature_sha256: bytes = _sha(0x11),
    style_policy_sha256: bytes = _sha(0x33),
    active_band_limit: int = 4,
    semantic_priority: int = 1120,
    prominence_tier: ProminenceTier = ProminenceTier.REGIONAL_MAJOR,
    provider_rank: int | None = 7,
    complete_geometry_measure_bucket: int = 42,
    prominence_rule_id: int = 0x123456789ABCDEF0,
    prominence_decision_sha256: bytes = _sha(0x77),
    placement_source_kind: PlacementSourceKind = PlacementSourceKind.EXACT_PARENT_PATH,
    identity_registry: HotIdRegistry | None = None,
) -> NormalizedPlacement:
    geometry_identity = renderer_geometry_fingerprint(geometry)
    return make_normalized_placement(
        text=text,
        source_feature_sha256=source_feature_sha256,
        placement_geometry_sha256=geometry_identity.full_sha256,
        text_evidence_kind=TextEvidenceKind.SOURCE_FIELD,
        text_source_field_id=41,
        placement_source_feature_id=int.from_bytes(source_feature_sha256[:8], "big"),
        placement_geometry_id=geometry_identity.hot_id,
        source_tile=source_tile,
        source_zoom=source_tile.z,
        source_declared_extent=100,
        source_edge_domain=(0, 0, 100, 100),
        placement_source_kind=placement_source_kind,
        display_min_zoom_centi=100,
        display_max_zoom_centi=600,
        spacing_px=1000,
        max_angle_degrees=30,
        collision_group=7,
        semantic_priority=semantic_priority,
        prominence_tier=prominence_tier,
        provider_rank=provider_rank,
        complete_geometry_measure_bucket=complete_geometry_measure_bucket,
        prominence_rule_id=prominence_rule_id,
        prominence_decision_sha256=prominence_decision_sha256,
        avoid_edges=True,
        keep_upright=True,
        active_band_limit=active_band_limit,
        style_policy_sha256=style_policy_sha256,
        identity_registry=identity_registry,
    )


def _variant(
    *,
    text: str = "Chester River",
    geometry: RendererGeometry | None = None,
    placement: NormalizedPlacement | None = None,
    layer_group: LayerGroup = LayerGroup.WATER,
    feature_kind: FeatureKind = FeatureKind.LABEL,
    render_style_token_ids: tuple[int, ...] = (401, 402),
    land_evidence: LandEvidence = LandEvidence.NOT_APPLICABLE,
    protected_status: ProtectedStatus = ProtectedStatus.NOT_APPLICABLE,
    draw_order: int = 20,
    priority: int = 50,
    identity_registry: HotIdRegistry | None = None,
) -> CanonicalVariant:
    geometry = geometry or renderer_geometry_from_source(_source_path())
    placement = placement or _placement(geometry, text=text)
    return make_canonical_variant(
        dedupe_id=0x0102030405060708,
        geometry_id=renderer_geometry_fingerprint(geometry).hot_id,
        source_layer_id=17,
        source_scale_band_id=3,
        layer_group=layer_group,
        feature_kind=feature_kind,
        semantic_subtype=9,
        source_style_layer_ids=(101, 103),
        render_style_token_ids=render_style_token_ids,
        text=text,
        geometry=geometry,
        min_zoom_centi=100,
        max_zoom_centi=600,
        fade_in_centi=25,
        fade_out_centi=30,
        draw_order=draw_order,
        priority=priority,
        placement=placement,
        land_evidence=land_evidence,
        protected_status=protected_status,
        flags=5,
        identity_registry=identity_registry,
    )


def _line_variant(
    *,
    dedupe_id: int = 0x0102030405060708,
    render_style_token_ids: tuple[int, ...] = (501,),
) -> CanonicalVariant:
    geometry = renderer_geometry_from_source(_source_path())
    return make_canonical_variant(
        dedupe_id=dedupe_id,
        geometry_id=renderer_geometry_fingerprint(geometry).hot_id,
        source_layer_id=17,
        source_scale_band_id=3,
        layer_group=LayerGroup.WATER,
        feature_kind=FeatureKind.LINE,
        semantic_subtype=9,
        source_style_layer_ids=(101,),
        render_style_token_ids=render_style_token_ids,
        text=None,
        geometry=geometry,
        min_zoom_centi=100,
        max_zoom_centi=600,
        fade_in_centi=25,
        fade_out_centi=30,
        draw_order=20,
        priority=50,
        placement=empty_normalized_placement(),
        land_evidence=LandEvidence.NOT_APPLICABLE,
        protected_status=ProtectedStatus.NOT_APPLICABLE,
        flags=0,
    )


class NumericContractTests(unittest.TestCase):
    def test_placement_source_kind_wire_values_are_explicit_and_stable(self) -> None:
        self.assertTrue(hasattr(semantic_model, "PlacementSourceKind"))
        source_kind = semantic_model.PlacementSourceKind
        self.assertEqual(
            {member.name: member.value for member in source_kind},
            {
                "NONE": 0,
                "DIRECT_SOURCE_POINT": 1,
                "SOURCE_OWNED_AREA_LABEL_POINT": 2,
                "DIRECT_SOURCE_PATH": 3,
                "EXACT_PARENT_PATH": 4,
            },
        )

    def test_prominence_tier_wire_values_are_explicit_and_stable(self) -> None:
        self.assertEqual(
            {member.name: member.value for member in ProminenceTier},
            {
                "GLOBAL_MAJOR": 0,
                "REGIONAL_MAJOR": 1,
                "LOCAL": 2,
                "FINE": 3,
            },
        )

    def test_exact_enum_values_are_frozen(self) -> None:
        self.assertEqual(
            {member.name: member.value for member in LayerGroup},
            {
                "PLACES": 1,
                "WATER": 2,
                "REGIONS": 3,
                "PUBLIC_LANDS": 4,
                "TRANSPORTATION": 5,
                "CONTEXT": 6,
            },
        )
        self.assertEqual(
            {member.name: member.value for member in FeatureKind},
            {"LABEL": 1, "LINE": 2, "POLYGON_OUTLINE": 3},
        )
        self.assertEqual(
            {member.name: member.value for member in GeometryKind},
            {"POINT": 1, "PATH": 2, "POLYGON": 3},
        )

    def test_dispute_zero_is_false_and_nonzero_is_true(self) -> None:
        self.assertFalse(is_disputed(0))
        self.assertTrue(is_disputed(7))
        with self.assertRaises(SemanticModelError):
            is_disputed(False)

    def test_canonical_properties_are_input_order_independent_and_float_free(self) -> None:
        first = canonical_typed_properties_bytes(
            {"name": "Caf\u00e9", "rank": 7, "visible": True, "none": None}
        )
        second = canonical_typed_properties_bytes(
            {"none": None, "visible": True, "rank": 7, "name": "Caf\u00e9"}
        )

        self.assertEqual(first, second)
        with self.assertRaisesRegex(CanonicalEncodingError, "float"):
            canonical_typed_properties_bytes({"rank": 7.0})
        with self.assertRaisesRegex(CanonicalEncodingError, "NFC"):
            canonical_typed_properties_bytes({"name": "Cafe\u0301"})

    def test_malformed_string_and_table_counts_fail_before_allocation(self) -> None:
        source = _source_path()
        occurrence = SourceOccurrence(
            source.tile_key, 0, 1, 2, "Water line", source, "01" * 32
        )
        encoded_occurrence = bytearray(encode_source_occurrence(occurrence))
        encoded_occurrence[33:37] = (1_048_577).to_bytes(4, "little")
        with self.assertRaisesRegex(CanonicalEncodingError, "UTF-8 byte ceiling"):
            decode_source_occurrence(bytes(encoded_occurrence))

        variant = _variant()
        encoded_variant = bytearray(encode_canonical_variant(variant))
        payload_start = encoded_variant.find(b"N8T1\x04")
        self.assertGreaterEqual(payload_start, 0)
        encoded_variant[payload_start + 43 : payload_start + 47] = (0xFFFFFFFF).to_bytes(
            4, "little"
        )
        with self.assertRaisesRegex(CanonicalEncodingError, "source-style count"):
            decode_canonical_variant(bytes(encoded_variant))

    def test_canonical_table_ids_use_lexicographic_bytes_not_encounter_order(self) -> None:
        self.assertEqual(
            assign_canonical_ids([b"zeta", b"alpha", b"zeta", b"middle"]),
            {b"alpha": 0, b"middle": 1, b"zeta": 2},
        )

    def test_style_interpolation_is_exact_integer_math_with_ties_away_from_zero(self) -> None:
        self.assertEqual(interpolate_fixed_integer(100, 200, 0, 100, 25), 125)
        self.assertEqual(interpolate_fixed_integer(0, 1, 0, 2, 1), 1)
        self.assertEqual(interpolate_fixed_integer(0, -1, 0, 2, 1), -1)
        self.assertEqual(interpolate_fixed_integer(100, 200, 0, 100, -5), 100)
        self.assertEqual(interpolate_fixed_integer(100, 200, 0, 100, 105), 200)
        with self.assertRaisesRegex(SemanticModelError, "integer"):
            interpolate_fixed_integer(0, 1.0, 0, 100, 50)


class GeometryContractTests(unittest.TestCase):
    def test_source_geometry_preserves_declared_extent_and_provider_buffers(self) -> None:
        geometry = _source_path(
            extent=4096,
            coordinates=(-128, 25, 4224, 4100),
        )

        self.assertEqual(geometry.declared_extent, 4096)
        self.assertEqual(geometry.source_local_coordinates, (-128, 25, 4224, 4100))
        self.assertEqual(geometry.bounds, (-128, 25, 4224, 4100))
        rendered = renderer_geometry_from_source(geometry)
        self.assertLess(rendered.bounds_numerators[0], 0)
        self.assertGreater(rendered.bounds_numerators[2], rendered.world_denominator // 2)

    def test_geometry_is_strict_immutable_and_validates_parts_bounds_and_ceiling(self) -> None:
        geometry = _source_path()
        with self.assertRaises(FrozenInstanceError):
            geometry.parts = (0, 1)  # type: ignore[misc]
        bad_values = (
            {"parts": [0]},
            {"parts": (1,)},
            {"parts": (0, 0)},
            {"source_local_coordinates": (0, 0, 1)},
            {"bounds": (0, 0, 999, 999)},
            {"source_local_coordinates": (True, 0, 1, 1)},
        )
        for changes in bad_values:
            with self.subTest(changes=changes), self.assertRaises(SemanticModelError):
                replace(geometry, **changes)

        import tools.experiment8.semantic_model as semantic_model

        original_limit = semantic_model.MAX_GEOMETRY_POINTS
        try:
            semantic_model.MAX_GEOMETRY_POINTS = 1
            with self.assertRaisesRegex(SemanticModelError, "point-count ceiling"):
                _source_path(coordinates=(0, 0, 1, 1))
        finally:
            semantic_model.MAX_GEOMETRY_POINTS = original_limit
        self.assertGreater(MAX_GEOMETRY_POINTS, 1)

    def test_path_and_polygon_structure_is_not_repaired(self) -> None:
        with self.assertRaisesRegex(SemanticModelError, "at least two"):
            _source_path(coordinates=(1, 1))
        polygon = SourceGeometry(
            kind=GeometryKind.POLYGON,
            tile_key=TileKey(0, 0, 0),
            source_zoom=0,
            declared_extent=100,
            parts=(0,),
            source_local_coordinates=(0, 0, 10, 0, 10, 10, 0, 0),
            bounds=(0, 0, 10, 10),
        )
        reversed_polygon = replace(
            polygon,
            source_local_coordinates=(0, 0, 10, 10, 10, 0, 0, 0),
        )
        self.assertNotEqual(encode_source_geometry(polygon), encode_source_geometry(reversed_polygon))
        with self.assertRaisesRegex(SemanticModelError, "closed"):
            replace(polygon, source_local_coordinates=(0, 0, 10, 0, 10, 10, 0, 1))

    def test_cross_lod_equal_points_have_identical_reduced_rational_geometry(self) -> None:
        low = renderer_geometry_from_source(
            _source_point(tile=TileKey(1, 1, 1), extent=100, x=0, y=0)
        )
        high = renderer_geometry_from_source(
            _source_point(tile=TileKey(2, 2, 2), extent=100, x=0, y=0)
        )

        self.assertEqual(low, high)
        self.assertEqual(low.world_denominator, 2)
        self.assertEqual(low.world_coordinate_numerators, (1, 1))

        with self.assertRaisesRegex(SemanticModelError, "reduced"):
            RendererGeometry(
                GeometryKind.POINT,
                (0,),
                4,
                (2, 2),
                (2, 2, 2, 2),
            )

    def test_antimeridian_point_identity_wraps_only_for_identity(self) -> None:
        west = renderer_geometry_from_source(
            _source_point(tile=TileKey(1, 0, 0), extent=100, x=0, y=0)
        )
        east = renderer_geometry_from_source(
            _source_point(tile=TileKey(1, 1, 0), extent=100, x=100, y=0)
        )

        self.assertNotEqual(west.world_coordinate_numerators, east.world_coordinate_numerators)
        self.assertEqual(point_wrap_anchor(west), point_wrap_anchor(east))
        self.assertEqual(east.world_coordinate_numerators[0], east.world_denominator)

    def test_exact_path_intersection_supports_descendants_and_world_wrap(self) -> None:
        path = renderer_geometry_from_source(_source_path())
        self.assertTrue(geometry_intersects_tile(path, TileKey(2, 0, 1)))
        self.assertTrue(geometry_intersects_tile(path, TileKey(2, 1, 1)))
        self.assertFalse(geometry_intersects_tile(path, TileKey(2, 2, 1)))

        crossing = renderer_geometry_from_source(
            _source_path(
                tile=TileKey(1, 1, 0),
                coordinates=(80, 50, 120, 50),
            )
        )
        self.assertFalse(geometry_intersects_tile(crossing, TileKey(2, 0, 1)))
        self.assertTrue(
            geometry_intersects_tile(crossing, TileKey(2, 0, 1), world_wrap=1)
        )

    def test_geometry_decoders_require_exact_eof(self) -> None:
        source = _source_path()
        renderer = renderer_geometry_from_source(source)
        self.assertEqual(decode_source_geometry(encode_source_geometry(source)), source)
        self.assertEqual(decode_renderer_geometry(encode_renderer_geometry(renderer)), renderer)
        with self.assertRaisesRegex(CanonicalEncodingError, "trailing"):
            decode_source_geometry(encode_source_geometry(source) + b"x")
        with self.assertRaisesRegex(CanonicalEncodingError, "trailing"):
            decode_renderer_geometry(encode_renderer_geometry(renderer) + b"x")

    def test_checked_rational_math_rejects_denominator_and_numerator_overflow(self) -> None:
        with self.assertRaisesRegex(SemanticModelError, "denominator"):
            _source_point(
                tile=TileKey(29, 0, 0),
                extent=1 << 34,
                x=0,
                y=0,
            )

        source = _source_path(
            tile=TileKey(29, (1 << 29) - 1, 0),
            extent=1 << 33,
            coordinates=((1 << 63) - 1, 0, (1 << 63) - 2, 1),
        )
        with self.assertRaisesRegex(SemanticModelError, "numerator"):
            renderer_geometry_from_source(source)


class IdentityBoundaryTests(unittest.TestCase):
    def test_identity_evidence_binds_domain_full_hot_and_complete_preimage(self) -> None:
        evidence = IdentityEvidence.from_preimage(
            b"exp8-feature-v1\0",
            b"complete canonical preimage",
        )
        encoded = encode_identity_evidence(evidence)
        self.assertEqual(decode_identity_evidence(encoded), evidence)
        with self.assertRaisesRegex(CanonicalEncodingError, "trailing"):
            decode_identity_evidence(encoded + b"x")
        with self.assertRaisesRegex(SemanticModelError, "hot ID"):
            replace(evidence, hot_id=evidence.hot_id + 1)
        second = IdentityEvidence.from_preimage(b"FAE8VAR1\0", b"variant payload")
        audit = source_audit_sha256([evidence, second])
        self.assertEqual(audit, source_audit_sha256([second, evidence]))
        self.assertNotEqual(audit, source_audit_sha256([evidence]))
        self.assertRegex(audit, r"^[0-9a-f]{64}$")

    def test_point_label_dedupe_uses_nfc_text_class_and_exact_wrapped_anchor(self) -> None:
        west = renderer_geometry_from_source(
            _source_point(tile=TileKey(1, 0, 0), extent=100, x=0, y=0)
        )
        east = renderer_geometry_from_source(
            _source_point(tile=TileKey(1, 1, 0), extent=100, x=100, y=0)
        )
        west_id = point_label_dedupe_fingerprint(
            LayerGroup.PLACES,
            FeatureKind.LABEL,
            "Caf\u00e9",
            17,
            west,
        )
        east_id = point_label_dedupe_fingerprint(
            LayerGroup.PLACES,
            FeatureKind.LABEL,
            "Caf\u00e9",
            17,
            east,
        )

        self.assertEqual(west_id, east_id)
        self.assertNotEqual(
            west_id,
            point_label_dedupe_fingerprint(
                LayerGroup.PLACES, FeatureKind.LABEL, "Cafe", 17, west
            ),
        )
        self.assertNotEqual(
            west_id,
            point_label_dedupe_fingerprint(
                LayerGroup.PLACES, FeatureKind.LABEL, "Caf\u00e9", 18, west
            ),
        )
        with self.assertRaisesRegex(CanonicalEncodingError, "NFC"):
            point_label_dedupe_fingerprint(
                LayerGroup.PLACES, FeatureKind.LABEL, "Cafe\u0301", 17, west
            )
        self.assertEqual(non_point_dedupe_id(123, FeatureKind.LINE), 123)
        self.assertEqual(non_point_dedupe_id(456, FeatureKind.POLYGON_OUTLINE), 456)
        with self.assertRaisesRegex(SemanticModelError, "point labels"):
            non_point_dedupe_id(123, FeatureKind.LABEL)

    def test_feature_identity_binds_generation_tile_layer_properties_geometry_and_duplicate(self) -> None:
        base = {
            "generation_sha256": _sha(0x01),
            "tile_key": TileKey(1, 0, 0),
            "source_layer": "Water line",
            "typed_properties": {"name": "Chester River", "rank": 3},
            "source_geometry": _source_path(),
            "duplicate_ordinal": 0,
        }
        original = feature_fingerprint(**base)
        mutations = (
            {"generation_sha256": _sha(0x02)},
            {"tile_key": TileKey(1, 1, 0), "source_geometry": _source_path(tile=TileKey(1, 1, 0))},
            {"source_layer": "Water area"},
            {"typed_properties": {"name": "Another River", "rank": 3}},
            {"source_geometry": _source_path(coordinates=(10, 51, 90, 51))},
            {"duplicate_ordinal": 1},
        )
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                changed = dict(base)
                changed.update(mutation)
                self.assertNotEqual(feature_fingerprint(**changed), original)

    def test_source_occurrence_is_detached_evidence_not_the_hot_dedupe_unit(self) -> None:
        source_geometry = _source_path()
        occurrence = SourceOccurrence(
            tile_key=source_geometry.tile_key,
            source_feature_ordinal=12,
            feature_id=0x2222222222222222,
            dedupe_id=0x3333333333333333,
            source_layer="Water line",
            source_geometry=source_geometry,
            source_audit_sha256="ab" * 32,
        )
        variant = _variant()
        posting = TilePosting(
            requested_tile=TileKey(2, 0, 1),
            feature_id=occurrence.feature_id,
            canonical_variant_id=variant.canonical_variant_id,
            owner_tile=TileKey(1, 0, 0),
            world_wrap=0,
        )
        record = reconstruct_renderer_records(
            [posting],
            {variant.canonical_variant_id: variant},
            public_land_token_ids=(),
        )[0]
        hot_bytes = renderer_record_bytes(record)

        changed_evidence = replace(
            occurrence,
            source_feature_ordinal=99,
            source_layer="Audited source layer changed",
            source_audit_sha256="cd" * 32,
        )
        self.assertNotEqual(source_occurrence_fingerprint(occurrence), source_occurrence_fingerprint(changed_evidence))
        self.assertEqual(renderer_record_bytes(record), hot_bytes)
        self.assertNotIn(occurrence.source_layer.encode("utf-8"), hot_bytes)
        self.assertNotIn(bytes.fromhex(occurrence.source_audit_sha256), hot_bytes)

    def test_source_occurrence_variant_and_posting_encodings_are_strict_and_exact_eof(self) -> None:
        source_geometry = _source_path()
        occurrence = SourceOccurrence(
            tile_key=source_geometry.tile_key,
            source_feature_ordinal=0,
            feature_id=11,
            dedupe_id=12,
            source_layer="Water line",
            source_geometry=source_geometry,
            source_audit_sha256="01" * 32,
        )
        variant = _variant()
        posting = TilePosting(TileKey(2, 0, 1), 11, variant.canonical_variant_id, TileKey(1, 0, 0), 0)
        encoded = (
            (encode_source_occurrence, decode_source_occurrence, occurrence),
            (encode_canonical_variant, decode_canonical_variant, variant),
            (encode_tile_posting, decode_tile_posting, posting),
        )
        for encoder, decoder, value in encoded:
            with self.subTest(value=type(value).__name__):
                data = encoder(value)
                self.assertTrue(data.startswith(CANONICAL_MAGIC))
                self.assertEqual(decoder(data), value)
                with self.assertRaisesRegex(CanonicalEncodingError, "trailing"):
                    decoder(data + b"x")

    def test_variant_is_self_addressed_by_fae8var1_and_every_meaning_field(self) -> None:
        original = _variant()
        original_fingerprint = variant_fingerprint(original)
        self.assertEqual(original.canonical_variant_id, original_fingerprint.hot_id)
        self.assertEqual(
            original_fingerprint.full_sha256,
            hashlib.sha256(b"FAE8VAR1\0" + canonical_variant_bytes(original)).digest(),
        )

        simple_mutations = {
            "dedupe_id": original.dedupe_id + 1,
            "source_layer_id": original.source_layer_id + 1,
            "source_scale_band_id": original.source_scale_band_id + 1,
            "layer_group": LayerGroup.REGIONS,
            "semantic_subtype": original.semantic_subtype + 1,
            "source_style_layer_ids": tuple(reversed(original.source_style_layer_ids)),
            "render_style_token_ids": tuple(reversed(original.render_style_token_ids)),
            "min_zoom_centi": original.min_zoom_centi + 1,
            "max_zoom_centi": original.max_zoom_centi + 1,
            "fade_in_centi": original.fade_in_centi + 1,
            "fade_out_centi": original.fade_out_centi + 1,
            "draw_order": original.draw_order + 1,
            "priority": original.priority + 1,
            "land_evidence": LandEvidence.AMBIGUOUS,
            "protected_status": ProtectedStatus.AMBIGUOUS,
            "flags": original.flags + 1,
        }
        for field, value in simple_mutations.items():
            with self.subTest(field=field):
                changed = replace_canonical_variant(original, **{field: value})
                self.assertNotEqual(variant_fingerprint(changed), original_fingerprint)

        changed_text = "Chester Creek"
        changed_placement = replace_normalized_placement(
            original.placement,
            text=changed_text,
        )
        changed = replace_canonical_variant(
            original,
            text=changed_text,
            placement=changed_placement,
        )
        self.assertNotEqual(variant_fingerprint(changed), original_fingerprint)

        changed_geometry = renderer_geometry_from_source(
            _source_path(coordinates=(10, 51, 90, 51))
        )
        geometry_identity = renderer_geometry_fingerprint(changed_geometry)
        changed_placement = replace_normalized_placement(
            original.placement,
            text=original.text,
            placement_geometry_sha256=geometry_identity.full_sha256,
            placement_geometry_id=geometry_identity.hot_id,
        )
        changed = replace_canonical_variant(
            original,
            geometry=changed_geometry,
            geometry_id=geometry_identity.hot_id,
            placement=changed_placement,
        )
        self.assertNotEqual(variant_fingerprint(changed), original_fingerprint)

    def test_non_nfc_text_and_fractional_floats_are_rejected_without_rewriting(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())
        with self.assertRaisesRegex(CanonicalEncodingError, "NFC"):
            _placement(geometry, text="Cafe\u0301")
        placement = _placement(geometry, text="Caf\u00e9")
        with self.assertRaisesRegex(SemanticModelError, "integer"):
            make_canonical_variant(
                **{
                    **{
                        field: getattr(_variant(text="Caf\u00e9", placement=placement), field)
                        for field in CanonicalVariant.__dataclass_fields__
                        if field != "canonical_variant_id"
                    },
                    "min_zoom_centi": 125.5,
                }
            )

    def test_hot_64_bit_collision_between_unequal_full_digests_is_fatal(self) -> None:
        def colliding_digest(preimage: bytes) -> bytes:
            return b"\x7f" * 8 + hashlib.sha256(preimage).digest()[8:]

        registry = HotIdRegistry(digest_function=colliding_digest)
        registry.register(b"domain\0", b"first")
        with self.assertRaises(IdentityCollisionError):
            registry.register(b"domain\0", b"second")
        registry.register(b"domain\0", b"first")

        candidate_registry = HotIdRegistry(digest_function=colliding_digest)
        geometry = renderer_geometry_from_source(_source_path())
        _placement(geometry, text="First", identity_registry=candidate_registry)
        with self.assertRaises(IdentityCollisionError):
            _placement(geometry, text="Second", identity_registry=candidate_registry)

        variant_registry = HotIdRegistry(digest_function=colliding_digest)
        _variant(identity_registry=variant_registry)
        with self.assertRaises(IdentityCollisionError):
            _variant(
                render_style_token_ids=(999,),
                identity_registry=variant_registry,
            )

    def test_only_byte_identical_variants_are_reused_and_postings_preserve_occurrences(self) -> None:
        variant = _variant()
        postings = (
            TilePosting(TileKey(2, 0, 1), 101, variant.canonical_variant_id, TileKey(1, 0, 0), 0),
            TilePosting(TileKey(2, 1, 1), 202, variant.canonical_variant_id, TileKey(1, 0, 0), 0),
        )
        records = reconstruct_renderer_records(
            postings,
            {variant.canonical_variant_id: variant},
            public_land_token_ids=(),
        )

        self.assertEqual(len(records), 2)
        self.assertEqual({record.posting.feature_id for record in records}, {101, 202})
        self.assertEqual(
            {canonical_variant_bytes(record.variant) for record in records},
            {canonical_variant_bytes(variant)},
        )
        with self.assertRaisesRegex(SemanticModelError, "missing canonical variant"):
            reconstruct_renderer_records(postings, {}, public_land_token_ids=())

        unequal_same_dedupe = _line_variant(
            dedupe_id=variant.dedupe_id,
            render_style_token_ids=(777,),
        )
        self.assertNotEqual(
            canonical_variant_bytes(variant),
            canonical_variant_bytes(unequal_same_dedupe),
        )
        self.assertNotEqual(
            variant.canonical_variant_id,
            unequal_same_dedupe.canonical_variant_id,
        )

    def test_public_land_claims_are_checked_at_final_group_and_token_boundary(self) -> None:
        public_token = 9001
        neutral = _variant(
            layer_group=LayerGroup.CONTEXT,
            render_style_token_ids=(7001,),
            land_evidence=LandEvidence.AMBIGUOUS,
            protected_status=ProtectedStatus.AMBIGUOUS,
        )
        validate_public_land_presentation(neutral, {public_token})

        explicit = _variant(
            layer_group=LayerGroup.PUBLIC_LANDS,
            render_style_token_ids=(public_token,),
            land_evidence=LandEvidence.SOURCE_EXPLICIT,
            protected_status=ProtectedStatus.SOURCE_EXPLICIT,
        )
        validate_public_land_presentation(explicit, {public_token})

        with self.assertRaisesRegex(SemanticModelError, "SOURCE_EXPLICIT"):
            replace_canonical_variant(neutral, layer_group=LayerGroup.PUBLIC_LANDS)
        dishonest_token = replace_canonical_variant(
            neutral, render_style_token_ids=(public_token,)
        )
        with self.assertRaisesRegex(SemanticModelError, "SOURCE_EXPLICIT"):
            validate_public_land_presentation(dishonest_token, {public_token})
        posting = TilePosting(
            TileKey(2, 0, 1),
            1,
            dishonest_token.canonical_variant_id,
            TileKey(1, 0, 0),
            0,
        )
        with self.assertRaisesRegex(SemanticModelError, "SOURCE_EXPLICIT"):
            reconstruct_renderer_records(
                [posting],
                {dishonest_token.canonical_variant_id: dishonest_token},
                public_land_token_ids={public_token},
            )

    def test_full_digests_and_hot_ids_must_agree(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())
        placement = _placement(geometry)
        with self.assertRaisesRegex(SemanticModelError, "source feature.*hot ID"):
            replace_normalized_placement(
                placement,
                text="Chester River",
                placement_source_feature_id=placement.placement_source_feature_id + 1,
            )
        with self.assertRaisesRegex(SemanticModelError, "geometry.*hot ID"):
            replace_normalized_placement(
                placement,
                text="Chester River",
                placement_geometry_id=placement.placement_geometry_id + 1,
            )

    def test_unlabeled_lines_have_one_enforced_non_applicable_placement(self) -> None:
        line = _line_variant()
        self.assertEqual(line.placement, empty_normalized_placement())
        sentinel_mutations = (
            {"placement_source_kind": PlacementSourceKind.DIRECT_SOURCE_PATH},
            {"semantic_priority": 1},
            {"prominence_tier": ProminenceTier.FINE},
            {"provider_rank": 0},
            {"complete_geometry_measure_bucket": 1},
            {"prominence_rule_id": 1},
            {"prominence_decision_sha256": _sha(0x77)},
        )
        for changes in sentinel_mutations:
            with self.subTest(changes=changes):
                with self.assertRaisesRegex(SemanticModelError, "non-applicable placement"):
                    replace(line.placement, **changes)

        source_polygon = SourceGeometry(
            GeometryKind.POLYGON,
            TileKey(0, 0, 0),
            0,
            100,
            (0,),
            (0, 0, 10, 0, 10, 10, 0, 0),
            (0, 0, 10, 10),
        )
        polygon_geometry = renderer_geometry_from_source(source_polygon)
        polygon = make_canonical_variant(
            dedupe_id=12,
            geometry_id=renderer_geometry_fingerprint(polygon_geometry).hot_id,
            source_layer_id=1,
            source_scale_band_id=1,
            layer_group=LayerGroup.CONTEXT,
            feature_kind=FeatureKind.POLYGON_OUTLINE,
            semantic_subtype=1,
            source_style_layer_ids=(1,),
            render_style_token_ids=(2,),
            text=None,
            geometry=polygon_geometry,
            min_zoom_centi=0,
            max_zoom_centi=100,
            fade_in_centi=0,
            fade_out_centi=0,
            draw_order=1,
            priority=1,
            placement=empty_normalized_placement(),
            land_evidence=LandEvidence.NOT_APPLICABLE,
            protected_status=ProtectedStatus.NOT_APPLICABLE,
            flags=0,
        )
        self.assertEqual(polygon.feature_kind, FeatureKind.POLYGON_OUTLINE)


class LabelContinuityTests(unittest.TestCase):
    def test_projection_mode_is_replaced_by_placement_source_kind(self) -> None:
        self.assertFalse(hasattr(semantic_model, "ProjectionMode"))
        field_names = tuple(NormalizedPlacement.__dataclass_fields__)
        self.assertIn("placement_source_kind", field_names)
        self.assertNotIn("projection_mode", field_names)
        parameters = inspect.signature(make_normalized_placement).parameters
        self.assertIn("placement_source_kind", parameters)
        self.assertNotIn("projection_mode", parameters)

    def test_applicable_placement_rejects_none_source_kind(self) -> None:
        placement = _placement(renderer_geometry_from_source(_source_path()))
        self.assertIs(
            empty_normalized_placement().placement_source_kind,
            PlacementSourceKind.NONE,
        )
        with self.assertRaisesRegex(
            SemanticModelError,
            "applicable placement source kind cannot be NONE",
        ):
            replace_normalized_placement(
                placement,
                text="Chester River",
                placement_source_kind=PlacementSourceKind.NONE,
            )

    def test_point_labels_require_point_placement_source_kinds(self) -> None:
        point_geometry = renderer_geometry_from_source(_source_point())
        for source_kind in (
            PlacementSourceKind.DIRECT_SOURCE_POINT,
            PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
        ):
            with self.subTest(source_kind=source_kind):
                placement = _placement(
                    point_geometry,
                    placement_source_kind=source_kind,
                )
                variant = _variant(geometry=point_geometry, placement=placement)
                self.assertEqual(variant.placement, placement)
                self.assertIs(
                    decode_canonical_variant(
                        encode_canonical_variant(variant)
                    ).placement.placement_source_kind,
                    source_kind,
                )

        incompatible = _placement(
            point_geometry,
            placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_PATH,
        )
        with self.assertRaisesRegex(
            SemanticModelError,
            "point LABEL.*point placement source kind",
        ):
            _variant(geometry=point_geometry, placement=incompatible)

    def test_path_labels_require_path_placement_source_kinds(self) -> None:
        path_geometry = renderer_geometry_from_source(_source_path())
        for source_kind in (
            PlacementSourceKind.DIRECT_SOURCE_PATH,
            PlacementSourceKind.EXACT_PARENT_PATH,
        ):
            with self.subTest(source_kind=source_kind):
                placement = _placement(
                    path_geometry,
                    placement_source_kind=source_kind,
                )
                variant = _variant(geometry=path_geometry, placement=placement)
                self.assertEqual(variant.placement, placement)
                self.assertIs(
                    decode_canonical_variant(
                        encode_canonical_variant(variant)
                    ).placement.placement_source_kind,
                    source_kind,
                )

        incompatible = _placement(
            path_geometry,
            placement_source_kind=PlacementSourceKind.DIRECT_SOURCE_POINT,
        )
        with self.assertRaisesRegex(
            SemanticModelError,
            "path LABEL.*path placement source kind",
        ):
            _variant(geometry=path_geometry, placement=incompatible)

    def test_normalized_placement_exposes_the_explicit_label_order_tuple(self) -> None:
        field_names = tuple(NormalizedPlacement.__dataclass_fields__)
        for required in (
            "semantic_priority",
            "prominence_tier",
            "provider_rank",
            "complete_geometry_measure_bucket",
            "prominence_rule_id",
            "prominence_decision_sha256",
        ):
            self.assertIn(required, field_names)
        self.assertNotIn("priority", field_names)

        parameters = inspect.signature(make_normalized_placement).parameters
        for required in (
            "semantic_priority",
            "prominence_tier",
            "provider_rank",
            "complete_geometry_measure_bucket",
            "prominence_rule_id",
            "prominence_decision_sha256",
        ):
            self.assertIn(required, parameters)
        self.assertNotIn("priority", parameters)
        self.assertIs(
            parameters["prominence_decision_sha256"].default,
            inspect.Parameter.empty,
        )

    def test_label_order_tuple_round_trips_through_the_canonical_variant(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())
        placement = _placement(geometry)
        variant = _variant(geometry=geometry, placement=placement)

        decoded = decode_canonical_variant(encode_canonical_variant(variant))

        self.assertEqual(decoded.placement, placement)
        self.assertEqual(decoded.placement.semantic_priority, 1120)
        self.assertIs(
            decoded.placement.prominence_tier,
            ProminenceTier.REGIONAL_MAJOR,
        )
        self.assertEqual(decoded.placement.provider_rank, 7)
        self.assertEqual(decoded.placement.complete_geometry_measure_bucket, 42)
        self.assertEqual(decoded.placement.prominence_rule_id, 0x123456789ABCDEF0)
        self.assertEqual(decoded.placement.prominence_decision_sha256, _sha(0x77))

        unranked = replace_normalized_placement(
            placement,
            text="Chester River",
            provider_rank=None,
        )
        unranked_variant = _variant(geometry=geometry, placement=unranked)
        self.assertIsNone(
            decode_canonical_variant(
                encode_canonical_variant(unranked_variant)
            ).placement.provider_rank
        )

    def test_pre_order_tuple_n8t1_variant_bytes_are_not_silently_accepted(self) -> None:
        old_variant = bytes.fromhex(
            "4e3854310796cd8b0456ae8d75dd0100004e385431040807060504030201"
            "f0e94b8bc08378ea11000000000000000300000000000000020109000000"
            "020000006500000000000000670000000000000002000000910100000000"
            "00009201000000000000010d000000436865737465722052697665725a00"
            "00004e385431020214000000000000000100000000000000040000000100"
            "000000000000050000000000000009000000000000000500000000000000"
            "010000000000000005000000000000000900000000000000050000000000"
            "00006400000058020000190000001e0000001400000032000000f8000000"
            "4e385431202789bbf903971a6a6a1a9703f9bb89271a16fdfca63270f00b"
            "8dd1d231c3cab13bd137120fe1139a11111111111111111111111111111111"
            "11111111111111111111111111111111ea7883c08b4be9f0acdd9e135476"
            "45338f6c902aa02bc69e9524a4ba30efd356012900000000000000111111"
            "1111111111f0e94b8bc08378ea0000000000000004016400000000000000"
            "000000000000000000000000000000006400000000000000640000000000"
            "0000026400000058020000e80300001e0000000700000000000000320000"
            "00010104333333333333333333333333333333333333333333333333333333"
            "3333333300000005000000"
        )

        with self.assertRaises(CanonicalEncodingError):
            decode_canonical_variant(old_variant)

    def test_pre_decision_digest_n8t1_variant_bytes_are_not_accepted(self) -> None:
        old_variant = bytes.fromhex(
            "4e38543107f9a7cb9123283d18ed0100004e385431040807060504030201"
            "f0e94b8bc08378ea11000000000000000300000000000000020109000000"
            "020000006500000000000000670000000000000002000000910100000000"
            "00009201000000000000010d000000436865737465722052697665725a00"
            "00004e385431020214000000000000000100000000000000040000000100"
            "000000000000050000000000000009000000000000000500000000000000"
            "010000000000000005000000000000000900000000000000050000000000"
            "00006400000058020000190000001e000000140000003200000008010000"
            "4e38543120850d5936a121e2b2b2e221a136590d85fede955320a59e0bdd"
            "1263387ac2b506e092781e7e903a8d11111111111111111111111111111111"
            "11111111111111111111111111111111ea7883c08b4be9f0acdd9e135476"
            "45338f6c902aa02bc69e9524a4ba30efd356012900000000000000111111"
            "1111111111f0e94b8bc08378ea0000000000000004016400000000000000"
            "000000000000000000000000000000006400000000000000640000000000"
            "0000046400000058020000e80300001e0000000700000000000000600400"
            "000101070000002a00f0debc9a785634120101043333333333333333333333"
            "333333333333333333333333333333333333333333333300000005000000"
        )

        with self.assertRaises(CanonicalEncodingError):
            decode_canonical_variant(old_variant)

    def test_label_order_tuple_rejects_noncanonical_numeric_domains(self) -> None:
        placement = _placement(renderer_geometry_from_source(_source_path()))
        invalid = (
            ("semantic priority", {"semantic_priority": 1 << 31}),
            ("prominence tier", {"prominence_tier": 1}),
            ("provider rank", {"provider_rank": -(1 << 31) - 1}),
            (
                "complete geometry measure bucket",
                {"complete_geometry_measure_bucket": 1 << 16},
            ),
            ("prominence rule ID", {"prominence_rule_id": 1 << 64}),
        )
        for message, changes in invalid:
            with self.subTest(message=message):
                with self.assertRaisesRegex(SemanticModelError, message):
                    replace(placement, **changes)
        with self.assertRaisesRegex(SemanticModelError, "prominence rule ID.*positive"):
            replace(placement, prominence_rule_id=0)

    def test_applicable_prominence_decision_requires_nonzero_sha256(self) -> None:
        placement = _placement(renderer_geometry_from_source(_source_path()))
        self.assertEqual(
            empty_normalized_placement().prominence_decision_sha256,
            b"\x00" * 32,
        )
        with self.assertRaisesRegex(
            SemanticModelError,
            "applicable prominence decision SHA-256 cannot be zero",
        ):
            replace(placement, prominence_decision_sha256=b"\x00" * 32)
        with self.assertRaisesRegex(
            SemanticModelError,
            "prominence decision SHA-256 must be exactly 32 bytes",
        ):
            replace(placement, prominence_decision_sha256=b"short")

    def test_semantic_priority_is_confined_to_its_prominence_tier_stride(self) -> None:
        placement = _placement(renderer_geometry_from_source(_source_path()))
        for tier in ProminenceTier:
            for semantic_priority in (
                tier.value * 1000,
                (tier.value + 1) * 1000 - 1,
            ):
                with self.subTest(tier=tier, semantic_priority=semantic_priority):
                    changed = replace(
                        placement,
                        prominence_tier=tier,
                        semantic_priority=semantic_priority,
                    )
                    self.assertEqual(changed.semantic_priority, semantic_priority)

        malformed = (
            (ProminenceTier.GLOBAL_MAJOR, -1),
            (ProminenceTier.GLOBAL_MAJOR, 1000),
            (ProminenceTier.REGIONAL_MAJOR, 999),
            (ProminenceTier.REGIONAL_MAJOR, 2000),
            (ProminenceTier.LOCAL, 1999),
            (ProminenceTier.LOCAL, 3000),
            (ProminenceTier.FINE, -1),
            (ProminenceTier.FINE, 4000),
        )
        for tier, semantic_priority in malformed:
            with self.subTest(tier=tier, semantic_priority=semantic_priority):
                with self.assertRaisesRegex(
                    SemanticModelError,
                    "semantic priority.*prominence tier stride",
                ):
                    replace(
                        placement,
                        prominence_tier=tier,
                        semantic_priority=semantic_priority,
                    )

    def test_decoder_rejects_fine_tier_with_negative_semantic_priority(self) -> None:
        encoded = encode_canonical_variant(_variant())
        canonical_pair = struct.pack("<iB", 1120, ProminenceTier.REGIONAL_MAJOR.value)
        self.assertEqual(encoded.count(canonical_pair), 1)
        malformed = encoded.replace(
            canonical_pair,
            struct.pack("<iB", -1, ProminenceTier.FINE.value),
            1,
        )

        with self.assertRaisesRegex(
            SemanticModelError,
            "semantic priority.*prominence tier stride",
        ):
            decode_canonical_variant(malformed)

    def test_viewport_candidates_use_the_exact_semantic_order_tuple(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())

        def candidate(
            text: str,
            semantic_priority: int,
            tier: ProminenceTier,
            provider_rank: int | None,
            bucket: int,
            renderer_priority: int = 50,
        ) -> CanonicalVariant:
            placement = _placement(
                geometry,
                text=text,
                semantic_priority=semantic_priority,
                prominence_tier=tier,
                provider_rank=provider_rank,
                complete_geometry_measure_bucket=bucket,
            )
            return _variant(
                text=text,
                geometry=geometry,
                placement=placement,
                priority=renderer_priority,
            )

        ordered_prefix = (
            candidate("semantic", 10, ProminenceTier.GLOBAL_MAJOR, None, 0, 999),
            candidate("ranked", 20, ProminenceTier.GLOBAL_MAJOR, 5, 0),
            candidate("unranked", 20, ProminenceTier.GLOBAL_MAJOR, None, 65535),
            candidate("large bucket", 30, ProminenceTier.GLOBAL_MAJOR, None, 100),
            candidate("small bucket", 30, ProminenceTier.GLOBAL_MAJOR, None, 50),
            candidate("regional", 1000, ProminenceTier.REGIONAL_MAJOR, None, 0, -999),
        )
        tie_candidates = (
            candidate("tie a", 1100, ProminenceTier.REGIONAL_MAJOR, None, 0),
            candidate("tie b", 1100, ProminenceTier.REGIONAL_MAJOR, None, 0),
        )
        expected_ties = tuple(
            sorted(tie_candidates, key=lambda item: item.placement.label_candidate_id)
        )
        expected = ordered_prefix + expected_ties

        def membership(variant: CanonicalVariant) -> PlacementMembership:
            return PlacementMembership(
                requested_tile=TileKey(2, 0, 1),
                label_candidate_id=variant.placement.label_candidate_id,
                source_feature_id=variant.placement.placement_source_feature_id,
                placement_geometry_id=variant.placement.placement_geometry_id,
                owner_tile=TileKey(1, 0, 0),
                metatile_id=1,
                feature_page_id=0,
                local_ordinal=0,
                world_wrap=0,
            )

        scrambled = tuple(reversed(expected))
        variants = {item.placement.label_candidate_id: item for item in scrambled}
        forward = viewport_label_candidates(
            [membership(item) for item in scrambled],
            variants,
        )
        backward = viewport_label_candidates(
            [membership(item) for item in reversed(scrambled)],
            variants,
        )

        self.assertEqual(forward, expected)
        self.assertEqual(backward, expected)

    def test_fae8llb1_binds_every_meaning_field_but_not_transport_membership(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())
        placement = _placement(geometry)
        text = "Chester River"
        original_bytes = canonical_line_label_candidate_bytes(placement, text)
        original = line_label_candidate_fingerprint(placement, text)
        original_variant = _variant(geometry=geometry, placement=placement)
        evidence = IdentityEvidence.from_preimage(b"FAE8LLB1\0", original_bytes)
        variant_evidence = IdentityEvidence.from_preimage(
            b"FAE8VAR1\0",
            canonical_variant_bytes(original_variant),
        )
        self.assertEqual(
            original.full_sha256,
            hashlib.sha256(b"FAE8LLB1\0" + original_bytes).digest(),
        )
        self.assertEqual(placement.label_candidate_id, original.hot_id)
        self.assertEqual(evidence.full_sha256, placement.label_candidate_sha256)
        self.assertEqual(
            variant_evidence.full_sha256,
            variant_fingerprint(original_variant).full_sha256,
        )

        mutations = {
            "text_evidence_kind": TextEvidenceKind.PROVIDER_STABLE_JOIN,
            "text_source_field_id": 42,
            "source_tile": TileKey(1, 1, 0),
            "source_declared_extent": 101,
            "source_edge_domain": (-1, 0, 100, 100),
            "placement_source_kind": PlacementSourceKind.DIRECT_SOURCE_PATH,
            "display_min_zoom_centi": 101,
            "display_max_zoom_centi": 601,
            "spacing_px": 999,
            "max_angle_degrees": 29,
            "collision_group": 8,
            "semantic_priority": 1121,
            "prominence_tier": ProminenceTier.LOCAL,
            "provider_rank": None,
            "complete_geometry_measure_bucket": 43,
            "prominence_rule_id": placement.prominence_rule_id + 1,
            "prominence_decision_sha256": _sha(0x78),
            "avoid_edges": False,
            "keep_upright": False,
            "active_band_limit": 3,
            "style_policy_sha256": _sha(0x66),
            "provider_feature_id": 88,
        }
        for field, value in mutations.items():
            with self.subTest(field=field):
                changed_fields = {field: value}
                if field == "source_tile":
                    changed_fields["source_zoom"] = value.z
                if field == "text_evidence_kind":
                    changed_fields["provider_feature_id"] = 77
                if field == "prominence_tier":
                    changed_fields["semantic_priority"] = 2120
                changed = replace_normalized_placement(
                    placement,
                    text=text,
                    **changed_fields,
                )
                self.assertNotEqual(
                    line_label_candidate_fingerprint(changed, text),
                    original,
                )
                changed_variant = replace_canonical_variant(
                    original_variant,
                    placement=changed,
                )
                self.assertNotEqual(
                    variant_fingerprint(changed_variant),
                    variant_fingerprint(original_variant),
                )
        for source_digest, geometry_digest in (
            (_sha(0x44), placement.placement_geometry_sha256),
            (placement.source_feature_sha256, _sha(0x55)),
        ):
            changed = replace_normalized_placement(
                placement,
                text=text,
                source_feature_sha256=source_digest,
                placement_source_feature_id=int.from_bytes(source_digest[:8], "big"),
                placement_geometry_sha256=geometry_digest,
                placement_geometry_id=int.from_bytes(geometry_digest[:8], "big"),
            )
            self.assertNotEqual(line_label_candidate_fingerprint(changed, text), original)
        changed_text = "Chester Creek"
        changed = replace_normalized_placement(placement, text=changed_text)
        self.assertNotEqual(line_label_candidate_fingerprint(changed, changed_text), original)

        membership = PlacementMembership(
            requested_tile=TileKey(2, 0, 1),
            label_candidate_id=placement.label_candidate_id,
            source_feature_id=placement.placement_source_feature_id,
            placement_geometry_id=placement.placement_geometry_id,
            owner_tile=TileKey(1, 0, 0),
            metatile_id=1,
            feature_page_id=2,
            local_ordinal=3,
            world_wrap=0,
        )
        transport_mutations = {
            "requested_tile": TileKey(2, 1, 1),
            "owner_tile": TileKey(2, 0, 1),
            "metatile_id": 8,
            "feature_page_id": 9,
            "local_ordinal": 10,
            "world_wrap": 1,
        }
        for field, value in transport_mutations.items():
            with self.subTest(transport=field):
                changed_membership = replace(membership, **{field: value})
                self.assertEqual(changed_membership.label_candidate_id, original.hot_id)
                self.assertEqual(line_label_candidate_fingerprint(placement, text), original)

    def test_zero_provider_id_cannot_be_used_as_stable_join_evidence(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())
        placement = _placement(geometry)
        with self.assertRaisesRegex(SemanticModelError, "provider.*zero"):
            replace_normalized_placement(
                placement,
                text="Chester River",
                text_evidence_kind=TextEvidenceKind.PROVIDER_STABLE_JOIN,
                provider_feature_id=0,
            )

    def test_parent_band_derives_only_exact_path_intersecting_descendants(self) -> None:
        variant = _variant()
        band = ParentLabelBand.from_variant(variant)
        requested = [
            TileKey(2, 2, 1),
            TileKey(2, 1, 1),
            TileKey(2, 0, 1),
        ]

        memberships = derive_descendant_memberships(band, requested)

        self.assertEqual(
            [membership.requested_tile for membership in memberships],
            [TileKey(2, 0, 1), TileKey(2, 1, 1)],
        )
        self.assertTrue(
            all(
                membership.label_candidate_id == variant.placement.label_candidate_id
                and membership.placement_geometry_id == variant.geometry_id
                for membership in memberships
            )
        )
        self.assertEqual(variant_fingerprint(variant), variant_fingerprint(band.variant))
        self.assertEqual(variant.geometry, band.variant.geometry)
        self.assertTrue(
            source_edge_eligible(
                variant.placement,
                1,
                1,
                4,
                margin_source_units=5,
            )
        )

    def test_parent_band_respects_display_and_active_band_limits_without_child_expansion(self) -> None:
        geometry = renderer_geometry_from_source(_source_path())
        placement = _placement(geometry, active_band_limit=2)
        variant = _variant(geometry=geometry, placement=placement)
        band = ParentLabelBand.from_variant(variant)

        memberships = derive_descendant_memberships(
            band,
            [TileKey(2, 0, 1), TileKey(4, 1, 4)],
        )

        self.assertEqual(len(memberships), 1)
        self.assertEqual(memberships[0].requested_tile, TileKey(2, 0, 1))

    def test_antimeridian_parent_band_uses_transport_wrap_without_mutating_identity(self) -> None:
        source_tile = TileKey(1, 1, 0)
        geometry = renderer_geometry_from_source(
            _source_path(tile=source_tile, coordinates=(80, 50, 120, 50))
        )
        placement = _placement(geometry, source_tile=source_tile)
        variant = _variant(geometry=geometry, placement=placement)
        before = variant_fingerprint(variant)

        memberships = derive_descendant_memberships(
            ParentLabelBand.from_variant(variant),
            [TileKey(2, 0, 1)],
            world_wraps=(0, 1),
        )

        self.assertEqual(len(memberships), 1)
        self.assertEqual(memberships[0].world_wrap, 1)
        self.assertEqual(variant_fingerprint(variant), before)

    def test_viewport_dedupes_memberships_to_one_whole_utf8_label(self) -> None:
        text = "Chester River \u6771\u4eac"
        geometry = renderer_geometry_from_source(_source_path())
        placement = _placement(geometry, text=text)
        variant = _variant(text=text, geometry=geometry, placement=placement)
        memberships = derive_descendant_memberships(
            ParentLabelBand.from_variant(variant),
            [TileKey(2, 0, 1), TileKey(2, 1, 1)],
        )

        candidates = viewport_label_candidates(
            memberships,
            {variant.placement.label_candidate_id: variant},
        )

        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0].text, text)
        self.assertIn(text.encode("utf-8"), canonical_variant_bytes(candidates[0]))
        self.assertTrue(whole_label_accepted(120_000, 100_000, 20, 30, True))
        self.assertFalse(whole_label_accepted(99_999, 100_000, 20, 30, True))
        self.assertFalse(whole_label_accepted(120_000, 100_000, 31, 30, True))
        self.assertFalse(whole_label_accepted(120_000, 100_000, 20, 30, False))
        self.assertEqual(
            label_repeat_phase(variant.placement.label_candidate_id, 1000),
            variant.placement.label_candidate_id % 1000,
        )

    def test_equal_text_on_disconnected_paths_never_transfers_or_dedupes(self) -> None:
        first = _variant()
        second_geometry = renderer_geometry_from_source(
            _source_path(coordinates=(10, 80, 90, 80))
        )
        second = _variant(
            geometry=second_geometry,
            placement=_placement(second_geometry),
        )
        self.assertEqual(first.text, second.text)
        self.assertNotEqual(
            first.placement.label_candidate_id,
            second.placement.label_candidate_id,
        )

        first_memberships = derive_descendant_memberships(
            ParentLabelBand.from_variant(first), [TileKey(2, 0, 1)]
        )
        second_memberships = derive_descendant_memberships(
            ParentLabelBand.from_variant(second), [TileKey(2, 0, 1)]
        )
        candidates = viewport_label_candidates(
            first_memberships + second_memberships,
            {
                first.placement.label_candidate_id: first,
                second.placement.label_candidate_id: second,
            },
        )
        self.assertEqual(len(candidates), 2)

    def test_equal_rational_path_at_a_different_source_lod_is_a_distinct_candidate(self) -> None:
        low_source = _source_path(
            tile=TileKey(1, 0, 0),
            coordinates=(20, 20, 80, 20),
        )
        high_source = _source_path(
            tile=TileKey(2, 0, 0),
            coordinates=(80, 80, 320, 80),
            extent=200,
        )
        low_geometry = renderer_geometry_from_source(low_source)
        high_geometry = renderer_geometry_from_source(high_source)
        self.assertEqual(low_geometry, high_geometry)

        low = _placement(low_geometry, source_tile=low_source.tile_key)
        high = _placement(high_geometry, source_tile=high_source.tile_key)

        self.assertNotEqual(low.label_candidate_id, high.label_candidate_id)


class RendererContractTests(unittest.TestCase):
    def test_language_neutral_encoding_and_hash_golden_vectors(self) -> None:
        source = _source_path()
        renderer = renderer_geometry_from_source(source)
        occurrence = SourceOccurrence(
            source.tile_key,
            0,
            11,
            12,
            "Water line",
            source,
            "01" * 32,
        )
        variant = _variant()
        posting = TilePosting(
            TileKey(2, 0, 1),
            11,
            variant.canonical_variant_id,
            TileKey(1, 0, 0),
            0,
        )
        record = reconstruct_renderer_records(
            [posting],
            {variant.canonical_variant_id: variant},
            public_land_token_ids=(),
        )[0]
        evidence = IdentityEvidence.from_preimage(b"exp8-feature-v1\0", b"golden")
        encoded = {
            "source_geometry": encode_source_geometry(source),
            "renderer_geometry": encode_renderer_geometry(renderer),
            "source_occurrence": encode_source_occurrence(occurrence),
            "label_candidate": canonical_line_label_candidate_bytes(
                variant.placement, variant.text
            ),
            "variant_payload": canonical_variant_bytes(variant),
            "variant_wrapper": encode_canonical_variant(variant),
            "tile_posting": encode_tile_posting(posting),
            "renderer_record": renderer_record_bytes(record),
            "identity_evidence": encode_identity_evidence(evidence),
        }
        expected_sha256 = {
            "source_geometry": "bddfdf310161d6bdc3f89e98a60aa407204017533102dca3b1c748939808761e",
            "renderer_geometry": "de070e99420ac3b9b45ac6784b238e61932dbaa8872303ebc033799e2c4e7ba1",
            "source_occurrence": "9bb7c196db5bb2b7ce73823f8591b434585e8ebc04546abc47a096474b0ca722",
            "label_candidate": "dbdbdf0035ad8600d4af7423e2e95170f7e264fa1d0b5917a85629c5e12e5784",
            "variant_payload": "7f593b1f358509530140a2cd483505e6a85a887e247843a4509de2fee5ad7bef",
            "variant_wrapper": "3d3b0aa6be79420abbdf48eed2f5adc5f8ebf40a354123e47d4ec4f62fc319f3",
            "tile_posting": "de6aef78fffc7c163275e07cfb3ed38027076b4259511d9e5998870809e4fe90",
            "renderer_record": "7ade0bc7d06a97313a939eaf8e5487a41b5fe6fcf366e7eb0174e9464f57e170",
            "identity_evidence": "384ff71faa80ffee18358f77b3933a35902d4f529198cd761b7b1b36c639c06b",
        }

        self.assertEqual(
            encode_source_geometry(source).hex(),
            "4e385431010200000000000000040164000000000000000100000000000000040000000a0000000000000032000000000000005a0000000000000032000000000000000a0000000000000032000000000000005a000000000000003200000000000000",
        )
        self.assertEqual(
            encode_tile_posting(posting).hex(),
            "4e3854310501000000000000080b00000000000000badf55e08d982bcf000000000000000400000000",
        )
        self.assertEqual(
            {name: hashlib.sha256(value).hexdigest() for name, value in encoded.items()},
            expected_sha256,
        )
        self.assertEqual(
            renderer_contract_hash([record]),
            "f21dd173f781fbae1847f582c5f0376d315350f318af0e5a3d43ec8511794e13",
        )

    def test_owner_and_world_wrap_break_renderer_order_ties_deterministically(self) -> None:
        variant = _variant()
        common = {
            "requested_tile": TileKey(2, 0, 1),
            "feature_id": 7,
            "canonical_variant_id": variant.canonical_variant_id,
        }
        first = TilePosting(**common, owner_tile=TileKey(1, 0, 0), world_wrap=0)
        second = TilePosting(**common, owner_tile=TileKey(1, 1, 0), world_wrap=1)
        variants = {variant.canonical_variant_id: variant}

        forward = reconstruct_renderer_records(
            [first, second], variants, public_land_token_ids=()
        )
        backward = reconstruct_renderer_records(
            [second, first], variants, public_land_token_ids=()
        )

        self.assertEqual(forward, backward)
        self.assertNotEqual(renderer_order_key(forward[0]), renderer_order_key(forward[1]))
        self.assertEqual(renderer_contract_hash(forward), renderer_contract_hash(backward))
    def test_one_renderer_order_and_hash_is_input_package_and_codec_independent(self) -> None:
        first = _variant(draw_order=20, priority=9)
        second = _variant(draw_order=10, priority=99)
        postings = (
            TilePosting(TileKey(2, 0, 1), 20, first.canonical_variant_id, TileKey(1, 0, 0), 0),
            TilePosting(TileKey(2, 0, 1), 10, second.canonical_variant_id, TileKey(1, 0, 0), 0),
        )
        variants = {
            first.canonical_variant_id: first,
            second.canonical_variant_id: second,
        }
        format_a_deflate = reconstruct_renderer_records(
            postings, variants, public_land_token_ids=()
        )
        format_b_zstd = reconstruct_renderer_records(
            reversed(postings), variants, public_land_token_ids=()
        )

        self.assertEqual(format_a_deflate, format_b_zstd)
        self.assertEqual(
            [record.variant.draw_order for record in format_a_deflate],
            [10, 20],
        )
        for record in format_a_deflate:
            self.assertEqual(
                renderer_order_key(record),
                (
                    record.variant.draw_order,
                    record.variant.priority,
                    record.variant.layer_group.value,
                    record.variant.feature_kind.value,
                    record.variant.canonical_variant_id,
                    record.posting.feature_id,
                    renderer_record_bytes(record),
                ),
            )
        self.assertEqual(
            renderer_contract_hash(format_a_deflate),
            renderer_contract_hash(format_b_zstd),
        )

    def test_renderer_stream_preserves_duplicate_multiset_and_is_packed_tile_sorted(self) -> None:
        variant = _variant()
        postings = (
            TilePosting(TileKey(2, 1, 1), 7, variant.canonical_variant_id, TileKey(1, 0, 0), 0),
            TilePosting(TileKey(2, 0, 1), 7, variant.canonical_variant_id, TileKey(1, 0, 0), 0),
            TilePosting(TileKey(2, 0, 1), 7, variant.canonical_variant_id, TileKey(1, 0, 0), 0),
        )
        records = reconstruct_renderer_records(
            postings,
            {variant.canonical_variant_id: variant},
            public_land_token_ids=(),
        )
        self.assertEqual([record.posting.requested_tile for record in records], [TileKey(2, 0, 1), TileKey(2, 0, 1), TileKey(2, 1, 1)])
        digest = renderer_contract_hash(records)
        self.assertEqual(len(digest), 64)
        self.assertEqual(digest, renderer_contract_hash(reversed(records)))

    def test_exact_renderer_heap_weight_charges_every_record_and_enforces_ceiling(self) -> None:
        variant = _variant(text="Whole UTF8 \u6771\u4eac")
        posting = TilePosting(TileKey(2, 0, 1), 1, variant.canonical_variant_id, TileKey(1, 0, 0), 0)
        record = reconstruct_renderer_records(
            [posting],
            {variant.canonical_variant_id: variant},
            public_land_token_ids=(),
        )[0]
        weight = renderer_record_heap_weight(record)

        self.assertGreater(weight, len(variant.text.encode("utf-8")))
        self.assertEqual(
            weight,
            312
            + 2 * 16
            + 1 * 4
            + 4 * 8
            + len(variant.text.encode("utf-8")),
        )
        self.assertEqual(renderer_records_heap_weight([record, record]), weight * 2)
        self.assertEqual(MAX_RENDERER_HEAP_BYTES, 33_554_432)
        with self.assertRaises(HeapLimitError):
            renderer_records_heap_weight([record], ceiling=weight - 1)


if __name__ == "__main__":
    unittest.main()
