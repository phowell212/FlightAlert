@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class DictionaryLineRecordRef(
    val tile: DictionaryTileDrawRef,
    val record: DictionaryLineRecord,
)