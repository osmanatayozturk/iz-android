package org.iz.navigation.integration.osm

import org.iz.navigation.data.MapEditDraft
import org.iz.navigation.data.MapEditStage
import org.iz.navigation.data.MapEditStatus
import org.iz.navigation.data.OsmRef
import org.iz.navigation.data.validateMapEditForPublish
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class OsmMapCorrectionPublisher(
    private val store: OsmMapEditStore,
    private val account: () -> OsmCredentials?,
    private val api: OsmMapCorrectionGateway,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onUnauthorized: (OsmCredentials) -> Unit = {},
) {
    suspend fun readSupported(ref: OsmRef): OsmCorrectionElement = api.readElement(ref).also { it.editorTopology() }

    suspend fun preview(draft: MapEditDraft): OsmCorrectionReview {
        val session = account()?.takeIf { "write_api" in it.scopes }
            ?: throw IllegalStateException(ACCOUNT_CHANGED)
        val ref = requireNotNull(draft.correctionRef())
        val original = readSupported(ref)
        check(account() === session) { ACCOUNT_CHANGED }
        return original.review(draft, session)
    }

    suspend fun send(review: OsmCorrectionReview): OsmContributionResult {
        val draft = review.draft
        val session = review.credentials
        if (account() !== session || "write_api" !in session.scopes) return failure(ACCOUNT_CHANGED)
        if (draft.status !in setOf(MapEditStatus.DRAFT, MapEditStatus.FAILED) || store.get(draft.id) != draft)
            return failure(REVIEW_AGAIN)
        val fresh = try {
            validateMapEditForPublish(draft)
            readSupported(requireNotNull(draft.correctionRef()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OsmUnsupportedCorrection) {
            return failure(REVIEW_AGAIN)
        } catch (_: Exception) {
            return failure("OSM mekânı doğrulanamadı. Taslağın saklı. Bağlantını kontrol edip yeniden incele.")
        }
        if (account() !== session) return failure(ACCOUNT_CHANGED)
        if (fresh != review.original) return failure(REVIEW_AGAIN)
        // Rebuild from the current element and the persisted form, rather than trusting an upload body from the UI.
        val corrected = fresh.review(draft, session).corrected
        if (corrected != review.corrected) return failure(REVIEW_AGAIN)
        val previous = draft.submittedAt ?: 0
        require(previous < Long.MAX_VALUE)
        var current = draft.editableCopy().copy(
            status = MapEditStatus.SENDING,
            stage = MapEditStage.CREATE_CHANGESET,
            submittedAt = maxOf(clock(), previous + 1),
            submittedBy = session.user.id,
            reviewedVersion = fresh.version,
            reviewedFingerprint = corrected.fingerprint(),
            localAttemptId = UUID.randomUUID().toString(),
        )
        if (!store.compareAndSet(draft, current)) return failure(REVIEW_AGAIN)
        val canDispatch = { account() === session }
        try {
            if (!canDispatch()) return reject(current, ACCOUNT_CHANGED)
            val changeset = api.createChangeset(session.token, current, canDispatch)
            current = checkNotNull(store.recordLocalChangeset(current, changeset))
            if (!canDispatch()) return reject(current, ACCOUNT_CHANGED)
            val version = api.updateElement(session.token, corrected, changeset, canDispatch)
            require(version == fresh.version + 1)
            val applied = current.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET, appliedVersion = version)
            check(store.compareAndSet(current, applied))
            current = applied
            if (canDispatch()) store.closePublishedMapEdit(current, session, api, account)
            return OsmContributionResult(true, "Mekân düzeltmesi OSM'de yayımlandı.")
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (current.status != MapEditStatus.SENT) runCatching { markUnknown(current) }
            }
            throw cancelled
        } catch (error: Exception) {
            if (current.status == MapEditStatus.SENT) return OsmContributionResult(true, "Mekân düzeltmesi OSM'de yayımlandı.")
            if (error is OsmMapEditNotDispatched) return reject(current, ACCOUNT_CHANGED)
            if (error is OsmApiException && error.statusCode == 401) onUnauthorized(session)
            if (error is OsmApiException && error.statusCode in setOf(400, 401, 403, 404, 405, 409, 410, 412, 413, 429)) {
                return reject(current, if (error.statusCode == 409) REVIEW_AGAIN
                    else "OSM düzeltmeyi reddetti (${error.statusCode}). Taslağını yeniden inceleyebilirsin.")
            }
            runCatching { markUnknown(current) }
            return failure(UNKNOWN)
        }
    }

    suspend fun reconcile(id: String): OsmContributionResult {
        val draft = store.get(id) ?: return failure("Taslak bulunamadı.")
        val session = account()?.takeIf { it.user.id == draft.submittedBy }
            ?: return failure("Bu düzeltmeyi yapan OSM hesabını bağla.")
        if (draft.correctionRef() == null) return failure("OSM mekân kimliği bulunamadı.")
        if (draft.status !in setOf(MapEditStatus.UNKNOWN, MapEditStatus.SENT)) return failure("Kontrol edilecek düzeltme yok.")
        val changeset = draft.changesetId
        if (changeset == null) {
            // No modify can be dispatched until the changeset ID and UPDATE_ELEMENT stage are durable.
            if (draft.status == MapEditStatus.UNKNOWN && draft.stage == MapEditStage.CREATE_CHANGESET && account() === session)
                return reject(draft, "Mekân düzeltmesi OSM'ye gönderilmedi. Taslağı yeniden inceleyebilirsin.")
            return failure(UNKNOWN)
        }
        val expectedVersion = draft.reviewedVersion?.plus(1) ?: return failure(UNKNOWN)
        try {
            val info = readProof(draft, changeset, expectedVersion) ?: return failure(UNKNOWN)
            if (account() !== session) return failure(ACCOUNT_CHANGED)
            val applied = draft.copy(status = MapEditStatus.SENT, stage = MapEditStage.CLOSE_CHANGESET, appliedVersion = expectedVersion,
                changesetClosed = draft.changesetClosed || !info.open, error = null)
            if (!store.compareAndSet(draft, applied)) return failure("Kayıt değişti. Güncel durumu kontrol et.")
            return OsmContributionResult(true, "Mekân düzeltmesi OSM'de doğrulandı.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failure(UNKNOWN)
        }
    }

    /** Restored publication state is a lookup key. Only an exact remote modification authorizes closing it. */
    suspend fun close(draft: MapEditDraft): OsmContributionResult {
        val session = account()?.takeIf { "write_api" in it.scopes && it.user.id == draft.submittedBy }
            ?: return failure("Gönderimi yapan hesabın harita düzenleme izni gerekli.")
        val changeset = draft.changesetId ?: return failure(UNKNOWN)
        val expectedVersion = draft.reviewedVersion?.plus(1) ?: return failure(UNKNOWN)
        if (draft.status != MapEditStatus.SENT || draft.appliedVersion != expectedVersion) return failure(UNKNOWN)
        if (!draft.localChangesetCreated) return failure(LOCAL_CLOSE_REQUIRED)
        try {
            val info = readProof(draft, changeset, expectedVersion) ?: return failure(UNKNOWN)
            if (store.get(draft.id) != draft) return failure(ROW_CHANGED)
            if (account() !== session) return failure(ACCOUNT_CHANGED)
            if (info.open) {
                if (draft.changesetClosed) return failure(UNKNOWN)
                api.closeChangeset(session.token, changeset) {
                    store.canDispatchMapEditClose(draft, session, account)
                }
            }
            if (account() !== session) return failure(ACCOUNT_CHANGED)
            if (!store.compareAndSet(draft, draft.copy(changesetClosed = true, error = null))) return failure(ROW_CHANGED)
            return OsmContributionResult(true, "OSM gönderimi tamamlandı.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: OsmMapEditNotDispatched) {
            return failure(if (account() !== session) ACCOUNT_CHANGED else ROW_CHANGED)
        } catch (_: Exception) {
            return failure(UNKNOWN)
        }
    }

    private suspend fun readProof(draft: MapEditDraft, changeset: Long, expectedVersion: Long): OsmChangesetInfo? {
        val ref = draft.correctionRef() ?: return null
        val info = api.readChangeset(changeset)
        if (info.id != changeset || info.userId != draft.submittedBy) return null
        val match = api.downloadCorrection(changeset) ?: return null
        return info.takeIf {
            match.ref == ref && match.version == expectedVersion && match.changesetId == changeset &&
                match.userId == draft.submittedBy && match.fingerprint() == draft.reviewedFingerprint
        }
    }

    private suspend fun reject(current: MapEditDraft, message: String): OsmContributionResult {
        if (!store.compareAndSet(current, current.copy(status = MapEditStatus.FAILED, error = message))) return failure(UNKNOWN)
        return failure(message)
    }

    private suspend fun markUnknown(current: MapEditDraft) {
        store.compareAndSet(current, current.copy(status = MapEditStatus.UNKNOWN, error = UNKNOWN))
    }

    private fun failure(message: String) = OsmContributionResult(false, message)

    private companion object {
        const val ACCOUNT_CHANGED = "OSM oturumu değişti. Hesabını kontrol edip düzeltmeyi yeniden incele."
        const val REVIEW_AGAIN = "Taslak veya OSM mekânı değişti. Düzeltmeyi yeniden incele."
        const val ROW_CHANGED = "Kayıt değişti. Güncel durumu kontrol et."
        const val UNKNOWN = "OSM düzeltmesi kesin olarak doğrulanamadı. Sonucu daha sonra kontrol et."
    }
}
