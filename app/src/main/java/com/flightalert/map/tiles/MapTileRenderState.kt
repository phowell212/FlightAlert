@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import com.flightalert.map.TileSource
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.labels.ReferenceScreenRect


data class MapTileRenderStyle(
    val map_empty: Int,
    val panel_alt: Int,
    val text: Int
)

data class MapTileRenderState(
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val map_label_text_scale: Float,
    val place_labels_enabled: Boolean,
    val water_labels_enabled: Boolean,
    val region_labels_enabled: Boolean,
    val public_lands_enabled: Boolean,
    val map_label_transition_alpha: Float = 1f,
    val user_agent: String,
    val interaction_active: Boolean = false,
    val reference_interaction_active: Boolean = false,
    val reference_filter_state: FilterState = FilterState.defaults(),
    val reference_label_avoid_rects: List<ReferenceScreenRect> = emptyList()
) {
    val cache_key: String = map_source.cache_key(map_labels_enabled)
}
