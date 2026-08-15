@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


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
}