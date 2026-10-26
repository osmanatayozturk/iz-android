package org.iz.navigation.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.InvalidationTracker
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.IzApplication
import org.iz.navigation.data.*
import org.iz.navigation.integration.*
import org.iz.navigation.tracking.TrackingService
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

internal data class AnalysisInput(val journeys: List<Journey>, val points: List<TrackPoint>,
    val samples: List<JourneyHealthSample>, val syncs: List<JourneyHealthSync>, val watch: List<WatchHealthSample>)
/** Exact phone policy, shared with targeted host checks; charging never bypasses it. */
internal fun analysisPowerEligible(percent: Int?, active: Boolean): Boolean = !active && percent != null && percent in 20..100
private class AnalysisDeferred : RuntimeException()

/** Heavy derived work has one owner. Location, route/voice, weather, groups and raw watch writes stay live. */
internal class PostJourneyAnalysis private constructor(context: Context) {
    private val context = context.applicationContext
    private val repository = DiaryRepository(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Cache reads hold the asset monitor; queue repair outside it to avoid cache/control lock inversion.
    internal val cache = AnalysisCache(File(this.context.noBackupFilesDir, "post_journey_analysis")) { scope.launch { request() } }
    private val initialized = CompletableDeferred<Unit>()
    private val workMutex = Mutex()
    private val privacyMutex = Mutex()
    private val privacyChanges = AtomicInteger()
    private val scheduleMutex = Mutex()
    private val control = Any()
    private val generation = AtomicLong()
    private val recordingChanges = AtomicInteger()
    private val mutableState = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = mutableState.asStateFlow()
    @Volatile private var pending = true
    @Volatile private var metadataLoaded = false
    @Volatile private var recording = true
    @Volatile private var foreground = false
    @Volatile private var batteryPercent: Int? = null
    @Volatile private var processing = false
    @Volatile private var daily: JSONObject? = null
    private var lastAllowed = false
    private var failed = false

    private fun initialize() {
        scope.launch {
            try {
                val db = DiaryDatabase.get(context)
                db.invalidationTracker.addObserver(object : InvalidationTracker.Observer(
                    "journeys", "track_points", "journey_health_samples", "journey_health_sync", "watch_health_samples") {
                    override fun onInvalidated(tables: Set<String>) { request() }
                })
                val loaded = cache.load()
                // Reconcile once after process death, including a raw transaction whose invalidation
                // could not be delivered. Matching per-journey fingerprints reuse prepared results.
                mutableState.value = loaded.first.copy(pending = true)
                daily = loaded.second
                cache.pending(true)
                batteryPercent = PhoneBatteryReader.read(context).percent
                recording = repository.activeJourney() != null
                metadataLoaded = true
                ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        batteryPercent = PhoneBatteryReader.read(context).percent
                        updateGate()
                    }
                }, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
                launch {
                    repository.journeys.map { trips -> trips.any { it.endedAt == null } }.distinctUntilChanged().collect {
                        recording = it
                        PowerDiagnostics.state(it, (context as IzApplication).navigation.state.value.guidance)
                        updateGate()
                    }
                }
                launch {
                    (context as IzApplication).navigation.state.collect { value ->
                        PowerDiagnostics.state(recording, value.guidance)
                        updateGate()
                        PowerDiagnostics.sampleIfDue()
                    }
                }
                initialized.complete(Unit)
                updateGate()
                schedule(immediate = true)
            } catch (error: CancellationException) { initialized.cancel(error); throw error }
            catch (error: Exception) {
                mutableState.update { it.copy(phase = AnalysisPhase.FAILED, pending = true) }
                initialized.completeExceptionally(error)
            }
        }
    }
    /** Privacy writes never wait for workMutex: the worker may already be waiting for the HC mutex. */
    internal suspend fun <T> privacyChange(block: suspend () -> T): T {
        initialized.await()
        return privacyMutex.withLock {
            privacyChanges.incrementAndGet()
            generation.incrementAndGet()
            updateGate()
            try { block() }
            finally {
                generation.incrementAndGet()
                privacyChanges.decrementAndGet()
                updateGate()
                schedule()
            }
        }
    }
    /** Called inside the raw transaction; a checked cache failure must abort that transaction. */
    internal fun purgeJourneys(ids: Set<String>) {
        if (ids.isNotEmpty()) purgePrepared(ids)
    }
    internal fun purgeHealth(ids: Set<String>? = null) {
        if (ids == null) purgePrepared(health = true)
        else if (ids.isNotEmpty()) purgePrepared(ids, health = true)
    }
    internal fun purgeAll() = purgePrepared(all = true)
    private fun purgePrepared(ids: Set<String> = emptySet(), health: Boolean = false, all: Boolean = false) {
        check(privacyChanges.get() > 0) { "Privacy purge requires its mutation boundary" }
        var removed: Set<String>? = null
        try {
            // Existing cache monitor serializes AtomicFile completion with this deletion. Every
            // production writer also checks generation/gate inside that monitor before commit.
            removed = cache.purge(ids, health, all)
        } finally {
            synchronized(control) {
                generation.incrementAndGet()
                pending = true
                daily = null
                mutableState.update { previous ->
                    val retained = previous.entries.filter { (id, entry) ->
                        val liveHealth = health && ids.isEmpty() &&
                            (entry.health != JourneyHealthSummary(id) || entry.watch != JourneyHealthSummary(id))
                        val affected = all || id in ids || liveHealth || removed?.contains(AnalysisCache.digest(id)) == true
                        !affected
                    }
                    // A new reader identity disposes decoded chart/map copies and changes the
                    // tuple observed by Wear publication. A pending pulse alone may be conflated.
                    previous.copy(entries = retained, cacheRevision = "", pending = true,
                        phase = phase(), changed = System.currentTimeMillis())
                }
            }
        }
    }
    fun onRuntimeChanged() { updateGate() }
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) { batteryPercent = PhoneBatteryReader.read(context).percent; updateGate() }
    }
    fun canProcessNow(): Boolean {
        val nav = (context as IzApplication).navigation.state.value
        val active = !metadataLoaded || recording || recordingChanges.get() > 0 || privacyChanges.get() > 0 || TrackingService.isRunning ||
            nav.guidance || nav.recording || nav.locationActive || nav.loading || nav.sessionId != null
        return analysisPowerEligible(batteryPercent, active)
    }
    private fun phase(): AnalysisPhase {
        val nav = (context as IzApplication).navigation.state.value
        return when {
            !metadataLoaded || recording || recordingChanges.get() > 0 || privacyChanges.get() > 0 || TrackingService.isRunning ||
                nav.guidance || nav.recording || nav.locationActive || nav.loading || nav.sessionId != null -> AnalysisPhase.WAITING_END
            (batteryPercent ?: -1) < 20 -> AnalysisPhase.WAITING_BATTERY
            processing -> AnalysisPhase.RUNNING
            failed -> AnalysisPhase.FAILED
            pending -> AnalysisPhase.QUEUED
            else -> AnalysisPhase.READY
        }
    }
    /** Invalidates checkpoints before a new recorder row is committed, including a temporary row. */
    fun recordingWillChange() {
        recordingChanges.incrementAndGet()
        generation.incrementAndGet()
        updateGate()
    }
    fun recordingDidChange() {
        scope.launch {
            try { recording = repository.activeJourney() != null }
            finally { recordingChanges.decrementAndGet(); updateGate() }
        }
    }
    private fun updateGate() {
        val shouldSchedule = synchronized(control) {
            val allowed = canProcessNow()
            val resumed = allowed && !lastAllowed
            if (!allowed && lastAllowed) generation.incrementAndGet()
            lastAllowed = allowed
            mutableState.update { it.copy(phase = phase(), pending = pending) }
            PowerDiagnostics.analysis(pending, processing, mutableState.value.phase)
            pending && allowed && resumed && !processing
        }
        if (shouldSchedule) schedule(immediate = true)
    }
    fun request() {
        val first = synchronized(control) {
            generation.incrementAndGet()
            val wasPending = pending
            pending = true; failed = false
            mutableState.update { it.copy(pending = true, phase = phase()) }
            !wasPending
        }
        if (first) scope.launch {
            runCatching { cache.pending(true) }
            schedule(immediate = true)
        }
        PowerDiagnostics.analysis(true, processing)
    }
    private fun schedule(immediate: Boolean = false) {
        scope.launch { scheduleMutex.withLock {
            val work = WorkManager.getInstance(context)
            if (!pending) { work.cancelUniqueWork(PERIODIC); return@withLock }
            // WorkManager's minimum periodic interval is 15 minutes. This check exists only
            // while derived work is pending; the exact >=20 gate is enforced in this worker.
            // https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PostJourneyWorker>(15, TimeUnit.MINUTES).build())
            if (immediate && !processing && canProcessNow()) work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PostJourneyWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        } }
    }
    fun dailyTotals(day: DailyActivityDay): DailyJourneyTotals? {
        val result = if (!pending) AnalysisCache.daily(daily, day) else null
        if (result == null && !pending) request()
        return result
    }
    suspend fun runPending(): Boolean {
        initialized.await()
        return workMutex.withLock {
            batteryPercent = PhoneBatteryReader.read(context).percent
            if (!pending || !canProcessNow() || repository.activeJourney() != null) {
                updateGate(); schedule(); return@withLock true
            }
            val start = System.nanoTime()
            processing = true; failed = false; updateGate()
            PowerDiagnostics.event(PowerDiagnostics.Event.ANALYSIS_STARTED)
            try {
                // HC remains subject to its permissions, foreground/background policy and this gate.
                (context as IzApplication).healthManager.sync(background = !foreground)
                val token = generation.get()
                val coroutine = currentCoroutineContext()
                var lastBatteryCheck = android.os.SystemClock.elapsedRealtime()
                fun checkAllowed() {
                    // https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/ensure-active.html
                    coroutine.ensureActive()
                    val elapsed = android.os.SystemClock.elapsedRealtime()
                    if (elapsed - lastBatteryCheck >= 1_000) {
                        batteryPercent = PhoneBatteryReader.read(context).percent
                        lastBatteryCheck = elapsed
                    }
                    if (!canProcessNow() || generation.get() != token) throw AnalysisDeferred()
                    PowerDiagnostics.sampleIfDue()
                }
                checkAllowed()
                val now = System.currentTimeMillis()
                val input = repository.analysisInput(::checkAllowed)
                checkAllowed()
                if (input.journeys.any { it.endedAt == null && !DiaryRules.isExpired(it, now) }) throw AnalysisDeferred()
                val journeys = input.journeys.filter { it.endedAt != null && it.status == JourneyStatus.CONFIRMED && !DiaryRules.isExpired(it, now) }
                val ids = journeys.map { it.id }.toSet()
                fun <T> group(values: List<T>, journeyId: (T) -> String): Map<String, List<T>> {
                    val result = linkedMapOf<String, MutableList<T>>()
                    values.forEachIndexed { index, value ->
                        if (index % 256 == 0) checkAllowed()
                        val id = journeyId(value)
                        if (id in ids) result.getOrPut(id) { mutableListOf() }.add(value)
                    }
                    return result
                }
                val points = group(input.points) { it.journeyId }
                val samples = group(input.samples) { it.journeyId }
                val syncs = group(input.syncs) { it.journeyId }
                val watch = group(input.watch) { it.journeyId }
                val entries = linkedMapOf<String, AnalysisEntry>()
                for (journey in journeys) {
                    checkAllowed()
                    val track = points[journey.id].orEmpty()
                    val healthSamples = samples[journey.id].orEmpty()
                    val checks = syncs[journey.id].orEmpty()
                    val direct = watch[journey.id].orEmpty()
                    val hash = MessageDigest.getInstance("SHA-256")
                    fun add(value: String) { hash.update(value.toByteArray(Charsets.UTF_8)); hash.update(0.toByte()) }
                    // Ignore display-only title/note edits. Every raw GPS/HC/direct-watch field
                    // and recording metadata affects reuse, even if sample counts are unchanged.
                    add("analysis-v2")
                    add(listOf(journey.id, journey.transport, journey.status, journey.startedAt, journey.endedAt,
                        journey.expiresAt, journey.interrupted, journey.stepCount).joinToString("|"))
                    var count = 0
                    fun <T> fingerprint(values: List<T>) {
                        values.forEach { if (++count % 256 == 0) checkAllowed(); add(it.toString()) }
                    }
                    fingerprint(track); fingerprint(healthSamples); fingerprint(checks); fingerprint(direct)
                    val fingerprint = hash.digest().joinToString("") { "%02x".format(it) }
                    val previous = cache.entry(journey.id, fingerprint)
                    if (previous != null && cache.hasTrail(journey.id, fingerprint) && cache.hasHealthSamples(journey.id, fingerprint)) {
                        entries[journey.id] = previous; continue
                    }
                    val stats = JourneyStatistics.calculate(journey, track, now, ::checkAllowed)
                    val health = HealthRules.summarize(journey, healthSamples, checks, now, ::checkAllowed)
                    val watchHealth = WatchHealthRules.summarize(journey, direct, now, ::checkAllowed)
                    val chartSamples = HealthRules.observedHeartRateSamples(journey, healthSamples, now, ::checkAllowed)
                    checkAllowed()
                    val trail = recordedSpeedTrail(track, ::checkAllowed)
                    cache.saveTrail(journey.id, fingerprint, trail, ::checkAllowed)
                    cache.saveHealthSamples(journey.id, fingerprint, chartSamples, ::checkAllowed)
                    val entry = AnalysisEntry(fingerprint, stats, health, watchHealth, journey)
                    cache.saveEntry(journey.id, entry, ::checkAllowed)
                    entries[journey.id] = entry
                }
                val revision = AnalysisCache.digest(entries.entries.sortedBy { it.key }.joinToString { it.key + ":" + it.value.fingerprint })
                val completedPoints = buildList {
                    points.values.forEach { values -> values.forEachIndexed { index, point ->
                        if (index % 256 == 0) checkAllowed()
                        add(point)
                    } }
                }
                for (mode in listOf<Transport?>(null) + Transport.entries) {
                    checkAllowed()
                    val filter = mode?.name ?: "ALL"
                    if (cache.hasHeatmap(revision, filter)) continue
                    val data = buildHeatmapData(journeys, completedPoints, mode, now, ::checkAllowed)
                    cache.saveHeatmap(revision, filter, prepareHeatmapRender(data, ::checkAllowed), ::checkAllowed)
                }
                checkAllowed()
                val day = DailyActivityRules.day(now)
                val totals = DailyActivityRules.totals(journeys, completedPoints, day, now, ::checkAllowed)
                val dailyResult = AnalysisCache.daily(day, totals)
                checkAllowed()
                if (repository.activeJourney() != null) throw AnalysisDeferred()
                cache.cleanup(entries, revision, ::checkAllowed)
                val changed = System.currentTimeMillis()
                cache.save(entries, revision, dailyResult, changed, ::checkAllowed)
                checkAllowed()
                synchronized(control) {
                    checkAllowed()
                    // A late invalidation can only set pending back to true after this commit.
                    cache.pending(false)
                    daily = dailyResult; pending = false
                    mutableState.value = AnalysisState(phase(), false, entries, revision, changed)
                }
                PowerDiagnostics.event(PowerDiagnostics.Event.ANALYSIS_FINISHED)
                true
            } catch (_: AnalysisDeferred) {
                PowerDiagnostics.event(PowerDiagnostics.Event.ANALYSIS_PAUSED)
                // Retry only a changed input while still eligible (WorkManager's 30s backoff).
                // A closed recording/battery gate succeeds and waits for the durable queue or gate transition.
                !canProcessNow()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                failed = true
                synchronized(control) { pending = true }
                runCatching { cache.pending(true) }
                mutableState.update { it.copy(phase = AnalysisPhase.FAILED, pending = true) }
                false
            } finally {
                processing = false
                PowerDiagnostics.count(PowerDiagnostics.Metric.ANALYSIS, System.nanoTime() - start)
                PowerDiagnostics.analysis(pending, false)
                updateGate()
                schedule()
            }
        }
    }
    companion object {
        private const val PERIODIC = "iz-pending-post-journey-check"
        private const val IMMEDIATE = "iz-post-journey-process"
        @Volatile private var instance: PostJourneyAnalysis? = null
        fun get(context: Context): PostJourneyAnalysis = instance ?: synchronized(this) {
            instance ?: PostJourneyAnalysis(context).also { instance = it; it.initialize() }
        }
    }
}

class PostJourneyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (PostJourneyAnalysis.get(applicationContext).runPending()) Result.success() else Result.retry()
}
