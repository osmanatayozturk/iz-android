package org.iz.navigation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.iz.navigation.osmcommunity.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmCommunityUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun messageMarkupRemainsPlainTextAndOnlySafeWebLinksOpen() {
        var opened: String? = null
        val body = "<b>Merhaba</b> [tehlikeli](javascript:alert(1)) https://www.openstreetmap.org/"
        compose.setContent { MaterialTheme { CommunityPlainText(body) { opened = it } } }
        compose.onNodeWithText(body).assertExists()
        compose.onNodeWithText("https://www.openstreetmap.org/").performClick()
        compose.runOnIdle { assertEquals("https://www.openstreetmap.org/", opened) }
    }

    @Test fun unknownDraftCannotSendAndCopyRequiresDuplicateWarning() {
        var copied = 0
        val draft = CommunityDraft(accountId = 7, recipientId = 8, recipientName = "Haritacı",
            title = "Merhaba", body = "Yol bilgisi", status = CommunityDraftStatus.UNKNOWN)
        compose.setContent { MaterialTheme {
            CommunityComposer(draft, canSend = true, busy = false, onChange = {}, onClose = {},
                onSend = { error("An uncertain send must never repeat") }, onDelete = {},
                onOutbox = {}, onCopy = { copied++ })
        } }
        compose.onNodeWithTag("community-body").assertIsNotEnabled()
        compose.onNodeWithText("Gönder").assertDoesNotExist()
        compose.onNodeWithText("Yeni taslağa kopyala").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, copied) }
        compose.onNodeWithText("Aynı mesaj iki kez gidebilir", substring = true).assertExists()
        compose.onNodeWithText("Riski anladım, kopyala").performClick()
        compose.runOnIdle { assertEquals(1, copied) }
    }

    @Test fun recipientModeSendsOnlyIdOrNameAndPreservesBodyEdits() {
        var current = CommunityDraft(accountId = 7, recipientId = 8, recipientName = "Haritacı",
            title = "Merhaba", body = "Eski metin")
        compose.setContent { MaterialTheme {
            var draft by remember { mutableStateOf(current) }
            CommunityComposer(draft, canSend = true, busy = false, onChange = { draft = it; current = it },
                onClose = {}, onSend = {}, onDelete = {}, onOutbox = {}, onCopy = {})
        } }
        compose.onNodeWithText("Kullanıcı adı").performScrollTo().performClick()
        compose.onNodeWithTag("community-recipient").performTextReplacement("Yeni kişi")
        compose.onNodeWithTag("community-body").performScrollTo().performTextReplacement("Yeni metin")
        compose.runOnIdle {
            assertNull(current.recipientId)
            assertEquals("Yeni kişi", current.recipientName)
            assertEquals("Yeni metin", current.body)
        }
    }

    @Test fun sendingDraftLocksEditingAndRejectsConcurrentSubmit() {
        val draft = CommunityDraft(accountId = 7, recipientName = "Haritacı", title = "Merhaba",
            body = "Metin", status = CommunityDraftStatus.SENDING)
        compose.setContent { MaterialTheme {
            CommunityComposer(draft, true, true, {}, {}, { error("Double submit") }, {}, {}, {})
        } }
        compose.onNodeWithTag("community-body").assertIsNotEnabled()
        compose.onNodeWithText("Gönder").performScrollTo().assertIsNotEnabled()
    }

    @Test fun ownOutgoingMessageDoesNotOfferRecipientReadControl() {
        val summary = OsmMessageSummary(44, 7, "Ben", 8, "Diğer kişi", "Merhaba", 0, false)
        compose.setContent { MaterialTheme {
            CommunityMessageView(OsmMessageDetail(summary, "Düz metin"), 7, false,
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Okundu işaretle").assertDoesNotExist()
        compose.onNodeWithText("Okunmadı işaretle").assertDoesNotExist()
        compose.onNodeWithText("Düz metin").assertExists()
    }

    @Test fun deleteExplainsOwnMailboxAndWaitsForConfirmation() {
        var deleted = 0
        val summary = OsmMessageSummary(44, 8, "Diğer kişi", 7, "Ben", "Merhaba", 0, true)
        compose.setContent { MaterialTheme {
            CommunityMessageView(OsmMessageDetail(summary, "Düz metin"), 7, false,
                {}, {}, {}, { deleted++ }, {}, {})
        } }
        compose.onNodeWithText("Mesajı sil").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("Yalnızca senin posta kutundan silinir", substring = true).assertExists()
        compose.onNodeWithText("Sil").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test fun permissionDenialShowsSystemBlockedStatusWithoutEnablingNotifications() {
        var preference: Boolean? = null
        compose.setContent { MaterialTheme {
            CommunityNotificationSettings(enabled = true, systemAllowed = false, canConsume = true,
                onEnabled = { preference = it }, onSystemSettings = {})
        } }
        compose.onNodeWithText("Android bildirim izni kapalı", substring = true).assertExists()
        compose.onNodeWithTag("community-notifications").assertIsOn().performClick()
        compose.runOnIdle { assertEquals(false, preference) }
    }
}
