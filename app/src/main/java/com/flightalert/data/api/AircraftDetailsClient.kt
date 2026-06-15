package com.flightalert.data.api

import android.util.Log
import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftMetadataSeed
import com.flightalert.data.AircraftRouteSource
import com.flightalert.data.AircraftTelemetry
import com.flightalert.data.AirportDetails
import com.flightalert.data.FaaRegistryRecord
import com.flightalert.data.airplaneslive.AirplanesLiveHttp
import com.flightalert.settings.FlightAlertSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

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
        val feed_registration = normalized_registration(registration_hint)
        val seed_registration = normalized_registration(seed_details?.registration)
        val decoded_us_registration = decode_us_n_number(normalized_hex)
        val airplanes_registration = normalized_registration(airplanes_live?.registration ?: static_airplanes_live?.registration)
        val adsb_aircraft = fetch_adsb_db_aircraft(normalized_hex, airplanes_registration ?: feed_registration ?: seed_registration ?: decoded_us_registration)
        val aircraft = fetch_json("https://hexdb.io/api/v1/aircraft/$normalized_hex")
        val registration = first_present(
            airplanes_registration,
            adsb_aircraft?.registration,
            aircraft?.opt_string_or_null("Registration")?.let(::normalized_registration),
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
        val route_codes = route?.opt_string_or_null("route")?.split("-")?.takeIf { it.size >= 2 }
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
        if (clean_callsign.isNotEmpty()) {
            Log.d(
                TAG,
                "Route lookup hex=$normalized_hex callsign=$clean_callsign " +
                    "adsb=${adsb_route?.route ?: "none"} adsbim=${adsb_im_route?.route ?: "none"} hexdb=${route?.opt_string_or_null("route") ?: "none"} " +
                    "origin=${origin?.icao ?: "none"} destination=${destination?.icao ?: "none"}"
            )
        }
        val feed_type = airplanes_live?.description ?: static_airplanes_live?.description
        val api_manufacturer = faa?.manufacturer ?: adsb_aircraft?.manufacturer ?: aircraft?.opt_string_or_null("Manufacturer")
        val api_type = faa?.model ?: adsb_aircraft?.type ?: aircraft?.opt_string_or_null("Type")
        val api_type_code = airplanes_live?.type_code ?: static_airplanes_live?.type_code ?: adsb_aircraft?.icao_type ?: aircraft?.opt_string_or_null("ICAOTypeCode")
        val seed_type = seed_details?.type
        val internet = if (feed_type == null && api_type == null && seed_type == null) {
            fetch_internet_aircraft_metadata(api_manufacturer ?: seed_details?.manufacturer, seed_type, api_type_code ?: seed_details?.type_code)
        } else {
            null
        }
        val manufacturer = api_manufacturer ?: seed_details?.manufacturer ?: internet?.manufacturer
        val type = feed_type ?: api_type ?: seed_type ?: internet?.model
        val owner = faa?.registered_owner ?: adsb_aircraft?.registered_owner ?: aircraft?.opt_string_or_null("RegisteredOwners") ?: airplanes_live?.owner_operator ?: seed_details?.owner

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
            operator_code = adsb_aircraft?.operator_code ?: aircraft?.opt_string_or_null("OperatorFlagCode") ?: airplanes_live?.operator_code ?: static_airplanes_live?.operator_code ?: seed_details?.operator_code,
            route = adsb_route?.route ?: adsb_im_route?.route ?: route?.opt_string_or_null("route") ?: seed_details?.route,
            route_updated_epoch_sec = route?.opt_long_or_null("updatetime") ?: seed_details?.route_updated_epoch_sec,
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
        val registration = first_present(
            normalized_registration(seed?.registration),
            normalized_registration(registration_hint),
            decode_us_n_number(normalized_hex)
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
            val title = result.opt_string_or_null("title") ?: continue
            val snippet = strip_html(result.opt_string_or_null("snippet").orEmpty())
            if (!looks_like_aircraft_page(title, snippet)) continue
            val summary = fetch_wikipedia_summary(title) ?: continue
            if (!looks_like_aircraft_page(summary.title, summary.extract)) continue
            return InternetAircraftMetadata(
                manufacturer = manufacturer_from_wikipedia_title(summary.title),
                model = summary.title,
                source_name = "Wikipedia summary"
            )
        }
        return null
    }

    private fun fetch_wikipedia_summary(title: String): WikipediaSummary? {
        val encoded_title = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val json = fetch_json("https://en.wikipedia.org/api/rest_v1/page/summary/$encoded_title") ?: return null
        val extract = json.opt_string_or_null("extract") ?: return null
        return WikipediaSummary(
            title = json.opt_string_or_null("title") ?: title,
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
            val item_hex = item.opt_string_or_null("hex")?.trim()?.trimStart('~')?.lowercase(Locale.US)
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
            registration = row.opt_string_or_null(0),
            type_code = row.opt_string_or_null(1),
            description = row.opt_string_or_null(3),
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
            while (static_aircraft_db_cache.size > STATIC_DB_CACHE_MAX_ENTRIES) {
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
        val result_text = strip_html(result_html)
        val normalized_registration = "N${n_number.uppercase(Locale.US)}"
        if (!result_text.contains("$normalized_registration is Assigned", ignoreCase = true)) return null

        val owner_section = registered_owner_table_section(result_html)
        val private_owner = owner_section
            ?.let(::strip_html)
            ?.contains("requested to keep this data private", ignoreCase = true) == true
        val owner_name = if (private_owner) {
            "Private under 49 USC 44114"
        } else {
            owner_section?.let { value_from_data_label(it, "Name") }
        }

        return FaaRegistryRecord(
            registration = normalized_registration,
            manufacturer = value_from_data_label(result_html, "Manufacturer Name"),
            model = value_from_data_label(result_html, "Model"),
            manufactured_year = value_from_data_label(result_html, "Mfr Year")?.take(4),
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
            val mode_s = aircraft.opt_string_or_null("mode_s")?.trim()?.trimStart('~')?.lowercase(Locale.US)
            if (key.equals(hex, ignoreCase = true) && mode_s != null && mode_s != hex) continue
            val found_registration = normalized_registration(aircraft.opt_string_or_null("registration"))
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
                manufacturer = aircraft.opt_string_or_null("manufacturer"),
                type = aircraft.opt_string_or_null("type"),
                icao_type = aircraft.opt_string_or_null("icao_type"),
                registered_owner = aircraft.opt_string_or_null("registered_owner"),
                operator_code = aircraft.opt_string_or_null("registered_owner_operator_flag_code")
            )
        }
        return null
    }

    private fun fetch_airport(icao: String): AirportDetails? {
        val json = fetch_json("https://hexdb.io/api/v1/airport/icao/${icao.trim()}") ?: return null
        return AirportDetails(
            icao = json.opt_string_or_null("icao") ?: icao,
            iata = json.opt_string_or_null("iata_code") ?: json.opt_string_or_null("iata"),
            name = json.opt_string_or_null("airport"),
            country_code = json.opt_string_or_null("country_code"),
            region_name = json.opt_string_or_null("region_name"),
            latitude = json.opt_double_or_null("latitude"),
            longitude = json.opt_double_or_null("longitude")
        )
    }

    private fun fetch_adsb_db_route(callsign: String): RouteLookup? {
        val encoded = URLEncoder.encode(callsign.trim(), "UTF-8")
        val route = fetch_json("https://api.adsbdb.com/v0/callsign/$encoded")
            ?.optJSONObject("response")
            ?.optJSONObject("flightroute")
            ?: run {
                Log.d(TAG, "ADSBdb route unavailable callsign=${callsign.trim()}")
                return null
            }
        val origin = route.optJSONObject("origin")?.to_adsb_db_airport()
        val destination = route.optJSONObject("destination")?.to_adsb_db_airport()
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
        val route = post_json_array("https://adsb.im/api/0/routeset", request_body)
            ?.optJSONObject(0)
            ?: return null
        val returned_callsign = route.opt_string_or_null("callsign")?.replace(" ", "")?.uppercase(Locale.US)
        if (returned_callsign != null && returned_callsign != clean_callsign) return null
        val airports = route.optJSONArray("_airports")?.to_route_airports().orEmpty()
        if (airports.isEmpty()) return null
        val route_label = route.opt_string_or_null("airport_codes")
            ?: airports.mapNotNull { airport -> airport.icao.takeIf { it.isNotBlank() } }.takeIf { it.size >= 2 }?.joinToString("-")
            ?: return null
        val display_route = if (route.has("plausible") && !route.optBoolean("plausible", true)) {
            "?? $route_label"
        } else {
            route_label
        }
        return RouteLookup(display_route, airports.firstOrNull(), airports.lastOrNull()?.takeIf { airports.size >= 2 })
    }

    private fun post_json_array(url: String, body: String): JSONArray? {
        val safe_url = https_url(url) ?: return null
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
            JSONObject(body).takeUnless { it.opt_string_or_null("status") == "404" }
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
                if (connection.responseCode == HTTP_TOO_MANY_REQUESTS && safe_url.host.equals("api.airplanes.live", ignoreCase = true)) {
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

    private fun https_url(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }
}

private fun registered_owner_table_section(html: String): String? {
    val caption_regex = Regex("<caption[^>]*>\\s*Registered Owner\\s*</caption>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val start = caption_regex.find(html)?.range?.first ?: return null
    val end = html.indexOf("</table>", start, ignoreCase = true).takeIf { it >= 0 } ?: return null
    return html.substring(start, end)
}

private fun looks_like_aircraft_page(title: String, text: String): Boolean {
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

// Wikipedia titles are useful model hints, but the title alone is not a structured manufacturer field.
private fun manufacturer_from_wikipedia_title(@Suppress("UNUSED_PARAMETER") title: String): String? {
    return null
}

internal fun JSONObject.to_airplanes_live_metadata(source_name: String): AirplanesLiveMetadata {
    return AirplanesLiveMetadata(
        source_name = source_name,
        registration = opt_first_string_or_null("r", "registration", "reg"),
        type_code = opt_first_string_or_null("t", "icaoType", "icao_type", "type_code"),
        description = opt_first_string_or_null("desc", "typeLong", "description", "typeDescription", "type_description"),
        owner_operator = opt_first_string_or_null("ownOp", "owner_operator", "operator", "owner"),
        operator_code = opt_first_string_or_null("ownOpCode", "operator_code"),
        year = opt_first_string_or_null("year", "mfrYear", "manufacturedYear", "manufactureYear", "mfr_year", "manufacture_year")
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

private fun value_from_data_label(html: String, label: String): String? {
    val regex = Regex("<td[^>]*data-label=[\"']${Regex.escape(label)}[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
    return regex.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::strip_html)
        ?.let(::clean_registry_value)
}

private fun strip_html(html: String): String {
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

private fun clean_registry_value(value: String): String? {
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

private fun String.clean_seed_value(): String? {
    val cleaned = trim().trim('-').trim()
    return cleaned.takeIf {
        it.isNotBlank() &&
            !it.equals("null", ignoreCase = true) &&
            !it.equals("n/a", ignoreCase = true) &&
            !it.equals("unavailable", ignoreCase = true)
    }
}

private fun normalized_registration(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.replace(Regex("[^A-Z0-9-]"), "")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() && it != "NA" }
}

private fun first_present(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun decode_us_n_number(hex: String): String? {
    val value = hex.toLongOrNull(16) ?: return null
    if (value !in US_N_NUMBER_ICAO_START..US_N_NUMBER_ICAO_END) return null

    var offset = (value - US_N_NUMBER_ICAO_START).toInt()
    val registration = StringBuilder("N")
    registration.append(offset / FIRST_N_NUMBER_STRIDE + 1)
    offset %= FIRST_N_NUMBER_STRIDE
    append_n_number_suffix(offset, registration)?.let { return it }

    offset -= N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / SECOND_N_NUMBER_STRIDE)
    offset %= SECOND_N_NUMBER_STRIDE
    append_n_number_suffix(offset, registration)?.let { return it }

    offset -= N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / THIRD_N_NUMBER_STRIDE)
    offset %= THIRD_N_NUMBER_STRIDE
    append_n_number_suffix(offset, registration)?.let { return it }

    offset -= N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / FOURTH_N_NUMBER_STRIDE)
    offset %= FOURTH_N_NUMBER_STRIDE
    if (offset <= N_NUMBER_ALPHABET.length) {
        return registration.append(n_number_single_suffix(offset)).toString()
    }

    val last_digit = offset - N_NUMBER_ALPHABET.length - 1
    return registration.append(last_digit).toString()
}

private fun append_n_number_suffix(offset: Int, registration: StringBuilder): String? {
    if (offset > MAX_TWO_LETTER_SUFFIX_OFFSET) return null
    return registration.append(n_number_suffix(offset)).toString()
}

private fun n_number_suffix(offset: Int): String {
    if (offset <= 0) return ""
    val index = offset - 1
    if (index < N_NUMBER_ALPHABET.length) return N_NUMBER_ALPHABET[index].toString()
    val double_index = index - N_NUMBER_ALPHABET.length
    val first = double_index / N_NUMBER_ALPHABET.length
    val second = double_index % N_NUMBER_ALPHABET.length
    return "${N_NUMBER_ALPHABET[first]}${N_NUMBER_ALPHABET[second]}"
}

private fun n_number_single_suffix(offset: Int): String {
    return if (offset <= 0) "" else N_NUMBER_ALPHABET[offset - 1].toString()
}

private data class AdsbDbAircraftRecord(
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

private data class InternetAircraftMetadata(
    val manufacturer: String?,
    val model: String?,
    val source_name: String
)

private data class WikipediaSummary(
    val title: String,
    val extract: String
)

private data class CachedDetails(
    val details: AircraftDetails,
    val stored_at_ms: Long
)

private data class RouteLookup(
    val route: String?,
    val origin: AirportDetails?,
    val destination: AirportDetails?
)

private fun JSONObject.to_adsb_db_airport(): AirportDetails {
    return AirportDetails(
        icao = opt_string_or_null("icao_code") ?: opt_string_or_null("icao") ?: "Unavailable",
        iata = opt_string_or_null("iata_code") ?: opt_string_or_null("iata"),
        name = opt_string_or_null("name"),
        country_code = opt_string_or_null("country_iso_name") ?: opt_string_or_null("country_iso"),
        region_name = opt_string_or_null("municipality"),
        latitude = opt_double_or_null("latitude"),
        longitude = opt_double_or_null("longitude")
    )
}

private fun JSONArray.to_route_airports(): List<AirportDetails> {
    val result = ArrayList<AirportDetails>(length())
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val icao = item.opt_string_or_null("icao") ?: item.opt_string_or_null("icao_code") ?: continue
        result += AirportDetails(
            icao = icao,
            iata = item.opt_string_or_null("iata") ?: item.opt_string_or_null("iata_code"),
            name = item.opt_string_or_null("name") ?: item.opt_string_or_null("airport"),
            country_code = item.opt_string_or_null("countryiso2") ?: item.opt_string_or_null("country_code") ?: item.opt_string_or_null("country"),
            region_name = item.opt_string_or_null("location") ?: item.opt_string_or_null("region_name"),
            latitude = item.opt_double_or_null("lat") ?: item.opt_double_or_null("latitude"),
            longitude = item.opt_double_or_null("lon") ?: item.opt_double_or_null("lng") ?: item.opt_double_or_null("longitude")
        )
    }
    return result
}

private fun JSONObject.opt_string_or_null(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}

private fun JSONObject.opt_first_string_or_null(vararg keys: String): String? {
    for (key in keys) {
        opt_string_or_null(key)?.let { return it }
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

private fun JSONArray.opt_string_or_null(index: Int): String? {
    if (index !in 0 until length() || isNull(index)) return null
    return optString(index).trim().ifEmpty { null }
}

private fun JSONObject.opt_long_or_null(key: String): Long? {
    return if (has(key) && !isNull(key)) optLong(key) else null
}

private fun JSONObject.opt_double_or_null(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}

private const val US_N_NUMBER_ICAO_START = 0xA00001L
private const val HTTP_TOO_MANY_REQUESTS = FlightAlertSettings.AircraftDetails.HTTP_TOO_MANY_REQUESTS
private const val DETAILS_CACHE_MAX_AGE_MS = FlightAlertSettings.AircraftDetails.CACHE_MAX_AGE_MS
private const val DETAILS_CACHE_MAX_ENTRIES = FlightAlertSettings.AircraftDetails.CACHE_MAX_ENTRIES
private const val STATIC_DB_CACHE_MAX_ENTRIES = FlightAlertSettings.AircraftDetails.STATIC_DB_CACHE_MAX_ENTRIES
private const val TAG = FlightAlertSettings.App.TAG
private const val US_N_NUMBER_ICAO_END = 0xADF7C7L
private const val FIRST_N_NUMBER_STRIDE = 101711
private const val SECOND_N_NUMBER_STRIDE = 10111
private const val THIRD_N_NUMBER_STRIDE = 951
private const val FOURTH_N_NUMBER_STRIDE = 35
private const val N_NUMBER_SUFFIX_COUNT = 601
private const val MAX_TWO_LETTER_SUFFIX_OFFSET = N_NUMBER_SUFFIX_COUNT - 1
private const val N_NUMBER_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
