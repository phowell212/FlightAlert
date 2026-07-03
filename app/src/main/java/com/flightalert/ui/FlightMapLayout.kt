@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.graphics.RectF
import com.flightalert.map.ScreenPoint
import kotlin.math.max
import kotlin.math.min

data class FlightMapLayoutState(
    val following_location: Boolean,
    val has_location: Boolean,
    val has_selected_flight_path: Boolean,
    val show_previous_flights: Boolean,
    val show_clear_flight_path: Boolean
)

data class MapSurfaceTarget(
    val bounds: RectF,
    val action: MapSurfaceAction
)

enum class MapSurfaceAction {
    TOGGLE_PREVIOUS_FLIGHTS,
    CLEAR_SELECTED_FLIGHT_PATH,
    SHOW_SELECTED_FLIGHT_PATH,
    RECENTER,
    OPEN_SETTINGS,
    OPEN_FILTERS,
    OPEN_TRAFFIC_DETAILS
}

// Owns map and panel geometry so FlightMapView can ask where things go without doing rectangle math inline.
class FlightMapLayout(private val scale_dp: (Float) -> Float) {
    // Keep the top status clear of the traffic card on wide screens and full-width on narrow screens.
    fun top_status_bounds(w: Float, h: Float): RectF {
        val left = dp(12)
        val top = dp(12)
        val right = if (is_wide_layout(w, h)) {
            min(info_panel_bounds(w, h).left - dp(12), left + dp(620))
        } else {
            w - dp(12)
        }
        return RectF(left, top, right, top + dp(66))
    }

    // Context controls share one row or stack above the toolbar when width gets tight.
    fun recenter_button_bounds(w: Float, h: Float, state: FlightMapLayoutState): RectF {
        val slot = if (state.has_selected_flight_path) 1 else 0
        return context_button_bounds(w, h, slot)
    }

    fun flight_path_button_bounds(w: Float, h: Float): RectF {
        return context_button_bounds(w, h, 0)
    }

    fun previous_flights_button_bounds(w: Float, h: Float, state: FlightMapLayoutState): RectF {
        var slot = 0
        if (state.has_selected_flight_path) slot++
        if (!state.following_location && state.has_location) slot++
        return context_button_bounds(w, h, slot)
    }

    fun clear_flight_path_button_bounds(w: Float, h: Float, state: FlightMapLayoutState): RectF {
        var slot = 0
        if (state.has_selected_flight_path) slot++
        if (!state.following_location && state.has_location) slot++
        if (state.show_previous_flights) slot++
        return context_button_bounds(w, h, slot)
    }

    // Slot-based context buttons let recenter/path/history/clear appear without overlapping filters.
    fun context_button_bounds(w: Float, h: Float, slot: Int): RectF {
        val anchor = filters_button_bounds(w, h)
        val size = dp(44)
        val gap = dp(10)
        val left = anchor.right + gap + slot * (size + gap)
        return if (left + size <= w - dp(12)) {
            RectF(left, anchor.top, left + size, anchor.top + size)
        } else {
            val stacked_left = anchor.left + slot * (size + gap)
            val top = anchor.top - size - gap
            RectF(stacked_left, top, stacked_left + size, top + size)
        }
    }

    fun settings_button_bounds(w: Float, h: Float): RectF {
        val info = info_panel_bounds(w, h)
        val width = dp(84)
        val height = dp(44)
        val x = dp(12)
        val y = if (is_wide_layout(w, h)) h - height - dp(14) else info.top - height - dp(12)
        return RectF(x, y, x + width, y + height)
    }

    fun filters_button_bounds(w: Float, h: Float): RectF {
        val settings = settings_button_bounds(w, h)
        val gap = dp(10)
        val width = dp(84)
        return RectF(
            settings.right + gap,
            settings.top,
            settings.right + gap + width,
            settings.bottom
        )
    }

    fun map_surface_targets(w: Float, h: Float, state: FlightMapLayoutState): List<MapSurfaceTarget> {
        val targets = ArrayList<MapSurfaceTarget>(7)
        fun add(bounds: RectF, action: MapSurfaceAction) {
            targets.add(MapSurfaceTarget(bounds, action))
        }
        if (state.show_previous_flights) {
            add(previous_flights_button_bounds(w, h, state), MapSurfaceAction.TOGGLE_PREVIOUS_FLIGHTS)
        }
        if (state.show_clear_flight_path) {
            add(clear_flight_path_button_bounds(w, h, state), MapSurfaceAction.CLEAR_SELECTED_FLIGHT_PATH)
        }
        if (state.has_location && state.has_selected_flight_path) {
            add(flight_path_button_bounds(w, h), MapSurfaceAction.SHOW_SELECTED_FLIGHT_PATH)
        }
        if (state.has_location && !state.following_location) {
            add(recenter_button_bounds(w, h, state), MapSurfaceAction.RECENTER)
        }
        add(settings_button_bounds(w, h), MapSurfaceAction.OPEN_SETTINGS)
        add(filters_button_bounds(w, h), MapSurfaceAction.OPEN_FILTERS)
        add(info_panel_bounds(w, h), MapSurfaceAction.OPEN_TRAFFIC_DETAILS)
        return targets
    }

    fun map_surface_action_at(
        w: Float,
        h: Float,
        state: FlightMapLayoutState,
        x: Float,
        y: Float
    ): MapSurfaceAction? {
        return map_surface_targets(w, h, state)
            .firstOrNull { it.bounds.contains(x, y) }
            ?.action
    }

    fun map_surface_blocks_aircraft_selection(
        w: Float,
        h: Float,
        state: FlightMapLayoutState,
        x: Float,
        y: Float
    ): Boolean {
        return top_status_bounds(w, h).contains(x, y) ||
                map_surface_targets(w, h, state).any { it.bounds.contains(x, y) }
    }

    fun traffic_label_avoid_rects(w: Float, h: Float, padding: Float): List<RectF> {
        return listOf(
            top_status_bounds(w, h).padded_copy(padding),
            info_panel_bounds(w, h).padded_copy(padding),
            settings_button_bounds(w, h).padded_copy(padding),
            filters_button_bounds(w, h).padded_copy(padding)
        )
    }

    // Settings panel changes shape for landscape-short devices before it starts clipping.
    fun settings_panel_bounds(w: Float, h: Float): RectF {
        val compact = w > h && h < dp(500)
        val narrow_portrait = !compact && w < dp(430)
        val width = if (compact) min(w - dp(24), dp(860)) else min(w - dp(32), dp(430))
        val height = when {
            compact -> min(h - dp(16), dp(320))
            narrow_portrait -> h - dp(32)
            else -> min(h - dp(32), dp(660))
        }
        val top = max(if (compact) dp(8) else dp(16), (h - height) / 2f)
        return RectF((w - width) / 2f, top, (w + width) / 2f, top + height)
    }

    private fun display_setting_button_bounds(
        panel: RectF,
        compact_top_dp: Int,
        portrait_top_dp: Int
    ): RectF = setting_row_button_bounds(panel, compact_top_dp, portrait_top_dp)

    private fun map_setting_button_bounds(
        panel: RectF,
        compact_top_dp: Int,
        portrait_top_dp: Int
    ): RectF = setting_row_button_bounds(panel, compact_top_dp, portrait_top_dp)

    private fun safety_setting_button_bounds(
        panel: RectF,
        compact_top_dp: Int,
        portrait_top_dp: Int
    ): RectF = setting_row_button_bounds(panel, compact_top_dp, portrait_top_dp)

    private fun setting_row_button_bounds(
        panel: RectF,
        compact_top_dp: Int,
        portrait_top_dp: Int
    ): RectF {
        if (is_compact_settings_panel(panel)) {
            val top = panel.top + dp(compact_top_dp)
            return compact_settings_submenu_button_bounds(panel, top)
        }
        val top = panel.top + dp(portrait_top_dp)
        return RectF(panel.left + dp(18), top, panel.right - dp(18), top + dp(36))
    }

    private fun compact_settings_submenu_button_bounds(panel: RectF, top: Float): RectF {
        return RectF(panel.left + dp(18), top, panel.right - dp(18), top + dp(36))
    }

    fun imperial_button_bounds(panel: RectF): RectF =
        display_setting_button_bounds(panel, compact_top_dp = 66, portrait_top_dp = 88)

    fun metric_button_bounds(panel: RectF): RectF =
        display_setting_button_bounds(panel, compact_top_dp = 112, portrait_top_dp = 134)

    fun close_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.right - dp(112),
            panel.top + dp(14),
            panel.right - dp(18),
            panel.top + dp(50)
        )
    }

    fun settings_display_button_bounds(panel: RectF): RectF =
        settings_hub_button_bounds(panel, 0)

    fun settings_map_button_bounds(panel: RectF): RectF =
        settings_hub_button_bounds(panel, 1)

    fun settings_alerts_button_bounds(panel: RectF): RectF =
        settings_hub_button_bounds(panel, 2)

    fun settings_info_button_bounds(panel: RectF): RectF =
        settings_hub_button_bounds(panel, 3)

    private fun settings_hub_button_bounds(panel: RectF, index: Int): RectF {
        val gap = dp(10)
        val height = dp(36)
        return if (is_compact_settings_panel(panel)) {
            val columns = 2
            val row = index / columns
            val column = index % columns
            val width = (panel.width() - dp(36) - gap) / columns
            val left = panel.left + dp(18) + column * (width + gap)
            val top = panel.top + dp(74) + row * (height + gap)
            RectF(left, top, left + width, top + height)
        } else {
            val top = panel.top + dp(86) + index * (height + gap)
            RectF(panel.left + dp(18), top, panel.right - dp(18), top + height)
        }
    }

    fun map_source_button_bounds(panel: RectF): RectF =
        map_setting_button_bounds(
            panel,
            compact_top_dp = 66,
            portrait_top_dp = 88
        )

    fun map_labels_button_bounds(panel: RectF): RectF =
        map_setting_button_bounds(
            panel,
            compact_top_dp = 112,
            portrait_top_dp = 134
        )

    fun aviation_layers_button_bounds(panel: RectF): RectF =
        map_setting_button_bounds(
            panel,
            compact_top_dp = 158,
            portrait_top_dp = 180
        )

    fun theme_button_bounds(panel: RectF): RectF =
        display_setting_button_bounds(panel, compact_top_dp = 158, portrait_top_dp = 180)

    fun alerts_toggle_bounds(panel: RectF): RectF =
        safety_setting_button_bounds(panel, compact_top_dp = 66, portrait_top_dp = 88)

    fun alert_distance_minus_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            adjuster_minus_bounds(priority_alert_control_area(panel), panel.top + dp(132))
        } else {
            adjuster_minus_bounds(panel, panel.top + dp(178))
        }
    }

    fun alert_distance_plus_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            adjuster_plus_bounds(priority_alert_control_area(panel), panel.top + dp(132))
        } else {
            adjuster_plus_bounds(panel, panel.top + dp(178))
        }
    }

    fun alert_altitude_minus_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            adjuster_minus_bounds(priority_alert_control_area(panel), panel.top + dp(200))
        } else {
            adjuster_minus_bounds(panel, panel.top + dp(266))
        }
    }

    fun alert_altitude_plus_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            adjuster_plus_bounds(priority_alert_control_area(panel), panel.top + dp(200))
        } else {
            adjuster_plus_bounds(panel, panel.top + dp(266))
        }
    }

    fun priority_tracker_button_bounds(panel: RectF): RectF =
        safety_setting_button_bounds(panel, compact_top_dp = 112, portrait_top_dp = 134)

    fun monitoring_notification_status_bounds(panel: RectF): RectF =
        safety_setting_button_bounds(panel, compact_top_dp = 158, portrait_top_dp = 180)

    fun monitoring_notification_hider_button_bounds(panel: RectF): RectF =
        safety_setting_button_bounds(panel, compact_top_dp = 204, portrait_top_dp = 226)

    fun impact_source_button_bounds(panel: RectF, index: Int, source_count: Int): RectF {
        val gap = dp(10)
        val safe_index = index.coerceIn(0, source_count - 1)
        return if (is_compact_settings_panel(panel)) {
            val left = panel.left + dp(18)
            val button_width = (panel.width() - dp(36) - gap * (source_count - 1)) / source_count
            val x = left + safe_index * (button_width + gap)
            RectF(x, panel.bottom - dp(52), x + button_width, panel.bottom - dp(16))
        } else {
            val columns = 3
            val row = safe_index / columns
            val column = safe_index % columns
            val left = panel.left + dp(18)
            val button_width = (panel.width() - dp(36) - gap * (columns - 1)) / columns
            val x = left + column * (button_width + gap)
            val y = panel.bottom - dp(98) + row * (dp(36) + gap)
            RectF(x, y, x + button_width, y + dp(36))
        }
    }

    fun map_street_labels_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = panel.left + dp(18)
            val right = panel.centerX() - dp(5)
            return RectF(left, panel.top + dp(102), right, panel.top + dp(138))
        }
        return RectF(
            panel.left + dp(18),
            panel.top + dp(114),
            panel.right - dp(18),
            panel.top + dp(150)
        )
    }

    fun map_borders_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = panel.centerX() + dp(5)
            val right = panel.right - dp(18)
            return RectF(left, panel.top + dp(102), right, panel.top + dp(138))
        }
        return RectF(
            panel.left + dp(18),
            panel.top + dp(160),
            panel.right - dp(18),
            panel.top + dp(196)
        )
    }

    fun map_label_text_slider_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.left + dp(18), panel.top + dp(154), panel.right - dp(18), panel.top + dp(196))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(206), panel.right - dp(18), panel.top + dp(256))
        }
    }

    fun aviation_layer_status_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.left + dp(18), panel.top + dp(64), panel.right - dp(18), panel.top + dp(86))
        } else {
            RectF(
                panel.left + dp(18),
                panel.top + dp(72),
                panel.right - dp(18),
                panel.top + dp(116)
            )
        }
    }

    fun layer_atc_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(panel, row = 0, right_column = false)
    }

    fun layer_restricted_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(
            panel,
            row = if (is_compact_settings_panel(panel)) 0 else 1,
            right_column = is_compact_settings_panel(panel)
        )
    }

    fun layer_oceanic_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(
            panel,
            row = if (is_compact_settings_panel(panel)) 1 else 2,
            right_column = false
        )
    }

    fun layer_airport_labels_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(
            panel,
            row = if (is_compact_settings_panel(panel)) 1 else 3,
            right_column = is_compact_settings_panel(panel)
        )
    }

    // Aviation layer toggles use the same row/column math in compact and portrait panels.
    fun layer_toggle_bounds(panel: RectF, row: Int, right_column: Boolean): RectF {
        return if (is_compact_settings_panel(panel)) {
            val column =
                if (right_column) compact_settings_right_column(panel) else compact_settings_left_column(
                    panel
                )
            val top = panel.top + dp(104 + row * 46)
            RectF(column.left, top, column.right, top + dp(36))
        } else {
            val top = panel.top + dp(126 + row * 46)
            RectF(panel.left + dp(18), top, panel.right - dp(18), top + dp(36))
        }
    }

    fun filter_search_box_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(
                panel.left + dp(18),
                panel.top + dp(62),
                panel.right - dp(210),
                panel.top + dp(96)
            )
        } else {
            RectF(
                panel.left + dp(18),
                panel.top + dp(74),
                panel.right - dp(18),
                panel.top + dp(112)
            )
        }
    }

    fun filter_search_find_button_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(
                panel.right - dp(200),
                panel.top + dp(62),
                panel.right - dp(112),
                panel.top + dp(96)
            )
        } else {
            RectF(
                panel.left + dp(18),
                panel.top + dp(122),
                panel.centerX() - dp(5),
                panel.top + dp(156)
            )
        }
    }

    fun filter_search_clear_button_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(
                panel.right - dp(102),
                panel.top + dp(62),
                panel.right - dp(18),
                panel.top + dp(96)
            )
        } else {
            RectF(
                panel.centerX() + dp(5),
                panel.top + dp(122),
                panel.right - dp(18),
                panel.top + dp(156)
            )
        }
    }

    fun filter_aircraft_type_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(panel, row = 0, right_column = false)
    }

    fun filter_altitude_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(panel, row = 1, right_column = false)
    }

    fun filter_distance_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(panel, row = 2, right_column = false)
    }

    fun filter_status_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(
            panel,
            row = if (is_compact_settings_panel(panel)) 0 else 3,
            right_column = is_compact_settings_panel(panel)
        )
    }

    fun filter_age_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(
            panel,
            row = if (is_compact_settings_panel(panel)) 1 else 4,
            right_column = is_compact_settings_panel(panel)
        )
    }

    fun filter_alert_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(
            panel,
            row = if (is_compact_settings_panel(panel)) 2 else 5,
            right_column = is_compact_settings_panel(panel)
        )
    }

    fun filter_reset_button_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            filter_button_bounds(panel, row = 3, right_column = true)
        } else {
            RectF(
                panel.left + dp(18),
                panel.bottom - dp(74),
                panel.right - dp(18),
                panel.bottom - dp(38)
            )
        }
    }

    // Filter rows share spacing math so search, reset, and every toggle stay visible together.
    fun filter_button_bounds(panel: RectF, row: Int, right_column: Boolean): RectF {
        return if (is_compact_settings_panel(panel)) {
            val column =
                if (right_column) compact_settings_right_column(panel) else compact_settings_left_column(
                    panel
                )
            val top = panel.top + dp(120 + row * 46)
            RectF(column.left, top, column.right, top + dp(32))
        } else {
            val row_height = dp(36)
            val start = filter_search_clear_button_bounds(panel).bottom + dp(20)
            val reset_top = filter_reset_button_bounds(panel).top
            val available = (reset_top - start - row_height * 6).coerceAtLeast(dp(30))
            val gap = (available / 5f).coerceIn(dp(6), dp(16))
            val top = start + row * (row_height + gap)
            RectF(panel.left + dp(18), top, panel.right - dp(18), top + row_height)
        }
    }

    // Priority tracker reuses the settings modal size so safety controls do not introduce another layout mode.
    fun priority_tracker_panel_bounds(w: Float, h: Float): RectF {
        return settings_panel_bounds(w, h)
    }

    fun priority_close_button_bounds(panel: RectF): RectF {
        return RectF(
            panel.right - dp(112),
            panel.top + dp(14),
            panel.right - dp(18),
            panel.top + dp(50)
        )
    }

    fun priority_tracking_toggle_bounds(panel: RectF): RectF {
        val gap = dp(10)
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            val right = left.left + (left.width() - gap) / 2f
            return RectF(left.left, panel.top + dp(66), right, panel.top + dp(102))
        }
        val left = panel.left + dp(18)
        val right = panel.centerX() - gap / 2f
        return RectF(left, panel.top + dp(72), right, panel.top + dp(108))
    }

    fun priority_ring_toggle_bounds(panel: RectF): RectF {
        val gap = dp(10)
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            val button_left = left.left + (left.width() + gap) / 2f
            return RectF(button_left, panel.top + dp(66), left.right, panel.top + dp(102))
        }
        val button_left = panel.centerX() + gap / 2f
        return RectF(button_left, panel.top + dp(72), panel.right - dp(18), panel.top + dp(108))
    }

    // Compact settings means short landscape: use columns before shrinking text or clipping controls.
    fun is_compact_settings_panel(panel: RectF): Boolean {
        return panel.width() >= dp(520) && panel.height() < dp(380)
    }

    fun compact_settings_left_column(panel: RectF): RectF {
        return RectF(
            panel.left + dp(18),
            panel.top,
            panel.left + panel.width() * 0.49f,
            panel.bottom
        )
    }

    fun compact_settings_right_column(panel: RectF): RectF {
        return RectF(
            panel.left + panel.width() * 0.54f,
            panel.top,
            panel.right - dp(18),
            panel.bottom
        )
    }

    // Priority alert steppers live in the left column when the modal is split into compact columns.
    fun priority_alert_control_area(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.left, panel.top, panel.left + panel.width() * 0.49f, panel.bottom)
        } else {
            panel
        }
    }

    fun adjuster_minus_bounds(panel: RectF, top: Float): RectF {
        return RectF(panel.left + dp(18), top, panel.left + dp(72), top + dp(36))
    }

    fun adjuster_plus_bounds(panel: RectF, top: Float): RectF {
        return RectF(panel.right - dp(72), top, panel.right - dp(18), top + dp(36))
    }

    // The traffic panel anchors opposite the top/status controls and becomes a bottom card in portrait.
    fun info_panel_bounds(w: Float, h: Float): RectF {
        val margin = dp(12)
        return if (is_wide_layout(w, h)) {
            val panel_width = min(dp(360), max(dp(300), w * 0.32f))
            RectF(w - margin - panel_width, margin, w - margin, h - margin)
        } else {
            val panel_height = min(dp(176), max(dp(152), h * 0.24f))
            RectF(margin, h - margin - panel_height, w - margin, h - margin)
        }
    }

    // Following mode centers the user in the largest open map area, not necessarily the physical screen center.
    fun default_map_focus(w: Float, h: Float, state: FlightMapLayoutState): ScreenPoint {
        val open = largest_unblocked_map_rect(w, h, state)
        return ScreenPoint(open.centerX(), open.centerY())
    }

    // Find the largest rectangle not covered by cockpit UI so map fitting avoids panels and controls.
    fun largest_unblocked_map_rect(w: Float, h: Float, state: FlightMapLayoutState): RectF {
        val obstacles = map_obstacles(w, h, state)
        val xs = mutableListOf(0f, w)
        val ys = mutableListOf(0f, h)
        obstacles.forEach {
            xs += it.left.coerceIn(0f, w)
            xs += it.right.coerceIn(0f, w)
            ys += it.top.coerceIn(0f, h)
            ys += it.bottom.coerceIn(0f, h)
        }
        val sorted_x = xs.distinct().sorted()
        val sorted_y = ys.distinct().sorted()
        var best = RectF(0f, 0f, w, h)
        var best_area = -1f
        for (left_index in sorted_x.indices) {
            for (right_index in left_index + 1 until sorted_x.size) {
                for (top_index in sorted_y.indices) {
                    for (bottom_index in top_index + 1 until sorted_y.size) {
                        val candidate = RectF(
                            sorted_x[left_index],
                            sorted_y[top_index],
                            sorted_x[right_index],
                            sorted_y[bottom_index]
                        )
                        val area = candidate.width() * candidate.height()
                        if (area <= best_area) continue
                        if (obstacles.none { RectF.intersects(candidate, it) }) {
                            best = candidate
                            best_area = area
                        }
                    }
                }
            }
        }
        return best
    }

    // Obstacles are the UI rectangles that map focus, labels, and path fitting should avoid.
    fun map_obstacles(w: Float, h: Float, state: FlightMapLayoutState): List<RectF> {
        val items = mutableListOf(
            top_status_bounds(w, h),
            info_panel_bounds(w, h),
            settings_button_bounds(w, h),
            filters_button_bounds(w, h)
        )
        if (!state.following_location && state.has_location) items += recenter_button_bounds(
            w,
            h,
            state
        )
        return items
    }

    fun is_wide_layout(w: Float, h: Float): Boolean = w > h * 1.15f

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = scale_dp(value)

    private fun RectF.padded_copy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }
}
