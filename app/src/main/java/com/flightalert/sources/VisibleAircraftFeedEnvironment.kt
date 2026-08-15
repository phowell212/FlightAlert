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

sealed interface VisibleAircraftFeedEnvironment {
    object NO_LOCATION : VisibleAircraftFeedEnvironment
    object NO_VIEWPORT : VisibleAircraftFeedEnvironment

    data class READY(
        val now_elapsed_ms: Long,
        val feed_ready: Boolean,
        val interaction_deferred: Boolean,
        val interaction_delay_ms: Long,
    ) : VisibleAircraftFeedEnvironment
}