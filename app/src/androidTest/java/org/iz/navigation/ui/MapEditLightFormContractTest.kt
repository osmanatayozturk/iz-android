package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapPlacePreset
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test-first UI contract. Execution belongs to the allocated Build/Test owners. */
@RunWith(AndroidJUnit4::class)
class MapEditLightFormContractTest {
    @get:Rule val compose = createComposeRule()
    private val catalog by lazy {
        OsmPresetCatalog.load(ApplicationProvider.getApplicationContext<Context>().assets.open(OsmPresetCatalog.BUNDLED_ASSET))
    }

    @Test fun typeSearchCanReportNoResultsWithoutPublishing() {
        val initial = MapEditDraft(latitude = 41.0, longitude = 29.0, name = "Yerinde gözlem")
        var published = 0
        compose.setContent { MaterialTheme {
            MapEditEditor(initial, false, true, false, null, {}, {},
                { error("Opening type search must not query nearby objects") }, {}, { published++ }, catalog = catalog)
        } }
        compose.onNodeWithTag("osm-map-type").performScrollTo().performClick()
        compose.onNodeWithText("Tür ara").performTextInput("zzzz-no-such-preset-93742")
        compose.onNodeWithText("Tür bulunamadı. Başka bir ad veya etiket dene.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, published) }
    }

    @Test fun optionalPhoneIsEditableWhileLegacyAddressIsNotOfferedForEditing() {
        val initial = MapEditDraft(latitude = 41.0, longitude = 29.0,
            street = "Korunacak sokak", houseNumber = "12")
        compose.setContent { MaterialTheme {
            MapEditEditor(initial, false, true, false, null, {}, {},
                { error("Editing a phone must not query nearby objects") }, {}, { error("Unexpected publication") }, catalog = catalog)
        } }
        compose.onNodeWithText("Cadde / sokak (varsa)").assertDoesNotExist()
        compose.onNodeWithText("Kapı numarası (varsa)").assertDoesNotExist()
        compose.onNodeWithTag("osm-map-phone").performScrollTo().performTextReplacement("+90 312 555 01 23")
        compose.onNodeWithTag("osm-map-phone").assertTextContains("+90 312 555 01 23")
    }

    @Test fun clearingAnExistingNameShowsDeletionAndRestoringTheValueUndoesThatIntent() {
        val initial = MapEditDraft(latitude = 41.0, longitude = 29.0,
            preset = MapPlacePreset.CAFE, name = "Mevcut kafe", targetType = OsmType.NODE, targetId = 700)
        val original = OsmCorrectionElement(OsmRef(OsmType.NODE, 700), 12, 99, 30,
            "41.0", "29.0", emptyList(), mapOf("amenity" to "cafe", "name" to "Mevcut kafe"))
        compose.setContent { MaterialTheme {
            MapEditCorrectionEditor(initial = initial, target = original,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { initial }, liveBusy = { false }, onClose = {}, onSave = {},
                onReadTarget = { error("The supplied current object must be used") },
                onReview = { error("Clearing a field alone must not prepare publication") },
                isReviewCurrent = { false }, onLogin = {},
                onPublish = { error("Unexpected publication") }, onNoteFallback = {}, catalog = catalog)
        } }
        val warning = "Yayımlanırsa bu bilgi OSM'den silinecek."
        compose.onNodeWithText(warning).assertDoesNotExist()
        compose.onNodeWithTag("osm-map-correction-name").performScrollTo().performTextReplacement("")
        compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-correction-name").performScrollTo().performTextReplacement("Mevcut kafe")
        compose.onNodeWithText(warning).assertDoesNotExist()
    }
}
