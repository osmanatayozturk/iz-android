package org.iz.navigation.offline

data class OfflineDriverStatus(val completed: Long, val required: Long, val precise: Boolean)
interface OfflineDriverRegion {
    val id: Long
    fun observe(observer: (OfflineDriverStatus) -> Unit, failed: (String) -> Unit)
    fun active()
    fun inactive()
    fun delete(done: () -> Unit, failed: (String) -> Unit)
}
interface OfflineNativeDriver {
    fun create(pack: OfflinePack, created: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit)
    fun get(id: Long, found: (OfflineDriverRegion) -> Unit, failed: (String) -> Unit)
}

/**
 * Command dispatcher must execute on MAIN in the Android binding. Admission is checked inside
 * every dispatched closure, not merely before posting. Construction performs no native work.
 */
class OfflineNativeLifecycle(
    private val driver: OfflineNativeDriver,
    private val current: () -> OfflinePack?,
    private val closure: () -> OfflineResourceClosure,
    private val dispatch: (() -> Unit) -> Unit = { it() },
) : OfflineNativePort {
    private class Binding(val generation: Long, val region: OfflineDriverRegion) {
        var active = false
        var deleting = false
    }
    // All access belongs to dispatch's single command owner (MAIN for the real SDK).
    private val bindings = mutableMapOf<Long, Binding>()

    override fun create(plan: OfflinePack, events: OfflineRegionEvents) {
        dispatch {
            if (!admitted(plan, null)) return@dispatch
            try {
                driver.create(plan, { region -> dispatch { attach(plan, region, events) } },
                    { dispatch { failIfCurrent(plan, events) } })
            } catch (_: Exception) { failIfCurrent(plan, events) }
        }
    }
    override fun resume(id: Long, events: OfflineRegionEvents) {
        val expected = current() ?: return
        dispatch {
            if (!admitted(expected, id)) return@dispatch
            try {
                driver.get(id, { region -> dispatch { attach(expected, region, events) } },
                    { dispatch { failIfCurrent(expected, events) } })
            } catch (_: Exception) { failIfCurrent(expected, events) }
        }
    }
    private fun admitted(expected: OfflinePack, id: Long?): Boolean {
        val live = current() ?: return false
        return live.status == OfflinePackStatus.DOWNLOADING && live.generation == expected.generation &&
            live.request == expected.request && live.rings == expected.rings &&
            (id == null || live.regionId == id)
    }
    private fun attach(expected: OfflinePack, region: OfflineDriverRegion, events: OfflineRegionEvents) {
        val previous = bindings[region.id]
        // A delayed callback for the same native ID must not mutate its newer wrapper/observer.
        if (previous != null && previous.generation > expected.generation) return
        if (!admitted(expected, expected.regionId)) { orphan(expected, region); return }
        if (previous?.generation == expected.generation && (previous.active || previous.deleting)) return
        val owned = Binding(expected.generation, region)
        bindings[region.id] = owned
        try {
            events.created(region.id) // Controller persists ownership; may retire this operation.
            if (!isOwnedCurrent(expected, owned)) { orphan(expected, region); return }
            region.observe(
                { status ->
                    dispatch {
                        if (isOwnedCurrent(expected, owned)) {
                            try { events.progress(status.completed, status.required, status.precise, closure()) }
                            catch (_: Exception) { failIfCurrent(expected, events) }
                        }
                    }
                },
                { dispatch { if (isOwnedCurrent(expected, owned)) failIfCurrent(expected, events) } },
            )
            // Installing the observer can synchronously complete/cancel in a driver or test fixture.
            if (isOwnedCurrent(expected, owned)) { owned.active = true; region.active() }
        } catch (_: Exception) {
            failIfCurrent(expected, events)
            if (bindings[region.id] === owned && !owned.deleting) runCatching { region.inactive() }
        }
    }
    private fun isOwnedCurrent(expected: OfflinePack, binding: Binding) =
        bindings[binding.region.id] === binding && !binding.deleting && admitted(expected, binding.region.id)

    private fun orphan(expected: OfflinePack, region: OfflineDriverRegion) {
        val binding = bindings[region.id]
        if (binding != null && (binding.generation > expected.generation || binding.deleting)) return
        val live = current()
        if (live?.regionId == region.id) {
            // Cancellation inside created retained this partial region for explicit resume.
            // If a new generation is already active, do not even pause its native ID.
            if (live.status != OfflinePackStatus.DOWNLOADING)
                runCatching { region.inactive() }
            return
        }
        val orphan = Binding(expected.generation, region).also { it.deleting = true }
        bindings[region.id] = orphan
        try {
            region.inactive()
            region.delete(
                { dispatch { if (bindings[region.id] === orphan) bindings.remove(region.id) } },
                { dispatch { if (bindings[region.id] === orphan) bindings.remove(region.id) } },
            )
        } catch (_: Exception) { if (bindings[region.id] === orphan) bindings.remove(region.id) }
    }
    private fun failIfCurrent(expected: OfflinePack, events: OfflineRegionEvents) {
        if (admitted(expected, expected.regionId)) events.failed("Harita alanı hazırlanamadı. Mevcut rota korundu.")
    }

    override fun pause(id: Long) {
        dispatch {
            val binding = bindings[id] ?: return@dispatch
            if (binding.deleting) return@dispatch
            val live = current()
            if (live?.regionId == id && live.status == OfflinePackStatus.DOWNLOADING &&
                live.generation != binding.generation) return@dispatch
            binding.active = false
            runCatching { binding.region.inactive() }
        }
    }
    override fun delete(id: Long) { delete(id, {}, {}) }
    override fun delete(id: Long, completed: () -> Unit, failed: (String) -> Unit) {
        val expected = current() ?: return
        dispatch {
            fun stillOwned() = current()?.let {
                it.regionId == id && it.generation == expected.generation && it.request == expected.request
            } == true
            if (!stillOwned()) return@dispatch
            fun remove(region: OfflineDriverRegion) {
                if (!stillOwned()) return
                val existing = bindings[id]
                if (existing != null && (existing.generation > expected.generation || existing.deleting)) return
                val removing = existing ?: Binding(expected.generation, region).also { bindings[id] = it }
                removing.deleting = true; removing.active = false
                try {
                    region.inactive()
                    region.delete(
                        { dispatch {
                            if (bindings[id] === removing) {
                                bindings.remove(id)
                                if (stillOwned()) completed()
                            }
                        } },
                        { dispatch {
                            if (bindings[id] === removing) {
                                removing.deleting = false
                                if (stillOwned()) failed("Harita alanı kaldırılamadı.")
                            }
                        } },
                    )
                } catch (_: Exception) {
                    removing.deleting = false
                    if (stillOwned()) failed("Harita alanı kaldırılamadı.")
                }
            }
            val existing = bindings[id]
            if (existing != null) remove(existing.region)
            else try { driver.get(id, { region -> dispatch { remove(region) } }, { if (stillOwned()) failed("Harita alanı kaldırılamadı.") }) }
            catch (_: Exception) { if (stillOwned()) failed("Harita alanı kaldırılamadı.") }
        }
    }
}
