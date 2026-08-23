@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


private const val GLOBE_SNAPSHOT_VALIDITY_MS = 9000L
internal const val GLOBE_SNAPSHOT_VALIDITY_SECONDS =
    GLOBE_SNAPSHOT_VALIDITY_MS / 1000.0

private fun FeedBounds.to_globe_query_bounds(): GlobeQueryBounds {
    val min_latitude = min(min_lat, max_lat).coerceIn(-90.0, 90.0)
    val max_latitude = max(min_lat, max_lat).coerceIn(-90.0, 90.0)
    val min_longitude = min_lon.coerceIn(-180.0, 180.0)
    val max_longitude = max_lon.coerceIn(-180.0, 180.0)
    return GlobeQueryBounds(
        min_lat = min_latitude,
        min_lon = min_longitude,
        max_lat = max_latitude,
        max_lon = max_longitude,
        wraps_longitude = min_longitude > max_longitude
    )
}


class GlobeBinCraftAircraftSource(user_agent: String) {
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
    private var latest_query: GlobeQuery? = null

    @Volatile
    private var extraction_paused_until_elapsed_ms = 0L

    @Volatile
    private var fetch_in_progress = false

    private val fetcher = Runnable { fetch_inventory() }

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

    fun update_viewport() {
        if (!source_enabled || destroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(::update_viewport)
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

    @Synchronized
    fun latest_snapshot(
        display_bounds: List<FeedBounds>,
        safety_bounds: FeedBounds?,
        own_lat: Double,
        own_lon: Double,
        search_query: String?,
        selected_aircraft_key: String?
    ): FeedResult? {
        if (!source_enabled || destroyed) return null
        val viewport = pending_viewport ?: return null
        val snapshot = latest ?: return null
        if (snapshot.viewport_generation != viewport.generation) return null
        if (SystemClock.elapsedRealtime() - snapshot.received_elapsed_ms > GLOBE_SNAPSHOT_VALIDITY_MS) return null
        if (snapshot.store.aircraft.isEmpty() && snapshot.partial_coverage) return null
        val query_bounds = display_bounds.map(FeedBounds::to_globe_query_bounds)
        val safety_query_bounds = safety_bounds?.to_globe_query_bounds()
        val normalized_search = normalize_globe_search_key(search_query)
        val normalized_selected_key = normalize_globe_search_key(selected_aircraft_key)
        val cached_query = latest_query
        if (cached_query != null &&
            cached_query.content_revision == snapshot.content_revision &&
            cached_query.own_lat_bits == own_lat.toBits() &&
            cached_query.own_lon_bits == own_lon.toBits() &&
            cached_query.display_bounds == query_bounds &&
            cached_query.safety_bounds == safety_query_bounds &&
            cached_query.normalized_search == normalized_search &&
            cached_query.normalized_selected_key == normalized_selected_key
        ) {
            return cached_query.result
        }
        val queried = snapshot.store.query(
            display_bounds = query_bounds,
            safety_bounds = safety_query_bounds,
            normalized_search = normalized_search,
            selected_aircraft_key = normalized_selected_key,
            own_lat = own_lat,
            own_lon = own_lon
        )
        val result = FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.AIRPLANES_LIVE_GLOBE,
            aircraft = queried.aircraft,
            epoch_sec = snapshot.epoch_sec,
            query_count = 0,
            partial_coverage = snapshot.partial_coverage,
            content_revision = snapshot.content_revision,
            globe_aircraft = snapshot.store.aircraft,
            globe_motion_candidates = queried.motion_candidates,
            globe_default_summary = GlobeDefaultAircraftSummary(
                total = snapshot.store.aircraft.size,
                airborne_total = queried.airborne_total,
                nearest_airborne = queried.nearest_airborne,
                content_revision = snapshot.content_revision,
                own_lat_bits = own_lat.toBits(),
                own_lon_bits = own_lon.toBits(),
                projection_valid_until_epoch_sec =
                    snapshot.epoch_sec + GLOBE_SNAPSHOT_VALIDITY_SECONDS
            )
        )
        latest_query = GlobeQuery(
            content_revision = snapshot.content_revision,
            own_lat_bits = own_lat.toBits(),
            own_lon_bits = own_lon.toBits(),
            display_bounds = query_bounds,
            safety_bounds = safety_query_bounds,
            normalized_search = normalized_search,
            normalized_selected_key = normalized_selected_key,
            result = result
        )
        return result
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
        if (clear_snapshot) {
            latest = null
            latest_query = null
            pending_viewport = null
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
                    if (snapshot == null || (snapshot.store.aircraft.isEmpty() && snapshot.partial_coverage)) {
                        schedule_fetch(STARTUP_RETRY_MS)
                        return@post
                    }
                    val received_elapsed_ms = SystemClock.elapsedRealtime()
                    val current = latest
                    if (current != null &&
                        current.viewport_generation == viewport.generation &&
                        current.content_revision == snapshot.content_revision
                    ) {
                        latest = current.copy(received_elapsed_ms = received_elapsed_ms)
                        schedule_fetch(next_cadence_delay_ms(fetch_started_elapsed_ms))
                        return@post
                    }
                    val next = GlobeSnapshot(
                        store = snapshot.store,
                        epoch_sec = snapshot.epoch_sec,
                        received_elapsed_ms = received_elapsed_ms,
                        partial_coverage = snapshot.partial_coverage,
                        viewport_generation = viewport.generation,
                        content_revision = snapshot.content_revision
                    )
                    latest_query = null
                    latest = next
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

    private data class GlobeSnapshot(
        val store: GlobeAircraftStore,
        val epoch_sec: Double,
        val received_elapsed_ms: Long,
        val partial_coverage: Boolean,
        val viewport_generation: Long,
        val content_revision: Long
    )

    private data class GlobeQuery(
        val content_revision: Long,
        val own_lat_bits: Long,
        val own_lon_bits: Long,
        val display_bounds: List<GlobeQueryBounds>,
        val safety_bounds: GlobeQueryBounds?,
        val normalized_search: String?,
        val normalized_selected_key: String?,
        val result: FeedResult
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
        private const val EXTRACT_INTERVAL_MS = 1000L
        private const val STARTUP_RETRY_MS = 450L
        private const val GLOBE_INVENTORY_ZOOM = 0.0
        private const val GLOBE_INVENTORY_CENTER_LAT = 0.0
        private const val GLOBE_INVENTORY_CENTER_LON = 0.0
        private const val VIEWPORT_LAT_LON_EPSILON = 0.01
        private const val VIEWPORT_ZOOM_EPSILON = 0.12
        private val WORLD_FEED_BOUNDS = FeedBounds(-90.0, -180.0, 90.0, 180.0)
    }
}
