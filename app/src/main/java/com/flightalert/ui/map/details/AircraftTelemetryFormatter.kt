package com.flightalert.ui.map.details

import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.MapMeasurementFormatter
import com.flightalert.ui.map.traffic.AircraftPositionProjector
import java.util.Locale

internal class AircraftTelemetryFormatter(
    private val measurement_formatter: MapMeasurementFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val loading_or_unavailable: (Boolean) -> String,
    private val now_epoch_seconds: () -> Double
) {
    fun aircraft_label_detail(aircraft: Aircraft): String {
        val altitude = aircraft.altitude_m?.let { altitude_value(it) } ?: "alt n/a"
        return "${distance(reported_distance_meters(aircraft))}  $altitude"
    }

    fun aircraft_detail(aircraft: Aircraft): String {
        return "${distance(reported_distance_meters(aircraft))}  ${altitude_value(aircraft.altitude_m)}"
    }

    fun distance(meters: Double): String = measurement_formatter.format_distance(meters)

    fun altitude_value(meters: Double?): String = measurement_formatter.format_altitude(meters)

    fun accuracy(meters: Double): String = measurement_formatter.format_accuracy(meters)

    fun speed_value(ms: Double?): String = measurement_formatter.format_speed(ms)

    fun aviation_speed(ms: Double?, loading: Boolean = false): String {
        ms ?: return loading_or_unavailable(loading)
        val knots = ms / KNOTS_TO_METERS_PER_SECOND
        val display = speed_value(ms)
        return String.format(Locale.US, "%.0f kt / %s", knots, display)
    }

    fun track(degrees: Double?): String = measurement_formatter.format_track(degrees)

    fun vertical_rate(ms: Double?): String = measurement_formatter.format_vertical_rate(ms)

    fun telemetry_value(value: String?): String {
        return value?.trim()?.takeIf { it.isNotBlank() } ?: "Unavailable"
    }

    fun source_type(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = normalized.lowercase(Locale.US).replace("-", "_")
        return when {
            "adsb" in compact || "ads_b" in compact -> "ADS-B"
            "mlat" in compact -> "MLAT"
            "tisb" in compact || "tis_b" in compact -> "TIS-B"
            else -> normalized.uppercase(Locale.US)
        }
    }

    fun degrees_decimal(degrees: Double?, decimals: Int = 0, loading: Boolean = false): String {
        degrees ?: return loading_or_unavailable(loading)
        return String.format(Locale.US, "%.${decimals}f deg", degrees)
    }

    fun signed_degrees(degrees: Double?): String {
        degrees ?: return "Unavailable"
        return String.format(Locale.US, "%+.1f deg", degrees)
    }

    fun track_rate(degrees_per_second: Double?): String {
        degrees_per_second ?: return "Unavailable"
        return String.format(Locale.US, "%+.2f deg/s", degrees_per_second)
    }

    fun temperature_pair(tat_c: Double?, oat_c: Double?, loading: Boolean = false): String {
        return when {
            tat_c != null && oat_c != null -> String.format(Locale.US, "%.0f / %.0f C", tat_c, oat_c)
            tat_c != null -> String.format(Locale.US, "TAT %.0f C", tat_c)
            oat_c != null -> String.format(Locale.US, "OAT %.0f C", oat_c)
            else -> loading_or_unavailable(loading)
        }
    }

    fun mach(mach: Double?): String {
        mach ?: return "Unavailable"
        return String.format(Locale.US, "%.3f", mach)
    }

    fun pressure(hpa: Double?): String {
        hpa ?: return "Unavailable"
        return String.format(Locale.US, "%.1f hPa", hpa)
    }

    fun reported_position(aircraft: Aircraft): String {
        val reported = AircraftPositionProjector.reported_position(aircraft)
        return String.format(Locale.US, "%.4f, %.4f", reported.lat, reported.lon)
    }

    fun age(aircraft: Aircraft): String {
        val age = AircraftPositionProjector.contact_age_seconds(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_seconds()
        ) ?: return "Age unavailable"
        return "${age.toLong()}s old"
    }

    fun feet_setting(feet: Float): String = measurement_formatter.format_feet_setting(feet)

    private companion object {
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
    }
}
