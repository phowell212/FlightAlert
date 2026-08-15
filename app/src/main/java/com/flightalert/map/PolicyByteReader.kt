@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
)

package com.flightalert.map


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

