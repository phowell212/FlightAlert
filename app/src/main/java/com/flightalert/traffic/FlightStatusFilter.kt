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



enum class FlightStatusFilter(val short_label: String) {
    ANY("Any"),
    AIRBORNE("Airborne"),
    ON_GROUND("Ground"),
    UNKNOWN("Unknown");

    fun next(): FlightStatusFilter = entries[(ordinal + 1) % entries.size]
}