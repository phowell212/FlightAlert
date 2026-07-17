package com.flightalert.map

internal data class ReferenceLineLabelPlacementOption(
    val placement: ReferencePathLabelPlacement,
    val occurrenceId: ReferenceLabelOccurrenceId,
    val placementRank: Int,
)

internal object ReferenceLineLabelPlacementAdapter {
    fun fromPlanner(
        placements: List<ReferencePathLabelPlacement>,
    ): List<ReferenceLineLabelPlacementOption> = placements.mapIndexed { index, placement ->
        ReferenceLineLabelPlacementOption(
            placement = placement,
            occurrenceId = ReferenceLabelOccurrenceId(
                placement.candidateId,
                placement.repeatOrdinal,
            ),
            placementRank = index,
        )
    }
}
