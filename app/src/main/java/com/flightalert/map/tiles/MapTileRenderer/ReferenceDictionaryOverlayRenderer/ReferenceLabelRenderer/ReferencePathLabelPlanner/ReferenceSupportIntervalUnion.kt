@file:Suppress(
    "ConstPropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.labels

import kotlin.math.max
import kotlin.math.min

internal class ReferenceSupportIntervalUnion(
    private val mergeEpsilon: Double,
    initialIntervalCapacity: Int = 16,
) {
    private var values = DoubleArray(max(1, initialIntervalCapacity) * 2)
    private var count = 0
    private var batchEvaluation = false

    fun clear() {
        count = 0
        batchEvaluation = false
    }

    fun add(start: Double, end: Double) {
        if (batchEvaluation) {
            append(start, end)
            return
        }
        var mergedStart = start
        var mergedEnd = end
        var insertionIndex = 0
        while (
            insertionIndex < count &&
            intervalEnd(insertionIndex) < mergedStart - mergeEpsilon
        ) {
            insertionIndex += 1
        }
        var afterMergedIndex = insertionIndex
        while (
            afterMergedIndex < count &&
            intervalStart(afterMergedIndex) <= mergedEnd + mergeEpsilon
        ) {
            mergedStart = min(mergedStart, intervalStart(afterMergedIndex))
            mergedEnd = max(mergedEnd, intervalEnd(afterMergedIndex))
            afterMergedIndex += 1
        }

        val removedCount = afterMergedIndex - insertionIndex
        val newCount = count - removedCount + 1
        if (newCount > MaximumOnlineIntervals) {
            append(start, end)
            batchEvaluation = true
            return
        }
        ensureCapacity(newCount)
        if (afterMergedIndex < count) {
            values.copyInto(
                destination = values,
                destinationOffset = (insertionIndex + 1) * 2,
                startIndex = afterMergedIndex * 2,
                endIndex = count * 2,
            )
        }
        values[insertionIndex * 2] = mergedStart
        values[insertionIndex * 2 + 1] = mergedEnd
        count = newCount
    }

    fun coversWholeOnline(): Boolean =
        !batchEvaluation && count > 0 &&
            intervalStart(0) <= mergeEpsilon &&
            intervalEnd(0) >= 1.0 - mergeEpsilon

    fun coversWhole(): Boolean {
        if (!batchEvaluation) return coversWholeOnline()
        sortIntervals(0, count - 1)
        var coveredEnd = 0.0
        for (index in 0 until count) {
            val start = intervalStart(index)
            if (start > coveredEnd + mergeEpsilon) return false
            coveredEnd = max(coveredEnd, intervalEnd(index))
            if (coveredEnd >= 1.0 - mergeEpsilon) return true
        }
        return false
    }

    fun coversWholeAfterAll(): Boolean = coversWhole()

    private fun append(start: Double, end: Double) {
        ensureCapacity(count + 1)
        values[count * 2] = start
        values[count * 2 + 1] = end
        count += 1
    }

    private fun sortIntervals(left: Int, right: Int) {
        if (left >= right) return
        var lower = left
        var upper = right
        val pivotIndex = left + (right - left) / 2
        val pivotStart = intervalStart(pivotIndex)
        val pivotEnd = intervalEnd(pivotIndex)
        while (lower <= upper) {
            while (compareToPivot(lower, pivotStart, pivotEnd) < 0) lower += 1
            while (compareToPivot(upper, pivotStart, pivotEnd) > 0) upper -= 1
            if (lower <= upper) {
                swap(lower, upper)
                lower += 1
                upper -= 1
            }
        }
        if (left < upper) sortIntervals(left, upper)
        if (lower < right) sortIntervals(lower, right)
    }

    private fun compareToPivot(index: Int, pivotStart: Double, pivotEnd: Double): Int {
        val start = intervalStart(index)
        if (start < pivotStart) return -1
        if (start > pivotStart) return 1
        val end = intervalEnd(index)
        return when {
            end > pivotEnd -> -1
            end < pivotEnd -> 1
            else -> 0
        }
    }

    private fun swap(first: Int, second: Int) {
        if (first == second) return
        val firstOffset = first * 2
        val secondOffset = second * 2
        val firstStart = values[firstOffset]
        val firstEnd = values[firstOffset + 1]
        values[firstOffset] = values[secondOffset]
        values[firstOffset + 1] = values[secondOffset + 1]
        values[secondOffset] = firstStart
        values[secondOffset + 1] = firstEnd
    }

    private fun ensureCapacity(requiredCount: Int) {
        val requiredValues = requiredCount * 2
        if (requiredValues > values.size) {
            values = values.copyOf(max(requiredValues, values.size * 2))
        }
    }

    private fun intervalStart(index: Int): Double = values[index * 2]

    private fun intervalEnd(index: Int): Double = values[index * 2 + 1]

    private companion object {
        const val MaximumOnlineIntervals = 128
    }
}