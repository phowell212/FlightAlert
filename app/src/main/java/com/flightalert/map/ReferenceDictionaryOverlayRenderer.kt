@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
    "ArrayInDataClass",
)

package com.flightalert.map

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
    private val package_store = ReferenceDictionaryPackageStore(context)
    private val boundary_raster_disk_root = File(
        context.cacheDir,
        REFERENCE_BOUNDARY_RASTER_CACHE_ROOT,
    )
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
    private val boundary_record_refs = ArrayList<DictionaryLineRecordRef>(256)
    private val boundary_occurrence_ids = HashSet<ReferenceBoundaryOccurrenceKey>(256)
    private val ui_label_workspace = LabelPlanningWorkspace(text_paint)
    private val retained_label_workspace = LabelPlanningWorkspace(
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG),
    )
    private val requested_tiles = HashSet<String>()
    private val desired_tile_keys = HashSet<String>()
    private val next_desired_tile_keys = HashSet<String>()
    private val zoom_ahead_desired_tile_keys = HashSet<String>()
    private val next_zoom_ahead_desired_tile_keys = HashSet<String>()
    private val retained_destination = ReferenceRetainedDestination()
    private val fallback_destination = ReferenceRetainedDestination()
    private val fading_destination = ReferenceRetainedDestination()
    private val candidate_destination = ReferenceRetainedDestination()
    private val boundary_raster_cells = ArrayList<ReferenceBoundaryRasterCell>(40)
    private val boundary_raster_draw_cells = ArrayList<ReferenceBoundaryRasterCell>(40)
    private val boundary_raster_cell_window = BoundaryRasterCellWindow()
    private val boundary_raster_draw_cell_window = BoundaryRasterCellWindow()
    private val boundary_raster_destination = RectF()
    private val boundary_raster_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    @Volatile
    private var package_snapshot: PackageSnapshot? = null
    @Volatile
    private var package_retry_after_ms = 0L
    private val package_probe_pending = AtomicReference<PackageProbe?>()
    private val package_generation = AtomicLong()
    private val package_lifecycle_lock = ReentrantLock()
    @Volatile
    private var closed = false
    private val content_revision = AtomicLong()
    private val request_generation = AtomicLong()
    private val tile_cache = ReferenceDictionaryTileMemoryCache<ParsedDictionaryTile>(
        normalLimit = MAX_MEMORY_TILES,
        lowZoomLimit = MAX_LOW_ZOOM_MEMORY_TILES,
    )
    private val worker_id = AtomicInteger()
    private val tile_executor = Executors.newFixedThreadPool(DICTIONARY_TILE_WORKERS) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-dictionary-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
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
    private val boundary_raster_executor = Executors.newFixedThreadPool(2) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-raster-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private val boundary_raster_workspaces = object : ThreadLocal<BoundaryRasterWorkspace>() {
        override fun initialValue(): BoundaryRasterWorkspace = BoundaryRasterWorkspace()
    }
    private val completed_boundary_rasters = ConcurrentLinkedQueue<PreparedBoundaryRaster>()
    private val boundary_raster_requests = HashSet<ReferenceBoundaryRasterKey>()
    private val boundary_raster_disk_misses =
        ConcurrentHashMap.newKeySet<ReferenceBoundaryRasterKey>()
    private val boundary_raster_disk_namespaces =
        ConcurrentHashMap<ReferenceBoundaryRasterBand, String>()
    private val boundary_raster_cache = LinkedHashMap<
        ReferenceBoundaryRasterKey,
        BoundaryRasterTile
        >(48, 0.75f, true)
    private var boundary_raster_cache_bytes = 0L
    @Volatile
    private var active_boundary_raster_band: ReferenceBoundaryRasterBand? = null
    @Volatile
    private var pending_boundary_raster_band: ReferenceBoundaryRasterBand? = null
    @Volatile
    private var relevant_boundary_raster_keys: Set<ReferenceBoundaryRasterKey> = emptySet()
    private var relevant_boundary_raster_desired_band: ReferenceBoundaryRasterBand? = null
    private var relevant_boundary_raster_active_band: ReferenceBoundaryRasterBand? = null
    private var relevant_boundary_raster_cell_revision = -1
    private var relevant_boundary_raster_draw_cell_revision = -1
    private val boundary_raster_safety_cells = ArrayList<ReferenceBoundaryRasterCell>(64)
    private val boundary_raster_safety_cell_window = BoundaryRasterCellWindow()
    private val boundary_raster_safety_draw_cells =
        ArrayList<ReferenceBoundaryRasterCell>(64)
    private val boundary_raster_safety_draw_cell_window = BoundaryRasterCellWindow()
    private var pending_boundary_raster_safety_scene: BoundaryRasterScene? = null
    private var ready_boundary_raster_safety_scene: BoundaryRasterScene? = null
    private var settled_boundary_raster_scene: BoundaryRasterScene? = null
    private var resident_boundary_raster_scene: BoundaryRasterScene? = null
    private var boundary_raster_fade: BoundaryRasterFade? = null
    @Volatile
    private var relevant_boundary_raster_safety_keys: Set<ReferenceBoundaryRasterKey> =
        emptySet()
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
    private var retained_frame: RetainedReferenceFrame? = null
    private var retained_fallback_frame: RetainedReferenceFrame? = null
    private var partial_label_frame: RetainedReferenceFrame? = null
    private var retained_fade: RetainedReferenceFade? = null
    private var displayed_retained_frame: RetainedReferenceFrame? = null
    private var retained_label_interaction_active = false
    private val retained_frame_history =
        ReferenceRetainedSceneHistory<RetainedReferenceFrame>(RETAINED_FRAME_HISTORY_LIMIT)

    init {
        request_package_probe_if_due()
    }

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
        val snapshot = package_snapshot
        if (snapshot == null) {
            request_package_probe_if_due()
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
            clear_zoom_ahead_desired_tile_keys()
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
                current_package_generation = package_generation.get(),
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
                    draw_boundary_raster_layer(
                        canvas = canvas,
                        viewport = viewport,
                        source_zoom = tile_zoom,
                        groups = groups,
                        filter_mask = filter_mask,
                        interaction_active = false,
                        tile_request_generation = request_generation.get(),
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
            reference_boundary_raster_band_is_compatible(
                active = active_boundary_raster_band,
                settingsKey = boundary_raster_settings_key(groups, filter_mask),
                packageGeneration = package_generation.get(),
            )
        if (reference_should_defer_target_work(
                interactionActive = interaction_active,
                labelsEnabled = labels_enabled,
                retainedLabelsAvailable = retained_for_interaction != null,
                bordersEnabled = borders_enabled,
                compatibleActiveBoundaryBand = compatible_active_boundary_band,
            )
        ) {
            val generation = request_generation.get()
            val boundary_raster = if (borders_enabled) {
                draw_boundary_raster_layer(
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
        val generation = update_desired_tile_keys(
            fallback_tiles = fallback_visible_tiles,
            include_target_tiles = true,
        )

        fallback_draw_tiles.clear()
        var fallback_missing = 0
        var fallback_prefetch_requests = 0
        var requested = zoom_ahead_requests
        for (tile in fallback_visible_tiles) {
            val parsed = synchronized(tile_cache) {
                tile_cache.get(tile.cache_key(), tile.low_zoom_profile())
            }
            if (parsed != null) {
                fallback_draw_tiles += DictionaryTileDrawRef(
                    tile = parsed,
                    draw_x = tile.draw_x,
                    core_visible = tile.core_visible,
                )
            } else {
                fallback_missing++
                if (fallback_prefetch_requests < FALLBACK_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    val queued = request_tile_if_needed(tile, generation)
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
            val cache_key = tile.cache_key()
            val parsed = synchronized(tile_cache) {
                tile_cache.get(cache_key, tile.low_zoom_profile())
            }
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
                    requested += request_tile_if_needed(tile, generation)
                } else if (padded_prefetch_requests < PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    val queued = request_tile_if_needed(tile, generation)
                    requested += queued
                    padded_prefetch_requests += queued
                }
            }
        }

        val ready = missing == 0
        val fallback_ready = fallback_visible_tiles.isNotEmpty() && fallback_missing == 0
        val boundary_raster = if (borders_enabled) {
            draw_boundary_raster_layer(
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
                package_generation_snapshot = package_generation.get(),
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
                    current_package_generation = package_generation.get(),
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
                        package_generation_snapshot = package_generation.get(),
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
                        package_generation_snapshot = package_generation.get(),
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
                package_generation_snapshot = package_generation.get(),
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
                current_package_generation = package_generation.get(),
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

    fun content_revision(): Long {
        return content_revision.get()
    }

    fun package_check_pending(): Boolean {
        return package_probe_pending.get() != null
    }

    fun reference_class_catalog(): ReferenceClassCatalog? {
        val snapshot = package_snapshot
        if (snapshot != null) return snapshot.catalog
        request_package_probe_if_due()
        return null
    }

    fun clear() {
        package_lifecycle_lock.lock()
        try {
            synchronized(tile_cache) {
                package_generation.incrementAndGet()
                package_snapshot = null
                package_retry_after_ms = 0L
                tile_cache.clear()
                requested_tiles.clear()
                desired_tile_keys.clear()
                zoom_ahead_desired_tile_keys.clear()
                next_zoom_ahead_desired_tile_keys.clear()
            }
            package_probe_pending.set(null)
            cancel_pending_retained_bitmap()
            retained_label_coordinator.cancel()
            retained_label_interaction_active = false
            clear_retained_frames()
            clear_boundary_rasters()
            content_revision.incrementAndGet()
            package_store.close()
        } finally {
            package_lifecycle_lock.unlock()
        }
    }

    fun reset_retained_frame() {
        cancel_pending_retained_bitmap()
        retained_label_coordinator.cancel()
        clear_zoom_ahead_desired_tile_keys()
        retained_label_interaction_active = false
        clear_retained_frames()
    }

    override fun close() {
        package_lifecycle_lock.lock()
        try {
            if (closed) return
            closed = true
            retained_bitmap_executor.shutdownNow()
            retained_label_executor.shutdownNow()
            boundary_raster_executor.shutdownNow()
            tile_executor.shutdownNow()
            clear()
        } finally {
            package_lifecycle_lock.unlock()
        }
    }

    private fun request_package_probe_if_due() {
        if (package_snapshot != null) return
        if (!package_lifecycle_lock.tryLock()) return
        try {
            if (package_snapshot != null) return
            val now_ms = SystemClock.elapsedRealtime()
            if (now_ms < package_retry_after_ms) return
            val probe = PackageProbe(package_generation.get())
            if (!package_probe_pending.compareAndSet(null, probe)) return
            enqueue_package_probe(probe)
        } finally {
            package_lifecycle_lock.unlock()
        }
    }

    private fun enqueue_package_probe(probe: PackageProbe) {
        try {
            tile_executor.execute(probe)
        } catch (_: RejectedExecutionException) {
            package_probe_pending.compareAndSet(probe, null)
        }
    }

    private fun probe_package(probe: PackageProbe) {
        var published = false
        package_lifecycle_lock.lock()
        try {
            if (!package_probe_is_current(probe)) return
            val info = package_store.package_info_if_available()
            val catalog = if (info != null) {
                package_store.reference_class_catalog_if_available()
            } else {
                null
            }
            val loaded = if (info != null && catalog != null) PackageSnapshot(info, catalog) else null
            synchronized(tile_cache) {
                if (!package_probe_is_current(probe)) return
                if (loaded == null) {
                    package_retry_after_ms = SystemClock.elapsedRealtime() +
                        ReferenceDictionaryPackageStore.PACKAGE_MISSING_RETRY_MS
                } else {
                    package_snapshot = loaded
                    package_retry_after_ms = 0L
                    published = true
                }
            }
        } catch (_: Exception) {
            synchronized(tile_cache) {
                if (package_probe_is_current(probe)) {
                    package_retry_after_ms = SystemClock.elapsedRealtime() +
                        ReferenceDictionaryPackageStore.PACKAGE_MISSING_RETRY_MS
                }
            }
        } finally {
            package_probe_pending.compareAndSet(probe, null)
            try {
                if (published) request_redraw()
            } finally {
                package_lifecycle_lock.unlock()
            }
        }
    }

    private fun package_probe_is_current(probe: PackageProbe): Boolean {
        return package_probe_pending.get() === probe &&
            package_generation.get() == probe.generation
    }

    private fun request_tile_if_needed(
        tile: VisibleDictionaryTile,
        generation: Long,
        additionally_relevant: (() -> Boolean)? = null,
    ): Int {
        val cache_key = tile.cache_key()
        synchronized(tile_cache) {
            if (tile_cache.contains(cache_key, tile.low_zoom_profile())) return 0
            if (!requested_tiles.add(cache_key)) return 0
        }
        tile_executor.execute {
            var request_became_obsolete = false
            val keep_loading = {
                if (request_became_obsolete) {
                    false
                } else {
                    val relevant = tile_request_still_relevant(cache_key, generation) ||
                        additionally_relevant?.invoke() == true
                    if (!relevant) request_became_obsolete = true
                    relevant
                }
            }
            var published = false
            try {
                if (!keep_loading()) return@execute
                val payload = package_store.read_tile_payload(
                    z = tile.z,
                    x = tile.x,
                    y = tile.y,
                    request_is_relevant = keep_loading,
                )
                if (!keep_loading()) return@execute
                val parsed = if (payload != null) {
                    parse_tile_payload(
                        payload = payload,
                        outline_visibility_centizoom = tile.outline_visibility_centizoom,
                        request_is_relevant = keep_loading,
                    ) ?: return@execute
                } else {
                    empty_parsed_tile(tile)
                }
                synchronized(tile_cache) {
                    if (!keep_loading()) return@synchronized
                    tile_cache.put(cache_key, tile.low_zoom_profile(), parsed)
                    content_revision.incrementAndGet()
                    published = true
                }
            } catch (_: Exception) {
                synchronized(tile_cache) {
                    if (keep_loading()) {
                        tile_cache.put(
                            cache_key,
                            tile.low_zoom_profile(),
                            empty_parsed_tile(tile),
                        )
                        content_revision.incrementAndGet()
                        published = true
                    }
                }
            } finally {
                var replacement_generation: Long? = null
                synchronized(tile_cache) {
                    requested_tiles.remove(cache_key)
                    if (
                        request_became_obsolete &&
                        !tile_cache.contains(cache_key, tile.low_zoom_profile()) &&
                        (
                            desired_tile_keys.contains(cache_key) ||
                                zoom_ahead_desired_tile_keys.contains(cache_key)
                            )
                    ) {
                        replacement_generation = request_generation.get()
                    }
                }
                replacement_generation?.let { current_generation ->
                    try {
                        request_tile_if_needed(tile, current_generation)
                    } catch (_: RejectedExecutionException) {
                        synchronized(tile_cache) {
                            requested_tiles.remove(cache_key)
                        }
                    }
                }
                if (published || keep_loading()) {
                    request_redraw()
                }
            }
        }
        return 1
    }

    private fun update_desired_tile_keys(
        fallback_tiles: List<VisibleDictionaryTile> = emptyList(),
        include_target_tiles: Boolean = true,
    ): Long {
        return synchronized(tile_cache) {
            next_desired_tile_keys.clear()
            if (include_target_tiles) {
                visible_tiles.forEach { tile ->
                    next_desired_tile_keys += tile.cache_key()
                }
            }
            fallback_tiles.forEach { tile -> next_desired_tile_keys += tile.cache_key() }
            val changed = desired_tile_keys != next_desired_tile_keys
            if (changed) {
                desired_tile_keys.clear()
                desired_tile_keys += next_desired_tile_keys
                request_generation.incrementAndGet()
            } else {
                request_generation.get()
            }
        }
    }

    private fun tile_request_still_relevant(cache_key: String, generation: Long): Boolean {
        if (generation == request_generation.get()) return true
        return synchronized(tile_cache) {
            desired_tile_keys.contains(cache_key) ||
                zoom_ahead_desired_tile_keys.contains(cache_key)
        }
    }

    private fun update_zoom_ahead_desired_tile_keys(
        tiles: List<VisibleDictionaryTile>,
    ): Long {
        return synchronized(tile_cache) {
            next_zoom_ahead_desired_tile_keys.clear()
            for (tile in tiles) {
                next_zoom_ahead_desired_tile_keys += tile.cache_key()
            }
            if (zoom_ahead_desired_tile_keys != next_zoom_ahead_desired_tile_keys) {
                zoom_ahead_desired_tile_keys.clear()
                zoom_ahead_desired_tile_keys += next_zoom_ahead_desired_tile_keys
                request_generation.incrementAndGet()
            } else {
                request_generation.get()
            }
        }
    }

    private fun clear_zoom_ahead_desired_tile_keys() {
        synchronized(tile_cache) {
            if (zoom_ahead_desired_tile_keys.isEmpty()) return
            zoom_ahead_desired_tile_keys.clear()
            request_generation.incrementAndGet()
        }
    }

    private fun empty_parsed_tile(tile: VisibleDictionaryTile): ParsedDictionaryTile {
        return ParsedDictionaryTile(
            coordinate = ReferenceDictionaryTileCoordinate(z = tile.z, x = tile.x, y = tile.y),
            boundaries = emptyList(),
            labels = emptyList()
        )
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
            if (package_snapshot?.info?.runtime_schema ==
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

    private fun tile_request_priority(
        tx: Int,
        ty: Int,
        center_x: Int,
        center_y: Int
    ): Int {
        val dx = tx - center_x
        val dy = ty - center_y
        return dx * dx + dy * dy
    }

    private fun draw_boundary_raster_layer(
        canvas: Canvas,
        viewport: Viewport,
        source_zoom: Int,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        interaction_active: Boolean,
        tile_request_generation: Long,
        now_ms: Long,
        freeze_active_band: Boolean = false,
    ): BoundaryRasterDrawResult {
        val settings_key = boundary_raster_settings_key(groups, filter_mask)
        val desired_band = desired_boundary_raster_band(
            viewport = viewport,
            source_zoom = source_zoom,
            settings_key = settings_key,
            interaction_active = interaction_active,
            freeze_active_band = freeze_active_band,
        )
        if (publish_completed_boundary_rasters()) {
            promote_boundary_raster_safety_if_complete()
        }
        val active_before_promotion = active_boundary_raster_band
        val raster_work_suspended = reference_boundary_raster_work_is_suspended(
            interactionActive = interaction_active,
            hasActiveBand = active_before_promotion != null,
        )
        if (interaction_active && pending_boundary_raster_safety_scene != null &&
            active_before_promotion != null &&
            viewport.zoom > active_before_promotion.presentationCentizoom / 100.0
        ) {
            pending_boundary_raster_safety_scene = null
            update_boundary_raster_safety_relevance()
        }
        val continuity_scene = active_before_promotion?.let { active ->
            boundary_raster_continuity_scene(
                active = active,
                viewport = viewport,
                settingsKey = settings_key,
                packageGeneration = package_generation.get(),
            )
        }
        val continuity_blend = if (continuity_scene != null) {
            reference_boundary_raster_safety_blend(
                active = requireNotNull(active_before_promotion),
                safety = continuity_scene.band,
                viewportZoom = viewport.zoom,
            )
        } else {
            0f
        }
        if (raster_work_suspended && continuity_blend >= 1f) {
            trim_boundary_raster_cache()
            return draw_boundary_raster_safety_scene(
                canvas,
                viewport,
                requireNotNull(continuity_scene),
            )
        }
        val hold_continuity_while_loading = !interaction_active && continuity_blend >= 1f &&
            desired_band != active_before_promotion
        val initial_draw_band = if (hold_continuity_while_loading) {
            requireNotNull(continuity_scene).band
        } else {
            active_before_promotion
        }
        val desired_padding_cells = when {
            desired_band != active_boundary_raster_band -> 0
            interaction_active -> 1
            else -> reference_boundary_raster_padding_cells(
                retainedPaddingPx = retained_scene_padding_for_viewport(viewport, source_zoom),
                rasterCellScreenPx =
                    MAP_TILE_SIZE * 2.0.pow(viewport.zoom - desired_band.rasterZoom),
                interactionActive = false,
            )
        }
        build_boundary_raster_cells(
            output = boundary_raster_cells,
            window = boundary_raster_cell_window,
            viewport = viewport,
            raster_zoom = desired_band.rasterZoom,
            padding_cells = desired_padding_cells,
        )
        val desired_keys = boundary_raster_cell_window.keys_for(
            desired_band,
            boundary_raster_cells,
        )
        var draw_keys = if (initial_draw_band != null) {
            build_boundary_raster_cells(
                output = boundary_raster_draw_cells,
                window = boundary_raster_draw_cell_window,
                viewport = viewport,
                raster_zoom = initial_draw_band.rasterZoom,
                padding_cells = 1,
            )
            boundary_raster_draw_cell_window.keys_for(
                initial_draw_band,
                boundary_raster_draw_cells,
            )
        } else {
            boundary_raster_draw_cells.clear()
            boundary_raster_draw_cell_window.invalidate()
            emptyList()
        }
        val relevance_changed =
            desired_band != relevant_boundary_raster_desired_band ||
                initial_draw_band != relevant_boundary_raster_active_band ||
                boundary_raster_cell_window.revision !=
                relevant_boundary_raster_cell_revision ||
                boundary_raster_draw_cell_window.revision !=
                relevant_boundary_raster_draw_cell_revision
        if (!raster_work_suspended && relevance_changed) {
            val relevant_keys =
                HashSet<ReferenceBoundaryRasterKey>(
                    boundary_raster_cells.size + boundary_raster_draw_cells.size,
                )
            for (index in boundary_raster_cells.indices) {
                val cell = boundary_raster_cells[index]
                if (cell.coreVisible || desired_band == active_before_promotion) {
                    relevant_keys += desired_keys[index]
                }
            }
            for (key in draw_keys) {
                relevant_keys += key
            }
            relevant_boundary_raster_keys = relevant_keys
            relevant_boundary_raster_desired_band = desired_band
            relevant_boundary_raster_active_band = initial_draw_band
            relevant_boundary_raster_cell_revision = boundary_raster_cell_window.revision
            relevant_boundary_raster_draw_cell_revision =
                boundary_raster_draw_cell_window.revision
        }
        trim_boundary_raster_cache()

        var requested = 0
        var desired_core_ready = true
        for (index in boundary_raster_cells.indices) {
            val cell = boundary_raster_cells[index]
            val key = desired_keys[index]
            if (boundary_raster_cache.containsKey(key)) continue
            if (cell.coreVisible) desired_core_ready = false
            if (raster_work_suspended) continue
            if (requested >= BOUNDARY_RASTER_REQUESTS_PER_DRAW) continue
            if (!cell.coreVisible && desired_band != active_boundary_raster_band) continue
            requested += request_boundary_raster(
                key = key,
                groups = groups,
                filter_mask = filter_mask,
                tile_request_generation = tile_request_generation,
            )
        }

        if (desired_band != active_boundary_raster_band && desired_core_ready) {
            promote_boundary_raster_band(
                next = desired_band,
                displayed_scene = continuity_scene.takeIf { continuity_blend > 0f },
                now_ms = now_ms,
            )
        }
        var draw_band = initial_draw_band
            ?: active_boundary_raster_band
            ?: return BoundaryRasterDrawResult(0, false, requested)
        if (active_boundary_raster_band != active_before_promotion) {
            draw_band = requireNotNull(active_boundary_raster_band)
            build_boundary_raster_cells(
                output = boundary_raster_draw_cells,
                window = boundary_raster_draw_cell_window,
                viewport = viewport,
                raster_zoom = draw_band.rasterZoom,
                padding_cells = 1,
            )
            draw_keys = boundary_raster_draw_cell_window.keys_for(
                draw_band,
                boundary_raster_draw_cells,
            )
            val promoted_keys = HashSet(relevant_boundary_raster_keys)
            for (key in draw_keys) {
                promoted_keys += key
            }
            relevant_boundary_raster_keys = promoted_keys
            invalidate_boundary_raster_relevance()
            trim_boundary_raster_cache()
        }

        if (!interaction_active && desired_core_ready &&
            active_boundary_raster_band == desired_band
        ) {
            capture_settled_boundary_raster_scene(
                band = desired_band,
                cells = boundary_raster_draw_cells,
                keys = draw_keys,
                window = boundary_raster_draw_cell_window,
            )
        }

        var underlay_scene = continuity_scene
        var underlay_blend = if (continuity_blend > 0f &&
            draw_band == active_before_promotion
        ) {
            continuity_blend
        } else {
            0f
        }
        val raster_fade = boundary_raster_fade
        if (raster_fade != null && raster_fade.currentBand == draw_band &&
            raster_fade.previous.matches_environment(
                settingsKey = settings_key,
                packageGeneration = package_generation.get(),
            ) &&
            raster_fade.previous.covers(viewport)
        ) {
            val fade_underlay = reference_boundary_raster_fade_underlay(
                elapsedMillis = now_ms - raster_fade.startMs,
                durationMillis = BOUNDARY_RASTER_FADE_MS,
            )
            if (fade_underlay > 0f) {
                underlay_scene = raster_fade.previous
                underlay_blend = fade_underlay
            } else {
                boundary_raster_fade = null
                update_boundary_raster_safety_relevance()
            }
        }
        val continuity_underlay = if (underlay_scene != null && underlay_blend > 0f) {
            draw_boundary_raster_safety_scene(
                canvas = canvas,
                viewport = viewport,
                scene = underlay_scene,
                alpha = (255f * underlay_blend).roundToInt().coerceIn(0, 255),
            )
        } else {
            null
        }
        var boundaries_drawn = continuity_underlay?.boundaries ?: 0
        var all_core_drawn = continuity_underlay?.ready ?: true
        boundary_raster_paint.alpha = if (continuity_underlay != null) {
            (255f * (1f - underlay_blend)).roundToInt().coerceIn(0, 255)
        } else {
            255
        }
        for (index in boundary_raster_draw_cells.indices) {
            val cell = boundary_raster_draw_cells[index]
            val key = draw_keys[index]
            val tile = boundary_raster_cache[key]
            var fallback_available = continuity_underlay?.ready == true
            if (cell.coreVisible) {
                if (tile != null) {
                    draw_boundary_raster(canvas, viewport, cell, tile)
                    boundaries_drawn += tile.boundaries
                } else if (!fallback_available) {
                    val fallback_boundaries = draw_boundary_raster_fallback(
                        canvas,
                        viewport,
                        cell,
                        draw_band,
                    )
                    if (fallback_boundaries >= 0) {
                        boundaries_drawn += fallback_boundaries
                        fallback_available = true
                    } else {
                        all_core_drawn = false
                    }
                }
            }
            if (!raster_work_suspended && tile == null &&
                requested < BOUNDARY_RASTER_REQUESTS_PER_DRAW &&
                reference_boundary_raster_should_request_draw_cell(
                    drawBand = draw_band,
                    desiredBand = desired_band,
                    coreVisible = cell.coreVisible,
                    fallbackAvailable = fallback_available,
                )
            ) {
                requested += request_boundary_raster(
                    key = key,
                    groups = groups,
                    filter_mask = filter_mask,
                    tile_request_generation = tile_request_generation,
                )
            }
        }
        boundary_raster_paint.alpha = 255
        if (!interaction_active && desired_core_ready) {
            requested += request_boundary_raster_safety_scene(
                viewport = viewport,
                settingsKey = settings_key,
                groups = groups,
                filterMask = filter_mask,
                tileRequestGeneration = tile_request_generation,
                requestBudget = (
                    BOUNDARY_RASTER_REQUESTS_PER_DRAW - requested
                    ).coerceIn(0, BOUNDARY_RASTER_SAFETY_REQUESTS_PER_DRAW),
            )
        }
        return BoundaryRasterDrawResult(boundaries_drawn, all_core_drawn, requested)
    }

    private fun desired_boundary_raster_band(
        viewport: Viewport,
        source_zoom: Int,
        settings_key: Long,
        interaction_active: Boolean,
        freeze_active_band: Boolean,
    ): ReferenceBoundaryRasterBand {
        val active = active_boundary_raster_band
        val current_package_generation = package_generation.get()
        if (freeze_active_band && reference_boundary_raster_band_is_compatible(
                active = active,
                settingsKey = settings_key,
                packageGeneration = current_package_generation,
            )
        ) {
            return requireNotNull(active)
        }
        val exact = reference_boundary_raster_band(
            viewportZoom = viewport.zoom,
            sourceZoom = source_zoom,
            settingsKey = settings_key,
            packageGeneration = current_package_generation,
        )
        if (active == null) {
            val target = reference_boundary_raster_cold_target(
                exact = exact,
                pending = pending_boundary_raster_band,
                interactionActive = interaction_active,
            )
            pending_boundary_raster_band = target
            return target
        }
        if (!interaction_active) {
            pending_boundary_raster_band = exact.takeUnless { it == active }
            return exact
        }

        val pending = pending_boundary_raster_band
        if (pending != null && pending.same_grid_and_content(exact)) return pending
        if (!reference_boundary_raster_band_transition(
                active = active,
                viewportZoom = viewport.zoom,
                sourceZoom = source_zoom,
                settingsKey = settings_key,
                packageGeneration = current_package_generation,
            )
        ) {
            pending_boundary_raster_band = null
            return active
        }
        pending_boundary_raster_band = exact
        return exact
    }

    private fun ReferenceBoundaryRasterBand.same_grid_and_content(
        other: ReferenceBoundaryRasterBand,
    ): Boolean {
        return rasterZoom == other.rasterZoom &&
            sourceZoom == other.sourceZoom &&
            settingsKey == other.settingsKey &&
            packageGeneration == other.packageGeneration
    }

    private fun ReferenceBoundaryRasterBand.matches_safety_target(
        safetyZoom: Int,
        sourceZoom: Int,
        settingsKey: Long,
        packageGeneration: Long,
    ): Boolean {
        return presentationCentizoom == safetyZoom * 100 &&
            this.sourceZoom == sourceZoom &&
            this.settingsKey == settingsKey &&
            this.packageGeneration == packageGeneration
    }

    private fun boundary_raster_settings_key(
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
    ): Long {
        var group_bits = 0L
        if (groups.places) group_bits = group_bits or 1L
        if (groups.water) group_bits = group_bits or 2L
        if (groups.regions) group_bits = group_bits or 4L
        if (groups.public_lands) group_bits = group_bits or 8L
        return (filter_mask.cacheKey.toLong() and 0xffff_ffffL) or (group_bits shl 32)
    }

    private fun build_boundary_raster_cells(
        output: MutableList<ReferenceBoundaryRasterCell>,
        window: BoundaryRasterCellWindow,
        viewport: Viewport,
        raster_zoom: Int,
        padding_cells: Int,
    ) {
        build_boundary_raster_cells(
            output = output,
            window = window,
            viewport_zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            viewport_width = viewport.width,
            viewport_height = viewport.height,
            raster_zoom = raster_zoom,
            padding_cells = padding_cells,
        )
    }

    private fun build_boundary_raster_cells(
        output: MutableList<ReferenceBoundaryRasterCell>,
        window: BoundaryRasterCellWindow,
        viewport_zoom: Double,
        center_x: Double,
        center_y: Double,
        viewport_width: Float,
        viewport_height: Float,
        raster_zoom: Int,
        padding_cells: Int,
    ) {
        val tile_scale = MAP_TILE_SIZE * 2.0.pow(viewport_zoom - raster_zoom)
        val left_world = center_x - viewport_width / 2.0
        val top_world = center_y - viewport_height / 2.0
        val core_first_x = floor(left_world / tile_scale).toInt()
        val core_first_y = floor(top_world / tile_scale).toInt()
        val core_last_x = floor((left_world + viewport_width) / tile_scale).toInt()
        val core_last_y = floor((top_world + viewport_height) / tile_scale).toInt()
        val center_tile_x = floor(center_x / tile_scale).toInt()
        val center_tile_y = floor(center_y / tile_scale).toInt()
        if (window.matches(
                raster_zoom,
                padding_cells,
                core_first_x,
                core_first_y,
                core_last_x,
                core_last_y,
                center_tile_x,
                center_tile_y,
            )
        ) return
        window.update(
            raster_zoom,
            padding_cells,
            core_first_x,
            core_first_y,
            core_last_x,
            core_last_y,
            center_tile_x,
            center_tile_y,
        )
        output.clear()
        val tile_limit = 1 shl raster_zoom
        for (raw_y in core_first_y - padding_cells..core_last_y + padding_cells) {
            if (raw_y !in 0 until tile_limit) continue
            for (raw_x in core_first_x - padding_cells..core_last_x + padding_cells) {
                val normalized_x = Math.floorMod(raw_x, tile_limit)
                output += ReferenceBoundaryRasterCell(
                    zoom = raster_zoom,
                    x = normalized_x,
                    drawX = raw_x,
                    y = raw_y,
                    coreVisible = raw_x in core_first_x..core_last_x &&
                        raw_y in core_first_y..core_last_y,
                    requestPriority = tile_request_priority(
                        raw_x,
                        raw_y,
                        center_tile_x,
                        center_tile_y,
                    ),
                )
            }
        }
        output.sortWith(
            compareBy<ReferenceBoundaryRasterCell> { !it.coreVisible }
                .thenBy { it.requestPriority },
        )
    }

    private fun request_boundary_raster_safety_scene(
        viewport: Viewport,
        settingsKey: Long,
        groups: ReferenceDictionaryLayerGroups,
        filterMask: ReferenceFilterMask,
        tileRequestGeneration: Long,
        requestBudget: Int,
    ): Int {
        val snapshot = package_snapshot ?: return 0
        val safety_zoom = reference_boundary_raster_safety_zoom(viewport.zoom, MIN_ZOOM)
        val safety_source_zoom = dictionary_tile_zoom(
            safety_zoom.toDouble(),
            snapshot.info.zooms,
        ) ?: return 0
        val current_package_generation = package_generation.get()
        val ready_band = ready_boundary_raster_safety_scene?.band
        val pending_band = pending_boundary_raster_safety_scene?.band
        val band = when {
            ready_band?.matches_safety_target(
                safetyZoom = safety_zoom,
                sourceZoom = safety_source_zoom,
                settingsKey = settingsKey,
                packageGeneration = current_package_generation,
            ) == true -> ready_band
            pending_band?.matches_safety_target(
                safetyZoom = safety_zoom,
                sourceZoom = safety_source_zoom,
                settingsKey = settingsKey,
                packageGeneration = current_package_generation,
            ) == true -> pending_band
            else -> reference_boundary_raster_band(
                viewportZoom = safety_zoom.toDouble(),
                sourceZoom = safety_source_zoom,
                settingsKey = settingsKey,
                packageGeneration = current_package_generation,
            )
        }
        val safety_center_x = reference_boundary_raster_safety_center(
            center = viewport.center_x,
            viewportZoom = viewport.zoom,
            safetyZoom = safety_zoom,
        )
        val safety_center_y = reference_boundary_raster_safety_center(
            center = viewport.center_y,
            viewportZoom = viewport.zoom,
            safetyZoom = safety_zoom,
        )
        build_boundary_raster_cells(
            output = boundary_raster_safety_cells,
            window = boundary_raster_safety_cell_window,
            viewport_zoom = safety_zoom.toDouble(),
            center_x = safety_center_x,
            center_y = safety_center_y,
            viewport_width = viewport.width,
            viewport_height = viewport.height,
            raster_zoom = band.rasterZoom,
            padding_cells = BOUNDARY_RASTER_SAFETY_PADDING_CELLS,
        )
        val keys = boundary_raster_safety_cell_window.keys_for(
            band,
            boundary_raster_safety_cells,
        )
        val bounds = boundary_raster_safety_cell_window.bounds()
        val ready = ready_boundary_raster_safety_scene
        var pending_changed = false
        if (ready == null || ready.band != band || ready.bounds != bounds) {
            val pending = pending_boundary_raster_safety_scene
            if (pending == null || pending.band != band || pending.bounds != bounds) {
                pending_boundary_raster_safety_scene = BoundaryRasterScene(
                    band = band,
                    bounds = bounds,
                    keys = LinkedHashSet(keys),
                )
                update_boundary_raster_safety_relevance()
                pending_changed = true
            }
        } else if (pending_boundary_raster_safety_scene != null) {
            pending_boundary_raster_safety_scene = null
            update_boundary_raster_safety_relevance()
        }
        if (pending_changed) {
            promote_boundary_raster_safety_if_complete()
        }
        val pending = pending_boundary_raster_safety_scene ?: return 0
        if (requestBudget <= 0) return 0

        var requested = 0
        for (key in pending.keys) {
            if (boundary_raster_cache.containsKey(key) ||
                boundary_raster_requests.contains(key)
            ) {
                continue
            }
            requested += request_boundary_raster(
                key = key,
                groups = groups,
                filter_mask = filterMask,
                tile_request_generation = tileRequestGeneration,
            )
            if (requested >= requestBudget) break
        }
        return requested
    }

    private fun promote_boundary_raster_safety_if_complete() {
        val pending = pending_boundary_raster_safety_scene ?: return
        for (key in pending.keys) {
            if (!boundary_raster_cache.containsKey(key)) return
        }
        ready_boundary_raster_safety_scene = pending
        pending_boundary_raster_safety_scene = null
        update_boundary_raster_safety_relevance()
    }

    private fun boundary_raster_continuity_scene(
        active: ReferenceBoundaryRasterBand,
        viewport: Viewport,
        settingsKey: Long,
        packageGeneration: Long,
    ): BoundaryRasterScene? {
        val safety = ready_boundary_raster_safety_scene?.takeIf { scene ->
            scene.matches_environment(settingsKey, packageGeneration) &&
                scene.covers(viewport)
        }
        val resident = resident_boundary_raster_scene?.takeIf { scene ->
            scene.matches_environment(settingsKey, packageGeneration) &&
                scene.covers(viewport)
        }
        val selected_band = reference_boundary_raster_continuity_band(
            active = active,
            safety = safety?.band,
            resident = resident?.band,
        )
        return when (selected_band) {
            safety?.band -> safety
            resident?.band -> resident
            else -> null
        }
    }

    private fun capture_settled_boundary_raster_scene(
        band: ReferenceBoundaryRasterBand,
        cells: List<ReferenceBoundaryRasterCell>,
        keys: List<ReferenceBoundaryRasterKey>,
        window: BoundaryRasterCellWindow,
    ) {
        val bounds = window.core_bounds()
        val current = settled_boundary_raster_scene
        if (current?.band == band && current.bounds == bounds) return

        val core_keys = LinkedHashSet<ReferenceBoundaryRasterKey>()
        var bytes = 0L
        for (index in cells.indices) {
            if (!cells[index].coreVisible) continue
            val key = keys[index]
            val tile = boundary_raster_cache[key] ?: return
            bytes += tile.byteCount
            if (bytes > BOUNDARY_RASTER_RESIDENT_CACHE_BYTES) return
            core_keys += key
        }
        if (core_keys.isEmpty()) return
        settled_boundary_raster_scene = BoundaryRasterScene(
            band = band,
            bounds = bounds,
            keys = core_keys,
        )
    }

    private fun update_boundary_raster_safety_relevance() {
        val ready = ready_boundary_raster_safety_scene
        val pending = pending_boundary_raster_safety_scene
        val resident = resident_boundary_raster_scene
        val fading = boundary_raster_fade?.previous
        if (ready == null && pending == null && resident == null && fading == null) {
            relevant_boundary_raster_safety_keys = emptySet()
            return
        }
        val keys = HashSet<ReferenceBoundaryRasterKey>(
            (ready?.keys?.size ?: 0) +
                (pending?.keys?.size ?: 0) +
                (resident?.keys?.size ?: 0) +
                (fading?.keys?.size ?: 0),
        )
        ready?.keys?.let(keys::addAll)
        pending?.keys?.let(keys::addAll)
        resident?.keys?.let(keys::addAll)
        fading?.keys?.let(keys::addAll)
        relevant_boundary_raster_safety_keys = keys
    }

    private fun draw_boundary_raster_safety_scene(
        canvas: Canvas,
        viewport: Viewport,
        scene: BoundaryRasterScene,
        alpha: Int = 255,
    ): BoundaryRasterDrawResult {
        build_boundary_raster_cells(
            output = boundary_raster_safety_draw_cells,
            window = boundary_raster_safety_draw_cell_window,
            viewport = viewport,
            raster_zoom = scene.band.rasterZoom,
            padding_cells = 0,
        )
        val keys = boundary_raster_safety_draw_cell_window.keys_for(
            scene.band,
            boundary_raster_safety_draw_cells,
        )
        boundary_raster_paint.alpha = alpha
        var boundaries = 0
        for (index in keys.indices) {
            val key = keys[index]
            if (key !in scene.keys) {
                boundary_raster_paint.alpha = 255
                return BoundaryRasterDrawResult(boundaries, false, 0)
            }
            val tile = boundary_raster_cache[key]
            if (tile == null) {
                boundary_raster_paint.alpha = 255
                return BoundaryRasterDrawResult(boundaries, false, 0)
            }
            draw_boundary_raster(
                canvas = canvas,
                viewport = viewport,
                cell = boundary_raster_safety_draw_cells[index],
                tile = tile,
            )
            boundaries += tile.boundaries
        }
        boundary_raster_paint.alpha = 255
        return BoundaryRasterDrawResult(boundaries, true, 0)
    }

    private fun invalidate_boundary_raster_relevance() {
        relevant_boundary_raster_desired_band = null
        relevant_boundary_raster_active_band = null
        relevant_boundary_raster_cell_revision = -1
        relevant_boundary_raster_draw_cell_revision = -1
    }

    private fun request_boundary_raster(
        key: ReferenceBoundaryRasterKey,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        tile_request_generation: Long,
    ): Int {
        if (boundary_raster_executor.isShutdown ||
            boundary_raster_cache.containsKey(key) ||
            boundary_raster_requests.contains(key)
        ) {
            return 0
        }
        val disk_file = boundary_raster_disk_file(key)
        if (disk_file != null && key !in boundary_raster_disk_misses) {
            boundary_raster_requests.add(key)
            boundary_raster_executor.execute {
                val should_read = boundary_raster_request_is_relevant(key)
                val tile = if (should_read) read_boundary_raster_from_disk(key, disk_file) else null
                val prepared = PreparedBoundaryRaster(key, tile)
                publish_boundary_raster_completion(
                    prepared = prepared,
                    disk_file_missed = should_read && tile == null,
                )
            }
            return 1
        }
        val source = reference_boundary_raster_source(
            rasterZoom = key.band.rasterZoom,
            rasterX = key.x,
            rasterY = key.y,
            sourceZoom = key.band.sourceZoom,
        )
        val cell_min_x = source.minimumTileX * DICTIONARY_EXTENT
        val cell_min_y = source.minimumTileY * DICTIONARY_EXTENT
        val cell_span = source.tileSpan * DICTIONARY_EXTENT
        val local_to_pixel = key.band.corePixels / cell_span
        val gutter_local = BOUNDARY_RASTER_GUTTER_PX / local_to_pixel
        val minimum_dx = floor((cell_min_x - gutter_local) / DICTIONARY_EXTENT).toInt()
        val maximum_dx = floor(
            (cell_min_x + cell_span + gutter_local) / DICTIONARY_EXTENT,
        ).toInt()
        val minimum_dy = floor((cell_min_y - gutter_local) / DICTIONARY_EXTENT).toInt()
        val maximum_dy = floor(
            (cell_min_y + cell_span + gutter_local) / DICTIONARY_EXTENT,
        ).toInt()
        val core_minimum_dx = floor(cell_min_x / DICTIONARY_EXTENT).toInt()
        val core_maximum_dx = ceil(
            (cell_min_x + cell_span) / DICTIONARY_EXTENT,
        ).toInt() - 1
        val core_minimum_dy = floor(cell_min_y / DICTIONARY_EXTENT).toInt()
        val core_maximum_dy = ceil(
            (cell_min_y + cell_span) / DICTIONARY_EXTENT,
        ).toInt() - 1
        val source_limit = 1 shl key.band.sourceZoom
        val source_tiles = ArrayList<BoundaryRasterSourceTile>(
            (maximum_dx - minimum_dx + 1) * (maximum_dy - minimum_dy + 1),
        )
        var missing_source = false
        var source_requests = 0
        for (core_pass in 0..1) {
            for (dy in minimum_dy..maximum_dy) {
                val raw_y = source.originY + dy
                if (raw_y !in 0 until source_limit) continue
                for (dx in minimum_dx..maximum_dx) {
                    val core = dx in core_minimum_dx..core_maximum_dx &&
                        dy in core_minimum_dy..core_maximum_dy
                    if (core != (core_pass == 0)) continue
                    val raw_x = source.originX + dx
                    val normalized_x = Math.floorMod(raw_x, source_limit)
                    val source_tile = VisibleDictionaryTile(
                        z = key.band.sourceZoom,
                        x = normalized_x,
                        draw_x = raw_x,
                        y = raw_y,
                        core_visible = true,
                        request_priority = 0,
                        outline_visibility_centizoom =
                            ReferenceDictionaryLodPolicy.outline_decode_profile(
                                key.band.presentationCentizoom,
                                key.band.sourceZoom,
                            ),
                    )
                    val parsed = synchronized(tile_cache) {
                        tile_cache.get(
                            source_tile.cache_key(),
                            source_tile.low_zoom_profile(),
                        )
                    }
                    if (parsed != null) {
                        source_tiles += BoundaryRasterSourceTile(parsed, raw_x, raw_y)
                        continue
                    }
                    missing_source = true
                    source_requests += request_tile_if_needed(
                        source_tile,
                        tile_request_generation,
                        additionally_relevant = {
                            boundary_raster_request_is_relevant(key)
                        },
                    )
                }
            }
        }
        if (missing_source) return source_requests
        if (!boundary_raster_requests.add(key)) return 0
        val request = BoundaryRasterRequest(key, source, source_tiles, groups, filter_mask)
        boundary_raster_executor.execute {
            val prepared = PreparedBoundaryRaster(
                key,
                if (boundary_raster_request_is_relevant(key)) {
                    render_boundary_raster(request)
                } else {
                    null
                },
            )
            val persistence = prepared.tile?.let { tile ->
                boundary_raster_disk_file(key)?.let { file ->
                    encode_boundary_raster_png(tile)?.let { bytes -> file to bytes }
                }
            }
            publish_boundary_raster_completion(prepared, persistence = persistence)
        }
        return 1
    }

    private fun boundary_raster_disk_file(key: ReferenceBoundaryRasterKey): File? {
        val band = key.band
        if (!reference_boundary_raster_disk_cacheable(band)) return null
        val snapshot = package_snapshot ?: return null
        val semantic_sha256 = snapshot.catalog.renderer_semantic_stream_sha256 ?: return null
        val namespace = boundary_raster_disk_namespaces[band]
            ?: reference_boundary_raster_disk_namespace(
                band = band,
                packageId = snapshot.info.package_id,
                schemaVersion = snapshot.info.schema_version,
                rendererSemanticStreamSha256 = semantic_sha256,
                densityRawBits = boundary_density.toRawBits(),
            ).also { generated -> boundary_raster_disk_namespaces[band] = generated }
        return File(
            File(File(boundary_raster_disk_root, namespace), key.x.toString()),
            "${key.y}.png",
        )
    }

    private fun read_boundary_raster_from_disk(
        key: ReferenceBoundaryRasterKey,
        file: File,
    ): BoundaryRasterTile? {
        return map_tile_disk_cache.read_if_fresh(file, Long.MAX_VALUE) { cached ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val bitmap = BitmapFactory.decodeFile(cached.path, options)
                ?: return@read_if_fresh null
            val expected_size = key.band.corePixels + BOUNDARY_RASTER_GUTTER_PX * 2
            if (bitmap.width != expected_size || bitmap.height != expected_size) {
                bitmap.recycle()
                return@read_if_fresh null
            }
            bitmap.prepareToDraw()
            BoundaryRasterTile(
                key = key,
                bitmap = bitmap,
                gutterPixels = BOUNDARY_RASTER_GUTTER_PX,
                boundaries = 0,
                byteCount = bitmap.allocationByteCount.toLong(),
            )
        }
    }

    private fun encode_boundary_raster_png(tile: BoundaryRasterTile): ByteArray? {
        val output = ByteArrayOutputStream()
        if (!tile.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
        return output.toByteArray()
    }

    private fun publish_boundary_raster_completion(
        prepared: PreparedBoundaryRaster,
        disk_file_missed: Boolean = false,
        persistence: Pair<File, ByteArray>? = null,
    ) {
        package_lifecycle_lock.lock()
        try {
            if (!reference_boundary_raster_completion_is_current(
                    requestPackageGeneration = prepared.key.band.packageGeneration,
                    currentPackageGeneration = package_generation.get(),
                    rendererClosed = closed,
                )
            ) {
                prepared.tile?.bitmap?.recycle()
                return
            }
            if (disk_file_missed) boundary_raster_disk_misses.add(prepared.key)
            completed_boundary_rasters.add(prepared)
            persistence?.let { (file, bytes) ->
                map_tile_disk_cache.write_async(file, bytes)
                boundary_raster_disk_misses.remove(prepared.key)
            }
            request_redraw()
        } finally {
            package_lifecycle_lock.unlock()
        }
    }

    private fun boundary_raster_request_is_relevant(key: ReferenceBoundaryRasterKey): Boolean {
        return key in relevant_boundary_raster_keys ||
            key in relevant_boundary_raster_safety_keys
    }

    private fun render_boundary_raster(request: BoundaryRasterRequest): BoundaryRasterTile? {
        val key = request.key
        val band = key.band
        val source = request.source
        val workspace = boundary_raster_workspaces.get()!!
        workspace.reset()
        val cell_span = source.tileSpan * DICTIONARY_EXTENT
        val cell_min_x = source.minimumTileX * DICTIONARY_EXTENT
        val cell_min_y = source.minimumTileY * DICTIONARY_EXTENT
        val local_to_pixel = band.corePixels / cell_span
        val gutter_local = BOUNDARY_RASTER_GUTTER_PX / local_to_pixel
        val clip_min_x = cell_min_x - gutter_local
        val clip_min_y = cell_min_y - gutter_local
        val clip_max_x = cell_min_x + cell_span + gutter_local
        val clip_max_y = cell_min_y + cell_span + gutter_local
        var inspected_records = 0
        var accepted_record_order = 0
        val keep_rendering = { boundary_raster_request_is_relevant(key) }
        for (source_tile in request.tiles) {
            val offset_x = (source_tile.drawX - source.originX) * DICTIONARY_EXTENT
            val offset_y = (source_tile.y - source.originY) * DICTIONARY_EXTENT
            for (record in source_tile.tile.boundaries) {
                if (inspected_records++ and 63 == 0 &&
                    !boundary_raster_request_is_relevant(key)
                ) return null
                if (!request.filter_mask.allows(
                        record.filter_id,
                        request.groups.enabled(record.layer_group),
                    )
                ) continue
                val visibility = record.visibility_rule?.let { rule ->
                    ReferencePresentationPolicy.outline_alpha_milli(
                        rule,
                        band.visibilityCentizoom,
                    )
                } ?: ReferencePresentationPolicy.full_alpha_milli
                if (visibility <= 0 || !record.geometry.intersects(
                        clip_min_x - offset_x,
                        clip_min_y - offset_y,
                        clip_max_x - offset_x,
                        clip_max_y - offset_y,
                    )
                ) continue
                val rendered_world_copy = ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
                    zoom = band.sourceZoom,
                    requestedTileX = source_tile.tile.coordinate.x,
                    drawTileX = source_tile.drawX,
                    postingWorldWrap = record.posting_world_wrap,
                )
                if (!workspace.occurrence_ids.admit(record.dedupe_id, rendered_world_copy)) continue
                if (!boundary_raster_request_is_relevant(key)) return null
                val batch = workspace.batch_for(record, accepted_record_order++)
                var appended = false
                for (ring in record.geometry.rings) {
                    appended = append_boundary_raster_ring(
                        target = batch.path,
                        ring = ring,
                        cell_min_x = cell_min_x,
                        cell_min_y = cell_min_y,
                        local_to_pixel = local_to_pixel,
                        clip_min_x = clip_min_x,
                        clip_min_y = clip_min_y,
                        clip_max_x = clip_max_x,
                        clip_max_y = clip_max_y,
                        clipped_segment = workspace.clipped_segment,
                        preserve_dash_phase = record.style.dash_pattern_dp.isNotEmpty(),
                        offset_x = offset_x,
                        offset_y = offset_y,
                        keep_rendering = keep_rendering,
                    ) || appended
                    if (!keep_rendering()) return null
                }
                if (appended) batch.boundaries++
            }
        }
        sort_boundary_raster_batches(workspace.batches)

        if (!boundary_raster_request_is_relevant(key)) return null
        val bitmap_size = band.corePixels + BOUNDARY_RASTER_GUTTER_PX * 2
        val bitmap = Bitmap.createBitmap(bitmap_size, bitmap_size, Bitmap.Config.ARGB_8888)
        draw_boundary_batches(
            canvas = Canvas(bitmap),
            viewport_zoom = band.visibilityCentizoom / 100.0,
            batches = workspace.batches,
            target_paint = workspace.paint,
            keep_drawing = keep_rendering,
        )
        if (!keep_rendering()) {
            bitmap.recycle()
            return null
        }
        bitmap.prepareToDraw()
        return BoundaryRasterTile(
            key = key,
            bitmap = bitmap,
            gutterPixels = BOUNDARY_RASTER_GUTTER_PX,
            boundaries = workspace.batches.sumOf { it.boundaries },
            byteCount = bitmap.allocationByteCount.toLong(),
        )
    }

    private fun sort_boundary_raster_batches(batches: ArrayList<BoundaryPathBatch>) {
        for (index in 1 until batches.size) {
            val batch = batches[index]
            var insertion_index = index
            while (insertion_index > 0 &&
                compare_boundary_raster_batches(batch, batches[insertion_index - 1]) < 0
            ) {
                batches[insertion_index] = batches[insertion_index - 1]
                insertion_index--
            }
            batches[insertion_index] = batch
        }
    }

    private fun compare_boundary_raster_batches(
        first: BoundaryPathBatch,
        second: BoundaryPathBatch,
    ): Int {
        val record_order = compare_reference_boundary_batch_order(
            first.draw_order,
            first.priority,
            first.first_source_feature_id,
            second.draw_order,
            second.priority,
            second.first_source_feature_id,
        )
        if (record_order != 0) return record_order
        return first.first_source_order.compareTo(second.first_source_order)
    }

    private fun DictionaryGeometry.intersects(
        min_x: Double,
        min_y: Double,
        max_x: Double,
        max_y: Double,
    ): Boolean {
        val bounds = bounds ?: return true
        return bounds.max_x.toDouble() >= min_x && bounds.min_x.toDouble() <= max_x &&
            bounds.max_y.toDouble() >= min_y && bounds.min_y.toDouble() <= max_y
    }

    private fun append_boundary_raster_ring(
        target: Path,
        ring: DictionaryRing,
        cell_min_x: Double,
        cell_min_y: Double,
        local_to_pixel: Double,
        clip_min_x: Double,
        clip_min_y: Double,
        clip_max_x: Double,
        clip_max_y: Double,
        clipped_segment: FloatArray,
        preserve_dash_phase: Boolean,
        offset_x: Double,
        offset_y: Double,
        keep_rendering: () -> Boolean,
    ): Boolean {
        if (ring.point_count < 2) return false
        if (preserve_dash_phase) {
            for (index in 0 until ring.point_count) {
                if (!reference_boundary_raster_segment_is_relevant(index, keep_rendering)) {
                    return false
                }
                val x = (BOUNDARY_RASTER_GUTTER_PX +
                    (ring.points[index * 2] + offset_x - cell_min_x) * local_to_pixel).toFloat()
                val y = (BOUNDARY_RASTER_GUTTER_PX +
                    (ring.points[index * 2 + 1] + offset_y - cell_min_y) * local_to_pixel).toFloat()
                if (index == 0) target.moveTo(x, y) else target.lineTo(x, y)
            }
            return true
        }

        var appended = false
        var last_x = Float.NaN
        var last_y = Float.NaN
        for (index in 1 until ring.point_count) {
            if (!reference_boundary_raster_segment_is_relevant(index, keep_rendering)) {
                return false
            }
            if (!clip_reference_boundary_segment(
                    (ring.points[(index - 1) * 2] + offset_x).toFloat(),
                    (ring.points[(index - 1) * 2 + 1] + offset_y).toFloat(),
                    (ring.points[index * 2] + offset_x).toFloat(),
                    (ring.points[index * 2 + 1] + offset_y).toFloat(),
                    clip_min_x.toFloat(),
                    clip_min_y.toFloat(),
                    clip_max_x.toFloat(),
                    clip_max_y.toFloat(),
                    clipped_segment,
                )
            ) {
                last_x = Float.NaN
                last_y = Float.NaN
                continue
            }
            val start_x = (BOUNDARY_RASTER_GUTTER_PX +
                (clipped_segment[0] - cell_min_x) * local_to_pixel).toFloat()
            val start_y = (BOUNDARY_RASTER_GUTTER_PX +
                (clipped_segment[1] - cell_min_y) * local_to_pixel).toFloat()
            val end_x = (BOUNDARY_RASTER_GUTTER_PX +
                (clipped_segment[2] - cell_min_x) * local_to_pixel).toFloat()
            val end_y = (BOUNDARY_RASTER_GUTTER_PX +
                (clipped_segment[3] - cell_min_y) * local_to_pixel).toFloat()
            if (last_x != start_x || last_y != start_y) target.moveTo(start_x, start_y)
            target.lineTo(end_x, end_y)
            last_x = end_x
            last_y = end_y
            appended = true
        }
        return appended
    }

    private fun publish_completed_boundary_rasters(): Boolean {
        var published = false
        while (true) {
            val prepared = completed_boundary_rasters.poll() ?: return published
            val requested = boundary_raster_requests.remove(prepared.key)
            val tile = prepared.tile
            if (!requested || tile == null ||
                prepared.key.band.packageGeneration != package_generation.get() ||
                !boundary_raster_request_is_relevant(prepared.key)
            ) {
                tile?.bitmap?.recycle()
                continue
            }
            put_boundary_raster(tile)
            published = true
        }
    }

    private fun put_boundary_raster(tile: BoundaryRasterTile) {
        val previous = boundary_raster_cache.put(tile.key, tile)
        if (previous != null) {
            boundary_raster_cache_bytes -= previous.byteCount
            previous.bitmap.recycle()
        }
        boundary_raster_cache_bytes += tile.byteCount
        trim_boundary_raster_cache()
    }

    private fun trim_boundary_raster_cache() {
        if (boundary_raster_cache_bytes <= BOUNDARY_RASTER_CACHE_BYTES) return
        val iterator = boundary_raster_cache.entries.iterator()
        while (boundary_raster_cache_bytes > BOUNDARY_RASTER_CACHE_BYTES &&
            iterator.hasNext()
        ) {
            val entry = iterator.next()
            if (entry.key in relevant_boundary_raster_keys ||
                entry.key in relevant_boundary_raster_safety_keys
            ) continue
            iterator.remove()
            boundary_raster_cache_bytes -= entry.value.byteCount
            entry.value.bitmap.recycle()
        }
    }

    private fun promote_boundary_raster_band(
        next: ReferenceBoundaryRasterBand,
        displayed_scene: BoundaryRasterScene?,
        now_ms: Long,
    ) {
        val previous = active_boundary_raster_band
        if (previous != null && previous != next) {
            val settled_previous = settled_boundary_raster_scene
                ?.takeIf { scene -> scene.band == previous }
            if (settled_previous != null) {
                resident_boundary_raster_scene = settled_previous
            }
            val fade_source = displayed_scene ?: settled_previous
            boundary_raster_fade = fade_source?.let { scene ->
                BoundaryRasterFade(
                    previous = scene,
                    currentBand = next,
                    startMs = now_ms,
                )
            }
            update_boundary_raster_safety_relevance()
        }
        active_boundary_raster_band = next
        pending_boundary_raster_band = null
        settled_boundary_raster_scene = null
    }

    private fun draw_boundary_raster(
        canvas: Canvas,
        viewport: Viewport,
        cell: ReferenceBoundaryRasterCell,
        tile: BoundaryRasterTile,
        clip: RectF? = null,
    ) {
        val tile_scale = MAP_TILE_SIZE * 2.0.pow(viewport.zoom - cell.zoom)
        val left = cell.drawX * tile_scale - (viewport.center_x - viewport.width / 2.0)
        val top = cell.y * tile_scale - (viewport.center_y - viewport.height / 2.0)
        val gutter_scale = tile_scale / tile.key.band.corePixels
        boundary_raster_destination.set(
            (left - tile.gutterPixels * gutter_scale).toFloat(),
            (top - tile.gutterPixels * gutter_scale).toFloat(),
            (left + tile_scale + tile.gutterPixels * gutter_scale).toFloat(),
            (top + tile_scale + tile.gutterPixels * gutter_scale).toFloat(),
        )
        canvas.withSave {
            if (clip != null) {
                clipRect(clip)
            } else {
                clipRect(
                    left.toFloat(),
                    top.toFloat(),
                    (left + tile_scale).toFloat(),
                    (top + tile_scale).toFloat(),
                )
            }
            drawBitmap(tile.bitmap, null, boundary_raster_destination, boundary_raster_paint)
        }
    }

    private fun draw_boundary_raster_fallback(
        canvas: Canvas,
        viewport: Viewport,
        cell: ReferenceBoundaryRasterCell,
        active_band: ReferenceBoundaryRasterBand,
    ): Int {
        val active_tile_scale = MAP_TILE_SIZE * 2.0.pow(viewport.zoom - cell.zoom)
        val child_left = cell.drawX * active_tile_scale -
            (viewport.center_x - viewport.width / 2.0)
        val child_top = cell.y * active_tile_scale -
            (viewport.center_y - viewport.height / 2.0)
        val child_clip = RectF(
            child_left.toFloat(),
            child_top.toFloat(),
            (child_left + active_tile_scale).toFloat(),
            (child_top + active_tile_scale).toFloat(),
        )
        val safety = draw_boundary_raster_scene_fallback(
            canvas,
            viewport,
            cell,
            child_clip,
            active_band,
            ready_boundary_raster_safety_scene,
        )
        if (safety >= 0) return safety
        val resident = draw_boundary_raster_scene_fallback(
            canvas,
            viewport,
            cell,
            child_clip,
            active_band,
            resident_boundary_raster_scene,
        )
        if (resident >= 0) return resident
        return draw_boundary_raster_scene_fallback(
            canvas,
            viewport,
            cell,
            child_clip,
            active_band,
            boundary_raster_fade?.previous,
        )
    }

    private fun draw_boundary_raster_scene_fallback(
        canvas: Canvas,
        viewport: Viewport,
        cell: ReferenceBoundaryRasterCell,
        child_clip: RectF,
        active_band: ReferenceBoundaryRasterBand,
        scene: BoundaryRasterScene?,
    ): Int {
        val band = scene?.band ?: return -1
        if (!scene.matches_environment(
                settingsKey = active_band.settingsKey,
                packageGeneration = active_band.packageGeneration,
            ) ||
            band.rasterZoom > cell.zoom
        ) return -1

        val factor = 1 shl (cell.zoom - band.rasterZoom)
        val parent_draw_x = Math.floorDiv(cell.drawX, factor)
        val parent_y = Math.floorDiv(cell.y, factor)
        val parent_limit = 1 shl band.rasterZoom
        val parent_x = Math.floorMod(parent_draw_x, parent_limit)
        val key = ReferenceBoundaryRasterKey(band, parent_x, parent_y)
        val tile = boundary_raster_cache[key] ?: return -1
        if (key !in scene.keys) return -1
        draw_boundary_raster(
            canvas = canvas,
            viewport = viewport,
            cell = ReferenceBoundaryRasterCell(
                zoom = band.rasterZoom,
                x = parent_x,
                drawX = parent_draw_x,
                y = parent_y,
                coreVisible = true,
                requestPriority = 0,
            ),
            tile = tile,
            clip = child_clip,
        )
        return tile.boundaries
    }

    private fun clear_boundary_rasters() {
        active_boundary_raster_band = null
        pending_boundary_raster_band = null
        relevant_boundary_raster_keys = emptySet()
        pending_boundary_raster_safety_scene = null
        ready_boundary_raster_safety_scene = null
        settled_boundary_raster_scene = null
        resident_boundary_raster_scene = null
        boundary_raster_fade = null
        relevant_boundary_raster_safety_keys = emptySet()
        boundary_raster_requests.clear()
        boundary_raster_disk_misses.clear()
        boundary_raster_disk_namespaces.clear()
        boundary_raster_cache.values.forEach { tile -> tile.bitmap.recycle() }
        boundary_raster_cache.clear()
        boundary_raster_cache_bytes = 0L
        boundary_raster_cells.clear()
        boundary_raster_draw_cells.clear()
        boundary_raster_cell_window.invalidate()
        boundary_raster_draw_cell_window.invalidate()
        boundary_raster_safety_cells.clear()
        boundary_raster_safety_cell_window.invalidate()
        boundary_raster_safety_draw_cells.clear()
        boundary_raster_safety_draw_cell_window.invalidate()
        invalidate_boundary_raster_relevance()
        while (true) {
            val prepared = completed_boundary_rasters.poll() ?: break
            prepared.tile?.bitmap?.recycle()
        }
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
        val current_generation = package_generation.get()
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
        val current_generation = package_generation.get()
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
        val records = ArrayList<DictionaryLineRecordRef>(256)
        collect_boundary_record_refs(
            viewport = request.viewport,
            tiles = request.tiles,
            groups = groups,
            filter_mask = request.filter_mask,
            output = records,
            occurrence_ids = HashSet(256),
            include_hidden = true,
        )
        if (!retained_bitmap_coordinator.shouldStart(request.token)) {
            return PreparedRetainedBoundaryBitmap(request, emptyList(), false)
        }
        val batches = prepare_boundary_batches(
            viewport = request.viewport,
            records = records,
            keep_drawing = { retained_bitmap_coordinator.shouldStart(request.token) },
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
                    current_package_generation = package_generation.get(),
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
        val current_generation = package_generation.get()
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
        val current_generation = package_generation.get()
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
            frame.package_generation != package_generation.get() ||
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
            frame.package_generation != package_generation.get() ||
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
                draw_boundary_batches(
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
            clear_zoom_ahead_desired_tile_keys()
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
            clear_zoom_ahead_desired_tile_keys()
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
            clear_zoom_ahead_desired_tile_keys()
            return 0
        }

        val generation = update_zoom_ahead_desired_tile_keys(zoom_ahead_visible_tiles)
        zoom_ahead_draw_tiles.clear()
        var missing = 0
        var requested = 0
        for (tile in zoom_ahead_visible_tiles) {
            val parsed = synchronized(tile_cache) {
                tile_cache.get(tile.cache_key(), tile.low_zoom_profile())
            }
            if (parsed == null) {
                missing++
                if (requested < ZOOM_AHEAD_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    requested += request_tile_if_needed(tile, generation)
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

        clear_zoom_ahead_desired_tile_keys()
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
            package_generation_snapshot = package_generation.get(),
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
        val package_generation_snapshot = package_generation.get()
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
            collect_boundary_record_refs(viewport, tiles, groups, filter_mask)
            boundaries_drawn = draw_boundary_records(canvas, viewport, boundary_record_refs)
            boundary_record_refs.clear()
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

    private fun collect_boundary_record_refs(
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        groups: ReferenceDictionaryLayerGroups,
        filter_mask: ReferenceFilterMask,
        output: MutableList<DictionaryLineRecordRef> = boundary_record_refs,
        occurrence_ids: MutableSet<ReferenceBoundaryOccurrenceKey> = boundary_occurrence_ids,
        include_hidden: Boolean = false,
    ) {
        output.clear()
        occurrence_ids.clear()
        val current_centizoom = ReferencePresentationPolicy.centizoom(viewport.zoom)
        for (tile in tiles) {
            for (record in tile.tile.boundaries) {
                if (!filter_mask.allows(record.filter_id, groups.enabled(record.layer_group))) {
                    continue
                }
                val visibility_alpha_milli = record.visibility_rule?.let { rule ->
                    ReferencePresentationPolicy.outline_alpha_milli(rule, current_centizoom)
                } ?: ReferencePresentationPolicy.full_alpha_milli
                if (!include_hidden && visibility_alpha_milli <= 0) continue
                val rendered_world_copy = ReferenceBoundaryOccurrenceSelector.renderedWorldCopy(
                    zoom = tile.tile.coordinate.z,
                    requestedTileX = tile.tile.coordinate.x,
                    drawTileX = tile.draw_x,
                    postingWorldWrap = record.posting_world_wrap,
                )
                if (!ReferenceBoundaryOccurrenceSelector.admit(
                        occurrence_ids,
                        record.dedupe_id,
                        rendered_world_copy,
                    )
                ) {
                    continue
                }
                output += DictionaryLineRecordRef(
                    tile = tile,
                    record = record,
                )
            }
        }
        output.sortWith(
            compareBy<DictionaryLineRecordRef> { it.record.draw_order }
                .thenBy { it.record.priority }
                .thenBy { it.record.source_feature_id },
        )
    }

    private fun draw_boundary_records(
        canvas: Canvas,
        viewport: Viewport,
        records: List<DictionaryLineRecordRef>,
        target_paint: Paint = paint,
        keep_drawing: (() -> Boolean)? = null,
    ): Int {
        val batches = prepare_boundary_batches(viewport, records, keep_drawing)
        return draw_boundary_batches(
            canvas = canvas,
            viewport_zoom = viewport.zoom,
            batches = batches,
            target_paint = target_paint,
            keep_drawing = keep_drawing,
        )
    }

    private fun prepare_boundary_batches(
        viewport: Viewport,
        records: List<DictionaryLineRecordRef>,
        keep_drawing: (() -> Boolean)? = null,
    ): List<BoundaryPathBatch> {
        val batches = LinkedHashMap<BoundaryPathBatchKey, BoundaryPathBatch>()
        var record_index = 0
        for (reference in records) {
            if (record_index++ and 63 == 0 && keep_drawing?.invoke() == false) {
                return emptyList()
            }
            val record = reference.record
            val key = BoundaryPathBatchKey(
                draw_order = record.draw_order,
                priority = record.priority,
                style = record.style,
                visibility_rule = record.visibility_rule,
            )
            val batch = batches.getOrPut(key) {
                BoundaryPathBatch(
                    path = Path(),
                    style = record.style,
                    visibility_rule = record.visibility_rule,
                )
            }
            var appended = false
            for (ring in record.geometry.rings) {
                appended = append_ring_path(batch.path, viewport, reference.tile, ring) || appended
            }
            if (appended) batch.boundaries++
        }
        return batches.values.toList()
    }

    private fun draw_boundary_batches(
        canvas: Canvas,
        viewport_zoom: Double,
        batches: List<BoundaryPathBatch>,
        target_paint: Paint = paint,
        draw_scale: Float = 1f,
        frame_alpha: Int = 255,
        keep_drawing: (() -> Boolean)? = null,
    ): Int {
        val current_centizoom = ReferencePresentationPolicy.centizoom(viewport_zoom)
        var boundaries_drawn = 0
        for (batch in batches) {
            if (keep_drawing?.invoke() == false) return boundaries_drawn
            val visibility_alpha_milli = batch.visibility_rule?.let { rule ->
                ReferencePresentationPolicy.outline_alpha_milli(rule, current_centizoom)
            } ?: ReferencePresentationPolicy.full_alpha_milli
            if (visibility_alpha_milli <= 0) continue
            draw_boundary_batch(
                canvas = canvas,
                batch = batch,
                visibility_alpha_milli = visibility_alpha_milli,
                target_paint = target_paint,
                draw_scale = draw_scale,
                frame_alpha = frame_alpha,
            )
            boundaries_drawn += batch.boundaries
        }
        return boundaries_drawn
    }

    private fun draw_boundary_batch(
        canvas: Canvas,
        batch: BoundaryPathBatch,
        visibility_alpha_milli: Int,
        target_paint: Paint = paint,
        draw_scale: Float = 1f,
        frame_alpha: Int = 255,
    ) {
        prepare_boundary_paint(target_paint, batch.style, draw_scale)
        apply_boundary_paint(
            target_paint,
            batch.style,
            visibility_alpha_milli,
            BoundaryPaintPass.HALO,
            draw_scale,
            frame_alpha,
        )
        canvas.drawPath(batch.path, target_paint)
        apply_boundary_paint(
            target_paint,
            batch.style,
            visibility_alpha_milli,
            BoundaryPaintPass.STROKE,
            draw_scale,
            frame_alpha,
        )
        canvas.drawPath(batch.path, target_paint)
        target_paint.pathEffect = null
    }

    private fun prepare_boundary_paint(
        target_paint: Paint,
        style: DictionaryLineStyle,
        draw_scale: Float,
    ) {
        val inverse_scale = 1f / draw_scale.coerceAtLeast(MIN_BOUNDARY_DRAW_SCALE)
        target_paint.isAntiAlias = true
        target_paint.style = Paint.Style.STROKE
        target_paint.strokeJoin = style.stroke_join
        target_paint.strokeCap = style.stroke_cap
        target_paint.pathEffect = style.dash_pattern_dp.takeIf { it.isNotEmpty() }
            ?.map { value -> value * inverse_scale }
            ?.toFloatArray()
            ?.let { intervals ->
                DashPathEffect(intervals, style.dash_phase_dp * inverse_scale)
            }
    }

    private fun apply_boundary_paint(
        target_paint: Paint,
        style: DictionaryLineStyle,
        visibility_alpha_milli: Int,
        pass: BoundaryPaintPass,
        draw_scale: Float,
        frame_alpha: Int,
    ) {
        val inverse_scale = 1f / draw_scale.coerceAtLeast(MIN_BOUNDARY_DRAW_SCALE)
        if (pass == BoundaryPaintPass.HALO) {
            target_paint.strokeWidth = boundary_density *
                (style.stroke_width_dp + style.halo_width_dp) * inverse_scale
            target_paint.color = boundary_color_with_alpha(
                style.halo_color,
                scaled_alpha_byte(
                    style.halo_alpha,
                    visibility_alpha_milli,
                    frame_alpha,
                ),
            )
        } else {
            target_paint.strokeWidth = boundary_density * style.stroke_width_dp * inverse_scale
            target_paint.color = boundary_color_with_alpha(
                style.color,
                scaled_alpha_byte(style.alpha, visibility_alpha_milli, frame_alpha),
            )
        }
    }

    private fun boundary_color_with_alpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private fun append_ring_path(
        target: Path,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        ring: DictionaryRing,
    ): Boolean {
        if (ring.point_count < 2) return false
        val scale = 2.0.pow(viewport.zoom - tile.tile.coordinate.z)
        val local_to_screen = (MAP_TILE_SIZE / DICTIONARY_EXTENT) * scale
        val left_world = viewport.center_x - viewport.width / 2.0
        val top_world = viewport.center_y - viewport.height / 2.0
        val translate_x = tile.draw_x * MAP_TILE_SIZE * scale - left_world
        val translate_y = tile.tile.coordinate.y * MAP_TILE_SIZE * scale - top_world
        for (index in 0 until ring.point_count) {
            val x = (translate_x + ring.points[index * 2] * local_to_screen).toFloat()
            val y = (translate_y + ring.points[index * 2 + 1] * local_to_screen).toFloat()
            if (index == 0) {
                target.moveTo(x, y)
            } else {
                target.lineTo(x, y)
            }
        }
        return true
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

    private fun parse_tile_payload(
        payload: ReferenceDictionaryTilePayload,
        outline_visibility_centizoom: Int? = null,
        request_is_relevant: () -> Boolean,
    ): ParsedDictionaryTile? {
        if (!request_is_relevant()) return null
        if (payload.runtime_schema == ReferenceDictionaryRuntimeSchema.BINARY_V3 ||
            payload.runtime_schema == ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1
        ) {
            return parse_binary_tile_payload(
                payload,
                outline_visibility_centizoom,
                request_is_relevant,
            )
        }
        val root = JSONObject(payload.raw_json)
        if (!request_is_relevant()) return null
        val records = root.optJSONObject("records") ?: JSONObject()
        val hot_fields = payload.runtime_schema == ReferenceDictionaryRuntimeSchema.HOT_JSON_V2
        val parsed = ParsedDictionaryTile(
            coordinate = payload.coordinate,
            boundaries = parse_line_records(
                records = records.optJSONArray("boundaries"),
                hot_fields = hot_fields
            ),
            labels = parse_label_records(
                records = records.optJSONArray("labels"),
                z = payload.coordinate.z,
                hot_fields = hot_fields
            )
        )
        return if (request_is_relevant()) parsed else null
    }

    private fun parse_binary_tile_payload(
        payload: ReferenceDictionaryTilePayload,
        outline_visibility_centizoom: Int?,
        request_is_relevant: () -> Boolean,
    ): ParsedDictionaryTile? {
        val decoded = when (payload.runtime_schema) {
            ReferenceDictionaryRuntimeSchema.BINARY_V3 -> {
                if (!request_is_relevant()) return null
                val tile = ReferenceDictionaryBinaryTileCodec.decode(
                    payload.coordinate,
                    payload.raw_bytes,
                )
                if (request_is_relevant()) tile else return null
            }

            ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1 ->
                ReferenceDictionaryRenderTileCodec.decodeIfRelevant(
                    payload.coordinate,
                    payload.raw_bytes,
                    outlineVisibilityCentizoom = outline_visibility_centizoom,
                    requestIsRelevant = request_is_relevant,
                ) ?: return null

            else -> error("reference payload is not a binary render tile")
        }
        val boundaries = ArrayList<DictionaryLineRecord>()
        val labels = ArrayList<DictionaryLabelRecord>()
        decoded.records.forEachIndexed { index, record ->
            if (
                index % PRESENTATION_CANCELLATION_INTERVAL == 0 &&
                !request_is_relevant()
            ) {
                return null
            }
            when (val model = ReferenceDictionaryBinaryPresenter.present(record)) {
                is ReferenceDictionaryBinaryDrawModel.Label -> {
                    val resolved = model.resolvedStyle
                    val text_size_sp = model.visibility.textSizeMilliSp / 1_000f
                    val presentation_subtype =
                        ReferenceLabelRuntimePresentationPolicy.presentationSubtype(
                            model.subtype,
                            model.text.primaryText,
                        )
                    val runtime_typography = ReferenceLabelRuntimePresentationPolicy.typography(
                        presentation_subtype,
                        model.prominenceTier,
                        model.completeGeometryMeasureBucket,
                    )
                    val source_kind = binary_source_kind(model.subtype)
                    val geometry = if (
                        model.geometry.kind == ReferenceDictionaryBinaryGeometryKind.POINT
                    ) {
                        null
                    } else {
                        binary_geometry(model.geometry, request_is_relevant) ?: return null
                    }
                    labels += DictionaryLabelRecord(
                        text = model.text.primaryText,
                        source_layer = "",
                        source_kind = source_kind,
                        line_label = model.lineLabel,
                        drawable = true,
                        protected_area = model.subtype == SemanticSubtype.PROTECTED_LAND,
                        layer_group = binary_layer_group(model.filterId),
                        filter_id = model.filterId,
                        priority = model.priority,
                        color = resolved.color_argb.toInt(),
                        base_text_size_sp = text_size_sp,
                        alpha = alpha_milli_to_byte(resolved.alpha_milli),
                        halo_color = resolved.halo_argb.toInt(),
                        halo_width_dp = text_size_sp * resolved.halo_width_milli_em / 1_000f,
                        halo_alpha = alpha_milli_to_byte(resolved.halo_alpha_milli),
                        typeface_style = binary_typeface_style(model.style),
                        letter_spacing_em = model.visibility.letterSpacingMilliEm / 1_000f,
                        rotation = 0f,
                        rank = null,
                        min_zoom = model.visibility.minimumZoomCenti / 100.0,
                        max_zoom = ReferencePresentationPolicy.label_display_max_zoom_centi / 100.0,
                        anchor = if (model.geometry.kind == ReferenceDictionaryBinaryGeometryKind.POINT) {
                            DictionaryPoint(
                                model.geometry.localCoordinates[0],
                                model.geometry.localCoordinates[1],
                            )
                        } else {
                            null
                        },
                        geometry = geometry,
                        sourced_text = model.text,
                        dedupe_key = model.labelCandidateId.toString(16),
                        is_water = binary_water_label(model.subtype),
                        candidate_id = model.labelCandidateId,
                        source_feature_id = model.featureId,
                        presentation_subtype = presentation_subtype,
                        prominence_tier = model.prominenceTier,
                        complete_geometry_measure_bucket = model.completeGeometryMeasureBucket,
                        low_zoom_country_rank = if (
                            model.subtype == SemanticSubtype.COUNTRY_TERRITORY
                        ) {
                            reference_low_zoom_country_rank(model.featureId)
                        } else {
                            UNKNOWN_LOW_ZOOM_COUNTRY_RANK
                        },
                        runtime_typography = runtime_typography,
                        visibility_rule = model.visibilityRule,
                        repeat_spacing_px = model.repeatSpacingPx,
                        posting_world_wrap = record.postingWorldWrap,
                    )
                }

                is ReferenceDictionaryBinaryDrawModel.Outline -> {
                    val resolved = model.resolvedStyle
                    val geometry = binary_geometry(
                        model.geometry,
                        request_is_relevant,
                    ) ?: return null
                    boundaries += DictionaryLineRecord(
                        source_layer = "",
                        source_kind = binary_source_kind(model.subtype),
                        class_name = model.subtype.name,
                        protected_area = model.subtype == SemanticSubtype.PROTECTED_AREA_OUTLINE,
                        layer_group = binary_layer_group(model.filterId),
                        filter_id = model.filterId,
                        geometry = geometry,
                        visibility_rule = if (
                            model.subtype == SemanticSubtype.STATE_PROVINCE_BOUNDARY
                        ) {
                            model.visibility.copy(full_alpha_zoom_centi = 700)
                        } else {
                            model.visibility
                        },
                        draw_order = if (
                            model.subtype == SemanticSubtype.INTERNATIONAL_BOUNDARY
                        ) {
                            24
                        } else {
                            model.drawOrder
                        },
                        priority = model.priority,
                        source_feature_id = model.featureId,
                        dedupe_id = model.dedupeId,
                        posting_world_wrap = record.postingWorldWrap,
                        style = boundary_display_style(
                            model.subtype,
                            DictionaryLineStyle(
                                color = resolved.color_argb.toInt(),
                                stroke_width_dp = model.style.line_width_milli_dp / 1_000f,
                                halo_width_dp = resolved.line_halo_width_milli_dp / 1_000f,
                                alpha = alpha_milli_to_byte(resolved.alpha_milli),
                                halo_alpha = alpha_milli_to_byte(resolved.halo_alpha_milli),
                                halo_color = resolved.halo_argb.toInt(),
                                dash_pattern_dp = resolved.dash_milli_dp.map { it / 1_000f },
                                dash_phase_dp = resolved.dash_phase_milli_dp / 1_000f,
                                stroke_cap = if (resolved.line_cap == "butt") {
                                    Paint.Cap.BUTT
                                } else {
                                    Paint.Cap.ROUND
                                },
                                stroke_join = if (resolved.line_join == "miter") {
                                    Paint.Join.MITER
                                } else {
                                    Paint.Join.ROUND
                                },
                            ),
                        ),
                    )
                }
            }
        }
        if (!request_is_relevant()) return null
        boundaries.sortBy { it.style.stroke_width_dp }
        if (!request_is_relevant()) return null
        return ParsedDictionaryTile(
            coordinate = decoded.coordinate,
            boundaries = boundaries,
            labels = labels,
        )
    }

    // Keep the verified dictionary policy immutable; only its final on-screen treatment changes.
    private fun boundary_display_style(
        subtype: SemanticSubtype,
        canonical_style: DictionaryLineStyle,
    ): DictionaryLineStyle = when (subtype) {
        SemanticSubtype.INTERNATIONAL_BOUNDARY -> canonical_style.copy(
            color = Color.rgb(238, 242, 244),
            stroke_width_dp = 1.55f,
            halo_width_dp = 0.9f,
            alpha = 230,
            halo_alpha = 166,
            dash_pattern_dp = emptyList(),
        )

        SemanticSubtype.STATE_PROVINCE_BOUNDARY -> canonical_style.copy(
            color = Color.rgb(210, 218, 222),
            stroke_width_dp = 0.75f,
            halo_width_dp = 0.15f,
            alpha = 120,
            halo_alpha = 25,
            dash_pattern_dp = listOf(0.9f, 2.7f),
            stroke_cap = Paint.Cap.ROUND,
        )

        else -> canonical_style
    }

    private fun binary_geometry(
        geometry: ReferenceDictionaryBinaryGeometry,
        request_is_relevant: () -> Boolean,
    ): DictionaryGeometry? {
        val rings = ArrayList<DictionaryRing>(geometry.partOffsets.size)
        val total_points = geometry.localCoordinates.size / 2
        geometry.partOffsets.forEachIndexed { index, start ->
            if (!request_is_relevant()) return null
            val end = if (index + 1 < geometry.partOffsets.size) {
                geometry.partOffsets[index + 1]
            } else {
                total_points
            }
            val point_count = end - start
            if (point_count > 0) {
                rings += DictionaryRing(
                    points = geometry.localCoordinates.copyOfRange(start * 2, end * 2),
                    point_count = point_count,
                )
            }
        }
        return DictionaryGeometry(
            rings = rings,
            bounds = DictionaryBounds(
                geometry.localBounds[0],
                geometry.localBounds[1],
                geometry.localBounds[2],
                geometry.localBounds[3],
            ),
        )
    }

    private fun binary_layer_group(filter_id: FilterId): ReferenceDictionaryLayerGroup {
        return when (filter_id) {
            FilterId.LABELS_PLACES,
            FilterId.LABELS_ISLANDS -> ReferenceDictionaryLayerGroup.PLACES

            FilterId.LABELS_MAJOR_WATER,
            FilterId.LABELS_RIVERS,
            FilterId.LABELS_STREAMS,
            FilterId.LABELS_CANALS,
            FilterId.OUTLINES_COASTLINES,
            FilterId.OUTLINES_WATER_BOUNDARIES -> ReferenceDictionaryLayerGroup.WATER

            FilterId.LABELS_PROTECTED_LANDS,
            FilterId.OUTLINES_PROTECTED_AREAS -> ReferenceDictionaryLayerGroup.PUBLIC_LANDS

            else -> ReferenceDictionaryLayerGroup.REGIONS
        }
    }

    private fun binary_source_kind(subtype: SemanticSubtype): String {
        return when (subtype) {
            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
            SemanticSubtype.COASTLINE,
            SemanticSubtype.WATERSHED_WATER_BOUNDARY -> "water"

            SemanticSubtype.COUNTRY_TERRITORY,
            SemanticSubtype.FIRST_ORDER_REGION,
            SemanticSubtype.SECOND_LOCAL_REGION,
            SemanticSubtype.INTERNATIONAL_BOUNDARY,
            SemanticSubtype.STATE_PROVINCE_BOUNDARY,
            SemanticSubtype.COUNTY_LOCAL_BOUNDARY,
            SemanticSubtype.OTHER_ADMIN_BOUNDARY -> "admin"

            else -> "place"
        }
    }

    private fun binary_water_label(subtype: SemanticSubtype): Boolean {
        return subtype in setOf(
            SemanticSubtype.OCEAN_SEA,
            SemanticSubtype.BAY_SOUND,
            SemanticSubtype.LAKE_RESERVOIR,
            SemanticSubtype.RIVER,
            SemanticSubtype.STREAM_CREEK,
            SemanticSubtype.CANAL_CHANNEL,
            SemanticSubtype.UNSPECIFIED_WATERCOURSE,
        )
    }

    private fun binary_typeface_style(style: StyleSpec): Int {
        val bold = style.font_weight >= 600
        val italic = style.font_slant == FontSlant.ITALIC
        return when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
    }

    private fun alpha_milli_to_byte(value: Int): Int {
        return ((value.coerceIn(0, 1_000) * 255) + 500) / 1_000
    }

    private fun scaled_alpha_byte(
        base_alpha: Int,
        visibility_alpha_milli: Int,
        frame_alpha: Int = 255,
    ): Int {
        val visible_alpha = (
            base_alpha.coerceIn(0, 255) * visibility_alpha_milli.coerceIn(0, 1_000) + 500
            ) / 1_000
        return (visible_alpha * frame_alpha.coerceIn(0, 255) + 127) / 255
    }

    private fun parse_line_records(
        records: JSONArray?,
        hot_fields: Boolean
    ): List<DictionaryLineRecord> {
        if (records == null || records.length() <= 0) return emptyList()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val source_layer = record.optString("sourceLayer", "")
                val baked_layer_group = if (hot_fields) record.layer_group_or_null() else null
                val source_kind = record.optString("sourceKind", "")
                    .ifBlank { source_kind_for_group(baked_layer_group) ?: "" }
                val class_name = record.optString("class", "")
                val protected_area = record.optional_boolean("protectedArea")
                    ?: if (baked_layer_group != null) {
                        baked_layer_group == ReferenceDictionaryLayerGroup.PUBLIC_LANDS
                    } else {
                        protected_area_line(
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name
                        )
                    }
                val layer_group = baked_layer_group ?: line_layer_group(
                    source_layer = source_layer,
                    source_kind = source_kind,
                    class_name = class_name,
                    protected_area = protected_area
                )
                val geometry = parse_geometry(record.optJSONObject("geometry")) ?: continue
                add(
                    DictionaryLineRecord(
                        source_layer = source_layer,
                        source_kind = source_kind,
                        class_name = class_name,
                        protected_area = protected_area,
                        layer_group = layer_group,
                        geometry = geometry,
                        style = line_style_for(
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name,
                            layer_group = layer_group
                        )
                    )
                )
            }
        }
    }

    private fun parse_label_records(
        records: JSONArray?,
        z: Int,
        hot_fields: Boolean
    ): List<DictionaryLabelRecord> {
        if (records == null || records.length() <= 0) return emptyList()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val text = record.optString("text", "").trim()
                if (text.isEmpty()) continue
                val source_layer = record.optString("sourceLayer", "")
                val baked_layer_group = if (hot_fields) record.layer_group_or_null() else null
                val source_kind = record.optString("sourceKind", "")
                    .ifBlank { source_kind_for_group(baked_layer_group) ?: "" }
                val label_placement = record.optString("labelPlacement", "")
                    .ifBlank { if (record.optBoolean("lineLabel", false)) "line" else "" }
                val rank = record.optional_int("rank")
                val class_name = record.optString("class", "")
                val protected_area = record.optional_boolean("protectedArea")
                    ?: if (baked_layer_group != null) {
                        baked_layer_group == ReferenceDictionaryLayerGroup.PUBLIC_LANDS
                    } else {
                        protected_area_label(
                            text = text,
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name
                        )
                    }
                val layer_group = baked_layer_group ?: label_layer_group(
                    source_kind = source_kind,
                    source_layer = source_layer,
                    protected_area = protected_area
                )
                if (!should_draw_label(
                        text = text,
                        source_kind = source_kind,
                        source_layer = source_layer,
                        label_placement = label_placement,
                        rank = rank,
                        protected_area = protected_area,
                        z = z
                    )
                ) {
                    continue
                }
                val line_label = label_placement == "line"
                val anchor = parse_point(record.optJSONObject("anchor"))
                val geometry = if (line_label || anchor == null) {
                    parse_geometry(record.optJSONObject("geometry"))
                } else {
                    null
                }
                val style_template = label_style_template_for(
                    text = text,
                    source_kind = source_kind,
                    source_layer = source_layer,
                    label_placement = label_placement,
                    rank = rank,
                    protected_area = protected_area,
                    z = z
                )
                add(
                    DictionaryLabelRecord(
                        text = text,
                        source_layer = source_layer,
                        source_kind = source_kind,
                        line_label = line_label,
                        drawable = true,
                        protected_area = protected_area,
                        layer_group = layer_group,
                        priority = record.optional_int("priority") ?: label_priority(
                            text = text,
                            source_kind = source_kind,
                            source_layer = source_layer,
                            label_placement = label_placement,
                            rank = rank,
                            protected_area = protected_area,
                            z = z
                        ),
                        color = style_template.color,
                        base_text_size_sp = style_template.text_size_sp,
                        alpha = style_template.alpha,
                        halo_width_dp = style_template.halo_width_dp,
                        halo_alpha = style_template.halo_alpha,
                        typeface_style = style_template.typeface_style,
                        rotation = if (source_kind == "water" && geometry?.bounds != null) {
                            label_rotation(geometry.bounds)
                        } else {
                            0f
                        },
                        rank = rank,
                        min_zoom = record.optional_double("minZoom")
                            ?: record.optional_double("minzoom"),
                        max_zoom = record.optional_double("maxZoom")
                            ?: record.optional_double("maxzoom"),
                        anchor = anchor,
                        geometry = geometry,
                        dedupe_key = "$source_kind:${text.lowercase()}",
                        is_water = source_kind == "water",
                    )
                )
            }
        }
    }

    private fun parse_geometry(geometry: JSONObject?): DictionaryGeometry? {
        if (geometry == null) return null
        val rings_json = geometry.optJSONArray("rings")
        val rings = if (rings_json != null) {
            buildList(rings_json.length()) {
                for (index in 0 until rings_json.length()) {
                    parse_ring(rings_json.optJSONArray(index))?.let { add(it) }
                }
            }
        } else {
            emptyList()
        }
        val bounds = geometry.optJSONObject("bounds")?.let { bounds_json ->
            DictionaryBounds(
                min_x = bounds_json.optDouble("minX").toFloat(),
                min_y = bounds_json.optDouble("minY").toFloat(),
                max_x = bounds_json.optDouble("maxX").toFloat(),
                max_y = bounds_json.optDouble("maxY").toFloat()
            )
        }
        return DictionaryGeometry(rings = rings, bounds = bounds)
    }

    private fun parse_ring(ring: JSONArray?): DictionaryRing? {
        if (ring == null || ring.length() < 2) return null
        val points = FloatArray(ring.length() * 2)
        var count = 0
        for (index in 0 until ring.length()) {
            val point = ring.optJSONArray(index) ?: continue
            points[count * 2] = point.optDouble(0).toFloat()
            points[count * 2 + 1] = point.optDouble(1).toFloat()
            count++
        }
        if (count < 2) return null
        return DictionaryRing(points = points.copyOf(count * 2), point_count = count)
    }

    private fun parse_point(point: JSONObject?): DictionaryPoint? {
        if (point == null) return null
        return DictionaryPoint(
            x = point.optDouble("x").toFloat(),
            y = point.optDouble("y").toFloat()
        )
    }

    private fun should_draw_label(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        protected_area: Boolean,
        z: Int
    ): Boolean {
        if (text.length > MAX_LABEL_TEXT_LENGTH) return false
        if (source_kind == "transportation") return false
        if (source_layer.contains("Road", ignoreCase = true)) return false
        if (source_layer.contains("Spot elevation", ignoreCase = true)) return false
        if (ROAD_SHIELD_LABEL.matches(text) && source_layer != "Graticule/label") {
            return false
        }
        if (z <= 8 && source_layer.contains("Graticule")) return false
        if (source_kind == "water") {
            if (label_placement == "line") return true
            return rank == null || rank <= water_point_rank_limit(z)
        }
        if (source_kind == "admin") return rank == null || rank <= admin_rank_limit(z)
        if (source_kind == "county") return z <= 9 && (rank == null || rank <= 24)
        if (protected_area) {
            return rank == null || rank <= protected_area_rank_limit(z)
        }
        if (source_kind == "place") {
            val limit = place_rank_limit(z, source_layer)
            return (rank == null && source_layer.contains("City", ignoreCase = true)) ||
                    (rank != null && rank <= limit)
        }
        return rank != null && rank <= 24
    }

    private fun place_rank_limit(z: Int, source_layer: String): Int {
        val base = when {
            z <= 8 -> 12
            z <= 10 -> 22
            z == 11 -> 34
            else -> 46
        }
        return if (source_layer.contains("City", ignoreCase = true)) base + 14 else base
    }

    private fun water_point_rank_limit(z: Int): Int {
        return when {
            z <= 8 -> 28
            z <= 10 -> 20
            else -> 34
        }
    }

    private fun admin_rank_limit(z: Int): Int {
        return if (z <= 8) 36 else 24
    }

    private fun protected_area_rank_limit(z: Int): Int {
        return when {
            z <= 8 -> 18
            z <= 10 -> 32
            else -> 48
        }
    }

    private fun label_priority(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        protected_area: Boolean,
        z: Int
    ): Int {
        val hierarchy_rank = hierarchy_rank(
            source_kind = source_kind,
            source_layer = source_layer,
            rank = rank
        )
        var priority = when {
            protected_area -> 172
            source_kind == "water" -> if (label_placement == "line") 38 else 128
            source_kind == "admin" -> 56
            source_kind == "place" && source_layer.contains("City", ignoreCase = true) -> 86
            source_kind == "place" -> 158
            source_kind == "county" -> 138
            else -> 260
        }
        priority += hierarchy_rank
        if (text.length <= 4) priority += 72
        if (z <= 8 && source_kind == "place") priority += 18
        return priority
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

    private fun label_style_template_for(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        protected_area: Boolean,
        z: Int
    ): DictionaryLabelStyleTemplate {
        val tier = hierarchy_tier(
            source_kind = source_kind,
            source_layer = source_layer,
            rank = rank
        )
        if (protected_area) {
            return DictionaryLabelStyleTemplate(
                color = Color.rgb(178, 213, 174),
                text_size_sp = hierarchy_size(12.4f, tier + 1, 0.45f),
                alpha = hierarchy_alpha(196, tier + 1, 12),
                halo_width_dp = hierarchy_halo(2.05f, tier + 1),
                halo_alpha = hierarchy_alpha(198, tier + 1, 12),
                typeface_style = Typeface.NORMAL
            )
        }
        return when (source_kind) {
            "water" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(172, 222, 247),
                text_size_sp = if (label_placement == "line") {
                    hierarchy_size(14.2f, tier, 0.8f)
                } else {
                    hierarchy_size(12.6f, tier, 0.55f)
                },
                alpha = hierarchy_alpha(238, tier, 14),
                halo_width_dp = hierarchy_halo(2.25f, tier),
                halo_alpha = hierarchy_alpha(226, tier, 10),
                typeface_style = Typeface.ITALIC
            )

            "county" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(222, 232, 240),
                text_size_sp = hierarchy_size(12.4f, tier, 0.45f),
                alpha = hierarchy_alpha(186, tier + 1, 16),
                halo_width_dp = hierarchy_halo(2.0f, tier + 1),
                halo_alpha = hierarchy_alpha(190, tier + 1, 14),
                typeface_style = Typeface.NORMAL
            )

            "admin" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(238, 246, 252),
                text_size_sp = hierarchy_size(14.8f, tier, 0.75f),
                alpha = hierarchy_alpha(230, tier, 12),
                halo_width_dp = hierarchy_halo(2.45f, tier),
                halo_alpha = hierarchy_alpha(224, tier, 10),
                typeface_style = Typeface.BOLD
            )

            "place" -> DictionaryLabelStyleTemplate(
                color = Color.rgb(248, 251, 255),
                text_size_sp = hierarchy_size(
                    if (source_layer.contains("City", ignoreCase = true)) 14.3f else 12.7f,
                    tier,
                    0.7f
                ),
                alpha = hierarchy_alpha(238, tier, 14),
                halo_width_dp = hierarchy_halo(2.5f, tier),
                halo_alpha = hierarchy_alpha(230, tier, 10),
                typeface_style = if (tier <= 1) Typeface.BOLD else Typeface.NORMAL
            )

            else -> DictionaryLabelStyleTemplate(
                color = Color.rgb(242, 247, 251),
                text_size_sp = if (z >= 12) 12.5f else 13.5f,
                alpha = 226,
                halo_width_dp = 2.4f,
                halo_alpha = 220,
                typeface_style = Typeface.NORMAL
            )
        }
    }

    private fun hierarchy_rank(
        source_kind: String,
        source_layer: String,
        rank: Int?
    ): Int {
        if (rank != null) return rank.coerceIn(0, 160)
        return when {
            source_kind == "admin" -> 10
            source_kind == "water" -> 18
            source_kind == "place" && source_layer.contains("City", ignoreCase = true) -> 24
            source_kind == "county" -> 46
            else -> 80
        }
    }

    private fun hierarchy_tier(
        source_kind: String,
        source_layer: String,
        rank: Int?
    ): Int {
        val normalized_rank = hierarchy_rank(source_kind, source_layer, rank)
        return when {
            normalized_rank <= 10 -> 0
            normalized_rank <= 24 -> 1
            normalized_rank <= 46 -> 2
            else -> 3
        }
    }

    private fun hierarchy_size(base_sp: Float, tier: Int, step_sp: Float): Float {
        return (base_sp - tier.coerceIn(0, 3) * step_sp).coerceAtLeast(10.8f)
    }

    private fun hierarchy_alpha(base: Int, tier: Int, step: Int): Int {
        return (base - tier.coerceIn(0, 4) * step).coerceIn(128, 245)
    }

    private fun hierarchy_halo(base_dp: Float, tier: Int): Float {
        return (base_dp - tier.coerceIn(0, 3) * 0.18f).coerceAtLeast(1.7f)
    }

    private fun protected_area_label(
        text: String,
        source_layer: String,
        source_kind: String,
        class_name: String = ""
    ): Boolean {
        if (source_kind == "water" || source_kind == "county") return false
        val text_terms = reference_terms(text)
        val metadata_terms = reference_terms(source_layer, source_kind, class_name)
        if (protected_area_terms(text_terms) || protected_area_terms(metadata_terms)) {
            return true
        }
        return text_terms.endsWith(" park ") ||
                text_terms.contains(" parks ") ||
                text_terms.contains(" wildlife ") ||
                text_terms.contains(" parkway ") ||
                text_terms.contains(" gameland ") ||
                text_terms.contains(" gamelands ") ||
                metadata_terms.contains(" park ") ||
                metadata_terms.contains(" parks ") ||
                metadata_terms.contains(" wildlife ") ||
                metadata_terms.contains(" parkway ") ||
                metadata_terms.contains(" forest ") ||
                metadata_terms.contains(" forests ") ||
                metadata_terms.contains(" gameland ") ||
                metadata_terms.contains(" gamelands ")
    }

    private fun protected_area_line(
        source_layer: String,
        source_kind: String,
        class_name: String
    ): Boolean {
        if (source_kind == "water" || source_kind == "county") return false
        val terms = reference_terms(source_layer, source_kind, class_name)
        return protected_area_terms(terms) ||
                terms.contains(" park ") ||
                terms.contains(" parks ") ||
                terms.contains(" wildlife ") ||
                terms.contains(" parkway ") ||
                terms.contains(" forest ") ||
                terms.contains(" forests ") ||
                terms.contains(" gameland ") ||
                terms.contains(" gamelands ")
    }

    private fun protected_area_terms(terms: String): Boolean {
        return terms.contains(" wildlife refuge ") ||
                terms.contains(" national wildlife ") ||
                terms.contains(" wildlife area ") ||
                terms.contains(" wildlife areas ") ||
                terms.contains(" wildlife sanctuary ") ||
                terms.contains(" wildlife sanctuaries ") ||
                terms.contains(" refuge ") ||
                terms.contains(" national park ") ||
                terms.contains(" national parks ") ||
                terms.contains(" national capital parks ") ||
                terms.contains(" state park ") ||
                terms.contains(" county park ") ||
                terms.contains(" regional park ") ||
                terms.contains(" military park ") ||
                terms.contains(" memorial parkway ") ||
                terms.contains(" national forest ") ||
                terms.contains(" state forest ") ||
                terms.contains(" forest preserve ") ||
                terms.contains(" nature preserve ") ||
                terms.contains(" preserve ") ||
                terms.contains(" wilderness ") ||
                terms.contains(" national monument ") ||
                terms.contains(" national battlefield ") ||
                terms.contains(" national historic ") ||
                terms.contains(" national seashore ") ||
                terms.contains(" national seashores ") ||
                terms.contains(" recreation area ") ||
                terms.contains(" management area ") ||
                terms.contains(" conservation ") ||
                terms.contains(" natural area ") ||
                terms.contains(" game land ") ||
                terms.contains(" game lands ") ||
                terms.contains(" gameland ") ||
                terms.contains(" gamelands ") ||
                terms.contains(" state gameland ") ||
                terms.contains(" state gamelands ") ||
                terms.contains(" game refuge ") ||
                terms.contains(" wildlife management ")
    }

    private fun reference_terms(vararg values: String): String {
        val compact = REFERENCE_TOKEN_SEPARATOR
            .replace(values.joinToString(" ").lowercase(), " ")
            .trim()
        return if (compact.isEmpty()) " " else " $compact "
    }

    private fun JSONObject.layer_group_or_null(): ReferenceDictionaryLayerGroup? {
        val raw = optString("layerGroup")
            .ifBlank { optString("group") }
            .ifBlank { optString("g") }
            .trim()
            .uppercase()
        return when (raw) {
            "PLACES", "PLACE" -> ReferenceDictionaryLayerGroup.PLACES
            "WATER", "HYDRO" -> ReferenceDictionaryLayerGroup.WATER
            "REGIONS", "REGION", "ADMIN", "BOUNDARIES", "BORDERS" ->
                ReferenceDictionaryLayerGroup.REGIONS
            "PUBLIC_LANDS", "PUBLICLANDS", "PARKS", "PARK_FOREST" ->
                ReferenceDictionaryLayerGroup.PUBLIC_LANDS
            "TRANSPORTATION", "TRANSPORT", "STREETS", "ROADS" ->
                ReferenceDictionaryLayerGroup.TRANSPORTATION
            "OTHER" -> ReferenceDictionaryLayerGroup.OTHER
            else -> null
        }
    }

    private fun source_kind_for_group(group: ReferenceDictionaryLayerGroup?): String? {
        return when (group) {
            ReferenceDictionaryLayerGroup.PLACES -> "place"
            ReferenceDictionaryLayerGroup.WATER -> "water"
            ReferenceDictionaryLayerGroup.REGIONS -> "admin"
            ReferenceDictionaryLayerGroup.PUBLIC_LANDS -> "place"
            ReferenceDictionaryLayerGroup.TRANSPORTATION -> "transportation"
            ReferenceDictionaryLayerGroup.OTHER,
            null -> null
        }
    }

    private fun label_layer_group(
        source_kind: String,
        source_layer: String,
        protected_area: Boolean
    ): ReferenceDictionaryLayerGroup {
        if (protected_area) return ReferenceDictionaryLayerGroup.PUBLIC_LANDS
        return when {
            source_kind == "water" -> ReferenceDictionaryLayerGroup.WATER
            source_kind == "admin" || source_kind == "county" -> ReferenceDictionaryLayerGroup.REGIONS
            source_kind == "place" -> ReferenceDictionaryLayerGroup.PLACES
            source_layer.contains("City", ignoreCase = true) -> ReferenceDictionaryLayerGroup.PLACES
            source_layer.contains("Admin", ignoreCase = true) -> ReferenceDictionaryLayerGroup.REGIONS
            else -> ReferenceDictionaryLayerGroup.PLACES
        }
    }

    private fun line_layer_group(
        source_layer: String,
        source_kind: String,
        class_name: String,
        protected_area: Boolean
    ): ReferenceDictionaryLayerGroup {
        if (protected_area) return ReferenceDictionaryLayerGroup.PUBLIC_LANDS
        if (water_line_feature(source_layer, source_kind, class_name)) {
            return ReferenceDictionaryLayerGroup.WATER
        }
        return when (source_kind) {
            "water" -> ReferenceDictionaryLayerGroup.WATER
            "admin", "county" -> ReferenceDictionaryLayerGroup.REGIONS
            else -> ReferenceDictionaryLayerGroup.REGIONS
        }
    }

    private fun water_line_feature(
        source_layer: String,
        source_kind: String,
        class_name: String
    ): Boolean {
        if (source_kind == "water") return true
        val terms = reference_terms(source_layer, source_kind, class_name)
        return terms.contains(" water ") ||
                terms.contains(" waterbody ") ||
                terms.contains(" water bodies ") ||
                terms.contains(" ocean ") ||
                terms.contains(" sea ") ||
                terms.contains(" bay ") ||
                terms.contains(" lake ") ||
                terms.contains(" river ") ||
                terms.contains(" stream ") ||
                terms.contains(" coastline ") ||
                terms.contains(" shoreline ") ||
                terms.contains(" marine ") ||
                terms.contains(" hydro ")
    }

    private fun line_style_for(
        source_layer: String,
        source_kind: String,
        class_name: String,
        layer_group: ReferenceDictionaryLayerGroup
    ): DictionaryLineStyle {
        val source = source_layer.lowercase()
        val normalized_class = class_name.lowercase()
        return when {
            layer_group == ReferenceDictionaryLayerGroup.WATER ||
                    water_line_feature(source_layer, source_kind, class_name) -> DictionaryLineStyle(
                color = Color.rgb(166, 214, 236),
                stroke_width_dp = 0.58f,
                halo_width_dp = 0.82f,
                alpha = 112,
                halo_alpha = 56
            )

            source.contains("admin0") || normalized_class.contains("country") -> DictionaryLineStyle(
                color = Color.WHITE,
                stroke_width_dp = 1.65f,
                halo_width_dp = 2.25f,
                alpha = 202,
                halo_alpha = 148
            )

            source.contains("admin1") || normalized_class.contains("state") -> DictionaryLineStyle(
                color = Color.rgb(250, 253, 255),
                stroke_width_dp = 1.3f,
                halo_width_dp = 1.95f,
                alpha = 184,
                halo_alpha = 126
            )

            source.contains("admin2") || source_kind == "county" -> DictionaryLineStyle(
                color = Color.rgb(238, 246, 252),
                stroke_width_dp = 0.82f,
                halo_width_dp = 1.35f,
                alpha = 124,
                halo_alpha = 82
            )

            source.contains("forest") || source.contains("park") -> DictionaryLineStyle(
                color = Color.rgb(145, 220, 156),
                stroke_width_dp = 0.62f,
                halo_width_dp = 1.05f,
                alpha = 82,
                halo_alpha = 58
            )

            else -> DictionaryLineStyle(
                color = Color.rgb(232, 244, 255),
                stroke_width_dp = 0.8f,
                halo_width_dp = 1.25f,
                alpha = 112,
                halo_alpha = 74
            )
        }
    }

    private fun label_rotation(bounds: DictionaryBounds): Float {
        val dx = bounds.max_x - bounds.min_x
        val dy = bounds.max_y - bounds.min_y
        if (dx < 40f || dy < 40f) return 0f
        return (atan2(dy, dx) * 180f / Math.PI.toFloat() - 12f).coerceIn(-24f, 24f)
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

    private fun dictionary_tile_zoom(viewport_zoom: Double, zooms: Set<Int>): Int? {
        return ReferenceDictionaryLodPolicy.select(viewport_zoom, zooms)
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

    private fun JSONObject.optional_int(key: String): Int? {
        if (isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }?.roundToInt()
    }

    private fun JSONObject.optional_double(key: String): Double? {
        if (isNull(key)) return null
        return optDouble(key).takeIf { it.isFinite() }
    }

    private fun JSONObject.optional_boolean(key: String): Boolean? {
        if (!has(key) || isNull(key)) return null
        return optBoolean(key)
    }

    private data class VisibleDictionaryTile(
        val z: Int,
        val x: Int,
        val draw_x: Int,
        val y: Int,
        val core_visible: Boolean,
        val request_priority: Int,
        val outline_visibility_centizoom: Int? = null,
    ) {
        fun cache_key(): String {
            val visibility = outline_visibility_centizoom ?: return "$z/$x/$y"
            return "$z/$x/$y@outline-$visibility"
        }

        fun low_zoom_profile(): Boolean =
            outline_visibility_centizoom == ReferenceDictionaryLodPolicy.LOW_ZOOM_OUTLINE_PROFILE
    }

    private data class DictionaryTileDrawRef(
        val tile: ParsedDictionaryTile,
        val draw_x: Int,
        val core_visible: Boolean,
    )

    private data class DictionaryLineRecordRef(
        val tile: DictionaryTileDrawRef,
        val record: DictionaryLineRecord,
    )

    private data class BoundaryPathBatchKey(
        val draw_order: Int,
        val priority: Int,
        val style: DictionaryLineStyle,
        val visibility_rule: OutlineVisibilityRule?,
    )

    private class BoundaryPathBatch(
        val path: Path,
        var style: DictionaryLineStyle,
        var visibility_rule: OutlineVisibilityRule?,
        var draw_order: Int = 0,
        var priority: Int = 0,
        var first_source_feature_id: ULong = 0uL,
        var first_source_order: Int = 0,
        var boundaries: Int = 0,
    )

    private class BoundaryRasterWorkspace {
        val occurrence_ids = ReferenceBoundaryOccurrenceSet()
        val batches = ArrayList<BoundaryPathBatch>(8)
        val clipped_segment = FloatArray(4)
        val paint = Paint()
        private val batch_pool = ArrayList<BoundaryPathBatch>(8)

        fun reset() {
            occurrence_ids.clear()
            batches.clear()
        }

        fun batch_for(record: DictionaryLineRecord, source_order: Int): BoundaryPathBatch {
            for (batch in batches) {
                if (batch.draw_order == record.draw_order &&
                    batch.priority == record.priority &&
                    batch.style == record.style &&
                    batch.visibility_rule == record.visibility_rule
                ) {
                    if (record.source_feature_id < batch.first_source_feature_id) {
                        batch.first_source_feature_id = record.source_feature_id
                        batch.first_source_order = source_order
                    }
                    return batch
                }
            }

            val batch_index = batches.size
            val batch = if (batch_index < batch_pool.size) {
                batch_pool[batch_index]
            } else {
                BoundaryPathBatch(Path(), record.style, record.visibility_rule).also {
                    batch_pool += it
                }
            }
            batch.path.rewind()
            batch.style = record.style
            batch.visibility_rule = record.visibility_rule
            batch.draw_order = record.draw_order
            batch.priority = record.priority
            batch.first_source_feature_id = record.source_feature_id
            batch.first_source_order = source_order
            batch.boundaries = 0
            batches += batch
            return batch
        }
    }

    private data class BoundaryRasterRequest(
        val key: ReferenceBoundaryRasterKey,
        val source: ReferenceBoundaryRasterSource,
        val tiles: List<BoundaryRasterSourceTile>,
        val groups: ReferenceDictionaryLayerGroups,
        val filter_mask: ReferenceFilterMask,
    )

    private data class BoundaryRasterSourceTile(
        val tile: ParsedDictionaryTile,
        val drawX: Int,
        val y: Int,
    )

    private data class PreparedBoundaryRaster(
        val key: ReferenceBoundaryRasterKey,
        val tile: BoundaryRasterTile?,
    )

    private data class BoundaryRasterScene(
        val band: ReferenceBoundaryRasterBand,
        val bounds: ReferenceBoundaryRasterWindowBounds,
        val keys: Set<ReferenceBoundaryRasterKey>,
    ) {
        fun matches_environment(settingsKey: Long, packageGeneration: Long): Boolean {
            return band.settingsKey == settingsKey &&
                band.packageGeneration == packageGeneration
        }

        fun covers(viewport: Viewport): Boolean {
            return reference_boundary_raster_window_covers(
                bounds = bounds,
                viewportZoom = viewport.zoom,
                centerX = viewport.center_x,
                centerY = viewport.center_y,
                viewportWidth = viewport.width,
                viewportHeight = viewport.height,
            )
        }
    }

    private data class BoundaryRasterFade(
        val previous: BoundaryRasterScene,
        val currentBand: ReferenceBoundaryRasterBand,
        val startMs: Long,
    )

    private data class BoundaryRasterTile(
        val key: ReferenceBoundaryRasterKey,
        val bitmap: Bitmap,
        val gutterPixels: Int,
        val boundaries: Int,
        val byteCount: Long,
    )

    private class BoundaryRasterCellWindow {
        private var raster_zoom = Int.MIN_VALUE
        private var padding_cells = 0
        private var core_first_x = 0
        private var core_first_y = 0
        private var core_last_x = 0
        private var core_last_y = 0
        private var center_x = 0
        private var center_y = 0
        private var key_band: ReferenceBoundaryRasterBand? = null
        private var key_revision = -1
        private val keys = ArrayList<ReferenceBoundaryRasterKey>()
        private var bounds_revision = -1
        private var cached_bounds: ReferenceBoundaryRasterWindowBounds? = null
        private var core_bounds_revision = -1
        private var cached_core_bounds: ReferenceBoundaryRasterWindowBounds? = null
        var revision = 0
            private set

        fun matches(
            next_raster_zoom: Int,
            next_padding_cells: Int,
            next_core_first_x: Int,
            next_core_first_y: Int,
            next_core_last_x: Int,
            next_core_last_y: Int,
            next_center_x: Int,
            next_center_y: Int,
        ): Boolean {
            return raster_zoom == next_raster_zoom &&
                padding_cells == next_padding_cells &&
                core_first_x == next_core_first_x &&
                core_first_y == next_core_first_y &&
                core_last_x == next_core_last_x &&
                core_last_y == next_core_last_y &&
                center_x == next_center_x &&
                center_y == next_center_y
        }

        fun update(
            next_raster_zoom: Int,
            next_padding_cells: Int,
            next_core_first_x: Int,
            next_core_first_y: Int,
            next_core_last_x: Int,
            next_core_last_y: Int,
            next_center_x: Int,
            next_center_y: Int,
        ) {
            raster_zoom = next_raster_zoom
            padding_cells = next_padding_cells
            core_first_x = next_core_first_x
            core_first_y = next_core_first_y
            core_last_x = next_core_last_x
            core_last_y = next_core_last_y
            center_x = next_center_x
            center_y = next_center_y
            revision++
        }

        fun keys_for(
            band: ReferenceBoundaryRasterBand,
            cells: List<ReferenceBoundaryRasterCell>,
        ): List<ReferenceBoundaryRasterKey> {
            if (key_band == band && key_revision == revision) return keys
            keys.clear()
            for (cell in cells) {
                keys += ReferenceBoundaryRasterKey(band, cell.x, cell.y)
            }
            key_band = band
            key_revision = revision
            return keys
        }

        fun bounds(): ReferenceBoundaryRasterWindowBounds {
            if (bounds_revision == revision) return requireNotNull(cached_bounds)
            val tile_limit = 1 shl raster_zoom
            val bounds = ReferenceBoundaryRasterWindowBounds(
                rasterZoom = raster_zoom,
                firstDrawX = core_first_x - padding_cells,
                lastDrawX = core_last_x + padding_cells,
                firstY = (core_first_y - padding_cells).coerceAtLeast(0),
                lastY = (core_last_y + padding_cells).coerceAtMost(tile_limit - 1),
            )
            cached_bounds = bounds
            bounds_revision = revision
            return bounds
        }

        fun core_bounds(): ReferenceBoundaryRasterWindowBounds {
            if (core_bounds_revision == revision) return requireNotNull(cached_core_bounds)
            val tile_limit = 1 shl raster_zoom
            val bounds = ReferenceBoundaryRasterWindowBounds(
                rasterZoom = raster_zoom,
                firstDrawX = core_first_x,
                lastDrawX = core_last_x,
                firstY = core_first_y.coerceAtLeast(0),
                lastY = core_last_y.coerceAtMost(tile_limit - 1),
            )
            cached_core_bounds = bounds
            core_bounds_revision = revision
            return bounds
        }

        fun invalidate() {
            if (raster_zoom != Int.MIN_VALUE) {
                raster_zoom = Int.MIN_VALUE
                revision++
            }
            key_band = null
            key_revision = -1
            keys.clear()
        }
    }

    private data class BoundaryRasterDrawResult(
        val boundaries: Int,
        val ready: Boolean,
        val requested: Int,
    )

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

    private enum class BoundaryPaintPass { HALO, STROKE }

    private class LabelPlanningWorkspace(
        val textPaint: Paint,
    ) {
        val typefaceCache = HashMap<Int, Typeface>(8)
        val recordRefs = ArrayList<DictionaryLabelRecordRef>(512)
        val candidates = ArrayList<DictionaryLabelCandidate>(256)
        val plannedCandidateIds = HashSet<ReferenceLabelCandidateKey>(256)
        val acceptedLabels = ArrayList<DictionaryLabelCandidate>(128)
    }

    private data class DictionaryLabelRecordRef(
        val tile: DictionaryTileDrawRef,
        val record: DictionaryLabelRecord,
        val layout_priority: Int,
        val layout_feature_id: ULong,
        val encounter_order: Int,
    )

    private data class ParsedDictionaryTile(
        val coordinate: ReferenceDictionaryTileCoordinate,
        val boundaries: List<DictionaryLineRecord>,
        val labels: List<DictionaryLabelRecord>
    )

    private data class PackageSnapshot(
        val info: ReferenceDictionaryPackageInfo,
        val catalog: ReferenceClassCatalog,
    )

    private inner class PackageProbe(
        val generation: Long,
    ) : Runnable {
        override fun run() {
            probe_package(this)
        }
    }

    private data class DictionaryLineRecord(
        val source_layer: String,
        val source_kind: String,
        val class_name: String,
        val protected_area: Boolean,
        val layer_group: ReferenceDictionaryLayerGroup,
        val filter_id: FilterId? = null,
        val geometry: DictionaryGeometry,
        val visibility_rule: OutlineVisibilityRule? = null,
        val draw_order: Int = 0,
        val priority: Int = 0,
        val source_feature_id: ULong = 0uL,
        val dedupe_id: ULong? = null,
        val posting_world_wrap: Int = 0,
        val style: DictionaryLineStyle
    )

    private data class DictionaryLabelRecord(
        val text: String,
        val source_layer: String,
        val source_kind: String,
        val line_label: Boolean,
        val drawable: Boolean,
        val protected_area: Boolean,
        val layer_group: ReferenceDictionaryLayerGroup,
        val filter_id: FilterId? = null,
        val priority: Int,
        val color: Int,
        val base_text_size_sp: Float,
        val alpha: Int,
        val halo_color: Int = Color.rgb(7, 20, 25),
        val halo_width_dp: Float,
        val halo_alpha: Int,
        val typeface_style: Int,
        val letter_spacing_em: Float = 0f,
        val rotation: Float,
        val rank: Int?,
        val min_zoom: Double?,
        val max_zoom: Double?,
        val anchor: DictionaryPoint?,
        val geometry: DictionaryGeometry?,
        val sourced_text: SourcedMapText? = null,
        val dedupe_key: String? = null,
        val is_water: Boolean? = null,
        val candidate_id: ULong? = null,
        val source_feature_id: ULong? = null,
        val presentation_subtype: SemanticSubtype? = null,
        val prominence_tier: ProminenceTier? = null,
        val complete_geometry_measure_bucket: Int = 0,
        val low_zoom_country_rank: Int = UNKNOWN_LOW_ZOOM_COUNTRY_RANK,
        val runtime_typography: ReferenceLabelRuntimeTypography? = null,
        val visibility_rule: LabelVisibilityRule? = null,
        val repeat_spacing_px: Int? = null,
        val posting_world_wrap: Int = 0,
    ) {
        fun visible_at(zoom: Double): Boolean {
            if (min_zoom != null && zoom + 0.01 < min_zoom) return false
            if (max_zoom != null && zoom - 0.01 > max_zoom) return false
            return true
        }
    }

    private data class DictionaryGeometry(
        val rings: List<DictionaryRing>,
        val bounds: DictionaryBounds?
    )

    private data class DictionaryRing(
        val points: FloatArray,
        val point_count: Int
    )

    private data class DictionaryBounds(
        val min_x: Float,
        val min_y: Float,
        val max_x: Float,
        val max_y: Float
    )

    private data class DictionaryPoint(
        val x: Float,
        val y: Float
    )

    private data class DictionaryScreenPoint(
        val x: Float,
        val y: Float
    )

    private data class DictionaryLineStyle(
        val color: Int,
        val stroke_width_dp: Float,
        val halo_width_dp: Float,
        val alpha: Int,
        val halo_alpha: Int,
        val halo_color: Int = Color.rgb(4, 10, 14),
        val dash_pattern_dp: List<Float> = emptyList(),
        val dash_phase_dp: Float = 0f,
        val stroke_cap: Paint.Cap = Paint.Cap.ROUND,
        val stroke_join: Paint.Join = Paint.Join.ROUND,
    )

    private data class DictionaryLabelStyle(
        val color: Int,
        val text_size: Float,
        val alpha: Int,
        val halo_color: Int,
        val halo_width_dp: Float,
        val halo_width_milli_em: Int,
        val halo_alpha: Int,
        val font_weight: Int,
        val italic: Boolean,
        val letter_spacing_em: Float,
    )

    private data class DictionaryLabelStyleTemplate(
        val color: Int,
        val text_size_sp: Float,
        val alpha: Int,
        val halo_width_dp: Float,
        val halo_alpha: Int,
        val typeface_style: Int
    )

    private data class DictionaryLabelCandidate(
        val text: String,
        val source_kind: String,
        val presentation: SourcedMapTextPresentationPlan?,
        val style: DictionaryLabelStyle,
        override val occurrenceId: ReferenceLabelOccurrenceId,
        override val featureId: ULong,
        override val priority: Int,
        override val placementRank: Int,
        override val protectedArea: Boolean,
        override val waterLine: Boolean,
        override val anchor: ReferencePathLabelPoint,
        override val collisionShape: ReferenceLabelCollisionShape,
        val rotation: Float = 0f,
        val path_points: List<ReferencePathLabelPoint>? = null,
    ) : ReferenceLabelLayoutCandidate {
        val x: Float get() = anchor.x.toFloat()
        val y: Float get() = anchor.y.toFloat()
    }

    private data class ReferenceDrawCounts(
        val boundaries: Int,
        val labels: Int
    )

    private data class RetainedReferenceContent(
        val boundaries: Int,
        val labels: List<DictionaryLabelCandidate>,
    )

    private enum class ReferenceDictionaryLayerGroup {
        PLACES,
        WATER,
        REGIONS,
        PUBLIC_LANDS,
        TRANSPORTATION,
        OTHER
    }

    private data class ReferenceDictionaryLayerGroups(
        val places: Boolean,
        val water: Boolean,
        val regions: Boolean,
        val public_lands: Boolean
    ) {
        val cache_suffix: String =
            "${if (places) 'p' else '-'}${if (water) 'w' else '-'}" +
                    "${if (regions) 'r' else '-'}${if (public_lands) 'l' else '-'}"

        fun enabled(group: ReferenceDictionaryLayerGroup): Boolean {
            return when (group) {
                ReferenceDictionaryLayerGroup.PLACES -> places
                ReferenceDictionaryLayerGroup.WATER -> water
                ReferenceDictionaryLayerGroup.REGIONS -> regions
                ReferenceDictionaryLayerGroup.PUBLIC_LANDS -> public_lands
                ReferenceDictionaryLayerGroup.TRANSPORTATION,
                ReferenceDictionaryLayerGroup.OTHER -> false
            }
        }
    }

    private data class RetainedReferenceOptions(
        val labels_enabled: Boolean,
        val borders_enabled: Boolean,
        val place_labels_enabled: Boolean,
        val water_labels_enabled: Boolean,
        val region_labels_enabled: Boolean,
        val public_lands_enabled: Boolean,
        val filter_mask_key: Int,
        val label_text_scale_key: Int
    )

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
