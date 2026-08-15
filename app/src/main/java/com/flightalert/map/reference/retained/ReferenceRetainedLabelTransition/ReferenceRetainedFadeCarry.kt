@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.reference.retained


internal data class ReferenceRetainedFadeCarry<T>(
    val established: T,
    val elapsedMs: Long,
)