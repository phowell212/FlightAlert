@file:Suppress("FunctionName", "PackageName")

package com.flightalert.map

internal class ReferenceRetainedLabelCoverage(
    private val width: Double,
    private val height: Double,
) {
    private val occupied = LongArray(GRID_SIZE * WORDS_PER_ROW)
    private var first_x = 0
    private var last_x = 0
    private var first_y = 0
    private var last_y = 0

    init {
        require(width.isFinite() && width > 0.0) { "coverage width must be positive" }
        require(height.isFinite() && height > 0.0) { "coverage height must be positive" }
    }

    fun add(bounds: ReferenceScreenRect) {
        if (!set_cell_range(
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
        )) return
        update_range { index, mask ->
            occupied[index] = occupied[index] or mask
            false
        }
    }

    fun intersects(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Boolean {
        if (!set_cell_range(left, top, right, bottom)) return false
        return update_range { index, mask -> occupied[index] and mask != 0L }
    }

    private fun set_cell_range(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Boolean {
        if (right < 0.0 || bottom < 0.0 || left > width || top > height ||
            left > right || top > bottom
        ) return false
        first_x = cell(left.coerceAtLeast(0.0), width)
        last_x = cell(right.coerceAtMost(width), width)
        first_y = cell(top.coerceAtLeast(0.0), height)
        last_y = cell(bottom.coerceAtMost(height), height)
        return true
    }

    private inline fun update_range(
        visit: (Int, Long) -> Boolean,
    ): Boolean {
        var y = first_y
        while (y <= last_y) {
            val first_word = first_x / Long.SIZE_BITS
            val last_word = last_x / Long.SIZE_BITS
            var word = first_word
            while (word <= last_word) {
                val first_bit = if (word == first_word) {
                    first_x and (Long.SIZE_BITS - 1)
                } else {
                    0
                }
                val last_bit = if (word == last_word) {
                    last_x and (Long.SIZE_BITS - 1)
                } else {
                    Long.SIZE_BITS - 1
                }
                val mask = (-1L shl first_bit) and
                    (-1L ushr (Long.SIZE_BITS - 1 - last_bit))
                if (visit(y * WORDS_PER_ROW + word, mask)) return true
                word++
            }
            y++
        }
        return false
    }

    private fun cell(value: Double, span: Double): Int {
        return (value * GRID_SIZE / span).toInt().coerceIn(0, GRID_SIZE - 1)
    }

    private companion object {
        const val GRID_SIZE = 128
        const val WORDS_PER_ROW = GRID_SIZE / Long.SIZE_BITS
    }
}

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
