package org.iz.navigation.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.iz.navigation.group.GroupBackend
import org.iz.navigation.group.GroupCoordinator
import org.iz.navigation.group.GroupNavigation
import org.iz.navigation.integration.WeatherRouteMap
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.weather.RideWeatherLiveState
import org.iz.navigation.weather.RideWeatherStatus
import org.iz.navigation.weather.RouteWeatherSample
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherReading
import org.json.JSONObject

internal fun PhoneSceneHost.conditionsScene(id: String) {
    when (id) {
        "weather", "weathermap", "weatherlive" -> weatherScene(id)
        "groupactive" -> groupScene()
        "calibrate" -> {
            show { PhoneVisualScroll { HandlebarCalibrationControl(hasProfile = false, canCalibrate = { true },
                onSave = { unexpectedVisualAction() }, onReset = { unexpectedVisualAction() },
                capture = { _, _, _ -> unexpectedVisualAction() }) } }
            compose.onNodeWithTag("handlebar-calibration-open").performClick()
            capture(id, hasTestTag("handlebar-calibration-start"), "HandlebarCalibrationControl",
                "Parked setup prompt before any sensor use")
        }
        else -> error("Unallocated weather/settings scene $id")
    }
}

private fun PhoneSceneHost.weatherScene(id: String) {
    val now = System.currentTimeMillis()
    val route = PhoneVisualFixture.route
    val reading = WeatherReading(18.0, 25.0, 0.0, 12.0, 19.0, 180.0)
    val samples = route.stops.mapIndexed { index, stop ->
        RouteWeatherSample(stop.coordinate, index * 140.0, now + index * 140_000, reading, emptySet(), complete = true)
    }
    val assessment = WeatherAssessment(now, samples, 0.0, true, 18.0, 18.0, 25.0, 12.0, 19.0, now)
    val live = if (id == "weatherlive") RideWeatherLiveState(
        RideWeatherStatus.READY, journeyId = PhoneVisualFixture.journey.id, route = route, assessment = assessment,
        remainingMeters = 3_600.0, arrivalAt = now + 400_000, remainingSeconds = 400.0, timingUpdatedAt = now,
    ) else RideWeatherLiveState()
    val actions = WeatherPlannerActions(
        chooseStop = { unexpectedVisualAction() }, useCurrentOrigin = { unexpectedVisualAction() },
        removeStop = { unexpectedVisualAction() }, addVia = { unexpectedVisualAction() },
        pickDate = { unexpectedVisualAction() }, pickTime = { unexpectedVisualAction() }, departNow = { unexpectedVisualAction() },
        calculate = { unexpectedVisualAction() }, selectDeparture = { unexpectedVisualAction() },
        start = { unexpectedVisualAction() }, openSettings = { unexpectedVisualAction() },
        refreshLive = { unexpectedVisualAction() }, stopLive = { unexpectedVisualAction() },
    )
    show {
        WeatherPlannerContent(WeatherPlannerState(stops = route.stops, departureAt = now, route = route,
            selectedWeather = assessment, effectiveDepartureAt = now), live, PhoneVisualFixture.places, actions,
            routeMap = {
                WeatherRouteMap(route, assessment, Modifier.fillMaxWidth().height(280.dp).testTag("visual-forecast-map"),
                    expandable = false)
            })
    }
    val anchor = when (id) {
        "weathermap" -> hasTestTag("visual-forecast-map")
        "weatherlive" -> hasTestTag("weather_live")
        else -> hasText("Yolculuk havası ve kalkış")
    }
    if (id != "weather") compose.onNodeWithTag("weather_planner").performScrollToNode(anchor)
    capture(id, anchor, "WeatherPlannerContent", "Synthetic forecast, no provider calls", native = id == "weathermap")
}

private fun PhoneSceneHost.groupScene() {
    val backend = object : GroupBackend {
        override val configured = true
        override val hasIdentity = true
        override suspend fun command(action: String, body: JSONObject): JSONObject {
            check(action == "status" && !body.optBoolean("keepalive"))
            return JSONObject("""
                {"user_id":"visual-member","group":{"id":"visual-group","host_id":"visual-host",
                "expires_at":${System.currentTimeMillis() + 3_600_000},"self_status":"approved",
                "consent":false,"inbox":"visual-inbox","stops":[],
                "members":[{"user_id":"visual-member","name":"Sentetik üye","status":"approved","consent":false}]}}
            """.trimIndent())
        }
        override suspend fun token(): String = unexpectedVisualAction()
        override fun openSocket(listener: WebSocketListener): WebSocket = unexpectedVisualAction()
    }
    val navigation = object : GroupNavigation {
        override val state = MutableStateFlow(NavigationState())
        override suspend fun setSharingLocation(enabled: Boolean, expectedSessionId: String?) { unexpectedVisualAction() }
        override suspend fun currentLocation(): NavigationFix = unexpectedVisualAction()
    }
    val group = GroupCoordinator(backend, navigation)
    try {
        show { GroupScreen(group, onClose = {}, onOpenRoute = { _, _ -> unexpectedVisualAction() }) }
        compose.waitUntil(5_000) { group.state.value.selfStatus == "approved" && !group.state.value.sharing }
        capture("groupactive", hasText("Grup yolculuğu"), "GroupScreen", "Approved synthetic group, sharing off")
    } finally {
        compose.runOnIdle { group.dispose() }
    }
}
