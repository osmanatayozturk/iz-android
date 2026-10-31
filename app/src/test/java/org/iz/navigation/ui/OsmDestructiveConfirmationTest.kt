package org.iz.navigation.ui

import org.junit.Assert.*
import org.junit.Test
import org.iz.navigation.ui.history.UiHistory
import org.iz.navigation.ui.history.UiPage
import org.iz.navigation.ui.history.UiExitGuard
import org.iz.navigation.ui.history.UiEditState

class OsmDestructiveConfirmationTest {
    private data class Draft(val id: String = "a", val revision: Int = 1, val eligible: Boolean = true)
    private val session = Any()
    private fun owner(entry: String = "standalone", account: Long? = 7, cache: String? = "cache",
        identity: Any? = session, history: UiHistory? = null) = OsmConfirmationOwner(entry, account, cache, identity, history)
    private fun gate() = OsmDestructiveConfirmation<Draft> { it.eligible }

    @Test fun validConfirmationProducesOnceOnlyPermit() {
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(), false))
        var deletes = 0
        val permit = gate.take(request, owner(), target, false)
        assertNotNull("An unchanged eligible target must produce a permit", permit)
        assertNull("Consume confirmation before invoking any side effect", gate.pending.value)
        assertTrue(permit!!.applyIfCurrent(owner(), target, false) { deletes++ })
        assertFalse(permit.applyIfCurrent(owner(), target, false) { deletes++ })
        assertEquals(1, deletes)
        assertNull(gate.take(request, owner(), target, false))
    }

    @Test fun requestAndEveryCancelPathPerformNoMutation() {
        for (cancel in listOf("button", "back", "outside")) {
            val gate = gate()
            val target = Draft()
            val request = requireNotNull(gate.request(target, owner(), false))
            assertEquals(target, gate.pending.value?.target)
            gate.dismiss(request)
            assertNull(cancel, gate.pending.value)
            assertNull(gate.take(request, owner(), target, false))
        }
    }

    @Test fun changedMissingOrIneligibleRowCannotBeConfirmed() {
        val initial = Draft()
        for (current in listOf(initial.copy(revision = 2), initial.copy(eligible = false), null)) {
            val gate = gate()
            val request = requireNotNull(gate.request(initial, owner(), false))
            assertNull(gate.take(request, owner(), current, false))
            assertNull(gate.pending.value)
        }
        assertNull(gate().request(initial.copy(eligible = false), owner(), false))
    }

    @Test fun accountCacheEntryAndOpaqueSessionReplacementInvalidateOldConfirmation() {
        val initial = Draft()
        for (replacement in listOf(owner(entry = "other"), owner(account = 8), owner(cache = "new-cache"),
            owner(identity = Any()), owner(account = null, cache = null, identity = null))) {
            val gate = gate()
            val request = requireNotNull(gate.request(initial, owner(), false))
            assertNull(gate.take(request, replacement, initial, false))
            assertNull(gate.pending.value)
        }
    }

    @Test fun anonymousLocalDraftCanBeConfirmedWithoutInventingAnAccount() {
        val gate = gate()
        val anonymous = owner(account = null, cache = null, identity = null)
        val target = Draft()
        val request = requireNotNull(gate.request(target, anonymous, false))
        val permit = requireNotNull(gate.take(request, anonymous, target, false))
        var calls = 0
        assertTrue(permit.applyIfCurrent(anonymous, target, false) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun busyBlocksRequestAndConfirmationWithoutQueuingAnAction() {
        val gate = gate()
        val target = Draft()
        assertNull(gate.request(target, owner(), true))
        assertNull(gate.pending.value)
        val request = requireNotNull(gate.request(target, owner(), false))
        assertNull(gate.take(request, owner(), target, true))
        assertSame(request, gate.pending.value)
        val permit = requireNotNull(gate.take(request, owner(), target, false))
        var calls = 0
        assertFalse(permit.applyIfCurrent(owner(), target, true) { calls++ })
        assertFalse("A rejected permit cannot replay after busy ends",
            permit.applyIfCurrent(owner(), target, false) { calls++ })
        assertEquals(0, calls)
    }

    @Test fun deferredDirtyExitRevalidatesOwnerAndTargetBeforeMutation() {
        val target = Draft()
        for (newTarget in listOf(target.copy(revision = 3), null)) {
            val gate = gate()
            val request = requireNotNull(gate.request(target, owner(), false))
            val permit = requireNotNull(gate.take(request, owner(), target, false))
            var calls = 0
            assertFalse(permit.applyIfCurrent(owner(), newTarget, false) { calls++ })
            assertEquals(0, calls)
        }
        val gate = gate()
        val request = requireNotNull(gate.request(target, owner(), false))
        val permit = requireNotNull(gate.take(request, owner(), target, false))
        assertFalse(permit.applyIfCurrent(owner(identity = Any()), target, false) { fail("Old login must not disconnect new login") })
    }

    @Test fun disposeInvalidatesPendingAndAlreadyGrantedPermit() {
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(), false))
        val permit = requireNotNull(gate.take(request, owner(), target, false))
        gate.invalidate()
        assertFalse(permit.applyIfCurrent(owner(), target, false) { fail("Disposed entry") })
        val next = requireNotNull(gate.request(target, owner(), false))
        gate.invalidate()
        assertNull(gate.take(next, owner(), target, false))
        assertNull("Recreation creates a fresh owner without an exit token", gate().pending.value)
    }

    @Test fun staleDismissCannotClearNewerDialog() {
        val gate = gate()
        val target = Draft()
        val old = requireNotNull(gate.request(target, owner(), false))
        gate.dismiss(old)
        val newer = requireNotNull(gate.request(target.copy(id = "b"), owner(), false))
        gate.dismiss(old)
        assertSame(newer, gate.pending.value)
    }

    @Test fun doubleRequestCannotReplaceTheTargetUnderAnOpenDialog() {
        val gate = gate()
        val first = requireNotNull(gate.request(Draft(), owner(), false))
        assertNull(gate.request(Draft(id = "b"), owner(), false))
        assertSame(first, gate.pending.value)
    }

    @Test fun sideEffectFailureConsumesPermitAndNeverInventsSuccess() {
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(), false))
        val permit = requireNotNull(gate.take(request, owner(), target, false))
        assertThrows(IllegalStateException::class.java) {
            permit.applyIfCurrent(owner(), target, false) { error("Local failure") }
        }
        assertNull(gate.pending.value)
        assertFalse(permit.applyIfCurrent(owner(), target, false) { fail("No automatic retry") })
    }

    @Test fun reentrantConfirmationCannotApplyTheSameActionTwice() {
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(), false))
        val permit = requireNotNull(gate.take(request, owner(), target, false))
        var calls = 0
        assertTrue(permit.applyIfCurrent(owner(), target, false) {
            calls++
            assertFalse(permit.applyIfCurrent(owner(), target, false) { calls++ })
            assertNull(gate.take(request, owner(), target, false))
        })
        assertEquals(1, calls)
    }

    @Test fun historyPruneBlocksRequestBeforeRecomposition() {
        val history = UiHistory()
        val ancestor = history.push(UiPage.JOURNEY, entityId = "journey").key
        val captured = history.push(UiPage.MAP_EDITS).key
        val gate = gate()
        history.prune { it.key != ancestor }
        assertEquals("map", history.current.key)
        assertNull("Removed entry cannot request consent through a still-mounted callback",
            gate.request(Draft(), owner(entry = captured, history = history), false))
        assertNull(gate.pending.value)
    }

    @Test fun historyReplacementBlocksConfirmationBeforeRecomposition() {
        val history = UiHistory()
        val captured = history.push(UiPage.COMMUNITY).key
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(entry = captured, history = history), false))
        history.prune { it.key != captured }
        val replacement = history.push(UiPage.COMMUNITY).key
        assertNotEquals(captured, replacement)
        assertNull("Same page with a new entry cannot inherit old consent",
            gate.take(request, owner(entry = captured, history = history), target, false))
        assertNull(gate.pending.value)
    }

    @Test fun historyPruneBlocksDeferredExitCallbackBeforeRecomposition() {
        val history = UiHistory()
        val ancestor = history.push(UiPage.PLACE, entityId = "place").key
        val captured = history.push(UiPage.COMMUNITY).key
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(entry = captured, history = history), false))
        val permit = requireNotNull(gate.take(request, owner(entry = captured, history = history), target, false))
        val exit = UiExitGuard()
        exit.update(UiEditState(captured + "/editor", "7:cache", 1, 0, true, false))
        var mutations = 0
        exit.requestExit {
            permit.applyIfCurrent(owner(entry = captured, history = history), target, false) { mutations++ }
        }
        assertNotNull(exit.pending.value)
        // Real prune does not clear the exit guard; no recomposition/disposal is simulated.
        history.prune { it.key != ancestor }
        exit.discardAndExit()
        assertEquals("Deferred callback must read the current history key", 0, mutations)
        assertFalse(permit.applyIfCurrent(owner(entry = captured, history = history), target, false) { mutations++ })
    }

    @Test fun historyPruneInvalidatesOwnerAfterStartupSuspension() {
        val history = UiHistory()
        val captured = history.push(UiPage.MAP_EDITS).key
        val expected = owner(entry = captured, history = history)
        assertTrue(expected.matches(owner(entry = captured, history = history)))
        // Model only the suspension boundary: the same captured owner survives startup await.
        history.prune { it.key != captured }
        assertFalse("Post-await owner check must read history before any database mutation",
            expected.matches(owner(entry = captured, history = history)))
    }

    @Test fun liveHistoryEntryAllowsConsentAndMissingHistoryRequiresStandalone() {
        val history = UiHistory()
        val captured = history.push(UiPage.MAP_EDITS).key
        val gate = gate()
        val target = Draft()
        val request = requireNotNull(gate.request(target, owner(entry = captured, history = history), false))
        history.updateBrowse(captured, org.iz.navigation.ui.history.UiBrowseState(query = "cafe"))
        val permit = requireNotNull(gate.take(request, owner(entry = captured, history = history), target, false))
        var mutations = 0
        assertTrue(permit.applyIfCurrent(owner(entry = captured, history = history), target, false) { mutations++ })
        assertEquals(1, mutations)
        assertNull("A missing provider cannot validate a non-standalone entry",
            gate.request(target, owner(entry = captured, history = null), false))
        assertNotNull("Explicit standalone remains usable without a history provider",
            gate.request(target, owner(entry = "standalone", history = null), false))
    }
}