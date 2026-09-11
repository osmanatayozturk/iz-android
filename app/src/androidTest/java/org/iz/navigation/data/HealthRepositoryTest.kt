package org.iz.navigation.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthRepositoryTest {
    private lateinit var repository: DiaryRepository
    private val trip = Journey(id = "trip", transport = Transport.RUN, startedAt = 1_000, endedAt = 5_000)
    private fun hr(id: String = "hr", time: Long = 2_000, value: Double = 75.0) = ImportedHealthSample(
        id, HealthRules.SAMSUNG_HEALTH_PACKAGE, 1, "Samsung", "Watch8 Classic", HealthMetric.HEART_RATE_BPM, time, time, value)

    @Before fun setup() = runBlocking {
        repository = DiaryRepository(ApplicationProvider.getApplicationContext<Context>())
        repository.restore(DiarySnapshot(journeys = listOf(trip)))
    }
    @After fun cleanup() = runBlocking { repository.restore(DiarySnapshot()) }

    @Test fun repeatedImportsAreIdempotentAndChangesReplaceAllSamplesOfAParent() = runBlocking {
        val samples = listOf(hr(time = 2_000), hr(time = 3_000, value = 85.0))
        repeat(2) { repository.replaceJourneyHealth(setOf(trip.id), samples, now = 6_000) }
        assertEquals(2, repository.snapshot().healthSamples.size)
        repository.applyHealthChanges(listOf(hr(time = 2_500, value = 90.0)), setOf("hr"), now = 7_000)
        val saved = repository.snapshot().healthSamples.single()
        assertEquals(2_500L, saved.startAt)
        assertEquals(90.0, saved.value, 0.0)
        repository.applyHealthChanges(emptyList(), setOf("hr"), now = 8_000)
        assertTrue(repository.snapshot().healthSamples.isEmpty())
        val summary = repository.healthSummaries.first().single()
        assertNull(summary.latestHeartRateBpm)
        assertEquals(8_000L, summary.metricCheckedAt[HealthMetric.HEART_RATE_BPM])
    }

    @Test fun partialPermissionRefreshDoesNotDeleteOtherMetricsAndEmptyReadsAreRemembered() = runBlocking {
        val calories = hr("cal").copy(metric = HealthMetric.TOTAL_CALORIES_KCAL, startAt = 1_000, endAt = 4_000, value = 25.0)
        repository.replaceJourneyHealth(setOf(trip.id), listOf(hr(), calories), now = 6_000)
        repository.replaceJourneyHealth(setOf(trip.id), emptyList(), metrics = setOf(HealthMetric.HEART_RATE_BPM), now = 7_000)
        val summary = repository.healthSummaries.first().single()
        assertNull(summary.latestHeartRateBpm)
        assertEquals(25.0, summary.totalCaloriesKcal!!, 0.0)
        assertEquals(6_000L, summary.metricCheckedAt[HealthMetric.TOTAL_CALORIES_KCAL])
        assertEquals(7_000L, summary.metricCheckedAt[HealthMetric.HEART_RATE_BPM])
    }

    @Test fun invalidOrOutOfTripSourcesCannotBePersistedAndDeleteCascades() = runBlocking {
        repository.replaceJourneyHealth(setOf(trip.id), listOf(hr(), hr("phone").copy(deviceType = 2),
            hr("unknown").copy(deviceType = null), hr("future", 6_000), hr("outside", 999)), now = 6_000)
        assertEquals(listOf("hr"), repository.snapshot().healthSamples.map { it.sourceId })
        repository.deleteJourney(trip.id)
        assertTrue(repository.healthSamples.first().isEmpty())
        assertTrue(repository.healthSyncs.first().isEmpty())
    }

    @Test fun temporaryCandidatesReceiveNeitherHealthSamplesNorSuccessfulReadStamps() = runBlocking {
        val pending = repository.createJourney(Transport.RUN, true)
        val time = pending.startedAt + 1_000
        repository.replaceJourneyHealth(setOf(pending.id), listOf(hr(time = time)), now = time + 1_000)
        repository.applyHealthChanges(listOf(hr("changed", time)), emptySet(), now = time + 1_000)
        assertTrue(repository.snapshot().healthSamples.isEmpty())
        assertTrue(repository.healthSyncs.first().isEmpty())
        assertEquals(listOf(trip.id), repository.allHealthJourneys().map { it.id })
    }

    @Test fun changedParentDeletionIsGlobalButReassociationRespectsAccessibleJourneyIds() = runBlocking {
        val other = trip.copy(id = "other")
        repository.saveJourney(other)
        repository.replaceJourneyHealth(setOf(trip.id, other.id), listOf(hr()), now = 6_000)
        assertEquals(2, repository.snapshot().healthSamples.size)
        repository.applyHealthChanges(listOf(hr(value = 90.0)), setOf("hr"), now = 7_000, journeyIds = setOf(trip.id))
        assertEquals(trip.id, repository.snapshot().healthSamples.single().journeyId)
    }

    @Test fun shorteningJourneyCannotLeaveHealthOutsideItsPersistedBounds() = runBlocking {
        repository.replaceJourneyHealth(setOf(trip.id), listOf(hr(time = 2_000), hr("later", 4_000)), now = 6_000)
        repository.saveJourney(trip.copy(endedAt = 3_000))
        val saved = repository.snapshot()
        assertEquals(listOf("hr"), saved.healthSamples.map { it.sourceId })
        DiaryRules.validate(saved)
    }

    @Test fun invalidRestoreIsAtomicAndValidRestoreDoesNotReenableSync() = runBlocking {
        repository.replaceJourneyHealth(setOf(trip.id), listOf(hr()), now = 6_000)
        val saved = repository.snapshot()
        try {
            repository.restore(saved.copy(healthSamples = listOf(hr().copy(deviceType = null).forJourney(trip.id))))
            fail("Invalid backup should not replace the diary")
        } catch (_: IllegalArgumentException) { }
        assertEquals(saved, repository.snapshot())
        repository.restore(saved)
        assertEquals(saved, repository.snapshot())
        assertTrue(repository.healthSyncs.first().isEmpty())
    }

    @Test fun walkingAndRunningEditsPreservePhoneStepsButMotorModesClearThem() = runBlocking {
        repository.finishJourney(trip.id)
        val active = repository.createJourney(Transport.WALK, false)
        assertTrue(repository.recordWalkingSteps(active.id, 24))
        repository.updateJourneyDetails(active.id, "Run", "", Transport.RUN)
        assertEquals(24L, repository.getJourney(active.id)?.stepCount)
        assertTrue(repository.recordWalkingSteps(active.id, 31))
        repository.saveJourney(repository.getJourney(active.id)!!.copy(transport = Transport.WALK, stepCount = 2))
        assertEquals(31L, repository.getJourney(active.id)?.stepCount)
        repository.updateJourneyDetails(active.id, "Ride", "", Transport.BICYCLE)
        assertNull(repository.getJourney(active.id)?.stepCount)
        assertFalse(repository.recordWalkingSteps(active.id, 99))
    }
}
