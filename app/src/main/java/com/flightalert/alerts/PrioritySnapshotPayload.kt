@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
)

package com.flightalert.alerts

import kotlin.math.roundToInt
import com.flightalert.aircraft.AlertAircraft

@Suppress("ArrayInDataClass")
data class PrioritySnapshotPayload(
    val signature: String,
    val icao: Array<String>,
    val callsign: Array<String>,
    val registration: Array<String>,
    val distance_feet: DoubleArray,
    val altitude_feet: DoubleArray,
    val vertical_feet: DoubleArray,
    val contact_age_seconds: DoubleArray,
    val estimated: BooleanArray
)