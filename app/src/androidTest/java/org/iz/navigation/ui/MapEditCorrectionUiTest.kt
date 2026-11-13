package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditFieldAction
import org.iz.navigation.data.selectPreset
import org.iz.navigation.data.selectedPresetSnapshot
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCorrectionReview
import org.iz.navigation.integration.osm.OsmCredentials
import org.iz.navigation.integration.osm.OsmUser
import org.iz.navigation.integration.osm.editorDraft
import org.iz.navigation.osmcatalog.OsmPresetCatalog
import org.iz.navigation.osmcatalog.OsmPresetGeometry
import org.iz.navigation.osmcatalog.OsmPresetRuleSnapshot
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapEditCorrectionUiTest {
    @get:Rule val compose = createComposeRule()
    private val catalog by lazy {
        OsmPresetCatalog.load(ApplicationProvider.getApplicationContext<Context>().assets.open(OsmPresetCatalog.BUNDLED_ASSET))
    }

    @Test fun nodeReviewPublishesOnlyConfirmedNameAndPreservesCoordinatesAndTags() {
        val original = OsmCorrectionElement(
            ref = OsmRef(OsmType.NODE, 700),
            version = 12,
            changesetId = 99,
            userId = 30,
            latitude = "41.1234567",
            longitude = "29.2345678",
            nodeRefs = emptyList(),
            tags = mapOf("amenity" to "cafe", "name" to "Eski kafe", "addr:street" to "Sahil Sokak",
                "addr:housenumber" to "5", "opening_hours" to "08:00-19:00"),
        )
        val sent = reviewAndPublish(original, "name", "Yeni kafe", "Eski kafe")
        assertEquals(OsmRef(OsmType.NODE, 700), sent.ref)
        assertEquals(12L, sent.version)
        assertEquals("41.1234567", sent.latitude)
        assertEquals("29.2345678", sent.longitude)
        assertTrue(sent.nodeRefs.isEmpty())
        assertEquals(mapOf("amenity" to "cafe", "name" to "Yeni kafe", "addr:street" to "Sahil Sokak",
            "addr:housenumber" to "5", "opening_hours" to "08:00-19:00"), sent.tags)
    }

    @Test fun closedWayReviewPublishesPhoneAndPreservesAddressOrderedRefsAndTags() {
        val original = OsmCorrectionElement(
            ref = OsmRef(OsmType.WAY, 900),
            version = 21,
            changesetId = 110,
            userId = 30,
            latitude = null,
            longitude = null,
            nodeRefs = listOf(10L, 6L, 14L, 2L, 10L),
            tags = mapOf("amenity" to "cafe", "building" to "yes", "name" to "Alan kafe",
                "addr:street" to "Sahil Sokak", "addr:housenumber" to "8", "operator" to "Yerel işletme",
                "phone" to "0312 555 01 23", "contact:phone" to "0312 555 01 23"),
        )
        val sent = reviewAndPublish(original, "phone", "+90 312 555 45 67", "0312 555 01 23")
        assertEquals(OsmRef(OsmType.WAY, 900), sent.ref)
        assertEquals(21L, sent.version)
        assertNull(sent.latitude)
        assertNull(sent.longitude)
        assertEquals(listOf(10L, 6L, 14L, 2L, 10L), sent.nodeRefs)
        assertEquals(mapOf("amenity" to "cafe", "building" to "yes", "name" to "Alan kafe",
            "addr:street" to "Sahil Sokak", "addr:housenumber" to "8", "operator" to "Yerel işletme",
            "phone" to "+90 312 555 45 67"), sent.tags)
    }

    @Test fun legacyAddressOnlyCorrectionCanBeReviewedWithoutAddingEditableAddressFields() {
        val original = OsmCorrectionElement(OsmRef(OsmType.NODE, 701), 4, 99, 30,
            "41.0", "29.0", emptyList(),
            mapOf("amenity" to "cafe", "addr:street" to "Önceki sokak", "operator" to "Korunacak"))
        val legacy = MapEditDraft(latitude = 41.0, longitude = 29.0, targetType = OsmType.NODE,
            targetId = 701, street = "Taslakta düzeltilmiş sokak", surveyConfirmed = true)
        val session = OsmCredentials("fixture-token", OsmUser(41, "Test haritacı"), null, setOf("write_api"))
        var requested: MapEditDraft? = null
        compose.setContent { MaterialTheme {
            MapEditCorrectionEditor(initial = legacy, target = original,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { legacy }, liveBusy = { false }, onClose = {}, onSave = {},
                onReadTarget = { error("Target already supplied") },
                onReview = { value -> requested = value; original.review(value, session) },
                isReviewCurrent = { it.credentials === session }, onLogin = {},
                onPublish = { error("Review alone must not publish") }, onNoteFallback = {}, catalog = catalog)
        } }
        compose.onNodeWithTag("osm-map-correction-addr:street").assertDoesNotExist()
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").performScrollTo().performClick()
        compose.onNodeWithText("Önce: Önceki sokak").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sonra: Taslakta düzeltilmiş sokak").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(requireNotNull(requested).legacyAddressIntent)
            assertEquals("Taslakta düzeltilmiş sokak", requested!!.street)
        }
    }

    @Test fun closedHighwayUsesExternalEditorInsteadOfEditableForm() {
        assertUnsupportedArea(mapOf("highway" to "residential", "name" to "Kapalı yol"))
        compose.waitForIdle()
        OsmLightEditorNativeCapture.capture("05-unsupported-fallback")
    }

    @Test fun closedDualGeometryTrackWithoutAreaEvidenceUsesExternalEditor() {
        assertUnsupportedArea(mapOf("leisure" to "track", "name" to "Belirsiz pist"))
    }

    @Test fun explicitReloadPreservesEditsAndDeletesButRequiresNewObservationAndReview() {
        val original = OsmCorrectionElement(OsmRef(OsmType.NODE, 702), 4, 99, 30,
            "41.0", "29.0", emptyList(),
            mapOf("amenity" to "cafe", "name" to "Eski ad", "phone" to "0312 555 01 23"))
        val fresh = original.copy(version = 5, tags = original.tags +
            mapOf("name" to "Sunucudaki ad", "phone" to "0312 555 45 67"))
        val initial = original.editorDraft(catalog, UUID.randomUUID().toString(), 41.0, 29.0)
        val session = OsmCredentials("fixture-token", OsmUser(41, "Test haritacı"), null, setOf("write_api"))
        var reads = 0
        var reviews = 0
        compose.setContent { MaterialTheme {
            MapEditCorrectionEditor(initial = initial, target = original,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { initial }, liveBusy = { false }, onClose = {}, onSave = {},
                onReadTarget = { reads++; fresh },
                onReview = { value -> reviews++; (if (reads == 0) original else fresh).review(value, session) },
                isReviewCurrent = { it.credentials === session }, onLogin = {},
                onPublish = { error("Reload must never publish") }, onNoteFallback = {}, catalog = catalog)
        } }
        compose.onNodeWithTag("osm-map-correction-name").performScrollTo().performTextReplacement("Yerel ad")
        compose.onNodeWithTag("osm-map-correction-phone").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("osm-map-correction-survey").performScrollTo().performClick()
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-correction-reviewed").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-correction-rebase").performScrollTo().performClick()
        compose.onNodeWithTag("osm-map-correction-name").performScrollTo().assertTextContains("Yerel ad")
        compose.onNodeWithTag("osm-map-correction-phone").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("osm-map-correction-survey").performScrollTo().assertIsOff()
        compose.onNodeWithTag("osm-map-correction-publish").assertDoesNotExist()
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(1, reviews) }
        compose.onNodeWithTag("osm-map-correction-survey").performScrollTo().performClick()
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").performScrollTo().performClick()
        compose.onNodeWithText("Önce: Sunucudaki ad").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sonra: Yerel ad").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Önce: 0312 555 45 67").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sonra: Silinecek").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-correction-reviewed").performScrollTo().assertIsOff()
    }

    @Test fun existingPreviewShowsReadableBeforeAfterFieldsUsingFrozenTypeNamesAndExactAliasDeletion() {
        val original = OsmCorrectionElement(OsmRef(OsmType.NODE, 703), 4, 99, 30,
            "41.0", "29.0", emptyList(), mapOf("amenity" to "cafe", "name" to "Eski kafe",
                "phone" to "0312 555 01 23", "contact:phone" to "0312 555 01 23",
                "addr:street" to "Korunacak sokak"))
        fun named(snapshot: OsmPresetRuleSnapshot, name: String) = snapshot.copy(
            ruleJson = org.json.JSONObject(snapshot.ruleJson).put("name", name).toString())
        val basis = original.editorDraft(catalog, UUID.randomUUID().toString(), 41.0, 29.0)
        val old = named(requireNotNull(basis.selectedPresetSnapshot()), "Taslakta sabit kafe")
        val next = named(catalog.snapshot("amenity/restaurant", OsmPresetGeometry.POINT), "Taslakta sabit restoran")
        val initial = basis.copy(originalPresetSnapshot = old.toJson()).selectPreset(next).copy(
            name = "Yeni restoran", nameAction = MapEditFieldAction.SET,
            phone = "+90 312 555 45 67", phoneAction = MapEditFieldAction.SET, surveyConfirmed = true)
        val session = OsmCredentials("fixture-token", OsmUser(41, "Test haritacı"), null, setOf("write_api"))
        compose.setContent { MaterialTheme {
            MapEditCorrectionEditor(initial = initial, target = original,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { initial }, liveBusy = { false }, onClose = {}, onSave = {},
                onReadTarget = { error("Target already supplied") },
                onReview = { original.review(it, session) }, isReviewCurrent = { it.credentials === session },
                onLogin = {}, onPublish = { error("Preview must not publish") }, onNoteFallback = {}, catalog = catalog)
        } }
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").performScrollTo().performClick()
        compose.onNodeWithText("Ad: Eski kafe → Yeni restoran").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tür: Taslakta sabit kafe → Taslakta sabit restoran").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Telefon: 0312 555 01 23 → +90 312 555 45 67").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("contact:phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sonra: Silinecek").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        OsmLightEditorNativeCapture.capture("04-alias-delete-preview")
    }

    private fun assertUnsupportedArea(tags: Map<String, String>) {
        val original = OsmCorrectionElement(OsmRef(OsmType.WAY, 804), 4, 99, 30,
            null, null, listOf(10L, 20L, 30L, 10L), tags)
        val initial = MapEditDraft(latitude = 41.0, longitude = 29.0, editorVersion = 2,
            targetType = OsmType.WAY, targetId = 804)
        compose.setContent { MaterialTheme {
            MapEditCorrectionEditor(initial = initial, target = original,
                busy = false, canWriteMap = true, authBusy = false, authError = null,
                liveBaseline = { initial }, liveBusy = { false }, onClose = {}, onSave = {},
                onReadTarget = { error("Target already supplied") },
                onReview = { error("Unsupported geometry must not prepare a write") },
                isReviewCurrent = { false }, onLogin = {}, onPublish = { error("Unexpected write") },
                onNoteFallback = {}, catalog = catalog)
        } }
        compose.onNodeWithText("Bu OSM nesnesinin geometrisi İz'de düzenlenemiyor. OSM düzenleyicisini kullan.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OSM düzenleyicisinde aç").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-correction-name").assertDoesNotExist()
        compose.onNodeWithText("OSM bilgilerini kontrol et ve incele").assertDoesNotExist()
    }

    private fun reviewAndPublish(
        original: OsmCorrectionElement,
        key: String,
        input: String,
        before: String,
    ): OsmCorrectionElement {
        val draft = original.editorDraft(catalog, UUID.randomUUID().toString(), 40.0, 30.0)
        val session = OsmCredentials("fixture-token", OsmUser(41, "Test haritacı"), null, setOf("write_api"))
        val submissions = mutableListOf<OsmCorrectionReview>()
        compose.setContent {
            MaterialTheme {
                MapEditCorrectionEditor(
                    initial = draft, target = null, busy = false, canWriteMap = true, authBusy = false, authError = null,
                    liveBaseline = { draft }, liveBusy = { false }, onClose = {},
                    onSave = { error("Unexpected save") },
                    onReadTarget = { ref -> original.also { assertEquals(original.ref, ref) } },
                    onReview = { value -> original.review(value, session) },
                    isReviewCurrent = { it.credentials === session },
                    onLogin = { error("Unexpected login") }, onPublish = { submissions += it },
                    onNoteFallback = { error("Unexpected note") }, catalog = catalog,
                )
            }
        }
        compose.onNodeWithText("OSM’deki değer: $before").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("osm-map-correction-$key").performScrollTo().performTextReplacement(input)
        val preview = compose.onNodeWithText("OSM bilgilerini kontrol et ve incele")
        preview.performScrollTo().assertIsNotEnabled()
        confirmCheckbox("osm-map-correction-survey", "Düzelttiğim bilgileri yerinde doğruladım.")
        preview.performScrollTo().performClick()
        compose.onNodeWithText("OSM hesabı · Test haritacı").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Önce: $before").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sonra: $input").performScrollTo().assertIsDisplayed()
        val publish = compose.onNodeWithTag("osm-map-correction-publish")
        publish.performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(submissions.isEmpty()) }
        confirmCheckbox("osm-map-correction-reviewed", "Güncellenecek bilgileri ve OSM hesabımı kontrol ettim.")
        publish.performScrollTo().performClick()
        return compose.runOnIdle {
            val review = submissions.single()
            assertTrue(review.draft.surveyConfirmed)
            assertEquals(41L, review.credentials.user.id)
            review.corrected
        }
    }

    private fun confirmCheckbox(tag: String, label: String) {
        val checkbox = hasTestTag(tag) and hasText(label) and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)
        compose.onAllNodes(checkbox).assertCountEquals(1)
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNode(checkbox).performScrollTo().assertHeightIsAtLeast(48.dp)
            .assertIsOff().performClick().assertIsOn()
    }
}
