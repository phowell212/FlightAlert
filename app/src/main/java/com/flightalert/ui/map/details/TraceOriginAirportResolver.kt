package com.flightalert.ui.map.details

import com.flightalert.data.AirportDetails
import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.MapProjection
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject

class TraceOriginAirportResolver(private val user_agent: String) {
    fun resolve_origin_airport(point: TrackPoint): AirportDetails? {
        ORIGIN_SEARCH_RADII_M.forEach { radius_m ->
            val match = aerodromes_near(point, radius_m).minByOrNull { it.distance_m }
            if (match != null) return match.airport
        }
        return null
    }

    private fun aerodromes_near(point: TrackPoint, radius_m: Double): List<TraceOriginAirportCandidate> {
        val query = """
            [out:json][timeout:8];
            (
              node(around:${radius_m.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
              way(around:${radius_m.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
              relation(around:${radius_m.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
            );
            out center tags 30;
        """.trimIndent()
        val api_url = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
        val elements = fetch_json_object(api_url)?.optJSONArray("elements") ?: return emptyList()
        val candidates = mutableListOf<TraceOriginAirportCandidate>()
        for (index in 0 until elements.length()) {
            val item = elements.optJSONObject(index) ?: continue
            val tags = item.optJSONObject("tags")
            val center = item.optJSONObject("center")
            val lat = if (item.has("lat")) item.optDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (item.has("lon")) item.optDouble("lon") else center?.optDouble("lon") ?: continue
            val distance_m = MapProjection.distance_meters(point.lat, point.lon, lat, lon)
            if (distance_m > radius_m) continue
            val airport = airport_from_tags(tags, lat, lon) ?: continue
            candidates += TraceOriginAirportCandidate(airport, distance_m)
        }
        return candidates
    }

    private fun airport_from_tags(tags: JSONObject?, lat: Double, lon: Double): AirportDetails? {
        val name = tags?.opt_clean_string("name") ?: tags?.opt_clean_string("official_name")
        val icao = tags?.opt_clean_string("icao")
        val iata = tags?.opt_clean_string("iata")
        val display_code = listOfNotNull(icao, iata, name)
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return AirportDetails(
            icao = display_code.uppercase(Locale.US),
            iata = iata,
            name = name,
            country_code = tags?.opt_clean_string("addr:country") ?: tags?.opt_clean_string("ISO3166-1"),
            region_name = tags?.opt_clean_string("addr:state") ?: tags?.opt_clean_string("is_in:state"),
            latitude = lat,
            longitude = lon
        )
    }

    private fun fetch_json_object(url: String): JSONObject? {
        val safe_url = https_url(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 9000
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun https_url(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }

    private data class TraceOriginAirportCandidate(
        val airport: AirportDetails,
        val distance_m: Double
    )

    private companion object {
        val ORIGIN_SEARCH_RADII_M = listOf(9000.0, 18000.0, 30000.0)
    }
}

private fun JSONObject.opt_clean_string(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}
