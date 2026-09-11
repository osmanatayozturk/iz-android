package com.atay.iz.data

import org.junit.Assert.*
import org.junit.Test

class WatchHealthRulesTest {
    private val journey = Journey(id = "trip", transport = Transport.RUN, startedAt = 1000, endedAt = 10_000)
    private val session = WatchHealthSession("s", "trip", "watch", "Watch8", 1500)
    private val sample = WatchHealthSample("s", 1, "trip", HealthMetric.HEART_RATE_BPM, 2000, 2000, 80.0)
    @Test fun directDataPreservesItsSourceAndNeverAcceptsEstimatedCalories() {
        WatchHealthRules.validateSnapshot(listOf(journey), listOf(session), listOf(sample))
        assertThrows(IllegalArgumentException::class.java) { WatchHealthRules.validateSnapshot(listOf(journey), listOf(session),
            listOf(sample.copy(metric = HealthMetric.TOTAL_CALORIES_KCAL, endAt = 3000))) }
        assertThrows(IllegalArgumentException::class.java) { WatchHealthRules.validateSnapshot(listOf(journey), listOf(session),
            listOf(sample.copy(sessionId = "other"))) }
    }
    @Test fun summaryNeverCountsRetransmissionsOrMeasurementsOutsideTrip() {
        val end = sample.copy(sequence = 2, startAt = 11_000, endAt = 11_000)
        val summary = WatchHealthRules.summarize(journey, listOf(sample, sample, end))
        assertEquals(1, summary.heartRateSampleCount)
        assertEquals(80.0, summary.latestHeartRateBpm!!, 0.0)
        assertNull(summary.watchSteps)
        assertNull(summary.totalCaloriesKcal)
    }
}
