package org.iz.navigation.integration

import org.iz.navigation.data.GeoCoordinate
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs

class HeatmapDataTest {
    private val now = 100_000L
    private fun journey(id: String, mode: Transport = Transport.CAR) = Journey(
        id = id, transport = mode, startedAt = 0L, endedAt = 50_000L,
    )
    private fun point(id: String, lon: Double = 29.0, time: Long = 0L, lat: Double = 41.0) = TrackPoint(
        journeyId = id, latitude = lat, longitude = lon, accuracy = 5f, recordedAt = time,
    )
    private fun trace(id: String, reverse: Boolean = false) = if (reverse) listOf(
        point(id, 29.002, 0), point(id, 29.0, 10_000),
    ) else listOf(point(id, 29.0, 0), point(id, 29.002, 10_000))

    @Test fun connectedSparseSegmentCountsTheCellBetweenItsEndpoints() {
        val data = buildHeatmapData(listOf(journey("crossing"), journey("middle")),
            trace("crossing") + point("middle", 29.001), now = now)
        assertEquals(2, data.isolatedPoints.single().journeyCount)
        assertEquals(listOf(1, 2, 1), data.lines.map { it.journeyCount })
        assertEquals(GeoCoordinate(41.0, 29.0), data.lines.first().coordinates.first())
        assertEquals(GeoCoordinate(41.0, 29.002), data.lines.last().coordinates.last())
        val overlap = data.lines[1].coordinates
        assertTrue(overlap.first().longitude < 29.001)
        assertTrue(overlap.last().longitude > 29.001)
        assertTrue(overlap.last().longitude - overlap.first().longitude < 0.0004)
        data.lines.zipWithNext().forEach { (a, b) -> assertEquals(a.coordinates.last(), b.coordinates.first()) }
    }

    @Test fun repeatedSamplesReturnsAndReverseTraversalWithinOneJourneyCountOnce() {
        val samples = trace("one") + point("one", 29.0, 20_000) +
            List(500) { point("one", 29.0, 21_000L + it * 100L) }
        val data = buildHeatmapData(listOf(journey("one")), samples, now = now)
        assertEquals(1, data.journeyCount)
        assertTrue(data.isolatedPoints.isEmpty())
        assertEquals(1, data.lines.single().journeyCount)
        assertEquals(listOf(GeoCoordinate(41.0, 29.0), GeoCoordinate(41.0, 29.002), GeoCoordinate(41.0, 29.0)),
            data.lines.single().coordinates)
    }

    @Test fun separateJourneysIncludingReverseTraversalIncreaseEveryCrossedRegion() {
        val data = buildHeatmapData(listOf(journey("a"), journey("b")), trace("a") + trace("b", true), now = now)
        assertEquals(2, data.journeyCount)
        assertTrue(data.lines.isNotEmpty())
        assertTrue(data.lines.all { it.journeyCount == 2 })
        assertTrue(data.isolatedPoints.isEmpty())
    }

    @Test fun sparseAndDenseEquivalentPathsProduceIdenticalCoalescedGeometryAndCounts() {
        val journeys = listOf(journey("a"), journey("middle"))
        val sparse = trace("a") + point("middle", 29.001)
        val dense = (0..20).map { point("a", 29.0 + it * 0.0001, it * 500L) } + point("middle", 29.001)
        assertEquals(buildHeatmapData(journeys, sparse, now = now), buildHeatmapData(journeys, dense, now = now))
    }

    @Test fun diagonalSegmentsCountInteriorCellsWithoutInventingCellCenterGeometry() {
        val data = buildHeatmapData(listOf(journey("a"), journey("middle")), listOf(
            point("a", 29.0, 0, 41.0), point("a", 29.002, 10_000, 41.002), point("middle", 29.001, 0, 41.001),
        ), now = now)
        assertEquals(2, data.isolatedPoints.single().journeyCount)
        assertTrue(data.lines.any { it.journeyCount == 2 })
        data.lines.flatMap { it.coordinates }.forEach { coordinate ->
            assertEquals(coordinate.latitude - 41.0, coordinate.longitude - 29.0, 0.00000000001)
        }
    }

    @Test fun temporaryExpiredAndOrphanPointsNeverContribute() {
        val journeys = listOf(journey("kept"),
            journey("pending").copy(status = JourneyStatus.TEMPORARY, expiresAt = now + 1),
            journey("expired").copy(status = JourneyStatus.TEMPORARY, expiresAt = now), journey("stale").copy(expiresAt = now))
        val data = buildHeatmapData(journeys,
            listOf("kept", "pending", "expired", "stale", "orphan").map { point(it) }, now = now)
        assertEquals(1, data.journeyCount)
        assertEquals(1, data.isolatedPoints.single().journeyCount)
        assertTrue(data.lines.isEmpty())
    }

    @Test fun everyTransportFilterSelectsOnlyItsOwnJourneys() {
        val modes = Transport.entries
        val journeys = modes.map { journey(it.name, it) }
        val points = modes.map { point(it.name) }
        assertEquals(modes.size, buildHeatmapData(journeys, points, now = now).journeyCount)
        modes.forEach { mode ->
            val data = buildHeatmapData(journeys, points, mode, now)
            assertEquals(mode.name, 1, data.journeyCount)
            assertEquals(mode.name, 1, data.isolatedPoints.single().journeyCount)
        }
    }

    @Test fun runningFilterNeverReclassifiesHistoricalWalkingPoints() {
        val journeys = listOf(journey("walk", Transport.WALK), journey("run", Transport.RUN))
        val points = listOf(point("walk"), point("run", lat = 41.01))
        val running = buildHeatmapData(journeys, points, Transport.RUN, now)
        assertEquals(listOf(HeatmapTracePoint(GeoCoordinate(41.01, 29.0), 1)), running.isolatedPoints)
        assertEquals(1, running.journeyCount)
        assertEquals(2, buildHeatmapData(journeys, points, now = now).journeyCount)
        assertEquals(Transport.WALK, journeys.first().transport)
    }

    @Test fun countsAreAbsoluteAndUnrelatedJourneysDoNotReweightOtherPlaces() {
        val journeys = listOf(journey("a"), journey("b")) + (1..8).map { journey("far$it") }
        val data = buildHeatmapData(journeys, listOf(point("a"), point("b")) +
            (1..8).map { point("far$it", lat = 42.0) }, now = now)
        assertEquals(listOf(2, 8), data.isolatedPoints.map { it.journeyCount }.sorted())
    }

    @Test fun invalidFixIsAGeometryBreakEvenWhenValidNeighborsWouldConnect() {
        val invalid = listOf(point("a", 29.0001, 1_000).copy(latitude = Double.NaN),
            point("a", 29.0001, 1_000).copy(longitude = 181.0), point("a", 29.0001, 1_000).copy(accuracy = 101f),
            point("a", 29.0001, 1_000).copy(speed = Float.NaN),
            point("a", 29.0001, 1_000).copy(altitude = Double.POSITIVE_INFINITY), point("a", 29.0001, 1_000).copy(latitude = 89.0))
        invalid.forEach { bad ->
            val data = buildHeatmapData(listOf(journey("a")),
                listOf(point("a"), bad, point("a", 29.0002, 2_000)), now = now)
            assertTrue("Invalid fix must split, $bad", data.lines.isEmpty())
            assertEquals(2, data.isolatedPoints.size)
        }
    }

    @Test fun explicitBreakTimeGapAndImplausibleJumpNeverConnect() {
        val endings = listOf(point("a", 29.0002, 2_000).copy(breakBefore = true),
            point("a", 29.0002, 120_001), point("a", 29.01, 1_000), point("a", 29.0002, 0))
        endings.forEach { end ->
            val data = buildHeatmapData(listOf(journey("a")), listOf(point("a"), end), now = now)
            assertTrue(data.lines.isEmpty())
            assertEquals(2, data.isolatedPoints.size)
        }
    }

    @Test fun separateJourneysNeverJoinAndIsolatedFixesKeepActualCoordinates() {
        val data = buildHeatmapData(listOf(journey("a"), journey("b")),
            listOf(point("a"), point("b", 29.0001, 1_000)), now = now)
        assertTrue(data.lines.isEmpty())
        assertEquals(setOf(GeoCoordinate(41.0, 29.0), GeoCoordinate(41.0, 29.0001)), data.isolatedPoints.map { it.coordinate }.toSet())
    }

    @Test fun stationaryFixesBecomeOneSmallPointAndCountEachJourneyOnce() {
        val data = buildHeatmapData(listOf(journey("a"), journey("b")),
            List(500) { point("a", time = it * 100L) } + point("b"), now = now)
        assertTrue(data.lines.isEmpty())
        assertEquals(listOf(HeatmapTracePoint(GeoCoordinate(41.0, 29.0), 2)), data.isolatedPoints)
    }

    @Test fun unusableOnlyJourneysDoNotCountAsMapped() {
        val data = buildHeatmapData(listOf(journey("good"), journey("bad")), listOf(
            point("bad", lat = 90.0), point("bad", lat = Double.NaN), point("good", lat = 85.05)), now = now)
        assertEquals(1, data.journeyCount)
        assertEquals(listOf(HeatmapTracePoint(GeoCoordinate(85.05, 29.0), 1)), data.isolatedPoints)
    }

    @Test fun emptyMissingAndUnmatchedSelectionsHaveNoSyntheticGeometry() {
        assertEquals(HeatmapData(), buildHeatmapData(emptyList(), emptyList(), now = now))
        assertEquals(HeatmapData(), buildHeatmapData(listOf(journey("a")), emptyList(), now = now))
        assertEquals(HeatmapData(), buildHeatmapData(listOf(journey("a")), listOf(point("a")), Transport.WALK, now))
    }

    @Test fun inputOrderAndExactDuplicateSamplesDoNotChangeGeometry() {
        val journeys = listOf(journey("a"), journey("b"))
        val points = trace("a") + trace("b", true)
        assertEquals(buildHeatmapData(journeys, points, now = now), buildHeatmapData(journeys.reversed(),
            (points + points.map { it.copy(id = "duplicate${it.id}") }).reversed(), now = now))
    }

    @Test fun datelineCrossingSplitsAtMapEdgesAndStillCountsReverseJourneys() {
        val points = listOf(point("a", 179.999, 0, 0.0), point("a", -179.999, 10_000, 0.0),
            point("b", -179.999, 0, 0.0), point("b", 179.999, 10_000, 0.0))
        val data = buildHeatmapData(listOf(journey("a"), journey("b")), points, now = now)
        assertEquals(2, data.journeyCount)
        assertTrue(data.lines.isNotEmpty())
        assertTrue(data.lines.all { it.journeyCount == 2 })
        assertTrue(data.isolatedPoints.isEmpty())
        data.lines.forEach { line ->
            line.coordinates.zipWithNext().forEach { (a, b) -> assertTrue(abs(a.longitude - b.longitude) < 0.01) }
        }
        assertTrue(data.lines.flatMap { it.coordinates }.any { it.longitude == 180.0 })
        assertTrue(data.lines.flatMap { it.coordinates }.any { it.longitude == -180.0 })
    }

    @Test fun twoNamesForDatelineMeridianShareTheSameFrequency() {
        val data = buildHeatmapData(listOf(journey("a"), journey("b")),
            listOf(point("a", 180.0, lat = 0.0), point("b", -180.0, lat = 0.0)), now = now)
        assertEquals(listOf(HeatmapTracePoint(GeoCoordinate(0.0, -180.0), 2)), data.isolatedPoints)
    }

    @Test fun consecutiveDatelineAliasesAreAStationaryFixOrASingleMeridianTrace() {
        var checkpoints = 0
        val bounded: () -> Unit = { check(++checkpoints < 10_000) { "Meridian traversal must terminate" } }
        val stationary = buildHeatmapData(listOf(journey("a")),
            listOf(point("a", 180.0, 0, 0.0), point("a", -180.0, 1_000, 0.0)), now = now, checkActive = bounded)
        assertTrue(stationary.lines.isEmpty())
        assertEquals(listOf(HeatmapTracePoint(GeoCoordinate(0.0, -180.0), 1)), stationary.isolatedPoints)
        val moving = buildHeatmapData(listOf(journey("a")),
            listOf(point("a", 180.0, 0, 0.0), point("a", -180.0, 10_000, 0.001)), now = now, checkActive = bounded)
        assertEquals(listOf(HeatmapTraceLine(listOf(GeoCoordinate(0.0, -180.0), GeoCoordinate(0.001, -180.0)), 1)), moving.lines)
        assertTrue(moving.isolatedPoints.isEmpty())
    }

    @Test fun journeysAlongBothDatelineAliasesShareOneMeridianFrequency() {
        val data = buildHeatmapData(listOf(journey("a"), journey("b")), listOf(
            point("a", 180.0, 0, 0.0), point("a", 180.0, 10_000, 0.001),
            point("b", -180.0, 0, 0.0), point("b", -180.0, 10_000, 0.001)), now = now)
        assertEquals(listOf(HeatmapTraceLine(listOf(GeoCoordinate(0.0, -180.0), GeoCoordinate(0.001, -180.0)), 2)), data.lines)
        assertTrue(data.isolatedPoints.isEmpty())
    }

    @Test fun arrivingAtAndLeavingTheDatelineKeepsShortGeometry() {
        var checkpoints = 0
        val data = buildHeatmapData(listOf(journey("a"), journey("b")), listOf(
            point("a", 179.999, 0, 0.0), point("a", 180.0, 10_000, 0.0),
            point("b", -180.0, 0, 0.0), point("b", 179.999, 10_000, 0.0)), now = now,
            checkActive = { check(++checkpoints < 10_000) { "Dateline arrival must terminate" } })
        assertEquals(2, data.journeyCount)
        assertTrue(data.lines.isNotEmpty())
        assertTrue(data.lines.all { it.journeyCount == 2 })
        assertTrue(data.isolatedPoints.isEmpty())
        assertTrue(data.lines.flatMap { it.coordinates }.all { it.latitude == 0.0 && it.longitude in 179.999..180.0 })
    }

    @Test fun cancellationCanStopLongProcessing() {
        var calls = 0
        var cancelled = false
        try {
            buildHeatmapData(listOf(journey("a")), List(5_000) { point("a", time = it * 1_000L) }, now = now,
                checkActive = { if (++calls == 4) throw CancellationException("cancelled") })
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals(4, calls)
    }

    @Test fun denseDatasetKeepsDistinctJourneyCountsAndCoalescesStraightGeometry() {
        val journeys = (0 until 50).map { journey("journey$it") }
        val points = journeys.flatMap { journey -> (0 until 2_000).map { point(journey.id, 29.0 + it * 0.00001, it * 1_000L) } }
        val start = System.nanoTime()
        val data = buildHeatmapData(journeys, points, now = now)
        println("Heatmap 100000 fixes / 50 journeys: ${(System.nanoTime() - start) / 1_000_000} ms; ${data.lines.size} lines")
        assertEquals(50, data.journeyCount)
        assertTrue(data.lines.isNotEmpty())
        assertTrue(data.lines.all { it.journeyCount == 50 && it.coordinates.size == 2 })
        assertTrue(data.isolatedPoints.isEmpty())
    }
}
