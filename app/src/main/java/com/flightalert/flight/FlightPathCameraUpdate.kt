@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.flight

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.MAX_ZOOM
import com.flightalert.MIN_ZOOM
import com.flightalert.PATH_FIT_CONTEXT_MULTIPLIER
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.TrackPoint
import com.flightalert.map.Bounds
import com.flightalert.map.GeoPoint
import com.flightalert.map.MapProjection
import com.flightalert.map.Viewport



data class FlightPathCameraUpdate(val zoom: Double, val center: GeoPoint)