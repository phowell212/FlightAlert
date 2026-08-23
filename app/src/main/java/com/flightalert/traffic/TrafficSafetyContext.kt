@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic


internal data class TrafficSafetyContext(
    val now_epoch_sec: Double,
    val alerts_enabled: Boolean,
)