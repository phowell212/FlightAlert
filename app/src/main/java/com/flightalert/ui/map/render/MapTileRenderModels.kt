package com.flightalert.ui.map.render

import com.flightalert.ui.map.ReferenceTileOverlay
import com.flightalert.ui.map.TileSource

data class MapTileRenderState(
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val user_agent: String,
    val interaction_active: Boolean = false
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
    val reference_overlay_layers: List<ReferenceTileOverlay> =
        map_source.reference_overlay_layers(map_labels_enabled, map_borders_enabled)
}

data class MapTileRenderStyle(
    val map_empty: Int,
    val panel_alt: Int,
    val text: Int
)
