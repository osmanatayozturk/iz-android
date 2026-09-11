package org.iz.navigation.health

import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.data.DailyActivityRules
import org.iz.navigation.wearprotocol.*
import org.iz.navigation.wearprotocol.WearDailyHealthStatus.*
import java.nio.file.Files
import java.time.ZoneId

class DailyActivityAccessPolicyTest {
    private val now = 1_800_000_000_000L
    private val day = DailyActivityRules.day(now, ZoneId.of("Europe/Istanbul"))
    private fun measured() = WearDailySummary(day.localDate, day.zoneId, day.startAt, day.endAt, now, now - 10_000,
        WearDailyJourneyTotal(), WearDailyJourneyTotal(), null, 4200, 2000.0, 900_000,
        AVAILABLE, AVAILABLE, AVAILABLE)

    @Test fun transientAccessFailureHidesValuesWithoutErasingCacheAndRecoveryKeepsOriginalCheck() {
        val directory = Files.createTempDirectory("daily-access-test").toFile()
        try {
            val cache = DailyActivityCache(java.io.File(directory, "daily.bin"))
            val original = measured()
            cache.write(original)
            val unknown = DailyActivityAccessPolicy.project(cache.read(day, now)!!, DailyAccessKnowledge.UNKNOWN)
            assertNull(unknown.visible.samsungSteps)
            assertNull(unknown.visible.samsungExerciseMeters)
            assertEquals(UNAVAILABLE, unknown.visible.stepsStatus)
            assertNull("A transient binder failure must not request a cache replacement", unknown.cacheReplacement)
            unknown.cacheReplacement?.let(cache::write)
            val recovered = DailyActivityAccessPolicy.project(cache.read(day, now + 1000)!!,
                DailyAccessKnowledge.AVAILABLE, stepsAllowed = true, exerciseAllowed = true, distanceAllowed = true)
            assertEquals(4200L, recovered.visible.samsungSteps)
            assertEquals(original.healthCheckedAt, recovered.visible.healthCheckedAt)
            assertEquals(original.checkedAt, recovered.visible.checkedAt)
            assertNull(recovered.cacheReplacement)
        } finally { directory.deleteRecursively() }
    }
    @Test fun confirmedRevocationPersistsOnlyRevokedMetricRemoval() {
        val result = DailyActivityAccessPolicy.project(measured(), DailyAccessKnowledge.AVAILABLE,
            stepsAllowed = false, exerciseAllowed = true, distanceAllowed = true)
        assertNull(result.visible.samsungSteps)
        assertEquals(PERMISSION_REQUIRED, result.visible.stepsStatus)
        assertEquals(2000.0, result.visible.samsungExerciseMeters!!, 0.0)
        assertEquals(result.visible, result.cacheReplacement)
        assertEquals(measured().healthCheckedAt, result.visible.healthCheckedAt)
    }
    @Test fun confirmedDisabledAndUnavailableClearAllHealthAndTimestamp() {
        for (state in listOf(DailyAccessKnowledge.DISABLED, DailyAccessKnowledge.UNAVAILABLE)) {
            val result = DailyActivityAccessPolicy.project(measured(), state)
            assertNull(result.visible.samsungSteps)
            assertNull(result.visible.samsungExerciseMillis)
            assertNull(result.visible.samsungExerciseMeters)
            assertNull(result.visible.healthCheckedAt)
            assertEquals(result.visible, result.cacheReplacement)
        }
    }
}
