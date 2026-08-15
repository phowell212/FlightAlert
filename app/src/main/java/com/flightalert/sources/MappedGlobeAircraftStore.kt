@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.sources

import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs
import com.flightalert.AircraftFeedMode
import com.flightalert.FlightAlertAppSettings
import com.flightalert.aircraft.Aircraft



internal data class MappedGlobeAircraftStore(
    val aircraft: List<Aircraft>
)