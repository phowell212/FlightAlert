@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

internal const val AVIATION_AIRPORT_LABEL_MIN_ZOOM = 8.4

internal fun airport_label_zoom_opacity(zoom: Double): Float {
    if (!zoom.isFinite()) return 0f
    val progress = ((zoom - AVIATION_AIRPORT_LABEL_MIN_ZOOM) / 0.35).coerceIn(0.0, 1.0)
    return (progress * progress * (3.0 - 2.0 * progress)).toFloat()
}


data class AviationLayerVisibility(
    val restricted_airspaces_enabled: Boolean,
    val atc_boundaries_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)
