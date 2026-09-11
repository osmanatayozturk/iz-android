package com.atay.iz.osmcommunity

import android.content.Context
import androidx.room.withTransaction
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class CommunityState(
    val accountId: Long? = null,
    val profile: OsmCommunityProfile? = null,
    val inbox: List<OsmMessageSummary> = emptyList(),
    val outbox: List<OsmMessageSummary> = emptyList(),
    val contacts: List<OsmCommunityProfile> = emptyList(),
    val drafts: List<CommunityDraft> = emptyList(),
    val canConsume: Boolean = false,
    val canSend: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val nextInboxFromId: Long? = null,
    val nextOutboxFromId: Long? = null,
    val ready: Boolean = false,
    val cacheIdentity: String? = null,
)

internal class CommunitySessionChangedException : IllegalStateException("OSM hesabı değişti. Geçerli hesapla yeniden deneyin.")
internal class CommunityPermissionException(message: String) : IllegalStateException(message)

internal class OsmCommunityRepository(
    context: Context,
    private val sessionSource: OsmCommunitySessionSource,
    private val client: OsmCommunityGateway = OsmCommunityClient(),
    private val database: OsmCommunityDatabase = OsmCommunityDatabase.open(context),
    private val notifications: CommunityNotificationSink = CommunityNotifications(context),
    private val scheduler: CommunityNotificationScheduler = WorkCommunityNotificationScheduler(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val dao = database.communityDao()
    private val databaseMutex = Mutex()
    private val inboxMutex = Mutex()
    private val outboxMutex = Mutex()
    private val lifecycleLock = Any()
    private val initialized = AtomicBoolean(false)
    private var observedAccount = sessionSource.sessions.value?.userId
    @Volatile private var accountEpoch = 0L
    @Volatile private var wipeVersion = 0L
    private var appliedWipeVersion = 0L
    private var prepared = false
    @Volatile private var publishedEpoch = -1L
    private val published = MutableStateFlow(CommunityState())

    // Reading state must never expose the previous account even before Room cleanup completes.
    val state: StateFlow<CommunityState> = object : StateFlow<CommunityState> by published {
        override val value: CommunityState get() = visibleState()
    }

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        // The observer performs its privacy barrier without suspension on the emitting thread.
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            sessionSource.sessions.collect { session ->
                synchronized(lifecycleLock) {
                    if (observedAccount != session?.userId) {
                        observedAccount = session?.userId
                        accountEpoch++
                        wipeVersion++
                        scheduler.cancel()
                        notifications.cancelAll()
                    }
                    published.value = visibleState()
                }
                scope.launch {
                    try {
                        databaseMutex.withLock {
                            prepareLocked()
                            sessionSource.sessions.value?.let { publishLocked(stamp(it)) }
                        }
                        refreshNotificationSchedule()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The next explicit operation retries preparation and reports its error.
                    }
                }
            }
        }
    }

    private fun visibleState(): CommunityState {
        val session = sessionSource.sessions.value ?: return CommunityState()
        val cached = published.value
        val valid = session.expiresAt?.let { it > clock() } ?: true
        val base = if (cached.accountId == session.userId && publishedEpoch == accountEpoch) cached
            else CommunityState(accountId = session.userId)
        return base.copy(canConsume = valid && CONSUME in session.scopes,
            canSend = valid && SEND in session.scopes)
    }

    private data class SessionStamp(val session: CommunitySession, val epoch: Long)
    private fun stamp(session: CommunitySession) = SessionStamp(session, accountEpoch)
    private fun capture(requiredScope: String? = null): SessionStamp {
        initialize()
        val session = sessionSource.sessions.value
            ?: throw CommunityPermissionException("OSM hesabınıza giriş yapın.")
        val result = stamp(session)
        ensureCurrent(result)
        if (requiredScope != null && requiredScope !in session.scopes)
            throw CommunityPermissionException("Mesaj izinlerini vermek için OSM hesabınızı yeniden bağlayın.")
        return result
    }

    private fun ensureCurrent(expected: SessionStamp) {
        ensureIdentity(expected)
        if (expected.session.expiresAt?.let { it <= clock() } == true)
            throw CommunityPermissionException("OSM oturumunuzun süresi doldu. Yeniden giriş yapın.")
    }

    private fun ensureIdentity(expected: SessionStamp) {
        val current = sessionSource.sessions.value
        if (current == null || current.userId != expected.session.userId ||
            current.generation != expected.session.generation || current.token != expected.session.token ||
            current.scopes != expected.session.scopes || current.expiresAt != expected.session.expiresAt ||
            accountEpoch != expected.epoch) throw CommunitySessionChangedException()
    }

    private suspend fun clearLocked() {
        dao.clearMessages()
        dao.clearDetails()
        dao.clearProfiles()
        dao.clearDrafts()
        dao.clearAccounts()
        dao.clearNotifications()
        dao.clearRetiredDrafts()
    }

    private suspend fun prepareLocked() {
        val targetWipe = wipeVersion
        val accountId = sessionSource.sessions.value?.userId
        database.withTransaction {
            val accounts = dao.accounts()
            if (appliedWipeVersion != targetWipe || accountId == null || accounts.any { it.accountId != accountId }) {
                clearLocked()
            }
            if (!prepared) dao.recoverInterrupted(UNKNOWN_MESSAGE)
            if (accountId != null && dao.account(accountId) == null) dao.putAccount(CommunityAccountEntity(accountId))
        }
        prepared = true
        appliedWipeVersion = targetWipe
    }

    private suspend fun publishLocked(expected: SessionStamp, allowExpired: Boolean = false) {
        val accountId = expected.session.userId
        val account = dao.account(accountId) ?: return
        val profiles = dao.profiles(accountId).map { it.profile }
        val value = CommunityState(accountId, profiles.firstOrNull { it.id == accountId },
            dao.messages(accountId, CommunityMailbox.INBOX.name).map { it.summary },
            dao.messages(accountId, CommunityMailbox.OUTBOX.name).map { it.summary },
            profiles.filter { it.id != accountId }, dao.drafts(accountId).map { it.draft },
            CONSUME in expected.session.scopes && (expected.session.expiresAt?.let { it > clock() } ?: true),
            SEND in expected.session.scopes && (expected.session.expiresAt?.let { it > clock() } ?: true),
            account.notificationsEnabled, account.nextInboxFromId, account.nextOutboxFromId,
            ready = true, cacheIdentity = account.cacheIdentity)
        synchronized(lifecycleLock) {
            if (allowExpired) ensureIdentity(expected) else ensureCurrent(expected)
            publishedEpoch = expected.epoch
            published.value = value
        }
    }

    private suspend fun <T> access(expected: SessionStamp, block: suspend () -> T): T = databaseMutex.withLock {
        prepareLocked()
        ensureCurrent(expected)
        val result = database.withTransaction {
            ensureCurrent(expected)
            val value = block()
            // Throwing here rolls back a response that raced an auth change.
            ensureCurrent(expected)
            value
        }
        publishLocked(expected)
        result
    }

    suspend fun refresh() {
        val expected = capture()
        val profile = get(expected) { client.profile(expected.session.token, null) }
        access(expected) {
            if (profile.id != expected.session.userId) throw IOException("OSM hesap bilgisi doğrulanamadı.")
            dao.putProfile(CommunityProfileEntity(expected.session.userId, profile))
        }
        if (CONSUME in expected.session.scopes) {
            refreshMailbox(expected, CommunityMailbox.INBOX, false)
            refreshMailbox(expected, CommunityMailbox.OUTBOX, false)
        }
        refreshNotificationSchedule()
    }

    suspend fun refreshMailbox(mailbox: CommunityMailbox, loadMore: Boolean = false) {
        refreshMailbox(capture(CONSUME), mailbox, loadMore)
    }

    private suspend fun refreshMailbox(expected: SessionStamp, mailbox: CommunityMailbox, loadMore: Boolean) {
        val mailboxMutex = if (mailbox == CommunityMailbox.INBOX) inboxMutex else outboxMutex
        mailboxMutex.withLock {
            val account = access(expected) { requireNotNull(dao.account(expected.session.userId)) }
            val initialCursor = if (loadMore) {
                (if (mailbox == CommunityMailbox.INBOX) account.nextInboxFromId else account.nextOutboxFromId)
                    ?: return@withLock
            } else null
            val messages = LinkedHashMap<Long, OsmMessageSummary>()
            var cursor = initialCursor
            var nextCursor: Long?
            do {
                val page = get(expected) { client.mailbox(expected.session.token, mailbox, cursor) }
                page.messages.forEach { message ->
                    if (mailbox == CommunityMailbox.INBOX && message.toId != expected.session.userId ||
                        mailbox == CommunityMailbox.OUTBOX && message.fromId != expected.session.userId)
                        throw IOException("OSM posta kutusu bilgisi doğrulanamadı.")
                    messages[message.id] = message
                }
                nextCursor = page.nextFromId
                if (nextCursor != null && (nextCursor <= 0 || cursor != null && nextCursor >= cursor))
                    throw IOException("OSM mesaj sayfası yenilenemedi. Daha sonra tekrar deneyin.")
                val keepPaging = mailbox == CommunityMailbox.INBOX && !loadMore && account.baselineReady &&
                    nextCursor != null && page.messages.none { it.id <= account.inboxWatermark }
                cursor = nextCursor
            } while (keepPaging)
            val candidates = mutableListOf<OsmMessageSummary>()
            access(expected) {
                val current = requireNotNull(dao.account(expected.session.userId))
                for (message in messages.values) {
                    cacheSummary(expected.session.userId, mailbox, message)
                    if (mailbox == CommunityMailbox.INBOX && !loadMore && current.baselineReady &&
                        message.id > current.inboxWatermark && !message.read && !message.deleted &&
                        current.notificationsEnabled && notifications.permissionGranted() &&
                        dao.claimNotification(CommunityNotifiedEntity(expected.session.userId, message.id)) != -1L) {
                        candidates += message
                    }
                }
                val maxId = messages.keys.maxOrNull() ?: 0L
                dao.putAccount(if (mailbox == CommunityMailbox.INBOX) current.copy(
                    baselineReady = current.baselineReady || !loadMore,
                    inboxWatermark = if (loadMore) current.inboxWatermark else maxOf(current.inboxWatermark, maxId),
                    nextInboxFromId = nextCursor,
                ) else current.copy(nextOutboxFromId = nextCursor))
            }
            // Notification IDs and persisted claims both deduplicate. Serialize with auth invalidation.
            synchronized(lifecycleLock) {
                ensureCurrent(expected)
                if (state.value.notificationsEnabled && notifications.permissionGranted())
                    candidates.sortedBy { it.id }.forEach { notifications.show(expected.session.userId, it) }
            }
        }
    }

    private suspend fun cacheSummary(accountId: Long, mailbox: CommunityMailbox, message: OsmMessageSummary) {
        dao.putMessage(CommunityMessageEntity(accountId, mailbox.name, message))
        if (message.deleted) {
            // A deleted mailbox row is a tombstone, never a reason to revive cached content.
            dao.removeDetail(accountId, message.id)
            notifications.cancel(accountId, message.id)
        }
        listOf(message.fromId to message.fromName, message.toId to message.toName).forEach { (id, name) ->
            if (dao.profile(accountId, id) == null) dao.putProfile(CommunityProfileEntity(accountId, OsmCommunityProfile(id, name)))
        }
    }

    suspend fun message(id: Long): OsmMessageDetail {
        require(id > 0) { "Geçerli bir mesaj seçin." }
        val expected = capture(CONSUME)
        return bothMailboxes { message(expected, id) }
    }

    private suspend fun message(expected: SessionStamp, id: Long): OsmMessageDetail {
        val cached = access(expected) { dao.detail(expected.session.userId, id) }
        val result = try { get(expected) { client.message(expected.session.token, id) } }
        catch (failure: IOException) {
            ensureCurrent(expected)
            if (failure is CommunityHttpException && failure.statusCode == 404) access(expected) {
                dao.removeMessage(expected.session.userId, id)
                dao.removeDetail(expected.session.userId, id)
                notifications.cancel(expected.session.userId, id)
            }
            if (failure is CommunityHttpException && failure.statusCode in 400..499 || cached == null) throw failure
            return cached.model()
        }
        access(expected) {
            if (result.summary.id != id || (result.summary.toId != expected.session.userId &&
                result.summary.fromId != expected.session.userId)) throw IOException("OSM mesaj bilgisi doğrulanamadı.")
            dao.putDetail(CommunityDetailEntity(expected.session.userId, result.summary, result.body))
            // Detail flags describe recipient state; never overwrite an independent outbox flag.
            if (result.summary.toId == expected.session.userId)
                cacheSummary(expected.session.userId, CommunityMailbox.INBOX, result.summary)
            if (result.summary.fromId == expected.session.userId &&
                dao.message(expected.session.userId, CommunityMailbox.OUTBOX.name, id) == null)
                cacheSummary(expected.session.userId, CommunityMailbox.OUTBOX, result.summary.copy(read = true))
        }
        return result
    }

    suspend fun profile(userId: Long): OsmCommunityProfile {
        require(userId > 0) { "Geçerli bir OSM kullanıcısı seçin." }
        val expected = capture()
        val cached = access(expected) { dao.profile(expected.session.userId, userId) }
        val result = try { get(expected) { client.profile(expected.session.token, userId) } }
        catch (failure: IOException) {
            ensureCurrent(expected)
            if (failure is CommunityHttpException && failure.statusCode == 404) access(expected) {
                dao.removeProfile(expected.session.userId, userId)
            }
            if (failure is CommunityHttpException && failure.statusCode in 400..499 || cached == null) throw failure
            return cached.profile
        }
        access(expected) {
            if (result.id != userId) throw IOException("OSM profil bilgisi doğrulanamadı.")
            dao.putProfile(CommunityProfileEntity(expected.session.userId, result))
        }
        return result
    }

    // Final lifecycle saves belong to the application, so clearing a ViewModel cannot drop them.
    fun persistDraft(draft: CommunityDraft): Deferred<Unit> = scope.async(start = CoroutineStart.UNDISPATCHED) {
        saveDraft(draft)
    }

    suspend fun saveDraft(draft: CommunityDraft) {
        val expected = capture()
        require(draft.accountId == expected.session.userId) { "Taslak başka bir OSM hesabına ait." }
        require(draft.id.isNotBlank()) { "Taslak kimliği eksik." }
        access(expected) {
            if (dao.isDraftRetired(draft.accountId, draft.id))
                throw IllegalStateException("Bu taslak tamamlandı veya silindi. Yeni bir taslak oluşturun.")
            val old = dao.draft(draft.accountId, draft.id)?.draft
            if (old?.status == CommunityDraftStatus.SENDING || old?.status == CommunityDraftStatus.UNKNOWN)
                throw IllegalStateException(UNKNOWN_MESSAGE)
            require(draft.status == CommunityDraftStatus.DRAFT || draft.status == CommunityDraftStatus.FAILED) {
                "Bu taslak doğrudan düzenlenemez. Yeni bir taslak oluşturun."
            }
            dao.putDraft(CommunityDraftEntity(draft.copy(status = CommunityDraftStatus.DRAFT,
                createdAt = old?.createdAt ?: draft.createdAt, updatedAt = clock(), error = null)))
        }
    }

    suspend fun deleteDraft(id: String) {
        val expected = capture()
        access(expected) {
            if (dao.draft(expected.session.userId, id)?.draft?.status == CommunityDraftStatus.SENDING)
                throw IllegalStateException("Gönderim tamamlanana kadar taslak silinemez.")
            dao.removeDraft(expected.session.userId, id)
            dao.retireDraft(CommunityRetiredDraftEntity(expected.session.userId, id))
        }
    }

    suspend fun sendDraft(id: String): OsmMessageDetail {
        val expected = capture(SEND)
        enforceCooldown(expected)
        var claimed: CommunityDraft? = null
        try {
            // Persist before any POST; cancellation anywhere after this point leaves UNKNOWN.
            withContext(NonCancellable) {
                access(expected) {
                    val draft = dao.draft(expected.session.userId, id)?.draft
                        ?: throw IllegalStateException("Taslak bulunamadı. Mesajları yenileyin.")
                    if (draft.status != CommunityDraftStatus.DRAFT && draft.status != CommunityDraftStatus.FAILED)
                        throw IllegalStateException(UNKNOWN_MESSAGE)
                    require(draft.title.isNotBlank() && CommunityText.titleLength(draft.title) <= 255) { "Konu 1–255 karakter olmalı." }
                    require(draft.body.isNotBlank()) { "Mesaj metnini yazın." }
                    require(draft.recipientId == null || draft.recipientId > 0) { "Geçerli bir OSM alıcısı seçin." }
                    require(draft.recipientId != null || draft.recipientName.isNotBlank()) { "Alıcı seçin veya OSM kullanıcı adını yazın." }
                    val sending = draft.copy(status = CommunityDraftStatus.SENDING, updatedAt = clock(), error = null)
                    dao.putDraft(CommunityDraftEntity(sending))
                    claimed = sending
                }
            }
            currentCoroutineContext().ensureActive()
            ensureCurrent(expected)
            val draft = requireNotNull(claimed)
            val sent = mutateRemote(expected) { client.send(expected.session.token, draft.recipientId,
                if (draft.recipientId == null) draft.recipientName.trim() else "", draft.title, draft.body) }
            access(expected) {
                if (sent.summary.fromId != expected.session.userId) throw IOException("OSM gönderim sonucu doğrulanamadı.")
                dao.removeDraft(expected.session.userId, id)
                dao.retireDraft(CommunityRetiredDraftEntity(expected.session.userId, id))
                cacheSummary(expected.session.userId, CommunityMailbox.OUTBOX, sent.summary)
                dao.putDetail(CommunityDetailEntity(expected.session.userId, sent.summary, draft.body))
            }
            return sent.copy(body = draft.body)
        } catch (failure: Exception) {
            if (claimed != null) withContext(NonCancellable) {
                try {
                    settleFailedSend(expected, id, if (failure is CommunityHttpException && failure.statusCode in 400..499)
                        CommunityDraftStatus.FAILED else CommunityDraftStatus.UNKNOWN)
                } catch (_: CommunitySessionChangedException) {
                    // The old account's cleanup owns this claim now; never restore it into a new cache.
                }
            }
            if (failure is CancellationException) throw failure
            if (claimed != null && !(failure is CommunityHttpException && failure.statusCode in 400..499))
                throw IOException(UNKNOWN_MESSAGE)
            throw failure
        }
    }

    private suspend fun settleFailedSend(expected: SessionStamp, id: String, status: CommunityDraftStatus) {
        databaseMutex.withLock {
            prepareLocked()
            val current = sessionSource.sessions.value
            if (current?.userId != expected.session.userId || accountEpoch != expected.epoch) return@withLock
            database.withTransaction {
                val old = dao.draft(current.userId, id)?.draft ?: return@withTransaction
                if (old.status == CommunityDraftStatus.SENDING) dao.putDraft(CommunityDraftEntity(old.copy(
                    status = status, updatedAt = clock(), error = if (status == CommunityDraftStatus.UNKNOWN)
                        UNKNOWN_MESSAGE else "OSM mesajı kabul etmedi. Bilgileri ve mesaj izinlerinizi kontrol edin.")))
                // This only downgrades a durable claim; expiry/scope refresh cannot leave it SENDING.
                if (sessionSource.sessions.value?.userId != expected.session.userId || accountEpoch != expected.epoch)
                    throw CommunitySessionChangedException()
            }
            sessionSource.sessions.value?.let { publishLocked(stamp(it), allowExpired = true) }
        }
    }

    suspend fun markRead(id: Long, read: Boolean) {
        require(id > 0) { "Geçerli bir mesaj seçin." }
        val expected = capture(CONSUME)
        bothMailboxes {
            val cached = access(expected) { dao.message(expected.session.userId, CommunityMailbox.INBOX.name, id)?.summary }
                ?: message(expected, id).summary
            require(cached.toId == expected.session.userId && !cached.deleted) { "Yalnızca gelen mesajlarınızın okunma durumu değiştirilebilir." }
            mutateRemote(expected) { client.markRead(expected.session.token, id, read) }
            access(expected) {
                dao.putMessage(CommunityMessageEntity(expected.session.userId, CommunityMailbox.INBOX.name, cached.copy(read = read)))
                dao.detail(expected.session.userId, id)?.let { dao.putDetail(it.copy(summary = it.summary.copy(read = read))) }
            }
            if (read) notifications.cancel(expected.session.userId, id)
        }
    }

    suspend fun deleteMessage(id: Long) {
        require(id > 0) { "Geçerli bir mesaj seçin." }
        val expected = capture(CONSUME)
        bothMailboxes {
            mutateRemote(expected) { client.delete(expected.session.token, id) }
            access(expected) {
                dao.removeMessage(expected.session.userId, id)
                dao.removeDetail(expected.session.userId, id)
            }
            notifications.cancel(expected.session.userId, id)
        }
    }

    private suspend fun <T> bothMailboxes(action: suspend () -> T): T =
        inboxMutex.withLock { outboxMutex.withLock { action() } }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        val expected = capture()
        access(expected) {
            dao.putAccount(requireNotNull(dao.account(expected.session.userId)).copy(notificationsEnabled = enabled))
        }
        if (!enabled) notifications.cancelAll()
        refreshNotificationSchedule()
    }

    fun refreshNotificationSchedule() {
        synchronized(lifecycleLock) {
            val session = sessionSource.sessions.value
            val canSchedule = session != null && CONSUME in session.scopes &&
                publishedEpoch == accountEpoch && published.value.accountId == session.userId &&
                (session.expiresAt?.let { it > clock() } ?: true) && state.value.notificationsEnabled && notifications.permissionGranted()
            if (canSchedule) scheduler.schedule(requireNotNull(session).userId) else scheduler.cancel()
        }
    }

    suspend fun checkForNewMessages(expectedAccountId: Long) {
        val expected = capture(CONSUME)
        if (expected.session.userId != expectedAccountId) throw CommunitySessionChangedException()
        val enabled = access(expected) { requireNotNull(dao.account(expectedAccountId)).notificationsEnabled }
        if (!enabled || !notifications.permissionGranted()) { refreshNotificationSchedule(); return }
        refreshMailbox(expected, CommunityMailbox.INBOX, false)
    }

    private suspend fun <T> mutateRemote(expected: SessionStamp, action: suspend () -> T): T {
        ensureCurrent(expected)
        enforceCooldown(expected)
        try { return action().also { ensureCurrent(expected) } }
        catch (failure: CommunityHttpException) {
            if (failure.statusCode == 401) sessionSource.rejectSession(expected.session)
            else if (failure.retryAfterMillis != null && (failure.statusCode == 429 || failure.statusCode == 503))
                rememberCooldown(expected, failure.retryAfterMillis)
            throw failure
        }
    }

    private suspend fun enforceCooldown(expected: SessionStamp) {
        val notBefore = access(expected) { dao.account(expected.session.userId)?.retryNotBefore ?: 0 }
        if (notBefore > clock()) throw CommunityHttpException(429, notBefore - clock())
    }

    private suspend fun rememberCooldown(expected: SessionStamp, retryAfterMillis: Long) {
        access(expected) {
            val current = requireNotNull(dao.account(expected.session.userId))
            val delayMillis = retryAfterMillis.coerceAtLeast(0)
            val until = if (delayMillis > Long.MAX_VALUE - clock()) Long.MAX_VALUE else clock() + delayMillis
            dao.putAccount(current.copy(retryNotBefore = maxOf(current.retryNotBefore, until)))
        }
    }

    private suspend fun <T> get(expected: SessionStamp, action: suspend () -> T): T {
        var attempt = 0
        while (true) {
            ensureCurrent(expected)
            try { return mutateRemote(expected, action) }
            catch (failure: IOException) {
                val http = failure as? CommunityHttpException
                val retryable = http == null || http.statusCode == 429 || http.statusCode >= 500
                if (!retryable) throw failure
                val retryAfter = http?.retryAfterMillis?.coerceAtLeast(0) ?: (1_000L shl attempt)
                if (attempt++ >= 2 || retryAfter > 10_000) throw failure
                delay(retryAfter)
            }
        }
    }

    private companion object {
        const val CONSUME = "consume_messages"
        const val SEND = "send_messages"
        const val UNKNOWN_MESSAGE = "Mesaj gönderilmiş olabilir. Yeniden göndermeden önce giden kutusunu kontrol edin."
    }
}
