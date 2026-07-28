@file:Suppress("FunctionName", "LocalVariableName", "PropertyName", "ArrayInDataClass")

package com.flightalert.map.reference.borders

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withSave
import com.flightalert.MIN_ZOOM
import com.flightalert.map.*
import com.flightalert.map.reference.*
import com.flightalert.map.reference.dictionary.*
import com.flightalert.map.tiles.MapTileDiskCache
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.*

internal class ReferenceBoundaryRenderer(
    context: Context,
    private val map_tile_disk_cache: MapTileDiskCache,
    private val paint: Paint,
    private val boundary_density: Float,
    private val request_redraw: () -> Unit,
    private val tile_repository: ReferenceTileRepository,
    private val retained_scene_padding_px: (Viewport, Int) -> Int,
) : Closeable {
    private val boundary_raster_disk_root = File(
        context.cacheDir,
        REFERENCE_BOUNDARY_RASTER_CACHE_ROOT,
    )
    private val boundary_record_refs = ArrayList<DictionaryLineRecordRef>(256)
    private val boundary_occurrence_ids = HashSet<ReferenceBoundaryOccurrenceKey>(256)
    private val boundary_raster_cells = ArrayList<ReferenceBoundaryRasterCell>(40)
    private val boundary_raster_draw_cells = ArrayList<ReferenceBoundaryRasterCell>(40)
    private val boundary_raster_cell_window = BoundaryRasterCellWindow()
    private val boundary_raster_draw_cell_window = BoundaryRasterCellWindow()
    private val boundary_raster_destination = RectF()
    private val boundary_raster_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    @Volatile
    private var closed = false
    private val lifecycle_lock = ReentrantLock()
    private val worker_id = AtomicInteger()
    private val boundary_raster_task_sequence = AtomicLong()
    private val boundary_raster_admission_lock = Any()
    private val boundary_raster_executor = ThreadPoolExecutor(
        BOUNDARY_RASTER_WORKERS,
        BOUNDARY_RASTER_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
    ) { runnable ->
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
    private val boundary_raster_requests =
        ConcurrentHashMap.newKeySet<ReferenceBoundaryRasterKey>()
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
    private var relevant_boundary_raster_safety_keys: Set<ReferenceBoundaryRasterKey> = emptySet()

    fun has_compatible_active_band(
        groups: ReferenceDictionaryLayerGroups,
        filterMask: ReferenceFilterMask,
        packageGeneration: Long,
    ): Boolean = reference_boundary_raster_band_is_compatible(
        active = active_boundary_raster_band,
        settingsKey = boundary_raster_settings_key(groups, filterMask),
        packageGeneration = packageGeneration,
    )

    fun draw_records(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        groups: ReferenceDictionaryLayerGroups,
        filterMask: ReferenceFilterMask,
    ): Int {
        collect_boundary_record_refs(viewport, tiles, groups, filterMask)
        val drawn = draw_boundary_records(canvas, viewport, boundary_record_refs)
        boundary_record_refs.clear()
        return drawn
    }

    fun prepare_batches(
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        groups: ReferenceDictionaryLayerGroups,
        filterMask: ReferenceFilterMask,
        keepDrawing: () -> Boolean,
        includeHidden: Boolean,
    ): List<BoundaryPathBatch> {
        val records = ArrayList<DictionaryLineRecordRef>(256)
        collect_boundary_record_refs(
            viewport = viewport,
            tiles = tiles,
            groups = groups,
            filter_mask = filterMask,
            output = records,
            occurrence_ids = HashSet(256),
            include_hidden = includeHidden,
        )
        if (!keepDrawing()) return emptyList()
        return prepare_boundary_batches(viewport, records, keepDrawing)
    }

    fun draw_batches(
        canvas: Canvas,
        viewport_zoom: Double,
        batches: List<BoundaryPathBatch>,
        draw_scale: Float = 1f,
        frame_alpha: Int = 255,
    ): Int = draw_boundary_batches(
        canvas = canvas,
        viewport_zoom = viewport_zoom,
        batches = batches,
        draw_scale = draw_scale,
        frame_alpha = frame_alpha,
    )

    override fun close() {
        lifecycle_lock.lock()
        try {
            if (closed) return
            closed = true
            boundary_raster_executor.shutdownNow().forEach { task ->
                (task as? BoundaryRasterTask)?.discard()
            }
            clear()
        } finally {
            lifecycle_lock.unlock()
        }
    }

    fun draw(
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
                packageGeneration = tile_repository.package_generation(),
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
                retainedPaddingPx = retained_scene_padding_px(viewport, source_zoom),
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
            purge_obsolete_boundary_raster_tasks()
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
                core_visible = cell.coreVisible,
                request_priority = cell.requestPriority,
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
                packageGeneration = tile_repository.package_generation(),
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
                    core_visible = cell.coreVisible,
                    request_priority = cell.requestPriority,
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
        val current_package_generation = tile_repository.package_generation()
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
        val snapshot = tile_repository.snapshot() ?: return 0
        val safety_zoom = reference_boundary_raster_safety_zoom(viewport.zoom, MIN_ZOOM)
        val safety_source_zoom = dictionary_tile_zoom(
            safety_zoom.toDouble(),
            snapshot.info.zooms,
        ) ?: return 0
        val current_package_generation = tile_repository.package_generation()
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
        for ((index, key) in pending.keys.withIndex()) {
            if (boundary_raster_cache.containsKey(key) ||
                boundary_raster_requests.contains(key)
            ) {
                continue
            }
            val cell = boundary_raster_safety_cells[index]
            requested += request_boundary_raster(
                key = key,
                groups = groups,
                filter_mask = filterMask,
                tile_request_generation = tileRequestGeneration,
                core_visible = false,
                request_priority = cell.requestPriority,
                continuity_request = true,
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
            purge_obsolete_boundary_raster_tasks()
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
        purge_obsolete_boundary_raster_tasks()
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
        core_visible: Boolean,
        request_priority: Int,
        continuity_request: Boolean = false,
    ): Int {
        if (boundary_raster_executor.isShutdown ||
            boundary_raster_cache.containsKey(key) ||
            boundary_raster_requests.contains(key)
        ) {
            return 0
        }
        val disk_file = boundary_raster_disk_file(key)
        if (disk_file != null && key !in boundary_raster_disk_misses) {
            if (!boundary_raster_requests.add(key)) return 0
            val task = BoundaryRasterTask(
                key = key,
                core_visible = core_visible,
                request_priority = request_priority,
                continuity_request = continuity_request,
                sequence = boundary_raster_task_sequence.incrementAndGet(),
                action = {
                    val should_read = boundary_raster_request_is_relevant(key)
                    val tile = if (should_read) {
                        read_boundary_raster_from_disk(key, disk_file)
                    } else {
                        null
                    }
                    val prepared = PreparedBoundaryRaster(key, tile)
                    publish_boundary_raster_completion(
                        prepared = prepared,
                        disk_file_missed = should_read && tile == null,
                    )
                },
            )
            return if (submit_boundary_raster_task(task)) 1 else 0
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
                        core_visible = core_visible && !continuity_request,
                        request_priority = request_priority,
                        outline_visibility_centizoom =
                            ReferenceDictionaryLodPolicy.outline_decode_profile(
                                key.band.presentationCentizoom,
                                key.band.sourceZoom,
                            ),
                    )
                    val parsed = tile_repository.tile(source_tile)
                    if (parsed != null) {
                        source_tiles += BoundaryRasterSourceTile(parsed, raw_x, raw_y)
                        continue
                    }
                    missing_source = true
                    source_requests += tile_repository.request_tile_if_needed(
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
        val task = BoundaryRasterTask(
            key = key,
            core_visible = core_visible,
            request_priority = request_priority,
            continuity_request = continuity_request,
            sequence = boundary_raster_task_sequence.incrementAndGet(),
            action = {
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
            },
        )
        return if (submit_boundary_raster_task(task)) 1 else 0
    }

    private fun submit_boundary_raster_task(task: BoundaryRasterTask): Boolean {
        val discarded = ArrayList<BoundaryRasterTask>()
        var submitted = false
        synchronized(boundary_raster_admission_lock) {
            purge_obsolete_boundary_raster_tasks_locked(discarded)
            if (!closed && !boundary_raster_executor.isShutdown && task.is_relevant()) {
                val queue = boundary_raster_executor.queue
                if (queue.size >= MAX_PENDING_BOUNDARY_RASTER_REQUESTS) {
                    var worst: BoundaryRasterTask? = null
                    for (queued in queue) {
                        val candidate = queued as? BoundaryRasterTask ?: continue
                        if (worst == null || candidate.compareTo(worst) > 0) {
                            worst = candidate
                        }
                    }
                    if (worst != null &&
                        task.compareTo(worst) < 0 &&
                        queue.remove(worst)
                    ) {
                        discarded += worst
                    }
                }
                if (queue.size < MAX_PENDING_BOUNDARY_RASTER_REQUESTS) {
                    try {
                        boundary_raster_executor.execute(task)
                        submitted = true
                    } catch (_: RejectedExecutionException) {
                        // Cleanup below releases rejected work.
                    }
                }
            }
        }
        discarded.forEach(BoundaryRasterTask::discard)
        if (!submitted) task.discard()
        return submitted
    }

    private fun purge_obsolete_boundary_raster_tasks() {
        val discarded = ArrayList<BoundaryRasterTask>()
        synchronized(boundary_raster_admission_lock) {
            purge_obsolete_boundary_raster_tasks_locked(discarded)
        }
        discarded.forEach(BoundaryRasterTask::discard)
    }

    private fun purge_obsolete_boundary_raster_tasks_locked(
        discarded: MutableList<BoundaryRasterTask>,
    ) {
        val queue = boundary_raster_executor.queue
        for (queued in queue) {
            val task = queued as? BoundaryRasterTask ?: continue
            if (!task.is_relevant() && queue.remove(task)) discarded += task
        }
    }

    private fun discard_queued_boundary_raster_tasks() {
        val discarded = ArrayList<BoundaryRasterTask>()
        synchronized(boundary_raster_admission_lock) {
            val queue = boundary_raster_executor.queue
            for (queued in queue) {
                val task = queued as? BoundaryRasterTask ?: continue
                if (queue.remove(task)) discarded += task
            }
        }
        discarded.forEach(BoundaryRasterTask::discard)
    }

    private fun boundary_raster_disk_file(key: ReferenceBoundaryRasterKey): File? {
        val band = key.band
        if (!reference_boundary_raster_disk_cacheable(band)) return null
        val snapshot = tile_repository.snapshot() ?: return null
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
        lifecycle_lock.lock()
        try {
            if (!reference_boundary_raster_completion_is_current(
                    requestPackageGeneration = prepared.key.band.packageGeneration,
                    currentPackageGeneration = tile_repository.package_generation(),
                    rendererClosed = closed,
                )
            ) {
                prepared.tile?.bitmap?.recycle()
                boundary_raster_requests.remove(prepared.key)
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
            lifecycle_lock.unlock()
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
                prepared.key.band.packageGeneration != tile_repository.package_generation() ||
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

    fun clear() {
        active_boundary_raster_band = null
        pending_boundary_raster_band = null
        relevant_boundary_raster_keys = emptySet()
        pending_boundary_raster_safety_scene = null
        ready_boundary_raster_safety_scene = null
        settled_boundary_raster_scene = null
        resident_boundary_raster_scene = null
        boundary_raster_fade = null
        relevant_boundary_raster_safety_keys = emptySet()
        discard_queued_boundary_raster_tasks()
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

    private inner class BoundaryRasterTask(
        private val key: ReferenceBoundaryRasterKey,
        private val core_visible: Boolean,
        private val request_priority: Int,
        private val continuity_request: Boolean,
        private val sequence: Long,
        private val action: () -> Unit,
    ) : Runnable, Comparable<Runnable> {
        override fun run() {
            try {
                action()
            } catch (_: Exception) {
                publish_boundary_raster_completion(PreparedBoundaryRaster(key, null))
            }
        }

        fun is_relevant(): Boolean =
            !closed && boundary_raster_request_is_relevant(key)

        fun discard() {
            boundary_raster_requests.remove(key)
        }

        override fun compareTo(other: Runnable): Int {
            val task = other as? BoundaryRasterTask ?: return -1
            if (continuity_request != task.continuity_request) {
                return if (continuity_request) 1 else -1
            }
            if (core_visible != task.core_visible) {
                return if (core_visible) -1 else 1
            }
            if (request_priority != task.request_priority) {
                return request_priority.compareTo(task.request_priority)
            }
            return sequence.compareTo(task.sequence)
        }
    }

    private companion object {
        const val MAP_TILE_SIZE = 256.0
        const val DICTIONARY_EXTENT = 4096.0
        const val BOUNDARY_RASTER_WORKERS = 2
        const val MAX_PENDING_BOUNDARY_RASTER_REQUESTS = 96
        const val BOUNDARY_RASTER_GUTTER_PX = 16
        const val BOUNDARY_RASTER_REQUESTS_PER_DRAW = 8
        const val BOUNDARY_RASTER_SAFETY_REQUESTS_PER_DRAW = 4
        const val BOUNDARY_RASTER_SAFETY_PADDING_CELLS = 1
        const val BOUNDARY_RASTER_CACHE_BYTES = 128L * 1024L * 1024L
        const val BOUNDARY_RASTER_RESIDENT_CACHE_BYTES = 32L * 1024L * 1024L
        const val BOUNDARY_RASTER_FADE_MS = 220L
        const val MIN_BOUNDARY_DRAW_SCALE = 0.000_1f
    }
}

internal data class BoundaryPathBatchKey(
    val draw_order: Int,
    val priority: Int,
    val style: DictionaryLineStyle,
    val visibility_rule: OutlineVisibilityRule?,
)

internal class BoundaryPathBatch(
    val path: Path,
    var style: DictionaryLineStyle,
    var visibility_rule: OutlineVisibilityRule?,
    var draw_order: Int = 0,
    var priority: Int = 0,
    var first_source_feature_id: ULong = 0uL,
    var first_source_order: Int = 0,
    var boundaries: Int = 0,
)

internal class BoundaryRasterWorkspace {
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

internal data class BoundaryRasterRequest(
    val key: ReferenceBoundaryRasterKey,
    val source: ReferenceBoundaryRasterSource,
    val tiles: List<BoundaryRasterSourceTile>,
    val groups: ReferenceDictionaryLayerGroups,
    val filter_mask: ReferenceFilterMask,
)

internal data class BoundaryRasterSourceTile(
    val tile: ParsedDictionaryTile,
    val drawX: Int,
    val y: Int,
)

internal data class PreparedBoundaryRaster(
    val key: ReferenceBoundaryRasterKey,
    val tile: BoundaryRasterTile?,
)

internal data class BoundaryRasterScene(
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

internal data class BoundaryRasterFade(
    val previous: BoundaryRasterScene,
    val currentBand: ReferenceBoundaryRasterBand,
    val startMs: Long,
)

internal data class BoundaryRasterTile(
    val key: ReferenceBoundaryRasterKey,
    val bitmap: Bitmap,
    val gutterPixels: Int,
    val boundaries: Int,
    val byteCount: Long,
)

internal class BoundaryRasterCellWindow {
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

internal data class BoundaryRasterDrawResult(
    val boundaries: Int,
    val ready: Boolean,
    val requested: Int,
)


internal enum class BoundaryPaintPass { HALO, STROKE }
