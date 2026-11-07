@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.sources

import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.map.clamped_haversine_distance_meters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.ceil

private fun JSONArray.feed_json_array_boolean_or_null(index: Int): Boolean? {
    if (index >= length() || isNull(index)) return null
    return optBoolean(index)
}

private fun JSONObject.feed_json_int_or_null(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

private fun JSONObject.feed_json_finite_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null
}

private fun JSONObject.feed_json_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}

private fun JSONObject.feed_json_number_as_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toString()
        is String -> raw.trim().takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
        else -> null
    }
}

private fun JSONObject.feed_json_string_list(key: String): List<String> {
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

private fun JSONObject.feed_json_message_rate_or_null(): Double? {
    return feed_json_finite_double_or_null("message_rate")
        ?: feed_json_finite_double_or_null("msgRate")
        ?: feed_json_finite_double_or_null("messageRate")
}

private fun JSONObject.feed_json_receiver_count_label(): String? {
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

private fun feed_distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
    clamped_haversine_distance_meters(lat1, lon1, lat2, lon2)

private fun feed_knots_to_meters_per_second(knots: Double): Double = knots * 0.514444

private fun feed_feet_per_minute_to_meters_per_second(feet_per_minute: Double): Double =
    feet_per_minute / 196.850394

private fun adsb_emitter_category(raw_category: String?): Int? {
    return when (raw_category?.trim()?.uppercase(Locale.US)) {
        "A1" -> 2
        "A2" -> 3
        "A3" -> 4
        "A4" -> 5
        "A5" -> 6
        "A6" -> 7
        "A7" -> 8
        "B1" -> 9
        "B2" -> 10
        "B3" -> 11
        "B4" -> 12
        "B6" -> 14
        "B7" -> 15
        "C1" -> 16
        "C2" -> 17
        "C3" -> 18
        "C4" -> 19
        "C5" -> 20
        else -> null
    }
}

class AircraftFeedClient(private val user_agent: String) {
    private data class ReadsbPointQuery(
        val center_lat: Double,
        val center_lon: Double,
        val radius_nm: Double
    )

    private data class ReadsbQueryPlan(
        val queries: List<ReadsbPointQuery>,
        val partial_coverage: Boolean
    )

    /** One bounded public API request for foreground globe-outage recovery. */
    internal fun fetch_adsb_lol_point(
        center_lat: Double,
        center_lon: Double,
        radius_nm: Int,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        if (!center_lat.isFinite() || center_lat !in -90.0..90.0 ||
            !center_lon.isFinite() || center_lon !in -180.0..180.0 ||
            radius_nm !in 1..250 || !own_lat.isFinite() || own_lat !in -90.0..90.0 ||
            !own_lon.isFinite() || own_lon !in -180.0..180.0) {
            return FeedResult(FeedStatus.UNAVAILABLE, FeedSource.ADSB_LOL,
                partial_coverage = true, failure_reason = "Invalid recovery point query")
        }
        return fetch_readsb_point(ReadsbPointQuery(center_lat, center_lon, radius_nm.toDouble()),
            own_lat, own_lon, ReadsbFeedProvider.ADSB_LOL).copy(partial_coverage = true)
    }


    fun fetch_aircraft(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double,
        exact_search: String? = null
    ): FeedResult {
        val viewport_result = fetch_viewport_aircraft(bounds, own_lat, own_lon)
        val search_result = fetch_readsb_exact_search(exact_search, own_lat, own_lon)
        return merge_viewport_and_search(viewport_result, search_result)
    }

    // Use ADSB.lol first, retaining the other real providers when it is unavailable.
    private fun fetch_viewport_aircraft(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        if (bounds.min_lon <= bounds.max_lon) {
            return fetch_non_wrapping_viewport_aircraft(bounds, own_lat, own_lon)
        }
        val west = fetch_non_wrapping_viewport_aircraft(
            bounds.copy(max_lon = 180.0),
            own_lat,
            own_lon
        )
        val east = fetch_non_wrapping_viewport_aircraft(
            bounds.copy(min_lon = -180.0),
            own_lat,
            own_lon
        )
        return merge_wrapped_viewport_results(west, east)
    }

    private fun fetch_non_wrapping_viewport_aircraft(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double
    ): FeedResult {
        val now = System.currentTimeMillis()
        var provider_result = FeedResult(FeedStatus.UNAVAILABLE, FeedSource.ADSB_LOL)
        for (provider in ReadsbFeedProvider.entries) {
            provider_result = fetch_readsb_area(bounds, own_lat, own_lon, provider)
            if (provider_result.status == FeedStatus.OK) return provider_result
        }

        val open_sky_result =
            if (now >= open_sky_retry_after_ms) fetch_open_sky(bounds, own_lat, own_lon) else null
        if (open_sky_result?.status == FeedStatus.OK) return open_sky_result

        return open_sky_result ?: provider_result
    }

    private fun merge_wrapped_viewport_results(
        west: FeedResult,
        east: FeedResult
    ): FeedResult {
        if (west.status != FeedStatus.OK && east.status != FeedStatus.OK) {
            return if (east.status == FeedStatus.RATE_LIMITED) east else west
        }
        if (west.status != FeedStatus.OK || east.status != FeedStatus.OK) {
            val available = if (west.status == FeedStatus.OK) west else east
            return available.copy(
                query_count = west.query_count + east.query_count,
                partial_coverage = true
            )
        }
        val merged = linkedMapOf<String, FeedAircraft>()
        west.aircraft.forEach { item -> merged[item.feed_key()] = item }
        east.aircraft.forEach { item ->
            val key = item.feed_key()
            merged[key] = newer_aircraft(merged[key], item)
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = merged_source(west.source, east.source),
            aircraft = merged.values.sortedBy { it.distance_m },
            epoch_sec = max_epoch(west.epoch_sec, east.epoch_sec),
            query_count = west.query_count + east.query_count,
            partial_coverage = west.partial_coverage || east.partial_coverage
        )
    }

    private fun merge_viewport_and_search(viewport: FeedResult, search: FeedResult?): FeedResult {
        if (search == null) return viewport
        if (search.status != FeedStatus.OK) return viewport
        if (viewport.status != FeedStatus.OK) return search
        if (search.aircraft.isEmpty()) return viewport.copy(query_count = viewport.query_count + search.query_count)
        return merge_complete_coverage_with_detail(viewport, search)
    }


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

    // Split large bounds into real point queries, preserving partial coverage at wide zoom.
    private fun fetch_readsb_area(
        bounds: FeedBounds,
        own_lat: Double,
        own_lon: Double,
        provider: ReadsbFeedProvider
    ): FeedResult {
        val plan = airplanes_live_query_plan(bounds)
        val merged = linkedMapOf<String, FeedAircraft>()
        var latest_epoch_sec: Double? = null
        var saw_ok = false
        var saw_rate_limit = false
        var last_http_code: Int? = null
        val query_results = fetch_readsb_points(plan.queries, own_lat, own_lon, provider)
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
                source = provider.api_source,
                aircraft = merged.values.sortedBy { it.distance_m },
                epoch_sec = latest_epoch_sec,
                query_count = plan.queries.size,
                partial_coverage = plan.partial_coverage || timed_out ||
                    query_results.any { it.status != FeedStatus.OK }
            )

            saw_rate_limit -> FeedResult(
                status = FeedStatus.RATE_LIMITED,
                source = provider.api_source,
                http_code = last_http_code,
                query_count = plan.queries.size,
                partial_coverage = plan.partial_coverage
            )

            else -> FeedResult(
                status = FeedStatus.UNAVAILABLE,
                source = provider.api_source,
                http_code = last_http_code,
                query_count = plan.queries.size,
                partial_coverage = plan.partial_coverage || timed_out,
                failure_reason = query_results.lastOrNull()?.failure_reason
            )
        }
    }

    private fun fetch_readsb_points(
        queries: List<ReadsbPointQuery>,
        own_lat: Double,
        own_lon: Double,
        provider: ReadsbFeedProvider
    ): List<FeedResult> {
        return queries.map { query -> fetch_readsb_point(query, own_lat, own_lon, provider) }
    }

    private fun fetch_readsb_point(
        query: ReadsbPointQuery,
        own_lat: Double,
        own_lon: Double,
        provider: ReadsbFeedProvider
    ): FeedResult {
        val path = String.format(
            Locale.US,
            "point/%.5f/%.5f/%.0f",
            query.center_lat,
            query.center_lon,
            query.radius_nm
        )
        return fetch_readsb_path(path, own_lat, own_lon, provider)
    }

    private fun fetch_readsb_exact_search(
        search: String?,
        own_lat: Double,
        own_lon: Double
    ): FeedResult? {
        val path = airplanes_live_search_path(search) ?: return null
        var result: FeedResult? = null
        for (provider in ReadsbFeedProvider.entries) {
            result = fetch_readsb_path(path, own_lat, own_lon, provider)
            if (result.status == FeedStatus.OK) return result
        }
        return result
    }

    // All callers share each provider's rate limit and blocked-response backoff.
    private fun fetch_readsb_path(
        path: String,
        own_lat: Double,
        own_lon: Double,
        provider: ReadsbFeedProvider
    ): FeedResult {
        var connection: HttpURLConnection? = null
        return try {
            provider.await_api_slot()?.let { return it }
            val url = URL("${provider.api_base_url}/$path")
            connection = open_connection(url)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK &&
                !connection.getHeaderField("cf-mitigated").equals("challenge", ignoreCase = true)) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                require(json.has("now") && (json.optJSONArray("ac") != null ||
                    json.optJSONArray("aircraft") != null)) { "Invalid aircraft response" }
                FeedResult(
                    status = FeedStatus.OK,
                    source = provider.api_source,
                    aircraft = parse_readsb_aircraft(json, own_lat, own_lon),
                    epoch_sec = json.feed_json_finite_double_or_null("now")
                        ?.let { normalize_epoch_seconds(it) },
                    query_count = 1
                )
            } else {
                val failure = aircraft_feed_http_failure(provider.api_source, code,
                    connection.getHeaderField("cf-mitigated"))
                provider.back_off_api(failure,
                    aircraft_feed_retry_delay_ms(failure, connection.getHeaderField("Retry-After")))
                connection.errorStream?.close()
                failure
            }
        } catch (_: Exception) {
            FeedResult(FeedStatus.UNAVAILABLE, provider.api_source,
                failure_reason = "Connection or response unavailable").also {
                provider.back_off_api(it, 2000L)
            }
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
    private fun airplanes_live_query_plan(bounds: FeedBounds): ReadsbQueryPlan {
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

        val queries = mutableListOf<ReadsbPointQuery>()
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
                queries += ReadsbPointQuery(
                    center_lat = cell_center_lat,
                    center_lon = cell_center_lon,
                    radius_nm = radius_nm_for(cell, cell_center_lat, cell_center_lon)
                        .times(AIRPLANES_QUERY_RADIUS_PADDING)
                        .coerceIn(MIN_AIRPLANES_RADIUS_NM, MAX_AIRPLANES_RADIUS_NM)
                )
            }
        }

        return ReadsbQueryPlan(
            queries = queries.ifEmpty {
                listOf(ReadsbPointQuery(center_lat, center_lon, MIN_AIRPLANES_RADIUS_NM))
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

    // Both public APIs return readsb objects; retain missing fields as unavailable.
    private fun parse_readsb_aircraft(
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
                category = adsb_emitter_category(item.optString("category")),
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

    private fun normalize_epoch_seconds(value: Double): Double {
        return if (value > 10_000_000_000.0) value / 1000.0 else value
    }


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

    }
}
