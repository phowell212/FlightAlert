@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

internal fun ellipsize_text(
    paint: Paint,
    value: String,
    max_width: Float,
): String {
    if (paint.measureText(value) <= max_width) return value
    if (value.length <= 3) return value
    var end = value.length
    while (end > 3 && paint.measureText(value.substring(0, end) + "...") > max_width) {
        end--
    }
    return value.substring(0, end) + "..."
}

internal fun draw_fitted_text(
    canvas: Canvas,
    paint: Paint,
    value: String,
    x: Float,
    y: Float,
    max_width: Float,
    start_size: Float,
    min_size: Float,
    align: Paint.Align,
    size_step: Float,
    ellipsize: (String, Float) -> String,
    minimum_width: Float = 0f
) {
    val width = max_width.coerceAtLeast(minimum_width)
    paint.textAlign = align
    paint.textSize = start_size
    while (paint.textSize > min_size && paint.measureText(value) > width) {
        paint.textSize -= size_step
    }
    val display = if (paint.measureText(value) <= width) value else ellipsize(value, width)
    canvas.drawText(display, x, y, paint)
}

internal fun wrapped_text_lines(
    paint: Paint,
    value: String,
    width: Float,
    max_lines: Int
): List<String> {
    val words = value.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val lines = mutableListOf<String>()
    var line = ""

    fun push_line(next_line: String): Boolean {
        if (lines.size >= max_lines) return false
        lines += next_line
        return true
    }

    words.forEach { word ->
        val candidate = if (line.isBlank()) word else "$line $word"
        if (paint.measureText(candidate) <= width) {
            line = candidate
        } else {
            if (line.isNotBlank() && !push_line(line)) return lines
            line = ""
            if (paint.measureText(word) <= width) {
                line = word
            } else {
                split_long_word(paint, word, width).forEach { segment ->
                    if (!push_line(segment)) return lines
                }
            }
        }
    }
    if (line.isNotBlank() && lines.size < max_lines) lines += line
    return lines
}

internal fun split_long_word(paint: Paint, word: String, width: Float): List<String> {
    val parts = mutableListOf<String>()
    var part = ""
    word.forEach { char ->
        val candidate = part + char
        if (candidate.length > 1 && paint.measureText(candidate) > width) {
            parts += part
            part = char.toString()
        } else {
            part = candidate
        }
    }
    if (part.isNotBlank()) parts += part
    return parts
}

internal fun draw_wrapped_text(
    canvas: Canvas,
    paint: Paint,
    value: String,
    x: Float,
    y: Float,
    width: Float,
    max_lines: Int,
    line_height: Float
): Float {
    var current_y = y
    wrapped_text_lines(paint, value, width, max_lines).forEach { line ->
        canvas.drawText(line, x, current_y, paint)
        current_y += line_height
    }
    return current_y
}
