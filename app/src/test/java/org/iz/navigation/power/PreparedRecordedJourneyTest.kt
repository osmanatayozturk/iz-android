package org.iz.navigation.power

import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyHealthSummary
import org.iz.navigation.data.JourneyStats
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.junit.Assert.*
import org.junit.Test

class PreparedRecordedJourneyTest {
    @Test fun completedRecordedFactsRemainReadableWhileNewSessionBlocksTheWorker() {
        val journey = completed()
        val state = state(entry(journey))
        val prepared = state.preparedRecorded(journey)
        assertNotNull(prepared)
        assertEquals("historical", prepared!!.journey.id)
        assertEquals(FINGERPRINT_A, prepared.fingerprint)
        assertEquals(100.0, prepared.stats.distanceMeters, 0.0)
        assertEquals(10_000L, prepared.stats.elapsedMillis)
        assertEquals(36.0, prepared.stats.averageSpeedKmh!!, 0.0)
        assertNull("General health/watch reader remains blocked", state.ready("historical"))
        assertEquals(AnalysisPhase.WAITING_END, state.phase)
        assertTrue(state.pending)
    }

    @Test fun historyCanBeReadWithoutMakingAnActiveJourneyEligible() {
        val history = completed()
        val active = completed().copy(id = "active", startedAt = 20_000, endedAt = null)
        val state = AnalysisState(phase = AnalysisPhase.WAITING_END, pending = true,
            entries = mapOf(history.id to entry(history), active.id to entry(active)))
        assertNotNull(state.preparedRecorded(history))
        assertNull(state.preparedRecorded(active))
        assertNull(state.ready(history.id))
        assertNull(state.ready(active.id))
    }

    @Test fun pendingWorkPhasesDoNotHideUnchangedCompletedRecordedMetrics() {
        // WAITING_END covers an active, paused or no-recording navigation session in the worker policy.
        // This pure API receives no session flags; it must not open the worker gate to serve history.
        listOf(AnalysisPhase.WAITING_END, AnalysisPhase.WAITING_BATTERY,
            AnalysisPhase.QUEUED, AnalysisPhase.RUNNING).forEach { phase ->
            val state = state(entry(completed())).copy(phase = phase, pending = true)
            val result = state.preparedRecorded(completed())
            assertNotNull("Historical facts during $phase", result)
            assertEquals("historical", result!!.journey.id)
            assertEquals(FINGERPRINT_A, result.fingerprint)
            assertEquals(100.0, result.stats.distanceMeters, 0.0)
            assertNull("General reader remains strict during $phase", state.ready("historical"))
            assertTrue(state.pending)
            assertEquals(phase, state.phase)
        }
    }

    @Test fun waitingEndStillBlocksGeneralReadyWhenThePendingBitIsFalse() {
        val state = state(entry(completed())).copy(pending = false)
        assertNotNull(state.preparedRecorded(completed()))
        assertNull(state.ready("historical"))
        assertFalse(state.pending)
        assertEquals(AnalysisPhase.WAITING_END, state.phase)
    }

    @Test fun activeCandidateIsUnavailableEvenWithAnExactlyMatchingEntry() {
        val active = completed().copy(endedAt = null)
        assertNull(state(entry(active)).preparedRecorded(active))
    }

    @Test fun temporaryCompletedCandidateCannotBeDisplayedAsConfirmedHistory() {
        val temporary = completed().copy(status = JourneyStatus.TEMPORARY)
        assertNull(state(entry(temporary)).preparedRecorded(temporary))
    }

    @Test fun cachedUnconfirmedOrActiveMetadataCannotAuthorizeAConfirmedCandidate() {
        val current = completed()
        listOf(current.copy(status = JourneyStatus.TEMPORARY), current.copy(endedAt = null)).forEach { cached ->
            val state = state(entry(cached))
            assertNull("Cached metadata=$cached", state.preparedRecorded(current))
        }
    }

    @Test fun changedRecordingMetadataInvalidatesTheOldPreparedEntry() {
        val cached = completed()
        val state = state(entry(cached))
        assertNotNull("Unchanged baseline must be eligible", state.preparedRecorded(cached))
        listOf(
            cached.copy(transport = Transport.CAR),
            cached.copy(startedAt = 1_000),
            cached.copy(endedAt = 11_000),
            cached.copy(interrupted = true),
        ).forEach { current ->
            assertNull("Changed current metadata=$current", state.preparedRecorded(current))
        }
    }

    @Test fun changedWalkingStepMetadataInvalidatesTheOldPreparedEntry() {
        val walking = completed().copy(transport = Transport.WALK, stepCount = 100)
        val state = state(entry(walking))
        assertNotNull(state.preparedRecorded(walking))
        assertNull(state.preparedRecorded(walking.copy(stepCount = 101)))
    }

    @Test fun cacheMapKeyAloneDoesNotProveTheEntryBelongsToThatJourney() {
        val foreign = entry(completed().copy(id = "foreign"))
        val state = AnalysisState(phase = AnalysisPhase.WAITING_END, pending = true,
            entries = mapOf("historical" to foreign))
        assertNull(state.preparedRecorded(completed()))
    }

    @Test fun missingOrDeletedEntryNeverFallsBackToAnotherJourneysMetrics() {
        val empty = AnalysisState(phase = AnalysisPhase.WAITING_END, pending = true)
        assertNull(empty.preparedRecorded(completed()))
        val other = completed().copy(id = "other")
        val state = state(entry(other))
        assertNotNull(state.preparedRecorded(other))
        assertNull(state.preparedRecorded(completed()))
        val removed = state.copy(entries = emptyMap())
        assertNull(removed.preparedRecorded(other))
    }

    @Test fun malformedPreparedFingerprintsCannotReachTheRecordedReader() {
        listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), " " + "a".repeat(63)).forEach { bad ->
            val state = state(entry(completed()).copy(fingerprint = bad))
            assertNull("Malformed fingerprint length=${bad.length}", state.preparedRecorded(completed()))
        }
    }

    @Test fun displayOnlyEditsUseCurrentMetadataWithoutDiscardingRecordedFacts() {
        val stored = completed().copy(title = "Old title", note = "Old note")
        val current = stored.copy(title = "Updated title", note = "Updated note")
        val result = state(entry(stored)).preparedRecorded(current)
        assertNotNull(result)
        assertEquals("Updated title", result!!.journey.title)
        assertEquals("Updated note", result.journey.note)
        assertEquals(current, result.journey)
        assertEquals(FINGERPRINT_A, result.fingerprint)
        assertEquals(100.0, result.stats.distanceMeters, 0.0)
    }

    @Test fun observedStationaryZeroIsPreservedDuringPendingWork() {
        val stationary = JourneyStats(distanceMeters = 0.0, elapsedMillis = 10_000,
            observedMillis = 10_000, movingMillis = 0, stoppedMillis = 10_000, unobservedMillis = 0,
            averageSpeedKmh = 0.0, movingAverageSpeedKmh = null, maxSpeedKmh = 0.0)
        val state = state(entry(completed()).copy(stats = stationary))
        val result = state.preparedRecorded(completed())
        assertNotNull(result)
        assertEquals(0.0, result!!.stats.distanceMeters, 0.0)
        assertEquals(0.0, result.stats.averageSpeedKmh!!, 0.0)
        assertEquals(0.0, result.stats.maxSpeedKmh!!, 0.0)
        assertNull(result.stats.movingAverageSpeedKmh)
        assertEquals(10_000L, result.stats.stoppedMillis)
        assertEquals(0L, result.stats.unobservedMillis)
    }

    @Test fun absentObservationsRemainNullAndNeverBecomeAStationaryZero() {
        val unknown = JourneyStats(distanceMeters = 0.0, elapsedMillis = 10_000,
            observedMillis = 0, movingMillis = 0, stoppedMillis = 0, unobservedMillis = 10_000,
            averageSpeedKmh = null, movingAverageSpeedKmh = null, maxSpeedKmh = null)
        val state = state(entry(completed()).copy(stats = unknown))
        val result = state.preparedRecorded(completed())
        assertNotNull(result)
        assertNull(result!!.stats.averageSpeedKmh)
        assertNull(result.stats.movingAverageSpeedKmh)
        assertNull(result.stats.maxSpeedKmh)
        assertEquals(0L, result.stats.observedMillis)
        assertEquals(0L, result.stats.stoppedMillis)
        assertEquals(10_000L, result.stats.unobservedMillis)
    }

    @Test fun healthAndWatchOnlyChangesCannotLeakIntoTheRecordedResult() {
        val journey = completed()
        val first = entry(journey)
        val changedHealth = first.copy(
            health = JourneyHealthSummary(journey.id, heartRateMeanBpm = 180.0, totalCaloriesKcal = 900.0),
            watch = JourneyHealthSummary(journey.id, watchSteps = 7_000),
        )
        val original = state(first).preparedRecorded(journey)
        val after = state(changedHealth).preparedRecorded(journey)
        assertNotNull(original)
        assertNotNull(after)
        val expected = PreparedRecordedJourney(journey, FINGERPRINT_A, recordedStats())
        assertEquals(expected, original)
        assertEquals(expected, after)
        assertEquals(original, after)
    }

    @Test fun replacingThePreparedEntryReturnsItsCurrentFingerprint() {
        val journey = completed()
        val old = state(entry(journey))
        val replaced = old.copy(entries = mapOf(journey.id to entry(journey).copy(fingerprint = FINGERPRINT_B)))
        assertEquals(FINGERPRINT_A, old.preparedRecorded(journey)!!.fingerprint)
        assertEquals(FINGERPRINT_B, replaced.preparedRecorded(journey)!!.fingerprint)
        assertEquals(100.0, replaced.preparedRecorded(journey)!!.stats.distanceMeters, 0.0)
    }

    private fun state(entry: AnalysisEntry) = AnalysisState(phase = AnalysisPhase.WAITING_END,
        pending = true, entries = mapOf(entry.journey.id to entry))

    private fun entry(journey: Journey) = AnalysisEntry(FINGERPRINT_A, recordedStats(),
        JourneyHealthSummary(journey.id, heartRateMeanBpm = 90.0),
        JourneyHealthSummary(journey.id, watchSteps = 100), journey)

    private fun completed() = Journey(id = "historical", title = "Completed trip", transport = Transport.MOTORCYCLE,
        status = JourneyStatus.CONFIRMED, startedAt = 0, endedAt = 10_000)

    // Independent stored facts:100 m /10 s =36 km/h; no raw analysis is called by this test.
    private fun recordedStats() = JourneyStats(distanceMeters = 100.0, elapsedMillis = 10_000,
        observedMillis = 10_000, movingMillis = 10_000, stoppedMillis = 0, unobservedMillis = 0,
        averageSpeedKmh = 36.0, movingAverageSpeedKmh = 36.0, maxSpeedKmh = 72.0)

    private companion object {
        val FINGERPRINT_A = "a".repeat(64)
        val FINGERPRINT_B = "b".repeat(64)
    }
}
