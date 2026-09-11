package org.iz.navigation.osmcommunity

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmCommunityRepositoryTest {
    private lateinit var db: OsmCommunityDatabase
    private lateinit var repository: OsmCommunityRepository
    private lateinit var scope: CoroutineScope
    private val sessions = FakeCommunitySessions()
    private val gateway = FakeCommunityGateway()
    private val sink = RecordingCommunitySink()
    private val scheduler = RecordingCommunityScheduler()
    private var now = 1_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OsmCommunityDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = newRepository()
        repository.initialize()
    }
    private fun newRepository() = OsmCommunityRepository(context, sessions, gateway,
        database = db, notifications = sink, scheduler = scheduler, clock = { now }, scope = scope)
    @After fun tearDown() { scope.cancel(); db.close() }

    @Test fun logoutImmediatelyHidesPrivateStateAndLateFetchCannotRestoreIt() = runBlocking {
        repository.saveDraft(draft())
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<OsmMessageDetail>()
        gateway.detailResponse = { started.complete(Unit); answer.await() }
        val pending = async { runCatching { repository.message(31) } }
        started.await()
        sessions.sessions.value = null
        assertNull(repository.state.value.accountId)
        assertTrue(repository.state.value.drafts.isEmpty())
        answer.complete(detail(31))
        assertTrue(pending.await().isFailure)
        sessions.sessions.value = session(2, 2)
        repository.saveDraft(draft(accountId = 2, id = "second"))
        assertEquals(listOf("second"), repository.state.value.drafts.map { it.id })
        assertTrue(db.communityDao().messages(1, "INBOX").isEmpty())
        assertTrue(db.communityDao().drafts(1).isEmpty())
        assertTrue(sink.cancelCount > 0)
    }
    @Test fun sameAccountScopeUpgradePreservesDraftButOldGenerationCannotCommit() = runBlocking {
        repository.saveDraft(draft())
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<OsmMessageDetail>()
        gateway.detailResponse = { started.complete(Unit); answer.await() }
        val pending = async { runCatching { repository.message(31) } }
        started.await()
        sessions.sessions.value = session(1, 2)
        answer.complete(detail(31))
        assertTrue(pending.await().isFailure)
        repository.saveDraft(draft(title = "updated"))
        assertEquals("updated", repository.state.value.drafts.single().title)
        assertNull(db.communityDao().detail(1, 31))
    }
    @Test fun lateUnauthorizedResponseRejectsOnlyItsCapturedSession() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<OsmMessageDetail>()
        gateway.detailResponse = { started.complete(Unit); answer.await() }
        val pending = async { runCatching { repository.message(31) } }
        started.await()
        sessions.sessions.value = session(2, 2)
        answer.completeExceptionally(CommunityHttpException(401))
        pending.await()
        assertEquals(2L, sessions.sessions.value?.userId)
        assertEquals(1L, sessions.rejected.single().userId)
    }
    @Test fun duplicateSubmitClaimsOnceAndEditorCannotOverwriteInFlightDraft() = runBlocking {
        repository.saveDraft(draft())
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<OsmMessageDetail>()
        gateway.sendResponse = { started.complete(Unit); answer.await() }
        val pending = async { repository.sendDraft("draft") }
        started.await()
        assertEquals(CommunityDraftStatus.SENDING, repository.state.value.drafts.single().status)
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertTrue(runCatching { repository.saveDraft(draft(body = "overwrite")) }.isFailure)
        answer.complete(detail(40, fromId = 1, toId = 2))
        pending.await()
        assertEquals(1, gateway.sendCount)
        assertTrue(repository.state.value.drafts.isEmpty())
        assertEquals(40L, repository.state.value.outbox.single().id)
    }
    @Test fun uncertainSendIsDurableUnknownAndCannotBeReplayed() = runBlocking {
        repository.saveDraft(draft())
        gateway.sendResponse = { throw IOException("connection lost") }
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertEquals(CommunityDraftStatus.UNKNOWN, db.communityDao().drafts(1).single().draft.status)
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertTrue(runCatching { repository.saveDraft(draft()) }.isFailure)
        assertEquals(1, gateway.sendCount)
    }
    @Test fun cancellationAfterClaimPreservesUnknownAndNeverRetries() = runBlocking {
        repository.saveDraft(draft())
        val started = CompletableDeferred<Unit>()
        gateway.sendResponse = { started.complete(Unit); CompletableDeferred<OsmMessageDetail>().await() }
        val pending = async { repository.sendDraft("draft") }
        started.await()
        pending.cancelAndJoin()
        assertEquals(CommunityDraftStatus.UNKNOWN, db.communityDao().drafts(1).single().draft.status)
        assertEquals(1, gateway.sendCount)
    }
    @Test fun restartRecoversInterruptedClaimWithoutSendingAgain() = runBlocking {
        repository.saveDraft(draft())
        db.communityDao().putDraft(CommunityDraftEntity(draft(status = CommunityDraftStatus.SENDING)))
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = newRepository()
        repository.initialize()
        repository.setNotificationsEnabled(false)
        assertEquals(CommunityDraftStatus.UNKNOWN, repository.state.value.drafts.single().status)
        assertEquals(0, gateway.sendCount)
    }
    @Test fun explicitRejectionIsEditableFailedDraft() = runBlocking {
        repository.saveDraft(draft())
        gateway.sendResponse = { throw CommunityHttpException(422) }
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertEquals(CommunityDraftStatus.FAILED, repository.state.value.drafts.single().status)
        repository.saveDraft(draft(title = "fixed"))
        assertEquals(CommunityDraftStatus.DRAFT, repository.state.value.drafts.single().status)
    }
    @Test fun oldOrForgedAccountDraftCannotBeSaved() = runBlocking {
        assertTrue(runCatching { repository.saveDraft(draft(accountId = 2)) }.isFailure)
        assertTrue(db.communityDao().drafts(1).isEmpty())
        assertTrue(db.communityDao().drafts(2).isEmpty())
    }
    @Test fun firstSyncEstablishesBaselineThenAllNewPagesNotifyOnceAcrossRestart() = runBlocking {
        gateway.pages = { _, _ -> CommunityPage(listOf(summary(10)), null) }
        repository.refreshMailbox(CommunityMailbox.INBOX)
        assertTrue(sink.messages.isEmpty())
        gateway.pages = { _, cursor -> when(cursor) {
            null -> CommunityPage((150L downTo 51L).map { summary(it) }, 50)
            50L -> CommunityPage((50L downTo 1L).map { summary(it) }, null)
            else -> error("unexpected cursor")
        } }
        repository.refreshMailbox(CommunityMailbox.INBOX)
        assertEquals(140, sink.messages.size)
        assertEquals(140, sink.messages.map { it.second }.toSet().size)
        assertEquals(listOf(null, null, 50L), gateway.cursors)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = newRepository()
        repository.initialize()
        repository.checkForNewMessages(1)
        assertEquals(140, sink.messages.size)
    }
    @Test fun readDeletedAndDisabledMessagesNeverNotifyAndLogoutRemovesBaseline() = runBlocking {
        gateway.pages = { _, _ -> CommunityPage(listOf(summary(10)), null) }
        repository.refreshMailbox(CommunityMailbox.INBOX)
        gateway.pages = { _, _ -> CommunityPage(listOf(summary(13).copy(deleted = true),
            summary(12).copy(read = true), summary(11)), null) }
        repository.refreshMailbox(CommunityMailbox.INBOX)
        assertEquals(listOf(1L to 11L), sink.messages)
        repository.setNotificationsEnabled(false)
        gateway.pages = { _, _ -> CommunityPage(listOf(summary(14)), null) }
        repository.refreshMailbox(CommunityMailbox.INBOX)
        assertEquals(1, sink.messages.size)
        assertFalse(scheduler.enabled)
        sessions.sessions.value = null
        sessions.sessions.value = session(1, 3)
        repository.refreshMailbox(CommunityMailbox.INBOX)
        assertEquals(1, sink.messages.size)
    }
    @Test fun selfSentInboxReadFlagDoesNotOverwriteOutboxFlag() = runBlocking {
        gateway.pages = { box, _ -> CommunityPage(listOf(summary(10, fromId = 1, toId = 1)
            .copy(read = box == CommunityMailbox.INBOX)), null) }
        repository.refreshMailbox(CommunityMailbox.INBOX)
        repository.refreshMailbox(CommunityMailbox.OUTBOX)
        assertTrue(repository.state.value.inbox.single().read)
        assertFalse(repository.state.value.outbox.single().read)
    }
    @Test fun deniedPermissionAndMissingConsumeScopeCancelSchedule() = runBlocking {
        repository.setNotificationsEnabled(true)
        assertTrue(scheduler.enabled)
        sink.allowed = false
        repository.refreshNotificationSchedule()
        assertFalse(scheduler.enabled)
        sink.allowed = true
        sessions.sessions.value = session(1, 2, setOf("read_prefs"))
        repository.refreshNotificationSchedule()
        assertFalse(scheduler.enabled)
        assertTrue(runCatching { repository.refreshMailbox(CommunityMailbox.INBOX) }.isFailure)
    }

    @Test fun successfulSendRetiresDraftIdAgainstLateEditorSaveAfterRestart() = runBlocking {
        repository.saveDraft(draft())
        repository.sendDraft("draft")
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = newRepository()
        repository.initialize()
        assertTrue(runCatching { repository.saveDraft(draft()) }.isFailure)
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertEquals(1, gateway.sendCount)
        assertTrue(db.communityDao().drafts(1).isEmpty())
    }

    @Test fun deletedDraftCannotBeResurrectedByDebouncedEditorSave() = runBlocking {
        repository.saveDraft(draft())
        repository.deleteDraft("draft")
        assertTrue(runCatching { repository.saveDraft(draft()) }.isFailure)
        assertTrue(db.communityDao().drafts(1).isEmpty())
    }

    @Test fun expiryDuringSendStillPersistsUnknownBeforeReturning() = runBlocking {
        sessions.sessions.value = CommunitySession("expiring", 1, "person1", setOf("consume_messages", "send_messages"), 2, 2_000)
        repository.saveDraft(draft())
        gateway.sendResponse = { now = 3_000; throw IOException("lost response") }
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertEquals(CommunityDraftStatus.UNKNOWN, db.communityDao().drafts(1).single().draft.status)
        assertFalse(repository.state.value.canSend)
    }

    @Test fun missingRemoteMessageEvictsBothSummaryAndPrivateDetail() = runBlocking {
        repository.message(31)
        assertNotNull(db.communityDao().detail(1, 31))
        gateway.detailResponse = { throw CommunityHttpException(404) }
        assertTrue(runCatching { repository.message(31) }.isFailure)
        assertNull(db.communityDao().detail(1, 31))
        assertTrue(repository.state.value.inbox.isEmpty())
    }

    @Test fun markReadUpdatesOnlyOwnedInboxAndDeletionRemovesCachedBody() = runBlocking {
        gateway.detailResponse = { detail(it, 1, 1) }
        repository.message(31)
        gateway.pages = { _, _ -> CommunityPage(listOf(summary(31, 1, 1)), null) }
        repository.refreshMailbox(CommunityMailbox.OUTBOX)
        repository.markRead(31, true)
        assertTrue(repository.state.value.inbox.single().read)
        assertFalse(repository.state.value.outbox.single().read)
        assertEquals(listOf(31L to true), gateway.readChanges)
        repository.deleteMessage(31)
        assertTrue(repository.state.value.inbox.isEmpty())
        assertTrue(repository.state.value.outbox.isEmpty())
        assertNull(db.communityDao().detail(1, 31))
    }

    @Test fun olderMailboxRequestCannotUndoFollowingDelete() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<CommunityPage>()
        val deleted = CompletableDeferred<Unit>()
        gateway.pages = { _, _ -> started.complete(Unit); answer.await() }
        gateway.deleteResponse = { deleted.complete(Unit) }
        val refreshing = async { repository.refreshMailbox(CommunityMailbox.INBOX) }
        started.await()
        val deleting = async(start = CoroutineStart.UNDISPATCHED) { repository.deleteMessage(31) }
        assertNull(withTimeoutOrNull(100) { deleted.await() })
        answer.complete(CommunityPage(listOf(summary(31)), null))
        refreshing.await()
        deleting.await()
        assertTrue(deleted.isCompleted)
        assertTrue(repository.state.value.inbox.isEmpty())
    }

    @Test fun sendRateLimitIsPersistedAndPreventsNewPostBeforeRetryAfter() = runBlocking {
        repository.saveDraft(draft())
        gateway.sendResponse = { throw CommunityHttpException(429, 120_000) }
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertEquals(CommunityDraftStatus.FAILED, repository.state.value.drafts.single().status)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = newRepository()
        repository.initialize()
        assertTrue(runCatching { repository.sendDraft("draft") }.isFailure)
        assertEquals(1, gateway.sendCount)
    }

    @Test fun finalDraftSaveSurvivesCancellationOfItsUiWaiter() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocker = async(Dispatchers.IO) { db.withTransaction { entered.complete(Unit); release.await() } }
        entered.await()
        val save = repository.persistDraft(draft())
        val uiWaiter = async { save.await() }
        uiWaiter.cancelAndJoin()
        release.complete(Unit)
        blocker.await()
        save.await()
        assertEquals("private body", db.communityDao().drafts(1).single().draft.body)
    }

    @Test fun hydrationIdentityPersistsAcrossRestartAndChangesAfterLogout() = runBlocking {
        repository.saveDraft(draft())
        assertTrue(repository.state.value.ready)
        val identity = repository.state.value.cacheIdentity
        assertNotNull(identity)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository = newRepository()
        repository.initialize()
        repository.setNotificationsEnabled(true)
        assertEquals(identity, repository.state.value.cacheIdentity)
        sessions.sessions.value = null
        assertFalse(repository.state.value.ready)
        assertNull(repository.state.value.cacheIdentity)
        sessions.sessions.value = session(1, 3)
        repository.setNotificationsEnabled(true)
        assertNotEquals(identity, repository.state.value.cacheIdentity)
    }
    private fun draft(accountId: Long = 1, id: String = "draft", title: String = "hello",
        body: String = "private body", status: CommunityDraftStatus = CommunityDraftStatus.DRAFT) =
        CommunityDraft(id, accountId, 2, "", title, body, status, 1, 1)
}

internal fun session(id: Long = 1, generation: Long = 1,
    scopes: Set<String> = setOf("consume_messages", "send_messages")) =
    CommunitySession("test-token-$id-$generation", id, "person$id", scopes, generation)
internal fun summary(id: Long, fromId: Long = 2, toId: Long = 1) =
    OsmMessageSummary(id, fromId, "person$fromId", toId, "person$toId", "subject$id", id * 10, false)
internal fun detail(id: Long, fromId: Long = 2, toId: Long = 1) =
    OsmMessageDetail(summary(id, fromId, toId), "private message body")
internal class FakeCommunitySessions : OsmCommunitySessionSource {
    override val sessions = MutableStateFlow<CommunitySession?>(session())
    val rejected = mutableListOf<CommunitySession>()
    override fun rejectSession(expected: CommunitySession) {
        rejected += expected
        if (sessions.value === expected) sessions.value = null
    }
}
internal class FakeCommunityGateway : OsmCommunityGateway {
    var detailResponse: suspend (Long) -> OsmMessageDetail = { detail(it) }
    var sendResponse: suspend () -> OsmMessageDetail = { detail(99, 1, 2) }
    var pages: suspend (CommunityMailbox, Long?) -> CommunityPage = { _, _ -> CommunityPage(emptyList(), null) }
    var sendCount = 0
    val readChanges = mutableListOf<Pair<Long, Boolean>>()
    var deleteResponse: suspend (Long) -> Unit = { }
    val cursors = mutableListOf<Long?>()
    override suspend fun profile(token: String?, userId: Long?) = OsmCommunityProfile(userId ?: 1, "person${userId ?: 1}")
    override suspend fun mailbox(token: String, box: CommunityMailbox, fromId: Long?): CommunityPage {
        cursors += fromId
        return pages(box, fromId)
    }
    override suspend fun message(token: String, id: Long) = detailResponse(id)
    override suspend fun send(token: String, recipientId: Long?, recipientName: String, title: String, body: String): OsmMessageDetail {
        sendCount++
        return sendResponse()
    }
    override suspend fun markRead(token: String, id: Long, read: Boolean) { readChanges += id to read }
    override suspend fun delete(token: String, id: Long) = deleteResponse(id)
}
internal class RecordingCommunitySink : CommunityNotificationSink {
    var allowed = true
    var cancelCount = 0
    val messages = mutableListOf<Pair<Long, Long>>()
    override fun permissionGranted() = allowed
    override fun show(accountId: Long, message: OsmMessageSummary) { messages += accountId to message.id }
    override fun cancelAll() { cancelCount++ }
    override fun cancel(accountId: Long, messageId: Long) = Unit
}
internal class RecordingCommunityScheduler : CommunityNotificationScheduler {
    var enabled = false
    override fun schedule(accountId: Long) { enabled = true }
    override fun cancel() { enabled = false }
}
