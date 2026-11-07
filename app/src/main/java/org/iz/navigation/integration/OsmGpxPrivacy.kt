package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections

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
    val segments: List<List<OsmGpxPoint>>,
    val measuredDistanceMeters: Double,
    val distanceMeters: Double,
    val removedStartMeters: Double,
    val removedEndMeters: Double,
    val gpxXml: String,
    val sha256: String,
) {
    val pointCount: Int = segments.sumOf { it.size }
    val fileName: String = "iz-gps-${sha256.take(24)}.gpx"

    companion object {
        internal fun create(
            journeyId: String,
            segments: List<List<TrackPoint>>,
            measuredDistanceMeters: Double,
            distanceMeters: Double,
            removedStartMeters: Double,
            removedEndMeters: Double,
        ): PreparedOsmGpxTrace {
            val frozen = Collections.unmodifiableList(segments.map { segment ->
                Collections.unmodifiableList(segment.map { OsmGpxPoint(it.latitude, it.longitude, it.recordedAt) })
            })
            val xml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                append("<gpx version=\"1.1\" creator=\"İz\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk>\n")
                frozen.forEach { segment ->
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
            val digest = MessageDigest.getInstance("SHA-256").digest(xml.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return PreparedOsmGpxTrace(journeyId, frozen, measuredDistanceMeters, distanceMeters,
                removedStartMeters, removedEndMeters, xml, digest)
        }
    }
}

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
