@file:Suppress("FunctionName", "PropertyName")

package com.flightalert.map

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

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
