package org.iz.navigation.car

import android.Manifest
import androidx.car.app.OnDoneCallback
import androidx.car.app.ScreenManager
import androidx.car.app.model.*
import androidx.car.app.navigation.model.*
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real AndroidX template builders and their runtime content constraints. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class CarTemplatesTest {
    private fun onCar(block: (TestCarContext, CarMapSurface, CarHomeScreen) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = ApplicationProvider.getApplicationContext<IzApplication>()
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.ACCESS_FINE_LOCATION)
        instrumentation.runOnMainSync {
            val context = TestCarContext.createCarContext(app)
            val surface = CarMapSurface(context)
            try { block(context, surface, CarHomeScreen(context, surface)) }
            finally { surface.close() }
        }
    }

    private fun route(): PlannedRoute {
        val a = WeatherCoordinate(41.0, 29.0)
        val b = WeatherCoordinate(41.001, 29.001)
        return PlannedRoute("car-template-test", listOf(RouteStop("Başlangıç", a), RouteStop("Hedef", b)),
            listOf(RouteVertex(a, 0.0), RouteVertex(b, 60.0)), 140.0, 60.0, System.currentTimeMillis(),
            transport = Transport.CAR, maneuvers = listOf(RouteManeuver(10, "Sağa dönün", "Sağa dönün",
                listOf("Test Caddesi"), 0, 1, 0.0, 60.0)))
    }

    @Test fun routeLessHomeRecordingAndCandidateHaveActionsWithoutRouteGuidance() = onCar { _, _, home ->
        val states = listOf(
            "idle" to NavigationState(),
            "recording" to NavigationState(journey = Journey(transport = Transport.CAR), recording = true),
            "temporary" to NavigationState(
                journey = Journey(transport = Transport.CAR, status = JourneyStatus.TEMPORARY),
                recording = true,
            ),
        )
        for ((name, state) in states) {
            home.refresh(state)
            val template = home.onGetTemplate()
            assertTrue("$name must expose the route-less map", template is NavigationTemplate)
            template as NavigationTemplate
            assertNull("$name navigation instructions", template.navigationInfo)
            assertNull("$name destination estimate", template.destinationTravelEstimate)
            assertTrue("$name directions action",
                template.actionStrip?.actions.orEmpty().any { it.title.toString() == "Yol tarifi" })
        }
    }

    @Test fun guidanceStaleGpsOffRouteAndNoManeuverAllBuildValidTemplates() = onCar { _, _, home ->
        val current = NavigationState(journey = Journey(transport = Transport.CAR), recording = true,
            guidance = true, route = route(), gpsStale = false,
            progress = NavigationProgress(0.0, 60.0, 140.0, 0.0, 0, 50.0))
        home.refresh(current)
        assertTrue((home.onGetTemplate() as NavigationTemplate).navigationInfo is RoutingInfo)
        home.refresh(current.copy(gpsStale = true))
        assertTrue((home.onGetTemplate() as NavigationTemplate).navigationInfo is MessageInfo)
        home.refresh(current.copy(progress = current.progress!!.copy(offRoute = true)))
        assertTrue((home.onGetTemplate() as NavigationTemplate).navigationInfo is MessageInfo)
        home.refresh(current.copy(progress = current.progress.copy(maneuverIndex = -1)))
        assertTrue((home.onGetTemplate() as NavigationTemplate).navigationInfo is MessageInfo)
    }

    @Test fun routeLessDirectionsActionOpensMenuAndPreviewKeepsGuidanceSeparate() = onCar { context, _, home ->
        val controller = ScreenController(home).moveToState(Lifecycle.State.STARTED)
        val screens = context.getCarService(ScreenManager::class.java)
        try {
            val map = home.onGetTemplate() as NavigationTemplate
            val directions = map.actionStrip!!.actions.single { it.title.toString() == "Yol tarifi" }
            requireNotNull(directions.onClickDelegate).sendClick(object : OnDoneCallback {})

            val menu = screens.top as CarMenuScreen
            val rows = (menu.onGetTemplate() as ListTemplate).singleList!!.items.filterIsInstance<Row>()
            assertTrue(rows.any { it.title.toString() == "Hedef ara" })
            assertTrue(rows.any { it.title.toString() == "İstatistikler" })
            assertTrue(rows.none { it.title.toString() == "Hazır rotalar" })
            val options = rows.single { it.title.toString() == "Sürüş ve harita" }
            requireNotNull(options.onClickDelegate).sendClick(object : OnDoneCallback {})
            val driving = screens.top as CarDrivingOptionsScreen
            val drivingRows = (driving.onGetTemplate() as ListTemplate).singleList!!.items.filterIsInstance<Row>()
            assertTrue(drivingRows.any { it.title.toString() == "Hazır rotalar" })

            val planned = route()
            home.preview(planned)
            val preview = screens.top as CarPreviewScreen
            val pane = (preview.onGetTemplate() as MapWithContentTemplate).contentTemplate as PaneTemplate
            assertTrue(pane.pane.rows.any { it.title.toString() == planned.stops.last().label })
            assertTrue(pane.pane.actions.any { it.title.toString() == "Başlat" })
            assertNull((home.onGetTemplate() as NavigationTemplate).destinationTravelEstimate)

            screens.popToRoot()
            home.refresh(NavigationState(guidance = true, route = planned, gpsStale = false,
                progress = NavigationProgress(0.0, 60.0, 140.0, 0.0, 0, 50.0)))
            val guidance = home.onGetTemplate() as NavigationTemplate
            assertNotNull(guidance.destinationTravelEstimate)
            assertTrue(guidance.actionStrip!!.actions.none { it.title.toString() == "Yol tarifi" })
        } finally {
            controller.moveToState(Lifecycle.State.DESTROYED)
        }
    }

    @Test fun savedPlacesTemplateOffersSearchAndBackOnEntry() = onCar { context, _, home ->
        val places = CarPlacesScreen(context, home)
        val template = places.onGetTemplate() as ListTemplate
        assertEquals("carplaces", places.marker)
        assertEquals("Hedef ve yerler", template.title.toString())
        assertEquals(androidx.car.app.model.Action.BACK, template.headerAction)
        assertEquals(listOf("Hedef ara"), template.actionStrip?.actions?.map { it.title.toString() })
    }

    @Test fun menusSearchPreviewWeatherAndFinishMeetHostConstraints() = onCar { context, surface, home ->
        assertTrue(CarMenuScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarDrivingOptionsScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarCameraScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarModeScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarRoutesScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarSearchScreen(context, home).onGetTemplate() is SearchTemplate)
        assertTrue(CarPreviewScreen(context, home, surface, route()).onGetTemplate() is MapWithContentTemplate)
        assertTrue(CarWeatherScreen(context).onGetTemplate() is MessageTemplate)
        home.refresh(NavigationState(journey = Journey(id = "pending-car-statistics", transport = Transport.CAR), recording = true))
        val pendingStatistics = CarStatisticsScreen(context, home).onGetTemplate()
        assertTrue("Active recording statistics must be deferred instead of showing live measurements", pendingStatistics is MessageTemplate)
        val message = (pendingStatistics as MessageTemplate).message.toString()
        assertTrue("Deferred statistics must explain their pending state", message.isNotBlank())
        assertFalse("Pending statistics must not invent a speed measurement", message.contains("km/sa"))
        assertTrue(CarFinishScreen(context, "not-a-real-trip").onGetTemplate() is MessageTemplate)
    }

    @Test fun routeOverviewIsAbsentForRouteLessRecordingAndReturnsWithRoute() = onCar { context, _, home ->
        home.refresh(NavigationState(recording = true, journey = Journey(transport = Transport.CAR)))
        val withoutRoute = CarCameraScreen(context, home).onGetTemplate() as ListTemplate
        assertEquals(2, withoutRoute.singleList!!.items.size)
        home.refresh(NavigationState(recording = true, route = route(), journey = Journey(transport = Transport.CAR)))
        val withRoute = CarCameraScreen(context, home).onGetTemplate() as ListTemplate
        assertEquals(3, withRoute.singleList!!.items.size)
    }

    @Test fun hostTripContainsDestinationAndSuppressesStaleSteps() = onCar { _, _, _ ->
        val current = NavigationState(route = route(), guidance = true, gpsStale = false,
            progress = NavigationProgress(0.0, 60.0, 140.0, 0.0, 0, 50.0))
        val trip = carTrip(current)
        assertEquals(1, trip.destinations.size)
        val step = trip.steps.single()
        assertEquals("Sağa dönün · Test Caddesi", step.cue.toString())
        assertEquals("Test Caddesi", step.road.toString())
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, step.maneuver!!.type)
        assertEquals(50.0, trip.stepTravelEstimates.single().remainingDistance!!.displayDistance, 0.0)
        assertNull("Upcoming maneuver street must not be reported as the current road", trip.currentRoad)
        assertTrue(carTrip(current.copy(gpsStale = true)).isLoading)
        assertTrue(carTrip(current.copy(gpsStale = true)).steps.isEmpty())
    }
}
