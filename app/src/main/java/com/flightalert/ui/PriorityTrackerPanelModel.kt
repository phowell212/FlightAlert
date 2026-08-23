@file:Suppress("PropertyName")

package com.flightalert.ui

import android.graphics.RectF

internal enum class PriorityTrackerPanelAction {
    CLOSE,
    TOGGLE_TRACKING,
    TOGGLE_RANGE_RING,
    ADJUST_RANGE
}

internal data class PriorityTrackerPanelTarget(
    val bounds: RectF,
    val action: PriorityTrackerPanelAction,
    val adjust_button: PriorityRangeAdjustButton? = null
)

internal data class PriorityTrackerPanelHitResult(
    val action: PriorityTrackerPanelAction,
    val adjust_button: PriorityRangeAdjustButton? = null
)

data class PriorityAircraftPanelRow(
    val title: String,
    val altitude: String,
    val detail: String,
    val is_extreme: Boolean
)

data class PriorityTrackerPanelState(
    val priority_tracking_enabled: Boolean,
    val priority_range_circle_visible: Boolean,
    val alert_distance_label: String,
    val alert_altitude_label: String,
    val aircraft_rows: List<PriorityAircraftPanelRow>,
    val long_press_fill: PriorityRangeButtonFillState? = null
)
