package com.flightalert.data.web

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedResult
import com.flightalert.data.FeedSource
import com.flightalert.data.FeedStatus
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// Direct Airplanes.Live globe inventory source backed by lightweight binCraft HTTP.
class GlobeBinCraftAircraftSource(private val user_agent: String) {
    var on_snapshot_updated: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val parser_executor = Executors.newSingleThreadExecutor()
    private val inventory_client = GlobeBinCraftClient(user_agent)
    private var host_resumed = false
    private var running = false

    @Volatile
    private var source_enabled = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var pending_viewport: GlobeViewport? = null
    private var viewport_generation = 0L

    @Volatile
    private var latest: GlobeSnapshot? = null

    @Volatile
    private var extraction_paused_until_elapsed_ms = 0L

    @Volatile
    private var fetch_in_progress = false

    private val fetcher = object : Runnable {
        override fun run() {
            fetch_inventory()
        }
    }

    fun start() {
        host_resumed = true
        start_if_allowed()
    }

    fun stop() {
        host_resumed = false
        stop_active(clear_snapshot = false)
    }

    fun destroy() {
        destroyed = true
        stop_active(clear_snapshot = true)
        parser_executor.shutdownNow()
    }

    fun set_enabled(enabled: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { set_enabled(enabled) }
            return
        }
        if (source_enabled == enabled) return
        source_enabled = enabled
        if (enabled) {
            start_if_allowed()
        } else {
            stop_active(clear_snapshot = true)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun update_viewport(bounds: FeedBounds, center_lat: Double, center_lon: Double, zoom: Double) {
        if (!source_enabled || destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { update_viewport(bounds, center_lat, center_lon, zoom) }
            return
        }
        val viewport = globe_inventory_viewport()
        val previous = pending_viewport
        if (previous != null && previous.matches(viewport)) return
        viewport_generation += 1L
        val next = viewport.copy(generation = viewport_generation)
        pending_viewport = next
        if (running) schedule_fetch(0L)
    }

    @Suppress("UNUSED_PARAMETER")
    fun latest_snapshot(bounds: FeedBounds, own_lat: Double, own_lon: Double, exact_search: String?): FeedResult? {
        if (!source_enabled || destroyed) return null
        val viewport = pending_viewport ?: return null
        val snapshot = latest ?: return null
        if (snapshot.viewport_generation != viewport.generation) return null
        if (SystemClock.elapsedRealtime() - snapshot.received_elapsed_ms > MAX_SNAPSHOT_AGE_MS) return null
        if (snapshot.aircraft.isEmpty() && snapshot.partial_coverage) return null
        val normalized_search = exact_search?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
        val filtered = ArrayList<FeedAircraft>(snapshot.aircraft.size)
        snapshot.aircraft.forEach { aircraft ->
            if (normalized_search == null || aircraft.matches_search(normalized_search)) {
                filtered += aircraft.with_distance_from(own_lat, own_lon)
            }
        }
        filtered.sortBy { it.distance_m }
        if (normalized_search != null && filtered.isEmpty()) return null
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.AIRPLANES_LIVE_GLOBE,
            aircraft = filtered,
            epoch_sec = snapshot.epoch_sec,
            query_count = 0,
            partial_coverage = snapshot.partial_coverage
        )
    }

    fun pause_inventory_extraction(duration_ms: Long) {
        if (!source_enabled || destroyed) return
        if (duration_ms <= 0L) return
        extraction_paused_until_elapsed_ms = max(
            extraction_paused_until_elapsed_ms,
            SystemClock.elapsedRealtime() + duration_ms.coerceAtLeast(0L)
        )
    }

    private fun start_if_allowed() {
        if (destroyed || !source_enabled || !host_resumed || running) return
        if (pending_viewport == null) {
            viewport_generation += 1L
            pending_viewport = globe_inventory_viewport()
        }
        running = true
        schedule_fetch(0L)
    }

    private fun stop_active(clear_snapshot: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { stop_active(clear_snapshot) }
            return
        }
        running = false
        handler.removeCallbacks(fetcher)
        fetch_in_progress = false
        if (clear_snapshot) {
            latest = null
            pending_viewport = null
            viewport_generation = 0L
        }
    }

    private fun schedule_fetch(delay_ms: Long) {
        if (!running) return
        handler.removeCallbacks(fetcher)
        handler.postDelayed(fetcher, delay_ms.coerceAtLeast(0L))
    }

    private fun fetch_inventory() {
        if (!source_enabled || !running || destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { fetch_inventory() }
            return
        }
        val viewport = pending_viewport ?: run {
            schedule_fetch(STARTUP_RETRY_MS)
            return
        }
        val pause_remaining_ms = extraction_paused_until_elapsed_ms - SystemClock.elapsedRealtime()
        if (pause_remaining_ms > 0L) {
            schedule_fetch(pause_remaining_ms)
            return
        }
        if (fetch_in_progress) return
        fetch_in_progress = true
        val fetch_started_elapsed_ms = SystemClock.elapsedRealtime()
        try {
            parser_executor.execute {
                val snapshot = inventory_client.fetch(viewport.bounds)
                handler.post {
                    fetch_in_progress = false
                    if (!source_enabled || !running || destroyed) return@post
                    if (pending_viewport?.generation != viewport.generation) {
                        schedule_fetch(STARTUP_RETRY_MS)
                        return@post
                    }
                    if (snapshot == null || (snapshot.aircraft.isEmpty() && snapshot.partial_coverage)) {
                        schedule_fetch(STARTUP_RETRY_MS)
                        return@post
                    }
                    val next = GlobeSnapshot(
                        aircraft = snapshot.aircraft,
                        epoch_sec = snapshot.epoch_sec,
                        received_elapsed_ms = SystemClock.elapsedRealtime(),
                        partial_coverage = snapshot.partial_coverage,
                        viewport_generation = viewport.generation
                    )
                    latest = next
                    Log.d(TAG, "Globe binCraft source: ${next.aircraft.size} aircraft, partial=${next.partial_coverage}")
                    on_snapshot_updated?.invoke()
                    schedule_fetch(next_cadence_delay_ms(fetch_started_elapsed_ms))
                }
            }
        } catch (_: RejectedExecutionException) {
            fetch_in_progress = false
        }
    }

    private fun next_cadence_delay_ms(fetch_started_elapsed_ms: Long): Long {
        val elapsed_ms = SystemClock.elapsedRealtime() - fetch_started_elapsed_ms
        return (EXTRACT_INTERVAL_MS - elapsed_ms).coerceAtLeast(0L)
    }

    private fun globe_inventory_viewport(): GlobeViewport {
        return GlobeViewport(
            bounds = WORLD_FEED_BOUNDS,
            center_lat = GLOBE_INVENTORY_CENTER_LAT,
            center_lon = GLOBE_INVENTORY_CENTER_LON,
            zoom = GLOBE_INVENTORY_ZOOM,
            generation = viewport_generation
        )
    }

    private fun FeedAircraft.with_distance_from(own_lat: Double, own_lon: Double): FeedAircraft {
        return copy(distance_m = distance_meters(own_lat, own_lon, lat, lon))
    }

    private fun FeedAircraft.matches_search(search: String): Boolean {
        return icao24.uppercase(Locale.US).contains(search) ||
            callsign.uppercase(Locale.US).replace(" ", "").contains(search.replace(" ", "")) ||
            registration?.uppercase(Locale.US)?.contains(search) == true
    }

    private data class GlobeSnapshot(
        val aircraft: List<FeedAircraft>,
        val epoch_sec: Double,
        val received_elapsed_ms: Long,
        val partial_coverage: Boolean,
        val viewport_generation: Long
    )

    private data class GlobeViewport(
        val bounds: FeedBounds,
        val center_lat: Double,
        val center_lon: Double,
        val zoom: Double,
        val generation: Long
    )

    private fun GlobeViewport.matches(other: GlobeViewport): Boolean {
        return abs(center_lat - other.center_lat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(center_lon - other.center_lon) < VIEWPORT_LAT_LON_EPSILON &&
            abs(zoom - other.zoom) < VIEWPORT_ZOOM_EPSILON &&
            abs(bounds.min_lat - other.bounds.min_lat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.max_lat - other.bounds.max_lat) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.min_lon - other.bounds.min_lon) < VIEWPORT_LAT_LON_EPSILON &&
            abs(bounds.max_lon - other.bounds.max_lon) < VIEWPORT_LAT_LON_EPSILON
    }

    private companion object {
        private const val TAG = "FlightAlert"
        private const val EXTRACT_INTERVAL_MS = 1000L
        private const val STARTUP_RETRY_MS = 450L
        private const val MAX_SNAPSHOT_AGE_MS = 9000L
        private const val GLOBE_INVENTORY_ZOOM = 0.0
        private const val GLOBE_INVENTORY_CENTER_LAT = 0.0
        private const val GLOBE_INVENTORY_CENTER_LON = 0.0
        private const val VIEWPORT_LAT_LON_EPSILON = 0.01
        private const val VIEWPORT_ZOOM_EPSILON = 0.12
        private val WORLD_FEED_BOUNDS = FeedBounds(-90.0, -180.0, 90.0, 180.0)
    }
}

private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat_distance = Math.toRadians(lat2 - lat1)
    val lon_distance = Math.toRadians(lon2 - lon1)
    val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(lon_distance / 2) * sin(lon_distance / 2)
    return 2.0 * GLOBE_EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}

private const val GLOBE_EARTH_RADIUS_M = 6371000.0
