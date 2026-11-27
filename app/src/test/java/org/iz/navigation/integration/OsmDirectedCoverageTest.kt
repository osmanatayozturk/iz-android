package org.iz.navigation.integration

import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Collections

class OsmDirectedCoverageTest {
    @Test fun onlyFirstOfTenSameDirectionPassesContributesDespiteGpsShiftAndDifferentSampling() {
        val coverage = OsmDirectedCoverage(emptyList())
        val first = prepare((0..240 step 20).map { fix(it) })
        assertSame(first, coverage.filter(first))
        repeat(9) { pass ->
            val shifted = (2..242 step 15).map { fix(it, north = if (pass % 2 == 0) 7 else -7) }
            assertNull(coverage.filter(prepare(shifted, day = pass + 1)))
        }
        val reverse = prepare((240 downTo 0 step 20).map { fix(it, north = 5) }, day = 10)
        assertSame(reverse, coverage.filter(reverse))
        assertNull(coverage.filter(prepare((240 downTo 0 step 16).map { fix(it, north = 3) }, day = 11)))
    }

    @Test fun partialOverlapProducesTwoSegmentsWithOnlyRetainedOriginalFixesAndTimes() {
        val trace = prepare((0..400 step 20).map { fix(it) })
        val result = requireNotNull(OsmDirectedCoverage(listOf(history(fix(100), fix(300)))).filter(trace))
        val original = trace.segments.single()
        val expected = listOf(original.take(5), original.takeLast(5))
        assertEquals(expected, result.segments)
        assertEquals(expected.flatten().size, result.pointCount)
        assertEquals(160.0, result.distanceMeters, 1e-6)
        assertEquals(trace.measuredDistanceMeters, result.measuredDistanceMeters, 0.0)
        assertTrue(result.removedStartMeters >= 1000.0)
        assertTrue(result.removedEndMeters >= 1000.0)
        assertEquals(2, "<trkseg>".toRegex().findAll(result.gpxXml).count())
        for (discarded in original - expected.flatten().toSet()) {
            assertFalse(result.gpxXml.contains(Instant.ofEpochMilli(discarded.recordedAt).toString()))
        }
        assertFalse(trace.sha256 == result.sha256)
    }

    @Test fun alternatingLateralJitterCannotBecomeAnotherPassButTheReverseCan() {
        val straight = (-120..120 step 10).map { fix(it) }
        val jittered = straight.mapIndexed { i, point -> point.copy(north = if (i % 2 == 0) 4 else -4) }
        val coverage = OsmDirectedCoverage(listOf(straight.map { coordinate(it) }))
        assertNull(coverage.filter(prepare(jittered, day = 1)))
        val reverse = prepare(jittered.reversed(), day = 2)
        assertSame(reverse, coverage.filter(reverse))
        assertNull(coverage.filter(prepare(straight.reversed(), day = 3)))
        assertNull(OsmDirectedCoverage(listOf(jittered.map { coordinate(it) })).filter(prepare(straight)))
    }

    @Test fun shallowCrossingRemainsWholeAndExtendedParallelLosesOnlyItsOverlappingPortion() {
        val known = listOf(history(fix(-220), fix(220)))
        val shallowCrossing = prepare((-180..180 step 10).map { fix(it, it / 5) })
        assertSame(shallowCrossing, OsmDirectedCoverage(known).filter(shallowCrossing))

        val prolongedParallel = prepare((-220..360 step 10).map { fix(it, 7) })
        val retained = requireNotNull(OsmDirectedCoverage(known).filter(prolongedParallel))
        assertEquals(prolongedParallel.segments.single().takeLast(14), retained.segments.single())
    }

    @Test fun overlappingJourneysGiveTheSharedSectionToItsEarliestPassageAndBreakTiesById() {
        val ownSection = listOf(fix(0, 500), fix(20, 500), fix(40, 500))
        val shared = (0..100 step 20).map { fix(it) }
        val startedFirst = prepare(ownSection + shared.first().copy(delayMs = 7_000, breakBefore = true) +
            shared.drop(1), journeyId = "a-started-first")
        val passedFirst = prepare(shared, startOffsetMs = 10_000, journeyId = "b-passed-first")
        val tied = prepare(shared, startOffsetMs = 10_000, journeyId = "c-tied")
        assertTrue(startedFirst.segments.first().first().recordedAt < passedFirst.segments.first().first().recordedAt)
        assertTrue(startedFirst.segments.last().first().recordedAt > passedFirst.segments.first().first().recordedAt)
        val journeyOrder = OsmDirectedCoverage(emptyList())
        assertSame(startedFirst, journeyOrder.filter(startedFirst))
        assertNull(journeyOrder.filter(passedFirst))

        val chronological = OsmDirectedCoverage(emptyList())
        val result = chronological.filterChronologically(listOf(tied, startedFirst, passedFirst))
        assertEquals(listOf(startedFirst.journeyId, passedFirst.journeyId), result.map { it.journeyId })
        assertEquals(listOf(startedFirst.segments.first()), result.first().segments)
        assertSame(passedFirst, result.last())
        assertNull(chronological.filter(passedFirst))
    }

    @Test fun crossingAndDistinctParallelRemainButCloseSameDirectionParallelIsWithheld() {
        val known = listOf(history(fix(0), fix(400)))
        val crossing = prepare((-100..100 step 20).map { fix(200, north = it) })
        val close = prepare((0..400 step 20).map { fix(it, north = 8) })
        val far = prepare((0..400 step 20).map { fix(it, north = 35) })
        val uncertain = prepare((0..400 step 20).map { fix(it, north = 20, accuracy = 23f) })
        assertSame(crossing, OsmDirectedCoverage(known).filter(crossing))
        assertNull(OsmDirectedCoverage(known).filter(close))
        assertSame(far, OsmDirectedCoverage(known).filter(far))
        assertNull(OsmDirectedCoverage(known).filter(uncertain))
    }

    @Test fun aTurnoffRetainsTheUnsharedLegAfterTheCut() {
        val trace = prepare((0..100 step 20).map { fix(it) } + (20..100 step 20).map { fix(100, it) })
        val result = requireNotNull(OsmDirectedCoverage(listOf(history(fix(0), fix(240)))).filter(trace))
        assertEquals(trace.segments.single().takeLast(5), result.segments.single())
        assertEquals(80.0, result.distanceMeters, 1e-6)
    }

    @Test fun repeatedLoopWithinOneArtifactContributesOnlyFirstPass() {
        val loop = (0..100 step 20).map { fix(it) } +
            (20..100 step 20).map { fix(100, it) } +
            (80 downTo 0 step 20).map { fix(it, 100) } +
            (80 downTo 0 step 20).map { fix(0, it) }
        val trace = prepare(loop + loop.drop(1))
        val coverage = OsmDirectedCoverage(emptyList())
        val result = requireNotNull(coverage.filter(trace))
        // The shared last/first fix is withheld because it starts a repeated leg.
        assertEquals(trace.segments.single().take(loop.size - 1), result.segments.single())
        assertNull(coverage.filter(prepare(loop, day = 1)))
    }

    @Test fun poorAccuracyAndSparseLegsSplitWithoutBridgingOrIndexingRejectedGeometry() {
        val trace = prepare(listOf(fix(0), fix(20), fix(40, accuracy = 52f),
            fix(60), fix(80), fix(220), fix(240), fix(260, delayMs = 45_000), fix(280)))
        val coverage = OsmDirectedCoverage(emptyList())
        val result = requireNotNull(coverage.filter(trace))
        val original = trace.segments.single()
        assertEquals(listOf(original.take(2), original.subList(3, 5), original.subList(5, 7),
            original.takeLast(2)), result.segments)
        assertEquals(80.0, result.distanceMeters, 1e-6)
        val missingSection = prepare(listOf(fix(95), fix(115), fix(135)))
        assertSame(missingSection, coverage.filter(missingSection))
    }

    @Test fun historyAndCandidateSegmentBoundariesAreNeverJoined() {
        val history = listOf(history(fix(0), fix(80)), history(fix(240), fix(320)))
        val uncovered = prepare((100..220 step 20).map { fix(it) })
        assertSame(uncovered, OsmDirectedCoverage(history).filter(uncovered))
        val split = prepare(listOf(fix(0), fix(20), fix(40), fix(220, breakBefore = true), fix(240)))
        assertEquals(listOf(3, 2), split.segments.map { it.size })
        assertSame(split, OsmDirectedCoverage(emptyList()).filter(split))
    }

    @Test fun matchingUsesMetersAcrossAntimeridian() {
        val first = prepare((0..240 step 20).map { fix(it) }, originLongitude = 179.999)
        val sameDirection = prepare((0..240 step 20).map { fix(it, north = 7) }, originLongitude = 179.999)
        val reverse = prepare((240 downTo 0 step 20).map { fix(it) }, originLongitude = 179.999)
        assertTrue(first.segments.single().any { it.longitude < -179.0 })
        val history = first.segments.map { segment -> segment.map { OsmDirectedPoint(it.latitude, it.longitude) } }
        val coverage = OsmDirectedCoverage(history)
        assertNull(coverage.filter(sameDirection))
        assertNotNull(coverage.filter(reverse))
    }

    @Test fun malformedOrExcessiveHistoryFailsExplicitly() {
        assertThrows(IllegalArgumentException::class.java) {
            OsmDirectedCoverage(listOf(listOf(OsmDirectedPoint(Double.NaN, 0.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OsmDirectedCoverage(listOf(listOf(OsmDirectedPoint(0.0, 0.0), OsmDirectedPoint(0.0, 1.0))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OsmDirectedCoverage(Collections.nCopies(50_001, emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OsmDirectedCoverage(listOf(Collections.nCopies(300_001, OsmDirectedPoint(0.0, 0.0))))
        }
    }

    @Test fun aComparisonLimitFailureDoesNotCommitEarlierPartsOfTheCandidate() {
        val coverage = OsmDirectedCoverage(Collections.nCopies(10_000, history(fix(0), fix(80))))
        val newSection = listOf(fix(0, 500), fix(20, 500), fix(40, 500))
        val oscillation = listOf(fix(60), fix(40), fix(20), fix(0), fix(20), fix(40), fix(60), fix(80))
        val dense = listOf(fix(80, breakBefore = true)) + List(250) { oscillation }.flatten()
        assertThrows(IllegalArgumentException::class.java) {
            coverage.filter(prepare(newSection + dense))
        }
        val standalone = prepare(newSection, day = 1)
        assertSame(standalone, coverage.filter(standalone))
    }

    private data class Fix(
        val east: Int,
        val north: Int,
        val accuracy: Float,
        val delayMs: Long,
        val breakBefore: Boolean,
    )

    private fun fix(east: Int, north: Int = 0, accuracy: Float = 5f, delayMs: Long = 2_000,
                    breakBefore: Boolean = false) = Fix(east, north, accuracy, delayMs, breakBefore)

    private fun coordinate(fix: Fix, originLongitude: Double = 0.0): OsmDirectedPoint {
        val longitude = originLongitude + Math.toDegrees(fix.east / 6_371_000.0)
        return OsmDirectedPoint(Math.toDegrees(fix.north / 6_371_000.0),
            if (longitude > 180.0) longitude - 360.0 else longitude)
    }

    private fun history(vararg fixes: Fix) = fixes.map { coordinate(it) }

    private fun prepare(
        fixes: List<Fix>,
        day: Int = 0,
        originLongitude: Double = 0.0,
        startOffsetMs: Long = 0,
        journeyId: String = "synthetic-$day",
    ): PreparedOsmGpxTrace {
        val start = 1_700_000_000_000L + day * 86_400_000L + startOffsetMs
        val journey = Journey(id = journeyId, startedAt = start, endedAt = start + 120_000_000L)
        val prefix = fixes.first().copy(north = fixes.first().north - 1250, delayMs = 0)
        val suffix = fixes.last().copy(north = fixes.last().north + 1250, delayMs = 20_000, breakBefore = false)
        var time = start
        val points = (listOf(prefix, fixes.first().copy(delayMs = 20_000)) + fixes.drop(1) + suffix)
            .mapIndexed { index, fix ->
                time += fix.delayMs
                val point = coordinate(fix, originLongitude)
                TrackPoint(id = "synthetic-$index", journeyId = journey.id, latitude = point.latitude,
                    longitude = point.longitude, recordedAt = time, accuracy = fix.accuracy,
                    breakBefore = fix.breakBefore)
            }
        return (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
    }
}
