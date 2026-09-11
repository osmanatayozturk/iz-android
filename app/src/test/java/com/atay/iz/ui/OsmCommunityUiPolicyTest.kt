package com.atay.iz.ui

import com.atay.iz.osmcommunity.CommunityDraft
import com.atay.iz.osmcommunity.CommunityDraftStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class OsmCommunityUiPolicyTest {
    @Test fun lateUiResponseCannotPublishAcrossAccountChange() {
        val state = MutableStateFlow(CommunityUiState(accountId = 5))
        publishCommunityUi(state, 4, 5) { it.copy(error = "Old account error") }
        assertEquals(5L, state.value.accountId)
        assertNull(state.value.error)
    }

    @Test fun accountResetDuringResponsePublicationWins() {
        val state = MutableStateFlow(CommunityUiState(accountId = 4))
        publishCommunityUi(state, 4, 4) {
            state.value = CommunityUiState(accountId = 5)
            it.copy(error = "Old account error")
        }
        assertEquals(5L, state.value.accountId)
        assertNull(state.value.error)
    }

    @Test fun unchangedAccountReceivesResponse() {
        val state = MutableStateFlow(CommunityUiState(accountId = 4))
        publishCommunityUi(state, 4, 4) { it.copy(notice = "Gönderildi") }
        assertEquals("Gönderildi", state.value.notice)
    }

    @Test fun sameAccountNewLoginRejectsPreviousCacheResponse() {
        val state = MutableStateFlow(CommunityUiState(accountId = 4, cacheIdentity = "new-login"))
        publishCommunityUi(state, 4, 4, "old-login", "new-login") { it.copy(error = "Old error") }
        assertNull(state.value.error)
    }

    @Test fun titleLimitCountsEmojiAsOneCharacter() {
        val draft = CommunityDraft(accountId = 4, recipientName = "Haritacı", title = "🚲".repeat(255), body = "Metin")
        assertTrue(communityDraftCanSend(draft))
        assertFalse(communityDraftCanSend(draft.copy(title = "🚲".repeat(256))))
    }
    @Test fun notificationTargetRequiresSameConnectedAccountAndPositiveMessageId() {
        assertTrue(communityTargetMatches(77, 4, 4))
        assertFalse(communityTargetMatches(77, 4, 5))
        assertFalse(communityTargetMatches(77, 4, null))
        assertFalse(communityTargetMatches(0, 4, 4))
        assertFalse(communityTargetMatches(77, null, 4))
    }

    @Test fun onlyOneRecipientModeAndEditableDraftCanSend() {
        val draft = CommunityDraft(accountId = 4, recipientName = "Haritacı", title = "Konu", body = "Metin")
        assertTrue(communityDraftCanSend(draft))
        assertTrue(communityDraftCanSend(draft.copy(recipientId = 8, recipientName = "")))
        assertFalse(communityDraftCanSend(draft.copy(recipientId = 8)))
        assertFalse(communityDraftCanSend(draft.copy(recipientId = 0, recipientName = "")))
        assertFalse(communityDraftCanSend(draft.copy(recipientName = "")))
        assertFalse(communityDraftCanSend(draft.copy(title = " ")))
        assertFalse(communityDraftCanSend(draft.copy(title = "x".repeat(256))))
        assertFalse(communityDraftCanSend(draft.copy(body = " ")))
        assertFalse(communityDraftCanSend(draft.copy(status = CommunityDraftStatus.UNKNOWN)))
        assertFalse(communityDraftCanSend(draft.copy(status = CommunityDraftStatus.SENDING)))
        assertTrue(communityDraftCanSend(draft.copy(status = CommunityDraftStatus.FAILED)))
    }
}
