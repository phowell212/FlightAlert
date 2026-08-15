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



interface VisibleAircraftFeedEnvironmentSource {
    /** Capture time only after location and viewport are available. */
    fun snapshot(): VisibleAircraftFeedEnvironment
    fun feed_ready(): Boolean
    fun build_request(): VisibleAircraftRequest
}