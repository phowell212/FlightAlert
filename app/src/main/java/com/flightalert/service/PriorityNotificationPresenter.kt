package com.flightalert.service

import java.util.Locale

object PriorityNotificationPresenter {
    fun extreme_priority_aircraft(priority_aircraft: List<AlertAircraft>): List<AlertAircraft> {
        return priority_aircraft.filter { it.is_extreme_priority }
    }

    fun notification_body(extreme_priority_aircraft: List<AlertAircraft>): String {
        val body = extreme_priority_aircraft
            .sortedWith(compareBy<AlertAircraft> { it.altitude_feet }.thenBy { it.distance_feet })
            .take(MAX_LISTED_AIRCRAFT)
            .joinToString("; ") { aircraft ->
                val registration = aircraft.registration ?: "reg unavailable"
                String.format(Locale.US, "%s %.0f ft", registration, aircraft.altitude_feet)
            }
        val suffix = if (extreme_priority_aircraft.size > MAX_LISTED_AIRCRAFT) {
            " +${extreme_priority_aircraft.size - MAX_LISTED_AIRCRAFT} more"
        } else {
            ""
        }
        return "$body$suffix"
    }

    private const val MAX_LISTED_AIRCRAFT = 4
}
