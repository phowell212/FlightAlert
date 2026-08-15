@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference


enum class StyleFamily(val stable_id: String) {
    REGION_COUNTRY("region.country"), REGION_FIRST_ORDER("region.first_order"),
    REGION_LOCAL("region.local"), PLACE_MAJOR("place.major"), PLACE("place"),
    PLACE_LOCAL("place.local"), ISLAND("island"), WATER_OCEAN("water.ocean"),
    WATER_BAY("water.bay"), WATER_LAKE("water.lake"), RIVER("water.river"),
    STREAM("water.stream"), CANAL("water.canal"),
    WATERCOURSE_UNSPECIFIED("water.unspecified_course"), PROTECTED_LAND("land.protected"),
    COASTLINE("outline.coastline"), INTERNATIONAL_BOUNDARY("outline.international"),
    STATE_PROVINCE_BOUNDARY("outline.state_province"),
    COUNTY_LOCAL_BOUNDARY("outline.county_local"), OTHER_ADMIN_BOUNDARY("outline.other_admin"),
    PROTECTED_AREA_OUTLINE("outline.protected_area"),
    WATERSHED_WATER_BOUNDARY("outline.water_boundary"), OTHER_SOURCED_OUTLINE("outline.other")
}