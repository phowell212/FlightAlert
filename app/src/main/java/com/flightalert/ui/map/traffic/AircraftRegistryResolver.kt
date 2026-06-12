package com.flightalert.ui.map.traffic

import java.util.Locale

data class RegistryCountry(val iso_code: String, val name: String) {
    val label: String
        get() = "${flag_emoji(iso_code)} $name"

    private fun flag_emoji(code: String): String {
        val normalized = code.uppercase(Locale.US).take(2)
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) return normalized
        val first = Character.toChars(0x1F1E6 + (normalized[0] - 'A')).concatToString()
        val second = Character.toChars(0x1F1E6 + (normalized[1] - 'A')).concatToString()
        return first + second
    }
}

data class IcaoRegistryRange(val start: Int, val end: Int, val country: RegistryCountry)

enum class RegistryCountrySource {
    REGISTRATION,
    ICAO_ALLOCATION
}

data class RegistryCountryMatch(val country: RegistryCountry, val source: RegistryCountrySource) {
    val label: String
        get() = country.label
}

object AircraftRegistryResolver {
    fun country_for(registration: String?, icao24: String): RegistryCountryMatch? {
        country_from_registration(registration)?.let {
            return RegistryCountryMatch(it, RegistryCountrySource.REGISTRATION)
        }
        country_from_icao24(icao24)?.let {
            return RegistryCountryMatch(it, RegistryCountrySource.ICAO_ALLOCATION)
        }
        return null
    }

    fun label_for(registration: String?, icao24: String): String? {
        return country_for(registration, icao24)?.label
    }

    private fun country_from_registration(registration: String?): RegistryCountry? {
        val reg = registration ?: return null
        return when {
            reg.startsWith("N") && reg.getOrNull(1)?.isDigit() == true -> REGISTRY_UNITED_STATES
            reg.startsWith("C-F") || reg.startsWith("C-G") || reg.startsWith("C-I") || reg.startsWith("CF") -> RegistryCountry("CA", "Canada")
            reg.startsWith("G-") -> RegistryCountry("GB", "United Kingdom")
            reg.startsWith("D-") -> RegistryCountry("DE", "Germany")
            reg.startsWith("F-") -> RegistryCountry("FR", "France")
            reg.startsWith("I-") -> RegistryCountry("IT", "Italy")
            reg.startsWith("EC-") -> RegistryCountry("ES", "Spain")
            reg.startsWith("PH-") -> RegistryCountry("NL", "Netherlands")
            reg.startsWith("HB-") -> RegistryCountry("CH", "Switzerland")
            reg.startsWith("OE-") -> RegistryCountry("AT", "Austria")
            reg.startsWith("OO-") -> RegistryCountry("BE", "Belgium")
            reg.startsWith("SE-") -> RegistryCountry("SE", "Sweden")
            reg.startsWith("LN-") -> RegistryCountry("NO", "Norway")
            reg.startsWith("OY-") -> RegistryCountry("DK", "Denmark")
            reg.startsWith("OH-") -> RegistryCountry("FI", "Finland")
            reg.startsWith("EI-") -> RegistryCountry("IE", "Ireland")
            reg.startsWith("CS-") -> RegistryCountry("PT", "Portugal")
            reg.startsWith("SP-") -> RegistryCountry("PL", "Poland")
            reg.startsWith("OK-") -> RegistryCountry("CZ", "Czechia")
            reg.startsWith("HA-") -> RegistryCountry("HU", "Hungary")
            reg.startsWith("SX-") -> RegistryCountry("GR", "Greece")
            reg.startsWith("TC-") -> RegistryCountry("TR", "Turkiye")
            reg.startsWith("RA-") || reg.startsWith("RF-") -> RegistryCountry("RU", "Russia")
            reg.startsWith("B-") -> RegistryCountry("CN", "China")
            reg.startsWith("JA") -> RegistryCountry("JP", "Japan")
            reg.startsWith("HL") -> RegistryCountry("KR", "South Korea")
            reg.startsWith("VH-") -> RegistryCountry("AU", "Australia")
            reg.startsWith("ZK-") -> RegistryCountry("NZ", "New Zealand")
            reg.startsWith("PT") || reg.startsWith("PR") || reg.startsWith("PP") || reg.startsWith("PS") -> RegistryCountry("BR", "Brazil")
            reg.startsWith("LV-") -> RegistryCountry("AR", "Argentina")
            reg.startsWith("XA") || reg.startsWith("XB") || reg.startsWith("XC") -> RegistryCountry("MX", "Mexico")
            reg.startsWith("HK-") -> RegistryCountry("CO", "Colombia")
            reg.startsWith("CC-") -> RegistryCountry("CL", "Chile")
            reg.startsWith("9M-") -> RegistryCountry("MY", "Malaysia")
            reg.startsWith("HS-") -> RegistryCountry("TH", "Thailand")
            reg.startsWith("VT-") -> RegistryCountry("IN", "India")
            reg.startsWith("RP-") -> RegistryCountry("PH", "Philippines")
            reg.startsWith("PK-") -> RegistryCountry("ID", "Indonesia")
            reg.startsWith("9V-") -> RegistryCountry("SG", "Singapore")
            reg.startsWith("A6-") -> RegistryCountry("AE", "United Arab Emirates")
            reg.startsWith("A7-") -> RegistryCountry("QA", "Qatar")
            reg.startsWith("HZ-") -> RegistryCountry("SA", "Saudi Arabia")
            reg.startsWith("ZS") || reg.startsWith("ZU") || reg.startsWith("ZT") -> RegistryCountry("ZA", "South Africa")
            else -> null
        }
    }

    private fun country_from_icao24(icao24: String): RegistryCountry? {
        val value = icao24.trim().trimStart('~').toIntOrNull(16) ?: return null
        return ICAO_REGISTRY_RANGES.firstOrNull { value in it.start..it.end }?.country
    }

    private val REGISTRY_UNITED_STATES = RegistryCountry("US", "United States")

    private val ICAO_REGISTRY_RANGES = listOf(
        IcaoRegistryRange(0xA00000, 0xADF7C7, REGISTRY_UNITED_STATES),
        IcaoRegistryRange(0xC00000, 0xC3FFFF, RegistryCountry("CA", "Canada")),
        IcaoRegistryRange(0x400000, 0x43FFFF, RegistryCountry("GB", "United Kingdom")),
        IcaoRegistryRange(0x3C0000, 0x3FFFFF, RegistryCountry("DE", "Germany")),
        IcaoRegistryRange(0x380000, 0x3BFFFF, RegistryCountry("FR", "France")),
        IcaoRegistryRange(0x300000, 0x33FFFF, RegistryCountry("IT", "Italy")),
        IcaoRegistryRange(0x340000, 0x37FFFF, RegistryCountry("ES", "Spain")),
        IcaoRegistryRange(0x440000, 0x447FFF, RegistryCountry("AT", "Austria")),
        IcaoRegistryRange(0x448000, 0x44FFFF, RegistryCountry("BE", "Belgium")),
        IcaoRegistryRange(0x458000, 0x45FFFF, RegistryCountry("DK", "Denmark")),
        IcaoRegistryRange(0x460000, 0x467FFF, RegistryCountry("FI", "Finland")),
        IcaoRegistryRange(0x468000, 0x46FFFF, RegistryCountry("GR", "Greece")),
        IcaoRegistryRange(0x480000, 0x487FFF, RegistryCountry("NL", "Netherlands")),
        IcaoRegistryRange(0x4B0000, 0x4B7FFF, RegistryCountry("CH", "Switzerland")),
        IcaoRegistryRange(0x4B8000, 0x4BFFFF, RegistryCountry("TR", "Turkiye")),
        IcaoRegistryRange(0x7C0000, 0x7FFFFF, RegistryCountry("AU", "Australia")),
        IcaoRegistryRange(0x840000, 0x87FFFF, RegistryCountry("JP", "Japan"))
    )
}
