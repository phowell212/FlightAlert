package com.flightalert.traffic

import android.graphics.RectF
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftSymbol
import com.flightalert.map.ReferenceScreenRect
import com.flightalert.map.ScreenPoint
import com.flightalert.map.TileSource
import com.flightalert.map.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSpriteFootprintsTest {
    @Test
    fun footprintUsesTheRenderedMotionTransformAndFullSpriteChrome() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val item = aircraft_state(
            screen_x = 100f,
            screen_y = 200f,
            velocity_x = 10f,
            velocity_y = -5f,
            motion_built_ms = 1_000L,
            motion_limit_sec = 5f,
        )
        val output = mutableListOf<ReferenceScreenRect>()

        footprints.append_reference_label_avoid_rects(
            state = overlay_state(
                aircraft = listOf(item),
                transform_scale = 2f,
                translation_x = 5f,
                translation_y = -3f,
            ),
            now_elapsed_ms = 3_000L,
            clearance_px = 8f,
            output = output,
        )

        assertEquals(1, output.size)
        assert_rect(output.single(), left = 212.75, top = 344.75, right = 277.25, bottom = 409.25)
    }

    @Test
    fun paintedRadiusMatchesEveryRenderedSilhouetteAndSelectedRing() {
        val expected = mapOf(
            AircraftSymbol.GENERAL_AVIATION to 20.25f,
            AircraftSymbol.AIRLINER to 26f,
            AircraftSymbol.ROTORCRAFT to 28.085f,
            AircraftSymbol.GLIDER to 28.5f,
            AircraftSymbol.UAV to 34.75f,
            AircraftSymbol.SURFACE to 23.25f,
        )

        expected.forEach { (symbol, expected_radius) ->
            assertEquals(
                symbol.name,
                expected_radius,
                traffic_aircraft_painted_radius_px(
                    item = aircraft_state(symbol = symbol),
                    viewport_zoom = 14.0,
                    selected = false,
                    dp = { it },
                ),
                0.0001f,
            )
        }
        assertEquals(
            25.3f,
            traffic_aircraft_painted_radius_px(
                item = aircraft_state(symbol = AircraftSymbol.SURFACE),
                viewport_zoom = 14.0,
                selected = true,
                dp = { it },
            ),
            0.0001f,
        )
    }

    @Test
    fun appearanceEnterScaleControlsTheCurrentPaintedRadius() {
        assertEquals(
            11.9475f,
            traffic_aircraft_painted_radius_px(
                item = aircraft_state(
                    symbol = AircraftSymbol.GENERAL_AVIATION,
                    appearance_progress = 0.5f,
                ),
                viewport_zoom = 14.0,
                selected = false,
                dp = { it },
            ),
            0.0001f,
        )
    }

    @Test
    fun keyedDeadbandKeepsTheExactSameRectangleUntilClearanceWouldBeConsumed() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val output = mutableListOf<ReferenceScreenRect>()
        val first_state = overlay_state(aircraft = listOf(aircraft_state(screen_x = 100f)))

        footprints.append_reference_label_avoid_rects(first_state, 1_000L, 8f, output)
        val first = output.single()
        output.clear()
        footprints.append_reference_label_avoid_rects(
            overlay_state(aircraft = listOf(aircraft_state(screen_x = 103f))),
            1_100L,
            8f,
            output,
        )
        assertSame(first, output.single())

        output.clear()
        footprints.append_reference_label_avoid_rects(
            overlay_state(aircraft = listOf(aircraft_state(screen_x = 105f))),
            1_200L,
            8f,
            output,
        )
        assertNotSame(first, output.single())
    }

    @Test
    fun interactionKeepsExactTransformedTrafficAvoidanceLive() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val output = mutableListOf<ReferenceScreenRect>()
        val idle = overlay_state(aircraft = listOf(aircraft_state()))

        footprints.append_reference_label_avoid_rects(idle, 1_000L, 8f, output)
        output.clear()
        footprints.append_reference_label_avoid_rects(
            idle.copy(
                aircraft_transform_scale = 1.5f,
                aircraft_translation_x = 20f,
                aircraft_translation_y = -10f,
                interaction_active = true,
            ),
            1_100L,
            8f,
            output,
        )
        assertEquals(1, output.size)
        val center_x = (output.single().left + output.single().right) / 2.0
        val center_y = (output.single().top + output.single().bottom) / 2.0
        assertEquals(170.0, center_x, 0.0001)
        assertEquals(140.0, center_y, 0.0001)
    }

    @Test
    fun ordinaryBatchedArraysDoNotCreateAReferenceObstacleExplosion() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val points = FloatArray(4_000) { index ->
            if (index % 2 == 0) (index % 200).toFloat() else (index / 200).toFloat()
        }
        val output = mutableListOf<ReferenceScreenRect>()

        footprints.append_reference_label_avoid_rects(
            state = overlay_state(
                aircraft = emptyList(),
                viewport = viewport(zoom = 2.0),
                dot_batch = dot_batch(points = points),
            ),
            now_elapsed_ms = 1_000L,
            clearance_px = 8f,
            output = output,
        )

        assertTrue(output.isEmpty())
    }

    @Test
    fun selectedBatchedDotUsesItsOwnBatchTransformAndRing() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val selected = aircraft_state(screen_x = 100f, screen_y = 50f)
        val output = mutableListOf<ReferenceScreenRect>()

        footprints.append_reference_label_avoid_rects(
            state = overlay_state(
                aircraft = emptyList(),
                viewport = viewport(zoom = 2.0),
                dot_batch = dot_batch(
                    selected = selected,
                    transform_scale = 2f,
                    translation_x = 10f,
                    translation_y = -5f,
                ),
            ),
            now_elapsed_ms = 1_000L,
            clearance_px = 8f,
            output = output,
        )

        assertEquals(1, output.size)
        assert_rect(output.single(), 194.06, 79.06, 225.94, 110.94)
    }

    @Test
    fun denseBatchTransitionOnlyReservesTheSelectedAircraftForReferenceLabels() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val selected = aircraft_state()
        val output = mutableListOf<ReferenceScreenRect>()

        footprints.append_reference_label_avoid_rects(
            state = overlay_state(
                aircraft = listOf(
                    selected,
                    aircraft_state(appearance_key = "hex:def456", screen_x = 200f),
                ),
                viewport = viewport(zoom = 8.0),
                selected_aircraft_id = "abc123",
                dot_batch = dot_batch(selected = selected),
            ),
            now_elapsed_ms = 1_000L,
            clearance_px = 8f,
            output = output,
        )

        assertEquals(1, output.size)
    }

    @Test
    fun denseUnbatchedSymbolsOnlyReserveTheSelectedAircraftForReferenceLabels() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val selected = aircraft_state()
        val traffic = listOf(selected) + (1..64).map { index ->
            aircraft_state(
                appearance_key = "hex:traffic$index",
                screen_x = 100f + index,
            )
        }
        val output = mutableListOf<ReferenceScreenRect>()

        footprints.append_reference_label_avoid_rects(
            state = overlay_state(
                aircraft = traffic,
                viewport = viewport(zoom = 9.0),
                selected_aircraft_id = "abc123",
            ),
            now_elapsed_ms = 1_000L,
            clearance_px = 8f,
            output = output,
        )

        assertEquals(1, output.size)
    }

    @Test
    fun unbatchedWideDotsOnlyReserveTheSelectedAircraftForReferenceLabels() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 4f)
        val output = mutableListOf<ReferenceScreenRect>()

        footprints.append_reference_label_avoid_rects(
            state = overlay_state(
                aircraft = listOf(
                    aircraft_state(appearance_key = "hex:abc123", screen_x = 100f),
                    aircraft_state(appearance_key = "hex:b", screen_x = 200f),
                ),
                viewport = viewport(zoom = 2.0),
                selected_aircraft_id = "abc123",
            ),
            now_elapsed_ms = 1_000L,
            clearance_px = 8f,
            output = output,
        )

        assertEquals(1, output.size)
    }

    @Test
    fun deselectionImmediatelyShrinksAStableFootprint() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 0f)
        val output = mutableListOf<ReferenceScreenRect>()
        val item = aircraft_state(symbol = AircraftSymbol.SURFACE)

        footprints.append_reference_label_avoid_rects(
            overlay_state(aircraft = listOf(item), selected_aircraft_id = "abc123"),
            1_000L,
            8f,
            output,
        )
        val selected = output.single()
        output.clear()
        footprints.append_reference_label_avoid_rects(
            overlay_state(aircraft = listOf(item), selected_aircraft_id = null),
            1_100L,
            8f,
            output,
        )
        val deselected = output.single()

        assertNotSame(selected, deselected)
        assertTrue(deselected.right - deselected.left < selected.right - selected.left)
    }

    @Test
    fun zoomShrinkImmediatelyReplacesTheStableFootprint() {
        val footprints = TrafficSpriteFootprints(dp = { it }, deadband_dp = 0f)
        val output = mutableListOf<ReferenceScreenRect>()
        val item = aircraft_state(symbol = AircraftSymbol.UAV)

        footprints.append_reference_label_avoid_rects(
            overlay_state(aircraft = listOf(item), viewport = viewport(zoom = 14.0)),
            1_000L,
            8f,
            output,
        )
        val large = output.single()
        output.clear()
        footprints.append_reference_label_avoid_rects(
            overlay_state(aircraft = listOf(item), viewport = viewport(zoom = 10.0)),
            1_100L,
            8f,
            output,
        )
        val small = output.single()

        assertNotSame(large, small)
        assertTrue(small.right - small.left < large.right - large.left)
    }

    private fun overlay_state(
        aircraft: List<TrafficAircraftOverlayState>,
        viewport: Viewport = viewport(),
        transform_scale: Float = 1f,
        translation_x: Float = 0f,
        translation_y: Float = 0f,
        selected_aircraft_id: String? = null,
        dot_batch: TrafficDotBatchOverlayState? = null,
    ) = TrafficOverlayState(
        viewport = viewport,
        aircraft = aircraft,
        selected_aircraft_id = selected_aircraft_id,
        map_source = TileSource.SATELLITE,
        content_width = viewport.width,
        content_height = viewport.height,
        label_avoid_rects = emptyList<RectF>(),
        dot_batch = dot_batch,
        aircraft_transform_scale = transform_scale,
        aircraft_translation_x = translation_x,
        aircraft_translation_y = translation_y,
    )

    private fun aircraft_state(
        appearance_key: String = "hex:abc123",
        symbol: AircraftSymbol = AircraftSymbol.GENERAL_AVIATION,
        screen_x: Float = 100f,
        screen_y: Float = 100f,
        velocity_x: Float = 0f,
        velocity_y: Float = 0f,
        motion_built_ms: Long = 0L,
        motion_limit_sec: Float = 0f,
        appearance_progress: Float = 1f,
    ) = TrafficAircraftOverlayState(
        aircraft = Aircraft(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = null,
            type_code = null,
            metadata_seed = null,
            is_military = false,
            lat = 0.0,
            lon = 0.0,
            on_ground = false,
            altitude_m = 1_000.0,
            velocity_ms = 100.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = null,
            position_time_sec = null,
            last_contact_sec = null,
            distance_m = 1_000.0,
        ),
        screen_point = ScreenPoint(screen_x, screen_y),
        appearance_key = appearance_key,
        color = 0,
        appearance_progress = appearance_progress,
        symbol = symbol,
        symbol_scale = 1f,
        dot_group = 0,
        screen_velocity_x_px_per_sec = velocity_x,
        screen_velocity_y_px_per_sec = velocity_y,
        motion_limit_sec = motion_limit_sec,
        motion_built_elapsed_ms = motion_built_ms,
    )

    private fun viewport(zoom: Double = 14.0) = Viewport(
        zoom = zoom,
        center_x = 0.0,
        center_y = 0.0,
        width = 500f,
        height = 500f,
    )

    private fun dot_batch(
        points: FloatArray = floatArrayOf(),
        selected: TrafficAircraftOverlayState? = null,
        transform_scale: Float = 1f,
        translation_x: Float = 0f,
        translation_y: Float = 0f,
    ) = TrafficDotBatchOverlayState(
        outline_points = points,
        outline_count = points.size,
        fill_points = Array(TrafficDotBatchOverlayState.GROUP_COUNT) { group ->
            if (group == 0) points else floatArrayOf()
        },
        fill_counts = IntArray(TrafficDotBatchOverlayState.GROUP_COUNT) { group ->
            if (group == 0) points.size else 0
        },
        selected_aircraft = selected,
        visible_count = points.size / 2,
        transform_scale = transform_scale,
        translation_x = translation_x,
        translation_y = translation_y,
    )

    private fun assert_rect(
        actual: ReferenceScreenRect,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ) {
        assertEquals(left, actual.left, 0.0001)
        assertEquals(top, actual.top, 0.0001)
        assertEquals(right, actual.right, 0.0001)
        assertEquals(bottom, actual.bottom, 0.0001)
    }
}
