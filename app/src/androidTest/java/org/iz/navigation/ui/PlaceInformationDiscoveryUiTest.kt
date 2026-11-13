package org.iz.navigation.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real discovery owner/choice UI with injected nearby IO; not native-map hit proof. */
@RunWith(AndroidJUnit4::class)
class PlaceInformationDiscoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var owner: PlaceInformationDiscoveryOwner
    private val endpoint = mutableStateOf("synthetic-endpoint-a")
    private val screen = mutableStateOf(1)
    private val selected = mutableStateListOf<SelectedOsmPlace>()
    private val first = SelectedOsmPlace("First synthetic place", 41.01, 29.01, OsmRef(OsmType.NODE, 11))
    private val second = SelectedOsmPlace("Second synthetic place", 41.02, 29.02, OsmRef(OsmType.WAY, 22))
    private val pending = mutableListOf<CompletableDeferred<Unit>>()
    @After fun releaseIgnoredCancellation() { pending.forEach { it.complete(Unit) } }
    private fun deferred() = CompletableDeferred<Unit>().also { pending += it }
    private fun show(nearby: suspend (Double, Double) -> List<SelectedOsmPlace>) {
        compose.setContent { IzTheme {
            val scope = rememberCoroutineScope()
            owner = remember { PlaceInformationDiscoveryOwner(scope, nearby, { selected += it },
                identity = { screen.value to endpoint.value }) }
            Text("Synthetic discovery host")
            PlaceInformationDiscoveryContent(owner)
        } }
    }
    @Test fun explicitRasterTapDiscoversRealChoicesAndOpensOnlyTheChosenObjectOnce() {
        var reads = 0
        show { lat, lon ->
            assertEquals(41.0, lat, 0.0); assertEquals(29.0, lon, 0.0); reads++
            listOf(first, second, first.copy(osmRef = null), second.copy(latitude = Double.NaN))
        }
        compose.runOnIdle { assertEquals(0, reads); assertTrue(selected.isEmpty()); owner.tap(41.0, 29.0, 17.0) }
        compose.onNodeWithTag("place-info-discovery-choice-1").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.runOnIdle { assertEquals(1, reads); assertTrue(selected.isEmpty()); assertEquals(listOf(first, second), owner.state.overlay) }
        val oldClick = requireNotNull(compose.onNodeWithTag("place-info-discovery-choice-1").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithTag("place-info-discovery-choice-1").performClick()
        compose.runOnIdle { oldClick(); assertEquals(1, selected.size); assertSame(second, selected.single()); assertEquals(1, reads) }
    }
    @Test fun replacementAndCloseRejectIgnoredCancellationLateDiscovery() {
        val lateFirst = deferred()
        val lateClosed = deferred()
        var reads = 0
        show { _, _ ->
            reads++
            when (reads) {
                1 -> { withContext(NonCancellable) { lateFirst.await() }; listOf(first) }
                2 -> listOf(second)
                else -> { withContext(NonCancellable) { lateClosed.await() }; listOf(first) }
            }
        }
        compose.runOnIdle { owner.tap(41.0, 29.0, 17.0) }
        compose.runOnIdle { assertEquals(1, reads); owner.tap(41.1, 29.1, 17.0) }
        compose.runOnIdle { assertEquals(listOf(second), selected.toList()); lateFirst.complete(Unit) }
        compose.runOnIdle { assertEquals(listOf(second), selected.toList()); owner.tap(41.2, 29.2, 17.0) }
        compose.runOnIdle { assertEquals(3, reads); owner.close(); lateClosed.complete(Unit) }
        compose.runOnIdle { assertEquals(listOf(second), selected.toList()); assertFalse(owner.state.busy); assertTrue(owner.state.choices.isEmpty()) }
    }

    @Test fun unnamedBuildingKeepsItsRealIdentityAndRequiresChoiceAmongMultipleCandidates() {
        val building = SelectedOsmPlace("", 41.01, 29.01, OsmRef(OsmType.WAY, 804))
        show { _, _ -> listOf(building, first) }
        compose.runOnIdle { owner.tap(41.0, 29.0, 17.0) }
        compose.onNodeWithTag("place-info-discovery-choice-0").assertIsDisplayed()
            .assertTextContains("way/804", substring = true)
        compose.onNodeWithTag("place-info-discovery-choice-1").assertIsDisplayed()
        compose.runOnIdle { assertTrue(selected.isEmpty()); assertEquals(listOf(building, first), owner.state.choices) }
        compose.onNodeWithTag("place-info-discovery-choice-0").performClick()
        compose.runOnIdle { assertEquals(listOf(building), selected.toList()) }
    }
    @Test fun endpointAndScreenChangesRejectLateResultsAndRetainedChoice() {
        val late = deferred()
        var reads = 0
        show { _, _ -> reads++; if (reads == 1) withContext(NonCancellable) { late.await() }; listOf(first, second) }
        compose.runOnIdle { owner.tap(41.0, 29.0, 17.0) }
        compose.runOnIdle { endpoint.value = "synthetic-endpoint-b"; late.complete(Unit) }
        compose.runOnIdle { assertTrue(selected.isEmpty()); assertTrue(owner.state.overlay.isEmpty()); owner.tap(41.0, 29.0, 17.0) }
        val oldClick = requireNotNull(compose.onNodeWithTag("place-info-discovery-choice-0").fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.runOnIdle { screen.value = 2; oldClick(); owner.close(); assertTrue(selected.isEmpty()) }
    }
    @Test fun zoomEmptyAndFailureStatesNeedExplicitTapOrRetry() {
        var reads = 0
        show { _, _ -> reads++; when (reads) { 1 -> emptyList(); 2 -> error("Synthetic offline"); else -> listOf(first) } }
        compose.runOnIdle { owner.tap(41.0, 29.0, 15.0) }
        compose.onNodeWithText("Yakındaki yerleri seçmek için haritayı yakınlaştır.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithTag("place-info-discovery-close").performClick()
        compose.runOnIdle { owner.tap(41.0, 29.0, 17.0) }
        compose.onNodeWithText("Yakında bir OSM yeri veya bina bulunamadı. Yeni OSM mekânı için haritaya uzun basabilirsin.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reads); assertTrue(selected.isEmpty()) }
        compose.onNodeWithTag("place-info-discovery-close").performClick()
        compose.runOnIdle { owner.tap(41.0, 29.0, 17.0) }
        compose.onNodeWithTag("place-info-discovery-retry").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads); assertTrue(selected.isEmpty()) }
        compose.onNodeWithTag("place-info-discovery-retry").performClick()
        compose.runOnIdle { assertEquals(3, reads); assertEquals(listOf(first), selected.toList()) }
    }
}
