package com.flightalert.data.api

import com.flightalert.data.AviationAirportFeature
import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationLayerBounds
import com.flightalert.data.AviationLayerKind
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.AviationLayerState
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationLayerStatus
import com.flightalert.data.AviationOceanicTrack
import com.flightalert.data.to_bounds
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class AviationLayerClient(private val user_agent: String) {

    fun fetch_layers(
        bounds: AviationLayerBounds,
        include_atc_boundaries: Boolean,
        include_restricted_airspaces: Boolean,
        include_airports: Boolean,
        include_oceanic_tracks: Boolean
    ): AviationLayerSnapshot {
        val statuses = mutableMapOf<AviationLayerKind, AviationLayerStatus>()
        val atc = if (include_atc_boundaries) {
            fetch_feature_layer(
                kind = AviationLayerKind.ATC_BOUNDARIES,
                bounds = bounds,
                base_url = BOUNDARY_AIRSPACE_QUERY_URL,
                where = "TYPE_CODE IN ('ARTCC','FIR','OCA')",
                out_fields = "NAME,IDENT,TYPE_CODE,LOCAL_TYPE,LEVEL_,UPPER_VAL,LOWER_VAL",
                max_records = MAX_BOUNDARY_RECORDS,
                statuses = statuses,
                parser = ::parse_airspace_features
            )
        } else {
            emptyList()
        }
        val restricted = if (include_restricted_airspaces) {
            fetch_feature_layer(
                kind = AviationLayerKind.RESTRICTED_AIRSPACES,
                bounds = bounds,
                base_url = SPECIAL_USE_AIRSPACE_QUERY_URL,
                where = "1=1",
                out_fields = "NAME,TYPE_CODE,UPPER_VAL,LOWER_VAL,UPPER_UOM,LOWER_UOM,TIMESOFUSE,CITY,STATE",
                max_records = MAX_SPECIAL_USE_RECORDS,
                statuses = statuses,
                parser = ::parse_airspace_features
            )
        } else {
            emptyList()
        }
        val airports = if (include_airports) {
            fetch_feature_layer(
                kind = AviationLayerKind.AIRPORTS,
                bounds = bounds,
                base_url = AIRPORT_QUERY_URL,
                where = "OPERSTATUS = 'OPERATIONAL'",
                out_fields = "IDENT,NAME,ICAO_ID,TYPE_CODE,MIL_CODE,SERVCITY,STATE,COUNTRY",
                max_records = MAX_AIRPORT_RECORDS,
                statuses = statuses,
                parser = ::parse_airport_features
            )
        } else {
            emptyList()
        }
        val oceanic = if (include_oceanic_tracks) {
            fetch_oceanic_tracks(statuses)
        } else {
            emptyList()
        }

        return AviationLayerSnapshot(
            atc_boundaries = atc,
            restricted_airspaces = restricted,
            airports = airports,
            oceanic_tracks = oceanic,
            statuses = statuses,
            fetched_at_ms = System.currentTimeMillis()
        )
    }

    private fun <T> fetch_feature_layer(
        kind: AviationLayerKind,
        bounds: AviationLayerBounds,
        base_url: String,
        where: String,
        out_fields: String,
        max_records: Int,
        statuses: MutableMap<AviationLayerKind, AviationLayerStatus>,
        parser: (JSONObject) -> List<T>
    ): List<T> {
        return try {
            val url = URL(
                "$base_url?f=geojson" +
                    "&where=${encode(where)}" +
                    "&geometry=${encode(bounds.arc_gis_envelope())}" +
                    "&geometryType=esriGeometryEnvelope" +
                    "&inSR=4326" +
                    "&spatialRel=esriSpatialRelIntersects" +
                    "&out_fields=${encode(out_fields)}" +
                    "&outSR=4326" +
                    "&returnGeometry=true" +
                    "&resultRecordCount=$max_records"
            )
            val json = fetch_json(url)
            val parsed = parser(json)
            statuses[kind] = if (parsed.isEmpty()) {
                AviationLayerStatus(AviationLayerState.EMPTY, "No ${kind.display_name.lowercase(Locale.US)} in view")
            } else {
                AviationLayerStatus(AviationLayerState.LOADED, "${parsed.size} ${kind.display_name.lowercase(Locale.US)} loaded")
            }
            parsed
        } catch (_: Exception) {
            statuses[kind] = AviationLayerStatus(AviationLayerState.UNAVAILABLE, "${kind.display_name} unavailable")
            emptyList()
        }
    }

    private fun fetch_oceanic_tracks(statuses: MutableMap<AviationLayerKind, AviationLayerStatus>): List<AviationOceanicTrack> {
        return try {
            val json = fetch_json_array(URL(NAT_TRACKS_URL))
            val tracks = parse_nat_tracks(json)
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

    private fun parse_airspace_features(json: JSONObject): List<AviationAirspaceFeature> {
        val features = json.optJSONArray("features") ?: JSONArray()
        val parsed = mutableListOf<AviationAirspaceFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val rings = polygon_rings(feature.optJSONObject("geometry")).take(MAX_RINGS_PER_FEATURE)
            if (rings.isEmpty()) continue
            val type = properties.opt_clean_string("TYPE_CODE") ?: properties.opt_clean_string("LOCAL_TYPE") ?: "Airspace"
            val name = properties.opt_clean_string("NAME")
                ?: properties.opt_clean_string("IDENT")
                ?: type
            parsed += AviationAirspaceFeature(
                name = name,
                type = type,
                lower_limit = altitude_label(properties, "LOWER"),
                upper_limit = altitude_label(properties, "UPPER"),
                schedule = properties.opt_clean_string("TIMESOFUSE"),
                rings = rings,
                bounds = rings.flatten().to_bounds()
            )
        }
        return parsed
    }

    private fun parse_airport_features(json: JSONObject): List<AviationAirportFeature> {
        val features = json.optJSONArray("features") ?: JSONArray()
        val parsed = mutableListOf<AviationAirportFeature>()
        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val geometry = feature.optJSONObject("geometry") ?: continue
            if (geometry.optString("type") != "Point") continue
            val coordinates = geometry.optJSONArray("coordinates") ?: continue
            val lon = coordinates.opt_double_or_null(0) ?: continue
            val lat = coordinates.opt_double_or_null(1) ?: continue
            val properties = feature.optJSONObject("properties") ?: JSONObject()
            val ident = properties.opt_clean_string("ICAO_ID")
                ?: properties.opt_clean_string("IDENT")
                ?: continue
            val name = properties.opt_clean_string("NAME") ?: ident
            parsed += AviationAirportFeature(
                ident = ident,
                name = name,
                type = properties.opt_clean_string("TYPE_CODE") ?: "AD",
                military = properties.opt_clean_string("MIL_CODE")?.contains("MIL", ignoreCase = true) == true,
                lat = lat,
                lon = lon
            )
        }
        return parsed
    }

    private fun parse_nat_tracks(json: JSONArray): List<AviationOceanicTrack> {
        val tracks = linkedMapOf<String, AviationOceanicTrack>()
        for (index in 0 until json.length()) {
            val item = json.optJSONObject(index) ?: continue
            val source = item.opt_clean_string("notam_number_formatted") ?: item.opt_clean_string("icao_id") ?: "FAA NMS"
            val window = listOfNotNull(item.opt_clean_string("start_datetime"), item.opt_clean_string("end_datetime"))
                .joinToString(" to ")
                .ifBlank { null }
            val message = item.opt_clean_string("condition_message") ?: continue
            message.split('\n')
                .map { it.trim().trimEnd('-') }
                .forEach { line ->
                    val tokens = line.split(Regex("\\s+")).filter { it.isNotBlank() }
                    if (tokens.size < 3) return@forEach
                    val designator = tokens.first().takeIf { TRACK_DESIGNATOR.matches(it) } ?: return@forEach
                    val points = tokens.drop(1).mapNotNull(::parse_nat_coordinate)
                    if (points.size < 2) return@forEach
                    val key = "$source:$designator"
                    tracks[key] = AviationOceanicTrack(
                        name = "NAT $designator",
                        source = source,
                        active_window = window,
                        points = points,
                        bounds = points.to_bounds()
                    )
                }
        }
        return tracks.values.toList()
    }

    private fun parse_nat_coordinate(raw: String): AviationLayerPoint? {
        val token = raw.trim().uppercase(Locale.US).trimEnd(',', '.', ';')
        val match = NAT_COORDINATE.matchEntire(token) ?: return null
        val lat_degrees = match.groupValues[1].toDoubleOrNull() ?: return null
        val lat_minutes = match.groupValues[2].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.div(60.0) ?: 0.0
        val lon_degrees = match.groupValues[3].toDoubleOrNull() ?: return null
        val lon_minutes = match.groupValues[4].takeIf { it.isNotBlank() }?.toDoubleOrNull()?.div(60.0) ?: 0.0
        val lat = lat_degrees + lat_minutes
        val lon = -(lon_degrees + lon_minutes)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return AviationLayerPoint(lat, lon)
    }

    private fun polygon_rings(geometry: JSONObject?): List<List<AviationLayerPoint>> {
        geometry ?: return emptyList()
        return when (geometry.optString("type")) {
            "Polygon" -> geometry.optJSONArray("coordinates").polygon_to_rings()
            "MultiPolygon" -> {
                val coordinates = geometry.optJSONArray("coordinates") ?: return emptyList()
                val rings = mutableListOf<List<AviationLayerPoint>>()
                for (index in 0 until coordinates.length()) {
                    rings += coordinates.optJSONArray(index).polygon_to_rings()
                }
                rings
            }
            else -> emptyList()
        }
    }

    private fun JSONArray?.polygon_to_rings(): List<List<AviationLayerPoint>> {
        this ?: return emptyList()
        val rings = mutableListOf<List<AviationLayerPoint>>()
        for (ring_index in 0 until length()) {
            val ring = optJSONArray(ring_index) ?: continue
            val points = mutableListOf<AviationLayerPoint>()
            for (point_index in 0 until ring.length()) {
                val coordinate = ring.optJSONArray(point_index) ?: continue
                val lon = coordinate.opt_double_or_null(0) ?: continue
                val lat = coordinate.opt_double_or_null(1) ?: continue
                points += AviationLayerPoint(lat, lon)
            }
            if (points.size >= MIN_RING_POINTS) rings += points
        }
        return rings
    }

    private fun altitude_label(properties: JSONObject, prefix: String): String? {
        val value = properties.opt_double_or_null("${prefix}_VAL") ?: return null
        if (value <= MISSING_ALTITUDE_SENTINEL) return null
        val unit = properties.opt_clean_string("${prefix}_UOM") ?: return null
        return "${value.toInt()} $unit"
    }

    private fun fetch_json(url: URL): JSONObject {
        return JSONObject(fetch_text(url))
    }

    private fun fetch_json_array(url: URL): JSONArray {
        return JSONArray(fetch_text(url))
    }

    private fun fetch_text(url: URL): String {
        require(url.protocol.equals("https", ignoreCase = true)) { "Only HTTPS aviation layers are allowed" }
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", user_agent)
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

private fun JSONObject.opt_clean_string(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).trim().takeIf { it.isNotBlank() }
}

private fun JSONArray.opt_double_or_null(index: Int): Double? {
    if (index >= length() || isNull(index)) return null
    return optDouble(index)
}

private fun JSONObject.opt_double_or_null(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return when (val raw = opt(key)) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
}
