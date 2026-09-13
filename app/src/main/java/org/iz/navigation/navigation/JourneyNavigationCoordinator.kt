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
import org.iz.navigation.gpx.*

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
    /** Android commits under the recorder mutex; this default supports non-Android runtimes. */
    suspend fun prepareTrackFollowSession(transport: Transport, stillCurrent: () -> Boolean,
        onDiscard: (Journey) -> Unit, commit: (Journey?) -> Unit) {
        val existing = activeJourney()
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        val reusable = trackRecordingToReuse(existing, transport, recordingId(), existing?.let { startPending(it.id) } == true)
        if (existing != null && reusable == null) {
            if (existing.status == JourneyStatus.TEMPORARY) discardCandidate(existing.id) else interrupt(existing.id)
            onDiscard(existing)
        }
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        commit(reusable)
    }
    fun setLocationSession(id: String?, transport: Transport?, highFrequency: Boolean, suppressAutomatic: Boolean) {}
    fun setPreparingNoRecord(enabled: Boolean) {}
    fun locationActive(): Boolean = recordingId() != null
    suspend fun interrupt(id: String)
    fun startPending(id: String): Boolean = false
    fun recordingId(): String?
    fun setHighFrequency(journeyId: String?)
    suspend fun plan(stops: List<RouteStop>, transport: Transport): PlannedRoute
    suspend fun plan(stops: List<RouteStop>, transport: Transport, preferences: RoutePreferences,
        travelSpeedKmh: Double?): PlannedRoute = plan(stops, transport)
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

/** A real pending manual start is never discarded as an automatic candidate or orphan. */
internal fun trackRecordingToReuse(existing: Journey?, transport: Transport, recordingId: String?, pending: Boolean): Journey? {
    require(!pending) { "Kaydın başlamasını bekle ve GPX takibini tekrar başlat." }
    val reusable = existing?.takeIf { it.status == JourneyStatus.CONFIRMED && it.id == recordingId &&
        !it.interrupted && it.endedAt == null }
    require(reusable == null || reusable.transport == transport) { "Açık kaydın yolculuk türü farklı. Önce mevcut kaydı durdur." }
    return reusable
}

/** Phone-owned route and recorder identity. A car Session owns only a display subscription. */
class JourneyNavigationCoordinator(
    private val runtime: NavigationRuntime,
    private val cache: NavigationRouteCache?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val trackFollowStore: TrackFollowSessionStore? = null,
) {
    constructor(context: Context) : this(AndroidNavigationRuntime(context.applicationContext),
        NavigationRouteCache(java.io.File(context.applicationContext.noBackupFilesDir, "navigation")),
        trackFollowStore = TrackFollowSessionStore(java.io.File(context.applicationContext.noBackupFilesDir, "navigation")))

    private val mutableState = MutableStateFlow(NavigationState())
    val state = mutableState.asStateFlow()
    private val mutableSimulationPoints = MutableStateFlow<List<TrackPoint>>(emptyList())
    val simulationPoints = mutableSimulationPoints.asStateFlow()
    private val commands = Mutex()
    private val cacheLock = Mutex()
    private data class AcceptedFix(val id: String, val fix: NavigationFix, val epoch: Long)
    private val fixes = Channel<AcceptedFix>(Channel.CONFLATED)
    @Volatile private var epoch = 0L
    private var engine: NavigationEngine? = null
    private var trackEngine: TrackFollowEngine? = null
    private var trackStartPending = false
    private var finishingSession = false
    private val markerLock = Mutex()
    @Volatile private var markerRevision = 0L
    private var markerProblem: String? = null
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
            val interrupted = withContext(Dispatchers.IO) { markerLock.withLock { runCatching { trackFollowStore?.read() == true } } }
            if (hydrationEpoch == epoch && markerRevision == 0L) {
                publish(mutableState.value.copy(interruptedTrackFollow = interrupted.getOrDefault(false),
                    message = if (interrupted.isFailure) "Kesilen GPX bilgisi okunamadı." else mutableState.value.message))
            }
        }
        scope.launch {
            runtime.journeys.collect { values ->
                // A repository may emit before its suspend call returns. Let the owning command
                // commit or fail before interpreting that emission as an external transition.
                if (trackStartPending || finishingSession) commands.withLock { }
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
                if (trackStartPending || finishingSession) commands.withLock { }
                if (observationEpoch != epoch || mutableState.value.simulation) return@collect
                val before = mutableState.value
                // Explicit external starts may attach a compatible confirmed recorder.
                // Candidates and retired snapshots cannot change an opt-out.
                if (before.sessionId != null && before.journey == null) {
                    if (current == null || current.status != JourneyStatus.CONFIRMED ||
                        runtime.recordingId() != current.id) return@collect
                    if (current.transport != before.sessionTransport) {
                        if (before.trackFollow == null) return@collect
                        clearRoute()
                    }
                    publish(mutableState.value.copy(journey = current, sessionTransport = current.transport, recording = true))
                    return@collect
                }
                if (before.journey?.id != null && current?.id != before.journey.id && stoppingRecordingId != before.journey.id) {
                    endLocalSession()
                } else if ((before.route != null || before.trackFollow != null) && current != null && before.journey != null &&
                    (current.transport != before.journey.transport || current.status != JourneyStatus.CONFIRMED)) clearRoute()
                publish(mutableState.value.copy(journey = current,
                    sessionId = mutableState.value.sessionId ?: current?.id?.takeIf { runtime.recordingId() == it },
                    sessionTransport = current?.transport ?: mutableState.value.sessionTransport,
                    recording = current != null && runtime.recordingId() == current.id,
                    fix = mutableState.value.fix.takeIf { before.journey?.id == current?.id }))
            }
        }
        scope.launch { for (accepted in fixes) if (accepted.epoch == epoch) accept(accepted.id, accepted.fix) }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (trackStartPending || finishingSession) continue
                val before = mutableState.value
                val fresh = before.fix?.let { fresh(it, clock()) } == true &&
                    (before.trackFollow == null || runtime.locationActive())
                if (!fresh && !before.gpsStale) runtime.cancelSpeech()
                val alive = if (before.simulation) before.recording else before.journey?.id?.let { runtime.recordingId() == it } == true
                if (before.recording && !alive && stoppingRecordingId != before.journey?.id) {
                    if (before.trackFollow != null) endLocalSession() else clearRoute()
                    publish(mutableState.value.copy(sharingLocation = false))
                }
                publish(mutableState.value.copy(recording = alive, gpsStale = !fresh,
                    sessionId = mutableState.value.sessionId ?: before.journey?.id?.takeIf { alive },
                    sessionTransport = before.journey?.transport ?: mutableState.value.sessionTransport,
                    locationActive = !before.simulation && runtime.locationActive(),
                    trackFollow = mutableState.value.trackFollow?.let { follow ->
                        if (!fresh) follow.copy(progress = follow.progress.copy(status = TrackFollowStatus.WAITING_FOR_GPS)) else follow
                    }))
                if (fresh && mutableState.value.progress?.offRoute == true) requestReroute()
                else if (fresh) requestTrafficRefresh()
            }
        }
    }

    suspend fun startTrackFollow(track: ImportedTrack, selection: TrackFollowSelection, transport: Transport,
        replaceExisting: Boolean = false, expectedReplacementKey: String? = null): String = commands.withLock {
        require(transport != Transport.UNKNOWN) { "Yolculuk türünü seç." }
        check(!simulationEnabled && !mutableState.value.simulation) { "Önce deneme sürüşünü bitir." }
        fun checkReplacement() {
            check(expectedReplacementKey == null || mutableState.value.navigationReplacementKey() == expectedReplacementKey) {
                "Açık yönlendirme değişti. GPX takibini başlatmak için yeniden onayla."
            }
            require(replaceExisting || mutableState.value.let { it.route == null && it.trackFollow == null }) {
                "Açık yol tarifini veya GPX takibini değiştirmek için onay ver."
            }
        }
        checkReplacement()
        val token = epoch
        trackStartPending = true
        runtime.setPreparingNoRecord(true)
        try {
            val prepared = withContext(Dispatchers.Default) { TrackFollowEngine(selectedTrackPoints(track, selection)) }
            check(token == epoch) { "Başlatma işlemi iptal edildi." }
            runtime.prepareTrackFollowSession(transport, stillCurrent = { token == epoch }, onDiscard = { existing ->
                retireJourney(existing.id)
                val before = mutableState.value
                if (before.journey?.id == existing.id) publish(before.copy(journey = null, recording = false,
                    sessionId = before.sessionId.takeUnless { it == existing.id }))
            }, commit = { recording ->
                check(token == epoch) { "Başlatma işlemi iptal edildi." }
                checkReplacement()
                val id = mutableState.value.sessionId ?: recording?.id ?: UUID.randomUUID().toString()
                clearRoute()
                trackEngine = prepared
                markerProblem = null
                publish(mutableState.value.copy(sessionId = id, sessionTransport = transport,
                    journey = recording, recording = recording != null, interruptedTrackFollow = false,
                    trackFollow = TrackFollowState(track, selection, prepared.initial), message = null))
                updateTrackMarker(true)
            })
            requireNotNull(mutableState.value.sessionId)
        } finally {
            runtime.setPreparingNoRecord(false)
            trackStartPending = false
        }
    }

    suspend fun stopTrackFollow() {
        // A stop must invalidate a suspended preparation before waiting for its command lock.
        val token = ++epoch
        commands.withLock {
            if (token != epoch) return@withLock
            trackEngine = null
            markerProblem = null
            publish(mutableState.value.copy(trackFollow = null, interruptedTrackFollow = false, routeRevision = epoch, message = null))
            updateTrackMarker(false)
            releaseIfIdle()
        }
    }

    fun dismissInterruptedTrackFollow() {
        epoch++
        publish(mutableState.value.copy(interruptedTrackFollow = false, routeRevision = epoch))
        updateTrackMarker(mutableState.value.trackFollow != null)
    }

    private fun updateTrackMarker(active: Boolean) {
        val revision = ++markerRevision
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                markerLock.withLock {
                    if (revision != markerRevision) return@withLock Result.success(Unit)
                    runCatching { if (active) trackFollowStore?.write() else trackFollowStore?.clear(); Unit }
                }
            }
            if (revision == markerRevision && result.isFailure) {
                markerProblem = if (active) "GPX takibi açık; kesilme bilgisi saklanamadı." else "GPX kesilme bilgisi temizlenemedi."
                publish(mutableState.value.copy(message = markerProblem))
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
            val route = runtime.plan(stops, transport).requireUsablePreferences()
            check(route.transport == transport) { "Rota seçili yolculuk türüyle eşleşmedi." }
            check(token == epoch) { "Yolculuk durumu değişti; rotayı yeniden seç." }
            route
        } finally {
            if (token == epoch) publish(mutableState.value.copy(loading = false))
        }
    }

    suspend fun startGuidance(route: PlannedRoute, recordJourney: Boolean = true, replaceTrackFollow: Boolean = false,
        expectedTrackFollowKey: String? = null): String =
        activate(route, true, recordJourney, replaceTrackFollow, expectedTrackFollowKey)
    suspend fun activateRoute(route: PlannedRoute, replaceTrackFollow: Boolean = false): String = activate(route, false, true, replaceTrackFollow)

    private suspend fun activate(route: PlannedRoute, guidance: Boolean, recordJourney: Boolean, replaceTrackFollow: Boolean,
        expectedTrackFollowKey: String? = null): String = commands.withLock {
        route.requireUsablePreferences()
        check(expectedTrackFollowKey == null || mutableState.value.trackReplacementKey() == expectedTrackFollowKey) {
            "GPX takibi değişti. Rotayı başlatmak için yeniden onayla."
        }
        require(mutableState.value.trackFollow == null || replaceTrackFollow) { "Açık GPX takibini değiştirmek için onay ver." }
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
        else mutableState.value.fix?.takeIf { fresh(it, clock()) }?.let { onAcceptedLocation(sessionId, it) }
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
        if (mutableState.value.trackFollow != null || trackStartPending) return@launch
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
            if (before.route == null && before.trackFollow == null) freeDrive = false
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
        finishingSession = true
        try {
            if (!before.simulation) before.journey?.id?.let { runtime.finish(it) }
            before.journey?.id?.let(::retireJourney)
            if (mutableState.value.sessionId == expectedSessionId) endLocalSession()
        } finally { finishingSession = false }
    }

    suspend fun finishJourney(expectedJourneyId: String) = commands.withLock {
        val before = mutableState.value
        if (before.journey?.id == expectedJourneyId) before.sessionId?.let { finishSessionLocked(it) }
        else if (before.sessionId == null && runtime.activeJourney()?.id == expectedJourneyId) runtime.finish(expectedJourneyId)
    }

    private fun releaseIfIdle() {
        val before = mutableState.value
        if (before.sessionId != null && !before.recording && before.route == null && before.trackFollow == null && !before.sharingLocation && !freeDrive) endLocalSession()
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

    fun onAcceptedLocation(journeyId: String, fix: NavigationFix) { fixes.trySend(AcceptedFix(journeyId, fix, epoch)) }

    fun onTrackingStopped(journeyId: String?) { scope.launch {
        if (trackStartPending || finishingSession) commands.withLock { }
        if (!mutableState.value.simulation && (journeyId == null || mutableState.value.journey?.id == journeyId)) {
            if (stoppingRecordingId != journeyId && mutableState.value.journey != null) endLocalSession()
            publish(mutableState.value.copy(recording = false, gpsStale = true, locationActive = false,
                trackFollow = mutableState.value.trackFollow?.let { it.copy(progress = it.progress.copy(status = TrackFollowStatus.WAITING_FOR_GPS)) }))
        }
    } }

    fun invalidateAfterDiaryReplacement() {
        // Invalidate synchronously before any already-completed HTTP result can publish.
        val token = ++epoch
        scope.launch {
            if (token != epoch) return@launch
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
        val currentTrackEngine = trackEngine
        val token = epoch
        if (currentTrackEngine != null) {
            val progress = withContext(Dispatchers.Default) { currentTrackEngine.update(fix, now) } ?: return
            if (token != epoch || mutableState.value.sessionId != before.sessionId || trackEngine !== currentTrackEngine) return
            val follow = mutableState.value.trackFollow ?: return
            publish(mutableState.value.copy(fix = fix, gpsStale = false, trackFollow = follow.copy(progress = progress),
                locationActive = runtime.locationActive(), message = markerProblem))
            return
        }
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
                val replacement = runtime.plan(listOf(RouteStop("Mevcut konum", fix.coordinate)) + remaining,
                    route.transport, route.preferences, route.travelSpeedKmh).requireUsablePreferences()
                check(replacement.transport == route.transport && replacement.preferences == route.preferences &&
                    replacement.travelSpeedKmh == route.travelSpeedKmh) { "Yeni rota seçili yol tercihleriyle eşleşmedi." }
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
        trackEngine = null
        if (mutableState.value.trackFollow != null) updateTrackMarker(false)
        markerProblem = null
        runtime.cancelSpeech()
        publish(mutableState.value.copy(route = null, guidance = false, arrived = false, progress = null,
            loading = false, routeRevision = epoch, message = null, trackFollow = null))
    }

    private fun publish(value: NavigationState, refreshSpeed: Boolean = true) {
        val rendered = value.copy(roadSpeed = runtime.roadSpeedState(value, clock()))
        mutableState.value = rendered
        val highFrequency = value.guidance || value.trackFollow != null
        runtime.setHighFrequency(value.journey?.id.takeIf { highFrequency && value.recording && !value.simulation })
        runtime.setLocationSession(value.sessionId.takeIf { !value.simulation }, value.sessionTransport,
            highFrequency, value.sessionId != null && !value.recording && !value.simulation)
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
                fixes.send(AcceptedFix(journey.id, fix, epoch))
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
