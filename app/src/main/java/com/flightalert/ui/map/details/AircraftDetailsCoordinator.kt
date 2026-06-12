package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.data.AircraftDetailsClient
import com.flightalert.data.AircraftMetadataSeed
import com.flightalert.data.FeedAircraft
import com.flightalert.data.GlobeWebAircraftSource
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.photo.AircraftPhotoFetcher
import com.flightalert.ui.map.photo.AircraftPhotoResult
import com.flightalert.ui.map.photo.PhotoQuality
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AircraftDetailsCoordinator(
    private val aircraft_details_client: AircraftDetailsClient,
    private val aircraft_photo_fetcher: AircraftPhotoFetcher,
    private val globe_web_aircraft_source: GlobeWebAircraftSource?,
    private val executor: Executor,
    private val post_to_main: (() -> Unit) -> Unit,
    private val elapsed_realtime_ms: () -> Long,
    private val sleep_ms: (Long) -> Unit = { Thread.sleep(it) }
) {
    fun request_aircraft_details(
        aircraft: Aircraft,
        mode: FlightAlertSettings.AircraftFeedMode,
        lookup: WebDetailLookupContext?,
        globe_web_source_enabled: Boolean,
        request_token: Long,
        is_current_request: (String, Long) -> Boolean,
        is_photo_available: () -> Boolean,
        on_details: (String, Long, AircraftDetails, Boolean) -> Unit,
        on_details_unavailable: (String, Long) -> Unit,
        on_photo_found: (String, Long, AircraftPhotoResult.Found, Boolean) -> Unit,
        on_photo_unavailable: (String, Long) -> Unit
    ) {
        val requested_id = aircraft.icao24
        val candidates = detail_candidates_for_aircraft(aircraft, mode, lookup, globe_web_source_enabled)
        val remaining = AtomicInteger(candidates.size)
        val photo_in_flight = AtomicInteger(0)
        val photo_found = AtomicBoolean(false)
        val detail_lock = Any()
        val photo_keys = mutableSetOf<String>()
        var merged_details: AircraftDetails? = null

        fun maybe_post_final_photo_unavailable() {
            if (remaining.get() != 0 || photo_in_flight.get() != 0 || photo_found.get()) return
            post_to_main {
                if (!is_current_request(requested_id, request_token) || is_photo_available()) return@post_to_main
                on_photo_unavailable(requested_id, request_token)
            }
        }

        fun lookup_photo(details: AircraftDetails) {
            if (photo_found.get()) return
            val key = photo_lookup_key(aircraft, details)
            val should_run = synchronized(photo_keys) { photo_keys.add(key) }
            if (!should_run) {
                maybe_post_final_photo_unavailable()
                return
            }
            photo_in_flight.incrementAndGet()
            val photo = aircraft_photo_fetcher.fetch(aircraft, details)
            photo_in_flight.decrementAndGet()
            when (photo) {
                is AircraftPhotoResult.Found -> {
                    if (photo_found.compareAndSet(false, true)) {
                        post_to_main { on_photo_found(requested_id, request_token, photo, false) }
                    }
                }
                is AircraftPhotoResult.Unavailable -> maybe_post_final_photo_unavailable()
            }
        }

        candidates.forEach { candidate ->
            executor.execute {
                val details = runCatching { candidate.fetch() }.getOrNull()
                val merged = synchronized(detail_lock) {
                    if (details != null) {
                        merged_details = merged_details?.let { merge_aircraft_details(it, details) } ?: details
                    }
                    merged_details
                }
                val still_loading = remaining.decrementAndGet() > 0
                if (merged != null) {
                    post_to_main { on_details(requested_id, request_token, merged, still_loading) }
                    lookup_photo(merged)
                } else if (!still_loading) {
                    post_to_main { on_details_unavailable(requested_id, request_token) }
                    maybe_post_final_photo_unavailable()
                }
            }
        }
    }

    fun request_photo_improvement(
        aircraft: Aircraft,
        details: AircraftDetails,
        request_token: Long,
        previous_quality: PhotoQuality?,
        is_current_request: (String, Long) -> Boolean,
        on_improved_photo: (AircraftPhotoResult.Found) -> Unit,
        on_not_improved: () -> Unit
    ) {
        val requested_id = aircraft.icao24
        executor.execute {
            val result = aircraft_photo_fetcher.fetch(aircraft, details)
            post_to_main {
                if (!is_current_request(requested_id, request_token)) return@post_to_main
                when (result) {
                    is AircraftPhotoResult.Found -> {
                        if (previous_quality == null || result.quality.rank > previous_quality.rank) {
                            on_improved_photo(result)
                        } else {
                            on_not_improved()
                        }
                    }
                    is AircraftPhotoResult.Unavailable -> on_not_improved()
                }
            }
        }
    }

    private fun detail_candidates_for_aircraft(
        aircraft: Aircraft,
        mode: FlightAlertSettings.AircraftFeedMode,
        lookup: WebDetailLookupContext?,
        globe_web_source_enabled: Boolean
    ): List<AircraftDetailCandidate> {
        val api = AircraftDetailCandidate("API feed") {
            aircraft_details_client.fetch_details(aircraft.icao24, aircraft.callsign, aircraft.registration)
        }
        val web = AircraftDetailCandidate("Web feed") {
            fetch_web_feed_details(aircraft, lookup, globe_web_source_enabled)
        }
        return when (mode) {
            FlightAlertSettings.AircraftFeedMode.API -> listOf(api)
            FlightAlertSettings.AircraftFeedMode.WEB -> listOf(web, api)
            FlightAlertSettings.AircraftFeedMode.HYBRID -> listOf(api, web)
        }
    }

    private fun fetch_web_feed_details(
        aircraft: Aircraft,
        lookup: WebDetailLookupContext?,
        globe_web_source_enabled: Boolean
    ): AircraftDetails? {
        val deadline = elapsed_realtime_ms() + WEB_DETAIL_WAIT_MS
        while (elapsed_realtime_ms() <= deadline) {
            val seed = find_web_metadata_seed(aircraft, lookup, globe_web_source_enabled)
            if (seed != null) {
                return aircraft_details_client.fetch_details(aircraft.icao24, aircraft.callsign, aircraft.registration, seed)
            }
            sleep_ms(WEB_DETAIL_POLL_MS)
        }
        return null
    }

    private fun find_web_metadata_seed(
        aircraft: Aircraft,
        lookup: WebDetailLookupContext?,
        globe_web_source_enabled: Boolean
    ): AircraftMetadataSeed? {
        aircraft.metadata_seed?.takeIf { it.has_details }?.let { return it }
        val source = globe_web_aircraft_source?.takeIf { globe_web_source_enabled } ?: return null
        val context = lookup ?: return null
        val searches = listOfNotNull(
            aircraft.icao24.takeIf { it.isNotBlank() },
            aircraft.registration?.takeIf { it.isNotBlank() },
            aircraft.callsign.takeIf { it.isNotBlank() }
        ).distinct()
        searches.forEach { search ->
            source.latest_snapshot(context.bounds, context.own_lat, context.own_lon, search)
                ?.aircraft
                ?.firstOrNull { it.matches_aircraft(aircraft) }
                ?.metadata
                ?.takeIf { it.has_details }
                ?.let { return it }
        }
        return source.latest_snapshot(context.bounds, context.own_lat, context.own_lon, exact_search = null)
            ?.aircraft
            ?.firstOrNull { it.matches_aircraft(aircraft) }
            ?.metadata
            ?.takeIf { it.has_details }
    }

    private fun FeedAircraft.matches_aircraft(aircraft: Aircraft): Boolean {
        val feed_hex = icao24.trim().trimStart('~').lowercase(Locale.US)
        val selected_hex = aircraft.icao24.trim().trimStart('~').lowercase(Locale.US)
        if (feed_hex.isNotBlank() && selected_hex.isNotBlank() && feed_hex == selected_hex) return true
        val feed_registration = normalized_registration(registration)
        val selected_registration = normalized_registration(aircraft.registration)
        if (feed_registration != null && selected_registration != null && feed_registration == selected_registration) return true
        return callsign.compact_callsign().isNotBlank() &&
            callsign.compact_callsign() == aircraft.callsign.compact_callsign()
    }

    private fun String.compact_callsign(): String {
        return trim().replace(" ", "").uppercase(Locale.US)
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

    private fun merge_aircraft_details(seed: AircraftDetails, enrichment: AircraftDetails): AircraftDetails {
        val source = listOfNotNull(seed.registry_source, enrichment.registry_source)
            .flatMap { it.split(" + ") }
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
            route_updated_epoch_sec = enrichment.route_updated_epoch_sec ?: seed.route_updated_epoch_sec,
            route_source = enrichment.route_source ?: seed.route_source,
            origin_airport = enrichment.origin_airport ?: seed.origin_airport,
            destination_airport = enrichment.destination_airport ?: seed.destination_airport
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

        private const val WEB_DETAIL_WAIT_MS = 9000L
        private const val WEB_DETAIL_POLL_MS = 350L
    }
}
