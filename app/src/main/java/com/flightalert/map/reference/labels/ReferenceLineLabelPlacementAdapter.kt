package com.flightalert.map.reference.labels


    internal fun fromPlanner(
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
