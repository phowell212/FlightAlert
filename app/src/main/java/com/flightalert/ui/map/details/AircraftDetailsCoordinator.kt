package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.data.api.AircraftDetailsClient
import com.flightalert.data.AircraftMetadataSeed
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.photo.AircraftPhotoGalleryItem
import com.flightalert.ui.map.photo.AircraftPhotoFetcher
import com.flightalert.ui.map.photo.AircraftPhotoResult
import com.flightalert.ui.map.photo.PhotoQuality
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private data class AircraftDetailCandidate(
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
        mode: FlightAlertSettings.AircraftFeedMode,
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
        val candidates = detail_candidates_for_aircraft(aircraft, mode)
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
                    val photo = runCatching { aircraft_photo_fetcher.fetch_fallback_photo(aircraft, details) }
                        .getOrElse { AircraftPhotoResult.Unavailable("Exact, representative, and search photos unavailable") }
                    photo_in_flight.decrementAndGet()
                    when (photo) {
                        is AircraftPhotoResult.Found -> {
                            val first_photo = photo_found.compareAndSet(false, true)
                            if (first_photo || photo.quality == PhotoQuality.EXACT) {
                                post_to_main { on_photo_found(requested_id, request_token, photo, photo.quality == PhotoQuality.EXACT) }
                            }
                            maybe_post_final_photo_unavailable()
                        }
                        is AircraftPhotoResult.Unavailable -> maybe_post_final_photo_unavailable()
                    }
                }
            }

            if (should_run_exact) {
                executor.execute {
                    val exact = runCatching { aircraft_photo_fetcher.fetch_exact_aircraft_photo(aircraft, details) }.getOrNull()
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
                        merged_details = merged_details?.let { merge_aircraft_details(it, details) } ?: details
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
        mode: FlightAlertSettings.AircraftFeedMode,
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
    private fun detail_candidates_for_aircraft(
        aircraft: Aircraft,
        @Suppress("UNUSED_PARAMETER") mode: FlightAlertSettings.AircraftFeedMode
    ): List<AircraftDetailCandidate> {
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

    private fun details_from_feed_seed(aircraft: Aircraft, seed: AircraftMetadataSeed?): AircraftDetails {
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

    private fun String.clean_seed_value(): String? {
        val cleaned = trim().trim('-').trim()
        return cleaned.takeIf {
            it.isNotBlank() &&
                !it.equals("null", ignoreCase = true) &&
                !it.equals("n/a", ignoreCase = true) &&
                !it.equals("unavailable", ignoreCase = true)
        }
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

        private fun has_route_metadata(details: AircraftDetails): Boolean {
            return details.route != null ||
                details.origin_airport != null ||
                details.destination_airport != null
        }
    }
}
