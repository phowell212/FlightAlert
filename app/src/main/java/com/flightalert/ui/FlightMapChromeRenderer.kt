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

package com.flightalert.ui

import com.flightalert.VisualTheme
import com.flightalert.ThemeTreatment
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import com.flightalert.aircraft.Aircraft
import com.flightalert.details.AircraftDetailsPanelChrome
import com.flightalert.map.ScaleLabel
import com.flightalert.traffic.TrafficOverlayChrome
import java.util.LinkedHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class FlightMapChromeBridge(
    override val layout: FlightMapLayout,
    private val dp_value: (Float) -> Float,
    private val sp_value: (Float) -> Float,
    private val fit_text: (String, Float) -> String,
    private val control_radius_value: () -> Float,
    private val panel_surface: (Canvas, RectF, Int, Int) -> Unit,
    private val choice_button: (Canvas, RectF, String, Boolean) -> Unit,
    private val control_surface: (Canvas, RectF, Int, Int, Boolean) -> Unit,
    private val wrapped_text: (Canvas, String, Float, Float, Float, Int) -> Float,
    private val aircraft_label: (Aircraft) -> String,
    private val animation_frame: () -> Unit
) : FlightMapChromeHost,
    FlightMapPanelChrome,
    AircraftDetailsPanelChrome,
    TrafficPanelChrome,
    TrafficOverlayChrome {

    override fun dp(value: Float): Float = dp_value(value)

    override fun sp(value: Float): Float = sp_value(value)

    override fun ellipsize(value: String, max_width: Float): String = fit_text(value, max_width)

    override fun control_radius(): Float = control_radius_value()

    override fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int) {
        panel_surface(canvas, rect, fill, alpha)
    }

    override fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean) {
        choice_button(canvas, rect, label, selected)
    }

    override fun draw_control_surface(
        canvas: Canvas,
        rect: RectF,
        fill: Int,
        stroke: Int,
        selected: Boolean
    ) {
        control_surface(canvas, rect, fill, stroke, selected)
    }

    override fun draw_wrapped_text(
        canvas: Canvas,
        value: String,
        x: Float,
        y: Float,
        width: Float,
        max_lines: Int
    ): Float {
        return wrapped_text(canvas, value, x, y, width, max_lines)
    }

    override fun aircraft_label_detail(aircraft: Aircraft): String = aircraft_label(aircraft)

    override fun request_animation_frame() {
        animation_frame()
    }
}

// Draws shared map chrome: panels, controls, status labels, and theme treatments.
class FlightMapChromeRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val host: FlightMapChromeHost
) {
    private val surface_cache_paint = Paint(Paint.DITHER_FLAG)
    private val surface_cache_rect = RectF()
    private val surface_cache =
        object : LinkedHashMap<ChromeSurfaceKey, Bitmap>(CHROME_SURFACE_CACHE_LIMIT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChromeSurfaceKey, Bitmap>): Boolean {
                val remove = size > CHROME_SURFACE_CACHE_LIMIT
                if (remove && !eldest.value.isRecycled) eldest.value.recycle()
                return remove
            }
        }

    fun panel_radius(style: FlightMapChromeStyle): Float {
        return host.dp(style.visual_theme.style.panel_corner_dp)
    }

    fun control_radius(style: FlightMapChromeStyle): Float {
        return host.dp(style.visual_theme.style.control_corner_dp)
    }

    // Choice buttons shrink text before ellipsizing so labels stay inside compact controls.
    fun draw_choice_button(
        canvas: Canvas,
        rect: RectF,
        label: String,
        selected: Boolean,
        style: FlightMapChromeStyle
    ) {
        val theme_style = style.visual_theme.style
        val colors = style.visual_theme.colors
        val previous_align = text_paint.textAlign
        val color = if (selected) colors.accent_green else colors.button_stroke
        paint.style = Paint.Style.FILL
        val fill = if (selected) {
            with_alpha(color, colors.selected_fill_alpha)
        } else if (theme_style.treatment == ThemeTreatment.PLAIN) {
            colors.button_fill
        } else {
            with_alpha(colors.button_fill, theme_style.control_alpha)
        }
        draw_control_surface(canvas, rect, fill, color, selected, null, style)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = host.sp(12f)
        val available_width = (rect.width() - host.dp(12f)).coerceAtLeast(host.dp(4f))
        val available_height = (rect.height() - host.dp(8f)).coerceAtLeast(host.dp(4f))
        while (text_paint.textSize > host.sp(8f) &&
            (text_paint.measureText(label) > available_width ||
                    text_paint.fontMetrics.let { it.descent - it.ascent } > available_height)
        ) {
            text_paint.textSize -= host.dp(0.5f)
        }
        val display = if (text_paint.measureText(label) <= available_width) {
            label
        } else {
            host.ellipsize(label, available_width)
        }
        text_paint.color = if (selected) colors.accent_green else colors.text
        val metrics = text_paint.fontMetrics
        canvas.drawText(
            display,
            rect.centerX(),
            rect.centerY() - (metrics.ascent + metrics.descent) / 2f,
            text_paint
        )
        text_paint.isFakeBoldText = false
        text_paint.textAlign = previous_align
    }

    // Context controls are icon-only map actions that share one cockpit surface treatment.
    fun draw_context_control(
        canvas: Canvas,
        rect: RectF,
        stroke: Int,
        style: FlightMapChromeStyle
    ) {
        val theme_style = style.visual_theme.style
        val colors = style.visual_theme.colors
        val alpha =
            if (theme_style.treatment == ThemeTreatment.PLAIN) 235 else theme_style.control_alpha
        val stroke_width =
            if (theme_style.treatment == ThemeTreatment.PLAIN) 1.4f else theme_style.control_stroke_dp
        draw_control_surface(
            canvas,
            rect,
            with_alpha(colors.control_fill, alpha),
            stroke,
            false,
            stroke_width,
            style
        )
    }

    // Draw the reusable control surface before icons or text are placed on top.
    fun draw_control_surface(
        canvas: Canvas,
        rect: RectF,
        fill: Int,
        stroke: Int,
        selected: Boolean = false,
        stroke_width_dp: Float? = null,
        style: FlightMapChromeStyle
    ) {
        val theme_style = style.visual_theme.style
        val radius = control_radius(style)
        val stroke_width_px = host.dp(stroke_width_dp ?: theme_style.control_stroke_dp)
        if (
            theme_style.treatment != ThemeTreatment.DAYLIGHT_CARD &&
            draw_cached_surface(
                canvas = canvas,
                rect = rect,
                key = ChromeSurfaceKey(
                    kind = ChromeSurfaceKind.CONTROL,
                    width = rect.width().roundToInt(),
                    height = rect.height().roundToInt(),
                    radius = radius.roundToInt(),
                    fill = fill,
                    stroke = stroke,
                    stroke_width = stroke_width_px.roundToInt(),
                    selected = selected,
                    visual_theme = style.visual_theme
                )
            ) { cache_canvas, cache_rect ->
                draw_control_surface_direct(
                    canvas = cache_canvas,
                    rect = cache_rect,
                    fill = fill,
                    stroke = stroke,
                    selected = selected,
                    stroke_width_px = stroke_width_px,
                    style = style
                )
            }
        ) {
            return
        }
        draw_control_surface_direct(canvas, rect, fill, stroke, selected, stroke_width_px, style)
    }

    private fun draw_control_surface_direct(
        canvas: Canvas,
        rect: RectF,
        fill: Int,
        stroke: Int,
        selected: Boolean,
        stroke_width_px: Float,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val radius = control_radius(style)
        if (theme_style.treatment == ThemeTreatment.DAYLIGHT_CARD) {
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(colors.scrim, 28)
            canvas.drawRoundRect(
                RectF(
                    rect.left + host.dp(1f),
                    rect.top + host.dp(2f),
                    rect.right + host.dp(1f),
                    rect.bottom + host.dp(2f)
                ),
                radius,
                radius,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        draw_control_treatment(canvas, rect, radius, stroke, selected, style)

        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = stroke_width_px
        stroke_paint.color = stroke
        canvas.drawRoundRect(rect, radius, radius, stroke_paint)
    }

    // Draw the reusable panel surface with theme texture and border applied consistently.
    fun draw_panel_surface(
        canvas: Canvas,
        rect: RectF,
        fill: Int? = null,
        alpha: Int? = null,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val radius = panel_radius(style)
        val fill_color = with_alpha(fill ?: colors.panel, alpha ?: theme_style.info_panel_alpha)
        val stroke_width_px = host.dp(theme_style.panel_stroke_dp)
        if (
            theme_style.treatment != ThemeTreatment.DAYLIGHT_CARD &&
            draw_cached_surface(
                canvas = canvas,
                rect = rect,
                key = ChromeSurfaceKey(
                    kind = ChromeSurfaceKind.PANEL,
                    width = rect.width().roundToInt(),
                    height = rect.height().roundToInt(),
                    radius = radius.roundToInt(),
                    fill = fill_color,
                    stroke = colors.panel_stroke,
                    stroke_width = stroke_width_px.roundToInt(),
                    selected = false,
                    visual_theme = style.visual_theme
                )
            ) { cache_canvas, cache_rect ->
                draw_panel_surface_direct(
                    canvas = cache_canvas,
                    rect = cache_rect,
                    fill_color = fill_color,
                    stroke_width_px = stroke_width_px,
                    style = style
                )
            }
        ) {
            return
        }
        draw_panel_surface_direct(canvas, rect, fill_color, stroke_width_px, style)
    }

    private fun draw_panel_surface_direct(
        canvas: Canvas,
        rect: RectF,
        fill_color: Int,
        stroke_width_px: Float,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val radius = panel_radius(style)
        if (theme_style.treatment == ThemeTreatment.DAYLIGHT_CARD) {
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(colors.scrim, 42)
            canvas.drawRoundRect(
                RectF(
                    rect.left + host.dp(2f),
                    rect.top + host.dp(3f),
                    rect.right + host.dp(2f),
                    rect.bottom + host.dp(3f)
                ),
                radius,
                radius,
                paint
            )
        }

        paint.style = Paint.Style.FILL
        paint.color = fill_color
        canvas.drawRoundRect(rect, radius, radius, paint)
        draw_panel_treatment(canvas, rect, radius, style)

        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = stroke_width_px
        stroke_paint.color = colors.panel_stroke
        canvas.drawRoundRect(rect, radius, radius, stroke_paint)
    }

    private fun draw_cached_surface(
        canvas: Canvas,
        rect: RectF,
        key: ChromeSurfaceKey,
        create_surface: (Canvas, RectF) -> Unit
    ): Boolean {
        if (key.width <= 0 || key.height <= 0) return false
        val bitmap = synchronized(surface_cache) {
            surface_cache[key]?.takeIf { !it.isRecycled } ?: create_surface_bitmap(
                key,
                create_surface
            )?.also {
                surface_cache[key] = it
            }
        } ?: return false
        surface_cache_rect.set(rect.left, rect.top, rect.right, rect.bottom)
        canvas.drawBitmap(bitmap, null, surface_cache_rect, surface_cache_paint)
        return true
    }

    private fun create_surface_bitmap(
        key: ChromeSurfaceKey,
        create_surface: (Canvas, RectF) -> Unit
    ): Bitmap? {
        val bitmap = try {
            createBitmap(key.width, key.height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        val cache_canvas = Canvas(bitmap)
        val local_rect = RectF(0f, 0f, key.width.toFloat(), key.height.toFloat())
        create_surface(cache_canvas, local_rect)
        return bitmap
    }

    // Modal backdrop dims the map while keeping the active panel visually attached to the cockpit.
    fun draw_modal_backdrop(canvas: Canvas, w: Float, h: Float, style: FlightMapChromeStyle) {
        val colors = style.visual_theme.colors
        val alpha = when (style.visual_theme.style.treatment) {
            ThemeTreatment.DAYLIGHT_CARD -> 96
            ThemeTreatment.GLASS -> 120
            ThemeTreatment.CRT_SCANLINE -> 148
            else -> 132
        }
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(colors.scrim, alpha)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    // No-location state is intentionally plain: without real ownship location, the map and traffic stay unavailable.
    fun draw_no_location_state(
        canvas: Canvas,
        w: Float,
        h: Float,
        location_permission_granted: Boolean,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        paint.style = Paint.Style.FILL
        paint.color = colors.map_empty
        canvas.drawRect(0f, 0f, w, h, paint)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = host.sp(18f)
        text_paint.color = colors.text
        val message =
            if (location_permission_granted) "Waiting for device location" else "Location permission required"
        canvas.drawText(message, w / 2f, h * 0.45f, text_paint)
        text_paint.isFakeBoldText = false
        text_paint.textSize = host.sp(12f)
        text_paint.color = colors.muted
        canvas.drawText(
            "No map or aircraft will be shown until real location data is available.",
            w / 2f,
            h * 0.45f + host.dp(24f),
            text_paint
        )
    }

    // Top status combines source truth, alert status, and scale into one quick-read card.
    fun draw_top_status(
        canvas: Canvas,
        rect: RectF,
        subtitle: String,
        traffic_status: Pair<String, Int>,
        scale_label: ScaleLabel,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        draw_panel_surface(canvas, rect, colors.panel, theme_style.top_panel_alpha, style)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        val right_status_left = rect.right - host.dp(132f)
        val title_left = rect.left + host.dp(16f)
        text_paint.textSize = host.sp(19f * theme_style.heading_scale)
        text_paint.color = colors.text
        draw_fitted_left_text(
            canvas = canvas,
            value = "Flight Alert",
            left = title_left,
            y = rect.top + host.dp(27f),
            max_width = (right_status_left - host.dp(12f) - title_left).coerceAtLeast(host.dp(36f)),
            start_size = host.sp(19f * theme_style.heading_scale),
            min_size = host.sp(11f)
        )
        text_paint.isFakeBoldText = false
        text_paint.textSize = host.sp(12f)
        text_paint.color = colors.muted

        val subtitle_left = rect.left + host.dp(16f)
        draw_fitted_left_text(
            canvas = canvas,
            value = subtitle,
            left = subtitle_left,
            y = rect.top + host.dp(49f),
            max_width = (right_status_left - host.dp(12f) - subtitle_left).coerceAtLeast(host.dp(36f)),
            start_size = host.sp(12f),
            min_size = host.sp(9f)
        )
        draw_status_label(
            canvas,
            right_status_left,
            rect.top + host.dp(14f),
            host.dp(116f),
            host.dp(26f),
            traffic_status.first,
            traffic_status.second
        )
        draw_scale_label(
            canvas,
            right_status_left,
            rect.top + host.dp(45f),
            host.dp(116f),
            host.dp(17f),
            scale_label,
            style
        )
    }

    // Recenter uses the same context-control surface as path controls so the top toolbar stays predictable.
    fun draw_recenter_button(canvas: Canvas, rect: RectF, style: FlightMapChromeStyle) {
        val color = style.visual_theme.colors.accent_green
        draw_context_control(canvas, rect, color, style)
        draw_locate_icon(canvas, rect.centerX(), rect.centerY(), color)
    }

    // Path buttons choose their icon from the command label while keeping the styling caller-controlled.
    fun draw_flight_path_button(
        canvas: Canvas,
        rect: RectF,
        label: String,
        color: Int,
        style: FlightMapChromeStyle
    ) {
        draw_context_control(canvas, rect, color, style)
        when (label) {
            "Clear" -> draw_clear_icon(canvas, rect.centerX(), rect.centerY(), color)
            "History" -> draw_history_icon(canvas, rect.centerX(), rect.centerY(), color)
            else -> draw_path_fit_icon(canvas, rect.centerX(), rect.centerY(), color)
        }
    }

    // Settings button keeps text plus icon because it is a top-level panel opener.
    fun draw_settings_button(canvas: Canvas, bounds: RectF, style: FlightMapChromeStyle) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val stroke = if (theme_style.treatment == ThemeTreatment.PLAIN) with_alpha(
            colors.control_stroke,
            155
        ) else colors.control_stroke
        val fill_alpha =
            if (theme_style.treatment == ThemeTreatment.PLAIN) 228 else theme_style.control_alpha
        val stroke_width =
            if (theme_style.treatment == ThemeTreatment.PLAIN) 1f else theme_style.control_stroke_dp
        draw_control_surface(
            canvas,
            bounds,
            with_alpha(colors.control_fill, fill_alpha),
            stroke,
            false,
            stroke_width,
            style
        )
        draw_gear_icon(canvas, bounds.centerX(), bounds.centerY() - host.dp(4f), colors.accent_blue)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = host.sp(8f)
        text_paint.color = colors.text
        canvas.drawText("Settings", bounds.centerX(), bounds.bottom - host.dp(6f), text_paint)
    }

    // Filters button changes label and accent only when a filter is actively changing visible traffic.
    fun draw_filters_button(
        canvas: Canvas,
        bounds: RectF,
        active: Boolean,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val stroke = when {
            active -> colors.accent_orange
            theme_style.treatment == ThemeTreatment.PLAIN -> with_alpha(colors.control_stroke, 155)
            else -> colors.control_stroke
        }
        val fill_alpha =
            if (theme_style.treatment == ThemeTreatment.PLAIN) 228 else theme_style.control_alpha
        val fill = if (active) {
            with_alpha(colors.accent_orange, colors.selected_fill_alpha)
        } else {
            with_alpha(colors.control_fill, fill_alpha)
        }
        val stroke_width =
            if (theme_style.treatment == ThemeTreatment.PLAIN) 1f else theme_style.control_stroke_dp
        draw_control_surface(canvas, bounds, fill, stroke, active, stroke_width, style)
        draw_filter_icon(
            canvas,
            bounds.centerX(),
            bounds.centerY() - host.dp(4f),
            if (active) colors.accent_orange else colors.accent_blue
        )
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = host.sp(8f)
        text_paint.color = if (active) colors.accent_orange else colors.text
        canvas.drawText(
            if (active) "Filtered" else "Filters",
            bounds.centerX(),
            bounds.bottom - host.dp(6f),
            text_paint
        )
    }

    fun draw_gear_icon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(2f)
        paint.color = color
        canvas.drawCircle(cx, cy, host.dp(8f), paint)
        canvas.drawCircle(cx, cy, host.dp(3f), paint)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            canvas.drawLine(
                cx + cos(angle).toFloat() * host.dp(10f),
                cy + sin(angle).toFloat() * host.dp(10f),
                cx + cos(angle).toFloat() * host.dp(13f),
                cy + sin(angle).toFloat() * host.dp(13f),
                paint
            )
        }
    }

    fun draw_filter_icon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(2.2f)
        paint.color = color
        path.reset()
        path.moveTo(cx - host.dp(13f), cy - host.dp(10f))
        path.lineTo(cx + host.dp(13f), cy - host.dp(10f))
        path.lineTo(cx + host.dp(4f), cy)
        path.lineTo(cx + host.dp(4f), cy + host.dp(10f))
        path.lineTo(cx - host.dp(4f), cy + host.dp(14f))
        path.lineTo(cx - host.dp(4f), cy)
        path.close()
        canvas.drawPath(path, paint)
    }

    fun draw_locate_icon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(2f)
        paint.color = color
        canvas.drawCircle(cx, cy, host.dp(12f), paint)
        canvas.drawLine(cx, cy - host.dp(18f), cx, cy - host.dp(13f), paint)
        canvas.drawLine(cx, cy + host.dp(13f), cx, cy + host.dp(18f), paint)
        canvas.drawLine(cx - host.dp(18f), cy, cx - host.dp(13f), cy, paint)
        canvas.drawLine(cx + host.dp(13f), cy, cx + host.dp(18f), cy, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawCircle(cx, cy, host.dp(4f), paint)
    }

    fun draw_path_fit_icon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(2f)
        paint.color = color
        val size = host.dp(15f)
        canvas.drawRect(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f, paint)
        canvas.drawLine(
            cx - host.dp(12f),
            cy - host.dp(12f),
            cx - host.dp(6f),
            cy - host.dp(12f),
            paint
        )
        canvas.drawLine(
            cx - host.dp(12f),
            cy - host.dp(12f),
            cx - host.dp(12f),
            cy - host.dp(6f),
            paint
        )
        canvas.drawLine(
            cx + host.dp(12f),
            cy + host.dp(12f),
            cx + host.dp(6f),
            cy + host.dp(12f),
            paint
        )
        canvas.drawLine(
            cx + host.dp(12f),
            cy + host.dp(12f),
            cx + host.dp(12f),
            cy + host.dp(6f),
            paint
        )
    }

    fun draw_history_icon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(2.1f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = color
        path.reset()
        path.moveTo(cx - host.dp(12f), cy + host.dp(8f))
        path.cubicTo(
            cx - host.dp(5f),
            cy - host.dp(11f),
            cx + host.dp(6f),
            cy + host.dp(12f),
            cx + host.dp(12f),
            cy - host.dp(8f)
        )
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - host.dp(12f), cy + host.dp(8f), host.dp(3f), paint)
        canvas.drawCircle(cx, cy, host.dp(2.6f), paint)
        canvas.drawCircle(cx + host.dp(12f), cy - host.dp(8f), host.dp(3f), paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeJoin = Paint.Join.MITER
    }

    fun draw_clear_icon(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = host.dp(2.4f)
        paint.color = color
        canvas.drawCircle(cx, cy, host.dp(12f), paint)
        canvas.drawLine(
            cx - host.dp(6f),
            cy - host.dp(6f),
            cx + host.dp(6f),
            cy + host.dp(6f),
            paint
        )
        canvas.drawLine(
            cx + host.dp(6f),
            cy - host.dp(6f),
            cx - host.dp(6f),
            cy + host.dp(6f),
            paint
        )
    }

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
        host.dp(0.5f),
        host::ellipsize
    )

    private fun draw_status_label(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        label: String,
        color: Int
    ) {
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(34, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = if (height > host.dp(20f)) host.sp(10f) else host.sp(9f)
        text_paint.color = color
        val max_width = (rect.width() - host.dp(12f)).coerceAtLeast(host.dp(8f))
        while (text_paint.textSize > host.sp(7f) && text_paint.measureText(label) > max_width) {
            text_paint.textSize -= host.dp(0.5f)
        }
        val display = if (text_paint.measureText(label) <= max_width) label else host.ellipsize(
            label,
            max_width
        )
        val metrics = text_paint.fontMetrics
        canvas.drawText(
            display,
            rect.centerX(),
            rect.centerY() - (metrics.ascent + metrics.descent) / 2f,
            text_paint
        )
        text_paint.isFakeBoldText = false
    }

    private fun draw_scale_label(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        scale: ScaleLabel,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val rect = RectF(x, y, x + width, y + height)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(
            34,
            Color.red(colors.accent_yellow),
            Color.green(colors.accent_yellow),
            Color.blue(colors.accent_yellow)
        )
        canvas.drawRoundRect(rect, height / 2f, height / 2f, paint)

        val line_left = rect.left + host.dp(9f)
        val line_width = scale.pixels.coerceIn(host.dp(18f), width * 0.44f)
        val line_right = line_left + line_width
        val line_y = rect.centerY()
        stroke_paint.color = colors.accent_yellow
        stroke_paint.strokeWidth = host.dp(1.2f)
        canvas.drawLine(line_left, line_y, line_right, line_y, stroke_paint)
        canvas.drawLine(
            line_left,
            line_y - host.dp(3f),
            line_left,
            line_y + host.dp(3f),
            stroke_paint
        )
        canvas.drawLine(
            line_right,
            line_y - host.dp(3f),
            line_right,
            line_y + host.dp(3f),
            stroke_paint
        )

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = host.sp(9f)
        text_paint.color = colors.accent_yellow
        draw_fitted_left_text(
            canvas = canvas,
            value = scale.label,
            left = line_right + host.dp(7f),
            y = rect.centerY() - (text_paint.fontMetrics.ascent + text_paint.fontMetrics.descent) / 2f,
            max_width = (rect.right - line_right - host.dp(13f)).coerceAtLeast(host.dp(16f)),
            start_size = host.sp(9f),
            min_size = host.sp(7f)
        )
        text_paint.isFakeBoldText = false
    }

    // Control treatments add theme texture without changing the control's hit rectangle or label contract.
    private fun draw_control_treatment(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        stroke: Int,
        selected: Boolean,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        if (theme_style.texture_alpha <= 0) return
        val alpha = if (selected) max(theme_style.texture_alpha, 46) else theme_style.texture_alpha
        when (theme_style.treatment) {
            ThemeTreatment.PLAIN -> Unit
            ThemeTreatment.GLASS -> {
                val glass_accent = if (selected) stroke else colors.accent_blue
                val clip = Path()
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                val save = canvas.save()
                canvas.clipPath(clip)

                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.bottom,
                    intArrayOf(
                        with_alpha(Color.WHITE, (alpha * 0.78f).roundToInt()),
                        with_alpha(glass_accent, (alpha * 0.24f).roundToInt()),
                        with_alpha(Color.WHITE, (alpha * 0.18f).roundToInt())
                    ),
                    floatArrayOf(0f, 0.56f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect, paint)
                paint.shader = null

                val shine_height =
                    min(rect.height() * 0.42f, host.dp(18f)).coerceAtLeast(host.dp(7f))
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.top + shine_height,
                    with_alpha(Color.WHITE, (alpha * 0.64f).roundToInt()),
                    with_alpha(Color.WHITE, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + shine_height, paint)
                paint.shader = null

                paint.color = with_alpha(glass_accent, (alpha * 0.40f).roundToInt())
                canvas.drawRect(
                    rect.left,
                    rect.top + host.dp(2f),
                    rect.left + host.dp(2f),
                    rect.bottom - host.dp(2f),
                    paint
                )
                paint.color = with_alpha(Color.WHITE, (alpha * 0.18f).roundToInt())
                canvas.drawRect(
                    rect.right - host.dp(1.2f),
                    rect.top + radius * 0.44f,
                    rect.right,
                    rect.bottom - radius * 0.44f,
                    paint
                )

                val inset = host.dp(1.1f)
                val inner = RectF(
                    rect.left + inset,
                    rect.top + inset,
                    rect.right - inset,
                    rect.bottom - inset
                )
                val inner_radius = max(0f, radius - inset)
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = host.dp(0.75f)
                stroke_paint.color = with_alpha(Color.WHITE, (alpha * 0.78f).roundToInt())
                canvas.drawRoundRect(inner, inner_radius, inner_radius, stroke_paint)

                stroke_paint.strokeWidth = host.dp(0.9f)
                val line_inset =
                    min(radius * 0.86f, rect.width() * 0.22f).coerceAtLeast(host.dp(7f))
                stroke_paint.color = with_alpha(glass_accent, (alpha * 0.95f).roundToInt())
                canvas.drawLine(
                    rect.left + line_inset,
                    rect.top + host.dp(4f),
                    rect.right - line_inset,
                    rect.top + host.dp(4f),
                    stroke_paint
                )
                stroke_paint.strokeWidth = host.dp(0.7f)
                stroke_paint.color = with_alpha(Color.WHITE, (alpha * 0.34f).roundToInt())
                canvas.drawLine(
                    rect.left + line_inset,
                    rect.bottom - host.dp(4f),
                    rect.right - line_inset,
                    rect.bottom - host.dp(4f),
                    stroke_paint
                )
                canvas.restoreToCount(save)
            }

            ThemeTreatment.RADAR_GRID -> {
                stroke_paint.strokeWidth = host.dp(1f)
                stroke_paint.color = with_alpha(stroke, alpha)
                val inset = host.dp(5f)
                val tick = min(rect.width(), rect.height()) * 0.22f
                canvas.drawLine(
                    rect.left + inset,
                    rect.top + inset,
                    rect.left + inset + tick,
                    rect.top + inset,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.left + inset,
                    rect.top + inset,
                    rect.left + inset,
                    rect.top + inset + tick,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.right - inset - tick,
                    rect.top + inset,
                    rect.right - inset,
                    rect.top + inset,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.right - inset,
                    rect.top + inset,
                    rect.right - inset,
                    rect.top + inset + tick,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.left + inset,
                    rect.bottom - inset,
                    rect.left + inset + tick,
                    rect.bottom - inset,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.left + inset,
                    rect.bottom - inset - tick,
                    rect.left + inset,
                    rect.bottom - inset,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.right - inset - tick,
                    rect.bottom - inset,
                    rect.right - inset,
                    rect.bottom - inset,
                    stroke_paint
                )
                canvas.drawLine(
                    rect.right - inset,
                    rect.bottom - inset - tick,
                    rect.right - inset,
                    rect.bottom - inset,
                    stroke_paint
                )
            }

            ThemeTreatment.DAYLIGHT_CARD -> {
                paint.style = Paint.Style.FILL
                paint.color = with_alpha(stroke, alpha)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + host.dp(3f), paint)
                paint.color = with_alpha(colors.accent_pink, (alpha * 0.65f).roundToInt())
                canvas.drawRect(
                    rect.left,
                    rect.bottom - host.dp(2f),
                    rect.right,
                    rect.bottom,
                    paint
                )
            }

            ThemeTreatment.STORM_BAND -> {
                paint.style = Paint.Style.FILL
                paint.color = with_alpha(stroke, alpha)
                canvas.drawRect(rect.left, rect.top, rect.left + host.dp(4f), rect.bottom, paint)
                stroke_paint.strokeWidth = host.dp(1.2f)
                stroke_paint.color = with_alpha(colors.accent_orange, (alpha * 0.85f).roundToInt())
                canvas.drawLine(
                    rect.right - host.dp(15f),
                    rect.top + host.dp(7f),
                    rect.right - host.dp(7f),
                    rect.bottom - host.dp(7f),
                    stroke_paint
                )
            }

            ThemeTreatment.CRT_SCANLINE -> {
                stroke_paint.strokeWidth = host.dp(0.7f)
                stroke_paint.color = with_alpha(colors.accent_green, (alpha * 0.9f).roundToInt())
                var y = rect.top + host.dp(5f)
                while (y < rect.bottom - host.dp(2f)) {
                    canvas.drawLine(
                        rect.left + host.dp(3f),
                        y,
                        rect.right - host.dp(3f),
                        y,
                        stroke_paint
                    )
                    y += host.dp(10f)
                }
                stroke_paint.strokeWidth = host.dp(0.8f)
                stroke_paint.color = with_alpha(colors.text, (alpha * 0.55f).roundToInt())
                canvas.drawRect(
                    rect.left + host.dp(3f),
                    rect.top + host.dp(3f),
                    rect.right - host.dp(3f),
                    rect.bottom - host.dp(3f),
                    stroke_paint
                )
            }
        }
    }

    // Panel treatments are visual-only layers that keep map/provider attribution and data states untouched.
    private fun draw_panel_treatment(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        style: FlightMapChromeStyle
    ) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        if (theme_style.texture_alpha <= 0) return
        when (theme_style.treatment) {
            ThemeTreatment.PLAIN -> Unit
            ThemeTreatment.GLASS -> {
                val clip = Path()
                clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                val save = canvas.save()
                canvas.clipPath(clip)

                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.bottom,
                    intArrayOf(
                        with_alpha(Color.WHITE, (theme_style.texture_alpha * 0.58f).roundToInt()),
                        with_alpha(
                            colors.accent_blue,
                            (theme_style.texture_alpha * 0.16f).roundToInt()
                        ),
                        with_alpha(Color.WHITE, (theme_style.texture_alpha * 0.10f).roundToInt())
                    ),
                    floatArrayOf(0f, 0.56f, 1f),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect, paint)
                paint.shader = null

                val shine_height = min(host.dp(78f), max(host.dp(28f), rect.height() * 0.16f))
                paint.shader = LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.top + shine_height,
                    with_alpha(Color.WHITE, (theme_style.texture_alpha * 0.42f).roundToInt()),
                    with_alpha(Color.WHITE, 0),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + shine_height, paint)
                paint.shader = null

                paint.color =
                    with_alpha(colors.accent_blue, (theme_style.texture_alpha * 0.34f).roundToInt())
                canvas.drawRect(
                    rect.left,
                    rect.top + host.dp(4f),
                    rect.left + host.dp(3f),
                    rect.bottom - host.dp(4f),
                    paint
                )
                paint.color =
                    with_alpha(Color.WHITE, (theme_style.texture_alpha * 0.13f).roundToInt())
                canvas.drawRect(
                    rect.right - host.dp(2f),
                    rect.top + radius * 0.5f,
                    rect.right,
                    rect.bottom - radius * 0.5f,
                    paint
                )

                val inset = host.dp(1.4f)
                val inner = RectF(
                    rect.left + inset,
                    rect.top + inset,
                    rect.right - inset,
                    rect.bottom - inset
                )
                val inner_radius = max(0f, radius - inset)
                stroke_paint.style = Paint.Style.STROKE
                stroke_paint.strokeWidth = host.dp(0.8f)
                stroke_paint.color =
                    with_alpha(Color.WHITE, (theme_style.texture_alpha * 0.58f).roundToInt())
                canvas.drawRoundRect(inner, inner_radius, inner_radius, stroke_paint)

                val line_inset = max(host.dp(16f), min(rect.width(), rect.height()) * 0.1f)
                stroke_paint.strokeWidth = host.dp(0.9f)
                stroke_paint.color =
                    with_alpha(colors.accent_blue, (theme_style.texture_alpha * 1.05f).roundToInt())
                canvas.drawLine(
                    rect.left + line_inset,
                    rect.top + host.dp(8f),
                    rect.right - line_inset,
                    rect.top + host.dp(8f),
                    stroke_paint
                )
                stroke_paint.strokeWidth = host.dp(0.75f)
                stroke_paint.color =
                    with_alpha(Color.WHITE, (theme_style.texture_alpha * 0.38f).roundToInt())
                canvas.drawLine(
                    rect.left + line_inset,
                    rect.bottom - host.dp(9f),
                    rect.right - line_inset,
                    rect.bottom - host.dp(9f),
                    stroke_paint
                )
                canvas.restoreToCount(save)
            }

            ThemeTreatment.RADAR_GRID -> {
                stroke_paint.strokeWidth = host.dp(0.6f)
                stroke_paint.color = with_alpha(colors.accent_yellow, theme_style.texture_alpha)
                draw_even_radar_grid(canvas, rect)
            }

            ThemeTreatment.DAYLIGHT_CARD -> {
                paint.style = Paint.Style.FILL
                paint.color = with_alpha(colors.accent_blue, theme_style.texture_alpha)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + host.dp(3f), paint)
            }

            ThemeTreatment.STORM_BAND -> {
                paint.style = Paint.Style.FILL
                paint.color = with_alpha(colors.accent_blue, theme_style.texture_alpha)
                canvas.drawRect(rect.left, rect.top, rect.left + host.dp(5f), rect.bottom, paint)
                paint.color = with_alpha(colors.text, (theme_style.texture_alpha * 0.5f).toInt())
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + host.dp(2f), paint)
            }

            ThemeTreatment.CRT_SCANLINE -> {
                stroke_paint.strokeWidth = host.dp(0.7f)
                stroke_paint.color = with_alpha(colors.accent_green, theme_style.texture_alpha)
                var y = rect.top + host.dp(6f)
                while (y < rect.bottom) {
                    canvas.drawLine(
                        rect.left + host.dp(2f),
                        y,
                        rect.right - host.dp(2f),
                        y,
                        stroke_paint
                    )
                    y += host.dp(12f)
                }
            }
        }
    }

    // Radar-grid texture is centered evenly so decorative lines do not bunch up on resized panels.
    private fun draw_even_radar_grid(canvas: Canvas, rect: RectF) {
        val inset = host.dp(2f)
        val step = host.dp(RADAR_GRID_SPACING_DP)
        val left = rect.left + inset
        val top = rect.top + inset
        val right = rect.right - inset
        val bottom = rect.bottom - inset
        val width = right - left
        val height = bottom - top
        if (width < step || height < step) return

        val columns = max(1, floor(width / step).toInt())
        val rows = max(1, floor(height / step).toInt())
        val grid_width = columns * step
        val grid_height = rows * step
        val start_x = left + (width - grid_width) / 2f
        val start_y = top + (height - grid_height) / 2f

        for (index in 0..columns) {
            val x = start_x + index * step
            if (x > left + 0.5f && x < right - 0.5f) {
                canvas.drawLine(x, top, x, bottom, stroke_paint)
            }
        }
        for (index in 0..rows) {
            val y = start_y + index * step
            if (y > top + 0.5f && y < bottom - 0.5f) {
                canvas.drawLine(left, y, right, y, stroke_paint)
            }
        }
    }

    private companion object {
        const val CHROME_SURFACE_CACHE_LIMIT = 48
        const val RADAR_GRID_SPACING_DP = 36f
    }
}
data class FlightMapChromeStyle(val visual_theme: VisualTheme)

internal enum class ChromeSurfaceKind {
    CONTROL,
    PANEL
}

internal data class ChromeSurfaceKey(
    val kind: ChromeSurfaceKind,
    val width: Int,
    val height: Int,
    val radius: Int,
    val fill: Int,
    val stroke: Int,
    val stroke_width: Int,
    val selected: Boolean,
    val visual_theme: VisualTheme
)

interface FlightMapChromeHost {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
}

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

// Text sizing and wrapping are policy, not renderer-specific behavior. Renderers keep
// small vocabulary wrappers, while this code owns the exact measurement algorithm.
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

internal fun recycle_bitmap_pair(first: Bitmap, second: Bitmap) {
    first.recycle()
    second.recycle()
}
