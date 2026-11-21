package org.iz.navigation.ui

import android.content.Context
import android.graphics.PointF
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.descendants
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.data.DiaryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class HomeMapUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun placeEditorOpensAboveMainMapAndCancelReturnsToMap() {
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNode(hasText("Bir yer kaydet") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Bu yere verdiğin ad").assertIsDisplayed()
        compose.onNodeWithTag("navigation-home-map").assertExists()
        compose.onNodeWithText("Vazgeç").performClick()
        compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
    }

    @Test fun idleMapPointOpensVehicleChoiceAndOsmDraftAtTappedCoordinates() = runBlocking {
        val app = compose.activity.application as IzApplication
        val repository = DiaryRepository(app)
        val beforeDraftIds = repository.mapEdits.first().map { it.id }.toSet()
        val readBudget = app.getSharedPreferences("osm_read_limits", Context.MODE_PRIVATE)
        val beforeBudget = readBudget.all.toMap()
        val mainMap = compose.onNodeWithTag("navigation-home-map").assertIsDisplayed()
        var map: MapLibreMap? = null
        compose.waitUntil(15_000) { compose.runOnIdle {
            if (map == null) (compose.activity.window.decorView as ViewGroup).descendants
                .filterIsInstance<MapView>().firstOrNull { it.isShown }?.getMapAsync { map = it }
            map?.style?.isFullyLoaded == true
        } }
        val native = requireNotNull(map)
        // Zoom 15 is below the existing nearby query threshold. Use an off-centre synthetic point.
        compose.runOnIdle { native.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(1.2345, 2.3456), 15.0)) }
        val size = mainMap.fetchSemanticsNode().size
        val tap = Offset((size.width * .4f).toInt().toFloat(), (size.height * .6f).toInt().toFloat())
        fun tapAndChoose(): LatLng {
            val coordinate = compose.runOnIdle { native.projection.fromScreenLocation(PointF(tap.x, tap.y)) }
            mainMap.performTouchInput { click(tap) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("map-point-add").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("map-point-add").assertTextContains("Yer ekle")
            compose.onNodeWithTag("map-point-directions").assertTextContains("Yol tarifi")
            compose.onNodeWithTag("place-info-discovery").assertDoesNotExist()
            return coordinate
        }
        fun assertCoordinates(node: SemanticsNodeInteraction, expected: LatLng) {
            val text = node.assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single().text
            val (latitude, longitude) = text.split(", ").map(String::toDouble)
            assertEquals(expected.latitude, latitude, .00001)
            assertEquals(expected.longitude, longitude, .00001)
        }

        tapAndChoose()
        Espresso.pressBack()
        compose.onNodeWithTag("map-point-add").assertDoesNotExist()
        compose.onNodeWithTag("home-menu").performClick()
        compose.onNodeWithTag("menu-inspect-map-place").performScrollTo().performClick()
        compose.onNodeWithTag("map-inspection-close").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("map-inspection-close").assertDoesNotExist()

        val routePoint = tapAndChoose()
        compose.onNodeWithTag("map-point-directions").performClick()
        compose.onNodeWithTag("quick-destinations").assertDoesNotExist()
        assertCoordinates(compose.onNode(hasAnyAncestor(hasTestTag("quick-route-options")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)), routePoint)
        compose.onNodeWithTag("quick-route-options").performScrollToNode(hasTestTag("quick-mode-CAR"))
        compose.onNodeWithTag("quick-mode-CAR").assertIsEnabled()
        compose.onNodeWithTag("quick-back").performClick()
        assertEquals(beforeDraftIds, repository.mapEdits.first().map { it.id }.toSet())

        val addPoint = tapAndChoose()
        compose.onNodeWithTag("map-point-add").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("osm-map-draft-coordinate").fetchSemanticsNodes().isNotEmpty() }
        val draft = repository.mapEdits.first().single { it.id !in beforeDraftIds }
        try {
            assertCoordinates(compose.onNodeWithTag("osm-map-draft-coordinate").performScrollTo(), addPoint)
            assertEquals(addPoint.latitude, draft.latitude, .00001)
            assertEquals(addPoint.longitude, draft.longitude, .00001)
            assertEquals(beforeBudget, readBudget.all.toMap())
            compose.onNodeWithTag("osm-map-nearby").performScrollTo().assertIsEnabled()
            Espresso.pressBack()
            Espresso.pressBack()
            mainMap.assertIsDisplayed()
        } finally {
            assertTrue(repository.deleteMapEditIfUnchanged(draft))
        }
        assertEquals(beforeDraftIds, repository.mapEdits.first().map { it.id }.toSet())
    }
}

