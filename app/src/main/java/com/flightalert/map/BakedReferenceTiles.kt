@file:Suppress(
    "FunctionName",
    "PrivatePropertyName",
)

package com.flightalert.map

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal class BakedReferenceTileStore(context: Context) {
    private val package_roots = listOfNotNull(
        context.getExternalFilesDir(null)?.let {
            File(it, "reference/world-basemap-v2-classified")
        },
        File(context.filesDir, "reference/world-basemap-v2-classified")
    )
    private val scratch = ThreadLocal.withInitial { ByteArray(INFLATE_BUFFER_BYTES) }
    private val stored_blob_cache_lock = Any()
    private val stored_blob_cache =
        LinkedHashMap<StoredBlobKey, ByteArray>(STORED_BLOB_CACHE_INITIAL_CAPACITY, 0.75f, true)
    private var stored_blob_cache_bytes = 0
    private val read_permits = Semaphore(BAKED_READ_CONCURRENCY)
    @Volatile private var index: BakedReferenceIndex? = null
    @Volatile private var unavailable = false

    fun load_tile(overlay: ReferenceTileOverlay, z: Int, x: Int, y: Int): Bitmap? {
        read_permits.acquire()
        return try {
            val active_index = index ?: load_index() ?: return null
            if (z !in MIN_PACKAGE_ZOOM..MAX_PACKAGE_ZOOM) return null
            val record = active_index.find(tile_key(z, x, y)) ?: return null
            val chunk = File(active_index.root, active_index.chunk_paths[record.chunk_id])
            val payload = read_tile_bytes(
                chunk = chunk,
                storage_codec = record.storage_codec,
                storage_offset = record.payload_storage_offset,
                storage_length = record.payload_storage_length,
                uncompressed_offset = record.payload_uncompressed_offset,
                uncompressed_length = record.payload_uncompressed_length
            ) ?: return null
            val classes = read_tile_bytes(
                chunk = chunk,
                storage_codec = record.storage_codec,
                storage_offset = record.class_storage_offset,
                storage_length = record.class_storage_length,
                uncompressed_offset = record.class_uncompressed_offset,
                uncompressed_length = record.class_uncompressed_length
            ) ?: return null
            if (payload.size != TILE_RGBA_BYTES || classes.size != TILE_CLASS_BYTES) return null
            bitmap_from_payload(overlay, payload, classes)
        } finally {
            read_permits.release()
        }
    }

    private fun load_index(): BakedReferenceIndex? {
        if (unavailable) return null
        synchronized(this) {
            index?.let { return it }
            for (root in package_roots) {
                val file = File(root, INDEX_FILE_NAME)
                val loaded = BakedReferenceIndex.open(root, file)
                if (loaded != null) {
                    index = loaded
                    return loaded
                }
            }
            unavailable = true
            return null
        }
    }

    private fun read_tile_bytes(
        chunk: File,
        storage_codec: Int,
        storage_offset: Long,
        storage_length: Int,
        uncompressed_offset: Int,
        uncompressed_length: Int
    ): ByteArray? {
        if (!chunk.exists() || storage_length <= 0 || uncompressed_length <= 0) return null
        return try {
            if (storage_codec == STORAGE_RAW) {
                read_stored_bytes(chunk, storage_offset, storage_length)
            } else {
                val stored = read_cached_stored_blob(chunk, storage_offset, storage_length)
                    ?: return null
                inflate_slice(
                    stored = stored,
                    start = uncompressed_offset,
                    length = uncompressed_length
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun read_cached_stored_blob(
        chunk: File,
        storage_offset: Long,
        storage_length: Int
    ): ByteArray? {
        val key = StoredBlobKey(chunk.absolutePath, storage_offset, storage_length)
        synchronized(stored_blob_cache_lock) {
            stored_blob_cache[key]?.let { return it }
        }
        val stored = read_stored_bytes(chunk, storage_offset, storage_length) ?: return null
        if (storage_length > STORED_BLOB_CACHE_MAX_SINGLE_BYTES) return stored
        synchronized(stored_blob_cache_lock) {
            stored_blob_cache[key]?.let { return it }
            stored_blob_cache[key] = stored
            stored_blob_cache_bytes += stored.size
            trim_stored_blob_cache_locked()
        }
        return stored
    }

    private fun trim_stored_blob_cache_locked() {
        while (stored_blob_cache_bytes > STORED_BLOB_CACHE_MAX_BYTES && stored_blob_cache.isNotEmpty()) {
            val first_key = stored_blob_cache.keys.first()
            val removed = stored_blob_cache.remove(first_key) ?: break
            stored_blob_cache_bytes -= removed.size
        }
    }

    private fun read_stored_bytes(
        chunk: File,
        storage_offset: Long,
        storage_length: Int
    ): ByteArray? {
        if (storage_offset < 0L || storage_length <= 0) return null
        return RandomAccessFile(chunk, "r").use { file ->
            file.seek(storage_offset)
            ByteArray(storage_length).also { file.readFully(it) }
        }
    }

    private fun inflate_slice(stored: ByteArray, start: Int, length: Int): ByteArray? {
        if (start < 0 || length <= 0) return null
        val target = ByteArray(length)
        val buffer = scratch.get() ?: ByteArray(INFLATE_BUFFER_BYTES).also { scratch.set(it) }
        val inflater = Inflater()
        return try {
            inflater.setInput(stored)
            var produced = 0
            var copied = 0
            val end = start + length
            while (copied < length && !inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                val chunk_start = produced
                val chunk_end = produced + read
                if (chunk_end > start && chunk_start < end) {
                    val copy_start = maxOf(start, chunk_start)
                    val copy_end = minOf(end, chunk_end)
                    val source_start = copy_start - chunk_start
                    val copy_length = copy_end - copy_start
                    buffer.copyInto(
                        destination = target,
                        destinationOffset = copied,
                        startIndex = source_start,
                        endIndex = source_start + copy_length
                    )
                    copied += copy_length
                }
                produced = chunk_end
            }
            if (copied == length) target else null
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun bitmap_from_payload(
        overlay: ReferenceTileOverlay,
        payload: ByteArray,
        classes: ByteArray
    ): Bitmap {
        val pixels = IntArray(TILE_PIXELS)
        var source = 0
        for (i in 0 until TILE_PIXELS) {
            val class_id = classes[i].toInt() and 0xff
            if (class_visible_for_overlay(overlay, class_id)) {
                val r = payload[source].toInt() and 0xff
                val g = payload[source + 1].toInt() and 0xff
                val b = payload[source + 2].toInt() and 0xff
                val a = payload[source + 3].toInt() and 0xff
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            source += 4
        }
        return Bitmap.createBitmap(pixels, TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
    }

    private fun class_visible_for_overlay(overlay: ReferenceTileOverlay, class_id: Int): Boolean {
        return when (overlay) {
            ReferenceTileOverlay.WORLD_BOUNDARIES_AND_PLACES ->
                class_id in BOUNDARY_PLACE_CLASSES

            ReferenceTileOverlay.WORLD_TRANSPORTATION ->
                class_id in TRANSPORTATION_CLASSES
        }
    }

    private class BakedReferenceIndex(
        val root: File,
        private val index_file: File,
        val chunk_paths: List<String>,
        private val record_count: Int,
        private val record_table_offset: Long
    ) {
        fun find(key: Long): BakedReferenceRecord? {
            return try {
                RandomAccessFile(index_file, "r").use { file ->
                    var low = 0
                    var high = record_count - 1
                    while (low <= high) {
                        val mid = (low + high).ushr(1)
                        file.seek(record_table_offset + mid.toLong() * RECORD_SIZE_BYTES)
                        val record_key = file.readLong()
                        when {
                            record_key < key -> low = mid + 1
                            record_key > key -> high = mid - 1
                            else -> return read_record(file, record_key)
                        }
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun read_record(file: RandomAccessFile, key: Long): BakedReferenceRecord {
            return BakedReferenceRecord(
                key = key,
                chunk_id = file.readInt(),
                payload_uncompressed_offset = file.readInt(),
                payload_uncompressed_length = file.readInt(),
                class_uncompressed_offset = file.readInt(),
                class_uncompressed_length = file.readInt(),
                payload_storage_offset = file.readLong(),
                payload_storage_length = file.readInt(),
                class_storage_offset = file.readLong(),
                class_storage_length = file.readInt(),
                storage_codec = file.readUnsignedByte()
            )
        }

        companion object {
            fun open(root: File, file: File): BakedReferenceIndex? {
                if (!file.exists() || file.length() <= 0L) return null
                return try {
                    RandomAccessFile(file, "r").use { input ->
                        val magic = ByteArray(MAGIC.size)
                        input.readFully(magic)
                        if (!magic.contentEquals(MAGIC)) return null
                        val version = input.readInt()
                        if (version != INDEX_VERSION) return null
                        val record_count = input.readInt()
                        val chunk_count = input.readInt()
                        if (record_count <= 0 || chunk_count <= 0) return null
                        val chunks = ArrayList<String>(chunk_count)
                        repeat(chunk_count) {
                            val length = input.readInt()
                            if (length <= 0 || length > MAX_CHUNK_PATH_BYTES) return null
                            val bytes = ByteArray(length)
                            input.readFully(bytes)
                            chunks += String(bytes, Charsets.UTF_8)
                        }
                        BakedReferenceIndex(
                            root = root,
                            index_file = file,
                            chunk_paths = chunks,
                            record_count = record_count,
                            record_table_offset = input.filePointer
                        )
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private data class BakedReferenceRecord(
        val key: Long,
        val chunk_id: Int,
        val payload_uncompressed_offset: Int,
        val payload_uncompressed_length: Int,
        val class_uncompressed_offset: Int,
        val class_uncompressed_length: Int,
        val payload_storage_offset: Long,
        val payload_storage_length: Int,
        val class_storage_offset: Long,
        val class_storage_length: Int,
        val storage_codec: Int
    )

    private data class StoredBlobKey(
        val chunk_path: String,
        val storage_offset: Long,
        val storage_length: Int
    )

    private companion object {
        const val INDEX_FILE_NAME = "fai6-index-v1.bin"
        const val INDEX_VERSION = 1
        const val RECORD_SIZE_BYTES = 53L
        const val MAX_CHUNK_PATH_BYTES = 512
        const val TILE_SIZE = 256
        const val TILE_PIXELS = TILE_SIZE * TILE_SIZE
        const val TILE_RGBA_BYTES = TILE_PIXELS * 4
        const val TILE_CLASS_BYTES = TILE_PIXELS
        const val INFLATE_BUFFER_BYTES = 64 * 1024
        const val STORAGE_RAW = 0
        const val STORED_BLOB_CACHE_INITIAL_CAPACITY = 8
        const val STORED_BLOB_CACHE_MAX_BYTES = 8 * 1024 * 1024
        const val STORED_BLOB_CACHE_MAX_SINGLE_BYTES = 8 * 1024 * 1024
        const val BAKED_READ_CONCURRENCY = 1
        const val MIN_PACKAGE_ZOOM = 0
        const val MAX_PACKAGE_ZOOM = 16
        val MAGIC = byteArrayOf(70, 65, 73, 54, 73, 68, 88, 49)
        val BOUNDARY_PLACE_CLASSES = setOf(1, 2, 3, 4, 5, 14, 15, 16, 17, 18, 19, 20, 22, 24)
        val TRANSPORTATION_CLASSES = setOf(6, 7, 8, 9, 10, 11, 12, 13, 21)

        fun tile_key(z: Int, x: Int, y: Int): Long {
            return (z.toLong() shl 40) or (x.toLong() shl 20) or y.toLong()
        }
    }
}
