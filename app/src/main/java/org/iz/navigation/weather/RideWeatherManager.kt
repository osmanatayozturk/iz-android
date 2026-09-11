package org.iz.navigation.weather

import android.content.Context
import org.iz.navigation.data.DiaryRepository
import org.iz.navigation.data.Journey
import org.iz.navigation.data.JourneyStatus
import org.iz.navigation.data.Transport
import org.iz.navigation.tracking.TrackingController
import org.iz.navigation.navigation.JourneyNavigationCoordinator
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.wearprotocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RideWeatherStatus { OFF, LOADING, READY, ERROR }
data class RideWeatherLiveState(
    val status: RideWeatherStatus = RideWeatherStatus.OFF,
    val journeyId: String? = null,
    val route: PlannedRoute? = null,
    val assessment: WeatherAssessment? = null,
    val remainingMeters: Double = 0.0,
    val arrivalAt: Long? = null,
    val gpsStale: Boolean = false,
    val message: String? = null,
    val remainingSeconds: Double? = null,
    val timingUpdatedAt: Long? = null,
    // An in-process display signal; cached forecasts can remain READY after a failed refresh.
    val refreshFailed: Boolean = false,
)

interface RideWeatherJourneySource {
    val journeys: Flow<List<Journey>>
    suspend fun activeJourney(): Journey?
    suspend fun startJourney(transport: Transport): String
}
private class DiaryWeatherJourneySource(context: Context) : RideWeatherJourneySource {
    private val repository = DiaryRepository(context)
    private val tracker = TrackingController(context)
    override val journeys get() = repository.journeys
    override suspend fun activeJourney() = repository.activeJourney()
    override suspend fun startJourney(transport: Transport) = tracker.startManual(transport)
}

interface RideWeatherNavigationSource {
    val state: StateFlow<NavigationState>
    suspend fun activateRoute(route: PlannedRoute): String
    suspend fun startGuidance(route: PlannedRoute): String = error("Sesli yol tarifi başlatılamadı.")
}

interface RideWeatherAlertOutput {
    fun announce(journeyId: String, hazards: Set<WeatherHazard>, assessment: WeatherAssessment, voiceEnabled: Boolean, transport: Transport = Transport.MOTORCYCLE)
    fun clear()
    fun testVoice()
    fun suspendVoice() = Unit
}

/**
 * One phone-owned weather session. GPS callbacks only enqueue, and all HTTP runs in
 * cancellable child jobs outside both tracking's mutex and the fix consumer.
 * Saved plans never reactivate this manager after process death.
 */
class RideWeatherManager(
    context: Context,
    private val journeySource: RideWeatherJourneySource = DiaryWeatherJourneySource(context),
    private val plannerFactory: (String) -> RoutePlanner = { ValhallaRoutePlanner(context, it) },
    private val providerFactory: (String) -> WeatherProvider = { OpenMeteoWeatherProvider(context, it) },
    private val output: RideWeatherAlertOutput = WeatherNotifications(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMillis: Long = 15_000L,
    navigation: JourneyNavigationCoordinator? = null,
    private val navigationSource: RideWeatherNavigationSource? = navigation?.let { owner ->
        object : RideWeatherNavigationSource {
            override val state = owner.state
            override suspend fun activateRoute(route: PlannedRoute) = owner.activateRoute(route)
            override suspend fun startGuidance(route: PlannedRoute) = owner.startGuidance(route)
        }
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = WeatherSettingsStore(context)
    private val mutableState = MutableStateFlow(RideWeatherLiveState())
    val state: StateFlow<RideWeatherLiveState> = mutableState.asStateFlow()
    private val activationMutex = Mutex()
    private var disabledJourneyId: String? = null
    private var sharedNavigation: NavigationState? = null
    private var generation = 0L
    private var session: Session? = null
    private var tickJob: Job? = null
    private var journeyJob: Job? = null
    private var fixJob: Job? = null
    private var networkJob: Job? = null
    @Volatile private var fixChannel: Channel<Fix>? = null

    private data class Fix(val journeyId: String, val coordinate: WeatherCoordinate, val recordedAt: Long, val accuracy: Float)
    private class Session(val journeyId: String, var route: PlannedRoute, var forecasts: List<LocationForecast>, val activatedAt: Long) {
        var routeRevision = 0L
        var freeDrive = false
        // Advance only when a forecast response covers the current position, never on each GPS fix.
        var freeDriveWeatherCoordinate = route.vertices.first().coordinate
        val transport = route.transport
        var tracker = RouteProgressTracker(route)
        var progress = RouteProgress(0.0, route.durationSeconds, route.distanceMeters, 0.0)
        var timing: RouteTravelTiming? = null
        var lastFix: Fix? = null
        var lastEvaluatedAt = Long.MIN_VALUE
        var lastFetchAttemptAt = activatedAt
        var lastRouteAttemptAt: Long? = null
        var lastSettings: RideWeatherSettings? = null
        var problem: String? = null
        var pendingReroute = false
        val deviation = OffRouteGate()
        val alerts = WeatherAlertGate()
    }

    init {
        navigationSource?.let { source ->
            scope.launch { source.state.collect { consumeNavigation(it) } }
        }
    }

    // Weather follows the live session. The legacy journey fallback supports callers without
    // a navigation session; the Wear adapter below still requires an actual confirmed recording.
    private fun NavigationState.weatherOwnerId(): String? =
        if (simulation) null else sessionId ?: journey?.id?.takeIf { recording }

    private fun consumeNavigation(value: NavigationState) {
        sharedNavigation = value
        val ownerId = value.weatherOwnerId()
        val transport = value.sessionTransport ?: value.journey?.transport
        if (ownerId == null || transport == null) {
            if (session != null || mutableState.value.status != RideWeatherStatus.OFF) reset()
            return
        }
        if (disabledJourneyId == ownerId) return
        disabledJourneyId = null
        if (transport == Transport.UNKNOWN) return
        val route = value.route ?: value.fix?.let { fix ->
            // Single-point weather sampling adapter; never published as a planned route.
            PlannedRoute("current-location", listOf(RouteStop("Mevcut konum", fix.coordinate),
                RouteStop("Mevcut konum", fix.coordinate)), listOf(RouteVertex(fix.coordinate, 0.0)),
                0.0, 0.0, clock(), transport = transport)
        } ?: run {
            if (session != null) reset()
            mutableState.value = RideWeatherLiveState(RideWeatherStatus.LOADING, ownerId,
                gpsStale = true, message = "Mevcut konumun havası için konum bekleniyor.")
            return
        }
        val existing = session
        val replace = existing == null || existing.journeyId != ownerId ||
            existing.routeRevision != value.routeRevision || existing.freeDrive != (value.route == null) ||
            existing.transport != transport
        val current = if (replace) {
            reset()
            Session(ownerId, route, emptyList(), clock()).also { next ->
                next.routeRevision = value.routeRevision
                next.freeDrive = value.route == null
                next.lastFetchAttemptAt = Long.MIN_VALUE / 2
                session = next
                mutableState.value = RideWeatherLiveState(RideWeatherStatus.LOADING, ownerId, value.route)
                val token = generation
                tickJob = scope.launch {
                    while (isActive && isCurrent(next, token)) {
                        evaluate(next)
                        requestNetwork(next, false, freeDriveNeedsRefresh(next))
                        delay(tickMillis.coerceAtLeast(25L))
                    }
                }
            }
        } else requireNotNull(existing)
        value.fix?.let { current.lastFix = Fix(ownerId, it.coordinate, it.recordedAt, it.accuracyMeters) }
        value.progress?.let {
            current.progress = RouteProgress(it.elapsedSeconds, it.remainingSeconds,
                it.remainingMeters, it.distanceFromRouteMeters)
        }
        if (current.freeDrive) current.route = route
        val moved = freeDriveNeedsRefresh(current)
        // Assessment rejects forecasts outside 100 m; retaining them also covers a return during cooldown.
        evaluate(current, force = replace || moved)
        requestNetwork(current, false, moved || current.freeDrive && current.forecasts.isEmpty())
    }

    /** Explicit planner Start records and enables turn guidance through the shared owner. */
    suspend fun activateGuidance(route: PlannedRoute, forecasts: List<LocationForecast>): String =
        withContext(Dispatchers.Main.immediate) {
            activationMutex.withLock {
                val source = checkNotNull(navigationSource) { "Sesli yol tarifi şu an kullanılamıyor." }
                val id = source.startGuidance(route)
                currentCoroutineContext().ensureActive()
                // Starting again explicitly also resumes weather disabled earlier on this trip.
                disabledJourneyId = null
                consumeNavigation(source.state.value)
                session?.takeIf { it.journeyId == id && it.route.id == route.id }?.let {
                    if (forecasts.isNotEmpty()) it.forecasts = forecasts
                    evaluate(it, force = true)
                }
                id
            }
        }

    /** Retained for callers that intentionally activate route weather without spoken guidance. */
    suspend fun activate(route: PlannedRoute, forecasts: List<LocationForecast>): String =
        withContext(Dispatchers.Main.immediate) {
            activationMutex.withLock {
                navigationSource?.let { source ->
                    disabledJourneyId = null
                    val id = source.activateRoute(route)
                    currentCoroutineContext().ensureActive()
                    consumeNavigation(source.state.value)
                    session?.takeIf { it.journeyId == id && it.route.id == route.id }?.let {
                        if (forecasts.isNotEmpty()) it.forecasts = forecasts
                        evaluate(it, force = true)
                    }
                    return@withLock id
                }
                require(route.stops.size in 2..6 && route.vertices.size >= 2 && route.durationSeconds > 0) { "Önce geçerli bir rota hesapla." }
                // No TrackingCoordinator lock here: startManual owns that non-reentrant lock.
                val activationGeneration = generation
                val before = journeySource.activeJourney()
                currentCoroutineContext().ensureActive()
                check(generation == activationGeneration) { "Yolculuk durumu değişti. Yeniden başlat." }
                if (before != null) require(matchesJourney(before, route.transport)) { "Açık yolculuğun türü planla aynı olmalı ve kayıt onaylanmış olmalı." }
                val id = before?.id ?: journeySource.startJourney(route.transport)
                val actual = journeySource.activeJourney()
                currentCoroutineContext().ensureActive()
                check(generation == activationGeneration) { "Yolculuk durumu değişti. Yeniden başlat." }
                check(actual?.id == id && matchesJourney(actual, route.transport)) { "Seçili türde yolculuk kaydı başlatılamadı." }
                currentCoroutineContext().ensureActive()
                reset()
                val current = Session(id, route, forecasts, clock())
                session = current
                val token = generation
                val fixes = Channel<Fix>(Channel.CONFLATED)
                fixChannel = fixes
                mutableState.value = RideWeatherLiveState(RideWeatherStatus.LOADING, id, route, remainingMeters = route.distanceMeters, gpsStale = true)
                evaluate(current, force = true)
                fixJob = scope.launch {
                    for (fix in fixes) {
                        if (!isCurrent(current, token)) break
                        if (fix.journeyId != id || !RideWeatherTiming.gpsFresh(fix.recordedAt, clock()) ||
                            !fix.accuracy.isFinite() || fix.accuracy !in 0f..50f ||
                            current.lastFix?.let { fix.recordedAt <= it.recordedAt } == true) continue
                        val tracker = current.tracker
                        val progress = withContext(Dispatchers.Default) { tracker.project(fix.coordinate, fix.accuracy) }
                        if (!isCurrent(current, token) || current.tracker !== tracker) continue
                        current.lastFix = fix
                        current.progress = progress
                        if (progress.distanceFromRouteMeters <= 250.0) current.pendingReroute = false
                        if (current.deviation.accept(progress.distanceFromRouteMeters, fix.accuracy, fix.recordedAt, clock())) {
                            current.pendingReroute = true
                            requestNetwork(current, reroute = true, force = true)
                        }
                        evaluate(current, force = current.lastEvaluatedAt == Long.MIN_VALUE || mutableState.value.gpsStale)
                    }
                }
                journeyJob = scope.launch {
                    try {
                        journeySource.journeys.collect { values ->
                            if (!isCurrent(current, token)) return@collect
                            val active = values.firstOrNull { it.id == id }
                            if (active == null || !matchesJourney(active, current.transport)) reset()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (isCurrent(current, token)) reset() }
                }
                tickJob = scope.launch {
                    while (isActive && isCurrent(current, token)) {
                        evaluate(current)
                        if (current.pendingReroute) requestNetwork(current, true, true)
                        else if (clock() - current.lastFetchAttemptAt >= RideWeatherTiming.REFRESH_MS) requestNetwork(current, false, false)
                        delay(tickMillis.coerceAtLeast(25L))
                    }
                }
                id
            }
        }

    fun onAcceptedLocation(journeyId: String, coordinate: WeatherCoordinate, recordedAt: Long, accuracyMeters: Float) {
        // Do not suspend, parse JSON, touch TTS or wait for HTTP in the tracking callback.
        if (navigationSource == null) fixChannel?.trySend(Fix(journeyId, coordinate, recordedAt, accuracyMeters))
    }

    fun onTrackingStopped(journeyId: String?) {
        if (navigationSource == null) scope.launch { if (journeyId == null || session?.journeyId == journeyId) reset() }
    }

    fun stop() { scope.launch {
        disabledJourneyId = navigationSource?.state?.value?.weatherOwnerId()
        reset()
    } }
    fun disableWeather() = stop()
    fun testVoice() { scope.launch { runCatching { output.testVoice() } } }
    fun preferencesChanged() { scope.launch { session?.let { evaluate(it, force = true) } } }
    fun refresh(): Job = scope.launch {
        session?.let {
            evaluate(it, force = true)
            requestNetwork(it, reroute = false, force = true)
            networkJob?.join()
        }
    }

    private fun isCurrent(value: Session, token: Long): Boolean {
        if (session !== value || generation != token) return false
        val shared = navigationSource?.state?.value ?: return true
        return shared.weatherOwnerId() == value.journeyId &&
            (shared.sessionTransport ?: shared.journey?.transport) == value.transport &&
            shared.routeRevision == value.routeRevision
    }
    private fun matchesJourney(value: Journey, transport: Transport) = value.transport == transport && transport != Transport.UNKNOWN &&
        value.status == JourneyStatus.CONFIRMED && value.endedAt == null && !value.interrupted

    private fun reset() {
        generation++
        session = null
        tickJob?.cancel(); tickJob = null
        journeyJob?.cancel(); journeyJob = null
        fixJob?.cancel(); fixJob = null
        networkJob?.cancel(); networkJob = null
        fixChannel?.close(); fixChannel = null
        mutableState.value = RideWeatherLiveState()
        runCatching { output.clear() }
    }

    private fun evaluate(current: Session, force: Boolean = false) {
        if (session !== current) return
        val now = clock()
        val wasGpsStale = mutableState.value.gpsStale
        val timing = publishTiming(current, now)
        val gpsFresh = !timing.gpsStale
        val preferences = settings.read(current.transport)
        if (!preferences.alertsEnabled && current.lastSettings?.alertsEnabled != false) runCatching { output.clear() }
        if (!preferences.voiceEnabled || !RideWeatherTiming.forecastFresh(mutableState.value.assessment?.fetchedAt,now)) runCatching { output.suspendVoice() }
        val shouldEvaluate = force || current.lastEvaluatedAt == Long.MIN_VALUE ||
            now - current.lastEvaluatedAt >= RideWeatherTiming.EVALUATE_MS ||
            preferences != current.lastSettings
        if (!shouldEvaluate) {
            if (wasGpsStale != timing.gpsStale) mutableState.update {
                it.copy(gpsStale = !gpsFresh, message = if (!gpsFresh) "Konum güncel değil; hava uyarıları duraklatıldı." else current.problem)
            }
            return
        }
        current.lastSettings = preferences
        current.lastEvaluatedAt = now
        val progress = current.progress
        val assessment = WeatherEngine.assess(current.route, now - (progress.elapsedSeconds * 1000).toLong(),
            current.forecasts, preferences.thresholds, progress.elapsedSeconds)
        val forecastFresh = RideWeatherTiming.forecastFresh(assessment.fetchedAt, now)
        if (!forecastFresh || !assessment.complete) runCatching { output.suspendVoice() }
        val availabilityMessage = when {
            !gpsFresh -> "Konum bekleniyor; konuma bağlı hava uyarıları duraklatıldı."
            !forecastFresh -> "Hava tahmini güncel değil; son sonuç gösteriliyor."
            !assessment.complete -> "Rotanın bazı bölümlerinde hava verisi eksik."
            else -> null
        }
        val message = listOfNotNull(availabilityMessage, current.problem).distinct()
            .joinToString(" ").ifBlank { null }
        mutableState.update { it.copy(
            status = if (assessment.samples.isEmpty() || current.problem != null && assessment.fetchedAt == null)
                RideWeatherStatus.ERROR else RideWeatherStatus.READY,
            journeyId = current.journeyId, route = current.route.takeUnless { current.freeDrive }, assessment = assessment,
            refreshFailed = current.problem != null,
            message = if (current.freeDrive) "Mevcut konum havası; ilerideki yol için tahmin değildir." + (message?.let { " $it" } ?: "") else message,
        ) }
        val future = assessment.samples.filter { it.arrivalAt in now..(now + 3_600_000L) && it.complete }
        val hazards = future.flatMap { it.hazards }.toSet()
        val events = current.alerts.events(hazards.map { it.name }.toSet(), now,
            preferences.alertsEnabled && !current.freeDrive && gpsFresh && forecastFresh && assessment.complete && progress.remainingMeters > 30.0)
        if (events.isNotEmpty()) runCatching {
            output.announce(current.journeyId, events.mapNotNull { runCatching { WeatherHazard.valueOf(it) }.getOrNull() }.toSet(), assessment, preferences.voiceEnabled, current.transport)
        }
    }

    /** Every accepted position/tick can update ETA while weather assessment keeps its own interval. */
    private fun publishTiming(current: Session, now: Long): RouteTravelTiming {
        val sharedStale = navigationSource != null && sharedNavigation?.gpsStale != false
        val timing = RouteTravelTime.advance(current.timing, current.progress.remainingSeconds,
            current.lastFix?.recordedAt?.takeUnless { sharedStale }, now)
        // State collectors may resume on another thread as soon as gpsStale is published.
        // Cancel pending automatic speech first, so stale state never precedes cancellation.
        if (timing.gpsStale) runCatching { output.suspendVoice() }
        if (current.freeDrive) {
            mutableState.update { it.copy(route = null, remainingMeters = 0.0, remainingSeconds = null,
                arrivalAt = null, timingUpdatedAt = null, gpsStale = timing.gpsStale) }
            return timing
        }
        current.timing = timing
        mutableState.update { it.copy(remainingMeters = current.progress.remainingMeters,
            remainingSeconds = timing.remainingSeconds, arrivalAt = timing.arrivalAt,
            timingUpdatedAt = timing.updatedAt, gpsStale = timing.gpsStale) }
        return timing
    }

    private fun freeDriveNeedsRefresh(current: Session): Boolean = current.freeDrive &&
        WeatherEngine.distanceMeters(current.freeDriveWeatherCoordinate, current.route.vertices.first().coordinate) > 100.0

    private fun requestNetwork(current: Session, reroute: Boolean, force: Boolean) {
        if (session !== current || networkJob?.isActive == true) return
        if (navigationSource != null && reroute) return
        val now = clock()
        val minInterval = if (force) 60_000L else RideWeatherTiming.REFRESH_MS
        if (reroute) {
            if (current.lastRouteAttemptAt?.let { now - it < 60_000L } == true) return
        } else if (now - current.lastFetchAttemptAt < minInterval) return
        val fix = current.lastFix
        if (reroute && (fix == null || !RideWeatherTiming.gpsFresh(fix.recordedAt, now))) return
        val token = generation
        val oldRoute = current.route
        val revision = current.routeRevision
        val remainingStops = if (navigationSource == null) current.tracker.remainingStops(current.progress.elapsedSeconds) else emptyList()
        val preferences = settings.read(current.transport)
        current.lastFetchAttemptAt = now
        if (reroute) { current.lastRouteAttemptAt = now; current.pendingReroute = false }
        networkJob = scope.launch {
            try {
                val route = if (reroute) plannerFactory(preferences.routeEndpoint).plan(
                    listOf(RouteStop("Mevcut konum", fix!!.coordinate)) + remainingStops, now, current.transport, oldRoute.travelSpeedKmh,
                ) else oldRoute
                require(route.transport == current.transport) { "Rota servisi farklı ulaşım türünde rota döndürdü." }
                currentCoroutineContext().ensureActive()
                if (!isCurrent(current, token) || current.route.id != oldRoute.id || current.routeRevision != revision) return@launch
                val coordinates = WeatherEngine.sampleVertices(route).map { it.coordinate }
                val forecasts = providerFactory(preferences.weatherEndpoint).hourly(coordinates, now, now + (route.durationSeconds * 1000).toLong() + 3_600_000L)
                currentCoroutineContext().ensureActive()
                if (!isCurrent(current, token) || current.route.id != oldRoute.id || current.routeRevision != revision) return@launch
                // Ensure an in-place mode edit/end cannot receive a late successful response.
                if (navigationSource == null) {
                    val journey = journeySource.activeJourney()
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(current, token) || current.route.id != oldRoute.id || current.routeRevision != revision) return@launch
                    if (journey?.id != current.journeyId || !matchesJourney(journey, current.transport)) { reset(); return@launch }
                }
                if (current.freeDrive) {
                    val requestedCoordinate = route.vertices.first().coordinate
                    // Free-drive routes share an ID. A delayed response must not satisfy movement
                    // beyond its coverage or clear the pending refresh for the latest position.
                    if (WeatherEngine.distanceMeters(requestedCoordinate, current.route.vertices.first().coordinate) > 100.0) return@launch
                    current.freeDriveWeatherCoordinate = requestedCoordinate
                }
                if (reroute) {
                    val replacement = RouteProgressTracker(route)
                    val latestFix = current.lastFix?.takeIf { RideWeatherTiming.gpsFresh(it.recordedAt, clock()) }
                    val progress = if (latestFix != null) withContext(Dispatchers.Default) {
                        replacement.project(latestFix.coordinate, latestFix.accuracy)
                    } else RouteProgress(0.0, route.durationSeconds, route.distanceMeters, 0.0)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent(current, token) || current.route.id != oldRoute.id || current.routeRevision != revision) return@launch
                    current.route = route
                    current.tracker = replacement
                    current.progress = progress
                    current.timing = null
                }
                current.forecasts = forecasts
                current.problem = null
                evaluate(current, force = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (isCurrent(current, token)) {
                    current.problem = (if (reroute) "Rota yenilenemedi. " else "Hava tahmini yenilenemedi. ") +
                        (error.message ?: "Bağlantıyı kontrol et.").take(180)
                    evaluate(current, force = true)
                }
            } finally {
                if (isCurrent(current, token)) {
                    networkJob = null
                    if (current.pendingReroute) requestNetwork(current, reroute = true, force = true)
                    else if (freeDriveNeedsRefresh(current)) requestNetwork(current, reroute = false, force = true)
                }
            }
        }
    }

    fun wearWeather(journeyId: String): WearRouteWeather? {
        val value = state.value
        if (value.status == RideWeatherStatus.OFF) return null
        val shared = navigationSource?.state?.value
        if (shared != null) {
            if (!shared.recording || shared.journey?.id != journeyId || shared.weatherOwnerId() != value.journeyId) return null
        } else if (value.journeyId != journeyId) return null
        val now = clock()
        val cachedAt = value.assessment?.fetchedAt
        if (value.gpsStale) return WearRouteWeather(WearWeatherStatus.ERROR, cachedAt ?: now,
            cachedAt?.let { it + RideWeatherTiming.FORECAST_TTL_MS } ?: now,
            value.remainingMeters, value.arrivalAt, headline = "Konum bekleniyor", detail = "Hava uyarıları duraklatıldı.")
        val assessment = value.assessment
        if (value.status == RideWeatherStatus.LOADING) return WearRouteWeather(WearWeatherStatus.LOADING, now, now,
            value.remainingMeters, value.arrivalAt, headline = "Hava hazırlanıyor")
        val fetchedAt = assessment?.fetchedAt
        if (assessment == null || fetchedAt == null || value.status == RideWeatherStatus.ERROR) return WearRouteWeather(
            WearWeatherStatus.ERROR, now, now, value.remainingMeters, value.arrivalAt,
            headline = "Hava alınamadı", detail = value.message.orEmpty().take(90),
        )
        if (!assessment.complete) return WearRouteWeather(
            WearWeatherStatus.ERROR, fetchedAt, fetchedAt + RideWeatherTiming.FORECAST_TTL_MS,
            value.remainingMeters, value.arrivalAt, headline = "Tahmin kısmen eksik",
            detail = "Bazı bölümlerde tahmin eksik; hava uyarıları duraklatıldı.",
        )
        val next = assessment.samples.firstOrNull { it.complete && it.hazards.isNotEmpty() && it.arrivalAt >= now }
        val message = if (!assessment.complete) "Bazı bölümlerde tahmin eksik." else {
            val rain = assessment.maxPrecipitationProbabilityPercent?.toInt()?.toString() ?: "—"
            val wind = assessment.maxWindKmh?.toInt()?.toString() ?: "—"
            val gust = assessment.maxGustKmh?.toInt()?.toString() ?: "—"
            "Yağış %$rain · Rüzgâr $wind · Hamle $gust km/sa"
        }
        return WearRouteWeather(WearWeatherStatus.READY, fetchedAt, fetchedAt + RideWeatherTiming.FORECAST_TTL_MS,
            value.remainingMeters, value.arrivalAt, next?.arrivalAt,
            if (next != null) WearWeatherThreshold.EXCEEDED else WearWeatherThreshold.BELOW_THRESHOLD,
            when { value.route == null -> "Mevcut konum havası"; !assessment.complete -> "Tahmin kısmen eksik"; next != null -> "Hava eşiği aşılıyor"; else -> "Eşiklerin altında" }, if (value.route == null) "Yalnızca mevcut konum. $message" else message,
        )
    }
}
