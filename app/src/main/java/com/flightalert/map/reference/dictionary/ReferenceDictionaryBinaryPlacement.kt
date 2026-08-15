@file:Suppress(
    "ArrayInDataClass",
    "ConvertTwoComparisonsToRangeCheck",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.reference.ProminenceTier


internal data class ReferenceDictionaryBinaryPlacement(
    val labelCandidateId: ULong,
    val textSourceFieldId: ULong,
    val prominenceTier: ProminenceTier,
    val completeGeometryMeasureBucket: Int,
    val spacingPx: Int,
    val maximumAngleDegrees: Int,
    val collisionGroup: ULong,
    val semanticPriority: Int,
    val avoidEdges: Boolean,
    val keepUpright: Boolean,
    val activeBandLimit: Int,
)