package com.flightalert.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class AviationLayerClient(private val userAgent: String) {

    fun fetchLayers(
        bounds: AviationLayerBounds,
        includeAtcBoundaries: Boolean,
        includeRestrictedAirspaces: Boolean,
        includeAirports: Boolean,
        includeOceanicTracks: Boolean
    ): AviationLayerSnapshot {
        val statuses = mutableMapOf<AviationLayerKind, AviationLayerStatus>()
        val atc = if (includeAtcBoundaries) {
            fetchFeatureLayer(
                kind = AviationLayerKind.ATC_BOUNDARIES,
                bounds = bounds,
                baseUrl = BOUNDARY_AIRSPACE_QUERY_URL,
                where = "TYPE_CODE IN ('ARTCC','FIR','OCA')",
                outFields = "NAME,IDENT,TYPE_CODE,LOCAL_TYPE,LEVEL_,UPPER_VAL,LOWER_VAL",
                maxRecords = MAX_BOUNDARY_RECORDS,
                statuses = statuses,
                parser = ::parseAirspaceFeatures
            )
        } else {
            emptyList()
        }
        val restricted = if (includeRestrictedAirspaces) {
            fetchFeatureLayer(
                kind = AviationLayerKind.RESTRICTED_AIRSPACES,
                bounds = bounds,
                baseUrl = SPECIAL_USE_AIRSPACE_QUERY_URL,
                where = "1=1",
                outFields = "NAME,TYPE_CODE,UPPER_VAL,LOWER_VAL,UPPER_UOM,LOWER_UOM,TIMESOFUSE,CITY,STATE",
                maxRecords = MAX_SPECIAL_USE_RECORDS,
                statuses = statuses,
                parser = ::parseAirspaceFeatures
            )
        } else {
            emptyList()
        }
        val airports = if (includeAirports) {
            fetchFeatureLayer(
                kind = AviationLayerKind.AIRPORTS,
                bounds = bounds,
                baseUrl = AIRPORT_QUERY_URL,
                where = "OPERSTATUS = 'OPERATIONAL'",
                outFields = "IDENT,NAME,ICAO_ID,TYPE_CODE,MIL_CODE,SERVCITY,STATE,COUNTRY",
                maxRecords = MAX_AIRPORT_RECORDS,
                statuses = statuses,
                parser = ::parseAirportFeatures
            )
        } else {
            emptyList()
        }
        val oceanic = if (includeOceanicTracks) {
            fetchOceanicTracks(statuses)
        } else {
            emptyList()
        }

        return AviationLayerSnapshot(
            atcBoundaries = atc,
            restrictedAirspaces = restricted,
            airports = airports,
            oceanicTracks = oceanic,
            statuses = statuses,
            fetchedAtMs = System.currentTimeMillis()
        )
    }

    private fun <T> fetchFeatureLayer(
        kind: AviationLayerKind,
        bounds: AviationLayerBounds,
        baseUrl: String,
        where: String,
        outFields: String,
        maxRecords: Int,
        statuses: MutableMap<AviationLayerKind, AviationLayerStatus>,
        parser: (JSONObject) -> List<T>
    ): List<T> {
        return try {
            val url = URL(
                "$baseUrl?f=geojson" +
                    "&where=${encode(where)}" +
                    "&geometry=${encode(bounds.arcGisEnvelope())}" +
                    "&geometryType=esriGeometryEnvelope" +
                    "&inSR=4326" +
                    "&spatialRel=esriSpatialRelIntersects" +
                    "&outFields=${encode(outFields)}" +
                    "&outSR=4326" +
                    "&returnGeometry=true" +
                    "&resultRecordCount=$maxRecords"
            )
            val json = fetchJson(url)
            val parsed = parser(json)
            statuses[kind] = if (parsed.isEmpty()) {
                AviationLayerStatus(AviationLayerState.EMPTY, "No ${kind.displayName.lowercase(Locale.US)} in view")
            } else {
                AviationLayerStatus(AviationLayerState.LOADED, "${parsed.size} ${kind.displayName.lowercase(Locale.US)} loaded")
            }
            parsed
        } catch (_: Exception) {
            statuses[kind] = AviationLayerStatus(AviationLayerState.UNAVAILABLE, "${kind.displayName} unavailable")
            emptyList()
        }
    }

    private fun fetchOceanicTracks(statuses: MutableMap<AviationLayerKind, AviationLayerStatus>): List<AviationOceanicTrack> {
        return try {
            val json = fetchJsonArray(URL(NAT_TRACKS_URL))
            val tracks = parseNatTracks(json)
            statuses[AviationLayerKind.OCEANIC_TRACKS] = if (tracks.isEmpty()) {
                AviationLayerStatus(AviationLayerState.EMPTY, "No drawable NAT track coordinates")
            } else {
                AviationLayerStatus(AviationLayerState.LOADED, "${tracks.size} NAT tracks loaded")
            }
            tracks
        } catch (_: Exception) {
            statuses[AviationLayerKind.OCEANIC_TRACKS] = AviationLayerStatus(AviationLayerState.UNAVAILABLE, "NAT tracks unavailable")
            emptyList()
        }
    }

    private fun parseAirspaceFeatures(json: JSONObject): List<AviationAirspaceFeature> {
        val features = json.optJSONArray("features") ?: JSONArray()
        val parsed = mutableListOf<AviationAirspaceFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val rings = polygonRings(feature.optJSONObject("geometry")).take(MAX_RINGS_PER_FEATURE)
            if (rings.isEmpty()) continue
            val type = properties.optCleanString("TYPE_CODE") ?: properties.optCleanString("LOCAL_TYPE") ?: "Airspace"
            val name = properties.optCleanString("NAME")
                ?: properties.optCleanString("IDENT")
                ?: type
            parsed += AviationAirspaceFeature(
                name = name,
                type = type,
                lowerLimit = altitudeLabel(properties, "LOWER"),
                upperLimit = altitudeLabel(properties, "UPPER"),
                schedule = properties.optCleanString("TIMESOFUSE"),
                rings = rings,
                bounds = rings.flatten().toBounds()
            )
        }
        return parsed
    }

    private fun parseAirportFeatures(json: JSONObject): List<AviationAirportFeature> {
        val features = json.optJSONArray("features") ?: JSONArray()
        val parsed = mutableListOf<AviationAirportFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") != "Point") continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            val lon = coordinates.optDoubleOrNull(0) ?: continue
            val lat = coordinates.optDoubleOrNull(1) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val ident = properties.optCleanString("ICAO_ID")
                ?: properties.optCleanString("IDENT")
                ?: continue
            val name = properties.optCleanString("NAME") ?: ident
            parsed += AviationAirportFeature(
                ident = ident,
                name = name,
                type = properties.optCleanString("TYPE_CODE") ?: "AD",
                military = properties.optCleanString("MIL_CODE")?.contains("MIL", ignoreCase = true) == true,
                lat = lat,
                lon = lon
            )
        }
        return parsed
    }

    private fun parseNatTracks(json: JSONArray): List<AviationOceanicTrack> {
        val tracks = linkedMapOf<String, AviationOceanicTrack>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val source = item.optCleanString("notam_number_formatted") ?: item.optCleanString("icao_id") ?: "FAA NMS"
            val window = listOfNotNull(item.optCleanString("start_datetime"), item.optCleanString("end_datetime"))
                .joinToString(" to ")
                .ifBlank { null }
            val message = item.optCleanString("condition_message") ?: continue
            message.split('\n')
                .map { it.trim().trimEnd('-') }
                .forEach { line ->
                    val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (tokens.size < 3) return@forEach
                    val designator = tokens.first().takeIf { TRACK_DESIGNATOR.matches(it) } ?: return@forEach
                    val points = tokens.drop(1).mapNotNull(::parseNatCoordinate)
                    if (points.size < 2) return@forEach
                    val key = "$source:$designator"
                    tracks[key] = AviationOceanicTrack(
                        name = "NAT $designator",
                        source = source,
                        activeWindow = window,
                        points = points,
                        bounds = points.toBounds()
                    )
                }
        }
        return tracks.values.toList()
    }

    private fun parseNatCoordinate(raw: String): AviationLayerPoint? {
        val token = raw.trim().uppercase(Locale.US).trimEnd(',', '.', ';')
        val match = NAT_COORDINATE.matchEntire(token) ?: return null
        val latDegrees = match.groupValues[1].toDoubleOrNull() ?: return null
        val latMinutes = match.groupValues[2].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.div(60.0) ?: 0.0
        val lonDegrees = match.groupValues[3].toDoubleOrNull() ?: return null
        val lonMinutes = match.groupValues[4].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.div(60.0) ?: 0.0
        val lat = latDegrees + latMinutes
        val lon = -(lonDegrees + lonMinutes)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return AviationLayerPoint(lat, lon)
    }

    private fun polygonRings(geometry: JSONObject?): List<List<AviationLayerPoint>> {
        geometry ?: return emptyList()
        return when (geometry.optString("type")) {
            "Polygon" -> geometry.optJSONArray("coordinates").polygonToRings()
            "MultiPolygon" -> {
                val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
                val rings = mutableListOf<List<AviationLayerPoint>>()
                for (index in 0 until coordinates.length()) {
                    rings += coordinates.optJSONArray(index).polygonToRings()
                }
                rings
            }
            else -> emptyList()
        }
    }

    private fun JSONArray?.polygonToRings(): List<List<AviationLayerPoint>> {
        this ?: return emptyList()
        val rings = mutableListOf<List<AviationLayerPoint>>()
        for (ringIndex in 0 until length()) {
            val ring = optJSONArray(ringIndex) ?: continue
            val points = mutableListOf<AviationLayerPoint>()
            for (pointIndex in 0 until ring.length()) {
                val coordinate = ring.optJSONArray(pointIndex) ?: continue
                val lon = coordinate.optDoubleOrNull(0) ?: continue
                val lat = coordinate.optDoubleOrNull(1) ?: continue
                points += AviationLayerPoint(lat, lon)
            }
            if (points.size >= MIN_RING_POINTS) rings += points
        }
        return rings
    }

    private fun altitudeLabel(properties: JSONObject, prefix: String): String? {
        val value = properties.optDoubleOrNull("${prefix}_VAL") ?: return null
        if (value <= MISSING_ALTITUDE_SENTINEL) return null
        val unit = properties.optCleanString("${prefix}_UOM") ?: "FT"
        return "${value.toInt()} $unit"
    }

    private fun fetchJson(url: URL): JSONObject {
        return JSONObject(fetchText(url))
    }

    private fun fetchJsonArray(url: URL): JSONArray {
        return JSONArray(fetchText(url))
    }

    private fun fetchText(url: URL): String {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS aviation layers are allowed" }
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                throw IllegalStateException("HTTP $code")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val BOUNDARY_AIRSPACE_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Boundary_Airspace/FeatureServer/0/query"
        private const val SPECIAL_USE_AIRSPACE_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/Special_Use_Airspace/FeatureServer/0/query"
        private const val AIRPORT_QUERY_URL =
            "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/arcgis/rest/services/US_Airport/FeatureServer/0/query"
        private const val NAT_TRACKS_URL = "https://nms.aim.faa.gov/datanat/nat.json"
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 9000
        private const val MAX_BOUNDARY_RECORDS = 80
        private const val MAX_SPECIAL_USE_RECORDS = 140
        private const val MAX_AIRPORT_RECORDS = 220
        private const val MAX_RINGS_PER_FEATURE = 8
        private const val MIN_RING_POINTS = 3
        private const val MISSING_ALTITUDE_SENTINEL = -9000.0
        private val TRACK_DESIGNATOR = Regex("^[A-Z]$")
        private val NAT_COORDINATE = Regex("^(\\d{2})(\\d{2})?/(\\d{2,3})(\\d{2})?$")
    }
}

enum class AviationLayerKind(val displayName: String) {
    ATC_BOUNDARIES("ATC boundaries"),
    RESTRICTED_AIRSPACES("Restricted airspaces"),
    AIRPORTS("Airport labels"),
    OCEANIC_TRACKS("Oceanic tracks")
}

enum class AviationLayerState {
    LOADED,
    EMPTY,
    UNAVAILABLE
}

data class AviationLayerStatus(
    val state: AviationLayerState,
    val message: String
)

data class AviationLayerSnapshot(
    val atcBoundaries: List<AviationAirspaceFeature>,
    val restrictedAirspaces: List<AviationAirspaceFeature>,
    val airports: List<AviationAirportFeature>,
    val oceanicTracks: List<AviationOceanicTrack>,
    val statuses: Map<AviationLayerKind, AviationLayerStatus>,
    val fetchedAtMs: Long
)

data class AviationLayerBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    fun arcGisEnvelope(): String = "$minLon,$minLat,$maxLon,$maxLat"
}

data class AviationLayerPoint(val lat: Double, val lon: Double)

data class AviationGeoBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double
) {
    fun intersects(other: AviationGeoBounds): Boolean {
        return maxLat >= other.minLat &&
            minLat <= other.maxLat &&
            maxLon >= other.minLon &&
            minLon <= other.maxLon
    }
}

data class AviationAirspaceFeature(
    val name: String,
    val type: String,
    val lowerLimit: String?,
    val upperLimit: String?,
    val schedule: String?,
    val rings: List<List<AviationLayerPoint>>,
    val bounds: AviationGeoBounds
)

data class AviationAirportFeature(
    val ident: String,
    val name: String,
    val type: String,
    val military: Boolean,
    val lat: Double,
    val lon: Double
)

data class AviationOceanicTrack(
    val name: String,
    val source: String,
    val activeWindow: String?,
    val points: List<AviationLayerPoint>,
    val bounds: AviationGeoBounds
)

private fun List<AviationLayerPoint>.toBounds(): AviationGeoBounds {
    if (isEmpty()) return AviationGeoBounds(0.0, 0.0, 0.0, 0.0)
    var minLat = first().lat
    var maxLat = first().lat
    var minLon = first().lon
    var maxLon = first().lon
    forEach { point ->
        minLat = min(minLat, point.lat)
        maxLat = max(maxLat, point.lat)
        minLon = min(minLon, point.lon)
        maxLon = max(maxLon, point.lon)
    }
    return AviationGeoBounds(minLat, minLon, maxLat, maxLon)
}

private fun JSONObject.optCleanString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() }
}

private fun JSONArray.optDoubleOrNull(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}
