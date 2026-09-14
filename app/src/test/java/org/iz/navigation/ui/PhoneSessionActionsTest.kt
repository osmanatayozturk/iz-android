@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.iz.navigation.ui

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import org.iz.navigation.data.Journey
import org.iz.navigation.navigation.NavigationState
import org.junit.Assert.*
import org.junit.Test

class PhoneSessionActionsTest {
    private val active = NavigationState(sessionId = "session-a", journey = Journey(id = "journey-a"),
        guidance = true, recording = true, sharingLocation = true, routeRevision = 7)

    @Test fun openingActivePanelPreservesPlannerAndNeverDispatchesAnEndAction() {
        listOf(active, NavigationState(recording = true), NavigationState(guidance = true),
            NavigationState(locationActive = true), NavigationState(sessionId = "free")).forEach { nav ->
            var resets = 0; var opens = 0
            openPhoneDirectionsPanel(nav, false, { resets++ }, { opens++ })
            assertEquals("Active panel must preserve current input/preview", 0, resets)
            assertEquals(1, opens)
        }
    }

    @Test fun firstIdleEntryStillPreparesPlannerButOpenPanelDoesNotResetAgain() {
        var resets = 0; var opens = 0
        openPhoneDirectionsPanel(NavigationState(), false, { resets++ }, { opens++ })
        openPhoneDirectionsPanel(NavigationState(), true, { resets++ }, { opens++ })
        assertEquals(1, resets); assertEquals(2, opens)
    }

    @Test fun requestsExistOnlyForTheirActiveWorkAndIdentifiedSession() {
        assertNull(phoneSessionEndRequest(PhoneSessionEnd.GUIDANCE, active.copy(guidance = false)))
        assertNull(phoneSessionEndRequest(PhoneSessionEnd.RECORDING, active.copy(recording = false)))
        assertNull(phoneSessionEndRequest(PhoneSessionEnd.RECORDING, active.copy(journey = null)))
        assertNull(phoneSessionEndRequest(PhoneSessionEnd.RECORDING, active.copy(simulation = true)))
        assertNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, active.copy(sessionId = null)))
        assertNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, NavigationState(sessionId = "idle-session")))
    }

    @Test fun eachConfirmedActionDispatchesOnlyItsOwnCapturedTarget() = runTest {
        PhoneSessionEnd.entries.forEach { action ->
            val request = requireNotNull(phoneSessionEndRequest(action, active))
            val calls = mutableListOf<String>()
            assertTrue(dispatchPhoneSessionEnd(request, active, false,
                { token -> assertSame(request.snapshot, token); calls += "guidance:${token.sessionId}:${token.routeRevision}"; true },
                { token -> assertSame(request.snapshot, token); calls += "recording:${token.journeyId}"; true },
                { token -> assertSame(request.snapshot, token); calls += "all:${token.sessionId}"; true }))
            assertEquals(listOf(when (action) {
                PhoneSessionEnd.GUIDANCE -> "guidance:session-a:7"
                PhoneSessionEnd.RECORDING -> "recording:journey-a"
                PhoneSessionEnd.ALL -> "all:session-a"
            }), calls)
        }
    }

    @Test fun staleSessionJourneyRouteOrPromisedWorkRejectsEveryMutation() = runTest {
        val changed = listOf(active.copy(sessionId = "session-b"), active.copy(journey = Journey(id = "journey-b")),
            active.copy(routeRevision = 8), active.copy(guidance = false), active.copy(recording = false),
            active.copy(sharingLocation = false), active.copy(simulation = true))
        PhoneSessionEnd.entries.forEach { action ->
            val request = requireNotNull(phoneSessionEndRequest(action, active))
            changed.forEach { now ->
                var calls = 0
                assertFalse("Stale $action request must be rejected", dispatchPhoneSessionEnd(request, now, false,
                    { calls++; true }, { calls++; true }, { calls++; true }))
                assertEquals(0, calls)
            }
        }
    }

    @Test fun busyOrStartingDoesNotDispatch() = runTest {
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, active))
        assertFalse(dispatchPhoneSessionEnd(request, active, true,
            { error("locked guidance") }, { error("locked recording") }, { error("locked all") }))
    }

    @Test fun rejectedActionsAreNotReportedAsSuccessAndErrorsReachCaller() = runTest {
        PhoneSessionEnd.entries.forEach { action ->
            val request = requireNotNull(phoneSessionEndRequest(action, active))
            assertFalse(dispatchPhoneSessionEnd(request, active, false, { false }, { false }, { false }))
            val failure = IllegalStateException("synthetic failure")
            try {
                dispatchPhoneSessionEnd(request, active, false, { throw failure }, { throw failure }, { throw failure })
                fail("Error must propagate to the phone command handler")
            } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        }
    }

    @Test fun progressMessagesAndMuteDoNotInvalidateTheConfirmedWork() {
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, active))
        assertTrue(request.matches(active.copy(muted = true, message = "synthetic progress", gpsStale = true)))
    }

    @Test fun awaitingConfirmedCommandLocksDuplicatesAndReportsFailureBeforeUnlocking() = runTest {
        val result = CompletableDeferred<Boolean>()
        val request = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.GUIDANCE, active))
        var busy = false; var calls = 0; var deliveredError: Exception? = null
        val work = PhoneNavigationWork(this, { busy = it }, { deliveredError = it })
        fun submit() = work.mutate {
            dispatchPhoneSessionEnd(request, active, false,
                { calls++; result.await() }, { error("wrong recording action") }, { error("wrong full action") })
        }
        submit(); submit(); runCurrent()
        assertTrue(busy); assertEquals(1, calls); assertNull(deliveredError)
        val failure = IllegalStateException("synthetic command failure")
        result.completeExceptionally(failure); runCurrent()
        // Suspension can recover a stack trace into an equivalent exception instance.
        assertEquals(failure.javaClass, deliveredError?.javaClass)
        assertEquals(failure.message, deliveredError?.message)
        assertFalse(busy); assertEquals(1, calls)
    }
    @Test fun approvedConfirmationCopyMentionsOnlyCurrentlyActiveWork() {
        for (guidance in listOf(false, true)) for (recording in listOf(false, true)) for (sharing in listOf(false, true)) {
            val nav = active.copy(guidance = guidance, recording = recording, sharingLocation = sharing)
            val all = requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
            val expected = buildList {
                if (guidance) add("Yol tarifi bitecek.")
                if (recording) add("Yolculuk kaydı bitecek.")
                if (sharing) add("Grup paylaşımı bitecek.")
                if (isEmpty()) add("Yolculuk oturumu sonlandırılacak.")
            }.joinToString(" ")
            assertEquals(expected, all.explanation)
            if (guidance) assertEquals("Yol tarifi bitecek." +
                (if (recording) " Yolculuk kaydı devam edecek." else "") +
                (if (sharing) " Grup paylaşımı devam edecek." else ""),
                requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.GUIDANCE, nav)).explanation)
            if (recording) assertEquals("Yolculuk kaydı bitecek." +
                (if (guidance) " Yol tarifi devam edecek." else "") +
                (if (sharing) " Grup paylaşımı devam edecek." else ""),
                requireNotNull(phoneSessionEndRequest(PhoneSessionEnd.RECORDING, nav)).explanation)
        }
    }

}
