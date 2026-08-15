@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class DictionaryTileDrawRef(
    val tile: ParsedDictionaryTile,
    val draw_x: Int,
    val core_visible: Boolean,
)