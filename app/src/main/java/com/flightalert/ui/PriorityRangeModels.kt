@file:Suppress("PropertyName")

package com.flightalert.ui

enum class PriorityRangeAdjustButton {
    DISTANCE_MINUS,
    DISTANCE_PLUS,
    ALTITUDE_MINUS,
    ALTITUDE_PLUS
}

enum class PriorityRangeValue {
    HORIZONTAL,
    VERTICAL
}

data class PriorityRangeButtonFillState(
    val button: PriorityRangeAdjustButton,
    val press_x: Float,
    val press_y: Float,
    val started_ms: Long,
    val duration_ms: Long
)
