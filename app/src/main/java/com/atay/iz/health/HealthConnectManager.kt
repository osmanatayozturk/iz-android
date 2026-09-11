package com.atay.iz.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.work.*
import com.atay.iz.data.DiaryRepository
import com.atay.iz.data.HealthMetric
import com.atay.iz.data.Journey
import com.atay.iz.data.JourneyStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class HealthConnectionState(
    val sdkStatus: Int = HealthConnectClient.SDK_UNAVAILABLE,
    val enabled: Boolean = false,
    val grantedMetrics: Set<HealthMetric> = emptySet(),
    val backgroundSupported: Boolean = false,
    val backgroundAllowed: Boolean = false,
    val syncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
    val sourceCounts: Map<HealthMetric, HealthSourceCounts> = emptyMap(),
)

private class HealthReadAborted : Exception()

/** One coordinator per app process. Health records and cursors never leave private storage. */
class HealthConnectManager(context: Context, private val repository: DiaryRepository) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("health_connect_sync_v1", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow(HealthConnectionState())
    val state: StateFlow<HealthConnectionState> = mutableState.asStateFlow()
    @Volatile private var foreground = false
    @Volatile private var generation = 0L
    private var initialized = false
    private var foregroundPolling: Job? = null

    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            refresh()
            for (ignored in requests) sync(background = !foreground)
        }
        scope.launch {
            repository.journeys.map { trips ->
                trips.filter { it.status == JourneyStatus.CONFIRMED }.map { Triple(it.id, it.startedAt, it.endedAt) }
            }.distinctUntilChanged().collect { requestSync() }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        foregroundPolling?.cancel()
        foregroundPolling = null
        if (value) {
            requestSync()
            foregroundPolling = scope.launch {
                while (isActive && foreground) {
                    delay(60_000)
                    if (foreground && state.value.enabled && state.value.grantedMetrics.isNotEmpty()) requestSync()
                }
            }
        }
    }
    fun requestSync() { requests.trySend(Unit) }

    suspend fun connect() {
        mutex.withLock {
            // New/reinstalled permissions may have a different history window.
            if (!prefs.getBoolean("enabled", false)) {
                val editor = prefs.edit()
                prefs.all.keys.filter { it.startsWith("cursor:") || it.startsWith("trip:") }.forEach(editor::remove)
                editor.putLong("historyStart", System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
                    .putBoolean("enabled", true).commit()
            }
        }
        refresh()
        requestSync()
    }

    suspend fun disconnect() {
        generation++
        prefs.edit().putBoolean("enabled", false).commit()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        mutex.withLock { refresh() }
    }

    suspend fun <T> withDiaryReplacement(block: suspend () -> T): T {
        generation++
        return mutex.withLock {
            val editor = prefs.edit().remove("lastSync")
            prefs.all.keys.filter { it.startsWith("cursor:") || it.startsWith("trip:") }.forEach(editor::remove)
            editor.commit()
            try { block() } finally { requestSync() }
        }
    }

    suspend fun clearLocalData() {
        // Invalidates an in-flight read before waiting for its transaction boundary.
        generation++
        mutex.withLock {
            repository.clearHealthData()
            prefs.edit().remove("lastSync").commit()
            mutableState.update { it.copy(lastSyncedAt = null, sourceCounts = emptyMap()) }
        }
    }

    suspend fun refresh(): HealthConnectionState {
        try {
            val sdk = HealthConnectClient.getSdkStatus(context)
            val client = if (sdk == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null
            val granted = client?.permissionController?.getGrantedPermissions().orEmpty()
            val metrics = HealthMetric.entries.filter { it.readPermission() in granted }.toSet()
            // A later grant must re-read any data missed during revocation.
            val editor = prefs.edit()
            HealthMetric.entries.filter { it !in metrics }.forEach { metric ->
                editor.remove("cursor:${metric.name}")
                prefs.all.keys.filter { it.startsWith("trip:${metric.name}:") }.forEach(editor::remove)
            }
            editor.apply()
            val supported = client?.features?.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            mutableState.update { old -> old.copy(
                sdkStatus = sdk, enabled = prefs.getBoolean("enabled", false), grantedMetrics = metrics,
                backgroundSupported = supported,
                backgroundAllowed = supported && BACKGROUND_HEALTH_PERMISSION in granted,
                lastSyncedAt = prefs.getLong("lastSync", 0).takeIf { it > 0 }, error = null,
            ) }
            scheduleBackground(mutableState.value)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutableState.update { it.copy(error = "Health Connect durumuna erişilemedi. Yeniden deneyebilirsin.") } }
        return mutableState.value
    }

    private fun scheduleBackground(state: HealthConnectionState) {
        val work = WorkManager.getInstance(context)
        if (state.enabled && state.backgroundAllowed && state.grantedMetrics.isNotEmpty()) {
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        } else work.cancelUniqueWork(WORK_NAME)
    }

    /** Background reads are gated even when invoked by a foreground location service. */
    suspend fun sync(background: Boolean): Boolean = mutex.withLock {
        val status = refresh()
        if (!status.enabled || status.sdkStatus != HealthConnectClient.SDK_AVAILABLE ||
            status.grantedMetrics.isEmpty() || (background && !status.backgroundAllowed)) return@withLock true
        val epoch = generation
        mutableState.update { it.copy(syncing = true, error = null) }
        try {
            val client = HealthConnectClient.getOrCreate(context)
            val source = HealthConnectSource(client) { checkReadAllowed(epoch, background) }
            val now = System.currentTimeMillis()
            val historyStart = prefs.getLong("historyStart", now - TimeUnit.DAYS.toMillis(30))
            val journeys = repository.allHealthJourneys().filter {
                it.status == JourneyStatus.CONFIRMED && healthReadWindow(it.startedAt, it.endedAt, historyStart, now) != null
            }
            var failed = false
            for (metric in status.grantedMetrics) {
                try {
                    checkReadAllowed(epoch, background)
                    syncMetric(source, metric, journeys, historyStart, now, epoch, background)
                } catch (e: HealthReadAborted) { throw e }
                catch (e: CancellationException) { throw e }
                catch (e: SecurityException) {
                    resetMetric(metric)
                    failed = true
                    mutableState.update { it.copy(error = "Sağlık okuma izni değişti. İzinleri kontrol et.") }
                } catch (_: Exception) {
                    failed = true
                    mutableState.update { it.copy(error = "Bazı sağlık verileri eşitlenemedi. Mevcut kayıtlar korundu; yeniden denenecek.") }
                }
            }
            mutableState.update { it.copy(sourceCounts = source.counts.toMap()) }
            if (!failed) {
                checkReadAllowed(epoch, background)
                prefs.edit().putLong("lastSync", now).commit()
                mutableState.update { it.copy(lastSyncedAt = now) }
            }
            !failed
        } catch (_: HealthReadAborted) { true }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutableState.update { it.copy(error = "Sağlık verileri eşitlenemedi. Yeniden deneyebilirsin.") }
            false
        } finally { mutableState.update { it.copy(syncing = false) } }
    }

    private suspend fun syncMetric(
        source: HealthConnectSource, metric: HealthMetric, journeys: List<Journey>,
        historyStart: Long, now: Long, epoch: Long, background: Boolean,
    ) {
        val key = "cursor:${metric.name}"
        var token = prefs.getString(key, null)
        for (attempt in 0..1) {
            val rebuild = token == null
            // Capture before the full reads, so changes during pagination are replayed afterwards.
            val startToken = token ?: source.newToken(metric)
            try {
                for (journey in journeys) {
                    val stampKey = "trip:${metric.name}:${journey.id}"
                    val fingerprint = "${journey.startedAt}:${journey.endedAt ?: "active"}"
                    if (rebuild || prefs.getString(stampKey, null) != fingerprint) {
                        val window = healthReadWindow(journey.startedAt, journey.endedAt, historyStart, now) ?: continue
                        val samples = source.read(metric, window)
                        checkReadAllowed(epoch, background)
                        repository.replaceJourneyHealth(setOf(journey.id), samples, metrics = setOf(metric), now = now)
                        prefs.edit().putString(stampKey, fingerprint).commit()
                    }
                }
                val changes = source.changes(startToken)
                checkReadAllowed(epoch, background)
                repository.applyHealthChanges(changes.samples.filter { it.startAt >= historyStart }, changes.changedSourceIds,
                    now = now, journeyIds = journeys.map { it.id }.toSet())
                prefs.edit().putString(key, changes.nextToken).commit()
                return
            } catch (e: HealthTokenExpired) {
                resetMetric(metric)
                token = null
                if (attempt == 1) throw e
            }
        }
    }

    private fun resetMetric(metric: HealthMetric) {
        val editor = prefs.edit().remove("cursor:${metric.name}")
        prefs.all.keys.filter { it.startsWith("trip:${metric.name}:") }.forEach(editor::remove)
        editor.commit()
    }
    private fun checkReadAllowed(epoch: Long, background: Boolean) {
        if (epoch != generation || !prefs.getBoolean("enabled", false)) throw HealthReadAborted()
        if (background && !state.value.backgroundAllowed) throw HealthReadAborted()
        if (!background && !foreground && !state.value.backgroundAllowed) throw HealthReadAborted()
    }

    companion object { internal const val WORK_NAME = "iz_health_connect_read" }
}

class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = (applicationContext as com.atay.iz.IzApplication).healthManager
        return if (manager.sync(background = true)) Result.success()
            else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
