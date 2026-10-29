package org.iz.navigation.ui.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Feature-owned working revision and last acknowledged persisted baseline. Never saved in history. */
data class UiEditState(val entryKey: String, val accountKey: String?, val editRevision: Long,
    val acknowledgedRevision: Long, val dirty: Boolean, val busy: Boolean)

data class UiPendingExit(val entryKey: String, val accountKey: String?, val editRevision: Long)

/** Main-thread UI owner. This class never saves, sends, stops a session or owns a draft. */
class UiExitGuard {
    private val mutableEdit = MutableStateFlow<UiEditState?>(null)
    val editState: StateFlow<UiEditState?> = mutableEdit.asStateFlow()
    private val mutablePending = MutableStateFlow<UiPendingExit?>(null)
    val pending: StateFlow<UiPendingExit?> = mutablePending.asStateFlow()
    private var discard: () -> Unit = {}
    private var pendingAction: (() -> Unit)? = null

    private data class Registration(val owner: Any, val state: UiEditState, val discard: () -> Unit,
        val currentState: () -> UiEditState = { state })
    private val registrations = mutableListOf<Registration>()
    private var currentOwner: Any? = null

    fun update(state: UiEditState?, discardUnsaved: () -> Unit = {}) {
        registrations.clear()
        publish(null, state, discardUnsaved)
    }
    fun updateOwned(owner: Any, state: UiEditState, discardUnsaved: () -> Unit = {}) {
        val index = registrations.indexOfFirst { it.owner === owner }
        val registration = Registration(owner, state, discardUnsaved)
        if (index < 0) registrations += registration else registrations[index] = registration
        // A still-mounted parent's recomposition must not steal its child's exit ownership.
        if (registrations.last().owner === owner) publish(owner, state, discardUnsaved)
    }
    // Live provider stays ephemeral and is read at the actual user action, not only composition.
    fun updateOwned(owner: Any, state: UiEditState, currentState: () -> UiEditState, discardUnsaved: () -> Unit) {
        updateOwned(owner, state, discardUnsaved)
        val index = registrations.indexOfFirst { it.owner === owner }
        registrations[index] = registrations[index].copy(currentState = currentState)
    }
    fun clearOwner(owner: Any) {
        registrations.removeAll { it.owner === owner }
        if (currentOwner === owner) {
            val parent = registrations.lastOrNull()
            publish(parent?.owner, parent?.state, parent?.discard ?: {})
        }
    }
    /** Legacy unregistered caller support. Registered Compose owners can only be cleared by identity. */
    fun clearOwner(entryKey: String, accountKey: String?, editRevision: Long? = null) {
        val current = mutableEdit.value ?: return
        if (currentOwner == null && current.entryKey == entryKey && current.accountKey == accountKey &&
            (editRevision == null || current.editRevision == editRevision)) publish(null, null, {})
    }
    private fun publish(owner: Any?, state: UiEditState?, discardUnsaved: () -> Unit) {
        if (owner !== currentOwner) keepEditing()
        currentOwner = owner
        mutableEdit.value = state
        discard = discardUnsaved
        // Autosave acknowledgment retires the prompt/action and stays in the form. Next Back is clean.
        if (mutablePending.value != null && (mutablePending.value?.matches(state) != true || state?.unsaved() != true)) keepEditing()
    }
    fun requestExit(exit: () -> Unit) {
        if (!refreshCurrent()) return
        if (mutablePending.value != null) return
        val current = mutableEdit.value
        if (current?.busy == true) return
        if (current == null || !current.unsaved()) { exit(); return }
        pendingAction = exit
        mutablePending.value = UiPendingExit(current.entryKey, current.accountKey, current.editRevision)
    }
    fun keepEditing() { mutablePending.value = null; pendingAction = null }
    fun discardAndExit() {
        if (!refreshCurrent()) return
        val token = mutablePending.value ?: return
        val current = mutableEdit.value
        if (!token.matches(current)) { keepEditing(); return }
        if (current?.busy == true) return
        val action = pendingAction ?: return
        val discardCurrent = discard
        // Clear before feature callbacks: neither a second tap nor a reentrant callback can replay this exit.
        keepEditing()
        if (current?.unsaved() == true) discardCurrent()
        action()
    }
    /** No editor is a valid clean lease; a failing live provider produces no usable lease. */
    fun captureAsyncOwner(entryKey: String): UiAsyncOwner? {
        if (!refreshCurrent()) return null
        return UiAsyncOwner.capture(entryKey, mutableEdit.value, currentOwner)
    }
    fun acceptsAsyncOwner(owner: UiAsyncOwner?, entryKey: String): Boolean {
        if (owner == null || !refreshCurrent()) return false
        return owner.matches(entryKey, mutableEdit.value, currentOwner)
    }
    private fun refreshCurrent(): Boolean {
        val owner = registrations.lastOrNull() ?: return true
        val live = try { owner.currentState() } catch (_: Exception) { keepEditing(); return false }
        publish(owner.owner, live, owner.discard)
        return true
    }
    private fun UiEditState.unsaved() = dirty && editRevision > acknowledgedRevision
    private fun UiPendingExit.matches(state: UiEditState?) = state != null && entryKey == state.entryKey &&
        accountKey == state.accountKey && editRevision == state.editRevision
}
