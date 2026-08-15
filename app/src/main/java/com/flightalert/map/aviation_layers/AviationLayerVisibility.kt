@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


data class AviationLayerVisibility(
    val restricted_airspaces_enabled: Boolean,
    val atc_boundaries_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)