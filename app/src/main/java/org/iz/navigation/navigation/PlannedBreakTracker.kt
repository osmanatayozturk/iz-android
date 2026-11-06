package org.iz.navigation.navigation

import org.iz.navigation.data.Transport
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.WeatherEngine
import kotlin.math.abs
import kotlin.math.max

/** Bounded, session-local detector. Stop occurrences are route indices, never names or coordinates. */
internal class PlannedBreakTracker(private val route: PlannedRoute) {
    enum class Change { NONE, STARTED, RESUMED }
    private data class Candidate(val index: Int, val first: NavigationFix, var last: NavigationFix, var count: Int)
    private data class Active(val index: Int, val first: NavigationFix, var elapsed: Double = 0.0)
    private data class Movement(val first: NavigationFix, var last: NavigationFix)
    private val consumed = mutableSetOf<Int>()
    private var candidate: Candidate? = null
    private var active: Active? = null
    private var movement: Movement? = null
    private var lastFixAt = Long.MIN_VALUE
    private val profile = when (route.transport) {
        Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER -> Triple(.8, 2.0, 25.0)
        Transport.BICYCLE -> Triple(.6, 1.2, 20.0)
        else -> Triple(.4, .7, 12.0)
    }

    fun gpsUnavailable() { candidate = null; movement = null }

    fun snapshot(now: Long): PlannedBreakState? = active?.let {
        it.elapsed = max(it.elapsed, ((now - it.first.recordedAt).coerceAtLeast(0) / 1000.0))
        val stop = route.stops[it.index]
        PlannedBreakState(it.index, stop.label, stop.plannedBreakSeconds, it.first.recordedAt, it.elapsed,
            (stop.plannedBreakSeconds - it.elapsed).coerceAtLeast(0.0))
    }

    fun remaining(now: Long): Map<Int, Double> {
        val current = snapshot(now)
        return (1 until route.stops.lastIndex).filter { it !in consumed && route.stops[it].plannedBreakSeconds > 0 }
            .associateWith { index ->
                if (current?.stopIndex == index) current.remainingSeconds else route.stops[index].plannedBreakSeconds.toDouble()
            }
    }

    fun retainedIndices(indices: List<Int>): List<Int> =
        (indices + listOfNotNull(candidate?.index, active?.index)).distinct().sorted()

    fun stopForRequest(index: Int) = route.stops[index].let { if (index in consumed) it.copy(plannedBreakSeconds = 0) else it }

    fun finish(): Boolean {
        val current = active ?: return false
        consumed += current.index
        active = null; candidate = null; movement = null
        return true
    }

    fun cancelCandidate() { candidate = null; movement = null }

    fun update(fix: NavigationFix, now: Long, distanceToStop: (Int) -> Double?, offRoute: Boolean = false): Change {
        if (now - fix.recordedAt !in 0..10_000 || !fix.accuracyMeters.isFinite() ||
            fix.accuracyMeters !in 0f..20f || fix.recordedAt <= lastFixAt) {
            if (fix.recordedAt > lastFixAt) gpsUnavailable()
            return Change.NONE
        }
        lastFixAt = fix.recordedAt
        // Android reports Float speeds; compare inclusive GPS thresholds at that precision.
        val speed = fix.speedMps?.takeIf { it.isFinite() && it in 0f..100f }
        val current = active
        if (current != null) {
            val displacement = WeatherEngine.distanceMeters(current.first.coordinate, fix.coordinate)
            val uncertainty = current.first.accuracyMeters.toDouble() + fix.accuracyMeters.toDouble()
            val moved = displacement >= profile.third &&
                if (speed == null) displacement > uncertainty else speed >= profile.second.toFloat()
            if (!moved) { movement = null; return Change.NONE }
            val previous = movement
            if (previous == null || fix.recordedAt - previous.last.recordedAt > 10_000)
                movement = Movement(fix, fix)
            else previous.last = fix
            val window = requireNotNull(movement)
            val seconds = (fix.recordedAt - window.first.recordedAt) / 1000.0
            if (seconds >= 5.0) {
                val windowDistance = WeatherEngine.distanceMeters(window.first.coordinate, fix.coordinate)
                val windowUncertainty = window.first.accuracyMeters.toDouble() + fix.accuracyMeters.toDouble()
                if (speed != null || windowDistance > windowUncertainty && windowDistance / seconds >= profile.second) {
                    finish()
                    return Change.RESUMED
                }
                movement = Movement(fix, fix)
            }
            return Change.NONE
        }
        if (offRoute) { cancelCandidate(); return Change.NONE }
        // Do not use remainingStopIndices(): it drops a via five metres before confirmation can finish.
        for (index in 1 until route.stops.lastIndex) {
            if (index !in consumed && route.stops[index].plannedBreakSeconds > 0) {
                val along = distanceToStop(index)
                if (along != null && along < -40.0 &&
                    WeatherEngine.distanceMeters(route.stops[index].coordinate, fix.coordinate) > 40.0) {
                    consumed += index
                    if (candidate?.index == index) candidate = null
                }
            }
        }
        val index = (1 until route.stops.lastIndex).firstOrNull {
            it !in consumed && route.stops[it].plannedBreakSeconds > 0
        } ?: return Change.NONE
        val along = distanceToStop(index)
        val distance = WeatherEngine.distanceMeters(route.stops[index].coordinate, fix.coordinate)
        if (along == null || abs(along) > 40.0 || distance > 40.0 || speed != null && speed > profile.first.toFloat()) {
            cancelCandidate(); return Change.NONE
        }
        val previous = candidate
        val uncertainMotion = previous != null && WeatherEngine.distanceMeters(previous.first.coordinate, fix.coordinate) >
            max(3.0, minOf(8.0, previous.first.accuracyMeters.toDouble(), fix.accuracyMeters.toDouble()))
        if (previous == null || previous.index != index || fix.recordedAt - previous.last.recordedAt > 30_000 || uncertainMotion) {
            candidate = Candidate(index, fix, fix, 1)
            return Change.NONE
        }
        previous.last = fix; previous.count++
        if (previous.count >= 3 && fix.recordedAt - previous.first.recordedAt >= 45_000) {
            active = Active(index, previous.first)
            candidate = null; movement = null
            return Change.STARTED
        }
        return Change.NONE
    }
}
