package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.traffic.AircraftSymbol
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object AircraftSymbolRenderer {
    fun draw(
        canvas: Canvas,
        symbol: AircraftSymbol,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        when (symbol) {
            AircraftSymbol.ROTORCRAFT -> draw_rotorcraft(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.GLIDER -> draw_glider(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.UAV -> draw_uav(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.SURFACE -> draw_surface(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.AIRLINER -> draw_airliner(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.GENERAL_AVIATION -> draw_general_aviation(canvas, progress, paint, stroke_paint, dp)
        }
    }

    private fun draw_general_aviation(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        draw_morphed_polygon(
            canvas = canvas,
            target = listOf(
                ScreenPoint(0f, -17f),
                ScreenPoint(3.2f, -4.2f),
                ScreenPoint(17f, 0.8f),
                ScreenPoint(15.2f, 4.4f),
                ScreenPoint(3.3f, 3.4f),
                ScreenPoint(2.2f, 13.8f),
                ScreenPoint(8.4f, 17.2f),
                ScreenPoint(0f, 12.7f),
                ScreenPoint(-8.4f, 17.2f),
                ScreenPoint(-2.2f, 13.8f),
                ScreenPoint(-3.3f, 3.4f),
                ScreenPoint(-15.2f, 4.4f),
                ScreenPoint(-17f, 0.8f),
                ScreenPoint(-3.2f, -4.2f)
            ),
            progress = progress,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )
    }

    private fun draw_airliner(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        draw_morphed_polygon(
            canvas = canvas,
            target = listOf(
                ScreenPoint(0f, -21f),
                ScreenPoint(5.5f, -4.8f),
                ScreenPoint(23.5f, 0.8f),
                ScreenPoint(21.5f, 5.8f),
                ScreenPoint(7.2f, 6.2f),
                ScreenPoint(5.1f, 16.5f),
                ScreenPoint(13.5f, 21f),
                ScreenPoint(2.8f, 17.2f),
                ScreenPoint(0f, 13.5f),
                ScreenPoint(-2.8f, 17.2f),
                ScreenPoint(-13.5f, 21f),
                ScreenPoint(-5.1f, 16.5f),
                ScreenPoint(-7.2f, 6.2f),
                ScreenPoint(-21.5f, 5.8f),
                ScreenPoint(-23.5f, 0.8f),
                ScreenPoint(-5.5f, -4.8f)
            ),
            progress = p,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )

        val engine = smooth_step(0.48f, 1f, p)
        if (engine > 0f) {
            canvas.drawCircle(-dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
            canvas.drawCircle(dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
        }
    }

    private fun draw_rotorcraft(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.55f, p)
        val rotor = smooth_step(0.25f, 1f, p)
        val tail = smooth_step(0.45f, 1f, p)
        val body_rect = RectF(-dp(4f + 4f * body), -dp(4f + 3f * body), dp(4f + 5f * body), dp(4f + 4f * body))
        canvas.drawOval(body_rect, paint)
        canvas.drawOval(body_rect, stroke_paint)
        stroke_paint.strokeWidth = dp(2.5f)
        canvas.drawLine(-dp(5f + 19f * rotor), 0f, dp(5f + 19f * rotor), 0f, stroke_paint)
        canvas.drawLine(0f, -dp(5f + 17f * rotor), 0f, dp(5f + 17f * rotor), stroke_paint)
        stroke_paint.strokeWidth = dp(2f)
        canvas.drawLine(dp(5f + 4f * body), dp(1f), dp(7f + 16f * tail), dp(4f + 5f * tail), stroke_paint)
        canvas.drawLine(dp(8f + 13f * tail), dp(3f + 2f * tail), dp(9f + 16f * tail), dp(5f + 8f * tail), stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_glider(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        draw_morphed_polygon(
            canvas = canvas,
            target = listOf(
                ScreenPoint(0f, -16f),
                ScreenPoint(3f, 2f),
                ScreenPoint(27f, 5f),
                ScreenPoint(4f, 8f),
                ScreenPoint(1.8f, 17f),
                ScreenPoint(-1.8f, 17f),
                ScreenPoint(-4f, 8f),
                ScreenPoint(-27f, 5f),
                ScreenPoint(-3f, 2f)
            ),
            progress = progress,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )
    }

    private fun draw_uav(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val path = Path()
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.55f, p)
        val arms = smooth_step(0.2f, 1f, p)
        val rotors = smooth_step(0.55f, 1f, p)
        path.moveTo(0f, -dp(5f + 8f * body))
        path.lineTo(dp(3f + 5f * body), 0f)
        path.lineTo(0f, dp(5f + 8f * body))
        path.lineTo(-dp(3f + 5f * body), 0f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawPath(path, stroke_paint)
        stroke_paint.strokeWidth = dp(2f)
        val arm_inner = dp(4f + 2f * body)
        val arm_outer = dp(7f + 11f * arms)
        canvas.drawLine(-arm_inner, -arm_inner, -arm_outer, -arm_outer, stroke_paint)
        canvas.drawLine(arm_inner, -arm_inner, arm_outer, -arm_outer, stroke_paint)
        canvas.drawLine(-arm_inner, arm_inner, -arm_outer, arm_outer, stroke_paint)
        canvas.drawLine(arm_inner, arm_inner, arm_outer, arm_outer, stroke_paint)
        listOf(
            -dp(8f + 12f * arms) to -dp(8f + 12f * arms),
            dp(8f + 12f * arms) to -dp(8f + 12f * arms),
            -dp(8f + 12f * arms) to dp(8f + 12f * arms),
            dp(8f + 12f * arms) to dp(8f + 12f * arms)
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, dp(1.5f + 3.5f * rotors), paint)
            canvas.drawCircle(x, y, dp(1.5f + 3.5f * rotors), stroke_paint)
            canvas.drawLine(x - dp(5f * rotors), y, x + dp(5f * rotors), y, stroke_paint)
        }
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_surface(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.6f, p)
        val gear = smooth_step(0.55f, 1f, p)
        draw_morphed_polygon(
            canvas = canvas,
            target = listOf(
                ScreenPoint(0f, -15f),
                ScreenPoint(5f, -4f),
                ScreenPoint(18f, 1f),
                ScreenPoint(15f, 6f),
                ScreenPoint(4f, 4f),
                ScreenPoint(2f, 13f),
                ScreenPoint(-2f, 13f),
                ScreenPoint(-4f, 4f),
                ScreenPoint(-15f, 6f),
                ScreenPoint(-18f, 1f),
                ScreenPoint(-5f, -4f)
            ),
            progress = p,
            paint = paint,
            stroke_paint = stroke_paint,
            dp = dp
        )
        stroke_paint.strokeWidth = dp(2f)
        canvas.drawLine(-dp(5f + 9f * gear), dp(8f + 10f * body), dp(5f + 9f * gear), dp(8f + 10f * body), stroke_paint)
        canvas.drawCircle(-dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        canvas.drawCircle(dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_morphed_polygon(
        canvas: Canvas,
        target: List<ScreenPoint>,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val path = Path()
        val p = smooth_step(0f, 1f, progress.coerceIn(0f, 1f))
        target.forEachIndexed { index, point ->
            val angle = atan2(point.y.toDouble(), point.x.toDouble())
            val start_x = (cos(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val start_y = (sin(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val x = lerp(start_x, point.x, p)
            val y = lerp(start_y, point.y, p)
            if (index == 0) {
                path.moveTo(dp(x), dp(y))
            } else {
                path.lineTo(dp(x), dp(y))
            }
        }
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawPath(path, stroke_paint)
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private const val AIRCRAFT_MORPH_SEED_RADIUS_DP = 4f
}
