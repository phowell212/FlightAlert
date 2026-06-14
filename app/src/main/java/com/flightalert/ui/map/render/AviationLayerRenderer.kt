package com.flightalert.ui.map.render

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RectF
import com.flightalert.data.AviationAirspaceFeature
import com.flightalert.data.AviationAirportFeature
import com.flightalert.data.AviationGeoBounds
import com.flightalert.data.AviationLayerPoint
import com.flightalert.data.AviationLayerSnapshot
import com.flightalert.data.AviationOceanicTrack
import com.flightalert.settings.FlightAlertSettings.ThemeTreatment
import com.flightalert.ui.map.MapProjection
import com.flightalert.ui.map.ScreenPoint
import com.flightalert.ui.map.Viewport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

data class AviationLayerVisibility(
    val restricted_airspaces_enabled: Boolean,
    val atc_boundaries_enabled: Boolean,
    val oceanic_tracks_enabled: Boolean,
    val airport_labels_enabled: Boolean
)

data class AviationLayerStyle(
    val accent_orange: Int,
    val danger: Int,
    val accent_blue: Int,
    val accent_green: Int,
    val accent_pink: Int,
    val accent_yellow: Int,
    val military_gray: Int,
    val panel: Int,
    val text: Int,
    val treatment: ThemeTreatment
)

class AviationLayerRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String
) {
    private var prepared_snapshot: AviationLayerSnapshot? = null
    private var prepared_restricted_airspaces: List<PreparedAirspaceFeature> = emptyList()
    private var prepared_atc_boundaries: List<PreparedAirspaceFeature> = emptyList()
    private val layer_label_rects = ArrayList<RectF>(16)
    private var settled_cache_bitmap: Bitmap? = null
    private var settled_cache_canvas: Canvas? = null
    private var settled_cache_key: SettledLayerCacheKey? = null
    private val settled_cache_matrix = Matrix()
    private var interaction_line_points = FloatArray(INTERACTION_LINE_BUFFER_INITIAL_FLOATS)

    fun draw_layers(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature? = null,
        interaction_active: Boolean = false
    ) {
        prepare_snapshot_if_needed(snapshot)
        if (interaction_active && draw_transformed_settled_cache(canvas, viewport, snapshot, visibility, style, selected_restricted_airspace)) {
            return
        }
        if (!interaction_active && draw_settled_cache_if_current(canvas, viewport, snapshot, visible_bounds, visibility, style, selected_restricted_airspace)) {
            return
        }
        if (!interaction_active && draw_into_settled_cache(canvas, viewport, snapshot, visible_bounds, visibility, style, selected_restricted_airspace)) {
            return
        }
        draw_layers_direct(
            canvas = canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selected_restricted_airspace = selected_restricted_airspace,
            interaction_active = interaction_active
        )
    }

    private fun draw_settled_cache_if_current(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (key != settled_layer_cache_key(viewport, snapshot, visible_bounds, visibility, style, selected_restricted_airspace)) return false
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return true
    }

    private fun draw_transformed_settled_cache(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (bitmap.isRecycled) return false
        if (key.snapshot_identity != System.identityHashCode(snapshot) ||
            key.width != viewport.width.toInt() ||
            key.height != viewport.height.toInt() ||
            key.visibility != visibility ||
            key.style != style ||
            key.selected_restricted_airspace_identity != selected_restricted_airspace?.let(System::identityHashCode)
        ) {
            return false
        }
        val zoom_delta = viewport.zoom - key.zoom
        if (abs(zoom_delta) > MAX_TRANSFORMED_CACHE_ZOOM_DELTA) return false
        val scale = 2.0.pow(zoom_delta).toFloat()
        if (scale <= 0f || !scale.isFinite()) return false
        val translation_x = (key.center_x * scale - viewport.center_x + viewport.width / 2.0 - key.width * scale / 2.0).toFloat()
        val translation_y = (key.center_y * scale - viewport.center_y + viewport.height / 2.0 - key.height * scale / 2.0).toFloat()
        if (abs(translation_x) > viewport.width * MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION ||
            abs(translation_y) > viewport.height * MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION
        ) {
            return false
        }
        settled_cache_matrix.reset()
        settled_cache_matrix.setScale(scale, scale)
        settled_cache_matrix.postTranslate(translation_x, translation_y)
        canvas.drawBitmap(bitmap, settled_cache_matrix, null)
        return true
    }

    private fun draw_into_settled_cache(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?
    ): Boolean {
        val layer_canvas = settled_cache_canvas_for(viewport) ?: return false
        val bitmap = settled_cache_bitmap ?: return false
        layer_canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        draw_layers_direct(
            canvas = layer_canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selected_restricted_airspace = selected_restricted_airspace,
            interaction_active = false
        )
        settled_cache_key = settled_layer_cache_key(viewport, snapshot, visible_bounds, visibility, style, selected_restricted_airspace)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return true
    }

    private fun settled_cache_canvas_for(viewport: Viewport): Canvas? {
        val width = viewport.width.toInt().coerceAtLeast(1)
        val height = viewport.height.toInt().coerceAtLeast(1)
        val current = settled_cache_bitmap
        if (current != null && current.width == width && current.height == height && !current.isRecycled) {
            return settled_cache_canvas
        }
        current?.recycle()
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            settled_cache_bitmap = bitmap
            settled_cache_canvas = Canvas(bitmap)
            settled_cache_key = null
            settled_cache_canvas
        } catch (_: OutOfMemoryError) {
            settled_cache_bitmap = null
            settled_cache_canvas = null
            settled_cache_key = null
            null
        }
    }

    private fun settled_layer_cache_key(
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?
    ): SettledLayerCacheKey {
        return SettledLayerCacheKey(
            snapshot_identity = System.identityHashCode(snapshot),
            width = viewport.width.toInt(),
            height = viewport.height.toInt(),
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selected_restricted_airspace_identity = selected_restricted_airspace?.let(System::identityHashCode)
        )
    }

    private fun draw_layers_direct(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selected_restricted_airspace: AviationAirspaceFeature?,
        interaction_active: Boolean
    ) {
        layer_label_rects.clear()
        if (visibility.restricted_airspaces_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_restricted_airspaces,
                visible_bounds = visible_bounds,
                excluded_feature = selected_restricted_airspace,
                stroke = restricted_airspace_stroke(style),
                fill = restricted_airspace_fill(style),
                label_limit = if (viewport.zoom >= 8.0) 6 else 3,
                label_rects = layer_label_rects,
                style = style,
                restricted = true,
                interaction_active = interaction_active
            )
            selected_restricted_airspace
                ?.takeIf { it.bounds.intersects(visible_bounds) }
                ?.let { selected ->
                    val selected_prepared = prepared_restricted_airspaces.firstOrNull { it.source == selected }
                        ?: prepare_airspace_feature(selected)
                        ?: return@let
                    draw_selected_airspace(canvas, viewport, selected, style)
                    if (viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                        draw_airspace_label(canvas, viewport, selected_prepared, layer_label_rects, style)
                    }
                }
        }
        if (visibility.atc_boundaries_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_atc_boundaries,
                visible_bounds = visible_bounds,
                excluded_feature = null,
                stroke = style.accent_blue,
                fill = style.accent_blue,
                label_limit = 4,
                label_rects = layer_label_rects,
                style = style,
                restricted = false,
                interaction_active = interaction_active
            )
        }
        if (visibility.oceanic_tracks_enabled) {
            draw_oceanic_tracks(
                canvas = canvas,
                viewport = viewport,
                tracks = snapshot.oceanic_tracks.filter { it.bounds.intersects(visible_bounds) },
                label_rects = layer_label_rects,
                style = style
            )
        }
        if (visibility.airport_labels_enabled) {
            draw_airport_labels(
                canvas = canvas,
                viewport = viewport,
                airports = snapshot.airports,
                label_rects = layer_label_rects,
                style = style
            )
        }
    }

    private fun prepare_snapshot_if_needed(snapshot: AviationLayerSnapshot) {
        if (prepared_snapshot === snapshot) return
        prepared_snapshot = snapshot
        prepared_restricted_airspaces = prepare_airspace_features(snapshot.restricted_airspaces)
        prepared_atc_boundaries = prepare_airspace_features(snapshot.atc_boundaries)
    }

    private fun prepare_airspace_features(features: List<AviationAirspaceFeature>): List<PreparedAirspaceFeature> {
        return features
            .mapNotNull(::prepare_airspace_feature)
            .sortedBy { it.point_count }
    }

    private fun prepare_airspace_feature(feature: AviationAirspaceFeature): PreparedAirspaceFeature? {
        val rings = feature.rings
            .take(MAX_DRAWN_RINGS_PER_FEATURE)
            .mapNotNull { ring -> prepare_airspace_ring(ring, MAX_DRAWN_AIRSPACE_POINTS_PER_RING) }
        val interaction_rings = feature.rings
            .take(MAX_DRAWN_RINGS_PER_FEATURE)
            .mapNotNull { ring -> prepare_airspace_ring(ring, MAX_DRAWN_AIRSPACE_POINTS_PER_RING_INTERACTION) }
        if (rings.isEmpty()) return null
        val label = airspace_label(feature)
        return PreparedAirspaceFeature(
            source = feature,
            label = label,
            center = feature.bounds.center_point(),
            point_count = rings.sumOf { it.point_count },
            rings = rings,
            interaction_rings = interaction_rings.ifEmpty { rings }
        )
    }

    private fun airspace_label(feature: AviationAirspaceFeature): String {
        val type = feature.type.trim()
        if (type.isBlank() || type.equals(feature.name, ignoreCase = true)) return feature.name
        val type_already_in_name = Regex("\\b${Regex.escape(type)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(feature.name)
        return if (type_already_in_name) feature.name else "${feature.name} $type"
    }

    private fun prepare_airspace_ring(ring: List<AviationLayerPoint>, max_points: Int): PreparedAirspaceRing? {
        if (ring.size < 3) return null
        val step = max(1, ring.size / max_points)
        val sampled_count = ring.indices.count { index -> index % step == 0 || index == ring.lastIndex }
        if (sampled_count < 3) return null
        val points = FloatArray(sampled_count * 2)
        var output = 0
        ring.forEachIndexed { index, point ->
            if (index % step == 0 || index == ring.lastIndex) {
                val world = MapProjection.lat_lon_to_world(point.lat, point.lon, 0.0)
                points[output++] = world.x.toFloat()
                points[output++] = world.y.toFloat()
            }
        }
        return PreparedAirspaceRing(points = points, point_count = sampled_count)
    }

    private fun draw_airspace_layer(
        canvas: Canvas,
        viewport: Viewport,
        features: List<PreparedAirspaceFeature>,
        visible_bounds: AviationGeoBounds,
        excluded_feature: AviationAirspaceFeature?,
        stroke: Int,
        fill: Int,
        label_limit: Int,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle,
        restricted: Boolean,
        interaction_active: Boolean
    ) {
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(
            when {
                interaction_active && restricted -> 1.25f
                interaction_active -> 1.0f
                restricted && viewport.zoom >= 8.0 -> 2.0f
                viewport.zoom >= 8.0 -> 1.6f
                restricted -> 1.35f
                else -> 1.1f
            }
        )
        stroke_paint.color = with_alpha(
            stroke,
            when {
                interaction_active && viewport.zoom >= 8.0 -> 190
                interaction_active -> 150
                viewport.zoom >= 8.0 -> 220
                else -> 170
            }
        )
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(fill, if (restricted && viewport.zoom >= 8.5) 28 else if (viewport.zoom >= 8.5) 18 else 10)

        if (interaction_active) {
            draw_interaction_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = features,
                visible_bounds = visible_bounds,
                excluded_feature = excluded_feature,
                outline_paint = stroke_paint
            )
            stroke_paint.strokeCap = Paint.Cap.BUTT
            stroke_paint.strokeJoin = Paint.Join.MITER
            return
        }

        var labels_drawn = 0
        var drawn_features = 0
        for (feature in features) {
            if (feature.source == excluded_feature || !feature.source.bounds.intersects(visible_bounds)) continue
            if (drawn_features >= MAX_DRAWN_AIRSPACE_FEATURES) break
            feature.rings.forEach { ring ->
                draw_prepared_airspace_ring(canvas, viewport, ring, paint, stroke_paint)
            }
            if (labels_drawn < label_limit && viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                if (draw_airspace_label(canvas, viewport, feature, label_rects, style)) {
                    labels_drawn++
                }
            }
            drawn_features++
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_interaction_airspace_layer(
        canvas: Canvas,
        viewport: Viewport,
        features: List<PreparedAirspaceFeature>,
        visible_bounds: AviationGeoBounds,
        excluded_feature: AviationAirspaceFeature?,
        outline_paint: Paint
    ) {
        val scale = 2.0.pow(viewport.zoom).toFloat()
        val world_span = TILE_SIZE * scale
        val screen_offset_x = (-viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_offset_y = (-viewport.center_y + viewport.height / 2.0).toFloat()
        var drawn_features = 0
        var float_count = 0
        for (feature in features) {
            if (feature.source == excluded_feature || !feature.source.bounds.intersects(visible_bounds)) continue
            if (drawn_features >= MAX_DRAWN_AIRSPACE_FEATURES) break
            feature.interaction_rings.forEach { ring ->
                float_count = append_interaction_ring_lines(
                    ring = ring,
                    viewport = viewport,
                    scale = scale,
                    world_span = world_span,
                    screen_offset_x = screen_offset_x,
                    screen_offset_y = screen_offset_y,
                    float_count = float_count
                )
                if (float_count >= INTERACTION_LINE_FLUSH_FLOATS) {
                    canvas.drawLines(interaction_line_points, 0, float_count, outline_paint)
                    float_count = 0
                }
            }
            drawn_features++
        }
        if (float_count > 0) {
            canvas.drawLines(interaction_line_points, 0, float_count, outline_paint)
        }
    }

    private fun append_interaction_ring_lines(
        ring: PreparedAirspaceRing,
        viewport: Viewport,
        scale: Float,
        world_span: Float,
        screen_offset_x: Float,
        screen_offset_y: Float,
        float_count: Int
    ): Int {
        val points = ring.points
        if (points.size < 6) return float_count
        var output = float_count
        var first_x = 0f
        var first_y = 0f
        var previous_x = 0f
        var previous_y = 0f
        var has_previous = false
        var visible = false
        var index = 0
        while (index + 1 < points.size) {
            var sx = points[index] * scale + screen_offset_x
            while (sx < -world_span / 2f) sx += world_span
            while (sx > viewport.width + world_span / 2f) sx -= world_span
            val sy = points[index + 1] * scale + screen_offset_y
            if (sx > -viewport.width * 0.5f &&
                sx < viewport.width * 1.5f &&
                sy > -viewport.height * 0.5f &&
                sy < viewport.height * 1.5f
            ) {
                visible = true
            }
            if (has_previous) {
                output = append_interaction_line(previous_x, previous_y, sx, sy, output)
            } else {
                first_x = sx
                first_y = sy
                has_previous = true
            }
            previous_x = sx
            previous_y = sy
            index += 2
        }
        if (!visible || !has_previous) return float_count
        return append_interaction_line(previous_x, previous_y, first_x, first_y, output)
    }

    private fun append_interaction_line(x1: Float, y1: Float, x2: Float, y2: Float, float_count: Int): Int {
        val next_count = float_count + 4
        ensure_interaction_line_capacity(next_count)
        interaction_line_points[float_count] = x1
        interaction_line_points[float_count + 1] = y1
        interaction_line_points[float_count + 2] = x2
        interaction_line_points[float_count + 3] = y2
        return next_count
    }

    private fun ensure_interaction_line_capacity(required: Int) {
        if (required <= interaction_line_points.size) return
        var next_size = interaction_line_points.size
        while (next_size < required) next_size *= 2
        interaction_line_points = interaction_line_points.copyOf(next_size)
    }

    private fun draw_selected_airspace(
        canvas: Canvas,
        viewport: Viewport,
        feature: AviationAirspaceFeature,
        style: AviationLayerStyle
    ) {
        val prepared = prepared_restricted_airspaces.firstOrNull { it.source == feature }
            ?: prepare_airspace_feature(feature)
            ?: return
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(restricted_airspace_fill(style), selected_restricted_airspace_fill_alpha(style))
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(3.0f)
        stroke_paint.color = with_alpha(selected_restricted_airspace_stroke(style), 235)
        prepared.rings.forEach { ring ->
            draw_prepared_airspace_ring(canvas, viewport, ring, paint, stroke_paint)
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_prepared_airspace_ring(
        canvas: Canvas,
        viewport: Viewport,
        ring: PreparedAirspaceRing,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        val points = ring.points
        if (points.size < 6) return
        path.reset()
        val scale = 2.0.pow(viewport.zoom)
        val world_span = (TILE_SIZE * scale).toFloat()
        val screen_offset_x = (-viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_offset_y = (-viewport.center_y + viewport.height / 2.0).toFloat()
        var index = 0
        var point_index = 0
        var visible = false
        while (index + 1 < points.size) {
            var sx = points[index] * scale.toFloat() + screen_offset_x
            while (sx < -world_span / 2f) sx += world_span
            while (sx > viewport.width + world_span / 2f) sx -= world_span
            val sy = points[index + 1] * scale.toFloat() + screen_offset_y
            if (sx > -viewport.width * 0.5f &&
                sx < viewport.width * 1.5f &&
                sy > -viewport.height * 0.5f &&
                sy < viewport.height * 1.5f
            ) {
                visible = true
            }
            if (point_index == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            point_index++
            index += 2
        }
        if (!visible) return
        path.close()
        if (fill_paint != null) canvas.drawPath(path, fill_paint)
        canvas.drawPath(path, outline_paint)
    }

    private fun draw_airspace_label(
        canvas: Canvas,
        viewport: Viewport,
        feature: PreparedAirspaceFeature,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ): Boolean {
        val screen = aviation_point_to_screen(feature.center, viewport) ?: return false
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return false
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(if (feature.source.type.equals("R", ignoreCase = true)) 11f else 10f)
        text_paint.color = with_alpha(style.text, 224)
        val display = ellipsize(feature.label, dp(142f))
        val width = text_paint.measureText(display) + dp(14f)
        val rect = RectF(screen.x - width / 2f, screen.y - dp(20f), screen.x + width / 2f, screen.y + dp(3f))
        if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return false
        val padded = rect.padded_copy(dp(3f))
        if (label_rects.any { RectF.intersects(padded, it) }) return false
        label_rects += padded
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.panel, 184)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
        canvas.drawText(display, rect.centerX(), rect.bottom - dp(7f), text_paint)
        text_paint.isFakeBoldText = false
        return true
    }

    private fun draw_oceanic_tracks(
        canvas: Canvas,
        viewport: Viewport,
        tracks: List<AviationOceanicTrack>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < OCEANIC_TRACK_MIN_ZOOM) return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(2.2f)
        stroke_paint.color = with_alpha(style.accent_pink, 215)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(11f)
        text_paint.color = style.accent_pink

        tracks.take(MAX_DRAWN_OCEANIC_TRACKS).forEach { track ->
            val points = ring_to_screen_points(track.points, viewport, max_points = MAX_DRAWN_OCEANIC_POINTS)
            if (points.size < 2) return@forEach
            path.reset()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            canvas.drawPath(path, stroke_paint)
            val label_point = points.getOrNull(points.size / 2) ?: return@forEach
            canvas.drawCircle(label_point.x, label_point.y, dp(3f), paint.apply {
                this.style = Paint.Style.FILL
                color = style.accent_pink
            })
            val label_width = text_paint.measureText(track.name)
            val label_rect = RectF(
                label_point.x - label_width / 2f,
                label_point.y - dp(24f),
                label_point.x + label_width / 2f,
                label_point.y - dp(8f)
            )
            val padded = label_rect.padded_copy(dp(4f))
            if (!label_rect.intersects(0f, 0f, viewport.width, viewport.height) ||
                label_rects.any { RectF.intersects(padded, it) }
            ) {
                return@forEach
            }
            label_rects += padded
            canvas.drawText(track.name, label_point.x, label_point.y - dp(8f), text_paint)
        }
        text_paint.isFakeBoldText = false
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_airport_labels(
        canvas: Canvas,
        viewport: Viewport,
        airports: List<AviationAirportFeature>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AIRPORT_LABEL_MIN_ZOOM) return
        val visible = airports
            .mapNotNull { airport -> aviation_point_to_screen(AviationLayerPoint(airport.lat, airport.lon), viewport)?.let { airport to it } }
            .filter { (_, point) -> point.x in 0f..viewport.width && point.y in 0f..viewport.height }
            .sortedBy { (_, point) -> distance_from_screen_center(point, viewport) }
            .take(if (viewport.zoom >= 10.0) MAX_DRAWN_AIRPORT_LABELS else MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM)

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10f)
        visible.forEach { (airport, point) ->
            val label = airport.ident.ifBlank { airport.name }
            val color = if (airport.military) style.military_gray else style.accent_yellow
            val max_width = dp(if (viewport.zoom >= 10.0) 98f else 62f)
            text_paint.color = color
            val display = ellipsize(label, max_width)
            val width = text_paint.measureText(display) + dp(10f)
            val preferred_left = if (point.x + dp(5f) + width <= viewport.width - dp(2f)) {
                point.x + dp(5f)
            } else {
                point.x - dp(5f) - width
            }
            val left = preferred_left.coerceIn(dp(2f), (viewport.width - width - dp(2f)).coerceAtLeast(dp(2f)))
            val top = (point.y - dp(16f)).coerceIn(dp(2f), viewport.height - dp(22f))
            val rect = RectF(left, top, left + width, top + dp(20f))
            if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return@forEach
            val padded = rect.padded_copy(dp(3f))
            if (label_rects.any { RectF.intersects(padded, it) }) return@forEach
            label_rects += padded
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(style.panel, 165)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
            paint.color = color
            canvas.drawCircle(point.x, point.y, dp(3f), paint)
            canvas.drawText(display, rect.left + dp(5f), rect.bottom - dp(6f), text_paint)
        }
        text_paint.isFakeBoldText = false
    }

    private fun ring_to_screen_points(points: List<AviationLayerPoint>, viewport: Viewport, max_points: Int): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        val step = max(1, points.size / max_points)
        val result = mutableListOf<ScreenPoint>()
        points.forEachIndexed { index, point ->
            if (index % step == 0 || index == points.lastIndex) {
                aviation_point_to_screen(point, viewport)?.let { result += it }
            }
        }
        return result
    }

    private fun aviation_point_to_screen(point: AviationLayerPoint, viewport: Viewport): ScreenPoint? {
        val world = MapProjection.lat_lon_to_world(point.lat, point.lon, viewport.zoom)
        var sx = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
        val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
        while (sx < -world_span / 2f) sx += world_span
        while (sx > viewport.width + world_span / 2f) sx -= world_span
        val sy = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
        if (sx < -viewport.width || sx > viewport.width * 2f || sy < -viewport.height || sy > viewport.height * 2f) return null
        return ScreenPoint(sx, sy)
    }

    private fun distance_from_screen_center(point: ScreenPoint, viewport: Viewport): Float {
        val dx = point.x - viewport.width / 2f
        val dy = point.y - viewport.height / 2f
        return dx * dx + dy * dy
    }

    private fun AviationGeoBounds.center_point(): AviationLayerPoint {
        return AviationLayerPoint((min_lat + max_lat) / 2.0, (min_lon + max_lon) / 2.0)
    }

    private fun RectF.padded_copy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
    }

    private fun with_alpha(color: Int, alpha: Int): Int {
        return android.graphics.Color.argb(
            alpha.coerceIn(0, 255),
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }

    private fun restricted_airspace_stroke(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.PLAIN -> style.accent_orange
            ThemeTreatment.GLASS -> style.accent_pink
            ThemeTreatment.RADAR_GRID -> style.accent_yellow
            ThemeTreatment.DAYLIGHT_CARD -> style.accent_pink
            ThemeTreatment.STORM_BAND -> style.accent_orange
            ThemeTreatment.CRT_SCANLINE -> style.accent_green
        }
    }

    private fun restricted_airspace_fill(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.PLAIN -> style.danger
            ThemeTreatment.GLASS -> style.accent_blue
            ThemeTreatment.RADAR_GRID -> style.danger
            ThemeTreatment.DAYLIGHT_CARD -> style.danger
            ThemeTreatment.STORM_BAND -> style.accent_pink
            ThemeTreatment.CRT_SCANLINE -> style.danger
        }
    }

    private fun selected_restricted_airspace_stroke(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.PLAIN -> style.accent_yellow
            ThemeTreatment.GLASS -> style.accent_yellow
            ThemeTreatment.RADAR_GRID -> style.accent_green
            ThemeTreatment.DAYLIGHT_CARD -> style.accent_orange
            ThemeTreatment.STORM_BAND -> style.accent_yellow
            ThemeTreatment.CRT_SCANLINE -> style.accent_yellow
        }
    }

    private fun selected_restricted_airspace_fill_alpha(style: AviationLayerStyle): Int {
        return when (style.treatment) {
            ThemeTreatment.DAYLIGHT_CARD -> 34
            ThemeTreatment.GLASS -> 36
            else -> 42
        }
    }

    private data class PreparedAirspaceFeature(
        val source: AviationAirspaceFeature,
        val label: String,
        val center: AviationLayerPoint,
        val point_count: Int,
        val rings: List<PreparedAirspaceRing>,
        val interaction_rings: List<PreparedAirspaceRing>
    )

    private data class PreparedAirspaceRing(
        val points: FloatArray,
        val point_count: Int
    )

    private data class SettledLayerCacheKey(
        val snapshot_identity: Int,
        val width: Int,
        val height: Int,
        val zoom: Double,
        val center_x: Double,
        val center_y: Double,
        val visible_bounds: AviationGeoBounds,
        val visibility: AviationLayerVisibility,
        val style: AviationLayerStyle,
        val selected_restricted_airspace_identity: Int?
    )

    private companion object {
        const val TILE_SIZE = 256
        const val MAX_DRAWN_AIRSPACE_FEATURES = 80
        const val MAX_DRAWN_RINGS_PER_FEATURE = 4
        const val MAX_DRAWN_AIRSPACE_POINTS_PER_RING = 180
        const val MAX_DRAWN_AIRSPACE_POINTS_PER_RING_INTERACTION = 42
        const val INTERACTION_LINE_BUFFER_INITIAL_FLOATS = 8192
        const val INTERACTION_LINE_FLUSH_FLOATS = 16384
        const val MAX_TRANSFORMED_CACHE_ZOOM_DELTA = 1.6
        const val MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION = 0.65f
        const val MAX_DRAWN_AIRPORT_LABELS = 36
        const val MAX_DRAWN_AIRPORT_LABELS_LOW_ZOOM = 16
        const val MAX_DRAWN_OCEANIC_TRACKS = 16
        const val MAX_DRAWN_OCEANIC_POINTS = 24
        const val AIRSPACE_LABEL_MIN_ZOOM = 7.2
        const val AIRPORT_LABEL_MIN_ZOOM = 8.4
        const val OCEANIC_TRACK_MIN_ZOOM = 3.0
    }
}
