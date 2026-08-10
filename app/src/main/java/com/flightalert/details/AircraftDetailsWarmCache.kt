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

    fun display_entry(key: String): AircraftDetailsWarmCacheEntry? = entries[key]

    fun needs_refresh(key: String, now_elapsed_ms: Long): Boolean {
        if (key.isBlank() || request_tokens.containsKey(key)) return false
        val cached = entries[key] ?: return true
        return now_elapsed_ms - cached.updated_elapsed_ms > max_age_ms
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
                details = if (still_loading) previous?.details ?: details else details,
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
