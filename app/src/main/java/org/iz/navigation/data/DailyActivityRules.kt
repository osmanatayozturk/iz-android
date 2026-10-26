package org.iz.navigation.data

import org.iz.navigation.wearprotocol.WearDailyJourneyTotal
import java.time.Instant
import java.time.ZoneId

data class DailyActivityDay(val localDate: String, val zoneId: String, val startAt: Long, val endAt: Long)
data class DailyJourneyTotals(val vehicle: WearDailyJourneyTotal, val walkingRunning: WearDailyJourneyTotal, val cycling: WearDailyJourneyTotal?)

object DailyActivityRules {
    fun day(now: Long, zone: ZoneId = ZoneId.systemDefault()): DailyActivityDay {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return DailyActivityDay(date.toString(), zone.id, date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }

    /** Clip only after checking the original GPS segment. Pauses and gaps remain elapsed time. */
    fun totals(journeys: List<Journey>, points: List<TrackPoint>, day: DailyActivityDay, now: Long, checkActive: () -> Unit = {}): DailyJourneyTotals {
        var processed = 0
        fun checkpoint() { if (++processed % 256 == 0) checkActive() }
        checkActive()
        val grouped = points.groupBy { checkpoint(); it.journeyId }
        val totals = mutableMapOf<Int, WearDailyJourneyTotal>()
        for (journey in journeys) {
            checkpoint()
            if (journey.status != JourneyStatus.CONFIRMED) continue
            val group = when (journey.transport) {
                Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER -> 0
                Transport.WALK, Transport.RUN -> 1
                Transport.BICYCLE -> 2
                else -> continue
            }
            val end = minOf(journey.endedAt ?: now, now)
            val start = maxOf(day.startAt, journey.startedAt)
            val clippedEnd = minOf(day.endAt, end)
            if (clippedEnd <= start) continue
            val route = grouped[journey.id].orEmpty().filter { checkpoint(); it.recordedAt in journey.startedAt..end }
                .sortedWith { a, b -> checkpoint(); a.recordedAt.compareTo(b.recordedAt) }
            val meters = route.asSequence().zipWithNext().sumOf { (a, b) ->
                checkpoint()
                if (!DiaryRules.connects(a, b)) 0.0 else {
                    val overlap = (minOf(b.recordedAt, clippedEnd) - maxOf(a.recordedAt, start)).coerceAtLeast(0)
                    DiaryRules.distanceMeters(listOf(a, b)) * overlap.toDouble() / (b.recordedAt - a.recordedAt)
                }
            }
            val old = totals[group] ?: WearDailyJourneyTotal()
            totals[group] = WearDailyJourneyTotal(old.distanceMeters + meters, old.elapsedMillis + clippedEnd - start, old.journeyCount + 1)
        }
        return DailyJourneyTotals(totals[0] ?: WearDailyJourneyTotal(), totals[1] ?: WearDailyJourneyTotal(), totals[2])
    }
}
