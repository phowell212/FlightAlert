@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import android.graphics.Bitmap


internal data class BoundaryRasterTile(
    val key: ReferenceBoundaryRasterKey,
    val bitmap: Bitmap?,
    val gutterPixels: Int,
    val boundaries: Int,
    val byteCount: Long,
)