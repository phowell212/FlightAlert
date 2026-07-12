@file:Suppress("FunctionName", "PropertyName")

package com.flightalert.map

import kotlin.math.max

data class ReferenceScreenRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        require(left < right && top < bottom)
    }

    fun padded(padding_px: Double): ReferenceScreenRect {
        require(padding_px.isFinite() && padding_px >= 0.0)
        return ReferenceScreenRect(
            left = left - padding_px,
            top = top - padding_px,
            right = right + padding_px,
            bottom = bottom + padding_px,
        )
    }
}

internal object ReferenceLabelAvoidance {
    fun ownship_rect(
        center_x: Double,
        center_y: Double,
        marker_radius_px: Double,
        pill_half_width_px: Double,
        pill_top_offset_px: Double,
        pill_height_px: Double,
        padding_px: Double,
    ): ReferenceScreenRect {
        require(center_x.isFinite() && center_y.isFinite())
        require(marker_radius_px.isFinite() && marker_radius_px >= 0.0)
        require(pill_half_width_px.isFinite() && pill_half_width_px >= 0.0)
        require(pill_top_offset_px.isFinite())
        require(pill_height_px.isFinite() && pill_height_px >= 0.0)
        require(padding_px.isFinite() && padding_px >= 0.0)

        val half_width = max(marker_radius_px, pill_half_width_px)
        val bottom_offset = max(marker_radius_px, pill_top_offset_px + pill_height_px)
        return ReferenceScreenRect(
            left = center_x - half_width,
            top = center_y - marker_radius_px,
            right = center_x + half_width,
            bottom = center_y + bottom_offset,
        ).padded(padding_px)
    }
}
