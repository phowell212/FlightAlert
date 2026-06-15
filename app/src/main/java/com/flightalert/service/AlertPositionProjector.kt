package com.flightalert.service

import com.flightalert.settings.FlightAlertSettings
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ProjectedAlertPosition(
    val distance_meters: Double,
    val altitude_meters: Double?,
    val estimated: Boolean
)

object AlertPositionProjector {
    fun projected_alert_position(
        own_lat: Double,
        own_lon: Double,
        aircraft_lat: Double,
        aircraft_lon: Double,
        reported_distance_meters: Double,
        altitude_meters: Double?,
        velocity_ms: Double?,
        track_deg: Double?,
        vertical_rate_ms: Double?,
        position_time_sec: Double?,
        last_contact_sec: Double?,
        now_epoch_sec: Double,
        max_projection_seconds: Double = ALERT_PROJECTION_MAX_SECONDS
    ): ProjectedAlertPosition {
        val reported_distance = reported_distance(
            own_lat = own_lat,
            own_lon = own_lon,
            aircraft_lat = aircraft_lat,
            aircraft_lon = aircraft_lon,
            reported_distance_meters = reported_distance_meters
        )
        val age_seconds = projection_age_seconds(position_time_sec, last_contact_sec, now_epoch_sec, max_projection_seconds)
        if (age_seconds <= ALERT_PROJECTION_MIN_SECONDS || !has_projectable_motion(velocity_ms, track_deg)) {
            return ProjectedAlertPosition(reported_distance, altitude_meters, estimated = false)
        }
        val speed = velocity_ms ?: return ProjectedAlertPosition(reported_distance, altitude_meters, estimated = false)
        val track = normalized_degrees(track_deg) ?: return ProjectedAlertPosition(reported_distance, altitude_meters, estimated = false)
        val projected = advance_position(aircraft_lat, aircraft_lon, track, speed * age_seconds)
        return ProjectedAlertPosition(
            distance_meters = distance_meters(own_lat, own_lon, projected.lat, projected.lon),
            altitude_meters = projected_altitude_meters(altitude_meters, vertical_rate_ms, age_seconds),
            estimated = true
        )
    }

    fun contact_age_seconds(position_time_sec: Double?, last_contact_sec: Double?, now_epoch_sec: Double): Double? {
        val contact = last_contact_sec ?: position_time_sec ?: return null
        return (now_epoch_sec - contact).coerceAtLeast(0.0)
    }

    fun has_fresh_projectable_motion(
        velocity_ms: Double?,
        track_deg: Double?,
        position_time_sec: Double?,
        last_contact_sec: Double?,
        now_epoch_sec: Double,
        freshness_seconds: Double
    ): Boolean {
        val report_time = position_time_sec ?: last_contact_sec ?: return false
        if (now_epoch_sec - report_time > freshness_seconds) return false
        return has_projectable_motion(velocity_ms, track_deg)
    }

    fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val d_lat = Math.toRadians(lat2 - lat1)
        val d_lon = Math.toRadians(lon2 - lon1)
        val a = sin(d_lat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(d_lon / 2).pow(2.0)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun reported_distance(
        own_lat: Double,
        own_lon: Double,
        aircraft_lat: Double,
        aircraft_lon: Double,
        reported_distance_meters: Double
    ): Double {
        return if (reported_distance_meters.isFinite() && reported_distance_meters > 0.0) {
            reported_distance_meters
        } else {
            distance_meters(own_lat, own_lon, aircraft_lat, aircraft_lon)
        }
    }

    private fun projection_age_seconds(
        position_time_sec: Double?,
        last_contact_sec: Double?,
        now_epoch_sec: Double,
        max_projection_seconds: Double
    ): Double {
        val report_time = position_time_sec ?: last_contact_sec ?: return 0.0
        return (now_epoch_sec - report_time)
            .coerceAtLeast(0.0)
            .coerceAtMost(max_projection_seconds.coerceAtLeast(0.0))
    }

    private fun projected_altitude_meters(altitude_meters: Double?, vertical_rate_ms: Double?, age_seconds: Double): Double? {
        val altitude = altitude_meters ?: return null
        val vertical_rate = vertical_rate_ms
            ?.takeIf { it.isFinite() && abs(it) <= MAX_PROJECTABLE_VERTICAL_RATE_MS }
            ?: return altitude
        return altitude + vertical_rate * age_seconds
    }

    private fun has_projectable_motion(velocity_ms: Double?, track_deg: Double?): Boolean {
        val speed = velocity_ms ?: return false
        return speed.isFinite() &&
            speed in MIN_PROJECTABLE_ALERT_SPEED_MS..MAX_PROJECTABLE_ALERT_SPEED_MS &&
            normalized_degrees(track_deg) != null
    }

    private fun advance_position(lat: Double, lon: Double, track_degrees: Double, distance_meters: Double): GeoPosition {
        val angular_distance = distance_meters / EARTH_RADIUS_M
        val bearing = Math.toRadians(track_degrees)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(angular_distance) + cos(lat1) * sin(angular_distance) * cos(bearing))
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular_distance) * cos(lat1),
            cos(angular_distance) - sin(lat1) * sin(lat2)
        )
        return GeoPosition(
            lat = Math.toDegrees(lat2).coerceIn(-90.0, 90.0),
            lon = normalize_lon_degrees(Math.toDegrees(lon2))
        )
    }

    private fun normalized_degrees(value: Double?): Double? {
        val degrees = value?.takeIf { it.isFinite() } ?: return null
        return ((degrees % 360.0) + 360.0) % 360.0
    }

    private fun normalize_lon_degrees(value: Double): Double {
        return ((value + 540.0) % 360.0) - 180.0
    }

    private data class GeoPosition(val lat: Double, val lon: Double)

    const val ALERT_PROJECTION_MAX_SECONDS = FlightAlertSettings.AlertProjection.MAX_SECONDS
    const val ALERT_PROJECTION_MIN_SECONDS = FlightAlertSettings.AlertProjection.MIN_SECONDS
    const val MIN_PROJECTABLE_ALERT_SPEED_MS = FlightAlertSettings.AlertProjection.MIN_PROJECTABLE_SPEED_MS
    const val MAX_PROJECTABLE_ALERT_SPEED_MS = FlightAlertSettings.AlertProjection.MAX_PROJECTABLE_SPEED_MS
    const val MAX_PROJECTABLE_VERTICAL_RATE_MS = FlightAlertSettings.AlertProjection.MAX_PROJECTABLE_VERTICAL_RATE_MS
    const val EARTH_RADIUS_M = 6371000.0
}
