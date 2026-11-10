package org.iz.navigation.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import okhttp3.FormBody
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.DirectionsPlaceSearch
import org.iz.navigation.integration.DirectionsViewbox
import org.iz.navigation.integration.NominatimSearch
import org.iz.navigation.integration.OsmReadBudget
import org.iz.navigation.integration.OverpassRead
import org.iz.navigation.integration.PartialNameOverpassSearch
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.QuickNavigationOrigin
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** No socket, location provider, OSM account or route service. Uses the production search pipeline. */
@RunWith(AndroidJUnit4::class)
class PartialNameDirectionsUiTest {
    @get:Rule val compose = createComposeRule()
    private val publicOrigin = WeatherCoordinate(39.93, 32.85)

    @Test fun hamamPartialServiceResultReachesCarSelection() = serviceResultToTransport(
        "Kecioren Hamam", SelectedOsmPlace("Keçiören Hamamı", 40.0, 32.86, OsmRef(OsmType.NODE, 14170085798)),
        "[]", """{"elements":[{"type":"node","id":14170085798,"lat":40.0,"lon":32.86,"tags":{"name":"Keçiören Hamamı"}}]}""",
        "Adres bilgisi yok",
    )

    @Test fun tobbInitialsServiceResultKeepsUniversitySeparateAndReachesCarSelection() = serviceResultToTransport(
        "TOBB ET", SelectedOsmPlace("TOBB Ekonomi ve Teknoloji Üniversitesi", 39.921, 32.798, OsmRef(OsmType.WAY, 61403953)),
        """[{"osm_type":"node","osm_id":900000001,"name":"Több út","lat":"47.5","lon":"19.1"}]""",
        """{"elements":[{"type":"way","id":61403953,"center":{"lat":39.921,"lon":32.798},"tags":{"name":"TOBB Ekonomi ve Teknoloji Üniversitesi","addr:street":"Söğütözü Caddesi","addr:housenumber":"43"}},{"type":"way","id":900000003,"center":{"lat":39.922,"lon":32.799},"tags":{"name":"TOBB ETÜ Hastanesi"}}]}""",
        "Söğütözü Caddesi 43",
    )

    private fun serviceResultToTransport(query: String, target: SelectedOsmPlace, primary: String, secondary: String, address: String) {
        val requests = CopyOnWriteArrayList<Request>()
        val locations = AtomicInteger()
        var budget = OsmReadBudget()
        fun execute(request: Request): Response {
            requests += request
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body((if (request.method == "GET") primary else secondary).toResponseBody()).build()
        }
        val overpass = OverpassRead({ budget }, { budget = it; true }, ::execute)
        val nearby = PartialNameOverpassSearch(Mutex(), { "https://fixture.invalid/overpass" }, { null }, { _, _ -> }, overpass::query)
        val nominatim = NominatimSearch(Mutex(), { "https://fixture.invalid/search" }, { null }, { _, _ -> },
            { 0 }, { _, _ -> true }, ::execute)
        val pipeline = DirectionsPlaceSearch(nominatim, nearby)
        val submit: suspend (String) -> List<SelectedOsmPlace> = { original ->
            pipeline.search(original) { locations.incrementAndGet(); publicOrigin }.also { results ->
                val actual = results.single { it.osmRef == target.osmRef }
                assertEquals(target.name, actual.name)
                assertEquals(target.latitude, actual.latitude, 0.0); assertEquals(target.longitude, actual.longitude, 0.0)
                if (query == "TOBB ET") assertEquals(2, results.map { it.osmRef }.distinct().size)
            }
        }
        val fix = QuickNavigationOrigin(NavigationFix(publicOrigin, 100L, 200f), precise = false,
            routeRevision = 0L, sessionId = null, requiresFinePermission = false)
        val planned = CopyOnWriteArrayList<RouteStop>()
        val activated = CopyOnWriteArrayList<PlannedRoute>()
        val navigationState = MutableStateFlow(NavigationState())
        var completed = 0
        compose.setContent {
            IzTheme {
                QuickDirectionsDialog(
                    places = emptyList(), navigationState = navigationState, locate = { fix }, validateOrigin = {},
                    plan = { coordinate, destination, transport, _ ->
                        planned += destination
                        PlannedRoute("partial-synthetic", listOf(RouteStop("Konum", coordinate), destination),
                            listOf(RouteVertex(coordinate, 0.0), RouteVertex(destination.coordinate, 60.0)),
                            1_200.0, 60.0, 100L, transport = transport)
                    },
                    activate = { route, _, _ -> activated += route; route.id },
                    onDismiss = {}, onStarted = { completed++ }, hasLocationPermission = { true }, searchPlaces = submit,
                )
            }
        }
        val list = compose.onNodeWithTag("quick-destinations")
        list.performScrollToNode(hasTestTag("quick-search-query"))
        compose.onNodeWithTag("quick-search-query").performTextInput(query)
        compose.runOnIdle { assertTrue("Typing must not send any request", requests.isEmpty()) }
        compose.onNodeWithTag("quick-search-submit").performClick()
        compose.waitUntil(5_000) {
            requests.size == 2 && compose.onAllNodes(hasTestTag("quick-search-submit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("quick-search-query").assertTextContains(query)
        val targetRow = hasText(target.name) and !hasSetTextAction()
        list.performScrollToNode(targetRow)
        compose.onNode(targetRow).assertIsDisplayed()
        compose.onNodeWithText(address, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(targetRow).performClick()
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            assertEquals(query, requests.first().url.queryParameter("q"))
            val box = requireNotNull(DirectionsViewbox.around(publicOrigin))
            assertEquals(box.value, requests.first().url.queryParameter("viewbox"))
            assertTrue((requests.last().body as FormBody).value(0).contains("nwr(${box.overpassValue})"))
            assertEquals(1, locations.get()); assertEquals(1, budget.queries)
            val destination = planned.single()
            assertEquals(target.name, destination.label)
            assertEquals(WeatherCoordinate(target.latitude, target.longitude), destination.coordinate)
            assertEquals(Transport.CAR, activated.single().transport)
            assertEquals(destination, activated.single().stops.last()); assertEquals(1, completed)
        }
    }
}
