package com.flightalert.ui.map.details

import android.os.SystemClock
import com.flightalert.data.AircraftDetails
import com.flightalert.settings.FlightAlertSettings
import com.flightalert.ui.map.Aircraft
import com.flightalert.ui.map.photo.AircraftPhotoResult

class AircraftDetailsWarmRequester(
    private val coordinator: AircraftDetailsCoordinator,
    private val feed_mode: () -> FlightAlertSettings.AircraftFeedMode,
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
            mode = feed_mode(),
            request_token = request_token,
            is_current_request = { requested_id, token ->
                is_current_warmed_request(key, aircraft, requested_id, token)
            },
            is_photo_available = { warm_cache.fresh_entry(key, SystemClock.elapsedRealtime())?.photo != null },
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
