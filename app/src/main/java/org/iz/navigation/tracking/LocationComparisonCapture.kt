package org.iz.navigation.tracking

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

internal fun interface ComparisonRegistration { fun close() }
internal interface ComparisonSources {
    suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
        availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration
}
internal class ComparisonBusyException : IllegalStateException()
internal class ComparisonAcquisitionFailure(val issue: ComparisonIssue, val source: ComparisonSourceId? = null) : Exception()

/** No scope or persistence: the visible panel owns this suspension and cancellation. */
internal class LocationComparisonCapture(private val sources: ComparisonSources, private val now: () -> Long,
    private val guard: () -> ComparisonIssue?) {
    private val mutex = Mutex()
    private var epoch = 0L
    private val mutableState = MutableStateFlow(LocationComparisonSnapshot())
    val state: StateFlow<LocationComparisonSnapshot> = mutableState
    fun clear() {
        check(!mutex.isLocked)
        epoch++
        mutableState.value = LocationComparisonSnapshot()
    }

    suspend fun measure(onUpdate: (LocationComparisonSnapshot) -> Unit = {}): LocationComparisonSnapshot {
        if (!mutex.tryLock()) throw ComparisonBusyException()
        val token = ++epoch
        val preparation = now()
        val registrations = mutableListOf<ComparisonRegistration>()
        var active = true
        var window: LocationComparisonWindow? = null
        var registrationMillis = 0L
        var failure: ComparisonAcquisitionFailure? = null
        fun publish(phase: ComparisonPhase, issue: ComparisonIssue? = null, source: ComparisonSourceId? = null): LocationComparisonSnapshot {
            val value = window?.snapshot(now(), phase, registrationMillis, issue)
                ?: LocationComparisonSnapshot(epoch = token, phase = phase,
                    registrationMillis = ((now() - preparation) / 1_000_000).coerceAtLeast(0), issue = issue)
            val result = value.copy(issueSource = source)
            mutableState.value = result
            onUpdate(result)
            return result
        }
        fun checkGuard() { guard()?.let { throw ComparisonAcquisitionFailure(it) }; failure?.let { throw it } }
        try {
            publish(ComparisonPhase.REGISTERING)
            checkGuard()
            val ready = withTimeoutOrNull(5_000) {
                coroutineScope {
                    val monitor = launch { while (isActive) { delay(100); checkGuard() } }
                    try { ComparisonSourceId.entries.map { source -> async {
                        registrations += sources.open(source, samples = { samples ->
                            if (active && epoch == token) {
                                if (samples.any { it.motionEvidence(now()) })
                                    failure = ComparisonAcquisitionFailure(ComparisonIssue.MOVING, source)
                                window?.batch(token, source, samples, now())
                                if (window != null) publish(ComparisonPhase.MEASURING)
                            }
                        }, availability = { available ->
                            if (active && epoch == token) window?.availability(token, source, available)
                        }, failure = { reason ->
                            if (active && epoch == token) failure = ComparisonAcquisitionFailure(reason, source)
                        })
                    } }.awaitAll() } finally { monitor.cancel() }
                }
                true
            } ?: false
            if (!ready) throw ComparisonAcquisitionFailure(ComparisonIssue.REGISTRATION_TIMEOUT)
            checkGuard()
            registrationMillis = ((now() - preparation) / 1_000_000).coerceAtLeast(0)
            val measured = LocationComparisonWindow(token, now())
            window = measured
            publish(ComparisonPhase.MEASURING)
            while (now() < measured.started + measured.durationNanos) {
                checkGuard()
                delay(minOf(100L, ((measured.started + measured.durationNanos - now()) / 1_000_000).coerceAtLeast(1)))
                publish(ComparisonPhase.MEASURING)
            }
            checkGuard()
            return publish(ComparisonPhase.COMPLETE)
        } catch (cancelled: CancellationException) {
            publish(ComparisonPhase.CANCELLED)
            throw cancelled
        } catch (problem: ComparisonAcquisitionFailure) {
            return publish(ComparisonPhase.ERROR, problem.issue, problem.source)
        } catch (_: SecurityException) {
            return publish(ComparisonPhase.ERROR, ComparisonIssue.FINE_PERMISSION)
        } catch (_: Exception) {
            return publish(ComparisonPhase.ERROR, ComparisonIssue.REGISTRATION_FAILED)
        } finally {
            active = false
            window?.close()
            registrations.asReversed().forEach { runCatching { it.close() } }
            mutex.unlock()
        }
    }
}
