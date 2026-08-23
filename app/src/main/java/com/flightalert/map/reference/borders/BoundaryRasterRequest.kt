@file:Suppress(
    "FunctionName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.ParsedDictionaryTile
import com.flightalert.map.reference.dictionary.ReferenceDictionaryLayerGroups
import com.flightalert.map.reference.dictionary.ReferenceFilterMask


internal data class ReferenceBoundaryRasterSource(
    val originX: Int,
    val originY: Int,
    val minimumTileX: Double,
    val minimumTileY: Double,
    val tileSpan: Double,
)

internal data class BoundaryRasterSourceTile(
    val tile: ParsedDictionaryTile,
    val drawX: Int,
    val y: Int,
)

internal data class BoundaryRasterRequest(
    val key: ReferenceBoundaryRasterKey,
    val source: ReferenceBoundaryRasterSource,
    val tiles: List<BoundaryRasterSourceTile>,
    val groups: ReferenceDictionaryLayerGroups,
    val filter_mask: ReferenceFilterMask,
)

internal enum class BoundaryPaintPass { HALO, STROKE }

internal data class ReferenceBoundaryRasterWindowBounds(
    val rasterZoom: Int,
    val firstDrawX: Int,
    val lastDrawX: Int,
    val firstY: Int,
    val lastY: Int,
)

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

internal data class PreparedBoundaryRaster(
    val key: ReferenceBoundaryRasterKey,
    val tile: BoundaryRasterTile?,
)
