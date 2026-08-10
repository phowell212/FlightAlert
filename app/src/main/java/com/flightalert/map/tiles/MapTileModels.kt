@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import java.util.Locale
import com.flightalert.map.TileSource
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.labels.ReferenceScreenRect

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

object MapTileStatusPresenter {
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
}

internal data class MapTileAvailability(
    val loaded: Int,
    val requested: Int,
) {
    companion object {
        val EMPTY = MapTileAvailability(loaded = 0, requested = 0)
    }
}

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

data class MapTileRenderStyle(
    val map_empty: Int,
    val panel_alt: Int,
    val text: Int
)

internal data class TileLayerDrawStats(
    val visible: Int,
    val loaded: Int,
    val requested: Int,
    val fallback_drawn: Int,
    val fading: Boolean
)
