package org.iz.navigation.tracking

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationComparisonCaptureTest {
    @Test fun providerLossDuringRegistrationStopsThePendingPairWithoutWaitingForTimeout() = runTest {
        var closed = 0
        val sources = object : ComparisonSources {
            override suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
                availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration {
                if (source == ComparisonSourceId.FUSED) try { awaitCancellation() } finally { closed++ }
                failure(ComparisonIssue.PROVIDER_DISABLED)
                return ComparisonRegistration { closed++ }
            }
        }
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { null })
        val result = capture.measure()
        assertEquals(ComparisonIssue.PROVIDER_DISABLED, result.issue)
        assertEquals(ComparisonSourceId.GPS, result.issueSource)
        assertNull(result.startedElapsedNanos)
        assertEquals(2, closed)
        assertEquals(100L, currentTime)
    }
    @Test fun sharedWindowStartsOnlyAfterBothSubscriptionsAreReady() = runTest {
        val callbacks = mutableMapOf<ComparisonSourceId, (List<ComparisonSample>) -> Unit>()
        val sources = object : ComparisonSources {
            override suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
                availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration {
                callbacks[source] = samples
                samples(listOf(ComparisonSample(1, emptyMap())))
                if (source == ComparisonSourceId.FUSED) delay(1_000)
                return ComparisonRegistration { }
            }
        }
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { null })
        val job = launch { capture.measure() }
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertEquals(1_000_000_001L, capture.state.value.startedElapsedNanos)
        assertEquals(0, capture.state.value.gps.received)
        callbacks.getValue(ComparisonSourceId.GPS)(listOf(ComparisonSample(1_000_000_001, emptyMap())))
        advanceTimeBy(10_000); runCurrent(); job.join()
        assertEquals(10_000L, capture.state.value.elapsedMillis)
        assertEquals(1_000L, capture.state.value.registrationMillis)
        assertEquals(1, capture.state.value.gps.accepted)
    }
    @Test fun registrationTimeoutCancelsThePendingSourceAndClosesTheReadySource() = runTest {
        var readyClosed = 0
        var pendingClosed = 0
        val sources = object : ComparisonSources {
            override suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
                availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration {
                if (source == ComparisonSourceId.FUSED) try { awaitCancellation() } finally { pendingClosed++ }
                return ComparisonRegistration { readyClosed++ }
            }
        }
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { null })
        val result = capture.measure()
        assertEquals(ComparisonIssue.REGISTRATION_TIMEOUT, result.issue)
        assertNull(result.startedElapsedNanos)
        assertEquals(1, pendingClosed); assertEquals(1, readyClosed)
    }
    @Test fun movementInTheDiagnosticStreamStopsBothWithoutAProductionLocationRequest() = runTest {
        val sources = Sources()
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { null })
        val job = launch { capture.measure() }
        runCurrent()
        sources.callbacks.first()(listOf(ComparisonSample(1, mapOf(
            ComparisonField.SPEED to 2.0, ComparisonField.HORIZONTAL_ACCURACY to 5.0))))
        advanceTimeBy(100); runCurrent(); job.join()
        assertEquals(ComparisonIssue.MOVING, capture.state.value.issue)
        assertEquals(2, sources.closed)
    }
    private class Sources : ComparisonSources {
        var opened = 0
        var closed = 0
        val callbacks = mutableListOf<(List<ComparisonSample>) -> Unit>()
        override suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
            availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration {
            opened++; callbacks += samples
            var done = false
            return ComparisonRegistration { if (!done) { done = true; closed++ } }
        }
    }
    @Test fun cancellationClosesBothSourcesAndLateCallbacksCannotEnterANewEpoch() = runTest {
        val sources = Sources()
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { null })
        val first = launch { capture.measure() }
        runCurrent()
        assertEquals(2, sources.opened)
        first.cancelAndJoin()
        assertEquals(2, sources.closed)
        assertEquals(ComparisonPhase.CANCELLED, capture.state.value.phase)
        val old = sources.callbacks.first()
        val second = launch { capture.measure() }
        runCurrent()
        old(listOf(ComparisonSample(currentTime * 1_000_000 + 1, emptyMap())))
        assertEquals(0, capture.state.value.fused.received)
        advanceTimeBy(10_000); runCurrent(); second.join()
        assertEquals(4, sources.closed)
        assertEquals(ComparisonPhase.COMPLETE, capture.state.value.phase)
    }
    @Test fun permissionLossStopsBothAndRepeatedStartCannotReplaceTheWindow() = runTest {
        val sources = Sources()
        var issue: ComparisonIssue? = null
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { issue })
        val first = launch { capture.measure() }
        runCurrent()
        try { capture.measure(); fail("A second capture must not start") }
        catch (_: ComparisonBusyException) { }
        assertEquals(2, sources.opened)
        issue = ComparisonIssue.FINE_PERMISSION
        advanceTimeBy(100); runCurrent(); first.join()
        assertEquals(ComparisonIssue.FINE_PERMISSION, capture.state.value.issue)
        assertEquals(ComparisonPhase.ERROR, capture.state.value.phase)
        assertEquals(2, sources.closed)
    }
    @Test fun oneRegistrationFailureClosesTheOtherSourceWithoutStartingAWindow() = runTest {
        var closed = 0
        val sources = object : ComparisonSources {
            override suspend fun open(source: ComparisonSourceId, samples: (List<ComparisonSample>) -> Unit,
                availability: (Boolean) -> Unit, failure: (ComparisonIssue) -> Unit): ComparisonRegistration {
                if (source == ComparisonSourceId.GPS) throw ComparisonAcquisitionFailure(ComparisonIssue.GPS_UNAVAILABLE, source)
                return ComparisonRegistration { closed++ }
            }
        }
        val capture = LocationComparisonCapture(sources, { currentTime * 1_000_000 + 1 }, { null })
        val result = capture.measure()
        assertEquals(1, closed)
        assertNull(result.startedElapsedNanos)
        assertEquals(ComparisonIssue.GPS_UNAVAILABLE, result.issue)
        assertEquals(ComparisonSourceId.GPS, result.issueSource)
    }
}
