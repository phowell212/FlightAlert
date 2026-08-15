@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import android.graphics.Canvas
import com.flightalert.map.Viewport

internal fun interface SatelliteBaseOverlayDrawer {
    fun draw(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState
    ): TileLayerDrawStats
}