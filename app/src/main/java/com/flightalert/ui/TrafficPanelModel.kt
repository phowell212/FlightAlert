@file:Suppress("PropertyName")

package com.flightalert.ui

import com.flightalert.VisualTheme

sealed interface TrafficPanelContent

data class TrafficPanelEmptyState(
    val headline: String,
    val message: String,
    val data_time_label: String?
) : TrafficPanelContent

data class TrafficPanelAircraftState(
    val callsign: String,
    val model_label: String,
    val distance_label: String,
    val distance_color: Int,
    val wide_rows: List<TrafficPanelRow>,
    val compact_rows: List<TrafficPanelRow>
) : TrafficPanelContent

data class TrafficPanelRow(val label: String, val value: String)

data class TrafficPanelStyle(val visual_theme: VisualTheme)

data class TrafficPanelState(
    val title: String,
    val title_color: Int,
    val content: TrafficPanelContent
)
