@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.location.Location
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round
import com.flightalert.FlightMapOverlaySnapshot
import com.flightalert.PHOTO_REPLACEMENT_TRANSITION_MS
import com.flightalert.VisualTheme
import com.flightalert.aircraft.Aircraft
import com.flightalert.aircraft.AircraftPositionProjector
import com.flightalert.aircraft.AlertAircraftClassifier
import com.flightalert.aircraft.AlertPositionProjector
import com.flightalert.aircraft.ProjectedAlertPosition
import com.flightalert.aircraft.TrafficDisplay
import com.flightalert.alerts.MonitoringNotificationHider
import com.flightalert.details.AircraftDetailsContentBuilder
import com.flightalert.details.AircraftDetailsMainState
import com.flightalert.details.AircraftDetailsOverlay
import com.flightalert.details.AircraftDetailsPage
import com.flightalert.details.AircraftDetailsPanelRenderer
import com.flightalert.details.AircraftDetailsPanelState
import com.flightalert.details.AircraftDetailsPanelStyle
import com.flightalert.details.AircraftDetailsPhotoState
import com.flightalert.details.AircraftDetailsRowsBuilder
import com.flightalert.details.AircraftDetailsSection
import com.flightalert.details.AircraftDetailsSession
import com.flightalert.details.AircraftImpactEstimator
import com.flightalert.details.AircraftPhotoEvidencePanelState
import com.flightalert.details.AircraftPhotoGalleryPanelState
import com.flightalert.details.AircraftTelemetryFormatter
import com.flightalert.details.ImpactProfile
import com.flightalert.map.TileSource
import com.flightalert.map.UnitSystem
import com.flightalert.map.aviation_layers.AviationInspectorInteractionPolicy
import com.flightalert.map.aviation_layers.AviationInspectorScrollPolicy
import com.flightalert.map.aviation_layers.AviationInspectorTapAction
import com.flightalert.map.aviation_layers.AviationLayerInspector
import com.flightalert.map.aviation_layers.AviationLayerInspectorStyle
import com.flightalert.map.aviation_layers.AviationLayerKind
import com.flightalert.map.aviation_layers.AviationLayerSnapshot
import com.flightalert.map.aviation_layers.AviationSelection
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog
import com.flightalert.traffic.AircraftTypeFilter
import com.flightalert.traffic.AltitudeFilter
import com.flightalert.traffic.DistanceFilter
import com.flightalert.traffic.FilterStats
import com.flightalert.traffic.FlightStatusFilter
import com.flightalert.traffic.ReportAgeFilter

internal class FlightMapOverlayNavigator {
    var settings_page: FlightMapSettingsPage? = null
        private set
    var priority_tracker_open: Boolean = false
    var filters_open: Boolean = false

    val settings_open: Boolean
        get() = settings_page != null

    fun set_settings_open(open: Boolean) {
        settings_page = if (open) settings_page ?: FlightMapSettingsPage.ROOT else null
    }

    fun set_settings_subpage(page: FlightMapSettingsPage, open: Boolean) {
        settings_page = when {
            open -> page
            settings_page == page -> FlightMapSettingsPage.ROOT
            else -> settings_page
        }
    }
}

internal class ReferenceFilterCatalogPanelCache {
    private var catalog: ReferenceClassCatalog? = null

    fun update(
        next: ReferenceClassCatalog?,
        on_identity_changed: () -> Unit,
    ): ReferenceClassCatalog? {
        if (next !== catalog) {
            catalog = next
            on_identity_changed()
        }
        return catalog
    }
}

internal class FlightMapPanelCoordinator(
    private val layout: FlightMapLayout,
    private val aviation_layer_inspector: AviationLayerInspector,
    private val panel_renderer: FlightMapPanelRenderer,
    private val reference_filters_panel_controller: ReferenceFiltersPanelController,
    private val details_panel_renderer: AircraftDetailsPanelRenderer,
    private val traffic_panel_renderer: TrafficPanelRenderer,
    private val traffic_panel_state_builder: TrafficPanelStateBuilder,
    private val reference_filter_catalog_cache: ReferenceFilterCatalogPanelCache,
    private var reference_filters_panel_session: ReferenceFiltersPanelSession,
    private val telemetry_formatter: AircraftTelemetryFormatter,
    private val details_session: AircraftDetailsSession,
    reported_distance_meters: (Aircraft) -> Double,
    muted_color: () -> Int,
    impact_score_color: (ImpactProfile) -> Int,
    units: () -> UnitSystem,
) {
    private val overlay_navigator = FlightMapOverlayNavigator()
    private var reference_filters_panel_plan_cache_key: ReferenceFiltersPanelPlanCacheKey? = null
    private var reference_filters_panel_plan_cache: ReferenceFiltersPanelPlan? = null
    private val aircraft_details_rows_builder = AircraftDetailsRowsBuilder(
        telemetry_formatter = telemetry_formatter,
        details_session = details_session,
        reported_distance_meters = reported_distance_meters,
    )
    private val aircraft_details_content_builder = AircraftDetailsContentBuilder(
        muted_color = muted_color,
        impact_score_color = impact_score_color,
        details_session = details_session,
        units = units,
    )

    val settings_page: FlightMapSettingsPage?
        get() = overlay_navigator.settings_page

    var settings_open: Boolean
        get() = overlay_navigator.settings_open
        set(value) = overlay_navigator.set_settings_open(value)

    var display_settings_open: Boolean
        get() = settings_page == FlightMapSettingsPage.DISPLAY
        set(value) = set_settings_subpage(FlightMapSettingsPage.DISPLAY, value)

    var map_settings_open: Boolean
        get() = settings_page == FlightMapSettingsPage.MAP
        set(value) = set_settings_subpage(FlightMapSettingsPage.MAP, value)

    var alert_settings_open: Boolean
        get() = settings_page == FlightMapSettingsPage.ALERTS
        set(value) = set_settings_subpage(FlightMapSettingsPage.ALERTS, value)

    var map_labels_open: Boolean
        get() = settings_page == FlightMapSettingsPage.MAP_LABELS
        set(value) = set_settings_subpage(FlightMapSettingsPage.MAP_LABELS, value)

    var aviation_layers_open: Boolean
        get() = settings_page == FlightMapSettingsPage.AVIATION_LAYERS
        set(value) = set_settings_subpage(FlightMapSettingsPage.AVIATION_LAYERS, value)

    var priority_tracker_open: Boolean
        get() = overlay_navigator.priority_tracker_open
        set(value) {
            overlay_navigator.priority_tracker_open = value
        }

    var filters_open: Boolean
        get() = overlay_navigator.filters_open
        set(value) {
            overlay_navigator.filters_open = value
        }
    var filter_search_focused = false
    private var filter_panel_scroll_start_offset_dp = 0f

    var impact_methodology_open: Boolean
        get() = settings_page == FlightMapSettingsPage.IMPACT_METHODOLOGY
        set(value) = set_settings_subpage(FlightMapSettingsPage.IMPACT_METHODOLOGY, value)

    private var priority_adjust_hold: PriorityAdjustHold? = null
    private var map_label_text_slider_dragging = false
    private var details_scroll_y = 0f
    private var details_max_scroll_y = 0f
    private var aviation_details_scroll_y = 0f
    private var aviation_details_max_scroll_y = 0f
    private var panel_scroll_start_y = 0f
    private var panel_scroll_start_offset = 0f

    val priority_adjust_hold_active: Boolean
        get() = priority_adjust_hold != null

    val details_scroll_offset: Float
        get() = details_scroll_y

    fun panel_overlay_open(): Boolean = settings_open || priority_tracker_open || filters_open

    fun reset_details_scroll() {
        details_scroll_y = 0f
        details_max_scroll_y = 0f
    }

    fun reset_details_scroll_offset() {
        details_scroll_y = 0f
    }

    fun clear_details_scroll_limit() {
        details_max_scroll_y = 0f
    }

    fun reset_aviation_details_scroll() {
        aviation_details_scroll_y = 0f
        aviation_details_max_scroll_y = 0f
    }

    fun begin_panel_scroll(y: Float, aviation_details_open: Boolean) {
        panel_scroll_start_y = y
        panel_scroll_start_offset = if (aviation_details_open) aviation_details_scroll_y else details_scroll_y
    }

    fun panel_scroll_drag_started(y: Float, drag_started: Boolean, threshold: Float): Boolean {
        return drag_started || abs(y - panel_scroll_start_y) > threshold
    }

    fun aviation_details_scroll_available(): Boolean = aviation_details_max_scroll_y > 0f

    fun update_aviation_details_scroll(y: Float) {
        aviation_details_scroll_y = AviationInspectorScrollPolicy.scroll_from_drag(
            start_scroll_y = panel_scroll_start_offset,
            drag_delta_y = y - panel_scroll_start_y,
            max_scroll_y = aviation_details_max_scroll_y,
        )
    }

    fun scroll_aviation_details_by(drag_delta_y: Float) {
        aviation_details_scroll_y = AviationInspectorScrollPolicy.scroll_from_drag(
            start_scroll_y = aviation_details_scroll_y,
            drag_delta_y = drag_delta_y,
            max_scroll_y = aviation_details_max_scroll_y,
        )
    }

    fun details_scroll_available(): Boolean = details_max_scroll_y > 0f

    fun update_details_scroll(y: Float) {
        details_scroll_y = (panel_scroll_start_offset - (y - panel_scroll_start_y))
            .coerceIn(0f, details_max_scroll_y)
    }

    fun aviation_details_tap_action(
        w: Float,
        h: Float,
        kind: AviationLayerKind,
        x: Float,
        y: Float,
    ): AviationInspectorTapAction {
        val panel = aviation_details_panel_bounds(w, h, kind)
        return AviationInspectorInteractionPolicy.action_for(
            inside_panel = panel.contains(x, y),
            inside_close_button = aviation_details_close_button_bounds(panel).contains(x, y),
        )
    }

    fun settings_panel_hit_result(w: Float, h: Float, x: Float, y: Float): SettingsPanelHitResult? {
        if (!settings_open) return null
        return layout.settings_panel_hit_result(
            panel = layout.settings_panel_bounds(w, h),
            x = x,
            y = y,
            state = SettingsPanelHitState(
                impact_methodology_open = impact_methodology_open,
                aviation_layers_open = aviation_layers_open,
                map_labels_open = map_labels_open,
                display_settings_open = display_settings_open,
                map_settings_open = map_settings_open,
                alert_settings_open = alert_settings_open,
                impact_source_count = AircraftImpactEstimator.source_labels.size,
            ),
        )
    }

    fun filter_panel_action_at(
        w: Float,
        h: Float,
        x: Float,
        y: Float,
        state: FiltersPanelState,
    ): FilterPanelAction? {
        if (!filters_open) return null
        return layout.filter_panel_action_at(layout.settings_panel_bounds(w, h), x, y, state)
    }

    fun priority_tracker_panel_hit_result(
        w: Float,
        h: Float,
        x: Float,
        y: Float,
    ): PriorityTrackerPanelHitResult? {
        if (!priority_tracker_open) return null
        return layout.priority_tracker_panel_hit_result(layout.priority_tracker_panel_bounds(w, h), x, y)
    }

    fun begin_priority_adjust_hold(
        w: Float,
        h: Float,
        x: Float,
        y: Float,
    ): PriorityAdjustHold? {
        if (!priority_tracker_open) return null
        val panel = layout.priority_tracker_panel_bounds(w, h)
        val button = layout.priority_adjust_button_at(panel, x, y) ?: return null
        val now = SystemClock.elapsedRealtime()
        val duration = ViewConfiguration.getLongPressTimeout().toLong()
        return PriorityAdjustHold(button, x, y, now, duration).also { priority_adjust_hold = it }
    }

    fun update_priority_adjust_hold(w: Float, h: Float, x: Float, y: Float): Boolean {
        val hold = priority_adjust_hold ?: return false
        if (priority_tracker_open && priority_adjust_button_bounds(w, h, hold.button).contains(x, y)) return false
        priority_adjust_hold = null
        return true
    }

    fun fire_priority_adjust_hold(
        button: PriorityRangeAdjustButton,
        started_ms: Long,
    ): PriorityRangeAdjustButton? {
        val hold = priority_adjust_hold ?: return null
        if (hold.button != button || hold.started_ms != started_ms || hold.fired) return null
        hold.fired = true
        return button
    }

    fun fire_priority_adjust_hold_if_due(
        w: Float,
        h: Float,
        x: Float,
        y: Float,
        now: Long,
    ): PriorityRangeAdjustButton? {
        val hold = priority_adjust_hold ?: return null
        if (!priority_tracker_open || !priority_adjust_button_bounds(w, h, hold.button).contains(x, y)) return null
        if (now - hold.started_ms < hold.duration_ms) return null
        return fire_priority_adjust_hold(hold.button, hold.started_ms)
    }

    fun finish_priority_adjust_hold(): Boolean {
        val consumed = priority_adjust_hold?.fired == true
        priority_adjust_hold = null
        return consumed
    }

    fun cancel_priority_adjust_hold(): Boolean {
        if (priority_adjust_hold == null) return false
        priority_adjust_hold = null
        return true
    }

    fun priority_adjust_hold_fill_state(): PriorityRangeButtonFillState? {
        val hold = priority_adjust_hold ?: return null
        return PriorityRangeButtonFillState(
            button = hold.button,
            press_x = hold.press_x,
            press_y = hold.press_y,
            started_ms = hold.started_ms,
            duration_ms = hold.duration_ms,
        )
    }

    private fun priority_adjust_button_bounds(
        w: Float,
        h: Float,
        button: PriorityRangeAdjustButton,
    ): RectF {
        return layout.priority_adjust_button_bounds(layout.priority_tracker_panel_bounds(w, h), button)
    }

    fun begin_map_label_text_slider_drag(w: Float, h: Float, x: Float, y: Float): Float? {
        if (!settings_open || !map_labels_open) return null
        val bounds = layout.map_label_text_slider_bounds(layout.settings_panel_bounds(w, h))
        if (!bounds.contains(x, y)) return null
        map_label_text_slider_dragging = true
        return map_label_text_scale_for_x(x, bounds)
    }

    fun update_map_label_text_slider_drag(w: Float, h: Float, x: Float): Float? {
        if (!map_label_text_slider_dragging) return null
        val bounds = layout.map_label_text_slider_bounds(layout.settings_panel_bounds(w, h))
        return map_label_text_scale_for_x(x, bounds)
    }

    fun finish_map_label_text_slider_drag(w: Float, h: Float, x: Float): Float? {
        if (!map_label_text_slider_dragging) return null
        map_label_text_slider_dragging = false
        val bounds = layout.map_label_text_slider_bounds(layout.settings_panel_bounds(w, h))
        return map_label_text_scale_for_x(x, bounds)
    }

    fun cancel_map_label_text_slider_drag() {
        map_label_text_slider_dragging = false
    }

    fun set_settings_subpage(page: FlightMapSettingsPage, open: Boolean) {
        overlay_navigator.set_settings_subpage(page, open)
    }

    fun settings_subpage_open(): Boolean {
        return display_settings_open ||
                map_settings_open ||
                alert_settings_open ||
                map_labels_open ||
                aviation_layers_open ||
                impact_methodology_open
    }

    fun close_settings_subpage() {
        when {
            map_labels_open -> map_labels_open = false
            aviation_layers_open -> aviation_layers_open = false
            impact_methodology_open -> impact_methodology_open = false
            display_settings_open -> display_settings_open = false
            map_settings_open -> map_settings_open = false
            alert_settings_open -> alert_settings_open = false
        }
    }

    fun close_all_settings_subpages() {
        display_settings_open = false
        map_settings_open = false
        alert_settings_open = false
        map_labels_open = false
        aviation_layers_open = false
        impact_methodology_open = false
    }

    fun update_overlay_snapshot(
        snapshot: FlightMapOverlaySnapshot,
        selected_aviation: AviationSelection?,
        details_page: AircraftDetailsPage,
    ) {
        snapshot.update(
            selected_aviation = selected_aviation,
            details_page = details_page,
            settings_page = settings_page,
            priority_tracker_open = priority_tracker_open,
            filters_open = filters_open,
        )
    }

    fun draw_traffic_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        display: TrafficDisplay,
        muted_color: Int,
        accent_blue_color: Int,
        danger_color: Int,
        filters_active: Boolean,
        filter_stats: FilterStats,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?,
    ) {
        val rect = layout.info_panel_bounds(w, h)
        val wide = layout.is_wide_layout(w, h)
        val style = traffic_panel_style(visual_theme)
        val state = traffic_panel_state(
            display = display,
            muted_color = muted_color,
            accent_blue_color = accent_blue_color,
            danger_color = danger_color,
            filters_active = filters_active,
            filter_stats = filter_stats,
            aircraft_status = aircraft_status,
            last_aircraft_data_epoch_sec = last_aircraft_data_epoch_sec,
        )
        traffic_panel_renderer.draw_panel(canvas, rect, wide, style, state)
    }

    private fun traffic_panel_style(visual_theme: VisualTheme): TrafficPanelStyle {
        return TrafficPanelStyle(visual_theme)
    }

    private fun traffic_panel_state(
        display: TrafficDisplay,
        muted_color: Int,
        accent_blue_color: Int,
        danger_color: Int,
        filters_active: Boolean,
        filter_stats: FilterStats,
        aircraft_status: String,
        last_aircraft_data_epoch_sec: Double?,
    ): TrafficPanelState {
        return traffic_panel_state_builder.panel_state(
            display = display,
            muted_color = muted_color,
            accent_blue_color = accent_blue_color,
            danger_color = danger_color,
            filters_active = filters_active,
            filter_stats = filter_stats,
            aircraft_status = aircraft_status,
            last_aircraft_data_epoch_sec = last_aircraft_data_epoch_sec,
        )
    }

    fun draw_aircraft_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
    ) {
        val photo_transition_progress = details_session.photo_transition_progress(PHOTO_REPLACEMENT_TRANSITION_MS)
        val state = AircraftDetailsPanelState(
            content = when {
                details_session.environmental_impact_open -> aircraft_details_content_builder.impact_panel_state(
                    details_session.displayed_aircraft_source(),
                    details_session.aircraft_details
                )

                details_session.usage_open -> aircraft_details_content_builder.usage_panel_state(
                    details_session.displayed_aircraft_source()
                )

                details_session.photo_evidence_open -> AircraftPhotoEvidencePanelState(
                    details_session.active_photo_evidence
                )

                details_session.photo_gallery_open -> AircraftPhotoGalleryPanelState(
                    items = details_session.aircraft_photo_gallery,
                    status = details_session.aircraft_photo_gallery_status,
                    loading = details_session.aircraft_photo_gallery_loading
                )

                else -> {
                    val aircraft = details_session.displayed_aircraft_source()
                    AircraftDetailsMainState(
                        title = aircraft?.callsign ?: "Aircraft details",
                        rows = aircraft?.let {
                            aircraft_details_rows_builder.aircraft_details_rows(
                                it,
                                details_session.aircraft_details_for_panel(it)
                            )
                        }.orEmpty(),
                        has_aircraft = aircraft != null,
                        has_usage_trace = aircraft?.let {
                            details_session.has_usage_trace_for(it)
                        } == true
                    )
                }
            },
            photo = AircraftDetailsPhotoState(
                bitmap = details_session.aircraft_photo,
                status = details_session.aircraft_photo_status,
                previous_bitmap = details_session.aircraft_photo_previous_bitmap,
                transition_progress = photo_transition_progress
            ),
            scroll_y = details_scroll_offset,
            wide_layout = layout.is_wide_layout(w, h)
        )
        val result = details_panel_renderer.draw_panel(
            canvas = canvas,
            w = w,
            h = h,
            style = AircraftDetailsPanelStyle(visual_theme),
            state = state,
        )
        details_max_scroll_y = result.max_scroll_y
        details_scroll_y = result.scroll_y
    }

    fun details_panel_bounds(w: Float, h: Float): RectF {
        return details_panel_renderer.panel_bounds(w, h, layout.is_wide_layout(w, h))
    }

    fun current_details_photo_bounds(
        panel: RectF,
        w: Float,
        h: Float,
        has_photo: Boolean,
    ): RectF {
        return details_panel_renderer.current_photo_bounds(
            panel,
            layout.is_wide_layout(w, h),
            has_photo,
        )
    }

    // 0 consumes without an effect; 1..11 select fixed effects; 12 + n selects gallery item n.
    fun details_panel_action_at(
        w: Float,
        h: Float,
        x: Float,
        y: Float,
        page: AircraftDetailsPage,
        has_image_source: Boolean,
        has_page_source: Boolean,
        has_photo_evidence: Boolean,
        has_photo: Boolean,
        gallery_item_count: Int,
    ): Int {
        val wide = layout.is_wide_layout(w, h)
        val panel = details_panel_renderer.panel_bounds(w, h, wide)
        val close_hit = details_panel_renderer.close_button_bounds(panel).contains(x, y)
        return when {
            page.section == AircraftDetailsSection.ENVIRONMENTAL_IMPACT -> if (close_hit) 1 else 0
            page.section == AircraftDetailsSection.USAGE -> if (close_hit) 2 else 0
            page.overlay is AircraftDetailsOverlay.PHOTO_EVIDENCE -> when {
                close_hit -> 3
                has_image_source && details_panel_renderer.photo_image_source_button_bounds(panel).contains(x, y) -> 4
                has_page_source && details_panel_renderer.photo_page_source_button_bounds(panel).contains(x, y) -> 5
                else -> 0
            }
            page.overlay == AircraftDetailsOverlay.PHOTO_GALLERY -> {
                if (close_hit) return 6
                for (index in 0 until gallery_item_count) {
                    val bounds = details_panel_renderer.gallery_item_bounds(panel, index, wide)
                    val displayed_bounds = RectF(
                        bounds.left,
                        bounds.top - details_scroll_y,
                        bounds.right,
                        bounds.bottom - details_scroll_y,
                    )
                    if (displayed_bounds.contains(x, y)) return 12 + index
                }
                0
            }
            close_hit -> 7
            details_panel_renderer.usage_button_bounds(panel).contains(x, y) -> 8
            details_panel_renderer.impact_hit_bounds(panel).contains(x, y) -> 9
            has_photo_evidence && details_panel_renderer.current_photo_bounds(
                panel,
                wide,
                has_photo,
            ).contains(x, y) -> 10
            has_photo && details_panel_renderer.current_photo_bounds(
                panel,
                wide,
                true,
            ).contains(x, y) -> 11
            else -> 0
        }
    }

    fun draw_aviation_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot,
        visual_theme: VisualTheme,
        now_epoch_ms: Long,
    ): Boolean {
        val requested_scroll_y = aviation_details_scroll_y
        val result = aviation_layer_inspector.draw_details_panel(
            canvas = canvas,
            w = w,
            h = h,
            selection = selection,
            snapshot = snapshot,
            now_epoch_ms = now_epoch_ms,
            scroll_y = requested_scroll_y,
            style = aviation_layer_inspector_style(visual_theme),
        )
        aviation_details_scroll_y = result.scroll_y
        aviation_details_max_scroll_y = result.max_scroll_y
        return result.scroll_y != requested_scroll_y
    }

    private fun aviation_layer_inspector_style(visual_theme: VisualTheme): AviationLayerInspectorStyle {
        val colors = visual_theme.colors
        return AviationLayerInspectorStyle(
            panel_color = colors.panel,
            modal_panel_alpha = visual_theme.style.modal_panel_alpha,
            text_color = colors.text,
            muted_color = colors.muted,
            accent_orange_color = colors.accent_orange,
            accent_blue_color = colors.accent_blue,
            accent_pink_color = colors.accent_pink,
            accent_yellow_color = colors.accent_yellow,
            military_gray_color = colors.military,
        )
    }

    fun aviation_details_panel_bounds(w: Float, h: Float, kind: AviationLayerKind): RectF {
        return aviation_layer_inspector.panel_bounds(w, h, kind)
    }

    fun aviation_details_close_button_bounds(panel: RectF): RectF {
        return aviation_layer_inspector.close_button_bounds(panel)
    }

    fun aviation_details_body_bounds(w: Float, h: Float, kind: AviationLayerKind): RectF =
        aviation_layer_inspector.body_bounds(aviation_details_panel_bounds(w, h, kind))

    fun draw_settings_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: SettingsPanelState,
    ) {
        panel_renderer.draw_settings_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_display_settings_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: SettingsPanelState,
    ) {
        panel_renderer.draw_display_settings_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_map_settings_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: SettingsPanelState,
    ) {
        panel_renderer.draw_map_settings_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_alert_settings_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: SettingsPanelState,
    ) {
        panel_renderer.draw_alert_settings_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_map_labels_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: MapLabelsPanelState,
    ) {
        panel_renderer.draw_map_labels_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_aviation_layers_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: AviationLayersPanelState,
    ) {
        panel_renderer.draw_aviation_layers_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_impact_methodology_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: ImpactMethodologyPanelState,
    ) {
        panel_renderer.draw_impact_methodology_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_filters_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: FiltersPanelState,
    ) {
        panel_renderer.draw_filters_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    fun draw_priority_tracker_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        visual_theme: VisualTheme,
        state: PriorityTrackerPanelState,
    ) {
        panel_renderer.draw_priority_tracker_panel(canvas, w, h, panel_style(visual_theme), state)
    }

    private fun panel_style(visual_theme: VisualTheme): FlightMapPanelStyle {
        return FlightMapPanelStyle(visual_theme)
    }

    fun settings_panel_state(
        context: Context,
        app_opened_elapsed_ms: Long,
        units: UnitSystem,
        map_source: TileSource,
        map_labels_enabled: Boolean,
        map_borders_enabled: Boolean,
        alerts_enabled: Boolean,
        priority_tracking_enabled: Boolean,
    ): SettingsPanelState {
        return SettingsPanelState(
            units = units,
            map_source = map_source,
            map_labels_enabled = map_labels_enabled,
            map_borders_enabled = map_borders_enabled,
            alerts_enabled = alerts_enabled,
            priority_tracking_enabled = priority_tracking_enabled,
            watcher_notification_hider_enabled = MonitoringNotificationHider.is_enabled(context),
            watcher_notification_hider_status = MonitoringNotificationHider.status(
                context,
                app_opened_elapsed_ms,
            ),
            map_attribution = map_source.attribution_text(map_labels_enabled, map_borders_enabled),
        )
    }

    fun map_labels_panel_state(
        street_labels_enabled: Boolean,
        borders_enabled: Boolean,
        label_text_scale: Float,
    ): MapLabelsPanelState {
        return MapLabelsPanelState(
            street_labels_enabled = street_labels_enabled,
            borders_enabled = borders_enabled,
            label_text_scale = label_text_scale,
        )
    }

    fun aviation_layers_panel_state(
        status_text: String,
        snapshot: AviationLayerSnapshot?,
        fetch_in_flight: Boolean,
        atc_boundaries_enabled: Boolean,
        restricted_airspaces_enabled: Boolean,
        oceanic_tracks_enabled: Boolean,
        airport_labels_enabled: Boolean,
    ): AviationLayersPanelState {
        return AviationLayersPanelState(
            status_text = status_text,
            snapshot = snapshot,
            fetch_in_flight = fetch_in_flight,
            atc_boundaries_enabled = atc_boundaries_enabled,
            restricted_airspaces_enabled = restricted_airspaces_enabled,
            oceanic_tracks_enabled = oceanic_tracks_enabled,
            airport_labels_enabled = airport_labels_enabled,
        )
    }

    fun impact_methodology_panel_state(): ImpactMethodologyPanelState {
        return ImpactMethodologyPanelState(source_labels = AircraftImpactEstimator.source_labels)
    }

    private fun reference_filter_catalog_for_panel(
        map_borders_enabled: Boolean,
        current_catalog: ReferenceClassCatalog?,
    ): ReferenceClassCatalog? {
        return reference_filter_catalog_cache.update(
            if (map_borders_enabled) current_catalog else null,
        ) {
            reference_filters_panel_plan_cache_key = null
            reference_filters_panel_plan_cache = null
        }
    }

    fun reference_filters_panel_plan(
        w: Float,
        h: Float,
        density: Float,
        font_scale: Float,
        font_family: String,
        map_borders_enabled: Boolean,
        current_catalog: ReferenceClassCatalog?,
        filter_state: FilterState,
    ): ReferenceFiltersPanelPlan {
        val panel = layout.settings_panel_bounds(w, h)
        val compact = layout.is_compact_settings_panel(panel)
        val legacySearchTopDp = if (compact) 62f else 74f
        val trafficContentHeightDp = (panel.height() / density - legacySearchTopDp).coerceAtLeast(0f)
        val fontScale = font_scale.coerceAtLeast(0.01f)
        val catalog = reference_filter_catalog_for_panel(map_borders_enabled, current_catalog)
        val cacheKey = ReferenceFiltersPanelPlanCacheKey(
            panelWidthPx = panel.width(),
            panelHeightPx = panel.height(),
            density = density,
            fontScale = fontScale,
            fontFamily = font_family,
            compact = compact,
            session = reference_filters_panel_session,
            catalog = catalog,
            filterState = filter_state,
        )
        if (cacheKey == reference_filters_panel_plan_cache_key) {
            reference_filters_panel_plan_cache?.let { return it }
        }
        return reference_filters_panel_controller.plan(
            viewport = ReferenceFiltersViewport(
                widthDp = panel.width() / density,
                heightDp = panel.height() / density,
                fontScale = fontScale,
            ),
            session = reference_filters_panel_session,
            catalog = catalog,
            filterState = filter_state,
            trafficContentHeightDp = trafficContentHeightDp,
        ).also { plan ->
            reference_filters_panel_plan_cache_key = cacheKey
            reference_filters_panel_plan_cache = plan
        }
    }

    fun filters_panel_state(
        w: Float,
        h: Float,
        density: Float,
        font_scale: Float,
        font_family: String,
        map_borders_enabled: Boolean,
        current_catalog: ReferenceClassCatalog?,
        filter_state: FilterState,
        filter_search_query: String,
        filter_search_focused: Boolean,
        aircraft_type_filter: AircraftTypeFilter,
        altitude_filter: AltitudeFilter,
        distance_filter: DistanceFilter,
        flight_status_filter: FlightStatusFilter,
        report_age_filter: ReportAgeFilter,
        alert_volume_filter: Boolean,
        filters_active: Boolean,
        stats_summary: String,
    ): FiltersPanelState {
        return FiltersPanelState(
            filter_search_query = filter_search_query,
            filter_search_focused = filter_search_focused,
            aircraft_type_filter = aircraft_type_filter,
            altitude_filter = altitude_filter,
            distance_filter = distance_filter,
            flight_status_filter = flight_status_filter,
            report_age_filter = report_age_filter,
            alert_volume_filter = alert_volume_filter,
            filters_active = filters_active,
            stats_summary = stats_summary,
            reference_panel_plan = reference_filters_panel_plan(
                w = w,
                h = h,
                density = density,
                font_scale = font_scale,
                font_family = font_family,
                map_borders_enabled = map_borders_enabled,
                current_catalog = current_catalog,
                filter_state = filter_state,
            ),
            reference_focused_action_id = reference_filters_panel_session.focusedActionId,
            pixels_per_dp = density,
        )
    }

    fun priority_aircraft_snapshot(
        now_epoch_sec: Double,
        aircraft: List<Aircraft>,
        latest_location: Location?,
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_tracking_enabled: Boolean,
        priority_range_feet: Float,
    ): List<Aircraft> {
        return aircraft
            .map { item ->
                item to priority_alert_position_for(
                    aircraft = item,
                    latest_location = latest_location,
                    now_epoch_sec = now_epoch_sec,
                    reported_distance_meters = AircraftPositionProjector.reported_distance_meters(
                        aircraft = item,
                        own_lat = latest_location?.latitude,
                        own_lon = latest_location?.longitude,
                    ),
                )
            }
            .filter { (_, alert_position) ->
                alert_position.distance_meters <= priority_range_feet.toDouble() / 3.28084
            }
            .sortedWith(
                compareByDescending<Pair<Aircraft, ProjectedAlertPosition>> {
                    priority_is_extreme(
                        aircraft = it.first,
                        latest_location = latest_location,
                        alerts_enabled = alerts_enabled,
                        alert_distance_feet = alert_distance_feet,
                        alert_altitude_feet = alert_altitude_feet,
                        priority_tracking_enabled = priority_tracking_enabled,
                        priority_range_feet = priority_range_feet,
                        alert_position = it.second,
                        now_epoch_sec = now_epoch_sec,
                    )
                }
                    .thenBy { it.first.altitude_m ?: Double.MAX_VALUE }
                    .thenBy { it.second.distance_meters }
            )
            .map { it.first }
    }

    fun priority_tracker_panel_state(
        priority_tracking_enabled: Boolean,
        priority_range_circle_visible: Boolean,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_range_feet: Float,
        alerts_enabled: Boolean,
        latest_location: Location?,
        long_press_fill: PriorityRangeButtonFillState?,
        priority_aircraft: List<Aircraft>,
    ): PriorityTrackerPanelState {
        return PriorityTrackerPanelState(
            priority_tracking_enabled = priority_tracking_enabled,
            priority_range_circle_visible = priority_range_circle_visible,
            alert_distance_label = telemetry_formatter.feet_setting(alert_distance_feet),
            alert_altitude_label = telemetry_formatter.feet_setting(alert_altitude_feet),
            long_press_fill = long_press_fill,
            aircraft_rows = priority_aircraft.map { aircraft ->
                val priority_position = priority_alert_position_for(aircraft, latest_location)
                val estimate_note = if (priority_position.estimated) " est." else ""
                PriorityAircraftPanelRow(
                    title = aircraft.registration ?: aircraft.callsign,
                    altitude = telemetry_formatter.altitude_value(aircraft.altitude_m),
                    detail = "${telemetry_formatter.distance(priority_position.distance_meters)}$estimate_note  ${
                        telemetry_formatter.age(aircraft)
                    }",
                    is_extreme = priority_is_extreme(
                        aircraft = aircraft,
                        latest_location = latest_location,
                        alerts_enabled = alerts_enabled,
                        alert_distance_feet = alert_distance_feet,
                        alert_altitude_feet = alert_altitude_feet,
                        priority_tracking_enabled = priority_tracking_enabled,
                        priority_range_feet = priority_range_feet,
                    ),
                )
            },
        )
    }

    private fun priority_alert_position_for(
        aircraft: Aircraft,
        latest_location: Location?,
        now_epoch_sec: Double = System.currentTimeMillis() / 1000.0,
    ): ProjectedAlertPosition {
        if (latest_location == null) {
            return ProjectedAlertPosition(
                aircraft.distance_m,
                aircraft.altitude_m,
                estimated = false,
            )
        }
        return AlertPositionProjector.projected_alert_position(
            own_lat = latest_location.latitude,
            own_lon = latest_location.longitude,
            aircraft_lat = aircraft.lat,
            aircraft_lon = aircraft.lon,
            reported_distance_meters = AircraftPositionProjector.reported_distance_meters(
                aircraft = aircraft,
                own_lat = latest_location.latitude,
                own_lon = latest_location.longitude,
            ),
            altitude_meters = aircraft.altitude_m,
            velocity_ms = aircraft.velocity_ms,
            track_deg = aircraft.track_deg,
            vertical_rate_ms = aircraft.vertical_rate_ms,
            position_time_sec = aircraft.position_time_sec,
            last_contact_sec = aircraft.last_contact_sec,
            now_epoch_sec = now_epoch_sec,
        )
    }

    private fun priority_alert_position_for(
        aircraft: Aircraft,
        latest_location: Location?,
        now_epoch_sec: Double,
        reported_distance_meters: Double,
    ): ProjectedAlertPosition {
        if (latest_location == null) {
            return ProjectedAlertPosition(
                aircraft.distance_m,
                aircraft.altitude_m,
                estimated = false,
            )
        }
        return AlertPositionProjector.projected_alert_position(
            own_lat = latest_location.latitude,
            own_lon = latest_location.longitude,
            aircraft_lat = aircraft.lat,
            aircraft_lon = aircraft.lon,
            reported_distance_meters = reported_distance_meters,
            altitude_meters = aircraft.altitude_m,
            velocity_ms = aircraft.velocity_ms,
            track_deg = aircraft.track_deg,
            vertical_rate_ms = aircraft.vertical_rate_ms,
            position_time_sec = aircraft.position_time_sec,
            last_contact_sec = aircraft.last_contact_sec,
            now_epoch_sec = now_epoch_sec,
        )
    }

    private fun priority_is_extreme(
        aircraft: Aircraft,
        latest_location: Location?,
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_tracking_enabled: Boolean,
        priority_range_feet: Float,
    ): Boolean {
        val alert_position = priority_alert_position_for(aircraft, latest_location)
        return AlertAircraftClassifier.classify(
            icao24 = aircraft.icao24,
            callsign = aircraft.callsign,
            registration = aircraft.registration,
            distance_meters = alert_position.distance_meters,
            altitude_meters = alert_position.altitude_meters,
            last_contact_sec = aircraft.last_contact_sec,
            position_time_sec = aircraft.position_time_sec,
            own_altitude_feet = latest_location?.takeIf { it.hasAltitude() }?.altitude?.let { it * 3.28084 },
            alerts_enabled = alerts_enabled,
            alert_distance_feet = alert_distance_feet,
            alert_altitude_feet = alert_altitude_feet,
            priority_enabled = priority_tracking_enabled,
            priority_range_feet = priority_range_feet,
            now_epoch_sec = System.currentTimeMillis() / 1000.0,
            is_estimated_position = alert_position.estimated,
            extreme_priority_only = false,
        )?.is_extreme_priority == true
    }

    private fun priority_is_extreme(
        aircraft: Aircraft,
        latest_location: Location?,
        alerts_enabled: Boolean,
        alert_distance_feet: Float,
        alert_altitude_feet: Float,
        priority_tracking_enabled: Boolean,
        priority_range_feet: Float,
        alert_position: ProjectedAlertPosition,
        now_epoch_sec: Double,
    ): Boolean {
        return AlertAircraftClassifier.classify(
            icao24 = aircraft.icao24,
            callsign = aircraft.callsign,
            registration = aircraft.registration,
            distance_meters = alert_position.distance_meters,
            altitude_meters = alert_position.altitude_meters,
            last_contact_sec = aircraft.last_contact_sec,
            position_time_sec = aircraft.position_time_sec,
            own_altitude_feet = latest_location?.takeIf { it.hasAltitude() }?.altitude?.let { it * 3.28084 },
            alerts_enabled = alerts_enabled,
            alert_distance_feet = alert_distance_feet,
            alert_altitude_feet = alert_altitude_feet,
            priority_enabled = priority_tracking_enabled,
            priority_range_feet = priority_range_feet,
            now_epoch_sec = now_epoch_sec,
            is_estimated_position = alert_position.estimated,
            extreme_priority_only = false,
        )?.is_extreme_priority == true
    }

    fun synchronize_reference_filter_scroll(plan: ReferenceFiltersPanelPlan) {
        reference_filters_panel_session = reference_filters_panel_session.copy(
            requestedScrollOffsetDp = plan.appliedScrollOffsetDp,
        )
        filter_panel_scroll_start_offset_dp = plan.appliedScrollOffsetDp
    }

    fun clear_reference_filter_action_focus() {
        reference_filters_panel_session = reference_filters_panel_session.copy(focusedActionId = null)
    }

    fun update_filter_panel_scroll(
        w: Float,
        h: Float,
        down_x: Float,
        y: Float,
        density: Float,
        plan: ReferenceFiltersPanelPlan,
        drag_started: Boolean,
        drag_threshold: Float,
    ): ReferencePanelIntent? {
        if (!filters_open || plan.maxScrollOffsetDp <= 0f) return null
        val panel = layout.settings_panel_bounds(w, h)
        val content = plan.contentViewport
        val down_inside_content = down_x >= panel.left + content.left * density &&
                down_x <= panel.left + content.right * density &&
                panel_scroll_start_y >= panel.top + content.top * density &&
                panel_scroll_start_y <= panel.top + content.bottom * density
        if (!down_inside_content) return null
        val dy = y - panel_scroll_start_y
        if (!drag_started && abs(dy) <= drag_threshold) return null
        if (!drag_started) clear_reference_filter_action_focus()
        val next = (filter_panel_scroll_start_offset_dp - dy / density)
            .coerceIn(0f, plan.maxScrollOffsetDp)
        return ReferencePanelIntent.SetScrollOffset(next)
    }

    fun reference_filter_panel_key(key_code: Int, shift_pressed: Boolean): ReferencePanelKey? {
        return when (key_code) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> ReferencePanelKey.BACK
            KeyEvent.KEYCODE_TAB -> if (shift_pressed) ReferencePanelKey.PREVIOUS else ReferencePanelKey.NEXT
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> ReferencePanelKey.NEXT
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> ReferencePanelKey.PREVIOUS
            KeyEvent.KEYCODE_PAGE_DOWN -> ReferencePanelKey.SCROLL_FORWARD
            KeyEvent.KEYCODE_PAGE_UP -> ReferencePanelKey.SCROLL_BACKWARD
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE -> if (filter_search_focused) null else ReferencePanelKey.ACTIVATE
            else -> null
        }
    }

    fun handle_reference_filter_panel_key(
        plan: ReferenceFiltersPanelPlan,
        key: ReferencePanelKey,
        filter_state: FilterState,
    ): ReferenceFiltersPanelControllerResult? {
        val intent = reference_filters_panel_controller.keyIntent(
            plan,
            reference_filters_panel_session,
            key,
        ) ?: return null
        return handle_reference_filter_panel_intent(filter_state, intent)
    }

    fun handle_reference_filter_panel_intent(
        filter_state: FilterState,
        intent: ReferencePanelIntent,
    ): ReferenceFiltersPanelControllerResult {
        val result = reference_filters_panel_controller.reduce(
            session = reference_filters_panel_session,
            filterState = filter_state,
            intent = intent,
        )
        reference_filters_panel_session = result.session
        return result
    }

    private data class ReferenceFiltersPanelPlanCacheKey(
        val panelWidthPx: Float,
        val panelHeightPx: Float,
        val density: Float,
        val fontScale: Float,
        val fontFamily: String,
        val compact: Boolean,
        val session: ReferenceFiltersPanelSession,
        val catalog: ReferenceClassCatalog?,
        val filterState: FilterState,
    )

    data class PriorityAdjustHold(
        val button: PriorityRangeAdjustButton,
        val press_x: Float,
        val press_y: Float,
        val started_ms: Long,
        val duration_ms: Long,
        var fired: Boolean = false
    )
}

object PriorityRangeAdjuster {
    fun adjusted_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean,
        units: UnitSystem
    ): Float {
        return if (units == UnitSystem.METRIC) {
            adjusted_metric_feet(current_feet, value, direction, long_press)
        } else {
            adjusted_imperial_feet(current_feet, value, direction, long_press)
        }
    }

    private fun adjusted_metric_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean
    ): Float {
        val current_meters = feet_to_meters(current_feet.toDouble())
        val normal_step_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 250.0
            PriorityRangeValue.VERTICAL -> 100.0
        }
        val action_step_meters = if (long_press) 3000.0 else normal_step_meters
        val min_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 200.0
            PriorityRangeValue.VERTICAL -> 30.0
        }
        val max_meters = when (value) {
            PriorityRangeValue.HORIZONTAL -> 30_000.0
            PriorityRangeValue.VERTICAL -> 30_000.0
        }
        return meters_to_feet(
            stepped_display_value(
                current = current_meters,
                direction = direction.toDouble(),
                normal_step = normal_step_meters,
                action_step = action_step_meters,
                min_value = min_meters,
                max_value = max_meters
            )
        ).toFloat()
    }

    private fun adjusted_imperial_feet(
        current_feet: Float,
        value: PriorityRangeValue,
        direction: Float,
        long_press: Boolean
    ): Float {
        val normal_step_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 1000.0
            PriorityRangeValue.VERTICAL -> 500.0
        }
        val action_step_feet = if (long_press) 10_000.0 else normal_step_feet
        val min_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 500.0
            PriorityRangeValue.VERTICAL -> 100.0
        }
        val max_feet = when (value) {
            PriorityRangeValue.HORIZONTAL -> 100_000.0
            PriorityRangeValue.VERTICAL -> 100_000.0
        }
        return stepped_display_value(
            current = current_feet.toDouble(),
            direction = direction.toDouble(),
            normal_step = normal_step_feet,
            action_step = action_step_feet,
            min_value = min_feet,
            max_value = max_feet
        ).toFloat()
    }

    private fun stepped_display_value(
        current: Double,
        direction: Double,
        normal_step: Double,
        action_step: Double,
        min_value: Double,
        max_value: Double
    ): Double {
        val current_on_grid = grid_aligned_value(current, normal_step)
        val aligned = if (direction > 0.0) {
            floor(current_on_grid / normal_step) * normal_step
        } else {
            ceil(current_on_grid / normal_step) * normal_step
        }
        return (aligned + direction * action_step).coerceIn(min_value, max_value)
    }

    private fun grid_aligned_value(current: Double, step: Double): Double {
        val nearest = round(current / step) * step
        return if (abs(current - nearest) < GRID_EPSILON) nearest else current
    }

    private fun feet_to_meters(feet: Double): Double = feet / FEET_PER_METER

    private fun meters_to_feet(meters: Double): Double = meters * FEET_PER_METER

    private const val FEET_PER_METER = 3.28084

    private const val GRID_EPSILON = 0.05
}
