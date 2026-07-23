package com.flightalert.map

internal data class ReferenceBoundaryOccurrenceKey(
    val dedupeId: ULong,
    val renderedWorldCopy: Long,
)

internal class ReferenceBoundaryOccurrenceSet(initialCapacity: Int = 512) {
    private var dedupeIds: LongArray
    private var worldCopies: LongArray
    private var generations: IntArray
    private var generation = 1
    private var size = 0
    private var resizeAt: Int

    init {
        require(initialCapacity > 0) { "initial capacity must be positive" }
        var capacity = 4
        while (capacity < initialCapacity) capacity = capacity shl 1
        dedupeIds = LongArray(capacity)
        worldCopies = LongArray(capacity)
        generations = IntArray(capacity)
        resizeAt = capacity * 2 / 3
    }

    fun clear() {
        size = 0
        if (generation == Int.MAX_VALUE) {
            generations.fill(0)
            generation = 1
        } else {
            generation++
        }
    }

    fun admit(dedupeId: ULong?, renderedWorldCopy: Long): Boolean {
        if (dedupeId == null) return true
        val id = dedupeId.toLong()
        var slot = findSlot(id, renderedWorldCopy)
        if (generations[slot] == generation) return false
        if (size >= resizeAt) {
            grow()
            slot = findSlot(id, renderedWorldCopy)
        }
        generations[slot] = generation
        dedupeIds[slot] = id
        worldCopies[slot] = renderedWorldCopy
        size++
        return true
    }

    private fun findSlot(dedupeId: Long, renderedWorldCopy: Long): Int {
        val mask = dedupeIds.lastIndex
        var slot = occurrenceHash(dedupeId, renderedWorldCopy).toInt() and mask
        while (generations[slot] == generation) {
            if (dedupeIds[slot] == dedupeId && worldCopies[slot] == renderedWorldCopy) {
                return slot
            }
            slot = (slot + 1) and mask
        }
        return slot
    }

    private fun grow() {
        val previousIds = dedupeIds
        val previousWorldCopies = worldCopies
        val previousGenerations = generations
        val previousGeneration = generation
        dedupeIds = LongArray(previousIds.size shl 1)
        worldCopies = LongArray(dedupeIds.size)
        generations = IntArray(dedupeIds.size)
        generation = 1
        size = 0
        resizeAt = dedupeIds.size * 2 / 3
        for (index in previousIds.indices) {
            if (previousGenerations[index] != previousGeneration) continue
            val slot = findSlot(previousIds[index], previousWorldCopies[index])
            generations[slot] = generation
            dedupeIds[slot] = previousIds[index]
            worldCopies[slot] = previousWorldCopies[index]
            size++
        }
    }

    private fun occurrenceHash(dedupeId: Long, renderedWorldCopy: Long): Long {
        var hash = dedupeId xor java.lang.Long.rotateLeft(renderedWorldCopy, 29)
        hash = (hash xor (hash ushr 33)) * -49064778989728563L
        hash = (hash xor (hash ushr 33)) * -4265267296055464877L
        return hash xor (hash ushr 33)
    }
}

/** Admits one complete canonical outline path per displayed wrapped-world copy. */
internal object ReferenceBoundaryOccurrenceSelector {
    fun admit(
        seen: MutableSet<ReferenceBoundaryOccurrenceKey>,
        dedupeId: ULong?,
        renderedWorldCopy: Long,
    ): Boolean = dedupeId == null || seen.add(
        ReferenceBoundaryOccurrenceKey(dedupeId, renderedWorldCopy),
    )

    fun renderedWorldCopy(
        zoom: Int,
        requestedTileX: Int,
        drawTileX: Int,
        postingWorldWrap: Int,
    ): Long {
        require(zoom in 0..29) { "reference tile zoom is outside [0,29]" }
        val worldTiles = 1L shl zoom
        val drawWrap = Math.floorDiv(
            drawTileX.toLong() - requestedTileX.toLong(),
            worldTiles,
        )
        return drawWrap - postingWorldWrap.toLong()
    }
}
