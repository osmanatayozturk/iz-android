package org.iz.navigation.gpx

import java.io.FilterInputStream
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.iz.navigation.weather.WeatherCoordinate
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

class GpxImportException(message: String) : IllegalArgumentException(message)

/** Streaming geometry-only import. No document-provided path, URL or entity is resolved. */
object GpxImporter {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_POINTS = 100_000
    const val MAX_TRACKS = 100
    const val MAX_SEGMENTS = 1_000

    fun read(input: InputStream, fallbackName: String = "GPX izi"): ImportedTrack {
        try {
            val handler = Handler()
            val reader = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
            }.newSAXParser().xmlReader
            // Android and desktop SAX differ in optional feature support. The required
            // lexical handler rejects every DTD before declarations can be processed.
            for (feature in listOf("external-general-entities", "external-parameter-entities")) {
                try { reader.setFeature("http://xml.org/sax/features/$feature", false) }
                catch (_: SAXException) { /* Required DTD and entity handlers remain fail closed. */ }
            }
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.contentHandler = handler
            reader.entityResolver = handler
            reader.errorHandler = handler
            reader.parse(InputSource(BoundedInput(input)))
            if (handler.segments.isEmpty()) throw GpxImportException("Dosyada takip edilebilir bir GPX izi yok.")
            return ImportedTrack(name = fallbackName.trim().take(160).ifBlank { "GPX izi" }, segments = handler.segments.toList())
        } catch (error: GpxImportException) {
            throw error
        } catch (_: Exception) {
            // Provider paths and attacker-controlled XML text must not reach logs or UI.
            throw GpxImportException("GPX dosyası okunamadı. Dosya bozuk, desteklenmiyor veya sınırları aşıyor.")
        }
    }

    private class BoundedInput(input: InputStream) : FilterInputStream(input) {
        private var consumed = 0
        private fun count(size: Int) {
            if (size > 0) consumed += size
            if (consumed > MAX_BYTES) throw GpxImportException("GPX dosyası en fazla 10 MB olabilir.")
        }
        override fun read(): Int = super.read().also { if (it >= 0) count(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, minOf(length, MAX_BYTES - consumed + 1)).also(::count)
        override fun skip(n: Long): Long {
            val bytes = ByteArray(minOf(n.coerceAtLeast(0), 8192L).toInt())
            var total = 0L
            while (total < n) {
                val size = read(bytes, 0, minOf(bytes.size.toLong(), n - total).toInt())
                if (size <= 0) break
                total += size
            }
            return total
        }
        override fun markSupported() = false
        override fun reset(): Unit = throw java.io.IOException("Reset unsupported")
    }

    private class Handler : DefaultHandler2() {
        val segments = mutableListOf<TrackSegment>()
        private val path = mutableListOf<String>()
        private var namespace = ""
        private var tracks = 0
        private var totalPoints = 0
        private var trackName = ""
        private var trackFirstSegment = 0
        private var points: MutableList<WeatherCoordinate>? = null
        private var nameText: StringBuilder? = null

        override fun startDTD(name: String?, publicId: String?, systemId: String?): Unit =
            throw SAXException("DTD prohibited")
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
            throw SAXException("External entity prohibited")
        override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
            throw SAXException("External entity prohibited")
        override fun skippedEntity(name: String?): Unit = throw SAXException("Entity prohibited")
        override fun error(e: SAXParseException): Unit = throw e
        override fun fatalError(e: SAXParseException): Unit = throw e

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            if (path.size >= 64) throw SAXException("Too deep")
            if (path.isEmpty()) {
                val version = attributes.getValue("version")
                if (localName != "gpx" || version !in setOf("1.0", "1.1") ||
                    uri != "http://www.topografix.com/GPX/$version") throw SAXException("Not GPX")
                namespace = uri
            }
            path += if (uri == namespace) localName else "#extension"
            when {
                path == listOf("gpx", "trk") -> {
                    if (++tracks > MAX_TRACKS) throw SAXException("Too many tracks")
                    trackName = "İz $tracks"
                    trackFirstSegment = segments.size
                }
                path == listOf("gpx", "trk", "name") -> nameText = StringBuilder()
                path == listOf("gpx", "trk", "trkseg") -> {
                    if (segments.size >= MAX_SEGMENTS) throw SAXException("Too many segments")
                    points = mutableListOf()
                }
                path == listOf("gpx", "trk", "trkseg", "trkpt") -> {
                    if (++totalPoints > MAX_POINTS) throw SAXException("Too many points")
                    val lat = attributes.getValue("lat")?.toDoubleOrNull()
                    val lon = attributes.getValue("lon")?.toDoubleOrNull()
                    if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() ||
                        lat !in -90.0..90.0 || lon !in -180.0..180.0) throw SAXException("Bad coordinate")
                    requireNotNull(points).add(WeatherCoordinate(lat, lon))
                }
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (path == listOf("gpx", "trk", "name")) nameText?.let {
                it.append(ch, start, minOf(length, (160 - it.length).coerceAtLeast(0)))
            }
        }
        override fun endElement(uri: String, localName: String, qName: String) {
            when (path) {
                listOf("gpx", "trk", "name") -> {
                    trackName = nameText.toString().trim().ifBlank { "İz $tracks" }
                    nameText = null
                }
                listOf("gpx", "trk", "trkseg") -> {
                    val values = requireNotNull(points)
                    if (values.size < 2 || values.none { it != values.first() }) throw SAXException("Empty segment")
                    segments += TrackSegment("Bölüm ${segments.size - trackFirstSegment + 1}", values.toList(), trackName, tracks - 1)
                    points = null
                }
                listOf("gpx", "trk") -> {
                    if (segments.size == trackFirstSegment) throw SAXException("Empty track")
                    // A name after trkseg is tolerated without losing the track grouping.
                    for (i in trackFirstSegment until segments.size) segments[i] = segments[i].copy(trackName = trackName)
                }
            }
            path.removeAt(path.lastIndex)
        }
    }
}
