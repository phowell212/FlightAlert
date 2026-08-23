@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.traffic

import com.flightalert.aircraft.AircraftSymbol


internal object TrafficSpritePaintGeometry {
    internal data class SymbolPaintGeometry(
        val maximum_radius_dp: Float,
        val icon_scale_multiplier: Float = 1f,
    )

    const val DOT_RADIUS_DP = 3.6f
    const val DOT_SHADOW_OFFSET_DP = 1.5f
    const val DOT_SHADOW_EXTRA_RADIUS_DP = 1.3f
    const val DOT_STROKE_WIDTH_DP = 1.1f
    const val BATCH_DOT_OUTLINE_EXTRA_DP = 1.25f
    const val SELECTION_RING_BASE_RADIUS_DP = 11f
    const val SELECTION_RING_SHAPE_RADIUS_DP = 13f
    const val SELECTION_RING_STROKE_WIDTH_DP = 2.6f

    // Reuse this geometry for rendering and footprint checks.
    private val general_aviation = SymbolPaintGeometry(20.25f)
    private val airliner = SymbolPaintGeometry(26f)
    private val rotorcraft = SymbolPaintGeometry(34.25f, 0.82f)
    private val glider = SymbolPaintGeometry(28.5f)
    private val uav = SymbolPaintGeometry(34.75f)
    private val surface = SymbolPaintGeometry(23.25f)

    internal fun symbol(symbol: AircraftSymbol): SymbolPaintGeometry = when (symbol) {
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
