@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.TILE_SIZE
import com.flightalert.map.*
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object AviationAirspaceRenderPolicy {
    fun forEachApplicable(
        features: List<AviationAirspaceFeature>,
        visibleBounds: AviationGeoBounds,
        excludedFeature: AviationAirspaceFeature?,
        action: (AviationAirspaceFeature) -> Unit
    ) {
        for (feature in features) {
            if (isApplicable(feature, visibleBounds, excludedFeature)) action(feature)
        }
    }

    fun isApplicable(
        feature: AviationAirspaceFeature,
        visibleBounds: AviationGeoBounds,
        excludedFeature: AviationAirspaceFeature?
    ): Boolean {
        if (feature == excludedFeature ||
            feature.bounds.max_lat < visibleBounds.min_lat ||
            feature.bounds.min_lat > visibleBounds.max_lat
        ) return false
        val rawLongitudeSpan = feature.bounds.max_lon - feature.bounds.min_lon
        val span = if (rawLongitudeSpan <= 180.0) {
            LongitudeSpan(feature.bounds.min_lon, feature.bounds.max_lon)
        } else {
            longitudeSpan(feature)
        }
        return longitudeSpansIntersect(
            span.min,
            span.max,
            visibleBounds.min_lon,
            visibleBounds.max_lon
        )
    }

    fun contains(feature: AviationAirspaceFeature, lat: Double, lon: Double): Boolean {
        val featureAnchorLon = feature.geometry.polygons.first().shell.first().lon
        return feature.geometry.polygons.any { polygon ->
            val polygonAnchorLon = unwrapLongitude(polygon.shell.first().lon, featureAnchorLon)
            pointInsideRing(lat, lon, polygon.shell, polygonAnchorLon) &&
                    polygon.holes.none { hole ->
                        pointInsideRing(lat, lon, hole, polygonAnchorLon)
                    }
        }
    }

    fun boundsArea(feature: AviationAirspaceFeature): Double {
        val latitudeSpan = (feature.bounds.max_lat - feature.bounds.min_lat).coerceAtLeast(0.0)
        val rawLongitudeSpan = (feature.bounds.max_lon - feature.bounds.min_lon).coerceAtLeast(0.0)
        val longitudeWidth = if (rawLongitudeSpan <= 180.0) {
            rawLongitudeSpan
        } else {
            val span = longitudeSpan(feature)
            (span.max - span.min).coerceIn(0.0, 360.0)
        }
        return latitudeSpan * longitudeWidth
    }

    fun unwrapLongitude(lon: Double, referenceLon: Double): Double {
        var unwrapped = lon
        while (unwrapped - referenceLon > 180.0) unwrapped -= 360.0
        while (unwrapped - referenceLon < -180.0) unwrapped += 360.0
        return unwrapped
    }

    fun firstWrappedShift(
        baseLeft: Float,
        baseRight: Float,
        extendedLeft: Float,
        extendedRight: Float,
        worldSpan: Float,
        maxCopies: Int = 8
    ): Float {
        if (worldSpan <= 0f || !worldSpan.isFinite() || maxCopies <= 0) return 0f
        var shift = 0f
        var copies = 0
        while (baseLeft + shift > extendedRight && copies++ < maxCopies) shift -= worldSpan
        copies = 0
        while (baseRight + shift < extendedLeft && copies++ < maxCopies) shift += worldSpan
        return shift
    }

    private fun pointInsideRing(
        lat: Double,
        lon: Double,
        ring: List<AviationLayerPoint>,
        anchorLon: Double
    ): Boolean {
        if (ring.size < 3) return false
        val first = ring.first()
        val firstLon = unwrapLongitude(first.lon, anchorLon)
        val queryLon = unwrapLongitude(lon, firstLon)
        var inside = false
        var previousLat = first.lat
        var previousLon = firstLon
        for (index in 1 until ring.size) {
            val current = ring[index]
            val currentLon = unwrapLongitude(current.lon, previousLon)
            val crossesLat = (current.lat > lat) != (previousLat > lat)
            if (crossesLat) {
                val lonAtLat = (previousLon - currentLon) *
                        (lat - current.lat) /
                        (previousLat - current.lat) +
                        currentLon
                if (queryLon < lonAtLat) inside = !inside
            }
            previousLat = current.lat
            previousLon = currentLon
        }
        return inside
    }

    internal fun longitudeSpansIntersect(
        firstMin: Double,
        firstMax: Double,
        secondMin: Double,
        secondMax: Double
    ): Boolean {
        if (firstMax - firstMin >= 360.0 || secondMax - secondMin >= 360.0) return true
        if (secondMin > secondMax) {
            return longitudeSpansIntersect(firstMin, firstMax, secondMin, 180.0) ||
                    longitudeSpansIntersect(firstMin, firstMax, -180.0, secondMax)
        }
        for (worldCopy in -2..2) {
            val shift = worldCopy * 360.0
            if (firstMax + shift >= secondMin && firstMin + shift <= secondMax) return true
        }
        return false
    }

    private fun longitudeSpan(feature: AviationAirspaceFeature): LongitudeSpan {
        val featureAnchorLon = feature.geometry.polygons.first().shell.first().lon
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (polygon in feature.geometry.polygons) {
            val polygonAnchorLon = unwrapLongitude(polygon.shell.first().lon, featureAnchorLon)
            for (ring in polygon.all_rings) {
                var previousLon = polygonAnchorLon
                for (point in ring) {
                    val currentLon = unwrapLongitude(point.lon, previousLon)
                    minLon = min(minLon, currentLon)
                    maxLon = max(maxLon, currentLon)
                    previousLon = currentLon
                }
            }
        }
        return LongitudeSpan(minLon, maxLon)
    }

    private data class LongitudeSpan(val min: Double, val max: Double)
}

internal fun project_aviation_point_to_screen(
    point: AviationLayerPoint,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): ScreenPoint? {
    val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
    var screen_x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
    val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
    while (screen_x < -world_span / 2f) screen_x += world_span
    while (screen_x > viewport.width + world_span / 2f) screen_x -= world_span
    val screen_y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
    if (
        screen_x < -viewport.width ||
        screen_x > viewport.width * 2f ||
        screen_y < -viewport.height ||
        screen_y > viewport.height * 2f
    ) {
        return null
    }
    return ScreenPoint(screen_x, screen_y)
}

internal fun project_aviation_source_point_to_screen(
    point: AviationLayerPoint,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): ScreenPoint? {
    project_aviation_point_to_screen(point, viewport, lat_lon_to_world)?.let { return it }
    val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
    val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
    if (!world.x.isFinite() || !world.y.isFinite() ||
        !world_span.isFinite() || world_span <= 0.0
    ) return null
    var screen_x = world.x - viewport.center_x + viewport.width / 2.0
    while (screen_x < -world_span / 2.0) screen_x += world_span
    while (screen_x > viewport.width + world_span / 2.0) screen_x -= world_span
    val screen_y = world.y - viewport.center_y + viewport.height / 2.0
    if (!screen_x.isFinite() || !screen_y.isFinite()) return null
    return ScreenPoint(screen_x.toFloat(), screen_y.toFloat())
}

internal fun aviation_screen_to_geo_point(
    x: Float,
    y: Float,
    viewport: Viewport,
    world_to_lat_lon: (Double, Double, Double) -> GeoPoint
): GeoPoint {
    val world_x = viewport.center_x - viewport.width / 2.0 + x
    val world_y = viewport.center_y - viewport.height / 2.0 + y
    return world_to_lat_lon(world_x, world_y, viewport.zoom)
}

internal fun aviation_distance_to_airspace_screen_squared_px(
    x: Float,
    y: Float,
    feature: AviationAirspaceFeature,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): Double? = aviation_distance_to_source_paths_screen_squared_px(
    x,
    y,
    feature.rings,
    viewport,
    lat_lon_to_world
)

internal fun aviation_distance_to_source_paths_screen_squared_px(
    x: Float,
    y: Float,
    source_paths: List<List<AviationLayerPoint>>,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): Double? {
    var best: Double? = null
    source_paths.forEach { source_path ->
        val points = source_path_screen_points(source_path, viewport, lat_lon_to_world)
        if (points.size < 2) return@forEach
        for (index in 1 until points.size) {
            val distance = wrapped_point_to_segment_distance_squared_px(
                x,
                y,
                points[index - 1],
                points[index],
                viewport
            )
            best = min(best ?: distance, distance)
        }
    }
    return best
}

private fun source_path_screen_points(
    source: List<AviationLayerPoint>,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): List<ScreenPoint> {
    val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
    if (!world_span.isFinite() || world_span <= 0.0) return emptyList()
    val result = ArrayList<ScreenPoint>(source.size)
    source.forEach { source_point ->
        val projected = project_aviation_source_point_to_screen(
            source_point,
            viewport,
            lat_lon_to_world
        ) ?: return@forEach
        var x = projected.x.toDouble()
        val previous_x = result.lastOrNull()?.x?.toDouble()
        if (previous_x != null) {
            while (x - previous_x > world_span / 2.0) x -= world_span
            while (x - previous_x < -world_span / 2.0) x += world_span
        }
        result += ScreenPoint(x.toFloat(), projected.y)
    }
    return result
}

private fun wrapped_point_to_segment_distance_squared_px(
    x: Float,
    y: Float,
    start: ScreenPoint,
    end: ScreenPoint,
    viewport: Viewport
): Double {
    val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
    var best = Double.POSITIVE_INFINITY
    for (world_copy in -2..2) {
        val shift = world_copy * world_span
        best = min(
            best,
            point_to_segment_distance_squared_px(
                x.toDouble(),
                y.toDouble(),
                start.x.toDouble() + shift,
                start.y.toDouble(),
                end.x.toDouble() + shift,
                end.y.toDouble()
            )
        )
    }
    return best
}

private fun point_to_segment_distance_squared_px(
    x: Double,
    y: Double,
    start_x: Double,
    start_y: Double,
    end_x: Double,
    end_y: Double
): Double {
    val dx = end_x - start_x
    val dy = end_y - start_y
    val length_squared = dx * dx + dy * dy
    if (length_squared <= 0.0001) {
        val px = x - start_x
        val py = y - start_y
        return px * px + py * py
    }
    val t = (((x - start_x) * dx + (y - start_y) * dy) / length_squared)
        .coerceIn(0.0, 1.0)
    val px = x - (start_x + dx * t)
    val py = y - (start_y + dy * t)
    return px * px + py * py
}

internal fun aviation_point_distance_squared_px(
    x: Float,
    y: Float,
    point: ScreenPoint
): Double {
    val dx = x.toDouble() - point.x
    val dy = y.toDouble() - point.y
    return dx * dx + dy * dy
}
