@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


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