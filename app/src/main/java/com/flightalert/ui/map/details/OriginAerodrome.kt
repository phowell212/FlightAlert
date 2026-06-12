package com.flightalert.ui.map.details

data class OriginAerodrome(
    val name: String?,
    val icao: String?,
    val distance_m: Double,
    val military: Boolean
) {
    fun label(): String = listOfNotNull(name, icao)
        .distinct()
        .joinToString(" / ")
        .ifEmpty { "Unnamed aerodrome" }
}
