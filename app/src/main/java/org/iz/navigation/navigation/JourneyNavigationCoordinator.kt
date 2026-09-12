package org.iz.navigation.navigation

import android.content.Context
import org.iz.navigation.data.*
import org.iz.navigation.weather.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.iz.navigation.speed.*

/** Side effects are isolated so concurrent car/phone/watch commands can be tested without a host. */
interface NavigationRuntime {
    val journeys: Flow<List<Journey>>
    suspend fun activeJourney(): Journey?
    suspend fun start(transport: Transport, stillCurrent: () -> Boolean = { true }): String
    suspend fun confirm(id: String, transport: Transport)
    suspend fun finish(id: String)
    suspend fun stopRecording(id: String) = finish(id)
    suspend fun discardCandidate(id: String) = finish(id)
    /** The Android adapter serializes the decision and commit with every recorder writer. */
    suspend fun prepareNoRecordSession(stillCurrent: () -> Boolean,
        onDiscard: (Journey) -> Unit, commit: () -> Unit) {
        val existing = activeJourney()
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        require(existing == null || existing.status == JourneyStatus.TEMPORARY ||
            recordingId() != existing.id && !startPending(existing.id)) {
            "Kaydet kapalı başlatmak için önce mevcut kaydı durdur."
        }
        if (existing != null) {
            onDiscard(existing)
            if (existing.status == JourneyStatus.TEMPORARY) discardCandidate(existing.id) else interrupt(existing.id)
        }
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        commit()
    }
    fun setLocationSession(id: String?, transport: Transport?, highFrequency: Boolean, suppressAutomatic: Boolean) {}
    fun setPreparingNoRecord(enabled: Boolean) {}
    fun locationActive(): Boolean = recordingId() != null
    suspend fun interrupt(id: String)
    fun startPending(id: String): Boolean = false
    fun recordingId(): String?
    fun setHighFrequency(journeyId: String?)
    suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute
    suspend fun locate(): NavigationFix
    fun trafficRefreshEnabled(route: PlannedRoute): Boolean = route.provider == RouteProvider.TOMTOM
    val roadSpeedMonitoring: Boolean get() = false
    fun roadSpeedState(state: NavigationState, now: Long): RoadSpeedState = RoadSpeedState(
        ownKmh = if (state.gpsStale) null else ownSpeedKmh(state.fix, now),
        visible = state.sessionId != null && speedMode(state.sessionTransport))
    suspend fun refreshRoadSpeed() {}
    fun render(state: NavigationState)
    fun speak(text: String)
    fun cancelSpeech()
}

/** Phone-owned route and recorder identity. A car Session owns only a display subscription. */
class JourneyNavigationCoordinator(
    private val runtime: NavigationRuntime,
    private val cache: NavigationRouteCache?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    constructor(context: Context) : this(AndroidNavigationRuntime(context.applicationContext),
        NavigationRouteCache(java.io.File(context.applicationContext.noBackupFilesDir, "navigation")))

    private val mutableState = MutableStateFlow(NavigationState())
    val state = mutableState.asStateFlow()
    private val mutableSimulationPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val simulationPoints = mutableSimulationPoints.asStateFlow()
    private val commands = Mutex()
    private val cacheLock = Mutex()
    private val fixes = Channel<Pair<String, NavigationFix>>(Channel.CONFLATED)
    @Volatile private var epoch = 0L
    private var engine: NavigationEngine? = null
    private val cues = NavigationCuePolicy()
    private var rerouteJob: Job? = null
    private var simulationJob: Job? = null
    private var speedRefreshJob: Job? = null
    private var lastSpeedRefresh = Long.MIN_VALUE
    private var simulationEnabled = false
    private var freeDrive = false
    private var stoppingRecordingId: String? = null
    private val retiredJourneys = linkedSetOf<String>()

    private fun retireJourney(id: String) {
        retiredJourneys += id
        while (retiredJourneys.size > 64) retiredJourneys.remove(retiredJourneys.first())
    }
    private var lastRerouteAttempt: Long? = null
    private var lastTrafficCheckAt: Long? = null
    private var cachedRoute: PlannedRoute? = null
    val lastRoute: PlannedRoute? get() = cachedRoute

    init {
        val hydrationEpoch = epoch
        scope.launch {
            val restored = withContext(Dispatchers.IO) { cacheLock.withLock { cache?.read() } }
            if (hydrationEpoch == epoch && cachedRoute == null) cachedRoute = restored
        }
        scope.launch {
            runtime.journeys.collect { values ->
                if (mutableState.value.simulation) return@collect
                val observationEpoch = epoch
                val observed = values.firstOrNull { it.endedAt == null && !it.interrupted }
                val known = mutableState.value.journey
                // Room can deliver a snapshot captured before an explicit confirmation/start.
                // Recheck any identity or metadata change before invalidating live guidance.
                val changed = known != null && (observed?.id != known.id ||
                    observed.transport != known.transport || observed.status != known.status)
                val current = (if (changed)
                    runtime.activeJourney()?.takeUnless { it.interrupted || it.endedAt != null } else observed)
                    ?.takeUnless { it.id in retiredJourneys }
                if (observationEpoch != epoch || mutableState.value.simulation) return@collect
                val before = mutableState.value
                // Explicit external starts may attach a compatible confirmed recorder.
                // Candidates and retired snapshots cannot change an opt-out.
                if (before.sessionId != null && before.journey == null) {
                    if (current == null || current.status != JourneyStatus.CONFIRMED ||
                        runtime.recordingId() != current.id || current.transport != before.sessionTransport) return@collect
                    publish(before.copy(journey = current, recording = true))
                    return@collect
                }
                if (before.journey?.id != null && current?.id != before.journey.id && stoppingRecordingId != before.journey.id) {
                    endLocalSession()
                } else if (before.route != null && current != null && before.journey != null &&
                    (current.transport != before.journey.transport || current.status != JourneyStatus.CONFIRMED)) clearRoute()
                publish(mutableState.value.copy(journey = current,
                    sessionId = mutableState.value.sessionId ?: current?.id?.takeIf { runtime.recordingId() == it },
                    sessionTransport = current?.transport ?: mutableState.value.sessionTransport,
                    recording = current != null && runtime.recordingId() == current.id,
                    fix = mutableState.value.fix.takeIf { before.journey?.id == current?.id }))
            }
        }
        scope.launch { for ((id, fix) in fixes) accept(id, fix) }
        scope.launch {
            while (isActive) {
                delay(1000)
                val before = mutableState.value
                val fresh = before.fix?.let { fresh(it, clock()) } == true
                if (!fresh && !before.gpsStale) runtime.cancelSpeech()
                val alive = if (before.simulation) before.recording else before.journey?.id?.let { runtime.recordingId() == it } == true
                if (before.recording && !alive && stoppingRecordingId != before.journey?.id) {
                    clearRoute()
                    publish(mutableState.value.copy(sharingLocation = false))
                }
                publish(mutableState.value.copy(recording = alive, gpsStale = !fresh,
                    sessionId = mutableState.value.sessionId ?: before.journey?.id?.takeIf { alive },
                    sessionTransport = before.journey?.transport ?: mutableState.value.sessionTransport,
                    locationActive = !before.simulation && runtime.locationActive()))
                if (fresh && mutableState.value.progress?.offRoute == true) requestReroute()
                else if (fresh) requestTrafficRefresh()
            }
        }
    }

    suspend fun startFreeDrive(transport: Transport, recordJourney: Boolean = true): String = commands.withLock {
        require(transport != Transport.UNKNOWN) { "Yolculuk türünü seç." }
        check(!simulationEnabled && !mutableState.value.simulation) { "Önce deneme sürüşünü bitir." }
        val id = ensureSession(transport, recordJourney)
        if (mutableState.value.route == null) freeDrive = true
        publish(mutableState.value)
        id
    }

    private suspend fun ensureSession(transport: Transport, recordJourney: Boolean): String {
        val prior = mutableState.value
        require(prior.sessionId == null || prior.sessionTransport == transport ||
            prior.journey?.let { it.status == JourneyStatus.TEMPORARY && it.transport == Transport.UNKNOWN } == true) {
            "Açık yolculuğun türü farklı. Önce mevcut yolculuğu bitir."
        }
        if (!recordJourney) runtime.setPreparingNoRecord(true)
        try {
            val token = epoch
            if (!recordJourney) {
                runtime.prepareNoRecordSession(stillCurrent = { token == epoch }, onDiscard = { existing ->
                    retireJourney(existing.id)
                    publish(mutableState.value.copy(journey = null, recording = false,
                        sessionId = mutableState.value.sessionId.takeUnless { it == existing.id }))
                }, commit = {
                    val id = mutableState.value.sessionId ?: UUID.randomUUID().toString()
                    publish(mutableState.value.copy(sessionId = id, sessionTransport = transport,
                        journey = null, recording = false, message = null))
                })
                return requireNotNull(mutableState.value.sessionId)
            }
            val journey = ensureRecording(transport)
            val id = mutableState.value.sessionId ?: journey.id
            publish(mutableState.value.copy(sessionId = id, sessionTransport = transport,
                journey = journey, recording = true, message = null))
            return id
        } finally { if (!recordJourney) runtime.setPreparingNoRecord(false) }
    }

    suspend fun previewRoute(stops: List<RouteStop>, transport: Transport): PlannedRoute {
        require(stops.size in 2..6)
        val token = epoch
        publish(mutableState.value.copy(loading = true, message = null))
        return try {
            val route = runtime.plan(stops, transport)
            check(token == epoch) { "Yolculuk durumu değişti; rotayı yeniden seç." }
            route
        } finally {
            if (token == epoch) publish(mutableState.value.copy(loading = false))
        }
    }

    suspend fun startGuidance(route: PlannedRoute, recordJourney: Boolean = true): String = activate(route, true, recordJourney)
    suspend fun activateRoute(route: PlannedRoute): String = activate(route, false, true)

    private suspend fun activate(route: PlannedRoute, guidance: Boolean, recordJourney: Boolean): String = commands.withLock {
        require(route.vertices.size >= 2 && route.durationSeconds > 0) { "Geçerli bir rota hesapla." }
        require(!guidance || route.maneuvers.isNotEmpty()) { "Bu rota dönüş talimatı içermiyor. Yeniden hesapla." }
        val simulated = simulationEnabled && guidance
        check(!mutableState.value.simulation || simulated) { "Önce deneme sürüşünü bitir." }
        check(!simulationEnabled || guidance) { "Deneme modunda gerçek kayıt başlatılamaz." }
        val activationEpoch = epoch
        check(!simulated || runtime.activeJourney() == null) { "Deneme sürüşü için önce gerçek yolculuğu bitir." }
        check(activationEpoch == epoch) { "Başlatma işlemi iptal edildi." }
        val sessionId = if (simulated) "simulation-${UUID.randomUUID()}" else ensureSession(route.transport, recordJourney)
        val journey = if (simulated) Journey(id = sessionId, transport = route.transport, startedAt = clock()) else mutableState.value.journey
        clearRoute()
        freeDrive = false
        engine = NavigationEngine(route)
        lastTrafficCheckAt = clock()
        cues.reset()
        publish(mutableState.value.copy(journey = journey, recording = journey != null, route = route,
            sessionId = sessionId, sessionTransport = route.transport,
            guidance = guidance, arrived = false, progress = null, routeRevision = epoch,
            loading = false, message = if (simulated) "Deneme sürüşü · günlüğe kaydedilmez" else null,
            simulation = simulated))
        if (!simulated) saveRoute(route)
        if (simulated) startSimulation(route, requireNotNull(journey))
        else mutableState.value.fix?.takeIf { fresh(it, clock()) }?.let { fixes.trySend(sessionId to it) }
        sessionId
    }

    private suspend fun ensureRecording(transport: Transport): Journey {
        val token = epoch
        var before = runtime.activeJourney()
        check(token == epoch) { "Başlatma işlemi iptal edildi." }
        if (before != null && runtime.recordingId() != before.id && !runtime.startPending(before.id)) {
            val orphanId = before.id
            retireJourney(orphanId)
            // This deliberate interruption must not invalidate its own asynchronous Start.
            publish(mutableState.value.copy(journey = null, recording = false,
                sessionId = mutableState.value.sessionId.takeUnless { it == orphanId }))
            runtime.interrupt(orphanId)
            check(token == epoch) { "Başlatma işlemi iptal edildi." }
            before = null
        }
        if (before != null) {
            require(before.transport == transport || before.status == JourneyStatus.TEMPORARY && before.transport == Transport.UNKNOWN) {
                "Açık yolculuğun türü farklı. Önce mevcut yolculuğu bitir veya türünü telefondan değiştir."
            }
            require(!before.interrupted) { "Kesilen yolculuğu telefondan kontrol et." }
            if (before.status == JourneyStatus.TEMPORARY) runtime.confirm(before.id, transport)
        }
        check(token == epoch) { "Başlatma işlemi iptal edildi." }
        val id = before?.id ?: runtime.start(transport) { token == epoch }
        try {
            withTimeout(8000) {
                while (runtime.recordingId() != id) {
                    check(token == epoch) { "Başlatma işlemi iptal edildi." }
                    check(runtime.activeJourney()?.id == id) { "Yolculuk başlatılamadı." }
                    delay(100)
                }
            }
        } catch (error: TimeoutCancellationException) {
            if (before == null) runtime.interrupt(id)
            throw IllegalStateException("Konum servisi başlamadı. Telefonun konum izinlerini kontrol et.", error)
        }
        check(token == epoch) { "Yolculuk durumu değişti." }
        val actual = runtime.activeJourney()
        check(token == epoch) { "Yolculuk durumu değişti." }
        check(actual?.id == id && actual.status == JourneyStatus.CONFIRMED) { "Yolculuk artık açık değil." }
        publish(mutableState.value.copy(journey = actual, recording = true,
            fix = mutableState.value.fix.takeIf { mutableState.value.journey?.id == id }, message = null))
        return actual
    }

    fun stopGuidance() { scope.launch {
        clearRoute()
        releaseIfIdle()
    } }

    suspend fun stopRecording(expectedJourneyId: String) = commands.withLock {
        val before = mutableState.value
        if (before.simulation || before.journey?.id != expectedJourneyId) return@withLock
        stoppingRecordingId = expectedJourneyId
        retireJourney(expectedJourneyId)
        try {
            // Clear ownership before Room can emit the finished diary row.
            publish(before.copy(journey = null, recording = false))
            runtime.stopRecording(expectedJourneyId)
            if (before.route == null) freeDrive = false
            releaseIfIdle()
        } catch (error: Exception) {
            retiredJourneys.remove(expectedJourneyId)
            publish(before)
            throw error
        } finally { stoppingRecordingId = null }
    }

    suspend fun setSharingLocation(enabled: Boolean, expectedSessionId: String? = null) = commands.withLock {
        val before = mutableState.value
        if (expectedSessionId != null && before.sessionId != expectedSessionId) return@withLock
        if (enabled) check(before.sessionId != null && !before.simulation) { "Konum paylaşmak için canlı bir yolculuk başlat." }
        publish(before.copy(sharingLocation = enabled))
        releaseIfIdle()
    }

    suspend fun finishSession(expectedSessionId: String) = commands.withLock {
        finishSessionLocked(expectedSessionId)
    }

    private suspend fun finishSessionLocked(expectedSessionId: String) {
        val before = mutableState.value
        if (before.sessionId != expectedSessionId) return
        before.journey?.id?.let(::retireJourney)
        endLocalSession()
        if (!before.simulation) before.journey?.id?.let { runtime.finish(it) }
    }

    suspend fun finishJourney(expectedJourneyId: String) = commands.withLock {
        val before = mutableState.value
        if (before.journey?.id == expectedJourneyId) before.sessionId?.let { finishSessionLocked(it) }
        else if (before.sessionId == null && runtime.activeJourney()?.id == expectedJourneyId) runtime.finish(expectedJourneyId)
    }

    private fun releaseIfIdle() {
        val before = mutableState.value
        if (before.sessionId != null && !before.recording && before.route == null && !before.sharingLocation && !freeDrive) endLocalSession()
    }

    private fun endLocalSession() {
        clearRoute()
        freeDrive = false
        simulationEnabled = false
        mutableSimulationPoints.value = emptyList()
        publish(NavigationState(muted = mutableState.value.muted, routeRevision = epoch))
    }

    fun setMuted(muted: Boolean) { scope.launch {
        if (muted) runtime.cancelSpeech()
        publish(mutableState.value.copy(muted = muted))
    } }

    fun onAcceptedLocation(journeyId: String, fix: NavigationFix) { fixes.trySend(journeyId to fix) }

    fun onTrackingStopped(journeyId: String?) { scope.launch {
        if (!mutableState.value.simulation && (journeyId == null || mutableState.value.journey?.id == journeyId)) {
            if (stoppingRecordingId != journeyId && mutableState.value.journey != null) endLocalSession()
            publish(mutableState.value.copy(recording = false, gpsStale = true, locationActive = false))
        }
    } }

    fun invalidateAfterDiaryReplacement() {
        // Invalidate synchronously before any already-completed HTTP result can publish.
        epoch++
        scope.launch {
            clearRoute()
            simulationEnabled = false
            cachedRoute = null
            mutableSimulationPoints.value = emptyList()
            publish(NavigationState(routeRevision = epoch))
            withContext(Dispatchers.IO) { cacheLock.withLock { cache?.clear() } }
        }
    }

    suspend fun currentLocation(): NavigationFix {
        mutableState.value.fix?.takeIf { fresh(it, clock()) }?.let { return it }
        val token = epoch
        return runtime.locate().also {
            require(fresh(it, clock())) { "Güncel ve hassas konum bekleniyor." }
            if (token == epoch && mutableState.value.sessionId == null) publish(mutableState.value.copy(fix = it, gpsStale = false))
        }
    }

    private suspend fun accept(id: String, fix: NavigationFix) {
        if (id in retiredJourneys && id != mutableState.value.sessionId) return
        val now = clock()
        var before = mutableState.value
        if (!before.simulation && before.sessionId != id && before.journey?.id != id) {
            if (before.sessionId != null) return
            val observationEpoch = epoch
            val trip = runtime.activeJourney()?.takeIf { it.id == id } ?: return
            if (observationEpoch != epoch || runtime.recordingId() != id) return
            clearRoute()
            publish(mutableState.value.copy(journey = trip, sessionId = id, sessionTransport = trip.transport, recording = runtime.recordingId() == id))
            before = mutableState.value
        }
        if (before.sessionId != id && before.journey?.id != id || !fresh(fix, now)) return
        if (before.fix?.let { fix.recordedAt <= it.recordedAt } == true) return
        val currentEngine = engine
        val token = epoch
        val progress = if (currentEngine != null) withContext(Dispatchers.Default) { currentEngine.update(fix, now) } else null
        if (token != epoch || mutableState.value.sessionId != before.sessionId) return
        val arrived = progress?.arrived == true
        val next = mutableState.value.copy(fix = fix, gpsStale = false, progress = progress ?: before.progress,
            guidance = before.guidance && !arrived, arrived = arrived || before.arrived,
            message = when {
                arrived -> if (before.recording) "Hedefe vardın. Yolculuk kaydı devam ediyor." else "Hedefe vardın."
                progress?.offRoute == true -> "Rota dışındasın. Yeni rota hesaplanıyor."
                before.simulation -> "Deneme sürüşü · günlüğe kaydedilmez"
                else -> null
            })
        publish(next)
        if (arrived && !before.arrived && before.guidance && !before.muted) {
            runtime.cancelSpeech(); runtime.speak(if (before.recording) "Hedefe vardın. Yolculuk kaydı devam ediyor." else "Hedefe vardın.")
        } else if (before.guidance && progress != null && !progress.offRoute && !before.muted) {
            before.route?.let { cues.cue(it, progress, fix) }?.let(runtime::speak)
        } else if (progress?.offRoute == true) runtime.cancelSpeech()
        if (progress?.offRoute == true && !arrived) requestReroute()
    }

    private fun requestTrafficRefresh() {
        val before = mutableState.value
        val route = before.route ?: return
        if (!before.guidance || before.sessionId == null || before.simulation || before.arrived || before.gpsStale) return
        val now = clock()
        if (now - (lastTrafficCheckAt ?: route.createdAt) < 120_000L) return
        lastTrafficCheckAt = now
        if (runtime.trafficRefreshEnabled(route)) requestReroute(trafficRefresh = true)
    }

    private fun requestReroute(trafficRefresh: Boolean = false) {
        val before = mutableState.value
        val route = before.route ?: return
        val fix = before.fix ?: return
        val remaining = engine?.remainingStops().orEmpty()
        if (before.simulation || before.arrived || before.sessionId == null || !fresh(fix, clock()) || remaining.isEmpty() || rerouteJob?.isActive == true) return
        val now = clock()
        if (lastRerouteAttempt?.let { now - it in 0 until 15_000 } == true) return
        lastRerouteAttempt = now
        val token = epoch
        publish(before.copy(loading = true))
        rerouteJob = scope.launch {
            try {
                val replacement = runtime.plan(listOf(RouteStop("Mevcut konum", fix.coordinate)) + remaining, route.transport)
                val activeJourney = runtime.activeJourney()
                if (token != epoch || mutableState.value.route?.id != route.id || mutableState.value.sessionId != before.sessionId ||
                    before.journey != null && activeJourney?.id != before.journey.id) return@launch
                check(!before.guidance || replacement.maneuvers.isNotEmpty()) { "Dönüş talimatı alınamadı." }
                epoch++
                engine = NavigationEngine(replacement); cues.reset(); runtime.cancelSpeech()
                publish(mutableState.value.copy(route = replacement, routeRevision = epoch, progress = null, loading = false, message = null))
                lastTrafficCheckAt = clock()
                saveRoute(replacement)
                mutableState.value.fix?.let { latest ->
                    val updated = withContext(Dispatchers.Default) { engine?.update(latest, clock()) }
                    if (mutableState.value.route?.id == replacement.id) publish(mutableState.value.copy(progress = updated))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == epoch) publish(mutableState.value.copy(loading = false,
                    message = if (trafficRefresh) "Trafik güncellenemedi. Mevcut rota korunuyor; son trafik bilgisi eski olabilir."
                        else "Yeni rota alınamadı. Bağlantı bekleniyor. ${error.message.orEmpty().take(120)}"))
            } finally {
                if (mutableState.value.route?.id == route.id) publish(mutableState.value.copy(loading = false))
            }
        }
    }

    private fun saveRoute(route: PlannedRoute) {
        val token = epoch
        cachedRoute = route
        scope.launch(Dispatchers.IO) {
            cacheLock.withLock { if (token == epoch) runCatching { cache?.write(route) } }
        }
    }

    private fun clearRoute() {
        epoch++
        rerouteJob?.cancel(); rerouteJob = null
        simulationJob?.cancel(); simulationJob = null
        engine = null; cues.reset(); lastRerouteAttempt = null; lastTrafficCheckAt = null
        runtime.cancelSpeech()
        publish(mutableState.value.copy(route = null, guidance = false, arrived = false, progress = null,
            loading = false, routeRevision = epoch, message = null))
    }

    private fun publish(value: NavigationState, refreshSpeed: Boolean = true) {
        val rendered = value.copy(roadSpeed = runtime.roadSpeedState(value, clock()))
        mutableState.value = rendered
        runtime.setHighFrequency(value.journey?.id.takeIf { value.guidance && value.recording && !value.simulation })
        runtime.setLocationSession(value.sessionId.takeIf { !value.simulation }, value.sessionTransport,
            value.guidance, value.sessionId != null && !value.recording && !value.simulation)
        // Display/notification failures cannot take down the recorder's process or state collector.
        runCatching { runtime.render(rendered) }
        val eligible = value.sessionId != null && speedMode(value.sessionTransport) && !value.simulation && !value.gpsStale
        if (!eligible) { speedRefreshJob?.cancel(); speedRefreshJob = null }
        else if (refreshSpeed && runtime.roadSpeedMonitoring && speedRefreshJob?.isActive != true &&
            (lastSpeedRefresh == Long.MIN_VALUE || clock() - lastSpeedRefresh >= 1_000)) {
            lastSpeedRefresh = clock()
            speedRefreshJob = scope.launch {
                try { runtime.refreshRoadSpeed() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A speed provider cannot interrupt navigation. */ }
                publish(mutableState.value, refreshSpeed = false)
            }
        }
    }

    fun enableSimulation() { simulationEnabled = true }
    fun stopSimulation() { scope.launch {
        simulationEnabled = false
        if (mutableState.value.simulation) {
            clearRoute(); mutableSimulationPoints.value = emptyList()
            publish(NavigationState(routeRevision = epoch))
        }
    } }

    private fun startSimulation(route: PlannedRoute, journey: Journey) {
        mutableSimulationPoints.value = emptyList()
        simulationJob = scope.launch {
            var seconds = 0.0
            while (isActive && mutableState.value.simulation && !mutableState.value.arrived) {
                val segment = route.vertices.indexOfLast { it.elapsedSeconds <= seconds }.coerceIn(0, route.vertices.lastIndex - 1)
                val a = route.vertices[segment]; val b = route.vertices[segment + 1]
                val ratio = ((seconds - a.elapsedSeconds) / (b.elapsedSeconds - a.elapsedSeconds).coerceAtLeast(.001)).coerceIn(0.0, 1.0)
                val coordinate = WeatherCoordinate(a.coordinate.latitude + (b.coordinate.latitude - a.coordinate.latitude) * ratio,
                    a.coordinate.longitude + (b.coordinate.longitude - a.coordinate.longitude) * ratio)
                val now = clock()
                val fix = NavigationFix(coordinate, now, 3f, 12f)
                mutableSimulationPoints.value = (mutableSimulationPoints.value + TrackPoint(journeyId = journey.id,
                    latitude = coordinate.latitude, longitude = coordinate.longitude, recordedAt = now, accuracy = 3f, speed = 12f)).takeLast(10_000)
                fixes.send(journey.id to fix)
                seconds = (seconds + 1).coerceAtMost(route.durationSeconds)
                delay(1000)
            }
        }
    }

    companion object {
        fun fresh(fix: NavigationFix, now: Long): Boolean =
            fix.coordinate.latitude.isFinite() && fix.coordinate.latitude in -90.0..90.0 &&
            fix.coordinate.longitude.isFinite() && fix.coordinate.longitude in -180.0..180.0 &&
            now - fix.recordedAt in 0..10_000 &&
            fix.accuracyMeters.isFinite() && fix.accuracyMeters in 0f..50f
    }
}
