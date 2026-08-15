@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import com.flightalert.map.reference.ProminenceTier


internal data class ReferencePathLabelRequest(
    val parts: List<List<ReferencePathLabelPoint>>,
    val viewport: ReferenceScreenRect,
    val shapedAdvancePx: Double,
    val endClearancePx: Double,
    val edgeClearancePx: Double,
    val maxBendDegrees: Double,
    val candidateId: ULong,
    val repeatSpacingPx: Int,
    val prominenceTier: ProminenceTier,
    val staticAvoidRects: List<ReferenceScreenRect> = emptyList(),
    val maximumTangentOffsetPx: Double = 0.0,
    val maximumTangentSourceDistancePx: Double = 0.0,
    val maximumCurvedSourceDistancePx: Double = 0.0,
    val allowCurvedPlacement: Boolean = true,
    val allowTangentFallback: Boolean = true,
)