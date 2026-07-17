package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceLabelLayoutSelectorTest {
    private data class Candidate(
        val token: Int,
        override val occurrenceId: ReferenceLabelOccurrenceId,
        override val featureId: ULong,
        override val priority: Int,
        override val placementRank: Int,
        override val protectedArea: Boolean,
        override val waterLine: Boolean,
        override val anchor: ReferencePathLabelPoint,
        override val collisionShape: ReferenceLabelCollisionShape,
    ) : ReferenceLabelLayoutCandidate

    @Test
    fun alternatePathPlacementIsTriedAfterTheFirstCollides() {
        val blocker = box(
            token = 1,
            occurrence = occurrence(1uL),
            priority = 1,
            rect = rect(40.0, 40.0, 80.0, 80.0),
        )
        val first = path(
            token = 2,
            occurrence = occurrence(2uL),
            priority = 2,
            rank = 0,
            points = listOf(point(20.0, 60.0), point(100.0, 60.0)),
            radius = 5.0,
        )
        val second = path(
            token = 3,
            occurrence = occurrence(2uL),
            priority = 2,
            rank = 1,
            points = listOf(point(20.0, 110.0), point(100.0, 110.0)),
            radius = 5.0,
        )

        assertEquals(listOf(1, 3), select(blocker, first, second).map { it.token })
    }

    @Test
    fun duplicateTileMembershipConsumesOneBudgetSlot() {
        val duplicateA = box(
            token = 1,
            occurrence = occurrence(10uL),
            priority = 1,
            rect = rect(10.0, 10.0, 30.0, 30.0),
        )
        val duplicateB = duplicateA.copy(token = 2)
        val distinct = box(
            token = 3,
            occurrence = occurrence(11uL),
            priority = 2,
            rect = rect(60.0, 10.0, 80.0, 30.0),
        )

        assertEquals(
            listOf(1, 3),
            select(duplicateA, duplicateB, distinct, budget = 2).map { it.token },
        )
    }

    @Test
    fun curvedPathIsNotRejectedByItsBroadBoundingBox() {
        val obstacle = rect(40.0, 40.0, 60.0, 60.0)
        val aroundObstacle = path(
            token = 1,
            occurrence = occurrence(20uL),
            priority = 1,
            rank = 0,
            points = listOf(point(0.0, 0.0), point(0.0, 100.0), point(100.0, 100.0)),
            radius = 5.0,
        )

        assertEquals(
            listOf(1),
            select(aroundObstacle, avoid = listOf(obstacle)).map { it.token },
        )
    }

    private fun occurrence(candidateId: ULong, repeatOrdinal: Long = 0L) =
        ReferenceLabelOccurrenceId(candidateId, repeatOrdinal)

    private fun point(x: Double, y: Double) = ReferencePathLabelPoint(x, y)

    private fun rect(left: Double, top: Double, right: Double, bottom: Double) =
        ReferenceScreenRect(left, top, right, bottom)

    private fun box(
        token: Int,
        occurrence: ReferenceLabelOccurrenceId,
        priority: Int,
        rect: ReferenceScreenRect,
    ) = Candidate(
        token = token,
        occurrenceId = occurrence,
        featureId = occurrence.candidateId,
        priority = priority,
        placementRank = 0,
        protectedArea = false,
        waterLine = false,
        anchor = point((rect.left + rect.right) / 2.0, (rect.top + rect.bottom) / 2.0),
        collisionShape = ReferenceLabelCollisionShape.Box(rect),
    )

    private fun path(
        token: Int,
        occurrence: ReferenceLabelOccurrenceId,
        priority: Int,
        rank: Int,
        points: List<ReferencePathLabelPoint>,
        radius: Double,
    ) = Candidate(
        token = token,
        occurrenceId = occurrence,
        featureId = occurrence.candidateId,
        priority = priority,
        placementRank = rank,
        protectedArea = false,
        waterLine = false,
        anchor = points[points.size / 2],
        collisionShape = ReferenceLabelCollisionShape.Path(
            points = points,
            radiusPx = radius,
            bounds = rect(
                left = points.minOf { it.x } - radius,
                top = points.minOf { it.y } - radius,
                right = points.maxOf { it.x } + radius,
                bottom = points.maxOf { it.y } + radius,
            ),
        ),
    )

    private fun select(
        vararg candidates: Candidate,
        budget: Int = 10,
        avoid: List<ReferenceScreenRect> = emptyList(),
    ): List<Candidate> = ReferenceLabelLayoutSelector.select(
        candidates = candidates.toList(),
        viewport = rect(-1_000.0, -1_000.0, 1_000.0, 1_000.0),
        staticAvoidRects = avoid,
        labelBudget = budget,
        protectedAreaBudget = budget,
        waterRepeatDistancePx = 260.0,
    )
}
