package org.iz.navigation.ui

import android.content.res.Configuration
import android.os.SystemClock
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.iz.navigation.data.Place
import org.iz.navigation.data.Transport
import org.iz.navigation.lean.LiveLeanReading
import org.iz.navigation.navigation.NavigationFix
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.QuickNavigationOrigin
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.RouteVertex
import org.iz.navigation.weather.WeatherCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RedesignFirstSliceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun leanDialStaysCompactAndDropsThePreviousSessionsReading() {
        val reading = mutableStateOf<LiveLeanReading?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                IzTheme { LiveLeanDial("synthetic-drive", reading.value) }
            }
        }

        compose.runOnIdle {
            reading.value = LiveLeanReading("synthetic-drive", -26.0, SystemClock.elapsedRealtime())
        }
        val dial = compose.onNodeWithTag("live-lean-dial", useUnmergedTree = true)
        dial.assertIsDisplayed().assertContentDescriptionEquals("Tahmini yatış, Sol 26 derece")
        val bounds = dial.getUnclippedBoundsInRoot()
        val width: Dp = bounds.right - bounds.left
        val height: Dp = bounds.bottom - bounds.top
        assertTrue("Dial width was $width", width.value in 113f..128f)
        assertTrue("Dial height was $height", height.value in 50f..60f)

        compose.runOnIdle {
            reading.value = LiveLeanReading("previous-synthetic-drive", 19.0, SystemClock.elapsedRealtime())
        }
        dial.assertIsDisplayed().assertContentDescriptionEquals("Tahmini yatış, ölçüm yok")
    }

    @Test fun liveLeanDialExpiresWithoutAnotherReading() {
        val reading = mutableStateOf<LiveLeanReading?>(null)
        compose.setContent { IzTheme { LiveLeanDial("continuous-ride", reading.value) } }
        compose.runOnIdle {
            reading.value = LiveLeanReading("continuous-ride", 19.0, SystemClock.elapsedRealtime())
        }
        val dial = compose.onNodeWithTag("live-lean-dial", useUnmergedTree = true)
        dial.assertContentDescriptionEquals("Tahmini yatış, Sağ 19 derece")
        compose.mainClock.advanceTimeBy(LiveLeanLifetimeMillis + 50)
        dial.assertContentDescriptionEquals("Tahmini yatış, ölçüm yok")
        compose.onNodeWithTag("live-lean-value", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun largeTextRouteFailureCanRetryWithTheSameDestinationAndRecordingChoice() {
        val start = WeatherCoordinate(1.0, 1.0)
        val finish = WeatherCoordinate(1.01, 1.01)
        val destinationName = "Sentetik hedef"
        val origin = QuickNavigationOrigin(
            NavigationFix(start, 100L, 200f), precise = false,
            routeRevision = 0L, sessionId = null, requiresFinePermission = false,
        )
        val probes = mutableListOf<Pair<RouteStop, Transport>>()
        val activations = mutableListOf<Triple<PlannedRoute, QuickNavigationOrigin, Boolean>>()
        val retryReady = CompletableDeferred<Unit>()
        var completions = 0
        val navigation = MutableStateFlow(NavigationState())

        compose.setContent {
            // The real Dialog owns a separate Android Compose view. Its context must also have font scale 2.
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                val largeTextContext = ContextThemeWrapper(context, 0).apply {
                    applyOverrideConfiguration(Configuration().apply { fontScale = 2f })
                }
                ComposeView(largeTextContext).apply {
                    setContent {
                        IzTheme {
                            QuickDirectionsDialog(
                                places = listOf(Place(name = destinationName, latitude = finish.latitude, longitude = finish.longitude)),
                                navigationState = navigation,
                                locate = { origin },
                                validateOrigin = { candidate ->
                                    check(candidate === origin) { "Unexpected origin in the isolated route fixture" }
                                },
                                plan = { coordinate, destination, mode, _ ->
                                    probes += destination to mode
                                    if (probes.size == 1) throw IOException("Synthetic route failure")
                                    retryReady.await()
                                    PlannedRoute(
                                        "redesign-retry", listOf(RouteStop("Başlangıç", coordinate), destination),
                                        listOf(RouteVertex(coordinate, 0.0), RouteVertex(destination.coordinate, 60.0)),
                                        1_200.0, 60.0, 100L, transport = mode,
                                    )
                                },
                                activate = { route, fix, record ->
                                    activations += Triple(route, fix, record)
                                    route.id
                                },
                                onDismiss = {},
                                onStarted = { completions++ },
                                hasLocationPermission = { true },
                            )
                        }
                    }
                }
            })
        }

        compose.onNodeWithTag("quick-destinations").performScrollToNode(hasText(destinationName))
        compose.onNodeWithText(destinationName).assertIsDisplayed().performClick()
        val options = compose.onNodeWithTag("quick-route-options")
        options.performScrollToNode(hasTestTag("quick-record"))
        compose.onNodeWithTag("quick-record").assertIsDisplayed().assertIsOn()
        options.performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").assertIsDisplayed().performClick()

        options.performScrollToNode(hasTestTag("quick-route-error"))
        assertLargeText(compose.onNodeWithTag("quick-route-error"))
        compose.runOnIdle {
            val (destination, mode) = probes.single()
            assertEquals(destinationName, destination.label)
            assertEquals(finish, destination.coordinate)
            assertEquals(Transport.CAR, mode)
            assertEquals(0, activations.size)
            assertEquals(0, completions)
        }

        options.performScrollToNode(hasTestTag("quick-retry"))
        compose.onNodeWithTag("quick-retry").assertIsDisplayed().assertIsEnabled().performClick()
        options.performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(2, probes.size)
            assertEquals(probes.first(), probes.last())
            assertEquals(0, activations.size)
            retryReady.complete(Unit)
        }
        compose.runOnIdle {
            val (route, fix, record) = activations.single()
            assertEquals(probes.last().first, route.stops.last())
            assertEquals(Transport.CAR, route.transport)
            assertSame(origin, fix)
            assertEquals(true, record)
            assertEquals(1, completions)
        }
    }

    @Test fun largeTextOsmHistoryFailureCanRefreshAndBackWithoutInvokingContributionActions() {
        val history = mutableStateOf<OsmContributionHubHistoryResult?>(OsmContributionHubHistoryResult.Failed)
        var refreshes = 0
        var closes = 0
        val contributionActions = mutableListOf<String>()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                IzTheme {
                    OsmContributionHubContent(
                        rows = emptyList(), history = history.value, canRefresh = true,
                        onClose = { closes++ },
                        onNewNote = { contributionActions += "note" },
                        onNewPlace = { contributionActions += "place" },
                        onPickExistingPlace = { contributionActions += "correction" },
                        onGps = { contributionActions += "gps" },
                        onGpsHistory = { contributionActions += "uploads" },
                        onRefresh = { refreshes++; history.value = null },
                        onOpen = { contributionActions += "row" },
                        onSurvey = { contributionActions += "survey" },
                    )
                }
            }
        }

        val list = compose.onNodeWithTag("osm-contribution-list")
        list.performScrollToNode(hasTestTag("osm-contribution-activities"))
        compose.onNodeWithTag("osm-contribution-activities").assertIsDisplayed().performClick()
        list.performScrollToNode(hasTestTag("osm-contribution-history-error"))
        assertLargeText(compose.onNodeWithTag("osm-contribution-history-error"))
        list.performScrollToNode(hasTestTag("osm-contribution-refresh"))
        compose.onNodeWithTag("osm-contribution-refresh").assertIsDisplayed().performClick().assertIsNotEnabled()
        list.performScrollToNode(hasTestTag("osm-contribution-history-loading"))
        compose.onNodeWithTag("osm-contribution-history-loading").assertIsDisplayed()

        compose.runOnIdle { history.value = OsmContributionHubHistoryResult.Ready(emptyList()) }
        list.performScrollToNode(hasText("Henüz katkı kaydı yok."))
        compose.onNodeWithText("Henüz katkı kaydı yok.").assertIsDisplayed()
        compose.onNodeWithTag("osm-contribution-back").assertIsDisplayed().performClick()
        list.performScrollToNode(hasTestTag("osm-contribution-new-note"))
        compose.onNodeWithTag("osm-contribution-new-note").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithTag("osm-contribution-back").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(1, closes)
            assertEquals(emptyList<String>(), contributionActions)
        }
    }

    private fun assertLargeText(node: SemanticsNodeInteraction) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(2f, layout.layoutInput.density.fontScale, .01f)
        assertTrue("Error text overflows its layout", !layout.hasVisualOverflow)
    }
}
