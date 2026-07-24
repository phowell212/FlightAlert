@file:Suppress("FunctionName", "PropertyName")

package com.flightalert.map

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal class ReferenceRetainedDestination {
    var left = 0.0
        private set
    var top = 0.0
        private set
    var right = 0.0
        private set
    var bottom = 0.0
        private set

    fun set(left: Double, top: Double, right: Double, bottom: Double) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }
}

internal fun reference_retained_destination(
    frame_zoom: Double,
    frame_center_x: Double,
    frame_center_y: Double,
    frame_width: Int,
    frame_height: Int,
    viewport: Viewport,
    destination: ReferenceRetainedDestination,
) {
    val frame_zoom_scale = 2.0.pow(frame_zoom)
    val viewport_zoom_scale = 2.0.pow(viewport.zoom)
    val draw_scale = viewport_zoom_scale / frame_zoom_scale
    val frame_center_x_zero = frame_center_x / frame_zoom_scale
    val viewport_center_x_zero = viewport.center_x / viewport_zoom_scale
    val center_x_delta_zero = shortest_wrapped_world_delta(
        frame_center_x_zero - viewport_center_x_zero
    )
    val center_y_delta_zero =
        frame_center_y / frame_zoom_scale - viewport.center_y / viewport_zoom_scale
    val left = viewport.width / 2.0 + center_x_delta_zero * viewport_zoom_scale -
        frame_width * draw_scale / 2.0
    val top = viewport.height / 2.0 + center_y_delta_zero * viewport_zoom_scale -
        frame_height * draw_scale / 2.0
    destination.set(
        left = left,
        top = top,
        right = left + frame_width * draw_scale,
        bottom = top + frame_height * draw_scale,
    )
}

internal fun reference_retained_covers_viewport(
    destination: ReferenceRetainedDestination,
    viewport: Viewport,
): Boolean {
    return destination.left <= 0.0 &&
        destination.top <= 0.0 &&
        destination.right >= viewport.width &&
        destination.bottom >= viewport.height
}

internal fun reference_vector_scene_needs_refresh(
    destination: ReferenceRetainedDestination,
    viewport: Viewport,
    frameTileZoom: Int,
    targetTileZoom: Int,
    fallbackTileZoom: Int?,
): Boolean {
    return !ReferenceDictionaryLodPolicy.can_reuse_scene(
        sceneZoom = frameTileZoom,
        targetZoom = targetTileZoom,
        fallbackZoom = fallbackTileZoom,
    ) || !reference_retained_covers_viewport(destination, viewport)
}

internal fun reference_retained_scene_can_settle(
    destination: ReferenceRetainedDestination,
    viewport: Viewport,
    frameZoom: Double,
    frameTileZoom: Int,
    targetTileZoom: Int,
    fallbackTileZoom: Int?,
): Boolean {
    return reference_retained_scene_matches_target(
        frameZoom = frameZoom,
        frameTileZoom = frameTileZoom,
        viewportZoom = viewport.zoom,
        targetTileZoom = targetTileZoom,
    ) &&
        !reference_vector_scene_needs_refresh(
            destination = destination,
            viewport = viewport,
            frameTileZoom = frameTileZoom,
            targetTileZoom = targetTileZoom,
            fallbackTileZoom = fallbackTileZoom,
        )
}

internal fun reference_retained_scene_matches_target(
    frameZoom: Double,
    frameTileZoom: Int,
    viewportZoom: Double,
    targetTileZoom: Int,
): Boolean {
    return frameTileZoom == targetTileZoom &&
        abs(frameZoom - viewportZoom) < RETAINED_SCENE_EXACT_ZOOM_EPSILON
}

internal fun reference_should_defer_target_work(
    interactionActive: Boolean,
    labelsEnabled: Boolean,
    retainedLabelsAvailable: Boolean,
    bordersEnabled: Boolean,
    compatibleActiveBoundaryBand: Boolean,
): Boolean {
    return interactionActive &&
        (!labelsEnabled || retainedLabelsAvailable) &&
        (!bordersEnabled || compatibleActiveBoundaryBand)
}

internal fun reference_retained_scene_has_content(
    labelCount: Int,
    boundaryBatchCount: Int,
): Boolean {
    return labelCount > 0 || boundaryBatchCount > 0
}

internal fun reference_retained_viewport_at_zoom(
    viewport: Viewport,
    targetZoom: Double,
): Viewport? {
    if (targetZoom <= viewport.zoom) return null
    val scale = 2.0.pow(targetZoom - viewport.zoom)
    return Viewport(
        zoom = targetZoom,
        center_x = viewport.center_x * scale,
        center_y = viewport.center_y * scale,
        width = viewport.width,
        height = viewport.height,
    )
}

internal fun reference_retained_zoom_ahead_target(
    viewportZoom: Double,
    step: Int,
    interval: Double,
    maximumZoom: Double,
): Double? {
    if (step <= 0 || interval <= 0.0 || viewportZoom >= maximumZoom) return null
    val previous_target = viewportZoom + interval * (step - 1)
    if (previous_target >= maximumZoom) return null
    return minOf(viewportZoom + interval * step, maximumZoom)
}

internal fun reference_retained_zoom_ahead_can_handoff(
    establishedZoom: Double,
    viewportZoom: Double,
    minimumLead: Double,
): Boolean {
    return viewportZoom > establishedZoom + minimumLead
}

internal fun reference_retained_padding_px(
    viewport_width: Float,
    viewport_height: Float,
    tile_size_px: Double,
    maximum_dimension: Int,
    maximum_tiles: Int,
): Int {
    val horizontal_limit = (
        (maximum_dimension - ceil(viewport_width).toInt()) / 2
        ).coerceAtLeast(0)
    val vertical_limit = (
        (maximum_dimension - ceil(viewport_height).toInt()) / 2
        ).coerceAtLeast(0)
    var low = 0
    var high = minOf(horizontal_limit, vertical_limit)
    while (low < high) {
        val candidate = (low + high + 1) / 2
        val tile_count = reference_retained_tile_count_upper_bound(
            viewport_width,
            viewport_height,
            candidate,
            tile_size_px,
        )
        if (tile_count <= maximum_tiles) {
            low = candidate
        } else {
            high = candidate - 1
        }
    }
    return low
}

internal fun reference_retained_scene_padding_px(
    viewportWidth: Float,
    viewportHeight: Float,
    tileSizePx: Double,
    maximumDimension: Int,
    maximumTiles: Int,
    repeatingWorldWidthPx: Double? = null,
): Int {
    val desired_padding = (
        max(viewportWidth, viewportHeight) * RETAINED_SCENE_PADDING_FRACTION
        ).roundToInt().coerceIn(
        RETAINED_SCENE_PADDING_MIN_PX,
        RETAINED_SCENE_PADDING_MAX_PX,
    )
    if (repeatingWorldWidthPx != null) {
        val dimension_limit = minOf(
            (maximumDimension - ceil(viewportWidth).toInt()) / 2,
            (maximumDimension - ceil(viewportHeight).toInt()) / 2,
        ).coerceAtLeast(0)
        return minOf(
            desired_padding,
            ceil(repeatingWorldWidthPx / 2.0).toInt(),
            dimension_limit,
        )
    }
    return minOf(
        desired_padding,
        reference_retained_padding_px(
            viewport_width = viewportWidth,
            viewport_height = viewportHeight,
            tile_size_px = tileSizePx,
            maximum_dimension = maximumDimension,
            maximum_tiles = maximumTiles,
        ),
    )
}

internal fun reference_retained_scene_should_stage(
    destination: ReferenceRetainedDestination,
    viewport: Viewport,
    repeatingWorldWidthPx: Double? = null,
): Boolean {
    if (!reference_retained_covers_viewport(destination, viewport)) return false
    val horizontal_padding =
        ((destination.right - destination.left) - viewport.width) / 2.0
    val vertical_padding =
        ((destination.bottom - destination.top) - viewport.height) / 2.0
    val horizontal_remaining = minOf(
        -destination.left,
        destination.right - viewport.width,
    )
    val vertical_remaining = minOf(
        -destination.top,
        destination.bottom - viewport.height,
    )
    val covers_complete_horizontal_world = repeatingWorldWidthPx != null &&
        destination.right - destination.left - viewport.width >= repeatingWorldWidthPx
    val horizontal_consumed =
        !covers_complete_horizontal_world &&
        horizontal_padding > 0.0 &&
            horizontal_remaining < horizontal_padding * RETAINED_SCENE_STAGE_FRACTION
    val vertical_consumed =
        vertical_padding > 0.0 &&
            vertical_remaining < vertical_padding * RETAINED_SCENE_STAGE_FRACTION
    return horizontal_consumed || vertical_consumed
}

internal fun reference_retained_tile_count_upper_bound(
    viewport_width: Float,
    viewport_height: Float,
    padding: Int,
    tile_size_px: Double,
): Int {
    val columns = ceil((viewport_width + padding * 2.0) / tile_size_px).toInt() + 1
    val rows = ceil((viewport_height + padding * 2.0) / tile_size_px).toInt() + 1
    return columns * rows
}

private fun shortest_wrapped_world_delta(delta: Double): Double {
    return delta - floor(
        (delta + WORLD_WIDTH_AT_ZOOM_ZERO / 2.0) / WORLD_WIDTH_AT_ZOOM_ZERO
    ) * WORLD_WIDTH_AT_ZOOM_ZERO
}

private const val WORLD_WIDTH_AT_ZOOM_ZERO = 256.0
private const val RETAINED_SCENE_PADDING_FRACTION = 0.35f
private const val RETAINED_SCENE_PADDING_MIN_PX = 128
private const val RETAINED_SCENE_PADDING_MAX_PX = 1_024
private const val RETAINED_SCENE_STAGE_FRACTION = 0.5
private const val RETAINED_SCENE_EXACT_ZOOM_EPSILON = 0.000_001
