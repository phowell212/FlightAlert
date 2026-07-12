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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PathEffect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import androidx.core.graphics.withSave
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
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
    private val paint: Paint,
    private val text_paint: Paint,
    private val dp: (Float) -> Float,
    private val sp: (Float) -> Float,
    private val with_alpha: (Int, Int) -> Int,
    private val request_redraw: () -> Unit
) : Closeable {
    private val package_store = ReferenceDictionaryPackageStore(context)
    private val path = Path()
    private val line_label_path = Path()
    private val path_measure = PathMeasure()
    private val line_label_bounds = RectF()
    private val line_label_position = FloatArray(2)
    private val visible_tiles = ArrayList<VisibleDictionaryTile>(32)
    private val draw_tiles = ArrayList<DictionaryTileDrawRef>(32)
    private val label_candidates = ArrayList<DictionaryLabelCandidate>(256)
    private val accepted_labels = ArrayList<DictionaryLabelCandidate>(128)
    private val occupied_label_bounds = ArrayList<RectF>(128)
    private val requested_tiles = HashSet<String>()
    private val desired_tile_keys = HashSet<String>()
    private val frame_destination = RectF()
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val content_revision = AtomicLong()
    private val request_generation = AtomicLong()
    private val tile_cache =
        object : LinkedHashMap<String, ParsedDictionaryTile>(MAX_MEMORY_TILES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, ParsedDictionaryTile>
            ): Boolean = size > MAX_MEMORY_TILES
        }
    private val worker_id = AtomicInteger()
    private val tile_executor = Executors.newFixedThreadPool(DICTIONARY_TILE_WORKERS) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-dictionary-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    private var retained_frame: RetainedReferenceFrame? = null
    private var retained_fade: RetainedReferenceFade? = null

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
        val package_info = package_store.package_info_if_available()
            ?: return unavailable_stats()
        val filter_mask = ReferenceFilterMask.from(filter_state)
        val tile_zoom = dictionary_tile_zoom(viewport.zoom, package_info.zooms)
            ?: return unavailable_stats()
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
        val retained_drawn_for_interaction = interaction_active &&
                draw_retained_frame_if_available(canvas, viewport, options, now_ms)
        val draw_viewport = retained_frame_viewport(viewport)
        build_visible_tile_list(draw_viewport, viewport, tile_zoom)
        if (visible_tiles.isEmpty()) return ready_empty_stats()
        val generation = update_desired_tile_keys()

        draw_tiles.clear()
        var requested = 0
        var missing = 0
        var core_missing = 0
        var padded_prefetch_requests = 0
        for (tile in visible_tiles) {
            val cache_key = tile.cache_key()
            val parsed = synchronized(tile_cache) { tile_cache[cache_key] }
            if (parsed != null) {
                draw_tiles += DictionaryTileDrawRef(
                    tile = parsed,
                    draw_x = tile.draw_x
                )
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

        val ready = core_missing == 0
        if (retained_drawn_for_interaction) {
            val labels_drawn = draw_live_labels(
                canvas = canvas,
                viewport = viewport,
                tiles = draw_tiles,
                labels_enabled = labels_enabled,
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
                ready = ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = if (ready) 0 else maxOf(requested, missing),
                boundaries_drawn = 0,
                labels_drawn = labels_drawn,
                retained_frame_drawn = true,
                fading = false
            )
        }
        if ((interaction_active || !ready) &&
            draw_retained_frame_if_available(canvas, viewport, options, now_ms)
        ) {
            val labels_drawn = draw_live_labels(
                canvas = canvas,
                viewport = viewport,
                tiles = draw_tiles,
                labels_enabled = labels_enabled,
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
                ready = ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = maxOf(requested, missing),
                boundaries_drawn = 0,
                labels_drawn = labels_drawn,
                retained_frame_drawn = true,
                fading = !ready
            )
        }

        if (draw_tiles.isEmpty()) {
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = false,
                visible_tiles = visible_tiles.size,
                loaded_tiles = 0,
                requested_tiles = maxOf(requested, missing),
                boundaries_drawn = 0,
                labels_drawn = 0,
                retained_frame_drawn = false,
                fading = true
            )
        }

        if (ready && !interaction_active) {
            val exact = retained_frame
            if (exact != null && exact.matches_exact(viewport, options)) {
                draw_retained_frame_with_transition(canvas, viewport, exact, now_ms)
                val labels_drawn = draw_live_labels(
                    canvas = canvas,
                    viewport = viewport,
                    tiles = draw_tiles,
                    labels_enabled = labels_enabled,
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
                    ready = true,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = 0,
                    boundaries_drawn = exact.boundaries_drawn,
                    labels_drawn = labels_drawn,
                    retained_frame_drawn = true,
                    fading = false
                )
            }
            val rendered = render_retained_frame(
                viewport = viewport,
                tiles = draw_tiles,
                labels_enabled = labels_enabled,
                borders_enabled = borders_enabled,
                label_text_scale = label_text_scale,
                place_labels_enabled = place_labels_enabled,
                water_labels_enabled = water_labels_enabled,
                region_labels_enabled = region_labels_enabled,
                public_lands_enabled = public_lands_enabled,
                filter_mask = filter_mask,
                options = options
            )
            if (rendered != null) {
                replace_retained_frame(rendered, fade_from_previous = true, now_ms = now_ms)
                draw_retained_frame_with_transition(canvas, viewport, rendered, now_ms)
                val labels_drawn = draw_live_labels(
                    canvas = canvas,
                    viewport = viewport,
                    tiles = draw_tiles,
                    labels_enabled = labels_enabled,
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
                    ready = true,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = 0,
                    boundaries_drawn = rendered.boundaries_drawn,
                    labels_drawn = labels_drawn,
                    retained_frame_drawn = true,
                    fading = false
                )
            }
        }

        val counts = draw_reference_content(
            canvas = canvas,
            viewport = viewport,
            tiles = draw_tiles,
            labels_enabled = labels_enabled,
            borders_enabled = borders_enabled,
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
            ready = ready,
            visible_tiles = visible_tiles.size,
            loaded_tiles = draw_tiles.size,
            requested_tiles = if (ready) 0 else maxOf(requested, missing),
            boundaries_drawn = counts.boundaries,
            labels_drawn = counts.labels,
            retained_frame_drawn = false,
            fading = !ready
        )
    }

    fun content_revision(): Long {
        return content_revision.get()
    }

    fun reference_class_catalog(): ReferenceClassCatalog? {
        return package_store.reference_class_catalog_if_available()
    }

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
            requested_tiles.clear()
            desired_tile_keys.clear()
        }
        replace_retained_frame(null)
        content_revision.incrementAndGet()
        package_store.close()
    }

    fun reset_retained_frame() {
        replace_retained_frame(null)
    }

    override fun close() {
        tile_executor.shutdownNow()
        clear()
    }

    private fun request_tile_if_needed(
        tile: VisibleDictionaryTile,
        generation: Long
    ): Int {
        val cache_key = tile.cache_key()
        synchronized(tile_cache) {
            if (tile_cache.containsKey(cache_key)) return 0
            if (!requested_tiles.add(cache_key)) return 0
        }
        tile_executor.execute {
            try {
                if (!tile_request_still_relevant(cache_key, generation)) return@execute
                val payload = package_store.read_tile_payload(
                    z = tile.z,
                    x = tile.x,
                    y = tile.y
                )
                val parsed = if (payload != null) {
                    parse_tile_payload(payload = payload)
                } else {
                    empty_parsed_tile(tile)
                }
                synchronized(tile_cache) {
                    tile_cache[cache_key] = parsed
                    content_revision.incrementAndGet()
                }
            } catch (_: Exception) {
                // Missing or corrupt baked reference data is treated as unavailable real data.
            } finally {
                synchronized(tile_cache) {
                    requested_tiles.remove(cache_key)
                }
                request_redraw()
            }
        }
        return 1
    }

    private fun update_desired_tile_keys(): Long {
        return synchronized(tile_cache) {
            var changed = desired_tile_keys.size != visible_tiles.size
            if (!changed) {
                for (tile in visible_tiles) {
                    if (!desired_tile_keys.contains(tile.cache_key())) {
                        changed = true
                        break
                    }
                }
            }
            if (changed) {
                desired_tile_keys.clear()
                visible_tiles.forEach { tile -> desired_tile_keys += tile.cache_key() }
                request_generation.incrementAndGet()
            } else {
                request_generation.get()
            }
        }
    }

    private fun tile_request_still_relevant(cache_key: String, generation: Long): Boolean {
        return synchronized(tile_cache) {
            generation == request_generation.get() || desired_tile_keys.contains(cache_key)
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
        viewport: Viewport,
        core_viewport: Viewport,
        tile_zoom: Int
    ) {
        visible_tiles.clear()
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
        for (ty in first_tile_y..last_tile_y) {
            if (ty !in 0 until max_tile) continue
            for (tx_raw in first_tile_x..last_tile_x) {
                val tx = ((tx_raw % max_tile) + max_tile) % max_tile
                visible_tiles += VisibleDictionaryTile(
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
                    )
                )
            }
        }
        visible_tiles.sortWith(
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

    private fun render_retained_frame(
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
        options: RetainedReferenceOptions
    ): RetainedReferenceFrame? {
        val width = ceil(viewport.width).toInt().coerceAtLeast(1)
        val height = ceil(viewport.height).toInt().coerceAtLeast(1)
        val padding = retained_frame_padding_px(viewport)
        val bitmap_width = (width + padding * 2).coerceAtMost(MAX_RETAINED_BITMAP_SIZE)
        val bitmap_height = (height + padding * 2).coerceAtMost(MAX_RETAINED_BITMAP_SIZE)
        val bitmap = Bitmap.createBitmap(bitmap_width, bitmap_height, Bitmap.Config.ARGB_8888)
        val frame_viewport = viewport.copy(
            width = bitmap_width.toFloat(),
            height = bitmap_height.toFloat()
        )
        val counts = draw_reference_content(
            canvas = Canvas(bitmap),
            viewport = frame_viewport,
            tiles = tiles,
            // Reference text is always recomputed from the exact current viewport. Retaining
            // labels would stretch glyphs and freeze their path placement across pan/zoom.
            labels_enabled = false,
            borders_enabled = borders_enabled,
            label_text_scale = label_text_scale,
            place_labels_enabled = place_labels_enabled,
            water_labels_enabled = water_labels_enabled,
            region_labels_enabled = region_labels_enabled,
            public_lands_enabled = public_lands_enabled,
            filter_mask = filter_mask,
            label_avoid_rects = emptyList(),
        )
        return RetainedReferenceFrame(
            bitmap = bitmap,
            zoom = viewport.zoom,
            center_x = viewport.center_x,
            center_y = viewport.center_y,
            width = bitmap_width,
            height = bitmap_height,
            visible_width = width,
            visible_height = height,
            options = options,
            content_revision = content_revision.get(),
            boundaries_drawn = counts.boundaries,
            labels_drawn = counts.labels
        )
    }

    private fun draw_live_labels(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        labels_enabled: Boolean,
        label_text_scale: Float,
        place_labels_enabled: Boolean,
        water_labels_enabled: Boolean,
        region_labels_enabled: Boolean,
        public_lands_enabled: Boolean,
        filter_mask: ReferenceFilterMask,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): Int {
        if (!labels_enabled || tiles.isEmpty()) return 0
        return draw_labels(
            canvas = canvas,
            viewport = viewport,
            tiles = tiles,
            label_text_scale = label_text_scale,
            groups = ReferenceDictionaryLayerGroups(
                places = place_labels_enabled,
                water = water_labels_enabled,
                regions = region_labels_enabled,
                public_lands = public_lands_enabled,
            ),
            filter_mask = filter_mask,
            label_avoid_rects = label_avoid_rects,
        )
    }

    private fun draw_retained_frame_if_available(
        canvas: Canvas,
        viewport: Viewport,
        options: RetainedReferenceOptions,
        now_ms: Long
    ): Boolean {
        val frame = retained_frame ?: return false
        if (!frame.matches_options(options) || frame.bitmap.isRecycled) return false
        if (kotlin.math.abs(frame.zoom - viewport.zoom) > MAX_RETAINED_ZOOM_DELTA) return false
        draw_retained_frame_with_transition(canvas, viewport, frame, now_ms)
        return true
    }

    private fun draw_retained_frame_with_transition(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        now_ms: Long
    ) {
        val fade = retained_fade
        if (fade == null || fade.previous === frame || fade.previous.bitmap.isRecycled ||
            fade.previous.options != frame.options ||
            kotlin.math.abs(fade.previous.zoom - viewport.zoom) > MAX_RETAINED_ZOOM_DELTA
        ) {
            cleanup_retained_fade()
            draw_retained_frame(canvas, viewport, frame)
            return
        }
        val raw_progress = ((now_ms - fade.start_ms).toFloat() / RETAINED_FRAME_FADE_MS)
            .coerceIn(0f, 1f)
        if (raw_progress >= 1f) {
            cleanup_retained_fade()
            draw_retained_frame(canvas, viewport, frame)
            return
        }
        val eased_progress = raw_progress * raw_progress * (3f - 2f * raw_progress)
        draw_retained_frame(canvas, viewport, fade.previous, 255)
        draw_retained_frame(
            canvas = canvas,
            viewport = viewport,
            frame = frame,
            alpha = (255f * eased_progress).roundToInt().coerceIn(0, 255)
        )
        request_redraw()
    }

    private fun draw_retained_frame(
        canvas: Canvas,
        viewport: Viewport,
        frame: RetainedReferenceFrame,
        alpha: Int = 255
    ) {
        val frame_zoom_scale = 2.0.pow(frame.zoom)
        val viewport_zoom_scale = 2.0.pow(viewport.zoom)
        val frame_left_zero = (frame.center_x - frame.width / 2.0) / frame_zoom_scale
        val frame_top_zero = (frame.center_y - frame.height / 2.0) / frame_zoom_scale
        val viewport_left_zero = (viewport.center_x - viewport.width / 2.0) / viewport_zoom_scale
        val viewport_top_zero = (viewport.center_y - viewport.height / 2.0) / viewport_zoom_scale
        val draw_scale = 2.0.pow(viewport.zoom - frame.zoom).toFloat()
        val left = ((frame_left_zero - viewport_left_zero) * viewport_zoom_scale).toFloat()
        val top = ((frame_top_zero - viewport_top_zero) * viewport_zoom_scale).toFloat()
        frame_destination.set(
            left,
            top,
            left + frame.width * draw_scale,
            top + frame.height * draw_scale
        )
        val previous_alpha = bitmap_paint.alpha
        bitmap_paint.alpha = alpha
        canvas.drawBitmap(frame.bitmap, null, frame_destination, bitmap_paint)
        bitmap_paint.alpha = previous_alpha
    }

    private fun retained_frame_viewport(viewport: Viewport): Viewport {
        val padding = retained_frame_padding_px(viewport)
        return viewport.copy(
            width = viewport.width + padding * 2f,
            height = viewport.height + padding * 2f
        )
    }

    private fun retained_frame_padding_px(viewport: Viewport): Int {
        val longest_side = max(viewport.width, viewport.height)
        return (longest_side * RETAINED_FRAME_PADDING_FRACTION)
            .roundToInt()
            .coerceIn(RETAINED_FRAME_PADDING_MIN_PX, RETAINED_FRAME_PADDING_MAX_PX)
    }

    private fun replace_retained_frame(
        next: RetainedReferenceFrame?,
        fade_from_previous: Boolean = false,
        now_ms: Long = SystemClock.elapsedRealtime()
    ) {
        val previous = retained_frame
        val old_fade = retained_fade
        if (old_fade != null && old_fade.previous !== previous && old_fade.previous !== next) {
            recycle_retained_frame(old_fade.previous)
        }
        retained_fade = null
        retained_frame = next
        if (previous != null && previous !== next) {
            if (fade_from_previous && next != null &&
                previous.matches_options(next.options) &&
                !previous.bitmap.isRecycled
            ) {
                retained_fade = RetainedReferenceFade(
                    previous = previous,
                    start_ms = now_ms
                )
            } else {
                recycle_retained_frame(previous)
            }
        }
    }

    private fun cleanup_retained_fade() {
        val fade = retained_fade ?: return
        retained_fade = null
        if (fade.previous !== retained_frame) {
            recycle_retained_frame(fade.previous)
        }
    }

    private fun recycle_retained_frame(frame: RetainedReferenceFrame) {
        if (!frame.bitmap.isRecycled) {
            frame.bitmap.recycle()
        }
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
            for (tile in tiles) {
                for (record in tile.tile.boundaries) {
                    if (!filter_mask.allows(record.filter_id, groups.enabled(record.layer_group))) {
                        continue
                    }
                    if (draw_path_record(canvas, viewport, tile, record.geometry, record.style)) {
                        boundaries_drawn++
                    }
                }
            }
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

    private fun draw_path_record(
        canvas: Canvas,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        geometry: DictionaryGeometry?,
        style: DictionaryLineStyle
    ): Boolean {
        val rings = geometry?.rings ?: return false
        var drew = false
        for (ring in rings) {
            if (!build_ring_path(path, viewport, tile, ring)) continue
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeJoin = style.stroke_join
            paint.strokeCap = style.stroke_cap
            paint.pathEffect = style.path_effect
            paint.strokeWidth = dp(style.stroke_width_dp + style.halo_width_dp)
            paint.color = with_alpha(style.halo_color, style.halo_alpha)
            canvas.drawPath(path, paint)
            paint.strokeWidth = dp(style.stroke_width_dp)
            paint.color = with_alpha(style.color, style.alpha)
            canvas.drawPath(path, paint)
            paint.pathEffect = null
            drew = true
        }
        return drew
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
        label_candidates.clear()
        for (tile in tiles) {
            for (record in tile.tile.labels) {
                if (!record.drawable || !record.visible_at(viewport.zoom)) continue
                if (!filter_mask.allows(record.filter_id, groups.enabled(record.layer_group))) {
                    continue
                }
                val style = label_style_for(record, label_text_scale, viewport.zoom)
                if (style.alpha <= 0 && style.halo_alpha <= 0) continue
                val candidate = if (record.line_label && record.geometry?.rings?.isNotEmpty() == true) {
                    line_label_candidate(viewport, tile, record, style, label_avoid_rects)
                } else {
                    point_label_candidate(viewport, tile, record, style)
                }
                if (candidate != null) label_candidates += candidate
            }
        }
        accept_label_candidates(viewport, label_avoid_rects)
        for (index in accepted_labels.indices.reversed()) {
            draw_label_candidate(canvas, accepted_labels[index])
        }
        return accepted_labels.size
    }

    private fun line_label_candidate(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle,
        label_avoid_rects: List<ReferenceScreenRect>,
    ): DictionaryLabelCandidate? {
        prepare_text_paint(style, style.typeface_style, style.text_size)
        val presentation = record.sourced_text?.let {
            SourcedMapTextPresentation.plan(it, style.text_size)
        }
        val primary_width = text_paint.measureText(record.text)
        val english_width = presentation?.english?.let { english ->
            prepare_text_paint(
                style,
                italic_typeface_style(style.typeface_style),
                english.textSize,
            )
            text_paint.measureText(english.text)
        } ?: 0f
        prepare_text_paint(style, style.typeface_style, style.text_size)
        val text_width = max(primary_width, english_width)
        val is_water = record.is_water ?: (record.source_kind == "water")
        val parts = record.geometry?.rings?.mapNotNull { ring ->
            if (ring.point_count < 2) return@mapNotNull null
            List(ring.point_count) { index ->
                val point = project_point(
                    viewport = viewport,
                    tile = tile,
                    local_x = ring.points[index * 2],
                    local_y = ring.points[index * 2 + 1],
                )
                ReferencePathLabelPoint(point.x.toDouble(), point.y.toDouble())
            }
        }.orEmpty()
        if (parts.isEmpty()) return null
        val collision_height = presentation?.collisionHeightEm?.times(style.text_size)
            ?: style.text_size
        val collision_radius = collision_height / 2f +
                dp(style.halo_width_dp) + LABEL_COLLISION_PADDING_PX
        val policy_edge_clearance = style.text_size *
                ReferencePresentationPolicy.label_edge_clearance_milli_em / 1_000f
        val placement = ReferencePathLabelPlanner.plan(
            ReferencePathLabelRequest(
                parts = parts,
                viewport = ReferenceScreenRect(
                    left = 0.0,
                    top = 0.0,
                    right = viewport.width.toDouble(),
                    bottom = viewport.height.toDouble(),
                ),
                shapedAdvancePx = text_width.toDouble(),
                endClearancePx = (
                    style.text_size * ReferencePresentationPolicy.label_end_clearance_milli_em /
                        1_000f
                    ).toDouble(),
                edgeClearancePx = max(collision_radius, policy_edge_clearance).toDouble(),
                maxBendDegrees = (
                    record.visibility_rule?.max_bend_centi_degrees
                        ?: ReferencePresentationPolicy.max_line_label_bend_centi_degrees
                    ) / 100.0,
                candidateId = record.candidate_id
                    ?: (record.dedupe_key ?: record.text).hashCode().toUInt().toULong(),
                repeatSpacingPx = record.repeat_spacing_px
                    ?.takeIf { it > 0 }
                    ?: ReferencePresentationPolicy.line_label_repeat_spacing_px,
                prominenceTier = record.prominence_tier ?: ProminenceTier.LOCAL,
                staticAvoidRects = label_avoid_rects,
                maximumTangentOffsetPx = max(dp(48f), collision_radius * 4f).toDouble(),
            ),
        ).firstOrNull()
        if (placement == null) {
            return if (record.candidate_id == null && !is_water) {
                point_label_candidate(viewport, tile, record, style)
            } else {
                null
            }
        }
        line_label_path.reset()
        placement.presentationPath.forEachIndexed { index, point ->
            if (index == 0) {
                line_label_path.moveTo(point.x.toFloat(), point.y.toFloat())
            } else {
                line_label_path.lineTo(point.x.toFloat(), point.y.toFloat())
            }
        }
        val min_x = placement.presentationPath.minOf { it.x }.toFloat()
        val min_y = placement.presentationPath.minOf { it.y }.toFloat()
        val max_x = placement.presentationPath.maxOf { it.x }.toFloat()
        val max_y = placement.presentationPath.maxOf { it.y }.toFloat()
        val label_bounds = RectF(
            min_x - collision_radius,
            min_y - collision_radius,
            max_x + collision_radius,
            max_y + collision_radius,
        )
        val anchor_x = placement.anchor.x.toFloat()
        val anchor_y = placement.anchor.y.toFloat()
        return DictionaryLabelCandidate(
            text = record.text,
            source_kind = record.source_kind,
            sourced_text = record.sourced_text,
            line_label = true,
            dedupe_key = record.dedupe_key ?: "${record.source_kind}:${record.text.lowercase()}",
            style = style,
            priority = record.priority,
            protected_area = record.protected_area,
            is_water = is_water,
            bounds = label_bounds,
            x = anchor_x,
            y = anchor_y,
            path = Path(line_label_path)
        )
    }

    private fun point_label_candidate(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle
    ): DictionaryLabelCandidate? {
        val anchor = label_anchor(viewport, tile, record) ?: return null
        val presentation = record.sourced_text?.let {
            SourcedMapTextPresentation.plan(it, style.text_size)
        }
        prepare_text_paint(style, style.typeface_style, style.text_size)
        val primary_width = text_paint.measureText(record.text)
        val english_width = presentation?.english?.let { english ->
            prepare_text_paint(
                style,
                italic_typeface_style(style.typeface_style),
                english.textSize,
            )
            text_paint.measureText(english.text)
        } ?: 0f
        return DictionaryLabelCandidate(
            text = record.text,
            source_kind = record.source_kind,
            sourced_text = record.sourced_text,
            line_label = false,
            dedupe_key = record.dedupe_key ?: "${record.source_kind}:${record.text.lowercase()}",
            style = style,
            priority = record.priority,
            protected_area = record.protected_area,
            is_water = record.is_water ?: (record.source_kind == "water"),
            bounds = label_bbox(
                record.text,
                anchor.x,
                anchor.y,
                style.text_size,
                width_override = max(primary_width, english_width),
                height_override = presentation?.collisionHeightEm?.times(style.text_size),
            ),
            x = anchor.x,
            y = anchor.y,
            rotation = record.rotation
        )
    }

    private fun accept_label_candidates(
        viewport: Viewport,
        label_avoid_rects: List<ReferenceScreenRect>,
    ) {
        accepted_labels.clear()
        occupied_label_bounds.clear()
        label_avoid_rects.forEach { rect ->
            occupied_label_bounds += RectF(
                rect.left.toFloat(),
                rect.top.toFloat(),
                rect.right.toFloat(),
                rect.bottom.toFloat(),
            )
        }
        val label_budget = label_budget(viewport)
        var protected_area_labels = 0
        label_candidates
            .filter { bbox_intersects_screen(it.bounds, viewport) }
            .sortedWith(compareBy<DictionaryLabelCandidate> { it.priority }.thenBy { it.text })
            .forEach { candidate ->
                if (accepted_labels.size >= label_budget) return@forEach
                if (candidate.protected_area &&
                    protected_area_labels >= protected_area_label_budget(viewport)
                ) {
                    return@forEach
                }
                if (candidate.line_label && candidate.is_water) {
                    val duplicate_nearby = accepted_labels.any { accepted ->
                        accepted.line_label &&
                                accepted.is_water &&
                                accepted.dedupe_key == candidate.dedupe_key &&
                                screen_distance_squared(accepted, candidate) <
                                WATER_LINE_LABEL_REPEAT_DISTANCE_PX *
                                WATER_LINE_LABEL_REPEAT_DISTANCE_PX
                    }
                    if (duplicate_nearby) return@forEach
                }
                val padded = RectF(candidate.bounds).apply {
                    inset(-LABEL_COLLISION_PADDING_PX, -LABEL_COLLISION_PADDING_PX)
                }
                if (occupied_label_bounds.any { RectF.intersects(padded, it) }) return@forEach
                occupied_label_bounds += padded
                accepted_labels += candidate
                if (candidate.protected_area) protected_area_labels++
            }
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

    private fun draw_label_candidate(canvas: Canvas, candidate: DictionaryLabelCandidate) {
        val style = candidate.style
        val presentation = candidate.sourced_text?.let {
            SourcedMapTextPresentation.plan(it, style.text_size)
        }
        val path_label = candidate.path
        if (path_label != null) {
            if (presentation == null) {
                draw_text_on_path(
                    canvas,
                    candidate.text,
                    path_label,
                    style,
                    style.text_size,
                    style.typeface_style,
                    0f,
                )
            } else {
                draw_text_on_path(
                    canvas,
                    presentation.primary.text,
                    path_label,
                    style,
                    presentation.primary.textSize,
                    style.typeface_style,
                    presentation.primary.baselineOffset,
                )
                presentation.english?.let { english ->
                    draw_text_on_path(
                        canvas,
                        english.text,
                        path_label,
                        style,
                        english.textSize,
                        italic_typeface_style(style.typeface_style),
                        english.baselineOffset,
                    )
                }
            }
            return
        }
        val x = candidate.x
        val y = candidate.y
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
                    style.typeface_style,
                )
            } else {
                draw_text_with_halo(
                    canvas,
                    presentation.primary.text,
                    x,
                    y + presentation.primary.baselineOffset,
                    style,
                    presentation.primary.textSize,
                    style.typeface_style,
                )
                presentation.english?.let { english ->
                    draw_text_with_halo(
                        canvas,
                        english.text,
                        x,
                        y + english.baselineOffset,
                        style,
                        english.textSize,
                        italic_typeface_style(style.typeface_style),
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
        typeface_style: Int,
        baseline_offset: Float,
    ) {
        prepare_text_paint(style, typeface_style, text_size)
        path_measure.setPath(label_path, false)
        val path_length = path_measure.length
        text_paint.textAlign = Paint.Align.LEFT
        val h_offset = ((path_length - text_paint.measureText(text)) / 2f).coerceAtLeast(0f)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = max(dp(style.halo_width_dp), text_size * 0.22f)
        text_paint.color = with_alpha(style.halo_color, style.halo_alpha)
        canvas.drawTextOnPath(text, label_path, h_offset, baseline_offset, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(style.color, style.alpha)
        canvas.drawTextOnPath(text, label_path, h_offset, baseline_offset, text_paint)
    }

    private fun draw_text_with_halo(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        style: DictionaryLabelStyle,
        text_size: Float,
        typeface_style: Int,
    ) {
        prepare_text_paint(style, typeface_style, text_size)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = max(dp(style.halo_width_dp), text_size * 0.22f)
        text_paint.color = with_alpha(style.halo_color, style.halo_alpha)
        canvas.drawText(text, x, y, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(style.color, style.alpha)
        canvas.drawText(text, x, y, text_paint)
    }

    private fun prepare_text_paint(
        style: DictionaryLabelStyle,
        typeface_style: Int,
        text_size: Float,
    ) {
        text_paint.isAntiAlias = true
        text_paint.isSubpixelText = true
        text_paint.typeface = Typeface.create(Typeface.DEFAULT, typeface_style)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = text_size
        text_paint.textScaleX = 1f
        text_paint.letterSpacing = style.letter_spacing_em
    }

    private fun italic_typeface_style(typeface_style: Int): Int {
        return if (typeface_style == Typeface.BOLD || typeface_style == Typeface.BOLD_ITALIC) {
            Typeface.BOLD_ITALIC
        } else {
            Typeface.ITALIC
        }
    }

    private fun build_ring_path(
        target: Path,
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        ring: DictionaryRing
    ): Boolean {
        if (ring.point_count < 2) return false
        target.reset()
        for (index in 0 until ring.point_count) {
            val point = project_point(
                viewport = viewport,
                tile = tile,
                local_x = ring.points[index * 2],
                local_y = ring.points[index * 2 + 1]
            )
            if (index == 0) {
                target.moveTo(point.x, point.y)
            } else {
                target.lineTo(point.x, point.y)
            }
        }
        return true
    }

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
        payload: ReferenceDictionaryTilePayload
    ): ParsedDictionaryTile {
        if (payload.runtime_schema == ReferenceDictionaryRuntimeSchema.BINARY_V3) {
            return parse_binary_tile_payload(payload)
        }
        val root = JSONObject(payload.raw_json)
        val records = root.optJSONObject("records") ?: JSONObject()
        val hot_fields = payload.runtime_schema == ReferenceDictionaryRuntimeSchema.HOT_JSON_V2
        return ParsedDictionaryTile(
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
    }

    private fun parse_binary_tile_payload(
        payload: ReferenceDictionaryTilePayload
    ): ParsedDictionaryTile {
        val decoded = ReferenceDictionaryBinaryTileCodec.decode(
            payload.coordinate,
            payload.raw_bytes,
        )
        val boundaries = ArrayList<DictionaryLineRecord>()
        val labels = ArrayList<DictionaryLabelRecord>()
        decoded.records.forEach { record ->
            when (val model = ReferenceDictionaryBinaryPresenter.present(record)) {
                is ReferenceDictionaryBinaryDrawModel.Label -> {
                    val resolved = model.resolvedStyle
                    val text_size_sp = model.visibility.textSizeMilliSp / 1_000f
                    val source_kind = binary_source_kind(model.subtype)
                    val geometry = binary_geometry(model.geometry)
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
                        geometry = if (model.geometry.kind == ReferenceDictionaryBinaryGeometryKind.POINT) {
                            null
                        } else {
                            geometry
                        },
                        sourced_text = model.text,
                        dedupe_key = model.labelCandidateId.toString(16),
                        is_water = binary_water_label(model.subtype),
                        candidate_id = model.labelCandidateId,
                        prominence_tier = model.prominenceTier,
                        visibility_rule = model.visibilityRule,
                        repeat_spacing_px = model.repeatSpacingPx,
                    )
                }

                is ReferenceDictionaryBinaryDrawModel.Outline -> {
                    val resolved = model.resolvedStyle
                    boundaries += DictionaryLineRecord(
                        source_layer = "",
                        source_kind = binary_source_kind(model.subtype),
                        class_name = model.subtype.name,
                        protected_area = model.subtype == SemanticSubtype.PROTECTED_AREA_OUTLINE,
                        layer_group = binary_layer_group(model.filterId),
                        filter_id = model.filterId,
                        geometry = binary_geometry(model.geometry),
                        style = DictionaryLineStyle(
                            color = resolved.color_argb.toInt(),
                            stroke_width_dp = model.style.line_width_milli_dp / 1_000f,
                            halo_width_dp = resolved.line_halo_width_milli_dp / 1_000f,
                            alpha = alpha_milli_to_byte(resolved.alpha_milli),
                            halo_alpha = alpha_milli_to_byte(resolved.halo_alpha_milli),
                            halo_color = resolved.halo_argb.toInt(),
                            path_effect = resolved.dash_milli_dp.takeIf { it.isNotEmpty() }
                                ?.map { it / 1_000f }
                                ?.toFloatArray()
                                ?.let { DashPathEffect(it, resolved.dash_phase_milli_dp / 1_000f) },
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
                    )
                }
            }
        }
        return ParsedDictionaryTile(
            coordinate = decoded.coordinate,
            boundaries = boundaries.sortedBy { it.style.stroke_width_dp },
            labels = labels,
        )
    }

    private fun binary_geometry(
        geometry: ReferenceDictionaryBinaryGeometry
    ): DictionaryGeometry {
        val rings = ArrayList<DictionaryRing>(geometry.partOffsets.size)
        val total_points = geometry.localCoordinates.size / 2
        geometry.partOffsets.forEachIndexed { index, start ->
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
        val visibility_alpha_milli = record.visibility_rule?.let { rule ->
            ReferencePresentationPolicy.label_alpha_milli(
                rule,
                ReferencePresentationPolicy.centizoom(zoom),
            )
        } ?: ReferencePresentationPolicy.full_alpha_milli
        fun scaled_alpha(alpha: Int): Int {
            return ((alpha * visibility_alpha_milli) + 500) / 1_000
        }
        return DictionaryLabelStyle(
            color = record.color,
            text_size = sp(record.base_text_size_sp * scale),
            alpha = scaled_alpha(record.alpha),
            halo_color = record.halo_color,
            halo_width_dp = record.halo_width_dp,
            halo_alpha = scaled_alpha(record.halo_alpha),
            typeface_style = record.typeface_style,
            letter_spacing_em = record.letter_spacing_em,
        )
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

    private fun screen_distance_squared(
        first: DictionaryLabelCandidate,
        second: DictionaryLabelCandidate
    ): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return dx * dx + dy * dy
    }

    private fun bbox_intersects_screen(box: RectF, viewport: Viewport): Boolean {
        return box.right >= -LABEL_SCREEN_PADDING_X &&
                box.left <= viewport.width + LABEL_SCREEN_PADDING_X &&
                box.bottom >= -LABEL_SCREEN_PADDING_Y &&
                box.top <= viewport.height + LABEL_SCREEN_PADDING_Y
    }

    private fun dictionary_tile_zoom(viewport_zoom: Double, zooms: Set<Int>): Int? {
        if (zooms.isEmpty()) return null
        val floor_zoom = floor(viewport_zoom).toInt()
        val min_zoom = zooms.minOrNull() ?: return null
        val max_zoom = zooms.maxOrNull() ?: return null
        return floor_zoom.coerceIn(min_zoom, max_zoom)
            .takeIf { it in zooms }
            ?: zooms.filter { it <= floor_zoom }.maxOrNull()
            ?: min_zoom
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
        val request_priority: Int
    ) {
        fun cache_key(): String {
            return "$z/$x/$y"
        }
    }

    private data class DictionaryTileDrawRef(
        val tile: ParsedDictionaryTile,
        val draw_x: Int
    )

    private data class ParsedDictionaryTile(
        val coordinate: ReferenceDictionaryTileCoordinate,
        val boundaries: List<DictionaryLineRecord>,
        val labels: List<DictionaryLabelRecord>
    )

    private data class DictionaryLineRecord(
        val source_layer: String,
        val source_kind: String,
        val class_name: String,
        val protected_area: Boolean,
        val layer_group: ReferenceDictionaryLayerGroup,
        val filter_id: FilterId? = null,
        val geometry: DictionaryGeometry,
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
        val prominence_tier: ProminenceTier? = null,
        val visibility_rule: LabelVisibilityRule? = null,
        val repeat_spacing_px: Int? = null,
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
        val path_effect: PathEffect? = null,
        val stroke_cap: Paint.Cap = Paint.Cap.ROUND,
        val stroke_join: Paint.Join = Paint.Join.ROUND,
    )

    private data class DictionaryLabelStyle(
        val color: Int,
        val text_size: Float,
        val alpha: Int,
        val halo_color: Int,
        val halo_width_dp: Float,
        val halo_alpha: Int,
        val typeface_style: Int,
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
        val sourced_text: SourcedMapText?,
        val line_label: Boolean,
        val dedupe_key: String,
        val style: DictionaryLabelStyle,
        val priority: Int,
        val protected_area: Boolean,
        val is_water: Boolean,
        val bounds: RectF,
        val x: Float = 0f,
        val y: Float = 0f,
        val rotation: Float = 0f,
        val path: Path? = null
    )

    private data class ReferenceDrawCounts(
        val boundaries: Int,
        val labels: Int
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
        val bitmap: Bitmap,
        val zoom: Double,
        val center_x: Double,
        val center_y: Double,
        val width: Int,
        val height: Int,
        val visible_width: Int,
        val visible_height: Int,
        val options: RetainedReferenceOptions,
        val content_revision: Long,
        val boundaries_drawn: Int,
        val labels_drawn: Int
    ) {
        fun matches_options(next: RetainedReferenceOptions): Boolean {
            return options == next
        }

        fun matches_exact(viewport: Viewport, next: RetainedReferenceOptions): Boolean {
            return matches_options(next) &&
                    visible_width == ceil(viewport.width).toInt().coerceAtLeast(1) &&
                    visible_height == ceil(viewport.height).toInt().coerceAtLeast(1) &&
                    kotlin.math.abs(zoom - viewport.zoom) < EXACT_VIEWPORT_EPSILON &&
                    kotlin.math.abs(center_x - viewport.center_x) < EXACT_VIEWPORT_EPSILON &&
                    kotlin.math.abs(center_y - viewport.center_y) < EXACT_VIEWPORT_EPSILON
        }
    }

    private data class RetainedReferenceFade(
        val previous: RetainedReferenceFrame,
        val start_ms: Long
    )

    private companion object {
        const val MAP_TILE_SIZE = 256.0
        const val DICTIONARY_EXTENT = 4096.0
        const val MAX_MEMORY_TILES = 128
        const val DICTIONARY_TILE_WORKERS = 4
        const val MAX_LABEL_TEXT_LENGTH = 48
        const val MAP_LABEL_TEXT_SCALE_MIN = 1f
        const val MAP_LABEL_TEXT_SCALE_MAX = 1.75f
        const val LABEL_COLLISION_PADDING_PX = 4f
        const val LABEL_SCREEN_PADDING_X = 96f
        const val LABEL_SCREEN_PADDING_Y = 72f
        const val LABEL_AREA_PER_ITEM_PX = 118_000f
        const val MIN_LABELS_PER_VIEWPORT = 9
        const val MAX_LABELS_PER_VIEWPORT = 22
        const val MIN_LINE_LABEL_PATH_FRACTION = 0.8f
        const val MIN_WATER_LINE_LABEL_PATH_FRACTION = 0.62f
        const val WATER_LINE_LABEL_REPEAT_DISTANCE_PX = 260f
        const val PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW = 2
        const val RETAINED_FRAME_PADDING_FRACTION = 0.16f
        const val RETAINED_FRAME_PADDING_MIN_PX = 128
        const val RETAINED_FRAME_PADDING_MAX_PX = 256
        const val RETAINED_FRAME_FADE_MS = 220f
        const val MAX_RETAINED_BITMAP_SIZE = 4096
        const val MAX_RETAINED_ZOOM_DELTA = 2.5
        const val EXACT_VIEWPORT_EPSILON = 0.000_001
        val REFERENCE_TOKEN_SEPARATOR = Regex("[^a-z0-9]+")
        val ROAD_SHIELD_LABEL = Regex("^\\d+[A-Z]?$")
        val DICTIONARY_LABEL_HALO = Color.argb(234, 4, 10, 14)
    }
}
