package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class OsmGpxPrivacyTest {
    private val start = 1_700_000_000_000L
    private val journey = Journey(id = "private-journey-id", title = "PRIVATE_TITLE",
        note = "PRIVATE_NOTE", startedAt = start, endedAt = start + 100_000_000L)
    private fun point(meters: Double, index: Int, breakBefore: Boolean = false) = TrackPoint(
        id = "private-point-$index", journeyId = journey.id, latitude = 0.0,
        longitude = Math.toDegrees(meters / 6_371_000.0), recordedAt = start + index * 20_000L,
        accuracy = 5f, altitude = 42.12345, breakBefore = breakBefore)
    private fun points(vararg meters: Double) = meters.mapIndexed { i, m -> point(m, i) }
    private fun ready(points: List<TrackPoint>) =
        (prepareOsmGpxTrace(journey, points) as OsmGpxPreparation.Ready).trace
    private fun assertBlocked(reason: OsmGpxBlockedReason, points: List<TrackPoint>, record: Journey = journey) {
        assertEquals(reason, (prepareOsmGpxTrace(record, points) as OsmGpxPreparation.Blocked).reason)
    }

    @Test fun strictTwoKilometerBoundaryAndIncompleteJourneysAreBlocked() {
        val exact = points(0.0, 500.0, 1000.0, 1500.0, 2000.0)
        assertEquals(2000.0, DiaryRules.distanceMeters(exact), 1e-8)
        assertBlocked(OsmGpxBlockedReason.TOO_SHORT, exact)
        assertBlocked(OsmGpxBlockedReason.TOO_SHORT, points(0.0, 500.0, 1000.0, 1999.0))
        assertBlocked(OsmGpxBlockedReason.NOT_CONFIRMED_COMPLETED, exact, journey.copy(endedAt = null))
        assertBlocked(OsmGpxBlockedReason.NOT_CONFIRMED_COMPLETED, exact, journey.copy(status = JourneyStatus.TEMPORARY))
        assertBlocked(OsmGpxBlockedReason.NOT_CONFIRMED_COMPLETED, exact, journey.copy(endedAt = start - 1))
        assertBlocked(OsmGpxBlockedReason.TOO_SHORT, emptyList())
    }

    @Test fun clipsToActualFixesWithoutInterpolationAndLeavesSourceUntouched() {
        val original = points(0.0, 900.0, 1050.0, 1350.0, 1650.0, 1950.0, 2150.0, 3000.0)
        val shuffled = original.reversed().toMutableList()
        val before = shuffled.toList()
        val trace = ready(shuffled)
        assertEquals(original.subList(2, 6).map { it.recordedAt }, trace.segments.single().map { it.recordedAt })
        assertEquals(900.0, trace.distanceMeters, 1e-6)
        assertEquals(1050.0, trace.removedStartMeters, 1e-6)
        assertEquals(1050.0, trace.removedEndMeters, 1e-6)
        assertEquals(before, shuffled)
        shuffled.clear()
        assertEquals(4, trace.pointCount)
        assertEquals("PRIVATE_TITLE", journey.title)
    }

    @Test fun sparseTrackCannotUploadOneRemainingFix() {
        assertBlocked(OsmGpxBlockedReason.NO_DRAWABLE_SEGMENT, points(0.0, 1200.0, 2400.0))
        assertTrue(ready(points(0.0, 1001.0, 1005.0, 2006.0)).distanceMeters > 0)
    }

    @Test fun disconnectedRunsDoNotSpendGapDistanceOrJoinInTheArtifact() {
        val first = points(0.0, 700.0, 1100.0, 1500.0, 1900.0)
        val second = listOf(10000.0, 10400.0, 10800.0, 11400.0, 12200.0)
            .mapIndexed { i, m -> point(m, i + 5, breakBefore = i == 0) }
        val trace = ready(first + second)
        assertEquals(4100.0, trace.measuredDistanceMeters, 1e-6)
        assertEquals(1600.0, trace.distanceMeters, 1e-6)
        assertEquals(listOf(first.drop(2).map { it.recordedAt }, second.take(3).map { it.recordedAt }),
            trace.segments.map { segment -> segment.map { it.recordedAt } })
        assertEquals(2, "<trkseg>".toRegex().findAll(trace.gpxXml).count())
    }

    @Test fun invalidFixesAndTimeGapsSplitOtherwiseConnectablePoints() {
        val original = points(0.0, 800.0, 1200.0, 1600.0, 2000.0, 2400.0, 2800.0, 3200.0, 4000.0)
        val invalid = point(1800.0, 0).copy(recordedAt = start + 70_000L, latitude = Double.NaN)
        val split = ready(original + invalid)
        assertEquals(3600.0, split.measuredDistanceMeters, 1e-6)
        assertEquals(listOf(2, 3), split.segments.map { it.size })
        val timeGap = original.mapIndexed { i, p -> if (i >= 4) p.copy(recordedAt = p.recordedAt + 130_000L) else p }
        assertEquals(split.measuredDistanceMeters, ready(timeGap).measuredDistanceMeters, 1e-6)
        assertEquals(listOf(2, 3), ready(timeGap).segments.map { it.size })
    }

    @Test fun stationaryJitterAtBothEndsDoesNotConsumePrivacyBudget() {
        val prefix = (0 until 1200).map { point(if (it % 2 == 0) 0.0 else 1.0, it) }
        val route = listOf(0.0, 900.0, 1100.0, 1500.0, 2000.0, 2500.0, 2800.0, 3200.0, 4000.0)
            .mapIndexed { i, m -> point(m, 1200 + i) }
        val suffix = (0 until 1200).map { point(if (it % 2 == 0) 4000.0 else 4001.0, 1209 + it) }
        val trace = ready(prefix + route + suffix)
        assertEquals(4000.0, trace.measuredDistanceMeters, 1e-6)
        assertEquals(route.subList(2, 7).map { it.recordedAt }, trace.segments.single().map { it.recordedAt })
        assertTrue(trace.removedStartMeters >= 1000)
        assertTrue(trace.removedEndMeters >= 1000)
    }

    @Test fun foreignAndOutOfJourneyPointsCannotEnterTheArtifact() {
        val valid = points(0.0, 900.0, 1050.0, 1350.0, 1650.0, 1950.0, 2150.0, 3000.0)
        val foreign = point(1500.0, 3).copy(journeyId = "foreign", recordedAt = start + 25_000L)
        val tooEarly = point(-10000.0, -1)
        val tooLate = point(20000.0, 50).copy(recordedAt = journey.endedAt!! + 1)
        assertEquals(ready(valid).gpxXml, ready(valid + foreign + tooEarly + tooLate).gpxXml)
    }

    @Test fun artifactContainsOnlyExactCoordinatesAndTimesAndHasStableDigest() {
        val source = points(0.0, 900.0, 1050.0, 1350.0, 1650.0, 1950.0, 2150.0, 3000.0)
        val trace = ready(source)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(trace.gpxXml.byteInputStream())
        val nodes = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(4, nodes.length)
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as org.w3c.dom.Element
            val expected = source[i + 2]
            assertEquals(2, element.attributes.length)
            assertEquals(expected.latitude, element.getAttribute("lat").toDouble(), 0.0)
            assertEquals(expected.longitude, element.getAttribute("lon").toDouble(), 0.0)
            assertEquals(Instant.ofEpochMilli(expected.recordedAt).toString(), element.textContent)
            assertEquals(1, element.childNodes.length)
        }
        listOf("PRIVATE", "private-journey", "private-point", "<ele", "<name", "<desc", "<wpt", "42.12345").forEach {
            assertFalse(trace.gpxXml.contains(it))
            assertFalse(trace.fileName.contains(it))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(trace.gpxXml.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(digest, trace.sha256)
        assertEquals(trace.gpxXml, ready(source.reversed()).gpxXml)
        try {
            (trace.segments as MutableList<*>).clear()
            fail("outer artifact list must be immutable")
        } catch (_: UnsupportedOperationException) { }
        try {
            (trace.segments.single() as MutableList<*>).clear()
            fail("segment list must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }
}
