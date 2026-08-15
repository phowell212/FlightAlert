@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles


internal data class MapTileAvailability(
    val loaded: Int,
    val requested: Int,
) {
    companion object {
        val EMPTY = MapTileAvailability(loaded = 0, requested = 0)
    }
}