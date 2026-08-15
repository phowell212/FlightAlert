package com.flightalert.map.reference.labels


internal data class ReferenceLineLabelPlacementOption(
    val placement: ReferencePathLabelPlacement,
    val occurrenceId: ReferenceLabelOccurrenceId,
    val placementRank: Int,
)