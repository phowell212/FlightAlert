@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

internal data class DictionaryBounds(
    val min_x: Float,
    val min_y: Float,
    val max_x: Float,
    val max_y: Float
)

internal data class DictionaryRing(
    val points: FloatArray,
    val point_count: Int
)

internal data class DictionaryGeometry(
    val rings: List<DictionaryRing>,
    val bounds: DictionaryBounds?
)
