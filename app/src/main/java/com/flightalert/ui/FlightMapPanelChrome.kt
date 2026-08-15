@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.graphics.withSave
import java.util.Locale
import kotlin.math.sqrt
import com.flightalert.ThemeTreatment
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.draw_fitted_text
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.aviation_layers.AviationLayerKind
import com.flightalert.map.aviation_layers.AviationLayerState
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter
import com.flightalert.with_alpha



interface FlightMapPanelChrome {
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
