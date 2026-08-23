@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


data class AircraftMetadataSeed(
    val source_name: String,
    val registration: String? = null,
    val manufacturer: String? = null,
    val type: String? = null,
    val type_code: String? = null,
    val owner: String? = null,
    val manufactured_year: String? = null,
    val operator_code: String? = null
) {
    val has_details: Boolean
        get() = !registration.isNullOrBlank() ||
                !manufacturer.isNullOrBlank() ||
                !type.isNullOrBlank() ||
                !type_code.isNullOrBlank() ||
                !owner.isNullOrBlank() ||
                !manufactured_year.isNullOrBlank() ||
                !operator_code.isNullOrBlank()
}