package org.iz.navigation.gpx

import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class TrackFollowEngineTest {
    private fun p(lon: Double, lat: Double = 41.0) = WeatherCoordinate(lat, lon)
    private fun fix(lon: Double, time: Long, lat: Double = 41.0, accuracy: Float = 4f) = NavigationFix(p(lon, lat), time, accuracy, 10f)
    private fun line() = TrackFollowEngine(listOf(p(29.0), p(29.01)))

    @Test fun selectedSegmentDirectionAndStartNeverBridgeOtherSegments() {
        val a = TrackSegment("A", listOf(p(29.0), p(29.001), p(29.002)))
        val b = TrackSegment("B", listOf(p(30.0), p(30.01)))
        val track = ImportedTrack(name = "İz", segments = listOf(a, b))
        assertEquals(a.points, selectedTrackPoints(track, TrackFollowSelection()))
        assertEquals(listOf(p(29.001), p(29.0)), selectedTrackPoints(track, TrackFollowSelection(reversed = true, startPointIndex = 1)))
        assertEquals(b.points, selectedTrackPoints(track, TrackFollowSelection(segmentIndex = 1)))
        assertEquals(p(29.0), track.segments.first().points.first())
        assertThrows(IllegalArgumentException::class.java) { selectedTrackPoints(track, TrackFollowSelection(startPointIndex = 2)) }
    }
    @Test fun sparseLineProgressHasOnlyDistanceAndCompletesAfterRepeatedFreshFixes() {
        val engine = line()
        assertTrue(engine.initial.remainingMeters > 800)
        assertEquals(TrackFollowStatus.TRACKING, engine.update(fix(29.0, 1000), 1000)!!.status)
        for (i in 1..10) {
            val progress = engine.update(fix(29.0 + i / 1000.0, 1000L + i * 10000), 1000L + i * 10000)!!
            assertEquals(i * engine.initial.remainingMeters / 10.0, progress.travelledMeters, 1.0)
        }
        assertNotEquals(TrackFollowStatus.SEGMENT_COMPLETE, engine.update(fix(29.01, 112000), 112000)!!.status)
        assertEquals(TrackFollowStatus.SEGMENT_COMPLETE, engine.update(fix(29.01, 115000), 115000)!!.status)
    }
    @Test fun staleInaccurateFutureAndOutOfOrderFixesCannotAdvance() {
        val engine = line()
        val first = engine.update(fix(29.0, 10000), 10000)!!
        assertNull(engine.update(fix(29.005, 11000), 22000))
        assertNull(engine.update(fix(29.005, 11001, accuracy = 51f), 11001))
        assertNull(engine.update(fix(29.005, 13000), 12000))
        assertNull(engine.update(fix(29.005, 9000), 12000))
        assertEquals(first.travelledMeters, engine.update(fix(29.0, 12000), 12000)!!.travelledMeters, .01)
    }
    @Test fun distantInitialPositionRequiresExplicitStartInsteadOfSkipping() {
        val state = line().update(fix(29.009, 1000), 1000)!!
        assertEquals(0.0, state.travelledMeters, .01)
        assertEquals(TrackFollowStatus.NEEDS_START_POINT, state.status)
        assertTrue(state.distanceFromLineMeters!! < 1)
    }
    @Test fun offLineFreezesProgressAndNeverInventsConnector() {
        val engine = line()
        engine.update(fix(29.0, 1000), 1000)
        val progress = engine.update(fix(29.002, 11000, lat = 41.01), 11000)!!
        assertEquals(TrackFollowStatus.OFF_TRACK, progress.status)
        assertEquals(0.0, progress.travelledMeters, .01)
        assertTrue(progress.distanceFromLineMeters!! > 1000)
    }
    @Test fun loopAtStartCannotJumpToItsEnd() {
        val engine = TrackFollowEngine(listOf(p(29.0), p(29.01), p(29.0)))
        repeat(4) { i ->
            val state = engine.update(fix(29.0, 1000L + i * 1000), 1000L + i * 1000)!!
            assertEquals(0.0, state.travelledMeters, .01)
            assertNotEquals(TrackFollowStatus.SEGMENT_COMPLETE, state.status)
        }
    }
    @Test fun ambiguousReconnectionNeedsSelectionAndRemainsFrozen() {
        val engine = TrackFollowEngine(listOf(p(29.0), p(29.01), p(29.0)))
        engine.update(fix(29.0, 1000), 1000)
        val state = engine.update(fix(29.0, 40000), 40000)!!
        assertEquals(TrackFollowStatus.NEEDS_START_POINT, state.status)
        assertEquals(TrackFollowStatus.NEEDS_START_POINT, engine.update(fix(29.001, 50000), 50000)!!.status)
        assertEquals(0.0, state.travelledMeters, .01)
    }
    @Test fun fractionalStartUsesTappedPointOnSparseEdgeAndRejectsEndpointOnly() {
        val track = ImportedTrack(name = "Seyrek", segments = listOf(TrackSegment("A", listOf(p(29.0), p(29.01)))))
        val selected = selectedTrackPoints(track, TrackFollowSelection(startFraction = 0.5))
        assertEquals(29.005, selected.first().longitude, .000001)
        assertEquals(p(29.01), selected.last())
        assertThrows(IllegalArgumentException::class.java) { selectedTrackPoints(track, TrackFollowSelection(startFraction = 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { selectedTrackPoints(track, TrackFollowSelection(startFraction = Double.NaN)) }
    }
    @Test fun datelineCrossingStaysOnShortSegment() {
        val engine = TrackFollowEngine(listOf(p(179.999), p(-179.999)))
        assertTrue(engine.initial.remainingMeters in 160.0..180.0)
        engine.update(fix(179.999, 1000), 1000)
        val middle = engine.update(fix(180.0, 11000), 11000)!!
        assertEquals(engine.initial.remainingMeters / 2, middle.travelledMeters, 1.0)
        assertTrue(middle.distanceFromLineMeters!! < 1)
    }
}
