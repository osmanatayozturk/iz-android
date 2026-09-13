package org.iz.navigation.gpx

import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.buildGpx
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test

class GpxNamespaceCompatibilityTest {
    private val geometry = """<trk><trkseg><trkpt lat="10" lon="20"/><trkpt lat="10.001" lon="20.001"/></trkseg></trk>"""
    private fun read(xml: String) = GpxImporter.read(xml.byteInputStream())

    @Test fun acceptsStandardGpx10Namespace() {
        val xml = """<gpx version="1.0" creator="test" xmlns="http://www.topografix.com/GPX/1/0">$geometry</gpx>"""
        assertEquals(listOf(WeatherCoordinate(10.0, 20.0), WeatherCoordinate(10.001, 20.001)), read(xml).segments.single().points)
    }

    @Test fun acceptsStandardGpx11Namespace() {
        val xml = """<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">$geometry</gpx>"""
        assertEquals(listOf(WeatherCoordinate(10.0, 20.0), WeatherCoordinate(10.001, 20.001)), read(xml).segments.single().points)
    }

    @Test fun rejectsDottedNamespaceForBothVersions() {
        for (version in listOf("1.0", "1.1")) {
            val xml = """<gpx version="$version" xmlns="http://www.topografix.com/GPX/$version">$geometry</gpx>"""
            assertThrows(GpxImportException::class.java) { read(xml) }
        }
    }

    @Test fun rejectsNamespaceOfOtherSupportedVersion() {
        for ((version, namespace) in listOf("1.0" to "1/1", "1.1" to "1/0")) {
            val xml = """<gpx version="$version" xmlns="http://www.topografix.com/GPX/$namespace">$geometry</gpx>"""
            assertThrows(GpxImportException::class.java) { read(xml) }
        }
    }

    @Test fun importsOwnGpxExportWithoutLosingCoordinatesOrJoiningBreaks() {
        val journey = Journey(id = "synthetic-round-trip", startedAt = 0, endedAt = 5_000)
        fun point(time: Long, latitude: Double, breakBefore: Boolean = false) =
            TrackPoint(journeyId = journey.id, recordedAt = time, latitude = latitude, longitude = 29.0, accuracy = 5f, breakBefore = breakBefore)
        val points = listOf(point(1_000, 41.0), point(2_000, 41.0001), point(3_000, 41.01, true), point(4_000, 41.0101))

        val imported = read(buildGpx(journey, points))

        assertEquals(listOf(
            listOf(WeatherCoordinate(41.0, 29.0), WeatherCoordinate(41.0001, 29.0)),
            listOf(WeatherCoordinate(41.01, 29.0), WeatherCoordinate(41.0101, 29.0)),
        ), imported.segments.map { it.points })
        assertEquals(listOf(0, 0), imported.segments.map { it.trackIndex })
    }
}
