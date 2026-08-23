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


internal data class ReferenceDictionaryStorageManifest(
    val chunk_byte_count: Long,
    val records_byte_count: Long,
    val index_byte_count: Long,
)
