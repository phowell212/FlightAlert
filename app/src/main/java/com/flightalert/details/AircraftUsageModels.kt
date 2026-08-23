@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details


data class UsageBucket(val label: String, val flights: Int, val hours: Double)


data class UsageStats(
    val buckets: List<UsageBucket>,
    val flight_count: Int,
    val total_hours: Double,
    val week_flight_count: Int,
    val week_hours: Double,
    val window_label: String
)
