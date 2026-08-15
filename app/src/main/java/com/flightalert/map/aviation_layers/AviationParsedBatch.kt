@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


class AviationParsedBatch<T>(
    records: List<T>,
    returned_object_ids: Set<Long>,
    invalid_object_ids: Set<Long>
) {
    val records: List<T> = immutable_list(records)
    val returned_object_ids: Set<Long> = immutable_set(returned_object_ids)
    val invalid_object_ids: Set<Long> = immutable_set(invalid_object_ids)

}