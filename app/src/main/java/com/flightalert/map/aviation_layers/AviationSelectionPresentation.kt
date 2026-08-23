@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

internal data class AviationDetailRow(val label: String, val value: String)

internal enum class AviationProvenanceState {
    CURRENT_COMPLETE,
    CURRENT_PARTIAL,
    SHOWING_LAST_GOOD,
    UNAVAILABLE
}

internal data class AviationProvenancePresentation(
    val state: AviationProvenanceState,
    val banner: String,
    val latest_refresh: String?,
    val source: String,
    val observed_utc: String
)

internal data class AviationSelectionPresentation(
    val title: String,
    val subtitle: String,
    val provenance: AviationProvenancePresentation,
    val rows: List<AviationDetailRow>
)
