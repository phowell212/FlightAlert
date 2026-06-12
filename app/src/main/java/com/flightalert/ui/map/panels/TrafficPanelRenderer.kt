package com.flightalert.ui.map.panels

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.flightalert.settings.FlightAlertSettings.VisualTheme
import java.util.Locale
import kotlin.math.min

data class TrafficPanelStyle(val visual_theme: VisualTheme)

data class TrafficPanelState(
    val title: String,
    val title_color: Int,
    val content: TrafficPanelContent
)

sealed interface TrafficPanelContent

data class TrafficPanelAircraftState(
    val callsign: String,
    val distance_label: String,
    val distance_color: Int,
    val wide_rows: List<TrafficPanelRow>,
    val compact_primary_values: List<String>,
    val compact_secondary_values: List<String>,
    val military_label: String?
) : TrafficPanelContent

data class TrafficPanelEmptyState(
    val headline: String,
    val message: String,
    val data_time_label: String?
) : TrafficPanelContent

data class TrafficPanelRow(val label: String, val value: String)

interface TrafficPanelChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
}

class TrafficPanelRenderer(
    private val text_paint: Paint,
    private val stroke_paint: Paint,
    private val chrome: TrafficPanelChrome,
    private val with_alpha: (Int, Int) -> Int
) {
    fun draw_panel(canvas: Canvas, rect: RectF, wide: Boolean, style: TrafficPanelStyle, state: TrafficPanelState) {
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel, style.visual_theme.style.info_panel_alpha)

        var y = rect.top + if (wide) dp(32) else dp(27)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = state.title_color
        canvas.drawText(state.title, rect.left + dp(16), y, text_paint)

        when (val content = state.content) {
            is TrafficPanelEmptyState -> draw_empty_panel(canvas, rect, y + if (wide) dp(60) else dp(38), style, content)
            is TrafficPanelAircraftState -> draw_aircraft_panel(canvas, rect, wide, y, style, content)
        }
    }

    private fun draw_aircraft_panel(
        canvas: Canvas,
        rect: RectF,
        wide: Boolean,
        start_y: Float,
        style: TrafficPanelStyle,
        content: TrafficPanelAircraftState
    ) {
        var y = start_y + if (wide) dp(44) else dp(32)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = if (wide) sp(29) else sp(24)
        text_paint.color = style.visual_theme.colors.text
        canvas.drawText(content.callsign, rect.left + dp(16), y, text_paint)

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = if (wide) sp(29) else sp(24)
        text_paint.color = content.distance_color
        canvas.drawText(content.distance_label, rect.right - dp(16), y, text_paint)

        if (wide) {
            y += dp(38)
            val row_height = row_height(rect, y, content.wide_rows.size)
            content.wide_rows.forEach { row ->
                if (y + row_height <= rect.bottom - dp(8)) {
                    y = draw_detail_row(canvas, rect, y, row, row_height, style)
                }
            }
            return
        }

        y += dp(28)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.muted
        content.compact_primary_values.forEachIndexed { index, value ->
            canvas.drawText(value, compact_column_x(rect, index, primary = true), y, text_paint)
        }

        y += dp(24)
        content.compact_secondary_values.forEachIndexed { index, value ->
            canvas.drawText(value, compact_column_x(rect, index, primary = false), y, text_paint)
        }
        content.military_label?.let { label ->
            y += dp(22)
            text_paint.isFakeBoldText = true
            text_paint.color = style.visual_theme.colors.military
            canvas.drawText(label, rect.left + dp(16), y, text_paint)
            text_paint.isFakeBoldText = false
        }
    }

    private fun draw_empty_panel(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        style: TrafficPanelStyle,
        content: TrafficPanelEmptyState
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(20)
        text_paint.color = style.visual_theme.colors.text
        canvas.drawText(content.headline, rect.left + dp(16), y, text_paint)
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(content.message, rect.left + dp(16), y + dp(24), text_paint)
        content.data_time_label?.let {
            canvas.drawText(it, rect.left + dp(16), y + dp(44), text_paint)
        }
    }

    private fun row_height(rect: RectF, start_y: Float, row_count: Int): Float {
        if (row_count <= 0) return dp(28)
        val available = (rect.bottom - dp(14) - start_y).coerceAtLeast(dp(21))
        return (available / row_count).coerceIn(dp(21), dp(28))
    }

    private fun draw_detail_row(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        row: TrafficPanelRow,
        row_height: Float,
        style: TrafficPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(row.label.uppercase(Locale.US), rect.left + dp(16), y, text_paint)

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_right_text(canvas, row.value, rect.right - dp(16), y, rect.width() * 0.56f, sp(13), sp(9))

        stroke_paint.color = with_alpha(style.visual_theme.colors.panel_stroke, style.visual_theme.style.divider_alpha)
        stroke_paint.strokeWidth = dp(1)
        val divider_y = y + min(dp(10), row_height - dp(7))
        canvas.drawLine(rect.left + dp(16), divider_y, rect.right - dp(16), divider_y, stroke_paint)
        text_paint.isFakeBoldText = false
        return y + row_height
    }

    private fun draw_fitted_right_text(
        canvas: Canvas,
        value: String,
        right: Float,
        y: Float,
        max_width: Float,
        start_size: Float,
        min_size: Float
    ) {
        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = start_size
        while (text_paint.textSize > min_size && text_paint.measureText(value) > max_width) {
            text_paint.textSize -= dp(0.5f)
        }
        val display = if (text_paint.measureText(value) <= max_width) value else chrome.ellipsize(value, max_width)
        canvas.drawText(display, right, y, text_paint)
    }

    private fun compact_column_x(rect: RectF, index: Int, primary: Boolean): Float {
        return when (index) {
            0 -> rect.left + dp(16)
            1 -> rect.left + rect.width() * if (primary) 0.34f else 0.46f
            else -> rect.left + rect.width() * 0.60f
        }
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = chrome.dp(value)

    private fun sp(value: Int): Float = sp(value.toFloat())

    private fun sp(value: Float): Float = chrome.sp(value)
}
