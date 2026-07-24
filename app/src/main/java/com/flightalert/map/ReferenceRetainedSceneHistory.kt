package com.flightalert.map

import java.util.ArrayDeque

internal enum class ReferenceRetainedCompletedTargetDisposition {
    PUBLISH,
    REMEMBER,
    DISCARD,
}

internal fun reference_retained_completed_target_disposition(
    environmentMatches: Boolean,
    matchesCurrentTarget: Boolean,
    interactionActive: Boolean,
): ReferenceRetainedCompletedTargetDisposition {
    if (!environmentMatches) return ReferenceRetainedCompletedTargetDisposition.DISCARD
    if (matchesCurrentTarget && !interactionActive) {
        return ReferenceRetainedCompletedTargetDisposition.PUBLISH
    }
    return ReferenceRetainedCompletedTargetDisposition.REMEMBER
}

internal class ReferenceRetainedSceneHistory<T : Any>(
    private val limit: Int,
) : Iterable<T> {
    private val scenes = ArrayDeque<T>(limit)

    init {
        require(limit > 0) { "retained scene history limit must be positive" }
    }

    fun remember(scene: T) {
        val iterator = scenes.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() === scene) {
                iterator.remove()
                break
            }
        }
        scenes.addFirst(scene)
        while (scenes.size > limit) {
            scenes.removeLast()
        }
    }

    fun remove(scene: T): Boolean {
        val iterator = scenes.iterator()
        while (iterator.hasNext()) {
            if (iterator.next() !== scene) continue
            iterator.remove()
            return true
        }
        return false
    }

    fun clear() {
        scenes.clear()
    }

    override fun iterator(): Iterator<T> = scenes.iterator()
}
