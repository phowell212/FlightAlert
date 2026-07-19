package com.flightalert.map

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceDictionaryIndexReaderTest {
    @Test
    fun readsEntriesPositionallyWithoutRetainingTheWholeIndexOnHeap() = withIndex(2) { _, reader, _ ->
        assertEquals(entry(0), reader.read(0L))
        assertEquals(entry(1), reader.read(1L))
        assertNull(reader.read(2L))
        assertFalse(
            "the whole index must remain on disk instead of becoming a managed-heap byte array",
            ReferenceDictionaryIndexReader::class.java.declaredFields.any { field ->
                field.type == ByteArray::class.java
            },
        )
    }

    @Test
    fun readsBothSidesOfAPageBoundaryAndThePartialFinalPage() = withIndex(257) { _, reader, _ ->
        assertEquals(entry(255), reader.read(255L))
        assertEquals(entry(256), reader.read(256L))
        assertNull(reader.read(257L))
    }

    @Test
    fun evictsAndReloadsTheLeastRecentlyUsedPageAfterThirtyTwoCachedPages() =
        withIndex(33 * ENTRIES_PER_PAGE) { indexFile, reader, _ ->
            assertEquals(entry(0), reader.read(0L))
            val replacement = entry(0, generation = 1)
            writeEntry(indexFile, ordinal = 0, replacement)

            for (page in 1..32) {
                val ordinal = page * ENTRIES_PER_PAGE
                assertEquals(entry(ordinal), reader.read(ordinal.toLong()))
            }

            assertEquals(replacement, reader.read(0L))
        }

    @Test
    fun concurrentReadsReturnTheEntryAtEachRequestedPosition() =
        withIndex(40 * ENTRIES_PER_PAGE) { _, reader, _ ->
            val executor = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            try {
                val futures = (0 until 8).map { worker ->
                    executor.submit(Callable {
                        start.await()
                        repeat(256) { iteration ->
                            val ordinal = (
                                worker * 503 + iteration * (ENTRIES_PER_PAGE + 1)
                                ) % (40 * ENTRIES_PER_PAGE)
                            assertEquals(entry(ordinal), reader.read(ordinal.toLong()))
                        }
                    })
                }
                start.countDown()
                futures.forEach { future -> future.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun closeDropsCachedPagesAndRejectsFurtherReads() = withIndex(1) { _, reader, _ ->
        assertEquals(entry(0), reader.read(0L))
        reader.close()
        assertThrows(IOException::class.java) { reader.read(0L) }
        reader.close()
    }

    @Test
    fun postOpenTruncationFailsWithoutCachingAPartialPage() =
        withIndex(2 * ENTRIES_PER_PAGE) { indexFile, reader, originalBytes ->
            RandomAccessFile(indexFile, "rw").use { file ->
                file.setLength(PAGE_BYTES + ENTRY_BYTES / 2L)
            }

            assertThrows(IOException::class.java) {
                reader.read(ENTRIES_PER_PAGE.toLong())
            }

            indexFile.writeBytes(originalBytes)
            assertEquals(entry(ENTRIES_PER_PAGE), reader.read(ENTRIES_PER_PAGE.toLong()))
        }

    private fun withIndex(
        entryCount: Int,
        test: (File, ReferenceDictionaryIndexReader, ByteArray) -> Unit,
    ) {
        val directory = Files.createTempDirectory("reference-index-reader").toFile()
        val indexFile = File(directory, "tile-index.bin")
        val bytes = indexBytes(entryCount)
        indexFile.writeBytes(bytes)
        val reader = ReferenceDictionaryIndexReader.open(indexFile, bytes.size.toLong())
        try {
            test(indexFile, reader, bytes)
        } finally {
            reader.close()
            directory.deleteRecursively()
        }
    }

    private fun indexBytes(entryCount: Int): ByteArray =
        ByteBuffer.allocate(entryCount * ENTRY_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer ->
                repeat(entryCount) { ordinal -> putEntry(buffer, entry(ordinal)) }
            }
            .array()

    private fun writeEntry(
        indexFile: File,
        ordinal: Int,
        entry: ReferenceDictionaryIndexEntry,
    ) {
        RandomAccessFile(indexFile, "rw").use { file ->
            file.seek(ordinal * ENTRY_BYTES.toLong())
            file.write(
                ByteBuffer.allocate(ENTRY_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .also { putEntry(it, entry) }
                    .array(),
            )
        }
    }

    private fun putEntry(buffer: ByteBuffer, entry: ReferenceDictionaryIndexEntry) {
        buffer.putLong(entry.offset)
            .putInt(entry.compressed_length)
            .putInt(entry.raw_length)
            .putInt(entry.raw_hash32)
            .putInt(entry.flags)
    }

    private fun entry(ordinal: Int, generation: Int = 0): ReferenceDictionaryIndexEntry {
        val base = generation * 100_000 + ordinal * 10
        return ReferenceDictionaryIndexEntry(
            offset = generation * 1_000_000L + ordinal * 10L + 11L,
            compressed_length = base + 12,
            raw_length = base + 13,
            raw_hash32 = base + 14,
            flags = base + 15,
        )
    }

    private companion object {
        const val ENTRY_BYTES = 24
        const val ENTRIES_PER_PAGE = 256
        const val PAGE_BYTES = ENTRY_BYTES * ENTRIES_PER_PAGE.toLong()
    }
}
