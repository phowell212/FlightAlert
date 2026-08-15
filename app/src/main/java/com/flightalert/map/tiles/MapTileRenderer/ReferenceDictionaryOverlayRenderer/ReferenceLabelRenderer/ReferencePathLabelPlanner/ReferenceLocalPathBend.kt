@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil


internal object ReferenceLocalPathBend {
    private const val Epsilon = 1e-9
    private const val AngleRoundingEpsilon = 1e-7

    fun centiDegrees(part: ReferencePreparedPathPart, sourceDistance: Double): Int {
        val ordinal = segmentOrdinalAt(part, sourceDistance)
        val segment = part.segments[ordinal]
        if (abs(sourceDistance - segment.sourceEndDistance) > Epsilon) return 0
        val next = part.segments.getOrNull(ordinal + 1) ?: return 0
        if (abs(next.sourceStartDistance - segment.sourceEndDistance) > Epsilon) return 0
        val firstAngle = atan2(segment.dy, segment.dx)
        var nextAngle = atan2(next.dy, next.dx)
        while (nextAngle - firstAngle > Math.PI) nextAngle -= Math.PI * 2.0
        while (nextAngle - firstAngle < -Math.PI) nextAngle += Math.PI * 2.0
        return ceil(abs(Math.toDegrees(nextAngle - firstAngle)) * 100.0 - AngleRoundingEpsilon)
            .toInt()
            .coerceAtLeast(0)
    }

    private fun segmentOrdinalAt(part: ReferencePreparedPathPart, sourceDistance: Double): Int {
        val canonical = sourceDistance.coerceIn(0.0, part.fullLength)
        var lower = 0
        var upper = part.segments.lastIndex
        while (lower < upper) {
            val middle = lower + (upper - lower) / 2
            if (canonical <= part.segments[middle].sourceEndDistance + Epsilon) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        return lower
    }
}