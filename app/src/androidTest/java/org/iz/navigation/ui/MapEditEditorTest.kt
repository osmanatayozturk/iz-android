package org.iz.navigation.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.*
import org.iz.navigation.osmcatalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapEditEditorTest {
    @get:Rule val compose = createComposeRule()
    private val catalog by lazy {
        OsmPresetCatalog.load(ApplicationProvider.getApplicationContext<Context>().assets.open(OsmPresetCatalog.BUNDLED_ASSET))
    }
    private fun draft() = newMapEditDraft(39.9, 32.8)
        .selectPreset(catalog.snapshot("amenity/drinking_water", OsmPresetGeometry.POINT))

    @Test fun newTypeIsEmptyAndSurveyAloneDoesNotPermitPreviewButDraftCanBeSaved() {
        var saved: MapEditDraft? = null
        compose.setContent { MaterialTheme {
            MapEditEditor(newMapEditDraft(39.9, 32.8), false, true, false, null, {}, { saved = it },
                { error("Type is required before preview") }, {}, { error("Unexpected publication") }, catalog = catalog)
        } }
        compose.onNodeWithText("Tür seç").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick()
        compose.onNodeWithText("Önizle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Taslağı sakla").performScrollTo().performClick()
        compose.runOnIdle { assertNotNull(saved); assertNull(saved!!.presetSnapshot); assertEquals(2, saved!!.editorVersion) }
    }

    @Test fun publicationRequiresSurveyAndPublicTagReviewButNoNearbyReview() {
        val submissions = mutableListOf<MapEditDraft>()
        var previews = 0
        compose.setContent { MaterialTheme {
            MapEditEditor(draft(), false, true, false, null, {}, {}, { previews++; it.editableCopy() }, {},
                { submissions += it }, catalog = catalog)
        } }
        compose.onNodeWithText("Önizle").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick()
        compose.onNodeWithText("Önizle").performScrollTo().performClick()
        compose.onNodeWithText("amenity: drinking_water").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-duplicates-reviewed").assertDoesNotExist()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("osm-map-publish-reviewed").performScrollTo().performClick()
        val oldClick = requireNotNull(compose.onNodeWithTag("osm-map-publish").performScrollTo()
            .fetchSemanticsNode().config[SemanticsActions.OnClick].action)
        compose.onNodeWithTag("osm-map-publish").performClick()
        compose.runOnIdle {
            oldClick()
            assertEquals(1, previews)
            assertEquals(1, submissions.size)
            assertEquals(mapOf("amenity" to "drinking_water"), submissions.single().publicTags())
        }
    }

    @Test fun loginReceivesReviewedNameAndPhoneWithoutPublishing() {
        var loginDraft: MapEditDraft? = null
        compose.setContent { MaterialTheme {
            MapEditEditor(draft(), false, false, false, null, {}, {}, { it.editableCopy() }, { loginDraft = it },
                { error("Must not publish") }, catalog = catalog)
        } }
        compose.onNodeWithTag("osm-map-name").performScrollTo().performTextReplacement("Yeni çeşme")
        compose.onNodeWithTag("osm-map-phone").performScrollTo().performTextReplacement("+90 312 555 01 23")
        compose.onNodeWithTag("osm-map-survey").performScrollTo().performClick()
        compose.onNodeWithText("Önizle").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-publish-reviewed").performScrollTo().performClick()
        compose.onNodeWithText("OSM düzenleme iznini etkinleştir").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("Yeni çeşme", loginDraft?.name)
            assertEquals("+90 312 555 01 23", loginDraft?.phone)
        }
    }

    @Test fun resumedLegacyAddressRemainsReadOnlyAndAppearsInPublicTagPreview() {
        val legacy = MapEditDraft(latitude = 39.9, longitude = 32.8,
            preset = MapPlacePreset.DRINKING_WATER, street = "Eski taslak sokağı", houseNumber = "12",
            surveyConfirmed = true)
        compose.setContent { MaterialTheme {
            MapEditEditor(legacy, false, true, false, null, {}, {}, { it.editableCopy() }, {},
                { error("Preview alone must not publish") }, catalog = catalog)
        } }
        compose.onNodeWithText("Cadde / sokak (varsa)").assertDoesNotExist()
        compose.onNodeWithText("Kapı numarası (varsa)").assertDoesNotExist()
        compose.onNodeWithText("Önizle").performScrollTo().performClick()
        compose.onNodeWithText("addr:street: Eski taslak sokağı").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("addr:housenumber: 12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().assertIsNotEnabled()
    }

    @Test fun previewShowsReadableFieldsAndTheFrozenTypeNameRatherThanCurrentCatalogName() {
        val current = catalog.snapshot("amenity/drinking_water", OsmPresetGeometry.POINT)
        val frozen = current.copy(ruleJson = org.json.JSONObject(current.ruleJson)
            .put("name", "Taslakta sabit çeşme türü").toString())
        val initial = newMapEditDraft(39.9, 32.8, "Sahil çeşmesi").selectPreset(frozen)
            .copy(phone = "+90 312 555 01 23", phoneAction = MapEditFieldAction.SET, surveyConfirmed = true)
        compose.setContent { MaterialTheme {
            MapEditEditor(initial, false, true, false, null, {}, {}, { it.editableCopy() }, {},
                { error("Preview alone must not publish") }, catalog = catalog)
        } }
        compose.onNodeWithText("Önizle").performScrollTo().performClick()
        compose.onNodeWithText("Ad: Sahil çeşmesi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tür: Taslakta sabit çeşme türü").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Telefon: +90 312 555 01 23").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("amenity: drinking_water").performScrollTo().assertIsDisplayed()
    }

    @Test fun retryPreviewPublishesTheNormalizedSavedRowInsteadOfFailedAttemptMetadata() {
        val failed = draft().copy(status = MapEditStatus.FAILED, stage = MapEditStage.CREATE_CHANGESET,
            submittedAt = 1000, submittedBy = 41, error = "Definitely rejected", surveyConfirmed = true)
        var normalized: MapEditDraft? = null
        var published: MapEditDraft? = null
        compose.setContent { MaterialTheme {
            MapEditEditor(failed, false, true, false, null, {}, {},
                { it.editableCopy().also { row -> normalized = row } }, {},
                { published = it }, catalog = catalog)
        } }
        compose.onNodeWithText("Önizle").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-publish-reviewed").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-publish").performScrollTo().performClick()
        compose.runOnIdle {
            assertSame(normalized, published)
            assertEquals(MapEditStatus.DRAFT, published!!.status)
            assertEquals(MapEditStage.NONE, published!!.stage)
            assertNull(published!!.submittedAt)
            assertNull(published!!.error)
        }
    }
}
