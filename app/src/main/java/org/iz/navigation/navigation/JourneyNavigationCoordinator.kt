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
import org.iz.navigation.power.PowerDiagnostics

/** Side effects are isolated so concurrent car/phone/watch commands can be tested without a host. */
interface NavigationRuntime : NavigationCueRuntime {
    override fun speakCue(request: NavigationCueRequest) { request.finish(CueDelivery.REJECTED) }
    val journeys: Flow<List<Journey>>
    suspend fun activeJourney(): Journey?
    suspend fun start(transport: Transport, stillCurrent: () -> Boolean = { true }): String
    suspend fun confirm(id: String, transport: Transport)
    suspend fun finish(id: String)
    /** Conservative default: adapters must opt into the recorder-atomic unattached finish. */
    suspend fun finishUnattached(id: String, stillUnattached: () -> Boolean): Boolean = false
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
    fun hasPreciseLocation(): Boolean = false
    fun canLocateQuickly(): Boolean = hasPreciseLocation()
    suspend fun cachedQuickLocation(precise: Boolean): NavigationFix? = null
    suspend fun locateApproximate(): NavigationFix? = null
    /** The Android runtime commits while holding the recorder's mutex. */
    suspend fun prepareQuickSession(transport: Transport, stillCurrent: () -> Boolean,
        commit: (Journey?) -> Unit) {
        val existing = activeJourney()
        check(stillCurrent()) { "Başlatma isteği artık güncel değil." }
        require(existing == null || existing.status == JourneyStatus.CONFIRMED &&
            existing.transport == transport && recordingId() == existing.id) {
            "Mevcut kaydı korumak için aynı yolculuk türünü seç veya kaydı önce bitir."
        }
        commit(existing)
    }
    fun trafficRefreshEnabled(route: PlannedRoute): Boolean = route.provider == RouteProvider.TOMTOM
    val roadSpeedMonitoring: Boolean get() = false
    fun roadSpeedState(state: NavigationState, now: Long): RoadSpeedState = RoadSpeedState(
        ownKmh = if (state.gpsStale) null else ownSpeedKmh(state.fix, now),
        visible = state.sessionId != null && speedMode(state.sessionTransport))
    suspend fun refreshRoadSpeed() {}
    fun render(state: NavigationState)
    /** Silent, non-blocking preflight only after a guidance activation has committed. */
    fun prepareSpeech() {}
    fun speak(text: String)
    fun cancelSpeech()
    /** Ends only guidance voice policy; adapters must not initialize speech for cleanup. */
    fun endGuidanceSpeech() { cancelSpeech() }
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
    monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    constructor(context: Context) : this(AndroidNavigationRuntime(context.applicationContext),
        NavigationRouteCache(java.io.File(context.applicationContext.noBackupFilesDir, "navigation")),
        trackFollowStore = TrackFollowSessionStore(java.io.File(context.applicationContext.noBackupFilesDir, "navigation")),
        monotonicClock = android.os.SystemClock::elapsedRealtime)

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
    private var breaks: PlannedBreakTracker? = null
    private var trackEngine: TrackFollowEngine? = null
    private var trackStartPending = false
    private var finishingSession = false
    private val markerLock = Mutex()
    @Volatile private var markerRevision = 0L
    private var markerProblem: String? = null
    private val cues = NavigationCuePolicy(monotonicClock)
    private var rerouteJob: Job? = null
    private var editJob: Job? = null
    private var editSequence = 0L
    private val mutableEdits = MutableStateFlow(RouteEditState())
    val edits = mutableEdits.asStateFlow()
    private var simulationJob: Job? = null
    private var speedRefreshJob: Job? = null
    private var lastSpeedRefresh = Long.MIN_VALUE
    private var simulationEnabled = false
    private var freeDrive = false
    private var quickSessionId: String? = null
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
                PowerDiagnostics.sampleIfDue()
                if (trackStartPending || finishingSession) continue
                val before = mutableState.value
                val fresh = before.fix?.let { fresh(it, clock()) } == true &&
                    (quickSessionId == null || runtime.hasPreciseLocation()) &&
                    (before.trackFollow == null || runtime.locationActive())
                if (!fresh) breaks?.gpsUnavailable()
                if (!fresh && !before.gpsStale) runtime.cancelSpeech()
                val alive = if (before.simulation) before.recording else before.journey?.id?.let { runtime.recordingId() == it } == true
                if (before.recording && !alive && stoppingRecordingId != before.journey?.id) {
                    if (before.trackFollow != null) endLocalSession() else clearRoute()
                    publish(mutableState.value.copy(sharingLocation = false))
                }
                val current = mutableState.value
                val clearQuickProgress = quickSessionId != null && !fresh
                publish(current.copy(recording = alive, gpsStale = !fresh,
                    progress = current.progress.takeUnless { clearQuickProgress },
                    progressUpdatedAt = current.progressUpdatedAt.takeUnless { clearQuickProgress },
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
            require(stops.none { it.plannedBreakSeconds > 0 } || route.stops == stops) { "Planlanan molalar önizleme rotasında korunamadı." }
            RouteSchedule.planned(route, clock())
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

    internal suspend fun quickSearchCoordinate(): WeatherCoordinate? = findQuickSearchCoordinate(
        canLocate = runtime::canLocateQuickly,
        hasFine = runtime::hasPreciseLocation,
        visible = { state.value.takeUnless { it.simulation || simulationEnabled }?.fix },
        cached = runtime::cachedQuickLocation,
        approximate = runtime::locateApproximate,
        now = clock,
    )

    internal suspend fun quickOrigin(): QuickNavigationOrigin {
        val owner = state.value
        check(owner.route == null && owner.trackFollow == null && !owner.simulation && !simulationEnabled) {
            "Önce açık yol tarifini, GPX takibini veya deneme sürüşünü bitir."
        }
        check(runtime.canLocateQuickly()) { "Konum iznini kontrol et ve yeniden dene." }
        val visible = owner.fix.takeIf { runtime.hasPreciseLocation() }
        fun result(fix: NavigationFix, precise: Boolean, requiresFine: Boolean): QuickNavigationOrigin {
            return QuickNavigationOrigin(fix, precise, owner.routeRevision, owner.sessionId, requiresFine)
                .also(::validateQuickOrigin)
        }
        if (visible != null && fresh(visible, clock())) return result(visible, true, requiresFine = true)

        return withTimeoutOrNull(5_000) {
            val cacheRequiresFine = runtime.hasPreciseLocation()
            val candidate = try { withTimeoutOrNull(250) { runtime.cachedQuickLocation(cacheRequiresFine) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val fine = runtime.hasPreciseLocation()
            val cached = candidate.takeUnless { cacheRequiresFine && !fine }
            if (cached != null && cacheRequiresFine && fresh(cached, clock())) {
                return@withTimeoutOrNull result(cached, true, requiresFine = true)
            }
            val previous = visible?.takeIf { fine && usableQuickLocation(it, clock()) }
            val usableCache = cached?.takeIf { usableQuickLocation(it, clock()) }
            if (previous != null && (usableCache == null || previous.recordedAt >= usableCache.recordedAt)) {
                return@withTimeoutOrNull result(previous, false, requiresFine = true)
            }
            if (usableCache != null) return@withTimeoutOrNull result(usableCache, false, requiresFine = cacheRequiresFine)
            runtime.locateApproximate()?.takeIf { usableQuickLocation(it, clock()) }
                ?.let { result(it, false, requiresFine = false) }
        } ?: error("Konum alınamadı. Yeniden dene veya konum ayarlarını aç.")
    }

    internal fun validateQuickOrigin(origin: QuickNavigationOrigin) {
        check(quickLocationAllowed(origin)) { "Konum izni değişti. Konumu yeniden al." }
        check(usableQuickLocation(origin.fix, clock())) { "Başlangıç konumu eskidi. Yeniden dene." }
        check(state.value.routeRevision == origin.routeRevision && state.value.sessionId == origin.sessionId) {
            "Yolculuk değişti. Konumu yeniden al."
        }
    }

    private fun quickLocationAllowed(origin: QuickNavigationOrigin) =
        runtime.canLocateQuickly() && (!origin.requiresFinePermission || runtime.hasPreciseLocation())

    internal suspend fun startQuickGuidance(route: PlannedRoute, origin: QuickNavigationOrigin,
        recordJourney: Boolean): String {
        validateQuickOrigin(origin)
        val caller = currentCoroutineContext()
        return activate(route, true, recordJourney, false, quickOrigin = origin, startStillCurrent = {
            caller.isActive && quickLocationAllowed(origin) &&
                state.value.routeRevision == origin.routeRevision && state.value.sessionId == origin.sessionId
        })
    }

    /** Exact prepared geometry: never asks a planner for a connector or replacement. */
    suspend fun startPreparedGuidance(route: PlannedRoute, fingerprint: String, recordJourney: Boolean = true,
        stillPrepared: () -> Boolean = { true }, stillCurrent: () -> Boolean = { true }): String {
        val before = state.value
        val ownerEpoch = epoch
        val startCurrent = { epoch == ownerEpoch && state.value.sessionId == before.sessionId &&
            state.value.routeRevision == before.routeRevision && state.value.trackReplacementKey() == before.trackReplacementKey() && stillCurrent() }
        check(stillPrepared() && startCurrent()) { "Hazır rota veya yolculuk durumu değişti." }
        require(fingerprint.matches(Regex("[a-fA-F0-9]{64}"))) { "Hazır rota kimliği geçersiz." }
        val fix = currentLocation()
        val match = PreparedRouteStart.inspect(route, fix, clock())
        require(match.ready) { match.message }
        return activate(route, true, recordJourney, false, preparedFingerprint = fingerprint,
            preparedFix = fix, stillPrepared = stillPrepared, startStillCurrent = startCurrent)
    }

    private suspend fun activate(route: PlannedRoute, guidance: Boolean, recordJourney: Boolean, replaceTrackFollow: Boolean,
        expectedTrackFollowKey: String? = null, preparedFingerprint: String? = null,
        preparedFix: NavigationFix? = null, stillPrepared: () -> Boolean = { true },
        quickOrigin: QuickNavigationOrigin? = null,
        startStillCurrent: () -> Boolean = { true }): String = commands.withLock {
        route.requireUsablePreferences()
        check(expectedTrackFollowKey == null || mutableState.value.trackReplacementKey() == expectedTrackFollowKey) {
            "GPX takibi değişti. Rotayı başlatmak için yeniden onayla."
        }
        require(mutableState.value.trackFollow == null || replaceTrackFollow) { "Açık GPX takibini değiştirmek için onay ver." }
        require(route.vertices.size >= 2 && route.durationSeconds > 0) { "Geçerli bir rota hesapla." }
        RouteSchedule.planned(route, clock()) // Validate optional break boundaries before starting a recorder.
        require(!guidance || route.maneuvers.isNotEmpty()) { "Bu rota dönüş talimatı içermiyor. Yeniden hesapla." }
        val simulated = simulationEnabled && guidance
        check(!mutableState.value.simulation || simulated) { "Önce deneme sürüşünü bitir." }
        check(!simulationEnabled || guidance) { "Deneme modunda gerçek kayıt başlatılamaz." }
        check(startStillCurrent() && stillPrepared()) { "Başlatma isteği artık güncel değil." }
        if (quickOrigin != null) {
            check(!simulated && mutableState.value.route == null) { "Önce açık yol tarifini veya deneme sürüşünü bitir." }
            return@withLock activateQuickLocked(route, quickOrigin, recordJourney, startStillCurrent)
        }
        val activationEpoch = epoch
        check(!simulated || runtime.activeJourney() == null) { "Deneme sürüşü için önce gerçek yolculuğu bitir." }
        check(activationEpoch == epoch) { "Başlatma işlemi iptal edildi." }
        val sessionId = if (simulated) "simulation-${UUID.randomUUID()}" else ensureSession(route.transport, recordJourney)
        val journey = if (simulated) Journey(id = sessionId, transport = route.transport, startedAt = clock()) else mutableState.value.journey
        check(activationEpoch == epoch && stillPrepared()) { "Hazır rota veya yolculuk durumu değişti." }
        val initialFix = if (preparedFingerprint != null) {
            val candidate = mutableState.value.fix?.takeIf { fresh(it, clock()) } ?: preparedFix
            val match = PreparedRouteStart.inspect(route, candidate, clock())
            require(match.ready) { match.message }
            candidate
        } else null
        clearRoute()
        freeDrive = false
        configureRoute(route)
        publish(mutableState.value.copy(journey = journey, recording = journey != null, route = route,
            sessionId = sessionId, sessionTransport = route.transport,
            guidance = guidance, arrived = false, progress = null, progressUpdatedAt = null, routeRevision = epoch,
            loading = false, message = if (simulated) "Deneme sürüşü · günlüğe kaydedilmez" else null,
            simulation = simulated, paused = false, preparedRouteFingerprint = preparedFingerprint))
        // Voice availability cannot undo the already committed visual guidance/session.
        if (guidance) prepareGuidanceSpeech(preparedFingerprint != null)
        if (!simulated) saveRoute(route)
        if (simulated) startSimulation(route, requireNotNull(journey))
        else (initialFix ?: mutableState.value.fix)?.takeIf { fresh(it, clock()) }?.let { fix ->
            if (initialFix != null) publish(mutableState.value.copy(fix = fix, gpsStale = false, progress = engine?.update(fix, clock()), progressUpdatedAt = clock()))
            else onAcceptedLocation(sessionId, fix)
        }
        sessionId
    }

    private suspend fun activateQuickLocked(route: PlannedRoute, origin: QuickNavigationOrigin,
        recordJourney: Boolean, stillCurrent: () -> Boolean): String {
        val previous = mutableState.value
        require(previous.sessionId == null || previous.sessionTransport == route.transport) {
            "Açık yolculuğun türü farklı. Önce mevcut yolculuğu bitir."
        }
        lateinit var session: String
        var initialFix: NavigationFix? = null
        runtime.prepareQuickSession(route.transport, stillCurrent) { existing ->
            require(usableQuickLocation(origin.fix, clock())) { "Başlangıç konumu eskidi. Yeniden dene." }
            initialFix = previous.fix?.takeIf { runtime.hasPreciseLocation() && fresh(it, clock()) }
                ?: origin.fix.takeIf { origin.precise && runtime.hasPreciseLocation() && fresh(it, clock()) }
            session = previous.sessionId ?: existing?.id ?: UUID.randomUUID().toString()
            clearRoute()
            freeDrive = false
            configureRoute(route)
            quickSessionId = session
            publish(mutableState.value.copy(journey = existing, recording = existing != null, route = route,
                sessionId = session, sessionTransport = route.transport, guidance = true,
                fix = null, gpsStale = true, recordingPending = recordJourney && existing == null,
                approximateOrigin = origin.fix.coordinate.takeIf { initialFix == null }))
        }
        prepareGuidanceSpeech(prepared = false)
        saveRoute(route)
        initialFix?.let { onAcceptedLocation(session, it) }
        return session
    }

    private fun configureRoute(route: PlannedRoute) {
        engine = NavigationEngine(route)
        breaks = PlannedBreakTracker(route)
        lastTrafficCheckAt = clock()
        cues.reset()
    }

    private fun prepareGuidanceSpeech(prepared: Boolean) {
        runCatching {
            val voice = runtime as? NavigationVoiceRuntime
            if (voice != null) voice.prepareSpeech(if (prepared) GuidanceVoiceMode.PREPARED_LOCAL else GuidanceVoiceMode.DEFAULT)
            else runtime.prepareSpeech()
        }
    }

    private suspend fun ensureRecording(transport: Transport, quickStillCurrent: (() -> Boolean)? = null): Journey {
        val token = epoch
        val stillCurrent = { token == epoch && quickStillCurrent?.invoke() != false }
        var before = runtime.activeJourney()
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        check(quickStillCurrent == null || before == null) { "Başka bir kayıt başladı." }
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
        check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
        val id = before?.id ?: runtime.start(transport, stillCurrent)
        try {
            withTimeout(8000) {
                while (runtime.recordingId() != id) {
                    check(stillCurrent()) { "Başlatma işlemi iptal edildi." }
                    check(runtime.activeJourney()?.id == id) { "Yolculuk başlatılamadı." }
                    delay(100)
                }
            }
            check(stillCurrent()) { "Yolculuk durumu değişti." }
            val actual = runtime.activeJourney()
            check(stillCurrent()) { "Yolculuk durumu değişti." }
            check(actual?.id == id && actual.status == JourneyStatus.CONFIRMED) { "Yolculuk artık açık değil." }
            publish(mutableState.value.copy(journey = actual, recording = true,
                fix = mutableState.value.fix.takeIf { mutableState.value.journey?.id == id }, message = null))
            return actual
        } catch (error: TimeoutCancellationException) {
            if (before == null && quickStillCurrent == null) runtime.interrupt(id)
            if (quickStillCurrent != null) releaseFailedQuickRecording(id)
            throw IllegalStateException("Konum servisi başlamadı. Telefonun konum izinlerini kontrol et.", error)
        } catch (error: Exception) {
            if (quickStillCurrent != null) releaseFailedQuickRecording(id)
            throw error
        }
    }

    private suspend fun releaseFailedQuickRecording(id: String) = withContext(NonCancellable) {
        retireJourney(id)
        runtime.stopRecording(id)
    }

    fun stopGuidance() { scope.launch {
        if (mutableState.value.trackFollow != null || trackStartPending) return@launch
        clearRoute()
        releaseIfIdle()
    } }

    /** Applies a confirmed stop only to the same guidance after earlier commands finish. */
    suspend fun stopGuidance(expected: NavigationStopSnapshot): Boolean = commands.withLock {
        val before = mutableState.value
        if (NavigationStopSnapshot.from(before) != expected ||
            !before.guidance || before.trackFollow != null || trackStartPending) return@withLock false
        clearRoute()
        releaseIfIdle()
        true
    }

    suspend fun stopRecording(expectedJourneyId: String) = commands.withLock {
        stopRecordingLocked(expectedJourneyId)
    }

    suspend fun stopRecording(expected: NavigationStopSnapshot): Boolean = commands.withLock {
        val before = mutableState.value
        if (NavigationStopSnapshot.from(before) != expected || before.simulation ||
            !before.recording || before.journey == null) return@withLock false
        stopRecordingLocked(before.journey.id)
        true
    }

    private suspend fun stopRecordingLocked(expectedJourneyId: String) {
        val before = mutableState.value
        if (before.simulation || before.journey?.id != expectedJourneyId) return
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

    suspend fun finishSession(expected: NavigationStopSnapshot): Boolean = commands.withLock {
        if (NavigationStopSnapshot.from(mutableState.value) != expected) return@withLock false
        finishSessionLocked(expected.sessionId)
        true
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

    /** Finish only the captured diary target while no navigation session owns it. */
    suspend fun finishUnattachedJourney(expectedJourneyId: String): Boolean = commands.withLock {
        val token = epoch
        fun stillUnattached(): Boolean {
            val current = mutableState.value
            return token == epoch && !trackStartPending && current.sessionId == null &&
                !current.simulation && !current.guidance && current.route == null && current.trackFollow == null &&
                (current.journey == null || current.journey.id == expectedJourneyId)
        }
        if (!stillUnattached()) return@withLock false
        finishingSession = true
        try {
            if (!runtime.finishUnattached(expectedJourneyId, ::stillUnattached)) return@withLock false
            retireJourney(expectedJourneyId)
            val current = mutableState.value
            if (current.sessionId == null && current.journey?.id == expectedJourneyId) endLocalSession()
            true
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

    fun setMuted(muted: Boolean, stillCurrent: () -> Boolean = { true }) { scope.launch {
        if (!stillCurrent()) return@launch
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
        val acceptedEpoch = epoch
        if (finishingSession) commands.withLock { }
        if (acceptedEpoch != epoch || id in retiredJourneys && id != mutableState.value.sessionId) return
        val now = clock()
        var before = mutableState.value
        if (!before.simulation && before.sessionId != id && before.journey?.id != id) {
            if (before.sessionId != null) return
            val observationEpoch = epoch
            val trip = runtime.activeJourney()?.takeIf { it.id == id } ?: return
            // This lookup may have started before a finish command acquired ownership.
            if (finishingSession) commands.withLock { }
            val current = mutableState.value
            if (observationEpoch != epoch || id in retiredJourneys || current.sessionId != null ||
                (current.journey != null && current.journey.id != id) || runtime.recordingId() != id) return
            clearRoute()
            publish(mutableState.value.copy(journey = trip, sessionId = id, sessionTransport = trip.transport, recording = runtime.recordingId() == id))
            before = mutableState.value
        }
        if (before.sessionId != id && before.journey?.id != id || !fresh(fix, now)) return
        if (quickSessionId != null && !runtime.hasPreciseLocation()) return
        if (before.fix?.let { fix.recordedAt <= it.recordedAt } == true) return
        var resumedPlannedBreak = false
        if (before.paused) {
            val resumed = before.plannedBreak != null && breaks?.update(fix, now, { engine?.distanceToStopMeters(it) }) == PlannedBreakTracker.Change.RESUMED
            if (!resumed) {
                publish(before.copy(fix = fix, gpsStale = false, locationActive = runtime.locationActive()))
                return
            }
            resumedPlannedBreak = true
            epoch++; invalidateEdits(); cues.reset(); runtime.cancelSpeech()
            before = before.copy(paused = false, plannedBreak = null, routeRevision = epoch, message = null)
        }
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
        if (quickSessionId != null && !runtime.hasPreciseLocation()) return
        val arrived = progress?.arrived == true
        // A resumed break must not expose the pre-break projection as usable guidance.
        val missingResumeProgress = resumedPlannedBreak && progress == null
        val next = mutableState.value.copy(fix = fix, gpsStale = missingResumeProgress,
            approximateOrigin = null, recordingPending = !arrived && before.recordingPending,
            progress = if (missingResumeProgress) null else progress ?: before.progress,
            paused = before.paused, plannedBreak = before.plannedBreak, routeRevision = epoch,
            scheduleContext = if (missingResumeProgress) null else before.scheduleContext,
            guidance = before.guidance && !arrived, arrived = arrived || before.arrived,
            progressUpdatedAt = if (progress != null) fix.recordedAt else if (missingResumeProgress) null else before.progressUpdatedAt,
            message = when {
                arrived -> if (before.recording) "Hedefe vardın. Yolculuk kaydı devam ediyor." else "Hedefe vardın."
                progress?.offRoute == true -> if (before.preparedRouteFingerprint != null)
                    "Hazır rotanın dışındasın. Yeni rota için internet gerekir." else "Rota dışındasın. Yeni rota hesaplanıyor."
                before.simulation -> "Deneme sürüşü · günlüğe kaydedilmez"
                else -> null
            })
        val breakStarted = !arrived && !before.arrived && !before.simulation && before.guidance && progress != null &&
            breaks?.update(fix, now, { currentEngine?.distanceToStopMeters(it) }, progress.offRoute) == PlannedBreakTracker.Change.STARTED
        if (breakStarted) {
            epoch++; rerouteJob?.cancel(); rerouteJob = null; invalidateEdits(); runtime.cancelSpeech()
            publish(next.copy(paused = true, plannedBreak = breaks?.snapshot(now), routeRevision = epoch,
                loading = false, message = if (before.recording) "Planlanan mola başladı. Yolculuk kaydı devam ediyor." else "Planlanan mola başladı."))
            return
        }
        publish(next)
        if (arrived && !before.arrived && before.guidance && !before.muted) {
            runtime.cancelSpeech(); runtime.speak(if (before.recording) "Hedefe vardın. Yolculuk kaydı devam ediyor." else "Hedefe vardın.")
        } else if (before.guidance && progress != null && !progress.offRoute && !before.muted) {
            before.route?.let { route ->
                if (preparedVoiceReady(mutableState.value)) cues.offer(route, progress, fix) { request ->
                    val current = mutableState.value
                    val currentProgress = current.progress
                    val currentFix = current.fix
                    token == epoch && current.sessionId == before.sessionId && current.route?.id == route.id &&
                        current.routeRevision == before.routeRevision && current.guidance && !current.paused &&
                         !current.muted && !current.gpsStale && !current.arrived && preparedVoiceReady(current) &&
                         (quickSessionId == null || runtime.hasPreciseLocation()) &&
                        currentProgress != null && !currentProgress.offRoute && !currentProgress.arrived &&
                        currentFix != null && fresh(currentFix, clock()) && cues.isEligible(request, route, currentProgress, currentFix)
                }?.let(runtime::speakCue)
            }
        } else if (progress?.offRoute == true) runtime.cancelSpeech()
        if (next.recordingPending) attachQuickRecording(next.sessionId, fix)
        if (progress?.offRoute == true && !arrived) requestReroute()
    }

    private suspend fun attachQuickRecording(session: String?, fix: NavigationFix) = commands.withLock {
        val before = mutableState.value
        val mode = before.sessionTransport
        if (session == null || session != quickSessionId || session != before.sessionId || mode == null ||
            !before.guidance || !before.recordingPending || before.recording ||
            !runtime.hasPreciseLocation() || !fresh(fix, clock())) return@withLock
        try {
            // An external active journey is handled by the existing session observer.
            if (runtime.activeJourney() != null) return@withLock
            if (!runtime.hasPreciseLocation() || !fresh(fix, clock())) return@withLock
            ensureRecording(mode) {
                val current = mutableState.value
                current.sessionId == session && quickSessionId == session && current.guidance &&
                    runtime.hasPreciseLocation() && fresh(fix, clock())
            }
            val current = mutableState.value
            if (current.sessionId == session && quickSessionId == session) {
                publish(current.copy(fix = fix, recordingPending = false, gpsStale = !fresh(fix, clock())))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            val current = mutableState.value
            if (current.sessionId == session && quickSessionId == session) {
                publish(current.copy(recordingPending = false, message = error.message ?: "Yolculuk kaydı başlatılamadı."))
            }
        }
    }

    private fun remainingRouteStopIndices(): List<Int> = engine?.remainingStopIndices().orEmpty().let {
        breaks?.retainedIndices(it) ?: it
    }

    fun remainingStopIndices(expected: RouteEditOwner): List<Int> =
        if (expected.matches(state.value)) remainingRouteStopIndices() else emptyList()

    /** A provider result is a preview only. Recorder and active geometry remain untouched. */
    suspend fun previewEdit(action: RouteEditAction, expected: RouteEditOwner): String? = coroutineScope {
        val captured = commands.withLock {
            val before = state.value
            require(expected.matches(before) && before.guidance && !before.paused && !before.simulation && !before.arrived &&
                !before.loading && !before.gpsStale) { "Rota durumu değişti. Yeniden seç." }
            val route = requireNotNull(before.route)
            val fix = requireNotNull(before.fix)
            require(fresh(fix, clock())) { "Rota değişikliği için güncel konum bekleniyor." }
            val progress = requireNotNull(before.progress)
            require(!progress.offRoute) { "Önce güncel rota konumunu bekle." }
            val remaining = remainingRouteStopIndices()
            val requestRoute = route.copy(stops = route.stops.mapIndexed { index, stop -> breaks?.stopForRequest(index) ?: stop })
            val request = RouteEditPolicy.prepare(requestRoute, progress.elapsedSeconds, fix.coordinate, clock(), action, remaining)
            invalidateEdits()
            val id = UUID.randomUUID().toString()
            val sequence = editSequence
            editJob = currentCoroutineContext()[Job]
            mutableEdits.value = RouteEditState(sequence, true, id)
            Triple(id, sequence, request)
        }
        val (id, sequence, request) = captured
        try {
            val replacement = planModified(request).requireUsablePreferences()
            require(replacement.transport == request.transport && replacement.preferences == request.preferences &&
                routeTravelSpeedKmh(replacement.transport, replacement.travelSpeedKmh) ==
                    routeTravelSpeedKmh(request.transport, request.travelSpeedKmh) && replacement.stops == request.stops &&
                replacement.avoidAreas == request.avoidAreas && replacement.maneuvers.isNotEmpty()) { "Rota koşulları korunamadı." }
            val ownerContext = currentCoroutineContext()
            requireAvoidedAreas(replacement, request.avoidAreas) { ownerContext.ensureActive() }
            commands.withLock {
                if (sequence != editSequence || !expected.matches(state.value) || state.value.paused) return@withLock null
                mutableEdits.value = RouteEditState(sequence, preview = RouteEditPreview(id, expected, action, request, replacement))
                id
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            commands.withLock {
                if (sequence == editSequence && expected.matches(state.value)) mutableEdits.value = RouteEditState(sequence,
                    error = "Yeni rota alınamadı veya seçili koşullar sağlanamadı. Mevcut rota korunuyor.")
            }
            null
        } finally {
            if (sequence == editSequence) { editJob = null; mutableEdits.value = mutableEdits.value.copy(loading = false) }
        }
    }

    suspend fun commitEdit(expected: RouteEditOwner, previewId: String): Boolean = commands.withLock {
        val before = state.value
        val preview = edits.value.preview ?: return@withLock false
        if (preview.id != previewId || preview.owner != expected || !expected.matches(before) || before.paused ||
            !before.guidance || before.arrived || before.gpsStale) return@withLock false
        val fix = before.fix ?: return@withLock false
        require(fresh(fix, clock()) && WeatherEngine.distanceMeters(fix.coordinate, preview.request.stops.first().coordinate) <= 100) {
            "Konum değişti. Rota önizlemesini yenile."
        }
        val replacementEngine = NavigationEngine(preview.route)
        val progress = replacementEngine.update(fix, clock())
        require(progress != null && !progress.offRoute && progress.distanceFromRouteMeters <= kotlin.math.max(40.0, fix.accuracyMeters * 2.0)) {
            "Yeni rotaya geçiş konumu doğrulanamadı. Mevcut rota korundu."
        }
        requireAvoidedAreas(preview.route, preview.request.avoidAreas)
        epoch++
        rerouteJob?.cancel(); rerouteJob = null
        invalidateEdits()
        engine = replacementEngine; breaks = PlannedBreakTracker(preview.route); cues.reset(); runtime.cancelSpeech()
        publish(before.copy(route = preview.route, progress = progress, routeRevision = epoch,
            loading = false, message = null, preparedRouteFingerprint = null, progressUpdatedAt = clock()))
        (runtime as? NavigationVoiceRuntime)?.prepareSpeech(GuidanceVoiceMode.DEFAULT)
        lastTrafficCheckAt = clock(); saveRoute(preview.route)
        true
    }

    suspend fun cancelEdit(expected: RouteEditOwner, requestId: String): Boolean = commands.withLock {
        val current = edits.value
        if (!expected.matches(state.value) || requestId != (current.preview?.id ?: current.requestId)) return@withLock false
        invalidateEdits(); true
    }

    private fun invalidateEdits() {
        editSequence++
        editJob?.cancel(); editJob = null
        mutableEdits.value = RouteEditState(editSequence)
    }
    private suspend fun planModified(request: RouteModificationRequest): PlannedRoute {
        val planner = runtime as? RouteModificationPlanner
        if (planner != null) return planner.planModified(request)
        require(request.avoidAreas.isEmpty()) { "Bu rota sağlayıcısı hariç tutmayı desteklemiyor." }
        return runtime.plan(request.stops, request.transport, request.preferences, request.travelSpeedKmh)
    }

    private fun preparedVoiceReady(state: NavigationState): Boolean {
        if (state.preparedRouteFingerprint == null) return true
        val status = (runtime as? NavigationVoiceRuntime)?.speechStatus?.value ?: return false
        return status.readiness == EngineReadiness.READY && status.voiceMode == GuidanceVoiceMode.PREPARED_LOCAL &&
            status.localVoice == LocalVoiceCapability.AVAILABLE
    }

    suspend fun resumePlannedBreak(expected: NavigationStopSnapshot): Boolean = commands.withLock {
        val before = state.value
        if (NavigationStopSnapshot.from(before) != expected || before.plannedBreak == null || !before.paused) return@withLock false
        resumeBreakLocked(before)
        true
    }

    private fun resumeBreakLocked(before: NavigationState) {
        breaks?.finish(); epoch++; invalidateEdits(); cues.reset(); runtime.cancelSpeech()
        val fix = before.fix?.takeIf { fresh(it, clock()) }
        val progress = fix?.let { engine?.update(it, clock()) } ?: before.progress
        publish(before.copy(paused = false, plannedBreak = null, routeRevision = epoch, loading = false,
            progress = progress, progressUpdatedAt = fix?.recordedAt, message = null))
    }

    /** Guidance pause retains recorder/GPS/session ownership. */
    suspend fun setPaused(expected: NavigationStopSnapshot, paused: Boolean): Boolean = commands.withLock {
        val before = mutableState.value
        if (NavigationStopSnapshot.from(before) != expected || !before.guidance || before.arrived || before.simulation ||
            before.route == null || before.paused == paused) return@withLock false
        if (!paused && before.plannedBreak != null) { resumeBreakLocked(before); return@withLock true }
        breaks?.cancelCandidate()
        if (!paused) require(before.fix?.let { fresh(it, clock()) } == true) { "Devam etmek için güncel GPS konumu bekleniyor." }
        epoch++
        rerouteJob?.cancel(); rerouteJob = null
        invalidateEdits()
        runtime.cancelSpeech()
        val progress = if (!paused) engine?.update(requireNotNull(before.fix), clock()) ?: before.progress else before.progress
        publish(before.copy(paused = paused, routeRevision = epoch, loading = false, progress = progress, progressUpdatedAt = if (paused) null else clock(),
            message = if (paused) "Yönlendirme duraklatıldı. Yolculuk kaydı ve konum takibi devam eder." else null))
        true
    }

    private fun requestTrafficRefresh() {
        val before = mutableState.value
        val route = before.route ?: return
        if (!before.guidance || before.paused || before.preparedRouteFingerprint != null || before.sessionId == null || before.simulation || before.arrived || before.gpsStale) return
        val now = clock()
        if (now - (lastTrafficCheckAt ?: route.createdAt) < 120_000L) return
        lastTrafficCheckAt = now
        if (runtime.trafficRefreshEnabled(route)) requestReroute(trafficRefresh = true)
    }

    private fun requestReroute(trafficRefresh: Boolean = false) {
        val before = mutableState.value
        val route = before.route ?: return
        val fix = before.fix ?: return
        val remaining = remainingRouteStopIndices().map { breaks?.stopForRequest(it) ?: route.stops[it] }
        if (before.paused || before.preparedRouteFingerprint != null || before.simulation || before.arrived || before.sessionId == null || !fresh(fix, clock()) || remaining.isEmpty() || rerouteJob?.isActive == true) return
        val now = clock()
        if (lastRerouteAttempt?.let { now - it in 0 until 15_000 } == true) return
        lastRerouteAttempt = now
        val token = epoch
        publish(before.copy(loading = true))
        rerouteJob = scope.launch {
            val started = System.nanoTime()
            try {
                val request = RouteModificationRequest(listOf(RouteStop("Mevcut konum", fix.coordinate)) + remaining,
                    clock(), route.transport, route.travelSpeedKmh, route.preferences, route.avoidAreas)
                val replacement = planModified(request).requireUsablePreferences()
                require(replacement.avoidAreas == route.avoidAreas) { "Hariç tutulan alanlar korunamadı." }
                require(request.stops.none { it.plannedBreakSeconds > 0 } || replacement.stops == request.stops) {
                    "Planlanan molalar yeni rotada korunamadı."
                }
                RouteSchedule.planned(replacement, clock())
                val ownerContext = currentCoroutineContext()
                requireAvoidedAreas(replacement, request.avoidAreas) { ownerContext.ensureActive() }
                check(replacement.transport == route.transport && replacement.preferences == route.preferences &&
                    routeTravelSpeedKmh(replacement.transport, replacement.travelSpeedKmh) ==
                    routeTravelSpeedKmh(route.transport, route.travelSpeedKmh)) { "Yeni rota seçili yol tercihleriyle eşleşmedi." }
                val activeJourney = runtime.activeJourney()
                if (token != epoch || mutableState.value.route?.id != route.id || mutableState.value.sessionId != before.sessionId ||
                    before.journey != null && activeJourney?.id != before.journey.id) return@launch
                check(!before.guidance || replacement.maneuvers.isNotEmpty()) { "Dönüş talimatı alınamadı." }
                epoch++
                invalidateEdits()
                engine = NavigationEngine(replacement); breaks = PlannedBreakTracker(replacement); cues.reset(); runtime.cancelSpeech()
                publish(mutableState.value.copy(route = replacement, routeRevision = epoch, progress = null, progressUpdatedAt = null, loading = false, message = null))
                lastTrafficCheckAt = clock()
                saveRoute(replacement)
                mutableState.value.fix?.let { latest ->
                    val updated = withContext(Dispatchers.Default) { engine?.update(latest, clock()) }
                    if (mutableState.value.route?.id == replacement.id) publish(mutableState.value.copy(progress = updated, progressUpdatedAt = clock()))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token == epoch) publish(mutableState.value.copy(loading = false,
                    message = if (trafficRefresh) "Trafik güncellenemedi. Mevcut rota korunuyor; son trafik bilgisi eski olabilir."
                        else "Yeni rota alınamadı. Mevcut rota korunuyor; bağlantıyı ve rota koşullarını kontrol et."))
            } finally {
                PowerDiagnostics.count(if (trafficRefresh) PowerDiagnostics.Metric.TRAFFIC_REFRESH else PowerDiagnostics.Metric.OFF_ROUTE_PLAN,
                    System.nanoTime() - started)
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
        quickSessionId = null
        rerouteJob?.cancel(); rerouteJob = null
        simulationJob?.cancel(); simulationJob = null
        invalidateEdits()
        engine = null; breaks = null; cues.reset(); lastRerouteAttempt = null; lastTrafficCheckAt = null
        trackEngine = null
        if (mutableState.value.trackFollow != null) updateTrackMarker(false)
        markerProblem = null
        runtime.endGuidanceSpeech()
        publish(mutableState.value.copy(route = null, guidance = false, arrived = false, progress = null,
            loading = false, routeRevision = epoch, message = null, trackFollow = null, paused = false, preparedRouteFingerprint = null,
            progressUpdatedAt = null, approximateOrigin = null, recordingPending = false))
    }

    private fun publish(value: NavigationState, refreshSpeed: Boolean = true) {
        val now = clock()
        val activeBreak = breaks?.snapshot(now).takeIf { value.route != null && value.paused }
        val route = value.route
        val manualPause = value.paused && activeBreak == null
        val canTime = route != null && value.progress != null && !manualPause && !value.arrived &&
            (!value.gpsStale || activeBreak != null)
        val context = if (canTime) RouteScheduleContext(
            if (activeBreak != null) now else value.progressUpdatedAt ?: value.fix?.recordedAt ?: now,
            requireNotNull(value.progress).elapsedSeconds, breaks?.remaining(now))
            else if (route != null && !manualPause && !value.arrived &&
                (quickSessionId == null || !value.gpsStale) &&
                mutableState.value.route?.id == route.id) value.scheduleContext else null
        val timing = if (context != null && route != null) RouteSchedule.remaining(route, context) else null
        val rendered = value.copy(plannedBreak = activeBreak, scheduleContext = context, tripTiming = timing,
            recordingPending = value.recordingPending && !value.recording,
            roadSpeed = runtime.roadSpeedState(value, now))
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
                if (mutableSimulationPoints.subscriptionCount.value > 0) {
                    mutableSimulationPoints.value = (mutableSimulationPoints.value + TrackPoint(journeyId = journey.id,
                        latitude = coordinate.latitude, longitude = coordinate.longitude, recordedAt = now, accuracy = 3f, speed = 12f)).takeLast(256)
                } else if (mutableSimulationPoints.value.isNotEmpty()) mutableSimulationPoints.value = emptyList()
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
