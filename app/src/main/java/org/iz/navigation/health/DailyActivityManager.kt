package org.iz.navigation.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.data.*
import org.iz.navigation.wearprotocol.*
import org.iz.navigation.wearprotocol.WearDailyHealthStatus.*
import java.io.File
import java.util.concurrent.TimeUnit

/** Independent daily reader; no sensors, journey health samples, database schema or backup writes. */
class DailyActivityManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val repository = DiaryRepository(this.context)
    private val connectionPrefs = this.context.getSharedPreferences("health_connect_sync_v1", Context.MODE_PRIVATE)
    private val cache = DailyActivityCache(File(this.context.noBackupFilesDir, "daily_activity_v1.bin"))
    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableData = MutableStateFlow<WearDailySummary?>(null)
    val currentDataFlow: StateFlow<WearDailySummary?> = mutableData.asStateFlow()
    @Volatile private var foreground = false
    @Volatile private var generation = 0L
    private var polling: Job? = null
    private var health: WearDailySummary? = null
    private var lastAttemptAt = 0L
    private var lastAttemptDay: DailyActivityDay? = null
    private var lastReadPermissions: Set<String> = emptySet()
    @Volatile private var localData: Pair<List<Journey>, List<TrackPoint>>? = null

    init {
        // Room invalidation refreshes this input cache when journeys or GPS points change.
        scope.launch {
            combine(repository.journeys, repository.points) { journeys, points -> journeys to points }
                .collect { localData = it }
        }
    }

    fun setForeground(value: Boolean) {
        foreground = value
        polling?.cancel()
        polling = null
        if (value) polling = scope.launch {
            refresh()
            while (isActive && foreground) { delay(60_000); refresh() }
        }
    }

    private data class Access(val client: HealthConnectClient?, val permissions: Set<String>, val enabled: Boolean, val background: Boolean, val known: Boolean = true) {
        val knowledge: DailyAccessKnowledge get() = when {
            !known -> DailyAccessKnowledge.UNKNOWN
            !enabled -> DailyAccessKnowledge.DISABLED
            client == null -> DailyAccessKnowledge.UNAVAILABLE
            else -> DailyAccessKnowledge.AVAILABLE
        }
    }
    private suspend fun accessOrUnknown(): Access = try {
        withTimeoutOrNull(500) { access() } ?: Access(null, emptySet(), false, false, known = false)
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { Access(null, emptySet(), false, false, known = false) }
    private suspend fun access(): Access {
        val enabled = connectionPrefs.getBoolean("enabled", false)
        if (!enabled) return Access(null, emptySet(), false, false)
        if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return Access(null, emptySet(), enabled, false)
        val client = HealthConnectClient.getOrCreate(context)
        val permissions = client.permissionController.getGrantedPermissions()
        val background = BACKGROUND_HEALTH_PERMISSION in permissions && client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        return Access(client, permissions, enabled, background)
    }
    private fun empty(day: DailyActivityDay, now: Long, status: WearDailyHealthStatus = UNAVAILABLE) = WearDailySummary(
        day.localDate, day.zoneId, day.startAt, day.endAt, now, null, WearDailyJourneyTotal(), WearDailyJourneyTotal(), null,
        null, null, null, status, status, status)

    /** Fresh local totals, with the health cache's original check time. Never initiates health reads. */
    suspend fun snapshot(now: Long): WearDailySummary? = withContext(Dispatchers.IO) {
        mutex.withLock { snapshotLocked(now) }
    }
    private suspend fun snapshotLocked(now: Long, knownAccess: Access? = null): WearDailySummary {
        val day = DailyActivityRules.day(now)
        val stored = health?.takeIf { it.localDate == day.localDate && it.zoneId == day.zoneId && it.dayStartAt == day.startAt && it.checkedAt <= now }
            ?: cache.read(day, now)
        val access = knownAccess ?: accessOrUnknown()
        val projection = DailyActivityAccessPolicy.project(stored ?: empty(day, now), access.knowledge,
            stepsAllowed = stepsPermission in access.permissions, exerciseAllowed = exercisePermission in access.permissions,
            distanceAllowed = distancePermission in access.permissions)
        val safe = projection.visible
        // Only a confirmed access change may alter durable health data; transient failures just hide it.
        if (stored != null) projection.cacheReplacement?.let { replacement ->
            health = replacement
            runCatching { cache.write(replacement) }
        }
        val data = localData ?: run {
            val journeys = repository.allHealthJourneys()
            journeys to journeys.flatMap { repository.journeyPoints(it.id) }
        }
        val totals = DailyActivityRules.totals(data.first, data.second, day, now)
        return safe.copy(checkedAt = now, vehicle = totals.vehicle, recordedWalkingRunning = totals.walkingRunning, cycling = totals.cycling)
    }

    // Force re-evaluates state but never bypasses the provider read throttle.
    @Suppress("UNUSED_PARAMETER")
    suspend fun refresh(force: Boolean = false): Unit = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val day = DailyActivityRules.day(now)
            val access = accessOrUnknown()
            schedule(access)
            val epoch = generation
            val permissionSet = access.permissions.intersect(requestedPermissions)
            val readDue = lastAttemptDay != day || permissionSet != lastReadPermissions || now - lastAttemptAt !in 0 until 60_000
            if (access.enabled && access.client != null && (stepsPermission in permissionSet || exercisePermission in permissionSet) && (foreground || access.background) &&
                readDue) {
                lastAttemptAt = now
                lastAttemptDay = day
                lastReadPermissions = permissionSet
                val source = DailyActivitySource(access.client) {
                    check(epoch == generation && connectionPrefs.getBoolean("enabled", false) && (foreground || access.background)) { "Daily read stopped" }
                }
                val reading = if (now > day.startAt) readDailyHealth(source, DailyRange(day.startAt, now),
                    stepsPermission in access.permissions, exercisePermission in access.permissions, distancePermission in access.permissions) else DailyHealthReading()
                mutex.withLock {
                    if (epoch == generation && connectionPrefs.getBoolean("enabled", false)) {
                        health = empty(day, now).copy(healthCheckedAt = now, samsungSteps = reading.steps, samsungExerciseMillis = reading.exerciseMillis,
                            samsungExerciseMeters = reading.exerciseMeters, stepsStatus = reading.stepsStatus, exerciseStatus = reading.exerciseStatus, distanceStatus = reading.distanceStatus)
                        // Filesystem failures cannot disrupt the existing journey health sync.
                        runCatching { cache.write(health!!) }
                    }
                }
            }
            mutex.withLock { mutableData.value = snapshotLocked(System.currentTimeMillis(), access) }
        }
    }

    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        generation++
        mutex.withLock {
            health = null
            lastAttemptAt = 0
            lastAttemptDay = null
            cache.clear()
            mutableData.value = snapshotLocked(System.currentTimeMillis())
        }
    }

    private fun schedule(access: Access) {
        if (!access.known) return
        val work = WorkManager.getInstance(context)
        if (access.enabled && access.background && requestedPermissions.any { it in access.permissions }) {
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DailyActivityWorker>(15, TimeUnit.MINUTES).build())
        } else work.cancelUniqueWork(WORK_NAME)
    }

    companion object {
        internal const val WORK_NAME = "iz_daily_activity_read"
        internal val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        internal val exercisePermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        internal val distancePermission = HealthPermission.getReadPermission(DistanceRecord::class)
        val requestedPermissions = setOf(stepsPermission, exercisePermission, distancePermission)
        @Volatile private var instance: DailyActivityManager? = null
        fun get(context: Context): DailyActivityManager = instance ?: synchronized(this) {
            instance ?: DailyActivityManager(context).also { instance = it }
        }
    }
}

class DailyActivityWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        DailyActivityManager.get(applicationContext).refresh()
        Result.success()
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
}
