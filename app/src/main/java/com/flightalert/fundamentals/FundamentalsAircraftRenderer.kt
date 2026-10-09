package com.flightalert.fundamentals

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.flightalert.VisualTheme
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolRenderer
import com.flightalert.aircraft.RotorcraftIconRenderer
import com.flightalert.aircraft.blended_icon_scale
import com.flightalert.aircraft.shape_progress
import com.flightalert.aircraft.symbol_visibility
import com.flightalert.aircraft.zoom_marker_dot_blend
import com.flightalert.traffic.TrafficSpritePaintGeometry
import com.flightalert.with_alpha
import com.flightalert.safe_smooth_step
import kotlin.math.ceil
import kotlin.math.max

/** One zoom-dependent style shared by drawing, edge culling, selection and hit testing. */
internal class FundamentalsAircraftStyle(zoom: Double) {
    val dotRadiusDp = 2f + 2.5f * safe_smooth_step(5f, 10f, zoom.toFloat())
    private val blend = zoom_marker_dot_blend(zoom)
    val shapeProgress = shape_progress(blend)
    val symbolAlpha = (255 * symbol_visibility(blend)).toInt().coerceIn(0, 255)
    val dotAlpha = 255 - symbolAlpha
    private val baseScale = blended_icon_scale(zoom, blend)

    fun scale(symbol: AircraftSymbol, typeScale: Float): Float =
        baseScale * typeScale * TrafficSpritePaintGeometry.symbol(symbol).icon_scale_multiplier

    fun radiusDp(symbol: AircraftSymbol, typeScale: Float): Float = max(9.5f,
        TrafficSpritePaintGeometry.symbol(symbol).maximum_radius_dp * scale(symbol, typeScale) + 3f)
}

/** Two neutral masks per symbol, replaced in place when the shared morph position changes. */
internal class FundamentalsAircraftRenderer(private val density: Float) {
    var theme: VisualTheme = VisualTheme.DEFAULT
    private val dp: (Float) -> Float = { it * density }
    private val rotorcraft = RotorcraftIconRenderer(dp)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val maskFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val maskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val masks = arrayOfNulls<SymbolMasks>(AircraftSymbol.entries.size)
    // Twice native density preserves detail at all current type/icon scales without color caches.
    private val maskResolutionScale = 2f
    private val halfSize = ceil(40 * density * maskResolutionScale).toInt().coerceAtLeast(1)
    private val maskBounds = RectF(-halfSize / maskResolutionScale, -halfSize / maskResolutionScale,
        halfSize / maskResolutionScale, halfSize / maskResolutionScale)
    private val maskDp: (Float) -> Float = { dp(it) * maskResolutionScale }

    /** Returns true only when this drawn helicopter has visible blades that need another frame. */
    fun draw(canvas: Canvas, x: Float, y: Float, symbol: AircraftSymbol,
             typeScale: Float, heading: Double?, style: FundamentalsAircraftStyle,
             color: Int, nowElapsedMs: Long): Boolean {
        if (style.symbolAlpha == 0) return false
        val scale = style.scale(symbol, typeScale)
        val scrim = theme.colors.scrim
        if (symbol == AircraftSymbol.ROTORCRAFT) {
            return rotorcraft.draw(canvas, x, y, heading, scale, style.shapeProgress,
                style.symbolAlpha, color, scrim, nowElapsedMs)
        }
        fill.color = with_alpha(scrim, 74 * style.symbolAlpha / 255)
        canvas.drawCircle(x + dp(TrafficSpritePaintGeometry.shadow_offset_x_dp(style.shapeProgress)) * scale,
            y + dp(TrafficSpritePaintGeometry.shadow_offset_y_dp(style.shapeProgress)) * scale,
            dp(TrafficSpritePaintGeometry.shadow_radius_dp(style.shapeProgress)) *
                TrafficSpritePaintGeometry.shadow_radius_scale(symbol, style.shapeProgress) * scale, fill)
        fill.color = with_alpha(color, style.symbolAlpha)
        stroke.color = with_alpha(scrim, 235 * style.symbolAlpha / 255)
        stroke.strokeWidth = dp(1.2f)
        stroke.strokeCap = Paint.Cap.BUTT
        val save = canvas.save()
        try {
            canvas.translate(x, y)
            if (heading != null && heading.isFinite()) canvas.rotate((heading % 360).toFloat())
            canvas.scale(scale, scale)
            // UAV arms are drawn beneath rotor fills; separate fill/stroke masks reverse that order.
            if (canvas.isHardwareAccelerated && symbol != AircraftSymbol.UAV) {
                val mask = symbolMasks(symbol, style.shapeProgress)
                canvas.drawBitmap(mask.fill, null, maskBounds, fill)
                canvas.drawBitmap(mask.stroke, null, maskBounds, stroke)
            } else {
                AircraftSymbolRenderer.draw(canvas, symbol, style.shapeProgress, fill, stroke, dp)
            }
        } finally {
            canvas.restoreToCount(save)
        }
        return false
    }

    private fun symbolMasks(symbol: AircraftSymbol, shapeProgress: Float): SymbolMasks {
        val index = symbol.ordinal
        val mask = masks[index] ?: SymbolMasks(
            Bitmap.createBitmap(halfSize * 2, halfSize * 2, Bitmap.Config.ALPHA_8),
            Bitmap.createBitmap(halfSize * 2, halfSize * 2, Bitmap.Config.ALPHA_8)
        ).also { masks[index] = it }
        if (mask.progress == shapeProgress) return mask
        mask.fill.eraseColor(Color.TRANSPARENT)
        mask.stroke.eraseColor(Color.TRANSPARENT)
        maskStroke.strokeWidth = maskDp(1.2f)
        maskStroke.strokeCap = Paint.Cap.BUTT
        maskFill.color = Color.WHITE
        maskStroke.color = Color.TRANSPARENT
        recordMask(mask.fill, symbol, shapeProgress)
        maskFill.color = Color.TRANSPARENT
        maskStroke.color = Color.WHITE
        recordMask(mask.stroke, symbol, shapeProgress)
        mask.progress = shapeProgress
        return mask
    }

    private fun recordMask(bitmap: Bitmap, symbol: AircraftSymbol, shapeProgress: Float) {
        val canvas = Canvas(bitmap)
        canvas.translate(halfSize.toFloat(), halfSize.toFloat())
        AircraftSymbolRenderer.draw(canvas, symbol, shapeProgress, maskFill, maskStroke, maskDp)
    }

    fun reset() {
        // Dropping references lets Android release masks after any pending GPU draws finish.
        masks.fill(null)
        rotorcraft.reset()
    }

    fun destroy() = reset()

    private class SymbolMasks(val fill: Bitmap, val stroke: Bitmap, var progress: Float = Float.NaN)
}
