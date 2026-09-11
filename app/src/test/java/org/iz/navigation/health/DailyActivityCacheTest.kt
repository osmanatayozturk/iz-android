package org.iz.navigation.health

import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.wearprotocol.*
import org.iz.navigation.data.DailyActivityRules
import java.nio.file.Files
import java.time.ZoneId

class DailyActivityCacheTest {
    @Test fun restartPreservesOriginalHealthCheckAndRejectsNextDayOrZone() {
        val dir = Files.createTempDirectory("daily-cache-test").toFile()
        try {
            val file = java.io.File(dir, "daily.bin")
            val now = 1_800_000_000_000L
            val day = DailyActivityRules.day(now, ZoneId.of("Europe/Istanbul"))
            val daily = WearDailySummary(day.localDate, day.zoneId, day.startAt, day.endAt, now, now - 10_000,
                WearDailyJourneyTotal(), WearDailyJourneyTotal(), null, 0, null, null,
                WearDailyHealthStatus.AVAILABLE, WearDailyHealthStatus.UNAVAILABLE, WearDailyHealthStatus.UNAVAILABLE)
            DailyActivityCache(file).write(daily)
            val restored = DailyActivityCache(file).read(day, now + 1000)
            assertEquals(now - 10_000, restored!!.healthCheckedAt)
            assertEquals(0L, restored.samsungSteps)
            assertNull(DailyActivityCache(file).read(DailyActivityRules.day(day.endAt, ZoneId.of(day.zoneId)), day.endAt))
            assertNull(DailyActivityCache(file).read(DailyActivityRules.day(now, ZoneId.of("UTC")), now))
            file.writeBytes(byteArrayOf(1, 2, 3))
            assertNull(DailyActivityCache(file).read(day, now))
        } finally { dir.deleteRecursively() }
    }
}
