@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.ui

import android.graphics.Canvas
import android.graphics.RectF


interface TrafficPanelChrome {
    // Give the traffic panel only canvas and sizing calls.
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
}
