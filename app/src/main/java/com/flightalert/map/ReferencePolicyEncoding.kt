package com.flightalert.map

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

internal fun require_sha256(value: String?, label: String): String {
    policy_require(value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label must be 64 lowercase hexadecimal characters"
    }
    return value!!
}

internal fun hex_bytes(value: String): ByteArray {
    require_sha256(value, "SHA-256")
    return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

internal fun sha256_bytes(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

internal fun sha256_hex(value: ByteArray): String = sha256_bytes(value).joinToString("") {
    "%02x".format(it.toInt() and 0xff)
}

internal fun first_u64_big_endian(digest: ByteArray): ULong {
    policy_require(digest.size >= 8) { "digest is truncated" }
    var value = 0uL
    repeat(8) { index -> value = (value shl 8) or digest[index].toUByte().toULong() }
    return value
}

internal class PolicyByteWriter {
    private val output = ByteArrayOutputStream()

    fun raw(value: ByteArray): PolicyByteWriter = apply { output.write(value) }
    fun ascii(value: String): PolicyByteWriter = raw(value.toByteArray(Charsets.US_ASCII))
    fun u8(value: Int): PolicyByteWriter = apply {
        policy_require(value in 0..0xff) { "u8 is out of range" }
        output.write(value)
    }
    fun boolean(value: Boolean): PolicyByteWriter = u8(if (value) 1 else 0)
    fun u16(value: Int): PolicyByteWriter = apply {
        policy_require(value in 0..0xffff) { "u16 is out of range" }
        repeat(2) { output.write(value ushr (it * 8)) }
    }
    fun u32(value: UInt): PolicyByteWriter = apply {
        repeat(4) { output.write((value shr (it * 8)).toInt()) }
    }
    fun i32(value: Int): PolicyByteWriter = u32(value.toUInt())
    fun u64(value: ULong): PolicyByteWriter = apply {
        repeat(8) { output.write((value shr (it * 8)).toInt()) }
    }
    fun i64(value: Long): PolicyByteWriter = u64(value.toULong())
    fun finish(): ByteArray = output.toByteArray()
}

internal class PolicyByteReader(private val data: ByteArray) {
    private var offset = 0
    val remaining: Int get() = data.size - offset

    fun take(length: Int): ByteArray {
        policy_require(length >= 0 && length <= remaining) { "canonical bytes are truncated" }
        val result = data.copyOfRange(offset, offset + length)
        offset += length
        return result
    }

    fun u8(): Int = take(1)[0].toUByte().toInt()
    fun u32(): UInt {
        val bytes = take(4)
        var result = 0u
        repeat(4) { result = result or (bytes[it].toUByte().toUInt() shl (it * 8)) }
        return result
    }
    fun u64(): ULong {
        val bytes = take(8)
        var result = 0uL
        repeat(8) { result = result or (bytes[it].toUByte().toULong() shl (it * 8)) }
        return result
    }
    fun finish() = policy_require(remaining == 0) { "canonical bytes have trailing data" }
}
