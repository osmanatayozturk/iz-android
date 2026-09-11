package org.iz.navigation.ui

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import org.iz.navigation.IzApplication
import org.iz.navigation.osmcommunity.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

internal enum class CommunitySection { MESSAGES, CONTACTS, ACCOUNT }
internal enum class CommunityFolder { INBOX, OUTBOX, DRAFTS }

internal data class CommunityUiState(
    val accountId: Long? = null,
    val cacheIdentity: String? = null,
    val section: CommunitySection = CommunitySection.MESSAGES,
    val folder: CommunityFolder = CommunityFolder.INBOX,
    val detail: OsmMessageDetail? = null,
    val person: OsmCommunityProfile? = null,
    val editor: CommunityDraft? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

internal fun communityTargetMatches(messageId: Long?, requestedAccountId: Long?, activeAccountId: Long?): Boolean =
    messageId != null && messageId > 0 && requestedAccountId != null && requestedAccountId > 0 &&
        requestedAccountId == activeAccountId

internal fun communityDraftCanSend(draft: CommunityDraft): Boolean =
    draft.status in setOf(CommunityDraftStatus.DRAFT, CommunityDraftStatus.FAILED) &&
        ((draft.recipientId != null && draft.recipientId > 0 && draft.recipientName.isBlank()) ||
            (draft.recipientId == null && draft.recipientName.isNotBlank())) &&
        draft.title.isNotBlank() && CommunityText.titleLength(draft.title) <= 255 && draft.body.isNotBlank()

internal fun publishCommunityUi(state: MutableStateFlow<CommunityUiState>, expectedAccount: Long?,
    activeAccount: Long?, expectedCache: String? = null, activeCache: String? = null,
    update: (CommunityUiState) -> CommunityUiState,
) {
    val before = state.value
    if (expectedAccount == activeAccount && before.accountId == expectedAccount &&
        expectedCache == activeCache && before.cacheIdentity == expectedCache)
        state.compareAndSet(before, update(before).copy(accountId = expectedAccount, cacheIdentity = expectedCache))
}

internal class OsmCommunityViewModel(application: Application, private val savedState: SavedStateHandle,
    val repository: OsmCommunityRepository,
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState, (application as IzApplication).community)
    val community = repository.state
    private val mutableUi = MutableStateFlow(CommunityUiState(accountId = community.value.accountId, cacheIdentity = community.value.cacheIdentity))
    val ui = mutableUi.asStateFlow()
    private var operation: Job? = null
    private var draftSave: Job? = null
    private var draftRevision = 0L
    private var pendingRestore = savedState.get<Bundle>(EDITOR_STATE)
    private var pendingNotification: Pair<Long, Long>? = null

    init {
        viewModelScope.launch {
            community.collect { state ->
                if (state.accountId != mutableUi.value.accountId || state.cacheIdentity != mutableUi.value.cacheIdentity) {
                    if (state.accountId != mutableUi.value.accountId) pendingNotification = null
                    operation?.cancel()
                    draftSave?.cancel()
                    draftRevision++
                    mutableUi.value = CommunityUiState(accountId = state.accountId, cacheIdentity = state.cacheIdentity)
                    if (state.accountId == null || pendingRestore?.getLong("account") != state.accountId) clearSavedEditor()
                } else {
                    val editor = mutableUi.value.editor
                    val stored = state.drafts.firstOrNull { it.id == editor?.id }
                    if (stored != null && stored.status in setOf(CommunityDraftStatus.SENDING, CommunityDraftStatus.UNKNOWN)) {
                        mutableUi.value = mutableUi.value.copy(editor = stored)
                        clearSavedEditor()
                    }
                }
                if (state.ready) {
                    restoreEditor(state)
                    pendingNotification?.let { target ->
                        pendingNotification = null
                        openNotification(target.first, target.second)
                    }
                }
            }
        }
    }

    fun section(value: CommunitySection) { persistEditor(); clearSavedEditor(); mutableUi.value = mutableUi.value.copy(section = value, detail = null, person = null, editor = null) }
    fun folder(value: CommunityFolder) { persistEditor(); clearSavedEditor(); mutableUi.value = mutableUi.value.copy(folder = value, detail = null, person = null, editor = null) }
    fun clearError() { mutableUi.value = mutableUi.value.copy(error = null, notice = null) }
    fun reportError(message: String) { mutableUi.value = mutableUi.value.copy(error = message) }

    fun refresh() = runOperation { repository.refresh() }
    fun loadMore() = runOperation {
        repository.refreshMailbox(if (mutableUi.value.folder == CommunityFolder.OUTBOX) CommunityMailbox.OUTBOX else CommunityMailbox.INBOX, true)
    }

    fun openNotification(messageId: Long?, accountId: Long?) {
        if (!communityTargetMatches(messageId, accountId, community.value.accountId)) {
            mutableUi.value = CommunityUiState(accountId = community.value.accountId, cacheIdentity = community.value.cacheIdentity,
                error = "Bu bildirim başka bir OSM hesabına ait. İlgili hesabı bağlayıp mesajı Mesajlar bölümünden açabilirsin.")
            return
        }
        if (!community.value.ready) {
            pendingNotification = messageId!! to accountId!!
            return
        }
        openMessage(messageId!!)
    }

    fun openMessage(id: Long) = runOperation {
        val account = community.value.accountId ?: return@runOperation
        val cache = community.value.cacheIdentity
        val detail = repository.message(id)
        if (community.value.accountId != account) return@runOperation
        updateFor(account, cache) { it.copy(section = CommunitySection.MESSAGES, detail = detail, editor = null, person = null) }
        if (detail.summary.toId == account && !detail.summary.read) {
            repository.markRead(id, true)
            updateFor(account, cache) { it.copy(detail = detail.copy(summary = detail.summary.copy(read = true))) }
        }
    }

    fun openProfile(id: Long) = runOperation {
        val account = community.value.accountId
        val cache = community.value.cacheIdentity
        val profile = repository.profile(id)
        updateFor(account, cache) { it.copy(person = profile, detail = null) }
    }

    fun back(): Boolean {
        if (mutableUi.value.editor == null && mutableUi.value.detail == null && mutableUi.value.person == null) return false
        persistEditor()
        clearSavedEditor()
        mutableUi.value = mutableUi.value.copy(editor = null, detail = null, person = null)
        return true
    }

    fun editDraft(draft: CommunityDraft) {
        if (draft.accountId != community.value.accountId || !community.value.ready || mutableUi.value.busy) return
        mutableUi.value = mutableUi.value.copy(editor = draft, detail = null, person = null, section = CommunitySection.MESSAGES, folder = CommunityFolder.DRAFTS)
        rememberEditor(draft)
    }

    fun compose(recipientId: Long? = null, title: String = "") {
        val accountId = community.value.accountId ?: return
        editDraft(CommunityDraft(accountId = accountId, recipientId = recipientId, title = CommunityText.truncateTitle(title)))
        persistEditor()
    }

    fun reply() {
        val detail = mutableUi.value.detail ?: return
        val account = community.value.accountId ?: return
        val recipient = if (detail.summary.fromId == account) detail.summary.toId else detail.summary.fromId
        val title = if (detail.summary.title.startsWith("Re:", ignoreCase = true)) detail.summary.title else "Re: ${detail.summary.title}"
        compose(recipient, title)
    }

    fun changeDraft(draft: CommunityDraft) {
        val current = mutableUi.value.editor ?: return
        if (current.id != draft.id || draft.accountId != community.value.accountId || mutableUi.value.busy ||
            current.status in setOf(CommunityDraftStatus.UNKNOWN, CommunityDraftStatus.SENDING)) return
        val next = draft.copy(status = CommunityDraftStatus.DRAFT, error = null, updatedAt = System.currentTimeMillis())
        mutableUi.value = mutableUi.value.copy(editor = next)
        val inlineSnapshot = rememberEditor(next)
        draftSave?.cancel()
        val revision = ++draftRevision
        draftSave = if (inlineSnapshot) viewModelScope.launch {
            delay(250); enqueueSave(next, revision)
        } else {
            // Large text cannot fit safely in Android's Activity state parcel.
            enqueueSave(next, revision)
            null
        }
    }

    fun persistEditor() {
        val draft = mutableUi.value.editor ?: return
        if (draft.status !in setOf(CommunityDraftStatus.DRAFT, CommunityDraftStatus.FAILED)) return
        draftSave?.cancel()
        val revision = ++draftRevision
        enqueueSave(draft, revision)
    }

    private fun enqueueSave(draft: CommunityDraft, revision: Long) {
        // The repository owns this captured write; finishing the Activity cannot cancel it.
        val pending = repository.persistDraft(draft)
        val cache = community.value.cacheIdentity
        viewModelScope.launch {
            try { pending.await() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (revision == draftRevision) updateFor(draft.accountId, cache) {
                    it.copy(error = "Taslak kaydedilemedi. Ekranı kapatmadan yeniden dene.")
                }
            }
        }
    }

    fun send() {
        val draft = mutableUi.value.editor ?: return
        if (!community.value.canSend || !communityDraftCanSend(draft) || mutableUi.value.busy) return
        val cache = community.value.cacheIdentity
        draftSave?.cancel()
        draftRevision++
        clearSavedEditor()
        runOperation {
            updateFor(draft.accountId, cache) { it.copy(editor = draft.copy(status = CommunityDraftStatus.SENDING)) }
            try {
                repository.persistDraft(draft).await()
                repository.sendDraft(draft.id)
                updateFor(draft.accountId, cache) { it.copy(editor = null, folder = CommunityFolder.OUTBOX, notice = "Mesaj gönderildi.") }
            } catch (e: Exception) {
                val stored = community.value.drafts.firstOrNull { it.id == draft.id }
                updateFor(draft.accountId, cache) { it.copy(editor = stored ?: draft) }
                if (community.value.accountId == draft.accountId && community.value.cacheIdentity == cache) rememberEditor(stored ?: draft)
                throw e
            }
        }
    }

    fun copyUncertainDraft() {
        val draft = mutableUi.value.editor ?: return
        if (draft.status != CommunityDraftStatus.UNKNOWN || mutableUi.value.busy) return
        val now = System.currentTimeMillis()
        mutableUi.value = mutableUi.value.copy(editor = draft.copy(id = UUID.randomUUID().toString(),
            status = CommunityDraftStatus.DRAFT, createdAt = now, updatedAt = now, error = null))
        mutableUi.value.editor?.let(::rememberEditor)
        persistEditor()
    }

    fun deleteDraft() {
        val draft = mutableUi.value.editor ?: return
        val cache = community.value.cacheIdentity
        draftSave?.cancel()
        draftRevision++
        clearSavedEditor()
        runOperation { repository.deleteDraft(draft.id); updateFor(draft.accountId, cache) { it.copy(editor = null) } }
    }

    fun markRead() {
        val detail = mutableUi.value.detail ?: return
        val account = community.value.accountId
        val cache = community.value.cacheIdentity
        if (detail.summary.toId != account) return
        runOperation {
            repository.markRead(detail.summary.id, !detail.summary.read)
            updateFor(account, cache) { it.copy(detail = detail.copy(summary = detail.summary.copy(read = !detail.summary.read))) }
        }
    }

    fun deleteMessage() {
        val detail = mutableUi.value.detail ?: return
        val account = community.value.accountId
        val cache = community.value.cacheIdentity
        runOperation { repository.deleteMessage(detail.summary.id); updateFor(account, cache) { it.copy(detail = null) } }
    }

    fun notifications(enabled: Boolean) = runOperation { repository.setNotificationsEnabled(enabled) }

    private fun runOperation(block: suspend () -> Unit) {
        if (mutableUi.value.busy) return
        val account = community.value.accountId
        val cache = community.value.cacheIdentity
        mutableUi.value = mutableUi.value.copy(busy = true, error = null, notice = null)
        operation = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = when (e) {
                    is CommunityHttpException -> when (e.statusCode) {
                        401 -> "OSM oturumun sona erdi. Hesabını yeniden bağla."
                        403 -> "Bu işlem için OSM izni gerekiyor. Hesabım bölümünden mesaj izinlerini ver."
                        404 -> "Mesaj veya kullanıcı bulunamadı. Listeyi yenileyebilirsin."
                        429 -> "OSM kısa bir bekleme istiyor. Biraz sonra yeniden dene."
                        else -> "OSM işlemi tamamlanamadı. Bilgileri kontrol edip yeniden dene."
                    }
                    is IOException -> "Bağlantı kurulamadı. Kayıtlı içerikleri görebilirsin; internet bağlantını kontrol et."
                    else -> e.message?.takeIf { it.length < 220 } ?: "İşlem tamamlanamadı. Yeniden deneyebilirsin."
                }
                updateFor(account, cache) { it.copy(error = message) }
            } finally {
                updateFor(account, cache) { it.copy(busy = false) }
            }
        }
    }

    private fun updateFor(account: Long?, cache: String?, update: (CommunityUiState) -> CommunityUiState) =
        publishCommunityUi(mutableUi, account, community.value.accountId, cache, community.value.cacheIdentity, update)

    private fun rememberEditor(draft: CommunityDraft): Boolean {
        val state = community.value
        val identity = state.cacheIdentity ?: return false
        if (draft.accountId != state.accountId || draft.status !in setOf(CommunityDraftStatus.DRAFT, CommunityDraftStatus.FAILED)) {
            clearSavedEditor()
            return false
        }
        val inline = listOf(draft.recipientName, draft.title, draft.body)
            .sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_EDITOR_SNAPSHOT_BYTES
        savedState[EDITOR_STATE] = Bundle().apply {
            putString("cache", identity); putLong("account", draft.accountId); putString("id", draft.id)
            putBoolean("inline", inline)
            if (inline) {
                draft.recipientId?.let { putLong("recipientId", it) }
                putString("recipientName", draft.recipientName); putString("title", draft.title); putString("body", draft.body)
                putLong("createdAt", draft.createdAt); putLong("updatedAt", draft.updatedAt)
            }
        }
        return inline
    }

    private fun clearSavedEditor() { pendingRestore = null; savedState.remove<Bundle>(EDITOR_STATE) }

    private fun restoreEditor(state: CommunityState) {
        val saved = pendingRestore ?: return
        pendingRestore = null
        if (saved.getLong("account") != state.accountId || saved.getString("cache") != state.cacheIdentity) {
            savedState.remove<Bundle>(EDITOR_STATE)
            return
        }
        val id = saved.getString("id") ?: return
        val stored = state.drafts.firstOrNull { it.id == id }
        if (stored != null && stored.status in setOf(CommunityDraftStatus.SENDING, CommunityDraftStatus.UNKNOWN)) {
            mutableUi.value = mutableUi.value.copy(editor = stored, folder = CommunityFolder.DRAFTS)
            savedState.remove<Bundle>(EDITOR_STATE)
            return
        }
        val restored = if (saved.getBoolean("inline", true)) {
            CommunityDraft(id = id, accountId = saved.getLong("account"),
                recipientId = if (saved.containsKey("recipientId")) saved.getLong("recipientId") else null,
                recipientName = saved.getString("recipientName").orEmpty(), title = saved.getString("title").orEmpty(),
                body = saved.getString("body").orEmpty(), createdAt = saved.getLong("createdAt"), updatedAt = saved.getLong("updatedAt"))
        } else stored
        if (restored == null) {
            clearSavedEditor()
            mutableUi.value = mutableUi.value.copy(error = "Önceki taslak bulunamadı. Taslaklar veya Gönderilen bölümünü kontrol et.")
            return
        }
        // Validate against durable UNKNOWN/retired-id guards before exposing an editable restored draft.
        val pending = repository.persistDraft(restored)
        viewModelScope.launch {
            try {
                pending.await()
                updateFor(state.accountId, state.cacheIdentity) { it.copy(editor = restored, folder = CommunityFolder.DRAFTS) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                updateFor(state.accountId, state.cacheIdentity) {
                    it.copy(editor = null, error = "Önceki taslak geri açılamadı. Güncel durumu Taslaklar veya Gönderilen bölümünde kontrol et.")
                }
                if (community.value.cacheIdentity == state.cacheIdentity) clearSavedEditor()
            }
        }
    }

    private companion object {
        const val EDITOR_STATE = "osm-community-editor"
        const val MAX_EDITOR_SNAPSHOT_BYTES = 32 * 1024L
    }
}
