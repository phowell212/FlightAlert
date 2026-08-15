@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal enum class AviationHitKind {
    AIRPORT,
    NAT_SEGMENT,
    SPECIAL_USE_BOUNDARY,
    ATC_BOUNDARY,
    SPECIAL_USE_INTERIOR
}