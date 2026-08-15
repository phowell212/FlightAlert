@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary


internal enum class ReferenceDictionaryBinaryFeatureKind(val stableCode: Int) {
    LABEL(1),
    LINE(2),
    POLYGON_OUTLINE(3),
}