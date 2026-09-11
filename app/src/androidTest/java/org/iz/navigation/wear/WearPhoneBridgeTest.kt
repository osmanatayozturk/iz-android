package org.iz.navigation.wear

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.tracking.TrackingController
import org.iz.navigation.wearprotocol.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class WearPhoneBridgeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val bridge = WearPhoneBridge(context)
    private fun command(action: WearAction = WearAction.START, mode: WearMode? = WearMode.WALK, journey: String? = null) =
        WearCommand(UUID.randomUUID().toString(), action, System.currentTimeMillis(), mode, journey)
    @Before fun clean() = runBlocking {
        TrackingController(context).disableDetection()
        repository.activeJourney()?.let { TrackingController(context).finish(it.id) }
        repository.restore(DiarySnapshot())
        context.getSharedPreferences("wear_commands_v1", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }
    @After fun after() { clean() }

    @Test fun backgroundStartRequiresPhoneAndDuplicateSurvivesStoreRecreation() = runBlocking {
        val request = command()
        val first = bridge.handle("watch", request, foreground = false)
        assertEquals(WearResultCode.NEEDS_PHONE, first.code)
        assertNull(repository.activeJourney())
        assertEquals(first, WearPhoneBridge(context).handle("watch", request, foreground = false))
        assertEquals(request, bridge.pending(bridge.pendingKey()!!))
        assertEquals(WearResultCode.REJECTED, bridge.handle("watch", request.copy(mode = WearMode.CAR), false).code)
    }
    @Test fun staleRequestAndSecondPendingCannotStartOrOverwriteFirst() = runBlocking {
        val expired = command().copy(requestedAt = System.currentTimeMillis() - WearProtocol.COMMAND_TTL_MS)
        assertEquals(WearResultCode.REJECTED, bridge.handle("watch", expired, false).code)
        assertNull(bridge.pendingKey())
        val first = command()
        bridge.handle("watch", first, false)
        assertEquals(WearResultCode.REJECTED, bridge.handle("watch", command(mode = WearMode.CAR), false).code)
        assertEquals(first.id, bridge.pending(bridge.pendingKey()!!)?.id)
        assertNull(repository.activeJourney())
    }
    @Test fun oldStopCannotFinishNewJourneyAndResultCannotRepeatSideEffect() = runBlocking {
        val old = repository.createJourney(Transport.CAR, false)
        repository.finishJourney(old.id)
        val current = repository.createJourney(Transport.PASSENGER, false)
        val obsolete = command(WearAction.STOP, null, old.id)
        assertEquals(WearResultCode.REJECTED, bridge.handle("watch", obsolete, false).code)
        assertEquals(current.id, repository.activeJourney()?.id)
        val stop = command(WearAction.STOP, null, current.id)
        assertEquals(WearResultCode.STOPPED, bridge.handle("watch", stop, false).code)
        val next = repository.createJourney(Transport.WALK, false)
        assertEquals(WearResultCode.STOPPED, WearPhoneBridge(context).handle("watch", stop, false).code)
        assertEquals(next.id, repository.activeJourney()?.id)
    }
    @Test fun snapshotsDoNotReportUnattachedDatabaseJourneyAsRecording() = runBlocking {
        val current = repository.createJourney(Transport.WALK, false)
        val snapshot = bridge.snapshot()
        assertEquals(current.id, snapshot.journeyId)
        assertFalse(snapshot.recording)
        assertNull(snapshot.stepCount)
        repository.finishJourney(current.id)
        assertNull(bridge.snapshot().journeyId)
    }
    @Test fun stoppingTemporaryCandidateDoesNotClaimPermanentSaving() = runBlocking {
        val candidate = repository.createJourney(Transport.WALK, true)
        val result = bridge.handle("watch", command(WearAction.STOP, null, candidate.id), false)
        assertEquals(WearResultCode.STOPPED, result.code)
        assertTrue(result.message.contains("Geçici"))
        assertEquals(JourneyStatus.TEMPORARY, repository.getJourney(candidate.id)?.status)
        assertNotNull(repository.getJourney(candidate.id)?.endedAt)
    }

    @Test fun legacyPendingJsonRemainsReadableAndKeepsItsV1AcknowledgementAfterUpgrade() = runBlocking {
        val request = command()
        val result = WearResult(request.id, WearResultCode.NEEDS_PHONE, "Telefon onayı")
        val entry = WearCommandStore(context).save("old-watch", request, result, System.currentTimeMillis(), 1)
        val prefs = context.getSharedPreferences("wear_commands_v1", Context.MODE_PRIVATE)
        // Version 0.2 had these exact bytes and no protocolVersion JSON property.
        val legacyJson = JSONObject(prefs.getString(entry.key, null)!!).apply { remove("protocolVersion") }
        assertTrue(prefs.edit().putString(entry.key, legacyJson.toString()).commit())
        val restored = WearCommandStore(context).get(entry.key)!!
        assertEquals(1, restored.protocolVersion)
        assertEquals(request, restored.command)
        assertEquals(result, WearPhoneBridge(context).handle("old-watch", request, false, 1))
        assertEquals(result, WearPhoneBridge(context).handle("old-watch", request, false, 2))
        assertNull(repository.activeJourney())
        assertEquals(WearResultCode.REJECTED, bridge.cancelOnPhone(entry.key).code)
        assertEquals(1, WearCommandStore(context).get(entry.key)!!.protocolVersion)
    }

    @Test fun v2RunPendingSurvivesRestartAndLegacyWatchCanStopRunningJourney() = runBlocking {
        val run = command(mode = WearMode.RUN)
        assertEquals(WearResultCode.REJECTED, bridge.handle("legacy", run, false, 1).code)
        assertNull(bridge.pendingKey())
        val ack = bridge.handle("new-watch", run, false, 2)
        assertEquals(WearResultCode.NEEDS_PHONE, ack.code)
        assertEquals(2, WearCommandStore(context).get("new-watch", run)!!.protocolVersion)
        assertEquals(ack, WearPhoneBridge(context).handle("new-watch", run, false, 2))
        bridge.cancelOnPhone(bridge.pendingKey()!!)
        val journey = repository.createJourney(Transport.RUN, false)
        val stop = command(WearAction.STOP, null, journey.id)
        assertEquals(WearResultCode.STOPPED, bridge.handle("old-watch", stop, false, 1).code)
        assertNotNull(repository.getJourney(journey.id)?.endedAt)
    }
}
