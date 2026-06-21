package com.flightalert

// One-file consolidation branch: original Kotlin sources are concatenated below.
// Android resources, assets, the manifest, and Gradle files remain separate because the platform needs them.

import android.graphics.Color
import java.util.Locale
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test


// Source: app/src/test/java/com/flightalert/data/api/AirplanesLiveMetadataParserTest.kt

class AirplanesLiveMetadataParserTest {
    @Test
    fun normalized_year_accepts_alias_values_from_selected_aircraft_payloads() {
        assertEquals("2013", normalized_year("Mfr Year 2013"))
        assertEquals("2008", normalized_year("manufacturedYear=2008"))
    }

    @Test
    fun trace_metadata_can_fill_missing_live_year_without_replacing_identity() {
        val live = AirplanesLiveMetadata(
            source_name = "Airplanes.Live",
            registration = "D-AIKS",
            type_code = "A333",
            description = "AIRBUS A-330-300",
            owner_operator = null,
            operator_code = "DLH",
            year = null
        )
        val trace = AirplanesLiveMetadata(
            source_name = "Airplanes.Live trace_recent",
            registration = null,
            type_code = null,
            description = null,
            owner_operator = "Lufthansa",
            operator_code = null,
            year = "2008"
        )

        val merged = live.merge_airplanes_live(trace)

        assertEquals("D-AIKS", merged?.registration)
        assertEquals("A333", merged?.type_code)
        assertEquals("Lufthansa", merged?.owner_operator)
        assertEquals("2008", merged?.year)
        assertEquals("Airplanes.Live + Airplanes.Live trace_recent", merged?.source_name)
        assertTrue(merged?.has_core_metadata == true)
    }
}

// Source: app/src/test/java/com/flightalert/service/AlertAircraftClassifierTest.kt

class AlertAircraftClassifierTest {
    @Test
    fun inside_alert_volume_is_extreme_priority_when_priority_tracking_is_enabled() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 420.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true
        )

        assertNotNull(aircraft)
        assertTrue(aircraft!!.is_hazard)
        assertTrue(aircraft.is_extreme_priority)
    }

    @Test
    fun aircraft_inside_horizontal_range_is_not_extreme_when_vertical_separation_is_too_large() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 2000.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true
        )

        assertNotNull(aircraft)
        assertFalse(aircraft!!.is_hazard)
        assertFalse(aircraft.is_extreme_priority)
    }

    @Test
    fun missing_aircraft_altitude_is_not_classified_as_safe_or_extreme() {
        val aircraft = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = feet_to_meters(500.0),
            altitude_meters = null,
            last_contact_sec = NOW_EPOCH_SECONDS,
            position_time_sec = null,
            own_altitude_feet = 500.0,
            alerts_enabled = true,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 2000f,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )

        assertNull(aircraft)
    }

    @Test
    fun stale_aircraft_inside_alert_volume_is_not_hazard_or_extreme_priority() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            contact_age_seconds = AlertAircraftClassifier.EXTREME_PRIORITY_CONTACT_MAX_AGE_SECONDS + 0.1
        )

        assertNotNull(aircraft)
        assertFalse(aircraft!!.is_hazard)
        assertFalse(aircraft.is_extreme_priority)
    }

    @Test
    fun priority_range_aircraft_is_not_extreme_unless_it_is_inside_alert_volume() {
        val aircraft = classify(
            distance_feet = 1500.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 2000f
        )

        assertNotNull(aircraft)
        assertTrue(aircraft!!.is_priority_range_aircraft)
        assertFalse(aircraft.is_hazard)
        assertFalse(aircraft.is_extreme_priority)
    }

    @Test
    fun persistent_priority_notification_is_allowed_only_for_non_empty_extreme_priority_list() {
        val aircraft = classify(
            distance_feet = 900.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true
        )!!
        val nearby_non_extreme = classify(
            distance_feet = 1500.0,
            altitude_feet = 500.0,
            own_altitude_feet = 500.0,
            alert_distance_feet = 1000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 2000f
        )!!

        assertTrue(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                extreme_priority_aircraft = listOf(aircraft),
                has_notification_permission = true
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                extreme_priority_aircraft = emptyList(),
                has_notification_permission = true
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = false,
                extreme_priority_aircraft = listOf(aircraft),
                has_notification_permission = true
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                extreme_priority_aircraft = listOf(aircraft),
                has_notification_permission = false
            )
        )
        assertFalse(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                extreme_priority_aircraft = listOf(nearby_non_extreme),
                has_notification_permission = true
            )
        )
        assertTrue(
            AlertAircraftClassifier.should_show_persistent_priority_notification(
                alerts_enabled = true,
                extreme_priority_aircraft = listOf(nearby_non_extreme, aircraft),
                has_notification_permission = true
            )
        )
    }

    private fun classify(
        distance_feet: Double,
        altitude_feet: Double,
        own_altitude_feet: Double?,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_enabled: Boolean,
        priority_range_feet: Float = 2000f,
        contact_age_seconds: Double = 1.0
    ): AlertAircraft? {
        return AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = feet_to_meters(distance_feet),
            altitude_meters = feet_to_meters(altitude_feet),
            last_contact_sec = NOW_EPOCH_SECONDS - contact_age_seconds,
            position_time_sec = null,
            own_altitude_feet = own_altitude_feet,
            alerts_enabled = true,
            alert_distance_feet = alert_distance_feet,
            alert_altitude_feet = alert_altitude_feet,
            priority_enabled = priority_enabled,
            priority_range_feet = priority_range_feet,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )
    }

    private fun feet_to_meters(feet: Double): Double = feet / 3.28084

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_800_000_000.0
    }
}

// Source: app/src/test/java/com/flightalert/service/AlertPositionProjectorTest.kt

class AlertPositionProjectorTest {
    @Test
    fun projected_position_can_enter_the_extreme_priority_volume_before_the_reported_position() {
        val start_distance_m = 2_000.0
        val start = AlertPositionProjector.projected_alert_position(
            own_lat = OWN_LAT,
            own_lon = OWN_LON,
            aircraft_lat = OWN_LAT,
            aircraft_lon = START_WEST_LON,
            reported_distance_meters = start_distance_m,
            altitude_meters = 160.0,
            velocity_ms = 250.0,
            track_deg = 90.0,
            vertical_rate_ms = null,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )

        val reported_classification = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = start_distance_m,
            altitude_meters = 160.0,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            own_altitude_feet = 500.0,
            alerts_enabled = true,
            alert_distance_feet = 4_000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 8_000f,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )
        val projected_classification = AlertAircraftClassifier.classify(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = "N12345",
            distance_meters = start.distance_meters,
            altitude_meters = start.altitude_meters,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            own_altitude_feet = 500.0,
            alerts_enabled = true,
            alert_distance_feet = 4_000f,
            alert_altitude_feet = 150f,
            priority_enabled = true,
            priority_range_feet = 8_000f,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            is_estimated_position = start.estimated
        )

        assertTrue(start.estimated)
        assertFalse(reported_classification!!.is_extreme_priority)
        assertTrue(projected_classification!!.is_extreme_priority)
        assertTrue(projected_classification.is_estimated_position)
    }

    @Test
    fun non_projectable_motion_keeps_the_reported_position() {
        val projected = AlertPositionProjector.projected_alert_position(
            own_lat = OWN_LAT,
            own_lon = OWN_LON,
            aircraft_lat = OWN_LAT,
            aircraft_lon = START_WEST_LON,
            reported_distance_meters = 2_000.0,
            altitude_meters = 160.0,
            velocity_ms = null,
            track_deg = 90.0,
            vertical_rate_ms = null,
            position_time_sec = NOW_EPOCH_SECONDS - 4.0,
            last_contact_sec = NOW_EPOCH_SECONDS - 1.0,
            now_epoch_sec = NOW_EPOCH_SECONDS
        )

        assertFalse(projected.estimated)
        assertTrue(projected.distance_meters >= 1_999.0)
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_800_000_000.0
        const val OWN_LAT = 0.0
        const val OWN_LON = 0.0
        const val START_WEST_LON = -0.017986
    }
}

// Source: app/src/test/java/com/flightalert/service/PriorityNotificationPresenterTest.kt

class PriorityNotificationPresenterTest {
    @Test
    fun notification_body_uses_only_extreme_aircraft_and_current_altitude() {
        val non_extreme = alert_aircraft("NEAR1", "N-NEAR", altitude_feet = 900.0, is_extreme = false)
        val lower_extreme = alert_aircraft("LOW1", "N-LOW", altitude_feet = 700.0, is_extreme = true)
        val higher_extreme = alert_aircraft("HIGH1", "N-HIGH", altitude_feet = 1400.0, is_extreme = true)

        val extreme = PriorityNotificationPresenter.extreme_priority_aircraft(
            listOf(non_extreme, higher_extreme, lower_extreme)
        )

        assertEquals(listOf(lower_extreme, higher_extreme), extreme.sortedBy { it.altitude_feet })
        val body = PriorityNotificationPresenter.notification_body(extreme)
        assertTrue(body.contains("N-LOW 700 ft"))
        assertTrue(body.contains("N-HIGH 1400 ft"))
        assertFalse(body.contains("N-NEAR"))
    }

    @Test
    fun notification_body_summarizes_multiple_extreme_aircraft_without_spam_entries() {
        val aircraft = (1..5).map { index ->
            alert_aircraft("EXT$index", "N-$index", altitude_feet = 1000.0 + index, is_extreme = true)
        }

        val body = PriorityNotificationPresenter.notification_body(aircraft)

        assertTrue(body.contains("N-1 1001 ft"))
        assertTrue(body.contains("N-4 1004 ft"))
        assertFalse(body.contains("N-5 1005 ft"))
        assertTrue(body.endsWith("+1 more"))
    }

    @Test
    fun estimated_priority_notification_entries_are_labeled() {
        val aircraft = alert_aircraft("EST1", "N-EST", altitude_feet = 1200.0, is_extreme = true, is_estimated_position = true)

        val body = PriorityNotificationPresenter.notification_body(listOf(aircraft))

        assertEquals("N-EST 1200 ft est.", body)
    }

    private fun alert_aircraft(
        callsign: String,
        registration: String,
        altitude_feet: Double,
        is_extreme: Boolean,
        is_estimated_position: Boolean = false
    ): AlertAircraft {
        return AlertAircraft(
            icao24 = callsign.lowercase(),
            callsign = callsign,
            registration = registration,
            distance_feet = 500.0,
            altitude_feet = altitude_feet,
            vertical_separation_feet = 25.0,
            contact_age_seconds = 1.0,
            is_hazard = is_extreme,
            is_priority_range_aircraft = true,
            is_extreme_priority = is_extreme,
            is_estimated_position = is_estimated_position
        )
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/AviationLayerControllerTest.kt

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

// Source: app/src/test/java/com/flightalert/ui/map/details/AircraftDetailsRowsBuilderTest.kt

class AircraftDetailsRowsBuilderTest {
    @Test
    fun aircraft_identity_rows_show_callsign_and_single_icao_hex_label() {
        val aircraft = aircraft()
        val rows = rows_builder().aircraft_details_rows(aircraft, details = null)
        val labels = rows.map { it.label }

        assertTrue(labels.contains("Callsign"))
        assertTrue(labels.contains("ICAO hex"))
        assertFalse(labels.contains("ICAO"))
        assertFalse(labels.contains("Hex"))
    }

    private fun rows_builder(): AircraftDetailsRowsBuilder {
        val formatter = AircraftTelemetryFormatter(
            measurement_formatter = MapMeasurementFormatter { UnitSystem.IMPERIAL },
            reported_distance_meters = { it.distance_m },
            loading_or_unavailable = ::loading_or_unavailable,
            now_epoch_seconds = { 1_000.0 }
        )
        return AircraftDetailsRowsBuilder(
            telemetry_formatter = formatter,
            is_details_loading_for = { false },
            details_with_trace_origin = { details, _ -> details },
            current_flight_route_details = { details, _ -> details },
            route_trace_context = { aircraft ->
                AircraftRouteTraceContext(
                    aircraft_id = aircraft.icao24.lowercase(Locale.US),
                    selected_trace_aircraft_id = null,
                    trace = null,
                    segments = null,
                    loading = false
                )
            },
            registry_country_label = { _, _, _ -> "United States (ICAO allocation)" },
            format_origin_status = { _, _ -> "Unavailable" },
            current_flight_route_loading = { _, _ -> false },
            reported_distance_meters = { it.distance_m },
            loading_or_unavailable = ::loading_or_unavailable
        )
    }

    private fun aircraft(): Aircraft {
        return Aircraft(
            icao24 = "abc123",
            callsign = "TEST123",
            registration = "N123FA",
            type_code = "B738",
            metadata_seed = null,
            is_military = false,
            lat = 40.0,
            lon = -73.0,
            on_ground = false,
            altitude_m = 10_000.0,
            velocity_ms = 220.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = null,
            position_time_sec = 1_000.0,
            last_contact_sec = 1_000.0,
            distance_m = 500.0
        )
    }

    private fun loading_or_unavailable(loading: Boolean): String {
        return if (loading) "Loading" else "Unavailable"
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/impact/AircraftTraceImpactAnalyzerTest.kt

class AircraftTraceImpactAnalyzerTest {
    @Test
    fun profile_for_known_icao_type_uses_type_specific_benchmark() {
        val profile = AircraftImpactEstimator.profile_for(
            ImpactFacts(
                feed_type_code = "PA44",
                details_type_code = null,
                manufacturer = null,
                model = null,
                category = null,
                on_ground = false
            )
        )

        assertNotNull(profile)
        assertEquals(ImpactProfileBasis.TYPE_SPECIFIC, profile!!.basis)
        assertTrue(profile.label.contains("Piper PA-44"))
    }

    @Test
    fun observed_estimate_uses_trace_phases_when_altitude_and_time_support_them() {
        val profile = pa44_profile()
        val estimate = AircraftTraceImpactAnalyzer.observed_estimate(
            profile = profile,
            segments = listOf(
                TraceSegment(
                    listOf(
                        point(40.000, -75.000, 0L, 0.0, true),
                        point(40.020, -74.950, 300L, 520.0, false),
                        point(40.060, -74.850, 900L, 1520.0, false),
                        point(40.180, -74.350, 1800L, 1525.0, false)
                    )
                )
            ),
            details = null
        )

        assertNotNull(estimate)
        val phases = estimate!!.phase_hours.map { it.phase }.toSet()
        assertTrue(phases.contains(ImpactFlightPhase.CLIMB))
        assertTrue(phases.contains(ImpactFlightPhase.CRUISE))
        assertTrue(estimate.carbon.mid_kg > 0.0)
        assertNull(estimate.full_flight)
    }

    @Test
    fun full_flight_estimate_stays_unavailable_without_real_route_context() {
        val estimate = AircraftTraceImpactAnalyzer.observed_estimate(
            profile = pa44_profile(),
            segments = route_progress_segments(),
            details = null
        )

        assertNotNull(estimate)
        assertNull(estimate!!.full_flight)
    }

    @Test
    fun full_flight_estimate_requires_real_origin_destination_and_credible_progress() {
        val estimate = AircraftTraceImpactAnalyzer.observed_estimate(
            profile = pa44_profile(),
            segments = route_progress_segments(),
            details = details_with_route()
        )

        assertNotNull(estimate)
        assertNotNull(estimate!!.full_flight)
        assertTrue(estimate.full_flight!!.carbon.mid_kg > estimate.carbon.mid_kg)
        assertTrue(estimate.full_flight!!.progress_fraction in 0.2..0.97)
    }

    @Test
    fun presenter_keeps_passenger_count_unavailable_instead_of_inferred() {
        val aircraft = aircraft(type_code = "PA44", category = null)
        val profile = AircraftImpactPresenter.profile_for(aircraft, details = null)
        val rows = AircraftImpactPresenter.rows(
            aircraft = aircraft,
            details = null,
            profile = profile,
            trace = null,
            trace_estimate = null,
            usage_trace = null,
            details_loading = false,
            trace_loading = false,
            units = UnitSystem.IMPERIAL
        )

        assertEquals(
            "Unavailable: passenger count and load factor are not inferred.",
            rows.first { it.first == "Passenger/load" }.second
        )
    }

    @Test
    fun unsupported_profile_is_unavailable_not_loading_when_no_fetch_is_active() {
        val aircraft = aircraft(type_code = null, category = 14)
        val rows = AircraftImpactPresenter.rows(
            aircraft = aircraft,
            details = null,
            profile = null,
            trace = null,
            trace_estimate = null,
            usage_trace = null,
            details_loading = false,
            trace_loading = false,
            units = UnitSystem.IMPERIAL
        )

        assertEquals("Unavailable", rows.first { it.first == "Aircraft class" }.second)
        assertEquals("Unavailable", rows.first { it.first == "Observed CO2 estimate" }.second)
    }

    private fun pa44_profile(): ImpactProfile {
        return AircraftImpactEstimator.profile_for(
            ImpactFacts(
                feed_type_code = "PA44",
                details_type_code = null,
                manufacturer = null,
                model = null,
                category = null,
                on_ground = false
            )
        )!!
    }

    private fun route_progress_segments(): List<TraceSegment> {
        return listOf(
            TraceSegment(
                listOf(
                    point(40.000, -75.000, 0L, 0.0, true),
                    point(40.050, -74.700, 900L, 1100.0, false),
                    point(40.100, -74.300, 1800L, 1500.0, false)
                )
            )
        )
    }

    private fun details_with_route(): AircraftDetails {
        return AircraftDetails(
            icao24 = "abc123",
            registration = "N12345",
            manufacturer = "Piper",
            type = "PA-44-180",
            type_code = "PA44",
            owner = null,
            manufactured_year = null,
            registry_source = "test registry",
            operator_code = null,
            route = "KAAA-KBBB",
            route_updated_epoch_sec = 1L,
            route_source = "test route",
            origin_airport = AirportDetails("KAAA", null, "Origin", "US", null, 40.0, -75.0),
            destination_airport = AirportDetails("KBBB", null, "Destination", "US", null, 40.0, -72.0)
        )
    }

    private fun aircraft(type_code: String?, category: Int?): Aircraft {
        return Aircraft(
            icao24 = "abc123",
            callsign = "TEST123",
            registration = "N12345",
            type_code = type_code,
            metadata_seed = null,
            is_military = false,
            lat = 40.0,
            lon = -75.0,
            on_ground = false,
            altitude_m = 1400.0,
            velocity_ms = 70.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = category,
            position_time_sec = null,
            last_contact_sec = null,
            distance_m = 0.0
        )
    }

    private fun point(lat: Double, lon: Double, epoch: Long, altitude_m: Double?, on_ground: Boolean?): TrackPoint {
        return TrackPoint(
            lat = lat,
            lon = lon,
            epoch_sec = epoch,
            altitude_m = altitude_m,
            track_deg = null,
            on_ground = on_ground
        )
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/PriorityRangeAdjusterTest.kt

class PriorityRangeAdjusterTest {
    @Test
    fun imperial_long_press_changes_horizontal_range_by_clean_feet() {
        val result = PriorityRangeAdjuster.adjusted_feet(
            current_feet = 5000f,
            value = PriorityRangeValue.HORIZONTAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.IMPERIAL
        )

        assertEquals(15000f, result, 0.01f)
    }

    @Test
    fun imperial_long_press_changes_vertical_range_by_clean_feet() {
        val result = PriorityRangeAdjuster.adjusted_feet(
            current_feet = 1000f,
            value = PriorityRangeValue.VERTICAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.IMPERIAL
        )

        assertEquals(10000f, result, 0.01f)
    }

    @Test
    fun metric_long_press_changes_horizontal_range_by_clean_meters() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = meters_to_feet(1500.0).toFloat(),
            value = PriorityRangeValue.HORIZONTAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.METRIC
        )

        assertEquals(4500.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    @Test
    fun metric_tap_snaps_to_clean_meter_grid_from_legacy_feet_value() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = 5000f,
            value = PriorityRangeValue.HORIZONTAL,
            direction = 1f,
            long_press = false,
            units = UnitSystem.METRIC
        )

        assertEquals(1750.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    @Test
    fun metric_vertical_long_press_uses_clean_meter_step_and_respects_maximum() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = meters_to_feet(2500.0).toFloat(),
            value = PriorityRangeValue.VERTICAL,
            direction = 1f,
            long_press = true,
            units = UnitSystem.METRIC
        )

        assertEquals(3000.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    @Test
    fun metric_decrement_respects_minimum() {
        val result_feet = PriorityRangeAdjuster.adjusted_feet(
            current_feet = meters_to_feet(250.0).toFloat(),
            value = PriorityRangeValue.HORIZONTAL,
            direction = -1f,
            long_press = true,
            units = UnitSystem.METRIC
        )

        assertEquals(200.0, feet_to_meters(result_feet.toDouble()), 0.01)
    }

    private fun feet_to_meters(feet: Double): Double = feet / FEET_PER_METER

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    private companion object {
        const val FEET_PER_METER = 3.28084
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/render/AircraftMarkerMorphTest.kt

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

// Source: app/src/test/java/com/flightalert/ui/map/traffic/AircraftFilterEngineTest.kt

class AircraftFilterEngineTest {
    @Test
    fun stats_from_counts_preserves_normal_airborne_summary() {
        val stats = AircraftFilterEngine.stats_from_counts(
            total = 7000,
            matched = 6123,
            filters = AircraftFilterState.DEFAULT
        )

        assertEquals(FilterStats(7000, 6123, "6123 airborne aircraft in current feed"), stats)
    }

    @Test
    fun stats_from_counts_preserves_filtered_summary() {
        val stats = AircraftFilterEngine.stats_from_counts(
            total = 7000,
            matched = 42,
            filters = AircraftFilterState.DEFAULT.copy(aircraft_type = AircraftTypeFilter.MILITARY)
        )

        assertEquals(FilterStats(7000, 42, "42 of 7000 live aircraft match filters"), stats)
    }

    @Test
    fun stats_from_counts_preserves_zero_match_summary() {
        val stats = AircraftFilterEngine.stats_from_counts(
            total = 7000,
            matched = 0,
            filters = AircraftFilterState.DEFAULT.copy(search_query = "ZZZ")
        )

        assertEquals(FilterStats(7000, 0, "0 of 7000 live aircraft match filters"), stats)
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/traffic/AircraftPositionProjectorTest.kt

class AircraftPositionProjectorTest {
    @Test
    fun fresh_aircraft_starts_at_reported_position_but_can_still_animate() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertFalse(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertEquals(0.0, display.point.lon, 0.000001)
        assertEquals(MAX_PROJECTION_SECONDS, display.motion_remaining_seconds, 0.000001)
    }

    @Test
    fun stale_aircraft_keeps_projecting_from_the_latest_real_report_time() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS - MAX_PROJECTION_SECONDS - 120.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )
        val later = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS + 30.0,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertTrue(display.projected)
        assertEquals(MAX_PROJECTION_SECONDS, display.motion_remaining_seconds, 0.000001)
        assertTrue(later.projected)
        assertEquals(MAX_PROJECTION_SECONDS, later.motion_remaining_seconds, 0.000001)
        assertEquals(display.point.lat, later.point.lat, 0.000001)
        assertTrue(later.point.lon > display.point.lon)
    }

    @Test
    fun plausible_aircraft_motion_reports_cache_motion_budget() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS - 10.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertTrue(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertTrue(display.point.lon > 0.017)
        assertEquals(MAX_PROJECTION_SECONDS, display.motion_remaining_seconds, 0.000001)
    }

    @Test
    fun implausible_speed_keeps_the_real_reported_position() {
        val aircraft = aircraft(
            velocity_ms = 1_500.0,
            track_deg = 90.0,
            position_time_sec = NOW_EPOCH_SECONDS - 10.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertFalse(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertEquals(0.0, display.point.lon, 0.000001)
        assertEquals(0.0, display.motion_remaining_seconds, 0.000001)
    }

    @Test
    fun missing_position_time_does_not_project_from_contact_time() {
        val aircraft = aircraft(
            velocity_ms = 200.0,
            track_deg = 90.0,
            position_time_sec = null,
            last_contact_sec = NOW_EPOCH_SECONDS - 10.0
        )

        val display = AircraftPositionProjector.projected_display_position(
            aircraft = aircraft,
            now_epoch_sec = NOW_EPOCH_SECONDS,
            max_projection_seconds = MAX_PROJECTION_SECONDS
        )

        assertFalse(display.projected)
        assertEquals(0.0, display.point.lat, 0.000001)
        assertEquals(0.0, display.point.lon, 0.000001)
        assertEquals(0.0, display.motion_remaining_seconds, 0.000001)
    }

    private fun aircraft(
        velocity_ms: Double,
        track_deg: Double,
        position_time_sec: Double?,
        last_contact_sec: Double? = position_time_sec
    ): Aircraft {
        return Aircraft(
            icao24 = "abc123",
            callsign = "TEST1",
            registration = null,
            type_code = null,
            metadata_seed = null,
            is_military = false,
            lat = 0.0,
            lon = 0.0,
            on_ground = false,
            altitude_m = 10_000.0,
            velocity_ms = velocity_ms,
            track_deg = track_deg,
            vertical_rate_ms = null,
            category = null,
            position_time_sec = position_time_sec,
            last_contact_sec = last_contact_sec,
            distance_m = 0.0
        )
    }

    private companion object {
        const val NOW_EPOCH_SECONDS = 1_800_000_000.0
        const val MAX_PROJECTION_SECONDS = 10.0 * 60.0
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/traffic/AircraftRegistryResolverTest.kt

class AircraftRegistryResolverTest {
    @Test
    fun us_military_icao_allocation_resolves_past_civil_n_number_range() {
        val match = AircraftRegistryResolver.country_for(registration = null, icao24 = " ae1234 ")

        assertEquals(RegistryCountrySource.ICAO_ALLOCATION, match?.source)
        assertEquals("US", match?.country?.iso_code)
        assertEquals("United States", match?.country?.name)
    }

    @Test
    fun non_us_military_icao_allocations_resolve_when_no_registration_is_present() {
        val israel = AircraftRegistryResolver.country_for(registration = null, icao24 = "738123")
        val turkey = AircraftRegistryResolver.country_for(registration = null, icao24 = "4B8123")

        assertEquals(RegistryCountrySource.ICAO_ALLOCATION, israel?.source)
        assertEquals("IL", israel?.country?.iso_code)
        assertEquals("Israel", israel?.country?.name)
        assertEquals(RegistryCountrySource.ICAO_ALLOCATION, turkey?.source)
        assertEquals("TR", turkey?.country?.iso_code)
        assertEquals("Turkiye", turkey?.country?.name)
    }

    @Test
    fun registration_prefix_takes_priority_over_icao_allocation() {
        val match = AircraftRegistryResolver.country_for(registration = "C-FABC", icao24 = "AE1234")

        assertEquals(RegistryCountrySource.REGISTRATION, match?.source)
        assertEquals("CA", match?.country?.iso_code)
    }

    @Test
    fun non_icao_addresses_do_not_get_allocation_fallback() {
        val match = AircraftRegistryResolver.country_for(registration = null, icao24 = "~AE1234")

        assertNull(match)
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/traffic/TrafficCacheControllerTest.kt

class TrafficCacheControllerTest {
    @Test
    fun cached_traffic_returns_last_prepared_cache_without_rebuilding_dirty_data() {
        var snapshot_calls = 0
        val aircraft = aircraft("a1")
        val controller = TrafficCacheController(
            all_aircraft_snapshot = {
                snapshot_calls++
                listOf(aircraft)
            },
            passes_aircraft_filters = { _, _ -> true },
            distance_meters = { it.distance_m },
            is_hazard_aircraft = { false },
            aircraft_icao_key = { it.icao24.lowercase() },
            spatial_entry_for = { item, now -> entry(item, now) },
            alerts_enabled = { false },
            now_epoch_seconds = { 1_000.0 },
            now_elapsed_ms = { 1_234L }
        )

        controller.mark_dirty()

        assertEquals(0, controller.cached_traffic().total)
        assertEquals(0, snapshot_calls)

        controller.publish_cached_traffic(controller.build_cached_traffic(listOf(aircraft)))

        assertEquals(1, controller.cached_traffic().total)
        assertEquals(0, snapshot_calls)
        assertFalse(controller.is_dirty())
    }

    @Test
    fun cached_traffic_tracks_nearest_filtered_aircraft_by_reported_distance() {
        val far = aircraft("far").copy(distance_m = 4_000.0)
        val filtered_near = aircraft("filtered").copy(distance_m = 100.0)
        val near = aircraft("near").copy(distance_m = 450.0)
        val controller = TrafficCacheController(
            all_aircraft_snapshot = { listOf(far, filtered_near, near) },
            passes_aircraft_filters = { item, _ -> item.icao24 != filtered_near.icao24 },
            distance_meters = { it.distance_m },
            is_hazard_aircraft = { false },
            aircraft_icao_key = { it.icao24.lowercase() },
            spatial_entry_for = { item, now -> entry(item, now) },
            alerts_enabled = { false },
            now_epoch_seconds = { 1_000.0 },
            now_elapsed_ms = { 1_234L }
        )

        val cache = controller.build_cached_traffic(listOf(far, filtered_near, near))

        assertEquals(near, cache.nearest_aircraft)
        assertEquals(listOf(far, near), cache.aircraft)
    }

    private fun aircraft(hex: String): Aircraft {
        return Aircraft(
            icao24 = hex,
            callsign = "TEST$hex",
            registration = null,
            type_code = "B738",
            metadata_seed = null,
            is_military = false,
            lat = 40.0,
            lon = -73.0,
            on_ground = false,
            altitude_m = 10_000.0,
            velocity_ms = 220.0,
            track_deg = 90.0,
            vertical_rate_ms = 0.0,
            category = null,
            position_time_sec = 1_000.0,
            last_contact_sec = 1_000.0,
            distance_m = 0.0
        )
    }

    private fun entry(aircraft: Aircraft, now_epoch_sec: Double): TrafficSpatialEntry {
        return TrafficSpatialEntry(
            aircraft = aircraft,
            world_x_zoom_zero = 0.5,
            world_y_zoom_zero = 0.5,
            projected_velocity_x_zoom_zero = 0.0,
            projected_velocity_y_zoom_zero = 0.0,
            projected_motion_remaining_sec = 60.0,
            projection_epoch_sec = now_epoch_sec
        )
    }
}

// Source: app/src/test/java/com/flightalert/ui/map/traffic/TrafficOverlayStateBuilderTest.kt

class TrafficOverlayStateBuilderTest {
    @Test
    fun active_dense_gestures_keep_prepared_dots_and_symbol_states_for_visual_parity() {
        val state = builder(now_elapsed_ms = 1_000L).traffic_overlay_state(
            frame(
                interaction = TrafficOverlayInteraction(
                    pinch_in_progress = true,
                    drag_started = false,
                    last_map_interaction_ms = 1_000L
                )
            )
        )

        assertNotNull(state.dot_batch)
        assertEquals(DENSE_AIRCRAFT_COUNT, state.dot_batch?.visible_count)
        assertTrue(state.interaction_active)
        assertTrue(state.aircraft.isNotEmpty())
    }

    @Test
    fun idle_dense_transition_still_allows_aircraft_symbols_after_gesture_settles() {
        val state = builder(now_elapsed_ms = 10_000L).traffic_overlay_state(
            frame(
                interaction = TrafficOverlayInteraction(
                    pinch_in_progress = false,
                    drag_started = false,
                    last_map_interaction_ms = 0L
                )
            )
        )

        assertNotNull(state.dot_batch)
        assertEquals(DENSE_AIRCRAFT_COUNT, state.dot_batch?.visible_count)
        assertTrue(state.aircraft.isNotEmpty())
    }

    private fun builder(now_elapsed_ms: Long): TrafficOverlayStateBuilder {
        return TrafficOverlayStateBuilder(
            dp = { it },
            aircraft_color = { Color.WHITE },
            aircraft_appearance_progress = { _, _ -> 1f },
            aircraft_appearance = { _, _ -> null },
            display_aircraft_position = { aircraft, _ -> GeoPoint(aircraft.lat, aircraft.lon) },
            spatial_entry_for = { aircraft, now -> aircraft.entry(now) },
            lat_lon_to_world = { lat, lon, _ -> WorldPoint(lon, lat) },
            now_elapsed_ms = { now_elapsed_ms }
        )
    }

    private fun frame(interaction: TrafficOverlayInteraction): TrafficOverlayFrame {
        return TrafficOverlayFrame(
            viewport = dense_viewport(),
            cache = cached_dense_traffic(),
            now_epoch_sec = 1_000.0,
            selection = TrafficOverlaySelection(
                selected_aircraft_id = null,
                selected_aircraft_key = null,
                selected_aircraft_snapshot = null,
                path_visible = false,
                has_selected_flight_path = false
            ),
            filters_restrict_aircraft = false,
            map_source = TileSource.STREET,
            visual_theme_key = 0,
            content_width = PHONE_WIDTH,
            content_height = PHONE_HEIGHT,
            label_avoid_rects = emptyList(),
            interaction = interaction
        )
    }

    private fun cached_dense_traffic(): CachedTraffic {
        val aircraft = (0 until DENSE_AIRCRAFT_COUNT).map { index ->
            Aircraft(
                icao24 = index.toString(16).padStart(6, '0'),
                callsign = "T$index",
                registration = null,
                type_code = null,
                metadata_seed = null,
                is_military = false,
                lat = 128.0 + ((index / 40) - 8) * 0.001,
                lon = 128.0 + ((index % 40) - 20) * 0.001,
                on_ground = false,
                altitude_m = 9_000.0,
                velocity_ms = 210.0,
                track_deg = 90.0,
                vertical_rate_ms = 0.0,
                category = null,
                position_time_sec = 1_000.0,
                last_contact_sec = 1_000.0,
                distance_m = 0.0
            )
        }
        val entries = aircraft.map { it.entry(1_000.0) }
        return CachedTraffic(
            aircraft = aircraft,
            nearest_aircraft = aircraft.firstOrNull(),
            entries = entries,
            spatial_index = TrafficSpatialIndex(entries),
            world_dot_batch = TrafficWorldDotBatch.empty(),
            total = aircraft.size,
            hazard_present = false,
            extreme_priority_aircraft = emptyList(),
            extreme_priority_keys = emptySet(),
            max_projected_speed_zoom_zero = 0.0
        )
    }

    private fun Aircraft.entry(now_epoch_sec: Double): TrafficSpatialEntry {
        return TrafficSpatialEntry(
            aircraft = this,
            world_x_zoom_zero = lon,
            world_y_zoom_zero = lat,
            projected_velocity_x_zoom_zero = 0.0,
            projected_velocity_y_zoom_zero = 0.0,
            projected_motion_remaining_sec = 30.0,
            projection_epoch_sec = now_epoch_sec
        )
    }

    private fun dense_viewport(): Viewport {
        val scale = 2.0.pow(DENSE_ZOOM)
        return Viewport(
            zoom = DENSE_ZOOM,
            center_x = 128.0 * scale,
            center_y = 128.0 * scale,
            width = PHONE_WIDTH,
            height = PHONE_HEIGHT
        )
    }

    private companion object {
        const val DENSE_ZOOM = 8.4
        const val DENSE_AIRCRAFT_COUNT = 700
        const val PHONE_WIDTH = 1080f
        const val PHONE_HEIGHT = 1920f
    }
}
