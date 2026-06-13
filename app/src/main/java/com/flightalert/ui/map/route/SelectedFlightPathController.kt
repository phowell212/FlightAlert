package com.flightalert.ui.map.route

import com.flightalert.data.FlightTrace
import com.flightalert.data.TraceSegment
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.Bounds
import com.flightalert.ui.map.GeoPoint
import com.flightalert.ui.map.traffic.AircraftPositionProjector
import java.util.Locale

// Owns selected aircraft trace state so FlightMapView can ask what to draw without editing trace internals.
class SelectedFlightPathController(
    private val now_epoch_seconds: () -> Double,
    private val max_projection_seconds: Double,
    private val path_trace_newer_than_feed_seconds: Long,
    private val max_trail_report_age_seconds: Double
) {
    var selected_aircraft_id: String? = null
        private set

    var selected_aircraft_snapshot: Aircraft? = null
        private set

    var selected_trace_aircraft_id: String? = null
        private set

    var trace: FlightTrace? = null
        private set

    var path_visible: Boolean = false
        private set

    var previous_flights_visible: Boolean = false
        private set

    val selected_aircraft_key: String?
        get() = selected_aircraft_id?.lowercase(Locale.US)

    fun trace_request_key(icao24: String): String? {
        return icao24.trim().lowercase(Locale.US).takeIf { it.isNotEmpty() }
    }

    fun is_selected_key(key: String): Boolean {
        return selected_aircraft_key == key.lowercase(Locale.US)
    }

    // Selection clears visible path state first; a path returns only after a matching real trace is fetched.
    fun select_aircraft(aircraft: Aircraft) {
        selected_aircraft_id = aircraft.icao24
        selected_aircraft_snapshot = aircraft
        clear_trace()
    }

    fun clear_selection() {
        selected_aircraft_id = null
        selected_aircraft_snapshot = null
        clear_trace()
    }

    fun update_selected_aircraft(aircraft: Aircraft) {
        if (selected_aircraft_key == aircraft.icao24.lowercase(Locale.US)) {
            selected_aircraft_snapshot = aircraft
        }
    }

    fun selected_snapshot_for_key(key: String): Aircraft? {
        return selected_aircraft_snapshot?.takeIf { it.icao24.lowercase(Locale.US) == key.lowercase(Locale.US) }
    }

    // Accept trace responses only for the current key so old aircraft paths cannot appear.
    fun apply_trace_result(key: String, next_trace: FlightTrace?) {
        if (!is_selected_key(key)) return
        selected_trace_aircraft_id = if (next_trace != null) key.lowercase(Locale.US) else null
        trace = next_trace
        path_visible = false
        previous_flights_visible = false
    }

    fun clear_trace_for_key(key: String) {
        if (!is_selected_key(key)) return
        clear_trace()
    }

    fun clear_trace() {
        selected_trace_aircraft_id = null
        trace = null
        path_visible = false
        previous_flights_visible = false
    }

    fun close_visible_path(): Boolean {
        if (!path_visible) return false
        path_visible = false
        previous_flights_visible = false
        return true
    }

    // Show the path only when the selected aircraft has real current trace segments.
    fun show_path(current_aircraft: Aircraft?): Boolean {
        if (!has_path()) return false
        current_aircraft?.let { selected_aircraft_snapshot = it }
        path_visible = true
        return true
    }

    fun toggle_previous_flights(): Boolean {
        if (!should_show_previous_flights_button()) return false
        previous_flights_visible = !previous_flights_visible
        return previous_flights_visible
    }

    fun has_path(): Boolean {
        val id = selected_aircraft_key ?: return false
        val current_trace = trace ?: return false
        return selected_trace_aircraft_id == id && current_trace.segments.isNotEmpty()
    }

    fun has_previous_flights(): Boolean {
        val id = selected_aircraft_key ?: return false
        val current_trace = trace ?: return false
        return selected_trace_aircraft_id == id && current_trace.previous_segments.isNotEmpty()
    }

    fun should_show_clear_path_button(): Boolean {
        return path_visible && has_path()
    }

    fun should_show_previous_flights_button(): Boolean {
        return path_visible && has_previous_flights()
    }

    // Current-flight segments are the default path; previous legs are a separate opt-in context.
    fun selected_segments(visible_only: Boolean): List<TraceSegment>? {
        if (visible_only && !path_visible) return null
        if (!has_path()) return null
        return trace?.segments?.takeIf { it.isNotEmpty() }
    }

    fun previous_segments(visible_only: Boolean): List<TraceSegment>? {
        if (visible_only && (!path_visible || !previous_flights_visible)) return null
        if (!has_previous_flights()) return null
        return trace?.previous_segments?.takeIf { it.isNotEmpty() }
    }

    // Fit uses only real trace points and the projected endpoint already approved by this controller.
    fun bounds(): Bounds? {
        val points = selected_path_points()?.toMutableList() ?: return null
        if (previous_flights_visible) previous_flight_points()?.let { points += it }
        projected_endpoint()?.let { points += it }
        if (points.size < 2) return null
        return Bounds(
            min_lat = points.minOf { it.lat },
            min_lon = points.minOf { it.lon },
            max_lat = points.maxOf { it.lat },
            max_lon = points.maxOf { it.lon }
        )
    }

    // When a path is visible, pin the selected sprite to the trace/live endpoint chosen for that aircraft.
    fun display_position(aircraft: Aircraft): GeoPoint {
        return selected_path_display_endpoint(aircraft) ?: projected_aircraft_position(aircraft)
    }

    fun projected_endpoint(): GeoPoint? {
        val aircraft = selected_aircraft_snapshot?.takeIf {
            selected_aircraft_key == it.icao24.lowercase(Locale.US)
        } ?: return null
        if (!path_visible || !has_path()) return null
        val report_sec = aircraft.position_time_sec ?: aircraft.last_contact_sec ?: return null
        val age_seconds = now_epoch_seconds() - report_sec
        if (age_seconds > max_trail_report_age_seconds) return null
        return selected_path_display_endpoint(aircraft) ?: projected_aircraft_position(aircraft)
    }

    private fun selected_path_points(): List<GeoPoint>? {
        return selected_segments(visible_only = false)
            ?.flatMap { segment -> segment.points.map { GeoPoint(it.lat, it.lon) } }
            ?.takeIf { it.size >= 2 }
    }

    private fun previous_flight_points(): List<GeoPoint>? {
        return previous_segments(visible_only = false)
            ?.flatMap { segment -> segment.points.map { GeoPoint(it.lat, it.lon) } }
            ?.takeIf { it.size >= 2 }
    }

    private fun selected_path_display_endpoint(aircraft: Aircraft): GeoPoint? {
        if (!path_visible || selected_aircraft_key != aircraft.icao24.lowercase(Locale.US) || !has_path()) return null
        val last = selected_segment_points()?.maxByOrNull { it.epoch_sec } ?: return null
        val report_sec = (aircraft.position_time_sec ?: aircraft.last_contact_sec)?.toLong()
        if (report_sec == null || last.epoch_sec > report_sec + path_trace_newer_than_feed_seconds) {
            return GeoPoint(last.lat, last.lon)
        }
        return projected_aircraft_position(aircraft)
    }

    private fun selected_segment_points(): List<TrackPoint>? {
        return selected_segments(visible_only = false)
            ?.flatMap { it.points }
            ?.takeIf { it.size >= 2 }
    }

    private fun projected_aircraft_position(aircraft: Aircraft): GeoPoint {
        return AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_seconds(),
            max_projection_seconds = max_projection_seconds
        ).point
    }
}
