package org.iz.navigation.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.iz.navigation.osmcommunity.CommunityDraft
import org.iz.navigation.osmcommunity.CommunityDraftStatus

internal data class OsmCommunityEditorState(
    val draft: CommunityDraft? = null,
    val baseline: CommunityDraft? = null,
    val cacheIdentity: String? = null,
    val editRevision: Long = 0,
    val acknowledgedRevision: Long = 0,
    val dirty: Boolean = false,
    val saving: Boolean = false,
)

/** Main-thread feature owner. Repository-owned accepted writes are never cancelled or rolled back. */
internal class OsmCommunityEditorAutosave(
    private val scope: CoroutineScope,
    private val persist: (CommunityDraft) -> Deferred<Unit>,
    private val onSnapshot: (CommunityDraft?) -> Unit,
    private val onError: () -> Unit,
) {
    private val mutableState = MutableStateFlow(OsmCommunityEditorState())
    val state = mutableState.asStateFlow()
    private var debounce: Job? = null
    private var generation = 0L
    private var revision = 0L
    private var saveAgain = false

    fun open(draft: CommunityDraft, cacheIdentity: String, persisted: CommunityDraft?) {
        require(persisted == null || persisted.id == draft.id && persisted.accountId == draft.accountId)
        debounce?.cancel(); debounce = null; saveAgain = false
        generation++
        // Opening an untouched new composer is not an unsaved edit. Existing persisted content is the ACK baseline.
        val baseline = persisted ?: draft
        val dirty = editable(draft) && !sameContent(draft, baseline)
        val nextRevision = ++revision
        mutableState.value = OsmCommunityEditorState(draft, baseline, cacheIdentity, nextRevision,
            if (dirty) nextRevision - 1 else nextRevision, dirty)
        onSnapshot(draft)
        if (dirty) scheduleSave()
    }

    fun change(draft: CommunityDraft) {
        val before = state.value
        val current = before.draft ?: return
        if (draft.id != current.id || draft.accountId != current.accountId || !editable(current)) return
        if (sameContent(draft, current)) return
        mutableState.value = before.copy(draft = draft, editRevision = ++revision,
            dirty = before.baseline?.let { !sameContent(draft, it) } == true)
        onSnapshot(draft)
        scheduleSave()
    }

    private fun scheduleSave() {
        debounce?.cancel(); debounce = null
        if (!state.value.dirty) { saveAgain = false; return }
        val owner = generation
        debounce = scope.launch {
            delay(250)
            if (owner == generation) saveNow()
        }
    }

    fun saveNow(force: Boolean = false) {
        debounce?.cancel(); debounce = null
        val before = state.value
        val draft = before.draft ?: return
        if (!editable(draft) || (!before.dirty && !force)) return
        if (before.saving) { saveAgain = true; return }
        val owner = generation
        val capturedRevision = before.editRevision
        saveAgain = false
        mutableState.value = before.copy(saving = true)
        val pending = try { persist(draft) }
        catch (_: Exception) { fail(owner); return }
        // await cancellation belongs to this observer, not to the independent repository Deferred.
        // https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-deferred/await.html
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                pending.await()
                if (owner != generation) return@launch
                val live = state.value
                val working = live.draft ?: return@launch
                mutableState.value = live.copy(baseline = draft, acknowledgedRevision = capturedRevision,
                    dirty = !sameContent(working, draft), saving = false)
                onSnapshot(working)
                val next = saveAgain
                saveAgain = false
                if (next && state.value.dirty) saveNow()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                fail(owner)
            } catch (_: Exception) {
                fail(owner)
            }
        }
    }

    private fun fail(owner: Long) {
        if (owner != generation) return
        saveAgain = false
        mutableState.value = state.value.copy(saving = false)
        onError()
    }

    /** Discards only delta not already accepted by the repository. */
    fun discardUnsaved(): Boolean {
        val before = state.value
        if (before.saving) return false
        debounce?.cancel(); debounce = null; saveAgain = false
        val nextRevision = ++revision
        mutableState.value = before.copy(draft = before.baseline, dirty = false,
            editRevision = nextRevision, acknowledgedRevision = nextRevision)
        onSnapshot(before.baseline)
        return true
    }

    fun close() {
        val hadEditor = state.value.draft != null
        debounce?.cancel(); debounce = null; saveAgain = false
        generation++
        mutableState.value = OsmCommunityEditorState(editRevision = ++revision)
        if (hadEditor) onSnapshot(null)
    }

    private fun editable(draft: CommunityDraft) =
        draft.status in setOf(CommunityDraftStatus.DRAFT, CommunityDraftStatus.FAILED)

    private fun sameContent(a: CommunityDraft, b: CommunityDraft) =
        a.id == b.id && a.accountId == b.accountId && a.recipientId == b.recipientId &&
            a.recipientName == b.recipientName && a.title == b.title && a.body == b.body
}