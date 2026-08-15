@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class DictionaryLabelRecordRef(
    val tile: DictionaryTileDrawRef,
    val record: DictionaryLabelRecord,
    val layout_priority: Int,
    val layout_feature_id: ULong,
    val encounter_order: Int,
)