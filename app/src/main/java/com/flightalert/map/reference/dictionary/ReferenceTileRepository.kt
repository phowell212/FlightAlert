@file:Suppress("FunctionName", "LocalVariableName", "PropertyName", "PrivatePropertyName")

package com.flightalert.map.reference.dictionary

import android.content.Context
import android.os.SystemClock
import java.io.Closeable
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
    private val request_generation = AtomicLong()
    private val tile_cache = ReferenceDictionaryTileMemoryCache<ParsedDictionaryTile>(
        normalLimit = MAX_MEMORY_TILES,
        lowZoomLimit = MAX_LOW_ZOOM_MEMORY_TILES,
    )
    private val requested_tiles = HashSet<String>()
    private val desired_tile_keys = HashSet<String>()
    private val next_desired_tile_keys = HashSet<String>()
    private val worker_id = AtomicInteger()
    private val tile_task_sequence = AtomicLong()
    private val tile_request_admission_lock = Any()
    private val tile_executor = ThreadPoolExecutor(
        DICTIONARY_TILE_WORKERS,
        DICTIONARY_TILE_WORKERS,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue(),
    ) { runnable ->
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-reference-dictionary-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

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
            discard_queued_repository_work()
            synchronized(tile_cache) {
                package_generation.incrementAndGet()
                request_generation.incrementAndGet()
                package_snapshot = null
                package_retry_after_ms = 0L
                tile_cache.clear()
                requested_tiles.clear()
                desired_tile_keys.clear()
                next_desired_tile_keys.clear()
            }
            package_probe_pending.set(null)
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
            tile_executor.shutdownNow().forEach(::discard_repository_work)
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
        val discarded = ArrayList<TileRequestTask>()
        var submitted = false
        synchronized(tile_request_admission_lock) {
            purge_obsolete_tile_requests_locked(discarded)
            if (!closed && !tile_executor.isShutdown) {
                val queue = tile_executor.queue
                if (queue.size >= MAX_PENDING_DICTIONARY_TILE_REQUESTS) {
                    worst_queued_tile_request(queue)?.let { worst ->
                        if (queue.remove(worst)) discarded += worst
                    }
                }
                if (queue.size < MAX_PENDING_DICTIONARY_TILE_REQUESTS) {
                    try {
                        tile_executor.execute(probe)
                        submitted = true
                    } catch (_: RejectedExecutionException) {
                        // Cleanup below releases rejected work.
                    }
                }
            }
        }
        discarded.forEach(TileRequestTask::discard)
        if (!submitted) probe.discard()
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
        val task_package_generation = synchronized(tile_cache) {
            if (tile_cache.contains(cache_key, tile.low_zoom_profile())) return 0
            if (!requested_tiles.add(cache_key)) return 0
            package_generation.get()
        }
        val task = TileRequestTask(
            tile = tile,
            cache_key = cache_key,
            generation = generation,
            package_generation = task_package_generation,
            additionally_relevant = additionally_relevant,
            sequence = tile_task_sequence.incrementAndGet(),
        )
        return if (submit_tile_request(task)) 1 else 0
    }

    fun update_desired_tile_keys(
        target_tiles: List<VisibleDictionaryTile>,
        fallback_tiles: List<VisibleDictionaryTile> = emptyList(),
        include_target_tiles: Boolean = true,
    ): Long {
        val generation = synchronized(tile_cache) {
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
        purge_obsolete_tile_requests()
        return generation
    }

    private fun tile_request_still_relevant(cache_key: String, generation: Long): Boolean {
        if (closed) return false
        if (generation == request_generation.get()) return true
        return synchronized(tile_cache) {
            desired_tile_keys.contains(cache_key)
        }
    }

    private fun empty_parsed_tile(tile: VisibleDictionaryTile): ParsedDictionaryTile {
        return ParsedDictionaryTile(
            coordinate = ReferenceDictionaryTileCoordinate(z = tile.z, x = tile.x, y = tile.y),
            boundaries = emptyList(),
            labels = emptyList()
        )
    }

    private fun submit_tile_request(task: TileRequestTask): Boolean {
        val discarded = ArrayList<TileRequestTask>()
        var submitted = false
        synchronized(tile_request_admission_lock) {
            purge_obsolete_tile_requests_locked(discarded)
            if (!closed && !tile_executor.isShutdown && task.is_relevant()) {
                val queue = tile_executor.queue
                if (queue.size >= MAX_PENDING_DICTIONARY_TILE_REQUESTS) {
                    val worst = worst_queued_tile_request(queue)
                    if (worst != null &&
                        task < worst &&
                        queue.remove(worst)
                    ) {
                        discarded += worst
                    }
                }
                if (queue.size < MAX_PENDING_DICTIONARY_TILE_REQUESTS) {
                    try {
                        tile_executor.execute(task)
                        submitted = true
                    } catch (_: RejectedExecutionException) {
                        // Cleanup below releases rejected work.
                    }
                }
            }
        }
        discarded.forEach(TileRequestTask::discard)
        if (!submitted) task.discard()
        return submitted
    }

    private fun purge_obsolete_tile_requests() {
        val discarded = ArrayList<TileRequestTask>()
        synchronized(tile_request_admission_lock) {
            purge_obsolete_tile_requests_locked(discarded)
        }
        discarded.forEach(TileRequestTask::discard)
    }

    private fun purge_obsolete_tile_requests_locked(discarded: MutableList<TileRequestTask>) {
        val queue = tile_executor.queue
        for (queued in queue) {
            val task = queued as? TileRequestTask ?: continue
            if (!task.is_relevant() && queue.remove(task)) discarded += task
        }
    }

    private fun worst_queued_tile_request(queue: Collection<Runnable>): TileRequestTask? {
        var worst: TileRequestTask? = null
        for (queued in queue) {
            val candidate = queued as? TileRequestTask ?: continue
            if (worst == null || candidate > worst) worst = candidate
        }
        return worst
    }

    private fun discard_queued_repository_work() {
        val discarded = ArrayList<Runnable>()
        synchronized(tile_request_admission_lock) {
            val queue = tile_executor.queue
            for (queued in queue) {
                if (queue.remove(queued)) discarded += queued
            }
        }
        discarded.forEach(::discard_repository_work)
    }

    private fun discard_repository_work(task: Runnable) {
        when (task) {
            is TileRequestTask -> task.discard()
            is PackageProbe -> task.discard()
        }
    }

    private inner class TileRequestTask(
        private val tile: VisibleDictionaryTile,
        private val cache_key: String,
        private val generation: Long,
        private val package_generation: Long,
        private val additionally_relevant: (() -> Boolean)?,
        private val sequence: Long,
    ) : Runnable, Comparable<Runnable> {
        override fun run() {
            var request_became_obsolete = false
            val keep_loading = {
                if (request_became_obsolete) {
                    false
                } else {
                    val relevant = is_relevant()
                    if (!relevant) request_became_obsolete = true
                    relevant
                }
            }
            var published = false
            try {
                if (!keep_loading()) return
                val payload = package_store.read_tile_payload(
                    z = tile.z,
                    x = tile.x,
                    y = tile.y,
                    request_is_relevant = keep_loading,
                )
                if (!keep_loading()) return
                val parsed = if (payload != null) {
                    tile_decoder.decode(
                        payload = payload,
                        outline_visibility_centizoom = tile.outline_visibility_centizoom,
                        request_is_relevant = keep_loading,
                    ) ?: return
                } else {
                    empty_parsed_tile(tile)
                }
                synchronized(tile_cache) {
                    if (!keep_loading()) return@synchronized
                    tile_cache.put(cache_key, tile.low_zoom_profile(), parsed)
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
                        desired_tile_keys.contains(cache_key)
                    ) {
                        replacement_generation = request_generation.get()
                    }
                }
                replacement_generation?.let { current_generation ->
                    request_tile_if_needed(tile, current_generation)
                }
                if (published || keep_loading()) request_redraw()
            }
        }

        fun is_relevant(): Boolean =
            !closed &&
                package_generation == this@ReferenceTileRepository.package_generation.get() &&
                (
                    tile_request_still_relevant(cache_key, generation) ||
                        additionally_relevant?.invoke() == true
                    )

        fun discard() {
            synchronized(tile_cache) {
                requested_tiles.remove(cache_key)
            }
        }

        override fun compareTo(other: Runnable): Int {
            val task = other as? TileRequestTask ?: return 1
            if (generation != task.generation) return task.generation.compareTo(generation)
            if (tile.core_visible != task.tile.core_visible) {
                return if (tile.core_visible) -1 else 1
            }
            if (tile.request_priority != task.tile.request_priority) {
                return tile.request_priority.compareTo(task.tile.request_priority)
            }
            return sequence.compareTo(task.sequence)
        }
    }

    private inner class PackageProbe(
        val generation: Long,
    ) : Runnable, Comparable<Runnable> {
        override fun run() {
            probe_package(this)
        }

        fun discard() {
            package_probe_pending.compareAndSet(this, null)
        }

        override fun compareTo(other: Runnable): Int =
            if (other is PackageProbe) 0 else -1
    }

    private companion object {
        const val MAX_MEMORY_TILES = 256
        const val MAX_LOW_ZOOM_MEMORY_TILES = 256
        const val DICTIONARY_TILE_WORKERS = 4
        const val MAX_PENDING_DICTIONARY_TILE_REQUESTS = 192
    }
}
