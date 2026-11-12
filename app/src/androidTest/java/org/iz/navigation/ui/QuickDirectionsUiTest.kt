package org.iz.navigation.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Place
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.rankDirectionsPlaces
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.QuickNavigationOrigin
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickDirectionsUiTest {
    @get:Rule val compose = createComposeRule()

    private val origin = QuickNavigationOrigin(
        NavigationFix(WeatherCoordinate(41.01, 29.01), 100L, 200f), precise = false,
        routeRevision = 0L, sessionId = null, requiresFinePermission = false,
    )

    @Test fun imeSearchAndBackPreserveChoicesBeforeSingleSavedPlaceStart() {
        val nav = MutableStateFlow(NavigationState())
        val ready = CompletableDeferred<Unit>()
        val searchQueries = mutableListOf<String>()
        val starts = mutableListOf<Triple<PlannedRoute, QuickNavigationOrigin, Boolean>>()
        var validatedOrigin: QuickNavigationOrigin? = null
        var completion = 0
        var choicesAtPlan = emptyMap<QuickRoutePreference, Boolean>()
        compose.setContent {
            IzTheme {
                QuickDirectionsDialog(
                    places = listOf(Place(name = "Kayıtlı kıyı", latitude = 41.02, longitude = 29.02)),
                    navigationState = nav, locate = { origin }, validateOrigin = { validatedOrigin = it },
                    plan = { coordinate, destination, transport, choices ->
                        assertSame(origin, validatedOrigin)
                        choicesAtPlan = choices
                        ready.await()
                        testRoute(coordinate, destination, transport)
                    },
                    activate = { route, fix, record -> starts += Triple(route, fix, record); route.id },
                    onDismiss = {}, onStarted = { completion++ }, hasLocationPermission = { true },
                    searchPlaces = { query -> searchQueries += query; listOf(SelectedOsmPlace("OSM kıyısı", 41.03, 29.03)) },
                )
            }
        }

        val destinations = compose.onNodeWithTag("quick-destinations")
        val tollPreference = hasTestTag("quick-preference-TOLLS")
        destinations.performScrollToNode(tollPreference)
        compose.onNode(tollPreference).performClick()
        destinations.performScrollToNode(hasTestTag("quick-search-query"))
        compose.onNodeWithTag("quick-search-query").performTextInput("kıyı")
        compose.runOnIdle { assertEquals(emptyList<String>(), searchQueries) }
        compose.onNodeWithTag("quick-search-query").performImeAction()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("OSM kıyısı").fetchSemanticsNodes().isNotEmpty() }
        destinations.performScrollToNode(hasText("OSM kıyısı"))
        compose.onNodeWithText("OSM kıyısı").performClick()
        compose.onNodeWithTag("quick-route-options").assertIsDisplayed()
        compose.onNodeWithText("Ücretli yol seçimi bisiklet, yürüyüş ve koşuda uygulanmaz.").assertIsDisplayed()
        compose.onNodeWithTag("quick-back").performClick()
        destinations.performScrollToNode(tollPreference)
        compose.onNode(tollPreference).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Bu gezi için kaçın"))
        destinations.performScrollToNode(hasText("Kayıtlı kıyı"))
        compose.onNodeWithText("Kayıtlı kıyı").performClick()

        val modes = compose.onNodeWithTag("quick-route-options")
        modes.performScrollToNode(hasTestTag("quick-record"))
        compose.onNodeWithTag("quick-record").assertContentDescriptionEquals("Yolculuğu kaydet")
            .assertIsOn().performClick().assertIsOff()
        modes.performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").performClick().assertIsNotEnabled()
        compose.runOnIdle { ready.complete(Unit) }
        compose.runOnIdle {
            assertEquals(listOf("kıyı"), searchQueries)
            assertEquals(mapOf(QuickRoutePreference.TOLLS to true), choicesAtPlan)
            val (route, fix, record) = starts.single()
            assertEquals("Kayıtlı kıyı", route.stops.last().label)
            assertEquals(Transport.CAR, route.transport)
            assertSame(origin, fix)
            assertFalse(record)
            assertEquals(1, completion)
        }
    }

    @Test fun osmResultStartsOnceWithTheSelectedCoordinates() {
        val nav = MutableStateFlow(NavigationState())
        val searchOrigin = WeatherCoordinate(39.93, 32.85)
        val expected = WeatherCoordinate(39.94, 32.86)
        val nearby = SelectedOsmPlace("Yahya Çavuş İlkokulu", expected.latitude, expected.longitude,
            OsmRef(OsmType.WAY, 363072750), "Yahya Çavuş İlkokulu, Ankara")
        val distant = SelectedOsmPlace("Yahya Çavuş", 41.01, 29.01, displayName = "Yahya Çavuş, İstanbul")
        val plannedDestinations = mutableListOf<WeatherCoordinate>()
        val activatedRoutes = mutableListOf<PlannedRoute>()
        var completion = 0
        compose.setContent {
            IzTheme {
                QuickDirectionsDialog(
                    places = emptyList(), navigationState = nav, locate = { origin }, validateOrigin = {},
                    plan = { coordinate, destination, transport, _ ->
                        plannedDestinations += destination.coordinate
                        testRoute(coordinate, destination, transport)
                    },
                    activate = { route, fix, _ ->
                        assertSame(origin, fix)
                        activatedRoutes += route
                        route.id
                    },
                    onDismiss = {}, onStarted = { completion++ }, hasLocationPermission = { true },
                    searchPlaces = {
                        rankDirectionsPlaces(listOf(distant, nearby), searchOrigin)
                    },
                )
            }
        }

        val destinations = compose.onNodeWithTag("quick-destinations")
        destinations.performScrollToNode(hasTestTag("quick-search-query"))
        compose.onNodeWithTag("quick-search-query").performTextInput("Yahya Çavuş")
        compose.onNodeWithTag("quick-search-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(nearby.name).fetchSemanticsNodes().isNotEmpty() }
        // Title, search field and preferences precede the two settled results.
        destinations.performScrollToIndex(3)
        val nearRow = compose.onNodeWithText(nearby.name).assertIsDisplayed()
        val farRow = compose.onNode(hasText(distant.name) and !hasSetTextAction()).assertIsDisplayed()
        assertTrue(nearRow.fetchSemanticsNode().boundsInRoot.top < farRow.fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText(checkNotNull(distant.displayName), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(checkNotNull(nearby.displayName), useUnmergedTree = true).assertIsDisplayed()
        nearRow.performClick()
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").performClick()
        compose.runOnIdle {
            assertEquals(listOf(expected), plannedDestinations)
            val activated = activatedRoutes.single()
            assertEquals(Transport.CAR, activated.transport)
            assertEquals(expected, activated.stops.last().coordinate)
            assertEquals(1, completion)
        }
    }

    @Test fun backWhilePlanningDiscardsLateRouteWithoutActivation() {
        val nav = MutableStateFlow(NavigationState())
        val ready = CompletableDeferred<Unit>()
        var planning = false
        var activations = 0
        compose.setContent {
            IzTheme {
                QuickDirectionsDialog(
                    places = listOf(Place(name = "Kayıtlı kıyı", latitude = 41.02, longitude = 29.02)),
                    navigationState = nav, locate = { origin }, validateOrigin = {},
                    plan = { coordinate, destination, transport, _ ->
                        planning = true
                        withContext(NonCancellable) { ready.await() }
                        testRoute(coordinate, destination, transport)
                    },
                    activate = { route, _, _ -> activations++; route.id },
                    onDismiss = {}, onStarted = {}, hasLocationPermission = { true },
                )
            }
        }
        compose.onNodeWithTag("quick-destinations").performScrollToNode(hasText("Kayıtlı kıyı"))
        compose.onNodeWithText("Kayıtlı kıyı").performClick()
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("quick-back").performClick()
        compose.onNodeWithTag("quick-destinations").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(true, planning)
            ready.complete(Unit)
        }
        compose.runOnIdle { assertEquals(0, activations) }
    }

    @Test fun preselectedShortcutSkipsSearchAndStartsOnceWithModeDefaults() {
        val nav = MutableStateFlow(NavigationState())
        val destination = RouteStop("Kayıtlı ev", WeatherCoordinate(41.02, 29.02))
        val finishPlanning = CompletableDeferred<Unit>()
        var locations = 0
        var starts = 0
        compose.setContent {
            IzTheme {
                QuickDirectionsDialog(
                    places = emptyList(), navigationState = nav,
                    initialDestination = destination,
                    locate = { locations++; origin }, validateOrigin = { assertSame(origin, it) },
                    plan = { coordinate, stop, transport, choices ->
                        assertEquals(destination, stop)
                        assertEquals(Transport.WALK, transport)
                        assertEquals(emptyMap<QuickRoutePreference, Boolean>(), choices)
                        finishPlanning.await()
                        testRoute(coordinate, stop, transport)
                    },
                    activate = { route, fix, record ->
                        assertEquals(destination, route.stops.last())
                        assertSame(origin, fix)
                        assertFalse(record)
                        starts++
                        route.id
                    },
                    onDismiss = {}, onStarted = {}, hasLocationPermission = { true },
                    searchPlaces = { error("Shortcut must not search") },
                )
            }
        }

        compose.onNodeWithTag("quick-destinations").assertDoesNotExist()
        compose.onNodeWithText("Kayıtlı ev").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, locations) }
        val options = compose.onNodeWithTag("quick-route-options")
        options.performScrollToNode(hasTestTag("quick-record"))
        compose.onNodeWithTag("quick-record").assertIsOn().performClick()
        options.performScrollToNode(hasTestTag("quick-mode-WALK"))
        compose.onNodeWithTag("quick-mode-WALK").performClick().assertIsNotEnabled()
        compose.runOnIdle { finishPlanning.complete(Unit) }
        compose.runOnIdle {
            assertEquals(1, locations)
            assertEquals(1, starts)
        }
    }

    @Test fun shortcutBackDismissesAndDiscardsLatePlan() {
        val nav = MutableStateFlow(NavigationState())
        val destination = RouteStop("Kayıtlı iş", WeatherCoordinate(41.02, 29.02))
        val finishPlanning = CompletableDeferred<Unit>()
        var planning = false
        var starts = 0
        var dismissals = 0
        compose.setContent {
            IzTheme {
                QuickDirectionsDialog(
                    places = emptyList(), navigationState = nav,
                    initialDestination = destination,
                    locate = { origin }, validateOrigin = {},
                    plan = { coordinate, stop, transport, _ ->
                        planning = true
                        withContext(NonCancellable) { finishPlanning.await() }
                        testRoute(coordinate, stop, transport)
                    },
                    activate = { route, _, _ -> starts++; route.id },
                    onDismiss = { dismissals++ }, onStarted = {}, hasLocationPermission = { true },
                )
            }
        }

        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").performClick().assertIsNotEnabled()
        compose.onNodeWithTag("quick-back").performClick()
        compose.runOnIdle {
            assertEquals(true, planning)
            assertEquals(1, dismissals)
            finishPlanning.complete(Unit)
        }
        compose.runOnIdle { assertEquals(0, starts) }
    }

    private fun testRoute(coordinate: WeatherCoordinate, destination: RouteStop, transport: Transport) =
        PlannedRoute("quick-test", listOf(RouteStop("Konum", coordinate), destination),
            listOf(RouteVertex(coordinate, 0.0), RouteVertex(destination.coordinate, 60.0)),
            1_200.0, 60.0, 100L, transport = transport)
}
