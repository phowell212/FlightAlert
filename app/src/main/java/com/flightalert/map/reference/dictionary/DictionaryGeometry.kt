@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class DictionaryGeometry(
    val rings: List<DictionaryRing>,
    val bounds: DictionaryBounds?
)