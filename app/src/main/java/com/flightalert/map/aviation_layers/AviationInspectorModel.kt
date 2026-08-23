@file:Suppress("PropertyName")

package com.flightalert.map.aviation_layers

internal enum class AviationInspectorContentSection { SELECTION_ROWS, PROVENANCE }

internal data class AviationInspectorScrollLayout(
    val scroll_y: Float,
    val max_scroll_y: Float
)

internal enum class AviationInspectorTapAction { CLOSE, CONSUME }

internal data class AviationLayerInspectorStyle(
    val panel_color: Int,
    val modal_panel_alpha: Int,
    val text_color: Int,
    val muted_color: Int,
    val accent_orange_color: Int,
    val accent_blue_color: Int,
    val accent_pink_color: Int,
    val accent_yellow_color: Int,
    val military_gray_color: Int
)
