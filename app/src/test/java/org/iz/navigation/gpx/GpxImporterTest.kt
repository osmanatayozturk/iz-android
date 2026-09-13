package org.iz.navigation.gpx

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class GpxImporterTest {
    private val segment = "<trkseg><trkpt lat=\"41\" lon=\"29\"/><trkpt lat=\"41.001\" lon=\"29.001\"/></trkseg>"
    private fun file(body: String, version: String = "1.1") =
        "<gpx xmlns=\"http://www.topografix.com/GPX/${if (version == "1.0") "1/0" else "1/1"}\" version=\"$version\">$body</gpx>"
    private fun read(xml: String) = GpxImporter.read(ByteArrayInputStream(xml.toByteArray()), "İçe alınan iz")
    private fun rejects(xml: String) { assertThrows(GpxImportException::class.java) { read(xml) } }

    @Test fun preservesTrackGroupsSegmentsAndTurkishNamesWithoutBridging() {
        val track = read(file("<trk><name>İlk yürüyüş</name>$segment$segment</trk><trk><name>İkinci</name>$segment</trk>"))
        assertEquals(3, track.segments.size)
        assertEquals(listOf(0, 0, 1), track.segments.map { it.trackIndex })
        assertEquals(listOf("İlk yürüyüş", "İlk yürüyüş", "İkinci"), track.segments.map { it.trackName })
        assertTrue(track.segments.all { it.points.size == 2 })
        assertEquals("İçe alınan iz", track.name)
    }
    @Test fun readsGpx10AndIgnoresForeignHealthAndTimestamps() {
        val xml = file("<trk>$segment<extensions xmlns:other=\"urn:health\"><other:trkpt lat=\"999\"/><other:hr>90</other:hr></extensions></trk>", "1.0")
        assertEquals(2, read(xml).segments.single().points.size)
    }
    @Test fun rejectsWaypointsRoutesEmptyAndDegenerateTracks() {
        listOf("", "<rte><rtept lat=\"41\" lon=\"29\"/></rte>", "<wpt lat=\"41\" lon=\"29\"/>",
            "<trk/>", "<trk><trkseg/></trk>", "<trk><trkseg><trkpt lat=\"41\" lon=\"29\"/><trkpt lat=\"41\" lon=\"29\"/></trkseg></trk>")
            .forEach { rejects(file(it)) }
    }
    @Test fun malformedTailOrInvalidCoordinateRejectsWholeFile() {
        rejects(file("<trk>$segment</trk>") + "<broken")
        listOf("NaN", "Infinity", "91", "-91", "bad").forEach {
            rejects(file("<trk>$segment${segment.replace("lat=\"41\"", "lat=\"$it\"")}</trk>"))
        }
        rejects(file("<trk>$segment${segment.replace("lon=\"29\"", "lon=\"181\"")}</trk>"))
        rejects(file("<trk>$segment${segment.replace("lat=\"41\"", "")}</trk>"))
    }
    @Test fun rejectsDoctypeInternalAndExternalEntities() {
        rejects("<!DOCTYPE gpx [<!ENTITY x \"İz\">]>" + file("<trk><name>&x;</name>$segment</trk>"))
        rejects("<!DOCTYPE gpx SYSTEM \"file:///not-allowed\">" + file("<trk>$segment</trk>"))
        rejects("<!DOCTYPE gpx [<!ENTITY x SYSTEM \"https://example.invalid/private\">]>" + file("<trk><name>&x;</name>$segment</trk>"))
    }
    @Test fun rejectsByteTrackSegmentAndPointLimitsWithoutPartialSuccess() {
        rejects(file("<trk>$segment</trk>" + " ".repeat(10 * 1024 * 1024)))
        rejects(file((1..101).joinToString("") { "<trk>$segment</trk>" }))
        rejects(file("<trk>" + segment.repeat(1001) + "</trk>"))
        rejects(file("<trk><trkseg>" + "<trkpt lat=\"41\" lon=\"29\"/>".repeat(100000) + "<trkpt lat=\"42\" lon=\"29\"/></trkseg></trk>"))
    }
    @Test fun namespaceAndVersionMustDescribeGpx() {
        rejects("<gpx version=\"2.0\"><trk>$segment</trk></gpx>")
        rejects(file("<trk>$segment</trk>").replace("topografix.com", "example.invalid"))
        rejects(file("<extensions><trk>$segment</trk></extensions>"))
    }
    @Test fun acceptsExactLimitsAndEscapedNames() {
        val points = "<trkpt lat=\"41\" lon=\"29\"/>".repeat(99999) + "<trkpt lat=\"42\" lon=\"29\"/>"
        assertEquals(100000, read(file("<trk><name>A &amp; B</name><trkseg>$points</trkseg></trk>")).segments.single().points.size)
        assertEquals(1000, read(file("<trk>" + segment.repeat(1000) + "</trk>")).segments.size)
    }
}
