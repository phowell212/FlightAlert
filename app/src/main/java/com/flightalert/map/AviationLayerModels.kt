package com.flightalert.map

import java.util.Collections

enum class AviationLayerAvailability { OFF, LOADING, USABLE, EMPTY, UNAVAILABLE }
enum class AviationLayerCompleteness { COMPLETE, PARTIAL, UNKNOWN }
enum class AviationLayerFreshness { CURRENT, STALE }

data class AviationLayerHealth(
    val availability: AviationLayerAvailability,
    val completeness: AviationLayerCompleteness,
    val freshness: AviationLayerFreshness
) {
    init {
        require(
            availability != AviationLayerAvailability.EMPTY ||
                completeness == AviationLayerCompleteness.COMPLETE
        ) { "An empty aviation layer must be complete" }
    }

    val is_usable: Boolean
        get() = availability == AviationLayerAvailability.USABLE
    val can_claim_complete: Boolean
        get() = completeness == AviationLayerCompleteness.COMPLETE &&
            (availability == AviationLayerAvailability.USABLE ||
                availability == AviationLayerAvailability.EMPTY)
    val must_label_stale: Boolean
        get() = freshness == AviationLayerFreshness.STALE
}

class AviationSourceIdentity(
    val provider: String,
    val service: String,
    val layer: String,
    requested_envelopes: List<AviationGeoBounds>,
    val object_ids_captured_at_epoch_ms: Long?,
    val response_observed_at_epoch_ms: Long,
    val final_source_url: String,
    val advertised_revision: Long?
) {
    val requested_envelopes: List<AviationGeoBounds> = immutable_list(requested_envelopes)
    val request_url: String = final_source_url
    val observed_at_epoch_ms: Long = response_observed_at_epoch_ms
    val source_revision: Long? = advertised_revision
}

data class AviationLayerPoint(val lat: Double, val lon: Double)

data class AviationGeoBounds(
    val min_lat: Double,
    val min_lon: Double,
    val max_lat: Double,
    val max_lon: Double
) {
    fun intersects(other: AviationGeoBounds): Boolean {
        return max_lat >= other.min_lat &&
            min_lat <= other.max_lat &&
            max_lon >= other.min_lon &&
            min_lon <= other.max_lon
    }
}

data class AviationPolygon(
    val shell: List<AviationLayerPoint>,
    val holes: List<List<AviationLayerPoint>>
) {
    init {
        require(shell.size >= 4 && shell.first() == shell.last()) {
            "A polygon shell requires a closed four-point ring"
        }
        require(holes.all { it.size >= 4 && it.first() == it.last() }) {
            "A polygon hole requires a closed four-point ring"
        }
    }

    val all_rings: List<List<AviationLayerPoint>> = listOf(shell) + holes
}

data class AviationMultiPolygon(val polygons: List<AviationPolygon>) {
    init {
        require(polygons.isNotEmpty()) { "A multipolygon requires at least one polygon" }
    }

    val all_rings: List<List<AviationLayerPoint>> =
        polygons.flatMap(AviationPolygon::all_rings)
}

data class AviationAirspaceFeature(
    val object_id: Long,
    val name: String,
    val type: String,
    val lower_limit: String?,
    val upper_limit: String?,
    val schedule: String?,
    val city: String?,
    val state: String?,
    val geometry: AviationMultiPolygon,
    val bounds: AviationGeoBounds,
    internal val map_text_source_field: AviationMapTextSourceField =
        AviationMapTextSourceField.AIRSPACE_NAME,
) {
    val rings: List<List<AviationLayerPoint>> = geometry.all_rings
    val map_text: SourcedMapText = AviationMapTextAdapter.airspace(this).sourced_text
}

data class AviationAirportFeature(
    val object_id: Long,
    val ident: String,
    val name: String,
    val type: String,
    val military_code: String,
    val lat: Double,
    val lon: Double,
    internal val map_text_ident_source_field: AviationMapTextSourceField? =
        if (ident.isNotEmpty()) AviationMapTextSourceField.AIRPORT_IDENT else null,
) {
    val military: Boolean = military_code.equals("MIL", ignoreCase = true)
    val map_text: SourcedMapText = AviationMapTextAdapter.airport(this).sourced_text
}

sealed interface AviationNatWaypoint {
    val source_token: String

    data class Coordinate(
        override val source_token: String,
        val point: AviationLayerPoint
    ) : AviationNatWaypoint

    data class NamedFix(override val source_token: String) : AviationNatWaypoint
    data class Unrecognized(override val source_token: String) : AviationNatWaypoint
}

enum class AviationNatTemporalState { ACTIVE, UPCOMING, EXPIRED, UNKNOWN }

class AviationOceanicTrack(
    val designator: String,
    val source_notam: String?,
    val icao_id: String?,
    val part_number: Int?,
    val declared_part_number: Int?,
    val declared_part_count: Int?,
    val start_datetime: String?,
    val end_datetime: String?,
    val entry_datetime: String?,
    val last_updated: String?,
    val raw_condition_message: String,
    val raw_track_line: String,
    val start_epoch_ms: Long?,
    val end_epoch_ms: Long?,
    val temporal_state: AviationNatTemporalState,
    waypoints: List<AviationNatWaypoint>,
    drawable_segments: List<List<AviationLayerPoint>>
) {
    val name: String = "NAT $designator"
    val map_text: SourcedMapText = AviationMapTextAdapter.oceanic_track(this).sourced_text
    val source: String = source_notam ?: icao_id ?: "FAA NMS"
    val active_window: String? = listOfNotNull(start_datetime, end_datetime)
        .joinToString(" to ")
        .ifBlank { null }
    val waypoints: List<AviationNatWaypoint> = immutable_list(waypoints)
    val drawable_segments: List<List<AviationLayerPoint>> = immutable_nested_list(drawable_segments)
    val bounds: AviationGeoBounds? = this.drawable_segments.to_bounds_or_null()
}

class AviationParsedBatch<T>(
    records: List<T>,
    returned_object_ids: Set<Long>,
    invalid_object_ids: Set<Long>
) {
    val records: List<T> = immutable_list(records)
    val returned_object_ids: Set<Long> = immutable_set(returned_object_ids)
    val invalid_object_ids: Set<Long> = immutable_set(invalid_object_ids)

    fun copy(
        records: List<T> = this.records,
        returned_object_ids: Set<Long> = this.returned_object_ids,
        invalid_object_ids: Set<Long> = this.invalid_object_ids
    ): AviationParsedBatch<T> = AviationParsedBatch(
        records,
        returned_object_ids,
        invalid_object_ids
    )
}

class AviationSourceResult<T>(
    records: List<T>,
    expected_object_ids: Set<Long>?,
    returned_object_ids: Set<Long>,
    invalid_object_ids: Set<Long>,
    val identity: AviationSourceIdentity,
    val health: AviationLayerHealth,
    val message: String
) {
    val records: List<T> = immutable_list(records)
    val expected_object_ids: Set<Long>? = expected_object_ids?.let(::immutable_set)
    val returned_object_ids: Set<Long> = immutable_set(returned_object_ids)
    val invalid_object_ids: Set<Long> = immutable_set(invalid_object_ids)
}

private fun <T> immutable_list(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <T> immutable_set(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(LinkedHashSet(values))

private fun <T> immutable_nested_list(values: Collection<Collection<T>>): List<List<T>> =
    immutable_list(values.map(::immutable_list))

private fun Collection<Collection<AviationLayerPoint>>.to_bounds_or_null(): AviationGeoBounds? {
    var first_point: AviationLayerPoint? = null
    var min_lat = 0.0
    var min_lon = 0.0
    var max_lat = 0.0
    var max_lon = 0.0
    for (segment in this) {
        for (point in segment) {
            if (first_point == null) {
                first_point = point
                min_lat = point.lat
                min_lon = point.lon
                max_lat = point.lat
                max_lon = point.lon
            } else {
                min_lat = minOf(min_lat, point.lat)
                min_lon = minOf(min_lon, point.lon)
                max_lat = maxOf(max_lat, point.lat)
                max_lon = maxOf(max_lon, point.lon)
            }
        }
    }
    if (first_point == null) return null
    return AviationGeoBounds(min_lat, min_lon, max_lat, max_lon)
}
