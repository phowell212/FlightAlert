package com.flightalert.ui.map.render

import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftMarkerMorphTest {
    @Test
    fun marker_blend_moves_monotonically_from_dots_to_symbols_as_zoom_increases() {
        var previous = 1f
        for (step in 0..48) {
            val zoom = 2.0 + step * 0.25
            val blend = AircraftMarkerMorph.marker_dot_blend(
                visible_count = BUSY_VISIBLE_AIRCRAFT,
                zoom = zoom,
                width = PHONE_WIDTH,
                height = PHONE_HEIGHT
            )
            assertTrue("blend rose at zoom $zoom", blend <= previous + EPSILON)
            previous = blend
        }
    }

    @Test
    fun marker_blend_is_zoom_locked_not_density_locked() {
        val zoom = 8.4
        val sparse_blend = AircraftMarkerMorph.marker_dot_blend(
            visible_count = 50,
            zoom = zoom,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )
        val dense_blend = AircraftMarkerMorph.marker_dot_blend(
            visible_count = 6000,
            zoom = zoom,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )

        assertTrue("density changed the morph blend", kotlin.math.abs(sparse_blend - dense_blend) <= EPSILON)
    }

    @Test
    fun shape_transition_begins_before_the_dense_dot_batch_zoom_limit() {
        val blend = AircraftMarkerMorph.marker_dot_blend(
            visible_count = BUSY_VISIBLE_AIRCRAFT,
            zoom = 8.4,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )
        val symbol_progress = AircraftMarkerMorph.symbol_progress(blend)

        assertTrue(symbol_progress >= AircraftMarkerMorph.SYMBOL_ACTIVE_MIN_PROGRESS)
    }

    @Test
    fun dot_size_grows_smoothly_across_the_full_transition_range() {
        var previous = AircraftMarkerMorph.aircraft_dot_scale(3.0)
        for (step in 1..44) {
            val zoom = 3.0 + step * 0.25
            val scale = AircraftMarkerMorph.aircraft_dot_scale(zoom)
            assertTrue("dot scale shrank at zoom $zoom", scale >= previous - EPSILON)
            assertTrue("dot scale jumped at zoom $zoom", scale - previous <= 0.045f)
            previous = scale
        }
    }

    @Test
    fun dot_size_never_shrinks_below_the_readable_scale_bar_transition_size() {
        val far_zoom_scale = AircraftMarkerMorph.aircraft_dot_scale(3.0)

        assertTrue(far_zoom_scale >= AircraftMarkerMorph.READABLE_DOT_SCALE_MIN)
    }

    @Test
    fun blended_icon_scale_has_no_large_zoom_step_discontinuities() {
        var previous = AircraftMarkerMorph.blended_icon_scale(
            zoom = 5.5,
            dot_blend = AircraftMarkerMorph.marker_dot_blend(BUSY_VISIBLE_AIRCRAFT, 5.5, PHONE_WIDTH, PHONE_HEIGHT)
        )
        for (step in 1..34) {
            val zoom = 5.5 + step * 0.25
            val blend = AircraftMarkerMorph.marker_dot_blend(BUSY_VISIBLE_AIRCRAFT, zoom, PHONE_WIDTH, PHONE_HEIGHT)
            val scale = AircraftMarkerMorph.blended_icon_scale(zoom, blend)
            assertTrue("blended scale jumped at zoom $zoom", kotlin.math.abs(scale - previous) <= 0.08f)
            previous = scale
        }
    }

    @Test
    fun dot_fade_and_symbol_progress_overlap_during_the_middle_of_the_transition() {
        val blend = AircraftMarkerMorph.marker_dot_blend(
            visible_count = BUSY_VISIBLE_AIRCRAFT,
            zoom = 9.6,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )

        assertTrue(AircraftMarkerMorph.dot_progress(blend) > 0.1f)
        assertTrue(AircraftMarkerMorph.symbol_progress(blend) > 0.25f)
    }

    @Test
    fun shape_morph_remains_mid_transition_at_medium_zoom() {
        val blend = AircraftMarkerMorph.marker_dot_blend(
            visible_count = BUSY_VISIBLE_AIRCRAFT,
            zoom = 8.4,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )
        val shape_progress = AircraftMarkerMorph.shape_progress(blend)

        assertTrue("shape already looked finished", shape_progress < 0.8f)
        assertTrue("shape had not started enough", shape_progress > 0.45f)
    }

    private companion object {
        const val PHONE_WIDTH = 1080f
        const val PHONE_HEIGHT = 1920f
        const val BUSY_VISIBLE_AIRCRAFT = 650
        const val EPSILON = 0.0001f
    }
}
