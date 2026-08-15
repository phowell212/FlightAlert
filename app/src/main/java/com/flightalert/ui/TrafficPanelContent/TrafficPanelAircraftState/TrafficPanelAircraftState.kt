@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.min
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.aircraft.AircraftSymbolClassifier
import com.flightalert.aircraft.AirportDetails
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftRoutePresenter
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.draw_fitted_text
import com.flightalert.traffic.FilterStats



data class TrafficPanelAircraftState(
    val callsign: String,
    val model_label: String,
    val distance_label: String,
    val distance_color: Int,
    val wide_rows: List<TrafficPanelRow>,
    val compact_rows: List<TrafficPanelRow>
) : TrafficPanelContent