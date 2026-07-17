@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import androidx.core.graphics.get
import com.flightalert.details.throwable_safe_https_url
import com.flightalert.ui.smooth_step
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class ReferencePrefetchBudget(
    var remaining_submissions: Int
)

// Satellite renderer restored behind the Satellite toggle, with continuous LOD blending.
// The satellite base path is intentionally kept independent of optional labels.
// Labels/reference tiles are drawn afterward in their own cache so they cannot disturb base imagery.
internal class SatelliteMapTileRenderer(
    private val context: Context,
    private val paint: Paint,
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val report_status: (String) -> Unit,
    private val request_redraw: () -> Unit,
    shared_map_tile_disk_cache: MapTileDiskCache? = null,
) {
    private val map_tile_disk_cache = shared_map_tile_disk_cache ?: ProcessMapTileDiskCaches.cache(
        context.cacheDir,
        com.flightalert.MAP_TILE_DISK_CACHE_MAX_BYTES,
    )
    private val map_content_revision = AtomicLong()
    private val base_renderer = SatelliteBaseTileRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        report_status = report_status,
        request_redraw = request_redraw,
        map_content_revision = map_content_revision,
        shared_map_tile_disk_cache = map_tile_disk_cache,
        overlay_drawer = ::draw_reference_layers,
    )
    private val reference_tile_cache =
        LinkedHashMap<String, Bitmap>(MAX_REFERENCE_MEMORY_TILES, 0.75f, true)
    private val reference_tile_loaded_elapsed_ms = mutableMapOf<String, Long>()
    private val protected_reference_tile_keys = HashSet<String>()
    private val previous_protected_reference_tile_keys = HashSet<String>()
    private val requested_reference_tiles = mutableMapOf<String, Long>()
    private val requested_reference_tile_redraws = mutableSetOf<String>()
    private val current_reference_tile_request_generations = mutableMapOf<String, Long>()
    private var current_reference_tile_request_generation = 0L
    private val reference_selected_tile_zooms = mutableMapOf<ReferenceTileOverlay, Int>()
    private val reference_rendered_tile_zooms = mutableMapOf<ReferenceTileOverlay, Int>()
    private val reference_lod_crossfades = mutableMapOf<ReferenceTileOverlay, ReferenceLodCrossfade>()
    private val reference_motion_coverage_alpha_floor = mutableMapOf<ReferenceTileOverlay, Float>()
    private val reference_motion_coverage_alpha_floor_ms = mutableMapOf<ReferenceTileOverlay, Long>()
    private val reference_lod_prefetch_lock = Any()
    private var reference_lod_prefetch_latest_plans: List<ReferencePrefetchGridPlan> = emptyList()
    private var reference_lod_prefetch_scheduled = false
    private var reference_lod_prefetch_epoch = 0L
    private val reference_tile_worker_id = AtomicInteger()
    private val reference_tile_task_sequence = AtomicLong()
    private val dictionary_reference_renderer = ReferenceDictionaryOverlayRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        request_redraw = request_redraw
    )

    // Label/reference overlays deliberately run on their own small, prioritized worker pool so
    // stale zoom levels cannot block the current real roads/borders/place-label tiles.
    private val reference_tile_executor = ThreadPoolExecutor(
        REFERENCE_TILE_NETWORK_THREADS,
        REFERENCE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue()
    ) { runnable ->
        Thread(
            runnable,
            "flightalert-satellite-labels-${reference_tile_worker_id.incrementAndGet()}"
        ).apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val reference_tile_destination = RectF()
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    private var last_reference_lod_prefetch_batch_key: String? = null
    private var last_reference_lod_prefetch_batch_ms = 0L
    private val last_reference_pan_prefetch_request_ms =
        LinkedHashMap<String, Long>(REFERENCE_PAN_PREFETCH_THROTTLE_HISTORY_MAX, 0.75f, true)

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        return base_renderer.draw_tiles(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style
        )
    }

    fun reference_class_catalog(): ReferenceClassCatalog? {
        return dictionary_reference_renderer.reference_class_catalog()
    }

    private fun draw_reference_layers(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        @Suppress("UNUSED_PARAMETER") lod: SatelliteLodState,
        @Suppress("UNUSED_PARAMETER") now_ms: Long
    ): TileLayerDrawStats {
        if (!state.map_labels_enabled && !state.map_borders_enabled) {
            return empty_reference_stats()
        }
        val dictionary_stats = dictionary_reference_renderer.draw(
            canvas = canvas,
            viewport = viewport,
            labels_enabled = state.map_labels_enabled,
            borders_enabled = state.map_borders_enabled,
            label_text_scale = state.map_label_text_scale,
            place_labels_enabled = state.place_labels_enabled,
            water_labels_enabled = state.water_labels_enabled,
            region_labels_enabled = state.region_labels_enabled,
            public_lands_enabled = state.public_lands_enabled,
            filter_state = state.reference_filter_state,
            interaction_active = state.interaction_active,
            label_avoid_rects = state.reference_label_avoid_rects,
        )
        if (dictionary_stats.available) {
            return TileLayerDrawStats(
                visible = dictionary_stats.visible_tiles,
                loaded = dictionary_stats.loaded_tiles,
                requested = if (dictionary_stats.ready) {
                    0
                } else {
                    maxOf(
                        dictionary_stats.requested_tiles,
                        dictionary_stats.visible_tiles - dictionary_stats.loaded_tiles
                    )
                },
                fallback_drawn = 0,
                fading = dictionary_stats.fading
            )
        }
        return empty_reference_stats()
    }

    private fun empty_reference_stats(): TileLayerDrawStats {
        return TileLayerDrawStats(
            visible = 0,
            loaded = 0,
            requested = 0,
            fallback_drawn = 0,
            fading = false
        )
    }


    fun clear() {
        base_renderer.clear()
        dictionary_reference_renderer.clear()
        synchronized(reference_tile_cache) {
            reference_tile_cache.clear()
            reference_tile_loaded_elapsed_ms.clear()
        }
        last_reference_lod_prefetch_batch_key = null
        last_reference_lod_prefetch_batch_ms = 0L
        last_reference_pan_prefetch_request_ms.clear()
        synchronized(requested_reference_tiles) {
            requested_reference_tiles.clear()
            requested_reference_tile_redraws.clear()
            current_reference_tile_request_generations.clear()
            current_reference_tile_request_generation = 0L
        }
        synchronized(reference_lod_prefetch_lock) {
            reference_lod_prefetch_latest_plans = emptyList()
            reference_lod_prefetch_epoch++
        }
        reference_selected_tile_zooms.clear()
        reference_rendered_tile_zooms.clear()
        reference_lod_crossfades.clear()
        reference_motion_coverage_alpha_floor.clear()
        reference_motion_coverage_alpha_floor_ms.clear()
    }

    fun reset_transitions() {
        base_renderer.reset_transitions()
        dictionary_reference_renderer.reset_retained_frame()
        reference_lod_crossfades.clear()
    }

    fun shutdown() {
        base_renderer.shutdown()
        reference_tile_executor.shutdownNow()
        dictionary_reference_renderer.close()
        clear()
    }

    private fun draw_reference_overlay_layers(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long,
        lower_tile_zoom: Int,
        upper_tile_zoom: Int,
        has_upper_lod: Boolean,
        upper_lod_alpha: Float,
        prefetch_upper_lod: Boolean,
        request_generation: Long
    ): TileLayerDrawStats {
        val overlays = state.reference_overlay_layers
        if (overlays.isEmpty()) {
            return TileLayerDrawStats(
                visible = 0,
                loaded = 0,
                requested = 0,
                fallback_drawn = 0,
                fading = false
            )
        }
        if (!REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED) {
            return draw_baked_only_reference_overlay_layers(
                canvas = canvas,
                viewport = viewport,
                state = state,
                now_ms = now_ms,
                lower_tile_zoom = lower_tile_zoom,
                upper_tile_zoom = upper_tile_zoom,
                has_upper_lod = has_upper_lod,
                upper_lod_alpha = upper_lod_alpha,
                request_generation = request_generation,
                overlays = overlays
            )
        }

        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val deferred_lod_prefetch_plans = ArrayList<ReferencePrefetchGridPlan>(overlays.size)

        for (overlay in overlays) {
            val plan = reference_overlay_draw_plan(
                overlay = overlay,
                viewport = viewport,
                now_ms = now_ms,
                lower_tile_zoom = lower_tile_zoom,
                upper_tile_zoom = upper_tile_zoom,
                has_upper_lod = has_upper_lod,
                upper_lod_alpha = upper_lod_alpha,
                prefetch_upper_lod = prefetch_upper_lod
            )
            if (plan == null) continue
            val overlay_alpha = reference_overlay_zoom_alpha(
                overlay = overlay,
                viewport_zoom = viewport.zoom
            )
            val raw_coverage_alpha = reference_overlay_coverage_alpha(
                overlay = overlay,
                viewport_zoom = viewport.zoom,
                coverage = plan.coverage
            )
            val coverage_alpha = stable_reference_overlay_coverage_alpha(
                overlay = overlay,
                interaction_active = state.interaction_active,
                overlay_alpha = overlay_alpha,
                coverage_alpha = raw_coverage_alpha,
                now_ms = now_ms
            )
            if (!state.interaction_active &&
                coverage_alpha > raw_coverage_alpha + MIN_LAYER_ALPHA
            ) {
                fading = true
            }
            val draw_alpha = overlay_alpha * coverage_alpha
            val requests_allowed_for_zoom = reference_overlay_requests_allowed(
                overlay = overlay,
                viewport_zoom = viewport.zoom,
                overlay_alpha = overlay_alpha
            )
            if (!requests_allowed_for_zoom && overlay_alpha <= MIN_LAYER_ALPHA) {
                continue
            }
            var overlay_visible = 0
            var overlay_loaded = 0
            var overlay_requested = 0
            var overlay_fallback_drawn = 0
            val crossfade = reference_lod_crossfade(
                overlay = overlay,
                previous_tile_zoom = reference_rendered_tile_zooms[overlay],
                draw_tile_zoom = plan.draw_tile_zoom,
                now_ms = now_ms
            )
            val draw_stats = if (crossfade != null && draw_alpha > MIN_LAYER_ALPHA) {
                draw_crossfaded_reference_overlay_grid(
                    canvas = canvas,
                    viewport = viewport,
                    state = state,
                    overlay = overlay,
                    plan = plan,
                    crossfade = crossfade,
                    now_ms = now_ms,
                    draw_alpha = draw_alpha,
                    allow_exact_requests = requests_allowed_for_zoom,
                    allow_parent_requests = requests_allowed_for_zoom,
                    request_generation = request_generation
                ).also {
                    fading = true
                }
            } else {
                draw_reference_overlay_grid(
                    canvas = canvas,
                    viewport = viewport,
                    state = state,
                    overlay = overlay,
                    tile_zoom = plan.draw_tile_zoom,
                    now_ms = now_ms,
                    layer_alpha = draw_alpha,
                    allow_exact_requests = requests_allowed_for_zoom,
                    allow_parent_requests = requests_allowed_for_zoom,
                    request_generation = request_generation
                )
            }
            reference_rendered_tile_zooms[overlay] = plan.draw_tile_zoom
            overlay_visible += draw_stats.visible
            overlay_loaded += draw_stats.loaded
            overlay_requested += draw_stats.requested
            overlay_fallback_drawn += draw_stats.fallback_drawn
            fading = fading || draw_stats.fading

            request_reference_overlay_prefetches(
                viewport = viewport,
                state = state,
                overlay = overlay,
                plan = plan,
                requests_allowed_for_zoom = requests_allowed_for_zoom,
                request_generation = request_generation,
                deferred_lod_prefetch_plans = deferred_lod_prefetch_plans
            )
            visible += overlay_visible
            loaded += overlay_loaded
            requested += overlay_requested
            fallback_drawn += overlay_fallback_drawn
        }
        offer_reference_lod_prefetch_batch(deferred_lod_prefetch_plans)

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading
        )
    }

    private fun draw_baked_only_reference_overlay_layers(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long,
        lower_tile_zoom: Int,
        upper_tile_zoom: Int,
        has_upper_lod: Boolean,
        upper_lod_alpha: Float,
        request_generation: Long,
        overlays: List<ReferenceTileOverlay>
    ): TileLayerDrawStats {
        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val baked_overlays = overlays.sortedBy { overlay ->
            if (overlay == ReferenceTileOverlay.WORLD_TRANSPORTATION) 0 else 1
        }
        for (overlay in baked_overlays) {
            val overlay_alpha = reference_overlay_zoom_alpha(
                overlay = overlay,
                viewport_zoom = viewport.zoom
            )
            val requests_allowed_for_zoom = reference_overlay_requests_allowed(
                overlay = overlay,
                viewport_zoom = viewport.zoom,
                overlay_alpha = overlay_alpha
            )
            if (!requests_allowed_for_zoom && overlay_alpha <= MIN_LAYER_ALPHA) continue
            val tile_zoom = baked_only_reference_tile_zoom(
                overlay = overlay,
                viewport_zoom = viewport.zoom,
                lower_tile_zoom = lower_tile_zoom,
                upper_tile_zoom = upper_tile_zoom,
                has_upper_lod = has_upper_lod,
                upper_lod_alpha = upper_lod_alpha
            )
            val draw_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = tile_zoom,
                now_ms = now_ms,
                layer_alpha = overlay_alpha,
                allow_exact_requests = requests_allowed_for_zoom,
                allow_parent_requests = false,
                request_generation = request_generation
            )
            reference_rendered_tile_zooms[overlay] = tile_zoom
            visible += draw_stats.visible
            loaded += draw_stats.loaded
            requested += draw_stats.requested
            fallback_drawn += draw_stats.fallback_drawn
            fading = fading || draw_stats.fading
        }
        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading
        )
    }

    private fun baked_only_reference_tile_zoom(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double,
        lower_tile_zoom: Int,
        upper_tile_zoom: Int,
        has_upper_lod: Boolean,
        upper_lod_alpha: Float
    ): Int {
        val satellite_zoom =
            if (has_upper_lod && upper_lod_alpha >= REFERENCE_LOD_SWITCH_ALPHA) {
                upper_tile_zoom
            } else {
                lower_tile_zoom
            }
        val overlay_zoom = when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> satellite_zoom
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> minOf(
                satellite_zoom,
                reference_transportation_detail_tile_zoom(viewport_zoom)
            )
        }
        val tile_zoom = overlay_zoom
            .coerceIn(MIN_ZOOM, TileSource.SATELLITE.max_native_zoom)
        return tile_zoom
    }

    private fun draw_crossfaded_reference_overlay_grid(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        plan: ReferenceOverlayDrawPlan,
        crossfade: ReferenceLodCrossfade,
        now_ms: Long,
        draw_alpha: Float,
        allow_exact_requests: Boolean,
        allow_parent_requests: Boolean,
        request_generation: Long
    ): TileLayerDrawStats {
        val progress = reference_lod_crossfade_progress(crossfade, now_ms)
        val zooming_in = crossfade.to_tile_zoom > crossfade.from_tile_zoom
        val old_stats: TileLayerDrawStats
        val new_stats: TileLayerDrawStats
        if (zooming_in) {
            old_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = crossfade.from_tile_zoom,
                now_ms = now_ms,
                layer_alpha = draw_alpha,
                allow_exact_requests = false,
                allow_parent_requests = false,
                request_generation = request_generation
            )
            new_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = plan.draw_tile_zoom,
                now_ms = now_ms,
                layer_alpha = draw_alpha * progress,
                allow_exact_requests = allow_exact_requests,
                allow_parent_requests = allow_parent_requests,
                request_generation = request_generation
            )
        } else {
            new_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = plan.draw_tile_zoom,
                now_ms = now_ms,
                layer_alpha = draw_alpha,
                allow_exact_requests = allow_exact_requests,
                allow_parent_requests = allow_parent_requests,
                request_generation = request_generation
            )
            old_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = crossfade.from_tile_zoom,
                now_ms = now_ms,
                layer_alpha = draw_alpha * (1f - progress),
                allow_exact_requests = false,
                allow_parent_requests = false,
                request_generation = request_generation
            )
        }
        return TileLayerDrawStats(
            visible = old_stats.visible + new_stats.visible,
            loaded = old_stats.loaded + new_stats.loaded,
            requested = old_stats.requested + new_stats.requested,
            fallback_drawn = old_stats.fallback_drawn + new_stats.fallback_drawn,
            fading = true
        )
    }

    private fun reference_lod_crossfade(
        overlay: ReferenceTileOverlay,
        previous_tile_zoom: Int?,
        draw_tile_zoom: Int,
        now_ms: Long
    ): ReferenceLodCrossfade? {
        val active = reference_lod_crossfades[overlay]
        if (active != null && active.to_tile_zoom == draw_tile_zoom) {
            if (reference_lod_crossfade_progress(active, now_ms) < 1f) return active
            reference_lod_crossfades.remove(overlay)
        }
        if (
            previous_tile_zoom == null ||
            previous_tile_zoom == draw_tile_zoom ||
            !reference_lod_crossfade_enabled(overlay, previous_tile_zoom, draw_tile_zoom)
        ) {
            return null
        }
        return ReferenceLodCrossfade(
            from_tile_zoom = previous_tile_zoom,
            to_tile_zoom = draw_tile_zoom,
            start_ms = now_ms,
            duration_ms = reference_lod_crossfade_duration_ms(overlay)
        ).also { reference_lod_crossfades[overlay] = it }
    }

    private fun reference_lod_crossfade_progress(
        crossfade: ReferenceLodCrossfade,
        now_ms: Long
    ): Float {
        return smooth_step(
            0f,
            1f,
            ((now_ms - crossfade.start_ms).coerceAtLeast(0L).toFloat() /
                    crossfade.duration_ms)
        )
    }

    private fun reference_lod_crossfade_enabled(
        overlay: ReferenceTileOverlay,
        from_tile_zoom: Int,
        to_tile_zoom: Int
    ): Boolean {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                minOf(from_tile_zoom, to_tile_zoom) == REFERENCE_BOUNDARY_COUNTY_DETAIL_FROM_TILE_ZOOM &&
                        maxOf(from_tile_zoom, to_tile_zoom) == REFERENCE_BOUNDARY_COUNTY_DETAIL_TO_TILE_ZOOM

            ReferenceTileOverlay.WORLD_TRANSPORTATION -> false
        }
    }

    private fun reference_lod_crossfade_duration_ms(overlay: ReferenceTileOverlay): Float {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_LOD_CROSSFADE_MS
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_LOD_CROSSFADE_MS
        }
    }

    private fun request_reference_overlay_prefetches(
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        plan: ReferenceOverlayDrawPlan,
        requests_allowed_for_zoom: Boolean,
        request_generation: Long,
        deferred_lod_prefetch_plans: MutableList<ReferencePrefetchGridPlan>?
    ) {
        for (prefetch_tile_zoom in plan.prefetch_tile_zooms) {
            val prefetch_plan = build_reference_prefetch_grid_plan(
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = prefetch_tile_zoom,
                allow_exact_requests = requests_allowed_for_zoom,
                tile_buffer = reference_overlay_prefetch_tile_buffer(
                    draw_tile_zoom = plan.draw_tile_zoom,
                    prefetch_tile_zoom = prefetch_tile_zoom,
                    viewport_zoom = viewport.zoom
                ),
                request_priority_base = reference_prefetch_request_priority_base(
                    draw_tile_zoom = plan.draw_tile_zoom,
                    prefetch_tile_zoom = prefetch_tile_zoom,
                    viewport_zoom = viewport.zoom
                ),
                request_generation = request_generation
            )
            if (prefetch_plan != null) {
                deferred_lod_prefetch_plans?.add(prefetch_plan)
            }
        }
        val pan_prefetch_buffer = reference_overlay_pan_prefetch_tile_buffer(
            overlay = overlay,
            viewport_zoom = viewport.zoom
        )
        if (pan_prefetch_buffer > 0) {
            request_reference_overlay_prefetch_grid(
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = plan.draw_tile_zoom,
                allow_exact_requests = requests_allowed_for_zoom,
                tile_buffer = pan_prefetch_buffer,
                request_generation = request_generation
            )
        }
    }

    private fun build_reference_prefetch_grid_plan(
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        allow_exact_requests: Boolean,
        tile_buffer: Int,
        request_priority_base: Int,
        request_generation: Long
    ): ReferencePrefetchGridPlan? {
        if (!allow_exact_requests) {
            return null
        }
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt() - tile_buffer
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt() - tile_buffer
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt() + tile_buffer
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt() + tile_buffer
        val max_tile = 1 shl tile_zoom
        val request_priority = reference_tile_request_priority(
            overlay = overlay,
            base_priority = request_priority_base
        )
        return ReferencePrefetchGridPlan(
            epoch = 0L,
            overlay = overlay,
            cache_key = overlay.cache_key,
            user_agent = state.user_agent,
            tile_zoom = tile_zoom,
            first_tile_x = first_tile_x,
            first_tile_y = first_tile_y,
            last_tile_x = last_tile_x,
            last_tile_y = last_tile_y,
            max_tile = max_tile,
            request_priority = request_priority,
            request_generation = request_generation
        )
    }

    private fun offer_reference_lod_prefetch_batch(plans: List<ReferencePrefetchGridPlan>) {
        if (plans.isEmpty()) return
        if (should_throttle_reference_lod_prefetch_batch(plans)) return
        val should_schedule = synchronized(reference_lod_prefetch_lock) {
            val epoch = reference_lod_prefetch_epoch + 1L
            reference_lod_prefetch_epoch = epoch
            reference_lod_prefetch_latest_plans = plans.map { it.copy(epoch = epoch) }
            if (reference_lod_prefetch_scheduled) {
                false
            } else {
                reference_lod_prefetch_scheduled = true
                true
            }
        }
        if (should_schedule) {
            enqueue_reference_lod_prefetch_drain()
        }
    }

    private fun should_throttle_reference_lod_prefetch_batch(
        plans: List<ReferencePrefetchGridPlan>
    ): Boolean {
        val now_ms = SystemClock.elapsedRealtime()
        val key = reference_lod_prefetch_batch_key(plans)
        if (key == last_reference_lod_prefetch_batch_key &&
            now_ms - last_reference_lod_prefetch_batch_ms < REFERENCE_LOD_PREFETCH_REQUEST_THROTTLE_MS
        ) {
            return true
        }
        last_reference_lod_prefetch_batch_key = key
        last_reference_lod_prefetch_batch_ms = now_ms
        return false
    }

    private fun reference_lod_prefetch_batch_key(plans: List<ReferencePrefetchGridPlan>): String {
        return buildString(plans.size * 48) {
            for (plan in plans) {
                append(plan.cache_key)
                append('/')
                append(plan.tile_zoom)
                append(':')
                append(plan.first_tile_x)
                append(',')
                append(plan.first_tile_y)
                append('-')
                append(plan.last_tile_x)
                append(',')
                append(plan.last_tile_y)
                append(';')
            }
        }
    }

    private fun enqueue_reference_lod_prefetch_drain() {
        val task_generation = synchronized(requested_reference_tiles) {
            current_reference_tile_request_generation
        }
        reference_tile_executor.execute(
            ReferenceTileRequestTask(
                generation = task_generation,
                priority = REFERENCE_TILE_REQUEST_PRIORITY_ZOOM_OUT_PREFETCH,
                sequence = reference_tile_task_sequence.incrementAndGet(),
                action = { drain_reference_lod_prefetch_batches() }
            )
        )
    }

    private fun drain_reference_lod_prefetch_batches() {
        while (true) {
            val plans = synchronized(reference_lod_prefetch_lock) {
                reference_lod_prefetch_latest_plans.also {
                    reference_lod_prefetch_latest_plans = emptyList()
                }
            }
            if (plans.isNotEmpty()) {
                drain_reference_lod_prefetch_batch(plans)
            }
            val has_more = synchronized(reference_lod_prefetch_lock) {
                if (reference_lod_prefetch_latest_plans.isEmpty()) {
                    reference_lod_prefetch_scheduled = false
                    false
                } else {
                    true
                }
            }
            if (!has_more) return
        }
    }

    private fun drain_reference_lod_prefetch_batch(plans: List<ReferencePrefetchGridPlan>) {
        val budget = ReferencePrefetchBudget(
            remaining_submissions = REFERENCE_LOD_PREFETCH_MAX_ADMITTED_PER_BATCH
        )
        for (plan in plans) {
            if (!reference_lod_prefetch_epoch_current(plan.epoch)) {
                break
            }
                if (!reference_lod_prefetch_generation_recent(plan.request_generation)) {
                continue
            }
            if (!request_reference_lod_prefetch_grid_from_plan(plan, budget)) break
        }
    }

    private fun request_reference_lod_prefetch_grid_from_plan(
        plan: ReferencePrefetchGridPlan,
        budget: ReferencePrefetchBudget
    ): Boolean {
        for (ty in plan.first_tile_y..plan.last_tile_y) {
            if (ty !in 0 until plan.max_tile) continue
            if (!reference_lod_prefetch_epoch_current(plan.epoch)) {
                return false
            }
            if (!reference_lod_prefetch_generation_recent(plan.request_generation)) {
                return true
            }
            for (tx_raw in plan.first_tile_x..plan.last_tile_x) {
                if (budget.remaining_submissions <= 0) {
                    return false
                }
                val tx = ((tx_raw % plan.max_tile) + plan.max_tile) % plan.max_tile
                val key = "${plan.cache_key}/${plan.tile_zoom}/$tx/$ty"
                if (reference_tile_bitmap(key) != null) {
                    continue
                }
                val admission = request_reference_tile(
                    overlay = plan.overlay,
                    z = plan.tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    cache_key = plan.cache_key,
                    url = plan.overlay.tile_url(plan.tile_zoom, tx, ty),
                    user_agent = plan.user_agent,
                    request_generation = plan.request_generation,
                    request_priority = plan.request_priority,
                    request_stale_generation_tolerance = REFERENCE_TILE_PREFETCH_STALE_GENERATIONS,
                    redraw_when_loaded = false,
                    speculative = true,
                    allow_baked_package = false
                )
                when (admission) {
                    REFERENCE_TILE_REQUEST_ADMITTED -> {
                        budget.remaining_submissions--
                        if (budget.remaining_submissions <= 0) {
                            return false
                        }
                    }

                    else -> Unit
                }
            }
        }
        return true
    }

    private fun reference_lod_prefetch_epoch_current(epoch: Long): Boolean {
        return synchronized(reference_lod_prefetch_lock) {
            epoch == reference_lod_prefetch_epoch
        }
    }

    private fun reference_lod_prefetch_generation_recent(generation: Long): Boolean {
        return synchronized(requested_reference_tiles) {
            current_reference_tile_request_generation - generation <=
                    REFERENCE_TILE_PREFETCH_STALE_GENERATIONS
        }
    }

    private fun reference_overlay_draw_plan(
        overlay: ReferenceTileOverlay,
        viewport: Viewport,
        now_ms: Long,
        lower_tile_zoom: Int,
        upper_tile_zoom: Int,
        has_upper_lod: Boolean,
        upper_lod_alpha: Float,
        prefetch_upper_lod: Boolean
    ): ReferenceOverlayDrawPlan? {
        val candidates = reference_overlay_candidate_zooms(
            overlay = overlay,
            lower_tile_zoom = lower_tile_zoom,
            upper_tile_zoom = upper_tile_zoom,
            has_upper_lod = has_upper_lod,
            upper_lod_alpha = upper_lod_alpha,
            prefetch_upper_lod = prefetch_upper_lod,
            viewport_zoom = viewport.zoom
        )
        val previous = reference_selected_tile_zooms[overlay]
        if (previous != null &&
            previous in MIN_ZOOM..TileSource.SATELLITE.max_native_zoom &&
            reference_retained_lod_drawable(
                overlay = overlay,
                tile_zoom = previous,
                viewport_zoom = viewport.zoom
            )
        ) {
            candidates += previous
        }

        val drawable_candidates = candidates
            .filter { reference_overlay_lod_drawable(overlay, it) }
            .distinct()
        if (drawable_candidates.isEmpty()) return null

        val target_tile_zoom = if (has_upper_lod && upper_lod_alpha >= REFERENCE_LOD_SWITCH_ALPHA) {
            upper_tile_zoom
        } else {
            lower_tile_zoom
        }.let { target ->
            if (target in drawable_candidates) {
                target
            } else {
                drawable_candidates.minByOrNull { abs(it - target) } ?: drawable_candidates.first()
            }
        }

        val coverage_cache = HashMap<Int, ReferenceTileCoverage>(drawable_candidates.size)
        fun coverage_for(tile_zoom: Int): ReferenceTileCoverage {
            return coverage_cache.getOrPut(tile_zoom) {
                reference_tile_coverage(
                    overlay = overlay,
                    viewport = viewport,
                    tile_zoom = tile_zoom,
                    now_ms = now_ms
                )
            }
        }

        fun finish_plan(
            draw_tile_zoom: Int,
            draw_coverage: ReferenceTileCoverage
        ): ReferenceOverlayDrawPlan {
            if (draw_tile_zoom != target_tile_zoom) {
                protect_reference_tile_grid(
                    overlay = overlay,
                    viewport = viewport,
                    tile_zoom = target_tile_zoom
                )
            }
            reference_selected_tile_zooms[overlay] = draw_tile_zoom

            val prefetch_tile_zooms = reference_overlay_prefetch_tile_zooms(
                overlay = overlay,
                drawable_candidates = drawable_candidates,
                draw_tile_zoom = draw_tile_zoom,
                target_tile_zoom = target_tile_zoom,
                viewport_zoom = viewport.zoom,
                coverage = draw_coverage
            )
            return ReferenceOverlayDrawPlan(
                draw_tile_zoom = draw_tile_zoom,
                prefetch_tile_zooms = prefetch_tile_zooms,
                coverage = draw_coverage
            )
        }

        val target_coverage = coverage_for(target_tile_zoom)
        val target_commit_ready = reference_lod_switch_commit_ready(overlay, target_coverage)
        if (target_commit_ready) {
            return finish_plan(target_tile_zoom, target_coverage)
        }

        val target_visual_holdable = previous == target_tile_zoom &&
                reference_close_pan_target_lod_holdable(
                    viewport_zoom = viewport.zoom,
                    coverage = target_coverage
                )
        if (target_visual_holdable) {
            return finish_plan(target_tile_zoom, target_coverage)
        }

        val previous_coverage = previous?.takeIf { it in drawable_candidates }?.let { zoom ->
            zoom to if (zoom == target_tile_zoom) target_coverage else coverage_for(zoom)
        }
        val retained_previous = previous_coverage?.takeIf { (_, coverage) ->
            reference_selected_lod_holdable(
                overlay = overlay,
                viewport_zoom = viewport.zoom,
                coverage = coverage
            )
        }
        if (retained_previous != null) {
            return finish_plan(retained_previous.first, retained_previous.second)
        }

        val selectable_coverages = drawable_candidates.mapNotNull { zoom ->
            if (zoom != previous || zoom == target_tile_zoom) {
                java.util.AbstractMap.SimpleImmutableEntry(
                    zoom,
                    if (zoom == target_tile_zoom) target_coverage else coverage_for(zoom)
                )
            } else {
                null
            }
        }
        val coverage_comparator = reference_lod_coverage_comparator(overlay, previous)
        val best_committed = selectable_coverages
            .filter { (_, coverage) -> reference_lod_switch_commit_ready(overlay, coverage) }
            .maxWithOrNull(coverage_comparator)
        val best_ready = selectable_coverages.maxWithOrNull(coverage_comparator)
        val selected = best_committed ?: best_ready
        return if (selected != null) {
            finish_plan(selected.key, selected.value)
        } else {
            finish_plan(target_tile_zoom, target_coverage)
        }
    }

    private fun reference_overlay_candidate_zooms(
        overlay: ReferenceTileOverlay,
        lower_tile_zoom: Int,
        upper_tile_zoom: Int,
        has_upper_lod: Boolean,
        upper_lod_alpha: Float,
        prefetch_upper_lod: Boolean,
        viewport_zoom: Double
    ): LinkedHashSet<Int> {
        val max_candidate =
            if (has_upper_lod && (upper_lod_alpha > MIN_LAYER_ALPHA || prefetch_upper_lod)) {
                upper_tile_zoom
            } else {
                lower_tile_zoom
            }.let { candidate ->
                when (overlay) {
                    ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> candidate
                    ReferenceTileOverlay.WORLD_TRANSPORTATION -> minOf(
                        candidate,
                        reference_transportation_detail_tile_zoom(viewport_zoom)
                    )
                }
            }.coerceIn(MIN_ZOOM, TileSource.SATELLITE.max_native_zoom)
        val min_draw_zoom = when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> MIN_ZOOM
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_MIN_ZOOM
        }
        val coarser_depth = when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_COARSE_LOD_DEPTH
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_COARSE_LOD_DEPTH
        }
        val min_candidate = maxOf(
            lower_tile_zoom - coarser_depth,
            min_draw_zoom,
            reference_min_visual_tile_zoom(overlay, viewport_zoom)
        )
            .coerceAtMost(max_candidate)
        val candidates = linkedSetOf<Int>()
        for (zoom in min_candidate..max_candidate) {
            candidates += zoom
        }
        return candidates
    }

    private fun reference_min_visual_tile_zoom(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double
    ): Int {
        val max_upscale_delta = when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_MAX_TILE_UPSCALE_DELTA
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_MAX_TILE_UPSCALE_DELTA
        }
        return ceil(viewport_zoom - max_upscale_delta)
            .toInt()
            .coerceAtLeast(MIN_ZOOM)
            .coerceAtMost(TileSource.SATELLITE.max_native_zoom)
    }

    private fun reference_transportation_detail_tile_zoom(viewport_zoom: Double): Int {
        return floor(viewport_zoom + REFERENCE_TRANSPORTATION_DETAIL_ADVANCE)
            .toInt()
            .coerceIn(REFERENCE_TRANSPORTATION_MIN_ZOOM, TileSource.SATELLITE.max_native_zoom)
    }

    private fun reference_overlay_lod_drawable(
        overlay: ReferenceTileOverlay,
        tile_zoom: Int
    ): Boolean {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> true
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> tile_zoom >= REFERENCE_TRANSPORTATION_MIN_ZOOM
        }
    }

    private fun reference_retained_lod_drawable(
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        viewport_zoom: Double
    ): Boolean {
        if (!reference_overlay_lod_drawable(overlay, tile_zoom)) return false
        val max_upscale_delta = when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_MAX_TILE_UPSCALE_DELTA
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_MAX_TILE_UPSCALE_DELTA
        }
        val max_detail_delta = when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_RETAINED_MAX_DETAIL_DELTA
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_RETAINED_MAX_DETAIL_DELTA
        }
        val delta = tile_zoom.toDouble() - viewport_zoom
        return delta >= -max_upscale_delta && delta <= max_detail_delta
    }

    private fun reference_retained_lod_ratio(
        overlay: ReferenceTileOverlay,
        coverage: ReferenceTileCoverage
    ): Float {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                minOf(coverage.loaded_ratio, coverage.center_loaded_ratio)

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                minOf(coverage.loaded_ratio, coverage.center_loaded_ratio)
        }
    }

    private fun reference_lod_ready_ratio(
        overlay: ReferenceTileOverlay,
        coverage: ReferenceTileCoverage
    ): Float {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                minOf(coverage.ready_ratio, coverage.center_ready_ratio)

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                minOf(coverage.ready_ratio, coverage.center_ready_ratio)
        }
    }

    private fun reference_lod_switch_commit_ready(
        overlay: ReferenceTileOverlay,
        coverage: ReferenceTileCoverage
    ): Boolean {
        val loaded_ratio = reference_retained_lod_ratio(overlay, coverage)
        val ready_ratio = reference_lod_ready_ratio(overlay, coverage)
        return loaded_ratio >= REFERENCE_LOD_SWITCH_COMMIT_RATIO &&
                ready_ratio >= REFERENCE_LOD_SWITCH_COMMIT_RATIO
    }

    private fun reference_selected_lod_holdable(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double,
        coverage: ReferenceTileCoverage
    ): Boolean {
        if (reference_close_pan_target_lod_holdable(viewport_zoom, coverage)) {
            return true
        }
        return reference_lod_switch_commit_ready(overlay, coverage) &&
                (
                        reference_retained_lod_ratio(
                            overlay,
                            coverage
                        ) >= REFERENCE_RETAINED_LOD_MIN_RATIO ||
                                reference_overlay_coverage_alpha(
                                    overlay = overlay,
                                    viewport_zoom = viewport_zoom,
                                    coverage = coverage
                                ) > MIN_LAYER_ALPHA
                        )
    }

    private fun reference_close_pan_target_lod_holdable(
        viewport_zoom: Double,
        coverage: ReferenceTileCoverage
    ): Boolean {
        if (viewport_zoom.toFloat() < REFERENCE_CLOSE_PAN_TARGET_LOD_HOLD_ZOOM) return false
        if (coverage.visible <= 0 || coverage.center_visible <= 0) return false
        return coverage.visual_ready >= coverage.visible &&
                coverage.center_visual_ready >= coverage.center_visible
    }

    private fun reference_lod_coverage_comparator(
        overlay: ReferenceTileOverlay,
        previous: Int?
    ): Comparator<Map.Entry<Int, ReferenceTileCoverage>> {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                compareBy<Map.Entry<Int, ReferenceTileCoverage>> {
                    reference_lod_ready_ratio(
                        overlay,
                        it.value
                    )
                }
                    .thenBy { it.value.ready_ratio }
                    .thenBy { it.value.loaded_ratio }
                    .thenBy { it.value.center_loaded_ratio }
                    .thenBy { it.value.visual_ratio }
                    .thenBy { it.value.center_visual_ratio }
                    .thenBy { if (it.key == previous) 1 else 0 }
                    .thenBy { -it.key }

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                compareBy<Map.Entry<Int, ReferenceTileCoverage>> {
                    reference_lod_ready_ratio(
                        overlay,
                        it.value
                    )
                }
                    .thenBy { it.value.ready_ratio }
                    .thenBy { it.value.loaded_ratio }
                    .thenBy { it.value.center_loaded_ratio }
                    .thenBy { it.value.visual_ratio }
                    .thenBy { it.value.center_visual_ratio }
                    .thenBy { if (it.key == previous) 1 else 0 }
                    .thenBy { -it.key }
        }
    }

    private fun reference_overlay_requests_allowed(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double,
        overlay_alpha: Float
    ): Boolean {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> true
            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                overlay_alpha > MIN_LAYER_ALPHA ||
                        viewport_zoom.toFloat() >= REFERENCE_TRANSPORTATION_PREFETCH_START_ZOOM
        }
    }

    private fun reference_prefetch_lod_limit(overlay: ReferenceTileOverlay): Int {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_PREFETCH_LOD_LIMIT
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_PREFETCH_LOD_LIMIT
        }
    }

    private fun reference_overlay_prefetch_tile_zooms(
        overlay: ReferenceTileOverlay,
        drawable_candidates: List<Int>,
        draw_tile_zoom: Int,
        target_tile_zoom: Int,
        viewport_zoom: Double,
        coverage: ReferenceTileCoverage
    ): List<Int> {
        val prefetch_zooms = linkedSetOf<Int>()
        if (reference_overlay_prefetch_allowed(overlay, coverage)) {
            drawable_candidates
                .asSequence()
                .filter { it != draw_tile_zoom && it > draw_tile_zoom }
                .sortedBy { abs(it - target_tile_zoom) }
                .take(reference_prefetch_lod_limit(overlay))
                .forEach { prefetch_zooms += it }
        }
        if (overlay == ReferenceTileOverlay.WORLD_TRANSPORTATION) {
            if (reference_overlay_prefetch_allowed(overlay, coverage)) {
                var zoom = reference_transportation_detail_tile_zoom(viewport_zoom) + 1
                var count = 0
                while (zoom <= TileSource.SATELLITE.max_native_zoom &&
                    count < REFERENCE_TRANSPORTATION_ZOOM_IN_PREFETCH_COUNT
                ) {
                    if (zoom != draw_tile_zoom) prefetch_zooms += zoom
                    zoom++
                    count++
                }
            }
            val zoom_out_prefetch = ArrayList<Int>(REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_COUNT)
            var zoom = reference_transportation_detail_tile_zoom(viewport_zoom) -
                    REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_OFFSET
            var count = 0
            while (zoom >= REFERENCE_TRANSPORTATION_MIN_ZOOM &&
                count < REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_COUNT
            ) {
                if (zoom != draw_tile_zoom) zoom_out_prefetch += zoom
                zoom -= REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_STEP
                count++
            }
            zoom_out_prefetch.asReversed().forEach { prefetch_zooms += it }
        } else {
            val zoom_out_prefetch = ArrayList<Int>(REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_COUNT)
            var zoom = floor(viewport_zoom).toInt() - REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_OFFSET
            var count = 0
            while (zoom >= MIN_ZOOM && count < REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_COUNT) {
                if (zoom != draw_tile_zoom) zoom_out_prefetch += zoom
                zoom -= REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_STEP
                count++
            }
            zoom_out_prefetch.asReversed().forEach { prefetch_zooms += it }
        }
        return prefetch_zooms.toList()
    }

    private fun reference_overlay_coverage_alpha(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double,
        coverage: ReferenceTileCoverage
    ): Float {
        if (!REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED) {
            return baked_only_reference_coverage_alpha(coverage)
        }
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> smooth_step(
                REFERENCE_BOUNDARY_DRAW_MIN_LOADED_RATIO,
                REFERENCE_BOUNDARY_DRAW_FULL_LOADED_RATIO,
                coverage.visual_ratio
            )

            ReferenceTileOverlay.WORLD_TRANSPORTATION -> reference_transportation_coverage_alpha(
                viewport_zoom = viewport_zoom,
                coverage = coverage
            )
        }
    }

    private fun baked_only_reference_coverage_alpha(coverage: ReferenceTileCoverage): Float {
        return if (coverage.visual_ready > 0 || coverage.center_visual_ready > 0) 1f else 0f
    }

    private fun reference_transportation_coverage_alpha(
        viewport_zoom: Double,
        coverage: ReferenceTileCoverage
    ): Float {
        val zoom = viewport_zoom.toFloat()
        val usable_visual_ratio = minOf(
            coverage.visual_ratio,
            maxOf(
                coverage.center_visual_ratio,
                coverage.visual_ratio * REFERENCE_TRANSPORTATION_CENTER_COVERAGE_FLOOR
            )
        )
        if (zoom >= REFERENCE_TRANSPORTATION_CLOSE_PAN_KEEP_VISIBLE_ZOOM) {
            return smooth_step(
                REFERENCE_TRANSPORTATION_CLOSE_PAN_DRAW_MIN_LOADED_RATIO,
                REFERENCE_TRANSPORTATION_CLOSE_PAN_DRAW_FULL_LOADED_RATIO,
                usable_visual_ratio
            )
        }

        val relaxed = smooth_step(
            REFERENCE_TRANSPORTATION_RELAXED_DRAW_START_ZOOM,
            REFERENCE_TRANSPORTATION_CLOSE_PAN_KEEP_VISIBLE_ZOOM,
            zoom
        )
        val min_loaded = REFERENCE_TRANSPORTATION_DRAW_MIN_LOADED_RATIO +
                (REFERENCE_TRANSPORTATION_MID_DRAW_MIN_LOADED_RATIO -
                        REFERENCE_TRANSPORTATION_DRAW_MIN_LOADED_RATIO) * relaxed
        val full_loaded = REFERENCE_TRANSPORTATION_DRAW_FULL_LOADED_RATIO +
                (REFERENCE_TRANSPORTATION_MID_DRAW_FULL_LOADED_RATIO -
                        REFERENCE_TRANSPORTATION_DRAW_FULL_LOADED_RATIO) * relaxed
        return smooth_step(min_loaded, full_loaded, usable_visual_ratio)
    }

    private fun stable_reference_overlay_coverage_alpha(
        overlay: ReferenceTileOverlay,
        interaction_active: Boolean,
        overlay_alpha: Float,
        coverage_alpha: Float,
        now_ms: Long
    ): Float {
        if (overlay_alpha <= MIN_LAYER_ALPHA) {
            reference_motion_coverage_alpha_floor.remove(overlay)
            reference_motion_coverage_alpha_floor_ms.remove(overlay)
            return coverage_alpha
        }
        val previous_alpha = reference_motion_coverage_alpha_floor[overlay] ?: coverage_alpha
        val previous_ms = reference_motion_coverage_alpha_floor_ms[overlay] ?: now_ms
        if (!interaction_active) {
            if (previous_alpha > coverage_alpha + MIN_LAYER_ALPHA) {
                val age_ms = (now_ms - previous_ms).coerceAtLeast(0L)
                val decay_ms = age_ms - REFERENCE_MOTION_COVERAGE_ALPHA_HOLD_MS
                if (decay_ms <= 0L) {
                    return previous_alpha
                }
                val decay = smooth_step(
                    0f,
                    1f,
                    (decay_ms.toFloat() / REFERENCE_MOTION_COVERAGE_ALPHA_DECAY_MS)
                )
                if (decay < 1f) {
                    return previous_alpha + (coverage_alpha - previous_alpha) * decay
                }
            }
            reference_motion_coverage_alpha_floor[overlay] = coverage_alpha
            reference_motion_coverage_alpha_floor_ms[overlay] = now_ms
            return coverage_alpha
        }
        val stable_alpha = maxOf(coverage_alpha, previous_alpha)
        reference_motion_coverage_alpha_floor[overlay] = stable_alpha
        reference_motion_coverage_alpha_floor_ms[overlay] = now_ms
        return stable_alpha
    }

    private fun reference_overlay_prefetch_tile_buffer(
        draw_tile_zoom: Int,
        prefetch_tile_zoom: Int,
        viewport_zoom: Double
    ): Int {
        if (prefetch_tile_zoom >= draw_tile_zoom) return 0
        val zoom_gap = (viewport_zoom - prefetch_tile_zoom.toDouble()).coerceAtLeast(0.0)
        return when {
            zoom_gap >= 3.0 -> 1
            zoom_gap >= 1.5 -> 1
            else -> 0
        }
    }

    private fun reference_overlay_pan_prefetch_tile_buffer(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double
    ): Int {
        val zoom = viewport_zoom.toFloat()
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                if (zoom >= REFERENCE_BOUNDARY_PAN_PREFETCH_START_ZOOM) REFERENCE_OVERLAY_PAN_PREFETCH_TILE_BUFFER else 0

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                if (zoom >= REFERENCE_TRANSPORTATION_PAN_PREFETCH_START_ZOOM) REFERENCE_OVERLAY_PAN_PREFETCH_TILE_BUFFER else 0
        }
    }

    private fun reference_overlay_prefetch_allowed(
        overlay: ReferenceTileOverlay,
        coverage: ReferenceTileCoverage
    ): Boolean {
        return coverage.visible <= 0 ||
                reference_lod_ready_ratio(overlay, coverage) >= REFERENCE_LOD_SWITCH_READY_RATIO ||
                minOf(
                    coverage.loaded_ratio,
                    coverage.center_loaded_ratio
                ) >= REFERENCE_PREFETCH_LOADED_RATIO
    }

    private fun reference_tile_coverage(
        overlay: ReferenceTileOverlay,
        viewport: Viewport,
        tile_zoom: Int,
        now_ms: Long
    ): ReferenceTileCoverage {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom

        var visible = 0
        var loaded = 0
        var ready = 0
        var fallback_ready = 0
        var center_visible = 0
        var center_loaded = 0
        var center_ready = 0
        var center_fallback_ready = 0
        val center_left = viewport.width * REFERENCE_CENTER_COVERAGE_LEFT_FRACTION
        val center_right = viewport.width * REFERENCE_CENTER_COVERAGE_RIGHT_FRACTION
        val center_top = viewport.height * REFERENCE_CENTER_COVERAGE_TOP_FRACTION
        val center_bottom = viewport.height * REFERENCE_CENTER_COVERAGE_BOTTOM_FRACTION
        val parent_fallback_allowed = reference_parent_fallback_allowed(overlay, viewport.zoom)
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                val covers_center = screen_x < center_right &&
                        screen_x + tile_size_on_screen > center_left &&
                        screen_y < center_bottom &&
                        screen_y + tile_size_on_screen > center_top
                visible++
                if (covers_center) center_visible++
                val key = "${overlay.cache_key}/$tile_zoom/$tx/$ty"
                val has_parent_fallback = parent_fallback_allowed &&
                        reference_parent_fallback_available(
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            overlay = overlay
                        )
                val bitmap = reference_tile_bitmap(key)
                if (bitmap != null) {
                    loaded++
                    if (covers_center) center_loaded++
                    if (reference_tile_load_alpha(key, now_ms) >= 0.999f) {
                        ready++
                        if (covers_center) center_ready++
                    } else if (has_parent_fallback) {
                        fallback_ready++
                        if (covers_center) center_fallback_ready++
                    }
                } else if (has_parent_fallback) {
                    fallback_ready++
                    if (covers_center) center_fallback_ready++
                }
            }
        }
        return ReferenceTileCoverage(
            visible = visible,
            loaded = loaded,
            ready = ready,
            fallback_ready = fallback_ready,
            center_visible = center_visible,
            center_loaded = center_loaded,
            center_ready = center_ready,
            center_fallback_ready = center_fallback_ready
        )
    }

    private fun protect_reference_tile_grid(
        overlay: ReferenceTileOverlay,
        viewport: Viewport,
        tile_zoom: Int
    ) {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                protect_visible_reference_tile("${overlay.cache_key}/$tile_zoom/$tx/$ty")
            }
        }
    }

    private fun request_reference_overlay_prefetch_grid(
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        allow_exact_requests: Boolean,
        tile_buffer: Int,
        request_generation: Long
    ) {
        if (!allow_exact_requests) {
            return
        }
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt() - tile_buffer
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt() - tile_buffer
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt() + tile_buffer
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt() + tile_buffer
        val max_tile = 1 shl tile_zoom
        val request_priority = reference_tile_request_priority(
            overlay = overlay,
            base_priority = REFERENCE_TILE_REQUEST_PRIORITY_PAN_PREFETCH
        )
        if (should_throttle_reference_pan_prefetch_grid(
                overlay = overlay,
                tile_zoom = tile_zoom,
                first_tile_x = first_tile_x,
                first_tile_y = first_tile_y,
                last_tile_x = last_tile_x,
                last_tile_y = last_tile_y
            )
        ) {
            return
        }

        var remaining_prefetch_admissions = REFERENCE_PAN_PREFETCH_MAX_ADMITTED_PER_GRID
        prefetch_loop@ for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${overlay.cache_key}/$tile_zoom/$tx/$ty"
                val memory_bitmap = reference_tile_bitmap(key)
                if (memory_bitmap != null) {
                    continue
                }
                val admission = request_reference_tile(
                    overlay = overlay,
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    cache_key = overlay.cache_key,
                    url = overlay.tile_url(tile_zoom, tx, ty),
                    user_agent = state.user_agent,
                    request_generation = request_generation,
                    request_priority = request_priority,
                    request_stale_generation_tolerance = REFERENCE_TILE_PAN_PREFETCH_STALE_GENERATIONS,
                    redraw_when_loaded = false,
                    speculative = true,
                    allow_baked_package = false
                )
                if (admission == REFERENCE_TILE_REQUEST_ADMITTED) {
                    remaining_prefetch_admissions--
                    if (remaining_prefetch_admissions <= 0) {
                        break@prefetch_loop
                    }
                }
            }
        }
    }

    private fun should_throttle_reference_pan_prefetch_grid(
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        first_tile_x: Int,
        first_tile_y: Int,
        last_tile_x: Int,
        last_tile_y: Int
    ): Boolean {
        val now_ms = SystemClock.elapsedRealtime()
        val key = "${overlay.cache_key}/$tile_zoom:$first_tile_x,$first_tile_y-$last_tile_x,$last_tile_y"
        val last_ms = last_reference_pan_prefetch_request_ms[key]
        if (last_ms != null &&
            now_ms - last_ms < REFERENCE_PAN_PREFETCH_REQUEST_THROTTLE_MS
        ) {
            return true
        }
        last_reference_pan_prefetch_request_ms[key] = now_ms
        while (
            last_reference_pan_prefetch_request_ms.size >
            REFERENCE_PAN_PREFETCH_THROTTLE_HISTORY_MAX
        ) {
            val iterator = last_reference_pan_prefetch_request_ms.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
        return false
    }

    private fun draw_reference_overlay_grid(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        now_ms: Long,
        layer_alpha: Float,
        allow_exact_requests: Boolean,
        allow_parent_requests: Boolean,
        request_generation: Long
    ): TileLayerDrawStats {
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom

        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val layer_visible = layer_alpha > MIN_LAYER_ALPHA
        val parent_request_allowed = reference_parent_request_allowed(overlay, viewport.zoom)
        val parent_fallback_allowed = reference_parent_fallback_allowed(overlay, viewport.zoom)

        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${overlay.cache_key}/$tile_zoom/$tx/$ty"
                protect_visible_reference_tile(key)
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                reference_tile_destination.set(
                    screen_x,
                    screen_y,
                    screen_x + tile_size_on_screen,
                    screen_y + tile_size_on_screen
                )
                visible++

                val bitmap = reference_tile_bitmap(key)
                if (bitmap != null) {
                    loaded++
                    if (layer_visible) {
                        val load_alpha = reference_tile_load_alpha(key, now_ms)
                        val parent_underlay_drawn =
                            if (parent_fallback_allowed && load_alpha < 0.999f) {
                                draw_reference_parent_fallback_tile(
                                    canvas = canvas,
                                    z = tile_zoom,
                                    x = tx,
                                    y = ty,
                                    overlay = overlay,
                                    destination = reference_tile_destination,
                                    alpha = layer_alpha * (1f - load_alpha)
                                )
                            } else {
                                false
                            }
                        val draw_alpha = (layer_alpha * load_alpha).coerceIn(0f, 1f)
                        if (draw_alpha > MIN_LAYER_ALPHA) {
                            draw_tile_bitmap(
                                canvas,
                                bitmap,
                                null,
                                reference_tile_destination,
                                draw_alpha
                            )
                        }
                        if (load_alpha < 0.999f || parent_underlay_drawn) fading = true
                    }
                } else {
                    requested++
                    if (allow_exact_requests) {
                        request_reference_tile(
                            overlay = overlay,
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            key = key,
                            cache_key = overlay.cache_key,
                            url = overlay.tile_url(tile_zoom, tx, ty),
                            user_agent = state.user_agent,
                            request_generation = request_generation,
                            request_priority = reference_tile_request_priority(
                                overlay = overlay,
                                base_priority = REFERENCE_TILE_REQUEST_PRIORITY_EXACT
                            ),
                            request_stale_generation_tolerance = REFERENCE_TILE_REQUEST_STALE_GENERATIONS,
                            redraw_when_loaded = true,
                            allow_baked_package =
                                false
                        )
                    }
                    if (allow_parent_requests && parent_request_allowed) {
                        request_reference_parent_tiles(
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            overlay = overlay,
                            user_agent = state.user_agent,
                            request_generation = request_generation
                        )
                    }
                    if (parent_fallback_allowed &&
                        layer_visible &&
                        draw_reference_parent_fallback_tile(
                            canvas = canvas,
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            overlay = overlay,
                            destination = reference_tile_destination,
                            alpha = layer_alpha
                        )
                    ) {
                        fallback_drawn++
                    }
                }
            }
        }

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading
        )
    }

    private fun reference_overlay_zoom_alpha(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double
    ): Float {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> {
                val zoom_alpha = REFERENCE_BOUNDARY_MIN_ALPHA +
                        (REFERENCE_BOUNDARY_MAX_ALPHA - REFERENCE_BOUNDARY_MIN_ALPHA) *
                        smooth_step(
                            REFERENCE_BOUNDARY_ZOOM_FADE_START,
                            REFERENCE_BOUNDARY_ZOOM_FADE_END,
                            viewport_zoom.toFloat()
                        )
                if (zoom_alpha >= REFERENCE_FULL_ALPHA_SNAP) 1f else zoom_alpha.coerceIn(0f, 1f)
            }

            ReferenceTileOverlay.WORLD_TRANSPORTATION -> {
                val zoom_alpha = smooth_step(
                    REFERENCE_TRANSPORTATION_FADE_START_ZOOM,
                    REFERENCE_TRANSPORTATION_FADE_END_ZOOM,
                    viewport_zoom.toFloat()
                )
                if (zoom_alpha >= REFERENCE_FULL_ALPHA_SNAP) 1f else zoom_alpha
            }
        }
    }

    private fun reference_tile_bitmap(key: String): Bitmap? {
        return synchronized(reference_tile_cache) { reference_tile_cache[key] }
    }

    private fun reference_tile_cache_has_speculative_room(key: String): Boolean {
        return synchronized(reference_tile_cache) {
            reference_tile_cache.containsKey(key) ||
                    reference_tile_cache.size < MAX_REFERENCE_MEMORY_TILES
        }
    }

    private fun reference_tile_load_alpha(key: String, now_ms: Long): Float {
        val loaded_at = reference_tile_loaded_elapsed_ms[key] ?: 0L
        return if (loaded_at > 0L) {
            smooth_step(
                0f,
                1f,
                ((now_ms - loaded_at).coerceAtLeast(0L).toFloat() / REFERENCE_TILE_FADE_MS)
            )
        } else {
            1f
        }
    }

    private fun reference_parent_fallback_allowed(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double
    ): Boolean {
        val zoom = viewport_zoom.toFloat()
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                reference_parent_fallback_depth(overlay) > 0 &&
                        zoom <= REFERENCE_BOUNDARY_PARENT_FALLBACK_MAX_ZOOM

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                reference_parent_fallback_depth(overlay) > 0 &&
                        zoom >= REFERENCE_TRANSPORTATION_FADE_START_ZOOM &&
                        zoom <= REFERENCE_TRANSPORTATION_PARENT_FALLBACK_MAX_ZOOM
        }
    }

    private fun reference_parent_request_allowed(
        overlay: ReferenceTileOverlay,
        viewport_zoom: Double
    ): Boolean {
        val zoom = viewport_zoom.toFloat()
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                zoom <= REFERENCE_BOUNDARY_PARENT_FALLBACK_MAX_ZOOM

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                zoom in REFERENCE_TRANSPORTATION_PREFETCH_START_ZOOM..REFERENCE_TRANSPORTATION_PARENT_FALLBACK_MAX_ZOOM
        }
    }

    private fun request_reference_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        overlay: ReferenceTileOverlay,
        user_agent: String,
        request_generation: Long
    ) {
        val min_parent_zoom = reference_parent_min_zoom(overlay)
        if (z <= min_parent_zoom) return
        val max_depth = (z - min_parent_zoom).coerceAtMost(reference_parent_request_depth(overlay))
        for (depth in 1..max_depth) {
            val parent_z = z - depth
            val scale = 1 shl depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${overlay.cache_key}/$parent_z/$parent_x/$parent_y"
            if (reference_tile_bitmap(parent_key) != null) continue
            request_reference_tile(
                overlay = overlay,
                z = parent_z,
                x = parent_x,
                y = parent_y,
                key = parent_key,
                cache_key = overlay.cache_key,
                url = overlay.tile_url(parent_z, parent_x, parent_y),
                user_agent = user_agent,
                request_generation = request_generation,
                request_priority = reference_tile_request_priority(
                    overlay = overlay,
                    base_priority = REFERENCE_TILE_REQUEST_PRIORITY_PARENT - depth
                ),
                request_stale_generation_tolerance = REFERENCE_TILE_PARENT_STALE_GENERATIONS,
                redraw_when_loaded = reference_parent_fallback_depth(overlay) > 0,
                allow_baked_package = false
            )
        }
    }

    private fun reference_tile_request_priority(
        overlay: ReferenceTileOverlay,
        base_priority: Int
    ): Int {
        return base_priority + when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARIES_REQUEST_PRIORITY_OFFSET
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_REQUEST_PRIORITY_OFFSET
        }
    }

    private fun reference_prefetch_request_priority_base(
        draw_tile_zoom: Int,
        prefetch_tile_zoom: Int,
        viewport_zoom: Double
    ): Int {
        val current_floor_zoom = floor(viewport_zoom).toInt()
        val is_zoom_out_prefetch = prefetch_tile_zoom < draw_tile_zoom ||
                prefetch_tile_zoom <= current_floor_zoom
        return if (is_zoom_out_prefetch) {
            REFERENCE_TILE_REQUEST_PRIORITY_ZOOM_OUT_PREFETCH
        } else {
            REFERENCE_TILE_REQUEST_PRIORITY_PREFETCH
        }
    }

    private fun request_reference_tile(
        overlay: ReferenceTileOverlay,
        z: Int,
        x: Int,
        y: Int,
        key: String,
        cache_key: String,
        url: String,
        user_agent: String,
        request_generation: Long,
        request_priority: Int,
        request_stale_generation_tolerance: Long,
        redraw_when_loaded: Boolean,
        speculative: Boolean = false,
        allow_baked_package: Boolean = false
    ): Int {
        val will_try_baked_package = allow_baked_package && false
        val baked_only_request = will_try_baked_package &&
                !REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED
        if (!will_try_baked_package && !REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED) {
            return REFERENCE_TILE_REQUEST_DENIED
        }
        mark_current_reference_tile_request(key, request_generation)
        if (reference_tile_bitmap(key) != null) return REFERENCE_TILE_REQUEST_MEMORY_HIT
        if (speculative && !reference_tile_cache_has_speculative_room(key)) {
            return REFERENCE_TILE_REQUEST_DENIED
        }
        var task_generation = request_generation
        synchronized(requested_reference_tiles) {
            if (redraw_when_loaded) requested_reference_tile_redraws += key
            val queued_generation = requested_reference_tiles[key]
            if (queued_generation != null && baked_only_request) {
                return if (queued_generation == request_generation) {
                    REFERENCE_TILE_REQUEST_QUEUED_SAME_GENERATION
                } else {
                    REFERENCE_TILE_REQUEST_QUEUED_RECENT_GENERATION
                }
            }
            if (
                queued_generation != null &&
                request_generation - queued_generation <= REFERENCE_TILE_REQUEUE_GENERATIONS
            ) {
                return if (queued_generation == request_generation) {
                    REFERENCE_TILE_REQUEST_QUEUED_SAME_GENERATION
                } else {
                    REFERENCE_TILE_REQUEST_QUEUED_RECENT_GENERATION
                }
            }
            requested_reference_tiles[key] = request_generation
            task_generation = request_generation
        }
        reference_tile_executor.execute(
            ReferenceTileRequestTask(
                generation = task_generation,
                priority = request_priority,
                sequence = reference_tile_task_sequence.incrementAndGet(),
                action = tileTask@{
                    var connection: HttpURLConnection? = null
                    var redrew_when_loaded = false
                    var skipped_request = false
                    try {
                        if (!baked_only_request &&
                            should_skip_stale_reference_tile_request(
                                key,
                                request_stale_generation_tolerance
                            )
                        ) {
                            skipped_request = true
                            return@tileTask
                        }
                        if (!baked_only_request &&
                            should_skip_superseded_reference_tile_request(key, task_generation)
                        ) {
                            skipped_request = true
                            return@tileTask
                        }
                        if (reference_tile_bitmap(key) != null) {
                            skipped_request = true
                            return@tileTask
                        }
                        if (speculative && !reference_tile_cache_has_speculative_room(key)) {
                            skipped_request = true
                            return@tileTask
                        }
                        var usable_disk_reference_tile = false
                        val file = reference_tile_file(z, x, y, cache_key)
                        if (REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED &&
                            file.exists() &&
                            file.length() > 0L
                        ) {
                            val fresh =
                                System.currentTimeMillis() - file.lastModified() < REFERENCE_TILE_CACHE_MAX_AGE_MS
                            val bitmap =
                                BitmapFactory.decodeFile(file.absolutePath, decode_options())
                            if (bitmap != null && reference_overlay_tile_is_safe(bitmap)) {
                                usable_disk_reference_tile = true
                                val published = put_reference_tile_in_memory_if_allowed(
                                    key = key,
                                    bitmap = bitmap,
                                    speculative = speculative
                                )
                                if (!published) {
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                    skipped_request = true
                                    return@tileTask
                                }
                                if (should_redraw_reference_tile_request(key, redraw_when_loaded)) {
                                    redrew_when_loaded = true
                                    request_redraw()
                                }
                                if (fresh) return@tileTask
                            } else {
                                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                                file.delete()
                            }
                        }
                        if (!REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED) {
                            skipped_request = true
                            return@tileTask
                        }
                        val tile_url = https_url(url) ?: run {
                            report_status("Map labels unavailable")
                            return@tileTask
                        }
                        connection = (tile_url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 10000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", user_agent)
                        }
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val bytes = connection.inputStream.use { it.readBytes() }
                            val bitmap = BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size,
                                decode_options()
                            )
                            if (bitmap != null && reference_overlay_tile_is_safe(bitmap)) {
                                val published = put_reference_tile_in_memory_if_allowed(
                                    key = key,
                                    bitmap = bitmap,
                                    speculative = speculative
                                )
                                if (!published) {
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                    skipped_request = true
                                    return@tileTask
                                }
                                if (should_redraw_reference_tile_request(key, redraw_when_loaded)) {
                                    redrew_when_loaded = true
                                    request_redraw()
                                }
                            } else {
                                bitmap?.takeIf { !it.isRecycled }?.recycle()
                            }
                        } else {
                            connection.errorStream?.close()
                            report_status("Map labels unavailable")
                        }
                    } catch (_: Exception) {
                        report_status("Map labels unavailable")
                    } finally {
                        connection?.disconnect()
                        val should_redraw_after_task =
                            should_redraw_reference_tile_request(key, redraw_when_loaded)
                        synchronized(requested_reference_tiles) {
                            if ((requested_reference_tiles[key]
                                    ?: Long.MIN_VALUE) <= task_generation
                            ) {
                                requested_reference_tiles.remove(key)
                                requested_reference_tile_redraws.remove(key)
                            }
                        }
                        if (!redrew_when_loaded &&
                            !skipped_request &&
                            should_redraw_after_task
                        ) {
                            request_redraw()
                        }
                    }
                })
        )
        return REFERENCE_TILE_REQUEST_ADMITTED
    }

    private fun begin_reference_tile_request_generation(): Long {
        synchronized(requested_reference_tiles) {
            current_reference_tile_request_generation++
            if (current_reference_tile_request_generations.size > REFERENCE_TILE_REQUEST_HISTORY_MAX) {
                val stale_before =
                    current_reference_tile_request_generation - REFERENCE_TILE_REQUEST_HISTORY_AGE
                val iterator = current_reference_tile_request_generations.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value < stale_before) {
                        iterator.remove()
                    }
                }
                val overflow_count =
                    current_reference_tile_request_generations.size - REFERENCE_TILE_REQUEST_HISTORY_MAX
                if (overflow_count > 0) {
                    val oldest_keys = current_reference_tile_request_generations.entries
                        .sortedBy { it.value }
                        .take(overflow_count)
                        .map { it.key }
                    for (oldest_key in oldest_keys) {
                        current_reference_tile_request_generations.remove(oldest_key)
                    }
                }
            }
            return current_reference_tile_request_generation
        }
    }

    private fun mark_current_reference_tile_request(key: String, generation: Long) {
        synchronized(requested_reference_tiles) {
            val previous_generation = current_reference_tile_request_generations[key]
            if (previous_generation == null || generation >= previous_generation) {
                current_reference_tile_request_generations[key] = generation
            }
        }
    }

    private fun should_skip_stale_reference_tile_request(
        key: String,
        max_generation_age: Long
    ): Boolean {
        synchronized(requested_reference_tiles) {
            val generation = current_reference_tile_request_generations[key] ?: return true
            return current_reference_tile_request_generation - generation > max_generation_age
        }
    }

    private fun should_redraw_reference_tile_request(key: String, fallback: Boolean): Boolean {
        synchronized(requested_reference_tiles) {
            return fallback || requested_reference_tile_redraws.contains(key)
        }
    }

    private fun should_skip_superseded_reference_tile_request(
        key: String,
        task_generation: Long
    ): Boolean {
        synchronized(requested_reference_tiles) {
            val queued_generation = requested_reference_tiles[key] ?: return false
            return queued_generation > task_generation
        }
    }

    private fun put_reference_tile_in_memory(key: String, bitmap: Bitmap) {
        val previous = reference_tile_cache[key]
        reference_tile_cache[key] = bitmap
        if (previous !== bitmap) map_content_revision.incrementAndGet()
        reference_tile_loaded_elapsed_ms.remove(key)
        while (reference_tile_cache.size > MAX_REFERENCE_MEMORY_TILES) {
            val first_key =
                reference_tile_cache.keys.firstOrNull { !is_protected_reference_tile(it) }
                    ?: reference_tile_cache.keys.firstOrNull()
                    ?: break
            reference_tile_cache.remove(first_key)
            reference_tile_loaded_elapsed_ms.remove(first_key)
        }
    }

    private fun put_reference_tile_in_memory_if_allowed(
        key: String,
        bitmap: Bitmap,
        speculative: Boolean
    ): Boolean {
        synchronized(reference_tile_cache) {
            if (
                speculative &&
                !reference_tile_cache.containsKey(key) &&
                reference_tile_cache.size >= MAX_REFERENCE_MEMORY_TILES
            ) {
                return false
            }
            put_reference_tile_in_memory(key, bitmap)
            return true
        }
    }

    private fun begin_reference_tile_protection_frame() {
        synchronized(protected_reference_tile_keys) {
            previous_protected_reference_tile_keys.clear()
            previous_protected_reference_tile_keys.addAll(protected_reference_tile_keys)
            protected_reference_tile_keys.clear()
        }
    }

    private fun protect_visible_reference_tile(key: String) {
        synchronized(protected_reference_tile_keys) {
            protected_reference_tile_keys += key
        }
    }

    private fun is_protected_reference_tile(key: String): Boolean {
        return synchronized(protected_reference_tile_keys) {
            protected_reference_tile_keys.contains(key) ||
                    previous_protected_reference_tile_keys.contains(key)
        }
    }

    private fun reference_tile_file(z: Int, x: Int, y: Int, cache_key: String): File {
        return File(context.cacheDir, "${cache_key}_tiles/$z/$x/$y.png")
    }

    private fun reference_overlay_tile_is_safe(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val sample_columns =
            REFERENCE_ALPHA_SAMPLE_COLUMNS.coerceAtMost(bitmap.width).coerceAtLeast(1)
        val sample_rows = REFERENCE_ALPHA_SAMPLE_ROWS.coerceAtMost(bitmap.height).coerceAtLeast(1)
        var samples = 0
        var mostly_opaque_samples = 0
        var visible_alpha_samples = 0
        for (row in 0 until sample_rows) {
            val y =
                if (sample_rows == 1) bitmap.height / 2 else (row * (bitmap.height - 1) / (sample_rows - 1))
            for (col in 0 until sample_columns) {
                val x =
                    if (sample_columns == 1) bitmap.width / 2 else (col * (bitmap.width - 1) / (sample_columns - 1))
                val alpha = Color.alpha(bitmap[x, y])
                if (alpha >= REFERENCE_OPAQUE_ALPHA_THRESHOLD) mostly_opaque_samples++
                if (alpha >= REFERENCE_VISIBLE_ALPHA_THRESHOLD) visible_alpha_samples++
                samples++
            }
        }
        if (samples == 0) return true
        val sample_count = samples.toFloat()
        return mostly_opaque_samples.toFloat() < sample_count * REFERENCE_OPAQUE_SAMPLE_REJECT_FRACTION &&
                visible_alpha_samples.toFloat() < sample_count * REFERENCE_VISIBLE_SAMPLE_REJECT_FRACTION
    }

    private fun draw_reference_parent_fallback_tile(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        overlay: ReferenceTileOverlay,
        destination: RectF,
        alpha: Float
    ): Boolean {
        if (alpha < REFERENCE_PARENT_FALLBACK_MIN_ALPHA) return false
        val parent = reference_parent_tile_fallback(z, x, y, overlay) ?: return false
        draw_tile_bitmap(canvas, parent.bitmap, parent.source_rect, destination, alpha)
        return true
    }

    private fun reference_parent_fallback_available(
        z: Int,
        x: Int,
        y: Int,
        overlay: ReferenceTileOverlay
    ): Boolean {
        return reference_parent_tile_fallback(z, x, y, overlay) != null
    }

    private fun reference_parent_tile_fallback(
        z: Int,
        x: Int,
        y: Int,
        overlay: ReferenceTileOverlay
    ): TileFallback? {
        val min_parent_zoom = reference_parent_min_zoom(overlay)
        if (z <= min_parent_zoom) return null
        val max_depth = (z - min_parent_zoom).coerceAtMost(reference_parent_fallback_depth(overlay))
        if (max_depth <= 0) return null
        for (depth in 1..max_depth) {
            val fallback_z = z - depth
            val scale = 1 shl depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${overlay.cache_key}/$fallback_z/$parent_x/$parent_y"
            val bitmap = reference_tile_bitmap(parent_key) ?: continue
            protect_visible_reference_tile(parent_key)
            val src_width = (bitmap.width / scale).coerceAtLeast(1)
            val src_height = (bitmap.height / scale).coerceAtLeast(1)
            val child_x = x % scale
            val child_y = y % scale
            val left = (child_x * src_width).coerceIn(0, bitmap.width - 1)
            val top = (child_y * src_height).coerceIn(0, bitmap.height - 1)
            return TileFallback(
                bitmap = bitmap,
                source_rect = Rect(
                    left,
                    top,
                    (left + src_width).coerceAtMost(bitmap.width),
                    (top + src_height).coerceAtMost(bitmap.height)
                )
            )
        }
        return null
    }

    private fun reference_parent_min_zoom(overlay: ReferenceTileOverlay): Int {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> MIN_ZOOM
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_MIN_ZOOM
        }
    }

    private fun reference_parent_request_depth(overlay: ReferenceTileOverlay): Int {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_PARENT_REQUEST_DEPTH
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_PARENT_REQUEST_DEPTH
        }
    }

    private fun reference_parent_fallback_depth(overlay: ReferenceTileOverlay): Int {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> REFERENCE_BOUNDARY_PARENT_FALLBACK_DEPTH
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> REFERENCE_TRANSPORTATION_PARENT_FALLBACK_DEPTH
        }
    }


    private fun draw_tile_bitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        source: Rect?,
        destination: RectF,
        alpha: Float
    ) {
        bitmap_paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, source, destination, bitmap_paint)
        bitmap_paint.alpha = 255
    }

    private fun decode_options(): BitmapFactory.Options {
        val options = tile_decode_options.get() ?: BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        options.inSampleSize = 1
        options.inJustDecodeBounds = false
        return options
    }


    private fun https_url(value: String): URL? = throwable_safe_https_url(value)

    private class ReferenceTileRequestTask(
        private val generation: Long,
        private val priority: Int,
        private val sequence: Long,
        private val action: () -> Unit
    ) : Runnable, Comparable<Runnable> {
        override fun run() = action()

        override fun compareTo(other: Runnable): Int {
            val task = other as? ReferenceTileRequestTask ?: return -1
            if (priority != task.priority) return priority.compareTo(task.priority)
            if (generation != task.generation) return task.generation.compareTo(generation)
            return sequence.compareTo(task.sequence)
        }
    }

    private data class ReferenceLodCrossfade(
        val from_tile_zoom: Int,
        val to_tile_zoom: Int,
        val start_ms: Long,
        val duration_ms: Float
    )


    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_REFERENCE_MEMORY_TILES = 768
        const val REFERENCE_TILE_CACHE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val REFERENCE_TILE_FADE_MS = 280f
        const val REFERENCE_BOUNDARY_LOD_CROSSFADE_MS = 320f
        const val REFERENCE_TRANSPORTATION_LOD_CROSSFADE_MS = 260f
        const val REFERENCE_MOTION_COVERAGE_ALPHA_HOLD_MS = 450L
        const val REFERENCE_MOTION_COVERAGE_ALPHA_DECAY_MS = 360f
        const val REFERENCE_TILE_NETWORK_THREADS = 4
        const val REFERENCE_LOD_PREFETCH_REQUEST_THROTTLE_MS = 120L
        const val REFERENCE_PAN_PREFETCH_REQUEST_THROTTLE_MS = 120L
        const val REFERENCE_PAN_PREFETCH_THROTTLE_HISTORY_MAX = 96
        const val REFERENCE_LOD_PREFETCH_MAX_ADMITTED_PER_BATCH = 96
        const val REFERENCE_PAN_PREFETCH_MAX_ADMITTED_PER_GRID = 24
        const val REFERENCE_LOD_SWITCH_ALPHA = 0.5f
        const val REFERENCE_PARENT_FALLBACK_MIN_ALPHA = 0.03f
        const val REFERENCE_TRANSPORTATION_MIN_ZOOM = 6
        const val REFERENCE_TRANSPORTATION_PREFETCH_START_ZOOM = 5.85f
        const val REFERENCE_FULL_ALPHA_SNAP = 0.985f
        const val REFERENCE_BOUNDARY_MIN_ALPHA = 0.62f
        const val REFERENCE_BOUNDARY_MAX_ALPHA = 1f
        const val REFERENCE_BOUNDARY_ZOOM_FADE_START = 3.2f
        const val REFERENCE_BOUNDARY_ZOOM_FADE_END = 6.8f
        const val REFERENCE_BOUNDARY_PARENT_FALLBACK_MAX_ZOOM = 16.2f
        const val REFERENCE_TRANSPORTATION_FADE_START_ZOOM = 6.0f
        const val REFERENCE_TRANSPORTATION_FADE_END_ZOOM = 7.2f
        const val REFERENCE_TRANSPORTATION_PARENT_FALLBACK_MAX_ZOOM = 16.2f
        const val REFERENCE_TILE_REQUEST_PRIORITY_EXACT = 0
        const val REFERENCE_TILE_REQUEST_PRIORITY_ZOOM_OUT_PREFETCH = 1
        const val REFERENCE_TILE_REQUEST_PRIORITY_PAN_PREFETCH = 1
        const val REFERENCE_TILE_REQUEST_PRIORITY_PARENT = 2
        const val REFERENCE_TILE_REQUEST_PRIORITY_PREFETCH = 3
        const val REFERENCE_TILE_REQUEST_MEMORY_HIT = 0
        const val REFERENCE_TILE_REQUEST_ADMITTED = 2
        const val REFERENCE_TILE_REQUEST_QUEUED_SAME_GENERATION = 3
        const val REFERENCE_TILE_REQUEST_QUEUED_RECENT_GENERATION = 4
        const val REFERENCE_TILE_REQUEST_DENIED = 5
        const val REFERENCE_BOUNDARIES_REQUEST_PRIORITY_OFFSET = 0
        const val REFERENCE_TRANSPORTATION_REQUEST_PRIORITY_OFFSET = 0
        const val REFERENCE_BOUNDARY_PARENT_REQUEST_DEPTH = 4
        const val REFERENCE_TRANSPORTATION_PARENT_REQUEST_DEPTH = 7
        const val REFERENCE_BOUNDARY_PARENT_FALLBACK_DEPTH = 4
        const val REFERENCE_TRANSPORTATION_PARENT_FALLBACK_DEPTH = 4
        const val REFERENCE_BOUNDARY_COARSE_LOD_DEPTH = 2
        const val REFERENCE_TRANSPORTATION_COARSE_LOD_DEPTH = 2
        const val REFERENCE_BOUNDARY_MAX_TILE_UPSCALE_DELTA = 1.65
        const val REFERENCE_TRANSPORTATION_MAX_TILE_UPSCALE_DELTA = 1.55
        const val REFERENCE_BOUNDARY_RETAINED_MAX_DETAIL_DELTA = 0.75
        const val REFERENCE_TRANSPORTATION_RETAINED_MAX_DETAIL_DELTA = 0.75
        const val REFERENCE_BOUNDARY_DRAW_MIN_LOADED_RATIO = 0.84f
        const val REFERENCE_BOUNDARY_DRAW_FULL_LOADED_RATIO = 0.98f
        const val REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_OFFSET = 3
        const val REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_STEP = 2
        const val REFERENCE_BOUNDARY_ZOOM_OUT_PREFETCH_COUNT = 4
        const val REFERENCE_BOUNDARY_COUNTY_DETAIL_FROM_TILE_ZOOM = 8
        const val REFERENCE_BOUNDARY_COUNTY_DETAIL_TO_TILE_ZOOM = 9
        const val REFERENCE_TRANSPORTATION_DETAIL_ADVANCE = 0.28
        const val REFERENCE_TRANSPORTATION_DRAW_MIN_LOADED_RATIO = 0.9f
        const val REFERENCE_TRANSPORTATION_DRAW_FULL_LOADED_RATIO = 0.99f
        const val REFERENCE_TRANSPORTATION_RELAXED_DRAW_START_ZOOM = 7.2f
        const val REFERENCE_TRANSPORTATION_MID_DRAW_MIN_LOADED_RATIO = 0.46f
        const val REFERENCE_TRANSPORTATION_MID_DRAW_FULL_LOADED_RATIO = 0.82f
        const val REFERENCE_TRANSPORTATION_CENTER_COVERAGE_FLOOR = 0.55f
        const val REFERENCE_TRANSPORTATION_CLOSE_PAN_KEEP_VISIBLE_ZOOM = 12.0f
        const val REFERENCE_TRANSPORTATION_CLOSE_PAN_DRAW_MIN_LOADED_RATIO = 0.2f
        const val REFERENCE_TRANSPORTATION_CLOSE_PAN_DRAW_FULL_LOADED_RATIO = 0.55f
        const val REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_OFFSET = 1
        const val REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_STEP = 1
        const val REFERENCE_TRANSPORTATION_ZOOM_OUT_PREFETCH_COUNT = 5
        const val REFERENCE_TRANSPORTATION_ZOOM_IN_PREFETCH_COUNT = 2
        const val REFERENCE_BOUNDARY_PAN_PREFETCH_START_ZOOM = 4.0f
        const val REFERENCE_TRANSPORTATION_PAN_PREFETCH_START_ZOOM = 7.2f
        const val REFERENCE_OVERLAY_PAN_PREFETCH_TILE_BUFFER = 1
        const val REFERENCE_CLOSE_PAN_TARGET_LOD_HOLD_ZOOM = 11.5f
        const val REFERENCE_RETAINED_LOD_MIN_RATIO = 0.8f
        const val REFERENCE_LOD_SWITCH_READY_RATIO = 0.92f
        const val REFERENCE_LOD_SWITCH_COMMIT_RATIO = 1f
        const val REFERENCE_BOUNDARY_PREFETCH_LOD_LIMIT = 1
        const val REFERENCE_TRANSPORTATION_PREFETCH_LOD_LIMIT = 1
        const val REFERENCE_PREFETCH_LOADED_RATIO = 0.86f
        const val REFERENCE_TILE_REQUEST_HISTORY_MAX = 2_400
        const val REFERENCE_RASTER_REFERENCE_SOURCES_ENABLED = false
        const val REFERENCE_TILE_REQUEST_HISTORY_AGE = 48L
        const val REFERENCE_TILE_REQUEST_STALE_GENERATIONS = 2L
        const val REFERENCE_TILE_PREFETCH_STALE_GENERATIONS = 36L
        const val REFERENCE_TILE_PAN_PREFETCH_STALE_GENERATIONS = 24L
        const val REFERENCE_TILE_PARENT_STALE_GENERATIONS = 36L
        const val REFERENCE_TILE_REQUEUE_GENERATIONS = 3L
        const val REFERENCE_CENTER_COVERAGE_LEFT_FRACTION = 0.18f
        const val REFERENCE_CENTER_COVERAGE_RIGHT_FRACTION = 0.82f
        const val REFERENCE_CENTER_COVERAGE_TOP_FRACTION = 0.2f
        const val REFERENCE_CENTER_COVERAGE_BOTTOM_FRACTION = 0.84f
        const val REFERENCE_ALPHA_SAMPLE_COLUMNS = 8
        const val REFERENCE_ALPHA_SAMPLE_ROWS = 8
        const val REFERENCE_VISIBLE_ALPHA_THRESHOLD = 12
        const val REFERENCE_VISIBLE_SAMPLE_REJECT_FRACTION = 0.78f
        const val REFERENCE_OPAQUE_ALPHA_THRESHOLD = 248
        const val REFERENCE_OPAQUE_SAMPLE_REJECT_FRACTION = 0.95f
        const val MIN_LAYER_ALPHA = 0.01f
        const val SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS = 15_000L
    }
}
