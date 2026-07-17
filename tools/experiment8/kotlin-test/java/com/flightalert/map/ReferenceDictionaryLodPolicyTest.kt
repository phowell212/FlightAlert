package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceDictionaryLodPolicyTest {
    private val availableZooms = (0..11).toSet()

    @Test
    fun zoom6Point8UsesDictionaryZoom6() {
        assertEquals(6, ReferenceDictionaryLodPolicy.select(6.8, availableZooms))
    }

    @Test
    fun zoom8Point5UsesDictionaryZoom7() {
        assertEquals(7, ReferenceDictionaryLodPolicy.select(8.5, availableZooms))
    }

    @Test
    fun zoom9Point2UsesDictionaryZoom7() {
        assertEquals(7, ReferenceDictionaryLodPolicy.select(9.2, availableZooms))
    }

    @Test
    fun zoom9Point5UsesDictionaryZoom8() {
        assertEquals(8, ReferenceDictionaryLodPolicy.select(9.5, availableZooms))
    }

    @Test
    fun zoom10Point316UsesDictionaryZoom8() {
        assertEquals(8, ReferenceDictionaryLodPolicy.select(10.316, availableZooms))
    }

    @Test
    fun zoom10Point5UsesDictionaryZoom9() {
        assertEquals(9, ReferenceDictionaryLodPolicy.select(10.5, availableZooms))
    }

    @Test
    fun zoom12Point5UsesDictionaryZoom11() {
        assertEquals(11, ReferenceDictionaryLodPolicy.select(12.5, availableZooms))
    }

    @Test
    fun selectedDictionaryLodNeverMovesBackwardAsPhoneZoomIncreases() {
        val selected = (0..1_400).map { centizoom ->
            ReferenceDictionaryLodPolicy.select(centizoom / 100.0, availableZooms)!!
        }

        selected.zipWithNext().forEach { (before, after) ->
            assertTrue("dictionary LOD moved backward from $before to $after", after >= before)
        }
    }

    @Test
    fun sparsePackagesChooseTheNearestAvailableLodAtOrBelowThePhoneTarget() {
        val sparse = setOf(3, 5, 7, 9, 11)

        assertEquals(3, ReferenceDictionaryLodPolicy.select(2.0, sparse))
        assertEquals(7, ReferenceDictionaryLodPolicy.select(9.5, sparse))
        assertEquals(9, ReferenceDictionaryLodPolicy.select(10.5, sparse))
    }
}
