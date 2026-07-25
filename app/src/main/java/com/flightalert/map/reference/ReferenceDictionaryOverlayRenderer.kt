@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "ArrayInDataClass",
)

package com.flightalert.map.reference

import com.flightalert.map.*
import com.flightalert.map.reference.borders.*
import com.flightalert.map.reference.dictionary.*
import com.flightalert.map.reference.labels.*

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.graphics.withSave
import com.flightalert.MAX_ZOOM
import com.flightalert.MIN_ZOOM
import com.flightalert.map.tiles.MapTileDiskCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class ReferenceDictionaryOverlayDrawStats(
    val available: Boolean,
    val ready: Boolean,
    val visible_tiles: Int,
    val loaded_tiles: Int,
    val requested_tiles: Int,
    val boundaries_drawn: Int,
    val labels_drawn: Int,
    val retained_frame_drawn: Boolean,
    val fading: Boolean
)

internal class ReferenceDictionaryOverlayRenderer(
    context: Context,
    private val map_tile_disk_cache: MapTileDiskCache,
    private val paint: Paint,
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val request_redraw: () -> Unit
) : Closeable {
    private val tile_repository = ReferenceTileRepository(context, request_redraw)
    private val line_label_path = Path()
    private val path_measure = PathMeasure()
    private val line_label_bounds = RectF()
    private val line_label_position = FloatArray(2)
    private val visible_tiles = ArrayList<VisibleDictionaryTile>(32)
    private val fallback_visible_tiles = ArrayList<VisibleDictionaryTile>(16)
    private val zoom_ahead_visible_tiles = ArrayList<VisibleDictionaryTile>(16)
    private val draw_tiles = ArrayList<DictionaryTileDrawRef>(32)
    private val core_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    private val fallback_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    private val zoom_ahead_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    private val ui_label_workspace = LabelPlanningWorkspace(text_paint)
    private val retained_label_workspace = LabelPlanningWorkspace(
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG),
    )
    private val retained_destination = ReferenceRetainedDestination()
    private val fallback_destination = ReferenceRetainedDestination()
    private val fading_destination = ReferenceRetainedDestination()
    private val candidate_destination = ReferenceRetainedDestination()
    @Volatile
    private var closed = false
    private val lifecycle_lock = ReentrantLock()
    private val retained_bitmap_executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            runnable.run()
        }, "flightalert-reference-bitmap").apply {
            isDaemon = true
        }
    }
    private val retained_label_executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-labels").apply {
            isDaemon = true
        }
    }
    private val retained_bitmap_coordinator = ReferenceRetainedBitmapCoordinator<
        RetainedBoundaryBitmapKey,
        PreparedRetainedBoundaryBitmap
        > {}
    private val retained_label_coordinator = ReferenceRetainedBitmapCoordinator<
        RetainedLabelPlanKey,
        PreparedRetainedLabelFrame
        > {}
    @Volatile
    private var failed_retained_bitmap_key: RetainedBoundaryBitmapKey? = null
    @Volatile
    private var retained_bitmap_retry_after_ms = 0L
    private val boundary_density = dp(1f)
    private val label_scaled_density = sp(1f)
    private val boundary_renderer = ReferenceBoundaryRenderer(
        context = context,
        map_tile_disk_cache = map_tile_disk_cache,
        paint = paint,
        boundary_density = boundary_density,
        request_redraw = request_redraw,
        tile_repository = tile_repository,
        retained_scene_padding_px = ::retained_scene_padding_for_viewport,
    )
    private var retained_frame: RetainedReferenceFrame? = null
    private var retained_fallback_frame: RetainedReferenceFrame? = null
    private var partial_label_frame: RetainedReferenceFrame? = null
    private var retained_fade: RetainedReferenceFade? = null
    private var displayed_retained_frame: RetainedReferenceFrame? = null
    private var retained_label_interaction_active = false
    private val retained_frame_history =
        ReferenceRetainedSceneHistory<RetainedReferenceFrame>(RETAINED_FRAME_HISTORY_LIMIT)

    fun draw(
        canvas: Canvas,
        viewport: Viewport,
        labels_enabled: Boolean,
        borders_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_state: FilterState,
        interaction_active: Boolean,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): ReferenceDictionaryOverlayDrawStats {
        if (!labels_enabled && !borders_enabled) {
            return ready_empty_stats()
        }
        val snapshot = tile_repository.snapshot()
        if (snapshot == null) {
            tile_repository.prepare()
            return unavailable_stats()
        }
        val package_info = snapshot.info
        val filter_mask = ReferenceFilterMask.from(filter_state)
        val tile_zoom = dictionary_tile_zoom(viewport.zoom, package_info.zooms)
            ?: return unavailable_stats()
        val fallback_zoom = ReferenceDictionaryLodPolicy.select_fallback(
            viewport.zoom,
            tile_zoom,
            package_info.zooms,
        )
        val interaction_started = interaction_active && !retained_label_interaction_active
        val now_ms = SystemClock.elapsedRealtime()
        val options = RetainedReferenceOptions(
            labels_enabled = labels_enabled,
            borders_enabled = borders_enabled,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask_key = filter_mask.cacheKey,
            label_text_scale_key = (label_text_scale * 1000f).roundToInt()
        )
        finish_completed_retained_label_frame(
            viewport = viewport,
            target_tile_zoom = tile_zoom,
            fallback_tile_zoom = fallback_zoom,
            options = options,
            label_avoid_rects = label_avoid_rects,
            interaction_active = interaction_active,
        )
        if (interaction_started) {
            retained_label_coordinator.cancel()
            tile_repository.clear_zoom_ahead_desired_tile_keys()
        }
        retained_label_interaction_active = interaction_active
        val groups = ReferenceDictionaryLayerGroups(
            places = place_labels_enabled,
            water = water_labels_enabled,
            regions = region_labels_enabled,
            public_lands = public_lands_enabled,
        )
        val settled_target = retained_frame
        val fallback_needs_build = retained_fallback_needs_build(
            canvas = canvas,
            viewport = viewport,
            options = options,
            target_tile_zoom = tile_zoom,
            fallback_tile_zoom = fallback_zoom,
        )
        val settled_target_ready = !interaction_active &&
            settled_target != null && settled_target.is_drawable() &&
            settled_target.matches_environment(
                viewport = viewport,
                next = options,
                current_package_generation = tile_repository.package_generation(),
            ) &&
            retained_target_frame_can_settle(
                frame = settled_target,
                viewport = viewport,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = retained_destination,
            )
        var zoom_ahead_requests = 0
        if (settled_target_ready &&
            !reference_retained_scene_should_stage(
                destination = retained_destination,
                viewport = viewport,
                repeatingWorldWidthPx = retained_repeating_world_width_px(
                    viewport,
                    tile_zoom,
                ),
            )
        ) {
            zoom_ahead_requests = stage_zoom_ahead_labels(
                viewport = viewport,
                source_zoom = tile_zoom,
                labels_enabled = labels_enabled,
                label_text_scale = label_text_scale,
                place_labels_enabled = place_labels_enabled,
                water_labels_enabled = water_labels_enabled,
                region_labels_enabled = region_labels_enabled,
                public_lands_enabled = public_lands_enabled,
                filter_mask = filter_mask,
                label_avoid_rects = label_avoid_rects,
                options = options,
            )
        }
        if (settled_target_ready && !fallback_needs_build) {
            val successor_needed =
                reference_retained_scene_should_stage(
                    destination = retained_destination,
                    viewport = viewport,
                    repeatingWorldWidthPx = retained_repeating_world_width_px(
                        viewport,
                        tile_zoom,
                    ),
                ) &&
                    !retained_history_has_exact_target(viewport, tile_zoom, options) &&
                    !retained_label_coordinator.hasCurrentRequest()
            if (!successor_needed) {
                val boundary_raster = if (borders_enabled) {
                    boundary_renderer.draw(
                        canvas = canvas,
                        viewport = viewport,
                        source_zoom = tile_zoom,
                        groups = groups,
                        filter_mask = filter_mask,
                        interaction_active = false,
                        tile_request_generation = tile_repository.request_generation(),
                        now_ms = now_ms,
                    )
                } else {
                    BoundaryRasterDrawResult(0, true, 0)
                }
                draw_retained_frame_with_transition(
                    canvas = canvas,
                    viewport = viewport,
                    frame = settled_target,
                    now_ms = now_ms,
                    destination = retained_destination,
                )
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = boundary_raster.ready,
                    visible_tiles = 0,
                    loaded_tiles = 0,
                    requested_tiles = boundary_raster.requested + zoom_ahead_requests,
                    boundaries_drawn = boundary_raster.boundaries,
                    labels_drawn = settled_target.labels_drawn,
                    retained_frame_drawn = true,
                    fading = retained_fade != null,
                )
            }
        }
        val retained_for_interaction = if (interaction_active && labels_enabled) {
            retained_frame_for_viewport(
                canvas = canvas,
                viewport = viewport,
                options = options,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = retained_destination,
            )
        } else {
            null
        }
        val compatible_active_boundary_band = borders_enabled &&
            boundary_renderer.has_compatible_active_band(
                groups = groups,
                filterMask = filter_mask,
                packageGeneration = tile_repository.package_generation(),
            )
        if (reference_should_defer_target_work(
                interactionActive = interaction_active,
                labelsEnabled = labels_enabled,
                retainedLabelsAvailable = retained_for_interaction != null,
                bordersEnabled = borders_enabled,
                compatibleActiveBoundaryBand = compatible_active_boundary_band,
            )
        ) {
            val generation = tile_repository.request_generation()
            val boundary_raster = if (borders_enabled) {
                boundary_renderer.draw(
                    canvas = canvas,
                    viewport = viewport,
                    source_zoom = tile_zoom,
                    groups = groups,
                    filter_mask = filter_mask,
                    interaction_active = true,
                    tile_request_generation = generation,
                    freeze_active_band = true,
                    now_ms = now_ms,
                )
            } else {
                BoundaryRasterDrawResult(0, true, 0)
            }
            if (retained_for_interaction != null) {
                draw_retained_frame_with_transition(
                    canvas = canvas,
                    viewport = viewport,
                    frame = retained_for_interaction,
                    now_ms = now_ms,
                    destination = retained_destination,
                )
            }
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = boundary_raster.ready,
                visible_tiles = 0,
                loaded_tiles = 0,
                requested_tiles = boundary_raster.requested,
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = retained_for_interaction?.labels_drawn ?: 0,
                retained_frame_drawn = retained_for_interaction != null,
                fading = retained_for_interaction != null && retained_fade != null,
            )
        }
        val draw_viewport = if (interaction_active) {
            viewport
        } else {
            retained_frame_viewport(viewport, tile_zoom)
        }
        build_visible_tile_list(visible_tiles, draw_viewport, viewport, tile_zoom)
        if (visible_tiles.isEmpty()) return ready_empty_stats()
        val fallback_viewport = if (fallback_needs_build) {
            if (interaction_active) {
                viewport
            } else {
                retained_frame_viewport(viewport, requireNotNull(fallback_zoom))
            }
        } else {
            null
        }
        if (fallback_viewport != null) {
            build_visible_tile_list(
                fallback_visible_tiles,
                fallback_viewport,
                viewport,
                requireNotNull(fallback_zoom),
            )
        } else {
            fallback_visible_tiles.clear()
        }
        val generation = tile_repository.update_desired_tile_keys(
            target_tiles = visible_tiles,
            fallback_tiles = fallback_visible_tiles,
            include_target_tiles = true,
        )

        fallback_draw_tiles.clear()
        var fallback_missing = 0
        var fallback_prefetch_requests = 0
        var requested = zoom_ahead_requests
        for (tile in fallback_visible_tiles) {
            val parsed = tile_repository.tile(tile)
            if (parsed != null) {
                fallback_draw_tiles += DictionaryTileDrawRef(
                    tile = parsed,
                    draw_x = tile.draw_x,
                    core_visible = tile.core_visible,
                )
            } else {
                fallback_missing++
                if (fallback_prefetch_requests < FALLBACK_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    val queued = tile_repository.request_tile_if_needed(tile, generation)
                    requested += queued
                    fallback_prefetch_requests += queued
                }
            }
        }

        draw_tiles.clear()
        core_draw_tiles.clear()
        var missing = 0
        var core_missing = 0
        var padded_prefetch_requests = 0
        for (tile in visible_tiles) {
            val parsed = tile_repository.tile(tile)
            if (parsed != null) {
                val reference = DictionaryTileDrawRef(
                    tile = parsed,
                    draw_x = tile.draw_x,
                    core_visible = tile.core_visible,
                )
                draw_tiles += reference
                if (tile.core_visible) core_draw_tiles += reference
            } else {
                missing++
                if (tile.core_visible) {
                    core_missing++
                    requested += tile_repository.request_tile_if_needed(tile, generation)
                } else if (padded_prefetch_requests < PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    val queued = tile_repository.request_tile_if_needed(tile, generation)
                    requested += queued
                    padded_prefetch_requests += queued
                }
            }
        }

        val ready = missing == 0
        val fallback_ready = fallback_visible_tiles.isNotEmpty() && fallback_missing == 0
        val boundary_raster = if (borders_enabled) {
            boundary_renderer.draw(
                canvas = canvas,
                viewport = viewport,
                source_zoom = tile_zoom,
                groups = groups,
                filter_mask = filter_mask,
                interaction_active = interaction_active,
                tile_request_generation = generation,
                now_ms = now_ms,
            )
        } else {
            BoundaryRasterDrawResult(0, true, 0)
        }
        requested += boundary_raster.requested
        val layer_ready = ready && boundary_raster.ready
        val retained_drawn_for_interaction = if (interaction_active) {
            draw_retained_frame_if_available(
                canvas,
                viewport,
                options,
                tile_zoom,
                fallback_zoom,
                now_ms,
            )
        } else {
            null
        }
        if (interaction_active) {
            val direct_counts = if (retained_drawn_for_interaction == null) {
                val direct_tiles = when {
                    core_missing == 0 && core_draw_tiles.isNotEmpty() -> core_draw_tiles
                    fallback_ready -> fallback_draw_tiles
                    else -> emptyList()
                }
                if (direct_tiles.isEmpty()) null else draw_reference_content(
                    canvas = canvas,
                    viewport = viewport,
                    tiles = direct_tiles,
                    labels_enabled = labels_enabled,
                    borders_enabled = false,
                    label_text_scale = label_text_scale,
                    place_labels_enabled = place_labels_enabled,
                    water_labels_enabled = water_labels_enabled,
                    region_labels_enabled = region_labels_enabled,
                    public_lands_enabled = public_lands_enabled,
                    filter_mask = filter_mask,
                    label_avoid_rects = label_avoid_rects,
                )
            } else null
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = layer_ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = retained_drawn_for_interaction?.labels_drawn
                    ?: direct_counts?.labels ?: 0,
                retained_frame_drawn = retained_drawn_for_interaction != null,
                fading = retained_drawn_for_interaction != null && retained_fade != null,
            )
        }
        if (!ready && !(fallback_needs_build && fallback_ready)) {
            val retained = draw_retained_frame_if_available(
                canvas,
                viewport,
                options,
                tile_zoom,
                fallback_zoom,
                now_ms,
            )
            if (retained != null) {
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = layer_ready,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = maxOf(requested, missing),
                    boundaries_drawn = boundary_raster.boundaries,
                    labels_drawn = retained.labels_drawn,
                    retained_frame_drawn = true,
                    fading = retained_fade != null,
                )
            }
        }

        if (fallback_needs_build && fallback_ready && !ready) {
            request_retained_label_frame(
                role = RetainedFrameRole.FALLBACK,
                viewport = viewport,
                frame_viewport = requireNotNull(fallback_viewport),
                tile_zoom = requireNotNull(fallback_zoom),
                tiles = fallback_draw_tiles,
                labels_enabled = labels_enabled,
                label_text_scale = label_text_scale,
                place_labels_enabled = place_labels_enabled,
                water_labels_enabled = water_labels_enabled,
                region_labels_enabled = region_labels_enabled,
                public_lands_enabled = public_lands_enabled,
                filter_mask = filter_mask,
                label_avoid_rects = label_avoid_rects,
                options = options,
                package_generation_snapshot = tile_repository.package_generation(),
            )
            val retained = draw_retained_frame_if_available(
                canvas,
                viewport,
                options,
                tile_zoom,
                fallback_zoom,
                now_ms,
            )
            if (retained != null) {
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = false,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = maxOf(requested, missing),
                    boundaries_drawn = boundary_raster.boundaries,
                    labels_drawn = retained.labels_drawn,
                    retained_frame_drawn = true,
                    fading = retained_fade != null,
                )
            }
            val counts = draw_reference_content(
                canvas = canvas,
                viewport = viewport,
                tiles = fallback_draw_tiles,
                labels_enabled = labels_enabled,
                borders_enabled = false,
                label_text_scale = label_text_scale,
                place_labels_enabled = place_labels_enabled,
                water_labels_enabled = water_labels_enabled,
                region_labels_enabled = region_labels_enabled,
                public_lands_enabled = public_lands_enabled,
                filter_mask = filter_mask,
                label_avoid_rects = label_avoid_rects,
            )
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = false,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = maxOf(requested, missing),
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = counts.labels,
                retained_frame_drawn = false,
                fading = false,
            )
        }

        if (draw_tiles.isEmpty()) {
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = false,
                visible_tiles = visible_tiles.size,
                loaded_tiles = 0,
                requested_tiles = maxOf(requested, missing),
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = 0,
                retained_frame_drawn = false,
                fading = false,
            )
        }

        if (!ready) {
            val partial = partial_label_frame_for(
                viewport = viewport,
                tile_zoom = tile_zoom,
                tiles = core_draw_tiles,
                labels_enabled = labels_enabled,
                label_text_scale = label_text_scale,
                place_labels_enabled = place_labels_enabled,
                water_labels_enabled = water_labels_enabled,
                region_labels_enabled = region_labels_enabled,
                public_lands_enabled = public_lands_enabled,
                filter_mask = filter_mask,
                label_avoid_rects = label_avoid_rects,
                options = options,
            )
            partial?.let { frame ->
                draw_retained_frame_with_transition(canvas, viewport, frame, now_ms)
            }
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = false,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = maxOf(requested, missing),
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = partial?.labels_drawn ?: 0,
                retained_frame_drawn = partial != null,
                fading = partial != null && retained_fade != null,
            )
        }

        if (ready) {
            val target = retained_frame
            if (target != null && target.is_drawable() &&
                target.matches_environment(
                    viewport = viewport,
                    next = options,
                    current_package_generation = tile_repository.package_generation(),
                ) &&
                retained_target_frame_can_settle(
                    frame = target,
                    viewport = viewport,
                    target_tile_zoom = tile_zoom,
                    fallback_tile_zoom = fallback_zoom,
                    destination = retained_destination,
                )
                ) {
                partial_label_frame = null
                if (fallback_needs_build && fallback_ready &&
                    !retained_label_coordinator.hasCurrentRequest()
                ) {
                    request_retained_label_frame(
                        role = RetainedFrameRole.FALLBACK,
                        viewport = viewport,
                        frame_viewport = requireNotNull(fallback_viewport),
                        tile_zoom = requireNotNull(fallback_zoom),
                        tiles = fallback_draw_tiles,
                        labels_enabled = labels_enabled,
                        label_text_scale = label_text_scale,
                        place_labels_enabled = place_labels_enabled,
                        water_labels_enabled = water_labels_enabled,
                        region_labels_enabled = region_labels_enabled,
                        public_lands_enabled = public_lands_enabled,
                        filter_mask = filter_mask,
                        label_avoid_rects = label_avoid_rects,
                        options = options,
                        package_generation_snapshot = tile_repository.package_generation(),
                    )
                } else if (
                    reference_retained_scene_should_stage(retained_destination, viewport) &&
                    !retained_history_has_exact_target(
                        viewport = viewport,
                        tile_zoom = tile_zoom,
                        options = options,
                    ) &&
                    !retained_label_coordinator.hasCurrentRequest()
                ) {
                    request_retained_label_frame(
                        role = RetainedFrameRole.TARGET,
                        viewport = viewport,
                        frame_viewport = draw_viewport,
                        tile_zoom = tile_zoom,
                        tiles = draw_tiles,
                        labels_enabled = labels_enabled,
                        label_text_scale = label_text_scale,
                        place_labels_enabled = place_labels_enabled,
                        water_labels_enabled = water_labels_enabled,
                        region_labels_enabled = region_labels_enabled,
                        public_lands_enabled = public_lands_enabled,
                        filter_mask = filter_mask,
                        label_avoid_rects = label_avoid_rects,
                        options = options,
                        package_generation_snapshot = tile_repository.package_generation(),
                    )
                }
                draw_retained_frame_with_transition(
                    canvas,
                    viewport,
                    target,
                    now_ms,
                    retained_destination,
                )
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = layer_ready,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
                    boundaries_drawn = boundary_raster.boundaries,
                    labels_drawn = target.labels_drawn,
                    retained_frame_drawn = true,
                    fading = retained_fade != null,
                )
            }
            request_retained_label_frame(
                role = RetainedFrameRole.TARGET,
                viewport = viewport,
                frame_viewport = draw_viewport,
                tile_zoom = tile_zoom,
                tiles = draw_tiles,
                labels_enabled = labels_enabled,
                label_text_scale = label_text_scale,
                place_labels_enabled = place_labels_enabled,
                water_labels_enabled = water_labels_enabled,
                region_labels_enabled = region_labels_enabled,
                public_lands_enabled = public_lands_enabled,
                filter_mask = filter_mask,
                label_avoid_rects = label_avoid_rects,
                options = options,
                package_generation_snapshot = tile_repository.package_generation(),
            )
            val retained = draw_retained_frame_if_available(
                canvas = canvas,
                viewport = viewport,
                options = options,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                now_ms = now_ms,
            )
            if (retained != null) {
                partial_label_frame = null
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = layer_ready,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
                    boundaries_drawn = boundary_raster.boundaries,
                    labels_drawn = retained.labels_drawn,
                    retained_frame_drawn = true,
                    fading = retained_fade != null,
                )
            }
        }

        partial_label_frame?.takeIf { frame ->
            frame.matches_exact(
                viewport = viewport,
                next_tile_zoom = tile_zoom,
                next = options,
                current_package_generation = tile_repository.package_generation(),
            )
        }?.let { partial ->
            draw_retained_frame_with_transition(canvas, viewport, partial, now_ms)
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = layer_ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = partial.labels_drawn,
                retained_frame_drawn = true,
                fading = retained_fade != null,
            )
        }

        val counts = draw_reference_content(
            canvas = canvas,
            viewport = viewport,
            tiles = draw_tiles,
            labels_enabled = labels_enabled,
            borders_enabled = false,
            label_text_scale = label_text_scale,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
        )
        return ReferenceDictionaryOverlayDrawStats(
            available = true,
            ready = layer_ready,
            visible_tiles = visible_tiles.size,
            loaded_tiles = draw_tiles.size,
            requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
            boundaries_drawn = boundary_raster.boundaries,
            labels_drawn = counts.labels,
            retained_frame_drawn = false,
            fading = false,
        )
    }

    fun content_revision(): Long = tile_repository.content_revision()

    fun package_check_pending(): Boolean = tile_repository.package_check_pending()

    fun prepare() = tile_repository.prepare()

    fun reference_class_catalog(): ReferenceClassCatalog? =
        tile_repository.reference_class_catalog()

    fun clear() {
        tile_repository.clear()
        clear_renderer_state()
    }

    fun reset_retained_frame() {
        cancel_pending_retained_bitmap()
        retained_label_coordinator.cancel()
        tile_repository.clear_zoom_ahead_desired_tile_keys()
        retained_label_interaction_active = false
        clear_retained_frames()
    }

    override fun close() {
        lifecycle_lock.lock()
        try {
            if (closed) return
            closed = true
            retained_bitmap_executor.shutdownNow()
            retained_label_executor.shutdownNow()
            boundary_renderer.close()
            tile_repository.close()
            clear_renderer_state(clear_boundaries = false)
        } finally {
            lifecycle_lock.unlock()
        }
    }

    private fun clear_renderer_state(clear_boundaries: Boolean = true) {
        cancel_pending_retained_bitmap()
        retained_label_coordinator.cancel()
        retained_label_interaction_active = false
        clear_retained_frames()
        if (clear_boundaries) boundary_renderer.clear()
    }

    private fun build_visible_tile_list(
        output: MutableList<VisibleDictionaryTile>,
        viewport: Viewport,
        core_viewport: Viewport,
        tile_zoom: Int,
        outline_visibility_zoom: Double = core_viewport.zoom,
    ) {
        output.clear()
        val tile_to_viewport_scale = 2.0.pow(viewport.zoom - tile_zoom)
        val tile_world_scale = 1.0 / tile_to_viewport_scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val first_tile_x = floor(left_world * tile_world_scale / MAP_TILE_SIZE).toInt()
        val first_tile_y = floor(top_world * tile_world_scale / MAP_TILE_SIZE).toInt()
        val last_tile_x =
            floor((left_world + viewport.width) * tile_world_scale / MAP_TILE_SIZE).toInt()
        val last_tile_y =
            floor((top_world + viewport.height) * tile_world_scale / MAP_TILE_SIZE).toInt()
        val center_tile_x = floor(viewport.center_x * tile_world_scale / MAP_TILE_SIZE).toInt()
        val center_tile_y = floor(viewport.center_y * tile_world_scale / MAP_TILE_SIZE).toInt()
        val max_tile = 1 shl tile_zoom
        val outline_visibility_centizoom =
            if (tile_repository.snapshot()?.info?.runtime_schema ==
                ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1
            ) {
                ReferenceDictionaryLodPolicy.LABEL_ONLY_OUTLINE_PROFILE
            } else {
                ReferenceDictionaryLodPolicy.outline_decode_profile(
                    outline_visibility_zoom,
                    tile_zoom,
                )
            }
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                output += VisibleDictionaryTile(
                    z = tile_zoom,
                    x = tx,
                    draw_x = tx_raw,
                    y = ty,
                    core_visible = tile_intersects_viewport(
                        viewport = core_viewport,
                        tile_zoom = tile_zoom,
                        tile_x = tx_raw,
                        tile_y = ty
                    ),
                    request_priority = tile_request_priority(
                        tx = tx_raw,
                        ty = ty,
                        center_x = center_tile_x,
                        center_y = center_tile_y
                    ),
                    outline_visibility_centizoom = outline_visibility_centizoom,
                )
            }
        }
        output.sortWith(
            compareBy<VisibleDictionaryTile> { !it.core_visible }.thenBy { it.request_priority }
        )
    }

    private fun tile_intersects_viewport(
        viewport: Viewport,
        tile_zoom: Int,
        tile_x: Int,
        tile_y: Int
    ): Boolean {
        val tile_scale = MAP_TILE_SIZE * 2.0.pow(viewport.zoom - tile_zoom)
        val left = (tile_x * tile_scale - (viewport.center_x - viewport.width / 2.0)).toFloat()
        val top = (tile_y * tile_scale - (viewport.center_y - viewport.height / 2.0)).toFloat()
        return left <= viewport.width &&
                left + tile_scale >= 0.0 &&
                top <= viewport.height &&
                top + tile_scale >= 0.0
    }

    private fun request_retained_label_frame(
        role: RetainedFrameRole,
        viewport: Viewport,
        frame_viewport: Viewport,
        tile_zoom: Int,
        tiles: List<DictionaryTileDrawRef>,
        labels_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
        package_generation_snapshot: Long,
    ) {
        if (!labels_enabled || retained_label_executor.isShutdown) return
        val avoid_rects = label_avoid_rects.toList()
        val key = RetainedLabelPlanKey(
            role = role,
            viewport = viewport,
            frameViewport = frame_viewport,
            tileZoom = tile_zoom,
            tiles = tiles.map { tile ->
                RetainedLabelTileKey(
                    z = tile.tile.coordinate.z,
                    x = tile.tile.coordinate.x,
                    y = tile.tile.coordinate.y,
                    drawX = tile.draw_x,
                    coreVisible = tile.core_visible,
                    identity = System.identityHashCode(tile.tile),
                )
            },
            options = options,
            packageGeneration = package_generation_snapshot,
            labelAvoidRects = avoid_rects,
        )
        val token = retained_label_coordinator.start(key) ?: return
        val preferred_labels = displayed_retained_frame
            ?.takeIf {
                it.options == options &&
                    it.package_generation == package_generation_snapshot
            }
            ?.labels
            ?: emptyList()
        val request = RetainedLabelPlanRequest(
            token = token,
            tiles = tiles.toList(),
            preferredLabels = preferred_labels,
            labelsEnabled = labels_enabled,
            labelTextScale = label_text_scale,
            placeLabelsEnabled = place_labels_enabled,
            waterLabelsEnabled = water_labels_enabled,
            regionLabelsEnabled = region_labels_enabled,
            publicLandsEnabled = public_lands_enabled,
            filterMask = filter_mask,
        )
        retained_label_executor.execute {
            prepare_and_publish_retained_label_frame(request)
        }
    }

    private fun prepare_and_publish_retained_label_frame(request: RetainedLabelPlanRequest) {
        if (!retained_label_coordinator.shouldStart(request.token)) return
        val key = request.token.key
        val keep_planning = { retained_label_coordinator.shouldStart(request.token) }
        val preferred_occurrences = HashSet<ReferenceLabelOccurrenceId>(request.preferredLabels.size)
        val preferred_candidate_keys =
            HashSet<ReferenceLabelCandidateKey>(request.preferredLabels.size)
        for (label in request.preferredLabels) {
            val occurrence_id = label.occurrenceId
            preferred_occurrences += occurrence_id
            preferred_candidate_keys += ReferenceLabelCandidateKey(
                occurrence_id.candidateId,
                occurrence_id.renderedWorldCopy,
            )
        }
        val frame = create_retained_frame(
            boundary_batches = emptyList(),
            viewport = key.viewport,
            frame_viewport = key.frameViewport,
            tile_zoom = key.tileZoom,
            tiles = request.tiles,
            labels_enabled = request.labelsEnabled,
            borders_enabled = false,
            label_text_scale = request.labelTextScale,
            place_labels_enabled = request.placeLabelsEnabled,
            water_labels_enabled = request.waterLabelsEnabled,
            region_labels_enabled = request.regionLabelsEnabled,
            public_lands_enabled = request.publicLandsEnabled,
            filter_mask = request.filterMask,
            label_avoid_rects = key.labelAvoidRects,
            options = key.options,
            package_generation_snapshot = key.packageGeneration,
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            workspace = retained_label_workspace,
            keep_planning = keep_planning,
        )
        val complete = frame != null && keep_planning()
        val completion = retained_label_coordinator.complete(
            request = request.token,
            value = PreparedRetainedLabelFrame(request, frame),
            complete = complete,
        )
        if (completion == ReferenceRetainedBitmapCompletion.PUBLISH) {
            request_redraw()
        }
    }

    private fun finish_completed_retained_label_frame(
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        options: RetainedReferenceOptions,
        label_avoid_rects: List<ReferenceScreenRect>,
        interaction_active: Boolean,
    ) {
        val prepared = retained_label_coordinator.take() ?: return
        val frame = prepared.frame ?: return
        val key = prepared.request.token.key
        val current_generation = tile_repository.package_generation()
        val environment_matches =
            frame.options == options &&
                frame.package_generation == current_generation &&
                key.labelAvoidRects == label_avoid_rects &&
                frame.is_drawable()
        val visible_at_current_viewport = if (environment_matches) {
            retained_frame_destination(frame, viewport, retained_destination)
            frame.has_visible_content(viewport, retained_destination)
        } else {
            false
        }
        when (key.role) {
            RetainedFrameRole.TARGET -> when (
                reference_retained_completed_target_disposition(
                    environmentMatches = environment_matches,
                    matchesCurrentTarget = visible_at_current_viewport &&
                        frame.matches_exact(
                        viewport = viewport,
                        next_tile_zoom = target_tile_zoom,
                        next = options,
                        current_package_generation = current_generation,
                    ),
                    interactionActive = interaction_active,
                )
            ) {
                ReferenceRetainedCompletedTargetDisposition.PUBLISH ->
                    publish_retained_target_frame(
                        frame = frame,
                        viewport = viewport,
                        target_tile_zoom = target_tile_zoom,
                        fallback_tile_zoom = fallback_tile_zoom,
                        options = options,
                        current_generation = current_generation,
                    )
                ReferenceRetainedCompletedTargetDisposition.REMEMBER ->
                    retained_frame_history.remember(frame)
                ReferenceRetainedCompletedTargetDisposition.DISCARD -> Unit
            }
            RetainedFrameRole.FALLBACK -> when {
                !environment_matches -> Unit
                !interaction_active &&
                    visible_at_current_viewport &&
                    fallback_tile_zoom != null &&
                    frame.matches_exact(
                    viewport = viewport,
                    next_tile_zoom = fallback_tile_zoom,
                    next = options,
                    current_package_generation = current_generation,
                ) -> replace_retained_fallback_frame(frame)
                else -> retained_frame_history.remember(frame)
            }
        }
    }

    private fun finish_completed_retained_boundary_bitmap(
        canvas: Canvas,
        viewport: Viewport,
        tile_zoom: Int,
        fallback_zoom: Int?,
        labels_enabled: Boolean,
        borders_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
        interaction_active: Boolean,
        now_ms: Long,
    ) {
        val prepared = retained_bitmap_coordinator.take() ?: return
        val request = prepared.request
        val key = request.token.key
        val visible_width = ceil(viewport.width).toInt().coerceAtLeast(1)
        val visible_height = ceil(viewport.height).toInt().coerceAtLeast(1)
        val request_width = ceil(request.content_viewport.width).toInt().coerceAtLeast(1)
        val request_height = ceil(request.content_viewport.height).toInt().coerceAtLeast(1)
        val current_generation = tile_repository.package_generation()
        val valid = prepared.complete && borders_enabled &&
            key.package_generation == current_generation &&
            key.place_labels_enabled == place_labels_enabled &&
            key.water_labels_enabled == water_labels_enabled &&
            key.region_labels_enabled == region_labels_enabled &&
            key.public_lands_enabled == public_lands_enabled &&
            key.filter_mask_key == filter_mask.cacheKey &&
            request_width == visible_width && request_height == visible_height &&
            ReferenceDictionaryLodPolicy.can_reuse_scene(
                request.tile_zoom,
                tile_zoom,
                fallback_zoom,
            )
        if (!valid) {
            return
        }

        val frame_width = ceil(request.viewport.width).toInt().coerceAtLeast(1)
        val frame_height = ceil(request.viewport.height).toInt().coerceAtLeast(1)
        reference_retained_destination(
            frame_zoom = request.content_viewport.zoom,
            frame_center_x = request.content_viewport.center_x,
            frame_center_y = request.content_viewport.center_y,
            frame_width = frame_width,
            frame_height = frame_height,
            viewport = viewport,
            destination = retained_destination,
        )
        if (!reference_retained_covers_viewport(retained_destination, viewport)) {
            return
        }
        if (key.role == RetainedFrameRole.TARGET &&
            retained_frame?.matches_exact(
                viewport = viewport,
                next_tile_zoom = tile_zoom,
                next = options,
                current_package_generation = current_generation,
            ) == true
        ) {
            return
        }

        val frame = create_retained_frame(
            boundary_batches = prepared.batches,
            viewport = request.content_viewport,
            frame_viewport = request.viewport,
            tile_zoom = request.tile_zoom,
            tiles = request.tiles,
            labels_enabled = labels_enabled,
            borders_enabled = false,
            label_text_scale = label_text_scale,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
            options = options,
            package_generation_snapshot = current_generation,
        ) ?: return
        val destination = if (key.role == RetainedFrameRole.TARGET) {
            retained_destination
        } else {
            fallback_destination
        }
        if (!retained_frame_is_eligible(
                frame = frame,
                canvas = canvas,
                viewport = viewport,
                options = options,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = destination,
            )
        ) {
            return
        }
        if (key.role == RetainedFrameRole.TARGET) {
            replace_retained_frame(frame)
        } else {
            replace_retained_fallback_frame(frame)
        }
    }

    private fun create_retained_frame(
        boundary_batches: List<BoundaryPathBatch>,
        viewport: Viewport,
        frame_viewport: Viewport,
        tile_zoom: Int,
        tiles: List<DictionaryTileDrawRef>,
        labels_enabled: Boolean,
        borders_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
        package_generation_snapshot: Long,
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey> = emptySet(),
        workspace: LabelPlanningWorkspace = ui_label_workspace,
        keep_planning: () -> Boolean = ALWAYS_KEEP_LABEL_PLAN,
    ): RetainedReferenceFrame? {
        val width = ceil(viewport.width).toInt().coerceAtLeast(1)
        val height = ceil(viewport.height).toInt().coerceAtLeast(1)
        val frame_width = ceil(frame_viewport.width).toInt().coerceAtLeast(1)
        val frame_height = ceil(frame_viewport.height).toInt().coerceAtLeast(1)
        val horizontal_padding = (frame_width - width) / 2.0
        val vertical_padding = (frame_height - height) / 2.0
        val content = draw_retained_scene_content(
            workspace = workspace,
            boundary_batches = boundary_batches,
            viewport = viewport,
            frame_viewport = frame_viewport,
            tiles = tiles,
            labels_enabled = labels_enabled,
            borders_enabled = borders_enabled,
            label_text_scale = label_text_scale,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            horizontal_padding = horizontal_padding,
            vertical_padding = vertical_padding,
            keep_planning = keep_planning,
        ) ?: return null
        val label_coverage = ReferenceRetainedLabelCoverage(
            width = frame_width.toDouble(),
            height = frame_height.toDouble(),
        )
        for (label in content.labels) {
            label_coverage.add(label.collisionShape.bounds)
        }
        return RetainedReferenceFrame(
            boundary_batches = boundary_batches,
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            tile_zoom = tile_zoom,
            width = frame_width,
            height = frame_height,
            visible_width = width,
            visible_height = height,
            options = options,
            package_generation = package_generation_snapshot,
            boundaries_drawn = content.boundaries,
            labels = content.labels,
            label_coverage = label_coverage,
        )
    }

    private fun request_retained_boundary_bitmap(
        role: RetainedFrameRole,
        content_viewport: Viewport,
        viewport: Viewport,
        tile_zoom: Int,
        tiles: List<DictionaryTileDrawRef>,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        package_generation_snapshot: Long,
    ) {
        val key = RetainedBoundaryBitmapKey(
            role = role,
            viewport = viewport,
            tiles = tiles.map { tile ->
                RetainedBoundaryBitmapTileKey(
                    z = tile.tile.coordinate.z,
                    x = tile.tile.coordinate.x,
                    y = tile.tile.coordinate.y,
                    draw_x = tile.draw_x,
                    identity = System.identityHashCode(tile.tile),
                )
            },
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask_key = filter_mask.cacheKey,
            package_generation = package_generation_snapshot,
        )
        if (failed_retained_bitmap_key == key &&
            SystemClock.elapsedRealtime() < retained_bitmap_retry_after_ms
        ) {
            return
        }
        val token = retained_bitmap_coordinator.start(key) ?: return
        failed_retained_bitmap_key = null
        val request = RetainedBoundaryBitmapRequest(
            token = token,
            content_viewport = content_viewport,
            viewport = viewport,
            tile_zoom = tile_zoom,
            tiles = tiles.toList(),
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
        )
        retained_bitmap_executor.execute {
            prepare_and_publish_retained_boundary_bitmap(request)
        }
    }

    private fun prepare_and_publish_retained_boundary_bitmap(
        request: RetainedBoundaryBitmapRequest,
    ) {
        if (!retained_bitmap_coordinator.shouldStart(request.token)) return
        val prepared = render_retained_boundary_bitmap(request)
        val completion = retained_bitmap_coordinator.complete(
            request = request.token,
            value = prepared,
            complete = prepared.complete,
        )
        when (completion) {
            ReferenceRetainedBitmapCompletion.INCOMPLETE -> {
                failed_retained_bitmap_key = request.token.key
                retained_bitmap_retry_after_ms = SystemClock.elapsedRealtime() +
                    RETAINED_BITMAP_FAILURE_RETRY_MS
                request_redraw()
            }

            ReferenceRetainedBitmapCompletion.PUBLISH -> request_redraw()
            ReferenceRetainedBitmapCompletion.DISCARD -> Unit
        }
    }

    private fun render_retained_boundary_bitmap(
        request: RetainedBoundaryBitmapRequest,
    ): PreparedRetainedBoundaryBitmap {
        if (!retained_bitmap_coordinator.shouldStart(request.token)) {
            return PreparedRetainedBoundaryBitmap(request, emptyList(), false)
        }
        val groups = ReferenceDictionaryLayerGroups(
            places = request.place_labels_enabled,
            water = request.water_labels_enabled,
            regions = request.region_labels_enabled,
            public_lands = request.public_lands_enabled,
        )
        val batches = boundary_renderer.prepare_batches(
            viewport = request.viewport,
            tiles = request.tiles,
            groups = groups,
            filterMask = request.filter_mask,
            keepDrawing = { retained_bitmap_coordinator.shouldStart(request.token) },
            includeHidden = true,
        )
        val complete = retained_bitmap_coordinator.shouldStart(request.token)
        return PreparedRetainedBoundaryBitmap(request, batches, complete)
    }

    private fun cancel_pending_retained_bitmap() {
        retained_bitmap_coordinator.cancel()
        failed_retained_bitmap_key = null
        retained_bitmap_retry_after_ms = 0L
    }

    private fun draw_retained_scene_content(
        workspace: LabelPlanningWorkspace,
        boundary_batches: List<BoundaryPathBatch>,
        viewport: Viewport,
        frame_viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        labels_enabled: Boolean,
        borders_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        preferred_occurrences: Set<ReferenceLabelOccurrenceId>,
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey>,
        horizontal_padding: Double,
        vertical_padding: Double,
        keep_planning: () -> Boolean,
    ): RetainedReferenceContent? {
        val boundaries_drawn = if (borders_enabled) {
            boundary_batches.sumOf { batch -> batch.boundaries }
        } else 0
        if (!labels_enabled) {
            return RetainedReferenceContent(
                boundaries = boundaries_drawn,
                labels = emptyList(),
            )
        }

        val groups = ReferenceDictionaryLayerGroups(
            places = place_labels_enabled,
            water = water_labels_enabled,
            regions = region_labels_enabled,
            public_lands = public_lands_enabled,
        )
        if (!plan_labels(
            workspace = workspace,
            viewport = viewport,
            tiles = tiles,
            label_text_scale = label_text_scale,
            groups = groups,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            keep_planning = keep_planning,
        )) return null
        val core_candidates = workspace.acceptedLabels.map { candidate ->
            translate_label_candidate(candidate, horizontal_padding, vertical_padding)
        }
        val core_rect = ReferenceScreenRect(
            left = horizontal_padding,
            top = vertical_padding,
            right = horizontal_padding + viewport.width,
            bottom = vertical_padding + viewport.height,
        )
        val shifted_avoid_rects = ArrayList<ReferenceScreenRect>(label_avoid_rects.size + 1)
        shifted_avoid_rects += shift_label_avoid_rects(
            label_avoid_rects,
            horizontal_padding,
            vertical_padding,
        )
        shifted_avoid_rects += core_rect
        val core_area = viewport.width.toDouble() * viewport.height
        val frame_area = frame_viewport.width.toDouble() * frame_viewport.height
        val padding_area_ratio = ((frame_area - core_area) / core_area).coerceAtLeast(0.0)
        val padding_budget = kotlin.math.ceil(
            label_budget(viewport) * padding_area_ratio
        ).toInt().coerceIn(0, MAX_RETAINED_PADDING_LABELS)
        val padding_protected_area_budget = kotlin.math.ceil(
            protected_area_label_budget(viewport) * padding_area_ratio
        ).toInt().coerceAtLeast(0)
        if (!keep_planning()) return null
        if (!plan_labels(
            workspace = workspace,
            viewport = frame_viewport,
            tiles = tiles,
            label_text_scale = label_text_scale,
            groups = groups,
            filter_mask = filter_mask,
            label_avoid_rects = shifted_avoid_rects,
            include_buffer_tiles = true,
            fixed_candidates = core_candidates,
            excluded_rect = core_rect,
            label_budget_override = (core_candidates.size + padding_budget).coerceAtLeast(1),
            protected_area_budget_override = (
                core_candidates.count { it.protectedArea } + padding_protected_area_budget
                ).coerceAtLeast(1),
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            keep_planning = keep_planning,
        )) return null
        return RetainedReferenceContent(
            boundaries = boundaries_drawn,
            labels = workspace.acceptedLabels.toList(),
        )
    }

    private fun shift_label_avoid_rects(
        rects: List<ReferenceScreenRect>,
        dx: Double,
        dy: Double,
    ): List<ReferenceScreenRect> {
        if (rects.isEmpty()) return emptyList()
        return rects.map { rect ->
            ReferenceScreenRect(
                left = rect.left + dx,
                top = rect.top + dy,
                right = rect.right + dx,
                bottom = rect.bottom + dy,
            )
        }
    }

    private fun translate_label_candidate(
        candidate: DictionaryLabelCandidate,
        dx: Double,
        dy: Double,
    ): DictionaryLabelCandidate {
        val translated_collision = when (val shape = candidate.collisionShape) {
            is ReferenceLabelCollisionShape.Box -> ReferenceLabelCollisionShape.Box(
                translate_screen_rect(shape.rect, dx, dy)
            )
            is ReferenceLabelCollisionShape.Path ->
                translateReferencePathCollisionShape(shape, dx, dy)
        }
        return candidate.copy(
            anchor = ReferencePathLabelPoint(
                candidate.anchor.x + dx,
                candidate.anchor.y + dy,
            ),
            collisionShape = translated_collision,
            path_points = (translated_collision as? ReferenceLabelCollisionShape.Path)?.points,
        )
    }

    private fun translate_screen_rect(
        rect: ReferenceScreenRect,
        dx: Double,
        dy: Double,
    ): ReferenceScreenRect {
        return ReferenceScreenRect(
            left = rect.left + dx,
            top = rect.top + dy,
            right = rect.right + dx,
            bottom = rect.bottom + dy,
        )
    }

    private fun draw_retained_frame_if_available(
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        now_ms: Long
    ): RetainedReferenceFrame? {
        val frame = retained_frame_for_viewport(
            canvas,
            viewport,
            options,
            target_tile_zoom,
            fallback_tile_zoom,
            retained_destination,
        )
        if (frame == null) {
            cleanup_retained_fade()
            displayed_retained_frame = null
            return null
        }
        draw_retained_frame_with_transition(
            canvas = canvas,
            viewport = viewport,
            frame = frame,
            now_ms = now_ms,
            destination = retained_destination,
        )
        return frame
    }

    private fun retained_frame_for_viewport(
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): RetainedReferenceFrame? {
        val primary = retained_frame
        val established = displayed_retained_frame ?: primary
        var closer_future: RetainedReferenceFrame? = null
        var closest_zoom_distance = Double.POSITIVE_INFINITY
        if (established != null && primary != null && primary !== established &&
            primary.zoom > established.zoom &&
            reference_retained_zoom_ahead_can_handoff(
                establishedZoom = established.zoom,
                viewportZoom = viewport.zoom,
                minimumLead = ZOOM_AHEAD_HANDOFF_LEAD,
            ) &&
            retained_frame_is_emergency_eligible(
                frame = primary,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = candidate_destination,
            )
        ) {
            closer_future = primary
            closest_zoom_distance = kotlin.math.abs(primary.zoom - viewport.zoom)
        }
        if (established != null) {
            for (previous in retained_frame_history) {
                val zoom_distance = kotlin.math.abs(previous.zoom - viewport.zoom)
                if (previous === established ||
                    previous.zoom <= established.zoom ||
                    zoom_distance >= closest_zoom_distance ||
                    !reference_retained_zoom_ahead_can_handoff(
                        establishedZoom = established.zoom,
                        viewportZoom = viewport.zoom,
                        minimumLead = ZOOM_AHEAD_HANDOFF_LEAD,
                    ) ||
                    !retained_frame_is_emergency_eligible(
                        frame = previous,
                        viewport = viewport,
                        options = options,
                        target_tile_zoom = target_tile_zoom,
                        fallback_tile_zoom = fallback_tile_zoom,
                        destination = candidate_destination,
                    )
                ) {
                    continue
                }
                closer_future = previous
                closest_zoom_distance = zoom_distance
            }
        }
        if (closer_future != null) {
            if (closer_future !== primary) promote_retained_frame(closer_future)
            retained_frame_destination(closer_future, viewport, destination)
            return closer_future
        }

        if (primary != null && retained_frame_is_eligible(
                primary,
                canvas,
                viewport,
                options,
                target_tile_zoom,
                fallback_tile_zoom,
                destination,
            )
        ) {
            return primary
        }
        var covering_target_history: RetainedReferenceFrame? = null
        for (previous in retained_frame_history) {
            if (previous !== primary &&
                previous.matches_environment(
                    viewport = viewport,
                    next = options,
                    current_package_generation = tile_repository.package_generation(),
                ) &&
                previous.is_drawable() &&
                retained_target_frame_can_settle(
                    frame = previous,
                    viewport = viewport,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = destination,
                )
            ) {
                covering_target_history = previous
                break
            }
        }
        if (covering_target_history != null) {
            promote_retained_frame(covering_target_history)
            return covering_target_history
        }
        val fallback = retained_fallback_frame
        if (fallback != null && fallback !== primary && retained_frame_is_eligible(
                fallback,
                canvas,
                viewport,
                options,
                target_tile_zoom,
                fallback_tile_zoom,
                destination,
            )
        ) {
            return fallback
        }
        var historical: RetainedReferenceFrame? = null
        for (previous in retained_frame_history) {
            if (previous !== primary && previous !== fallback && retained_frame_is_eligible(
                    previous,
                    canvas,
                    viewport,
                    options,
                    target_tile_zoom,
                    fallback_tile_zoom,
                    destination,
                )
            ) {
                historical = previous
                break
            }
        }
        if (historical != null) {
            promote_retained_frame(historical)
            return historical
        }

        val displayed = displayed_retained_frame
        if (displayed != null && retained_frame_is_emergency_eligible(
                frame = displayed,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = destination,
            )
        ) {
            return displayed
        }
        if (primary != null && primary !== displayed &&
            retained_frame_is_emergency_eligible(
                frame = primary,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = destination,
            )
        ) {
            return primary
        }
        if (fallback != null && fallback !== displayed && fallback !== primary &&
            retained_frame_is_emergency_eligible(
                frame = fallback,
                viewport = viewport,
                options = options,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = destination,
            )
        ) {
            return fallback
        }
        for (previous in retained_frame_history) {
            if (previous !== displayed && previous !== primary && previous !== fallback &&
                retained_frame_is_emergency_eligible(
                    frame = previous,
                    viewport = viewport,
                    options = options,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = destination,
                )
            ) {
                return previous
            }
        }
        return null
    }

    private fun retained_history_has_exact_target(
        viewport: Viewport,
        tile_zoom: Int,
        options: RetainedReferenceOptions,
    ): Boolean {
        val current_generation = tile_repository.package_generation()
        for (frame in retained_frame_history) {
            if (frame.matches_exact(
                    viewport = viewport,
                    next_tile_zoom = tile_zoom,
                    next = options,
                    current_package_generation = current_generation,
                )
            ) return true
        }
        return false
    }

    private fun retained_frames_have_covering_target(
        viewport: Viewport,
        tile_zoom: Int,
        options: RetainedReferenceOptions,
    ): Boolean {
        val current_generation = tile_repository.package_generation()
        val active = retained_frame
        if (active != null &&
            active.matches_environment(viewport, options, current_generation) &&
            retained_target_frame_can_settle(
                frame = active,
                viewport = viewport,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = null,
                destination = candidate_destination,
            )
        ) {
            return true
        }
        for (frame in retained_frame_history) {
            if (frame.matches_environment(viewport, options, current_generation) &&
                retained_target_frame_can_settle(
                    frame = frame,
                    viewport = viewport,
                    target_tile_zoom = tile_zoom,
                    fallback_tile_zoom = null,
                    destination = candidate_destination,
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun retained_frame_is_eligible(
        frame: RetainedReferenceFrame,
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        if (!frame.matches_options(options) ||
            frame.package_generation != tile_repository.package_generation() ||
            !ReferenceDictionaryLodPolicy.can_reuse_scene(
                frame.tile_zoom,
                target_tile_zoom,
                fallback_tile_zoom,
            ) ||
            !frame.is_drawable()
        ) return false
        retained_frame_destination(frame, viewport, destination)
        return reference_retained_covers_viewport(destination, viewport) &&
            frame.has_visible_content(viewport, destination)
    }

    private fun retained_frame_is_emergency_eligible(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        if (!frame.matches_options(options) ||
            frame.package_generation != tile_repository.package_generation() ||
            !ReferenceDictionaryLodPolicy.can_reuse_scene_during_interaction(
                frame.tile_zoom,
                target_tile_zoom,
                fallback_tile_zoom,
            ) ||
            !frame.is_drawable()
        ) return false
        retained_frame_destination(frame, viewport, destination)
        return reference_retained_covers_viewport(destination, viewport) &&
            frame.has_visible_content(viewport, destination)
    }

    private fun retained_fallback_needs_build(
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
    ): Boolean {
        val fallback = retained_fallback_frame
        return fallback_tile_zoom != null && (
            fallback == null ||
                fallback.tile_zoom != fallback_tile_zoom ||
                !retained_frame_is_eligible(
                    frame = fallback,
                    canvas = canvas,
                    viewport = viewport,
                    options = options,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = fallback_destination,
                )
            )
    }

    private fun draw_retained_frame_with_transition(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        now_ms: Long,
        destination: ReferenceRetainedDestination? = null,
    ) {
        val current_destination = destination ?: retained_destination.also {
            retained_frame_destination(frame, viewport, it)
        }
        var established = displayed_retained_frame
        var carried_fade_elapsed_ms = 0L
        val interrupted_fade = retained_fade
        if (established != null &&
            interrupted_fade != null &&
            interrupted_fade.previous === established &&
            interrupted_fade.current !== frame &&
            established.options == frame.options &&
            established.package_generation == frame.package_generation &&
            established.is_drawable()
        ) {
            val interrupted_elapsed_ms = (now_ms - interrupted_fade.start_ms)
                .coerceIn(0L, RETAINED_FRAME_FADE_MS.toLong())
            if (frame === interrupted_fade.previous) {
                established = interrupted_fade.current
                carried_fade_elapsed_ms =
                    RETAINED_FRAME_FADE_MS.toLong() - interrupted_elapsed_ms
            } else {
                val carry = reference_retained_fade_carry(
                    previous = interrupted_fade.previous,
                    current = interrupted_fade.current,
                    elapsedMs = interrupted_elapsed_ms,
                    durationMs = RETAINED_FRAME_FADE_MS.toLong(),
                )
                established = carry.established
                carried_fade_elapsed_ms = carry.elapsedMs
            }
            displayed_retained_frame = established
            cleanup_retained_fade()
        }
        if (established == null || established === frame ||
            established.options != frame.options ||
            established.package_generation != frame.package_generation ||
            !established.is_drawable()
        ) {
            displayed_retained_frame = reference_retained_displayed_frame(
                established = established,
                requested = frame,
                transition_complete = true,
            )
            cleanup_retained_fade()
            draw_retained_frame(canvas, viewport, frame, destination = current_destination)
            return
        }

        val active_fade = retained_fade
        if (active_fade == null ||
            active_fade.previous !== established ||
            active_fade.current !== frame
        ) {
            retained_frame_destination(established, viewport, fading_destination)
            retained_fade = retained_reference_fade(
                previous = established,
                current = frame,
                previous_destination = fading_destination,
                current_destination = current_destination,
                start_ms = now_ms - carried_fade_elapsed_ms,
            )
        }
        val fade = requireNotNull(retained_fade)
        val raw_progress = ((now_ms - fade.start_ms).toFloat() / RETAINED_FRAME_FADE_MS)
            .coerceIn(0f, 1f)
        if (raw_progress >= 1f) {
            displayed_retained_frame = reference_retained_displayed_frame(
                established = established,
                requested = frame,
                transition_complete = true,
            )
            cleanup_retained_fade()
            draw_retained_frame(canvas, viewport, frame, destination = current_destination)
            return
        }
        displayed_retained_frame = reference_retained_displayed_frame(
            established = established,
            requested = frame,
            transition_complete = false,
        )
        val eased_progress = raw_progress * raw_progress * (3f - 2f * raw_progress)
        val previous_alpha = (255f * (1f - eased_progress)).roundToInt().coerceIn(0, 255)
        val current_alpha = (255f * eased_progress).roundToInt().coerceIn(0, 255)
        val leaving_label_alpha = reference_retained_leaving_label_alpha(raw_progress)
        val entering_label_alpha = reference_retained_entering_label_alpha(raw_progress)
        retained_frame_destination(fade.previous, viewport, fading_destination)
        draw_retained_frame(
            canvas = canvas,
            viewport = viewport,
            frame = fade.previous,
            alpha = previous_alpha,
            destination = fading_destination,
            draw_labels = false,
        )
        draw_retained_frame(
            canvas = canvas,
            viewport = viewport,
            frame = frame,
            alpha = current_alpha,
            destination = current_destination,
            draw_labels = false,
        )
        draw_retained_labels(
            canvas = canvas,
            viewport = viewport,
            frame = fade.previous,
            destination = fading_destination,
            alpha = leaving_label_alpha,
            labels = fade.labels.leaving,
        )
        draw_retained_labels(
            canvas = canvas,
            viewport = viewport,
            frame = frame,
            destination = current_destination,
            alpha = entering_label_alpha,
            fully_visible = fade.labels.continuing_current,
        )
        request_redraw()
    }

    private fun draw_retained_frame(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        alpha: Int = 255,
        destination: ReferenceRetainedDestination? = null,
        draw_labels: Boolean = true,
    ) {
        val draw_destination = destination ?: retained_destination.also {
            retained_frame_destination(frame, viewport, it)
        }
        if (frame.boundary_batches.isNotEmpty()) {
            val draw_scale = (
                (draw_destination.right - draw_destination.left) / frame.width
                ).toFloat()
            canvas.withSave {
                clipRect(0f, 0f, viewport.width, viewport.height)
                translate(
                    draw_destination.left.toFloat(),
                    draw_destination.top.toFloat(),
                )
                scale(draw_scale, draw_scale)
                boundary_renderer.draw_batches(
                    canvas = this,
                    viewport_zoom = viewport.zoom,
                    batches = frame.boundary_batches,
                    draw_scale = draw_scale,
                    frame_alpha = alpha,
                )
            }
        }
        if (draw_labels) {
            draw_retained_labels(canvas, viewport, frame, draw_destination)
        }
    }

    private fun draw_retained_labels(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        destination: ReferenceRetainedDestination,
        alpha: Int = 255,
        labels: List<DictionaryLabelCandidate> = frame.labels,
        fully_visible: BooleanArray? = null,
    ) {
        val zoom_scale = (destination.right - destination.left) / frame.width
        for (index in labels.indices.reversed()) {
            val candidate = labels[index]
            if (!reference_retained_label_intersects_viewport(
                    collision_shape = candidate.collisionShape,
                    anchor = candidate.anchor,
                    zoom_scale = zoom_scale,
                    translate_x = destination.left,
                    translate_y = destination.top,
                    viewport_width = viewport.width.toDouble(),
                    viewport_height = viewport.height.toDouble(),
                )
            ) {
                continue
            }
            draw_label_candidate(
                canvas = canvas,
                candidate = candidate,
                zoom_scale = zoom_scale,
                translate_x = destination.left,
                translate_y = destination.top,
                frame_alpha = if (fully_visible?.get(index) == true) 255 else alpha,
            )
        }
    }

    private fun retained_target_frame_can_settle(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        destination: ReferenceRetainedDestination,
    ): Boolean {
        retained_frame_destination(frame, viewport, destination)
        return frame.has_visible_content(viewport, destination) &&
            reference_retained_scene_can_settle(
            destination = destination,
            viewport = viewport,
            frameZoom = frame.zoom,
            frameTileZoom = frame.tile_zoom,
            targetTileZoom = target_tile_zoom,
            fallbackTileZoom = fallback_tile_zoom,
        )
    }

    private fun retained_frame_destination(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        destination: ReferenceRetainedDestination,
    ) {
        reference_retained_destination(
            frame_zoom = frame.zoom,
            frame_center_x = frame.center_x,
            frame_center_y = frame.center_y,
            frame_width = frame.width,
            frame_height = frame.height,
            viewport = viewport,
            destination = destination,
        )
    }

    private fun retained_frame_viewport(
        viewport: Viewport,
        tile_zoom: Int,
    ): Viewport {
        val padding = retained_frame_padding_px(viewport, tile_zoom)
        return viewport.copy(
            width = viewport.width + padding * 2f,
            height = viewport.height + padding * 2f
        )
    }

    private fun stage_zoom_ahead_labels(
        viewport: Viewport,
        source_zoom: Int,
        labels_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
    ): Int {
        if (!labels_enabled) {
            tile_repository.clear_zoom_ahead_desired_tile_keys()
            return 0
        }
        if (retained_label_coordinator.hasCurrentRequest()) return 0
        var future_viewport: Viewport? = null
        var step = 1
        while (step <= ZOOM_AHEAD_PRESENTATION_STEPS) {
            val future_zoom = reference_retained_zoom_ahead_target(
                viewportZoom = viewport.zoom,
                step = step,
                interval = ZOOM_AHEAD_PRESENTATION_DELTA,
                maximumZoom = MAX_ZOOM.toDouble(),
            ) ?: break
            val candidate = reference_retained_viewport_at_zoom(
                viewport = viewport,
                targetZoom = future_zoom,
            ) ?: break
            if (!retained_frames_have_covering_target(candidate, source_zoom, options)) {
                future_viewport = candidate
                break
            }
            step++
        }
        if (future_viewport == null) {
            tile_repository.clear_zoom_ahead_desired_tile_keys()
            return 0
        }

        val future_frame_viewport = retained_frame_viewport(
            viewport = future_viewport,
            tile_zoom = source_zoom,
        )
        build_visible_tile_list(
            output = zoom_ahead_visible_tiles,
            viewport = future_frame_viewport,
            core_viewport = future_viewport,
            tile_zoom = source_zoom,
            outline_visibility_zoom = viewport.zoom,
        )
        if (zoom_ahead_visible_tiles.isEmpty()) {
            tile_repository.clear_zoom_ahead_desired_tile_keys()
            return 0
        }

        val generation = tile_repository.update_zoom_ahead_desired_tile_keys(zoom_ahead_visible_tiles)
        zoom_ahead_draw_tiles.clear()
        var missing = 0
        var requested = 0
        for (tile in zoom_ahead_visible_tiles) {
            val parsed = tile_repository.tile(tile)
            if (parsed == null) {
                missing++
                if (requested < ZOOM_AHEAD_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    requested += tile_repository.request_tile_if_needed(tile, generation)
                }
            } else {
                zoom_ahead_draw_tiles += DictionaryTileDrawRef(
                    tile = parsed,
                    draw_x = tile.draw_x,
                    core_visible = tile.core_visible,
                )
            }
        }
        if (missing > 0) return requested

        tile_repository.clear_zoom_ahead_desired_tile_keys()
        request_retained_label_frame(
            role = RetainedFrameRole.TARGET,
            viewport = future_viewport,
            frame_viewport = future_frame_viewport,
            tile_zoom = source_zoom,
            tiles = zoom_ahead_draw_tiles,
            labels_enabled = true,
            label_text_scale = label_text_scale,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
            options = options,
            package_generation_snapshot = tile_repository.package_generation(),
        )
        return 0
    }

    private fun retained_frame_padding_px(
        viewport: Viewport,
        tile_zoom: Int,
    ): Int {
        val tile_size_px = MAP_TILE_SIZE * 2.0.pow(viewport.zoom - tile_zoom)
        return reference_retained_scene_padding_px(
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            tileSizePx = tile_size_px,
            maximumDimension = MAX_RETAINED_FRAME_SIZE,
            maximumTiles = MAX_TARGET_SCENE_TILES,
            repeatingWorldWidthPx = retained_repeating_world_width_px(
                viewport,
                tile_zoom,
            ),
        )
    }

    private fun retained_repeating_world_width_px(
        viewport: Viewport,
        tile_zoom: Int,
    ): Double? {
        if (tile_zoom != ReferenceDictionaryLodPolicy.LOW_ZOOM_SOURCE_LOD ||
            viewport.zoom > ReferenceDictionaryLodPolicy.LOW_ZOOM_SOURCE_LOD
        ) return null
        return MAP_TILE_SIZE * 2.0.pow(viewport.zoom)
    }

    private fun replace_retained_frame(next: RetainedReferenceFrame?) {
        val previous = retained_frame
        retained_frame = next
        if (previous != null && previous !== next) {
            retained_frame_history.remember(previous)
        }
    }

    private fun publish_retained_target_frame(
        frame: RetainedReferenceFrame,
        viewport: Viewport,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        options: RetainedReferenceOptions,
        current_generation: Long,
    ) {
        val active = retained_frame
        if (active == null) {
            replace_retained_frame(frame)
            return
        }
        if (active.matches_exact(
                viewport = viewport,
                next_tile_zoom = target_tile_zoom,
                next = options,
                current_package_generation = current_generation,
            )
        ) return
        if (active.matches_environment(viewport, options, current_generation) &&
            retained_target_frame_can_settle(
                frame = active,
                viewport = viewport,
                target_tile_zoom = target_tile_zoom,
                fallback_tile_zoom = fallback_tile_zoom,
                destination = retained_destination,
            )
        ) {
            retained_frame_history.remember(frame)
            return
        }
        replace_retained_frame(frame)
    }

    private fun promote_retained_frame(next: RetainedReferenceFrame) {
        if (retained_frame === next) return
        retained_frame_history.remove(next)
        replace_retained_frame(next)
    }

    private fun retained_reference_fade(
        previous: RetainedReferenceFrame,
        current: RetainedReferenceFrame,
        previous_destination: ReferenceRetainedDestination,
        current_destination: ReferenceRetainedDestination,
        start_ms: Long,
    ): RetainedReferenceFade {
        return RetainedReferenceFade(
            previous = previous,
            current = current,
            labels = reference_retained_label_transition(
                previous = previous.labels,
                current = current.labels,
                identity = DictionaryLabelCandidate::occurrenceId,
                samePlacement = { old, next ->
                    reference_retained_label_anchor_is_stable(
                        previousAnchor = old.anchor,
                        previousDestination = previous_destination,
                        previousFrameWidth = previous.width,
                        currentAnchor = next.anchor,
                        currentDestination = current_destination,
                        currentFrameWidth = current.width,
                    )
                },
            ),
            start_ms = start_ms,
        )
    }

    private fun retained_scene_padding_for_viewport(
        viewport: Viewport,
        tile_zoom: Int,
    ): Int {
        val active = retained_frame
        if (active != null &&
            active.tile_zoom == tile_zoom &&
            kotlin.math.abs(active.zoom - viewport.zoom) < EXACT_VIEWPORT_EPSILON &&
            active.visible_width == ceil(viewport.width).toInt().coerceAtLeast(1) &&
            active.visible_height == ceil(viewport.height).toInt().coerceAtLeast(1)
        ) {
            return minOf(
                (active.width - active.visible_width) / 2,
                (active.height - active.visible_height) / 2,
            ).coerceAtLeast(0)
        }
        return retained_frame_padding_px(viewport, tile_zoom)
    }

    private fun replace_retained_fallback_frame(next: RetainedReferenceFrame?) {
        if (retained_fallback_frame === next) return
        retained_fallback_frame = next
    }

    private fun cleanup_retained_fade() {
        if (retained_fade == null) return
        retained_fade = null
    }

    private fun partial_label_frame_for(
        viewport: Viewport,
        tile_zoom: Int,
        tiles: List<DictionaryTileDrawRef>,
        labels_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
    ): RetainedReferenceFrame? {
        val package_generation_snapshot = tile_repository.package_generation()
        partial_label_frame?.takeIf { frame ->
            frame.matches_exact(
                viewport = viewport,
                next_tile_zoom = tile_zoom,
                next = options,
                current_package_generation = package_generation_snapshot,
            )
        }?.let { return it }
        partial_label_frame = null
        if (!labels_enabled || viewport.zoom >= MIN_PATH_LABEL_ZOOM || tiles.isEmpty()) return null

        // Freeze the first partial point-label plan so incoming tiles cannot make labels jump.
        return create_retained_frame(
            boundary_batches = emptyList(),
            viewport = viewport,
            frame_viewport = viewport,
            tile_zoom = tile_zoom,
            tiles = tiles,
            labels_enabled = true,
            borders_enabled = false,
            label_text_scale = label_text_scale,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
            options = options,
            package_generation_snapshot = package_generation_snapshot,
        )?.takeIf { frame -> frame.labels.isNotEmpty() }
            ?.also { frame -> partial_label_frame = frame }
    }

    private fun clear_retained_frames() {
        retained_frame = null
        retained_fallback_frame = null
        partial_label_frame = null
        retained_fade = null
        displayed_retained_frame = null
        retained_frame_history.clear()
    }

    private fun draw_reference_content(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        labels_enabled: Boolean,
        borders_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): ReferenceDrawCounts {
        val groups = ReferenceDictionaryLayerGroups(
            places = place_labels_enabled,
            water = water_labels_enabled,
            regions = region_labels_enabled,
            public_lands = public_lands_enabled
        )
        var boundaries_drawn = 0
        if (borders_enabled) {
            boundaries_drawn = boundary_renderer.draw_records(
                canvas = canvas,
                viewport = viewport,
                tiles = tiles,
                groups = groups,
                filterMask = filter_mask,
            )
        }
        val labels_drawn = if (labels_enabled) {
            draw_labels(
                canvas = canvas,
                viewport = viewport,
                tiles = tiles,
                label_text_scale = label_text_scale,
                groups = groups,
                filter_mask = filter_mask,
                label_avoid_rects = label_avoid_rects,
            )
        } else {
            0
        }
        return ReferenceDrawCounts(boundaries = boundaries_drawn, labels = labels_drawn)
    }

    private fun draw_labels(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): Int {
        plan_labels(
            workspace = ui_label_workspace,
            viewport = viewport,
            tiles = tiles,
            label_text_scale = label_text_scale,
            groups = groups,
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
        )
        draw_planned_labels(canvas, ui_label_workspace)
        return ui_label_workspace.acceptedLabels.size
    }

    private fun plan_labels(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        include_buffer_tiles: Boolean = false,
        fixed_candidates: List<DictionaryLabelCandidate> = emptyList(),
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey> = emptySet(),
        excluded_rect: ReferenceScreenRect? = null,
        label_budget_override: Int? = null,
        protected_area_budget_override: Int? = null,
        keep_planning: () -> Boolean = ALWAYS_KEEP_LABEL_PLAN,
    ): Boolean {
        if (!keep_planning()) return abandon_label_plan(workspace)
        workspace.recordRefs.clear()
        workspace.candidates.clear()
        workspace.plannedCandidateIds.clear()
        workspace.acceptedLabels.clear()
        var encounter_order = 0
        for (tile in tiles) {
            if (!include_buffer_tiles && !tile.core_visible) continue
            for (record in tile.tile.labels) {
                if (encounter_order and 63 == 0 && !keep_planning()) {
                    return abandon_label_plan(workspace)
                }
                if (!label_record_visible_for_admission(record, viewport.zoom, groups, filter_mask)) {
                    continue
                }
                if (!label_record_intersects_viewport(viewport, tile, record)) continue
                workspace.recordRefs += DictionaryLabelRecordRef(
                    tile = tile,
                    record = record,
                    layout_priority = reference_low_zoom_label_priority(
                        record.priority,
                        record.low_zoom_country_rank,
                        viewport.zoom,
                    ),
                    layout_feature_id = layout_feature_id(record),
                    encounter_order = encounter_order++,
                )
            }
        }
        workspace.recordRefs.sortWith(
            compareBy<DictionaryLabelRecordRef> { it.layout_priority }
                .thenBy { it.layout_feature_id }
                .thenBy { it.encounter_order },
        )
        var preferred_seeds: List<DictionaryLabelCandidate> = emptyList()
        val selection_scratch: ArrayList<DictionaryLabelCandidate>?
        if (preferred_occurrences.isEmpty()) {
            selection_scratch = null
        } else {
            val seed_candidates = ArrayList<DictionaryLabelCandidate>()
            val seed_candidate_keys = HashSet<ReferenceLabelCandidateKey>()
            val prepass_complete = ReferenceLabelAdmissionPolicy.visitPreferredSeedRecords(
                records = workspace.recordRefs,
                preferredLookupKeys = preferred_candidate_keys,
                lookupKey = { record_ref ->
                    ReferenceLabelCandidateKey(
                        layout_candidate_id(record_ref.record),
                        rendered_label_world_copy(record_ref.tile, record_ref.record),
                    )
                },
                recordCandidateId = { record_ref -> record_ref.record.candidate_id },
                dedupeKey = { candidate_id, lookup_key ->
                    ReferenceLabelCandidateKey(
                        candidate_id,
                        lookup_key.rendered_world_copy,
                    )
                },
                visit = { record_ref, lookup_key, dedupe_key ->
                    keep_planning() && generate_label_record_candidates(
                        workspace = workspace,
                        viewport = viewport,
                        record_ref = record_ref,
                        label_text_scale = label_text_scale,
                        label_avoid_rects = label_avoid_rects,
                        excluded_rect = excluded_rect,
                        rendered_world_copy = lookup_key.rendered_world_copy,
                        candidate_key = dedupe_key,
                        planned_candidate_ids = seed_candidate_keys,
                        output = seed_candidates,
                        keep_planning = keep_planning,
                    )
                },
            )
            if (!prepass_complete) return abandon_label_plan(workspace)
            val retained_seeds = ArrayList<DictionaryLabelCandidate>()
            ReferenceLabelAdmissionPolicy.retainPreferredSeeds(
                candidates = seed_candidates,
                preferredOccurrences = preferred_occurrences,
                occurrenceId = DictionaryLabelCandidate::occurrenceId,
                output = retained_seeds,
            )
            seed_candidate_keys.clear()
            seed_candidates.clear()
            preferred_seeds = retained_seeds
            selection_scratch = ArrayList()
        }
        val budget = label_budget_override ?: label_budget(viewport)
        var selection_threshold = ReferenceLabelAdmissionPolicy.initial_threshold(budget)
        var last_selected_candidate_count = -1
        var record_index = 0
        while (record_index < workspace.recordRefs.size) {
            if (!keep_planning()) return abandon_label_plan(workspace)
            val first_ref = workspace.recordRefs[record_index]
            val block_priority = first_ref.layout_priority
            val block_feature_id = first_ref.layout_feature_id
            do {
                val record_ref = workspace.recordRefs[record_index]
                val rendered_world_copy =
                    rendered_label_world_copy(record_ref.tile, record_ref.record)
                val candidate_key = record_ref.record.candidate_id?.let { candidate_id ->
                    ReferenceLabelCandidateKey(candidate_id, rendered_world_copy)
                }
                if (!generate_label_record_candidates(
                        workspace = workspace,
                        viewport = viewport,
                        record_ref = record_ref,
                        label_text_scale = label_text_scale,
                        label_avoid_rects = label_avoid_rects,
                        excluded_rect = excluded_rect,
                        rendered_world_copy = rendered_world_copy,
                        candidate_key = candidate_key,
                        planned_candidate_ids = workspace.plannedCandidateIds,
                        output = workspace.candidates,
                        keep_planning = keep_planning,
                    )
                ) {
                    return abandon_label_plan(workspace)
                }
                record_index++
            } while (
                record_index < workspace.recordRefs.size &&
                    workspace.recordRefs[record_index].layout_priority == block_priority &&
                    workspace.recordRefs[record_index].layout_feature_id == block_feature_id
            )
            if (workspace.candidates.size >= selection_threshold) {
                accept_label_candidates(
                    workspace = workspace,
                    viewport = viewport,
                    label_avoid_rects = label_avoid_rects,
                    fixed_candidates = fixed_candidates,
                    preferred_occurrences = preferred_occurrences,
                    preferred_seeds = preferred_seeds,
                    priority_frontier = block_priority,
                    selection_scratch = selection_scratch,
                    label_budget = budget,
                    protected_area_budget = protected_area_budget_override
                        ?: protected_area_label_budget(viewport),
                )
                last_selected_candidate_count = workspace.candidates.size
                if (workspace.acceptedLabels.size >= budget) break
                selection_threshold =
                    ReferenceLabelAdmissionPolicy.next_threshold(workspace.candidates.size)
            }
        }
        if (
            workspace.acceptedLabels.size < budget &&
            last_selected_candidate_count != workspace.candidates.size
        ) {
            accept_label_candidates(
                workspace = workspace,
                viewport = viewport,
                label_avoid_rects = label_avoid_rects,
                fixed_candidates = fixed_candidates,
                preferred_occurrences = preferred_occurrences,
                preferred_seeds = preferred_seeds,
                priority_frontier =
                    workspace.recordRefs.getOrNull(record_index - 1)?.layout_priority,
                selection_scratch = selection_scratch,
                label_budget = budget,
                protected_area_budget = protected_area_budget_override
                    ?: protected_area_label_budget(viewport),
            )
        }
        workspace.recordRefs.clear()
        if (!keep_planning()) return abandon_label_plan(workspace)
        return true
    }

    private fun generate_label_record_candidates(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        record_ref: DictionaryLabelRecordRef,
        label_text_scale: Float,
        label_avoid_rects: List<ReferenceScreenRect>,
        excluded_rect: ReferenceScreenRect?,
        rendered_world_copy: Long,
        candidate_key: ReferenceLabelCandidateKey?,
        planned_candidate_ids: MutableSet<ReferenceLabelCandidateKey>,
        output: MutableList<DictionaryLabelCandidate>,
        keep_planning: () -> Boolean,
    ): Boolean {
        val tile = record_ref.tile
        val record = record_ref.record
        val line_candidate_already_planned = record.line_label &&
            candidate_key != null &&
            candidate_key in planned_candidate_ids
        if (line_candidate_already_planned) return true
        val style = label_style_for(record, label_text_scale, viewport.zoom)
        if (
            record.line_label &&
            record.geometry?.rings?.any { ring -> ring.point_count >= 2 } == true
        ) {
            if (!keep_planning()) return false
            val planned_candidates = line_label_candidates(
                workspace,
                viewport,
                tile,
                record,
                style,
                label_text_scale,
                label_avoid_rects,
                rendered_world_copy,
            )
            if (!keep_planning()) return false
            val admitted_candidates = if (excluded_rect == null) {
                planned_candidates
            } else {
                planned_candidates.filter { candidate ->
                    label_is_outside(candidate.collisionShape.bounds, excluded_rect)
                }
            }
            if (admitted_candidates.isNotEmpty()) {
                candidate_key?.let(planned_candidate_ids::add)
                output += admitted_candidates
            }
        } else if (!record.line_label) {
            point_label_candidate(
                workspace,
                viewport,
                tile,
                record,
                style,
                rendered_world_copy,
            )?.let { candidate ->
                val outside_excluded_rect = excluded_rect == null ||
                    label_is_outside(candidate.collisionShape.bounds, excluded_rect)
                if (outside_excluded_rect && (
                    candidate_key == null ||
                        planned_candidate_ids.add(candidate_key)
                    )
                ) {
                    output += candidate
                }
            }
        }
        return true
    }

    private fun abandon_label_plan(workspace: LabelPlanningWorkspace): Boolean {
        workspace.recordRefs.clear()
        workspace.candidates.clear()
        workspace.plannedCandidateIds.clear()
        workspace.acceptedLabels.clear()
        return false
    }

    private fun draw_planned_labels(canvas: Canvas, workspace: LabelPlanningWorkspace) {
        for (index in workspace.acceptedLabels.indices.reversed()) {
            draw_label_candidate(canvas, workspace.acceptedLabels[index])
        }
    }

    private fun label_record_visible_for_admission(
        record: DictionaryLabelRecord,
        zoom: Double,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
    ): Boolean {
        if (!record.drawable || !record.visible_at(zoom)) return false
        if (record.line_label && zoom < MIN_PATH_LABEL_ZOOM) return false
        if (!filter_mask.allows(record.filter_id, groups.enabled(record.layer_group))) return false
        val visibility_alpha_milli = label_visibility_alpha_milli(record, zoom)
        return ReferenceLabelRuntimePresentationPolicy.hasVisiblePaintAlpha(
            record.alpha,
            record.halo_alpha,
            visibility_alpha_milli,
        )
    }

    private fun label_record_intersects_viewport(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
    ): Boolean {
        record.anchor?.let { anchor ->
            val point = project_point(viewport, tile, anchor.x, anchor.y)
            return point.x in 0f..viewport.width && point.y in 0f..viewport.height
        }
        val bounds = record.geometry?.bounds ?: return true
        val top_left = project_point(viewport, tile, bounds.min_x, bounds.min_y)
        val bottom_right = project_point(viewport, tile, bounds.max_x, bounds.max_y)
        return top_left.x <= viewport.width && bottom_right.x >= 0f &&
            top_left.y <= viewport.height && bottom_right.y >= 0f
    }

    private fun layout_candidate_id(record: DictionaryLabelRecord): ULong =
        record.candidate_id
            ?: (record.dedupe_key ?: record.text).hashCode().toUInt().toULong()

    private fun layout_feature_id(record: DictionaryLabelRecord): ULong =
        record.source_feature_id ?: layout_candidate_id(record)

    private fun rendered_label_world_copy(
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
    ): Long = ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
        zoom = tile.tile.coordinate.z,
        requestedTileX = tile.tile.coordinate.x,
        drawTileX = tile.draw_x,
        postingWorldWrap = record.posting_world_wrap,
    )

    private fun line_label_candidates(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle,
        label_text_scale: Float,
        label_avoid_rects: List<ReferenceScreenRect>,
        rendered_world_copy: Long,
    ): List<DictionaryLabelCandidate> {
        val is_water = record.is_water ?: (record.source_kind == "water")
        val geometry = record.geometry ?: return emptyList()
        val scale = 2.0.pow(viewport.zoom - tile.tile.coordinate.z)
        val local_to_screen = (MAP_TILE_SIZE / DICTIONARY_EXTENT) * scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val prepared_path = ReferenceVisiblePathProjector.prepare(
            parts = geometry.rings.mapIndexed { part_index, ring ->
                ReferenceRawPathPart(part_index, ring.points, ring.point_count)
            },
            transform = ReferencePathProjectionTransform(
                scaleX = local_to_screen,
                scaleY = local_to_screen,
                translateX = tile.draw_x * MAP_TILE_SIZE * scale - left_world,
                translateY = tile.tile.coordinate.y * MAP_TILE_SIZE * scale - top_world,
            ),
            viewport = ReferenceScreenRect(
                left = 0.0,
                top = 0.0,
                right = viewport.width.toDouble(),
                bottom = viewport.height.toDouble(),
            ),
        )
        if (prepared_path.parts.isEmpty()) return emptyList()
        val candidate_id = layout_candidate_id(record)
        val minimum_water_text_size_px =
            label_scaled_density * MIN_WATER_LINE_TEXT_SIZE_SP * label_text_scale
        val viewport_diagonal = hypot(viewport.width.toDouble(), viewport.height.toDouble())
        var selected_style = style
        var selected_collision_radius = 0f
        var placements: List<ReferencePathLabelPlacement> = emptyList()
        var tangent_fallback_style = style
        var tangent_fallback_collision_radius = 0f
        var tangent_fallback_request: ReferencePathLabelRequest? = null
        var previous_text_size = -1f
        for (attempt_index in 0 until ReferenceLineLabelFitPolicy.attemptCount(is_water)) {
            val fitted_text_size = ReferenceLineLabelFitPolicy.textSizePx(
                baseTextSizePx = style.text_size,
                minimumTextSizePx = minimum_water_text_size_px,
                isWater = is_water,
                attemptIndex = attempt_index,
            )
            if (fitted_text_size == previous_text_size) continue
            previous_text_size = fitted_text_size
            val fitted_style = if (fitted_text_size == style.text_size) {
                style
            } else {
                style.copy(text_size = fitted_text_size)
            }
            prepare_planning_text_paint(
                workspace,
                fitted_style,
                fitted_style.font_weight,
                fitted_style.italic,
                fitted_style.text_size,
            )
            val presentation = record.sourced_text?.let {
                SourcedMapTextPresentation.plan(it, fitted_style.text_size)
            }
            val primary_width = measure_primary_label_width(
                workspace,
                fitted_style,
                presentation,
                record.text,
            )
            val english_width = presentation?.english?.let { english ->
                prepare_planning_text_paint(
                    workspace,
                    fitted_style,
                    ENGLISH_FONT_WEIGHT,
                    ENGLISH_ITALIC,
                    english.textSize,
                )
                workspace.textPaint.measureText(english.text)
            } ?: 0f
            prepare_planning_text_paint(
                workspace,
                fitted_style,
                fitted_style.font_weight,
                fitted_style.italic,
                fitted_style.text_size,
            )
            val text_width = max(primary_width, english_width)
            val collision_height = presentation?.collisionHeightEm
                ?.times(fitted_style.text_size)
                ?: fitted_style.text_size
            val collision_radius = collision_height / 2f +
                label_halo_width_px(fitted_style, fitted_style.text_size) +
                LABEL_COLLISION_PADDING_PX
            val policy_edge_clearance = fitted_style.text_size *
                ReferencePresentationPolicy.label_edge_clearance_milli_em / 1_000f
            val attempt_request = ReferencePathLabelRequest(
                    parts = emptyList(),
                    viewport = ReferenceScreenRect(
                        left = 0.0,
                        top = 0.0,
                        right = viewport.width.toDouble(),
                        bottom = viewport.height.toDouble(),
                    ),
                    shapedAdvancePx = text_width.toDouble(),
                    endClearancePx = (
                        fitted_style.text_size *
                            ReferencePresentationPolicy.label_end_clearance_milli_em / 1_000f
                        ).toDouble(),
                    edgeClearancePx = max(collision_radius, policy_edge_clearance).toDouble(),
                    maxBendDegrees = (
                        record.visibility_rule?.max_bend_centi_degrees
                            ?: ReferencePresentationPolicy.max_line_label_bend_centi_degrees
                        ) / 100.0,
                    candidateId = candidate_id,
                    repeatSpacingPx = record.repeat_spacing_px
                        ?.takeIf { it > 0 }
                        ?: ReferencePresentationPolicy.line_label_repeat_spacing_px,
                    prominenceTier = record.prominence_tier ?: ProminenceTier.LOCAL,
                    staticAvoidRects = label_avoid_rects,
                    maximumTangentOffsetPx = if (is_water) {
                        0.0
                    } else {
                        max(boundary_density * 48f, collision_radius * 4f).toDouble()
                    },
                    maximumTangentSourceDistancePx = if (is_water) {
                        collision_radius.toDouble()
                    } else {
                        viewport_diagonal
                    },
                    maximumCurvedSourceDistancePx = if (is_water) {
                        (
                            collision_radius * WATER_CURVED_SOURCE_DISTANCE_FRACTION
                            ).toDouble()
                    } else {
                        0.0
                    },
                    allowTangentFallback = !is_water,
                )
            if (is_water) {
                tangent_fallback_style = fitted_style
                tangent_fallback_collision_radius = collision_radius
                tangent_fallback_request = attempt_request.copy(
                    allowCurvedPlacement = false,
                    allowTangentFallback = true,
                )
            }
            val attempt_placements = ReferencePathLabelPlanner.planPrepared(
                attempt_request,
                prepared_path,
            )
            val has_curved_placement = attempt_placements.any { placement ->
                placement.mode == ReferencePathLabelPlacementMode.CURVED
            }
            if (
                attempt_placements.isNotEmpty() &&
                ReferenceLineLabelFitPolicy.acceptAttempt(is_water, has_curved_placement)
            ) {
                selected_style = fitted_style
                selected_collision_radius = collision_radius
                placements = attempt_placements
                break
            }
        }
        if (placements.isEmpty()) {
            tangent_fallback_request?.let { fallback_request ->
                val fallback_placements = ReferencePathLabelPlanner.planPrepared(
                    fallback_request,
                    prepared_path,
                )
                if (fallback_placements.isNotEmpty()) {
                    selected_style = tangent_fallback_style
                    selected_collision_radius = tangent_fallback_collision_radius
                    placements = fallback_placements
                }
            }
        }
        if (placements.isEmpty()) {
            return if (record.candidate_id == null && !is_water) {
                listOfNotNull(
                    point_label_candidate(
                        workspace,
                        viewport,
                        tile,
                        record,
                        style,
                        rendered_world_copy,
                    )
                )
            } else {
                emptyList()
            }
        }
        val selected_presentation = record.sourced_text?.let {
            SourcedMapTextPresentation.plan(it, selected_style.text_size)
        }
        return ReferenceLineLabelPlacementAdapter.fromPlanner(
            placements,
            rendered_world_copy,
        ).map { option ->
            DictionaryLabelCandidate(
                text = record.text,
                source_kind = record.source_kind,
                presentation = selected_presentation,
                style = selected_style,
                occurrenceId = option.occurrenceId,
                featureId = record.source_feature_id ?: candidate_id,
                priority = reference_low_zoom_label_priority(
                    record.priority,
                    record.low_zoom_country_rank,
                    viewport.zoom,
                ),
                placementRank = option.placementRank,
                protectedArea = record.protected_area,
                waterLine = is_water,
                anchor = option.placement.anchor,
                collisionShape = referencePathCollisionShape(
                    option.placement.presentationPath,
                    selected_collision_radius.toDouble(),
                ),
                path_points = option.placement.presentationPath,
            )
        }
    }

    private fun point_label_candidate(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle,
        rendered_world_copy: Long,
    ): DictionaryLabelCandidate? {
        val anchor = label_anchor(viewport, tile, record) ?: return null
        val presentation = record.sourced_text?.let {
            SourcedMapTextPresentation.plan(it, style.text_size)
        }
        prepare_planning_text_paint(
            workspace,
            style,
            style.font_weight,
            style.italic,
            style.text_size,
        )
        val primary_width = measure_primary_label_width(
            workspace,
            style,
            presentation,
            record.text,
        )
        val english_width = presentation?.english?.let { english ->
            prepare_planning_text_paint(
                workspace,
                style,
                ENGLISH_FONT_WEIGHT,
                ENGLISH_ITALIC,
                english.textSize,
            )
            workspace.textPaint.measureText(english.text)
        } ?: 0f
        val candidate_id = layout_candidate_id(record)
        val label_bounds = label_bbox(
            record.text,
            anchor.x,
            anchor.y,
            style.text_size,
            width_override = max(primary_width, english_width),
            height_override = presentation?.collisionHeightEm?.times(style.text_size),
        )
        val collision_padding =
            label_halo_width_px(style, style.text_size) + LABEL_COLLISION_PADDING_PX
        return DictionaryLabelCandidate(
            text = record.text,
            source_kind = record.source_kind,
            presentation = presentation,
            style = style,
            occurrenceId = ReferenceLabelOccurrenceId(
                candidate_id,
                0L,
                rendered_world_copy,
            ),
            featureId = record.source_feature_id ?: candidate_id,
            priority = reference_low_zoom_label_priority(
                record.priority,
                record.low_zoom_country_rank,
                viewport.zoom,
            ),
            placementRank = 0,
            protectedArea = record.protected_area,
            waterLine = false,
            anchor = ReferencePathLabelPoint(anchor.x.toDouble(), anchor.y.toDouble()),
            collisionShape = ReferenceLabelCollisionShape.Box(
                ReferenceScreenRect(
                    left = label_bounds.left.toDouble() - collision_padding,
                    top = label_bounds.top.toDouble() - collision_padding,
                    right = label_bounds.right.toDouble() + collision_padding,
                    bottom = label_bounds.bottom.toDouble() + collision_padding,
                ),
            ),
            rotation = record.rotation
        )
    }

    private fun accept_label_candidates(
        workspace: LabelPlanningWorkspace,
        viewport: Viewport,
        label_avoid_rects: List<ReferenceScreenRect>,
        fixed_candidates: List<DictionaryLabelCandidate> = emptyList(),
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_seeds: List<DictionaryLabelCandidate> = emptyList(),
        priority_frontier: Int? = null,
        selection_scratch: MutableList<DictionaryLabelCandidate>? = null,
        label_budget: Int = label_budget(viewport),
        protected_area_budget: Int = protected_area_label_budget(viewport),
    ) {
        val selection_candidates = if (selection_scratch == null || priority_frontier == null) {
            workspace.candidates
        } else {
            selection_scratch.clear()
            selection_scratch += workspace.candidates
            ReferenceLabelAdmissionPolicy.appendActivePreferredSeeds(
                seeds = preferred_seeds,
                priorityFrontier = priority_frontier,
                priority = DictionaryLabelCandidate::priority,
                output = selection_scratch,
            )
            selection_scratch
        }
        workspace.acceptedLabels.clear()
        workspace.acceptedLabels += ReferenceLabelLayoutSelector.select(
            candidates = selection_candidates,
            fixedCandidates = fixed_candidates,
            preferredOccurrences = preferred_occurrences,
            viewport = ReferenceScreenRect(
                0.0,
                0.0,
                viewport.width.toDouble(),
                viewport.height.toDouble(),
            ),
            staticAvoidRects = label_avoid_rects,
            labelBudget = label_budget,
            protectedAreaBudget = protected_area_budget,
            waterRepeatDistancePx = WATER_LINE_LABEL_REPEAT_DISTANCE_PX.toDouble(),
            singleWaterLabelPerFeature = viewport.zoom <= WATER_SINGLE_LABEL_MAX_ZOOM,
        )
    }

    private fun measure_primary_label_width(
        workspace: LabelPlanningWorkspace,
        style: DictionaryLabelStyle,
        presentation: SourcedMapTextPresentationPlan?,
        fallback_text: String,
    ): Float {
        if (presentation == null) return workspace.textPaint.measureText(fallback_text)
        var width = 0f
        for (line in presentation.primaryLines) {
            prepare_planning_text_paint(
                workspace,
                style,
                style.font_weight,
                style.italic,
                line.textSize,
            )
            width = max(width, workspace.textPaint.measureText(line.text))
        }
        return width
    }

    private fun label_is_outside(
        label: ReferenceScreenRect,
        core: ReferenceScreenRect,
    ): Boolean {
        return label.right <= core.left || label.left >= core.right ||
            label.bottom <= core.top || label.top >= core.bottom
    }

    private fun label_budget(viewport: Viewport): Int {
        val area_budget = (viewport.width * viewport.height / LABEL_AREA_PER_ITEM_PX)
            .roundToInt()
            .coerceIn(MIN_LABELS_PER_VIEWPORT, MAX_LABELS_PER_VIEWPORT)
        return when {
            viewport.zoom < 8.5 -> (area_budget - 4).coerceAtLeast(MIN_LABELS_PER_VIEWPORT)
            viewport.zoom > 11.5 -> (area_budget + 3).coerceAtMost(MAX_LABELS_PER_VIEWPORT)
            else -> area_budget
        }
    }

    private fun protected_area_label_budget(viewport: Viewport): Int {
        return when {
            viewport.zoom < 8.5 -> 1
            viewport.zoom < 10.5 -> 2
            else -> 3
        }
    }

    private fun draw_label_candidate(
        canvas: Canvas,
        candidate: DictionaryLabelCandidate,
        zoom_scale: Double = 1.0,
        translate_x: Double = 0.0,
        translate_y: Double = 0.0,
        frame_alpha: Int = 255,
    ) {
        val style = candidate.style
        val presentation = candidate.presentation
        val path_points = candidate.path_points
        if (path_points != null) {
            line_label_path.reset()
            path_points.forEachIndexed { index, point ->
                val x = (translate_x + point.x * zoom_scale).toFloat()
                val y = (translate_y + point.y * zoom_scale).toFloat()
                if (index == 0) {
                    line_label_path.moveTo(x, y)
                } else {
                    line_label_path.lineTo(x, y)
                }
            }
            if (presentation == null) {
                draw_text_on_path(
                    canvas,
                    candidate.text,
                    line_label_path,
                    style,
                    style.text_size,
                    style.font_weight,
                    style.italic,
                    0f,
                    frame_alpha,
                )
            } else {
                for (line in presentation.primaryLines) {
                    draw_text_on_path(
                        canvas,
                        line.text,
                        line_label_path,
                        style,
                        line.textSize,
                        style.font_weight,
                        style.italic,
                        line.baselineOffset,
                        frame_alpha,
                    )
                }
                presentation.english?.let { english ->
                    draw_text_on_path(
                        canvas,
                        english.text,
                        line_label_path,
                        style,
                        english.textSize,
                        ENGLISH_FONT_WEIGHT,
                        ENGLISH_ITALIC,
                        english.baselineOffset,
                        frame_alpha,
                    )
                }
            }
            return
        }
        val x = (translate_x + candidate.anchor.x * zoom_scale).toFloat()
        val y = (translate_y + candidate.anchor.y * zoom_scale).toFloat()
        canvas.withSave {
            if (candidate.rotation != 0f) rotate(candidate.rotation, x, y)
            if (presentation == null) {
                draw_text_with_halo(
                    canvas,
                    candidate.text,
                    x,
                    y,
                    style,
                    style.text_size,
                    style.font_weight,
                    style.italic,
                    frame_alpha,
                )
            } else {
                for (line in presentation.primaryLines) {
                    draw_text_with_halo(
                        canvas,
                        line.text,
                        x,
                        y + line.baselineOffset,
                        style,
                        line.textSize,
                        style.font_weight,
                        style.italic,
                        frame_alpha,
                    )
                }
                presentation.english?.let { english ->
                    draw_text_with_halo(
                        canvas,
                        english.text,
                        x,
                        y + english.baselineOffset,
                        style,
                        english.textSize,
                        ENGLISH_FONT_WEIGHT,
                        ENGLISH_ITALIC,
                        frame_alpha,
                    )
                }
            }
        }
    }

    private fun draw_text_on_path(
        canvas: Canvas,
        text: String,
        label_path: Path,
        style: DictionaryLabelStyle,
        text_size: Float,
        font_weight: Int,
        italic: Boolean,
        baseline_offset: Float,
        frame_alpha: Int,
    ) {
        prepare_text_paint(style, font_weight, italic, text_size)
        path_measure.setPath(label_path, false)
        val path_length = path_measure.length
        text_paint.textAlign = Paint.Align.LEFT
        val h_offset = ((path_length - text_paint.measureText(text)) / 2f).coerceAtLeast(0f)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = label_halo_width_px(style, text_size)
        text_paint.color = with_alpha(
            style.halo_color,
            scaled_alpha_byte(style.halo_alpha, 1_000, frame_alpha),
        )
        canvas.drawTextOnPath(text, label_path, h_offset, baseline_offset, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(
            style.color,
            scaled_alpha_byte(style.alpha, 1_000, frame_alpha),
        )
        canvas.drawTextOnPath(text, label_path, h_offset, baseline_offset, text_paint)
    }

    private fun draw_text_with_halo(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        style: DictionaryLabelStyle,
        text_size: Float,
        font_weight: Int,
        italic: Boolean,
        frame_alpha: Int,
    ) {
        prepare_text_paint(style, font_weight, italic, text_size)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = label_halo_width_px(style, text_size)
        text_paint.color = with_alpha(
            style.halo_color,
            scaled_alpha_byte(style.halo_alpha, 1_000, frame_alpha),
        )
        canvas.drawText(text, x, y, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(
            style.color,
            scaled_alpha_byte(style.alpha, 1_000, frame_alpha),
        )
        canvas.drawText(text, x, y, text_paint)
    }

    private fun prepare_text_paint(
        style: DictionaryLabelStyle,
        font_weight: Int,
        italic: Boolean,
        text_size: Float,
    ) {
        configure_text_paint(
            target = text_paint,
            typeface_cache = ui_label_workspace.typefaceCache,
            style = style,
            font_weight = font_weight,
            italic = italic,
            text_size = text_size,
        )
    }

    private fun prepare_planning_text_paint(
        workspace: LabelPlanningWorkspace,
        style: DictionaryLabelStyle,
        font_weight: Int,
        italic: Boolean,
        text_size: Float,
    ) {
        configure_text_paint(
            target = workspace.textPaint,
            typeface_cache = workspace.typefaceCache,
            style = style,
            font_weight = font_weight,
            italic = italic,
            text_size = text_size,
        )
    }

    private fun configure_text_paint(
        target: Paint,
        typeface_cache: MutableMap<Int, Typeface>,
        style: DictionaryLabelStyle,
        font_weight: Int,
        italic: Boolean,
        text_size: Float,
    ) {
        target.isAntiAlias = true
        target.isSubpixelText = true
        val typeface_key = font_weight * 2 + if (italic) 1 else 0
        target.typeface = typeface_cache.getOrPut(typeface_key) {
            Typeface.create(Typeface.DEFAULT, font_weight, italic)
        }
        target.textAlign = Paint.Align.CENTER
        target.textSize = text_size
        target.textScaleX = 1f
        target.isFakeBoldText = false
        target.textSkewX = 0f
        target.letterSpacing = style.letter_spacing_em
    }

    private fun label_halo_width_px(
        style: DictionaryLabelStyle,
        text_size: Float,
    ): Float = max(
        boundary_density * style.halo_width_dp,
        text_size * style.halo_width_milli_em / 1_000f,
    )

    private fun label_anchor(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord
    ): DictionaryScreenPoint? {
        record.anchor?.let { anchor ->
            return project_point(viewport, tile, anchor.x, anchor.y)
        }
        record.geometry?.bounds?.let { bounds ->
            return project_point(
                viewport = viewport,
                tile = tile,
                local_x = (bounds.min_x + bounds.max_x) / 2f,
                local_y = (bounds.min_y + bounds.max_y) / 2f
            )
        }
        return null
    }

    private fun project_point(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        local_x: Float,
        local_y: Float
    ): DictionaryScreenPoint {
        val scale = 2.0.pow(viewport.zoom - tile.tile.coordinate.z)
        val local_to_screen = (MAP_TILE_SIZE / DICTIONARY_EXTENT) * scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val origin_x = tile.draw_x * MAP_TILE_SIZE * scale - left_world
        val origin_y = tile.tile.coordinate.y * MAP_TILE_SIZE * scale - top_world
        return DictionaryScreenPoint(
            x = (origin_x + local_x * local_to_screen).toFloat(),
            y = (origin_y + local_y * local_to_screen).toFloat()
        )
    }

    private fun label_style_for(
        record: DictionaryLabelRecord,
        label_text_scale: Float,
        zoom: Double,
    ): DictionaryLabelStyle {
        val scale = label_text_scale.coerceIn(MAP_LABEL_TEXT_SCALE_MIN, MAP_LABEL_TEXT_SCALE_MAX)
        val runtime_typography = record.runtime_typography
        val visibility_alpha_milli = label_visibility_alpha_milli(record, zoom)
        fun scaled_alpha(alpha: Int): Int {
            return ((alpha * visibility_alpha_milli) + 500) / 1_000
        }
        val text_size_sp = runtime_typography?.textSizeMilliSp?.div(1_000f)
            ?: record.base_text_size_sp
        val halo_width_milli_em = runtime_typography?.haloWidthMilliEm
            ?: LEGACY_LABEL_HALO_WIDTH_MILLI_EM
        return DictionaryLabelStyle(
            color = record.color,
            text_size = label_scaled_density * text_size_sp * scale,
            alpha = scaled_alpha(record.alpha),
            halo_color = record.halo_color,
            halo_width_dp = if (runtime_typography == null) {
                record.halo_width_dp
            } else {
                0f
            },
            halo_width_milli_em = halo_width_milli_em,
            halo_alpha = scaled_alpha(record.halo_alpha),
            font_weight = runtime_typography?.fontWeight
                ?: legacy_font_weight(record.typeface_style),
            italic = runtime_typography?.italic ?: legacy_italic(record.typeface_style),
            letter_spacing_em = (
                runtime_typography?.letterSpacingMilliEm?.div(1_000f)
                    ?: record.letter_spacing_em
            ),
        )
    }

    private fun label_visibility_alpha_milli(
        record: DictionaryLabelRecord,
        zoom: Double,
    ): Int {
        val current_centizoom = ReferencePresentationPolicy.centizoom(zoom)
        val package_visibility_alpha_milli = record.visibility_rule?.let { rule ->
            ReferencePresentationPolicy.label_alpha_milli(rule, current_centizoom)
        } ?: ReferencePresentationPolicy.full_alpha_milli
        val runtime_visibility_alpha_milli = record.presentation_subtype?.let { subtype ->
            ReferenceLabelRuntimePresentationPolicy.visibilityAlphaMilli(
                subtype,
                record.prominence_tier
                    ?: ReferencePresentationPolicy.default_prominence_for_subtype(subtype),
                current_centizoom,
            )
        } ?: ReferencePresentationPolicy.full_alpha_milli
        return (
            package_visibility_alpha_milli * runtime_visibility_alpha_milli + 500
        ) / 1_000
    }

    private fun legacy_font_weight(typeface_style: Int): Int = when (typeface_style) {
        Typeface.BOLD,
        Typeface.BOLD_ITALIC -> 700
        else -> 400
    }

    private fun legacy_italic(typeface_style: Int): Boolean = when (typeface_style) {
        Typeface.ITALIC,
        Typeface.BOLD_ITALIC -> true
        else -> false
    }

    private fun label_bbox(
        text: String,
        x: Float,
        y: Float,
        font_size: Float,
        width_override: Float? = null,
        height_override: Float? = null,
    ): RectF {
        val width_estimate = max(24f, width_override ?: (text.length * font_size * 0.58f))
        val height_estimate = height_override ?: (font_size * 1.45f)
        return RectF(
            x - width_estimate / 2f,
            y - height_estimate / 2f,
            x + width_estimate / 2f,
            y + height_estimate / 2f
        )
    }

    private fun ready_empty_stats(): ReferenceDictionaryOverlayDrawStats {
        return ReferenceDictionaryOverlayDrawStats(
            available = true,
            ready = true,
            visible_tiles = 0,
            loaded_tiles = 0,
            requested_tiles = 0,
            boundaries_drawn = 0,
            labels_drawn = 0,
            retained_frame_drawn = false,
            fading = false
        )
    }

    private fun unavailable_stats(): ReferenceDictionaryOverlayDrawStats {
        return ReferenceDictionaryOverlayDrawStats(
            available = false,
            ready = false,
            visible_tiles = 0,
            loaded_tiles = 0,
            requested_tiles = 0,
            boundaries_drawn = 0,
            labels_drawn = 0,
            retained_frame_drawn = false,
            fading = false
        )
    }

    private enum class RetainedFrameRole { FALLBACK, TARGET }

    private data class RetainedLabelTileKey(
        val z: Int,
        val x: Int,
        val y: Int,
        val drawX: Int,
        val coreVisible: Boolean,
        val identity: Int,
    )

    private data class RetainedLabelPlanKey(
        val role: RetainedFrameRole,
        val viewport: Viewport,
        val frameViewport: Viewport,
        val tileZoom: Int,
        val tiles: List<RetainedLabelTileKey>,
        val options: RetainedReferenceOptions,
        val packageGeneration: Long,
        val labelAvoidRects: List<ReferenceScreenRect>,
    )

    private data class RetainedLabelPlanRequest(
        val token: ReferenceRetainedBitmapRequestToken<RetainedLabelPlanKey>,
        val tiles: List<DictionaryTileDrawRef>,
        val preferredLabels: List<DictionaryLabelCandidate>,
        val labelsEnabled: Boolean,
        val labelTextScale: Float,
        val placeLabelsEnabled: Boolean,
        val waterLabelsEnabled: Boolean,
        val regionLabelsEnabled: Boolean,
        val publicLandsEnabled: Boolean,
        val filterMask: ReferenceFilterMask,
    )

    private data class PreparedRetainedLabelFrame(
        val request: RetainedLabelPlanRequest,
        val frame: RetainedReferenceFrame?,
    )

    private data class RetainedBoundaryBitmapTileKey(
        val z: Int,
        val x: Int,
        val y: Int,
        val draw_x: Int,
        val identity: Int,
    )

    private data class RetainedBoundaryBitmapKey(
        val role: RetainedFrameRole,
        val viewport: Viewport,
        val tiles: List<RetainedBoundaryBitmapTileKey>,
        val place_labels_enabled: Boolean,
        val water_labels_enabled: Boolean,
        val region_labels_enabled: Boolean,
        val public_lands_enabled: Boolean,
        val filter_mask_key: Int,
        val package_generation: Long,
    )

    private data class RetainedBoundaryBitmapRequest(
        val token: ReferenceRetainedBitmapRequestToken<RetainedBoundaryBitmapKey>,
        val content_viewport: Viewport,
        val viewport: Viewport,
        val tile_zoom: Int,
        val tiles: List<DictionaryTileDrawRef>,
        val place_labels_enabled: Boolean,
        val water_labels_enabled: Boolean,
        val region_labels_enabled: Boolean,
        val public_lands_enabled: Boolean,
        val filter_mask: ReferenceFilterMask,
    )

    private data class PreparedRetainedBoundaryBitmap(
        val request: RetainedBoundaryBitmapRequest,
        val batches: List<BoundaryPathBatch>,
        val complete: Boolean,
    )


    private class LabelPlanningWorkspace(
        val textPaint: Paint,
    ) {
        val typefaceCache = HashMap<Int, Typeface>(8)
        val recordRefs = ArrayList<DictionaryLabelRecordRef>(512)
        val candidates = ArrayList<DictionaryLabelCandidate>(256)
        val plannedCandidateIds = HashSet<ReferenceLabelCandidateKey>(256)
        val acceptedLabels = ArrayList<DictionaryLabelCandidate>(128)
    }

    private data class RetainedReferenceFrame(
        val boundary_batches: List<BoundaryPathBatch>,
        val zoom: Double,
        val center_x: Double,
        val center_y: Double,
        val tile_zoom: Int,
        val width: Int,
        val height: Int,
        val visible_width: Int,
        val visible_height: Int,
        val options: RetainedReferenceOptions,
        val package_generation: Long,
        val boundaries_drawn: Int,
        val labels: List<DictionaryLabelCandidate>,
        val label_coverage: ReferenceRetainedLabelCoverage,
    ) {
        val labels_drawn: Int get() = labels.size

        fun matches_options(next: RetainedReferenceOptions): Boolean {
            return options == next
        }

        fun matches_environment(
            viewport: Viewport,
            next: RetainedReferenceOptions,
            current_package_generation: Long,
        ): Boolean {
            return matches_options(next) &&
                package_generation == current_package_generation &&
                visible_width == ceil(viewport.width).toInt().coerceAtLeast(1) &&
                visible_height == ceil(viewport.height).toInt().coerceAtLeast(1)
        }

        fun matches_exact(
            viewport: Viewport,
            next_tile_zoom: Int,
            next: RetainedReferenceOptions,
            current_package_generation: Long,
        ): Boolean {
            return matches_environment(viewport, next, current_package_generation) &&
                    tile_zoom == next_tile_zoom &&
                    kotlin.math.abs(zoom - viewport.zoom) < EXACT_VIEWPORT_EPSILON &&
                    kotlin.math.abs(center_x - viewport.center_x) < EXACT_VIEWPORT_EPSILON &&
                    kotlin.math.abs(center_y - viewport.center_y) < EXACT_VIEWPORT_EPSILON
        }

        fun has_content(): Boolean {
            return reference_retained_scene_has_content(
                labelCount = labels.size,
                boundaryBatchCount = boundary_batches.size,
            )
        }

        fun is_drawable(): Boolean {
            return has_content()
        }

        fun has_visible_content(
            viewport: Viewport,
            destination: ReferenceRetainedDestination,
        ): Boolean {
            if (boundary_batches.isNotEmpty()) return true
            if (labels.isEmpty()) return false
            val zoom_scale = (destination.right - destination.left) / width
            if (!zoom_scale.isFinite() || zoom_scale <= 0.0) return false
            return label_coverage.intersects(
                left = -destination.left / zoom_scale,
                top = -destination.top / zoom_scale,
                right = (viewport.width - destination.left) / zoom_scale,
                bottom = (viewport.height - destination.top) / zoom_scale,
            )
        }
    }

    private data class ReferenceLabelCandidateKey(
        val candidate_id: ULong,
        val rendered_world_copy: Long,
    )

    private data class RetainedReferenceFade(
        val previous: RetainedReferenceFrame,
        val current: RetainedReferenceFrame,
        val labels: ReferenceRetainedLabelTransition<DictionaryLabelCandidate>,
        val start_ms: Long
    )

    private companion object {
        val ALWAYS_KEEP_LABEL_PLAN: () -> Boolean = { true }
        const val MAP_TILE_SIZE = 256.0
        const val DICTIONARY_EXTENT = 4096.0
        const val MAX_MEMORY_TILES = 256
        const val MAX_LOW_ZOOM_MEMORY_TILES = 256
        const val DICTIONARY_TILE_WORKERS = 4
        const val PRESENTATION_CANCELLATION_INTERVAL = 64
        const val MAX_LABEL_TEXT_LENGTH = 48
        const val MAP_LABEL_TEXT_SCALE_MIN = 1f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val MIN_PATH_LABEL_ZOOM = 10.0
        const val LEGACY_LABEL_HALO_WIDTH_MILLI_EM = 220
        const val ENGLISH_FONT_WEIGHT = 400
        const val ENGLISH_ITALIC = true
        const val LABEL_COLLISION_PADDING_PX = 4f
        const val LABEL_SCREEN_PADDING_X = 96f
        const val LABEL_SCREEN_PADDING_Y = 72f
        const val LABEL_AREA_PER_ITEM_PX = 118_000f
        const val MIN_LABELS_PER_VIEWPORT = 9
        const val MAX_LABELS_PER_VIEWPORT = 22
        const val MIN_LINE_LABEL_PATH_FRACTION = 0.8f
        const val MIN_WATER_LINE_LABEL_PATH_FRACTION = 0.62f
        const val WATER_LINE_LABEL_REPEAT_DISTANCE_PX = 520f
        const val WATER_SINGLE_LABEL_MAX_ZOOM = 10.5
        const val MIN_WATER_LINE_TEXT_SIZE_SP = 8.25f
        const val WATER_CURVED_SOURCE_DISTANCE_FRACTION = 1f
        const val PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW = 2
        const val FALLBACK_TILE_PREFETCH_REQUESTS_PER_DRAW = 4
        const val ZOOM_AHEAD_TILE_PREFETCH_REQUESTS_PER_DRAW = 2
        const val ZOOM_AHEAD_PRESENTATION_DELTA = 1.0
        const val ZOOM_AHEAD_PRESENTATION_STEPS = 0
        const val ZOOM_AHEAD_HANDOFF_LEAD = 0.25
        const val BOUNDARY_RASTER_GUTTER_PX = 16
        const val BOUNDARY_RASTER_REQUESTS_PER_DRAW = 8
        const val BOUNDARY_RASTER_SAFETY_REQUESTS_PER_DRAW = 4
        const val BOUNDARY_RASTER_SAFETY_PADDING_CELLS = 1
        const val BOUNDARY_RASTER_CACHE_BYTES = 128L * 1024L * 1024L
        const val BOUNDARY_RASTER_RESIDENT_CACHE_BYTES = 32L * 1024L * 1024L
        const val BOUNDARY_RASTER_FADE_MS = 220L
        const val RETAINED_FRAME_FADE_MS = 220f
        const val RETAINED_FRAME_HISTORY_LIMIT = 5
        const val MAX_RETAINED_FRAME_SIZE = 8192
        const val MAX_RETAINED_PADDING_LABELS = 96
        const val MAX_TARGET_SCENE_TILES = 64
        const val RETAINED_BITMAP_FAILURE_RETRY_MS = 2_000L
        const val MIN_BOUNDARY_DRAW_SCALE = 0.000_1f
        const val EXACT_VIEWPORT_EPSILON = 0.000_001
        val REFERENCE_TOKEN_SEPARATOR = Regex("[^a-z0-9]+")
        val ROAD_SHIELD_LABEL = Regex("^\\d+[A-Z]?$")
        val DICTIONARY_LABEL_HALO = Color.argb(234, 4, 10, 14)
    }
}
