package org.iz.navigation.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyActivityRulesTest {
    private val zone = ZoneId.of("Europe/Istanbul")
    private val midnight = Instant.parse("2026-09-11T21:00:00Z").toEpochMilli()
    private fun point(t: Long, lon: Double, broken: Boolean = false) = TrackPoint(journeyId = "j", latitude = 41.0, longitude = lon, recordedAt = t, accuracy = 3f, breakBefore = broken)

    @Test fun dayUsesPhoneZoneAndDstBoundary() {
        val day = DailyActivityRules.day(midnight + 1, zone)
        assertEquals("2026-09-12", day.localDate)
        assertEquals(midnight, day.startAt)
        val dst = DailyActivityRules.day(Instant.parse("2026-03-29T12:00:00Z").toEpochMilli(), ZoneId.of("Europe/Berlin"))
        assertEquals(23 * 60 * 60 * 1000L, dst.endAt - dst.startAt)
    }
    @Test fun validMidnightSegmentSplitsDistanceAndElapsedIncludesPauses() {
        val trip = Journey(id = "j", transport = Transport.WALK, startedAt = midnight - 60_000, endedAt = midnight + 180_000)
        val points = listOf(point(midnight - 30_000, 29.0), point(midnight + 30_000, 29.001))
        val result = DailyActivityRules.totals(listOf(trip), points, DailyActivityRules.day(midnight, zone), midnight + 180_000)
        assertEquals(DiaryRules.distanceMeters(points) / 2, result.walkingRunning.distanceMeters, 0.001)
        assertEquals(180_000, result.walkingRunning.elapsedMillis)
        assertEquals(1, result.walkingRunning.journeyCount)
        assertNull(result.cycling)
    }
    @Test fun invalidGapAcrossMidnightNeverBecomesValidAfterClipping() {
        val trip = Journey(id = "j", transport = Transport.BICYCLE, startedAt = midnight - 300_000, endedAt = midnight + 30_000)
        val result = DailyActivityRules.totals(listOf(trip), listOf(point(midnight - 300_000, 29.0), point(midnight + 30_000, 29.001)), DailyActivityRules.day(midnight, zone), midnight + 30_000)
        assertEquals(0.0, result.cycling!!.distanceMeters, 0.0)
    }
    @Test fun onlyConfirmedSupportedModesAndCurrentDayCount() {
        val trips = listOf(Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER, Transport.UNKNOWN).mapIndexed { i, mode -> Journey(id = "$i", transport = mode, startedAt = midnight, endedAt = midnight + 1000) } +
            Journey(transport = Transport.BICYCLE, status = JourneyStatus.TEMPORARY, startedAt = midnight) +
            Journey(transport = Transport.BICYCLE, startedAt = midnight - 2000, endedAt = midnight)
        val result = DailyActivityRules.totals(trips, emptyList(), DailyActivityRules.day(midnight, zone), midnight + 1000)
        assertEquals(3, result.vehicle.journeyCount)
        assertEquals(3000, result.vehicle.elapsedMillis)
        assertNull(result.cycling)
    }
    @Test fun breakAndInvalidPointDoNotJoinNeighbours() {
        val trip = Journey(id = "j", transport = Transport.RUN, startedAt = midnight, endedAt = midnight + 60_000)
        val points = listOf(point(midnight, 29.0), point(midnight + 20_000, 29.001).copy(accuracy = 200f), point(midnight + 40_000, 29.002), point(midnight + 60_000, 29.003, true))
        assertEquals(0.0, DailyActivityRules.totals(listOf(trip), points, DailyActivityRules.day(midnight, zone), midnight + 60_000).walkingRunning.distanceMeters, 0.0)
    }
}
