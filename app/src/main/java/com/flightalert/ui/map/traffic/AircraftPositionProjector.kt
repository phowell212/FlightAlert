package com.flightalert.ui.map.traffic

import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.GeoPoint

data class AircraftDisplayPosition(
    val point: GeoPoint,
    val projected: Boolean
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
        val speed = aircraft.velocity_ms
        val track = aircraft.track_deg
        val report_time = aircraft.position_time_sec ?: aircraft.last_contact_sec
        val reported = reported_position(aircraft)
        if (speed == null || track == null || report_time == null || aircraft.on_ground == true) {
            return AircraftDisplayPosition(reported, projected = false)
        }

        val elapsed = (now_epoch_sec - report_time).coerceIn(0.0, max_projection_seconds)
        if (elapsed <= 0.0 || speed <= 0.5) {
            return AircraftDisplayPosition(reported, projected = false)
        }

        return AircraftDisplayPosition(
            point = MapProjection.destination_point(aircraft.lat, aircraft.lon, track, speed * elapsed),
            projected = true
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
}
