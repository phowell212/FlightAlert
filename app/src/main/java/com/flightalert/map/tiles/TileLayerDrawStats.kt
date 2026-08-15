@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles


internal data class TileLayerDrawStats(
    val visible: Int,
    val loaded: Int,
    val requested: Int,
    val fallback_drawn: Int,
    val fading: Boolean
)
