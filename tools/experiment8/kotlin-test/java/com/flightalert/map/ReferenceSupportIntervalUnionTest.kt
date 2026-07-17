package com.flightalert.map

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceSupportIntervalUnionTest {
    private val epsilon = 1e-9

    @Test
    fun bridgingIntervalCompletesCoverageImmediately() {
        val union = ReferenceSupportIntervalUnion(epsilon)

        union.add(0.0, 0.4)
        union.add(0.6, 1.0)
        assertFalse(union.coversWhole())

        union.add(0.4, 0.6)

        assertTrue(union.coversWhole())
    }

    @Test
    fun epsilonTouchMergesButLargerGapRemainsUnsupported() {
        val touching = ReferenceSupportIntervalUnion(epsilon)
        touching.add(0.0, 0.5)
        touching.add(0.5 + epsilon, 1.0)
        assertTrue(touching.coversWhole())

        val separated = ReferenceSupportIntervalUnion(epsilon)
        separated.add(0.0, 0.5)
        separated.add(0.5 + epsilon * 2.0, 1.0)
        assertFalse(separated.coversWhole())
    }

    @Test
    fun everyInsertionMatchesTheFrozenSortAndFrontierOracle() {
        val random = Random(0x51_77_A8)
        repeat(200) {
            val union = ReferenceSupportIntervalUnion(epsilon)
            val inserted = mutableListOf<Pair<Double, Double>>()
            repeat(80) {
                val first = random.nextDouble(-0.25, 1.25)
                val second = random.nextDouble(-0.25, 1.25)
                val start = minOf(first, second).coerceAtLeast(0.0)
                val end = maxOf(first, second).coerceAtMost(1.0)
                if (start <= end + epsilon) {
                    union.add(start, end)
                    inserted += start to end
                }
                assertEquals(frozenCoverageOracle(inserted), union.coversWhole())
            }
        }
    }

    @Test
    fun reverseOrderedDisjointIntervalsMatchTheFrozenOracleAndClearReusesState() {
        val union = ReferenceSupportIntervalUnion(epsilon, initialIntervalCapacity = 1)
        val inserted = mutableListOf<Pair<Double, Double>>()
        for (index in 99 downTo 0) {
            val start = index / 100.0
            val end = start + 0.004
            union.add(start, end)
            inserted += start to end
            assertEquals(frozenCoverageOracle(inserted), union.coversWhole())
        }

        union.clear()
        assertFalse(union.coversWhole())
        union.add(0.0, 0.5)
        union.add(0.5, 1.0)
        assertTrue(union.coversWhole())
    }

    @Test
    fun denseReverseDisjointInputSwitchesToBoundedBatchEvaluation() {
        val union = ReferenceSupportIntervalUnion(epsilon, initialIntervalCapacity = 1)
        for (index in 8_191 downTo 0) {
            val start = index / 8_192.0
            union.add(start, start + 0.000_01)
            assertFalse(union.coversWholeOnline())
        }

        assertTrue(union.usesBatchEvaluation)
        assertFalse(union.coversWholeAfterAll())

        union.add(0.0, 1.0)
        assertTrue(union.usesBatchEvaluation)
        assertTrue(union.coversWholeAfterAll())
    }

    private fun frozenCoverageOracle(intervals: List<Pair<Double, Double>>): Boolean {
        var coveredEnd = 0.0
        for ((start, end) in intervals.sortedWith(compareBy<Pair<Double, Double>> { it.first }.thenByDescending { it.second })) {
            if (start > coveredEnd + epsilon) return false
            coveredEnd = maxOf(coveredEnd, end)
            if (coveredEnd >= 1.0 - epsilon) return true
        }
        return false
    }
}
