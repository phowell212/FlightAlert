@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.TrackPoint
import com.flightalert.aircraft.projected_display_position
import com.flightalert.map.Bounds
import com.flightalert.map.GeoPoint
import java.util.Locale


class SelectedFlightPathController(
    private val now_epoch_seconds: () -> Double,
    private val max_projection_seconds: Double,
    private val path_trace_newer_than_feed_seconds: Long,
    private val max_trail_report_age_seconds: Double,
    private val on_display_endpoint_changed: () -> Unit
) {
    var selected_aircraft_id: String? = null
        private set

    private var selected_aircraft_key_cache: String? = null

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
        get() = selected_aircraft_key_cache

    fun trace_request_key(icao24: String): String? {
        return normalized_aircraft_key(icao24).takeIf { it.isNotEmpty() }
    }

    fun is_selected_key(key: String): Boolean {
        return selected_aircraft_key == normalized_aircraft_key(key)
    }

    // Clear visible path state until a matching real trace arrives.
    fun select_aircraft(aircraft: Aircraft) {
        val endpoint_was_visible = path_visible && has_path()
        selected_aircraft_id = aircraft.icao24
        selected_aircraft_key_cache = aircraft.icao_key
        selected_aircraft_snapshot = aircraft
        clear_trace_state()
        if (endpoint_was_visible) on_display_endpoint_changed()
    }

    fun clear_selection() {
        val endpoint_was_visible = path_visible && has_path()
        selected_aircraft_id = null
        selected_aircraft_key_cache = null
        selected_aircraft_snapshot = null
        clear_trace_state()
        if (endpoint_was_visible) on_display_endpoint_changed()
    }

    fun update_selected_aircraft(aircraft: Aircraft) {
        if (selected_aircraft_key == aircraft.icao_key) {
            selected_aircraft_snapshot = aircraft
        }
    }

    fun selected_snapshot_for_key(key: String): Aircraft? {
        val normalized = normalized_aircraft_key(key)
        return selected_aircraft_snapshot?.takeIf { it.icao_key == normalized }
    }

    // Ignore trace results that no longer match the selected aircraft.
    fun apply_trace_result(key: String, next_trace: FlightTrace?) {
        val normalized = normalized_aircraft_key(key)
        if (selected_aircraft_key != normalized) return
        val endpoint_was_visible = path_visible && has_path()
        selected_trace_aircraft_id = if (next_trace != null) normalized else null
        trace = next_trace
        path_visible = false
        previous_flights_visible = false
        if (endpoint_was_visible) on_display_endpoint_changed()
    }

    fun clear_trace_for_key(key: String) {
        if (!is_selected_key(key)) return
        clear_trace()
    }

    fun clear_trace() {
        val endpoint_was_visible = path_visible && has_path()
        clear_trace_state()
        if (endpoint_was_visible) on_display_endpoint_changed()
    }

    private fun clear_trace_state() {
        selected_trace_aircraft_id = null
        trace = null
        path_visible = false
        previous_flights_visible = false
    }

    fun close_visible_path(): Boolean {
        if (!path_visible) return false
        val endpoint_was_visible = has_path()
        path_visible = false
        previous_flights_visible = false
        if (endpoint_was_visible) on_display_endpoint_changed()
        return true
    }


    fun show_path(current_aircraft: Aircraft?): Boolean {
        if (!has_path()) return false
        val endpoint_was_visible = path_visible
        current_aircraft?.let { selected_aircraft_snapshot = it }
        path_visible = true
        if (!endpoint_was_visible) on_display_endpoint_changed()
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

    // Keep the selected sprite on the controller-approved trace or live endpoint.
    fun display_position(aircraft: Aircraft): GeoPoint {
        return selected_path_display_endpoint(aircraft) ?: projected_aircraft_position(aircraft)
    }

    fun projected_endpoint(): GeoPoint? {
        val aircraft = selected_aircraft_snapshot?.takeIf {
            selected_aircraft_key == it.icao_key
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
        if (!path_visible || selected_aircraft_key != aircraft.icao_key || !has_path()) return null
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

    private fun normalized_aircraft_key(value: String): String {
        return value.trim().lowercase(Locale.US)
    }

    private fun projected_aircraft_position(aircraft: Aircraft): GeoPoint {
        return projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_seconds(),
            max_projection_seconds = max_projection_seconds
        ).point
    }
}
