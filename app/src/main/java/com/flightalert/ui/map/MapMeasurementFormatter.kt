package com.flightalert.ui.map

import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.ScaleLabel
import com.flightalert.ui.map.UnitSystem
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

class MapMeasurementFormatter(
    private val units: () -> UnitSystem
) {
    fun current_map_scale(target_pixels: Float, center_latitude: Double, zoom: Double): ScaleLabel {
        val meters_per_pixel = MapProjection.meters_per_pixel_at(center_latitude, zoom).coerceAtLeast(0.0001)
        val raw_meters = meters_per_pixel * target_pixels
        val scale_meters = if (units() == UnitSystem.IMPERIAL) {
            nice_imperial_scale_meters(raw_meters)
        } else {
            nice_metric_scale_meters(raw_meters)
        }
        return ScaleLabel((scale_meters / meters_per_pixel).toFloat(), format_scale_distance(scale_meters))
    }

    fun format_distance(meters: Double): String {
        val unit_system = units()
        return String.format(Locale.US, "%.1f %s", unit_system.distance_meters_to_display(meters), unit_system.distance_label)
    }

    fun format_altitude(meters: Double?): String {
        val unit_system = units()
        return meters?.let {
            String.format(Locale.US, "%.0f %s", unit_system.altitude_meters_to_display(it), unit_system.altitude_label)
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
            String.format(Locale.US, "%.0f %s", unit_system.speed_meters_per_second_to_display(it), unit_system.speed_label)
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
                if (miles < 10.0) String.format(Locale.US, "%.1f mi", miles) else "${miles.roundToInt()} mi"
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
