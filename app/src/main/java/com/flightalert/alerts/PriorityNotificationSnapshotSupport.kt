@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.alerts

import kotlin.math.roundToInt
import com.flightalert.aircraft.AlertAircraft



fun prepare_priority_snapshot(
    priority_aircraft: List<AlertAircraft>
): PrioritySnapshotPayload {
    val size = priority_aircraft.size
    val icao = Array(size) { "" }
    val callsign = Array(size) { "" }
    val registration = Array(size) { "" }
    val distance_feet = DoubleArray(size)
    val altitude_feet = DoubleArray(size)
    val vertical_feet = DoubleArray(size)
    val contact_age_seconds = DoubleArray(size)
    val estimated = BooleanArray(size)
    val signature = StringBuilder(size * 24)
    priority_aircraft.forEachIndexed { index, aircraft ->
        icao[index] = aircraft.icao24
        callsign[index] = aircraft.callsign
        registration[index] = aircraft.registration.orEmpty()
        distance_feet[index] = aircraft.distance_feet
        altitude_feet[index] = aircraft.altitude_feet
        vertical_feet[index] = aircraft.vertical_separation_feet ?: Double.NaN
        contact_age_seconds[index] = aircraft.contact_age_seconds
        estimated[index] = aircraft.is_estimated_position
        if (index > 0) signature.append('|')
        signature.append(aircraft.icao24)
            .append(':')
            .append(aircraft.registration.orEmpty())
            .append(':')
            .append(aircraft.altitude_feet.roundToInt())
            .append(':')
            .append(aircraft.is_estimated_position)
    }
    return PrioritySnapshotPayload(
        signature = signature.toString(),
        icao = icao,
        callsign = callsign,
        registration = registration,
        distance_feet = distance_feet,
        altitude_feet = altitude_feet,
        vertical_feet = vertical_feet,
        contact_age_seconds = contact_age_seconds,
        estimated = estimated
    )
}
