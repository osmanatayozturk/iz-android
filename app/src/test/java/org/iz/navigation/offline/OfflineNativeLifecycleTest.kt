package org.iz.navigation.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineNativeLifecycleTest {
    private class Region(override val id: Long) : OfflineDriverRegion {
        var activations = 0; var pauses = 0; var deletes = 0
        var observer: ((OfflineDriverStatus) -> Unit)? = null
        override fun observe(observer: (OfflineDriverStatus) -> Unit, failed: (String) -> Unit) { this.observer = observer }
        override fun active() { activations++ }
        override fun inactive() { pauses++ }
        override fun delete(done: () -> Unit, failed: (String) -> Unit) { deletes++; done() }
    }
    private class Driver : OfflineNativeDriver {
        var created: ((OfflineDriverRegion) -> Unit)? = null
        var fetched: ((OfflineDriverRegion) -> Unit)? = null
        override fun create(pack: OfflinePack, created: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit) { this.created = created }
        override fun get(id: Long, found: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit) { fetched = found }
    }
    private fun pack(generation: Long = 1) = OfflinePack(OfflineCorridorRequest(
        "route", "a".repeat(64), listOf(OfflinePoint(41.0, 29.0), OfflinePoint(41.01, 29.01)), 500.0, 8, 12),
        OfflinePackStatus.DOWNLOADING, generation = generation)
    private fun closure() = OfflineResourceClosure("b".repeat(64),
        setOf("https://tiles.openfreemap.org"), setOf("OpenFreeMap", "OpenMapTiles", "OpenStreetMap"), true)
    private fun events(created: (Long) -> Unit = {}) = object : OfflineRegionEvents {
        override fun created(id: Long) { created.invoke(id) }
        override fun progress(completed: Long, required: Long, precise: Boolean, closure: OfflineResourceClosure) {}
        override fun failed(message: String) {}
    }

    @Test fun matchingCreatedRegionActivatesAfterControllerAdmission() {
        var current = pack()
        val driver = Driver()
        val lifecycle = OfflineNativeLifecycle(driver, { current }, ::closure)
        lifecycle.create(current, events { current = current.copy(regionId = it) })
        val region = Region(7)
        driver.created!!.invoke(region)
        assertEquals("Matching admitted native region must start exactly once", 1, region.activations)
        assertNotNull(region.observer)
    }

    @Test fun cancelBeforeCreateCallbackCannotActivateAndCleansOnlyItsOrphan() {
        var current = pack()
        val driver = Driver(); val lifecycle = OfflineNativeLifecycle(driver, { current }, ::closure)
        lifecycle.create(current, events { fail("Retired creation must not reach the controller") })
        current = current.copy(generation = 2, status = OfflinePackStatus.PARTIAL)
        val region = Region(7); driver.created!!.invoke(region)
        assertEquals(0, region.activations)
        assertEquals(1, region.deletes)
    }

    @Test fun controllerRetiringDuringCreatedCallbackPreventsActivation() {
        var current = pack()
        val driver = Driver(); val lifecycle = OfflineNativeLifecycle(driver, { current }, ::closure)
        lifecycle.create(current, events { current = current.copy(regionId = it, generation = 2, status = OfflinePackStatus.PARTIAL) })
        val region = Region(7); driver.created!!.invoke(region)
        assertEquals(0, region.activations)
        assertEquals(0, region.observer?.let { 1 } ?: 0)
    }

    @Test fun staleSameIdCallbackCannotPauseDeleteOrActivateNewGeneration() {
        var current = pack()
        val driver = Driver(); val lifecycle = OfflineNativeLifecycle(driver, { current }, ::closure)
        lifecycle.create(current, events { current = current.copy(regionId = it) })
        val oldCreate = driver.created!!
        val region = Region(7); oldCreate(region)
        val oldObserver = region.observer
        current = current.copy(generation = 2)
        lifecycle.resume(7, events())
        driver.fetched!!.invoke(region)
        val baseline = Triple(region.activations, region.pauses, region.deletes)
        oldCreate(region)
        oldObserver?.invoke(OfflineDriverStatus(1, 1, true))
        assertEquals(baseline, Triple(region.activations, region.pauses, region.deletes))
    }

    @Test fun selectedRegionIdentityRejectsSameRouteDifferentAreaOrStyle() {
        val base = corridorPlan(pack().request)
        val style = "b".repeat(64)
        val metadata = OfflineRegionIdentity.metadata(base, style)
        val changedRing = base.rings.toMutableList().also { rings ->
            rings[0] = rings[0].mapIndexed { index, point ->
                if (index == 1) point.copy(longitude = point.longitude + 0.001) else point
            }
        }
        for (replacement in listOf(
            base.copy(request = base.request.copy(widthMeters = 700.0)),
            base.copy(request = base.request.copy(maxZoom = 13)),
            base.copy(request = base.request.copy(line = base.request.line.map { it.copy(latitude = it.latitude + 0.01) })),
            base.copy(rings = changedRing),
        )) assertFalse("Same route fingerprint cannot validate a different selected region",
            OfflineRegionIdentity.matches(metadata, replacement, style))
        assertFalse("Audited style bytes belong to the immutable region identity",
            OfflineRegionIdentity.matches(metadata, base, "c".repeat(64)))
    }

    @Test fun regionIdentitySurvivesPauseGenerationButNotRawMetadataTampering() {
        val base = corridorPlan(pack().request)
        val style = "b".repeat(64)
        val metadata = OfflineRegionIdentity.metadata(base, style)
        assertTrue(OfflineRegionIdentity.matches(metadata,
            base.copy(generation = 9, regionId = 72, downloadedBytes = 4321, status = OfflinePackStatus.PAUSED), style))
        val modified = metadata.copyOf()
        modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        assertFalse(OfflineRegionIdentity.matches(modified, base, style))
        val text = metadata.toString(Charsets.UTF_8)
        assertFalse("Native metadata contains only namespace+digest", text.contains("41.0"))
        assertTrue(text.length < 100)
    }
}
