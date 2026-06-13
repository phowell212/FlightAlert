package com.flightalert.ui.map.photo

import com.flightalert.data.AircraftDetails
import java.util.Locale

object AircraftPhotoCatalog {
    fun representative_model_names(details: AircraftDetails): List<String> {
        val base = representative_model_name(details)
        val type = details.type?.trim()
        val manufacturer = details.manufacturer?.trim()
        val aliases = mutableListOf<String>()
        if (base != null) aliases += base
        if (manufacturer != null && type != null) {
            aliases += "$manufacturer $type"
            manufacturer_aliases(manufacturer).forEach { alias -> aliases += "$alias $type" }
            type_model_aliases(type).forEach { alias_type ->
                aliases += "$manufacturer $alias_type"
                manufacturer_aliases(manufacturer).forEach { alias -> aliases += "$alias $alias_type" }
            }
        }
        type?.let { aliases += it }
        return aliases
            .map { normalize_aircraft_search_name(it) }
            .filter { it.length >= 3 }
            .distinct()
    }

    fun representative_photo_queries(details: AircraftDetails, model: String): List<String> {
        return listOfNotNull(
            "\"$model\" aircraft exterior",
            "$model aircraft exterior",
            "\"$model\" exterior photo",
            "\"$model\" aircraft",
            "$model aircraft",
            "\"$model\" aircraft photo",
            "$model aircraft photo",
            "\"$model\" airplane",
            "$model airplane",
            "\"$model\" airliner",
            "\"$model\" in flight",
            details.type?.let { "\"$it\" aircraft" },
            details.type_code?.let { "${details.manufacturer.orEmpty()} $it aircraft".trim() },
            details.type_code?.let { "$it aircraft" }
        ).filter { it.isNotBlank() }.distinct()
    }

    fun exact_photo_queries(registration: String?, icao24: String): List<String> {
        return listOfNotNull(
            registration?.let { "\"$it\" aircraft exterior" },
            registration?.let { "\"$it\" exterior photo" },
            registration?.let { "\"$it\" aircraft photo" },
            registration?.let { "$it aircraft" },
            icao24.takeIf { it.isNotBlank() }?.let { "\"${it.uppercase(Locale.US)}\" aircraft" }
        ).distinct()
    }

    fun photo_verification_terms(details: AircraftDetails, model: String): List<String> {
        val family_terms = listOfNotNull(
            Regex("""Airbus A\d{3}""").find(model)?.value,
            Regex("""Boeing \d{3}""").find(model)?.value,
            Regex("""Cessna \d{3}""").find(model)?.value,
            Regex("""Piper PA-\d{2}""").find(model)?.value,
            Regex("""Embraer \d{3}""").find(model)?.value
        )
        return listOfNotNull(
            model,
            model.substringAfterLast(" ").takeIf { it.length >= 3 && it.any(Char::isDigit) },
            details.type,
            details.type_code
        ).plus(family_terms)
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun representative_model_name(details: AircraftDetails): String? {
        val code = details.type_code?.uppercase(Locale.US)?.trim()
        val known_model = when (code) {
            "A19N" -> "Airbus A319neo"
            "A20N" -> "Airbus A320neo"
            "A21N" -> "Airbus A321neo"
            "A319" -> "Airbus A319"
            "A320" -> "Airbus A320"
            "A321" -> "Airbus A321"
            "B37M" -> "Boeing 737 MAX 7"
            "B38M" -> "Boeing 737 MAX 8"
            "B39M" -> "Boeing 737 MAX 9"
            "B3XM" -> "Boeing 737 MAX 10"
            "B737" -> "Boeing 737"
            "B738" -> "Boeing 737-800"
            "B739" -> "Boeing 737-900"
            "B752" -> "Boeing 757-200"
            "B763" -> "Boeing 767-300"
            "B772" -> "Boeing 777-200"
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
            "C700" -> "Cessna Citation Longitude"
            "CL30" -> "Bombardier Challenger 300"
            "CL35" -> "Bombardier Challenger 350"
            "CL60" -> "Bombardier Challenger 600"
            "CRJ7" -> "Bombardier CRJ700"
            "CRJ9" -> "Bombardier CRJ900"
            "DA40" -> "Diamond DA40"
            "DA42" -> "Diamond DA42"
            "GA7" -> "Grumman American GA-7 Cougar"
            "DH8D" -> "De Havilland Canada Dash 8 Q400"
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
            "LJ35" -> "Learjet 35"
            "LJ45" -> "Learjet 45"
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
        return listOfNotNull(details.manufacturer, details.type ?: details.type_code)
            .joinToString(" ")
            .trim()
            .ifEmpty { null }
    }

    private fun manufacturer_aliases(manufacturer: String): List<String> {
        val normalized = manufacturer.uppercase(Locale.US)
        return when {
            "GRUMMAN" in normalized -> listOf("Grumman American", "Grumman")
            "AMERICAN" in normalized && "GENERAL" in normalized -> listOf("American General", "Grumman American")
            "CESSNA" in normalized -> listOf("Cessna")
            "PIPER" in normalized -> listOf("Piper")
            "BEECH" in normalized -> listOf("Beechcraft", "Beech")
            "CIRRUS" in normalized -> listOf("Cirrus")
            "DIAMOND" in normalized -> listOf("Diamond")
            "MOONEY" in normalized -> listOf("Mooney")
            "ROBINSON" in normalized -> listOf("Robinson")
            "VANS" in normalized || "VAN'S" in normalized -> listOf("Van's", "Vans")
            "PILATUS" in normalized -> listOf("Pilatus")
            else -> emptyList()
        }
    }

    private fun type_model_aliases(type: String): List<String> {
        val normalized = type.uppercase(Locale.US).replace(Regex("[^A-Z0-9]+"), " ").trim()
        val compact = normalized.replace(" ", "")
        return when {
            compact == "AA1" || compact.startsWith("AA1") -> listOf("AA-1", "AA1", "American Yankee")
            compact.startsWith("AA5") -> listOf("AA-5", "AA5", "Cheetah", "Tiger")
            compact.startsWith("GA7") -> listOf("GA-7", "GA7", "Cougar")
            compact.startsWith("C120") -> listOf("120")
            compact.startsWith("C140") -> listOf("140")
            compact.startsWith("C150") -> listOf("150")
            compact.startsWith("C152") -> listOf("152")
            compact.startsWith("C172") -> listOf("172 Skyhawk", "172")
            compact.startsWith("C177") -> listOf("177 Cardinal", "177")
            compact.startsWith("C182") -> listOf("182 Skylane", "182")
            compact.startsWith("C185") -> listOf("185 Skywagon", "185")
            compact.startsWith("C206") -> listOf("206 Stationair", "206")
            compact.startsWith("C210") -> listOf("210 Centurion", "210")
            compact.startsWith("C337") -> listOf("337 Skymaster", "337")
            compact.startsWith("BE33") -> listOf("Bonanza")
            compact.startsWith("BE35") -> listOf("Bonanza")
            compact.startsWith("BE36") -> listOf("Bonanza")
            compact.startsWith("BE55") -> listOf("Baron")
            compact.startsWith("BE58") -> listOf("Baron")
            compact.startsWith("BE20") -> listOf("King Air 200", "King Air")
            compact.startsWith("B350") -> listOf("King Air 350", "King Air")
            compact.startsWith("PA28") -> listOf("PA-28 Cherokee", "PA-28")
            compact.startsWith("PA32") -> listOf("PA-32 Cherokee Six", "PA-32")
            compact.startsWith("PA34") -> listOf("PA-34 Seneca", "PA-34")
            compact.startsWith("PA44") -> listOf("PA-44 Seminole", "PA-44")
            compact.startsWith("PA46") || compact.startsWith("P46T") -> listOf("PA-46 Malibu", "PA-46 Mirage", "PA-46 Meridian", "PA-46")
            compact.startsWith("SR20") -> listOf("SR20")
            compact.startsWith("SR22") || compact.startsWith("S22T") -> listOf("SR22", "SR22T")
            compact.startsWith("DA40") -> listOf("DA40")
            compact.startsWith("DA42") -> listOf("DA42")
            compact.startsWith("M20") -> listOf("M20")
            compact.startsWith("RV6") -> listOf("RV-6", "RV6")
            compact.startsWith("RV7") -> listOf("RV-7", "RV7")
            compact.startsWith("RV8") -> listOf("RV-8", "RV8")
            compact.startsWith("RV9") -> listOf("RV-9", "RV9")
            compact.startsWith("R44") -> listOf("R44 Raven", "R44")
            compact.startsWith("R66") -> listOf("R66")
            compact.startsWith("PC12") -> listOf("PC-12", "PC12")
            else -> emptyList()
        }
    }

    private fun normalize_aircraft_search_name(value: String): String {
        return value
            .replace(Regex("\\bINC\\.?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bCORP\\.?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bAVN\\.?\\b", RegexOption.IGNORE_CASE), " Aviation ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
