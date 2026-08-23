@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


    internal fun layout(requested_scroll_y: Float, max_scroll_y: Float): AviationInspectorScrollLayout {
        val safe_max = max_scroll_y.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val scroll = requested_scroll_y.takeIf { it.isFinite() }
            ?.coerceIn(0f, safe_max)
            ?: 0f
        return AviationInspectorScrollLayout(
            scroll_y = scroll,
            max_scroll_y = safe_max
        )
    }

    internal fun scroll_from_drag(
        start_scroll_y: Float,
        drag_delta_y: Float,
        max_scroll_y: Float
    ): Float = layout(start_scroll_y - drag_delta_y, max_scroll_y).scroll_y
