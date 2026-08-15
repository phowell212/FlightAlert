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



enum class ReportAgeFilter(val short_label: String) {
    ANY("Any"),
    FRESH_30("Fresh <=30s"),
    STALE_60("Stale >=60s"),
    UNKNOWN("Unknown");

    fun next(): ReportAgeFilter = entries[(ordinal + 1) % entries.size]
}