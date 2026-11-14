package org.iz.navigation.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline component QA with real phone owner/configured policy; not live provider or Main map proof. */
@RunWith(AndroidJUnit4::class)
class MotorcycleRoutingUiTest {
    @get:Rule val compose = createComposeRule()
    private val stops = listOf(0.0, .008, .002, .010).mapIndexed { index, lon -> RouteStop("Synthetic $index", WeatherCoordinate(35.0, 25.0 + lon)) }
    private lateinit var owner: PhoneDirectionsCoordinator

    @Before fun requireIsolatedOfflineQa() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("org.iz.navigation.qalighteditor20261113", app.packageName)
        OsmLightEditorQaTargetAdmission.requireAdmittedTarget()
        @Suppress("DEPRECATION")
        assertFalse(Manifest.permission.INTERNET in app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty())
    }

    private fun route(values: List<RouteStop>, duration: Double, preferences: RoutePreferences, id: String): PlannedRoute {
        val times = values.indices.map { duration * it / values.lastIndex }
        return PlannedRoute(id, values, values.mapIndexed { i, stop -> RouteVertex(stop.coordinate, times[i]) },
            10000.0, duration, 1000L, times, Transport.MOTORCYCLE, provider = RouteProvider.VALHALLA,
            preferences = preferences, hasHighway = false)
    }

    @Composable private fun phoneContent() {
        val state by owner.state.collectAsState()
        PhoneDirectionsContent(state, NavigationState(), emptyList(), false, {}, {}, {}, {}, {}, { owner.preview() },
            {}, {}, {}, {}, {}, {}, {}, null, {}, showMap = false,
            suggestStopOrder = { owner.suggestStopOrder() }, acceptStopOrder = owner::acceptStopOrder,
            dismissStopOrder = owner::dismissStopOrder)
    }

    @Test fun phoneValhallaFailureKeepsPreviewAndManualRetryHasNoTomTomSetupMessage() {
        val fail = AtomicBoolean(false)
        val requests = AtomicInteger()
        val tomTom = AtomicInteger()
        compose.setContent { IzTheme {
            val scope = rememberCoroutineScope()
            owner = remember { PhoneDirectionsCoordinator(scope, { stops.first().coordinate }, { _, _ -> error("Use preference-aware planner") }, {},
                planWithPreferences = { values, mode, speed, preferences ->
                    ConfiguredRoutePlanner({ TrafficCredentials("synthetic-key", true, true, "stable") }, { object : RoutePlanner {
                        override suspend fun plan(stops: List<RouteStop>, departureAt: Long, transport: Transport, travelSpeedKmh: Double?, preferences: RoutePreferences): PlannedRoute {
                            val count = requests.incrementAndGet()
                            if (fail.get()) throw RouteServiceException("Motosiklet rotası hesaplanamadı. Bağlantıyı kontrol edip yeniden dene.")
                            return route(stops, 100.0, preferences, "synthetic-$count")
                        }
                    } }, { _, _ -> tomTom.incrementAndGet(); error("Motorcycle must not use TomTom") }).plan(values, 1000L, mode, speed, preferences)
                }) }
            LaunchedEffect(Unit) { owner.enter(1L, listOf(stops.first(), stops.last()), Transport.MOTORCYCLE, locateOnOpen = false) }
            phoneContent()
        } }
        compose.runOnIdle { owner.preview() }
        compose.waitUntil(5000) { owner.state.value.preview != null && !owner.state.value.busy }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasText("Valhalla · Trafik hariç"))
        compose.onNodeWithText("Valhalla · Trafik hariç").assertIsDisplayed()
        val original = owner.state.value.preview
        fail.set(true)
        compose.runOnIdle { owner.preview() }
        compose.waitUntil(5000) { owner.state.value.message != null && !owner.state.value.busy }
        compose.runOnIdle { assertEquals(original, owner.state.value.preview); assertEquals(2, requests.get()); assertEquals(0, tomTom.get()) }
        compose.onAllNodesWithText("TomTom", substring = true).assertCountEquals(0)
        fail.set(false)
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("preview-directions"))
        compose.onNodeWithTag("preview-directions").assertIsEnabled().performClick()
        compose.waitUntil(5000) { owner.state.value.preview?.id == "synthetic-3" && !owner.state.value.busy }
        assertEquals(3, requests.get()); assertEquals(0, tomTom.get())
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-traffic-settings"))
        compose.onNodeWithTag("directions-traffic-settings").assertTextContains("Hız sınırı ayarları")
    }

    @Test fun defaultMotorcycleAvoidancesAllowHeuristicProposalButOrderChangesOnlyOnApply() {
        val settings = defaultWeatherSettings(Transport.MOTORCYCLE)
        compose.setContent { IzTheme {
            val scope = rememberCoroutineScope()
            owner = remember { PhoneDirectionsCoordinator(scope, { stops.first().coordinate }, { _, _ -> error("No preview for ordering") }, {},
                clock = { 1000L }, readSettings = { settings },
                suggestOrderWithPreferences = { values, at, mode, preferences ->
                    ConfiguredStopOrderPlanner({ TrafficCredentials(null, false, false, "") }, { 1000L },
                        matrix = { _, _, _, _, _ -> error("No Matrix request") },
                        largeSettings = { settings }, largeRoute = { points, _, _, actual, provider ->
                            check(provider == RouteProvider.VALHALLA)
                            route(points, if (points == stops) 100.0 else 60.0, actual, "synthetic-order")
                        }).propose(values, at, mode, preferences)
                }) }
            LaunchedEffect(Unit) { owner.enter(1L, stops, Transport.MOTORCYCLE, locateOnOpen = false) }
            phoneContent()
        } }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("directions-matrix-order"))
        compose.onNodeWithTag("directions-matrix-order").assertIsEnabled().performClick()
        compose.waitUntil(5000) { owner.state.value.orderProposal != null }
        compose.runOnIdle { assertEquals(stops, owner.state.value.stops); assertTrue(owner.state.value.orderProposal!!.heuristic) }
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("heuristic-order-explanation"))
        compose.onNodeWithTag("heuristic-order-explanation").assertIsDisplayed()
        compose.onAllNodesWithText("otomobil Matrix", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("navigation-screen").performScrollToNode(hasTestTag("matrix-apply-order"))
        compose.onNodeWithTag("matrix-apply-order").performClick()
        compose.runOnIdle { assertEquals(listOf(stops[0], stops[2], stops[1], stops[3]), owner.state.value.stops) }
    }

    @Test fun selectedWeatherDepartureShowsValhallaTrafficFreeContextAndMissingWeatherSeparately() {
        val departure = 1_700_000_000_000L
        val comparisons = (0..6).map { WeatherAssessment(departure + it * 1_800_000L, emptyList(), 0.0, false, null, null, null, null, null, null) }
        compose.setContent { IzTheme {
            WeatherPlannerContent(WeatherPlannerState(stops = listOf(stops.first(), stops.last()),
                route = route(listOf(stops.first(), stops.last()), 100.0, RoutePreferences.defaults(Transport.MOTORCYCLE), "synthetic-weather"),
                comparisons = comparisons, selectedDepartureAt = departure, selectedWeather = comparisons.first()),
                RideWeatherLiveState(), emptyList(), WeatherPlannerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}), routeMap = {})
        } }
        val text = "Valhalla · Trafik hariç · Hava verisi eksik"
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onAllNodesWithText("TomTom rotası hesaplandı", substring = true).assertCountEquals(0)
    }
}
