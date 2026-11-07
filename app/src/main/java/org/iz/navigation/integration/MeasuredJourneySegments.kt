package org.iz.navigation.integration

import org.iz.navigation.data.DiaryRules
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.TrackPoint

/** Actual recorded fixes only; invalid fixes, explicit breaks and time/speed gaps split runs. */
internal fun measuredJourneySegments(journey: Journey, points: List<TrackPoint>): List<List<TrackPoint>> {
    val endedAt = journey.endedAt
    require(journey.status == JourneyStatus.CONFIRMED && endedAt != null && endedAt >= journey.startedAt) {
        "Yalnızca tamamlanmış ve onaylanmış yolculuklar dışa aktarılabilir."
    }
    val segments = mutableListOf<MutableList<TrackPoint>>()
    var previous: TrackPoint? = null
    points.filter { it.journeyId == journey.id }.sortedBy { it.recordedAt }.forEach { point ->
        if (!DiaryRules.isUsablePoint(point) || point.recordedAt !in journey.startedAt..endedAt) {
            previous = null
        } else {
            if (previous == null || !DiaryRules.connects(previous!!, point)) segments.add(mutableListOf())
            segments.last().add(point)
            previous = point
        }
    }
    return segments
}
