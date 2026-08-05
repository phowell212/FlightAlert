@file:Suppress("FunctionName", "LocalVariableName", "PropertyName", "ArrayInDataClass", "PrivatePropertyName")

package com.flightalert.map.reference

import android.graphics.Canvas
import com.flightalert.map.*
import com.flightalert.map.reference.dictionary.*
import com.flightalert.map.reference.labels.*
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.*

private const val EXACT_VIEWPORT_EPSILON = 0.000_001

internal class ReferenceRetainedRenderer(
    private val tile_repository: ReferenceTileRepository,
    private val label_renderer: ReferenceLabelRenderer,
    private val request_redraw: () -> Unit,
) : Closeable {
    val destination = ReferenceRetainedDestination()
    private val retained_destination = destination
    private val fallback_destination = ReferenceRetainedDestination()
    private val fading_destination = ReferenceRetainedDestination()
    private val candidate_destination = ReferenceRetainedDestination()
    private val retained_label_executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
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
    private var retained_frame: RetainedReferenceFrame? = null
    private var retained_fallback_frame: RetainedReferenceFrame? = null
    private var partial_label_frame: RetainedReferenceFrame? = null
    private var retained_fade: RetainedReferenceFade? = null
    private var displayed_retained_frame: RetainedReferenceFrame? = null
    private var retained_label_interaction_active = false
    private val retained_frame_history =
        ReferenceRetainedSceneHistory<RetainedReferenceFrame>(RETAINED_FRAME_HISTORY_LIMIT)

    fun interaction_started(interactionActive: Boolean): Boolean =
        interactionActive && !retained_label_interaction_active

    fun update_interaction_active(interactionActive: Boolean) {
        retained_label_interaction_active = interactionActive
    }

    fun target_frame(): RetainedReferenceFrame? = retained_frame

    fun partial_frame(): RetainedReferenceFrame? = partial_label_frame

    fun clear_partial_frame() {
        partial_label_frame = null
    }

    fun is_fading(): Boolean = retained_fade != null

    fun has_current_label_request(): Boolean = retained_label_coordinator.hasCurrentRequest()

    fun cancel_label_request() {
        cancel_retained_label_plan()
    }

    fun reset() {
        cancel_retained_label_plan()
        retained_label_interaction_active = false
        clear_retained_frames()
    }

    fun clear() = reset()

    override fun close() {
        reset()
        retained_label_executor.shutdownNow()
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
        if (retained_label_plan_is_available(key)) {
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

    private fun retained_label_plan_is_available(key: RetainedLabelPlanKey): Boolean {
        if (retained_frame?.label_plan_key == key ||
            retained_fallback_frame?.label_plan_key == key ||
            partial_label_frame?.label_plan_key == key ||
            displayed_retained_frame?.label_plan_key == key
        ) return true
        return retained_frame_history.any { frame -> frame.label_plan_key == key }
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
            create_retained_frame(
                viewport = key.viewport,
                frame_viewport = key.frameViewport,
                tile_zoom = key.tileZoom,
                tiles = request.tiles,
                label_text_scale = request.labelTextScale,
                place_labels_enabled = request.placeLabelsEnabled,
                water_labels_enabled = request.waterLabelsEnabled,
                region_labels_enabled = request.regionLabelsEnabled,
                public_lands_enabled = request.publicLandsEnabled,
                filter_mask = request.filterMask,
                label_avoid_rects = request.labelAvoidRects,
                options = key.options,
                package_generation_snapshot = key.packageGeneration,
                label_plan_key = key,
                preferred_occurrences = preferred_occurrences,
                preferred_candidate_keys = preferred_candidate_keys,
                workspace = label_renderer.retained_workspace,
                keep_planning = keep_planning,
                static_label_plan = static_plan,
            )
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

    fun finish_completed_retained_label_frame(
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

    private fun create_retained_frame(
        viewport: Viewport,
        frame_viewport: Viewport,
        tile_zoom: Int,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
        options: RetainedReferenceOptions,
        package_generation_snapshot: Long,
        label_plan_key: RetainedLabelPlanKey? = null,
        preferred_occurrences: Set<ReferenceLabelOccurrenceId> = emptySet(),
        preferred_candidate_keys: Set<ReferenceLabelCandidateKey> = emptySet(),
        workspace: LabelPlanningWorkspace = label_renderer.ui_workspace,
        keep_planning: () -> Boolean = ALWAYS_KEEP_LABEL_PLAN,
        static_label_plan: PreparedRetainedStaticLabelPlan? = null,
    ): RetainedReferenceFrame? {
        val resolved_static_label_plan = static_label_plan ?: prepare_retained_static_label_plan(
            key = RetainedStaticLabelPlanKey(
                role = RetainedFrameRole.TARGET,
                viewport = viewport,
                frameViewport = frame_viewport,
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
            workspace = workspace,
            keep_planning = keep_planning,
        ) ?: return null
        val width = ceil(viewport.width).toInt().coerceAtLeast(1)
        val height = ceil(viewport.height).toInt().coerceAtLeast(1)
        val frame_width = ceil(frame_viewport.width).toInt().coerceAtLeast(1)
        val frame_height = ceil(frame_viewport.height).toInt().coerceAtLeast(1)
        val labels = resolve_retained_static_label_plan(
            workspace = workspace,
            plan = resolved_static_label_plan,
            label_avoid_rects = label_avoid_rects,
            preferred_occurrences = preferred_occurrences,
            preferred_candidate_keys = preferred_candidate_keys,
            keep_planning = keep_planning,
        ) ?: return null
        val label_coverage = ReferenceRetainedLabelCoverage(
            width = frame_width.toDouble(),
            height = frame_height.toDouble(),
        )
        for (label in labels) {
            label_coverage.add(label.collisionShape.bounds)
        }
        return RetainedReferenceFrame(
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
            label_plan_key = label_plan_key,
            labels = labels,
            label_coverage = label_coverage,
        )
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
        val frame = retained_frame_for_viewport(
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

    fun retained_frame_for_viewport(
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
            closest_zoom_distance = abs(primary.zoom - viewport.zoom)
        }
        if (established != null) {
            for (previous in retained_frame_history) {
                val zoom_distance = abs(previous.zoom - viewport.zoom)
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

    fun retained_history_has_exact_target(
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

    private fun retained_frame_is_eligible(
        frame: RetainedReferenceFrame,
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

    fun retained_fallback_needs_build(
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
                    viewport = viewport,
                    options = options,
                    target_tile_zoom = target_tile_zoom,
                    fallback_tile_zoom = fallback_tile_zoom,
                    destination = fallback_destination,
                )
            )
    }

    fun draw_retained_frame_with_transition(
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
        val leaving_label_alpha = reference_retained_leaving_label_alpha(raw_progress)
        val entering_label_alpha = reference_retained_entering_label_alpha(raw_progress)
        retained_frame_destination(fade.previous, viewport, fading_destination)
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
        val draw_destination = destination ?: retained_destination.also {
            retained_frame_destination(frame, viewport, it)
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

    fun retained_target_frame_can_settle(
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

    fun retained_frame_viewport(
        viewport: Viewport,
        tile_zoom: Int,
    ): Viewport {
        val padding = retained_frame_padding_px(viewport, tile_zoom)
        return viewport.copy(
            width = viewport.width + padding * 2f,
            height = viewport.height + padding * 2f
        )
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

    fun retained_repeating_world_width_px(
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
        ) {
            if (active.label_plan_key != frame.label_plan_key) replace_retained_frame(frame)
            return
        }
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

    fun retained_scene_padding_for_viewport(
        viewport: Viewport,
        tile_zoom: Int,
    ): Int {
        val active = retained_frame
        if (active != null &&
            active.tile_zoom == tile_zoom &&
            abs(active.zoom - viewport.zoom) < EXACT_VIEWPORT_EPSILON &&
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
        if (!labels_enabled || viewport.zoom >= MIN_PATH_LABEL_ZOOM || tiles.isEmpty()) return null

        // Freeze the first partial point-label plan so incoming tiles cannot make labels jump.
        return create_retained_frame(
            viewport = viewport,
            frame_viewport = viewport,
            tile_zoom = tile_zoom,
            tiles = tiles,
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
        synchronized(retained_static_label_plans) {
            retained_static_label_plans.clear()
        }
        requested_retained_static_label_key = null
    }


    private companion object {
        const val MAP_TILE_SIZE = 256.0
        const val ZOOM_AHEAD_HANDOFF_LEAD = 0.25
        const val RETAINED_FRAME_FADE_MS = 220f
        const val RETAINED_FRAME_HISTORY_LIMIT = 5
        const val MAX_RETAINED_FRAME_SIZE = 8192
        const val MAX_RETAINED_PADDING_LABELS = 96
        const val MAX_TARGET_SCENE_TILES = 64
        const val MIN_PATH_LABEL_ZOOM = 10.0
    }
}

internal enum class RetainedFrameRole { FALLBACK, TARGET }

internal data class RetainedLabelTileKey(
    val z: Int,
    val x: Int,
    val y: Int,
    val drawX: Int,
    val coreVisible: Boolean,
    val identity: Int,
)

internal data class RetainedStaticLabelPlanKey(
    val role: RetainedFrameRole,
    val viewport: Viewport,
    val frameViewport: Viewport,
    val tileZoom: Int,
    val tiles: List<RetainedLabelTileKey>,
    val options: RetainedReferenceOptions,
    val packageGeneration: Long,
)

internal data class RetainedLabelPlanKey(
    val role: RetainedFrameRole,
    val viewport: Viewport,
    val frameViewport: Viewport,
    val tileZoom: Int,
    val tiles: List<RetainedLabelTileKey>,
    val options: RetainedReferenceOptions,
    val packageGeneration: Long,
    val labelAvoidRects: List<ReferenceScreenRect>,
)

internal data class RetainedLabelPlanRequest(
    val token: ReferenceRetainedBitmapRequestToken<RetainedLabelPlanKey>,
    val staticKey: RetainedStaticLabelPlanKey,
    val tiles: List<DictionaryTileDrawRef>,
    val preferredLabels: List<DictionaryLabelCandidate>,
    val labelAvoidRects: List<ReferenceScreenRect>,
    val labelTextScale: Float,
    val placeLabelsEnabled: Boolean,
    val waterLabelsEnabled: Boolean,
    val regionLabelsEnabled: Boolean,
    val publicLandsEnabled: Boolean,
    val filterMask: ReferenceFilterMask,
)

internal data class PreparedRetainedLabelFrame(
    val request: RetainedLabelPlanRequest,
    val frame: RetainedReferenceFrame?,
)

internal data class PreparedRetainedStaticLabelPlan(
    val key: RetainedStaticLabelPlanKey,
    val core: PreparedLabelPlan,
    val padding: PreparedLabelPlan,
)

internal data class RetainedReferenceFrame(
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
    val label_plan_key: RetainedLabelPlanKey?,
    val labels: List<DictionaryLabelCandidate>,
    val label_coverage: ReferenceRetainedLabelCoverage,
) {
    val labels_drawn: Int get() = labels.size

    fun matches_options(next: RetainedReferenceOptions): Boolean {
        return options == next
    }

    fun matches_label_avoidance(next: List<ReferenceScreenRect>): Boolean {
        return !options.labels_enabled || label_plan_key?.labelAvoidRects == next
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
                abs(zoom - viewport.zoom) < EXACT_VIEWPORT_EPSILON &&
                abs(center_x - viewport.center_x) < EXACT_VIEWPORT_EPSILON &&
                abs(center_y - viewport.center_y) < EXACT_VIEWPORT_EPSILON
    }

    fun has_content(): Boolean {
        return labels.isNotEmpty()
    }

    fun is_drawable(): Boolean {
        return has_content()
    }

    fun has_visible_content(
        viewport: Viewport,
        destination: ReferenceRetainedDestination,
    ): Boolean {
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

internal data class RetainedReferenceFade(
    val previous: RetainedReferenceFrame,
    val current: RetainedReferenceFrame,
    val labels: ReferenceRetainedLabelTransition<DictionaryLabelCandidate>,
    val start_ms: Long
)
