@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import com.flightalert.map.SourcedMapText
import com.flightalert.map.SourcedMapTextPolicy


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
    val map_text: SourcedMapText = map_text_ident_source_field?.let {
        SourcedMapTextPolicy.create(
            primary = ident,
            primarySourceFieldId = it.stable_source_field_id,
        )
    } ?: SourcedMapTextPolicy.create(
        primary = name,
        primarySourceFieldId = AviationMapTextSourceField.AIRPORT_NAME.stable_source_field_id,
    )
}