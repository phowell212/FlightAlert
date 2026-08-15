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



internal enum class ChromeSurfaceKind {
    CONTROL,
    PANEL
}