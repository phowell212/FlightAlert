@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import com.flightalert.map.TileSource


enum class MapTileFailureKind {
    PROVIDER_UNAVAILABLE,
    NETWORK_UNAVAILABLE,
}

data class MapTileFailure(
    val source: TileSource,
    val kind: MapTileFailureKind,
)

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
