package com.flightalert.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToLong

class OpenSkyClient(private val userAgent: String) {

    fun fetchTrack(icao24: String): List<TrackPoint> {
        val cleanIcao = icao24.trim().lowercase(Locale.US)
        if (cleanIcao.isEmpty()) return emptyList()

        fetchOpenSkyTrack(cleanIcao).takeIf { it.size >= 2 }?.let { return it }
        fetchAdsbLolTrace(cleanIcao, fullTrace = true).takeIf { it.size >= 2 }?.let { return it }
        return fetchAdsbLolTrace(cleanIcao, fullTrace = false)
    }

    private fun fetchOpenSkyTrack(cleanIcao: String): List<TrackPoint> {
        val url = URL("https://opensky-network.org/api/tracks/all?icao24=$cleanIcao&time=0")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseTrack(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchAdsbLolTrace(cleanIcao: String, fullTrace: Boolean): List<TrackPoint> {
        val folder = cleanIcao.takeLast(2)
        val traceName = if (fullTrace) "trace_full_$cleanIcao.json" else "trace_recent_$cleanIcao.json"
        val url = URL("https://adsb.lol/data/traces/$folder/$traceName")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 9000
            readTimeout = 16000
            requestMethod = "GET"
            setRequestProperty("User-Agent", userAgent)
        }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseAdsbLolTrace(JSONObject(body))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseTrack(json: JSONObject): List<TrackPoint> {
        val path = json.optJSONArray("path") ?: return emptyList()
        val points = mutableListOf<TrackPoint>()

        for (index in 0 until path.length()) {
            val row = path.optJSONArray(index) ?: continue
            val timeSec = row.optNullableLong(0) ?: continue
            val lat = row.optNullableDouble(1) ?: continue
            val lon = row.optNullableDouble(2) ?: continue
            points += TrackPoint(
                lat = lat,
                lon = lon,
                epochSec = timeSec,
                altitudeM = row.optNullableDouble(3),
                trackDeg = row.optNullableDouble(4),
                onGround = row.optNullableBoolean(5)
            )
        }

        return points
            .distinctBy { "${it.epochSec}:${"%.5f".format(Locale.US, it.lat)}:${"%.5f".format(Locale.US, it.lon)}" }
            .sortedBy { it.epochSec }
    }

    private fun parseAdsbLolTrace(json: JSONObject): List<TrackPoint> {
        val baseEpochSec = json.optDoubleOrNull("timestamp") ?: return emptyList()
        val trace = json.optJSONArray("trace") ?: return emptyList()
        val points = mutableListOf<TrackPoint>()

        for (index in 0 until trace.length()) {
            val row = trace.optJSONArray(index) ?: continue
            val offsetSec = row.optNullableDouble(0) ?: continue
            val lat = row.optNullableDouble(1) ?: continue
            val lon = row.optNullableDouble(2) ?: continue
            val altitude = row.opt(3)
            val onGround = altitude is String && altitude.equals("ground", ignoreCase = true)
            points += TrackPoint(
                lat = lat,
                lon = lon,
                epochSec = (baseEpochSec + offsetSec).roundToLong(),
                altitudeM = when (altitude) {
                    is Number -> altitude.toDouble() / FEET_PER_METER
                    is String -> altitude.toDoubleOrNull()?.div(FEET_PER_METER) ?: if (onGround) 0.0 else null
                    else -> null
                },
                trackDeg = row.optNullableDouble(5),
                onGround = onGround
            )
        }

        return points
            .distinctBy { "${it.epochSec}:${"%.5f".format(Locale.US, it.lat)}:${"%.5f".format(Locale.US, it.lon)}" }
            .sortedBy { it.epochSec }
    }
}

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val epochSec: Long,
    val altitudeM: Double?,
    val trackDeg: Double?,
    val onGround: Boolean?
)

private fun org.json.JSONArray.optNullableDouble(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun org.json.JSONArray.optNullableLong(index: Int): Long? {
    if (index >= length() || isNull(index)) return null
    return optLong(index)
}

private fun org.json.JSONArray.optNullableBoolean(index: Int): Boolean? {
    if (index >= length() || isNull(index)) return null
    return optBoolean(index)
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

private const val FEET_PER_METER = 3.28084
