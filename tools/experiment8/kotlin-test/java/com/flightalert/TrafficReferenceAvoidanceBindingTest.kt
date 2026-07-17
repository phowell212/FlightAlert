package com.flightalert

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficReferenceAvoidanceBindingTest {
    @Test
    fun onePreparedTrafficStateFeedsReferenceAvoidanceAndTheActualDraw() {
        val source = File(
            "app/src/main/java/com/flightalert/FlightMapView.kt",
        ).readText()
        val on_draw = source_section(source, "override fun onDraw", "override fun onTouchEvent")
        val prepare = on_draw.indexOf("val traffic_state = traffic_overlay_state(viewport)")
        val timestamp = on_draw.indexOf("val traffic_draw_elapsed_ms = SystemClock.elapsedRealtime()")
        val map_draw = on_draw.indexOf(
            "draw_map_tiles(this, viewport, traffic_state, traffic_draw_elapsed_ms)",
        )
        val traffic_draw = on_draw.indexOf(
            "draw_traffic_overlay(this, traffic_state, traffic_draw_elapsed_ms)",
        )

        assertTrue("traffic must be prepared before map labels", prepare >= 0 && prepare < map_draw)
        assertTrue("one motion timestamp must be frozen before map labels", timestamp > prepare && timestamp < map_draw)
        assertTrue("the same state must draw traffic after the map", map_draw < traffic_draw)
        assertEquals(1, Regex("traffic_overlay_state\\(viewport\\)").findAll(on_draw).count())

        val map_state = source_section(source, "private fun map_tile_state", "private fun map_label_transition_alpha")
        assertTrue(map_state.contains("traffic_sprite_footprints.append_reference_label_avoid_rects("))
        assertTrue(map_state.contains("state = traffic_state"))
        assertTrue(map_state.contains("now_elapsed_ms = traffic_draw_elapsed_ms"))

        val traffic_overlay = source_section(
            source,
            "private fun draw_traffic_overlay",
            "private fun draw_ownship_overlay",
        )
        assertFalse(traffic_overlay.contains("traffic_overlay_state("))
        assertTrue(traffic_overlay.contains("state = traffic_state"))
        assertTrue(traffic_overlay.contains("now_elapsed_ms = traffic_draw_elapsed_ms"))
    }

    private fun source_section(source: String, start: String, end: String): String {
        val start_index = source.indexOf(start)
        val end_index = source.indexOf(end, startIndex = start_index + start.length)
        assertTrue("missing section start: $start", start_index >= 0)
        assertTrue("missing section end: $end", end_index > start_index)
        return source.substring(start_index, end_index)
    }
}
