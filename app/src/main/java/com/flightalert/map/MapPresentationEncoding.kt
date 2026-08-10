@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
)

package com.flightalert.map

import java.security.MessageDigest

class ReferencePolicyException(message: String) : IllegalArgumentException(message)

internal inline fun policy_require(condition: Boolean, message: () -> String) {
    if (!condition) throw ReferencePolicyException(message())
}

internal fun require_sha256(value: String?, label: String): String {
    policy_require(value != null && value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
        "$label must be 64 lowercase hexadecimal characters"
    }
    return value!!
}

internal fun sha256_bytes(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

internal fun sha256_bytes(
    digest: MessageDigest,
    domain: ByteArray,
    canonical: ByteArray,
): ByteArray {
    digest.reset()
    digest.update(domain)
    digest.update(canonical)
    return digest.digest()
}

internal fun sha256_hex(value: ByteArray): String = sha256_bytes(value).joinToString("") {
    "%02x".format(it.toInt() and 0xff)
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

