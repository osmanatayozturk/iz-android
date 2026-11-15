package org.iz.navigation.ui

import java.net.UnknownHostException
import java.net.ConnectException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.iz.navigation.integration.NearbyCode
import org.junit.Assert.*
import org.junit.Test

class NearbyDiagnosticsOwnerTest {
    @Test fun failedDnsAttemptHasSafeDiagnosticWithoutRetainingRawException() = runTest {
        val secret = "private-account https://secret.invalid/?token=secret 39.12345,32.98765"
        var calls = 0
        val owner = PlaceInformationDiscoveryOwner(this,
            { _, _ -> calls++; throw UnknownHostException(secret) },
            { fail("A failed request must not open information") }, { "entry" })
        owner.tap(0.0, 0.0, 17.0)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(owner.state.retryable)
        assertFalse(owner.state.busy)
        // This assertion can run against the unmodified baseline: RED must be missing
        // diagnosis, rather than an unresolved symbol/compiler failure.
        val getter = owner.state.javaClass.methods.singleOrNull { it.name == "getDiagnostic" }
        assertNotNull("A failed nearby attempt must expose a safe request-scoped diagnostic", getter)
        val diagnostic = requireNotNull(getter).invoke(owner.state)
        assertNotNull(diagnostic)
        assertEquals("ND_DNS", diagnostic.javaClass.getMethod("getCode").invoke(diagnostic).toString())
        assertTrue((diagnostic.javaClass.getMethod("getElapsedMillis").invoke(diagnostic) as Long) >= 0)
        assertFalse(diagnostic.toString().contains(secret))
        assertFalse(diagnostic.javaClass.declaredFields.any { Throwable::class.java.isAssignableFrom(it.type) })
    }

    @Test fun explicitRetryClearsOldDiagnosisAndMakesExactlyOneNewRead() = runTest {
        var calls = 0
        var clock = 1_000L
        val next = CompletableDeferred<Unit>()
        val owner = PlaceInformationDiscoveryOwner(this, { _, _ ->
            calls++
            if (calls == 2) next.await()
            clock += 125
            throw UnknownHostException("private")
        }, {}, { "same" }, nowMillis = { clock })
        owner.tap(0.0, 0.0, 17.0); runCurrent()
        assertEquals(125L, owner.state.diagnostic!!.elapsedMillis)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        owner.retry()
        assertNull(owner.state.diagnostic)
        assertTrue(owner.state.busy)
        runCurrent(); assertEquals(2, calls)
        owner.retry(); runCurrent(); assertEquals(2, calls)
        next.complete(Unit); runCurrent()
        assertEquals(NearbyCode.ND_DNS, owner.state.diagnostic!!.code)
        owner.close()
        assertNull(owner.state.diagnostic)
        assertFalse(owner.state.open)
    }

    @Test fun staleNonCancellableFailureCannotReplaceLatestDiagnosis() = runTest {
        val late = CompletableDeferred<Unit>()
        var reads = 0
        val owner = PlaceInformationDiscoveryOwner(this, { _, _ ->
            if (++reads == 1) withContext(NonCancellable) { late.await(); throw UnknownHostException("old private error") }
            throw ConnectException("new private error")
        }, {}, { "same" })
        owner.tap(0.0, 0.0, 17.0); runCurrent()
        owner.tap(1.0, 1.0, 17.0); runCurrent()
        val newest = owner.state
        assertEquals(NearbyCode.ND_CONNECTION, newest.diagnostic!!.code)
        late.complete(Unit); runCurrent()
        assertEquals(newest, owner.state)
    }

    @Test fun cancellationAndIdentityChangeDoNotBecomeDiagnosticErrors() = runTest {
        var identity = "first"
        val late = CompletableDeferred<Unit>()
        val owner = PlaceInformationDiscoveryOwner(this, { _, _ ->
            withContext(NonCancellable) { late.await(); throw UnknownHostException("private") }
        }, {}, { identity })
        owner.tap(0.0, 0.0, 17.0); runCurrent()
        identity = "second"
        late.complete(Unit); runCurrent()
        assertNull(owner.state.diagnostic)
        assertNull(owner.state.message)
        assertFalse(owner.state.retryable)
        val cancelled = PlaceInformationDiscoveryOwner(this, { _, _ -> throw CancellationException("private") }, {}, { null })
        cancelled.tap(0.0, 0.0, 17.0); runCurrent()
        assertNull(cancelled.state.diagnostic)
        assertFalse(cancelled.state.retryable)
        val pending = PlaceInformationDiscoveryOwner(this, { _, _ -> awaitCancellation() }, {}, { null })
        pending.tap(0.0, 0.0, 17.0); runCurrent(); pending.close(); runCurrent()
        assertNull(pending.state.diagnostic)
        assertFalse(pending.state.open)
    }

    @Test fun existingTwentySecondDeadlineGetsSafeCodeWithoutAutomaticRetry() = runTest {
        var calls = 0
        val owner = PlaceInformationDiscoveryOwner(this, { _, _ -> calls++; awaitCancellation() }, {}, { null },
            nowMillis = { testScheduler.currentTime })
        owner.tap(0.0, 0.0, 17.0); runCurrent()
        advanceTimeBy(19_999); runCurrent(); assertTrue(owner.state.busy)
        advanceTimeBy(1); runCurrent()
        assertEquals(NearbyCode.ND_DISCOVERY_TIMEOUT, owner.state.diagnostic!!.code)
        assertEquals(20_000L, owner.state.diagnostic!!.elapsedMillis)
        assertTrue(owner.state.retryable)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, calls)
    }
}
