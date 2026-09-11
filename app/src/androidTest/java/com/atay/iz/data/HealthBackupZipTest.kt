package com.atay.iz.data

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthBackupZipTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val trip = Journey(id = "zip-run", title = "Saved run", transport = Transport.RUN,
        startedAt = 1_000, endedAt = 5_000, stepCount = 42)
    private val health = ImportedHealthSample("zip-source", HealthRules.SAMSUNG_HEALTH_PACKAGE, 1,
        "Samsung", "Watch8 Classic", HealthMetric.HEART_RATE_BPM, 2_000, 2_000, 90.0)

    @Before fun setup() = runBlocking { repository.restore(DiarySnapshot()) }
    @After fun cleanup() = runBlocking { repository.restore(DiarySnapshot()) }

    @Test fun actualZipRoundTripIncludesHealthOnlyWhenExplicitlySelected() = runBlocking {
        val cameraCache = File(context.cacheDir, "camera")
        check(cameraCache.mkdirs() || cameraCache.isDirectory)
        val backup = DiaryBackup(context, repository)
        for (includeHealth in listOf(false, true)) {
            val archive = File(cameraCache, "health-backup-${UUID.randomUUID()}.zip")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
            try {
                repository.restore(DiarySnapshot(journeys = listOf(trip)))
                repository.replaceJourneyHealth(setOf(trip.id), listOf(health), now = 6_000)
                assertFalse(repository.healthSyncs.first().isEmpty())
                if (includeHealth) backup.exportTo(uri, includeHealth = true) else backup.exportTo(uri)

                ZipFile(archive).use { zip ->
                    val manifest = zip.getEntry("manifest.json")
                    assertNotNull(manifest)
                    val json = JSONObject(zip.getInputStream(manifest).bufferedReader().use { it.readText() })
                    assertEquals(5, json.getInt("version"))
                    assertEquals(if (includeHealth) 1 else 0, json.getJSONArray("healthSamples").length())
                }

                repository.restore(DiarySnapshot())
                backup.importFrom(uri)
                val restored = repository.snapshot()
                assertEquals(listOf(trip), restored.journeys)
                assertEquals(if (includeHealth) listOf(health.forJourney(trip.id)) else emptyList<JourneyHealthSample>(), restored.healthSamples)
                assertTrue("Import must not restore health sync checkpoints", repository.healthSyncs.first().isEmpty())
            } finally { archive.delete() }
        }
    }

    @Test fun restoringActiveBackupDropsOnlyHealthOutsideItsNormalizedEndTime() = runBlocking {
        val now = System.currentTimeMillis()
        val active = trip.copy(startedAt = now - 60_000, endedAt = null)
        val past = health.copy(sourceId = "past", startAt = now - 30_000, endAt = now - 30_000).forJourney(active.id)
        val future = health.copy(sourceId = "future", startAt = now + 3_600_000, endAt = now + 3_600_000).forJourney(active.id)
        val crossing = health.copy(sourceId = "crossing", metric = HealthMetric.TOTAL_CALORIES_KCAL,
            startAt = now - 15_000, endAt = now + 3_600_000, value = 15.0).forJourney(active.id)

        repository.restore(DiarySnapshot(journeys = listOf(active), healthSamples = listOf(past, future, crossing)))
        val restored = repository.snapshot()
        assertEquals(listOf(past), restored.healthSamples)
        val savedJourney = restored.journeys.single()
        assertNotNull(savedJourney.endedAt)
        assertTrue(savedJourney.interrupted)
        assertEquals(active.startedAt, savedJourney.startedAt)
        assertEquals(active.stepCount, savedJourney.stepCount)
        DiaryRules.validate(restored)
        assertEquals(restored, BackupJson.decode(BackupJson.encode(restored, includeHealth = true)))
    }
}
