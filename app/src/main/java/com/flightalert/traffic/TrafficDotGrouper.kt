@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.traffic

import com.flightalert.aircraft.Aircraft

internal object TrafficDotGrouper {
    const val GROUP_LOW = 0
    const val GROUP_MID = 1
    const val GROUP_HIGH = 2
    const val GROUP_MILITARY = 3
    const val GROUP_UNKNOWN = 4
    const val GROUP_COUNT = 5

    fun dot_group(aircraft: Aircraft): Int {
        if (aircraft.is_military) return GROUP_MILITARY
        val altitude_feet = aircraft.altitude_m?.times(FEET_PER_METER)
        return when {
            altitude_feet == null -> GROUP_UNKNOWN
            altitude_feet < 5000.0 -> GROUP_LOW
            altitude_feet < 25000.0 -> GROUP_MID
            else -> GROUP_HIGH
        }
    }

    private const val FEET_PER_METER = 3.28084
}

