@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

enum class SemanticSubtype(val stable_id: Int) {
    COUNTRY_TERRITORY(100), FIRST_ORDER_REGION(110), SECOND_LOCAL_REGION(120),
    CAPITAL_MAJOR_CITY(200), CITY_TOWN(210), LOCAL_PLACE(220), ISLAND_ISLET(230),
    OCEAN_SEA(300), BAY_SOUND(310), LAKE_RESERVOIR(320), RIVER(330),
    STREAM_CREEK(340), CANAL_CHANNEL(350), UNSPECIFIED_WATERCOURSE(360),
    PROTECTED_LAND(400), COASTLINE(500), INTERNATIONAL_BOUNDARY(510),
    STATE_PROVINCE_BOUNDARY(520), COUNTY_LOCAL_BOUNDARY(530), OTHER_ADMIN_BOUNDARY(540),
    PROTECTED_AREA_OUTLINE(550), WATERSHED_WATER_BOUNDARY(560), OTHER_SOURCED_OUTLINE(570);

    val is_label: Boolean get() = stable_id < 500

    companion object {
        private val by_id = entries.associateBy { it.stable_id }
        fun from_stable_id(stable_id: Int): SemanticSubtype? = by_id[stable_id]
    }
}