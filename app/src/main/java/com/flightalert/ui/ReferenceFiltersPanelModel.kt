package com.flightalert.ui

import com.flightalert.map.reference.dictionary.FilterState

enum class ReferenceFiltersPanelContent {
    TRAFFIC_SLOT,
    MAP_UNAVAILABLE,
    MAP_FILTERS,
}

enum class ReferenceFiltersTab {
    TRAFFIC,
    MAP,
}

enum class ReferenceFilterSection {
    LABELS,
    OUTLINES,
}

data class ReferenceFilterRowUi(
    val stableId: String,
    val title: String,
    val section: ReferenceFilterSection,
    val sortOrder: Int,
    val storedEnabled: Boolean,
    val swatch: ReferenceStyleSwatch,
) {
    init {
        require(STABLE_FILTER_ID.matches(stableId)) { "filter row stable ID is invalid" }
        require(title.isNotBlank() && title == title.trim() && '\n' !in title && '\r' !in title) {
            "filter row title must be nonempty canonical text"
        }
        require(sortOrder >= 0) { "filter row sort order must be nonnegative" }
        require(
            (section == ReferenceFilterSection.LABELS && stableId.startsWith("labels.")) ||
                (section == ReferenceFilterSection.OUTLINES && stableId.startsWith("outlines."))
        ) { "filter row stable ID does not match its section" }
        require(
            (section == ReferenceFilterSection.LABELS && swatch is ReferenceLabelStyleSwatch) ||
                (section == ReferenceFilterSection.OUTLINES && swatch is ReferenceOutlineStyleSwatch)
        ) { "filter row swatch does not match its section" }
    }

    private companion object {
        val STABLE_FILTER_ID = Regex("^(labels|outlines)\\.[a-z0-9_]+(?:\\.[a-z0-9_]+)*$")
    }
}

data class ReferenceFilterSectionPlan(
    val section: ReferenceFilterSection,
    val title: String,
    val bounds: ReferenceDpRect,
    val masterActionId: String,
    val rowActionIds: List<String>,
    val heading: ReferenceTextPlan? = null,
    val columnIndex: Int = 0,
)

sealed interface ReferenceMapFilterCatalogUi {
    data class Unavailable(val message: String) : ReferenceMapFilterCatalogUi {
        init {
            require(message.isNotBlank() && message == message.trim()) {
                "unavailable catalog message must be explicit"
            }
        }
    }

    data class Verified(val rows: List<ReferenceFilterRowUi>) : ReferenceMapFilterCatalogUi {
        init {
            require(rows.map(ReferenceFilterRowUi::stableId).distinct().size == rows.size) {
                "verified catalog rows must have unique stable IDs"
            }
        }
    }
}

data class ReferenceFiltersPanelInput(
    val viewport: ReferenceFiltersViewport,
    val selectedTab: ReferenceFiltersTab,
    val mapCatalog: ReferenceMapFilterCatalogUi,
    val labelsMasterEnabled: Boolean = true,
    val outlinesMasterEnabled: Boolean = true,
    val trafficContentHeightDp: Float = 0f,
    val requestedScrollOffsetDp: Float = 0f,
    val focusedActionId: String? = null,
) {
    init {
        require(trafficContentHeightDp.isFinite() && trafficContentHeightDp >= 0f) {
            "traffic content height must be finite and nonnegative"
        }
    }
}

data class ReferenceFiltersViewport(
    val widthDp: Float,
    val heightDp: Float,
    val fontScale: Float,
) {
    init {
        require(widthDp >= 128f && widthDp.isFinite()) {
            "viewport width must provide two 48dp tabs"
        }
        require(heightDp >= 192f && heightDp.isFinite()) {
            "viewport height must provide the panel header, tabs, and one 48dp content target"
        }
        require(fontScale > 0f && fontScale.isFinite()) { "font scale must be positive" }
    }
}

data class ReferenceFiltersPanelPlan(
    val content: ReferenceFiltersPanelContent,
    val controls: List<ReferenceControlPlan>,
    val sections: List<ReferenceFilterSectionPlan>,
    val accessibilityNodes: List<ReferenceAccessibilityNode>,
    val statusMessage: ReferenceTextPlan?,
    val trafficContentBounds: ReferenceDpRect?,
    val title: ReferenceTextPlan? = null,
    val contentViewport: ReferenceDpRect = ReferenceDpRect(0f, 0f, 0f, 0f),
    val columnCount: Int = 1,
    val maxScrollOffsetDp: Float = 0f,
    val appliedScrollOffsetDp: Float = 0f,
)

data class ReferenceFiltersPanelControllerResult(
    val session: ReferenceFiltersPanelSession,
    val filterState: FilterState,
    val dismissRequested: Boolean,
    val clearTrafficSearchFocus: Boolean,
    val filterStateChanged: Boolean,
    val encodedFilterState: String?,
)

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

class ReferenceFiltersPanel(
    textMeasurer: ReferenceFiltersTextMeasurer,
) {
    private val layout = ReferenceFiltersPanelLayout(textMeasurer)

    fun plan(input: ReferenceFiltersPanelInput): ReferenceFiltersPanelPlan =
        layout.plan(input)

    fun keyIntent(
        plan: ReferenceFiltersPanelPlan,
        focusedActionId: String?,
        key: ReferencePanelKey,
    ): ReferencePanelIntent? {
        val actionIds = plan.controls.map(ReferenceControlPlan::actionId)
        return when (key) {
            ReferencePanelKey.BACK -> ReferencePanelIntent.Dismiss
            ReferencePanelKey.ACTIVATE -> plan.controls
                .firstOrNull { it.actionId == focusedActionId }
                ?.intent
            ReferencePanelKey.NEXT -> {
                val index = actionIds.indexOf(focusedActionId)
                ReferencePanelIntent.MoveFocus(actionIds[(index + 1).mod(actionIds.size)])
            }
            ReferencePanelKey.PREVIOUS -> {
                val index = actionIds.indexOf(focusedActionId).takeIf { it >= 0 } ?: 0
                ReferencePanelIntent.MoveFocus(actionIds[(index - 1).mod(actionIds.size)])
            }
            ReferencePanelKey.SCROLL_FORWARD -> ReferencePanelIntent.SetScrollOffset(
                (plan.appliedScrollOffsetDp + plan.contentViewport.height * 0.8f)
                    .coerceAtMost(plan.maxScrollOffsetDp)
            )
            ReferencePanelKey.SCROLL_BACKWARD -> ReferencePanelIntent.SetScrollOffset(
                (plan.appliedScrollOffsetDp - plan.contentViewport.height * 0.8f)
                    .coerceAtLeast(0f)
            )
        }
    }
}
