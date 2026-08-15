@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.min
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftRoutePresenter
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.draw_fitted_text
import com.flightalert.traffic.FilterStats

class TrafficPanelRenderer(
    private val text_paint: Paint,
    private val stroke_paint: Paint,
    private val chrome: TrafficPanelChrome,
    private val with_alpha: (Int, Int) -> Int
) {
    fun draw_panel(
        canvas: Canvas,
        rect: RectF,
        wide: Boolean,
        style: TrafficPanelStyle,
        state: TrafficPanelState
    ) {
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel,
            style.visual_theme.style.info_panel_alpha
        )

        val y = rect.top + if (wide) dp(32) else dp(27)
        val model_label = (state.content as? TrafficPanelAircraftState)
            ?.model_label
            ?.takeIf { it != "Unavailable" }
        val content_width = rect.width() - dp(32)
        val model_width = if (model_label != null) {
            min(content_width * if (wide) 0.52f else 0.48f, if (wide) dp(230) else dp(190))
        } else {
            0f
        }
        val header_gap = if (model_label != null) dp(14) else 0f
        val title_width = content_width - model_width - header_gap
        val title_size = if (wide) sp(14) else sp(13)
        val model_size = if (wide) sp(15) else sp(13)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = title_size
        text_paint.color = state.title_color
        draw_fitted_left_text(
            canvas,
            state.title,
            rect.left + dp(16),
            y,
            title_width,
            title_size,
            sp(9)
        )
        if (model_label != null) {
            text_paint.color = style.visual_theme.colors.text
            draw_fitted_right_text(
                canvas,
                model_label,
                rect.right - dp(16),
                y,
                model_width,
                model_size,
                sp(9)
            )
        }

        when (val content = state.content) {
            is TrafficPanelEmptyState -> draw_empty_panel(
                canvas,
                rect,
                y + if (wide) dp(60) else dp(38),
                style,
                content
            )

            is TrafficPanelAircraftState -> draw_aircraft_panel(
                canvas,
                rect,
                wide,
                y,
                style,
                content
            )
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
        val main_text_size = if (wide) sp(30) else sp(24)
        text_paint.textSize = main_text_size
        text_paint.color = style.visual_theme.colors.text
        val horizontal_padding = dp(16)
        val main_gap = dp(if (wide) 18 else 14)
        val distance_width = (rect.width() * if (wide) 0.40f else 0.38f)
            .coerceAtLeast(dp(if (wide) 86 else 58))
        val callsign_width =
            (rect.width() - horizontal_padding * 2f - main_gap - distance_width).coerceAtLeast(dp(54))
        draw_fitted_left_text(
            canvas = canvas,
            value = content.callsign,
            left = rect.left + horizontal_padding,
            y = y,
            max_width = callsign_width,
            start_size = main_text_size,
            min_size = sp(12)
        )

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = main_text_size
        text_paint.color = content.distance_color
        draw_fitted_right_text(
            canvas = canvas,
            value = content.distance_label,
            right = rect.right - horizontal_padding,
            y = y,
            max_width = distance_width,
            start_size = main_text_size,
            min_size = sp(12)
        )

        if (wide) {
            y += dp(38)
            draw_wide_detail_rows(canvas, rect, y, content.wide_rows, style)
            return
        }

        draw_compact_stat_grid(canvas, rect, y + dp(26), style, content.compact_rows)
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
        draw_fitted_left_text(
            canvas,
            content.headline,
            rect.left + dp(16),
            y,
            rect.width() - dp(32),
            sp(20),
            sp(12)
        )
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        draw_fitted_left_text(
            canvas,
            content.message,
            rect.left + dp(16),
            y + dp(24),
            rect.width() - dp(32),
            sp(12),
            sp(9)
        )
        content.data_time_label?.let {
            draw_fitted_left_text(
                canvas,
                it,
                rect.left + dp(16),
                y + dp(44),
                rect.width() - dp(32),
                sp(12),
                sp(9)
            )
        }
    }

    private fun row_height(rect: RectF, start_y: Float, row_count: Int): Float {
        if (row_count <= 0) return dp(40)
        val available = (rect.bottom - dp(14) - start_y).coerceAtLeast(dp(21))
        return (available / row_count).coerceIn(dp(24), dp(40))
    }

    private fun draw_wide_detail_rows(
        canvas: Canvas,
        rect: RectF,
        top: Float,
        rows: List<TrafficPanelRow>,
        style: TrafficPanelStyle
    ) {
        val columns = if (rect.width() >= dp(300)) 2 else 1
        if (columns == 1) {
            var y = top
            val row_height = row_height(rect, y, rows.size)
            rows.forEach { row ->
                if (y + row_height <= rect.bottom - dp(8)) {
                    y = draw_detail_row(canvas, rect, y, row, row_height, style)
                }
            }
            return
        }

        val left = rect.left + dp(16)
        val right = rect.right - dp(16)
        val bottom = rect.bottom - dp(16)
        val route_rows = rows.filter { it.is_route_endpoint }
        val normal_rows = rows.filterNot { it.is_route_endpoint }
        val has_route_rows = route_rows.isNotEmpty()
        val route_row_height = dp(58)
        val route_height = route_rows.size * route_row_height
        val route_top = if (has_route_rows) (bottom - route_height).coerceAtLeast(top) else bottom
        val grid_bottom = if (has_route_rows) route_top - dp(8) else bottom
        val available = (grid_bottom - top).coerceAtLeast(dp(44))
        val min_row_height = if (has_route_rows) dp(42) else dp(54)
        val max_row_height = if (has_route_rows) dp(58) else dp(84)
        val line_capacity = (available / min_row_height).toInt().coerceAtLeast(1)
        val visible_rows = normal_rows.take(line_capacity * columns)
        val line_count = ((visible_rows.size + columns - 1) / columns).coerceAtLeast(1)
        val row_height = (available / line_count).coerceIn(min_row_height, max_row_height)
        val gap = dp(18)
        val column_width = ((right - left) - gap) / columns

        visible_rows.forEachIndexed { index, row ->
            val column = index % columns
            val line = index / columns
            val cell_left = left + column * (column_width + gap)
            val cell_top = top + line * row_height
            draw_wide_detail_cell(canvas, cell_left, cell_top, column_width, row_height, row, style)
        }
        route_rows.forEachIndexed { index, row ->
            val cell_top = route_top + index * route_row_height
            draw_wide_detail_cell(canvas, left, cell_top, right - left, route_row_height, row, style)
        }
    }

    private fun draw_wide_detail_cell(
        canvas: Canvas,
        left: Float,
        top: Float,
        width: Float,
        row_height: Float,
        row: TrafficPanelRow,
        style: TrafficPanelStyle
    ) {
        val spacious = row_height >= dp(76)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.color = style.visual_theme.colors.muted
        draw_fitted_left_text(
            canvas,
            row.label.uppercase(Locale.US),
            left,
            top + if (spacious) dp(14) else dp(11),
            width,
            if (spacious) sp(10) else sp(9),
            sp(7)
        )
        text_paint.isFakeBoldText = true
        text_paint.color = style.visual_theme.colors.text
        val value_lines = row.value.split('\n').take(2)
        value_lines.forEachIndexed { index, value ->
            draw_fitted_left_text(
                canvas,
                value,
                left,
                top + if (index == 0) {
                    if (spacious) dp(38) else dp(29)
                } else {
                    if (spacious) dp(60) else dp(45)
                },
                width,
                if (index == 0) {
                    if (spacious) sp(15) else sp(13)
                } else {
                    if (spacious) sp(12) else sp(11)
                },
                sp(8)
            )
        }
        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha
        )
        stroke_paint.strokeWidth = dp(1)
        canvas.drawLine(left, top + row_height - dp(5), left + width, top + row_height - dp(5), stroke_paint)
        text_paint.isFakeBoldText = false
    }

    private val TrafficPanelRow.is_route_endpoint: Boolean
        get() = label == "From" || label == "To"

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
        draw_fitted_right_text(
            canvas,
            row.value,
            rect.right - dp(16),
            y,
            rect.width() * 0.56f,
            sp(13),
            sp(9)
        )

        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha
        )
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
    ) = draw_fitted_text(
        canvas,
        text_paint,
        value,
        right,
        y,
        max_width,
        start_size,
        min_size,
        Paint.Align.RIGHT,
        dp(0.5f),
        chrome::ellipsize
    )

    private fun draw_fitted_left_text(
        canvas: Canvas,
        value: String,
        left: Float,
        y: Float,
        max_width: Float,
        start_size: Float,
        min_size: Float
    ) = draw_fitted_text(
        canvas,
        text_paint,
        value,
        left,
        y,
        max_width,
        start_size,
        min_size,
        Paint.Align.LEFT,
        dp(0.5f),
        chrome::ellipsize
    )

    private fun draw_compact_stat_grid(
        canvas: Canvas,
        rect: RectF,
        top: Float,
        style: TrafficPanelStyle,
        rows: List<TrafficPanelRow>
    ) {
        if (rows.isEmpty()) return
        val left = rect.left + dp(16)
        val right = rect.right - dp(16)
        val bottom = rect.bottom - dp(16)
        val columns = when {
            rect.width() >= dp(520) -> 4
            rect.width() >= dp(300) -> 3
            else -> 2
        }
        val visible_rows = rows.take(columns * 2)
        val row_count = (visible_rows.size + columns - 1) / columns
        val column_gap = dp(if (columns == 3) 12 else 14)
        val row_gap = dp(8)
        val available_height = (bottom - top).coerceAtLeast(dp(34))
        val cell_height =
            ((available_height - row_gap * (row_count - 1)) / row_count).coerceIn(dp(34), dp(42))
        val grid_height = cell_height * row_count + row_gap * (row_count - 1)
        val grid_top = top + ((available_height - grid_height).coerceAtLeast(0f) * 0.5f)
        val cell_width = ((right - left) - column_gap * (columns - 1)) / columns

        visible_rows.forEachIndexed { index, row ->
            val column = index % columns
            val line = index / columns
            val cell_left = left + column * (cell_width + column_gap)
            val cell_top = grid_top + line * (cell_height + row_gap)
            text_paint.textAlign = Paint.Align.LEFT
            text_paint.isFakeBoldText = false
            text_paint.color = style.visual_theme.colors.muted
            draw_fitted_left_text(
                canvas,
                row.label.uppercase(Locale.US),
                cell_left,
                cell_top + dp(10),
                cell_width,
                sp(9),
                sp(7)
            )
            text_paint.isFakeBoldText = true
            text_paint.color = style.visual_theme.colors.text
            draw_fitted_left_text(
                canvas,
                row.value,
                cell_left,
                cell_top + dp(31),
                cell_width,
                sp(13),
                sp(8)
            )
        }
        text_paint.isFakeBoldText = false
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = chrome.dp(value)

    private fun sp(value: Int): Float = sp(value.toFloat())

    private fun sp(value: Float): Float = chrome.sp(value)
}