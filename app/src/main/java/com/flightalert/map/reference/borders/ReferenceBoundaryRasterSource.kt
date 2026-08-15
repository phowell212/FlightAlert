@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.reference.borders


internal data class ReferenceBoundaryRasterSource(
    val originX: Int,
    val originY: Int,
    val minimumTileX: Double,
    val minimumTileY: Double,
    val tileSpan: Double,
)