package com.flightalert.map

import android.os.Process
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.TreeSet
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal fun interface MapTileAtomicPublisher {
    fun publish(temp: Path, target: Path)
}

internal object DefaultMapTileAtomicPublisher : MapTileAtomicPublisher {
    override fun publish(temp: Path, target: Path) {
        Files.move(
            temp,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

internal class MapTileDiskCacheRegistry(
    private val cache_factory: (File, Long) -> MapTileDiskCache = { root, max_bytes ->
        MapTileDiskCache(cache_root = root, max_bytes = max_bytes)
    },
) {
    private val registry_lock = Any()
    private val caches = HashMap<Path, RegisteredCache>()

    fun cache(cache_root: File, max_bytes: Long): MapTileDiskCache {
        val canonical_root = cache_root.canonicalFile.toPath().normalize()
        synchronized(registry_lock) {
            val existing = caches[canonical_root]
            if (existing != null) {
                require(existing.max_bytes == max_bytes) {
                    "one map tile cache root cannot have multiple byte budgets"
                }
                return existing.cache
            }
            val cache = cache_factory(cache_root, max_bytes)
            caches[canonical_root] = RegisteredCache(max_bytes, cache)
            return cache
        }
    }

    private data class RegisteredCache(
        val max_bytes: Long,
        val cache: MapTileDiskCache,
    )
}

internal object ProcessMapTileDiskCaches {
    private val registry = MapTileDiskCacheRegistry()

    fun cache(cache_root: File, max_bytes: Long): MapTileDiskCache =
        registry.cache(cache_root, max_bytes)
}

internal class MapTileDiskCache(
    cache_root: File,
    private val max_bytes: Long,
    private val executor: Executor = default_map_tile_disk_executor(),
    private val atomic_publisher: MapTileAtomicPublisher = DefaultMapTileAtomicPublisher,
    private val now_ms: () -> Long = System::currentTimeMillis,
    private val before_inventory_scan: () -> Unit = {},
    private val before_file_delete: (Path) -> Unit = {},
    private val before_temp_create: (Path) -> Unit = {},
) : Closeable {
    private val cache_root = cache_root.toPath().toAbsolutePath().normalize()
    private val write_lock = Any()
    private val state_lock = Any()
    private var entries = HashMap<Path, CacheEntry>()
    private var eviction_order = new_eviction_order()
    private val pinned_paths = HashMap<Path, Int>()
    private val evicting_paths = HashSet<Path>()
    private val write_reserved_paths = HashSet<Path>()
    private val pending_accesses = ArrayList<PendingAccess>()
    private val initialization_task_scheduled = AtomicBoolean()
    private val initialization_retry_requested = AtomicBoolean()
    private val temp_sequence = AtomicLong()
    private var total_bytes = 0L
    private var access_sequence = 0L
    private var pending_access_sequence = 0L
    private var initialized = false

    init {
        require(max_bytes >= 0L) { "map tile disk-cache budget must not be negative" }
        schedule_initialization()
    }

    fun write_async(file: File, bytes: ByteArray) {
        schedule {
            synchronized(write_lock) {
                if (!initialize_and_trim()) return@synchronized
                publish(file, bytes)
            }
        }
    }

    fun <T> read_if_fresh(
        file: File,
        max_age_ms: Long,
        reader: (File) -> T?,
    ): T? {
        if (max_age_ms <= 0L) return null
        val path = canonical_tile_path(file, require_regular_file = true) ?: return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        if (size <= 0L) return null
        val modified = runCatching {
            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        }.getOrNull() ?: return null
        if (now_ms() - modified >= max_age_ms) return null
        val metadata = synchronized(state_lock) {
            if (path in evicting_paths || path in write_reserved_paths) return null
            pinned_paths[path] = (pinned_paths[path] ?: 0) + 1
            if (initialized) reconcile_entry_locked(path, size, modified)
            ReadMetadata(size, modified)
        }

        var read_succeeded = false
        try {
            val result = reader(file)
            read_succeeded = result != null
            return result
        } finally {
            var trim_needed = false
            var initialization_needed = false
            synchronized(state_lock) {
                if (read_succeeded) {
                    if (initialized) {
                        reconcile_entry_locked(path, metadata.size, metadata.modified)
                        mark_used_locked(path)
                    } else {
                        pending_access_sequence += 1L
                        pending_accesses += PendingAccess(
                            path = path,
                            size = metadata.size,
                            modified = metadata.modified,
                            completion_order = pending_access_sequence,
                        )
                    }
                }
                val remaining = (pinned_paths[path] ?: 1) - 1
                if (remaining <= 0) pinned_paths.remove(path) else pinned_paths[path] = remaining
                trim_needed = initialized && total_bytes > max_bytes
                initialization_needed = !initialized
            }
            if (trim_needed) schedule_trim()
            if (initialization_needed) schedule_initialization()
        }
    }

    override fun close() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    private fun initialize_and_trim(): Boolean {
        synchronized(state_lock) {
            if (initialized) return true
        }
        val scanned = try {
            before_inventory_scan()
            scan_map_tile_files()
        } catch (_: Exception) {
            return false
        }
        val startup_index = build_startup_index(scanned)
        var applied_pending_order = 0L
        synchronized(state_lock) {
            if (initialized) return true
            entries = startup_index.entries
            eviction_order = startup_index.eviction_order
            total_bytes = startup_index.total_bytes
            access_sequence = startup_index.access_sequence
            applied_pending_order = replay_pending_accesses_locked(after_completion_order = 0L)
        }

        while (true) {
            if (!trim_to_budget(max_bytes, excluded_path = null)) {
                synchronized(state_lock) {
                    if (!initialized) reset_index_locked()
                }
                return false
            }
            val completed = synchronized(state_lock) {
                if (initialized) return@synchronized true
                applied_pending_order = replay_pending_accesses_locked(applied_pending_order)
                if (total_bytes <= max_bytes) {
                    pending_accesses.clear()
                    initialized = true
                    true
                } else {
                    false
                }
            }
            if (completed) return true
        }
    }

    private fun publish(file: File, bytes: ByteArray) {
        if (bytes.size.toLong() > max_bytes) return
        val target = canonical_tile_path(file, require_regular_file = false) ?: return
        if (!ensure_safe_parent_directories(target)) return
        val existing_metadata = regular_file_metadata(target)
        val reserved = synchronized(state_lock) {
            if (existing_metadata == null) {
                remove_entry_locked(target)
            } else {
                reconcile_entry_locked(target, existing_metadata.size, existing_metadata.modified)
            }
            if (
                pinned_paths.containsKey(target) ||
                target in write_reserved_paths ||
                target in evicting_paths
            ) {
                false
            } else {
                write_reserved_paths.add(target)
                true
            }
        }
        if (!reserved) return

        var temp: Path? = null
        try {
            if (!make_room(target, bytes.size.toLong())) return
            before_temp_create(target)
            if (!ensure_safe_parent_directories(target)) return
            temp = target.resolveSibling(
                ".${target.fileName}.flightalert-map-tile-${temp_sequence.incrementAndGet()}.tmp",
            )
            // Canonical files stay within budget; the same-directory publish temp is transient
            // and is removed in finally whether the atomic replacement succeeds or fails.
            Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write(bytes)
            }
            atomic_publisher.publish(temp, target)
            val published_size = runCatching { Files.size(target) }.getOrNull()
            if (
                published_size != bytes.size.toLong() ||
                !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            ) {
                runCatching { Files.deleteIfExists(target) }
                synchronized(state_lock) { remove_entry_locked(target) }
                return
            }
            synchronized(state_lock) {
                remove_entry_locked(target)
                put_entry_locked(
                    path = target,
                    size = published_size,
                    last_used_order = next_access_locked(),
                    relative_path = relative_path(target),
                )
            }
        } catch (_: Exception) {
            // The decoded in-memory tile is already published; disk persistence remains best-effort.
        } finally {
            temp?.let { owned_temp -> runCatching { Files.deleteIfExists(owned_temp) } }
            synchronized(state_lock) { write_reserved_paths.remove(target) }
        }
    }

    private fun make_room(target: Path, incoming_size: Long): Boolean {
        val allowed_before_publish = max_bytes - incoming_size
        if (allowed_before_publish < 0L) return false
        val (target_size, desired_existing_bytes) = synchronized(state_lock) {
            val existing_size = entries[target]?.size ?: 0L
            existing_size to (total_bytes - existing_size).coerceAtLeast(0L)
        }
        if (desired_existing_bytes <= allowed_before_publish) return true
        return trim_to_budget(
            target_bytes = saturated_add(allowed_before_publish, target_size),
            excluded_path = target,
        ) && synchronized(state_lock) {
            total_bytes - (entries[target]?.size ?: 0L) <= allowed_before_publish
        }
    }

    private fun trim_to_budget(target_bytes: Long, excluded_path: Path?): Boolean {
        val blocked = HashSet<Path>()
        while (true) {
            val candidate = synchronized(state_lock) {
                if (total_bytes <= target_bytes) return true
                reserve_eviction_candidate_locked(excluded_path, blocked)
            } ?: return false
            val removed = runCatching {
                before_file_delete(candidate.path)
                Files.deleteIfExists(candidate.path)
            }.getOrDefault(false)
            val absent = removed || !Files.exists(candidate.path, LinkOption.NOFOLLOW_LINKS)
            synchronized(state_lock) {
                evicting_paths.remove(candidate.path)
                if (absent) {
                    remove_entry_locked(candidate.path)
                } else {
                    blocked.add(candidate.path)
                    entries[candidate.path]?.let(::enqueue_eviction_locked)
                }
            }
        }
    }

    private fun scan_map_tile_files(): List<ScannedEntry> {
        if (!Files.isDirectory(cache_root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val scanned = ArrayList<ScannedEntry>()
        Files.list(cache_root).use { children ->
            val iterator = children.iterator()
            while (iterator.hasNext()) {
                val child = iterator.next()
                if (child.fileName.toString() !in MANAGED_TILE_ROOT_NAMES) continue
                if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) continue
                Files.walk(child).use { descendants ->
                    val descendant_iterator = descendants.iterator()
                    while (descendant_iterator.hasNext()) {
                        val path = descendant_iterator.next()
                        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                        if (is_owned_temp(path)) {
                            Files.deleteIfExists(path)
                            continue
                        }
                        if (canonical_tile_path(path.toFile(), require_regular_file = true) == null) {
                            continue
                        }
                        val size = Files.size(path)
                        val modified = Files.getLastModifiedTime(
                            path,
                            LinkOption.NOFOLLOW_LINKS,
                        ).toMillis()
                        scanned += ScannedEntry(
                            path = path.toAbsolutePath().normalize(),
                            size = size,
                            last_modified_ms = modified,
                            relative_path = relative_path(path),
                        )
                    }
                }
            }
        }
        return scanned
    }

    private fun build_startup_index(scanned: List<ScannedEntry>): StartupIndex {
        val startup_entries = HashMap<Path, CacheEntry>(scanned.size)
        val startup_eviction_order = new_eviction_order()
        var startup_total_bytes = 0L
        var startup_access_sequence = 0L
        scanned.sortedWith(compareBy<ScannedEntry>({ it.last_modified_ms }, { it.relative_path }))
            .forEach { scanned_entry ->
                startup_access_sequence += 1L
                val entry = CacheEntry(
                    path = scanned_entry.path,
                    size = scanned_entry.size,
                    last_used_order = startup_access_sequence,
                    relative_path = scanned_entry.relative_path,
                )
                startup_entries[entry.path] = entry
                startup_eviction_order.add(entry.to_eviction_record())
                startup_total_bytes = saturated_add(startup_total_bytes, entry.size)
            }
        return StartupIndex(
            entries = startup_entries,
            eviction_order = startup_eviction_order,
            total_bytes = startup_total_bytes,
            access_sequence = startup_access_sequence,
        )
    }

    private fun canonical_tile_path(file: File, require_regular_file: Boolean): Path? {
        if (!Files.isDirectory(cache_root, LinkOption.NOFOLLOW_LINKS)) return null
        val path = file.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(cache_root)) return null
        val relative = cache_root.relativize(path)
        if (relative.nameCount != 4) return null
        if (relative.getName(0).toString() !in MANAGED_TILE_ROOT_NAMES) return null
        if (!relative.getName(1).toString().is_ascii_digits()) return null
        if (!relative.getName(2).toString().is_ascii_digits()) return null
        val file_name = relative.getName(3).toString()
        if (!file_name.endsWith(".png")) return null
        if (!file_name.removeSuffix(".png").is_ascii_digits()) return null
        var ancestor = cache_root
        for (index in 0..2) {
            ancestor = ancestor.resolve(relative.getName(index))
            if (
                Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)
            ) return null
        }
        if (
            require_regular_file &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) return null
        if (
            !require_regular_file &&
            Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) return null
        return path
    }

    private fun ensure_safe_parent_directories(target: Path): Boolean {
        val relative = cache_root.relativize(target)
        var current = cache_root
        for (index in 0..2) {
            current = current.resolve(relative.getName(index))
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return false
            } else if (!runCatching { Files.createDirectory(current) }.isSuccess) {
                return false
            }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) return false
        }
        return true
    }

    private fun reconcile_entry_locked(path: Path, size: Long, modified: Long) {
        val existing = entries[path]
        if (existing != null && existing.size == size) return
        val access = if (existing != null) existing.last_used_order else next_access_locked()
        put_entry_locked(path, size, access, relative_path(path))
    }

    private fun put_entry_locked(
        path: Path,
        size: Long,
        last_used_order: Long,
        relative_path: String,
    ) {
        val entry = CacheEntry(path, size, last_used_order, relative_path)
        val replaced = entries.put(path, entry)
        if (replaced != null) {
            eviction_order.remove(replaced.to_eviction_record())
            total_bytes = (total_bytes - replaced.size).coerceAtLeast(0L)
        }
        total_bytes = saturated_add(total_bytes, size)
        enqueue_eviction_locked(entry)
    }

    private fun replay_pending_accesses_locked(after_completion_order: Long): Long {
        var latest = after_completion_order
        pending_accesses.asSequence()
            .filter { it.completion_order > after_completion_order }
            .sortedBy { it.completion_order }
            .forEach { access ->
                reconcile_entry_locked(access.path, access.size, access.modified)
                mark_used_locked(access.path)
                latest = access.completion_order
            }
        return latest
    }

    private fun reset_index_locked() {
        entries.clear()
        eviction_order.clear()
        evicting_paths.clear()
        total_bytes = 0L
        access_sequence = 0L
    }

    private fun reserve_eviction_candidate_locked(
        excluded_path: Path?,
        blocked: Set<Path>,
    ): CacheEntry? {
        val iterator = eviction_order.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next()
            val current = entries[record.path] ?: continue
            if (
                current.last_used_order != record.last_used_order ||
                current.size != record.size
            ) {
                iterator.remove()
                continue
            }
            if (
                current.path == excluded_path ||
                current.path in blocked ||
                current.path in pinned_paths ||
                current.path in evicting_paths ||
                current.path in write_reserved_paths
            ) {
                continue
            }
            iterator.remove()
            evicting_paths.add(current.path)
            return current
        }
        return null
    }

    private fun enqueue_eviction_locked(entry: CacheEntry) {
        eviction_order.add(entry.to_eviction_record())
    }

    private fun mark_used_locked(path: Path) {
        val existing = entries[path]
        if (existing != null) {
            eviction_order.remove(existing.to_eviction_record())
            val updated = existing.copy(last_used_order = next_access_locked())
            entries[path] = updated
            enqueue_eviction_locked(updated)
        }
    }

    private fun remove_entry_locked(path: Path) {
        val removed = entries.remove(path) ?: return
        eviction_order.remove(removed.to_eviction_record())
        total_bytes = (total_bytes - removed.size).coerceAtLeast(0L)
    }

    private fun regular_file_metadata(path: Path): ReadMetadata? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        val size = runCatching { Files.size(path) }.getOrNull() ?: return null
        val modified = runCatching {
            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        }.getOrNull() ?: return null
        return ReadMetadata(size, modified)
    }

    private fun schedule_trim() {
        schedule {
            synchronized(write_lock) {
                trim_to_budget(max_bytes, excluded_path = null)
            }
        }
    }

    private fun schedule_initialization() {
        synchronized(state_lock) {
            if (initialized) return
        }
        if (!initialization_task_scheduled.compareAndSet(false, true)) {
            initialization_retry_requested.set(true)
            return
        }
        val accepted = schedule {
            try {
                synchronized(write_lock) { initialize_and_trim() }
            } finally {
                initialization_task_scheduled.set(false)
                if (initialization_retry_requested.getAndSet(false)) {
                    schedule_initialization()
                }
            }
        }
        if (!accepted) initialization_task_scheduled.set(false)
    }

    private fun schedule(action: () -> Unit): Boolean {
        return try {
            executor.execute(action)
            true
        } catch (_: RejectedExecutionException) {
            // A closing renderer may abandon optional disk-cache work.
            false
        }
    }

    private fun next_access_locked(): Long {
        access_sequence = if (access_sequence == Long.MAX_VALUE) 1L else access_sequence + 1L
        return access_sequence
    }

    private fun relative_path(path: Path): String =
        cache_root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')

    private fun is_owned_temp(path: Path): Boolean =
        OWNED_TEMP_NAME.matches(path.fileName.toString())

    private data class CacheEntry(
        val path: Path,
        val size: Long,
        val last_used_order: Long,
        val relative_path: String,
    ) {
        fun to_eviction_record(): EvictionRecord = EvictionRecord(
            path = path,
            size = size,
            last_used_order = last_used_order,
            relative_path = relative_path,
        )
    }

    private data class ScannedEntry(
        val path: Path,
        val size: Long,
        val last_modified_ms: Long,
        val relative_path: String,
    )

    private data class EvictionRecord(
        val path: Path,
        val size: Long,
        val last_used_order: Long,
        val relative_path: String,
    )

    private data class StartupIndex(
        val entries: HashMap<Path, CacheEntry>,
        val eviction_order: TreeSet<EvictionRecord>,
        val total_bytes: Long,
        val access_sequence: Long,
    )

    private data class PendingAccess(
        val path: Path,
        val size: Long,
        val modified: Long,
        val completion_order: Long,
    )

    private data class ReadMetadata(
        val size: Long,
        val modified: Long,
    )

    private companion object {
        fun new_eviction_order(): TreeSet<EvictionRecord> = TreeSet(
            compareBy<EvictionRecord>({ it.last_used_order }, { it.relative_path }),
        )

        val MANAGED_TILE_ROOT_NAMES = setOf(
            "carto_voyager_tiles",
            "carto_voyager_nolabels_tiles",
            "carto_voyager_only_labels_tiles",
            "esri_world_imagery_tiles",
            REFERENCE_BOUNDARY_RASTER_CACHE_ROOT,
        )
        val OWNED_TEMP_NAME = Regex(
            "^\\.[0-9]+\\.png\\.flightalert-map-tile-[0-9]+\\.tmp$",
        )

        fun saturated_add(left: Long, right: Long): Long =
            if (right > 0L && Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

        fun String.is_ascii_digits(): Boolean = isNotEmpty() && all { it in '0'..'9' }
    }
}

private fun default_map_tile_disk_executor(): ExecutorService {
    val worker_id = AtomicInteger()
    return ThreadPoolExecutor(
        1,
        1,
        15_000L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
    ) { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "flightalert-map-tile-disk-${worker_id.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }
}
