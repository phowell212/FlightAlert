@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.details

import android.graphics.Canvas
import android.graphics.RectF


interface AircraftDetailsPanelChrome {
    // Give details rendering only the canvas and sizing calls it needs.
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun control_radius(): Float
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
    fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean)
}
