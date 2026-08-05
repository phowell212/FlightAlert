package com.flightalert

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.roundToInt

internal fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun safe_smooth_step(edge0: Float, edge1: Float, value: Float): Float {
    if (edge0 == edge1) return if (value >= edge1) 1f else 0f
    return smooth_step(edge0, edge1, value)
}

internal fun lerp(start: Float, end: Float, progress: Float): Float {
    return start + (end - start) * progress.coerceIn(0f, 1f)
}

internal fun mix_color(start: Int, end: Int, progress: Float): Int {
    val t = progress.coerceIn(0f, 1f)
    return Color.rgb(
        lerp(Color.red(start).toFloat(), Color.red(end).toFloat(), t).roundToInt().coerceIn(0, 255),
        lerp(Color.green(start).toFloat(), Color.green(end).toFloat(), t).roundToInt()
            .coerceIn(0, 255),
        lerp(Color.blue(start).toFloat(), Color.blue(end).toFloat(), t).roundToInt()
            .coerceIn(0, 255)
    )
}

internal fun with_alpha(color: Int, alpha: Int): Int {
    return Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )
}

// Cache/state builders intentionally share geometric growth. The renderer keeps its
// separate exact-size growth path because that allocation policy is a hot-path choice.
internal fun ensure_point_capacity(points: FloatArray, required: Int): FloatArray {
    if (points.size >= required) return points
    var next_size = max(128, points.size * 2)
    while (next_size < required) next_size *= 2
    return points.copyOf(next_size)
}

internal fun recycle_bitmap_pair(first: Bitmap, second: Bitmap) {
    first.recycle()
    second.recycle()
}
