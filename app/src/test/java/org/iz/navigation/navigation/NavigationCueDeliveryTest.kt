package org.iz.navigation.navigation

import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test

class NavigationCueDeliveryTest {
    private var now = 0L
    private val policy = NavigationCuePolicy { now }
    private val fix = NavigationFix(WeatherCoordinate(0.0, .003), 10_000, 5f, 10f, 90f)
    private val progress = NavigationProgress(30.0, 70.0, 700.0, 0.0, 0, 150.0)
    private val near = progress.copy(nextManeuverDistanceMeters = 10.0)
    private val route = PlannedRoute("cue-route",
        listOf(RouteStop("A", WeatherCoordinate(0.0, 0.0)), RouteStop("B", WeatherCoordinate(0.0, .01))),
        listOf(RouteVertex(WeatherCoordinate(0.0, 0.0), 0.0), RouteVertex(WeatherCoordinate(0.0, .01), 100.0)),
        1112.0, 100.0, 1, maneuvers = listOf(RouteManeuver(10, "", "", emptyList(), 0, 1, 0.0, 100.0)))

    @Test fun recoveryHasExactCooldownThreeAttemptLimitAndIndependentNowPhase() {
        val first = policy.offer(route, progress, fix)!!
        first.finish(CueDelivery.REJECTED)
        now = 999
        assertNull(policy.offer(route, progress, fix))
        now = 1_000
        val second = policy.offer(route, progress, fix)!!
        assertEquals(15_000L, second.expiresAtMillis)
        second.finish(CueDelivery.REJECTED)
        now = 1_999
        assertNull(policy.offer(route, progress, fix))
        now = 2_000
        policy.offer(route, progress, fix)!!.finish(CueDelivery.REJECTED)
        now = 3_000
        assertNull("A fresh fix must not admit an unbounded fourth request", policy.offer(route, progress, fix))
        val immediate = policy.offer(route, near, fix)!!
        assertEquals(18_000L, immediate.expiresAtMillis)
        immediate.finish(CueDelivery.COMPLETED)
        assertNull(policy.offer(route, progress, fix))
        assertNull(policy.offer(route, near, fix))
    }

    @Test fun recoveryNeverRenewsFirstDeadlineAndExactExpiryRejects() {
        policy.offer(route, progress, fix)!!.finish(CueDelivery.REJECTED)
        now = 14_999
        val last = policy.offer(route, progress.copy(nextManeuverDistanceMeters = 100.0), fix)!!
        assertEquals(15_000L, last.expiresAtMillis)
        last.finish(CueDelivery.REJECTED)
        now = 15_000
        assertNull(policy.offer(route, progress, fix))
        assertNotNull("New route owns a new budget", policy.offer(route.copy(id = "replacement"), progress, fix))
    }

    @Test fun rejectedNowCannotEraseCompletedAdvanceHistory() {
        policy.offer(route, progress, fix)!!.finish(CueDelivery.COMPLETED)
        policy.offer(route, near, fix)!!.finish(CueDelivery.REJECTED)
        now = 1_000
        assertNull(policy.offer(route, progress, fix))
        assertNotNull(policy.offer(route, near, fix))
    }

    @Test fun oldSameRouteReceiptCannotConsumeOrReleaseReplacementReservation() {
        val old = policy.offer(route, progress, fix)!!
        policy.reset()
        val replacement = policy.offer(route, progress, fix)!!
        old.finish(CueDelivery.EXPIRED)
        assertTrue(policy.isEligible(replacement, route, progress, fix))
        replacement.finish(CueDelivery.REJECTED)
        now = 1_000
        assertNotNull(policy.offer(route, progress, fix))
    }

    @Test fun currentEligibilityRejectsChangedPhaseRoutePoorGpsOffRouteArrivalAndExpiry() {
        val request = policy.offer(route, near, fix)!!
        assertTrue(policy.isEligible(request, route, near, fix))
        assertFalse(policy.isEligible(request, route, progress, fix))
        assertFalse(policy.isEligible(request, route.copy(id = "other"), near, fix))
        assertFalse(policy.isEligible(request, route, near.copy(offRoute = true), fix))
        assertFalse(policy.isEligible(request, route, near.copy(arrived = true), fix))
        assertFalse(policy.isEligible(request, route, near, fix.copy(accuracyMeters = 51f)))
        now = 15_000
        assertFalse(policy.isEligible(request, route, near, fix))
    }

    @Test fun cancelledAndExpiredCuesAreConsumedWithoutReplay() {
        for (terminal in listOf(CueDelivery.CANCELLED, CueDelivery.EXPIRED)) {
            policy.reset()
            policy.offer(route, near, fix)!!.finish(terminal)
            now += 1_000
            assertNull(policy.offer(route, near, fix))
            assertNull(policy.offer(route, progress, fix))
        }
    }

    @Test fun pendingDeduplicatesAndReceiptSettlesOnce() {
        val request = policy.offer(route, progress, fix)!!
        assertNull(policy.offer(route, progress, fix))
        request.finish(CueDelivery.COMPLETED)
        request.finish(CueDelivery.REJECTED)
        now = 1_000
        assertNull(policy.offer(route, progress, fix))
        val results = mutableListOf<CueDelivery>()
        val receipt = NavigationCueRequest("one") { results += it }
        receipt.finish(CueDelivery.CANCELLED)
        receipt.finish(CueDelivery.COMPLETED)
        assertEquals(listOf(CueDelivery.CANCELLED), results)
        assertFalse(receipt.isCurrent())
    }
}
