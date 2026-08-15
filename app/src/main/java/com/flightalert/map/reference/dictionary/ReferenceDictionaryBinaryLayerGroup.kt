@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary


internal enum class ReferenceDictionaryBinaryLayerGroup(val stableCode: Int) {
    PLACES(1),
    WATER(2),
    REGIONS(3),
    PUBLIC_LANDS(4),
    TRANSPORTATION(5),
    CONTEXT(6),
}