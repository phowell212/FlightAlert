@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.smooth_step



internal class TrafficSpriteFootprints(
    private val dp: (Float) -> Float,
    deadband_dp: Float = DEFAULT_DEADBAND_DP,
) {
    private enum class PaintMode {
        SYMBOL,
        UNBATCHED_DOT,
        BATCH_SELECTED_DOT,
    }

    private data class GeometrySignature(
        val mode: PaintMode,
        val symbol: AircraftSymbol,
        val selected: Boolean,
        val radius_bits: Int,
        val clearance_bits: Int,
    )

    private data class StableFootprint(
        val signature: GeometrySignature,
        val rect: ReferenceScreenRect,
        var seen_generation: Int,
    )

    private val deadband_px = dp(deadband_dp).also {
        require(it.isFinite() && it >= 0f) { "traffic footprint deadband must be finite and nonnegative" }
    }
    private val retained = HashMap<String, StableFootprint>()
    private var generation = 0

    fun append_reference_label_avoid_rects(
        state: TrafficOverlayState,
        now_elapsed_ms: Long,
        clearance_px: Float,
        output: MutableList<ReferenceScreenRect>,
    ) {
        require(clearance_px.isFinite() && clearance_px >= 0f) {
            "traffic footprint clearance must be finite and nonnegative"
        }
        advance_generation()
        val marker_blend = AircraftMarkerMorph.marker_dot_blend(state.viewport)
        val selected_key = normalized_selected_aircraft_key(state.selected_aircraft_id)
        val batch = state.dot_batch
        val draw_symbols = state.aircraft.isNotEmpty() && marker_blend < SYMBOL_DRAW_BLEND_LIMIT
        val selected_only = batch != null || state.viewport.zoom < ALL_TRAFFIC_AVOID_MIN_ZOOM
        when {
            batch != null && draw_symbols -> append_symbol_footprints(
                state,
                now_elapsed_ms,
                clearance_px,
                selected_key,
                marker_blend,
                selected_only = true,
                output = output,
            )

            batch != null -> append_selected_batch_dot(
                state,
                batch,
                clearance_px,
                output,
            )

            marker_blend >= SYMBOL_DRAW_BLEND_LIMIT -> append_unbatched_dot_footprints(
                state,
                clearance_px,
                selected_key,
                selected_only,
                output,
            )

            else -> append_symbol_footprints(
                state,
                now_elapsed_ms,
                clearance_px,
                selected_key,
                marker_blend,
                selected_only = selected_only,
                output = output,
            )
        }
        prune_unseen()
    }

    private fun append_symbol_footprints(
        state: TrafficOverlayState,
        now_elapsed_ms: Long,
        clearance_px: Float,
        selected_key: String?,
        marker_blend: Float,
        selected_only: Boolean,
        output: MutableList<ReferenceScreenRect>,
    ) {
        if (selected_only && selected_key == null) return
        val scale = state.aircraft_transform_scale.coerceAtLeast(0.001f)
        val symbol_visibility = AircraftMarkerMorph.symbol_visibility(marker_blend)
        for (item in state.aircraft) {
            val selected = item.appearance_key == selected_key
            if (selected_only && !selected) continue
            val appearance = traffic_aircraft_appearance_progress_at(item, now_elapsed_ms)
            if ((appearance * 255f).toInt() <= MIN_VISIBLE_ALPHA ||
                (appearance * symbol_visibility * 255f).toInt() <= MIN_VISIBLE_ALPHA
            ) {
                continue
            }
            val elapsed_seconds = traffic_motion_elapsed_seconds(item, now_elapsed_ms)
            val center_x =
                (item.screen_point.x + item.screen_velocity_x_px_per_sec * elapsed_seconds) * scale +
                    state.aircraft_translation_x
            val center_y =
                (item.screen_point.y + item.screen_velocity_y_px_per_sec * elapsed_seconds) * scale +
                    state.aircraft_translation_y
            append_footprint(
                key = item.appearance_key,
                mode = PaintMode.SYMBOL,
                item = item,
                selected = selected,
                center_x = center_x,
                center_y = center_y,
                radius = traffic_aircraft_painted_radius_px(
                    item = item,
                    viewport_zoom = state.viewport.zoom,
                    selected = selected,
                    dp = dp,
                    appearance_progress = appearance,
                ),
                clearance_px = clearance_px,
                state = state,
                output = output,
            )
        }
    }

    private fun append_selected_batch_dot(
        state: TrafficOverlayState,
        batch: TrafficDotBatchOverlayState,
        clearance_px: Float,
        output: MutableList<ReferenceScreenRect>,
    ) {
        val item = batch.selected_aircraft ?: return
        val appearance = item.appearance_progress.coerceIn(0f, 1f)
        val radius = traffic_selected_dot_painted_radius_px(
            item = item,
            viewport_zoom = state.viewport.zoom,
            appearance_progress = appearance,
            dp = dp,
        )
        if (radius <= 0f) return
        val transform_scale = batch.transform_scale.coerceAtLeast(0.001f)
        append_footprint(
            key = item.appearance_key,
            mode = PaintMode.BATCH_SELECTED_DOT,
            item = item,
            selected = true,
            center_x = item.screen_point.x * transform_scale + batch.translation_x,
            center_y = item.screen_point.y * transform_scale + batch.translation_y,
            radius = radius,
            clearance_px = clearance_px,
            state = state,
            output = output,
        )
    }

    private fun append_unbatched_dot_footprints(
        state: TrafficOverlayState,
        clearance_px: Float,
        selected_key: String?,
        selected_only: Boolean,
        output: MutableList<ReferenceScreenRect>,
    ) {
        if (selected_only && selected_key == null) return
        val base_radius = traffic_unbatched_dot_painted_radius_px(state.viewport.zoom, dp)
        for (item in state.aircraft) {
            val selected = item.appearance_key == selected_key
            if (selected_only && !selected) continue
            val selected_radius = if (selected) {
                traffic_selected_dot_painted_radius_px(
                    item = item,
                    viewport_zoom = state.viewport.zoom,
                    appearance_progress = item.appearance_progress.coerceIn(0f, 1f),
                    dp = dp,
                )
            } else {
                0f
            }
            append_footprint(
                key = item.appearance_key,
                mode = PaintMode.UNBATCHED_DOT,
                item = item,
                selected = selected,
                center_x = item.screen_point.x,
                center_y = item.screen_point.y,
                radius = max(base_radius, selected_radius),
                clearance_px = clearance_px,
                state = state,
                output = output,
            )
        }
    }

    private fun append_footprint(
        key: String,
        mode: PaintMode,
        item: TrafficAircraftOverlayState,
        selected: Boolean,
        center_x: Float,
        center_y: Float,
        radius: Float,
        clearance_px: Float,
        state: TrafficOverlayState,
        output: MutableList<ReferenceScreenRect>,
    ) {
        if (!center_x.isFinite() || !center_y.isFinite() || !radius.isFinite() || radius <= 0f) return
        val raw_left = (center_x - radius).toDouble()
        val raw_top = (center_y - radius).toDouble()
        val raw_right = (center_x + radius).toDouble()
        val raw_bottom = (center_y + radius).toDouble()
        if (raw_right <= 0.0 || raw_bottom <= 0.0 ||
            raw_left >= state.content_width.toDouble() || raw_top >= state.content_height.toDouble()
        ) {
            return
        }
        val signature = GeometrySignature(
            mode = mode,
            symbol = item.symbol,
            selected = selected,
            radius_bits = radius.toRawBits(),
            clearance_bits = clearance_px.toRawBits(),
        )
        val clearance = clearance_px.toDouble()
        val previous = retained[key]
        val stable = if (previous != null && previous.signature == signature &&
            previous.rect.left <= raw_left - clearance &&
            previous.rect.top <= raw_top - clearance &&
            previous.rect.right >= raw_right + clearance &&
            previous.rect.bottom >= raw_bottom + clearance
        ) {
            previous
        } else {
            val total_padding = clearance + deadband_px.toDouble()
            StableFootprint(
                signature = signature,
                rect = ReferenceScreenRect(
                    left = raw_left - total_padding,
                    top = raw_top - total_padding,
                    right = raw_right + total_padding,
                    bottom = raw_bottom + total_padding,
                ),
                seen_generation = generation,
            ).also { retained[key] = it }
        }
        stable.seen_generation = generation
        output += stable.rect
    }

    private fun advance_generation() {
        if (generation == Int.MAX_VALUE) {
            retained.values.forEach { it.seen_generation = 0 }
            generation = 1
        } else {
            generation++
        }
    }

    private fun prune_unseen() {
        val iterator = retained.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value.seen_generation != generation) iterator.remove()
        }
    }

    private companion object {
        const val DEFAULT_DEADBAND_DP = 4f
        const val MIN_VISIBLE_ALPHA = 4
        const val SYMBOL_DRAW_BLEND_LIMIT = 0.995f
        const val ALL_TRAFFIC_AVOID_MIN_ZOOM = 10.0
    }
}