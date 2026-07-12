package com.flightalert.map

internal data class SatelliteBaseTileRequestOrder(
    val generation: Long,
    val kind: SatelliteBaseRequestKind,
    val parentDepth: Int = 0,
    val sequence: Long
) {
    val priority: Int = SatelliteBaseTilePolicy.priority(kind, parentDepth)
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
