package com.flightalert.ui.map.origin

data class OriginAerodrome(
    val name: String?,
    val icao: String?,
    val distanceM: Double,
    val military: Boolean
) {
    fun label(): String = listOfNotNull(name, icao)
        .distinct()
        .joinToString(" / ")
        .ifEmpty { "Unnamed aerodrome" }
}
