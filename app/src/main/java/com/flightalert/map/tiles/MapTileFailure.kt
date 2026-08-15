@file:Suppress(
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.tiles

import com.flightalert.map.TileSource


data class MapTileFailure(
    val source: TileSource,
    val kind: MapTileFailureKind,
)