package com.flightalert.map

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

internal data class ReferenceBoundaryRasterBand(
    val rasterZoom: Int,
    val corePixels: Int,
    val sourceZoom: Int,
    val presentationCentizoom: Int,
    val settingsKey: Long,
    val packageGeneration: Long,
    val visibilityCentizoom: Int = presentationCentizoom,
)

internal data class ReferenceBoundaryRasterSource(
    val originX: Int,
    val originY: Int,
    val minimumTileX: Double,
    val minimumTileY: Double,
    val tileSpan: Double,
)

internal data class ReferenceBoundaryRasterCell(
    val zoom: Int,
    val x: Int,
    val drawX: Int,
    val y: Int,
    val coreVisible: Boolean,
    val requestPriority: Int,
)

internal data class ReferenceBoundaryRasterKey(
    val band: ReferenceBoundaryRasterBand,
    val x: Int,
    val y: Int,
)

internal data class ReferenceBoundaryRasterWindowBounds(
    val rasterZoom: Int,
    val firstDrawX: Int,
    val lastDrawX: Int,
    val firstY: Int,
    val lastY: Int,
)

internal fun reference_boundary_raster_band(
    viewportZoom: Double,
    sourceZoom: Int,
    settingsKey: Long,
    packageGeneration: Long,
): ReferenceBoundaryRasterBand {
    val presentationCentizoom = ReferencePresentationPolicy.centizoom(viewportZoom)
    val presentationZoom = presentationCentizoom / 100.0
    val rasterZoom = max(
        floor(presentationZoom).toInt(),
        minOf(sourceZoom, MINIMUM_LOW_ZOOM_RASTER_LOD),
    )
    val corePixels = ceil(256.0 * 2.0.pow(presentationZoom - rasterZoom))
        .toInt()
        .coerceIn(64, 512)
    return ReferenceBoundaryRasterBand(
        rasterZoom,
        corePixels,
        sourceZoom,
        presentationCentizoom,
        settingsKey,
        packageGeneration,
        max(presentationCentizoom, sourceZoom * 100),
    )
}

internal fun reference_boundary_raster_source(
    rasterZoom: Int,
    rasterX: Int,
    rasterY: Int,
    sourceZoom: Int,
): ReferenceBoundaryRasterSource {
    require(rasterZoom in 0..29 && sourceZoom in 0..29) {
        "raster and source zooms must be inside [0, 29]"
    }
    val sourceTilesPerRasterCell = 2.0.pow(sourceZoom - rasterZoom)
    val minimumSourceX = rasterX * sourceTilesPerRasterCell
    val minimumSourceY = rasterY * sourceTilesPerRasterCell
    val originX = floor(minimumSourceX).toInt()
    val originY = floor(minimumSourceY).toInt()
    return ReferenceBoundaryRasterSource(
        originX = originX,
        originY = originY,
        minimumTileX = minimumSourceX - originX,
        minimumTileY = minimumSourceY - originY,
        tileSpan = sourceTilesPerRasterCell,
    )
}

internal fun reference_boundary_raster_band_transition(
    active: ReferenceBoundaryRasterBand,
    viewportZoom: Double,
    sourceZoom: Int,
    settingsKey: Long,
    packageGeneration: Long,
): Boolean {
    val rasterZoom = max(
        ReferencePresentationPolicy.centizoom(viewportZoom) / 100,
        minOf(sourceZoom, MINIMUM_LOW_ZOOM_RASTER_LOD),
    )
    return rasterZoom != active.rasterZoom ||
        sourceZoom != active.sourceZoom ||
        settingsKey != active.settingsKey ||
        packageGeneration != active.packageGeneration
}

internal fun reference_boundary_raster_should_request_draw_cell(
    drawBand: ReferenceBoundaryRasterBand,
    desiredBand: ReferenceBoundaryRasterBand,
    coreVisible: Boolean,
    fallbackAvailable: Boolean,
): Boolean {
    return drawBand == desiredBand || coreVisible && !fallbackAvailable
}

internal fun reference_boundary_raster_cold_target(
    exact: ReferenceBoundaryRasterBand,
    pending: ReferenceBoundaryRasterBand?,
    interactionActive: Boolean,
): ReferenceBoundaryRasterBand {
    if (!interactionActive || pending == null) return exact
    if (pending.settingsKey != exact.settingsKey ||
        pending.packageGeneration != exact.packageGeneration
    ) return exact
    return pending
}

internal fun reference_boundary_raster_band_is_compatible(
    active: ReferenceBoundaryRasterBand?,
    settingsKey: Long,
    packageGeneration: Long,
): Boolean {
    return active != null &&
        active.settingsKey == settingsKey &&
        active.packageGeneration == packageGeneration
}

internal fun reference_boundary_raster_work_is_suspended(
    interactionActive: Boolean,
    hasActiveBand: Boolean,
): Boolean {
    return interactionActive && hasActiveBand
}

internal fun reference_boundary_raster_safety_zoom(
    viewportZoom: Double,
    minimumZoom: Int,
): Int {
    return max(minimumZoom, floor(viewportZoom).toInt() - SAFETY_RASTER_LOD_DELTA)
}

internal fun reference_boundary_raster_safety_center(
    center: Double,
    viewportZoom: Double,
    safetyZoom: Int,
): Double {
    return center * 2.0.pow(safetyZoom - viewportZoom)
}

internal fun reference_boundary_raster_window_covers(
    bounds: ReferenceBoundaryRasterWindowBounds,
    viewportZoom: Double,
    centerX: Double,
    centerY: Double,
    viewportWidth: Float,
    viewportHeight: Float,
): Boolean {
    val tileScale = 256.0 * 2.0.pow(viewportZoom - bounds.rasterZoom)
    val coreFirstX = floor((centerX - viewportWidth / 2.0) / tileScale).toInt()
    val coreLastX = floor((centerX + viewportWidth / 2.0) / tileScale).toInt()
    val tileLimit = 1 shl bounds.rasterZoom
    val coreFirstY = floor((centerY - viewportHeight / 2.0) / tileScale)
        .toInt()
        .coerceAtLeast(0)
    val coreLastY = floor((centerY + viewportHeight / 2.0) / tileScale)
        .toInt()
        .coerceAtMost(tileLimit - 1)
    if (coreFirstY <= coreLastY &&
        (coreFirstY < bounds.firstY || coreLastY > bounds.lastY)
    ) {
        return false
    }

    val worldShift = Math.floorDiv(bounds.firstDrawX - coreFirstX, tileLimit)
    val alignedFirstX = coreFirstX + worldShift * tileLimit
    val alignedLastX = coreLastX + worldShift * tileLimit
    return alignedFirstX >= bounds.firstDrawX && alignedLastX <= bounds.lastDrawX ||
        alignedFirstX + tileLimit >= bounds.firstDrawX &&
        alignedLastX + tileLimit <= bounds.lastDrawX
}

internal fun reference_boundary_raster_should_draw_safety(
    active: ReferenceBoundaryRasterBand,
    safety: ReferenceBoundaryRasterBand,
    viewportZoom: Double,
): Boolean {
    return reference_boundary_raster_safety_blend(active, safety, viewportZoom) >= 1f
}

internal fun reference_boundary_raster_safety_blend(
    active: ReferenceBoundaryRasterBand,
    safety: ReferenceBoundaryRasterBand,
    viewportZoom: Double,
): Float {
    if (safety.presentationCentizoom >= active.presentationCentizoom) return 0f
    val blendStartZoom = active.presentationCentizoom / 100.0
    val blendEndZoom = safety.presentationCentizoom / 100.0
    return (
        (blendStartZoom - viewportZoom) /
            (blendStartZoom - blendEndZoom)
        )
        .toFloat()
        .coerceIn(0f, 1f)
}

internal fun reference_boundary_raster_continuity_band(
    active: ReferenceBoundaryRasterBand,
    safety: ReferenceBoundaryRasterBand?,
    resident: ReferenceBoundaryRasterBand?,
): ReferenceBoundaryRasterBand? {
    var closestLowerBand: ReferenceBoundaryRasterBand? = null
    if (safety != null && safety.presentationCentizoom < active.presentationCentizoom) {
        closestLowerBand = safety
    }
    if (resident != null &&
        resident.presentationCentizoom < active.presentationCentizoom &&
        (closestLowerBand == null ||
            resident.presentationCentizoom > closestLowerBand.presentationCentizoom)
    ) {
        closestLowerBand = resident
    }
    return closestLowerBand
}

internal fun reference_boundary_raster_fade_underlay(
    elapsedMillis: Long,
    durationMillis: Long,
): Float {
    if (durationMillis <= 0L || elapsedMillis >= durationMillis) return 0f
    if (elapsedMillis <= 0L) return 1f
    val progress = elapsedMillis.toFloat() / durationMillis
    val easedProgress = progress * progress * (3f - 2f * progress)
    return 1f - easedProgress
}

internal fun reference_boundary_raster_padding_cells(
    retainedPaddingPx: Int,
    rasterCellScreenPx: Double,
    interactionActive: Boolean,
): Int {
    if (interactionActive) return 1
    return ceil(retainedPaddingPx.coerceAtLeast(0) / rasterCellScreenPx)
        .toInt()
        .coerceAtLeast(1)
}

internal fun reference_boundary_raster_completion_is_current(
    requestPackageGeneration: Long,
    currentPackageGeneration: Long,
    rendererClosed: Boolean,
): Boolean {
    return !rendererClosed && requestPackageGeneration == currentPackageGeneration
}

internal fun compare_reference_boundary_batch_order(
    firstDrawOrder: Int,
    firstPriority: Int,
    firstSourceFeatureId: ULong,
    secondDrawOrder: Int,
    secondPriority: Int,
    secondSourceFeatureId: ULong,
): Int {
    val drawOrder = firstDrawOrder.compareTo(secondDrawOrder)
    if (drawOrder != 0) return drawOrder
    val priority = firstPriority.compareTo(secondPriority)
    if (priority != 0) return priority
    return firstSourceFeatureId.compareTo(secondSourceFeatureId)
}

internal fun reference_boundary_raster_segment_is_relevant(
    segmentIndex: Int,
    keepRendering: () -> Boolean,
): Boolean {
    return segmentIndex and BOUNDARY_RASTER_CANCELLATION_MASK != 0 || keepRendering()
}

internal fun clip_reference_boundary_segment(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    minimumX: Float,
    minimumY: Float,
    maximumX: Float,
    maximumY: Float,
    output: FloatArray,
): Boolean {
    val dx = endX - startX
    val dy = endY - startY
    output[0] = 0f
    output[1] = 1f
    if (!clip_reference_boundary_edge(-dx, startX - minimumX, output) ||
        !clip_reference_boundary_edge(dx, maximumX - startX, output) ||
        !clip_reference_boundary_edge(-dy, startY - minimumY, output) ||
        !clip_reference_boundary_edge(dy, maximumY - startY, output)
    ) return false

    val clippedStart = output[0]
    val clippedEnd = output[1]
    output[0] = startX + clippedStart * dx
    output[1] = startY + clippedStart * dy
    output[2] = startX + clippedEnd * dx
    output[3] = startY + clippedEnd * dy
    return true
}

private fun clip_reference_boundary_edge(
    delta: Float,
    distance: Float,
    range: FloatArray,
): Boolean {
    if (delta == 0f) return distance >= 0f
    val ratio = distance / delta
    if (delta < 0f) {
        if (ratio > range[1]) return false
        if (ratio > range[0]) range[0] = ratio
    } else {
        if (ratio < range[0]) return false
        if (ratio < range[1]) range[1] = ratio
    }
    return true
}

private const val MINIMUM_LOW_ZOOM_RASTER_LOD = 2
private const val SAFETY_RASTER_LOD_DELTA = 2
private const val BOUNDARY_RASTER_CANCELLATION_MASK = 255
