@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.reference.borders


internal data class ReferenceBoundaryRasterWindowBounds(
    val rasterZoom: Int,
    val firstDrawX: Int,
    val lastDrawX: Int,
    val firstY: Int,
    val lastY: Int,
)