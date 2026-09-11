package org.iz.navigation.watch

import org.iz.navigation.wearprotocol.*
import org.junit.Assert.*
import org.junit.Test

class WatchSessionTest {
    private val now = 1_000_000L
    private val idle = WearSnapshot(generatedAt = now)
    private fun ready() = WatchUiState(phoneId = "phone", phoneName = "Telefon", snapshot = idle, phoneVersion = 2)
    private fun start() = WearCommand("request", WearAction.START, now, mode = WearMode.WALK)

    @Test fun runRequiresV2ButOldPhoneStillSupportsWalkingAndJourneyBoundStop() {
        val legacy = ready().copy(phoneVersion = 1)
        assertFalse(legacy.canStart(now, WearMode.RUN))
        assertNull(legacy.begin(start().copy(mode = WearMode.RUN), now).pending)
        assertTrue(legacy.canStart(now, WearMode.WALK))
        assertTrue(legacy.copy(snapshot = idle.copy(journeyId = "run", recording = true)).canStop("run", now))
        assertNotNull(ready().begin(start().copy(mode = WearMode.RUN), now).pending)
    }

    @Test fun versionSwitchClearsSnapshotButPendingRetryKeepsItsOriginalWireVersion() {
        val pending = ready().copy(phoneVersion = 1).begin(start(), now)
        val upgraded = pending.phone("phone", "Telefon", 2)
        assertNull(upgraded.snapshot)
        assertEquals(start(), upgraded.retryCommand(now + 1))
        assertEquals(1, upgraded.pendingVersion)
        assertEquals(2, upgraded.phoneVersion)
    }

    @Test fun paceFormattingKeepsMissingSeparateAndCarriesRoundedSeconds() {
        assertEquals("Ölçüm yok", pace(null))
        assertEquals("Ölçüm yok", pace(0.0))
        assertEquals("Ölçüm yok", pace(Double.NaN))
        assertEquals("6:00 dk/km", pace(359.8))
        assertEquals("10:05 dk/km", pace(605.0))
    }

    @Test fun disconnectedMissingAndStalePhoneStateDisableCommands() {
        assertFalse(WatchUiState().canStart(now))
        assertFalse(WatchUiState(phoneId = "phone").canStart(now))
        assertTrue(ready().canStart(now))
        assertFalse(ready().canStart(now + WearProtocol.STATE_TTL_MS + 1))
        assertFalse(ready().copy(snapshot = idle.copy(journeyId = "waiting-for-service")).canStart(now))
    }

    @Test fun sendingAStartStaysPendingUntilTheMatchingPhoneAcknowledgesIt() {
        val pending = ready().begin(start(), now)
        assertEquals("request", pending.pending?.id)
        assertFalse(pending.canStart(now))
        assertEquals(pending, pending.result("other-phone", WearResult("request", WearResultCode.STARTED, "OK", "trip")))
        assertEquals(pending, pending.result("phone", WearResult("other-request", WearResultCode.STARTED, "OK", "trip")))
        val acknowledged = pending.result("phone", WearResult("request", WearResultCode.STARTED, "Başladı", "trip"))
        assertNull(acknowledged.pending)
        assertNull(acknowledged.snapshot)
        assertFalse(acknowledged.canStart(now))
        assertEquals("Başladı", acknowledged.message)
    }

    @Test fun phonePermissionConfirmationKeepsTheCommandForALaterStartAcknowledgement() {
        val pending = ready().begin(start(), now)
            .result("phone", WearResult("request", WearResultCode.NEEDS_PHONE, "Telefondaki bildirime dokun."))
        assertTrue(pending.needsPhone)
        assertEquals("request", pending.pending?.id)
        assertFalse(pending.canStart(now))
        val started = pending.result("phone", WearResult("request", WearResultCode.STARTED, "Başladı", "trip"))
        assertFalse(started.needsPhone)
        assertNull(started.pending)
        assertEquals("Başladı", started.message)
    }

    @Test fun stopIsBoundToTheFreshJourneyShownInTheConfirmation() {
        val recording = ready().copy(snapshot = idle.copy(journeyId = "new-trip", recording = true))
        assertFalse(recording.canStop("old-trip", now))
        assertTrue(recording.canStop("new-trip", now))
        val stale = WearCommand("stop", WearAction.STOP, now, journeyId = "old-trip")
        assertNull(recording.begin(stale, now).pending)
        assertFalse(recording.canStop("new-trip", now + WearProtocol.STATE_TTL_MS + 1))
    }

    @Test fun snapshotsFromOtherPhonesOrOlderThanTheLatestOneCannotReplaceLiveData() {
        val state = ready()
        assertEquals(state, state.receive("other-phone", idle.copy(distanceMeters = 99.0), now))
        assertEquals(state, state.receive("phone", idle.copy(generatedAt = now - 1), now))
        assertEquals(12.0, state.receive("phone", idle.copy(generatedAt = now + 1, distanceMeters = 12.0), now + 1).snapshot!!.distanceMeters, 0.0)
    }

    @Test fun switchingPhoneOrLosingItNeverQueuesAnOfflineCommand() {
        val pending = ready().begin(start(), now)
        val lost = pending.phone(null, null)
        assertNull(lost.pending)
        assertFalse(lost.canStart(now))
        assertNull(lost.begin(start(), now).pending)
        val changed = pending.phone("another-phone", "Yeni telefon")
        assertNull(changed.snapshot)
        assertNull(changed.pending)
    }

    @Test fun expiredPendingCommandRequiresAFreshSnapshotBeforeAnotherStart() {
        val pending = ready().begin(start(), now).tick(now + WearProtocol.COMMAND_TTL_MS + 1)
        assertNull(pending.pending)
        assertNull(pending.snapshot)
        assertFalse(pending.canStart(now + WearProtocol.COMMAND_TTL_MS + 1))
    }

    @Test fun successfulStartCannotBeOverwrittenByAnIdleSnapshotCachedBeforeItsAcknowledgement() {
        val acknowledgedAt = now + 20_000
        val started = ready().begin(start(), now)
            .receive("phone", idle.copy(generatedAt = now + 15_000), acknowledgedAt)
            .result("phone", WearResult("request", WearResultCode.STARTED, "Başladı", "trip"))
        val cached = started.receive("phone", idle.copy(generatedAt = now + 15_000), acknowledgedAt)
        assertNull(cached.snapshot)
        assertFalse(cached.canStart(acknowledgedAt))
        val fresh = cached.receive("phone", idle.copy(generatedAt = acknowledgedAt + 1, recording = true, journeyId = "trip"), acknowledgedAt + 1)
        assertTrue(fresh.canStop("trip", acknowledgedAt + 1))
    }

    @Test fun rejectedCommandInvalidatesOldIdleStateBeforeAllowingAnotherStart() {
        val rejected = ready().begin(start(), now)
            .result("phone", WearResult("request", WearResultCode.REJECTED, "Telefonda mevcut kayıt var."))
        assertNull(rejected.pending)
        assertNull(rejected.snapshot)
        assertFalse(rejected.canStart(now + 1_000))
        assertFalse(rejected.receive("phone", idle, now + 1_000).canStart(now + 1_000))
    }

    @Test fun lostAcknowledgementRetriesTheExactSameCommandAndStopsOnDisconnectOrExpiry() {
        val command = start()
        val pending = ready().begin(command, now)
        assertEquals(command, pending.retryCommand(now + 15_000))
        val needsPhone = pending.result("phone", WearResult(command.id, WearResultCode.NEEDS_PHONE, "Telefon onayı"))
        assertEquals(command, needsPhone.retryCommand(now + 30_000))
        assertNull(pending.phone(null, null).retryCommand(now + 15_000))
        assertNull(pending.retryCommand(now + WearProtocol.COMMAND_TTL_MS))
    }

    @Test fun farFutureSnapshotCannotPoisonSubsequentCurrentPhoneData() {
        val poisoned = ready().receive("phone", idle.copy(generatedAt = now + 86_400_000), now)
        assertEquals(idle, poisoned.snapshot)
        assertEquals(9.0, poisoned.receive("phone", idle.copy(generatedAt = now + 1, distanceMeters = 9.0), now + 1).snapshot!!.distanceMeters, 0.0)
    }

    @Test fun clockCorrectionAcceptsFreshPhoneDataWhenTheOldReferenceIsNoLongerValid() {
        val correctedNow = now - 60_000
        val recovered = ready().receive("phone", idle.copy(generatedAt = correctedNow, distanceMeters = 7.0), correctedNow)
        assertEquals(correctedNow, recovered.snapshot?.generatedAt)
        assertEquals(7.0, recovered.snapshot!!.distanceMeters, 0.0)
        val acknowledged = ready().begin(start(), now).result("phone", WearResult("request", WearResultCode.STARTED, "OK", "trip"))
        assertNotNull(acknowledged.receive("phone", idle.copy(generatedAt = correctedNow, journeyId = "trip", recording = true), correctedNow).snapshot)
    }
}
