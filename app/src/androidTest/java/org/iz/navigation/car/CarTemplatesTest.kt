package org.iz.navigation.car

import android.Manifest
import androidx.car.app.model.*
import androidx.car.app.navigation.model.*
import androidx.car.app.testing.TestCarContext
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

    @Test fun freeDriveAndTemporaryCandidateUseMapContentWithoutNavigationTemplate() = onCar { _, _, home ->
        home.refresh(NavigationState())
        assertTrue(home.onGetTemplate() is MapWithContentTemplate)
        home.refresh(NavigationState(journey = Journey(transport = Transport.CAR), recording = true))
        assertTrue(home.onGetTemplate() is MapWithContentTemplate)
        home.refresh(NavigationState(journey = Journey(transport = Transport.CAR, status = JourneyStatus.TEMPORARY), recording = true))
        val template = home.onGetTemplate() as MapWithContentTemplate
        assertTrue((template.contentTemplate as PaneTemplate).title.toString().contains("Geçici"))
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

    @Test fun menusSearchPreviewWeatherAndFinishMeetHostConstraints() = onCar { context, surface, home ->
        assertTrue(CarMenuScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarModeScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarRoutesScreen(context, home).onGetTemplate() is ListTemplate)
        assertTrue(CarSearchScreen(context, home).onGetTemplate() is SearchTemplate)
        assertTrue(CarPreviewScreen(context, home, surface, route()).onGetTemplate() is MapWithContentTemplate)
        assertTrue(CarWeatherScreen(context).onGetTemplate() is MessageTemplate)
        assertTrue(CarStatisticsScreen(context, home).onGetTemplate() is PaneTemplate)
        assertTrue(CarFinishScreen(context, "not-a-real-trip").onGetTemplate() is MessageTemplate)
    }

    @Test fun hostTripContainsDestinationAndSuppressesStaleSteps() = onCar { _, _, _ ->
        val current = NavigationState(route = route(), guidance = true, gpsStale = false,
            progress = NavigationProgress(0.0, 60.0, 140.0, 0.0, 0, 50.0))
        val trip = carTrip(current)
        assertEquals(1, trip.destinations.size)
        assertEquals(1, trip.steps.size)
        assertNull("Upcoming maneuver street must not be reported as the current road", trip.currentRoad)
        assertTrue(carTrip(current.copy(gpsStale = true)).isLoading)
        assertTrue(carTrip(current.copy(gpsStale = true)).steps.isEmpty())
    }
}
