package com.flightalert.map

import java.util.Random
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceScreenSegmentAabbIndexTest {
    @Test
    fun randomizedQueriesMatchTheExactBruteForceAabbFilter() {
        val random = Random(0x5eedc0deL)
        repeat(12) { fixtureIndex ->
            val segments = List(320) { sourceIndex ->
                val startX = random.nextDouble() * 4_000.0 - 2_000.0
                val startY = random.nextDouble() * 4_000.0 - 2_000.0
                var endX = startX + random.nextDouble() * 500.0 - 250.0
                var endY = startY + random.nextDouble() * 500.0 - 250.0
                if (endX == startX && endY == startY) endX = Math.nextUp(endX)
                segment(
                    sourceIndex = fixtureIndex * 1_000 + sourceIndex,
                    startX = startX,
                    startY = startY,
                    endX = endX,
                    endY = endY,
                )
            }
            val index = ReferenceScreenSegmentAabbIndex(segments)
            val scratch = ReferenceScreenSegmentAabbQueryScratch()

            repeat(80) {
                val queryStart = point(
                    random.nextDouble() * 4_000.0 - 2_000.0,
                    random.nextDouble() * 4_000.0 - 2_000.0,
                )
                val queryEnd = point(
                    random.nextDouble() * 4_000.0 - 2_000.0,
                    random.nextDouble() * 4_000.0 - 2_000.0,
                )
                val radiusPx = random.nextDouble() * 100.0

                assertEquals(
                    bruteForce(segments, queryStart, queryEnd, radiusPx),
                    query(index, scratch, queryStart, queryEnd, radiusPx),
                )
            }
        }
    }

    @Test
    fun radiusZeroAndExpandedBoundaryTangenciesAreIncludedExactly() {
        val zeroTangent = segment(0, 20.0, 14.0, 20.0, 16.0)
        val zeroOutside = segment(1, Math.nextUp(20.0), 14.0, Math.nextUp(20.0), 16.0)
        val radiusTangent = segment(2, 22.0, 14.0, 22.0, 16.0)
        val radiusOutside = segment(3, Math.nextUp(22.0), 14.0, Math.nextUp(22.0), 16.0)
        val index = ReferenceScreenSegmentAabbIndex(
            listOf(zeroTangent, zeroOutside, radiusTangent, radiusOutside),
        )
        val queryStart = point(10.0, 10.0)
        val queryEnd = point(20.0, 20.0)
        val scratch = ReferenceScreenSegmentAabbQueryScratch()

        assertEquals(
            listOf(zeroTangent),
            query(index, scratch, queryStart, queryEnd, radiusPx = 0.0),
        )
        assertEquals(
            listOf(zeroTangent, zeroOutside, radiusTangent),
            query(index, scratch, queryStart, queryEnd, radiusPx = 2.0),
        )
    }

    @Test
    fun denseSnakeRetainsSpatiallyNearSourceDistantSegmentsInSourceOrder() {
        val firstNear = segment(0, -1.0, -1.0, 1.0, 1.0)
        val denseDistantSnake = List(8_190) { offset ->
            val sourceIndex = offset + 1
            val x = 1_000.0 + (sourceIndex % 127) * 0.125
            val y = 1_000.0 + (sourceIndex / 127) * 0.125
            segment(
                sourceIndex = sourceIndex,
                startX = x,
                startY = y,
                endX = x + if (sourceIndex % 2 == 0) 0.1 else -0.1,
                endY = y + 0.1,
            )
        }
        val lastNear = segment(8_191, 0.5, -0.5, 1.5, 0.5)
        val segments = buildList {
            add(firstNear)
            addAll(denseDistantSnake)
            add(lastNear)
        }
        val index = ReferenceScreenSegmentAabbIndex(segments)
        val scratch = ReferenceScreenSegmentAabbQueryScratch()

        assertEquals(
            listOf(firstNear, lastNear),
            query(index, scratch, point(0.0, 0.0), point(1.0, 0.0), radiusPx = 0.0),
        )
    }

    @Test
    fun denseIdenticalBoundsArePrunedToTheExactHalfOpenSourceOrdinalWindow() {
        val segments = List(8_192) { ordinal ->
            segment(
                sourceIndex = ordinal * 3,
                startX = -1.0,
                startY = -1.0,
                endX = 1.0,
                endY = 1.0,
            )
        }
        val index = ReferenceScreenSegmentAabbIndex(segments)
        val scratch = ReferenceScreenSegmentAabbQueryScratch()

        index.queryInto(
            queryStartX = 0.0,
            queryStartY = 0.0,
            queryEndX = 0.0,
            queryEndY = 0.0,
            radiusPx = 0.0,
            sourceOrdinalStartInclusive = 4_095,
            sourceOrdinalEndExclusive = 4_098,
            scratch = scratch,
        )

        assertEquals(3, scratch.size)
        assertSame(segments[4_095], index.matchAt(scratch, 0))
        assertSame(segments[4_096], index.matchAt(scratch, 1))
        assertSame(segments[4_097], index.matchAt(scratch, 2))

        index.queryInto(
            queryStartX = 0.0,
            queryStartY = 0.0,
            queryEndX = 0.0,
            queryEndY = 0.0,
            radiusPx = 0.0,
            sourceOrdinalStartInclusive = 4_096,
            sourceOrdinalEndExclusive = 4_096,
            scratch = scratch,
        )
        assertEquals(0, scratch.size)
    }

    @Test
    fun randomizedSpatialAndOrdinalQueriesMatchTheExactCombinedOracle() {
        val random = Random(0x0dd1_aa66L)
        val segments = List(480) { ordinal ->
            val startX = random.nextDouble() * 2_000.0 - 1_000.0
            val startY = random.nextDouble() * 2_000.0 - 1_000.0
            segment(
                sourceIndex = ordinal * 7,
                startX = startX,
                startY = startY,
                endX = startX + random.nextDouble() * 200.0 - 100.0,
                endY = startY + random.nextDouble() * 200.0 - 100.0,
            )
        }
        val index = ReferenceScreenSegmentAabbIndex(segments)
        val scratch = ReferenceScreenSegmentAabbQueryScratch()

        repeat(300) {
            val queryStart = point(
                random.nextDouble() * 2_000.0 - 1_000.0,
                random.nextDouble() * 2_000.0 - 1_000.0,
            )
            val queryEnd = point(
                random.nextDouble() * 2_000.0 - 1_000.0,
                random.nextDouble() * 2_000.0 - 1_000.0,
            )
            val radiusPx = random.nextDouble() * 80.0
            val first = random.nextInt(segments.size + 1)
            val endExclusive = first + random.nextInt(segments.size - first + 1)

            index.queryInto(
                queryStartX = queryStart.x,
                queryStartY = queryStart.y,
                queryEndX = queryEnd.x,
                queryEndY = queryEnd.y,
                radiusPx = radiusPx,
                sourceOrdinalStartInclusive = first,
                sourceOrdinalEndExclusive = endExclusive,
                scratch = scratch,
            )
            val actual = List(scratch.size) { matchIndex -> index.matchAt(scratch, matchIndex) }
            val expected = bruteForce(segments, queryStart, queryEnd, radiusPx)
                .filter { candidate ->
                    val ordinal = segments.indexOf(candidate)
                    ordinal in first until endExclusive
                }
            assertEquals(expected, actual)
        }
    }

    @Test
    fun sourceDistanceWindowsIncludeSharedEndpointsAndRejectRealGaps() {
        val segments = listOf(
            sourceDistanceSegment(sourceIndex = 2, sourceStartDistance = 0.0, length = 10.0),
            sourceDistanceSegment(sourceIndex = 900, sourceStartDistance = 10.0, length = 10.0),
            sourceDistanceSegment(sourceIndex = 901, sourceStartDistance = 30.0, length = 10.0),
        )

        assertEquals(
            0,
            ReferenceSourceSegmentOrdinalWindow.startInclusive(
                segments,
                sourceStartDistance = 10.0,
                epsilon = 1e-9,
            ),
        )
        assertEquals(
            2,
            ReferenceSourceSegmentOrdinalWindow.endExclusive(
                segments,
                sourceEndDistance = 10.0,
                epsilon = 1e-9,
            ),
        )
        assertEquals(
            2,
            ReferenceSourceSegmentOrdinalWindow.startInclusive(
                segments,
                sourceStartDistance = 25.0,
                epsilon = 1e-9,
            ),
        )
        assertEquals(
            2,
            ReferenceSourceSegmentOrdinalWindow.endExclusive(
                segments,
                sourceEndDistance = 25.0,
                epsilon = 1e-9,
            ),
        )
    }

    @Test
    fun mostlyOffscreenTailsDoNotExpandTheIndexedVisibleBounds() {
        val visibleFarFromQuery = segment(
            sourceIndex = 0,
            startX = -1_000.0,
            startY = 50.0,
            endX = 1_000.0,
            endY = 50.0,
            visibleStartFraction = 0.50,
            visibleEndFraction = 0.55,
        )
        val visibleAtQuery = segment(
            sourceIndex = 1,
            startX = -1_000.0,
            startY = 60.0,
            endX = 1_000.0,
            endY = 60.0,
            visibleStartFraction = 0.90,
            visibleEndFraction = 0.975,
        )
        val segments = listOf(visibleFarFromQuery, visibleAtQuery)
        val index = ReferenceScreenSegmentAabbIndex(segments)
        val scratch = ReferenceScreenSegmentAabbQueryScratch()

        assertEquals(
            listOf(visibleAtQuery),
            query(index, scratch, point(900.0, 55.0), point(900.0, 55.0), radiusPx = 10.0),
        )
    }

    @Test
    fun callerOwnedScratchReusesItsPrimitiveStorageAcrossQueries() {
        val segments = List(128) { sourceIndex ->
            segment(
                sourceIndex = sourceIndex,
                startX = sourceIndex.toDouble(),
                startY = -1.0,
                endX = sourceIndex.toDouble(),
                endY = 1.0,
            )
        }
        val index = ReferenceScreenSegmentAabbIndex(segments)
        val scratch = ReferenceScreenSegmentAabbQueryScratch(initialCapacity = 1)

        index.queryInto(
            queryStartX = -1.0,
            queryStartY = 0.0,
            queryEndX = 129.0,
            queryEndY = 0.0,
            radiusPx = 0.0,
            scratch = scratch,
        )
        val storageField = scratch.javaClass.getDeclaredField("values").apply {
            isAccessible = true
        }
        val grownStorage = storageField.get(scratch)
        assertEquals(segments.size, scratch.size)

        repeat(1_000) { queryIndex ->
            val x = (queryIndex % segments.size).toDouble()
            index.queryInto(x, -0.5, x, 0.5, radiusPx = 0.0, scratch = scratch)
            assertSame(grownStorage, storageField.get(scratch))
            assertEquals(1, scratch.size)
            assertSame(segments[queryIndex % segments.size], index.matchAt(scratch, 0))
        }
    }

    @Test
    fun emptyIndexReturnsNoSegmentsAndInvalidRadiiFailClosed() {
        val index = ReferenceScreenSegmentAabbIndex(emptyList())
        val start = point(0.0, 0.0)
        val end = point(1.0, 1.0)
        val scratch = ReferenceScreenSegmentAabbQueryScratch()

        assertEquals(emptyList<ReferencePreparedPathSegment>(), query(index, scratch, start, end, 0.0))
        assertThrows(IllegalArgumentException::class.java) {
            query(index, scratch, start, end, -1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            query(index, scratch, start, end, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            query(index, scratch, start, end, Double.POSITIVE_INFINITY)
        }
        val populated = ReferenceScreenSegmentAabbIndex(
            listOf(segment(0, 0.0, 0.0, 1.0, 1.0)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            populated.queryInto(0.0, 0.0, 1.0, 1.0, 0.0, -1, 1, scratch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            populated.queryInto(0.0, 0.0, 1.0, 1.0, 0.0, 1, 0, scratch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            populated.queryInto(0.0, 0.0, 1.0, 1.0, 0.0, 0, 2, scratch)
        }
    }

    private fun query(
        index: ReferenceScreenSegmentAabbIndex,
        scratch: ReferenceScreenSegmentAabbQueryScratch,
        queryStart: ReferencePathLabelPoint,
        queryEnd: ReferencePathLabelPoint,
        radiusPx: Double,
    ): List<ReferencePreparedPathSegment> {
        index.queryInto(
            queryStartX = queryStart.x,
            queryStartY = queryStart.y,
            queryEndX = queryEnd.x,
            queryEndY = queryEnd.y,
            radiusPx = radiusPx,
            scratch = scratch,
        )
        return List(scratch.size) { matchIndex -> index.matchAt(scratch, matchIndex) }
    }

    private fun bruteForce(
        segments: List<ReferencePreparedPathSegment>,
        queryStart: ReferencePathLabelPoint,
        queryEnd: ReferencePathLabelPoint,
        radiusPx: Double,
    ): List<ReferencePreparedPathSegment> {
        val queryLeft = min(queryStart.x, queryEnd.x) - radiusPx
        val queryTop = min(queryStart.y, queryEnd.y) - radiusPx
        val queryRight = max(queryStart.x, queryEnd.x) + radiusPx
        val queryBottom = max(queryStart.y, queryEnd.y) + radiusPx
        return segments.filter { segment ->
            val visibleStartX = segment.start.x + segment.dx * segment.visibleStartFraction
            val visibleStartY = segment.start.y + segment.dy * segment.visibleStartFraction
            val visibleEndX = segment.start.x + segment.dx * segment.visibleEndFraction
            val visibleEndY = segment.start.y + segment.dy * segment.visibleEndFraction
            val segmentLeft = min(visibleStartX, visibleEndX)
            val segmentTop = min(visibleStartY, visibleEndY)
            val segmentRight = max(visibleStartX, visibleEndX)
            val segmentBottom = max(visibleStartY, visibleEndY)
            segmentRight >= queryLeft &&
                segmentLeft <= queryRight &&
                segmentBottom >= queryTop &&
                segmentTop <= queryBottom
        }
    }

    private fun point(x: Double, y: Double) = ReferencePathLabelPoint(x, y)

    private fun segment(
        sourceIndex: Int,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        visibleStartFraction: Double = 0.0,
        visibleEndFraction: Double = 1.0,
    ): ReferencePreparedPathSegment {
        val start = point(startX, startY)
        val end = point(endX, endY)
        return ReferencePreparedPathSegment(
            sourceIndex = sourceIndex,
            start = start,
            end = end,
            sourceStartDistance = sourceIndex.toDouble(),
            length = hypot(endX - startX, endY - startY),
            visibleStartFraction = visibleStartFraction,
            visibleEndFraction = visibleEndFraction,
        )
    }

    private fun sourceDistanceSegment(
        sourceIndex: Int,
        sourceStartDistance: Double,
        length: Double,
    ): ReferencePreparedPathSegment = ReferencePreparedPathSegment(
        sourceIndex = sourceIndex,
        start = point(sourceStartDistance, 0.0),
        end = point(sourceStartDistance + length, 0.0),
        sourceStartDistance = sourceStartDistance,
        length = length,
        visibleStartFraction = 0.0,
        visibleEndFraction = 1.0,
    )
}
