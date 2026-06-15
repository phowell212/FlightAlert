package com.flightalert.ui.map.traffic

import java.util.Locale

data class RegistryCountry(val iso_code: String, val name: String, val flag_code: String? = iso_code) {
    val label: String
        get() = flag_code?.let { "${flag_emoji(it)} $name" } ?: name

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
        val reg = registration?.trim()?.uppercase(Locale.US) ?: return null
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
        val hex = icao24.trim()
        if (hex.startsWith("~")) return null
        val value = hex.toIntOrNull(16) ?: return null
        return ICAO_REGISTRY_RANGES.firstOrNull { value in it.start..it.end }?.country
    }

    private val REGISTRY_UNITED_STATES = country("US", "United States")

    private fun country(iso_code: String, name: String, flag_code: String? = iso_code): RegistryCountry {
        return RegistryCountry(iso_code, name, flag_code)
    }

    private fun icao_range(start: Int, end: Int, iso_code: String, name: String, flag_code: String? = iso_code): IcaoRegistryRange {
        return IcaoRegistryRange(start, end, country(iso_code, name, flag_code))
    }

    // Reserved and unallocated ICAO blocks are intentionally absent so unresolved addresses stay honest.
    private val ICAO_REGISTRY_RANGES = listOf(
        icao_range(0x004000, 0x0043FF, "ZW", "Zimbabwe"),
        icao_range(0x006000, 0x006FFF, "MZ", "Mozambique"),
        icao_range(0x008000, 0x00FFFF, "ZA", "South Africa"),
        icao_range(0x010000, 0x017FFF, "EG", "Egypt"),
        icao_range(0x018000, 0x01FFFF, "LY", "Libya"),
        icao_range(0x020000, 0x027FFF, "MA", "Morocco"),
        icao_range(0x028000, 0x02FFFF, "TN", "Tunisia"),
        icao_range(0x030000, 0x0303FF, "BW", "Botswana"),
        icao_range(0x032000, 0x032FFF, "BI", "Burundi"),
        icao_range(0x034000, 0x034FFF, "CM", "Cameroon"),
        icao_range(0x035000, 0x0353FF, "KM", "Comoros"),
        icao_range(0x036000, 0x036FFF, "CG", "Congo"),
        icao_range(0x038000, 0x038FFF, "CI", "Cote d'Ivoire"),
        icao_range(0x03E000, 0x03EFFF, "GA", "Gabon"),
        icao_range(0x040000, 0x040FFF, "ET", "Ethiopia"),
        icao_range(0x042000, 0x042FFF, "GQ", "Equatorial Guinea"),
        icao_range(0x044000, 0x044FFF, "GH", "Ghana"),
        icao_range(0x046000, 0x046FFF, "GN", "Guinea"),
        icao_range(0x048000, 0x0483FF, "GW", "Guinea-Bissau"),
        icao_range(0x04A000, 0x04A3FF, "LS", "Lesotho"),
        icao_range(0x04C000, 0x04CFFF, "KE", "Kenya"),
        icao_range(0x050000, 0x050FFF, "LR", "Liberia"),
        icao_range(0x054000, 0x054FFF, "MG", "Madagascar"),
        icao_range(0x058000, 0x058FFF, "MW", "Malawi"),
        icao_range(0x05A000, 0x05A3FF, "MV", "Maldives"),
        icao_range(0x05C000, 0x05CFFF, "ML", "Mali"),
        icao_range(0x05E000, 0x05E3FF, "MR", "Mauritania"),
        icao_range(0x060000, 0x0603FF, "MU", "Mauritius"),
        icao_range(0x062000, 0x062FFF, "NE", "Niger"),
        icao_range(0x064000, 0x064FFF, "NG", "Nigeria"),
        icao_range(0x068000, 0x068FFF, "UG", "Uganda"),
        icao_range(0x06A000, 0x06A3FF, "QA", "Qatar"),
        icao_range(0x06C000, 0x06CFFF, "CF", "Central African Republic"),
        icao_range(0x06E000, 0x06EFFF, "RW", "Rwanda"),
        icao_range(0x070000, 0x070FFF, "SN", "Senegal"),
        icao_range(0x074000, 0x0743FF, "SC", "Seychelles"),
        icao_range(0x076000, 0x0763FF, "SL", "Sierra Leone"),
        icao_range(0x078000, 0x078FFF, "SO", "Somalia"),
        icao_range(0x07A000, 0x07A3FF, "SZ", "Eswatini"),
        icao_range(0x07C000, 0x07CFFF, "SD", "Sudan"),
        icao_range(0x080000, 0x080FFF, "TZ", "Tanzania"),
        icao_range(0x084000, 0x084FFF, "TD", "Chad"),
        icao_range(0x088000, 0x088FFF, "TG", "Togo"),
        icao_range(0x08A000, 0x08AFFF, "ZM", "Zambia"),
        icao_range(0x08C000, 0x08CFFF, "CD", "Democratic Republic of the Congo"),
        icao_range(0x090000, 0x090FFF, "AO", "Angola"),
        icao_range(0x094000, 0x0943FF, "BJ", "Benin"),
        icao_range(0x096000, 0x0963FF, "CV", "Cape Verde"),
        icao_range(0x098000, 0x0983FF, "DJ", "Djibouti"),
        icao_range(0x09A000, 0x09AFFF, "GM", "Gambia"),
        icao_range(0x09C000, 0x09CFFF, "BF", "Burkina Faso"),
        icao_range(0x09E000, 0x09E3FF, "ST", "Sao Tome and Principe"),
        icao_range(0x0A0000, 0x0A7FFF, "DZ", "Algeria"),
        icao_range(0x0A8000, 0x0A8FFF, "BS", "Bahamas"),
        icao_range(0x0AA000, 0x0AA3FF, "BB", "Barbados"),
        icao_range(0x0AB000, 0x0AB3FF, "BZ", "Belize"),
        icao_range(0x0AC000, 0x0ACFFF, "CO", "Colombia"),
        icao_range(0x0AE000, 0x0AEFFF, "CR", "Costa Rica"),
        icao_range(0x0B0000, 0x0B0FFF, "CU", "Cuba"),
        icao_range(0x0B2000, 0x0B2FFF, "SV", "El Salvador"),
        icao_range(0x0B4000, 0x0B4FFF, "GT", "Guatemala"),
        icao_range(0x0B6000, 0x0B6FFF, "GY", "Guyana"),
        icao_range(0x0B8000, 0x0B8FFF, "HT", "Haiti"),
        icao_range(0x0BA000, 0x0BAFFF, "HN", "Honduras"),
        icao_range(0x0BC000, 0x0BC3FF, "VC", "Saint Vincent and the Grenadines"),
        icao_range(0x0BE000, 0x0BEFFF, "JM", "Jamaica"),
        icao_range(0x0C0000, 0x0C0FFF, "NI", "Nicaragua"),
        icao_range(0x0C2000, 0x0C2FFF, "PA", "Panama"),
        icao_range(0x0C4000, 0x0C4FFF, "DO", "Dominican Republic"),
        icao_range(0x0C6000, 0x0C6FFF, "TT", "Trinidad and Tobago"),
        icao_range(0x0C8000, 0x0C8FFF, "SR", "Suriname"),
        icao_range(0x0CA000, 0x0CA3FF, "AG", "Antigua and Barbuda"),
        icao_range(0x0CC000, 0x0CC3FF, "GD", "Grenada"),
        icao_range(0x0D0000, 0x0D7FFF, "MX", "Mexico"),
        icao_range(0x0D8000, 0x0DFFFF, "VE", "Venezuela"),
        icao_range(0x100000, 0x1FFFFF, "RU", "Russia"),
        icao_range(0x201000, 0x2013FF, "NA", "Namibia"),
        icao_range(0x202000, 0x2023FF, "ER", "Eritrea"),
        icao_range(0x300000, 0x33FFFF, "IT", "Italy"),
        icao_range(0x340000, 0x37FFFF, "ES", "Spain"),
        icao_range(0x380000, 0x3BFFFF, "FR", "France"),
        icao_range(0x3C0000, 0x3FFFFF, "DE", "Germany"),
        icao_range(0x400000, 0x43FFFF, "GB", "United Kingdom"),
        icao_range(0x440000, 0x447FFF, "AT", "Austria"),
        icao_range(0x448000, 0x44FFFF, "BE", "Belgium"),
        icao_range(0x450000, 0x457FFF, "BG", "Bulgaria"),
        icao_range(0x458000, 0x45FFFF, "DK", "Denmark"),
        icao_range(0x460000, 0x467FFF, "FI", "Finland"),
        icao_range(0x468000, 0x46FFFF, "GR", "Greece"),
        icao_range(0x470000, 0x477FFF, "HU", "Hungary"),
        icao_range(0x478000, 0x47FFFF, "NO", "Norway"),
        icao_range(0x480000, 0x487FFF, "NL", "Netherlands"),
        icao_range(0x488000, 0x48FFFF, "PL", "Poland"),
        icao_range(0x490000, 0x497FFF, "PT", "Portugal"),
        icao_range(0x498000, 0x49FFFF, "CZ", "Czech Republic"),
        icao_range(0x4A0000, 0x4A7FFF, "RO", "Romania"),
        icao_range(0x4A8000, 0x4AFFFF, "SE", "Sweden"),
        icao_range(0x4B0000, 0x4B7FFF, "CH", "Switzerland"),
        icao_range(0x4B8000, 0x4BFFFF, "TR", "Turkiye"),
        icao_range(0x4C0000, 0x4C7FFF, "YU", "Yugoslavia", flag_code = null),
        icao_range(0x4C8000, 0x4C83FF, "CY", "Cyprus"),
        icao_range(0x4CA000, 0x4CAFFF, "IE", "Ireland"),
        icao_range(0x4CC000, 0x4CCFFF, "IS", "Iceland"),
        icao_range(0x4D0000, 0x4D03FF, "LU", "Luxembourg"),
        icao_range(0x4D2000, 0x4D23FF, "MT", "Malta"),
        icao_range(0x4D4000, 0x4D43FF, "MC", "Monaco"),
        icao_range(0x500000, 0x5004FF, "SM", "San Marino"),
        icao_range(0x501000, 0x5013FF, "AL", "Albania"),
        icao_range(0x501C00, 0x501FFF, "HR", "Croatia"),
        icao_range(0x502C00, 0x502FFF, "LV", "Latvia"),
        icao_range(0x503C00, 0x503FFF, "LT", "Lithuania"),
        icao_range(0x504C00, 0x504FFF, "MD", "Moldova"),
        icao_range(0x505C00, 0x505FFF, "SK", "Slovakia"),
        icao_range(0x506C00, 0x506FFF, "SI", "Slovenia"),
        icao_range(0x507C00, 0x507FFF, "UZ", "Uzbekistan"),
        icao_range(0x508000, 0x50FFFF, "UA", "Ukraine"),
        icao_range(0x510000, 0x5103FF, "BY", "Belarus"),
        icao_range(0x511000, 0x5113FF, "EE", "Estonia"),
        icao_range(0x512000, 0x5123FF, "MK", "North Macedonia"),
        icao_range(0x513000, 0x5133FF, "BA", "Bosnia and Herzegovina"),
        icao_range(0x514000, 0x5143FF, "GE", "Georgia"),
        icao_range(0x515000, 0x5153FF, "TJ", "Tajikistan"),
        icao_range(0x600000, 0x6003FF, "AM", "Armenia"),
        icao_range(0x600800, 0x600BFF, "AZ", "Azerbaijan"),
        icao_range(0x601000, 0x6013FF, "KG", "Kyrgyzstan"),
        icao_range(0x601800, 0x601BFF, "TM", "Turkmenistan"),
        icao_range(0x680000, 0x6803FF, "BT", "Bhutan"),
        icao_range(0x681000, 0x6813FF, "FM", "Micronesia"),
        icao_range(0x682000, 0x6823FF, "MN", "Mongolia"),
        icao_range(0x683000, 0x6833FF, "KZ", "Kazakhstan"),
        icao_range(0x684000, 0x6843FF, "PW", "Palau"),
        icao_range(0x700000, 0x700FFF, "AF", "Afghanistan"),
        icao_range(0x702000, 0x702FFF, "BD", "Bangladesh"),
        icao_range(0x704000, 0x704FFF, "MM", "Myanmar"),
        icao_range(0x706000, 0x706FFF, "KW", "Kuwait"),
        icao_range(0x708000, 0x708FFF, "LA", "Laos"),
        icao_range(0x70A000, 0x70AFFF, "NP", "Nepal"),
        icao_range(0x70C000, 0x70C3FF, "OM", "Oman"),
        icao_range(0x70E000, 0x70EFFF, "KH", "Cambodia"),
        icao_range(0x710000, 0x717FFF, "SA", "Saudi Arabia"),
        icao_range(0x718000, 0x71FFFF, "KR", "South Korea"),
        icao_range(0x720000, 0x727FFF, "KP", "North Korea"),
        icao_range(0x728000, 0x72FFFF, "IQ", "Iraq"),
        icao_range(0x730000, 0x737FFF, "IR", "Iran"),
        icao_range(0x738000, 0x73FFFF, "IL", "Israel"),
        icao_range(0x740000, 0x747FFF, "JO", "Jordan"),
        icao_range(0x748000, 0x74FFFF, "LB", "Lebanon"),
        icao_range(0x750000, 0x757FFF, "MY", "Malaysia"),
        icao_range(0x758000, 0x75FFFF, "PH", "Philippines"),
        icao_range(0x760000, 0x767FFF, "PK", "Pakistan"),
        icao_range(0x768000, 0x76FFFF, "SG", "Singapore"),
        icao_range(0x770000, 0x777FFF, "LK", "Sri Lanka"),
        icao_range(0x778000, 0x77FFFF, "SY", "Syria"),
        icao_range(0x780000, 0x7BFFFF, "CN", "China"),
        icao_range(0x7C0000, 0x7FFFFF, "AU", "Australia"),
        icao_range(0x800000, 0x83FFFF, "IN", "India"),
        icao_range(0x840000, 0x87FFFF, "JP", "Japan"),
        icao_range(0x880000, 0x887FFF, "TH", "Thailand"),
        icao_range(0x888000, 0x88FFFF, "VN", "Viet Nam"),
        icao_range(0x890000, 0x890FFF, "YE", "Yemen"),
        icao_range(0x894000, 0x894FFF, "BH", "Bahrain"),
        icao_range(0x895000, 0x8953FF, "BN", "Brunei"),
        icao_range(0x896000, 0x896FFF, "AE", "United Arab Emirates"),
        icao_range(0x897000, 0x8973FF, "SB", "Solomon Islands"),
        icao_range(0x898000, 0x898FFF, "PG", "Papua New Guinea"),
        icao_range(0x899000, 0x8993FF, "TW", "Taiwan"),
        icao_range(0x8A0000, 0x8A7FFF, "ID", "Indonesia"),
        icao_range(0x900000, 0x9003FF, "MH", "Marshall Islands"),
        icao_range(0x901000, 0x9013FF, "CK", "Cook Islands"),
        icao_range(0x902000, 0x9023FF, "WS", "Samoa"),
        IcaoRegistryRange(0xA00000, 0xAFFFFF, REGISTRY_UNITED_STATES),
        icao_range(0xC00000, 0xC3FFFF, "CA", "Canada"),
        icao_range(0xC80000, 0xC87FFF, "NZ", "New Zealand"),
        icao_range(0xC88000, 0xC88FFF, "FJ", "Fiji"),
        icao_range(0xC8A000, 0xC8A3FF, "NR", "Nauru"),
        icao_range(0xC8C000, 0xC8C3FF, "LC", "Saint Lucia"),
        icao_range(0xC8D000, 0xC8D3FF, "TO", "Tonga"),
        icao_range(0xC8E000, 0xC8E3FF, "KI", "Kiribati"),
        icao_range(0xC90000, 0xC903FF, "VU", "Vanuatu"),
        icao_range(0xE00000, 0xE3FFFF, "AR", "Argentina"),
        icao_range(0xE40000, 0xE7FFFF, "BR", "Brazil"),
        icao_range(0xE80000, 0xE80FFF, "CL", "Chile"),
        icao_range(0xE84000, 0xE84FFF, "EC", "Ecuador"),
        icao_range(0xE88000, 0xE88FFF, "PY", "Paraguay"),
        icao_range(0xE8C000, 0xE8CFFF, "PE", "Peru"),
        icao_range(0xE90000, 0xE90FFF, "UY", "Uruguay"),
        icao_range(0xE94000, 0xE94FFF, "BO", "Bolivia")
    )
}
