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
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.details.json_int_or_null
import com.flightalert.details.json_number_or_null
import com.flightalert.details.max_epoch
import com.flightalert.map.clamped_haversine_distance_meters
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

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
            looks_like_registration_search(upper) -> "reg/${URLEncoder.encode(upper, "UTF-8")}"
            CALLSIGN_SEARCH.matches(upper) -> "callsign/${URLEncoder.encode(upper, "UTF-8")}"
            else -> null
        }
    }

    private fun looks_like_registration_search(upper: String): Boolean {
        return upper.startsWith("N") || upper.contains("-")
    }

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
