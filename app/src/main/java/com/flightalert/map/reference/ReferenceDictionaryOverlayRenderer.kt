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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import com.flightalert.map.tiles.MapTileDiskCache
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.floor
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
    private val visible_tiles = ArrayList<VisibleDictionaryTile>(32)
    private val fallback_visible_tiles = ArrayList<VisibleDictionaryTile>(16)
    private val draw_tiles = ArrayList<DictionaryTileDrawRef>(32)
    private val core_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    private val fallback_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    private val zoom_ahead_draw_tiles = ArrayList<DictionaryTileDrawRef>(16)
    @Volatile
    private var closed = false
    private val lifecycle_lock = ReentrantLock()
    private val boundary_density = dp(1f)
    private val label_renderer = ReferenceLabelRenderer(
        text_paint = text_paint,
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        boundary_density = boundary_density,
    )
    private val boundary_renderer = ReferenceBoundaryRenderer(
        context = context,
        map_tile_disk_cache = map_tile_disk_cache,
        paint = paint,
        boundary_density = boundary_density,
        request_redraw = request_redraw,
        tile_repository = tile_repository,
        retained_scene_padding_px = ::retained_scene_padding_for_viewport,
    )
    private val retained_renderer = ReferenceRetainedRenderer(
        tile_repository = tile_repository,
        boundary_renderer = boundary_renderer,
        label_renderer = label_renderer,
        request_redraw = request_redraw,
        build_visible_tiles = ::build_visible_tile_list,
    )

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
        val interaction_started = retained_renderer.interaction_started(interaction_active)
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
        retained_renderer.finish_completed_retained_label_frame(
            viewport = viewport,
            target_tile_zoom = tile_zoom,
            fallback_tile_zoom = fallback_zoom,
            options = options,
            interaction_active = interaction_active,
        )
        if (interaction_started) {
            retained_renderer.cancel_label_request()
            tile_repository.clear_zoom_ahead_desired_tile_keys()
        }
        retained_renderer.update_interaction_active(interaction_active)
        val groups = ReferenceDictionaryLayerGroups(
            places = place_labels_enabled,
            water = water_labels_enabled,
            regions = region_labels_enabled,
            public_lands = public_lands_enabled,
        )
        val settled_target = retained_renderer.target_frame()
        val fallback_needs_build = retained_renderer.retained_fallback_needs_build(
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
            retained_renderer.retained_target_frame_can_settle(
                frame = settled_target,
                viewport = viewport,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = retained_renderer.destination,
            )
        var zoom_ahead_requests = 0
        if (settled_target_ready &&
            !reference_retained_scene_should_stage(
                destination = retained_renderer.destination,
                viewport = viewport,
                repeatingWorldWidthPx = retained_renderer.retained_repeating_world_width_px(
                    viewport,
                    tile_zoom,
                ),
            )
        ) {
            zoom_ahead_requests = retained_renderer.stage_zoom_ahead_labels(
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
                    destination = retained_renderer.destination,
                    viewport = viewport,
                    repeatingWorldWidthPx = retained_renderer.retained_repeating_world_width_px(
                        viewport,
                        tile_zoom,
                    ),
                ) &&
                    !retained_renderer.retained_history_has_exact_target(viewport, tile_zoom, options) &&
                    !retained_renderer.has_current_label_request()
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
                retained_renderer.draw_retained_frame_with_transition(
                    canvas = canvas,
                    viewport = viewport,
                    frame = settled_target,
                    now_ms = now_ms,
                    label_avoid_rects = label_avoid_rects,
                    destination = retained_renderer.destination,
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
                    fading = retained_renderer.is_fading(),
                )
            }
        }
        val retained_for_interaction = if (interaction_active && labels_enabled) {
            retained_renderer.retained_frame_for_viewport(
                canvas = canvas,
                viewport = viewport,
                options = options,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                destination = retained_renderer.destination,
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
                retained_renderer.draw_retained_frame_with_transition(
                    canvas = canvas,
                    viewport = viewport,
                    frame = retained_for_interaction,
                    now_ms = now_ms,
                    label_avoid_rects = label_avoid_rects,
                    destination = retained_renderer.destination,
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
                fading = retained_for_interaction != null && retained_renderer.is_fading(),
            )
        }
        val draw_viewport = if (interaction_active) {
            viewport
        } else {
            retained_renderer.retained_frame_viewport(viewport, tile_zoom)
        }
        build_visible_tile_list(visible_tiles, draw_viewport, viewport, tile_zoom)
        if (visible_tiles.isEmpty()) return ready_empty_stats()
        val fallback_viewport = if (fallback_needs_build) {
            if (interaction_active) {
                viewport
            } else {
                retained_renderer.retained_frame_viewport(viewport, requireNotNull(fallback_zoom))
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
            retained_renderer.draw_retained_frame_if_available(
                canvas,
                viewport,
                options,
                tile_zoom,
                fallback_zoom,
                now_ms,
                label_avoid_rects,
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
                fading = retained_drawn_for_interaction != null && retained_renderer.is_fading(),
            )
        }
        if (!ready && !(fallback_needs_build && fallback_ready)) {
            val retained = retained_renderer.draw_retained_frame_if_available(
                canvas,
                viewport,
                options,
                tile_zoom,
                fallback_zoom,
                now_ms,
                label_avoid_rects,
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
                    fading = retained_renderer.is_fading(),
                )
            }
        }

        if (fallback_needs_build && fallback_ready && !ready) {
            retained_renderer.request_retained_label_frame(
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
            val retained = retained_renderer.draw_retained_frame_if_available(
                canvas,
                viewport,
                options,
                tile_zoom,
                fallback_zoom,
                now_ms,
                label_avoid_rects,
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
                    fading = retained_renderer.is_fading(),
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
            val partial = retained_renderer.partial_label_frame_for(
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
                retained_renderer.draw_retained_frame_with_transition(
                    canvas,
                    viewport,
                    frame,
                    now_ms,
                    label_avoid_rects,
                )
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
                fading = partial != null && retained_renderer.is_fading(),
            )
        }

        if (ready) {
            val target = retained_renderer.target_frame()
            if (target != null && target.is_drawable() &&
                target.matches_environment(
                    viewport = viewport,
                    next = options,
                    current_package_generation = tile_repository.package_generation(),
                ) &&
                retained_renderer.retained_target_frame_can_settle(
                    frame = target,
                    viewport = viewport,
                    target_tile_zoom = tile_zoom,
                    fallback_tile_zoom = fallback_zoom,
                    destination = retained_renderer.destination,
                )
                ) {
                retained_renderer.clear_partial_frame()
                if (fallback_needs_build && fallback_ready &&
                    !retained_renderer.has_current_label_request()
                ) {
                    retained_renderer.request_retained_label_frame(
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
                    !retained_renderer.has_current_label_request()
                ) {
                    retained_renderer.request_retained_label_frame(
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
                retained_renderer.draw_retained_frame_with_transition(
                    canvas,
                    viewport,
                    target,
                    now_ms,
                    label_avoid_rects,
                    retained_renderer.destination,
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
                    fading = retained_renderer.is_fading(),
                )
            }
            retained_renderer.request_retained_label_frame(
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
            val retained = retained_renderer.draw_retained_frame_if_available(
                canvas = canvas,
                viewport = viewport,
                options = options,
                target_tile_zoom = tile_zoom,
                fallback_tile_zoom = fallback_zoom,
                now_ms = now_ms,
                label_avoid_rects = label_avoid_rects,
            )
            if (retained != null) {
                retained_renderer.clear_partial_frame()
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = layer_ready,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
                    boundaries_drawn = boundary_raster.boundaries,
                    labels_drawn = retained.labels_drawn,
                    retained_frame_drawn = true,
                    fading = retained_renderer.is_fading(),
                )
            }
        }

        retained_renderer.partial_frame()?.takeIf { frame ->
            frame.matches_exact(
                viewport = viewport,
                next_tile_zoom = tile_zoom,
                next = options,
                current_package_generation = tile_repository.package_generation(),
            )
        }?.let { partial ->
            retained_renderer.draw_retained_frame_with_transition(
                canvas,
                viewport,
                partial,
                now_ms,
                label_avoid_rects,
            )
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = layer_ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = if (layer_ready) 0 else maxOf(requested, missing),
                boundaries_drawn = boundary_raster.boundaries,
                labels_drawn = partial.labels_drawn,
                retained_frame_drawn = true,
                fading = retained_renderer.is_fading(),
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
        retained_renderer.reset()
    }

    override fun close() {
        lifecycle_lock.lock()
        try {
            if (closed) return
            closed = true
            retained_renderer.close()
            boundary_renderer.close()
            tile_repository.close()
            clear_renderer_state(clear_retained = false, clear_boundaries = false)
        } finally {
            lifecycle_lock.unlock()
        }
    }

    private fun clear_renderer_state(
        clear_retained: Boolean = true,
        clear_boundaries: Boolean = true,
    ) {
        if (clear_retained) retained_renderer.clear()
        if (clear_boundaries) boundary_renderer.clear()
    }

    private fun retained_scene_padding_for_viewport(
        viewport: Viewport,
        tile_zoom: Int,
    ): Int = retained_renderer.retained_scene_padding_for_viewport(viewport, tile_zoom)

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
            label_renderer.draw_labels(
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

    private companion object {
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
