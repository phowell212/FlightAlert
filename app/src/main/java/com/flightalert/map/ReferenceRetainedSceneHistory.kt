package com.flightalert.map

import java.util.ArrayDeque

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

    fun clear() {
        scenes.clear()
    }

    override fun iterator(): Iterator<T> = scenes.iterator()
}
