@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.details

import android.graphics.Bitmap
import android.os.SystemClock
import com.flightalert.DETAILS_PREFETCH_IDLE_DELAY_MS
import com.flightalert.DETAILS_PREFETCH_INTERVAL_MS
import com.flightalert.DETAILS_PREFETCH_MAX_IN_FLIGHT
import com.flightalert.DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES
import com.flightalert.DETAILS_PREFETCH_MIN_ZOOM
import com.flightalert.DETAILS_PREFETCH_SCAN_LIMIT
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftMetadataSeed
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AircraftTelemetry
import com.flightalert.flight.AircraftRoutePresenter
import com.flightalert.flight.AircraftRouteTraceContext
import com.flightalert.flight.CurrentRouteValidator
import com.flightalert.flight.FlightTrace
import com.flightalert.flight.TraceSegment
import com.flightalert.flight.TrackPoint
import com.flightalert.map.MapMeasurementFormatter
import com.flightalert.map.ScreenPoint
import com.flightalert.map.UnitSystem
import com.flightalert.map.Viewport
import com.flightalert.traffic.CachedTraffic
import com.flightalert.traffic.TrafficOverlayState
import com.flightalert.traffic.TrafficSpatialEntry
import com.flightalert.ui.AircraftFeedMode
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

internal class AircraftDetailsContentBuilder(
    private val muted_color: () -> Int,
    private val impact_score_color: (ImpactProfile) -> Int,
    private val loading_or_unavailable: (Boolean) -> String,
    private val is_details_loading_for: (Aircraft) -> Boolean,
    private val is_flight_path_loading: (Aircraft) -> Boolean,
    private val current_impact_trace_for: (Aircraft) -> ImpactTrace?,
    private val usage_trace_for: (Aircraft) -> FlightTrace?,
    private val units: () -> UnitSystem
) {
    fun impact_panel_state(
        aircraft: Aircraft?,
        details: AircraftDetails?
    ): AircraftImpactPanelState {
        if (aircraft == null) {
            return AircraftImpactPanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                show_trace_co2 = false,
                co2_text = "Unavailable",
                score_label = "CARBON IMPACT SCORE",
                score_text = "Unavailable",
                score_color = muted_color(),
                rows = emptyList()
            )
        }

        val details_loading = is_details_loading_for(aircraft)
        val profile = AircraftImpactPresenter.profile_for(aircraft, details)
        val trace = current_impact_trace_for(aircraft)
        val trace_loading = is_flight_path_loading(aircraft)
        val usage_trace = usage_trace_for(aircraft)
        val trace_estimate = profile?.let {
            AircraftTraceImpactAnalyzer.observed_estimate(it, usage_trace?.segments, details)
        }
        val loading = details_loading || trace_loading
        val score_value = when {
            trace_estimate != null -> AircraftImpactEstimator.score_for_kg_per_hour(trace_estimate.average_kg_per_hour_mid)
            profile != null -> AircraftImpactEstimator.score(profile)
            else -> null
        }
        val score_text = score_value?.let { "$it / 100" } ?: loading_or_unavailable(details_loading)
        val co2_text = when {
            trace_estimate != null -> AircraftImpactPresenter.carbon_range(trace_estimate.carbon)
            profile != null && trace != null -> AircraftImpactPresenter.carbon_range(
                profile.carbon_for_hours(
                    trace.hours
                )
            )

            trace_loading -> "Loading"
            profile != null -> AircraftImpactPresenter.kg_range(
                profile.low_co2_kg_per_hour(),
                profile.high_co2_kg_per_hour()
            )

            else -> loading_or_unavailable(loading)
        }
        return AircraftImpactPanelState(
            selected_aircraft_available = true,
            status = AircraftImpactPresenter.status(profile, trace, details_loading, trace_loading),
            show_trace_co2 = trace != null,
            co2_text = co2_text,
            score_label = if (trace_estimate != null) "OBSERVED IMPACT SCORE" else "CLASS INTENSITY SCORE",
            score_text = score_text,
            score_color = if (profile == null) muted_color() else impact_score_color(profile),
            rows = AircraftImpactPresenter.rows(
                aircraft = aircraft,
                details = details,
                profile = profile,
                trace = trace,
                trace_estimate = trace_estimate,
                usage_trace = usage_trace,
                details_loading = details_loading,
                trace_loading = trace_loading,
                units = units()
            )
                .map { (label, value) -> AircraftDetailsRow(label, value) }
        )
    }

    fun usage_panel_state(aircraft: Aircraft?): AircraftUsagePanelState {
        if (aircraft == null) {
            return AircraftUsagePanelState(
                selected_aircraft_available = false,
                status = "Unavailable",
                unavailable_message = null,
                stat_rows = emptyList(),
                stats = null
            )
        }

        val trace = usage_trace_for(aircraft)
        val loading = trace == null && is_flight_path_loading(aircraft)
        val status = when {
            trace != null -> "Trace-derived from ${trace.source}"
            loading -> "Loading trace usage"
            else -> "Unavailable from trace source"
        }
        if (trace == null) {
            return AircraftUsagePanelState(
                selected_aircraft_available = true,
                status = status,
                unavailable_message = if (loading) "Loading" else "Unavailable: no real trace history was retrieved for this aircraft.",
                stat_rows = emptyList(),
                stats = null
            )
        }

        val stats = AircraftUsageAnalyzer.stats_for(trace)
        if (stats.flight_count == 0) {
            return AircraftUsagePanelState(
                selected_aircraft_available = true,
                status = status,
                unavailable_message = "Unavailable: trace data does not contain usable completed or current flight segments.",
                stat_rows = emptyList(),
                stats = null
            )
        }

        return AircraftUsagePanelState(
            selected_aircraft_available = true,
            status = status,
            unavailable_message = null,
            stat_rows = listOf(
                AircraftDetailsRow(
                    "Current week",
                    "${stats.week_flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.week_hours)}"
                ),
                AircraftDetailsRow(
                    "Trace window total",
                    "${stats.flight_count} flights  ${AircraftUsageAnalyzer.format_hours(stats.total_hours)}"
                ),
                AircraftDetailsRow("Trace window", stats.window_label)
            ),
            stats = stats
        )
    }
}

internal data class AircraftDetailCandidate(
    val source_name: String,
    val fetch: () -> AircraftDetails?
)

// Coordinates details, photo, and feed seed lookups so FlightMapView only reacts to finished callbacks.
class AircraftDetailsCoordinator(
    private val aircraft_details_client: AircraftDetailsClient,
    private val aircraft_photo_fetcher: AircraftPhotoFetcher,
    private val executor: Executor,
    private val post_to_main: (() -> Unit) -> Unit
) {
    // Start metadata and photo work around one selected aircraft, then post only callbacks that still match its token.
    fun request_aircraft_details(
        aircraft: Aircraft,
        request_token: Long,
        is_current_request: (String, Long) -> Boolean,
        is_photo_available: () -> Boolean,
        on_details: (String, Long, AircraftDetails, Boolean) -> Unit,
        on_details_unavailable: (String, Long) -> Unit,
        on_photo_found: (String, Long, AircraftPhotoResult.Found, Boolean) -> Unit,
        on_photo_unavailable: (String, Long) -> Unit,
        on_photo_search_done: (String, Long) -> Unit
    ) {
        val requested_id = aircraft.icao24
        val candidates = detail_candidates_for_aircraft(aircraft)
        val remaining = AtomicInteger(candidates.size)
        val photo_in_flight = AtomicInteger(0)
        val photo_found = AtomicBoolean(false)
        val photo_search_done = AtomicBoolean(false)
        val detail_lock = Any()
        val photo_keys = mutableSetOf<String>()
        var merged_details: AircraftDetails? = null

        // Wait for all real photo paths to fail before telling the UI no photo is available.
        fun maybe_post_final_photo_unavailable() {
            if (remaining.get() != 0 || photo_in_flight.get() != 0) return
            if (!photo_search_done.compareAndSet(false, true)) return
            post_to_main {
                if (!is_current_request(requested_id, request_token)) return@post_to_main
                if (!photo_found.get() && !is_photo_available()) {
                    on_photo_unavailable(requested_id, request_token)
                }
                on_photo_search_done(requested_id, request_token)
            }
        }

        // Let an honest fallback render quickly while exact exterior lookups continue in parallel.
        fun lookup_photo(details: AircraftDetails) {
            val key = photo_lookup_key(aircraft, details)
            val exact_key = "exact|$key"
            val fallback_key = "fallback|$key"
            val should_run_exact = synchronized(photo_keys) { photo_keys.add(exact_key) }
            val should_run_fallback = synchronized(photo_keys) { photo_keys.add(fallback_key) }
            if (!should_run_exact && !should_run_fallback) {
                maybe_post_final_photo_unavailable()
                return
            }
            photo_in_flight.addAndGet(listOf(should_run_exact, should_run_fallback).count { it })

            if (should_run_fallback) {
                executor.execute {
                    val photo = runCatching {
                        aircraft_photo_fetcher.fetch_fallback_photo(
                            aircraft,
                            details
                        )
                    }
                        .getOrElse { AircraftPhotoResult.Unavailable("Exact, representative, and search photos unavailable") }
                    photo_in_flight.decrementAndGet()
                    when (photo) {
                        is AircraftPhotoResult.Found -> {
                            val first_photo = photo_found.compareAndSet(false, true)
                            if (first_photo || photo.quality == PhotoQuality.EXACT) {
                                post_to_main {
                                    on_photo_found(
                                        requested_id,
                                        request_token,
                                        photo,
                                        photo.quality == PhotoQuality.EXACT
                                    )
                                }
                            }
                            maybe_post_final_photo_unavailable()
                        }

                        is AircraftPhotoResult.Unavailable -> maybe_post_final_photo_unavailable()
                    }
                }
            }

            if (should_run_exact) {
                executor.execute {
                    val exact = runCatching {
                        aircraft_photo_fetcher.fetch_exact_aircraft_photo(
                            aircraft,
                            details
                        )
                    }.getOrNull()
                    photo_in_flight.decrementAndGet()
                    if (exact != null) {
                        photo_found.set(true)
                        post_to_main { on_photo_found(requested_id, request_token, exact, true) }
                        maybe_post_final_photo_unavailable()
                    } else {
                        maybe_post_final_photo_unavailable()
                    }
                }
            }
        }

        candidates.forEach { candidate ->
            executor.execute {
                val details = runCatching { candidate.fetch() }.getOrNull()
                val merged = synchronized(detail_lock) {
                    if (details != null) {
                        merged_details =
                            merged_details?.let { merge_aircraft_details(it, details) } ?: details
                    }
                    merged_details
                }
                if (merged != null) {
                    val still_loading = remaining.get() > 1
                    post_to_main { on_details(requested_id, request_token, merged, still_loading) }
                    lookup_photo(merged)
                    remaining.decrementAndGet()
                    maybe_post_final_photo_unavailable()
                } else {
                    val still_loading = remaining.decrementAndGet() > 0
                    if (!still_loading) {
                        post_to_main { on_details_unavailable(requested_id, request_token) }
                        maybe_post_final_photo_unavailable()
                    }
                }
            }
        }
    }

    fun seeded_aircraft_details(aircraft: Aircraft): AircraftDetails? {
        val seed = aircraft.metadata_seed
        if (seed == null && aircraft.telemetry?.has_values != true) return null
        return details_from_feed_seed(aircraft, seed)
    }

    fun request_photo_gallery(
        aircraft: Aircraft,
        details: AircraftDetails,
        request_token: Long,
        is_current_request: (String, Long) -> Boolean,
        on_gallery: (String, Long, List<AircraftPhotoGalleryItem>) -> Unit
    ) {
        val requested_id = aircraft.icao24
        executor.execute {
            val items = aircraft_photo_fetcher.fetch_gallery(aircraft, details)
            post_to_main {
                if (!is_current_request(requested_id, request_token)) return@post_to_main
                on_gallery(requested_id, request_token, items)
            }
        }
    }

    // Use current feed data as seed metadata, then enrich from documented registries and routes.
    private fun detail_candidates_for_aircraft(aircraft: Aircraft): List<AircraftDetailCandidate> {
        val seed = AircraftDetailCandidate("Live feed seed") {
            seeded_aircraft_details(aircraft)
        }
        val direct = AircraftDetailCandidate("Documented sources") {
            aircraft_details_client.fetch_details(
                hex = aircraft.icao24,
                callsign = aircraft.callsign,
                registration_hint = aircraft.registration,
                metadata_seed = aircraft.metadata_seed,
                telemetry_seed = aircraft.telemetry,
                latitude = aircraft.lat,
                longitude = aircraft.lon
            )
        }
        return listOf(seed, direct)
    }

    private fun details_from_feed_seed(
        aircraft: Aircraft,
        seed: AircraftMetadataSeed?
    ): AircraftDetails {
        return AircraftDetails(
            icao24 = aircraft.icao24.trim().trimStart('~').lowercase(Locale.US),
            registration = normalized_registration(seed?.registration ?: aircraft.registration),
            manufacturer = seed?.manufacturer?.clean_seed_value(),
            type = seed?.type?.clean_seed_value(),
            type_code = seed?.type_code?.clean_seed_value() ?: aircraft.type_code,
            owner = seed?.owner?.clean_seed_value(),
            manufactured_year = seed?.manufactured_year?.clean_seed_value()?.take(4),
            registry_source = seed?.source_name,
            operator_code = seed?.operator_code?.clean_seed_value(),
            route = null,
            route_updated_epoch_sec = null,
            route_source = null,
            origin_airport = null,
            destination_airport = null,
            telemetry = aircraft.telemetry
        )
    }

    private fun photo_lookup_key(aircraft: Aircraft, details: AircraftDetails): String {
        return listOfNotNull(
            normalized_registration(details.registration ?: aircraft.registration),
            details.manufacturer?.trim()?.uppercase(Locale.US),
            details.type?.trim()?.uppercase(Locale.US),
            details.type_code?.trim()?.uppercase(Locale.US),
            details.owner?.trim()?.uppercase(Locale.US)
        ).joinToString("|").ifBlank { aircraft.icao24.trim().lowercase(Locale.US) }
    }

    // Prefer specific documented enrichment but keep live/feed seed values when enrichment is missing.
    private fun merge_aircraft_details(
        seed: AircraftDetails,
        enrichment: AircraftDetails
    ): AircraftDetails {
        val source = listOfNotNull(seed.registry_source, enrichment.registry_source)
            .asSequence()
            .flatMap { it.split(" + ").asSequence() }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")
            .ifEmpty { null }
        return AircraftDetails(
            icao24 = seed.icao24,
            registration = enrichment.registration ?: seed.registration,
            manufacturer = enrichment.manufacturer ?: seed.manufacturer,
            type = enrichment.type ?: seed.type,
            type_code = enrichment.type_code ?: seed.type_code,
            owner = enrichment.owner ?: seed.owner,
            manufactured_year = enrichment.manufactured_year ?: seed.manufactured_year,
            registry_source = source,
            operator_code = enrichment.operator_code ?: seed.operator_code,
            route = enrichment.route ?: seed.route,
            route_updated_epoch_sec = enrichment.route_updated_epoch_sec
                ?: seed.route_updated_epoch_sec,
            route_source = enrichment.route_source ?: seed.route_source,
            origin_airport = enrichment.origin_airport ?: seed.origin_airport,
            destination_airport = enrichment.destination_airport ?: seed.destination_airport,
            telemetry = enrichment.telemetry?.with_fallback(seed.telemetry) ?: seed.telemetry
        )
    }

    private fun normalized_registration(value: String?): String? {
        return value
            ?.trim()
            ?.uppercase(Locale.US)
            ?.replace("[^A-Z0-9-]".toRegex(), "")
            ?.takeIf { it.isNotBlank() }
    }

    companion object {
        fun details_status(details: AircraftDetails, still_loading: Boolean): String {
            return when {
                still_loading && !has_aircraft_metadata(details) -> "Loading aircraft details from remaining feed sources"
                still_loading -> "Metadata from ${details.registry_source ?: "configured sources"}; checking remaining feed sources"
                !has_aircraft_metadata(details) && details.telemetry?.has_values == true -> "Telemetry from ${details.registry_source ?: "configured sources"}; metadata unavailable"
                !has_aircraft_metadata(details) -> "Metadata unavailable from configured sources"
                else -> "Metadata from ${details.registry_source ?: "configured sources"}"
            }
        }

        fun has_aircraft_metadata(details: AircraftDetails): Boolean {
            return details.registration != null ||
                    details.manufacturer != null ||
                    details.type != null ||
                    details.type_code != null ||
                    details.owner != null
        }
    }
}

// Picks likely next-tapped aircraft for warm details/photo requests while the map is idle.
internal class AircraftDetailsPrefetchPlanner(
    private val warm_requester: AircraftDetailsWarmRequester,
    private val displayed_aircraft: () -> Aircraft?,
    private val selected_aircraft_snapshot: () -> Aircraft?,
    private val cached_traffic: () -> CachedTraffic,
    private val cache_key: (Aircraft) -> String,
    private val traffic_query_padding_px: (Viewport) -> Float,
    private val screen_point_for_entry: (TrafficSpatialEntry, Viewport, Double, Double) -> ScreenPoint,
    private val screen_neighborhood_contains: (Float, Float, Boolean, Viewport) -> Boolean,
    private val now_epoch_seconds: () -> Double
) {
    fun schedule(
        state: TrafficOverlayState,
        details_open: Boolean,
        pinch_in_progress: Boolean,
        drag_started: Boolean,
        last_map_interaction_ms: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        if (details_open || state.viewport.zoom < DETAILS_PREFETCH_MIN_ZOOM) return
        if (pinch_in_progress || drag_started || now - last_map_interaction_ms < DETAILS_PREFETCH_IDLE_DELAY_MS) return
        if (warm_requester.in_flight_count >= DETAILS_PREFETCH_MAX_IN_FLIGHT) return
        if (now - warm_requester.last_prefetch_elapsed_ms < DETAILS_PREFETCH_INTERVAL_MS) return

        val candidate = prefetch_candidates(state)
            .firstOrNull { warm_requester.should_prefetch(it, now) }
            ?: return
        warm_requester.last_prefetch_elapsed_ms = now
        warm_requester.start_prefetch(candidate)
    }

    private fun prefetch_candidates(state: TrafficOverlayState): List<Aircraft> {
        val preferred = listOfNotNull(
            displayed_aircraft(),
            selected_aircraft_snapshot()
        ).distinctBy(cache_key)
        val center_x = state.viewport.width / 2f
        val center_y = state.viewport.height / 2f
        if (state.aircraft.isNotEmpty()) {
            val visible = state.aircraft
                .asSequence()
                .sortedBy { item ->
                    val dx = item.screen_point.x - center_x
                    val dy = item.screen_point.y - center_y
                    dx * dx + dy * dy
                }
                .map { it.aircraft }
                .distinctBy(cache_key)
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
                .toList()
            return (preferred + visible)
                .distinctBy(cache_key)
                .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        }

        val viewport = state.viewport
        val cache = cached_traffic()
        val scale = 2.0.pow(viewport.zoom)
        val now_epoch_sec = now_epoch_seconds()
        val candidates = ArrayList<Pair<Float, Aircraft>>(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
        val seen = HashSet<String>()
        val query = cache.spatial_index.query(viewport, traffic_query_padding_px(viewport))
        for (entry in query) {
            if (candidates.size >= DETAILS_PREFETCH_SCAN_LIMIT) break
            val aircraft = entry.aircraft
            val key = cache_key(aircraft)
            if (!seen.add(key)) continue
            val screen = screen_point_for_entry(entry, viewport, scale, now_epoch_sec)
            if (!screen_neighborhood_contains(screen.x, screen.y, false, viewport)) continue
            val dx = screen.x - center_x
            val dy = screen.y - center_y
            candidates += dx * dx + dy * dy to aircraft
        }
        val visible = candidates
            .sortedBy { it.first }
            .asSequence()
            .map { it.second }
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
            .toList()
        return (preferred + visible)
            .distinctBy(cache_key)
            .take(DETAILS_PREFETCH_MAX_VISIBLE_CANDIDATES)
    }
}

internal class AircraftDetailsRowsBuilder(
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val is_details_loading_for: (Aircraft) -> Boolean,
    private val details_with_trace_origin: (AircraftDetails?, Aircraft) -> AircraftDetails?,
    private val current_flight_route_details: (AircraftDetails?, Aircraft) -> AircraftDetails?,
    private val route_trace_context: (Aircraft) -> AircraftRouteTraceContext,
    private val registry_country_label: (Aircraft, AircraftDetails?, Boolean) -> String,
    private val format_origin_status: (Aircraft, AircraftDetails?) -> String,
    private val current_flight_route_loading: (Aircraft, Boolean) -> Boolean,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val loading_or_unavailable: (Boolean) -> String
) {
    // Build honest detail rows from live aircraft plus documented metadata; missing data stays unavailable.
    fun aircraft_details_rows(
        aircraft: Aircraft,
        details: AircraftDetails?
    ): List<AircraftDetailsRow> {
        val details_loading = is_details_loading_for(aircraft)
        val enriched_details = details_with_trace_origin(details, aircraft)
        val route_details = current_flight_route_details(enriched_details, aircraft)
        val route_context = route_trace_context(aircraft)
        val telemetry =
            enriched_details?.telemetry?.with_fallback(aircraft.telemetry) ?: aircraft.telemetry
        val rows = mutableListOf<AircraftDetailsRow>()
        rows += AircraftDetailsRow.section("Aircraft")
        rows += AircraftDetailsRow("Callsign", aircraft.callsign)
        rows += AircraftDetailsRow("ICAO hex", aircraft.icao24.uppercase(Locale.US))
        rows += AircraftDetailsRow(
            "Registration",
            enriched_details?.registration ?: aircraft.registration ?: loading_or_unavailable(
                details_loading
            )
        )
        rows += AircraftDetailsRow(
            "Registry country",
            registry_country_label(aircraft, enriched_details, details_loading)
        )
        rows += AircraftDetailsRow(
            "Owner/Operator",
            AircraftRoutePresenter.details_value(enriched_details?.owner, details_loading)
        )
        rows += AircraftDetailsRow(
            "Aircraft",
            AircraftRoutePresenter.aircraft_type(enriched_details, aircraft, details_loading)
        )
        rows += AircraftDetailsRow(
            "MFR year",
            AircraftRoutePresenter.details_value(
                enriched_details?.manufactured_year,
                details_loading
            )
        )
        rows += AircraftDetailsRow(
            "Type code",
            enriched_details?.type_code ?: aircraft.type_code ?: loading_or_unavailable(
                details_loading
            )
        )
        rows += AircraftDetailsRow("Squawk", telemetry_formatter.telemetry_value(telemetry?.squawk))
        rows += AircraftDetailsRow(
            "Data source",
            telemetry_formatter.telemetry_value(telemetry_formatter.source_type(telemetry?.source_type))
        )
        rows += AircraftDetailsRow(
            "Registry source",
            AircraftRoutePresenter.details_value(enriched_details?.registry_source, details_loading)
        )
        if (aircraft.is_military) {
            rows += AircraftDetailsRow("Military", "Tagged military")
            rows += AircraftDetailsRow(
                "Origin status",
                format_origin_status(aircraft, route_details)
            )
        }
        val route_loading = current_flight_route_loading(aircraft, details_loading)
        rows += AircraftDetailsRow.section("Route")
        rows += AircraftDetailsRow(
            "Route",
            AircraftRoutePresenter.value(route_details?.route, route_loading)
        )
        rows += AircraftDetailsRow(
            "Origin",
            AircraftRoutePresenter.airport(route_details?.origin_airport, route_loading)
        )
        rows += AircraftDetailsRow(
            "Destination",
            AircraftRoutePresenter.airport(route_details?.destination_airport, route_loading)
        )
        rows += AircraftDetailsRow(
            "Path source",
            AircraftRoutePresenter.trace_source(route_context)
        )
        rows += AircraftDetailsRow(
            "Flight time",
            AircraftRoutePresenter.observed_flight_time(route_context)
        )
        rows += AircraftDetailsRow(
            "Route complete",
            AircraftRoutePresenter.route_completion(
                route_details,
                aircraft,
                route_context,
                route_loading
            )
        )
        rows += AircraftDetailsRow(
            "Observed path span",
            AircraftRoutePresenter.observed_path_span(route_context)
        )
        rows += spatial_rows(aircraft, telemetry)
        rows += speed_rows(aircraft, telemetry)
        rows += wind_rows(telemetry, details_loading)
        rows += altitude_rows(aircraft, telemetry)
        rows += direction_rows(aircraft, telemetry)
        return rows
    }

    private fun spatial_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Spatial"),
            AircraftDetailsRow(
                "Groundspeed",
                telemetry_formatter.aviation_speed(
                    telemetry?.ground_speed_ms ?: aircraft.velocity_ms
                )
            ),
            AircraftDetailsRow(
                "Baro. altitude",
                telemetry_formatter.altitude_value(
                    telemetry?.baro_altitude_m ?: aircraft.altitude_m
                )
            ),
            AircraftDetailsRow(
                "WGS84 altitude",
                telemetry_formatter.altitude_value(telemetry?.geom_altitude_m)
            ),
            AircraftDetailsRow(
                "Vert. Rate",
                telemetry_formatter.vertical_rate(
                    telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms
                )
            ),
            AircraftDetailsRow(
                "Track",
                telemetry_formatter.degrees_decimal(aircraft.track_deg, decimals = 1)
            ),
            AircraftDetailsRow("Pos.", telemetry_formatter.reported_position(aircraft)),
            AircraftDetailsRow(
                "Distance",
                telemetry_formatter.distance(reported_distance_meters(aircraft))
            )
        )
    }

    private fun wind_rows(
        telemetry: AircraftTelemetry?,
        loading: Boolean
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Wind"),
            AircraftDetailsRow(
                "Speed",
                telemetry_formatter.aviation_speed(telemetry?.wind_speed_ms, loading)
            ),
            AircraftDetailsRow(
                "Direction (from)",
                telemetry_formatter.degrees_decimal(
                    telemetry?.wind_direction_deg,
                    loading = loading
                )
            ),
            AircraftDetailsRow(
                "TAT / OAT",
                telemetry_formatter.temperature_pair(telemetry?.tat_c, telemetry?.oat_c, loading)
            )
        )
    }

    private fun speed_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Speed"),
            AircraftDetailsRow(
                "Ground",
                telemetry_formatter.aviation_speed(
                    telemetry?.ground_speed_ms ?: aircraft.velocity_ms
                )
            ),
            AircraftDetailsRow(
                "True",
                telemetry_formatter.aviation_speed(telemetry?.true_speed_ms)
            ),
            AircraftDetailsRow(
                "Indicated",
                telemetry_formatter.aviation_speed(telemetry?.indicated_speed_ms)
            ),
            AircraftDetailsRow("Mach", telemetry_formatter.mach(telemetry?.mach))
        )
    }

    private fun altitude_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Altitude"),
            AircraftDetailsRow(
                "Barometric",
                telemetry_formatter.altitude_value(
                    telemetry?.baro_altitude_m ?: aircraft.altitude_m
                )
            ),
            AircraftDetailsRow(
                "Baro. Rate",
                telemetry_formatter.vertical_rate(
                    telemetry?.baro_rate_ms ?: aircraft.vertical_rate_ms
                )
            ),
            AircraftDetailsRow(
                "Geom. WGS84",
                telemetry_formatter.altitude_value(telemetry?.geom_altitude_m)
            ),
            AircraftDetailsRow(
                "Geom. Rate",
                telemetry_formatter.vertical_rate(telemetry?.geom_rate_ms)
            ),
            AircraftDetailsRow("QNH", telemetry_formatter.pressure(telemetry?.qnh_hpa)),
            AircraftDetailsRow(
                "Sel. Alt.",
                telemetry_formatter.altitude_value(telemetry?.selected_altitude_m)
            )
        )
    }

    private fun direction_rows(
        aircraft: Aircraft,
        telemetry: AircraftTelemetry?
    ): List<AircraftDetailsRow> {
        return listOf(
            AircraftDetailsRow.section("Direction"),
            AircraftDetailsRow(
                "Ground Track",
                telemetry_formatter.degrees_decimal(aircraft.track_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "True Heading",
                telemetry_formatter.degrees_decimal(telemetry?.true_heading_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "Magnetic Heading",
                telemetry_formatter.degrees_decimal(telemetry?.magnetic_heading_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "Magnetic Decl.",
                telemetry_formatter.signed_degrees(telemetry?.magnetic_declination_deg)
            ),
            AircraftDetailsRow(
                "Track Rate",
                telemetry_formatter.track_rate(telemetry?.track_rate_deg_per_sec)
            ),
            AircraftDetailsRow("Roll", telemetry_formatter.signed_degrees(telemetry?.roll_deg)),
            AircraftDetailsRow(
                "Sel. Head.",
                telemetry_formatter.degrees_decimal(telemetry?.selected_heading_deg, decimals = 1)
            ),
            AircraftDetailsRow(
                "Nav. Modes",
                telemetry?.nav_modes?.joinToString(", ")?.takeIf { it.isNotBlank() }
                    ?: "Unavailable")
        )
    }
}

class AircraftDetailsSession(
    private val coordinator: AircraftDetailsCoordinator,
    private val warm_requester: AircraftDetailsWarmRequester,
    private val feed_mode: () -> AircraftFeedMode,
    private val selected_aircraft: () -> Aircraft?,
    private val select_aircraft: (Aircraft) -> Unit,
    private val has_selected_flight_path: () -> Boolean,
    private val is_flight_path_loading: (Aircraft) -> Boolean,
    private val request_flight_path: (String) -> Unit,
    private val request_trace_origin_airport: (Aircraft) -> Unit,
    private val reset_scroll: () -> Unit,
    private val reset_scroll_offset: () -> Unit,
    private val request_redraw: () -> Unit,
    private val request_animation_frame: () -> Unit
) {
    var details_open = false
    var usage_open = false
    var environmental_impact_open = false
    var impact_methodology_open = false
    var aircraft_details: AircraftDetails? = null
    var aircraft_details_status = "Select aircraft"
    var aircraft_details_loading = false
    var aircraft_photo: Bitmap? = null
    var aircraft_photo_previous_bitmap: Bitmap? = null
    var aircraft_photo_transition_started_elapsed_ms = 0L
    var aircraft_photo_status = "Photo unavailable"
    var aircraft_photo_evidence: PhotoEvidence? = null
    var active_photo_evidence: PhotoEvidence? = null
    var aircraft_photo_quality: PhotoQuality? = null
    var photo_evidence_open = false
    var photo_gallery_open = false
    var aircraft_photo_gallery: List<AircraftPhotoGalleryItem> = emptyList()
    var aircraft_photo_gallery_status = "Photo gallery unavailable"
    var aircraft_photo_gallery_loading = false

    private var details_request_token = 0L

    private enum class SecondaryPanel {
        USAGE,
        ENVIRONMENTAL_IMPACT
    }

    // Details open by first anchoring selection, then letting real metadata/photo sources race safely by token.
    fun open_details(aircraft: Aircraft) {
        select_aircraft(aircraft)
        details_open = true
        usage_open = false
        environmental_impact_open = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        reset_scroll()
        clear_photo_transition()
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = false
        aircraft_photo_gallery_status = "Photo gallery unavailable"
        val warmed = warm_requester.fresh_details_for(aircraft)
        if (warmed != null) {
            apply_warmed_details(warmed, preserve_existing = false)
        } else {
            aircraft_details = null
            aircraft_details_loading = true
            aircraft_photo = null
            aircraft_photo_evidence = null
            aircraft_photo_quality = null
            aircraft_details_status = "Loading live aircraft details"
            aircraft_photo_status = "Searching real photo sources"
            apply_seeded_details(aircraft)
        }
        request_details(aircraft)
    }

    fun open_usage(aircraft: Aircraft, selected_aircraft_id: String?) {
        open_secondary_panel(aircraft, selected_aircraft_id, SecondaryPanel.USAGE)
    }

    fun open_impact(aircraft: Aircraft, selected_aircraft_id: String?) {
        open_secondary_panel(aircraft, selected_aircraft_id, SecondaryPanel.ENVIRONMENTAL_IMPACT)
    }

    private fun open_secondary_panel(
        aircraft: Aircraft,
        selected_aircraft_id: String?,
        panel: SecondaryPanel
    ) {
        if (selected_aircraft_id != aircraft.icao24) {
            select_aircraft(aircraft)
        } else if (!has_selected_flight_path() && !is_flight_path_loading(aircraft)) {
            request_flight_path(aircraft.icao24)
        }
        usage_open = panel == SecondaryPanel.USAGE
        environmental_impact_open = panel == SecondaryPanel.ENVIRONMENTAL_IMPACT
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        reset_scroll()
    }

    fun close_details_shell() {
        details_open = false
        aircraft_details_loading = false
        photo_evidence_open = false
        photo_gallery_open = false
        active_photo_evidence = null
        usage_open = false
        environmental_impact_open = false
    }

    fun open_photo_evidence(evidence: PhotoEvidence?) {
        active_photo_evidence = evidence
        photo_evidence_open = true
        reset_scroll_offset()
    }

    fun close_photo_evidence() {
        photo_evidence_open = false
        active_photo_evidence = null
    }

    fun close_photo_gallery() {
        photo_gallery_open = false
    }

    fun open_photo_gallery(aircraft: Aircraft, details: AircraftDetails) {
        val request_token = details_request_token
        photo_gallery_open = true
        photo_evidence_open = false
        active_photo_evidence = null
        reset_scroll()
        aircraft_photo_gallery = emptyList()
        aircraft_photo_gallery_loading = true
        aircraft_photo_gallery_status = when (feed_mode()) {
            AircraftFeedMode.WEB -> "Loading exact photos from direct aircraft-photo sources"
            AircraftFeedMode.API -> "Loading exterior photos from API sources"
            AircraftFeedMode.HYBRID -> "Loading exact photos, then labeled representatives"
        }
        request_redraw()
        coordinator.request_photo_gallery(
            aircraft = aircraft,
            details = details,
            request_token = request_token,
            is_current_request = ::is_current_details_request,
            on_gallery = ::post_photo_gallery
        )
    }

    fun photo_transition_progress(replacement_transition_ms: Long): Float {
        if (aircraft_photo_previous_bitmap == null) return 1f
        val elapsed = SystemClock.elapsedRealtime() - aircraft_photo_transition_started_elapsed_ms
        if (elapsed !in 0L until replacement_transition_ms) {
            clear_photo_transition()
            return 1f
        }
        request_animation_frame()
        return (elapsed.toFloat() / replacement_transition_ms).coerceIn(0f, 1f)
    }

    fun unavailable_photo_status(): String {
        return "Exact, representative, and search photos unavailable"
    }

    fun is_current_details_request(requested_id: String, request_token: Long): Boolean {
        return details_open &&
                details_request_token == request_token &&
                selected_aircraft()?.icao24 == requested_id
    }

    fun apply_warm_cache_to_current_details(
        key: String,
        entry: AircraftDetailsWarmCacheEntry,
        current_key: String?
    ) {
        if (!details_open || current_key != key) return
        apply_warmed_details(entry, preserve_existing = true)
        request_redraw()
    }

    private fun request_details(aircraft: Aircraft) {
        val request_token = ++details_request_token
        coordinator.request_aircraft_details(
            aircraft = aircraft,
            request_token = request_token,
            is_current_request = ::is_current_details_request,
            is_photo_available = { aircraft_photo != null },
            on_details = ::post_aircraft_details,
            on_details_unavailable = ::post_aircraft_details_unavailable,
            on_photo_found = ::post_aircraft_photo,
            on_photo_unavailable = ::post_aircraft_photo_unavailable,
            on_photo_search_done = ::post_aircraft_photo_search_done
        )
    }

    private fun post_aircraft_details(
        requested_id: String,
        request_token: Long,
        details: AircraftDetails,
        still_loading: Boolean
    ) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details = details
        aircraft_details_loading = still_loading
        aircraft_details_status = AircraftDetailsCoordinator.details_status(details, still_loading)
        if (aircraft_photo == null) {
            aircraft_photo_status = "Searching real photo sources"
        }
        selected_aircraft()?.let { request_trace_origin_airport(it) }
        request_redraw()
    }

    private fun post_aircraft_details_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_details_loading = false
        if (aircraft_details == null) {
            aircraft_details_status = "Metadata unavailable from configured sources"
        }
        selected_aircraft()?.let { request_trace_origin_airport(it) }
        request_redraw()
    }

    private fun post_photo_gallery(
        requested_id: String,
        request_token: Long,
        items: List<AircraftPhotoGalleryItem>
    ) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_photo_gallery = items
        aircraft_photo_gallery_loading = false
        aircraft_photo_gallery_status = if (items.isEmpty()) {
            "No real gallery photos available from checked sources"
        } else {
            "Tap a source-marked photo for proof and browser links"
        }
        request_redraw()
    }

    private fun apply_seeded_details(aircraft: Aircraft): Boolean {
        val details = coordinator.seeded_aircraft_details(
            aircraft = aircraft
        ) ?: return false
        warm_requester.cache_aircraft_details(aircraft, details, still_loading = true)
        aircraft_details = details
        aircraft_details_loading = true
        aircraft_details_status =
            AircraftDetailsCoordinator.details_status(details, still_loading = true)
        aircraft_photo_status = "Searching real photo sources"
        request_redraw()
        return true
    }

    private fun apply_warmed_details(
        entry: AircraftDetailsWarmCacheEntry,
        preserve_existing: Boolean
    ) {
        val warmed_details = entry.details
        if (warmed_details != null) {
            if (!preserve_existing || aircraft_details == null || aircraft_details_loading) {
                aircraft_details = warmed_details
                aircraft_details_loading = entry.details_loading
                aircraft_details_status = entry.details_status
            }
        } else if (!preserve_existing || aircraft_details == null) {
            aircraft_details = null
            aircraft_details_loading = entry.details_loading
            aircraft_details_status = entry.details_status
        }

        val warmed_photo = entry.photo
        if (warmed_photo != null) {
            val current_rank = aircraft_photo_quality?.rank ?: 0
            if (!preserve_existing || aircraft_photo == null || warmed_photo.quality.rank > current_rank) {
                apply_aircraft_photo(
                    warmed_photo,
                    animate_replace = preserve_existing && aircraft_photo != null && warmed_photo.quality.rank > current_rank
                )
            }
        } else if (!preserve_existing || aircraft_photo == null) {
            aircraft_photo = null
            aircraft_photo_status = entry.photo_status
            aircraft_photo_evidence = null
            aircraft_photo_quality = null
            clear_photo_transition()
        }
    }

    private fun post_aircraft_photo(
        requested_id: String,
        request_token: Long,
        photo: AircraftPhotoResult.Found,
        allow_replace: Boolean
    ) {
        if (!is_current_details_request(requested_id, request_token)) return
        val current_quality = aircraft_photo_quality
        if (!allow_replace && aircraft_photo != null) return
        if (allow_replace && current_quality != null && photo.quality.rank <= current_quality.rank) {
            request_redraw()
            return
        }
        apply_aircraft_photo(
            photo,
            animate_replace = allow_replace && aircraft_photo != null && photo.quality.rank > (current_quality?.rank
                ?: 0)
        )
        request_redraw()
    }

    private fun post_aircraft_photo_unavailable(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
        aircraft_photo = null
        aircraft_photo_status = unavailable_photo_status()
        aircraft_photo_evidence = null
        aircraft_photo_quality = null
        clear_photo_transition()
        request_redraw()
    }

    private fun post_aircraft_photo_search_done(requested_id: String, request_token: Long) {
        if (!is_current_details_request(requested_id, request_token)) return
    }

    private fun apply_aircraft_photo(photo: AircraftPhotoResult.Found, animate_replace: Boolean) {
        val previous = aircraft_photo
        if (animate_replace && previous != null && previous != photo.bitmap) {
            aircraft_photo_previous_bitmap = previous
            aircraft_photo_transition_started_elapsed_ms = SystemClock.elapsedRealtime()
            request_animation_frame()
        } else {
            clear_photo_transition()
        }
        aircraft_photo = photo.bitmap
        aircraft_photo_status = photo.note
        aircraft_photo_evidence = photo.evidence
        aircraft_photo_quality = photo.quality
    }

    private fun clear_photo_transition() {
        aircraft_photo_previous_bitmap = null
        aircraft_photo_transition_started_elapsed_ms = 0L
    }
}

data class AircraftDetailsWarmCacheEntry(
    val details: AircraftDetails? = null,
    val details_loading: Boolean = false,
    val details_status: String = "Metadata unavailable from configured sources",
    val photo: AircraftPhotoResult.Found? = null,
    val photo_status: String = "Photo unavailable",
    val updated_elapsed_ms: Long
)

class AircraftDetailsWarmCache(
    max_entries: Int,
    private val max_age_ms: Long,
    private val photo_unavailable_status: () -> String
) {
    private val entries = object : LinkedHashMap<String, AircraftDetailsWarmCacheEntry>(
        max_entries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AircraftDetailsWarmCacheEntry>): Boolean {
            return size > max_entries
        }
    }
    private val request_tokens = mutableMapOf<String, Long>()
    private var next_request_token = 0L

    var last_prefetch_elapsed_ms = 0L

    val in_flight_count: Int
        get() = request_tokens.size

    // Warm requests use monotonically increasing tokens so stale detail/photo callbacks cannot repaint a new selection.
    fun begin_request(key: String): Long {
        val token = ++next_request_token
        request_tokens[key] = token
        return token
    }

    fun has_in_flight_request(key: String): Boolean {
        return request_tokens.containsKey(key)
    }

    fun is_current_request(
        key: String,
        selected_aircraft_id: String,
        requested_id: String,
        request_token: Long
    ): Boolean {
        return request_tokens[key] == request_token && requested_id == selected_aircraft_id
    }

    fun finish_request(key: String) {
        request_tokens.remove(key)
    }

    fun fresh_entry(key: String, now_elapsed_ms: Long): AircraftDetailsWarmCacheEntry? {
        val entry = entries[key] ?: return null
        if (now_elapsed_ms - entry.updated_elapsed_ms <= max_age_ms) return entry
        entries.remove(key)
        return null
    }

    fun needs_refresh(key: String, now_elapsed_ms: Long): Boolean {
        if (key.isBlank() || request_tokens.containsKey(key)) return false
        val cached = entries[key] ?: return true
        if (now_elapsed_ms - cached.updated_elapsed_ms > max_age_ms) {
            entries.remove(key)
            return true
        }
        return false
    }

    fun cache_details(
        key: String,
        aircraft_current_key: String,
        details: AircraftDetails,
        still_loading: Boolean,
        now_elapsed_ms: Long
    ): AircraftDetailsWarmCacheEntry {
        val previous = entries[key]
        val entry =
            (previous ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)).copy(
                details = details,
                details_loading = still_loading,
                details_status = AircraftDetailsCoordinator.details_status(details, still_loading),
                photo_status = previous?.photo_status ?: "Searching real photo sources",
                updated_elapsed_ms = now_elapsed_ms
            )
        entries[key] = entry
        if (previous == null && aircraft_current_key != key) {
            entries.remove(key)
        }
        return entry
    }

    fun cache_details_unavailable(
        key: String,
        now_elapsed_ms: Long
    ): AircraftDetailsWarmCacheEntry {
        val previous = entries[key]
        val entry =
            (previous ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)).copy(
                details_loading = false,
                details_status = "Metadata unavailable from configured sources",
                updated_elapsed_ms = now_elapsed_ms
            )
        entries[key] = entry
        return entry
    }

    fun cache_photo(
        key: String,
        photo: AircraftPhotoResult.Found,
        allow_replace: Boolean,
        now_elapsed_ms: Long
    ): AircraftDetailsWarmCacheEntry {
        val previous =
            entries[key] ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)
        val current_photo = previous.photo
        val should_replace =
            current_photo == null || (allow_replace && photo.quality.rank > current_photo.quality.rank)
        val entry = if (should_replace) {
            previous.copy(
                photo = photo,
                photo_status = photo.note,
                updated_elapsed_ms = now_elapsed_ms
            )
        } else {
            previous.copy(updated_elapsed_ms = now_elapsed_ms)
        }
        entries[key] = entry
        return entry
    }

    fun cache_photo_unavailable(key: String, now_elapsed_ms: Long): AircraftDetailsWarmCacheEntry {
        val previous =
            entries[key] ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)
        val entry = previous.copy(
            photo_status = photo_unavailable_status(),
            updated_elapsed_ms = now_elapsed_ms
        )
        finish_request(key)
        entries[key] = entry
        return entry
    }
}

class AircraftDetailsWarmRequester(
    private val coordinator: AircraftDetailsCoordinator,
    private val cache_key: (Aircraft) -> String,
    private val apply_entry: (String, AircraftDetailsWarmCacheEntry) -> Unit,
    max_entries: Int,
    max_age_ms: Long,
    photo_unavailable_status: () -> String
) {
    private val warm_cache = AircraftDetailsWarmCache(
        max_entries = max_entries,
        max_age_ms = max_age_ms,
        photo_unavailable_status = photo_unavailable_status
    )

    var last_prefetch_elapsed_ms: Long
        get() = warm_cache.last_prefetch_elapsed_ms
        set(value) {
            warm_cache.last_prefetch_elapsed_ms = value
        }

    val in_flight_count: Int
        get() = warm_cache.in_flight_count

    fun fresh_details_for(aircraft: Aircraft): AircraftDetailsWarmCacheEntry? {
        return warm_cache.fresh_entry(cache_key(aircraft), SystemClock.elapsedRealtime())
    }

    fun should_prefetch(aircraft: Aircraft, now_elapsed_ms: Long): Boolean {
        return warm_cache.needs_refresh(cache_key(aircraft), now_elapsed_ms)
    }

    // Warm requests run ahead of user taps, but every callback still proves it belongs to the same aircraft key.
    fun start_prefetch(aircraft: Aircraft) {
        val key = cache_key(aircraft)
        if (key.isBlank() || warm_cache.has_in_flight_request(key)) return
        val request_token = warm_cache.begin_request(key)
        coordinator.seeded_aircraft_details(
            aircraft = aircraft
        )?.let { details ->
            cache_aircraft_details(aircraft, details, still_loading = true)
        }
        coordinator.request_aircraft_details(
            aircraft = aircraft,
            request_token = request_token,
            is_current_request = { requested_id, token ->
                is_current_warmed_request(key, aircraft, requested_id, token)
            },
            is_photo_available = {
                warm_cache.fresh_entry(
                    key,
                    SystemClock.elapsedRealtime()
                )?.photo != null
            },
            on_details = { requested_id, token, details, still_loading ->
                if (is_current_warmed_request(key, aircraft, requested_id, token)) {
                    cache_aircraft_details(aircraft, details, still_loading)
                }
            },
            on_details_unavailable = { requested_id, token ->
                if (is_current_warmed_request(key, aircraft, requested_id, token)) {
                    cache_details_unavailable(key)
                }
            },
            on_photo_found = { requested_id, token, photo, allow_replace ->
                if (is_current_warmed_request(key, aircraft, requested_id, token)) {
                    cache_aircraft_photo(key, photo, allow_replace)
                }
            },
            on_photo_unavailable = { requested_id, token ->
                if (is_current_warmed_request(key, aircraft, requested_id, token)) {
                    cache_photo_unavailable(key)
                }
            },
            on_photo_search_done = { requested_id, token ->
                if (is_current_warmed_request(key, aircraft, requested_id, token)) {
                    warm_cache.finish_request(key)
                }
            }
        )
    }

    fun cache_aircraft_details(
        aircraft: Aircraft,
        details: AircraftDetails,
        still_loading: Boolean
    ) {
        val key = cache_key(aircraft)
        val entry = warm_cache.cache_details(
            key = key,
            aircraft_current_key = cache_key(aircraft),
            details = details,
            still_loading = still_loading,
            now_elapsed_ms = SystemClock.elapsedRealtime()
        )
        apply_entry(key, entry)
    }

    private fun cache_details_unavailable(key: String) {
        val entry = warm_cache.cache_details_unavailable(key, SystemClock.elapsedRealtime())
        apply_entry(key, entry)
    }

    private fun cache_aircraft_photo(
        key: String,
        photo: AircraftPhotoResult.Found,
        allow_replace: Boolean
    ) {
        val entry = warm_cache.cache_photo(
            key = key,
            photo = photo,
            allow_replace = allow_replace,
            now_elapsed_ms = SystemClock.elapsedRealtime()
        )
        apply_entry(key, entry)
    }

    private fun cache_photo_unavailable(key: String) {
        val entry = warm_cache.cache_photo_unavailable(key, SystemClock.elapsedRealtime())
        apply_entry(key, entry)
    }

    private fun is_current_warmed_request(
        key: String,
        aircraft: Aircraft,
        requested_id: String,
        request_token: Long
    ): Boolean {
        return warm_cache.is_current_request(
            key = key,
            selected_aircraft_id = aircraft.icao24,
            requested_id = requested_id,
            request_token = request_token
        )
    }
}

internal class AircraftTelemetryFormatter(
    private val measurement_formatter: MapMeasurementFormatter,
    private val reported_distance_meters: (Aircraft) -> Double,
    private val loading_or_unavailable: (Boolean) -> String,
    private val now_epoch_seconds: () -> Double
) {
    fun aircraft_label_detail(aircraft: Aircraft): String {
        val altitude = aircraft.altitude_m?.let { altitude_value(it) } ?: "alt n/a"
        return "${distance(reported_distance_meters(aircraft))}  $altitude"
    }

    fun aircraft_detail(aircraft: Aircraft): String {
        return "${distance(reported_distance_meters(aircraft))}  ${altitude_value(aircraft.altitude_m)}"
    }

    fun distance(meters: Double): String = measurement_formatter.format_distance(meters)

    fun altitude_value(meters: Double?): String = measurement_formatter.format_altitude(meters)

    fun accuracy(meters: Double): String = measurement_formatter.format_accuracy(meters)

    fun speed_value(ms: Double?): String = measurement_formatter.format_speed(ms)

    fun aviation_speed(ms: Double?, loading: Boolean = false): String {
        ms ?: return loading_or_unavailable(loading)
        val knots = ms / KNOTS_TO_METERS_PER_SECOND
        val display = speed_value(ms)
        return String.format(Locale.US, "%.0f kt / %s", knots, display)
    }

    fun track(degrees: Double?): String = measurement_formatter.format_track(degrees)

    fun vertical_rate(ms: Double?): String = measurement_formatter.format_vertical_rate(ms)

    fun telemetry_value(value: String?): String {
        return value?.trim()?.takeIf { it.isNotBlank() } ?: "Unavailable"
    }

    fun source_type(value: String?): String? {
        val normalized = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val compact = normalized.lowercase(Locale.US).replace("-", "_")
        return when {
            "adsb" in compact || "ads_b" in compact -> "ADS-B"
            "mlat" in compact -> "MLAT"
            "tisb" in compact || "tis_b" in compact -> "TIS-B"
            else -> normalized.uppercase(Locale.US)
        }
    }

    fun degrees_decimal(degrees: Double?, decimals: Int = 0, loading: Boolean = false): String {
        degrees ?: return loading_or_unavailable(loading)
        return String.format(Locale.US, "%.${decimals}f deg", degrees)
    }

    fun signed_degrees(degrees: Double?): String {
        degrees ?: return "Unavailable"
        return String.format(Locale.US, "%+.1f deg", degrees)
    }

    fun track_rate(degrees_per_second: Double?): String {
        degrees_per_second ?: return "Unavailable"
        return String.format(Locale.US, "%+.2f deg/s", degrees_per_second)
    }

    fun temperature_pair(tat_c: Double?, oat_c: Double?, loading: Boolean = false): String {
        return when {
            tat_c != null && oat_c != null -> String.format(
                Locale.US,
                "%.0f / %.0f C",
                tat_c,
                oat_c
            )

            tat_c != null -> String.format(Locale.US, "TAT %.0f C", tat_c)
            oat_c != null -> String.format(Locale.US, "OAT %.0f C", oat_c)
            else -> loading_or_unavailable(loading)
        }
    }

    fun mach(mach: Double?): String {
        mach ?: return "Unavailable"
        return String.format(Locale.US, "%.3f", mach)
    }

    fun pressure(hpa: Double?): String {
        hpa ?: return "Unavailable"
        return String.format(Locale.US, "%.1f hPa", hpa)
    }

    fun reported_position(aircraft: Aircraft): String {
        val reported = AircraftPositionProjector.reported_position(aircraft)
        return String.format(Locale.US, "%.4f, %.4f", reported.lat, reported.lon)
    }

    fun age(aircraft: Aircraft): String {
        val age = AircraftPositionProjector.contact_age_seconds(
            aircraft = aircraft,
            now_epoch_sec = now_epoch_seconds()
        ) ?: return "Age unavailable"
        return "${age.toLong()}s old"
    }

    fun feet_setting(feet: Float): String = measurement_formatter.format_feet_setting(feet)

    private companion object {
        const val KNOTS_TO_METERS_PER_SECOND = 0.514444
    }
}

internal class AircraftTraceDetailsPresenter(
    private val selected_trace_aircraft_id: () -> String?,
    private val trace: () -> FlightTrace?,
    private val selected_segments: (Boolean) -> List<TraceSegment>?,
    private val is_flight_path_loading: (Aircraft) -> Boolean,
    private val trace_origin_airport_for: (Aircraft) -> AirportDetails?,
    private val trace_origin_loading_for: (Aircraft) -> Boolean,
    private val aircraft_feed_mode: () -> AircraftFeedMode,
    private val log_route_diagnostic: (String) -> Unit
) {
    fun current_flight_route_details(
        details: AircraftDetails?,
        aircraft: Aircraft
    ): AircraftDetails? {
        val route_details = details_with_trace_origin(details, aircraft) ?: return null
        if (!CurrentRouteValidator.has_route_metadata(route_details)) return null
        val validation = CurrentRouteValidator.evaluate(
            details = route_details,
            aircraft_icao24 = aircraft.icao24,
            aircraft_callsign = aircraft.callsign,
            selected_trace_aircraft_id = selected_trace_aircraft_id(),
            trace_segments = selected_segments(false)
        )
        log_route_diagnostic(validation.diagnostic)
        return route_details.takeIf { validation.accepted }
    }

    fun flight_trace_diagnostic(trace: FlightTrace?): String {
        if (trace == null) return "source=none points=0"
        val points = trace.all_points.sortedBy { it.epoch_sec }
        val first = points.firstOrNull()
        val last = points.lastOrNull()
        return "source=${trace.source.ifBlank { "unknown" }} points=${trace.point_count} previous=${trace.previous_point_count} " +
                "first=${first?.lat_lon_label() ?: "none"} last=${last?.lat_lon_label() ?: "none"}"
    }

    fun current_flight_route_loading(aircraft: Aircraft, details_loading: Boolean): Boolean {
        val trace_origin_pending = trace_origin_loading_for(aircraft) &&
                aircraft_feed_mode() == AircraftFeedMode.HYBRID
        return details_loading || is_flight_path_loading(aircraft) || trace_origin_pending
    }

    fun details_with_trace_origin(details: AircraftDetails?, aircraft: Aircraft): AircraftDetails? {
        val fallback_origin = trace_origin_airport_for(aircraft) ?: return details
        if (details?.origin_airport != null) return details
        val source = listOfNotNull(details?.route_source, "OSM trace-origin aerodrome")
            .distinct()
            .joinToString(" + ")
        return (details ?: AircraftDetails(
            icao24 = aircraft.icao24,
            registration = aircraft.registration,
            manufacturer = null,
            type = null,
            type_code = aircraft.type_code,
            owner = null,
            manufactured_year = null,
            registry_source = null,
            operator_code = null,
            route = null,
            route_updated_epoch_sec = null,
            route_source = source,
            origin_airport = fallback_origin,
            destination_airport = null
        )).copy(
            route_source = source,
            origin_airport = fallback_origin
        )
    }

    fun route_trace_context(aircraft: Aircraft): AircraftRouteTraceContext {
        val id = aircraft.icao24.lowercase(Locale.US)
        return AircraftRouteTraceContext(
            aircraft_id = id,
            selected_trace_aircraft_id = selected_trace_aircraft_id(),
            trace = trace(),
            segments = selected_segments(false),
            loading = is_flight_path_loading(aircraft)
        )
    }

    fun current_impact_trace_for(aircraft: Aircraft): ImpactTrace? {
        val segments = current_trace_segments_for_impact(aircraft) ?: return null
        val points = segments.flatMap { it.points }.takeIf { it.size >= 2 } ?: return null
        val start = points.minOf { it.epoch_sec }
        val end = points.maxOf { it.epoch_sec }
        val seconds = (end - start).coerceAtLeast(0L)
        val distance = AircraftRoutePresenter.trace_distance_meters(segments)
        if (seconds <= 0L || distance <= 0.0) return null
        return ImpactTrace(
            distance_m = distance,
            hours = seconds / 3600.0,
            average_speed_ms = distance / seconds,
            point_count = points.size,
            source = trace()?.source ?: "trace source"
        )
    }

    fun current_trace_segments_for_impact(aircraft: Aircraft): List<TraceSegment>? {
        val id = aircraft.icao24.lowercase(Locale.US)
        if (selected_trace_aircraft_id() != id) return null
        return selected_segments(false)?.takeIf { segments ->
            segments.sumOf { it.points.size } >= 2
        }
    }

    fun has_usage_trace_for(aircraft: Aircraft): Boolean {
        return usage_trace_for(aircraft) != null
    }

    fun usage_trace_for(aircraft: Aircraft): FlightTrace? {
        val id = aircraft.icao24.lowercase(Locale.US)
        val current_trace = trace() ?: return null
        if (selected_trace_aircraft_id() != id) return null
        if (current_trace.segments.isEmpty() && current_trace.previous_segments.isEmpty()) return null
        return current_trace
    }

    private fun TrackPoint.lat_lon_label(): String {
        return String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }
}
