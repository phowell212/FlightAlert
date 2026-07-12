package com.flightalert.map

import com.flightalert.FlightMapView
import com.flightalert.ReferenceFilterCatalogPanelCache
import com.flightalert.ui.ReferenceFilterCatalogUiMapper
import com.flightalert.ui.ReferenceMapFilterCatalogUi
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReferenceDictionaryPackageClassCatalogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun validV3CatalogPreservesExactRendererSemanticContractAlias() {
        val fixture = writeCatalog()

        val catalog = loadCatalog(fixture)

        assertEquals(CatalogControlStatus.AVAILABLE, catalog.status)
        assertEquals(SEMANTIC_SHA256, catalog.renderer_semantic_stream_sha256)
        assertEquals(SEMANTIC_SHA256, catalog.renderer_contract_sha256)
        assertEquals(fixture.catalogSha256, catalog.catalog_sha256)
    }

    @Test
    fun missingClassCatalogManifestOrFileKeepsCatalogUnavailableWithNoRows() {
        val packageDir = temporaryFolder.newFolder("missing-catalog")
        val missingManifest = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = null,
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )
        val missingFile = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = ReferenceDictionaryV3ClassCatalogManifest(
                catalog_sha256 = "0".repeat(64),
                renderer_contract_sha256 = SEMANTIC_SHA256,
            ),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )

        assertUnavailable(missingManifest, "classCatalog object")
        assertUnavailable(missingFile, "class-catalog.bin is missing")
    }

    @Test
    fun malformedManifestAndCorruptCatalogKeepPackageCatalogUnavailable() {
        val fixture = writeCatalog()
        val malformed = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = "A".repeat(64),
            class_catalog_manifest = fixture.manifest,
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )
        val corruptBytes = ByteArray(754) { index -> index.toByte() }
        File(fixture.packageDir, "class-catalog.bin").writeBytes(corruptBytes)
        val corrupt = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest.copy(
                catalog_sha256 = sha256Hex(corruptBytes),
            ),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )

        assertUnavailable(malformed, "rendererSemanticStreamSha256")
        assertUnavailable(corrupt, "catalog domain is unknown")
    }

    @Test
    fun catalogHashAndPresentationPolicyMismatchKeepCatalogUnavailable() {
        val fixture = writeCatalog()
        val hashMismatch = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest.copy(catalog_sha256 = "0".repeat(64)),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )
        val policyMismatchBytes = fixture.bytes.copyOf().also { bytes ->
            bytes[CATALOG_PRESENTATION_POLICY_OFFSET] =
                (bytes[CATALOG_PRESENTATION_POLICY_OFFSET].toInt() xor 0x01).toByte()
        }
        File(fixture.packageDir, "class-catalog.bin").writeBytes(policyMismatchBytes)
        val policyMismatch = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest.copy(
                catalog_sha256 = sha256Hex(policyMismatchBytes),
            ),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )

        assertUnavailable(hashMismatch, "catalog SHA-256 mismatch")
        assertUnavailable(policyMismatch, "catalog presentation policy")
    }

    @Test
    fun semanticAndContractMismatchKeepCatalogUnavailable() {
        val fixture = writeCatalog()
        val manifestAliasMismatch = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest.copy(
                renderer_contract_sha256 = OTHER_SHA256,
            ),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )
        val contractMismatchBytes = catalogBytes(
            rendererSemanticSha256 = SEMANTIC_SHA256,
            rendererContractSha256 = OTHER_SHA256,
        )
        File(fixture.packageDir, "class-catalog.bin").writeBytes(contractMismatchBytes)
        val catalogContractMismatch = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest.copy(
                catalog_sha256 = sha256Hex(contractMismatchBytes),
            ),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )
        val semanticMismatchBytes = catalogBytes(
            rendererSemanticSha256 = OTHER_SHA256,
            rendererContractSha256 = OTHER_SHA256,
        )
        File(fixture.packageDir, "class-catalog.bin").writeBytes(semanticMismatchBytes)
        val catalogSemanticMismatch = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest.copy(
                catalog_sha256 = sha256Hex(semanticMismatchBytes),
            ),
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )

        assertUnavailable(manifestAliasMismatch, "must exactly equal")
        assertUnavailable(catalogContractMismatch, "renderer contract does not match")
        assertUnavailable(catalogSemanticMismatch, "semantic stream does not match")
    }

    @Test
    fun catalogBytesLoadOnceAndCachedTypedCatalogBacksRepeatedPanelMapping() {
        val fixture = writeCatalog()
        var readCount = 0
        val catalog = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest,
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
            read_catalog_bytes = { file ->
                readCount += 1
                file.readBytes()
            },
        )
        File(fixture.packageDir, "class-catalog.bin").delete()

        repeat(3) {
            val mapped = ReferenceFilterCatalogUiMapper.map(catalog, FilterState.defaults())
            assertTrue(mapped is ReferenceMapFilterCatalogUi.Verified)
        }

        assertEquals(1, readCount)
    }

    @Test
    fun catalogReadFailureIsContainedAsUnavailable() {
        val fixture = writeCatalog()

        val catalog = ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest,
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
            read_catalog_bytes = { throw IOException("catalog read denied") },
        )

        assertUnavailable(catalog, "catalog read denied")
    }

    @Test
    fun productionOwnersExposeTheSameTypedCatalogAccessPathToFlightMapView() {
        val accessors = listOf(
            ReferenceDictionaryPackageStore::class.java to
                "reference_class_catalog_if_available",
            ReferenceDictionaryOverlayRenderer::class.java to
                "reference_class_catalog",
            SatelliteMapTileRenderer::class.java to
                "reference_class_catalog",
            MapTileRenderer::class.java to
                "reference_class_catalog",
            FlightMapView::class.java to
                "reference_filter_catalog_for_panel",
        )

        accessors.forEach { (owner, methodName) ->
            val method = owner.declaredMethods.single { method -> method.name == methodName }
            assertEquals(ReferenceClassCatalog::class.java, method.returnType)
            assertEquals(0, method.parameterCount)
        }
    }

    @Test
    fun packageStoreOpensPackageAndCatalogOnceThenReusesTypedCatalogAndTileBytes() {
        val fixture = writeOpenablePackage()
        val counts = OpenCounts()
        val store = packageStore(fixture, counts)

        val firstCatalog = store.reference_class_catalog_if_available()
        val firstTile = store.read_tile_payload(0, 0, 0, verify_hash = true)
        File(fixture.packageDir, "class-catalog.bin").writeBytes(ByteArray(754))
        val secondCatalog = store.reference_class_catalog_if_available()
        val secondTile = store.read_tile_payload(0, 0, 0, verify_hash = true)

        assertEquals(CatalogControlStatus.AVAILABLE, firstCatalog?.status)
        assertSame(firstCatalog, secondCatalog)
        assertArrayEquals(fixture.rawTileBytes, firstTile?.raw_bytes)
        assertArrayEquals(fixture.rawTileBytes, secondTile?.raw_bytes)
        assertTrue(
            ReferenceDictionaryBinaryTileCodec.decode(
                ReferenceDictionaryTileCoordinate(0, 0, 0),
                requireNotNull(firstTile).raw_bytes,
            ).records.isEmpty(),
        )
        assertEquals(1, counts.packageOpens)
        assertEquals(1, counts.catalogReads)
        assertTrue(
            ReferenceFilterCatalogUiMapper.map(firstCatalog, FilterState.defaults()) is
                ReferenceMapFilterCatalogUi.Verified,
        )
        store.close()
    }

    @Test
    fun packageStoreSingleFlightsConcurrentFirstOpenAcrossReferenceWorkers() {
        val fixture = writeOpenablePackage()
        val openerCalls = AtomicInteger()
        val firstOpenerEntered = CountDownLatch(1)
        val duplicateOpenerEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val tileResults = Collections.synchronizedList(
            mutableListOf<ReferenceDictionaryTilePayload?>(),
        )
        val store = ReferenceDictionaryPackageStore(
            candidate_provider = { listOf(fixture.packageDir) },
            package_opener = { candidate ->
                assertEquals(fixture.packageDir, candidate)
                if (openerCalls.incrementAndGet() == 1) {
                    firstOpenerEntered.countDown()
                } else {
                    duplicateOpenerEntered.countDown()
                }
                assertTrue(releaseOpen.await(5, TimeUnit.SECONDS))
                ReferenceDictionaryPackage.open_from_manifest(
                    manifest = fixture.manifest,
                    records_file = fixture.recordsFile,
                    index_file = fixture.indexFile,
                )
            },
            elapsed_realtime_ms = { 1_000L },
        )

        val first = thread(start = true, name = "reference-open-first") {
            try {
                store.reference_class_catalog_if_available()
            } catch (failure: Throwable) {
                failures += failure
            }
        }
        assertTrue(firstOpenerEntered.await(5, TimeUnit.SECONDS))
        val second = thread(start = true, name = "reference-open-second") {
            try {
                tileResults += store.read_tile_payload(0, 0, 0, verify_hash = true)
            } catch (failure: Throwable) {
                failures += failure
            }
        }

        val duplicateOpenObserved = duplicateOpenerEntered.await(1, TimeUnit.SECONDS)
        releaseOpen.countDown()
        first.join(5_000L)
        second.join(5_000L)
        store.close()

        assertFalse("concurrent workers must not duplicate the package open", duplicateOpenObserved)
        assertEquals(1, openerCalls.get())
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(1, tileResults.size)
        assertArrayEquals(fixture.rawTileBytes, tileResults.single()?.raw_bytes)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }

    @Test
    fun catalogValidationFailureDoesNotInvalidatePackageOrTilePayload() {
        val fixture = writeOpenablePackage(catalogSha256Override = "0".repeat(64))
        val counts = OpenCounts()
        val store = packageStore(fixture, counts)

        val catalog = store.reference_class_catalog_if_available()
        val tile = store.read_tile_payload(0, 0, 0, verify_hash = true)

        assertUnavailable(requireNotNull(catalog), "catalog SHA-256 mismatch")
        assertArrayEquals(fixture.rawTileBytes, tile?.raw_bytes)
        assertTrue(
            ReferenceDictionaryBinaryTileCodec.decode(
                ReferenceDictionaryTileCoordinate(0, 0, 0),
                requireNotNull(tile).raw_bytes,
            ).records.isEmpty(),
        )
        assertEquals(1, counts.packageOpens)
        assertEquals(1, counts.catalogReads)
        store.close()
    }

    @Test
    fun legacyPackageRemainsUsableWithoutReadingAClassCatalog() {
        val fixture = writeOpenablePackage(
            runtimeSchema = ReferenceDictionaryRuntimeSchema.LEGACY_JSON,
            includeCatalog = false,
        )
        val counts = OpenCounts()
        val store = packageStore(fixture, counts)

        val catalog = store.reference_class_catalog_if_available()
        val tile = store.read_tile_payload(0, 0, 0, verify_hash = true)

        assertUnavailable(requireNotNull(catalog), "not defined for this reference package schema")
        assertArrayEquals(fixture.rawTileBytes, tile?.raw_bytes)
        assertEquals(ReferenceDictionaryRuntimeSchema.LEGACY_JSON, tile?.runtime_schema)
        assertEquals(1, counts.packageOpens)
        assertEquals(0, counts.catalogReads)
        store.close()
    }

    @Test
    fun panelCatalogCacheInvalidatesOnlyWhenTypedCatalogIdentityChanges() {
        val first = loadCatalog(writeCatalog())
        val second = loadCatalog(writeCatalog())
        val cache = ReferenceFilterCatalogPanelCache()
        var invalidations = 0

        assertSame(first, cache.update(first) { invalidations += 1 })
        assertSame(first, cache.update(first) { invalidations += 1 })
        assertEquals(1, invalidations)
        assertNotSame(first, second)
        assertSame(second, cache.update(second) { invalidations += 1 })
        assertEquals(2, invalidations)
    }

    private fun loadCatalog(fixture: CatalogFixture): ReferenceClassCatalog =
        ReferenceDictionaryV3ClassCatalogLoader.load(
            package_dir = fixture.packageDir,
            runtime_schema = ReferenceDictionaryRuntimeSchema.BINARY_V3,
            renderer_semantic_stream_sha256 = SEMANTIC_SHA256,
            class_catalog_manifest = fixture.manifest,
            presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        )

    private fun writeCatalog(): CatalogFixture {
        val packageDir = temporaryFolder.newFolder("catalog-${temporaryFolder.root.list()?.size}")
        val bytes = catalogBytes(SEMANTIC_SHA256, SEMANTIC_SHA256)
        File(packageDir, "class-catalog.bin").writeBytes(bytes)
        val catalogSha256 = sha256Hex(bytes)
        return CatalogFixture(
            packageDir = packageDir,
            bytes = bytes,
            catalogSha256 = catalogSha256,
            manifest = ReferenceDictionaryV3ClassCatalogManifest(
                catalog_sha256 = catalogSha256,
                renderer_contract_sha256 = SEMANTIC_SHA256,
            ),
        )
    }

    private fun writeOpenablePackage(
        runtimeSchema: ReferenceDictionaryRuntimeSchema =
            ReferenceDictionaryRuntimeSchema.BINARY_V3,
        includeCatalog: Boolean = true,
        catalogSha256Override: String? = null,
    ): OpenablePackageFixture {
        val packageDir = temporaryFolder.newFolder("package-${temporaryFolder.root.list()?.size}")
        val rawTileBytes = if (runtimeSchema == ReferenceDictionaryRuntimeSchema.BINARY_V3) {
            ByteBuffer.allocate(23)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("FAE8TILE1\u0000".toByteArray(Charsets.US_ASCII))
                .put(0.toByte())
                .putInt(0)
                .putInt(0)
                .putInt(0)
                .array()
        } else {
            "{\"labels\":[]}".toByteArray(Charsets.UTF_8)
        }
        val compressed = rawDeflate(rawTileBytes)
        val recordsFile = File(packageDir, "records.fadictpack").apply {
            writeBytes(compressed)
        }
        val indexFile = File(packageDir, "tile-index.bin").apply {
            writeBytes(
                ByteBuffer.allocate(24)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putLong(0L)
                    .putInt(compressed.size)
                    .putInt(rawTileBytes.size)
                    .putInt(rawHash32(rawTileBytes))
                    .putInt(0)
                    .array(),
            )
        }
        val catalogBytes = catalogBytes(SEMANTIC_SHA256, SEMANTIC_SHA256)
        val classCatalogManifest = if (includeCatalog) {
            File(packageDir, "class-catalog.bin").writeBytes(catalogBytes)
            ReferenceDictionaryV3ClassCatalogManifest(
                catalog_sha256 = catalogSha256Override ?: sha256Hex(catalogBytes),
                renderer_contract_sha256 = SEMANTIC_SHA256,
            )
        } else {
            null
        }
        val info = ReferenceDictionaryPackageInfo(
            package_id = packageDir.name,
            schema_version = if (runtimeSchema == ReferenceDictionaryRuntimeSchema.BINARY_V3) 3 else 1,
            tile_count = 1L,
            zooms = setOf(0),
            complete_declared_scope = true,
            complete_whole_earth_dictionary = false,
            runtime_schema = runtimeSchema,
            package_dir = packageDir,
        )
        return OpenablePackageFixture(
            packageDir = packageDir,
            recordsFile = recordsFile,
            indexFile = indexFile,
            rawTileBytes = rawTileBytes,
            manifest = ReferenceDictionaryParsedManifest(
                info = info,
                zoom_ranges = listOf(
                    ReferenceDictionaryZoomRange(
                        z = 0,
                        x_min = 0,
                        x_max = 0,
                        y_min = 0,
                        y_max = 0,
                        tile_count = 1L,
                        first_ordinal = 0L,
                    ),
                ),
                renderer_semantic_stream_sha256 =
                    if (runtimeSchema == ReferenceDictionaryRuntimeSchema.BINARY_V3) {
                        SEMANTIC_SHA256
                    } else {
                        null
                    },
                class_catalog_manifest = classCatalogManifest,
                presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
            ),
        )
    }

    private fun packageStore(
        fixture: OpenablePackageFixture,
        counts: OpenCounts,
    ): ReferenceDictionaryPackageStore = ReferenceDictionaryPackageStore(
        candidate_provider = { listOf(fixture.packageDir) },
        package_opener = { candidate ->
            assertEquals(fixture.packageDir, candidate)
            counts.packageOpens += 1
            ReferenceDictionaryPackage.open_from_manifest(
                manifest = fixture.manifest,
                records_file = fixture.recordsFile,
                index_file = fixture.indexFile,
                read_catalog_bytes = { file ->
                    counts.catalogReads += 1
                    file.readBytes()
                },
            )
        },
        elapsed_realtime_ms = { 1_000L },
    )

    private fun rawDeflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        try {
            deflater.setInput(raw)
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
        } finally {
            deflater.end()
        }
        return output.toByteArray()
    }

    private fun rawHash32(raw: ByteArray): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw)
        return ((digest[0].toInt() and 0xff) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
    }

    private fun catalogBytes(
        rendererSemanticSha256: String,
        rendererContractSha256: String,
    ): ByteArray = ReferencePresentationPolicy.canonical_class_catalog_bytes(
        renderer_semantic_stream_sha256 = rendererSemanticSha256,
        renderer_contract_sha256 = rendererContractSha256,
        presentation_policy_sha256 = ReferencePresentationPolicy.canonical_policy_sha256,
        subtype_counts = SemanticSubtype.entries.associateWith {
            SubtypeCatalogCounts(1uL, 1uL, 1uL)
        },
    )

    private fun assertUnavailable(catalog: ReferenceClassCatalog, reasonFragment: String) {
        assertEquals(CatalogControlStatus.UNAVAILABLE, catalog.status)
        assertTrue(catalog.reason, catalog.reason.contains(reasonFragment))
        assertTrue(catalog.subtype_counts.isEmpty())
        assertTrue(
            ReferenceFilterCatalogUiMapper.map(catalog, FilterState.defaults()) is
                ReferenceMapFilterCatalogUi.Unavailable,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }

    private data class CatalogFixture(
        val packageDir: File,
        val bytes: ByteArray,
        val catalogSha256: String,
        val manifest: ReferenceDictionaryV3ClassCatalogManifest,
    )

    private data class OpenablePackageFixture(
        val packageDir: File,
        val recordsFile: File,
        val indexFile: File,
        val rawTileBytes: ByteArray,
        val manifest: ReferenceDictionaryParsedManifest,
    )

    private data class OpenCounts(
        var packageOpens: Int = 0,
        var catalogReads: Int = 0,
    )

    private companion object {
        const val CATALOG_PRESENTATION_POLICY_OFFSET = 74
        val SEMANTIC_SHA256 = "a".repeat(64)
        val OTHER_SHA256 = "b".repeat(64)
    }
}
