package org.iz.navigation.offline

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.*
import org.junit.Test

/** Real manifest/controller; only the external native preparation port is recorded, never initialized.
 * The byte count is ledger metadata, not an allocated50MB corpus or evidence of native readability.
 */
class OfflineCapRestoreTest {
    @Test fun readyAtExactCapCanColdRestoreAndResumeLocallyOnExplicitAction() = withDisk { directory ->
        assertCompleteLocalRestore(directory, 50_000_000L)
    }

    @Test fun readyOneByteBelowCapCanColdRestoreAndResumeWithoutNewProviderWork() = withDisk { directory ->
        assertCompleteLocalRestore(directory, 49_999_999L)
    }

    @Test fun incompleteAtCapCannotDispatchMoreProviderWorkResetBudgetOrClaimReady() = withDisk { directory ->
        val saved = seed(directory, 50_000_000L, complete = false)
        val port = RecordingPort(completeLocal = false)
        val restored = OfflineCorridorController(FileOfflinePackStore(directory.toFile()), port)
        val before = requireNotNull(restored.state.value)
        assertEquals(OfflinePackStatus.PARTIAL, before.status)
        assertEquals(saved.generation + 1, before.generation)
        assertEquals(50_000_000L, before.downloadedBytes)
        assertNull(before.closure)
        assertFalse(restored.mapReady(ROUTE, FINGERPRINT))
        assertNoDispatch(port)

        assertFalse("An incomplete pack at the cap cannot authorize new preparation", restored.resume())
        assertFalse(restored.download())
        assertFalse("No active transfer may add another byte", restored.recordTransfer(1))
        assertThrows(IllegalStateException::class.java) { restored.prepare(request().copy(routeId = "other-route")) }

        assertEquals(before, restored.state.value)
        assertEquals(before, FileOfflinePackStore(directory.toFile()).read())
        assertFalse(restored.mapReady(ROUTE, FINGERPRINT))
        assertNoDispatch(port)
        assertEquals("Incomplete local resources must never enter provider preparation", 0, port.providerPreparationAttempts)
    }

    private fun assertCompleteLocalRestore(directory: Path, bytes: Long) {
        val saved = seed(directory, bytes, complete = true)
        val port = RecordingPort(completeLocal = true)
        val restored = OfflineCorridorController(FileOfflinePackStore(directory.toFile()), port)
        val cold = requireNotNull(restored.state.value)
        assertEquals(OfflinePackStatus.PARTIAL, cold.status)
        assertEquals(saved.generation + 1, cold.generation)
        assertEquals(bytes, cold.downloadedBytes)
        assertEquals(REGION, cold.regionId)
        assertEquals(proof(), cold.closure)
        assertEquals(cold, FileOfflinePackStore(directory.toFile()).read())
        assertFalse("Cold construction alone must not claim active local readiness", restored.mapReady(ROUTE, FINGERPRINT))
        assertNoDispatch(port)

        assertTrue("A fully retained READY pack must allow explicit local revalidation at $bytes bytes", restored.resume())

        val active = requireNotNull(restored.state.value)
        assertEquals(listOf(REGION), port.resumed)
        assertEquals(1, port.localRevalidations)
        assertEquals(0, port.providerPreparationAttempts)
        assertEquals(0, port.creates)
        assertEquals(OfflinePackStatus.READY, active.status)
        assertTrue(active.generation > cold.generation)
        assertEquals(bytes, active.downloadedBytes)
        assertEquals(REGION, active.regionId)
        assertEquals(cold.request, active.request)
        assertEquals(cold.rings, active.rings)
        assertEquals(proof(), active.closure)
        assertTrue(restored.mapReady(ROUTE, FINGERPRINT))
        assertEquals(active, FileOfflinePackStore(directory.toFile()).read())
        assertFalse("A ready local corpus is not a second resume command", restored.resume())
        assertEquals(listOf(REGION), port.resumed)
    }

    private fun seed(directory: Path, bytes: Long, complete: Boolean): OfflinePack {
        val store = FileOfflinePackStore(directory.toFile())
        val port = RecordingPort(completeLocal = complete)
        val first = OfflineCorridorController(store, port)
        first.prepare(request())
        assertTrue(first.download())
        assertEquals(1, port.creates)
        assertTrue(first.recordTransfer(bytes))
        val events = requireNotNull(port.createdEvents)
        if (complete) {
            events.progress(1, 1, true, proof())
            assertEquals(OfflinePackStatus.READY, first.state.value!!.status)
            assertTrue("Exactly the cap is valid for an already active completed pack", first.mapReady(ROUTE, FINGERPRINT))
        } else {
            events.progress(0, 1, true, proof().copy(complete = false))
            assertEquals(OfflinePackStatus.DOWNLOADING, first.state.value!!.status)
            assertFalse(first.mapReady(ROUTE, FINGERPRINT))
        }
        val saved = requireNotNull(FileOfflinePackStore(directory.toFile()).read())
        assertEquals(bytes, saved.downloadedBytes)
        assertEquals(first.state.value, saved)
        return saved
    }

    private class RecordingPort(private val completeLocal: Boolean) : OfflineNativePort {
        var creates = 0
        var createdEvents: OfflineRegionEvents? = null
        val resumed = mutableListOf<Long>()
        var localRevalidations = 0
        var providerPreparationAttempts = 0
        override fun create(plan: OfflinePack, events: OfflineRegionEvents) {
            creates++
            createdEvents = events
            events.created(REGION)
        }
        override fun resume(id: Long, events: OfflineRegionEvents) {
            resumed += id
            if (completeLocal) {
                localRevalidations++
                events.progress(1, 1, true, proof())
            } else {
                providerPreparationAttempts++
                events.failed("Incomplete local fixture cannot fetch another provider resource")
            }
        }
        override fun pause(id: Long) = Unit
        override fun delete(id: Long) = Unit
    }

    private fun assertNoDispatch(port: RecordingPort) {
        assertEquals(0, port.creates)
        assertTrue(port.resumed.isEmpty())
        assertEquals(0, port.localRevalidations)
    }

    private fun withDisk(block: (Path) -> Unit) {
        val parent = Files.createDirectories(Paths.get(System.getProperty("iz.offline.testRoot",
            "D:/CodexBuilds/iz-roadmap-five-20261102/unit-temp"))).toRealPath()
        val directory = Files.createTempDirectory(parent, "offline-cap-restore-").toRealPath()
        check(directory.parent == parent)
        try { block(directory) }
        finally {
            check(directory.toRealPath().parent == parent)
            Files.newDirectoryStream(directory).use { entries -> entries.forEach { entry ->
                check(entry.parent == directory && !Files.isSymbolicLink(entry) && Files.isRegularFile(entry))
                Files.delete(entry)
            } }
            Files.delete(directory)
        }
    }

    private companion object {
        const val ROUTE = "cap-boundary-route"
        const val FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val REGION = 7L
        fun request() = OfflineCorridorRequest(ROUTE, FINGERPRINT,
            listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.001, 29.001)), 100.0, 0, 0)
        // Synthetic external completion fact, never a full-profile license/native acceptance claim.
        fun proof() = OfflineResourceClosure("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            setOf("https://tiles.openfreemap.org"), setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"), true)
    }
}
