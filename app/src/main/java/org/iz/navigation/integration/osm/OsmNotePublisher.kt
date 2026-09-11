package org.iz.navigation.integration.osm

import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class OsmContributionResult(val success: Boolean, val message: String)

internal interface OsmContributionStore {
    suspend fun get(id: String): ContributionDraft?
    suspend fun begin(id: String, userId: Long, time: Long): ContributionDraft?
    suspend fun finish(id: String, time: Long, status: ContributionStatus, noteId: Long?, remoteStatus: String?, error: String?): Boolean
    suspend fun reconcile(id: String, time: Long, noteId: Long, remoteStatus: String): Boolean
    suspend fun refresh(id: String, noteId: Long, remoteStatus: String): Boolean
}

internal class OsmNotePublisher(
    private val store: OsmContributionStore,
    private val account: () -> OsmCredentials?,
    private val api: OsmNotesGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUnauthorized: (OsmCredentials) -> Unit = {},
) {
    suspend fun send(id: String): OsmContributionResult {
        val session = account() ?: return failure("Not göndermek için OSM hesabınızı bağlayın.")
        val draft = try { store.begin(id, session.user.id, clock()) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return failure("Taslak gönderime hazırlanamadı. Metni ve konumu kontrol edin.") }
            ?: return failure("Bu taslak gönderilemiyor. Belirsiz gönderimlerde önce OSM sonucunu kontrol edin.")
        val attemptTime = requireNotNull(draft.submittedAt)
        // begin() is durable and atomic. All subsequent outcomes belong to this exact attempt.
        try {
            val note = api.createNote(session.token, draft.latitude, draft.longitude, draft.text)
            if (!OsmSafetyRules.matchesIdentity(draft.attempt(), note)) {
                return finish(draft, ContributionStatus.UNKNOWN, null, UNKNOWN_MESSAGE)
            }
            return finish(draft, ContributionStatus.SENT, note, "Not OSM'ye gönderildi.")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { store.finish(id, attemptTime, ContributionStatus.UNKNOWN, null, null, UNKNOWN_MESSAGE) }
            }
            throw cancelled
        } catch (error: Exception) {
            val rejected = error is OsmApiException && error.statusCode in DEFINITE_REJECTIONS
            if (error is OsmApiException && error.statusCode == 401) onUnauthorized(session)
            return finish(draft, if (rejected) ContributionStatus.FAILED else ContributionStatus.UNKNOWN, null,
                if (rejected) "OSM isteği reddetti (${(error as OsmApiException).statusCode}). Düzeltip yeniden gönderebilirsiniz." else UNKNOWN_MESSAGE)
        }
    }

    suspend fun reconcile(id: String): OsmContributionResult = readOperation {
        val draft = store.get(id) ?: return@readOperation failure("Taslak bulunamadı.")
        if (draft.status != ContributionStatus.UNKNOWN || draft.submittedAt == null || draft.submittedBy == null) {
            return@readOperation failure("Yalnızca sonucu belirsiz gönderimler kontrol edilebilir.")
        }
        if (account()?.user?.id != draft.submittedBy) {
            return@readOperation failure("Bu gönderimi yapan OSM hesabını bağlayın.")
        }
        val match = OsmSafetyRules.reconcile(draft.attempt(), api.nearbyNotes(draft.latitude, draft.longitude))
            ?: return@readOperation failure("Tek bir kesin eşleşme bulunamadı. Sonuç belirsiz; tekrar gönderim yapılmadı.")
        if (!store.reconcile(id, draft.submittedAt, match.id, match.status)) {
            return@readOperation failure("Taslak durumu değişti. Listeyi yeniden kontrol edin.")
        }
        OsmContributionResult(true, "OSM'deki not bulundu ve taslağa bağlandı.")
    }

    suspend fun refreshRemoteStatus(id: String): OsmContributionResult = readOperation {
        val draft = store.get(id) ?: return@readOperation failure("Taslak bulunamadı.")
        val noteId = draft.remoteNoteId
        if (draft.status != ContributionStatus.SENT || noteId == null) {
            return@readOperation failure("Gönderilmiş bir OSM notu seçin.")
        }
        val remoteStatus = try {
            api.readNote(noteId).also { require(it.id == noteId) }.status
        } catch (error: OsmApiException) {
            if (error.statusCode == 410) "hidden" else throw error
        }
        if (!store.refresh(id, noteId, remoteStatus)) return@readOperation failure("Taslak durumu değişti. Listeyi yeniden kontrol edin.")
        OsmContributionResult(true, "OSM notunun güncel durumu alındı.")
    }

    private suspend fun finish(draft: ContributionDraft, status: ContributionStatus, note: OsmRemoteNote?, message: String): OsmContributionResult {
        val saved = try {
            store.finish(draft.id, requireNotNull(draft.submittedAt), status, note?.id, note?.status,
                message.takeUnless { status == ContributionStatus.SENT })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        return if (saved) OsmContributionResult(status == ContributionStatus.SENT, message)
            else failure("Gönderim sonucu kaydedilemedi. Yeniden göndermeyin; uygulamayı açıp OSM sonucunu kontrol edin.")
    }

    private suspend fun readOperation(action: suspend () -> OsmContributionResult): OsmContributionResult = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure("OSM sonucu alınamadı. Bağlantınızı kontrol edip tekrar kontrol edebilirsiniz.") }

    private fun ContributionDraft.attempt() = NoteAttempt(requireNotNull(submittedBy), latitude, longitude, text, requireNotNull(submittedAt))
    private fun failure(message: String) = OsmContributionResult(false, message)

    companion object {
        private val DEFINITE_REJECTIONS = setOf(400, 401, 403, 404, 405, 406, 413, 415, 422, 429)
        private const val UNKNOWN_MESSAGE = "Gönderimin sonucu belirsiz. Tekrar göndermeyin; OSM sonucunu kontrol edin."
    }
}
