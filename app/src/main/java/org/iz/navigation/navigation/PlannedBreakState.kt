package org.iz.navigation.navigation

/** Ephemeral active break; route/session ownership is supplied by NavigationState. Never resumes on restore. */
data class PlannedBreakState(
    val stopIndex: Int,
    val stopLabel: String,
    val plannedSeconds: Int,
    val startedAt: Long,
    val elapsedSeconds: Double,
    val remainingSeconds: Double,
)
