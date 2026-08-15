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

internal enum class ReferenceDictionaryRuntimeSchema {
    LEGACY_JSON,
    HOT_JSON_V2,
    BINARY_V3,
    RENDER_TILE_V1,
}