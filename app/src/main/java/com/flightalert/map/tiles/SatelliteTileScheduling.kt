package com.flightalert.map.tiles

internal enum class SatelliteBaseRequestKind {
    VISIBLE_PARENT,
    VISIBLE_EXACT,
    UPPER_PREFETCH,
    BUFFER_EXACT,
    BUFFER_PARENT
}

internal data class SatelliteBaseTileRequestOrder(
    val generation: Long,
    val kind: SatelliteBaseRequestKind,
    val parentDepth: Int = 0,
    val sequence: Long
) {
    val priority: Int = SatelliteBaseTilePolicy.priority(kind, parentDepth)
}

internal object SatelliteBaseTilePolicy {
    fun priority(kind: SatelliteBaseRequestKind, parentDepth: Int = 0): Int {
        return when (kind) {
            SatelliteBaseRequestKind.VISIBLE_PARENT -> {
                require_parent_depth(parentDepth)
                VISIBLE_PARENT_PRIORITY_BASE - parentDepth
            }

            SatelliteBaseRequestKind.VISIBLE_EXACT -> {
                require_non_parent_depth(parentDepth)
                VISIBLE_EXACT_PRIORITY
            }

            SatelliteBaseRequestKind.UPPER_PREFETCH -> {
                require_non_parent_depth(parentDepth)
                UPPER_PREFETCH_PRIORITY
            }

            SatelliteBaseRequestKind.BUFFER_EXACT -> {
                require_non_parent_depth(parentDepth)
                BUFFER_EXACT_PRIORITY
            }

            SatelliteBaseRequestKind.BUFFER_PARENT -> {
                require_parent_depth(parentDepth)
                BUFFER_PARENT_PRIORITY_BASE - parentDepth
            }
        }
    }

    private fun require_parent_depth(parentDepth: Int) {
        require(parentDepth in 1..MAX_PARENT_DEPTH) {
            "Satellite parent depth must be in 1..$MAX_PARENT_DEPTH"
        }
    }

    private fun require_non_parent_depth(parentDepth: Int) {
        require(parentDepth == 0) { "Only parent requests may carry a parent depth" }
    }

    private const val MAX_PARENT_DEPTH = 10
    private const val VISIBLE_PARENT_PRIORITY_BASE = 2
    private const val VISIBLE_EXACT_PRIORITY = 4
    private const val UPPER_PREFETCH_PRIORITY = 5
    private const val BUFFER_EXACT_PRIORITY = 10
    private const val BUFFER_PARENT_PRIORITY_BASE = 24
}

internal object SatelliteBaseTileScheduler {
    fun compare(
        first: SatelliteBaseTileRequestOrder,
        second: SatelliteBaseTileRequestOrder
    ): Int {
        if (first.generation != second.generation) {
            return second.generation.compareTo(first.generation)
        }
        if (first.priority != second.priority) {
            return first.priority.compareTo(second.priority)
        }
        return first.sequence.compareTo(second.sequence)
    }

    fun ordered(requests: Collection<SatelliteBaseTileRequestOrder>): List<SatelliteBaseTileRequestOrder> {
        return requests.sortedWith(::compare)
    }
}
