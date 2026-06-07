package com.flightalert.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AircraftFeedClient(private val userAgent: String) {

    fun fetchAircraft(bounds: FeedBounds, ownLat: Double, ownLon: Double): FeedResult {
        val now = System.currentTimeMillis()
        val airplanesResult = fetchAirplanesLive(bounds, ownLat, ownLon)
        if (airplanesResult.status == FeedStatus.OK && airplanesResult.aircraft.isNotEmpty()) return airplanesResult

        val openSkyResult = if (now >= openSkyRetryAfterMs) fetchOpenSky(bounds, ownLat, ownLon) else null
        if (openSkyResult?.status == FeedStatus.OK && openSkyResult.aircraft.isNotEmpty()) return openSkyResult

        if (airplanesResult.status == FeedStatus.OK) return airplanesResult
        if (openSkyResult?.status == FeedStatus.OK) return openSkyResult

        return openSkyResult ?: airplanesResult
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
            val code = connection.responseCode
            when (code) {
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
        var connection: HttpURLConnection? = null
        return try {
            val centerLat = (bounds.minLat + bounds.maxLat) / 2.0
            val centerLon = (bounds.minLon + bounds.maxLon) / 2.0
            val radiusNm = radiusNmFor(bounds, centerLat, centerLon).coerceIn(MIN_AIRPLANES_RADIUS_NM, MAX_AIRPLANES_RADIUS_NM)
            val url = URL(
                String.format(
                    Locale.US,
                    "https://api.airplanes.live/v2/point/%.5f/%.5f/%.0f",
                    centerLat,
                    centerLon,
                    radiusNm
                )
            )
            connection = openConnection(url)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                FeedResult(
                    status = FeedStatus.OK,
                    source = FeedSource.AIRPLANES_LIVE,
                    aircraft = parseAirplanesLiveAircraft(json, ownLat, ownLon),
                    epochSec = json.optDoubleOrNull("now")?.let { normalizeEpochSeconds(it) }
                )
            } else {
                connection.errorStream?.close()
                FeedResult(
                    status = if (code == HTTP_TOO_MANY_REQUESTS) FeedStatus.RATE_LIMITED else FeedStatus.UNAVAILABLE,
                    source = FeedSource.AIRPLANES_LIVE,
                    httpCode = code
                )
            }
        } catch (_: Exception) {
            FeedResult(FeedStatus.UNAVAILABLE, FeedSource.AIRPLANES_LIVE)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS aircraft feeds are allowed" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
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

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val DEFAULT_OPENSKY_BACKOFF_SECONDS = 3600L
        private const val MIN_AIRPLANES_RADIUS_NM = 25.0
        private const val MAX_AIRPLANES_RADIUS_NM = 250.0
        private const val METERS_PER_NAUTICAL_MILE = 1852.0
        private const val FEET_PER_METER = 3.28084

        @Volatile
        private var openSkyRetryAfterMs = 0L
    }
}

data class FeedBounds(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double)

data class FeedResult(
    val status: FeedStatus,
    val source: FeedSource,
    val aircraft: List<FeedAircraft> = emptyList(),
    val epochSec: Double? = null,
    val httpCode: Int? = null
)

data class FeedAircraft(
    val icao24: String,
    val callsign: String,
    val registration: String?,
    val typeCode: String?,
    val dbFlags: Int?,
    val lat: Double,
    val lon: Double,
    val onGround: Boolean?,
    val altitudeM: Double?,
    val velocityMs: Double?,
    val trackDeg: Double?,
    val verticalRateMs: Double?,
    val category: Int?,
    val positionTimeSec: Double?,
    val lastContactSec: Double?,
    val distanceM: Double
)

enum class FeedStatus {
    OK,
    RATE_LIMITED,
    UNAVAILABLE
}

enum class FeedSource(val displayName: String) {
    OPENSKY("OpenSky"),
    AIRPLANES_LIVE("Airplanes.Live")
}

private fun JSONArray.optNullableDouble(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun JSONArray.optNullableLong(index: Int): Long? {
    if (index >= length() || isNull(index)) return null
    return optLong(index)
}

private fun JSONArray.optNullableInt(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}

private fun JSONArray.optNullableBoolean(index: Int): Boolean? {
    if (index >= length() || isNull(index)) return null
    return optBoolean(index)
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    return if (has(key) && !isNull(key)) optLong(key) else null
}

private fun JSONObject.optIntOrNull(key: String): Int? {
    return if (has(key) && !isNull(key)) optInt(key) else null
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

private fun JSONObject.optAltitudeFeet(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key)
    return when (raw) {
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
