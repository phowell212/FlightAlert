package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLabelAdmissionPolicyTest {
    @Test
    fun startsAtTheExactViewportBudget() {
        assertEquals(4, ReferenceLabelAdmissionPolicy.initial_threshold(4))
        assertEquals(22, ReferenceLabelAdmissionPolicy.initial_threshold(22))
    }

    @Test
    fun doublesFromTheActuallyAdmittedPrefixWithoutOverflow() {
        assertEquals(44, ReferenceLabelAdmissionPolicy.next_threshold(22))
        assertEquals(2_000, ReferenceLabelAdmissionPolicy.next_threshold(1_000))
        assertEquals(Int.MAX_VALUE, ReferenceLabelAdmissionPolicy.next_threshold(Int.MAX_VALUE))
    }

    @Test
    fun largeCandidateSetsRequireOnlyLogarithmicSelectorPasses() {
        var threshold = ReferenceLabelAdmissionPolicy.initial_threshold(22)
        var passes = 0
        for (admitted in 1..100_000) {
            if (admitted >= threshold) {
                passes++
                threshold = ReferenceLabelAdmissionPolicy.next_threshold(admitted)
            }
        }
        assertTrue("selector passes must stay logarithmic, got $passes", passes <= 13)
    }
}
