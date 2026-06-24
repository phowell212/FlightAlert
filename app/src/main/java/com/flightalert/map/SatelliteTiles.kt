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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
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

internal data class ReferencePrefetchDrainStats(
    var batch_count: Long = 0L,
    var plan_count: Long = 0L,
    var tile_count: Long = 0L,
    var memory_hit_count: Long = 0L,
    var miss_count: Long = 0L,
    var submitted_count: Long = 0L,
    var queued_count: Long = 0L,
    var memory_recheck_count: Long = 0L,
    var stale_count: Long = 0L,
    var superseded_count: Long = 0L
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
    private val request_redraw: () -> Unit
) {
    private val tile_cache = LinkedHashMap<String, Bitmap>(MAX_MEMORY_TILES, 0.75f, true)
    private val tile_loaded_elapsed_ms = mutableMapOf<String, Long>()
    private val interim_tiles = linkedMapOf<String, InterimRasterTile>()
    private val requested_tiles = mutableSetOf<String>()
    private val current_tile_request_generations = mutableMapOf<String, Long>()
    private var current_tile_request_generation = 0L
    private val satellite_tile_worker_id = AtomicInteger()
    private val satellite_tile_task_sequence = AtomicLong()
    private val satellite_tile_executor = ThreadPoolExecutor(
        SATELLITE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_NETWORK_THREADS,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue()
    ) { runnable ->
        Thread(
            runnable,
            "flightalert-satellite-tiles-${satellite_tile_worker_id.incrementAndGet()}"
        ).apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
    private val tile_disk_worker_id = AtomicInteger()
    private val tile_disk_executor = ThreadPoolExecutor(
        1,
        1,
        SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-satellite-tile-disk-${tile_disk_worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
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
    private val reference_motion_coverage_alpha_floor = mutableMapOf<ReferenceTileOverlay, Float>()
    private val reference_lod_prefetch_lock = Any()
    private var reference_lod_prefetch_latest_plans: List<ReferencePrefetchGridPlan> = emptyList()
    private var reference_lod_prefetch_scheduled = false
    private var reference_lod_prefetch_epoch = 0L
    private val reference_tile_worker_id = AtomicInteger()
    private val reference_tile_task_sequence = AtomicLong()

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
    private val tile_destination = RectF()
    private val reference_tile_destination = RectF()
    private val interim_tile_destination = RectF()
    private val interim_tile_test_rect = RectF()
    private val satellite_child_destination = RectF()
    private val tile_decode_options = ThreadLocal.withInitial {
        BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    }
    private var transition_cache_key: String? = null
    private var transition_map_source: TileSource? = null
    private var current_tile_zoom = Int.MIN_VALUE
    private var tile_zoom_direction = 0
    private var last_satellite_buffer_request_key: String? = null
    private var last_satellite_buffer_request_ms = 0L
    private var last_satellite_prefetch_request_key: String? = null
    private var last_satellite_prefetch_request_ms = 0L
    private val local_reference_renderer = LocalReferenceOverlayRenderer(
        context = context,
        paint = paint,
        text_paint = text_paint,
        path = Path(),
        dp = dp,
        sp = sp,
        with_alpha = with_alpha,
        request_redraw = request_redraw
    )

    var debug_last_tile_summary: String = ""
        private set

    @Volatile
    var debug_collect_detail_timing = false
    private var debug_detail_interim_ns = 0L
    private var debug_detail_lower_ns = 0L
    private var debug_detail_upper_ns = 0L
    private var debug_detail_merge_ns = 0L
    private var debug_detail_reference_ns = 0L
    private var debug_detail_reference_plan_ns = 0L
    private var debug_detail_reference_draw_ns = 0L
    private var debug_detail_reference_prefetch_ns = 0L
    private var debug_detail_reference_alpha_ns = 0L
    private var debug_detail_reference_bookkeeping_ns = 0L
    private var debug_detail_reference_generation_ns = 0L
    private var debug_detail_reference_protection_ns = 0L
    private var debug_detail_reference_overlay_count = 0
    private var debug_detail_reference_prefetch_call_count = 0
    private var debug_detail_reference_prefetch_cpu_ns = 0L
    private var debug_detail_reference_prefetch_wait_ns = 0L
    private var debug_detail_reference_prefetch_cpu_missing_count = 0
    private var debug_detail_reference_prefetch_range_ns = 0L
    private var debug_detail_reference_prefetch_enum_ns = 0L
    private var debug_detail_reference_prefetch_memory_lookup_ns = 0L
    private var debug_detail_reference_prefetch_url_ns = 0L
    private var debug_detail_reference_prefetch_submit_ns = 0L
    private var debug_detail_reference_prefetch_tile_count = 0
    private var debug_detail_reference_prefetch_memory_hit_count = 0
    private var debug_detail_reference_prefetch_miss_count = 0
    private var debug_detail_reference_prefetch_submitted_count = 0
    private var debug_detail_reference_prefetch_queued_count = 0
    private var debug_detail_reference_prefetch_queued_same_generation_count = 0
    private var debug_detail_reference_prefetch_queued_recent_generation_count = 0
    private var debug_detail_reference_prefetch_lod_call_count = 0
    private var debug_detail_reference_prefetch_pan_call_count = 0
    private var debug_detail_reference_prefetch_lod_tile_count = 0
    private var debug_detail_reference_prefetch_pan_tile_count = 0
    private var debug_detail_reference_prefetch_lod_submitted_count = 0
    private var debug_detail_reference_prefetch_pan_submitted_count = 0
    private var debug_detail_reference_prefetch_lod_queued_count = 0
    private var debug_detail_reference_prefetch_pan_queued_count = 0
    private var debug_detail_reference_prefetch_lod_max_grid_count = 0
    private var debug_detail_reference_prefetch_pan_max_grid_count = 0
    private var debug_detail_reference_prefetch_memory_recheck_count = 0
    private var debug_detail_reference_prefetch_denied_count = 0
    private var debug_detail_reference_prefetch_max_grid_count = 0
    private val debug_reference_lod_prefetch_async_offered_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_coalesced_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_batch_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_plan_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_tile_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_memory_hit_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_miss_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_submitted_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_queued_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_memory_recheck_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_stale_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_superseded_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_denied_count = AtomicLong()
    private val debug_reference_lod_prefetch_async_ns = AtomicLong()
    private val debug_reference_lod_prefetch_async_max_ns = AtomicLong()
    private var debug_detail_reference_lod_prefetch_async_offered_count = 0L
    private var debug_detail_reference_lod_prefetch_async_coalesced_count = 0L
    private var debug_detail_reference_lod_prefetch_async_batch_count = 0L
    private var debug_detail_reference_lod_prefetch_async_plan_count = 0L
    private var debug_detail_reference_lod_prefetch_async_tile_count = 0L
    private var debug_detail_reference_lod_prefetch_async_memory_hit_count = 0L
    private var debug_detail_reference_lod_prefetch_async_miss_count = 0L
    private var debug_detail_reference_lod_prefetch_async_submitted_count = 0L
    private var debug_detail_reference_lod_prefetch_async_queued_count = 0L
    private var debug_detail_reference_lod_prefetch_async_memory_recheck_count = 0L
    private var debug_detail_reference_lod_prefetch_async_stale_count = 0L
    private var debug_detail_reference_lod_prefetch_async_superseded_count = 0L
    private var debug_detail_reference_lod_prefetch_async_denied_count = 0L
    private var debug_detail_reference_lod_prefetch_async_ns = 0L
    private var debug_detail_reference_lod_prefetch_async_max_ns = 0L
    private var debug_detail_reference_protected_count = 0
    private var debug_detail_reference_previous_protected_count = 0
    private var debug_detail_reference_history_start_count = 0
    private var debug_detail_reference_history_count = 0
    private var debug_detail_reference_history_stale_removed_count = 0
    private var debug_detail_reference_history_overflow_removed_count = 0
    private var debug_detail_local_reference_ns = 0L

    fun draw_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle
    ): String {
        reset_detail_timing()
        val now_ms = SystemClock.elapsedRealtime()
        paint.style = Paint.Style.FILL
        paint.color = style.map_empty
        canvas.drawRect(0f, 0f, viewport.width, viewport.height, paint)

        val lower_tile_zoom = floor(viewport.zoom)
            .toInt()
            .coerceIn(MIN_ZOOM, TileSource.SATELLITE.max_native_zoom)
        val upper_tile_zoom = (lower_tile_zoom + 1)
            .coerceAtMost(TileSource.SATELLITE.max_native_zoom)
        val zoom_fraction = (viewport.zoom - lower_tile_zoom).toFloat().coerceIn(0f, 1f)
        val has_upper_lod = upper_tile_zoom > lower_tile_zoom
        val upper_lod_alpha = if (has_upper_lod) {
            smooth_step(LOD_BLEND_START_FRACTION, LOD_BLEND_END_FRACTION, zoom_fraction)
        } else {
            0f
        }
        val prefetch_upper_lod = has_upper_lod && zoom_fraction >= LOD_PREFETCH_START_FRACTION
        val blend_active = has_upper_lod &&
                upper_lod_alpha > MIN_LAYER_ALPHA &&
                upper_lod_alpha < 1f - MIN_LAYER_ALPHA

        update_tile_zoom_transition(lower_tile_zoom, state)
        val request_generation = begin_tile_request_generation()

        val interim_draw_needed = blend_active ||
                !satellite_tile_grid_fully_available(
                    viewport = viewport,
                    state = state,
                    tile_zoom = lower_tile_zoom
                )
        val interim_drawn = if (interim_draw_needed) {
            val detail_start_ns = detail_timing_start_ns()
            draw_interim_tiles(
                canvas = canvas,
                viewport = viewport,
                state = state,
                now_ms = now_ms
            ).also { debug_detail_interim_ns += detail_timing_elapsed_ns(detail_start_ns) }
        } else {
            false
        }
        val loaded_interim_tiles = ArrayList<InterimRasterTile>()

        val lower_detail_start_ns = detail_timing_start_ns()
        val lower_stats = draw_tile_grid_layer(
            canvas = canvas,
            viewport = viewport,
            state = state,
            style = style,
            tile_zoom = lower_tile_zoom,
            now_ms = now_ms,
            layer_alpha = 1f,
            draw_unavailable_if_missing = true,
            allow_parent_fallback = true,
            allow_child_fallback = true,
            retain_as_interim = true,
            loaded_interim_tiles = loaded_interim_tiles,
            request_generation = request_generation
        ).also { debug_detail_lower_ns += detail_timing_elapsed_ns(lower_detail_start_ns) }

        val upper_detail_start_ns = detail_timing_start_ns()
        val upper_stats = if (has_upper_lod && upper_lod_alpha > MIN_LAYER_ALPHA) {
            draw_tile_grid_layer(
                canvas = canvas,
                viewport = viewport,
                state = state,
                style = style,
                tile_zoom = upper_tile_zoom,
                now_ms = now_ms,
                layer_alpha = upper_lod_alpha,
                draw_unavailable_if_missing = false,
                allow_parent_fallback = false,
                allow_child_fallback = true,
                retain_as_interim = true,
                loaded_interim_tiles = loaded_interim_tiles,
                request_generation = request_generation
            )
        } else {
            if (prefetch_upper_lod) {
                request_satellite_prefetch_grid(
                    viewport = viewport,
                    state = state,
                    tile_zoom = upper_tile_zoom,
                    now_ms = now_ms,
                    request_generation = request_generation
                )
            }
            TileLayerDrawStats(
                visible = 0,
                loaded = 0,
                requested = 0,
                fallback_drawn = 0,
                fading = false
            )
        }.also { debug_detail_upper_ns += detail_timing_elapsed_ns(upper_detail_start_ns) }

        val required_tiles_ready = lower_stats.requested == 0 &&
                (!has_upper_lod || upper_lod_alpha <= MIN_LAYER_ALPHA || upper_stats.requested == 0)

        if (loaded_interim_tiles.isNotEmpty()) {
            val merge_detail_start_ns = detail_timing_start_ns()
            if (blend_active || !required_tiles_ready) {
                merge_interim_tiles(loaded_interim_tiles)
            } else {
                replace_interim_tiles(loaded_interim_tiles)
            }
            debug_detail_merge_ns += detail_timing_elapsed_ns(merge_detail_start_ns)
        }

        val reference_detail_start_ns = detail_timing_start_ns()
        val reference_generation_detail_start_ns = detail_timing_start_ns()
        val reference_request_generation = begin_reference_tile_request_generation()
        debug_detail_reference_generation_ns += detail_timing_elapsed_ns(
            reference_generation_detail_start_ns
        )
        val reference_protection_detail_start_ns = detail_timing_start_ns()
        begin_reference_tile_protection_frame()
        debug_detail_reference_protection_ns += detail_timing_elapsed_ns(
            reference_protection_detail_start_ns
        )
        val label_stats = draw_reference_overlay_layers(
            canvas = canvas,
            viewport = viewport,
            state = state,
            now_ms = now_ms,
            lower_tile_zoom = lower_tile_zoom,
            upper_tile_zoom = upper_tile_zoom,
            has_upper_lod = has_upper_lod,
            upper_lod_alpha = upper_lod_alpha,
            prefetch_upper_lod = prefetch_upper_lod,
            allow_exact_requests = true,
            request_generation = reference_request_generation
        )
        debug_detail_reference_ns += detail_timing_elapsed_ns(reference_detail_start_ns)
        val settings_border_overlay_enabled =
            state.reference_overlay_layers.contains(ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES)
        val local_reference_detail_start_ns = detail_timing_start_ns()
        val local_reference_stats = local_reference_renderer.draw(
            canvas = canvas,
            viewport = viewport,
            enabled = state.map_borders_enabled && !settings_border_overlay_enabled,
            interaction_active = state.interaction_active
        )
        debug_detail_local_reference_ns += detail_timing_elapsed_ns(local_reference_detail_start_ns)

        if (lower_stats.fading || upper_stats.fading || label_stats.fading) {
            request_redraw()
        }

        val visible = lower_stats.visible + upper_stats.visible
        val loaded = lower_stats.loaded + upper_stats.loaded
        val requested = lower_stats.requested + upper_stats.requested
        val fallback_drawn = lower_stats.fallback_drawn + upper_stats.fallback_drawn
        debug_last_tile_summary =
            " mapTiles=$visible loaded=$loaded requested=$requested fallback=$fallback_drawn " +
                    "satLod=${lower_tile_zoom}->${upper_tile_zoom} lodAlpha=${
                        "%.2f".format(
                            Locale.US,
                            upper_lod_alpha
                        )
                    } " +
                    "blend=$blend_active interim=${interim_tiles.size} interimDraw=$interim_drawn labels=${label_stats.loaded}/${label_stats.visible} " +
                    "labelReq=${label_stats.requested} labelFallback=${label_stats.fallback_drawn}" +
                    label_stats.debug_summary + local_reference_stats.summary() + detail_timing_summary() + " "

        return if (lower_stats.requested == 0 && lower_stats.loaded > 0) {
            "${TileSource.SATELLITE.display_name} loaded"
        } else {
            "Loading ${TileSource.SATELLITE.display_name.lowercase(Locale.US)} tiles"
        }
    }

    private fun reset_detail_timing() {
        if (!debug_collect_detail_timing) return
        debug_detail_interim_ns = 0L
        debug_detail_lower_ns = 0L
        debug_detail_upper_ns = 0L
        debug_detail_merge_ns = 0L
        debug_detail_reference_ns = 0L
        debug_detail_reference_plan_ns = 0L
        debug_detail_reference_draw_ns = 0L
        debug_detail_reference_prefetch_ns = 0L
        debug_detail_reference_alpha_ns = 0L
        debug_detail_reference_bookkeeping_ns = 0L
        debug_detail_reference_generation_ns = 0L
        debug_detail_reference_protection_ns = 0L
        debug_detail_reference_overlay_count = 0
        debug_detail_reference_prefetch_call_count = 0
        debug_detail_reference_prefetch_cpu_ns = 0L
        debug_detail_reference_prefetch_wait_ns = 0L
        debug_detail_reference_prefetch_cpu_missing_count = 0
        debug_detail_reference_prefetch_range_ns = 0L
        debug_detail_reference_prefetch_enum_ns = 0L
        debug_detail_reference_prefetch_memory_lookup_ns = 0L
        debug_detail_reference_prefetch_url_ns = 0L
        debug_detail_reference_prefetch_submit_ns = 0L
        debug_detail_reference_prefetch_tile_count = 0
        debug_detail_reference_prefetch_memory_hit_count = 0
        debug_detail_reference_prefetch_miss_count = 0
        debug_detail_reference_prefetch_submitted_count = 0
        debug_detail_reference_prefetch_queued_count = 0
        debug_detail_reference_prefetch_queued_same_generation_count = 0
        debug_detail_reference_prefetch_queued_recent_generation_count = 0
        debug_detail_reference_prefetch_lod_call_count = 0
        debug_detail_reference_prefetch_pan_call_count = 0
        debug_detail_reference_prefetch_lod_tile_count = 0
        debug_detail_reference_prefetch_pan_tile_count = 0
        debug_detail_reference_prefetch_lod_submitted_count = 0
        debug_detail_reference_prefetch_pan_submitted_count = 0
        debug_detail_reference_prefetch_lod_queued_count = 0
        debug_detail_reference_prefetch_pan_queued_count = 0
        debug_detail_reference_prefetch_lod_max_grid_count = 0
        debug_detail_reference_prefetch_pan_max_grid_count = 0
        debug_detail_reference_prefetch_memory_recheck_count = 0
        debug_detail_reference_prefetch_denied_count = 0
        debug_detail_reference_prefetch_max_grid_count = 0
        debug_detail_reference_lod_prefetch_async_offered_count =
            debug_reference_lod_prefetch_async_offered_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_coalesced_count =
            debug_reference_lod_prefetch_async_coalesced_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_batch_count =
            debug_reference_lod_prefetch_async_batch_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_plan_count =
            debug_reference_lod_prefetch_async_plan_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_tile_count =
            debug_reference_lod_prefetch_async_tile_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_memory_hit_count =
            debug_reference_lod_prefetch_async_memory_hit_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_miss_count =
            debug_reference_lod_prefetch_async_miss_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_submitted_count =
            debug_reference_lod_prefetch_async_submitted_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_queued_count =
            debug_reference_lod_prefetch_async_queued_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_memory_recheck_count =
            debug_reference_lod_prefetch_async_memory_recheck_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_stale_count =
            debug_reference_lod_prefetch_async_stale_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_superseded_count =
            debug_reference_lod_prefetch_async_superseded_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_denied_count =
            debug_reference_lod_prefetch_async_denied_count.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_ns =
            debug_reference_lod_prefetch_async_ns.getAndSet(0L)
        debug_detail_reference_lod_prefetch_async_max_ns =
            debug_reference_lod_prefetch_async_max_ns.getAndSet(0L)
        debug_detail_reference_protected_count = 0
        debug_detail_reference_previous_protected_count = 0
        debug_detail_reference_history_start_count = 0
        debug_detail_reference_history_count = 0
        debug_detail_reference_history_stale_removed_count = 0
        debug_detail_reference_history_overflow_removed_count = 0
        debug_detail_local_reference_ns = 0L
    }

    private fun detail_timing_start_ns(): Long {
        return if (debug_collect_detail_timing) SystemClock.elapsedRealtimeNanos() else 0L
    }

    private fun detail_timing_elapsed_ns(start_ns: Long): Long {
        return if (debug_collect_detail_timing && start_ns > 0L) {
            SystemClock.elapsedRealtimeNanos() - start_ns
        } else {
            0L
        }
    }

    private fun detail_timing_summary(): String {
        if (!debug_collect_detail_timing) return ""
        val reference_bucketed_ns = debug_detail_reference_plan_ns +
                debug_detail_reference_draw_ns +
                debug_detail_reference_prefetch_ns +
                debug_detail_reference_alpha_ns +
                debug_detail_reference_bookkeeping_ns +
                debug_detail_reference_generation_ns +
                debug_detail_reference_protection_ns
        val reference_other_ns =
            (debug_detail_reference_ns - reference_bucketed_ns).coerceAtLeast(0L)
        return " mapDetail interim=${debug_detail_interim_ns.ms_debug()} lower=${debug_detail_lower_ns.ms_debug()} " +
                "upper=${debug_detail_upper_ns.ms_debug()} merge=${debug_detail_merge_ns.ms_debug()} " +
                "reference=${debug_detail_reference_ns.ms_debug()} refPlan=${debug_detail_reference_plan_ns.ms_debug()} " +
                "refDraw=${debug_detail_reference_draw_ns.ms_debug()} refPrefetch=${debug_detail_reference_prefetch_ns.ms_debug()} " +
                "refAlpha=${debug_detail_reference_alpha_ns.ms_debug()} refBook=${debug_detail_reference_bookkeeping_ns.ms_debug()} " +
                "refGen=${debug_detail_reference_generation_ns.ms_debug()} refProtect=${debug_detail_reference_protection_ns.ms_debug()} " +
                "refOther=${reference_other_ns.ms_debug()} refOvl=$debug_detail_reference_overlay_count " +
                "refPf=$debug_detail_reference_prefetch_call_count refProt=$debug_detail_reference_protected_count " +
                "refPfCpu=${debug_detail_reference_prefetch_cpu_ns.ms_debug()} " +
                "refPfWait=${debug_detail_reference_prefetch_wait_ns.ms_debug()} " +
                "refPfCpuMiss=$debug_detail_reference_prefetch_cpu_missing_count " +
                "refPfRange=${debug_detail_reference_prefetch_range_ns.ms_debug()} " +
                "refPfEnum=${debug_detail_reference_prefetch_enum_ns.ms_debug()} " +
                "refPfMemLookup=${debug_detail_reference_prefetch_memory_lookup_ns.ms_debug()} " +
                "refPfUrl=${debug_detail_reference_prefetch_url_ns.ms_debug()} " +
                "refPfSubmitNs=${debug_detail_reference_prefetch_submit_ns.ms_debug()} " +
                "refPfTiles=$debug_detail_reference_prefetch_tile_count " +
                "refPfMem=$debug_detail_reference_prefetch_memory_hit_count " +
                "refPfMiss=$debug_detail_reference_prefetch_miss_count " +
                "refPfSubmit=$debug_detail_reference_prefetch_submitted_count " +
                "refPfQueued=$debug_detail_reference_prefetch_queued_count " +
                "refPfQSame=$debug_detail_reference_prefetch_queued_same_generation_count " +
                "refPfQRecent=$debug_detail_reference_prefetch_queued_recent_generation_count " +
                "refPfLod=$debug_detail_reference_prefetch_lod_call_count " +
                "refPfPan=$debug_detail_reference_prefetch_pan_call_count " +
                "refPfLodTiles=$debug_detail_reference_prefetch_lod_tile_count " +
                "refPfPanTiles=$debug_detail_reference_prefetch_pan_tile_count " +
                "refPfLodSub=$debug_detail_reference_prefetch_lod_submitted_count " +
                "refPfPanSub=$debug_detail_reference_prefetch_pan_submitted_count " +
                "refPfLodQ=$debug_detail_reference_prefetch_lod_queued_count " +
                "refPfPanQ=$debug_detail_reference_prefetch_pan_queued_count " +
                "refPfLodMaxGrid=$debug_detail_reference_prefetch_lod_max_grid_count " +
                "refPfPanMaxGrid=$debug_detail_reference_prefetch_pan_max_grid_count " +
                "refPfMem2=$debug_detail_reference_prefetch_memory_recheck_count " +
                "refPfDeny=$debug_detail_reference_prefetch_denied_count " +
                "refPfMaxGrid=$debug_detail_reference_prefetch_max_grid_count " +
                "refPfAsyncOffer=$debug_detail_reference_lod_prefetch_async_offered_count " +
                "refPfAsyncCoalesce=$debug_detail_reference_lod_prefetch_async_coalesced_count " +
                "refPfAsyncBatch=$debug_detail_reference_lod_prefetch_async_batch_count " +
                "refPfAsyncPlan=$debug_detail_reference_lod_prefetch_async_plan_count " +
                "refPfAsyncTiles=$debug_detail_reference_lod_prefetch_async_tile_count " +
                "refPfAsyncMem=$debug_detail_reference_lod_prefetch_async_memory_hit_count " +
                "refPfAsyncMiss=$debug_detail_reference_lod_prefetch_async_miss_count " +
                "refPfAsyncSubmit=$debug_detail_reference_lod_prefetch_async_submitted_count " +
                "refPfAsyncQueued=$debug_detail_reference_lod_prefetch_async_queued_count " +
                "refPfAsyncMem2=$debug_detail_reference_lod_prefetch_async_memory_recheck_count " +
                "refPfAsyncStale=$debug_detail_reference_lod_prefetch_async_stale_count " +
                "refPfAsyncSuper=$debug_detail_reference_lod_prefetch_async_superseded_count " +
                "refPfAsyncDeny=$debug_detail_reference_lod_prefetch_async_denied_count " +
                "refPfAsyncNs=${debug_detail_reference_lod_prefetch_async_ns.ms_debug()} " +
                "refPfAsyncMaxNs=${debug_detail_reference_lod_prefetch_async_max_ns.ms_debug()} " +
                "refPrev=$debug_detail_reference_previous_protected_count " +
                "refHistStart=$debug_detail_reference_history_start_count refHist=$debug_detail_reference_history_count " +
                "refStale=$debug_detail_reference_history_stale_removed_count " +
                "refOverflow=$debug_detail_reference_history_overflow_removed_count " +
                "localRef=${debug_detail_local_reference_ns.ms_debug()}"
    }

    private fun Long.ms_debug(): String {
        return String.format(Locale.US, "%.2fms", this / 1_000_000.0)
    }

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
            tile_loaded_elapsed_ms.clear()
        }
        synchronized(reference_tile_cache) {
            reference_tile_cache.clear()
            reference_tile_loaded_elapsed_ms.clear()
        }
        interim_tiles.clear()
        synchronized(requested_tiles) { requested_tiles.clear() }
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
        reference_motion_coverage_alpha_floor.clear()
        local_reference_renderer.clear()
        transition_cache_key = null
        transition_map_source = null
        current_tile_zoom = Int.MIN_VALUE
        tile_zoom_direction = 0
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
        last_satellite_prefetch_request_key = null
        last_satellite_prefetch_request_ms = 0L
        debug_last_tile_summary = ""
    }

    fun reset_transitions() {
        transition_cache_key = null
        transition_map_source = null
        current_tile_zoom = Int.MIN_VALUE
        tile_zoom_direction = 0
        last_satellite_buffer_request_key = null
        last_satellite_buffer_request_ms = 0L
        last_satellite_prefetch_request_key = null
        last_satellite_prefetch_request_ms = 0L
        debug_last_tile_summary = ""
    }

    fun shutdown() {
        satellite_tile_executor.shutdownNow()
        tile_disk_executor.shutdownNow()
        reference_tile_executor.shutdownNow()
        clear()
    }

    private fun draw_tile_grid_layer(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        style: MapTileRenderStyle,
        tile_zoom: Int,
        now_ms: Long,
        layer_alpha: Float,
        draw_unavailable_if_missing: Boolean,
        allow_parent_fallback: Boolean,
        allow_child_fallback: Boolean,
        retain_as_interim: Boolean,
        loaded_interim_tiles: MutableList<InterimRasterTile>,
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

        request_satellite_buffer_tiles(
            tile_zoom = tile_zoom,
            first_tile_x = first_tile_x,
            last_tile_x = last_tile_x,
            first_tile_y = first_tile_y,
            last_tile_y = last_tile_y,
            max_tile = max_tile,
            state = state,
            now_ms = now_ms,
            request_generation = request_generation
        )

        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val layer_visible = layer_alpha > MIN_LAYER_ALPHA

        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                mark_current_tile_request(key, request_generation)
                val screen_x = (tx_raw * TILE_SIZE * tile_to_viewport_scale - left_world).toFloat()
                val screen_y = (ty * TILE_SIZE * tile_to_viewport_scale - top_world).toFloat()
                val tile_size_on_screen = (TILE_SIZE * tile_to_viewport_scale).toFloat()
                tile_destination.set(
                    screen_x,
                    screen_y,
                    screen_x + tile_size_on_screen,
                    screen_y + tile_size_on_screen
                )
                visible++

                val bitmap = tile_bitmap(tile_zoom, tx, ty, key, state)
                if (bitmap != null) {
                    if (retain_as_interim) {
                        loaded_interim_tiles += InterimRasterTile(
                            cache_key = state.cache_key,
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            bitmap = bitmap,
                            last_used_ms = now_ms
                        )
                    }
                    loaded++
                    if (layer_visible) {
                        val load_alpha = satellite_tile_load_alpha(key, now_ms)
                        val fallback_underlay_drawn =
                            if (load_alpha < 0.999f && layer_alpha >= 0.999f) {
                                draw_satellite_fallback_tile(
                                    canvas = canvas,
                                    z = tile_zoom,
                                    x = tx,
                                    y = ty,
                                    state = state,
                                    destination = tile_destination,
                                    alpha = layer_alpha,
                                    allow_parent_fallback = allow_parent_fallback,
                                    allow_child_fallback = allow_child_fallback
                                )
                            } else {
                                false
                            }
                        val tile_alpha = if (fallback_underlay_drawn) load_alpha else 1f
                        val draw_alpha = (layer_alpha * tile_alpha).coerceIn(0f, 1f)
                        if (draw_alpha > MIN_LAYER_ALPHA) {
                            draw_tile_bitmap(canvas, bitmap, null, tile_destination, draw_alpha)
                        }
                        if (fallback_underlay_drawn && load_alpha < 0.999f) fading = true
                    }
                } else {
                    requested++
                    request_satellite_parent_tiles(tile_zoom, tx, ty, state, request_generation)
                    request_tile(
                        z = tile_zoom,
                        x = tx,
                        y = ty,
                        key = key,
                        state = state,
                        request_generation = request_generation,
                        request_priority = SATELLITE_TILE_REQUEST_PRIORITY_EXACT
                    )

                    if (layer_visible) {
                        if (
                            draw_satellite_fallback_tile(
                                canvas = canvas,
                                z = tile_zoom,
                                x = tx,
                                y = ty,
                                state = state,
                                destination = tile_destination,
                                alpha = layer_alpha,
                                allow_parent_fallback = allow_parent_fallback,
                                allow_child_fallback = allow_child_fallback
                            )
                        ) {
                            fallback_drawn++
                        } else if (draw_unavailable_if_missing &&
                            !retained_interim_intersects_destination(
                                viewport,
                                state.cache_key,
                                tile_destination
                            )
                        ) {
                            draw_unavailable_tile(
                                canvas,
                                screen_x,
                                screen_y,
                                tile_size_on_screen,
                                style
                            )
                        }
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

    private fun satellite_tile_grid_fully_available(
        viewport: Viewport,
        state: MapTileRenderState,
        tile_zoom: Int
    ): Boolean {
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
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                if (tile_bitmap(tile_zoom, tx, ty, key, state) == null) return false
            }
        }
        return true
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
        allow_exact_requests: Boolean,
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

        var visible = 0
        var loaded = 0
        var requested = 0
        var fallback_drawn = 0
        var fading = false
        val debug_parts = ArrayList<String>(overlays.size)
        val deferred_lod_prefetch_plans = ArrayList<ReferencePrefetchGridPlan>(overlays.size)

        for (overlay in overlays) {
            val plan_detail_start_ns = detail_timing_start_ns()
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
            debug_detail_reference_plan_ns += detail_timing_elapsed_ns(plan_detail_start_ns)
            if (plan == null) continue
            debug_detail_reference_overlay_count++
            val alpha_detail_start_ns = detail_timing_start_ns()
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
                coverage = plan.coverage
            )
            val draw_alpha = overlay_alpha * coverage_alpha
            val requests_allowed_for_zoom = reference_overlay_requests_allowed(
                overlay = overlay,
                viewport_zoom = viewport.zoom,
                overlay_alpha = overlay_alpha
            )
            debug_detail_reference_alpha_ns += detail_timing_elapsed_ns(alpha_detail_start_ns)
            if (!requests_allowed_for_zoom && overlay_alpha <= MIN_LAYER_ALPHA) {
                continue
            }
            var overlay_visible = 0
            var overlay_loaded = 0
            var overlay_requested = 0
            var overlay_fallback_drawn = 0
            val draw_detail_start_ns = detail_timing_start_ns()
            val draw_stats = draw_reference_overlay_grid(
                canvas = canvas,
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = plan.draw_tile_zoom,
                now_ms = now_ms,
                layer_alpha = draw_alpha,
                allow_exact_requests = allow_exact_requests && requests_allowed_for_zoom,
                allow_parent_requests = requests_allowed_for_zoom,
                request_priority_base = REFERENCE_TILE_REQUEST_PRIORITY_EXACT,
                request_stale_generation_tolerance = REFERENCE_TILE_REQUEST_STALE_GENERATIONS,
                request_generation = request_generation
            )
            debug_detail_reference_draw_ns += detail_timing_elapsed_ns(draw_detail_start_ns)
            overlay_visible += draw_stats.visible
            overlay_loaded += draw_stats.loaded
            overlay_requested += draw_stats.requested
            overlay_fallback_drawn += draw_stats.fallback_drawn
            fading = fading || draw_stats.fading

            val prefetch_detail_start_ns = detail_timing_start_ns()
            request_reference_overlay_prefetches(
                viewport = viewport,
                state = state,
                overlay = overlay,
                plan = plan,
                allow_exact_requests = allow_exact_requests,
                requests_allowed_for_zoom = requests_allowed_for_zoom,
                request_generation = request_generation,
                deferred_lod_prefetch_plans = deferred_lod_prefetch_plans
            )
            debug_detail_reference_prefetch_ns += detail_timing_elapsed_ns(prefetch_detail_start_ns)
            val bookkeeping_detail_start_ns = detail_timing_start_ns()
            visible += overlay_visible
            loaded += overlay_loaded
            requested += overlay_requested
            fallback_drawn += overlay_fallback_drawn
            debug_parts += " ${reference_overlay_debug_name(overlay)}=$overlay_loaded/$overlay_visible" +
                    " req=$overlay_requested fallback=$overlay_fallback_drawn lod=${plan.draw_tile_zoom}" +
                    " ready=${plan.coverage.ready}/${plan.coverage.visible}" +
                    " center=${plan.coverage.center_ready}/${plan.coverage.center_visible}" +
                    " visual=${plan.coverage.visual_ready}/${plan.coverage.visible}" +
                    " centerVisual=${plan.coverage.center_visual_ready}/${plan.coverage.center_visible}" +
                    " alpha=${draw_alpha.alpha_debug()} coverageAlpha=${coverage_alpha.alpha_debug()}" +
                    " rawCoverageAlpha=${raw_coverage_alpha.alpha_debug()}"
            debug_detail_reference_bookkeeping_ns += detail_timing_elapsed_ns(
                bookkeeping_detail_start_ns
            )
        }
        offer_reference_lod_prefetch_batch(deferred_lod_prefetch_plans)

        return TileLayerDrawStats(
            visible = visible,
            loaded = loaded,
            requested = requested,
            fallback_drawn = fallback_drawn,
            fading = fading,
            debug_summary = debug_parts.joinToString(separator = "")
        )
    }

    private fun request_reference_overlay_prefetches(
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        plan: ReferenceOverlayDrawPlan,
        allow_exact_requests: Boolean,
        requests_allowed_for_zoom: Boolean,
        request_generation: Long,
        deferred_lod_prefetch_plans: MutableList<ReferencePrefetchGridPlan>?
    ) {
        for (prefetch_tile_zoom in plan.prefetch_tile_zooms) {
            debug_detail_reference_prefetch_call_count++
            debug_detail_reference_prefetch_lod_call_count++
            val prefetch_plan = build_reference_prefetch_grid_plan(
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = prefetch_tile_zoom,
                prefetch_kind = REFERENCE_PREFETCH_KIND_LOD,
                allow_exact_requests = allow_exact_requests && requests_allowed_for_zoom,
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
                request_stale_generation_tolerance = REFERENCE_TILE_PREFETCH_STALE_GENERATIONS,
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
            debug_detail_reference_prefetch_call_count++
            debug_detail_reference_prefetch_pan_call_count++
            request_reference_overlay_prefetch_grid(
                viewport = viewport,
                state = state,
                overlay = overlay,
                tile_zoom = plan.draw_tile_zoom,
                prefetch_kind = REFERENCE_PREFETCH_KIND_PAN,
                allow_exact_requests = allow_exact_requests && requests_allowed_for_zoom,
                tile_buffer = pan_prefetch_buffer,
                request_priority_base = REFERENCE_TILE_REQUEST_PRIORITY_PAN_PREFETCH,
                request_stale_generation_tolerance = REFERENCE_TILE_PAN_PREFETCH_STALE_GENERATIONS,
                request_generation = request_generation
            )
        }
    }

    private fun build_reference_prefetch_grid_plan(
        viewport: Viewport,
        state: MapTileRenderState,
        overlay: ReferenceTileOverlay,
        tile_zoom: Int,
        @Suppress("UNUSED_PARAMETER") prefetch_kind: Int,
        allow_exact_requests: Boolean,
        tile_buffer: Int,
        request_priority_base: Int,
        request_stale_generation_tolerance: Long,
        request_generation: Long
    ): ReferencePrefetchGridPlan? {
        if (!allow_exact_requests) {
            if (debug_collect_detail_timing) {
                debug_detail_reference_prefetch_denied_count++
                debug_reference_lod_prefetch_async_denied_count.incrementAndGet()
            }
            return null
        }
        val collect_detail = debug_collect_detail_timing
        val range_detail_start_ns = if (collect_detail) detail_timing_start_ns() else 0L
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
        if (collect_detail) {
            debug_detail_reference_prefetch_range_ns += detail_timing_elapsed_ns(
                range_detail_start_ns
            )
        }
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
            request_stale_generation_tolerance = request_stale_generation_tolerance,
            request_generation = request_generation
        )
    }

    private fun offer_reference_lod_prefetch_batch(plans: List<ReferencePrefetchGridPlan>) {
        if (plans.isEmpty()) return
        val should_schedule = synchronized(reference_lod_prefetch_lock) {
            val epoch = reference_lod_prefetch_epoch + 1L
            reference_lod_prefetch_epoch = epoch
            if (debug_collect_detail_timing) {
                debug_reference_lod_prefetch_async_offered_count.addAndGet(plans.size.toLong())
                if (reference_lod_prefetch_latest_plans.isNotEmpty()) {
                    debug_reference_lod_prefetch_async_coalesced_count.addAndGet(
                        reference_lod_prefetch_latest_plans.size.toLong()
                    )
                }
            }
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
        val collect_detail = debug_collect_detail_timing
        val batch_start_ns = if (collect_detail) SystemClock.elapsedRealtimeNanos() else 0L
        val stats = if (collect_detail) {
            ReferencePrefetchDrainStats(
                batch_count = 1L,
                plan_count = plans.size.toLong()
            )
        } else {
            null
        }
        for (plan in plans) {
            if (!reference_lod_prefetch_epoch_current(plan.epoch)) {
                stats?.let { it.superseded_count++ }
                break
            }
            if (!reference_request_generation_recent(
                    generation = plan.request_generation,
                    max_generation_age = plan.request_stale_generation_tolerance
                )
            ) {
                stats?.let { it.stale_count++ }
                continue
            }
            if (!request_reference_lod_prefetch_grid_from_plan(plan, stats)) break
        }
        if (collect_detail && stats != null) {
            val elapsed_ns = (SystemClock.elapsedRealtimeNanos() - batch_start_ns).coerceAtLeast(0L)
            publish_reference_lod_prefetch_async_stats(stats, elapsed_ns)
        }
    }

    private fun request_reference_lod_prefetch_grid_from_plan(
        plan: ReferencePrefetchGridPlan,
        stats: ReferencePrefetchDrainStats?
    ): Boolean {
        for (ty in plan.first_tile_y..plan.last_tile_y) {
            if (ty !in 0 until plan.max_tile) continue
            if (!reference_lod_prefetch_epoch_current(plan.epoch)) {
                stats?.let { it.superseded_count++ }
                return false
            }
            if (!reference_request_generation_recent(
                    generation = plan.request_generation,
                    max_generation_age = plan.request_stale_generation_tolerance
                )
            ) {
                stats?.let { it.stale_count++ }
                return true
            }
            for (tx_raw in plan.first_tile_x..plan.last_tile_x) {
                stats?.let { it.tile_count++ }
                val tx = ((tx_raw % plan.max_tile) + plan.max_tile) % plan.max_tile
                val key = "${plan.cache_key}/${plan.tile_zoom}/$tx/$ty"
                if (reference_tile_bitmap(key) != null) {
                    stats?.let { it.memory_hit_count++ }
                    continue
                }
                stats?.let { it.miss_count++ }
                val admission = request_reference_tile(
                    z = plan.tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    cache_key = plan.cache_key,
                    url = plan.overlay.tile_url(plan.tile_zoom, tx, ty),
                    user_agent = plan.user_agent,
                    request_generation = plan.request_generation,
                    request_priority = plan.request_priority,
                    request_stale_generation_tolerance = plan.request_stale_generation_tolerance,
                    redraw_when_loaded = false
                )
                if (stats != null) {
                    when (admission) {
                        REFERENCE_TILE_REQUEST_ADMITTED ->
                            stats.submitted_count++

                        REFERENCE_TILE_REQUEST_QUEUED,
                        REFERENCE_TILE_REQUEST_QUEUED_SAME_GENERATION,
                        REFERENCE_TILE_REQUEST_QUEUED_RECENT_GENERATION ->
                            stats.queued_count++

                        REFERENCE_TILE_REQUEST_MEMORY_HIT ->
                            stats.memory_recheck_count++
                    }
                }
            }
        }
        return true
    }

    private fun publish_reference_lod_prefetch_async_stats(
        stats: ReferencePrefetchDrainStats,
        elapsed_ns: Long
    ) {
        debug_reference_lod_prefetch_async_batch_count.addAndGet(stats.batch_count)
        debug_reference_lod_prefetch_async_plan_count.addAndGet(stats.plan_count)
        debug_reference_lod_prefetch_async_tile_count.addAndGet(stats.tile_count)
        debug_reference_lod_prefetch_async_memory_hit_count.addAndGet(stats.memory_hit_count)
        debug_reference_lod_prefetch_async_miss_count.addAndGet(stats.miss_count)
        debug_reference_lod_prefetch_async_submitted_count.addAndGet(stats.submitted_count)
        debug_reference_lod_prefetch_async_queued_count.addAndGet(stats.queued_count)
        debug_reference_lod_prefetch_async_memory_recheck_count.addAndGet(stats.memory_recheck_count)
        debug_reference_lod_prefetch_async_stale_count.addAndGet(stats.stale_count)
        debug_reference_lod_prefetch_async_superseded_count.addAndGet(stats.superseded_count)
        debug_reference_lod_prefetch_async_ns.addAndGet(elapsed_ns)
        update_reference_lod_prefetch_async_max_ns(elapsed_ns)
    }

    private fun reference_lod_prefetch_epoch_current(epoch: Long): Boolean {
        return synchronized(reference_lod_prefetch_lock) {
            epoch == reference_lod_prefetch_epoch
        }
    }

    private fun reference_request_generation_recent(
        generation: Long,
        max_generation_age: Long
    ): Boolean {
        return synchronized(requested_reference_tiles) {
            current_reference_tile_request_generation - generation <= max_generation_age
        }
    }

    private fun update_reference_lod_prefetch_async_max_ns(elapsed_ns: Long) {
        while (true) {
            val current = debug_reference_lod_prefetch_async_max_ns.get()
            if (elapsed_ns <= current) return
            if (debug_reference_lod_prefetch_async_max_ns.compareAndSet(current, elapsed_ns)) return
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
        coverage: ReferenceTileCoverage
    ): Float {
        if (overlay != ReferenceTileOverlay.WORLD_TRANSPORTATION || overlay_alpha <= MIN_LAYER_ALPHA) {
            if (overlay_alpha <= MIN_LAYER_ALPHA) {
                reference_motion_coverage_alpha_floor.remove(overlay)
            } else {
                reference_motion_coverage_alpha_floor[overlay] = coverage_alpha
            }
            return coverage_alpha
        }
        if (!interaction_active) {
            reference_motion_coverage_alpha_floor[overlay] = coverage_alpha
            return coverage_alpha
        }
        val previous_alpha = reference_motion_coverage_alpha_floor[overlay] ?: coverage_alpha
        val stable_alpha = maxOf(coverage_alpha, previous_alpha)
        reference_motion_coverage_alpha_floor[overlay] = stable_alpha
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
        prefetch_kind: Int,
        allow_exact_requests: Boolean,
        tile_buffer: Int,
        request_priority_base: Int,
        request_stale_generation_tolerance: Long,
        request_generation: Long
    ) {
        if (!allow_exact_requests) {
            if (debug_collect_detail_timing) debug_detail_reference_prefetch_denied_count++
            return
        }
        val collect_detail = debug_collect_detail_timing
        val range_detail_start_ns = if (collect_detail) detail_timing_start_ns() else 0L
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
        if (collect_detail) {
            debug_detail_reference_prefetch_range_ns += detail_timing_elapsed_ns(
                range_detail_start_ns
            )
        }

        var grid_tile_count = 0
        val grid_detail_start_ns = if (collect_detail) detail_timing_start_ns() else 0L
        var grid_memory_lookup_ns = 0L
        var grid_url_ns = 0L
        var grid_submit_ns = 0L
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                if (collect_detail) {
                    debug_detail_reference_prefetch_tile_count++
                    grid_tile_count++
                    if (prefetch_kind == REFERENCE_PREFETCH_KIND_PAN) {
                        debug_detail_reference_prefetch_pan_tile_count++
                    } else {
                        debug_detail_reference_prefetch_lod_tile_count++
                    }
                }
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${overlay.cache_key}/$tile_zoom/$tx/$ty"
                val memory_bitmap = if (collect_detail) {
                    val memory_lookup_start_ns = detail_timing_start_ns()
                    reference_tile_bitmap(key).also {
                        grid_memory_lookup_ns += detail_timing_elapsed_ns(memory_lookup_start_ns)
                    }
                } else {
                    reference_tile_bitmap(key)
                }
                if (memory_bitmap != null) {
                    if (collect_detail) debug_detail_reference_prefetch_memory_hit_count++
                    continue
                }
                if (collect_detail) debug_detail_reference_prefetch_miss_count++
                val url_start_ns = if (collect_detail) detail_timing_start_ns() else 0L
                val url = overlay.tile_url(tile_zoom, tx, ty)
                if (collect_detail) {
                    grid_url_ns += detail_timing_elapsed_ns(url_start_ns)
                }
                val submit_start_ns = if (collect_detail) detail_timing_start_ns() else 0L
                val admission = request_reference_tile(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    cache_key = overlay.cache_key,
                    url = url,
                    user_agent = state.user_agent,
                    request_generation = request_generation,
                    request_priority = request_priority,
                    request_stale_generation_tolerance = request_stale_generation_tolerance,
                    redraw_when_loaded = false
                )
                if (collect_detail) {
                    grid_submit_ns += detail_timing_elapsed_ns(submit_start_ns)
                }
                if (collect_detail) {
                    when (admission) {
                        REFERENCE_TILE_REQUEST_ADMITTED -> {
                            debug_detail_reference_prefetch_submitted_count++
                            if (prefetch_kind == REFERENCE_PREFETCH_KIND_PAN) {
                                debug_detail_reference_prefetch_pan_submitted_count++
                            } else {
                                debug_detail_reference_prefetch_lod_submitted_count++
                            }
                        }

                        REFERENCE_TILE_REQUEST_QUEUED -> {
                            debug_detail_reference_prefetch_queued_count++
                            if (prefetch_kind == REFERENCE_PREFETCH_KIND_PAN) {
                                debug_detail_reference_prefetch_pan_queued_count++
                            } else {
                                debug_detail_reference_prefetch_lod_queued_count++
                            }
                        }

                        REFERENCE_TILE_REQUEST_QUEUED_SAME_GENERATION -> {
                            debug_detail_reference_prefetch_queued_count++
                            debug_detail_reference_prefetch_queued_same_generation_count++
                            if (prefetch_kind == REFERENCE_PREFETCH_KIND_PAN) {
                                debug_detail_reference_prefetch_pan_queued_count++
                            } else {
                                debug_detail_reference_prefetch_lod_queued_count++
                            }
                        }

                        REFERENCE_TILE_REQUEST_QUEUED_RECENT_GENERATION -> {
                            debug_detail_reference_prefetch_queued_count++
                            debug_detail_reference_prefetch_queued_recent_generation_count++
                            if (prefetch_kind == REFERENCE_PREFETCH_KIND_PAN) {
                                debug_detail_reference_prefetch_pan_queued_count++
                            } else {
                                debug_detail_reference_prefetch_lod_queued_count++
                            }
                        }

                        REFERENCE_TILE_REQUEST_MEMORY_HIT -> debug_detail_reference_prefetch_memory_recheck_count++
                    }
                }
            }
        }
        if (collect_detail) {
            val grid_total_ns = detail_timing_elapsed_ns(grid_detail_start_ns)
            debug_detail_reference_prefetch_memory_lookup_ns += grid_memory_lookup_ns
            debug_detail_reference_prefetch_url_ns += grid_url_ns
            debug_detail_reference_prefetch_submit_ns += grid_submit_ns
            debug_detail_reference_prefetch_enum_ns +=
                (grid_total_ns - grid_memory_lookup_ns - grid_url_ns - grid_submit_ns).coerceAtLeast(
                    0L
                )
        }
        if (collect_detail && grid_tile_count > debug_detail_reference_prefetch_max_grid_count) {
            debug_detail_reference_prefetch_max_grid_count = grid_tile_count
        }
        if (collect_detail && prefetch_kind == REFERENCE_PREFETCH_KIND_PAN &&
            grid_tile_count > debug_detail_reference_prefetch_pan_max_grid_count
        ) {
            debug_detail_reference_prefetch_pan_max_grid_count = grid_tile_count
        }
        if (collect_detail && prefetch_kind == REFERENCE_PREFETCH_KIND_LOD &&
            grid_tile_count > debug_detail_reference_prefetch_lod_max_grid_count
        ) {
            debug_detail_reference_prefetch_lod_max_grid_count = grid_tile_count
        }
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
        request_priority_base: Int,
        request_stale_generation_tolerance: Long,
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
                                base_priority = request_priority_base
                            ),
                            request_stale_generation_tolerance = request_stale_generation_tolerance,
                            redraw_when_loaded = true
                        )
                    }
                    if (allow_parent_requests && parent_request_allowed) {
                        request_reference_parent_tiles(
                            z = tile_zoom,
                            x = tx,
                            y = ty,
                            overlay = overlay,
                            user_agent = state.user_agent,
                            request_generation = request_generation,
                            request_stale_generation_tolerance = REFERENCE_TILE_PARENT_STALE_GENERATIONS
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

    private fun reference_overlay_debug_name(overlay: ReferenceTileOverlay): String {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_TRANSPORTATION -> "roads"
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES -> "borders"
        }
    }

    private fun Float.alpha_debug(): String {
        return String.format(Locale.US, "%.2f", this)
    }

    private fun reference_tile_bitmap(key: String): Bitmap? {
        return synchronized(reference_tile_cache) { reference_tile_cache[key] }
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
        request_generation: Long,
        request_stale_generation_tolerance: Long
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
                request_stale_generation_tolerance = request_stale_generation_tolerance,
                redraw_when_loaded = reference_parent_fallback_depth(overlay) > 0
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
        redraw_when_loaded: Boolean
    ): Int {
        mark_current_reference_tile_request(key, request_generation)
        if (reference_tile_bitmap(key) != null) return REFERENCE_TILE_REQUEST_MEMORY_HIT
        var task_generation = request_generation
        synchronized(requested_reference_tiles) {
            if (redraw_when_loaded) requested_reference_tile_redraws += key
            val queued_generation = requested_reference_tiles[key]
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
                        if (should_skip_stale_reference_tile_request(
                                key,
                                request_stale_generation_tolerance
                            )
                        ) {
                            skipped_request = true
                            return@tileTask
                        }
                        if (should_skip_superseded_reference_tile_request(key, task_generation)) {
                            skipped_request = true
                            return@tileTask
                        }
                        if (reference_tile_bitmap(key) != null) {
                            skipped_request = true
                            return@tileTask
                        }
                        val file = reference_tile_file(z, x, y, cache_key)
                        if (file.exists() && file.length() > 0L) {
                            val fresh =
                                System.currentTimeMillis() - file.lastModified() < REFERENCE_TILE_CACHE_MAX_AGE_MS
                            val bitmap =
                                BitmapFactory.decodeFile(file.absolutePath, decode_options())
                            if (bitmap != null && reference_overlay_tile_is_safe(bitmap)) {
                                synchronized(reference_tile_cache) {
                                    put_reference_tile_in_memory(
                                        key,
                                        bitmap,
                                        fade = false
                                    )
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
                                synchronized(reference_tile_cache) {
                                    put_reference_tile_in_memory(
                                        key,
                                        bitmap,
                                        fade = false
                                    )
                                }
                                if (should_redraw_reference_tile_request(key, redraw_when_loaded)) {
                                    redrew_when_loaded = true
                                    request_redraw()
                                }
                                write_tile_file_async(file, bytes)
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
            val history_start_count = current_reference_tile_request_generations.size
            var stale_removed_count = 0
            var overflow_removed_count = 0
            if (current_reference_tile_request_generations.size > REFERENCE_TILE_REQUEST_HISTORY_MAX) {
                val stale_before =
                    current_reference_tile_request_generation - REFERENCE_TILE_REQUEST_HISTORY_AGE
                val iterator = current_reference_tile_request_generations.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value < stale_before) {
                        iterator.remove()
                        stale_removed_count++
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
                        if (current_reference_tile_request_generations.remove(oldest_key) != null) {
                            overflow_removed_count++
                        }
                    }
                }
            }
            if (debug_collect_detail_timing) {
                debug_detail_reference_history_start_count = history_start_count
                debug_detail_reference_history_count =
                    current_reference_tile_request_generations.size
                debug_detail_reference_history_stale_removed_count = stale_removed_count
                debug_detail_reference_history_overflow_removed_count = overflow_removed_count
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

    private fun put_reference_tile_in_memory(key: String, bitmap: Bitmap, fade: Boolean) {
        reference_tile_cache[key] = bitmap
        if (fade) {
            reference_tile_loaded_elapsed_ms[key] = SystemClock.elapsedRealtime()
        } else {
            reference_tile_loaded_elapsed_ms.remove(key)
        }
        while (reference_tile_cache.size > MAX_REFERENCE_MEMORY_TILES) {
            val first_key =
                reference_tile_cache.keys.firstOrNull { !is_protected_reference_tile(it) }
                    ?: reference_tile_cache.keys.firstOrNull()
                    ?: break
            reference_tile_cache.remove(first_key)
            reference_tile_loaded_elapsed_ms.remove(first_key)
        }
    }

    private fun begin_reference_tile_protection_frame() {
        synchronized(protected_reference_tile_keys) {
            val protected_count = protected_reference_tile_keys.size
            previous_protected_reference_tile_keys.clear()
            previous_protected_reference_tile_keys.addAll(protected_reference_tile_keys)
            if (debug_collect_detail_timing) {
                debug_detail_reference_protected_count = protected_count
                debug_detail_reference_previous_protected_count =
                    previous_protected_reference_tile_keys.size
            }
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
                val alpha = (bitmap[x, y] ushr 24) and 0xFF
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

    // Memory cache is first. Interim tiles are treated as a retained raster source so a zoom-level
    // crossfade can keep using the previous complete imagery even after the active tile grid changes.
    private fun tile_bitmap(
        @Suppress("UNUSED_PARAMETER") z: Int,
        @Suppress("UNUSED_PARAMETER") x: Int,
        @Suppress("UNUSED_PARAMETER") y: Int,
        key: String,
        @Suppress("UNUSED_PARAMETER") state: MapTileRenderState
    ): Bitmap? {
        synchronized(tile_cache) {
            tile_cache[key]?.let { return it }
        }
        interim_tiles[key]?.let { return it.bitmap }
        return null
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

    private fun draw_interim_tiles(
        canvas: Canvas,
        viewport: Viewport,
        state: MapTileRenderState,
        now_ms: Long
    ): Boolean {
        prune_interim_tiles(now_ms)
        val visible_tiles = ArrayList<InterimRasterTile>()
        for (tile in interim_tiles.values) {
            if (tile.cache_key != state.cache_key) continue
            if (!interim_tile_intersects_viewport(tile, viewport)) continue
            visible_tiles += tile
        }
        if (visible_tiles.isEmpty()) return false
        visible_tiles.sortBy { it.z }
        for (tile in visible_tiles) {
            draw_interim_tile(canvas, tile, viewport)
            tile.last_used_ms = now_ms
        }
        return true
    }

    private fun draw_interim_tile(canvas: Canvas, tile: InterimRasterTile, viewport: Viewport) {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x =
            (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y =
            (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        val repeat_start =
            floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((viewport.width - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            interim_tile_destination.set(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (!rect_intersects_viewport(interim_tile_destination, viewport)) continue
            draw_tile_bitmap(canvas, tile.bitmap, null, interim_tile_destination, 1f)
        }
    }

    private fun interim_tile_intersects_viewport(
        tile: InterimRasterTile,
        viewport: Viewport
    ): Boolean {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x =
            (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y =
            (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (screen_y > viewport.height || screen_y + tile_size_on_screen < 0f) return false
        val repeat_start =
            floor((-tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end = floor((viewport.width - base_screen_x) / tile_world_width_at_zoom).toInt()
        return repeat_start <= repeat_end
    }

    private fun rect_intersects_viewport(rect: RectF, viewport: Viewport): Boolean {
        return rect.right >= 0f &&
                rect.left <= viewport.width &&
                rect.bottom >= 0f &&
                rect.top <= viewport.height
    }

    private fun retained_interim_intersects_destination(
        viewport: Viewport,
        cache_key: String,
        destination: RectF
    ): Boolean {
        for (tile in interim_tiles.values) {
            if (tile.cache_key != cache_key) continue
            if (interim_tile_intersects_destination(tile, viewport, destination)) return true
        }
        return false
    }

    private fun interim_tile_intersects_destination(
        tile: InterimRasterTile,
        viewport: Viewport,
        destination: RectF
    ): Boolean {
        val scale = 2.0.pow(viewport.zoom - tile.z)
        val tile_size_on_screen = (TILE_SIZE * scale).toFloat()
        val tile_world_width_at_zoom = TILE_SIZE * 2.0.pow(viewport.zoom)
        val base_screen_x =
            (tile.x * TILE_SIZE * scale - viewport.center_x + viewport.width / 2.0).toFloat()
        val screen_y =
            (tile.y * TILE_SIZE * scale - viewport.center_y + viewport.height / 2.0).toFloat()
        if (screen_y > destination.bottom || screen_y + tile_size_on_screen < destination.top) return false
        val repeat_start =
            floor((destination.left - tile_size_on_screen - base_screen_x) / tile_world_width_at_zoom).toInt()
        val repeat_end =
            floor((destination.right - base_screen_x) / tile_world_width_at_zoom).toInt()
        for (repeat in repeat_start..repeat_end) {
            val screen_x = (base_screen_x + repeat * tile_world_width_at_zoom).toFloat()
            interim_tile_test_rect.set(
                screen_x,
                screen_y,
                screen_x + tile_size_on_screen,
                screen_y + tile_size_on_screen
            )
            if (RectF.intersects(interim_tile_test_rect, destination)) return true
        }
        return false
    }

    private fun replace_interim_tiles(tiles: List<InterimRasterTile>) {
        interim_tiles.clear()
        for (tile in tiles.takeLast(MAX_INTERIM_TILES)) {
            val key = "${tile.cache_key}/${tile.z}/${tile.x}/${tile.y}"
            interim_tiles[key] = tile
        }
    }

    private fun merge_interim_tiles(tiles: List<InterimRasterTile>) {
        for (tile in tiles) {
            val key = "${tile.cache_key}/${tile.z}/${tile.x}/${tile.y}"
            interim_tiles[key] = tile
        }
        while (interim_tiles.size > MAX_INTERIM_TILES) {
            val first_key = interim_tiles.keys.firstOrNull() ?: break
            interim_tiles.remove(first_key)
        }
    }

    private fun prune_interim_tiles(now_ms: Long) {
        val iterator = interim_tiles.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now_ms - entry.value.last_used_ms > RASTER_INTERIM_MAX_AGE_MS) {
                iterator.remove()
            }
        }
    }

    private fun draw_satellite_fallback_tile(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        destination: RectF,
        alpha: Float,
        allow_parent_fallback: Boolean,
        allow_child_fallback: Boolean
    ): Boolean {
        if (alpha <= MIN_LAYER_ALPHA) return false
        if (allow_parent_fallback) {
            val parent = satellite_parent_tile_fallback(z, x, y, state)
            if (parent != null) {
                draw_tile_bitmap(canvas, parent.bitmap, parent.source_rect, destination, alpha)
                return true
            }
        }
        if (allow_child_fallback) {
            return draw_satellite_child_tile_fallback(canvas, z, x, y, state, destination, alpha)
        }
        return false
    }

    // Satellite zooms look better when a real lower-zoom tile remains visible until the exact tile arrives.
    private fun satellite_parent_tile_fallback(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState
    ): TileFallback? {
        if (z <= MIN_ZOOM) return null
        val max_depth = (z - MIN_ZOOM).coerceAtMost(SATELLITE_PARENT_REQUEST_DEPTH)
        for (delta in 1..max_depth) {
            val fallback_z = z - delta
            val scale = 1 shl delta
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${state.cache_key}/$fallback_z/$parent_x/$parent_y"
            val bitmap = synchronized(tile_cache) { tile_cache[parent_key] }
                ?: interim_tiles[parent_key]?.bitmap
            if (bitmap != null) {
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
        }
        return null
    }

    private fun draw_satellite_child_tile_fallback(
        canvas: Canvas,
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        destination: RectF,
        alpha: Float
    ): Boolean {
        if (z >= TileSource.SATELLITE.max_native_zoom) return false
        val max_delta = (TileSource.SATELLITE.max_native_zoom - z).coerceAtMost(
            SATELLITE_CHILD_FALLBACK_MAX_DELTA
        )
        for (delta in 1..max_delta) {
            val child_z = z + delta
            val scale = 1 shl delta
            val max_child_tile = 1 shl child_z
            val children = ArrayList<ChildTileFallback>(scale * scale)
            for (child_y_offset in 0 until scale) {
                val child_y = y * scale + child_y_offset
                if (child_y !in 0 until max_child_tile) {
                    continue
                }
                for (child_x_offset in 0 until scale) {
                    val child_x_raw = x * scale + child_x_offset
                    val child_x = ((child_x_raw % max_child_tile) + max_child_tile) % max_child_tile
                    val child_key = "${state.cache_key}/$child_z/$child_x/$child_y"
                    val bitmap = synchronized(tile_cache) { tile_cache[child_key] }
                        ?: interim_tiles[child_key]?.bitmap
                    if (bitmap == null) continue
                    children += ChildTileFallback(
                        bitmap = bitmap,
                        child_x = child_x_offset,
                        child_y = child_y_offset,
                        child_scale = scale
                    )
                }
            }
            if (children.isNotEmpty()) {
                draw_satellite_child_tiles(canvas, destination, children, alpha)
                return true
            }
        }
        return false
    }

    private fun draw_satellite_child_tiles(
        canvas: Canvas,
        destination: RectF,
        children: List<ChildTileFallback>,
        alpha: Float
    ) {
        val width = destination.width()
        val height = destination.height()
        for (child in children) {
            val left = destination.left + child.child_x * width / child.child_scale
            val top = destination.top + child.child_y * height / child.child_scale
            val right = destination.left + (child.child_x + 1) * width / child.child_scale
            val bottom = destination.top + (child.child_y + 1) * height / child.child_scale
            satellite_child_destination.set(left, top, right, bottom)
            draw_tile_bitmap(canvas, child.bitmap, null, satellite_child_destination, alpha)
        }
    }

    private fun request_satellite_buffer_tiles(
        tile_zoom: Int,
        first_tile_x: Int,
        last_tile_x: Int,
        first_tile_y: Int,
        last_tile_y: Int,
        max_tile: Int,
        state: MapTileRenderState,
        now_ms: Long,
        request_generation: Long
    ) {
        val buffer = SATELLITE_REQUEST_TILE_BUFFER
        if (buffer <= 0) return
        val request_key =
            "${state.cache_key}/$tile_zoom/$first_tile_x/$last_tile_x/$first_tile_y/$last_tile_y"
        if (
            request_key == last_satellite_buffer_request_key &&
            now_ms - last_satellite_buffer_request_ms < SATELLITE_BUFFER_REQUEST_THROTTLE_MS
        ) {
            return
        }
        last_satellite_buffer_request_key = request_key
        last_satellite_buffer_request_ms = now_ms
        for (ty in (first_tile_y - buffer)..(last_tile_y + buffer)) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in (first_tile_x - buffer)..(last_tile_x + buffer)) {
                if (ty in first_tile_y..last_tile_y && tx_raw in first_tile_x..last_tile_x) continue
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                mark_current_tile_request(key, request_generation)
                request_satellite_parent_tiles(tile_zoom, tx, ty, state, request_generation)
                request_tile(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    state = state,
                    request_generation = request_generation,
                    request_priority = SATELLITE_TILE_REQUEST_PRIORITY_BUFFER
                )
            }
        }
    }

    private fun request_satellite_prefetch_grid(
        viewport: Viewport,
        state: MapTileRenderState,
        tile_zoom: Int,
        now_ms: Long,
        request_generation: Long
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
        val request_key =
            "${state.cache_key}/$tile_zoom/$first_tile_x/$last_tile_x/$first_tile_y/$last_tile_y"
        if (
            request_key == last_satellite_prefetch_request_key &&
            now_ms - last_satellite_prefetch_request_ms < SATELLITE_PREFETCH_REQUEST_THROTTLE_MS
        ) {
            return
        }
        last_satellite_prefetch_request_key = request_key
        last_satellite_prefetch_request_ms = now_ms
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                val key = "${state.cache_key}/$tile_zoom/$tx/$ty"
                mark_current_tile_request(key, request_generation)
                request_tile(
                    z = tile_zoom,
                    x = tx,
                    y = ty,
                    key = key,
                    state = state,
                    request_generation = request_generation,
                    request_priority = SATELLITE_TILE_REQUEST_PRIORITY_PREFETCH
                )
            }
        }
    }

    private fun request_satellite_parent_tiles(
        z: Int,
        x: Int,
        y: Int,
        state: MapTileRenderState,
        request_generation: Long
    ) {
        if (z <= MIN_ZOOM) return
        val max_depth = (z - MIN_ZOOM).coerceAtMost(SATELLITE_PARENT_REQUEST_DEPTH)
        for (depth in 1..max_depth) {
            val parent_z = z - depth
            val scale = 1 shl depth
            val parent_x = x / scale
            val parent_y = y / scale
            val parent_key = "${state.cache_key}/$parent_z/$parent_x/$parent_y"
            mark_current_tile_request(parent_key, request_generation)
            if (tile_bitmap(parent_z, parent_x, parent_y, parent_key, state) == null) {
                request_tile(
                    z = parent_z,
                    x = parent_x,
                    y = parent_y,
                    key = parent_key,
                    state = state,
                    request_generation = request_generation,
                    request_priority = SATELLITE_TILE_REQUEST_PRIORITY_PARENT_BASE - depth
                )
            }
        }
    }

    // Download one HTTPS tile off the UI thread, then redraw when real bitmap data is available.
    private fun request_tile(
        z: Int,
        x: Int,
        y: Int,
        key: String,
        state: MapTileRenderState,
        request_generation: Long,
        request_priority: Int
    ) {
        if (tile_bitmap(z, x, y, key, state) != null) return
        synchronized(requested_tiles) {
            if (requested_tiles.contains(key)) return
            requested_tiles += key
        }
        satellite_tile_executor.execute(
            SatelliteTileRequestTask(
                generation = request_generation,
                priority = request_priority,
                sequence = satellite_tile_task_sequence.incrementAndGet(),
                action = tileTask@{
                    var connection: HttpURLConnection? = null
                    var redrew_when_loaded = false
                    var skipped_stale_request = false
                    try {
                        if (should_skip_stale_tile_request(key)) {
                            skipped_stale_request = true
                            return@tileTask
                        }
                        if (tile_bitmap(z, x, y, key, state) != null) {
                            skipped_stale_request = true
                            return@tileTask
                        }
                        val file = tile_file(z, x, y, state)
                        if (is_fresh_tile_file(file)) {
                            val bitmap =
                                BitmapFactory.decodeFile(file.absolutePath, decode_options())
                            if (bitmap != null) {
                                synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                                return@tileTask
                            }
                        }
                        val url = https_url(
                            TileSource.SATELLITE.tile_url(
                                z,
                                x,
                                y,
                                state.map_labels_enabled
                            )
                        ) ?: run {
                            report_status("Map tiles unavailable")
                            return@tileTask
                        }
                        connection = (url.openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8000
                            readTimeout = 10000
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", state.user_agent)
                        }
                        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                            val bytes = connection.inputStream.use { it.readBytes() }
                            val bitmap = BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size,
                                decode_options()
                            )
                            if (bitmap != null) {
                                synchronized(tile_cache) { put_tile_in_memory(key, bitmap) }
                                redrew_when_loaded = true
                                request_redraw()
                                write_tile_file_async(file, bytes)
                            }
                        } else {
                            connection.errorStream?.close()
                            report_status("Map tiles unavailable")
                        }
                    } catch (_: Exception) {
                        report_status("Map network unavailable")
                    } finally {
                        connection?.disconnect()
                        synchronized(requested_tiles) { requested_tiles -= key }
                        if (!redrew_when_loaded && !skipped_stale_request) request_redraw()
                    }
                })
        )
    }

    private fun begin_tile_request_generation(): Long {
        synchronized(requested_tiles) {
            current_tile_request_generation++
            if (current_tile_request_generations.size > CURRENT_TILE_REQUEST_HISTORY_MAX) {
                val stale_before =
                    current_tile_request_generation - CURRENT_TILE_REQUEST_HISTORY_AGE
                val iterator = current_tile_request_generations.iterator()
                while (iterator.hasNext()) {
                    if (iterator.next().value < stale_before) iterator.remove()
                }
                while (current_tile_request_generations.size > CURRENT_TILE_REQUEST_HISTORY_MAX) {
                    val oldest_key =
                        current_tile_request_generations.minByOrNull { it.value }?.key ?: break
                    current_tile_request_generations.remove(oldest_key)
                }
            }
            return current_tile_request_generation
        }
    }

    private fun mark_current_tile_request(key: String, generation: Long) {
        synchronized(requested_tiles) {
            current_tile_request_generations[key] = generation
        }
    }

    private fun should_skip_stale_tile_request(key: String): Boolean {
        synchronized(requested_tiles) {
            val generation = current_tile_request_generations[key] ?: return true
            return current_tile_request_generation - generation > CURRENT_TILE_REQUEST_STALE_GENERATIONS
        }
    }

    private fun write_tile_file_async(file: File, bytes: ByteArray) {
        write_cache_file_async(tile_disk_executor, file, bytes)
    }

    private fun put_tile_in_memory(key: String, bitmap: Bitmap) {
        val is_new = !tile_cache.containsKey(key)
        tile_cache[key] = bitmap
        if (is_new) tile_loaded_elapsed_ms[key] = SystemClock.elapsedRealtime()
        while (tile_cache.size > MAX_MEMORY_TILES) {
            val first_key = tile_cache.keys.firstOrNull() ?: break
            tile_cache.remove(first_key)
            tile_loaded_elapsed_ms.remove(first_key)
        }
    }

    private fun update_tile_zoom_transition(tile_zoom: Int, state: MapTileRenderState) {
        val changed_source =
            transition_cache_key != state.cache_key || transition_map_source != state.map_source
        if (changed_source) {
            transition_cache_key = state.cache_key
            transition_map_source = state.map_source
            current_tile_zoom = tile_zoom
            tile_zoom_direction = 0
            return
        }
        if (tile_zoom != current_tile_zoom) {
            tile_zoom_direction = tile_zoom.compareTo(current_tile_zoom)
            current_tile_zoom = tile_zoom
        }
    }

    private fun satellite_tile_load_alpha(key: String, now_ms: Long): Float {
        val loaded_at = tile_loaded_elapsed_ms[key] ?: 0L
        return if (loaded_at > 0L) {
            smooth_step(
                0f,
                1f,
                ((now_ms - loaded_at).coerceAtLeast(0L).toFloat() / SATELLITE_TILE_FADE_MS)
            )
        } else {
            1f
        }
    }

    private fun tile_file(z: Int, x: Int, y: Int, state: MapTileRenderState): File {
        return map_tile_cache_file(context, state.cache_key, z, x, y)
    }

    private fun is_fresh_tile_file(file: File): Boolean {
        return is_fresh_cache_file(file)
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

    // Fill missing tile space with a neutral unavailable tile while the real provider request is pending or failed.
    private fun draw_unavailable_tile(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        style: MapTileRenderStyle
    ) {
        draw_unavailable_map_tile(
            canvas,
            paint,
            text_paint,
            x,
            y,
            size,
            style.panel_alt,
            with_alpha(style.text, 170),
            sp(10f)
        )
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

    private class SatelliteTileRequestTask(
        private val generation: Long,
        private val priority: Int,
        private val sequence: Long,
        private val action: () -> Unit
    ) : Runnable, Comparable<Runnable> {
        override fun run() = action()

        override fun compareTo(other: Runnable): Int {
            val task = other as? SatelliteTileRequestTask ?: return -1
            if (generation != task.generation) return task.generation.compareTo(generation)
            if (priority != task.priority) return priority.compareTo(task.priority)
            return sequence.compareTo(task.sequence)
        }
    }

    private companion object {
        const val TILE_SIZE = 256
        const val MIN_ZOOM = 3
        const val MAX_MEMORY_TILES = 260
        const val MAX_INTERIM_TILES = 360
        const val RASTER_INTERIM_MAX_AGE_MS = 45_000L
        const val SATELLITE_REQUEST_TILE_BUFFER = 1
        const val SATELLITE_BUFFER_REQUEST_THROTTLE_MS = 180L
        const val SATELLITE_PREFETCH_REQUEST_THROTTLE_MS = 120L
        const val SATELLITE_PARENT_REQUEST_DEPTH = 10
        const val SATELLITE_CHILD_FALLBACK_MAX_DELTA = 1
        const val SATELLITE_TILE_FADE_MS = 360f
        const val MAX_REFERENCE_MEMORY_TILES = 768
        const val REFERENCE_TILE_CACHE_MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
        const val REFERENCE_TILE_FADE_MS = 280f
        const val REFERENCE_TILE_NETWORK_THREADS = 4
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
        const val REFERENCE_TILE_REQUEST_QUEUED = 1
        const val REFERENCE_TILE_REQUEST_ADMITTED = 2
        const val REFERENCE_TILE_REQUEST_QUEUED_SAME_GENERATION = 3
        const val REFERENCE_TILE_REQUEST_QUEUED_RECENT_GENERATION = 4
        const val REFERENCE_PREFETCH_KIND_LOD = 0
        const val REFERENCE_PREFETCH_KIND_PAN = 1
        const val REFERENCE_PREFETCH_TRACE_SECTION = "FlightAlert.refPrefetch"
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
        const val LOD_PREFETCH_START_FRACTION = 0.08f
        const val LOD_BLEND_START_FRACTION = 0.18f
        const val LOD_BLEND_END_FRACTION = 0.82f
        const val MIN_LAYER_ALPHA = 0.01f
        const val SATELLITE_TILE_DISK_WORKER_KEEP_ALIVE_MS = 15_000L
        const val SATELLITE_TILE_NETWORK_THREADS = 4
        const val SATELLITE_TILE_REQUEST_PRIORITY_PARENT_BASE = 2
        const val SATELLITE_TILE_REQUEST_PRIORITY_EXACT = 6
        const val SATELLITE_TILE_REQUEST_PRIORITY_PREFETCH = 5
        const val SATELLITE_TILE_REQUEST_PRIORITY_BUFFER = 10
        const val CURRENT_TILE_REQUEST_HISTORY_MAX = 900
        const val CURRENT_TILE_REQUEST_HISTORY_AGE = 6L
        const val CURRENT_TILE_REQUEST_STALE_GENERATIONS = 2L
    }
}
