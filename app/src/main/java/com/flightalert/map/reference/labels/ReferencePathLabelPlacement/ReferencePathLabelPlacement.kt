@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal data class ReferencePathLabelPlacement(
    val mode: ReferencePathLabelPlacementMode,
    val candidateId: ULong,
    val presentationPath: List<ReferencePathLabelPoint>,
    val anchor: ReferencePathLabelPoint,
    val sourcePosition: ReferencePathSourcePosition,
    val tangentDegrees: Double,
    val bendCentiDegrees: Int,
    val minimumClearanceQ8Px: Long,
    val centerDistanceQ8Px: Long,
    val repeatOrdinal: Long,
    val normalOffsetPx: Double,
)