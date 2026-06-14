package com.flightalert.ui.map.details

import android.os.SystemClock
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.DETAILS_PREFETCH_IDLE_DELAY_MS
import com.flightalert.ui.map.DETAILS_PREFETCH_INTERVAL_MS
import com.flightalert.ui.map.DETAILS_PREFETCH_MAX_IN_FLIGHT
import com.flightalert.ui.map.DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES
import com.flightalert.ui.map.DETAILS_PREFETCH_MIN_ZOOM
import com.flightalert.ui.map.DETAILS_PREFETCH_SCAN_LIMIT
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.Viewport
import com.flightalert.ui.map.render.TrafficOverlayState
import com.flightalert.ui.map.traffic.CachedTraffic
import com.flightalert.ui.map.traffic.TrafficSpatialEntry
import kotlin.math.pow

// Picks likely next-tapped aircraft for warm details/photo requests while the map is idle.
internal class AircraftDetailsPrefetchPlanner(
    private val warm_requester: AircraftDetailsWarmRequester,
    private val displayed_aircraft: () -> Aircraft?,
    private val selected_aircraft_snapshot: () -> Aircraft?,
    private val cached_traffic: () -> CachedTraffic,
    private val cache_key: (Aircraft) -> String,
    private val traffic_query_padding_px: (Viewport) -> Float,
    private val screen_point_for_entry: (TrafficSpatialEntry, Viewport, Double, Double) -> ScreenPoint,
    private val screen_neighborhood_contains: (Float, Float, Boolean, Viewport) -> Boolean,
    private val now_epoch_seconds: () -> Double
) {
    fun schedule(
        state: TrafficOverlayState,
        details_open: Boolean,
        pinch_in_progress: Boolean,
        drag_started: Boolean,
        last_map_interaction_ms: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        if (details_open || state.viewport.zoom < DETAILS_PREFETCH_MIN_ZOOM) return
        if (pinch_in_progress || drag_started || now - last_map_interaction_ms < DETAILS_PREFETCH_IDLE_DELAY_MS) return
        if (warm_requester.in_flight_count >= DETAILS_PREFETCH_MAX_IN_FLIGHT) return
        if (now - warm_requester.last_prefetch_elapsed_ms < DETAILS_PREFETCH_INTERVAL_MS) return

        val candidate = prefetch_candidates(state)
            .firstOrNull { warm_requester.should_prefetch(it, now) }
            ?: return
        warm_requester.last_prefetch_elapsed_ms = now
        warm_requester.start_prefetch(candidate)
    }

    private fun prefetch_candidates(state: TrafficOverlayState): List<Aircraft> {
        val preferred = listOfNotNull(
            displayed_aircraft(),
            selected_aircraft_snapshot()
        ).distinctBy(cache_key)
        val center_x = state.viewport.width / 2f
        val center_y = state.viewport.height / 2f
        if (state.aircraft.isNotEmpty()) {
            val visible = state.aircraft
                .asSequence()
                .sortedBy { item ->
                    val dx = item.screen_point.x - center_x
                    val dy = item.screen_point.y - center_y
                    dx * dx + dy * dy
                }
                .map { it.aircraft }
                .distinctBy(cache_key)
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
                .toList()
            return (preferred + visible)
                .distinctBy(cache_key)
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        }

        val viewport = state.viewport
        val cache = cached_traffic()
        val scale = 2.0.pow(viewport.zoom)
        val now_epoch_sec = now_epoch_seconds()
        val candidates = ArrayList<Pair<Float, Aircraft>>(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        val seen = HashSet<String>()
        val query = cache.spatial_index.query(viewport, traffic_query_padding_px(viewport))
        for (entry in query) {
            if (candidates.size >= DETAILS_PREFETCH_SCAN_LIMIT) break
            val aircraft = entry.aircraft
            val key = cache_key(aircraft)
            if (!seen.add(key)) continue
            val screen = screen_point_for_entry(entry, viewport, scale, now_epoch_sec)
            if (!screen_neighborhood_contains(screen.x, screen.y, false, viewport)) continue
            val dx = screen.x - center_x
            val dy = screen.y - center_y
            candidates += dx * dx + dy * dy to aircraft
        }
        val visible = candidates
            .sortedBy { it.first }
            .asSequence()
            .map { it.second }
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
            .toList()
        return (preferred + visible)
            .distinctBy(cache_key)
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
    }
}
