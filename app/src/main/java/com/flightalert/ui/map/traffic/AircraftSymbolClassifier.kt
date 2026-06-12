package com.flightalert.ui.map.traffic

import com.flightalert.ui.map.Aircraft
import java.util.Locale

enum class AircraftSymbol {
    GENERAL_AVIATION,
    AIRLINER,
    ROTORCRAFT,
    GLIDER,
    UAV,
    SURFACE
}

object AircraftSymbolClassifier {
    fun symbol_for(aircraft: Aircraft): AircraftSymbol {
        if (aircraft.on_ground == true) return AircraftSymbol.SURFACE
        return when (aircraft.category) {
            4, 5, 6 -> AircraftSymbol.AIRLINER
            8 -> AircraftSymbol.ROTORCRAFT
            9, 10 -> AircraftSymbol.GLIDER
            14 -> AircraftSymbol.UAV
            16, 17, 18, 19, 20 -> AircraftSymbol.SURFACE
            else -> if (is_airliner_type_code(aircraft.type_code)) AircraftSymbol.AIRLINER else AircraftSymbol.GENERAL_AVIATION
        }
    }

    fun size_multiplier(aircraft: Aircraft, symbol: AircraftSymbol): Float {
        val code = aircraft.type_code?.uppercase(Locale.US)?.trim().orEmpty()
        return when (symbol) {
            AircraftSymbol.AIRLINER -> when {
                HEAVY_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.18f
                LARGE_AIRLINER_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.10f
                REGIONAL_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 0.98f
                else -> 1.05f
            }
            AircraftSymbol.GENERAL_AVIATION -> when {
                LIGHT_JET_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 1.04f
                SMALL_GENERAL_AVIATION_SIZE_TYPE_PREFIXES.any { code.startsWith(it) } -> 0.86f
                else -> 1.0f
            }
            AircraftSymbol.ROTORCRAFT -> 0.9f
            AircraftSymbol.GLIDER -> 0.82f
            AircraftSymbol.UAV -> 0.74f
            AircraftSymbol.SURFACE -> 0.86f
        }
    }

    private fun is_airliner_type_code(type_code: String?): Boolean {
        val code = type_code?.uppercase(Locale.US)?.trim() ?: return false
        return AIRLINER_TYPE_PREFIXES.any { code.startsWith(it) }
    }

    private val HEAVY_SIZE_TYPE_PREFIXES = listOf(
        "A34",
        "A35",
        "A38",
        "B74",
        "B77",
        "B78",
        "C5",
        "C17",
        "DC10",
        "MD11"
    )

    private val LARGE_AIRLINER_SIZE_TYPE_PREFIXES = listOf(
        "A30",
        "A31",
        "A32",
        "A33",
        "B70",
        "B71",
        "B72",
        "B73",
        "B75",
        "B76",
        "B79"
    )

    private val REGIONAL_SIZE_TYPE_PREFIXES = listOf(
        "AT4",
        "AT7",
        "BCS",
        "CRJ",
        "DH8",
        "E17",
        "E19",
        "E70",
        "E75",
        "F70",
        "F90"
    )

    private val LIGHT_JET_SIZE_TYPE_PREFIXES = listOf(
        "C25",
        "C55",
        "C56",
        "E50",
        "E55",
        "GLF",
        "LJ",
        "PRM",
        "SF50"
    )

    private val SMALL_GENERAL_AVIATION_SIZE_TYPE_PREFIXES = listOf(
        "BE23",
        "BE35",
        "BE36",
        "C15",
        "C17",
        "C18",
        "C19",
        "C20",
        "C21",
        "DA40",
        "DA42",
        "P28",
        "PA2",
        "SR20",
        "SR22"
    )

    private val AIRLINER_TYPE_PREFIXES = listOf(
        "A19",
        "A20",
        "A21",
        "A30",
        "A31",
        "A32",
        "A33",
        "A34",
        "A35",
        "A38",
        "AT4",
        "AT7",
        "B37",
        "B38",
        "B39",
        "B70",
        "B71",
        "B72",
        "B73",
        "B74",
        "B75",
        "B76",
        "B77",
        "B78",
        "BCS",
        "CRJ",
        "DH8",
        "E17",
        "E19",
        "E29",
        "E70",
        "E75",
        "F70",
        "F90",
        "MD8",
        "MD9"
    )
}
