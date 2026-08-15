@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders


internal data class BoundaryRasterFade(
    val previous: BoundaryRasterScene,
    val currentBand: ReferenceBoundaryRasterBand,
    val startMs: Long,
)