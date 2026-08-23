@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders


internal class BoundaryRasterCellWindow {
    private var raster_zoom = Int.MIN_VALUE
    private var padding_cells = 0
    private var core_first_x = 0
    private var core_first_y = 0
    private var core_last_x = 0
    private var core_last_y = 0
    private var center_x = 0
    private var center_y = 0
    private var key_band: ReferenceBoundaryRasterBand? = null
    private var key_revision = -1
    private val keys = ArrayList<ReferenceBoundaryRasterKey>()
    private var bounds_revision = -1
    private var cached_bounds: ReferenceBoundaryRasterWindowBounds? = null
    private var core_bounds_revision = -1
    private var cached_core_bounds: ReferenceBoundaryRasterWindowBounds? = null
    var revision = 0
        private set

    fun matches(
        next_raster_zoom: Int,
        next_padding_cells: Int,
        next_core_first_x: Int,
        next_core_first_y: Int,
        next_core_last_x: Int,
        next_core_last_y: Int,
        next_center_x: Int,
        next_center_y: Int,
    ): Boolean {
        return raster_zoom == next_raster_zoom &&
            padding_cells == next_padding_cells &&
            core_first_x == next_core_first_x &&
            core_first_y == next_core_first_y &&
            core_last_x == next_core_last_x &&
            core_last_y == next_core_last_y &&
            center_x == next_center_x &&
            center_y == next_center_y
    }

    fun update(
        next_raster_zoom: Int,
        next_padding_cells: Int,
        next_core_first_x: Int,
        next_core_first_y: Int,
        next_core_last_x: Int,
        next_core_last_y: Int,
        next_center_x: Int,
        next_center_y: Int,
    ) {
        raster_zoom = next_raster_zoom
        padding_cells = next_padding_cells
        core_first_x = next_core_first_x
        core_first_y = next_core_first_y
        core_last_x = next_core_last_x
        core_last_y = next_core_last_y
        center_x = next_center_x
        center_y = next_center_y
        revision++
    }

    fun keys_for(
        band: ReferenceBoundaryRasterBand,
        cells: List<ReferenceBoundaryRasterCell>,
    ): List<ReferenceBoundaryRasterKey> {
        if (key_band == band && key_revision == revision) return keys
        keys.clear()
        for (cell in cells) {
            keys += ReferenceBoundaryRasterKey(band, cell.x, cell.y)
        }
        key_band = band
        key_revision = revision
        return keys
    }

    fun bounds(): ReferenceBoundaryRasterWindowBounds {
        if (bounds_revision == revision) return requireNotNull(cached_bounds)
        val tile_limit = 1 shl raster_zoom
        val bounds = ReferenceBoundaryRasterWindowBounds(
            rasterZoom = raster_zoom,
            firstDrawX = core_first_x - padding_cells,
            lastDrawX = core_last_x + padding_cells,
            firstY = (core_first_y - padding_cells).coerceAtLeast(0),
            lastY = (core_last_y + padding_cells).coerceAtMost(tile_limit - 1),
        )
        cached_bounds = bounds
        bounds_revision = revision
        return bounds
    }

    fun core_bounds(): ReferenceBoundaryRasterWindowBounds {
        if (core_bounds_revision == revision) return requireNotNull(cached_core_bounds)
        val tile_limit = 1 shl raster_zoom
        val bounds = ReferenceBoundaryRasterWindowBounds(
            rasterZoom = raster_zoom,
            firstDrawX = core_first_x,
            lastDrawX = core_last_x,
            firstY = core_first_y.coerceAtLeast(0),
            lastY = core_last_y.coerceAtMost(tile_limit - 1),
        )
        cached_core_bounds = bounds
        core_bounds_revision = revision
        return bounds
    }

    fun invalidate() {
        if (raster_zoom != Int.MIN_VALUE) {
            raster_zoom = Int.MIN_VALUE
            revision++
        }
        key_band = null
        key_revision = -1
        keys.clear()
    }
}
