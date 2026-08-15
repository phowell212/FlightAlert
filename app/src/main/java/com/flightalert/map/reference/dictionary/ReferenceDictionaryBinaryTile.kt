@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary


internal data class ReferenceDictionaryBinaryTile(
    val coordinate: ReferenceDictionaryTileCoordinate,
    val records: List<ReferenceDictionaryBinaryRecord>,
)