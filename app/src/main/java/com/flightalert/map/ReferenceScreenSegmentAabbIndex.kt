package com.flightalert.map

import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/** Exact screen-space segment AABB queries with results in the supplied source order. */
internal class ReferenceScreenSegmentAabbIndex(
    segmentsInSourceOrder: List<ReferencePreparedPathSegment>,
) {
    private val segments = segmentsInSourceOrder.toList()
    private val root: Node? = if (segments.isEmpty()) {
        null
    } else {
        val entries = Array(segments.size) { ordinal ->
            val segment = segments[ordinal]
            val visibleStartX = segment.start.x + segment.dx * segment.visibleStartFraction
            val visibleStartY = segment.start.y + segment.dy * segment.visibleStartFraction
            val visibleEndX = segment.start.x + segment.dx * segment.visibleEndFraction
            val visibleEndY = segment.start.y + segment.dy * segment.visibleEndFraction
            Entry(
                ordinal = ordinal,
                bounds = Bounds(
                    left = min(visibleStartX, visibleEndX),
                    top = min(visibleStartY, visibleEndY),
                    right = max(visibleStartX, visibleEndX),
                    bottom = max(visibleStartY, visibleEndY),
                ),
            )
        }
        build(entries, 0, entries.size)
    }

    fun queryInto(
        queryStartX: Double,
        queryStartY: Double,
        queryEndX: Double,
        queryEndY: Double,
        radiusPx: Double,
        sourceOrdinalStartInclusive: Int = 0,
        sourceOrdinalEndExclusive: Int = segments.size,
        scratch: ReferenceScreenSegmentAabbQueryScratch,
    ) {
        require(radiusPx.isFinite() && radiusPx >= 0.0) {
            "path support query radius must be finite and nonnegative"
        }
        require(
            sourceOrdinalStartInclusive in 0..segments.size &&
                sourceOrdinalEndExclusive in sourceOrdinalStartInclusive..segments.size,
        ) { "path support source ordinal window is outside the prepared segments" }
        scratch.clear()
        if (sourceOrdinalStartInclusive == sourceOrdinalEndExclusive) return
        val node = root ?: return
        collect(
            node = node,
            queryLeft = subtractSaturated(min(queryStartX, queryEndX), radiusPx),
            queryTop = subtractSaturated(min(queryStartY, queryEndY), radiusPx),
            queryRight = addSaturated(max(queryStartX, queryEndX), radiusPx),
            queryBottom = addSaturated(max(queryStartY, queryEndY), radiusPx),
            sourceOrdinalStartInclusive = sourceOrdinalStartInclusive,
            sourceOrdinalEndExclusive = sourceOrdinalEndExclusive,
            matches = scratch,
        )
        scratch.sort()
    }

    fun matchAt(
        scratch: ReferenceScreenSegmentAabbQueryScratch,
        matchIndex: Int,
    ): ReferencePreparedPathSegment {
        return segments[scratch.ordinalAt(matchIndex)]
    }

    private fun build(entries: Array<Entry>, fromIndex: Int, toIndex: Int): Node {
        if (toIndex - fromIndex == 1) return Leaf(entries[fromIndex])
        val bounds = Bounds.enclosing(entries, fromIndex, toIndex)
        val compareOnX = bounds.width >= bounds.height
        Arrays.sort(entries, fromIndex, toIndex) { first, second ->
            val primary = if (compareOnX) {
                compareValues(first.bounds.left, second.bounds.left)
            } else {
                compareValues(first.bounds.top, second.bounds.top)
            }
            if (primary != 0) {
                primary
            } else {
                val secondary = if (compareOnX) {
                    compareValues(first.bounds.right, second.bounds.right)
                } else {
                    compareValues(first.bounds.bottom, second.bounds.bottom)
                }
                if (secondary != 0) secondary else first.ordinal.compareTo(second.ordinal)
            }
        }
        val midpoint = fromIndex + (toIndex - fromIndex) / 2
        return Branch(
            bounds = bounds,
            first = build(entries, fromIndex, midpoint),
            second = build(entries, midpoint, toIndex),
        )
    }

    private fun collect(
        node: Node,
        queryLeft: Double,
        queryTop: Double,
        queryRight: Double,
        queryBottom: Double,
        sourceOrdinalStartInclusive: Int,
        sourceOrdinalEndExclusive: Int,
        matches: ReferenceScreenSegmentAabbQueryScratch,
    ) {
        if (
            node.maximumSourceOrdinal < sourceOrdinalStartInclusive ||
            node.minimumSourceOrdinal >= sourceOrdinalEndExclusive
        ) return
        if (!node.bounds.intersects(queryLeft, queryTop, queryRight, queryBottom)) return
        when (node) {
            is Leaf -> {
                if (
                    node.entry.ordinal in
                        sourceOrdinalStartInclusive until sourceOrdinalEndExclusive &&
                    node.entry.bounds.intersects(queryLeft, queryTop, queryRight, queryBottom)
                ) {
                    matches.add(node.entry.ordinal)
                }
            }
            is Branch -> {
                collect(
                    node.first,
                    queryLeft,
                    queryTop,
                    queryRight,
                    queryBottom,
                    sourceOrdinalStartInclusive,
                    sourceOrdinalEndExclusive,
                    matches,
                )
                collect(
                    node.second,
                    queryLeft,
                    queryTop,
                    queryRight,
                    queryBottom,
                    sourceOrdinalStartInclusive,
                    sourceOrdinalEndExclusive,
                    matches,
                )
            }
        }
    }

    private sealed interface Node {
        val bounds: Bounds
        val minimumSourceOrdinal: Int
        val maximumSourceOrdinal: Int
    }

    private data class Leaf(
        val entry: Entry,
    ) : Node {
        override val bounds: Bounds get() = entry.bounds
        override val minimumSourceOrdinal: Int get() = entry.ordinal
        override val maximumSourceOrdinal: Int get() = entry.ordinal
    }

    private data class Branch(
        override val bounds: Bounds,
        val first: Node,
        val second: Node,
    ) : Node {
        override val minimumSourceOrdinal: Int = min(
            first.minimumSourceOrdinal,
            second.minimumSourceOrdinal,
        )
        override val maximumSourceOrdinal: Int = max(
            first.maximumSourceOrdinal,
            second.maximumSourceOrdinal,
        )
    }

    private data class Entry(
        val ordinal: Int,
        val bounds: Bounds,
    )

    private data class Bounds(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        val width: Double get() = right - left
        val height: Double get() = bottom - top

        fun intersects(
            otherLeft: Double,
            otherTop: Double,
            otherRight: Double,
            otherBottom: Double,
        ): Boolean =
            right >= otherLeft &&
                left <= otherRight &&
                bottom >= otherTop &&
                top <= otherBottom

        companion object {
            fun enclosing(entries: Array<Entry>, fromIndex: Int, toIndex: Int): Bounds {
                var left = Double.POSITIVE_INFINITY
                var top = Double.POSITIVE_INFINITY
                var right = Double.NEGATIVE_INFINITY
                var bottom = Double.NEGATIVE_INFINITY
                for (index in fromIndex until toIndex) {
                    val bounds = entries[index].bounds
                    left = min(left, bounds.left)
                    top = min(top, bounds.top)
                    right = max(right, bounds.right)
                    bottom = max(bottom, bounds.bottom)
                }
                return Bounds(left, top, right, bottom)
            }
        }
    }

    private companion object {
        fun subtractSaturated(value: Double, amount: Double): Double {
            val result = value - amount
            return if (result == Double.NEGATIVE_INFINITY) -Double.MAX_VALUE else result
        }

        fun addSaturated(value: Double, amount: Double): Double {
            val result = value + amount
            return if (result == Double.POSITIVE_INFINITY) Double.MAX_VALUE else result
        }
    }
}

internal object ReferenceSourceSegmentOrdinalWindow {
    fun startInclusive(
        segments: List<ReferencePreparedPathSegment>,
        sourceStartDistance: Double,
        epsilon: Double,
    ): Int {
        require(sourceStartDistance.isFinite()) { "source start distance must be finite" }
        require(epsilon.isFinite() && epsilon >= 0.0) {
            "source distance epsilon must be finite and nonnegative"
        }
        var lower = 0
        var upper = segments.size
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (segments[middle].sourceEndDistance + epsilon >= sourceStartDistance) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }

    fun endExclusive(
        segments: List<ReferencePreparedPathSegment>,
        sourceEndDistance: Double,
        epsilon: Double,
    ): Int {
        require(sourceEndDistance.isFinite()) { "source end distance must be finite" }
        require(epsilon.isFinite() && epsilon >= 0.0) {
            "source distance epsilon must be finite and nonnegative"
        }
        var lower = 0
        var upper = segments.size
        val inclusiveUpperDistance = sourceEndDistance + epsilon
        while (lower < upper) {
            val middle = (lower + upper) ushr 1
            if (segments[middle].sourceStartDistance > inclusiveUpperDistance) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }
}

internal class ReferenceScreenSegmentAabbQueryScratch(initialCapacity: Int = 16) {
    private var values = IntArray(max(1, initialCapacity))
    var size: Int = 0
        private set

    internal fun clear() {
        size = 0
    }

    internal fun add(value: Int) {
        if (size == values.size) {
            values = values.copyOf(values.size * 2)
        }
        values[size++] = value
    }

    internal fun ordinalAt(index: Int): Int {
        require(index in 0 until size)
        return values[index]
    }

    internal fun sort() {
        Arrays.sort(values, 0, size)
    }
}
