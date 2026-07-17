package com.flightalert.map

import java.io.File
import java.io.IOException
import java.nio.file.Files
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
    fun wholeWorldExperiment8V4IsTheOnlyAutomaticRuntimePackage() {
        assertEquals(
            listOf("world-experiment8-binary-v4"),
            ReferenceDictionaryPackage.PREFERRED_PACKAGE_IDS,
        )
    }

    @Test
    fun validV4WinsWhenValidV3AndDefaultPackagesCoexist() {
        val root = Files.createTempDirectory("reference-package-v4-preference").toFile()
        val packageIds = listOf(
            "world-experiment8-binary-v4",
            "world-experiment8-binary-v3",
            ReferenceDictionaryPackage.DEFAULT_PACKAGE_ID,
        )
        packageIds.forEach { packageId ->
            writeEmptyBinaryPackage(File(root, packageId))
        }
        val openedPackageIds = mutableListOf<String>()
        val store = ReferenceDictionaryPackageStore(
            candidate_provider = {
                ReferenceDictionaryPackage.PREFERRED_PACKAGE_IDS.map { packageId ->
                    File(root, packageId)
                }
            },
            package_opener = { candidate ->
                openedPackageIds += candidate.name
                ReferenceDictionaryPackage.open_if_available(candidate)
            },
            elapsed_realtime_ms = { 1_000L },
        )
        try {
            assertEquals(
                "world-experiment8-binary-v4",
                store.package_info_if_available()?.package_id,
            )
            assertEquals(listOf("world-experiment8-binary-v4"), openedPackageIds)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun presentButUnreadableV4FailsClosedInsteadOfShowingStaleLegacyData() {
        val root = Files.createTempDirectory("reference-package-v4-fail-closed").toFile()
        val v4 = File(root, ReferenceDictionaryPackage.EXPERIMENT8_PACKAGE_ID).apply {
            mkdirs()
        }
        val legacy = File(root, ReferenceDictionaryPackage.DEFAULT_PACKAGE_ID)
        writeEmptyBinaryPackage(legacy)
        val openedPackageIds = mutableListOf<String>()
        val store = ReferenceDictionaryPackageStore(
            candidate_provider = { listOf(v4, legacy) },
            package_opener = { candidate ->
                openedPackageIds += candidate.name
                if (candidate == v4) null else ReferenceDictionaryPackage.open_if_available(candidate)
            },
            elapsed_realtime_ms = { 1_000L },
        )
        try {
            assertEquals(null, store.package_info_if_available())
            assertEquals(listOf(ReferenceDictionaryPackage.EXPERIMENT8_PACKAGE_ID), openedPackageIds)
        } finally {
            store.close()
            root.deleteRecursively()
        }
    }

    private fun writeEmptyBinaryPackage(packageDir: File) {
        packageDir.mkdirs()
        File(packageDir, "manifest.json").writeText(
            """
            {
              "schemaVersion": 3,
              "payloadSchema": "flightalert.reference.renderer-tile.v1",
              "presentationPolicySha256": "${ReferencePresentationPolicy.canonical_policy_sha256}",
              "sourcedTextPolicySha256": "${SourcedMapTextBinaryCodec.policySha256}",
              "unicodeScriptProfileSha256": "${SourcedMapTextBinaryCodec.unicodeScriptProfileSha256}",
              "coverage": {
                "tileCount": 0,
                "zoomRanges": [],
                "completeDeclaredScope": true,
                "completeWholeEarthDictionary": true
              }
            }
            """.trimIndent(),
        )
        File(packageDir, "records.fadictpack").writeBytes(byteArrayOf())
        File(packageDir, "tile-index.bin").writeBytes(byteArrayOf())
    }
}
