package org.iz.navigation.tracking

import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex

/** Serializes recording decisions across activity, receiver, service and worker in this process. */
internal object TrackingCoordinator {
    val mutex = Mutex()
    const val MANUAL_START_TIMEOUT_MILLIS = 30_000L

    private data class PendingManualStart(val journeyId: String, val requestedAtElapsed: Long)
    private var pendingManualStart: PendingManualStart? = null

    // Recording decisions hold mutex; synchronization also allows non-suspending service teardown.
    @Synchronized
    fun markPendingManualStart(journeyId: String, nowElapsed: Long = SystemClock.elapsedRealtime()) {
        pendingManualStart = PendingManualStart(journeyId, nowElapsed)
    }

    @Synchronized
    fun pendingManualStartId(nowElapsed: Long = SystemClock.elapsedRealtime()): String? {
        val pending = pendingManualStart ?: return null
        val age = nowElapsed - pending.requestedAtElapsed
        if (age < 0 || age >= MANUAL_START_TIMEOUT_MILLIS) {
            pendingManualStart = null
            return null
        }
        return pending.journeyId
    }

    fun isManualStartPending(journeyId: String): Boolean = pendingManualStartId() == journeyId

    fun hasPendingManualStartOtherThan(journeyId: String?): Boolean =
        pendingManualStartId()?.let { it != journeyId } == true

    @Synchronized
    fun clearPendingManualStart(journeyId: String) {
        if (pendingManualStart?.journeyId == journeyId) pendingManualStart = null
    }

    /** Successful diary replacement invalidates every in-flight start from the previous diary. */
    @Synchronized
    fun clearPendingManualStarts() {
        pendingManualStart = null
    }
}
