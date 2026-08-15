@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import com.flightalert.map.Viewport


internal data class BoundaryRasterScene(
    val band: ReferenceBoundaryRasterBand,
    val bounds: ReferenceBoundaryRasterWindowBounds,
    val keys: Set<ReferenceBoundaryRasterKey>,
) {
    fun matches_environment(settingsKey: Long, packageGeneration: Long): Boolean {
        return band.settingsKey == settingsKey &&
            band.packageGeneration == packageGeneration
    }

    fun covers(viewport: Viewport): Boolean {
        return reference_boundary_raster_window_covers(
            bounds = bounds,
            viewportZoom = viewport.zoom,
            centerX = viewport.center_x,
            centerY = viewport.center_y,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
        )
    }
}