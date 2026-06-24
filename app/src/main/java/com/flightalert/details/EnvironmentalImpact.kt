@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.details
import com.flightalert.aircraft.Aircraft
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.flight.TrackPoint
import com.flightalert.map.UnitSystem
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object AircraftImpactEstimator {
    val source_labels = listOf("EIA CO2", "EPA Hub", "ICAO", "FAA AEDT", "EPA Lead")
    val source_urls = listOf(
        "https://www.eia.gov/environment/emissions/co2_vol_mass.php",
        "https://www.epa.gov/climateleadership/ghg-emission-factors-hub",
        "https://www.icao.int/environmental-protection/environmental-tools/icec",
        "https://aedt.faa.gov/",
        "https://www.epa.gov/regulations-emissions-vehicles-and-engines/regulations-lead-emissions-aircraft"
    )

    fun facts_for(feed_type_code: String?, category: Int?, on_ground: Boolean?, details: AircraftDetails?): ImpactFacts {
        return ImpactFacts(
            feed_type_code = feed_type_code,
            details_type_code = details?.type_code,
            manufacturer = details?.manufacturer,
            model = details?.type,
            category = category,
            on_ground = on_ground
        )
    }

    fun type_code(facts: ImpactFacts): String? {
        return listOfNotNull(facts.details_type_code, facts.feed_type_code)
            .map { it.trim().uppercase(Locale.US) }
            .firstOrNull { it.isNotBlank() }
    }

    fun profile_for(facts: ImpactFacts): ImpactProfile? {
        val code = type_code(facts).orEmpty()
        val text = search_text(facts)
        if (code.isBlank() && text.isBlank()) return null
        if (facts.category == 14 || text.contains("UAV") || text.contains("DRONE")) return null
        if (facts.category == 9 || code.startsWith("GL") || text.contains("GLIDER")) return null
        if (facts.on_ground == true && facts.category != null && facts.category in listOf(16, 17, 18, 19, 20)) return null
        type_specific_profile(code)?.let { return it }

        return when {
            is_small_piston_aircraft(code, text) -> PISTON_SINGLE
            is_piston_rotorcraft(code, text) -> PISTON_ROTOR
            is_turbine_rotorcraft(code, text) -> TURBINE_ROTOR
            is_military_transport_aircraft(code, text) -> HEAVY
            is_tactical_jet_aircraft(code, text) -> TACTICAL_JET
            is_heavy_aircraft(code, text) -> HEAVY
            is_large_airliner_aircraft(code, text) -> NARROW_BODY
            is_regional_aircraft(code, text) -> REGIONAL
            is_turboprop_aircraft(code, text) -> TURBOPROP
            is_light_jet_aircraft(code, text) -> LIGHT_JET
            facts.category in listOf(4, 5, 6) -> NARROW_BODY
            facts.category == 8 -> null
            is_airliner_type_code(code) -> NARROW_BODY
            else -> null
        }
    }

    fun score(profile: ImpactProfile): Int {
        return score_for_kg_per_hour(profile.mid_co2_kg_per_hour())
    }

    fun score_for_kg_per_hour(kg_per_hour: Double): Int {
        val normalized = ((log10(kg_per_hour.coerceAtLeast(0.01)) - log10(SCORE_MIN_KG_CO2_PER_HOUR)) /
            (log10(SCORE_MAX_KG_CO2_PER_HOUR) - log10(SCORE_MIN_KG_CO2_PER_HOUR)))
            .coerceIn(0.0, 1.0)
        return (normalized * 100.0).roundToInt()
    }

    fun comparison(profile: ImpactProfile): String {
        val current = profile.mid_co2_kg_per_hour()
        val piston = PISTON_SINGLE.mid_co2_kg_per_hour()
        val narrow = NARROW_BODY.mid_co2_kg_per_hour()
        return "${format_multiplier(current, piston)} piston single; ${format_multiplier(current, narrow)} A320/B737 class"
    }

    private fun search_text(facts: ImpactFacts): String {
        return listOfNotNull(facts.feed_type_code, facts.details_type_code, facts.manufacturer, facts.model)
            .joinToString(" ")
            .uppercase(Locale.US)
    }

    private fun type_specific_profile(code: String): ImpactProfile? {
        val compact = compact_impact_text(code)
        return TYPE_SPECIFIC_PROFILES[compact]
    }

    private fun is_small_piston_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, SMALL_PISTON_PREFIXES, SMALL_PISTON_TEXT)
    }

    private fun is_piston_rotorcraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, PISTON_ROTOR_PREFIXES, PISTON_ROTOR_TEXT)
    }

    private fun is_turbine_rotorcraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, TURBINE_ROTOR_PREFIXES, TURBINE_ROTOR_TEXT)
    }

    private fun is_turboprop_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, TURBOPROP_PREFIXES, TURBOPROP_TEXT)
    }

    private fun is_regional_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, REGIONAL_PREFIXES, REGIONAL_TEXT)
    }

    private fun is_light_jet_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, LIGHT_JET_PREFIXES, LIGHT_JET_TEXT)
    }

    private fun is_large_airliner_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, NARROW_BODY_PREFIXES, NARROW_BODY_TEXT)
    }

    private fun is_heavy_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, HEAVY_PREFIXES, HEAVY_TEXT)
    }

    private fun is_military_transport_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, MILITARY_TRANSPORT_PREFIXES, MILITARY_TRANSPORT_TEXT)
    }

    private fun is_tactical_jet_aircraft(code: String, text: String): Boolean {
        return matches_impact_terms(code, text, TACTICAL_JET_PREFIXES, TACTICAL_JET_TEXT)
    }

    private fun is_airliner_type_code(code: String): Boolean {
        return NARROW_BODY_PREFIXES.any { code.startsWith(it) } ||
            HEAVY_PREFIXES.any { code.startsWith(it) } ||
            REGIONAL_PREFIXES.any { code.startsWith(it) }
    }

    private fun matches_impact_terms(
        code: String,
        text: String,
        prefixes: List<String>,
        phrases: List<String>
    ): Boolean {
        val compact_code = compact_impact_text(code)
        val compact_text = compact_impact_text(text)
        return prefixes.any { prefix ->
            val compact_prefix = compact_impact_text(prefix)
            compact_prefix.isNotBlank() &&
                (compact_code.startsWith(compact_prefix) || compact_text.contains(compact_prefix))
        } || phrases.any { phrase ->
            text.contains(phrase) || compact_text.contains(compact_impact_text(phrase))
        }
    }

    private fun compact_impact_text(value: String): String {
        return value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "")
    }

    private fun format_multiplier(value: Double, baseline: Double): String {
        if (baseline <= 0.0) return "Unavailable"
        val ratio = value / baseline
        return when {
            ratio >= 10.0 -> String.format(Locale.US, "~%.0fx", ratio)
            ratio >= 1.0 -> String.format(Locale.US, "~%.1fx", ratio)
            else -> String.format(Locale.US, "~%.2fx", ratio)
        }
    }

    private const val SCORE_MIN_KG_CO2_PER_HOUR = 20.0
    private const val SCORE_MAX_KG_CO2_PER_HOUR = 20000.0

    private val PISTON_SINGLE = ImpactProfile(
        label = "Piston general aviation",
        examples = "C172/SR22/PA-28/PA-44",
        fuel = ImpactFuel.AVGAS,
        low_gallons_per_hour = 8.0,
        high_gallons_per_hour = 18.0,
        cruise_knots = 125.0,
        confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val PISTON_ROTOR = ImpactProfile(
        label = "Piston rotorcraft",
        examples = "R22/R44",
        fuel = ImpactFuel.AVGAS,
        low_gallons_per_hour = 10.0,
        high_gallons_per_hour = 22.0,
        cruise_knots = 95.0,
        confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val TURBINE_ROTOR = ImpactProfile(
        label = "Turbine rotorcraft",
        examples = "H60/AW139/S-76",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 60.0,
        high_gallons_per_hour = 220.0,
        cruise_knots = 130.0,
        confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val TURBOPROP = ImpactProfile(
        label = "Turboprop or commuter aircraft",
        examples = "PC-12/C208/B350",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 40.0,
        high_gallons_per_hour = 160.0,
        cruise_knots = 250.0,
        confidence = "Medium: broad turbine-prop benchmark, not exact aircraft fuel flow.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val LIGHT_JET = ImpactProfile(
        label = "Business or light jet",
        examples = "Citation/Phenom/Learjet",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 90.0,
        high_gallons_per_hour = 350.0,
        cruise_knots = 410.0,
        confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val REGIONAL = ImpactProfile(
        label = "Regional airline aircraft",
        examples = "CRJ/E175/Dash 8",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 220.0,
        high_gallons_per_hour = 650.0,
        cruise_knots = 430.0,
        confidence = "Medium: class benchmark only; load factor and route phase are unavailable.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val NARROW_BODY = ImpactProfile(
        label = "Large narrow-body airliner",
        examples = "A320/B737/A321",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 550.0,
        high_gallons_per_hour = 950.0,
        cruise_knots = 455.0,
        confidence = "Medium: class benchmark only; load factor and route phase are unavailable.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val HEAVY = ImpactProfile(
        label = "Heavy transport or wide-body aircraft",
        examples = "B777/A350/C-17",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 1200.0,
        high_gallons_per_hour = 3200.0,
        cruise_knots = 485.0,
        confidence = "Medium-low: heavy-aircraft fuel flow depends heavily on model, weight, and mission.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )
    private val TACTICAL_JET = ImpactProfile(
        label = "High-performance tactical jet",
        examples = "F-16/F-18/F-35/T-38",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 700.0,
        high_gallons_per_hour = 2200.0,
        cruise_knots = 480.0,
        confidence = "Low: military mission profile and power setting are unavailable.",
        basis = ImpactProfileBasis.CLASS_BENCHMARK
    )

    private val TYPE_SPECIFIC_PROFILES = listOf(
        "C150" to typed_profile("C150", "Cessna 150", ImpactFuel.AVGAS, 5.0, 8.0, 95.0),
        "C152" to typed_profile("C152", "Cessna 152", ImpactFuel.AVGAS, 5.0, 8.0, 100.0),
        "C172" to typed_profile("C172", "Cessna 172", ImpactFuel.AVGAS, 7.0, 11.0, 120.0),
        "C182" to typed_profile("C182", "Cessna 182", ImpactFuel.AVGAS, 11.0, 16.0, 135.0),
        "PA28" to typed_profile("PA28", "Piper PA-28", ImpactFuel.AVGAS, 8.0, 13.0, 115.0),
        "PA32" to typed_profile("PA32", "Piper PA-32", ImpactFuel.AVGAS, 13.0, 18.0, 140.0),
        "PA34" to typed_profile("PA34", "Piper PA-34", ImpactFuel.AVGAS, 15.0, 22.0, 160.0),
        "PA44" to typed_profile("PA44", "Piper PA-44", ImpactFuel.AVGAS, 14.0, 22.0, 155.0),
        "SR20" to typed_profile("SR20", "Cirrus SR20", ImpactFuel.AVGAS, 10.0, 15.0, 150.0),
        "SR22" to typed_profile("SR22", "Cirrus SR22", ImpactFuel.AVGAS, 14.0, 21.0, 175.0),
        "R44" to typed_profile("R44", "Robinson R44", ImpactFuel.AVGAS, 12.0, 18.0, 95.0),
        "C208" to typed_profile("C208", "Cessna 208", ImpactFuel.JET, 45.0, 75.0, 175.0),
        "PC12" to typed_profile("PC12", "Pilatus PC-12", ImpactFuel.JET, 55.0, 85.0, 260.0),
        "B350" to typed_profile("B350", "Beechcraft King Air 350", ImpactFuel.JET, 90.0, 135.0, 300.0),
        "SF50" to typed_profile("SF50", "Cirrus Vision Jet", ImpactFuel.JET, 55.0, 85.0, 300.0),
        "E75L" to typed_profile("E75L", "Embraer 175", ImpactFuel.JET, 400.0, 700.0, 430.0),
        "E75S" to typed_profile("E75S", "Embraer 175", ImpactFuel.JET, 400.0, 700.0, 430.0),
        "CRJ9" to typed_profile("CRJ9", "CRJ900", ImpactFuel.JET, 420.0, 720.0, 430.0),
        "A319" to typed_profile("A319", "Airbus A319", ImpactFuel.JET, 520.0, 820.0, 455.0),
        "A320" to typed_profile("A320", "Airbus A320", ImpactFuel.JET, 600.0, 950.0, 455.0),
        "A321" to typed_profile("A321", "Airbus A321", ImpactFuel.JET, 700.0, 1100.0, 455.0),
        "B738" to typed_profile("B738", "Boeing 737-800", ImpactFuel.JET, 620.0, 980.0, 455.0),
        "B38M" to typed_profile("B38M", "Boeing 737 MAX 8", ImpactFuel.JET, 560.0, 900.0, 455.0),
        "B77W" to typed_profile("B77W", "Boeing 777-300ER", ImpactFuel.JET, 2100.0, 3200.0, 490.0),
        "A333" to typed_profile("A333", "Airbus A330-300", ImpactFuel.JET, 1700.0, 2700.0, 480.0)
    ).associate { (code, profile) -> compact_impact_text(code) to profile }

    private fun typed_profile(
        code: String,
        model: String,
        fuel: ImpactFuel,
        low_gph: Double,
        high_gph: Double,
        cruise_knots: Double
    ): ImpactProfile {
        return ImpactProfile(
            label = "$model benchmark",
            examples = code,
            fuel = fuel,
            low_gallons_per_hour = low_gph,
            high_gallons_per_hour = high_gph,
            cruise_knots = cruise_knots,
            confidence = "Medium: ICAO type code matched a maintained fuel benchmark; exact engine, payload, and operator fuel flow are unavailable.",
            basis = ImpactProfileBasis.TYPE_SPECIFIC
        )
    }

    private val SMALL_PISTON_PREFIXES = listOf(
        "AA5", "BE23", "BE33", "BE35", "BE36", "BE55", "BE56", "BE58", "BE76",
        "C150", "C152", "C162", "C170", "C172", "C175", "C177", "C180", "C182",
        "C185", "C206", "C207", "C210", "C310", "C320", "C337", "C340", "C402",
        "C414", "C421", "DA40", "DA42", "GA7", "M20P", "M20T", "P28A", "PA23",
        "PA24", "PA28", "PA30", "PA31", "PA32", "PA34", "PA38", "PA44", "PA46",
        "SR20", "SR22"
    )
    private val SMALL_PISTON_TEXT = listOf(
        "CESSNA 150", "CESSNA 152", "CESSNA 172", "CESSNA 182", "CESSNA 206",
        "CESSNA 310", "CESSNA 340", "CESSNA 402", "CESSNA 414", "CESSNA 421",
        "PIPER PA-28", "PIPER PA28", "PIPER PA-32", "PIPER PA32", "PIPER PA-34",
        "PIPER PA34", "PIPER PA-44", "PIPER PA44", "PA-44-180", "PIPER SEMINOLE",
        "PIPER CHEROKEE", "PIPER ARCHER", "PIPER AZTEC", "PIPER NAVAJO",
        "CIRRUS SR20", "CIRRUS SR22", "DIAMOND DA40", "DIAMOND DA42",
        "BEECH BONANZA", "BEECH BARON", "BEECHCRAFT BARON", "GRUMMAN TIGER",
        "MOONEY M20"
    )
    private val PISTON_ROTOR_PREFIXES = listOf("R22", "R44")
    private val PISTON_ROTOR_TEXT = listOf("ROBINSON R22", "ROBINSON R44")
    private val TURBINE_ROTOR_PREFIXES = listOf(
        "H60", "AS3", "AS5", "EC30", "EC35", "EC45", "B06", "B407", "B429", "S76", "S92", "A109", "A139", "AW13"
    )
    private val TURBINE_ROTOR_TEXT = listOf(
        "BLACK HAWK", "SIKORSKY S-76", "SIKORSKY S-92", "AW139", "BELL 407", "BELL 429",
        "EUROCOPTER", "AIRBUS HELICOPTERS"
    )
    private val TURBOPROP_PREFIXES = listOf("C208", "PC12", "TBM", "B350", "BE20", "BE30", "P46T", "DHC6")
    private val TURBOPROP_TEXT = listOf("CARAVAN", "PC-12", "KING AIR", "TBM", "TWIN OTTER")
    private val LIGHT_JET_PREFIXES = listOf("C25", "C510", "C52", "C55", "C56", "C68", "E50", "E55", "E545", "GLF", "H25", "LJ", "PRM", "SF50", "CL30")
    private val LIGHT_JET_TEXT = listOf("CITATION", "PHENOM", "LEARJET", "GULFSTREAM", "PILATUS PC-24", "VISION JET", "CHALLENGER 300")
    private val REGIONAL_PREFIXES = listOf("AT4", "AT7", "CRJ", "DH8", "E17", "E19", "E70", "E75", "F70", "F90", "SB20")
    private val REGIONAL_TEXT = listOf("CRJ", "EMBRAER 170", "EMBRAER 175", "DASH 8", "ATR 42", "ATR 72", "SAAB 2000")
    private val NARROW_BODY_PREFIXES = listOf("A19", "A20", "A21", "A30", "A31", "A32", "B37", "B38", "B39", "B70", "B71", "B72", "B73", "B75", "B76", "BCS", "MD8", "MD9")
    private val NARROW_BODY_TEXT = listOf("AIRBUS A320", "AIRBUS A321", "BOEING 737", "BOEING 757", "BOEING 767", "A220", "737 MAX")
    private val HEAVY_PREFIXES = listOf("A33", "A34", "A35", "A38", "B74", "B77", "B78", "DC10", "MD11", "A124", "IL76")
    private val HEAVY_TEXT = listOf("A330", "A340", "A350", "A380", "747", "777", "787", "MD-11", "C-17", "C-5", "IL-76", "AN-124")
    private val MILITARY_TRANSPORT_PREFIXES = listOf("C17", "C5", "C130", "A400", "K35", "KC10", "R135")
    private val MILITARY_TRANSPORT_TEXT = listOf("GLOBEMASTER", "GALAXY", "HERCULES", "ATLAS", "STRATOTANKER", "EXTENDER")
    private val TACTICAL_JET_PREFIXES = listOf("A10", "F15", "F16", "F18", "F22", "F35", "T38", "EUFI", "TOR")
    private val TACTICAL_JET_TEXT = listOf("F-15", "F-16", "F-18", "F-22", "F-35", "T-38", "TYPHOON", "TORNADO", "WARTHOG")
}


data class ImpactFacts(
    val feed_type_code: String?,
    val details_type_code: String?,
    val manufacturer: String?,
    val model: String?,
    val category: Int?,
    val on_ground: Boolean?
)


data class ImpactCarbonRange(val low_kg: Double, val mid_kg: Double, val high_kg: Double)


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


enum class ImpactProfileBasis(val display_name: String) {
    TYPE_SPECIFIC("type-specific benchmark"),
    CLASS_BENCHMARK("class benchmark")
}


enum class ImpactFuel(val display_name: String, val co2_kg_per_gallon: Double) {
    JET("Probable jet fuel", 9.75),
    AVGAS("Probable aviation gasoline", 8.31)
}



data class ImpactTrace(
    val distance_m: Double,
    val hours: Double,
    val average_speed_ms: Double,
    val point_count: Int,
    val source: String
)


object AircraftImpactPresenter {
    fun rows(
        aircraft: Aircraft,
        details: AircraftDetails?,
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        usage_trace: FlightTrace?,
        details_loading: Boolean,
        trace_loading: Boolean,
        units: UnitSystem
    ): List<Pair<String, String>> {
        val loading = details_loading || trace_loading
        return listOf(
            "Data basis" to data_basis(aircraft, details, details_loading),
            "Aircraft class" to (profile?.label ?: loading_or_unavailable(loading)),
            "Current trace" to current_trace_impact(trace, trace_loading, units),
            "Observed CO2 estimate" to current_trace_carbon(profile, trace, trace_estimate, loading),
            "Trace phases" to phase_summary(trace_estimate, trace_loading),
            "Full-flight estimate" to full_flight_summary(trace_estimate, trace_loading),
            "Observed intensity" to observed_carbon_intensity(profile, trace, trace_estimate, loading, units),
            "Profile basis" to (profile?.profile_basis_label() ?: loading_or_unavailable(loading)),
            "Class CO2 rate" to (profile?.let { kg_range(it.low_co2_kg_per_hour(), it.high_co2_kg_per_hour()) } ?: loading_or_unavailable(loading)),
            "Class cruise context" to (profile?.cruise_intensity_label() ?: loading_or_unavailable(loading)),
            "Fuel and factor" to (profile?.fuel_and_factor_label() ?: loading_or_unavailable(loading)),
            "Live state" to live_state(aircraft, units),
            "Trace history" to trace_history_carbon(usage_trace, profile, trace_loading, loading),
            "Comparison" to (profile?.let { AircraftImpactEstimator.comparison(it) } ?: loading_or_unavailable(loading)),
            "Score meaning" to "0-100 log scale for kg CO2/hr intensity; observed trace phases are used when available, otherwise profile rate.",
            "Lead / non-carbon" to lead_note(profile, loading),
            "Passenger/load" to "Unavailable: passenger count and load factor are not inferred.",
            "Limits" to "No exact engine fuel flow, phase power, payload, SAF blend, contrails, NOx, or noise from the live feed.",
            "Confidence" to confidence(profile, trace, trace_estimate, loading, details)
        )
    }

    fun status(profile: ImpactProfile?, trace: ImpactTrace?, details_loading: Boolean, trace_loading: Boolean): String {
        return when {
            profile != null && trace != null -> "Trace-derived time/distance with benchmark fuel profile; not measured fuel flow"
            profile != null && trace_loading -> "Loading real trace before estimating current-flight carbon"
            profile != null -> "${profile.basis.display_name.replaceFirstChar { it.uppercase() }} carbon-rate estimate; current trace unavailable"
            details_loading -> "Loading aircraft metadata"
            else -> "Unavailable: aircraft type or fuel class is not supported"
        }
    }

    fun profile_for(aircraft: Aircraft, details: AircraftDetails?): ImpactProfile? {
        return AircraftImpactEstimator.profile_for(facts_for(aircraft, details))
    }

    fun facts_for(aircraft: Aircraft, details: AircraftDetails?) =
        AircraftImpactEstimator.facts_for(
            feed_type_code = aircraft.type_code,
            category = aircraft.category,
            on_ground = aircraft.on_ground,
            details = details
        )

    fun kg_range(low: Double, high: Double): String {
        return "~${kg_compact(low)}-${kg_compact(high)} kg CO2/hr"
    }

    fun carbon_range(range: ImpactCarbonRange): String {
        return "~${kg_compact(range.low_kg)}-${kg_compact(range.high_kg)} kg CO2"
    }

    private fun data_basis(aircraft: Aircraft, details: AircraftDetails?, loading: Boolean): String {
        val code = AircraftImpactEstimator.type_code(facts_for(aircraft, details))
        val model = listOfNotNull(details?.manufacturer, details?.type).joinToString(" ").ifBlank { null }
        val category = aircraft.category?.let { "ADS-B category $it" }
        val source = when {
            details?.registry_source != null -> details.registry_source
            aircraft.type_code != null || aircraft.category != null -> "live aircraft feed"
            loading -> "Loading"
            else -> "Unavailable"
        }
        return listOfNotNull(code?.let { "type $it" }, model, category, source).joinToString("; ").ifEmpty {
            loading_or_unavailable(loading)
        }
    }

    private fun live_state(aircraft: Aircraft, units: UnitSystem): String {
        val state = when (aircraft.on_ground) {
            true -> "Ground"
            false -> "Airborne"
            null -> "State unavailable"
        }
        return "$state, ${altitude(aircraft.altitude_m, units)}, ${speed(aircraft.velocity_ms, units)}, report ${age(aircraft)}"
    }

    private fun current_trace_impact(trace: ImpactTrace?, loading: Boolean, units: UnitSystem): String {
        if (loading) return "Loading"
        trace ?: return "Unavailable"
        return "${distance(trace.distance_m, units)}, ${AircraftUsageAnalyzer.format_hours(trace.hours)}, ${speed(trace.average_speed_ms, units)} avg from ${trace.source}"
    }

    private fun current_trace_carbon(
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        loading: Boolean
    ): String {
        val estimate = trace_estimate as? TraceImpactEstimate
        if (estimate != null) {
            return "${carbon_range(estimate.carbon)} over ${AircraftUsageAnalyzer.format_hours(estimate.hours)}"
        }
        if (profile == null || trace == null) return loading_or_unavailable(loading)
        return "${carbon_range(profile.carbon_for_hours(trace.hours))} over ${AircraftUsageAnalyzer.format_hours(trace.hours)}"
    }

    private fun phase_summary(trace_estimate: Any?, loading: Boolean): String {
        if (loading) return "Loading"
        val estimate = trace_estimate as? TraceImpactEstimate ?: return "Unavailable"
        return estimate.phase_hours
            .take(3)
            .joinToString("; ") { "${it.phase.display_name} ${AircraftUsageAnalyzer.format_hours(it.hours)}" }
            .ifEmpty { "Unavailable" }
    }

    private fun full_flight_summary(trace_estimate: Any?, loading: Boolean): String {
        if (loading) return "Loading"
        val estimate = trace_estimate as? TraceImpactEstimate ?: return "Unavailable"
        val full = estimate.full_flight ?: return "Unavailable: route/progress not credible enough"
        return "${carbon_range(full.carbon)} ${full.basis}"
    }

    private fun observed_carbon_intensity(
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        loading: Boolean,
        units: UnitSystem
    ): String {
        val estimate = trace_estimate as? TraceImpactEstimate
        if (estimate != null) {
            if (estimate.distance_m <= 0.0) return "Unavailable"
            val display_distance = units.distance_meters_to_display(estimate.distance_m)
            if (display_distance <= 0.0) return "Unavailable"
            return String.format(
                Locale.US,
                "%s-%s kg CO2/%s observed trace",
                kg_decimal(estimate.carbon.low_kg / display_distance),
                kg_decimal(estimate.carbon.high_kg / display_distance),
                units.distance_label
            )
        }
        if (profile == null || trace == null) return loading_or_unavailable(loading)
        if (trace.distance_m <= 0.0) return "Unavailable"
        val carbon = profile.carbon_for_hours(trace.hours)
        val display_distance = units.distance_meters_to_display(trace.distance_m)
        if (display_distance <= 0.0) return "Unavailable"
        return String.format(
            Locale.US,
            "%s-%s kg CO2/%s observed trace",
            kg_decimal(carbon.low_kg / display_distance),
            kg_decimal(carbon.high_kg / display_distance),
            units.distance_label
        )
    }

    private fun trace_history_carbon(
        usage_trace: FlightTrace?,
        profile: ImpactProfile?,
        trace_loading: Boolean,
        loading: Boolean
    ): String {
        if (trace_loading) return "Loading"
        if (profile == null) return loading_or_unavailable(loading)
        val trace = usage_trace ?: return "Unavailable"
        val stats = AircraftUsageAnalyzer.stats_for(trace)
        val pieces = mutableListOf<String>()
        if (stats.week_hours > 0.0 && stats.week_flight_count > 0) {
            pieces += "week ${kg_compact(profile.mid_co2_kg_per_hour() * stats.week_hours)} kg"
        }
        if (stats.total_hours > 0.0 && stats.flight_count > 0) {
            pieces += "trace window ${kg_compact(profile.mid_co2_kg_per_hour() * stats.total_hours)} kg"
        }
        return pieces.joinToString("; ").ifEmpty { "Unavailable" }
    }

    private fun lead_note(profile: ImpactProfile?, loading: Boolean): String {
        return when (profile?.fuel) {
            ImpactFuel.AVGAS -> "Avgas may be leaded; exact fuel and unleaded status are unavailable and not included in the carbon score."
            ImpactFuel.JET -> "Jet-fuel carbon is scored; leaded-avgas exposure is not part of this class."
            null -> loading_or_unavailable(loading)
        }
    }

    private fun confidence(
        profile: ImpactProfile?,
        trace: ImpactTrace?,
        trace_estimate: Any?,
        loading: Boolean,
        details: AircraftDetails?
    ): String {
        if (profile == null) return loading_or_unavailable(loading)
        val estimate = trace_estimate as? TraceImpactEstimate
        val trace_text = estimate?.let { "${it.confidence.label}: ${it.confidence.basis}" }
            ?: trace?.let { "real trace, ${it.point_count} pts" }
            ?: "no current trace total"
        val source_note = when {
            details?.registry_source?.contains("Wikipedia", ignoreCase = true) == true ->
                " Metadata includes broad internet fallback, so model confidence is lower than registry/API metadata."
            details?.registry_source?.contains("Airplanes.Live", ignoreCase = true) == true ->
                " Metadata uses live web/feed aircraft fields before registry/API fallbacks."
            else -> ""
        }
        return "${profile.confidence} Basis: $trace_text; ${profile.basis.display_name}, not measured fuel.$source_note"
    }

    private fun ImpactProfile.profile_basis_label(): String {
        return "${basis.display_name}; ${label}; exact fuel flow unavailable"
    }

    private fun distance(meters: Double, units: UnitSystem): String {
        return String.format(Locale.US, "%.1f %s", units.distance_meters_to_display(meters), units.distance_label)
    }

    private fun altitude(meters: Double?, units: UnitSystem): String {
        return meters?.let {
            String.format(Locale.US, "%.0f %s", units.altitude_meters_to_display(it), units.altitude_label)
        } ?: "Unavailable"
    }

    private fun speed(ms: Double?, units: UnitSystem): String {
        return ms?.let {
            String.format(Locale.US, "%.0f %s", units.speed_meters_per_second_to_display(it), units.speed_label)
        } ?: "Unavailable"
    }

    private fun age(aircraft: Aircraft): String {
        val contact = aircraft.last_contact_sec ?: aircraft.position_time_sec ?: return "Age unavailable"
        val age = max(0.0, System.currentTimeMillis() / 1000.0 - contact)
        return "${age.toLong()}s old"
    }

    private fun kg_compact(kg: Double): String {
        return if (kg >= 1000.0) {
            String.format(Locale.US, "%,.0f", kg)
        } else {
            String.format(Locale.US, "%.0f", kg)
        }
    }

    private fun kg_decimal(kg: Double): String {
        return when {
            kg >= 100.0 -> String.format(Locale.US, "%.0f", kg)
            kg >= 10.0 -> String.format(Locale.US, "%.1f", kg)
            else -> String.format(Locale.US, "%.2f", kg)
        }
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}



object AircraftTraceImpactAnalyzer {
    fun observed_estimate(
        profile: ImpactProfile,
        segments: List<TraceSegment>?,
        details: AircraftDetails?
    ): TraceImpactEstimate? {
        val usable_segments = segments?.filter { it.points.size >= 2 }.orEmpty()
        if (usable_segments.isEmpty()) return null

        val accumulator = PhaseAccumulator()
        var supported_altitude_legs = 0
        var total_legs = 0
        var largest_gap_sec = 0L
        usable_segments.forEach { segment ->
            segment.points.sortedBy { it.epoch_sec }.zipWithNext { previous, current ->
                val seconds = current.epoch_sec - previous.epoch_sec
                if (seconds <= 0L) return@zipWithNext
                total_legs += 1
                largest_gap_sec = max(largest_gap_sec, seconds)
                val phase = classify_phase(previous, current, seconds, profile)
                if (previous.altitude_m != null && current.altitude_m != null) supported_altitude_legs += 1
                accumulator.add(phase, previous, current, seconds, profile)
            }
        }
        if (accumulator.total_seconds <= 0L) return null

        val carbon = ImpactCarbonRange(
            low_kg = accumulator.low_kg,
            mid_kg = accumulator.mid_kg,
            high_kg = accumulator.high_kg
        )
        val hours = accumulator.total_seconds / 3600.0
        val distance_m = accumulator.distance_m
        val altitude_coverage = if (total_legs > 0) supported_altitude_legs.toDouble() / total_legs else 0.0
        val full_flight = full_flight_estimate(carbon, distance_m, usable_segments, details)
        val confidence = trace_confidence(
            profile = profile,
            point_count = usable_segments.sumOf { it.points.size },
            altitude_coverage = altitude_coverage,
            largest_gap_sec = largest_gap_sec,
            full_flight = full_flight
        )
        return TraceImpactEstimate(
            carbon = carbon,
            hours = hours,
            distance_m = distance_m,
            phase_hours = accumulator.phase_hours(),
            average_kg_per_hour_mid = carbon.mid_kg / hours,
            full_flight = full_flight,
            confidence = confidence
        )
    }

    private fun classify_phase(
        previous: TrackPoint,
        current: TrackPoint,
        seconds: Long,
        profile: ImpactProfile
    ): ImpactFlightPhase {
        val distance = distance_meters(previous.lat, previous.lon, current.lat, current.lon)
        val speed_ms = distance / seconds
        val altitude1 = previous.altitude_m
        val altitude2 = current.altitude_m
        val avg_altitude = listOfNotNull(altitude1, altitude2).average().takeIf { it.isFinite() }
        val vertical_rate = if (altitude1 != null && altitude2 != null) (altitude2 - altitude1) / seconds else null
        val cruise_ms = profile.cruise_knots * KNOT_TO_MPS

        return when {
            previous.on_ground == true && current.on_ground == true -> ImpactFlightPhase.GROUND
            avg_altitude != null && avg_altitude < 100.0 && speed_ms < 35.0 -> ImpactFlightPhase.GROUND
            vertical_rate != null && vertical_rate > 1.5 -> ImpactFlightPhase.CLIMB
            vertical_rate != null && vertical_rate < -1.5 -> ImpactFlightPhase.DESCENT
            avg_altitude != null && avg_altitude < 900.0 && speed_ms < cruise_ms * 0.55 -> ImpactFlightPhase.LOW_LEVEL
            vertical_rate != null && abs(vertical_rate) <= 1.5 && speed_ms >= cruise_ms * 0.45 -> ImpactFlightPhase.CRUISE
            vertical_rate == null -> ImpactFlightPhase.UNKNOWN
            else -> ImpactFlightPhase.LOW_LEVEL
        }
    }

    private fun full_flight_estimate(
        observed_carbon: ImpactCarbonRange,
        observed_distance_m: Double,
        segments: List<TraceSegment>,
        details: AircraftDetails?
    ): FullFlightImpactEstimate? {
        val origin = details?.origin_airport ?: return null
        val destination = details.destination_airport ?: return null
        val origin_lat = origin.latitude ?: return null
        val origin_lon = origin.longitude ?: return null
        val dest_lat = destination.latitude ?: return null
        val dest_lon = destination.longitude ?: return null
        val direct_route_m = distance_meters(origin_lat, origin_lon, dest_lat, dest_lon)
        if (direct_route_m < MIN_FULL_ROUTE_DISTANCE_M) return null
        val first = segments.firstOrNull()?.points?.firstOrNull() ?: return null
        val last = segments.lastOrNull()?.points?.lastOrNull() ?: return null
        val first_from_origin_m = distance_meters(origin_lat, origin_lon, first.lat, first.lon)
        if (first_from_origin_m > max(MAX_ORIGIN_CREDIT_M, direct_route_m * MAX_ORIGIN_CREDIT_FRACTION)) return null
        val remaining_m = distance_meters(last.lat, last.lon, dest_lat, dest_lon)
        val total_estimated_m = first_from_origin_m + observed_distance_m + remaining_m
        if (total_estimated_m < MIN_FULL_ROUTE_DISTANCE_M) return null
        val progress = ((first_from_origin_m + observed_distance_m) / total_estimated_m).coerceIn(0.0, 1.0)
        if (progress !in MIN_FULL_FLIGHT_PROGRESS..MAX_FULL_FLIGHT_PROGRESS) return null
        val scale = 1.0 / progress
        return FullFlightImpactEstimate(
            carbon = ImpactCarbonRange(
                low_kg = observed_carbon.low_kg * scale,
                mid_kg = observed_carbon.mid_kg * scale,
                high_kg = observed_carbon.high_kg * scale
            ),
            progress_fraction = progress,
            basis = String.format(Locale.US, "route-scaled from %.0f%% observed trace", progress * 100.0)
        )
    }

    private fun trace_confidence(
        profile: ImpactProfile,
        point_count: Int,
        altitude_coverage: Double,
        largest_gap_sec: Long,
        full_flight: FullFlightImpactEstimate?
    ): ImpactEstimateConfidence {
        val type_level = when (profile.basis) {
            ImpactProfileBasis.TYPE_SPECIFIC -> "type profile"
            ImpactProfileBasis.CLASS_BENCHMARK -> "class profile"
        }
        val trace_level = when {
            point_count >= 80 && largest_gap_sec <= 300L -> "dense trace"
            point_count >= 20 && largest_gap_sec <= 900L -> "usable trace"
            else -> "sparse trace"
        }
        val phase_level = when {
            altitude_coverage >= 0.9 -> "phase-aware"
            altitude_coverage >= 0.5 -> "partial phase-aware"
            else -> "time-only fallback"
        }
        val full_flight_level = if (full_flight != null) "route-scaled full-flight available" else "full-flight unavailable"
        val label = when {
            profile.basis == ImpactProfileBasis.TYPE_SPECIFIC && altitude_coverage >= 0.9 && point_count >= 80 -> "High"
            altitude_coverage >= 0.5 && point_count >= 20 -> "Medium"
            else -> "Medium-low"
        }
        return ImpactEstimateConfidence(label, "$type_level; $trace_level; $phase_level; $full_flight_level")
    }

    private class PhaseAccumulator {
        var total_seconds = 0L
            private set
        var distance_m = 0.0
            private set
        var low_kg = 0.0
            private set
        var mid_kg = 0.0
            private set
        var high_kg = 0.0
            private set
        private val seconds_by_phase = mutableMapOf<ImpactFlightPhase, Long>()

        fun add(
            phase: ImpactFlightPhase,
            previous: TrackPoint,
            current: TrackPoint,
            seconds: Long,
            profile: ImpactProfile
        ) {
            val hours = seconds / 3600.0
            val multiplier = phase.fuel_multiplier
            total_seconds += seconds
            seconds_by_phase[phase] = (seconds_by_phase[phase] ?: 0L) + seconds
            distance_m += distance_meters(previous.lat, previous.lon, current.lat, current.lon)
            low_kg += profile.low_co2_kg_per_hour() * multiplier * hours
            mid_kg += profile.mid_co2_kg_per_hour() * multiplier * hours
            high_kg += profile.high_co2_kg_per_hour() * multiplier * hours
        }

        fun phase_hours(): List<ImpactPhaseHours> {
            return seconds_by_phase
                .entries
                .sortedByDescending { it.value }
                .map { ImpactPhaseHours(it.key, it.value / 3600.0) }
        }
    }

    private const val KNOT_TO_MPS = 0.514444
    private const val MIN_FULL_ROUTE_DISTANCE_M = 25000.0
    private const val MAX_ORIGIN_CREDIT_M = 25000.0
    private const val MAX_ORIGIN_CREDIT_FRACTION = 0.12
    private const val MIN_FULL_FLIGHT_PROGRESS = 0.20
    private const val MAX_FULL_FLIGHT_PROGRESS = 0.97
    private const val EARTH_RADIUS_M = 6371000.0

    private fun distance_meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val d_lat = Math.toRadians(lat2 - lat1)
        val d_lon = Math.toRadians(lon2 - lon1)
        val r_lat1 = Math.toRadians(lat1)
        val r_lat2 = Math.toRadians(lat2)
        val a = (sin(d_lat / 2.0) * sin(d_lat / 2.0) +
            cos(r_lat1) * cos(r_lat2) * sin(d_lon / 2.0) * sin(d_lon / 2.0)
            ).coerceIn(0.0, 1.0)
        return EARTH_RADIUS_M * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}


enum class ImpactFlightPhase(val display_name: String, val fuel_multiplier: Double) {
    GROUND("ground/taxi", 0.18),
    CLIMB("climb", 1.35),
    CRUISE("cruise", 1.0),
    DESCENT("descent", 0.45),
    LOW_LEVEL("low-level/loiter", 0.85),
    UNKNOWN("time-only", 1.0)
}


data class ImpactPhaseHours(val phase: ImpactFlightPhase, val hours: Double)


data class FullFlightImpactEstimate(
    val carbon: ImpactCarbonRange,
    val progress_fraction: Double,
    val basis: String
)


data class ImpactEstimateConfidence(
    val label: String,
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
