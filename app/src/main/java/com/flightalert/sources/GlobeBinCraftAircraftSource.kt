@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.sources

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import kotlin.io.DEFAULT_BUFFER_SIZE
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.nextDown
import kotlin.math.nextUp
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.map.MapProjection




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

internal fun globe_distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat_distance = Math.toRadians(lat2 - lat1)
    val lon_distance = Math.toRadians(lon2 - lon1)
    val a = sin(lat_distance / 2) * sin(lat_distance / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(lon_distance / 2) * sin(lon_distance / 2)
    return 2.0 * GLOBE_EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}

internal const val GLOBE_EARTH_RADIUS_M = 6371000.0
private const val GLOBE_SNAPSHOT_VALIDITY_MS = 9000L
private const val GLOBE_SNAPSHOT_VALIDITY_SECONDS =
    GLOBE_SNAPSHOT_VALIDITY_MS / 1000.0

internal data class GlobeBinCraftSnapshot(
    val store: GlobeAircraftStore,
    val epoch_sec: Double,
    val partial_coverage: Boolean,
    val content_revision: Long = 0L
)

internal data class GlobeAircraftQueryResult(
    val aircraft: List<FeedAircraft>,
    val motion_candidates: List<FeedAircraft>,
    val airborne_total: Int,
    val nearest_airborne: FeedAircraft?
)

internal class GlobeAircraftStore(
    val aircraft: List<FeedAircraft>,
    private val display_projection_bands: ByteArray,
    private val alert_projection_bands: ByteArray,
    private val maximum_display_projection_band: Int,
    private val maximum_alert_projection_band: Int
) {
    fun query(
        display_bounds: List<GlobeQueryBounds>,
        safety_bounds: GlobeQueryBounds?,
        normalized_search: String?,
        selected_aircraft_key: String?,
        own_lat: Double,
        own_lon: Double
    ): GlobeAircraftQueryResult {
        val selected = ArrayList<FeedAircraft>()
        val motion_candidates = ArrayList<FeedAircraft>()
        val display_bounds_by_band =
            Array(display_bounds.size) {
                arrayOfNulls<GlobeQueryBounds>(maximum_display_projection_band + 1)
            }
        val alert_bounds_by_band =
            safety_bounds?.let {
                arrayOfNulls<GlobeQueryBounds>(maximum_alert_projection_band + 1)
            }
        val distance_result = FloatArray(1)
        var airborne_total = 0
        var nearest_airborne: FeedAircraft? = null
        var nearest_distance_meters = Double.POSITIVE_INFINITY
        aircraft.forEachIndexed { index, item ->
            if (item.on_ground == false) {
                airborne_total++
                val latitude_distance_floor =
                    abs(item.lat - own_lat) * MINIMUM_METERS_PER_LATITUDE_DEGREE
                if (latitude_distance_floor < nearest_distance_meters) {
                    Location.distanceBetween(
                        own_lat,
                        own_lon,
                        item.lat,
                        item.lon,
                        distance_result
                    )
                    val distance_meters = distance_result[0].toDouble()
                    if (distance_meters < nearest_distance_meters) {
                        nearest_airborne = item
                        nearest_distance_meters = distance_meters
                    }
                }
            }

            var raw_authoritative = false
            var bounds_index = 0
            while (bounds_index < display_bounds.size) {
                if (display_bounds[bounds_index].contains(item.lat, item.lon)) {
                    raw_authoritative = true
                    break
                }
                bounds_index++
            }
            if (!raw_authoritative && safety_bounds?.contains(item.lat, item.lon) == true) {
                raw_authoritative = true
            }
            if (!raw_authoritative && selected_aircraft_key != null &&
                item.matches_normalized_exact_key(selected_aircraft_key)
            ) {
                raw_authoritative = true
            }
            if (raw_authoritative ||
                normalized_search != null && item.matches_normalized_search(normalized_search)
            ) {
                selected += item.copy(
                    distance_m = globe_distance_meters(own_lat, own_lon, item.lat, item.lon)
                )
            }
            if (!raw_authoritative) {
                val display_band = display_projection_bands[index].toInt() and 0xff
                var may_project_into_display = false
                if (display_band != 0) {
                    bounds_index = 0
                    while (bounds_index < display_bounds.size) {
                        if (bounds_for_projection_band(
                                display_bounds[bounds_index],
                                display_bounds_by_band[bounds_index],
                                display_band
                            ).contains(item.lat, item.lon)
                        ) {
                            may_project_into_display = true
                            break
                        }
                        bounds_index++
                    }
                }
                val alert_band = alert_projection_bands[index].toInt() and 0xff
                val may_project_into_safety =
                    safety_bounds != null &&
                            alert_bounds_by_band != null &&
                            alert_band != 0 &&
                            bounds_for_projection_band(
                                safety_bounds,
                                alert_bounds_by_band,
                                alert_band
                            ).contains(item.lat, item.lon)
                if (may_project_into_display || may_project_into_safety) {
                    motion_candidates += item
                }
            }
        }
        selected.sortWith(distance_order)
        return GlobeAircraftQueryResult(
            aircraft = selected,
            motion_candidates = motion_candidates,
            airborne_total = airborne_total,
            nearest_airborne = nearest_airborne
        )
    }

    companion object {
        private val distance_order = Comparator<FeedAircraft> { left, right ->
            left.distance_m.compareTo(right.distance_m)
        }

        internal fun display_projection_band(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Int {
            return projection_band(
                display_projection_reach_meters(item, projection_valid_until)
            )
        }

        internal fun alert_projection_band(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Int {
            return projection_band(
                alert_projection_reach_meters(item, projection_valid_until)
            )
        }

        private fun display_projection_reach_meters(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Double {
            if (item.on_ground == true || !projection_valid_until.isFinite()) return 0.0
            val speed = item.velocity_ms?.takeIf {
                it.isFinite() &&
                        it > MIN_DISPLAY_PROJECTION_SPEED_MS &&
                        it <= MAX_DISPLAY_PROJECTION_SPEED_MS
            } ?: return 0.0
            if (item.track_deg?.isFinite() != true) return 0.0
            val position_time =
                item.position_time_sec?.takeIf { it.isFinite() } ?: return 0.0
            val raw_elapsed = projection_valid_until - position_time
            if (!raw_elapsed.isFinite()) return 0.0
            return speed * raw_elapsed.coerceAtLeast(0.0)
        }

        private fun alert_projection_reach_meters(
            item: FeedAircraft,
            projection_valid_until: Double
        ): Double {
            if (!projection_valid_until.isFinite()) return 0.0
            val speed = item.velocity_ms?.takeIf {
                it.isFinite() &&
                        it in FlightAlertAppSettings.AlertProjection.MIN_PROJECTABLE_SPEED_MS..
                        FlightAlertAppSettings.AlertProjection.MAX_PROJECTABLE_SPEED_MS
            } ?: return 0.0
            if (item.track_deg?.isFinite() != true) return 0.0
            val report_time = item.position_time_sec ?: item.last_contact_sec ?: return 0.0
            val raw_age = projection_valid_until - report_time
            val age = when {
                raw_age.isNaN() -> 0.0
                raw_age <= 0.0 -> 0.0
                raw_age >= FlightAlertAppSettings.AlertProjection.MAX_SECONDS ->
                    FlightAlertAppSettings.AlertProjection.MAX_SECONDS
                else -> raw_age
            }
            if (age <= FlightAlertAppSettings.AlertProjection.MIN_SECONDS) return 0.0
            return speed * age
        }

        private fun projection_band(distance_meters: Double): Int {
            if (!(distance_meters > 0.0)) return 0
            if (!distance_meters.isFinite()) return FULL_GLOBE_PROJECTION_BAND
            val rounded_exponent =
                (Math.getExponent(distance_meters.nextDown()) + 1).coerceAtLeast(0)
            return if (rounded_exponent >= FULL_GLOBE_PROJECTION_BAND - 1) {
                FULL_GLOBE_PROJECTION_BAND
            } else {
                rounded_exponent + 1
            }
        }

        private fun bounds_for_projection_band(
            bounds: GlobeQueryBounds,
            cached_bounds: Array<GlobeQueryBounds?>,
            band: Int
        ): GlobeQueryBounds {
            return cached_bounds[band]
                ?: bounds.expanded_by_distance_meters(
                    if (band == FULL_GLOBE_PROJECTION_BAND) {
                        Double.POSITIVE_INFINITY
                    } else {
                        Math.scalb(1.0, band - 1)
                    }
                ).also { cached_bounds[band] = it }
        }

        private const val FULL_GLOBE_PROJECTION_BAND = 0xff
        private const val MINIMUM_METERS_PER_LATITUDE_DEGREE = 110_000.0
        private const val MIN_DISPLAY_PROJECTION_SPEED_MS = 0.5
        private const val MAX_DISPLAY_PROJECTION_SPEED_MS = 1_200.0
    }
}

internal data class GlobeQueryBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double,
    val wraps_longitude: Boolean
) {
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat !in min_lat..max_lat) return false
        return if (wraps_longitude) lon >= min_lon || lon <= max_lon else lon in min_lon..max_lon
    }

    fun expanded_by_distance_meters(distance_meters: Double): GlobeQueryBounds {
        if (distance_meters <= 0.0) return this
        if (!distance_meters.isFinite()) {
            return GlobeQueryBounds(-90.0, -180.0, 90.0, 180.0, false)
        }
        val angular_distance =
            (distance_meters / GLOBE_EARTH_RADIUS_M).nextUp()
        val latitude_delta = Math.toDegrees(angular_distance)
        val expanded_min_lat = (min_lat - latitude_delta).coerceAtLeast(-90.0)
        val expanded_max_lat = (max_lat + latitude_delta).coerceAtMost(90.0)
        val full_longitude =
            !wraps_longitude && min_lon <= -180.0 && max_lon >= 180.0
        val maximum_source_latitude =
            Math.toRadians(max(abs(min_lat), abs(max_lat)))
        if (full_longitude ||
            angular_distance >= Math.PI ||
            maximum_source_latitude + angular_distance >= Math.PI / 2.0
        ) {
            return GlobeQueryBounds(
                expanded_min_lat,
                -180.0,
                expanded_max_lat,
                180.0,
                false
            )
        }

        val longitude_delta = Math.toDegrees(
            asin(
                (sin(angular_distance) / cos(maximum_source_latitude))
                    .coerceIn(0.0, 1.0)
            )
        ).nextUp()
        val longitude_span =
            if (wraps_longitude) 360.0 - min_lon + max_lon else max_lon - min_lon
        if (longitude_span + 2.0 * longitude_delta >= 360.0) {
            return GlobeQueryBounds(
                expanded_min_lat,
                -180.0,
                expanded_max_lat,
                180.0,
                false
            )
        }
        val expanded_min_lon = MapProjection.normalize_longitude(min_lon - longitude_delta)
        val expanded_max_lon = MapProjection.normalize_longitude(max_lon + longitude_delta)
        return GlobeQueryBounds(
            min_lat = expanded_min_lat,
            min_lon = expanded_min_lon,
            max_lat = expanded_max_lat,
            max_lon = expanded_max_lon,
            wraps_longitude = expanded_min_lon > expanded_max_lon
        )
    }
}

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

private fun normalize_globe_search_key(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.filter { it.isLetterOrDigit() || it == '-' }
        ?.takeIf { it.isNotBlank() }
}

private fun FeedAircraft.matches_normalized_search(search: String): Boolean {
    return normalize_globe_search_key(callsign)?.contains(search) == true ||
            normalize_globe_search_key(registration)?.contains(search) == true ||
            normalize_globe_search_key(icao24)?.contains(search) == true ||
            normalize_globe_search_key(type_code)?.contains(search) == true
}

private fun FeedAircraft.matches_normalized_exact_key(search: String): Boolean {
    return normalize_globe_search_key(icao24) == search ||
            normalize_globe_search_key(callsign) == search ||
            normalize_globe_search_key(registration) == search
}

internal class GlobeBinCraftClient(private val user_agent: String) {
    private var content_revision = 0L
    private var cached_compressed: ByteArray? = null
    private var cached_compressed_size = 0
    private var incoming_compressed = ByteArray(DEFAULT_BUFFER_SIZE)
    private var decoded = ByteArray(DEFAULT_BUFFER_SIZE)
    private var cached_snapshot: GlobeBinCraftSnapshot? = null

    fun fetch(bounds: FeedBounds): GlobeBinCraftSnapshot? {
        var connection: HttpURLConnection? = null
        return try {
            val normalized = bounds.normalized()
            val url = URL(
                String.format(
                    Locale.US,
                    "%s/re-api/?binCraft&zstd&box=%.6f,%.6f,%.6f,%.6f",
                    AirplanesLiveHttp.GLOBE_BASE_URL,
                    normalized.min_lat,
                    normalized.max_lat,
                    normalized.min_lon,
                    normalized.max_lon
                )
            )
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                AirplanesLiveHttp.apply_browser_headers(
                    connection = this,
                    app_user_agent = user_agent,
                    referer = "${AirplanesLiveHttp.GLOBE_BASE_URL}/?nowebgl",
                    accept = "*/*"
                )
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            val compressed_size = connection.inputStream.use { input ->
                read_compressed(
                    input = input,
                    expected_size = connection.contentLength.coerceIn(
                        DEFAULT_BUFFER_SIZE,
                        MAX_INITIAL_RESPONSE_CAPACITY
                    )
                )
            }
            val previous_snapshot = cached_snapshot
            val previous_compressed = cached_compressed
            if (previous_snapshot != null && previous_compressed != null &&
                cached_compressed_size == compressed_size
            ) {
                var index = 0
                while (index < compressed_size &&
                    previous_compressed[index] == incoming_compressed[index]
                ) {
                    index++
                }
                if (index == compressed_size) return previous_snapshot
            }
            val decoded_size = decompress(compressed_size)
            parse(decoded, decoded_size)?.let {
                content_revision += 1L
                it.copy(content_revision = content_revision).also { versioned ->
                    cached_compressed = incoming_compressed
                    cached_compressed_size = compressed_size
                    incoming_compressed = previous_compressed
                        ?: ByteArray(max(DEFAULT_BUFFER_SIZE, compressed_size))
                    cached_snapshot = versioned
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun read_compressed(input: InputStream, expected_size: Int): Int {
        incoming_compressed = ensure_capacity(incoming_compressed, expected_size)
        var size = 0
        while (true) {
            if (size == incoming_compressed.size) {
                incoming_compressed = ensure_capacity(incoming_compressed, size + 1)
            }
            val read = input.read(
                incoming_compressed,
                size,
                incoming_compressed.size - size
            )
            when {
                read < 0 -> return size
                read > 0 -> size += read
                else -> {
                    val next = input.read()
                    if (next < 0) return size
                    if (size == incoming_compressed.size) {
                        incoming_compressed = ensure_capacity(incoming_compressed, size + 1)
                    }
                    incoming_compressed[size++] = next.toByte()
                }
            }
        }
    }

    private fun decompress(compressed_size: Int): Int {
        val expected_size = Zstd.getFrameContentSize(
            incoming_compressed,
            0,
            compressed_size
        )
        if (expected_size in 1L..Int.MAX_VALUE.toLong()) {
            decoded = ensure_capacity(decoded, expected_size.toInt())
            val decoded_size = Zstd.decompressByteArray(
                decoded,
                0,
                decoded.size,
                incoming_compressed,
                0,
                compressed_size
            )
            if (!Zstd.isError(decoded_size) && decoded_size in 0L..Int.MAX_VALUE.toLong()) {
                return decoded_size.toInt()
            }
        }
        ZstdInputStream(
            ByteArrayInputStream(incoming_compressed, 0, compressed_size)
        ).use { stream ->
            var size = 0
            while (true) {
                if (size == decoded.size) {
                    decoded = ensure_capacity(decoded, size + 1)
                }
                val read = stream.read(decoded, size, decoded.size - size)
                when {
                    read < 0 -> return size
                    read > 0 -> size += read
                    else -> {
                        val next = stream.read()
                        if (next < 0) return size
                        if (size == decoded.size) {
                            decoded = ensure_capacity(decoded, size + 1)
                        }
                        decoded[size++] = next.toByte()
                    }
                }
            }
        }
    }

    private fun ensure_capacity(buffer: ByteArray, required_size: Int): ByteArray {
        if (buffer.size >= required_size) return buffer
        return buffer.copyOf(max(required_size, buffer.size * 2))
    }

    private fun parse(bytes: ByteArray, byte_count: Int): GlobeBinCraftSnapshot? {
        if (byte_count < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes, 0, byte_count).order(ByteOrder.LITTLE_ENDIAN)
        val now_sec = u32(buffer, 0) / 1000.0 + u32(buffer, 4) * 4294967.296
        val stride = int(buffer, 8)
        if (stride !in MIN_STRIDE_BYTES..MAX_STRIDE_BYTES || byte_count < stride) return null
        val global_with_position = int(buffer, 12)
        val messages = u32(buffer, 28)
        val bin_craft_version = int(buffer, 40)
        val message_rate = u32(buffer, 44) / 10.0
        val flags = int(buffer, 48)
        val use_message_rate = flags and 1 != 0
        val parsed = ArrayList<FeedAircraft>(global_with_position.coerceIn(512, 32768))
        val record_count = ((byte_count - stride) / stride).coerceAtLeast(0)
        val display_projection_bands = ByteArray(record_count)
        val alert_projection_bands = ByteArray(record_count)
        val projection_valid_until = now_sec + GLOBE_SNAPSHOT_VALIDITY_SECONDS
        var maximum_display_projection_band = 0
        var maximum_alert_projection_band = 0
        var offset = stride
        while (offset + stride <= byte_count) {
            val item = parse_aircraft(
                buffer,
                offset,
                stride,
                bin_craft_version,
                use_message_rate,
                now_sec
            )
            if (item != null) {
                val index = parsed.size
                parsed += item
                val display_band =
                    GlobeAircraftStore.display_projection_band(item, projection_valid_until)
                val alert_band =
                    GlobeAircraftStore.alert_projection_band(item, projection_valid_until)
                display_projection_bands[index] = display_band.toByte()
                alert_projection_bands[index] = alert_band.toByte()
                maximum_display_projection_band =
                    max(maximum_display_projection_band, display_band)
                maximum_alert_projection_band =
                    max(maximum_alert_projection_band, alert_band)
            }
            offset += stride
        }
        return GlobeBinCraftSnapshot(
            store = GlobeAircraftStore(
                aircraft = parsed,
                display_projection_bands = display_projection_bands,
                alert_projection_bands = alert_projection_bands,
                maximum_display_projection_band = maximum_display_projection_band,
                maximum_alert_projection_band = maximum_alert_projection_band
            ),
            epoch_sec = now_sec,
            partial_coverage = parsed.isEmpty() && (global_with_position > 0 || messages > 0 || message_rate > 0.0)
        )
    }

    private fun parse_aircraft(
        buffer: ByteBuffer,
        offset: Int,
        stride: Int,
        version: Int,
        use_message_rate: Boolean,
        now_sec: Double
    ): FeedAircraft? {
        val raw_hex = int(buffer, offset)
        val non_icao = raw_hex and (1 shl 24) != 0
        val hex = (raw_hex and ((1 shl 24) - 1)).toString(16).padStart(6, '0').let {
            if (non_icao) "~$it" else it
        }
        val seen = if (version >= 20240218) int(buffer, offset + 4) / 10.0 else u16(
            buffer,
            offset + 6
        ) / 10.0
        val seen_pos = if (version >= 20240218 && stride >= 112) {
            int(buffer, offset + 27 * 4) / 10.0
        } else {
            u16(buffer, offset + 4) / 10.0
        }
        val lon_raw = int(buffer, offset + 8)
        val lat_raw = int(buffer, offset + 12)
        val validity_73 = u8(buffer, offset + 73)
        if (validity_73 and 64 == 0 || lon_raw == Int.MAX_VALUE || lat_raw == Int.MAX_VALUE) return null
        val lon = lon_raw / 1_000_000.0
        val lat = lat_raw / 1_000_000.0
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null

        val airground = u8(buffer, offset + 68) and 15
        val on_ground = airground == 1
        val valid_74 = u8(buffer, offset + 74)
        val valid_75 = u8(buffer, offset + 75)
        val valid_76 = u8(buffer, offset + 76)
        val valid_77 = u8(buffer, offset + 77)
        val category_value = u8(buffer, offset + 64)
        val category = if (category_value != 0) hex_labels[category_value] else null
        val type_code = ascii(buffer, offset + 88, 4)
        val registration = ascii(buffer, offset + 92, 12)
        val flight = ascii(buffer, offset + 78, 8)
        val callsign = flight ?: registration ?: hex.trimStart('~')
        val altitude_feet = when {
            on_ground -> 0.0
            validity_73 and 16 != 0 -> s16(buffer, offset + 20) * 25.0
            validity_73 and 32 != 0 -> s16(buffer, offset + 22) * 25.0
            else -> null
        }
        val speed_kt = if (validity_73 and 128 != 0) s16(buffer, offset + 34) / 10.0 else null
        val track = if (valid_74 and 8 != 0) s16(buffer, offset + 40) / 90.0 else null
        val vertical_rate_fpm = when {
            valid_75 and 1 != 0 -> s16(buffer, offset + 16) * 8.0
            valid_75 and 2 != 0 -> s16(buffer, offset + 18) * 8.0
            else -> null
        }
        val db_flags = u16(buffer, offset + 86).takeIf { it != 0 }
        val receiver_count = u8(buffer, offset + 104).takeIf { stride > 104 && it != 0 }
        val rssi = if (stride > 105) {
            if (version >= 20250403) {
                u8(buffer, offset + 105) * (50.0 / 255.0) - 50.0
            } else {
                val level = u8(buffer, offset + 105).let { it * it / 65025.0 + 1.125e-5 }
                10.0 * ln(level) / ln(10.0)
            }
        } else {
            null
        }
        val telemetry = AircraftTelemetry(
            source_type = source_type_label(u8(buffer, offset + 67) ushr 4),
            squawk = if (valid_76 and 4 != 0) squawk(buffer, offset + 32) else null,
            baro_altitude_m = if (validity_73 and 16 != 0 || on_ground) (if (on_ground) 0.0 else s16(
                buffer,
                offset + 20
            ) * 25.0 / FEET_PER_METER) else null,
            geom_altitude_m = if (validity_73 and 32 != 0) s16(
                buffer,
                offset + 22
            ) * 25.0 / FEET_PER_METER else null,
            ground_speed_ms = speed_kt?.let { it * KNOTS_TO_METERS_PER_SECOND },
            true_speed_ms = if (valid_74 and 2 != 0) u16(
                buffer,
                offset + 56
            ) * KNOTS_TO_METERS_PER_SECOND else null,
            indicated_speed_ms = if (valid_74 and 1 != 0) u16(
                buffer,
                offset + 58
            ) * KNOTS_TO_METERS_PER_SECOND else null,
            mach = if (valid_74 and 4 != 0) s16(buffer, offset + 36) / 1000.0 else null,
            baro_rate_ms = if (valid_75 and 1 != 0) s16(
                buffer,
                offset + 16
            ) * 8.0 / FEET_PER_METER / 60.0 else null,
            geom_rate_ms = if (valid_75 and 2 != 0) s16(
                buffer,
                offset + 18
            ) * 8.0 / FEET_PER_METER / 60.0 else null,
            selected_altitude_m = selected_altitude_m(buffer, offset, valid_76),
            selected_heading_deg = if (valid_77 and 2 != 0) s16(
                buffer,
                offset + 30
            ) / 90.0 else null,
            wind_speed_ms = if (valid_77 and 16 != 0) s16(
                buffer,
                offset + 50
            ) * KNOTS_TO_METERS_PER_SECOND else null,
            wind_direction_deg = if (valid_77 and 16 != 0) s16(
                buffer,
                offset + 48
            ).toDouble() else null,
            tat_c = if (valid_77 and 32 != 0) s16(buffer, offset + 54).toDouble() else null,
            oat_c = if (valid_77 and 32 != 0) s16(buffer, offset + 52).toDouble() else null,
            qnh_hpa = if (valid_76 and 32 != 0) s16(buffer, offset + 28) / 10.0 else null,
            true_heading_deg = if (valid_74 and 128 != 0) s16(buffer, offset + 46) / 90.0 else null,
            magnetic_heading_deg = if (valid_74 and 64 != 0) s16(
                buffer,
                offset + 44
            ) / 90.0 else null,
            track_rate_deg_per_sec = if (valid_74 and 16 != 0) s16(
                buffer,
                offset + 42
            ) / 100.0 else null,
            roll_deg = if (valid_74 and 32 != 0) s16(buffer, offset + 38) / 100.0 else null,
            nav_modes = if (valid_77 and 4 != 0) nav_modes(
                u8(
                    buffer,
                    offset + 66
                )
            ) else emptyList(),
            adsb_version = adsb_version_label(buffer, offset),
            nac_p = if (valid_75 and 32 != 0) u8(buffer, offset + 71) and 15 else null,
            nac_v = if (valid_75 and 64 != 0) (u8(buffer, offset + 71) and 240) ushr 4 else null,
            sil = if (valid_75 and 128 != 0) u8(buffer, offset + 72) and 3 else null,
            sil_type = if (valid_75 and 128 != 0) sil_type_label(
                u8(
                    buffer,
                    offset + 69
                ) and 15
            ) else null,
            nic_baro = if (valid_75 and 16 != 0) decimal_labels[u8(
                buffer,
                offset + 73
            ) and 1] else null,
            rc_m = if (u8(buffer, offset + 71) and 15 != 0) u16(
                buffer,
                offset + 60
            ).toDouble() else null,
            rssi = rssi,
            message_rate = if (use_message_rate) u16(buffer, offset + 62) / 10.0 else null,
            receiver_count_label =
                if (receiver_count != null) decimal_labels[receiver_count] else null,
            category_label = category
        )
        return FeedAircraft(
            icao24 = hex,
            callsign = callsign,
            registration = registration,
            type_code = type_code,
            metadata = if (registration != null || type_code != null) {
                AircraftMetadataSeed(
                    source_name = FeedSource.AIRPLANES_LIVE_GLOBE.display_name,
                    registration = registration,
                    type_code = type_code
                )
            } else {
                null
            },
            db_flags = db_flags,
            lat = lat,
            lon = lon,
            on_ground = on_ground,
            altitude_m = altitude_feet?.let { it / FEET_PER_METER },
            velocity_ms = speed_kt?.let { it * KNOTS_TO_METERS_PER_SECOND },
            track_deg = track,
            vertical_rate_ms = vertical_rate_fpm?.let { it / FEET_PER_METER / 60.0 },
            category = category_from_globe(category, type_code),
            position_time_sec = now_sec - seen_pos,
            last_contact_sec = now_sec - seen,
            distance_m = 0.0,
            telemetry = telemetry
        )
    }

    private fun selected_altitude_m(buffer: ByteBuffer, offset: Int, valid_76: Int): Double? {
        val mcp = if (valid_76 and 64 != 0) u16(buffer, offset + 24) * 4.0 else null
        val fms = if (valid_76 and 128 != 0) u16(buffer, offset + 26) * 4.0 else null
        return (mcp ?: fms)?.let { it / FEET_PER_METER }
    }

    private fun source_type_label(type: Int): String {
        return when (type) {
            0 -> "adsb_icao"
            1 -> "adsb_icao_nt"
            2 -> "adsr_icao"
            3 -> "tisb_icao"
            4 -> "adsc"
            5 -> "mlat"
            6 -> "other"
            7 -> "mode_s"
            8 -> "adsb_other"
            9 -> "adsr_other"
            10 -> "tisb_trackfile"
            11 -> "tisb_other"
            12 -> "mode_ac"
            else -> "unknown"
        }
    }

    private fun adsb_version_label(buffer: ByteBuffer, offset: Int): String? {
        val source_type = u8(buffer, offset + 67) ushr 4
        val version = when (source_type) {
            0, 1, 8 -> (u8(buffer, offset + 69) and 240) ushr 4
            2, 9 -> u8(buffer, offset + 70) and 15
            3, 10, 11 -> (u8(buffer, offset + 70) and 240) ushr 4
            else -> return null
        }
        return adsb_version_labels[version]
    }

    private fun sil_type_label(value: Int): String {
        return when (value) {
            1 -> "per_sample"
            2 -> "per_hour"
            else -> decimal_labels[value]
        }
    }

    private fun nav_modes(bits: Int): List<String> {
        return nav_mode_labels[bits and 63]
    }

    private fun squawk(buffer: ByteBuffer, offset: Int): String {
        val raw = u16(buffer, offset).toString(16).padStart(4, '0')
        return if (raw[0] > '9') {
            parse_hex_digit(raw[0]).toString() + raw.substring(1)
        } else {
            raw
        }
    }

    private fun parse_hex_digit(value: Char): Int {
        return value.digitToIntOrNull(16) ?: 0
    }

    private fun ascii(buffer: ByteBuffer, offset: Int, max_length: Int): String? {
        var end = offset
        val limit = minOf(offset + max_length, buffer.limit())
        while (end < limit && u8(buffer, end) != 0) end++
        var start = offset
        while (start < end && u8(buffer, start).toChar().isWhitespace()) start++
        while (end > start && u8(buffer, end - 1).toChar().isWhitespace()) end--
        if (start == end) return null
        return String(
            buffer.array(),
            buffer.arrayOffset() + start,
            end - start,
            Charsets.ISO_8859_1
        )
    }

    private fun category_from_globe(category: String?, type_code: String?): Int? {
        val normalized = category?.trim()?.uppercase(Locale.US).orEmpty()
        if (normalized.startsWith("A7") || normalized.startsWith("B7")) return 8
        if (normalized.startsWith("B1")) return 9
        if (normalized.startsWith("B6")) return 14
        if (normalized.startsWith("C") || normalized.startsWith("D")) return 15
        val type = type_code?.trim()?.uppercase(Locale.US).orEmpty()
        return when {
            type.startsWith("H") || type.startsWith("R") -> 8
            type.startsWith("GL") -> 9
            type.startsWith("UAV") || type.startsWith("DRON") -> 14
            else -> null
        }
    }

    private fun int(buffer: ByteBuffer, offset: Int): Int = buffer.getInt(offset)

    private fun u32(buffer: ByteBuffer, offset: Int): Long =
        int(buffer, offset).toLong() and 0xffffffffL

    private fun s16(buffer: ByteBuffer, offset: Int): Short = buffer.getShort(offset)

    private fun u16(buffer: ByteBuffer, offset: Int): Int =
        buffer.getShort(offset).toInt() and 0xffff

    private fun u8(buffer: ByteBuffer, offset: Int): Int = buffer.get(offset).toInt() and 0xff

    private companion object {
        const val HEADER_BYTES = 52
        const val MIN_STRIDE_BYTES = 108
        const val MAX_STRIDE_BYTES = 160
        const val FEET_PER_METER = 3.28084
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
        const val MAX_INITIAL_RESPONSE_CAPACITY = 8 * 1024 * 1024

        val decimal_labels = Array(256) { it.toString() }
        val hex_labels = Array(256) { it.toString(16).uppercase(Locale.US) }
        val adsb_version_labels = Array(16) { "v$it" }
        val nav_mode_labels = Array(64) { bits ->
            buildList(6) {
                if (bits and 1 != 0) add("autopilot")
                if (bits and 2 != 0) add("vnav")
                if (bits and 4 != 0) add("alt_hold")
                if (bits and 8 != 0) add("approach")
                if (bits and 16 != 0) add("lnav")
                if (bits and 32 != 0) add("tcas")
            }
        }
    }
}

