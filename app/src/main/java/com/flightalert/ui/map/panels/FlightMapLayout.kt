package com.flightalert.ui.map.panels

import android.graphics.RectF
import com.flightalert.ui.map.ScreenPoint
import kotlin.math.max
import kotlin.math.min

data class FlightMapLayoutState(
    val following_location: Boolean,
    val has_location: Boolean,
    val has_selected_flight_path: Boolean,
    val show_previous_flights: Boolean
)

class FlightMapLayout(private val scale_dp: (Float) -> Float) {
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
        return RectF(settings.right + gap, settings.top, settings.right + gap + width, settings.bottom)
    }

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

    fun imperial_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(66), left.right, panel.top + dp(96))
        }
        return RectF(panel.left + dp(18), panel.top + dp(88), panel.right - dp(18), panel.top + dp(122))
    }

    fun metric_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(102), left.right, panel.top + dp(132))
        }
        return RectF(panel.left + dp(18), panel.top + dp(130), panel.right - dp(18), panel.top + dp(164))
    }

    fun close_button_bounds(panel: RectF): RectF {
        return RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))
    }

    fun map_source_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(182), left.right, panel.top + dp(208))
        }
        return RectF(panel.left + dp(18), panel.top + dp(244), panel.right - dp(18), panel.top + dp(278))
    }

    fun map_labels_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(212), left.right, panel.top + dp(238))
        }
        return RectF(panel.left + dp(18), panel.top + dp(284), panel.right - dp(18), panel.top + dp(318))
    }

    fun globe_web_source_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(242), left.right, panel.top + dp(268))
        }
        return RectF(panel.left + dp(18), panel.top + dp(324), panel.right - dp(18), panel.top + dp(358))
    }

    fun aviation_layers_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(272), left.right, panel.top + dp(298))
        }
        return RectF(panel.left + dp(18), panel.top + dp(364), panel.right - dp(18), panel.top + dp(398))
    }

    fun theme_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            return RectF(left.left, panel.top + dp(138), left.right, panel.top + dp(168))
        }
        return RectF(panel.left + dp(18), panel.top + dp(168), panel.right - dp(18), panel.top + dp(202))
    }

    fun alerts_toggle_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val right = compact_settings_right_column(panel)
            return RectF(right.left, panel.top + dp(66), right.right, panel.top + dp(96))
        }
        return RectF(panel.left + dp(18), panel.top + dp(452), panel.right - dp(18), panel.top + dp(486))
    }

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

    fun priority_tracker_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val right = compact_settings_right_column(panel)
            return RectF(right.left, panel.top + dp(102), right.right, panel.top + dp(132))
        }
        return RectF(panel.left + dp(18), panel.top + dp(492), panel.right - dp(18), panel.top + dp(526))
    }

    fun impact_methodology_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val right = compact_settings_right_column(panel)
            return RectF(right.left, panel.top + dp(166), right.right, panel.top + dp(196))
        }
        return RectF(panel.left + dp(18), panel.top + dp(580), panel.right - dp(18), panel.top + dp(614))
    }

    fun impact_source_button_bounds(panel: RectF, index: Int, source_count: Int): RectF {
        val gap = dp(8)
        val safe_index = index.coerceIn(0, source_count - 1)
        return if (is_compact_settings_panel(panel)) {
            val left = panel.left + dp(18)
            val button_width = (panel.width() - dp(36) - gap * (source_count - 1)) / source_count
            val x = left + safe_index * (button_width + gap)
            RectF(x, panel.bottom - dp(46), x + button_width, panel.bottom - dp(16))
        } else {
            val columns = 3
            val row = safe_index / columns
            val column = safe_index % columns
            val left = panel.left + dp(18)
            val button_width = (panel.width() - dp(36) - gap * (columns - 1)) / columns
            val x = left + column * (button_width + gap)
            val y = panel.bottom - dp(92) + row * (dp(34) + gap)
            RectF(x, y, x + button_width, y + dp(34))
        }
    }

    fun map_labels_on_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = panel.left + dp(18)
            val right = panel.centerX() - dp(5)
            return RectF(left, panel.top + dp(92), right, panel.top + dp(126))
        }
        return RectF(panel.left + dp(18), panel.top + dp(102), panel.right - dp(18), panel.top + dp(138))
    }

    fun map_labels_off_button_bounds(panel: RectF): RectF {
        if (is_compact_settings_panel(panel)) {
            val left = panel.centerX() + dp(5)
            val right = panel.right - dp(18)
            return RectF(left, panel.top + dp(92), right, panel.top + dp(126))
        }
        return RectF(panel.left + dp(18), panel.top + dp(148), panel.right - dp(18), panel.top + dp(184))
    }

    fun aviation_layer_status_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.left + dp(18), panel.top + dp(64), panel.right - dp(18), panel.top + dp(86))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(72), panel.right - dp(18), panel.top + dp(116))
        }
    }

    fun layer_atc_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(panel, row = 0, right_column = false)
    }

    fun layer_restricted_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(panel, row = if (is_compact_settings_panel(panel)) 0 else 1, right_column = is_compact_settings_panel(panel))
    }

    fun layer_oceanic_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(panel, row = if (is_compact_settings_panel(panel)) 1 else 2, right_column = false)
    }

    fun layer_airport_labels_button_bounds(panel: RectF): RectF {
        return layer_toggle_bounds(panel, row = if (is_compact_settings_panel(panel)) 1 else 3, right_column = is_compact_settings_panel(panel))
    }

    fun layer_toggle_bounds(panel: RectF, row: Int, right_column: Boolean): RectF {
        return if (is_compact_settings_panel(panel)) {
            val column = if (right_column) compact_settings_right_column(panel) else compact_settings_left_column(panel)
            val top = panel.top + dp(104 + row * 44)
            RectF(column.left, top, column.right, top + dp(32))
        } else {
            val top = panel.top + dp(126 + row * 46)
            RectF(panel.left + dp(18), top, panel.right - dp(18), top + dp(36))
        }
    }

    fun filter_search_box_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.left + dp(18), panel.top + dp(62), panel.right - dp(210), panel.top + dp(96))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(74), panel.right - dp(18), panel.top + dp(112))
        }
    }

    fun filter_search_find_button_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.right - dp(200), panel.top + dp(62), panel.right - dp(112), panel.top + dp(96))
        } else {
            RectF(panel.left + dp(18), panel.top + dp(122), panel.centerX() - dp(5), panel.top + dp(156))
        }
    }

    fun filter_search_clear_button_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.right - dp(102), panel.top + dp(62), panel.right - dp(18), panel.top + dp(96))
        } else {
            RectF(panel.centerX() + dp(5), panel.top + dp(122), panel.right - dp(18), panel.top + dp(156))
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
        return filter_button_bounds(panel, row = if (is_compact_settings_panel(panel)) 0 else 3, right_column = is_compact_settings_panel(panel))
    }

    fun filter_age_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(panel, row = if (is_compact_settings_panel(panel)) 1 else 4, right_column = is_compact_settings_panel(panel))
    }

    fun filter_alert_button_bounds(panel: RectF): RectF {
        return filter_button_bounds(panel, row = if (is_compact_settings_panel(panel)) 2 else 5, right_column = is_compact_settings_panel(panel))
    }

    fun filter_reset_button_bounds(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.right - dp(126), panel.bottom - dp(52), panel.right - dp(18), panel.bottom - dp(22))
        } else {
            RectF(panel.left + dp(18), panel.bottom - dp(74), panel.right - dp(18), panel.bottom - dp(38))
        }
    }

    fun filter_button_bounds(panel: RectF, row: Int, right_column: Boolean): RectF {
        return if (is_compact_settings_panel(panel)) {
            val column = if (right_column) compact_settings_right_column(panel) else compact_settings_left_column(panel)
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

    fun priority_tracker_panel_bounds(w: Float, h: Float): RectF {
        return settings_panel_bounds(w, h)
    }

    fun priority_close_button_bounds(panel: RectF): RectF {
        return RectF(panel.right - dp(112), panel.top + dp(14), panel.right - dp(18), panel.top + dp(48))
    }

    fun priority_tracking_toggle_bounds(panel: RectF): RectF {
        val gap = dp(10)
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            val right = left.left + (left.width() - gap) / 2f
            return RectF(left.left, panel.top + dp(66), right, panel.top + dp(96))
        }
        val left = panel.left + dp(18)
        val right = panel.centerX() - gap / 2f
        return RectF(left, panel.top + dp(72), right, panel.top + dp(110))
    }

    fun priority_ring_toggle_bounds(panel: RectF): RectF {
        val gap = dp(10)
        if (is_compact_settings_panel(panel)) {
            val left = compact_settings_left_column(panel)
            val button_left = left.left + (left.width() + gap) / 2f
            return RectF(button_left, panel.top + dp(66), left.right, panel.top + dp(96))
        }
        val button_left = panel.centerX() + gap / 2f
        return RectF(button_left, panel.top + dp(72), panel.right - dp(18), panel.top + dp(110))
    }

    fun is_compact_settings_panel(panel: RectF): Boolean {
        return panel.width() > dp(620) && panel.height() < dp(380)
    }

    fun compact_settings_left_column(panel: RectF): RectF {
        return RectF(panel.left + dp(18), panel.top, panel.left + panel.width() * 0.49f, panel.bottom)
    }

    fun compact_settings_right_column(panel: RectF): RectF {
        return RectF(panel.left + panel.width() * 0.54f, panel.top, panel.right - dp(18), panel.bottom)
    }

    fun priority_alert_control_area(panel: RectF): RectF {
        return if (is_compact_settings_panel(panel)) {
            RectF(panel.left, panel.top, panel.left + panel.width() * 0.49f, panel.bottom)
        } else {
            panel
        }
    }

    fun adjuster_minus_bounds(panel: RectF, top: Float): RectF {
        return RectF(panel.left + dp(18), top, panel.left + dp(72), top + dp(38))
    }

    fun adjuster_plus_bounds(panel: RectF, top: Float): RectF {
        return RectF(panel.right - dp(72), top, panel.right - dp(18), top + dp(38))
    }

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

    fun default_map_focus(w: Float, h: Float, state: FlightMapLayoutState): ScreenPoint {
        val open = largest_unblocked_map_rect(w, h, state)
        return ScreenPoint(open.centerX(), open.centerY())
    }

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
                        val candidate = RectF(sorted_x[left_index], sorted_y[top_index], sorted_x[right_index], sorted_y[bottom_index])
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

    fun map_obstacles(w: Float, h: Float, state: FlightMapLayoutState): List<RectF> {
        val items = mutableListOf(
            top_status_bounds(w, h),
            info_panel_bounds(w, h),
            settings_button_bounds(w, h),
            filters_button_bounds(w, h)
        )
        if (!state.following_location && state.has_location) items += recenter_button_bounds(w, h, state)
        return items
    }

    fun is_wide_layout(w: Float, h: Float): Boolean = w > h * 1.15f

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = scale_dp(value)
}
