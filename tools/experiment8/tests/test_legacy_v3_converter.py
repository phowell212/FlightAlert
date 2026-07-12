from __future__ import annotations

import hashlib
import json
import struct
import tempfile
import unittest
import unicodedata
import zlib
from pathlib import Path
from unittest import mock

from tools.experiment8.model import TileKey
from tools.experiment8.renderer_tile_package import decode_tile_payload, raw_deflate
from tools.experiment8.semantic_model import PlacementSourceKind


def _label_record(text: str = "\u0391\u03b8\u03ae\u03bd\u03b1") -> dict[str, object]:
    return {
        "role": "label",
        "sourceLayer": "City small scale",
        "featureIndex": 7,
        "text": text,
        "names": {"_name_el": "\u0391\u03b8\u03ae\u03bd\u03b1", "_name_en": "Athens"},
        "class": "8",
        "rank": 31,
        "minZoom": 2,
        "maxZoom": 10,
        "styleTokenId": "48ffbace1030",
        "styleLayerIds": ["City small scale/town small non capital"],
        "styleLabelPlacement": "point",
        "labelPlacement": "point",
        "sourceKind": "place",
        "anchor": {"x": 3601, "y": 3149},
        "geometry": {
            "ringCount": 1,
            "pointCount": 1,
            "bounds": {"minX": 3601, "minY": 3149, "maxX": 3601, "maxY": 3149},
            "rings": [[[3601, 3149]]],
        },
        "properties": {
            "_name": "Athens",
            "_name_en": "Athens",
            "_symbol": 8,
            "_label_class": 2,
            "SelectionPriority": 31,
        },
        "dedupeKey": "b3ed0a3ca1c079843622ea15",
    }


def _r4_non_nfc_water_point_label() -> dict[str, object]:
    text = "Sambaali\u0328a\u0328h T\u0142a\u0301h"
    return {
        "role": "label",
        "sourceLayer": "Water point",
        "featureIndex": 0,
        "text": text,
        "names": {"_name_en": text},
        "class": None,
        "rank": None,
        "minZoom": 9,
        "maxZoom": None,
        "styleTokenId": "96534b45e459",
        "styleLayerIds": [
            "Water point/Stream or river",
            "Water point/Lake or reservoir",
            "Water point/Bay or inlet",
            "Water point/Sea or ocean",
            "Water point/Canal or ditch",
            "Water point/Island",
        ],
        "styleLabelPlacement": "point",
        "labelPlacement": "point",
        "sourceKind": "water",
        "anchor": {"x": 368529, "y": 305364},
        "geometry": {
            "ringCount": 1,
            "pointCount": 1,
            "bounds": {
                "minX": 368529,
                "minY": 305364,
                "maxX": 368529,
                "maxY": 305364,
            },
            "rings": [[[368529, 305364]]],
        },
        "properties": {
            "_label_class": 1,
            "_name_en": text,
            "_name_global": text,
            "_name_local": text,
        },
        "dedupeKey": "5187ac1da293d10af1c41f26",
    }


def _boundary_record() -> dict[str, object]:
    return {
        "role": "boundary",
        "sourceLayer": "Boundary line",
        "featureIndex": 3,
        "text": None,
        "names": {},
        "class": None,
        "rank": None,
        "minZoom": None,
        "maxZoom": None,
        "styleTokenId": "boundary-token",
        "styleLayerIds": ["Boundary line/Admin1/casing"],
        "styleLabelPlacement": None,
        "labelPlacement": None,
        "sourceKind": "admin",
        "anchor": None,
        "geometry": {
            "ringCount": 1,
            "pointCount": 2,
            "bounds": {"minX": -64, "minY": 20, "maxX": 4160, "maxY": 20},
            "rings": [[[-64, 20], [4160, 20]]],
        },
        "properties": {"_symbol": 1, "DisputeID": 0},
        "dedupeKey": "00112233445566778899aabb",
    }


def _transportation_record() -> dict[str, object]:
    record = _boundary_record()
    record.update(
        {
            "role": "transportation",
            "sourceLayer": "Road",
            "featureIndex": 9,
            "styleLayerIds": ["Road/Freeway"],
            "dedupeKey": "fedcba987654321001234567",
        }
    )
    return record


def _admin2_point_label(points: list[tuple[int, int]]) -> dict[str, object]:
    if not points:
        raise AssertionError("point-label test fixture requires at least one point")
    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    first_x, first_y = points[0]
    return {
        "role": "label",
        "sourceLayer": "Admin2 area/label",
        "featureIndex": 11,
        "text": "Chesapeake",
        "names": {"_name": "Chesapeake"},
        "class": None,
        "rank": None,
        "minZoom": 10,
        "maxZoom": 11,
        "styleTokenId": "10129611a703",
        "styleLayerIds": [
            "Admin2 area/label/small",
            "Admin2 area/label/large",
        ],
        "styleLabelPlacement": "point",
        "labelPlacement": "point",
        "sourceKind": "county",
        "anchor": {"x": first_x, "y": first_y},
        "geometry": {
            "ringCount": len(points),
            "pointCount": len(points),
            "bounds": {
                "minX": min(xs),
                "minY": min(ys),
                "maxX": max(xs),
                "maxY": max(ys),
            },
            "rings": [[[x, y]] for x, y in points],
        },
        "properties": {
            "_name": "Chesapeake",
            "_label_class": 1,
            "_minzoom": 0,
        },
        "dedupeKey": "6d261c004ffdb8f071bc8b3e",
    }


def _tile_document(tile: TileKey, *, text: str = "\u0391\u03b8\u03ae\u03bd\u03b1") -> dict[str, object]:
    labels = [_label_record(text)]
    boundaries = [_boundary_record()]
    transportation = [_transportation_record()]
    return {
        "schemaVersion": 1,
        "tileKey": f"{tile.z}/{tile.x}/{tile.y}",
        "coordinate": {"z": tile.z, "x": tile.x, "y": tile.y},
        "provenance": {
            "vectorService": "World_Basemap_v2",
            "pbfPath": f"cache/{tile.z}/{tile.x}/{tile.y}.pbf",
            "pbfBytes": 1234,
            "styleDigest": "97f72adcdaf35127",
        },
        "counts": {
            "labels": len(labels),
            "boundaries": len(boundaries),
            "transportation": len(transportation),
            "duplicatesSkipped": {"labels": 0, "boundaries": 0, "transportation": 0},
        },
        "records": {
            "labels": labels,
            "boundaries": boundaries,
            "transportation": transportation,
        },
        "compositeDedupe": {"schemaVersion": 1},
    }


def _write_legacy_package(
    root: Path,
    tiles: list[tuple[TileKey, dict[str, object]]],
    *,
    whole_earth: bool = False,
    raw_override: dict[TileKey, bytes] | None = None,
) -> Path:
    package = root / "legacy"
    package.mkdir()
    ordered = sorted(tiles, key=lambda item: (item[0].z, item[0].y, item[0].x))
    zoom_ranges: list[dict[str, int]] = []
    for zoom in sorted({tile.z for tile, _ in ordered}):
        at_zoom = [tile for tile, _ in ordered if tile.z == zoom]
        x_min = min(tile.x for tile in at_zoom)
        x_max = max(tile.x for tile in at_zoom)
        y_min = min(tile.y for tile in at_zoom)
        y_max = max(tile.y for tile in at_zoom)
        expected = [
            TileKey(zoom, x, y)
            for y in range(y_min, y_max + 1)
            for x in range(x_min, x_max + 1)
        ]
        if at_zoom != expected:
            raise AssertionError("test input tiles must make a dense range")
        zoom_ranges.append(
            {
                "z": zoom,
                "xMin": x_min,
                "xMax": x_max,
                "yMin": y_min,
                "yMax": y_max,
                "tileCount": len(expected),
            }
        )

    records = bytearray()
    index = bytearray()
    raw_override = raw_override or {}
    for tile, document in ordered:
        raw = raw_override.get(tile)
        if raw is None:
            raw = json.dumps(
                document,
                allow_nan=False,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8", "strict")
        compressed = raw_deflate(raw)
        index.extend(
            struct.pack(
                "<QIIII",
                len(records),
                len(compressed),
                len(raw),
                int.from_bytes(hashlib.sha256(raw).digest()[:4], "big"),
                0,
            )
        )
        records.extend(compressed)

    manifest = {
        "schemaVersion": 1,
        "packageId": "legacy-world",
        "storage": {
            "kind": "flightalert-reference-tile-pack",
            "version": 1,
            "payloadEncoding": "utf8-json",
            "tileCompression": "deflate-raw",
        },
        "currentScope": {"completeWholeEarthDictionary": whole_earth},
        "coverage": {
            "tileCount": len(ordered),
            "completeDeclaredScope": True,
            "completeWholeEarthDictionary": whole_earth,
            "zoomRanges": zoom_ranges,
        },
        "size": {
            "payloadBytes": len(records),
            "binaryIndexBytes": len(index),
        },
    }
    (package / "manifest.json").write_text(
        json.dumps(manifest, allow_nan=False, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    (package / "records.fadictpack").write_bytes(records)
    (package / "tile-index.bin").write_bytes(index)
    return package


def _convert_single_label(
    root: Path,
    tile: TileKey,
    label: dict[str, object],
    *,
    package_id: str,
) -> tuple[list[object], dict[str, object]]:
    from tools.experiment8.legacy_v3_converter import (
        LegacyConversionError,
        TileWindow,
        convert_legacy_package,
    )

    document = _tile_document(tile)
    document["records"] = {
        "labels": [label],
        "boundaries": [],
        "transportation": [],
    }
    document["counts"].update(
        {"labels": 1, "boundaries": 0, "transportation": 0}
    )
    source = _write_legacy_package(root, [(tile, document)])
    output = root / "converted"
    try:
        convert_legacy_package(
            source_directory=source,
            output_directory=output,
            package_id=package_id,
            windows=(TileWindow(tile.z, tile.x, tile.x, tile.y, tile.y),),
            checkpoint_every=1,
        )
    except LegacyConversionError as error:
        raise AssertionError(f"supported point-label conversion failed: {error}") from error

    offset, compressed_length, _raw_length, _hash32, _flags = struct.unpack(
        "<QIIII", (output / "tile-index.bin").read_bytes()
    )
    compressed = (output / "records.fadictpack").read_bytes()
    decoded = decode_tile_payload(
        tile,
        zlib.decompress(
            compressed[offset : offset + compressed_length],
            wbits=-zlib.MAX_WBITS,
        ),
    )
    labels = [item for item in decoded.records if item.sourced_text is not None]
    report = json.loads((output / "conversion-report.json").read_text("utf-8"))
    return labels, report


def _decoded_local_point(item: object, tile: TileKey) -> tuple[int, int]:
    geometry = item.renderer_record.variant.geometry
    if len(geometry.parts) != 1 or len(geometry.world_coordinate_numerators) != 2:
        raise AssertionError("decoded renderer record is not one exact point")
    world_denominator = (1 << tile.z) * 4096
    numerator_x, numerator_y = geometry.world_coordinate_numerators
    scaled_x = numerator_x * world_denominator
    scaled_y = numerator_y * world_denominator
    if (
        scaled_x % geometry.world_denominator != 0
        or scaled_y % geometry.world_denominator != 0
    ):
        raise AssertionError("decoded point does not map to an exact source coordinate")
    return (
        scaled_x // geometry.world_denominator - tile.x * 4096,
        scaled_y // geometry.world_denominator - tile.y * 4096,
    )


class LegacyV3ConverterTests(unittest.TestCase):
    def test_real_admin2_duplicate_point_parts_emit_one_unique_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tile = TileKey(8, 73, 99)
            labels, report = _convert_single_label(
                Path(directory),
                tile,
                _admin2_point_label([(3033, 3806), (3033, 3806)]),
                package_id="admin2-exact-duplicate-v3",
            )

            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(1, report["convertedCounts"]["labels"])
            self.assertEqual(
                {"labels.redundant_exact_point_parts": 1},
                report["sourcePartAuditCounts"],
            )
            self.assertEqual(1, len(labels))
            self.assertEqual((3033, 3806), _decoded_local_point(labels[0], tile))

    def test_repeated_point_parts_emit_only_unique_candidates(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tile = TileKey(8, 73, 99)
            unique_points = {(3033, 3806), (3000, 3700), (3200, 3900)}
            labels, report = _convert_single_label(
                Path(directory),
                tile,
                _admin2_point_label(
                    [
                        (3033, 3806),
                        (3000, 3700),
                        (3033, 3806),
                        (3200, 3900),
                        (3000, 3700),
                    ]
                ),
                package_id="admin2-stable-unique-points-v3",
            )

            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(len(unique_points), report["convertedCounts"]["labels"])
            self.assertEqual(
                {"labels.redundant_exact_point_parts": 2},
                report["sourcePartAuditCounts"],
            )
            self.assertEqual(
                unique_points,
                {_decoded_local_point(item, tile) for item in labels},
            )
            self.assertEqual(
                1,
                len({item.renderer_record.posting.feature_id for item in labels}),
            )
            self.assertEqual(
                1,
                len({item.renderer_record.variant.dedupe_id for item in labels}),
            )
            self.assertEqual(
                1,
                len(
                    {
                        item.renderer_record.variant.placement.source_feature_sha256
                        for item in labels
                    }
                ),
            )
            self.assertEqual(
                len(unique_points),
                len({item.renderer_record.variant.geometry_id for item in labels}),
            )
            self.assertEqual(
                len(unique_points),
                len(
                    {
                        item.renderer_record.variant.placement.label_candidate_id
                        for item in labels
                    }
                ),
            )
            self.assertEqual(
                len(unique_points),
                len(
                    {
                        item.renderer_record.variant.canonical_variant_id
                        for item in labels
                    }
                ),
            )
            postings = {
                (
                    item.renderer_record.posting.feature_id,
                    item.renderer_record.posting.canonical_variant_id,
                )
                for item in labels
            }
            self.assertEqual(len(unique_points), len(postings))

    def test_all_identical_point_parts_emit_one_candidate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tile = TileKey(8, 73, 99)
            labels, report = _convert_single_label(
                Path(directory),
                tile,
                _admin2_point_label([(3033, 3806)] * 4),
                package_id="admin2-all-identical-points-v3",
            )

            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(1, report["convertedCounts"]["labels"])
            self.assertEqual(
                {"labels.redundant_exact_point_parts": 3},
                report["sourcePartAuditCounts"],
            )
            self.assertEqual(1, len(labels))

    def test_supported_multi_point_label_splits_every_singleton_source_part(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(7, 98, 22)
            document = _tile_document(tile)
            label = _label_record("\u0410\u0441\u0442\u0440\u043e\u043d\u043e\u043c\u0438\u0447\u0435\u0441\u043a\u0438\u0435 \u043e\u0437\u0435\u0440\u0430")
            label.update(
                {
                    "sourceLayer": "Water area/label",
                    "featureIndex": 0,
                    "names": {
                        "_name_local": "\u0410\u0441\u0442\u0440\u043e\u043d\u043e\u043c\u0438\u0447\u0435\u0441\u043a\u0438\u0435 \u043e\u0437\u0435\u0440\u0430",
                        "_name_en": "Astronomicheskiye ozera",
                    },
                    "minZoom": 11,
                    "maxZoom": None,
                    "styleTokenId": "dcdfd300d96b",
                    "styleLayerIds": [
                        "Water area/label/Canal or ditch",
                        "Water area/label/Small river",
                        "Water area/label/Large river",
                        "Water area/label/Small lake or reservoir",
                        "Water area/label/Large lake or reservoir",
                        "Water area/label/Bay or inlet",
                        "Water area/label/Small island",
                        "Water area/label/Large island",
                    ],
                    "styleLabelPlacement": "line",
                    "labelPlacement": "point",
                    "sourceKind": "water",
                    "anchor": {"x": 670002, "y": 311711},
                    "geometry": {
                        "ringCount": 2,
                        "pointCount": 2,
                        "bounds": {
                            "minX": 582059,
                            "minY": 270651,
                            "maxX": 757944,
                            "maxY": 352771,
                        },
                        "rings": [[[582059, 352771]], [[757944, 270651]]],
                    },
                    "properties": {
                        "_label_class": 4,
                        "_name_local": "\u0410\u0441\u0442\u0440\u043e\u043d\u043e\u043c\u0438\u0447\u0435\u0441\u043a\u0438\u0435 \u043e\u0437\u0435\u0440\u0430",
                        "_name_en": "Astronomicheskiye ozera",
                    },
                    "dedupeKey": "70d995a22b2d4e9c089e99ab",
                }
            )
            document["records"] = {
                "labels": [label],
                "boundaries": [],
                "transportation": [],
            }
            document["counts"].update(
                {"labels": 1, "boundaries": 0, "transportation": 0}
            )
            source = _write_legacy_package(root, [(tile, document)])
            output = root / "converted"

            convert_legacy_package(
                source_directory=source,
                output_directory=output,
                package_id="multi-point-label-v3",
                windows=(TileWindow(7, 98, 98, 22, 22),),
                checkpoint_every=1,
            )

            offset, compressed_length, _raw_length, _hash32, _flags = struct.unpack(
                "<QIIII", (output / "tile-index.bin").read_bytes()
            )
            compressed = (output / "records.fadictpack").read_bytes()
            decoded = decode_tile_payload(
                tile,
                zlib.decompress(
                    compressed[offset : offset + compressed_length],
                    wbits=-zlib.MAX_WBITS,
                ),
            )
            labels = [item for item in decoded.records if item.sourced_text is not None]
            self.assertEqual(2, len(labels))
            self.assertEqual(1, len({item.renderer_record.posting.feature_id for item in labels}))
            self.assertEqual(1, len({item.renderer_record.variant.dedupe_id for item in labels}))
            self.assertEqual(
                1,
                len(
                    {
                        item.renderer_record.variant.placement.source_feature_sha256
                        for item in labels
                    }
                ),
            )
            self.assertEqual(2, len({item.renderer_record.variant.geometry_id for item in labels}))
            self.assertEqual(
                2,
                len(
                    {
                        item.renderer_record.variant.placement.label_candidate_id
                        for item in labels
                    }
                ),
            )
            self.assertEqual(
                2,
                len(
                    {
                        item.renderer_record.variant.canonical_variant_id
                        for item in labels
                    }
                ),
            )
            expected_local_points = {(582059, 352771), (757944, 270651)}
            actual_local_points = set()
            world_denominator = (1 << tile.z) * 4096
            for item in labels:
                geometry = item.renderer_record.variant.geometry
                self.assertEqual(1, len(geometry.parts))
                self.assertEqual(2, len(geometry.world_coordinate_numerators))
                numerator_x, numerator_y = geometry.world_coordinate_numerators
                for local_x, local_y in expected_local_points:
                    world_x = tile.x * 4096 + local_x
                    world_y = tile.y * 4096 + local_y
                    if (
                        numerator_x * world_denominator == world_x * geometry.world_denominator
                        and numerator_y * world_denominator == world_y * geometry.world_denominator
                    ):
                        actual_local_points.add((local_x, local_y))
            self.assertEqual(expected_local_points, actual_local_points)
            self.assertEqual(
                {"\u0410\u0441\u0442\u0440\u043e\u043d\u043e\u043c\u0438\u0447\u0435\u0441\u043a\u0438\u0435 \u043e\u0437\u0435\u0440\u0430"},
                {item.sourced_text.primary_text for item in labels},
            )
            self.assertEqual(
                {"Astronomicheskiye ozera"},
                {item.sourced_text.english_text for item in labels},
            )
            report = json.loads((output / "conversion-report.json").read_text("utf-8"))
            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(2, report["convertedCounts"]["labels"])
            self.assertEqual({}, report["sourcePartAuditCounts"])
            subtype = next(
                item for item in report["subtypeCounts"] if item["semanticSubtype"] == 320
            )
            self.assertEqual(1, subtype["distinctFeatureIds"])
            self.assertEqual(2, subtype["canonicalVariantIds"])
            self.assertEqual(2, subtype["postings"])

    def test_point_label_with_mixed_source_part_shapes_fails_closed(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(7, 98, 22)
            document = _tile_document(tile)
            label = document["records"]["labels"][0]
            label["geometry"] = {
                "ringCount": 2,
                "pointCount": 3,
                "bounds": {
                    "minX": 100,
                    "minY": 200,
                    "maxX": 500,
                    "maxY": 600,
                },
                "rings": [[[100, 200]], [[400, 500], [500, 600]]],
            }
            source = _write_legacy_package(root, [(tile, document)])

            with self.assertRaisesRegex(
                LegacyConversionError,
                "source parts must each retain exactly one source point",
            ):
                convert_legacy_package(
                    source_directory=source,
                    output_directory=root / "converted",
                    package_id="mixed-point-parts-v3",
                    windows=(TileWindow(7, 98, 98, 22, 22),),
                    checkpoint_every=1,
                )

    def test_resume_rejects_converter_identity_drift(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tiles = [
                (TileKey(8, x, 85), _tile_document(TileKey(8, x, 85)))
                for x in range(127, 129)
            ]
            source = _write_legacy_package(root, tiles)
            target = root / "converted"
            with mock.patch(
                "tools.experiment8.legacy_v3_converter._converter_sha256",
                return_value="1" * 64,
            ):
                first = convert_legacy_package(
                    source_directory=source,
                    output_directory=target,
                    package_id="converter-bound-v3",
                    windows=(TileWindow(8, 127, 128, 85, 85),),
                    max_tiles=1,
                    checkpoint_every=1,
                )
            self.assertFalse(first.complete)
            with mock.patch(
                "tools.experiment8.legacy_v3_converter._converter_sha256",
                return_value="2" * 64,
            ), self.assertRaisesRegex(LegacyConversionError, "does not match"):
                convert_legacy_package(
                    source_directory=source,
                    output_directory=target,
                    package_id="converter-bound-v3",
                    windows=(TileWindow(8, 127, 128, 85, 85),),
                    resume=True,
                    checkpoint_every=1,
                )

    def test_declared_english_requiring_trim_is_rejected_not_rewritten(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 144, 98)
            document = _tile_document(tile)
            document["records"]["labels"][0]["names"]["_name_en"] = "Athens "
            source = _write_legacy_package(root, [(tile, document)])
            output = root / "converted"

            result = convert_legacy_package(
                source_directory=source,
                output_directory=output,
                package_id="invalid-english-audit-v3",
                windows=(TileWindow(8, 144, 144, 98, 98),),
                checkpoint_every=1,
            )

            self.assertTrue(result.complete)
            report = json.loads((output / "conversion-report.json").read_text("utf-8"))
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(1, report["droppedCounts"]["labels.invalid_english_text"])

    def test_invalid_optional_english_uses_explicit_gap_without_blocking_primary(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            label = _label_record()
            label["names"]["_name_en"] = ""

            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="blank-english-gap-v3",
            )

            self.assertEqual(1, len(labels))
            self.assertEqual("\u0391\u03b8\u03ae\u03bd\u03b1", labels[0].sourced_text.primary_text)
            self.assertIsNone(labels[0].sourced_text.english_text)
            self.assertEqual("BLANK", labels[0].sourced_text.english_gap_reason.name)
            self.assertIsNotNone(labels[0].sourced_text.english_source_field_id)
            self.assertEqual(1, report["convertedCounts"]["labels"])
            self.assertEqual(
                0,
                report["droppedCounts"].get("labels.invalid_english_text", 0),
            )

    def test_non_nfc_declared_english_is_preserved_exactly_when_shared_contract_accepts_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            declared_english = "Sambaali\u0328a\u0328h"
            label = _label_record()
            label["names"]["_name_en"] = declared_english

            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="source-exact-english-v3",
            )

            self.assertEqual(1, len(labels))
            self.assertNotEqual(
                declared_english,
                unicodedata.normalize("NFC", declared_english),
            )
            self.assertEqual(declared_english, labels[0].sourced_text.english_text)
            self.assertEqual("NONE", labels[0].sourced_text.english_gap_reason.name)
            self.assertEqual(1, report["convertedCounts"]["labels"])

    def test_real_z9_ustek_is_dropped_without_emitting_suspect_text(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source_text = "\u00da\u0161t\u011bk"
            self.assertEqual("c39ac5a174c49b6b", source_text.encode("utf-8").hex())
            label = _label_record(source_text)
            label.update(
                {
                    "featureIndex": 4,
                    "class": "18",
                    "rank": 97,
                    "anchor": {"x": 1639, "y": 1398},
                    "geometry": {
                        "ringCount": 1,
                        "pointCount": 1,
                        "bounds": {
                            "minX": 1639,
                            "minY": 1398,
                            "maxX": 1639,
                            "maxY": 1398,
                        },
                        "rings": [[[1639, 1398]]],
                    },
                    "dedupeKey": "b4bd32138984707387c5a89a",
                }
            )
            label["names"] = {
                "_name": source_text,
                "_name_cs": source_text,
                "_name_en": source_text,
            }
            label["properties"] = {
                "SelectionPriority": 97,
                "_label_class": 5,
                "_name": source_text,
                "_name_cs": source_text,
                "_name_en": source_text,
                "_symbol": 18,
            }
            root = Path(directory)

            labels, report = _convert_single_label(
                root,
                TileKey(9, 276, 172),
                label,
                package_id="real-z9-ustek-probable-mojibake-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )
            offset, compressed_length, _raw_length, _hash32, _flags = struct.unpack(
                "<QIIII", (root / "converted" / "tile-index.bin").read_bytes()
            )
            compressed = (root / "converted" / "records.fadictpack").read_bytes()
            payload = zlib.decompress(
                compressed[offset : offset + compressed_length],
                wbits=-zlib.MAX_WBITS,
            )
            self.assertNotIn(source_text.encode("utf-8"), payload)

    def test_probable_mojibake_does_not_hide_malformed_names_structure(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(9, 276, 172)
            document = _tile_document(tile, text="\u00da\u0161t\u011bk")
            document["records"]["labels"][0]["names"] = []
            source = _write_legacy_package(root, [(tile, document)])

            with self.assertRaisesRegex(
                LegacyConversionError,
                "record names must be an object",
            ):
                convert_legacy_package(
                    source_directory=source,
                    output_directory=root / "rejected",
                    package_id="malformed-probable-mojibake-v3",
                    windows=(TileWindow(9, 276, 276, 172, 172),),
                    checkpoint_every=1,
                )

    def test_legitimate_latin1_marker_with_non_nfc_english_remains_source_exact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            declared_english = "\u00c2me e\u0301"
            label = _label_record()
            label["names"]["_name_en"] = declared_english

            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="legitimate-latin1-english-v3",
            )

            self.assertEqual(1, len(labels))
            self.assertNotEqual(
                declared_english,
                unicodedata.normalize("NFC", declared_english),
            )
            self.assertEqual(declared_english, labels[0].sourced_text.english_text)
            self.assertEqual("NONE", labels[0].sourced_text.english_gap_reason.name)
            self.assertEqual(1, report["convertedCounts"]["labels"])

    def test_non_latin_english_with_legitimate_latin1_marker_remains_explicit_gap(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            label = _label_record()
            label["names"]["_name_en"] = "\u6771\u4eac \u00c2me"

            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="non-latin-english-gap-v3",
            )

            self.assertEqual(1, len(labels))
            self.assertIsNone(labels[0].sourced_text.english_text)
            self.assertEqual(
                "HAS_STRONG_NON_LATIN",
                labels[0].sourced_text.english_gap_reason.name,
            )
            self.assertEqual(1, report["convertedCounts"]["labels"])

    def test_declared_english_mojibake_is_audited_and_dropped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            label = _label_record()
            label["names"]["_name_en"] = "Fran\u00c3\u00a7ais"
            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="english-mojibake-drop-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )

    def test_mixed_non_nfc_declared_english_with_mojibake_is_dropped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            label = _label_record()
            label["names"]["_name_en"] = "Fran\u00c3\u00a7ais e\u0301"
            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="mixed-english-mojibake-drop-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )

    def test_replacement_component_in_mixed_declared_english_is_dropped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            replacement_component = "\u00ef\u00bf\u00bd"
            self.assertEqual(
                "c3afc2bfc2bd",
                replacement_component.encode("utf-8").hex(),
            )
            label = _label_record()
            label["names"]["_name_en"] = replacement_component + " e\u0301"
            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 144, 98),
                label,
                package_id="replacement-english-mojibake-drop-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )

    def test_conversion_never_materializes_a_planned_coordinate_collection(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 127, 85)
            source = _write_legacy_package(root, [(tile, _tile_document(tile))])
            with mock.patch.object(
                TileWindow,
                "coordinates",
                side_effect=AssertionError("planned coordinates were materialized"),
            ):
                result = convert_legacy_package(
                    source_directory=source,
                    output_directory=root / "converted",
                    package_id="streaming-plan-v3",
                    windows=(TileWindow(8, 127, 127, 85, 85),),
                    checkpoint_every=1,
                )
            self.assertTrue(result.complete)

    def test_source_text_requiring_trim_is_rejected_with_an_audited_reason(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 142, 94)
            document = _tile_document(tile, text="Fierza Reservoir ")
            source = _write_legacy_package(root, [(tile, document)])
            output = root / "converted"

            result = convert_legacy_package(
                source_directory=source,
                output_directory=output,
                package_id="invalid-text-audit-v3",
                windows=(TileWindow(8, 142, 142, 94, 94),),
                checkpoint_every=1,
            )

            self.assertTrue(result.complete)
            report = json.loads((output / "conversion-report.json").read_text("utf-8"))
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(1, report["droppedCounts"]["labels.invalid_visible_text"])

    def test_r4_supported_non_nfc_primary_is_audited_without_rewrite_or_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            label = _r4_non_nfc_water_point_label()
            source_text = label["text"]
            self.assertEqual(
                "53616d6261616c69cca861cca8682054c58261cc8168",
                source_text.encode("utf-8").hex(),
            )
            self.assertNotEqual(source_text, unicodedata.normalize("NFC", source_text))

            labels, report = _convert_single_label(
                Path(directory),
                TileKey(9, 83, 146),
                label,
                package_id="r4-non-nfc-primary-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.invalid_visible_text"],
            )

    def test_non_nfc_primary_does_not_hide_malformed_names_structure(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(9, 83, 146)
            document = _tile_document(tile)
            label = _r4_non_nfc_water_point_label()
            label["names"] = []
            document["records"] = {
                "labels": [label],
                "boundaries": [],
                "transportation": [],
            }
            document["counts"].update(
                {"labels": 1, "boundaries": 0, "transportation": 0}
            )
            source = _write_legacy_package(root, [(tile, document)])

            with self.assertRaisesRegex(
                LegacyConversionError,
                "record names must be an object",
            ):
                convert_legacy_package(
                    source_directory=source,
                    output_directory=root / "rejected",
                    package_id="non-nfc-malformed-names-v3",
                    windows=(TileWindow(9, 83, 83, 146, 146),),
                    checkpoint_every=1,
                )

    def test_non_nfc_primary_with_control_text_remains_fatal(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(9, 83, 146)
            document = _tile_document(tile)
            label = _r4_non_nfc_water_point_label()
            label["text"] += "\n"
            document["records"] = {
                "labels": [label],
                "boundaries": [],
                "transportation": [],
            }
            document["counts"].update(
                {"labels": 1, "boundaries": 0, "transportation": 0}
            )
            source = _write_legacy_package(root, [(tile, document)])

            with self.assertRaisesRegex(
                LegacyConversionError,
                "record visible text contains invalid Unicode",
            ):
                convert_legacy_package(
                    source_directory=source,
                    output_directory=root / "rejected",
                    package_id="non-nfc-control-primary-v3",
                    windows=(TileWindow(9, 83, 83, 146, 146),),
                    checkpoint_every=1,
                )

    def test_mixed_non_nfc_primary_with_mojibake_is_dropped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            label = _label_record("Fran\u00c3\u00a7ais e\u0301")
            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 127, 85),
                label,
                package_id="mixed-primary-mojibake-drop-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )

    def test_replacement_component_in_mixed_primary_is_dropped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            replacement_component = "\u00ef\u00bf\u00bd"
            self.assertEqual(
                "c3afc2bfc2bd",
                replacement_component.encode("utf-8").hex(),
            )
            label = _label_record(replacement_component + " e\u0301")
            labels, report = _convert_single_label(
                Path(directory),
                TileKey(8, 127, 85),
                label,
                package_id="replacement-primary-mojibake-drop-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )

    def test_source_owned_area_label_point_preserves_resolved_point_geometry(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 131, 85)
            document = _tile_document(tile)
            area_label = _label_record("Canal Albert")
            area_label.update(
                {
                    "sourceLayer": "Water area large scale/label",
                    "styleLayerIds": [
                        "Water area large scale/label/Lake or lake intermittent"
                    ],
                    "styleLabelPlacement": "line",
                    "labelPlacement": "point",
                    "sourceKind": "water",
                    "geometry": {
                        "ringCount": 1,
                        "pointCount": 1,
                        "bounds": {
                            "minX": 2000,
                            "minY": 2100,
                            "maxX": 2000,
                            "maxY": 2100,
                        },
                        "rings": [[[2000, 2100]]],
                    },
                    "anchor": {"x": 2000, "y": 2100},
                    "dedupeKey": "0123456789abcdef01234567",
                }
            )
            document["records"]["labels"] = [area_label]
            document["counts"]["labels"] = 1
            source = _write_legacy_package(root, [(tile, document)])
            output = root / "converted"

            convert_legacy_package(
                source_directory=source,
                output_directory=output,
                package_id="area-label-v3",
                windows=(TileWindow(8, 131, 131, 85, 85),),
                checkpoint_every=1,
            )

            entry = struct.unpack("<QIIII", (output / "tile-index.bin").read_bytes())
            compressed = (output / "records.fadictpack").read_bytes()
            raw = zlib.decompress(
                compressed[entry[0] : entry[0] + entry[1]],
                wbits=-zlib.MAX_WBITS,
            )
            decoded = decode_tile_payload(tile, raw)
            area = next(
                item
                for item in decoded.records
                if item.sourced_text is not None
                and item.sourced_text.primary_text == "Canal Albert"
            )
            self.assertIs(
                PlacementSourceKind.SOURCE_OWNED_AREA_LABEL_POINT,
                area.renderer_record.variant.placement.placement_source_kind,
            )

    def test_unsupported_guide_label_is_audited_without_reinterpreting_its_path(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 127, 85)
            document = _tile_document(tile)
            guide = _label_record("Prime Meridian")
            guide.update(
                {
                    "sourceLayer": "Graticule/label",
                    "styleLayerIds": [],
                    "geometry": {
                        "ringCount": 1,
                        "pointCount": 2,
                        "bounds": {
                            "minX": 4096,
                            "minY": -64,
                            "maxX": 4096,
                            "maxY": 3852,
                        },
                        "rings": [[[4096, 3852], [4096, -64]]],
                    },
                    "dedupeKey": "84866e06c878c2b686e5a49f",
                }
            )
            document["records"]["labels"].append(guide)
            document["counts"]["labels"] = 2
            source = _write_legacy_package(root, [(tile, document)])

            result = convert_legacy_package(
                source_directory=source,
                output_directory=root / "converted",
                package_id="guide-audit-v3",
                windows=(TileWindow(8, 127, 127, 85, 85),),
                checkpoint_every=1,
            )

            self.assertTrue(result.complete)
            report = json.loads(
                (root / "converted" / "conversion-report.json").read_text("utf-8")
            )
            self.assertEqual(1, report["convertedCounts"]["labels"])
            self.assertEqual(1, report["droppedCounts"]["labels.unsupported_source_layer"])

    def test_unsupported_landform_label_with_control_text_is_audited_and_dropped(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 15, 74)
            document = _tile_document(tile)
            landform = _label_record("Oinathluk Point\nOinathluk Point")
            landform.update(
                {
                    "sourceLayer": "Landform/label",
                    "featureIndex": 0,
                    "styleLayerIds": [],
                    "anchor": {"x": 36864, "y": 837397},
                    "geometry": {
                        "ringCount": 1,
                        "pointCount": 1,
                        "bounds": {
                            "minX": 36864,
                            "minY": 837397,
                            "maxX": 36864,
                            "maxY": 837397,
                        },
                        "rings": [[[36864, 837397]]],
                    },
                    "dedupeKey": "5fa9247147d7ab2b338ea981",
                }
            )
            document["records"] = {
                "labels": [landform],
                "boundaries": [],
                "transportation": [],
            }
            document["counts"].update(
                {"labels": 1, "boundaries": 0, "transportation": 0}
            )
            source = _write_legacy_package(root, [(tile, document)])
            output = root / "converted"

            try:
                result = convert_legacy_package(
                    source_directory=source,
                    output_directory=output,
                    package_id="unsupported-control-text-v3",
                    windows=(TileWindow(8, 15, 15, 74, 74),),
                    checkpoint_every=1,
                )
            except LegacyConversionError as error:
                self.fail(f"unsupported record text blocked conversion: {error}")

            self.assertTrue(result.complete)
            report = json.loads((output / "conversion-report.json").read_text("utf-8"))
            self.assertEqual(1, report["inputCounts"]["labels"])
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.unsupported_source_layer"],
            )
            offset, compressed_length, _raw_length, _hash32, _flags = struct.unpack(
                "<QIIII", (output / "tile-index.bin").read_bytes()
            )
            compressed = (output / "records.fadictpack").read_bytes()
            decoded = decode_tile_payload(
                tile,
                zlib.decompress(
                    compressed[offset : offset + compressed_length],
                    wbits=-zlib.MAX_WBITS,
                ),
            )
            self.assertEqual(0, len(decoded.records))

    def test_supported_label_with_control_text_still_fails_closed(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            LegacyConversionError,
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 15, 74)
            source = _write_legacy_package(
                root,
                [(tile, _tile_document(tile, text="Supported City\nSupported City"))],
            )

            with self.assertRaisesRegex(
                LegacyConversionError,
                "record visible text contains invalid Unicode",
            ):
                convert_legacy_package(
                    source_directory=source,
                    output_directory=root / "rejected",
                    package_id="supported-control-text-v3",
                    windows=(TileWindow(8, 15, 15, 74, 74),),
                    checkpoint_every=1,
                )

    def test_window_conversion_is_deterministic_and_android_decodable(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tiles = [
                (TileKey(8, 127, 85), _tile_document(TileKey(8, 127, 85))),
                (TileKey(8, 128, 85), _tile_document(TileKey(8, 128, 85))),
            ]
            source = _write_legacy_package(root, tiles)
            outputs = [root / "first", root / "second"]
            results = [
                convert_legacy_package(
                    source_directory=source,
                    output_directory=output,
                    package_id="europe-pilot-v3",
                    windows=(TileWindow(8, 127, 128, 85, 85),),
                    checkpoint_every=1,
                )
                for output in outputs
            ]

            self.assertTrue(all(result.complete for result in results))
            for name in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "conversion-report.json",
            ):
                self.assertEqual(
                    (outputs[0] / name).read_bytes(),
                    (outputs[1] / name).read_bytes(),
                )

            manifest = json.loads((outputs[0] / "manifest.json").read_text("utf-8"))
            report = json.loads(
                (outputs[0] / "conversion-report.json").read_text("utf-8")
            )
            self.assertEqual(3, manifest["schemaVersion"])
            self.assertTrue(manifest["coverage"]["completeDeclaredScope"])
            self.assertFalse(manifest["coverage"]["completeWholeEarthDictionary"])
            self.assertEqual(2, report["plannedTileCount"])
            self.assertEqual(2, report["auditedTileCount"])
            self.assertEqual({"boundaries": 2, "labels": 2}, report["convertedCounts"])
            self.assertEqual(2, report["droppedCounts"]["transportation.unsupported"])
            self.assertGreater(report["projection"]["projectedFullRuntimeBytes"], 0)
            self.assertRegex(report["rendererSemanticStreamSha256"], r"^[0-9a-f]{64}$")
            self.assertEqual(
                report["rendererSemanticStreamSha256"],
                manifest["rendererSemanticStreamSha256"],
            )
            counts = {
                item["semanticSubtype"]: item for item in report["subtypeCounts"]
            }
            self.assertEqual(
                {
                    "canonicalVariantIds": 2,
                    "distinctFeatureIds": 2,
                    "postings": 2,
                    "semanticSubtype": 210,
                    "semanticSubtypeName": "CITY_TOWN",
                },
                counts[210],
            )
            self.assertEqual(2, counts[520]["distinctFeatureIds"])
            self.assertEqual(2, counts[520]["canonicalVariantIds"])
            self.assertEqual(2, counts[520]["postings"])
            self.assertFalse(report["classCatalog"]["emitted"])
            self.assertIsNone(report["classCatalog"]["rendererContractSha256"])
            self.assertEqual(
                "renderer_contract_sha256_not_defined_by_current_runtime_package",
                report["classCatalog"]["reason"],
            )
            self.assertFalse((outputs[0] / "class-catalog.bin").exists())

            index = (outputs[0] / "tile-index.bin").read_bytes()
            records = (outputs[0] / "records.fadictpack").read_bytes()
            self.assertEqual(48, len(index))
            offset, compressed_length, raw_length, _hash32, flags = struct.unpack(
                "<QIIII", index[:24]
            )
            payload = zlib.decompress(
                records[offset : offset + compressed_length],
                wbits=-zlib.MAX_WBITS,
            )
            self.assertEqual(raw_length, len(payload))
            self.assertEqual(1, flags)
            decoded = decode_tile_payload(TileKey(8, 127, 85), payload)
            self.assertEqual(2, len(decoded.records))
            label = next(item for item in decoded.records if item.sourced_text is not None)
            self.assertEqual("\u0391\u03b8\u03ae\u03bd\u03b1", label.sourced_text.primary_text)
            self.assertEqual("Athens", label.sourced_text.english_text)
            self.assertNotEqual(
                label.sourced_text.primary_source_field_id,
                label.sourced_text.english_source_field_id,
            )

    def test_subtype_audit_counts_distinct_variants_not_only_postings(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 127, 85)
            document = _tile_document(tile)
            duplicate = _boundary_record()
            document["records"]["labels"] = []
            document["records"]["boundaries"] = [duplicate, dict(duplicate)]
            document["records"]["transportation"] = []
            document["counts"].update(
                {"labels": 0, "boundaries": 2, "transportation": 0}
            )
            source = _write_legacy_package(root, [(tile, document)])
            output = root / "converted"

            convert_legacy_package(
                source_directory=source,
                output_directory=output,
                package_id="variant-count-v3",
                windows=(TileWindow(8, 127, 127, 85, 85),),
                checkpoint_every=1,
            )

            report = json.loads((output / "conversion-report.json").read_text("utf-8"))
            counts = {
                item["semanticSubtype"]: item for item in report["subtypeCounts"]
            }
            self.assertEqual(2, counts[520]["postings"])
            self.assertEqual(2, counts[520]["distinctFeatureIds"])
            self.assertEqual(1, counts[520]["canonicalVariantIds"])

    def test_resume_truncates_uncommitted_tail_and_matches_fresh_conversion(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            TileWindow,
            convert_legacy_package,
        )

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tiles = [
                (TileKey(8, x, 85), _tile_document(TileKey(8, x, 85)))
                for x in range(127, 130)
            ]
            source = _write_legacy_package(root, tiles)
            target = root / "resumed"
            first = convert_legacy_package(
                source_directory=source,
                output_directory=target,
                package_id="resume-v3",
                windows=(TileWindow(8, 127, 129, 85, 85),),
                max_tiles=1,
                checkpoint_every=1,
            )
            self.assertFalse(first.complete)
            self.assertFalse(target.exists())
            partial = target.with_name(target.name + ".partial")
            self.assertTrue((partial / "records.fadictpack").is_file())
            self.assertTrue((partial / "tile-index.bin").is_file())
            self.assertFalse((partial / "records.fadictpack.partial").exists())
            self.assertFalse((partial / "tile-index.bin.partial").exists())
            with (partial / "records.fadictpack").open("ab") as handle:
                handle.write(b"uncommitted-tail")

            resumed = convert_legacy_package(
                source_directory=source,
                output_directory=target,
                package_id="resume-v3",
                windows=(TileWindow(8, 127, 129, 85, 85),),
                resume=True,
                checkpoint_every=1,
            )
            fresh_target = root / "fresh"
            fresh = convert_legacy_package(
                source_directory=source,
                output_directory=fresh_target,
                package_id="resume-v3",
                windows=(TileWindow(8, 127, 129, 85, 85),),
                checkpoint_every=1,
            )
            self.assertTrue(resumed.complete)
            self.assertTrue(fresh.complete)
            for name in (
                "manifest.json",
                "records.fadictpack",
                "tile-index.bin",
                "conversion-report.json",
            ):
                self.assertEqual(
                    (target / name).read_bytes(),
                    (fresh_target / name).read_bytes(),
                )

    def test_mojibake_detector_catches_exact_script_components_with_mixed_suffixes(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            _looks_like_utf8_mojibake,
        )

        cases = {
            "cyrillic-d0": "\u00d0\u00a2\u00d0\u00b5\u00d0\u00bc\u00d0\u00b7\u00d0\u00b0",
            "cyrillic-d1": "\u00d1\u201a",
            "latin-extended-c4": "\u00c4\u00af",
            "greek-ce": "\u00ce\u2018",
            "hebrew-d7": "\u00d7\u00aa",
            "arabic-d9": "\u00d9\u2026",
            "arabic-da-isolated": "\u00da\u0161",
            "arabic-da-repeated": "\u00da\u0161\u00da\u0161",
        }
        for label, value in cases.items():
            with self.subTest(case=label, form="pure"):
                self.assertTrue(_looks_like_utf8_mojibake(value))
            with self.subTest(case=label, form="mixed"):
                self.assertTrue(_looks_like_utf8_mojibake(value + " e\u0301"))

    def test_mojibake_detector_rejects_embedded_all_latin_components(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            _looks_like_utf8_mojibake,
        )

        cases = {
            "cyrillic": ("A\u00d0\u0161B", "A\u041aB"),
            "greek": ("A\u00ce\u0161B", "A\u039aB"),
            "hebrew": ("A\u00d6\u0161B", "A\u059aB"),
            "arabic": ("A\u00da\u0161B", "A\u069aB"),
        }
        for label, (value, decoded) in cases.items():
            with self.subTest(case=label):
                self.assertEqual(
                    decoded,
                    value.encode("cp1252", "strict").decode("utf-8", "strict"),
                )
                self.assertTrue(_looks_like_utf8_mojibake(value))

    def test_mojibake_detector_uses_no_host_unicode_category_boundary(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            _looks_like_utf8_mojibake,
        )
        from tools.experiment8.sourced_text import DEFAULT_UNICODE_SCRIPT_PROFILE

        value = "\u00da\u0161\U00010780"
        self.assertEqual(
            "Latin",
            DEFAULT_UNICODE_SCRIPT_PROFILE.script_for_scalar(ord(value[-1])),
        )
        self.assertTrue(_looks_like_utf8_mojibake(value))

    def test_mojibake_detector_covers_every_representable_utf8_lead_byte(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            _looks_like_utf8_mojibake,
        )

        for lead in range(0xC2, 0xF5):
            if lead <= 0xDF:
                raw = bytes((lead, 0xA0))
            elif lead <= 0xEF:
                raw = bytes((lead, 0xA0 if lead == 0xE0 else 0x80, 0x80))
            elif lead == 0xF0:
                raw = bytes((lead, 0x91, 0x80, 0x80))
            else:
                raw = bytes((lead, 0x80, 0x80, 0x80))
            raw.decode("utf-8", "strict")
            rendered = raw.decode("cp1252", "strict")
            self.assertEqual(raw, rendered.encode("cp1252", "strict"))
            with self.subTest(lead=f"{lead:02x}", form="pure"):
                self.assertTrue(_looks_like_utf8_mojibake(rendered))
            with self.subTest(lead=f"{lead:02x}", form="mixed"):
                self.assertTrue(
                    _looks_like_utf8_mojibake("\u6771\u4eac " + rendered + " e\u0301")
                )
        replacement_raw = bytes.fromhex("efbfbd")
        replacement_rendered = replacement_raw.decode("cp1252", "strict")
        self.assertEqual(
            "c3afc2bfc2bd",
            replacement_rendered.encode("utf-8").hex(),
        )
        self.assertTrue(_looks_like_utf8_mojibake(replacement_rendered))
        self.assertTrue(
            _looks_like_utf8_mojibake(replacement_rendered + " e\u0301")
        )

    def test_mojibake_detector_accepts_legitimate_latin_and_non_latin_text(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            _looks_like_utf8_mojibake,
        )

        values = (
            "\u00c2me",
            "\u00c2ngela",
            "S\u00e3o Tom\u00e9",
            "Fran\u00e7ais",
            "\u00c9lodie",
            "\u00d0or",
            "\u0391\u03b8\u03ae\u03bd\u03b1",
            "\u05ea\u05dc \u05d0\u05d1\u05d9\u05d1",
            "\u0627\u0644\u0642\u0627\u0647\u0631\u0629",
            "\u6771\u4eac",
        )
        for value in values:
            with self.subTest(value=value, form="plain"):
                self.assertFalse(_looks_like_utf8_mojibake(value))
            with self.subTest(value=value, form="non-nfc-suffix"):
                self.assertFalse(_looks_like_utf8_mojibake(value + " e\u0301"))

    def test_mojibake_detector_requires_a_complete_strict_utf8_component(self) -> None:
        from tools.experiment8.legacy_v3_converter import (
            _looks_like_utf8_mojibake,
        )

        values = (
            "\u00c4",
            "\u00d0A",
            "\u00e0\u20ac\u20ac",
            "\u00ed\u00a0\u20ac",
            "\u00f0\u20ac\u20ac\u20ac",
            "\u00f4\u2018\u20ac\u20ac",
            "\u00f5\u00a0\u00a0\u00a0",
        )
        for value in values:
            with self.subTest(value=value):
                self.assertFalse(_looks_like_utf8_mojibake(value))

    def test_mojibake_is_dropped_instead_of_repaired_or_emitted(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tile = TileKey(8, 127, 85)
            source_text = "Fran\u00c3\u00a7ais"
            labels, report = _convert_single_label(
                root,
                tile,
                _label_record(source_text),
                package_id="mojibake-primary-drop-v3",
            )

            self.assertEqual([], labels)
            self.assertEqual(0, report["convertedCounts"].get("labels", 0))
            self.assertEqual(
                1,
                report["droppedCounts"]["labels.probable_mojibake"],
            )
            offset, compressed_length, _raw_length, _hash32, _flags = struct.unpack(
                "<QIIII", (root / "converted" / "tile-index.bin").read_bytes()
            )
            compressed = (root / "converted" / "records.fadictpack").read_bytes()
            payload = zlib.decompress(
                compressed[offset : offset + compressed_length],
                wbits=-zlib.MAX_WBITS,
            )
            self.assertNotIn(source_text.encode("utf-8"), payload)

    def test_full_mode_claims_whole_earth_only_from_complete_audited_input(self) -> None:
        from tools.experiment8.legacy_v3_converter import convert_legacy_package

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tiles = [
                (TileKey(1, x, y), _tile_document(TileKey(1, x, y)))
                for y in range(2)
                for x in range(2)
            ]
            source = _write_legacy_package(root, tiles, whole_earth=True)
            output = root / "whole"
            result = convert_legacy_package(
                source_directory=source,
                output_directory=output,
                package_id="whole-v3",
                full=True,
                checkpoint_every=1,
            )
            manifest = json.loads((output / "manifest.json").read_text("utf-8"))
            report = json.loads((output / "conversion-report.json").read_text("utf-8"))
            self.assertTrue(result.complete)
            self.assertTrue(report["allInputTilesAudited"])
            self.assertTrue(manifest["coverage"]["completeWholeEarthDictionary"])


if __name__ == "__main__":
    unittest.main()
