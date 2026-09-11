package com.atay.iz.tracking

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atay.iz.MainActivity
import com.atay.iz.data.DiaryRepository
import com.atay.iz.data.DiarySnapshot
import com.atay.iz.data.JourneyStatus
import com.atay.iz.data.Transport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Delays only Android's manual-start delivery; database and service decisions remain real. */
@RunWith(AndroidJUnit4::class)
class PendingManualStartTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repository = DiaryRepository(context)
    private val controller = TrackingController(context)
    private var ownsFixture = false
    private var previousEnabled = false
    private var previousSuppressed = -1
    private var previousError: String? = null
    private var previousDecision: String? = null

    @Before fun prepare() = runBlocking {
        check(!TrackingService.isRunning) { "A separate test recording must finish before this fixture." }
        previousEnabled = controller.settings.enabled
        previousSuppressed = controller.settings.suppressedActivity
        previousError = controller.settings.lastError
        previousDecision = controller.settings.lastDetectionDecision
        ownsFixture = true
        controller.settings.enabled = false
        TrackingCoordinator.mutex.withLock { TrackingCoordinator.clearPendingManualStarts() }
        repository.restore(DiarySnapshot())
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @After fun cleanup() = runBlocking {
        if (!ownsFixture) return@runBlocking
        controller.settings.enabled = false
        repository.activeJourney()?.let { controller.finish(it.id) }
        context.stopService(Intent(context, TrackingService::class.java))
        withTimeout(10_000) { while (TrackingService.isRunning) delay(25) }
        // onDestroy queues database cleanup before exposing an idle service to the next fixture.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        TrackingCoordinator.mutex.withLock {
            TrackingCoordinator.clearPendingManualStarts()
            repository.restore(DiarySnapshot())
        }
        controller.settings.enabled = previousEnabled
        controller.settings.suppressedActivity = previousSuppressed
        controller.settings.lastError = previousError
        controller.settings.lastDetectionDecision = previousDecision
    }

    @Test fun detectionDeliveredBeforeManualStartKeepsTheConfirmedMotorcycle() = runBlocking<Unit> {
        withTimeout(20_000) {
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val delivery = DelayedStartContext(context)
                val id = TrackingController(delivery).startManual(Transport.MOTORCYCLE)
                assertNull(TrackingService.runningJourneyId)
                controller.settings.enabled = true

                // Framework receives detection first, exactly while the manual ACTION_START is queued.
                deliverDetection()
                delivery.deliver()
                awaitAttachedOrInterrupted(id)

                assertEquals(id, TrackingService.runningJourneyId)
                assertConfirmedOpen(id, Transport.MOTORCYCLE)
                assertEquals(1, repository.snapshot().journeys.size)
                assertNull(TrackingCoordinator.pendingManualStartId())
                controller.finish(id)
                awaitStopped()
            }
        }
    }

    @Test fun detectionDeliveredBeforeTransportSwitchKeepsTheNewManualJourney() = runBlocking<Unit> {
        withTimeout(20_000) {
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val first = controller.startManual(Transport.CAR)
                awaitAttachedOrInterrupted(first)
                assertEquals(first, TrackingService.runningJourneyId)
                val delivery = DelayedStartContext(context)
                val next = TrackingController(delivery).switchTransport(Transport.MOTORCYCLE)
                controller.settings.enabled = true

                deliverDetection()
                delivery.deliver()
                awaitAttachedOrInterrupted(next)

                assertEquals(next, TrackingService.runningJourneyId)
                assertConfirmedOpen(next, Transport.MOTORCYCLE)
                assertNotNull(repository.getJourney(first)?.endedAt)
                assertFalse(repository.getJourney(first)!!.interrupted)
                assertEquals(2, repository.snapshot().journeys.size)
                assertNull(TrackingCoordinator.pendingManualStartId())
                controller.finish(next)
                awaitStopped()
            }
        }
    }

    @Test fun lateStopOfPreviousJourneyCannotCancelAPendingTransportSwitch() = runBlocking<Unit> {
        withTimeout(20_000) {
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val first = controller.startManual(Transport.CAR)
                awaitAttachedOrInterrupted(first)
                assertEquals(first, TrackingService.runningJourneyId)
                val delivery = DelayedStartContext(context)
                val next = TrackingController(delivery).switchTransport(Transport.MOTORCYCLE)

                // A delayed UI finish and notification STOP still name the old attached journey.
                controller.finish(first)
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_STOP).putExtra("journeyId", first))
                delivery.deliver()
                awaitAttachedOrInterrupted(next)

                assertEquals(next, TrackingService.runningJourneyId)
                assertConfirmedOpen(next, Transport.MOTORCYCLE)
                assertFalse(repository.getJourney(first)!!.interrupted)
                assertEquals(2, repository.snapshot().journeys.size)
                controller.finish(next)
                awaitStopped()
            }
        }
    }

    @Test fun stoppingThePendingJourneyItselfStillFinishesItBeforeAttachment() = runBlocking<Unit> {
        withTimeout(20_000) {
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val first = controller.startManual(Transport.CAR)
                awaitAttachedOrInterrupted(first)
                assertEquals(first, TrackingService.runningJourneyId)
                val delivery = DelayedStartContext(context)
                val next = TrackingController(delivery).switchTransport(Transport.MOTORCYCLE)

                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
                    .setAction(TrackingService.ACTION_STOP).putExtra("journeyId", next))
                delivery.deliver()
                withTimeout(10_000) {
                    while (repository.getJourney(next)?.endedAt == null || TrackingService.isRunning) delay(25)
                }

                assertNotNull(repository.getJourney(next)?.endedAt)
                assertFalse(repository.getJourney(next)!!.interrupted)
                assertNull(repository.activeJourney())
                assertNull(TrackingCoordinator.pendingManualStartId())
                assertEquals(2, repository.snapshot().journeys.size)
            }
        }
    }

    @Test fun detectionStillClosesAnUnreservedOrphanAndStartsANewCandidate() = runBlocking<Unit> {
        withTimeout(20_000) {
            ActivityScenario.launch(MainActivity::class.java).use {
                controller.recoverInterrupted()
                val orphan = repository.createJourney(Transport.MOTORCYCLE, temporary = false,
                    now = System.currentTimeMillis() - 120_000)
                controller.settings.enabled = true
                deliverDetection()
                withTimeout(10_000) { while (TrackingService.runningJourneyId == null) delay(25) }

                val active = repository.activeJourney()!!
                assertNotEquals(orphan.id, active.id)
                assertEquals(active.id, TrackingService.runningJourneyId)
                assertEquals(JourneyStatus.TEMPORARY, active.status)
                assertEquals(Transport.WALK, active.transport)
                assertTrue(repository.getJourney(orphan.id)!!.interrupted)
                assertNotNull(repository.getJourney(orphan.id)?.endedAt)
                controller.finish(active.id)
                awaitStopped()
            }
        }
    }

    @Test fun appRecoveryProtectsOnlyAFreshPendingStartAndDoesNotRenewItsDeadline() = runBlocking<Unit> {
        withTimeout(10_000) {
            val delivery = DelayedStartContext(context)
            val id = TrackingController(delivery).startManual(Transport.MOTORCYCLE)
            controller.recoverInterrupted()
            assertConfirmedOpen(id, Transport.MOTORCYCLE)
            assertNull(TrackingService.runningJourneyId)

            TrackingCoordinator.mutex.withLock {
                TrackingCoordinator.markPendingManualStart(id,
                    SystemClock.elapsedRealtime() - TrackingCoordinator.MANUAL_START_TIMEOUT_MILLIS)
            }
            controller.recoverInterrupted()
            assertTrue(repository.getJourney(id)!!.interrupted)
            assertNotNull(repository.getJourney(id)?.endedAt)
            assertNull(repository.activeJourney())
            assertNull(TrackingCoordinator.pendingManualStartId())
        }
    }

    @Test fun failedStartClearsItsReservationAndClosesItsDiaryRow() = runBlocking<Unit> {
        withTimeout(10_000) {
            val delivery = DelayedStartContext(context, fail = true)
            try {
                TrackingController(delivery).startManual(Transport.MOTORCYCLE)
                fail("The rejected foreground start must reach the caller.")
            } catch (_: IllegalStateException) {
                // Android rejected the dispatch, so recovery must not wait out a reservation.
            }
            val journey = repository.snapshot().journeys.single()
            assertTrue(journey.interrupted)
            assertNotNull(journey.endedAt)
            assertNull(repository.activeJourney())
            assertNull(TrackingCoordinator.pendingManualStartId())
        }
    }

    @Test fun finishingAPendingJourneyClearsItsReservation() = runBlocking<Unit> {
        withTimeout(10_000) {
            val delivery = DelayedStartContext(context)
            val id = TrackingController(delivery).startManual(Transport.MOTORCYCLE)
            controller.finish(id)
            assertNull(TrackingCoordinator.pendingManualStartId())
            assertNotNull(repository.getJourney(id)?.endedAt)
            assertFalse(repository.getJourney(id)!!.interrupted)
        }
    }

    @Test fun reservationsExpireAtThirtySecondsAndLateClearCannotEraseANewerId() = runBlocking<Unit> {
        TrackingCoordinator.mutex.withLock {
            TrackingCoordinator.markPendingManualStart("old", 1_000)
            TrackingCoordinator.markPendingManualStart("new", 2_000)
            TrackingCoordinator.clearPendingManualStart("old")
            assertEquals("new", TrackingCoordinator.pendingManualStartId(31_999))
            assertNull(TrackingCoordinator.pendingManualStartId(32_000))
            assertNull(TrackingCoordinator.pendingManualStartId(2_000))
            TrackingCoordinator.markPendingManualStart("future", 5_000)
            assertNull(TrackingCoordinator.pendingManualStartId(4_999))
        }
    }

    private suspend fun assertConfirmedOpen(id: String, transport: Transport) {
        val journey = repository.getJourney(id)!!
        assertEquals(JourneyStatus.CONFIRMED, journey.status)
        assertEquals(transport, journey.transport)
        assertNull(journey.endedAt)
        assertFalse(journey.interrupted)
    }

    private suspend fun awaitAttachedOrInterrupted(id: String) = withTimeout(10_000) {
        while (TrackingService.runningJourneyId != id && repository.getJourney(id)?.endedAt == null) delay(25)
    }

    private suspend fun awaitStopped() = withTimeout(10_000) {
        while (TrackingService.isRunning) delay(25)
    }

    private fun deliverDetection() {
        ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java)
            .setAction(TrackingService.ACTION_DETECTED).putExtra("suggestedTransport", Transport.WALK.name))
    }

    private class DelayedStartContext(private val app: Context, private val fail: Boolean = false) : ContextWrapper(app) {
        private var queued: Intent? = null
        override fun getApplicationContext(): Context = this
        override fun startForegroundService(service: Intent): ComponentName {
            check(service.action == TrackingService.ACTION_START)
            if (fail) throw IllegalStateException("Synthetic foreground-service dispatch rejection")
            check(queued == null)
            queued = Intent(service)
            return ComponentName(app, TrackingService::class.java)
        }
        fun deliver() {
            val intent = checkNotNull(queued)
            queued = null
            ContextCompat.startForegroundService(app, intent)
        }
    }
}
