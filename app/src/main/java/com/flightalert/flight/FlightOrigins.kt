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

package com.flightalert.flight
import com.flightalert.FlightAlertAppSettings.AircraftFeedMode
import com.flightalert.aircraft.Aircraft
import com.flightalert.details.AircraftDetails
import com.flightalert.details.AirportDetails
import com.flightalert.details.OriginAerodrome
import com.flightalert.details.fetch_json_object
import com.flightalert.map.MapProjection
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executor

class AircraftOriginLookupController(
    private val military_origin_resolver: MilitaryOriginResolver,
    private val trace_origin_airport_resolver: TraceOriginAirportResolver,
    private val executor: Executor,
    private val post_to_main: (() -> Unit) -> Unit,
    private val request_redraw: () -> Unit,
    private val is_selected_key: (String) -> Boolean,
    private val selected_segments: () -> List<TraceSegment>?,
    private val aircraft_feed_mode: () -> AircraftFeedMode,
    private val aircraft_details: () -> AircraftDetails?,
    private val current_flight_route_details: (AircraftDetails, Aircraft) -> AircraftDetails?
) {
    private var military_origin_aircraft_id: String? = null
    private var military_origin_status = "Unavailable"
    private var military_origin_request_key: String? = null
    private var trace_origin_aircraft_id: String? = null
    private var trace_origin_airport: AirportDetails? = null
    private var trace_origin_request_key: String? = null
    private var trace_origin_loading = false

    fun reset_for_selection(aircraft: Aircraft) {
        military_origin_aircraft_id = aircraft.icao24
        military_origin_status = if (aircraft.is_military) "Waiting for flight path origin" else "Unavailable"
        military_origin_request_key = null
        trace_origin_aircraft_id = aircraft.icao24
        trace_origin_airport = null
        trace_origin_request_key = null
        trace_origin_loading = false
    }

    fun clear_trace_origin() {
        trace_origin_airport = null
        trace_origin_request_key = null
        trace_origin_loading = false
    }

    fun military_status_for(aircraft: Aircraft): String? {
        return military_origin_status.takeIf {
            military_origin_aircraft_id == aircraft.icao24 && it != "Unavailable"
        }
    }

    fun trace_origin_airport_for(aircraft: Aircraft): AirportDetails? {
        return trace_origin_airport?.takeIf { trace_origin_aircraft_id == aircraft.icao24 }
    }

    fun trace_origin_loading_for(aircraft: Aircraft): Boolean {
        return trace_origin_loading && trace_origin_aircraft_id == aircraft.icao24
    }

    // Military origin claims only start from the selected aircraft's real trace, never from type or registration guesses.
    fun request_military_origin_if_needed(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (!aircraft.is_military || !is_selected_key(key)) return
        val first_point = first_selected_trace_point() ?: return
        val request_key = trace_request_key(key, first_point)
        if (military_origin_request_key == request_key) return

        military_origin_request_key = request_key
        military_origin_aircraft_id = aircraft.icao24
        military_origin_status = "Checking track origin"
        executor.execute {
            val status = military_origin_resolver.resolve_origin(first_point)
            post_to_main {
                if (is_selected_key(key) && military_origin_request_key == request_key) {
                    military_origin_status = status
                    request_redraw()
                }
            }
        }
    }

    // Hybrid mode can enrich route origin from the selected trace only when no provider route origin exists.
    fun request_trace_origin_airport_if_needed(aircraft: Aircraft) {
        val key = aircraft.icao24.lowercase(Locale.US)
        if (aircraft_feed_mode() != AircraftFeedMode.HYBRID) return
        if (!is_selected_key(key)) return
        if (trace_origin_airport != null && trace_origin_aircraft_id == aircraft.icao24) return
        if (trace_origin_aircraft_id == aircraft.icao24 && trace_origin_loading) return
        if (should_skip_airport_origin_fallback(aircraft)) return
        val route_with_supplied_origin = aircraft_details()
            ?.takeIf { CurrentRouteValidator.has_route_metadata(it) }
            ?.let { details -> current_flight_route_details(details, aircraft) }
        if (route_with_supplied_origin?.origin_airport != null) return
        val first_point = first_selected_trace_point() ?: return
        val request_key = trace_request_key(key, first_point)
        if (trace_origin_request_key == request_key) return

        trace_origin_aircraft_id = aircraft.icao24
        trace_origin_request_key = request_key
        trace_origin_loading = true
        executor.execute {
            val airport = trace_origin_airport_resolver.resolve_origin_airport(first_point)
            post_to_main {
                if (is_selected_key(key) && trace_origin_request_key == request_key) {
                    trace_origin_airport = airport
                    trace_origin_loading = false
                    request_redraw()
                }
            }
        }
    }

    private fun first_selected_trace_point(): TrackPoint? {
        return selected_segments()
            ?.flatMap { it.points }
            ?.minByOrNull { it.epoch_sec }
    }

    private fun should_skip_airport_origin_fallback(aircraft: Aircraft): Boolean {
        val type = aircraft.type_code?.trim()?.uppercase(Locale.US).orEmpty()
        if (type.startsWith("H") || type.startsWith("R") || type.startsWith("UAV") || type.startsWith("DRON")) return true
        return aircraft.category == 8 || aircraft.category == 14
    }

    private fun trace_request_key(key: String, first_point: TrackPoint): String {
        return "${key}:${first_point.epoch_sec}:${"%.4f".format(Locale.US, first_point.lat)}:${"%.4f".format(Locale.US, first_point.lon)}"
    }
}



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

    private fun fetch_json_object(url: String): JSONObject? =
        fetch_json_object(url, user_agent)


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
        val name = tags?.trace_origin_json_clean_string("name") ?: tags?.trace_origin_json_clean_string("official_name")
        val icao = tags?.trace_origin_json_clean_string("icao")
        val iata = tags?.trace_origin_json_clean_string("iata")
        val display_code = listOfNotNull(icao, iata, name)
            .firstOrNull { it.isNotBlank() }
            ?: return null
        return AirportDetails(
            icao = display_code.uppercase(Locale.US),
            iata = iata,
            name = name,
            country_code = tags?.trace_origin_json_clean_string("addr:country") ?: tags?.trace_origin_json_clean_string("ISO3166-1"),
            region_name = tags?.trace_origin_json_clean_string("addr:state") ?: tags?.trace_origin_json_clean_string("is_in:state"),
            latitude = lat,
            longitude = lon
        )
    }

    private fun fetch_json_object(url: String): JSONObject? =
        fetch_json_object(url, user_agent)


    private data class TraceOriginAirportCandidate(
        val airport: AirportDetails,
        val distance_m: Double
    )

    private companion object {
        val ORIGIN_SEARCH_RADII_M = listOf(9000.0, 18000.0, 30000.0)
    }
}


internal fun JSONObject.trace_origin_json_clean_string(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}
