package org.iz.navigation.data

import java.security.MessageDigest
import kotlin.math.*

/** Recorded heading changes only. These values do not estimate lean, road radius or accuracy. */
enum class RecordedCurveDirection { LEFT, RIGHT }
data class RecordedCurveEndpoint(val pointId: String, val at: Long, val latitude: Double, val longitude: Double)
data class RecordedCurveDetail(
    val id: String, val journeyId: String, val sourceFingerprint: String, val algorithmVersion: Int,
    val start: RecordedCurveEndpoint, val end: RecordedCurveEndpoint, val direction: RecordedCurveDirection,
    val pointCount: Int, val recordedDistanceMeters: Double,
    val sampledMinSpeedKmh: Double?, val sampledMaxSpeedKmh: Double?,
)
data class RecordedCurveDetails(
    val journeyId: String, val sourceFingerprint: String, val algorithmVersion: Int,
    val curves: List<RecordedCurveDetail>, val usablePointCount: Int, val excludedIntervalCount: Int,
    val truncated: Boolean = false,
)
object RecordedCurves {
    const val ALGORITHM_VERSION = 1
    const val MAX_CURVES = 4096
    private data class Leg(val a: TrackPoint, val b: TrackPoint, val distance: Double, val bearing: Double)

    fun prepare(journey: Journey, points: List<TrackPoint>, fingerprint: String,
        checkActive: () -> Unit = {}): RecordedCurveDetails {
        checkActive()
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        val end = journey.endedAt
        if (journey.status != JourneyStatus.CONFIRMED || end == null || end < journey.startedAt || journey.startedAt < 0)
            return RecordedCurveDetails(journey.id, fingerprint, ALGORITHM_VERSION, emptyList(), 0, 0)
        var work = 0
        fun checkpoint() { if (++work % 256 == 0) checkActive() }
        fun usable(p: TrackPoint) = p.id.isNotBlank() && p.id.length <= 200 &&
            DiaryRules.isUsablePoint(p) && p.accuracy <= 25f
        val route = points.asSequence().onEach { checkpoint() }
            .filter { it.journeyId == journey.id && it.recordedAt in journey.startedAt..end }
            .map { if (it.speed?.let { speed -> speed.isFinite() && speed > 100f } == true) it.copy(speed = null) else it }
            // Invalid points remain barriers. They must never be removed before interval matching.
            .sortedWith { a, b -> checkpoint(); val time = a.recordedAt.compareTo(b.recordedAt)
                if (time != 0) time else a.id.compareTo(b.id) }.toList()
        val usableCount = route.count { checkpoint(); usable(it) }
        val curves = mutableListOf<RecordedCurveDetail>()
        val candidate = mutableListOf<TrackPoint>()
        var directionChange = 0.0
        var distance = 0.0
        var excluded = 0
        var truncated = false
        fun flush() {
            if (candidate.size >= 4 && candidate.last().recordedAt - candidate.first().recordedAt >= 3000 &&
                distance >= 20 && abs(directionChange) >= 30) {
                if (curves.size < MAX_CURVES) {
                    val first = candidate.first(); val last = candidate.last()
                    val direction = if (directionChange > 0) RecordedCurveDirection.RIGHT else RecordedCurveDirection.LEFT
                    val key = listOf(journey.id, fingerprint, ALGORITHM_VERSION.toString(), first.id,
                        first.recordedAt.toString(), last.id, last.recordedAt.toString(), direction.name)
                    val hash = MessageDigest.getInstance("SHA-256")
                    key.forEach { value -> val bytes = value.toByteArray(Charsets.UTF_8)
                        hash.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); hash.update(0.toByte()); hash.update(bytes) }
                    val speeds = candidate.mapNotNull { it.speed?.takeIf { v -> v.isFinite() && v in 0f..100f }?.toDouble()?.times(3.6) }
                    curves += RecordedCurveDetail(hash.digest().joinToString("") { "%02x".format(it) },
                        journey.id, fingerprint, ALGORITHM_VERSION, first.endpoint(), last.endpoint(), direction,
                        candidate.size, distance, speeds.minOrNull(), speeds.maxOrNull())
                } else truncated = true
            }
            candidate.clear(); directionChange = 0.0; distance = 0.0
        }
        var previous: Leg? = null
        for (index in 1 until route.size) {
            checkpoint()
            val a = route[index - 1]; val b = route[index]
            val seconds = b.recordedAt - a.recordedAt
            val meters = if (usable(a) && usable(b)) DiaryRules.metersBetween(a, b) else Double.NaN
            if (!usable(a) || !usable(b) || seconds !in 1..15_000 || !DiaryRules.connects(a, b) ||
                !meters.isFinite() || meters < max(10.0, 2.0 * max(a.accuracy, b.accuracy))) {
                excluded++; flush(); previous = null; continue
            }
            val leg = Leg(a, b, meters, bearing(a, b))
            val before = previous
            if (before == null) { previous = leg; continue }
            val delta = ((leg.bearing - before.bearing + 540) % 360) - 180
            if (abs(delta) < 5 || abs(delta) > 120 ||
                candidate.isNotEmpty() && delta * directionChange < 0) {
                // A new direction starts after the previous emitted interval, never overlaps it.
                flush(); previous = leg; continue
            }
            if (candidate.isEmpty()) {
                candidate += before.a; candidate += before.b; candidate += leg.b
                distance = before.distance + leg.distance
                directionChange = delta
            } else {
                candidate += leg.b; distance += leg.distance; directionChange += delta
            }
            if (candidate.size >= 512 || candidate.last().recordedAt - candidate.first().recordedAt >= 120_000) {
                truncated = true; flush(); previous = null
            } else previous = leg
        }
        flush(); checkActive()
        return RecordedCurveDetails(journey.id, fingerprint, ALGORITHM_VERSION, curves, usableCount, excluded, truncated)
    }
    private fun TrackPoint.endpoint() = RecordedCurveEndpoint(id, recordedAt, latitude, longitude)
    private fun bearing(a: TrackPoint, b: TrackPoint): Double {
        val x = Math.toRadians(a.latitude); val y = Math.toRadians(b.latitude)
        val longitude = Math.toRadians(((b.longitude - a.longitude + 540) % 360) - 180)
        return (Math.toDegrees(atan2(sin(longitude) * cos(y),
            cos(x) * sin(y) - sin(x) * cos(y) * cos(longitude))) + 360) % 360
    }
}