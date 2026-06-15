package com.flightalert.ui.map

import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationAirportFeature
import com.flightalert.data.AviationGeoBounds
import com.flightalert.data.AviationLayerBounds
import com.flightalert.data.AviationLayerKind
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationLayerState
import com.flightalert.data.AviationLayerStatus
import com.flightalert.data.AviationOceanicTrack
import com.flightalert.data.api.AviationLayerClient
import com.flightalert.ui.map.render.AviationLayerVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AviationLayerControllerTest {
    @Test
    fun turning_layers_off_keeps_last_real_snapshot_for_instant_reenable() {
        val fetches = mutableListOf<FetchFlags>()
        val controller = controller(fetches)

        controller.request_if_needed(VIEWPORT, RESTRICTED_ON)
        val loaded = controller.snapshot

        controller.on_visibility_changed(LAYERS_OFF)
        assertEquals("Layers off", controller.status_text)
        assertEquals(listOf(FetchFlags(restricted = true), ALL_LAYERS), fetches)
        val warmed = controller.snapshot
        assertEquals(listOf("restricted-2"), warmed?.restricted_airspaces?.map { it.name })
        assertEquals(listOf("atc-2"), warmed?.atc_boundaries?.map { it.name })
        assertEquals(listOf("airport-2"), warmed?.airports?.map { it.ident })
        assertEquals(listOf("oceanic-2"), warmed?.oceanic_tracks?.map { it.name })

        controller.on_visibility_changed(RESTRICTED_ON)
        assertSame(warmed, controller.snapshot)
        assertEquals(2, fetches.size)
        assertEquals("1 aviation layer loaded", controller.status_text)
    }

    @Test
    fun layers_off_warms_real_aviation_layer_data_without_enabling_display() {
        val fetches = mutableListOf<FetchFlags>()
        val controller = controller(fetches)

        controller.request_if_needed(VIEWPORT, LAYERS_OFF)

        assertEquals(listOf(ALL_LAYERS), fetches)
        assertEquals("Layers off", controller.status_text)
        assertEquals(listOf("restricted-1"), controller.snapshot?.restricted_airspaces?.map { it.name })
        assertEquals(listOf("atc-1"), controller.snapshot?.atc_boundaries?.map { it.name })
        assertEquals(listOf("airport-1"), controller.snapshot?.airports?.map { it.ident })
        assertEquals(listOf("oceanic-1"), controller.snapshot?.oceanic_tracks?.map { it.name })
    }

    @Test
    fun all_layer_warm_fetch_keeps_real_cached_layer_data_available() {
        val fetches = mutableListOf<FetchFlags>()
        val controller = controller(fetches)

        controller.request_if_needed(VIEWPORT, ATC_ON)
        controller.on_visibility_changed(LAYERS_OFF)

        assertEquals(listOf(FetchFlags(atc = true), ALL_LAYERS), fetches)
        assertEquals(listOf("atc-2"), controller.snapshot?.atc_boundaries?.map { it.name })
        assertEquals(listOf("restricted-2"), controller.snapshot?.restricted_airspaces?.map { it.name })
        assertEquals(listOf("airport-2"), controller.snapshot?.airports?.map { it.ident })
        assertEquals(listOf("oceanic-2"), controller.snapshot?.oceanic_tracks?.map { it.name })
        assertEquals("Layers off", controller.status_text)
    }

    @Test
    fun wide_layer_viewports_fetch_world_longitude_bounds_instead_of_skipping_layers() {
        val fetches = mutableListOf<FetchFlags>()
        val fetched_bounds = mutableListOf<AviationLayerBounds>()
        val controller = controller(
            fetches = fetches,
            fetched_bounds = fetched_bounds,
            visible_bounds_provider = { null }
        )

        controller.request_if_needed(WIDE_VIEWPORT, RESTRICTED_ON)

        assertEquals(listOf(FetchFlags(restricted = true)), fetches)
        val bounds = fetched_bounds.single()
        assertEquals(-180.0, bounds.min_lon, 0.0)
        assertEquals(180.0, bounds.max_lon, 0.0)
        assertTrue(bounds.min_lat < bounds.max_lat)
    }

    private fun controller(
        fetches: MutableList<FetchFlags>,
        fetched_bounds: MutableList<AviationLayerBounds>? = null,
        visible_bounds_provider: (Viewport) -> Bounds? = { VISIBLE_BOUNDS }
    ): AviationLayerController {
        return AviationLayerController(
            client = AviationLayerClient("test"),
            run_in_background = { task -> task() },
            post_to_main = { task -> task() },
            request_redraw = {},
            current_viewport = { VIEWPORT },
            visible_bounds = visible_bounds_provider,
            refresh_ms = 60_000L,
            bounds_padding_fraction = 0.5,
            now_ms = { 1_000L },
            fetch_layers = { bounds: AviationLayerBounds, atc: Boolean, restricted: Boolean, airports: Boolean, oceanic: Boolean ->
                fetched_bounds?.add(bounds)
                val flags = FetchFlags(atc = atc, restricted = restricted, airports = airports, oceanic = oceanic)
                fetches += flags
                snapshot(flags, fetches.size)
            }
        )
    }

    private fun snapshot(flags: FetchFlags, index: Int): AviationLayerSnapshot {
        val atc = if (flags.atc) listOf(airspace("atc-$index")) else emptyList()
        val restricted = if (flags.restricted) listOf(airspace("restricted-$index")) else emptyList()
        val airports = if (flags.airports) listOf(airport("airport-$index")) else emptyList()
        val oceanic = if (flags.oceanic) listOf(oceanic("oceanic-$index")) else emptyList()
        val statuses = buildMap {
            if (flags.atc) put(AviationLayerKind.ATC_BOUNDARIES, AviationLayerStatus(AviationLayerState.LOADED, "loaded"))
            if (flags.restricted) put(AviationLayerKind.RESTRICTED_AIRSPACES, AviationLayerStatus(AviationLayerState.LOADED, "loaded"))
            if (flags.airports) put(AviationLayerKind.AIRPORTS, AviationLayerStatus(AviationLayerState.LOADED, "loaded"))
            if (flags.oceanic) put(AviationLayerKind.OCEANIC_TRACKS, AviationLayerStatus(AviationLayerState.LOADED, "loaded"))
        }
        return AviationLayerSnapshot(
            atc_boundaries = atc,
            restricted_airspaces = restricted,
            airports = airports,
            oceanic_tracks = oceanic,
            statuses = statuses,
            fetched_at_ms = index.toLong()
        )
    }

    private fun airspace(name: String): AviationAirspaceFeature {
        return AviationAirspaceFeature(
            name = name,
            type = "TEST",
            lower_limit = null,
            upper_limit = null,
            schedule = null,
            city = null,
            state = null,
            rings = listOf(TEST_RING),
            bounds = TEST_BOUNDS
        )
    }

    private fun airport(name: String): AviationAirportFeature {
        return AviationAirportFeature(
            ident = name,
            name = name,
            type = "AIRPORT",
            military = false,
            lat = 40.7,
            lon = -73.9
        )
    }

    private fun oceanic(name: String): AviationOceanicTrack {
        return AviationOceanicTrack(
            name = name,
            source = "test",
            active_window = null,
            points = TEST_RING,
            bounds = TEST_BOUNDS
        )
    }

    private data class FetchFlags(
        val atc: Boolean = false,
        val restricted: Boolean = false,
        val airports: Boolean = false,
        val oceanic: Boolean = false
    )

    private companion object {
        val VIEWPORT = Viewport(
            zoom = 8.4,
            center_x = MapProjection.lat_lon_to_world(40.73, -73.93, 8.4).x,
            center_y = MapProjection.lat_lon_to_world(40.73, -73.93, 8.4).y,
            width = 1080f,
            height = 1920f
        )
        val WIDE_VIEWPORT = Viewport(
            zoom = 3.0,
            center_x = MapProjection.lat_lon_to_world(39.1, -96.0, 3.0).x,
            center_y = MapProjection.lat_lon_to_world(39.1, -96.0, 3.0).y,
            width = 2400f,
            height = 1200f
        )
        val VISIBLE_BOUNDS = Bounds(40.0, -74.5, 41.2, -72.9)
        val TEST_RING = listOf(
            AviationLayerPoint(40.6, -74.0),
            AviationLayerPoint(40.8, -74.0),
            AviationLayerPoint(40.8, -73.8),
            AviationLayerPoint(40.6, -73.8)
        )
        val TEST_BOUNDS = AviationGeoBounds(40.6, -74.0, 40.8, -73.8)
        val LAYERS_OFF = AviationLayerVisibility(
            restricted_airspaces_enabled = false,
            atc_boundaries_enabled = false,
            oceanic_tracks_enabled = false,
            airport_labels_enabled = false
        )
        val RESTRICTED_ON = LAYERS_OFF.copy(restricted_airspaces_enabled = true)
        val ATC_ON = LAYERS_OFF.copy(atc_boundaries_enabled = true)
        val ALL_LAYERS = FetchFlags(atc = true, restricted = true, airports = true, oceanic = true)
    }
}
