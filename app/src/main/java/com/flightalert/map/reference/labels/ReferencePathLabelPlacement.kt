@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels


internal enum class ReferencePathLabelPlacementMode {
    CURVED,
    TANGENT_WIDE,
}

internal data class ReferencePathSourcePosition(
    val partIndex: Int,
    val segmentIndex: Int,
    val segmentFraction: Double,
) {
    init {
        require(partIndex >= 0 && segmentIndex >= 0) { "source indices must be nonnegative" }
        require(segmentFraction.isFinite() && segmentFraction in 0.0..1.0) {
            "source segment fraction must be inside [0, 1]"
        }
    }
}

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

internal data class ReferenceLineLabelPlacementOption(
    val placement: ReferencePathLabelPlacement,
    val occurrenceId: ReferenceLabelOccurrenceId,
    val placementRank: Int,
)
