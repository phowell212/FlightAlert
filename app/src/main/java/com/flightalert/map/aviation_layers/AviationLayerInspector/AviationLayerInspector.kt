@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max


internal class AviationLayerInspector(
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String,
    private val draw_panel_surface: (Canvas, RectF, Int, Int) -> Unit,
    private val draw_choice_button: (Canvas, RectF, String, Boolean) -> Unit,
    private val draw_wrapped_text: (Canvas, String, Float, Float, Float, Int) -> Float
) {
    fun draw_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot,
        now_epoch_ms: Long,
        scroll_y: Float,
        style: AviationLayerInspectorStyle
    ): AviationInspectorDrawResult {
        val presentation = AviationSelectionPresentationPolicy.build(
            selection,
            snapshot,
            now_epoch_ms
        )
        val panel = panel_bounds(w, h, selection.kind)
        val accent = accent_for(selection, style)
        draw_panel_surface(canvas, panel, style.panel_color, style.modal_panel_alpha)
        draw_choice_button(canvas, close_button_bounds(panel), "Close", false)
        draw_fixed_header(canvas, panel, selection.kind, presentation, accent, style)

        val body = body_bounds(panel)
        val requested_scroll = scroll_y.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val checkpoint = canvas.save()
        canvas.clipRect(body)
        canvas.translate(0f, -requested_scroll)
        val content_bottom = draw_body(
            canvas = canvas,
            body = body,
            selection = selection,
            presentation = presentation,
            accent = accent,
            style = style
        )
        canvas.restoreToCount(checkpoint)

        val content_height = content_bottom - body.top + dp(12f)
        val max_scroll = (content_height - body.height()).coerceAtLeast(0f)
        val layout = AviationInspectorScrollPolicy.layout(requested_scroll, max_scroll)
        draw_scroll_indicator(canvas, body, layout, style)
        return AviationInspectorDrawResult(layout.scroll_y, layout.max_scroll_y)
    }

    fun panel_bounds(w: Float, h: Float, kind: AviationLayerKind): RectF =
        AviationInspectorPanelPolicy.bounds(w, h, dp(1f), kind).let { bounds ->
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }

    fun close_button_bounds(panel: RectF): RectF = RectF(
        panel.right - dp(118f),
        panel.top + dp(14f),
        panel.right - dp(18f),
        panel.top + dp(48f)
    )

    fun body_bounds(panel: RectF): RectF = RectF(
        panel.left + dp(2f),
        panel.top + dp(70f),
        panel.right - dp(2f),
        panel.bottom - dp(10f)
    )

    private fun draw_fixed_header(
        canvas: Canvas,
        panel: RectF,
        kind: AviationLayerKind,
        presentation: AviationSelectionPresentation,
        accent: Int,
        style: AviationLayerInspectorStyle
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(22f)
        text_paint.color = style.text_color
        val title_left = panel.left + dp(18f)
        canvas.drawText(
            ellipsize(
                presentation.title,
                AviationInspectorPanelPolicy.title_max_width(
                    panel_width = panel.width(),
                    density = dp(1f),
                    kind = kind
                )
            ),
            title_left,
            panel.top + dp(34f),
            text_paint
        )

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11f)
        text_paint.color = accent
        canvas.drawText(
            ellipsize(presentation.subtitle, panel.width() - dp(142f)),
            title_left,
            panel.top + dp(55f),
            text_paint
        )
    }

    private fun draw_body(
        canvas: Canvas,
        body: RectF,
        selection: AviationSelection,
        presentation: AviationSelectionPresentation,
        accent: Int,
        style: AviationLayerInspectorStyle
    ): Float {
        var y = body.top + if (selection is AviationSelection.SpecialUse) dp(18f) else dp(22f)
        AviationInspectorContentPolicy.sections(selection.kind).forEach { section ->
            y = when (section) {
                AviationInspectorContentSection.SELECTION_ROWS -> {
                    if (selection is AviationSelection.SpecialUse) {
                        draw_legacy_special_use_rows(canvas, body, y, presentation.rows, style)
                    } else {
                        draw_selection_rows(canvas, body, y, presentation.rows, style)
                    }
                }
                AviationInspectorContentSection.PROVENANCE ->
                    draw_provenance(canvas, body, y, presentation.provenance, accent, style)
            }
        }
        return y
    }

    private fun draw_provenance(
        canvas: Canvas,
        body: RectF,
        start_y: Float,
        provenance: AviationProvenancePresentation,
        accent: Int,
        style: AviationLayerInspectorStyle
    ): Float {
        val left = body.left + dp(16f)
        val width = body.width() - dp(32f)
        var y = start_y
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13f)
        text_paint.color = accent
        y = draw_wrapped_text(
            canvas,
            provenance.banner,
            left,
            y,
            width,
            3
        ) + dp(8f)

        provenance.latest_refresh?.let { latest ->
            y = draw_detail_row(canvas, left, width, y, "Latest refresh", latest, style)
        }
        y = draw_detail_row(
            canvas,
            left,
            width,
            y,
            "Displayed source",
            provenance.source,
            style
        )
        y = draw_detail_row(
            canvas,
            left,
            width,
            y,
            "Observed UTC",
            provenance.observed_utc,
            style
        ) + dp(5f)
        return y
    }

    private fun draw_selection_rows(
        canvas: Canvas,
        body: RectF,
        start_y: Float,
        rows: List<AviationDetailRow>,
        style: AviationLayerInspectorStyle
    ): Float {
        val left = body.left + dp(16f)
        val width = body.width() - dp(32f)
        var y = start_y
        rows.forEach { row ->
            y = draw_detail_row(canvas, left, width, y, row.label, row.value, style)
        }
        return y
    }

    private fun draw_legacy_special_use_rows(
        canvas: Canvas,
        body: RectF,
        start_y: Float,
        rows: List<AviationDetailRow>,
        style: AviationLayerInspectorStyle
    ): Float {
        var y = start_y
        rows.forEach { row ->
            text_paint.textAlign = Paint.Align.LEFT
            text_paint.isFakeBoldText = true
            text_paint.textSize = sp(12f)
            text_paint.color = style.muted_color
            val label_x = body.left + dp(16f)
            canvas.drawText(row.label.uppercase(Locale.US), label_x, y, text_paint)

            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(14f)
            text_paint.color = style.text_color
            val value_x = body.left + dp(110f)
            val max_width = body.right - value_x - dp(16f)
            val bottom = draw_wrapped_text(
                canvas,
                row.value,
                value_x,
                y,
                max_width,
                2
            )
            y = max(bottom + dp(9f), y + dp(30f))
        }
        return y + dp(6f)
    }

    private fun draw_detail_row(
        canvas: Canvas,
        left: Float,
        width: Float,
        y: Float,
        label: String,
        value: String,
        style: AviationLayerInspectorStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11f)
        text_paint.color = style.muted_color
        canvas.drawText(label.uppercase(Locale.US), left, y, text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(14f)
        text_paint.color = style.text_color
        val bottom = draw_wrapped_text(
            canvas,
            value,
            left,
            y + dp(20f),
            width,
            MAX_DETAIL_LINES
        )
        return max(bottom + dp(10f), y + dp(42f))
    }

    private fun draw_scroll_indicator(
        canvas: Canvas,
        body: RectF,
        layout: AviationInspectorScrollLayout,
        style: AviationLayerInspectorStyle
    ) {
        if (layout.max_scroll_y <= 0f) return
        val viewport_fraction = body.height() / (body.height() + layout.max_scroll_y)
        val thumb_height = (body.height() * viewport_fraction).coerceAtLeast(dp(24f))
        val travel = body.height() - thumb_height
        val progress = layout.scroll_y / layout.max_scroll_y
        val top = body.top + travel * progress
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = dp(2f)
        text_paint.color = style.muted_color
        canvas.drawLine(body.right - dp(5f), top, body.right - dp(5f), top + thumb_height, text_paint)
        text_paint.style = Paint.Style.FILL
    }

    private fun accent_for(
        selection: AviationSelection,
        style: AviationLayerInspectorStyle
    ): Int = when (selection) {
        is AviationSelection.SpecialUse -> style.accent_orange_color
        is AviationSelection.AtcBoundary -> style.accent_blue_color
        is AviationSelection.OceanicTrack -> style.accent_pink_color
        is AviationSelection.Airport ->
            if (selection.airport.military) style.military_gray_color else style.accent_yellow_color
    }

    private companion object {
        const val MAX_DETAIL_LINES = 64
    }
}

