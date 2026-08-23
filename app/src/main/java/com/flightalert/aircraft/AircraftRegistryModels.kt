@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import java.util.Locale


enum class RegistryCountrySource {
    REGISTRATION,
    ICAO_ALLOCATION
}


data class RegistryCountry(
    val iso_code: String,
    val name: String,
    val flag_code: String? = iso_code
) {
    val label: String
        get() = flag_code?.let { "${flag_emoji(it)} $name" } ?: name

    private fun flag_emoji(code: String): String {
        val normalized = code.uppercase(Locale.US).take(2)
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) return normalized
        val first = Character.toChars(0x1F1E6 + (normalized[0] - 'A')).concatToString()
        val second = Character.toChars(0x1F1E6 + (normalized[1] - 'A')).concatToString()
        return first + second
    }
}


data class RegistryCountryMatch(val country: RegistryCountry, val source: RegistryCountrySource) {
    val label: String
        get() = country.label
}
