@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles


internal data class SatelliteLodState(
    val lower_tile_zoom: Int,
    val upper_tile_zoom: Int,
    val has_upper_lod: Boolean,
    val upper_lod_alpha: Float,
    val prefetch_upper_lod: Boolean,
    val blend_active: Boolean
)