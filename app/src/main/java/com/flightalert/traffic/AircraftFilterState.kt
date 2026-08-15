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



data class AircraftFilterState(
    val search_query: String,
    val aircraft_type: AircraftTypeFilter,
    val altitude: AltitudeFilter,
    val distance: DistanceFilter,
    val flight_status: FlightStatusFilter,
    val report_age: ReportAgeFilter,
    val alert_volume_only: Boolean
)