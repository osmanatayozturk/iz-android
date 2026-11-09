package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.OsmType
import org.iz.navigation.integration.osm.OsmCorrectionElement
import org.iz.navigation.integration.osm.OsmCorrectionReview
import org.iz.navigation.integration.osm.OsmCredentials
import org.iz.navigation.integration.osm.OsmUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapEditCorrectionUiTest {
    @get:Rule val compose = createComposeRule()

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

    @Test fun closedWayReviewPublishesOnlyConfirmedAddressAndPreservesOrderedRefsAndTags() {
        val original = OsmCorrectionElement(
            ref = OsmRef(OsmType.WAY, 900),
            version = 21,
            changesetId = 110,
            userId = 30,
            latitude = null,
            longitude = null,
            nodeRefs = listOf(10L, 6L, 14L, 2L, 10L),
            tags = mapOf("amenity" to "cafe", "building" to "yes", "name" to "Alan kafe",
                "addr:street" to "Sahil Sokak", "addr:housenumber" to "8", "operator" to "Yerel işletme"),
        )
        val sent = reviewAndPublish(original, "addr:housenumber", "12A", "8")
        assertEquals(OsmRef(OsmType.WAY, 900), sent.ref)
        assertEquals(21L, sent.version)
        assertNull(sent.latitude)
        assertNull(sent.longitude)
        assertEquals(listOf(10L, 6L, 14L, 2L, 10L), sent.nodeRefs)
        assertEquals(mapOf("amenity" to "cafe", "building" to "yes", "name" to "Alan kafe",
            "addr:street" to "Sahil Sokak", "addr:housenumber" to "12A", "operator" to "Yerel işletme"), sent.tags)
    }

    private fun reviewAndPublish(
        original: OsmCorrectionElement,
        key: String,
        input: String,
        before: String,
    ): OsmCorrectionElement {
        val draft = MapEditDraft(latitude = 40.0, longitude = 30.0,
            targetType = original.ref.type, targetId = original.ref.id)
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
                    onNoteFallback = { error("Unexpected note") },
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
