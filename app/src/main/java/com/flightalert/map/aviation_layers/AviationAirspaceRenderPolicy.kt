@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.map.aviation_layers

import kotlin.math.max
import kotlin.math.min

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