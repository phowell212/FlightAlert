@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
)

package com.flightalert.details

import com.flightalert.aircraft.AircraftDetails
import com.flightalert.aircraft.display_model_name
import java.util.Locale

internal fun representative_aircraft_model_names(details: AircraftDetails): List<String> {
    val base = display_model_name(details.manufacturer, details.type, details.type_code)
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

internal fun representative_aircraft_photo_queries(details: AircraftDetails, model: String): List<String> {
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

internal fun exact_aircraft_photo_queries(registration: String?, icao24: String): List<String> {
    return listOfNotNull(
        registration?.let { "\"$it\" aircraft exterior" },
        registration?.let { "\"$it\" exterior photo" },
        registration?.let { "\"$it\" aircraft photo" },
        registration?.let { "$it aircraft" },
        icao24.takeIf { it.isNotBlank() }?.let { "\"${it.uppercase(Locale.US)}\" aircraft" }
    ).distinct()
}

internal fun aircraft_photo_verification_terms(details: AircraftDetails, model: String): List<String> {
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

private fun manufacturer_aliases(manufacturer: String): List<String> {
    val normalized = manufacturer.uppercase(Locale.US)
    return when {
        "GRUMMAN" in normalized -> listOf("Grumman American", "Grumman")
        "AMERICAN" in normalized && "GENERAL" in normalized -> listOf(
            "American General",
            "Grumman American"
        )

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
        compact == "AA1" || compact.startsWith("AA1") -> listOf(
            "AA-1",
            "AA1",
            "American Yankee"
        )

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
        compact.startsWith("PA46") || compact.startsWith("P46T") -> listOf(
            "PA-46 Malibu",
            "PA-46 Mirage",
            "PA-46 Meridian",
            "PA-46"
        )

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
