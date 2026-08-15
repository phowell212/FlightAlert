@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal data class AviationSelectionPresentation(
    val title: String,
    val subtitle: String,
    val provenance: AviationProvenancePresentation,
    val rows: List<AviationDetailRow>
)