@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


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
