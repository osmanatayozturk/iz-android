package org.iz.navigation.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.Transport
import org.iz.navigation.weather.defaultWeatherSettings
import org.iz.navigation.weather.RideWeatherSettings
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RideWeatherLiveState
import org.iz.navigation.weather.RideWeatherStatus
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherPlannerUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun directionsCanOpenWithStopsBeforeWeatherHasBeenCalculated() {
        var opened = 0
        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = WeatherPlannerState(stops = stops()),
                    live = RideWeatherLiveState(),
                    places = emptyList(),
                    actions = actions(openDirections = { opened++ }),
                    routeMap = {},
                )
            }
        }
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_directions"))
        compose.onNodeWithTag("weather_directions").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun waitingForGpsShowsFeedbackAndDisablesRepeatedStartAndEdits() {
        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = WeatherPlannerState(stops = stops(), route = route()),
                    live = RideWeatherLiveState(),
                    places = emptyList(),
                    actions = actions(),
                    locationBusy = true,
                    routeMap = {},
                )
            }
        }
        compose.onNodeWithTag("weather_mode_CAR").assertIsNotEnabled()
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_start"))
        compose.onNodeWithTag("weather_start").assertIsNotEnabled()
        compose.onNodeWithText("Güncel konum alınıyor…").assertIsDisplayed()
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_location_progress"))
        compose.onNodeWithTag("weather_location_progress").assertIsDisplayed()
    }

    @Test
    fun incompleteForecastIsVisibleAndValidRouteCanStart() {
        var started = false
        val comparisons = List(7) { index ->
            assessment(10_000L + index * 1_800_000L, complete = false)
        }
        val state = WeatherPlannerState(
            stops = stops(),
            departureAt = 10_000L,
            route = route(),
            comparisons = comparisons,
            selectedDepartureAt = comparisons.first().departureAt,
        )

        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = state,
                    live = RideWeatherLiveState(),
                    places = emptyList(),
                    actions = actions(start = { started = true }),
                    routeMap = { Text("Rota haritası") },
                )
            }
        }

        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_candidate_0"))
        compose.onAllNodesWithText("Eksik veri", substring = true).onFirst().assertIsDisplayed()
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_summary"))
        compose.onNodeWithText("Eksik değerler güvenli kabul edilmez", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_start"))
        compose.onNodeWithTag("weather_start").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(started) }
    }

    @Test
    fun completeForecastEnablesStartAndViaCanBeRemoved() {
        var started = false
        var removed = -1
        val comparisons = List(7) { index ->
            assessment(10_000L + index * 1_800_000L, complete = true)
        }
        val state = WeatherPlannerState(
            stops = listOf(stops()[0], RouteStop("Mola", WeatherCoordinate(40.5, 29.0)), stops()[1]),
            departureAt = 10_000L,
            route = route(),
            comparisons = comparisons,
            selectedDepartureAt = comparisons.first().departureAt,
        )

        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = state,
                    live = RideWeatherLiveState(),
                    notificationsEnabled = false,
                    places = emptyList(),
                    actions = actions(
                        start = { started = true },
                        removeStop = { removed = it },
                    ),
                    routeMap = {},
                )
            }
        }

        compose.onNodeWithTag("weather_planner").performScrollToNode(androidx.compose.ui.test.hasText("Bildirimler kapalı", substring = true))
        compose.onNodeWithText("Bildirimler kapalı", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_start"))
        compose.onNodeWithTag("weather_start").assertIsEnabled().performClick()
        compose.onNodeWithTag("weather_planner").performScrollToNode(hasContentDescription("Ara durağı kaldır"))
        compose.onNodeWithContentDescription("Ara durağı kaldır").performClick()
        compose.runOnIdle {
            assertTrue(started)
            assertEquals(1, removed)
        }
    }

    @Test
    fun staleLiveCardExplainsSuspendedAlertsAndKeepsWeatherOnlyStop() {
        var stopped = false
        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = WeatherPlannerState(stops = stops(), departureAt = 10_000L),
                    live = RideWeatherLiveState(
                        status = RideWeatherStatus.ERROR,
                        journeyId = "moto",
                        gpsStale = true,
                        message = "Çldı",
                    ),
                    places = emptyList(),
                    actions = actions(stopLive = { stopped = true }),
                    routeMap = {},
                )
            }
        }

        compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_live"))
        compose.onNodeWithTag("weather_live").assertIsDisplayed()
        compose.onNodeWithText("Konum güncel değil", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("weather_planner").performScrollToNode(androidx.compose.ui.test.hasText("Havayı durdur"))
        compose.onNodeWithText("Havayı durdur").performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Test
    fun allSixModesCanBeSelectedAndShowTheirOwnSettingsLabel() {
        compose.setContent {
            var state by remember { mutableStateOf(WeatherPlannerState()) }
            IzTheme {
                WeatherPlannerContent(
                    state = state,
                    live = RideWeatherLiveState(),
                    places = emptyList(),
                    actions = actions(selectTransport = { state = state.copy(transport = it, settings = defaultWeatherSettings(it)) }),
                    routeMap = {},
                )
            }
        }
        for (mode in transportDisplayOrder) {
            compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_mode_${mode.name}"))
            compose.onNodeWithTag("weather_mode_${mode.name}").performClick().assertIsSelected()
            compose.onNodeWithTag("weather_planner").performScrollToNode(hasTestTag("weather_settings"))
            compose.onNodeWithText("${mode.label()} · Hava ayarları").assertIsDisplayed()
        }
    }

    @Test
    fun walkingSettingsSavePaceAndAlertPreference() {
        var saved: RideWeatherSettings? = null
        compose.setContent {
            IzTheme {
                WeatherSettingsDialog(
                    initial = defaultWeatherSettings(Transport.WALK),
                    transport = Transport.WALK,
                    onDismiss = {},
                    onSave = { saved = it; true },
                    onTestVoice = {},
                )
            }
        }
        compose.onNodeWithText("Yürüyüş · Hava ayarları").assertIsDisplayed()
        compose.onNodeWithTag("weather_alerts_switch").performScrollTo().performClick()
        compose.onNodeWithText("Planlama hızı (km/sa)").performScrollTo().performTextReplacement("6,5")
        compose.onNodeWithText("Kaydet").performClick()
        compose.runOnIdle {
            assertEquals(6.5, saved?.travelSpeedKmh)
            assertEquals(false, saved?.alertsEnabled)
            assertEquals(defaultWeatherSettings(Transport.WALK).thresholds, saved?.thresholds)
        }
    }

    @Test
    fun nullSpeedProfilesUseModeDefaultWhenOnlyAlertsChange() {
        var transport by mutableStateOf(Transport.WALK)
        val saved = mutableMapOf<Transport, RideWeatherSettings>()
        val expectedSpeeds = mapOf(Transport.WALK to 5.1, Transport.RUN to 10.0, Transport.BICYCLE to 18.0)
        compose.setContent {
            IzTheme {
                key(transport) {
                    val mode = transport
                    WeatherSettingsDialog(
                        initial = defaultWeatherSettings(mode).copy(travelSpeedKmh = null),
                        transport = mode,
                        onDismiss = {},
                        onSave = { saved[mode] = it; true },
                        onTestVoice = {},
                    )
                }
            }
        }
        for ((mode, expectedSpeed) in expectedSpeeds) {
            compose.runOnIdle { transport = mode }
            compose.onNodeWithText("${mode.label()} · Hava ayarları").assertIsDisplayed()
            compose.onNodeWithText("Planlama hızı (km/sa)").performScrollTo()
            compose.onNodeWithText(expectedSpeed.toString()).assertIsDisplayed()
            compose.onNodeWithTag("weather_alerts_switch").performScrollTo().performClick()
            compose.onNodeWithText("Kaydet").performClick()
            compose.runOnIdle {
                assertEquals(expectedSpeed, saved[mode]?.travelSpeedKmh)
                assertEquals(false, saved[mode]?.alertsEnabled)
                assertEquals(defaultWeatherSettings(mode).thresholds, saved[mode]?.thresholds)
            }
        }
    }

    @Test
    fun sameModesActiveRouteDoesNotHideNewPreview() {
        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = WeatherPlannerState(stops = stops(), route = route().copy(id = "preview", distanceMeters = 2_000.0)),
                    live = RideWeatherLiveState(status = RideWeatherStatus.READY, journeyId = "existing", route = route()),
                    places = emptyList(),
                    actions = actions(),
                    routeMap = { Text("Yeni rota önizlemesi") },
                )
            }
        }
        compose.onNodeWithTag("weather_planner").performScrollToNode(androidx.compose.ui.test.hasText("Planlanan rota"))
        compose.onNodeWithText("Planlanan rota").assertIsDisplayed()
        compose.onNodeWithText("2,0 km", substring = true).assertIsDisplayed()
    }

    @Test
    fun anotherModesLiveRouteDoesNotReplaceSelectedModesPreview() {
        compose.setContent {
            IzTheme {
                WeatherPlannerContent(
                    state = WeatherPlannerState(transport = Transport.WALK, route = route().copy(transport = Transport.WALK, distanceMeters = 2_000.0)),
                    live = RideWeatherLiveState(status = RideWeatherStatus.READY, journeyId = "motorcycle", route = route()),
                    places = emptyList(),
                    actions = actions(),
                    routeMap = { Text("Yürüyüş rotası") },
                )
            }
        }
        compose.onNodeWithTag("weather_planner").performScrollToNode(androidx.compose.ui.test.hasText("Planlanan rota"))
        compose.onNodeWithText("Planlanan rota").assertIsDisplayed()
        compose.onNodeWithText("2,0 km", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Yürüyüş rotası").assertIsDisplayed()
    }

    private fun actions(
        start: () -> Unit = {},
        removeStop: (Int) -> Unit = {},
        stopLive: () -> Unit = {},
        selectTransport: (Transport) -> Unit = {},
        openDirections: () -> Unit = {},
    ) = WeatherPlannerActions(
        chooseStop = {},
        useCurrentOrigin = {},
        removeStop = removeStop,
        addVia = {},
        pickDate = {},
        pickTime = {},
        departNow = {},
        calculate = {},
        selectDeparture = {},
        start = start,
        openSettings = {},
        refreshLive = {},
        stopLive = stopLive,
        selectTransport = selectTransport,
        openDirections = openDirections,
    )

    private fun stops() = listOf(
        RouteStop("Başlangıç", WeatherCoordinate(41.0, 29.0)),
        RouteStop("Varış", WeatherCoordinate(40.0, 29.0)),
    )

    private fun route() = PlannedRoute(
        id = "route",
        stops = stops(),
        vertices = listOf(
            RouteVertex(stops()[0].coordinate, 0.0),
            RouteVertex(stops()[1].coordinate, 3_600.0),
        ),
        distanceMeters = 100_000.0,
        durationSeconds = 3_600.0,
        createdAt = 1L,
    )

    private fun assessment(departure: Long, complete: Boolean) = WeatherAssessment(
        departureAt = departure,
        samples = emptyList(),
        exceededSeconds = 0.0,
        complete = complete,
        minTemperatureC = if (complete) 10.0 else null,
        maxTemperatureC = if (complete) 18.0 else null,
        maxPrecipitationProbabilityPercent = if (complete) 20.0 else null,
        maxWindKmh = if (complete) 15.0 else null,
        maxGustKmh = if (complete) 22.0 else null,
        fetchedAt = if (complete) System.currentTimeMillis() else null,
    )
}
