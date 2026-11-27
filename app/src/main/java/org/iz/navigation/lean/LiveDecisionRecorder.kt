package org.iz.navigation.lean

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One confirmed recorder owns cumulative counts. Neither GPS nor IMU records enter this queue. */
internal class LiveDecisionRecorder(
    owner: LeanLiveDecisionOwner,
    val calibration: LeanCalibration,
    scope: CoroutineScope,
    private val store: LeanStore,
) {
    val token = owner.token
    private var latest = owner.summary
    private val updates = Channel<LeanLiveDecisionSummary>(Channel.CONFLATED)
    private val writer = scope.launch {
        for (summary in updates) {
            try {
                store.checkpointLiveDecisions(token, calibration.epoch, summary)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // The next cumulative update or the final flush includes all outstanding counts.
            }
        }
    }

    @Synchronized fun event(decision: LiveHandlebarDecision?) {
        val counts = latest.counts
        val next = if (decision == null || latest.total >= LeanLiveDecisionSummary.MAX_DECISIONS) {
            if (latest.dialOpened) return
            LeanLiveDecisionSummary(true, counts)
        } else {
            val code = LeanLiveDecisionCode.from(decision)
            LeanLiveDecisionSummary(true, counts + (code to (latest.count(code) + 1L)))
        }
        latest = next
        updates.trySend(next)
    }

    /** Called after synchronous detachment; drain before sealing the owner's token. */
    suspend fun finish(endNanos: Long) {
        updates.close()
        writer.join()
        val final = synchronized(this) { latest }
        store.closeLiveDecisions(token, calibration.epoch, endNanos, final)
    }
}
