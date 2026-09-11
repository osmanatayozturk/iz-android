package com.atay.iz.integration.osm

import com.atay.iz.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal interface OsmMapEditStore {
    suspend fun get(id: String): MapEditDraft?
    suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft?
    suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft): Boolean
}

/** One reviewed POI per changeset; no queued publication or automatic replay of uncertain writes. */
internal class OsmMapEditPublisher(
    private val store: OsmMapEditStore,
    private val account: () -> OsmCredentials?,
    private val api: OsmMapEditGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUnauthorized: (OsmCredentials) -> Unit = {},
) {
    suspend fun send(id: String, reviewedCandidates: Set<OsmRef> = emptySet()): OsmContributionResult {
        val session = account()?.takeIf { "write_api" in it.scopes }
            ?: return failure("Mekân yayımlamak için OSM harita düzenleme iznini etkinleştir.")
        val draft = store.get(id) ?: return failure("Taslak bulunamadı.")
        if (draft.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED))
            return failure("Bu kayıt yeniden gönderilemez. Önce OSM sonucunu kontrol et.")
        try {
            validateMapEditForPublish(draft)
            if (api.nearby(draft).map { it.ref }.toSet() != reviewedCandidates)
                return failure("Yakındaki yerler değişti. Gönderimi yeniden önizleyip mevcut mekânları kontrol et.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return failure("Yakındaki mekânlar doğrulanamadı. Taslağın saklı; bağlantını kontrol edip yeniden önizle.") }
        if (account()?.token != session.token) return failure("OSM oturumu değişti. Hesabı kontrol edip yeniden önizle.")
        var current = store.begin(id, session.user.id, clock()) ?: return failure("Taslak zaten gönderiliyor veya sonucu kontrol edilmeli.")
        try {
            // The claim must be the same form whose duplicate list was reviewed before the suspending query.
            check(current.copy(status = draft.status, stage = draft.stage, submittedAt = draft.submittedAt,
                submittedBy = draft.submittedBy, changesetId = draft.changesetId, remoteNodeId = draft.remoteNodeId,
                remoteNodeVersion = draft.remoteNodeVersion, changesetClosed = draft.changesetClosed, error = draft.error) == draft) {
                "Draft changed during duplicate check"
            }
            val changesetId = api.createChangeset(session.token, current)
            val prepared = current.copy(changesetId = changesetId, stage = MapEditStage.UPLOAD_NODE)
            check(store.compareAndSet(current, prepared)) { "Changeset could not be persisted" }
            current = prepared
            // A disconnect/account change after creation must not publish with stale authorization.
            check(account()?.token == session.token) { "OSM account changed" }
            val node = api.uploadNode(session.token, current, changesetId)
            require(node.id > 0 && node.version == 1L)
            val published = current.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
                remoteNodeId = node.id, remoteNodeVersion = node.version, error = null)
            check(store.compareAndSet(current, published)) { "Published result could not be persisted" }
            current = published
            closePublished(current, session)
            return success("Mekân OSM haritasında yayımlandı.")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markInterrupted(current) }
            throw cancelled
        } catch (error: Exception) {
            if (current.status == MapEditStatus.SENT) return success("Mekân yayımlandı. Gönderimin son durumu daha sonra kontrol edilebilir.")
            if (error is OsmApiException && error.statusCode == 401) onUnauthorized(session)
            val definite = error is OsmApiException && error.statusCode in setOf(400, 401, 403, 404, 405, 409, 412, 413, 429)
            val message = if (definite) "OSM gönderimi reddetti (${(error as OsmApiException).statusCode}). Taslağı düzenleyip yeniden önizleyebilirsin."
                else UNKNOWN_MESSAGE
            runCatching { store.compareAndSet(current, current.copy(status = if (definite) MapEditStatus.FAILED else MapEditStatus.UNKNOWN, error = message)) }
            return failure(message)
        }
    }

    /** This operation only reads. It never closes a changeset or uploads another node. */
    suspend fun reconcile(id: String): OsmContributionResult {
        val draft = store.get(id) ?: return failure("Taslak bulunamadı.")
        val session = account() ?: return failure("Bu gönderimi yapan OSM hesabını bağla.")
        if (draft.submittedBy != session.user.id) return failure("Bu gönderimi yapan OSM hesabını bağla.")
        if (draft.status !in setOf(MapEditStatus.UNKNOWN, MapEditStatus.SENT)) return failure("Kontrol edilecek belirsiz veya yayımlanmış gönderim yok.")
        val changeset = draft.changesetId
        if (changeset == null) {
            // Upload is impossible until the changeset ID has been committed to this row.
            // A lost create response can leave an empty remote changeset, never a POI.
            if (draft.status == MapEditStatus.UNKNOWN && draft.stage == MapEditStage.CREATE_CHANGESET) {
                val retryable = draft.copy(status = MapEditStatus.FAILED,
                    error = "Mekân yüklemesi başlamadı. Taslağı yeniden önizleyip gönderebilirsin.")
                if (store.compareAndSet(draft, retryable)) return failure(requireNotNull(retryable.error))
            }
            return failure("OSM gönderim kimliği alınamadı; gönderim kaydını kontrol et.")
        }
        try {
            val info = api.readChangeset(changeset)
            if (info.userId != draft.submittedBy || info.id != changeset) return failure("OSM gönderimini yapan hesap eşleşmiyor.")
            val nodes = api.downloadChangeset(changeset)
            val matches = nodes.filter { node -> node.id > 0 && node.version == 1L && node.changesetId == changeset &&
                node.userId == draft.submittedBy && abs(node.latitude - draft.latitude) <= 0.0000001 &&
                abs(node.longitude - draft.longitude) <= 0.0000001 && node.tags == draft.publicTags() }
                .distinctBy { it.id }
            val match = matches.singleOrNull()
            if (match == null) {
                // Only a closed, completely empty changeset proves no late upload can create this node.
                if (!info.open && nodes.isEmpty() && draft.status == MapEditStatus.UNKNOWN) {
                    val rejected = draft.copy(status = MapEditStatus.FAILED, changesetClosed = true,
                        error = "Bu OSM gönderimi boş ve tamamlanmış. Yeni önizlemeden sonra tekrar deneyebilirsin.")
                    if (store.compareAndSet(draft, rejected)) return failure(requireNotNull(rejected.error))
                }
                return failure("Tek bir kesin eşleşme bulunamadı. Sonuç belirsiz; yeniden gönderim yapılmadı.")
            }
            if (draft.remoteNodeId != null && draft.remoteNodeId != match.id) return failure("OSM mekân kimliği eşleşmiyor.")
            val updated = draft.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
                remoteNodeId = match.id, remoteNodeVersion = match.version, changesetClosed = !info.open, error = null)
            return if (store.compareAndSet(draft, updated)) success("Mekân OSM'de bulundu ve kayda bağlandı.") else failure("Kayıt değişti. Listeyi yenile.")
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return failure("OSM sonucu şu an kontrol edilemiyor. Yeniden gönderim yapılmadı.") }
    }

    /** Explicit user action for an already published node; never re-uploads the node. */
    suspend fun close(id: String): OsmContributionResult {
        val value = store.get(id) ?: return failure("Kayıt bulunamadı.")
        val session = account()?.takeIf { "write_api" in it.scopes && it.user.id == value.submittedBy }
            ?: return failure("Gönderimi yapan hesabın harita düzenleme izni gerekli.")
        if (value.status != MapEditStatus.SENT || value.remoteNodeId == null) return failure("Önce yayımlama sonucunu kontrol et.")
        return if (closePublished(value, session)) success("OSM gönderimi tamamlandı.") else failure("Mekân yayımlanmış durumda. Gönderimin son adımı şu an tamamlanamadı.")
    }

    private suspend fun closePublished(value: MapEditDraft, session: OsmCredentials): Boolean {
        if (value.changesetClosed) return true
        return try {
            api.closeChangeset(session.token, requireNotNull(value.changesetId))
            store.compareAndSet(value, value.copy(changesetClosed = true, error = null))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            runCatching { store.compareAndSet(value, value.copy(error = "Mekân yayımlandı; gönderimin son adımı bekliyor.")) }
            false
        }
    }
    private suspend fun markInterrupted(value: MapEditDraft) {
        if (value.status != MapEditStatus.SENT) runCatching { store.compareAndSet(value, value.copy(status = MapEditStatus.UNKNOWN, error = UNKNOWN_MESSAGE)) }
    }
    private fun failure(message: String) = OsmContributionResult(false, message)
    private fun success(message: String) = OsmContributionResult(true, message)
    companion object { private const val UNKNOWN_MESSAGE = "Gönderimin sonucu belirsiz. Tekrar gönderme; OSM sonucunu kontrol et." }
}

class OsmMapEditController(repository: DiaryRepository, auth: OsmAuthManager, overpassEndpoint: () -> String) {
    private val api = OsmMapEditClient(overpassEndpoint = overpassEndpoint)
    private val publisher = OsmMapEditPublisher(object : OsmMapEditStore {
        override suspend fun get(id: String) = repository.getMapEdit(id)
        override suspend fun begin(id: String, userId: Long, time: Long) = repository.beginMapEditSend(id, userId, time)
        override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft) = repository.compareAndSetMapEdit(expected, updated)
    }, auth::currentCredentials, api, onUnauthorized = auth::rejectExpiredSession)
    suspend fun nearby(draft: MapEditDraft): List<OsmDuplicateCandidate> = api.nearby(draft)
    suspend fun send(id: String, reviewedCandidates: Set<OsmRef>) = publisher.send(id, reviewedCandidates)
    suspend fun reconcile(id: String) = publisher.reconcile(id)
    suspend fun close(id: String) = publisher.close(id)
}
