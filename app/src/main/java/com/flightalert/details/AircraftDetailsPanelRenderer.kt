@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.details

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.withClip
import com.flightalert.map.ScreenPoint
import com.flightalert.ThemeTreatment
import com.flightalert.VisualTheme
import com.flightalert.ui.draw_fitted_text
import com.flightalert.ui.draw_wrapped_text
import com.flightalert.ui.with_alpha
import com.flightalert.ui.wrapped_text_lines
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Draws the selected-aircraft modal; FlightMapView provides state and this object owns all panel geometry.
class AircraftDetailsPanelRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val chrome: AircraftDetailsPanelChrome
) {
    // Dispatch one details-panel state to the matching drawing branch and return scroll bounds to FlightMapView.
    fun draw_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        style: AircraftDetailsPanelStyle,
        state: AircraftDetailsPanelState
    ): AircraftDetailsDrawResult {
        val rect = panel_bounds(w, h, state.wide_layout)
        return when (val content = state.content) {
            is AircraftDetailsMainState -> draw_main_panel(canvas, rect, style, state, content)
            is AircraftImpactPanelState -> draw_impact_panel(canvas, rect, style, state, content)
            is AircraftUsagePanelState -> {
                draw_usage_panel(canvas, rect, style, content)
                AircraftDetailsDrawResult(0f, 0f)
            }

            is AircraftPhotoEvidencePanelState -> draw_photo_evidence_panel(
                canvas,
                rect,
                style,
                state,
                content
            )

            is AircraftPhotoGalleryPanelState -> draw_photo_gallery_panel(
                canvas,
                rect,
                style,
                state,
                content
            )
        }
    }

    // Center the details modal differently for wide and portrait layouts without changing content state.
    fun panel_bounds(w: Float, h: Float, wide_layout: Boolean): RectF {
        val margin = dp(14)
        val width = if (wide_layout) min(dp(800), w - margin * 2f) else w - margin * 2f
        val height =
            if (wide_layout) min(h - margin * 2f, dp(390)) else min(h - margin * 2f, dp(720))
        return RectF((w - width) / 2f, (h - height) / 2f, (w + width) / 2f, (h + height) / 2f)
    }

    fun close_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.right - dp(112),
            panel.top + dp(14),
            panel.right - dp(18),
            panel.top + dp(48)
        )
    }

    fun usage_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.right - dp(214),
            panel.top + dp(14),
            panel.right - dp(122),
            panel.top + dp(48)
        )
    }

    fun impact_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.left + dp(18),
            panel.bottom - dp(52),
            panel.right - dp(18),
            panel.bottom - dp(16)
        )
    }

    fun impact_hit_bounds(panel: RectF): RectF {
        val button = impact_button_bounds(panel)
        return RectF(
            button.left - dp(4),
            button.top - dp(4),
            button.right + dp(4),
            button.bottom + dp(4)
        )
    }

    fun photo_image_source_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.left + dp(18),
            panel.bottom - dp(58),
            panel.left + dp(138),
            panel.bottom - dp(18)
        )
    }

    fun photo_page_source_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.left + dp(150),
            panel.bottom - dp(58),
            panel.left + dp(286),
            panel.bottom - dp(18)
        )
    }

    // The current photo hit area matches the rendered photo, not the whole photo/status column.
    fun current_photo_bounds(panel: RectF, wide_layout: Boolean, has_photo: Boolean): RectF {
        return if (wide_layout) {
            val left = RectF(
                panel.left + dp(18),
                panel.top + dp(66),
                panel.left + panel.width() * 0.38f,
                panel.bottom - dp(18)
            )
            photo_bounds(left, wide = true, has_photo = has_photo)
        } else {
            photo_bounds(panel, wide = false, has_photo = has_photo)
        }
    }

    fun gallery_item_bounds(panel: RectF, index: Int, wide_layout: Boolean): RectF {
        val columns = if (wide_layout) 3 else 2
        val gap = dp(12)
        val left = panel.left + dp(18)
        val top = panel.top + dp(82)
        val width = (panel.width() - dp(36) - gap * (columns - 1)) / columns
        val height = if (wide_layout) dp(122) else dp(142)
        val row = index / columns
        val column = index % columns
        val x = left + column * (width + gap)
        val y = top + row * (height + gap)
        return RectF(x, y, x + width, y + height)
    }

    // Draw the normal details mode: title, exact/representative photo, metadata rows, and impact entry.
    private fun draw_main_panel(
        canvas: Canvas,
        rect: RectF,
        style: AircraftDetailsPanelStyle,
        state: AircraftDetailsPanelState,
        content: AircraftDetailsMainState
    ): AircraftDetailsDrawResult {
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel_alt,
            style.visual_theme.style.modal_panel_alpha
        )
        chrome.draw_choice_button(canvas, close_button_bounds(rect), "Close", false)
        if (content.has_aircraft) {
            chrome.draw_choice_button(
                canvas,
                usage_button_bounds(rect),
                "Usage",
                content.has_usage_trace
            )
        }

        val title_left = rect.left + dp(18)
        val title_right = if (content.has_aircraft) {
            usage_button_bounds(rect).left - dp(10)
        } else {
            close_button_bounds(rect).left - dp(10)
        }
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(22)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_left_text(
            canvas = canvas,
            value = content.title,
            left = title_left,
            y = rect.top + dp(38),
            max_width = (title_right - title_left).coerceAtLeast(dp(36)),
            start_size = sp(22),
            min_size = sp(12)
        )

        val reserve_bottom = if (content.has_aircraft) dp(64) else dp(12)
        val clip = content_bounds(rect, reserve_bottom)
        val scroll_y = state.scroll_y
        val visible_top = clip.top + scroll_y
        val visible_bottom = clip.bottom + scroll_y - dp(2)
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        canvas.translate(0f, -scroll_y)
        val content_bottom = if (state.wide_layout && content.has_aircraft) {
            draw_wide_details(
                canvas,
                rect,
                state.photo,
                content.rows,
                style,
                visible_top,
                visible_bottom
            )
        } else {
            val photo_rect =
                photo_bounds(rect, wide = state.wide_layout, has_photo = state.photo.bitmap != null)
            draw_photo_block(canvas, photo_rect, state.photo, style)
            var y = photo_rect.bottom + dp(30)
            content.rows.forEach { row ->
                y = draw_adaptive_detail_row(
                    canvas,
                    row_bounds(rect),
                    y,
                    row,
                    style,
                    visible_top,
                    visible_bottom
                )
            }
            y
        }
        canvas.restoreToCount(checkpoint)

        val result =
            scroll_result(content_bottom - clip.top + dp(12), clip.height(), state.scroll_y)
        draw_scroll_indicator(canvas, clip, result, style)
        if (content.has_aircraft) {
            chrome.draw_choice_button(
                canvas,
                impact_button_bounds(rect),
                "Environmental impact",
                false
            )
        }
        return result
    }

    // Wide layouts split details into photo plus two row columns so long metadata stays scannable.
    private fun draw_wide_details(
        canvas: Canvas,
        rect: RectF,
        photo: AircraftDetailsPhotoState,
        rows: List<AircraftDetailsRow>,
        style: AircraftDetailsPanelStyle,
        visible_top: Float,
        visible_bottom: Float
    ): Float {
        val left = RectF(
            rect.left + dp(18),
            rect.top + dp(66),
            rect.left + rect.width() * 0.38f,
            rect.bottom - dp(18)
        )
        val gap = dp(18)
        val right_left = left.right + gap
        val right_width = rect.right - dp(18) - right_left
        val col_gap = dp(16)
        val col_width = (right_width - col_gap) / 2f
        val col_a =
            RectF(right_left, rect.top + dp(76), right_left + col_width, rect.bottom - dp(18))
        val col_b = RectF(col_a.right + col_gap, col_a.top, rect.right - dp(18), col_a.bottom)

        val photo_rect = photo_bounds(left, wide = true, has_photo = photo.bitmap != null)
        draw_photo_block(canvas, photo_rect, photo, style)

        val split = section_aware_split_index(rows)
        var y_a = col_a.top
        rows.take(split).forEach { row ->
            y_a = draw_adaptive_detail_row(
                canvas,
                col_a,
                y_a,
                row,
                style,
                visible_top,
                visible_bottom,
                compact = true
            )
        }
        var y_b = col_b.top
        rows.drop(split).forEach { row ->
            y_b = draw_adaptive_detail_row(
                canvas,
                col_b,
                y_b,
                row,
                style,
                visible_top,
                visible_bottom,
                compact = true
            )
        }
        return maxOf(photo_rect.bottom, y_a, y_b)
    }

    private fun section_aware_split_index(rows: List<AircraftDetailsRow>): Int {
        if (rows.size <= 2) return rows.size
        val target = rows.size / 2
        return rows.indices
            .filter { index -> index > 0 && rows[index].section }
            .minByOrNull { index -> abs(index - target) }
            ?: ((rows.size + 1) / 2)
    }

    private fun draw_panel_heading(
        canvas: Canvas,
        rect: RectF,
        title: String,
        style: AircraftDetailsPanelStyle
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(21)
        text_paint.color = style.visual_theme.colors.text
        val left = rect.left + dp(18)
        draw_fitted_left_text(
            canvas,
            title,
            left,
            rect.top + dp(38),
            max(0f, close_button_bounds(rect).left - left - dp(10)),
            sp(21),
            sp(11)
        )
    }

    // Draw environmental impact only from prepared presenter rows so missing aircraft data stays unavailable.
    private fun draw_impact_panel(
        canvas: Canvas,
        rect: RectF,
        style: AircraftDetailsPanelStyle,
        state: AircraftDetailsPanelState,
        content: AircraftImpactPanelState
    ): AircraftDetailsDrawResult {
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel_alt,
            style.visual_theme.style.modal_panel_alpha
        )
        chrome.draw_choice_button(canvas, close_button_bounds(rect), "Back", false)

        draw_panel_heading(canvas, rect, "Environmental impact", style)

        if (!content.selected_aircraft_available) {
            draw_centered_message(
                canvas,
                "Unavailable: no selected live aircraft.",
                RectF(
                    rect.left + dp(18),
                    rect.top + dp(86),
                    rect.right - dp(18),
                    rect.bottom - dp(24)
                ),
                style
            )
            return AircraftDetailsDrawResult(0f, 0f)
        }

        val clip = content_bounds(rect)
        val visible_top = clip.top + state.scroll_y
        val visible_bottom = clip.bottom + state.scroll_y - dp(2)
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        canvas.translate(0f, -state.scroll_y)

        var y = clip.top + dp(16)
        y = draw_impact_status(canvas, rect, y, content.status, style) + dp(18)
        y = draw_impact_score_summary(canvas, rect, y, content, style)
        y += dp(8)
        content.rows.forEach { row ->
            y = draw_impact_detail_row(
                canvas,
                row_bounds(rect),
                y,
                row,
                style,
                visible_top,
                visible_bottom
            )
        }

        canvas.restoreToCount(checkpoint)
        val result = scroll_result(y - clip.top + dp(12), clip.height(), state.scroll_y)
        draw_scroll_indicator(canvas, clip, result, style)
        return result
    }

    private fun draw_impact_status(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        status: String,
        style: AircraftDetailsPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        return draw_wrapped_text(
            canvas,
            status,
            rect.left + dp(18),
            y,
            rect.width() - dp(36),
            DETAILS_IMPACT_STATUS_MAX_LINES
        )
    }

    private fun draw_impact_score_summary(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        content: AircraftImpactPanelState,
        style: AircraftDetailsPanelStyle
    ): Float {
        val left = rect.left + dp(18)
        val right = rect.right - dp(18)
        val column_gap = dp(22)
        val two_column = rect.width() >= dp(520)
        val co2_label = if (content.show_trace_co2) "TRACE CO2 SO FAR" else "CLASS CO2 RATE"
        val bottom = if (two_column) {
            val column_width = (right - left - column_gap) / 2f
            max(
                draw_impact_metric_block(
                    canvas,
                    RectF(left, y, left + column_width, y),
                    co2_label,
                    content.co2_text,
                    style.visual_theme.colors.text,
                    style.visual_theme.colors.muted,
                    sp(21)
                ),
                draw_impact_metric_block(
                    canvas,
                    RectF(right - column_width, y, right, y),
                    content.score_label,
                    content.score_text,
                    content.score_color,
                    style.visual_theme.colors.muted,
                    sp(24)
                )
            )
        } else {
            var current = draw_impact_metric_block(
                canvas,
                RectF(left, y, right, y),
                co2_label,
                content.co2_text,
                style.visual_theme.colors.text,
                style.visual_theme.colors.muted,
                sp(21)
            ) + dp(12)
            current = draw_impact_metric_block(
                canvas,
                RectF(left, current, right, current),
                content.score_label,
                content.score_text,
                content.score_color,
                style.visual_theme.colors.muted,
                sp(24)
            )
            current
        }

        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha + 36
        )
        stroke_paint.strokeWidth = dp(1)
        val divider_y = bottom + dp(12)
        canvas.drawLine(left, divider_y, right, divider_y, stroke_paint)
        text_paint.isFakeBoldText = false
        return divider_y + dp(18)
    }

    private fun draw_impact_metric_block(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        value_color: Int,
        label_color: Int,
        value_size: Float
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = label_color
        val label_bottom = draw_wrapped_text(
            canvas,
            label.uppercase(Locale.US),
            rect.left,
            rect.top,
            rect.width(),
            DETAILS_IMPACT_METRIC_LABEL_MAX_LINES
        )

        text_paint.isFakeBoldText = true
        text_paint.textSize = value_size
        text_paint.color = value_color
        var value_y = label_bottom + dp(5)
        val line_height = max(text_paint.fontSpacing, value_size + dp(4))
        wrapped_text_lines(value, rect.width(), DETAILS_IMPACT_METRIC_VALUE_MAX_LINES).forEach { line ->
            canvas.drawText(line, rect.left, value_y, text_paint)
            value_y += line_height
        }
        text_paint.isFakeBoldText = false
        return value_y
    }

    private fun draw_impact_detail_row(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        row: AircraftDetailsRow,
        style: AircraftDetailsPanelStyle,
        visible_top: Float,
        visible_bottom: Float
    ): Float {
        if (row.section) {
            return draw_detail_section_header(
                canvas,
                rect,
                y,
                row,
                style,
                visible_top,
                visible_bottom,
                compact = false
            )
        }
        val left = rect.left + dp(18)
        val right = rect.right - dp(18)
        val width = right - left
        val label_line_height = dp(14)
        val value_line_height = dp(18)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        val label_lines = wrapped_text_lines(
            row.label.uppercase(Locale.US),
            width,
            DETAILS_IMPACT_ROW_LABEL_MAX_LINES
        )
        text_paint.textSize = sp(12)
        val value_lines = wrapped_text_lines(row.value, width, DETAILS_IMPACT_ROW_VALUE_MAX_LINES)
        val row_bottom =
            y + label_line_height * label_lines.size + dp(6) + value_line_height * value_lines.size + dp(10)
        if (row_bottom > visible_bottom || y < visible_top) return row_bottom

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.accent_blue
        var line_y = y
        label_lines.forEach { line ->
            canvas.drawText(line, left, line_y, text_paint)
            line_y += label_line_height
        }

        line_y += dp(6)
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.text
        value_lines.forEach { line ->
            canvas.drawText(line, left, line_y, text_paint)
            line_y += value_line_height
        }

        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha
        )
        stroke_paint.strokeWidth = dp(1)
        canvas.drawLine(left, row_bottom - dp(5), right, row_bottom - dp(5), stroke_paint)
        text_paint.isFakeBoldText = false
        return row_bottom
    }

    // Draw trace-derived usage stats, or an honest unavailable message when no real trace supports it.
    private fun draw_usage_panel(
        canvas: Canvas,
        rect: RectF,
        style: AircraftDetailsPanelStyle,
        content: AircraftUsagePanelState
    ) {
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel_alt,
            style.visual_theme.style.modal_panel_alpha
        )
        chrome.draw_choice_button(canvas, close_button_bounds(rect), "Back", false)

        draw_panel_heading(canvas, rect, "Aircraft usage", style)

        if (!content.selected_aircraft_available) {
            draw_centered_message(
                canvas,
                "Unavailable: no selected live aircraft.",
                RectF(
                    rect.left + dp(18),
                    rect.top + dp(86),
                    rect.right - dp(18),
                    rect.bottom - dp(24)
                ),
                style
            )
            return
        }

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(
            chrome.ellipsize(content.status, rect.width() - dp(36)),
            rect.left + dp(18),
            rect.top + dp(62),
            text_paint
        )

        val unavailable = content.unavailable_message
        if (unavailable != null) {
            draw_centered_message(
                canvas,
                unavailable,
                RectF(
                    rect.left + dp(18),
                    rect.top + dp(96),
                    rect.right - dp(18),
                    rect.bottom - dp(24)
                ),
                style
            )
            return
        }

        var y = rect.top + dp(102)
        content.stat_rows.forEach { row ->
            y = draw_usage_stat_line(canvas, rect, y, row.label, row.value, style)
        }
        content.stats?.let { stats ->
            val graph =
                RectF(rect.left + dp(18), y + dp(14), rect.right - dp(18), rect.bottom - dp(28))
            draw_usage_graph(canvas, graph, stats, style)
        }
    }

    private fun draw_usage_stat_line(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        label: String,
        value: String,
        style: AircraftDetailsPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(label.uppercase(Locale.US), rect.left + dp(18), y, text_paint)
        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_right_text(
            canvas,
            value,
            rect.right - dp(18),
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
        canvas.drawLine(
            rect.left + dp(18),
            y + dp(10),
            rect.right - dp(18),
            y + dp(10),
            stroke_paint
        )
        text_paint.isFakeBoldText = false
        return y + dp(28)
    }

    // Draw a small seven-day chart from trace buckets without implying broader historical coverage.
    private fun draw_usage_graph(
        canvas: Canvas,
        rect: RectF,
        stats: UsageStats,
        style: AircraftDetailsPanelStyle
    ) {
        if (rect.height() < dp(96) || rect.width() < dp(180)) return
        paint.style = Paint.Style.FILL
        paint.color = if (style.visual_theme.style.treatment == ThemeTreatment.PLAIN) {
            with_alpha(style.visual_theme.colors.panel, 120)
        } else {
            with_alpha(style.visual_theme.colors.panel, 150)
        }
        val radius = chrome.control_radius().coerceAtMost(dp(8))
        canvas.drawRoundRect(rect, radius, radius, paint)
        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha + 44
        )
        stroke_paint.strokeWidth = dp(1)
        canvas.drawRoundRect(rect, radius, radius, stroke_paint)

        val chart =
            RectF(rect.left + dp(16), rect.top + dp(28), rect.right - dp(16), rect.bottom - dp(28))
        val max_hours = max(1.0, stats.buckets.maxOf { it.hours })
        val max_flights = max(1, stats.buckets.maxOf { it.flights })
        val step = chart.width() / max(1, stats.buckets.size - 1)
        val bar_width = min(dp(18), step * 0.38f)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11)
        text_paint.color = style.visual_theme.colors.text
        canvas.drawText("7-day trace usage", rect.left + dp(14), rect.top + dp(18), text_paint)
        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(
            "hours line / flights bars",
            rect.right - dp(14),
            rect.top + dp(18),
            text_paint
        )

        stroke_paint.color = with_alpha(style.visual_theme.colors.muted, 90)
        stroke_paint.strokeWidth = dp(1)
        canvas.drawLine(chart.left, chart.bottom, chart.right, chart.bottom, stroke_paint)

        var previous: ScreenPoint? = null
        stats.buckets.forEachIndexed { index, bucket ->
            val x = chart.left + step * index
            val bar_height =
                (chart.height() * (bucket.flights.toFloat() / max_flights)).coerceAtLeast(
                    if (bucket.flights > 0) dp(3) else 0f
                )
            paint.color = with_alpha(
                style.visual_theme.colors.accent_blue,
                if (bucket.flights > 0) 150 else 42
            )
            canvas.drawRoundRect(
                RectF(
                    x - bar_width / 2f,
                    chart.bottom - bar_height,
                    x + bar_width / 2f,
                    chart.bottom
                ), dp(2), dp(2), paint
            )

            val point = ScreenPoint(
                x,
                chart.bottom - (chart.height() * (bucket.hours / max_hours)).toFloat()
            )
            previous?.let { old ->
                stroke_paint.color = style.visual_theme.colors.accent_yellow
                stroke_paint.strokeWidth = dp(2)
                canvas.drawLine(old.x, old.y, point.x, point.y, stroke_paint)
            }
            paint.color = style.visual_theme.colors.accent_yellow
            canvas.drawCircle(point.x, point.y, dp(3), paint)
            previous = point

            text_paint.textAlign = Paint.Align.CENTER
            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(9)
            text_paint.color = style.visual_theme.colors.muted
            canvas.drawText(bucket.label, x, rect.bottom - dp(10), text_paint)
        }
    }

    // Draw proof for search-engine photos so investigable images remain auditable to the user.
    private fun draw_photo_evidence_panel(
        canvas: Canvas,
        rect: RectF,
        style: AircraftDetailsPanelStyle,
        state: AircraftDetailsPanelState,
        content: AircraftPhotoEvidencePanelState
    ): AircraftDetailsDrawResult {
        val evidence = content.evidence
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel_alt,
            style.visual_theme.style.modal_panel_alpha
        )
        chrome.draw_choice_button(canvas, close_button_bounds(rect), "Close", false)

        draw_panel_heading(canvas, rect, "Photo verification", style)

        if (evidence == null) {
            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(13)
            text_paint.color = style.visual_theme.colors.muted
            draw_fitted_left_text(
                canvas,
                "No search-engine verification is attached to this photo.",
                rect.left + dp(18),
                rect.top + dp(78),
                rect.width() - dp(36),
                sp(13),
                sp(9)
            )
            return AircraftDetailsDrawResult(0f, 0f)
        }

        val wide = rect.width() > rect.height()
        val image_rect = if (wide) {
            RectF(
                rect.left + dp(18),
                rect.top + dp(72),
                rect.left + rect.width() * 0.44f,
                rect.bottom - dp(80)
            )
        } else {
            RectF(rect.left + dp(18), rect.top + dp(72), rect.right - dp(18), rect.top + dp(270))
        }
        val clip = content_bounds(rect, reserve_bottom = dp(72))
        val checkpoint = canvas.save()
        canvas.clipRect(clip)
        canvas.translate(0f, -state.scroll_y)
        draw_photo_block(canvas, image_rect, state.photo, style)

        val text_left = if (wide) image_rect.right + dp(22) else rect.left + dp(18)
        var y = if (wide) rect.top + dp(82) else image_rect.bottom + dp(32)
        val text_rect = RectF(text_left, y, rect.right - dp(18), rect.bottom - dp(82))

        y = draw_evidence_text_line(canvas, text_rect, y, "Source", evidence.source_name, style)
        y = draw_evidence_text_line(canvas, text_rect, y, "Search", evidence.search_query, style)
        y = draw_evidence_text_line(
            canvas,
            text_rect,
            y,
            "Matched",
            evidence.matched_terms.joinToString(", ").ifBlank { "Unavailable" },
            style
        )

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText("Verification quote", text_rect.left, y + dp(10), text_paint)
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        val quote_bottom = draw_wrapped_text(
            canvas,
            evidence.quote,
            text_rect.left,
            y + dp(34),
            text_rect.width(),
            DETAILS_PROOF_QUOTE_LINES
        )
        val content_bottom = maxOf(image_rect.bottom, quote_bottom) + dp(14)
        canvas.restoreToCount(checkpoint)

        val result = scroll_result(content_bottom - clip.top, clip.height(), state.scroll_y)
        draw_scroll_indicator(canvas, clip, result, style)

        evidence.image_url.takeIf { it.isNotBlank() }?.let {
            chrome.draw_choice_button(
                canvas,
                photo_image_source_button_bounds(rect),
                "Open image",
                false
            )
        }
        evidence.page_url.takeIf { it.isNotBlank() }?.let {
            chrome.draw_choice_button(
                canvas,
                photo_page_source_button_bounds(rect),
                "Open source",
                false
            )
        }
        return result
    }

    private fun draw_photo_gallery_panel(
        canvas: Canvas,
        rect: RectF,
        style: AircraftDetailsPanelStyle,
        state: AircraftDetailsPanelState,
        content: AircraftPhotoGalleryPanelState
    ): AircraftDetailsDrawResult {
        chrome.draw_panel_surface(
            canvas,
            rect,
            style.visual_theme.colors.panel_alt,
            style.visual_theme.style.modal_panel_alpha
        )
        chrome.draw_choice_button(canvas, close_button_bounds(rect), "Back", false)

        draw_panel_heading(canvas, rect, "Photo gallery", style)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(
            chrome.ellipsize(content.status, rect.width() - dp(36)),
            rect.left + dp(18),
            rect.top + dp(62),
            text_paint
        )

        val clip = content_bounds(rect)
        if (content.items.isEmpty()) {
            val message =
                if (content.loading) content.status else "No real gallery photos available from checked sources."
            draw_centered_message(canvas, message, clip.inset_copy(dp(18), dp(18)), style)
            return AircraftDetailsDrawResult(0f, 0f)
        }

        canvas.withClip(clip) {
            translate(0f, -state.scroll_y)
            content.items.forEachIndexed { index, item ->
                val item_rect = gallery_item_bounds(rect, index, state.wide_layout)
                draw_gallery_item(this, item_rect, item, style)
            }
        }

        val last = gallery_item_bounds(rect, content.items.lastIndex, state.wide_layout)
        val result = scroll_result(last.bottom - clip.top + dp(18), clip.height(), state.scroll_y)
        draw_scroll_indicator(canvas, clip, result, style)
        return result
    }

    private fun draw_gallery_item(
        canvas: Canvas,
        rect: RectF,
        item: AircraftPhotoGalleryItem,
        style: AircraftDetailsPanelStyle
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.text
        canvas.drawText(
            chrome.ellipsize(item.title, rect.width()),
            rect.left,
            rect.top + dp(10),
            text_paint
        )
        val photo_rect = RectF(rect.left, rect.top + dp(16), rect.right, rect.bottom)
        draw_photo_block(
            canvas,
            photo_rect,
            AircraftDetailsPhotoState(item.bitmap, item.caption),
            style
        )
        if (item.evidence != null) {
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(style.visual_theme.colors.accent_blue, 176)
            canvas.drawCircle(rect.right - dp(12), rect.top + dp(28), dp(5), paint)
        }
        text_paint.isFakeBoldText = false
    }

    // Draw either the real fetched bitmap or the honest photo status message in the same reserved area.
    private fun draw_photo_block(
        canvas: Canvas,
        photo_rect: RectF,
        photo: AircraftDetailsPhotoState,
        style: AircraftDetailsPanelStyle
    ) {
        paint.color = style.visual_theme.colors.photo_surface
        val radius = chrome.control_radius().coerceAtMost(dp(10))
        canvas.drawRoundRect(photo_rect, radius, radius, paint)
        val bitmap = photo.bitmap
        paint.alpha = 255
        if (bitmap != null) {
            val previous = photo.previous_bitmap
            val progress = photo.transition_progress.coerceIn(0f, 1f)
            if (previous != null && progress < 0.999f) {
                val eased = progress * progress * (3f - 2f * progress)
                canvas.withClip(photo_rect) {
                    draw_photo_bitmap(
                        canvas,
                        previous,
                        photo_rect,
                        -photo_rect.width() * eased * 0.42f
                    )
                    draw_photo_bitmap(canvas, bitmap, photo_rect, photo_rect.width() * (1f - eased))
                }
            } else {
                draw_photo_bitmap(canvas, bitmap, photo_rect)
            }
            draw_photo_caption(canvas, photo_rect, photo.status, style)
        } else {
            text_paint.textAlign = Paint.Align.CENTER
            text_paint.textSize = sp(11)
            text_paint.color = style.visual_theme.colors.muted
            draw_centered_wrapped_text(canvas, photo.status, photo_rect.inset_copy(dp(14), dp(8)))
        }
    }

    private fun draw_photo_bitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        photo_rect: RectF,
        offset_x: Float = 0f
    ) {
        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dest = aspect_fit_rect(bitmap.width, bitmap.height, photo_rect)
        dest.offset(offset_x, 0f)
        canvas.drawBitmap(bitmap, src, dest, paint)
    }

    // Draw one metadata row with wrapping and clipping so long values do not overlap nearby content.
    private fun draw_adaptive_detail_row(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        row: AircraftDetailsRow,
        style: AircraftDetailsPanelStyle,
        visible_top: Float,
        visible_bottom: Float,
        compact: Boolean = false
    ): Float {
        if (row.section) {
            return draw_detail_section_header(
                canvas,
                rect,
                y,
                row,
                style,
                visible_top,
                visible_bottom,
                compact
            )
        }
        val label_size = if (compact) sp(9) else sp(10)
        val value_size = if (compact) sp(12) else sp(13)
        val min_value_size = if (compact) sp(8) else sp(9)
        val one_line_width = rect.width() * if (compact) 0.62f else 0.56f
        val right = rect.right - if (compact) 0f else dp(16)
        val left = rect.left + if (compact) 0f else dp(16)
        text_paint.textSize = value_size
        val row_bottom = if (text_paint.measureText(row.value) <= one_line_width) {
            y + dp(if (compact) 25 else 28)
        } else {
            val lines = wrapped_text_lines(row.value, right - left, DETAILS_ROW_MAX_LINES)
            y + dp(if (compact) 18 else 21) + lines.size * (if (compact) dp(16) else dp(18)) + dp(if (compact) 8 else 10)
        }
        if (row_bottom > visible_bottom || y < visible_top) return row_bottom

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = label_size
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(
            row.label.uppercase(Locale.US),
            rect.left + if (compact) 0f else dp(16),
            y,
            text_paint
        )

        text_paint.isFakeBoldText = true
        text_paint.textSize = value_size
        text_paint.color = style.visual_theme.colors.text
        if (text_paint.measureText(row.value) <= one_line_width) {
            text_paint.textAlign = Paint.Align.RIGHT
            draw_fitted_right_text(
                canvas,
                row.value,
                right,
                y,
                one_line_width,
                value_size,
                min_value_size
            )
            stroke_paint.color = with_alpha(
                style.visual_theme.colors.panel_stroke,
                if (compact) max(
                    36,
                    style.visual_theme.style.divider_alpha - 10
                ) else style.visual_theme.style.divider_alpha
            )
            stroke_paint.strokeWidth = dp(1)
            canvas.drawLine(
                left,
                y + dp(if (compact) 9 else 10),
                right,
                y + dp(if (compact) 9 else 10),
                stroke_paint
            )
            text_paint.isFakeBoldText = false
            return row_bottom
        }

        text_paint.textAlign = Paint.Align.LEFT
        val available_width = right - left
        val lines = wrapped_text_lines(row.value, available_width, DETAILS_ROW_MAX_LINES)
        var cy = y + dp(if (compact) 18 else 21)
        val line_height = if (compact) dp(16) else dp(18)
        lines.forEach { line ->
            canvas.drawText(line, left, cy, text_paint)
            cy += line_height
        }
        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            if (compact) max(
                36,
                style.visual_theme.style.divider_alpha - 10
            ) else style.visual_theme.style.divider_alpha
        )
        stroke_paint.strokeWidth = dp(1)
        canvas.drawLine(left, cy - dp(6), right, cy - dp(6), stroke_paint)
        text_paint.isFakeBoldText = false
        return row_bottom
    }

    private fun draw_detail_section_header(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        row: AircraftDetailsRow,
        style: AircraftDetailsPanelStyle,
        visible_top: Float,
        visible_bottom: Float,
        compact: Boolean
    ): Float {
        val top_gap = if (compact) dp(12) else dp(16)
        val row_bottom = y + top_gap + dp(if (compact) 21 else 24)
        if (row_bottom > visible_bottom || y < visible_top) return row_bottom

        val left = rect.left + if (compact) 0f else dp(16)
        val right = rect.right - if (compact) 0f else dp(16)
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = if (compact) sp(10) else sp(11)
        text_paint.color = style.visual_theme.colors.accent_blue
        val value_width = if (row.value.isBlank()) 0f else (right - left) * 0.56f
        draw_fitted_left_text(
            canvas,
            row.label.uppercase(Locale.US),
            left,
            y + top_gap,
            right - left - value_width - if (row.value.isBlank()) 0f else dp(12),
            text_paint.textSize,
            if (compact) sp(8) else sp(9)
        )
        if (row.value.isNotBlank()) {
            text_paint.color = style.visual_theme.colors.text
            draw_fitted_right_text(
                canvas,
                row.value,
                right,
                y + top_gap,
                value_width,
                if (compact) sp(12) else sp(13),
                if (compact) sp(8) else sp(9)
            )
        }

        stroke_paint.color = with_alpha(
            style.visual_theme.colors.panel_stroke,
            style.visual_theme.style.divider_alpha + 42
        )
        stroke_paint.strokeWidth = dp(1)
        canvas.drawLine(left, y + top_gap + dp(8), right, y + top_gap + dp(8), stroke_paint)
        text_paint.isFakeBoldText = false
        return row_bottom
    }

    private fun draw_evidence_text_line(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        label: String,
        value: String,
        style: AircraftDetailsPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(label.uppercase(Locale.US), rect.left, y, text_paint)
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        val lines = wrapped_text_lines(value, rect.width(), DETAILS_EVIDENCE_LINE_MAX_LINES)
        var cy = y + dp(20)
        lines.forEach { line ->
            canvas.drawText(line, rect.left, cy, text_paint)
            cy += dp(18)
        }
        text_paint.isFakeBoldText = false
        return cy + dp(10)
    }

    private fun draw_photo_caption(
        canvas: Canvas,
        photo_rect: RectF,
        caption: String,
        style: AircraftDetailsPanelStyle
    ) {
        if (caption.isBlank()) return
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(10)
        val max_width = photo_rect.width() - dp(18)
        val line_height = dp(13)
        val max_lines =
            max(1, floor((photo_rect.height() - dp(12)) / line_height).toInt()).coerceAtMost(
                PHOTO_CAPTION_MAX_LINES
            )
        val lines = wrapped_text_lines(caption, max_width, max_lines)
        val caption_height =
            (dp(12) + lines.size * line_height).coerceAtMost(photo_rect.height() - dp(8))
        val caption_rect = RectF(
            photo_rect.left,
            photo_rect.bottom - caption_height,
            photo_rect.right,
            photo_rect.bottom
        )
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(
            style.visual_theme.colors.panel,
            if (style.visual_theme.style.treatment == ThemeTreatment.PLAIN) 190 else 216
        )
        val radius =
            if (style.visual_theme.style.treatment == ThemeTreatment.PLAIN) dp(4) else chrome.control_radius()
                .coerceAtMost(dp(8))
        canvas.drawRoundRect(caption_rect, radius, radius, paint)
        text_paint.color = style.visual_theme.colors.text
        val metrics = text_paint.fontMetrics
        var y = caption_rect.top + dp(6) - metrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, caption_rect.centerX(), y, text_paint)
            y += line_height
        }
    }

    private fun draw_centered_message(
        canvas: Canvas,
        message: String,
        rect: RectF,
        style: AircraftDetailsPanelStyle
    ) {
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.muted
        draw_centered_wrapped_text(canvas, message, rect)
    }

    private fun draw_centered_wrapped_text(canvas: Canvas, value: String, rect: RectF) {
        val lines = wrapped_text_lines(value, rect.width(), PHOTO_UNAVAILABLE_LINES)
        val metrics = text_paint.fontMetrics
        val line_height = text_paint.fontSpacing
        val block_height = line_height * lines.size
        var y = rect.centerY() - block_height / 2f - metrics.ascent
        lines.forEach { line ->
            canvas.drawText(line, rect.centerX(), y, text_paint)
            y += line_height
        }
    }

    private fun draw_wrapped_text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        max_lines: Int
    ): Float =
        draw_wrapped_text(canvas, text_paint, value, x, y, width, max_lines, dp(19))

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

    // Long words are split by the shared text primitive so every panel wraps identically.
    private fun wrapped_text_lines(value: String, width: Float, max_lines: Int): List<String> =
        wrapped_text_lines(text_paint, value, width, max_lines)

    private fun photo_bounds(area: RectF, wide: Boolean, has_photo: Boolean): RectF {
        return if (wide) {
            val height = if (has_photo) min(area.height(), dp(280)) else min(area.height(), dp(104))
            RectF(area.left + dp(18), area.top, area.right - dp(18), area.top + height)
        } else {
            val height = if (has_photo) dp(206) else dp(86)
            RectF(
                area.left + dp(18),
                area.top + dp(66),
                area.right - dp(18),
                area.top + dp(66) + height
            )
        }
    }

    private fun content_bounds(panel: RectF, reserve_bottom: Float = dp(12)): RectF {
        return RectF(panel.left, panel.top + dp(62), panel.right, panel.bottom - reserve_bottom)
    }

    private fun row_bounds(panel: RectF): RectF {
        return RectF(panel.left, panel.top, panel.right, panel.bottom)
    }

    // Return corrected scroll values to FlightMapView so touch scrolling and drawing stay in sync.
    private fun scroll_result(
        content_height: Float,
        viewport_height: Float,
        requested_scroll_y: Float
    ): AircraftDetailsDrawResult {
        val max_scroll_y = max(0f, content_height - viewport_height)
        return AircraftDetailsDrawResult(
            scroll_y = requested_scroll_y.coerceIn(0f, max_scroll_y),
            max_scroll_y = max_scroll_y
        )
    }

    private fun draw_scroll_indicator(
        canvas: Canvas,
        clip: RectF,
        result: AircraftDetailsDrawResult,
        style: AircraftDetailsPanelStyle
    ) {
        if (result.max_scroll_y <= dp(2)) return
        val track =
            RectF(clip.right - dp(6), clip.top + dp(8), clip.right - dp(3), clip.bottom - dp(8))
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.visual_theme.colors.text, 54)
        canvas.drawRoundRect(track, dp(2), dp(2), paint)
        val thumb_height =
            (track.height() * (clip.height() / (clip.height() + result.max_scroll_y))).coerceIn(
                dp(24), track.height()
            )
        val top =
            track.top + (track.height() - thumb_height) * (result.scroll_y / result.max_scroll_y)
        paint.color = with_alpha(style.visual_theme.colors.accent_blue, 170)
        canvas.drawRoundRect(
            RectF(track.left, top, track.right, top + thumb_height),
            dp(2),
            dp(2),
            paint
        )
    }

    private fun aspect_fit_rect(source_width: Int, source_height: Int, outer: RectF): RectF {
        if (source_width <= 0 || source_height <= 0 || outer.width() <= 0f || outer.height() <= 0f) return RectF(
            outer
        )
        val source_ratio = source_width.toFloat() / source_height
        val outer_ratio = outer.width() / outer.height()
        return if (source_ratio > outer_ratio) {
            val fitted_height = outer.width() / source_ratio
            val top = outer.centerY() - fitted_height / 2f
            RectF(outer.left, top, outer.right, top + fitted_height)
        } else {
            val fitted_width = outer.height() * source_ratio
            val left = outer.centerX() - fitted_width / 2f
            RectF(left, outer.top, left + fitted_width, outer.bottom)
        }
    }

    private fun RectF.inset_copy(dx: Float, dy: Float): RectF {
        return RectF(left + dx, top + dy, right - dx, bottom - dy)
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = chrome.dp(value)

    private fun sp(value: Int): Float = sp(value.toFloat())

    private fun sp(value: Float): Float = chrome.sp(value)

    private companion object {
        const val PHOTO_UNAVAILABLE_LINES = 4
        const val PHOTO_CAPTION_MAX_LINES = 7
        const val DETAILS_ROW_MAX_LINES = 12
        const val DETAILS_IMPACT_STATUS_MAX_LINES = 4
        const val DETAILS_IMPACT_METRIC_LABEL_MAX_LINES = 2
        const val DETAILS_IMPACT_METRIC_VALUE_MAX_LINES = 4
        const val DETAILS_IMPACT_ROW_LABEL_MAX_LINES = 2
        const val DETAILS_IMPACT_ROW_VALUE_MAX_LINES = 12
        const val DETAILS_EVIDENCE_LINE_MAX_LINES = 4
        const val DETAILS_PROOF_QUOTE_LINES = 12
    }
}
data class AircraftDetailsPanelStyle(val visual_theme: VisualTheme)

data class AircraftDetailsRow(
    val label: String,
    val value: String,
    val section: Boolean = false
) {
    companion object {
        fun section(label: String, value: String = ""): AircraftDetailsRow =
            AircraftDetailsRow(label, value, section = true)
    }
}

data class AircraftDetailsPhotoState(
    val bitmap: Bitmap?,
    val status: String,
    val previous_bitmap: Bitmap? = null,
    val transition_progress: Float = 1f
)

data class AircraftDetailsPanelState(
    val content: AircraftDetailsPanelContent,
    val photo: AircraftDetailsPhotoState,
    val scroll_y: Float,
    val wide_layout: Boolean
)

sealed interface AircraftDetailsPanelContent

data class AircraftDetailsMainState(
    val title: String,
    val rows: List<AircraftDetailsRow>,
    val has_aircraft: Boolean,
    val has_usage_trace: Boolean
) : AircraftDetailsPanelContent

data class AircraftImpactPanelState(
    val selected_aircraft_available: Boolean,
    val status: String,
    val show_trace_co2: Boolean,
    val co2_text: String,
    val score_label: String,
    val score_text: String,
    val score_color: Int,
    val rows: List<AircraftDetailsRow>
) : AircraftDetailsPanelContent

data class AircraftUsagePanelState(
    val selected_aircraft_available: Boolean,
    val status: String,
    val unavailable_message: String?,
    val stat_rows: List<AircraftDetailsRow>,
    val stats: UsageStats?
) : AircraftDetailsPanelContent

data class AircraftPhotoEvidencePanelState(
    val evidence: PhotoEvidence?
) : AircraftDetailsPanelContent

data class AircraftPhotoGalleryPanelState(
    val items: List<AircraftPhotoGalleryItem>,
    val status: String,
    val loading: Boolean
) : AircraftDetailsPanelContent

data class AircraftDetailsDrawResult(
    val scroll_y: Float,
    val max_scroll_y: Float
)

interface AircraftDetailsPanelChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun control_radius(): Float
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
    fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean)
}
