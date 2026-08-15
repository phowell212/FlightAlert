@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import kotlin.math.min


internal object AviationInspectorPanelPolicy {
    fun bounds(
        w: Float,
        h: Float,
        density: Float,
        kind: AviationLayerKind
    ): AviationScreenRect {
        val safe_density = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        fun dp(value: Float): Float = value * safe_density
        val panel_width = min(w - dp(32f), dp(620f)).coerceAtLeast(dp(280f))
        val panel_height = if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) {
            min(h - dp(72f), dp(286f)).coerceAtLeast(dp(236f))
        } else {
            min(h - dp(48f), dp(520f)).coerceAtLeast(dp(236f))
        }
        val left = (w - panel_width) / 2f
        val top = if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) {
            (h - panel_height - dp(24f)).coerceAtLeast(dp(24f))
        } else {
            ((h - panel_height) / 2f).coerceAtLeast(dp(24f))
        }
        return AviationScreenRect(left, top, left + panel_width, top + panel_height)
    }

    fun title_max_width(
        panel_width: Float,
        density: Float,
        kind: AviationLayerKind
    ): Float {
        val safe_density = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val reserved_dp = if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) 142f else 146f
        return (panel_width - reserved_dp * safe_density).coerceAtLeast(36f * safe_density)
    }
}