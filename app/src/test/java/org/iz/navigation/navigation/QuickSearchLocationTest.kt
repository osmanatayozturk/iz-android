package org.iz.navigation.navigation

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class QuickSearchLocationTest {
    private val now = 100_000L
    private val fine = NavigationFix(WeatherCoordinate(41.012345, 29.012345), now - 60_000, 8f)
    private val coarse = NavigationFix(WeatherCoordinate(41.1, 29.1), now, 1_000f)

    @Test fun freshVisibleFixUsesFinePermissionAndNeedsNoProviderCall() = runTest {
        val result = findQuickSearchCoordinate({ true }, { true }, { fine },
            { error("cache") }, { error("provider") }, { now })
        assertEquals(fine.coordinate, result)
    }

    @Test fun coarsePermissionUsesOnlyACoarseCacheAndMissingPermissionDoesNothing() = runTest {
        val result = findQuickSearchCoordinate({ true }, { false }, { fine }, {
            assertFalse(it)
            coarse
        }, { error("provider") }, { now })
        assertEquals(coarse.coordinate, result)
        assertNull(findQuickSearchCoordinate({ false }, { false }, { error("visible") },
            { error("cache") }, { error("provider") }, { now }))
    }

    @Test fun staleAndFutureFixesFallBackToAtMostFiveSecondsOfApproximation() = runTest {
        val result = findQuickSearchCoordinate({ true }, { true },
            { fine.copy(recordedAt = now - 60_001) }, { coarse.copy(recordedAt = now + 1) },
            { delay(5_001); coarse }, { now + testScheduler.currentTime })
        assertNull(result)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test fun approximateFixSucceedsOnlyWhilePermissionIsStillPresent() = runTest {
        var allowed = true
        assertEquals(coarse.coordinate, findQuickSearchCoordinate({ allowed }, { false }, { null },
            { null }, { coarse }, { now }))
        assertNull(findQuickSearchCoordinate({ allowed }, { false }, { null }, { null },
            { allowed = false; coarse }, { now }))
    }

    @Test fun cancellingTheSubmissionCancelsLocationWithoutReturningFallback() = runTest {
        var cancelled = false
        val job = launch {
            findQuickSearchCoordinate({ true }, { false }, { null }, { null }, {
                try { awaitCancellation() } finally { cancelled = true }
            }, { now })
            fail("cancelled location must not return a fallback")
        }
        testScheduler.runCurrent()
        job.cancelAndJoin()
        assertTrue(cancelled)
    }
}
