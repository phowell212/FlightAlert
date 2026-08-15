@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.graphics.Bitmap
import android.graphics.Rect


internal data class TileFallback(
    val bitmap: Bitmap,
    val source_rect: Rect
)