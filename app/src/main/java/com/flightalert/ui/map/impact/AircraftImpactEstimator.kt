package com.flightalert.ui.map.impact

import com.flightalert.data.AircraftDetails
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

object AircraftImpactEstimator {
    val sourceLabels = listOf("EIA CO2", "EPA Hub", "ICAO", "FAA AEDT", "EPA Lead")
    val sourceUrls = listOf(
        "https://www.eia.gov/environment/emissions/co2_vol_mass.php",
        "https://www.epa.gov/climateleadership/ghg-emission-factors-hub",
        "https://www.icao.int/environmental-protection/environmental-tools/icec",
        "https://aedt.faa.gov/",
        "https://www.epa.gov/regulations-emissions-vehicles-and-engines/regulations-lead-emissions-aircraft"
    )

    fun factsFor(feedTypeCode: String?, category: Int?, onGround: Boolean?, details: AircraftDetails?): ImpactFacts {
        return ImpactFacts(
            feedTypeCode = feedTypeCode,
            detailsTypeCode = details?.typeCode,
            manufacturer = details?.manufacturer,
            model = details?.type,
            category = category,
            onGround = onGround
        )
    }

    fun typeCode(facts: ImpactFacts): String? {
        return listOfNotNull(facts.detailsTypeCode, facts.feedTypeCode)
            .map { it.trim().uppercase(Locale.US) }
            .firstOrNull { it.isNotBlank() }
    }

    fun profileFor(facts: ImpactFacts): ImpactProfile? {
        val code = typeCode(facts).orEmpty()
        val text = searchText(facts)
        if (code.isBlank() && text.isBlank()) return null
        if (facts.category == 14 || text.contains("UAV") || text.contains("DRONE")) return null
        if (facts.category == 9 || code.startsWith("GL") || text.contains("GLIDER")) return null
        if (facts.onGround == true && facts.category != null && facts.category in listOf(16, 17, 18, 19, 20)) return null

        return when {
            isSmallPistonAircraft(code, text) -> PISTON_SINGLE
            isPistonRotorcraft(code, text) -> PISTON_ROTOR
            isTurbineRotorcraft(code, text) -> TURBINE_ROTOR
            isMilitaryTransportAircraft(code, text) -> HEAVY
            isTacticalJetAircraft(code, text) -> TACTICAL_JET
            isHeavyAircraft(code, text) -> HEAVY
            isLargeAirlinerAircraft(code, text) -> NARROW_BODY
            isRegionalAircraft(code, text) -> REGIONAL
            isTurbopropAircraft(code, text) -> TURBOPROP
            isLightJetAircraft(code, text) -> LIGHT_JET
            facts.category in listOf(4, 5, 6) -> NARROW_BODY
            facts.category == 8 -> null
            isAirlinerTypeCode(code) -> NARROW_BODY
            else -> null
        }
    }

    fun score(profile: ImpactProfile): Int {
        val normalized = ((log10(profile.midCo2KgPerHour()) - log10(SCORE_MIN_KG_CO2_PER_HOUR)) /
            (log10(SCORE_MAX_KG_CO2_PER_HOUR) - log10(SCORE_MIN_KG_CO2_PER_HOUR)))
            .coerceIn(0.0, 1.0)
        return (normalized * 100.0).roundToInt()
    }

    fun comparison(profile: ImpactProfile): String {
        val current = profile.midCo2KgPerHour()
        val piston = PISTON_SINGLE.midCo2KgPerHour()
        val narrow = NARROW_BODY.midCo2KgPerHour()
        return "${formatMultiplier(current, piston)} piston single; ${formatMultiplier(current, narrow)} A320/B737 class"
    }

    private fun searchText(facts: ImpactFacts): String {
        return listOfNotNull(facts.feedTypeCode, facts.detailsTypeCode, facts.manufacturer, facts.model)
            .joinToString(" ")
            .uppercase(Locale.US)
    }

    private fun isSmallPistonAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, SMALL_PISTON_PREFIXES, SMALL_PISTON_TEXT)
    }

    private fun isPistonRotorcraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, PISTON_ROTOR_PREFIXES, PISTON_ROTOR_TEXT)
    }

    private fun isTurbineRotorcraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, TURBINE_ROTOR_PREFIXES, TURBINE_ROTOR_TEXT)
    }

    private fun isTurbopropAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, TURBOPROP_PREFIXES, TURBOPROP_TEXT)
    }

    private fun isRegionalAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, REGIONAL_PREFIXES, REGIONAL_TEXT)
    }

    private fun isLightJetAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, LIGHT_JET_PREFIXES, LIGHT_JET_TEXT)
    }

    private fun isLargeAirlinerAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, NARROW_BODY_PREFIXES, NARROW_BODY_TEXT)
    }

    private fun isHeavyAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, HEAVY_PREFIXES, HEAVY_TEXT)
    }

    private fun isMilitaryTransportAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, MILITARY_TRANSPORT_PREFIXES, MILITARY_TRANSPORT_TEXT)
    }

    private fun isTacticalJetAircraft(code: String, text: String): Boolean {
        return matchesImpactTerms(code, text, TACTICAL_JET_PREFIXES, TACTICAL_JET_TEXT)
    }

    private fun isAirlinerTypeCode(code: String): Boolean {
        return NARROW_BODY_PREFIXES.any { code.startsWith(it) } ||
            HEAVY_PREFIXES.any { code.startsWith(it) } ||
            REGIONAL_PREFIXES.any { code.startsWith(it) }
    }

    private fun matchesImpactTerms(
        code: String,
        text: String,
        prefixes: List<String>,
        phrases: List<String>
    ): Boolean {
        val compactCode = compactImpactText(code)
        val compactText = compactImpactText(text)
        return prefixes.any { prefix ->
            val compactPrefix = compactImpactText(prefix)
            compactPrefix.isNotBlank() &&
                (compactCode.startsWith(compactPrefix) || compactText.contains(compactPrefix))
        } || phrases.any { phrase ->
            text.contains(phrase) || compactText.contains(compactImpactText(phrase))
        }
    }

    private fun compactImpactText(value: String): String {
        return value.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), "")
    }

    private fun formatMultiplier(value: Double, baseline: Double): String {
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
        lowGallonsPerHour = 8.0,
        highGallonsPerHour = 18.0,
        cruiseKnots = 125.0,
        confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable."
    )
    private val PISTON_ROTOR = ImpactProfile(
        label = "Piston rotorcraft",
        examples = "R22/R44",
        fuel = ImpactFuel.AVGAS,
        lowGallonsPerHour = 10.0,
        highGallonsPerHour = 22.0,
        cruiseKnots = 95.0,
        confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission."
    )
    private val TURBINE_ROTOR = ImpactProfile(
        label = "Turbine rotorcraft",
        examples = "H60/AW139/S-76",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 60.0,
        highGallonsPerHour = 220.0,
        cruiseKnots = 130.0,
        confidence = "Medium-low: rotorcraft fuel flow varies strongly by engine and mission."
    )
    private val TURBOPROP = ImpactProfile(
        label = "Turboprop or commuter aircraft",
        examples = "PC-12/C208/B350",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 40.0,
        highGallonsPerHour = 160.0,
        cruiseKnots = 250.0,
        confidence = "Medium: broad turbine-prop benchmark, not exact aircraft fuel flow."
    )
    private val LIGHT_JET = ImpactProfile(
        label = "Business or light jet",
        examples = "Citation/Phenom/Learjet",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 90.0,
        highGallonsPerHour = 350.0,
        cruiseKnots = 410.0,
        confidence = "Medium: type class is supported, exact engine/fuel flow is unavailable."
    )
    private val REGIONAL = ImpactProfile(
        label = "Regional airline aircraft",
        examples = "CRJ/E175/Dash 8",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 220.0,
        highGallonsPerHour = 650.0,
        cruiseKnots = 430.0,
        confidence = "Medium: class benchmark only; load factor and route phase are unavailable."
    )
    private val NARROW_BODY = ImpactProfile(
        label = "Large narrow-body airliner",
        examples = "A320/B737/A321",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 550.0,
        highGallonsPerHour = 950.0,
        cruiseKnots = 455.0,
        confidence = "Medium: class benchmark only; load factor and route phase are unavailable."
    )
    private val HEAVY = ImpactProfile(
        label = "Heavy transport or wide-body aircraft",
        examples = "B777/A350/C-17",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 1200.0,
        highGallonsPerHour = 3200.0,
        cruiseKnots = 485.0,
        confidence = "Medium-low: heavy-aircraft fuel flow depends heavily on model, weight, and mission."
    )
    private val TACTICAL_JET = ImpactProfile(
        label = "High-performance tactical jet",
        examples = "F-16/F-18/F-35/T-38",
        fuel = ImpactFuel.JET,
        lowGallonsPerHour = 700.0,
        highGallonsPerHour = 2200.0,
        cruiseKnots = 480.0,
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
    val feedTypeCode: String?,
    val detailsTypeCode: String?,
    val manufacturer: String?,
    val model: String?,
    val category: Int?,
    val onGround: Boolean?
)

data class ImpactCarbonRange(val lowKg: Double, val midKg: Double, val highKg: Double)

data class ImpactProfile(
    val label: String,
    val examples: String,
    val fuel: ImpactFuel,
    val lowGallonsPerHour: Double,
    val highGallonsPerHour: Double,
    val cruiseKnots: Double,
    val confidence: String
) {
    fun midpointGallonsPerHour(): Double = (lowGallonsPerHour + highGallonsPerHour) / 2.0

    fun lowCo2KgPerHour(): Double = lowGallonsPerHour * fuel.co2KgPerGallon

    fun highCo2KgPerHour(): Double = highGallonsPerHour * fuel.co2KgPerGallon

    fun midCo2KgPerHour(): Double = midpointGallonsPerHour() * fuel.co2KgPerGallon

    fun carbonForHours(hours: Double): ImpactCarbonRange {
        return ImpactCarbonRange(
            lowKg = lowCo2KgPerHour() * hours,
            midKg = midCo2KgPerHour() * hours,
            highKg = highCo2KgPerHour() * hours
        )
    }

    fun cruiseIntensityLabel(): String {
        val low = lowCo2KgPerHour() / cruiseKnots
        val high = highCo2KgPerHour() / cruiseKnots
        return String.format(Locale.US, "%s, ~%.1f-%.1f kg CO2/NM", examples, low, high)
    }

    fun fuelAndFactorLabel(): String {
        return String.format(
            Locale.US,
            "%s, %.0f-%.0f gal/hr, %.2f kg CO2/gal EIA",
            fuel.displayName,
            lowGallonsPerHour,
            highGallonsPerHour,
            fuel.co2KgPerGallon
        )
    }
}

enum class ImpactFuel(val displayName: String, val co2KgPerGallon: Double) {
    JET("Probable jet fuel", 9.75),
    AVGAS("Probable aviation gasoline", 8.31)
}
