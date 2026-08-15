@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


internal data class AviationProvenancePresentation(
    val state: AviationProvenanceState,
    val banner: String,
    val latest_refresh: String?,
    val source: String,
    val observed_utc: String
)