package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.integration.osm.OsmDuplicateCandidate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapEditEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun draft() = MapEditDraft(latitude = 39.9, longitude = 32.8, preset = MapPlacePreset.DRINKING_WATER)

    @Test fun publicationRequiresSurveyPreviewAndDuplicateReview() {
        var published: MapEditDraft? = null
        compose.setContent { MaterialTheme {
            MapEditEditor(draft(), false, true, false, null, {}, {}, { emptyList() }, {}, { value, _ -> published = value })
        } }
        compose.onNodeWithText("Mevcut yerleri kontrol et ve önizle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick()
        compose.onNodeWithText("Mevcut yerleri kontrol et ve önizle").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-duplicates-reviewed").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().performClick()
        compose.runOnIdle { assertNotNull(published); assertEquals(mapOf("amenity" to "drinking_water"), published!!.publicTags()) }
    }

    @Test fun loginReceivesSavedFieldsAndDoesNotPublish() {
        var loginDraft: MapEditDraft? = null
        compose.setContent { MaterialTheme {
            MapEditEditor(draft(), false, false, false, null, {}, {}, { emptyList() }, { loginDraft = it }, { _, _ -> error("Must not publish") })
        } }
        compose.onNodeWithTag("osm-map-name").performScrollTo().performTextReplacement("Yeni çeşme")
        compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick()
        compose.onNodeWithText("Mevcut yerleri kontrol et ve önizle").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-duplicates-reviewed").performScrollTo().performClick()
        compose.onNodeWithText("OSM düzenleme iznini etkinleştir").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Yeni çeşme", loginDraft?.name) }
    }

    @Test fun existingAreaIsDisplayedAndMustBeReviewed() {
        val initial = draft().copy(surveyConfirmed = true)
        val ref = OsmRef(OsmType.WAY, 77)
        var reviewed: Set<OsmRef>? = null
        compose.setContent { MaterialTheme {
            MapEditEditor(initial, false, true, false, null, {}, {},
                { listOf(OsmDuplicateCandidate(ref, "Mevcut alan", null, null)) }, {}, { _, refs -> reviewed = refs })
        } }
        compose.onNodeWithText("Mevcut yerleri kontrol et ve önizle").performScrollTo().performClick()
        compose.onNodeWithText("Mevcut alan · OSM'de incele").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-duplicates-reviewed").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(setOf(ref), reviewed) }
    }
}
