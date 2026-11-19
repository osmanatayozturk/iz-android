package org.iz.navigation.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import org.iz.navigation.data.Journey
import org.iz.navigation.data.Place
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.Transport
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationProgress
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.QuickNavigationOrigin
import org.iz.navigation.speed.RoadSpeedLimit
import org.iz.navigation.speed.RoadSpeedSource
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteManeuver
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.StopOrderProposal
import org.iz.navigation.weather.WeatherCoordinate

internal val phoneVisualSceneIds = setOf(
    "search", "vehicle", "preview", "stop", "order", "record", "drive", "lean", "operations", "end",
    "photos", "statsdetail", "reports", "compare",
    "weather", "weathermap", "weatherlive", "groupactive", "calibrate",
)

internal fun PhoneSceneHost.stagePhoneScene(id: String) {
    when (id) {
        "search", "vehicle", "preview" -> quickScene(id)
        "stop" -> {
            show { WeatherStopSourceDialog(PhoneVisualFixture.places, onDismiss = {},
                onSaved = { unexpectedVisualAction() }, onSearch = { unexpectedVisualAction() },
                onMap = { unexpectedVisualAction() }, title = "Ara durak · Sentetik mola") }
            capture(id, hasText("Ara durak · Sentetik mola"), "WeatherStopSourceDialog", "Named synthetic via target")
        }
        "order" -> orderScene()
        "record", "drive", "lean" -> ridingScene(id)
        "operations" -> operationsScene()
        "end" -> {
            val nav = PhoneVisualFixture.guided
            val request = checkNotNull(phoneSessionEndRequest(PhoneSessionEnd.ALL, nav))
            show { PhoneSessionEndConfirmation(request, nav, locked = false, onDismiss = {},
                onConfirm = { unexpectedVisualAction() }) }
            capture(id, hasTestTag("confirm-session-end"), "PhoneSessionEndConfirmation", "Synthetic guidance and recording")
        }
        "photos", "statsdetail", "reports", "compare" -> diaryScene(id)
        "weather", "weathermap", "weatherlive", "groupactive", "calibrate" -> conditionsScene(id)
        else -> error("Unallocated scene $id")
    }
}

private fun PhoneSceneHost.quickScene(id: String) {
    val nav = MutableStateFlow(NavigationState())
    val destination = PhoneVisualFixture.stops.last()
    val searchResult = SelectedOsmPlace(destination.label, destination.coordinate.latitude,
        destination.coordinate.longitude, displayName = "Sentetik Bulvar 12 · Örnek ilçe")
    show {
        QuickDirectionsDialog(
            places = PhoneVisualFixture.places,
            navigationState = nav,
            locate = { QuickNavigationOrigin(PhoneVisualFixture.fix, false, 0, null, false) },
            validateOrigin = {},
            plan = { _, _, mode, _ -> PhoneVisualFixture.route.copy(transport = mode) },
            activate = { _, _, _ -> unexpectedVisualAction() },
            onDismiss = {}, onStarted = { unexpectedVisualAction() },
            searchPlaces = { listOf(searchResult) }, hasLocationPermission = { true },
            initialDestination = destination.takeUnless { id == "search" },
            sharedPreview = id == "preview",
            previewMap = { route ->
                bindNativeDialogWindow()
                WeatherRouteMap(route, null, Modifier.fillMaxSize(), expandable = false, stops = route.stops)
            },
        )
    }
    if (id == "search") {
        compose.onNodeWithTag("quick-destinations").performScrollToNode(hasTestTag("quick-search-query"))
        compose.onNodeWithTag("quick-search-query").performTextInput("Sentetik bulvar")
        compose.onNodeWithTag("quick-search-submit").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(destination.label).fetchSemanticsNodes().isNotEmpty() }
        capture(id, hasText(destination.label), "QuickDirectionsDialog", "Local result and synthetic saved place")
    } else if (id == "preview") {
        compose.onNodeWithTag("shared-preview").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shared-start").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("shared-start").assertIsDisplayed()
        capture(id, hasTestTag("shared-preview-content"), "QuickDirectionsDialog/SharedRoutePreviewContent",
            "Synthetic route in the real preview without activation", native = true)
    } else {
        capture(id, hasTestTag("quick-mode-CAR"), "QuickDirectionsDialog", "Regular transport picker before activation")
    }
}

private fun PhoneSceneHost.orderScene() {
    val original = PhoneVisualFixture.route
    val stops = original.stops
    val reordered = listOf(stops[0], stops[2], stops[1], stops[3])
    val proposed = original.copy(id = "visual-order", stops = reordered, durationSeconds = 380.0,
        stopElapsedSeconds = listOf(0.0, 120.0, 250.0, 380.0))
    val proposal = StopOrderProposal(stops, reordered, original, proposed,
        PhoneVisualFixture.epoch, approximateMotorcycle = true,
        createdAt = PhoneVisualFixture.epoch, credentialRevision = "visual", heuristic = true)
    show { PhoneVisualScroll { StopOrderProposalCard(proposal, true,
        apply = { unexpectedVisualAction() }, dismiss = {}) } }
    capture("order", hasTestTag("matrix-order-proposal"), "StopOrderProposalCard", "Two synthetic via stops")
}

private fun PhoneSceneHost.ridingScene(id: String) {
    val lean = id == "lean"
    val nav = if (id == "record") PhoneVisualFixture.guided.copy(
        guidance = false, route = null, progress = null,
    ) else PhoneVisualFixture.guided
    show {
        val reading by produceState(
            LiveLeanReading(checkNotNull(nav.sessionId), 18.0, SystemClock.elapsedRealtime()), lean,
        ) {
            if (lean) while (isActive) {
                delay(550)
                value = LiveLeanReading(checkNotNull(nav.sessionId), 18.0, SystemClock.elapsedRealtime())
            }
        }
        NavigationHomeContent(
            ui = PhoneDirectionsState(), nav = nav, points = emptyList(), members = emptyList(),
            showPlanner = false, weatherLabel = "18° · Hava",
            onSearch = {}, onRecord = { unexpectedVisualAction() }, onGroup = {}, onMenu = {},
            onWeather = {}, onMute = {}, onManageSession = {}, onLocate = { unexpectedVisualAction() },
            onPin = {}, shortcuts = emptyList(), onShortcut = {},
            leanInstrument = if (lean) { { LiveLeanDial(checkNotNull(nav.sessionId), reading) } } else null,
            planner = {},
        )
    }
    val anchor = when (id) {
        "lean" -> hasContentDescription("Tahmini yatış, Sağ 18 derece")
        "drive" -> hasTestTag("compact-guidance-header")
        else -> hasText("Kayıt açık")
    }
    val fixture = when (id) {
        "lean" -> "Fresh synthetic lean display, no sensor capture"
        "drive" -> "Synthetic active guidance with maneuver and posted speed, recorder off"
        else -> "Synthetic free recording, recorder off"
    }
    capture(id, anchor, "NavigationHomeContent", fixture, native = true)
}

private fun PhoneSceneHost.operationsScene() {
    val route = PhoneVisualFixture.route
    val token = RouteOperationsUiToken("visual", PhoneVisualFixture.guided.sessionId, route.id, 1, 1)
    show {
        PhoneVisualScroll {
            RouteOperationsPanel(
                RouteOperationsPanelData(token, route, paused = false, online = RouteOnlineUiState.OFFLINE,
                    allowedActions = setOf(RouteOperationUiAction.PAUSE),
                    intermediateStops = listOf(RouteStopUiTarget("visual-via", route.stops[1]))),
                RouteOperationsPanelCallbacks(
                    onAvoid = { _, _ -> unexpectedVisualAction() },
                    onSkip = { _, _ -> unexpectedVisualAction() },
                    onLookup = { _, _ -> unexpectedVisualAction() },
                    onSelectPoi = { _, _, _ -> unexpectedVisualAction() },
                    onConfirm = { _, _, _ -> unexpectedVisualAction() },
                    onCancel = { _, _ -> unexpectedVisualAction() },
                    onPause = { unexpectedVisualAction() }, onResume = { unexpectedVisualAction() },
                ),
            )
        }
    }
    capture("operations", hasText("Aktif rotayı değiştir"), "RouteOperationsPanel", "Offline operation choices")
}

@Composable
internal fun PhoneVisualScroll(body: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { body() }
}

internal fun unexpectedVisualAction(): Nothing = error("The visual fixture cannot perform this action")

internal object PhoneVisualFixture {
    const val epoch = 1_795_176_000_000L
    val stops = listOf(
        RouteStop("Sentetik başlangıç", WeatherCoordinate(40.00, 32.00)),
        RouteStop("Sentetik mola", WeatherCoordinate(40.01, 32.01), 120),
        RouteStop("Sentetik seyir noktası", WeatherCoordinate(40.02, 32.00)),
        RouteStop("Sentetik varış", WeatherCoordinate(40.03, 32.02)),
    )
    val places = listOf(Place(id = "visual-place", name = "Sentetik kayıtlı yer", latitude = 40.01, longitude = 32.01))
    val route = PlannedRoute("visual-route", stops, stops.mapIndexed { index, stop -> RouteVertex(stop.coordinate, index * 140.0) },
        distanceMeters = 4_200.0, durationSeconds = 420.0, createdAt = epoch,
        stopElapsedSeconds = listOf(0.0, 140.0, 280.0, 420.0), transport = Transport.MOTORCYCLE,
        maneuvers = listOf(RouteManeuver(1, "Sentetik kavşaktan sağa dön", "Sağa dön", emptyList(), 0, 3, 0.0, 420.0)))
    val fix = NavigationFix(WeatherCoordinate(40.002, 32.002), epoch, 5f, 8f, 45f)
    val journey = Journey(id = "visual-journey", title = "Sentetik bulvar gezisi",
        transport = Transport.MOTORCYCLE, startedAt = epoch, endedAt = epoch + 420_000)
    val guided = NavigationState(sessionId = "visual-session", sessionTransport = Transport.MOTORCYCLE,
        guidance = true, recording = true, journey = journey.copy(endedAt = null), route = route, routeRevision = 1,
        gpsStale = false, locationActive = true, fix = fix,
        progress = NavigationProgress(20.0, 400.0, 4_000.0, 0.0, 0, 300.0),
        roadSpeed = RoadSpeedState(36.0, RoadSpeedLimit(50.0, RoadSpeedSource.OSM, false,
            epoch, fix.coordinate, Transport.MOTORCYCLE, "visual-road"), true))
}
