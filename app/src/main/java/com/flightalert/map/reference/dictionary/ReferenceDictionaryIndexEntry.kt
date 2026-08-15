@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

internal data class ReferenceDictionaryIndexEntry(
    val offset: Long,
    val compressed_length: Int,
    val raw_length: Int,
    val raw_hash32: Int,
    val flags: Int,
)