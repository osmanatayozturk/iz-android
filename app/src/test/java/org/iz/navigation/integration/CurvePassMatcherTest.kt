package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.RecordedCurveDetail
import org.iz.navigation.data.RecordedCurveDirection
import org.iz.navigation.data.RecordedCurveEndpoint
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.lean.LeanCurveResult
import org.iz.navigation.lean.LeanJourneyReport
import org.iz.navigation.lean.LeanMount
import org.junit.Assert.*
import org.junit.Test

class CurvePassMatcherTest {
    private val shape = listOf(.0 to .0, .00015 to .0, .00028 to .00004, .00039 to .00012,
        .00045 to .00025, .00045 to .00040)

    private fun points(id: String, at: Long, speed: Float?, coordinates: List<Pair<Double, Double>> = shape) =
        coordinates.mapIndexed { index, (lat, lon) -> TrackPoint("$id-$index", id, 40 + lat, 29 + lon,
            at + index * 1_000, 3f, speed) }

    private fun curve(points: List<TrackPoint>, direction: RecordedCurveDirection = RecordedCurveDirection.RIGHT): RecordedCurveDetail {
        fun endpoint(point: TrackPoint) = RecordedCurveEndpoint(point.id, point.recordedAt, point.latitude, point.longitude)
        return RecordedCurveDetail(points.first().id, points.first().journeyId, "a".repeat(64), 1,
            endpoint(points.first()), endpoint(points.last()), direction, points.size,
            points.zipWithNext().sumOf { (a, b) -> DiaryRules.metersBetween(a, b) }, null, null)
    }

    private fun trace(points: List<TrackPoint>, direction: RecordedCurveDirection = RecordedCurveDirection.RIGHT) =
        requireNotNull(CurvePassMatcher.trace(curve(points, direction), points))

    @Test fun sameDirectionDifferentSamplingAndSpeedUseCommonMeters() {
        val reference = trace(points("current", 20_000, 10f))
        val old = trace(points("earlier", 2_000, 7f, shape.filterIndexed { index, _ -> index != 1 }))
        val comparison = requireNotNull(CurvePassMatcher.compare(reference, old))
        assertEquals(0.0, comparison.samples.first().distanceMeters, .001)
        assertEquals(reference.meters.last(), comparison.samples.last().distanceMeters, .001)
        assertEquals(36.0, comparison.samples[3].selectedSpeedKmh!!, .001)
        assertEquals(25.2, comparison.samples[3].previousSpeedKmh!!, .001)
        assertEquals(5_000L, comparison.selected.durationMillis)
        assertEquals(4_000L, comparison.previous.durationMillis)
        assertNull(comparison.previous.lean)
    }

    @Test fun rejectsReverseParallelAndAnotherBranchWithTheSameEndpoints() {
        val reference = trace(points("current", 20_000, 10f))
        val reverse = trace(points("reverse", 2_000, 10f, shape.reversed()), RecordedCurveDirection.LEFT)
        val parallel = trace(points("parallel", 2_000, 10f, shape.map { it.first to it.second + .00007 }))
        val branch = trace(points("branch", 2_000, 10f, shape.mapIndexed { index, point ->
            if (index in 2..3) point.first + .00015 to point.second else point
        }))
        assertFalse(CurvePassMatcher.matches(reference, reverse))
        assertFalse(CurvePassMatcher.matches(reference, parallel))
        assertFalse(CurvePassMatcher.matches(reference, branch))
    }

    @Test fun breaksPoorAccuracyAndGpsGapsAreBarriers() {
        val base = points("recorded", 20_000, null)
        val broken = base.mapIndexed { index, point -> if (index == 2) point.copy(breakBefore = true) else point }
        val poor = base.mapIndexed { index, point -> if (index == 2) point.copy(accuracy = 23f) else point }
        val gapped = base.mapIndexed { index, point -> if (index >= 2) point.copy(recordedAt = point.recordedAt + 20_000) else point }
        assertNull(CurvePassMatcher.trace(curve(broken), broken))
        assertNull(CurvePassMatcher.trace(curve(poor), poor))
        assertNull(CurvePassMatcher.trace(curve(gapped), gapped))
    }

    @Test fun leanRequiresUniqueCurveTimeAndLocationAndNeverReplacesMissingSpeed() {
        val current = trace(points("current", 20_000, 10f))
        val previous = trace(points("earlier", 2_000, null))
        val anchor = current.points[2]
        val sample = LeanCurveResult(0, 1_000_000, null, 23.0, anchor.latitude, anchor.longitude, 20_000, 25_000)
        fun report(vararg bends: LeanCurveResult) = LeanJourneyReport(false, null, 23.0, 5_000, 5_000,
            emptySet(), bends.toList(), setOf("synthetic"))
        val handles = report().copy(handlebar = report(sample))
        val wrongRoad = report(sample.copy(latitude = anchor.latitude + .002, startUtcMillis = 2_000L, endUtcMillis = 7_000L))
        val comparison = requireNotNull(CurvePassMatcher.compare(current, previous, handles, wrongRoad))
        assertEquals(CurvePassLean(LeanMount.HANDLEBAR, null, 23.0), comparison.selected.lean)
        assertNull(comparison.previous.lean)
        assertNull(comparison.previous.sampledMaxSpeedKmh)
        assertTrue(comparison.samples.all { it.previousSpeedKmh == null })
        assertNull(CurvePassMatcher.compare(current, previous, report(sample, sample))?.selected?.lean)
        val estimated = handles.copy(handlebar = report(sample.copy(timingEstimated = true)))
        assertNull(requireNotNull(CurvePassMatcher.compare(current, previous, estimated)).selected.lean)
    }
}
