package org.iz.navigation.integration.osm

import java.io.File
import java.io.IOException
import java.util.Collections
import org.iz.navigation.integration.OsmDirectedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OsmGpxCoverageCacheTest {
    @get:Rule val noBackup = TemporaryFolder()

    @Test fun acrossAccountsOnlyRedownloadableGeometryCanBeEvictedAndMissingRequiredPartsFail() {
        val root = noBackup.newFolder("osm-gpx-coverage")
        val receipts = noBackup.newFolder("osm-gpx-receipts")
        val markers = listOf("unknown", "direct-2xx", "visibility-pending").associateWith { name ->
            File(receipts, name).apply { writeText(name) }
        }
        val quota = 480L
        val original = OsmGpxCoverageCache(root, quota)
        original.write(1, 10, sample(0.0, 'a'))
        val oldSnapshot = requireNotNull(original.inspect(1, 10, redownloadable = true))
        original.write(1, 11, sample(1.0, 'b'))

        val cache = OsmGpxCoverageCache(root, quota)
        cache.prepareAccount(2, setOf(20))
        cache.write(2, 20, sample(2.0, 'c'), redownloadable = true)
        assertNull(original.inspect(1, 10))
        assertNotNull(cache.inspect(1, 11))
        assertEquals("geometry-missing", assertThrows(IOException::class.java) {
            original.nearby(1, oldSnapshot, listOf(area(0.0))) { error("A missing required file cannot supply geometry") }
        }.message)
        original.nearby(1, oldSnapshot, listOf(area(20.0))) { error("An unrelated trace is outside the plan") }

        cache.prepareAccount(1, setOf(10, 11))
        val restoredData = sample(0.0, 'a')
        val restored = cache.write(1, 10, restoredData, redownloadable = true)
        assertEquals(oldSnapshot.sha256, restored.sha256)
        assertEquals(restoredData.segments, blocks(cache, 1, oldSnapshot, area(0.0)))
        assertNull(cache.inspect(2, 20))
        assertNotNull(cache.inspect(1, 11))
        assertEquals(quota, geometryBytes(root))
        for ((name, marker) in markers) assertEquals(name, marker.readText())
    }

    @Test fun anOversizedActiveAccountFailsWithoutChurningExistingGeometryOrLeavingTempFiles() {
        val root = noBackup.newFolder("osm-gpx-coverage")
        val cache = OsmGpxCoverageCache(root, quotaBytes = 500)
        val own = sample(0.0, 'a')
        cache.write(3, 30, own, redownloadable = true)
        cache.write(3, 31, sample(1.0, 'b'), redownloadable = true)
        val directory = File(root, "3")
        val abandoned = File(directory, "geometry-123.tmp").apply { writeText("abandoned") }
        cache.prepareAccount(3, setOf(30, 31, 32))
        assertFalse(abandoned.exists())

        repeat(2) {
            assertEquals("geometry-quota", assertThrows(IOException::class.java) {
                cache.write(3, 32, sample(2.0, 'c'), redownloadable = true)
            }.message)
            assertNull(cache.inspect(3, 32))
            assertEquals(own.segments, blocks(cache, 3, requireNotNull(cache.inspect(3, 30)), area(0.0)))
            assertNotNull(cache.inspect(3, 31))
            assertEquals(480L, geometryBytes(root))
            assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
        }
    }

    @Test fun staleActiveTraceIsPrunedAndAtomicReplacementReservesBothCopies() {
        val root = noBackup.newFolder("osm-gpx-coverage")
        val cache = OsmGpxCoverageCache(root, quotaBytes = 480)
        cache.write(4, 41, sample(0.0, 'a'), redownloadable = true)
        cache.write(4, 42, sample(1.0, 'b'))
        assertEquals("geometry-quota", assertThrows(IOException::class.java) {
            cache.write(4, 42, sample(1.0, 'c'), redownloadable = true)
        }.message)
        assertEquals("b".repeat(64), cache.inspect(4, 42)?.sha256)

        cache.prepareAccount(4, setOf(42, 43))
        assertNull(cache.inspect(4, 41))
        assertEquals("c".repeat(64), cache.write(4, 42, sample(1.0, 'c'), redownloadable = true).sha256)
        cache.write(4, 43, sample(2.0, 'd'))
        assertEquals(480L, geometryBytes(root))
    }

    @Test fun twoLargeUnrelatedRegionsStillLeaveTheNearbyPlanWithinItsPointBudget() {
        val root = noBackup.newFolder("osm-gpx-coverage")
        val cache = OsmGpxCoverageCache(root, quotaBytes = 6L * 1024 * 1024)
        val north = largeSample(65.0, 'a')
        val south = largeSample(-65.0, 'b')
        val near = sample(0.0, 'c')
        cache.prepareAccount(5, setOf(50, 51, 52))
        val snapshots = listOf(
            cache.write(5, 50, north, redownloadable = true),
            cache.write(5, 51, south, redownloadable = true),
            cache.write(5, 52, near, redownloadable = true),
        )
        assertEquals(300_002, north.segments.single().size + south.segments.single().size)
        assertEquals(near.segments, snapshots.flatMap { blocks(cache, 5, it, area(0.0)) })
        assertTrue(geometryBytes(root) < 6L * 1024 * 1024)
    }

    private fun sample(latitude: Double, digest: Char) = OsmGpxRemoteData(
        listOf(listOf(OsmDirectedPoint(latitude, 0.0), OsmDirectedPoint(latitude, 0.0001))),
        digest.toString().repeat(64),
    )

    private fun largeSample(latitude: Double, digest: Char) = OsmGpxRemoteData(
        listOf(Collections.nCopies(150_001, OsmDirectedPoint(latitude, 0.0))),
        digest.toString().repeat(64),
    )

    private fun area(latitude: Double) = OsmCoverageBounds().apply { add(latitude, 0.0) }

    private fun blocks(cache: OsmGpxCoverageCache, account: Long, trace: OsmCachedTrace, area: OsmCoverageBounds):
        List<List<OsmDirectedPoint>> = buildList { cache.nearby(account, trace, listOf(area)) { add(it) } }

    private fun geometryBytes(root: File) = root.walkTopDown().filter { it.isFile && it.extension == "geom" }.sumOf { it.length() }
}
