@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import java.util.Locale


object AircraftSymbolClassifier {
    fun symbol_for(aircraft: Aircraft): AircraftSymbol {
        if (aircraft.on_ground == true) return AircraftSymbol.SURFACE
        when (aircraft.category) {
            8 -> return AircraftSymbol.ROTORCRAFT
            9, 10 -> return AircraftSymbol.GLIDER
            14 -> return AircraftSymbol.UAV
            16, 17, 18, 19, 20 -> return AircraftSymbol.SURFACE
        }
        val type_code = normalized_type_code(aircraft.type_code)
        special_symbol_for_normalized_type_code(type_code)?.let { return it }
        if (type_code.startsWith("H25") ||
            type_code == "HDJT" ||
            type_code.startsWith("GLF") ||
            type_code == "GLEX" ||
            type_code == "GL5T" ||
            type_code == "GL7T"
        ) return AircraftSymbol.GENERAL_AVIATION
        return if (aircraft.category in 4..6 || is_airliner_type_code(type_code)) {
            AircraftSymbol.AIRLINER
        } else {
            AircraftSymbol.GENERAL_AVIATION
        }
    }

    internal fun special_symbol_for_type_code(type_code: String?): AircraftSymbol? {
        return special_symbol_for_normalized_type_code(normalized_type_code(type_code))
    }

    fun size_multiplier(aircraft: Aircraft, symbol: AircraftSymbol): Float {
        val code = normalized_type_code(aircraft.type_code)
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

    private fun normalized_type_code(type_code: String?): String {
        return type_code?.trim()?.uppercase(Locale.US).orEmpty()
    }

    private fun special_symbol_for_normalized_type_code(type_code: String): AircraftSymbol? {
        return when (type_code) {
            in ROTORCRAFT_TYPE_CODES -> AircraftSymbol.ROTORCRAFT
            "GLID", "GLIM" -> AircraftSymbol.GLIDER
            "FFLO", "VFHC", "UAV", "DRON" -> AircraftSymbol.UAV
            else -> null
        }
    }

    private fun is_airliner_type_code(type_code: String): Boolean {
        return AIRLINER_TYPE_PREFIXES.any { type_code.startsWith(it) }
    }

    // Reuse the type-code tables across every classification path.
    private val ROTORCRAFT_TYPE_CODES = setOf(
        "A002", "A109", "A119", "A129", "A139", "A149", "A169", "A189",
        "A205", "A2RT", "A600", "AC10", "AC31", "AC33", "ADEL", "AG1",
        "ALH", "ALO2", "ALO3", "ANST", "AR1", "AS32", "AS3B", "AS50",
        "AS55", "AS65", "B06", "B06T", "B105", "B150", "B212", "B214",
        "B222", "B230", "B305", "B407", "B412", "B427", "B429", "B430",
        "B47G", "B47J", "B47T", "B505", "B525", "B609", "BABY", "BK17",
        "BR54", "BRB2", "BSTP", "CDUS", "CH12", "CH14", "CH7", "CHIF",
        "CHSY", "CLD2", "CLON", "CMD1", "CMDE", "CMDT", "COMU", "DEAG",
        "DJIN", "DRAG", "DYH2", "DYH3", "EC20", "EC25", "EC30", "EC35",
        "EC45", "EC55", "EC75", "EGL3", "EH10", "EL10", "ELA7", "ELTO",
        "EN28", "EN48", "ES11", "EXEC", "EXEJ", "EXPL", "FH11", "FREL",
        "G2CA", "GAZL", "GOBU", "GYRO", "H12T", "H160", "H2", "H21",
        "H269", "H43A", "H43B", "H46", "H47", "H500", "H53", "H53S",
        "H60", "H64", "HA2", "HAW3", "HSMT", "HUCO", "HW4P", "HW4T",
        "HX2", "IS2", "J4B2", "JAG2", "JAS4", "JE2", "JRO", "K126",
        "K209", "K226", "KA25", "KA26", "KA27", "KA50", "KA52", "KA62",
        "KH4", "KMAX", "LAMA", "LCH", "LR2T", "LYNX", "M74", "MD52",
        "MD60", "MH20", "MI10", "MI14", "MI2", "MI24", "MI26", "MI28",
        "MI34", "MI38", "MI4", "MI6", "MI8", "MM14", "MM16", "MM19",
        "MM21", "MM22", "MM24", "MMAX", "MT", "NA40", "NH90", "OH1",
        "OKHO", "PAV4", "PCA2", "PHIL", "PHIX", "PSW4", "PUMA", "R22",
        "R4", "R44", "R66", "RAF2", "REV6", "RMOU", "RP1", "RPUP",
        "RVAL", "S274", "S278", "S285", "S330", "S360", "S434", "S51",
        "S52", "S55P", "S55T", "S58P", "S58T", "S61", "S61R", "S62",
        "S64", "S65C", "S76", "S92", "S97", "SB1", "SCII", "SCOR",
        "SCOU", "SH09", "SH4", "SNAD", "SPGY", "SPHA", "SSC", "SUCO",
        "SURN", "SYCA", "TIGR", "TSTR", "UFHT", "UH1", "UH12", "UH1Y",
        "UHEL", "ULTS", "UM18", "V22", "V280", "V500", "W3", "WASP",
        "WESX", "WZ10", "X2", "X3", "X49", "XNON", "YNHL", "ZA6", "ZEFR"
    )

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
