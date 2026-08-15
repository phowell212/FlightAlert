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

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import com.flightalert.AircraftFeedMode
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.aircraft.aircraft_identity_key



enum class FeedPublicationPhase(val precedence: Int) {
    GLOBE_INTERMEDIATE(0),
    FEED_INTERMEDIATE(1),
    FINAL(2)
}