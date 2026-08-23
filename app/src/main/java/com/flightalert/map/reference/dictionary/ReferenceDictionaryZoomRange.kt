@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class ReferenceDictionaryZoomRange(
    val z: Int,
    val x_min: Int,
    val x_max: Int,
    val y_min: Int,
    val y_max: Int,
    val tile_count: Long,
    val first_ordinal: Long,
) {
    val width: Int = x_max - x_min + 1
}
