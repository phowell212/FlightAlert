package com.flightalert.map

internal data class ReferenceRetainedLabelTransition<T>(
    val leaving: List<T>,
    val continuing_current: BooleanArray,
)

internal fun <T, K> reference_retained_label_transition(
    previous: List<T>,
    current: List<T>,
    identity: (T) -> K,
): ReferenceRetainedLabelTransition<T> {
    val current_ids = HashSet<K>(current.size)
    for (label in current) {
        current_ids += identity(label)
    }

    val previous_ids = HashSet<K>(previous.size)
    val leaving = ArrayList<T>()
    for (label in previous) {
        val id = identity(label)
        previous_ids += id
        if (id !in current_ids) leaving += label
    }

    val continuing_current = BooleanArray(current.size)
    for (index in current.indices) {
        continuing_current[index] = identity(current[index]) in previous_ids
    }
    return ReferenceRetainedLabelTransition(
        leaving = leaving,
        continuing_current = continuing_current,
    )
}
