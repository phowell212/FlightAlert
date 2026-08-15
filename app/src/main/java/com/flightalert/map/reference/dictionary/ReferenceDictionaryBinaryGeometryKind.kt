@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary


internal enum class ReferenceDictionaryBinaryGeometryKind(val stableCode: Int) {
    POINT(1),
    PATH(2),
    POLYGON(3),
}