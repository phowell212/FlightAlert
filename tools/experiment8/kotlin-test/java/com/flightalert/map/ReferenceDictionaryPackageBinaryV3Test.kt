package com.flightalert.map

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceDictionaryPackageBinaryV3Test {
    @Test
    fun binaryV3ManifestIdentitySelectsBinaryRuntimeAndPayloadRetainsBytes() {
        val schema = ReferenceDictionaryRuntimeSchemaResolver.resolve(
            schemaVersion = 3,
            hasPayloadSchema = true,
            payloadSchema = "flightalert.reference.renderer-tile.v1",
            emptyPresentTilesSharePayload = false,
            presentationPolicySha256 = ReferencePresentationPolicy.canonical_policy_sha256,
            sourcedTextPolicySha256 = SourcedMapTextBinaryCodec.policySha256,
            unicodeScriptProfileSha256 = SourcedMapTextBinaryCodec.unicodeScriptProfileSha256,
        )
        val raw = byteArrayOf(0, 0xff.toByte(), 0x80.toByte(), 0, 7, 12)
        val payload = ReferenceDictionaryTilePayload(
            coordinate = ReferenceDictionaryTileCoordinate(1, 0, 0),
            counts = ReferenceDictionaryTileCounts(0, 0, 0),
            runtime_schema = schema,
            raw_bytes = raw,
        )

        assertEquals(ReferenceDictionaryRuntimeSchema.BINARY_V3, schema)
        assertArrayEquals(raw, payload.raw_bytes)
    }

    @Test
    fun binaryV3ManifestWithWrongRuntimePolicyIdentityFailsClosed() {
        assertThrows(IOException::class.java) {
            ReferenceDictionaryRuntimeSchemaResolver.resolve(
                schemaVersion = 3,
                hasPayloadSchema = true,
                payloadSchema = "flightalert.reference.renderer-tile.v1",
                emptyPresentTilesSharePayload = false,
                presentationPolicySha256 = "0".repeat(64),
                sourcedTextPolicySha256 = SourcedMapTextBinaryCodec.policySha256,
                unicodeScriptProfileSha256 = SourcedMapTextBinaryCodec.unicodeScriptProfileSha256,
            )
        }
    }

    @Test
    fun experiment8BinaryPackageIsPreferredBeforeLegacyExperiment7() {
        assertEquals(
            ReferenceDictionaryPackage.EXPERIMENT8_PACKAGE_ID,
            ReferenceDictionaryPackage.PREFERRED_PACKAGE_IDS.first(),
        )
        assertEquals(
            ReferenceDictionaryPackage.DEFAULT_PACKAGE_ID,
            ReferenceDictionaryPackage.PREFERRED_PACKAGE_IDS[1],
        )
    }
}
