@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftRouteSource
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.TrackPoint
import com.flightalert.flight.CurrentRouteValidator
import com.flightalert.flight.OriginAerodrome
import com.flightalert.flight.TraceSegment
import com.flightalert.map.MapProjection
import com.flightalert.sources.AirplanesLiveHttp
import com.flightalert.sources.fetch_json_object
import com.flightalert.sources.https_url



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

internal fun details_registered_owner_table_section(html: String): String? {
    val caption_regex = Regex(
        "<caption[^>]*>\\s*Registered Owner\\s*</caption>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
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
        description = details_json_first_string_or_null(
            "desc",
            "typeLong",
            "description",
            "typeDescription",
            "type_description"
        ),
        owner_operator = details_json_first_string_or_null(
            "ownOp",
            "owner_operator",
            "operator",
            "owner"
        ),
        operator_code = details_json_first_string_or_null("ownOpCode", "operator_code"),
        year = details_json_first_string_or_null(
            "year",
            "mfrYear",
            "manufacturedYear",
            "manufactureYear",
            "mfr_year",
            "manufacture_year"
        )
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
    val regex = Regex(
        "<td[^>]*data-label=[\"']${Regex.escape(label)}[\"'][^>]*>([\\s\\S]*?)</td>",
        RegexOption.IGNORE_CASE
    )
    return regex.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::plain_text_from_html)
        ?.let(::details_clean_registry_value)
}

internal fun details_clean_registry_value(value: String): String? {
    val cleaned = value.trim().trim('-').trim()
    if (cleaned.isEmpty() || cleaned.equals("none", ignoreCase = true)) return null
    val page_chrome_terms =
        listOf("Lookup Aircraft By", "N-Number Availability", "Aircraft Inquiry Search")
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

internal fun JSONObject.details_to_adsb_db_airport(): AirportDetails {
    return AirportDetails(
        icao = details_json_string_or_null("icao_code") ?: details_json_string_or_null("icao")
        ?: "Unavailable",
        iata = details_json_string_or_null("iata_code") ?: details_json_string_or_null("iata"),
        name = details_json_string_or_null("name"),
        country_code = details_json_string_or_null("country_iso_name")
            ?: details_json_string_or_null("country_iso"),
        region_name = details_json_string_or_null("municipality"),
        latitude = details_json_double_or_null("latitude"),
        longitude = details_json_double_or_null("longitude")
    )
}

internal fun JSONArray.details_to_route_airports(): List<AirportDetails> {
    val result = ArrayList<AirportDetails>(length())
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val icao = item.details_json_string_or_null("icao")
            ?: item.details_json_string_or_null("icao_code") ?: continue
        result += AirportDetails(
            icao = icao,
            iata = item.details_json_string_or_null("iata")
                ?: item.details_json_string_or_null("iata_code"),
            name = item.details_json_string_or_null("name")
                ?: item.details_json_string_or_null("airport"),
            country_code = item.details_json_string_or_null("countryiso2")
                ?: item.details_json_string_or_null("country_code")
                ?: item.details_json_string_or_null("country"),
            region_name = item.details_json_string_or_null("location")
                ?: item.details_json_string_or_null("region_name"),
            latitude = item.details_json_double_or_null("lat")
                ?: item.details_json_double_or_null("latitude"),
            longitude = item.details_json_double_or_null("lon") ?: item.details_json_double_or_null(
                "lng"
            ) ?: item.details_json_double_or_null("longitude")
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

internal const val DETAILS_HTTP_TOO_MANY_REQUESTS =
    FlightAlertAppSettings.AircraftDetails.HTTP_TOO_MANY_REQUESTS

internal const val DETAILS_CACHE_MAX_AGE_MS =
    FlightAlertAppSettings.AircraftDetails.CACHE_MAX_AGE_MS

internal const val DETAILS_CACHE_MAX_ENTRIES =
    FlightAlertAppSettings.AircraftDetails.CACHE_MAX_ENTRIES

internal const val DETAILS_STATIC_DB_CACHE_MAX_ENTRIES =
    FlightAlertAppSettings.AircraftDetails.STATIC_DB_CACHE_MAX_ENTRIES


internal const val DETAILS_US_N_NUMBER_ICAO_END = 0xADF7C7L

internal const val DETAILS_FIRST_N_NUMBER_STRIDE = 101711

internal const val DETAILS_SECOND_N_NUMBER_STRIDE = 10111

internal const val DETAILS_THIRD_N_NUMBER_STRIDE = 951

internal const val DETAILS_FOURTH_N_NUMBER_STRIDE = 35

internal const val DETAILS_N_NUMBER_SUFFIX_COUNT = 601

internal const val DETAILS_MAX_TWO_LETTER_SUFFIX_OFFSET = DETAILS_N_NUMBER_SUFFIX_COUNT - 1

internal const val DETAILS_N_NUMBER_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"

internal fun JSONObject.trace_origin_json_clean_string(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
        .trim()
        .takeIf { it.isNotBlank() && !it.equals("n/a", ignoreCase = true) }
}

