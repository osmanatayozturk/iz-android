package org.iz.navigation.navigation

import kotlin.math.abs
import kotlin.math.exp

/** Shared safety decision; a surface must also flatten while GPS following is suspended. */
internal fun DriveCameraViewport.hasSafeAdaptiveArea(): Boolean = known &&
    leftPx >= 0 && topPx >= 0 && rightPx >= 0 && bottomPx >= 0 &&
    widthPx.toLong() - leftPx - rightPx >= 160L &&
    heightPx.toLong() - topPx - bottomPx >= 160L

/** Pure camera targets: callers own follow gestures, lifecycle and persistent padding reset. */
class DriveCameraPolicy {
    private var emittedAt: Long? = null
    private var previous: DriveCameraTarget? = null

    fun update(input: DriveCameraInput, viewport: DriveCameraViewport, force: Boolean = false): DriveCameraTarget? {
        val coordinate = input.coordinate ?: return null
        if (!input.enabled || !input.fresh || input.nowMillis < 0 ||
            viewport.widthPx <= 0 || viewport.heightPx <= 0) return null
        val lastAt = emittedAt
        if (lastAt != null && input.nowMillis < lastAt) return null
        if (!force && lastAt != null && input.nowMillis - lastAt < 1000L) return null

        val insetsValid = viewport.leftPx >= 0 && viewport.topPx >= 0 && viewport.rightPx >= 0 && viewport.bottomPx >= 0 &&
            viewport.leftPx.toLong() + viewport.rightPx < viewport.widthPx &&
            viewport.topPx.toLong() + viewport.bottomPx < viewport.heightPx
        // Covered/invalid extents are not an empty set of overlays: keep the native position.
        if (!insetsValid) return null
        val base = listOf(viewport.leftPx.toDouble(), viewport.topPx.toDouble(),
            viewport.rightPx.toDouble(), viewport.bottomPx.toDouble())
        val safeHeight = viewport.heightPx - base[1] - base[3]
        // At less than 160px usable extent, a forward anchor leaves too little map to read safely.
        val adaptive = input.mode == DriveCameraMode.ADAPTIVE && viewport.hasSafeAdaptiveArea()
        val padding = if (adaptive) listOf(base[0], base[1] + .3 * safeHeight, base[2], base[3]) else base
        val speed = input.speedKmh?.takeIf { it.isFinite() && it >= 0.0 }
        val prior = previous
        val closeTurn = input.maneuverMeters?.let { it.isFinite() && it in 0.0..100.0 } == true ||
            input.maneuverSeconds?.let { it.isFinite() && it in 0.0..8.0 } == true
        val desiredZoom = if (closeTurn) 17.5 else speed?.let(::speedZoom) ?: prior?.zoom ?: 17.5
        val alpha = if (prior == null || lastAt == null) 1.0 else
            1.0 - exp(-(input.nowMillis - lastAt).toDouble() / 2000.0)
        val zoom = prior?.let { it.zoom + (desiredZoom - it.zoom) * alpha } ?: desiredZoom
        // A GNSS course at standstill is not a heading reference; retain the previous course instead.
        val movingBearing = input.bearingDegrees?.takeIf { adaptive && speed != null && speed >= 3.0 && it.isFinite() }
        val desiredBearing = movingBearing?.let(::normalize) ?: prior?.bearing ?: 0.0
        val bearing = when {
            !adaptive -> 0.0
            prior == null -> desiredBearing
            else -> normalize(prior.bearing + shortestAngle(prior.bearing, desiredBearing) * alpha)
        }
        val target = DriveCameraTarget(coordinate, zoom, bearing, if (adaptive) 45.0 else 0.0, padding)
        if (!force && prior != null && target.coordinate == prior.coordinate && target.padding == prior.padding &&
            abs(target.zoom - prior.zoom) < 1e-6 && abs(shortestAngle(target.bearing, prior.bearing)) < 1e-6 &&
            target.pitch == prior.pitch) return null
        previous = target
        emittedAt = input.nowMillis
        return target
    }

    fun reset() { emittedAt = null; previous = null }

    private fun speedZoom(speed: Double): Double = when {
        speed <= 30.0 -> 17.5 - .5 * speed / 30.0
        speed <= 60.0 -> 17.0 - .7 * (speed - 30.0) / 30.0
        speed <= 100.0 -> 16.3 - .8 * (speed - 60.0) / 40.0
        else -> 15.5
    }

    private fun normalize(angle: Double): Double = ((angle % 360.0) + 360.0) % 360.0
    private fun shortestAngle(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0
}
