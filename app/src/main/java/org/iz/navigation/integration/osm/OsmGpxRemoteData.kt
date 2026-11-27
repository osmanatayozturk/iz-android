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
import org.xml.sax.ext.DefaultHandler2
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
            val entry = parseHistoryEntry(row, expectedUid)
            val description = text(row.get("description") as String)
            val array = row.getJSONArray("tags")
            requireOsmGpxDataLimit(array.length() <= 1000)
            val tags = List(array.length()) { text(array.get(it) as String) }
            return OsmGpxRemoteMetadata(entry.id, entry.fileName, entry.timestampMillis, entry.pending,
                requireNotNull(entry.visibility), description, tags)
        }

        /** The index consumes no write-only metadata; a future PUT must still fetch and parse the full detail. */
        fun parseHistoryEntry(row: JSONObject, expectedUid: Long): OsmGpxHistoryEntry {
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
            return OsmGpxHistoryEntry(id, name, time, pending, visibility)
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

internal class OsmGpxGeometryFormatException : IOException("Invalid or unsupported GPX geometry")

/** Bounded GPX 1.0/1.1 input. The coverage geometry is the nonempty track segments. */
internal object OsmGpxRemoteXml {
    const val MAX_BYTES = 16L * 1024 * 1024
    private const val MAX_ACTIVE_NAMESPACE_BINDINGS = 4_096
    private val geometryElements = setOf("gpx", "wpt", "rte", "rtept", "trk", "trkseg", "trkpt")
    private val legacyExtensionParents = geometryElements - "trkseg"
    private val otherGpxElements = setOf("metadata", "name", "desc", "author", "email", "link", "url", "urlname",
        "copyright", "year", "license", "time", "keywords", "bounds", "cmt", "src", "number", "type",
        "ele", "magvar", "geoidheight", "sym", "fix", "sat", "hdop", "vdop", "pdop", "ageofdgpsdata",
        "dgpsid", "text", "course", "speed")
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
            // Both the Android Expat reader and the JDK provider accept these SAX settings.
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false)
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
            reader.contentHandler = handler
            reader.errorHandler = handler
            reader.setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") }
            reader.parse(InputSource(StringReader(xml)))
            require(handler.segments.isNotEmpty())
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return OsmGpxRemoteData(handler.segments.map { it.toList() }, hash)
        } catch (limit: OsmGpxDataLimitException) { throw limit }
        catch (_: Exception) { throw OsmGpxGeometryFormatException() }
    }

    internal class GeometryHandler : DefaultHandler2() {
        val segments = mutableListOf<MutableList<OsmDirectedPoint>>()
        private val stack = mutableListOf<String>()
        private val namespacesByPrefix = mutableMapOf<String, ArrayDeque<String>>()
        private val namedPrefixesByUri = mutableMapOf<String, Int>()
        private var activeBindings = 0
        private var namespace = ""
        private var legacy = false
        private var extensionDepth: Int? = null
        private var segmentCount = 0
        private var points = 0
        private var elements = 0

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw OsmGpxGeometryFormatException()
        }

        override fun startPrefixMapping(prefix: String, uri: String) {
            requireOsmGpxDataLimit(++activeBindings <= MAX_ACTIVE_NAMESPACE_BINDINGS)
            val bindings = namespacesByPrefix.getOrPut(prefix) { ArrayDeque() }
            changeNamedPrefixCount(prefix, bindings.lastOrNull(), -1)
            bindings.addLast(uri)
            changeNamedPrefixCount(prefix, uri, 1)
        }

        override fun endPrefixMapping(prefix: String) {
            val bindings = checkNotNull(namespacesByPrefix[prefix])
            changeNamedPrefixCount(prefix, bindings.removeLast(), -1)
            changeNamedPrefixCount(prefix, bindings.lastOrNull(), 1)
            if (bindings.isEmpty()) namespacesByPrefix.remove(prefix)
            activeBindings--
        }

        private fun changeNamedPrefixCount(prefix: String, uri: String?, delta: Int) {
            if (prefix.isEmpty() || uri == null) return
            val count = (namedPrefixesByUri[uri] ?: 0) + delta
            if (count == 0) namedPrefixesByUri.remove(uri) else namedPrefixesByUri[uri] = count
        }

        private fun isUnqualified(uri: String, localName: String, qName: String): Boolean {
            if (qName.isNotEmpty()) return qName == localName
            // Android Expat may omit qName. A default binding and a named alias
            // for the same URI would make these two spellings indistinguishable.
            val defaultPossible = namespacesByPrefix[""]?.lastOrNull().orEmpty() == uri
            val prefixedPossible = uri in namedPrefixesByUri
            require(defaultPossible != prefixedPossible)
            return defaultPossible
        }

        override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
            requireOsmGpxDataLimit(stack.size < 32 && ++elements <= 1_000_000)
            if (localName == "trkseg" || localName == "trkpt") {
                val expectedParent = if (localName == "trkseg") "trk" else "trkseg"
                val supported = extensionDepth == null && uri == namespace && stack.lastOrNull() == expectedParent
                // OSM imports exact, unprefixed names even outside the GPX structure.
                require(isUnqualified(uri, localName, qName) == supported)
            }
            if (stack.isEmpty()) {
                require(localName == "gpx")
                val version = attrs.getValue("", "version")
                require(version in setOf("1.0", "1.1"))
                require(uri == "http://www.topografix.com/GPX/${version!!.replace('.', '/')}")
                namespace = uri
                legacy = version == "1.0"
            } else if (extensionDepth == null) {
                val parent = stack.last()
                if (uri != namespace) {
                    require(legacy && uri.isNotEmpty() && parent in legacyExtensionParents)
                    extensionDepth = stack.size
                } else {
                    when (localName) {
                        "gpx" -> error("Nested GPX")
                        "trk", "wpt", "rte" -> require(parent == "gpx")
                        "rtept" -> require(parent == "rte")
                        "extensions" -> extensionDepth = stack.size
                        "trkseg" -> {
                            require(parent == "trk")
                            requireOsmGpxDataLimit(++segmentCount <= 10_000)
                            segments.add(mutableListOf())
                        }
                        "trkpt" -> {
                            require(parent == "trkseg")
                            requireOsmGpxDataLimit(++points <= 200_000)
                            val lat = attrs.getValue("", "lat")?.toDoubleOrNull()
                            val lon = attrs.getValue("", "lon")?.toDoubleOrNull()
                            require(lat != null && lat.isFinite() && lat in -90.0..90.0)
                            require(lon != null && lon.isFinite() && lon in -180.0..180.0)
                            segments.last().add(OsmDirectedPoint(lat, lon))
                        }
                        else -> require(localName in otherGpxElements)
                    }
                }
            }
            stack.add(localName)
        }
        override fun endElement(uri: String, localName: String, qName: String) {
            stack.removeAt(stack.lastIndex)
            if (extensionDepth == stack.size) {
                extensionDepth = null
            } else if (extensionDepth == null && localName == "trkseg" && segments.last().isEmpty()) {
                segments.removeAt(segments.lastIndex)
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (extensionDepth == null && stack.lastOrNull() in geometryElements)
                require((start until start + length).all { ch[it].isWhitespace() })
        }
        override fun error(e: SAXParseException) { throw e }
        override fun fatalError(e: SAXParseException) { throw e }
    }
}
