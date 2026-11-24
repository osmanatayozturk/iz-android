package org.iz.navigation.moto

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.car.CarFuelAlertCancel
import org.iz.navigation.car.CarFuelAlertHost
import org.iz.navigation.car.CarFuelAlerts
import org.iz.navigation.car.CarHomeScreen
import org.iz.navigation.car.CarMapSurface
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.FuelFill
import org.iz.navigation.garage.FuelOffer
import org.iz.navigation.garage.FuelOfferController
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.navigation.FuelStationOwner
import org.iz.navigation.navigation.FuelStationPlan
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorcycleFuelOfferAcceptanceTest {
    @Test fun noVehicleRejectionExpiryHandoffAndDoubleAcceptanceShareOneDecisionPerDrive() = runBlocking<Unit> {
        val app = requireMotorcycleQaTarget().applicationContext as IzApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it)
        }
        val navigation = app.navigation
        val diary = DiaryRepository(app)
        val garage = app.garage
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val phone = Any()
        val auto = Any()
        val searches = AtomicInteger()
        val announcements = AtomicInteger()
        val commits = AtomicInteger()
        var pauseApply = CompletableDeferred<Unit>()
        var arrivedAtApply = CompletableDeferred<Unit>()
        var fixturePause = false
        val motorcycle = GarageVehicle(name = "QA Fuel", transport = Transport.MOTORCYCLE,
            capacityLitres = 12.0, typicalThresholdKm = 600.0, odometerAt = 0L)
        val origin = WeatherCoordinate(.27, -140.0)
        val station = WeatherCoordinate(.27, -139.998)
        val target = RouteStop("QA station", station)
        val controller = FuelOfferController(app, announce = { announcements.incrementAndGet() },
            scope = scope, search = {
                val state = navigation.state.value
                val fix = requireNotNull(state.fix)
                searches.incrementAndGet()
                val owner = FuelStationOwner(
                    state.routeRevision, state.sessionId, state.journey?.id, state.recording, state.route?.id,
                    state.routeRevision, Transport.MOTORCYCLE, fix, emptyList(),
                )
                val route = PlannedRoute("qa-fuel-${state.sessionId}", listOf(RouteStop("QA origin", origin), target),
                    listOf(RouteVertex(origin, 0.0), RouteVertex(station, 60.0)), 222.0, 60.0, System.currentTimeMillis(),
                    transport = Transport.MOTORCYCLE, maneuvers = listOf(RouteManeuver(10, "QA", "QA",
                        emptyList(), 0, 1, 0.0, 60.0)))
                FuelStationPlan(target.label, 222.0, null, owner, route)
            }, applyPlan = { plan, current ->
                if (fixturePause) {
                    arrivedAtApply.complete(Unit)
                    pauseApply.await()
                }
                if (!current()) false
                else navigation.acceptFuelStation(plan, current).also { if (it) commits.incrementAndGet() }
            },
        )
        val host = OfferHost()
        var homeVisible = false
        val alerts = CarFuelAlerts(scope, SystemClock::elapsedRealtime,
            visible = { controller.autoVisible.value && homeVisible }, current = { controller.offer.value },
            host = host, accept = controller::accept, reject = controller::reject, failed = {},
        )
        suspend fun start(assigned: Boolean): String {
            val id = withContext(Dispatchers.Main) { navigation.startFreeDrive(Transport.MOTORCYCLE, recordJourney = false) }
            if (assigned) garage.bindSession(id, null, motorcycle.id, Transport.MOTORCYCLE)
            fix(id, navigation::onAcceptedLocation, origin)
            withTimeout(4_000) { navigation.state.first { it.sessionId == id && it.fix?.coordinate == origin && !it.gpsStale } }
            return id
        }
        suspend fun finish(id: String) {
            withContext(Dispatchers.Main) { navigation.finishSession(id) }
            withTimeout(4_000) { controller.offer.first { it == null } }
        }
        suspend fun nextOffer() = withTimeout(5_000) { requireNotNull(controller.offer.first { it != null }) }

        assertNull(navigation.state.value.sessionId)
        val before = diary.snapshot().journeys
        navigation.setMuted(false)
        withTimeout(5_000) { navigation.state.first { !it.muted } }
        try {
            garage.saveVehicle(motorcycle)
            repeat(5) { index ->
                garage.saveFill(FuelFill(vehicleId = motorcycle.id, at = 1000L + index * 1000L,
                    odometerKm = 1000.0 + index * 200.0, litres = 8.0, pricePerLitre = 50.0))
            }
            withContext(Dispatchers.Main) { controller.setPhoneVisible(phone, true) }
            var session = start(assigned = false)
            delay(300)
            assertNull(controller.offer.value)
            assertEquals(0, searches.get())
            garage.bindSession(session, null, motorcycle.id, Transport.MOTORCYCLE)
            val rejected = nextOffer()
            withContext(Dispatchers.Main) { controller.reject(rejected.id) }
            assertFalse(controller.accept(rejected.id))
            val rejectedSession = session
            fix(rejectedSession, navigation::onAcceptedLocation, origin)
            delay(250)
            assertEquals(1, searches.get())
            assertNull(controller.offer.value)
            assertNull(navigation.state.value.route)
            finish(rejectedSession)

            session = start(assigned = true)
            val expiring = nextOffer()
            val liveSession = session
            coroutineScope {
                val keepFresh = launch {
                    while (true) {
                        delay(700)
                        fix(liveSession, navigation::onAcceptedLocation, origin)
                    }
                }
                try {
                    val remaining = expiring.expiresAtElapsedMs - SystemClock.elapsedRealtime()
                    assertTrue(remaining in 19_000..20_000)
                    delay(remaining - 1_200)
                    assertEquals(expiring.id, controller.offer.value?.id)
                    withTimeout(3_500) { controller.offer.first { it == null } }
                    assertTrue(SystemClock.elapsedRealtime() >= expiring.expiresAtElapsedMs)
                    assertFalse(controller.accept(expiring.id))
                } finally { keepFresh.cancel() }
            }
            assertEquals(2, searches.get())
            assertNull(navigation.state.value.route)
            finish(session)

            session = start(assigned = true)
            val handoff = nextOffer()
            fixturePause = true
            coroutineScope {
                val formerPhoneClick = async { controller.accept(handoff.id) }
                withTimeout(4_000) { arrivedAtApply.await() }
                withContext(Dispatchers.Main) {
                    controller.setAutoVisible(auto, true)
                    controller.setAutoMapVisible(auto, true)
                    homeVisible = true
                    alerts.update()
                }
                assertTrue(controller.autoVisible.value)
                assertEquals(handoff.id, controller.offer.value?.id)
                pauseApply.complete(Unit)
                assertFalse(formerPhoneClick.await())
            }
            assertNull(navigation.state.value.route)
            assertEquals(handoff.id, controller.offer.value?.id)
            val oldAlert = requireNotNull(host.shown)
            withContext(Dispatchers.Main) {
                homeVisible = false
                controller.setAutoMapVisible(auto, false)
                alerts.update()
                assertNull(host.shown)
                oldAlert.accept()
                oldAlert.reject()
            }
            assertFalse(controller.accept(handoff.id))
            assertEquals(handoff.id, controller.offer.value?.id)
            withContext(Dispatchers.Main) {
                homeVisible = true
                controller.setAutoMapVisible(auto, true)
                alerts.update()
                val restored = requireNotNull(host.shown)
                assertEquals(handoff.id, restored.offer.id)
                assertTrue(restored.remainingMs in 1..oldAlert.remainingMs)
                restored.reject()
                alerts.update()
            }
            assertNull(controller.offer.value)
            assertEquals(3, announcements.get())
            finish(session)

            session = start(assigned = true)
            val accepted = nextOffer()
            pauseApply = CompletableDeferred()
            arrivedAtApply = CompletableDeferred()
            withContext(Dispatchers.Main) {
                alerts.update()
                val action = requireNotNull(host.shown)
                assertEquals(accepted.id, action.offer.id)
                action.accept()
                action.accept()
            }
            withTimeout(4_000) { arrivedAtApply.await() }
            assertFalse(controller.accept(accepted.id))
            pauseApply.complete(Unit)
            withTimeout(5_000) { controller.offer.first { it == null } }
            assertEquals(1, commits.get())
            assertEquals(4, searches.get())
            assertEquals(4, announcements.get())
            val final = navigation.state.value
            assertEquals(session, final.sessionId)
            assertEquals(listOf(RouteStop("QA origin", origin), target), final.route?.stops)
            assertTrue(final.guidance)
            assertFalse(final.recording)
            assertNull(final.journey)
            assertEquals(before, diary.snapshot().journeys)
            finish(session)
            withContext(Dispatchers.Main) {
                alerts.hide()
                controller.setAutoVisible(auto, false)
                controller.setPhoneVisible(phone, false)
            }
            idleAutoHomeAcceptsOnlyOneSuitableVehicle(app, origin, target)
        } finally {
            pauseApply.complete(Unit)
            withContext(Dispatchers.Main) {
                alerts.hide()
                controller.setAutoMapVisible(auto, false)
                controller.setAutoVisible(auto, false)
                controller.setPhoneVisible(phone, false)
                navigation.state.value.sessionId?.let { navigation.finishSession(it) }
            }
            scope.cancel()
            garage.deleteVehicle(motorcycle.id)
        }
    }

    private suspend fun idleAutoHomeAcceptsOnlyOneSuitableVehicle(
        app: IzApplication, origin: WeatherCoordinate, target: RouteStop,
    ) {
        val garage = app.garage
        val navigation = app.navigation
        val diary = DiaryRepository(app)
        val diaryBefore = diary.snapshot()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val slowSearch = CompletableDeferred<Unit>()
        val delayedReply = CompletableDeferred<Unit>()
        val searches = AtomicInteger()
        val first = GarageVehicle(name = "QA Auto tek", transport = Transport.CAR, capacityLitres = 50.0,
            typicalThresholdKm = 600.0, odometerAt = 0L)
        val second = first.copy(id = UUID.randomUUID().toString(), name = "QA Auto ikinci")
        val offers = FuelOfferController(app, announce = {}, scope = scope, externalSearch = { mode, fix, current ->
            assertEquals(Transport.CAR, mode)
            assertTrue(current())
            val state = navigation.state.value
            assertNull(state.sessionId)
            assertNull(state.journey)
            val attempted = searches.incrementAndGet()
            if (attempted == 1) {
                slowSearch.complete(Unit)
                // Simulate a provider that can finish a request even after the projection disconnects.
                withContext(NonCancellable) { delayedReply.await() }
            }
            val owner = FuelStationOwner(state.routeRevision, null, null, false, null,
                state.routeRevision, mode, fix, emptyList())
            val route = PlannedRoute("qa-idle-$attempted", listOf(RouteStop("QA origin", fix.coordinate), target),
                listOf(RouteVertex(fix.coordinate, 0.0), RouteVertex(target.coordinate, 60.0)),
                222.0, 60.0, System.currentTimeMillis(), transport = mode,
                maneuvers = listOf(RouteManeuver(10, "QA", "QA", emptyList(), 0, 1, 0.0, 60.0)))
            FuelStationPlan(target.label, 222.0, null, owner, route)
        })
        val (host, home, surface) = withContext(Dispatchers.Main) {
            val car = TestCarContext.createCarContext(app)
            val drawing = CarMapSurface(car)
            val map = CarHomeScreen(car, drawing)
            val controller = SessionController(object : Session() {
                override fun onCreateScreen(intent: Intent): Screen = map
            }, car, Intent()).moveToState(Lifecycle.State.STARTED)
            ScreenController(map).moveToState(Lifecycle.State.STARTED)
            assertTrue(map.onGetTemplate() is NavigationTemplate)
            Triple(controller, map, drawing)
        }
        suspend fun display(visible: Boolean) = withContext(Dispatchers.Main) {
            offers.setAutoVisible(host, visible, home)
            offers.setAutoMapVisible(home, visible)
            if (visible) offers.setAutoMapFix(home, home.mode,
                NavigationFix(origin, System.currentTimeMillis(), 3f, 12f))
        }
        suspend fun assertNoOffer(expectedSearches: Int) {
            delay(300)
            assertEquals(expectedSearches, searches.get())
            assertNull(offers.offer.value)
            assertNull(navigation.state.value.sessionId)
            assertNull(navigation.state.value.journey)
            val after = diary.snapshot()
            assertEquals(diaryBefore.journeys, after.journeys)
            assertEquals(diaryBefore.points, after.points)
        }
        try {
            display(true)
            assertNoOffer(0)
            display(false)
            for (vehicle in listOf(first, second)) {
                garage.saveVehicle(vehicle)
                repeat(5) { index ->
                    garage.saveFill(FuelFill(vehicleId = vehicle.id, at = 1000L + index * 1000L,
                        odometerKm = 1000.0 + index * 200.0, litres = 8.0, pricePerLitre = 50.0))
                }
            }
            display(true)
            assertNoOffer(0)
            display(false)
            garage.deleteVehicle(second.id)

            display(true)
            withTimeout(5_000) { slowSearch.await() }
            display(false)
            delayedReply.complete(Unit)
            assertNoOffer(1)

            display(true)
            val offer = withTimeout(5_000) { requireNotNull(offers.offer.first { it != null }) }
            assertEquals(target.label, offer.title)
            assertNull(navigation.state.value.sessionId)
            assertNull(navigation.state.value.journey)
            assertTrue(offers.accept(offer.id))
            assertFalse(offers.accept(offer.id))
            val guided = navigation.state.value
            assertNotNull(guided.sessionId)
            assertTrue(guided.guidance)
            assertEquals(listOf(RouteStop("QA origin", origin), target), guided.route?.stops)
            assertFalse(guided.recording)
            assertNull(guided.journey)
            delay(250)
            assertEquals(2, searches.get())
            assertNull(offers.offer.value)
            val after = diary.snapshot()
            assertEquals(diaryBefore.journeys, after.journeys)
            assertEquals(diaryBefore.points, after.points)
        } finally {
            delayedReply.complete(Unit)
            display(false)
            scope.cancel()
            withContext(Dispatchers.Main) {
                host.moveToState(Lifecycle.State.DESTROYED)
                surface.close()
                navigation.state.value.sessionId?.let { navigation.finishSession(it) }
            }
            garage.deleteVehicle(first.id)
            garage.deleteVehicle(second.id)
        }
    }

    private class OfferHost : CarFuelAlertHost {
        data class Visible(val offer: FuelOffer, val remainingMs: Long, val accept: () -> Unit, val reject: () -> Unit)
        var shown: Visible? = null
            private set

        override fun show(offer: FuelOffer, remainingMs: Long, accept: () -> Unit, reject: () -> Unit,
            cancelled: (CarFuelAlertCancel) -> Unit) {
            shown = Visible(offer, remainingMs, accept, reject)
        }

        override fun dismiss() { shown = null }
    }

    private fun fix(id: String, deliver: (String, NavigationFix) -> Unit, point: WeatherCoordinate) {
        deliver(id, NavigationFix(point, System.currentTimeMillis(), 3f, 12f))
    }
}
