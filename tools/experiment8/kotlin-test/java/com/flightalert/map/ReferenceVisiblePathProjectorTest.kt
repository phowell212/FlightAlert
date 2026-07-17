package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceVisiblePathProjectorTest {
    private val viewport = ReferenceScreenRect(0.0, 0.0, 100.0, 100.0)

    @Test
    fun crossingSegmentWithBothVerticesOffscreenIsRetainedWithExactSourceFractions() {
        val prepared = ReferenceVisiblePathProjector.prepare(
            parts = listOf(
                ReferenceRawPathPart(
                    partIndex = 0,
                    points = floatArrayOf(-10f, 50f, 110f, 50f),
                    pointCount = 2,
                ),
            ),
            transform = ReferencePathProjectionTransform.identity(),
            viewport = viewport,
        )

        val segment = prepared.parts.single().segments.single()
        assertEquals(0, segment.sourceIndex)
        assertEquals(0.0, segment.sourceStartDistance, 1e-9)
        assertEquals(120.0, segment.length, 1e-9)
        assertEquals(1.0 / 12.0, segment.visibleStartFraction, 1e-9)
        assertEquals(11.0 / 12.0, segment.visibleEndFraction, 1e-9)
    }

    @Test
    fun extremelyLongCrossingSegmentIsRetainedByItsVisibleScreenLength() {
        val rawPrepared = ReferenceVisiblePathProjector.prepare(
            parts = listOf(
                ReferenceRawPathPart(
                    partIndex = 0,
                    points = floatArrayOf(-1.0e12f, 50f, 1.0e12f, 50f),
                    pointCount = 2,
                ),
            ),
            transform = ReferencePathProjectionTransform.identity(),
            viewport = viewport,
        )
        val projectedPrepared = ReferenceVisiblePathProjector.prepareScreenParts(
            parts = listOf(
                listOf(
                    ReferencePathLabelPoint(-1.0e12, 50.0),
                    ReferencePathLabelPoint(1.0e12, 50.0),
                ),
            ),
            viewport = viewport,
        )

        val rawSegment = rawPrepared.parts.single().segments.single()
        val projectedSegment = projectedPrepared.parts.single().segments.single()
        assertEquals(100.0, rawSegment.length * (rawSegment.visibleEndFraction - rawSegment.visibleStartFraction), 1e-3)
        assertEquals(
            100.0,
            projectedSegment.length *
                (projectedSegment.visibleEndFraction - projectedSegment.visibleStartFraction),
            1e-3,
        )
    }

    @Test
    fun offscreenExcursionRemainsTwoDisconnectedVisibleSpans() {
        val prepared = ReferenceVisiblePathProjector.prepare(
            parts = listOf(
                ReferenceRawPathPart(
                    partIndex = 4,
                    points = floatArrayOf(
                        10f, 50f,
                        90f, 50f,
                        150f, 50f,
                        150f, 80f,
                        90f, 80f,
                        10f, 80f,
                    ),
                    pointCount = 6,
                ),
            ),
            transform = ReferencePathProjectionTransform.identity(),
            viewport = viewport,
        )

        val segments = prepared.parts.single().segments
        assertEquals(4, prepared.parts.single().partIndex)
        assertEquals(listOf(0, 1, 3, 4), segments.map { it.sourceIndex })
        assertTrue(segments[2].sourceStartDistance > segments[1].sourceEndDistance)
    }

    @Test
    fun preparedMultipartProjectionAndPlacementsMatchFrozenGolden() {
        val transform = ReferencePathProjectionTransform(
            scaleX = 1.25,
            scaleY = 0.75,
            translateX = 3.1,
            translateY = -2.2,
        )
        val prepared = ReferenceVisiblePathProjector.prepare(
            parts = listOf(
                ReferenceRawPathPart(
                    partIndex = 7,
                    points = floatArrayOf(
                        -20f, 60f,
                        8f, 60f,
                        56f, 60f,
                        96f, 60f,
                        112f, 60f,
                        64f, 60f,
                        8f, 60f,
                        -20f, 60f,
                    ),
                    pointCount = 8,
                ),
                ReferenceRawPathPart(
                    partIndex = 23,
                    points = floatArrayOf(-20f, 100f, 96f, 100f),
                    pointCount = 2,
                ),
            ),
            transform = transform,
            viewport = viewport,
        )

        // Keep these hand-derived contract values independent of both production entry points.
        assertEquals(
            ReferencePreparedPathGeometry(
                viewport = viewport,
                parts = listOf(
                    ReferencePreparedPathPart(
                        partIndex = 7,
                        segments = listOf(
                            ReferencePreparedPathSegment(
                                sourceIndex = 0,
                                start = ReferencePathLabelPoint(
                                    -21.899999618530273,
                                    42.79999923706055,
                                ),
                                end = ReferencePathLabelPoint(
                                    13.100000381469727,
                                    42.79999923706055,
                                ),
                                sourceStartDistance = 0.0,
                                length = 35.0,
                                visibleStartFraction = 0.6257142748151506,
                                visibleEndFraction = 1.0,
                            ),
                            ReferencePreparedPathSegment(
                                sourceIndex = 1,
                                start = ReferencePathLabelPoint(
                                    13.100000381469727,
                                    42.79999923706055,
                                ),
                                end = ReferencePathLabelPoint(
                                    73.0999984741211,
                                    42.79999923706055,
                                ),
                                sourceStartDistance = 35.0,
                                length = 59.99999809265137,
                                visibleStartFraction = 0.0,
                                visibleEndFraction = 1.0,
                            ),
                            ReferencePreparedPathSegment(
                                sourceIndex = 2,
                                start = ReferencePathLabelPoint(
                                    73.0999984741211,
                                    42.79999923706055,
                                ),
                                end = ReferencePathLabelPoint(
                                    123.0999984741211,
                                    42.79999923706055,
                                ),
                                sourceStartDistance = 94.99999809265137,
                                length = 50.0,
                                visibleStartFraction = 0.0,
                                visibleEndFraction = 0.5380000305175782,
                            ),
                            ReferencePreparedPathSegment(
                                sourceIndex = 4,
                                start = ReferencePathLabelPoint(
                                    143.10000610351562,
                                    42.79999923706055,
                                ),
                                end = ReferencePathLabelPoint(
                                    83.0999984741211,
                                    42.79999923706055,
                                ),
                                sourceStartDistance = 165.0000057220459,
                                length = 60.00000762939453,
                                visibleStartFraction = 0.7183333437177857,
                                visibleEndFraction = 1.0,
                            ),
                            ReferencePreparedPathSegment(
                                sourceIndex = 5,
                                start = ReferencePathLabelPoint(
                                    83.0999984741211,
                                    42.79999923706055,
                                ),
                                end = ReferencePathLabelPoint(
                                    13.100000381469727,
                                    42.79999923706055,
                                ),
                                sourceStartDistance = 225.00001335144043,
                                length = 69.99999809265137,
                                visibleStartFraction = 0.0,
                                visibleEndFraction = 1.0,
                            ),
                            ReferencePreparedPathSegment(
                                sourceIndex = 6,
                                start = ReferencePathLabelPoint(
                                    13.100000381469727,
                                    42.79999923706055,
                                ),
                                end = ReferencePathLabelPoint(
                                    -21.899999618530273,
                                    42.79999923706055,
                                ),
                                sourceStartDistance = 295.0000114440918,
                                length = 35.0,
                                visibleStartFraction = 0.0,
                                visibleEndFraction = 0.3742857251848493,
                            ),
                        ),
                        fullLength = 330.0000114440918,
                    ),
                    ReferencePreparedPathPart(
                        partIndex = 23,
                        segments = listOf(
                            ReferencePreparedPathSegment(
                                sourceIndex = 0,
                                start = ReferencePathLabelPoint(
                                    -21.899999618530273,
                                    72.80000305175781,
                                ),
                                end = ReferencePathLabelPoint(
                                    123.0999984741211,
                                    72.80000305175781,
                                ),
                                sourceStartDistance = 0.0,
                                length = 144.99999809265137,
                                visibleStartFraction = 0.15103448211452197,
                                visibleEndFraction = 0.840689663600128,
                            ),
                        ),
                        fullLength = 144.99999809265137,
                    ),
                ),
            ),
            prepared,
        )

        val placements = ReferencePathLabelPlanner.planPrepared(
            request(parts = emptyList()).copy(
                shapedAdvancePx = 100.0,
                endClearancePx = 0.0,
                edgeClearancePx = 0.0,
                maxBendDegrees = 0.0,
                candidateId = 0uL,
                repeatSpacingPx = 100,
                prominenceTier = ProminenceTier.LOCAL,
                maximumCurvedSourceDistancePx = 0.0,
                allowTangentFallback = false,
            ),
            prepared,
        )

        assertEquals(
            listOf(
                ReferencePathLabelPlacement(
                    mode = ReferencePathLabelPlacementMode.CURVED,
                    candidateId = 0uL,
                    presentationPath = listOf(
                        ReferencePathLabelPoint(0.0, 42.79999923706055),
                        ReferencePathLabelPoint(13.100000381469727, 42.79999923706055),
                        ReferencePathLabelPoint(73.0999984741211, 42.79999923706055),
                        ReferencePathLabelPoint(100.0, 42.79999923706055),
                    ),
                    anchor = ReferencePathLabelPoint(50.0, 42.79999923706055),
                    sourcePosition = ReferencePathSourcePosition(
                        partIndex = 7,
                        segmentIndex = 1,
                        segmentFraction = 0.6150000131924951,
                    ),
                    tangentDegrees = 0.0,
                    bendCentiDegrees = 0,
                    minimumClearanceQ8Px = 0L,
                    centerDistanceQ8Px = 1_843L,
                    repeatOrdinal = 1L,
                    normalOffsetPx = 0.0,
                ),
                ReferencePathLabelPlacement(
                    mode = ReferencePathLabelPlacementMode.CURVED,
                    candidateId = 0uL,
                    presentationPath = listOf(
                        ReferencePathLabelPoint(0.0, 42.79999923706055),
                        ReferencePathLabelPoint(13.100000381469727, 42.79999923706055),
                        ReferencePathLabelPoint(83.0999984741211, 42.79999923706055),
                        ReferencePathLabelPoint(100.0, 42.79999923706055),
                    ),
                    anchor = ReferencePathLabelPoint(50.0, 42.79999923706055),
                    sourcePosition = ReferencePathSourcePosition(
                        partIndex = 7,
                        segmentIndex = 5,
                        segmentFraction = 0.47285713394320716,
                    ),
                    tangentDegrees = 0.0,
                    bendCentiDegrees = 0,
                    minimumClearanceQ8Px = 0L,
                    centerDistanceQ8Px = 1_843L,
                    repeatOrdinal = 3L,
                    normalOffsetPx = 0.0,
                ),
                ReferencePathLabelPlacement(
                    mode = ReferencePathLabelPlacementMode.CURVED,
                    candidateId = 0uL,
                    presentationPath = listOf(
                        ReferencePathLabelPoint(0.0, 72.80000305175781),
                        ReferencePathLabelPoint(100.0, 72.80000305175781),
                    ),
                    anchor = ReferencePathLabelPoint(50.0, 72.80000305175781),
                    sourcePosition = ReferencePathSourcePosition(
                        partIndex = 23,
                        segmentIndex = 0,
                        segmentFraction = 0.495862072857325,
                    ),
                    tangentDegrees = 0.0,
                    bendCentiDegrees = 0,
                    minimumClearanceQ8Px = 0L,
                    centerDistanceQ8Px = 5_837L,
                    repeatOrdinal = 1L,
                    normalOffsetPx = 0.0,
                ),
            ),
            placements,
        )
    }

    @Test
    fun projectionUsesTheRendererFloatCanonicalization() {
        val transform = ReferencePathProjectionTransform(
            scaleX = 1.25,
            scaleY = 0.75,
            translateX = 3.1,
            translateY = -2.2,
        )
        val expectedStartX = (3.1 + 8f * 1.25).toFloat().toDouble()
        val expectedStartY = (-2.2 + 9f * 0.75).toFloat().toDouble()
        val prepared = ReferenceVisiblePathProjector.prepare(
            parts = listOf(
                ReferenceRawPathPart(
                    partIndex = 0,
                    points = floatArrayOf(8f, 9f, 16f, 18f),
                    pointCount = 2,
                ),
            ),
            transform = transform,
            viewport = ReferenceScreenRect(-100.0, -100.0, 100.0, 100.0),
        )

        val segment = prepared.parts.single().segments.single()
        assertEquals(expectedStartX, segment.start.x, 0.0)
        assertEquals(expectedStartY, segment.start.y, 0.0)
    }

    @Test
    fun largeOffscreenPrefixAllocatesOnlyTheCrossingSegment() {
        val pointCount = 20_000
        val points = FloatArray(pointCount * 2)
        for (index in 0 until pointCount - 1) {
            points[index * 2] = -2_000f - index
            points[index * 2 + 1] = -2_000f
        }
        points[(pointCount - 2) * 2] = -10f
        points[(pointCount - 2) * 2 + 1] = 50f
        points[(pointCount - 1) * 2] = 110f
        points[(pointCount - 1) * 2 + 1] = 50f

        val prepared = ReferenceVisiblePathProjector.prepare(
            parts = listOf(ReferenceRawPathPart(0, points, pointCount)),
            transform = ReferencePathProjectionTransform.identity(),
            viewport = viewport,
        )

        assertEquals(1, prepared.parts.single().segments.size)
        assertEquals(pointCount - 2, prepared.parts.single().segments.single().sourceIndex)
    }

    private fun request(parts: List<List<ReferencePathLabelPoint>>) = ReferencePathLabelRequest(
        parts = parts,
        viewport = viewport,
        shapedAdvancePx = 42.0,
        endClearancePx = 3.0,
        edgeClearancePx = 2.0,
        maxBendDegrees = 55.0,
        candidateId = 77uL,
        repeatSpacingPx = 48,
        prominenceTier = ProminenceTier.REGIONAL_MAJOR,
        maximumCurvedSourceDistancePx = 4.0,
        allowTangentFallback = true,
    )
}
