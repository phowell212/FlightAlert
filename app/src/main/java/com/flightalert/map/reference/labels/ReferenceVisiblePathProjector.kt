@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min



    private const val epsilon = 1e-9

    internal fun prepare(
        parts: List<ReferenceRawPathPart>,
        transform: ReferencePathProjectionTransform,
        viewport: ReferenceScreenRect,
    ): ReferencePreparedPathGeometry {
        val prepared = parts.mapNotNull { part -> preparePart(part, transform, viewport) }
        return ReferencePreparedPathGeometry(viewport, prepared)
    }

    private fun preparePart(
        part: ReferenceRawPathPart,
        transform: ReferencePathProjectionTransform,
        viewport: ReferenceScreenRect,
    ): ReferencePreparedPathPart? {
        if (part.pointCount == 0) return null
        fun localX(index: Int): Float = part.points[index * 2]
        fun localY(index: Int): Float = part.points[index * 2 + 1]
        fun requireFiniteLocal(index: Int) {
            val localX = part.points[index * 2]
            val localY = part.points[index * 2 + 1]
            require(localX.isFinite() && localY.isFinite()) {
                "path-label coordinates must be finite"
            }
        }
        fun projectX(index: Int): Double =
            (transform.translateX + localX(index) * transform.scaleX).toFloat().toDouble()
        fun projectY(index: Int): Double =
            (transform.translateY + localY(index) * transform.scaleY).toFloat().toDouble()

        requireFiniteLocal(0)
        if (part.pointCount == 1) return null
        val segments = ArrayList<ReferencePreparedPathSegment>()
        var cumulative = 0.0
        var startX = projectX(0)
        var startY = projectY(0)
        for (sourceIndex in 0 until part.pointCount - 1) {
            requireFiniteLocal(sourceIndex + 1)
            val endX = projectX(sourceIndex + 1)
            val endY = projectY(sourceIndex + 1)
            val length = hypot(endX - startX, endY - startY)
            require(length.isFinite()) { "path-label segment length must be finite" }
            if (length > epsilon) {
                clipToRect(startX, startY, endX, endY, viewport)?.let { clip ->
                    if (length * (clip.endFraction - clip.startFraction) > epsilon) {
                        segments += ReferencePreparedPathSegment(
                            sourceIndex = sourceIndex,
                            start = ReferencePathLabelPoint(startX, startY),
                            end = ReferencePathLabelPoint(endX, endY),
                            sourceStartDistance = cumulative,
                            length = length,
                            visibleStartFraction = clip.startFraction,
                            visibleEndFraction = clip.endFraction,
                        )
                    }
                }
                cumulative += length
                require(cumulative.isFinite()) { "path-label part length must be finite" }
            }
            startX = endX
            startY = endY
        }
        return if (segments.isEmpty()) null else ReferencePreparedPathPart(
            partIndex = part.partIndex,
            segments = segments,
            fullLength = cumulative,
        )
    }

    private data class VisibleClipInterval(val startFraction: Double, val endFraction: Double)

    private fun clipToRect(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        rect: ReferenceScreenRect,
    ): VisibleClipInterval? {
        val dx = endX - startX
        val dy = endY - startY
        var lower = 0.0
        var upper = 1.0

        fun clip(p: Double, q: Double): Boolean {
            if (abs(p) <= epsilon) return q >= -epsilon
            val ratio = q / p
            if (p < 0.0) {
                if (ratio > upper + epsilon) return false
                lower = max(lower, ratio)
            } else {
                if (ratio < lower - epsilon) return false
                upper = min(upper, ratio)
            }
            return lower <= upper + epsilon
        }

        return if (
            clip(-dx, startX - rect.left) &&
            clip(dx, rect.right - startX) &&
            clip(-dy, startY - rect.top) &&
            clip(dy, rect.bottom - startY)
        ) {
            VisibleClipInterval(lower.coerceIn(0.0, 1.0), upper.coerceIn(0.0, 1.0))
        } else {
            null
        }
    }
