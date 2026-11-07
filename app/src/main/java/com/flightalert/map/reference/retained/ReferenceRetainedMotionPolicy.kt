@file:Suppress("FunctionName")

package com.flightalert.map.reference.retained

import com.flightalert.map.Viewport
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions
import com.flightalert.map.reference.labels.ReferenceScreenRect
import kotlin.math.ceil

internal fun reference_retained_request_covers_motion(
    key: RetainedLabelPlanKey,
    role: RetainedFrameRole,
    viewport: Viewport,
    tileZoom: Int,
    options: RetainedReferenceOptions,
    packageGeneration: Long,
    avoidRects: List<ReferenceScreenRect>,
): Boolean {
    if (key.role != role ||
        key.tileZoom != tileZoom ||
        key.options != options ||
        key.packageGeneration != packageGeneration ||
        key.labelAvoidRects != avoidRects ||
        key.viewport.width != viewport.width ||
        key.viewport.height != viewport.height
    ) return false
    val destination = ReferenceRetainedDestination()
    reference_retained_destination(
        frame_zoom = key.viewport.zoom,
        frame_center_x = key.viewport.center_x,
        frame_center_y = key.viewport.center_y,
        frame_width = ceil(key.frameViewport.width).toInt().coerceAtLeast(1),
        frame_height = ceil(key.frameViewport.height).toInt().coerceAtLeast(1),
        viewport = viewport,
        destination = destination,
    )
    return reference_retained_covers_viewport(destination, viewport)
}
