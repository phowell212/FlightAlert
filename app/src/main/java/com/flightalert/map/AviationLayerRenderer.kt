@file:Suppress(
    "CanBeVal",
    "FunctionName",
    "KotlinConstantConditions",
    "LocalVariableName",
    "ObsoleteSdkInt",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "RedundantQualifierName",
    "SameParameterValue",
    "UNUSED_PARAMETER",
    "UseKtxExtensionFunction",
    "unused"
)

package com.flightalert.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import com.flightalert.TILE_SIZE
import com.flightalert.ThemeTreatment
import com.flightalert.ui.lerp
import com.flightalert.ui.smooth_step
import com.flightalert.ui.with_alpha
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object AviationAirspaceRenderPolicy {
    fun forEachApplicable(
        features: List<AviationAirspaceFeature>,
        visibleBounds: AviationGeoBounds,
        excludedFeature: AviationAirspaceFeature?,
        action: (AviationAirspaceFeature) -> Unit
    ) {
        for (feature in features) {
            if (isApplicable(feature, visibleBounds, excludedFeature)) action(feature)
        }
    }

    fun isApplicable(
        feature: AviationAirspaceFeature,
        visibleBounds: AviationGeoBounds,
        excludedFeature: AviationAirspaceFeature?
    ): Boolean {
        if (feature == excludedFeature ||
            feature.bounds.max_lat < visibleBounds.min_lat ||
            feature.bounds.min_lat > visibleBounds.max_lat
        ) return false
        val rawLongitudeSpan = feature.bounds.max_lon - feature.bounds.min_lon
        val span = if (rawLongitudeSpan <= 180.0) {
            LongitudeSpan(feature.bounds.min_lon, feature.bounds.max_lon)
        } else {
            longitudeSpan(feature)
        }
        return longitudeSpansIntersect(
            span.min,
            span.max,
            visibleBounds.min_lon,
            visibleBounds.max_lon
        )
    }

    fun forEachSourcePolygon(
        feature: AviationAirspaceFeature,
        action: (AviationPolygon) -> Unit
    ) {
        for (polygon in feature.geometry.polygons) action(polygon)
    }

    fun forEachSourceRing(
        polygon: AviationPolygon,
        action: (List<AviationLayerPoint>) -> Unit
    ) {
        for (ring in polygon.all_rings) action(ring)
    }

    fun contains(feature: AviationAirspaceFeature, lat: Double, lon: Double): Boolean {
        val featureAnchorLon = feature.geometry.polygons.first().shell.first().lon
        return feature.geometry.polygons.any { polygon ->
            val polygonAnchorLon = unwrapLongitude(polygon.shell.first().lon, featureAnchorLon)
            pointInsideRing(lat, lon, polygon.shell, polygonAnchorLon) &&
                    polygon.holes.none { hole ->
                        pointInsideRing(lat, lon, hole, polygonAnchorLon)
                    }
        }
    }

    fun boundsArea(feature: AviationAirspaceFeature): Double {
        val latitudeSpan = (feature.bounds.max_lat - feature.bounds.min_lat).coerceAtLeast(0.0)
        val rawLongitudeSpan = (feature.bounds.max_lon - feature.bounds.min_lon).coerceAtLeast(0.0)
        val longitudeWidth = if (rawLongitudeSpan <= 180.0) {
            rawLongitudeSpan
        } else {
            val span = longitudeSpan(feature)
            (span.max - span.min).coerceIn(0.0, 360.0)
        }
        return latitudeSpan * longitudeWidth
    }

    fun unwrapLongitude(lon: Double, referenceLon: Double): Double {
        var unwrapped = lon
        while (unwrapped - referenceLon > 180.0) unwrapped -= 360.0
        while (unwrapped - referenceLon < -180.0) unwrapped += 360.0
        return unwrapped
    }

    fun firstWrappedShift(
        baseLeft: Float,
        baseRight: Float,
        extendedLeft: Float,
        extendedRight: Float,
        worldSpan: Float,
        maxCopies: Int = 8
    ): Float {
        if (worldSpan <= 0f || !worldSpan.isFinite() || maxCopies <= 0) return 0f
        var shift = 0f
        var copies = 0
        while (baseLeft + shift > extendedRight && copies++ < maxCopies) shift -= worldSpan
        copies = 0
        while (baseRight + shift < extendedLeft && copies++ < maxCopies) shift += worldSpan
        return shift
    }

    private fun pointInsideRing(
        lat: Double,
        lon: Double,
        ring: List<AviationLayerPoint>,
        anchorLon: Double
    ): Boolean {
        if (ring.size < 3) return false
        val first = ring.first()
        val firstLon = unwrapLongitude(first.lon, anchorLon)
        val queryLon = unwrapLongitude(lon, firstLon)
        var inside = false
        var previousLat = first.lat
        var previousLon = firstLon
        for (index in 1 until ring.size) {
            val current = ring[index]
            val currentLon = unwrapLongitude(current.lon, previousLon)
            val crossesLat = (current.lat > lat) != (previousLat > lat)
            if (crossesLat) {
                val lonAtLat = (previousLon - currentLon) *
                        (lat - current.lat) /
                        (previousLat - current.lat) +
                        currentLon
                if (queryLon < lonAtLat) inside = !inside
            }
            previousLat = current.lat
            previousLon = currentLon
        }
        return inside
    }

    internal fun longitudeSpansIntersect(
        firstMin: Double,
        firstMax: Double,
        secondMin: Double,
        secondMax: Double
    ): Boolean {
        if (firstMax - firstMin >= 360.0 || secondMax - secondMin >= 360.0) return true
        if (secondMin > secondMax) {
            return longitudeSpansIntersect(firstMin, firstMax, secondMin, 180.0) ||
                    longitudeSpansIntersect(firstMin, firstMax, -180.0, secondMax)
        }
        for (worldCopy in -2..2) {
            val shift = worldCopy * 360.0
            if (firstMax + shift >= secondMin && firstMin + shift <= secondMax) return true
        }
        return false
    }

    private fun longitudeSpan(feature: AviationAirspaceFeature): LongitudeSpan {
        val featureAnchorLon = feature.geometry.polygons.first().shell.first().lon
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (polygon in feature.geometry.polygons) {
            val polygonAnchorLon = unwrapLongitude(polygon.shell.first().lon, featureAnchorLon)
            for (ring in polygon.all_rings) {
                var previousLon = polygonAnchorLon
                for (point in ring) {
                    val currentLon = unwrapLongitude(point.lon, previousLon)
                    minLon = min(minLon, currentLon)
                    maxLon = max(maxLon, currentLon)
                    previousLon = currentLon
                }
            }
        }
        return LongitudeSpan(minLon, maxLon)
    }

    private data class LongitudeSpan(val min: Double, val max: Double)
}

internal class AviationAirspacePreparationTracker {
    private var restrictedSource: List<AviationAirspaceFeature>? = null
    private var atcSource: List<AviationAirspaceFeature>? = null

    fun shouldPrepareRestricted(
        source: List<AviationAirspaceFeature>,
        enabled: Boolean
    ): Boolean {
        if (!enabled || restrictedSource === source) return false
        restrictedSource = source
        return true
    }

    fun shouldPrepareAtc(
        source: List<AviationAirspaceFeature>,
        enabled: Boolean
    ): Boolean {
        if (!enabled || atcSource === source) return false
        atcSource = source
        return true
    }
}

internal class AviationLayerRenderer(
    private val paint: Paint,
    private val stroke_paint: Paint,
    private val text_paint: Paint,
    private val path: Path,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val ellipsize: (String, Float) -> String,
    private val now_epoch_ms: () -> Long = System::currentTimeMillis
) {
    private val airspace_preparation_tracker = AviationAirspacePreparationTracker()
    private val selection_hit_collector = AviationSelectionHitCollector(
        lat_lon_to_world = MapProjection::lat_lon_to_world,
        world_to_lat_lon = MapProjection::world_to_lat_lon
    )
    private var prepared_restricted_airspaces: List<PreparedAirspaceFeature> = emptyList()
    private var prepared_atc_boundaries: List<PreparedAirspaceFeature> = emptyList()
    private val layer_label_rects = ArrayList<RectF>(16)
    private var settled_cache_bitmap: Bitmap? = null
    private var settled_cache_canvas: Canvas? = null
    private var settled_cache_key: SettledLayerCacheKey? = null
    private val settled_cache_matrix = Matrix()

    fun draw_layers(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection? = null,
        interaction_active: Boolean = false
    ) {
        val draw_time_epoch_ms = if (visibility.oceanic_tracks_enabled) now_epoch_ms() else 0L
        prepare_snapshot_if_needed(snapshot, visibility)
        if (interaction_active && draw_transformed_settled_cache(
                canvas,
                viewport,
                snapshot,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) {
            return
        }
        if (!interaction_active && draw_settled_cache_if_current(
                canvas,
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) {
            return
        }
        if (!interaction_active && draw_into_settled_cache(
                canvas,
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) {
            return
        }
        draw_layers_direct(
            canvas = canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selection = selection,
            interaction_active = interaction_active,
            draw_time_epoch_ms = draw_time_epoch_ms
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
        now_epoch_ms: Long = this.now_epoch_ms()
    ): AviationSelectionKey? = selection_hit_collector.selection_key_at(
        x = x,
        y = y,
        density = density,
        snapshot = snapshot,
        viewport = viewport,
        visible_bounds = visible_bounds,
        visibility = visibility,
        now_epoch_ms = now_epoch_ms
    )

    private fun draw_settled_cache_if_current(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (key != settled_layer_cache_key(
                viewport,
                snapshot,
                visible_bounds,
                visibility,
                style,
                selection,
                draw_time_epoch_ms
            )
        ) return false
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return true
    }

    private fun draw_transformed_settled_cache(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): Boolean {
        val bitmap = settled_cache_bitmap ?: return false
        val key = settled_cache_key ?: return false
        if (bitmap.isRecycled) return false
        if (key.snapshot_identity != System.identityHashCode(snapshot) ||
            key.width != viewport.width.toInt() ||
            key.height != viewport.height.toInt() ||
            key.visibility != visibility ||
            key.style != style ||
            key.nat_temporal_state_token != nat_temporal_state_token(
                snapshot,
                visibility,
                draw_time_epoch_ms
            ) ||
            key.selection_key != selection?.key
        ) {
            return false
        }
        val zoom_delta = viewport.zoom - key.zoom
        if (abs(zoom_delta) > MAX_TRANSFORMED_CACHE_ZOOM_DELTA) return false
        val scale = 2.0.pow(zoom_delta).toFloat()
        if (scale <= 0f || !scale.isFinite()) return false
        val translation_x =
            (key.center_x * scale - viewport.center_x + viewport.width / 2.0 - key.width * scale / 2.0).toFloat()
        val translation_y =
            (key.center_y * scale - viewport.center_y + viewport.height / 2.0 - key.height * scale / 2.0).toFloat()
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
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): Boolean {
        val layer_canvas = settled_cache_canvas_for(viewport) ?: return false
        val bitmap = settled_cache_bitmap ?: return false
        layer_canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        draw_layers_direct(
            canvas = layer_canvas,
            viewport = viewport,
            snapshot = snapshot,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            selection = selection,
            interaction_active = false,
            draw_time_epoch_ms = draw_time_epoch_ms
        )
        settled_cache_key = settled_layer_cache_key(
            viewport,
            snapshot,
            visible_bounds,
            visibility,
            style,
            selection,
            draw_time_epoch_ms
        )
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
            val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
        selection: AviationSelection?,
        draw_time_epoch_ms: Long
    ): SettledLayerCacheKey {
        val binding = AviationLayerRenderCachePolicy.binding(snapshot, selection)
        return SettledLayerCacheKey(
            snapshot_identity = binding.snapshot_identity,
            width = viewport.width.toInt(),
            height = viewport.height.toInt(),
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            visible_bounds = visible_bounds,
            visibility = visibility,
            style = style,
            nat_temporal_state_token = nat_temporal_state_token(
                snapshot,
                visibility,
                draw_time_epoch_ms
            ),
            selection_key = binding.selection_key
        )
    }

    private fun nat_temporal_state_token(
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility,
        draw_time_epoch_ms: Long
    ): Long = if (visibility.oceanic_tracks_enabled) {
        AviationNatRenderPolicy.temporal_state_token(snapshot.oceanic_tracks, draw_time_epoch_ms)
    } else {
        0L
    }

    private fun draw_layers_direct(
        canvas: Canvas,
        viewport: Viewport,
        snapshot: AviationLayerSnapshot,
        visible_bounds: AviationGeoBounds,
        visibility: AviationLayerVisibility,
        style: AviationLayerStyle,
        selection: AviationSelection?,
        interaction_active: Boolean,
        draw_time_epoch_ms: Long
    ) {
        layer_label_rects.clear()
        selection_hit_collector.begin_draw(snapshot, viewport, visible_bounds, visibility)
        val selected_restricted_airspace =
            (selection as? AviationSelection.SpecialUse)?.feature
        if (visibility.restricted_airspaces_enabled) {
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_restricted_airspaces,
                visible_bounds = visible_bounds,
                excluded_feature = selected_restricted_airspace,
                stroke = restricted_airspace_stroke(style),
                fill = restricted_airspace_fill(style),
                label_rects = layer_label_rects,
                style = style,
                restricted = true,
                interaction_active = interaction_active
            )
            selected_restricted_airspace
                ?.takeIf { selected ->
                    AviationAirspaceRenderPolicy.isApplicable(selected, visible_bounds, null)
                }
                ?.let { selected ->
                    val selected_prepared =
                        prepared_restricted_airspaces.firstOrNull { it.source == selected }
                            ?: prepare_airspace_feature(selected)
                            ?: return@let
                    draw_selected_airspace(canvas, viewport, selected, style)
                    if (viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                        draw_airspace_label(
                            canvas,
                            viewport,
                            selected_prepared,
                            layer_label_rects,
                            style
                        )
                    }
                }
        }
        if (visibility.atc_boundaries_enabled) {
            val focused_atc_feature = focused_airspace_feature(
                viewport = viewport,
                features = prepared_atc_boundaries,
                visible_bounds = visible_bounds
            )
            draw_airspace_layer(
                canvas = canvas,
                viewport = viewport,
                features = prepared_atc_boundaries,
                visible_bounds = visible_bounds,
                excluded_feature = null,
                stroke = style.accent_blue,
                fill = style.accent_blue,
                label_rects = layer_label_rects,
                style = style,
                restricted = false,
                focused_feature = focused_atc_feature,
                interaction_active = interaction_active
            )
            (selection as? AviationSelection.AtcBoundary)
                ?.feature
                ?.takeIf { selected ->
                    AviationAirspaceRenderPolicy.isApplicable(selected, visible_bounds, null)
                }
                ?.let { selected ->
                    draw_selected_airspace_boundary(
                        canvas = canvas,
                        viewport = viewport,
                        feature = selected,
                        halo = style.panel,
                        core = style.accent_blue
                    )
                }
        }
        if (visibility.oceanic_tracks_enabled) {
            val active_tracks = AviationNatRenderPolicy.select_active_tracks(
                tracks = snapshot.oceanic_tracks,
                visible_bounds = visible_bounds,
                limit = snapshot.oceanic_tracks.size,
                now_epoch_ms = draw_time_epoch_ms
            )
            draw_oceanic_tracks(
                canvas = canvas,
                viewport = viewport,
                tracks = active_tracks,
                label_rects = layer_label_rects,
                style = style
            )
            (selection as? AviationSelection.OceanicTrack)
                ?.track
                ?.takeIf(active_tracks::contains)
                ?.let { selected ->
                    draw_selected_oceanic_track(canvas, viewport, selected, style)
                }
        }
        if (visibility.airport_labels_enabled) {
            draw_airport_labels(
                canvas = canvas,
                viewport = viewport,
                airports = snapshot.airports,
                label_rects = layer_label_rects,
                style = style
            )
            (selection as? AviationSelection.Airport)?.airport?.let { selected ->
                draw_selected_airport(canvas, viewport, selected, style)
            }
        }
    }

    private fun prepare_snapshot_if_needed(
        snapshot: AviationLayerSnapshot,
        visibility: AviationLayerVisibility
    ) {
        if (airspace_preparation_tracker.shouldPrepareRestricted(
                snapshot.restricted_airspaces,
                visibility.restricted_airspaces_enabled
            )
        ) {
            prepared_restricted_airspaces = prepare_airspace_features(snapshot.restricted_airspaces)
        }
        if (airspace_preparation_tracker.shouldPrepareAtc(
                snapshot.atc_boundaries,
                visibility.atc_boundaries_enabled
            )
        ) {
            prepared_atc_boundaries = prepare_airspace_features(snapshot.atc_boundaries)
        }
    }

    private fun prepare_airspace_features(features: List<AviationAirspaceFeature>): List<PreparedAirspaceFeature> {
        return features
            .mapNotNull(::prepare_airspace_feature)
            .sortedBy { it.point_count }
    }

    private fun prepare_airspace_feature(feature: AviationAirspaceFeature): PreparedAirspaceFeature? {
        val polygons = ArrayList<PreparedAirspacePolygon>(feature.geometry.polygons.size)
        val feature_anchor_lon = feature.geometry.polygons.first().shell.first().lon
        AviationAirspaceRenderPolicy.forEachSourcePolygon(feature) { polygon ->
            prepare_airspace_polygon(polygon, feature_anchor_lon)?.let(polygons::add)
        }
        if (polygons.isEmpty()) return null
        val center_geo = MapProjection.world_to_lat_lon(
            x = (polygons.minOf { it.min_x } + polygons.maxOf { it.max_x }) / 2.0,
            y = (polygons.minOf { it.min_y } + polygons.maxOf { it.max_y }) / 2.0,
            zoom = 0.0
        )
        val min_lon = polygons.minOf { it.min_x }.toDouble() / TILE_SIZE * 360.0 - 180.0
        val max_lon = polygons.maxOf { it.max_x }.toDouble() / TILE_SIZE * 360.0 - 180.0
        return PreparedAirspaceFeature(
            source = feature,
            map_text = feature.map_text,
            center = AviationLayerPoint(center_geo.lat, center_geo.lon),
            point_count = polygons.sumOf { it.point_count },
            min_unwrapped_lon = min_lon,
            max_unwrapped_lon = max_lon,
            polygons = polygons
        )
    }

    private fun prepare_airspace_polygon(
        polygon: AviationPolygon,
        feature_anchor_lon: Double
    ): PreparedAirspacePolygon? {
        val world_path = Path()
        world_path.fillType = Path.FillType.EVEN_ODD
        var point_count = 0
        var min_x = Float.POSITIVE_INFINITY
        var max_x = Float.NEGATIVE_INFINITY
        var min_y = Float.POSITIVE_INFINITY
        var max_y = Float.NEGATIVE_INFINITY
        val polygon_anchor_lon = AviationAirspaceRenderPolicy.unwrapLongitude(
            polygon.shell.first().lon,
            feature_anchor_lon
        )
        AviationAirspaceRenderPolicy.forEachSourceRing(polygon) { ring ->
            var ring_point_index = 0
            var previous_lon = polygon_anchor_lon
            ring.forEach { point ->
                val unwrapped_lon = AviationAirspaceRenderPolicy.unwrapLongitude(
                    point.lon,
                    previous_lon
                )
                val world = MapProjection.lat_lon_to_world(point.lat, unwrapped_lon, 0.0)
                val x = world.x.toFloat()
                val y = world.y.toFloat()
                if (ring_point_index == 0) world_path.moveTo(x, y) else world_path.lineTo(x, y)
                min_x = min(min_x, x)
                max_x = max(max_x, x)
                min_y = min(min_y, y)
                max_y = max(max_y, y)
                ring_point_index++
                point_count++
                previous_lon = unwrapped_lon
            }
            world_path.close()
        }
        if (point_count < 3) return null
        return PreparedAirspacePolygon(
            point_count = point_count,
            path = world_path,
            min_x = min_x,
            max_x = max_x,
            min_y = min_y,
            max_y = max_y
        )
    }

    private fun draw_airspace_layer(
        canvas: Canvas,
        viewport: Viewport,
        features: List<PreparedAirspaceFeature>,
        visible_bounds: AviationGeoBounds,
        excluded_feature: AviationAirspaceFeature?,
        stroke: Int,
        fill: Int,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle,
        restricted: Boolean,
        focused_feature: PreparedAirspaceFeature? = null,
        interaction_active: Boolean
    ) {
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        val low_zoom_emphasis = layer_low_zoom_emphasis(viewport.zoom, restricted)
        val base_stroke_width_dp = when {
            interaction_active && restricted -> 1.25f
            interaction_active -> 1.0f
            restricted && viewport.zoom >= 8.0 -> 2.0f
            viewport.zoom >= 8.0 -> 1.6f
            restricted -> 1.35f
            else -> 1.1f
        }
        val min_stroke_width_dp = if (restricted) 0.55f else 0.42f
        stroke_paint.strokeWidth =
            dp(lerp(min_stroke_width_dp, base_stroke_width_dp, low_zoom_emphasis))
        val raw_stroke_alpha = when {
            interaction_active && viewport.zoom >= 8.0 -> 190
            interaction_active -> 150
            viewport.zoom >= 8.0 -> 220
            else -> 170
        }
        val min_stroke_alpha_scale = if (restricted) 0.40f else 0.26f
        val stroke_alpha_scale = lerp(min_stroke_alpha_scale, 1f, low_zoom_emphasis)
        val base_stroke_alpha = (raw_stroke_alpha * stroke_alpha_scale).toInt().coerceIn(24, 235)
        paint.style = Paint.Style.FILL
        val fill_zoom_emphasis = smooth_step(5.3f, 8.5f, viewport.zoom.toFloat())
        val raw_fill_alpha =
            if (restricted && viewport.zoom >= 8.5) 28 else if (viewport.zoom >= 8.5) 18 else 10
        val base_fill_alpha = (raw_fill_alpha * fill_zoom_emphasis).toInt()

        for (feature in features) {
            if (!prepared_airspace_is_applicable(
                    feature,
                    visible_bounds,
                    excluded_feature
                )
            ) continue
            val is_focused = focused_feature == null || focused_feature === feature
            val fill_alpha = when {
                interaction_active -> 0
                restricted -> base_fill_alpha
                focused_feature == null -> 0
                is_focused -> base_fill_alpha
                else -> 0
            }
            val stroke_alpha = when {
                restricted -> base_stroke_alpha
                focused_feature == null -> (base_stroke_alpha * 0.72f).toInt()
                is_focused -> base_stroke_alpha
                else -> (base_stroke_alpha * 0.58f).toInt()
            }
            paint.color = with_alpha(fill, fill_alpha)
            stroke_paint.color = with_alpha(stroke, stroke_alpha)
            feature.polygons.forEach { polygon ->
                draw_prepared_airspace_polygon(
                    canvas = canvas,
                    viewport = viewport,
                    polygon = polygon,
                    fill_paint = paint.takeIf { fill_alpha > 0 },
                    outline_paint = stroke_paint
                )
            }
            if (!interaction_active && viewport.zoom >= AIRSPACE_LABEL_MIN_ZOOM) {
                val accepted_label = draw_airspace_label(
                    canvas,
                    viewport,
                    feature,
                    label_rects,
                    style
                )
                if (!restricted && accepted_label != null) {
                    selection_hit_collector.accept_atc_label(
                        feature.source,
                        accepted_label.point,
                        accepted_label.label_bounds
                    )
                }
            }
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun layer_low_zoom_emphasis(zoom: Double, restricted: Boolean): Float {
        val start = if (restricted) 3.2f else 3.7f
        val end = if (restricted) 7.0f else 7.4f
        return smooth_step(start, end, zoom.toFloat())
    }

    private fun focused_airspace_feature(
        viewport: Viewport,
        features: List<PreparedAirspaceFeature>,
        visible_bounds: AviationGeoBounds
    ): PreparedAirspaceFeature? {
        val center =
            MapProjection.world_to_lat_lon(viewport.center_x, viewport.center_y, viewport.zoom)
        val point = AviationLayerPoint(center.lat, center.lon)
        return features.firstOrNull { feature ->
            prepared_airspace_is_applicable(feature, visible_bounds, null) &&
                    point_inside_airspace(point, feature.source)
        }
    }

    private fun prepared_airspace_is_applicable(
        feature: PreparedAirspaceFeature,
        visible_bounds: AviationGeoBounds,
        excluded_feature: AviationAirspaceFeature?
    ): Boolean {
        if (feature.source == excluded_feature ||
            feature.source.bounds.max_lat < visible_bounds.min_lat ||
            feature.source.bounds.min_lat > visible_bounds.max_lat
        ) return false
        return AviationAirspaceRenderPolicy.longitudeSpansIntersect(
            feature.min_unwrapped_lon,
            feature.max_unwrapped_lon,
            visible_bounds.min_lon,
            visible_bounds.max_lon
        )
    }

    private fun point_inside_airspace(
        point: AviationLayerPoint,
        feature: AviationAirspaceFeature
    ): Boolean = AviationAirspaceRenderPolicy.contains(feature, point.lat, point.lon)

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
        paint.color = with_alpha(
            restricted_airspace_fill(style),
            selected_restricted_airspace_fill_alpha(style)
        )
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(3.0f)
        stroke_paint.color = with_alpha(selected_restricted_airspace_stroke(style), 235)
        prepared.polygons.forEach { polygon ->
            draw_prepared_airspace_polygon(canvas, viewport, polygon, paint, stroke_paint)
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_selected_airspace_boundary(
        canvas: Canvas,
        viewport: Viewport,
        feature: AviationAirspaceFeature,
        halo: Int,
        core: Int
    ) {
        val prepared = prepared_atc_boundaries.firstOrNull { it.source == feature }
            ?: prepare_airspace_feature(feature)
            ?: return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(5.2f)
        stroke_paint.color = with_alpha(halo, 230)
        prepared.polygons.forEach { polygon ->
            draw_prepared_airspace_polygon(canvas, viewport, polygon, null, stroke_paint)
        }
        stroke_paint.strokeWidth = dp(2.4f)
        stroke_paint.color = with_alpha(core, 255)
        prepared.polygons.forEach { polygon ->
            draw_prepared_airspace_polygon(canvas, viewport, polygon, null, stroke_paint)
        }
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_prepared_airspace_polygon(
        canvas: Canvas,
        viewport: Viewport,
        polygon: PreparedAirspacePolygon,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        val scale = 2.0.pow(viewport.zoom).toFloat()
        if (scale <= 0f || !scale.isFinite()) return
        val world_span = TILE_SIZE * scale
        val screen_offset_x = (-viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_offset_y = (-viewport.center_y + viewport.height / 2.0).toFloat()
        draw_prepared_airspace_polygon_path(
            canvas = canvas,
            viewport = viewport,
            polygon = polygon,
            scale = scale,
            world_span = world_span,
            screen_offset_x = screen_offset_x,
            screen_offset_y = screen_offset_y,
            fill_paint = fill_paint,
            outline_paint = outline_paint
        )
    }

    private fun draw_prepared_airspace_polygon_path(
        canvas: Canvas,
        viewport: Viewport,
        polygon: PreparedAirspacePolygon,
        scale: Float,
        world_span: Float,
        screen_offset_x: Float,
        screen_offset_y: Float,
        fill_paint: Paint?,
        outline_paint: Paint
    ) {
        if (polygon.point_count < 3 || scale <= 0f || !scale.isFinite()) return
        val extended_left = -viewport.width * 0.5f
        val extended_right = viewport.width * 1.5f
        val extended_top = -viewport.height * 0.5f
        val extended_bottom = viewport.height * 1.5f
        val screen_top = polygon.min_y * scale + screen_offset_y
        val screen_bottom = polygon.max_y * scale + screen_offset_y
        if (screen_bottom < extended_top || screen_top > extended_bottom) return

        val base_left = polygon.min_x * scale + screen_offset_x
        val base_right = polygon.max_x * scale + screen_offset_x
        var shift_x = AviationAirspaceRenderPolicy.firstWrappedShift(
            baseLeft = base_left,
            baseRight = base_right,
            extendedLeft = extended_left,
            extendedRight = extended_right,
            worldSpan = world_span,
            maxCopies = MAX_WRAPPED_RING_COPIES
        )
        var guard = 0
        val original_stroke_width = outline_paint.strokeWidth
        outline_paint.strokeWidth = original_stroke_width / scale
        try {
            while (base_left + shift_x <= extended_right && guard++ < MAX_WRAPPED_RING_COPIES) {
                if (base_right + shift_x >= extended_left) {
                    canvas.withTranslation(screen_offset_x + shift_x, screen_offset_y) {
                        scale(scale, scale)
                        if (fill_paint != null) drawPath(polygon.path, fill_paint)
                        drawPath(polygon.path, outline_paint)
                    }
                }
                shift_x += world_span
            }
        } finally {
            outline_paint.strokeWidth = original_stroke_width
        }
    }

    private fun draw_airspace_label(
        canvas: Canvas,
        viewport: Viewport,
        feature: PreparedAirspaceFeature,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ): AviationAcceptedLabelGeometry? {
        val screen = project_aviation_point_to_screen(
            feature.center,
            viewport,
            MapProjection::lat_lon_to_world
        ) ?: return null
        if (screen.x !in 0f..viewport.width || screen.y !in 0f..viewport.height) return null
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.isFakeBoldText = true
        text_paint.textSize =
            sp(if (feature.source.type.equals("R", ignoreCase = true)) 11f else 10f)
        text_paint.color = with_alpha(style.text, 224)
        val label = prepare_sourced_map_label(
            text = feature.map_text,
            primary_text_size = text_paint.textSize,
            max_width = dp(142f),
        )
        val width = label.width + dp(14f)
        val anchor_baseline = screen.y + dp(3f) - dp(7f)
        val rect = if (label.english == null) {
            RectF(
                screen.x - width / 2f,
                screen.y - dp(20f),
                screen.x + width / 2f,
                screen.y + dp(3f),
            )
        } else {
            val height = max(dp(23f), label.collision_height)
            val center_y = anchor_baseline + label.collision_center_offset
            RectF(
                screen.x - width / 2f,
                center_y - height / 2f,
                screen.x + width / 2f,
                center_y + height / 2f,
            )
        }
        if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return null
        val padded = rect.padded_copy(dp(3f))
        if (label_rects.any { RectF.intersects(padded, it) }) return null
        label_rects += padded
        paint.style = Paint.Style.FILL
        paint.color = with_alpha(style.panel, 184)
        canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
        draw_sourced_map_label(canvas, label, rect.centerX(), anchor_baseline)
        text_paint.isFakeBoldText = false
        return AviationAcceptedLabelGeometry(
            point = screen,
            label_bounds = AviationScreenRect(rect.left, rect.top, rect.right, rect.bottom)
        )
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

        tracks.forEach { track ->
            var track_label_point: ScreenPoint? = null
            track.drawable_segments.forEach segment_loop@{ segment ->
                val points = ring_to_screen_points(segment, viewport)
                if (points.size < 2) return@segment_loop
                path.reset()
                points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, stroke_paint)
                if (track_label_point == null) {
                    track_label_point = points.getOrNull(points.size / 2)
                }
            }
            val label_point = track_label_point ?: return@forEach
            canvas.drawCircle(label_point.x, label_point.y, dp(3f), paint.apply {
                this.style = Paint.Style.FILL
                color = style.accent_pink
            })
            val label = prepare_sourced_map_label(
                text = track.map_text,
                primary_text_size = text_paint.textSize,
                max_width = null,
            )
            val anchor_baseline = label_point.y - dp(8f)
            val label_rect = if (label.english == null) {
                RectF(
                    label_point.x - label.width / 2f,
                    label_point.y - dp(24f),
                    label_point.x + label.width / 2f,
                    label_point.y - dp(8f),
                )
            } else {
                val center_y = anchor_baseline + label.collision_center_offset
                RectF(
                    label_point.x - label.width / 2f,
                    center_y - label.collision_height / 2f,
                    label_point.x + label.width / 2f,
                    center_y + label.collision_height / 2f,
                )
            }
            val padded = label_rect.padded_copy(dp(4f))
            if (!label_rect.intersects(0f, 0f, viewport.width, viewport.height) ||
                label_rects.any { RectF.intersects(padded, it) }
            ) {
                return@forEach
            }
            label_rects += padded
            draw_sourced_map_label(canvas, label, label_point.x, anchor_baseline)
        }
        text_paint.isFakeBoldText = false
        text_paint.textSkewX = 0f
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_selected_oceanic_track(
        canvas: Canvas,
        viewport: Viewport,
        track: AviationOceanicTrack,
        style: AviationLayerStyle
    ) {
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeCap = Paint.Cap.ROUND
        stroke_paint.strokeJoin = Paint.Join.ROUND
        stroke_paint.strokeWidth = dp(6f)
        stroke_paint.color = with_alpha(style.panel, 235)
        draw_oceanic_track_segments(canvas, viewport, track, stroke_paint)
        stroke_paint.strokeWidth = dp(3.2f)
        stroke_paint.color = with_alpha(style.accent_pink, 255)
        draw_oceanic_track_segments(canvas, viewport, track, stroke_paint)
        stroke_paint.strokeCap = Paint.Cap.BUTT
        stroke_paint.strokeJoin = Paint.Join.MITER
    }

    private fun draw_oceanic_track_segments(
        canvas: Canvas,
        viewport: Viewport,
        track: AviationOceanicTrack,
        segment_paint: Paint
    ) {
        track.drawable_segments.forEach { segment ->
            val points = ring_to_screen_points(segment, viewport)
            if (points.size < 2) return@forEach
            path.reset()
            points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            canvas.drawPath(path, segment_paint)
        }
    }

    private fun draw_airport_labels(
        canvas: Canvas,
        viewport: Viewport,
        airports: List<AviationAirportFeature>,
        label_rects: MutableList<RectF>,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AVIATION_AIRPORT_LABEL_MIN_ZOOM) return
        val visible = airports
            .mapNotNull { airport ->
                project_aviation_point_to_screen(
                    AviationLayerPoint(airport.lat, airport.lon),
                    viewport,
                    MapProjection::lat_lon_to_world
                )?.let { airport to it }
            }
            .filter { (_, point) -> point.x in 0f..viewport.width && point.y in 0f..viewport.height }
            .sortedBy { (_, point) -> distance_from_screen_center(point, viewport) }

        text_paint.textAlign = Paint.Align.LEFT
        text_paint.isFakeBoldText = true
        text_paint.textSize = sp(10f)
        visible.forEach { (airport, point) ->
            val color = if (airport.military) style.military_gray else style.accent_yellow
            val max_width = dp(if (viewport.zoom >= 10.0) 98f else 62f)
            text_paint.color = color
            val label = prepare_sourced_map_label(
                text = airport.map_text,
                primary_text_size = text_paint.textSize,
                max_width = max_width,
            )
            val width = label.width + dp(10f)
            val preferred_left = if (point.x + dp(5f) + width <= viewport.width - dp(2f)) {
                point.x + dp(5f)
            } else {
                point.x - dp(5f) - width
            }
            val left = preferred_left.coerceIn(
                dp(2f),
                (viewport.width - width - dp(2f)).coerceAtLeast(dp(2f))
            )
            val height = if (label.english == null) dp(20f) else max(dp(20f), label.collision_height)
            val minimum_top = dp(2f)
            val maximum_top = (viewport.height - height - dp(2f)).coerceAtLeast(minimum_top)
            val top: Float
            val anchor_baseline: Float
            if (label.english == null) {
                top = (point.y - dp(16f)).coerceIn(minimum_top, maximum_top)
                anchor_baseline = top + dp(14f)
            } else {
                val desired_anchor_baseline = point.y - dp(2f)
                val desired_center = desired_anchor_baseline + label.collision_center_offset
                top = (desired_center - height / 2f).coerceIn(minimum_top, maximum_top)
                anchor_baseline = top + height / 2f - label.collision_center_offset
            }
            val rect = RectF(left, top, left + width, top + height)
            if (!rect.intersects(0f, 0f, viewport.width, viewport.height)) return@forEach
            val padded = rect.padded_copy(dp(3f))
            if (label_rects.any { RectF.intersects(padded, it) }) return@forEach
            label_rects += padded
            paint.style = Paint.Style.FILL
            paint.color = with_alpha(style.panel, 165)
            canvas.drawRoundRect(rect, dp(4f), dp(4f), paint)
            paint.color = color
            canvas.drawCircle(point.x, point.y, dp(3f), paint)
            draw_sourced_map_label(canvas, label, rect.left + dp(5f), anchor_baseline)
            selection_hit_collector.accept_airport_marker(
                airport,
                point,
                AviationScreenRect(rect.left, rect.top, rect.right, rect.bottom)
            )
        }
        text_paint.isFakeBoldText = false
        text_paint.textSkewX = 0f
    }

    private fun prepare_sourced_map_label(
        text: SourcedMapText,
        primary_text_size: Float,
        max_width: Float?,
    ): PreparedSourcedMapLabel {
        val presentation = SourcedMapTextPresentation.plan(text, primary_text_size)
        fun prepare(line: SourcedMapTextLinePlan): PreparedSourcedMapLabelLine {
            text_paint.textSize = line.textSize
            text_paint.textSkewX = if (line.forceItalic) {
                SourcedMapTextPresentation.forcedItalicSkewX
            } else {
                0f
            }
            val display = max_width?.let { ellipsize(line.text, it) } ?: line.text
            return PreparedSourcedMapLabelLine(
                text = display,
                text_size = line.textSize,
                baseline_offset = line.baselineOffset,
                force_italic = line.forceItalic,
                width = text_paint.measureText(display),
            )
        }
        val primary = prepare(presentation.primary)
        val english = presentation.english?.let(::prepare)
        text_paint.textSize = presentation.primary.textSize
        text_paint.textSkewX = 0f
        return PreparedSourcedMapLabel(
            primary = primary,
            english = english,
            width = max(primary.width, english?.width ?: 0f),
            collision_height = presentation.collisionHeightEm * primary_text_size,
            collision_center_offset = presentation.collisionCenterOffset,
        )
    }

    private fun draw_sourced_map_label(
        canvas: Canvas,
        label: PreparedSourcedMapLabel,
        x: Float,
        anchor_baseline: Float,
    ) {
        fun draw(line: PreparedSourcedMapLabelLine) {
            text_paint.textSize = line.text_size
            text_paint.textSkewX = if (line.force_italic) {
                SourcedMapTextPresentation.forcedItalicSkewX
            } else {
                0f
            }
            canvas.drawText(line.text, x, anchor_baseline + line.baseline_offset, text_paint)
        }
        draw(label.primary)
        label.english?.let(::draw)
        text_paint.textSize = label.primary.text_size
        text_paint.textSkewX = 0f
    }

    private fun draw_selected_airport(
        canvas: Canvas,
        viewport: Viewport,
        airport: AviationAirportFeature,
        style: AviationLayerStyle
    ) {
        if (viewport.zoom < AVIATION_AIRPORT_LABEL_MIN_ZOOM) return
        val point = project_aviation_point_to_screen(
            AviationLayerPoint(airport.lat, airport.lon),
            viewport,
            MapProjection::lat_lon_to_world
        ) ?: return
        if (point.x !in 0f..viewport.width || point.y !in 0f..viewport.height) return
        stroke_paint.style = Paint.Style.STROKE
        stroke_paint.strokeWidth = dp(2.4f)
        stroke_paint.color = with_alpha(
            if (airport.military) style.military_gray else style.accent_yellow,
            255
        )
        canvas.drawCircle(point.x, point.y, dp(8f), stroke_paint)
    }

    private fun ring_to_screen_points(
        points: List<AviationLayerPoint>,
        viewport: Viewport
    ): List<ScreenPoint> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<ScreenPoint>(points.size)
        val world_span = TILE_SIZE * 2.0.pow(viewport.zoom)
        points.forEach { point ->
            project_aviation_source_point_to_screen(
                point,
                viewport,
                MapProjection::lat_lon_to_world
            )?.let { projected ->
                var x = projected.x.toDouble()
                val previous_x = result.lastOrNull()?.x?.toDouble()
                if (previous_x != null && world_span.isFinite() && world_span > 0.0) {
                    while (x - previous_x > world_span / 2.0) x -= world_span
                    while (x - previous_x < -world_span / 2.0) x += world_span
                }
                result += ScreenPoint(x.toFloat(), projected.y)
            }
        }
        return result
    }

    private fun distance_from_screen_center(point: ScreenPoint, viewport: Viewport): Float {
        val dx = point.x - viewport.width / 2f
        val dy = point.y - viewport.height / 2f
        return dx * dx + dy * dy
    }

    private fun RectF.padded_copy(padding: Float): RectF {
        return RectF(left - padding, top - padding, right + padding, bottom + padding)
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
        val map_text: SourcedMapText,
        val center: AviationLayerPoint,
        val point_count: Int,
        val min_unwrapped_lon: Double,
        val max_unwrapped_lon: Double,
        val polygons: List<PreparedAirspacePolygon>
    )

    private data class PreparedSourcedMapLabelLine(
        val text: String,
        val text_size: Float,
        val baseline_offset: Float,
        val force_italic: Boolean,
        val width: Float,
    )

    private data class PreparedSourcedMapLabel(
        val primary: PreparedSourcedMapLabelLine,
        val english: PreparedSourcedMapLabelLine?,
        val width: Float,
        val collision_height: Float,
        val collision_center_offset: Float,
    )

    private data class PreparedAirspacePolygon(
        val point_count: Int,
        val path: Path,
        val min_x: Float,
        val max_x: Float,
        val min_y: Float,
        val max_y: Float
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
        val nat_temporal_state_token: Long,
        val selection_key: AviationSelectionKey?
    )

    private companion object {
        const val TILE_SIZE = 256
        const val MAX_WRAPPED_RING_COPIES = 8
        const val MAX_TRANSFORMED_CACHE_ZOOM_DELTA = 1.6
        const val MAX_TRANSFORMED_CACHE_TRANSLATION_FRACTION = 0.65f
        const val AIRSPACE_LABEL_MIN_ZOOM = 7.2
        const val OCEANIC_TRACK_MIN_ZOOM = 3.0
    }
}

internal fun project_aviation_point_to_screen(
    point: AviationLayerPoint,
    viewport: Viewport,
    lat_lon_to_world: (Double, Double, Double) -> WorldPoint
): ScreenPoint? {
    val world = lat_lon_to_world(point.lat, point.lon, viewport.zoom)
    var screen_x = (world.x - viewport.center_x + viewport.width / 2.0).toFloat()
    val world_span = (TILE_SIZE * 2.0.pow(viewport.zoom)).toFloat()
    while (screen_x < -world_span / 2f) screen_x += world_span
    while (screen_x > viewport.width + world_span / 2f) screen_x -= world_span
    val screen_y = (world.y - viewport.center_y + viewport.height / 2.0).toFloat()
    if (
        screen_x < -viewport.width ||
        screen_x > viewport.width * 2f ||
        screen_y < -viewport.height ||
        screen_y > viewport.height * 2f
    ) {
        return null
    }
    return ScreenPoint(screen_x, screen_y)
}
