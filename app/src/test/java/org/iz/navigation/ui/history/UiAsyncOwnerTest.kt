package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiAsyncOwnerTest {
    @Test fun resultRemainsBoundToOriginalEntryAndWorkingRevision() {
        val state = UiEditState("page/editor", "a", 3, 2, true, false)
        val owner = UiAsyncOwner.capture("page", state)
        assertTrue(owner.matches("page", state.copy(acknowledgedRevision = 3, dirty = false)))
        assertFalse(owner.matches("replacement", state))
        assertFalse(owner.matches("page", state.copy(editRevision = 4)))
        assertFalse(owner.matches("page", state.copy(accountKey = "b")))
        assertFalse(owner.matches("page", null))
    }
    @Test fun guardCapturesLiveRevisionWithoutWaitingForComposition() {
        val guard = UiExitGuard(); var live = UiEditState("editor", "a", 2, 1, true, false)
        guard.updateOwned(Any(), live, currentState = { live }, discardUnsaved = {})
        live = live.copy(editRevision = 3)
        val result = requireNotNull(guard.captureAsyncOwner("page"))
        assertTrue("Capture must read actual revision3, not last composition revision2", result.matches("page", live))
    }
    @Test fun guardRejectsResultAfterLiveAccountOrRevisionChangeWithoutComposition() {
        for (changeAccount in listOf(false, true)) {
            val guard = UiExitGuard(); var live = UiEditState("editor", "a", 2, 1, true, false)
            guard.updateOwned(Any(), live, currentState = { live }, discardUnsaved = {})
            val result = guard.captureAsyncOwner("page")
            live = if (changeAccount) live.copy(accountKey = "b") else live.copy(editRevision = 3)
            assertFalse("New live owner must reject an old OS result", guard.acceptsAsyncOwner(result, "page"))
        }
    }
    @Test fun failedLiveProviderIsNotConfusedWithCleanUnownedUi() {
        val clean = UiExitGuard()
        assertNotNull("No editor is a valid clean capture", clean.captureAsyncOwner("page"))
        val guard = UiExitGuard(); var fail = false
        val state = UiEditState("editor", "a", 2, 1, true, false)
        guard.updateOwned(Any(), state, currentState = { check(!fail); state }, discardUnsaved = {})
        val captured = guard.captureAsyncOwner("page"); fail = true
        assertNull("A failing getter must never produce a usable clean lease", guard.captureAsyncOwner("page"))
        assertFalse(guard.acceptsAsyncOwner(captured, "page"))
    }
    @Test fun resultsWithoutEditorStillRequireTheirOriginalPage() {
        val owner = UiAsyncOwner.capture("places", null)
        assertTrue(owner.matches("places", null)); assertFalse(owner.matches("journeys", null))
        assertFalse(owner.matches("places", UiEditState("new-editor", null, 0, 0, false, false)))
    }
}
