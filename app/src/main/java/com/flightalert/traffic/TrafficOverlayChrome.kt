@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import java.util.Arrays
import java.util.concurrent.Executors
import java.util.function.BooleanSupplier
import java.util.function.IntSupplier
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.ThemeColors
import com.flightalert.ThemeTreatment
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolRenderer
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import com.flightalert.map.is_inside_viewport
import com.flightalert.mix_color
import com.flightalert.recycle_bitmap_pair
import com.flightalert.smooth_step
import com.flightalert.with_alpha

interface TrafficOverlayChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun aircraft_label_detail(aircraft: Aircraft): String
    fun request_animation_frame()
    fun retire_bitmap_after_frame_commit(bitmap: Bitmap)
}