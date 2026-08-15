@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions
import com.flightalert.map.reference.labels.ReferenceScreenRect
import kotlin.math.abs
import kotlin.math.ceil


internal data class RetainedReferenceFrame(
    val zoom: Double,
    val center_x: Double,
    val center_y: Double,
    val tile_zoom: Int,
    val width: Int,
    val height: Int,
    val visible_width: Int,
    val visible_height: Int,
    val options: RetainedReferenceOptions,
    val package_generation: Long,
    val label_plan_key: RetainedLabelPlanKey?,
    val labels: List<DictionaryLabelCandidate>,
    val label_coverage: ReferenceRetainedLabelCoverage,
) {
    val labels_drawn: Int get() = labels.size

    fun matches_options(next: RetainedReferenceOptions): Boolean {
        return options == next
    }

    fun matches_label_avoidance(next: List<ReferenceScreenRect>): Boolean {
        return !options.labels_enabled || label_plan_key?.labelAvoidRects == next
    }

    fun matches_environment(
        viewport: Viewport,
        next: RetainedReferenceOptions,
        current_package_generation: Long,
    ): Boolean {
        return matches_options(next) &&
            package_generation == current_package_generation &&
            visible_width == ceil(viewport.width).toInt().coerceAtLeast(1) &&
            visible_height == ceil(viewport.height).toInt().coerceAtLeast(1)
    }

    fun matches_exact(
        viewport: Viewport,
        next_tile_zoom: Int,
        next: RetainedReferenceOptions,
        current_package_generation: Long,
    ): Boolean {
        return matches_environment(viewport, next, current_package_generation) &&
                tile_zoom == next_tile_zoom &&
                abs(zoom - viewport.zoom) < EXACT_VIEWPORT_EPSILON &&
                abs(center_x - viewport.center_x) < EXACT_VIEWPORT_EPSILON &&
                abs(center_y - viewport.center_y) < EXACT_VIEWPORT_EPSILON
    }

    fun has_content(): Boolean {
        return labels.isNotEmpty()
    }

    fun is_drawable(): Boolean {
        return has_content()
    }

    fun has_visible_content(
        viewport: Viewport,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        if (labels.isEmpty()) return false
        val zoom_scale = (destination.right - destination.left) / width
        if (!zoom_scale.isFinite() || zoom_scale <= 0.0) return false
        return label_coverage.intersects(
            left = -destination.left / zoom_scale,
            top = -destination.top / zoom_scale,
            right = (viewport.width - destination.left) / zoom_scale,
            bottom = (viewport.height - destination.top) / zoom_scale,
        )
    }
}