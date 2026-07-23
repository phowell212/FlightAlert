package com.flightalert.map

private const val LOW_ZOOM_COUNTRY_LIMIT = 4.0
private const val LOW_ZOOM_COUNTRY_RANK_RANGE = 0x100

internal const val UNKNOWN_LOW_ZOOM_COUNTRY_RANK = 0xff

// Generated from the current v5 country feature IDs and their exact OSM admin-2
// relation evidence. Population is preferred; missing population uses ten people
// per compact boundary-bounds square kilometer. Ranking happens here once during
// tile decoding, never while laying out or drawing a frame.
internal fun reference_low_zoom_country_rank(feature_id: ULong): Int =
    when (feature_id) {
        0x00074fc887d45168uL -> 164 // ME Montenegro
        0x025eac8a73a6e382uL -> 9 // MX Mxico
        0x0379e60b11072fb7uL -> 141 // LS Lesotho
        0x04a7d4e459ba2ec9uL -> 4 // PK Pakistan
        0x05b1f957e097b3d0uL -> 26 // ZA South Africa
        0x08161e4130cc2982uL -> 174 // BZ Belize
        0x09115fa138265647uL -> 83 // TN Tunisia
        0x0a58d43cfc545ba3uL -> 156 // TL Timor-Leste
        0x0a5ca3ff21a5ab75uL -> 139 // OM Oman
        0x0f9b32f81548df42uL -> 5 // BR Brasil
        0x11e4b9e8aeecb3bfuL -> 57 // NL Nederland
        0x1340b99246eca25duL -> 40 // SA Saudi Arabia
        0x13c3d16245c70f6auL -> 192 // DM Dominica
        0x158a150546d9acacuL -> 151 // SZ Eswatini
        0x16c0e2bd31baa457uL -> 215 // TK Tokelau
        0x193236d546f7b40euL -> 106 // GR Greece
        0x1ba15fa977b784ebuL -> 191 // BM Bermuda
        0x1e1e96db6c298026uL -> 42 // YE Yemen
        0x1e636f5eb566a795uL -> 2 // US United States
        0x1f5aa2a420d29860uL -> 118 // SL Sierra Leone
        0x203918f77ecbf2b9uL -> 89 // SE Sverige
        0x2058c1aec52588cbuL -> 214 // FK Falkland Islands
        0x20b840e85fc05759uL -> 202 // LI Liechtenstein
        0x2286f301dafcb2aauL -> 105 // LA Laos
        0x22c8dcdef12a61a3uL -> 148 // BW Botswana
        0x22cd7076ac9dce35uL -> 208 // CK Kki irani
        0x239322399dc491acuL -> 136 // AM Armenia
        0x239d27f02e10af9euL -> 205 // GI Gibraltar
        0x280b48a544664130uL -> 96 // HN Honduras
        0x28e8b77a71c0116euL -> 45 // IQ Iraq
        0x293615a10b3d7553uL -> 47 // AU Australia
        0x29a704649feeb75cuL -> 114 // DK Danmark
        0x29bbdc77f9c396ecuL -> 86 // TJ Tajikistan
        0x29ccb7b1f20a2efcuL -> 177 // BT Bhutan
        0x29d5efc311f1246duL -> 41 // GH Ghana
        0x2aaa772cdb9b562buL -> 134 // KH Cambodia
        0x2ade38355e724c0fuL -> 199 // KN Saint Kitts and Nevis
        0x2b072e374fb6c200uL -> 142 // MK North Macedonia
        0x2c01125bf84b25fbuL -> 46 // MY Malaysia
        0x2dc326988e884305uL -> 81 // PT Portugal
        0x2ddb81e7f6321aafuL -> 58 // NE Niger
        0x2dec045d21e160c1uL -> 51 // CM Cameroun
        0x2e865e8d18ac3196uL -> 100 // RS Serbia
        0x2fd5fd9bc7af791euL -> 162 // GY Guyana
        0x307954bf923fb8cfuL -> 193 // AG Antigua and Barbuda
        0x315968924ababdf9uL -> 21 // GB United Kingdom
        0x3186373259219bb3uL -> 94 // AT sterreich
        0x323a86e7c91b8058uL -> 169 // SR Suriname
        0x341f906fe22c240cuL -> 103 // NI Nicaragua
        0x36d0d678351e09eduL -> 97 // IL Israel
        0x3707217f3edff419uL -> 36 // MA Morocco
        0x37c32387e7ad3a8cuL -> 38 // UG Uganda
        0x3890f7217130403auL -> 49 // KP North Korea
        0x398ae183d47bd51buL -> 188 // JE Jersey
        0x39c3338460aced5auL -> 182 // BN Brunei
        0x3b3c74c102fcba0cuL -> 88 // SS South Sudan
        0x3bce81911461e5bfuL -> 32 // SD Sudan
        0x3bd3a258fd542a94uL -> 50 // TW Taiwan
        0x3c0bcadddd100648uL -> 204 // SM San Marino
        0x3d227723116f2a3euL -> 43 // NP Nepal
        0x41553fc507293e9euL -> 158 // PA Panama
        0x41cbf2cf59ed53d9uL -> 153 // EE Eesti
        0x4261a46ab9f0a088uL -> 13 // EG Egypt
        0x44da24902d882a5cuL -> 75 // BO Bolivia
        0x45c9829077f6cbcfuL -> 209 // AI Anguilla
        0x46d164ba7cb778dduL -> 212 // MS Montserrat
        0x473dda4aecf0c544uL -> 207 // PW Belau
        0x479049cdcc9d62acuL -> 138 // AL Shqipria
        0x4837c008733fa3feuL -> 217 // FJ Fiji
        0x489fef7ecb549f53uL -> 85 // HU Magyarorszg
        0x48d8de8b1486659fuL -> 64 // MW Malawi
        0x4a1ca8d70fa6792fuL -> 14 // VN Vit Nam
        0x4bc271e3b5ad28c6uL -> 206 // VG British Virgin Islands
        0x4bf3f51ab49d214duL -> 92 // AZ Azrbaycan
        0x4c2af6735be3c88cuL -> 76 // BE Belgi / Belgique / Belgien
        0x4deffb4b6f9a5e83uL -> 39 // AF Afghanistan
        0x4e1fe11cc2e06c66uL -> 195 // GG Guernsey
        0x501c0795a024b20euL -> 110 // LY Libya
        0x51448bec3436b3a3uL -> 135 // UY Uruguay
        0x527e27a5d5f59245uL -> 10 // JP Japan
        0x541b8bf92ed19353uL -> 175 // PN Pitcairn
        0x54a688f0d7587b60uL -> 197 // FO Froyar
        0x55127f734d184e82uL -> 210 // NR Naoero
        0x567b716bfe825a50uL -> 160 // CY Cyprus
        0x581b7a58bc9190a9uL -> 167 // SB Solomon Islands
        0x590ce5fc276b9235uL -> 80 // CZ esko
        0x59256685e1ce8c61uL -> 73 // CU Cuba
        0x5990fe590b6f3850uL -> 19 // CD Rpublique dmocratique du Congo
        0x5a422863399c3f15uL -> 6 // BD Bangladesh
        0x5b1fefcc14a56c85uL -> 121 // TM Trkmenistan
        0x5c0760f2ff5687bduL -> 35 // CA Canada
        0x5ccbe4174103761euL -> 120 // IE Ireland
        0x5f0edbe9a869e21auL -> 128 // HR Hrvatska
        0x5f60c0889f932ccauL -> 203 // MC Monaco
        0x62321a8cdeec38b5uL -> 37 // UZ O'zbekiston
        0x6267ec67942300fauL -> 126 // CI Cte d'Ivoire
        0x63112c4f285a75fbuL -> 98 // CG Rpublique du Congo
        0x631c093a5c9b73feuL -> 99 // PY Paraguay
        0x68126845a4bec241uL -> 91 // BY Belarus
        0x694d08c30602a017uL -> 82 // TD Chad
        0x6a424c1251c4a7f2uL -> 107 // SV El Salvador
        0x6cbf1966a023ecebuL -> 115 // SK Slovensko
        0x6cc080a61db5fd98uL -> 24 // MM Myanmar
        0x6e24ead1d0e05782uL -> 190 // IM Isle of Man
        0x6ea312f48b725b41uL -> 117 // LB Lebanon
        0x6ed1b3808d8deba0uL -> 170 // MT Malta
        0x710c659c1a9f0803uL -> 112 // NO Norge
        0x726044150e0d16eauL -> 163 // HT Haiti
        0x781740ef1e624663uL -> 78 // DO Repblica Dominicana
        0x785df84bc568df43uL -> 109 // SC Seychelles
        0x78745d104eb2d239uL -> 137 // JM Jamaica
        0x79a0d28b926175aauL -> 3 // ID Indonesia
        0x7a8f198c17adf4b7uL -> 187 // TO Tonga
        0x7aa8b9f1959074c2uL -> 172 // IS sland
        0x7c9143ce5a3ab211uL -> 149 // GA Gabon
        0x7ea031e5e3e7e71duL -> 176 // VU Vanuatu
        0x7fa8bc2d38c238c3uL -> 154 // MU Mauritius / Maurice
        0x81013fc916cfa8b0uL -> 66 // EC Ecuador
        0x81dcbc7e8fe72a8duL -> 20 // FR France
        0x83ec6c2c97ec75fcuL -> 211 // TV Tuvalu
        0x845fe02824dc2811uL -> 18 // TH Thailand
        0x847dd481c6231d81uL -> 44 // PE Per
        0x8590b6f98b2afd8buL -> 124 // SG Singapore
        0x86123ce021bde323uL -> 213 // IO British Indian Ocean Territory
        0x877b7a466faaa599uL -> 186 // GD Grenada
        0x879a7181a008eda6uL -> 61 // ZM Zambia
        0x8857a23707b9b0afuL -> 179 // XK Kosov / Kosovo
        0x8c0dfc000e6d49b8uL -> 116 // FI Suomi / Finland
        0x90a1869307b3b6b2uL -> 101 // MG Madagascar
        0x922e9d3086e02119uL -> 155 // BH Bahrain
        0x9456cfb6b6a602dbuL -> 194 // GL Kalaallit Nunaat
        0x94cd1fce9020910buL -> 133 // KW Kuwait
        0x94df4573aebc2dbauL -> 119 // NZ New Zealand / Aotearoa
        0x95c8f171807ad837uL -> 74 // RW Rwanda
        0x97973ac282802f6buL -> 90 // CH Schweiz/Suisse/Svizzera/Svizra
        0x9923028eca9669aauL -> 111 // BS Bahamas
        0x99bb3aaca2439ea4uL -> 196 // KY Cayman Islands
        0x9c3c4ce4f4f84ef1uL -> 145 // LV Latvija
        0x9c7e875abfb9556fuL -> 125 // AE United Arab Emirates
        0x9d0e291d8fef1a5euL -> 8 // RU Russia
        0x9f114909b5a5c754uL -> 84 // GN Guine
        0x9f42ef94d89bdf2euL -> 65 // SO Somalia
        0x9fc85671d0576e4buL -> 17 // IR Iran
        0xa0e04b9bb3171f6buL -> 31 // DZ Algrie
        0xa238073cd63dcb8auL -> 28 // ES Espaa
        0xa28290580eef03eeuL -> 1 // CN China
        0xa2baeb646088607duL -> 70 // SN Sngal
        0xa2e98f3c66cd83a0uL -> 104 // JO Jordan
        0xa34406d1400dfacfuL -> 29 // CO Colombia
        0xa42988cafbf789e8uL -> 161 // LK Sri Lanka
        0xa51f11aeb7ada86duL -> 171 // DJ Djibouti
        0xa6e27ba5f9ff25c8uL -> 173 // MV Maldives
        0xa84f7709d0d99d62uL -> 54 // CL Chile
        0xa9375d353a95f53euL -> 152 // TT Trinidad and Tobago
        0xa9c3a5542db78f5auL -> 147 // QA Qatar
        0xabc8a799c91d2822uL -> 25 // KR South Korea
        0xad59dbf1c7997373uL -> 165 // LU Ltzebuerg
        0xb0d88a63e2ea51a4uL -> 146 // GM Gambia
        0xb12f4d406b446f07uL -> 180 // ST So Tom e Prncipe
        0xb1823f44fed2a6c4uL -> 48 // VE Venezuela
        0xb22d31bdaf6695e8uL -> 150 // GW Guin-Bissau
        0xb32fefd16512a06fuL -> 67 // MN Mongolia
        0xb7b019bda61b8801uL -> 27 // AR Argentina
        0xb84dfbceb177e870uL -> 123 // GS South Georgia and South Sandwich Islands
        0xb89be6b86d8701a6uL -> 178 // BB Barbados
        0xb8c41e2b273415bauL -> 15 // TR Trkiye
        0xb9cd88b2a8494e47uL -> 144 // GQ Guinea Ecuatorial
        0xb9f84c170ad05425uL -> 60 // MR Mauritania
        0xba0a431a66c430fduL -> 62 // PG Papua New Guinea
        0xbac5e35d7c8162d8uL -> 131 // ER Eritrea
        0xbbc20ee5e737ec8auL -> 140 // NA Namibia
        0xbc02331649b5cbd7uL -> 55 // GT Guatemala
        0xbde773f0c0928e2auL -> 95 // SH Saint Helena, Ascension and Tristan da Cunha
        0xbf711262c519e90auL -> 0 // IN India
        0xc002dda49cc97c5fuL -> 23 // IT Italia
        0xc032f2894801798cuL -> 216 // VA Civitas Vaticana - Citt del Vaticano
        0xc1203a268df17108uL -> 184 // KI Kiribati
        0xc26a97451f27d183uL -> 166 // KM Comoros
        0xc46a279fc734e0c1uL -> 130 // LT Lietuva
        0xc4b0394813054cb6uL -> 87 // CF Centrafrique
        0xc5c94823c7a2b43auL -> 79 // BI Burundi
        0xc6158b6dc6c433f1uL -> 68 // MH Marshall Islands
        0xc63e63befba39445uL -> 11 // ET Ethiopia
        0xc731830fb2095ae1uL -> 159 // BA Bosna i Hercegovina
        0xc7a7d527292df743uL -> 189 // AD Andorra
        0xc8ec4577151c2d5auL -> 143 // SI Slovenija
        0xcb83dee09d3f8cbduL -> 69 // MZ Mozambique
        0xcd7e00ff8bae89ccuL -> 200 // TC Turks and Caicos Islands
        0xce40f99c8d09e335uL -> 63 // ML Mali
        0xd174ec2bb7ce9526uL -> 30 // UA Ukraine
        0xd3073acbd0d3d560uL -> 33 // KE Kenya
        0xd4fa3d80c2af8b1duL -> 7 // NG Nigeria
        0xd6f6114eef890c9auL -> 168 // CV Cabo Verde
        0xd6f772b47ceba684uL -> 53 // RO Romnia
        0xd987bccf805f5df1uL -> 122 // CR Costa Rica
        0xd9f3e3bad418d995uL -> 52 // KZ Kazakhstan
        0xdb69607abf418946uL -> 157 // BG Bulgaria
        0xdd5178830e6d01dcuL -> 72 // ZW Zimbabwe
        0xddd54975639d78efuL -> 59 // BF Burkina Faso
        0xdf9a318fe781edaduL -> 56 // SY Syria
        0xe01bf6896774f4fauL -> 113 // KG Kyrgyzstan
        0xe5082d029dfbe2d0uL -> 183 // LC Saint Lucia
        0xe56d2f97d718df21uL -> 12 // PH Philippines
        0xe5c1fbc8efd8e04fuL -> 181 // WS Smoa
        0xe658ff1fec590056uL -> 34 // PL Polska
        0xe66b9a0b02da8ae5uL -> 185 // VC Saint Vincent and the Grenadines
        0xe9a6649ebfcfd253uL -> 129 // GE Georgia
        0xed291cb18ac61350uL -> 102 // TG Togo
        0xed439d1c9bace52duL -> 127 // MD Moldova
        0xedb556f42f52e10fuL -> 71 // AO Angola
        0xf2fe0f624fd2949duL -> 132 // LR Liberia
        0xf4108f91183f0aebuL -> 201 // NU Niue
        0xf5a8088ad09dfffeuL -> 77 // FM Federated States of Micronesia
        0xf7633e378d9c1da3uL -> 108 // -- Sahrawi Arab Democratic Republic
        0xf87a4864d2f2b653uL -> 93 // BJ Bnin
        0xfab2e73040c83c86uL -> 16 // DE Deutschland
        0xfba3d077f7d6ef9euL -> 22 // TZ Tanzania
        else -> UNKNOWN_LOW_ZOOM_COUNTRY_RANK
    }

internal fun reference_low_zoom_label_priority(
    semantic_priority: Int,
    low_zoom_country_rank: Int,
    viewport_zoom: Double,
): Int {
    if (viewport_zoom >= LOW_ZOOM_COUNTRY_LIMIT) return semantic_priority
    return semantic_priority * LOW_ZOOM_COUNTRY_RANK_RANGE + low_zoom_country_rank
}
