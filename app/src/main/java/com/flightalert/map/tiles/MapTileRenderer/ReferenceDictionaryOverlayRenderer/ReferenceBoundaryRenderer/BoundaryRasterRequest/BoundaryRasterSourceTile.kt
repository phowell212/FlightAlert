@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.borders

import com.flightalert.map.reference.dictionary.ParsedDictionaryTile


internal data class BoundaryRasterSourceTile(
    val tile: ParsedDictionaryTile,
    val drawX: Int,
    val y: Int,
)