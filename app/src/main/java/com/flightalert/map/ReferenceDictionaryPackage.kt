@file:Suppress(
    "FunctionName",
    "LocalVariableName",
    "PackageName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater

internal data class ReferenceDictionaryPackageInfo(
    val package_id: String,
    val schema_version: Int,
    val tile_count: Long,
    val zooms: Set<Int>,
    val complete_declared_scope: Boolean,
    val complete_whole_earth_dictionary: Boolean,
    val package_dir: File
)

internal data class ReferenceDictionaryTileCoordinate(
    val z: Int,
    val x: Int,
    val y: Int
)

internal data class ReferenceDictionaryTileCounts(
    val labels: Int,
    val boundaries: Int,
    val transportation: Int
)

internal data class ReferenceDictionaryTilePayload(
    val coordinate: ReferenceDictionaryTileCoordinate,
    val counts: ReferenceDictionaryTileCounts,
    val raw_json: String
)

internal class ReferenceDictionaryPackageStore(
    private val context: Context,
    private val package_id: String? = null
) : Closeable {
    private var package_checked = false
    private var last_package_check_ms = 0L
    private var package_ref: ReferenceDictionaryPackage? = null

    fun package_info_if_available(): ReferenceDictionaryPackageInfo? {
        return open_package_if_available()?.info
    }

    fun read_tile_payload(
        z: Int,
        x: Int,
        y: Int,
        verify_hash: Boolean = false
    ): ReferenceDictionaryTilePayload? {
        return open_package_if_available()?.read_tile_payload(
            z = z,
            x = x,
            y = y,
            verify_hash = verify_hash
        )
    }

    override fun close() {
        package_ref?.close()
        package_ref = null
        package_checked = false
    }

    private fun open_package_if_available(): ReferenceDictionaryPackage? {
        package_ref?.let { return it }
        val now_ms = SystemClock.elapsedRealtime()
        if (package_checked && now_ms - last_package_check_ms < PACKAGE_MISSING_RETRY_MS) {
            return null
        }
        package_checked = true
        last_package_check_ms = now_ms
        for (candidate in ReferenceDictionaryPackage.package_candidates(context, package_id)) {
            val opened = ReferenceDictionaryPackage.open_if_available(candidate)
            if (opened != null) {
                package_ref = opened
                return opened
            }
        }
        return null
    }

    private companion object {
        const val PACKAGE_MISSING_RETRY_MS = 1_000L
    }
}

internal class ReferenceDictionaryPackage private constructor(
    val info: ReferenceDictionaryPackageInfo,
    private val records_file: RandomAccessFile,
    private val index_file: RandomAccessFile,
    private val zoom_ranges: List<ZoomRange>
) : Closeable {
    private val read_lock = Any()

    fun read_tile_payload(
        z: Int,
        x: Int,
        y: Int,
        verify_hash: Boolean = false
    ): ReferenceDictionaryTilePayload? = synchronized(read_lock) {
        val range = zoom_ranges.firstOrNull { it.z == z } ?: return@synchronized null
        if (x < range.x_min || x > range.x_max || y < range.y_min || y > range.y_max) {
            return@synchronized null
        }
        val ordinal = range.first_ordinal +
                (y - range.y_min).toLong() * range.width.toLong() +
                (x - range.x_min).toLong()
        val entry = read_index_entry(ordinal) ?: return@synchronized null
        if (entry.compressed_length <= 0 || entry.raw_length <= 0) {
            return@synchronized null
        }
        val compressed = ByteArray(entry.compressed_length)
        records_file.seek(entry.offset)
        records_file.readFully(compressed)
        val raw_bytes = inflate_raw_deflate(
            compressed = compressed,
            expected_size = entry.raw_length
        )
        if (verify_hash && raw_hash32(raw_bytes) != entry.raw_hash32) {
            throw IOException("Reference dictionary tile hash mismatch for $z/$x/$y")
        }
        val raw_json = raw_bytes.toString(Charsets.UTF_8)
        val counts = JSONObject(raw_json).optJSONObject("counts")
        ReferenceDictionaryTilePayload(
            coordinate = ReferenceDictionaryTileCoordinate(z = z, x = x, y = y),
            counts = ReferenceDictionaryTileCounts(
                labels = counts?.optInt("labels") ?: 0,
                boundaries = counts?.optInt("boundaries") ?: 0,
                transportation = counts?.optInt("transportation") ?: 0
            ),
            raw_json = raw_json
        )
    }

    override fun close() {
        index_file.use {
            records_file.close()
        }
    }

    private fun read_index_entry(ordinal: Long): IndexEntry? {
        val byte_offset = ordinal * INDEX_ENTRY_BYTES.toLong()
        if (byte_offset < 0L || byte_offset + INDEX_ENTRY_BYTES > index_file.length()) return null
        val bytes = ByteArray(INDEX_ENTRY_BYTES)
        index_file.seek(byte_offset)
        index_file.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IndexEntry(
            offset = buffer.long,
            compressed_length = buffer.int,
            raw_length = buffer.int,
            raw_hash32 = buffer.int,
            flags = buffer.int
        )
    }

    private data class IndexEntry(
        val offset: Long,
        val compressed_length: Int,
        val raw_length: Int,
        val raw_hash32: Int,
        val flags: Int
    )

    private data class ZoomRange(
        val z: Int,
        val x_min: Int,
        val x_max: Int,
        val y_min: Int,
        val y_max: Int,
        val tile_count: Long,
        val first_ordinal: Long
    ) {
        val width: Int = x_max - x_min + 1
    }

    companion object {
        const val DEFAULT_PACKAGE_ID = "world-z5-6-7-8-9-10-11-lossless-deflate-v1"
        val PREFERRED_PACKAGE_IDS = listOf(
            DEFAULT_PACKAGE_ID
        )

        fun package_candidates(context: Context, package_id: String? = null): List<File> {
            val roots = buildList {
                context.getExternalFilesDir("reference")?.let { add(it) }
                add(File("/storage/emulated/0/Android/data/${context.packageName}/files/reference"))
                add(File(context.filesDir, "reference"))
                add(File(context.noBackupFilesDir, "reference"))
            }
            val package_ids = package_id?.let { listOf(it) } ?: PREFERRED_PACKAGE_IDS
            val candidates = ArrayList<File>(roots.size * package_ids.size)
            for (id in package_ids) {
                roots.forEach { root -> candidates += File(root, id) }
            }
            return candidates.distinctBy { it.absolutePath }
        }

        fun open_if_available(package_dir: File): ReferenceDictionaryPackage? {
            val manifest_file = File(package_dir, "manifest.json")
            val records_file = File(package_dir, "records.fadictpack")
            val index_file = File(package_dir, "tile-index.bin")
            if (!manifest_file.isFile || !records_file.isFile || !index_file.isFile) return null
            return try {
                open(
                    package_dir = package_dir,
                    manifest_file = manifest_file,
                    records_file = records_file,
                    index_file = index_file
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun open(
            package_dir: File,
            manifest_file: File,
            records_file: File,
            index_file: File
        ): ReferenceDictionaryPackage {
            val manifest = JSONObject(manifest_file.readText())
            val zoom_ranges = parse_zoom_ranges(manifest)
            val declared_tile_count = manifest
                .optJSONObject("coverage")
                ?.optLong("tileCount")
                ?: zoom_ranges.sumOf { it.tile_count }
            val expected_index_bytes = declared_tile_count * INDEX_ENTRY_BYTES.toLong()
            if (index_file.length() < expected_index_bytes) {
                throw IOException("Reference dictionary index is shorter than its manifest coverage")
            }
            val package_id = manifest.optString("packageId", package_dir.name)
            val info = ReferenceDictionaryPackageInfo(
                package_id = package_id,
                schema_version = manifest.optInt("schemaVersion", 0),
                tile_count = declared_tile_count,
                zooms = zoom_ranges.map { it.z }.toSet(),
                complete_declared_scope = manifest
                    .optJSONObject("coverage")
                    ?.optBoolean("completeDeclaredScope")
                    ?: false,
                complete_whole_earth_dictionary = manifest
                    .optJSONObject("coverage")
                    ?.optBoolean("completeWholeEarthDictionary")
                    ?: false,
                package_dir = package_dir
            )
            var opened_records: RandomAccessFile? = null
            var opened_index: RandomAccessFile? = null
            try {
                opened_records = RandomAccessFile(records_file, "r")
                opened_index = RandomAccessFile(index_file, "r")
                return ReferenceDictionaryPackage(
                    info = info,
                    records_file = opened_records,
                    index_file = opened_index,
                    zoom_ranges = zoom_ranges
                )
            } catch (exception: Exception) {
                opened_records?.close()
                opened_index?.close()
                throw exception
            }
        }

        private fun parse_zoom_ranges(manifest: JSONObject): List<ZoomRange> {
            val coverage = manifest.getJSONObject("coverage")
            val ranges = coverage.getJSONArray("zoomRanges")
            val parsed = ArrayList<ZoomRange>(ranges.length())
            var first_ordinal = 0L
            for (index in 0 until ranges.length()) {
                val range = ranges.getJSONObject(index)
                val tile_count = range.optLong("tileCount")
                val parsed_range = ZoomRange(
                    z = range.getInt("z"),
                    x_min = range.getInt("xMin"),
                    x_max = range.getInt("xMax"),
                    y_min = range.getInt("yMin"),
                    y_max = range.getInt("yMax"),
                    tile_count = tile_count,
                    first_ordinal = first_ordinal
                )
                parsed += parsed_range
                first_ordinal += tile_count
            }
            return parsed
        }

        private fun inflate_raw_deflate(
            compressed: ByteArray,
            expected_size: Int
        ): ByteArray {
            val inflater = Inflater(true)
            val output = ByteArrayOutputStream(expected_size.coerceAtLeast(256))
            val buffer = ByteArray(INFLATE_BUFFER_BYTES)
            try {
                inflater.setInput(compressed)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) {
                        output.write(buffer, 0, count)
                    } else if (inflater.needsInput()) {
                        break
                    } else {
                        throw DataFormatException("Reference dictionary deflate stream stalled")
                    }
                }
            } finally {
                inflater.end()
            }
            val raw = output.toByteArray()
            if (raw.size != expected_size) {
                throw IOException("Reference dictionary tile length mismatch")
            }
            return raw
        }

        private fun raw_hash32(raw: ByteArray): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            return ((digest[0].toInt() and 0xff) shl 24) or
                    ((digest[1].toInt() and 0xff) shl 16) or
                    ((digest[2].toInt() and 0xff) shl 8) or
                    (digest[3].toInt() and 0xff)
        }

        private const val INDEX_ENTRY_BYTES = 24
        private const val INFLATE_BUFFER_BYTES = 16 * 1024
    }
}
