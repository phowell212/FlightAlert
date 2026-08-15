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



object VisibleAircraftFeedStatusPresenter {
    fun text(state: VisibleAircraftFeedState): String? = when (state) {
        VisibleAircraftFeedState.WAITING_FOR_CAPACITY ->
            "Aircraft feed waiting for background capacity"
        VisibleAircraftFeedState.UNAVAILABLE -> "Aircraft feed unavailable"
        else -> null
    }
}