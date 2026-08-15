@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

data class ReferenceScreenRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left < right && top < bottom)
    }

    fun padded(padding_px: Double): ReferenceScreenRect {
        require(padding_px.isFinite() && padding_px >= 0.0)
        return ReferenceScreenRect(
            left = left - padding_px,
            top = top - padding_px,
            right = right + padding_px,
            bottom = bottom + padding_px,
        )
    }
}