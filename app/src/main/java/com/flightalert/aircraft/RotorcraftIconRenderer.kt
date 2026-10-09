@file:Suppress("FunctionName", "LocalVariableName", "PrivatePropertyName", "PropertyName")

package com.flightalert.aircraft

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.withRotation
import androidx.core.graphics.withTranslation
import com.flightalert.smooth_step
import com.flightalert.traffic.TrafficSpritePaintGeometry
import com.flightalert.with_alpha
import kotlin.math.roundToInt

/** Full traffic helicopter geometry, kept independent of traffic state and frame scheduling. */
internal class RotorcraftIconRenderer(private val dp: (Float) -> Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke_paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rotorcraft_body_path = Path()
    private val rotorcraft_shadow_path = Path()
    private val rotorcraft_main_blade_lines = FloatArray(8)
    private val rotorcraft_tail_blade_lines = FloatArray(8)
    private var rotorcraft_geometry_shape_bits = 0
    private var rotorcraft_geometry: RotorcraftFrameGeometry? = null

    fun draw(canvas: Canvas, x: Float, y: Float, track_deg: Double?, icon_scale: Float,
             shape_progress: Float, symbol_alpha: Int, color: Int, scrim: Int,
             now_elapsed_ms: Long): Boolean {
        val geometry = rotorcraft_frame_geometry(shape_progress)
        val fill = with_alpha(color, symbol_alpha)
        val stroke = with_alpha(scrim, 235 * symbol_alpha / 255)
        val alpha = 74 * symbol_alpha / 255
        if (alpha > 4) {
            canvas.withTranslation(
                x + dp(TrafficSpritePaintGeometry.rotorcraft_shadow_offset_x_dp(shape_progress)) * icon_scale * 0.42f,
                y + dp(TrafficSpritePaintGeometry.rotorcraft_shadow_offset_y_dp(shape_progress)) * icon_scale * 0.42f
            ) {
                if (track_deg != null && track_deg.isFinite()) rotate((track_deg % 360).toFloat())
                scale(icon_scale, icon_scale)
                paint.style = Paint.Style.FILL
                paint.color = with_alpha(scrim, (alpha * 0.8f).roundToInt().coerceIn(0, 64))
                drawPath(rotorcraft_shadow_path, paint)
            }
        }
        val blade_alpha = (Color.alpha(stroke) * geometry.blade_visibility * 0.72f)
            .roundToInt().coerceIn(0, 255)
        val animate = blade_alpha > 4 && (geometry.rotor > 0f || geometry.tail_rotor > 0f)
        canvas.withTranslation(x, y) {
            if (track_deg != null && track_deg.isFinite()) rotate((track_deg % 360).toFloat())
            scale(icon_scale, icon_scale)
            paint.style = Paint.Style.FILL
            paint.color = fill
            drawPath(rotorcraft_body_path, paint)
            stroke_paint.style = Paint.Style.STROKE
            stroke_paint.strokeJoin = Paint.Join.ROUND
            stroke_paint.strokeCap = Paint.Cap.BUTT
            stroke_paint.color = stroke
            stroke_paint.strokeWidth = dp(1.2f)
            drawPath(rotorcraft_body_path, stroke_paint)
            if (animate) {
                val phase = ((now_elapsed_ms % ROTORCRAFT_BLADE_CYCLE_MS).toFloat() /
                    ROTORCRAFT_BLADE_CYCLE_MS) * 360f
                stroke_paint.strokeCap = Paint.Cap.ROUND
                stroke_paint.color = with_alpha(stroke, blade_alpha)
                stroke_paint.strokeWidth = geometry.main_blade_stroke_width
                draw_rotorcraft_blade_pair(this, rotorcraft_main_blade_lines, geometry.mast_y,
                    phase, stroke_paint)
                if (geometry.tail_rotor > 0f) {
                    stroke_paint.strokeWidth = geometry.tail_blade_stroke_width
                    draw_rotorcraft_blade_pair(this, rotorcraft_tail_blade_lines, geometry.tail_rotor_y,
                        (phase * 5f + 24f) % 360f, stroke_paint)
                }
            }
            paint.color = fill
            drawCircle(0f, geometry.mast_y, geometry.hub_radius, paint)
            stroke_paint.color = stroke
            stroke_paint.strokeWidth = dp(1.2f)
            drawCircle(0f, geometry.mast_y, geometry.hub_radius, stroke_paint)
        }
        return animate
    }

    private fun draw_rotorcraft_blade_pair(
        canvas: Canvas,
        lines: FloatArray,
        center_y: Float,
        angle_deg: Float,
        blade_paint: Paint
    ) {
        canvas.withRotation(angle_deg, 0f, center_y) {
            drawLines(lines, blade_paint)
        }
    }

    private fun rotorcraft_frame_geometry(shape_progress: Float): RotorcraftFrameGeometry {
        val shape_bits = shape_progress.toRawBits()
        rotorcraft_geometry?.let { cached ->
            if (rotorcraft_geometry_shape_bits == shape_bits) return cached
        }

        val body = smooth_step(0f, 0.62f, shape_progress)
        val tail = smooth_step(0.34f, 1f, shape_progress)
        val rotor = smooth_step(0.32f, 1f, shape_progress)
        val tail_rotor = smooth_step(0.58f, 1f, shape_progress)
        val mast_y = -dp(1.8f * body)
        val main_half_length = dp(5f + 22f * rotor)
        val main_gap = dp(2.8f + 1.6f * body)
        val tail_rotor_y = dp(7.8f + 19.2f * tail)
        val tail_half_length = dp(2.3f + 4.2f * tail_rotor)
        val tail_gap = dp(0.8f)

        rotorcraft_main_blade_lines[0] = -main_half_length
        rotorcraft_main_blade_lines[1] = mast_y
        rotorcraft_main_blade_lines[2] = -main_gap
        rotorcraft_main_blade_lines[3] = mast_y
        rotorcraft_main_blade_lines[4] = main_gap
        rotorcraft_main_blade_lines[5] = mast_y
        rotorcraft_main_blade_lines[6] = main_half_length
        rotorcraft_main_blade_lines[7] = mast_y
        rotorcraft_tail_blade_lines[0] = -tail_half_length
        rotorcraft_tail_blade_lines[1] = tail_rotor_y
        rotorcraft_tail_blade_lines[2] = -tail_gap
        rotorcraft_tail_blade_lines[3] = tail_rotor_y
        rotorcraft_tail_blade_lines[4] = tail_gap
        rotorcraft_tail_blade_lines[5] = tail_rotor_y
        rotorcraft_tail_blade_lines[6] = tail_half_length
        rotorcraft_tail_blade_lines[7] = tail_rotor_y

        prepare_rotorcraft_shadow_path(body, tail)
        prepare_rotorcraft_body_path(body, tail)

        return RotorcraftFrameGeometry(
            rotor = rotor,
            tail_rotor = tail_rotor,
            blade_visibility = smooth_step(0.32f, 0.82f, shape_progress),
            mast_y = mast_y,
            main_blade_stroke_width = dp(1.55f + 0.3f * rotor),
            tail_rotor_y = tail_rotor_y,
            tail_blade_stroke_width = dp(1.25f),
            hub_radius = dp(1.2f + 1.3f * body),
        ).also { geometry ->
            rotorcraft_geometry_shape_bits = shape_bits
            rotorcraft_geometry = geometry
        }
    }

    private fun prepare_rotorcraft_shadow_path(body: Float, tail: Float) {
        val nose_y = -dp(4.5f + 9.5f * body)
        val shoulder_y = -dp(3.4f + 5.3f * body)
        val cabin_mid_y = dp(1.2f + 4.4f * body)
        val cabin_back_y = dp(4.2f + 5f * body)
        val cabin_half_width = dp(3.4f + 4.4f * body)
        val cabin_back_half_width = dp(2.1f + 2.1f * body)
        val boom_half_width = dp(0.85f + 0.75f * tail)
        val boom_end_y = dp(6f + 18f * tail)
        val tail_fin_half_width = dp(2.1f + 3.1f * tail)
        val tail_fin_y = dp(7.4f + 19.6f * tail)
        rotorcraft_shadow_path.reset()
        rotorcraft_shadow_path.moveTo(0f, nose_y)
        rotorcraft_shadow_path.cubicTo(
            cabin_half_width,
            shoulder_y,
            cabin_half_width,
            cabin_mid_y,
            cabin_back_half_width,
            cabin_back_y
        )
        rotorcraft_shadow_path.lineTo(boom_half_width, boom_end_y)
        rotorcraft_shadow_path.lineTo(tail_fin_half_width, tail_fin_y)
        rotorcraft_shadow_path.lineTo(-tail_fin_half_width, tail_fin_y)
        rotorcraft_shadow_path.lineTo(-boom_half_width, boom_end_y)
        rotorcraft_shadow_path.lineTo(-cabin_back_half_width, cabin_back_y)
        rotorcraft_shadow_path.cubicTo(
            -cabin_half_width,
            cabin_mid_y,
            -cabin_half_width,
            shoulder_y,
            0f,
            nose_y
        )
        rotorcraft_shadow_path.close()
    }

    private fun prepare_rotorcraft_body_path(body: Float, tail: Float) {
        val nose_y = -dp(5f + 10f * body)
        val shoulder_y = -dp(3.8f + 5.8f * body)
        val cabin_mid_y = dp(1.4f + 4.8f * body)
        val cabin_back_y = dp(4.4f + 5.2f * body)
        val cabin_half_width = dp(2.9f + 4f * body)
        val cabin_back_half_width = dp(1.8f + 1.9f * body)
        val boom_half_width = dp(0.55f + 0.65f * tail)
        val boom_end_y = dp(6f + 18f * tail)
        val tail_fin_half_width = dp(1.8f + 2.9f * tail)
        val tail_fin_y = dp(7.4f + 19.6f * tail)
        rotorcraft_body_path.reset()
        rotorcraft_body_path.moveTo(0f, nose_y)
        rotorcraft_body_path.cubicTo(
            cabin_half_width,
            shoulder_y,
            cabin_half_width,
            cabin_mid_y,
            cabin_back_half_width,
            cabin_back_y
        )
        rotorcraft_body_path.lineTo(boom_half_width, boom_end_y)
        rotorcraft_body_path.lineTo(tail_fin_half_width, tail_fin_y)
        rotorcraft_body_path.lineTo(-tail_fin_half_width, tail_fin_y)
        rotorcraft_body_path.lineTo(-boom_half_width, boom_end_y)
        rotorcraft_body_path.lineTo(-cabin_back_half_width, cabin_back_y)
        rotorcraft_body_path.cubicTo(
            -cabin_half_width,
            cabin_mid_y,
            -cabin_half_width,
            shoulder_y,
            0f,
            nose_y
        )
        rotorcraft_body_path.close()
    }

    fun reset() {
        rotorcraft_geometry = null
        rotorcraft_geometry_shape_bits = 0
        rotorcraft_body_path.reset()
        rotorcraft_shadow_path.reset()
    }

    private data class RotorcraftFrameGeometry(
        val rotor: Float,
        val tail_rotor: Float,
        val blade_visibility: Float,
        val mast_y: Float,
        val main_blade_stroke_width: Float,
        val tail_rotor_y: Float,
        val tail_blade_stroke_width: Float,
        val hub_radius: Float,
    )

    private companion object {
        const val ROTORCRAFT_BLADE_CYCLE_MS = 288L
    }
}
