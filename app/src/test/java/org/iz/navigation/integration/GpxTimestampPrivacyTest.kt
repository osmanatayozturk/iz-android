package org.iz.navigation.integration

import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory

class GpxTimestampPrivacyTest {
    private val journey = Journey(id = "synthetic", startedAt = 0, endedAt = 10_000)
    private val points = listOf(
        TrackPoint(journeyId = journey.id, latitude = 10.0, longitude = 20.0, recordedAt = 1_000, accuracy = 5f),
        TrackPoint(journeyId = journey.id, latitude = 10.0001, longitude = 20.0001, recordedAt = 2_000, accuracy = 5f),
    )

    @Test fun defaultExportOmitsAllTimestampsAndPreservesSourceAndGeometry() {
        val original = points.toList()
        val xml = buildGpx(journey, points)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(2, document.getElementsByTagName("trkpt").length)
        assertEquals(0, document.getElementsByTagName("time").length)
        assertFalse(xml.contains("1970-01-01"))
        assertEquals(original, points)
    }

    @Test fun timestampOptInAppliesOnlyToUntrimmedPointsAndDoesNotChangeTheNextDefaultExport() {
        val expanded = points + points.last().copy(id = "third", recordedAt = 3000, latitude = 10.0002)
        val xml = buildGpx(journey, expanded, startTrim = 1, includeTimestamps = true)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals(2, document.getElementsByTagName("time").length)
        assertEquals("1970-01-01T00:00:02Z", document.getElementsByTagName("time").item(0).textContent)
        assertFalse(xml.contains("1970-01-01T00:00:01Z"))
        assertFalse(buildGpx(journey, expanded).contains("<time>"))
        assertEquals(listOf(1000L, 2000L, 3000L), expanded.map { it.recordedAt })
    }
}
