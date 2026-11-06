package org.iz.navigation.navigation

/** Valid proximity to a directional maneuver in the route's normalized type vocabulary. */
internal data class DriveCameraTurn(val meters: Double?, val seconds: Double?)

internal fun NavigationState.driveCameraTurn(): DriveCameraTurn? {
    if (!guidance || arrived) return null
    val route = route ?: return null
    val progress = progress ?: return null
    if (progress.arrived || progress.offRoute) return null
    val step = route.maneuvers.getOrNull(progress.maneuverIndex) ?: return null
    // Valhalla types are retained; TomTomRoutePlanner maps provider codes to this vocabulary.
    // Continue/keep/merge, name changes and arrival must not change the speed-based camera.
    if (step.type !in 9..16 && step.type !in 18..21 && step.type !in 26..27) return null
    // Each hint is independent: the engine may retain a turn just beyond its time anchor.
    val meters = progress.nextManeuverDistanceMeters.takeIf { it.isFinite() && it >= 0.0 }
    val elapsed = progress.elapsedSeconds.takeIf { it.isFinite() && it >= 0.0 }
    val seconds = elapsed?.let { step.beginElapsedSeconds - it }?.takeIf { it.isFinite() && it >= 0.0 }
    return if (meters == null && seconds == null) null else DriveCameraTurn(meters, seconds)
}
