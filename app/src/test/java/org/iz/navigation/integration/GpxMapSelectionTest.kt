package org.iz.navigation.integration

import org.iz.navigation.gpx.*
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class GpxMapSelectionTest {
    private fun point(lat: Double, lon: Double) = WeatherCoordinate(lat, lon)
    private fun track(vararg values: WeatherCoordinate) = ImportedTrack(name = "Sentetik iz",
        segments = listOf(TrackSegment("Bölüm", values.toList())))

    @Test fun sparseEdgeProjectsStartInsteadOfSnappingToFarVertex() {
        val result = gpxStartCandidates(track(point(0.0, 0.0), point(0.0, .02)),
            TrackFollowSelection(), point(.001, .005)).single()
        assertEquals(0, result.selection.startPointIndex)
        assertEquals(.25, result.selection.startFraction, .000001)
        assertEquals(.005, result.coordinate.longitude, .000001)
        assertTrue(result.alongMeters in 550.0..560.0)
    }

    @Test fun reverseUsesIndexAndFractionInDisplayedDirection() {
        val result = gpxStartCandidates(track(point(0.0, 0.0), point(0.0, .02)),
            TrackFollowSelection(reversed = true), point(0.0, .005)).single()
        assertTrue(result.selection.reversed)
        assertEquals(.75, result.selection.startFraction, .000001)
    }

    @Test fun datelineEdgeUsesShortLongitudeArc() {
        val result = gpxStartCandidates(track(point(0.0, 179.8), point(0.0, -179.8)),
            TrackFollowSelection(), point(.001, 179.9)).single()
        assertEquals(.25, result.selection.startFraction, .000001)
        assertEquals(179.9, result.coordinate.longitude, .000001)
        assertTrue(result.alongMeters in 11_000.0..11_200.0)
    }

    @Test fun crossingOffersBothDistantTraversalBranches() {
        val result = gpxStartCandidates(track(point(-.01, -.01), point(.01, .01),
            point(.01, -.01), point(-.01, .01)), TrackFollowSelection(), point(0.0, 0.0))
        assertEquals(listOf(0, 2), result.map { it.selection.startPointIndex })
        assertTrue(result.last().alongMeters - result.first().alongMeters > 1000.0)
    }

    @Test fun repeatedLoopStartOffersLaterPassWithoutChoosingItSilently() {
        val result = gpxStartCandidates(track(point(0.0, 0.0), point(0.0, .01),
            point(.01, .01), point(.01, 0.0), point(0.0, 0.0), point(0.0, -.01)),
            TrackFollowSelection(), point(0.0, 0.0))
        assertEquals(2, result.size)
        assertEquals(0.0, result.first().alongMeters, .01)
        assertTrue(result.last().alongMeters > 4000.0)
    }

    @Test fun neighboringEdgesAtOneVertexAreOneChoice() {
        val result = gpxStartCandidates(track(point(0.0, 0.0), point(0.0, .01), point(.01, .01)),
            TrackFollowSelection(), point(0.0, .01))
        assertEquals(1, result.size)
    }

    @Test fun selectedSegmentNeverConnectsOrSelectsAnotherSegment() {
        val values = ImportedTrack(name = "İki bölüm", segments = listOf(
            TrackSegment("Bir", listOf(point(0.0, 0.0), point(0.0, .01))),
            TrackSegment("İki", listOf(point(1.0, 1.0), point(1.0, 1.02)))))
        val result = gpxStartCandidates(values, TrackFollowSelection(segmentIndex = 1), point(1.0, 1.01)).single()
        assertEquals(1, result.selection.segmentIndex)
        assertEquals(1.0, result.coordinate.latitude, .000001)
    }

    @Test fun finalEndpointCannotCreateEmptyRemainingTrack() {
        assertTrue(gpxStartCandidates(track(point(0.0, 0.0), point(0.0, .01)),
            TrackFollowSelection(), point(0.0, .01)).isEmpty())
    }

    @Test fun degenerateEdgeDoesNotHideValidProjection() {
        val result = gpxStartCandidates(track(point(0.0, 0.0), point(0.0, 0.0), point(0.0, .01)),
            TrackFollowSelection(), point(0.0, .005)).single()
        assertEquals(1, result.selection.startPointIndex)
        assertEquals(.5, result.selection.startFraction, .000001)
    }

    @Test fun ordinaryDisplayLineKeepsGeometryAndDoesNotCreateConnections() {
        val points = listOf(point(41.0, 29.0), point(41.1, 29.1))
        assertEquals(listOf(points), gpxDisplayLines(points))
        assertTrue(gpxDisplayLines(emptyList()).isEmpty())
    }

    @Test fun dateLineDisplaySplitsAtWorldBoundaryInsteadOfDrawingAcrossMap() {
        val result = gpxDisplayLines(listOf(point(1.0, 179.0), point(3.0, -179.0)))
        assertEquals(listOf(listOf(point(1.0, 179.0), point(2.0, 180.0)),
            listOf(point(2.0, -180.0), point(3.0, -179.0))), result)
        assertEquals(listOf(listOf(point(1.0, 180.0), point(3.0, 180.0))),
            gpxDisplayLines(listOf(point(1.0, 180.0), point(3.0, -180.0))))
    }
}
