package com.atay.iz.car

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestCarRequestTest {
    @Test fun newerCoordinateWinsEvenWhenOldPlannerIgnoresCancellation() = runTest {
        val requests = LatestCarRequest(this)
        val gate = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val finished = mutableListOf<String>()
        requests.submit(work = { withContext(NonCancellable) { gate.await(); "A" } },
            publish = published::add, failed = { throw it }, finished = { finished.add("A") })
        runCurrent()
        requests.submit(work = { "B" }, publish = published::add, failed = { throw it }, finished = { finished.add("B") })
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("B"), published)
        assertEquals(listOf("B"), finished)
    }

    @Test fun textTargetCancelsPendingCoordinateWithoutLatePreviewOrBusyReset() = runTest {
        val requests = LatestCarRequest(this)
        val gate = CompletableDeferred<Unit>()
        var preview: String? = null
        var oldFinished = false
        requests.submit(work = { withContext(NonCancellable) { gate.await(); "Old coordinate" } },
            publish = { preview = it }, failed = { throw it }, finished = { oldFinished = true })
        runCurrent()
        requests.cancel() // CarHomeScreen.openTarget does this before pushing a text search screen.
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(preview)
        assertFalse(oldFinished)
    }

    @Test fun replacementSuppressesOldFailure() = runTest {
        val requests = LatestCarRequest(this)
        val gate = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        requests.submit<String>(work = { withContext(NonCancellable) { gate.await(); error("old") } },
            publish = {}, failed = { errors.add(it.message.orEmpty()) }, finished = {})
        runCurrent()
        requests.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(errors.isEmpty())
    }
}
