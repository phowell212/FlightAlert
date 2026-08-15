package com.flightalert.ui

import com.flightalert.map.reference.FilterId
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog



data class ReferenceFiltersPanelControllerResult(
    val session: ReferenceFiltersPanelSession,
    val filterState: FilterState,
    val dismissRequested: Boolean,
    val clearTrafficSearchFocus: Boolean,
    val filterStateChanged: Boolean,
    val encodedFilterState: String?,
)