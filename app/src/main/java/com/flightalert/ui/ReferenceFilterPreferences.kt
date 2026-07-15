@file:Suppress("FunctionName", "PropertyName")

package com.flightalert.ui

import com.flightalert.map.FilterId
import com.flightalert.map.FilterState
import com.flightalert.map.ReferencePolicyException

object ReferenceFilterPreferences {
    const val schema = "flight-alert-reference-filters-v1"
    private val app_default_state = fromLegacyGroups(
        placesEnabled = FlightAlertSettings.DEFAULT_LAYER_PLACE_LABELS_ENABLED,
        waterEnabled = FlightAlertSettings.DEFAULT_LAYER_WATER_LABELS_ENABLED,
        regionsEnabled = FlightAlertSettings.DEFAULT_LAYER_REGION_LABELS_ENABLED,
        publicLandsEnabled = FlightAlertSettings.DEFAULT_LAYER_PUBLIC_LANDS_ENABLED,
    )

    fun encode(state: FilterState): String = buildString {
        append(schema).append('\n')
        append("labels.master=").append(boolean_token(state.labels_master_enabled)).append('\n')
        append("outlines.master=").append(boolean_token(state.outlines_master_enabled)).append('\n')
        FilterId.entries.forEach { filter_id ->
            append(filter_id.stable_id)
                .append('=')
                .append(boolean_token(state.stored_enabled(filter_id)))
                .append('\n')
        }
    }

    fun decode(encoded: String?): FilterState {
        if (encoded == null) return FilterState.defaults()
        preference_require(encoded.endsWith('\n') && '\r' !in encoded) {
            "reference filter preferences must use canonical LF text with a final LF"
        }
        val lines = encoded.dropLast(1).split('\n')
        preference_require(lines.isNotEmpty() && lines.first() == schema) {
            "reference filter preference schema is unsupported"
        }

        val defaults = FilterState.defaults()
        val enabled = FilterId.entries
            .filter { defaults.stored_enabled(it) }
            .toMutableSet()
        var labels_master_enabled = defaults.labels_master_enabled
        var outlines_master_enabled = defaults.outlines_master_enabled
        val seen_known_keys = mutableSetOf<String>()

        lines.drop(1).forEach { line ->
            val equals_index = line.indexOf('=')
            preference_require(
                equals_index > 0 && equals_index == line.lastIndexOf('=') &&
                    equals_index < line.lastIndex,
            ) { "reference filter preference row is malformed" }
            val key = line.substring(0, equals_index)
            val raw_value = line.substring(equals_index + 1)
            when (key) {
                "labels.master" -> {
                    require_new_known_key(seen_known_keys, key)
                    labels_master_enabled = parse_boolean_token(raw_value)
                }
                "outlines.master" -> {
                    require_new_known_key(seen_known_keys, key)
                    outlines_master_enabled = parse_boolean_token(raw_value)
                }
                else -> {
                    val filter_id = FilterId.from_stable_id(key) ?: return@forEach
                    require_new_known_key(seen_known_keys, key)
                    val enabled_value = parse_boolean_token(raw_value)
                    if (enabled_value) enabled.add(filter_id) else enabled.remove(filter_id)
                }
            }
        }
        return FilterState.of(
            enabled = enabled,
            labels_master_enabled = labels_master_enabled,
            outlines_master_enabled = outlines_master_enabled,
        )
    }

    fun app_defaults(): FilterState = app_default_state

    fun reset(@Suppress("UNUSED_PARAMETER") current_state: FilterState): FilterState = app_defaults()

    fun fromLegacyGroups(
        placesEnabled: Boolean,
        waterEnabled: Boolean,
        regionsEnabled: Boolean,
        publicLandsEnabled: Boolean,
    ): FilterState {
        var state = FilterState.defaults()
        if (!placesEnabled) {
            state = state
                .with_filter(FilterId.LABELS_PLACES, false)
                .with_filter(FilterId.LABELS_ISLANDS, false)
        }
        if (!waterEnabled) {
            state = state
                .with_filter(FilterId.LABELS_MAJOR_WATER, false)
                .with_filter(FilterId.LABELS_RIVERS, false)
                .with_filter(FilterId.LABELS_STREAMS, false)
                .with_filter(FilterId.LABELS_CANALS, false)
        }
        if (!regionsEnabled) {
            state = state.with_filter(FilterId.LABELS_REGIONS, false)
        }
        if (!publicLandsEnabled) {
            state = state
                .with_filter(FilterId.LABELS_PROTECTED_LANDS, false)
                .with_filter(FilterId.OUTLINES_PROTECTED_AREAS, false)
        }
        return state
    }

    private fun boolean_token(value: Boolean): Char = if (value) '1' else '0'

    private fun parse_boolean_token(value: String): Boolean {
        return when (value) {
            "1" -> true
            "0" -> false
            else -> throw ReferencePolicyException(
                "reference filter preference Boolean must be encoded as 0 or 1",
            )
        }
    }

    private fun require_new_known_key(seen: MutableSet<String>, key: String) {
        preference_require(seen.add(key)) {
            "reference filter preference repeats known key $key"
        }
    }

    private inline fun preference_require(condition: Boolean, lazy_message: () -> String) {
        if (!condition) throw ReferencePolicyException(lazy_message())
    }
}
