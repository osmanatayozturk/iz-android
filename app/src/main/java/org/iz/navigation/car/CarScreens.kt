package org.iz.navigation.car

import android.Manifest
import android.content.Intent
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
import org.iz.navigation.IzApplication
import org.iz.navigation.MainActivity
import org.iz.navigation.navigation.NavigationStopSnapshot
import org.iz.navigation.data.*
import org.iz.navigation.integration.OsmPlaces
import org.iz.navigation.navigation.NavigationState
import org.iz.navigation.navigation.DriveCameraMode
import org.iz.navigation.navigation.SpeechStatus
import org.iz.navigation.navigation.NavigationSpeech
import org.iz.navigation.power.PostJourneyAnalysis
import org.iz.navigation.weather.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/** Each visible consumer owns its subscription; stopped screens do not receive queued notices. */
internal fun observeCarVoiceNotice(scope: CoroutineScope, lifecycle: Lifecycle,
    speech: StateFlow<SpeechStatus>, navigation: StateFlow<NavigationState>, onNotice: (String) -> Unit): Job = scope.launch {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        combine(speech, navigation.map { it.muted }.distinctUntilChanged()) { status, muted ->
            carVoiceNotice(status, muted)
        }.distinctUntilChanged().collect { onNotice(it) }
    }
}

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
            org.iz.navigation.tracking.LocationDiagnostics(java.io.File(carContext.noBackupFilesDir, "diagnostics"))
                .record(org.iz.navigation.tracking.LocationDiagnostics.Event.DISPLAY_FAILED, null, null)
        }
    }
    protected fun observeWeather() {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.weatherManager.state.collect { safeInvalidate() }
            }
        }
    }
    protected val currentVoiceNotice get() = carVoiceNotice(NavigationSpeech.get(app).status.value, navigation.state.value.muted)
    protected fun observeVoice() {
        observeCarVoiceNotice(scope, lifecycle, NavigationSpeech.get(app).status, navigation.state) { safeInvalidate() }
    }
    protected fun preparedNotice(route: PlannedRoute? = null): CarPreparedPresentation =
        carPreparedPresentation(app.preparedRoutes.state.value, route, app.preparedRoutes.mapReady())
    protected fun observePrepared(route: PlannedRoute? = null) {
        observeCarPreparedNotice(scope, lifecycle, app.preparedRoutes.state, route,
            { app.preparedRoutes.mapReady() }) { safeInvalidate() }
    }
    protected fun action(title: String, click: () -> Unit) = Action.Builder().setTitle(title).setOnClickListener { display(click) }.build()
    // The host checks parking before handing an action to the phone.
    // https://developer.android.com/reference/androidx/car/app/model/ParkedOnlyOnClickListener
    protected fun phoneListener(page: String, planId: String? = null) = ParkedOnlyOnClickListener.create {
        val intent = Intent(carContext, MainActivity::class.java)
            .setAction("org.iz.navigation.action.$page")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (planId != null) intent.putExtra("routePlanId", planId)
        display { carContext.startActivity(intent) }
    }
    protected fun phoneAction(title: String, page: String) =
        Action.Builder().setTitle(title).setOnClickListener(phoneListener(page)).build()
    protected fun phoneRow(title: String, detail: String, page: String, planId: String? = null) =
        Row.Builder().setTitle(title.take(150)).addText(detail.take(250))
            .setOnClickListener(phoneListener(page, planId)).build()
    protected fun mapReturn() = action("Haritaya dön") { screenManager.popToRoot() }
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
    private var error: String? = null
    private var starting = false
    private var idleLocationJob: Job? = null
    private var speechStatus = SpeechStatus()
    var selectedMode = Transport.CAR
    val mode get() = effectiveCarMode(state, selectedMode)
    val measurementSessionActive get() = state.recording || state.guidance || state.simulation
    fun refresh(value: NavigationState) {
        state = value
        surface.update(state, carVoiceWarning(state, speechStatus))
        safeInvalidate()
    }
    fun refreshSpeech(value: SpeechStatus) {
        if (speechStatus != value) { speechStatus = value; refresh(state) }
    }
    val voiceNotice get() = carVoiceNotice(speechStatus, state.muted)
    val cameraMode get() = surface.cameraMode
    val overviewAvailable get() = surface.overviewAvailable
    fun setCameraMode(value: DriveCameraMode) { surface.cameraMode(value) }
    fun showOverview() { surface.overview() }
    fun recenter() = surface.recenter()
    fun preview(route: PlannedRoute) { screenManager.push(CarPreviewScreen(carContext, this, surface, route)) }

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
        if (target.coordinate == null) {
            screenManager.push(CarSearchScreen(carContext, this, target.query))
        } else plan(RouteStop(target.query.ifBlank { "Hedef" }.take(300), target.coordinate))
    }

    fun plan(destination: RouteStop) {
        screenManager.push(CarPreviewScreen(carContext, this, surface, destination = destination))
    }

    private fun start() {
        if (starting) return
        starting = true
        safeInvalidate()
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
            org.iz.navigation.R.drawable.car_zoom_in)).build()).setOnClickListener { display { surface.zoom(1.0) } }.build())
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            org.iz.navigation.R.drawable.car_zoom_out)).build()).setOnClickListener { display { surface.zoom(-1.0) } }.build()).build()

    override fun onGetTemplate(): Template {
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return MessageTemplate.Builder("Park edin ve telefonda İz'i açarak hassas konum iznini verin. Ardından buraya dönün.")
                .setTitle("Telefonda kurulumu tamamlayın").setHeaderAction(Action.APP_ICON)
                .addAction(phoneAction("Telefonda aç", "OPEN_LOCATION_SETUP"))
                .addAction(action("Yenile") { requestIdleLocation(); safeInvalidate() }).build()
        }
        val controls = mapActions()
        val notice = carNavigationNotice(state)
        if (state.guidance && state.route != null) {
            val progress = state.progress
            val step = progress?.let { state.route!!.maneuvers.getOrNull(it.maneuverIndex) }
            val builder = NavigationTemplate.Builder().setMapActionStrip(controls)
                .setBackgroundColor(surface.guidanceBackground)
                .setActionStrip(ActionStrip.Builder()
                    .addAction(action("Menü") { screenManager.push(CarMenuScreen(carContext, this)) })
                    .addAction(action(if (state.muted) "Sesi aç" else "Sessiz") { navigation.setMuted(!state.muted) })
                    .apply {
                        NavigationStopSnapshot.from(state)?.let { expected ->
                            if (expected.recording && !expected.simulation) addAction(action("Sürüşü bitir") {
                                screenManager.push(CarFinishScreen(carContext, expected))
                            })
                            addAction(action("Tarifi bitir") {
                                screenManager.push(CarFinishScreen(carContext, expected, guidanceOnly = true))
                            })
                        }
                    }.build())
                .setPanModeListener { display { surface.panMode(it) } }
            if (notice != null) {
                val title = (if (state.simulation) "Simülasyon • telefonda · " else "") + notice
                builder.setNavigationInfo(MessageInfo.Builder(title).setText(voiceNotice).build())
            } else {
                val currentProgress = checkNotNull(progress)
                if (!surface.showsTurn) {
                    builder.setNavigationInfo(RoutingInfo.Builder().setCurrentStep(carStep(checkNotNull(step), state.simulation,
                        carVoiceWarning(state, speechStatus)),
                        carDistance(currentProgress.nextManeuverDistanceMeters)).build())
                }
                builder.setDestinationTravelEstimate(carDestinationEstimate(state))
            }
            return builder.build()
        }
        val temporary = state.journey?.status == JourneyStatus.TEMPORARY
        val menu = action("Menü") { screenManager.push(CarMenuScreen(carContext, this)) }
        val title = when {
            state.simulation -> "Simülasyon · Günlüğe yazılmaz"
            temporary -> "Geçici sürüş · ${modeLabel(mode)}"
            state.recording -> "Kayıt sürüyor · ${modeLabel(mode)}"
            else -> "Nereye?"
        }
        val content = if (state.recording || temporary) {
            val speed = state.fix?.speedMps?.takeIf { !state.gpsStale && it.isFinite() && it in 0f..100f }
            val expected = NavigationStopSnapshot.from(state)
            val journeyId = state.journey?.id
            val pane = Pane.Builder().addRow(row(
                if (temporary) "Geçici yolculuk" else "Hedefsiz kayıt",
                error ?: notice ?: state.message ?: speed?.let { "${(it * 3.6).toInt()} km/sa" }.orEmpty()))
                .addAction(action("Hedef ara") { screenManager.push(CarSearchScreen(carContext, this)) })
                .apply {
                    if (temporary) addAction(action(if (starting) "Kaydediliyor…" else "Sürüşü kaydet") { start() })
                    else if (expected != null || journeyId != null) addAction(action("Kaydı bitir") {
                        screenManager.push(if (expected != null) CarFinishScreen(carContext, expected)
                            else CarFinishScreen(carContext, requireNotNull(journeyId)))
                    })
                }.build()
            PaneTemplate.Builder(pane).setTitle(title).setHeaderAction(Action.APP_ICON).build()
        } else {
            val rows = listOf(
                row("Hedef ara", "Ad veya adres") { screenManager.push(CarSearchScreen(carContext, this)) },
                row("Ev / İş", "Kayıtlı ve son yerler") { screenManager.push(CarPlacesScreen(carContext, this)) },
                row(if (starting) "Kayıt başlatılıyor…" else "Hedefsiz kayıt", error ?: notice ?: modeLabel(mode)) { start() },
            )
            ListTemplate.Builder().setTitle(title).setHeaderAction(Action.APP_ICON)
                .setSingleList(ItemList.Builder().apply { rows.take(listLimit()).forEach(::addItem) }.build()).build()
        }
        return MapWithContentTemplate.Builder().setContentTemplate(content)
            .setActionStrip(ActionStrip.Builder().addAction(menu).build())
            .setMapController(MapController.Builder().setMapActionStrip(controls)
                .setPanModeListener { display { surface.panMode(it) } }.build()).build()
    }
}

internal class CarMenuScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun onGetTemplate(): Template {
        val rows = listOf(
            row("Hedef ve yerler", "Ev, İş ve son yerler") { screenManager.push(CarPlacesScreen(carContext, home)) },
            row("Sürüş ve harita", modeLabel(home.mode)) { screenManager.push(CarDrivingOptionsScreen(carContext, home)) },
            row("Hazır rotalar") { screenManager.push(CarRoutesScreen(carContext, home)) },
            row("Hava durumu") { screenManager.push(CarWeatherScreen(carContext)) },
            row("İstatistikler") { screenManager.push(CarStatisticsScreen(carContext, home)) },
        )
        return ListTemplate.Builder().setTitle("Sürüş menüsü").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply { rows.take(listLimit()).forEach(::addItem) }.build()).build()
    }
}

internal class CarDrivingOptionsScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    init { observeVoice() }
    override fun onGetTemplate(): Template = ListTemplate.Builder().setTitle("Sürüş ve harita").setHeaderAction(Action.BACK)
        .setSingleList(ItemList.Builder()
            .addItem(row("Sürüş türü", modeLabel(home.mode)) { screenManager.push(CarModeScreen(carContext, home)) })
            .addItem(row("Harita görünümü", if (home.cameraMode == DriveCameraMode.ADAPTIVE) "Uyarlamalı" else "Kuzey yukarı 2D") { screenManager.push(CarCameraScreen(carContext, home)) })
            .addItem(phoneRow("Ses ayarları", currentVoiceNotice, "OPEN_VOICE_SETTINGS")).build())
        .setActionStrip(ActionStrip.Builder().addAction(mapReturn()).build()).build()
}

internal class CarCameraScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun onGetTemplate(): Template = ListTemplate.Builder().setTitle("Araç kamerası").setHeaderAction(Action.BACK)
        .setActionStrip(ActionStrip.Builder().addAction(action("Konumum") {
            home.recenter(); screenManager.popToRoot()
        }).build())
        .setSingleList(ItemList.Builder()
            .addItem(row("Uyarlamalı görünüm", if (home.cameraMode == DriveCameraMode.ADAPTIVE) "Seçili · Yön ve hıza göre" else "Yön ve hıza göre") {
                home.setCameraMode(DriveCameraMode.ADAPTIVE); screenManager.popToRoot()
            })
            .addItem(row("Kuzey yukarı 2D", if (home.cameraMode == DriveCameraMode.SIMPLE_2D) "Seçili" else "Sabit yön") {
                home.setCameraMode(DriveCameraMode.SIMPLE_2D); screenManager.popToRoot()
            })
            .apply { if (home.overviewAvailable) addItem(row("Rota görünümü", "Tam rotayı gör; izlemeye dönmek için Konumum") {
                home.showOverview(); screenManager.popToRoot()
            }) }.build()).build()
}

internal class CarRoutesScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    private var saved = emptyList<SavedRoutePlan>()
    private var readError = false
    init {
        observePrepared()
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    TravelLibraryRepository(carContext).savedPlans.collect {
                        saved = it; readError = false; safeInvalidate()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { readError = true; safeInvalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val prepared = preparedNotice()
        val rows = buildList {
            (navigation.state.value.route ?: navigation.lastRoute)?.let { route ->
                route.stops.lastOrNull()?.let { target ->
                    add(row("Yüklü rota", target.label) { home.preview(route) })
                }
            }
            val draft = WeatherPlanStore(carContext).read()
            val detail = draft?.let { "${it.stops.size} nokta · ${it.stops.last().label}" } ?: "Park halinde planlayıcıyı aç"
            add(phoneRow("Telefondaki plan", detail, "OPEN_ROUTE_PLAN"))
            add(row("Harita hazırlığı", "${prepared.route} ${prepared.map}"))
            if (readError) add(row("Kayıtlı planlar okunamadı"))
            saved.take((listLimit() - size).coerceAtLeast(0)).forEach { plan ->
                add(phoneRow(plan.name, "${plan.stops.size} nokta · Telefonda devam et", "OPEN_ROUTE_PLAN", plan.id))
            }
        }
        return ListTemplate.Builder().setTitle("Hazır rotalar").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply { rows.take(listLimit()).forEach(::addItem) }.build()).build()
    }
}

internal class CarModeScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun onGetTemplate(): Template {
        if (!canSelectCarMode(navigation.state.value)) return MessageTemplate.Builder(
            "${modeLabel(home.mode)} seçili. Vasıtayı değiştirmek için sürüşü bitirin.")
            .setTitle("Aktif sürüş kullanılıyor").setHeaderAction(Action.BACK).build()
        return ListTemplate.Builder().setTitle("Vasıta seçimi").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply {
                listOf(Transport.CAR, Transport.MOTORCYCLE, Transport.PASSENGER).forEach { mode ->
                    addItem(row(modeLabel(mode), if (home.mode == mode) "Seçili" else "") {
                        if (canSelectCarMode(navigation.state.value)) home.selectedMode = mode
                        home.safeInvalidate(); screenManager.pop()
                    })
                }
            }.build()).build()
    }
}

internal class CarSearchScreen(context: CarContext, private val home: CarHomeScreen, initial: String = "") : CarScreen(context) {
    init { marker = "carsearch" }
    private var query = initial
    private var searching = false
    private var results = emptyList<SelectedOsmPlace>()
    private var message = ""
    private var submitted = false
    private val requests = LatestCarRequest(scope)
    override fun onGetTemplate(): Template = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) { query = searchText }
        override fun onSearchSubmitted(searchText: String) {
            val term = searchText.trim()
            query = searchText
            submitted = true
            results = emptyList()
            requests.cancel()
            if (term.length !in 2..200) {
                searching = false; message = "2–200 karakter girin"; safeInvalidate(); return
            }
            searching = true
            safeInvalidate()
            requests.submit(work = { OsmPlaces(carContext).search(term) },
                publish = { results = it; message = "Sonuç bulunamadı" },
                failed = { message = it.message ?: "Arama yapılamadı" },
                finished = { searching = false; safeInvalidate() })
        }
    }).setHeaderAction(Action.BACK).setInitialSearchText(query).setSearchHint("Ad veya adres")
        .setLoading(searching).apply {
            if (!searching) setItemList(ItemList.Builder().setNoItemsMessage(message).apply {
                if (!submitted) addItem(row("Son yerler", "Ev, İş ve kayıtlı yerler") {
                    if (screenManager.screenStack.any { it.marker == "carplaces" }) screenManager.popTo("carplaces")
                    else screenManager.push(CarPlacesScreen(carContext, home))
                })
                results.take(listLimit()).forEach { place -> addItem(row(place.name, carSearchSubtitle(place)) {
                    home.plan(RouteStop(place.name.take(300), WeatherCoordinate(place.latitude, place.longitude)))
                }) }
            }.build())
        }.build()
}

internal class CarPlacesScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    init { marker = "carplaces" }
    private var loading = true
    private var destinations = emptyList<Pair<RouteStop, Boolean>>()
    private var message = "Telefonda koordinatlı bir yer kaydedin veya hedef arayın"
    private val library = TravelLibraryRepository(context)
    private var shortcuts = emptyList<DestinationShortcut>()
    private var shortcutError: String? = null
    init { scope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            library.destinationShortcuts.collect { value -> shortcuts = value; safeInvalidate() }
        }
    } }
    init { scope.launch {
        try {
            val repository = DiaryRepository(carContext)
            val places = repository.places.first().filter { it.latitude != null && it.longitude != null }
            val recentIds = repository.visits.first().sortedByDescending { it.visitedAt }.map { it.placeId }.toCollection(linkedSetOf())
            val placesById = places.associateBy { it.id }
            val recent = recentIds.mapNotNull(placesById::get)
            destinations = (recent + places).distinctBy { it.id }.map {
                RouteStop(it.name.take(300).ifBlank { "Kayıtlı yer" }, WeatherCoordinate(it.latitude!!, it.longitude!!)) to (it.id in recentIds)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "Yerler okunamadı; telefonda İz'i açın" }
        finally { loading = false; safeInvalidate() }
    } }
    override fun onGetTemplate(): Template = ListTemplate.Builder().setTitle("Hedef ve yerler")
        .setActionStrip(ActionStrip.Builder().addAction(action("Hedef ara") {
            if (screenManager.screenStack.any { it.marker == "carsearch" }) screenManager.popTo("carsearch")
            else screenManager.push(CarSearchScreen(carContext, home))
        }).build())
        .setHeaderAction(Action.BACK).setLoading(loading).apply {
            if (!loading) setSingleList(ItemList.Builder().setNoItemsMessage(message).apply {
                val rows = mutableListOf<Row>()
                shortcutError?.let { rows.add(row("Hedef kullanılamıyor", it)) }
                DestinationShortcutKind.entries.forEach { kind ->
                    val target = shortcuts.firstOrNull { it.kind == kind }?.let(::carShortcutStop)
                    val label = carShortcutLabel(kind)
                    rows.add(if (target == null) phoneRow(label, "Telefonda ayarla", "OPEN_PLACES")
                        else row(label, target.label) { openShortcut(kind) })
                }
                navigation.lastRoute?.stops?.lastOrNull()?.let { last ->
                    rows.add(row(last.label, "Son hedef") { home.plan(last) })
                }
                if (destinations.isEmpty()) rows.add(row("Kayıtlı yerler", message))
                destinations.take((listLimit() - rows.size).coerceAtLeast(0)).forEach { (destination, recent) ->
                    rows.add(row(destination.label, if (recent) "Son ziyaret" else "Kayıtlı yer") { home.plan(destination) })
                }
                rows.take(listLimit()).forEach(::addItem)
            }.build())
        }.build()

    private fun openShortcut(kind: DestinationShortcutKind) { scope.launch {
        try {
            val target = library.destinationShortcuts.first().firstOrNull { it.kind == kind }?.let(::carShortcutStop)
                ?: error("Hedef değişmiş veya kaldırılmış. Telefonda yeniden seçin.")
            shortcutError = null
            home.plan(target)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { shortcutError = failure.message ?: "Hedef okunamadı"; safeInvalidate() }
    } }
}

internal class CarPreviewScreen(context: CarContext, private val home: CarHomeScreen,
    private val surface: CarMapSurface, initialRoute: PlannedRoute? = null,
    private val destination: RouteStop? = null) : CarScreen(context) {
    private var route = initialRoute
    private var starting = false
    private var planning = false
    private var error: String? = null

    init {
        observePrepared(initialRoute)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                display { surface.preview(route) }
                if (route == null && !planning && error == null) prepare()
            }
            override fun onStop(owner: LifecycleOwner) { display { surface.preview(null) } }
        })
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    delay(30_000)
                    if (route?.traffic != null) safeInvalidate()
                }
            }
        }
    }

    private fun prepare() {
        val target = destination ?: return
        if (planning) return
        val mode = home.mode
        planning = true
        error = null
        safeInvalidate()
        scope.launch {
            try {
                val origin = RouteStop("Konumum", navigation.currentLocation().coordinate)
                val result = navigation.previewRoute(listOf(origin, target), mode)
                ensureActive()
                route = result
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) display { surface.preview(result) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Rota hazırlanamadı." }
            finally { planning = false; safeInvalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val selected = route
        val pane = Pane.Builder()
        if (selected == null) {
            pane.addRow(row(destination?.label ?: "Hedef", error ?: "Rota hesaplanıyor…"))
            if (!planning && error != null) pane.addAction(action("Yeniden dene", ::prepare))
        } else {
            val information = carRoutePreviewPresentation(selected, System.currentTimeMillis())
            val prepared = preparedNotice(selected)
            pane.addRow(row(selected.stops.last().label,
                "${(selected.durationSeconds / 60).toInt()} dk · ${km(selected.distanceMeters)} km · ${modeLabel(selected.transport)}"))
            if (error != null) pane.addRow(row("Başlatılamadı", error.orEmpty()))
            pane.addRow(row("Kaynak ve trafik", information.source))
            information.warnings?.let { pane.addRow(row("Rota uyarıları", it)) }
            pane.addRow(row("Hazırlık", "${prepared.route} ${prepared.map}"))
            pane.addAction(Action.Builder().setTitle(if (starting) "Başlatılıyor…" else "Başlat")
                .setFlags(Action.FLAG_PRIMARY).setEnabled(!starting).setOnClickListener { display { start(selected) } }.build())
        }
        return MapWithContentTemplate.Builder().setContentTemplate(PaneTemplate.Builder(pane.build())
            .setTitle("Rota önizlemesi").setHeaderAction(Action.BACK).build()).build()
    }

    private fun start(selected: PlannedRoute) {
        if (starting) return
        starting = true
        error = null
        safeInvalidate()
        scope.launch {
            try { navigation.startGuidance(selected); screenManager.popToRoot() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Telefonda izinleri kontrol edin." }
            finally { starting = false; safeInvalidate() }
        }
    }
}

internal class CarFinishScreen private constructor(context: CarContext, private val expected: NavigationStopSnapshot?,
    private val journeyId: String?, private val guidanceOnly: Boolean) : CarScreen(context) {
    constructor(context: CarContext, journeyId: String) : this(context, null, journeyId, false)
    constructor(context: CarContext, expected: NavigationStopSnapshot, guidanceOnly: Boolean = false) :
        this(context, expected, expected.journeyId, guidanceOnly)

    private var error: String? = null
    private var busy = false
    private val explanation = buildList {
        if (guidanceOnly) {
            add("Yol tarifi bitecek.")
            if (expected?.recording == true) add("Yolculuk kaydı sürecek.")
        } else {
            add("Yolculuk kaydı bitecek.")
            if (expected?.sharingLocation == true) add("Konum paylaşımı kapanacak.")
            if (expected?.guidance == true) add("Yol tarifi bitecek.")
        }
    }.joinToString(" ")

    override fun onGetTemplate(): Template = MessageTemplate.Builder(error ?: explanation)
        .setTitle(if (guidanceOnly) "Yol tarifi bitsin mi?" else "Sürüş bitsin mi?").setHeaderAction(Action.BACK)
        .addAction(action(if (busy) "Bitiriliyor…" else "Bitir") {
            if (!busy) {
                busy = true
                safeInvalidate()
                scope.launch {
                    try {
                        val finished = when {
                            expected == null -> navigation.finishUnattachedJourney(requireNotNull(journeyId))
                            guidanceOnly -> navigation.stopGuidance(expected)
                            else -> navigation.finishSession(expected)
                        }
                        if (finished) screenManager.popToRoot() else error = "Yolculuk değişti. Haritaya dönüp yeniden seçin."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Sürüş bitirilemedi." }
                    finally { busy = false; safeInvalidate() }
                }
            }
        }).addAction(action("Geri dön") { screenManager.pop() }).build()
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
            }).addAction(phoneAction("Telefonda aç", "OPEN_WEATHER"))
            .setActionStrip(ActionStrip.Builder().addAction(mapReturn()).build()).build()
    }
}

internal class CarStatisticsScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    private val analysis = PostJourneyAnalysis.get(carContext)
    private var lastCompleted: Journey? = null
    private var journeysLoaded = false
    init {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { analysis.state.collect { safeInvalidate() } }
                launch {
                    DiaryRepository(carContext).journeys.collect { journeys ->
                        lastCompleted = journeys.filter { it.status == JourneyStatus.CONFIRMED && it.endedAt != null }
                            .maxByOrNull { it.endedAt ?: 0L }
                        journeysLoaded = true
                        safeInvalidate()
                    }
                }
                launch {
                    navigation.state.map { it.recording || it.guidance || it.simulation }
                        .distinctUntilChanged().collect { safeInvalidate() }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val active = home.measurementSessionActive
        val journey = lastCompleted
        val state = analysis.state.value
        val stats = carCompletedStatistics(active, journey, state)
        if (stats == null) {
            val reason = when {
                active -> "Kayıt ve yol tarifi bitince ölçümler hazırlanacak."
                !journeysLoaded -> "Yolculuklar yükleniyor…"
                journey == null -> "Tamamlanmış yolculuk yok."
                state.phase == org.iz.navigation.power.AnalysisPhase.READY -> "Bu yolculuğun ölçümleri henüz hazır değil."
                else -> state.label
            }
            return MessageTemplate.Builder(reason).setTitle("Sürüş istatistikleri")
                .setHeaderAction(Action.BACK).addAction(mapReturn()).build()
        }
        fun speed(value: Double?) = value?.let { String.format(Locale.forLanguageTag("tr"), "%.1f km/sa", it) } ?: "Ölçüm yok"
        return PaneTemplate.Builder(Pane.Builder()
            .addRow(row("Süre ve mesafe", "${stats.elapsedMillis / 60_000} dk · ${km(stats.distanceMeters)} km"))
            .addRow(row("Ortalama hız", speed(stats.averageSpeedKmh)))
            .addRow(row("En yüksek örneklenmiş hız", speed(stats.maxSpeedKmh)))
            .addRow(row("Gözlenen / GPS boşluğu", "${stats.observedMillis / 60_000} / ${stats.unobservedMillis / 60_000} dk"))
            .addAction(mapReturn()).build()).setTitle("Son tamamlanan sürüş").setHeaderAction(Action.BACK).build()
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
