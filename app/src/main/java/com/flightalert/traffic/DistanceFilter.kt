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



enum class DistanceFilter(val short_label: String) {
    ANY("Any"),
    WITHIN_5("<5 mi"),
    WITHIN_10("<10 mi"),
    WITHIN_25("<25 mi"),
    BEYOND_25(">25 mi");

    fun next(): DistanceFilter = entries[(ordinal + 1) % entries.size]
}