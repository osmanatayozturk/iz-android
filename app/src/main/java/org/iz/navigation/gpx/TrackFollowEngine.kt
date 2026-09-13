package org.iz.navigation.gpx

import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import kotlin.math.*

data class TrackFollowSelection(val segmentIndex: Int = 0, val reversed: Boolean = false, val startPointIndex: Int = 0, val startFraction: Double = 0.0)
enum class TrackFollowStatus { WAITING_FOR_GPS, TRACKING, OFF_TRACK, NEEDS_START_POINT, SEGMENT_COMPLETE }
data class TrackFollowProgress(val travelledMeters: Double, val remainingMeters: Double,
    val distanceFromLineMeters: Double?, val status: TrackFollowStatus)
data class TrackFollowState(val track: ImportedTrack, val selection: TrackFollowSelection,
    val progress: TrackFollowProgress)

/** Indices refer to the displayed direction; reversing resets the UI's start selection. */
fun selectedTrackPoints(track: ImportedTrack, selection: TrackFollowSelection): List<WeatherCoordinate> {
    require(selection.segmentIndex in track.segments.indices) { "GPX bölümünü seç." }
    val original = track.segments[selection.segmentIndex].points
    val ordered = if (selection.reversed) original.asReversed() else original
    require(selection.startPointIndex in 0 until ordered.lastIndex) { "Bitişten önce bir başlangıç noktası seç." }
    require(selection.startFraction.isFinite() && selection.startFraction in 0.0..1.0) { "Başlangıç noktası geçersiz." }
    val index = selection.startPointIndex
    val values = if (selection.startFraction == 0.0) ordered.subList(index, ordered.size) else {
        val a = ordered[index]; val b = ordered[index + 1]
        val fraction = selection.startFraction
        val longitudeDelta = ((b.longitude - a.longitude + 540) % 360) - 180
        val first = if (fraction == 1.0) b else WeatherCoordinate(a.latitude + (b.latitude - a.latitude) * fraction,
            ((a.longitude + longitudeDelta * fraction + 540) % 360) - 180)
        listOf(first) + ordered.subList(index + 1, ordered.size).dropWhile { it == first }
    }
    require(values.size >= 2 && values.any { it != values.first() }) { "Bitişten önce bir başlangıç noktası seç." }
    return values
}

/** An ordered line follower, deliberately without routing, ETA or diary-writing behavior. */
class TrackFollowEngine(points: List<WeatherCoordinate>) {
    private val points = points.toList()
    private val distances: DoubleArray
    private var travelled = 0.0
    private var lastTimestamp: Long? = null
    private var lastMatchedTimestamp: Long? = null
    private var requiresSelection = false
    private var endFixes = 0
    val initial: TrackFollowProgress

    init {
        require(points.size in 2..GpxImporter.MAX_POINTS && points.all(::validCoordinate)) { "GPX noktaları geçersiz." }
        distances = DoubleArray(points.size)
        for (i in 1 until points.size) distances[i] = distances[i - 1] + WeatherEngine.distanceMeters(points[i - 1], points[i])
        require(distances.last().isFinite() && distances.last() > 0.0) { "GPX bölümü boş." }
        initial = TrackFollowProgress(0.0, distances.last(), null, TrackFollowStatus.WAITING_FOR_GPS)
    }

    fun update(fix: NavigationFix, now: Long): TrackFollowProgress? {
        if (!validCoordinate(fix.coordinate) || fix.recordedAt < 0 || fix.recordedAt > now || now - fix.recordedAt > 10_000 ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters !in 0f..50f ||
            lastTimestamp?.let { fix.recordedAt <= it } == true) return null
        lastTimestamp = fix.recordedAt
        val accuracy = fix.accuracyMeters.toDouble().coerceAtLeast(1.0)
        val seconds = lastMatchedTimestamp?.let { (fix.recordedAt - it) / 1000.0 }
        val reconnecting = seconds != null && seconds > 30.0
        val speed = fix.speedMps?.takeIf { it.isFinite() && it in 0f..60f }?.toDouble() ?: 2.0
        val forwardWindow = if (seconds == null || reconnecting) 50.0 else
            (seconds * max(2.0, speed) * 1.5 + accuracy * 2).coerceIn(30.0, 500.0)
        val lower = (travelled - 15.0).coerceAtLeast(0.0)
        val upper = minOf(travelled + forwardWindow, distances.last())
        val threshold = max(25.0, accuracy * 2)
        var lineDistance = Double.POSITIVE_INFINITY
        var distantBranchOnReconnect = false
        val nearby = mutableListOf<Projection>()
        for (i in 0 until points.lastIndex) {
            val projected = project(i, fix.coordinate)
            lineDistance = minOf(lineDistance, projected.distance)
            if (projected.along in lower..upper) nearby += projected
            if (reconnecting && projected.distance <= accuracy + 10 && abs(projected.along - travelled) > 80.0)
                distantBranchOnReconnect = true
        }
        if (distantBranchOnReconnect) requiresSelection = true
        if (requiresSelection) return result(lineDistance, TrackFollowStatus.NEEDS_START_POINT)
        val closest = nearby.minOfOrNull { it.distance } ?: Double.POSITIVE_INFINITY
        // At indistinguishable crossings keep the earliest reachable branch in traversal order.
        val selected = nearby.filter { it.distance <= closest + accuracy }.minByOrNull { it.along }
        if (selected == null || selected.distance > threshold) {
            endFixes = 0
            if (lineDistance <= threshold) {
                requiresSelection = true
                return result(lineDistance, TrackFollowStatus.NEEDS_START_POINT)
            }
            return result(lineDistance, TrackFollowStatus.OFF_TRACK)
        }
        travelled = max(travelled, selected.along)
        lastMatchedTimestamp = fix.recordedAt
        val nearEnd = distances.last() - travelled <= 10.0 && WeatherEngine.distanceMeters(fix.coordinate, points.last()) <= 15.0
        endFixes = if (nearEnd) endFixes + 1 else 0
        return result(lineDistance, if (endFixes >= 3) TrackFollowStatus.SEGMENT_COMPLETE else TrackFollowStatus.TRACKING)
    }

    private fun result(distance: Double, status: TrackFollowStatus) = TrackFollowProgress(
        travelled, (distances.last() - travelled).coerceAtLeast(0.0), distance, status)
    private data class Projection(val along: Double, val distance: Double)
    private fun project(index: Int, point: WeatherCoordinate): Projection {
        val a = points[index]
        val b = points[index + 1]
        val scale = cos(Math.toRadians((a.latitude + b.latitude) / 2)).coerceAtLeast(.000001)
        fun lonDelta(longitude: Double) = ((longitude - a.longitude + 540) % 360) - 180
        val dx = lonDelta(b.longitude) * scale
        val dy = b.latitude - a.latitude
        val denominator = dx * dx + dy * dy
        val fraction = (if (denominator > 0) (lonDelta(point.longitude) * scale * dx + (point.latitude - a.latitude) * dy) / denominator else 0.0)
            .coerceIn(0.0, 1.0)
        val projected = WeatherCoordinate(a.latitude + dy * fraction, ((a.longitude + lonDelta(b.longitude) * fraction + 540) % 360) - 180)
        return Projection(distances[index] + (distances[index + 1] - distances[index]) * fraction,
            WeatherEngine.distanceMeters(point, projected))
    }
}

internal fun validCoordinate(point: WeatherCoordinate): Boolean = point.latitude.isFinite() && point.longitude.isFinite() &&
    point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
