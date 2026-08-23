@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

internal data class ReferenceDictionaryTileCounts(
    val labels: Int,
    val boundaries: Int,
    val transportation: Int
)

internal data class ReferenceDictionaryTilePayload(
    val coordinate: ReferenceDictionaryTileCoordinate,
    val counts: ReferenceDictionaryTileCounts,
    val runtime_schema: ReferenceDictionaryRuntimeSchema,
    val raw_bytes: ByteArray,
) {
    val raw_json: String
        get() = raw_bytes.toString(Charsets.UTF_8)
}
