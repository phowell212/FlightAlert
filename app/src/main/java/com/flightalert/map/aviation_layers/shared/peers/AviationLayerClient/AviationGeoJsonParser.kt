@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.map.SourcedMapTextException
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

object AviationGeoJsonParser {
    fun parse_airspaces(json: JSONObject): AviationParsedBatch<AviationAirspaceFeature> {
        val records = mutableListOf<AviationAirspaceFeature>()
        val returned_object_ids = linkedSetOf<Long>()
        val invalid_object_ids = linkedSetOf<Long>()
        val features = json.optJSONArray("features") ?: JSONArray()

        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val object_id = properties.object_id_or_null() ?: continue
            val record = try {
                parse_airspace_feature(feature, properties, object_id)
            } catch (_: SourcedMapTextException) {
                null
            }
            if (record == null) {
                invalid_object_ids += object_id
            } else {
                records += record
                returned_object_ids += object_id
            }
        }

        return AviationParsedBatch(records, returned_object_ids, invalid_object_ids)
    }

    fun parse_airports(json: JSONObject): AviationParsedBatch<AviationAirportFeature> {
        val records = mutableListOf<AviationAirportFeature>()
        val returned_object_ids = linkedSetOf<Long>()
        val invalid_object_ids = linkedSetOf<Long>()
        val features = json.optJSONArray("features") ?: JSONArray()

        for (index in 0 until features.length()) {
            val feature = features.optJSONObject(index) ?: continue
            val properties = feature.optJSONObject("properties") ?: continue
            val object_id = properties.object_id_or_null() ?: continue
            val record = try {
                parse_airport_feature(feature, properties, object_id)
            } catch (_: SourcedMapTextException) {
                null
            }
            if (record == null) {
                invalid_object_ids += object_id
            } else {
                records += record
                returned_object_ids += object_id
            }
        }

        return AviationParsedBatch(records, returned_object_ids, invalid_object_ids)
    }

    private fun parse_airspace_feature(
        feature: JSONObject,
        properties: JSONObject,
        object_id: Long
    ): AviationAirspaceFeature? {
        val geometry = parse_airspace_geometry(feature.optJSONObject("geometry")) ?: return null
        val type = properties.clean_string("TYPE_CODE")
            ?: properties.clean_string("LOCAL_TYPE")
            ?: return null
        val source_name = properties.source_text("NAME")
        val source_ident = properties.source_text("IDENT")
        val name = source_name ?: source_ident ?: return null
        return AviationAirspaceFeature(
            object_id = object_id,
            name = name,
            type = type,
            lower_limit = altitude_label(properties, "LOWER"),
            upper_limit = altitude_label(properties, "UPPER"),
            schedule = properties.clean_string("TIMESOFUSE"),
            city = properties.clean_string("CITY"),
            state = properties.clean_string("STATE"),
            geometry = geometry,
            bounds = geometry.all_rings.flatten().to_bounds(),
            map_text_source_field = if (source_name != null) {
                AviationMapTextSourceField.AIRSPACE_NAME
            } else {
                AviationMapTextSourceField.AIRSPACE_IDENT
            },
        )
    }

    private fun parse_airport_feature(
        feature: JSONObject,
        properties: JSONObject,
        object_id: Long
    ): AviationAirportFeature? {
        val geometry = feature.optJSONObject("geometry") ?: return null
        if (geometry.optString("type") != "Point") return null
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        val lon = coordinates.coordinate_number_or_null(0)?.takeIf { it in -180.0..180.0 }
            ?: return null
        val lat = coordinates.coordinate_number_or_null(1)?.takeIf { it in -90.0..90.0 }
            ?: return null
        val icao_id = properties.source_text("ICAO_ID")
        val faa_ident = properties.source_text("IDENT")
        val source_name = properties.source_text("NAME")
        val ident = icao_id ?: faa_ident ?: ""
        val name = source_name ?: ident.takeIf(String::isNotEmpty) ?: return null
        val type = properties.clean_string("TYPE_CODE") ?: return null
        val military_code = properties.clean_string("MIL_CODE") ?: return null
        return AviationAirportFeature(
            object_id = object_id,
            ident = ident,
            name = name,
            type = type,
            military_code = military_code,
            lat = lat,
            lon = lon,
            map_text_ident_source_field = when {
                icao_id != null -> AviationMapTextSourceField.AIRPORT_ICAO_ID
                faa_ident != null -> AviationMapTextSourceField.AIRPORT_IDENT
                else -> null
            },
        )
    }

    private fun parse_airspace_geometry(geometry: JSONObject?): AviationMultiPolygon? {
        geometry ?: return null
        val coordinates = geometry.optJSONArray("coordinates") ?: return null
        val polygons = when (geometry.optString("type")) {
            "Polygon" -> listOf(parse_polygon(coordinates) ?: return null)
            "MultiPolygon" -> {
                if (coordinates.length() == 0) return null
                buildList {
                    for (index in 0 until coordinates.length()) {
                        val polygon_coordinates = coordinates.optJSONArray(index) ?: return null
                        add(parse_polygon(polygon_coordinates) ?: return null)
                    }
                }
            }

            else -> return null
        }
        return AviationMultiPolygon(polygons)
    }

    private fun parse_polygon(coordinates: JSONArray): AviationPolygon? {
        if (coordinates.length() == 0) return null
        val shell = parse_ring(coordinates.optJSONArray(0)) ?: return null
        val holes = buildList {
            for (index in 1 until coordinates.length()) {
                add(parse_ring(coordinates.optJSONArray(index)) ?: return null)
            }
        }
        return AviationPolygon(shell, holes)
    }

    private fun parse_ring(coordinates: JSONArray?): List<AviationLayerPoint>? {
        coordinates ?: return null
        if (coordinates.length() < MIN_RING_POINTS) return null
        val points = buildList {
            for (index in 0 until coordinates.length()) {
                val coordinate = coordinates.optJSONArray(index) ?: return null
                val lon = coordinate.coordinate_number_or_null(0)
                    ?.takeIf { it in -180.0..180.0 }
                    ?: return null
                val lat = coordinate.coordinate_number_or_null(1)
                    ?.takeIf { it in -90.0..90.0 }
                    ?: return null
                add(AviationLayerPoint(lat, lon))
            }
        }
        return points.takeIf { it.first() == it.last() }
    }

    private fun JSONObject.object_id_or_null(): Long? {
        if (!has("OBJECTID") || isNull("OBJECTID")) return null
        return opt("OBJECTID").exact_aviation_object_id_or_null()
    }

    private fun JSONObject.clean_string(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return (opt(key) as? String)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun JSONObject.source_text(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return opt(key) as? String
    }

    private fun JSONObject.number_or_null(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }?.takeIf(Double::isFinite)
    }

    private fun JSONArray.coordinate_number_or_null(index: Int): Double? {
        if (index >= length() || isNull(index)) return null
        return (opt(index) as? Number)?.toDouble()?.takeIf(Double::isFinite)
    }

    private fun altitude_label(properties: JSONObject, prefix: String): String? {
        val code = properties.clean_string("${prefix}_CODE")
        val value = properties.number_or_null("${prefix}_VAL")
            ?.takeIf { it > MISSING_ALTITUDE_SENTINEL }
        val unit = properties.clean_string("${prefix}_UOM")

        if (code.equals(UNLIMITED_ALTITUDE_CODE, ignoreCase = true)) return code
        if (code.equals(SURFACE_ALTITUDE_CODE, ignoreCase = true) && value == 0.0) return code
        if (value == null) return code?.let { "$it (value unavailable)" }

        val value_label = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return when {
            unit == null && code == null -> "$value_label (unit/reference unavailable)"
            unit == null -> "$value_label $code (unit unavailable)"
            code == null -> "$value_label $unit (reference unavailable)"
            else -> "$value_label $unit $code"
        }
    }

    private const val MIN_RING_POINTS = 4
    private const val MISSING_ALTITUDE_SENTINEL = -9000.0
    private const val SURFACE_ALTITUDE_CODE = "SFC"
    private const val UNLIMITED_ALTITUDE_CODE = "UNLTD"
}