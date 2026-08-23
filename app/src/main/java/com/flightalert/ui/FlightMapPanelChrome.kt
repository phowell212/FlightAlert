@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.ui

import android.graphics.Canvas
import android.graphics.RectF


interface FlightMapPanelChrome {
    // Keep drawing, layout, and invalidation behind the host boundary.
    val layout: FlightMapLayout

    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun control_radius(): Float
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
    fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean)
    fun draw_control_surface(canvas: Canvas, rect: RectF, fill: Int, stroke: Int, selected: Boolean)
    fun draw_wrapped_text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        max_lines: Int
    ): Float

    fun request_animation_frame()
}
