@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

import com.flightalert.map.reference.labels.ReferenceLabelCollisionShape
import com.flightalert.map.reference.labels.ReferencePathLabelPoint
import kotlin.math.abs


internal fun reference_retained_leaving_label_alpha(progress: Float): Int {
    return 255 - reference_retained_smoothed_alpha(progress.coerceIn(0f, 1f))
}

internal fun reference_retained_entering_label_alpha(progress: Float): Int {
    val remaining = 1f - progress.coerceIn(0f, 1f)
    val eased = 1f - remaining * remaining * remaining
    return reference_retained_rounded_alpha(eased)
}

internal fun <T> reference_retained_fade_carry(
    previous: T,
    current: T,
    elapsedMs: Long,
    durationMs: Long,
): ReferenceRetainedFadeCarry<T> {
    val elapsed = elapsedMs.coerceIn(0L, durationMs)
    return if (elapsed * 2L < durationMs) {
        ReferenceRetainedFadeCarry(previous, elapsed)
    } else {
        ReferenceRetainedFadeCarry(current, durationMs - elapsed)
    }
}

internal fun <T> reference_retained_displayed_frame(
    established: T?,
    requested: T,
    transition_complete: Boolean,
): T {
    return if (established == null || established === requested || transition_complete) {
        requested
    } else {
        established
    }
}

internal fun <T, K> reference_retained_label_transition(
    previous: List<T>,
    current: List<T>,
    identity: (T) -> K,
    samePlacement: (T, T) -> Boolean = { _, _ -> true },
): ReferenceRetainedLabelTransition<T> {
    val current_by_id = HashMap<K, T>(current.size)
    for (label in current) {
        current_by_id[identity(label)] = label
    }

    val continuing_ids = HashSet<K>(minOf(previous.size, current.size))
    val leaving = ArrayList<T>()
    for (label in previous) {
        val id = identity(label)
        val next = current_by_id[id]
        if (next != null && samePlacement(label, next)) {
            continuing_ids += id
        } else {
            leaving += label
        }
    }

    val continuing_current = BooleanArray(current.size)
    for (index in current.indices) {
        continuing_current[index] = identity(current[index]) in continuing_ids
    }
    return ReferenceRetainedLabelTransition(
        leaving = leaving,
        continuing_current = continuing_current,
    )
}

internal fun reference_retained_label_anchor_is_stable(
    previousAnchor: ReferencePathLabelPoint,
    previousDestination: ReferenceRetainedDestination,
    previousFrameWidth: Int,
    currentAnchor: ReferencePathLabelPoint,
    currentDestination: ReferenceRetainedDestination,
    currentFrameWidth: Int,
): Boolean {
    if (previousFrameWidth <= 0 || currentFrameWidth <= 0) return false
    val previous_scale =
        (previousDestination.right - previousDestination.left) / previousFrameWidth
    val current_scale =
        (currentDestination.right - currentDestination.left) / currentFrameWidth
    val previous_x = previousDestination.left + previousAnchor.x * previous_scale
    val previous_y = previousDestination.top + previousAnchor.y * previous_scale
    val current_x = currentDestination.left + currentAnchor.x * current_scale
    val current_y = currentDestination.top + currentAnchor.y * current_scale
    return abs(previous_x - current_x) <= STABLE_LABEL_ANCHOR_EPSILON_PX &&
        abs(previous_y - current_y) <= STABLE_LABEL_ANCHOR_EPSILON_PX
}

private const val STABLE_LABEL_ANCHOR_EPSILON_PX = 0.5

private fun reference_retained_smoothed_alpha(progress: Float): Int {
    val eased = progress * progress * (3f - 2f * progress)
    return reference_retained_rounded_alpha(eased)
}

private fun reference_retained_rounded_alpha(opacity: Float): Int {
    return (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
}

internal fun reference_retained_label_intersects_viewport(
    collision_shape: ReferenceLabelCollisionShape,
    anchor: ReferencePathLabelPoint,
    zoom_scale: Double,
    translate_x: Double,
    translate_y: Double,
    viewport_width: Double,
    viewport_height: Double,
): Boolean {
    val transformed_anchor_x = translate_x + anchor.x * zoom_scale
    val transformed_anchor_y = translate_y + anchor.y * zoom_scale
    val left: Double
    val top: Double
    val right: Double
    val bottom: Double

    when (collision_shape) {
        is ReferenceLabelCollisionShape.Box -> {
            val box = collision_shape.rect
            left = transformed_anchor_x + box.left - anchor.x
            top = transformed_anchor_y + box.top - anchor.y
            right = transformed_anchor_x + box.right - anchor.x
            bottom = transformed_anchor_y + box.bottom - anchor.y
        }

        is ReferenceLabelCollisionShape.Path -> {
            var minimum_x = Double.POSITIVE_INFINITY
            var minimum_y = Double.POSITIVE_INFINITY
            var maximum_x = Double.NEGATIVE_INFINITY
            var maximum_y = Double.NEGATIVE_INFINITY
            for (point in collision_shape.points) {
                val x = translate_x + point.x * zoom_scale
                val y = translate_y + point.y * zoom_scale
                if (x < minimum_x) minimum_x = x
                if (x > maximum_x) maximum_x = x
                if (y < minimum_y) minimum_y = y
                if (y > maximum_y) maximum_y = y
            }
            left = minimum_x - collision_shape.radiusPx
            top = minimum_y - collision_shape.radiusPx
            right = maximum_x + collision_shape.radiusPx
            bottom = maximum_y + collision_shape.radiusPx
        }
    }

    return left <= viewport_width && top <= viewport_height && right >= 0.0 && bottom >= 0.0
}
