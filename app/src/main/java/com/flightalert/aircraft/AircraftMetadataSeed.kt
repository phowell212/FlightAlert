@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.FlightAlertAppSettings
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection



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