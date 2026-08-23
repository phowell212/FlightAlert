@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

internal const val AVIATION_AIRPORT_LABEL_MIN_ZOOM = 8.4


data class AviationLayerVisibility(
    val restricted_airspaces_enabled: Boolean,
    val atc_boundaries_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)
