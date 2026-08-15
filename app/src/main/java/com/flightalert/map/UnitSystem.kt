@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map


enum class UnitSystem(
    val distance_label: String,
    val altitude_label: String,
    val speed_label: String
) {
    IMPERIAL("mi", "ft", "mph"),
    METRIC("km", "m", "km/h");

    fun distance_meters_to_display(meters: Double): Double {
        return if (this == IMPERIAL) meters / 1609.344 else meters / 1000.0
    }

    fun altitude_meters_to_display(meters: Double): Double {
        return if (this == IMPERIAL) meters * 3.28084 else meters
    }

    fun speed_meters_per_second_to_display(ms: Double): Double {
        return if (this == IMPERIAL) ms * 2.236936 else ms * 3.6
    }
}