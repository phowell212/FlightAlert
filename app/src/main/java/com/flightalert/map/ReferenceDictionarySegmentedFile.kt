package com.flightalert.map

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional reads over either one binary file or fixed-size numbered parts. */
internal class ReferenceDictionarySegmentedFile private constructor(
    private val parts: List<File>,
    private val part_byte_count: Long,
    val byte_count: Long,
) : Closeable {
    private val lock = Any()
    private var closed = false
    private var open_part_index = -1
    private var open_channel: FileChannel? = null

    fun read_fully(destination: ByteBuffer, position: Long) {
        synchronized(lock) {
            if (closed) throw IOException("Reference dictionary file is closed")
            if (position < 0L) throw IOException("Reference dictionary read position is invalid")
            val end = try {
                Math.addExact(position, destination.remaining().toLong())
            } catch (_: ArithmeticException) {
                throw IOException("Reference dictionary read range is invalid")
            }
            if (end > byte_count) throw IOException("Reference dictionary file ended early")

            var cursor = position
            while (destination.hasRemaining()) {
                val part_index = (cursor / part_byte_count).toInt()
                val part_offset = cursor % part_byte_count
                val bytes_in_part = minOf(
                    destination.remaining().toLong(),
                    part_byte_count - part_offset,
                ).toInt()
                val original_limit = destination.limit()
                destination.limit(destination.position() + bytes_in_part)
                try {
                    var read_in_part = 0L
                    val channel = channel_for(part_index)
                    while (destination.hasRemaining()) {
                        val count = channel.read(destination, part_offset + read_in_part)
                        if (count <= 0) throw IOException("Reference dictionary file ended early")
                        read_in_part += count.toLong()
                    }
                } finally {
                    destination.limit(original_limit)
                }
                cursor += bytes_in_part.toLong()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            open_channel?.close()
            open_channel = null
            open_part_index = -1
        }
    }

    private fun channel_for(part_index: Int): FileChannel {
        if (part_index == open_part_index) return checkNotNull(open_channel)
        open_channel?.close()
        open_channel = null
        open_part_index = -1
        return FileInputStream(parts[part_index]).channel.also { channel ->
            open_channel = channel
            open_part_index = part_index
        }
    }

    companion object {
        fun open(file: File): ReferenceDictionarySegmentedFile {
            if (!file.isFile) throw IOException("Reference dictionary file is missing")
            val byte_count = file.length()
            return ReferenceDictionarySegmentedFile(
                parts = listOf(file),
                part_byte_count = maxOf(1L, byte_count),
                byte_count = byte_count,
            )
        }

        fun open_parts(
            directory: File,
            file_name: String,
            part_byte_count: Long,
            byte_count: Long,
        ): ReferenceDictionarySegmentedFile {
            if (part_byte_count <= 0L || byte_count <= 0L) {
                throw IOException("Reference dictionary part metadata is invalid")
            }
            val part_count_long = ((byte_count - 1L) / part_byte_count) + 1L
            if (part_count_long > Int.MAX_VALUE) {
                throw IOException("Reference dictionary has too many parts")
            }
            val part_count = part_count_long.toInt()
            val parts = List(part_count) { index ->
                val part = File(directory, part_file_name(file_name, index, part_count))
                val expected_size = minOf(
                    part_byte_count,
                    byte_count - index.toLong() * part_byte_count,
                )
                if (!part.isFile || part.length() != expected_size) {
                    throw IOException("Reference dictionary part is missing or has the wrong size")
                }
                part
            }
            return ReferenceDictionarySegmentedFile(
                parts = parts,
                part_byte_count = part_byte_count,
                byte_count = byte_count,
            )
        }

        internal fun part_file_name(file_name: String, index: Int, part_count: Int): String {
            val stem = file_name.removeSuffix(".bin")
            val number = (index + 1).toString().padStart(4, '0')
            val count = part_count.toString().padStart(4, '0')
            return "$stem.part-$number-of-$count.bin"
        }
    }
}
