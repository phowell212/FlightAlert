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

internal data class AviationHitCandidate(
    val key: AviationSelectionKey,
    val kind: AviationHitKind,
    val distance_squared_dp: Double? = null,
    val containment_area: Double? = null
)
