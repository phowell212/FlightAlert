package com.flightalert.ui

import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog

data class ReferenceFiltersPanelSession(
    val selectedTab: ReferenceFiltersTab = ReferenceFiltersTab.TRAFFIC,
    val requestedScrollOffsetDp: Float = 0f,
    val focusedActionId: String? = null,
) {
    init {
        require(requestedScrollOffsetDp.isFinite() && requestedScrollOffsetDp >= 0f) {
            "panel scroll offset must be finite and nonnegative"
        }
        require(focusedActionId == null || focusedActionId.isNotBlank()) {
            "focused action ID must be nonempty"
        }
    }
}