@file:Suppress(
    "FunctionName",
    "PropertyName",
)

package com.flightalert.ui

import com.flightalert.map.aviation_layers.AviationLayerSnapshot

enum class ReferencePanelKey {
    NEXT,
    PREVIOUS,
    ACTIVATE,
    BACK,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

internal const val REFERENCE_FILTER_ACTION_BACK = "panel.back"
internal const val REFERENCE_FILTER_ACTION_TRAFFIC_TAB = "tabs.traffic"
internal const val REFERENCE_FILTER_ACTION_MAP_TAB = "tabs.map"
internal const val REFERENCE_FILTER_ACTION_LABELS_MASTER = "master.labels"
internal const val REFERENCE_FILTER_ACTION_OUTLINES_MASTER = "master.outlines"

internal fun reference_filter_action(stableFilterId: String): String = "filter.$stableFilterId"

sealed interface ReferencePanelIntent {
    data object Dismiss : ReferencePanelIntent
    data class SelectTab(val tab: ReferenceFiltersTab) : ReferencePanelIntent
    data class ToggleMaster(val section: ReferenceFilterSection) : ReferencePanelIntent
    data class ToggleFilter(val stableFilterId: String) : ReferencePanelIntent
    data class MoveFocus(val actionId: String) : ReferencePanelIntent
    data class SetScrollOffset(val offsetDp: Float) : ReferencePanelIntent
}

data class MapLabelsPanelState(
    val street_labels_enabled: Boolean,
    val borders_enabled: Boolean,
    val label_text_scale: Float
)

data class AviationLayersPanelState(
    val status_text: String,
    val snapshot: AviationLayerSnapshot?,
    val fetch_in_flight: Boolean,
    val atc_boundaries_enabled: Boolean,
    val restricted_airspaces_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)

data class ImpactMethodologyPanelState(
    val source_labels: List<String>
)
