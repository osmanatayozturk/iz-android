package org.iz.navigation.placeinfo

import kotlinx.coroutines.runBlocking
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PublicPlaceCacheTest {
    @get:Rule val temp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC)
    private fun key(id: Long, endpoint: String = "https://api.openstreetmap.org/api/0.6") = PlaceInfoKey(endpoint, OsmRef(OsmType.NODE, id))
    private fun snapshot(id: Long, text: String = "Public name", endpoint: String = "https://api.openstreetmap.org/api/0.6") =
        PublicPlaceSnapshot(key(id, endpoint), 3, mapOf("name" to text), 1000)

    @Test fun publicSnapshotSurvivesNewInstanceAndEndpointNamespacesCannotShareObjects() = runBlocking {
        val directory = temp.newFolder()
        val first = PublicPlaceCache(directory, clock)
        assertTrue(first.put(snapshot(42)))
        val restored = PublicPlaceCache(directory, clock).cached(key(42))
        assertEquals(snapshot(42), restored?.snapshot)
        assertNull(restored?.unavailableStatus)
        assertNull(first.cached(key(42, "https://different.example/api/0.6")))
    }

    @Test fun objectEvictionUsesRecentAccessAndDefaultLimitRejectsThe201stOldestObject() = runBlocking {
        val cache = PublicPlaceCache(temp.newFolder(), clock)
        for (id in 1L..200L) assertTrue(cache.put(snapshot(id)))
        assertNotNull(cache.cached(key(1)))
        assertTrue(cache.put(snapshot(201)))
        assertNull(cache.cached(key(2)))
        assertNotNull(cache.cached(key(1))); assertNotNull(cache.cached(key(201)))
    }

    @Test fun serializedUtf8BudgetEvictsOldDataButOversizeReplacementPreservesAcceptedSnapshot() = runBlocking {
        val directory = temp.newFolder()
        val cache = PublicPlaceCache(directory, clock, maxBytes = 1500)
        assertTrue(cache.put(snapshot(1, "\u20ac".repeat(220))))
        assertTrue(cache.put(snapshot(2, "\u20ac".repeat(220))))
        assertNull(cache.cached(key(1)))
        assertNotNull(cache.cached(key(2)))
        assertFalse(cache.put(snapshot(2).copy(tags = (1..4).associate { "key$it" to "\u20ac".repeat(220) })))
        assertEquals("\u20ac".repeat(220), cache.cached(key(2))?.snapshot?.tags?.get("name"))
        assertTrue(directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= 1500)
    }

    @Test fun deletedMarkerRetainsHistoricalSnapshotAcrossRestartUntilFreshReadAndInvalidation() = runBlocking {
        val directory = temp.newFolder(); val cache = PublicPlaceCache(directory, clock)
        cache.put(snapshot(42)); cache.unavailable(key(42), 410)
        val restored = PublicPlaceCache(directory, clock)
        assertEquals(snapshot(42), restored.cached(key(42))?.snapshot)
        assertEquals(410, restored.cached(key(42))?.unavailableStatus)
        restored.put(snapshot(42).copy(version = 4, fetchedAtMillis = 2000))
        assertNull(restored.cached(key(42))?.unavailableStatus)
        restored.invalidate(key(42)); assertNull(PublicPlaceCache(directory, clock).cached(key(42)))
    }

    @Test fun acceptedSnapshotCannotBeChangedByInputMutationOrOlderVersionAndCorruptDiskIsAMiss() = runBlocking {
        val directory=temp.newFolder(); val cache=PublicPlaceCache(directory,clock)
        val tags=mutableMapOf("name" to "Accepted public name")
        assertTrue(cache.put(snapshot(42).copy(tags=tags)))
        tags["name"]="Changed input"
        assertEquals("Accepted public name",cache.cached(key(42))?.snapshot?.tags?.get("name"))
        assertFalse(cache.put(snapshot(42).copy(version=2)))
        assertEquals(3L,PublicPlaceCache(directory,clock).cached(key(42))?.snapshot?.version)
        java.io.File(directory,"places-v1.json").writeText("[".repeat(1000))
        assertNull(cache.cached(key(42)))
    }
}

