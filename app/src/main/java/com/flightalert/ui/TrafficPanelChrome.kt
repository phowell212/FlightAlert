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



interface TrafficPanelChrome {
    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
}