@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import com.flightalert.map.TileSource
import java.util.Locale


fun text(state: MapTileLoadState): String = when (state) {
    MapTileLoadState.WAITING -> "Waiting for location"
    is MapTileLoadState.LOADING -> {
        val label_note = if (state.source == TileSource.STREET && !state.labels_enabled) {
            " no-label"
        } else {
            ""
        }
        "Loading ${state.source.display_name.lowercase(Locale.US)}$label_note tiles"
    }
    is MapTileLoadState.READY -> {
        val label_note = if (state.source == TileSource.STREET && !state.labels_enabled) {
            " no-label"
        } else {
            ""
        }
        "${state.source.display_name}$label_note loaded"
    }
    is MapTileLoadState.UNAVAILABLE -> when (state.failure.kind) {
        MapTileFailureKind.PROVIDER_UNAVAILABLE -> "Map tiles unavailable"
        MapTileFailureKind.NETWORK_UNAVAILABLE -> "Map network unavailable"
    }
}
