@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.details

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import com.flightalert.ThemeColors
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.TrackPoint
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.map.UnitSystem



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