#!/usr/bin/env python3
"""Build Flight Alert's bundled local reference label asset.

The runtime uses this generated file instead of live raster country/place label
tiles so satellite zooms cannot mix incompatible label resolutions on screen.
"""

from __future__ import annotations

import json
import math
import pathlib
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[2]
WORK_DIR = ROOT / "tmp" / "natural-earth"
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "reference"
OUT_FILE = OUT_DIR / "reference_labels_v1.json"

SOURCES = {
    "countries": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson",
    "country_boundaries": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_boundary_lines_land.geojson",
    "admin1_lines": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_1_states_provinces_lines.geojson",
    "admin1_polygons": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_1_states_provinces.geojson",
    "places": "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_populated_places.geojson",
}


def read_geojson(name: str) -> dict:
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    path = WORK_DIR / f"{name}.geojson"
    url_path = WORK_DIR / f"{name}.url"
    source_url = SOURCES[name]
    cached_url = url_path.read_text(encoding="utf-8").strip() if url_path.exists() else ""
    if not path.exists() or cached_url != source_url:
        urllib.request.urlretrieve(source_url, path)
        url_path.write_text(source_url, encoding="utf-8")
    return json.loads(path.read_text(encoding="utf-8"))


def round_point(coord: list[float]) -> list[float]:
    return [round(float(coord[0]), 4), round(float(coord[1]), 4)]


def clean_line(coords: list[list[float]]) -> list[list[float]]:
    result: list[list[float]] = []
    previous: list[float] | None = None
    for coord in coords:
        point = round_point(coord)
        if previous == point:
            continue
        result.append(point)
        previous = point
    return result if len(result) >= 2 else []


def point_line_distance(point: list[float], start: list[float], end: list[float]) -> float:
    start_x, start_y = start
    end_x, end_y = end
    point_x, point_y = point
    dx = end_x - start_x
    dy = end_y - start_y
    if dx == 0 and dy == 0:
        return math.hypot(point_x - start_x, point_y - start_y)
    t = max(0.0, min(1.0, ((point_x - start_x) * dx + (point_y - start_y) * dy) / (dx * dx + dy * dy)))
    projected_x = start_x + t * dx
    projected_y = start_y + t * dy
    return math.hypot(point_x - projected_x, point_y - projected_y)


def simplify_line(points: list[list[float]], tolerance: float) -> list[list[float]]:
    if tolerance <= 0 or len(points) <= 2:
        return points
    stack = [(0, len(points) - 1)]
    keep = {0, len(points) - 1}
    while stack:
        start, end = stack.pop()
        max_distance = -1.0
        split_index: int | None = None
        for index in range(start + 1, end):
            distance = point_line_distance(points[index], points[start], points[end])
            if distance > max_distance:
                max_distance = distance
                split_index = index
        if split_index is not None and max_distance > tolerance:
            keep.add(split_index)
            stack.append((start, split_index))
            stack.append((split_index, end))
    return [points[index] for index in sorted(keep)]


def geometry_lines(geometry: dict) -> list[list[list[float]]]:
    kind = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if kind == "LineString":
        line = clean_line(coords)
        return [line] if line else []
    if kind == "MultiLineString":
        return [line for part in coords if (line := clean_line(part))]
    if kind == "Polygon":
        return [line for ring in coords[:1] if (line := clean_line(ring))]
    if kind == "MultiPolygon":
        lines: list[list[list[float]]] = []
        for polygon in coords:
            for ring in polygon[:1]:
                line = clean_line(ring)
                if line:
                    lines.append(line)
        return lines
    return []


def line_bbox(points: list[list[float]]) -> list[float]:
    lons = [p[0] for p in points]
    lats = [p[1] for p in points]
    return [round(min(lons), 4), round(min(lats), 4), round(max(lons), 4), round(max(lats), 4)]


def source_number(props: dict, key: str, fallback: float) -> float:
    value = props.get(key)
    if value is None:
        return fallback
    try:
        if isinstance(value, float) and math.isnan(value):
            return fallback
        return float(value)
    except (TypeError, ValueError):
        return fallback


def build_line_layer(features: list[dict], kind: str, min_zoom: float, simplify_tolerance: float) -> list[dict]:
    lines: list[dict] = []
    for feature in features:
        props = feature.get("properties") or {}
        feature_min_zoom = max(min_zoom, source_number(props, "MIN_ZOOM", min_zoom))
        for points in geometry_lines(feature.get("geometry") or {}):
            points = simplify_line(points, simplify_tolerance)
            if len(points) < 2:
                continue
            lines.append({
                "kind": kind,
                "minZoom": round(feature_min_zoom, 2),
                "bbox": line_bbox(points),
                "points": points,
            })
    return lines


def country_labels(features: list[dict]) -> list[dict]:
    labels: list[dict] = []
    for feature in features:
        props = feature.get("properties") or {}
        text = props.get("NAME_LONG") or props.get("NAME")
        lon = props.get("LABEL_X")
        lat = props.get("LABEL_Y")
        if not text or lon is None or lat is None:
            continue
        labels.append({
            "kind": "country",
            "text": text,
            "lon": round(float(lon), 4),
            "lat": round(float(lat), 4),
            "minZoom": round(max(2.0, source_number(props, "MIN_LABEL", 2.0) - 0.35), 2),
            "maxZoom": round(source_number(props, "MAX_LABEL", 7.0) + 1.4, 2),
            "rank": int(source_number(props, "LABELRANK", 6)),
        })
    return labels


def region_labels(features: list[dict]) -> list[dict]:
    labels: list[dict] = []
    for feature in features:
        props = feature.get("properties") or {}
        text = props.get("name") or props.get("gn_name")
        lon = props.get("longitude")
        lat = props.get("latitude")
        if not text or lon is None or lat is None:
            continue
        labels.append({
            "kind": "region",
            "text": text,
            "lon": round(float(lon), 4),
            "lat": round(float(lat), 4),
            "minZoom": round(max(3.6, source_number(props, "min_label", 4.4) - 0.45), 2),
            "maxZoom": round(source_number(props, "max_label", 9.0) + 1.5, 2),
            "rank": int(source_number(props, "labelrank", 5)),
        })
    return labels


def place_labels(features: list[dict]) -> list[dict]:
    labels: list[dict] = []
    for feature in features:
        props = feature.get("properties") or {}
        pop_max = source_number(props, "POP_MAX", 0)
        scalerank = source_number(props, "SCALERANK", 10)
        is_capital = int(source_number(props, "ADM0CAP", 0)) == 1
        if pop_max < 150_000 and scalerank > 6 and not is_capital:
            continue
        text = props.get("NAMEASCII") or props.get("NAME")
        lon = props.get("LONGITUDE")
        lat = props.get("LATITUDE")
        if not text or lon is None or lat is None:
            continue
        if is_capital or pop_max >= 5_000_000:
            population_min_zoom = 4.6
        elif pop_max >= 1_000_000:
            population_min_zoom = 5.0
        elif pop_max >= 250_000:
            population_min_zoom = 5.6
        else:
            population_min_zoom = 6.3
        labels.append({
            "kind": "place",
            "text": text,
            "lon": round(float(lon), 4),
            "lat": round(float(lat), 4),
            "minZoom": round(max(population_min_zoom, source_number(props, "MIN_ZOOM", 6.0) - 0.4), 2),
            "maxZoom": 13.0,
            "rank": int(source_number(props, "SCALERANK", 10)),
            "population": int(pop_max),
            "capital": is_capital,
        })
    return labels


def main() -> None:
    countries = read_geojson("countries")["features"]
    country_boundaries = read_geojson("country_boundaries")["features"]
    admin1_lines = read_geojson("admin1_lines")["features"]
    admin1_polygons = read_geojson("admin1_polygons")["features"]
    places = read_geojson("places")["features"]

    asset = {
        "version": 1,
        "source": "Natural Earth public domain vector data",
        "sourceUrls": SOURCES,
        "lines": (
            build_line_layer(country_boundaries, "country", min_zoom=2.0, simplify_tolerance=0.03) +
            build_line_layer(admin1_lines, "region", min_zoom=3.2, simplify_tolerance=0.06)
        ),
        "labels": (
            country_labels(countries) +
            region_labels(admin1_polygons) +
            place_labels(places)
        ),
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(asset, separators=(",", ":"), ensure_ascii=False)
    OUT_FILE.write_text(payload, encoding="utf-8")
    print(f"Wrote {OUT_FILE} ({OUT_FILE.stat().st_size:,} bytes json)")
    print(f"lines={len(asset['lines'])} labels={len(asset['labels'])}")


if __name__ == "__main__":
    main()
