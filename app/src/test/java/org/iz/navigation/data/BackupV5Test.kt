package org.iz.navigation.data

import org.junit.Assert.*
import org.junit.Test

class BackupV5Test {
    private val journey = Journey(id = "trip", transport = Transport.WALK, startedAt = 100, endedAt = 10_000)
    private val session = WatchHealthSession("capture", journey.id, "watch", "Watch8 Classic", 200)
    private val sample = WatchHealthSample(session.id, 1, journey.id, HealthMetric.HEART_RATE_BPM, 300, 300, 84.0)
    private val edit = MapEditDraft(latitude = 41.0, longitude = 29.0, preset = MapPlacePreset.DRINKING_WATER,
        observedAt = 100, surveyConfirmed = true, status = MapEditStatus.SENDING, stage = MapEditStage.UPLOAD_NODE,
        submittedAt = 200, submittedBy = 5, changesetId = 7)
    private fun snapshot() = DiarySnapshot(journeys = listOf(journey), mapEdits = listOf(edit),
        watchHealthSessions = listOf(session), watchHealthSamples = listOf(sample))

    @Test fun directWatchDataIsOptInAndUncertainMapEditsNeverReplayOnRestore() {
        val excluded = BackupJson.decode(BackupJson.encode(snapshot()))
        assertTrue(excluded.watchHealthSessions.isEmpty())
        assertTrue(excluded.watchHealthSamples.isEmpty())
        assertEquals(MapEditStatus.UNKNOWN, excluded.mapEdits.single().status)
        val json = BackupJson.encode(snapshot(), includeHealth = true)
        assertEquals(6, json.getInt("version"))
        val restored = BackupJson.decode(json)
        assertEquals(listOf(session.copy(acceptsUploads = false)), restored.watchHealthSessions)
        assertEquals(listOf(sample), restored.watchHealthSamples)
        assertEquals(edit.copy(status = MapEditStatus.UNKNOWN), restored.mapEdits.single())
    }

    @Test fun versionFourRequiresNoNewArrays() {
        val json = BackupJson.encode(DiarySnapshot(journeys = listOf(journey))).put("version", 4)
        json.remove("mapEdits"); json.remove("watchHealthSessions"); json.remove("watchHealthSamples")
        val restored = BackupJson.decode(json)
        assertEquals(listOf(journey), restored.journeys)
        assertTrue(restored.mapEdits.isEmpty())
        assertTrue(restored.watchHealthSamples.isEmpty())
    }

    @Test fun watchSampleCannotPointToAnotherJourneysSession() {
        val other = journey.copy(id = "other")
        val corrupt = snapshot().copy(journeys = listOf(journey, other), watchHealthSamples = listOf(sample.copy(journeyId = other.id)))
        assertThrows(IllegalArgumentException::class.java) { BackupJson.encode(corrupt, true) }
    }
}
