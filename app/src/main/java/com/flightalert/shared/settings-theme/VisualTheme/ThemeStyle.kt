@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert

import android.graphics.Color



data class ThemeStyle(
    val treatment: ThemeTreatment,
    val font_family: String,
    val panel_corner_dp: Float,
    val control_corner_dp: Float,
    val panel_stroke_dp: Float,
    val control_stroke_dp: Float,
    val top_panel_alpha: Int,
    val info_panel_alpha: Int,
    val modal_panel_alpha: Int,
    val control_alpha: Int,
    val divider_alpha: Int,
    val texture_alpha: Int,
    val heading_scale: Float = 1f
)