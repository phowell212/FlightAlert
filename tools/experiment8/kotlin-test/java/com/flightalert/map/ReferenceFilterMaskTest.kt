package com.flightalert.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceFilterMaskTest {
    @Test
    fun compilesStoredAndMasterChoicesIntoOneHotPathMask() {
        val defaults = ReferenceFilterMask.from(FilterState.defaults())
        val labelsOff = ReferenceFilterMask.from(
            FilterState.defaults().with_labels_master(false),
        )
        val streamsOff = ReferenceFilterMask.from(
            FilterState.defaults().with_filter(FilterId.LABELS_STREAMS, false),
        )
        val coastOn = ReferenceFilterMask.from(
            FilterState.defaults().with_filter(FilterId.OUTLINES_COASTLINES, true),
        )

        assertTrue(defaults.allows(FilterId.LABELS_RIVERS))
        assertFalse(defaults.allows(FilterId.OUTLINES_COASTLINES))
        assertFalse(labelsOff.allows(FilterId.LABELS_RIVERS))
        assertFalse(labelsOff.allows(FilterId.OUTLINES_COASTLINES))
        assertTrue(coastOn.allows(FilterId.OUTLINES_COASTLINES))
        assertFalse(streamsOff.allows(FilterId.LABELS_STREAMS))
        assertTrue(streamsOff.allows(FilterId.LABELS_RIVERS))
        assertFalse(streamsOff.allows(FilterId.LABELS_STREAMS, legacyAllowed = true))
        assertTrue(streamsOff.allows(FilterId.LABELS_RIVERS, legacyAllowed = false))
        assertTrue(streamsOff.allows(filterId = null, legacyAllowed = true))
        assertFalse(streamsOff.allows(filterId = null, legacyAllowed = false))
        assertNotEquals(defaults.cacheKey, labelsOff.cacheKey)
        assertNotEquals(defaults.cacheKey, streamsOff.cacheKey)
        assertNotEquals(defaults.cacheKey, coastOn.cacheKey)
    }
}
