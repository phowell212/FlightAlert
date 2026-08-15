@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.flightalert.AIRCRAFT_APPEAR_DURATION_MS
import com.flightalert.aircraft.AircraftMarkerMorph
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.smooth_step



internal object TrafficSpritePaintGeometry {
    const val DOT_RADIUS_DP = 3.6f
    const val DOT_SHADOW_OFFSET_DP = 1.5f
    const val DOT_SHADOW_EXTRA_RADIUS_DP = 1.3f
    const val DOT_STROKE_WIDTH_DP = 1.1f
    const val BATCH_DOT_OUTLINE_EXTRA_DP = 1.25f
    const val SELECTION_RING_BASE_RADIUS_DP = 11f
    const val SELECTION_RING_SHAPE_RADIUS_DP = 13f
    const val SELECTION_RING_STROKE_WIDTH_DP = 2.6f

    private val general_aviation = TrafficAircraftSymbolPaintGeometry(20.25f)
    private val airliner = TrafficAircraftSymbolPaintGeometry(26f)
    private val rotorcraft = TrafficAircraftSymbolPaintGeometry(34.25f, 0.82f)
    private val glider = TrafficAircraftSymbolPaintGeometry(28.5f)
    private val uav = TrafficAircraftSymbolPaintGeometry(34.75f)
    private val surface = TrafficAircraftSymbolPaintGeometry(23.25f)

    fun symbol(symbol: AircraftSymbol): TrafficAircraftSymbolPaintGeometry = when (symbol) {
        AircraftSymbol.GENERAL_AVIATION -> general_aviation
        AircraftSymbol.AIRLINER -> airliner
        AircraftSymbol.ROTORCRAFT -> rotorcraft
        AircraftSymbol.GLIDER -> glider
        AircraftSymbol.UAV -> uav
        AircraftSymbol.SURFACE -> surface
    }

    fun shadow_offset_x_dp(shape_progress: Float): Float = 2f + shape_progress

    fun shadow_offset_y_dp(shape_progress: Float): Float = 2.5f + 1.5f * shape_progress

    fun shadow_radius_dp(shape_progress: Float): Float = 5f + 11f * shape_progress

    fun selection_ring_radius_dp(shape_progress: Float): Float =
        SELECTION_RING_BASE_RADIUS_DP + SELECTION_RING_SHAPE_RADIUS_DP * shape_progress
}