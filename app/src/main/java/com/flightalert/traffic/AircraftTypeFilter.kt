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



enum class AircraftTypeFilter(val short_label: String) {
    ALL("All"),
    AIRPLANES("Airplanes"),
    ROTORCRAFT("Rotor"),
    GLIDER("Glider"),
    UAV("UAV"),
    SURFACE("Surface"),
    MILITARY("Military");

    fun next(): AircraftTypeFilter = entries[(ordinal + 1) % entries.size]
}