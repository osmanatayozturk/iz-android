package org.iz.navigation.gpx

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Android's actual SAX implementation, not the desktop parser. */
@RunWith(AndroidJUnit4::class)
class GpxParserAndroidTest {
    private val geometry = "<trk><name>İz yürüyüşü</name><trkseg><trkpt lat=\"41\" lon=\"29\"/><trkpt lat=\"41.001\" lon=\"29.001\"/></trkseg></trk>"
    private fun gpx(body: String) = "<gpx xmlns=\"http://www.topografix.com/GPX/1.1\" version=\"1.1\">$body</gpx>"
    private fun read(xml: String) = GpxImporter.read(ByteArrayInputStream(xml.toByteArray()))
    @Test fun androidReadsIndependentSegmentsAndTurkishNames() {
        val track = read(gpx(geometry + geometry))
        assertEquals(2, track.segments.size)
        assertEquals(listOf(0, 1), track.segments.map { it.trackIndex })
        assertEquals("İz yürüyüşü", track.segments.first().trackName)
    }
    @Test fun androidRejectsDtdAndExternalEntitiesIncludingUtf16() {
        val attempts = listOf(
            "<!DOCTYPE gpx [<!ENTITY a \"name\">]>" + gpx(geometry.replace("İz yürüyüşü", "&a;")),
            "<!DOCTYPE gpx SYSTEM \"file:///data/local/tmp/private\">" + gpx(geometry),
            "<!DOCTYPE gpx [<!ENTITY a SYSTEM \"https://example.invalid/private\">]>" + gpx(geometry.replace("İz yürüyüşü", "&a;")))
        for (xml in attempts) {
            assertThrows(GpxImportException::class.java) { read(xml) }
            val utf16 = ("<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + xml).toByteArray(Charsets.UTF_16)
            assertThrows(GpxImportException::class.java) { GpxImporter.read(ByteArrayInputStream(utf16)) }
        }
    }
    @Test fun androidNeverReturnsPartialTrackForInvalidTail() {
        assertThrows(GpxImportException::class.java) { read(gpx(geometry) + "<broken") }
        assertThrows(GpxImportException::class.java) { read(gpx(geometry + geometry.replace("41.001", "NaN"))) }
    }
}
