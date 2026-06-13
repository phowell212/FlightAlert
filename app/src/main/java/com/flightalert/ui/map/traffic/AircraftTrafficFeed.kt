package com.flightalert.ui.map.traffic

import com.flightalert.data.api.AircraftFeedClient
import com.flightalert.data.FeedAircraft
import com.flightalert.data.FeedBounds
import com.flightalert.data.FeedResult
import com.flightalert.data.FeedSource
import com.flightalert.data.FeedStatus
import com.flightalert.data.web.GlobeWebAircraftSource
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.Aircraft
import java.util.Locale
import kotlin.math.max

// Chooses and merges live aircraft sources so FlightMapView can ask for traffic without knowing source policy.
class AircraftTrafficFeed(
    private val aircraft_feed_client: AircraftFeedClient,
    private val globe_web_aircraft_source: GlobeWebAircraftSource?
) {
    // Keep the optional web source pointed at the same viewport as the visible map.
    fun update_viewport(
        feed_bounds: FeedBounds,
        center_lat: Double,
        center_lon: Double,
        zoom: Double,
        feed_mode: FlightAlertSettings.AircraftFeedMode
    ) {
        globe_web_aircraft_source
            ?.takeIf { feed_mode.uses_globe }
            ?.update_viewport(feed_bounds, center_lat, center_lon, zoom)
    }

    // Fetch according to the selected source mode, reporting intermediate real results when hybrid data arrives in stages.
    fun fetch_aircraft(
        feed_bounds: FeedBounds,
        safety_api_bounds: FeedBounds?,
        own_lat: Double,
        own_lon: Double,
        exact_search: String?,
        feed_mode: FlightAlertSettings.AircraftFeedMode,
        on_intermediate_result: (FeedResult) -> Unit
    ): FeedResult? {
        val globe_source = globe_web_aircraft_source?.takeIf { feed_mode.uses_globe }
        return when (feed_mode) {
            FlightAlertSettings.AircraftFeedMode.WEB -> {
                globe_source?.latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
                    ?: FeedResult(
                        status = FeedStatus.UNAVAILABLE,
                        source = FeedSource.AIRPLANES_LIVE_GLOBE,
                        partial_coverage = true
                    )
            }
            FlightAlertSettings.AircraftFeedMode.API -> {
                aircraft_feed_client.fetch_aircraft(feed_bounds, own_lat, own_lon, exact_search)
            }
            FlightAlertSettings.AircraftFeedMode.HYBRID -> {
                // Hybrid keeps the web source as the wide-area inventory, then spends API queries on the safety bubble.
                val targeted_api_bounds = safety_api_bounds ?: feed_bounds
                val first_globe_result = globe_source?.latest_snapshot(feed_bounds, own_lat, own_lon, exact_search)
                if (first_globe_result?.status == FeedStatus.OK) {
                    on_intermediate_result(first_globe_result)
                }
                val api_result = aircraft_feed_client.fetch_aircraft(targeted_api_bounds, own_lat, own_lon, exact_search)
                if (api_result.status == FeedStatus.OK && first_globe_result?.status != FeedStatus.OK) {
                    on_intermediate_result(api_result.copy(source = FeedSource.HYBRID, partial_coverage = true))
                }
                val globe_result = globe_source?.latest_snapshot(feed_bounds, own_lat, own_lon, exact_search) ?: first_globe_result
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

    // Convert provider feed rows into map aircraft without inventing missing fields.
    fun map_aircraft(result: FeedResult): List<Aircraft> {
        return result.aircraft.map { it.to_map_aircraft() }
    }

    // Turn feed coverage into short UI text so partial coverage stays visible to the user.
    fun coverage_label(result: FeedResult): String {
        return when {
            result.source == FeedSource.HYBRID && result.partial_coverage -> " (loading web supplement)"
            result.query_count > 1 && result.partial_coverage -> " (${result.query_count} areas, partial wide-area coverage)"
            result.query_count > 1 -> " (${result.query_count} areas)"
            result.partial_coverage -> " (partial wide-area coverage)"
            else -> ""
        }
    }

    // Merge API position data with web metadata by aircraft key, keeping source uncertainty in the result.
    private fun merge_hybrid_aircraft_feeds(api_result: FeedResult, globe_result: FeedResult): FeedResult {
        val merged = linkedMapOf<String, FeedAircraft>()
        api_result.aircraft.forEach { item ->
            merged[item.hybrid_feed_key()] = item
        }
        globe_result.aircraft.forEach { web_item ->
            val key = web_item.hybrid_feed_key()
            val api_item = merged[key]
            merged[key] = if (api_item == null) {
                web_item
            } else {
                api_item.copy(
                    registration = api_item.registration ?: web_item.registration,
                    type_code = api_item.type_code ?: web_item.type_code,
                    metadata = api_item.metadata ?: web_item.metadata,
                    db_flags = api_item.db_flags ?: web_item.db_flags,
                    category = api_item.category ?: web_item.category,
                    telemetry = api_item.telemetry?.with_fallback(web_item.telemetry) ?: web_item.telemetry
                )
            }
        }
        return FeedResult(
            status = FeedStatus.OK,
            source = FeedSource.HYBRID,
            aircraft = merged.values.sortedBy { it.distance_m },
            epoch_sec = max_epoch(api_result.epoch_sec, globe_result.epoch_sec),
            query_count = api_result.query_count + globe_result.query_count,
            partial_coverage = globe_result.partial_coverage
        )
    }

    // Prefer real aircraft identifiers for merges, falling back to coarse position only when no ID exists.
    private fun FeedAircraft.hybrid_feed_key(): String {
        val hex = icao24.trim().trimStart('~').lowercase(Locale.US)
        if (hex.isNotBlank()) return "hex:$hex"
        registration?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }?.let { return "reg:$it" }
        return "pos:${"%.4f".format(Locale.US, lat)}:${"%.4f".format(Locale.US, lon)}:${callsign.trim().uppercase(Locale.US)}"
    }

    // Keep the shared Aircraft model as a direct translation of feed data with nullable unknowns preserved.
    private fun FeedAircraft.to_map_aircraft(): Aircraft {
        return Aircraft(
            icao24 = icao24,
            callsign = callsign,
            registration = registration,
            type_code = type_code,
            metadata_seed = metadata,
            is_military = db_flags?.let { it and DB_FLAG_MILITARY != 0 } == true,
            lat = lat,
            lon = lon,
            on_ground = on_ground,
            altitude_m = altitude_m,
            velocity_ms = velocity_ms,
            track_deg = track_deg,
            vertical_rate_ms = vertical_rate_ms,
            category = category,
            position_time_sec = position_time_sec,
            last_contact_sec = last_contact_sec,
            distance_m = distance_m,
            telemetry = telemetry
        )
    }

    private fun max_epoch(first: Double?, second: Double?): Double? {
        return when {
            first == null -> second
            second == null -> first
            else -> max(first, second)
        }
    }

    private companion object {
        const val DB_FLAG_MILITARY = 1
    }
}
