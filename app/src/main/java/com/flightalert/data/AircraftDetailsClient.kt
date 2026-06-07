package com.flightalert.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

class AircraftDetailsClient(private val userAgent: String) {

    fun fetchDetails(hex: String, callsign: String, registrationHint: String? = null): AircraftDetails {
        val normalizedHex = hex.trim().trimStart('~').lowercase(Locale.US)
        val cleanCallsign = callsign.trim().replace(" ", "")
        val aircraft = fetchJson("https://hexdb.io/api/v1/aircraft/$normalizedHex")
        val registration = aircraft?.optStringOrNull("Registration") ?: registrationHint?.trim()?.ifEmpty { null }
        val faa = registration?.takeIf { it.startsWith("N", ignoreCase = true) }?.let { fetchFaaRegistry(it) }
        val adsbRoute = if (cleanCallsign.isNotEmpty() && cleanCallsign.lowercase(Locale.US) != normalizedHex) {
            fetchAdsbDbRoute(cleanCallsign)
        } else {
            null
        }
        val route = if (cleanCallsign.isNotEmpty() && cleanCallsign.lowercase(Locale.US) != normalizedHex) {
            fetchJson("https://hexdb.io/api/v1/route/icao/$cleanCallsign")
        } else {
            null
        }
        val routeCodes = route?.optStringOrNull("route")?.split("-")?.takeIf { it.size >= 2 }
        val origin = adsbRoute?.origin ?: routeCodes?.firstOrNull()?.let { fetchAirport(it) }
        val destination = adsbRoute?.destination ?: routeCodes?.lastOrNull()?.let { fetchAirport(it) }

        return AircraftDetails(
            icao24 = normalizedHex,
            registration = faa?.registration ?: registration,
            manufacturer = faa?.manufacturer ?: aircraft?.optStringOrNull("Manufacturer"),
            type = faa?.model ?: aircraft?.optStringOrNull("Type"),
            typeCode = aircraft?.optStringOrNull("ICAOTypeCode"),
            owner = faa?.registeredOwner ?: aircraft?.optStringOrNull("RegisteredOwners"),
            manufacturedYear = faa?.manufacturedYear,
            registrySource = faa?.sourceName ?: "HexDB",
            operatorCode = aircraft?.optStringOrNull("OperatorFlagCode"),
            route = adsbRoute?.route ?: route?.optStringOrNull("route"),
            routeUpdatedEpochSec = route?.optLongOrNull("updatetime"),
            originAirport = origin,
            destinationAirport = destination
        )
    }

    private fun fetchFaaRegistry(registration: String): FaaRegistryRecord? {
        val nNumber = registration.trim().removePrefix("N").removePrefix("n")
        if (nNumber.isEmpty()) return null
        val encoded = URLEncoder.encode(nNumber, "UTF-8")
        val html = fetchText("https://registry.faa.gov/AircraftInquiry/Search/NNumberResult?nNumberTxt=$encoded") ?: return null
        val text = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (!text.contains("N${nNumber.uppercase(Locale.US)} is Assigned", ignoreCase = true)) return null
        val ownerRaw = valueBetween(text, "Registered Owner", "Airworthiness")
        val privateOwner = ownerRaw?.contains("requested to keep this data private", ignoreCase = true) == true
        return FaaRegistryRecord(
            registration = "N${nNumber.uppercase(Locale.US)}",
            manufacturer = valueBetween(text, "Manufacturer Name", "Certificate Issue Date"),
            model = valueBetween(text, "Model", "Expiration Date"),
            manufacturedYear = valueBetween(text, "MFR Year", "Mode S Code")?.take(4),
            registeredOwner = if (privateOwner) "Private under 49 USC 44114" else valueBetween(ownerRaw ?: "", "Name", "Street"),
            sourceName = "FAA Registry"
        )
    }

    private fun fetchAirport(icao: String): AirportDetails? {
        val json = fetchJson("https://hexdb.io/api/v1/airport/icao/${icao.trim()}") ?: return null
        return AirportDetails(
            icao = json.optStringOrNull("icao") ?: icao,
            iata = json.optStringOrNull("iata"),
            name = json.optStringOrNull("airport"),
            countryCode = json.optStringOrNull("country_code"),
            regionName = json.optStringOrNull("region_name"),
            latitude = json.optDoubleOrNull("latitude"),
            longitude = json.optDoubleOrNull("longitude")
        )
    }

    private fun fetchAdsbDbRoute(callsign: String): RouteLookup? {
        val encoded = URLEncoder.encode(callsign.trim(), "UTF-8")
        val route = fetchJson("https://api.adsbdb.com/v0/callsign/$encoded")
            ?.optJSONObject("response")
            ?.optJSONObject("flightroute")
            ?: return null
        val origin = route.optJSONObject("origin")?.toAdsbDbAirport()
        val destination = route.optJSONObject("destination")?.toAdsbDbAirport()
        if (origin == null && destination == null) return null
        val routeLabel = listOfNotNull(
            origin?.icao ?: origin?.iata,
            destination?.icao ?: destination?.iata
        ).joinToString("-").ifEmpty { null }
        return RouteLookup(routeLabel, origin, destination)
    }

    private fun fetchJson(url: String): JSONObject? {
        val body = fetchText(url) ?: return null
        return try {
            JSONObject(body).takeUnless { it.optStringOrNull("status") == "404" }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchText(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
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

private fun valueBetween(text: String, start: String, end: String): String? {
    val regex = Regex("${Regex.escape(start)}\\s+(.*?)\\s+${Regex.escape(end)}", RegexOption.IGNORE_CASE)
    return regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.ifEmpty { null }
}

data class FaaRegistryRecord(
    val registration: String,
    val manufacturer: String?,
    val model: String?,
    val manufacturedYear: String?,
    val registeredOwner: String?,
    val sourceName: String
)

data class AircraftDetails(
    val icao24: String,
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val typeCode: String?,
    val owner: String?,
    val manufacturedYear: String?,
    val registrySource: String?,
    val operatorCode: String?,
    val route: String?,
    val routeUpdatedEpochSec: Long?,
    val originAirport: AirportDetails?,
    val destinationAirport: AirportDetails?
)

data class AirportDetails(
    val icao: String,
    val iata: String?,
    val name: String?,
    val countryCode: String?,
    val regionName: String?,
    val latitude: Double?,
    val longitude: Double?
)

private data class RouteLookup(
    val route: String?,
    val origin: AirportDetails?,
    val destination: AirportDetails?
)

private fun JSONObject.toAdsbDbAirport(): AirportDetails {
    return AirportDetails(
        icao = optStringOrNull("icao_code") ?: optStringOrNull("icao") ?: "Unavailable",
        iata = optStringOrNull("iata_code") ?: optStringOrNull("iata"),
        name = optStringOrNull("name"),
        countryCode = optStringOrNull("country_iso_name") ?: optStringOrNull("country_iso"),
        regionName = optStringOrNull("municipality"),
        latitude = optDoubleOrNull("latitude"),
        longitude = optDoubleOrNull("longitude")
    )
}

private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().ifEmpty { null }
}

private fun JSONObject.optLongOrNull(key: String): Long? {
    return if (has(key) && !isNull(key)) optLong(key) else null
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    return if (has(key) && !isNull(key)) optDouble(key) else null
}
