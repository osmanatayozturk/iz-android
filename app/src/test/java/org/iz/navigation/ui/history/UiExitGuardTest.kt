package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiExitGuardTest {
    private fun edit(entry: String = "editor", revision: Long = 2, acknowledged: Long = 1,
        dirty: Boolean = true, busy: Boolean = false, account: String? = "account-a") =
        UiEditState(entry, account, revision, acknowledged, dirty, busy)

    @Test fun cleanNavigationExitsWithoutSavingOrDiscarding() {
        val guard = UiExitGuard(); var exits = 0
        guard.update(edit(dirty = false)) { error("Clean state must not discard") }
        guard.requestExit { exits++ }
        assertEquals(1, exits); assertNull(guard.pending.value)
    }
    @Test fun keepEditingPreservesWorkingInputAndNeverExits() {
        val guard = UiExitGuard(); var text = "working"; var exits = 0
        guard.update(edit()) { text = "baseline" }
        guard.requestExit { exits++ }; assertNotNull(guard.pending.value)
        guard.keepEditing(); guard.discardAndExit()
        assertEquals("working", text); assertEquals(0, exits)
    }
    @Test fun discardCallsOnlyFeatureHookAndOriginalExitExactlyOnce() {
        val guard = UiExitGuard(); var discards = 0; val exits = mutableListOf<String>()
        guard.update(edit()) { discards++ }
        guard.requestExit { exits += "original" }
        guard.requestExit { exits += "replacement" }
        guard.discardAndExit(); guard.discardAndExit()
        assertEquals(1, discards); assertEquals(listOf("original"), exits)
    }
    @Test fun busyBlocksBothImmediateExitAndPendingDiscard() {
        val guard = UiExitGuard(); var exits = 0; var discards = 0
        guard.update(edit(busy = true)) { discards++ }
        guard.requestExit { exits++ }; assertNull(guard.pending.value)
        guard.update(edit()) { discards++ }; guard.requestExit { exits++ }
        guard.update(edit(busy = true)) { discards++ }; guard.discardAndExit()
        assertNotNull(guard.pending.value); assertEquals(0, exits); assertEquals(0, discards)
        guard.update(edit()) { discards++ }; guard.discardAndExit()
        assertEquals(1, exits); assertEquals(1, discards)
    }
    @Test fun acknowledgedAutosaveDuringDialogIsNeverDeletedOrRolledBack() {
        val guard = UiExitGuard(); var exits = 0
        guard.update(edit()) { error("Must use current acknowledgment, not captured discard") }
        guard.requestExit { exits++ }
        guard.update(edit(acknowledged = 2, dirty = false)) { error("Acknowledged baseline must remain") }
        assertNull("Acknowledged clean state must retire the stale unsaved prompt", guard.pending.value)
        assertEquals("Acknowledgment itself must not navigate", 0, exits)
        guard.discardAndExit(); assertEquals("Old confirmation must be inert", 0, exits)
        guard.requestExit { exits++ }; assertEquals(1, exits)
    }
    @Test fun staleEntryAccountOrWorkingRevisionCannotConfirmPreviousExit() {
        for (replacement in listOf(edit(entry = "other"), edit(account = "account-b"), edit(revision = 3))) {
            val guard = UiExitGuard()
            guard.update(edit()) { error("Old discard") }; guard.requestExit { error("Old exit") }
            guard.update(replacement) { error("Replacement discard") }
            guard.discardAndExit(); assertNull(guard.pending.value)
        }
    }
    @Test fun disposingOldOwnerCannotClearTheReplacementOwner() {
        val guard = UiExitGuard()
        guard.update(edit(entry = "old")); guard.update(edit(entry = "new"))
        guard.clearOwner("old", "account-a")
        assertEquals("new", guard.editState.value?.entryKey)
    }
    @Test fun oldRegistrationCannotDisposeReplacementWithSameEntryAccountAndRevision() {
        val guard = UiExitGuard(); val oldOwner = Any(); val replacementOwner = Any()
        val identical = edit(); val discarded = mutableListOf<String>(); var exits = 0
        guard.updateOwned(oldOwner, identical) { discarded += "old" }
        guard.updateOwned(replacementOwner, identical) { discarded += "replacement" }
        guard.clearOwner(oldOwner)
        assertEquals("Old disposal must retain the replacement registration, even with identical public IDs",
            identical, guard.editState.value)
        guard.requestExit { exits++ }; guard.discardAndExit()
        assertEquals(listOf("replacement"), discarded); assertEquals(1, exits)
        guard.clearOwner(replacementOwner); assertNull(guard.editState.value)
    }
    @Test fun childRegistrationKeepsPriorityAndDisposalRevealsUnchangedParent() {
        val guard = UiExitGuard(); val parent = Any(); val child = Any()
        guard.updateOwned(parent, edit(entry = "parent"))
        guard.updateOwned(child, edit(entry = "child"))
        guard.updateOwned(parent, edit(entry = "parent", revision = 3))
        assertEquals("child", guard.editState.value?.entryKey)
        guard.clearOwner(child)
        assertEquals("parent", guard.editState.value?.entryKey)
        assertEquals(3L, guard.editState.value?.editRevision)
    }
    @Test fun replacementRegistrationInvalidatesPendingEvenWhenAllPublicFieldsMatch() {
        val guard = UiExitGuard(); val oldOwner = Any(); val replacement = Any()
        guard.updateOwned(oldOwner, edit()) { error("Stale discard") }
        guard.requestExit { error("Stale exit") }
        guard.updateOwned(replacement, edit())
        assertNull(guard.pending.value)
        guard.discardAndExit(); guard.clearOwner(oldOwner)
        assertEquals(edit(), guard.editState.value)
    }
    @Test fun throwingDiscardDoesNotRunExitOrAllowReplay() {
        val guard = UiExitGuard(); var exits = 0
        guard.update(edit()) { throw IllegalStateException("Feature refused discard") }
        guard.requestExit { exits++ }
        try { guard.discardAndExit(); fail("Must preserve feature failure") } catch (_: IllegalStateException) { }
        guard.discardAndExit(); assertEquals(0, exits); assertNull(guard.pending.value)
    }
}
