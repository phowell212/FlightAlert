@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.reference.borders

internal data class ReferenceBoundaryRasterBand(
    val rasterZoom: Int,
    val corePixels: Int,
    val sourceZoom: Int,
    val presentationCentizoom: Int,
    val settingsKey: Long,
    val packageGeneration: Long,
    val visibilityCentizoom: Int = presentationCentizoom,
)