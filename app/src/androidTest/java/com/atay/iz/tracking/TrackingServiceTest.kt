package com.atay.iz.tracking

import android.Manifest
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atay.iz.MainActivity
import com.atay.iz.IzApplication
import com.atay.iz.data.*
import com.atay.iz.weather.*
import com.google.android.gms.location.LocationServices
import java.io.FileInputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Catches lost GPS callbacks and accidentally reusing one journey on a transport change. */
@RunWith(AndroidJUnit4::class)
class TrackingServiceTest {
    @Test fun noRecordStartWaitsForInFlightAutomaticWriterAndDiscardsItsCandidate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DiaryRepository(context)
        val controller = TrackingController(context)
        val navigation = (context as IzApplication).navigation
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        controller.disableDetection()
        navigation.state.value.sessionId?.let { navigation.finishSession(it) }
        repository.activeJourney()?.let { controller.finish(it.id) }
        repository.restore(DiarySnapshot())
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        ActivityScenario.launch(MainActivity::class.java).use {
            controller.recoverInterrupted()
            // Real tracking serialization + Room writer, paused exactly after an earlier
            // detection passed its initial suppression check but before its DB write.
            val writer = async(Dispatchers.IO) {
                TrackingCoordinator.mutex.withLock {
                    check(!TrackingService.suppressesAutomaticRecording)
                    entered.complete(Unit)
                    release.await()
                    repository.createJourney(Transport.WALK, temporary = true)
                }
            }
            entered.await()
            val starting = async(Dispatchers.Main) { navigation.startFreeDrive(Transport.WALK, recordJourney = false) }
            try {
                val prematureSession = withTimeoutOrNull(2000) { starting.await() }
                assertNull("No-record must not commit ahead of a writer already holding the tracking mutex", prematureSession)
                release.complete(Unit)
                writer.await()
                val session = withTimeout(10_000) { starting.await() }
                assertEquals(session, navigation.state.value.sessionId)
                assertTrue(TrackingService.suppressesAutomaticRecording)
                assertNull(navigation.state.value.journey)
                assertNull(repository.activeJourney())
                assertTrue(repository.snapshot().journeys.isEmpty())
            } finally {
                release.complete(Unit)
                runCatching { writer.await() }
                runCatching { starting.await() }
                navigation.state.value.sessionId?.let { navigation.finishSession(it) }
                repository.activeJourney()?.let { controller.finish(it.id) }
                withTimeout(10_000) { while (TrackingService.isRunning) delay(50) }
                repository.restore(DiarySnapshot())
            }
        }
    }

    @Test fun noRecordSessionReceivesGpsSuppressesCandidatesAndKeepsGpsAfterDiaryStop() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DiaryRepository(context)
        val controller = TrackingController(context)
        val navigation = (context as IzApplication).navigation
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        controller.disableDetection()
        navigation.state.value.sessionId?.let { navigation.finishSession(it) }
        repository.activeJourney()?.let { controller.finish(it.id) }
        repository.restore(DiarySnapshot())
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun shell(command: String) { automation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } } }
        shell("appops set ${context.packageName} android:mock_location allow")
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.setMockMode(true).await()
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val session = navigation.startFreeDrive(Transport.WALK, recordJourney = false)
                suspend fun fix(latitudeValue: Double) {
                    withTimeout(20_000) {
                        while (navigation.state.value.fix?.coordinate?.latitude != latitudeValue) {
                            client.setMockLocation(Location("fused").apply {
                                latitude = latitudeValue; longitude = 29.0; accuracy = 5f; speed = 3f
                                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            }).await()
                            delay(1000)
                        }
                    }
                }
                fix(41.0)
                assertNull(TrackingService.runningJourneyId)
                assertTrue(repository.snapshot().journeys.isEmpty())
                controller.settings.enabled = true
                androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_DETECTED)
                        .putExtra("suggestedTransport", Transport.WALK.name))
                fix(41.0001)
                assertTrue(repository.snapshot().journeys.isEmpty())
                assertTrue(TrackingService.suppressesAutomaticRecording)
                navigation.setSharingLocation(true)
                assertEquals(session, navigation.startFreeDrive(Transport.WALK, recordJourney = true))
                val diary = navigation.state.value.journey!!.id
                fix(41.0002)
                assertEquals(diary, TrackingService.runningJourneyId)
                // Queue the old notification action behind the diary-finish mutex but
                // ahead of ACTION_DETACH, where runningJourneyId still names the ended row.
                val diaryStop = TrackingCoordinator.mutex.withLock {
                    val stopping = async(Dispatchers.Main) { navigation.stopRecording(diary) }
                    withTimeout(5000) { while (navigation.state.value.journey != null) delay(10) }
                    context.startService(android.content.Intent(context, TrackingService::class.java)
                        .setAction(TrackingService.ACTION_STOP).putExtra("journeyId", diary))
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    delay(100)
                    stopping
                }
                diaryStop.await()
                withTimeout(10_000) { while (TrackingService.runningJourneyId != null) delay(50) }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                delay(250)
                assertTrue("Queued stale Stop must preserve GPS after the diary row ended", TrackingService.locationDeliveryActive)
                val notification = context.getSystemService(android.app.NotificationManager::class.java)
                    .activeNotifications.single { it.id == TrackingNotifications.RECORDING_ID }.notification
                assertEquals("İz • Canlı yolculuk", notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
                assertTrue(notification.actions.isNullOrEmpty())
                // A previously captured diary Stop action must not stop the surviving live session.
                context.startService(android.content.Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_STOP).putExtra("journeyId", diary))
                delay(500)
                assertTrue("Stale diary Stop must preserve live GPS", TrackingService.locationDeliveryActive)
                assertEquals(session, navigation.state.value.sessionId)
                fix(41.0003)
                assertEquals(session, navigation.state.value.sessionId)
                assertTrue(navigation.state.value.sharingLocation)
                assertTrue(TrackingService.locationDeliveryActive)
                assertNotNull(repository.getJourney(diary)?.endedAt)
                val points = repository.journeyPoints(diary)
                assertTrue(points.none { it.latitude == 41.0003 })
                navigation.finishSession(session)
                withTimeout(10_000) { while (TrackingService.isRunning) delay(50) }
                assertFalse(TrackingService.suppressesAutomaticRecording)
            }
        } finally {
            controller.settings.enabled = false
            navigation.state.value.sessionId?.let { navigation.finishSession(it) }
            repository.activeJourney()?.let { controller.finish(it.id) }
            runCatching { client.setMockMode(false).await() }
            shell("appops set ${context.packageName} android:mock_location default")
            repository.restore(DiarySnapshot())
        }
    }

    @Test fun guidanceCadenceChangesKeepOneRecordingAndContinueAcceptedFixes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = DiaryRepository(context)
        val controller = TrackingController(context)
        val navigation = (context as IzApplication).navigation
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        controller.disableDetection()
        repository.activeJourney()?.let { controller.finish(it.id) }
        repository.restore(DiarySnapshot())
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        fun shell(command: String) { automation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } } }
        shell("appops set ${context.packageName} android:mock_location allow")
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.setMockMode(true).await()
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val id = navigation.startFreeDrive(Transport.CAR)
                suspend fun fix(latitudeValue: Double) {
                    withTimeout(20_000) {
                        while (navigation.state.value.fix?.coordinate?.latitude != latitudeValue ||
                            repository.journeyPoints(id).none { point -> point.latitude == latitudeValue }) {
                            client.setMockLocation(Location("fused").apply {
                                latitude = latitudeValue; longitude = 29.0; accuracy = 5f; speed = 15f
                                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            }).await()
                            delay(1000)
                        }
                    }
                }
                fix(41.0)
                val route = PlannedRoute("service-cadence-test",
                    listOf(RouteStop("A", WeatherCoordinate(41.0, 29.0)), RouteStop("B", WeatherCoordinate(41.02, 29.0))),
                    listOf(RouteVertex(WeatherCoordinate(41.0, 29.0), 0.0), RouteVertex(WeatherCoordinate(41.02, 29.0), 300.0)),
                    2200.0, 300.0, System.currentTimeMillis(), transport = Transport.CAR,
                    maneuvers = listOf(RouteManeuver(1, "Kuzeye ilerleyin", "Kuzeye ilerleyin", emptyList(), 0, 1, 0.0, 300.0)))
                assertEquals(id, navigation.startGuidance(route))
                fix(41.0003)
                fix(41.0006)
                assertTrue(navigation.state.value.guidance)
                navigation.stopGuidance()
                withTimeout(5000) { while (navigation.state.value.guidance) delay(100) }
                fix(41.0009)
                assertTrue(navigation.state.value.recording)
                assertEquals(id, TrackingService.runningJourneyId)
                assertEquals(id, repository.activeJourney()?.id)
                assertEquals(1, repository.snapshot().journeys.size)
                assertTrue(repository.journeyPoints(id).size >= 4)
                navigation.finishJourney(id)
                withTimeout(10_000) { while (TrackingService.isRunning) delay(100) }
                assertNull(repository.activeJourney())
            }
        } finally {
            repository.activeJourney()?.let { controller.finish(it.id) }
            runCatching { client.setMockMode(false).await() }
            shell("appops set ${context.packageName} android:mock_location default")
            repository.restore(DiarySnapshot())
        }
    }

    @Test fun automaticTripKeepsItsStartAcrossRepeatedDetectionAndConfirmsAfter500Meters() = automaticTrip(Transport.WALK)

    @Test fun runningTripKeepsItsModeAcrossWalkingDetectionsAndUsesTheSame500MeterThreshold() = automaticTrip(Transport.RUN)

    private fun automaticTrip(mode: Transport) = runBlocking<Unit> {
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
        fun shell(command: String) { automation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } } }
        shell("appops set ${context.packageName} android:mock_location allow")
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.setMockMode(true).await()
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                controller.settings.enabled = true
                val start = System.currentTimeMillis() - 120_000
                val pending = repository.createJourney(mode, temporary = true, now = start)
                for (i in 0..4) repository.addPoint(TrackPoint(journeyId = pending.id,
                    latitude = Math.toDegrees(i * 100.0 / 6_371_000.0), longitude = 0.0,
                    accuracy = 5f, recordedAt = start + i * 10_000))
                androidx.core.content.ContextCompat.startForegroundService(context,
                    android.content.Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START).putExtra("journeyId", pending.id))
                withTimeout(10_000) { while (TrackingService.runningJourneyId != pending.id) delay(100) }
                suspend fun fix(meters: Double) {
                    val latitudeValue = Math.toDegrees(meters / 6_371_000.0)
                    val before = repository.journeyPoints(pending.id).size
                    withTimeout(20_000) {
                        while (repository.journeyPoints(pending.id).size <= before || repository.journeyPoints(pending.id).last().latitude != latitudeValue) {
                            client.setMockLocation(Location("fused").apply {
                                latitude = latitudeValue; longitude = 0.0; accuracy = 5f; speed = 20f
                                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                            }).await()
                            delay(1_500)
                        }
                    }
                }
                fix(400.0)
                fix(450.0)
                assertEquals(JourneyStatus.TEMPORARY, repository.getJourney(pending.id)?.status)
                listOf(Transport.CAR, Transport.WALK, Transport.RUN).forEach { suggestion ->
                    androidx.core.content.ContextCompat.startForegroundService(context,
                        android.content.Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_DETECTED).putExtra("suggestedTransport", suggestion.name))
                }
                fix(560.0)
                withTimeout(10_000) { while (repository.getJourney(pending.id)?.status != JourneyStatus.CONFIRMED) delay(100) }
                val confirmed = repository.getJourney(pending.id)!!
                assertEquals(start, confirmed.startedAt)
                assertNull(confirmed.expiresAt)
                assertNull(confirmed.endedAt)
                assertEquals(1, repository.snapshot().journeys.size)
                assertEquals(mode, confirmed.transport)
                controller.finish(pending.id)
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

    @Test fun manualServicePersistsFixAndTransportSwitchCreatesSeparateJourney() = runBlocking {
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
        fun shell(command: String) { automation.executeShellCommand(command).use { FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() } } }
        shell("appops set ${context.packageName} android:mock_location allow")
        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.setMockMode(true).await()
            ActivityScenario.launch(MainActivity::class.java).use {
                // Synchronize with the application's asynchronous interrupted-record recovery.
                controller.recoverInterrupted()
                val first = controller.startManual(Transport.CAR)
                withTimeout(15_000) { while (TrackingService.runningJourneyId != first) delay(100) }
                withTimeout(20_000) {
                    while (repository.snapshot().points.none { it.journeyId == first }) {
                        client.setMockLocation(Location("fused").apply {
                            latitude = 41.01; longitude = 29.02; accuracy = 5f; speed = 0f
                            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        }).await()
                        delay(1_000)
                    }
                }
                val second = controller.switchTransport(Transport.RUN)
                assertNotEquals(first, second)
                assertNotNull(repository.getJourney(first)?.endedAt)
                assertEquals(Transport.RUN, repository.getJourney(second)?.transport)
                withTimeout(10_000) { while (TrackingService.runningJourneyId != second) delay(100) }
                controller.finish(second)
                withTimeout(10_000) { while (TrackingService.isRunning) delay(100) }
                assertNull(repository.activeJourney())
                assertEquals(2, repository.snapshot().journeys.size)
                assertTrue(repository.snapshot().points.any { it.journeyId == first && it.latitude == 41.01 })
            }
        } finally {
            repository.activeJourney()?.let { controller.finish(it.id) }
            runCatching { client.setMockMode(false).await() }
            shell("appops set ${context.packageName} android:mock_location default")
            repository.restore(DiarySnapshot())
        }
    }
}
