package org.iz.navigation.integration.osm

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import org.iz.navigation.integration.OsmDirectedPoint
import org.junit.Assert.*
import org.junit.Test

class OsmGpxRemoteDataTest {
    @Test fun preservesTrackAndSegmentBoundariesAndHashesOriginalUncompressedBytes() {
        val bytes = remoteGpx("<trkseg><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"2\" lon=\"3\"/></trkseg>" +
            "<trkseg><trkpt lat=\"4\" lon=\"5\"/></trkseg>", "<trk><trkseg><trkpt lat=\"6\" lon=\"7\"/></trkseg></trk>").toByteArray()
        val expected = listOf(listOf(OsmDirectedPoint(1.0, 2.0), OsmDirectedPoint(2.0, 3.0)),
            listOf(OsmDirectedPoint(4.0, 5.0)), listOf(OsmDirectedPoint(6.0, 7.0)))
        val plain = OsmGpxRemoteXml.parse(bytes, "application/gpx+xml; charset=utf-8")
        val zipped = OsmGpxRemoteXml.parse(gzip(bytes), "application/gzip")
        assertEquals(expected, plain.segments)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, plain.sha256)
        assertEquals(plain, zipped)
        assertThrows(IOException::class.java) { OsmGpxRemoteXml.parse(gzip(bytes) + byteArrayOf(1, 2), "application/gzip") }
        assertThrows(IOException::class.java) { OsmGpxRemoteXml.parse(gzip(bytes).dropLast(4).toByteArray(), "application/gzip") }
        assertNotEquals(plain.sha256, OsmGpxRemoteXml.parse((String(bytes) + "\n").toByteArray(), null).sha256)
    }

    @Test fun unsupportedOrIncompleteInputCannotBecomeEmptyCoverage() {
        val bad = listOf("", "<html/>", remoteGpx(""), remoteGpx("<trkseg/>"),
            remoteGpx("<trkpt lat=\"1\" lon=\"2\"/>"),
            remoteGpx("<trkseg><trkpt lat=\"NaN\" lon=\"2\"/></trkseg>"),
            remoteGpx("<trkseg><trkpt lat=\"91\" lon=\"2\"/></trkseg>"),
            remoteGpx("<trkseg><trkpt lat=\"1\"/></trkseg>"),
            remoteGpx("<trkseg>unknown<trkpt lat=\"1\" lon=\"2\"/></trkseg>"),
            remoteGpx("<trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg><unknown/>"),
            remoteGpx("<trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg>", "<rte><rtept lat=\"3\" lon=\"4\"/></rte>"),
            remoteGpx("<trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg>").replace("GPX/1/1", "unexpected"),
            "<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///not-read'>]>" + remoteGpx("&x;"),
            "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" + remoteGpx(""),
            remoteGpx("<trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg>") + "trailing")
        bad.forEach { xml -> assertThrows(IOException::class.java) { OsmGpxRemoteXml.parse(xml.toByteArray(), null) } }
        assertThrows(IOException::class.java) { OsmGpxRemoteXml.parse(byteArrayOf(0xc3.toByte()), null) }
        for (type in listOf("text/html", "application/zip", "application/x-bzip2", "application/x-tar"))
            assertThrows(IOException::class.java) { OsmGpxRemoteXml.parse(remoteGpx("").toByteArray(), type) }
    }

    @Test fun boundsExpansionAndDepthWithoutAcceptingAPartialPrefix() {
        val tooBig = ByteArray(OsmGpxRemoteXml.MAX_BYTES.toInt() + 1) { 32 }
        assertThrows(OsmGpxDataLimitException::class.java) { OsmGpxRemoteXml.parse(tooBig, null) }
        assertThrows(OsmGpxDataLimitException::class.java) { OsmGpxRemoteXml.parse(gzip(tooBig), "application/gzip") }
        val deep = remoteGpx("<extensions>" + "<x>".repeat(40) + "</x>".repeat(40) + "</extensions>")
        assertThrows(OsmGpxDataLimitException::class.java) { OsmGpxRemoteXml.parse(deep.toByteArray(), null) }
        val tooManyPoints = remoteGpx("<trkseg>" + "<trkpt lat=\"1\" lon=\"2\"/>".repeat(200_001) + "</trkseg>")
        assertThrows(OsmGpxDataLimitException::class.java) { OsmGpxRemoteXml.parse(tooManyPoints.toByteArray(), null) }
        val tooManySegments = remoteGpx("<trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg>".repeat(10_001))
        assertThrows(OsmGpxDataLimitException::class.java) { OsmGpxRemoteXml.parse(tooManySegments.toByteArray(), null) }
        val unsupported = assertThrows(IOException::class.java) { OsmGpxRemoteXml.parse("<html/>".toByteArray(), null) }
        assertFalse(unsupported is OsmGpxDataLimitException)
    }
}

internal fun remoteGpx(segment: String = "<trkseg><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"2\" lon=\"3\"/></trkseg>",
    more: String = "") = "<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"><trk>$segment</trk>$more</gpx>"

internal fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
    GZIPOutputStream(out).use { it.write(bytes) }
}.toByteArray()
