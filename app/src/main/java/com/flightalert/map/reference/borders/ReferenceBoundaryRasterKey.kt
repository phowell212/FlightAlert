@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.reference.borders


internal data class ReferenceBoundaryRasterKey(
    val band: ReferenceBoundaryRasterBand,
    val x: Int,
    val y: Int,
)