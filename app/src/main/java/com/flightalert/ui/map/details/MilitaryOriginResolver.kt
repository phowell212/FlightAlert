package com.flightalert.ui.map.details

import com.flightalert.data.TrackPoint
import com.flightalert.ui.map.MapProjection
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import org.json.JSONObject

class MilitaryOriginResolver(private val user_agent: String) {
    fun resolve_origin(point: TrackPoint): String {
        val query = """
            [out:json][timeout:8];
            (
              node(around:${ORIGIN_AERODROME_RADIUS_M.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
              way(around:${ORIGIN_AERODROME_RADIUS_M.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
              relation(around:${ORIGIN_AERODROME_RADIUS_M.toInt()},${point.lat},${point.lon})["aeroway"="aerodrome"];
            );
            out center tags 20;
        """.trimIndent()
        val api_url = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
        val elements = fetch_json_object(api_url)?.optJSONArray("elements") ?: return "Origin lookup unavailable"
        val candidates = mutableListOf<OriginAerodrome>()
        for (index in 0 until elements.length()) {
            val item = elements.optJSONObject(index) ?: continue
            val center = item.optJSONObject("center")
            val lat = if (item.has("lat")) item.optDouble("lat") else center?.optDouble("lat") ?: continue
            val lon = if (item.has("lon")) item.optDouble("lon") else center?.optDouble("lon") ?: continue
            val distance_m = MapProjection.distance_meters(point.lat, point.lon, lat, lon)
            if (distance_m > ORIGIN_AERODROME_RADIUS_M) continue
            val tags = item.optJSONObject("tags")
            val name = tags?.optString("name")?.trim()?.ifEmpty { null }
            val icao = tags?.optString("icao")?.trim()?.ifEmpty { null }
            candidates += OriginAerodrome(
                name = name,
                icao = icao,
                distance_m = distance_m,
                military = is_military_aerodrome(tags, name, icao)
            )
        }

        val nearest = candidates.minByOrNull { it.distance_m } ?: return "Track origin not matched to an aerodrome"
        val label = nearest.label()
        return if (nearest.military) {
            "Military base: $label"
        } else {
            "Track origin: $label (civilian/other)"
        }
    }

    private fun is_military_aerodrome(tags: JSONObject?, name: String?, icao: String?): Boolean {
        if (is_military_airport_name(name, icao)) return true
        if (tags == null) return false
        val combined = listOf(
            tags.optString("military"),
            tags.optString("aerodrome"),
            tags.optString("aerodrome:type"),
            tags.optString("operator"),
            tags.optString("operator:type"),
            tags.optString("owner")
        ).joinToString(" ").uppercase(Locale.US)
        return MILITARY_AERODROME_KEYWORDS.any { combined.contains(it) }
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

    companion object {
        fun is_military_airport_name(name: String?, icao: String?): Boolean {
            val combined = listOfNotNull(name, icao).joinToString(" ").uppercase(Locale.US)
            if (combined.isBlank()) return false
            return MILITARY_AERODROME_KEYWORDS.any { combined.contains(it) }
        }

        private const val ORIGIN_AERODROME_RADIUS_M = 9000.0

        private val MILITARY_AERODROME_KEYWORDS = listOf(
            "AIR FORCE",
            "AFB",
            "NAVAL AIR",
            "NAVAL STATION",
            "NAS ",
            "JOINT BASE",
            "ARMY AIRFIELD",
            " AAF",
            "MARINE CORPS",
            "MCAS",
            "AIR NATIONAL GUARD",
            "COAST GUARD",
            "CGAS",
            "MILITARY",
            "DEFENCE",
            "DEFENSE"
        )
    }
}
