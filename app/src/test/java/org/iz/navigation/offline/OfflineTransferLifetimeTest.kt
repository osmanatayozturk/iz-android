package org.iz.navigation.offline

import java.io.IOException
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

/** Actual Controller commits call the exact Session decision seam and real local Call.Factory. */
class OfflineTransferLifetimeTest {
    private class Fixture(created: Boolean = true) {
        private class Store : OfflinePackStore {
            var pack: OfflinePack? = null
            var failWrites = false
            override fun read() = pack
            override fun write(pack: OfflinePack) {
                if (failWrites) error("fixture journal failure")
                this.pack = pack
            }
            override fun remove() { pack = null }
        }
        private val store = Store()
        lateinit var controller: OfflineCorridorController
        lateinit var events: OfflineRegionEvents
        val local = OfflineLocalResources()
        val identity = "a".repeat(64)
        val bytes = """{"version":8,"name":"retained-local-style"}""".toByteArray()
        var localLease: AutoCloseable? = null
        var localOwner: OfflinePack? = null
        var stoppedAcquisition = 0
        var retiredLocal = 0
        private val lifetime = OfflineTransferLifetime(
            { stoppedAcquisition++ },
            { retiredLocal++; localLease?.close(); localLease = null; localOwner = null },
        )
        val closure = OfflineResourceClosure(OfflineStyleAudit.sha256(bytes), setOf("https://tiles.openfreemap.org"),
            setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"), true)
        init {
            val native = object : OfflineNativePort {
                override fun create(plan: OfflinePack, events: OfflineRegionEvents) { this@Fixture.events = events }
                override fun resume(id: Long, events: OfflineRegionEvents) { this@Fixture.events = events }
                override fun pause(id: Long) { lifetime.nativePaused(controller.state.value, localOwner, id) }
                override fun delete(id: Long) {}
            }
            controller = OfflineCorridorController(store, native)
            controller.prepare(OfflineCorridorRequest("fixture-route", "c".repeat(64),
                listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.001, 29.001)), 100.0, 0, 0))
            check(controller.download())
            val owner = requireNotNull(controller.state.value)
            localOwner = owner.copy(closure = closure)
            localLease = local.install(identity, owner.generation, mapOf(
                "style.json" to OfflineLocalResource(OfflineLocalRole.STYLE, bytes, OfflineStyleAudit.sha256(bytes))
            )) {
                controller.state.value?.let {
                    it.generation == owner.generation && it.request == owner.request &&
                        it.status in setOf(OfflinePackStatus.DOWNLOADING, OfflinePackStatus.READY)
                } == true
            }
            if (created) events.created(17)
        }
        fun stopExplicit(cancel: Boolean, failJournal: Boolean): Boolean {
            val before = controller.state.value
            val owner = localOwner
            store.failWrites = failJournal
            val stopped = if (cancel) controller.cancel() else controller.pause()
            lifetime.explicitStopped(before, controller.state.value, owner)
            return stopped
        }
        fun localRead(): ByteArray = local.newCall(Request.Builder()
            .url(OfflineLocalResources.styleUrl(identity)).build()).execute().use { it.body!!.bytes() }
    }

    @Test fun readyCompletionStopsAcquisitionButKeepsTheActualLocalCorpusReadable() {
        val f = Fixture()
        f.events.progress(1, 1, true, f.closure)
        assertEquals(OfflinePackStatus.READY, f.controller.state.value!!.status)
        assertEquals(1, f.stoppedAcquisition)
        assertNotNull("Automatic READY native.pause must retain the installed local lease", f.localLease)
        assertEquals(0, f.retiredLocal)
        assertArrayEquals(f.bytes, f.localRead())
        f.localLease?.close()
    }

    @Test fun explicitUserPauseRetiresTheActualLocalCorpus() {
        val f = Fixture()
        assertArrayEquals(f.bytes, f.localRead())
        assertTrue(f.controller.pause())
        assertEquals(OfflinePackStatus.PAUSED, f.controller.state.value!!.status)
        assertEquals(1, f.stoppedAcquisition)
        assertEquals(1, f.retiredLocal)
        assertNull(f.localLease)
        assertThrows(IOException::class.java) { f.localRead() }
    }
    @Test fun earlyPauseAndCancelRetireWithoutARegionEvenWhenJournalFailureReturnsFalse() {
        for (cancel in listOf(false, true)) for (failJournal in listOf(false, true)) {
            val f = Fixture(created = false)
            assertNull(f.controller.state.value!!.regionId)
            assertArrayEquals(f.bytes, f.localRead())
            assertEquals(!failJournal, f.stopExplicit(cancel, failJournal))
            val expected = if (failJournal) OfflinePackStatus.ERROR else
                if (cancel) OfflinePackStatus.PARTIAL else OfflinePackStatus.PAUSED
            assertEquals(expected, f.controller.state.value!!.status)
            assertNull("Explicit stop must revoke the actual lease before a native region exists", f.localLease)
            assertEquals(1, f.stoppedAcquisition)
            assertEquals(1, f.retiredLocal)
            assertThrows(IOException::class.java) { f.localRead() }
        }
    }
}
