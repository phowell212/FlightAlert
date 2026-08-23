@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale


enum class ImpactFuel(val display_name: String, val co2_kg_per_gallon: Double) {
    JET("Probable jet fuel", 9.75),
    AVGAS("Probable aviation gasoline", 8.31)
}


enum class ImpactFlightPhase(val display_name: String, val fuel_multiplier: Double) {
    GROUND("ground/taxi", 0.18),
    CLIMB("climb", 1.35),
    CRUISE("cruise", 1.0),
    DESCENT("descent", 0.45),
    LOW_LEVEL("low-level/loiter", 0.85),
    UNKNOWN("time-only", 1.0)
}


enum class ImpactProfileBasis(val display_name: String) {
    TYPE_SPECIFIC("type-specific benchmark"),
    CLASS_BENCHMARK("class benchmark")
}


data class ImpactEstimateConfidence(
    val label: String,
    val basis: String
)


data class ImpactCarbonRange(val low_kg: Double, val mid_kg: Double, val high_kg: Double)


data class ImpactPhaseHours(val phase: ImpactFlightPhase, val hours: Double)


data class ImpactProfile(
    val label: String,
    val examples: String,
    val fuel: ImpactFuel,
    val low_gallons_per_hour: Double,
    val high_gallons_per_hour: Double,
    val cruise_knots: Double,
    val confidence: String,
    val basis: ImpactProfileBasis
) {
    fun midpoint_gallons_per_hour(): Double = (low_gallons_per_hour + high_gallons_per_hour) / 2.0

    fun low_co2_kg_per_hour(): Double = low_gallons_per_hour * fuel.co2_kg_per_gallon

    fun high_co2_kg_per_hour(): Double = high_gallons_per_hour * fuel.co2_kg_per_gallon

    fun mid_co2_kg_per_hour(): Double = midpoint_gallons_per_hour() * fuel.co2_kg_per_gallon

    fun carbon_for_hours(hours: Double): ImpactCarbonRange {
        return ImpactCarbonRange(
            low_kg = low_co2_kg_per_hour() * hours,
            mid_kg = mid_co2_kg_per_hour() * hours,
            high_kg = high_co2_kg_per_hour() * hours
        )
    }

    fun cruise_intensity_label(): String {
        val low = low_co2_kg_per_hour() / cruise_knots
        val high = high_co2_kg_per_hour() / cruise_knots
        return String.format(Locale.US, "%s, ~%.1f-%.1f kg CO2/NM", examples, low, high)
    }

    fun fuel_and_factor_label(): String {
        return String.format(
            Locale.US,
            "%s, %.0f-%.0f gal/hr, %.2f kg CO2/gal EIA",
            fuel.display_name,
            low_gallons_per_hour,
            high_gallons_per_hour,
            fuel.co2_kg_per_gallon
        )
    }
}


data class ImpactTrace(
    val distance_m: Double,
    val hours: Double,
    val average_speed_ms: Double,
    val point_count: Int,
    val source: String
)


data class ImpactFacts(
    val feed_type_code: String?,
    val details_type_code: String?,
    val manufacturer: String?,
    val model: String?,
    val category: Int?,
    val on_ground: Boolean?
)


data class FullFlightImpactEstimate(
    val carbon: ImpactCarbonRange,
    val basis: String
)


data class TraceImpactEstimate(
    val carbon: ImpactCarbonRange,
    val hours: Double,
    val distance_m: Double,
    val phase_hours: List<ImpactPhaseHours>,
    val average_kg_per_hour_mid: Double,
    val full_flight: FullFlightImpactEstimate?,
    val confidence: ImpactEstimateConfidence
)
