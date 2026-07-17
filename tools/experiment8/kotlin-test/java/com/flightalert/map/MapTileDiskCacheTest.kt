package com.flightalert.map

import com.flightalert.MAP_TILE_DISK_CACHE_MAX_BYTES
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapTileDiskCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun canonicalRootRegistrySharesOneBudgetExecutorAndTempSequenceAcrossFacades() {
        val cacheRoot = temporaryFolder.newFolder("registry")
        val creations = AtomicInteger()
        val publishedTemps = Collections.synchronizedList(mutableListOf<String>())
        val registry = MapTileDiskCacheRegistry { root, maxBytes ->
            creations.incrementAndGet()
            MapTileDiskCache(
                cache_root = root,
                max_bytes = maxBytes,
                executor = Executor { command -> command.run() },
                atomic_publisher = MapTileAtomicPublisher { temp, target ->
                    publishedTemps += temp.fileName.toString()
                    Files.move(
                        temp,
                        target,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                },
            )
        }

        val firstFacade = registry.cache(cacheRoot, 100L)
        val recreatedFacade = registry.cache(File(cacheRoot, "."), 100L)
        assertSame(firstFacade, recreatedFacade)
        assertEquals(1, creations.get())

        firstFacade.write_async(
            tileFile(cacheRoot, "carto_voyager", 3, 1, 1),
            ByteArray(60) { 1 },
        )
        recreatedFacade.write_async(
            tileFile(cacheRoot, "esri_world_imagery", 3, 1, 1),
            ByteArray(60) { 2 },
        )

        assertTrue(canonicalTileFiles(cacheRoot).sumOf { it.length() } <= 100L)
        assertEquals(2, publishedTemps.size)
        assertEquals(2, publishedTemps.toSet().size)
    }

    @Test
    fun preInitializationRecencyUsesSuccessfulReadCompletionOrderAndIgnoresFailedReads() {
        val cacheRoot = temporaryFolder.newFolder("pre-init-read-order")
        val street = writeTile(cacheRoot, "carto_voyager", 40, 1, 1_000L)
        val satellite = writeTile(cacheRoot, "esri_world_imagery", 40, 2, 1_000L)
        val queuedExecutor = QueuedExecutor()
        val cache = MapTileDiskCache(
            cache_root = cacheRoot,
            max_bytes = 40L,
            executor = queuedExecutor,
        )

        assertArrayEquals(
            ByteArray(40) { 2 },
            cache.read_if_fresh(satellite, Long.MAX_VALUE) { it.readBytes() },
        )
        assertArrayEquals(
            ByteArray(40) { 1 },
            cache.read_if_fresh(street, Long.MAX_VALUE) { it.readBytes() },
        )
        assertNull(cache.read_if_fresh(satellite, Long.MAX_VALUE) { null })

        queuedExecutor.runAll()
        cache.close()

        assertTrue(street.exists())
        assertFalse(satellite.exists())
    }

    @Test
    fun failedInventoryNeverAdmitsWriteAndNextSubmissionRetriesWholeInventory() {
        val cacheRoot = temporaryFolder.newFolder("inventory-retry")
        val retained = writeTile(cacheRoot, "carto_voyager", 40, 3, 1_000L)
        val firstIncoming = tileFile(cacheRoot, "esri_world_imagery", 3, 1, 1)
        val retriedIncoming = tileFile(cacheRoot, "esri_world_imagery", 3, 2, 1)
        val scanAttempts = AtomicInteger()
        val cache = MapTileDiskCache(
            cache_root = cacheRoot,
            max_bytes = 60L,
            executor = Executor { command -> command.run() },
            before_inventory_scan = {
                if (scanAttempts.incrementAndGet() <= 2) throw IOException("forced scan failure")
            },
        )

        cache.write_async(firstIncoming, ByteArray(20) { 4 })
        assertFalse(firstIncoming.exists())
        assertArrayEquals(ByteArray(40) { 3 }, retained.readBytes())

        cache.write_async(retriedIncoming, ByteArray(20) { 5 })
        cache.close()

        assertEquals(3, scanAttempts.get())
        assertArrayEquals(ByteArray(40) { 3 }, retained.readBytes())
        assertArrayEquals(ByteArray(20) { 5 }, retriedIncoming.readBytes())
    }

    @Test
    fun successfulReadDuringFailedInventoryQueuesOneRetryAfterActiveAttempt() {
        val cacheRoot = temporaryFolder.newFolder("inventory-active-retry")
        val tile = writeTile(cacheRoot, "carto_voyager", 40, 3, 1_000L)
        val queuedExecutor = QueuedExecutor()
        val firstScanStarted = CountDownLatch(1)
        val allowFirstScanToFail = CountDownLatch(1)
        val scanAttempts = AtomicInteger()
        val cache = MapTileDiskCache(
            cache_root = cacheRoot,
            max_bytes = 40L,
            executor = queuedExecutor,
            before_inventory_scan = {
                if (scanAttempts.incrementAndGet() == 1) {
                    firstScanStarted.countDown()
                    assertTrue(allowFirstScanToFail.await(5, TimeUnit.SECONDS))
                    throw IOException("forced first scan failure")
                }
            },
        )
        val initializer = thread(start = true, name = "map-cache-first-inventory") {
            queuedExecutor.runNext()
        }
        assertTrue(firstScanStarted.await(5, TimeUnit.SECONDS))

        assertArrayEquals(
            ByteArray(40) { 3 },
            cache.read_if_fresh(tile, Long.MAX_VALUE) { it.readBytes() },
        )
        allowFirstScanToFail.countDown()
        initializer.join(5_000L)
        assertFalse(initializer.isAlive)

        queuedExecutor.runAll()
        cache.close()

        assertEquals(2, scanAttempts.get())
        assertArrayEquals(ByteArray(40) { 3 }, tile.readBytes())
    }

    @Test
    fun blockedStartupDeletionDoesNotHoldStateLockAgainstConcurrentRead() {
        val cacheRoot = temporaryFolder.newFolder("startup-lock-slice")
        repeat(128) { index ->
            tileFile(cacheRoot, "carto_voyager", 3, index, 0).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(4) { 1 })
                assertTrue(setLastModified(1_000L + index))
            }
        }
        val survivor = writeTile(cacheRoot, "esri_world_imagery", 40, 2, 2_000L)
        val deleteStarted = CountDownLatch(1)
        val allowDelete = CountDownLatch(1)
        val initializationThread = AtomicReference<Thread>()
        val cache = MapTileDiskCache(
            cache_root = cacheRoot,
            max_bytes = 40L,
            executor = Executor { command ->
                initializationThread.set(thread(start = true, name = "map-cache-initialize") {
                    command.run()
                })
            },
            before_file_delete = {
                deleteStarted.countDown()
                assertTrue(allowDelete.await(5, TimeUnit.SECONDS))
            },
        )
        assertTrue(deleteStarted.await(5, TimeUnit.SECONDS))
        val readFinished = CountDownLatch(1)
        var readBytes: ByteArray? = null
        val reader = thread(start = true, name = "map-cache-read-during-delete") {
            readBytes = cache.read_if_fresh(survivor, Long.MAX_VALUE) { it.readBytes() }
            readFinished.countDown()
        }

        try {
            assertTrue("read was blocked by startup filesystem deletion", readFinished.await(1, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(40) { 2 }, readBytes)
        } finally {
            allowDelete.countDown()
            reader.join(5_000L)
            initializationThread.get()?.join(5_000L)
            cache.close()
        }
    }

    @Test
    fun startupTrimSharesBudgetAcrossStreetAndEsriWithoutTouchingReferenceData() {
        val cacheRoot = temporaryFolder.newFolder("cache")
        val street = tileFile(cacheRoot, "carto_voyager", 3, 1, 1).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(60) { 1 })
            setLastModified(1_000L)
        }
        val satellite = tileFile(cacheRoot, "esri_world_imagery", 3, 1, 1).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(60) { 2 })
            setLastModified(2_000L)
        }
        val reference = File(cacheRoot, "reference/world-experiment8-binary-v4/records.fadictpack").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(200) { 3 })
        }

        MapTileDiskCache(
            cache_root = cacheRoot,
            max_bytes = 100L,
            executor = Executor { command -> command.run() },
        ).close()

        assertFalse(street.exists())
        assertTrue(satellite.exists())
        assertTrue(reference.exists())
    }

    @Test
    fun successfulReadMakesTileNewestBeforeCrossProviderEviction() {
        val cacheRoot = temporaryFolder.newFolder("lru")
        val street = writeTile(cacheRoot, "carto_voyager", 40, 1, 1_000L)
        val satellite = writeTile(cacheRoot, "esri_world_imagery", 40, 2, 2_000L)
        val labels = tileFile(cacheRoot, "carto_voyager_only_labels", 3, 1, 1)
        val cache = directCache(cacheRoot, maxBytes = 80L)

        val read = cache.read_if_fresh(street, max_age_ms = Long.MAX_VALUE) { it.readBytes() }
        cache.write_async(labels, ByteArray(40) { 3 })
        cache.close()

        assertArrayEquals(ByteArray(40) { 1 }, read)
        assertTrue(street.exists())
        assertFalse(satellite.exists())
        assertArrayEquals(ByteArray(40) { 3 }, labels.readBytes())
    }

    @Test
    fun equalRecencyEvictsLexicographicallyFirstRelativePath() {
        val cacheRoot = temporaryFolder.newFolder("tie")
        val street = writeTile(cacheRoot, "carto_voyager", 40, 1, 1_000L)
        val satellite = writeTile(cacheRoot, "esri_world_imagery", 40, 2, 1_000L)

        directCache(cacheRoot, maxBytes = 40L).close()

        assertFalse(street.exists())
        assertTrue(satellite.exists())
    }

    @Test
    fun failedAtomicPublicationPreservesOldCanonicalBytesAndRemovesOwnedTemp() {
        val cacheRoot = temporaryFolder.newFolder("publish-failure")
        val target = writeTile(cacheRoot, "esri_world_imagery", 30, 4, 1_000L)
        val cache = directCache(
            root = cacheRoot,
            maxBytes = 100L,
            publisher = MapTileAtomicPublisher { _, _ -> throw IOException("forced move failure") },
        )

        cache.write_async(target, ByteArray(50) { 9 })
        cache.close()

        assertArrayEquals(ByteArray(30) { 4 }, target.readBytes())
        assertTrue(ownedTempFiles(cacheRoot).isEmpty())
    }

    @Test
    fun replacementTempMayTransientlyExceedCanonicalBudgetButIsAlwaysCleaned() {
        val cacheRoot = temporaryFolder.newFolder("publish-transient")
        val target = writeTile(cacheRoot, "esri_world_imagery", 60, 4, 1_000L)
        var observedTransient = false
        val cache = directCache(
            root = cacheRoot,
            maxBytes = 100L,
            publisher = MapTileAtomicPublisher { temp, canonical ->
                assertTrue(Files.isRegularFile(temp))
                assertArrayEquals(ByteArray(60) { 4 }, canonical.toFile().readBytes())
                assertTrue(Files.size(temp) + Files.size(canonical) > 100L)
                observedTransient = true
                Files.move(
                    temp,
                    canonical,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            },
        )

        cache.write_async(target, ByteArray(80) { 9 })
        cache.close()

        assertTrue(observedTransient)
        assertArrayEquals(ByteArray(80) { 9 }, target.readBytes())
        assertTrue(canonicalTileFiles(cacheRoot).sumOf { it.length() } <= 100L)
        assertTrue(ownedTempFiles(cacheRoot).isEmpty())
    }

    @Test
    fun tileLargerThanEntireBudgetIsNotPersisted() {
        val cacheRoot = temporaryFolder.newFolder("oversize")
        val target = tileFile(cacheRoot, "esri_world_imagery", 3, 1, 1)
        val cache = directCache(cacheRoot, maxBytes = 10L)

        cache.write_async(target, ByteArray(11) { 7 })
        cache.close()

        assertFalse(target.exists())
        assertTrue(ownedTempFiles(cacheRoot).isEmpty())
    }

    @Test
    fun activeReadLeasePreventsEvictionAndSkipsUncacheableIncomingWrite() {
        val cacheRoot = temporaryFolder.newFolder("read-lease")
        val pinned = writeTile(cacheRoot, "carto_voyager", 40, 5, 1_000L)
        val incoming = tileFile(cacheRoot, "esri_world_imagery", 3, 1, 1)
        val cache = directCache(cacheRoot, maxBytes = 40L)
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        var readBytes: ByteArray? = null
        val reader = thread(start = true, name = "map-tile-read") {
            readBytes = cache.read_if_fresh(pinned, Long.MAX_VALUE) { file ->
                readStarted.countDown()
                assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
                file.readBytes()
            }
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))

        cache.write_async(incoming, ByteArray(40) { 6 })
        releaseRead.countDown()
        reader.join(5_000L)
        cache.close()

        assertFalse(reader.isAlive)
        assertArrayEquals(ByteArray(40) { 5 }, readBytes)
        assertTrue(pinned.exists())
        assertFalse(incoming.exists())
    }

    @Test
    fun pinnedReplacementIsRejectedBeforeAnyUnrelatedTileIsEvicted() {
        val cacheRoot = temporaryFolder.newFolder("pinned-replacement")
        val target = writeTile(cacheRoot, "carto_voyager", 40, 5, 1_000L)
        val unrelated = writeTile(cacheRoot, "esri_world_imagery", 40, 6, 2_000L)
        val cache = directCache(cacheRoot, maxBytes = 80L)
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val reader = thread(start = true, name = "map-tile-replacement-read") {
            cache.read_if_fresh(target, Long.MAX_VALUE) { file ->
                readStarted.countDown()
                assertTrue(releaseRead.await(5, TimeUnit.SECONDS))
                file.readBytes()
            }
        }
        assertTrue(readStarted.await(5, TimeUnit.SECONDS))

        cache.write_async(target, ByteArray(60) { 9 })
        releaseRead.countDown()
        reader.join(5_000L)
        cache.close()

        assertFalse(reader.isAlive)
        assertArrayEquals(ByteArray(40) { 5 }, target.readBytes())
        assertArrayEquals(ByteArray(40) { 6 }, unrelated.readBytes())
    }

    @Test
    fun concurrentStreetAndEsriSubmissionsFinishWithinOneSharedBudget() {
        val cacheRoot = temporaryFolder.newFolder("concurrent")
        val cache = directCache(cacheRoot, maxBytes = 100L)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val writers = listOf("carto_voyager" to 11, "esri_world_imagery" to 22).map { (key, marker) ->
            thread(start = true, name = "writer-$key") {
                runCatching {
                    repeat(10) { index ->
                        cache.write_async(
                            tileFile(cacheRoot, key, 3, index, 0),
                            ByteArray(20) { marker.toByte() },
                        )
                    }
                }.onFailure { failures += it }
            }
        }
        writers.forEach { it.join(5_000L) }
        cache.close()

        assertTrue(failures.toString(), failures.isEmpty())
        assertTrue(writers.none { it.isAlive })
        val survivors = canonicalTileFiles(cacheRoot)
        assertTrue(survivors.sumOf { it.length() } <= 100L)
        survivors.forEach { file ->
            val expected = if (file.path.contains("carto_voyager_tiles")) 11.toByte() else 22.toByte()
            assertTrue(file.readBytes().all { it == expected })
        }
        assertTrue(ownedTempFiles(cacheRoot).isEmpty())
    }

    @Test
    fun outsideRootAndUnknownFilesAreNeverPublishedOrRemoved() {
        val cacheRoot = temporaryFolder.newFolder("boundary")
        val outside = temporaryFolder.newFile("outside.png").apply { writeBytes(ByteArray(25) { 8 }) }
        val unknown = File(cacheRoot, "esri_world_imagery_tiles/notes.txt").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        val unrelatedTileRoot = File(cacheRoot, "legacy_reference_tiles/3/1/1.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(25) { 7 })
        }
        val cache = directCache(cacheRoot, maxBytes = 0L)

        cache.write_async(outside, ByteArray(5) { 9 })
        cache.close()

        assertArrayEquals(ByteArray(25) { 8 }, outside.readBytes())
        assertEquals("keep", unknown.readText())
        assertArrayEquals(ByteArray(25) { 7 }, unrelatedTileRoot.readBytes())
    }

    @Test
    fun reparseTileRootIsNotTraversedOrWritten() {
        val cacheRoot = temporaryFolder.newFolder("reparse-cache")
        val outsideRoot = temporaryFolder.newFolder("reparse-outside")
        val outsideTile = File(outsideRoot, "3/1/1.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(30) { 4 })
        }
        val link = File(cacheRoot, "esri_world_imagery_tiles").toPath()
        try {
            Files.createSymbolicLink(link, outsideRoot.toPath())
        } catch (failure: Exception) {
            assumeNoException("symbolic links unavailable on this host", failure)
        }
        val cache = directCache(cacheRoot, maxBytes = 0L)

        cache.write_async(File(link.toFile(), "3/1/1.png"), ByteArray(10) { 7 })
        cache.close()

        assertArrayEquals(ByteArray(30) { 4 }, outsideTile.readBytes())
    }

    @Test
    fun tileRootSwappedToReparsePointDuringWriteIsRejectedBeforeTempCreation() {
        val cacheRoot = temporaryFolder.newFolder("reparse-race-cache")
        val outsideRoot = temporaryFolder.newFolder("reparse-race-outside")
        val probe = File(cacheRoot, "symlink-probe").toPath()
        try {
            Files.createSymbolicLink(probe, outsideRoot.toPath())
            Files.delete(probe)
        } catch (failure: Exception) {
            assumeNoException("symbolic links unavailable on this host", failure)
        }
        val managedRoot = File(cacheRoot, "esri_world_imagery_tiles").apply {
            resolve("3/1").mkdirs()
        }.toPath()
        val backupRoot = File(cacheRoot, "esri_world_imagery_tiles-before-swap").toPath()
        val target = File(managedRoot.toFile(), "3/1/1.png")
        val outsideTarget = File(outsideRoot, "3/1/1.png")
        val beforeTemp = CountDownLatch(1)
        val allowTemp = CountDownLatch(1)
        val cache = MapTileDiskCache(
            cache_root = cacheRoot,
            max_bytes = 100L,
            executor = Executor { command -> command.run() },
            before_temp_create = {
                beforeTemp.countDown()
                assertTrue(allowTemp.await(5, TimeUnit.SECONDS))
            },
        )
        val writer = thread(start = true, name = "map-cache-reparse-writer") {
            cache.write_async(target, ByteArray(20) { 8 })
        }
        assertTrue(beforeTemp.await(5, TimeUnit.SECONDS))

        try {
            Files.move(managedRoot, backupRoot)
            Files.createSymbolicLink(managedRoot, outsideRoot.toPath())
            allowTemp.countDown()
            writer.join(5_000L)

            assertFalse(writer.isAlive)
            assertFalse(outsideTarget.exists())
            assertTrue(ownedTempFiles(outsideRoot).isEmpty())
        } finally {
            allowTemp.countDown()
            writer.join(5_000L)
            Files.deleteIfExists(managedRoot)
            if (Files.exists(backupRoot)) Files.move(backupRoot, managedRoot)
            cache.close()
        }
    }

    @Test
    fun exactOwnedOrphanTempIsRemovedButUnknownTempIsPreserved() {
        val cacheRoot = temporaryFolder.newFolder("orphan")
        val tileParent = requireNotNull(
            tileFile(cacheRoot, "esri_world_imagery", 3, 1, 1).parentFile,
        ).apply {
            mkdirs()
        }
        val owned = File(tileParent, ".1.png.flightalert-map-tile-42.tmp").apply { writeText("owned") }
        val unknown = File(tileParent, "unknown.tmp").apply { writeText("unknown") }

        directCache(cacheRoot, maxBytes = 100L).close()

        assertFalse(owned.exists())
        assertEquals("unknown", unknown.readText())
    }

    @Test
    fun productionBudgetIsExactlyOneBillionBytes() {
        assertEquals(1_000_000_000L, MAP_TILE_DISK_CACHE_MAX_BYTES)
    }

    @Test
    fun mapFacadeOwnsOneDiskCachePassedThroughStreetAndSatelliteRenderers() {
        val facadeField = MapTileRenderer::class.java.declaredFields.single {
            it.name == "map_tile_disk_cache"
        }
        assertEquals(MapTileDiskCache::class.java, facadeField.type)
        listOf(
            StreetMapTileRenderer::class.java,
            SatelliteMapTileRenderer::class.java,
            SatelliteBaseTileRenderer::class.java,
        ).forEach { owner ->
            assertTrue(
                "${owner.simpleName} must receive the facade-owned cache",
                owner.declaredConstructors.any { constructor ->
                    constructor.parameterTypes.contains(MapTileDiskCache::class.java)
                },
            )
        }
        listOf(
            "app/src/main/java/com/flightalert/map/MapTiles.kt",
            "app/src/main/java/com/flightalert/map/SatelliteTiles.kt",
            "app/src/main/java/com/flightalert/map/SatelliteBaseTileRenderer.kt",
        ).forEach { sourcePath ->
            val source = File(sourcePath).readText()
            assertTrue("$sourcePath must use the process registry", source.contains("ProcessMapTileDiskCaches.cache("))
            assertFalse("$sourcePath must not allocate a separate budget", source.contains("MapTileDiskCache("))
        }
    }

    @Test
    fun dormantReferenceRasterPathDoesNotSubmitUnmanagedRootsToSharedCache() {
        val source = File(
            "app/src/main/java/com/flightalert/map/SatelliteTiles.kt",
        ).readText()

        assertFalse(source.contains("map_tile_disk_cache.write_async(file, bytes)"))
    }

    private fun directCache(
        root: File,
        maxBytes: Long,
        publisher: MapTileAtomicPublisher = DefaultMapTileAtomicPublisher,
    ): MapTileDiskCache = MapTileDiskCache(
        cache_root = root,
        max_bytes = maxBytes,
        executor = Executor { command -> command.run() },
        atomic_publisher = publisher,
    )

    private fun writeTile(
        root: File,
        cacheKey: String,
        size: Int,
        marker: Int,
        modified: Long,
    ): File = tileFile(root, cacheKey, 3, marker, 0).apply {
        parentFile?.mkdirs()
        writeBytes(ByteArray(size) { marker.toByte() })
        assertTrue(setLastModified(modified))
    }

    private fun canonicalTileFiles(root: File): List<File> =
        root.walkTopDown().filter { file ->
            file.isFile && Regex("^[0-9]+\\.png$").matches(file.name)
        }.toList()

    private fun ownedTempFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.name.contains(".flightalert-map-tile-") }.toList()

    private fun tileFile(root: File, cacheKey: String, z: Int, x: Int, y: Int): File =
        File(root, "${cacheKey}_tiles/$z/$x/$y.png")

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            synchronized(tasks) { tasks.addLast(command) }
        }

        fun runAll() {
            while (true) {
                val task = synchronized(tasks) {
                    if (tasks.isEmpty()) null else tasks.removeFirst()
                } ?: return
                task.run()
            }
        }

        fun runNext() {
            synchronized(tasks) { tasks.removeFirst() }.run()
        }
    }
}
