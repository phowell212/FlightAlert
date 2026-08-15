@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.tiles

import com.flightalert.map.reference.borders.REFERENCE_BOUNDARY_RASTER_CACHE_ROOT

internal val MANAGED_TILE_ROOT_NAMES = setOf(
    "carto_voyager_tiles",
    "carto_voyager_nolabels_tiles",
    "carto_voyager_only_labels_tiles",
    "esri_world_imagery_tiles",
    REFERENCE_BOUNDARY_RASTER_CACHE_ROOT,
)