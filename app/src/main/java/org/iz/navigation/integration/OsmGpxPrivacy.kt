package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import java.time.Instant
import java.util.Collections
import javax.xml.parsers.SAXParserFactory
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.xml.sax.Attributes
import org.xml.sax.SAXException
import org.xml.sax.ext.DefaultHandler2

enum class OsmGpxBlockedReason { NOT_CONFIRMED_COMPLETED, TOO_SHORT, NO_DRAWABLE_SEGMENT }

sealed class OsmGpxPreparation {
    data class Ready(val trace: PreparedOsmGpxTrace) : OsmGpxPreparation()
    data class Blocked(
        val reason: OsmGpxBlockedReason,
        val measuredDistanceMeters: Double = 0.0,
    ) : OsmGpxPreparation()
}

data class OsmGpxPoint(val latitude: Double, val longitude: Double, val recordedAt: Long)

/** One immutable artifact supplies both preview and upload. Raw diary fields never leave this factory. */
class PreparedOsmGpxTrace private constructor(
    val journeyId: String,
    val measuredDistanceMeters: Double,
    val distanceMeters: Double,
    val removedStartMeters: Double,
    val removedEndMeters: Double,
    internal val gpxBytes: ByteString,
) {
    val gpxXml: String get() = gpxBytes.utf8()
    val sha256: String = gpxBytes.sha256().hex()
    val fileName: String = osmGpxFileName(sha256)
    val segments: List<List<OsmGpxPoint>> by lazy { readPreviewSegments(gpxBytes) }
    val pointCount: Int get() = segments.sumOf { it.size }

    companion object {
        internal fun create(
            journeyId: String,
            segments: List<List<TrackPoint>>,
            measuredDistanceMeters: Double,
            distanceMeters: Double,
            removedStartMeters: Double,
            removedEndMeters: Double,
        ): PreparedOsmGpxTrace {
            val xml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<gpx version=\"1.1\" creator=\"İz\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk>\n")
                segments.forEach { segment ->
                    append("<trkseg>\n")
                    segment.forEach { point ->
                        append("<trkpt lat=\"").append(point.latitude).append("\" lon=\"")
                            .append(point.longitude).append("\"><time>")
                            .append(Instant.ofEpochMilli(point.recordedAt)).append("</time></trkpt>\n")
                    }
                    append("</trkseg>\n")
                }
                append("</trk>\n</gpx>\n")
            }
            return PreparedOsmGpxTrace(journeyId, measuredDistanceMeters, distanceMeters,
                removedStartMeters, removedEndMeters, xml.encodeUtf8())
        }
    }
}

/** Decodes only the immutable GPX produced above, using the same bytes as the multipart file. */
private fun readPreviewSegments(bytes: ByteString): List<List<OsmGpxPoint>> {
    val segments = mutableListOf<List<OsmGpxPoint>>()
    val handler = object : DefaultHandler2() {
        private var segment = mutableListOf<OsmGpxPoint>()
        private var latitude = 0.0
        private var longitude = 0.0
        private var time: StringBuilder? = null

        override fun startDTD(name: String?, publicId: String?, systemId: String?) {
            throw SAXException("GPX preview cannot contain a DTD")
        }

        override fun startElement(uri: String, name: String, qName: String, attributes: Attributes) {
            when (name) {
                "trkseg" -> segment = mutableListOf()
                "trkpt" -> {
                    latitude = attributes.getValue("lat").toDouble()
                    longitude = attributes.getValue("lon").toDouble()
                }
                "time" -> time = StringBuilder()
            }
        }

        override fun characters(chars: CharArray, start: Int, length: Int) {
            time?.append(chars, start, length)
        }

        override fun endElement(uri: String, name: String, qName: String) {
            when (name) {
                "time" -> {
                    segment.add(OsmGpxPoint(latitude, longitude, Instant.parse(checkNotNull(time)).toEpochMilli()))
                    time = null
                }
                "trkseg" -> segments.add(Collections.unmodifiableList(segment))
            }
        }
    }
    val parser = SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser()
    parser.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
    Buffer().write(bytes).inputStream().use { parser.parse(it, handler) }
    return Collections.unmodifiableList(segments)
}

internal fun osmGpxFileName(sha256: String): String = "iz_gps_${sha256.take(24)}.gpx"

private data class MeasuredFix(val point: TrackPoint, val distanceFromStart: Double)
private const val ENDPOINT_TRIM_METERS = 1000.0
private const val DISTANCE_ROUNDING_MARGIN_METERS = 0.000001

/**
 * Remove at least one measured kilometer from each end, keeping only actual fixes.
 * Gaps and stationary noise contribute zero distance; no interpolation or gap bridging is allowed.
 */
fun prepareOsmGpxTrace(journey: Journey, points: List<TrackPoint>): OsmGpxPreparation {
    val endedAt = journey.endedAt
    if (journey.status != JourneyStatus.CONFIRMED || endedAt == null || endedAt < journey.startedAt) {
        return OsmGpxPreparation.Blocked(OsmGpxBlockedReason.NOT_CONFIRMED_COMPLETED)
    }
    var total = 0.0
    val measured = measuredJourneySegments(journey, points).map { segment ->
        var previous: TrackPoint? = null
        segment.map { point ->
            previous?.let { total += DiaryRules.distanceContributionMeters(it, point) }
            previous = point
            MeasuredFix(point, total)
        }
    }
    if (total <= ENDPOINT_TRIM_METERS * 2 + DISTANCE_ROUNDING_MARGIN_METERS) {
        return OsmGpxPreparation.Blocked(OsmGpxBlockedReason.TOO_SHORT, total)
    }
    val retained = measured.mapNotNull { segment ->
        segment.filter {
            it.distanceFromStart >= ENDPOINT_TRIM_METERS &&
                total - it.distanceFromStart >= ENDPOINT_TRIM_METERS
        }.takeIf { fixes ->
            fixes.size >= 2 && fixes.last().distanceFromStart > fixes.first().distanceFromStart
        }
    }
    if (retained.isEmpty()) {
        return OsmGpxPreparation.Blocked(OsmGpxBlockedReason.NO_DRAWABLE_SEGMENT, total)
    }
    return OsmGpxPreparation.Ready(PreparedOsmGpxTrace.create(
        journeyId = journey.id,
        segments = retained.map { segment -> segment.map { it.point } },
        measuredDistanceMeters = total,
        distanceMeters = retained.sumOf { it.last().distanceFromStart - it.first().distanceFromStart },
        removedStartMeters = retained.first().first().distanceFromStart,
        removedEndMeters = total - retained.last().last().distanceFromStart,
    ))
}
