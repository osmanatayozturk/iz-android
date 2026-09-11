package com.atay.iz.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.*
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.*
import androidx.car.app.navigation.model.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.atay.iz.IzApplication
import com.atay.iz.data.*
import com.atay.iz.integration.OsmPlaces
import com.atay.iz.navigation.NavigationState
import com.atay.iz.weather.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import java.util.Locale

internal abstract class CarScreen(context: CarContext) : Screen(context) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected val app get() = carContext.applicationContext as IzApplication
    protected val navigation get() = app.navigation
    init { lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
    }) }
    fun safeInvalidate() = display { invalidate() }
    protected fun display(block: () -> Unit) {
        if (!safelyUpdateCarDisplay(block)) {
            com.atay.iz.tracking.LocationDiagnostics(java.io.File(carContext.noBackupFilesDir, "diagnostics"))
                .record(com.atay.iz.tracking.LocationDiagnostics.Event.DISPLAY_FAILED, null, null)
        }
    }
    protected fun observeWeather() {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.weatherManager.state.collect { safeInvalidate() }
            }
        }
    }
    protected fun action(title: String, click: () -> Unit) = Action.Builder().setTitle(title).setOnClickListener { display(click) }.build()
    protected fun row(title: String, text: String = "", click: (() -> Unit)? = null): Row = Row.Builder()
        .setTitle(title.take(150)).apply {
            if (text.isNotBlank()) addText(text.take(250))
            if (click != null) setOnClickListener { display(click) }
        }.build()
    protected fun listLimit() = carContext.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST).coerceIn(1, 100)
}

internal class CarHomeScreen(context: CarContext, private val surface: CarMapSurface) : CarScreen(context) {
    private var state = navigation.state.value
    private var points = emptyList<TrackPoint>()
    private var error: String? = null
    private var planning = false
    private var starting = false
    private val busy get() = planning || starting
    private val targetRequests = LatestCarRequest(scope)
    private var idleLocationJob: Job? = null
    var selectedMode = Transport.CAR
    val mode get() = effectiveCarMode(state, selectedMode)
    fun statistics(): JourneyStats? = state.journey?.let { JourneyStatistics.calculate(it, points) }
    fun refresh(value: NavigationState) { state = value; surface.update(state, points); safeInvalidate() }
    fun trail(value: List<TrackPoint>) { points = value; surface.update(state, points); safeInvalidate() }
    fun showError(value: String) { error = value; safeInvalidate() }
    private fun showPreview(route: PlannedRoute) { screenManager.push(CarPreviewScreen(carContext, this, surface, route)) }
    fun preview(route: PlannedRoute) { cancelTargetPlanning(); showPreview(route) }
    private fun cancelTargetPlanning() { targetRequests.cancel(); planning = false }

    fun requestIdleLocation() {
        if (state.sessionId != null || state.recording || (state.fix != null && !state.gpsStale) || idleLocationJob?.isActive == true ||
            ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        idleLocationJob = scope.launch {
            try { navigation.currentLocation(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Konum alınamadı. Telefonda konum ayarlarını kontrol edin." }
            finally { safeInvalidate() }
        }
    }

    fun openTarget(target: NavigationTarget) {
        cancelTargetPlanning()
        if (target.coordinate == null) {
            screenManager.push(CarSearchScreen(carContext, this, target.query))
        } else plan(RouteStop(target.query.ifBlank { "Hedef" }.take(300), target.coordinate))
    }

    fun plan(destination: RouteStop) {
        planStops(listOf(destination))
    }

    fun planStops(destinations: List<RouteStop>) {
        planning = true
        error = null
        safeInvalidate()
        targetRequests.submit(work = {
                val origin = navigation.currentLocation().coordinate
                navigation.previewRoute(listOf(RouteStop("Konumum", origin)) + destinations.take(4), mode)
            }, publish = ::showPreview,
            failed = { error = it.message ?: "Rota hazırlanamadı. Telefonda bağlantı ve konum ayarlarını kontrol edin." },
            finished = { planning = false; safeInvalidate() })
    }

    private fun start() {
        if (busy) return
        starting = true
        scope.launch {
            try { navigation.startFreeDrive(mode); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Kayıt başlatılamadı. Telefonda İz'i açın." }
            finally { starting = false; safeInvalidate() }
        }
    }

    private fun mapActions(): ActionStrip = ActionStrip.Builder().addAction(Action.PAN)
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            android.R.drawable.ic_menu_mylocation)).build()).setOnClickListener { display { surface.recenter() } }.build())
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            com.atay.iz.R.drawable.car_zoom_in)).build()).setOnClickListener { display { surface.zoom(1.0) } }.build())
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            com.atay.iz.R.drawable.car_zoom_out)).build()).setOnClickListener { display { surface.zoom(-1.0) } }.build()).build()

    override fun onGetTemplate(): Template {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return MessageTemplate.Builder("Park edin ve telefonda İz'i açarak hassas konum iznini verin. Ardından buraya dönün.")
                .setTitle("Telefonda kurulumu tamamlayın").setHeaderAction(Action.APP_ICON)
                .addAction(action("Yenile") { requestIdleLocation(); safeInvalidate() }).build()
        }
        val controls = mapActions()
        if (state.guidance && state.route != null) {
            val progress = state.progress
            val step = progress?.let { state.route!!.maneuvers.getOrNull(it.maneuverIndex) }
            val builder = NavigationTemplate.Builder().setMapActionStrip(controls)
                .setActionStrip(ActionStrip.Builder()
                    .addAction(action("Menü") { screenManager.push(CarMenuScreen(carContext, this)) })
                    .addAction(action(if (state.muted) "Sesi aç" else "Sessiz") { navigation.setMuted(!state.muted) })
                    .addAction(action("Rotayı bitir") { navigation.stopGuidance() }).build())
                .setPanModeListener { if (!it) display { surface.recenter() } }
            if (state.gpsStale || state.loading || progress == null || progress.offRoute || step == null) {
                builder.setNavigationInfo(MessageInfo.Builder(when {
                    state.gpsStale -> "GPS bekleniyor — yönlendirme duraklatıldı"
                    progress?.offRoute == true -> state.message ?: "Rota dışında — yeni rota bekleniyor"
                    state.loading -> state.message ?: "Rota hazırlanıyor"
                    else -> state.message ?: "Rotayı takip edin"
                }).build())
            } else {
                builder.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(carStep(step),
                    carDistance(progress.nextManeuverDistanceMeters)).build())
                builder.setDestinationTravelEstimate(carEstimate(progress.remainingMeters, progress.remainingSeconds))
            }
            return builder.build()
        }
        val stats = statistics()
        val temporary = state.journey?.status == JourneyStatus.TEMPORARY
        val currentSpeed = state.fix?.speedMps?.takeIf { !state.gpsStale && it.isFinite() && it in 0f..100f }
            ?.let { "${(it * 3.6).toInt()} km/sa" } ?: "— km/sa"
        val title = when { state.simulation -> "Simülasyon • Günlüğe yazılmaz"; temporary -> "Geçici sürüş • ${modeLabel(mode)}"; state.recording -> "Serbest sürüş • ${modeLabel(mode)}"; else -> "İz • ${modeLabel(mode)}" }
        val pane = Pane.Builder().addRow(row(if (state.recording) "$currentSpeed • ${km(stats?.distanceMeters ?: 0.0)} km • ${(stats?.elapsedMillis ?: 0) / 60_000} dk" else "Hedef seçmeden sürüşe başlayın",
            error ?: state.message ?: if (temporary) "Geçici kayıt: 500 m / 15 dk kuralı. Kaydetmek için sürüşü başlatın." else if (state.arrived) "Hedefe ulaştınız. Sürüş kaydı devam ediyor." else if (state.gpsStale) "GPS bekleniyor" else "Yalnızca bu sürüşün izi gösterilir"))
            .addRow(row("Hava durumu", carWeatherPresentation(app.weatherManager.state.value).summary))
            .addAction(action(if (busy) "Bekleyin…" else if (temporary) "Sürüşü kaydet" else if (state.recording) "Sürüşü bitir" else "Sürüşe başla") {
                if (state.recording && !temporary) state.journey?.id?.let { id ->
                    screenManager.push(CarFinishScreen(carContext, id))
                } else start()
            }).addAction(action("Menü") { screenManager.push(CarMenuScreen(carContext, this)) }).build()
        return MapWithContentTemplate.Builder().setContentTemplate(PaneTemplate.Builder(pane)
            .setTitle(title).setHeaderAction(Action.APP_ICON).build())
            .setMapController(MapController.Builder().setMapActionStrip(controls)
                .setPanModeListener { if (!it) display { surface.recenter() } }.build()).build()
    }
}

internal class CarMenuScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    init { observeWeather() }
    override fun onGetTemplate(): Template {
        val rows = mutableListOf(
            row("Hedef ara", "Ad veya adres; arama yalnızca gönderince yapılır") { screenManager.push(CarSearchScreen(carContext, home)) },
            row("Kayıtlı ve son yerler") { screenManager.push(CarPlacesScreen(carContext, home)) },
            row("Sürüş türü", modeLabel(home.mode)) { screenManager.push(CarModeScreen(carContext, home)) },
        )
        rows.add(row("Hazır rotalar ve telefon planı") { screenManager.push(CarRoutesScreen(carContext, home)) })
        if (navigation.state.value.recording) rows.add(row("Sürüş istatistikleri") {
            screenManager.push(CarStatisticsScreen(carContext, home))
        })
        rows.add(row("Hava durumu", carWeatherPresentation(app.weatherManager.state.value).summary) {
            screenManager.push(CarWeatherScreen(carContext))
        })
        return ListTemplate.Builder().setTitle("Sürüş menüsü").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply { rows.take(listLimit()).forEach(::addItem) }.build()).build()
    }
}

internal class CarRoutesScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun onGetTemplate(): Template = ListTemplate.Builder().setTitle("Rotalar").setHeaderAction(Action.BACK)
        .setSingleList(ItemList.Builder().setNoItemsMessage("Telefonda plan oluşturun veya hedef arayın").apply {
            (navigation.state.value.route ?: navigation.lastRoute)?.let { route ->
                addItem(row("Yüklenmiş rota", route.stops.last().label) { home.preview(route) })
            }
            WeatherPlanStore(carContext).read()?.let { plan ->
                addItem(row("Telefondaki plan", "${plan.stops.last().label} • ${modeLabel(home.mode)}") {
                    home.planStops(plan.stops.drop(1))
                })
            }
        }.build()).build()
}

internal class CarModeScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun onGetTemplate(): Template {
        if (!canSelectCarMode(navigation.state.value)) return MessageTemplate.Builder(
            "Aktif sürüşün türü: ${modeLabel(home.mode)}. Türü değiştirmek için önce sürüşü bitirin.")
            .setTitle("Aktif sürüş kullanılıyor").setHeaderAction(Action.BACK).build()
        return ListTemplate.Builder().setTitle("Yeni sürüş türü").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply {
                listOf(Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER).forEach { mode ->
                    addItem(row(modeLabel(mode)) { home.selectedMode = mode; home.safeInvalidate(); screenManager.pop() })
                }
            }.build()).build()
    }
}

internal class CarSearchScreen(context: CarContext, private val home: CarHomeScreen, initial: String = "") : CarScreen(context) {
    private var query = initial
    private var searching = false
    private var results = emptyList<SelectedOsmPlace>()
    private var message = "Ad veya adres yazıp aramayı gönderin"
    private var job: Job? = null
    override fun onGetTemplate(): Template = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) { query = searchText }
        override fun onSearchSubmitted(searchText: String) {
            query = searchText
            if (query.trim().length !in 2..200) { message = "2–200 karakter kullanın"; safeInvalidate(); return }
            job?.cancel()
            searching = true
            safeInvalidate()
            job = scope.launch {
                try { results = OsmPlaces(carContext).search(query); message = "Sonuç bulunamadı" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { results = emptyList(); message = failure.message ?: "Arama yapılamadı" }
                finally { searching = false; safeInvalidate() }
            }
        }
    }).setHeaderAction(Action.BACK).setInitialSearchText(query).setSearchHint("Yer veya adres")
        .setLoading(searching).apply {
            if (!searching) setItemList(ItemList.Builder().setNoItemsMessage(message).apply {
                results.take(listLimit()).forEach { place -> addItem(row(place.name) {
                    home.plan(RouteStop(place.name.take(300), WeatherCoordinate(place.latitude, place.longitude)))
                }) }
            }.build())
        }.build()
}

internal class CarPlacesScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    private var loading = true
    private var destinations = emptyList<RouteStop>()
    private var message = "Telefonda koordinatlı bir yer kaydedin veya hedef arayın"
    init { scope.launch {
        try {
            val repository = DiaryRepository(carContext)
            val places = repository.places.first().filter { it.latitude != null && it.longitude != null }
            val recentIds = repository.visits.first().sortedByDescending { it.visitedAt }.map { it.placeId }.distinct()
            val recent = recentIds.mapNotNull { id -> places.find { it.id == id } }
            destinations = (recent + places).distinctBy { it.id }.map {
                RouteStop(it.name.take(300).ifBlank { "Kayıtlı yer" }, WeatherCoordinate(it.latitude!!, it.longitude!!))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Yerler okunamadı; telefonda İz'i açın" }
        finally { loading = false; safeInvalidate() }
    } }
    override fun onGetTemplate(): Template = ListTemplate.Builder().setTitle("Kayıtlı ve son yerler")
        .setHeaderAction(Action.BACK).setLoading(loading).apply {
            if (!loading) setSingleList(ItemList.Builder().setNoItemsMessage(message).apply {
                destinations.take(listLimit()).forEach { destination -> addItem(row(destination.label) { home.plan(destination) }) }
            }.build())
        }.build()
}

internal class CarPreviewScreen(context: CarContext, private val home: CarHomeScreen,
    private val surface: CarMapSurface, private val route: PlannedRoute) : CarScreen(context) {
    private var busy = false
    private var error: String? = null
    init {
        display { surface.preview(route) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { display { surface.preview(null) } }
        })
    }
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder().addRow(row(route.stops.last().label,
            "${km(route.distanceMeters)} km • ${(route.durationSeconds / 60).toInt()} dk • ${modeLabel(route.transport)}"))
            .addRow(row(error ?: "Trafik bilgisi içermez", "Yüklenmiş rota çevrimdışı izlenebilir; harita yalnızca önbellekteki alanlarda görünür."))
            .addAction(action(if (busy) "Bekleyin…" else "Yönlendirmeyi başlat") {
                if (!busy) {
                    busy = true; safeInvalidate()
                    scope.launch {
                        try { navigation.startGuidance(route); surface.preview(null); screenManager.popToRoot() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "Başlatılamadı; telefondaki izinleri kontrol edin" }
                        finally { busy = false; safeInvalidate() }
                    }
                }
            }).build()
        return MapWithContentTemplate.Builder().setContentTemplate(PaneTemplate.Builder(pane)
            .setTitle("Rota önizleme").setHeaderAction(Action.BACK).build()).build()
    }
}

internal class CarFinishScreen(context: CarContext, private val journeyId: String) : CarScreen(context) {
    private var error: String? = null
    private var busy = false
    override fun onGetTemplate(): Template = MessageTemplate.Builder(error ?: "Sürüş kaydı ve varsa yönlendirme sona erecek.")
        .setTitle("Sürüşü bitir").setHeaderAction(Action.BACK)
        .addAction(action(if (busy) "Bekleyin…" else "Bitir") {
            if (!busy) { busy = true; safeInvalidate(); scope.launch {
                try { navigation.finishJourney(journeyId); screenManager.popToRoot() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Sürüş bitirilemedi" }
                finally { busy = false; safeInvalidate() }
            } }
        }).addAction(action("Devam et") { screenManager.pop() }).build()
}

internal class CarWeatherScreen(
    context: CarContext,
    private val refreshWeather: () -> Unit = { (context.applicationContext as IzApplication).weatherManager.refresh(); Unit },
) : CarScreen(context) {
    init { observeWeather() }
    override fun onGetTemplate(): Template {
        val weather = carWeatherPresentation(app.weatherManager.state.value)
        return MessageTemplate.Builder(weather.detail).setTitle(weather.title)
            .setHeaderAction(Action.BACK).addAction(action("Yenile") {
                refreshWeather()
                safeInvalidate()
            }).build()
    }
}

internal class CarStatisticsScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun onGetTemplate(): Template {
        val stats = home.statistics()
        fun speed(value: Double?) = value?.let { String.format(Locale.forLanguageTag("tr"), "%.1f km/sa", it) } ?: "—"
        return PaneTemplate.Builder(Pane.Builder()
            .addRow(row("Ortalama hız", speed(stats?.averageSpeedKmh)))
            .addRow(row("En yüksek örneklenmiş hız", speed(stats?.maxSpeedKmh)))
            .addRow(row("Gözlenen / GPS boşluğu", "${(stats?.observedMillis ?: 0) / 60_000} / ${(stats?.unobservedMillis ?: 0) / 60_000} dk"))
            .build()).setTitle("Bu sürüşün istatistikleri").setHeaderAction(Action.BACK).build()
    }
}

internal fun modeLabel(mode: Transport): String = when (mode) {
    Transport.CAR -> "Araba"
    Transport.MOTORCYCLE -> "Motosiklet"
    Transport.PASSENGER -> "Yolcu"
    Transport.BICYCLE -> "Bisiklet"
    Transport.WALK -> "Yürüyüş"
    Transport.RUN -> "Koşu"
    Transport.UNKNOWN -> "Belirsiz"
}
internal fun km(meters: Double): String = String.format(Locale.forLanguageTag("tr"), "%.1f", meters / 1_000)
