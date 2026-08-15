@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders


internal data class BoundaryRasterDrawResult(
    val boundaries: Int,
    val ready: Boolean,
    val requested: Int,
)