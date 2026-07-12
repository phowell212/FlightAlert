package com.flightalert.ui

import com.flightalert.map.FilterId
import com.flightalert.map.FilterState
import com.flightalert.map.ReferenceClassCatalog

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

data class ReferenceFiltersPanelControllerResult(
    val session: ReferenceFiltersPanelSession,
    val filterState: FilterState,
    val dismissRequested: Boolean,
    val clearTrafficSearchFocus: Boolean,
    val filterStateChanged: Boolean,
    val encodedFilterState: String?,
)

class ReferenceFiltersPanelController(
    textMeasurer: ReferenceFiltersTextMeasurer,
) {
    private val panel = ReferenceFiltersPanel(textMeasurer)

    fun plan(
        viewport: ReferenceFiltersViewport,
        session: ReferenceFiltersPanelSession,
        catalog: ReferenceClassCatalog?,
        filterState: FilterState,
        trafficContentHeightDp: Float,
    ): ReferenceFiltersPanelPlan = panel.plan(
        ReferenceFiltersPanelInput(
            viewport = viewport,
            selectedTab = session.selectedTab,
            mapCatalog = ReferenceFilterCatalogUiMapper.map(catalog, filterState),
            labelsMasterEnabled = filterState.labels_master_enabled,
            outlinesMasterEnabled = filterState.outlines_master_enabled,
            trafficContentHeightDp = trafficContentHeightDp,
            requestedScrollOffsetDp = session.requestedScrollOffsetDp,
            focusedActionId = session.focusedActionId,
        )
    )

    fun keyIntent(
        plan: ReferenceFiltersPanelPlan,
        session: ReferenceFiltersPanelSession,
        key: ReferencePanelKey,
    ): ReferencePanelIntent? = panel.keyIntent(plan, session.focusedActionId, key)

    fun reduce(
        session: ReferenceFiltersPanelSession,
        filterState: FilterState,
        intent: ReferencePanelIntent,
    ): ReferenceFiltersPanelControllerResult {
        var nextSession = session
        var nextFilterState = filterState
        var dismissRequested = false
        when (intent) {
            ReferencePanelIntent.Dismiss -> dismissRequested = true
            is ReferencePanelIntent.SelectTab -> {
                nextSession = session.copy(
                    selectedTab = intent.tab,
                    requestedScrollOffsetDp = 0f,
                    focusedActionId = when (intent.tab) {
                        ReferenceFiltersTab.TRAFFIC -> ReferenceFiltersPanelActions.TRAFFIC_TAB
                        ReferenceFiltersTab.MAP -> ReferenceFiltersPanelActions.MAP_TAB
                    },
                )
            }
            is ReferencePanelIntent.ToggleMaster -> {
                nextFilterState = when (intent.section) {
                    ReferenceFilterSection.LABELS -> filterState.with_labels_master(
                        !filterState.labels_master_enabled
                    )
                    ReferenceFilterSection.OUTLINES -> filterState.with_outlines_master(
                        !filterState.outlines_master_enabled
                    )
                }
                nextSession = session.copy(
                    focusedActionId = when (intent.section) {
                        ReferenceFilterSection.LABELS -> ReferenceFiltersPanelActions.LABELS_MASTER
                        ReferenceFilterSection.OUTLINES -> ReferenceFiltersPanelActions.OUTLINES_MASTER
                    },
                )
            }
            is ReferencePanelIntent.ToggleFilter -> {
                FilterId.from_stable_id(intent.stableFilterId)?.let { filterId ->
                    nextFilterState = filterState.with_filter(
                        filterId,
                        !filterState.stored_enabled(filterId),
                    )
                    nextSession = session.copy(
                        focusedActionId = ReferenceFiltersPanelActions.filter(filterId.stable_id),
                    )
                }
            }
            is ReferencePanelIntent.MoveFocus -> {
                nextSession = session.copy(focusedActionId = intent.actionId)
            }
            is ReferencePanelIntent.SetScrollOffset -> {
                require(intent.offsetDp.isFinite() && intent.offsetDp >= 0f) {
                    "panel scroll offset must be finite and nonnegative"
                }
                nextSession = session.copy(requestedScrollOffsetDp = intent.offsetDp)
            }
        }
        val filterStateChanged = nextFilterState != filterState
        return ReferenceFiltersPanelControllerResult(
            session = nextSession,
            filterState = nextFilterState,
            dismissRequested = dismissRequested,
            clearTrafficSearchFocus = intent is ReferencePanelIntent.Dismiss ||
                intent is ReferencePanelIntent.SelectTab ||
                intent is ReferencePanelIntent.MoveFocus,
            filterStateChanged = filterStateChanged,
            encodedFilterState = if (filterStateChanged) {
                ReferenceFilterPreferences.encode(nextFilterState)
            } else {
                null
            },
        )
    }
}
