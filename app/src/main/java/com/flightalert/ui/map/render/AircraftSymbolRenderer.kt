package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.flightalert.ui.map.traffic.AircraftSymbol
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object AircraftSymbolRenderer {
    private val path = Path()
    private val body_path_cache = object : LinkedHashMap<SymbolBodyPathKey, Path>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SymbolBodyPathKey, Path>): Boolean {
            return size > SYMBOL_BODY_PATH_CACHE_MAX_ENTRIES
        }
    }
    private val body_point_cache = object : LinkedHashMap<SymbolBodyPathKey, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SymbolBodyPathKey, FloatArray>): Boolean {
            return size > SYMBOL_BODY_PATH_CACHE_MAX_ENTRIES
        }
    }

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

    fun draw_cached_morph(
        canvas: Canvas,
        symbol: AircraftSymbol,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        when (symbol) {
            AircraftSymbol.GENERAL_AVIATION -> draw_cached_morphed_polygon(canvas, symbol, GENERAL_AVIATION_POINTS, progress, paint, stroke_paint, dp)
            AircraftSymbol.GLIDER -> draw_cached_morphed_polygon(canvas, symbol, GLIDER_POINTS, progress, paint, stroke_paint, dp)
            AircraftSymbol.AIRLINER -> draw_cached_airliner(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.SURFACE -> draw_cached_surface(canvas, progress, paint, stroke_paint, dp)
            AircraftSymbol.ROTORCRAFT,
            AircraftSymbol.UAV -> draw(canvas, symbol, progress, paint, stroke_paint, dp)
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
            target = GENERAL_AVIATION_POINTS,
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
            target = AIRLINER_POINTS,
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
            target = GLIDER_POINTS,
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
        path.reset()
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
        val rotor_offset = dp(8f + 12f * arms)
        val rotor_radius = dp(1.5f + 3.5f * rotors)
        val rotor_blade = dp(5f * rotors)
        draw_uav_rotor(canvas, -rotor_offset, -rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        draw_uav_rotor(canvas, rotor_offset, -rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        draw_uav_rotor(canvas, -rotor_offset, rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
        draw_uav_rotor(canvas, rotor_offset, rotor_offset, rotor_radius, rotor_blade, paint, stroke_paint)
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
            target = SURFACE_POINTS,
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

    private fun draw_cached_airliner(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        draw_cached_morphed_polygon(canvas, AircraftSymbol.AIRLINER, AIRLINER_POINTS, p, paint, stroke_paint, dp)
        draw_airliner_details(canvas, p, stroke_paint, dp)
    }

    private fun draw_airliner_details(
        canvas: Canvas,
        progress: Float,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val engine = smooth_step(0.48f, 1f, p)
        if (engine > 0f) {
            canvas.drawCircle(-dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
            canvas.drawCircle(dp(11.8f * engine), dp(5.6f * engine), dp(2.2f * engine), stroke_paint)
        }
    }

    private fun draw_cached_surface(
        canvas: Canvas,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        draw_cached_morphed_polygon(canvas, AircraftSymbol.SURFACE, SURFACE_POINTS, p, paint, stroke_paint, dp)
        draw_surface_details(canvas, p, stroke_paint, dp)
    }

    private fun draw_surface_details(
        canvas: Canvas,
        progress: Float,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val p = progress.coerceIn(0f, 1f)
        val body = smooth_step(0f, 0.6f, p)
        val gear = smooth_step(0.55f, 1f, p)
        stroke_paint.strokeWidth = dp(2f)
        canvas.drawLine(-dp(5f + 9f * gear), dp(8f + 10f * body), dp(5f + 9f * gear), dp(8f + 10f * body), stroke_paint)
        canvas.drawCircle(-dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        canvas.drawCircle(dp(3f + 5f * gear), dp(8f + 10f * body), dp(2.2f * gear), stroke_paint)
        stroke_paint.strokeWidth = dp(1.2f)
    }

    private fun draw_cached_morphed_polygon(
        canvas: Canvas,
        symbol: AircraftSymbol,
        target: FloatArray,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        val body = cached_morphed_polygon(symbol, target, progress, dp)
        canvas.drawPath(body, paint)
        canvas.drawPath(body, stroke_paint)
    }

    private fun draw_morphed_polygon(
        canvas: Canvas,
        target: FloatArray,
        progress: Float,
        paint: Paint,
        stroke_paint: Paint,
        dp: (Float) -> Float
    ) {
        path.reset()
        val p = smooth_step(0f, 1f, progress.coerceIn(0f, 1f))
        var target_index = 0
        var point_index = 0
        while (target_index + 1 < target.size) {
            val point_x = target[target_index]
            val point_y = target[target_index + 1]
            val angle = atan2(point_y.toDouble(), point_x.toDouble())
            val start_x = (cos(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val start_y = (sin(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val x = lerp(start_x, point_x, p)
            val y = lerp(start_y, point_y, p)
            if (point_index == 0) {
                path.moveTo(dp(x), dp(y))
            } else {
                path.lineTo(dp(x), dp(y))
            }
            target_index += 2
            point_index++
        }
        path.close()
        canvas.drawPath(path, paint)
        canvas.drawPath(path, stroke_paint)
    }

    private fun cached_morphed_polygon(
        symbol: AircraftSymbol,
        target: FloatArray,
        progress: Float,
        dp: (Float) -> Float
    ): Path {
        val key = symbol_body_key(symbol, progress, dp)
        body_path_cache[key]?.let { return it }
        val body = Path()
        val points = cached_morphed_points(symbol, target, progress, dp)
        var index = 0
        var point_index = 0
        while (index + 1 < points.size) {
            if (point_index == 0) {
                body.moveTo(points[index], points[index + 1])
            } else {
                body.lineTo(points[index], points[index + 1])
            }
            index += 2
            point_index++
        }
        body.close()
        body_path_cache[key] = body
        return body
    }

    private fun cached_morphed_points(
        symbol: AircraftSymbol,
        target: FloatArray,
        progress: Float,
        dp: (Float) -> Float
    ): FloatArray {
        val key = symbol_body_key(symbol, progress, dp)
        body_point_cache[key]?.let { return it }
        val p = smooth_step(0f, 1f, key.progress_bucket / SYMBOL_VECTOR_PROGRESS_STEPS.toFloat())
        val points = FloatArray(target.size)
        var target_index = 0
        while (target_index + 1 < target.size) {
            val point_x = target[target_index]
            val point_y = target[target_index + 1]
            val angle = atan2(point_y.toDouble(), point_x.toDouble())
            val start_x = (cos(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            val start_y = (sin(angle) * AIRCRAFT_MORPH_SEED_RADIUS_DP).toFloat()
            points[target_index] = dp(lerp(start_x, point_x, p))
            points[target_index + 1] = dp(lerp(start_y, point_y, p))
            target_index += 2
        }
        body_point_cache[key] = points
        return points
    }

    private fun symbol_body_key(
        symbol: AircraftSymbol,
        progress: Float,
        dp: (Float) -> Float
    ): SymbolBodyPathKey {
        val progress_bucket = (progress.coerceIn(0f, 1f) * SYMBOL_VECTOR_PROGRESS_STEPS)
            .roundToInt()
            .coerceIn(0, SYMBOL_VECTOR_PROGRESS_STEPS)
        val dp_unit_bucket = (dp(1f) * 1000f).roundToInt()
        return SymbolBodyPathKey(symbol, progress_bucket, dp_unit_bucket)
    }

    private fun draw_uav_rotor(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        blade: Float,
        paint: Paint,
        stroke_paint: Paint
    ) {
        canvas.drawCircle(x, y, radius, paint)
        canvas.drawCircle(x, y, radius, stroke_paint)
        canvas.drawLine(x - blade, y, x + blade, y, stroke_paint)
    }

    private fun smooth_step(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private data class SymbolBodyPathKey(
        val symbol: AircraftSymbol,
        val progress_bucket: Int,
        val dp_unit_bucket: Int
    )

    private const val AIRCRAFT_MORPH_SEED_RADIUS_DP = 4f
    private const val SYMBOL_VECTOR_PROGRESS_STEPS = 96
    private const val SYMBOL_BODY_PATH_CACHE_MAX_ENTRIES = 768

    private val GENERAL_AVIATION_POINTS = floatArrayOf(
        0f, -17f,
        3.2f, -4.2f,
        17f, 0.8f,
        15.2f, 4.4f,
        3.3f, 3.4f,
        2.2f, 13.8f,
        8.4f, 17.2f,
        0f, 12.7f,
        -8.4f, 17.2f,
        -2.2f, 13.8f,
        -3.3f, 3.4f,
        -15.2f, 4.4f,
        -17f, 0.8f,
        -3.2f, -4.2f
    )

    private val AIRLINER_POINTS = floatArrayOf(
        0f, -21f,
        5.5f, -4.8f,
        23.5f, 0.8f,
        21.5f, 5.8f,
        7.2f, 6.2f,
        5.1f, 16.5f,
        13.5f, 21f,
        2.8f, 17.2f,
        0f, 13.5f,
        -2.8f, 17.2f,
        -13.5f, 21f,
        -5.1f, 16.5f,
        -7.2f, 6.2f,
        -21.5f, 5.8f,
        -23.5f, 0.8f,
        -5.5f, -4.8f
    )

    private val GLIDER_POINTS = floatArrayOf(
        0f, -16f,
        3f, 2f,
        27f, 5f,
        4f, 8f,
        1.8f, 17f,
        -1.8f, 17f,
        -4f, 8f,
        -27f, 5f,
        -3f, 2f
    )

    private val SURFACE_POINTS = floatArrayOf(
        0f, -15f,
        5f, -4f,
        18f, 1f,
        15f, 6f,
        4f, 4f,
        2f, 13f,
        -2f, 13f,
        -4f, 4f,
        -15f, 6f,
        -18f, 1f,
        -5f, -4f
    )
}
