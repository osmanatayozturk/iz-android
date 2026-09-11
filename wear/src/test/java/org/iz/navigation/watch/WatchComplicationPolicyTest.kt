package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchComplicationPolicyTest {
    private val now = 1_000_000L
    private fun nav() = WearNavigationSummary("n", true, now, false, false, false, false, false,
        10, "Sağa dön", 150.0, 2200.0, now + 600000, "Ev")
    @Test fun frozenReportedEtaSurvivesShortGpsWindowButExpiresAtFiveMinutes() {
        val snapshot = WearSnapshot(now, navigation = nav())
        val original = WatchSurfacePolicy.complication(snapshot, true, now)
        assertNotEquals("İz’i aç", original.compact)
        assertEquals(original.compact, WatchSurfacePolicy.complication(snapshot, false, now + 299999).compact)
        assertEquals("İz’i aç", WatchSurfacePolicy.complication(snapshot, false, now + 300000).compact)
        assertEquals("İz’i aç", WatchSurfacePolicy.frame(snapshot, false, now + 60000).compact)
    }
    @Test fun staleGpsAndTerminalReportImmediatelyInvalidatePreviousEta() {
        for (nav in listOf(nav().copy(gpsStale = true), nav().copy(arrived = true), nav().copy(offRoute = true), nav().copy(simulation = true))) {
            assertEquals("İz’i aç", WatchSurfacePolicy.complication(WearSnapshot(now, navigation = nav), true, now).compact)
        }
    }
    @Test fun knownPhoneManeuverTypesAreDirectional() {
        assertEquals("→", WatchSurfacePolicy.turn(10))
        assertEquals("←", WatchSurfacePolicy.turn(15))
        assertEquals("↑", WatchSurfacePolicy.turn(8))
    }
}
