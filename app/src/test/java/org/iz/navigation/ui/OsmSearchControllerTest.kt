package org.iz.navigation.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class OsmSearchControllerTest {
    private val buildingA = SelectedOsmPlace("Örnek bina", 40.0, 30.0, OsmRef(OsmType.WAY, 101))
    private val buildingB = SelectedOsmPlace("Örnek bina", 40.001, 30.001, OsmRef(OsmType.WAY, 102))

    @Test fun typingDoesNotSearchAndExplicitSearchPreservesAllBuildingObjects() = runTest {
        val queries = mutableListOf<String>()
        val controller = OsmSearchController(backgroundScope, "") { query ->
            queries += query; listOf(buildingA, buildingB)
        }
        controller.edit("ilk"); controller.edit("  bina  "); runCurrent()
        assertTrue(queries.isEmpty())
        controller.submit(); runCurrent()
        assertEquals(listOf("bina"), queries)
        assertEquals(2, controller.state.value.results.size)
        assertSame(buildingA, controller.state.value.results[0])
        assertSame(buildingB, controller.state.value.results[1])
        assertTrue(controller.state.value.searched)
        assertFalse(controller.state.value.busy)
    }

    @Test fun editingCompletedSearchClearsResultsAndSearchedState() = runTest {
        val controller = OsmSearchController(backgroundScope, "ilk") { listOf(buildingA) }
        controller.submit(); runCurrent()
        controller.edit("yeni")
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched)
        assertNull(controller.state.value.error)
    }

    @Test fun editingEmptySearchClearsNotFoundState() = runTest {
        val controller = OsmSearchController(backgroundScope, "ilk") { emptyList() }
        controller.submit(); runCurrent()
        assertTrue(controller.state.value.searched)
        controller.edit("yeni")
        assertFalse(controller.state.value.searched)
    }

    @Test fun editingFailedSearchClearsError() = runTest {
        val controller = OsmSearchController(backgroundScope, "ilk") { throw IllegalStateException("Örnek hata") }
        controller.submit(); runCurrent()
        assertEquals("Örnek hata", controller.state.value.error)
        controller.edit("yeni")
        assertNull(controller.state.value.error)
    }

    @Test fun editCancelsInFlightSearchAndRejectsLateResultWithoutAnotherSubmit() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("yeni")
        val cancelled = pending.calls[0].job.isCancelled
        val edited = controller.state.value
        pending.success(0, buildingA); runCurrent()
        assertTrue(cancelled)
        assertFalse(edited.busy)
        assertEquals("yeni", controller.state.value.query)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched)
    }

    @Test fun lateOldResultCannotFinishNewSearchEvenWhenQueryReturnsToSameText() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("başka"); controller.edit("ilk"); controller.submit(); runCurrent()
        pending.success(0, buildingA); runCurrent()
        val duringNew = controller.state.value
        // The old implementation blocks the new submit, but the test still releases every request.
        if (pending.calls.size > 1) { pending.success(1, buildingB); runCurrent() }
        assertEquals(2, pending.calls.size)
        assertTrue(duringNew.busy)
        assertTrue(duringNew.results.isEmpty())
        assertSame(buildingB, controller.state.value.results.single())
    }

    @Test fun lateOldErrorCannotOverwriteNewSuccessfulSearch() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("yeni"); controller.submit(); runCurrent()
        if (pending.calls.size > 1) { pending.success(1, buildingB); runCurrent() }
        pending.failure(0); runCurrent()
        assertEquals(2, pending.calls.size)
        assertNull(controller.state.value.error)
        assertSame(buildingB, controller.state.value.results.single())
        assertFalse(controller.state.value.busy)
    }

    @Test fun lateOldErrorAndFinallyCannotResetNewSearchProgress() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("yeni"); controller.submit(); runCurrent()
        pending.failure(0); runCurrent()
        val duringNew = controller.state.value
        if (pending.calls.size > 1) { pending.success(1, buildingB); runCurrent() }
        assertEquals(2, pending.calls.size)
        assertTrue(duringNew.busy)
        assertNull(duringNew.error)
    }

    @Test fun closingDialogRejectsEvenNonCooperativeCompletion() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.close()
        val cancelled = pending.calls[0].job.isCancelled
        pending.success(0, buildingA); runCurrent()
        assertTrue(cancelled)
        assertTrue(controller.state.value.results.isEmpty())
        assertFalse(controller.state.value.searched)
        assertFalse(controller.state.value.busy)
    }

    @Test fun unchangedTextAndRepeatedSubmitDoNotCancelOrDuplicateCurrentSearch() = runTest {
        val pending = PendingSearch()
        val controller = OsmSearchController(backgroundScope, "ilk", pending::search)
        controller.submit(); runCurrent()
        controller.edit("ilk"); controller.submit(); runCurrent()
        val cancelled = pending.calls[0].job.isCancelled
        pending.success(0, buildingA); runCurrent()
        assertFalse(cancelled)
        assertEquals(1, pending.calls.size)
        assertSame(buildingA, controller.state.value.results.single())
    }

    @Test fun blankQueryDoesNotSearchAndCancellationDoesNotBecomeAnError() = runTest {
        var calls = 0
        val controller = OsmSearchController(backgroundScope, "  ") { calls++; throw CancellationException() }
        controller.submit(); runCurrent()
        assertEquals(0, calls)
        controller.edit("ilk"); controller.submit(); runCurrent()
        assertEquals(1, calls)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.busy)
        assertFalse(controller.state.value.searched)
    }

    // A controlled external boundary that intentionally ignores cancellation, as late I/O can do.
    private class PendingSearch {
        data class Call(val query: String, val job: Job, val continuation: Continuation<List<SelectedOsmPlace>>)
        val calls = mutableListOf<Call>()
        suspend fun search(query: String): List<SelectedOsmPlace> {
            val job = currentCoroutineContext().job
            return suspendCoroutine { calls += Call(query, job, it) }
        }
        fun success(index: Int, place: SelectedOsmPlace) = calls[index].continuation.resume(listOf(place))
        fun failure(index: Int) = calls[index].continuation.resumeWithException(IllegalStateException("Eski arama hatası"))
    }
}
