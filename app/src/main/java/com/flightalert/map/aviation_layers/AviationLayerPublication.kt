@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationLayerPublication(
    val displayed_health: AviationLayerHealth,
    val displayed_source_identity: AviationSourceIdentity?,
    val latest_attempt_health: AviationLayerHealth,
    val latest_attempt_source_identity: AviationSourceIdentity?
)