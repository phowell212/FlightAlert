package com.flightalert.map

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

internal data class ReferenceRawPathPart(
    val partIndex: Int,
    val points: FloatArray,
    val pointCount: Int,
) {
    init {
        require(partIndex >= 0) { "path part index must be nonnegative" }
        require(pointCount >= 0 && pointCount <= points.size / 2) {
            "path point count exceeds the coordinate array"
        }
    }
}

internal data class ReferencePathProjectionTransform(
    val scaleX: Double,
    val scaleY: Double,
    val translateX: Double,
    val translateY: Double,
) {
    init {
        require(scaleX.isFinite() && scaleX > 0.0) { "path x scale must be finite and positive" }
        require(scaleY.isFinite() && scaleY > 0.0) { "path y scale must be finite and positive" }
        require(translateX.isFinite() && translateY.isFinite()) {
            "path translation must be finite"
        }
    }

    companion object {
        fun identity() = ReferencePathProjectionTransform(1.0, 1.0, 0.0, 0.0)
    }
}

internal data class ReferencePreparedPathSegment(
    val sourceIndex: Int,
    val start: ReferencePathLabelPoint,
    val end: ReferencePathLabelPoint,
    val sourceStartDistance: Double,
    val length: Double,
    val visibleStartFraction: Double,
    val visibleEndFraction: Double,
) {
    init {
        require(sourceIndex >= 0) { "path source index must be nonnegative" }
        require(sourceStartDistance.isFinite() && sourceStartDistance >= 0.0) {
            "path source distance must be finite and nonnegative"
        }
        require(length.isFinite() && length > 0.0) { "path segment length must be finite and positive" }
        require(visibleStartFraction.isFinite() && visibleStartFraction in 0.0..1.0)
        require(visibleEndFraction.isFinite() && visibleEndFraction in 0.0..1.0)
        require(visibleStartFraction < visibleEndFraction) {
            "visible path segment must have positive length"
        }
    }

    val sourceEndDistance: Double get() = sourceStartDistance + length
    val dx: Double get() = end.x - start.x
    val dy: Double get() = end.y - start.y
}

internal data class ReferencePreparedPathPart(
    val partIndex: Int,
    val segments: List<ReferencePreparedPathSegment>,
    val fullLength: Double,
) {
    init {
        require(partIndex >= 0) { "path part index must be nonnegative" }
        require(segments.isNotEmpty()) { "prepared path part must retain a visible segment" }
        require(fullLength.isFinite() && fullLength > 0.0) {
            "prepared path length must be finite and positive"
        }
    }

    val supportIndex: ReferenceScreenSegmentAabbIndex by lazy(LazyThreadSafetyMode.NONE) {
        ReferenceScreenSegmentAabbIndex(segments)
    }
}

internal data class ReferencePreparedPathGeometry(
    val viewport: ReferenceScreenRect,
    val parts: List<ReferencePreparedPathPart>,
)

internal object ReferenceVisiblePathProjector {
    private const val epsilon = 1e-9

    fun prepare(
        parts: List<ReferenceRawPathPart>,
        transform: ReferencePathProjectionTransform,
        viewport: ReferenceScreenRect,
    ): ReferencePreparedPathGeometry {
        val prepared = parts.mapNotNull { part -> preparePart(part, transform, viewport) }
        return ReferencePreparedPathGeometry(viewport, prepared)
    }

    fun prepareScreenParts(
        parts: List<List<ReferencePathLabelPoint>>,
        viewport: ReferenceScreenRect,
    ): ReferencePreparedPathGeometry {
        val prepared = parts.mapIndexedNotNull { partIndex, points ->
            points.forEach { point ->
                require(point.x.isFinite() && point.y.isFinite()) {
                    "path-label coordinates must be finite"
                }
            }
            prepareProjectedPart(partIndex, points, viewport)
        }
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

    private fun prepareProjectedPart(
        partIndex: Int,
        points: List<ReferencePathLabelPoint>,
        viewport: ReferenceScreenRect,
    ): ReferencePreparedPathPart? {
        if (points.size < 2) return null
        val segments = ArrayList<ReferencePreparedPathSegment>()
        var cumulative = 0.0
        for (sourceIndex in 0 until points.lastIndex) {
            val start = points[sourceIndex]
            val end = points[sourceIndex + 1]
            val length = hypot(end.x - start.x, end.y - start.y)
            require(length.isFinite()) { "path-label segment length must be finite" }
            if (length > epsilon) {
                clipToRect(start.x, start.y, end.x, end.y, viewport)?.let { clip ->
                    if (length * (clip.endFraction - clip.startFraction) > epsilon) {
                        segments += ReferencePreparedPathSegment(
                            sourceIndex = sourceIndex,
                            start = start,
                            end = end,
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
        }
        return if (segments.isEmpty()) null else ReferencePreparedPathPart(
            partIndex = partIndex,
            segments = segments,
            fullLength = cumulative,
        )
    }

    private data class ClipInterval(val startFraction: Double, val endFraction: Double)

    private fun clipToRect(
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        rect: ReferenceScreenRect,
    ): ClipInterval? {
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
            ClipInterval(lower.coerceIn(0.0, 1.0), upper.coerceIn(0.0, 1.0))
        } else {
            null
        }
    }
}
