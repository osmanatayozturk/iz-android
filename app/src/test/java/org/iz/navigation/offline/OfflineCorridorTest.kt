package org.iz.navigation.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineCorridorTest {
    private class Store : OfflinePackStore {
        var saved: OfflinePack? = null
        var failWrites = false
        override fun read() = saved
        override fun write(pack: OfflinePack) { if (failWrites) error("disk full"); saved = pack }
        override fun remove() { saved = null }
    }
    private class Native : OfflineNativePort {
        var creates = 0
        var resumes = 0
        var pauses = 0
        var deletes = 0
        var events: OfflineRegionEvents? = null
        override fun create(plan: OfflinePack, events: OfflineRegionEvents) { creates++; this.events = events }
        override fun resume(id: Long, events: OfflineRegionEvents) { resumes++; this.events = events }
        override fun pause(id: Long) { pauses++ }
        override fun delete(id: Long) { deletes++ }
    }
    private fun request(route: String = "route-a", style: String = OPEN_FREE_MAP_STYLE) =
        OfflineCorridorRequest(route, "a".repeat(64), listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.01, 29.01)),
            widthMeters = 500.0, minZoom = 8, maxZoom = 12, styleUrl = style)
    private fun closure() = OfflineResourceClosure("b".repeat(64),
        setOf("https://tiles.openfreemap.org"), setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"), true)

    @Test fun explicitDownloadCreatesRegionOnlyAfterPreparation() {
        val store = Store(); val native = Native(); val controller = OfflineCorridorController(store, native)
        assertFalse(controller.download())
        val prepared = controller.prepare(request())
        assertEquals(OfflinePackStatus.PREPARED, prepared.status)
        assertEquals(0, native.creates)
        assertTrue("Explicit download must dispatch exactly one native create", controller.download())
        assertEquals(1, native.creates)
        assertFalse(controller.download())
        assertEquals(1, native.creates)
    }

    @Test fun defaultOsmAndUnknownSourcesNeverCreateADownload() {
        for (source in listOf("https://tile.openstreetmap.org/{z}/{x}/{y}.png",
            "https://evil.example/style", "https://tiles.openfreemap.org.evil.example/styles/liberty")) {
            val native = Native(); val controller = OfflineCorridorController(Store(), native)
            assertThrows(IllegalArgumentException::class.java) { controller.prepare(request(style = source)) }
            assertFalse(controller.download())
            assertEquals(0, native.creates)
        }
    }

    @Test fun corridorIsBufferedAndNeverReplacedWithItsWholeBoundingRectangle() {
        val controller = OfflineCorridorController(Store(), Native())
        val points = listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.02, 29.0), OfflinePoint(41.02, 29.02))
        val plan = controller.prepare(request().copy(line = points))
        assertTrue(plan.rings.isNotEmpty())
        assertTrue("A bent path must retain corridor shape instead of a four-corner bbox",
            plan.rings.sumOf { it.size } > 5)
        assertTrue(plan.tileCount in 1..3000)
        assertEquals(request().sourceFingerprint, plan.request.sourceFingerprint)
    }

    @Test fun unsafeGeometryOrExcessiveTilePlanIsRejectedBeforeNativeWork() {
        val native = Native(); val controller = OfflineCorridorController(Store(), native)
        for (bad in listOf(
            request().copy(line = listOf(OfflinePoint(Double.NaN, 29.0), OfflinePoint(41.0, 29.1))),
            request().copy(widthMeters = -1.0),
            request().copy(minZoom = 15, maxZoom = 14),
            request().copy(line = listOf(OfflinePoint(0.0, 0.0), OfflinePoint(50.0, 100.0)), maxZoom = 19),
        )) assertThrows(IllegalArgumentException::class.java) { controller.prepare(bad) }
        assertEquals(0, native.creates)
    }

    @Test fun readyRequiresPreciseCompleteResourceClosure() {
        val store = Store(); val native = Native(); val c = OfflineCorridorController(store, native)
        c.prepare(request()); assertTrue(c.download())
        val events = requireNotNull(native.events)
        events.created(12L)
        events.progress(10, 10, false, closure())
        assertNotEquals(OfflinePackStatus.READY, c.state.value?.status)
        events.progress(9, 10, true, closure())
        assertNotEquals(OfflinePackStatus.READY, c.state.value?.status)
        events.progress(10, 10, true, closure().copy(complete = false))
        assertNotEquals(OfflinePackStatus.READY, c.state.value?.status)
        events.progress(10, 10, true, closure())
        assertEquals(OfflinePackStatus.READY, c.state.value?.status)
        assertEquals(c.state.value, store.saved)
    }

    @Test fun maliciousClosureCannotPublishReady() {
        val native = Native(); val c = OfflineCorridorController(Store(), native)
        c.prepare(request()); c.download()
        native.events!!.created(12)
        native.events!!.progress(1, 1, true, closure().copy(origins = setOf("https://tile.openstreetmap.org")))
        assertNotEquals(OfflinePackStatus.READY, c.state.value?.status)
    }

    @Test fun pauseResumeRetainsCumulativeBudgetAndRegion() {
        val native = Native(); val c = OfflineCorridorController(Store(), native)
        c.prepare(request()); assertTrue(c.download()); native.events!!.created(12)
        c.recordTransfer(1200)
        assertTrue(c.pause())
        assertEquals(OfflinePackStatus.PAUSED, c.state.value?.status)
        assertTrue(c.resume())
        assertEquals(1, native.creates); assertEquals(1, native.resumes)
        assertEquals(1200L, c.state.value?.downloadedBytes)
    }

    @Test fun cancelledOrDeletedEpochCannotAcceptDelayedCallbacks() {
        val native = Native(); val c = OfflineCorridorController(Store(), native)
        c.prepare(request()); c.download(); val late = native.events!!
        late.created(12); assertTrue(c.cancel())
        late.progress(1, 1, true, closure())
        assertEquals(OfflinePackStatus.PARTIAL, c.state.value?.status)
        assertTrue(c.delete())
        late.progress(1, 1, true, closure())
        assertNull(c.state.value)
        assertEquals(1, native.deletes)
    }

    @Test fun restoredDownloadingBecomesPartialAndNeverStartsNativeWork() {
        val store = Store(); val original = OfflineCorridorController(store, Native())
        original.prepare(request())
        store.saved = store.saved!!.copy(status = OfflinePackStatus.DOWNLOADING, regionId = 12L, downloadedBytes = 4321)
        val native = Native(); val restored = OfflineCorridorController(store, native)
        assertEquals(OfflinePackStatus.PARTIAL, restored.state.value?.status)
        assertEquals(4321L, restored.state.value?.downloadedBytes)
        assertEquals(0, native.creates + native.resumes)
    }

    @Test fun activeUseBlocksDeleteAndRouteIdentityMismatchDoesNotClaimReady() {
        val native = Native(); val c = OfflineCorridorController(Store(), native)
        c.prepare(request()); c.download(); native.events!!.created(12)
        native.events!!.progress(1, 1, true, closure())
        assertTrue(c.mapReady("route-a", "a".repeat(64)))
        assertFalse(c.mapReady("route-b", "a".repeat(64)))
        assertFalse(c.mapReady("route-a", "c".repeat(64)))
        c.setInUse(true)
        assertFalse(c.delete()); assertEquals(0, native.deletes)
    }

    @Test fun manifestFailureCannotDispatchOrInventReady() {
        val store = Store(); val native = Native(); val c = OfflineCorridorController(store, native)
        c.prepare(request()); store.failWrites = true
        assertFalse(c.download())
        assertEquals(0, native.creates)
        assertNotEquals(OfflinePackStatus.DOWNLOADING, c.state.value?.status)
    }

    @Test fun oversizedCumulativeTransferFailsAndDoesNotResetOnResume() {
        val native = Native(); val c = OfflineCorridorController(Store(), native)
        c.prepare(request()); c.download(); native.events!!.created(12)
        assertTrue(c.recordTransfer(49_999_999))
        assertFalse(c.recordTransfer(2))
        assertEquals(OfflinePackStatus.ERROR, c.state.value?.status)
        assertEquals(49_999_999L, c.state.value?.downloadedBytes)
        assertFalse(c.resume())
    }
    @Test fun pendingAcquisitionCannotBeRepreparedToResetItsCumulativeBudget() {
        val native = Native(); val controller = OfflineCorridorController(Store(), native)
        controller.prepare(request()); assertTrue(controller.download())
        assertTrue(controller.recordTransfer(123))
        assertTrue(controller.cancel())
        assertThrows(IllegalStateException::class.java) { controller.prepare(request("route-b")) }
        assertEquals(123L, controller.state.value!!.downloadedBytes)
        assertTrue(controller.resume())
        assertEquals(123L, controller.state.value!!.downloadedBytes)
    }
}
