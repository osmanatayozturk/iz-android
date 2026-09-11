package com.atay.iz.health

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class HealthSyncPolicyTest {
    @Test fun readWindowClipsToPermissionHistoryAndCurrentTime() {
        assertEquals(HealthReadWindow(100, 150), healthReadWindow(50, null, 100, 150))
        assertEquals(HealthReadWindow(120, 140), healthReadWindow(120, 140, 100, 150))
        assertNull(healthReadWindow(20, 90, 100, 150))
        assertNull(healthReadWindow(160, null, 100, 150))
    }

    @Test fun paginationStopsOnNullOrEmptyAndKeepsEveryPage() = runBlocking {
        val tokens = mutableListOf<String?>()
        val result = collectHealthPages<Int> { token ->
            tokens += token
            if (token == null) HealthPage(listOf(1, 2), "next") else HealthPage(listOf(3), "")
        }
        assertEquals(listOf(1, 2, 3), result)
        assertEquals(listOf(null, "next"), tokens)
    }

    @Test fun interruptedPageNeverReturnsPartialSnapshot() = runBlocking {
        var calls = 0
        try {
            collectHealthPages<Int> {
                if (calls++ == 0) HealthPage(listOf(1), "next") else error("offline")
            }
            fail("Partial import must not succeed")
        } catch (e: IllegalStateException) {
            assertEquals("offline", e.message)
        }
    }

    @Test fun repeatedPageTokenIsRejected() = runBlocking {
        try {
            collectHealthPages<Int> { HealthPage(listOf(1), "same") }
            fail("Loop must stop")
        } catch (_: IllegalStateException) { }
    }

    @Test fun changesKeepLatestUpdateOrDeletionBeforeAtomicApply() = runBlocking {
        val result = collectHealthChanges("a") { token ->
            when (token) {
                "a" -> HealthChangePage(listOf(HealthSourceChange("x", listOf(10)), HealthSourceChange("y", listOf(20))), "b", true)
                else -> HealthChangePage(listOf(HealthSourceChange<Int>("x", emptyList()), HealthSourceChange("y", listOf(21))), "c", false)
            }
        }
        assertEquals("c", result.nextToken)
        assertEquals(setOf("x", "y"), result.changedSourceIds)
        assertEquals(listOf(21), result.samples)
    }

    @Test fun expiredChangeTokenDiscardsAccumulatedPages() = runBlocking {
        try {
            collectHealthChanges<Int>("a") { token ->
                if (token == "a") HealthChangePage(listOf(HealthSourceChange("x", listOf(10))), "b", true)
                else HealthChangePage(emptyList(), "c", false, expired = true)
            }
            fail("Expired token requires complete re-read")
        } catch (_: HealthTokenExpired) { }
    }

    @Test fun cancellationPropagates() = runBlocking {
        try {
            collectHealthPages<Int> { throw kotlinx.coroutines.CancellationException("cancel") }
            fail("Cancellation swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }
}
