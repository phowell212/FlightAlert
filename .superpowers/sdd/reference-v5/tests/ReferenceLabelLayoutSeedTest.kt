package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLabelLayoutSeedTest {
    @Test
    fun fixedCoreLabelSurvivesAndBlocksCollidingPaddingLabel() {
        val fixed = candidate(id = 1u, feature = 10u, left = 40.0, right = 60.0)
        val colliding = candidate(id = 2u, feature = 20u, left = 50.0, right = 70.0)
        val clear = candidate(id = 3u, feature = 30u, left = 75.0, right = 95.0)

        val selected = select(fixed, colliding, clear)

        assertEquals(fixed, selected.first())
        assertFalse(colliding in selected)
        assertTrue(clear in selected)
    }

    @Test
    fun fixedWaterLabelPreventsASecondCopyOfTheSameFeature() {
        val fixed = candidate(
            id = 1u,
            feature = 10u,
            left = 10.0,
            right = 30.0,
            water = true,
        )
        val repeated = candidate(
            id = 2u,
            feature = 10u,
            left = 150.0,
            right = 170.0,
            water = true,
        )

        val selected = select(fixed, repeated)

        assertEquals(listOf(fixed), selected)
    }

    @Test
    fun preferredOccurrenceWinsOnlyAnEqualPriorityCollision() {
        val challenger = candidate(id = 1u, feature = 10u, left = 40.0, right = 60.0)
        val previous = candidate(id = 2u, feature = 20u, left = 40.0, right = 60.0)

        assertEquals(
            listOf(previous),
            selectPreferred(previous.occurrenceId, challenger, previous),
        )

        val stronger = candidate(
            id = 3u,
            feature = 30u,
            left = 40.0,
            right = 60.0,
            priority = 0,
        )
        assertEquals(
            listOf(stronger),
            selectPreferred(previous.occurrenceId, stronger, previous),
        )
    }

    @Test
    fun emptyPreferredOccurrencesDoNotQueryMembership() {
        val first = candidate(id = 1u, feature = 10u, left = 40.0, right = 60.0)
        val second = candidate(id = 2u, feature = 20u, left = 70.0, right = 90.0)
        val empty_preferred_occurrences = object : Set<ReferenceLabelOccurrenceId> by emptySet() {
            override fun contains(element: ReferenceLabelOccurrenceId): Boolean =
                error("empty preferences must not be queried")
        }

        assertEquals(
            listOf(first, second),
            selectWithPreferred(empty_preferred_occurrences, first, second),
        )
    }

    private fun select(
        fixed: Candidate,
        vararg candidates: Candidate,
    ): List<Candidate> {
        return ReferenceLabelLayoutSelector.select(
            candidates = candidates.toList(),
            fixedCandidates = listOf(fixed),
            viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 100.0),
            staticAvoidRects = emptyList(),
            labelBudget = 8,
            protectedAreaBudget = 4,
            waterRepeatDistancePx = 500.0,
            singleWaterLabelPerFeature = true,
        )
    }

    private fun selectPreferred(
        preferredOccurrence: ReferenceLabelOccurrenceId,
        vararg candidates: Candidate,
    ): List<Candidate> = selectWithPreferred(setOf(preferredOccurrence), *candidates)

    private fun selectWithPreferred(
        preferredOccurrences: Set<ReferenceLabelOccurrenceId>,
        vararg candidates: Candidate,
    ): List<Candidate> {
        return ReferenceLabelLayoutSelector.select(
            candidates = candidates.toList(),
            preferredOccurrences = preferredOccurrences,
            viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 100.0),
            staticAvoidRects = emptyList(),
            labelBudget = 8,
            protectedAreaBudget = 4,
            waterRepeatDistancePx = 500.0,
            singleWaterLabelPerFeature = true,
        )
    }

    private fun candidate(
        id: ULong,
        feature: ULong,
        left: Double,
        right: Double,
        water: Boolean = false,
        priority: Int = 1,
    ): Candidate {
        return Candidate(
            occurrenceId = ReferenceLabelOccurrenceId(id, 0L, 0L),
            featureId = feature,
            priority = priority,
            placementRank = 0,
            protectedArea = false,
            waterLine = water,
            anchor = ReferencePathLabelPoint((left + right) / 2.0, 50.0),
            collisionShape = ReferenceLabelCollisionShape.Box(
                ReferenceScreenRect(left, 40.0, right, 60.0)
            ),
        )
    }

    private data class Candidate(
        override val occurrenceId: ReferenceLabelOccurrenceId,
        override val featureId: ULong,
        override val priority: Int,
        override val placementRank: Int,
        override val protectedArea: Boolean,
        override val waterLine: Boolean,
        override val anchor: ReferencePathLabelPoint,
        override val collisionShape: ReferenceLabelCollisionShape,
    ) : ReferenceLabelLayoutCandidate
}
