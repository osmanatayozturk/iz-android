package org.iz.navigation.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PhoneNavigationWorkTest {
    @Test fun newerDestinationRejectsUncooperativeOldReadAndCanStartNewRead() = runBlocking {
        val oldResult = CompletableDeferred<Unit>()
        var published = ""
        var busy = false
        val work = PhoneNavigationWork(this, { busy = it }, { throw it })
        work.read { current ->
            withContext(NonCancellable) { oldResult.await() }
            if (current()) published = "old"
        }
        yield()
        assertTrue(busy)
        work.newTarget()
        assertFalse(busy)
        work.read { current -> if (current()) published = "new" }
        yield()
        oldResult.complete(Unit)
        yield()
        assertEquals("new", published)
        assertFalse(busy)
    }

    @Test fun destinationDuringRecordingStartDoesNotCancelMutationOrClearItsBusyState() = runBlocking {
        val start = CompletableDeferred<Unit>()
        var completed = false
        var busy = false
        val work = PhoneNavigationWork(this, { busy = it }, { throw it })
        work.mutate { start.await(); completed = true }
        yield()
        work.newTarget()
        assertTrue(busy)
        assertFalse(completed)
        work.read { fail("Do not overlap new preview with recording activation") }
        start.complete(Unit)
        yield()
        assertTrue(completed)
        assertFalse(busy)
    }
}
