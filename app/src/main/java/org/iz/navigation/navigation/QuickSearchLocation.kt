package org.iz.navigation.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.weather.WeatherCoordinate

internal suspend fun findQuickSearchCoordinate(
    canLocate: () -> Boolean,
    hasFine: () -> Boolean,
    visible: () -> NavigationFix?,
    cached: suspend (precise: Boolean) -> NavigationFix?,
    approximate: suspend () -> NavigationFix?,
    now: () -> Long,
): WeatherCoordinate? {
    if (!canLocate()) return null
    val precise = hasFine()
    if (precise) visible()?.takeIf { usableQuickLocation(it, now()) }?.let { return it.coordinate }

    return withTimeoutOrNull(5_000) {
        val ready = tryLocation { withTimeoutOrNull(250) { cached(precise) } }
        if (!canLocate()) return@withTimeoutOrNull null
        if (ready != null && (!precise || hasFine()) && usableQuickLocation(ready, now())) {
            return@withTimeoutOrNull ready.coordinate
        }
        val current = tryLocation { approximate() }
        current?.takeIf { canLocate() && usableQuickLocation(it, now()) }?.coordinate
    }
}

private suspend fun tryLocation(read: suspend () -> NavigationFix?): NavigationFix? =
    try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

internal fun usableQuickLocation(fix: NavigationFix, now: Long): Boolean =
    now - fix.recordedAt in 0..60_000 && fix.accuracyMeters.isFinite() && fix.accuracyMeters >= 0
