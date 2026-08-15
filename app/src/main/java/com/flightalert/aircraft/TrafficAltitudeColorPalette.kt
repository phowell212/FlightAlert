@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import com.flightalert.ThemeTreatment
import com.flightalert.VisualTheme
import com.flightalert.lerp
import com.flightalert.map.Viewport
import com.flightalert.mix_color
import com.flightalert.safe_smooth_step
import com.flightalert.smooth_step



data class TrafficAltitudeColorPalette(
    val aggressive: Int,
    val aggressive_shade: Int,
    val distinct: Int,
    val calmest: Int
)