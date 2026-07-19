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
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import org.json.JSONObject

internal enum class ReferenceDictionaryRuntimeSchema {
    LEGACY_JSON,
    HOT_JSON_V2,
    BINARY_V3,
}

internal object ReferenceDictionaryRuntimeSchemaResolver {
    private const val binaryV3PayloadSchema = "flightalert.reference.renderer-tile.v1"

    fun resolve(
        schemaVersion: Int,
        hasPayloadSchema: Boolean,
        payloadSchema: String,
        emptyPresentTilesSharePayload: Boolean,
        presentationPolicySha256: String,
        sourcedTextPolicySha256: String,
        unicodeScriptProfileSha256: String,
    ): ReferenceDictionaryRuntimeSchema {
        if (schemaVersion == 3) {
            if (!hasPayloadSchema || payloadSchema != binaryV3PayloadSchema ||
                presentationPolicySha256 != ReferencePresentationPolicy.canonical_policy_sha256 ||
                sourcedTextPolicySha256 != SourcedMapTextBinaryCodec.policySha256 ||
                unicodeScriptProfileSha256 != SourcedMapTextBinaryCodec.unicodeScriptProfileSha256
            ) {
                throw IOException("Unsupported Experiment 8 binary package policy identity")
            }
            return ReferenceDictionaryRuntimeSchema.BINARY_V3
        }
        return if (hasPayloadSchema || emptyPresentTilesSharePayload || schemaVersion >= 2) {
            ReferenceDictionaryRuntimeSchema.HOT_JSON_V2
        } else {
            ReferenceDictionaryRuntimeSchema.LEGACY_JSON
        }
    }
}

internal data class ReferenceDictionaryV3ClassCatalogManifest(
    val catalog_sha256: String?,
    val renderer_contract_sha256: String?,
)

internal object ReferenceDictionaryV3ClassCatalogLoader {
    fun load(
        package_dir: File,
        runtime_schema: ReferenceDictionaryRuntimeSchema,
        renderer_semantic_stream_sha256: String?,
        class_catalog_manifest: ReferenceDictionaryV3ClassCatalogManifest?,
        presentation_policy_sha256: String,
        read_catalog_bytes: (File) -> ByteArray = { file -> file.readBytes() },
    ): ReferenceClassCatalog {
        if (runtime_schema != ReferenceDictionaryRuntimeSchema.BINARY_V3) {
            return unavailable("class catalog is not defined for this reference package schema")
        }
        val renderer_semantic_sha256 = renderer_semantic_stream_sha256
        if (renderer_semantic_sha256 == null || !renderer_semantic_sha256.is_sha256()) {
            return unavailable(
                "V3 manifest rendererSemanticStreamSha256 is missing or malformed",
            )
        }
        val catalog_manifest = class_catalog_manifest
            ?: return unavailable("V3 manifest classCatalog object is missing or malformed")
        val catalog_sha256 = catalog_manifest.catalog_sha256
        if (catalog_sha256 == null || !catalog_sha256.is_sha256()) {
            return unavailable("V3 manifest classCatalog.catalogSha256 is missing or malformed")
        }
        val renderer_contract_sha256 = catalog_manifest.renderer_contract_sha256
        if (renderer_contract_sha256 == null || !renderer_contract_sha256.is_sha256()) {
            return unavailable(
                "V3 manifest classCatalog.rendererContractSha256 is missing or malformed",
            )
        }
        if (renderer_contract_sha256 != renderer_semantic_sha256) {
            return unavailable(
                "V3 manifest classCatalog.rendererContractSha256 must exactly equal " +
                    "rendererSemanticStreamSha256",
            )
        }
        val catalog_file = File(package_dir, CLASS_CATALOG_FILE_NAME)
        return try {
            if (!catalog_file.isFile) {
                return unavailable("class-catalog.bin is missing")
            }
            if (catalog_file.length() != CLASS_CATALOG_BYTE_COUNT.toLong()) {
                return unavailable("class-catalog.bin length is not the canonical 754 bytes")
            }
            val catalog_bytes = read_catalog_bytes(catalog_file)
            if (catalog_bytes.size != CLASS_CATALOG_BYTE_COUNT) {
                return unavailable("class-catalog.bin length is not the canonical 754 bytes")
            }
            ReferenceClassCatalog.from_installed_bytes(
                catalog_bytes = catalog_bytes,
                expected_catalog_sha256 = catalog_sha256,
                expected_renderer_semantic_stream_sha256 = renderer_semantic_sha256,
                expected_renderer_contract_sha256 = renderer_contract_sha256,
                expected_presentation_policy_sha256 = presentation_policy_sha256,
            )
        } catch (exception: Exception) {
            unavailable(
                "class-catalog.bin validation failed: " +
                    (exception.message ?: exception::class.java.simpleName),
            )
        }
    }

    private fun String.is_sha256(): Boolean =
        length == 64 && all { character ->
            character in '0'..'9' || character in 'a'..'f'
        }

    private fun unavailable(reason: String): ReferenceClassCatalog =
        ReferenceClassCatalog.unavailable(reason)

    private const val CLASS_CATALOG_FILE_NAME = "class-catalog.bin"
    private const val CLASS_CATALOG_BYTE_COUNT = 754
}

internal data class ReferenceDictionaryPackageInfo(
    val package_id: String,
    val schema_version: Int,
    val tile_count: Long,
    val zooms: Set<Int>,
    val complete_declared_scope: Boolean,
    val complete_whole_earth_dictionary: Boolean,
    val runtime_schema: ReferenceDictionaryRuntimeSchema,
    val package_dir: File
)

internal data class ReferenceDictionaryZoomRange(
    val z: Int,
    val x_min: Int,
    val x_max: Int,
    val y_min: Int,
    val y_max: Int,
    val tile_count: Long,
    val first_ordinal: Long,
) {
    val width: Int = x_max - x_min + 1
}

internal data class ReferenceDictionaryParsedManifest(
    val info: ReferenceDictionaryPackageInfo,
    val zoom_ranges: List<ReferenceDictionaryZoomRange>,
    val renderer_semantic_stream_sha256: String?,
    val class_catalog_manifest: ReferenceDictionaryV3ClassCatalogManifest?,
    val presentation_policy_sha256: String,
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
    val runtime_schema: ReferenceDictionaryRuntimeSchema,
    val raw_bytes: ByteArray,
) {
    val raw_json: String
        get() = raw_bytes.toString(Charsets.UTF_8)
}

internal class ReferenceDictionaryPackageStore(
    private val candidate_provider: () -> List<File>,
    private val package_opener: (File) -> ReferenceDictionaryPackage?,
    private val elapsed_realtime_ms: () -> Long,
) : Closeable {
    constructor(
        context: Context,
        package_id: String? = null,
    ) : this(
        candidate_provider = {
            ReferenceDictionaryPackage.package_candidates(context, package_id)
        },
        package_opener = { candidate ->
            ReferenceDictionaryPackage.open_if_available(candidate)
        },
        elapsed_realtime_ms = { SystemClock.elapsedRealtime() },
    )

    private var package_checked = false
    private var last_package_check_ms = 0L
    private val package_lock = Any()
    @Volatile
    private var package_ref: ReferenceDictionaryPackage? = null

    fun package_info_if_available(): ReferenceDictionaryPackageInfo? {
        return open_package_if_available()?.info
    }

    fun reference_class_catalog_if_available(): ReferenceClassCatalog? {
        return open_package_if_available()?.reference_class_catalog
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
        synchronized(package_lock) {
            package_ref?.close()
            package_ref = null
            package_checked = false
        }
    }

    private fun open_package_if_available(): ReferenceDictionaryPackage? {
        package_ref?.let { return it }
        return synchronized(package_lock) {
            package_ref?.let { return@synchronized it }
            val now_ms = elapsed_realtime_ms()
            if (package_checked && now_ms - last_package_check_ms < PACKAGE_MISSING_RETRY_MS) {
                return@synchronized null
            }
            package_checked = true
            last_package_check_ms = now_ms
            for (candidate in candidate_provider()) {
                val opened = package_opener(candidate)
                if (opened != null) {
                    package_ref = opened
                    return@synchronized opened
                }
                if (
                    candidate.name == ReferenceDictionaryPackage.EXPERIMENT8_PACKAGE_ID &&
                    candidate.exists()
                ) {
                    return@synchronized null
                }
            }
            null
        }
    }

    private companion object {
        const val PACKAGE_MISSING_RETRY_MS = 1_000L
    }
}

internal class ReferenceDictionaryPackage private constructor(
    val info: ReferenceDictionaryPackageInfo,
    val reference_class_catalog: ReferenceClassCatalog,
    private val records_channel: FileChannel,
    private val index_reader: ReferenceDictionaryIndexReader,
    private val zoom_ranges: List<ReferenceDictionaryZoomRange>
) : Closeable {
    fun read_tile_payload(
        z: Int,
        x: Int,
        y: Int,
        verify_hash: Boolean = false
    ): ReferenceDictionaryTilePayload? {
        val range = zoom_ranges.firstOrNull { it.z == z } ?: return null
        if (x < range.x_min || x > range.x_max || y < range.y_min || y > range.y_max) {
            return null
        }
        val ordinal = range.first_ordinal +
                (y - range.y_min).toLong() * range.width.toLong() +
                (x - range.x_min).toLong()
        val entry = read_index_entry(ordinal) ?: return null
        if (entry.compressed_length <= 0 || entry.raw_length <= 0) {
            return null
        }
        if (entry.raw_length > MAX_RAW_TILE_BYTES) {
            throw IOException("Reference dictionary tile exceeds its raw byte ceiling")
        }
        val compressed = read_record_bytes(entry.offset, entry.compressed_length)
        val raw_bytes = inflate_raw_deflate(
            compressed = compressed,
            expected_size = entry.raw_length
        )
        if (verify_hash && raw_hash32(raw_bytes) != entry.raw_hash32) {
            throw IOException("Reference dictionary tile hash mismatch for $z/$x/$y")
        }
        return ReferenceDictionaryTilePayload(
            coordinate = ReferenceDictionaryTileCoordinate(z = z, x = x, y = y),
            counts = ReferenceDictionaryTileCounts(
                labels = 0,
                boundaries = 0,
                transportation = 0
            ),
            runtime_schema = info.runtime_schema,
            raw_bytes = raw_bytes,
        )
    }

    override fun close() {
        try {
            index_reader.close()
        } finally {
            records_channel.close()
        }
    }

    private fun read_index_entry(ordinal: Long): ReferenceDictionaryIndexEntry? =
        index_reader.read(ordinal)

    private fun read_record_bytes(offset: Long, length: Int): ByteArray {
        val bytes = ByteArray(length)
        val buffer = ByteBuffer.wrap(bytes)
        var read = 0
        while (buffer.hasRemaining()) {
            val count = records_channel.read(buffer, offset + read)
            if (count < 0) throw IOException("Reference dictionary tile record ended early")
            read += count
        }
        return bytes
    }

    companion object {
        const val EXPERIMENT8_PACKAGE_ID = "world-experiment8-binary-v4"
        const val EXPERIMENT8_V3_PACKAGE_ID = "world-experiment8-binary-v3"
        const val DEFAULT_PACKAGE_ID = "world-z5-6-7-8-9-10-11-lossless-deflate-v1"
        val PREFERRED_PACKAGE_IDS = listOf(EXPERIMENT8_PACKAGE_ID)

        fun package_candidates(context: Context, package_id: String? = null): List<File> {
            val roots = reference_roots(context)
            if (package_id != null) {
                return roots.map { root -> File(root, package_id) }
                    .distinctBy { it.absolutePath }
            }
            return roots.map { root -> File(root, EXPERIMENT8_PACKAGE_ID) }
                .distinctBy { it.absolutePath }
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
            if (!supported_schema_version(manifest.optInt("schemaVersion", 0))) {
                throw IOException("Unsupported reference dictionary schema version")
            }
            val runtime_schema = runtime_schema_for(manifest)
            val zoom_ranges = parse_zoom_ranges(manifest)
            val declared_tile_count = manifest
                .optJSONObject("coverage")
                ?.optLong("tileCount")
                ?: zoom_ranges.sumOf { it.tile_count }
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
                runtime_schema = runtime_schema,
                package_dir = package_dir
            )
            return open_from_manifest(
                manifest = ReferenceDictionaryParsedManifest(
                    info = info,
                    zoom_ranges = zoom_ranges,
                    renderer_semantic_stream_sha256 =
                        manifest.exact_string("rendererSemanticStreamSha256"),
                    class_catalog_manifest = class_catalog_manifest(manifest),
                    presentation_policy_sha256 = manifest.optString("presentationPolicySha256"),
                ),
                records_file = records_file,
                index_file = index_file,
            )
        }

        internal fun open_from_manifest(
            manifest: ReferenceDictionaryParsedManifest,
            records_file: File,
            index_file: File,
            read_catalog_bytes: (File) -> ByteArray = { file -> file.readBytes() },
        ): ReferenceDictionaryPackage {
            val expected_index_bytes = manifest.info.tile_count * INDEX_ENTRY_BYTES.toLong()
            if (index_file.length() < expected_index_bytes) {
                throw IOException("Reference dictionary index is shorter than its manifest coverage")
            }
            val reference_class_catalog = ReferenceDictionaryV3ClassCatalogLoader.load(
                package_dir = manifest.info.package_dir,
                runtime_schema = manifest.info.runtime_schema,
                renderer_semantic_stream_sha256 = manifest.renderer_semantic_stream_sha256,
                class_catalog_manifest = manifest.class_catalog_manifest,
                presentation_policy_sha256 = manifest.presentation_policy_sha256,
                read_catalog_bytes = read_catalog_bytes,
            )
            val index_reader = ReferenceDictionaryIndexReader.open(
                index_file = index_file,
                expectedByteCount = expected_index_bytes,
            )
            var opened_records: FileChannel? = null
            try {
                opened_records = FileInputStream(records_file).channel
                return ReferenceDictionaryPackage(
                    info = manifest.info,
                    reference_class_catalog = reference_class_catalog,
                    records_channel = opened_records,
                    index_reader = index_reader,
                    zoom_ranges = manifest.zoom_ranges,
                )
            } catch (exception: Exception) {
                opened_records?.close()
                index_reader.close()
                throw exception
            }
        }

        private fun reference_roots(context: Context): List<File> {
            return listOfNotNull(context.getExternalFilesDir("reference"))
        }

        private fun is_compatible_package_dir(package_dir: File): Boolean {
            val manifest_file = File(package_dir, "manifest.json")
            val records_file = File(package_dir, "records.fadictpack")
            val index_file = File(package_dir, "tile-index.bin")
            if (!manifest_file.isFile || !records_file.isFile || !index_file.isFile) return false
            return try {
                val manifest = JSONObject(manifest_file.readText())
                if (!supported_schema_version(manifest.optInt("schemaVersion", 0))) return false
                val package_id = manifest.optString("packageId", package_dir.name)
                if (package_id.isBlank()) return false
                val zoom_ranges = parse_zoom_ranges(manifest)
                val declared_tile_count = manifest
                    .optJSONObject("coverage")
                    ?.optLong("tileCount")
                    ?: zoom_ranges.sumOf { it.tile_count }
                declared_tile_count > 0L &&
                        index_file.length() >= declared_tile_count * INDEX_ENTRY_BYTES.toLong()
            } catch (_: Exception) {
                false
            }
        }

        private fun supported_schema_version(schema_version: Int): Boolean {
            return schema_version in 1..3
        }

        private fun runtime_schema_for(manifest: JSONObject): ReferenceDictionaryRuntimeSchema {
            val compatibility = manifest.optJSONObject("compatibility")
            return ReferenceDictionaryRuntimeSchemaResolver.resolve(
                schemaVersion = manifest.optInt("schemaVersion", 0),
                hasPayloadSchema = manifest.has("payloadSchema"),
                payloadSchema = manifest.optString("payloadSchema"),
                emptyPresentTilesSharePayload =
                    compatibility?.optBoolean("emptyPresentTilesSharePayload") == true,
                presentationPolicySha256 = manifest.optString("presentationPolicySha256"),
                sourcedTextPolicySha256 = manifest.optString("sourcedTextPolicySha256"),
                unicodeScriptProfileSha256 = manifest.optString("unicodeScriptProfileSha256"),
            )
        }

        private fun class_catalog_manifest(
            manifest: JSONObject,
        ): ReferenceDictionaryV3ClassCatalogManifest? {
            val class_catalog = manifest.optJSONObject("classCatalog") ?: return null
            return ReferenceDictionaryV3ClassCatalogManifest(
                catalog_sha256 = class_catalog.exact_string("catalogSha256"),
                renderer_contract_sha256 = class_catalog.exact_string("rendererContractSha256"),
            )
        }

        private fun JSONObject.exact_string(key: String): String? = opt(key) as? String

        private fun parse_zoom_ranges(manifest: JSONObject): List<ReferenceDictionaryZoomRange> {
            val coverage = manifest.getJSONObject("coverage")
            val ranges = coverage.getJSONArray("zoomRanges")
            val parsed = ArrayList<ReferenceDictionaryZoomRange>(ranges.length())
            var first_ordinal = 0L
            for (index in 0 until ranges.length()) {
                val range = ranges.getJSONObject(index)
                val tile_count = range.optLong("tileCount")
                val parsed_range = ReferenceDictionaryZoomRange(
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
            val output = ByteArray(expected_size)
            var output_offset = 0
            var finished = false
            try {
                inflater.setInput(compressed)
                while (!inflater.finished() && output_offset < output.size) {
                    val count = inflater.inflate(
                        output,
                        output_offset,
                        output.size - output_offset,
                    )
                    if (count > 0) {
                        output_offset += count
                    } else if (inflater.needsInput()) {
                        break
                    } else {
                        throw DataFormatException("Reference dictionary deflate stream stalled")
                    }
                }
                finished = inflater.finished()
            } finally {
                inflater.end()
            }
            if (!finished || output_offset != expected_size) {
                throw IOException("Reference dictionary tile length mismatch")
            }
            return output
        }

        private fun raw_hash32(raw: ByteArray): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw)
            return ((digest[0].toInt() and 0xff) shl 24) or
                    ((digest[1].toInt() and 0xff) shl 16) or
                    ((digest[2].toInt() and 0xff) shl 8) or
                    (digest[3].toInt() and 0xff)
        }

        private const val INDEX_ENTRY_BYTES = 24
        private const val MAX_RAW_TILE_BYTES = 32 * 1024 * 1024
    }
}
