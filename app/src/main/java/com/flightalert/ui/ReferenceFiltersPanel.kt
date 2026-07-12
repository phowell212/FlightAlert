package com.flightalert.ui

fun interface ReferenceFiltersTextMeasurer {
    fun measure(
        text: String,
        textSizeSp: Float,
        fontScale: Float,
        fontWeight: Int,
        italic: Boolean,
    ): Float
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

data class ReferenceDpRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite)) {
            "rectangle coordinates must be finite"
        }
        require(right >= left && bottom >= top) { "rectangle must not be inverted" }
    }

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun contains(other: ReferenceDpRect): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun intersection(other: ReferenceDpRect): ReferenceDpRect? {
        val clippedLeft = maxOf(left, other.left)
        val clippedTop = maxOf(top, other.top)
        val clippedRight = minOf(right, other.right)
        val clippedBottom = minOf(bottom, other.bottom)
        return if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
            ReferenceDpRect(clippedLeft, clippedTop, clippedRight, clippedBottom)
        } else {
            null
        }
    }
}

enum class ReferenceFiltersTab {
    TRAFFIC,
    MAP,
}

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

enum class ReferenceFilterSection {
    LABELS,
    OUTLINES,
}

sealed interface ReferenceStyleSwatch

data class ReferenceLabelStyleSwatch(
    val colorArgb: Int,
    val haloArgb: Int,
    val fontWeight: Int,
    val italic: Boolean,
    val letterSpacingEm: Float,
) : ReferenceStyleSwatch {
    init {
        require(fontWeight in 1..900) { "label swatch font weight is invalid" }
        require(letterSpacingEm.isFinite() && letterSpacingEm in 0f..1f) {
            "label swatch letter spacing is invalid"
        }
    }
}

enum class ReferenceOutlinePattern {
    SOLID,
    LONG_DASH,
    SHORT_DASH,
    DASH_DOT,
    DOT,
}

data class ReferenceOutlineStyleSwatch(
    val colorArgb: Int,
    val haloArgb: Int,
    val lineWidthDp: Float,
    val pattern: ReferenceOutlinePattern,
) : ReferenceStyleSwatch {
    init {
        require(lineWidthDp.isFinite() && lineWidthDp > 0f) {
            "outline swatch line width must be positive"
        }
    }
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

enum class ReferenceFiltersPanelContent {
    TRAFFIC_SLOT,
    MAP_UNAVAILABLE,
    MAP_FILTERS,
}

enum class ReferenceControlKind {
    BUTTON,
    TAB,
    SWITCH,
}

enum class ReferenceAccessibilityRole {
    BUTTON,
    TAB,
    SWITCH,
    STATUS,
    SCROLL_CONTAINER,
}

enum class ReferenceAccessibilityCategory {
    PANEL,
    TRAFFIC,
    MAP,
    LABELS,
    OUTLINES,
}

enum class ReferenceAccessibilityAction {
    CLICK,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

enum class ReferencePanelKey {
    NEXT,
    PREVIOUS,
    ACTIVATE,
    BACK,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

sealed interface ReferencePanelIntent {
    data object Dismiss : ReferencePanelIntent
    data class SelectTab(val tab: ReferenceFiltersTab) : ReferencePanelIntent
    data class ToggleMaster(val section: ReferenceFilterSection) : ReferencePanelIntent
    data class ToggleFilter(val stableFilterId: String) : ReferencePanelIntent
    data class MoveFocus(val actionId: String) : ReferencePanelIntent
    data class SetScrollOffset(val offsetDp: Float) : ReferencePanelIntent
}

data class ReferenceTextPlan(
    val text: String,
    val lines: List<String>,
    val textSizeSp: Float,
    val bounds: ReferenceDpRect,
    val fontScale: Float = 1f,
    val fontWeight: Int = 500,
    val italic: Boolean = false,
)

data class ReferenceControlPlan(
    val actionId: String,
    val kind: ReferenceControlKind,
    val label: String,
    val bounds: ReferenceDpRect,
    val category: ReferenceAccessibilityCategory,
    val selected: Boolean? = null,
    val onOff: Boolean? = null,
    val stateLabel: String? = null,
    val swatch: ReferenceStyleSwatch? = null,
    val sectionMasterEnabled: Boolean? = null,
    val labelText: ReferenceTextPlan? = null,
    val stateText: ReferenceTextPlan? = null,
    val swatchBounds: ReferenceDpRect? = null,
    val intent: ReferencePanelIntent,
)

data class ReferenceAccessibilityNode(
    val virtualId: Int,
    val stableId: String,
    val bounds: ReferenceDpRect,
    val role: ReferenceAccessibilityRole,
    val category: ReferenceAccessibilityCategory,
    val label: String,
    val selected: Boolean?,
    val onOff: Boolean?,
    val stateDescription: String?,
    val actionId: String?,
    val actions: Set<ReferenceAccessibilityAction>,
)

data class ReferenceFilterSectionPlan(
    val section: ReferenceFilterSection,
    val title: String,
    val bounds: ReferenceDpRect,
    val masterActionId: String,
    val rowActionIds: List<String>,
    val heading: ReferenceTextPlan? = null,
    val columnIndex: Int = 0,
)

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

object ReferenceFiltersPanelActions {
    const val BACK = "panel.back"
    const val TRAFFIC_TAB = "tabs.traffic"
    const val MAP_TAB = "tabs.map"
    const val LABELS_MASTER = "master.labels"
    const val OUTLINES_MASTER = "master.outlines"

    fun filter(stableFilterId: String): String = "filter.$stableFilterId"
}

class ReferenceFiltersPanel(
    textMeasurer: ReferenceFiltersTextMeasurer,
) {
    private val layout = ReferenceFiltersPanelLayout(textMeasurer)

    fun plan(input: ReferenceFiltersPanelInput): ReferenceFiltersPanelPlan =
        layout.plan(input)

    fun actionAt(
        plan: ReferenceFiltersPanelPlan,
        xDp: Float,
        yDp: Float,
    ): ReferencePanelIntent? = plan.controls.firstOrNull { control ->
        control.bounds.contains(xDp, yDp) &&
            (control.kind != ReferenceControlKind.SWITCH ||
                plan.contentViewport.contains(xDp, yDp))
    }?.intent

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
