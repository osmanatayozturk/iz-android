package org.iz.navigation.integration.osm

import org.iz.navigation.data.ContributionDraft
import org.iz.navigation.data.ContributionStatus
import org.iz.navigation.data.DiaryRepository

/** Actions are invoked only after the user previews and confirms the public contribution. */
class OsmContributionController(repository: DiaryRepository, auth: OsmAuthManager) {
    private val publisher = OsmNotePublisher(object : OsmContributionStore {
        override suspend fun get(id: String): ContributionDraft? = repository.getContribution(id)
        override suspend fun begin(id: String, userId: Long, time: Long): ContributionDraft? = repository.beginContributionSend(id, userId, time)
        override suspend fun finish(id: String, time: Long, status: ContributionStatus, noteId: Long?, remoteStatus: String?, error: String?): Boolean =
            repository.updateContributionResult(id, time, status, noteId, remoteStatus, error)
        override suspend fun reconcile(id: String, time: Long, noteId: Long, remoteStatus: String): Boolean =
            repository.reconcileContributionResult(id, time, ContributionStatus.UNKNOWN, noteId, remoteStatus)
        override suspend fun refresh(id: String, noteId: Long, remoteStatus: String): Boolean =
            repository.refreshContributionRemoteStatus(id, noteId, remoteStatus)
    }, auth::currentCredentials, OsmNotesClient(), onUnauthorized = auth::rejectExpiredSession)

    suspend fun send(id: String): OsmContributionResult = publisher.send(id)
    suspend fun reconcile(id: String): OsmContributionResult = publisher.reconcile(id)
    suspend fun refreshRemoteStatus(id: String): OsmContributionResult = publisher.refreshRemoteStatus(id)
}
