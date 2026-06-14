package com.flightalert.ui.map.traffic

import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.GeoPoint

data class AircraftDisplayPosition(
    val point: GeoPoint,
    val projected: Boolean,
    val motion_remaining_seconds: Double = 0.0
)

data class AircraftProjectionMotion(
    val speed_ms: Double,
    val track_deg: Double,
    val elapsed_seconds: Double,
    val remaining_seconds: Double
)

object AircraftPositionProjector {
    fun reported_position(aircraft: Aircraft): GeoPoint {
        return GeoPoint(aircraft.lat, aircraft.lon)
    }

    fun projected_display_position(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        max_projection_seconds: Double
    ): AircraftDisplayPosition {
        val reported = reported_position(aircraft)
        val motion = projection_motion(aircraft, now_epoch_sec, max_projection_seconds)
        if (motion == null) {
            return AircraftDisplayPosition(reported, projected = false)
        }

        if (motion.elapsed_seconds <= 0.0) {
            return AircraftDisplayPosition(
                point = reported,
                projected = false,
                motion_remaining_seconds = motion.remaining_seconds
            )
        }

        return AircraftDisplayPosition(
            point = MapProjection.destination_point(
                aircraft.lat,
                aircraft.lon,
                motion.track_deg,
                motion.speed_ms * motion.elapsed_seconds
            ),
            projected = true,
            motion_remaining_seconds = motion.remaining_seconds
        )
    }

    fun projection_motion(
        aircraft: Aircraft,
        now_epoch_sec: Double,
        max_projection_seconds: Double
    ): AircraftProjectionMotion? {
        if (aircraft.on_ground == true || !aircraft.lat.isFinite() || !aircraft.lon.isFinite()) return null
        if (!now_epoch_sec.isFinite() || !max_projection_seconds.isFinite() || max_projection_seconds <= 0.0) return null
        val speed = aircraft.velocity_ms?.takeIf { it.isFinite() && it > MIN_PROJECTABLE_SPEED_MS && it <= MAX_PROJECTABLE_SPEED_MS } ?: return null
        val track = normalized_track_degrees(aircraft.track_deg) ?: return null
        val report_time = aircraft.position_time_sec?.takeIf { it.isFinite() } ?: return null
        val raw_elapsed = now_epoch_sec - report_time
        if (!raw_elapsed.isFinite()) return null
        val elapsed = raw_elapsed.coerceAtLeast(0.0)
        val remaining = if (raw_elapsed < 0.0) {
            0.0
        } else {
            max_projection_seconds
        }
        return AircraftProjectionMotion(
            speed_ms = speed,
            track_deg = track,
            elapsed_seconds = elapsed,
            remaining_seconds = remaining
        )
    }

    fun reported_distance_meters(aircraft: Aircraft, own_lat: Double?, own_lon: Double?): Double {
        if (own_lat == null || own_lon == null) return aircraft.distance_m
        return MapProjection.distance_meters(own_lat, own_lon, aircraft.lat, aircraft.lon)
    }

    fun contact_age_seconds(aircraft: Aircraft, now_epoch_sec: Double): Double? {
        val contact = aircraft.last_contact_sec ?: aircraft.position_time_sec ?: return null
        return (now_epoch_sec - contact).coerceAtLeast(0.0)
    }

    fun to_track_point(aircraft: Aircraft): TrackPoint? {
        val epoch_sec = (aircraft.position_time_sec ?: aircraft.last_contact_sec)?.toLong() ?: return null
        if (epoch_sec <= 0L) return null
        return TrackPoint(
            lat = aircraft.lat,
            lon = aircraft.lon,
            epoch_sec = epoch_sec,
            altitude_m = aircraft.altitude_m,
            track_deg = aircraft.track_deg,
            on_ground = aircraft.on_ground
        )
    }

    private fun normalized_track_degrees(track_deg: Double?): Double? {
        val track = track_deg?.takeIf { it.isFinite() } ?: return null
        return ((track % 360.0) + 360.0) % 360.0
    }

    private const val MIN_PROJECTABLE_SPEED_MS = 0.5
    private const val MAX_PROJECTABLE_SPEED_MS = 1_200.0
}
