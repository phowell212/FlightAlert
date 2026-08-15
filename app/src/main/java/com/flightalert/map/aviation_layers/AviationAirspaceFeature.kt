@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.map.SourcedMapText
import com.flightalert.map.SourcedMapTextPolicy


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
    val map_text: SourcedMapText = SourcedMapTextPolicy.create(
        primary = name,
        primarySourceFieldId = map_text_source_field.stable_source_field_id,
    )
}