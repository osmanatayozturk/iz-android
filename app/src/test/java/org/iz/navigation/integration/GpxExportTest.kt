package org.iz.navigation.integration

import org.iz.navigation.data.*
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class GpxExportTest {
    private val journey = Journey(id = "route", title = "PRIVATE HOME", note = "PRIVATE NOTE", startedAt = 0, endedAt = 900_000)
    private fun point(time: Long, latitude: Double = 41.0, breakBefore: Boolean = false) =
        TrackPoint(journeyId = journey.id, latitude = latitude, longitude = 29.0, recordedAt = time, accuracy = 5f, breakBefore = breakBefore)

    @Test fun exportsMeasuredCoordinatesUtcTimeAndFiniteAltitudeWithoutDiaryText() {
        val first = point(1_000).copy(altitude = 12.5)
        val second = point(2_000, 41.0001)
        val xml = buildGpx(journey, listOf(second, first), includeTimestamps = true)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("gpx", document.documentElement.nodeName)
        assertEquals("http://www.topografix.com/GPX/1/1", document.documentElement.getAttribute("xmlns"))
        assertEquals(2, document.getElementsByTagName("trkpt").length)
        assertEquals("41.0", document.getElementsByTagName("trkpt").item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("1970-01-01T00:00:01Z", document.getElementsByTagName("time").item(0).textContent)
        assertEquals("12.5", document.getElementsByTagName("ele").item(0).textContent)
        assertFalse(xml.contains("PRIVATE"))
        assertFalse(xml.contains(journey.id))
    }

    @Test fun invalidMeasurementsExplicitBreaksAndLongGapsNeverCreateConnectingSegments() {
        val points = listOf(point(1_000), point(2_000, Double.NaN), point(3_000), point(4_000, breakBefore = true), point(200_000))
        val segments = gpxSegments(journey, points)
        assertEquals(listOf(listOf(1_000L), listOf(3_000L), listOf(4_000L), listOf(200_000L)), segments.map { it.map { p -> p.recordedAt } })
        assertEquals(4, DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(buildGpx(journey, points).byteInputStream()).getElementsByTagName("trkseg").length)
    }

    @Test fun trimmingRemovesUsableEndpointPointsWithoutInterpolatingAcrossBreaks() {
        val points = listOf(point(1_000), point(2_000, Double.NaN), point(3_000), point(4_000, breakBefore = true), point(5_000), point(6_000))
        val segments = gpxSegments(journey, points, startTrim = 1, endTrim = 1)
        assertEquals(listOf(listOf(3_000L), listOf(4_000L, 5_000L)), segments.map { it.map { p -> p.recordedAt } })
    }

    @Test fun excludesForeignAndOutsideJourneyMeasurements() {
        val points = listOf(point(-1), point(1_000), point(2_000).copy(journeyId = "other"), point(3_000), point(900_001))
        assertEquals(listOf(1_000L, 3_000L), gpxSegments(journey, points).flatten().map { it.recordedAt })
    }

    @Test fun cannotExportActiveTemporaryOverTrimmedOrInvalidRoutes() {
        val points = listOf(point(1_000), point(2_000))
        assertThrows(IllegalArgumentException::class.java) { buildGpx(journey.copy(endedAt = null), points) }
        assertThrows(IllegalArgumentException::class.java) { buildGpx(journey.copy(status = JourneyStatus.TEMPORARY), points) }
        assertThrows(IllegalArgumentException::class.java) { buildGpx(journey, points, startTrim = 1) }
        assertThrows(IllegalArgumentException::class.java) { buildGpx(journey, points, endTrim = -1) }
        assertThrows(IllegalArgumentException::class.java) { buildGpx(journey, points.map { it.copy(altitude = Double.POSITIVE_INFINITY) }) }
    }
}
