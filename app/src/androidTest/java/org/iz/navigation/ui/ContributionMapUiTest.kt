package org.iz.navigation.ui

import android.graphics.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionKind
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView

@RunWith(AndroidJUnit4::class)
class ContributionMapUiTest {
    @get:Rule val compose = createComposeRule()

    private val initial = SelectedOsmPlace("Bisiklet parkı", 39.9, 32.8, OsmRef(OsmType.NODE, 123L))

    @Test fun observationLocationUsesMostOfTheScreenForTheMap() {
        compose.setContent {
            IzTheme { ContributionLocationPicker(initial, null, onDismiss = {}, onChoose = {}) }
        }
        compose.waitForIdle()

        onView(isAssignableFrom(MapView::class.java)).inRoot(isDialog()).check { view, missing ->
            if (missing != null) throw missing
            val visible = Rect()
            assertTrue("The contribution map must be visible", view.getGlobalVisibleRect(visible))
            val display = view.resources.displayMetrics
            assertTrue("Map height ${visible.height()} should use over 60% of screen ${display.heightPixels}",
                visible.height() > display.heightPixels * 0.60f)
            assertTrue("The contribution map should span the available screen width",
                visible.width() > display.widthPixels * 0.90f)
        }
    }

    @Test fun panningDownDoesNotDismissThePickerOrChangeTheChosenPlace() {
        var dismissed = false
        var chosen: ContributionDraft? = null
        compose.setContent {
            IzTheme {
                ContributionLocationPicker(initial, "private-place", onDismiss = { dismissed = true }, onChoose = { chosen = it })
            }
        }
        compose.waitForIdle()

        onView(isAssignableFrom(MapView::class.java)).inRoot(isDialog()).perform(
            GeneralSwipeAction(Swipe.SLOW, GeneralLocation.TOP_CENTER, GeneralLocation.BOTTOM_CENTER, Press.FINGER),
        )
        compose.waitForIdle()
        compose.runOnIdle { assertFalse("Panning the map must not dismiss its container", dismissed) }
        compose.onNodeWithText("Bu konum için gözlem yaz").performClick()
        compose.runOnIdle {
            assertNotNull(chosen)
            assertEquals(39.9, chosen!!.latitude, 0.0)
            assertEquals(32.8, chosen!!.longitude, 0.0)
            assertEquals("private-place", chosen!!.placeId)
            assertEquals(OsmType.NODE, chosen!!.osmType)
            assertEquals(123L, chosen!!.osmId)
            assertEquals(ContributionKind.WRONG_DETAILS, chosen!!.kind)
        }
    }
}
