@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal enum class AviationProvenanceState {
    CURRENT_COMPLETE,
    CURRENT_PARTIAL,
    SHOWING_LAST_GOOD,
    UNAVAILABLE
}