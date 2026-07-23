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
            selectWithPreferred(empty_preferred_occurrences, 8, first, second),
        )
    }

    @Test
    fun preferredSeedsRequireExactOccurrenceAndActivePriority() {
        val challenger = candidate(id = 1u, feature = 10u, left = 40.0, right = 60.0)
        val exact_previous = candidate(
            id = 2u,
            feature = 20u,
            left = 40.0,
            right = 60.0,
            repeatOrdinal = 1L,
        )
        val stale_current_repeat = candidate(
            id = 3u,
            feature = 30u,
            left = 40.0,
            right = 60.0,
            repeatOrdinal = 2L,
        )
        val wrong_world_copy = candidate(
            id = 4u,
            feature = 40u,
            left = 40.0,
            right = 60.0,
            renderedWorldCopy = 1L,
        )
        val same_feature_nonpreferred = candidate(
            id = 5u,
            feature = exact_previous.featureId,
            left = 70.0,
            right = 90.0,
        )
        val weaker_previous = candidate(
            id = 6u,
            feature = 60u,
            left = 70.0,
            right = 90.0,
            priority = 2,
        )
        val stronger = candidate(
            id = 7u,
            feature = 5u,
            left = 40.0,
            right = 60.0,
            priority = 0,
        )
        val preferred_occurrences = setOf(
            exact_previous.occurrenceId,
            ReferenceLabelOccurrenceId(3u, 1L, 0L),
            ReferenceLabelOccurrenceId(4u, 0L, 0L),
            weaker_previous.occurrenceId,
        )
        val seeds = ArrayList<Candidate>()
        ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
            candidates = listOf(
                exact_previous,
                stale_current_repeat,
                wrong_world_copy,
                same_feature_nonpreferred,
                weaker_previous,
            ),
            preferredOccurrences = preferred_occurrences,
            occurrenceId = Candidate::occurrenceId,
            output = seeds,
        )

        assertEquals(
            listOf(exact_previous, weaker_previous),
            seeds,
        )
        assertFalse(same_feature_nonpreferred in seeds)

        val equal_priority_selection = arrayListOf(
            challenger,
            stale_current_repeat,
            wrong_world_copy,
            same_feature_nonpreferred,
        )
        ReferenceLabelAdmissionPolicy.appendActivePreferredSeeds(
            seeds = seeds,
            priorityFrontier = challenger.priority,
            priority = Candidate::priority,
            output = equal_priority_selection,
        )
        assertEquals(
            listOf(exact_previous),
            selectWithPreferred(
                preferred_occurrences,
                1,
                *equal_priority_selection.toTypedArray(),
            ),
        )

        val stale_repeat_seeds = ArrayList<Candidate>()
        ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
            candidates = listOf(stale_current_repeat),
            preferredOccurrences = setOf(ReferenceLabelOccurrenceId(3u, 1L, 0L)),
            occurrenceId = Candidate::occurrenceId,
            output = stale_repeat_seeds,
        )
        assertTrue(stale_repeat_seeds.isEmpty())
        assertEquals(
            listOf(challenger),
            selectWithPreferred(
                setOf(ReferenceLabelOccurrenceId(3u, 1L, 0L)),
                1,
                challenger,
                stale_current_repeat,
            ),
        )

        val wrong_world_seeds = ArrayList<Candidate>()
        ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
            candidates = listOf(wrong_world_copy),
            preferredOccurrences = setOf(ReferenceLabelOccurrenceId(4u, 0L, 0L)),
            occurrenceId = Candidate::occurrenceId,
            output = wrong_world_seeds,
        )
        assertTrue(wrong_world_seeds.isEmpty())
        assertEquals(
            listOf(challenger),
            selectWithPreferred(
                setOf(ReferenceLabelOccurrenceId(4u, 0L, 0L)),
                1,
                challenger,
                wrong_world_copy,
            ),
        )

        val stronger_priority_selection = arrayListOf(stronger)
        ReferenceLabelAdmissionPolicy.appendActivePreferredSeeds(
            seeds = seeds,
            priorityFrontier = challenger.priority,
            priority = Candidate::priority,
            output = stronger_priority_selection,
        )
        assertEquals(
            listOf(stronger),
            selectWithPreferred(
                preferred_occurrences,
                1,
                *stronger_priority_selection.toTypedArray(),
            ),
        )

        val stronger_frontier_selection = arrayListOf(stronger)
        ReferenceLabelAdmissionPolicy.appendActivePreferredSeeds(
            seeds = seeds,
            priorityFrontier = stronger.priority,
            priority = Candidate::priority,
            output = stronger_frontier_selection,
        )
        assertEquals(listOf(stronger), stronger_frontier_selection)
        assertEquals(
            listOf(stronger),
            selectWithPreferred(
                preferred_occurrences,
                1,
                *stronger_frontier_selection.toTypedArray(),
            ),
        )
    }

    @Test
    fun preferredPrepassDoesNotDedupeNullIdPostingsOrSeedExcludedPadding() {
        val lookup_key = PrepassKey(20u, 0L)
        val other_repeat = candidate(
            id = lookup_key.candidateId,
            feature = 20u,
            left = 40.0,
            right = 60.0,
            repeatOrdinal = 2L,
        )
        val exact_repeat = candidate(
            id = lookup_key.candidateId,
            feature = 20u,
            left = 40.0,
            right = 60.0,
            repeatOrdinal = 1L,
        )
        val excluded_exact_repeat = candidate(
            id = lookup_key.candidateId,
            feature = 20u,
            left = 120.0,
            right = 140.0,
            repeatOrdinal = 1L,
        )
        val postings = listOf(
            PrepassPosting(lookup_key, other_repeat),
            PrepassPosting(lookup_key, exact_repeat),
            PrepassPosting(lookup_key, excluded_exact_repeat, excluded = true),
        )
        val planned_keys = HashSet<PrepassKey>()
        val generated_candidates = ArrayList<Candidate>()
        var visited_postings = 0

        assertTrue(
            ReferenceLabelAdmissionPolicy.visitPreferredSeedRecords(
                records = postings,
                preferredLookupKeys = setOf(lookup_key),
                lookupKey = PrepassPosting::lookupKey,
                recordCandidateId = PrepassPosting::recordCandidateId,
                dedupeKey = { candidate_id, key ->
                    PrepassKey(candidate_id, key.renderedWorldCopy)
                },
                visit = { posting, _, dedupe_key ->
                    visited_postings++
                    if (dedupe_key != null && dedupe_key in planned_keys) {
                        true
                    } else {
                        if (!posting.excluded) {
                            generated_candidates += posting.generatedCandidate
                            dedupe_key?.let(planned_keys::add)
                        }
                        true
                    }
                },
            ),
        )
        val seeds = ArrayList<Candidate>()
        ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
            candidates = generated_candidates,
            preferredOccurrences = setOf(exact_repeat.occurrenceId),
            occurrenceId = Candidate::occurrenceId,
            output = seeds,
        )

        assertEquals(3, visited_postings)
        assertEquals(listOf(other_repeat, exact_repeat), generated_candidates)
        assertEquals(listOf(exact_repeat), seeds)
        assertFalse(excluded_exact_repeat in seeds)
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
    ): List<Candidate> = selectWithPreferred(setOf(preferredOccurrence), 8, *candidates)

    private fun selectWithPreferred(
        preferredOccurrences: Set<ReferenceLabelOccurrenceId>,
        labelBudget: Int = 8,
        vararg candidates: Candidate,
    ): List<Candidate> {
        return ReferenceLabelLayoutSelector.select(
            candidates = candidates.toList(),
            preferredOccurrences = preferredOccurrences,
            viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 100.0),
            staticAvoidRects = emptyList(),
            labelBudget = labelBudget,
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
        repeatOrdinal: Long = 0L,
        renderedWorldCopy: Long = 0L,
    ): Candidate {
        return Candidate(
            occurrenceId = ReferenceLabelOccurrenceId(id, repeatOrdinal, renderedWorldCopy),
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

    private data class PrepassKey(
        val candidateId: ULong,
        val renderedWorldCopy: Long,
    )

    private data class PrepassPosting(
        val lookupKey: PrepassKey,
        val generatedCandidate: Candidate,
        val excluded: Boolean = false,
        val recordCandidateId: ULong? = null,
    )
}
