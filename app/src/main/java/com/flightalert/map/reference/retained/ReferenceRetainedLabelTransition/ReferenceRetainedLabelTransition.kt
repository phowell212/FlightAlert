@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.retained

internal data class ReferenceRetainedLabelTransition<T>(
    val leaving: List<T>,
    val continuing_current: BooleanArray,
)