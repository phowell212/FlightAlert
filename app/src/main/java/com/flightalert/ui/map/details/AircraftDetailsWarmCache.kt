package com.flightalert.ui.map.details

import com.flightalert.data.AircraftDetails
import com.flightalert.ui.map.photo.AircraftPhotoResult
import java.util.LinkedHashMap

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

    fun is_current_request(key: String, selected_aircraft_id: String, requested_id: String, request_token: Long): Boolean {
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
        val entry = (previous ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)).copy(
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

    fun cache_details_unavailable(key: String, now_elapsed_ms: Long): AircraftDetailsWarmCacheEntry {
        val previous = entries[key]
        val entry = (previous ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)).copy(
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
        val previous = entries[key] ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)
        val current_photo = previous.photo
        val should_replace = current_photo == null || (allow_replace && photo.quality.rank > current_photo.quality.rank)
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
        val previous = entries[key] ?: AircraftDetailsWarmCacheEntry(updated_elapsed_ms = now_elapsed_ms)
        val entry = previous.copy(
            photo_status = photo_unavailable_status(),
            updated_elapsed_ms = now_elapsed_ms
        )
        finish_request(key)
        entries[key] = entry
        return entry
    }
}
