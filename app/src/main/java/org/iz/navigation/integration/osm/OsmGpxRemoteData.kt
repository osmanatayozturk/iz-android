package org.iz.navigation.integration.osm

import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.OffsetDateTime
import javax.xml.parsers.SAXParserFactory
import org.json.JSONObject
import org.iz.navigation.integration.OsmDirectedPoint
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import okio.Buffer
import okio.GzipSource
import okio.buffer

internal data class OsmGpxRemoteData(val segments: List<List<OsmDirectedPoint>>, val sha256: String)

internal class OsmGpxDataLimitException : IOException("GPX data exceeds safety limit")

internal fun requireOsmGpxDataLimit(condition: Boolean) {
    if (!condition) throw OsmGpxDataLimitException()
}

internal data class OsmGpxRemoteMetadata(val id: Long, val name: String, val timestampMillis: Long,
    val pending: Boolean, val visibility: String, val description: String, val tags: List<String>) {
    fun samePreservedFields(other: OsmGpxRemoteMetadata) = id == other.id && name == other.name &&
        timestampMillis == other.timestampMillis && description == other.description && tags.sorted() == other.tags.sorted()

    /** Canonical preserved fields: UTF-8 byte lengths, fixed field order and sorted tags with duplicates. */
    fun digest(): String {
        val canonical = Buffer()
        fun field(value: String) {
            val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
            canonical.writeInt(bytes.size).write(bytes)
        }
        field(id.toString())
        field(name)
        field(timestampMillis.toString())
        field(description)
        canonical.writeInt(tags.size)
        tags.sorted().forEach(::field)
        return canonical.readByteString().sha256().hex()
    }

    // Trace.update_from_xml_node requires description and replaces every tag, even for visibility-only changes.
    fun trackableXml() = "<osm><gpx_file id=\"$id\" visibility=\"trackable\"><description>" +
        escape(description) + "</description>" + tags.joinToString("") { "<tag>${escape(it)}</tag>" } + "</gpx_file></osm>"

    companion object {
        fun parse(row: JSONObject, expectedUid: Long): OsmGpxRemoteMetadata {
            require(!row.has("error") && row.integral("uid") == expectedUid)
            val id = row.integral("id").also { require(it > 0) }
            val name = (row.get("name") as String).also {
                requireOsmGpxDataLimit(it.length <= 1024)
                require(it.isNotBlank() && it.none(Char::isISOControl))
            }
            val time = OffsetDateTime.parse(row.get("timestamp") as String).toInstant().toEpochMilli()
            val pending = row.get("pending") as Boolean
            val visibility = (row.get("visibility") as String).also {
                require(it in setOf("identifiable", "trackable", "private", "public"))
            }
            val description = text(row.get("description") as String)
            val array = row.getJSONArray("tags")
            requireOsmGpxDataLimit(array.length() <= 1000)
            val tags = List(array.length()) { text(array.get(it) as String) }
            return OsmGpxRemoteMetadata(id, name, time, pending, visibility, description, tags)
        }
        private fun text(value: String) = value.also {
            requireOsmGpxDataLimit(it.length <= 1024)
            require(it.none { char -> char.isISOControl() && char !in "\t\n\r" })
        }
        private fun JSONObject.integral(key: String): Long = when (val value = get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> error("Invalid GPX metadata integer")
        }
        private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\r", "&#13;")
    }
}

/** Bounded, fail-closed GPX 1.0/1.1 input. Unsupported geometry/archives never mean an empty history. */
internal object OsmGpxRemoteXml {
    const val MAX_BYTES = 16L * 1024 * 1024
    fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
    fun isGzip(bytes: ByteArray) = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    fun parse(input: ByteArray, mediaType: String?): OsmGpxRemoteData {
        try {
            requireOsmGpxDataLimit(input.size <= MAX_BYTES)
            val type = mediaType?.substringBefore(';')?.trim()?.lowercase()
            require(type == null || type in setOf("application/gpx+xml", "application/xml", "text/xml",
                "application/octet-stream", "application/gzip", "application/x-gzip"))
            val bytes = if (isGzip(input)) {
                GzipSource(Buffer().write(input)).buffer().use { gzip ->
                    requireOsmGpxDataLimit(!gzip.request(MAX_BYTES + 1))
                    gzip.readByteArray()
                }
            } else input
            val xml = utf8(bytes)
            require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
            val declared = Regex("<\\?xml[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)
            require(declared == null || declared.equals("UTF-8", true))
            val handler = GeometryHandler()
            val reader = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().xmlReader
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") }
            reader.parse(InputSource(StringReader(xml)))
            require(handler.segments.isNotEmpty())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return OsmGpxRemoteData(handler.segments.map { it.toList() }, hash)
        } catch (limit: OsmGpxDataLimitException) { throw limit }
        catch (_: Exception) { throw IOException("Invalid or unsupported GPX geometry") }
    }

    private class GeometryHandler : DefaultHandler() {
        val segments = mutableListOf<MutableList<OsmDirectedPoint>>()
        private val stack = mutableListOf<String>()
        private var namespace = ""
        private var points = 0
        private var elements = 0
        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            requireOsmGpxDataLimit(stack.size < 32 && ++elements <= 1_000_000)
            if (stack.isEmpty()) {
                require(localName == "gpx")
                val version = attrs.getValue("version")
                require(version in setOf("1.0", "1.1"))
                require(uri == "http://www.topografix.com/GPX/${version!!.replace('.', '/')}")
                namespace = uri
            } else if ("extensions" !in stack) {
                require(uri == namespace)
                val parent = stack.last()
                when (localName) {
                    "gpx" -> error("Nested GPX")
                    "trk" -> require(parent == "gpx")
                    "trkseg" -> {
                        require(parent == "trk")
                        requireOsmGpxDataLimit(segments.size < 10_000)
                        segments.add(mutableListOf())
                    }
                    "trkpt" -> {
                        require(parent == "trkseg")
                        requireOsmGpxDataLimit(++points <= 200_000)
                        val lat = attrs.getValue("lat")?.toDoubleOrNull()
                        val lon = attrs.getValue("lon")?.toDoubleOrNull()
                        require(lat != null && lat.isFinite() && lat in -90.0..90.0)
                        require(lon != null && lon.isFinite() && lon in -180.0..180.0)
                        segments.last().add(OsmDirectedPoint(lat, lon))
                    }
                    "rte", "rtept", "wpt" -> error("Unsupported GPX geometry")
                    else -> require(localName in setOf("metadata", "name", "desc", "author", "email", "link", "url", "urlname",
                        "copyright", "year", "license", "time", "keywords", "bounds", "cmt", "src", "number", "type",
                        "ele", "magvar", "geoidheight", "sym", "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata",
                        "dgpsid", "text", "extensions", "course", "speed"))
                }
            }
            stack.add(localName)
        }
        override fun endElement(uri: String, localName: String, qName: String) {
            if (localName == "trkseg" && uri == namespace && "extensions" !in stack) require(segments.last().isNotEmpty())
            stack.removeAt(stack.lastIndex)
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if ("extensions" !in stack && stack.lastOrNull() in setOf("gpx", "trk", "trkseg", "trkpt"))
                require((start until start + length).all { ch[it].isWhitespace() })
        }
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
    }
}
