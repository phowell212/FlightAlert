package com.flightalert.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AircraftFeedClient(private val userAgent: String) {

    fun fetchAircraft(bounds: FeedBounds, ownLat: Double, ownLon: Double, exactSearch: String? = null): FeedResult {
        val viewportResult = fetchViewportAircraft(bounds, ownLat, ownLon)
        val searchResult = fetchAirplanesLiveExactSearch(exactSearch, ownLat, ownLon)
        return mergeViewportAndSearch(viewportResult, searchResult)
    }

    private fun fetchViewportAircraft(bounds: FeedBounds, ownLat: Double, ownLon: Double): FeedResult {
        val now = System.currentTimeMillis()
        val airplanesResult = if (now >= airplanesLiveRetryAfterMs) {
            fetchAirplanesLive(bounds, ownLat, ownLon)
        } else {
            FeedResult(FeedStatus.RATE_LIMITED, FeedSource.AIRPLANES_LIVE, httpCode = HTTP_TOO_MANY_REQUESTS)
        }
        if (airplanesResult.status == FeedStatus.OK) {
            return airplanesResult
        }

        val openSkyResult = if (now >= openSkyRetryAfterMs) fetchOpenSky(bounds, ownLat, ownLon) else null
        if (openSkyResult?.status == FeedStatus.OK) return openSkyResult

        return openSkyResult ?: airplanesResult
    }

    private fun mergeViewportAndSearch(viewport: FeedResult, search: FeedResult?): FeedResult {
        if (search == null) return viewport
        if (search.status != FeedStatus.OK) return viewport
        if (viewport.status != FeedStatus.OK) return search
        if (search.aircraft.isEmpty()) return viewport.copy(queryCount = viewport.queryCount + search.queryCount)
        return mergeCompleteCoverageWithDetail(viewport, search)
    }

    private fun mergeCompleteCoverageWithDetail(complete: FeedResult, detail: FeedResult): FeedResult {
        val merged = linkedMapOf<String, FeedAircraft>()
        complete.aircraft.forEach { item ->
            merged[item.feedKey()] = item
        }
        detail.aircraft.forEach { item ->
            val key = item.feedKey()
            merged[key] = newerAircraft(merged[key], item)
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = mergedSource(complete.source, detail.source),
            aircraft = merged.values.sortedBy { it.distanceM },
            epochSec = maxOfEpoch(complete.epochSec, detail.epochSec),
            queryCount = complete.queryCount + detail.queryCount,
            partialCoverage = false
        )
    }

    private fun fetchOpenSky(bounds: FeedBounds, ownLat: Double, ownLon: Double): FeedResult {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(
                String.format(
                    Locale.US,
                    "https://opensky-network.org/api/states/all?lamin=%.5f&lomin=%.5f&lamax=%.5f&lomax=%.5f&extended=1",
                    bounds.minLat,
                    bounds.minLon,
                    bounds.maxLat,
                    bounds.maxLon
                )
            )
            connection = openConnection(url)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    FeedResult(
                        status = FeedStatus.OK,
                        source = FeedSource.OPENSKY,
                        aircraft = parseOpenSkyAircraft(json, ownLat, ownLon),
                        epochSec = json.optDoubleOrNull("time")
                    )
                }
                HTTP_TOO_MANY_REQUESTS -> {
                    val retrySeconds = connection.getHeaderField("X-Rate-Limit-Retry-After-Seconds")?.toLongOrNull()
                    openSkyRetryAfterMs = System.currentTimeMillis() + ((retrySeconds ?: DEFAULT_OPENSKY_BACKOFF_SECONDS) * 1000L)
                    connection.errorStream?.close()
                    FeedResult(FeedStatus.RATE_LIMITED, FeedSource.OPENSKY, httpCode = code)
                }
                else -> {
                    connection.errorStream?.close()
                    FeedResult(FeedStatus.UNAVAILABLE, FeedSource.OPENSKY, httpCode = code)
                }
            }
        } catch (_: Exception) {
            FeedResult(FeedStatus.UNAVAILABLE, FeedSource.OPENSKY)
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchAirplanesLive(bounds: FeedBounds, ownLat: Double, ownLon: Double): FeedResult {
        val plan = airplanesLiveQueryPlan(bounds)
        val merged = linkedMapOf<String, FeedAircraft>()
        var latestEpochSec: Double? = null
        var sawOk = false
        var sawRateLimit = false
        var lastHttpCode: Int? = null
        val queryResults = fetchAirplanesLivePoints(plan.queries, ownLat, ownLon)
        val timedOut = queryResults.size < plan.queries.size

        for (result in queryResults) {
            latestEpochSec = maxOfEpoch(latestEpochSec, result.epochSec)
            lastHttpCode = result.httpCode ?: lastHttpCode
            when (result.status) {
                FeedStatus.OK -> {
                    sawOk = true
                    result.aircraft.forEach { item ->
                        val key = item.feedKey()
                        merged[key] = newerAircraft(merged[key], item)
                    }
                }
                FeedStatus.RATE_LIMITED -> {
                    sawRateLimit = true
                    lastHttpCode = result.httpCode ?: HTTP_TOO_MANY_REQUESTS
                }
                FeedStatus.UNAVAILABLE -> Unit
            }
        }

        return when {
            sawOk -> FeedResult(
                status = FeedStatus.OK,
                source = FeedSource.AIRPLANES_LIVE,
                aircraft = merged.values.sortedBy { it.distanceM },
                epochSec = latestEpochSec,
                queryCount = plan.queries.size,
                partialCoverage = plan.partialCoverage || timedOut
            )
            sawRateLimit -> FeedResult(
                status = FeedStatus.RATE_LIMITED,
                source = FeedSource.AIRPLANES_LIVE,
                httpCode = lastHttpCode,
                queryCount = plan.queries.size,
                partialCoverage = plan.partialCoverage
            )
            else -> FeedResult(
                status = FeedStatus.UNAVAILABLE,
                source = FeedSource.AIRPLANES_LIVE,
                httpCode = lastHttpCode,
                queryCount = plan.queries.size,
                partialCoverage = plan.partialCoverage || timedOut
            )
        }
    }

    private fun fetchAirplanesLivePoints(
        queries: List<AirplanesLiveQuery>,
        ownLat: Double,
        ownLon: Double
    ): List<FeedResult> {
        return queries.map { query -> fetchAirplanesLivePoint(query, ownLat, ownLon) }
    }

    private fun fetchAirplanesLivePoint(query: AirplanesLiveQuery, ownLat: Double, ownLon: Double): FeedResult {
        val path = String.format(
            Locale.US,
            "point/%.5f/%.5f/%.0f",
            query.centerLat,
            query.centerLon,
            query.radiusNm
        )
        return fetchAirplanesLivePath(path, ownLat, ownLon)
    }

    private fun fetchAirplanesLiveExactSearch(search: String?, ownLat: Double, ownLon: Double): FeedResult? {
        val path = airplanesLiveSearchPath(search) ?: return null
        if (System.currentTimeMillis() < airplanesLiveRetryAfterMs) {
            return FeedResult(FeedStatus.RATE_LIMITED, FeedSource.AIRPLANES_LIVE, httpCode = HTTP_TOO_MANY_REQUESTS)
        }
        return fetchAirplanesLivePath(path, ownLat, ownLon)
    }

    private fun fetchAirplanesLivePath(path: String, ownLat: Double, ownLon: Double): FeedResult {
        var connection: HttpURLConnection? = null
        return try {
            AirplanesLiveHttp.waitForRestApiSlot()
            val url = URL("${AirplanesLiveHttp.API_BASE_URL}/$path")
            connection = openConnection(url)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                FeedResult(
                    status = FeedStatus.OK,
                    source = FeedSource.AIRPLANES_LIVE,
                    aircraft = parseAirplanesLiveAircraft(json, ownLat, ownLon),
                    epochSec = json.optDoubleOrNull("now")?.let { normalizeEpochSeconds(it) },
                    queryCount = 1
                )
            } else {
                if (code == HTTP_TOO_MANY_REQUESTS) {
                    val retrySeconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        ?: DEFAULT_AIRPLANES_LIVE_BACKOFF_SECONDS
                    AirplanesLiveHttp.backOffRestApi(retrySeconds)
                    airplanesLiveRetryAfterMs = System.currentTimeMillis() + retrySeconds * 1000L
                }
                connection.errorStream?.close()
                FeedResult(
                    status = if (code == HTTP_TOO_MANY_REQUESTS) FeedStatus.RATE_LIMITED else FeedStatus.UNAVAILABLE,
                    source = FeedSource.AIRPLANES_LIVE,
                    httpCode = code,
                    queryCount = 1
                )
            }
        } catch (_: Exception) {
            FeedResult(FeedStatus.UNAVAILABLE, FeedSource.AIRPLANES_LIVE, queryCount = 1)
        } finally {
            connection?.disconnect()
        }
    }

    private fun airplanesLiveSearchPath(search: String?): String? {
        val raw = search?.trim().orEmpty()
        if (raw.length < 2) return null
        val compact = raw.replace("\\s+".toRegex(), "").take(MAX_EXACT_SEARCH_CHARS)
        if (!EXACT_SEARCH_ALLOWED.matches(compact)) return null
        val upper = compact.uppercase(Locale.US)
        return when {
            MODE_S_HEX.matches(upper) -> "hex/${upper.lowercase(Locale.US)}"
            looksLikeRegistrationSearch(upper) -> "reg/${urlEncode(upper)}"
            CALLSIGN_SEARCH.matches(upper) -> "callsign/${urlEncode(upper)}"
            else -> null
        }
    }

    private fun looksLikeRegistrationSearch(upper: String): Boolean {
        return upper.startsWith("N") || upper.contains("-")
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun airplanesLiveQueryPlan(bounds: FeedBounds): AirplanesLiveQueryPlan {
        val normalized = bounds.normalized()
        val centerLat = (normalized.minLat + normalized.maxLat) / 2.0
        val centerLon = (normalized.minLon + normalized.maxLon) / 2.0
        val widthM = distanceMeters(centerLat, normalized.minLon, centerLat, normalized.maxLon)
        val heightM = distanceMeters(normalized.minLat, centerLon, normalized.maxLat, centerLon)
        val targetCellSpanM = MAX_AIRPLANES_RADIUS_NM * METERS_PER_NAUTICAL_MILE * AIRPLANES_GRID_CELL_RADIUS_FACTOR
        var columns = ceil(widthM / targetCellSpanM).toInt().coerceAtLeast(1)
        var rows = ceil(heightM / targetCellSpanM).toInt().coerceAtLeast(1)
        val neededRows = rows
        val neededColumns = columns
        while (rows * columns > MAX_AIRPLANES_LIVE_QUERIES) {
            if (columns >= rows && columns > 1) columns-- else if (rows > 1) rows-- else break
        }

        val queries = mutableListOf<AirplanesLiveQuery>()
        for (row in 0 until rows) {
            val minLat = lerp(normalized.minLat, normalized.maxLat, row.toDouble() / rows)
            val maxLat = lerp(normalized.minLat, normalized.maxLat, (row + 1.0) / rows)
            for (column in 0 until columns) {
                val minLon = lerp(normalized.minLon, normalized.maxLon, column.toDouble() / columns)
                val maxLon = lerp(normalized.minLon, normalized.maxLon, (column + 1.0) / columns)
                val cell = FeedBounds(minLat, minLon, maxLat, maxLon)
                val cellCenterLat = (minLat + maxLat) / 2.0
                val cellCenterLon = (minLon + maxLon) / 2.0
                queries += AirplanesLiveQuery(
                    centerLat = cellCenterLat,
                    centerLon = cellCenterLon,
                    radiusNm = radiusNmFor(cell, cellCenterLat, cellCenterLon)
                        .times(AIRPLANES_QUERY_RADIUS_PADDING)
                        .coerceIn(MIN_AIRPLANES_RADIUS_NM, MAX_AIRPLANES_RADIUS_NM)
                )
            }
        }

        return AirplanesLiveQueryPlan(
            queries = queries.ifEmpty {
                listOf(AirplanesLiveQuery(centerLat, centerLon, MIN_AIRPLANES_RADIUS_NM))
            },
            partialCoverage = rows < neededRows || columns < neededColumns
        )
    }

    private fun openConnection(url: URL): HttpURLConnection {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS aircraft feeds are allowed" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            requestMethod = "GET"
            if (url.host.equals("api.airplanes.live", ignoreCase = true)) {
                AirplanesLiveHttp.applyBrowserHeaders(this, userAgent)
            } else {
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
        }
    }

    private fun parseOpenSkyAircraft(json: JSONObject, ownLat: Double, ownLon: Double): List<FeedAircraft> {
        val rows = json.optJSONArray("states") ?: JSONArray()
        val parsed = mutableListOf<FeedAircraft>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val lon = row.optNullableDouble(5) ?: continue
            val lat = row.optNullableDouble(6) ?: continue
            val icao24 = row.optString(0).trim()
            val callsign = row.optString(1).trim().ifEmpty { icao24.ifEmpty { "Unknown" } }
            parsed += FeedAircraft(
                icao24 = icao24,
                callsign = callsign,
                registration = null,
                typeCode = null,
                dbFlags = null,
                lat = lat,
                lon = lon,
                onGround = row.optNullableBoolean(8),
                altitudeM = row.optNullableDouble(7) ?: row.optNullableDouble(13),
                velocityMs = row.optNullableDouble(9),
                trackDeg = row.optNullableDouble(10),
                verticalRateMs = row.optNullableDouble(11),
                category = row.optNullableInt(17),
                positionTimeSec = row.optNullableDouble(3),
                lastContactSec = row.optNullableDouble(4),
                distanceM = distanceMeters(ownLat, ownLon, lat, lon)
            )
        }
        return parsed.sortedBy { it.distanceM }
    }

    private fun parseAirplanesLiveAircraft(json: JSONObject, ownLat: Double, ownLon: Double): List<FeedAircraft> {
        val nowSec = json.optDoubleOrNull("now")?.let { normalizeEpochSeconds(it) } ?: (System.currentTimeMillis() / 1000.0)
        val rows = json.optJSONArray("aircraft") ?: json.optJSONArray("ac") ?: JSONArray()
        val parsed = mutableListOf<FeedAircraft>()
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val lat = item.optDoubleOrNull("lat") ?: item.optJSONObject("lastPosition")?.optDoubleOrNull("lat") ?: continue
            val lon = item.optDoubleOrNull("lon") ?: item.optJSONObject("lastPosition")?.optDoubleOrNull("lon") ?: continue
            val icao24 = item.optString("hex").trim().trimStart('~').lowercase(Locale.US)
            val callsign = item.optString("flight").trim().ifEmpty { item.optString("r").trim().ifEmpty { icao24.ifEmpty { "Unknown" } } }
            val typeCode = item.optString("t").trim().ifEmpty { null }
            val altitudeFeet = item.optAltitudeFeet("alt_baro") ?: item.optAltitudeFeet("alt_geom")
            val seenPositionSec = item.optDoubleOrNull("seen_pos")
            val seenMessageSec = item.optDoubleOrNull("seen")
            val lastPositionTime = if (seenPositionSec != null) nowSec - seenPositionSec else nowSec
            val lastContactTime = if (seenMessageSec != null) nowSec - seenMessageSec else lastPositionTime
            parsed += FeedAircraft(
                icao24 = icao24,
                callsign = callsign,
                registration = item.optString("r").trim().ifEmpty { null },
                typeCode = typeCode,
                dbFlags = item.optIntOrNull("dbFlags"),
                lat = lat,
                lon = lon,
                onGround = item.optString("alt_baro") == "ground",
                altitudeM = altitudeFeet?.let { it / FEET_PER_METER },
                velocityMs = item.optDoubleOrNull("gs")?.let { knotsToMetersPerSecond(it) },
                trackDeg = item.optDoubleOrNull("track"),
                verticalRateMs = (item.optDoubleOrNull("baro_rate") ?: item.optDoubleOrNull("geom_rate"))?.let { feetPerMinuteToMetersPerSecond(it) },
                category = categoryFromAirplanesLive(item.optString("category"), item.optString("t")),
                positionTimeSec = lastPositionTime,
                lastContactSec = lastContactTime,
                distanceM = distanceMeters(ownLat, ownLon, lat, lon)
            )
        }
        return parsed.sortedBy { it.distanceM }
    }

    private fun radiusNmFor(bounds: FeedBounds, centerLat: Double, centerLon: Double): Double {
        val corners = listOf(
            bounds.minLat to bounds.minLon,
            bounds.minLat to bounds.maxLon,
            bounds.maxLat to bounds.minLon,
            bounds.maxLat to bounds.maxLon
        )
        return corners.maxOf { (lat, lon) -> distanceMeters(centerLat, centerLon, lat, lon) } / METERS_PER_NAUTICAL_MILE
    }

    private fun categoryFromAirplanesLive(category: String, typeCode: String): Int? {
        val normalized = category.trim().uppercase(Locale.US)
        if (normalized.startsWith("A7") || normalized.startsWith("B7")) return 8
        if (normalized.startsWith("B1")) return 9
        if (normalized.startsWith("B6")) return 14
        if (normalized.startsWith("C") || normalized.startsWith("D")) return 15
        val type = typeCode.trim().uppercase(Locale.US)
        return when {
            type.startsWith("H") || type.startsWith("R") -> 8
            type.startsWith("GL") -> 9
            type.startsWith("UAV") || type.startsWith("DRON") -> 14
            else -> null
        }
    }

    private fun normalizeEpochSeconds(value: Double): Double {
        return if (value > 10_000_000_000.0) value / 1000.0 else value
    }

    private fun maxOfEpoch(first: Double?, second: Double?): Double? {
        return when {
            first == null -> second
            second == null -> first
            else -> max(first, second)
        }
    }

    private fun newerAircraft(existing: FeedAircraft?, incoming: FeedAircraft): FeedAircraft {
        if (existing == null) return incoming
        val existingTime = existing.lastContactSec ?: existing.positionTimeSec ?: 0.0
        val incomingTime = incoming.lastContactSec ?: incoming.positionTimeSec ?: 0.0
        return if (incomingTime >= existingTime) incoming else existing
    }

    private fun lerp(start: Double, end: Double, amount: Double): Double {
        return start + (end - start) * amount
    }

    private fun mergedSource(first: FeedSource, second: FeedSource): FeedSource {
        return if (first == second) first else FeedSource.COMBINED
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_OPENSKY_BACKOFF_SECONDS = 3600L
        private const val DEFAULT_AIRPLANES_LIVE_BACKOFF_SECONDS = 120L
        private const val MIN_AIRPLANES_RADIUS_NM = 25.0
        private const val MAX_AIRPLANES_RADIUS_NM = 250.0
        private const val MAX_AIRPLANES_LIVE_QUERIES = 1
        private const val AIRPLANES_GRID_CELL_RADIUS_FACTOR = 1.45
        private const val AIRPLANES_QUERY_RADIUS_PADDING = 1.08
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val FEET_PER_METER = 3.28084
        private const val MAX_EXACT_SEARCH_CHARS = 18
        private val EXACT_SEARCH_ALLOWED = Regex("^[A-Za-z0-9-]+$")
        private val MODE_S_HEX = Regex("^[0-9A-F]{6}$")
        private val CALLSIGN_SEARCH = Regex("^[A-Z0-9]{2,12}$")

        @Volatile
        private var openSkyRetryAfterMs = 0L

        @Volatile
        private var airplanesLiveRetryAfterMs = 0L
    }
}

private data class AirplanesLiveQuery(val centerLat: Double, val centerLon: Double, val radiusNm: Double)

private data class AirplanesLiveQueryPlan(val queries: List<AirplanesLiveQuery>, val partialCoverage: Boolean)

private fun JSONArray.optNullableDouble(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun JSONArray.optNullableInt(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}

private fun JSONArray.optNullableBoolean(index: Int): Boolean? {
    if (index >= length() || isNull(index)) return null
    return optBoolean(index)
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

private fun JSONObject.optAltitudeFeet(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLat = Math.toRadians(lat2 - lat1)
    val deltaLon = Math.toRadians(lon2 - lon1)
    val a = sin(deltaLat / 2.0).pow(2.0) + cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2.0).pow(2.0)
    return 2.0 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}

private fun knotsToMetersPerSecond(knots: Double): Double = knots * 0.514444

private fun feetPerMinuteToMetersPerSecond(feetPerMinute: Double): Double = feetPerMinute / 196.850394

private const val EARTH_RADIUS_M = 6371000.0
