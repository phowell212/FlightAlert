@file:Suppress(
    "ConvertTwoComparisonsToRangeCheck",
    "FunctionName",
)

package com.flightalert.map

import java.security.MessageDigest

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
