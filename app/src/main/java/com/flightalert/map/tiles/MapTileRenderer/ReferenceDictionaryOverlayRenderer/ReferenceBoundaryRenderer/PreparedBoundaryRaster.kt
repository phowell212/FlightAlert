@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders


internal data class PreparedBoundaryRaster(
    val key: ReferenceBoundaryRasterKey,
    val tile: BoundaryRasterTile?,
)