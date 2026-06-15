package com.flightalert.ui.map.panels

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import com.flightalert.data.AviationLayerKind
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationLayerState
import com.flightalert.settings.FlightAlertSettings.AircraftFeedMode
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import com.flightalert.settings.FlightAlertSettings.VisualTheme
import com.flightalert.ui.map.traffic.AircraftTypeFilter
import com.flightalert.ui.map.traffic.AltitudeFilter
import com.flightalert.ui.map.traffic.DistanceFilter
import com.flightalert.ui.map.traffic.FlightStatusFilter
import com.flightalert.ui.map.traffic.ReportAgeFilter
import com.flightalert.ui.map.panels.FlightMapLayout
import com.flightalert.ui.map.TileSource
import com.flightalert.ui.map.UnitSystem

data class FlightMapPanelStyle(val visual_theme: VisualTheme)

data class SettingsPanelState(
    val units: UnitSystem,
    val map_source: TileSource,
    val map_labels_enabled: Boolean,
    val aircraft_feed_mode: AircraftFeedMode,
    val aviation_layers_enabled: Boolean,
    val alerts_enabled: Boolean,
    val priority_tracking_enabled: Boolean,
    val map_attribution: String,
    val aircraft_source_label: String
)

data class MapLabelsPanelState(
    val map_labels_enabled: Boolean
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
    val stats_summary: String
)

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

interface FlightMapPanelChrome {
    val layout: FlightMapLayout

    fun dp(value: Float): Float
    fun sp(value: Float): Float
    fun ellipsize(value: String, max_width: Float): String
    fun control_radius(): Float
    fun draw_panel_surface(canvas: Canvas, rect: RectF, fill: Int, alpha: Int)
    fun draw_choice_button(canvas: Canvas, rect: RectF, label: String, selected: Boolean)
    fun draw_control_surface(canvas: Canvas, rect: RectF, fill: Int, stroke: Int, selected: Boolean)
    fun draw_wrapped_text(canvas: Canvas, value: String, x: Float, y: Float, width: Float, max_lines: Int): Float
    fun request_animation_frame()
}

// Draws settings, filters, layers, methodology, and priority panels from prepared state snapshots.
class FlightMapPanelRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val chrome: FlightMapPanelChrome
) {
    private val long_press_fill_clip = Path()

    // Draw the main settings hub; subpanels own detailed choices but share this modal shell.
    fun draw_settings_panel(canvas: Canvas, w: Float, h: Float, style: FlightMapPanelStyle, state: SettingsPanelState) {
        paint.style = Paint.Style.FILL
        val rect = chrome.layout.settings_panel_bounds(w, h)
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel_alt, style.visual_theme.style.modal_panel_alpha)

        draw_panel_title(canvas, rect, "Settings", style)
        chrome.draw_choice_button(canvas, chrome.layout.close_button_bounds(rect), "Close", false)

        if (chrome.layout.is_compact_settings_panel(rect)) {
            draw_compact_settings_panel_contents(canvas, rect, style, state)
            return
        }

        draw_settings_section_label(canvas, rect.left + dp(18), rect.top + dp(74), "Display", style)
        chrome.draw_choice_button(canvas, chrome.layout.imperial_button_bounds(rect), "Miles / feet", state.units == UnitSystem.IMPERIAL)
        chrome.draw_choice_button(canvas, chrome.layout.metric_button_bounds(rect), "Kilometers / meters", state.units == UnitSystem.METRIC)
        chrome.draw_choice_button(canvas, chrome.layout.theme_button_bounds(rect), "Theme: ${style.visual_theme.display_name}", true)

        draw_settings_section_label(canvas, rect.left + dp(18), rect.top + dp(230), "Map", style)
        chrome.draw_choice_button(canvas, chrome.layout.map_source_button_bounds(rect), if (state.map_source == TileSource.SATELLITE) "Satellite map" else "Street map", state.map_source == TileSource.SATELLITE)
        chrome.draw_choice_button(canvas, chrome.layout.map_labels_button_bounds(rect), if (state.map_labels_enabled) "Map labels on" else "Map labels off", state.map_labels_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.globe_bin_craft_source_button_bounds(rect), state.aircraft_feed_mode.display_name, true)
        chrome.draw_choice_button(canvas, chrome.layout.aviation_layers_button_bounds(rect), "Aviation layers", state.aviation_layers_enabled)

        draw_settings_section_label(canvas, rect.left + dp(18), rect.top + dp(438), "Safety", style)
        chrome.draw_choice_button(canvas, chrome.layout.alerts_toggle_bounds(rect), if (state.alerts_enabled) "Hazard alerts on" else "Hazard alerts off", state.alerts_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.priority_tracker_button_bounds(rect), "Notification range and tracker", state.priority_tracking_enabled)

        draw_settings_section_label(canvas, rect.left + dp(18), rect.top + dp(566), "Reference", style)
        chrome.draw_choice_button(canvas, chrome.layout.impact_methodology_button_bounds(rect), "Impact methodology", false)

        val footer_top = rect.bottom - dp(38)
        if (chrome.layout.impact_methodology_button_bounds(rect).bottom + dp(24) <= footer_top) {
            text_paint.textAlign = Paint.Align.LEFT
            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(11)
            text_paint.color = style.visual_theme.colors.muted
            draw_fitted_left_text(canvas, "Map: ${state.map_attribution}", rect.left + dp(18), rect.bottom - dp(38), rect.width() - dp(36), sp(11), sp(9))
            val source_label = "Aircraft: ${state.aircraft_source_label}; paths: live trace sources"
            canvas.drawText(chrome.ellipsize(source_label, rect.width() - dp(36)), rect.left + dp(18), rect.bottom - dp(18), text_paint)
        }
    }

    // Draw map-label provider choices without implying label tiles are available for every map source.
    fun draw_map_labels_panel(canvas: Canvas, w: Float, h: Float, style: FlightMapPanelStyle, state: MapLabelsPanelState) {
        val rect = chrome.layout.settings_panel_bounds(w, h)
        val compact = chrome.layout.is_compact_settings_panel(rect)
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel_alt, style.visual_theme.style.modal_panel_alpha)

        draw_panel_title(canvas, rect, "Map labels", style)
        chrome.draw_choice_button(canvas, chrome.layout.close_button_bounds(rect), "Back", false)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = if (compact) sp(11) else sp(12)
        text_paint.color = style.visual_theme.colors.muted
        val label_y = if (compact) rect.top + dp(74) else rect.top + dp(82)
        canvas.drawText("Map labels", rect.left + dp(18), label_y, text_paint)

        chrome.draw_choice_button(canvas, chrome.layout.map_labels_on_button_bounds(rect), "Labels on", state.map_labels_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.map_labels_off_button_bounds(rect), "Labels off", !state.map_labels_enabled)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = if (compact) sp(10) else sp(11)
        text_paint.color = style.visual_theme.colors.muted
        val source_y = if (compact) rect.top + dp(154) else rect.top + dp(216)
        val source_text = if (state.map_labels_enabled) {
            "Current: labeled or reference tiles"
        } else {
            "Current: no-label base where available"
        }
        draw_fitted_left_text(canvas, source_text, rect.left + dp(18), source_y, rect.width() - dp(36), text_paint.textSize, sp(8))
    }

    // Draw aviation layer toggles with source status visible before any layer is treated as available.
    fun draw_aviation_layers_panel(canvas: Canvas, w: Float, h: Float, style: FlightMapPanelStyle, state: AviationLayersPanelState) {
        val rect = chrome.layout.settings_panel_bounds(w, h)
        val compact = chrome.layout.is_compact_settings_panel(rect)
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel_alt, style.visual_theme.style.modal_panel_alpha)

        draw_panel_title(canvas, rect, "Layers", style)
        chrome.draw_choice_button(canvas, chrome.layout.close_button_bounds(rect), "Back", false)

        val status_rect = chrome.layout.aviation_layer_status_bounds(rect)
        text_paint.isFakeBoldText = false
        text_paint.textSize = if (compact) sp(10) else sp(11)
        text_paint.color = style.visual_theme.colors.muted
        chrome.draw_wrapped_text(canvas, state.status_text, status_rect.left, status_rect.top, status_rect.width(), max_lines = if (compact) 1 else 2)

        draw_layer_toggle_button(canvas, chrome.layout.layer_atc_button_bounds(rect), "ATC boundaries", state.atc_boundaries_enabled, AviationLayerKind.ATC_BOUNDARIES, state)
        draw_layer_toggle_button(canvas, chrome.layout.layer_restricted_button_bounds(rect), "Restricted airspace", state.restricted_airspaces_enabled, AviationLayerKind.RESTRICTED_AIRSPACES, state)
        draw_layer_toggle_button(canvas, chrome.layout.layer_oceanic_button_bounds(rect), "Oceanic tracks", state.oceanic_tracks_enabled, AviationLayerKind.OCEANIC_TRACKS, state)
        draw_layer_toggle_button(canvas, chrome.layout.layer_airport_labels_button_bounds(rect), "Airport labels", state.airport_labels_enabled, AviationLayerKind.AIRPORTS, state)
    }

    // Draw methodology text and source buttons so impact numbers remain explained and auditable.
    fun draw_impact_methodology_panel(canvas: Canvas, w: Float, h: Float, style: FlightMapPanelStyle, state: ImpactMethodologyPanelState) {
        val rect = chrome.layout.settings_panel_bounds(w, h)
        val compact = chrome.layout.is_compact_settings_panel(rect)
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel_alt, style.visual_theme.style.modal_panel_alpha)

        draw_panel_title(canvas, rect, "Impact methodology", style)
        chrome.draw_choice_button(canvas, chrome.layout.close_button_bounds(rect), "Back", false)

        val items = impact_methodology_items()
        if (compact) {
            val left = RectF(rect.left + dp(18), rect.top + dp(64), rect.centerX() - dp(10), rect.bottom - dp(62))
            val right = RectF(rect.centerX() + dp(10), left.top, rect.right - dp(18), left.bottom)
            draw_methodology_column(canvas, left, items.take(3), style)
            draw_methodology_column(canvas, right, items.drop(3), style)
        } else {
            var y = rect.top + dp(72)
            val text_rect = RectF(rect.left + dp(18), y, rect.right - dp(18), rect.bottom - dp(104))
            items.forEach { item ->
                y = draw_methodology_item(canvas, text_rect, y, item.first, item.second, max_lines = 3, style)
            }
        }

        state.source_labels.forEachIndexed { index, label ->
            chrome.draw_choice_button(canvas, chrome.layout.impact_source_button_bounds(rect, index, state.source_labels.size), label, false)
        }
    }

    // Draw filters as explicit live-traffic filters, including search focus and current match summary.
    fun draw_filters_panel(canvas: Canvas, w: Float, h: Float, style: FlightMapPanelStyle, state: FiltersPanelState) {
        paint.style = Paint.Style.FILL
        val rect = chrome.layout.settings_panel_bounds(w, h)
        val compact = chrome.layout.is_compact_settings_panel(rect)
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel_alt, style.visual_theme.style.modal_panel_alpha)

        draw_panel_title(canvas, rect, "Filters", style)
        chrome.draw_choice_button(canvas, chrome.layout.close_button_bounds(rect), "Close", false)

        draw_filter_search_control(canvas, rect, style, state)

        if (compact) {
            draw_compact_filters_panel_contents(canvas, rect, state)
        } else {
            draw_portrait_filters_panel_contents(canvas, rect, state)
        }

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(if (compact) 10 else 11)
        text_paint.color = style.visual_theme.colors.muted
        val stats_y = if (compact) rect.bottom - dp(18) else rect.bottom - dp(22)
        draw_fitted_left_text(canvas, state.stats_summary, rect.left + dp(18), stats_y, rect.width() - dp(36), text_paint.textSize, sp(8))
    }

    // Draw alert-volume controls and the current priority queue without inventing aircraft status.
    fun draw_priority_tracker_panel(canvas: Canvas, w: Float, h: Float, style: FlightMapPanelStyle, state: PriorityTrackerPanelState) {
        paint.style = Paint.Style.FILL
        val rect = chrome.layout.priority_tracker_panel_bounds(w, h)
        chrome.draw_panel_surface(canvas, rect, style.visual_theme.colors.panel_alt, style.visual_theme.style.modal_panel_alpha)

        draw_panel_title(canvas, rect, "Notification range", style)
        chrome.draw_choice_button(canvas, chrome.layout.priority_close_button_bounds(rect), "Close", false)

        if (chrome.layout.is_compact_settings_panel(rect)) {
            draw_compact_priority_tracker_contents(canvas, rect, style, state)
            return
        }

        chrome.draw_choice_button(canvas, chrome.layout.priority_tracking_toggle_bounds(rect), if (state.priority_tracking_enabled) "Queue on" else "Queue off", state.priority_tracking_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.priority_ring_toggle_bounds(rect), if (state.priority_range_circle_visible) "Alert ring on" else "Alert ring off", state.priority_range_circle_visible)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText("Notification range", rect.left + dp(18), rect.top + dp(136), text_paint)
        draw_adjuster_row(
            canvas,
            rect,
            rect.top + dp(162),
            "Horizontal",
            state.alert_distance_label,
            chrome.layout.alert_distance_minus_bounds(rect),
            chrome.layout.alert_distance_plus_bounds(rect),
            style,
            state.long_press_fill,
            PriorityRangeAdjustButton.DISTANCE_MINUS,
            PriorityRangeAdjustButton.DISTANCE_PLUS
        )
        draw_adjuster_row(
            canvas,
            rect,
            rect.top + dp(250),
            "Vertical",
            state.alert_altitude_label,
            chrome.layout.alert_altitude_minus_bounds(rect),
            chrome.layout.alert_altitude_plus_bounds(rect),
            style,
            state.long_press_fill,
            PriorityRangeAdjustButton.ALTITUDE_MINUS,
            PriorityRangeAdjustButton.ALTITUDE_PLUS
        )

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText("Aircraft in queue", rect.left + dp(18), rect.top + dp(344), text_paint)

        if (state.aircraft_rows.isEmpty()) {
            text_paint.isFakeBoldText = true
            text_paint.textSize = sp(17)
            text_paint.color = style.visual_theme.colors.text
            draw_fitted_left_text(
                canvas,
                if (state.priority_tracking_enabled) "No aircraft in queue" else "Queue is off",
                rect.left + dp(18),
                rect.top + dp(386),
                rect.width() - dp(36),
                sp(17),
                sp(10)
            )
            text_paint.isFakeBoldText = false
            return
        }

        var y = rect.top + dp(382)
        state.aircraft_rows.take(PRIORITY_PANEL_ROWS).forEach { item ->
            y = draw_priority_aircraft_row(canvas, rect, y, item, style)
        }
        if (state.aircraft_rows.size > PRIORITY_PANEL_ROWS) {
            text_paint.textAlign = Paint.Align.LEFT
            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(11)
            text_paint.color = style.visual_theme.colors.muted
            draw_fitted_left_text(canvas, "+${state.aircraft_rows.size - PRIORITY_PANEL_ROWS} more", rect.left + dp(18), y + dp(8), rect.width() - dp(36), sp(11), sp(8))
        }
    }

    // Compact settings splits display/map and safety/reference into columns instead of clipping controls.
    private fun draw_compact_settings_panel_contents(canvas: Canvas, rect: RectF, style: FlightMapPanelStyle, state: SettingsPanelState) {
        val display = chrome.layout.compact_settings_display_column(rect)
        val map = chrome.layout.compact_settings_map_column(rect)
        val safety = chrome.layout.compact_settings_safety_column(rect)
        val wide = chrome.layout.is_wide_settings_hub_panel(rect)

        draw_settings_section_label(canvas, display.left, rect.top + dp(58), "Display", style)
        chrome.draw_choice_button(canvas, chrome.layout.imperial_button_bounds(rect), "Miles / feet", state.units == UnitSystem.IMPERIAL)
        chrome.draw_choice_button(canvas, chrome.layout.metric_button_bounds(rect), "Kilometers / meters", state.units == UnitSystem.METRIC)
        chrome.draw_choice_button(canvas, chrome.layout.theme_button_bounds(rect), "Theme: ${style.visual_theme.short_name}", true)

        draw_settings_section_label(canvas, map.left, rect.top + dp(if (wide) 58 else 184), "Map", style)
        chrome.draw_choice_button(canvas, chrome.layout.map_source_button_bounds(rect), if (state.map_source == TileSource.SATELLITE) "Satellite" else "Street", state.map_source == TileSource.SATELLITE)
        chrome.draw_choice_button(canvas, chrome.layout.map_labels_button_bounds(rect), if (state.map_labels_enabled) "Labels on" else "Labels off", state.map_labels_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.globe_bin_craft_source_button_bounds(rect), state.aircraft_feed_mode.compact_name, true)
        chrome.draw_choice_button(canvas, chrome.layout.aviation_layers_button_bounds(rect), "Layers", state.aviation_layers_enabled)

        draw_settings_section_label(canvas, safety.left, rect.top + dp(58), "Safety", style)
        chrome.draw_choice_button(canvas, chrome.layout.alerts_toggle_bounds(rect), if (state.alerts_enabled) "Hazard alerts on" else "Hazard alerts off", state.alerts_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.priority_tracker_button_bounds(rect), "Notification range", state.priority_tracking_enabled)

        draw_settings_section_label(canvas, safety.left, rect.top + dp(158), "Reference", style)
        chrome.draw_choice_button(canvas, chrome.layout.impact_methodology_button_bounds(rect), "Impact method", false)
    }

    private fun draw_settings_section_label(canvas: Canvas, x: Float, y: Float, label: String, style: FlightMapPanelStyle) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(label, x, y, text_paint)
    }

    // Layer buttons include source state suffixes so unavailable or empty real layers are labeled honestly.
    private fun draw_layer_toggle_button(
        canvas: Canvas,
        rect: RectF,
        label: String,
        enabled: Boolean,
        kind: AviationLayerKind,
        state: AviationLayersPanelState
    ) {
        val status = state.snapshot?.statuses?.get(kind)
        val suffix = when {
            !enabled -> "off"
            status?.state == AviationLayerState.LOADED -> "on"
            status?.state == AviationLayerState.EMPTY -> "empty"
            status?.state == AviationLayerState.UNAVAILABLE -> "retry"
            state.fetch_in_flight -> "loading"
            else -> "on"
        }
        chrome.draw_choice_button(canvas, rect, "$label $suffix", enabled)
    }

    private fun impact_methodology_items(): List<Pair<String, String>> {
        return listOf(
            "Trace first" to "Only real trace points from the aircraft source are used. App-session dots are never stored as a path.",
            "Aircraft profile" to "Live web/feed type is tried first, then registry/API metadata. Known ICAO types use a type benchmark; otherwise class benchmarks are used.",
            "Phase model" to "Observed legs are split into ground, climb, cruise, descent, low-level, or time-only using trace altitude, speed, and timestamps.",
            "Carbon math" to "CO2 range = profile gal/hr x phase multiplier x trace hours x EIA kg CO2/gal. Jet fuel uses 9.75; avgas uses 8.31.",
            "Score" to "0-100 is log-scaled kg CO2/hr. When a phase trace exists it scores observed intensity; otherwise it scores the profile rate.",
            "Full flight" to "Shown only when real origin, destination, and trace progress are credible enough to route-scale the observed estimate.",
            "Not claimed" to "No exact fuel flow, payload, passenger count, SAF blend, contrails, NOx, noise, or per-passenger allocation is inferred."
        )
    }

    private fun draw_methodology_column(
        canvas: Canvas,
        rect: RectF,
        items: List<Pair<String, String>>,
        style: FlightMapPanelStyle
    ) {
        var y = rect.top
        items.forEach { item ->
            y = draw_methodology_item(canvas, rect, y, item.first, item.second, max_lines = 2, style)
        }
    }

    private fun draw_methodology_item(
        canvas: Canvas,
        rect: RectF,
        y: Float,
        label: String,
        body: String,
        max_lines: Int,
        style: FlightMapPanelStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11)
        text_paint.color = style.visual_theme.colors.text
        canvas.drawText(label, rect.left, y, text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11)
        text_paint.color = style.visual_theme.colors.muted
        val bottom = chrome.draw_wrapped_text(canvas, body, rect.left, y + dp(18), rect.width(), max_lines)
        return bottom + dp(12)
    }

    // Search control owns the caret animation while FlightMapView owns actual text input.
    private fun draw_filter_search_control(canvas: Canvas, panel: RectF, style: FlightMapPanelStyle, state: FiltersPanelState) {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val search = chrome.layout.filter_search_box_bounds(panel)
        val stroke = if (state.filter_search_focused) colors.accent_green else colors.button_stroke
        val fill = if (theme_style.treatment == ThemeTreatment.PLAIN) colors.button_fill else with_alpha(colors.button_fill, theme_style.control_alpha)
        chrome.draw_control_surface(canvas, search, fill, stroke, state.filter_search_focused)

        val display = state.filter_search_query.ifBlank { "Callsign, reg, ICAO hex" }
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = state.filter_search_query.isNotBlank()
        text_paint.textSize = sp(13)
        text_paint.color = if (state.filter_search_query.isBlank()) colors.muted else colors.text
        val max_width = search.width() - dp(24)
        canvas.drawText(chrome.ellipsize(display, max_width), search.left + dp(12), search.centerY() + dp(5), text_paint)
        if (state.filter_search_focused && SystemClock.elapsedRealtime() % 1000L < 560L) {
            val visible_text = if (state.filter_search_query.isBlank()) "" else chrome.ellipsize(state.filter_search_query, max_width)
            val caret_x = (search.left + dp(12) + text_paint.measureText(visible_text)).coerceAtMost(search.right - dp(12))
            stroke_paint.color = colors.accent_green
            stroke_paint.strokeWidth = dp(1.2f)
            canvas.drawLine(caret_x, search.top + dp(9), caret_x, search.bottom - dp(9), stroke_paint)
            chrome.request_animation_frame()
        }
        text_paint.isFakeBoldText = false

        chrome.draw_choice_button(canvas, chrome.layout.filter_search_find_button_bounds(panel), "Find live", false)
        chrome.draw_choice_button(canvas, chrome.layout.filter_search_clear_button_bounds(panel), "Clear", false)
    }

    // Portrait filters use one vertical list so long labels stay physically consistent.
    private fun draw_portrait_filters_panel_contents(canvas: Canvas, rect: RectF, state: FiltersPanelState) {
        draw_filter_cycle_row(canvas, chrome.layout.filter_aircraft_type_button_bounds(rect), "Type: ${state.aircraft_type_filter.short_label}", state.aircraft_type_filter != AircraftTypeFilter.ALL)
        draw_filter_cycle_row(canvas, chrome.layout.filter_altitude_button_bounds(rect), "Alt: ${state.altitude_filter.short_label}", state.altitude_filter != AltitudeFilter.ANY)
        draw_filter_cycle_row(canvas, chrome.layout.filter_distance_button_bounds(rect), "Range: ${state.distance_filter.short_label}", state.distance_filter != DistanceFilter.ANY)
        draw_filter_cycle_row(canvas, chrome.layout.filter_status_button_bounds(rect), "Status: ${state.flight_status_filter.short_label}", state.flight_status_filter != FlightStatusFilter.AIRBORNE)
        draw_filter_cycle_row(canvas, chrome.layout.filter_age_button_bounds(rect), "Age: ${state.report_age_filter.short_label}", state.report_age_filter != ReportAgeFilter.ANY)
        draw_filter_cycle_row(canvas, chrome.layout.filter_alert_button_bounds(rect), if (state.alert_volume_filter) "Alert volume only" else "Alert volume: off", state.alert_volume_filter)
        chrome.draw_choice_button(canvas, chrome.layout.filter_reset_button_bounds(rect), "Reset filters", state.filters_active)
    }

    // Compact filters use two columns and shorter reset text to fit landscape-short devices.
    private fun draw_compact_filters_panel_contents(canvas: Canvas, rect: RectF, state: FiltersPanelState) {
        draw_filter_cycle_row(canvas, chrome.layout.filter_aircraft_type_button_bounds(rect), "Type: ${state.aircraft_type_filter.short_label}", state.aircraft_type_filter != AircraftTypeFilter.ALL)
        draw_filter_cycle_row(canvas, chrome.layout.filter_altitude_button_bounds(rect), "Alt: ${state.altitude_filter.short_label}", state.altitude_filter != AltitudeFilter.ANY)
        draw_filter_cycle_row(canvas, chrome.layout.filter_distance_button_bounds(rect), "Range: ${state.distance_filter.short_label}", state.distance_filter != DistanceFilter.ANY)
        draw_filter_cycle_row(canvas, chrome.layout.filter_status_button_bounds(rect), "Status: ${state.flight_status_filter.short_label}", state.flight_status_filter != FlightStatusFilter.AIRBORNE)
        draw_filter_cycle_row(canvas, chrome.layout.filter_age_button_bounds(rect), "Age: ${state.report_age_filter.short_label}", state.report_age_filter != ReportAgeFilter.ANY)
        draw_filter_cycle_row(canvas, chrome.layout.filter_alert_button_bounds(rect), if (state.alert_volume_filter) "Alert volume only" else "Alert volume: off", state.alert_volume_filter)
        chrome.draw_choice_button(canvas, chrome.layout.filter_reset_button_bounds(rect), "Reset", state.filters_active)
    }

    private fun draw_filter_cycle_row(canvas: Canvas, bounds: RectF, label: String, selected: Boolean) {
        chrome.draw_choice_button(canvas, bounds, label, selected)
    }

    // Compact priority tracker keeps alert controls left and queue rows right.
    private fun draw_compact_priority_tracker_contents(canvas: Canvas, rect: RectF, style: FlightMapPanelStyle, state: PriorityTrackerPanelState) {
        val left_area = chrome.layout.priority_alert_control_area(rect)
        val right = chrome.layout.compact_settings_right_column(rect)

        chrome.draw_choice_button(canvas, chrome.layout.priority_tracking_toggle_bounds(rect), if (state.priority_tracking_enabled) "Queue on" else "Queue off", state.priority_tracking_enabled)
        chrome.draw_choice_button(canvas, chrome.layout.priority_ring_toggle_bounds(rect), if (state.priority_range_circle_visible) "Alert ring on" else "Alert ring off", state.priority_range_circle_visible)
        draw_adjuster_row(
            canvas,
            left_area,
            rect.top + dp(118),
            "Horizontal",
            state.alert_distance_label,
            chrome.layout.alert_distance_minus_bounds(rect),
            chrome.layout.alert_distance_plus_bounds(rect),
            style,
            state.long_press_fill,
            PriorityRangeAdjustButton.DISTANCE_MINUS,
            PriorityRangeAdjustButton.DISTANCE_PLUS
        )
        draw_adjuster_row(
            canvas,
            left_area,
            rect.top + dp(186),
            "Vertical",
            state.alert_altitude_label,
            chrome.layout.alert_altitude_minus_bounds(rect),
            chrome.layout.alert_altitude_plus_bounds(rect),
            style,
            state.long_press_fill,
            PriorityRangeAdjustButton.ALTITUDE_MINUS,
            PriorityRangeAdjustButton.ALTITUDE_PLUS
        )

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText("Aircraft in queue", right.left, rect.top + dp(58), text_paint)

        if (state.aircraft_rows.isEmpty()) {
            text_paint.isFakeBoldText = true
            text_paint.textSize = sp(16)
            text_paint.color = style.visual_theme.colors.text
            canvas.drawText(if (state.priority_tracking_enabled) "No aircraft in queue" else "Queue is off", right.left, rect.top + dp(94), text_paint)
            text_paint.isFakeBoldText = false
            return
        }

        var y = rect.top + dp(94)
        state.aircraft_rows.take(3).forEach { item ->
            y = draw_priority_aircraft_row(canvas, RectF(right.left - dp(18), rect.top, rect.right, rect.bottom), y, item, style)
        }
        if (state.aircraft_rows.size > 3) {
            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(11)
            text_paint.color = style.visual_theme.colors.muted
            draw_fitted_left_text(canvas, "+${state.aircraft_rows.size - 3} more", right.left, y + dp(4), right.width(), sp(11), sp(8))
        }
    }

    private fun draw_priority_aircraft_row(
        canvas: Canvas,
        panel: RectF,
        y: Float,
        row_state: PriorityAircraftPanelRow,
        style: FlightMapPanelStyle
    ): Float {
        val colors = style.visual_theme.colors
        val theme_style = style.visual_theme.style
        val row = RectF(panel.left + dp(18), y - dp(22), panel.right - dp(18), y + dp(34))
        paint.style = Paint.Style.FILL
        paint.color = if (row_state.is_extreme) with_alpha(colors.danger, 60) else with_alpha(colors.text, colors.row_fill_alpha)
        val row_radius = if (theme_style.treatment == ThemeTreatment.PLAIN) dp(6) else chrome.control_radius().coerceAtLeast(dp(1))
        canvas.drawRoundRect(row, row_radius, row_radius, paint)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(15)
        text_paint.color = if (row_state.is_extreme) colors.danger else colors.text
        val altitude_width = (row.width() * 0.34f).coerceAtLeast(dp(58))
        val title_width = (row.width() - altitude_width - dp(28)).coerceAtLeast(dp(48))
        draw_fitted_left_text(canvas, row_state.title, row.left + dp(10), y, title_width, sp(15), sp(9))

        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = sp(13)
        text_paint.color = colors.text
        draw_fitted_right_text(canvas, row_state.altitude, row.right - dp(10), y, altitude_width, sp(13), sp(8))

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11)
        text_paint.color = colors.muted
        draw_fitted_left_text(canvas, row_state.detail, row.left + dp(10), y + dp(20), row.width() - dp(20), sp(11), sp(8))
        return y + dp(64)
    }

    private fun draw_adjuster_row(
        canvas: Canvas,
        panel: RectF,
        y: Float,
        label: String,
        value: String,
        minus: RectF,
        plus: RectF,
        style: FlightMapPanelStyle,
        long_press_fill: PriorityRangeButtonFillState?,
        minus_button: PriorityRangeAdjustButton,
        plus_button: PriorityRangeAdjustButton
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(12)
        text_paint.color = style.visual_theme.colors.muted
        canvas.drawText(label, panel.left + dp(18), y, text_paint)

        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        draw_fitted_center_text(canvas, value, minus.right + dp(8), plus.left - dp(8), y + dp(32), sp(13), sp(8))
        chrome.draw_choice_button(canvas, minus, "-", false)
        chrome.draw_choice_button(canvas, plus, "+", false)
        draw_long_press_fill(canvas, minus, "-", minus_button, long_press_fill, style)
        draw_long_press_fill(canvas, plus, "+", plus_button, long_press_fill, style)
    }

    private fun draw_long_press_fill(
        canvas: Canvas,
        rect: RectF,
        label: String,
        button: PriorityRangeAdjustButton,
        fill: PriorityRangeButtonFillState?,
        style: FlightMapPanelStyle
    ) {
        if (fill?.button != button) return
        val elapsed = SystemClock.elapsedRealtime() - fill.started_ms
        val progress = (elapsed.toFloat() / fill.duration_ms.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        val max_radius = max_distance_to_corner(fill.press_x, fill.press_y, rect)
        val save = canvas.save()
        long_press_fill_clip.reset()
        long_press_fill_clip.addRoundRect(rect, chrome.control_radius(), chrome.control_radius(), Path.Direction.CW)
        canvas.clipPath(long_press_fill_clip)
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.visual_theme.colors.accent_green, 92)
        canvas.drawCircle(fill.press_x, fill.press_y, max_radius * eased, paint)
        canvas.restoreToCount(save)

        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13)
        text_paint.color = style.visual_theme.colors.text
        val metrics = text_paint.fontMetrics
        canvas.drawText(label, rect.centerX(), rect.centerY() - (metrics.ascent + metrics.descent) / 2f, text_paint)
        text_paint.isFakeBoldText = false
        if (progress < 1f) chrome.request_animation_frame()
    }

    private fun max_distance_to_corner(x: Float, y: Float, rect: RectF): Float {
        var max_squared = 0f
        val xs = floatArrayOf(rect.left, rect.right)
        val ys = floatArrayOf(rect.top, rect.bottom)
        for (corner_x in xs) {
            for (corner_y in ys) {
                val dx = corner_x - x
                val dy = corner_y - y
                val squared = dx * dx + dy * dy
                if (squared > max_squared) max_squared = squared
            }
        }
        return kotlin.math.sqrt(max_squared)
    }

    private fun draw_panel_title(canvas: Canvas, rect: RectF, title: String, style: FlightMapPanelStyle) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(20)
        text_paint.color = style.visual_theme.colors.text
        val left = rect.left + dp(18)
        val close_left = chrome.layout.close_button_bounds(rect).left
        draw_fitted_left_text(canvas, title, left, rect.top + dp(34), close_left - left - dp(8), sp(20), sp(11))
    }

    private fun draw_fitted_left_text(
        canvas: Canvas,
        value: String,
        left: Float,
        y: Float,
        max_width: Float,
        start_size: Float,
        min_size: Float
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.textSize = start_size
        val width = max_width.coerceAtLeast(dp(8))
        while (text_paint.textSize > min_size && text_paint.measureText(value) > width) {
            text_paint.textSize -= dp(0.5f)
        }
        val display = if (text_paint.measureText(value) <= width) value else chrome.ellipsize(value, width)
        canvas.drawText(display, left, y, text_paint)
    }

    private fun draw_fitted_right_text(
        canvas: Canvas,
        value: String,
        right: Float,
        y: Float,
        max_width: Float,
        start_size: Float,
        min_size: Float
    ) {
        text_paint.textAlign = Paint.Align.RIGHT
        text_paint.textSize = start_size
        val width = max_width.coerceAtLeast(dp(8))
        while (text_paint.textSize > min_size && text_paint.measureText(value) > width) {
            text_paint.textSize -= dp(0.5f)
        }
        val display = if (text_paint.measureText(value) <= width) value else chrome.ellipsize(value, width)
        canvas.drawText(display, right, y, text_paint)
    }

    private fun draw_fitted_center_text(
        canvas: Canvas,
        value: String,
        left: Float,
        right: Float,
        y: Float,
        start_size: Float,
        min_size: Float
    ) {
        val width = (right - left).coerceAtLeast(dp(8))
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = start_size
        while (text_paint.textSize > min_size && text_paint.measureText(value) > width) {
            text_paint.textSize -= dp(0.5f)
        }
        val display = if (text_paint.measureText(value) <= width) value else chrome.ellipsize(value, width)
        canvas.drawText(display, (left + right) / 2f, y, text_paint)
    }

    private fun dp(value: Int): Float = dp(value.toFloat())

    private fun dp(value: Float): Float = chrome.dp(value)

    private fun sp(value: Int): Float = sp(value.toFloat())

    private fun sp(value: Float): Float = chrome.sp(value)

    private fun with_alpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
        const val PRIORITY_PANEL_ROWS = 5
    }
}
