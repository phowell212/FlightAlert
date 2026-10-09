package com.flightalert.fundamentals

import com.flightalert.map.MapProjection
import com.flightalert.map.Viewport
import com.flightalert.sources.FeedBounds
import kotlin.math.ln
import kotlin.math.pow

/** The baseline's entire camera: north-up Mercator, horizontal wrap, anchored pinch. */
class FundamentalsCamera(initialZoom: Double = 8.0) {
    private var zoom = if (initialZoom.isFinite()) initialZoom.coerceIn(3.0, 21.0) else 8.0
    private var x = 0.0
    private var y = 0.0

    fun center(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        val point = MapProjection.lat_lon_to_world(lat, lon, zoom)
        x = point.x
        y = point.y
    }

    fun viewport(width: Float, height: Float) = Viewport(zoom, x, y, width, height)

    /** Used by the user's sourced-path fit action, never by gesture validation. */
    fun setPathViewport(nextZoom: Double, lat: Double, lon: Double) {
        if (!nextZoom.isFinite() || !lat.isFinite() || !lon.isFinite()) return
        zoom = nextZoom.coerceIn(3.0, 21.0)
        center(lat, lon)
    }

    fun transform(width: Float, height: Float, factor: Double,
                  fromX: Double, fromY: Double, toX: Double, toY: Double) {
        if (!factor.isFinite() || factor <= 0 ||
            !fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()) return
        val nextZoom = (zoom + ln(factor) / ln(2.0)).coerceIn(3.0, 21.0)
        val scale = 2.0.pow(nextZoom - zoom)
        val worldSize = 256.0 * 2.0.pow(nextZoom)
        x = ((x - width / 2 + fromX) * scale + width / 2 - toX) % worldSize
        if (x < 0) x += worldSize
        y = ((y - height / 2 + fromY) * scale + height / 2 - toY).coerceIn(0.0, worldSize)
        zoom = nextZoom
    }

    fun bounds(width: Float, height: Float): FeedBounds {
        val topLeft = MapProjection.world_to_lat_lon(x - width / 2, y - height / 2, zoom)
        val bottomRight = MapProjection.world_to_lat_lon(x + width / 2, y + height / 2, zoom)
        val wholeWorld = width >= 256.0 * 2.0.pow(zoom)
        return FeedBounds(bottomRight.lat, if (wholeWorld) -180.0 else topLeft.lon,
            topLeft.lat, if (wholeWorld) 180.0 else bottomRight.lon)
    }
}
