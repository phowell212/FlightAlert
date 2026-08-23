@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

internal data class DictionaryLabelStyleTemplate(
    val color: Int,
    val text_size_sp: Float,
    val alpha: Int,
    val halo_width_dp: Float,
    val halo_alpha: Int,
    val typeface_style: Int
)

internal data class DictionaryLabelStyle(
    val color: Int,
    val text_size: Float,
    val alpha: Int,
    val halo_color: Int,
    val halo_width_dp: Float,
    val halo_width_milli_em: Int,
    val halo_alpha: Int,
    val font_weight: Int,
    val italic: Boolean,
    val letter_spacing_em: Float,
)
