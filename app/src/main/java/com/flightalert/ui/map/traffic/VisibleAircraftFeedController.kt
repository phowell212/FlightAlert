package com.flightalert.ui.map.traffic

import android.os.SystemClock
import android.util.Log
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedResult
import com.flightalert.data.FeedStatus
import com.flightalert.data.web.GlobeBinCraftAircraftSource
import com.flightalert.settings.FlightAlertSettings
import java.util.Locale
import java.util.concurrent.Executor

data class VisibleAircraftRequest(
    val feed_bounds: FeedBounds,
    val safety_api_bounds: FeedBounds?,
    val own_lat: Double,
    val own_lon: Double,
    val center_lat: Double,
    val center_lon: Double,
    val zoom: Double,
    val feed_mode: FlightAlertSettings.AircraftFeedMode,
    val exact_search: String?
)

class VisibleAircraftFeedController(
    private val aircraft_traffic_feed: AircraftTrafficFeed,
    private val globe_source: GlobeBinCraftAircraftSource?,
    private val executor: Executor,
    private val post_to_main: (() -> Unit) -> Unit,
    private val post_delayed: (() -> Unit, Long) -> Unit,
    private val has_location: () -> Boolean,
    private val has_usable_viewport: () -> Boolean,
    private val build_request: () -> VisibleAircraftRequest,
    private val current_total_aircraft: () -> Int,
    private val should_defer_for_interaction: (Long) -> Boolean,
    private val delay_after_interaction: (Long) -> Long,
    private val set_aircraft_status: (String) -> Unit,
    private val request_redraw: () -> Unit,
    private val apply_result: (FeedResult, Long, String) -> Unit,
    private val refresh_ms: Long,
    private val force_refresh_ms: Long,
    private val in_flight_retry_ms: Long,
    private val map_interaction_refresh_delay_ms: Long,
    private val hybrid_supplement_retry_ms: Long,
    private val globe_snapshot_refresh_ms: Long,
    private val api_grace_ms: Long,
    private val ready_aircraft_min: Int
) {
    private var fetch_in_flight = false
    private var refresh_scheduled = false
    private var scheduled_refresh_force = false
    private var last_fetch_elapsed_ms = 0L
    private var last_globe_snapshot_publish_elapsed_ms = 0L
    private var fetch_token = 0L
    private var fetch_signature: String? = null
    private var waiting_for_viewport = false

    // One request may publish intermediate and final real feed results, but only the newest token can affect the map.
    fun request_if_needed(force: Boolean = false) {
        if (!has_location()) return
        if (!has_usable_viewport()) {
            waiting_for_viewport = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (force && should_defer_for_interaction(now)) {
            schedule_refresh(delay_after_interaction(now), force = true)
            return
        }
        if (fetch_in_flight) {
            schedule_refresh(in_flight_retry_ms, force)
            return
        }
        val min_fetch_interval_ms = if (force) force_refresh_ms else refresh_ms
        if (now - last_fetch_elapsed_ms < min_fetch_interval_ms) {
            if (force) schedule_refresh(min_fetch_interval_ms - (now - last_fetch_elapsed_ms), true)
            return
        }

        fetch_in_flight = true
        last_fetch_elapsed_ms = now
        val request = build_request()
        val token = ++fetch_token
        val signature = request_signature(request)
        fetch_signature = signature
        aircraft_traffic_feed.update_viewport(
            request.feed_bounds,
            request.center_lat,
            request.center_lon,
            request.zoom,
            request.feed_mode
        )
        val publish_intermediate_results = current_total_aircraft() < ready_aircraft_min
        executor.execute {
            try {
                aircraft_traffic_feed.fetch_aircraft(
                    feed_bounds = request.feed_bounds,
                    safety_api_bounds = request.safety_api_bounds,
                    own_lat = request.own_lat,
                    own_lon = request.own_lon,
                    exact_search = request.exact_search,
                    feed_mode = request.feed_mode,
                    on_intermediate_result = { result ->
                        if (publish_intermediate_results) {
                            apply_result(result, token, signature)
                        }
                    }
                )?.let { result ->
                    apply_result(result, token, signature)
                }
            } catch (_: Exception) {
                if (is_current_token(token)) {
                    post_to_main {
                        if (is_current_token(token)) {
                            set_aircraft_status("Aircraft feed unavailable")
                            Log.d(TAG, "Aircraft feed request failed")
                        }
                    }
                }
            } finally {
                post_to_main {
                    if (is_current_token(token)) {
                        fetch_in_flight = false
                        request_redraw()
                    }
                }
            }
        }
    }

    fun request_after_map_interaction() {
        if (current_total_aircraft() < ready_aircraft_min) {
            request_if_needed(force = true)
        } else {
            schedule_refresh(map_interaction_refresh_delay_ms, force = true)
        }
    }

    fun publish_startup_globe_snapshot_if_needed(feed_mode: FlightAlertSettings.AircraftFeedMode) {
        publish_globe_snapshot_update_if_useful(feed_mode)
    }

    fun publish_globe_snapshot_update_if_useful(feed_mode: FlightAlertSettings.AircraftFeedMode) {
        val source = globe_source ?: return
        if (!feed_mode.uses_globe) return
        if (!has_location() || !has_usable_viewport()) return
        val now = SystemClock.elapsedRealtime()
        val startup_publish = current_total_aircraft() < ready_aircraft_min
        if (!startup_publish && fetch_in_flight && now - last_fetch_elapsed_ms < api_grace_ms) return
        if (!startup_publish &&
            now - last_globe_snapshot_publish_elapsed_ms < globe_publish_interval_floor_ms()
        ) {
            return
        }
        val request = build_request()
        val signature = request_signature(request)
        val publish_while_fetching = fetch_in_flight
        val token = if (publish_while_fetching && fetch_token > 0L) fetch_token else ++fetch_token
        val result_signature = if (publish_while_fetching) fetch_signature ?: signature else signature
        if (!publish_while_fetching) {
            fetch_signature = signature
        }
        last_globe_snapshot_publish_elapsed_ms = now
        executor.execute {
            val result = source.latest_snapshot(
                request.feed_bounds,
                request.own_lat,
                request.own_lon,
                request.exact_search
            ) ?: return@execute
            if (result.status == FeedStatus.OK) {
                if (publish_while_fetching) {
                    post_to_main {
                        if (fetch_in_flight && is_current_token(token)) {
                            apply_result(result, token, result_signature)
                        }
                    }
                } else {
                    apply_result(result, token, result_signature)
                }
            }
        }
    }

    fun request_deferred_refresh() {
        if (!waiting_for_viewport || !has_location() || !has_usable_viewport()) return
        waiting_for_viewport = false
        request_if_needed(force = true)
    }

    fun schedule_hybrid_supplement_refresh() {
        schedule_refresh(hybrid_supplement_retry_ms, force = true)
    }

    fun schedule_refresh(delay_ms: Long, force: Boolean) {
        scheduled_refresh_force = scheduled_refresh_force || force
        if (refresh_scheduled) return
        refresh_scheduled = true
        post_delayed({
            val should_force = scheduled_refresh_force
            refresh_scheduled = false
            scheduled_refresh_force = false
            request_if_needed(force = should_force)
        }, delay_ms.coerceAtLeast(0L))
    }

    fun is_current_fetch(token: Long, signature: String): Boolean {
        return fetch_token == token || fetch_signature == signature
    }

    fun is_current_token(token: Long): Boolean {
        return fetch_token == token
    }

    private fun request_signature(request: VisibleAircraftRequest): String {
        return listOf(
            request.feed_mode.name,
            request.exact_search.orEmpty(),
            "%.4f".format(Locale.US, request.feed_bounds.min_lat),
            "%.4f".format(Locale.US, request.feed_bounds.min_lon),
            "%.4f".format(Locale.US, request.feed_bounds.max_lat),
            "%.4f".format(Locale.US, request.feed_bounds.max_lon)
        ).joinToString("|")
    }

    private fun globe_publish_interval_floor_ms(): Long {
        return (globe_snapshot_refresh_ms - GLOBE_SNAPSHOT_CADENCE_JITTER_MS).coerceAtLeast(0L)
    }

    private companion object {
        const val TAG = "FlightAlert"
        const val GLOBE_SNAPSHOT_CADENCE_JITTER_MS = 150L
    }
}
