@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class ParsedDictionaryTile(
    val coordinate: ReferenceDictionaryTileCoordinate,
    val boundaries: List<DictionaryLineRecord>,
    val labels: List<DictionaryLabelRecord>
)