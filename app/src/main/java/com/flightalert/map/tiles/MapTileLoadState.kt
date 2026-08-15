@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import com.flightalert.map.TileSource


sealed interface MapTileLoadState {
    object WAITING : MapTileLoadState
    data class LOADING(
        val source: TileSource,
        val labels_enabled: Boolean,
    ) : MapTileLoadState

    data class READY(
        val source: TileSource,
        val labels_enabled: Boolean,
    ) : MapTileLoadState

    data class UNAVAILABLE(val failure: MapTileFailure) : MapTileLoadState
}