@file:Suppress(
    "FunctionName",
)

package com.flightalert.map.tiles



    internal fun compare(
        firstGeneration: Long,
        firstPriority: Int,
        firstSequence: Long,
        secondGeneration: Long,
        secondPriority: Int,
        secondSequence: Long
    ): Int {
        if (firstGeneration != secondGeneration) {
            return secondGeneration.compareTo(firstGeneration)
        }
        if (firstPriority != secondPriority) {
            return firstPriority.compareTo(secondPriority)
        }
        return firstSequence.compareTo(secondSequence)
    }
