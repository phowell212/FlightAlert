package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLineLabelPlacementAdapterTest {
    @Test
    fun preservesEveryRankedPlannerPlacementAndTypedOccurrenceIdentity() {
        val planned = ReferencePathLabelPlanner.plan(
            ReferencePathLabelRequest(
                parts = listOf(
                    listOf(
                        ReferencePathLabelPoint(0.0, 100.0),
                        ReferencePathLabelPoint(1_200.0, 100.0),
                    ),
                ),
                viewport = ReferenceScreenRect(0.0, 0.0, 1_200.0, 200.0),
                shapedAdvancePx = 80.0,
                endClearancePx = 10.0,
                edgeClearancePx = 5.0,
                maxBendDegrees = 30.0,
                candidateId = 50uL,
                repeatSpacingPx = 200,
                prominenceTier = ProminenceTier.LOCAL,
            ),
        )

        assertTrue(planned.size > 1)

        val adapted = ReferenceLineLabelPlacementAdapter.fromPlanner(planned)

        assertEquals(planned.size, adapted.size)
        assertEquals(planned, adapted.map { it.placement })
        adapted.forEachIndexed { index, item ->
            assertEquals(index, item.placementRank)
            assertEquals(
                ReferenceLabelOccurrenceId(
                    item.placement.candidateId,
                    item.placement.repeatOrdinal,
                ),
                item.occurrenceId,
            )
        }
    }
}
