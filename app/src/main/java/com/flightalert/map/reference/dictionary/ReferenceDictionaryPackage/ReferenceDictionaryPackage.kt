@file:Suppress(
    "ArrayInDataClass",
    "ConstPropertyName",
    "ConvertTryFinallyToUseCall",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import android.content.Context
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Inflater


internal class ReferenceDictionaryPackage private constructor(
    val info: ReferenceDictionaryPackageInfo,
    val reference_class_catalog: ReferenceClassCatalog,
    private val records_file: ReferenceDictionarySegmentedFile,
    private val index_reader: ReferenceDictionaryIndexReader,
    private val zoom_ranges: List<ReferenceDictionaryZoomRange>
) : Closeable {
    fun read_tile_payload(
        z: Int,
        x: Int,
        y: Int,
        verify_hash: Boolean = false,
        request_is_relevant: () -> Boolean,
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
        if (!request_is_relevant()) return null
        val compressed = read_record_bytes(entry.offset, entry.compressed_length)
        if (!request_is_relevant()) return null
        val raw_bytes = inflate_raw_deflate(
            compressed = compressed,
            expected_size = entry.raw_length,
            request_is_relevant = request_is_relevant,
        ) ?: return null
        if (!request_is_relevant()) return null
        if (
            (verify_hash || info.runtime_schema == ReferenceDictionaryRuntimeSchema.RENDER_TILE_V1) &&
            raw_hash32(raw_bytes) != entry.raw_hash32
        ) {
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
            records_file.close()
        }
    }

    private fun read_index_entry(ordinal: Long): ReferenceDictionaryIndexEntry? =
        index_reader.read(ordinal)

    private fun read_record_bytes(offset: Long, length: Int): ByteArray {
        if (offset < 0L || length < 0 || records_file.byte_count - length.toLong() < offset) {
            throw IOException("Reference dictionary tile record is outside the records file")
        }
        val bytes = ByteArray(length)
        records_file.read_fully(ByteBuffer.wrap(bytes), offset)
        return bytes
    }

    companion object {
        const val REFERENCE_PACKAGE_ID = "world-reference-dictionary"
        const val RENDER_PACKAGE_ID = "world-reference-dictionary-v5"
        internal const val RECORDS_FILE_NAME = "world-reference-records.bin"
        internal const val INDEX_FILE_NAME = "world-reference-tile-index.bin"

        fun package_candidates(context: Context, package_id: String? = null): List<File> {
            val root = context.getExternalFilesDir("references") ?: return emptyList()
            return package_candidate_names(package_id).map { name -> File(root, name) }
        }

        internal fun package_candidate_names(package_id: String? = null): List<String> =
            if (package_id == null) {
                listOf(RENDER_PACKAGE_ID, REFERENCE_PACKAGE_ID)
            } else {
                listOf(package_id)
            }

        fun open_if_available(package_dir: File): ReferenceDictionaryPackage? {
            val manifest_file = File(package_dir, "manifest.json")
            if (!manifest_file.isFile) return null
            return try {
                open(
                    package_dir = package_dir,
                    manifest_file = manifest_file,
                )
            } catch (_: Exception) {
                null
            }
        }

        private fun open(
            package_dir: File,
            manifest_file: File,
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
            val expected_index_bytes = expected_index_byte_count(declared_tile_count)
            val storage = storage_manifest(manifest)
            if (storage != null && storage.index_byte_count != expected_index_bytes) {
                throw IOException("Reference dictionary index length does not match coverage")
            }
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
            val parsed_manifest = ReferenceDictionaryParsedManifest(
                info = info,
                zoom_ranges = zoom_ranges,
                renderer_semantic_stream_sha256 =
                    manifest.exact_string("rendererSemanticStreamSha256"),
                class_catalog_manifest = class_catalog_manifest(manifest),
                presentation_policy_sha256 = manifest.optString("presentationPolicySha256"),
            )
            val records_file = open_runtime_file(
                package_dir = package_dir,
                file_name = RECORDS_FILE_NAME,
                chunk_byte_count = storage?.chunk_byte_count,
                byte_count = storage?.records_byte_count,
            ) ?: throw IOException("Reference dictionary records file is missing")
            try {
                val index_file = open_runtime_file(
                    package_dir = package_dir,
                    file_name = INDEX_FILE_NAME,
                    chunk_byte_count = storage?.chunk_byte_count,
                    byte_count = storage?.index_byte_count,
                ) ?: throw IOException("Reference dictionary index file is missing")
                return open_from_manifest(parsed_manifest, records_file, index_file)
            } catch (exception: Exception) {
                records_file.close()
                throw exception
            }
        }

        private fun open_from_manifest(
            manifest: ReferenceDictionaryParsedManifest,
            records_file: ReferenceDictionarySegmentedFile,
            index_file: ReferenceDictionarySegmentedFile,
            read_catalog_bytes: (File) -> ByteArray = { file -> file.readBytes() },
        ): ReferenceDictionaryPackage {
            return try {
                val expected_index_bytes = expected_index_byte_count(manifest.info.tile_count)
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
                ReferenceDictionaryPackage(
                    info = manifest.info,
                    reference_class_catalog = reference_class_catalog,
                    records_file = records_file,
                    index_reader = index_reader,
                    zoom_ranges = manifest.zoom_ranges,
                )
            } catch (exception: Exception) {
                records_file.close()
                index_file.close()
                throw exception
            }
        }

        private fun supported_schema_version(schema_version: Int): Boolean {
            return schema_version in 1..4
        }

        private fun open_runtime_file(
            package_dir: File,
            file_name: String,
            chunk_byte_count: Long?,
            byte_count: Long?,
        ): ReferenceDictionarySegmentedFile? {
            val current_file = File(package_dir, file_name)
            if (current_file.isFile) return open_monolithic_file(current_file, byte_count)
            if (chunk_byte_count != null || byte_count != null) {
                if (chunk_byte_count == null || byte_count == null) {
                    throw IOException("Reference dictionary storage metadata is incomplete")
                }
                return ReferenceDictionarySegmentedFile.open_parts(
                    directory = package_dir,
                    file_name = file_name,
                    part_byte_count = chunk_byte_count,
                    byte_count = byte_count,
                )
            }
            return null
        }

        private fun open_monolithic_file(
            file: File,
            expected_byte_count: Long?,
        ): ReferenceDictionarySegmentedFile {
            val opened = ReferenceDictionarySegmentedFile.open(file)
            if (expected_byte_count != null && opened.byte_count != expected_byte_count) {
                opened.close()
                throw IOException("Reference dictionary file length does not match its manifest")
            }
            return opened
        }

        private fun storage_manifest(manifest: JSONObject): ReferenceDictionaryStorageManifest? {
            val storage = manifest.optJSONObject("storage") ?: return null
            if (storage.optInt("schemaVersion", 0) != 1) {
                throw IOException("Unsupported reference dictionary storage schema")
            }
            val chunk_byte_count = storage.optLong("chunkByteCount", -1L)
            val records_byte_count = storage.optLong("recordsByteCount", -1L)
            val index_byte_count = storage.optLong("indexByteCount", -1L)
            if (chunk_byte_count <= 0L || records_byte_count <= 0L || index_byte_count <= 0L) {
                throw IOException("Reference dictionary storage metadata is invalid")
            }
            return ReferenceDictionaryStorageManifest(
                chunk_byte_count = chunk_byte_count,
                records_byte_count = records_byte_count,
                index_byte_count = index_byte_count,
            )
        }

        private fun expected_index_byte_count(tile_count: Long): Long {
            if (tile_count < 0L) throw IOException("Reference dictionary tile count is invalid")
            return try {
                Math.multiplyExact(tile_count, INDEX_ENTRY_BYTES.toLong())
            } catch (_: ArithmeticException) {
                throw IOException("Reference dictionary index length is invalid")
            }
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

        internal fun inflate_raw_deflate(
            compressed: ByteArray,
            expected_size: Int,
            request_is_relevant: () -> Boolean,
        ): ByteArray? {
            if (!request_is_relevant()) return null
            val inflater = Inflater(true)
            val output = ByteArray(expected_size)
            var output_offset = 0
            var finished: Boolean
            try {
                inflater.setInput(compressed)
                while (!inflater.finished() && output_offset < output.size) {
                    if (!request_is_relevant()) return null
                    val count = inflater.inflate(
                        output,
                        output_offset,
                        minOf(INFLATE_OUTPUT_CHUNK_BYTES, output.size - output_offset),
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

        private const val INFLATE_OUTPUT_CHUNK_BYTES = 256 * 1_024

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