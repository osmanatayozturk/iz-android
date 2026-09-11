package com.atay.iz.tracking

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class DetectionRegistrationCoordinatorTest {
    @Test(timeout = 5_000) fun transientFailureRetainsTheRequestedPreferenceAndCanRecover() = runBlocking {
        val harness = Harness()
        val coordinator = harness.coordinator(this)
        val first = CompletableDeferred<String?>()
        coordinator.enable { first.complete(it) }
        harness.gateway.awaitRequestCount(1)
        harness.gateway.requests.single().completeExceptionally(IllegalStateException("temporary"))
        assertNotNull(first.await())
        assertTrue(harness.store.requestedEnabled)
        assertEquals(DetectionRegistrationState.RETRY_PENDING, harness.store.registrationState)
        assertEquals(1, harness.retries.schedules)
        val next = CompletableDeferred<String?>()
        coordinator.restore { next.complete(it) }
        harness.gateway.awaitRequestCount(2)
        harness.gateway.requests.last().complete(Unit)
        assertNull(next.await())
        assertEquals(DetectionRegistrationState.READY, harness.store.registrationState)
        assertEquals(123_000L, harness.store.lastRegistrationAt)
        assertNull(harness.store.registrationError)
    }

    @Test(timeout = 5_000) fun disablingWhileRegistrationIsPendingCannotBeUndoneByItsLateSuccess() = runBlocking {
        val harness = Harness()
        val coordinator = harness.coordinator(this)
        val completed = CompletableDeferred<String?>()
        coordinator.enable { completed.complete(it) }
        yield()
        coordinator.disable()
        assertFalse(harness.store.requestedEnabled)
        harness.gateway.requests.single().complete(Unit)
        completed.await()
        yield()
        assertFalse(harness.store.requestedEnabled)
        assertEquals(DetectionRegistrationState.OFF, harness.store.registrationState)
        assertEquals(1, harness.gateway.removals)
        assertEquals(0, harness.retries.schedules)
    }

    @Test(timeout = 5_000) fun reEnableWaitsForAnOlderRemovalBeforeRegisteringAgain() = runBlocking {
        val harness = Harness()
        val coordinator = harness.coordinator(this)
        val initial = CompletableDeferred<String?>()
        coordinator.enable { initial.complete(it) }
        yield()
        harness.gateway.requests.single().complete(Unit)
        initial.await()
        harness.gateway.removalGate = CompletableDeferred()
        coordinator.disable()
        yield()
        val next = CompletableDeferred<String?>()
        coordinator.enable { next.complete(it) }
        yield()
        assertEquals(1, harness.gateway.requests.size)
        harness.gateway.removalGate!!.complete(Unit)
        harness.gateway.awaitRequestCount(2)
        harness.gateway.requests.last().complete(Unit)
        assertNull(next.await())
        assertTrue(harness.store.requestedEnabled)
        assertEquals(DetectionRegistrationState.READY, harness.store.registrationState)
        assertEquals(2, harness.gateway.requests.size)
    }

    @Test(timeout = 5_000) fun missingPermissionKeepsIntentAndRestoresAfterPermissionReturn() = runBlocking {
        val harness = Harness().also { it.permissionIssue = "Arka plan konumu gerekli" }
        val coordinator = harness.coordinator(this)
        val initial = CompletableDeferred<String?>()
        coordinator.enable { initial.complete(it) }
        assertEquals(harness.permissionIssue, initial.await())
        assertTrue(harness.store.requestedEnabled)
        assertEquals(DetectionRegistrationState.PERMISSION_REQUIRED, harness.store.registrationState)
        assertTrue(harness.gateway.requests.isEmpty())
        assertEquals(0, harness.retries.schedules)
        harness.permissionIssue = null
        val recovered = CompletableDeferred<String?>()
        coordinator.restore { recovered.complete(it) }
        yield()
        harness.gateway.requests.single().complete(Unit)
        assertNull(recovered.await())
        assertEquals(DetectionRegistrationState.READY, harness.store.registrationState)
    }

    @Test(timeout = 5_000) fun persistedReadyIsNotProofThatANewProcessHasRegistered() = runBlocking {
        val harness = Harness().also {
            it.store.requestedEnabled = true
            it.store.registrationState = DetectionRegistrationState.READY
            it.store.lastRegistrationAt = 122_999L
        }
        val coordinator = harness.coordinator(this)
        val result = CompletableDeferred<String?>()
        coordinator.restore { result.complete(it) }
        yield()
        assertEquals(1, harness.gateway.requests.size)
        harness.gateway.requests.single().complete(Unit)
        assertNull(result.await())
        val repeated = CompletableDeferred<String?>()
        coordinator.restore { repeated.complete(it) }
        assertNull(repeated.await())
        assertEquals(1, harness.gateway.requests.size)
        harness.permissionIssue = "Hareket izni kaldırıldı"
        val denied = CompletableDeferred<String?>()
        coordinator.restore { denied.complete(it) }
        assertNotNull(denied.await())
        assertEquals(DetectionRegistrationState.PERMISSION_REQUIRED, harness.store.registrationState)
    }

    @Test(timeout = 5_000) fun disabledPreferenceNeverRegistersDuringRestoration() = runBlocking {
        val harness = Harness()
        val result = CompletableDeferred<String?>()
        harness.coordinator(this).restore { result.complete(it) }
        assertNull(result.await())
        assertTrue(harness.gateway.requests.isEmpty())
        assertFalse(harness.store.requestedEnabled)
    }

    @Test(timeout = 5_000) fun aPermissionRevokedDuringRegistrationCannotProduceReadyState() = runBlocking {
        val harness = Harness()
        val result = CompletableDeferred<String?>()
        harness.coordinator(this).enable { result.complete(it) }
        harness.gateway.awaitRequestCount(1)
        harness.permissionIssue = "Hareket izni kaldırıldı"
        harness.gateway.requests.single().complete(Unit)
        assertEquals(harness.permissionIssue, result.await())
        assertTrue(harness.store.requestedEnabled)
        assertEquals(DetectionRegistrationState.PERMISSION_REQUIRED, harness.store.registrationState)
        assertEquals(0, harness.retries.schedules)
    }

    private class Harness {
        val store = Store()
        val gateway = Gateway()
        val retries = Retries()
        var permissionIssue: String? = null
        fun coordinator(scope: kotlinx.coroutines.CoroutineScope) = DetectionRegistrationCoordinator(
            store, gateway, retries, scope, { permissionIssue }, { 123_000L })
    }

    private class Store : DetectionRegistrationStore {
        override var requestedEnabled = false
        override var registrationState = DetectionRegistrationState.OFF
        override var registrationError: String? = null
        override var lastRegistrationAt = 0L
        override var lastRegistrationAttemptAt = 0L
    }

    private class Gateway : DetectionRegistrationGateway {
        val requests = mutableListOf<CompletableDeferred<Unit>>()
        var removals = 0
        var removalGate: CompletableDeferred<Unit>? = null
        suspend fun awaitRequestCount(count: Int) = withTimeout(2_000) { while (requests.size < count) yield() }
        override suspend fun register() {
            val request = CompletableDeferred<Unit>()
            requests += request
            request.await()
        }
        override suspend fun unregister() { removals++; removalGate?.await() }
    }

    private class Retries : DetectionRetryScheduler {
        var schedules = 0
        var cancellations = 0
        override fun schedule() { schedules++ }
        override fun cancel() { cancellations++ }
    }
}
