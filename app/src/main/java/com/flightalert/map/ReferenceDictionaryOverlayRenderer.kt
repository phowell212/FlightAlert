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
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
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
    private val frame_destination = RectF()
    private val bitmap_paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val content_revision = AtomicLong()
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
        public_lands_enabled: Boolean,
        interaction_active: Boolean
    ): ReferenceDictionaryOverlayDrawStats {
        if (!labels_enabled && !borders_enabled) {
            return ready_empty_stats()
        }
        val package_info = package_store.package_info_if_available()
            ?: return unavailable_stats()
        val tile_zoom = dictionary_tile_zoom(viewport.zoom, package_info.zooms)
            ?: return unavailable_stats()
        val now_ms = SystemClock.elapsedRealtime()
        val options = RetainedReferenceOptions(
            labels_enabled = labels_enabled,
            borders_enabled = borders_enabled,
            public_lands_enabled = public_lands_enabled,
            label_text_scale_key = (label_text_scale * 1000f).roundToInt()
        )
        val retained_drawn_for_interaction = interaction_active &&
                draw_retained_frame_if_available(canvas, viewport, options, now_ms)
        val draw_viewport = retained_frame_viewport(viewport)
        build_visible_tile_list(draw_viewport, viewport, tile_zoom)
        if (visible_tiles.isEmpty()) return ready_empty_stats()

        draw_tiles.clear()
        var requested = 0
        var missing = 0
        var core_missing = 0
        var padded_prefetch_requests = 0
        for (tile in visible_tiles) {
            val parsed = synchronized(tile_cache) { tile_cache[tile.key] }
            if (parsed != null) {
                draw_tiles += DictionaryTileDrawRef(
                    tile = parsed,
                    draw_x = tile.draw_x
                )
            } else {
                missing++
                if (tile.core_visible) {
                    core_missing++
                    requested += request_tile_if_needed(tile)
                } else if (padded_prefetch_requests < PADDED_TILE_PREFETCH_REQUESTS_PER_DRAW) {
                    val queued = request_tile_if_needed(tile)
                    requested += queued
                    padded_prefetch_requests += queued
                }
            }
        }

        val ready = core_missing == 0
        if (retained_drawn_for_interaction) {
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = if (ready) 0 else maxOf(requested, missing),
                boundaries_drawn = 0,
                labels_drawn = 0,
                retained_frame_drawn = true,
                fading = false
            )
        }
        if ((interaction_active || !ready) &&
            draw_retained_frame_if_available(canvas, viewport, options, now_ms)
        ) {
            return ReferenceDictionaryOverlayDrawStats(
                available = true,
                ready = ready,
                visible_tiles = visible_tiles.size,
                loaded_tiles = draw_tiles.size,
                requested_tiles = maxOf(requested, missing),
                boundaries_drawn = 0,
                labels_drawn = 0,
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
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = true,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = 0,
                    boundaries_drawn = exact.boundaries_drawn,
                    labels_drawn = exact.labels_drawn,
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
                public_lands_enabled = public_lands_enabled,
                options = options
            )
            if (rendered != null) {
                replace_retained_frame(rendered, fade_from_previous = true, now_ms = now_ms)
                draw_retained_frame_with_transition(canvas, viewport, rendered, now_ms)
                return ReferenceDictionaryOverlayDrawStats(
                    available = true,
                    ready = true,
                    visible_tiles = visible_tiles.size,
                    loaded_tiles = draw_tiles.size,
                    requested_tiles = 0,
                    boundaries_drawn = rendered.boundaries_drawn,
                    labels_drawn = rendered.labels_drawn,
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
            public_lands_enabled = public_lands_enabled
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

    fun clear() {
        synchronized(tile_cache) {
            tile_cache.clear()
            requested_tiles.clear()
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

    private fun request_tile_if_needed(tile: VisibleDictionaryTile): Int {
        synchronized(tile_cache) {
            if (!requested_tiles.add(tile.key)) return 0
        }
        tile_executor.execute {
            try {
                val payload = package_store.read_tile_payload(
                    z = tile.z,
                    x = tile.x,
                    y = tile.y
                )
                val parsed = if (payload != null) {
                    parse_tile_payload(payload)
                } else {
                    empty_parsed_tile(tile)
                }
                synchronized(tile_cache) {
                    tile_cache[tile.key] = parsed
                    content_revision.incrementAndGet()
                }
            } catch (_: Exception) {
                // Missing or corrupt baked reference data is treated as unavailable real data.
            } finally {
                synchronized(tile_cache) {
                    requested_tiles.remove(tile.key)
                }
                request_redraw()
            }
        }
        return 1
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
        public_lands_enabled: Boolean,
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
            labels_enabled = labels_enabled,
            borders_enabled = borders_enabled,
            label_text_scale = label_text_scale,
            public_lands_enabled = public_lands_enabled
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
        public_lands_enabled: Boolean
    ): ReferenceDrawCounts {
        var boundaries_drawn = 0
        if (borders_enabled) {
            for (tile in tiles) {
                for (record in tile.tile.boundaries) {
                    if (!public_lands_enabled && record.protected_area) continue
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
                public_lands_enabled = public_lands_enabled
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
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = dp(style.stroke_width_dp + style.halo_width_dp)
            paint.color = with_alpha(Color.rgb(4, 10, 14), style.halo_alpha)
            canvas.drawPath(path, paint)
            paint.strokeWidth = dp(style.stroke_width_dp)
            paint.color = with_alpha(style.color, style.alpha)
            canvas.drawPath(path, paint)
            drew = true
        }
        return drew
    }

    private fun draw_labels(
        canvas: Canvas,
        viewport: Viewport,
        tiles: List<DictionaryTileDrawRef>,
        label_text_scale: Float,
        public_lands_enabled: Boolean
    ): Int {
        label_candidates.clear()
        for (tile in tiles) {
            for (record in tile.tile.labels) {
                if (!record.drawable || !record.visible_at(viewport.zoom)) continue
                if (!public_lands_enabled && record.protected_area) continue
                val style = label_style_for(record, label_text_scale)
                val candidate = if (record.line_label && record.geometry?.rings?.isNotEmpty() == true) {
                    line_label_candidate(viewport, tile, record, style)
                } else {
                    point_label_candidate(viewport, tile, record, style)
                }
                if (candidate != null) label_candidates += candidate
            }
        }
        accept_label_candidates(viewport)
        for (index in accepted_labels.indices.reversed()) {
            draw_label_candidate(canvas, accepted_labels[index])
        }
        return accepted_labels.size
    }

    private fun line_label_candidate(
        viewport: Viewport,
        tile: DictionaryTileDrawRef,
        record: DictionaryLabelRecord,
        style: DictionaryLabelStyle
    ): DictionaryLabelCandidate? {
        val ring = record.geometry?.rings?.maxByOrNull { it.point_count } ?: return null
        if (!build_ring_path(line_label_path, viewport, tile, ring)) return null
        path_measure.setPath(line_label_path, false)
        val path_length = path_measure.length
        text_paint.textSize = style.text_size
        val text_width = text_paint.measureText(record.text)
        val min_path_fraction = if (record.source_kind == "water") {
            MIN_WATER_LINE_LABEL_PATH_FRACTION
        } else {
            MIN_LINE_LABEL_PATH_FRACTION
        }
        if (path_length < text_width * min_path_fraction) {
            return if (record.source_kind == "water") {
                null
            } else {
                point_label_candidate(viewport, tile, record, style)
            }
        }
        path_measure.getPosTan(path_length / 2f, line_label_position, null)
        val anchor_x = line_label_position[0]
        val anchor_y = line_label_position[1]
        line_label_path.computeBounds(line_label_bounds, false)
        val label_bounds = label_bbox(
            text = record.text,
            x = anchor_x,
            y = anchor_y,
            font_size = style.text_size,
            width_override = text_width
        )
        label_bounds.union(line_label_bounds.centerX(), line_label_bounds.centerY())
        return DictionaryLabelCandidate(
            text = record.text,
            source_kind = record.source_kind,
            line_label = true,
            dedupe_key = "${record.source_kind}:${record.text.lowercase()}",
            style = style,
            priority = record.priority,
            protected_area = record.protected_area,
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
        return DictionaryLabelCandidate(
            text = record.text,
            source_kind = record.source_kind,
            line_label = false,
            dedupe_key = "${record.source_kind}:${record.text.lowercase()}",
            style = style,
            priority = record.priority,
            protected_area = record.protected_area,
            bounds = label_bbox(record.text, anchor.x, anchor.y, style.text_size),
            x = anchor.x,
            y = anchor.y,
            rotation = record.rotation
        )
    }

    private fun accept_label_candidates(viewport: Viewport) {
        accepted_labels.clear()
        occupied_label_bounds.clear()
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
                if (candidate.line_label && candidate.source_kind == "water") {
                    val duplicate_nearby = accepted_labels.any { accepted ->
                        accepted.line_label &&
                                accepted.source_kind == "water" &&
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
        text_paint.isAntiAlias = true
        text_paint.isSubpixelText = true
        text_paint.typeface = Typeface.create(Typeface.DEFAULT, style.typeface_style)
        text_paint.textAlign = Paint.Align.CENTER
        text_paint.textSize = style.text_size
        val path_label = candidate.path
        if (path_label != null) {
            draw_text_on_path(canvas, candidate.text, path_label, style)
            return
        }
        val x = candidate.x
        val y = candidate.y
        canvas.withSave {
            if (candidate.rotation != 0f) rotate(candidate.rotation, x, y)
            draw_text_with_halo(canvas, candidate.text, x, y, style)
        }
    }

    private fun draw_text_on_path(
        canvas: Canvas,
        text: String,
        label_path: Path,
        style: DictionaryLabelStyle
    ) {
        path_measure.setPath(label_path, false)
        val path_length = path_measure.length
        text_paint.textAlign = Paint.Align.LEFT
        val h_offset = ((path_length - text_paint.measureText(text)) / 2f).coerceAtLeast(0f)
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = max(dp(style.halo_width_dp), style.text_size * 0.22f)
        text_paint.color = with_alpha(DICTIONARY_LABEL_HALO, style.halo_alpha)
        canvas.drawTextOnPath(text, label_path, h_offset, 0f, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(style.color, style.alpha)
        canvas.drawTextOnPath(text, label_path, h_offset, 0f, text_paint)
    }

    private fun draw_text_with_halo(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        style: DictionaryLabelStyle
    ) {
        text_paint.style = Paint.Style.STROKE
        text_paint.strokeWidth = max(dp(style.halo_width_dp), style.text_size * 0.22f)
        text_paint.color = with_alpha(DICTIONARY_LABEL_HALO, style.halo_alpha)
        canvas.drawText(text, x, y, text_paint)
        text_paint.style = Paint.Style.FILL
        text_paint.strokeWidth = 0f
        text_paint.color = with_alpha(style.color, style.alpha)
        canvas.drawText(text, x, y, text_paint)
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

    private fun parse_tile_payload(payload: ReferenceDictionaryTilePayload): ParsedDictionaryTile {
        val root = JSONObject(payload.raw_json)
        val records = root.optJSONObject("records") ?: JSONObject()
        return ParsedDictionaryTile(
            coordinate = payload.coordinate,
            boundaries = parse_line_records(records.optJSONArray("boundaries")),
            labels = parse_label_records(
                records = records.optJSONArray("labels"),
                z = payload.coordinate.z
            )
        )
    }

    private fun parse_line_records(records: JSONArray?): List<DictionaryLineRecord> {
        if (records == null || records.length() <= 0) return emptyList()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val geometry = parse_geometry(record.optJSONObject("geometry")) ?: continue
                val source_layer = record.optString("sourceLayer", "")
                val source_kind = record.optString("sourceKind", "")
                val class_name = record.optString("class", "")
                val protected_area = protected_area_line(
                    source_layer = source_layer,
                    source_kind = source_kind,
                    class_name = class_name
                )
                add(
                    DictionaryLineRecord(
                        source_layer = source_layer,
                        source_kind = source_kind,
                        class_name = class_name,
                        protected_area = protected_area,
                        geometry = geometry,
                        style = line_style_for(
                            source_layer = source_layer,
                            source_kind = source_kind,
                            class_name = class_name
                        )
                    )
                )
            }
        }
    }

    private fun parse_label_records(
        records: JSONArray?,
        z: Int
    ): List<DictionaryLabelRecord> {
        if (records == null || records.length() <= 0) return emptyList()
        return buildList(records.length()) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index) ?: continue
                val text = record.optString("text", "").trim()
                if (text.isEmpty()) continue
                val source_layer = record.optString("sourceLayer", "")
                val source_kind = record.optString("sourceKind", "")
                val label_placement = record.optString("labelPlacement", "")
                val rank = record.optional_int("rank")
                val protected_area = protected_area_label(
                    text = text,
                    source_layer = source_layer,
                    source_kind = source_kind,
                    class_name = record.optString("class", "")
                )
                if (!should_draw_label(
                        text = text,
                        source_kind = source_kind,
                        source_layer = source_layer,
                        label_placement = label_placement,
                        rank = rank,
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
                        priority = label_priority(
                            text = text,
                            source_kind = source_kind,
                            source_layer = source_layer,
                            label_placement = label_placement,
                            rank = rank,
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
                        min_zoom = record.optional_double("minZoom"),
                        max_zoom = record.optional_double("maxZoom"),
                        anchor = anchor,
                        geometry = geometry
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
        if (protected_area_label(text, source_layer, source_kind)) {
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
        z: Int
    ): Int {
        val hierarchy_rank = hierarchy_rank(
            source_kind = source_kind,
            source_layer = source_layer,
            rank = rank
        )
        var priority = when {
            protected_area_label(text, source_layer, source_kind) -> 172
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
        label_text_scale: Float
    ): DictionaryLabelStyle {
        val scale = label_text_scale.coerceIn(MAP_LABEL_TEXT_SCALE_MIN, MAP_LABEL_TEXT_SCALE_MAX)
        return DictionaryLabelStyle(
            color = record.color,
            text_size = sp(record.base_text_size_sp * scale),
            alpha = record.alpha,
            halo_width_dp = record.halo_width_dp,
            halo_alpha = record.halo_alpha,
            typeface_style = record.typeface_style
        )
    }

    private fun label_style_template_for(
        text: String,
        source_kind: String,
        source_layer: String,
        label_placement: String,
        rank: Int?,
        z: Int
    ): DictionaryLabelStyleTemplate {
        val tier = hierarchy_tier(
            source_kind = source_kind,
            source_layer = source_layer,
            rank = rank
        )
        if (protected_area_label(text, source_layer, source_kind)) {
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
        if (source_kind == "admin") return false
        return text_terms.endsWith(" park ") ||
                metadata_terms.contains(" park ") ||
                metadata_terms.contains(" parks ") ||
                metadata_terms.contains(" forest ") ||
                metadata_terms.contains(" forests ")
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
                terms.contains(" forest ") ||
                terms.contains(" forests ")
    }

    private fun protected_area_terms(terms: String): Boolean {
        return terms.contains(" wildlife refuge ") ||
                terms.contains(" national wildlife ") ||
                terms.contains(" refuge ") ||
                terms.contains(" national park ") ||
                terms.contains(" state park ") ||
                terms.contains(" county park ") ||
                terms.contains(" regional park ") ||
                terms.contains(" national forest ") ||
                terms.contains(" state forest ") ||
                terms.contains(" forest preserve ") ||
                terms.contains(" nature preserve ") ||
                terms.contains(" preserve ") ||
                terms.contains(" wilderness ") ||
                terms.contains(" recreation area ") ||
                terms.contains(" management area ") ||
                terms.contains(" conservation ") ||
                terms.contains(" natural area ") ||
                terms.contains(" game land ") ||
                terms.contains(" wildlife management ")
    }

    private fun reference_terms(vararg values: String): String {
        val compact = REFERENCE_TOKEN_SEPARATOR
            .replace(values.joinToString(" ").lowercase(), " ")
            .trim()
        return if (compact.isEmpty()) " " else " $compact "
    }

    private fun line_style_for(
        source_layer: String,
        source_kind: String,
        class_name: String
    ): DictionaryLineStyle {
        val source = source_layer.lowercase()
        val normalized_class = class_name.lowercase()
        return when {
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
        width_override: Float? = null
    ): RectF {
        val width_estimate = max(24f, width_override ?: (text.length * font_size * 0.58f))
        val height_estimate = font_size * 1.45f
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

    private data class VisibleDictionaryTile(
        val z: Int,
        val x: Int,
        val draw_x: Int,
        val y: Int,
        val core_visible: Boolean,
        val request_priority: Int
    ) {
        val key: String = "$z/$x/$y"
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
        val priority: Int,
        val color: Int,
        val base_text_size_sp: Float,
        val alpha: Int,
        val halo_width_dp: Float,
        val halo_alpha: Int,
        val typeface_style: Int,
        val rotation: Float,
        val rank: Int?,
        val min_zoom: Double?,
        val max_zoom: Double?,
        val anchor: DictionaryPoint?,
        val geometry: DictionaryGeometry?
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
        val halo_alpha: Int
    )

    private data class DictionaryLabelStyle(
        val color: Int,
        val text_size: Float,
        val alpha: Int,
        val halo_width_dp: Float,
        val halo_alpha: Int,
        val typeface_style: Int
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
        val line_label: Boolean,
        val dedupe_key: String,
        val style: DictionaryLabelStyle,
        val priority: Int,
        val protected_area: Boolean,
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

    private data class RetainedReferenceOptions(
        val labels_enabled: Boolean,
        val borders_enabled: Boolean,
        val public_lands_enabled: Boolean,
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
