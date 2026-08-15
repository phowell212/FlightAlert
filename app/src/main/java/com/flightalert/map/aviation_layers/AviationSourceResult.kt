@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.map.aviation_layers


class AviationSourceResult<T>(
    records: List<T>,
    val identity: AviationSourceIdentity,
    val health: AviationLayerHealth,
    val message: String
) {
    val records: List<T> = immutable_list(records)
}