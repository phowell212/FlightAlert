@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details


internal data class AirplanesLiveMetadata(
    val source_name: String,
    val registration: String?,
    val type_code: String?,
    val description: String?,
    val owner_operator: String?,
    val operator_code: String?,
    val year: String?
) {
    val has_metadata: Boolean
        get() = listOf(
            registration,
            type_code,
            description,
            owner_operator,
            operator_code,
            year
        ).any { !it.isNullOrBlank() }

    val has_core_metadata: Boolean
        get() = listOf(
            registration,
            type_code,
            description,
            owner_operator,
            operator_code,
            year
        ).all { !it.isNullOrBlank() }

    val has_aircraft_identity: Boolean
        get() = listOf(registration, type_code, description).all { !it.isNullOrBlank() }
}
