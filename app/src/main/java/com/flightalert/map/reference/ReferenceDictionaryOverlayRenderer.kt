@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Process
import android.os.SystemClock
import com.flightalert.map.Viewport
import com.flightalert.map.cache.MapTileDiskCache
import com.flightalert.map.reference.borders.ReferenceBoundaryRenderer
import com.flightalert.map.reference.dictionary.DictionaryLabelCandidate
import com.flightalert.map.reference.dictionary.DictionaryTileDrawRef
import com.flightalert.map.reference.dictionary.FilterState
import com.flightalert.map.reference.dictionary.ReferenceClassCatalog
import com.flightalert.map.reference.dictionary.ReferenceDictionaryLayerGroups
import com.flightalert.map.reference.dictionary.LABEL_ONLY_OUTLINE_PROFILE
import com.flightalert.map.reference.dictionary.outline_decode_profile
import com.flightalert.map.reference.dictionary.select_fallback
import com.flightalert.map.reference.dictionary.ReferenceDictionaryRuntimeSchema
import com.flightalert.map.reference.dictionary.ReferenceFilterMask
import com.flightalert.map.reference.dictionary.ReferenceTileRepository
import com.flightalert.map.reference.dictionary.RetainedReferenceOptions
import com.flightalert.map.reference.dictionary.VisibleDictionaryTile
import com.flightalert.map.reference.dictionary.dictionary_tile_zoom
import com.flightalert.map.reference.dictionary.tile_request_priority
import com.flightalert.map.reference.labels.ALWAYS_KEEP_LABEL_PLAN
import com.flightalert.map.reference.labels.LabelPlanningWorkspace
import com.flightalert.map.reference.labels.ReferenceLabelCandidateKey
import com.flightalert.map.reference.labels.ReferenceLabelCollisionShape
import com.flightalert.map.reference.labels.ReferenceLabelOccurrenceId
import com.flightalert.map.reference.labels.ReferenceLabelRenderer
import com.flightalert.map.reference.labels.ReferenceLabelRuntimePresentationPolicy
import com.flightalert.map.reference.labels.ReferencePathLabelPoint
import com.flightalert.map.reference.labels.ReferenceScreenRect
import com.flightalert.map.reference.labels.translateReferencePathCollisionShape
import com.flightalert.map.reference.retained.PreparedRetainedLabelFrame
import com.flightalert.map.reference.retained.PreparedRetainedStaticLabelPlan
import com.flightalert.map.reference.retained.RETAINED_FRAME_HISTORY_LIMIT
import com.flightalert.map.reference.retained.ReferenceRetainedBitmapCompletion
import com.flightalert.map.reference.retained.ReferenceRetainedBitmapCoordinator
import com.flightalert.map.reference.retained.ReferenceRetainedDestination
import com.flightalert.map.reference.retained.ReferenceRetainedRenderer
import com.flightalert.map.reference.retained.ReferenceRetainedSceneHistory
import com.flightalert.map.reference.retained.RetainedFrameRole
import com.flightalert.map.reference.retained.RetainedLabelPlanKey
import com.flightalert.map.reference.retained.RetainedLabelPlanRequest
import com.flightalert.map.reference.retained.RetainedLabelTileKey
import com.flightalert.map.reference.retained.RetainedReferenceFade
import com.flightalert.map.reference.retained.RetainedReferenceFrame
import com.flightalert.map.reference.retained.RetainedStaticLabelPlanKey
import com.flightalert.map.reference.retained.reference_retained_displayed_frame
import com.flightalert.map.reference.retained.reference_retained_entering_label_alpha
import com.flightalert.map.reference.retained.reference_retained_fade_carry
import com.flightalert.map.reference.retained.reference_retained_frame_viewport
import com.flightalert.map.reference.retained.reference_retained_label_anchor_is_stable
import com.flightalert.map.reference.retained.reference_retained_label_intersects_viewport
import com.flightalert.map.reference.retained.reference_retained_label_transition
import com.flightalert.map.reference.retained.reference_retained_leaving_label_alpha
import com.flightalert.map.reference.retained.reference_retained_repeating_world_width_px
import com.flightalert.map.reference.retained.reference_retained_scene_should_stage
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt


internal class ReferenceDictionaryOverlayRenderer(
    context: Context,
    private val map_tile_disk_cache: MapTileDiskCache,
    private val text_paint: Paint,
    dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val request_redraw: () -> Unit
) : Closeable {
    private val tile_repository = ReferenceTileRepository(context, request_redraw)
    private val visible_tiles = ArrayList<VisibleDictionaryTile>(32)
    private val fallback_visible_tiles = ArrayList<VisibleDictionaryTile>(16)
    private val draw_tiles = ArrayList<DictionaryTileDrawRef>(32)
    private val fallback_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    @Volatile
    private var closed = false
    private val lifecycle_lock = ReentrantLock()
    private val boundary_density = dp(1f)
    private val label_renderer = ReferenceLabelRenderer(
        text_paint = text_paint,
        sp = sp,
        with_alpha = with_alpha,
        boundary_density = boundary_density,
    )
    private val boundary_renderer = ReferenceBoundaryRenderer(
        context = context,
        map_tile_disk_cache = map_tile_disk_cache,
        boundary_density = boundary_density,
        request_redraw = request_redraw,
        tile_repository = tile_repository,
    )
    private val retained_renderer = ReferenceRetainedRenderer(
        tile_repository = tile_repository,
    )
    private val fading_destination = ReferenceRetainedDestination()
    private val retained_label_executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-labels").apply {
            isDaemon = true
        }
    }
    private val retained_label_coordinator = ReferenceRetainedBitmapCoordinator<
        RetainedLabelPlanKey,
        PreparedRetainedLabelFrame
        > {}
    private val retained_static_label_plans =
        ReferenceRetainedSceneHistory<PreparedRetainedStaticLabelPlan>(
            RETAINED_FRAME_HISTORY_LIMIT,
        )
    @Volatile
    private var requested_retained_static_label_key: RetainedStaticLabelPlanKey? = null
    private val retained_label_schedule_lock = Any()
    private var retained_label_pending: RetainedLabelPlanRequest? = null
    private var retained_label_worker_running = false
    private var partial_label_frame: RetainedReferenceFrame? = null
    private var retained_fade: RetainedReferenceFade? = null
    private var displayed_retained_frame: RetainedReferenceFrame? = null

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
    ): Boolean {
        if (interaction_active) {
            if (retained_renderer.interaction_started(true)) cancel_label_request()
            retained_renderer.update_interaction_active(true)
            return false
        }
        retained_renderer.update_interaction_active(false)
        if (!labels_enabled && !borders_enabled) {
            return false
        }
        val snapshot = tile_repository.snapshot()
        if (snapshot == null) {
            tile_repository.prepare()
            return false
        }
        val package_info = snapshot.info
        val filter_mask = ReferenceFilterMask.from(filter_state)
        val tile_zoom = dictionary_tile_zoom(viewport.zoom, package_info.zooms)
            ?: return false
        val fallback_zoom = select_fallback(
            tile_zoom,
            package_info.zooms,
        )
        val now_ms = SystemClock.elapsedRealtime()
        val options = RetainedReferenceOptions(
            labels_enabled = labels_enabled,
            // Retained frames contain labels only; borders draw independently below.
            borders_enabled = false,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask_key = filter_mask.cacheKey,
            label_text_scale_key = (label_text_scale * 1000f).roundToInt()
        )
        val prepared_retained_label_frame = retained_label_coordinator.take()
        if (prepared_retained_label_frame != null) {
            retained_renderer.finish_completed_retained_label_frame(
                prepared = prepared_retained_label_frame,
                viewport = viewport,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                options = options,
                label_avoid_rects = label_avoid_rects,
                interaction_active = interaction_active,
            )
        }
        val groups = ReferenceDictionaryLayerGroups(
            places = place_labels_enabled,
            water = water_labels_enabled,
            regions = region_labels_enabled,
            public_lands = public_lands_enabled,
        )
        val settled_target = retained_renderer.target_frame()
        val fallback_needs_build = retained_renderer.retained_fallback_needs_build(
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
            settled_target.matches_label_avoidance(label_avoid_rects) &&
            retained_renderer.retained_target_frame_can_settle(
                frame = settled_target,
                viewport = viewport,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = retained_renderer.destination,
            )
        if (settled_target_ready && !fallback_needs_build) {
            val successor_needed =
                reference_retained_scene_should_stage(
                    destination = retained_renderer.destination,
                    viewport = viewport,
                    repeatingWorldWidthPx = reference_retained_repeating_world_width_px(
                        viewport,
                        tile_zoom,
                    ),
                ) &&
                    !retained_renderer.retained_history_has_exact_target(viewport, tile_zoom, options) &&
                    !has_current_label_request()
            if (!successor_needed) {
                if (borders_enabled) {
                    boundary_renderer.draw(
                        canvas = canvas,
                        viewport = viewport,
                        source_zoom = tile_zoom,
                        groups = groups,
                        filter_mask = filter_mask,
                        interaction_active = false,
                        tile_request_generation = tile_repository.request_generation(),
                        active_retained_frame = settled_target,
                        now_ms = now_ms,
                    )
                }
                draw_retained_frame_with_transition(
                    canvas = canvas,
                    viewport = viewport,
                    frame = settled_target,
                    now_ms = now_ms,
                    destination = retained_renderer.destination,
                )
                return is_fading()
            }
        }
        val draw_viewport = reference_retained_frame_viewport(viewport, tile_zoom)
        build_visible_tile_list(visible_tiles, draw_viewport, viewport, tile_zoom)
        if (visible_tiles.isEmpty()) return false
        val fallback_viewport = if (fallback_needs_build) {
            reference_retained_frame_viewport(viewport, requireNotNull(fallback_zoom))
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
                    fallback_prefetch_requests += queued
                }
            }
        }

        draw_tiles.clear()
        var missing = 0
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
            } else {
                missing++
                if (tile.core_visible) {
                    tile_repository.request_tile_if_needed(tile, generation)
                } else if (padded_prefetch_requests < PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    val queued = tile_repository.request_tile_if_needed(tile, generation)
                    padded_prefetch_requests += queued
                }
            }
        }

        val ready = missing == 0
        val fallback_ready = fallback_visible_tiles.isNotEmpty() && fallback_missing == 0
        if (borders_enabled) {
            boundary_renderer.draw(
                canvas = canvas,
                viewport = viewport,
                source_zoom = tile_zoom,
                groups = groups,
                filter_mask = filter_mask,
                interaction_active = interaction_active,
                tile_request_generation = generation,
                active_retained_frame = settled_target,
                now_ms = now_ms,
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
                return is_fading()
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
                return is_fading()
            }
            return false
        }

        if (draw_tiles.isEmpty()) {
            return false
        }

        if (!ready) return false

        val target = retained_renderer.target_frame()
        if (
            target != null &&
            target.is_drawable() &&
            target.matches_environment(
                viewport = viewport,
                next = options,
                current_package_generation = tile_repository.package_generation(),
            ) &&
            target.matches_label_avoidance(label_avoid_rects) &&
            retained_renderer.retained_target_frame_can_settle(
                frame = target,
                viewport = viewport,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = retained_renderer.destination,
            )
        ) {
            clear_partial_frame()
            if (fallback_needs_build && fallback_ready &&
                !has_current_label_request()
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
                reference_retained_scene_should_stage(retained_renderer.destination, viewport) &&
                !retained_renderer.retained_history_has_exact_target(
                    viewport = viewport,
                    tile_zoom = tile_zoom,
                    options = options,
                ) &&
                !has_current_label_request()
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
                retained_renderer.destination,
            )
            return is_fading()
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
            clear_partial_frame()
            return is_fading()
        }

        return false
    }

    fun package_check_pending(): Boolean = tile_repository.package_check_pending()

    fun prepare() = tile_repository.prepare()

    fun reference_class_catalog(): ReferenceClassCatalog? =
        tile_repository.reference_class_catalog()

    fun clear() {
        tile_repository.clear()
        clear_renderer_state()
    }

    fun reset_retained_frame() {
        reset_retained_state()
    }

    override fun close() {
        lifecycle_lock.lock()
        try {
            if (closed) return
            closed = true
            reset_retained_state()
            retained_label_executor.shutdownNow()
            boundary_renderer.close()
            tile_repository.close()
            clear_renderer_state(clear_retained = false, clear_boundaries = false)
        } finally {
            lifecycle_lock.unlock()
        }
    }

    private fun reset_retained_state() {
        cancel_retained_label_plan()
        partial_label_frame = null
        retained_fade = null
        displayed_retained_frame = null
        synchronized(retained_static_label_plans) {
            retained_static_label_plans.clear()
        }
        retained_renderer.reset()
    }

    private fun clear_renderer_state(
        clear_retained: Boolean = true,
        clear_boundaries: Boolean = true,
    ) {
        if (clear_retained) reset_retained_state()
        if (clear_boundaries) boundary_renderer.clear()
    }

    fun partial_frame(): RetainedReferenceFrame? = partial_label_frame

    fun clear_partial_frame() {
        partial_label_frame = null
    }

    fun is_fading(): Boolean = retained_fade != null

    fun has_current_label_request(): Boolean = retained_label_coordinator.hasCurrentRequest()

    fun cancel_label_request() {
        cancel_retained_label_plan()
    }

    fun request_retained_label_frame(
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
        retained_label_coordinator.currentKey()?.let { current_key ->
            if (retained_label_request_matches(
                    key = current_key,
                    role = role,
                    viewport = viewport,
                    frame_viewport = frame_viewport,
                    tile_zoom = tile_zoom,
                    tiles = tiles,
                    label_avoid_rects = label_avoid_rects,
                    options = options,
                    package_generation = package_generation_snapshot,
                )
            ) return
        }
        val avoid_rects = label_avoid_rects.toList()
        val tile_keys = retained_label_tile_keys(tiles)
        val static_key = RetainedStaticLabelPlanKey(
            role = role,
            viewport = viewport,
            frameViewport = frame_viewport,
            tileZoom = tile_zoom,
            tiles = tile_keys,
            options = options,
            packageGeneration = package_generation_snapshot,
        )
        val key = RetainedLabelPlanKey(
            role = role,
            viewport = viewport,
            frameViewport = frame_viewport,
            tileZoom = tile_zoom,
            tiles = tile_keys,
            options = options,
            packageGeneration = package_generation_snapshot,
            labelAvoidRects = avoid_rects,
        )
        requested_retained_static_label_key = static_key
        if (retained_renderer.retained_label_plan_is_available(
                key,
                partial_label_frame,
                displayed_retained_frame,
            )
        ) {
            cancel_retained_label_plan()
            return
        }
        val token = retained_label_coordinator.start(key) ?: return
        val preferred_labels = displayed_retained_frame
            ?.takeIf {
                it.options == options &&
                    it.package_generation == package_generation_snapshot
            }
            ?.labels
            ?: emptyList()
        enqueue_retained_label_request(
            RetainedLabelPlanRequest(
                token = token,
                staticKey = static_key,
                tiles = tiles.toList(),
                preferredLabels = preferred_labels,
                labelAvoidRects = avoid_rects,
                labelTextScale = label_text_scale,
                placeLabelsEnabled = place_labels_enabled,
                waterLabelsEnabled = water_labels_enabled,
                regionLabelsEnabled = region_labels_enabled,
                publicLandsEnabled = public_lands_enabled,
                filterMask = filter_mask,
            )
        )
    }

    private fun enqueue_retained_label_request(request: RetainedLabelPlanRequest) {
        var schedule_worker = false
        synchronized(retained_label_schedule_lock) {
            retained_label_pending = request
            if (!retained_label_worker_running) {
                retained_label_worker_running = true
                schedule_worker = true
            }
        }
        if (!schedule_worker) return
        try {
            retained_label_executor.execute(::drain_retained_label_requests)
        } catch (_: RuntimeException) {
            synchronized(retained_label_schedule_lock) {
                retained_label_pending = null
                retained_label_worker_running = false
            }
            cancel_retained_label_plan()
        }
    }

    private fun drain_retained_label_requests() {
        while (true) {
            val request = synchronized(retained_label_schedule_lock) {
                retained_label_pending.also { retained_label_pending = null }
                    ?: run {
                        retained_label_worker_running = false
                        return
                    }
            }
            try {
                prepare_and_publish_retained_label_frame(request)
            } catch (_: Exception) {
                retained_label_coordinator.complete(
                    request = request.token,
                    value = PreparedRetainedLabelFrame(request, null),
                    complete = false,
                )
            }
        }
    }

    private fun prepare_and_publish_retained_label_frame(request: RetainedLabelPlanRequest) {
        if (!retained_label_coordinator.shouldStart(request.token)) return
        val key = request.token.key
        val keep_static_planning = {
            requested_retained_static_label_key == request.staticKey &&
                !Thread.currentThread().isInterrupted
        }
        val cached_static_plan = synchronized(retained_static_label_plans) {
            retained_static_label_plans.firstOrNull { plan ->
                plan.key == request.staticKey
            }?.also { plan -> retained_static_label_plans.remember(plan) }
        }
        val static_plan = cached_static_plan ?: prepare_retained_static_label_plan(
            key = request.staticKey,
            tiles = request.tiles,
            labelTextScale = request.labelTextScale,
            placeLabelsEnabled = request.placeLabelsEnabled,
            waterLabelsEnabled = request.waterLabelsEnabled,
            regionLabelsEnabled = request.regionLabelsEnabled,
            publicLandsEnabled = request.publicLandsEnabled,
            filterMask = request.filterMask,
            workspace = label_renderer.retained_workspace,
            keep_planning = keep_static_planning,
        )?.also { prepared ->
            synchronized(retained_static_label_plans) {
                if (requested_retained_static_label_key == request.staticKey) {
                    retained_static_label_plans.remember(prepared)
                }
            }
        }
        val keep_planning = {
            retained_label_coordinator.shouldStart(request.token) &&
                !Thread.currentThread().isInterrupted
        }
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
        val frame = if (static_plan != null && keep_planning()) {
            val labels = resolve_retained_static_label_plan(
                workspace = label_renderer.retained_workspace,
                plan = static_plan,
                label_avoid_rects = request.labelAvoidRects,
                preferred_occurrences = preferred_occurrences,
                preferred_candidate_keys = preferred_candidate_keys,
                keep_planning = keep_planning,
            )
            if (labels != null) {
                retained_renderer.create_retained_frame(
                    viewport = key.viewport,
                    frame_viewport = key.frameViewport,
                    tile_zoom = key.tileZoom,
                    options = key.options,
                    package_generation_snapshot = key.packageGeneration,
                    labels = labels,
                    label_plan_key = key,
                )
            } else null
        } else null
        val completion = retained_label_coordinator.complete(
            request = request.token,
            value = PreparedRetainedLabelFrame(request, frame),
            complete = frame != null && keep_planning(),
        )
        if (completion == ReferenceRetainedBitmapCompletion.PUBLISH) request_redraw()
    }

    private fun prepare_retained_static_label_plan(
        key: RetainedStaticLabelPlanKey,
        tiles: List<DictionaryTileDrawRef>,
        labelTextScale: Float,
        placeLabelsEnabled: Boolean,
        waterLabelsEnabled: Boolean,
        regionLabelsEnabled: Boolean,
        publicLandsEnabled: Boolean,
        filterMask: ReferenceFilterMask,
        workspace: LabelPlanningWorkspace,
        keep_planning: () -> Boolean,
    ): PreparedRetainedStaticLabelPlan? {
        val groups = ReferenceDictionaryLayerGroups(
            places = placeLabelsEnabled,
            water = waterLabelsEnabled,
            regions = regionLabelsEnabled,
            public_lands = publicLandsEnabled,
        )
        val core = label_renderer.prepare_label_plan(
            workspace = workspace,
            viewport = key.viewport,
            tiles = tiles,
            label_text_scale = labelTextScale,
            groups = groups,
            filter_mask = filterMask,
            keep_planning = keep_planning,
        ) ?: return null
        val width = ceil(key.viewport.width).toInt().coerceAtLeast(1)
        val height = ceil(key.viewport.height).toInt().coerceAtLeast(1)
        val frame_width = ceil(key.frameViewport.width).toInt().coerceAtLeast(1)
        val frame_height = ceil(key.frameViewport.height).toInt().coerceAtLeast(1)
        val horizontal_padding = (frame_width - width) / 2.0
        val vertical_padding = (frame_height - height) / 2.0
        val core_rect = ReferenceScreenRect(
            left = horizontal_padding,
            top = vertical_padding,
            right = horizontal_padding + key.viewport.width,
            bottom = vertical_padding + key.viewport.height,
        )
        val padding = label_renderer.prepare_label_plan(
            workspace = workspace,
            viewport = key.frameViewport,
            tiles = tiles,
            label_text_scale = labelTextScale,
            groups = groups,
            filter_mask = filterMask,
            include_buffer_tiles = true,
            excluded_rect = core_rect,
            keep_planning = keep_planning,
        ) ?: return null
        return PreparedRetainedStaticLabelPlan(
            key = key,
            core = core,
            padding = padding,
        )
    }

    private fun retained_label_tile_keys(
        tiles: List<DictionaryTileDrawRef>,
    ): List<RetainedLabelTileKey> = tiles.map { tile ->
        RetainedLabelTileKey(
            z = tile.tile.coordinate.z,
            x = tile.tile.coordinate.x,
            y = tile.tile.coordinate.y,
            drawX = tile.draw_x,
            coreVisible = tile.core_visible,
            identity = System.identityHashCode(tile.tile),
        )
    }

    private fun retained_label_request_matches(
        key: RetainedLabelPlanKey,
        role: RetainedFrameRole,
        viewport: Viewport,
        frame_viewport: Viewport,
        tile_zoom: Int,
        tiles: List<DictionaryTileDrawRef>,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
        package_generation: Long,
    ): Boolean {
        if (key.role != role ||
            key.viewport != viewport ||
            key.frameViewport != frame_viewport ||
            key.tileZoom != tile_zoom ||
            key.options != options ||
            key.packageGeneration != package_generation ||
            key.labelAvoidRects != label_avoid_rects ||
            key.tiles.size != tiles.size
        ) return false
        return tiles.indices.all { index ->
            val tile = tiles[index]
            val existing = key.tiles[index]
            existing.z == tile.tile.coordinate.z &&
                existing.x == tile.tile.coordinate.x &&
                existing.y == tile.tile.coordinate.y &&
                existing.drawX == tile.draw_x &&
                existing.coreVisible == tile.core_visible &&
                existing.identity == System.identityHashCode(tile.tile)
        }
    }

    private fun cancel_retained_label_plan() {
        requested_retained_static_label_key = null
        synchronized(retained_label_schedule_lock) {
            retained_label_pending = null
        }
        retained_label_coordinator.cancel()
    }

    private fun resolve_retained_static_label_plan(
        workspace: LabelPlanningWorkspace,
        plan: PreparedRetainedStaticLabelPlan,
        label_avoid_rects: List<ReferenceScreenRect>,
        preferred_occurrences: Set<ReferenceLabelOccurrenceId>,
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey>,
        keep_planning: () -> Boolean,
    ): List<DictionaryLabelCandidate>? {
        val viewport = plan.key.viewport
        val frame_viewport = plan.key.frameViewport
        val width = ceil(viewport.width).toInt().coerceAtLeast(1)
        val height = ceil(viewport.height).toInt().coerceAtLeast(1)
        val frame_width = ceil(frame_viewport.width).toInt().coerceAtLeast(1)
        val frame_height = ceil(frame_viewport.height).toInt().coerceAtLeast(1)
        val horizontal_padding = (frame_width - width) / 2.0
        val vertical_padding = (frame_height - height) / 2.0
        val core_plan_complete = label_renderer.resolve_label_plan(
            workspace = workspace,
            plan = plan.core,
            viewport = viewport,
            label_avoid_rects = label_avoid_rects,
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            keep_planning = keep_planning,
        )
        if (!core_plan_complete) return null
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
        for (rect in label_avoid_rects) {
            val shifted = translate_screen_rect(rect, horizontal_padding, vertical_padding)
            val contained_by_core =
                shifted.left >= core_rect.left &&
                    shifted.top >= core_rect.top &&
                    shifted.right <= core_rect.right &&
                    shifted.bottom <= core_rect.bottom
            if (!contained_by_core) shifted_avoid_rects += shifted
        }
        shifted_avoid_rects += core_rect
        val core_area = viewport.width.toDouble() * viewport.height
        val frame_area = frame_viewport.width.toDouble() * frame_viewport.height
        val padding_area_ratio = ((frame_area - core_area) / core_area).coerceAtLeast(0.0)
        val padding_budget = ceil(
            label_renderer.label_budget(viewport) * padding_area_ratio
        ).toInt().coerceIn(0, MAX_RETAINED_PADDING_LABELS)
        val padding_protected_area_budget = ceil(
            label_renderer.protected_area_label_budget(viewport) * padding_area_ratio
        ).toInt().coerceAtLeast(0)
        if (!keep_planning()) return null
        val padding_plan_complete = label_renderer.resolve_label_plan(
            workspace = workspace,
            plan = plan.padding,
            viewport = frame_viewport,
            label_avoid_rects = shifted_avoid_rects,
            fixed_candidates = core_candidates,
            label_budget_override = (core_candidates.size + padding_budget).coerceAtLeast(1),
            protected_area_budget_override = (
                core_candidates.count { it.protectedArea } + padding_protected_area_budget
                ).coerceAtLeast(1),
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            keep_planning = keep_planning,
        )
        if (!padding_plan_complete) return null
        return workspace.acceptedLabels.toList()
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

    fun draw_retained_frame_if_available(
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        target_tile_zoom: Int,
        fallback_tile_zoom: Int?,
        now_ms: Long,
    ): RetainedReferenceFrame? {
        val frame = retained_renderer.retained_frame_for_viewport(
            viewport,
            options,
            target_tile_zoom,
            fallback_tile_zoom,
            displayed_retained_frame,
            retained_renderer.destination,
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
            destination = retained_renderer.destination,
        )
        return frame
    }

    fun draw_retained_frame_with_transition(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        now_ms: Long,
        destination: ReferenceRetainedDestination? = null,
    ) {
        val current_destination = destination ?: retained_renderer.destination.also {
            retained_renderer.retained_frame_destination(frame, viewport, it)
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
            retained_renderer.retained_frame_destination(established, viewport, fading_destination)
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
        val leaving_label_alpha = reference_retained_leaving_label_alpha(raw_progress)
        val entering_label_alpha = reference_retained_entering_label_alpha(raw_progress)
        retained_renderer.retained_frame_destination(fade.previous, viewport, fading_destination)
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
        destination: ReferenceRetainedDestination? = null,
    ) {
        val draw_destination = destination ?: retained_renderer.destination.also {
            retained_renderer.retained_frame_destination(frame, viewport, it)
        }
        draw_retained_labels(canvas, viewport, frame, draw_destination)
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
            label_renderer.draw_label_candidate(
                canvas = canvas,
                candidate = candidate,
                zoom_scale = zoom_scale,
                translate_x = destination.left,
                translate_y = destination.top,
                frame_alpha = if (fully_visible?.get(index) == true) 255 else alpha,
            )
        }
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

    private fun cleanup_retained_fade() {
        if (retained_fade == null) return
        retained_fade = null
    }

    fun partial_label_frame_for(
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
        if (
            !labels_enabled ||
            viewport.zoom >= ReferenceLabelRuntimePresentationPolicy.minimumPathLabelZoom ||
            tiles.isEmpty()
        ) return null

        // Freeze the first partial point-label plan so incoming tiles cannot make labels jump.
        val static_label_plan = prepare_retained_static_label_plan(
            key = RetainedStaticLabelPlanKey(
                role = RetainedFrameRole.TARGET,
                viewport = viewport,
                frameViewport = viewport,
                tileZoom = tile_zoom,
                tiles = retained_label_tile_keys(tiles),
                options = options,
                packageGeneration = package_generation_snapshot,
            ),
            tiles = tiles,
            labelTextScale = label_text_scale,
            placeLabelsEnabled = place_labels_enabled,
            waterLabelsEnabled = water_labels_enabled,
            regionLabelsEnabled = region_labels_enabled,
            publicLandsEnabled = public_lands_enabled,
            filterMask = filter_mask,
            workspace = label_renderer.ui_workspace,
            keep_planning = ALWAYS_KEEP_LABEL_PLAN,
        ) ?: return null
        val labels = resolve_retained_static_label_plan(
            workspace = label_renderer.ui_workspace,
            plan = static_label_plan,
            label_avoid_rects = label_avoid_rects,
            preferred_occurrences = emptySet(),
            preferred_candidate_keys = emptySet(),
            keep_planning = ALWAYS_KEEP_LABEL_PLAN,
        ) ?: return null
        return retained_renderer.create_retained_frame(
            viewport = viewport,
            frame_viewport = viewport,
            tile_zoom = tile_zoom,
            options = options,
            package_generation_snapshot = package_generation_snapshot,
            labels = labels,
        ).takeIf { frame -> frame.labels.isNotEmpty() }
            ?.also { frame -> partial_label_frame = frame }
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
                LABEL_ONLY_OUTLINE_PROFILE
            } else {
                outline_decode_profile(
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

    private companion object {
        const val MAP_TILE_SIZE = 256.0
        const val PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW = 2
        const val FALLBACK_TILE_PREFETCH_REQUESTS_PER_DRAW = 4
        const val RETAINED_FRAME_FADE_MS = 220f
        const val MAX_RETAINED_PADDING_LABELS = 96
    }
}
