package org.iz.navigation.integration.osm

import android.app.Activity
import android.content.Context
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.iz.navigation.BuildConfig
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.integration.osmGpxFileName
import org.iz.navigation.osmcommunity.CommunitySession

data class OsmGpxReceiptKey(val journeyId: String, val sha256: String) {
    val fileName: String get() = osmGpxFileName(sha256)

    companion object {
        fun of(trace: PreparedOsmGpxTrace) = OsmGpxReceiptKey(trace.journeyId, trace.sha256)
    }
}

enum class OsmGpxReceiptStatus { UNKNOWN, RECEIVED }

data class OsmGpxReceipt internal constructor(
    val accountId: Long,
    val key: OsmGpxReceiptKey,
    val status: OsmGpxReceiptStatus,
    val traceId: Long?,
    val attemptId: String,
    val attemptedAt: Long,
) {
    fun traceUrl(user: OsmUser): String? {
        if (user.id != accountId || status != OsmGpxReceiptStatus.RECEIVED) return null
        val id = traceId ?: return null
        return osmTraceUrl(user, id)
    }
}

private val osmWebsite = "https://www.openstreetmap.org/".toHttpUrl()
const val OSM_GPX_MY_TRACES_URL = "https://www.openstreetmap.org/traces/mine"

private fun osmTraceUrl(user: OsmUser, id: Long): String =
    osmWebsite.newBuilder().addPathSegment("user").addPathSegment(user.displayName)
        .addPathSegment("traces").addPathSegment(id.toString()).build().toString()

data class OsmGpxHistoryEntry(val id: Long, val fileName: String, val timestampMillis: Long, val pending: Boolean) {
    fun traceUrl(user: OsmUser): String = osmTraceUrl(user, id)
}

sealed interface OsmGpxHistoryState {
    data object Idle : OsmGpxHistoryState
    data object Loading : OsmGpxHistoryState
    data object Failed : OsmGpxHistoryState
    data class Ready(val user: OsmUser, val entries: List<OsmGpxHistoryEntry>) : OsmGpxHistoryState {
        // The list is newest first. A repeated filename resolves to the newest trace.
        internal val byFileName = entries.asReversed().associateBy { it.fileName }
    }
}

internal data class OsmGpxSelection(val key: OsmGpxReceiptKey, val prior: OsmGpxReceipt?)

class OsmGpxConfirmation internal constructor(
    internal val session: CommunitySession,
    selection: List<OsmGpxSelection>,
    val visibility: OsmGpxVisibility,
) {
    internal val selection: List<OsmGpxSelection> = Collections.unmodifiableList(selection.toList())
    val user = OsmUser(session.userId, session.displayName)
    val keys: List<OsmGpxReceiptKey> = Collections.unmodifiableList(this.selection.map { it.key })
    val traceCount: Int get() = keys.size
    private val used = AtomicBoolean()
    internal fun consume(): Boolean = used.compareAndSet(false, true)

    internal fun replaceVisibility(visibility: OsmGpxVisibility): OsmGpxConfirmation? {
        if (visibility == this.visibility || !consume()) return null
        return OsmGpxConfirmation(session, selection, visibility)
    }
}

enum class OsmGpxStop {
    COMPLETE, BUSY, ACCOUNT_CHANGED, BACKGROUND, STALE_CONFIRMATION, UNKNOWN, REJECTED,
    RATE_LIMITED, STORAGE_ERROR, ALREADY_USED, HISTORY_REQUIRED
}

data class OsmGpxBatchResult(
    val stop: OsmGpxStop,
    val receipts: List<OsmGpxReceipt>,
    val unattemptedCount: Int,
    val retryAfterMillis: Long? = null,
    val httpStatus: Int? = null,
    val rejectedKeys: Map<OsmGpxReceiptKey, Int> = emptyMap(),
)

class OsmGpxController internal constructor(
    private val currentSession: () -> CommunitySession?,
    private val gateway: OsmGpxGateway,
    private val store: OsmGpxReceiptStore,
    val authState: StateFlow<OsmAuthUiState>,
    /** Session identity for UI invalidation. Upload eligibility also checks the current usable session. */
    internal val sessions: StateFlow<CommunitySession?>,
    private val permissionRequest: (Activity) -> Unit,
    private val rejectSession: (CommunitySession) -> Unit = {},
    private val historyGateway: OsmGpxHistoryGateway,
    private val currentHistorySession: () -> CommunitySession? = currentSession,
    private val readPermissionRequest: (Activity) -> Unit = permissionRequest,
) {
    private val uploadLock = Mutex()
    private val receiptLock = Mutex()
    private val historyLock = Any()
    private var historyOwner: CommunitySession? = null
    private var historyRequest: Any? = null
    private val mutableHistory = MutableStateFlow<OsmGpxHistoryState>(OsmGpxHistoryState.Idle)
    val history: StateFlow<OsmGpxHistoryState> = mutableHistory.asStateFlow()

    fun requestPermission(activity: Activity) = permissionRequest(activity)
    fun requestReadPermission(activity: Activity) = readPermissionRequest(activity)

    private fun usableSession() = receiptSession()?.takeIf { OSM_GPX_WRITE_SCOPE in it.scopes }
    private fun readableSession() = currentHistorySession()?.takeIf {
        OSM_GPX_READ_SCOPE in it.scopes && it.isUnexpired()
    }
    private fun receiptSession() = currentSession()?.takeIf { it.isUnexpired() }

    private fun CommunitySession.isUnexpired(): Boolean =
        expiresAt == null || expiresAt > System.currentTimeMillis()

    private fun watchSession(scope: CoroutineScope) {
        scope.launch {
            combine(sessions, authState) { _, _ -> Unit }.collect { clearStaleHistory() }
        }
    }

    private fun clearStaleHistory() = synchronized(historyLock) {
        if (historyOwner !== readableSession()) {
            historyOwner = null
            historyRequest = null
            mutableHistory.value = OsmGpxHistoryState.Idle
        }
    }

    private fun readyHistory(session: CommunitySession) = synchronized(historyLock) {
        if (historyOwner === session && readableSession() === session) {
            mutableHistory.value as? OsmGpxHistoryState.Ready
        } else null
    }

    suspend fun refreshHistory() {
        val session = readableSession() ?: run {
            clearStaleHistory()
            return
        }
        val request = Any()
        // Keep the verified snapshot stable for a running upload. Do not hold the upload lock over GET.
        uploadLock.withLock {
            synchronized(historyLock) {
                if (readableSession() !== session) return
                historyOwner = session
                historyRequest = request
                mutableHistory.value = OsmGpxHistoryState.Loading
            }
        }
        try {
            val entries = historyGateway.list(session.token, session.userId)
            finishHistory(session, request, ready(session, entries))
        } catch (cancel: CancellationException) {
            finishHistory(session, request, OsmGpxHistoryState.Idle)
            throw cancel
        } catch (error: Exception) {
            if (finishHistory(session, request, OsmGpxHistoryState.Failed) &&
                error is OsmGpxHistoryHttpException && error.statusCode == 401
            ) rejectSession(session)
        }
    }

    private fun finishHistory(session: CommunitySession, request: Any, state: OsmGpxHistoryState): Boolean =
        synchronized(historyLock) {
            clearStaleHistory()
            if (historyRequest !== request || historyOwner !== session) return@synchronized false
            historyRequest = null
            mutableHistory.value = state
            true
        }

    private fun ready(session: CommunitySession, entries: List<OsmGpxHistoryEntry>) = OsmGpxHistoryState.Ready(
        OsmUser(session.userId, session.displayName),
        Collections.unmodifiableList(entries.sortedWith(
            compareByDescending<OsmGpxHistoryEntry> { it.timestampMillis }.thenByDescending { it.id }
        )),
    )

    private fun appendHistory(session: CommunitySession, entry: OsmGpxHistoryEntry) = synchronized(historyLock) {
        val current = readyHistory(session) ?: return@synchronized
        mutableHistory.value = ready(session, current.entries.filterNot { it.id == entry.id } + entry)
    }

    private suspend fun <T> withReceipts(block: () -> T): T =
        receiptLock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Caller holds the receipt lock. Only resolution of a durable UNKNOWN is persisted. */
    private fun receiptFor(
        accountId: Long,
        key: OsmGpxReceiptKey,
        match: OsmGpxHistoryEntry?,
    ): OsmGpxReceipt? {
        val local = store.read(accountId, key)
        if (match == null || local?.status == OsmGpxReceiptStatus.RECEIVED) return local
        return if (local != null) {
            local.copy(status = OsmGpxReceiptStatus.RECEIVED, traceId = match.id).also(store::write)
        } else {
            OsmGpxReceipt(accountId, key, OsmGpxReceiptStatus.RECEIVED, match.id,
                "history:${match.id}", match.timestampMillis)
        }
    }

    /** Only the signed-in account's receipts or exact filenames. May fail on local I/O. */
    suspend fun receiptsForKeys(keys: List<OsmGpxReceiptKey>): Map<OsmGpxReceiptKey, OsmGpxReceipt> {
        val session = receiptSession() ?: return emptyMap()
        val found = withReceipts {
            val matches = readyHistory(session)?.byFileName.orEmpty()
            buildMap {
                for (key in keys) {
                    receiptFor(session.userId, key, matches[key.fileName])?.let { put(key, it) }
                }
            }
        }
        return if (receiptSession() === session) found else emptyMap()
    }

    /**
     * Call before showing consent. Review must identify the exact UNKNOWN receipt shown to the user.
     * Received traces and UNKNOWN attempts without manual review are excluded from the consent count.
     */
    suspend fun prepareConfirmationForKeys(
        keys: List<OsmGpxReceiptKey>,
        reviewedUnknown: Set<OsmGpxReceipt> = emptySet(),
        visibility: OsmGpxVisibility = OsmGpxVisibility.IDENTIFIABLE,
    ): OsmGpxConfirmation? {
        val session = usableSession() ?: return null
        val verified = readyHistory(session) ?: return null
        val selection = withReceipts {
            keys.distinctBy { it.journeyId }.mapNotNull { key ->
                val prior = receiptFor(session.userId, key, verified.byFileName[key.fileName])
                if (prior == null || (prior.status == OsmGpxReceiptStatus.UNKNOWN && prior in reviewedUnknown)) {
                    OsmGpxSelection(key, prior)
                } else null
            }.distinctBy { it.key.fileName }
        }
        return if (selection.isNotEmpty() && usableSession() === session && readyHistory(session) === verified) {
            OsmGpxConfirmation(session, selection, visibility)
        } else null
    }

    /**
     * Call only from explicit consent. The thread-safe foreground predicate must stay false after
     * this screen leaves the foreground, including when an upload overlaps a later resume.
     */
    suspend fun upload(
        confirmation: OsmGpxConfirmation,
        stillForeground: () -> Boolean,
        prepare: suspend (OsmGpxReceiptKey) -> PreparedOsmGpxTrace?,
        onAttempt: (OsmGpxReceiptKey, Int) -> Unit = { _, _ -> },
    ): OsmGpxBatchResult {
        val session = confirmation.session
        val results = mutableListOf<OsmGpxReceipt>()
        val rejectedKeys = linkedMapOf<OsmGpxReceiptKey, Int>()
        var unattempted = confirmation.traceCount
        fun result(stop: OsmGpxStop, rejected: OsmGpxUploadResult.Rejected? = null) =
            OsmGpxBatchResult(stop, results.toList(), unattempted, rejected?.retryAfterMillis,
                rejected?.statusCode, rejectedKeys.toMap())
        fun stopped(): OsmGpxStop? = when {
            usableSession() !== session -> OsmGpxStop.ACCOUNT_CHANGED
            !stillForeground() -> OsmGpxStop.BACKGROUND
            readyHistory(session) == null -> OsmGpxStop.HISTORY_REQUIRED
            else -> null
        }

        if (!uploadLock.tryLock()) return result(OsmGpxStop.BUSY)
        try {
            if (!confirmation.consume()) return result(OsmGpxStop.ALREADY_USED)
            for ((index, item) in confirmation.selection.withIndex()) {
                val (key, prior) = item
                stopped()?.let { return result(it) }
                val trace = prepare(key)
                stopped()?.let { return result(it) }
                if (trace == null || OsmGpxReceiptKey.of(trace) != key) {
                    return result(OsmGpxStop.STALE_CONFIRMATION)
                }
                val attempt = OsmGpxReceipt(session.userId, key, OsmGpxReceiptStatus.UNKNOWN, null,
                    UUID.randomUUID().toString(), System.currentTimeMillis())
                val reserved = withReceipts {
                    if (stopped() != null || readyHistory(session)?.byFileName?.containsKey(key.fileName) == true ||
                        store.read(session.userId, key) != prior
                    ) false
                    else {
                        // An interrupted process leaves a durable UNKNOWN before any bytes can be sent.
                        store.write(attempt)
                        true
                    }
                }
                if (!reserved) return result(stopped() ?: OsmGpxStop.STALE_CONFIRMATION)

                if (stopped() == null) onAttempt(key, index + 1)
                val response = try {
                    if (stopped() != null) OsmGpxUploadResult.NotDispatched
                    else gateway.upload(session.token, trace, confirmation.visibility) { stopped() == null }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    OsmGpxUploadResult.Unknown
                }
                if (response != OsmGpxUploadResult.NotDispatched) unattempted--
                when (response) {
                    is OsmGpxUploadResult.Received -> {
                        val receipt = attempt.copy(status = OsmGpxReceiptStatus.RECEIVED, traceId = response.traceId)
                        results += attempt
                        withContext(NonCancellable) {
                            withReceipts { store.write(receipt) }
                            appendHistory(session, OsmGpxHistoryEntry(response.traceId, key.fileName,
                                receipt.attemptedAt, pending = true))
                        }
                        results[results.lastIndex] = receipt
                    }
                    OsmGpxUploadResult.Unknown -> {
                        results += attempt
                        return result(OsmGpxStop.UNKNOWN)
                    }
                    is OsmGpxUploadResult.Rejected, OsmGpxUploadResult.NotDispatched -> {
                        // A definitive failed retry cannot erase uncertainty about the earlier attempt.
                        withContext(NonCancellable) {
                            withReceipts {
                                if (prior == null) store.remove(session.userId, key) else store.write(prior)
                            }
                        }
                        if (response is OsmGpxUploadResult.Rejected) {
                            rejectedKeys[key] = response.statusCode
                            if (response.statusCode in FILE_REJECTION_STATUSES) continue
                            if (response.statusCode == 401) rejectSession(session)
                            val stop = if (response.statusCode == 429) OsmGpxStop.RATE_LIMITED else OsmGpxStop.REJECTED
                            return result(stop, response)
                        }
                        return result(stopped() ?: OsmGpxStop.STALE_CONFIRMATION)
                    }
                }
            }
            return result(OsmGpxStop.COMPLETE)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return result(OsmGpxStop.STORAGE_ERROR)
        } finally {
            uploadLock.unlock()
        }
    }

    companion object {
        private val FILE_REJECTION_STATUSES = setOf(400, 413, 422)
        @Volatile private var testing: OsmGpxController? = null
        @Volatile private var instance: OsmGpxController? = null

        fun get(context: Context): OsmGpxController = testing ?: instance ?: synchronized(this) {
            testing ?: instance ?: run {
                val auth = OsmAuthManager.get(context)
                OsmGpxController(auth::currentGpxSession, OsmGpxClient(),
                    OsmGpxFileReceipts(context.noBackupFilesDir), auth.state,
                    auth.communitySessionSource.sessions, auth::requestGpxPermission,
                    auth.communitySessionSource::rejectSession, OsmGpxHistoryClient(),
                    auth::currentGpxHistorySession, auth::requestGpxReadPermission).also {
                    it.watchSession(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
                    instance = it
                }
            }
        }

        /** Install before opening the GPS screen in the isolated, non-network QA app. */
        internal fun installForTesting(
            context: Context,
            authState: StateFlow<OsmAuthUiState>,
            sessions: StateFlow<CommunitySession?>,
            gateway: OsmGpxGateway,
            requestPermission: (Activity) -> Unit,
            historyGateway: OsmGpxHistoryGateway = OsmGpxHistoryGateway { _, _ -> emptyList() },
            requestReadPermission: (Activity) -> Unit = requestPermission,
        ): AutoCloseable = synchronized(this) {
            check(BuildConfig.DEBUG && context.applicationContext.packageName == "org.iz.navigation.osmgpxqa")
            check(testing == null)
            val override = OsmGpxController(
                { sessions.value.takeUnless { authState.value.busy } }, gateway,
                OsmGpxFileReceipts(context.noBackupFilesDir), authState, sessions, requestPermission,
                historyGateway = historyGateway, readPermissionRequest = requestReadPermission,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            override.watchSession(scope)
            testing = override
            AutoCloseable {
                scope.cancel()
                synchronized(this) { if (testing === override) testing = null }
            }
        }
    }
}
