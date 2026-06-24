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

package com.flightalert.details
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.traffic.AirplanesLiveHttp
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max

internal fun https_url(value: String): URL? {
    return try {
        URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
    } catch (_: Exception) {
        null
    }
}

// Tile loading previously used runCatching, which catches Throwable rather than only Exception.

internal fun throwable_safe_https_url(value: String): URL? {
    return runCatching {
        URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
    }.getOrNull()
}


internal fun fetch_json_object(url: String, user_agent: String): JSONObject? {
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


internal fun String.clean_seed_value(): String? {
    val cleaned = trim().trim('-').trim()
    return cleaned.takeIf {
        it.isNotBlank() &&
            !it.equals("null", ignoreCase = true) &&
            !it.equals("n/a", ignoreCase = true) &&
            !it.equals("unavailable", ignoreCase = true)
    }
}


internal fun normalized_photo_registration(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.replace("PHOTOS", "")
        ?.replace(Regex("[^A-Z0-9-]"), "")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() && it != "NA" }
}


internal fun JSONArray.json_number_or_null(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}


internal fun JSONObject.json_number_or_null(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}


internal fun JSONArray.json_int_or_null(index: Int): Int? {
    if (index >= length() || isNull(index)) return null
    return optInt(index)
}


internal fun max_epoch(first: Double?, second: Double?): Double? {
    return when {
        first == null -> second
        second == null -> first
        else -> max(first, second)
    }
}


internal fun plain_text_from_html(html: String): String {
    return html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&#39;", "'")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
}


data class FaaRegistryRecord(
    val registration: String,
    val manufacturer: String?,
    val model: String?,
    val manufactured_year: String?,
    val registered_owner: String?,
    val source_name: String
)


data class AircraftDetails(
    val icao24: String,
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val type_code: String?,
    val owner: String?,
    val manufactured_year: String?,
    val registry_source: String?,
    val operator_code: String?,
    val route: String?,
    val route_updated_epoch_sec: Long?,
    val route_source: String?,
    val origin_airport: AirportDetails?,
    val destination_airport: AirportDetails?,
    val telemetry: AircraftTelemetry? = null
)


object AircraftRouteSource {
    const val ADSBDB_CALLSIGN = "ADSBdb callsign"
    const val ADSBIM_ROUTESET = "adsb.im routeset"
    const val HEXDB_CALLSIGN = "HexDB callsign"
}


data class AirportDetails(
    val icao: String,
    val iata: String?,
    val name: String?,
    val country_code: String?,
    val region_name: String?,
    val latitude: Double?,
    val longitude: Double?
)



class AircraftDetailsClient(private val user_agent: String) {
    private val details_cache = linkedMapOf<String, CachedDetails>()
    private val static_aircraft_db_cache = linkedMapOf<String, JSONObject?>()

    fun fetch_details(
        hex: String,
        callsign: String,
        registration_hint: String? = null,
        metadata_seed: AircraftMetadataSeed? = null,
        telemetry_seed: AircraftTelemetry? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): AircraftDetails {
        val normalized_hex = hex.trim().trimStart('~').lowercase(Locale.US)
        val clean_callsign = callsign.trim().replace(" ", "")
        val cache_key = details_cache_key(normalized_hex, clean_callsign, metadata_seed)
        cached_details(cache_key)?.let { cached ->
            return cached.copy(telemetry = cached.telemetry?.with_fallback(telemetry_seed) ?: telemetry_seed)
        }
        val seed_details = details_from_seed(normalized_hex, registration_hint, metadata_seed, telemetry_seed)
        val airplanes_live = fetch_airplanes_live_metadata(normalized_hex)
        val static_airplanes_live = if (airplanes_live?.has_aircraft_identity == true) {
            null
        } else {
            fetch_airplanes_live_static_metadata(normalized_hex)
        }
        val feed_registration = details_normalized_registration(registration_hint)
        val seed_registration = details_normalized_registration(seed_details?.registration)
        val decoded_us_registration = details_decode_us_n_number(normalized_hex)
        val airplanes_registration = details_normalized_registration(airplanes_live?.registration ?: static_airplanes_live?.registration)
        val adsb_aircraft = fetch_adsb_db_aircraft(normalized_hex, airplanes_registration ?: feed_registration ?: seed_registration ?: decoded_us_registration)
        val aircraft = fetch_json("https://hexdb.io/api/v1/aircraft/$normalized_hex")
        val registration = details_first_present(
            airplanes_registration,
            adsb_aircraft?.registration,
            aircraft?.details_json_string_or_null("Registration")?.let(::details_normalized_registration),
            feed_registration,
            seed_registration,
            decoded_us_registration
        )
        val faa = registration?.takeIf { it.startsWith("N", ignoreCase = true) }?.let { fetch_faa_registry(it) }
        val adsb_route = if (clean_callsign.isNotEmpty() && clean_callsign.lowercase(Locale.US) != normalized_hex) {
            fetch_adsb_db_route(clean_callsign)
        } else {
            null
        }
        val route = if (clean_callsign.isNotEmpty() && clean_callsign.lowercase(Locale.US) != normalized_hex) {
            fetch_json("https://hexdb.io/api/v1/route/icao/$clean_callsign")
        } else {
            null
        }
        val route_codes = route?.details_json_string_or_null("route")?.split("-")?.takeIf { it.size >= 2 }
        val adsb_im_route = if (
            adsb_route == null &&
            clean_callsign.isNotEmpty() &&
            clean_callsign.lowercase(Locale.US) != normalized_hex
        ) {
            fetch_adsb_im_route(clean_callsign, latitude, longitude)
        } else {
            null
        }
        val origin = adsb_route?.origin ?: adsb_im_route?.origin ?: route_codes?.firstOrNull()?.let { fetch_airport(it) }
        val destination = adsb_route?.destination ?: adsb_im_route?.destination ?: route_codes?.lastOrNull()?.let { fetch_airport(it) }
        val route_source = when {
            adsb_route != null -> AircraftRouteSource.ADSBDB_CALLSIGN
            adsb_im_route != null -> AircraftRouteSource.ADSBIM_ROUTESET
            route_codes != null -> AircraftRouteSource.HEXDB_CALLSIGN
            else -> null
        }
        val feed_type = airplanes_live?.description ?: static_airplanes_live?.description
        val api_manufacturer = faa?.manufacturer ?: adsb_aircraft?.manufacturer ?: aircraft?.details_json_string_or_null("Manufacturer")
        val api_type = faa?.model ?: adsb_aircraft?.type ?: aircraft?.details_json_string_or_null("Type")
        val api_type_code = airplanes_live?.type_code
            ?: static_airplanes_live?.type_code
            ?: adsb_aircraft?.icao_type
            ?: aircraft?.details_json_string_or_null("ICAOTypeCode")
        val seed_type = seed_details?.type
        val internet = if (feed_type == null && api_type == null && seed_type == null) {
            fetch_internet_aircraft_metadata(api_manufacturer ?: seed_details?.manufacturer, seed_type, api_type_code ?: seed_details?.type_code)
        } else {
            null
        }
        val manufacturer = api_manufacturer ?: seed_details?.manufacturer ?: internet?.manufacturer
        val type = feed_type ?: api_type ?: seed_type ?: internet?.model
        val owner = faa?.registered_owner
            ?: adsb_aircraft?.registered_owner
            ?: aircraft?.details_json_string_or_null("RegisteredOwners")
            ?: airplanes_live?.owner_operator
            ?: seed_details?.owner

        val metadata_sources = mutableListOf<String>()
        if (seed_details?.registry_source != null) metadata_sources += seed_details.registry_source
        if (airplanes_live?.has_metadata == true) metadata_sources += airplanes_live.source_name
        if (static_airplanes_live?.has_metadata == true) metadata_sources += static_airplanes_live.source_name
        if (faa != null) metadata_sources += faa.source_name
        if (adsb_aircraft != null) metadata_sources += "ADSBdb"
        if (aircraft != null) metadata_sources += "HexDB"
        if (internet?.model != null && internet.model == type) metadata_sources += internet.source_name
        if (metadata_sources.isEmpty() && registration != null && registration == feed_registration) metadata_sources += "Aircraft feed"
        if (metadata_sources.isEmpty() && registration != null && registration == decoded_us_registration) metadata_sources += "Mode S N-number decode"

        return AircraftDetails(
            icao24 = normalized_hex,
            registration = faa?.registration ?: registration,
            manufacturer = manufacturer,
            type = type,
            type_code = api_type_code ?: seed_details?.type_code,
            owner = owner,
            manufactured_year = faa?.manufactured_year ?: airplanes_live?.year ?: seed_details?.manufactured_year,
            registry_source = metadata_sources.distinct().joinToString(" + ").ifEmpty { null },
            operator_code = adsb_aircraft?.operator_code
                ?: aircraft?.details_json_string_or_null("OperatorFlagCode")
                ?: airplanes_live?.operator_code
                ?: static_airplanes_live?.operator_code
                ?: seed_details?.operator_code,
            route = adsb_route?.route ?: adsb_im_route?.route ?: route?.details_json_string_or_null("route") ?: seed_details?.route,
            route_updated_epoch_sec = route?.details_json_long_or_null("updatetime") ?: seed_details?.route_updated_epoch_sec,
            route_source = route_source ?: seed_details?.route_source,
            origin_airport = origin ?: seed_details?.origin_airport,
            destination_airport = destination ?: seed_details?.destination_airport,
            telemetry = telemetry_seed
        ).also { cache_details(cache_key, it) }
    }

    private fun details_from_seed(
        normalized_hex: String,
        registration_hint: String?,
        seed: AircraftMetadataSeed?,
        telemetry_seed: AircraftTelemetry?
    ): AircraftDetails? {
        if ((seed == null || !seed.has_details) && telemetry_seed?.has_values != true) return null
        val registration = details_first_present(
            details_normalized_registration(seed?.registration),
            details_normalized_registration(registration_hint),
            details_decode_us_n_number(normalized_hex)
        )
        val manufacturer = seed?.manufacturer?.clean_seed_value()
        val type = seed?.type?.clean_seed_value()
        val type_code = seed?.type_code?.clean_seed_value()
        val owner = seed?.owner?.clean_seed_value()
        val year = seed?.manufactured_year?.clean_seed_value()?.take(4)
        val operator_code = seed?.operator_code?.clean_seed_value()
        if (listOf(registration, manufacturer, type, type_code, owner, year, operator_code).all { it.isNullOrBlank() } && telemetry_seed?.has_values != true) {
            return null
        }
        return AircraftDetails(
            icao24 = normalized_hex,
            registration = registration,
            manufacturer = manufacturer,
            type = type,
            type_code = type_code,
            owner = owner,
            manufactured_year = year,
            registry_source = seed?.source_name,
            operator_code = operator_code,
            route = null,
            route_updated_epoch_sec = null,
            route_source = null,
            origin_airport = null,
            destination_airport = null,
            telemetry = telemetry_seed
        )
    }

    private fun details_cache_key(normalized_hex: String, clean_callsign: String, seed: AircraftMetadataSeed?): String {
        val source = seed?.source_name?.takeIf { seed.has_details } ?: "network"
        return "$normalized_hex|${clean_callsign.uppercase(Locale.US)}|$source"
    }

    private fun cached_details(key: String): AircraftDetails? {
        val now = System.currentTimeMillis()
        return synchronized(details_cache) {
            val cached = details_cache[key] ?: return@synchronized null
            if (now - cached.stored_at_ms > DETAILS_CACHE_MAX_AGE_MS) {
                details_cache.remove(key)
                null
            } else {
                cached.details
            }
        }
    }

    private fun cache_details(key: String, details: AircraftDetails) {
        synchronized(details_cache) {
            details_cache[key] = CachedDetails(details, System.currentTimeMillis())
            while (details_cache.size > DETAILS_CACHE_MAX_ENTRIES) {
                val first_key = details_cache.keys.firstOrNull() ?: break
                details_cache.remove(first_key)
            }
        }
    }

    private fun fetch_internet_aircraft_metadata(
        manufacturer: String?,
        model: String?,
        type_code: String?
    ): InternetAircraftMetadata? {
        val queries = listOfNotNull(
            listOfNotNull(manufacturer, model).joinToString(" ").takeIf { it.isNotBlank() }?.let { "$it aircraft" },
            type_code?.takeIf { it.length >= 3 }?.let { "$it aircraft" },
            model?.takeIf { it.length >= 3 }?.let { "$it aircraft" }
        ).distinctBy { it.uppercase(Locale.US) }
        for (query in queries) {
            fetch_wikipedia_aircraft_metadata(query)?.let { return it }
        }
        return null
    }

    private fun fetch_wikipedia_aircraft_metadata(query: String): InternetAircraftMetadata? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val search = fetch_json("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=3&srsearch=$encoded")
            ?.optJSONObject("query")
            ?.optJSONArray("search")
            ?: return null
        for (index in 0 until search.length()) {
            val result = search.optJSONObject(index) ?: continue
            val title = result.details_json_string_or_null("title") ?: continue
            val snippet = plain_text_from_html(result.details_json_string_or_null("snippet").orEmpty())
            if (!details_looks_like_aircraft_page(title, snippet)) continue
            val summary = fetch_wikipedia_summary(title) ?: continue
            if (!details_looks_like_aircraft_page(summary.title, summary.extract)) continue
            return InternetAircraftMetadata(
                manufacturer = null,
                model = summary.title,
                source_name = "Wikipedia summary"
            )
        }
        return null
    }

    private fun fetch_wikipedia_summary(title: String): WikipediaSummary? {
        val encoded_title = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val json = fetch_json("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded_title") ?: return null
        val extract = json.details_json_string_or_null("extract") ?: return null
        return WikipediaSummary(
            title = json.details_json_string_or_null("title") ?: title,
            extract = extract
        )
    }

    private fun fetch_airplanes_live_metadata(hex: String): AirplanesLiveMetadata? {
        if (hex.isBlank()) return null
        var best = fetch_airplanes_live_rest_metadata(hex)
        if (best?.has_core_metadata == true) return best
        best = best.merge_airplanes_live(fetch_airplanes_live_trace_metadata(hex, recent = true))
        if (best?.has_aircraft_identity == true) return best
        return best.merge_airplanes_live(fetch_airplanes_live_trace_metadata(hex, recent = false))
    }

    private fun fetch_airplanes_live_trace_metadata(hex: String, recent: Boolean): AirplanesLiveMetadata? {
        val folder = hex.takeLast(2)
        val trace_name = if (recent) "trace_recent_$hex.json" else "trace_full_$hex.json"
        val json = fetch_json("${AirplanesLiveHttp.GLOBE_BASE_URL}/data/traces/$folder/$trace_name", browser_headers = true)
            ?: return null
        return json.to_airplanes_live_metadata(if (recent) "Airplanes.Live trace_recent" else "Airplanes.Live trace_full")
    }

    private fun fetch_airplanes_live_rest_metadata(hex: String): AirplanesLiveMetadata? {
        AirplanesLiveHttp.wait_for_rest_api_slot()
        val json = fetch_json("${AirplanesLiveHttp.API_BASE_URL}/hex/$hex", browser_headers = true) ?: return null
        val aircraft = json.optJSONArray("aircraft") ?: json.optJSONArray("ac") ?: return null
        for (index in 0 until aircraft.length()) {
            val item = aircraft.optJSONObject(index) ?: continue
            val item_hex = item.details_json_string_or_null("hex")?.trim()?.trimStart('~')?.lowercase(Locale.US)
            if (item_hex == hex) return item.to_airplanes_live_metadata("Airplanes.Live")
        }
        return null
    }

    private fun fetch_airplanes_live_static_metadata(hex: String): AirplanesLiveMetadata? {
        val clean_hex = hex.trim().trimStart('~').uppercase(Locale.US)
        if (!clean_hex.matches(Regex("[0-9A-F]{6}"))) return null
        val row = fetch_airplanes_live_static_row(clean_hex) ?: return null
        return AirplanesLiveMetadata(
            source_name = "Airplanes.Live static aircraft DB",
            registration = row.details_json_string_or_null(0),
            type_code = row.details_json_string_or_null(1),
            description = row.details_json_string_or_null(3),
            owner_operator = null,
            operator_code = null,
            year = null
        ).takeIf { it.has_metadata }
    }

    private fun fetch_airplanes_live_static_row(clean_hex: String): JSONArray? {
        var level = 1
        while (level <= clean_hex.length) {
            val bkey = clean_hex.substring(0, level)
            val dkey = clean_hex.substring(level)
            val chunk = fetch_airplanes_live_static_chunk(bkey) ?: return null
            chunk.optJSONArray(dkey)?.let { return it }
            val next_child = clean_hex.substring(0, (level + 1).coerceAtMost(clean_hex.length))
            if (!static_chunk_has_child(chunk, next_child)) return null
            level++
        }
        return null
    }

    private fun fetch_airplanes_live_static_chunk(bkey: String): JSONObject? {
        val key = bkey.uppercase(Locale.US)
        synchronized(static_aircraft_db_cache) {
            if (static_aircraft_db_cache.containsKey(key)) return static_aircraft_db_cache[key]
        }
        val chunk = fetch_json("${AirplanesLiveHttp.STATIC_DB_BASE_URL}/$key.js", browser_headers = true)
        synchronized(static_aircraft_db_cache) {
            static_aircraft_db_cache[key] = chunk
            while (static_aircraft_db_cache.size > DETAILS_STATIC_DB_CACHE_MAX_ENTRIES) {
                val first_key = static_aircraft_db_cache.keys.firstOrNull() ?: break
                static_aircraft_db_cache.remove(first_key)
            }
        }
        return chunk
    }

    private fun fetch_faa_registry(registration: String): FaaRegistryRecord? {
        val n_number = registration.trim().removePrefix("N").removePrefix("n")
        if (n_number.isEmpty()) return null
        val encoded = URLEncoder.encode(n_number, "UTF-8")
        val html = fetch_text("https://registry.faa.gov/AircraftInquiry/Search/NNumberResult?nNumberTxt=$encoded") ?: return null
        val result_html = html.substringAfter("<div id=\"mainDiv\"", html)
        val result_text = plain_text_from_html(result_html)
        val details_normalized_registration = "N${n_number.uppercase(Locale.US)}"
        if (!result_text.contains("$details_normalized_registration is Assigned", ignoreCase = true)) return null

        val owner_section = details_registered_owner_table_section(result_html)
        val private_owner = owner_section
            ?.let(::plain_text_from_html)
            ?.contains("requested to keep this data private", ignoreCase = true) == true
        val owner_name = if (private_owner) {
            "Private under 49 USC 44114"
        } else {
            owner_section?.let { details_value_from_data_label(it, "Name") }
        }

        return FaaRegistryRecord(
            registration = details_normalized_registration,
            manufacturer = details_value_from_data_label(result_html, "Manufacturer Name"),
            model = details_value_from_data_label(result_html, "Model"),
            manufactured_year = details_value_from_data_label(result_html, "Mfr Year")?.take(4),
            registered_owner = owner_name,
            source_name = "FAA Registry"
        )
    }

    private fun fetch_adsb_db_aircraft(hex: String, registration: String?): AdsbDbAircraftRecord? {
        val keys = listOfNotNull(hex.takeIf { it.isNotBlank() }, registration)
            .distinctBy { it.uppercase(Locale.US) }
        for (key in keys) {
            val encoded = URLEncoder.encode(key, "UTF-8")
            val aircraft = fetch_json("https://api.adsbdb.com/v0/aircraft/$encoded")
                ?.optJSONObject("response")
                ?.optJSONObject("aircraft")
                ?: continue
            val mode_s = aircraft.details_json_string_or_null("mode_s")?.trim()?.trimStart('~')?.lowercase(Locale.US)
            if (key.equals(hex, ignoreCase = true) && mode_s != null && mode_s != hex) continue
            val found_registration = details_normalized_registration(aircraft.details_json_string_or_null("registration"))
            if (
                registration != null &&
                key.equals(registration, ignoreCase = true) &&
                found_registration != null &&
                found_registration != registration
            ) {
                continue
            }
            return AdsbDbAircraftRecord(
                registration = found_registration,
                manufacturer = aircraft.details_json_string_or_null("manufacturer"),
                type = aircraft.details_json_string_or_null("type"),
                icao_type = aircraft.details_json_string_or_null("icao_type"),
                registered_owner = aircraft.details_json_string_or_null("registered_owner"),
                operator_code = aircraft.details_json_string_or_null("registered_owner_operator_flag_code")
            )
        }
        return null
    }

    private fun fetch_airport(icao: String): AirportDetails? {
        val json = fetch_json("https://hexdb.io/api/v1/airport/icao/${icao.trim()}") ?: return null
        return AirportDetails(
            icao = json.details_json_string_or_null("icao") ?: icao,
            iata = json.details_json_string_or_null("iata_code") ?: json.details_json_string_or_null("iata"),
            name = json.details_json_string_or_null("airport"),
            country_code = json.details_json_string_or_null("country_code"),
            region_name = json.details_json_string_or_null("region_name"),
            latitude = json.details_json_double_or_null("latitude"),
            longitude = json.details_json_double_or_null("longitude")
        )
    }

    private fun fetch_adsb_db_route(callsign: String): RouteLookup? {
        val encoded = URLEncoder.encode(callsign.trim(), "UTF-8")
        val route = fetch_json("https://api.adsbdb.com/v0/callsign/$encoded")
            ?.optJSONObject("response")
            ?.optJSONObject("flightroute")
            ?: return null
        val origin = route.optJSONObject("origin")?.details_to_adsb_db_airport()
        val destination = route.optJSONObject("destination")?.details_to_adsb_db_airport()
        if (origin == null && destination == null) return null
        val route_label = listOfNotNull(
            origin?.icao ?: origin?.iata,
            destination?.icao ?: destination?.iata
        ).joinToString("-").ifEmpty { null }
        return RouteLookup(route_label, origin, destination)
    }

    private fun fetch_adsb_im_route(callsign: String, latitude: Double?, longitude: Double?): RouteLookup? {
        val lat = latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val lon = longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val clean_callsign = callsign.trim().replace(" ", "").uppercase(Locale.US)
        val request_body = JSONObject()
            .put(
                "planes",
                JSONArray().put(
                    JSONObject()
                        .put("callsign", clean_callsign)
                        .put("lat", lat)
                        .put("lng", lon)
                )
            )
            .toString()
        val route = post_adsb_im_routeset(request_body)
            ?.optJSONObject(0)
            ?: return null
        val returned_callsign = route.details_json_string_or_null("callsign")?.replace(" ", "")?.uppercase(Locale.US)
        if (returned_callsign != null && returned_callsign != clean_callsign) return null
        val airports = route.optJSONArray("_airports")?.details_to_route_airports().orEmpty()
        if (airports.isEmpty()) return null
        val route_label = route.details_json_string_or_null("airport_codes")
            ?: airports.mapNotNull { airport -> airport.icao.takeIf { it.isNotBlank() } }.takeIf { it.size >= 2 }?.joinToString("-")
            ?: return null
        val display_route = if (route.has("plausible") && !route.optBoolean("plausible", true)) {
            "?? $route_label"
        } else {
            route_label
        }
        return RouteLookup(display_route, airports.firstOrNull(), airports.lastOrNull()?.takeIf { airports.size >= 2 })
    }

    private fun post_adsb_im_routeset(body: String): JSONArray? {
        val safe_url = https_url("https://adsb.im/api/0/routeset") ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("User-Agent", AirplanesLiveHttp.browser_user_agent(user_agent))
                setRequestProperty("Accept", "application/json,text/plain,*/*")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { output -> output.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return null
            }
            JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetch_json(url: String, browser_headers: Boolean = false): JSONObject? {
        val body = fetch_text(url, browser_headers) ?: return null
        return try {
            JSONObject(body).takeUnless { it.details_json_string_or_null("status") == "404" }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetch_text(url: String, browser_headers: Boolean = false): String? {
        val safe_url = https_url(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safe_url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "GET"
                if (browser_headers) {
                    AirplanesLiveHttp.apply_browser_headers(this, user_agent)
                } else {
                    setRequestProperty("User-Agent", user_agent)
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                if (connection.responseCode == DETAILS_HTTP_TOO_MANY_REQUESTS && safe_url.host.equals("api.airplanes.live", ignoreCase = true)) {
                    AirplanesLiveHttp.back_off_rest_api(connection.getHeaderField("Retry-After")?.toLongOrNull())
                }
                connection.errorStream?.close()
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    }


internal fun details_registered_owner_table_section(html: String): String? {
    val caption_regex = Regex("<caption[^>]*>\\s*Registered Owner\\s*</caption>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val start = caption_regex.find(html)?.range?.first ?: return null
    val end = html.indexOf("</table>", start, ignoreCase = true).takeIf { it >= 0 } ?: return null
    return html.substring(start, end)
}


internal fun details_looks_like_aircraft_page(title: String, text: String): Boolean {
    val combined = "$title $text".uppercase(Locale.US)
    val aircraft_terms = listOf(
        "AIRCRAFT",
        "AIRPLANE",
        "AEROPLANE",
        "HELICOPTER",
        "ROTORCRAFT",
        "JET",
        "TURBOPROP",
        "PISTON",
        "GLIDER",
        "UAV",
        "DRONE"
    )
    val rejected_topics = listOf(
        "AIRPORT",
        "AIRLINE",
        "AIRWAYS",
        "ACCIDENT",
        "INCIDENT",
        "CRASH",
        "HIJACKING"
    )
    return aircraft_terms.any { combined.contains(it) } &&
        rejected_topics.none { combined.contains(it) }
}

internal fun JSONObject.to_airplanes_live_metadata(source_name: String): AirplanesLiveMetadata {
    return AirplanesLiveMetadata(
        source_name = source_name,
        registration = details_json_first_string_or_null("r", "registration", "reg"),
        type_code = details_json_first_string_or_null("t", "icaoType", "icao_type", "type_code"),
        description = details_json_first_string_or_null("desc", "typeLong", "description", "typeDescription", "type_description"),
        owner_operator = details_json_first_string_or_null("ownOp", "owner_operator", "operator", "owner"),
        operator_code = details_json_first_string_or_null("ownOpCode", "operator_code"),
        year = details_json_first_string_or_null("year", "mfrYear", "manufacturedYear", "manufactureYear", "mfr_year", "manufacture_year")
            ?.let(::normalized_year)
    )
}

internal fun AirplanesLiveMetadata?.merge_airplanes_live(newer: AirplanesLiveMetadata?): AirplanesLiveMetadata? {
    if (this == null) return newer
    newer ?: return this
    return AirplanesLiveMetadata(
        source_name = listOf(this.source_name, newer.source_name).distinct().joinToString(" + "),
        registration = newer.registration ?: registration,
        type_code = newer.type_code ?: type_code,
        description = newer.description ?: description,
        owner_operator = newer.owner_operator ?: owner_operator,
        operator_code = newer.operator_code ?: operator_code,
        year = newer.year ?: year
    )
}


internal fun details_value_from_data_label(html: String, label: String): String? {
    val regex = Regex("<td[^>]*data-label=[\"']${Regex.escape(label)}[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
    return regex.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::plain_text_from_html)
        ?.let(::details_clean_registry_value)
}



internal fun details_clean_registry_value(value: String): String? {
    val cleaned = value.trim().trim('-').trim()
    if (cleaned.isEmpty() || cleaned.equals("none", ignoreCase = true)) return null
    val page_chrome_terms = listOf("Lookup Aircraft By", "N-Number Availability", "Aircraft Inquiry Search")
    if (page_chrome_terms.any { cleaned.contains(it, ignoreCase = true) }) return null
    return cleaned
}

internal fun normalized_year(value: String): String? {
    return Regex("\\b(19|20)\\d{2}\\b")
        .find(value)
        ?.value
}



internal fun details_normalized_registration(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.replace(Regex("[^A-Z0-9-]"), "")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() && it != "NA" }
}


internal fun details_first_present(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}


internal fun details_decode_us_n_number(hex: String): String? {
    val value = hex.toLongOrNull(16) ?: return null
    if (value !in DETAILS_US_N_NUMBER_ICAO_START..DETAILS_US_N_NUMBER_ICAO_END) return null

    var offset = (value - DETAILS_US_N_NUMBER_ICAO_START).toInt()
    val registration = StringBuilder("N")
    registration.append(offset / DETAILS_FIRST_N_NUMBER_STRIDE + 1)
    offset %= DETAILS_FIRST_N_NUMBER_STRIDE
    details_append_n_number_suffix(offset, registration)?.let { return it }

    offset -= DETAILS_N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / DETAILS_SECOND_N_NUMBER_STRIDE)
    offset %= DETAILS_SECOND_N_NUMBER_STRIDE
    details_append_n_number_suffix(offset, registration)?.let { return it }

    offset -= DETAILS_N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / DETAILS_THIRD_N_NUMBER_STRIDE)
    offset %= DETAILS_THIRD_N_NUMBER_STRIDE
    details_append_n_number_suffix(offset, registration)?.let { return it }

    offset -= DETAILS_N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / DETAILS_FOURTH_N_NUMBER_STRIDE)
    offset %= DETAILS_FOURTH_N_NUMBER_STRIDE
    if (offset <= DETAILS_N_NUMBER_ALPHABET.length) {
        return registration.append(details_n_number_single_suffix(offset)).toString()
    }

    val last_digit = offset - DETAILS_N_NUMBER_ALPHABET.length - 1
    return registration.append(last_digit).toString()
}


internal fun details_append_n_number_suffix(offset: Int, registration: StringBuilder): String? {
    if (offset > DETAILS_MAX_TWO_LETTER_SUFFIX_OFFSET) return null
    return registration.append(details_n_number_suffix(offset)).toString()
}


internal fun details_n_number_suffix(offset: Int): String {
    if (offset <= 0) return ""
    val index = offset - 1
    if (index < DETAILS_N_NUMBER_ALPHABET.length) return DETAILS_N_NUMBER_ALPHABET[index].toString()
    val double_index = index - DETAILS_N_NUMBER_ALPHABET.length
    val first = double_index / DETAILS_N_NUMBER_ALPHABET.length
    val second = double_index % DETAILS_N_NUMBER_ALPHABET.length
    return "${DETAILS_N_NUMBER_ALPHABET[first]}${DETAILS_N_NUMBER_ALPHABET[second]}"
}


internal fun details_n_number_single_suffix(offset: Int): String {
    return if (offset <= 0) "" else DETAILS_N_NUMBER_ALPHABET[offset - 1].toString()
}

internal data class AdsbDbAircraftRecord(
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val icao_type: String?,
    val registered_owner: String?,
    val operator_code: String?
)


internal data class AirplanesLiveMetadata(
    val source_name: String,
    val registration: String?,
    val type_code: String?,
    val description: String?,
    val owner_operator: String?,
    val operator_code: String?,
    val year: String?
) {
    val has_metadata: Boolean
        get() = listOf(registration, type_code, description, owner_operator, operator_code, year).any { !it.isNullOrBlank() }

    val has_core_metadata: Boolean
        get() = listOf(registration, type_code, description, owner_operator, operator_code, year).all { !it.isNullOrBlank() }

    val has_aircraft_identity: Boolean
        get() = listOf(registration, type_code, description).all { !it.isNullOrBlank() }
}

internal data class InternetAircraftMetadata(
    val manufacturer: String?,
    val model: String?,
    val source_name: String
)

internal data class WikipediaSummary(
    val title: String,
    val extract: String
)

internal data class CachedDetails(
    val details: AircraftDetails,
    val stored_at_ms: Long
)

internal data class RouteLookup(
    val route: String?,
    val origin: AirportDetails?,
    val destination: AirportDetails?
)


internal fun JSONObject.details_to_adsb_db_airport(): AirportDetails {
    return AirportDetails(
        icao = details_json_string_or_null("icao_code") ?: details_json_string_or_null("icao") ?: "Unavailable",
        iata = details_json_string_or_null("iata_code") ?: details_json_string_or_null("iata"),
        name = details_json_string_or_null("name"),
        country_code = details_json_string_or_null("country_iso_name") ?: details_json_string_or_null("country_iso"),
        region_name = details_json_string_or_null("municipality"),
        latitude = details_json_double_or_null("latitude"),
        longitude = details_json_double_or_null("longitude")
    )
}


internal fun JSONArray.details_to_route_airports(): List<AirportDetails> {
    val result = ArrayList<AirportDetails>(length())
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val icao = item.details_json_string_or_null("icao") ?: item.details_json_string_or_null("icao_code") ?: continue
        result += AirportDetails(
            icao = icao,
            iata = item.details_json_string_or_null("iata") ?: item.details_json_string_or_null("iata_code"),
            name = item.details_json_string_or_null("name") ?: item.details_json_string_or_null("airport"),
            country_code = item.details_json_string_or_null("countryiso2")
                ?: item.details_json_string_or_null("country_code")
                ?: item.details_json_string_or_null("country"),
            region_name = item.details_json_string_or_null("location") ?: item.details_json_string_or_null("region_name"),
            latitude = item.details_json_double_or_null("lat") ?: item.details_json_double_or_null("latitude"),
            longitude = item.details_json_double_or_null("lon") ?: item.details_json_double_or_null("lng") ?: item.details_json_double_or_null("longitude")
        )
    }
    return result
}


internal fun JSONObject.details_json_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}


internal fun JSONObject.details_json_first_string_or_null(vararg keys: String): String? {
    for (key in keys) {
        details_json_string_or_null(key)?.let { return it }
    }
    return null
}

internal fun static_chunk_has_child(chunk: JSONObject, child_key: String): Boolean {
    val children = chunk.opt("children") ?: return false
    val normalized_child = child_key.uppercase(Locale.US)
    return when (children) {
        is JSONArray -> {
            for (index in 0 until children.length()) {
                if (children.optString(index).uppercase(Locale.US) == normalized_child) return true
            }
            false
        }
        else -> children.toString().uppercase(Locale.US).contains(normalized_child)
    }
}


internal fun JSONArray.details_json_string_or_null(index: Int): String? {
    if (index !in 0 until length() || isNull(index)) return null
    return optString(index).trim().ifEmpty { null }
}


internal fun JSONObject.details_json_long_or_null(key: String): Long? {
    return if (has(key) && !isNull(key)) optLong(key) else null
}


internal fun JSONObject.details_json_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}


internal const val DETAILS_US_N_NUMBER_ICAO_START = 0xA00001L

internal const val DETAILS_HTTP_TOO_MANY_REQUESTS = FlightAlertAppSettings.AircraftDetails.HTTP_TOO_MANY_REQUESTS

internal const val DETAILS_CACHE_MAX_AGE_MS = FlightAlertAppSettings.AircraftDetails.CACHE_MAX_AGE_MS

internal const val DETAILS_CACHE_MAX_ENTRIES = FlightAlertAppSettings.AircraftDetails.CACHE_MAX_ENTRIES

internal const val DETAILS_STATIC_DB_CACHE_MAX_ENTRIES = FlightAlertAppSettings.AircraftDetails.STATIC_DB_CACHE_MAX_ENTRIES

internal const val DETAILS_US_N_NUMBER_ICAO_END = 0xADF7C7L

internal const val DETAILS_FIRST_N_NUMBER_STRIDE = 101711

internal const val DETAILS_SECOND_N_NUMBER_STRIDE = 10111

internal const val DETAILS_THIRD_N_NUMBER_STRIDE = 951

internal const val DETAILS_FOURTH_N_NUMBER_STRIDE = 35

internal const val DETAILS_N_NUMBER_SUFFIX_COUNT = 601

internal const val DETAILS_MAX_TWO_LETTER_SUFFIX_OFFSET = DETAILS_N_NUMBER_SUFFIX_COUNT - 1

internal const val DETAILS_N_NUMBER_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"


// Live aircraft feed client: viewport coverage and exact-search detail stay separate until both real sources answer.
