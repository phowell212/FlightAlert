@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal data class ReferencePathSourcePosition(
    val partIndex: Int,
    val segmentIndex: Int,
    val segmentFraction: Double,
) {
    init {
        require(partIndex >= 0 && segmentIndex >= 0) { "source indices must be nonnegative" }
        require(segmentFraction.isFinite() && segmentFraction in 0.0..1.0) {
            "source segment fraction must be inside [0, 1]"
        }
    }
}