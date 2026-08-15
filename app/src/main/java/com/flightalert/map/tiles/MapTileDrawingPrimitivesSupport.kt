@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.graphics.Canvas
import android.graphics.Paint

internal fun draw_unavailable_map_tile(
    canvas: Canvas,
    paint: Paint,
    text_paint: Paint,
    x: Float,
    y: Float,
    size: Float,
    fill_color: Int,
    text_color: Int,
    text_size: Float
) {
    paint.style = Paint.Style.FILL
    paint.color = fill_color
    canvas.drawRect(x, y, x + size, y + size, paint)
    text_paint.textAlign = Paint.Align.CENTER
    text_paint.textSize = text_size
    text_paint.color = text_color
    canvas.drawText("Loading map", x + size / 2f, y + size / 2f, text_paint)
}
