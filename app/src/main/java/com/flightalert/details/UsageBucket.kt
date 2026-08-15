@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.flightalert.FlightAlertAppSettings
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.spherical_distance_meters



data class UsageBucket(val label: String, val flights: Int, val hours: Double)