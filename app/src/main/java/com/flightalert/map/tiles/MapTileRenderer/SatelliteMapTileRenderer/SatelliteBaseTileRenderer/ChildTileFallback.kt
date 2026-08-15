@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.graphics.Bitmap


internal data class ChildTileFallback(
    val bitmap: Bitmap,
    val child_x: Int,
    val child_y: Int,
    val child_scale: Int
)