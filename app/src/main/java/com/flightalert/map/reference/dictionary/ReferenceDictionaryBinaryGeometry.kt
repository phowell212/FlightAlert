@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary


internal data class ReferenceDictionaryBinaryGeometry(
    val kind: ReferenceDictionaryBinaryGeometryKind,
    val partOffsets: IntArray,
    val localCoordinates: FloatArray,
    val localBounds: FloatArray,
)