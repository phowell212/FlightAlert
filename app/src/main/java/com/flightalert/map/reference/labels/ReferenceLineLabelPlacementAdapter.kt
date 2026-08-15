package com.flightalert.map.reference.labels


internal object ReferenceLineLabelPlacementAdapter {
    fun fromPlanner(
        placements: List<ReferencePathLabelPlacement>,
        renderedWorldCopy: Long,
    ): List<ReferenceLineLabelPlacementOption> = placements.mapIndexed { index, placement ->
        ReferenceLineLabelPlacementOption(
            placement = placement,
            occurrenceId = ReferenceLabelOccurrenceId(
                placement.candidateId,
                placement.repeatOrdinal,
                renderedWorldCopy,
            ),
            placementRank = index,
        )
    }
}

