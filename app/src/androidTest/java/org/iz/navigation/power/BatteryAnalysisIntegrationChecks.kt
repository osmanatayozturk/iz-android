package org.iz.navigation.power

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.TrackPoint
import org.iz.navigation.data.Transport
import org.iz.navigation.tracking.TrackingService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** One real manager/Room integration scenario; no service, sensor, battery override or product hook. */
@RunWith(AndroidJUnit4::class)
class BatteryAnalysisIntegrationChecks {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun actualActiveJourneyVetoResumesAndPersistsPreparedResults() = runBlocking(Dispatchers.IO) {
        assertEquals("org.iz.navigation.batterycheck", context.packageName)
        assumeTrue("QA must not have real health-read access for this synthetic scenario",
            listOf("READ_HEART_RATE", "READ_TOTAL_CALORIES_BURNED", "READ_STEPS", "READ_EXERCISE", "READ_DISTANCE")
                .none { ContextCompat.checkSelfPermission(context, "android.permission.health.$it") == PackageManager.PERMISSION_GRANTED })
        naturalBatteryEligible()
        assertFalse("No QA recorder/sensor service may be running", TrackingService.isRunning)
        val repository = DiaryRepository(context)
        val baseline = repository.snapshot()
        assumeTrue("Preserve any preexisting QA journeys, GPS or health data",
            baseline.journeys.isEmpty() && baseline.points.isEmpty() && baseline.healthSamples.isEmpty() && baseline.watchHealthSamples.isEmpty())
        val manager = PostJourneyAnalysis.get(context)
        suspend fun state(message: String, predicate: (AnalysisState) -> Boolean): AnalysisState {
            val result = withTimeoutOrNull(5_000) {
                manager.state.first { it.phase == AnalysisPhase.FAILED || predicate(it) }
            }
            naturalBatteryEligible()
            assertNotNull("$message: current phase=${manager.state.value.phase}", result)
            assertNotEquals("$message: analysis failed", AnalysisPhase.FAILED, result!!.phase)
            return result
        }
        state("Initial eligible metadata", { manager.canProcessNow() })
        val id = "qa-battery-integration-${UUID.randomUUID()}"
        val start = System.currentTimeMillis() - 20_000
        val journey = Journey(id = id, title = "Pil QA sentetik entegrasyon", transport = Transport.WALK,
            startedAt = start, stepCount = 12)
        val points = listOf(
            TrackPoint(id = "$id-p1", journeyId = id, latitude = 0.0, longitude = 0.0,
                recordedAt = start + 1_000, accuracy = 5f),
            TrackPoint(id = "$id-p2", journeyId = id, latitude = 0.0001, longitude = 0.0,
                recordedAt = start + 11_000, accuracy = 5f),
        )
        assertNull(repository.getJourney(id))
        // Claim only this absent UUID before the transaction; finally also covers a partial failure.
        try {
            repository.saveJourney(journey)
            points.forEach { repository.addPoint(it) }
            manager.request()
            val waiting = state("Actual raw journey veto", { it.phase == AnalysisPhase.WAITING_END && it.pending && !manager.canProcessNow() })
            assertTrue(waiting.pending)
            assertEquals(id, repository.activeJourney()?.id)
            assertEquals(points, repository.journeyPoints(id))
            assertTrue("Closed gate returns without processing", withTimeout(5_000) { manager.runPending() })
            assertFalse(manager.canProcessNow())
            assertNull(manager.state.value.ready(id))
            assertFalse(manager.cache.load().first.entries.containsKey(id))

            repository.finishJourney(id, now = start + 20_000)
            manager.request()
            state("Actual end reopens gate", { manager.canProcessNow() })
            // Call the existing real worker body, not a fake executor or duplicated calculation.
            assertTrue("Real analysis body must finish", withTimeout(20_000) { manager.runPending() })
            val completed = state("Actual prepared result", {
                it.phase == AnalysisPhase.READY && !it.pending && it.entries.containsKey(id)
            })
            val entry = requireNotNull(completed.ready(id))
            val finished = requireNotNull(repository.getJourney(id))
            assertEquals(journey.copy(endedAt = start + 20_000), finished)
            assertTrue(entry.matches(finished))
            assertTrue("Two synthetic points must produce measured distance", entry.stats.distanceMeters in 10.0..12.0)
            assertEquals(20_000L, entry.stats.elapsedMillis)
            assertEquals(10_000L, entry.stats.observedMillis)
            assertEquals(points, repository.journeyPoints(id))
            val persisted = requireNotNull(manager.cache.load().first.ready(id))
            assertEquals(entry.fingerprint, persisted.fingerprint)
            assertEquals(entry.stats, persisted.stats)
            assertEquals(entry.health, persisted.health)
            assertEquals(entry.watch, persisted.watch)
            assertTrue(persisted.matches(finished))
            // Persisted metadata intentionally strips display text; do not require whole-object identity.
            assertEquals("", persisted.journey.title)
            assertEquals("", persisted.journey.note)
            assertTrue(manager.cache.hasTrail(id, entry.fingerprint))
            assertNotNull(manager.cache.trail(id, entry.fingerprint))
            assertTrue(manager.cache.hasHealthSamples(id, entry.fingerprint))
            assertTrue(manager.cache.healthSamples(id, entry.fingerprint).isEmpty())
            assertNotNull(manager.cache.heatmap(completed.cacheRevision, "ALL"))
            assertNull(entry.health.heartRateMeanBpm)
            assertNull(entry.watch.heartRateMeanBpm)
            assertFalse(TrackingService.isRunning)
        } finally {
            withContext(NonCancellable) {
                withTimeout(10_000) {
                    // Exact owned raw row/points only; never restore/clear a database or bypass the power gate.
                    repository.deleteJourney(id)
                    manager.request()
                    assertNull(repository.getJourney(id))
                    assertTrue(repository.journeyPoints(id).isEmpty())
                    assertEquals(baseline.journeys, repository.snapshot().journeys)
                }
            }
            // Disposable derived outputs are invalidated by the real queue; no manual cache deletion.
            // If the natural battery falls, normal gated cleanup may remain pending and must be reported.
        }
    }

    private fun naturalBatteryEligible() {
        val percent = PhoneBatteryReader.read(context).percent
        assumeTrue("Real battery must be known and at least20; defer without mocks", percent != null && percent in 20..100)
    }
}
