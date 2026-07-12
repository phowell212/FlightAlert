from __future__ import annotations

import hashlib
import inspect
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
from copy import copy, deepcopy
from dataclasses import replace
from decimal import Decimal
from pathlib import Path
from types import MappingProxyType
from typing import Mapping
from unittest.mock import patch

from tools.experiment8.reference_presentation_policy import (
    FilterId,
    LINE_LABEL_REPEAT_SPACING_PX,
    MAX_LINE_LABEL_BEND_CENTI_DEGREES,
    PRESENTATION_POLICY_SHA256,
    SemanticSubtype,
    filter_spec,
)
from tools.experiment8.semantic_model import (
    FeatureKind,
    LandEvidence,
    LayerGroup,
    PlacementSourceKind,
    ProtectedStatus,
)


PINNED_STYLE = Path(
    r"D:\FlightAlert-test-artifacts\experiment 6\phase1\world-basemap-v2-source-lock"
    r"\World_Basemap_v2-root-style.json"
)
PINNED_SHA256 = "92cec535724bebd560ce18ba47f5ddbc803e9bef61d8450bd24098f941276c5b"

_IMPORT_ERROR: ImportError | None = None
try:
    from tools.experiment8 import semantic_policy as policy
    from tools.experiment8 import style_contract as styles
except ImportError as error:
    _IMPORT_ERROR = error
    policy = None  # type: ignore[assignment]
    styles = None  # type: ignore[assignment]


def _style_bytes(layers: list[dict[str, object]]) -> bytes:
    document = {
        "version": 8,
        "sources": {"esri": {"type": "vector"}},
        "layers": layers,
    }
    return json.dumps(
        document,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _water_label_layer(**overrides: object) -> dict[str, object]:
    layer: dict[str, object] = {
        "id": "Water line/label/Default",
        "type": "symbol",
        "source": "esri",
        "source-layer": "Water line/label",
        "minzoom": 12,
        "layout": {
            "symbol-avoid-edges": True,
            "symbol-placement": "line",
            "symbol-spacing": 1000,
            "text-field": "{_name_global}",
            "text-font": ["Arial Italic"],
            "text-letter-spacing": "0.07",
            "text-max-angle": 30,
            "text-max-width": 8,
            "text-offset": [0, "-0.5"],
            "text-size": 10,
        },
        "paint": {"text-color": "#497AAB"},
    }
    layer.update(overrides)
    return layer


def _evidence_generation_sha256(files: Mapping[str, bytes]) -> str:
    return styles._style_evidence_generation_sha256(files)


def _read_pinned_style_evidence(
    output: Path, expected_files: Mapping[str, bytes]
) -> dict[str, bytes]:
    return styles.read_style_evidence(
        output,
        expected_generation_sha256=_evidence_generation_sha256(expected_files),
    )


def _install_recomputed_evidence_generation(
    output: Path, files: Mapping[str, bytes]
) -> str:
    generation_sha256 = _evidence_generation_sha256(files)
    pointer = json.loads((output / "current.json").read_bytes())
    old_generation = output / "generations" / pointer["generationSha256"]
    new_generation = output / "generations" / generation_sha256
    if new_generation != old_generation:
        old_generation.rename(new_generation)
    for filename, data in files.items():
        (new_generation / filename).write_bytes(data)
    (output / "current.json").write_bytes(
        styles._style_evidence_current_bytes(generation_sha256)
    )
    return generation_sha256


class StyleContractTestCase(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if _IMPORT_ERROR is None:
            cls.contract = styles.compile_style_contract()

    def setUp(self) -> None:
        if _IMPORT_ERROR is not None:
            self.fail(f"Task 2 style compiler is not implemented: {_IMPORT_ERROR}")


class PinnedStyleIntegrityTests(StyleContractTestCase):
    def test_raw_sha_is_verified_before_utf8_or_json_parse(self) -> None:
        malformed = b"not-json-\xff"
        with self.assertRaisesRegex(styles.StyleContractError, "SHA-256 mismatch"):
            styles.compile_style_bytes(
                malformed,
                expected_sha256="0" * 64,
                require_pinned_inventory=False,
            )

    def test_authoritative_style_identity_and_inventory_are_exact(self) -> None:
        raw = PINNED_STYLE.read_bytes()
        self.assertEqual(len(raw), 783_960)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), PINNED_SHA256)
        self.assertEqual(self.contract.raw_style_sha256, PINNED_SHA256)
        self.assertEqual(self.contract.style_version, 8)
        self.assertEqual(self.contract.layer_count, 916)
        self.assertEqual(
            self.contract.layer_type_counts,
            (("fill", 64), ("line", 74), ("symbol", 778)),
        )
        self.assertEqual(self.contract.source_layer_count, 115)
        self.assertTrue(hasattr(self.contract, "text_bearing_symbol_count"))
        self.assertEqual(self.contract.text_bearing_symbol_count, 767)
        self.assertEqual(
            self.contract.catalog_document["textBearingSymbolLayerCount"], 767
        )

    def test_duplicate_layer_ids_and_wrong_style_version_fail_closed(self) -> None:
        layer = _water_label_layer()
        duplicate = _style_bytes([layer, dict(layer)])
        with self.assertRaisesRegex(styles.StyleContractError, "duplicate style-layer ID"):
            styles.compile_style_bytes(
                duplicate,
                expected_sha256=hashlib.sha256(duplicate).hexdigest(),
                require_pinned_inventory=False,
            )
        wrong = _style_bytes([layer]).replace(b'"version":8', b'"version":7')
        with self.assertRaisesRegex(styles.StyleContractError, "Style v8"):
            styles.compile_style_bytes(
                wrong,
                expected_sha256=hashlib.sha256(wrong).hexdigest(),
                require_pinned_inventory=False,
            )

    def test_non_pinned_json_rejects_duplicate_object_keys_at_any_depth(self) -> None:
        raw = (
            b'{"version":8,"sources":{"esri":{"type":"vector"}},"layers":['
            b'{"id":"Water line/label/Default","id":"shadowed",'
            b'"type":"symbol","source":"esri","source-layer":"Water line/label",'
            b'"layout":{},"paint":{}}]}'
        )
        with self.assertRaisesRegex(styles.StyleContractError, "duplicate JSON key"):
            styles.compile_style_bytes(
                raw,
                expected_sha256=hashlib.sha256(raw).hexdigest(),
                require_pinned_inventory=False,
            )

    def test_non_pinned_json_rejects_nan_and_infinity_constants(self) -> None:
        for constant in (b"NaN", b"Infinity", b"-Infinity"):
            with self.subTest(constant=constant):
                raw = (
                    b'{"version":8,"sources":{"esri":{"type":"vector"}},'
                    b'"layers":[],"unsupported":' + constant + b"}"
                )
                with self.assertRaisesRegex(
                    styles.StyleContractError, "non-finite JSON constant"
                ):
                    styles.compile_style_bytes(
                        raw,
                        expected_sha256=hashlib.sha256(raw).hexdigest(),
                        require_pinned_inventory=False,
                    )


class StyleValueTests(StyleContractTestCase):
    def test_scalar_numeric_color_dash_font_and_text_values_are_typed(self) -> None:
        self.assertEqual(styles.compile_style_value("line-width", "1.25").milli, 1250)
        self.assertEqual(styles.compile_style_value("text-color", "#497AAB").argb, 0xFF497AAB)
        self.assertEqual(
            styles.compile_style_value("line-dasharray", ["7.5", "2.5"]).values_milli,
            (7500, 2500),
        )
        self.assertEqual(
            styles.compile_style_value("text-font", ["Arial Italic"]).values,
            ("Arial Italic",),
        )
        template = styles.compile_style_value("text-field", "{_name_global}")
        self.assertEqual(template.source_field, "_name_global")

    def test_zoom_stops_use_decimal_centizoom_and_fixed_outputs(self) -> None:
        function = styles.compile_style_value(
            "line-width",
            {
                "base": "1.2",
                "stops": [["7.25", 1], ["11.75", "1.33"]],
            },
        )
        self.assertEqual(function.function_type, "exponential")
        self.assertIsNone(function.property_name)
        self.assertEqual(function.base_milli, 1200)
        self.assertEqual(tuple(stop.input_centi for stop in function.stops), (725, 1175))
        self.assertEqual(styles.resolve_style_value(function, zoom_centi=725).milli, 1000)
        self.assertEqual(styles.resolve_style_value(function, zoom_centi=1175).milli, 1330)

    def test_stop_based_color_and_text_properties_are_supported(self) -> None:
        color = styles.compile_style_value(
            "line-color", {"stops": [[0, "#000000"], [10, "#FFFFFF"]]}
        )
        self.assertEqual(styles.resolve_style_value(color, zoom_centi=500).argb, 0xFF808080)
        text = styles.compile_style_value(
            "text-field",
            {"type": "interval", "stops": [[0, "{_name}"], [10, "{_name_global}"]]},
        )
        self.assertEqual(
            styles.resolve_style_value(text, zoom_centi=999).source_field,
            "_name",
        )
        self.assertEqual(
            styles.resolve_style_value(text, zoom_centi=1000).source_field,
            "_name_global",
        )

    def test_categorical_interval_and_exponential_feature_functions_are_exact(self) -> None:
        categorical = styles.compile_style_value(
            "icon-rotate",
            {
                "type": "categorical",
                "property": "DirTravel",
                "default": 0,
                "stops": [["F", 0], ["T", 180]],
            },
        )
        self.assertEqual(
            styles.resolve_style_value(categorical, properties={"DirTravel": "T"}).milli,
            180_000,
        )
        self.assertEqual(
            styles.resolve_style_value(categorical, properties={"DirTravel": "X"}).milli,
            0,
        )

        interval = styles.compile_style_value(
            "text-size",
            {
                "type": "interval",
                "property": "SelectionPriority",
                "stops": [[0, 10], [10, 12]],
            },
        )
        self.assertEqual(
            styles.resolve_style_value(
                interval, properties={"SelectionPriority": 9}
            ).milli,
            10_000,
        )
        self.assertEqual(
            styles.resolve_style_value(
                interval, properties={"SelectionPriority": 10}
            ).milli,
            12_000,
        )

        exponential = styles.compile_style_value(
            "text-size",
            {
                "type": "exponential",
                "base": 1,
                "property": "SelectionPriority",
                "stops": [[0, 10], [10, 20]],
            },
        )
        self.assertEqual(
            styles.resolve_style_value(
                exponential, properties={"SelectionPriority": 5}
            ).milli,
            15_000,
        )

    def test_float_inputs_missing_properties_and_bad_stop_order_fail_closed(self) -> None:
        with self.assertRaisesRegex(styles.StyleContractError, "binary float"):
            styles.compile_style_value("line-width", 1.25)
        function = styles.compile_style_value(
            "text-size",
            {
                "type": "interval",
                "property": "SelectionPriority",
                "stops": [[0, 10], [10, 12]],
            },
        )
        with self.assertRaisesRegex(styles.StyleContractError, "missing required property"):
            styles.resolve_style_value(function, properties={})
        with self.assertRaisesRegex(styles.StyleContractError, "strictly increasing"):
            styles.compile_style_value(
                "line-width", {"stops": [[10, 1], [10, 2]]}
            )


class FilterAndTextTests(StyleContractTestCase):
    def test_supported_filters_match_exact_types_and_nested_all(self) -> None:
        expression = [
            "all",
            ["==", "_symbol", 2],
            ["in", "DirTravel", "F", "T"],
            ["!in", "Viz", 3],
        ]
        compiled = styles.compile_filter(expression)
        self.assertEqual(compiled.operators, ("!in", "==", "all", "in"))
        self.assertTrue(compiled.matches({"_symbol": 2, "DirTravel": "F", "Viz": 0}))
        self.assertFalse(compiled.matches({"_symbol": 2, "DirTravel": "X", "Viz": 0}))
        self.assertFalse(compiled.matches({"_symbol": 2, "DirTravel": "F", "Viz": 3}))
        self.assertFalse(compiled.matches({"_symbol": "2", "DirTravel": "F", "Viz": 0}))

    def test_missing_filter_properties_are_not_coerced_to_zero_or_false(self) -> None:
        self.assertFalse(styles.compile_filter(["==", "_symbol", 0]).matches({}))
        self.assertTrue(styles.compile_filter(["!in", "Viz", 3]).matches({}))
        with self.assertRaisesRegex(styles.UnsupportedStyleError, "unsupported filter operator"):
            styles.compile_filter([">", "_symbol", 0])

    def test_text_extraction_is_exact_nfc_and_rstrip_only(self) -> None:
        rule = self.contract.rule("Water line/label/Default")
        text = styles.extract_display_text(
            rule,
            {
                "_name_global": "  Cafe\u0301 \t\n",
                "_name_fr": "unused",
                "_name_zh": "unused",
            },
        )
        self.assertEqual(text, "  Caf\u00e9")
        self.assertIsNone(styles.extract_display_text(rule, {"_name_global": " \t"}))
        self.assertIsNone(styles.extract_display_text(rule, {}))
        self.assertIn("_name_global", rule.retained_property_names)
        self.assertNotIn("_name_fr", rule.retained_property_names)
        self.assertNotIn("_name_zh", rule.retained_property_names)

    def test_only_exact_name_templates_are_accepted_on_included_rules(self) -> None:
        bad = _water_label_layer()
        bad["layout"] = dict(bad["layout"], **{"text-field": "River {_name_global}"})
        raw = _style_bytes([bad])
        with self.assertRaisesRegex(styles.UnsupportedStyleError, "exact text-field"):
            styles.compile_style_bytes(
                raw,
                expected_sha256=hashlib.sha256(raw).hexdigest(),
                require_pinned_inventory=False,
            )

    def test_blank_or_absent_matched_text_increments_explicit_no_text_audit(self) -> None:
        rule = self.contract.rule("Water line/label/Default")
        self.assertTrue(hasattr(styles, "TextExtractionAudit"))
        audit = styles.TextExtractionAudit()
        self.assertIsNone(styles.extract_display_text(rule, {}, audit=audit))
        self.assertIsNone(
            styles.extract_display_text(
                rule, {"_name_global": " \t"}, audit=audit
            )
        )
        self.assertEqual(
            styles.extract_display_text(
                rule, {"_name_global": "River"}, audit=audit
            ),
            "River",
        )
        self.assertEqual(audit.no_text_count, 2)
        self.assertEqual(audit.emitted_text_count, 1)

    def test_dynamic_text_field_candidate_reads_and_retains_the_resolved_field(self) -> None:
        layer = _water_label_layer(minzoom=0)
        layer["layout"] = dict(
            layer["layout"],
            **{
                "text-field": {
                    "type": "interval",
                    "stops": [[0, "{_name}"], [18, "{_name_global}"]],
                }
            },
        )
        raw = _style_bytes([layer])
        contract = styles.compile_style_bytes(
            raw,
            expected_sha256=hashlib.sha256(raw).hexdigest(),
            require_pinned_inventory=False,
        )
        rule = contract.rule("Water line/label/Default")
        candidate = contract.line_label_candidate(
            styles.SourcePathOccurrence(
                source_layer="Water line/label",
                source_zoom=18,
                tile_x=1,
                tile_y=2,
                feature_id=3,
                duplicate_ordinal=0,
                path_sha256="12" * 32,
            ),
            {"_name": "Local name", "_name_global": "Global name"},
        )
        self.assertIsNotNone(candidate)
        assert candidate is not None
        self.assertEqual(candidate.text_source_field, "_name_global")
        self.assertEqual(candidate.display_text, "Global name")
        self.assertEqual(
            (
                candidate.display_min_zoom_centi,
                candidate.display_max_zoom_centi,
            ),
            (1800, 2400),
        )
        reachable_candidates = contract.line_label_candidates(
            styles.SourcePathOccurrence(
                source_layer="Water line/label",
                source_zoom=17,
                tile_x=1,
                tile_y=2,
                feature_id=4,
                duplicate_ordinal=0,
                path_sha256="14" * 32,
            ),
            {"_name": "Local name", "_name_global": "Global name"},
        )
        self.assertEqual(
            tuple(
                (
                    item.text_source_field,
                    item.display_text,
                    item.display_min_zoom_centi,
                    item.display_max_zoom_centi,
                )
                for item in reachable_candidates
            ),
            (
                ("_name", "Local name", 0, 1800),
                ("_name_global", "Global name", 1800, 2400),
            ),
        )
        self.assertEqual(
            rule.label_style.text_source_fields, ("_name", "_name_global")
        )
        with self.assertRaisesRegex(
            styles.StyleContractError,
            "dynamic display-text extraction requires current centizoom",
        ):
            styles.extract_display_text(
                rule, {"_name": "Local name", "_name_global": "Global name"}
            )
        self.assertEqual(
            styles.extract_display_text(
                rule,
                {"_name": "Local name", "_name_global": "Global name"},
                current_centizoom=1800,
            ),
            "Global name",
        )
        self.assertEqual(
            rule.label_style.document()["textSourceFields"],
            ["_name", "_name_global"],
        )
        self.assertNotIn("textSourceField", rule.label_style.document())
        self.assertTrue(
            {"_name", "_name_global"}.issubset(rule.retained_property_names)
        )


class SemanticPolicyTests(StyleContractTestCase):
    def test_layer_groups_and_reference_subtypes_are_the_accepted_numeric_contract(self) -> None:
        self.assertEqual(
            {item.name: item.value for item in LayerGroup},
            {
                "PLACES": 1,
                "WATER": 2,
                "REGIONS": 3,
                "PUBLIC_LANDS": 4,
                "TRANSPORTATION": 5,
                "CONTEXT": 6,
            },
        )
        self.assertEqual(len(tuple(SemanticSubtype)), 23)
        self.assertEqual(len(set(item.value for item in SemanticSubtype)), 23)

    def test_transport_subtypes_are_frozen_disjoint_and_do_not_contaminate_filters(self) -> None:
        self.assertEqual(
            {item.name: item.value for item in policy.TransportSubtype},
            {
                "FREEWAY": 1000,
                "HIGHWAY": 1010,
                "FREEWAY_HIGHWAY_RAMP": 1020,
                "MAJOR_ROAD": 1030,
                "MAJOR_ROAD_RAMP": 1040,
                "MINOR_ROAD": 1050,
                "MINOR_ROAD_RAMP": 1060,
                "LOCAL_ROAD": 1070,
                "SERVICE_ROAD": 1080,
                "PEDESTRIAN_WAY": 1090,
                "FOUR_WHEEL_DRIVE": 1100,
                "RAILROAD": 1110,
                "FERRY": 1120,
                "TRAIL_PATH": 1130,
                "AIRPORT": 1140,
                "PORT": 1150,
                "TRANSPORTATION_PLACE": 1160,
                "OTHER_TRANSPORTATION": 1170,
            },
        )
        reference_ids = {item.value for item in SemanticSubtype}
        self.assertTrue(reference_ids.isdisjoint(item.value for item in policy.TransportSubtype))
        self.assertEqual(
            self.contract.catalog_document["presentationSemanticSubtypeIds"],
            sorted(reference_ids),
        )
        self.assertNotIn(
            policy.TransportSubtype.FREEWAY.value,
            self.contract.catalog_document["presentationSemanticSubtypeIds"],
        )

    def test_boundary_table_dispute_and_viz_rules_are_value_exact(self) -> None:
        expected = {
            0: (0, False),
            1: (1, False),
            2: (2, False),
            3: (3, False),
            4: (4, False),
            5: (5, False),
        }
        for symbol, (level, disputed) in expected.items():
            result = policy.classify_boundary(
                "Boundary line", {"_symbol": symbol, "DisputeID": 0, "Viz": 0}
            )
            self.assertEqual((result.admin_level, result.disputed), (level, disputed))
        for symbol in range(6, 12):
            self.assertIsNone(
                policy.classify_boundary(
                    "Boundary line", {"_symbol": symbol, "DisputeID": 0, "Viz": 0}
                )
            )
            result = policy.classify_boundary(
                "Boundary line", {"_symbol": symbol, "DisputeID": 9, "Viz": 0}
            )
            self.assertEqual((result.admin_level, result.disputed), (symbol - 6, True))
        self.assertIsNone(
            policy.classify_boundary(
                "Boundary line", {"_symbol": 0, "DisputeID": 0, "Viz": 3}
            )
        )
        with self.assertRaisesRegex(policy.SemanticPolicyError, "DisputeID"):
            policy.classify_boundary(
                "Boundary line", {"_symbol": 6, "DisputeID": False, "Viz": 0}
            )

    def test_boundary_coastline_and_watershed_kinds_are_not_conflated(self) -> None:
        coast = policy.classify_coastline("Coastline", {})
        self.assertTrue(coast.coastline)
        self.assertEqual(coast.semantic_subtype, SemanticSubtype.COASTLINE.value)
        watershed = policy.classify_boundary("Watershed boundary", {})
        self.assertEqual(watershed.layer_group, LayerGroup.WATER)
        self.assertEqual(
            watershed.semantic_subtype,
            SemanticSubtype.WATERSHED_WATER_BOUNDARY.value,
        )
        self.assertIsNone(watershed.admin_level)

    def test_water_line_and_area_tables_are_exact_and_booleans_use_values(self) -> None:
        line_expected = {
            0: ("stream_or_river", False),
            1: ("canal_or_ditch", False),
            4: ("stream_or_river", True),
        }
        for symbol, expected in line_expected.items():
            result = policy.classify_water_line("Water line", {"_symbol": symbol})
            self.assertEqual((result.kind, result.intermittent), expected)
        self.assertIsNone(policy.classify_water_line("Water line", {"_symbol": 2}))

        area_expected = {
            7: ("lake_river_or_bay", False),
            6: ("lake_river_or_bay", True),
            3: ("swamp_or_marsh", False),
            1: ("playa", False),
            2: ("ice", False),
        }
        for symbol, expected in area_expected.items():
            result = policy.classify_water_area("Water area", {"_symbol": symbol})
            self.assertEqual((result.kind, result.intermittent), expected)

    def test_road_table_tunnel_shield_and_one_way_are_orthogonal_exact_values(self) -> None:
        expected = {
            0: policy.TransportSubtype.FREEWAY,
            1: policy.TransportSubtype.HIGHWAY,
            2: policy.TransportSubtype.FREEWAY_HIGHWAY_RAMP,
            3: policy.TransportSubtype.MAJOR_ROAD,
            4: policy.TransportSubtype.MAJOR_ROAD_RAMP,
            5: policy.TransportSubtype.MINOR_ROAD,
            6: policy.TransportSubtype.MINOR_ROAD_RAMP,
            7: policy.TransportSubtype.LOCAL_ROAD,
            8: policy.TransportSubtype.SERVICE_ROAD,
            9: policy.TransportSubtype.PEDESTRIAN_WAY,
            10: policy.TransportSubtype.FOUR_WHEEL_DRIVE,
        }
        for symbol, subtype in expected.items():
            ordinary = policy.classify_road("Road", {"_symbol": symbol, "Viz": 0})
            tunnel = policy.classify_road("Road tunnel", {"_symbol": symbol, "Viz": 0})
            self.assertEqual(ordinary.semantic_subtype, subtype.value)
            self.assertFalse(ordinary.tunnel)
            self.assertTrue(tunnel.tunnel)
        self.assertIsNone(policy.classify_road("Road", {"_symbol": 0, "Viz": 3}))
        self.assertTrue(policy.transport_style_flags("Road/label/Shield blue white").shield)
        self.assertFalse(policy.transport_style_flags("Road/label/Freeway Motorway").shield)
        self.assertTrue(
            policy.transport_style_flags(
                "Road/label/One-way arrow freeway, motorway, highway"
            ).one_way
        )

    def test_transportation_place_kind_comes_from_exact_style_identity(self) -> None:
        airport = policy.classify_transportation_place(
            "Place/Transportation/Airport", {"_name": "Not A Port"}
        )
        same = policy.classify_transportation_place(
            "Place/Transportation/Airport", {"_name": "A Port"}
        )
        port = policy.classify_transportation_place(
            "Place/Transportation/Port", {"_name": "Airport"}
        )
        self.assertEqual(airport.semantic_subtype, policy.TransportSubtype.AIRPORT.value)
        self.assertEqual(same.semantic_subtype, policy.TransportSubtype.AIRPORT.value)
        self.assertEqual(port.semantic_subtype, policy.TransportSubtype.PORT.value)
        self.assertIsNone(policy.classify_transportation_place("Unknown", {"_name": "Airport"}))

    def test_every_relevant_source_layer_has_one_explicit_numeric_group(self) -> None:
        expected = {
            "Admin0 point": LayerGroup.REGIONS,
            "Admin1 area/label": LayerGroup.REGIONS,
            "City small scale": LayerGroup.PLACES,
            "Boundary line": LayerGroup.REGIONS,
            "Coastline": LayerGroup.WATER,
            "Water line": LayerGroup.WATER,
            "Water line/label": LayerGroup.WATER,
            "Marine park/label": LayerGroup.PUBLIC_LANDS,
            "Park or farming/label": LayerGroup.CONTEXT,
            "Road": LayerGroup.TRANSPORTATION,
            "Road/label": LayerGroup.TRANSPORTATION,
            "Transportation place": LayerGroup.TRANSPORTATION,
        }
        for source_layer, group in expected.items():
            self.assertEqual(policy.SOURCE_LAYER_GROUPS[source_layer], group)
        self.assertEqual(
            len(policy.SOURCE_LAYER_GROUPS), len(set(policy.SOURCE_LAYER_GROUPS))
        )

    def test_public_land_evidence_controls_final_group_and_token(self) -> None:
        explicit = policy.classify_land(
            "Marine park/label", "Marine park/label/Default", {"_name_global": "Bay Park"}
        )
        ambiguous = policy.classify_land(
            "Park or farming/label",
            "Park or farming/label/Default",
            {"_name_global": "National Forest"},
        )
        self.assertEqual(explicit.layer_group, LayerGroup.PUBLIC_LANDS)
        self.assertEqual(explicit.land_evidence, LandEvidence.SOURCE_EXPLICIT)
        self.assertEqual(explicit.protected_status, ProtectedStatus.SOURCE_EXPLICIT)
        self.assertEqual(explicit.semantic_subtype, SemanticSubtype.PROTECTED_LAND.value)
        self.assertEqual(ambiguous.layer_group, LayerGroup.CONTEXT)
        self.assertEqual(ambiguous.land_evidence, LandEvidence.AMBIGUOUS)
        self.assertEqual(ambiguous.protected_status, ProtectedStatus.AMBIGUOUS)
        self.assertNotEqual(explicit.render_style_token_id, ambiguous.render_style_token_id)
        self.assertNotEqual(
            ambiguous.render_style_token_id,
            policy.PUBLIC_LAND_RENDER_STYLE_TOKEN_ID,
        )

    def test_specialized_sources_reject_unknown_selectors_without_generic_fallthrough(self) -> None:
        layers = (
            {
                "id": "Water line/Unsupported",
                "type": "line",
                "source": "esri",
                "source-layer": "Water line",
                "filter": ["==", "_symbol", 2],
                "layout": {},
                "paint": {"line-color": "#000000", "line-width": 1},
            },
            {
                "id": "Water area/Unsupported",
                "type": "fill",
                "source": "esri",
                "source-layer": "Water area",
                "filter": ["==", "_symbol", 99],
                "layout": {},
                "paint": {"fill-color": "#000000"},
            },
            {
                "id": "Road/Unsupported",
                "type": "line",
                "source": "esri",
                "source-layer": "Road",
                "filter": ["all", ["==", "_symbol", 99], ["!in", "Viz", 3]],
                "layout": {},
                "paint": {"line-color": "#000000", "line-width": 1},
            },
            {
                "id": "Boundary line/Unsupported",
                "type": "line",
                "source": "esri",
                "source-layer": "Boundary line",
                "filter": ["==", "_symbol", 99],
                "layout": {},
                "paint": {"line-color": "#000000", "line-width": 1},
            },
            _water_label_layer(
                id="Place/Transportation/Unknown",
                **{"source-layer": "Transportation place"},
            ),
        )
        for layer in layers:
            with self.subTest(style_layer_id=layer["id"]):
                raw = _style_bytes([layer])
                with self.assertRaisesRegex(
                    styles.StyleContractError, "no exact semantic policy"
                ):
                    styles.compile_style_bytes(
                        raw,
                        expected_sha256=hashlib.sha256(raw).hexdigest(),
                        require_pinned_inventory=False,
                    )

    def test_style_only_overrides_require_exact_source_layer_ownership(self) -> None:
        one_way_id = "Road/label/One-way arrow local road"
        with self.assertRaisesRegex(
            policy.SemanticPolicyError,
            "style identity is not owned by source layer",
        ):
            policy.classification_for_style_rule(
                "Water line/label",
                one_way_id,
                "symbol",
                {"_label_class": 1},
            )
        expected_one_way = policy.classification_for_style_rule(
            "Road/label", one_way_id, "symbol", {"_label_class": 1}
        )
        self.assertFalse(
            policy.classification_matches(
                expected_one_way,
                source_layer="Water line/label",
                style_layer_id=one_way_id,
                properties={"DirTravel": "F"},
            )
        )
        disputed_id = "Disputed label point/Island"
        with self.assertRaisesRegex(
            policy.SemanticPolicyError,
            "style identity is not owned by source layer",
        ):
            policy.classification_for_style_rule(
                "City small scale", disputed_id, "symbol", {}
            )

    def test_every_style_identity_requires_one_exact_frozen_source_owner(self) -> None:
        rejected = (
            (
                "City large scale",
                "Water point/Stream or river",
                "symbol",
            ),
            (
                "Water line/label",
                "Road/label/Shield blue white",
                "symbol",
            ),
            (
                "City large scale",
                "City large scale/Arbitrary",
                "symbol",
            ),
        )
        for source_layer, style_layer_id, layer_type in rejected:
            with self.subTest(style_layer_id=style_layer_id):
                with self.assertRaisesRegex(
                    policy.SemanticPolicyError,
                    "style identity is not owned by source layer",
                ):
                    policy.classification_for_style_rule(
                        source_layer, style_layer_id, layer_type, {}
                    )
        self.assertEqual(len(policy.OWNED_SOURCE_STYLE_PAIR_SHA256), 294)
        for rule in self.contract.rules:
            with self.subTest(owned=rule.style_layer_id):
                self.assertTrue(
                    policy.source_style_identity_is_owned(
                        rule.source_layer, rule.style_layer_id
                    )
                )
        ownership_document = policy.semantic_policy_document()[
            "sourceStyleOwnership"
        ]
        self.assertEqual(ownership_document["ownedPairCount"], 294)
        self.assertEqual(
            ownership_document["ownedPairSha256"],
            sorted(
                digest.hex()
                for digest in policy.OWNED_SOURCE_STYLE_PAIR_SHA256
            ),
        )


class CompiledRuleTests(StyleContractTestCase):
    def test_locked_water_line_label_rule_preserves_every_source_value(self) -> None:
        rule = self.contract.rule("Water line/label/Default")
        self.assertTrue(hasattr(styles, "resolve_label_style"))
        label = styles.resolve_label_style(
            rule, current_centizoom=1200, properties={}
        )
        self.assertEqual(rule.source_layer, "Water line/label")
        self.assertEqual(rule.min_zoom_centi, 1200)
        self.assertEqual(rule.max_zoom_centi, 2400)
        self.assertEqual(label.text_source_field, "_name_global")
        self.assertEqual(label.placement, "line")
        self.assertTrue(label.avoid_edges)
        self.assertEqual(label.repeat_distance_px, 1000)
        self.assertEqual(label.font_families, ("Arial Italic",))
        self.assertEqual(label.font_slant, "italic")
        self.assertEqual(label.text_size_milli, 10_000)
        self.assertEqual(label.letter_spacing_milli_em, 70)
        self.assertEqual(label.max_width_milli_em, 8_000)
        self.assertEqual(label.max_angle_centi_degrees, 3_000)
        self.assertEqual(label.offset_milli_em, (0, -500))
        self.assertEqual(label.color_argb, 0xFF497AAB)
        self.assertEqual(label.opacity_milli, 1000)
        self.assertEqual(label.fade_in_centi, 0)
        self.assertEqual(label.fade_out_centi, 0)
        self.assertEqual(label.collision_group, 1)
        self.assertTrue(label.whole_text)
        self.assertEqual(label.per_glyph_record_count, 0)

    def test_line_casing_precedes_inner_stroke_for_same_exact_selector(self) -> None:
        boundary = self.contract.stroke_stack(
            "Boundary line", {"_symbol": 2, "Viz": 0}, zoom_centi=1200
        )
        self.assertEqual(
            tuple(item.stroke_role for item in boundary), ("casing", "inner")
        )
        self.assertEqual(
            tuple(item.style_layer_id for item in boundary),
            ("Boundary line/Admin2/casing", "Boundary line/Admin2/line"),
        )
        road = self.contract.stroke_stack(
            "Road", {"_symbol": 0, "Viz": 0}, zoom_centi=1000
        )
        self.assertEqual(tuple(item.stroke_role for item in road), ("casing", "inner"))

    def test_label_fields_fade_collision_priority_and_tokens_are_explicit(self) -> None:
        rule = self.contract.rule("Water line/label/Default")
        self.assertTrue(hasattr(rule, "semantic_collision_priority"))
        self.assertIsNone(rule.semantic_collision_priority)
        self.assertEqual(
            rule.priority_basis,
            "requires_feature_prominence_decision",
        )
        self.assertGreaterEqual(rule.draw_order, 0)
        label = styles.resolve_label_style(
            rule, current_centizoom=1200, properties={}
        )
        self.assertEqual(label.collision_group, 1)
        self.assertEqual(label.repeat_distance_px, 1000)
        self.assertEqual(len(rule.source_style_layer_ids), 1)
        self.assertEqual(len(rule.render_style_token_ids), 1)
        self.assertNotEqual(rule.source_style_layer_ids, rule.render_style_token_ids)

    def test_provider_decizoom_and_style_zoom_form_one_half_open_decimal_interval(self) -> None:
        interval = styles.combine_zoom_interval(
            Decimal("7.25"),
            Decimal("12.75"),
            {"_minzoom": "73", "_maxzoom": "120"},
        )
        self.assertEqual(interval, (730, 1200))
        self.assertTrue(styles.zoom_interval_contains(interval, 730))
        self.assertTrue(styles.zoom_interval_contains(interval, 1199))
        self.assertFalse(styles.zoom_interval_contains(interval, 1200))
        with self.assertRaisesRegex(styles.StyleContractError, "binary float"):
            styles.combine_zoom_interval(7.25, Decimal("12.75"), {})

    def test_water_large_scale_keeps_fallback_evidence_without_style_inheritance(self) -> None:
        rule = self.contract.rule("Water line large scale")
        self.assertIsNone(rule.label_style)
        self.assertEqual(rule.fallback_text_source_field, "_name_en")
        self.assertIn("_name_en", rule.retained_property_names)
        self.assertNotIn("Water line/label/Default", rule.inherited_style_layer_ids)

    def test_production_policy_has_no_literal_feature_name_branch(self) -> None:
        production = (
            Path(styles.__file__).read_text(encoding="utf-8")
            + Path(policy.__file__).read_text(encoding="utf-8")
        )
        forbidden = "Chester" + " River"
        self.assertNotIn(forbidden, production)

    def test_unsupported_expression_on_included_rule_is_a_hard_failure(self) -> None:
        layer = _water_label_layer()
        layer["layout"] = dict(
            layer["layout"],
            **{"text-size": ["interpolate", ["linear"], ["zoom"], 10, 10, 12, 12]},
        )
        raw = _style_bytes([layer])
        with self.assertRaisesRegex(styles.UnsupportedStyleError, "unsupported expression"):
            styles.compile_style_bytes(
                raw,
                expected_sha256=hashlib.sha256(raw).hexdigest(),
                require_pinned_inventory=False,
            )


class RejectedReviewRegressionTests(StyleContractTestCase):
    @classmethod
    def setUpClass(cls) -> None:
        super().setUpClass()
        if _IMPORT_ERROR is None:
            cls.raw_layers = json.loads(
                PINNED_STYLE.read_text(encoding="utf-8")
            )["layers"]

    def test_allowlisted_water_area_fills_compile_as_polygon_outlines(self) -> None:
        source_layers = {
            "Water area",
            "Water area large scale",
            "Water area medium scale",
            "Water area small scale",
        }
        rules = [
            rule for rule in self.contract.rules if rule.source_layer in source_layers
        ]
        self.assertEqual(len(rules), 10)
        for rule in rules:
            with self.subTest(rule=rule.style_layer_id):
                self.assertEqual(rule.feature_kind, FeatureKind.POLYGON_OUTLINE)
                self.assertIsNotNone(rule.area_style)
                self.assertIsNone(rule.line_style)
                self.assertIsNone(rule.label_style)
                self.assertEqual(rule.area_style.source_style_type, "fill")
                self.assertEqual(rule.area_style.renderer_draw_mode, "polygon_outline")
                self.assertTrue(rule.area_style.source_fill_evidence_retained)
                self.assertFalse(rule.area_style.renders_provider_fill)
        self.assertEqual(
            self.contract.catalog_document["includedRuleCounts"]["water_polygons"],
            10,
        )
        self.assertEqual(
            self.contract.audit_entry("Park or farming").reason,
            "satellite_base_owned_fill",
        )

    def test_water_geometry_uses_explicit_master_only_subtypes_not_label_filters(self) -> None:
        line_ids = {
            "Water line small scale",
            "Water line medium scale",
            "Water line large scale",
            "Water line/Canal or ditch",
            "Water line/Stream or river intermittent",
            "Water line/Stream or river",
        }
        area_ids = {
            "Water area small scale",
            "Water area medium scale/Lake intermittent",
            "Water area medium scale/Lake or river",
            "Water area large scale/Lake intermittent",
            "Water area large scale/Lake or river",
            "Water area/Lake, river or bay",
            "Water area/Lake or river intermittent",
        }
        line_subtype = policy.MasterOnlyGeometrySubtype.WATERCOURSE_LINE.value
        area_subtype = policy.MasterOnlyGeometrySubtype.WATER_AREA_OUTLINE.value
        self.assertEqual(
            {self.contract.rule(style_id).semantic_subtype for style_id in line_ids},
            {line_subtype},
        )
        self.assertEqual(
            {self.contract.rule(style_id).semantic_subtype for style_id in area_ids},
            {area_subtype},
        )
        master_only_ids = {item.value for item in policy.MasterOnlyGeometrySubtype}
        presentation_ids = {item.value for item in SemanticSubtype}
        filtered_ids = {
            subtype
            for subtypes in policy.PRESENTATION_FILTER_SUBTYPES.values()
            for subtype in subtypes
        }
        self.assertTrue(master_only_ids.isdisjoint(presentation_ids))
        self.assertTrue(master_only_ids.isdisjoint(filtered_ids))
        self.assertEqual(
            self.contract.catalog_document[
                "masterOnlyGeometrySemanticSubtypeIds"
            ],
            sorted(master_only_ids),
        )
        self.assertEqual(len(self.contract.catalog_document["presentationSemanticSubtypeIds"]), 23)
        self.assertEqual(len(self.contract.catalog_document["presentationFilters"]), 15)
        self.assertEqual(
            {
                rule.style_layer_id
                for rule in self.contract.rules
                if rule.semantic_subtype in master_only_ids
            },
            line_ids | area_ids,
        )

    def test_water_area_source_specific_symbols_classify_exactly(self) -> None:
        expected = {
            "Water area/Lake, river or bay": ("lake_river_or_bay", False),
            "Water area/Lake or river intermittent": ("lake_river_or_bay", True),
            "Water area/Swamp or marsh": ("swamp_or_marsh", False),
            "Water area/Playa": ("playa", False),
            "Water area/Ice mass": ("ice", False),
            "Water area medium scale/Lake or river": ("lake_river_or_bay", False),
            "Water area medium scale/Lake intermittent": ("lake_river_or_bay", True),
            "Water area large scale/Lake or river": ("lake_river_or_bay", False),
            "Water area large scale/Lake intermittent": ("lake_river_or_bay", True),
            "Water area small scale": ("lake_river_or_bay", False),
        }
        compiled_ids = {rule.style_layer_id for rule in self.contract.rules}
        self.assertTrue(set(expected).issubset(compiled_ids))
        for style_id, values in expected.items():
            rule = self.contract.rule(style_id)
            self.assertEqual((rule.semantic_kind, rule.intermittent), values)

    def test_all_68_shields_and_10_one_way_rules_have_exact_census(self) -> None:
        pinned_shields = {
            layer["id"] for layer in self.raw_layers[659:727]
        }
        pinned_one_way = {
            layer["id"]
            for layer in self.raw_layers
            if layer["id"].startswith(
                ("Road/label/One-way", "Road tunnel/label/One-way")
            )
        }
        self.assertEqual(len(pinned_shields), 68)
        self.assertEqual(len(policy.ROAD_SHIELD_STYLE_IDS), 68)
        self.assertEqual(policy.ROAD_SHIELD_STYLE_IDS, pinned_shields)
        self.assertIn("Road/label/Rectangle red white", pinned_shields)
        self.assertIn("Road/label/Rectangle blue white (Alt)", pinned_shields)
        self.assertEqual(len(pinned_one_way), 10)
        self.assertEqual(policy.ONE_WAY_STYLE_IDS, pinned_one_way)
        for style_id in pinned_shields:
            with self.subTest(shield=style_id):
                self.assertTrue(self.contract.rule(style_id).shield)

    def test_one_way_icons_are_typed_path_rules_with_direction_semantics(self) -> None:
        self.assertTrue(hasattr(self.contract, "rule_matches"))
        exact = {
            "Road/label/One-way arrow local road": (
                policy.TransportSubtype.LOCAL_ROAD,
                False,
            ),
            "Road/label/One-way arrow freeway, motorway, highway ramp": (
                policy.TransportSubtype.FREEWAY_HIGHWAY_RAMP,
                False,
            ),
            "Road tunnel/label/One-way arrow major road": (
                policy.TransportSubtype.MAJOR_ROAD,
                True,
            ),
        }
        raw_by_id = {layer["id"]: layer for layer in self.raw_layers}
        for style_id, (subtype, tunnel) in exact.items():
            rule = self.contract.rule(style_id)
            self.assertEqual(rule.feature_kind, FeatureKind.LINE)
            self.assertEqual(rule.layer_group, LayerGroup.TRANSPORTATION)
            self.assertEqual(rule.semantic_subtype, subtype.value)
            self.assertTrue(rule.one_way)
            self.assertEqual(rule.tunnel, tunnel)
            self.assertIn("DirTravel", rule.retained_property_names)
            label_class = raw_by_id[style_id]["filter"][1][2]
            base = {"_label_class": label_class, "Viz": 0}
            self.assertTrue(
                self.contract.rule_matches(
                    rule, dict(base, DirTravel="F"), zoom_centi=1600
                )
            )
            self.assertTrue(
                self.contract.rule_matches(
                    rule, dict(base, DirTravel="T"), zoom_centi=1600
                )
            )
            self.assertFalse(
                self.contract.rule_matches(
                    rule, dict(base, DirTravel="X"), zoom_centi=1600
                )
            )
            self.assertFalse(
                self.contract.rule_matches(rule, base, zoom_centi=1600)
            )

    def test_disputed_label_styles_have_exact_non_name_classification(self) -> None:
        expected = {
            "Disputed label point/Island": (
                LayerGroup.WATER,
                SemanticSubtype.ISLAND_ISLET,
                {"_label_class": 1, "DisputeID": 0},
            ),
            "Disputed label point/Waterbody": (
                LayerGroup.WATER,
                SemanticSubtype.LAKE_RESERVOIR,
                {"_label_class": 0, "DisputeID": 1006},
            ),
            "Disputed label point/Admin0": (
                LayerGroup.REGIONS,
                SemanticSubtype.COUNTRY_TERRITORY,
                {"_label_class": 2, "DisputeID": 1021},
            ),
        }
        for style_id, (group, subtype, properties) in expected.items():
            rule = self.contract.rule(style_id)
            self.assertEqual(rule.layer_group, group)
            self.assertEqual(rule.semantic_subtype, subtype.value)
            self.assertTrue(rule.disputed)
            self.assertTrue(
                self.contract.rule_matches(
                    rule,
                    dict(properties, _name="Any display text"),
                    zoom_centi=max(rule.min_zoom_centi, 600),
                )
            )
            self.assertTrue(
                self.contract.rule_matches(
                    rule,
                    dict(properties, _name="Completely different"),
                    zoom_centi=max(rule.min_zoom_centi, 600),
                )
            )

    def test_every_classification_field_is_persisted_in_rule_and_catalog(self) -> None:
        boundary = self.contract.rule("Boundary line/Disputed admin5")
        self.assertTrue(hasattr(boundary, "admin_level"))
        self.assertEqual(boundary.admin_level, 5)
        self.assertTrue(boundary.disputed)
        self.assertFalse(boundary.coastline)
        self.assertFalse(boundary.intermittent)
        self.assertFalse(boundary.tunnel)
        self.assertFalse(boundary.shield)
        self.assertFalse(boundary.one_way)
        self.assertEqual(boundary.land_evidence, LandEvidence.NOT_APPLICABLE)
        self.assertEqual(boundary.protected_status, ProtectedStatus.NOT_APPLICABLE)
        catalog_rule = next(
            item
            for item in self.contract.catalog_document["rules"]
            if item["styleLayerId"] == boundary.style_layer_id
        )
        self.assertEqual(catalog_rule["adminLevel"], 5)
        self.assertTrue(catalog_rule["disputed"])
        self.assertIn("landEvidence", catalog_rule)
        self.assertIn("protectedStatus", catalog_rule)

    def test_runtime_semantic_predicate_blocks_false_disputed_boundary(self) -> None:
        self.assertTrue(hasattr(self.contract, "rule_matches"))
        rule = self.contract.rule("Boundary line/Disputed admin5")
        base = {"_symbol": 11, "Viz": 0}
        self.assertFalse(
            self.contract.rule_matches(rule, base, zoom_centi=1700)
        )
        self.assertFalse(
            self.contract.rule_matches(
                rule, dict(base, DisputeID=0), zoom_centi=1700
            )
        )
        self.assertTrue(
            self.contract.rule_matches(
                rule, dict(base, DisputeID=9), zoom_centi=1700
            )
        )

    def test_stroke_stack_intersects_provider_decizoom_half_open_interval(self) -> None:
        base = {"_symbol": 0, "Viz": 0}
        self.assertEqual(
            self.contract.stroke_stack(
                "Road",
                dict(base, _minzoom="110", _maxzoom="130"),
                zoom_centi=1000,
            ),
            (),
        )
        self.assertEqual(
            self.contract.stroke_stack(
                "Road",
                dict(base, _minzoom="90", _maxzoom="100"),
                zoom_centi=1000,
            ),
            (),
        )
        self.assertGreater(
            len(
                self.contract.stroke_stack(
                    "Road",
                    dict(base, _minzoom="90", _maxzoom="120"),
                    zoom_centi=1000,
                )
            ),
            0,
        )
        with self.assertRaisesRegex(styles.StyleContractError, "_minzoom"):
            self.contract.stroke_stack(
                "Road", dict(base, _minzoom=9.0), zoom_centi=1000
            )

    def test_presentation_policy_sha_and_all_15_filters_are_bound(self) -> None:
        expected_filters = {
            filter_id.value: [item.value for item in filter_spec(filter_id).subtypes]
            for filter_id in FilterId
        }
        self.assertEqual(len(expected_filters), 15)
        self.assertIn("presentationPolicySha256", self.contract.catalog_document)
        self.assertIn("presentationFilters", self.contract.catalog_document)
        self.assertEqual(
            PRESENTATION_POLICY_SHA256,
            "40f4e98394dacfaaad7cdc195858d0b56fc72ba5c83ccfc1e75d71fff6f6395c",
        )
        self.assertEqual(
            self.contract.catalog_document["presentationPolicySha256"],
            PRESENTATION_POLICY_SHA256,
        )
        self.assertEqual(
            self.contract.catalog_document["presentationFilters"], expected_filters
        )
        semantic_document = json.loads(
            policy.canonical_semantic_policy_bytes()
        )
        self.assertEqual(
            semantic_document["presentationPolicySha256"],
            PRESENTATION_POLICY_SHA256,
        )
        self.assertEqual(
            semantic_document["presentationFilters"], expected_filters
        )
        manifest = json.loads(styles.style_evidence_bytes(self.contract)["manifest.json"])
        self.assertEqual(
            manifest["presentationPolicySha256"], PRESENTATION_POLICY_SHA256
        )
        self.assertEqual(manifest["presentationFilters"], expected_filters)
        self.assertEqual(
            manifest["presentationSemanticSubtypeIds"],
            sorted(item.value for item in SemanticSubtype),
        )

    def test_semantic_digest_binds_every_classification_table_and_defaults(self) -> None:
        self.assertTrue(hasattr(policy, "semantic_policy_document"))
        document = policy.semantic_policy_document()
        required = {
            "boundarySymbolTable",
            "cityCapitalStyleIds",
            "classificationEnumIds",
            "classifierBehaviorVectors",
            "fixedClassifications",
            "landEvidence",
            "waterLabelStyleSubtypes",
            "waterAreaSymbolTableBySource",
            "waterLineSymbolTable",
            "roadSymbolTable",
            "roadLabelStyleSubtypes",
            "transportPlaceStyleIds",
            "transportPlaceSubtypeOverrides",
            "roadShieldStyleIds",
            "oneWayStyleSubtypes",
            "disputedLabelStyleClassifications",
            "sourceLayers",
            "classifierDefaults",
        }
        self.assertTrue(required.issubset(document))
        self.assertEqual(len(document["roadShieldStyleIds"]), 68)
        self.assertEqual(len(document["oneWayStyleSubtypes"]), 10)
        baseline = policy.semantic_policy_sha256(document)
        self.assertEqual(policy.SEMANTIC_POLICY_SHA256, baseline)
        for key in required:
            with self.subTest(section=key):
                mutated = deepcopy(document)
                mutated[key] = {"mutated": True}
                self.assertNotEqual(policy.semantic_policy_sha256(mutated), baseline)
        mutated = deepcopy(document)
        mutated["classifierDefaults"]["vizExcludedValue"] = 4
        self.assertNotEqual(policy.semantic_policy_sha256(mutated), baseline)

    def test_semantic_digest_rejects_exact_integer_behavior_mutation(self) -> None:
        document = policy.semantic_policy_document()
        behavior = document["classifierBehaviorVectors"]
        self.assertEqual(
            {item["inputKind"] for item in behavior["exactInteger"]},
            {"boolean", "float", "integer", "missing", "string"},
        )
        self.assertEqual(
            {item["name"] for item in behavior["exactInteger"]},
            {"DisputeID", "Viz", "_symbol"},
        )
        for name in ("DisputeID", "Viz", "_symbol"):
            self.assertEqual(
                {
                    item["inputKind"]
                    for item in behavior["exactInteger"]
                    if item["name"] == name
                },
                {"boolean", "float", "integer", "missing", "string"},
            )
        self.assertEqual(
            {item["inputKind"] for item in behavior["unknownSelectors"]},
            {"unknown"},
        )
        baseline = policy.SEMANTIC_POLICY_SHA256
        mutated = deepcopy(document)
        mutated["classifierBehaviorVectors"]["exactInteger"][0]["required"] = False
        self.assertNotEqual(policy.semantic_policy_sha256(mutated), baseline)

        exact_int = policy._exact_int

        def bool_coercing_exact_int(
            properties: Mapping[str, object],
            name: str,
            *,
            required: bool,
        ) -> int | None:
            if name in properties and type(properties[name]) is bool:
                return int(properties[name])
            return exact_int(properties, name, required=required)

        with patch.object(policy, "_exact_int", side_effect=bool_coercing_exact_int):
            with self.assertRaisesRegex(
                policy.SemanticPolicyError, "classifier behavior vector"
            ):
                policy.semantic_policy_sha256()

    def test_semantic_identity_executes_every_public_classifier_failure_domain(
        self,
    ) -> None:
        behavior = policy.semantic_policy_document()["classifierBehaviorVectors"]
        expected_classifiers = {
            name
            for name, value in vars(policy).items()
            if name.startswith("classify_") and callable(value)
        }
        expected_classifiers.add("classification_for_style_rule")
        self.assertIn("publicClassifiers", behavior)
        self.assertEqual(set(behavior["publicClassifiers"]), expected_classifiers)
        for classifier_name, vectors in behavior["publicClassifiers"].items():
            with self.subTest(classifier=classifier_name):
                self.assertTrue(vectors)
                self.assertTrue(
                    {"boolean", "float", "string", "missing", "unknown"}.issubset(
                        {vector["inputKind"] for vector in vectors}
                    )
                )
        accepted_domains = behavior["acceptedDomains"]
        self.assertEqual(
            accepted_domains["classify_water_line"]["Water line"],
            sorted(policy._WATER_LINE_KIND),
        )
        self.assertEqual(
            accepted_domains["classify_road"]["Road"],
            sorted(policy._ROAD_SUBTYPE),
        )
        self.assertEqual(
            accepted_domains["classify_water_area"],
            {
                source_layer: [
                    "missing" if value is None else value
                    for value in sorted(
                        table,
                        key=lambda item: -1 if item is None else item,
                    )
                ]
                for source_layer, table in policy._WATER_AREA_KIND_BY_SOURCE.items()
            },
        )
        self.assertEqual(
            accepted_domains["classification_for_style_rule"]
            ["acceptedLayerTypesBySource"],
            {
                source_layer: list(source_policy.accepted_types)
                for source_layer, source_policy in sorted(
                    policy.SOURCE_LAYER_POLICIES.items()
                )
            },
        )

    def test_semantic_identity_fails_when_public_water_line_classifier_is_permissive(
        self,
    ) -> None:
        original = policy.classify_water_line

        def permissive_water_line(
            source_layer: str, properties: Mapping[str, object]
        ):
            if source_layer == "Water line" and (
                "_symbol" not in properties
                or properties.get("_symbol") in (True, 0.0, "0", 99)
            ):
                return original(source_layer, {"_symbol": 0})
            return original(source_layer, properties)

        with patch.object(
            policy, "classify_water_line", side_effect=permissive_water_line
        ):
            with self.assertRaisesRegex(
                policy.SemanticPolicyError, "classifier behavior vector"
            ):
                policy.semantic_policy_sha256()

    def test_known_sources_reject_unowned_fake_style_identities(self) -> None:
        with self.assertRaisesRegex(
            policy.SemanticPolicyError, "style identity is not owned"
        ):
            policy.classify_land(
                "Park or farming/label",
                "Park or farming/label/Fake",
                {},
            )
        with self.assertRaisesRegex(
            policy.SemanticPolicyError, "style identity is not owned"
        ):
            policy.classification_for_style_rule(
                "Water line",
                "Water line/Fake",
                "line",
                {"_symbol": 0},
            )

    def test_semantic_identity_fails_when_style_rule_accepts_unowned_fake_style(
        self,
    ) -> None:
        behavior = policy.semantic_policy_document()["classifierBehaviorVectors"]
        style_rule_vectors = behavior["publicClassifiers"][
            "classification_for_style_rule"
        ]
        self.assertTrue(
            any(
                vector.get("sourceLayer") == "Water line"
                and vector.get("styleLayerId") == "Water line/Fake"
                and vector.get("properties") == {"_symbol": 0}
                for vector in style_rule_vectors
            ),
            "semantic identity must execute a known-source/unowned-style pair",
        )
        original = policy.classification_for_style_rule

        def permissive_style_rule(
            source_layer: str,
            style_layer_id: str,
            layer_type: str,
            selector_properties: Mapping[str, object],
        ):
            if (
                source_layer == "Water line"
                and style_layer_id == "Water line/Fake"
            ):
                return original(
                    source_layer,
                    "Water line/Stream or river",
                    layer_type,
                    selector_properties,
                )
            return original(
                source_layer,
                style_layer_id,
                layer_type,
                selector_properties,
            )

        with patch.object(
            policy,
            "classification_for_style_rule",
            side_effect=permissive_style_rule,
        ):
            with self.assertRaisesRegex(
                policy.SemanticPolicyError, "classifier behavior vector"
            ):
                policy.semantic_policy_sha256()

    def test_semantic_identity_fails_when_style_rule_accepts_wrong_layer_type(
        self,
    ) -> None:
        original = policy.classification_for_style_rule

        def permissive_style_rule(
            source_layer: str,
            style_layer_id: str,
            layer_type: str,
            selector_properties: Mapping[str, object],
        ):
            if (
                source_layer == "Water line"
                and style_layer_id == "Water line/Stream or river"
                and layer_type == "symbol"
            ):
                layer_type = "line"
            return original(
                source_layer,
                style_layer_id,
                layer_type,
                selector_properties,
            )

        with patch.object(
            policy,
            "classification_for_style_rule",
            side_effect=permissive_style_rule,
        ):
            with self.assertRaisesRegex(
                policy.SemanticPolicyError, "classifier behavior vector"
            ):
                policy.semantic_policy_sha256()

    def test_semantic_identity_fails_when_classifiers_accept_wrong_known_sources(
        self,
    ) -> None:
        def boundary_mutation(original):
            def mutated(source_layer, properties):
                if source_layer == "Water line":
                    return original("Boundary line", properties)
                return original(source_layer, properties)

            return mutated

        def coastline_mutation(original):
            def mutated(source_layer, properties):
                if source_layer == "Boundary line":
                    return original("Coastline", properties)
                return original(source_layer, properties)

            return mutated

        def water_line_mutation(original):
            def mutated(source_layer, properties):
                if source_layer == "Boundary line":
                    return original("Water line", properties)
                return original(source_layer, properties)

            return mutated

        def water_area_mutation(original):
            def mutated(source_layer, properties):
                if source_layer == "Road":
                    return original("Water area", properties)
                return original(source_layer, properties)

            return mutated

        def road_mutation(original):
            def mutated(source_layer, properties):
                if source_layer == "Water line":
                    return original("Road", properties)
                return original(source_layer, properties)

            return mutated

        def one_way_mutation(original):
            def mutated(
                source_layer,
                style_layer_id,
                properties,
                *,
                require_direction=True,
            ):
                if source_layer == "Water line/label":
                    item = policy.ONE_WAY_STYLE_CLASSIFICATIONS.get(
                        style_layer_id
                    )
                    if item is not None:
                        source_layer = (
                            "Road tunnel/label" if item[1] else "Road/label"
                        )
                return original(
                    source_layer,
                    style_layer_id,
                    properties,
                    require_direction=require_direction,
                )

            return mutated

        def disputed_mutation(original):
            def mutated(source_layer, style_layer_id):
                if source_layer == "City small scale":
                    source_layer = "Disputed label point"
                return original(source_layer, style_layer_id)

            return mutated

        def land_mutation(original):
            def mutated(source_layer, style_layer_id, properties):
                if (
                    source_layer == "City small scale"
                    and style_layer_id == "Park or farming/label/Default"
                ):
                    source_layer = "Park or farming/label"
                return original(source_layer, style_layer_id, properties)

            return mutated

        mutations = {
            "classify_boundary": boundary_mutation,
            "classify_coastline": coastline_mutation,
            "classify_disputed_label_style": disputed_mutation,
            "classify_land": land_mutation,
            "classify_one_way_style": one_way_mutation,
            "classify_road": road_mutation,
            "classify_water_area": water_area_mutation,
            "classify_water_line": water_line_mutation,
        }
        for classifier_name, mutation in mutations.items():
            with self.subTest(classifier=classifier_name):
                original = getattr(policy, classifier_name)
                with patch.object(
                    policy,
                    classifier_name,
                    side_effect=mutation(original),
                ):
                    with self.assertRaisesRegex(
                        policy.SemanticPolicyError,
                        "classifier behavior vector",
                    ):
                        policy.semantic_policy_sha256()

    def test_semantic_document_binds_complete_actual_classifier_outputs(self) -> None:
        document = policy.semantic_policy_document()
        expected_fields = {
            "adminLevel",
            "coastline",
            "disputed",
            "featureKind",
            "intermittent",
            "kind",
            "landEvidence",
            "layerGroup",
            "oneWay",
            "protectedStatus",
            "renderStyleTokenId",
            "semanticSubtype",
            "shield",
            "tunnel",
        }
        one_way_id = "Road/label/One-way arrow local road"
        one_way = policy.classify_one_way_style(
            "Road/label", one_way_id, {}, require_direction=False
        )
        water_area = policy.classify_water_area(
            "Water area", {"_symbol": 7}
        )
        road = policy.classify_road("Road tunnel", {"_symbol": 0, "Viz": 0})
        disputed = policy.classify_disputed_label_style(
            "Disputed label point", "Disputed label point/Island"
        )
        transport = policy.classify_transportation_place(
            "Place/Transportation/Airport", {}
        )
        water_label_id = "Water point/Stream or river"
        water_label = policy.classification_for_style_rule(
            "Water point", water_label_id, "symbol", {}
        )
        road_label_id = "Road/label/Local"
        road_label = policy.classification_for_style_rule(
            "Road/label", road_label_id, "symbol", {}
        )
        shield_id = "Road/label/Shield blue white"
        shield = policy.classification_for_style_rule(
            "Road/label", shield_id, "symbol", {}
        )
        city_id = "City small scale/town small admin0 capital"
        city = policy.classification_for_style_rule(
            "City small scale", city_id, "symbol", {}
        )
        cases = (
            (document["oneWayStyleSubtypes"][one_way_id], one_way),
            (document["waterAreaSymbolTableBySource"]["Water area"]["7"], water_area),
            (document["roadSymbolClassificationsBySource"]["Road tunnel"]["0"], road),
            (
                document["disputedLabelStyleClassifications"]
                ["Disputed label point/Island"],
                disputed,
            ),
            (
                document["transportPlaceStyleClassifications"]
                ["Place/Transportation/Airport"],
                transport,
            ),
            (
                document["waterLabelStyleClassifications"][water_label_id],
                water_label,
            ),
            (
                document["roadLabelStyleClassifications"][road_label_id],
                road_label,
            ),
            (
                document["roadShieldStyleClassifications"][shield_id],
                shield,
            ),
            (document["cityCapitalStyleClassifications"][city_id], city),
        )
        for recorded, actual in cases:
            with self.subTest(kind=actual.kind):
                self.assertEqual(set(recorded), expected_fields)
                self.assertEqual(
                    recorded,
                    policy.semantic_classification_document(actual),
                )

    def test_runtime_classifier_tables_are_immutable_after_policy_digest(self) -> None:
        tables_and_keys = (
            (policy._WATER_LINE_KIND, 0),
            (policy._ROAD_SUBTYPE, 0),
            (policy._ONE_WAY_SUFFIX_SUBTYPE, "One-way arrow local road"),
            (
                policy._DISPUTED_LABEL_STYLE_CLASSIFICATIONS,
                "Disputed label point/Island",
            ),
            (policy._TRANSPORT_PLACE_SUBTYPE, "Place/Transportation/Airport"),
            (policy._WATER_LABEL_SUBTYPE, "Water point/Stream or river"),
            (policy._ROAD_LABEL_SUBTYPE, "Road/label/Local"),
        )
        for table, key in tables_and_keys:
            with self.subTest(table=key):
                original = table[key]
                with self.assertRaises(TypeError):
                    table[key] = original
        nested = policy._WATER_AREA_KIND_BY_SOURCE["Water area"]
        with self.assertRaises(TypeError):
            nested[7] = nested[7]
        with self.assertRaises(TypeError):
            policy._WATER_AREA_KIND_BY_SOURCE["Water area"] = nested
        self.assertEqual(policy.semantic_policy_sha256(), policy.SEMANTIC_POLICY_SHA256)

    def test_rule_policy_identity_rejects_one_field_semantic_mutations(self) -> None:
        rule = self.contract.rule("Water line/label/Default")
        variants = (
            {"style_order": rule.style_order + 1},
            {"source_layer": "Water line/label changed"},
            {"layer_group": LayerGroup.PLACES},
            {"semantic_subtype": SemanticSubtype.RIVER.value},
            {"semantic_kind": "mutated_label_kind"},
            {"source_style_layer_ids": (999,)},
            {"render_style_token_ids": (rule.render_style_token_ids[0] + 1,)},
            {"admin_level": 0},
            {"disputed": True},
            {"coastline": True},
            {"intermittent": True},
            {"tunnel": True},
            {"shield": True},
            {"one_way": True},
            {"land_evidence": LandEvidence.AMBIGUOUS},
            {"protected_status": ProtectedStatus.AMBIGUOUS},
            {"fallback_text_source_field": "_name_en"},
        )
        for kwargs in variants:
            with self.subTest(kwargs=kwargs):
                with self.assertRaisesRegex(
                    styles.StyleContractError,
                    "style-policy SHA-256 does not bind compiled rule",
                ):
                    replace(rule, **kwargs)

    def test_rule_policy_identity_rejects_behavior_coherent_replacements(self) -> None:
        rule = self.contract.rule("Water line/label/Default")
        layout = dict(rule.layout_values)
        layout["text-max-width"] = styles.FixedNumber(9_000)
        paint = dict(rule.paint_values)
        paint["text-color"] = styles.ColorValue(0xFF000000)
        variants = (
            {"min_zoom_centi": rule.min_zoom_centi + 1},
            {"max_zoom_centi": rule.max_zoom_centi - 1},
            {
                "compiled_filter": styles.compile_filter(
                    ["==", "_symbol", 0]
                )
            },
            {"layout_values": tuple(sorted(layout.items()))},
            {"paint_values": tuple(sorted(paint.items()))},
            {"fade_in_centi": 1},
            {"draw_order": rule.draw_order + 1},
            {
                "retained_property_names": tuple(
                    sorted((*rule.retained_property_names, "_name_en"))
                )
            },
            {
                "label_style": replace(
                    rule.label_style, collision_group=2
                )
            },
        )
        for kwargs in variants:
            with self.subTest(fields=tuple(kwargs)):
                with self.assertRaisesRegex(
                    styles.StyleContractError,
                    "style-policy SHA-256 does not bind compiled rule",
                ):
                    replace(rule, **kwargs)

    def test_dynamic_label_style_resolves_at_current_zoom(self) -> None:
        self.assertTrue(hasattr(styles, "resolve_label_style"))
        city = self.contract.rule("City large scale/town small")
        at_ten = styles.resolve_label_style(
            city, current_centizoom=1000, properties={}
        )
        at_seventeen = styles.resolve_label_style(
            city, current_centizoom=1700, properties={}
        )
        self.assertEqual(at_ten.text_size_milli, 10_000)
        self.assertEqual(at_seventeen.text_size_milli, 20_000)

        shield = self.contract.rule("Road/label/Shield blue white")
        at_first_stop = styles.resolve_label_style(
            shield, current_centizoom=1550, properties={}
        )
        at_last_stop = styles.resolve_label_style(
            shield, current_centizoom=1800, properties={}
        )
        self.assertEqual(at_first_stop.repeat_distance_px, 250)
        self.assertEqual(at_last_stop.repeat_distance_px, 500)
        self.assertIsInstance(shield.value("symbol-spacing"), styles.StyleFunction)

    def test_line_candidate_uses_renderer_policy_not_sampled_source_zoom_scalars(self) -> None:
        shield = self.contract.rule("Road/label/Shield blue white")
        source_at_fifteen = styles.resolve_label_style(
            shield, current_centizoom=1550, properties={}
        )
        source_at_eighteen = styles.resolve_label_style(
            shield, current_centizoom=1800, properties={}
        )
        self.assertEqual(source_at_fifteen.repeat_distance_px, 250)
        self.assertEqual(source_at_eighteen.repeat_distance_px, 500)
        candidate = self.contract.line_label_candidate(
            styles.SourcePathOccurrence(
                source_layer="Road/label",
                source_zoom=15,
                tile_x=1,
                tile_y=2,
                feature_id=3,
                duplicate_ordinal=0,
                path_sha256="13" * 32,
            ),
            {"_label_class": 7, "Viz": 0, "_name": "Route 7"},
        )
        self.assertIsNotNone(candidate)
        assert candidate is not None
        self.assertEqual(
            candidate.repeat_distance_px, LINE_LABEL_REPEAT_SPACING_PX
        )
        self.assertEqual(
            candidate.max_angle_centi_degrees,
            MAX_LINE_LABEL_BEND_CENTI_DEGREES,
        )
        catalog_rule = next(
            item
            for item in self.contract.catalog_document["rules"]
            if item["styleLayerId"] == shield.style_layer_id
        )
        catalog_spacing = catalog_rule["layout"]["symbol-spacing"]
        self.assertEqual(catalog_spacing, shield.value("symbol-spacing").document())
        self.assertEqual(catalog_spacing["functionType"], "exponential")

    def test_real_shield_candidates_never_sample_dynamic_provider_spacing(self) -> None:
        shield = self.contract.rule("Road/label/Shield blue white")
        self.assertIsInstance(shield.value("symbol-spacing"), styles.StyleFunction)
        for source_zoom in (15, 16, 17, 18):
            with self.subTest(source_zoom=source_zoom):
                bound = 1 << source_zoom
                candidate = self.contract.line_label_candidate(
                    styles.SourcePathOccurrence(
                        source_layer="Road/label",
                        source_zoom=source_zoom,
                        tile_x=min(1, bound - 1),
                        tile_y=min(2, bound - 1),
                        feature_id=source_zoom,
                        duplicate_ordinal=0,
                        path_sha256=f"{source_zoom:02x}" * 32,
                    ),
                    {"_label_class": 7, "Viz": 0, "_name": "Route 7"},
                )
                self.assertIsNotNone(candidate)
                assert candidate is not None
                self.assertEqual(
                    candidate.repeat_distance_px, LINE_LABEL_REPEAT_SPACING_PX
                )
                self.assertEqual(
                    candidate.max_angle_centi_degrees,
                    MAX_LINE_LABEL_BEND_CENTI_DEGREES,
                )
                self.assertEqual(
                    candidate.render_style_token_id,
                    policy.RenderStyleToken.TRANSPORT_LABEL_V1.value,
                )

    def test_direct_candidates_cover_reachable_intervals_without_source_zoom_sampling(
        self,
    ) -> None:
        first = _water_label_layer(
            id="Road/label/Rectangle green white",
            **{"source-layer": "Road/label"},
            minzoom=0,
            maxzoom=17,
        )
        second = _water_label_layer(
            id="Road/label/Rectangle red white",
            **{"source-layer": "Road/label"},
            minzoom=18,
            maxzoom=24,
        )
        for layer in (first, second):
            layer["layout"] = dict(
                layer["layout"],
                **{
                    "text-field": {
                        "type": "interval",
                        "stops": [[0, "{_name}"], [18, "{_name_global}"]],
                    }
                },
            )
        raw = _style_bytes([first, second])
        contract = styles.compile_style_bytes(
            raw,
            expected_sha256=hashlib.sha256(raw).hexdigest(),
            require_pinned_inventory=False,
        )
        low_zoom_candidates = contract.line_label_candidates(
            styles.SourcePathOccurrence(
                source_layer="Road/label",
                source_zoom=12,
                tile_x=1,
                tile_y=2,
                feature_id=5,
                duplicate_ordinal=0,
                path_sha256="15" * 32,
            ),
            {"_name": "Local", "_name_global": "Global"},
        )
        self.assertEqual(
            tuple(
                (
                    candidate.source_style_layer_ids,
                    candidate.text_source_field,
                    candidate.display_text,
                    candidate.display_min_zoom_centi,
                    candidate.display_max_zoom_centi,
                )
                for candidate in low_zoom_candidates
            ),
            (
                ((1,), "_name", "Local", 0, 1700),
                ((2,), "_name_global", "Global", 1800, 2400),
            ),
        )
        with self.assertRaisesRegex(
            styles.StyleContractError,
            "multiple reachable style intervals; use line_label_candidates",
        ):
            contract.line_label_candidate(
                styles.SourcePathOccurrence(
                    source_layer="Road/label",
                    source_zoom=12,
                    tile_x=1,
                    tile_y=2,
                    feature_id=5,
                    duplicate_ordinal=0,
                    path_sha256="15" * 32,
                ),
                {"_name": "Local", "_name_global": "Global"},
            )

        high_zoom_candidates = contract.line_label_candidates(
            styles.SourcePathOccurrence(
                source_layer="Road/label",
                source_zoom=18,
                tile_x=1,
                tile_y=2,
                feature_id=6,
                duplicate_ordinal=0,
                path_sha256="16" * 32,
            ),
            {"_name": "Local", "_name_global": "Global"},
        )
        self.assertEqual(
            tuple(
                (
                    candidate.source_style_layer_ids,
                    candidate.text_source_field,
                    candidate.display_text,
                    candidate.display_min_zoom_centi,
                    candidate.display_max_zoom_centi,
                )
                for candidate in high_zoom_candidates
            ),
            (((2,), "_name_global", "Global", 1800, 2400),),
        )

    def test_fallback_policy_fields_are_validated_and_bound_to_candidate_identity(self) -> None:
        occurrence = styles.SourcePathOccurrence(
            source_layer="Water line large scale",
            source_zoom=8,
            tile_x=120,
            tile_y=140,
            feature_id=0,
            duplicate_ordinal=0,
            path_sha256="ab" * 32,
        )
        base_policy = styles.NamedGeometryFallback(enabled=True)
        self.assertTrue(hasattr(base_policy, "avoid_edges"))
        self.assertEqual(
            base_policy.render_style_token_id,
            policy.FALLBACK_RENDER_STYLE_TOKEN_ID,
        )
        self.assertEqual(
            base_policy.repeat_distance_px,
            styles.LINE_LABEL_REPEAT_SPACING_PX,
        )
        self.assertEqual(
            base_policy.max_angle_centi_degrees,
            styles.MAX_LINE_LABEL_BEND_CENTI_DEGREES,
        )
        base = self.contract.line_label_candidate(
            occurrence, {"_name_en": "Own river"}, fallback_policy=base_policy
        )
        self.assertEqual(base.text_source_field, "_name_en")
        self.assertEqual(base.repeat_distance_px, base_policy.repeat_distance_px)
        self.assertEqual(
            base.max_angle_centi_degrees, base_policy.max_angle_centi_degrees
        )
        self.assertEqual(base.avoid_edges, base_policy.avoid_edges)
        self.assertEqual(base.keep_upright, base_policy.keep_upright)
        self.assertEqual(base.collision_group, base_policy.collision_group)
        self.assertEqual(base.active_band_limit, base_policy.active_band_limit)
        for kwargs in (
            {
                "render_style_token_id": (
                    policy.FALLBACK_RENDER_STYLE_TOKEN_ID + 1
                )
            },
            {"repeat_distance_px": styles.LINE_LABEL_REPEAT_SPACING_PX - 1},
            {
                "max_angle_centi_degrees": (
                    styles.MAX_LINE_LABEL_BEND_CENTI_DEGREES - 1
                )
            },
        ):
            with self.subTest(exact_presentation_override=kwargs):
                with self.assertRaisesRegex(
                    styles.StyleContractError,
                    "exact Flight Alert fallback presentation contract",
                ):
                    replace(base_policy, **kwargs)
        variants = (
            replace(base_policy, avoid_edges=False),
            replace(base_policy, keep_upright=False),
            replace(base_policy, collision_group=2),
            replace(base_policy, active_band_limit=3),
        )
        for variant in variants:
            with self.subTest(variant=variant):
                candidate = self.contract.line_label_candidate(
                    occurrence,
                    {"_name_en": "Own river"},
                    fallback_policy=variant,
                )
                self.assertNotEqual(candidate.candidate_sha256, base.candidate_sha256)
                self.assertNotEqual(candidate.style_policy_sha256, base.style_policy_sha256)
        for kwargs in (
            {"repeat_distance_px": 0},
            {"max_angle_centi_degrees": -1},
            {"collision_group": 0},
            {"active_band_limit": 0},
            {"whole_text": False},
        ):
            with self.subTest(kwargs=kwargs):
                with self.assertRaises(styles.StyleContractError):
                    styles.NamedGeometryFallback(enabled=True, **kwargs)

    def test_evidence_reader_requires_an_independently_trusted_generation_pin(
        self,
    ) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        generation_sha256 = _evidence_generation_sha256(expected)
        parameter = inspect.signature(styles.read_style_evidence).parameters[
            "expected_generation_sha256"
        ]
        self.assertIs(parameter.default, inspect.Parameter.empty)
        self.assertIs(parameter.kind, inspect.Parameter.KEYWORD_ONLY)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            with self.assertRaises(TypeError):
                styles.read_style_evidence(output)
            self.assertEqual(
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256=generation_sha256,
                ),
                expected,
            )
            with self.assertRaisesRegex(
                styles.StyleContractError, "trusted generation"
            ):
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256="00" * 32,
                )

    def test_recomputed_generation_rejects_mixed_audit_catalog_and_manifest(
        self,
    ) -> None:
        first_raw = _style_bytes([_water_label_layer()])
        second_layer = _water_label_layer()
        second_layer["paint"] = {"text-color": "#000000"}
        second_raw = _style_bytes([second_layer])
        first_contract = styles.compile_style_bytes(
            first_raw,
            expected_sha256=hashlib.sha256(first_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        second_contract = styles.compile_style_bytes(
            second_raw,
            expected_sha256=hashlib.sha256(second_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        mixed = dict(styles.style_evidence_bytes(second_contract))
        mixed["audit.json"] = styles.style_evidence_bytes(first_contract)["audit.json"]
        manifest = json.loads(mixed["manifest.json"])
        manifest["auditByteLength"] = len(mixed["audit.json"])
        manifest["auditSha256"] = hashlib.sha256(mixed["audit.json"]).hexdigest()
        mixed["manifest.json"] = styles._canonical_json_bytes(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(second_contract, output)
            generation_sha256 = _install_recomputed_evidence_generation(
                output, mixed
            )
            with self.assertRaisesRegex(
                styles.StyleContractError, "evidence semantic cross-link"
            ):
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256=generation_sha256,
                )

    def test_recomputed_generation_rejects_schema_length_and_hash_drift(self) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        variants: list[tuple[str, dict[str, bytes]]] = []
        for label, mutation in (
            (
                "schema",
                lambda manifest: manifest.__setitem__("schema", "unknown"),
            ),
            (
                "length",
                lambda manifest: manifest.__setitem__(
                    "auditByteLength", manifest["auditByteLength"] + 1
                ),
            ),
            (
                "hash",
                lambda manifest: manifest.__setitem__("auditSha256", "00" * 32),
            ),
        ):
            files = dict(expected)
            manifest = json.loads(files["manifest.json"])
            mutation(manifest)
            files["manifest.json"] = styles._canonical_json_bytes(manifest)
            variants.append((label, files))

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            for label, files in variants:
                with self.subTest(drift=label):
                    output = parent / label
                    styles.write_style_evidence(self.contract, output)
                    generation_sha256 = _install_recomputed_evidence_generation(
                        output, files
                    )
                    with self.assertRaises(styles.StyleContractError):
                        styles.read_style_evidence(
                            output,
                            expected_generation_sha256=generation_sha256,
                        )

    def test_recomputed_generation_rejects_rule_policy_cross_link_drift(
        self,
    ) -> None:
        files = dict(styles.style_evidence_bytes(self.contract))
        catalog = json.loads(files["catalog.json"])
        catalog["rules"][0]["drawOrder"] += 1
        files["catalog.json"] = styles._canonical_json_bytes(catalog)
        manifest = json.loads(files["manifest.json"])
        manifest["catalogByteLength"] = len(files["catalog.json"])
        manifest["catalogSha256"] = hashlib.sha256(
            files["catalog.json"]
        ).hexdigest()
        files["manifest.json"] = styles._canonical_json_bytes(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            generation_sha256 = _install_recomputed_evidence_generation(
                output, files
            )
            with self.assertRaisesRegex(
                styles.StyleContractError, "rule policy identity"
            ):
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256=generation_sha256,
                )

    def test_recomputed_generation_rejects_coherent_rule_semantic_lies(
        self,
    ) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        variants: list[tuple[str, dict[str, bytes]]] = []
        for label in ("classification", "source-style-id"):
            files = dict(expected)
            audit = json.loads(files["audit.json"])
            catalog = json.loads(files["catalog.json"])
            rule = catalog["rules"][0]
            entry = next(
                item
                for item in audit["entries"]
                if item["styleLayerId"] == rule["styleLayerId"]
            )
            if label == "classification":
                rule["semanticSubtype"] = 999_999
                rule["renderStyleTokenIds"] = [999_999]
                entry["semanticSubtype"] = 999_999
                entry["renderStyleTokenIds"] = [999_999]
            else:
                rule["sourceStyleLayerIds"] = [999_999]
                entry["sourceStyleLayerIds"] = [999_999]
            rule["stylePolicySha256"] = styles._catalog_rule_policy_sha256(
                rule
            )
            files["audit.json"] = styles._canonical_json_bytes(audit)
            files["catalog.json"] = styles._canonical_json_bytes(catalog)
            manifest = json.loads(files["manifest.json"])
            for filename, length_name, hash_name in (
                ("audit.json", "auditByteLength", "auditSha256"),
                ("catalog.json", "catalogByteLength", "catalogSha256"),
            ):
                manifest[length_name] = len(files[filename])
                manifest[hash_name] = hashlib.sha256(files[filename]).hexdigest()
            files["manifest.json"] = styles._canonical_json_bytes(manifest)
            variants.append((label, files))

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            for label, files in variants:
                with self.subTest(lie=label):
                    output = parent / label
                    styles.write_style_evidence(self.contract, output)
                    generation_sha256 = _install_recomputed_evidence_generation(
                        output, files
                    )
                    with self.assertRaisesRegex(
                        styles.StyleContractError,
                        "(classifier semantics|source style identity)",
                    ):
                        styles.read_style_evidence(
                            output,
                            expected_generation_sha256=generation_sha256,
                        )

    def test_recomputed_generation_rejects_malformed_nested_rule_schema(
        self,
    ) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        line_rule = lambda rule: rule["featureKind"] == FeatureKind.LINE.value
        label_rule = lambda rule: rule["featureKind"] == FeatureKind.LABEL.value

        def has_style_function(rule):
            return any(
                "baseMilli" in value
                for values in (rule["layout"], rule["paint"])
                for value in values.values()
            )

        def set_huge_style_function_base(rule):
            value = next(
                value
                for values in (rule["layout"], rule["paint"])
                for value in values.values()
                if "baseMilli" in value
            )
            value["baseMilli"] = 1 << 100

        mutations = {
            "unknown-layout-field": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "layout",
                    {"made-up-field": {"kind": "string", "value": "made-up"}},
                ),
            ),
            "unknown-layout-value-kind": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "layout",
                    {"line-join": {"kind": "unknown-value"}},
                ),
            ),
            "wrong-line-style-shape": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "lineStyle", "not-a-line-style-object"
                ),
            ),
            "wrong-style-applicability": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "areaStyle",
                    {
                        "rendererDrawMode": "polygon_outline",
                        "rendersProviderFill": False,
                        "sourceFillEvidenceRetained": True,
                        "sourceStyleType": "fill",
                    },
                ),
            ),
            "empty-zoom-interval": (
                line_rule,
                lambda rule: (
                    rule.__setitem__("minZoomCentiInclusive", 2400),
                    rule.__setitem__("maxZoomCentiExclusive", 2400),
                ),
            ),
            "fade-above-centizoom-domain": (
                line_rule,
                lambda rule: rule.__setitem__("fadeOutCenti", 10_001),
            ),
            "nonzero-line-fade": (
                line_rule,
                lambda rule: rule.__setitem__("fadeOutCenti", 1),
            ),
            "retained-property-wrong-type": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "retainedPropertyNames",
                    [*rule["retainedPropertyNames"], 7],
                ),
            ),
            "retained-property-omitted": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "retainedPropertyNames",
                    [
                        name
                        for name in rule["retainedPropertyNames"]
                        if name != "Alt_ID"
                    ],
                ),
            ),
            "derived-line-role-differs": (
                lambda rule: line_rule(rule)
                and rule["lineStyle"]["strokeRole"] == "single",
                lambda rule: rule["lineStyle"].__setitem__(
                    "strokeRole", "casing"
                ),
            ),
            "derived-label-style-differs": (
                label_rule,
                lambda rule: rule["labelStyle"].__setitem__(
                    "collisionGroup", 2
                ),
            ),
            "fallback-field-differs": (
                lambda rule: rule["fallbackTextSourceField"] == "_name_en",
                lambda rule: rule.__setitem__("fallbackTextSourceField", None),
            ),
            "draw-order-differs": (
                line_rule,
                lambda rule: rule.__setitem__(
                    "drawOrder", rule["styleOrder"] + 1
                ),
            ),
            "style-function-base-overflows": (
                has_style_function,
                set_huge_style_function_base,
            ),
        }
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            for label, (predicate, mutate) in mutations.items():
                with self.subTest(mutation=label):
                    files = dict(expected)
                    catalog = json.loads(files["catalog.json"])
                    rule = next(
                        item
                        for item in catalog["rules"]
                        if predicate(item)
                    )
                    mutate(rule)
                    rule["stylePolicySha256"] = (
                        styles._catalog_rule_policy_sha256(rule)
                    )
                    files["catalog.json"] = styles._canonical_json_bytes(catalog)
                    manifest = json.loads(files["manifest.json"])
                    manifest["catalogByteLength"] = len(files["catalog.json"])
                    manifest["catalogSha256"] = hashlib.sha256(
                        files["catalog.json"]
                    ).hexdigest()
                    files["manifest.json"] = styles._canonical_json_bytes(manifest)
                    output = parent / label
                    styles.write_style_evidence(self.contract, output)
                    generation_sha256 = _install_recomputed_evidence_generation(
                        output, files
                    )
                    with self.assertRaisesRegex(
                        styles.StyleContractError, "catalog rule schema"
                    ):
                        styles.read_style_evidence(
                            output,
                            expected_generation_sha256=generation_sha256,
                        )

    def test_recomputed_generation_rejects_catalog_type_count_lie(self) -> None:
        files = dict(styles.style_evidence_bytes(self.contract))
        catalog = json.loads(files["catalog.json"])
        catalog["styleLayerTypeCounts"]["fill"] += 1
        catalog["styleLayerTypeCounts"]["symbol"] -= 1
        files["catalog.json"] = styles._canonical_json_bytes(catalog)
        manifest = json.loads(files["manifest.json"])
        manifest["catalogByteLength"] = len(files["catalog.json"])
        manifest["catalogSha256"] = hashlib.sha256(
            files["catalog.json"]
        ).hexdigest()
        files["manifest.json"] = styles._canonical_json_bytes(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            generation_sha256 = _install_recomputed_evidence_generation(
                output, files
            )
            with self.assertRaisesRegex(
                styles.StyleContractError, "layer type counts"
            ):
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256=generation_sha256,
                )

    def test_reader_ignores_legacy_flat_evidence_files(self) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            for filename in expected:
                (output / filename).write_bytes(b"legacy-untrusted")
            self.assertEqual(
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256=_evidence_generation_sha256(
                        expected
                    ),
                ),
                expected,
            )

    def test_writer_lock_is_released_by_process_death(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            script = (
                "import os,sys\n"
                "from pathlib import Path\n"
                "from tools.experiment8 import style_contract as styles\n"
                "styles._acquire_style_evidence_writer_lock(Path(sys.argv[1]))\n"
                "os._exit(0)\n"
            )
            completed = subprocess.run(
                [sys.executable, "-c", script, str(output)],
                cwd=Path.cwd(),
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            try:
                styles.write_style_evidence(self.contract, output)
            except styles.StyleContractError as error:
                self.fail(f"process-death lock remained wedged: {error}")

    @unittest.skipUnless(os.name == "nt", "junction semantics are Windows-specific")
    def test_writer_rejects_junction_alias_of_existing_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            alias = parent / "style-contract-alias"
            styles.write_style_evidence(self.contract, output)
            completed = subprocess.run(
                [
                    "cmd.exe",
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(alias),
                    str(output),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            try:
                with self.assertRaisesRegex(
                    styles.StyleContractError, "reparse"
                ):
                    styles.write_style_evidence(self.contract, alias)
            finally:
                os.rmdir(alias)

    @unittest.skipUnless(os.name == "nt", "junction semantics are Windows-specific")
    def test_writer_does_not_create_missing_directories_through_junction(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            real_parent = parent / "real"
            real_parent.mkdir()
            alias = parent / "alias"
            completed = subprocess.run(
                [
                    "cmd.exe",
                    "/d",
                    "/c",
                    "mklink",
                    "/J",
                    str(alias),
                    str(real_parent),
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stderr)
            try:
                with self.assertRaisesRegex(
                    styles.StyleContractError, "reparse"
                ):
                    styles.write_style_evidence(
                        self.contract,
                        alias / "must-not-create" / "style-contract",
                    )
                self.assertFalse((real_parent / "must-not-create").exists())
            finally:
                os.rmdir(alias)

    def test_writer_requires_preexisting_trusted_parent_without_side_effects(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            missing_parent = parent / "missing-parent"
            output = missing_parent / "style-contract"
            with self.assertRaisesRegex(
                styles.StyleContractError, "pre-existing trusted parent"
            ):
                styles.write_style_evidence(self.contract, output)
            self.assertFalse(missing_parent.exists())

    def test_writer_rejects_lock_symlink_before_touching_target(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            output.mkdir()
            target = parent / "must-remain-empty.txt"
            target.write_bytes(b"")
            try:
                os.symlink(target, output / ".writer.lock")
            except OSError as error:
                self.skipTest(f"file symlinks are unavailable: {error}")
            with self.assertRaisesRegex(
                styles.StyleContractError, "real regular file"
            ):
                styles.write_style_evidence(self.contract, output)
            self.assertEqual(target.read_bytes(), b"")

    def test_writer_revalidates_lock_identity_before_initializing_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            output.mkdir()
            lock_path = output / ".writer.lock"
            lock_path.write_bytes(b"")
            displaced_lock = output / ".writer.lock.displaced"
            victim = parent / "must-remain-empty.txt"
            victim.write_bytes(b"")
            try:
                probe = parent / "symlink-probe"
                os.symlink(victim, probe)
                probe.unlink()
            except OSError as error:
                self.skipTest(f"file symlinks are unavailable: {error}")
            real_open = styles.os.open
            swapped = False

            def swap_before_open(path: Path | str, flags: int, *args: object):
                nonlocal swapped
                if Path(path) == lock_path and not swapped:
                    swapped = True
                    lock_path.rename(displaced_lock)
                    os.symlink(victim, lock_path)
                return real_open(path, flags, *args)

            with patch.object(styles.os, "open", side_effect=swap_before_open):
                with self.assertRaisesRegex(
                    styles.StyleContractError, "real regular file"
                ):
                    styles.write_style_evidence(self.contract, output)
            self.assertTrue(swapped)
            self.assertEqual(victim.read_bytes(), b"")

    def test_reader_rejects_hardlinked_generation_files(self) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        generation_sha256 = _evidence_generation_sha256(expected)
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            styles.write_style_evidence(self.contract, output)
            catalog = (
                output / "generations" / generation_sha256 / "catalog.json"
            )
            os.link(catalog, parent / "catalog-hardlink.json")
            with self.assertRaisesRegex(
                styles.StyleContractError, "hardlink"
            ):
                styles.read_style_evidence(
                    output,
                    expected_generation_sha256=generation_sha256,
                )

    def test_reader_rechecks_generation_file_set_after_read(self) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        generation_sha256 = _evidence_generation_sha256(expected)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            generation = output / "generations" / generation_sha256
            real_listdir = styles.os.listdir
            generation_reads = 0

            def changing_listdir(path: Path | str):
                nonlocal generation_reads
                names = real_listdir(path)
                if Path(path) == generation:
                    generation_reads += 1
                    if generation_reads == 2:
                        return [*names, "late-added.json"]
                return names

            with patch.object(styles.os, "listdir", side_effect=changing_listdir):
                with self.assertRaisesRegex(
                    styles.StyleContractError, "file set changed"
                ):
                    styles.read_style_evidence(
                        output,
                        expected_generation_sha256=generation_sha256,
                    )
            self.assertEqual(generation_reads, 2)

    def test_generation_and_pointer_commits_use_metadata_barriers(self) -> None:
        self.assertTrue(
            hasattr(styles, "_commit_replace_with_metadata_barrier"),
            "publication needs an explicit durable replace primitive",
        )
        expected = styles.style_evidence_bytes(self.contract)
        generation_sha256 = _evidence_generation_sha256(expected)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            destinations: list[Path] = []
            real_commit = styles._commit_replace_with_metadata_barrier

            def record_commit(source: Path, destination: Path) -> None:
                destinations.append(Path(destination))
                real_commit(Path(source), Path(destination))

            with patch.object(
                styles,
                "_commit_replace_with_metadata_barrier",
                side_effect=record_commit,
            ):
                styles.write_style_evidence(self.contract, output)
            self.assertEqual(
                destinations,
                [
                    output / "generations" / generation_sha256,
                    output / "current.json",
                ],
            )

    def test_generation_pointer_failures_preserve_one_complete_readable_generation(
        self,
    ) -> None:
        first_raw = _style_bytes([_water_label_layer()])
        second_layer = _water_label_layer()
        second_layer["paint"] = {"text-color": "#000000"}
        second_raw = _style_bytes([second_layer])
        first_contract = styles.compile_style_bytes(
            first_raw,
            expected_sha256=hashlib.sha256(first_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        second_contract = styles.compile_style_bytes(
            second_raw,
            expected_sha256=hashlib.sha256(second_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        expected_old = styles.style_evidence_bytes(first_contract)
        expected_new = styles.style_evidence_bytes(second_contract)
        phases = (
            ("before_generation_readback", expected_old),
            ("before_generation_publish", expected_old),
            ("after_generation_published", expected_old),
            ("before_pointer_readback", expected_old),
            ("before_current_replace", expected_old),
            ("after_current_replaced", expected_new),
            ("after_current_readback", expected_new),
        )

        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            for index, (failed_phase, expected_after_failure) in enumerate(phases):
                with self.subTest(phase=failed_phase):
                    output = parent / f"style-contract-{index}"
                    styles.write_style_evidence(first_contract, output)
                    observed: list[dict[str, bytes]] = []

                    def fail_at_boundary(phase: str, path: Path) -> None:
                        del path
                        if phase == failed_phase:
                            observed.append(
                                _read_pinned_style_evidence(
                                    output, expected_after_failure
                                )
                            )
                            raise RuntimeError(f"injected {phase} failure")

                    with self.assertRaisesRegex(
                        RuntimeError, f"injected {failed_phase} failure"
                    ):
                        styles.write_style_evidence(
                            second_contract,
                            output,
                            publication_hook=fail_at_boundary,
                        )
                    self.assertEqual(observed, [expected_after_failure])
                    self.assertEqual(
                        _read_pinned_style_evidence(
                            output, expected_after_failure
                        ),
                        expected_after_failure,
                    )

    def test_current_pointer_and_directory_bind_exact_generation_identity(self) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            pointer = json.loads((output / "current.json").read_bytes())
            self.assertEqual(
                pointer,
                {
                    "generationSha256": styles._style_evidence_generation_sha256(
                        expected
                    ),
                    "schema": "flight-alert-exp8-style-evidence-current-v1",
                },
            )
            generation = output / "generations" / pointer["generationSha256"]
            self.assertEqual(
                {item.name: item.read_bytes() for item in generation.iterdir()},
                expected,
            )
            self.assertEqual(
                _read_pinned_style_evidence(output, expected), expected
            )
            (generation / "catalog.json").write_bytes(b"tampered")
            with self.assertRaisesRegex(
                styles.StyleContractError, "generation identity"
            ):
                _read_pinned_style_evidence(output, expected)

    def test_failure_before_current_replace_preserves_previous_complete_set(self) -> None:
        self.assertIn(
            "publication_hook",
            inspect.signature(styles.write_style_evidence).parameters,
        )
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            previous = _read_pinned_style_evidence(
                output, styles.style_evidence_bytes(self.contract)
            )
            previous_pointer = (output / "current.json").read_bytes()

            def fail_before_current_replace(phase: str, path: Path) -> None:
                del path
                if phase == "before_current_replace":
                    raise RuntimeError("injected publication failure")

            with self.assertRaisesRegex(RuntimeError, "injected publication failure"):
                styles.write_style_evidence(
                    self.contract,
                    output,
                    publication_hook=fail_before_current_replace,
                )
            self.assertEqual(
                _read_pinned_style_evidence(output, previous), previous
            )
            self.assertEqual((output / "current.json").read_bytes(), previous_pointer)
            self.assertEqual(
                sorted(path.name for path in Path(temporary).iterdir()),
                ["style-contract"],
            )

    def test_generation_readback_tamper_never_replaces_previous_pointer(self) -> None:
        self.assertIn(
            "publication_hook",
            inspect.signature(styles.write_style_evidence).parameters,
        )
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(self.contract, output)
            previous = _read_pinned_style_evidence(
                output, styles.style_evidence_bytes(self.contract)
            )
            previous_pointer = (output / "current.json").read_bytes()

            def corrupt_before_readback(phase: str, path: Path) -> None:
                if phase == "before_generation_readback":
                    (path / "catalog.json").write_bytes(b"corrupt")

            with self.assertRaisesRegex(styles.StyleContractError, "readback"):
                styles.write_style_evidence(
                    self.contract,
                    output,
                    publication_hook=corrupt_before_readback,
                )
            self.assertEqual(
                _read_pinned_style_evidence(output, previous), previous
            )
            self.assertEqual((output / "current.json").read_bytes(), previous_pointer)
            self.assertEqual(tuple((output / "generations").glob("*.staging")), ())
            self.assertEqual(
                sorted(path.name for path in Path(temporary).iterdir()),
                ["style-contract"],
            )

    def test_publication_is_single_writer_and_cleans_only_owned_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            styles.write_style_evidence(self.contract, output)
            previous = _read_pinned_style_evidence(
                output, styles.style_evidence_bytes(self.contract)
            )
            unrelated_inside = output / "unrelated.txt"
            unrelated_inside.write_text("preserve inside", encoding="utf-8")
            unrelated_sibling = parent / "unrelated.txt"
            unrelated_sibling.write_text("preserve sibling", encoding="utf-8")
            writer_paused = threading.Event()
            release_writer = threading.Event()
            errors: list[BaseException] = []

            def pause_before_readback(phase: str, path: Path) -> None:
                del path
                if phase == "before_generation_readback":
                    writer_paused.set()
                    if not release_writer.wait(timeout=5):
                        raise RuntimeError("test writer release timed out")

            def first_writer() -> None:
                try:
                    styles.write_style_evidence(
                        self.contract,
                        output,
                        publication_hook=pause_before_readback,
                    )
                except BaseException as error:
                    errors.append(error)

            thread = threading.Thread(target=first_writer)
            thread.start()
            self.assertTrue(writer_paused.wait(timeout=5))
            try:
                with self.assertRaisesRegex(
                    styles.StyleContractError, "another writer owns"
                ):
                    styles.write_style_evidence(self.contract, output)
                self.assertEqual(
                    _read_pinned_style_evidence(output, previous), previous
                )
            finally:
                release_writer.set()
                thread.join(timeout=5)
            self.assertFalse(thread.is_alive())
            self.assertEqual(errors, [])
            self.assertEqual(unrelated_inside.read_text(encoding="utf-8"), "preserve inside")
            self.assertEqual(unrelated_sibling.read_text(encoding="utf-8"), "preserve sibling")
            self.assertFalse((parent / ".style-contract.writer.lock").exists())
            self.assertTrue((output / ".writer.lock").is_file())
            self.assertEqual(os.stat(output / ".writer.lock").st_nlink, 1)
            self.assertEqual(
                tuple((output / "generations").glob(".generation.*.staging")), ()
            )

    def test_failure_after_current_replace_keeps_verified_new_generation(self) -> None:
        first_raw = _style_bytes([_water_label_layer()])
        second_layer = _water_label_layer()
        second_layer["paint"] = {"text-color": "#000000"}
        second_raw = _style_bytes([second_layer])
        first_contract = styles.compile_style_bytes(
            first_raw,
            expected_sha256=hashlib.sha256(first_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        second_contract = styles.compile_style_bytes(
            second_raw,
            expected_sha256=hashlib.sha256(second_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            styles.write_style_evidence(first_contract, output)
            expected_new = styles.style_evidence_bytes(second_contract)

            def fail_after_publish(phase: str, path: Path) -> None:
                del path
                if phase == "after_current_replaced":
                    raise RuntimeError("injected post-publish failure")

            with self.assertRaisesRegex(
                RuntimeError, "injected post-publish failure"
            ):
                styles.write_style_evidence(
                    second_contract,
                    output,
                    publication_hook=fail_after_publish,
                )
            try:
                actual_new = _read_pinned_style_evidence(output, expected_new)
            except styles.StyleContractError as error:
                self.fail(
                    "post-rename failure deleted the referenced generation: "
                    f"{error}"
                )
            self.assertEqual(actual_new, expected_new)
            self.assertEqual(
                sorted(path.name for path in parent.iterdir()),
                ["style-contract"],
            )

    def test_failed_first_publication_has_no_partial_current_generation(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            unrelated = parent / "unrelated.txt"
            unrelated.write_text("preserve me", encoding="utf-8")

            def fail_before_commit(phase: str, path: Path) -> None:
                del path
                if phase == "before_current_replace":
                    raise RuntimeError("force first-publication failure")

            with self.assertRaisesRegex(
                RuntimeError, "force first-publication failure"
            ):
                styles.write_style_evidence(
                    self.contract,
                    output,
                    publication_hook=fail_before_commit,
                )
            with self.assertRaisesRegex(styles.StyleContractError, "current pointer"):
                _read_pinned_style_evidence(
                    output, styles.style_evidence_bytes(self.contract)
                )
            self.assertFalse((output / "current.json").exists())
            self.assertEqual(tuple((output / "generations").iterdir()), ())
            self.assertEqual(unrelated.read_text(encoding="utf-8"), "preserve me")
            self.assertFalse((parent / ".style-contract.writer.lock").exists())

    def test_success_retains_previous_immutable_generation_for_in_flight_readers(
        self,
    ) -> None:
        first_raw = _style_bytes([_water_label_layer()])
        second_layer = _water_label_layer()
        second_layer["paint"] = {"text-color": "#000000"}
        second_raw = _style_bytes([second_layer])
        first_contract = styles.compile_style_bytes(
            first_raw,
            expected_sha256=hashlib.sha256(first_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        second_contract = styles.compile_style_bytes(
            second_raw,
            expected_sha256=hashlib.sha256(second_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            styles.write_style_evidence(first_contract, output)
            expected_old = styles.style_evidence_bytes(first_contract)
            expected_new = styles.style_evidence_bytes(second_contract)
            old_generation = (
                output
                / "generations"
                / styles._style_evidence_generation_sha256(expected_old)
            )
            styles.write_style_evidence(second_contract, output)
            self.assertEqual(
                _read_pinned_style_evidence(output, expected_new), expected_new
            )
            self.assertEqual(
                {item.name: item.read_bytes() for item in old_generation.iterdir()},
                expected_old,
            )

    def test_current_pointer_replace_failure_keeps_old_generation_readable(self) -> None:
        first_raw = _style_bytes([_water_label_layer()])
        second_layer = _water_label_layer()
        second_layer["paint"] = {"text-color": "#000000"}
        second_raw = _style_bytes([second_layer])
        first_contract = styles.compile_style_bytes(
            first_raw,
            expected_sha256=hashlib.sha256(first_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        second_contract = styles.compile_style_bytes(
            second_raw,
            expected_sha256=hashlib.sha256(second_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            output = parent / "style-contract"
            styles.write_style_evidence(first_contract, output)
            expected_old = styles.style_evidence_bytes(first_contract)
            expected_new = styles.style_evidence_bytes(second_contract)
            old_pointer = (output / "current.json").read_bytes()
            real_commit = styles._commit_replace_with_metadata_barrier

            def fail_current_replace(source: Path, destination: Path) -> None:
                if (
                    Path(destination) == output / "current.json"
                    and str(source).endswith(".staging")
                ):
                    raise OSError("injected current pointer replace failure")
                real_commit(Path(source), Path(destination))

            with patch.object(
                styles,
                "_commit_replace_with_metadata_barrier",
                side_effect=fail_current_replace,
            ):
                with self.assertRaisesRegex(
                    OSError, "injected current pointer replace failure"
                ):
                    styles.write_style_evidence(second_contract, output)
            self.assertEqual(
                _read_pinned_style_evidence(output, expected_old), expected_old
            )
            self.assertEqual((output / "current.json").read_bytes(), old_pointer)
            self.assertFalse(
                (
                    output
                    / "generations"
                    / styles._style_evidence_generation_sha256(expected_new)
                ).exists()
            )

    def test_post_rename_barrier_failure_keeps_referenced_generation_readable(
        self,
    ) -> None:
        first_raw = _style_bytes([_water_label_layer()])
        second_layer = _water_label_layer()
        second_layer["paint"] = {"text-color": "#000000"}
        second_raw = _style_bytes([second_layer])
        first_contract = styles.compile_style_bytes(
            first_raw,
            expected_sha256=hashlib.sha256(first_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        second_contract = styles.compile_style_bytes(
            second_raw,
            expected_sha256=hashlib.sha256(second_raw).hexdigest(),
            require_pinned_inventory=False,
        )
        expected_old = styles.style_evidence_bytes(first_contract)
        expected_new = styles.style_evidence_bytes(second_contract)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "style-contract"
            styles.write_style_evidence(first_contract, output)
            real_commit = styles._commit_replace_with_metadata_barrier

            def fail_after_pointer_rename(
                source: Path, destination: Path
            ) -> None:
                if Path(destination) == output / "current.json":
                    os.replace(source, destination)
                    raise OSError("injected post-rename metadata barrier failure")
                real_commit(Path(source), Path(destination))

            with patch.object(
                styles,
                "_commit_replace_with_metadata_barrier",
                side_effect=fail_after_pointer_rename,
            ):
                with self.assertRaisesRegex(
                    OSError, "post-rename metadata barrier failure"
                ):
                    styles.write_style_evidence(second_contract, output)
            try:
                actual_new = _read_pinned_style_evidence(output, expected_new)
            except styles.StyleContractError as error:
                self.fail(
                    "post-rename failure deleted the referenced generation: "
                    f"{error}"
                )
            self.assertEqual(actual_new, expected_new)
            old_generation = (
                output
                / "generations"
                / _evidence_generation_sha256(expected_old)
            )
            self.assertTrue(old_generation.is_dir())

    def test_publication_documents_file_pointer_commit_without_directory_atomicity_claim(
        self,
    ) -> None:
        documentation = inspect.getdoc(styles.write_style_evidence)
        self.assertIsNotNone(documentation)
        assert documentation is not None
        self.assertIn("durable generation and pointer commits", documentation)
        self.assertIn("independently trusted generation SHA-256", documentation)
        self.assertIn(
            "Generation-directory replacement is not an atomicity premise",
            documentation,
        )
        self.assertIn("immediate parent", documentation)
        self.assertRegex(
            documentation, r"prevents concurrent namespace\s+replacement"
        )

    def test_task3_handoff_freezes_pinned_generation_and_plural_candidates(
        self,
    ) -> None:
        project_root = Path(__file__).resolve().parents[3]
        plan = (
            project_root
            / "docs"
            / "superpowers"
            / "plans"
            / "2026-07-11-experiment-8-semantic-codec-pilot.md"
        ).read_text(encoding="utf-8")
        brief = (
            project_root
            / ".superpowers"
            / "sdd"
            / "experiment8-plan2-task-3-brief.md"
        ).read_text(encoding="utf-8")
        for label, document in (("plan", plan), ("brief", brief)):
            with self.subTest(document=label):
                self.assertIn("expected_style_generation_sha256", document)
                self.assertIn(
                    "expected_generation_sha256=expected_style_generation_sha256",
                    document,
                )
                self.assertIn("before worker", document)
                self.assertIn("legacy flat files", document)
                self.assertIn("line_label_candidates", document)
                self.assertIn("line_label_candidate", document)
                self.assertIn("resume", document)
                self.assertRegex(
                    document,
                    r"(?i)(same|exact) (captured )?style[- ]generation",
                )


class LineLabelProvenanceTests(StyleContractTestCase):
    def _occurrence(
        self,
        *,
        source_layer: str = "Water line/label",
        source_zoom: int = 12,
        feature_id: int = 0,
        path_byte: str = "11",
        tile_x: int = 1200,
    ):
        tile_bound = 1 << source_zoom
        return styles.SourcePathOccurrence(
            source_layer=source_layer,
            source_zoom=source_zoom,
            tile_x=min(tile_x, tile_bound - 1),
            tile_y=min(1530, tile_bound - 1),
            feature_id=feature_id,
            duplicate_ordinal=0,
            path_sha256=path_byte * 32,
        )

    def test_direct_z12_candidate_uses_pinned_style_and_own_global_name(self) -> None:
        occurrence = self._occurrence()
        candidate = self.contract.line_label_candidate(
            occurrence, {"_name_global": "Chester River"}
        )
        self.assertEqual(
            candidate.provenance, styles.LabelProvenance.PINNED_STYLE_LINE_LABEL
        )
        self.assertEqual(candidate.text_source_field, "_name_global")
        self.assertEqual(candidate.placement_source_kind, PlacementSourceKind.DIRECT_SOURCE_PATH)
        self.assertEqual(candidate.placement_geometry_sha256, occurrence.path_sha256)
        self.assertEqual(candidate.source_zoom, 12)

    def test_candidate_identity_binds_subtype_kind_and_policy_meaning(self) -> None:
        candidate = self.contract.line_label_candidate(
            self._occurrence(), {"_name_global": "Bound river"}
        )
        self.assertIsNotNone(candidate)
        assert candidate is not None
        self.assertEqual(candidate.layer_group, LayerGroup.WATER)
        self.assertEqual(candidate.feature_kind, FeatureKind.LABEL)
        self.assertEqual(
            candidate.semantic_subtype,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE.value,
        )
        self.assertEqual(candidate.semantic_kind, "label")
        self.assertEqual(
            candidate.semantic_policy_sha256, policy.SEMANTIC_POLICY_SHA256
        )
        self.assertEqual(
            candidate.presentation_policy_sha256, PRESENTATION_POLICY_SHA256
        )
        variants = (
            {"display_text": "Changed river"},
            {"text_source_field": "_name"},
            {"style_policy_sha256": "22" * 32},
            {"render_style_token_id": candidate.render_style_token_id + 1},
            {"layer_group": LayerGroup.PLACES},
            {"semantic_subtype": SemanticSubtype.RIVER.value},
            {"semantic_kind": "mutated_candidate_kind"},
            {"semantic_policy_sha256": "00" * 32},
            {"presentation_policy_sha256": "11" * 32},
            {"source_style_layer_ids": (999,)},
            {"source_layer": "Water line/label changed"},
            {"source_zoom": candidate.source_zoom + 1},
            {"source_tile_x": candidate.source_tile_x + 1},
            {"source_tile_y": candidate.source_tile_y + 1},
            {"source_feature_id": candidate.source_feature_id + 1},
            {"duplicate_ordinal": candidate.duplicate_ordinal + 1},
            {"display_min_zoom_centi": candidate.display_min_zoom_centi + 1},
            {"display_max_zoom_centi": candidate.display_max_zoom_centi - 1},
            {"repeat_distance_px": candidate.repeat_distance_px + 1},
            {
                "max_angle_centi_degrees":
                candidate.max_angle_centi_degrees + 1
            },
            {"avoid_edges": not candidate.avoid_edges},
            {"keep_upright": not candidate.keep_upright},
            {"collision_group": candidate.collision_group + 1},
            {"active_band_limit": candidate.active_band_limit + 1},
        )
        for kwargs in variants:
            with self.subTest(kwargs=kwargs):
                with self.assertRaisesRegex(
                    styles.StyleContractError,
                    "candidate SHA-256 does not bind semantic provenance",
                ):
                    replace(candidate, **kwargs)

    def test_same_name_disconnected_paths_and_zero_pbf_ids_never_join(self) -> None:
        first = self.contract.line_label_candidate(
            self._occurrence(path_byte="11", tile_x=1200), {"_name_global": "Same"}
        )
        second = self.contract.line_label_candidate(
            self._occurrence(path_byte="22", tile_x=1201), {"_name_global": "Same"}
        )
        self.assertEqual(first.source_feature_id, 0)
        self.assertEqual(second.source_feature_id, 0)
        self.assertNotEqual(first.candidate_sha256, second.candidate_sha256)
        self.assertNotEqual(first.placement_geometry_sha256, second.placement_geometry_sha256)

    def test_disabled_fallback_emits_nothing_for_named_z8_geometry(self) -> None:
        occurrence = self._occurrence(
            source_layer="Water line large scale", source_zoom=8, path_byte="33"
        )
        self.assertIsNone(
            self.contract.line_label_candidate(occurrence, {"_name_en": "Named River"})
        )

    def test_empty_fallback_provider_zoom_intersection_emits_no_candidate(self) -> None:
        occurrence = self._occurrence(
            source_layer="Water line large scale", source_zoom=8, path_byte="34"
        )
        candidate = self.contract.line_label_candidate(
            occurrence,
            {"_name_en": "Named River", "_minzoom": "120"},
            fallback_policy=styles.NamedGeometryFallback(enabled=True),
        )
        self.assertIsNone(candidate)

    def test_enabled_fallback_uses_own_name_path_and_distinct_policy_identity(self) -> None:
        direct_occurrence = self._occurrence(path_byte="44")
        direct = self.contract.line_label_candidate(
            direct_occurrence, {"_name_global": "Named River"}
        )
        fallback_occurrence = self._occurrence(
            source_layer="Water line large scale",
            source_zoom=8,
            path_byte="55",
            tile_x=300,
        )
        fallback = self.contract.line_label_candidate(
            fallback_occurrence,
            {"_name_en": "Named River"},
            fallback_policy=styles.NamedGeometryFallback(enabled=True),
        )
        self.assertEqual(fallback.provenance, styles.LabelProvenance.FLIGHT_ALERT_POLICY)
        self.assertEqual(fallback.text_source_field, "_name_en")
        self.assertEqual(fallback.feature_kind, FeatureKind.LABEL)
        self.assertEqual(
            fallback.semantic_subtype,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE.value,
        )
        self.assertEqual(fallback.semantic_kind, "named_geometry_fallback")
        self.assertEqual(fallback.layer_group, LayerGroup.WATER)
        self.assertEqual(
            fallback.render_style_token_id,
            policy.RenderStyleToken.NAMED_GEOMETRY_FALLBACK_V1.value,
        )
        self.assertNotIn(
            fallback.semantic_subtype,
            {item.value for item in policy.MasterOnlyGeometrySubtype},
        )
        self.assertEqual(fallback.placement_geometry_sha256, fallback_occurrence.path_sha256)
        self.assertNotEqual(fallback.placement_geometry_sha256, direct_occurrence.path_sha256)
        self.assertNotEqual(fallback.style_policy_sha256, direct.style_policy_sha256)
        self.assertNotEqual(fallback.render_style_token_id, direct.render_style_token_id)
        self.assertNotEqual(fallback.candidate_sha256, direct.candidate_sha256)

    def test_fallback_cannot_be_replaced_with_another_occurrence_geometry(self) -> None:
        occurrence = self._occurrence(
            source_layer="Water line large scale", source_zoom=8, path_byte="66"
        )
        candidate = self.contract.line_label_candidate(
            occurrence,
            {"_name_en": "Own Path"},
            fallback_policy=styles.NamedGeometryFallback(enabled=True),
        )
        with self.assertRaisesRegex(styles.StyleContractError, "own exact source path"):
            replace(candidate, placement_geometry_sha256="77" * 32)


class AuditAndDeterminismTests(StyleContractTestCase):
    def test_all_916_layers_receive_one_stable_audit_outcome(self) -> None:
        raw_ids = [
            layer["id"]
            for layer in json.loads(PINNED_STYLE.read_text(encoding="utf-8"))["layers"]
        ]
        audited_ids = [entry.style_layer_id for entry in self.contract.audit]
        self.assertEqual(len(audited_ids), 916)
        self.assertEqual(len(set(audited_ids)), 916)
        self.assertEqual(set(audited_ids), set(raw_ids))
        self.assertEqual(self.contract.audit_counts["extraction_failure"], 0)
        self.assertEqual(self.contract.audit_counts["included"], 294)
        self.assertEqual(self.contract.audit_counts["excluded"], 622)
        manifest = json.loads(
            styles.style_evidence_bytes(self.contract)["manifest.json"]
        )
        self.assertEqual(manifest["includedRuleCount"], 294)

    def test_excluded_base_fills_icons_and_unrelated_context_have_stable_reasons(self) -> None:
        self.assertEqual(
            self.contract.audit_entry("Park or farming").reason,
            "satellite_base_owned_fill",
        )
        self.assertEqual(
            self.contract.audit_entry(
                "Road/label/One-way arrow freeway, motorway, highway"
            ).outcome,
            "included",
        )
        self.assertEqual(
            self.contract.audit_entry("Ferry/Rail ferry/symbol").reason,
            "icon_only",
        )
        self.assertEqual(
            self.contract.audit_entry("Place/Food and Drink/Restaurant").reason,
            "not_renderer_contract",
        )
        self.assertEqual(
            self.contract.audit_entry("Water line/label/Default").outcome,
            "included",
        )

    def test_included_rules_cover_every_required_family_and_no_unsupported_expression(self) -> None:
        counts = self.contract.catalog_document["includedRuleCounts"]
        for family in (
            "labels",
            "boundaries",
            "water",
            "public_lands",
            "transportation",
        ):
            self.assertGreater(counts[family], 0)
        self.assertEqual(self.contract.catalog_document["unsupportedIncludedExpressions"], 0)
        self.assertEqual(
            self.contract.catalog_document["supportedFilterOperators"],
            ["!in", "==", "all", "in"],
        )

    def test_source_style_ids_and_render_token_ids_have_separate_catalogs(self) -> None:
        source_ids = set(self.contract.catalog_document["sourceStyleLayerIds"].values())
        token_ids = set(self.contract.catalog_document["renderStyleTokenIds"].values())
        self.assertEqual(len(source_ids), 916)
        self.assertEqual(len(token_ids), len(self.contract.catalog_document["renderStyleTokenIds"]))
        self.assertTrue(source_ids.isdisjoint(token_ids))

    def test_rule_index_is_immutable_exact_ordered_and_bounds_occurrence_candidates(self) -> None:
        index = self.contract.rule_index_by_source_kind
        expected_counts = {
            ("Admin0 forest or park/label", FeatureKind.LABEL): 1,
            ("Admin0 point", FeatureKind.LABEL): 6,
            ("Admin1 area/label", FeatureKind.LABEL): 6,
            ("Admin1 forest or park/label", FeatureKind.LABEL): 1,
            ("Admin2 area/label", FeatureKind.LABEL): 2,
            ("Airport/label", FeatureKind.LABEL): 1,
            ("Boundary line", FeatureKind.LINE): 15,
            ("City large scale", FeatureKind.LABEL): 6,
            ("City small scale", FeatureKind.LABEL): 19,
            ("Coastline", FeatureKind.LINE): 1,
            ("Continent", FeatureKind.LABEL): 1,
            ("Disputed label point", FeatureKind.LABEL): 3,
            ("Exit", FeatureKind.LABEL): 1,
            ("Ferry", FeatureKind.LINE): 3,
            ("Ferry/label", FeatureKind.LABEL): 2,
            ("Freight/label", FeatureKind.LABEL): 1,
            ("Marine area/label", FeatureKind.LABEL): 1,
            ("Marine park/label", FeatureKind.LABEL): 1,
            ("Marine waterbody/label", FeatureKind.LABEL): 5,
            ("Neighborhood", FeatureKind.LABEL): 1,
            ("Openspace or forest/label", FeatureKind.LABEL): 1,
            ("Park or farming/label", FeatureKind.LABEL): 1,
            ("Pedestrian/label", FeatureKind.LABEL): 1,
            ("Port/label", FeatureKind.LABEL): 1,
            ("Railroad", FeatureKind.LINE): 3,
            ("Railroad/label", FeatureKind.LABEL): 1,
            ("Road", FeatureKind.LINE): 21,
            ("Road tunnel", FeatureKind.LINE): 21,
            ("Road tunnel/label", FeatureKind.LABEL): 8,
            ("Road tunnel/label", FeatureKind.LINE): 5,
            ("Road/label", FeatureKind.LABEL): 76,
            ("Road/label", FeatureKind.LINE): 5,
            ("Trail or path", FeatureKind.LINE): 1,
            ("Trail or path/label", FeatureKind.LABEL): 1,
            ("Transportation place", FeatureKind.LABEL): 35,
            ("Transportation/label", FeatureKind.LABEL): 1,
            ("Water area", FeatureKind.POLYGON_OUTLINE): 5,
            ("Water area large scale", FeatureKind.POLYGON_OUTLINE): 2,
            ("Water area large scale/label", FeatureKind.LABEL): 2,
            ("Water area medium scale", FeatureKind.POLYGON_OUTLINE): 2,
            ("Water area medium scale/label", FeatureKind.LABEL): 1,
            ("Water area small scale", FeatureKind.POLYGON_OUTLINE): 1,
            ("Water area small scale/label", FeatureKind.LABEL): 1,
            ("Water area/label", FeatureKind.LABEL): 8,
            ("Water line", FeatureKind.LINE): 3,
            ("Water line large scale", FeatureKind.LINE): 1,
            ("Water line medium scale", FeatureKind.LINE): 1,
            ("Water line small scale", FeatureKind.LINE): 1,
            ("Water line/label", FeatureKind.LABEL): 1,
            ("Water point", FeatureKind.LABEL): 6,
        }
        self.assertEqual(
            {key: len(bucket) for key, bucket in index.items()},
            expected_counts,
        )
        with self.assertRaises(TypeError):
            index[("Water line/label", FeatureKind.LABEL)] = ()
        for key, bucket in index.items():
            with self.subTest(key=key):
                expected = tuple(
                    rule
                    for rule in self.contract.rules
                    if (rule.source_layer, rule.feature_kind) == key
                )
                self.assertEqual(bucket, expected)
                self.assertEqual(
                    tuple(rule.style_order for rule in bucket),
                    tuple(sorted(rule.style_order for rule in bucket)),
                )
        water_label_bucket = self.contract.rules_for(
            "Water line/label", FeatureKind.LABEL
        )
        self.assertEqual(len(water_label_bucket), 1)
        self.assertLess(len(water_label_bucket), len(self.contract.rules))
        self.assertEqual(
            self.contract.rules_for("unknown", FeatureKind.LABEL), ()
        )
        calls: list[tuple[str, FeatureKind]] = []
        original_rules_for = styles.StyleContract.rules_for

        def recording_rules_for(
            contract: styles.StyleContract,
            source_layer: str,
            feature_kind: FeatureKind,
        ):
            calls.append((source_layer, feature_kind))
            return original_rules_for(contract, source_layer, feature_kind)

        with patch.object(styles.StyleContract, "rules_for", recording_rules_for):
            self.contract.stroke_stack(
                "Road", {"_symbol": 0, "Viz": 0}, zoom_centi=1000
            )
            self.contract.line_label_candidate(
                styles.SourcePathOccurrence(
                    source_layer="Water line/label",
                    source_zoom=12,
                    tile_x=1,
                    tile_y=2,
                    feature_id=1,
                    duplicate_ordinal=0,
                    path_sha256="16" * 32,
                ),
                {"_name_global": "Indexed river"},
            )
        self.assertEqual(
            calls,
            [
                ("Road", FeatureKind.LINE),
                ("Water line/label", FeatureKind.LABEL),
            ],
        )

    def test_canonical_audit_catalog_and_manifest_bytes_are_repeatable(self) -> None:
        second = styles.compile_style_contract()
        self.assertEqual(self.contract.audit_bytes, second.audit_bytes)
        self.assertEqual(self.contract.catalog_bytes, second.catalog_bytes)
        first_files = styles.style_evidence_bytes(self.contract)
        second_files = styles.style_evidence_bytes(second)
        self.assertEqual(first_files, second_files)
        self.assertEqual(tuple(first_files), ("audit.json", "catalog.json", "manifest.json"))
        manifest = json.loads(first_files["manifest.json"])
        self.assertEqual(
            policy.semantic_policy_document()["schema"],
            "flight-alert-exp8-semantic-policy-v3",
        )
        self.assertEqual(
            self.contract.catalog_document["schema"],
            "flight-alert-exp8-style-contract-v3",
        )
        self.assertEqual(
            manifest["schema"],
            "flight-alert-exp8-style-evidence-manifest-v3",
        )
        self.assertEqual(manifest["rawStyleSha256"], PINNED_SHA256)
        self.assertEqual(
            manifest["auditSha256"], hashlib.sha256(first_files["audit.json"]).hexdigest()
        )
        self.assertEqual(
            manifest["catalogSha256"],
            hashlib.sha256(first_files["catalog.json"]).hexdigest(),
        )

    def test_contract_rejects_replaced_catalog_audit_and_count_identities(self) -> None:
        catalog = deepcopy(dict(self.contract.catalog_document))
        catalog["includedRuleCounts"]["labels"] += 1
        catalog_bytes = styles._canonical_json_bytes(catalog)

        audit_document = json.loads(self.contract.audit_bytes)
        audit_document["counts"]["included"] -= 1
        audit_counts = dict(self.contract.audit_counts)
        audit_counts["included"] -= 1
        audit_bytes = styles._canonical_json_bytes(audit_document)

        variants = (
            {"catalog_document": catalog, "catalog_bytes": catalog_bytes},
            {"audit_counts": audit_counts, "audit_bytes": audit_bytes},
            {"audit": self.contract.audit[:-1]},
            {"catalog_bytes": self.contract.catalog_bytes + b" "},
            {"audit_bytes": self.contract.audit_bytes + b" "},
        )
        for kwargs in variants:
            with self.subTest(fields=tuple(kwargs)):
                with self.assertRaisesRegex(
                    styles.StyleContractError, ".*identity"
                ):
                    replace(self.contract, **kwargs)

    def test_contract_rejects_coherent_rule_replacement_against_verified_source(self) -> None:
        original = self.contract.rule("Water line/label/Default")
        mutated = copy(original)
        object.__setattr__(
            mutated, "min_zoom_centi", original.min_zoom_centi + 1
        )
        object.__setattr__(
            mutated,
            "style_policy_sha256",
            styles._rule_policy_sha256(
                raw_style_sha256=mutated.raw_style_sha256,
                semantic_policy_sha256=mutated.semantic_policy_sha256,
                style_layer_id=mutated.style_layer_id,
                style_order=mutated.style_order,
                source_layer=mutated.source_layer,
                source_style_layer_ids=mutated.source_style_layer_ids,
                render_style_token_ids=mutated.render_style_token_ids,
                classification=mutated.classification(),
                compiled_filter=mutated.compiled_filter,
                layout_values=mutated.layout_values,
                paint_values=mutated.paint_values,
                min_zoom_centi=mutated.min_zoom_centi,
                max_zoom_centi=mutated.max_zoom_centi,
                fade_in_centi=mutated.fade_in_centi,
                fade_out_centi=mutated.fade_out_centi,
                draw_order=mutated.draw_order,
                semantic_collision_priority=mutated.semantic_collision_priority,
                priority_basis=mutated.priority_basis,
                retained_property_names=mutated.retained_property_names,
                label_style=mutated.label_style,
                line_style=mutated.line_style,
                area_style=mutated.area_style,
                fallback_text_source_field=mutated.fallback_text_source_field,
                inherited_style_layer_ids=mutated.inherited_style_layer_ids,
            ),
        )
        rules = tuple(
            mutated if rule.style_layer_id == mutated.style_layer_id else rule
            for rule in self.contract.rules
        )
        catalog = styles._build_catalog_document(
            raw_style_sha256=self.contract.raw_style_sha256,
            raw_style_length=self.contract.raw_style_length,
            style_version=self.contract.style_version,
            layer_count=self.contract.layer_count,
            layer_type_counts=self.contract.layer_type_counts,
            text_bearing_symbol_count=self.contract.text_bearing_symbol_count,
            rules=rules,
            audit=self.contract.audit,
        )
        with self.assertRaisesRegex(
            styles.StyleContractError, "verified source rule identity"
        ):
            replace(
                self.contract,
                rules=rules,
                rule_index_by_source_kind=styles._build_rule_index(rules),
                catalog_document=MappingProxyType(catalog),
                catalog_bytes=styles._canonical_json_bytes(catalog),
            )

    def test_evidence_generation_rederives_documents_after_nested_mutation(self) -> None:
        contract = styles.compile_style_contract()
        counts = contract.catalog_document["includedRuleCounts"]
        counts["labels"] += 1
        with self.assertRaisesRegex(
            styles.StyleContractError, "contract catalog identity"
        ):
            styles.style_evidence_bytes(contract)

    def test_catalog_identity_comparison_is_json_type_exact(self) -> None:
        contract = styles.compile_style_contract()
        catalog_rule = next(
            item
            for item in contract.catalog_document["rules"]
            if item["styleLayerId"] == "Water line/label/Default"
        )
        self.assertIs(catalog_rule["disputed"], False)
        catalog_rule["disputed"] = 0
        with self.assertRaisesRegex(
            styles.StyleContractError, "contract catalog identity"
        ):
            styles.style_evidence_bytes(contract)

    def test_audit_count_identity_rejects_boolean_integer_alias(self) -> None:
        aliased_counts = dict(self.contract.audit_counts)
        self.assertEqual(aliased_counts["extraction_failure"], 0)
        aliased_counts["extraction_failure"] = False
        with self.assertRaisesRegex(
            styles.StyleContractError,
            "contract audit-count identity",
        ):
            replace(
                self.contract,
                audit_counts=MappingProxyType(aliased_counts),
            )

    def test_rule_index_identity_rejects_raw_integer_enum_alias(self) -> None:
        target_key = next(iter(self.contract.rule_index_by_source_kind))
        aliased_index = {
            (
                source_layer,
                feature_kind.value if key == target_key else feature_kind,
            ): rules
            for key, rules in self.contract.rule_index_by_source_kind.items()
            for source_layer, feature_kind in (key,)
        }
        stored_target = next(
            key
            for key in aliased_index
            if key[0] == target_key[0] and key[1] == target_key[1]
        )
        self.assertIs(type(stored_target[1]), int)
        with self.assertRaisesRegex(
            styles.StyleContractError,
            "contract rule-index identity",
        ):
            replace(
                self.contract,
                rule_index_by_source_kind=MappingProxyType(aliased_index),
            )

    def test_evidence_writer_round_trips_exact_canonical_bytes(self) -> None:
        expected = styles.style_evidence_bytes(self.contract)
        with tempfile.TemporaryDirectory() as temporary:
            styles.write_style_evidence(self.contract, Path(temporary))
            actual = _read_pinned_style_evidence(Path(temporary), expected)
        self.assertEqual(actual, expected)


if __name__ == "__main__":
    unittest.main()
