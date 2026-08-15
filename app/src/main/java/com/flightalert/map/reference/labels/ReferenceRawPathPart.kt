@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

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