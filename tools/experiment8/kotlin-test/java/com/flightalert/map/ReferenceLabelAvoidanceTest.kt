package com.flightalert.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceLabelAvoidanceTest {
    @Test
    fun paddedRectExpandsEveryEdgeWithoutChangingTheSourceRect() {
        val source = ReferenceScreenRect(left = 10.0, top = 20.0, right = 50.0, bottom = 70.0)

        val padded = source.padded(8.0)

        assertEquals(ReferenceScreenRect(2.0, 12.0, 58.0, 78.0), padded)
        assertEquals(ReferenceScreenRect(10.0, 20.0, 50.0, 70.0), source)
    }

    @Test
    fun ownshipRectEnclosesMarkerAndYouPillWithOnePaddingMargin() {
        val rect = ReferenceLabelAvoidance.ownship_rect(
            center_x = 100.0,
            center_y = 200.0,
            marker_radius_px = 28.0,
            pill_half_width_px = 35.0,
            pill_top_offset_px = 30.0,
            pill_height_px = 22.0,
            padding_px = 8.0,
        )

        assertEquals(ReferenceScreenRect(57.0, 164.0, 143.0, 260.0), rect)
    }

    @Test
    fun invalidOrNonFiniteRectsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceScreenRect(20.0, 0.0, 10.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceScreenRect(0.0, 0.0, 0.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceScreenRect(0.0, Double.NaN, 1.0, 1.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ReferenceScreenRect(0.0, 0.0, 1.0, 1.0).padded(-1.0)
        }
    }

    @Test
    fun mapTileStateKeepsOldCallersSourceCompatibleWithNoAvoidRects() {
        val state = MapTileRenderState(
            map_source = TileSource.SATELLITE,
            map_labels_enabled = true,
            map_borders_enabled = true,
            map_label_text_scale = 1f,
            place_labels_enabled = true,
            water_labels_enabled = true,
            region_labels_enabled = true,
            public_lands_enabled = true,
            user_agent = "test",
        )

        assertTrue(state.reference_label_avoid_rects.isEmpty())
    }
}
