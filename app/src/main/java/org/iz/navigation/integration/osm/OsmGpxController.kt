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
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.integration.OsmDirectedCoverage
import org.iz.navigation.integration.OsmGpxPreparation
import org.iz.navigation.integration.prepareOsmGpxTrace
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
    val directPost: Boolean = false,
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

data class OsmGpxHistoryEntry(val id: Long, val fileName: String, val timestampMillis: Long, val pending: Boolean, val visibility: String? = null) {
    fun traceUrl(user: OsmUser): String = osmTraceUrl(user, id)
}

sealed interface OsmGpxHistoryState {
    data object Idle : OsmGpxHistoryState
    data object Loading : OsmGpxHistoryState
    data class Failed(val issue: String) : OsmGpxHistoryState
    data class Ready(val user: OsmUser, val entries: List<OsmGpxHistoryEntry>, val coverageReady: Boolean = true,
        val issue: String? = null) : OsmGpxHistoryState
}

class OsmGpxPlan internal constructor(
    internal val session: CommunitySession,
    ids: List<String>,
    traces: List<PreparedOsmGpxTrace>,
) {
    internal val journeyIds: List<String> = Collections.unmodifiableList(ids.toList())
    val user = OsmUser(session.userId, session.displayName)
    val traces: List<PreparedOsmGpxTrace> = Collections.unmodifiableList(traces.toList())
}

class OsmGpxConfirmation internal constructor(internal val plan: OsmGpxPlan) {
    val visibility = OsmGpxVisibility.TRACKABLE
    val user = plan.user
    val keys: List<OsmGpxReceiptKey> = Collections.unmodifiableList(plan.traces.map(OsmGpxReceiptKey::of))
    val traceCount: Int get() = keys.size
    private val used = AtomicBoolean()
    internal fun consume(): Boolean = used.compareAndSet(false, true)
}

enum class OsmGpxStop {
    COMPLETE, BUSY, ACCOUNT_CHANGED, BACKGROUND, STALE_CONFIRMATION, UNKNOWN, REJECTED,
    RATE_LIMITED, STORAGE_ERROR, ALREADY_USED, HISTORY_REQUIRED, CHANGED
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
    private val loadJourney: suspend (String) -> Pair<Long, PreparedOsmGpxTrace>? = { null },
) {
    private val uploadLock = Mutex()
    private val historyLock = Any()
    private val coverageLoader = OsmGpxCoverageLoader(store, historyGateway, loadJourney)
    private var owner: CommunitySession? = null
    private var request: Any? = null
    private var coverage: OsmGpxAccountCoverage? = null
    private val mutableHistory = MutableStateFlow<OsmGpxHistoryState>(OsmGpxHistoryState.Idle)
    val history: StateFlow<OsmGpxHistoryState> = mutableHistory.asStateFlow()

    fun requestPermission(activity: Activity) = permissionRequest(activity)
    fun requestReadPermission(activity: Activity) = readPermissionRequest(activity)
    private fun CommunitySession.isUnexpired() = expiresAt == null || expiresAt > System.currentTimeMillis()
    private fun receiptSession() = currentSession()?.takeIf { it.isUnexpired() }
    private fun usableSession() = receiptSession()?.takeIf { OSM_GPX_WRITE_SCOPE in it.scopes && readableSession() === it }
    private fun readableSession() = currentHistorySession()?.takeIf { OSM_GPX_READ_SCOPE in it.scopes && it.isUnexpired() }
    private fun watchSession(scope: CoroutineScope) {
        scope.launch { combine(sessions, authState) { _, _ -> Unit }.collect { clearStaleHistory() } }
    }

    private fun clearStaleHistory() = synchronized(historyLock) {
        if (owner !== readableSession()) {
            owner = null
            request = null
            coverage = null
            mutableHistory.value = OsmGpxHistoryState.Idle
        }
    }

    private fun verified(session: CommunitySession) = synchronized(historyLock) {
        coverage.takeIf { owner === session && readableSession() === session }
    }

    suspend fun refreshHistory() {
        val session = readableSession() ?: return clearStaleHistory()
        synchronizeHistory(session, true)
    }

    private suspend fun synchronizeHistory(session: CommunitySession, showLoading: Boolean,
        acknowledgedHere: Set<Long> = emptySet()): OsmGpxAccountCoverage? {
        val task = Any()
        synchronized(historyLock) {
            if (readableSession() !== session) return null
            owner = session
            request = task
            coverage = null
            if (showLoading) mutableHistory.value = OsmGpxHistoryState.Loading
        }
        fun current() = synchronized(historyLock) { request === task && readableSession() === session }
        var state: OsmGpxHistoryState = OsmGpxHistoryState.Failed("OSM iz geçmişi okunamadı.")
        var next: OsmGpxAccountCoverage? = null
        try {
            val remote = historyGateway.list(session.token, session.userId)
            if (!current()) return null
            require(remote.size <= MAX_OSM_TRACES && remote.all { it.id > 0 } && remote.distinctBy { it.id }.size == remote.size)
            val sorted = Collections.unmodifiableList(remote.sortedWith(
                compareByDescending<OsmGpxHistoryEntry> { it.timestampMillis }.thenByDescending { it.id }))
            val ready = OsmGpxHistoryState.Ready(OsmUser(session.userId, session.displayName), sorted, false)
            state = ready
            next = withContext(Dispatchers.IO) { coverageLoader.load(session, sorted, acknowledgedHere, ::current) }
            state = ready.copy(coverageReady = true)
        } catch (cancel: CancellationException) {
            state = OsmGpxHistoryState.Idle
            throw cancel
        } catch (error: Exception) {
            if (current() && error is OsmGpxHistoryHttpException && error.statusCode == 401) rejectSession(session)
            val listed = state as? OsmGpxHistoryState.Ready
            val issue = when {
                error.message == "pending-trace" -> "OSM bir izi henüz işliyor."
                error.message == "unresolved-receipt" -> "Önceki gönderinin içeriği OSM geçmişinde henüz doğrulanamadı."
                error.message == "geometry-space" -> "İz geçmişini doğrulamak için cihazda yeterli boş alan yok."
                error.message == "unverified-gap" -> "Bir OSM izinde güvenle eşleştirilemeyen uzun bir konum aralığı var."
                error is OsmGpxDataLimitException -> "OSM yanıtı bu cihazda güvenle işlenebilecek tek dosya veya liste sınırını aştı."
                error is OsmGpxHistoryHttpException -> "OSM iz verisini vermedi (HTTP ${error.statusCode})."
                else -> "Hesaptaki iz verisinin tamamı güvenle okunamadı. Dosya biçimi veya güvenli işleme sınırı etkili olabilir."
            }
            state = listed?.copy(issue = issue) ?: OsmGpxHistoryState.Failed(issue)
        } finally {
            synchronized(historyLock) {
                if (current()) {
                    coverage = next
                    mutableHistory.value = state
                } else clearStaleHistory()
            }
        }
        return next.takeIf { current() }
    }

    private fun blockCoverage(session: CommunitySession) = synchronized(historyLock) {
        if (owner === session) {
            request = null
            coverage = null
            val ready = mutableHistory.value as? OsmGpxHistoryState.Ready
            if (ready != null) mutableHistory.value = ready.copy(coverageReady = false)
        }
    }

    suspend fun receiptsForKeys(keys: List<OsmGpxReceiptKey>): Map<OsmGpxReceiptKey, OsmGpxReceipt> {
        val session = receiptSession() ?: return emptyMap()
        val snapshot = verified(session)
        val results = withContext(Dispatchers.IO) {
            keys.mapNotNull { key ->
                val local = store.read(session.userId, key)
                val id = snapshot?.byDigest?.get(key.sha256)
                val receipt = when {
                    id != null -> OsmGpxReceipt(session.userId, key, OsmGpxReceiptStatus.RECEIVED, id,
                        local?.attemptId ?: "verified:$id", local?.attemptedAt ?: 0)
                    local?.directPost == true || local?.status == OsmGpxReceiptStatus.UNKNOWN -> local
                    else -> null
                }
                receipt?.let { key to it }
            }.toMap()
        }
        return results.takeIf { receiptSession() === session }.orEmpty()
    }

    suspend fun unknownReceipts(): List<OsmGpxReceipt> {
        val session = readableSession() ?: return emptyList()
        val records = withContext(Dispatchers.IO) {
            store.inventory(session.userId).filter { it.status == OsmGpxReceiptStatus.UNKNOWN }.map {
                OsmGpxReceipt(session.userId, OsmGpxReceiptKey(it.journeyId ?: it.recordName, it.sha256),
                    it.status, null, it.attemptId, it.attemptedAt)
            }
        }
        return records.takeIf { readableSession() === session }.orEmpty()
    }

    suspend fun preparePlan(journeyIds: List<String>): OsmGpxPlan? {
        val session = readableSession() ?: return null
        val snapshot = verified(session) ?: return null
        return buildPlan(session, journeyIds, snapshot)?.takeIf { verified(session) === snapshot }
    }

    private suspend fun buildPlan(session: CommunitySession, ids: List<String>, snapshot: OsmGpxAccountCoverage): OsmGpxPlan? =
        withContext(Dispatchers.IO) {
            try {
                require(ids.size <= MAX_OSM_TRACES)
                val distinct = ids.distinct()
                var points = 0L
                val originals = distinct.mapNotNull { id ->
                    check(readableSession() === session)
                    loadJourney(id)?.also { (_, trace) ->
                        require(trace.journeyId == id)
                        points += trace.pointCount
                        require(points <= MAX_OSM_POINTS)
                    }
                }.filterNot { it.second.sha256 in snapshot.submittedDigests }
                    .sortedWith(compareBy<Pair<Long, PreparedOsmGpxTrace>> { it.first }.thenBy { it.second.journeyId })
                val used = OsmDirectedCoverage(snapshot.nearby(originals))
                val traces = used.filterChronologically(originals.map { it.second })
                    .filterNot { it.sha256 in snapshot.submittedDigests }
                check(readableSession() === session)
                OsmGpxPlan(session, distinct, traces)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }

    suspend fun prepareConfirmation(plan: OsmGpxPlan): OsmGpxConfirmation? {
        if (plan.traces.isEmpty() || usableSession() !== plan.session) return null
        val latest = preparePlan(plan.journeyIds) ?: return null
        return if (latest.session === plan.session && samePayload(plan.traces, latest.traces)) OsmGpxConfirmation(plan) else null
    }

    private fun samePayload(left: List<PreparedOsmGpxTrace>, right: List<PreparedOsmGpxTrace>) =
        left.size == right.size && left.indices.all { i ->
            left[i].journeyId == right[i].journeyId && left[i].gpxBytes == right[i].gpxBytes
        }

    suspend fun upload(confirmation: OsmGpxConfirmation, stillForeground: () -> Boolean,
        onAttempt: (OsmGpxReceiptKey, Int) -> Unit = { _, _ -> }): OsmGpxBatchResult {
        val plan = confirmation.plan
        val session = plan.session
        val results = mutableListOf<OsmGpxReceipt>()
        val rejected = mutableMapOf<OsmGpxReceiptKey, Int>()
        val finished = mutableSetOf<String>()
        val acceptedIds = mutableSetOf<Long>()
        var remaining = plan.traces.size
        fun result(stop: OsmGpxStop, error: OsmGpxUploadResult.Rejected? = null) =
            OsmGpxBatchResult(stop, results.toList(), remaining, error?.retryAfterMillis, error?.statusCode, rejected.toMap())
        fun interrupted() = when {
            usableSession() !== session -> OsmGpxStop.ACCOUNT_CHANGED
            !stillForeground() -> OsmGpxStop.BACKGROUND
            else -> null
        }
        if (!uploadLock.tryLock()) return result(OsmGpxStop.BUSY)
        try {
            if (!confirmation.consume()) return result(OsmGpxStop.ALREADY_USED)
            for ((index, trace) in plan.traces.withIndex()) {
                interrupted()?.let { return result(it) }
                val snapshot = synchronizeHistory(session, false, acceptedIds)
                    ?: return result(interrupted() ?: OsmGpxStop.HISTORY_REQUIRED)
                val next = buildPlan(session, plan.journeyIds.filterNot(finished::contains), snapshot)
                interrupted()?.let { return result(it) }
                if (next == null || !samePayload(plan.traces.drop(index), next.traces)) return result(OsmGpxStop.CHANGED)
                val key = OsmGpxReceiptKey.of(trace)
                val attempt = OsmGpxReceipt(session.userId, key, OsmGpxReceiptStatus.UNKNOWN, null,
                    UUID.randomUUID().toString(), System.currentTimeMillis())
                var previous: OsmGpxReceipt? = null
                val reserved = withContext(Dispatchers.IO) {
                    previous = store.read(session.userId, key)
                    if (previous?.directPost == true || previous?.status == OsmGpxReceiptStatus.UNKNOWN ||
                        verified(session) !== snapshot || interrupted() != null) false
                    else { store.write(attempt); true }
                }
                if (!reserved) return result(interrupted() ?: OsmGpxStop.CHANGED)
                fun canDispatch() = interrupted() == null && verified(session) === snapshot
                val response = try {
                    if (canDispatch()) onAttempt(key, index + 1)
                    if (canDispatch()) gateway.upload(session.token, trace, OsmGpxVisibility.TRACKABLE, ::canDispatch)
                    else OsmGpxUploadResult.NotDispatched
                } catch (cancel: CancellationException) {
                    blockCoverage(session)
                    throw cancel
                } catch (_: Exception) { OsmGpxUploadResult.Unknown }
                if (response != OsmGpxUploadResult.NotDispatched) remaining--
                when (response) {
                    is OsmGpxUploadResult.Received -> {
                        val receipt = attempt.copy(status = OsmGpxReceiptStatus.RECEIVED, traceId = response.traceId, directPost = true)
                        withContext(NonCancellable + Dispatchers.IO) {
                            require(response.traceId > 0)
                            // Persist geometry before releasing UNKNOWN, even when the server list lags the POST.
                            store.coverageCache.write(session.userId, response.traceId, trace.geometry())
                            store.write(receipt)
                        }
                        results += receipt
                        acceptedIds += response.traceId
                        finished += trace.journeyId
                        blockCoverage(session)
                    }
                    OsmGpxUploadResult.Unknown -> {
                        results += attempt
                        blockCoverage(session)
                        return result(OsmGpxStop.UNKNOWN)
                    }
                    is OsmGpxUploadResult.Rejected, OsmGpxUploadResult.NotDispatched -> {
                        withContext(NonCancellable + Dispatchers.IO) {
                            previous?.let(store::write) ?: store.remove(session.userId, key)
                        }
                        if (response is OsmGpxUploadResult.Rejected) {
                            rejected[key] = response.statusCode
                            finished += trace.journeyId
                            if (response.statusCode in FILE_REJECTION_STATUSES) continue
                            if (response.statusCode == 401) rejectSession(session)
                            return result(if (response.statusCode == 429) OsmGpxStop.RATE_LIMITED else OsmGpxStop.REJECTED, response)
                        }
                        return result(interrupted() ?: OsmGpxStop.CHANGED)
                    }
                }
            }
            return result(interrupted() ?: OsmGpxStop.COMPLETE)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { blockCoverage(session); return result(OsmGpxStop.STORAGE_ERROR) }
        finally { uploadLock.unlock() }
    }

    suspend fun changeToTrackable(userId: Long, ids: List<Long>, canContinue: () -> Boolean = { true }): Map<Long, String> {
        val session = usableSession()?.takeIf { it.userId == userId } ?: return emptyMap()
        val shown = synchronized(historyLock) {
            (mutableHistory.value as? OsmGpxHistoryState.Ready)?.takeIf { owner === session }
        } ?: return emptyMap()
        require(ids.size <= MAX_OSM_TRACES)
        val selected = ids.distinct()
        val eligible = shown.entries.filter { it.visibility.equals("identifiable", true) && !it.pending }.map { it.id }.toSet()
        if (!uploadLock.tryLock()) return selected.associateWith { "Başka bir işlem sürüyor." }
        val result = linkedMapOf<Long, String>()
        try {
            for (id in selected) {
                fun current() = usableSession() === session && canContinue()
                if (!current()) break
                result[id] = if (id !in eligible) "Bu iz dönüştürmeye uygun değil."
                else try {
                    if (historyGateway.changeToTrackable(session.token, userId, id, ::current)) "Trackable olarak doğrulandı."
                    else "Sonuç doğrulanamadı. Yeniden kontrol et."
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { "Sonuç doğrulanamadı. Yeniden kontrol et." }
            }
        } finally {
            blockCoverage(session)
            uploadLock.unlock()
        }
        return result.takeIf { usableSession() === session }.orEmpty()
    }

    companion object {
        private val FILE_REJECTION_STATUSES = setOf(400, 413, 422)
        @Volatile private var testing: OsmGpxController? = null
        @Volatile private var instance: OsmGpxController? = null

        private fun journeyLoader(context: Context): suspend (String) -> Pair<Long, PreparedOsmGpxTrace>? {
            val diary = DiaryRepository(context.applicationContext)
            return loader@{ id ->
                val journey = diary.getJourney(id) ?: return@loader null
                val points = diary.osmJourneyPoints(id, MAX_OSM_POINTS + 1)
                require(points.size <= MAX_OSM_POINTS)
                val trace = (prepareOsmGpxTrace(journey, points) as? OsmGpxPreparation.Ready)?.trace
                trace?.let { journey.startedAt to it }
            }
        }

        fun get(context: Context): OsmGpxController = testing ?: instance ?: synchronized(this) {
            testing ?: instance ?: run {
                val auth = OsmAuthManager.get(context)
                OsmGpxController(auth::currentGpxSession, OsmGpxClient(),
                    OsmGpxFileReceipts(context.noBackupFilesDir), auth.state,
                    auth.communitySessionSource.sessions, auth::requestGpxPermission,
                    auth.communitySessionSource::rejectSession, OsmGpxHistoryClient(),
                    auth::currentGpxHistorySession, auth::requestGpxReadPermission, journeyLoader(context)).also {
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
            check(BuildConfig.DEBUG && context.applicationContext.packageName in
                setOf("org.iz.navigation.osmgpxqa", "org.iz.navigation.qaosmyon20261127"))
            check(testing == null)
            val override = OsmGpxController(
                { sessions.value.takeUnless { authState.value.busy } }, gateway,
                OsmGpxFileReceipts(context.noBackupFilesDir), authState, sessions, requestPermission,
                historyGateway = historyGateway, readPermissionRequest = requestReadPermission, loadJourney = journeyLoader(context),
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
