package org.iz.navigation.ui.history

import org.junit.Assert.*
import org.junit.Test

class UiExitGuardLiveTest {
    private fun edit() = UiEditState("editor", "account", 2, 1, true, false)
    @Test fun busyWithoutRecompositionBlocksBothRequestAndConfirmation() {
        val guard = UiExitGuard(); var live = edit(); var exits = 0; var discards = 0
        guard.updateOwned(Any(), live, currentState = { live }, discardUnsaved = { discards++ })
        live = live.copy(busy = true)
        guard.requestExit { exits++ }; assertNull("Actual busy state must block requesting exit", guard.pending.value)
        live = live.copy(busy = false); guard.requestExit { exits++ }; assertNotNull(guard.pending.value)
        live = live.copy(busy = true); guard.discardAndExit()
        assertEquals(0, exits); assertEquals(0, discards); assertNotNull(guard.pending.value)
        live = live.copy(busy = false); guard.discardAndExit()
        assertEquals(1, exits); assertEquals(1, discards)
    }
    @Test fun workingRevisionChangedWithoutRecompositionRetiresOldExit() {
        val guard = UiExitGuard(); var live = edit(); var exits = 0; var discards = 0
        guard.updateOwned(Any(), live, currentState = { live }, discardUnsaved = { discards++ })
        guard.requestExit { exits++ }
        live = live.copy(editRevision = 3)
        guard.discardAndExit()
        assertNull(guard.pending.value); assertEquals(0, exits); assertEquals(0, discards)
        guard.requestExit { exits++ }; assertEquals(3L, guard.pending.value?.editRevision)
    }
    @Test fun acknowledgmentWithoutRecompositionRetiresPromptAndStaysInForm() {
        val guard = UiExitGuard(); var live = edit(); var exits = 0
        guard.updateOwned(Any(), live, currentState = { live }, discardUnsaved = { fail("Committed baseline must never be discarded") })
        guard.requestExit { exits++ }
        live = live.copy(acknowledgedRevision = 2, dirty = false)
        guard.discardAndExit()
        assertNull(guard.pending.value); assertEquals("Live acknowledgment must not execute the old exit", 0, exits)
        guard.requestExit { exits++ }; assertEquals(1, exits)
    }
}
