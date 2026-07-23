package com.flightalert.map

internal object ReferenceLabelAdmissionPolicy {
    fun <R, K> visitPreferredSeedRecords(
        records: Iterable<R>,
        preferredLookupKeys: Set<K>,
        lookupKey: (R) -> K,
        recordCandidateId: (R) -> ULong?,
        dedupeKey: (ULong, K) -> K,
        visit: (R, K, K?) -> Boolean,
    ): Boolean {
        for (record in records) {
            val lookup_key = lookupKey(record)
            if (lookup_key !in preferredLookupKeys) continue
            val dedupe_key = recordCandidateId(record)?.let { candidate_id ->
                dedupeKey(candidate_id, lookup_key)
            }
            if (!visit(record, lookup_key, dedupe_key)) return false
        }
        return true
    }

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
