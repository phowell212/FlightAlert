package com.flightalert.map

internal data class ReferenceRetainedLabelTransition<T>(
    val leaving: List<T>,
    val continuing_current: BooleanArray,
)

internal data class ReferenceRetainedFadeCarry<T>(
    val established: T,
    val elapsedMs: Long,
)

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
    return kotlin.math.abs(previous_x - current_x) <= STABLE_LABEL_ANCHOR_EPSILON_PX &&
        kotlin.math.abs(previous_y - current_y) <= STABLE_LABEL_ANCHOR_EPSILON_PX
}

private const val STABLE_LABEL_ANCHOR_EPSILON_PX = 0.5

private fun reference_retained_smoothed_alpha(progress: Float): Int {
    val eased = progress * progress * (3f - 2f * progress)
    return reference_retained_rounded_alpha(eased)
}

private fun reference_retained_rounded_alpha(opacity: Float): Int {
    return (opacity * 255f + 0.5f).toInt().coerceIn(0, 255)
}
