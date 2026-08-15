@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.max
import com.flightalert.FlightAlertSettings
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier



enum class AltitudeFilter(val short_label: String) {
    ANY("Any"),
    BELOW_1000("<1k ft"),
    FROM_1000_TO_5000("1k-5k ft"),
    FROM_5000_TO_18000("5k-18k ft"),
    ABOVE_18000("18k+ ft"),
    UNKNOWN("Unknown");

    fun next(): AltitudeFilter = entries[(ordinal + 1) % entries.size]
}