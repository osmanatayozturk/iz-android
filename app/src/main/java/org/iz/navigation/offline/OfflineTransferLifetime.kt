package org.iz.navigation.offline

/** Actual Session lifetime decisions. Public acquisition can finish without revoking a ready map. */
class OfflineTransferLifetime(
    private val stopAcquisition: () -> Unit,
    private val retireLocal: () -> Unit,
) {
    /** False also prevents a stale/unrelated ID from pausing a newer SDK binding. */
    fun nativePaused(current: OfflinePack?, localOwner: OfflinePack?, regionId: Long): Boolean {
        if (current == null || regionId < 0 || current.regionId != regionId) return false
        if (current.status == OfflinePackStatus.READY) {
            if (localOwner == null || localOwner.generation != current.generation ||
                !sameDefinition(localOwner, current) || !validClosure(current.closure) ||
                !validClosure(localOwner.closure) ||
                localOwner.closure?.styleSha256 != current.closure?.styleSha256) return false
            // Never cancel the handoff worker here: cached SDK completion may be immediate.
            stopAcquisition()
            return true
        }
        if (current.status !in STOPPED) return false
        if (localOwner != null && (localOwner.generation != current.generation - 1 ||
                !sameDefinition(localOwner, current) || !compatibleStyle(localOwner, current))) return false
        stopAcquisition()
        retireLocal()
        return true
    }

    /** A region-less explicit stop has no native.pause callback, including a failed journal commit. */
    fun explicitStopped(before: OfflinePack?, after: OfflinePack?, localOwner: OfflinePack?) {
        if (before == null || after == null || before.status != OfflinePackStatus.DOWNLOADING ||
            before.regionId != null || after.regionId != null || after.status !in STOPPED ||
            after.generation != before.generation + 1 || !sameDefinition(before, after)) return
        if (localOwner != null && (localOwner.generation != before.generation ||
                !sameDefinition(localOwner, before) || !compatibleStyle(localOwner, before))) return
        stopAcquisition()
        retireLocal()
    }

    private fun sameDefinition(a: OfflinePack, b: OfflinePack) = a.request == b.request && a.rings == b.rings
    private fun compatibleStyle(owner: OfflinePack, current: OfflinePack) =
        validClosure(owner.closure) &&
            (current.closure == null || owner.closure?.styleSha256 == current.closure.styleSha256)
    private companion object {
        val STOPPED = setOf(OfflinePackStatus.PAUSED, OfflinePackStatus.PARTIAL, OfflinePackStatus.ERROR)
    }
}
