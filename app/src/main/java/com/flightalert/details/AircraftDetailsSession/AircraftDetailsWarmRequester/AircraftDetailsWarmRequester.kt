@file:Suppress(
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
)

package com.flightalert.details

import android.os.SystemClock
import java.util.LinkedHashMap
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftDetails



class AircraftDetailsWarmRequester(
    private val coordinator: AircraftDetailsCoordinator,
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
        return warm_cache.display_entry(aircraft.appearance_key())
    }

    fun should_prefetch(aircraft: Aircraft, now_elapsed_ms: Long): Boolean {
        return warm_cache.needs_refresh(aircraft.appearance_key(), now_elapsed_ms)
    }

    // Warm requests run ahead of user taps, but every callback still proves it belongs to the same aircraft key.
    fun start_prefetch(aircraft: Aircraft) {
        val key = aircraft.appearance_key()
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
                warm_cache.display_entry(key)?.photo != null
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
        val key = aircraft.appearance_key()
        val entry = warm_cache.cache_details(
            key = key,
            aircraft_current_key = aircraft.appearance_key(),
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
