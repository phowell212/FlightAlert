@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.FilterKind
import com.flightalert.map.reference.ReferencePresentationPolicy
import java.util.Collections


@ConsistentCopyVisibility
data class FilterState private constructor(
    private val enabled_filters: Set<FilterId>,
    val labels_master_enabled: Boolean,
    val outlines_master_enabled: Boolean,
) {
    fun stored_enabled(filter_id: FilterId): Boolean = filter_id in enabled_filters

    fun effectively_enabled(filter_id: FilterId): Boolean {
        val master = if (ReferencePresentationPolicy.filter_spec(filter_id).kind == FilterKind.LABEL) {
            labels_master_enabled
        } else {
            outlines_master_enabled
        }
        return master && stored_enabled(filter_id)
    }

    fun with_filter(filter_id: FilterId, enabled: Boolean): FilterState {
        val next = enabled_filters.toMutableSet()
        if (enabled) next += filter_id else next -= filter_id
        return of(next, labels_master_enabled, outlines_master_enabled)
    }

    fun with_labels_master(enabled: Boolean): FilterState =
        of(enabled_filters, enabled, outlines_master_enabled)

    fun with_outlines_master(enabled: Boolean): FilterState =
        of(enabled_filters, labels_master_enabled, enabled)

    companion object {
        fun defaults(): FilterState = of(
            FilterId.entries.toSet() - FilterId.OUTLINES_COASTLINES,
            labels_master_enabled = true,
            outlines_master_enabled = true,
        )

        fun of(
            enabled: Collection<FilterId>,
            labels_master_enabled: Boolean,
            outlines_master_enabled: Boolean,
        ): FilterState = FilterState(
            Collections.unmodifiableSet(enabled.toSet()),
            labels_master_enabled,
            outlines_master_enabled,
        )
    }
}