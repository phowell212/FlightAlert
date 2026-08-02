@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PropertyName",
    "unused",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.reference.CatalogControlStatus

import android.content.Context
import android.os.StatFs
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_DERIVED_PARTS = 10_000L

internal data class ReferenceDictionaryDownloadFile(
    val name: String,
    val byte_count: Long,
)

internal data class ReferenceDictionaryDownloadPlan(
    val manifest_bytes: ByteArray,
    val files: List<ReferenceDictionaryDownloadFile>,
) {
    val total_byte_count = manifest_bytes.size.toLong() + files.sumOf { it.byte_count }

    companion object {
        @Throws(IOException::class)
        fun parse(manifest_bytes: ByteArray): ReferenceDictionaryDownloadPlan {
            if (manifest_bytes.isEmpty() || manifest_bytes.size > MAX_MANIFEST_BYTES) {
                throw IOException("Reference dictionary manifest size is invalid")
            }
            val manifest = try {
                JSONObject(manifest_bytes.toString(Charsets.UTF_8))
            } catch (_: JSONException) {
                throw IOException("Reference dictionary manifest is invalid")
            }
            if (
                manifest.optString("packageId") != ReferenceDictionaryPackage.RENDER_PACKAGE_ID ||
                manifest.optInt("schemaVersion", 0) != 4 ||
                manifest.optString("payloadSchema") != "flightalert.reference.render-tile.v1"
            ) {
                throw IOException("Reference dictionary manifest is not the pinned v5 contract")
            }
            val storage = manifest.optJSONObject("storage")
                ?: throw IOException("Reference dictionary storage metadata is missing")
            if (storage.optInt("schemaVersion", 0) != 1) {
                throw IOException("Unsupported reference dictionary storage schema")
            }
            val chunk_byte_count = storage.positive_long("chunkByteCount")
            val records_byte_count = storage.positive_long("recordsByteCount")
            val index_byte_count = storage.positive_long("indexByteCount")
            val record_part_count = part_count(records_byte_count, chunk_byte_count)
            val index_part_count = part_count(index_byte_count, chunk_byte_count)
            if (record_part_count.toLong() + index_part_count.toLong() > MAX_DERIVED_PARTS) {
                throw IOException("Reference dictionary has too many parts")
            }

            val files = ArrayList<ReferenceDictionaryDownloadFile>(
                1 + record_part_count + index_part_count,
            )
            files += ReferenceDictionaryDownloadFile(
                ReferenceDictionaryV3ClassCatalogLoader.CLASS_CATALOG_FILE_NAME,
                ReferenceDictionaryV3ClassCatalogLoader.CLASS_CATALOG_BYTE_COUNT.toLong(),
            )
            add_parts(
                files = files,
                file_name = ReferenceDictionaryPackage.RECORDS_FILE_NAME,
                part_count = record_part_count,
                part_byte_count = chunk_byte_count,
                byte_count = records_byte_count,
            )
            add_parts(
                files = files,
                file_name = ReferenceDictionaryPackage.INDEX_FILE_NAME,
                part_count = index_part_count,
                part_byte_count = chunk_byte_count,
                byte_count = index_byte_count,
            )
            try {
                files.fold(manifest_bytes.size.toLong()) { total, file ->
                    Math.addExact(total, file.byte_count)
                }
            } catch (_: ArithmeticException) {
                throw IOException("Reference dictionary byte count is invalid")
            }
            return ReferenceDictionaryDownloadPlan(manifest_bytes.copyOf(), files)
        }

        private fun JSONObject.positive_long(name: String): Long {
            val raw_value = opt(name)
            if (raw_value !is Number) {
                throw IOException("Reference dictionary storage metadata is invalid")
            }
            val encoded = raw_value.toString()
            val value = encoded.toLongOrNull()
            if (value == null || value <= 0L) {
                throw IOException("Reference dictionary storage metadata is invalid")
            }
            return value
        }

        private fun part_count(byte_count: Long, part_byte_count: Long): Int {
            val count = ((byte_count - 1L) / part_byte_count) + 1L
            if (count > MAX_DERIVED_PARTS) {
                throw IOException("Reference dictionary has too many parts")
            }
            return count.toInt()
        }

        private fun add_parts(
            files: MutableList<ReferenceDictionaryDownloadFile>,
            file_name: String,
            part_count: Int,
            part_byte_count: Long,
            byte_count: Long,
        ) {
            repeat(part_count) { index ->
                files += ReferenceDictionaryDownloadFile(
                    name = ReferenceDictionarySegmentedFile.part_file_name(
                        file_name,
                        index,
                        part_count,
                    ),
                    byte_count = minOf(
                        part_byte_count,
                        byte_count - index.toLong() * part_byte_count,
                    ),
                )
            }
        }
    }
}

internal class ReferenceDictionaryDownloader(private val context: Context) {
    @Volatile
    private var cancelled = false

    @Volatile
    private var connection: HttpURLConnection? = null

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    @Throws(IOException::class)
    fun download(on_progress: (downloaded: Long, total: Long) -> Unit): Boolean {
        if (cancelled) return false
        return try {
            val installed = ReferenceDictionaryPackage.package_candidates(
                context,
                ReferenceDictionaryPackage.RENDER_PACKAGE_ID,
            ).singleOrNull()
            if (installed != null && installed.is_available_package()) return true

            val manifest_bytes = fetch_manifest() ?: return false
            val plan = ReferenceDictionaryDownloadPlan.parse(manifest_bytes)
            val references_dir = context.getExternalFilesDir("references")
                ?: throw IOException("Reference dictionary storage is unavailable")
            if (!references_dir.isDirectory && !references_dir.mkdirs()) {
                throw IOException("Reference dictionary storage could not be created")
            }
            val staging = File(
                references_dir,
                ".${ReferenceDictionaryPackage.RENDER_PACKAGE_ID}.download",
            )
            if (!staging.isDirectory && !staging.mkdirs()) {
                throw IOException("Reference dictionary staging directory could not be created")
            }

            var staged_payload_bytes = 0L
            plan.files.forEach { download_file ->
                val file = File(staging, download_file.name)
                if (file.exists() && !file.isFile) {
                    throw IOException("Reference dictionary staging path is not a file")
                }
                if (file.length() > download_file.byte_count) {
                    RandomAccessFile(file, "rw").use { it.setLength(0L) }
                }
                staged_payload_bytes = Math.addExact(staged_payload_bytes, file.length())
            }
            val remaining_disk_bytes = Math.subtractExact(
                plan.total_byte_count,
                staged_payload_bytes,
            )
            val required_disk_bytes = try {
                Math.addExact(remaining_disk_bytes, FREE_SPACE_RESERVE_BYTES)
            } catch (_: ArithmeticException) {
                throw IOException("Reference dictionary byte count is invalid")
            }
            if (StatFs(staging.absolutePath).availableBytes < required_disk_bytes) {
                throw IOException("Insufficient space for the reference dictionary")
            }

            var downloaded = manifest_bytes.size.toLong() + staged_payload_bytes
            on_progress(downloaded, plan.total_byte_count)
            plan.files.forEach { download_file ->
                if (cancelled) return false
                val file = File(staging, download_file.name)
                val existing_bytes = file.length()
                if (existing_bytes == download_file.byte_count) return@forEach
                val bytes_before_file = downloaded - existing_bytes
                if (!download_file(file, download_file, bytes_before_file, plan, on_progress)) {
                    return false
                }
                downloaded = bytes_before_file + download_file.byte_count
            }
            if (cancelled) return false

            val manifest_file = File(staging, MANIFEST_FILE_NAME)
            FileOutputStream(manifest_file, false).use { output ->
                output.write(manifest_bytes)
                output.fd.sync()
            }
            if (cancelled) return false
            if (!staging.is_available_package()) {
                throw IOException("Downloaded reference dictionary failed validation")
            }

            val final_dir = File(references_dir, ReferenceDictionaryPackage.RENDER_PACKAGE_ID)
            if (final_dir.exists()) {
                throw IOException("Reference dictionary publication target already exists")
            }
            if (!staging.renameTo(final_dir)) {
                throw IOException("Reference dictionary publication failed")
            }
            on_progress(plan.total_byte_count, plan.total_byte_count)
            true
        } catch (exception: IOException) {
            if (cancelled) false else throw exception
        }
    }

    private fun fetch_manifest(): ByteArray? {
        val opened = open_connection(URL("$RAW_BASE/$MANIFEST_FILE_NAME")) ?: return null
        return try {
            if (opened.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Reference dictionary manifest download failed")
            }
            if (opened.contentLengthLong > MAX_MANIFEST_BYTES) {
                throw IOException("Reference dictionary manifest exceeds 64 KiB")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            opened.inputStream.use { input ->
                while (true) {
                    if (cancelled) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_MANIFEST_BYTES) {
                        throw IOException("Reference dictionary manifest exceeds 64 KiB")
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } finally {
            close_connection(opened)
        }
    }

    private fun download_file(
        destination: File,
        download_file: ReferenceDictionaryDownloadFile,
        bytes_before_file: Long,
        plan: ReferenceDictionaryDownloadPlan,
        on_progress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        var offset = destination.length()
        val opened = open_connection(URL("$RAW_BASE/${download_file.name}")) ?: return false
        return try {
            if (offset > 0L) {
                opened.setRequestProperty("Range", "bytes=$offset-")
            }
            val response_code = opened.responseCode
            if (offset > 0L && response_code == HttpURLConnection.HTTP_OK) {
                RandomAccessFile(destination, "rw").use { it.setLength(0L) }
                offset = 0L
                on_progress(bytes_before_file, plan.total_byte_count)
            } else if (offset > 0L) {
                val expected_content_range =
                    "bytes $offset-${download_file.byte_count - 1L}/${download_file.byte_count}"
                if (
                    response_code != HttpURLConnection.HTTP_PARTIAL ||
                    opened.getHeaderField("Content-Range") != expected_content_range
                ) {
                    throw IOException("Reference dictionary resume response is invalid")
                }
            } else if (response_code != HttpURLConnection.HTTP_OK) {
                throw IOException("Reference dictionary file download failed")
            }

            RandomAccessFile(destination, "rw").use { output ->
                output.seek(offset)
                opened.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = offset
                    while (true) {
                        if (cancelled) return false
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > download_file.byte_count - written) {
                            throw IOException("Reference dictionary file exceeds its manifest size")
                        }
                        output.write(buffer, 0, count)
                        written += count.toLong()
                        on_progress(
                            bytes_before_file + written,
                            plan.total_byte_count,
                        )
                    }
                    if (written != download_file.byte_count) {
                        throw IOException("Reference dictionary file ended early")
                    }
                }
                output.fd.sync()
            }
            true
        } finally {
            close_connection(opened)
        }
    }

    private fun open_connection(url: URL): HttpURLConnection? {
        if (cancelled) return null
        val opened = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept-Encoding", "identity")
        }
        connection = opened
        if (!cancelled) return opened
        close_connection(opened)
        return null
    }

    private fun close_connection(opened: HttpURLConnection) {
        if (connection === opened) connection = null
        opened.disconnect()
    }

    private fun File.is_available_package(): Boolean {
        val opened = ReferenceDictionaryPackage.open_if_available(this) ?: return false
        return opened.use {
            it.reference_class_catalog.status == CatalogControlStatus.AVAILABLE
        }
    }

    companion object {
        private const val RAW_BASE =
            "https://raw.githubusercontent.com/phowell212/FlightAlert/" +
                "bf9df7b1108323c2b23ddefbff5e0d0cefe83db2/references/" +
                ReferenceDictionaryPackage.RENDER_PACKAGE_ID
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val BUFFER_BYTES = 64 * 1024
        private const val FREE_SPACE_RESERVE_BYTES = 128L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
