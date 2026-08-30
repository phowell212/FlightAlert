@file:Suppress(
    "CanBeParameter",
    "ConstPropertyName",
    "FunctionName",
    "LocalVariableName",
    "PrivatePropertyName",
    "PropertyName",
)

package com.flightalert.map.reference.dictionary

import com.flightalert.map.PolicyByteReader
import com.flightalert.map.policy_require
import com.flightalert.map.reference.SemanticSubtype


internal object ReferenceCatalogCodec {
    // Keep the binary size and subtype order fixed while decoding.
    private const val version = 1
    private const val exactSize = 754

    fun decode(bytes: ByteArray): DecodedCatalog {
        policy_require(bytes.size == exactSize) { "catalog byte length is noncanonical" }
        val reader = PolicyByteReader(bytes)
        policy_require(reader.take(9).contentEquals("FAE8CAT1\u0000".toByteArray(Charsets.US_ASCII))) {
            "catalog domain is unknown"
        }
        policy_require(reader.u8() == version) { "catalog version is unsupported" }
        val semantic = reader.take(32).toHex()
        val contract = reader.take(32).toHex()
        val policy = reader.take(32).toHex()
        policy_require(reader.u32().toInt() == SemanticSubtype.entries.size) {
            "catalog must contain the exact subtype set"
        }
        val counts = SemanticSubtype.entries.map { expected ->
            val actual = SemanticSubtype.from_stable_id(reader.u32().toInt())
            policy_require(actual == expected) {
                "catalog subtype entries must be exact and strictly ID-sorted"
            }
            val distinct_feature_count = reader.u64()
            repeat(2) { reader.u64() }
            expected to SubtypeCatalogCounts(distinct_feature_count)
        }
        reader.finish()
        return DecodedCatalog(semantic, contract, policy, counts)
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }
}
