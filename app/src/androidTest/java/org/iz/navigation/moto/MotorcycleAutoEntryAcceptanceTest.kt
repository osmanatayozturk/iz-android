package org.iz.navigation.moto

import android.Manifest
import android.content.Intent
import androidx.car.app.OnDoneCallback
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.SessionController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.car.CarDrivingOptionsScreen
import org.iz.navigation.car.CarHomeScreen
import org.iz.navigation.car.CarMenuScreen
import org.iz.navigation.car.CarVehicleScreen
import org.iz.navigation.car.IzCarSession
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Transport
import org.iz.navigation.garage.GarageVehicle
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MotorcycleAutoEntryAcceptanceTest {
    @Test fun hostRecordingMenuWaitsForAChoiceAndBindsTheSelectedVehicle() {
        val context = requireMotorcycleQaTarget()
        val app = context.applicationContext as IzApplication
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val garage = app.garage
        val navigation = app.navigation
        val diary = DiaryRepository(app)
        val alpha = GarageVehicle(name = "QA Auto A", transport = Transport.CAR, capacityLitres = 40.0)
        val beta = GarageVehicle(name = "QA Auto B", transport = Transport.CAR, capacityLitres = 55.0)
        val moto = GarageVehicle(name = "QA Moto", transport = Transport.MOTORCYCLE, capacityLitres = 12.0)
        assertNull(navigation.state.value.sessionId)
        assertTrue(runBlocking { garage.vehicles.first() }.isEmpty())
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        runBlocking { listOf(alpha, beta, moto).forEach { garage.saveVehicle(it) } }
        val intent = Intent(context, MainActivity::class.java)
        try {
            // The AndroidX host does not supply a system car role. Keep a synthetic foreground app
            // for the recorder's Android while-in-use permission while operating the real Car screen.
            ActivityScenario.launch<MainActivity>(intent).use {
                runBlocking {
                    val (host, screens) = withContext(Dispatchers.Main) {
                        val car = TestCarContext.createCarContext(app)
                        val controller = SessionController(IzCarSession(), car, Intent())
                            .moveToState(Lifecycle.State.STARTED)
                        controller to car.getCarService(ScreenManager::class.java)
                    }
                    try {
                        withContext(Dispatchers.Main) {
                            val home = screens.top as CarHomeScreen
                            ScreenController(home).moveToState(Lifecycle.State.STARTED)
                            assertPreviewBudget(home, screens)
                            val action = (home.onGetTemplate() as NavigationTemplate).actionStrip!!.actions
                                .single { it.title.toString() == "Yol tarifi" }
                            requireNotNull(action.onClickDelegate).sendClick(object : OnDoneCallback {})
                            val opened = screens.top as CarMenuScreen
                            ScreenController(opened).moveToState(Lifecycle.State.STARTED)
                            val rows = (opened.onGetTemplate() as ListTemplate).singleList!!.items.filterIsInstance<Row>()
                            click(rows.single { it.title.toString() == "Kaydı başlat" })
                        }
                        val (picker, choices) = withTimeout(5_000) {
                            var ready: Pair<CarVehicleScreen, List<Row>>? = null
                            while (ready == null) {
                                ready = withContext(Dispatchers.Main) {
                                    val current = screens.top as? CarVehicleScreen ?: return@withContext null
                                    if (!current.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                        ScreenController(current).moveToState(Lifecycle.State.STARTED)
                                    }
                                    val outer = current.onGetTemplate() as MapWithContentTemplate
                                    val rows = (outer.contentTemplate as ListTemplate).singleList?.items.orEmpty().filterIsInstance<Row>()
                                    if (rows.none { it.title.toString() == beta.name }) null else current to rows
                                }
                                if (ready == null) delay(30)
                            }
                            requireNotNull(ready)
                        }
                        assertEquals(listOf(alpha.name, beta.name), choices.map { it.title.toString() })
                        assertNull(navigation.state.value.sessionId)
                        withContext(Dispatchers.Main) {
                            assertSame(picker, screens.top)
                            click(choices.single { it.title.toString() == beta.name })
                        }
                        val active = withTimeout(8_000) { navigation.state.first { it.recording && it.journey != null } }
                        val trip = requireNotNull(active.journey)
                        assertEquals(Transport.CAR, trip.transport)
                        assertEquals(beta.id, withTimeout(5_000) { garage.assignedVehicleId(trip.id).first { it != null } })
                        assertEquals(trip.id, diary.activeJourney()?.id)
                        assertEquals(active.sessionId, navigation.state.value.sessionId)
                    } finally { withContext(Dispatchers.Main) { host.moveToState(Lifecycle.State.DESTROYED) } }
                }
            }
        } finally {
            runBlocking {
                val active = navigation.state.value
                withContext(Dispatchers.Main) { active.sessionId?.let { navigation.finishSession(it) } }
                active.journey?.id?.let { if (diary.getJourney(it) != null) diary.deleteJourney(it) }
                listOf(alpha, beta, moto).forEach { garage.deleteVehicle(it.id) }
            }
        }
    }

    private fun assertPreviewBudget(home: CarHomeScreen, screens: ScreenManager) {
        val origin = WeatherCoordinate(0.27, -140.0)
        val target = WeatherCoordinate(0.27, -139.998)
        val route = PlannedRoute("qa-host-preview", listOf(RouteStop("QA başlangıç", origin), RouteStop("QA hedef", target)),
            listOf(RouteVertex(origin, 0.0), RouteVertex(target, 60.0)), 222.0, 60.0, System.currentTimeMillis(),
            transport = Transport.CAR)

        fun verify(terminal: Boolean) {
            fun assertShape(preview: Screen, ready: Boolean) {
                val outer = preview.onGetTemplate()
                val pane = if (terminal) {
                    assertTrue("The fifth step requires a terminal PaneTemplate", outer is PaneTemplate)
                    outer as PaneTemplate
                } else {
                    assertTrue("Shallow preview keeps the map", outer is MapWithContentTemplate)
                    (outer as MapWithContentTemplate).contentTemplate as PaneTemplate
                }
                assertEquals("QA hedef", pane.pane.rows.first().title.toString())
                assertEquals(ready, pane.pane.actions.any { it.title.toString() == "Başlat" })
            }

            home.plan(route.stops.last(), vehicleChosen = true)
            val pending = screens.top
            ScreenController(pending).moveToState(Lifecycle.State.CREATED)
            assertShape(pending, ready = false)
            screens.pop()

            home.preview(route, vehicleChosen = true)
            val prepared = screens.top
            ScreenController(prepared).moveToState(Lifecycle.State.STARTED)
            assertShape(prepared, ready = true)
            screens.pop()
        }

        assertEquals(1, screens.screenStack.size)
        verify(terminal = false)
        val directions = (home.onGetTemplate() as NavigationTemplate).actionStrip!!.actions
            .single { it.title.toString() == "Yol tarifi" }
        requireNotNull(directions.onClickDelegate).sendClick(object : OnDoneCallback {})
        val menu = screens.top as CarMenuScreen
        ScreenController(menu).moveToState(Lifecycle.State.STARTED)
        click((menu.onGetTemplate() as ListTemplate).singleList!!.items.filterIsInstance<Row>()
            .single { it.title.toString() == "Sürüş ve harita" })
        val options = screens.top as CarDrivingOptionsScreen
        ScreenController(options).moveToState(Lifecycle.State.STARTED)
        click((options.onGetTemplate() as ListTemplate).singleList!!.items.filterIsInstance<Row>()
            .single { it.title.toString() == "Hazır rotalar" })
        ScreenController(screens.top).moveToState(Lifecycle.State.STARTED)
        assertEquals(4, screens.screenStack.size)
        verify(terminal = true)
        screens.popToRoot()
        assertSame(home, screens.top)
    }

    private fun click(row: Row) {
        requireNotNull(row.onClickDelegate).sendClick(object : OnDoneCallback {})
    }
}
