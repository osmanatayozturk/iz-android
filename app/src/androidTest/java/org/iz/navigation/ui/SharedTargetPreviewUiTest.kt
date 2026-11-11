package org.iz.navigation.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.*
import org.iz.navigation.weather.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedTargetPreviewUiTest {
    @get:Rule val compose = createComposeRule()
    private val origin = QuickNavigationOrigin(NavigationFix(WeatherCoordinate(41.0, 29.0), 100, 200f),
        precise = false, routeRevision = 0, sessionId = null, requiresFinePermission = false)
    private val target = RouteStop("Sentetik hedef", WeatherCoordinate(41.01, 29.01))
    private var plans = 0
    private var locations = 0
    private val navigation = MutableStateFlow(NavigationState())
    private val starts = mutableListOf<Boolean>()
    private val startedTargets = mutableListOf<String>()
    private val startedModes = mutableListOf<Transport>()
    private val pending = mutableListOf<CompletableDeferred<Unit>>()
    @org.junit.After fun releasePendingPlans() { pending.forEach { it.complete(Unit) } }
    private fun show(failFirst: Boolean = false, selectedTarget: State<RouteStop>? = null,
        delayedFirst: CompletableDeferred<Unit>? = null) {
        delayedFirst?.let { pending += it }
        compose.setContent { IzTheme {
            QuickDirectionsDialog(emptyList(), navigation, locate = { locations++; origin },
                validateOrigin = {}, plan = { coordinate, destination, mode, _ ->
                    plans++
                    if (failFirst && plans == 1) error("Synthetic provider failure")
                    if (delayedFirst != null && plans == 1) withContext(NonCancellable) { delayedFirst.await() }
                    PlannedRoute("shared-test-$plans", listOf(RouteStop("A", coordinate), destination),
                        listOf(RouteVertex(coordinate, 0.0), RouteVertex(destination.coordinate, 600.0)),
                        1500.0, 600.0, 1, transport = mode)
                }, activate = { route, _, record -> starts += record; startedTargets += route.stops.last().label; startedModes += route.transport; "synthetic-session" },
                onDismiss = {}, onStarted = {}, hasLocationPermission = { true },
                initialDestination = selectedTarget?.value ?: target, sharedPreview = true, previewMap = { Text("Sentetik rota önizlemesi") })
        } }
    }
    private fun options(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    @Test fun receiveAndModeSelectionAndPreviewNeverStartUntilExplicitStart() {
        show()
        options("quick-record").assertIsOn()
        options("quick-mode-CAR").performClick()
        compose.runOnIdle { assertEquals(0, plans); assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithText("Sentetik rota önizlemesi").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, plans); assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("shared-start").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf(true), starts) }
    }
    @Test fun activeMotorcycleRecordingRequiresMatchingVehicleBeforePreviewWithoutAutoStarting() {
        val active = NavigationState(recording = true, sessionTransport = Transport.MOTORCYCLE,
            sessionId = "synthetic-active-motorcycle")
        navigation.value = active
        show()
        compose.onNodeWithTag("shared-preview").assertIsNotEnabled()
        options("quick-mode-CAR").assertIsSelected().assertIsNotEnabled()
        options("quick-record").assertIsOn().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, locations); assertEquals(0, plans); assertTrue(starts.isEmpty())
            assertEquals(active, navigation.value)
        }
        options("quick-mode-MOTORCYCLE").assertIsEnabled().performClick().assertIsSelected()
        compose.onNodeWithTag("shared-preview").assertIsEnabled()
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, locations); assertEquals(0, plans); assertTrue(starts.isEmpty())
            assertEquals(active, navigation.value)
        }
    }
    @Test fun backFromPreviewRetainsRecordChoiceAndChangingModeRequiresNewPreview() {
        show()
        options("quick-record").performClick().assertIsOff()
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").assertIsEnabled()
        compose.onNodeWithTag("quick-back").performClick()
        options("quick-record").assertIsOff()
        options("quick-mode-MOTORCYCLE").performClick()
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").performClick()
        compose.runOnIdle { assertEquals(listOf(false), starts); assertEquals(2, plans) }
    }
    @Test fun sharedRetryRecalculatesExactlyOnceWithoutStarting() {
        show(failFirst = true)
        compose.onNodeWithTag("shared-preview").performClick()
        options("quick-retry").assertIsEnabled().performClick()
        compose.onNodeWithTag("shared-start").assertIsEnabled()
        compose.runOnIdle { assertEquals(2, plans); assertTrue(starts.isEmpty()) }
    }
    @Test fun targetReplacementInvalidatesPreparedPreviewAndPreviouslyCapturedStart() {
        val selected = mutableStateOf(target)
        show(selectedTarget = selected)
        compose.onNodeWithTag("shared-preview").performClick()
        val oldStart = requireNotNull(compose.onNodeWithTag("shared-start").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.runOnIdle { selected.value = RouteStop("Yeni hedef", WeatherCoordinate(41.02, 29.02)) }
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
        compose.runOnIdle { oldStart(); assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").performClick()
        compose.runOnIdle { assertEquals(listOf("Yeni hedef"), startedTargets); assertEquals(2, plans) }
    }
    @Test fun retainedOldStartCannotActivateOrClearNewVehiclePreview() {
        show()
        compose.onNodeWithTag("shared-preview").performClick()
        val oldStart = requireNotNull(compose.onNodeWithTag("shared-start").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action)
        compose.onNodeWithTag("quick-back").performClick()
        options("quick-mode-MOTORCYCLE").performClick()
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").assertIsEnabled()
        compose.runOnIdle { oldStart(); assertTrue(starts.isEmpty()); assertEquals(2, plans) }
        compose.onNodeWithTag("shared-start").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(Transport.MOTORCYCLE), startedModes); assertEquals(listOf(true), starts) }
    }
    @Test fun ignoredCancellationLatePreviewCannotReplaceTheNewTarget() {
        val selected = mutableStateOf(target)
        val late = CompletableDeferred<Unit>()
        show(selectedTarget = selected, delayedFirst = late)
        compose.onNodeWithTag("shared-preview").performClick()
        compose.runOnIdle { assertEquals(1, plans); selected.value = RouteStop("Yeni hedef", WeatherCoordinate(41.02, 29.02)) }
        compose.onNodeWithTag("shared-preview").assertIsEnabled()
        compose.runOnIdle { late.complete(Unit) }
        compose.onNodeWithTag("shared-start").assertDoesNotExist()
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
        compose.onNodeWithTag("shared-preview").performClick()
        compose.onNodeWithTag("shared-start").performClick()
        compose.runOnIdle { assertEquals(listOf("Yeni hedef"), startedTargets); assertEquals(2, plans) }
    }
    @Test fun explicitStartStaysInViewportWithLandscapeFontTwoAndLongSummary() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 2f)) {
                IzTheme {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier
                        .then(androidx.compose.ui.Modifier.requiredSize(640.dp, 320.dp))) {
                        SharedRoutePreviewContent("Uzun sentetik hedef adı ".repeat(10), true,
                            "Uzun hata açıklaması ".repeat(20), true, previewMap = { Text("Sentetik harita") }, onStart = {})
                    }
                }
            }
        }
        val button = compose.onNodeWithTag("shared-start").assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp).assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("shared-preview-content").fetchSemanticsNode().boundsInRoot
        assertTrue(button.top >= viewport.top && button.bottom <= viewport.bottom)
    }
}
