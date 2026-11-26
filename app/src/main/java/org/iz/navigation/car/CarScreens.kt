package org.iz.navigation.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
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
import org.iz.navigation.tracking.CarMapLocation
import org.iz.navigation.speed.AutoOsmRoadSpeedResolver
import org.iz.navigation.speed.RoadSpeedState
import org.iz.navigation.weather.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    protected var vehicleChoosing = false
        private set
    protected var vehicleChoiceError: String? = null
        private set
    init { lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
    })
    }
    protected fun isVisibleScreen() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && screenManager.top === this
    protected fun selectManualVehicle(transport: Transport,
        operation: suspend (String?, Boolean, () -> Boolean) -> Unit) {
        if (vehicleChoosing) return
        vehicleChoosing = true
        vehicleChoiceError = null
        val expected = carVehicleStartSnapshot(navigation.state.value, transport)
        safeInvalidate()
        scope.launch {
            try {
                resolveCarStartVehicle(app.garage.vehicles, transport,
                    stillCurrent = { isVisibleScreen() &&
                        expected == carVehicleStartSnapshot(navigation.state.value, transport) },
                    start = { id -> operation(id, false, ::isVisibleScreen) },
                    choose = { showVehiclePicker(transport, expected) { id, visible -> operation(id, true, visible) } })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { vehicleChoiceError = failure.message ?: "Araçlar okunamadı. Yeniden deneyin." }
            finally { vehicleChoosing = false; safeInvalidate() }
        }
    }
    protected fun showVehiclePicker(transport: Transport, expected: CarVehicleStartSnapshot,
        parkedOnly: Boolean = false, journeyId: String? = null,
        operation: suspend (String?, () -> Boolean) -> Unit) {
        screenManager.push(CarVehicleScreen(carContext, transport, expected, parkedOnly, journeyId, operation))
    }
    protected fun planWithVehicle(home: CarHomeScreen, destination: RouteStop) {
        if (navigation.state.value.sessionId != null) {
            home.plan(destination, vehicleChosen = true)
            return
        }
        selectManualVehicle(home.mode) { vehicleId, explicit, visible ->
            if (visible()) home.plan(destination, vehicleId, vehicleChosen = true, vehicleExplicit = explicit)
        }
    }
    protected abstract fun carTemplate(): Template
    protected open fun templateBuilt(template: Template) = Unit
    final override fun onGetTemplate(): Template {
        val template = carTemplate()
        templateBuilt(template)
        return template
    }
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
    val recordingStatus = MutableStateFlow(CarRecordingStatus())
    private val mapLocation = CarMapLocation(context)
    private val mapSpeeds = AutoOsmRoadSpeedResolver(context)
    private val mapVisible = MutableStateFlow(false)
    private var idleSpeed = RoadSpeedState()
    private var homeVisible = false
    private var sessionVisible = false
    private var surfaceVisible = false
    private var mapClosed = false
    private var navigationTemplateVisible = false
    private val fuelAlerts by lazy { CarFuelAlerts(scope, SystemClock::elapsedRealtime,
        visible = { !mapClosed && homeVisible && sessionVisible && surfaceVisible && navigationTemplateVisible &&
            isVisibleScreen() && app.fuelOffers.isAutoMapActive(this) },
        current = { app.fuelOffers.offer.value }, host = NativeCarFuelAlertHost(carContext),
        accept = { app.fuelOffers.accept(it) }, reject = { app.fuelOffers.reject(it) },
        failed = { display { CarToast.makeText(carContext, "İstasyon eklenemedi; yolculuk korunuyor.", CarToast.LENGTH_LONG).show() } }) }
    private var speechStatus = SpeechStatus()
    var selectedMode = Transport.CAR
        set(value) {
            if (field == value) return
            field = value
            app.fuelOffers.setAutoMapFix(this, value, null)
        }
    val mode get() = effectiveCarMode(state, selectedMode)
    val measurementSessionActive get() = state.recording || state.guidance || state.simulation
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { homeVisible = true; syncMapLocation() }
            override fun onStop(owner: LifecycleOwner) {
                homeVisible = false; navigationTemplateVisible = false
                syncFuelVisibility(); syncMapLocation(); renderSurface()
            }
            override fun onDestroy(owner: LifecycleOwner) { closeMapLocation() }
        })
        scope.launch { mapLocation.fix.collect { renderSurface() } }
        scope.launch { mapLocation.recoveryNeeded.collect { safeInvalidate() } }
        scope.launch { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(app.fuelOffers.offer, app.fuelOffers.autoVisible) { _, _ -> Unit }
                .collect { fuelAlerts.update() }
        } }
        scope.launch {
            mapVisible.collectLatest { visible ->
                idleSpeed = RoadSpeedState()
                if (visible) mapSpeeds.states(mapLocation.fix, mode).collect { speed ->
                    idleSpeed = speed
                    renderSurface()
                }
            }
        }
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    // Permission return and GNSS silence must work without a new location emission.
                    syncMapLocation()
                    renderSurface()
                    delay(1_000)
                }
            }
        }
    }
    private fun syncFuelVisibility() {
        app.fuelOffers.setAutoMapVisible(this, !mapClosed && homeVisible && sessionVisible && surfaceVisible && navigationTemplateVisible)
        publishFuelMapFix()
        fuelAlerts.update()
    }
    override fun templateBuilt(template: Template) {
        navigationTemplateVisible = template is NavigationTemplate
        // Deliver the navigation template before asking the host to overlay its native Alert.
        scope.launch { yield(); syncFuelVisibility() }
    }
    private fun syncMapLocation() {
        val visible = !mapClosed && carMapLocationVisible(homeVisible, sessionVisible, surfaceVisible, state)
        if (!visible) idleSpeed = RoadSpeedState()
        mapVisible.value = visible
        mapLocation.setVisible(visible)
    }
    private fun renderSurface() {
        val fix = mapLocation.fix.value.takeIf { mapVisible.value }
        val shown = carMapDisplayState(state, fix, idleSpeed, System.currentTimeMillis(), mode)
        publishFuelMapFix(shown)
        surface.update(shown, carVoiceWarning(state, speechStatus))
    }
    private fun publishFuelMapFix(shown: NavigationState? = null) {
        when {
            mapClosed || !sessionVisible || carUsesNavigationFix(state) -> app.fuelOffers.setAutoMapFix(this, mode, null)
            mapVisible.value -> app.fuelOffers.setAutoMapFix(this, mode, (shown ?: carMapDisplayState(state,
                mapLocation.fix.value, idleSpeed, System.currentTimeMillis(), mode)).fix)
            // A menu hides this source; keep the pending offer's original deadline without acquiring fixes.
        }
    }
    fun setSessionVisible(visible: Boolean) {
        sessionVisible = visible
        syncFuelVisibility()
        syncMapLocation()
        renderSurface()
    }
    fun setSurfaceVisible(visible: Boolean) {
        if (surfaceVisible == visible) return
        surfaceVisible = visible
        syncFuelVisibility()
        syncMapLocation()
        renderSurface()
    }
    fun closeMapLocation() {
        mapClosed = true
        app.fuelOffers.setAutoMapFix(this, mode, null)
        syncFuelVisibility()
        mapVisible.value = false
        idleSpeed = RoadSpeedState()
        mapLocation.close()
    }
    fun refresh(value: NavigationState) {
        state = value
        syncMapLocation()
        renderSurface()
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
    fun preview(route: PlannedRoute, vehicleId: String? = null, vehicleChosen: Boolean = false, vehicleExplicit: Boolean = false) {
        screenManager.push(CarPreviewScreen(carContext, this, surface, route, selectedVehicleId = vehicleId,
            selectedVehicleExplicit = vehicleExplicit,
            vehicleChoiceOwner = carVehicleStartSnapshot(navigation.state.value, route.transport).takeIf { vehicleChosen }))
    }

    fun requestIdleLocation() {
        syncMapLocation()
        renderSurface()
        safeInvalidate()
    }

    fun openTarget(target: NavigationTarget) {
        if (target.coordinate == null) {
            screenManager.push(CarSearchScreen(carContext, this, target.query))
        } else plan(RouteStop(target.query.ifBlank { "Hedef" }.take(300), target.coordinate))
    }

    fun plan(destination: RouteStop, vehicleId: String? = null, vehicleChosen: Boolean = false, vehicleExplicit: Boolean = false) {
        screenManager.push(CarPreviewScreen(carContext, this, surface, destination = destination, selectedVehicleId = vehicleId,
            selectedVehicleExplicit = vehicleExplicit,
            vehicleChoiceOwner = carVehicleStartSnapshot(navigation.state.value, mode).takeIf { vehicleChosen }))
    }

    private suspend fun start(request: CarRecordingRequest, vehicleId: String?, vehicleExplicit: Boolean,
        stillVisible: () -> Boolean, onStarted: () -> Unit) {
        if (recordingStatus.value.starting) return
        recordingStatus.value = CarRecordingStatus(starting = true)
        var startedSession: String? = null
        try {
            val session = navigation.startCarRecording(request.mode,
                request.journeyId.takeIf { request.action == CarRecordingAction.SAVE },
                stillCurrent = { !mapClosed && stillVisible() && request == currentRecordingRequest() })
            startedSession = session
            val current = navigation.state.value.takeIf { it.sessionId == session }
            val remembered = app.garage.unrecordedBinding.value?.takeIf { it.sessionId == session }
            if (request.action == CarRecordingAction.START && current != null &&
                carShouldBindStartVehicle(current, vehicleExplicit, remembered != null))
                app.garage.bindSession(session, current.journey?.id, vehicleId, request.mode, explicitChoice = vehicleExplicit)
            if (stillVisible()) display(onStarted)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val message = if (startedSession != null) "Kayıt başladı; araç atanamadı. Park halinde yeniden atayın."
                else failure.message ?: "Kayıt başlatılamadı. Telefonda İz'i açın."
            recordingStatus.value = CarRecordingStatus(error = message)
            throw IllegalStateException(message, failure)
        }
        finally { recordingStatus.value = recordingStatus.value.copy(starting = false); safeInvalidate() }
    }

    fun currentRecordingRequest(): CarRecordingRequest? {
        val current = navigation.state.value
        return carRecordingRequest(current, effectiveCarMode(current, selectedMode))
    }
    suspend fun changeRecording(request: CarRecordingRequest, stillVisible: () -> Boolean, onStarted: () -> Unit,
        vehicleId: String? = null, vehicleExplicit: Boolean = false) {
        if (mapClosed || !stillVisible() || request != currentRecordingRequest()) {
            recordingStatus.value = CarRecordingStatus(error = "Yolculuk değişti. Haritaya dönüp yeniden seçin.")
            return
        }
        if (request.action != CarRecordingAction.FINISH) { start(request, vehicleId, vehicleExplicit, stillVisible, onStarted); return }
        val current = navigation.state.value
        val expected = NavigationStopSnapshot.from(current)
        val journeyId = current.journey?.id
        if (expected != null) screenManager.push(CarFinishScreen(carContext, expected))
        else if (journeyId != null) screenManager.push(CarFinishScreen(carContext, journeyId))
    }

    private fun mapActions(): ActionStrip = ActionStrip.Builder().addAction(Action.PAN)
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            android.R.drawable.ic_menu_mylocation)).build()).setOnClickListener { display { surface.recenter() } }.build())
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            org.iz.navigation.R.drawable.car_zoom_in)).build()).setOnClickListener { display { surface.zoom(1.0) } }.build())
        .addAction(Action.Builder().setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext,
            org.iz.navigation.R.drawable.car_zoom_out)).build()).setOnClickListener { display { surface.zoom(-1.0) } }.build()).build()

    override fun carTemplate(): Template {
        val locationGranted = ContextCompat.checkSelfPermission(carContext,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (state.guidance && !locationGranted) {
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
        // NavigationTemplate permits a map with no routing information or travel estimate.
        // https://developer.android.com/reference/androidx/car/app/navigation/model/NavigationTemplate
        return NavigationTemplate.Builder().setMapActionStrip(controls)
            .setActionStrip(ActionStrip.Builder().addAction(Action.Builder().setTitle("Yol tarifi")
                .setFlags(Action.FLAG_IS_PERSISTENT).setOnClickListener { display {
                    screenManager.push(CarMenuScreen(carContext, this))
                } }.build()).apply {
                    if (!carUsesNavigationFix(state) && (!locationGranted || mapLocation.recoveryNeeded.value))
                        addAction(phoneAction("Konumu düzelt", "OPEN_LOCATION_SETUP"))
                }.build()).setPanModeListener { display { surface.panMode(it) } }.build()
    }
}

internal class CarMenuScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    init {
        scope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(navigation.state.map { carRecordingRequest(it, effectiveCarMode(it, home.selectedMode)) }
                    .distinctUntilChanged(), home.recordingStatus) { _, _ -> Unit }
                    .collect { safeInvalidate() }
            }
        }
    }
    override fun carTemplate(): Template {
        val request = home.currentRecordingRequest()
        val status = home.recordingStatus.value
        val rows = buildList {
            add(row("Hedef ara", "Ad veya adres") { screenManager.push(CarSearchScreen(carContext, home)) })
            add(row("Kayıtlı ve son yerler", "Ev, İş ve son yerler") { screenManager.push(CarPlacesScreen(carContext, home)) })
            if (request != null) add(row(if (status.starting || vehicleChoosing) "Hazırlanıyor…" else request.action.title,
                vehicleChoiceError ?: status.error.orEmpty()) {
                if (request.action != CarRecordingAction.START) scope.launch {
                    try { home.changeRecording(request, ::isVisibleScreen, { screenManager.popToRoot() }) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { safeInvalidate() } // The shared recording status contains the error.
                } else if (!status.starting) selectManualVehicle(request.mode) { vehicleId, explicit, visible ->
                    home.changeRecording(request, visible, { screenManager.popToRoot() }, vehicleId, explicit)
                }
            })
            addAll(listOf(
                row("Sürüş ve harita", modeLabel(home.mode)) { screenManager.push(CarDrivingOptionsScreen(carContext, home)) },
                row("Hava durumu") { screenManager.push(CarWeatherScreen(carContext)) },
                row("İstatistikler") { screenManager.push(CarStatisticsScreen(carContext, home)) },
            ))
        }
        return ListTemplate.Builder().setTitle("Sürüş menüsü").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply { rows.take(listLimit()).forEach(::addItem) }.build()).build()
    }
}

internal class CarDrivingOptionsScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    init {
        observeVoice()
        scope.launch { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            navigation.state.map { carVehicleStartSnapshot(it, home.mode) }.distinctUntilChanged().collect { safeInvalidate() }
        } }
    }
    override fun carTemplate(): Template {
        val current = navigation.state.value
        val journey = current.journey?.takeIf { it.status == JourneyStatus.CONFIRMED &&
            it.transport in setOf(Transport.CAR, Transport.MOTORCYCLE) && current.sessionId != null &&
            (current.recording || current.guidance) && !current.simulation }
        val expected = carVehicleStartSnapshot(current, journey?.transport ?: home.mode)
        val idleMap = !carUsesNavigationFix(current) && home.mode in setOf(Transport.CAR, Transport.MOTORCYCLE)
        return ListTemplate.Builder().setTitle("Sürüş ve harita").setHeaderAction(Action.BACK)
        .setSingleList(ItemList.Builder()
            .addItem(row("Sürüş türü", modeLabel(home.mode)) { screenManager.push(CarModeScreen(carContext, home)) })
            .addItem(row("Harita görünümü", if (home.cameraMode == DriveCameraMode.ADAPTIVE) "Uyarlamalı" else "Kuzey yukarı 2D") { screenManager.push(CarCameraScreen(carContext, home)) })
            .addItem(row("Hazır rotalar") { screenManager.push(CarRoutesScreen(carContext, home)) })
            .addItem(phoneRow("Ses ayarları", currentVoiceNotice, "OPEN_VOICE_SETTINGS"))
            .addItem(phoneRow("Garaj ve yakıt", "Park halinde telefonda düzenle", "OPEN_GARAGE"))
            .apply { if (journey != null) addItem(Row.Builder().setTitle("Yolculuğa araç ata")
                .addText("Park halinde bu yolculuğun aracını seç")
                .setOnClickListener(ParkedOnlyOnClickListener.create { display {
                    if (isVisibleScreen() && expected == carVehicleStartSnapshot(navigation.state.value, journey.transport))
                        showVehiclePicker(journey.transport, expected,
                            parkedOnly = true, journeyId = journey.id) { vehicleId, visible ->
                            check(visible()) { "Atama ekranı değişti." }
                            app.garage.assignJourney(journey.id, requireNotNull(vehicleId))
                            if (visible()) screenManager.pop()
                        }
                } }).build())
                else if (idleMap) addItem(Row.Builder().setTitle("Haritadaki araç")
                    .addText("Yakıt önerileri için park halinde seç")
                    .setOnClickListener(ParkedOnlyOnClickListener.create { display {
                        val mode = home.mode
                        if (isVisibleScreen() && expected == carVehicleStartSnapshot(navigation.state.value, mode))
                            showVehiclePicker(mode, expected, parkedOnly = true) { vehicleId, visible ->
                                check(visible()) { "Araç seçim ekranı değişti." }
                                app.fuelOffers.selectAutoVehicle(requireNotNull(vehicleId), mode)
                                if (visible()) screenManager.pop()
                            }
                    } }).build()) }.build())
        .setActionStrip(ActionStrip.Builder().addAction(mapReturn()).build()).build()
    }
}

internal class CarCameraScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun carTemplate(): Template = ListTemplate.Builder().setTitle("Araç kamerası").setHeaderAction(Action.BACK)
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
    private var fleet: List<org.iz.navigation.garage.GarageVehicle>? = null
    private var vehicleError: String? = null
    private var page = 0
    init {
        observePrepared()
        scope.launch { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { app.garage.vehicles.collect { fleet = it; vehicleError = null; safeInvalidate() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { vehicleError = failure.message ?: "Araçlar okunamadı."; safeInvalidate() }
        } }
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

    override fun carTemplate(): Template {
        val prepared = preparedNotice()
        val rows = buildList {
            (navigation.state.value.route ?: navigation.lastRoute)?.let { route ->
                route.stops.lastOrNull()?.let { target ->
                    val expected = carVehicleStartSnapshot(navigation.state.value, route.transport)
                    val vehicles = fleet?.filter { it.transport == route.transport }
                    fun open(id: String?, explicit: Boolean = false) { scope.launch {
                        try {
                            check(expected == carVehicleStartSnapshot(navigation.state.value, route.transport) && isVisibleScreen()) {
                                "Yolculuk değişti. Yeniden seçin."
                            }
                            if (expected.sessionId == null) {
                                val latest = app.garage.vehicles.first().filter { it.transport == route.transport }
                                check(latest.map { it.id }.toSet() == vehicles.orEmpty().map { it.id }.toSet()) { "Garaj değişti. Yeniden seçin." }
                            }
                            if (isVisibleScreen()) home.preview(route, id, vehicleChosen = true, vehicleExplicit = explicit)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { vehicleError = failure.message; safeInvalidate() }
                    } }
                    when {
                        expected.sessionId != null -> add(row("Yüklü rota", target.label) { open(null) })
                        vehicleError != null -> add(row("Yüklü rota", vehicleError.orEmpty()))
                        vehicles == null -> add(row("Yüklü rota", "Araçlar yükleniyor…"))
                        vehicles.size <= 1 -> add(row("Yüklü rota", target.label) { open(vehicles.singleOrNull()?.id) })
                        else -> vehicles.forEach { vehicle -> add(row(vehicle.name, "Yüklü rota · ${target.label}") { open(vehicle.id, explicit = true) }) }
                    }
                }
            }
            val draft = WeatherPlanStore(carContext).read()
            val detail = draft?.let { "${it.stops.size} nokta · ${it.stops.last().label}" } ?: "Park halinde planlayıcıyı aç"
            add(phoneRow("Telefondaki plan", detail, "OPEN_ROUTE_PLAN"))
            add(row("Harita hazırlığı", "${prepared.route} ${prepared.map}"))
            if (readError) add(row("Kayıtlı planlar okunamadı"))
            saved.forEach { plan ->
                add(phoneRow(plan.name, "${plan.stops.size} nokta · Telefonda devam et", "OPEN_ROUTE_PLAN", plan.id))
            }
        }
        val limit = listLimit()
        val lastPage = (rows.size - 1).coerceAtLeast(0) / limit
        page = page.coerceIn(0, lastPage)
        return ListTemplate.Builder().setTitle("Hazır rotalar").setHeaderAction(Action.BACK)
            .setSingleList(ItemList.Builder().apply { rows.drop(page * limit).take(limit).forEach(::addItem) }.build())
            .apply { if (lastPage > 0) setActionStrip(ActionStrip.Builder().addAction(action("Diğer") {
                page = (page + 1) % (lastPage + 1); safeInvalidate()
            }).build()) }.build()
    }
}

internal class CarModeScreen(context: CarContext, private val home: CarHomeScreen) : CarScreen(context) {
    override fun carTemplate(): Template {
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
    override fun carTemplate(): Template = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
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
                    else { screenManager.pop(); screenManager.push(CarPlacesScreen(carContext, home)) }
                })
                results.take(listLimit()).forEach { place -> addItem(row(place.name, carSearchSubtitle(place)) {
                    planWithVehicle(home, RouteStop(place.name.take(300), WeatherCoordinate(place.latitude, place.longitude)))
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
    override fun carTemplate(): Template = ListTemplate.Builder().setTitle("Hedef ve yerler")
        .setActionStrip(ActionStrip.Builder().addAction(action("Hedef ara") {
            if (screenManager.screenStack.any { it.marker == "carsearch" }) screenManager.popTo("carsearch")
            else { screenManager.pop(); screenManager.push(CarSearchScreen(carContext, home)) }
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
                    rows.add(row(last.label, "Son hedef") { planWithVehicle(home, last) })
                }
                if (destinations.isEmpty()) rows.add(row("Kayıtlı yerler", message))
                destinations.take((listLimit() - rows.size).coerceAtLeast(0)).forEach { (destination, recent) ->
                    rows.add(row(destination.label, if (recent) "Son ziyaret" else "Kayıtlı yer") { planWithVehicle(home, destination) })
                }
                rows.take(listLimit()).forEach(::addItem)
            }.build())
        }.build()

    private fun openShortcut(kind: DestinationShortcutKind) { scope.launch {
        try {
            val target = library.destinationShortcuts.first().firstOrNull { it.kind == kind }?.let(::carShortcutStop)
                ?: error("Hedef değişmiş veya kaldırılmış. Telefonda yeniden seçin.")
            shortcutError = null
            planWithVehicle(home, target)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { shortcutError = failure.message ?: "Hedef okunamadı"; safeInvalidate() }
    } }
}

internal class CarPreviewScreen(context: CarContext, private val home: CarHomeScreen,
    private val surface: CarMapSurface, initialRoute: PlannedRoute? = null,
    private val destination: RouteStop? = null, private val selectedVehicleId: String? = null,
    private val selectedVehicleExplicit: Boolean = false,
    private val vehicleChoiceOwner: CarVehicleStartSnapshot? = null) : CarScreen(context) {
    // Capture before this screen is pushed; the fifth step must stay a terminal type throughout.
    private val terminalPane = screenManager.screenStack.size >= 4
    private var route = initialRoute
    private var starting = false
    private var planning = false
    private var error: String? = null
    private var selectedVehicleName: String? = null

    init {
        observePrepared(initialRoute)
        if (selectedVehicleId != null) scope.launch { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            app.garage.vehicles.collect { vehicles ->
                selectedVehicleName = vehicles.firstOrNull { it.id == selectedVehicleId }?.name
                safeInvalidate()
            }
        } }
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

    override fun carTemplate(): Template {
        val selected = route
        val pane = Pane.Builder()
        if (selected == null) {
            pane.addRow(row(destination?.label ?: "Hedef", error ?: "Rota hesaplanıyor…"))
            if (!planning && error != null) pane.addAction(action("Yeniden dene", ::prepare))
        } else {
            val information = carRoutePreviewPresentation(selected, System.currentTimeMillis())
            val prepared = preparedNotice(selected)
            pane.addRow(row(selected.stops.last().label,
                "${(selected.durationSeconds / 60).toInt()} dk · ${km(selected.distanceMeters)} km · ${selectedVehicleName ?: modeLabel(selected.transport)}"))
            (error ?: vehicleChoiceError)?.let { pane.addRow(row("İşlem tamamlanamadı", it)) }
            pane.addRow(row("Kaynak ve trafik", information.source))
            information.warnings?.let { pane.addRow(row("Rota uyarıları", it)) }
            pane.addRow(row("Hazırlık", "${prepared.route} ${prepared.map}"))
            pane.addAction(Action.Builder().setTitle(if (starting || vehicleChoosing) "Başlatılıyor…" else "Başlat")
                .setFlags(Action.FLAG_PRIMARY).setEnabled(!starting && !vehicleChoosing).setOnClickListener { display { start(selected) } }.build())
        }
        val content = PaneTemplate.Builder(pane.build()).setTitle("Rota önizlemesi").setHeaderAction(Action.BACK).build()
        return if (terminalPane) content else MapWithContentTemplate.Builder().setContentTemplate(content).build()
    }

    private fun start(selected: PlannedRoute) {
        if (starting || vehicleChoosing) return
        error = null
        suspend fun begin(vehicleId: String?, explicit: Boolean, visible: () -> Boolean, expected: CarVehicleStartSnapshot) {
            starting = true
            safeInvalidate()
            var startedSession: String? = null
            try {
                check(visible()) { "Başlatma ekranı değişti." }
                if (expected.sessionId == null) {
                    val matching = app.garage.vehicles.first().filter { it.transport == selected.transport }
                    check(if (vehicleId == null) matching.isEmpty() else matching.any { it.id == vehicleId }) {
                        "Garaj değişti. Geri dönüp aracı yeniden seçin."
                    }
                }
                val session = navigation.startGuidance(selected, stillCurrent = { visible() &&
                    expected == carVehicleStartSnapshot(navigation.state.value, selected.transport) })
                startedSession = session
                val current = navigation.state.value.takeIf { it.sessionId == session }
                val remembered = app.garage.unrecordedBinding.value?.takeIf { it.sessionId == session }
                if (current != null && carShouldBindStartVehicle(current, explicit, remembered != null))
                    app.garage.bindSession(session, current.journey?.id, vehicleId, selected.transport, explicitChoice = explicit)
                if (visible()) screenManager.popToRoot()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (startedSession != null) throw IllegalStateException("Yol tarifi başladı; araç atanamadı. Park halinde yeniden atayın.", failure)
                throw failure
            }
            finally { starting = false; safeInvalidate() }
        }
        val existing = vehicleChoiceOwner ?: carVehicleStartSnapshot(navigation.state.value, selected.transport)
            .takeIf { it.sessionId != null }
        if (existing != null) scope.launch {
            try { begin(selectedVehicleId, selectedVehicleExplicit, ::isVisibleScreen, existing) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Başlatılamadı."; safeInvalidate() }
        } else selectManualVehicle(selected.transport) { id, explicit, visible ->
            begin(id, explicit, visible, carVehicleStartSnapshot(navigation.state.value, selected.transport))
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

    override fun carTemplate(): Template = MessageTemplate.Builder(error ?: explanation)
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
    override fun carTemplate(): Template {
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

    override fun carTemplate(): Template {
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
