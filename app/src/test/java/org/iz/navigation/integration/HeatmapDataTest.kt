package org.iz.navigation.integration

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapDataTest {
    private val now = 100_000L
    private fun journey(id: String, mode: Transport = Transport.CAR) = Journey(
        id = id, transport = mode, startedAt = 0L, endedAt = 50_000L,
    )
    private fun point(journeyId: String, latitude: Double = 41.0, longitude: Double = 29.0) = TrackPoint(
        journeyId = journeyId, latitude = latitude, longitude = longitude,
        accuracy = 5f, recordedAt = 10_000L,
    )

    @Test fun temporaryExpiredAndOrphanPointsNeverContribute() {
        val journeys = listOf(
            journey("kept"),
            journey("pending").copy(status = JourneyStatus.TEMPORARY, expiresAt = now + 1),
            journey("expired").copy(status = JourneyStatus.TEMPORARY, expiresAt = now),
            journey("stale").copy(expiresAt = now),
        )
        val data = buildHeatmapData(journeys, listOf("kept", "pending", "expired", "stale", "orphan").map { point(it) }, now = now)
        assertEquals(1, data.journeyCount)
        assertEquals(1, data.cells.single().journeyCount)
    }

    @Test fun allAndEveryTransportFilterSelectOnlyTheirOwnJourneys() {
        val modes = Transport.entries.filter { it != Transport.UNKNOWN }
        val journeys = modes.map { journey(it.name, it) }
        val points = modes.map { point(it.name) }
        assertEquals(modes.size, buildHeatmapData(journeys, points, now = now).journeyCount)
        modes.forEach { mode ->
            val data = buildHeatmapData(journeys, points, mode, now)
            assertEquals(mode.name, 1, data.journeyCount)
            assertEquals(mode.name, 1, data.cells.single().journeyCount)
        }
    }

    @Test fun stationaryGpsTicksAndReturningInOneJourneyCountOnlyOnce() {
        val points = List(500) { point("one").copy(recordedAt = it * 1_000L) } +
            point("one", latitude = 41.01) + point("one")
        val data = buildHeatmapData(listOf(journey("one")), points, now = now)
        assertEquals(1, data.journeyCount)
        assertEquals(listOf(1, 1), data.cells.map { it.journeyCount })
    }

    @Test fun runningFilterNeverReclassifiesHistoricalWalkingPoints() {
        val journeys = listOf(journey("historical-walk", Transport.WALK), journey("new-run", Transport.RUN))
        val points = listOf(point("historical-walk"), point("new-run", latitude = 41.01))
        val running = buildHeatmapData(journeys, points, Transport.RUN, now)
        assertEquals(1, running.journeyCount)
        assertEquals(1, running.cells.size)
        assertTrue(running.cells.single().latitude > 41.005)
        assertEquals(2, buildHeatmapData(journeys, points, now = now).journeyCount)
        assertEquals(Transport.WALK, journeys.first().transport)
    }

    @Test fun repeatVisitsAcrossJourneysAreStrongerAndNormalizedWithinTheSelection() {
        val journeys = listOf(journey("first"), journey("second"), journey("third", Transport.WALK))
        val points = listOf(point("first"), point("second"), point("third", latitude = 41.01))
        val all = buildHeatmapData(journeys, points, now = now)
        assertEquals(listOf(1, 2), all.cells.map { it.journeyCount }.sorted())
        assertEquals(listOf(0.5, 1.0), all.cells.map { it.intensity }.sorted())
        assertEquals(1.0, buildHeatmapData(journeys, points, Transport.WALK, now).cells.single().intensity, 0.0)
    }

    @Test fun unusableCoordinatesNeverBecomeHeatOrCountAsMappedJourneys() {
        val invalid = listOf(
            point("bad", latitude = Double.NaN), point("bad", longitude = 181.0),
            point("bad", latitude = 90.0), point("bad").copy(accuracy = 101f),
            point("bad").copy(speed = Float.NaN),
        )
        val data = buildHeatmapData(listOf(journey("good"), journey("bad")), invalid + point("good"), now = now)
        assertEquals(1, data.journeyCount)
        assertEquals(1, data.cells.size)
        assertTrue(data.cells.all { it.latitude.isFinite() && it.longitude.isFinite() && it.intensity.isFinite() })
    }

    @Test fun emptyAndUnmatchedFiltersHaveNoSyntheticSamples() {
        assertTrue(buildHeatmapData(emptyList(), emptyList(), now = now).cells.isEmpty())
        val unmatched = buildHeatmapData(listOf(journey("car")), listOf(point("car")), Transport.WALK, now)
        assertTrue(unmatched.cells.isEmpty())
        assertEquals(0, unmatched.journeyCount)
        assertTrue(buildHeatmapData(listOf(journey("car")), emptyList(), now = now).cells.isEmpty())
    }

    @Test fun inputOrderAndSamplingFrequencyDoNotMoveCellCentersOrChangeWeights() {
        val journeys = listOf(journey("a"), journey("b"))
        val points = listOf(point("a"), point("b", latitude = 41.000001), point("a", latitude = 41.01))
        val expected = buildHeatmapData(journeys, points, now = now)
        val reordered = buildHeatmapData(journeys.reversed(), (points + List(100) { points[1] }).reversed(), now = now)
        assertEquals(expected, reordered)
    }
}
