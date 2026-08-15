@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import java.util.LinkedHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import com.flightalert.ThemeTreatment
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.details.AircraftDetailsPanelChrome
import com.flightalert.draw_fitted_text
import com.flightalert.map.ScaleLabel
import com.flightalert.traffic.TrafficOverlayChrome
import com.flightalert.with_alpha

class FlightMapChromeBridge(
    override val layout: FlightMapLayout,
    private val dp_value: FloatScale,
    private val sp_value: FloatScale,
    private val fit_text: (String, Float) -> String,
    private val control_radius_value: () -> Float,
    private val panel_surface: (Canvas, RectF, Int, Int) -> Unit,
    private val choice_button: (Canvas, RectF, String, Boolean) -> Unit,
    private val control_surface: (Canvas, RectF, Int, Int, Boolean) -> Unit,
    private val wrapped_text: (Canvas, String, Float, Float, Float, Int) -> Float,
    private val aircraft_label: (Aircraft) -> String,
    private val animation_frame: () -> Unit,
    private val retire_bitmap: (Bitmap) -> Unit,
) : FlightMapChromeHost,
    FlightMapPanelChrome,
    AircraftDetailsPanelChrome,
    TrafficPanelChrome,
    TrafficOverlayChrome {

    override fun dp(value: Float): Float = dp_value.scale(value)

    override fun sp(value: Float): Float = sp_value.scale(value)

    override fun ellipsize(value: String, max_width: Float): String = fit_text(value, max_width)

    override fun control_radius(): Float = control_radius_value()

    override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) {
        panel_surface(canvas, rect, fill, alpha)
    }

    override fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        choice_button(canvas, rect, label, selected)
    }

    override fun draw_control_surface(
        canvas: Canvas,
        rect: RectF,
        fill: Int,
        stroke: Int,
        selected: Boolean
    ) {
        control_surface(canvas, rect, fill, stroke, selected)
    }

    override fun draw_wrapped_text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        max_lines: Int
    ): Float {
        return wrapped_text(canvas, value, x, y, width, max_lines)
    }

    override fun aircraft_label_detail(aircraft: Aircraft): String = aircraft_label(aircraft)

    override fun request_animation_frame() {
        animation_frame()
    }

    override fun retire_bitmap_after_frame_commit(bitmap: Bitmap) {
        retire_bitmap(bitmap)
    }
}