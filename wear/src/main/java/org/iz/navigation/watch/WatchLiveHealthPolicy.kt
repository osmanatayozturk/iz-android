package org.iz.navigation.watch

/** The memory-only cache dies with the health service/process; it is never a sensor subscription. */
internal object WatchLiveHealthPolicy {
    fun heart(health: WatchLiveHealthState, phone: String?, journey: String?, session: String?, now: Long): Double? =
        health.latestHeartRate.takeIf { health.armed && health.capturing && health.onBody == true &&
            phone != null && journey != null && session != null && health.phoneId == phone &&
            health.journeyId == journey && health.sessionId == session &&
            health.latestHeartAt?.let { now - it in 0..30_000 } == true }
}
