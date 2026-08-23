@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.aircraft

import java.util.Locale


internal fun display_model_name(manufacturer: String?, type: String?, type_code: String?): String? {
    val code = type_code?.uppercase(Locale.US)?.trim()
    val known_model = when (code) {
        "A19N" -> "Airbus A319neo"
        "A20N" -> "Airbus A320neo"
        "A21N" -> "Airbus A321neo"
        "A319" -> "Airbus A319"
        "A320" -> "Airbus A320"
        "A321" -> "Airbus A321"
        "A359" -> "Airbus A350-900"
        "A35K" -> "Airbus A350-1000"
        "A388" -> "Airbus A380-800"
        "B37M" -> "Boeing 737 MAX 7"
        "B38M" -> "Boeing 737 MAX 8"
        "B39M" -> "Boeing 737 MAX 9"
        "B3XM" -> "Boeing 737 MAX 10"
        "B737" -> "Boeing 737"
        "B738" -> "Boeing 737-800"
        "B739" -> "Boeing 737-900"
        "B752" -> "Boeing 757-200"
        "B762" -> "Boeing 767-200"
        "B763" -> "Boeing 767-300"
        "B764" -> "Boeing 767-400"
        "B772" -> "Boeing 777-200"
        "B77L" -> "Boeing 777-200LR"
        "B77W" -> "Boeing 777-300ER"
        "B788" -> "Boeing 787-8"
        "B789" -> "Boeing 787-9"
        "B78X" -> "Boeing 787-10"
        "B744" -> "Boeing 747-400"
        "B748" -> "Boeing 747-8"
        "BCS1" -> "Airbus A220-100"
        "BCS3" -> "Airbus A220-300"
        "AT72" -> "ATR 72"
        "AT76" -> "ATR 72-600"
        "AA1" -> "Grumman American AA-1 Yankee"
        "AA5" -> "Grumman American AA-5 Traveler"
        "AG5B" -> "American General AG-5B Tiger"
        "BE20" -> "Beechcraft King Air 200"
        "BE30" -> "Beechcraft King Air 300"
        "BE33" -> "Beechcraft Bonanza"
        "BE35" -> "Beechcraft Bonanza"
        "BE36" -> "Beechcraft Bonanza"
        "BE40" -> "Beechjet 400"
        "BE55" -> "Beechcraft Baron"
        "BE58" -> "Beechcraft Baron"
        "BE76" -> "Beechcraft Duchess"
        "B350" -> "Beechcraft King Air 350"
        "B407" -> "Bell 407"
        "C120" -> "Cessna 120"
        "C140" -> "Cessna 140"
        "C150" -> "Cessna 150"
        "C152" -> "Cessna 152"
        "C172" -> "Cessna 172"
        "C175" -> "Cessna 175 Skylark"
        "C177" -> "Cessna 177 Cardinal"
        "C180" -> "Cessna 180"
        "C182" -> "Cessna 182"
        "C195" -> "Cessna 195"
        "C185" -> "Cessna 185"
        "C206" -> "Cessna 206"
        "C208" -> "Cessna 208 Caravan"
        "C210" -> "Cessna 210"
        "C337" -> "Cessna 337 Skymaster"
        "C25A" -> "Cessna Citation CJ2"
        "C25B" -> "Cessna Citation CJ3"
        "C25C" -> "Cessna Citation CJ4"
        "C310" -> "Cessna 310"
        "C414" -> "Cessna 414"
        "C421" -> "Cessna 421"
        "C525" -> "Cessna CitationJet"
        "C56X" -> "Cessna Citation Excel"
        "C680" -> "Cessna Citation Sovereign"
        "C68A" -> "Cessna Citation Latitude"
        "C700" -> "Cessna Citation Longitude"
        "C130" -> "Lockheed C-130 Hercules"
        "C17" -> "Boeing C-17 Globemaster III"
        "CL30" -> "Bombardier Challenger 300"
        "CL35" -> "Bombardier Challenger 350"
        "CL60" -> "Bombardier Challenger 600"
        "CRJ2" -> "Bombardier CRJ200"
        "CRJ7" -> "Bombardier CRJ700"
        "CRJ9" -> "Bombardier CRJ900"
        "DA40" -> "Diamond DA40"
        "DA42" -> "Diamond DA42"
        "GA7" -> "Grumman American GA-7 Cougar"
        "DH8D" -> "De Havilland Canada Dash 8 Q400"
        "A139" -> "Leonardo AW139"
        "AS50" -> "Airbus Helicopters AS350"
        "EC30" -> "Airbus Helicopters EC130"
        "EC35" -> "Airbus Helicopters EC135"
        "EC45" -> "Airbus Helicopters EC145"
        "E170" -> "Embraer 170"
        "E75L", "E75S" -> "Embraer 175"
        "E190" -> "Embraer 190"
        "E195" -> "Embraer 195"
        "E50P" -> "Embraer Phenom 100"
        "E55P" -> "Embraer Phenom 300"
        "F2TH" -> "Dassault Falcon 2000"
        "F900" -> "Dassault Falcon 900"
        "FA50" -> "Dassault Falcon 50"
        "GL5T" -> "Bombardier Global 5000"
        "GL7T" -> "Bombardier Global 7500"
        "GLEX" -> "Bombardier Global Express"
        "GLF4" -> "Gulfstream IV"
        "GLF5" -> "Gulfstream V"
        "GLF6" -> "Gulfstream G650"
        "H25B" -> "Hawker 800"
        "LJ31" -> "Learjet 31"
        "LJ35" -> "Learjet 35"
        "LJ45" -> "Learjet 45"
        "LJ60" -> "Learjet 60"
        "P28A" -> "Piper PA-28 Cherokee"
        "PA28" -> "Piper PA-28 Cherokee"
        "PA30" -> "Piper PA-30 Twin Comanche"
        "PA31" -> "Piper PA-31 Navajo"
        "PA32" -> "Piper PA-32 Cherokee Six"
        "PA34" -> "Piper PA-34 Seneca"
        "PA44" -> "Piper PA-44 Seminole"
        "P46T" -> "Piper PA-46 Malibu"
        "PC12" -> "Pilatus PC-12"
        "R44" -> "Robinson R44"
        "R66" -> "Robinson R66"
        "S22T" -> "Cirrus SR22T"
        "SF34" -> "Saab 340"
        "SR20" -> "Cirrus SR20"
        "SR22" -> "Cirrus SR22"
        "M20P" -> "Mooney M20"
        "M20T" -> "Mooney M20"
        "RV6" -> "Van's RV-6"
        "RV7" -> "Van's RV-7"
        "RV8" -> "Van's RV-8"
        "RV9" -> "Van's RV-9"
        "TBM7" -> "Socata TBM 700"
        "TBM8" -> "Socata TBM 850"
        "TBM9" -> "Daher TBM 900"
        else -> null
    }
    if (known_model != null) return known_model
    val maker = human_aircraft_name_part(manufacturer)
    val model = human_aircraft_name_part(type ?: type_code)
    return when {
        model == null -> maker
        maker == null || model_starts_with_aircraft_brand(model) -> model
        else -> "$maker $model"
    }
}

private fun model_starts_with_aircraft_brand(value: String): Boolean {
    val normalized = value.uppercase(Locale.US)
    return listOf(
        "AIRBUS",
        "BOEING",
        "CESSNA",
        "PIPER",
        "BEECHCRAFT",
        "CIRRUS",
        "DIAMOND",
        "EMBRAER",
        "BOMBARDIER",
        "DASSAULT",
        "GULFSTREAM",
        "LEARJET",
        "PILATUS",
        "ROBINSON",
        "SAAB",
        "ATR",
        "EUROCOPTER",
        "DE HAVILLAND",
        "HAWKER",
        "MOONEY",
        "DAHER",
        "SOCATA"
    ).any { brand -> normalized == brand || normalized.startsWith("$brand ") }
}

private fun human_aircraft_name_part(value: String?): String? {
    val clean = value?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotBlank() }
        ?: return null
    return clean.split(" ").joinToString(" ") { word ->
        if (word.any(Char::isLowerCase) || word.any(Char::isDigit) || word.length <= 3) {
            word
        } else {
            word.lowercase(Locale.US).replaceFirstChar { char -> char.titlecase(Locale.US) }
        }
    }
}
