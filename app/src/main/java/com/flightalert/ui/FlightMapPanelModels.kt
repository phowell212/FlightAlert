@file:Suppress(
    "ClassName",
    "ConvertTwoComparisonsToRangeCheck",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
import com.flightalert.VisualTheme
import com.flightalert.alerts.MonitoringNotificationHiderStatus
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter

data class SettingsPanelState(
    val units: UnitSystem,
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val map_borders_enabled: Boolean,
    val alerts_enabled: Boolean,
    val priority_tracking_enabled: Boolean,
    val watcher_notification_hider_enabled: Boolean,
    val watcher_notification_hider_status: MonitoringNotificationHiderStatus,
    val map_attribution: String
)

internal enum class FlightMapSettingsPage {
    ROOT,
    DISPLAY,
    MAP,
    ALERTS,
    MAP_LABELS,
    AVIATION_LAYERS,
    IMPACT_METHODOLOGY,
}

data class FlightMapPanelStyle(val visual_theme: VisualTheme)

data class MapLabelsPanelState(
    val street_labels_enabled: Boolean,
    val borders_enabled: Boolean,
    val label_text_scale: Float
)

data class AviationLayersPanelState(
    val status_text: String,
    val snapshot: AviationLayerSnapshot?,
    val fetch_in_flight: Boolean,
    val atc_boundaries_enabled: Boolean,
    val restricted_airspaces_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)

data class ImpactMethodologyPanelState(
    val source_labels: List<String>
)

data class FiltersPanelState(
    val filter_search_query: String,
    val filter_search_focused: Boolean,
    val aircraft_type_filter: AircraftTypeFilter,
    val altitude_filter: AltitudeFilter,
    val distance_filter: DistanceFilter,
    val flight_status_filter: FlightStatusFilter,
    val report_age_filter: ReportAgeFilter,
    val alert_volume_filter: Boolean,
    val filters_active: Boolean,
    val stats_summary: String,
    val reference_panel_plan: ReferenceFiltersPanelPlan,
    val reference_focused_action_id: String?,
    val pixels_per_dp: Float,
) {
    init {
        require(pixels_per_dp.isFinite() && pixels_per_dp > 0f) {
            "filter panel pixel density must be positive"
        }
    }
}

data class PriorityAircraftPanelRow(
    val title: String,
    val altitude: String,
    val detail: String,
    val is_extreme: Boolean
)

data class PriorityTrackerPanelState(
    val priority_tracking_enabled: Boolean,
    val priority_range_circle_visible: Boolean,
    val alert_distance_label: String,
    val alert_altitude_label: String,
    val aircraft_rows: List<PriorityAircraftPanelRow>,
    val long_press_fill: PriorityRangeButtonFillState? = null
)

enum class PriorityRangeAdjustButton {
    DISTANCE_MINUS,
    DISTANCE_PLUS,
    ALTITUDE_MINUS,
    ALTITUDE_PLUS
}

data class PriorityRangeButtonFillState(
    val button: PriorityRangeAdjustButton,
    val press_x: Float,
    val press_y: Float,
    val started_ms: Long,
    val duration_ms: Long
)

internal data class SettingsPanelHitState(
    val impact_methodology_open: Boolean,
    val aviation_layers_open: Boolean,
    val map_labels_open: Boolean,
    val display_settings_open: Boolean,
    val map_settings_open: Boolean,
    val alert_settings_open: Boolean,
    val impact_source_count: Int
)

internal data class SettingsPanelHitResult(
    val action: SettingsPanelAction,
    val index: Int = -1
)

internal data class SettingsPanelTarget(
    val bounds: RectF,
    val action: SettingsPanelAction,
    val index: Int = -1
)

internal enum class SettingsPanelAction {
    CLOSE_SUBPAGE,
    CLOSE_SETTINGS,
    OPEN_IMPACT_SOURCE,
    TOGGLE_ATC_BOUNDARIES,
    TOGGLE_RESTRICTED_AIRSPACES,
    TOGGLE_OCEANIC_TRACKS,
    TOGGLE_AIRPORT_LABELS,
    TOGGLE_MAP_LABELS,
    TOGGLE_MAP_BORDERS,
    SET_UNITS_IMPERIAL,
    SET_UNITS_METRIC,
    NEXT_THEME,
    TOGGLE_MAP_SOURCE,
    OPEN_MAP_LABELS,
    OPEN_AVIATION_LAYERS,
    TOGGLE_ALERTS,
    OPEN_NOTIFICATION_ACCESS,
    OPEN_PRIORITY_TRACKER,
    OPEN_DISPLAY_SETTINGS,
    OPEN_MAP_SETTINGS,
    OPEN_ALERT_SETTINGS,
    OPEN_IMPACT_METHODOLOGY
}

internal data class FilterPanelTarget(
    val bounds: RectF,
    val action: FilterPanelAction
)

internal sealed interface FilterPanelAction {
    data object FOCUS_SEARCH : FilterPanelAction
    data object SUBMIT_SEARCH : FilterPanelAction
    data object CLEAR_SEARCH : FilterPanelAction
    data object NEXT_AIRCRAFT_TYPE : FilterPanelAction
    data object NEXT_ALTITUDE : FilterPanelAction
    data object NEXT_DISTANCE : FilterPanelAction
    data object NEXT_STATUS : FilterPanelAction
    data object NEXT_AGE : FilterPanelAction
    data object TOGGLE_ALERT_VOLUME : FilterPanelAction
    data object RESET : FilterPanelAction
    data object CLEAR_SEARCH_FOCUS : FilterPanelAction
    data object NONE : FilterPanelAction
    data class ReferenceIntent(val intent: ReferencePanelIntent) : FilterPanelAction
}

internal data class PriorityTrackerPanelTarget(
    val bounds: RectF,
    val action: PriorityTrackerPanelAction,
    val adjust_button: PriorityRangeAdjustButton? = null
)

internal data class PriorityTrackerPanelHitResult(
    val action: PriorityTrackerPanelAction,
    val adjust_button: PriorityRangeAdjustButton? = null
)

internal enum class PriorityTrackerPanelAction {
    CLOSE,
    TOGGLE_TRACKING,
    TOGGLE_RANGE_RING,
    ADJUST_RANGE
}

enum class PriorityRangeValue {
    HORIZONTAL,
    VERTICAL
}

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
