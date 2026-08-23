@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft


data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val epoch_sec: Long,
    val altitude_m: Double?,
    val track_deg: Double?,
    val on_ground: Boolean?
)
