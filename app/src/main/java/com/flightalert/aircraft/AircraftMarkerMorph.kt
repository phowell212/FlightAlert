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

object AircraftMarkerMorph {
    fun marker_dot_blend(viewport: Viewport): Float {
        return zoom_marker_dot_blend(viewport.zoom)
    }

    fun zoom_marker_dot_blend(zoom: Double): Float {
        val zoom_dot_blend = 1f - smooth_step(DOT_ZOOM_FULL, DOT_ZOOM_SYMBOL, zoom.toFloat())
        return smooth_step(0f, 1f, zoom_dot_blend)
    }

    fun symbol_progress(dot_blend: Float): Float {
        return smooth_step(SYMBOL_BLEND_START, SYMBOL_BLEND_FULL, 1f - dot_blend)
    }

    fun symbol_visibility(dot_blend: Float): Float {
        return smooth_step(
            SYMBOL_VISIBILITY_START,
            SYMBOL_VISIBILITY_FULL,
            symbol_progress(dot_blend)
        )
    }

    fun shape_progress(dot_blend: Float): Float {
        return smooth_step(0f, 1f, 1f - dot_blend)
    }

    fun aircraft_icon_scale(zoom: Double): Float {
        val zoom_progress = smooth_step(SCALE_ZOOM_MIN, SCALE_ZOOM_MAX, zoom.toFloat())
        return SCALE_MIN + (SCALE_MAX - SCALE_MIN) * zoom_progress
    }

    fun aircraft_dot_scale(zoom: Double): Float {
        val far_progress =
            smooth_step(DOT_SCALE_FLOOR_ZOOM_MIN, DOT_SCALE_FLOOR_ZOOM_MAX, zoom.toFloat())
        val far_scale = lerp(DOT_SCALE_FLOOR_MIN, DOT_SCALE_FLOOR_MAX, far_progress)
        val transition_progress = smooth_step(
            DOT_SCALE_TRANSITION_ZOOM_START,
            DOT_SCALE_TRANSITION_ZOOM_END,
            zoom.toFloat()
        )
        return max(
            READABLE_DOT_SCALE_MIN,
            lerp(far_scale, DOT_SCALE_TRANSITION_MAX, transition_progress)
        )
    }

    fun blended_icon_scale(zoom: Double, dot_blend: Float): Float {
        return lerp(aircraft_icon_scale(zoom), aircraft_dot_scale(zoom), dot_blend)
    }

    fun dense_batch_dot_alpha(dot_blend: Float): Float {
        return 0.38f + 0.62f * smooth_step(0.12f, 0.92f, dot_blend)
    }

    fun batch_dot_outline_scale(dot_scale: Float): Float {
        return (dot_scale / DOT_SCALE_TRANSITION_MAX).coerceIn(BATCH_DOT_OUTLINE_MIN_SCALE, 1f)
    }

    fun smooth_step(edge0: Float, edge1: Float, value: Float): Float =
        safe_smooth_step(edge0, edge1, value)

    const val SYMBOL_ACTIVE_MIN_PROGRESS = 0.03f
    const val SYMBOL_IDLE_MIN_PROGRESS = 0.03f
    const val READABLE_DOT_SCALE_MIN = 0.24f

    private const val SCALE_ZOOM_MIN = 5.6f
    private const val SCALE_ZOOM_MAX = 13.2f
    private const val SCALE_MIN = 0.30f
    private const val SCALE_MAX = 1.0f
    private const val DOT_SCALE_FLOOR_MIN = 0.06f
    private const val DOT_SCALE_FLOOR_MAX = 0.34f
    private const val DOT_SCALE_TRANSITION_MAX = 1.08f
    private const val DOT_SCALE_FLOOR_ZOOM_MIN = 3.0f
    private const val DOT_SCALE_FLOOR_ZOOM_MAX = 6.4f
    private const val DOT_SCALE_TRANSITION_ZOOM_START = 3.8f
    private const val DOT_SCALE_TRANSITION_ZOOM_END = 13.6f
    private const val DOT_ZOOM_FULL = 2.0f
    private const val DOT_ZOOM_SYMBOL = 13.6f
    private const val SYMBOL_BLEND_START = 0.0f
    private const val SYMBOL_BLEND_FULL = 0.78f
    private const val SYMBOL_VISIBILITY_START = 0.0f
    private const val SYMBOL_VISIBILITY_FULL = 0.26f
    private const val BATCH_DOT_OUTLINE_MIN_SCALE = 0.22f
}