@file:Suppress(
    "CanBeVal",
    "ClassName",
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
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.Locale
import com.flightalert.AircraftFeedMode
import com.flightalert.FlightAlertAppSettings



sealed interface VisibleAircraftFeedState {
    object IDLE : VisibleAircraftFeedState
    object WAITING_FOR_LOCATION : VisibleAircraftFeedState
    object WAITING_FOR_VIEWPORT : VisibleAircraftFeedState
    object DEFERRED_FOR_INTERACTION : VisibleAircraftFeedState
    object FETCHING : VisibleAircraftFeedState
    object WAITING_FOR_CAPACITY : VisibleAircraftFeedState
    object UNAVAILABLE : VisibleAircraftFeedState
}