package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.data.SelectedOsmPlace
import org.iz.navigation.data.GeoCoordinate

@RunWith(AndroidJUnit4::class)
class OsmSurveyEntryUiTest {
    @get:Rule val compose = createComposeRule()

    // Runnable against the base UI: fails because the approved survey entry is absent.
    @Test fun contributionHubOffersAnAccessibleOnSiteEntry() {
        var opened = 0
        compose.setContent {
            MaterialTheme {
                OsmContributionHubContent(rows = emptyList(), history = null, canRefresh = false,
                    onClose = {}, onNewNote = {}, onNewPlace = {}, onPickExistingPlace = {},
                    onGps = {}, onGpsHistory = {}, onRefresh = {}, onOpen = {}, onSurvey = { opened++ })
            }
        }
        compose.onNodeWithTag("osm-contribution-list").performScrollToNode(hasText("Yerinde tamamla"))
        compose.onNodeWithText("Yerinde tamamla").assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Katkı Merkezi").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun selectedOsmPlaceOffersSurveyAlongsideItsExistingActions() {
        var opened = 0
        compose.setContent { MaterialTheme {
            PlaceEditor(pin = GeoCoordinate(41.0, 29.0),
                osm = SelectedOsmPlace("Sentetik kafe", 41.0, 29.0, OsmRef(OsmType.NODE, 700)),
                places = emptyList(), onDismiss = {}, onSearch = {}, onLocate = {},
                onOsmCorrection = {}, onMapNote = {}, onOsmSurvey = { opened++ }, onSave = { _, _, _ -> })
        } }
        compose.onNodeWithTag("osm-place-survey").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("OSM mekânını düzelt").assertExists()
        compose.onNodeWithText("Ziyareti kaydet").assertExists()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
