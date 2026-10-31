package org.iz.navigation.ui

internal data class DashboardOwner(val entryKey: String, val sessionId: String?)
internal data class DashboardRemaining(val meters: Double, val seconds: Double)

internal fun dashboardOwnerCurrent(expected: DashboardOwner, current: DashboardOwner): Boolean = expected == current

internal fun dashboardWeatherOwner(sessionId: String?, journeyId: String?, recording: Boolean, simulation: Boolean): String? =
    if (simulation) null else sessionId ?: journeyId?.takeIf { recording }

internal fun dashboardRemaining(guidance: Boolean, hasRoute: Boolean, gpsStale: Boolean,
    meters: Double?, seconds: Double?): DashboardRemaining? {
    if (!guidance || !hasRoute || gpsStale || meters == null || seconds == null ||
        !meters.isFinite() || !seconds.isFinite() || meters < 0.0 || seconds < 0.0) return null
    return DashboardRemaining(meters, seconds)
}
