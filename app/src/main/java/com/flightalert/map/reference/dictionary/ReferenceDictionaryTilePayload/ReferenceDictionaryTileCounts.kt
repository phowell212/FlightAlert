@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary


internal data class ReferenceDictionaryTileCounts(
    val labels: Int,
    val boundaries: Int,
    val transportation: Int
)