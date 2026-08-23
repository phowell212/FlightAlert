@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.map.SourcedMapText
import com.flightalert.map.SourcedMapTextPolicy

enum class AviationNatTemporalState { ACTIVE, UPCOMING, EXPIRED, UNKNOWN }

sealed interface AviationNatWaypoint {
    val source_token: String

    data class Coordinate(
        override val source_token: String,
        val point: AviationLayerPoint
    ) : AviationNatWaypoint

    data class NamedFix(override val source_token: String) : AviationNatWaypoint
    data class Unrecognized(override val source_token: String) : AviationNatWaypoint
}

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
    val raw_track_line: String,
    val start_epoch_ms: Long?,
    val end_epoch_ms: Long?,
    val temporal_state: AviationNatTemporalState,
    waypoints: List<AviationNatWaypoint>,
    drawable_segments: List<List<AviationLayerPoint>>
) {
    val name: String = "NAT $designator"
    val map_text: SourcedMapText = SourcedMapTextPolicy.create(
        primary = designator,
        primarySourceFieldId = AviationMapTextSourceField.NAT_DESIGNATOR.stable_source_field_id,
    )
    val source: String = source_notam ?: icao_id ?: "FAA NMS"
    val waypoints: List<AviationNatWaypoint> = immutable_list(waypoints)
    val drawable_segments: List<List<AviationLayerPoint>> = immutable_nested_list(drawable_segments)
    val bounds: AviationGeoBounds? = this.drawable_segments.to_bounds_or_null()
}
