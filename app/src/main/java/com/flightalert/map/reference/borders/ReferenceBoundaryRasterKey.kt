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

internal data class ReferenceBoundaryRasterKey(
    val band: ReferenceBoundaryRasterBand,
    val x: Int,
    val y: Int,
)

internal data class ReferenceBoundaryOccurrenceKey(
    val dedupeId: ULong,
    val renderedWorldCopy: Long,
)

internal data class ReferenceBoundaryRasterCell(
    val zoom: Int,
    val x: Int,
    val drawX: Int,
    val y: Int,
    val coreVisible: Boolean,
    val requestPriority: Int,
)
