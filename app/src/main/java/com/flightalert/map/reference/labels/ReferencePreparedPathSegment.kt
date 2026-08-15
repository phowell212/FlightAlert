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