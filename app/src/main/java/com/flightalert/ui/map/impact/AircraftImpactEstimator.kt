package com.flightalert.ui.map.impact

import com.flightalert.data.AircraftDetails
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

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
        val normalized = ((log10(profile.mid_co2_kg_per_hour()) - log10(SCORE_MIN_KG_CO2_PER_HOUR)) /
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
        confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable."
    )
    private val PISTON_ROTOR = ImpactProfile(
        label = "Piston rotorcraft",
        examples = "R22/R44",
        fuel = ImpactFuel.AVGAS,
        low_gallons_per_hour = 10.0,
        high_gallons_per_hour = 22.0,
        cruise_knots = 95.0,
        confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission."
    )
    private val TURBINE_ROTOR = ImpactProfile(
        label = "Turbine rotorcraft",
        examples = "H60/AW139/S-76",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 60.0,
        high_gallons_per_hour = 220.0,
        cruise_knots = 130.0,
        confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission."
    )
    private val TURBOPROP = ImpactProfile(
        label = "Turboprop or commuter aircraft",
        examples = "PC-12/C208/B350",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 40.0,
        high_gallons_per_hour = 160.0,
        cruise_knots = 250.0,
        confidence = "Medium: broad turbine-prop benchmark, not exact aircraft fuel flow."
    )
    private val LIGHT_JET = ImpactProfile(
        label = "Business or light jet",
        examples = "Citation/Phenom/Learjet",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 90.0,
        high_gallons_per_hour = 350.0,
        cruise_knots = 410.0,
        confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable."
    )
    private val REGIONAL = ImpactProfile(
        label = "Regional airline aircraft",
        examples = "CRJ/E175/Dash 8",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 220.0,
        high_gallons_per_hour = 650.0,
        cruise_knots = 430.0,
        confidence = "Medium: class benchmark only; load factor and route phase are unavailable."
    )
    private val NARROW_BODY = ImpactProfile(
        label = "Large narrow-body airliner",
        examples = "A320/B737/A321",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 550.0,
        high_gallons_per_hour = 950.0,
        cruise_knots = 455.0,
        confidence = "Medium: class benchmark only; load factor and route phase are unavailable."
    )
    private val HEAVY = ImpactProfile(
        label = "Heavy transport or wide-body aircraft",
        examples = "B777/A350/C-17",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 1200.0,
        high_gallons_per_hour = 3200.0,
        cruise_knots = 485.0,
        confidence = "Medium-low: heavy-aircraft fuel flow depends heavily on model, weight, and mission."
    )
    private val TACTICAL_JET = ImpactProfile(
        label = "High-performance tactical jet",
        examples = "F-16/F-18/F-35/T-38",
        fuel = ImpactFuel.JET,
        low_gallons_per_hour = 700.0,
        high_gallons_per_hour = 2200.0,
        cruise_knots = 480.0,
        confidence = "Low: military mission profile and power setting are unavailable."
    )

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
    val confidence: String
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

enum class ImpactFuel(val display_name: String, val co2_kg_per_gallon: Double) {
    JET("Probable jet fuel", 9.75),
    AVGAS("Probable aviation gasoline", 8.31)
}
