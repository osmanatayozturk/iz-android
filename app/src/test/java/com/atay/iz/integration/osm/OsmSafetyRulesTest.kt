package com.atay.iz.integration.osm

import org.junit.Assert.*
import org.junit.Test

class OsmSafetyRulesTest {
    private val startedAt = 1_800_000_000_123L
    private val expected = NoteAttempt(41, 41.01, 29.02, "Drinking fountain here.", startedAt)
    private val note = OsmRemoteNote(99, 41.01, 29.02, "open", 1_800_000_001_000L,
        listOf(OsmNoteComment(41, "opened", "Drinking fountain here.", 1_800_000_001_000L)))

    @Test fun reconciliationAcceptsOnlyOneMatchingPublicNote() {
        assertEquals(99L, OsmSafetyRules.reconcile(expected, listOf(note))?.id)
        assertNull(OsmSafetyRules.reconcile(expected, listOf(note, note.copy(id = 100))))
    }

    @Test fun anotherPersonsNoteOrLaterCommentCannotClaimOurUnknownAttempt() {
        assertNull(OsmSafetyRules.reconcile(expected, listOf(note.copy(comments = listOf(
            OsmNoteComment(42, "opened", expected.text, note.createdAt), note.comments.first())))))
        assertNull(OsmSafetyRules.reconcile(expected, listOf(note.copy(comments = listOf(
            note.comments.first().copy(action = "commented"))))))
    }

    @Test fun olderOrNearbyOrEditedTextNoteCannotClaimTheAttempt() {
        assertNull(OsmSafetyRules.reconcile(expected, listOf(note.copy(createdAt = startedAt - 180_000))))
        assertNull(OsmSafetyRules.reconcile(expected, listOf(note.copy(longitude = 29.0201))))
        assertNull(OsmSafetyRules.reconcile(expected, listOf(note.copy(comments = listOf(
            note.comments.first().copy(text = expected.text + " "))))))
    }

    @Test fun reconciliationAllowsBoundedClockSkewButStillRequiresUniqueness() {
        val slightlyEarlier = note.copy(createdAt = startedAt - 2000)
        assertEquals(note.id, OsmSafetyRules.reconcile(expected, listOf(slightlyEarlier))?.id)
        assertNull(OsmSafetyRules.reconcile(expected, listOf(slightlyEarlier, note.copy(id = 100))))
    }

    @Test fun callbackRejectsStateClientAndExpiredTransactionChanges() {
        val pending = OAuthTransaction("client", "random-state", startedAt)
        assertTrue(OsmSafetyRules.validCallback(pending, "client", "random-state", "com.atay.iz:/oauth2redirect", startedAt + 1000))
        assertFalse(OsmSafetyRules.validCallback(pending, "client", "wrong", "com.atay.iz:/oauth2redirect", startedAt + 1000))
        assertFalse(OsmSafetyRules.validCallback(pending, "other", "random-state", "com.atay.iz:/oauth2redirect", startedAt + 1000))
        assertFalse(OsmSafetyRules.validCallback(pending, "client", "random-state", "com.atay.iz:/oauth2redirect/evil", startedAt + 1000))
        assertFalse(OsmSafetyRules.validCallback(pending, "client", "random-state", "com.atay.iz:/oauth2redirect", startedAt + 600_001))
    }

    @Test fun actualCallbackUriMustUseTheRegisteredPathWithoutAuthorityOrFragment() {
        assertTrue(OsmSafetyRules.isExactRedirect("com.atay.iz:/oauth2redirect?code=abc&state=xyz"))
        for (invalid in listOf(null, "com.atay.iz:/oauth2redirect/evil?code=abc", "com.atay.iz://evil/oauth2redirect?code=abc",
            "com.atay.iz:/oauth2redirect#code=abc", "com.atay.iz:/%6fauth2redirect?code=abc", "https://osm.org/oauth2redirect")) {
            assertFalse(OsmSafetyRules.isExactRedirect(invalid))
        }
    }
}
