@file:Suppress(
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
)

package com.flightalert.details

import android.os.SystemClock
import java.util.LinkedHashMap
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails

data class AircraftDetailsWarmCacheEntry(
    val details: AircraftDetails? = null,
    val details_loading: Boolean = false,
    val details_status: String = "Metadata unavailable from configured sources",
    val photo: AircraftPhotoResult.Found? = null,
    val photo_status: String = "Photo unavailable",
    val updated_elapsed_ms: Long
)