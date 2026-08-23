@file:Suppress(
    "FunctionName",
)

package com.flightalert.ui

import android.graphics.RectF

internal const val MAP_LABEL_TEXT_SCALE_MIN = 1f
internal const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f

internal fun map_label_text_scale_for_x(x: Float, bounds: RectF): Float {
    val progress = ((x - bounds.left) / bounds.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
    return MAP_LABEL_TEXT_SCALE_MIN + progress * (MAP_LABEL_TEXT_SCALE_MAX - MAP_LABEL_TEXT_SCALE_MIN)
}

private fun FlightMapLayout.settings_panel_targets(
    panel: RectF,
    state: SettingsPanelHitState
): List<SettingsPanelTarget> {
    val targets = ArrayList<SettingsPanelTarget>(8)
    fun add(bounds: RectF, action: SettingsPanelAction, index: Int = -1) {
        targets.add(SettingsPanelTarget(bounds, action, index))
    }
    if (state.impact_methodology_open) {
        add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SUBPAGE)
        for (index in 0 until state.impact_source_count) {
            add(
                impact_source_button_bounds(panel, index, state.impact_source_count),
                SettingsPanelAction.OPEN_IMPACT_SOURCE,
                index
            )
        }
        return targets
    }
    if (state.aviation_layers_open) {
        add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SUBPAGE)
        add(layer_atc_button_bounds(panel), SettingsPanelAction.TOGGLE_ATC_BOUNDARIES)
        add(layer_restricted_button_bounds(panel), SettingsPanelAction.TOGGLE_RESTRICTED_AIRSPACES)
        add(layer_oceanic_button_bounds(panel), SettingsPanelAction.TOGGLE_OCEANIC_TRACKS)
        add(layer_airport_labels_button_bounds(panel), SettingsPanelAction.TOGGLE_AIRPORT_LABELS)
        return targets
    }
    if (state.map_labels_open) {
        add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SUBPAGE)
        add(map_borders_button_bounds(panel), SettingsPanelAction.TOGGLE_MAP_BORDERS)
        return targets
    }
    if (state.display_settings_open) {
        add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SUBPAGE)
        add(imperial_button_bounds(panel), SettingsPanelAction.SET_UNITS_IMPERIAL)
        add(metric_button_bounds(panel), SettingsPanelAction.SET_UNITS_METRIC)
        add(theme_button_bounds(panel), SettingsPanelAction.NEXT_THEME)
        return targets
    }
    if (state.map_settings_open) {
        add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SUBPAGE)
        add(map_source_button_bounds(panel), SettingsPanelAction.TOGGLE_MAP_SOURCE)
        add(map_labels_button_bounds(panel), SettingsPanelAction.OPEN_MAP_LABELS)
        add(aviation_layers_button_bounds(panel), SettingsPanelAction.OPEN_AVIATION_LAYERS)
        return targets
    }
    if (state.alert_settings_open) {
        add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SUBPAGE)
        add(alerts_toggle_bounds(panel), SettingsPanelAction.TOGGLE_ALERTS)
        add(monitoring_notification_hider_button_bounds(panel), SettingsPanelAction.OPEN_NOTIFICATION_ACCESS)
        add(priority_tracker_button_bounds(panel), SettingsPanelAction.OPEN_PRIORITY_TRACKER)
        return targets
    }
    add(close_button_bounds(panel), SettingsPanelAction.CLOSE_SETTINGS)
    add(settings_display_button_bounds(panel), SettingsPanelAction.OPEN_DISPLAY_SETTINGS)
    add(settings_map_button_bounds(panel), SettingsPanelAction.OPEN_MAP_SETTINGS)
    add(settings_alerts_button_bounds(panel), SettingsPanelAction.OPEN_ALERT_SETTINGS)
    add(settings_info_button_bounds(panel), SettingsPanelAction.OPEN_IMPACT_METHODOLOGY)
    return targets
}

internal fun FlightMapLayout.settings_panel_hit_result(
    panel: RectF,
    x: Float,
    y: Float,
    state: SettingsPanelHitState
): SettingsPanelHitResult? {
    return settings_panel_targets(panel, state)
        .firstOrNull { it.bounds.contains(x, y) }
        ?.let { SettingsPanelHitResult(it.action, it.index) }
}

private fun FlightMapLayout.filter_panel_targets(
    panel: RectF,
    state: FiltersPanelState
): List<FilterPanelTarget> {
    return buildList {
        val plan = state.reference_panel_plan
        val contentViewport = plan.contentViewport.to_pixel_rect(panel, state.pixels_per_dp)
        plan.controls.forEach { control ->
            val bounds = control.bounds.to_pixel_rect(panel, state.pixels_per_dp)
            val targetBounds = if (control.kind == ReferenceControlKind.SWITCH) {
                bounds.intersection_or_null(contentViewport)
            } else {
                bounds
            }
            targetBounds?.let {
                add(
                    FilterPanelTarget(it, FilterPanelAction.ReferenceIntent(control.intent))
                )
            }
        }
        if (plan.content == ReferenceFiltersPanelContent.TRAFFIC_SLOT) {
            val legacyPanel = legacy_traffic_panel_bounds(panel, state)
            val trafficTargets = listOf(
                FilterPanelTarget(filter_search_box_bounds(legacyPanel), FilterPanelAction.FOCUS_SEARCH),
                FilterPanelTarget(filter_search_find_button_bounds(legacyPanel), FilterPanelAction.SUBMIT_SEARCH),
                FilterPanelTarget(filter_search_clear_button_bounds(legacyPanel), FilterPanelAction.CLEAR_SEARCH),
                FilterPanelTarget(filter_aircraft_type_button_bounds(legacyPanel), FilterPanelAction.NEXT_AIRCRAFT_TYPE),
                FilterPanelTarget(filter_altitude_button_bounds(legacyPanel), FilterPanelAction.NEXT_ALTITUDE),
                FilterPanelTarget(filter_distance_button_bounds(legacyPanel), FilterPanelAction.NEXT_DISTANCE),
                FilterPanelTarget(filter_status_button_bounds(legacyPanel), FilterPanelAction.NEXT_STATUS),
                FilterPanelTarget(filter_age_button_bounds(legacyPanel), FilterPanelAction.NEXT_AGE),
                FilterPanelTarget(filter_alert_button_bounds(legacyPanel), FilterPanelAction.TOGGLE_ALERT_VOLUME),
                FilterPanelTarget(filter_reset_button_bounds(legacyPanel), FilterPanelAction.RESET),
            )
            trafficTargets.forEach { target ->
                target.bounds.intersection_or_null(contentViewport)?.let { clipped ->
                    add(target.copy(bounds = clipped))
                }
            }
        }
    }
}

internal fun FlightMapLayout.filter_panel_action_at(
    panel: RectF,
    x: Float,
    y: Float,
    state: FiltersPanelState
): FilterPanelAction {
    return filter_panel_targets(panel, state)
        .firstOrNull { it.bounds.contains(x, y) }
        ?.action
        ?: if (state.reference_panel_plan.content == ReferenceFiltersPanelContent.TRAFFIC_SLOT) {
            FilterPanelAction.CLEAR_SEARCH_FOCUS
        } else {
            FilterPanelAction.NONE
        }
}

internal fun FlightMapLayout.legacy_traffic_panel_bounds(
    panel: RectF,
    state: FiltersPanelState,
): RectF {
    val density = state.pixels_per_dp
    val plan = state.reference_panel_plan
    val legacySearchTopDp = if (is_compact_settings_panel(panel)) 62f else 74f
    val top = panel.top +
        (plan.contentViewport.top - legacySearchTopDp - plan.appliedScrollOffsetDp) * density
    return RectF(panel.left, top, panel.right, top + panel.height())
}

internal fun ReferenceDpRect.to_pixel_rect(panel: RectF, density: Float): RectF = RectF(
    panel.left + left * density,
    panel.top + top * density,
    panel.left + right * density,
    panel.top + bottom * density,
)

private fun RectF.intersection_or_null(other: RectF): RectF? {
    val clippedLeft = maxOf(left, other.left)
    val clippedTop = maxOf(top, other.top)
    val clippedRight = minOf(right, other.right)
    val clippedBottom = minOf(bottom, other.bottom)
    return if (clippedRight > clippedLeft && clippedBottom > clippedTop) {
        RectF(clippedLeft, clippedTop, clippedRight, clippedBottom)
    } else {
        null
    }
}

private fun FlightMapLayout.priority_tracker_panel_targets(panel: RectF): List<PriorityTrackerPanelTarget> {
    return listOf(
        PriorityTrackerPanelTarget(priority_close_button_bounds(panel), PriorityTrackerPanelAction.CLOSE),
        PriorityTrackerPanelTarget(priority_tracking_toggle_bounds(panel), PriorityTrackerPanelAction.TOGGLE_TRACKING),
        PriorityTrackerPanelTarget(priority_ring_toggle_bounds(panel), PriorityTrackerPanelAction.TOGGLE_RANGE_RING),
        PriorityTrackerPanelTarget(
            priority_adjust_button_bounds(panel, PriorityRangeAdjustButton.DISTANCE_MINUS),
            PriorityTrackerPanelAction.ADJUST_RANGE,
            PriorityRangeAdjustButton.DISTANCE_MINUS
        ),
        PriorityTrackerPanelTarget(
            priority_adjust_button_bounds(panel, PriorityRangeAdjustButton.DISTANCE_PLUS),
            PriorityTrackerPanelAction.ADJUST_RANGE,
            PriorityRangeAdjustButton.DISTANCE_PLUS
        ),
        PriorityTrackerPanelTarget(
            priority_adjust_button_bounds(panel, PriorityRangeAdjustButton.ALTITUDE_MINUS),
            PriorityTrackerPanelAction.ADJUST_RANGE,
            PriorityRangeAdjustButton.ALTITUDE_MINUS
        ),
        PriorityTrackerPanelTarget(
            priority_adjust_button_bounds(panel, PriorityRangeAdjustButton.ALTITUDE_PLUS),
            PriorityTrackerPanelAction.ADJUST_RANGE,
            PriorityRangeAdjustButton.ALTITUDE_PLUS
        )
    )
}

internal fun FlightMapLayout.priority_tracker_panel_hit_result(
    panel: RectF,
    x: Float,
    y: Float
): PriorityTrackerPanelHitResult? {
    return priority_tracker_panel_targets(panel)
        .firstOrNull { it.bounds.contains(x, y) }
        ?.let { PriorityTrackerPanelHitResult(it.action, it.adjust_button) }
}

internal fun FlightMapLayout.priority_adjust_button_bounds(
    panel: RectF,
    button: PriorityRangeAdjustButton
): RectF {
    return when (button) {
        PriorityRangeAdjustButton.DISTANCE_MINUS -> alert_distance_minus_bounds(panel)
        PriorityRangeAdjustButton.DISTANCE_PLUS -> alert_distance_plus_bounds(panel)
        PriorityRangeAdjustButton.ALTITUDE_MINUS -> alert_altitude_minus_bounds(panel)
        PriorityRangeAdjustButton.ALTITUDE_PLUS -> alert_altitude_plus_bounds(panel)
    }
}

internal fun FlightMapLayout.priority_adjust_button_at(
    panel: RectF,
    x: Float,
    y: Float
): PriorityRangeAdjustButton? {
    return PriorityRangeAdjustButton.entries.firstOrNull {
        priority_adjust_button_bounds(panel, it).contains(x, y)
    }
}
