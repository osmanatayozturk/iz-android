package org.iz.navigation.ui

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.StringReader
import java.security.MessageDigest
import javax.xml.parsers.SAXParserFactory
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.osm.OsmGpxGeometryFormatException
import org.iz.navigation.integration.osm.OsmGpxRemoteXml
import org.iz.navigation.integration.prepareOsmGpxTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

@RunWith(AndroidJUnit4::class)
class OsmAndroidProviderAcceptanceTest {
    @Test fun nativeSaxPreservesIzAndDecorativeExtensionsButRejectsUncertainTrackSpelling() {
        requireDirectionalQa()
        val legacy = (prepareOsmGpxTrace(
            Journey(id = "qa-generated-history", startedAt = 1_714_662_720_000L, endedAt = 1_714_663_320_000L),
            List(131) { index ->
                TrackPoint(journeyId = "qa-generated-history", latitude = .24, longitude = -.37 + index * .00027,
                    recordedAt = 1_714_662_720_000L + index * 4_000L, accuracy = 3f, speed = 8f)
            },
        ) as OsmGpxPreparation.Ready).trace
        val legacyParsed = OsmGpxRemoteXml.parse(legacy.gpxXml.toByteArray(Charsets.UTF_8), "application/gpx+xml")
        assertEquals(listOf(63), legacyParsed.segments.map { it.size })
        assertEquals("afc9ed388b87d1f45c8a267d8715cd9172b9e5e9c30c3c29944f5be3abd96987", legacyParsed.sha256)

        val normal = """
            <gpx version="1.1" creator="Fixture" xmlns="$GPX_NAMESPACE" xmlns:style="urn:qa:style">
              <trk>
                <extensions><style:trackDecor><style:trkpt/></style:trackDecor></extensions>
                $SEGMENT
                $SEGMENT
              </trk>
            </gpx>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val normalParsed = OsmGpxRemoteXml.parse(normal, "application/gpx+xml")
        assertEquals(listOf(2, 2), normalParsed.segments.map { it.size })
        assertEquals(normalParsed.segments[0], normalParsed.segments[1])
        assertEquals(sha256(normal), normalParsed.sha256)

        val uncertain = """
            <gpx version="1.1" creator="Fixture" xmlns="$GPX_NAMESPACE">
              <trk>
                $SEGMENT
                <trkseg xmlns:alias="$GPX_NAMESPACE"><alias:trkpt lat="0.21" lon="-0.31"/></trkseg>
              </trk>
            </gpx>
        """.trimIndent()
        val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
        var known = 0
        var missing = 0
        reader.contentHandler = object : DefaultHandler() {
            override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
                if (localName != "trkseg" && localName != "trkpt") return
                if (qName.isEmpty()) missing++ else known++
            }
        }
        reader.parse(InputSource(StringReader(uncertain)))
        assertEquals(5, known + missing)
        assertThrows(OsmGpxGeometryFormatException::class.java) {
            OsmGpxRemoteXml.parse(uncertain.toByteArray(Charsets.UTF_8), "application/gpx+xml")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
            putString("stream", "\nOSM Android SAX QA: qualified names present=$known, omitted=$missing; whole-file rejection verified\n")
        })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
        const val SEGMENT = """<trkseg><trkpt lat="0.20" lon="-0.30"/><trkpt lat="0.2002" lon="-0.30"/></trkseg>"""
    }
}
