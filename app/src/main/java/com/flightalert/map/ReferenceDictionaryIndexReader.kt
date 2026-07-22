package com.flightalert.map

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.LinkedHashMap

internal data class ReferenceDictionaryIndexEntry(
    val offset: Long,
    val compressed_length: Int,
    val raw_length: Int,
    val raw_hash32: Int,
    val flags: Int,
)

/** Bounded, page-cached access to the whole-world tile index. */
internal class ReferenceDictionaryIndexReader private constructor(
    private val index_file: ReferenceDictionarySegmentedFile,
    private val expected_byte_count: Long,
) : Closeable {
    private val pages = object : LinkedHashMap<Long, ByteArray>(
        MAX_CACHED_PAGES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean =
            size > MAX_CACHED_PAGES
    }

    fun read(ordinal: Long): ReferenceDictionaryIndexEntry? {
        if (ordinal < 0L) return null
        val byte_offset = try {
            Math.multiplyExact(ordinal, INDEX_ENTRY_BYTES.toLong())
        } catch (_: ArithmeticException) {
            return null
        }
        if (byte_offset > expected_byte_count - INDEX_ENTRY_BYTES) return null

        val page_number = ordinal / ENTRIES_PER_PAGE
        val page_offset = ((ordinal % ENTRIES_PER_PAGE) * INDEX_ENTRY_BYTES).toInt()
        val page = synchronized(pages) {
            pages[page_number] ?: read_page(page_number).also { loaded ->
                pages[page_number] = loaded
            }
        }
        return ReferenceDictionaryIndexEntry(
            offset = little_endian_long(page, page_offset),
            compressed_length = little_endian_int(page, page_offset + 8),
            raw_length = little_endian_int(page, page_offset + 12),
            raw_hash32 = little_endian_int(page, page_offset + 16),
            flags = little_endian_int(page, page_offset + 20),
        )
    }

    override fun close() {
        synchronized(pages) {
            pages.clear()
            index_file.close()
        }
    }

    private fun read_page(page_number: Long): ByteArray {
        val page_start = page_number * PAGE_BYTES.toLong()
        val remaining = expected_byte_count - page_start
        val page_size = minOf(PAGE_BYTES.toLong(), remaining).toInt()
        val bytes = ByteArray(page_size)
        index_file.read_fully(ByteBuffer.wrap(bytes), page_start)
        return bytes
    }

    companion object {
        fun open(index_file: File, expectedByteCount: Long): ReferenceDictionaryIndexReader {
            return open(ReferenceDictionarySegmentedFile.open(index_file), expectedByteCount)
        }

        fun open(
            index_file: ReferenceDictionarySegmentedFile,
            expectedByteCount: Long,
        ): ReferenceDictionaryIndexReader {
            try {
                if (expectedByteCount < 0L || expectedByteCount % INDEX_ENTRY_BYTES != 0L) {
                    throw IOException("Reference dictionary index length is invalid")
                }
                if (index_file.byte_count < expectedByteCount) {
                    throw IOException(
                        "Reference dictionary index is shorter than its manifest coverage",
                    )
                }
                return ReferenceDictionaryIndexReader(
                    index_file = index_file,
                    expected_byte_count = expectedByteCount,
                )
            } catch (exception: Exception) {
                index_file.close()
                throw exception
            }
        }

        private fun little_endian_int(bytes: ByteArray, offset: Int): Int {
            return (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun little_endian_long(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
            }
            return value
        }

        private const val INDEX_ENTRY_BYTES = 24
        private const val ENTRIES_PER_PAGE = 256
        private const val PAGE_BYTES = INDEX_ENTRY_BYTES * ENTRIES_PER_PAGE
        private const val MAX_CACHED_PAGES = 32
    }
}
