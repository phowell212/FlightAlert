@file:Suppress(
    "FunctionName",
    "LocalVariableName",
)

package com.flightalert.traffic

import com.flightalert.aircraft.Aircraft

internal const val TRAFFIC_DOT_GROUP_LOW = 0
internal const val TRAFFIC_DOT_GROUP_MID = 1
internal const val TRAFFIC_DOT_GROUP_HIGH = 2
internal const val TRAFFIC_DOT_GROUP_MILITARY = 3
internal const val TRAFFIC_DOT_GROUP_UNKNOWN = 4
internal const val TRAFFIC_DOT_GROUP_COUNT = 5

internal fun traffic_dot_group(aircraft: Aircraft): Int {
    if (aircraft.is_military) return TRAFFIC_DOT_GROUP_MILITARY
    val altitude_feet = aircraft.altitude_m?.times(FEET_PER_METER)
    return when {
        altitude_feet == null -> TRAFFIC_DOT_GROUP_UNKNOWN
        altitude_feet < 5000.0 -> TRAFFIC_DOT_GROUP_LOW
        altitude_feet < 25000.0 -> TRAFFIC_DOT_GROUP_MID
        else -> TRAFFIC_DOT_GROUP_HIGH
    }
}

private const val FEET_PER_METER = 3.28084
