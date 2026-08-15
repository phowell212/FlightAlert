@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.graphics.Bitmap


internal data class InterimRasterTile(
    val key: String,
    val cache_key: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val bitmap: Bitmap,
    var last_used_ms: Long
)