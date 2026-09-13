package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import java.time.Instant

/** Endpoint trims count usable measured points, ordered by recording time. No points are invented. */
fun gpxSegments(journey: Journey, points: List<TrackPoint>, startTrim: Int = 0, endTrim: Int = 0): List<List<TrackPoint>> {
    require(journey.status == JourneyStatus.CONFIRMED && journey.endedAt != null && journey.endedAt >= journey.startedAt) { "Yalnızca tamamlanmış ve onaylanmış yolculuklar dışa aktarılabilir." }
    require(startTrim >= 0 && endTrim >= 0) { "Kırpma değeri negatif olamaz." }
    val segments = mutableListOf<MutableList<TrackPoint>>()
    var previous: TrackPoint? = null
    points.filter { it.journeyId == journey.id }.sortedBy { it.recordedAt }.forEach { point ->
        if (!DiaryRules.isUsablePoint(point) || point.recordedAt !in journey.startedAt..journey.endedAt) {
            previous = null
        } else {
            if (previous == null || !DiaryRules.connects(previous!!, point)) segments.add(mutableListOf())
            segments.last().add(point)
            previous = point
        }
    }
    val count = segments.sumOf { it.size }
    require(count.toLong() - startTrim - endTrim >= 2) { "Dışa aktarmak için kırpma sonrasında en az iki geçerli rota noktası gerekli." }
    var index = 0
    return segments.mapNotNull { segment ->
        segment.filter { index++ in startTrim until count - endTrim }.takeIf { it.isNotEmpty() }
    }
}

/** Measured geometry only by default; recording timestamps require a per-export opt-in. */
fun buildGpx(journey: Journey, points: List<TrackPoint>, startTrim: Int = 0, endTrim: Int = 0, includeTimestamps: Boolean = false): String {
    val segments = gpxSegments(journey, points, startTrim, endTrim)
    return buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"").append(xmlEscape("İz")).append("\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk>\n")
        segments.forEach { segment ->
            append("<trkseg>\n")
            segment.forEach { point ->
                append("<trkpt lat=\"").append(point.latitude).append("\" lon=\"").append(point.longitude).append("\">")
                point.altitude?.takeIf { it.isFinite() }?.let { append("<ele>").append(it).append("</ele>") }
                if (includeTimestamps) append("<time>").append(xmlEscape(Instant.ofEpochMilli(point.recordedAt).toString())).append("</time>")
                append("</trkpt>\n")
            }
            append("</trkseg>\n")
        }
        append("</trk>\n</gpx>\n")
    }
}

private fun xmlEscape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
