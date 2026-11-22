package org.iz.navigation.tracking

import org.iz.navigation.navigation.JourneyNavigationCoordinator
import org.iz.navigation.navigation.NavigationFix

/** The first sample after a display/source change must have been measured during this registration. */
internal class CarMapFixWindow(private val startedNanos: Long) {
    private var lastNanos = Long.MIN_VALUE

    fun accept(fix: NavigationFix, measuredNanos: Long, now: Long): Long? {
        if (measuredNanos < startedNanos || measuredNanos <= lastNanos ||
            !JourneyNavigationCoordinator.fresh(fix, now)) return null
        lastNanos = measuredNanos
        return JourneyNavigationCoordinator.FIX_MAX_AGE_MS + 1L - (now - fix.recordedAt)
    }
}
