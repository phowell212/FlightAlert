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



data class VisibleAircraftRequest(
    val feed_bounds: FeedBounds,
    val globe_display_bounds: List<FeedBounds>,
    val safety_api_bounds: FeedBounds?,
    val own_lat: Double,
    val own_lon: Double,
    val center_lat: Double,
    val center_lon: Double,
    val zoom: Double,
    val feed_mode: AircraftFeedMode,
    val search_query: String?,
    val selected_aircraft_key: String?
)