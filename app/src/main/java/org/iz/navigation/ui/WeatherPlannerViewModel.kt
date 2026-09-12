package org.iz.navigation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.iz.navigation.IzApplication
import org.iz.navigation.data.Transport
import org.iz.navigation.navigation.prepareRouteStartStops
import org.iz.navigation.weather.ConfiguredRoutePlanner
import org.iz.navigation.weather.LocationForecast
import org.iz.navigation.weather.OpenMeteoWeatherProvider
import org.iz.navigation.weather.PlannedRoute
import org.iz.navigation.weather.RideWeatherSettings
import org.iz.navigation.weather.RoutePlanner
import org.iz.navigation.weather.RouteStop
import org.iz.navigation.weather.SavedWeatherPlan
import org.iz.navigation.weather.ValhallaRoutePlanner
import org.iz.navigation.weather.VerifiedTomTomRoutePlanner
import org.iz.navigation.weather.TrafficSettingsStore
import org.iz.navigation.weather.RouteProvider
import org.iz.navigation.weather.StopOrderProposal
import org.iz.navigation.weather.ConfiguredStopOrderPlanner
import org.iz.navigation.weather.WeatherAssessment
import org.iz.navigation.weather.WeatherCoordinate
import org.iz.navigation.weather.WeatherEngine
import org.iz.navigation.weather.WeatherPlanStore
import org.iz.navigation.weather.WeatherProvider
import org.iz.navigation.weather.WeatherSettingsStore
import org.iz.navigation.weather.WeatherThresholds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal const val WEATHER_PLAN_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L

internal data class WeatherPlannerState(
    val stops: List<RouteStop> = emptyList(),
    val departureAt: Long = 0L,
    val departureIsNow: Boolean = true,
    val route: PlannedRoute? = null,
    val forecasts: List<LocationForecast> = emptyList(),
    val comparisons: List<WeatherAssessment> = emptyList(),
    val selectedDepartureAt: Long? = null,
    val settings: RideWeatherSettings = RideWeatherSettings(),
    val transport: Transport = Transport.MOTORCYCLE,
    val busy: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
    val activeJourneyId: String? = null,
    val originUsesCurrentLocation: Boolean = false,
    val requestedDepartureAt: Long? = null,
    val effectiveDepartureAt: Long? = null,
    val selectedWeather: WeatherAssessment? = null,
    val verifiedDepartures: Set<Long> = emptySet(),
    val trafficFreeDeparture: Long? = null,
    val ordering: Boolean = false,
    val orderProposal: StopOrderProposal? = null,
) {
    val selectedAssessment: WeatherAssessment?
        get() = selectedWeather ?: comparisons.firstOrNull { it.departureAt == selectedDepartureAt }
    val canStart: Boolean
        get() = route?.transport == transport && route.vertices.size >= 2 && route.durationSeconds > 0 && !starting
}

internal data class WeatherPlannerServices(
    val routePlanner: (RideWeatherSettings) -> RoutePlanner,
    val weatherProvider: (RideWeatherSettings) -> WeatherProvider,
    val readSettings: (Transport) -> RideWeatherSettings,
    val saveSettings: (RideWeatherSettings, Transport) -> Unit,
    val savePlan: (SavedWeatherPlan) -> Unit,
    val activate: suspend (PlannedRoute, List<LocationForecast>) -> String,
    val compare: (PlannedRoute, Long, List<LocationForecast>, WeatherThresholds) -> List<WeatherAssessment> =
        { route, departure, forecasts, thresholds -> WeatherEngine.compare(route, departure, forecasts, thresholds) },
    val clock: () -> Long = System::currentTimeMillis,
    val startRoutePlanner: (RideWeatherSettings) -> RoutePlanner = routePlanner,
    val verificationPlanner: (RideWeatherSettings) -> RoutePlanner = startRoutePlanner,
    val trafficFreePlanner: (RideWeatherSettings) -> RoutePlanner = routePlanner,
    val credentialRevision: () -> String = { "" },
    val suggestOrder: suspend (List<RouteStop>, Long, Transport) -> StopOrderProposal? = { _, _, _ -> null },
)

internal data class WeatherDepartureBundle(
    val route: PlannedRoute,
    val forecasts: List<LocationForecast>,
    val assessment: WeatherAssessment,
    val calculatedAt: Long,
)

internal fun recommendedAssessment(values: List<WeatherAssessment>): WeatherAssessment? =
    values.asSequence().filter { it.complete }
        .minWithOrNull(compareBy<WeatherAssessment> { it.exceededSeconds }.thenBy { it.departureAt })

internal class WeatherPlannerCoordinator(
    private val scope: CoroutineScope,
    private val services: WeatherPlannerServices,
    initialPlan: SavedWeatherPlan?,
) {
    private val initialNow = services.clock()
    private val mutableState = MutableStateFlow(
        WeatherPlannerState(
            stops = initialPlan?.stops.orEmpty(),
            departureAt = initialPlan?.departureAt?.takeIf { it >= initialNow } ?: initialNow,
            departureIsNow = initialPlan == null || initialPlan.departureAt < initialNow,
            transport = initialPlan?.transport ?: Transport.MOTORCYCLE,
            settings = services.readSettings(initialPlan?.transport ?: Transport.MOTORCYCLE),
            originUsesCurrentLocation = initialPlan?.stops?.firstOrNull()?.label == "Mevcut konum",
        ),
    )
    val state: StateFlow<WeatherPlannerState> = mutableState.asStateFlow()
    private var generation = 0L
    private var selectionJob: Job? = null
    private val candidates = mutableMapOf<Long, WeatherDepartureBundle>()
    private var baseComparisons = emptyList<WeatherAssessment>()
    private var credentialsRevision = services.credentialRevision()

    private fun invalidateCandidates() {
        selectionJob?.cancel()
        selectionJob = null
        candidates.clear()
        baseComparisons = emptyList()
        mutableState.value = mutableState.value.copy(requestedDepartureAt = null, effectiveDepartureAt = null,
            selectedWeather = null, verifiedDepartures = emptySet(), trafficFreeDeparture = null,
            ordering = false, orderProposal = null)
        credentialsRevision = services.credentialRevision()
    }

    fun refreshCredentials() {
        if (credentialsRevision == services.credentialRevision()) return
        generation++
        invalidateCandidates()
        mutableState.value = mutableState.value.copy(route = null, forecasts = emptyList(), comparisons = emptyList(),
            selectedDepartureAt = null, busy = false, error = null)
    }

    fun setTransport(transport: Transport) {
        require(transport != Transport.UNKNOWN) { "Bir yolculuk türü seç." }
        val current = mutableState.value
        if (current.starting || current.transport == transport) return
        val settings = services.readSettings(transport)
        generation++
        mutableState.value = current.copy(
            transport = transport,
            settings = settings,
            route = null,
            forecasts = emptyList(),
            comparisons = emptyList(),
            selectedDepartureAt = null,
            busy = false,
            error = null,
            activeJourneyId = null,
        )
        invalidateCandidates()
    }

    fun setStops(stops: List<RouteStop>) {
        if (mutableState.value.starting) return
        require(stops.size <= 5) { "En fazla üç ara durak ekleyebilirsin." }
        generation++
        mutableState.value = mutableState.value.copy(
            stops = stops,
            route = null,
            forecasts = emptyList(),
            comparisons = emptyList(),
            selectedDepartureAt = null,
            busy = false,
            starting = false,
            error = null,
            activeJourneyId = null,
            originUsesCurrentLocation = mutableState.value.originUsesCurrentLocation &&
                stops.firstOrNull() == mutableState.value.stops.firstOrNull(),
        )
        invalidateCandidates()
    }

    fun setCurrentOrigin(coordinate: WeatherCoordinate) {
        if (mutableState.value.starting) return
        setStop(0, RouteStop("Mevcut konum", coordinate))
        mutableState.value = mutableState.value.copy(originUsesCurrentLocation = true)
    }

    fun setStop(index: Int, stop: RouteStop) {
        val stops = mutableState.value.stops.toMutableList()
        require(index in 0..stops.size)
        if (index == stops.size) stops += stop else stops[index] = stop
        setStops(stops)
    }

    fun addVia(stop: RouteStop) {
        val stops = mutableState.value.stops
        require(stops.size in 2..4) { "En fazla üç ara durak ekleyebilirsin." }
        setStops(stops.dropLast(1) + stop + stops.last())
    }

    fun removeStop(index: Int) {
        val stops = mutableState.value.stops
        if (index !in stops.indices || stops.size <= 2) return
        setStops(stops.filterIndexed { candidate, _ -> candidate != index })
    }

    fun setDeparture(value: Long) {
        if (mutableState.value.starting) return
        generation++
        mutableState.value = mutableState.value.copy(
            departureAt = value,
            departureIsNow = false,
            route = null,
            forecasts = emptyList(),
            comparisons = emptyList(),
            selectedDepartureAt = null,
            busy = false,
            error = null,
        )
        invalidateCandidates()
    }

    fun departNow() {
        if (mutableState.value.starting) return
        generation++
        mutableState.value = mutableState.value.copy(
            departureAt = services.clock(),
            departureIsNow = true,
            route = null,
            forecasts = emptyList(),
            comparisons = emptyList(),
            selectedDepartureAt = null,
            busy = false,
            error = null,
        )
        invalidateCandidates()
    }

    fun selectDeparture(value: Long) {
        verifyDeparture(value, trafficFree = false)
    }

    fun continueTrafficFree() {
        mutableState.value.trafficFreeDeparture?.let { verifyDeparture(it, trafficFree = true) }
    }

    fun suggestStopOrder(): Job? {
        refreshCredentials()
        val snapshot = mutableState.value
        if (snapshot.starting || snapshot.busy || snapshot.ordering || snapshot.stops.size !in 4..5 ||
            snapshot.transport !in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)) return null
        val token = ++generation
        val revision = services.credentialRevision()
        mutableState.value = snapshot.copy(ordering = true, orderProposal = null, error = null)
        return scope.launch {
            try {
                val proposal = services.suggestOrder(snapshot.stops,
                    snapshot.selectedDepartureAt ?: if (snapshot.departureIsNow) services.clock() else snapshot.departureAt,
                    snapshot.transport)
                if (token == generation && revision == services.credentialRevision()) mutableState.value = mutableState.value.copy(
                    orderProposal = proposal, error = if (proposal == null) "Daha hızlı bir durak sırası bulunamadı." else null)
            } catch (cancelled: CancellationException) { refreshCredentials(); throw cancelled }
            catch (error: Exception) { if (token == generation) mutableState.value = mutableState.value.copy(error = error.message) }
            finally { if (token == generation) mutableState.value = mutableState.value.copy(ordering = false) }
        }.also { selectionJob = it }
    }

    fun acceptStopOrder() {
        refreshCredentials()
        val snapshot = mutableState.value
        val proposal = snapshot.orderProposal ?: return
        if (snapshot.starting || snapshot.busy || snapshot.ordering) return
        if (proposal.originalStops != snapshot.stops || proposal.credentialRevision != services.credentialRevision() ||
            services.clock() - proposal.createdAt !in 0L..120_000L) {
            mutableState.value = snapshot.copy(orderProposal = null, error = "Öneri güncel değil. Yeniden hesapla.")
            return
        }
        setStops(proposal.orderedStops)
        mutableState.value = mutableState.value.copy(error = "Durak sırası uygulandı. Kalkış seçeneklerini yeniden hesapla.")
    }

    fun dismissStopOrder() { mutableState.value = mutableState.value.copy(orderProposal = null) }

    private fun commitDeparture(requestedAt: Long, bundle: WeatherDepartureBundle) {
        candidates[requestedAt] = bundle
        val comparisons = baseComparisons.map { approximate ->
            candidates[approximate.departureAt]?.assessment?.copy(departureAt = approximate.departureAt) ?: approximate
        }
        mutableState.value = mutableState.value.copy(route = bundle.route, forecasts = bundle.forecasts,
            selectedDepartureAt = requestedAt, effectiveDepartureAt = bundle.assessment.departureAt,
            selectedWeather = bundle.assessment, comparisons = comparisons, requestedDepartureAt = null,
            verifiedDepartures = candidates.filterValues { it.route.provider == RouteProvider.TOMTOM }.keys.toSet(),
            trafficFreeDeparture = null, busy = false, error = null, orderProposal = null, ordering = false)
    }

    private fun verifyDeparture(value: Long, trafficFree: Boolean) {
        refreshCredentials()
        val snapshot = mutableState.value
        if (snapshot.starting || snapshot.comparisons.none { it.departureAt == value }) return
        selectionJob?.cancel()
        val token = ++generation
        val now = services.clock()
        val cached = candidates[value]?.takeIf { now - it.calculatedAt in 0L..120_000L }
        if (!trafficFree && cached != null) { commitDeparture(value, cached); return }
        if (value < now) {
            mutableState.value = snapshot.copy(error = "Bu kalkış saati geçti. Şimdi seçip yeniden hesapla.",
                requestedDepartureAt = null, busy = false, ordering = false, orderProposal = null, trafficFreeDeparture = null)
            return
        }
        val revision = services.credentialRevision()
        mutableState.value = snapshot.copy(requestedDepartureAt = value, busy = true, error = null, trafficFreeDeparture = null,
            orderProposal = null, ordering = false)
        selectionJob = scope.launch {
            try {
                val motorized = snapshot.transport in setOf(Transport.CAR, Transport.PASSENGER, Transport.MOTORCYCLE)
                val planner = if (trafficFree || !motorized) services.trafficFreePlanner(snapshot.settings)
                    else services.verificationPlanner(snapshot.settings)
                val route = planner.plan(snapshot.stops, value, snapshot.transport, snapshot.settings.travelSpeedKmh)
                val calculatedAt = services.clock()
                check(route.transport == snapshot.transport && (trafficFree || !motorized || route.provider == RouteProvider.TOMTOM))
                val departure = route.effectiveDepartureAt ?: value
                val coordinates = WeatherEngine.sampleVertices(route).map { it.coordinate }
                val forecasts = services.weatherProvider(snapshot.settings).hourly(coordinates,
                    (departure - 3_600_000L).coerceAtLeast(0L), departure + (route.durationSeconds * 1000).toLong() + 3_600_000L)
                val assessment = WeatherEngine.assess(route, departure, forecasts, snapshot.settings.thresholds)
                if (token != generation) return@launch
                if (revision != services.credentialRevision()) { refreshCredentials(); return@launch }
                commitDeparture(value, WeatherDepartureBundle(route, forecasts, assessment, calculatedAt))
            } catch (cancelled: CancellationException) { refreshCredentials(); throw cancelled }
            catch (_: Exception) {
                if (revision != services.credentialRevision()) { refreshCredentials(); return@launch }
                if (token == generation) mutableState.value = mutableState.value.copy(busy = false, requestedDepartureAt = null,
                    trafficFreeDeparture = if (!trafficFree) value else null,
                    error = "Seçilen saatin rota ve hava verisi alınamadı. Önceki seçim korunuyor.")
            } finally {
                if (token == generation && mutableState.value.requestedDepartureAt != null)
                    mutableState.value = mutableState.value.copy(busy = false, requestedDepartureAt = null)
            }
        }
    }

    fun saveSettings(value: RideWeatherSettings): Boolean {
        if (mutableState.value.starting) return false
        return try {
        services.saveSettings(value, mutableState.value.transport)
        generation++
        mutableState.value = mutableState.value.copy(
            settings = value,
            route = null,
            forecasts = emptyList(),
            comparisons = emptyList(),
            selectedDepartureAt = null,
            busy = false,
            error = null,
        )
        invalidateCandidates()
        true
    } catch (error: Exception) {
        mutableState.value = mutableState.value.copy(error = error.message ?: "Hava ayarları kaydedilemedi.")
        false
    }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    /** Consume before the screen changes so returning to the planner never reopens navigation. */
    fun consumeNavigationStarted(): String? {
        val id = mutableState.value.activeJourneyId ?: return null
        mutableState.value = mutableState.value.copy(activeJourneyId = null)
        return id
    }

    fun calculate(): Job? {
        refreshCredentials()
        if (mutableState.value.starting) return null
        invalidateCandidates()
        val snapshot = mutableState.value
        if (snapshot.starting) return null
        val now = services.clock()
        val departureAt = if (snapshot.departureIsNow) now else snapshot.departureAt
        val issue = planIssue(snapshot.stops, departureAt, now)
        if (issue != null) {
            mutableState.value = snapshot.copy(error = issue)
            return null
        }
        val token = ++generation
        mutableState.value = snapshot.copy(departureAt = departureAt, route = null, forecasts = emptyList(), comparisons = emptyList(), busy = true, error = null)
        return scope.launch {
            try {
                val result = load(snapshot.stops, departureAt, snapshot.settings, snapshot.transport) { route ->
                    if (token != generation) throw CancellationException("Plan changed")
                    services.savePlan(SavedWeatherPlan(snapshot.stops, departureAt, snapshot.transport))
                    mutableState.value = mutableState.value.copy(route = route, forecasts = emptyList(), comparisons = emptyList(), selectedDepartureAt = null)
                }
                if (token != generation) return@launch
                if (credentialsRevision != services.credentialRevision()) { refreshCredentials(); return@launch }
                services.savePlan(SavedWeatherPlan(snapshot.stops, departureAt, snapshot.transport))
                baseComparisons = result.comparisons
                val first = result.comparisons.first()
                candidates[first.departureAt] = WeatherDepartureBundle(result.route, result.forecasts, first, result.calculatedAt)
                mutableState.value = mutableState.value.copy(
                    route = result.route,
                    forecasts = result.forecasts,
                    comparisons = result.comparisons,
                    selectedDepartureAt = first.departureAt,
                    effectiveDepartureAt = first.departureAt,
                    selectedWeather = first,
                    verifiedDepartures = if (result.route.provider == RouteProvider.TOMTOM) setOf(first.departureAt) else emptySet(),
                    busy = false,
                    error = if (result.comparisons.none { it.complete }) {
                        "Rotanın bazı bölümlerinde hava verisi eksik. Yolculuğu başlatabilirsin."
                    } else null,
                )
            } catch (cancelled: CancellationException) {
                refreshCredentials()
                throw cancelled
            } catch (error: Exception) {
                if (token == generation) mutableState.value = mutableState.value.copy(
                    busy = false,
                    error = error.message ?: "Rota ve hava tahmini alınamadı.",
                )
            }
        }
    }

    fun startFromCurrentLocation(coordinate: WeatherCoordinate): Job? {
        refreshCredentials()
        val snapshot = mutableState.value
        if (!snapshot.canStart || snapshot.stops.size !in 2..5) {
            mutableState.value = snapshot.copy(error = "Önce geçerli bir rota hesapla.")
            return null
        }
        val now = services.clock()
        val userStops = snapshot.stops.toMutableList().also {
            if (snapshot.originUsesCurrentLocation) it[0] = RouteStop("Mevcut konum", coordinate)
        }
        val freshStops = prepareRouteStartStops(userStops, coordinate)
        invalidateCandidates()
        val token = ++generation
        mutableState.value = snapshot.copy(starting = true, busy = false, error = null, activeJourneyId = null,
            ordering = false, orderProposal = null, requestedDepartureAt = null, trafficFreeDeparture = null)
        return scope.launch {
            try {
                val route = services.startRoutePlanner(snapshot.settings).plan(
                    freshStops, now, snapshot.transport, snapshot.settings.travelSpeedKmh)
                check(route.transport == snapshot.transport && route.vertices.size >= 2 && route.durationSeconds > 0) {
                    "Rota seçili yolculuk türüyle eşleşmedi."
                }
                if (token != generation) return@launch
                // A draft write must not fail after the recorder has already started.
                services.savePlan(SavedWeatherPlan(userStops, now, snapshot.transport))
                // Recording/ETA start immediately; the weather manager retrieves forecasts independently.
                val journeyId = services.activate(route, emptyList())
                if (token != generation) return@launch
                mutableState.value = mutableState.value.copy(
                    stops = userStops,
                    departureAt = now,
                    departureIsNow = true,
                    route = route,
                    forecasts = emptyList(),
                    comparisons = emptyList(),
                    selectedDepartureAt = now,
                    effectiveDepartureAt = route.effectiveDepartureAt ?: now,
                    selectedWeather = null,
                    requestedDepartureAt = null,
                    verifiedDepartures = emptySet(),
                    starting = false,
                    busy = false,
                    activeJourneyId = journeyId,
                    error = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (token == generation) mutableState.value = mutableState.value.copy(
                    starting = false,
                    busy = false,
                    error = error.message ?: "Yolculuk kaydı başlatılamadı.",
                )
            } finally {
                // A provider may cancel just this request when its settings revision changes.
                if (token == generation && mutableState.value.starting) {
                    mutableState.value = mutableState.value.copy(starting = false, busy = false)
                }
            }
        }
    }

    private suspend fun load(
        stops: List<RouteStop>,
        departureAt: Long,
        settings: RideWeatherSettings,
        transport: Transport,
        onRoute: (PlannedRoute) -> Unit = {},
    ): LoadedWeatherPlan {
        val route = services.routePlanner(settings).plan(stops, departureAt, transport, settings.travelSpeedKmh)
        val calculatedAt = services.clock()
        check(route.transport == transport) { "Rota seçili yolculuk türüyle eşleşmedi." }
        onRoute(route)
        val coordinates = WeatherEngine.sampleVertices(route).map { it.coordinate }
        val effectiveDeparture = route.effectiveDepartureAt ?: departureAt
        val until = effectiveDeparture + (route.durationSeconds * 1_000.0).toLong() + 4L * 60L * 60L * 1_000L
        val forecasts = services.weatherProvider(settings).hourly(
            coordinates,
            (effectiveDeparture - 60L * 60L * 1_000L).coerceAtLeast(0L),
            until,
        )
        val comparisons = services.compare(route, effectiveDeparture, forecasts, settings.thresholds)
        check(comparisons.size == 7) { "Yedi kalkış seçeneği hesaplanamadı." }
        return LoadedWeatherPlan(route, forecasts, comparisons, calculatedAt)
    }

    private data class LoadedWeatherPlan(
        val route: PlannedRoute,
        val forecasts: List<LocationForecast>,
        val comparisons: List<WeatherAssessment>,
        val calculatedAt: Long,
    )
}

internal fun planIssue(stops: List<RouteStop>, departureAt: Long, now: Long): String? = when {
    stops.size !in 2..5 -> "Başlangıç ve varış noktalarını seç."
    departureAt < now -> "Kalkış zamanı geçmişte olamaz."
    departureAt > now + WEATHER_PLAN_WINDOW_MS -> "Kalkış en fazla yedi gün sonrası olabilir."
    else -> null
}

internal class WeatherPlannerViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = WeatherSettingsStore(application)
    private val planStore = WeatherPlanStore(application)
    private val manager = (application as IzApplication).weatherManager
    private val coordinator = WeatherPlannerCoordinator(
        scope = viewModelScope,
        initialPlan = planStore.read(),
        services = WeatherPlannerServices(
            routePlanner = { ConfiguredRoutePlanner(application) },
            weatherProvider = { settings -> OpenMeteoWeatherProvider(application, settings.weatherEndpoint) },
            readSettings = settingsStore::read,
            saveSettings = settingsStore::save,
            savePlan = planStore::save,
            activate = manager::activateGuidance,
            startRoutePlanner = { ConfiguredRoutePlanner(application) },
            verificationPlanner = { VerifiedTomTomRoutePlanner(application) },
            trafficFreePlanner = { settings -> ValhallaRoutePlanner(application, settings.routeEndpoint) },
            credentialRevision = { TrafficSettingsStore(application).read().revision },
            suggestOrder = ConfiguredStopOrderPlanner(application)::propose,
        ),
    )
    val state: StateFlow<WeatherPlannerState> = coordinator.state
    val liveState = manager.state

    fun setTransport(value: Transport) = coordinator.setTransport(value)
    fun setStops(stops: List<RouteStop>) = coordinator.setStops(stops)
    fun setStop(index: Int, stop: RouteStop) = coordinator.setStop(index, stop)
    fun setCurrentOrigin(value: WeatherCoordinate) = coordinator.setCurrentOrigin(value)
    fun addVia(stop: RouteStop) = coordinator.addVia(stop)
    fun removeStop(index: Int) = coordinator.removeStop(index)
    fun setDeparture(value: Long) = coordinator.setDeparture(value)
    fun departNow() = coordinator.departNow()
    fun selectDeparture(value: Long) = coordinator.selectDeparture(value)
    fun continueTrafficFree() = coordinator.continueTrafficFree()
    fun refreshCredentials() = coordinator.refreshCredentials()
    fun suggestStopOrder() = coordinator.suggestStopOrder()
    fun acceptStopOrder() = coordinator.acceptStopOrder()
    fun dismissStopOrder() = coordinator.dismissStopOrder()
    fun calculate() = coordinator.calculate()
    fun startFromCurrentLocation(value: WeatherCoordinate) = coordinator.startFromCurrentLocation(value)
    fun saveSettings(value: RideWeatherSettings) = coordinator.saveSettings(value).also { saved -> if (saved) manager.preferencesChanged() }
    fun clearError() = coordinator.clearError()
    fun consumeNavigationStarted() = coordinator.consumeNavigationStarted()
    fun stopWeather() = manager.stop()
    fun refreshWeather() = manager.refresh()
    fun testVoice() = manager.testVoice()
}
