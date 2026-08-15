@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


enum class AviationLayerKind(val display_name: String) {
    ATC_BOUNDARIES("FAA US-chart ARTCC/FIR/OCA boundaries"),
    RESTRICTED_AIRSPACES("FAA special-use airspace"),
    AIRPORTS("FAA US-chart operational airport records"),
    OCEANIC_TRACKS("FAA NAT tracks")
}