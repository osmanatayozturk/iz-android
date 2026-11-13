package org.iz.navigation.integration.osm

import org.iz.navigation.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToLong

internal interface OsmMapEditStore {
    suspend fun get(id: String): MapEditDraft?
    fun isCurrentForDispatch(expected: MapEditDraft): Boolean
    suspend fun begin(id: String, userId: Long, time: Long): MapEditDraft?
    suspend fun recordLocalChangeset(expected: MapEditDraft, changeset: Long): MapEditDraft?
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
    suspend fun send(id: String, reviewedDraft: MapEditDraft? = null): OsmContributionResult {
        val session = account()?.takeIf { "write_api" in it.scopes }
            ?: return failure("Mekân yayımlamak için OSM harita düzenleme iznini etkinleştir.")
        val draft = store.get(id) ?: return failure("Taslak bulunamadı.")
        if ((reviewedDraft != null && reviewedDraft != draft) || (draft.editorVersion == 2 && reviewedDraft == null))
            return failure("Taslak değişti. Yayımlanacak bilgileri yeniden incele.")
        if (draft.targetType != null) return failure("Düzeltmeyi güncel OSM bilgileriyle incele.")
        if (draft.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED))
            return failure("Bu kayıt yeniden gönderilemez. Önce OSM sonucunu kontrol et.")
        try {
            validateMapEditForPublish(draft)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return failure("Mekân bilgileri doğrulanamadı. Taslağın saklı; bilgileri kontrol edip yeniden önizle.") }
        if (account() !== session) return failure("OSM oturumu değişti. Hesabı kontrol edip yeniden önizle.")
        var current = store.begin(id, session.user.id, clock()) ?: return failure("Taslak zaten gönderiliyor veya sonucu kontrol edilmeli.")
        try {
            // The durable claim must still be the exact form validated above.
            check(current.copy(status = draft.status, stage = draft.stage, submittedAt = draft.submittedAt,
                submittedBy = draft.submittedBy, changesetId = draft.changesetId, remoteNodeId = draft.remoteNodeId,
                remoteNodeVersion = draft.remoteNodeVersion, changesetClosed = draft.changesetClosed, error = draft.error,
                localAttemptId = draft.localAttemptId, localChangesetCreated = draft.localChangesetCreated) == draft) {
                "Draft changed before publication"
            }
            val changesetId = api.createChangeset(session.token, current) {
                account() === session && store.isCurrentForDispatch(current)
            }
            current = checkNotNull(store.recordLocalChangeset(current, changesetId)) { "Changeset could not be persisted" }
            // A disconnect/account change after creation must not publish with stale authorization.
            check(account() === session) { "OSM account changed" }
            val node = api.uploadNode(session.token, current, changesetId) {
                account() === session && store.isCurrentForDispatch(current)
            }
            require(node.id > 0 && node.version == 1L)
            val published = current.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET,
                remoteNodeId = node.id, remoteNodeVersion = node.version, error = null)
            check(store.compareAndSet(current, published)) { "Published result could not be persisted" }
            current = published
            store.closePublishedMapEdit(current, session, api, account)
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
            val matches = nodes.filter { node -> node.matchesCreatedNode(draft, changeset) &&
                abs(node.latitude - draft.latitude) <= 0.0000001 && abs(node.longitude - draft.longitude) <= 0.0000001 }
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

    /** Recheck remote creation proof before acting on a persisted or restored publication. */
    suspend fun close(id: String): OsmContributionResult {
        val value = store.get(id) ?: return failure("Kayıt bulunamadı.")
        if (value.targetType != null) return failure("Düzeltmenin OSM sonucunu doğrula.")
        val session = account()?.takeIf { "write_api" in it.scopes && it.user.id == value.submittedBy }
            ?: return failure("Gönderimi yapan hesabın harita düzenleme izni gerekli.")
        if (value.status != MapEditStatus.SENT || value.remoteNodeId == null || value.remoteNodeVersion != 1L)
            return failure("Önce yayımlama sonucunu kontrol et.")
        if (!value.localChangesetCreated) return failure(LOCAL_CLOSE_REQUIRED)
        val changeset = value.changesetId ?: return failure(CLOSE_UNVERIFIED)
        try {
            val info = api.readChangeset(changeset)
            if (info.id != changeset || info.userId != session.user.id) return failure(CLOSE_UNVERIFIED)
            val node = api.downloadSingleCreatedNode(changeset) ?: return failure(CLOSE_UNVERIFIED)
            if (node.id != value.remoteNodeId || !node.matchesCreatedNode(value, changeset) ||
                node.latitude != osmStoredCoordinate(value.latitude) || node.longitude != osmStoredCoordinate(value.longitude))
                return failure(CLOSE_UNVERIFIED)
            if (store.get(value.id) != value || account() !== session) return failure(CLOSE_CHANGED)
            if (info.open) {
                if (value.changesetClosed) return failure(CLOSE_UNVERIFIED)
                api.closeChangeset(session.token, changeset) {
                    store.canDispatchMapEditClose(value, session, account)
                }
            }
            if (account() !== session || !store.compareAndSet(value, value.copy(changesetClosed = true, error = null)))
                return failure(CLOSE_CHANGED)
            return success("OSM gönderimi tamamlandı.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OsmMapEditNotDispatched) {
            return failure(CLOSE_CHANGED)
        } catch (_: Exception) {
            return failure(CLOSE_UNVERIFIED)
        }
    }

    private suspend fun markInterrupted(value: MapEditDraft) {
        if (value.status != MapEditStatus.SENT) runCatching { store.compareAndSet(value, value.copy(status = MapEditStatus.UNKNOWN, error = UNKNOWN_MESSAGE)) }
    }
    private fun failure(message: String) = OsmContributionResult(false, message)
    private fun success(message: String) = OsmContributionResult(true, message)
    companion object {
        private const val UNKNOWN_MESSAGE = "Gönderimin sonucu belirsiz. Tekrar gönderme; OSM sonucunu kontrol et."
        private const val CLOSE_UNVERIFIED = "OSM gönderimi kesin olarak doğrulanamadı. Sonucu daha sonra kontrol et."
        private const val CLOSE_CHANGED = "OSM oturumu veya gönderim kaydı değişti. Güncel durumu kontrol et."
    }
}

private fun OsmCreatedNode.matchesCreatedNode(draft: MapEditDraft, changeset: Long): Boolean =
    id > 0 && version == 1L && changesetId == changeset && userId == draft.submittedBy && tags == draft.publicTags()

/** OSM stores node coordinates as rounded signed integer units of 10⁻⁷ degrees. */
private fun osmStoredCoordinate(value: Double): Double {
    val scaled = value * 10_000_000.0
    val units = abs(scaled).roundToLong()
    return (if (scaled < 0) -units else units) / 10_000_000.0
}

internal const val LOCAL_CLOSE_REQUIRED = "Bu kurulumda oluşturulan OSM gönderimleri buradan kapatılabilir."

internal fun OsmMapEditStore.canDispatchMapEditClose(
    value: MapEditDraft, session: OsmCredentials, account: () -> OsmCredentials?,
): Boolean = value.localChangesetCreated && account() === session &&
    isCurrentForDispatch(value) && account() === session

internal suspend fun OsmMapEditStore.closePublishedMapEdit(
    value: MapEditDraft,
    session: OsmCredentials,
    api: OsmMapEditGateway,
    account: () -> OsmCredentials?,
): Boolean {
    if (!value.localChangesetCreated || get(value.id) != value || account() !== session) return false
    if (value.changesetClosed) return true
    return try {
        val changeset = requireNotNull(value.changesetId)
        api.closeChangeset(session.token, changeset) { canDispatchMapEditClose(value, session, account) }
        account() === session && compareAndSet(value, value.copy(changesetClosed = true, error = null))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: OsmMapEditNotDispatched) { false }
    catch (_: Exception) {
        runCatching { compareAndSet(value, value.copy(error = "Mekân yayımlandı; gönderimin son adımı bekliyor.")) }
        false
    }
}

class OsmMapEditController internal constructor(
    private val store: OsmMapEditStore,
    account: () -> OsmCredentials?,
    private val api: OsmMapCorrectionGateway,
    onUnauthorized: (OsmCredentials) -> Unit = {},
) {
    constructor(repository: DiaryRepository, auth: OsmAuthManager) : this(
        object : OsmMapEditStore {
            override suspend fun get(id: String) = repository.getMapEdit(id)
            override fun isCurrentForDispatch(expected: MapEditDraft) = repository.isMapEditCurrentForDispatch(expected)
            override suspend fun begin(id: String, userId: Long, time: Long) = repository.beginMapEditSend(id, userId, time)
            override suspend fun recordLocalChangeset(expected: MapEditDraft, changeset: Long) =
                repository.recordLocalMapEditChangeset(expected, changeset)
            override suspend fun compareAndSet(expected: MapEditDraft, updated: MapEditDraft) = repository.compareAndSetMapEdit(expected, updated)
        },
        auth::currentCredentials,
        OsmMapEditClient(),
        auth::rejectExpiredSession,
    )

    private val publisher = OsmMapEditPublisher(store, account, api, onUnauthorized = onUnauthorized)
    private val correction = OsmMapCorrectionPublisher(store, account, api, onUnauthorized = onUnauthorized)
    suspend fun send(draft: MapEditDraft) = publisher.send(draft.id, draft)
    internal suspend fun readCorrectionTarget(ref: OsmRef) = correction.readSupported(ref)
    internal suspend fun previewCorrection(draft: MapEditDraft) = correction.preview(draft)
    internal suspend fun sendCorrection(review: OsmCorrectionReview) = correction.send(review)
    suspend fun reconcile(id: String) =
        if (store.get(id)?.targetType != null) correction.reconcile(id) else publisher.reconcile(id)
    suspend fun close(id: String): OsmContributionResult {
        val draft = store.get(id) ?: return OsmContributionResult(false, "Kayıt bulunamadı.")
        return if (draft.targetType != null) correction.close(draft) else publisher.close(id)
    }
}
