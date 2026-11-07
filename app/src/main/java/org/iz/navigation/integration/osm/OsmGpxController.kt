package org.iz.navigation.integration.osm

import android.app.Activity
import android.content.Context
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.iz.navigation.BuildConfig
import org.iz.navigation.integration.PreparedOsmGpxTrace
import org.iz.navigation.osmcommunity.CommunitySession

data class OsmGpxReceiptKey(val journeyId: String, val sha256: String) {
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
        return osmWebsite.newBuilder().addPathSegment("user").addPathSegment(user.displayName)
            .addPathSegment("traces").addPathSegment(id.toString()).build().toString()
    }
}

private val osmWebsite = "https://www.openstreetmap.org/".toHttpUrl()
const val OSM_GPX_MY_TRACES_URL = "https://www.openstreetmap.org/traces/mine"

internal data class OsmGpxSelection(val trace: PreparedOsmGpxTrace, val prior: OsmGpxReceipt?)

class OsmGpxConfirmation internal constructor(
    internal val session: CommunitySession,
    internal val selection: List<OsmGpxSelection>,
) {
    val user = OsmUser(session.userId, session.displayName)
    val traces: List<PreparedOsmGpxTrace> = Collections.unmodifiableList(selection.map { it.trace })
    val traceCount: Int get() = traces.size
    private val used = AtomicBoolean()
    internal fun consume(): Boolean = used.compareAndSet(false, true)
}

enum class OsmGpxStop {
    COMPLETE, BUSY, ACCOUNT_CHANGED, BACKGROUND, STALE_CONFIRMATION, UNKNOWN, REJECTED,
    RATE_LIMITED, STORAGE_ERROR, ALREADY_USED
}

data class OsmGpxBatchResult(
    val stop: OsmGpxStop,
    val receipts: List<OsmGpxReceipt>,
    val unattemptedCount: Int,
    val retryAfterMillis: Long? = null,
    val httpStatus: Int? = null,
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
) {
    private val uploadLock = Mutex()

    fun requestPermission(activity: Activity) = permissionRequest(activity)

    private fun usableSession() = currentSession()?.takeIf {
        it.expiresAt == null || it.expiresAt > System.currentTimeMillis()
    }

    /** Only the signed-in account's receipts for these exact prepared traces. May fail on local I/O. */
    suspend fun receipts(traces: List<PreparedOsmGpxTrace>): Map<OsmGpxReceiptKey, OsmGpxReceipt> {
        val session = usableSession() ?: return emptyMap()
        val found = withContext(Dispatchers.IO) {
            buildMap {
                for (trace in traces) {
                    val key = OsmGpxReceiptKey.of(trace)
                    store.read(session.userId, key)?.let { put(key, it) }
                }
            }
        }
        return if (usableSession() === session) found else emptyMap()
    }

    /**
     * Call before showing consent. Review must identify the exact UNKNOWN receipt shown to the user.
     * Received traces and UNKNOWN attempts without manual review are excluded from the consent count.
     */
    suspend fun prepareConfirmation(
        traces: List<PreparedOsmGpxTrace>,
        reviewedUnknown: Set<OsmGpxReceipt> = emptySet(),
    ): OsmGpxConfirmation? {
        val session = usableSession()?.takeIf { OSM_GPX_WRITE_SCOPE in it.scopes } ?: return null
        val selection = withContext(Dispatchers.IO) {
            traces.distinctBy { it.journeyId }.mapNotNull { trace ->
                val prior = store.read(session.userId, OsmGpxReceiptKey.of(trace))
                if (prior == null || (prior.status == OsmGpxReceiptStatus.UNKNOWN && prior in reviewedUnknown)) {
                    OsmGpxSelection(trace, prior)
                } else null
            }
        }
        return if (selection.isNotEmpty() && usableSession() === session) {
            OsmGpxConfirmation(session, selection)
        } else null
    }

    /**
     * Call only from explicit consent. The thread-safe foreground predicate must stay false after
     * this screen leaves the foreground, including when an upload overlaps a later resume.
     */
    suspend fun upload(
        confirmation: OsmGpxConfirmation,
        stillForeground: () -> Boolean,
        onAttempt: (OsmGpxReceiptKey, Int) -> Unit = { _, _ -> },
    ): OsmGpxBatchResult {
        val session = confirmation.session
        val results = mutableListOf<OsmGpxReceipt>()
        var unattempted = confirmation.traceCount
        fun result(stop: OsmGpxStop, rejected: OsmGpxUploadResult.Rejected? = null) =
            OsmGpxBatchResult(stop, results.toList(), unattempted, rejected?.retryAfterMillis, rejected?.statusCode)
        fun stopped(): OsmGpxStop? = when {
            usableSession() !== session -> OsmGpxStop.ACCOUNT_CHANGED
            !stillForeground() -> OsmGpxStop.BACKGROUND
            else -> null
        }

        if (!uploadLock.tryLock()) return result(OsmGpxStop.BUSY)
        try {
            if (!confirmation.consume()) return result(OsmGpxStop.ALREADY_USED)
            for ((index, item) in confirmation.selection.withIndex()) {
                val (trace, prior) = item
                stopped()?.let { return result(it) }
                val key = OsmGpxReceiptKey.of(trace)
                val attempt = OsmGpxReceipt(session.userId, key, OsmGpxReceiptStatus.UNKNOWN, null,
                    UUID.randomUUID().toString(), System.currentTimeMillis())
                val reserved = withContext(Dispatchers.IO) {
                    if (store.read(session.userId, key) != prior) false
                    else {
                        // An interrupted process leaves a durable UNKNOWN before any bytes can be sent.
                        store.write(attempt)
                        true
                    }
                }
                if (!reserved) return result(OsmGpxStop.STALE_CONFIRMATION)

                if (stopped() == null) onAttempt(key, index + 1)
                val response = try {
                    if (stopped() != null) OsmGpxUploadResult.NotDispatched
                    else gateway.upload(session.token, trace) { stopped() == null }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    OsmGpxUploadResult.Unknown
                }
                if (response != OsmGpxUploadResult.NotDispatched) unattempted--
                when (response) {
                    is OsmGpxUploadResult.Received -> {
                        val receipt = attempt.copy(status = OsmGpxReceiptStatus.RECEIVED, traceId = response.traceId)
                        results += receipt
                        withContext(NonCancellable + Dispatchers.IO) { store.write(receipt) }
                    }
                    OsmGpxUploadResult.Unknown -> {
                        results += attempt
                        return result(OsmGpxStop.UNKNOWN)
                    }
                    is OsmGpxUploadResult.Rejected, OsmGpxUploadResult.NotDispatched -> {
                        // A definitive failed retry cannot erase uncertainty about the earlier attempt.
                        withContext(NonCancellable + Dispatchers.IO) {
                            if (prior == null) store.remove(session.userId, key) else store.write(prior)
                        }
                        if (response is OsmGpxUploadResult.Rejected) {
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
        @Volatile private var testing: OsmGpxController? = null
        @Volatile private var instance: OsmGpxController? = null

        fun get(context: Context): OsmGpxController = testing ?: instance ?: synchronized(this) {
            testing ?: instance ?: run {
                val auth = OsmAuthManager.get(context)
                OsmGpxController(auth::currentGpxSession, OsmGpxClient(),
                    OsmGpxFileReceipts(context.noBackupFilesDir), auth.state,
                    auth.communitySessionSource.sessions, auth::requestGpxPermission,
                    auth.communitySessionSource::rejectSession).also { instance = it }
            }
        }

        /** Install before opening the GPS screen in the isolated, non-network QA app. */
        internal fun installForTesting(
            context: Context,
            authState: StateFlow<OsmAuthUiState>,
            sessions: StateFlow<CommunitySession?>,
            gateway: OsmGpxGateway,
            requestPermission: (Activity) -> Unit,
        ): AutoCloseable = synchronized(this) {
            check(BuildConfig.DEBUG && context.applicationContext.packageName == "org.iz.navigation.osmgpxqa")
            check(testing == null)
            val override = OsmGpxController(
                { sessions.value.takeUnless { authState.value.busy } }, gateway,
                OsmGpxFileReceipts(context.noBackupFilesDir), authState, sessions, requestPermission,
            )
            testing = override
            AutoCloseable { synchronized(this) { if (testing === override) testing = null } }
        }
    }
}
