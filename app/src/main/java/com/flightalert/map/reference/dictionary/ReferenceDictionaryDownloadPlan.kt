@file:Suppress(
    "ArrayInDataClass",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_DERIVED_PARTS = 10_000L

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
