package org.iz.navigation.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.MainActivity
import org.iz.navigation.data.*
import com.google.android.gms.location.LocationServices
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticCandidateServiceTest {
    @Test fun tickResetsWithoutGpsAndContinuedWalkingQualifiesWithoutAnotherActivityEnter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DiaryRepository(context)
        val controller = TrackingController(context)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        controller.disableDetection()
        repository.activeJourney()?.let { controller.finish(it.id) }
        repository.restore(DiarySnapshot())
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun shell(command: String) {
            automation.executeShellCommand(command).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        shell("appops set ${context.packageName} android:mock_location allow")
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.setMockMode(true).await()
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                controller.settings.enabled = true
                val start = System.currentTimeMillis() - TrackingPolicy.AUTOMATIC_WINDOW_MS + 10_000
                val old = repository.createJourney(Transport.WALK, temporary = true, now = start)
                for (index in 0..4) repository.addPoint(TrackPoint(journeyId = old.id,
                    latitude = Math.toDegrees(index * 100.0 / 6_371_000.0), longitude = 0.0,
                    accuracy = 5f, recordedAt = start + index * 10_000L))
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_START).putExtra("journeyId", old.id))
                withTimeout(8_000) { while (TrackingService.runningJourneyId != old.id) delay(100) }
                // No GPS is supplied: the service timer alone must replace the timed-out candidate.
                withTimeout(25_000) {
                    while (TrackingService.runningJourneyId == old.id || TrackingService.runningJourneyId == null) delay(100)
                }
                val nextId = TrackingService.runningJourneyId!!
                val next = repository.getJourney(nextId)!!
                assertNull(repository.getJourney(old.id))
                assertEquals(Transport.WALK, next.transport)
                assertEquals(JourneyStatus.TEMPORARY, next.status)
                assertTrue(next.startedAt >= start + TrackingPolicy.AUTOMATIC_WINDOW_MS)
                assertTrue(repository.snapshot().points.isEmpty())
                // A notification from the old window must not stop the replacement.
                context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
                    .putExtra("journeyId", old.id))
                delay(300)
                assertEquals(nextId, TrackingService.runningJourneyId)
                suspend fun fix(meters: Double) {
                    val latitudeValue = Math.toDegrees(meters / 6_371_000.0)
                    withTimeout(15_000) {
                        while (repository.journeyPoints(nextId).lastOrNull()?.latitude != latitudeValue) {
                            client.setMockLocation(Location("fused").apply {
                                latitude = latitudeValue; longitude = 0.0; accuracy = 5f; speed = 20f
                                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            }).await()
                            delay(1_500)
                        }
                    }
                }
                fix(0.0)
                val first = repository.journeyPoints(nextId).first()
                assertTrue(first.breakBefore)
                assertTrue(first.recordedAt >= next.startedAt)
                assertEquals(0.0, DiaryRules.distanceMeters(repository.journeyPoints(nextId)), 0.001)
                assertEquals(JourneyStatus.TEMPORARY, repository.getJourney(nextId)?.status)
                // Continuing GPS movement, with no ACTION_DETECTED/ENTER, qualifies only the new route.
                for (meters in listOf(100.0, 200.0, 300.0, 400.0, 510.0)) fix(meters)
                withTimeout(10_000) { while (repository.getJourney(nextId)?.status != JourneyStatus.CONFIRMED) delay(100) }
                assertEquals(next.startedAt, repository.getJourney(nextId)?.startedAt)
                assertEquals(1, repository.snapshot().journeys.size)
                controller.finish(nextId)
                withTimeout(10_000) { while (TrackingService.isRunning) delay(100) }
            }
        } finally {
            controller.settings.enabled = false
            repository.activeJourney()?.let { controller.finish(it.id) }
            runCatching { client.setMockMode(false).await() }
            shell("appops set ${context.packageName} android:mock_location default")
            repository.restore(DiarySnapshot())
        }
    }
}
