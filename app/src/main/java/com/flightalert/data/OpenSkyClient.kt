package com.flightalert.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class OpenSkyClient(private val userAgent: String) {

    fun fetchTrack(icao24: String): List<TrackPoint> {
        val cleanIcao = icao24.trim().lowercase(Locale.US)
        if (cleanIcao.isEmpty()) return emptyList()

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
