package com.atay.iz.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.atay.iz.IzApplication
import com.atay.iz.data.Transport
import com.atay.iz.navigation.prepareRouteStartStops
import com.atay.iz.weather.ConfiguredRoutePlanner
import com.atay.iz.weather.LocationForecast
import com.atay.iz.weather.OpenMeteoWeatherProvider
import com.atay.iz.weather.PlannedRoute
import com.atay.iz.weather.RideWeatherSettings
import com.atay.iz.weather.RoutePlanner
import com.atay.iz.weather.RouteStop
import com.atay.iz.weather.SavedWeatherPlan
import com.atay.iz.weather.ValhallaRoutePlanner
import com.atay.iz.weather.WeatherAssessment
import com.atay.iz.weather.WeatherCoordinate
import com.atay.iz.weather.WeatherEngine
import com.atay.iz.weather.WeatherPlanStore
import com.atay.iz.weather.WeatherProvider
import com.atay.iz.weather.WeatherSettingsStore
import com.atay.iz.weather.WeatherThresholds
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
) {
    val selectedAssessment: WeatherAssessment?
        get() = comparisons.firstOrNull { it.departureAt == selectedDepartureAt }
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
    }

    fun selectDeparture(value: Long) {
        if (mutableState.value.starting) return
        if (mutableState.value.comparisons.any { it.departureAt == value }) {
            mutableState.value = mutableState.value.copy(selectedDepartureAt = value)
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
                services.savePlan(SavedWeatherPlan(snapshot.stops, departureAt, snapshot.transport))
                val recommendation = recommendedAssessment(result.comparisons)
                mutableState.value = mutableState.value.copy(
                    route = result.route,
                    forecasts = result.forecasts,
                    comparisons = result.comparisons,
                    selectedDepartureAt = recommendation?.departureAt ?: result.comparisons.firstOrNull()?.departureAt,
                    busy = false,
                    error = if (result.comparisons.none { it.complete }) {
                        "Rotanın bazı bölümlerinde hava verisi eksik. Yolculuğu başlatabilirsin."
                    } else null,
                )
            } catch (cancelled: CancellationException) {
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
        val token = ++generation
        mutableState.value = snapshot.copy(starting = true, busy = false, error = null, activeJourneyId = null)
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
        check(route.transport == transport) { "Rota seçili yolculuk türüyle eşleşmedi." }
        onRoute(route)
        val coordinates = WeatherEngine.sampleVertices(route).map { it.coordinate }
        val until = departureAt + (route.durationSeconds * 1_000.0).toLong() + 4L * 60L * 60L * 1_000L
        val forecasts = services.weatherProvider(settings).hourly(
            coordinates,
            (departureAt - 60L * 60L * 1_000L).coerceAtLeast(0L),
            until,
        )
        val comparisons = services.compare(route, departureAt, forecasts, settings.thresholds)
        check(comparisons.size == 7) { "Yedi kalkış seçeneği hesaplanamadı." }
        return LoadedWeatherPlan(route, forecasts, comparisons)
    }

    private data class LoadedWeatherPlan(
        val route: PlannedRoute,
        val forecasts: List<LocationForecast>,
        val comparisons: List<WeatherAssessment>,
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
            routePlanner = { settings -> ValhallaRoutePlanner(application, settings.routeEndpoint) },
            weatherProvider = { settings -> OpenMeteoWeatherProvider(application, settings.weatherEndpoint) },
            readSettings = settingsStore::read,
            saveSettings = settingsStore::save,
            savePlan = planStore::save,
            activate = manager::activateGuidance,
            startRoutePlanner = { ConfiguredRoutePlanner(application) },
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
    fun calculate() = coordinator.calculate()
    fun startFromCurrentLocation(value: WeatherCoordinate) = coordinator.startFromCurrentLocation(value)
    fun saveSettings(value: RideWeatherSettings) = coordinator.saveSettings(value).also { saved -> if (saved) manager.preferencesChanged() }
    fun clearError() = coordinator.clearError()
    fun consumeNavigationStarted() = coordinator.consumeNavigationStarted()
    fun stopWeather() = manager.stop()
    fun refreshWeather() = manager.refresh()
    fun testVoice() = manager.testVoice()
}
