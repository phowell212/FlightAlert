package com.flightalert.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale

class AircraftDetailsClient(private val userAgent: String) {
    private val detailsCache = linkedMapOf<String, CachedDetails>()

    fun fetchDetails(
        hex: String,
        callsign: String,
        registrationHint: String? = null,
        metadataSeed: AircraftMetadataSeed? = null
    ): AircraftDetails {
        val normalizedHex = hex.trim().trimStart('~').lowercase(Locale.US)
        val cleanCallsign = callsign.trim().replace(" ", "")
        val cacheKey = detailsCacheKey(normalizedHex, cleanCallsign, metadataSeed)
        cachedDetails(cacheKey)?.let { return it }
        detailsFromSeed(normalizedHex, registrationHint, metadataSeed)?.let { details ->
            cacheDetails(cacheKey, details)
            return details
        }
        val airplanesLive = fetchAirplanesLiveMetadata(normalizedHex)
        val feedRegistration = normalizedRegistration(registrationHint)
        val decodedUsRegistration = decodeUsNNumber(normalizedHex)
        val airplanesRegistration = normalizedRegistration(airplanesLive?.registration)
        val adsbAircraft = fetchAdsbDbAircraft(normalizedHex, airplanesRegistration ?: feedRegistration ?: decodedUsRegistration)
        val aircraft = fetchJson("https://hexdb.io/api/v1/aircraft/$normalizedHex")
        val registration = firstPresent(
            airplanesRegistration,
            adsbAircraft?.registration,
            aircraft?.optStringOrNull("Registration")?.let(::normalizedRegistration),
            feedRegistration,
            decodedUsRegistration
        )
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
        val feedType = airplanesLive?.description
        val apiManufacturer = faa?.manufacturer ?: adsbAircraft?.manufacturer ?: aircraft?.optStringOrNull("Manufacturer")
        val apiType = faa?.model ?: adsbAircraft?.type ?: aircraft?.optStringOrNull("Type")
        val apiTypeCode = airplanesLive?.typeCode ?: adsbAircraft?.icaoType ?: aircraft?.optStringOrNull("ICAOTypeCode")
        val internet = if (feedType == null && apiType == null) {
            fetchInternetAircraftMetadata(apiManufacturer, feedType ?: apiType, apiTypeCode)
        } else {
            null
        }
        val manufacturer = apiManufacturer ?: internet?.manufacturer
        val type = feedType ?: apiType ?: internet?.model
        val owner = faa?.registeredOwner ?: adsbAircraft?.registeredOwner ?: aircraft?.optStringOrNull("RegisteredOwners") ?: airplanesLive?.ownerOperator

        val metadataSources = mutableListOf<String>()
        if (airplanesLive?.hasMetadata == true) metadataSources += airplanesLive.sourceName
        if (faa != null) metadataSources += faa.sourceName
        if (adsbAircraft != null) metadataSources += "ADSBdb"
        if (aircraft != null) metadataSources += "HexDB"
        if (internet?.model != null && internet.model == type) metadataSources += internet.sourceName
        if (metadataSources.isEmpty() && registration != null && registration == feedRegistration) metadataSources += "Aircraft feed"
        if (metadataSources.isEmpty() && registration != null && registration == decodedUsRegistration) metadataSources += "Mode S N-number decode"

        return AircraftDetails(
            icao24 = normalizedHex,
            registration = faa?.registration ?: registration,
            manufacturer = manufacturer,
            type = type,
            typeCode = apiTypeCode,
            owner = owner,
            manufacturedYear = faa?.manufacturedYear ?: airplanesLive?.year,
            registrySource = metadataSources.distinct().joinToString(" + ").ifEmpty { null },
            operatorCode = adsbAircraft?.operatorCode ?: aircraft?.optStringOrNull("OperatorFlagCode") ?: airplanesLive?.operatorCode,
            route = adsbRoute?.route ?: route?.optStringOrNull("route"),
            routeUpdatedEpochSec = route?.optLongOrNull("updatetime"),
            originAirport = origin,
            destinationAirport = destination
        ).also { cacheDetails(cacheKey, it) }
    }

    private fun detailsFromSeed(
        normalizedHex: String,
        registrationHint: String?,
        seed: AircraftMetadataSeed?
    ): AircraftDetails? {
        if (seed == null || !seed.hasDetails) return null
        val registration = firstPresent(
            normalizedRegistration(seed.registration),
            normalizedRegistration(registrationHint),
            decodeUsNNumber(normalizedHex)
        )
        val manufacturer = seed.manufacturer?.cleanSeedValue()
        val type = seed.type?.cleanSeedValue()
        val typeCode = seed.typeCode?.cleanSeedValue()
        val owner = seed.owner?.cleanSeedValue()
        val year = seed.manufacturedYear?.cleanSeedValue()?.take(4)
        val operatorCode = seed.operatorCode?.cleanSeedValue()
        if (listOf(registration, manufacturer, type, typeCode, owner, year, operatorCode).all { it.isNullOrBlank() }) {
            return null
        }
        return AircraftDetails(
            icao24 = normalizedHex,
            registration = registration,
            manufacturer = manufacturer,
            type = type,
            typeCode = typeCode,
            owner = owner,
            manufacturedYear = year,
            registrySource = seed.sourceName,
            operatorCode = operatorCode,
            route = null,
            routeUpdatedEpochSec = null,
            originAirport = null,
            destinationAirport = null
        )
    }

    private fun detailsCacheKey(normalizedHex: String, cleanCallsign: String, seed: AircraftMetadataSeed?): String {
        val source = seed?.sourceName?.takeIf { seed.hasDetails } ?: "network"
        return "$normalizedHex|${cleanCallsign.uppercase(Locale.US)}|$source"
    }

    private fun cachedDetails(key: String): AircraftDetails? {
        val now = System.currentTimeMillis()
        return synchronized(detailsCache) {
            val cached = detailsCache[key] ?: return@synchronized null
            if (now - cached.storedAtMs > DETAILS_CACHE_MAX_AGE_MS) {
                detailsCache.remove(key)
                null
            } else {
                cached.details
            }
        }
    }

    private fun cacheDetails(key: String, details: AircraftDetails) {
        synchronized(detailsCache) {
            detailsCache[key] = CachedDetails(details, System.currentTimeMillis())
            while (detailsCache.size > DETAILS_CACHE_MAX_ENTRIES) {
                val firstKey = detailsCache.keys.firstOrNull() ?: break
                detailsCache.remove(firstKey)
            }
        }
    }

    private fun fetchInternetAircraftMetadata(
        manufacturer: String?,
        model: String?,
        typeCode: String?
    ): InternetAircraftMetadata? {
        val queries = listOfNotNull(
            listOfNotNull(manufacturer, model).joinToString(" ").takeIf { it.isNotBlank() }?.let { "$it aircraft" },
            typeCode?.takeIf { it.length >= 3 }?.let { "$it aircraft" },
            model?.takeIf { it.length >= 3 }?.let { "$it aircraft" }
        ).distinctBy { it.uppercase(Locale.US) }
        for (query in queries) {
            fetchWikipediaAircraftMetadata(query)?.let { return it }
        }
        return null
    }

    private fun fetchWikipediaAircraftMetadata(query: String): InternetAircraftMetadata? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val search = fetchJson("https://en.wikipedia.org/w/api.php?action=query&format=json&list=search&srlimit=3&srsearch=$encoded")
            ?.optJSONObject("query")
            ?.optJSONArray("search")
            ?: return null
        for (index in 0 until search.length()) {
            val result = search.optJSONObject(index) ?: continue
            val title = result.optStringOrNull("title") ?: continue
            val snippet = stripHtml(result.optStringOrNull("snippet").orEmpty())
            if (!looksLikeAircraftPage(title, snippet)) continue
            val summary = fetchWikipediaSummary(title) ?: continue
            if (!looksLikeAircraftPage(summary.title, summary.extract)) continue
            return InternetAircraftMetadata(
                manufacturer = manufacturerFromWikipediaTitle(summary.title),
                model = summary.title,
                sourceName = "Wikipedia summary"
            )
        }
        return null
    }

    private fun fetchWikipediaSummary(title: String): WikipediaSummary? {
        val encodedTitle = URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
        val json = fetchJson("https://en.wikipedia.org/api/rest_v1/page/summary/$encodedTitle") ?: return null
        val extract = json.optStringOrNull("extract") ?: return null
        return WikipediaSummary(
            title = json.optStringOrNull("title") ?: title,
            extract = extract
        )
    }

    private fun fetchAirplanesLiveMetadata(hex: String): AirplanesLiveMetadata? {
        if (hex.isBlank()) return null
        fetchAirplanesLiveTraceMetadata(hex)?.let { return it }
        return fetchAirplanesLiveRestMetadata(hex)
    }

    private fun fetchAirplanesLiveTraceMetadata(hex: String): AirplanesLiveMetadata? {
        val folder = hex.takeLast(2)
        val json = fetchJson("${AirplanesLiveHttp.GLOBE_BASE_URL}/data/traces/$folder/trace_full_$hex.json", browserHeaders = true)
            ?: return null
        return json.toAirplanesLiveMetadata("Airplanes.Live trace")
    }

    private fun fetchAirplanesLiveRestMetadata(hex: String): AirplanesLiveMetadata? {
        AirplanesLiveHttp.waitForRestApiSlot()
        val json = fetchJson("${AirplanesLiveHttp.API_BASE_URL}/hex/$hex", browserHeaders = true) ?: return null
        val aircraft = json.optJSONArray("aircraft") ?: return null
        for (index in 0 until aircraft.length()) {
            val item = aircraft.optJSONObject(index) ?: continue
            val itemHex = item.optStringOrNull("hex")?.trim()?.trimStart('~')?.lowercase(Locale.US)
            if (itemHex == hex) return item.toAirplanesLiveMetadata("Airplanes.Live")
        }
        return null
    }

    private fun fetchFaaRegistry(registration: String): FaaRegistryRecord? {
        val nNumber = registration.trim().removePrefix("N").removePrefix("n")
        if (nNumber.isEmpty()) return null
        val encoded = URLEncoder.encode(nNumber, "UTF-8")
        val html = fetchText("https://registry.faa.gov/AircraftInquiry/Search/NNumberResult?nNumberTxt=$encoded") ?: return null
        val resultHtml = html.substringAfter("<div id=\"mainDiv\"", html)
        val resultText = stripHtml(resultHtml)
        val normalizedRegistration = "N${nNumber.uppercase(Locale.US)}"
        if (!resultText.contains("$normalizedRegistration is Assigned", ignoreCase = true)) return null

        val ownerSection = registeredOwnerTableSection(resultHtml)
        val privateOwner = ownerSection
            ?.let(::stripHtml)
            ?.contains("requested to keep this data private", ignoreCase = true) == true
        val ownerName = if (privateOwner) {
            "Private under 49 USC 44114"
        } else {
            ownerSection?.let { valueFromDataLabel(it, "Name") }
        }

        return FaaRegistryRecord(
            registration = normalizedRegistration,
            manufacturer = valueFromDataLabel(resultHtml, "Manufacturer Name"),
            model = valueFromDataLabel(resultHtml, "Model"),
            manufacturedYear = valueFromDataLabel(resultHtml, "Mfr Year")?.take(4),
            registeredOwner = ownerName,
            sourceName = "FAA Registry"
        )
    }

    private fun fetchAdsbDbAircraft(hex: String, registration: String?): AdsbDbAircraftRecord? {
        val keys = listOfNotNull(hex.takeIf { it.isNotBlank() }, registration)
            .distinctBy { it.uppercase(Locale.US) }
        for (key in keys) {
            val encoded = URLEncoder.encode(key, "UTF-8")
            val aircraft = fetchJson("https://api.adsbdb.com/v0/aircraft/$encoded")
                ?.optJSONObject("response")
                ?.optJSONObject("aircraft")
                ?: continue
            val modeS = aircraft.optStringOrNull("mode_s")?.trim()?.trimStart('~')?.lowercase(Locale.US)
            if (key.equals(hex, ignoreCase = true) && modeS != null && modeS != hex) continue
            val foundRegistration = normalizedRegistration(aircraft.optStringOrNull("registration"))
            if (
                registration != null &&
                key.equals(registration, ignoreCase = true) &&
                foundRegistration != null &&
                foundRegistration != registration
            ) {
                continue
            }
            return AdsbDbAircraftRecord(
                registration = foundRegistration,
                manufacturer = aircraft.optStringOrNull("manufacturer"),
                type = aircraft.optStringOrNull("type"),
                icaoType = aircraft.optStringOrNull("icao_type"),
                registeredOwner = aircraft.optStringOrNull("registered_owner"),
                operatorCode = aircraft.optStringOrNull("registered_owner_operator_flag_code")
            )
        }
        return null
    }

    private fun fetchAirport(icao: String): AirportDetails? {
        val json = fetchJson("https://hexdb.io/api/v1/airport/icao/${icao.trim()}") ?: return null
        return AirportDetails(
            icao = json.optStringOrNull("icao") ?: icao,
            iata = json.optStringOrNull("iata_code") ?: json.optStringOrNull("iata"),
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

    private fun fetchJson(url: String, browserHeaders: Boolean = false): JSONObject? {
        val body = fetchText(url, browserHeaders) ?: return null
        return try {
            JSONObject(body).takeUnless { it.optStringOrNull("status") == "404" }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchText(url: String, browserHeaders: Boolean = false): String? {
        val safeUrl = httpsUrl(url) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (safeUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 6000
                requestMethod = "GET"
                if (browserHeaders) {
                    AirplanesLiveHttp.applyBrowserHeaders(this, userAgent)
                } else {
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "application/json,text/plain,*/*")
                }
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                if (connection.responseCode == HTTP_TOO_MANY_REQUESTS && safeUrl.host.equals("api.airplanes.live", ignoreCase = true)) {
                    AirplanesLiveHttp.backOffRestApi(connection.getHeaderField("Retry-After")?.toLongOrNull())
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

    private fun httpsUrl(value: String): URL? {
        return try {
            URL(value.trim()).takeIf { it.protocol.equals("https", ignoreCase = true) }
        } catch (_: Exception) {
            null
        }
    }
}

private fun registeredOwnerTableSection(html: String): String? {
    val captionRegex = Regex("<caption[^>]*>\\s*Registered Owner\\s*</caption>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val start = captionRegex.find(html)?.range?.first ?: return null
    val end = html.indexOf("</table>", start, ignoreCase = true).takeIf { it >= 0 } ?: return null
    return html.substring(start, end)
}

private fun looksLikeAircraftPage(title: String, text: String): Boolean {
    val combined = "$title $text".uppercase(Locale.US)
    val aircraftTerms = listOf(
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
    val rejectedTopics = listOf(
        "AIRPORT",
        "AIRLINE",
        "AIRWAYS",
        "ACCIDENT",
        "INCIDENT",
        "CRASH",
        "HIJACKING"
    )
    return aircraftTerms.any { combined.contains(it) } &&
        rejectedTopics.none { combined.contains(it) }
}

private fun manufacturerFromWikipediaTitle(@Suppress("UNUSED_PARAMETER") title: String): String? {
    // Wikipedia titles are useful model hints, but the title alone is not a structured manufacturer field.
    return null
}

private fun JSONObject.toAirplanesLiveMetadata(sourceName: String): AirplanesLiveMetadata {
    return AirplanesLiveMetadata(
        sourceName = sourceName,
        registration = optStringOrNull("r"),
        typeCode = optStringOrNull("t"),
        description = optStringOrNull("desc"),
        ownerOperator = optStringOrNull("ownOp"),
        operatorCode = optStringOrNull("ownOpCode"),
        year = optStringOrNull("year")?.take(4)
    )
}

private fun valueFromDataLabel(html: String, label: String): String? {
    val regex = Regex("<td[^>]*data-label=[\"']${Regex.escape(label)}[\"'][^>]*>([\\s\\S]*?)</td>", RegexOption.IGNORE_CASE)
    return regex.find(html)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::stripHtml)
        ?.let(::cleanRegistryValue)
}

private fun stripHtml(html: String): String {
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

private fun cleanRegistryValue(value: String): String? {
    val cleaned = value.trim().trim('-').trim()
    if (cleaned.isEmpty() || cleaned.equals("none", ignoreCase = true)) return null
    val pageChromeTerms = listOf("Lookup Aircraft By", "N-Number Availability", "Aircraft Inquiry Search")
    if (pageChromeTerms.any { cleaned.contains(it, ignoreCase = true) }) return null
    return cleaned
}

private fun String.cleanSeedValue(): String? {
    val cleaned = trim().trim('-').trim()
    return cleaned.takeIf {
        it.isNotBlank() &&
            !it.equals("null", ignoreCase = true) &&
            !it.equals("n/a", ignoreCase = true) &&
            !it.equals("unavailable", ignoreCase = true)
    }
}

private fun normalizedRegistration(value: String?): String? {
    return value
        ?.uppercase(Locale.US)
        ?.replace(Regex("[^A-Z0-9-]"), "")
        ?.trim('-')
        ?.takeIf { it.isNotBlank() && it != "NA" }
}

private fun firstPresent(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

private fun decodeUsNNumber(hex: String): String? {
    val value = hex.toLongOrNull(16) ?: return null
    if (value !in US_N_NUMBER_ICAO_START..US_N_NUMBER_ICAO_END) return null

    var offset = (value - US_N_NUMBER_ICAO_START).toInt()
    val registration = StringBuilder("N")
    registration.append(offset / FIRST_N_NUMBER_STRIDE + 1)
    offset %= FIRST_N_NUMBER_STRIDE
    appendNNumberSuffix(offset, registration)?.let { return it }

    offset -= N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / SECOND_N_NUMBER_STRIDE)
    offset %= SECOND_N_NUMBER_STRIDE
    appendNNumberSuffix(offset, registration)?.let { return it }

    offset -= N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / THIRD_N_NUMBER_STRIDE)
    offset %= THIRD_N_NUMBER_STRIDE
    appendNNumberSuffix(offset, registration)?.let { return it }

    offset -= N_NUMBER_SUFFIX_COUNT
    if (offset < 0) return null
    registration.append(offset / FOURTH_N_NUMBER_STRIDE)
    offset %= FOURTH_N_NUMBER_STRIDE
    if (offset <= N_NUMBER_ALPHABET.length) {
        return registration.append(nNumberSingleSuffix(offset)).toString()
    }

    val lastDigit = offset - N_NUMBER_ALPHABET.length - 1
    return registration.append(lastDigit).toString()
}

private fun appendNNumberSuffix(offset: Int, registration: StringBuilder): String? {
    if (offset > MAX_TWO_LETTER_SUFFIX_OFFSET) return null
    return registration.append(nNumberSuffix(offset)).toString()
}

private fun nNumberSuffix(offset: Int): String {
    if (offset <= 0) return ""
    val index = offset - 1
    if (index < N_NUMBER_ALPHABET.length) return N_NUMBER_ALPHABET[index].toString()
    val doubleIndex = index - N_NUMBER_ALPHABET.length
    val first = doubleIndex / N_NUMBER_ALPHABET.length
    val second = doubleIndex % N_NUMBER_ALPHABET.length
    return "${N_NUMBER_ALPHABET[first]}${N_NUMBER_ALPHABET[second]}"
}

private fun nNumberSingleSuffix(offset: Int): String {
    return if (offset <= 0) "" else N_NUMBER_ALPHABET[offset - 1].toString()
}

data class FaaRegistryRecord(
    val registration: String,
    val manufacturer: String?,
    val model: String?,
    val manufacturedYear: String?,
    val registeredOwner: String?,
    val sourceName: String
)

private data class AdsbDbAircraftRecord(
    val registration: String?,
    val manufacturer: String?,
    val type: String?,
    val icaoType: String?,
    val registeredOwner: String?,
    val operatorCode: String?
)

private data class AirplanesLiveMetadata(
    val sourceName: String,
    val registration: String?,
    val typeCode: String?,
    val description: String?,
    val ownerOperator: String?,
    val operatorCode: String?,
    val year: String?
) {
    val hasMetadata: Boolean
        get() = listOf(registration, typeCode, description, ownerOperator, operatorCode, year).any { !it.isNullOrBlank() }
}

private data class InternetAircraftMetadata(
    val manufacturer: String?,
    val model: String?,
    val sourceName: String
)

private data class WikipediaSummary(
    val title: String,
    val extract: String
)

private data class CachedDetails(
    val details: AircraftDetails,
    val storedAtMs: Long
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

private const val US_N_NUMBER_ICAO_START = 0xA00001L
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val DETAILS_CACHE_MAX_AGE_MS = 10L * 60L * 1000L
private const val DETAILS_CACHE_MAX_ENTRIES = 128
private const val US_N_NUMBER_ICAO_END = 0xADF7C7L
private const val FIRST_N_NUMBER_STRIDE = 101711
private const val SECOND_N_NUMBER_STRIDE = 10111
private const val THIRD_N_NUMBER_STRIDE = 951
private const val FOURTH_N_NUMBER_STRIDE = 35
private const val N_NUMBER_SUFFIX_COUNT = 601
private const val MAX_TWO_LETTER_SUFFIX_OFFSET = N_NUMBER_SUFFIX_COUNT - 1
private const val N_NUMBER_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ"
