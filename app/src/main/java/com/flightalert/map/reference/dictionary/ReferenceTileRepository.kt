@file:Suppress("FunctionName", "LocalVariableName", "PropertyName")

package com.flightalert.map.reference.dictionary

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal class ReferenceTileRepository(
    context: Context,
    private val request_redraw: () -> Unit,
) : Closeable {
    private val package_store = ReferenceDictionaryPackageStore(context)
    private val tile_decoder = ReferenceDictionaryTileDecoder()
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
    private val requested_tiles = HashSet<String>()
    private val desired_tile_keys = HashSet<String>()
    private val next_desired_tile_keys = HashSet<String>()
    private val zoom_ahead_desired_tile_keys = HashSet<String>()
    private val next_zoom_ahead_desired_tile_keys = HashSet<String>()
    private val worker_id = AtomicInteger()
    private val tile_executor = Executors.newFixedThreadPool(DICTIONARY_TILE_WORKERS) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-dictionary-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    fun content_revision(): Long = content_revision.get()

    fun package_check_pending(): Boolean = package_probe_pending.get() != null

    fun reference_class_catalog(): ReferenceClassCatalog? {
        val snapshot = package_snapshot
        if (snapshot != null) return snapshot.catalog
        prepare()
        return null
    }

    fun snapshot(): PackageSnapshot? = package_snapshot

    fun package_generation(): Long = package_generation.get()

    fun request_generation(): Long = request_generation.get()

    fun tile(tile: VisibleDictionaryTile): ParsedDictionaryTile? = synchronized(tile_cache) {
        tile_cache.get(tile.cache_key(), tile.low_zoom_profile())
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
            content_revision.incrementAndGet()
            package_store.close()
        } finally {
            package_lifecycle_lock.unlock()
        }
    }

    override fun close() {
        package_lifecycle_lock.lock()
        try {
            if (closed) return
            closed = true
            tile_executor.shutdownNow()
            clear()
        } finally {
            package_lifecycle_lock.unlock()
        }
    }

    fun prepare() {
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

    fun request_tile_if_needed(
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
                    tile_decoder.decode(
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

    fun update_desired_tile_keys(
        target_tiles: List<VisibleDictionaryTile>,
        fallback_tiles: List<VisibleDictionaryTile> = emptyList(),
        include_target_tiles: Boolean = true,
    ): Long {
        return synchronized(tile_cache) {
            next_desired_tile_keys.clear()
            if (include_target_tiles) {
                target_tiles.forEach { tile ->
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

    fun update_zoom_ahead_desired_tile_keys(
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

    fun clear_zoom_ahead_desired_tile_keys() {
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


    private inner class PackageProbe(
        val generation: Long,
    ) : Runnable {
        override fun run() {
            probe_package(this)
        }
    }

    private companion object {
        const val MAX_MEMORY_TILES = 256
        const val MAX_LOW_ZOOM_MEMORY_TILES = 256
        const val DICTIONARY_TILE_WORKERS = 4
    }
}
