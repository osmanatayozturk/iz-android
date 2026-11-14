package org.iz.navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.integration.MapPlaceCandidate
import org.iz.navigation.integration.MapOutlineReadResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Plain panel behavior; actual native map and entry coverage live in the dedicated native tests. */
@RunWith(AndroidJUnit4::class)
class MapSelectionPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun informationBackKeepsScrollSelectionAndExposedHostInteractiveWithoutQuery() {
        lateinit var owner: PlaceInformationDiscoveryOwner
        var reads = 0
        var hostTouches = 0
        val places = (1..40).map { i -> MapPlaceCandidate(SelectedOsmPlace("Synthetic $i", 35.0 + i * .000001, 25.0, OsmRef(OsmType.NODE, i.toLong())), "OSM yeri") }
        compose.setContent { IzTheme {
            var details by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            owner = remember { PlaceInformationDiscoveryOwner(scope, { _, _ -> reads++; places }, { details = true }, { "panel-fixture" }) }
            Box(Modifier.fillMaxSize()) {
                TextButton({ hostTouches++ }, Modifier.padding(top = 40.dp).testTag("exposed-map-host")) { Text("Synthetic exposed map") }
                PlaceInformationDiscoveryContent(owner)
            }
            if (details) PlaceInformationMapDialog({ details = false }) { Text("Synthetic detail", Modifier.testTag("synthetic-detail")) }
        } }
        compose.runOnIdle { owner.tap(35.0, 25.0, 18.0) }
        compose.onNodeWithTag("place-info-open-information").assertIsNotEnabled()
        compose.onNodeWithTag("place-info-discovery-choice-36").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("place-info-open-information").performClick()
        compose.onNodeWithTag("synthetic-detail").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("place-info-discovery-choice-36").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("place-info-discovery-choice-0").assertDoesNotExist()
        compose.onNodeWithTag("exposed-map-host").performClick()
        compose.runOnIdle { assertEquals(1, hostTouches); assertEquals(1, reads) }
        compose.onNodeWithTag("place-info-discovery-collapse").performClick()
        compose.onNodeWithTag("place-info-open-information").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("place-info-discovery-collapse").performClick()
        compose.onNodeWithTag("place-info-discovery-choice-36").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("place-info-discovery-close").performClick()
        compose.runOnIdle { assertTrue(owner.state.candidates.isEmpty()); assertEquals(1, reads) }
    }

    @Test fun collisionRequiresExplicitChoiceAndOutlineFailureRetainsInformationAndManualRetry() {
        lateinit var owner: PlaceInformationDiscoveryOwner
        val pending = CompletableDeferred<MapOutlineReadResult>()
        var outlines = 0
        var opened = 0
        val node = MapPlaceCandidate(SelectedOsmPlace("Synthetic point", 35.0, 25.0, OsmRef(OsmType.NODE, 1)), "OSM yeri")
        val way = MapPlaceCandidate(SelectedOsmPlace("Synthetic building", 35.0, 25.0, OsmRef(OsmType.WAY, 2)), "Bina")
        compose.setContent { IzTheme {
            val scope = rememberCoroutineScope()
            owner = remember { PlaceInformationDiscoveryOwner(scope, { _, _ -> listOf(node, way) }, { opened++ }, { "collision-fixture" },
                outline = { outlines++; if (outlines == 1) pending.await() else MapOutlineReadResult(null, "Synthetic unavailable ring") }) }
            PlaceInformationDiscoveryContent(owner)
        } }
        compose.runOnIdle { owner.tap(35.0, 25.0, 18.0) }
        compose.runOnIdle { owner.selectRendered(listOf(node.place, way.place)); assertNull(owner.state.selectedRef) }
        compose.onNodeWithTag("place-info-open-information").assertIsNotEnabled()
        compose.onNodeWithTag("place-info-discovery-choice-1").performClick().assertIsSelected()
        compose.onNodeWithTag("place-info-outline-loading").assertExists()
        compose.onNodeWithTag("place-info-open-information").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opened); pending.complete(MapOutlineReadResult(null, "Synthetic failed ring")) }
        compose.onNodeWithTag("place-info-outline-retry").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, outlines); assertEquals(2, owner.state.candidates.size) }
        compose.onNodeWithTag("place-info-open-information").assertIsEnabled()
        compose.onNodeWithTag("place-info-outline-retry").performClick()
        compose.runOnIdle { assertEquals(2, outlines) }
        compose.onNodeWithTag("place-info-discovery-choice-0").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(owner.state.outlineBusy); assertFalse(owner.state.outlineRetryable); assertEquals(2, outlines) }
    }
}
