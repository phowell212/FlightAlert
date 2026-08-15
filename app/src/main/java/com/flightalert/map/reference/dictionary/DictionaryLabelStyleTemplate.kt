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