@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles


internal data class TileLayerDrawStats(
    val loaded: Int,
    val requested: Int,
    val fading: Boolean
)
