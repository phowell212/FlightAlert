@file:Suppress("FunctionName", "PackageName")

package com.flightalert.map

internal fun reference_retained_label_intersects_viewport(
    collision_shape: ReferenceLabelCollisionShape,
    anchor: ReferencePathLabelPoint,
    zoom_scale: Double,
    translate_x: Double,
    translate_y: Double,
    viewport_width: Double,
    viewport_height: Double,
): Boolean {
    val transformed_anchor_x = translate_x + anchor.x * zoom_scale
    val transformed_anchor_y = translate_y + anchor.y * zoom_scale
    val left: Double
    val top: Double
    val right: Double
    val bottom: Double

    when (collision_shape) {
        is ReferenceLabelCollisionShape.Box -> {
            val box = collision_shape.rect
            left = transformed_anchor_x + box.left - anchor.x
            top = transformed_anchor_y + box.top - anchor.y
            right = transformed_anchor_x + box.right - anchor.x
            bottom = transformed_anchor_y + box.bottom - anchor.y
        }

        is ReferenceLabelCollisionShape.Path -> {
            var minimum_x = Double.POSITIVE_INFINITY
            var minimum_y = Double.POSITIVE_INFINITY
            var maximum_x = Double.NEGATIVE_INFINITY
            var maximum_y = Double.NEGATIVE_INFINITY
            for (point in collision_shape.points) {
                val x = translate_x + point.x * zoom_scale
                val y = translate_y + point.y * zoom_scale
                if (x < minimum_x) minimum_x = x
                if (x > maximum_x) maximum_x = x
                if (y < minimum_y) minimum_y = y
                if (y > maximum_y) maximum_y = y
            }
            left = minimum_x - collision_shape.radiusPx
            top = minimum_y - collision_shape.radiusPx
            right = maximum_x + collision_shape.radiusPx
            bottom = maximum_y + collision_shape.radiusPx
        }
    }

    return left <= viewport_width && top <= viewport_height && right >= 0.0 && bottom >= 0.0
}
