package com.flightalert.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class AviationLayerRenderCacheBinding(
    val snapshot_identity: Int,
    val selection_key: AviationSelectionKey?
)

internal object AviationLayerRenderCachePolicy {
    fun binding(
        snapshot: AviationLayerSnapshot,
        selection: AviationSelection?
    ): AviationLayerRenderCacheBinding = AviationLayerRenderCacheBinding(
        snapshot_identity = System.identityHashCode(snapshot),
        selection_key = selection?.key
    )
}

internal data class AviationScreenRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom
}

internal data class AviationAcceptedLabelGeometry(
    val point: ScreenPoint,
    val label_bounds: AviationScreenRect
)

internal fun project_aviation_source_point_to_screen(
    point: AviationLayerPoint,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): ScreenPoint? {
    project_aviation_point_to_screen(point, viewport, lat_lon_to_world)?.let { return it }
    val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
    val world_span = com.flightalert.TILE_SIZE * 2.0.pow(viewport.zoom)
    if (!world.x.isFinite() || !world.y.isFinite() ||
        !world_span.isFinite() || world_span <= 0.0
    ) return null
    var screen_x = world.x - viewport.center_x + viewport.width / 2.0
    while (screen_x < -world_span / 2.0) screen_x += world_span
    while (screen_x > viewport.width + world_span / 2.0) screen_x -= world_span
    val screen_y = world.y - viewport.center_y + viewport.height / 2.0
    if (!screen_x.isFinite() || !screen_y.isFinite()) return null
    return ScreenPoint(screen_x.toFloat(), screen_y.toFloat())
}

internal class AviationSelectionHitCollector(
    private val lat_lon_to_world: (Double, Double, Double) -> WorldPoint,
    private val world_to_lat_lon: (Double, Double, Double) -> GeoPoint
) {
    private var draw_identity: DrawIdentity? = null
    private val accepted_label_targets = ArrayList<AcceptedLabelTarget>()

    fun begin_draw(
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility
    ) {
        draw_identity = DrawIdentity(snapshot, viewport, visible_bounds, visibility)
        accepted_label_targets.clear()
    }

    fun accept_airport_marker(
        airport: AviationAirportFeature,
        point: ScreenPoint,
        label_bounds: AviationScreenRect
    ) {
        accepted_label_targets += AcceptedLabelTarget(
            key = AviationSelectionKey.ArcGis(AviationLayerKind.AIRPORTS, airport.object_id),
            kind = AviationHitKind.AIRPORT,
            point = point,
            label_bounds = label_bounds
        )
    }

    fun accept_atc_label(
        feature: AviationAirspaceFeature,
        point: ScreenPoint,
        label_bounds: AviationScreenRect
    ) {
        accepted_label_targets += AcceptedLabelTarget(
            key = AviationSelectionKey.ArcGis(
                AviationLayerKind.ATC_BOUNDARIES,
                feature.object_id
            ),
            kind = AviationHitKind.ATC_BOUNDARY,
            point = point,
            label_bounds = label_bounds
        )
    }

    fun selection_key_at(
        x: Float,
        y: Float,
        density: Float,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        now_epoch_ms: Long
    ): AviationSelectionKey? {
        if (!x.isFinite() || !y.isFinite() || !density.isFinite() || density <= 0f) return null
        val density_squared = density.toDouble() * density.toDouble()
        val candidates = ArrayList<AviationHitCandidate>()

        if (draw_identity?.matches(snapshot, viewport, visible_bounds, visibility) == true) {
            accepted_label_targets.forEach { target ->
                if (!accepted_target_is_eligible(target, snapshot, viewport, visibility)) {
                    return@forEach
                }
                candidates += AviationHitCandidate(
                    key = target.key,
                    kind = target.kind,
                    distance_squared_dp = if (target.label_bounds.contains(x, y)) {
                        0.0
                    } else {
                        point_distance_squared_px(x, y, target.point) / density_squared
                    }
                )
            }
        }

        if (layer_is_eligible(
                AviationLayerKind.RESTRICTED_AIRSPACES,
                visibility.restricted_airspaces_enabled,
                snapshot
            )
        ) {
            val query_geo = screen_to_geo_point(x, y, viewport)
            AviationAirspaceRenderPolicy.forEachApplicable(
                features = snapshot.restricted_airspaces,
                visibleBounds = visible_bounds,
                excludedFeature = null
            ) { feature ->
                val key = AviationSelectionKey.ArcGis(
                    AviationLayerKind.RESTRICTED_AIRSPACES,
                    feature.object_id
                )
                distance_to_airspace_screen_squared_px(x, y, feature, viewport)?.let { distance ->
                    candidates += AviationHitCandidate(
                        key = key,
                        kind = AviationHitKind.SPECIAL_USE_BOUNDARY,
                        distance_squared_dp = distance / density_squared
                    )
                }
                if (AviationAirspaceRenderPolicy.contains(feature, query_geo.lat, query_geo.lon)) {
                    candidates += AviationHitCandidate(
                        key = key,
                        kind = AviationHitKind.SPECIAL_USE_INTERIOR,
                        containment_area = AviationAirspaceRenderPolicy.boundsArea(feature)
                    )
                }
            }
        }

        if (layer_is_eligible(
                AviationLayerKind.ATC_BOUNDARIES,
                visibility.atc_boundaries_enabled,
                snapshot
            )
        ) {
            AviationAirspaceRenderPolicy.forEachApplicable(
                features = snapshot.atc_boundaries,
                visibleBounds = visible_bounds,
                excludedFeature = null
            ) { feature ->
                distance_to_airspace_screen_squared_px(x, y, feature, viewport)?.let { distance ->
                    candidates += AviationHitCandidate(
                        key = AviationSelectionKey.ArcGis(
                            AviationLayerKind.ATC_BOUNDARIES,
                            feature.object_id
                        ),
                        kind = AviationHitKind.ATC_BOUNDARY,
                        distance_squared_dp = distance / density_squared
                    )
                }
            }
        }

        if (visibility.oceanic_tracks_enabled) {
            val status = snapshot.statuses[AviationLayerKind.OCEANIC_TRACKS]
            val publication = snapshot.publications[AviationLayerKind.OCEANIC_TRACKS]
            snapshot.oceanic_tracks.forEach { track ->
                if (!AviationSelectionPolicy.nat_is_eligible(
                        enabled = true,
                        track = track,
                        now_epoch_ms = now_epoch_ms,
                        status = status,
                        publication = publication
                    )
                ) return@forEach
                distance_to_source_paths_screen_squared_px(
                    x,
                    y,
                    track.drawable_segments,
                    viewport
                )?.let { distance ->
                    candidates += AviationHitCandidate(
                        key = AviationSelection.OceanicTrack(track).key,
                        kind = AviationHitKind.NAT_SEGMENT,
                        distance_squared_dp = distance / density_squared
                    )
                }
            }
        }

        return AviationSelectionPolicy.choose_hit(candidates)
    }

    private fun accepted_target_is_eligible(
        target: AcceptedLabelTarget,
        snapshot: AviationLayerSnapshot,
        viewport: Viewport,
        visibility: AviationLayerVisibility
    ): Boolean = when (target.kind) {
        AviationHitKind.AIRPORT ->
            AviationSelectionPolicy.resolve(target.key, snapshot) is AviationSelection.Airport &&
                AviationSelectionPolicy.airport_is_eligible(
                    enabled = visibility.airport_labels_enabled,
                    zoom = viewport.zoom,
                    status = snapshot.statuses[AviationLayerKind.AIRPORTS],
                    publication = snapshot.publications[AviationLayerKind.AIRPORTS]
                )
        AviationHitKind.ATC_BOUNDARY ->
            AviationSelectionPolicy.resolve(target.key, snapshot) is AviationSelection.AtcBoundary &&
                layer_is_eligible(
                    AviationLayerKind.ATC_BOUNDARIES,
                    visibility.atc_boundaries_enabled,
                    snapshot
                )
        else -> false
    }

    private fun layer_is_eligible(
        kind: AviationLayerKind,
        enabled: Boolean,
        snapshot: AviationLayerSnapshot
    ): Boolean = AviationSelectionPolicy.layer_is_eligible(
        kind = kind,
        enabled = enabled,
        status = snapshot.statuses[kind],
        publication = snapshot.publications[kind]
    )

    private fun screen_to_geo_point(x: Float, y: Float, viewport: Viewport): GeoPoint {
        val world_x = viewport.center_x - viewport.width / 2.0 + x
        val world_y = viewport.center_y - viewport.height / 2.0 + y
        return world_to_lat_lon(world_x, world_y, viewport.zoom)
    }

    private fun distance_to_airspace_screen_squared_px(
        x: Float,
        y: Float,
        feature: AviationAirspaceFeature,
        viewport: Viewport
    ): Double? = distance_to_source_paths_screen_squared_px(
        x,
        y,
        feature.rings,
        viewport
    )

    private fun distance_to_source_paths_screen_squared_px(
        x: Float,
        y: Float,
        source_paths: List<List<AviationLayerPoint>>,
        viewport: Viewport
    ): Double? {
        var best: Double? = null
        source_paths.forEach { source_path ->
            val points = source_path_screen_points(source_path, viewport)
            if (points.size < 2) return@forEach
            for (index in 1 until points.size) {
                val distance = wrapped_point_to_segment_distance_squared_px(
                    x,
                    y,
                    points[index - 1],
                    points[index],
                    viewport
                )
                best = min(best ?: distance, distance)
            }
        }
        return best
    }

    private fun source_path_screen_points(
        source: List<AviationLayerPoint>,
        viewport: Viewport
    ): List<ScreenPoint> {
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        if (!world_span.isFinite() || world_span <= 0.0) return emptyList()
        val result = ArrayList<ScreenPoint>(source.size)
        source.forEach { source_point ->
            val projected = project_aviation_source_point_to_screen(
                source_point,
                viewport,
                lat_lon_to_world
            ) ?: return@forEach
            var x = projected.x.toDouble()
            val previous_x = result.lastOrNull()?.x?.toDouble()
            if (previous_x != null) {
                while (x - previous_x > world_span / 2.0) x -= world_span
                while (x - previous_x < -world_span / 2.0) x += world_span
            }
            result += ScreenPoint(x.toFloat(), projected.y)
        }
        return result
    }

    private fun wrapped_point_to_segment_distance_squared_px(
        x: Float,
        y: Float,
        start: ScreenPoint,
        end: ScreenPoint,
        viewport: Viewport
    ): Double {
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        var best = Double.POSITIVE_INFINITY
        for (world_copy in -2..2) {
            val shift = world_copy * world_span
            best = min(
                best,
                point_to_segment_distance_squared_px(
                    x.toDouble(),
                    y.toDouble(),
                    start.x.toDouble() + shift,
                    start.y.toDouble(),
                    end.x.toDouble() + shift,
                    end.y.toDouble()
                )
            )
        }
        return best
    }

    private fun point_to_segment_distance_squared_px(
        x: Double,
        y: Double,
        start_x: Double,
        start_y: Double,
        end_x: Double,
        end_y: Double
    ): Double {
        val dx = end_x - start_x
        val dy = end_y - start_y
        val length_squared = dx * dx + dy * dy
        if (length_squared <= 0.0001) {
            val px = x - start_x
            val py = y - start_y
            return px * px + py * py
        }
        val t = (((x - start_x) * dx + (y - start_y) * dy) / length_squared)
            .coerceIn(0.0, 1.0)
        val px = x - (start_x + dx * t)
        val py = y - (start_y + dy * t)
        return px * px + py * py
    }

    private fun point_distance_squared_px(x: Float, y: Float, point: ScreenPoint): Double {
        val dx = x.toDouble() - point.x
        val dy = y.toDouble() - point.y
        return dx * dx + dy * dy
    }

    private data class AcceptedLabelTarget(
        val key: AviationSelectionKey,
        val kind: AviationHitKind,
        val point: ScreenPoint,
        val label_bounds: AviationScreenRect
    )

    private class DrawIdentity(
        val snapshot: AviationLayerSnapshot,
        val viewport: Viewport,
        val visible_bounds: AviationGeoBounds,
        val visibility: AviationLayerVisibility
    ) {
        fun matches(
            snapshot: AviationLayerSnapshot,
            viewport: Viewport,
            visible_bounds: AviationGeoBounds,
            visibility: AviationLayerVisibility
        ): Boolean = this.snapshot === snapshot &&
            this.viewport == viewport &&
            this.visible_bounds == visible_bounds &&
            this.visibility == visibility
    }

    private companion object {
        const val TILE_SIZE = 256.0
    }
}

internal data class AviationLayerInspectorStyle(
    val panel_color: Int,
    val modal_panel_alpha: Int,
    val text_color: Int,
    val muted_color: Int,
    val accent_orange_color: Int,
    val accent_blue_color: Int,
    val accent_pink_color: Int,
    val accent_yellow_color: Int,
    val military_gray_color: Int
)

internal enum class AviationInspectorContentSection { SELECTION_ROWS, PROVENANCE }

internal object AviationInspectorContentPolicy {
    fun sections(kind: AviationLayerKind): List<AviationInspectorContentSection> =
        if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) {
            listOf(
                AviationInspectorContentSection.SELECTION_ROWS,
                AviationInspectorContentSection.PROVENANCE
            )
        } else {
            listOf(
                AviationInspectorContentSection.PROVENANCE,
                AviationInspectorContentSection.SELECTION_ROWS
            )
        }
}

internal object AviationInspectorPanelPolicy {
    fun bounds(
        w: Float,
        h: Float,
        density: Float,
        kind: AviationLayerKind
    ): AviationScreenRect {
        val safe_density = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        fun dp(value: Float): Float = value * safe_density
        val panel_width = min(w - dp(32f), dp(620f)).coerceAtLeast(dp(280f))
        val panel_height = if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) {
            min(h - dp(72f), dp(286f)).coerceAtLeast(dp(236f))
        } else {
            min(h - dp(48f), dp(520f)).coerceAtLeast(dp(236f))
        }
        val left = (w - panel_width) / 2f
        val top = if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) {
            (h - panel_height - dp(24f)).coerceAtLeast(dp(24f))
        } else {
            ((h - panel_height) / 2f).coerceAtLeast(dp(24f))
        }
        return AviationScreenRect(left, top, left + panel_width, top + panel_height)
    }

    fun title_max_width(
        panel_width: Float,
        density: Float,
        kind: AviationLayerKind
    ): Float {
        val safe_density = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val reserved_dp = if (kind == AviationLayerKind.RESTRICTED_AIRSPACES) 142f else 146f
        return (panel_width - reserved_dp * safe_density).coerceAtLeast(36f * safe_density)
    }
}

internal data class AviationInspectorDrawResult(
    val scroll_y: Float,
    val max_scroll_y: Float
)

internal data class AviationInspectorScrollLayout(
    val scroll_y: Float,
    val max_scroll_y: Float,
    val header_translation_y: Float,
    val body_translation_y: Float
)

internal object AviationInspectorScrollPolicy {
    fun layout(requested_scroll_y: Float, max_scroll_y: Float): AviationInspectorScrollLayout {
        val safe_max = max_scroll_y.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val scroll = requested_scroll_y.takeIf { it.isFinite() }
            ?.coerceIn(0f, safe_max)
            ?: 0f
        return AviationInspectorScrollLayout(
            scroll_y = scroll,
            max_scroll_y = safe_max,
            header_translation_y = 0f,
            body_translation_y = -scroll
        )
    }

    fun scroll_from_drag(
        start_scroll_y: Float,
        drag_delta_y: Float,
        max_scroll_y: Float
    ): Float = layout(start_scroll_y - drag_delta_y, max_scroll_y).scroll_y
}

internal enum class AviationInspectorTapAction { CLOSE, CONSUME }

internal object AviationInspectorInteractionPolicy {
    fun action_for(
        inside_panel: Boolean,
        inside_close_button: Boolean
    ): AviationInspectorTapAction = if (inside_close_button || !inside_panel) {
        AviationInspectorTapAction.CLOSE
    } else {
        AviationInspectorTapAction.CONSUME
    }
}

internal class AviationLayerInspector(
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String,
    private val draw_panel_surface: (Canvas, RectF, Int, Int) -> Unit,
    private val draw_choice_button: (Canvas, RectF, String, Boolean) -> Unit,
    private val draw_wrapped_text: (Canvas, String, Float, Float, Float, Int) -> Float
) {
    fun draw_details_panel(
        canvas: Canvas,
        w: Float,
        h: Float,
        selection: AviationSelection,
        snapshot: AviationLayerSnapshot,
        now_epoch_ms: Long,
        scroll_y: Float,
        style: AviationLayerInspectorStyle
    ): AviationInspectorDrawResult {
        val presentation = AviationSelectionPresentationPolicy.build(
            selection,
            snapshot,
            now_epoch_ms
        )
        val panel = panel_bounds(w, h, selection.kind)
        val accent = accent_for(selection, style)
        draw_panel_surface(canvas, panel, style.panel_color, style.modal_panel_alpha)
        draw_choice_button(canvas, close_button_bounds(panel), "Close", false)
        draw_fixed_header(canvas, panel, selection.kind, presentation, accent, style)

        val body = body_bounds(panel)
        val requested_scroll = scroll_y.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val checkpoint = canvas.save()
        canvas.clipRect(body)
        canvas.translate(0f, -requested_scroll)
        val content_bottom = draw_body(
            canvas = canvas,
            body = body,
            selection = selection,
            presentation = presentation,
            accent = accent,
            style = style
        )
        canvas.restoreToCount(checkpoint)

        val content_height = content_bottom - body.top + dp(12f)
        val max_scroll = (content_height - body.height()).coerceAtLeast(0f)
        val layout = AviationInspectorScrollPolicy.layout(requested_scroll, max_scroll)
        draw_scroll_indicator(canvas, body, layout, style)
        return AviationInspectorDrawResult(layout.scroll_y, layout.max_scroll_y)
    }

    fun panel_bounds(w: Float, h: Float, kind: AviationLayerKind): RectF =
        AviationInspectorPanelPolicy.bounds(w, h, dp(1f), kind).let { bounds ->
            RectF(bounds.left, bounds.top, bounds.right, bounds.bottom)
        }

    fun close_button_bounds(panel: RectF): RectF = RectF(
        panel.right - dp(118f),
        panel.top + dp(14f),
        panel.right - dp(18f),
        panel.top + dp(48f)
    )

    fun body_bounds(panel: RectF): RectF = RectF(
        panel.left + dp(2f),
        panel.top + dp(70f),
        panel.right - dp(2f),
        panel.bottom - dp(10f)
    )

    private fun draw_fixed_header(
        canvas: Canvas,
        panel: RectF,
        kind: AviationLayerKind,
        presentation: AviationSelectionPresentation,
        accent: Int,
        style: AviationLayerInspectorStyle
    ) {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(22f)
        text_paint.color = style.text_color
        val title_left = panel.left + dp(18f)
        canvas.drawText(
            ellipsize(
                presentation.title,
                AviationInspectorPanelPolicy.title_max_width(
                    panel_width = panel.width(),
                    density = dp(1f),
                    kind = kind
                )
            ),
            title_left,
            panel.top + dp(34f),
            text_paint
        )

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(11f)
        text_paint.color = accent
        canvas.drawText(
            ellipsize(presentation.subtitle, panel.width() - dp(142f)),
            title_left,
            panel.top + dp(55f),
            text_paint
        )
    }

    private fun draw_body(
        canvas: Canvas,
        body: RectF,
        selection: AviationSelection,
        presentation: AviationSelectionPresentation,
        accent: Int,
        style: AviationLayerInspectorStyle
    ): Float {
        var y = body.top + if (selection is AviationSelection.SpecialUse) dp(18f) else dp(22f)
        AviationInspectorContentPolicy.sections(selection.kind).forEach { section ->
            y = when (section) {
                AviationInspectorContentSection.SELECTION_ROWS -> {
                    if (selection is AviationSelection.SpecialUse) {
                        draw_legacy_special_use_rows(canvas, body, y, presentation.rows, style)
                    } else {
                        draw_selection_rows(canvas, body, y, presentation.rows, style)
                    }
                }
                AviationInspectorContentSection.PROVENANCE ->
                    draw_provenance(canvas, body, y, presentation.provenance, accent, style)
            }
        }
        return y
    }

    private fun draw_provenance(
        canvas: Canvas,
        body: RectF,
        start_y: Float,
        provenance: AviationProvenancePresentation,
        accent: Int,
        style: AviationLayerInspectorStyle
    ): Float {
        val left = body.left + dp(16f)
        val width = body.width() - dp(32f)
        var y = start_y
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(13f)
        text_paint.color = accent
        y = draw_wrapped_text(
            canvas,
            provenance.banner,
            left,
            y,
            width,
            3
        ) + dp(8f)

        provenance.latest_refresh?.let { latest ->
            y = draw_detail_row(canvas, left, width, y, "Latest refresh", latest, style)
        }
        y = draw_detail_row(
            canvas,
            left,
            width,
            y,
            "Displayed source",
            provenance.source,
            style
        )
        y = draw_detail_row(
            canvas,
            left,
            width,
            y,
            "Observed UTC",
            provenance.observed_utc,
            style
        ) + dp(5f)
        return y
    }

    private fun draw_selection_rows(
        canvas: Canvas,
        body: RectF,
        start_y: Float,
        rows: List<AviationDetailRow>,
        style: AviationLayerInspectorStyle
    ): Float {
        val left = body.left + dp(16f)
        val width = body.width() - dp(32f)
        var y = start_y
        rows.forEach { row ->
            y = draw_detail_row(canvas, left, width, y, row.label, row.value, style)
        }
        return y
    }

    private fun draw_legacy_special_use_rows(
        canvas: Canvas,
        body: RectF,
        start_y: Float,
        rows: List<AviationDetailRow>,
        style: AviationLayerInspectorStyle
    ): Float {
        var y = start_y
        rows.forEach { row ->
            text_paint.textAlign = Paint.Align.LEFT
            text_paint.isFakeBoldText = true
            text_paint.textSize = sp(12f)
            text_paint.color = style.muted_color
            val label_x = body.left + dp(16f)
            canvas.drawText(row.label.uppercase(Locale.US), label_x, y, text_paint)

            text_paint.isFakeBoldText = false
            text_paint.textSize = sp(14f)
            text_paint.color = style.text_color
            val value_x = body.left + dp(110f)
            val max_width = body.right - value_x - dp(16f)
            val bottom = draw_wrapped_text(
                canvas,
                row.value,
                value_x,
                y,
                max_width,
                2
            )
            y = max(bottom + dp(9f), y + dp(30f))
        }
        return y + dp(6f)
    }

    private fun draw_detail_row(
        canvas: Canvas,
        left: Float,
        width: Float,
        y: Float,
        label: String,
        value: String,
        style: AviationLayerInspectorStyle
    ): Float {
        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11f)
        text_paint.color = style.muted_color
        canvas.drawText(label.uppercase(Locale.US), left, y, text_paint)

        text_paint.isFakeBoldText = false
        text_paint.textSize = sp(14f)
        text_paint.color = style.text_color
        val bottom = draw_wrapped_text(
            canvas,
            value,
            left,
            y + dp(20f),
            width,
            MAX_DETAIL_LINES
        )
        return max(bottom + dp(10f), y + dp(42f))
    }

    private fun draw_scroll_indicator(
        canvas: Canvas,
        body: RectF,
        layout: AviationInspectorScrollLayout,
        style: AviationLayerInspectorStyle
    ) {
        if (layout.max_scroll_y <= 0f) return
        val viewport_fraction = body.height() / (body.height() + layout.max_scroll_y)
        val thumb_height = (body.height() * viewport_fraction).coerceAtLeast(dp(24f))
        val travel = body.height() - thumb_height
        val progress = layout.scroll_y / layout.max_scroll_y
        val top = body.top + travel * progress
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = dp(2f)
        text_paint.color = style.muted_color
        canvas.drawLine(body.right - dp(5f), top, body.right - dp(5f), top + thumb_height, text_paint)
        text_paint.style = Paint.Style.FILL
    }

    private fun accent_for(
        selection: AviationSelection,
        style: AviationLayerInspectorStyle
    ): Int = when (selection) {
        is AviationSelection.SpecialUse -> style.accent_orange_color
        is AviationSelection.AtcBoundary -> style.accent_blue_color
        is AviationSelection.OceanicTrack -> style.accent_pink_color
        is AviationSelection.Airport ->
            if (selection.airport.military) style.military_gray_color else style.accent_yellow_color
    }

    private companion object {
        const val MAX_DETAIL_LINES = 64
    }
}
