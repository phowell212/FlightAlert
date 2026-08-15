@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class RetainedReferenceOptions(
    val labels_enabled: Boolean,
    val borders_enabled: Boolean,
    val place_labels_enabled: Boolean,
    val water_labels_enabled: Boolean,
    val region_labels_enabled: Boolean,
    val public_lands_enabled: Boolean,
    val filter_mask_key: Int,
    val label_text_scale_key: Int
)

