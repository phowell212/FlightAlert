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

package com.flightalert.details

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.withClip
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import com.flightalert.ThemeTreatment
import com.flightalert.VisualTheme
import com.flightalert.draw_fitted_text
import com.flightalert.draw_wrapped_text
import com.flightalert.map.ScreenPoint
import com.flightalert.with_alpha
import com.flightalert.wrapped_text_lines



interface AircraftDetailsPanelChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun control_radius(): Float
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
    fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean)
}

