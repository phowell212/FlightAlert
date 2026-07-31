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

package com.flightalert.sources

import com.flightalert.FlightAlertAppSettings
import android.os.SystemClock
import com.flightalert.aircraft.Aircraft
import com.flightalert.details.max_epoch
import com.flightalert.ui.AircraftFeedMode
import java.util.Locale
import kotlin.math.abs

class AircraftTrafficFeed(
    private val aircraft_feed_client: AircraftFeedClient,
    private val globe_bin_craft_aircraft_source: GlobeBinCraftAircraftSource?
) {
    private var mapped_globe_source: List<FeedAircraft>? = null
    private var mapped_globe_aircraft = MappedGlobeAircraftStore(emptyList())

    // Keep the optional binCraft source pointed at the same broad inventory area as the visible map.
    fun update_viewport(
        feed_bounds: FeedBounds,
        center_lat: Double,
        center_lon: Double,
        zoom: Double,
        feed_mode: AircraftFeedMode
    ) {
        globe_bin_craft_aircraft_source
            ?.takeIf { feed_mode.uses_globe }
            ?.update_viewport(feed_bounds, center_lat, center_lon, zoom)
    }

    // Fetch according to the selected source mode, reporting intermediate real results when hybrid data arrives in stages.
    fun fetch_aircraft(
        feed_bounds: FeedBounds,
        globe_display_bounds: List<FeedBounds>,
        safety_api_bounds: FeedBounds?,
        own_lat: Double,
        own_lon: Double,
        search_query: String?,
        selected_aircraft_key: String?,
        feed_mode: AircraftFeedMode,
        on_intermediate_result: (FeedResult) -> Unit
    ): FeedResult? {
        val globe_source = globe_bin_craft_aircraft_source?.takeIf { feed_mode.uses_globe }
        val remote_exact_search = search_query ?: selected_aircraft_key
        return when (feed_mode) {
            AircraftFeedMode.BINCRAFT -> {
                globe_source?.latest_snapshot(
                    globe_display_bounds,
                    safety_api_bounds,
                    own_lat,
                    own_lon,
                    search_query,
                    selected_aircraft_key
                )
                    ?: FeedResult(
                        status = FeedStatus.UNAVAILABLE,
                        source = FeedSource.AIRPLANES_LIVE_GLOBE,
                        partial_coverage = true
                    )
            }

            AircraftFeedMode.API -> {
                aircraft_feed_client.fetch_aircraft(
                    feed_bounds,
                    own_lat,
                    own_lon,
                    remote_exact_search
                )
            }

            AircraftFeedMode.HYBRID -> {
                // Hybrid keeps binCraft as the wide-area inventory, then spends API queries on the safety bubble.
                val targeted_api_bounds = safety_api_bounds ?: feed_bounds
                val first_globe_result =
                    globe_source?.latest_snapshot(
                        globe_display_bounds,
                        safety_api_bounds,
                        own_lat,
                        own_lon,
                        search_query,
                        selected_aircraft_key
                    )
                        ?: globe_source?.await_latest_snapshot(
                            globe_display_bounds,
                            safety_api_bounds,
                            own_lat,
                            own_lon,
                            search_query,
                            selected_aircraft_key
                        )
                if (first_globe_result?.status == FeedStatus.OK) {
                    on_intermediate_result(first_globe_result)
                }
                val api_result = aircraft_feed_client.fetch_aircraft(
                    targeted_api_bounds,
                    own_lat,
                    own_lon,
                    remote_exact_search
                )
                val globe_result =
                    globe_source?.latest_snapshot(
                        globe_display_bounds,
                        safety_api_bounds,
                        own_lat,
                        own_lon,
                        search_query,
                        selected_aircraft_key
                    )
                        ?: first_globe_result
                when {
                    api_result.status == FeedStatus.OK && globe_result?.status == FeedStatus.OK -> {
                        merge_hybrid_aircraft_feeds(api_result, globe_result)
                    }

                    api_result.status != FeedStatus.OK && globe_result?.status == FeedStatus.OK -> {
                        globe_result
                    }

                    api_result.status != FeedStatus.OK -> {
                        api_result
                    }

                    else -> api_result.copy(
                        source = FeedSource.HYBRID,
                        partial_coverage = true
                    )
                }
            }
        }
    }

    fun map_aircraft(aircraft: FeedAircraft, fallback: Aircraft? = null): Aircraft {
        return aircraft.to_map_aircraft(fallback)
    }

    @Synchronized
    internal fun map_globe_aircraft_or_cancelled(
        result: FeedResult,
        is_cancelled: () -> Boolean
    ): MappedGlobeAircraftStore? {
        val source = result.globe_aircraft ?: return null
        if (source === mapped_globe_source) return mapped_globe_aircraft
        val mapped = ArrayList<Aircraft>(source.size)
        source.forEachIndexed { index, item ->
            if (index and CANCELLATION_CHECK_MASK == 0 && is_cancelled()) return null
            mapped += item.to_map_aircraft()
        }
        return MappedGlobeAircraftStore(mapped).also {
            mapped_globe_source = source
            mapped_globe_aircraft = it
        }
    }

    // Turn feed coverage into short UI text so partial coverage stays visible to the user.
    fun coverage_label(result: FeedResult): String {
        return when {
            result.source == FeedSource.HYBRID && result.partial_coverage -> " (loading binCraft supplement)"
            result.query_count > 1 && result.partial_coverage -> " (${result.query_count} areas, partial wide-area coverage)"
            result.query_count > 1 -> " (${result.query_count} areas)"
            result.partial_coverage -> " (partial wide-area coverage)"
            else -> ""
        }
    }

    // Keep globe/binCraft positions canonical, then fill missing metadata from the API safety query.
    private fun merge_hybrid_aircraft_feeds(
        api_result: FeedResult,
        globe_result: FeedResult
    ): FeedResult {
        val merged = linkedMapOf<String, FeedAircraft>()
        globe_result.aircraft.forEach { item ->
            merged[item.hybrid_feed_key()] = item
        }
        api_result.aircraft.forEach { api_item ->
            val key = api_item.hybrid_feed_key()
            val globe_item = merged[key]
            merged[key] = if (globe_item == null) {
                api_item
            } else {
                merge_freshest_position_with_metadata(globe_item, api_item)
            }
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.HYBRID,
            aircraft = merged.values.sortedBy { it.distance_m },
            epoch_sec = max_epoch(api_result.epoch_sec, globe_result.epoch_sec),
            query_count = api_result.query_count + globe_result.query_count,
            partial_coverage = globe_result.partial_coverage,
            content_revision = globe_result.content_revision,
            globe_aircraft = globe_result.globe_aircraft,
        )
    }

    private fun merge_freshest_position_with_metadata(
        globe_item: FeedAircraft,
        api_item: FeedAircraft
    ): FeedAircraft {
        val position_item = fresher_position_aircraft(globe_item, api_item)
        val metadata_item = if (position_item === globe_item) api_item else globe_item
        return position_item.copy(
            callsign = position_item.callsign.takeUnless {
                it.isBlank() || it.equals(
                    "Unknown",
                    ignoreCase = true
                )
            }
                ?: metadata_item.callsign,
            registration = position_item.registration ?: metadata_item.registration,
            type_code = position_item.type_code ?: metadata_item.type_code,
            metadata = position_item.metadata ?: metadata_item.metadata,
            db_flags = position_item.db_flags ?: metadata_item.db_flags,
            on_ground = position_item.on_ground ?: metadata_item.on_ground,
            altitude_m = position_item.altitude_m ?: metadata_item.altitude_m,
            velocity_ms = position_item.velocity_ms ?: metadata_item.velocity_ms,
            track_deg = position_item.track_deg ?: metadata_item.track_deg,
            vertical_rate_ms = position_item.vertical_rate_ms ?: metadata_item.vertical_rate_ms,
            category = position_item.category ?: metadata_item.category,
            distance_m = position_item.distance_m.takeIf { it > 0.0 } ?: metadata_item.distance_m,
            telemetry = position_item.telemetry?.with_fallback(metadata_item.telemetry)
                ?: metadata_item.telemetry
        )
    }

    // Prefer real aircraft identifiers for merges, falling back to coarse position only when no ID exists.
    private fun FeedAircraft.hybrid_feed_key(): String {
        val hex = icao24.trim().lowercase(Locale.US)
        if (hex.isNotBlank()) return "hex:$hex"
        registration?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
            ?.let { return "reg:$it" }
        return "pos:${"%.4f".format(Locale.US, lat)}:${
            "%.4f".format(
                Locale.US,
                lon
            )
        }:${callsign.trim().uppercase(Locale.US)}"
    }

    private fun fresher_position_aircraft(first: FeedAircraft, second: FeedAircraft): FeedAircraft {
        val first_position_time = first.position_time_sec ?: first.last_contact_sec ?: 0.0
        val second_position_time = second.position_time_sec ?: second.last_contact_sec ?: 0.0
        if (abs(first_position_time - second_position_time) > POSITION_TIME_TIE_SECONDS) {
            return if (second_position_time > first_position_time) second else first
        }
        val first_contact_time = first.last_contact_sec ?: first.position_time_sec ?: 0.0
        val second_contact_time = second.last_contact_sec ?: second.position_time_sec ?: 0.0
        return if (second_contact_time >= first_contact_time) second else first
    }

    // Keep the shared Aircraft model as a direct translation of feed data with nullable unknowns preserved.
    private fun FeedAircraft.to_map_aircraft(fallback: Aircraft? = null): Aircraft {
        val resolved_callsign =
            callsign.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
                ?: fallback?.callsign
                ?: callsign
        return Aircraft(
            icao24 = icao24,
            callsign = resolved_callsign,
            registration = registration ?: fallback?.registration,
            type_code = type_code ?: fallback?.type_code,
            metadata_seed = metadata ?: fallback?.metadata_seed,
            is_military = db_flags?.let { it and DB_FLAG_MILITARY != 0 } == true ||
                    fallback?.is_military == true,
            lat = lat,
            lon = lon,
            on_ground = on_ground ?: fallback?.on_ground,
            altitude_m = altitude_m ?: fallback?.altitude_m,
            velocity_ms = velocity_ms ?: fallback?.velocity_ms,
            track_deg = track_deg ?: fallback?.track_deg,
            vertical_rate_ms = vertical_rate_ms ?: fallback?.vertical_rate_ms,
            category = category ?: fallback?.category,
            position_time_sec = position_time_sec,
            last_contact_sec = last_contact_sec,
            distance_m = distance_m,
            telemetry = if (fallback == null) {
                telemetry
            } else {
                telemetry?.with_fallback(fallback.telemetry) ?: fallback.telemetry
            }
        )
    }

    private companion object {
        const val DB_FLAG_MILITARY = 1
        const val POSITION_TIME_TIE_SECONDS =
            FlightAlertAppSettings.AircraftFeed.POSITION_TIME_TIE_SECONDS
        const val HYBRID_GLOBE_STARTUP_GRACE_MS =
            FlightAlertAppSettings.AircraftFeed.HYBRID_GLOBE_STARTUP_GRACE_MS
        const val HYBRID_GLOBE_STARTUP_POLL_MS =
            FlightAlertAppSettings.AircraftFeed.HYBRID_GLOBE_STARTUP_POLL_MS
        const val CANCELLATION_CHECK_MASK = 127
    }

    private fun GlobeBinCraftAircraftSource.await_latest_snapshot(
        globe_display_bounds: List<FeedBounds>,
        safety_bounds: FeedBounds?,
        own_lat: Double,
        own_lon: Double,
        search_query: String?,
        selected_aircraft_key: String?
    ): FeedResult? {
        val deadline = SystemClock.elapsedRealtime() + HYBRID_GLOBE_STARTUP_GRACE_MS
        var result = latest_snapshot(
            globe_display_bounds,
            safety_bounds,
            own_lat,
            own_lon,
            search_query,
            selected_aircraft_key
        )
        while (result?.status != FeedStatus.OK && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(HYBRID_GLOBE_STARTUP_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return result
            }
            result = latest_snapshot(
                globe_display_bounds,
                safety_bounds,
                own_lat,
                own_lon,
                search_query,
                selected_aircraft_key
            )
        }
        return result
    }
}

internal data class MappedGlobeAircraftStore(
    val aircraft: List<Aircraft>
)
