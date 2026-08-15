@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.MAX_ZOOM
import com.flightalert.MIN_ZOOM
import com.flightalert.PATH_FIT_CONTEXT_MULTIPLIER
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.Bounds
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.Viewport




internal class SelectedFlightPathViewportController(
    private val selected_path_controller: SelectedFlightPathController
) {
    fun fit_camera(width: Float, height: Float, usable: RectF): FlightPathCameraUpdate? {
        val bounds = selected_path_controller.bounds() ?: return null
        if (usable.width() <= 0f || usable.height() <= 0f) return null

        val top_left = MapProjection.lat_lon_to_world(bounds.max_lat, bounds.min_lon, 0.0)
        val bottom_right = MapProjection.lat_lon_to_world(bounds.min_lat, bounds.max_lon, 0.0)
        val path_width_at_zoom_zero = max(
            MIN_PATH_FIT_SPAN_ZOOM_ZERO_PX,
            abs(bottom_right.x - top_left.x)
        )
        val path_height_at_zoom_zero = max(
            MIN_PATH_FIT_SPAN_ZOOM_ZERO_PX,
            abs(bottom_right.y - top_left.y)
        )
        val width_fit = usable.width() / (path_width_at_zoom_zero * PATH_FIT_CONTEXT_MULTIPLIER)
        val height_fit = usable.height() / (path_height_at_zoom_zero * PATH_FIT_CONTEXT_MULTIPLIER)
        val zoom = (ln(min(width_fit, height_fit)) / ln(2.0)).coerceIn(
            MIN_ZOOM.toDouble(),
            MAX_ZOOM.toDouble()
        )

        val center_lat = (bounds.min_lat + bounds.max_lat) / 2.0
        val center_lon = MapProjection.normalize_longitude((bounds.min_lon + bounds.max_lon) / 2.0)
        val center_world = MapProjection.lat_lon_to_world(center_lat, center_lon, zoom)
        val center = MapProjection.world_to_lat_lon(
            center_world.x + width / 2.0 - usable.centerX(),
            center_world.y + height / 2.0 - usable.centerY(),
            zoom
        )
        return FlightPathCameraUpdate(zoom = zoom, center = center)
    }

    fun should_show_path_button(viewport: Viewport, usable: RectF): Boolean {
        if (!selected_path_controller.has_path()) return false
        if (!selected_path_controller.path_visible) return true
        val bounds = selected_path_controller.bounds() ?: return false
        return !path_bounds_visible(viewport, bounds, usable)
    }

    private fun path_bounds_visible(viewport: Viewport, bounds: Bounds, usable: RectF): Boolean {
        if (usable.width() <= 0f || usable.height() <= 0f) return false
        val corners = listOf(
            GeoPoint(bounds.min_lat, bounds.min_lon),
            GeoPoint(bounds.min_lat, bounds.max_lon),
            GeoPoint(bounds.max_lat, bounds.min_lon),
            GeoPoint(bounds.max_lat, bounds.max_lon)
        )
        return corners.all { point ->
            val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
            val sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
            val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
            usable.contains(sx, sy)
        }
    }

    private companion object {
        const val MIN_PATH_FIT_SPAN_ZOOM_ZERO_PX = 0.03
    }
}

