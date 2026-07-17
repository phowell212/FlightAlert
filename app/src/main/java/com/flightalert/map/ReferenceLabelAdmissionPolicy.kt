package com.flightalert.map

internal object ReferenceLabelAdmissionPolicy {
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
