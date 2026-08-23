@file:Suppress("PropertyName")

package com.flightalert.map.tiles

import android.graphics.Bitmap
import android.graphics.Rect

internal enum class SatelliteBaseRequestKind {
    VISIBLE_PARENT,
    VISIBLE_EXACT,
    UPPER_PREFETCH,
    BUFFER_EXACT,
    BUFFER_PARENT
}

internal data class SatelliteLodState(
    val lower_tile_zoom: Int,
    val upper_tile_zoom: Int,
    val has_upper_lod: Boolean,
    val upper_lod_alpha: Float,
    val prefetch_upper_lod: Boolean,
    val blend_active: Boolean
)

internal data class TileFallback(
    val bitmap: Bitmap,
    val source_rect: Rect
)

internal data class ChildTileFallback(
    val bitmap: Bitmap,
    val child_x: Int,
    val child_y: Int,
    val child_scale: Int
)

internal data class InterimRasterTile(
    val key: String,
    val cache_key: String,
    val z: Int,
    val x: Int,
    val y: Int,
    val bitmap: Bitmap,
    var last_used_ms: Long
)
