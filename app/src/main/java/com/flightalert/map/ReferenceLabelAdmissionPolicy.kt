package com.flightalert.map

internal object ReferenceLabelAdmissionPolicy {
    fun <T> retainPreferredSeeds(
        candidates: Iterable<T>,
        preferredOccurrences: Set<ReferenceLabelOccurrenceId>,
        occurrenceId: (T) -> ReferenceLabelOccurrenceId,
        output: MutableList<T>,
    ) {
        for (candidate in candidates) {
            if (occurrenceId(candidate) in preferredOccurrences) output += candidate
        }
    }

    fun <T> appendActivePreferredSeeds(
        seeds: Iterable<T>,
        priorityFrontier: Int,
        priority: (T) -> Int,
        output: MutableList<T>,
    ) {
        for (seed in seeds) {
            if (priority(seed) <= priorityFrontier) output += seed
        }
    }

    fun initial_threshold(labelBudget: Int): Int {
        require(labelBudget > 0) { "label budget must be positive" }
        return labelBudget
    }

    fun next_threshold(admittedCandidateCount: Int): Int {
        require(admittedCandidateCount > 0) { "admitted candidate count must be positive" }
        return if (admittedCandidateCount > Int.MAX_VALUE / 2) {
            Int.MAX_VALUE
        } else {
            admittedCandidateCount * 2
        }
    }
}
