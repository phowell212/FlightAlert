@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.map

import android.location.Location
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

internal fun bounds_around_location(location: Location, radius_meters: Double): Bounds {
    val radius_km = radius_meters / 1000.0
    val lat_delta = radius_km / 111.0
    val lon_delta = radius_km / (111.0 * max(0.25, cos(Math.toRadians(location.latitude))))
    return Bounds(
        min_lat = (location.latitude - lat_delta).coerceIn(-90.0, 90.0),
        max_lat = (location.latitude + lat_delta).coerceIn(-90.0, 90.0),
        min_lon = (location.longitude - lon_delta).coerceIn(-180.0, 180.0),
        max_lon = (location.longitude + lon_delta).coerceIn(-180.0, 180.0)
    )
}

internal fun spherical_distance_meters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val lat_distance = Math.toRadians(lat2 - lat1)
    val lon_distance = Math.toRadians(lon2 - lon1)
    val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lon_distance / 2) * sin(lon_distance / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return 6_371_000.0 * c
}

internal fun clamped_haversine_distance_meters(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val lat1_rad = Math.toRadians(lat1)
    val lat2_rad = Math.toRadians(lat2)
    val delta_lat = Math.toRadians(lat2 - lat1)
    val delta_lon = Math.toRadians(lon2 - lon1)
    val haversine = sin(delta_lat / 2.0).pow(2.0) +
            cos(lat1_rad) * cos(lat2_rad) * sin(delta_lon / 2.0).pow(2.0)
    return 2.0 * 6_371_000.0 * atan2(sqrt(haversine), sqrt(max(0.0, 1.0 - haversine)))
}

internal fun is_inside_viewport(x: Float, y: Float, viewport: Viewport, padding: Float): Boolean {
    return x >= -padding &&
            x <= viewport.width + padding &&
            y >= -padding &&
            y <= viewport.height + padding
}

enum class ReferenceTileOverlay(
    val cache_key: String,
    val attribution: String,
    private val service_path: String
) {
    WORLD_TRANSPORTATION(
        cache_key = "esri_world_transportation",
        attribution = "Esri World Transportation",
        service_path = "Reference/World_Transportation"
    ),
    WORLD_BOUNDARIES_AND_PLACES(
        cache_key = "esri_world_boundaries_places",
        attribution = "Esri World Boundaries and Places, HERE, Garmin, OpenStreetMap contributors, GIS user community",
        service_path = "Reference/World_Boundaries_and_Places"
    );

    fun tile_url(z: Int, x: Int, y: Int): String {
        return "https://services.arcgisonline.com/ArcGIS/rest/services/$service_path/MapServer/tile/$z/$y/$x"
    }
}

enum class TileSource(
    val base_cache_key: String,
    val display_name: String,
    private val base_attribution: String,
    val max_native_zoom: Int
) {
    // Providers stay explicit so the map background remains auditable and never becomes fake imagery.
    STREET("carto_voyager", "Street map", "CARTO Voyager tiles, OpenStreetMap data", 19),
    SATELLITE("esri_world_imagery", "Satellite", "Esri World Imagery", 19);

    fun cache_key(labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) base_cache_key else "carto_voyager_nolabels"
            SATELLITE -> base_cache_key
        }
    }

    fun reference_overlay_layers(
        street_labels_enabled: Boolean,
        borders_enabled: Boolean
    ): List<ReferenceTileOverlay> {
        return when (this) {
            STREET -> emptyList()
            SATELLITE -> buildList {
                if (borders_enabled) add(ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES)
                if (street_labels_enabled) add(ReferenceTileOverlay.WORLD_TRANSPORTATION)
            }
        }
    }

    fun attribution_text(
        labels_enabled: Boolean,
        borders_enabled: Boolean = labels_enabled
    ): String {
        return when (this) {
            STREET -> if (labels_enabled) base_attribution else "CARTO no-label tiles, OpenStreetMap data"
            SATELLITE -> {
                val overlays = reference_overlay_layers(labels_enabled, borders_enabled)
                buildList {
                    add(base_attribution)
                    overlays.forEach { add(it.attribution) }
                }.joinToString("; ")
            }
        }
    }

    fun tile_url(z: Int, x: Int, y: Int, labels_enabled: Boolean): String {
        return when (this) {
            STREET -> if (labels_enabled) {
                "https://basemaps.cartocdn.com/rastertiles/voyager/$z/$x/$y.png"
            } else {
                "https://basemaps.cartocdn.com/rastertiles/voyager_nolabels/$z/$x/$y.png"
            }

            SATELLITE -> "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
        }
    }

}

enum class UnitSystem(
    val distance_label: String,
    val altitude_label: String,
    val speed_label: String
) {
    IMPERIAL("mi", "ft", "mph"),
    METRIC("km", "m", "km/h");

    fun distance_meters_to_display(meters: Double): Double {
        return if (this == IMPERIAL) meters / 1609.344 else meters / 1000.0
    }

    fun altitude_meters_to_display(meters: Double): Double {
        return if (this == IMPERIAL) meters * 3.28084 else meters
    }

    fun speed_meters_per_second_to_display(ms: Double): Double {
        return if (this == IMPERIAL) ms * 2.236936 else ms * 3.6
    }
}

class MapMeasurementFormatter(
    private val units: () -> UnitSystem
) {
    fun current_map_scale(target_pixels: Float, center_latitude: Double, zoom: Double): ScaleLabel {
        val meters_per_pixel =
            MapProjection.meters_per_pixel_at(center_latitude, zoom).coerceAtLeast(0.0001)
        val raw_meters = meters_per_pixel * target_pixels
        val scale_meters = if (units() == UnitSystem.IMPERIAL) {
            nice_imperial_scale_meters(raw_meters)
        } else {
            nice_metric_scale_meters(raw_meters)
        }
        return ScaleLabel(
            (scale_meters / meters_per_pixel).toFloat(),
            format_scale_distance(scale_meters)
        )
    }

    fun format_distance(meters: Double): String {
        val unit_system = units()
        return String.format(
            Locale.US,
            "%.1f %s",
            unit_system.distance_meters_to_display(meters),
            unit_system.distance_label
        )
    }

    fun format_altitude(meters: Double?): String {
        val unit_system = units()
        return meters?.let {
            String.format(
                Locale.US,
                "%.0f %s",
                unit_system.altitude_meters_to_display(it),
                unit_system.altitude_label
            )
        } ?: "Unavailable"
    }

    fun format_accuracy(meters: Double): String {
        return if (units() == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.0f ft", meters * FEET_PER_METER)
        } else {
            String.format(Locale.US, "%.0f m", meters)
        }
    }

    fun format_speed(meters_per_second: Double?): String {
        val unit_system = units()
        return meters_per_second?.let {
            String.format(
                Locale.US,
                "%.0f %s",
                unit_system.speed_meters_per_second_to_display(it),
                unit_system.speed_label
            )
        } ?: "Unavailable"
    }

    fun format_track(degrees: Double?): String {
        return degrees?.let { String.format(Locale.US, "%.0f deg", it) } ?: "Unavailable"
    }

    fun format_vertical_rate(meters_per_second: Double?): String {
        return meters_per_second?.let {
            if (units() == UnitSystem.IMPERIAL) {
                String.format(Locale.US, "%+.0f ft/min", it * FEET_PER_MINUTE_PER_METER_SECOND)
            } else {
                String.format(Locale.US, "%+.1f m/s", it)
            }
        } ?: "Unavailable"
    }

    fun format_feet_setting(feet: Float): String {
        return if (units() == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.0f ft", feet)
        } else {
            String.format(Locale.US, "%.0f m", feet_to_meters(feet.toDouble()))
        }
    }

    private fun nice_imperial_scale_meters(raw_meters: Double): Double {
        val raw_feet = raw_meters * FEET_PER_METER
        return if (raw_feet < FEET_PER_MILE) {
            nice_scale_value(raw_feet).coerceAtLeast(1.0) / FEET_PER_METER
        } else {
            nice_scale_value(raw_feet / FEET_PER_MILE).coerceAtLeast(0.1) * METERS_PER_MILE
        }
    }

    private fun nice_metric_scale_meters(raw_meters: Double): Double {
        return if (raw_meters < 1000.0) {
            nice_scale_value(raw_meters).coerceAtLeast(1.0)
        } else {
            nice_scale_value(raw_meters / 1000.0).coerceAtLeast(0.1) * 1000.0
        }
    }

    private fun nice_scale_value(raw: Double): Double {
        if (raw <= 0.0 || raw.isNaN()) return 1.0
        val exponent = floor(log10(raw))
        val base = 10.0.pow(exponent)
        val fraction = raw / base
        val nice_fraction = when {
            fraction < 1.5 -> 1.0
            fraction < 3.5 -> 2.0
            fraction < 7.5 -> 5.0
            else -> 10.0
        }
        return nice_fraction * base
    }

    private fun format_scale_distance(meters: Double): String {
        return if (units() == UnitSystem.IMPERIAL) {
            val feet = meters * FEET_PER_METER
            if (feet < FEET_PER_MILE) {
                "${feet.roundToInt()} ft"
            } else {
                val miles = feet / FEET_PER_MILE
                if (miles < 10.0) String.format(
                    Locale.US,
                    "%.1f mi",
                    miles
                ) else "${miles.roundToInt()} mi"
            }
        } else if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            val km = meters / 1000.0
            if (km < 10.0) String.format(Locale.US, "%.1f km", km) else "${km.roundToInt()} km"
        }
    }

    private fun feet_to_meters(feet: Double): Double = feet / FEET_PER_METER

    private companion object {
        const val FEET_PER_METER = 3.28084
        const val FEET_PER_MILE = 5280.0
        const val METERS_PER_MILE = 1609.344
        const val FEET_PER_MINUTE_PER_METER_SECOND = 196.850394
    }
}

data class GeoPoint(val lat: Double, val lon: Double)

data class ScreenPoint(val x: Float, val y: Float)

data class WorldPoint(val x: Double, val y: Double)

data class Viewport(
    val zoom: Double,
    val center_x: Double,
    val center_y: Double,
    val width: Float,
    val height: Float
)

data class Bounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
)

data class ScaleLabel(val pixels: Float, val label: String)

object MapProjection {
    fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    fun meters_per_pixel_at(latitude: Double, zoom: Double): Double {
        return cos(Math.toRadians(latitude.coerceIn(-85.0, 85.0))) *
                EARTH_CIRCUMFERENCE_M /
                (TILE_SIZE * 2.0.pow(zoom))
    }

    fun lat_lon_to_world(lat: Double, lon: Double, zoom: Double): WorldPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val sin_lat = sin(Math.toRadians(lat.coerceIn(MERCATOR_MIN_LAT, MERCATOR_MAX_LAT)))
        val x = (lon + 180.0) / 360.0 * scale
        val y = (0.5 - ln((1.0 + sin_lat) / (1.0 - sin_lat)) / (4.0 * Math.PI)) * scale
        return WorldPoint(x, y)
    }

    fun world_to_lat_lon(x: Double, y: Double, zoom: Double): GeoPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val lon = x / scale * 360.0 - 180.0
        val n = Math.PI - 2.0 * Math.PI * y / scale
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(lat.coerceIn(MERCATOR_MIN_LAT, MERCATOR_MAX_LAT), normalize_longitude(lon))
    }

    fun destination_point(
        lat: Double,
        lon: Double,
        bearing_deg: Double,
        distance_m: Double
    ): GeoPoint {
        val angular_distance = distance_m / EARTH_RADIUS_M
        val bearing = Math.toRadians(bearing_deg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 =
            asin(sin(lat1) * cos(angular_distance) + cos(lat1) * sin(angular_distance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular_distance) * cos(lat1),
            cos(angular_distance) - sin(lat1) * sin(lat2)
        )
        return GeoPoint(Math.toDegrees(lat2), normalize_longitude(Math.toDegrees(lon2)))
    }

    fun normalize_longitude(lon: Double): Double {
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    }

    const val EARTH_CIRCUMFERENCE_M = 40075016.686

    private const val EARTH_RADIUS_M = 6371000.0
    private const val TILE_SIZE = 256.0
    private const val MERCATOR_MIN_LAT = -85.05112878
    private const val MERCATOR_MAX_LAT = 85.05112878
}
