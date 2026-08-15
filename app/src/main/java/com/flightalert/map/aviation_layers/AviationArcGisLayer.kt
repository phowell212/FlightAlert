@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers

import org.json.JSONObject

data class AviationArcGisLayer<T>(
    val kind: AviationLayerKind,
    val source_provider: String,
    val source_service: String,
    val source_layer: String,
    val metadata_url: String,
    val query_url: String,
    val where_clause: String,
    val out_fields: String,
    val record_id: (T) -> Long,
    val parser: (JSONObject) -> AviationParsedBatch<T>
) {
    init {
        require(source_provider.isNotBlank()) { "ArcGIS source provider is required" }
        require(source_service.isNotBlank()) { "ArcGIS source service is required" }
        require(source_layer.isNotBlank()) { "ArcGIS source layer is required" }
    }
}