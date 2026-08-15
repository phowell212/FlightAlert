@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.content.Context
import android.os.StatFs
import com.flightalert.map.reference.CatalogControlStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


internal class ReferenceDictionaryDownloader(private val context: Context) {
    private val publication_lock = Any()

    @Volatile
    private var cancelled = false

    private val active_connections: MutableSet<HttpURLConnection> =
        Collections.newSetFromMap(IdentityHashMap())

    fun cancel() {
        val connections = synchronized(publication_lock) {
            cancelled = true
            active_connections.toList()
        }
        connections.forEach { it.disconnect() }
    }

    @Throws(IOException::class)
    fun download(on_progress: (downloaded: Long, total: Long) -> Unit): Boolean {
        if (cancelled) return false
        var worker_failure: Throwable? = null
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
            val pending_files = ArrayList<ReferenceDictionaryDownloadFile>()
            plan.files.forEach { download_file ->
                val file = File(staging, download_file.name)
                if (file.exists() && !file.isFile) {
                    throw IOException("Reference dictionary staging path is not a file")
                }
                if (file.length() > download_file.byte_count) {
                    RandomAccessFile(file, "rw").use { it.setLength(0L) }
                }
                val staged_bytes = file.length()
                staged_payload_bytes = Math.addExact(staged_payload_bytes, staged_bytes)
                if (staged_bytes < download_file.byte_count) pending_files += download_file
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

            val progress_lock = Any()
            var downloaded = manifest_bytes.size.toLong() + staged_payload_bytes
            synchronized(progress_lock) {
                on_progress(downloaded, plan.total_byte_count)
            }
            val next_pending_file = AtomicInteger()
            val first_failure = AtomicReference<Throwable?>(null)
            val workers = Executors.newFixedThreadPool(DOWNLOAD_WORKER_COUNT)
            try {
                repeat(DOWNLOAD_WORKER_COUNT) {
                    workers.execute {
                        while (!cancelled && first_failure.get() == null) {
                            val pending_index = next_pending_file.getAndIncrement()
                            if (pending_index >= pending_files.size) return@execute
                            val download_file = pending_files[pending_index]
                            try {
                                if (
                                    !download_file(
                                        destination = File(staging, download_file.name),
                                        download_file = download_file,
                                        should_stop = { first_failure.get() != null },
                                        on_downloaded = { byte_count ->
                                            synchronized(progress_lock) {
                                                downloaded = Math.addExact(downloaded, byte_count)
                                                on_progress(downloaded, plan.total_byte_count)
                                            }
                                        },
                                    )
                                ) {
                                    return@execute
                                }
                            } catch (exception: Throwable) {
                                val connections = synchronized(publication_lock) {
                                    if (
                                        (cancelled && exception is IOException) ||
                                        !first_failure.compareAndSet(null, exception)
                                    ) {
                                        emptyList()
                                    } else {
                                        active_connections.toList()
                                    }
                                }
                                connections.forEach { it.disconnect() }
                                return@execute
                            }
                        }
                    }
                }
            } finally {
                workers.shutdown()
                await_workers(workers)
            }
            worker_failure = first_failure.get()
            worker_failure?.let { throw it }
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
            synchronized(publication_lock) {
                if (cancelled) return false
                if (!final_dir.is_available_package()) {
                    publish_replacing_invalid(staging, final_dir)
                }
            }
            synchronized(progress_lock) {
                on_progress(plan.total_byte_count, plan.total_byte_count)
            }
            true
        } catch (exception: IOException) {
            if (worker_failure !== exception && cancelled) false else throw exception
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
        should_stop: () -> Boolean,
        on_downloaded: (byte_count: Long) -> Unit,
    ): Boolean {
        var offset = destination.length()
        val opened = open_connection(
            URL("$RAW_BASE/${download_file.name}"),
            should_stop,
        ) ?: return false
        return try {
            if (offset > 0L) {
                opened.setRequestProperty("Range", "bytes=$offset-")
            }
            val response_code = opened.responseCode
            if (offset > 0L && response_code == HttpURLConnection.HTTP_OK) {
                RandomAccessFile(destination, "rw").use { it.setLength(0L) }
                on_downloaded(-offset)
                offset = 0L
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
                        if (cancelled || should_stop()) return false
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > download_file.byte_count - written) {
                            throw IOException("Reference dictionary file exceeds its manifest size")
                        }
                        output.write(buffer, 0, count)
                        written += count.toLong()
                        on_downloaded(count.toLong())
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

    private fun open_connection(
        url: URL,
        should_stop: () -> Boolean = { false },
    ): HttpURLConnection? {
        if (cancelled) return null
        val opened = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept-Encoding", "identity")
        }
        val registered = synchronized(publication_lock) {
            if (cancelled || should_stop()) false else active_connections.add(opened)
        }
        if (registered) return opened
        opened.disconnect()
        return null
    }

    private fun close_connection(opened: HttpURLConnection) {
        synchronized(publication_lock) {
            active_connections.remove(opened)
        }
        opened.disconnect()
    }

    private fun await_workers(workers: ExecutorService) {
        var interrupted = false
        while (!workers.isTerminated) {
            try {
                workers.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun File.is_available_package(): Boolean {
        val opened = ReferenceDictionaryPackage.open_if_available(this) ?: return false
        return opened.use {
            it.reference_class_catalog.status == CatalogControlStatus.AVAILABLE
        }
    }

    private fun publish_replacing_invalid(staging: File, final_dir: File) {
        val quarantined = if (final_dir.exists()) {
            val backup = unique_quarantine_path(final_dir)
            if (!final_dir.renameTo(backup)) {
                throw IOException("Invalid reference dictionary could not be quarantined")
            }
            backup
        } else {
            null
        }
        if (staging.renameTo(final_dir)) return
        if (quarantined != null && !quarantined.renameTo(final_dir)) {
            throw IOException(
                "Reference dictionary publication failed and the invalid package " +
                    "remains quarantined as ${quarantined.name}",
            )
        }
        throw IOException("Reference dictionary publication failed")
    }

    private fun unique_quarantine_path(final_dir: File): File {
        while (true) {
            val candidate = File(
                final_dir.parentFile,
                ".${final_dir.name}.invalid-${UUID.randomUUID()}",
            )
            if (!candidate.exists()) return candidate
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
        private const val DOWNLOAD_WORKER_COUNT = 3
    }
}

