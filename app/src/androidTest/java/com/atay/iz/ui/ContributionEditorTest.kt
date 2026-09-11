package com.atay.iz.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.atay.iz.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContributionEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun observationRequiresPreviewThenExplicitPublish() {
        val draft = ContributionDraft(latitude = 39.9, longitude = 32.8, observedAt = 1_700_000_000_000L, kind = ContributionKind.MISSING_PLACE, text = "Burada bir bisiklet parkı var.")
        var published: ContributionDraft? = null
        compose.setContent { MaterialTheme {
            ContributionEditor(draft, connected = true, busy = false, onDismiss = {}, onSave = {}, onPublish = { published = it }, onLogin = {})
        } }
        compose.onNodeWithText("Gönderimi önizle").performScrollTo().performClick()
        compose.runOnIdle { assertNull(published) }
        compose.onNodeWithText("OSM’ye gönder").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(draft.text, published?.text); assertEquals(39.9, published!!.latitude, 0.0) }
    }

    @Test fun blankObservationCannotBePublished() {
        val draft = ContributionDraft(latitude = 39.9, longitude = 32.8, observedAt = 1_700_000_000_000L, kind = ContributionKind.OTHER, text = "")
        compose.setContent { MaterialTheme {
            ContributionEditor(draft, connected = true, busy = false, onDismiss = {}, onSave = {}, onPublish = { error("Empty observation published") }, onLogin = {})
        } }
        compose.onNodeWithText("Gönderimi önizle").performScrollTo().assertIsNotEnabled()
    }

    @Test fun loginReceivesCurrentEditedObservationForDurableSave() {
        val draft = ContributionDraft(latitude = 39.9, longitude = 32.8, observedAt = 1_700_000_000_000L, kind = ContributionKind.OTHER, text = "Eski gözlem")
        var pending: ContributionDraft? = null
        compose.setContent { MaterialTheme {
            ContributionEditor(draft, connected = false, busy = false, onDismiss = {}, onSave = {}, onPublish = { error("Login must not publish") }, onLogin = { pending = it })
        } }
        compose.onNodeWithTag("osm-observation").performTextReplacement("Tabelada yeni isim yazıyor.")
        compose.onNodeWithText("Gönderimi önizle").performScrollTo().performClick()
        compose.onNodeWithText("OSM hesabını bağla").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(draft.id, pending?.id); assertEquals("Tabelada yeni isim yazıyor.", pending?.text) }
    }
}
