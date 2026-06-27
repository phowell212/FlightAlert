@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.sources

import com.flightalert.FlightAlertAppSettings

import android.os.SystemClock
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.aircraft.aircraft_identity_key
import com.flightalert.details.json_int_or_null
import com.flightalert.details.json_number_or_null
import com.flightalert.details.max_epoch
import com.flightalert.map.clamped_haversine_distance_meters
import com.flightalert.ui.AircraftFeedMode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class FeedBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun normalized(): FeedBounds {
        return FeedBounds(
            min_lat = min(min_lat, max_lat).coerceIn(-90.0, 90.0),
            min_lon = min(min_lon, max_lon).coerceIn(-180.0, 180.0),
            max_lat = max(min_lat, max_lat).coerceIn(-90.0, 90.0),
            max_lon = max(min_lon, max_lon).coerceIn(-180.0, 180.0)
        )
    }
}

data class FeedResult(
    val status: FeedStatus,
    val source: FeedSource,
    val aircraft: List<FeedAircraft> = emptyList(),
    val epoch_sec: Double? = null,
    val http_code: Int? = null,
    val query_count: Int = 1,
    val partial_coverage: Boolean = false
)

data class FeedAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val type_code: String?,
    val metadata: AircraftMetadataSeed? = null,
    val db_flags: Int?,
    val lat: Double,
    val lon: Double,
    val on_ground: Boolean?,
    val altitude_m: Double?,
    val velocity_ms: Double?,
    val track_deg: Double?,
    val vertical_rate_ms: Double?,
    val category: Int?,
    val position_time_sec: Double?,
    val last_contact_sec: Double?,
    val distance_m: Double,
    val telemetry: AircraftTelemetry? = null
) {
    fun feed_key(): String =
        aircraft_identity_key(icao24, registration, callsign, lat, lon)
}

enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}

enum class FeedSource(val display_name: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live"),
    AIRPLANES_LIVE_GLOBE("Airplanes.Live binCraft globe feed"),
    HYBRID("Hybrid feed"),
    COMBINED("OpenSky + Airplanes.Live")
}

internal object AirplanesLiveHttp {
    const val GLOBE_BASE_URL = "https://globe.airplanes.live"
    const val API_BASE_URL = "https://api.airplanes.live/v2"
    const val STATIC_DB_BASE_URL = "https://static.airplanes.live/db"

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
    private const val REST_MIN_INTERVAL_MS = 1100L
    private val rest_lock = Any()

    @Volatile
    private var next_rest_request_at_ms = 0L

    fun apply_browser_headers(
        connection: HttpURLConnection,
        app_user_agent: String,
        referer: String? = GLOBE_BASE_URL,
        accept: String = "application/json,text/plain,*/*"
    ) {
        connection.setRequestProperty("User-Agent", browser_user_agent(app_user_agent))
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.setRequestProperty("Cache-Control", "no-cache")
        referer?.let { connection.setRequestProperty("Referer", it) }
    }

    fun wait_for_rest_api_slot() {
        synchronized(rest_lock) {
            val now = System.currentTimeMillis()
            val wait_ms = next_rest_request_at_ms - now
            if (wait_ms > 0L) {
                try {
                    Thread.sleep(wait_ms)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            next_rest_request_at_ms = System.currentTimeMillis() + REST_MIN_INTERVAL_MS
        }
    }

    fun back_off_rest_api(retry_after_seconds: Long?) {
        val retry_ms = (retry_after_seconds ?: 2L).coerceAtLeast(1L) * 1000L
        synchronized(rest_lock) {
            next_rest_request_at_ms =
                maxOf(next_rest_request_at_ms, System.currentTimeMillis() + retry_ms)
        }
    }

    fun browser_user_agent(app_user_agent: String): String {
        val trimmed = app_user_agent.trim()
        if (trimmed.contains("Mozilla/", ignoreCase = true)) return trimmed
        return if (trimmed.isEmpty()) BROWSER_USER_AGENT else "$BROWSER_USER_AGENT $trimmed"
    }
}

// Live aircraft feed client: viewport coverage and exact-search detail stay separate until both real sources answer.
@Suppress("FunctionName")
class AircraftFeedClient(private val user_agent: String) {

    // Fetch the visible area first, then merge an exact search result if the user is filtering for one aircraft.
    fun fetch_aircraft(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double,
        exact_search: String? = null
    ): FeedResult {
        val viewport_result = fetch_viewport_aircraft(bounds, own_lat, own_lon)
        val search_result = fetch_airplanes_live_exact_search(exact_search, own_lat, own_lon)
        return merge_viewport_and_search(viewport_result, search_result)
    }

    // Prefer Airplanes.Live coverage, then fall back to OpenSky only when the primary source is not usable.
    private fun fetch_viewport_aircraft(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        val now = System.currentTimeMillis()
        val airplanes_result = if (now >= airplanes_live_retry_after_ms) {
            fetch_airplanes_live(bounds, own_lat, own_lon)
        } else {
            FeedResult(
                FeedStatus.RATE_LIMITED,
                FeedSource.AIRPLANES_LIVE,
                http_code = HTTP_TOO_MANY_REQUESTS
            )
        }
        if (airplanes_result.status == FeedStatus.OK) {
            return airplanes_result
        }

        val open_sky_result =
            if (now >= open_sky_retry_after_ms) fetch_open_sky(bounds, own_lat, own_lon) else null
        if (open_sky_result?.status == FeedStatus.OK) return open_sky_result

        return open_sky_result ?: airplanes_result
    }

    private fun merge_viewport_and_search(viewport: FeedResult, search: FeedResult?): FeedResult {
        if (search == null) return viewport
        if (search.status != FeedStatus.OK) return viewport
        if (viewport.status != FeedStatus.OK) return search
        if (search.aircraft.isEmpty()) return viewport.copy(query_count = viewport.query_count + search.query_count)
        return merge_complete_coverage_with_detail(viewport, search)
    }

    // Merge detail rows into complete coverage by aircraft key, keeping the newest real report for each aircraft.
    private fun merge_complete_coverage_with_detail(
        complete: FeedResult,
        detail: FeedResult
    ): FeedResult {
        val merged = linkedMapOf<String, FeedAircraft>()
        complete.aircraft.forEach { item ->
            merged[item.feed_key()] = item
        }
        detail.aircraft.forEach { item ->
            val key = item.feed_key()
            merged[key] = newer_aircraft(merged[key], item)
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = merged_source(complete.source, detail.source),
            aircraft = merged.values.sortedBy { it.distance_m },
            epoch_sec = max_epoch(complete.epoch_sec, detail.epoch_sec),
            query_count = complete.query_count + detail.query_count,
            partial_coverage = false
        )
    }

    private fun fetch_open_sky(bounds: FeedBounds, own_lat: Double, own_lon: Double): FeedResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://opensky-network.org/api/states/all?lamin=%.5f&lomin=%.5f&lamax=%.5f&lomax=%.5f&extended=1",
                    bounds.min_lat,
                    bounds.min_lon,
                    bounds.max_lat,
                    bounds.max_lon
                )
            )
            connection = open_connection(url)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    FeedResult(
                        status = FeedStatus.OK,
                        source = FeedSource.OPENSKY,
                        aircraft = parse_open_sky_aircraft(json, own_lat, own_lon),
                        epoch_sec = json.feed_json_finite_double_or_null("time")
                    )
                }

                HTTP_TOO_MANY_REQUESTS -> {
                    val retry_seconds =
                        connection.getHeaderField("X-Rate-Limit-Retry-After-Seconds")
                            ?.toLongOrNull()
                    open_sky_retry_after_ms = System.currentTimeMillis() + ((retry_seconds
                        ?: DEFAULT_OPENSKY_BACKOFF_SECONDS) * 1000L)
                    connection.errorStream?.close()
                    FeedResult(FeedStatus.RATE_LIMITED, FeedSource.OPENSKY, http_code = code)
                }

                else -> {
                    connection.errorStream?.close()
                    FeedResult(FeedStatus.UNAVAILABLE, FeedSource.OPENSKY, http_code = code)
                }
            }
        } catch (_: Exception) {
            FeedResult(FeedStatus.UNAVAILABLE, FeedSource.OPENSKY)
        } finally {
            connection?.disconnect()
        }
    }

    // Split large bounds into real point queries so Airplanes.Live can cover the visible area honestly.
    private fun fetch_airplanes_live(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        val plan = airplanes_live_query_plan(bounds)
        val merged = linkedMapOf<String, FeedAircraft>()
        var latest_epoch_sec: Double? = null
        var saw_ok = false
        var saw_rate_limit = false
        var last_http_code: Int? = null
        val query_results = fetch_airplanes_live_points(plan.queries, own_lat, own_lon)
        val timed_out = query_results.size < plan.queries.size

        for (result in query_results) {
            latest_epoch_sec = max_epoch(latest_epoch_sec, result.epoch_sec)
            last_http_code = result.http_code ?: last_http_code
            when (result.status) {
                FeedStatus.OK -> {
                    saw_ok = true
                    result.aircraft.forEach { item ->
                        val key = item.feed_key()
                        merged[key] = newer_aircraft(merged[key], item)
                    }
                }

                FeedStatus.RATE_LIMITED -> {
                    saw_rate_limit = true
                    last_http_code = result.http_code ?: HTTP_TOO_MANY_REQUESTS
                }

                FeedStatus.UNAVAILABLE -> Unit
            }
        }

        return when {
            saw_ok -> FeedResult(
                status = FeedStatus.OK,
                source = FeedSource.AIRPLANES_LIVE,
                aircraft = merged.values.sortedBy { it.distance_m },
                epoch_sec = latest_epoch_sec,
                query_count = plan.queries.size,
                partial_coverage = plan.partial_coverage || timed_out
            )

            saw_rate_limit -> FeedResult(
                status = FeedStatus.RATE_LIMITED,
                source = FeedSource.AIRPLANES_LIVE,
                http_code = last_http_code,
                query_count = plan.queries.size,
                partial_coverage = plan.partial_coverage
            )

            else -> FeedResult(
                status = FeedStatus.UNAVAILABLE,
                source = FeedSource.AIRPLANES_LIVE,
                http_code = last_http_code,
                query_count = plan.queries.size,
                partial_coverage = plan.partial_coverage || timed_out
            )
        }
    }

    private fun fetch_airplanes_live_points(
        queries: List<AirplanesLiveQuery>,
        own_lat: Double,
        own_lon: Double
    ): List<FeedResult> {
        return queries.map { query -> fetch_airplanes_live_point(query, own_lat, own_lon) }
    }

    private fun fetch_airplanes_live_point(
        query: AirplanesLiveQuery,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        val path = String.format(
            Locale.US,
            "point/%.5f/%.5f/%.0f",
            query.center_lat,
            query.center_lon,
            query.radius_nm
        )
        return fetch_airplanes_live_path(path, own_lat, own_lon)
    }

    private fun fetch_airplanes_live_exact_search(
        search: String?,
        own_lat: Double,
        own_lon: Double
    ): FeedResult? {
        val path = airplanes_live_search_path(search) ?: return null
        if (System.currentTimeMillis() < airplanes_live_retry_after_ms) {
            return FeedResult(
                FeedStatus.RATE_LIMITED,
                FeedSource.AIRPLANES_LIVE,
                http_code = HTTP_TOO_MANY_REQUESTS
            )
        }
        return fetch_airplanes_live_path(path, own_lat, own_lon)
    }

    // All Airplanes.Live REST calls pass through the shared rate limiter and report failure instead of inventing data.
    private fun fetch_airplanes_live_path(
        path: String,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        var connection: HttpURLConnection? = null
        return try {
            AirplanesLiveHttp.wait_for_rest_api_slot()
            val url = URL("${AirplanesLiveHttp.API_BASE_URL}/$path")
            connection = open_connection(url)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                FeedResult(
                    status = FeedStatus.OK,
                    source = FeedSource.AIRPLANES_LIVE,
                    aircraft = parse_airplanes_live_aircraft(json, own_lat, own_lon),
                    epoch_sec = json.feed_json_finite_double_or_null("now")
                        ?.let { normalize_epoch_seconds(it) },
                    query_count = 1
                )
            } else {
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    val retry_seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        ?: DEFAULT_AIRPLANES_LIVE_BACKOFF_SECONDS
                    AirplanesLiveHttp.back_off_rest_api(retry_seconds)
                    airplanes_live_retry_after_ms =
                        System.currentTimeMillis() + retry_seconds * 1000L
                }
                connection.errorStream?.close()
                FeedResult(
                    status = if (code == HTTP_TOO_MANY_REQUESTS) FeedStatus.RATE_LIMITED else FeedStatus.UNAVAILABLE,
                    source = FeedSource.AIRPLANES_LIVE,
                    http_code = code,
                    query_count = 1
                )
            }
        } catch (_: Exception) {
            FeedResult(FeedStatus.UNAVAILABLE, FeedSource.AIRPLANES_LIVE, query_count = 1)
        } finally {
            connection?.disconnect()
        }
    }

    private fun airplanes_live_search_path(search: String?): String? {
        val raw = search?.trim().orEmpty()
        if (raw.length < 2) return null
        val compact = raw.replace("\\s+".toRegex(), "").take(MAX_EXACT_SEARCH_CHARS)
        if (!EXACT_SEARCH_ALLOWED.matches(compact)) return null
        val upper = compact.uppercase(Locale.US)
        return when {
            MODE_S_HEX.matches(upper) -> "hex/${upper.lowercase(Locale.US)}"
            looks_like_registration_search(upper) -> "reg/${url_encode(upper)}"
            CALLSIGN_SEARCH.matches(upper) -> "callsign/${url_encode(upper)}"
            else -> null
        }
    }

    private fun looks_like_registration_search(upper: String): Boolean {
        return upper.startsWith("N") || upper.contains("-")
    }

    private fun url_encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // Build a bounded grid of point queries; mark partial coverage when the viewport is too large for one pass.
    private fun airplanes_live_query_plan(bounds: FeedBounds): AirplanesLiveQueryPlan {
        val normalized = bounds.normalized()
        val center_lat = (normalized.min_lat + normalized.max_lat) / 2.0
        val center_lon = (normalized.min_lon + normalized.max_lon) / 2.0
        val width_m =
            feed_distance_meters(center_lat, normalized.min_lon, center_lat, normalized.max_lon)
        val height_m =
            feed_distance_meters(normalized.min_lat, center_lon, normalized.max_lat, center_lon)
        val target_cell_span_m =
            MAX_AIRPLANES_RADIUS_NM * METERS_PER_NAUTICAL_MILE * AIRPLANES_GRID_CELL_RADIUS_FACTOR
        var columns = ceil(width_m / target_cell_span_m).toInt().coerceAtLeast(1)
        var rows = ceil(height_m / target_cell_span_m).toInt().coerceAtLeast(1)
        val needed_rows = rows
        val needed_columns = columns
        while (rows * columns > MAX_AIRPLANES_LIVE_QUERIES) {
            if (columns >= rows && columns > 1) columns-- else if (rows > 1) rows-- else break
        }

        val queries = mutableListOf<AirplanesLiveQuery>()
        for (row in 0 until rows) {
            val min_lat = lerp(normalized.min_lat, normalized.max_lat, row.toDouble() / rows)
            val max_lat = lerp(normalized.min_lat, normalized.max_lat, (row + 1.0) / rows)
            for (column in 0 until columns) {
                val min_lon =
                    lerp(normalized.min_lon, normalized.max_lon, column.toDouble() / columns)
                val max_lon = lerp(normalized.min_lon, normalized.max_lon, (column + 1.0) / columns)
                val cell = FeedBounds(min_lat, min_lon, max_lat, max_lon)
                val cell_center_lat = (min_lat + max_lat) / 2.0
                val cell_center_lon = (min_lon + max_lon) / 2.0
                queries += AirplanesLiveQuery(
                    center_lat = cell_center_lat,
                    center_lon = cell_center_lon,
                    radius_nm = radius_nm_for(cell, cell_center_lat, cell_center_lon)
                        .times(AIRPLANES_QUERY_RADIUS_PADDING)
                        .coerceIn(MIN_AIRPLANES_RADIUS_NM, MAX_AIRPLANES_RADIUS_NM)
                )
            }
        }

        return AirplanesLiveQueryPlan(
            queries = queries.ifEmpty {
                listOf(AirplanesLiveQuery(center_lat, center_lon, MIN_AIRPLANES_RADIUS_NM))
            },
            partial_coverage = rows < needed_rows || columns < needed_columns
        )
    }

    private fun open_connection(url: URL): HttpURLConnection {
        require(
            url.protocol.equals(
                "https",
                ignoreCase = true
            )
        ) { "Only HTTPS aircraft feeds are allowed" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            requestMethod = "GET"
            if (url.host.equals("api.airplanes.live", ignoreCase = true)) {
                AirplanesLiveHttp.apply_browser_headers(this, user_agent)
            } else {
                setRequestProperty("User-Agent", user_agent)
                setRequestProperty("Accept", "application/json")
            }
        }
    }

    // Translate OpenSky arrays into the app feed model while leaving missing fields null.
    private fun parse_open_sky_aircraft(
        json: JSONObject,
        own_lat: Double,
        own_lon: Double
    ): List<FeedAircraft> {
        val rows = json.optJSONArray("states") ?: JSONArray()
        val parsed = mutableListOf<FeedAircraft>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val lon = row.json_number_or_null(5) ?: continue
            val lat = row.json_number_or_null(6) ?: continue
            val icao24 = row.optString(0).trim()
            val callsign = row.optString(1).trim().ifEmpty { icao24.ifEmpty { "Unknown" } }
            val altitude_m = row.json_number_or_null(7) ?: row.json_number_or_null(13)
            val velocity_ms = row.json_number_or_null(9)
            val vertical_rate_ms = row.json_number_or_null(11)
            parsed += FeedAircraft(
                icao24 = icao24,
                callsign = callsign,
                registration = null,
                type_code = null,
                db_flags = null,
                lat = lat,
                lon = lon,
                on_ground = row.feed_json_array_boolean_or_null(8),
                altitude_m = altitude_m,
                velocity_ms = velocity_ms,
                track_deg = row.json_number_or_null(10),
                vertical_rate_ms = vertical_rate_ms,
                category = row.json_int_or_null(17),
                position_time_sec = row.json_number_or_null(3),
                last_contact_sec = row.json_number_or_null(4),
                distance_m = feed_distance_meters(own_lat, own_lon, lat, lon),
                telemetry = AircraftTelemetry(
                    source_type = "OpenSky",
                    baro_altitude_m = altitude_m,
                    ground_speed_ms = velocity_ms,
                    baro_rate_ms = vertical_rate_ms
                ).takeIf { it.has_values }
            )
        }
        return parsed.sortedBy { it.distance_m }
    }

    // Translate Airplanes.Live objects into the app feed model without filling missing source fields.
    private fun parse_airplanes_live_aircraft(
        json: JSONObject,
        own_lat: Double,
        own_lon: Double
    ): List<FeedAircraft> {
        val now_sec =
            json.feed_json_finite_double_or_null("now")?.let { normalize_epoch_seconds(it) }
                ?: (System.currentTimeMillis() / 1000.0)
        val rows = json.optJSONArray("aircraft") ?: json.optJSONArray("ac") ?: JSONArray()
        val parsed = mutableListOf<FeedAircraft>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val lat =
                item.feed_json_finite_double_or_null("lat") ?: item.optJSONObject("lastPosition")
                    ?.feed_json_finite_double_or_null("lat") ?: continue
            val lon =
                item.feed_json_finite_double_or_null("lon") ?: item.optJSONObject("lastPosition")
                    ?.feed_json_finite_double_or_null("lon") ?: continue
            val icao24 = item.optString("hex").trim().lowercase(Locale.US)
            val callsign = item.optString("flight").trim()
                .ifEmpty { item.optString("r").trim().ifEmpty { icao24.ifEmpty { "Unknown" } } }
            val type_code = item.optString("t").trim().ifEmpty { null }
            val altitude_feet =
                item.json_number_or_null("alt_baro") ?: item.json_number_or_null("alt_geom")
            val seen_position_sec = item.feed_json_finite_double_or_null("seen_pos")
            val seen_message_sec = item.feed_json_finite_double_or_null("seen")
            val last_position_time = seen_position_sec?.let { now_sec - it }
            val last_contact_time = seen_message_sec?.let { now_sec - it } ?: last_position_time
            val vertical_rate_ms = (item.feed_json_finite_double_or_null("baro_rate")
                ?: item.feed_json_finite_double_or_null("geom_rate"))
                ?.let { feed_feet_per_minute_to_meters_per_second(it) }
            val telemetry = item.airplanes_live_telemetry()
            parsed += FeedAircraft(
                icao24 = icao24,
                callsign = callsign,
                registration = item.optString("r").trim().ifEmpty { null },
                type_code = type_code,
                db_flags = item.feed_json_int_or_null("dbFlags"),
                lat = lat,
                lon = lon,
                on_ground = item.optString("alt_baro") == "ground",
                altitude_m = altitude_feet?.let { it / FEET_PER_METER },
                velocity_ms = item.feed_json_finite_double_or_null("gs")
                    ?.let { feed_knots_to_meters_per_second(it) },
                track_deg = item.feed_json_finite_double_or_null("track"),
                vertical_rate_ms = vertical_rate_ms,
                category = category_from_airplanes_live(
                    item.optString("category"),
                    item.optString("t")
                ),
                position_time_sec = last_position_time,
                last_contact_sec = last_contact_time,
                distance_m = feed_distance_meters(own_lat, own_lon, lat, lon),
                telemetry = telemetry
            )
        }
        return parsed.sortedBy { it.distance_m }
    }

    private fun JSONObject.airplanes_live_telemetry(): AircraftTelemetry? {
        val telemetry = AircraftTelemetry(
            source_type = feed_json_string_or_null("type")
                ?: feed_json_string_or_null("source_type"),
            squawk = feed_json_string_or_null("squawk"),
            baro_altitude_m = json_number_or_null("alt_baro")?.let { it / FEET_PER_METER },
            geom_altitude_m = json_number_or_null("alt_geom")?.let { it / FEET_PER_METER },
            ground_speed_ms = feed_json_finite_double_or_null("gs")?.let {
                feed_knots_to_meters_per_second(
                    it
                )
            },
            true_speed_ms = feed_json_finite_double_or_null("tas")?.let {
                feed_knots_to_meters_per_second(
                    it
                )
            },
            indicated_speed_ms = feed_json_finite_double_or_null("ias")?.let {
                feed_knots_to_meters_per_second(
                    it
                )
            },
            mach = feed_json_finite_double_or_null("mach"),
            baro_rate_ms = feed_json_finite_double_or_null("baro_rate")?.let {
                feed_feet_per_minute_to_meters_per_second(
                    it
                )
            },
            geom_rate_ms = feed_json_finite_double_or_null("geom_rate")?.let {
                feed_feet_per_minute_to_meters_per_second(
                    it
                )
            },
            selected_altitude_m = (json_number_or_null("nav_altitude_mcp")
                ?: json_number_or_null("nav_altitude_fms"))?.let { it / FEET_PER_METER },
            selected_heading_deg = feed_json_finite_double_or_null("nav_heading"),
            wind_speed_ms = feed_json_finite_double_or_null("ws")?.let {
                feed_knots_to_meters_per_second(
                    it
                )
            },
            wind_direction_deg = feed_json_finite_double_or_null("wd"),
            tat_c = feed_json_finite_double_or_null("tat"),
            oat_c = feed_json_finite_double_or_null("oat"),
            qnh_hpa = feed_json_finite_double_or_null("nav_qnh"),
            true_heading_deg = feed_json_finite_double_or_null("true_heading"),
            magnetic_heading_deg = feed_json_finite_double_or_null("mag_heading"),
            magnetic_declination_deg = feed_json_finite_double_or_null("mag_declination"),
            track_rate_deg_per_sec = feed_json_finite_double_or_null("track_rate"),
            roll_deg = feed_json_finite_double_or_null("roll"),
            nav_modes = feed_json_string_list("nav_modes"),
            adsb_version = feed_json_number_as_string_or_null("version")?.let { "v$it" },
            nac_p = feed_json_int_or_null("nac_p"),
            nac_v = feed_json_int_or_null("nac_v"),
            sil = feed_json_int_or_null("sil"),
            sil_type = feed_json_string_or_null("sil_type"),
            nic_baro = feed_json_number_as_string_or_null("nic_baro"),
            rc_m = feed_json_finite_double_or_null("rc"),
            rssi = feed_json_finite_double_or_null("rssi"),
            message_rate = feed_json_message_rate_or_null(),
            receiver_count_label = feed_json_receiver_count_label(),
            category_label = feed_json_string_or_null("category")
        )
        return telemetry.takeIf { it.has_values }
    }

    private fun radius_nm_for(bounds: FeedBounds, center_lat: Double, center_lon: Double): Double {
        val corners = listOf(
            bounds.min_lat to bounds.min_lon,
            bounds.min_lat to bounds.max_lon,
            bounds.max_lat to bounds.min_lon,
            bounds.max_lat to bounds.max_lon
        )
        return corners.maxOf { (lat, lon) ->
            feed_distance_meters(
                center_lat,
                center_lon,
                lat,
                lon
            )
        } / METERS_PER_NAUTICAL_MILE
    }

    private fun category_from_airplanes_live(category: String, type_code: String): Int? {
        val normalized = category.trim().uppercase(Locale.US)
        if (normalized.startsWith("A7") || normalized.startsWith("B7")) return 8
        if (normalized.startsWith("B1")) return 9
        if (normalized.startsWith("B6")) return 14
        if (normalized.startsWith("C") || normalized.startsWith("D")) return 15
        val type = type_code.trim().uppercase(Locale.US)
        return when {
            type.startsWith("H") || type.startsWith("R") -> 8
            type.startsWith("GL") -> 9
            type.startsWith("UAV") || type.startsWith("DRON") -> 14
            else -> null
        }
    }

    private fun normalize_epoch_seconds(value: Double): Double {
        return if (value > 10_000_000_000.0) value / 1000.0 else value
    }

    // When two feeds report the same aircraft, keep the one with the newest contact time.
    private fun newer_aircraft(existing: FeedAircraft?, incoming: FeedAircraft): FeedAircraft {
        if (existing == null) return incoming
        val existing_time = existing.last_contact_sec ?: existing.position_time_sec ?: 0.0
        val incoming_time = incoming.last_contact_sec ?: incoming.position_time_sec ?: 0.0
        return if (incoming_time >= existing_time) incoming else existing
    }

    private fun lerp(start: Double, end: Double, amount: Double): Double {
        return start + (end - start) * amount
    }

    private fun merged_source(first: FeedSource, second: FeedSource): FeedSource {
        return if (first == second) first else FeedSource.COMBINED
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS =
            FlightAlertAppSettings.AircraftFeed.HTTP_TOO_MANY_REQUESTS
        private const val DEFAULT_OPENSKY_BACKOFF_SECONDS =
            FlightAlertAppSettings.AircraftFeed.DEFAULT_OPENSKY_BACKOFF_SECONDS
        private const val DEFAULT_AIRPLANES_LIVE_BACKOFF_SECONDS =
            FlightAlertAppSettings.AircraftFeed.DEFAULT_AIRPLANES_LIVE_BACKOFF_SECONDS
        private const val MIN_AIRPLANES_RADIUS_NM =
            FlightAlertAppSettings.AircraftFeed.MIN_AIRPLANES_RADIUS_NM
        private const val MAX_AIRPLANES_RADIUS_NM =
            FlightAlertAppSettings.AircraftFeed.MAX_AIRPLANES_RADIUS_NM
        private const val MAX_AIRPLANES_LIVE_QUERIES =
            FlightAlertAppSettings.AircraftFeed.MAX_AIRPLANES_LIVE_QUERIES
        private const val AIRPLANES_GRID_CELL_RADIUS_FACTOR =
            FlightAlertAppSettings.AircraftFeed.AIRPLANES_GRID_CELL_RADIUS_FACTOR
        private const val AIRPLANES_QUERY_RADIUS_PADDING =
            FlightAlertAppSettings.AircraftFeed.AIRPLANES_QUERY_RADIUS_PADDING
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val FEET_PER_METER = 3.28084
        private const val MAX_EXACT_SEARCH_CHARS =
            FlightAlertAppSettings.AircraftFeed.MAX_EXACT_SEARCH_CHARS
        private val EXACT_SEARCH_ALLOWED = Regex("^[A-Za-z0-9-]+$")
        private val MODE_S_HEX = Regex("^[0-9A-F]{6}$")
        private val CALLSIGN_SEARCH = Regex("^[A-Z0-9]{2,12}$")

        @Volatile
        private var open_sky_retry_after_ms = 0L

        @Volatile
        private var airplanes_live_retry_after_ms = 0L
    }
}

internal data class AirplanesLiveQuery(
    val center_lat: Double,
    val center_lon: Double,
    val radius_nm: Double
)

internal data class AirplanesLiveQueryPlan(
    val queries: List<AirplanesLiveQuery>,
    val partial_coverage: Boolean
)

internal fun JSONArray.feed_json_array_boolean_or_null(index: Int): Boolean? {
    if (index >= length() || isNull(index)) return null
    return optBoolean(index)
}

internal fun JSONObject.feed_json_int_or_null(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

internal fun JSONObject.feed_json_finite_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
}

internal fun JSONObject.feed_json_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}

internal fun JSONObject.feed_json_number_as_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toString()
        is String -> raw.trim().takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
        else -> null
    }
}

internal fun JSONObject.feed_json_string_list(key: String): List<String> {
    if (!has(key) || isNull(key)) return emptyList()
    val raw = opt(key)
    if (raw is JSONArray) {
        val values = mutableListOf<String>()
        for (index in 0 until raw.length()) {
            raw.optString(index).trim().takeIf { it.isNotBlank() }?.let { values += it }
        }
        return values
    }
    return (raw?.toString() ?: return emptyList())
        .split(",", " ")
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}

internal fun JSONObject.feed_json_message_rate_or_null(): Double? {
    return feed_json_finite_double_or_null("message_rate")
        ?: feed_json_finite_double_or_null("msgRate")
        ?: feed_json_finite_double_or_null("messageRate")
}

internal fun JSONObject.feed_json_receiver_count_label(): String? {
    for (key in listOf("receivers", "receiver_count", "receiverCount", "siteCount")) {
        if (!has(key) || isNull(key)) continue
        when (val raw = opt(key)) {
            is Number -> return raw.toInt().toString()
            is String -> raw.trim()
                .takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
                ?.let { return it }
        }
    }
    return null
}

internal fun feed_distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    clamped_haversine_distance_meters(lat1, lon1, lat2, lon2)

internal fun feed_knots_to_meters_per_second(knots: Double): Double = knots * 0.514444

internal fun feed_feet_per_minute_to_meters_per_second(feet_per_minute: Double): Double =
    feet_per_minute / 196.850394

// Chooses and merges live aircraft sources so FlightMapView can ask for traffic without knowing source policy.
class AircraftTrafficFeed(
    private val aircraft_feed_client: AircraftFeedClient,
    private val globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource?
) {
    // Keep the optional binCraft source pointed at the same broad inventory area as the visible map.
    fun update_viewport(
        feed_bounds: FeedBounds,
        center_lat: Double,
        center_lon: Double,
        zoom: Double,
        feed_mode: AircraftFeedMode
    ) {
        globe_bin_craft_aircraft_source
            ?.takeIf { feed_mode.uses_globe }
            ?.update_viewport(feed_bounds, center_lat, center_lon, zoom)
    }

    // Fetch according to the selected source mode, reporting intermediate real results when hybrid data arrives in stages.
    fun fetch_aircraft(
        feed_bounds: FeedBounds,
        safety_api_bounds: FeedBounds?,
        own_lat: Double,
        own_lon: Double,
        exact_search: String?,
        feed_mode: AircraftFeedMode,
        on_intermediate_result: (FeedResult) -> Unit
    ): FeedResult? {
        val globe_source = globe_bin_craft_aircraft_source?.takeIf { feed_mode.uses_globe }
        return when (feed_mode) {
            AircraftFeedMode.BINCRAFT -> {
                globe_source?.latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
                    ?: FeedResult(
                        status = FeedStatus.UNAVAILABLE,
                        source = FeedSource.AIRPLANES_LIVE_GLOBE,
                        partial_coverage = true
                    )
            }

            AircraftFeedMode.API -> {
                aircraft_feed_client.fetch_aircraft(feed_bounds, own_lat, own_lon, exact_search)
            }

            AircraftFeedMode.HYBRID -> {
                // Hybrid keeps binCraft as the wide-area inventory, then spends API queries on the safety bubble.
                val targeted_api_bounds = safety_api_bounds ?: feed_bounds
                val first_globe_result =
                    globe_source?.latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
                        ?: globe_source?.await_latest_snapshot(
                            feed_bounds,
                            own_lat,
                            own_lon,
                            exact_search
                        )
                if (first_globe_result?.status == FeedStatus.OK) {
                    on_intermediate_result(first_globe_result)
                }
                val api_result = aircraft_feed_client.fetch_aircraft(
                    targeted_api_bounds,
                    own_lat,
                    own_lon,
                    exact_search
                )
                val globe_result =
                    globe_source?.latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
                        ?: first_globe_result
                when {
                    api_result.status == FeedStatus.OK && globe_result?.status == FeedStatus.OK -> {
                        merge_hybrid_aircraft_feeds(api_result, globe_result)
                    }

                    api_result.status != FeedStatus.OK && globe_result?.status == FeedStatus.OK -> {
                        globe_result
                    }

                    api_result.status != FeedStatus.OK -> {
                        api_result
                    }

                    else -> api_result.copy(
                        source = FeedSource.HYBRID,
                        partial_coverage = true
                    )
                }
            }
        }
    }

    // Convert provider feed rows into map aircraft without inventing missing fields.
    fun map_aircraft(result: FeedResult): List<Aircraft> {
        return result.aircraft.map { it.to_map_aircraft() }
    }

    // Turn feed coverage into short UI text so partial coverage stays visible to the user.
    fun coverage_label(result: FeedResult): String {
        return when {
            result.source == FeedSource.HYBRID && result.partial_coverage -> " (loading binCraft supplement)"
            result.query_count > 1 && result.partial_coverage -> " (${result.query_count} areas, partial wide-area coverage)"
            result.query_count > 1 -> " (${result.query_count} areas)"
            result.partial_coverage -> " (partial wide-area coverage)"
            else -> ""
        }
    }

    // Keep globe/binCraft positions canonical, then fill missing metadata from the API safety query.
    private fun merge_hybrid_aircraft_feeds(
        api_result: FeedResult,
        globe_result: FeedResult
    ): FeedResult {
        val merged = linkedMapOf<String, FeedAircraft>()
        globe_result.aircraft.forEach { item ->
            merged[item.hybrid_feed_key()] = item
        }
        api_result.aircraft.forEach { api_item ->
            val key = api_item.hybrid_feed_key()
            val globe_item = merged[key]
            merged[key] = if (globe_item == null) {
                api_item
            } else {
                merge_freshest_position_with_metadata(globe_item, api_item)
            }
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.HYBRID,
            aircraft = merged.values.sortedBy { it.distance_m },
            epoch_sec = max_epoch(api_result.epoch_sec, globe_result.epoch_sec),
            query_count = api_result.query_count + globe_result.query_count,
            partial_coverage = globe_result.partial_coverage
        )
    }

    private fun merge_freshest_position_with_metadata(
        globe_item: FeedAircraft,
        api_item: FeedAircraft
    ): FeedAircraft {
        val position_item = fresher_position_aircraft(globe_item, api_item)
        val metadata_item = if (position_item === globe_item) api_item else globe_item
        return position_item.copy(
            callsign = position_item.callsign.takeUnless {
                it.isBlank() || it.equals(
                    "Unknown",
                    ignoreCase = true
                )
            }
                ?: metadata_item.callsign,
            registration = position_item.registration ?: metadata_item.registration,
            type_code = position_item.type_code ?: metadata_item.type_code,
            metadata = position_item.metadata ?: metadata_item.metadata,
            db_flags = position_item.db_flags ?: metadata_item.db_flags,
            on_ground = position_item.on_ground ?: metadata_item.on_ground,
            altitude_m = position_item.altitude_m ?: metadata_item.altitude_m,
            velocity_ms = position_item.velocity_ms ?: metadata_item.velocity_ms,
            track_deg = position_item.track_deg ?: metadata_item.track_deg,
            vertical_rate_ms = position_item.vertical_rate_ms ?: metadata_item.vertical_rate_ms,
            category = position_item.category ?: metadata_item.category,
            distance_m = position_item.distance_m.takeIf { it > 0.0 } ?: metadata_item.distance_m,
            telemetry = position_item.telemetry?.with_fallback(metadata_item.telemetry)
                ?: metadata_item.telemetry
        )
    }

    // Prefer real aircraft identifiers for merges, falling back to coarse position only when no ID exists.
    private fun FeedAircraft.hybrid_feed_key(): String {
        val hex = icao24.trim().lowercase(Locale.US)
        if (hex.isNotBlank()) return "hex:$hex"
        registration?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
            ?.let { return "reg:$it" }
        return "pos:${"%.4f".format(Locale.US, lat)}:${
            "%.4f".format(
                Locale.US,
                lon
            )
        }:${callsign.trim().uppercase(Locale.US)}"
    }

    private fun fresher_position_aircraft(first: FeedAircraft, second: FeedAircraft): FeedAircraft {
        val first_position_time = first.position_time_sec ?: first.last_contact_sec ?: 0.0
        val second_position_time = second.position_time_sec ?: second.last_contact_sec ?: 0.0
        if (abs(first_position_time - second_position_time) > POSITION_TIME_TIE_SECONDS) {
            return if (second_position_time > first_position_time) second else first
        }
        val first_contact_time = first.last_contact_sec ?: first.position_time_sec ?: 0.0
        val second_contact_time = second.last_contact_sec ?: second.position_time_sec ?: 0.0
        return if (second_contact_time >= first_contact_time) second else first
    }

    // Keep the shared Aircraft model as a direct translation of feed data with nullable unknowns preserved.
    private fun FeedAircraft.to_map_aircraft(): Aircraft {
        return Aircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            type_code = type_code,
            metadata_seed = metadata,
            is_military = db_flags?.let { it and DB_FLAG_MILITARY != 0 } == true,
            lat = lat,
            lon = lon,
            on_ground = on_ground,
            altitude_m = altitude_m,
            velocity_ms = velocity_ms,
            track_deg = track_deg,
            vertical_rate_ms = vertical_rate_ms,
            category = category,
            position_time_sec = position_time_sec,
            last_contact_sec = last_contact_sec,
            distance_m = distance_m,
            telemetry = telemetry
        )
    }

    private companion object {
        const val DB_FLAG_MILITARY = 1
        const val POSITION_TIME_TIE_SECONDS =
            FlightAlertAppSettings.AircraftFeed.POSITION_TIME_TIE_SECONDS
        const val HYBRID_GLOBE_STARTUP_GRACE_MS =
            FlightAlertAppSettings.AircraftFeed.HYBRID_GLOBE_STARTUP_GRACE_MS
        const val HYBRID_GLOBE_STARTUP_POLL_MS =
            FlightAlertAppSettings.AircraftFeed.HYBRID_GLOBE_STARTUP_POLL_MS
    }

    private fun GlobeBinCraftAircraftSource.await_latest_snapshot(
        feed_bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double,
        exact_search: String?
    ): FeedResult? {
        val deadline = SystemClock.elapsedRealtime() + HYBRID_GLOBE_STARTUP_GRACE_MS
        var result = latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
        while (result?.status != FeedStatus.OK && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(HYBRID_GLOBE_STARTUP_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result
            }
            result = latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
        }
        return result
    }
}

data class VisibleAircraftRequest(
    val feed_bounds: FeedBounds,
    val safety_api_bounds: FeedBounds?,
    val own_lat: Double,
    val own_lon: Double,
    val center_lat: Double,
    val center_lon: Double,
    val zoom: Double,
    val feed_mode: AircraftFeedMode,
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

    fun publish_startup_globe_snapshot_if_needed(feed_mode: AircraftFeedMode) {
        publish_globe_snapshot_update_if_useful(feed_mode)
    }

    fun publish_globe_snapshot_update_if_useful(feed_mode: AircraftFeedMode) {
        val source = globe_source ?: return
        if (!feed_mode.uses_globe) return
        if (!has_location() || !has_usable_viewport()) return
        val now = SystemClock.elapsedRealtime()
        val startup_publish = current_total_aircraft() < ready_aircraft_min
        if (!startup_publish && should_defer_for_interaction(now)) {
            schedule_refresh(delay_after_interaction(now), force = true)
            return
        }
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
        val result_signature =
            if (publish_while_fetching) fetch_signature ?: signature else signature
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
        const val TAG = FlightAlertAppSettings.App.TAG
        const val GLOBE_SNAPSHOT_CADENCE_JITTER_MS =
            FlightAlertAppSettings.AircraftFeed.GLOBE_SNAPSHOT_CADENCE_JITTER_MS
    }
}
