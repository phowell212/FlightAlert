package com.flightalert.map

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferencePathLabelPlannerTest {
    private val viewport = ReferenceScreenRect(0.0, 0.0, 300.0, 300.0)

    private fun point(x: Double, y: Double) = ReferencePathLabelPoint(x, y)

    private fun request(
        parts: List<List<ReferencePathLabelPoint>>,
        tier: ProminenceTier = ProminenceTier.LOCAL,
        shapedAdvancePx: Double = 100.0,
        endClearancePx: Double = 10.0,
        edgeClearancePx: Double = 5.0,
        maxBendDegrees: Double = 30.0,
        candidateId: ULong = 37uL,
        repeatSpacingPx: Int = 100,
        staticAvoidRects: List<ReferenceScreenRect> = emptyList(),
        maximumTangentOffsetPx: Double = 0.0,
        viewport: ReferenceScreenRect = this.viewport,
    ) = ReferencePathLabelRequest(
        parts = parts,
        viewport = viewport,
        shapedAdvancePx = shapedAdvancePx,
        endClearancePx = endClearancePx,
        edgeClearancePx = edgeClearancePx,
        maxBendDegrees = maxBendDegrees,
        candidateId = candidateId,
        repeatSpacingPx = repeatSpacingPx,
        prominenceTier = tier,
        staticAvoidRects = staticAvoidRects,
        maximumTangentOffsetPx = maximumTangentOffsetPx,
    )

    @Test
    fun curvedPlacementUsesOneCompleteVisibleSourceSpanAndWholeUncondensedAdvance() {
        val placements = ReferencePathLabelPlanner.plan(
            request(parts = listOf(listOf(point(20.0, 150.0), point(280.0, 150.0)))),
        )

        val best = placements.first()
        assertEquals(ReferencePathLabelPlacementMode.CURVED, best.mode)
        assertEquals(37uL, best.candidateId)
        assertEquals(1.0, best.textScaleX, 0.0)
        assertEquals(0, best.bendCentiDegrees)
        assertEquals(0, best.sourcePosition.partIndex)
        assertEquals(0, best.sourcePosition.segmentIndex)
        assertEquals(0.5, best.sourcePosition.segmentFraction, 1e-9)
        assertEquals(100.0, polylineLength(best.presentationPath), 1e-8)
        assertEquals(point(150.0, 150.0), best.anchor)
    }

    @Test
    fun exactRequiredSpanFitsButOneContinuousPixelLessDoesNot() {
        val exact = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 150.0), point(210.0, 150.0))),
                shapedAdvancePx = 100.0,
                endClearancePx = 10.0,
            ),
        )
        val short = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 150.0), point(209.999, 150.0))),
                shapedAdvancePx = 100.0,
                endClearancePx = 10.0,
            ),
        )

        assertEquals(ReferencePathLabelPlacementMode.CURVED, exact.first().mode)
        assertTrue(short.isEmpty())
    }

    @Test
    fun disconnectedPartsNeverCombineIntoAnArtificialCurvedRun() {
        val placements = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(40.0, 150.0), point(100.0, 150.0)),
                    listOf(point(110.0, 150.0), point(170.0, 150.0)),
                ),
                shapedAdvancePx = 100.0,
                endClearancePx = 10.0,
            ),
        )

        assertTrue(placements.isEmpty())
    }

    @Test
    fun bendIsRoundedUpToCentiDegreesBeforeEligibility() {
        fun bentPart(turnDegrees: Double): List<ReferencePathLabelPoint> {
            val radians = Math.toRadians(turnDegrees)
            return listOf(
                point(50.0, 150.0),
                point(150.0, 150.0),
                point(150.0 + 100.0 * kotlin.math.cos(radians), 150.0 + 100.0 * kotlin.math.sin(radians)),
            )
        }

        val exact = ReferencePathLabelPlanner.plan(
            request(parts = listOf(bentPart(30.0)), shapedAdvancePx = 120.0),
        )
        val over = ReferencePathLabelPlanner.plan(
            request(parts = listOf(bentPart(30.001)), shapedAdvancePx = 120.0),
        )

        assertTrue(exact.any { it.mode == ReferencePathLabelPlacementMode.CURVED && it.bendCentiDegrees == 3_000 })
        assertTrue(over.isEmpty())
    }

    @Test
    fun majorFeatureGetsStraightTangentFallbackOnlyWhenNoCurvedRunFits() {
        val shortPart = listOf(point(130.0, 150.0), point(170.0, 150.0))

        val regional = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(shortPart),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 120.0,
            ),
        )
        val global = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(shortPart),
                tier = ProminenceTier.GLOBAL_MAJOR,
                shapedAdvancePx = 120.0,
            ),
        )
        val local = ReferencePathLabelPlanner.plan(
            request(parts = listOf(shortPart), tier = ProminenceTier.LOCAL, shapedAdvancePx = 120.0),
        )
        val fine = ReferencePathLabelPlanner.plan(
            request(parts = listOf(shortPart), tier = ProminenceTier.FINE, shapedAdvancePx = 120.0),
        )

        listOf(regional, global).forEach { placements ->
            val best = placements.first()
            assertEquals(ReferencePathLabelPlacementMode.TANGENT_WIDE, best.mode)
            assertEquals(point(150.0, 150.0), best.anchor)
            assertEquals(120.0, polylineLength(best.presentationPath), 1e-8)
            assertEquals(1.0, best.textScaleX, 0.0)
            assertEquals(0, best.sourcePosition.segmentIndex)
            assertEquals(0.5, best.sourcePosition.segmentFraction, 1e-9)
        }
        assertTrue(local.isEmpty())
        assertTrue(fine.isEmpty())
    }

    @Test
    fun tangentFallbackReprojectsItsVisibleSourceAnchorInsteadOfKeepingAStaticSpot() {
        val before = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(120.0, 150.0), point(160.0, 150.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
            ),
        ).first()
        val after = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(180.0, 150.0), point(220.0, 150.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
            ),
        ).first()

        assertEquals(150.0, before.anchor.x, 1e-9)
        assertEquals(180.0, after.anchor.x, 1e-9)
        assertEquals(30.0, after.anchor.x - before.anchor.x, 1e-9)
        assertEquals(0.75, before.sourcePosition.segmentFraction, 1e-9)
        assertEquals(0.0, after.sourcePosition.segmentFraction, 1e-9)
    }

    @Test
    fun wholeFallbackMustClearViewportEdgesAndStaticAvoidRectangles() {
        val nearEdge = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(15.0, 100.0), point(35.0, 100.0))),
                tier = ProminenceTier.GLOBAL_MAJOR,
                shapedAdvancePx = 100.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 200.0),
            ),
        )
        val obstructed = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(90.0, 100.0), point(110.0, 100.0))),
                tier = ProminenceTier.GLOBAL_MAJOR,
                shapedAdvancePx = 100.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 200.0),
                staticAvoidRects = listOf(ReferenceScreenRect(40.0, 80.0, 160.0, 120.0)),
            ),
        )

        assertTrue(nearEdge.isEmpty())
        assertTrue(obstructed.isEmpty())
    }

    @Test
    fun majorFallbackUsesTheSmallestBoundedNormalOffsetWhenOwnshipCoversTheSource() {
        val obstacle = ReferenceScreenRect(40.0, 80.0, 160.0, 120.0)

        val placement = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(listOf(point(80.0, 100.0), point(120.0, 100.0))),
                tier = ProminenceTier.REGIONAL_MAJOR,
                shapedAdvancePx = 100.0,
                edgeClearancePx = 5.0,
                staticAvoidRects = listOf(obstacle),
                maximumTangentOffsetPx = 40.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 200.0, 200.0),
            ),
        ).first()

        assertEquals(ReferencePathLabelPlacementMode.TANGENT_WIDE, placement.mode)
        assertEquals(point(100.0, 100.0), placement.anchor)
        assertTrue(abs(placement.normalOffsetPx) >= 25.0)
        assertTrue(abs(placement.normalOffsetPx) <= 40.0)
        assertTrue(placement.presentationPath.all { point ->
            point.y < obstacle.top - 5.0 || point.y > obstacle.bottom + 5.0
        })
    }

    @Test
    fun rankingIsClearanceThenBendThenCenterThenCanonicalSourcePosition() {
        val clearanceRanked = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(20.0, 40.0), point(280.0, 40.0)),
                    listOf(point(20.0, 150.0), point(280.0, 150.0)),
                ),
            ),
        )
        assertEquals(1, clearanceRanked.first().sourcePosition.partIndex)

        val straightBeforeBent = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(100.0, 150.0), point(300.0, 150.0)),
                    listOf(point(100.0, 150.0), point(200.0, 145.0), point(300.0, 150.0)),
                ),
                shapedAdvancePx = 120.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 1_000.0, 1_000.0),
            ),
        )
        assertEquals(0, straightBeforeBent.first().sourcePosition.partIndex)
        assertEquals(0, straightBeforeBent.first().bendCentiDegrees)

        val centerRanked = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(250.0, 500.0), point(450.0, 500.0)),
                    listOf(point(450.0, 500.0), point(650.0, 500.0)),
                ),
                shapedAdvancePx = 80.0,
                viewport = ReferenceScreenRect(0.0, 0.0, 1_000.0, 1_000.0),
            ),
        )
        assertEquals(1, centerRanked.first().sourcePosition.partIndex)

        val canonicalRanked = ReferencePathLabelPlanner.plan(
            request(
                parts = listOf(
                    listOf(point(100.0, 150.0), point(200.0, 150.0)),
                    listOf(point(100.0, 150.0), point(200.0, 150.0)),
                ),
                shapedAdvancePx = 60.0,
            ),
        )
        assertEquals(0, canonicalRanked.first().sourcePosition.partIndex)
    }

    @Test
    fun repeatLatticeIsCandidateIdentityPhasedAndAvoidOrderCannotChangeResults() {
        val avoidA = ReferenceScreenRect(570.0, 0.0, 630.0, 90.0)
        val avoidB = ReferenceScreenRect(570.0, 110.0, 630.0, 200.0)
        val base = request(
            parts = listOf(listOf(point(0.0, 100.0), point(1_200.0, 100.0))),
            shapedAdvancePx = 80.0,
            candidateId = 50uL,
            repeatSpacingPx = 200,
            viewport = ReferenceScreenRect(0.0, 0.0, 1_200.0, 200.0),
            staticAvoidRects = listOf(avoidA, avoidB),
        )
        val forward = ReferencePathLabelPlanner.plan(base)
        val reversed = ReferencePathLabelPlanner.plan(base.copy(staticAvoidRects = listOf(avoidB, avoidA)))

        assertTrue(forward.any { abs(it.anchor.x - 250.0) < 1e-9 && it.repeatOrdinal == 1L })
        assertEquals(forward, reversed)
    }

    @Test
    fun invalidGeometryAndMeasurementsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePathLabelPlanner.plan(
                request(parts = listOf(listOf(point(Double.NaN, 0.0), point(1.0, 1.0)))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePathLabelPlanner.plan(
                request(
                    parts = listOf(listOf(point(0.0, 0.0), point(1.0, 1.0))),
                    shapedAdvancePx = 0.0,
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferencePathLabelPlanner.plan(
                request(
                    parts = listOf(listOf(point(0.0, 0.0), point(1.0, 1.0))),
                    maxBendDegrees = 181.0,
                ),
            )
        }
    }

    private fun polylineLength(points: List<ReferencePathLabelPoint>): Double = points
        .zipWithNext()
        .sumOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
}
