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

data class AviationLayerSnapshot(
    val atc_boundaries: List<AviationAirspaceFeature>,
    val restricted_airspaces: List<AviationAirspaceFeature>,
    val airports: List<AviationAirportFeature>,
    val oceanic_tracks: List<AviationOceanicTrack>,
    val statuses: Map<AviationLayerKind, AviationLayerStatus>,
    val fetched_at_ms: Long,
    val publications: Map<AviationLayerKind, AviationLayerPublication> = emptyMap()
)
