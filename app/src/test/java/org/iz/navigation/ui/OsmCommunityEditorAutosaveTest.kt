package org.iz.navigation.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.iz.navigation.osmcommunity.CommunityDraft
import org.iz.navigation.osmcommunity.CommunityDraftStatus
import org.iz.navigation.ui.history.UiEditState
import org.iz.navigation.ui.history.UiExitGuard
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OsmCommunityEditorAutosaveTest {
    @Test fun openingUnchangedDraftAndTimestampOnlyUpdateAreClean() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(updatedAt = 999))
        assertFalse(h.editor.state.value.dirty)
        advanceTimeBy(300); runCurrent()
        assertTrue(h.writes.isEmpty())
        var exits = 0
        h.syncGuard()
        h.guard.requestExit { exits++ }
        assertEquals(1, exits)
        assertNull(h.guard.pending.value)
    }

    @Test fun localAutosaveDebouncesToLatestContentAndAcknowledgesOnlyAfterCompletion() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "first"))
        advanceTimeBy(200); runCurrent()
        h.editor.change(h.initial.copy(body = "latest"))
        advanceTimeBy(249); runCurrent()
        assertTrue(h.writes.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf("latest"), h.writes.map { it.draft.body })
        assertTrue(h.editor.state.value.dirty)
        assertTrue(h.editor.state.value.saving)
        h.writes.single().ack.complete(Unit); runCurrent()
        assertFalse(h.editor.state.value.dirty)
        assertFalse(h.editor.state.value.saving)
        assertEquals("latest", h.editor.state.value.baseline?.body)
        assertEquals(h.editor.state.value.editRevision, h.editor.state.value.acknowledgedRevision)
    }

    @Test fun editingBackToAcknowledgedContentCancelsPendingSave() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "delta"))
        h.editor.change(h.initial)
        advanceTimeBy(300); runCurrent()
        assertFalse(h.editor.state.value.dirty)
        assertTrue(h.writes.isEmpty())
    }

    @Test fun discardBeforeDebounceNeverWritesDeltaAgainOnLifecycleFlushOrDispose() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "discard this"))
        h.syncGuard()
        var exits = 0
        h.guard.requestExit { exits++; h.editor.close() }
        assertNotNull(h.guard.pending.value)
        assertTrue(h.writes.isEmpty()) // Back itself cannot force save.
        h.guard.discardAndExit()
        h.guard.discardAndExit()
        h.editor.saveNow() // A late stop callback sees no active working delta.
        advanceTimeBy(500); runCurrent()
        assertEquals(1, exits)
        assertTrue(h.writes.isEmpty())
        assertNull(h.editor.state.value.draft)
    }

    @Test fun keepEditingLeavesInputAndScheduledAutosaveIntact() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "keep"))
        h.syncGuard()
        h.guard.requestExit { fail("Keep must not exit") }
        h.guard.keepEditing()
        assertEquals("keep", h.editor.state.value.draft?.body)
        advanceTimeBy(250); runCurrent()
        assertEquals("keep", h.writes.single().draft.body)
    }

    @Test fun saveCompletingDuringConfirmationKeepsCommittedBaselineAndExitsOnce() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "committed while dialog open"))
        h.syncGuard()
        var exits = 0
        h.guard.requestExit { exits++ }
        advanceTimeBy(250); runCurrent()
        h.syncGuard()
        h.guard.discardAndExit()
        assertEquals(0, exits) // Accepted repository write cannot be cancelled or rolled back.
        h.writes.single().ack.complete(Unit); runCurrent()
        h.syncGuard()
        assertNull("ACK retires the obsolete unsaved warning and pending action", h.guard.pending.value)
        h.guard.discardAndExit()
        assertEquals("A completed autosave stays on the form", 0, exits)
        h.guard.requestExit { exits++ }
        h.guard.discardAndExit()
        assertEquals("Only a new Back exits the now-clean form", 1, exits)
        assertEquals("committed while dialog open", h.editor.state.value.baseline?.body)
        assertEquals("committed while dialog open", h.editor.state.value.draft?.body)
        assertEquals(1, h.writes.size)
    }

    @Test fun newerEditDuringAcceptedSaveRemainsDirtyAndSerializesNextWrite() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "older"))
        advanceTimeBy(250); runCurrent()
        h.editor.change(h.initial.copy(body = "newer"))
        advanceTimeBy(250); runCurrent()
        assertEquals(1, h.writes.size)
        assertFalse(h.editor.discardUnsaved())
        h.writes[0].ack.complete(Unit); runCurrent()
        assertEquals("older", h.editor.state.value.baseline?.body)
        assertEquals("newer", h.editor.state.value.draft?.body)
        assertTrue(h.editor.state.value.dirty)
        assertEquals(listOf("older", "newer"), h.writes.map { it.draft.body })
        h.writes[1].ack.complete(Unit); runCurrent()
        assertEquals("newer", h.editor.state.value.baseline?.body)
        assertFalse(h.editor.state.value.dirty)
    }

    @Test fun sameAccountNewCacheAndOtherAccountRejectLateAcknowledgmentAndError() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "old login"))
        advanceTimeBy(250); runCurrent()
        val replacement = h.initial.copy(body = "new login baseline")
        h.editor.open(replacement, "new-login", replacement)
        h.writes[0].ack.complete(Unit); runCurrent()
        assertEquals("new login baseline", h.editor.state.value.baseline?.body)
        assertFalse(h.editor.state.value.dirty)
        h.editor.change(replacement.copy(body = "new login delta"))
        advanceTimeBy(250); runCurrent()
        val other = h.initial.copy(accountId = 2, body = "other account")
        h.editor.open(other, "other-login", other)
        h.writes[1].ack.completeExceptionally(IllegalStateException("old private error")); runCurrent()
        assertEquals(2L, h.editor.state.value.draft?.accountId)
        assertEquals("other account", h.editor.state.value.baseline?.body)
        assertEquals(0, h.errors)
    }

    @Test fun failedSaveKeepsUnsavedContentAndDoesNotRetryInALoop() = runTest {
        val h = Harness(this)
        h.open()
        h.editor.change(h.initial.copy(body = "retry explicitly"))
        advanceTimeBy(250); runCurrent()
        h.writes.single().ack.completeExceptionally(IllegalStateException("failure")); runCurrent()
        assertTrue(h.editor.state.value.dirty)
        assertFalse(h.editor.state.value.saving)
        assertEquals("saved", h.editor.state.value.baseline?.body)
        assertEquals(1, h.errors)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, h.writes.size)
        h.editor.saveNow()
        assertEquals(2, h.writes.size)
    }

    @Test fun restoredWorkingInputUsesPersistedBaselineAndNeverRestoresExitToken() = runTest {
        val h = Harness(this)
        h.editor.open(h.initial.copy(body = "restored delta"), "login", h.initial)
        h.syncGuard()
        assertTrue(h.editor.state.value.dirty)
        assertNull(h.guard.pending.value)
        assertEquals("saved", h.editor.state.value.baseline?.body)
        assertEquals("restored delta", h.editor.state.value.draft?.body)
    }

    @Test fun blockedSendingOrUnknownDraftNeverSchedulesAnEditableSave() = runTest {
        for (status in listOf(CommunityDraftStatus.SENDING, CommunityDraftStatus.UNKNOWN)) {
            val h = Harness(this)
            h.editor.open(h.initial.copy(status = status), "login", h.initial.copy(status = status))
            h.editor.change(h.initial.copy(body = "cannot replace locked draft"))
            h.editor.saveNow()
            advanceTimeBy(300); runCurrent()
            assertTrue(h.writes.isEmpty())
            assertEquals(status, h.editor.state.value.draft?.status)
        }
    }

    private class Write(val draft: CommunityDraft, val ack: CompletableDeferred<Unit> = CompletableDeferred())
    private class Harness(scope: TestScope) {
        val initial = CommunityDraft(id = "draft", accountId = 1, title = "Title", body = "saved", createdAt = 1, updatedAt = 1)
        val writes = mutableListOf<Write>()
        var errors = 0
        val editor = OsmCommunityEditorAutosave(scope.backgroundScope,
            persist = { value -> Write(value).also { writes += it }.ack },
            onSnapshot = {}, onError = { errors++ })
        val guard = UiExitGuard()
        fun open() = editor.open(initial, "login", initial)
        fun syncGuard() {
            val state = editor.state.value
            guard.update(UiEditState("community-entry", state.draft?.accountId?.toString() + ":" + state.cacheIdentity,
                state.editRevision, state.acknowledgedRevision, state.dirty, state.saving)) { editor.discardUnsaved() }
        }
    }
}